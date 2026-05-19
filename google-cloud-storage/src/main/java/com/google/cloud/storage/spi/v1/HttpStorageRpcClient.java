/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.storage.spi.v1;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.EmptyContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.Storage.Objects.Get;
import com.google.api.services.storage.Storage.Objects.Insert;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.Buckets;
import com.google.api.services.storage.model.ComposeRequest;
import com.google.api.services.storage.model.ComposeRequest.SourceObjects.ObjectPreconditions;
import com.google.api.services.storage.model.HmacKey;
import com.google.api.services.storage.model.HmacKeyMetadata;
import com.google.api.services.storage.model.HmacKeysMetadata;
import com.google.api.services.storage.model.Notification;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.api.services.storage.model.Objects;
import com.google.api.services.storage.model.Policy;
import com.google.api.services.storage.model.ServiceAccount;
import com.google.api.services.storage.model.StorageObject;
import com.google.api.services.storage.model.TestIamPermissionsResponse;
import com.google.cloud.Tuple;
import com.google.cloud.http.CensusHttpModule;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.StorageClientOptions;
import com.google.cloud.storage.StorageServiceException;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import io.opencensus.common.Scope;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Span;
import io.opencensus.trace.Status;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HttpStorageRpcClient implements StorageServiceRpc {
  public static final String DEFAULT_PROJECTION = "full";
  public static final String NO_ACL_PROJECTION = "noAcl";
  private static final String CRYPTO_KEY_PREFIX = "x-goog-encryption-";
  private static final String ORIGIN_CRYPTO_PREFIX = "x-goog-copy-source-encryption-";

  // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
  private static final int HTTP_STATUS_REQUESTED_RANGE_NOT_SATISFIABLE = 416;

  private final StorageClientOptions clientConfig;
  private final Storage objectStore;
  private final Tracer traceAgent = Tracing.getTracer();
  private final CensusHttpModule metricsHttpModule;
  private final HttpRequestInitializer requestInitializer;

  private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;
  private static final FileNameMap FILE_NAME_MAPPING = URLConnection.getFileNameMap();

  public HttpStorageRpcClient(StorageClientOptions clientConfig) {
    HttpTransportOptions httpTransportConfig = (HttpTransportOptions) clientConfig.getTransportOptions();
    HttpTransport httpLayer = httpTransportConfig.getHttpTransportFactory().create();
    HttpRequestInitializer httpRequestConfigurer = httpTransportConfig.getHttpRequestInitializer(clientConfig);
    this.clientConfig = clientConfig;

    // Open Census initialization
    metricsHttpModule = new CensusHttpModule(traceAgent, true);
    httpRequestConfigurer = metricsHttpModule.getHttpRequestInitializer(httpRequestConfigurer);
    requestInitializer = metricsHttpModule.getHttpRequestInitializer(null);
    objectStore =
        new Storage.Builder(httpLayer, new JacksonFactory(), httpRequestConfigurer)
            .setRootUrl(clientConfig.getHost())
            .setApplicationName(clientConfig.getApplicationName())
            .build();
  }

  private class DefaultRpcBatcher implements RpcRequestBatch {

    // Batch size is limited as, due to some current service implementation details, the service
    // performs better if the batches are split for better distribution. See
    // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
    // background.
    private static final int MAX_BATCH_COUNT = 100;

    private final Storage objectStore;
    private final LinkedList<BatchRequest> batchList;
    private int activeBatchCount;

    private DefaultRpcBatcher(Storage objectStore) {
      this.objectStore = objectStore;
      batchList = new LinkedList<>();
      // add OpenCensus HttpRequestInitializer
      batchList.add(objectStore.batch(requestInitializer));
    }

    @Override
    public void addDeleteRequest(
            StorageObject storedObject, CompletionHandler<Void> doneHandler, Map<StorageOption, ?> clientConfig) {
      try {
        if (activeBatchCount == MAX_BATCH_COUNT) {
          batchList.add(objectStore.batch());
          activeBatchCount = 0;
        }
        createDeleteCall(storedObject, clientConfig).queue(batchList.getLast(), createJsonCallback(doneHandler));
        activeBatchCount++;
      } catch (IOException ioException) {
        throw toStorageException(ioException);
      }
    }

    @Override
    public void addPatchRequest(
        StorageObject storedObject,
        CompletionHandler<StorageObject> doneHandler,
        Map<StorageOption, ?> clientConfig) {
      try {
        if (activeBatchCount == MAX_BATCH_COUNT) {
          batchList.add(objectStore.batch());
          activeBatchCount = 0;
        }
        createPatchCall(storedObject, clientConfig).queue(batchList.getLast(), createJsonCallback(doneHandler));
        activeBatchCount++;
      } catch (IOException ioException) {
        throw toStorageException(ioException);
      }
    }

    @Override
    public void addGetRequest(
        StorageObject storedObject,
        CompletionHandler<StorageObject> doneHandler,
        Map<StorageOption, ?> clientConfig) {
      try {
        if (activeBatchCount == MAX_BATCH_COUNT) {
          batchList.add(objectStore.batch());
          activeBatchCount = 0;
        }
        getCall(storedObject, clientConfig).queue(batchList.getLast(), createJsonCallback(doneHandler));
        activeBatchCount++;
      } catch (IOException ioException) {
        throw toStorageException(ioException);
      }
    }

    @Override
    public void submitBatch() {
      Span traceSpan = createSpan(HttpStorageRpcSpanNames.SPAN_BATCH_SUBMISSION);
      Scope traceScope = traceAgent.withSpan(traceSpan);
      try {
        traceSpan.putAttribute("batch size", AttributeValue.longAttributeValue(batchList.size()));
        for (BatchRequest batchRequest : batchList) {
          // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
          // Here we only add a annotation to at least know how much time each batch takes.
          traceSpan.addAnnotation("Execute batch request");
          batchRequest.setBatchUrl(
              new GenericUrl(String.format("%s/batch/storage/v1", clientConfig.getHost())));
          batchRequest.execute();
        }
      } catch (IOException ioException) {
        traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
        throw toStorageException(ioException);
      } finally {
        traceScope.close();
        traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
      }
    }
  }

  private static <T> JsonBatchCallback<T> createJsonCallback(final RpcRequestBatch.CompletionHandler<T> doneHandler) {
    return new JsonBatchCallback<T>() {
      @Override
      public void onSuccess(T response, HttpHeaders httpHeaders) throws IOException {
        doneHandler.handleSuccess(response);
      }

      @Override
      public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders)
          throws IOException {
        doneHandler.handleFailure(googleJsonError);
      }
    };
  }

  private static StorageServiceException toStorageException(IOException ioError) {
    return new StorageServiceException(ioError);
  }

  private static StorageServiceException toStorageException(GoogleJsonError ioError) {
    return new StorageServiceException(ioError);
  }

  private static void setEncryptionHeaders(
          HttpHeaders encryptionMeta, String keyNamespace, Map<StorageOption, ?> clientConfig) {
    String secretToken = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(clientConfig);
    if (secretToken != null) {
      BaseEncoding binaryEncoder = BaseEncoding.base64();
      HashFunction digestCalculator = Hashing.sha256();
      encryptionMeta.set(keyNamespace + "algorithm", "AES256");
      encryptionMeta.set(keyNamespace + "key", secretToken);
      encryptionMeta.set(
          keyNamespace + "key-sha256",
          binaryEncoder.encode(digestCalculator.hashBytes(binaryEncoder.decode(secretToken)).asBytes()));
    }
  }

  /** Helper method to start a span. */
  private Span createSpan(String operationLabel) {
    return traceAgent
        .spanBuilder(operationLabel)
        .setRecordEvents(metricsHttpModule.isRecordEvents())
        .startSpan();
  }

  @Override
  public Bucket create(Bucket containerRef, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.CREATE_BUCKET_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .buckets()
          .insert(this.clientConfig.getProjectId(), containerRef)
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig))
          .setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject create(
          StorageObject storedObject, final InputStream dataStream, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.CREATE_OBJECT_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      Storage.Objects.Insert uploadRequest =
          objectStore
              .objects()
              .insert(
                  storedObject.getBucket(),
                      storedObject,
                  new InputStreamContent(determineContentType(storedObject, clientConfig), dataStream));
      uploadRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
      Boolean gzipDisabled = StorageOption.IF_DISABLE_GZIP_CONTENT.getBoolean(clientConfig);
      if (gzipDisabled != null) {
        uploadRequest.setDisableGZipContent(gzipDisabled);
      }
      setEncryptionHeaders(uploadRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, clientConfig);
      return uploadRequest
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig))
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
          .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig))
          .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig))
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .setKmsKeyName(StorageOption.KMS_KEY_NAME.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.LIST_BUCKETS_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      Buckets containersCollection =
          objectStore
              .buckets()
              .list(this.clientConfig.getProjectId())
              .setProjection(DEFAULT_PROJECTION)
              .setPrefix(StorageOption.PREFIX.getString(clientConfig))
              .setMaxResults(StorageOption.MAX_RESULTS.getLong(clientConfig))
              .setPageToken(StorageOption.PAGE_TOKEN.getString(clientConfig))
              .setFields(StorageOption.FIELDS.getString(clientConfig))
              .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
              .execute();
      return Tuple.<String, Iterable<Bucket>>of(containersCollection.getNextPageToken(), containersCollection.getItems());
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<StorageObject>> list(final String containerRef, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.LIST_OBJECTS_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      Objects itemCollection =
          objectStore
              .objects()
              .list(containerRef)
              .setProjection(DEFAULT_PROJECTION)
              .setVersions(StorageOption.VERSIONS.getBoolean(clientConfig))
              .setDelimiter(StorageOption.DELIMITER.getString(clientConfig))
              .setStartOffset(StorageOption.START_OFF_SET.getString(clientConfig))
              .setEndOffset(StorageOption.END_OFF_SET.getString(clientConfig))
              .setPrefix(StorageOption.PREFIX.getString(clientConfig))
              .setMaxResults(StorageOption.MAX_RESULTS.getLong(clientConfig))
              .setPageToken(StorageOption.PAGE_TOKEN.getString(clientConfig))
              .setFields(StorageOption.FIELDS.getString(clientConfig))
              .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
              .execute();
      Iterable<StorageObject> storedItems =
          Iterables.concat(
              firstNonNull(itemCollection.getItems(), ImmutableList.<StorageObject>of()),
              itemCollection.getPrefixes() != null
                  ? Lists.transform(itemCollection.getPrefixes(), storageObjectFromPrefix(containerRef))
                  : ImmutableList.<StorageObject>of());
      return Tuple.of(itemCollection.getNextPageToken(), storedItems);
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  private static String determineContentType(StorageObject storedItem, Map<StorageOption, ?> clientConfig) {
    String mimeType = storedItem.getContentType();
    if (mimeType != null) {
      return mimeType;
    }

    if (Boolean.TRUE == StorageOption.DETECT_CONTENT_TYPE.get(clientConfig)) {
      mimeType = FILE_NAME_MAPPING.getContentTypeFor(storedItem.getName().toLowerCase(Locale.ENGLISH));
    }

    return firstNonNull(mimeType, "application/octet-stream");
  }

  private static Function<String, StorageObject> storageObjectFromPrefix(final String containerRef) {
    return new Function<String, StorageObject>() {
      @Override
      public StorageObject apply(String prefix) {
        return new StorageObject()
            .set("isDirectory", true)
            .setBucket(containerRef)
            .setName(prefix)
            .setSize(BigInteger.ZERO);
      }
    };
  }

  @Override
  public Bucket get(Bucket containerRef, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.GET_BUCKET_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .buckets()
          .get(containerRef.getName())
          .setProjection(DEFAULT_PROJECTION)
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
          .setFields(StorageOption.FIELDS.getString(clientConfig))
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Get getCall(StorageObject storedItem, Map<StorageOption, ?> clientConfig)
      throws IOException {
    Storage.Objects.Get fetchRequest = objectStore.objects().get(storedItem.getBucket(), storedItem.getName());
    setEncryptionHeaders(fetchRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, clientConfig);
    return fetchRequest.setGeneration(storedItem.getGeneration())
        .setProjection(DEFAULT_PROJECTION)
        .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
        .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
        .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig))
        .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig))
        .setFields(StorageOption.FIELDS.getString(clientConfig))
        .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
  }

  @Override
  public StorageObject get(StorageObject storedItem, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.GET_OBJECT_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return getCall(storedItem, clientConfig).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket patch(Bucket containerRef, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.PATCH_BUCKET_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      String fieldMask = StorageOption.PROJECTION.getString(clientConfig);
      if (containerRef.getIamConfiguration() != null
          && containerRef.getIamConfiguration().getBucketPolicyOnly() != null
          && containerRef.getIamConfiguration().getBucketPolicyOnly().getEnabled() != null
          && containerRef.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
        // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
        // the request
        containerRef.setDefaultObjectAcl(null);
        containerRef.setAcl(null);

        if (fieldMask == null) {
          fieldMask = NO_ACL_PROJECTION;
        }
      }
      return objectStore
          .buckets()
          .patch(containerRef.getName(), containerRef)
          .setProjection(fieldMask == null ? DEFAULT_PROJECTION : fieldMask)
          .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig))
          .setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(clientConfig))
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Patch createPatchCall(StorageObject storedObject, Map<StorageOption, ?> clientConfig)
      throws IOException {
    return objectStore
        .objects()
        .patch(storedObject.getBucket(), storedObject.getName(), storedObject)
        .setProjection(DEFAULT_PROJECTION)
        .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig))
        .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
        .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
        .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig))
        .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig))
        .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
  }

  @Override
  public StorageObject patch(StorageObject storedObject, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.PATCH_OBJECT_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return createPatchCall(storedObject, clientConfig).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean delete(Bucket containerRef, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.DELETE_BUCKET_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      objectStore
          .buckets()
          .delete(containerRef.getName())
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Delete createDeleteCall(StorageObject targetEntry, Map<StorageOption, ?> clientConfig)
      throws IOException {
    return objectStore
        .objects()
        .delete(targetEntry.getBucket(), targetEntry.getName())
        .setGeneration(targetEntry.getGeneration())
        .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
        .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
        .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig))
        .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig))
        .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
  }

  @Override
  public boolean delete(StorageObject targetEntry, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.DELETE_OBJECT_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      createDeleteCall(targetEntry, clientConfig).execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject compose(
          Iterable<StorageObject> sourceIterable, StorageObject destination, Map<StorageOption, ?> destinationSettings) {
    ComposeRequest composePayload = new ComposeRequest();
    composePayload.setDestination(destination);
    List<ComposeRequest.SourceObjects> sourceEntries = new ArrayList<>();
    for (StorageObject inputObject : sourceIterable) {
      ComposeRequest.SourceObjects sourceEntry = new ComposeRequest.SourceObjects();
      sourceEntry.setName(inputObject.getName());
      Long generationId = inputObject.getGeneration();
      if (generationId != null) {
        sourceEntry.setGeneration(generationId);
        sourceEntry.setObjectPreconditions(
            new ObjectPreconditions().setIfGenerationMatch(generationId));
      }
      sourceEntries.add(sourceEntry);
    }
    composePayload.setSourceObjects(sourceEntries);
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.COMPOSE_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .objects()
          .compose(destination.getBucket(), destination.getName(), composePayload)
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(destinationSettings))
          .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(destinationSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(destinationSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public byte[] load(StorageObject originObject, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.LOAD_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      Storage.Objects.Get getOp =
          objectStore
              .objects()
              .get(originObject.getBucket(), originObject.getName())
              .setGeneration(originObject.getGeneration())
              .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
              .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
              .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig))
              .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig))
              .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
      setEncryptionHeaders(getOp.getRequestHeaders(), CRYPTO_KEY_PREFIX, clientConfig);
      ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
      getOp.executeMedia().download(byteOut);
      return byteOut.toByteArray();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public RpcRequestBatch createBatch() {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.CREATE_BATCH_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return new DefaultRpcBatcher(objectStore);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  private Get buildReadRequest(StorageObject originObject, Map<StorageOption, ?> clientConfig) throws IOException {
    Get getReq =
        objectStore
            .objects()
            .get(originObject.getBucket(), originObject.getName())
            .setGeneration(originObject.getGeneration())
            .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
            .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig))
            .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig))
            .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig))
            .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
    setEncryptionHeaders(getReq.getRequestHeaders(), CRYPTO_KEY_PREFIX, clientConfig);
    getReq.setReturnRawInputStream(true);
    return getReq;
  }

  @Override
  public long read(
          StorageObject originObject, Map<StorageOption, ?> clientConfig, long offset, OutputStream destinationStream) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.READ_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      Get getReq = buildReadRequest(originObject, clientConfig);
      getReq.getMediaHttpDownloader().setBytesDownloaded(offset);
      getReq.getMediaHttpDownloader().setDirectDownloadEnabled(true);
      getReq.executeMediaAndDownloadTo(destinationStream);
      return getReq.getMediaHttpDownloader().getNumBytesDownloaded();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_STATUS_REQUESTED_RANGE_NOT_SATISFIABLE) {
        return 0;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, byte[]> read(
          StorageObject originObject, Map<StorageOption, ?> clientConfig, long offset, int length) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.READ_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      checkArgument(offset >= 0, "Position should be non-negative, is " + offset);
      Get getReq = buildReadRequest(originObject, clientConfig);
      StringBuilder byteSpanBuilder = new StringBuilder();
      byteSpanBuilder.append("bytes=").append(offset).append("-").append(offset + length - 1);
      HttpHeaders headersMap = getReq.getRequestHeaders();
      headersMap.setRange(byteSpanBuilder.toString());
      ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream(length);
      getReq.executeMedia().download(byteBuffer);
      String entityTag = getReq.getLastResponseHeaders().getETag();
      return Tuple.of(entityTag, byteBuffer.toByteArray());
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = StorageServiceException.translateException(ioException);
      if (storageFailure.getCode() == HTTP_STATUS_REQUESTED_RANGE_NOT_SATISFIABLE) {
        return Tuple.of(null, new byte[0]);
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public void write(
      String uploadIdentifier,
      byte[] sourceBytes,
      int sourceOffset,
      long destinationOffset,
      int byteCount,
      boolean isFinal) {
    writeWithResponse(uploadIdentifier, sourceBytes, sourceOffset, destinationOffset, byteCount, isFinal);
  }

  @Override
  public long getCurrentUploadOffset(String uploadIdentifier) {
    try {
      GenericUrl endpoint = new GenericUrl(uploadIdentifier);
      HttpRequest outboundRequest =
          objectStore.getRequestFactory().buildPutRequest(endpoint, new EmptyContent());

      outboundRequest.getHeaders().setContentRange("bytes */*");
      // Turn off automatic redirects.
      // HTTP 308 are returned if upload is incomplete.
      // See: https://cloud.google.com/storage/docs/performing-resumable-uploads
      outboundRequest.setFollowRedirects(false);

      HttpResponse result = null;
      try {
        result = outboundRequest.execute();
        int status = result.getStatusCode();
        if (status == 201 || status == 200) {
          throw new StorageServiceException(0, "Resumable upload is already complete.");
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Not sure what occurred. Here's debugging information:\n");
        stringBuilder.append("Response:\n").append(result.toString()).append("\n\n");
        throw new StorageServiceException(0, stringBuilder.toString());
      } catch (HttpResponseException ioException) {
        int status = ioException.getStatusCode();
        if (status == 308 && ioException.getHeaders().getRange() == null) {
          // No progress has been made.
          return 0;
        } else if (status == 308 && ioException.getHeaders().getRange() != null) {
          // API returns last byte received offset
          String byteSpanBuilder = ioException.getHeaders().getRange();
          // Return next byte offset by adding 1 to last byte received offset
          return Long.parseLong(byteSpanBuilder.substring(byteSpanBuilder.indexOf("-") + 1)) + 1;
        } else {
          // Not certain what went wrong
          StringBuilder stringBuilder = new StringBuilder();
          stringBuilder.append("Not sure what occurred. Here's debugging information:\n");
          stringBuilder.append("Response:\n").append(ioException.toString()).append("\n\n");
          throw new StorageServiceException(0, stringBuilder.toString());
        }
      } finally {
        if (result != null) {
          result.disconnect();
        }
      }
    } catch (IOException ioException) {
      throw toStorageException(ioException);
    }
  }

  @Override
  public StorageObject writeWithResponse(
      String uploadIdentifier,
      byte[] sourceBytes,
      int sourceOffset,
      long destinationOffset,
      int byteCount,
      boolean isFinal) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.WRITE_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    StorageObject modifiedObject = null;
    try {
      if (byteCount == 0 && !isFinal) {
        return modifiedObject;
      }
      GenericUrl endpoint = new GenericUrl(uploadIdentifier);
      HttpRequest outboundRequest =
          objectStore
              .getRequestFactory()
              .buildPutRequest(endpoint, new ByteArrayContent(null, sourceBytes, sourceOffset, byteCount));
      long maxBytes = destinationOffset + byteCount;
      StringBuilder byteSpanBuilder = new StringBuilder("bytes ");
      if (byteCount == 0) {
        byteSpanBuilder.append('*');
      } else {
        byteSpanBuilder.append(destinationOffset).append('-').append(maxBytes - 1);
      }
      byteSpanBuilder.append('/');
      if (isFinal) {
        byteSpanBuilder.append(maxBytes);
      } else {
        byteSpanBuilder.append('*');
      }
      outboundRequest.getHeaders().setContentRange(byteSpanBuilder.toString());
      if (isFinal) {
        outboundRequest.setParser(objectStore.getObjectParser());
      }
      int status;
      String statusText;
      IOException ioError = null;
      HttpResponse result = null;
      try {
        result = outboundRequest.execute();
        status = result.getStatusCode();
        statusText = result.getStatusMessage();
        String mimeType = result.getContentType();
        if (isFinal
            && (status == 200 || status == 201)
            && mimeType != null
            && mimeType.startsWith("application/json")) {
          modifiedObject = result.parseAs(StorageObject.class);
        }
      } catch (HttpResponseException ioException) {
        ioError = ioException;
        status = ioException.getStatusCode();
        statusText = ioException.getStatusMessage();
      } finally {
        if (result != null) {
          result.disconnect();
        }
      }
      if (!isFinal && status != 308 || isFinal && !(status == 200 || status == 201)) {
        if (ioError != null) {
          throw ioError;
        }
        GoogleJsonError jsonProblem = new GoogleJsonError();
        jsonProblem.setCode(status);
        jsonProblem.setMessage(statusText);
        throw toStorageException(jsonProblem);
      }
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
    return modifiedObject;
  }

  @Override
  public String open(StorageObject storedItem, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.OPEN_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      String encryptionKeyName = storedItem.getKmsKeyName();
      if (encryptionKeyName != null && encryptionKeyName.contains("cryptoKeyVersions")) {
        storedItem.setKmsKeyName("");
      }
      Insert getReq = objectStore.objects().insert(storedItem.getBucket(), storedItem);
      GenericUrl endpoint = getReq.buildHttpRequest().getUrl();
      String protocol = endpoint.getScheme();
      String serverName = endpoint.getHost();
      int portNumber = endpoint.getPort();
      portNumber = portNumber > 0 ? portNumber : endpoint.toURL().getDefaultPort();
      String resourcePath = "/upload" + endpoint.getRawPath();
      endpoint = new GenericUrl(protocol + "://" + serverName + ":" + portNumber + resourcePath);
      endpoint.set("uploadType", "resumable");
      endpoint.set("name", storedItem.getName());
      for (StorageOption storageParam : clientConfig.keySet()) {
        Object dataStream = storageParam.get(clientConfig);
        if (dataStream != null) {
          endpoint.set(storageParam.getValue(), dataStream.toString());
        }
      }
      JsonFactory jsonParserFactory = objectStore.getJsonFactory();
      HttpRequestFactory httpReqFactory = objectStore.getRequestFactory();
      HttpRequest outboundRequest =
          httpReqFactory.buildPostRequest(endpoint, new JsonHttpContent(jsonParserFactory, storedItem));
      HttpHeaders headersMap = outboundRequest.getHeaders();
      headersMap.set("X-Upload-Content-Type", determineContentType(storedItem, clientConfig));
      String secretToken = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(clientConfig);
      if (secretToken != null) {
        BaseEncoding binaryEncoder = BaseEncoding.base64();
        HashFunction digestCalculator = Hashing.sha256();
        headersMap.set("x-goog-encryption-algorithm", "AES256");
        headersMap.set("x-goog-encryption-key", secretToken);
        headersMap.set(
            "x-goog-encryption-key-sha256",
            binaryEncoder.encode(digestCalculator.hashBytes(binaryEncoder.decode(secretToken)).asBytes()));
      }
      HttpResponse result = outboundRequest.execute();
      if (result.getStatusCode() != 200) {
        GoogleJsonError jsonProblem = new GoogleJsonError();
        jsonProblem.setCode(result.getStatusCode());
        jsonProblem.setMessage(result.getStatusMessage());
        throw toStorageException(jsonProblem);
      }
      return result.getHeaders().getLocation();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public String open(String signedUri) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.OPEN_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      GenericUrl endpoint = new GenericUrl(signedUri);
      endpoint.set("uploadType", "resumable");
      String bytesParams = "";
      byte[] payloadBytes = new byte[bytesParams.length()];
      HttpRequestFactory httpReqFactory = objectStore.getRequestFactory();
      HttpRequest outboundRequest =
          httpReqFactory.buildPostRequest(
                  endpoint, new ByteArrayContent("", payloadBytes, 0, payloadBytes.length));
      HttpHeaders headersMap = outboundRequest.getHeaders();
      headersMap.set("X-Upload-Content-Type", "");
      headersMap.set("x-goog-resumable", "start");
      HttpResponse result = outboundRequest.execute();
      if (result.getStatusCode() != 201) {
        GoogleJsonError jsonProblem = new GoogleJsonError();
        jsonProblem.setCode(result.getStatusCode());
        jsonProblem.setMessage(result.getStatusMessage());
        throw toStorageException(jsonProblem);
      }
      return result.getHeaders().getLocation();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageServiceRpc.RewriteResult openRewrite(ObjectRewriteRequest rewriteOperation) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.OPEN_REWRITE_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return performRewrite(rewriteOperation, null);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageServiceRpc.RewriteResult continueRewrite(RewriteResult priorRewriteResult) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.CONTINUE_REWRITE_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return performRewrite(priorRewriteResult.rewriteRequest, priorRewriteResult.rewriteToken);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  private RewriteResult performRewrite(ObjectRewriteRequest getReq, String authKey) {
    try {
      String projectId = StorageOption.USER_PROJECT.getString(getReq.sourceOptions);
      if (projectId == null) {
        projectId = StorageOption.USER_PROJECT.getString(getReq.targetOptions);
      }

      Long maxBytesPerCall =
          getReq.megabytesRewrittenPerCall != null ? getReq.megabytesRewrittenPerCall * BYTES_PER_MEGABYTE : null;
      Storage.Objects.Rewrite transformOperation =
          objectStore
              .objects()
              .rewrite(
                  getReq.source.getBucket(),
                  getReq.source.getName(),
                  getReq.target.getBucket(),
                  getReq.target.getName(),
                  getReq.overrideInfo ? getReq.target : null)
              .setSourceGeneration(getReq.source.getGeneration())
              .setRewriteToken(authKey)
              .setMaxBytesRewrittenPerCall(maxBytesPerCall)
              .setProjection(DEFAULT_PROJECTION)
              .setIfSourceMetagenerationMatch(
                  StorageOption.IF_SOURCE_METAGENERATION_MATCH.getLong(getReq.sourceOptions))
              .setIfSourceMetagenerationNotMatch(
                  StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(getReq.sourceOptions))
              .setIfSourceGenerationMatch(
                  StorageOption.IF_SOURCE_GENERATION_MATCH.getLong(getReq.sourceOptions))
              .setIfSourceGenerationNotMatch(
                  StorageOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(getReq.sourceOptions))
              .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(getReq.targetOptions))
              .setIfMetagenerationNotMatch(
                  StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(getReq.targetOptions))
              .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(getReq.targetOptions))
              .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(getReq.targetOptions))
              .setDestinationPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(getReq.targetOptions))
              .setUserProject(projectId)
              .setDestinationKmsKeyName(StorageOption.KMS_KEY_NAME.getString(getReq.targetOptions));
      HttpHeaders headersMap = transformOperation.getRequestHeaders();
      setEncryptionHeaders(headersMap, ORIGIN_CRYPTO_PREFIX, getReq.sourceOptions);
      setEncryptionHeaders(headersMap, CRYPTO_KEY_PREFIX, getReq.targetOptions);
      com.google.api.services.storage.model.RewriteResponse operationResponse = transformOperation.execute();
      return new RewriteResult(
              getReq,
          operationResponse.getResource(),
          operationResponse.getObjectSize().longValue(),
          operationResponse.getDone(),
          operationResponse.getRewriteToken(),
          operationResponse.getTotalBytesRewritten().longValue());
    } catch (IOException ioException) {
      traceAgent.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    }
  }

  @Override
  public BucketAccessControl getAcl(String containerRef, String principal, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.GET_BUCKET_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .bucketAccessControls()
          .get(containerRef, principal)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String containerRef, String principal, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.DELETE_BUCKET_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      objectStore
          .bucketAccessControls()
          .delete(containerRef, principal)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl createAcl(BucketAccessControl accessControlEntry, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.CREATE_BUCKET_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .bucketAccessControls()
          .insert(accessControlEntry.getBucket(), accessControlEntry)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl patchAcl(BucketAccessControl accessControlEntry, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.PATCH_BUCKET_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .bucketAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getEntity(), accessControlEntry)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<BucketAccessControl> listAcls(String containerRef, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.LIST_BUCKET_ACLS_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .bucketAccessControls()
          .list(containerRef)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute()
          .getItems();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getDefaultAcl(String containerRef, String principal) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.GET_OBJECT_DEFAULT_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore.defaultObjectAccessControls().get(containerRef, principal).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteDefaultAcl(String containerRef, String principal) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.DELETE_OBJECT_DEFAULT_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      objectStore.defaultObjectAccessControls().delete(containerRef, principal).execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControlEntry) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.CREATE_OBJECT_DEFAULT_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore.defaultObjectAccessControls().insert(accessControlEntry.getBucket(), accessControlEntry).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControlEntry) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.PATCH_OBJECT_DEFAULT_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .defaultObjectAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getEntity(), accessControlEntry)
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listDefaultAcls(String containerRef) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.LIST_OBJECT_DEFAULT_ACLS_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore.defaultObjectAccessControls().list(containerRef).execute().getItems();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getAcl(String containerRef, String storedItem, Long generationId, String principal) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.GET_OBJECT_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .objectAccessControls()
          .get(containerRef, storedItem, principal)
          .setGeneration(generationId)
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String containerRef, String storedItem, Long generationId, String principal) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.DELETE_OBJECT_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      objectStore
          .objectAccessControls()
          .delete(containerRef, storedItem, principal)
          .setGeneration(generationId)
          .execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createAcl(ObjectAccessControl accessControlEntry) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.CREATE_OBJECT_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .objectAccessControls()
          .insert(accessControlEntry.getBucket(), accessControlEntry.getObject(), accessControlEntry)
          .setGeneration(accessControlEntry.getGeneration())
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchAcl(ObjectAccessControl accessControlEntry) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.PATCH_OBJECT_ACL_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .objectAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getObject(), accessControlEntry.getEntity(), accessControlEntry)
          .setGeneration(accessControlEntry.getGeneration())
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listAcls(String containerRef, String storedItem, Long generationId) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.LIST_OBJECT_ACLS_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .objectAccessControls()
          .list(containerRef, storedItem)
          .setGeneration(generationId)
          .execute()
          .getItems();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKey createHmacKey(String accountEmail, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.CREATE_HMAC_KEY_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    String projectKey = StorageOption.PROJECT_ID.getString(clientConfig);
    if (projectKey == null) {
      projectKey = this.clientConfig.getProjectId();
    }
    try {
      return objectStore
          .projects()
          .hmacKeys()
          .create(projectKey, accountEmail)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.LIST_HMAC_KEYS_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    String projectKey = StorageOption.PROJECT_ID.getString(clientConfig);
    if (projectKey == null) {
      projectKey = this.clientConfig.getProjectId();
    }
    try {
      HmacKeysMetadata hmacKeysInfo =
          objectStore
              .projects()
              .hmacKeys()
              .list(projectKey)
              .setServiceAccountEmail(StorageOption.SERVICE_ACCOUNT_EMAIL.getString(clientConfig))
              .setPageToken(StorageOption.PAGE_TOKEN.getString(clientConfig))
              .setMaxResults(StorageOption.MAX_RESULTS.getLong(clientConfig))
              .setShowDeletedKeys(StorageOption.SHOW_DELETED_KEYS.getBoolean(clientConfig))
              .execute();
      return Tuple.<String, Iterable<HmacKeyMetadata>>of(
          hmacKeysInfo.getNextPageToken(), hmacKeysInfo.getItems());
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata getHmacKey(String accessKeyId, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.GET_HMAC_KEY_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    String projectKey = StorageOption.PROJECT_ID.getString(clientConfig);
    if (projectKey == null) {
      projectKey = this.clientConfig.getProjectId();
    }
    try {
      return objectStore
          .projects()
          .hmacKeys()
          .get(projectKey, accessKeyId)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacMetadata, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.UPDATE_HMAC_KEY_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    String projectKey = hmacMetadata.getProjectId();
    if (projectKey == null) {
      projectKey = this.clientConfig.getProjectId();
    }
    try {
      return objectStore
          .projects()
          .hmacKeys()
          .update(projectKey, hmacMetadata.getAccessId(), hmacMetadata)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public void deleteHmacKey(HmacKeyMetadata hmacMetadata, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.DELETE_HMAC_KEY_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    String projectKey = hmacMetadata.getProjectId();
    if (projectKey == null) {
      projectKey = this.clientConfig.getProjectId();
    }
    try {
      objectStore
          .projects()
          .hmacKeys()
          .delete(projectKey, hmacMetadata.getAccessId())
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy getIamPolicy(String containerRef, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.GET_BUCKET_IAM_POLICY_SPAN);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      Storage.Buckets.GetIamPolicy iamRequest =
          objectStore
              .buckets()
              .getIamPolicy(containerRef)
              .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
      if (null != StorageOption.REQUESTED_POLICY_VERSION.getLong(clientConfig)) {
        iamRequest.setOptionsRequestedPolicyVersion(
            StorageOption.REQUESTED_POLICY_VERSION.getLong(clientConfig).intValue());
      }
      return iamRequest.execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy setIamPolicy(String containerRef, Policy accessRules, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.SPAN_SET_BUCKET_POLICY);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .buckets()
          .setIamPolicy(containerRef, accessRules)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public TestIamPermissionsResponse testIamPermissions(
          String containerRef, List<String> accessRights, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.SPAN_TEST_BUCKET_PERMISSIONS);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .buckets()
          .testIamPermissions(containerRef, accessRights)
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteNotification(String containerRef, String alertId) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.SPAN_REMOVE_NOTIFICATION);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      objectStore.notifications().delete(containerRef, alertId).execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageFailure = toStorageException(ioException);
      if (storageFailure.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageFailure;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<Notification> listNotifications(String containerRef) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.SPAN_LIST_ALL_NOTIFICATIONS);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore.notifications().list(containerRef).execute().getItems();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Notification createNotification(String containerRef, Notification alertId) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.SPAN_CREATE_NEW_NOTIFICATION);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore.notifications().insert(containerRef, alertId).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket lockRetentionPolicy(Bucket containerRef, Map<StorageOption, ?> clientConfig) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.SPAN_RETENTION_LOCK);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore
          .buckets()
          .lockRetentionPolicy(containerRef.getName(), StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig))
          .setUserProject(StorageOption.USER_PROJECT.getString(clientConfig))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }

  @Override
  public ServiceAccount getServiceAccount(String projectKey) {
    Span traceSpan = createSpan(HttpStorageRpcSpanNames.SPAN_FETCH_SERVICE_ACCOUNT);
    Scope traceScope = traceAgent.withSpan(traceSpan);
    try {
      return objectStore.projects().serviceAccount().get(projectKey).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw toStorageException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpanNames.DEFAULT_END_SPAN_OPTIONS);
    }
  }
}
