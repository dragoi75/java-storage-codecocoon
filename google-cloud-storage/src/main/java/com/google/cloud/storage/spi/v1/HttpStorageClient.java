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
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
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

public class HttpStorageClient implements StorageRpc {
  public static final String DEFAULT_PROJECTION = "full";
  public static final String NO_ACL_PROJECTION = "noAcl";
  private static final String DATA_ENCRYPTION_PREFIX = "x-goog-encryption-";
  private static final String SOURCE_DATA_ENCRYPTION_PREFIX = "x-goog-copy-source-encryption-";

  // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
  private static final int HTTP_SC_RANGE_NOT_SATISFIABLE = 416;

  private final StorageOptions configOptions;
  private final Storage storageService;
  private final Tracer telemetryTracer = Tracing.getTracer();
  private final CensusHttpModule censusHttpComponent;
  private final HttpRequestInitializer batchRequestInit;

  private static final long ONE_MEGABYTE = 1024L * 1024L;
  private static final FileNameMap FILE_EXTENSION_MAP = URLConnection.getFileNameMap();

  public HttpStorageClient(StorageOptions configOptions) {
    HttpTransportOptions httpTransportOptions = (HttpTransportOptions) configOptions.getTransportOptions();
    HttpTransport httpTransport = httpTransportOptions.getHttpTransportFactory().create();
    HttpRequestInitializer requestInitializer = httpTransportOptions.getHttpRequestInitializer(configOptions);
    this.configOptions = configOptions;

    // Open Census initialization
    censusHttpComponent = new CensusHttpModule(telemetryTracer, true);
    requestInitializer = censusHttpComponent.getHttpRequestInitializer(requestInitializer);
    batchRequestInit = censusHttpComponent.getHttpRequestInitializer(null);
    storageService =
        new Storage.Builder(httpTransport, new JacksonFactory(), requestInitializer)
            .setRootUrl(configOptions.getHost())
            .setApplicationName(configOptions.getApplicationName())
            .build();
  }

  private class DefaultRpcBatchImpl implements RpcBatch {

    // Batch size is limited as, due to some current service implementation details, the service
    // performs better if the batches are split for better distribution. See
    // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
    // background.
    private static final int BATCH_SIZE_LIMIT = 100;

    private final Storage storageService;
    private final LinkedList<BatchRequest> batchQueue;
    private int activeBatchSize;

    private DefaultRpcBatchImpl(Storage storageService) {
      this.storageService = storageService;
      batchQueue = new LinkedList<>();
      // add OpenCensus HttpRequestInitializer
      batchQueue.add(storageService.batch(batchRequestInit));
    }

    @Override
    public void addDelete(
            StorageObject storageEntity, RpcBatch.Callback<Void> resultCallback, Map<Option, ?> configOptions) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          batchQueue.add(storageService.batch());
          activeBatchSize = 0;
        }
        deleteObjectCall(storageEntity, configOptions).queue(batchQueue.getLast(), toJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioEx) {
        throw translateException(ioEx);
      }
    }

    @Override
    public void addPatch(
        StorageObject storageEntity,
        RpcBatch.Callback<StorageObject> resultCallback,
        Map<Option, ?> configOptions) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          batchQueue.add(storageService.batch());
          activeBatchSize = 0;
        }
        patchObjectCall(storageEntity, configOptions).queue(batchQueue.getLast(), toJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioEx) {
        throw translateException(ioEx);
      }
    }

    @Override
    public void addGet(
        StorageObject storageEntity,
        RpcBatch.Callback<StorageObject> resultCallback,
        Map<Option, ?> configOptions) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          batchQueue.add(storageService.batch());
          activeBatchSize = 0;
        }
        getCall(storageEntity, configOptions).queue(batchQueue.getLast(), toJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioEx) {
        throw translateException(ioEx);
      }
    }

    @Override
    public void submit() {
      Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
      Scope currentScope = telemetryTracer.withSpan(currentSpan);
      try {
        currentSpan.putAttribute("batch size", AttributeValue.longAttributeValue(batchQueue.size()));
        for (BatchRequest batchRequest : batchQueue) {
          // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
          // Here we only add a annotation to at least know how much time each batch takes.
          currentSpan.addAnnotation("Execute batch request");
          batchRequest.setBatchUrl(
              new GenericUrl(String.format("%s/batch/storage/v1", configOptions.getHost())));
          batchRequest.execute();
        }
      } catch (IOException ioEx) {
        currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
        throw translateException(ioEx);
      } finally {
        currentScope.close();
        currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
      }
    }
  }

  private static <T> JsonBatchCallback<T> toJsonBatchCallback(final RpcBatch.Callback<T> resultCallback) {
    return new JsonBatchCallback<T>() {
      @Override
      public void onSuccess(T response, HttpHeaders httpHeaders) throws IOException {
        resultCallback.onSuccess(response);
      }

      @Override
      public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders)
          throws IOException {
        resultCallback.onFailure(googleJsonError);
      }
    };
  }

  private static StorageException translateException(IOException caughtException) {
    return new StorageException(caughtException);
  }

  private static StorageException translateException(GoogleJsonError caughtException) {
    return new StorageException(caughtException);
  }

  private static void setEncryptionHeaders(
          HttpHeaders encryptionHeaders, String encryptionHeaderPrefix, Map<Option, ?> configOptions) {
    String encryptionKey = Option.CUSTOMER_SUPPLIED_KEY.getString(configOptions);
    if (encryptionKey != null) {
      BaseEncoding base64Encoder = BaseEncoding.base64();
      HashFunction checksumFunction = Hashing.sha256();
      encryptionHeaders.set(encryptionHeaderPrefix + "algorithm", "AES256");
      encryptionHeaders.set(encryptionHeaderPrefix + "key", encryptionKey);
      encryptionHeaders.set(
          encryptionHeaderPrefix + "key-sha256",
          base64Encoder.encode(checksumFunction.hashBytes(base64Encoder.decode(encryptionKey)).asBytes()));
    }
  }

  /** Helper method to start a span. */
  private Span beginSpan(String traceSpanName) {
    return telemetryTracer
        .spanBuilder(traceSpanName)
        .setRecordEvents(censusHttpComponent.isRecordEvents())
        .startSpan();
  }

  @Override
  public Bucket create(Bucket bucketName, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .buckets()
          .insert(this.configOptions.getProjectId(), bucketName)
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(Option.PREDEFINED_ACL.getString(configOptions))
          .setPredefinedDefaultObjectAcl(Option.PREDEFINED_DEFAULT_OBJECT_ACL.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject create(
          StorageObject storageEntity, final InputStream requestContent, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      Storage.Objects.Insert uploadRequest =
          storageService
              .objects()
              .insert(
                  storageEntity.getBucket(),
                      storageEntity,
                  new InputStreamContent(getContentType(storageEntity, configOptions), requestContent));
      uploadRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
      Boolean disableGzipFlag = Option.IF_DISABLE_GZIP_CONTENT.getBoolean(configOptions);
      if (disableGzipFlag != null) {
        uploadRequest.setDisableGZipContent(disableGzipFlag);
      }
      setEncryptionHeaders(uploadRequest.getRequestHeaders(), DATA_ENCRYPTION_PREFIX, configOptions);
      return uploadRequest
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(Option.PREDEFINED_ACL.getString(configOptions))
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
          .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
          .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(configOptions))
          .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(configOptions))
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .setKmsKeyName(Option.KMS_KEY_NAME.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<Bucket>> list(Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      Buckets bucketsList =
          storageService
              .buckets()
              .list(this.configOptions.getProjectId())
              .setProjection(DEFAULT_PROJECTION)
              .setPrefix(Option.PREFIX.getString(configOptions))
              .setMaxResults(Option.MAX_RESULTS.getLong(configOptions))
              .setPageToken(Option.PAGE_TOKEN.getString(configOptions))
              .setFields(Option.FIELDS.getString(configOptions))
              .setUserProject(Option.USER_PROJECT.getString(configOptions))
              .execute();
      return Tuple.<String, Iterable<Bucket>>of(bucketsList.getNextPageToken(), bucketsList.getItems());
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<StorageObject>> list(final String bucketName, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      Objects objectsList =
          storageService
              .objects()
              .list(bucketName)
              .setProjection(DEFAULT_PROJECTION)
              .setVersions(Option.VERSIONS.getBoolean(configOptions))
              .setDelimiter(Option.DELIMITER.getString(configOptions))
              .setStartOffset(Option.START_OFF_SET.getString(configOptions))
              .setEndOffset(Option.END_OFF_SET.getString(configOptions))
              .setPrefix(Option.PREFIX.getString(configOptions))
              .setMaxResults(Option.MAX_RESULTS.getLong(configOptions))
              .setPageToken(Option.PAGE_TOKEN.getString(configOptions))
              .setFields(Option.FIELDS.getString(configOptions))
              .setUserProject(Option.USER_PROJECT.getString(configOptions))
              .execute();
      Iterable<StorageObject> objectIterable =
          Iterables.concat(
              firstNonNull(objectsList.getItems(), ImmutableList.<StorageObject>of()),
              objectsList.getPrefixes() != null
                  ? Lists.transform(objectsList.getPrefixes(), storageObjectFromPrefix(bucketName))
                  : ImmutableList.<StorageObject>of());
      return Tuple.of(objectsList.getNextPageToken(), objectIterable);
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private static String getContentType(StorageObject object, Map<Option, ?> configOptions) {
    String detectedContentType = object.getContentType();
    if (detectedContentType != null) {
      return detectedContentType;
    }

    if (Boolean.TRUE == Option.DETECT_CONTENT_TYPE.get(configOptions)) {
      detectedContentType = FILE_EXTENSION_MAP.getContentTypeFor(object.getName().toLowerCase(Locale.ENGLISH));
    }

    return firstNonNull(detectedContentType, "application/octet-stream");
  }

  private static Function<String, StorageObject> storageObjectFromPrefix(final String bucketName) {
    return new Function<String, StorageObject>() {
      @Override
      public StorageObject apply(String prefix) {
        return new StorageObject()
            .set("isDirectory", true)
            .setBucket(bucketName)
            .setName(prefix)
            .setSize(BigInteger.ZERO);
      }
    };
  }

  @Override
  public Bucket get(Bucket bucketName, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .buckets()
          .get(bucketName.getName())
          .setProjection(DEFAULT_PROJECTION)
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
          .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
          .setFields(Option.FIELDS.getString(configOptions))
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Get getCall(StorageObject object, Map<Option, ?> configOptions)
      throws IOException {
    Storage.Objects.Get fetchRequest = storageService.objects().get(object.getBucket(), object.getName());
    setEncryptionHeaders(fetchRequest.getRequestHeaders(), DATA_ENCRYPTION_PREFIX, configOptions);
    return fetchRequest.setGeneration(object.getGeneration())
        .setProjection(DEFAULT_PROJECTION)
        .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
        .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
        .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(configOptions))
        .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(configOptions))
        .setFields(Option.FIELDS.getString(configOptions))
        .setUserProject(Option.USER_PROJECT.getString(configOptions));
  }

  @Override
  public StorageObject get(StorageObject object, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return getCall(object, configOptions).execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket patch(Bucket bucketName, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      String projectionParam = Option.PROJECTION.getString(configOptions);
      if (bucketName.getIamConfiguration() != null
          && bucketName.getIamConfiguration().getBucketPolicyOnly() != null
          && bucketName.getIamConfiguration().getBucketPolicyOnly().getEnabled() != null
          && bucketName.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
        // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
        // the request
        bucketName.setDefaultObjectAcl(null);
        bucketName.setAcl(null);

        if (projectionParam == null) {
          projectionParam = NO_ACL_PROJECTION;
        }
      }
      return storageService
          .buckets()
          .patch(bucketName.getName(), bucketName)
          .setProjection(projectionParam == null ? DEFAULT_PROJECTION : projectionParam)
          .setPredefinedAcl(Option.PREDEFINED_ACL.getString(configOptions))
          .setPredefinedDefaultObjectAcl(Option.PREDEFINED_DEFAULT_OBJECT_ACL.getString(configOptions))
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
          .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Patch patchObjectCall(StorageObject storageEntity, Map<Option, ?> configOptions)
      throws IOException {
    return storageService
        .objects()
        .patch(storageEntity.getBucket(), storageEntity.getName(), storageEntity)
        .setProjection(DEFAULT_PROJECTION)
        .setPredefinedAcl(Option.PREDEFINED_ACL.getString(configOptions))
        .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
        .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
        .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(configOptions))
        .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(configOptions))
        .setUserProject(Option.USER_PROJECT.getString(configOptions));
  }

  @Override
  public StorageObject patch(StorageObject storageEntity, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return patchObjectCall(storageEntity, configOptions).execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean delete(Bucket bucketName, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      storageService
          .buckets()
          .delete(bucketName.getName())
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
          .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
      return true;
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Delete deleteObjectCall(StorageObject blobObject, Map<Option, ?> configOptions)
      throws IOException {
    return storageService
        .objects()
        .delete(blobObject.getBucket(), blobObject.getName())
        .setGeneration(blobObject.getGeneration())
        .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
        .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
        .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(configOptions))
        .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(configOptions))
        .setUserProject(Option.USER_PROJECT.getString(configOptions));
  }

  @Override
  public boolean delete(StorageObject blobObject, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      deleteObjectCall(blobObject, configOptions).execute();
      return true;
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject compose(
          Iterable<StorageObject> sourceList, StorageObject targetObject, Map<Option, ?> targetRequestOptions) {
    ComposeRequest composeRequest = new ComposeRequest();
    composeRequest.setDestination(targetObject);
    List<ComposeRequest.SourceObjects> sourceObjectList = new ArrayList<>();
    for (StorageObject sourceObjectRef : sourceList) {
      ComposeRequest.SourceObjects sourcePart = new ComposeRequest.SourceObjects();
      sourcePart.setName(sourceObjectRef.getName());
      Long objectGeneration = sourceObjectRef.getGeneration();
      if (objectGeneration != null) {
        sourcePart.setGeneration(objectGeneration);
        sourcePart.setObjectPreconditions(
            new ObjectPreconditions().setIfGenerationMatch(objectGeneration));
      }
      sourceObjectList.add(sourcePart);
    }
    composeRequest.setSourceObjects(sourceObjectList);
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .objects()
          .compose(targetObject.getBucket(), targetObject.getName(), composeRequest)
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(targetRequestOptions))
          .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(targetRequestOptions))
          .setUserProject(Option.USER_PROJECT.getString(targetRequestOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public byte[] load(StorageObject from, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      Storage.Objects.Get fetchRequestObj =
          storageService
              .objects()
              .get(from.getBucket(), from.getName())
              .setGeneration(from.getGeneration())
              .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
              .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
              .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(configOptions))
              .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(configOptions))
              .setUserProject(Option.USER_PROJECT.getString(configOptions));
      setEncryptionHeaders(fetchRequestObj.getRequestHeaders(), DATA_ENCRYPTION_PREFIX, configOptions);
      ByteArrayOutputStream outStream = new ByteArrayOutputStream();
      fetchRequestObj.executeMedia().download(outStream);
      return outStream.toByteArray();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public RpcBatch createBatch() {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BATCH);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return new DefaultRpcBatchImpl(storageService);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Get buildReadRequest(StorageObject from, Map<Option, ?> configOptions) throws IOException {
    Get requestObj =
        storageService
            .objects()
            .get(from.getBucket(), from.getName())
            .setGeneration(from.getGeneration())
            .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(configOptions))
            .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(configOptions))
            .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(configOptions))
            .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(configOptions))
            .setUserProject(Option.USER_PROJECT.getString(configOptions));
    setEncryptionHeaders(requestObj.getRequestHeaders(), DATA_ENCRYPTION_PREFIX, configOptions);
    requestObj.setReturnRawInputStream(true);
    return requestObj;
  }

  @Override
  public long read(
          StorageObject from, Map<Option, ?> configOptions, long readPosition, OutputStream destOutputStream) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      Get requestObj = buildReadRequest(from, configOptions);
      requestObj.getMediaHttpDownloader().setBytesDownloaded(readPosition);
      requestObj.getMediaHttpDownloader().setDirectDownloadEnabled(true);
      requestObj.executeMediaAndDownloadTo(destOutputStream);
      return requestObj.getMediaHttpDownloader().getNumBytesDownloaded();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_SC_RANGE_NOT_SATISFIABLE) {
        return 0;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, byte[]> read(
          StorageObject from, Map<Option, ?> configOptions, long readPosition, int bytes) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      checkArgument(readPosition >= 0, "Position should be non-negative, is " + readPosition);
      Get requestObj = buildReadRequest(from, configOptions);
      StringBuilder rangeBuilder = new StringBuilder();
      rangeBuilder.append("bytes=").append(readPosition).append("-").append(readPosition + bytes - 1);
      HttpHeaders outgoingHeaders = requestObj.getRequestHeaders();
      outgoingHeaders.setRange(rangeBuilder.toString());
      ByteArrayOutputStream byteOutput = new ByteArrayOutputStream(bytes);
      requestObj.executeMedia().download(byteOutput);
      String objectEtag = requestObj.getLastResponseHeaders().getETag();
      return Tuple.of(objectEtag, byteOutput.toByteArray());
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = StorageException.translate(ioEx);
      if (storageServiceException.getCode() == HTTP_SC_RANGE_NOT_SATISFIABLE) {
        return Tuple.of(null, new byte[0]);
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void write(
      String uploadIdentifier,
      byte[] writeBuffer,
      int writeBufferOffset,
      long destinationOffset,
      int writeLength,
      boolean isLastChunk) {
    writeWithResponse(uploadIdentifier, writeBuffer, writeBufferOffset, destinationOffset, writeLength, isLastChunk);
  }

  @Override
  public long getCurrentUploadOffset(String uploadIdentifier) {
    try {
      GenericUrl genericUrl = new GenericUrl(uploadIdentifier);
      HttpRequest httpReq =
          storageService.getRequestFactory().buildPutRequest(genericUrl, new EmptyContent());

      httpReq.getHeaders().setContentRange("bytes */*");
      // Turn off automatic redirects.
      // HTTP 308 are returned if upload is incomplete.
      // See: https://cloud.google.com/storage/docs/performing-resumable-uploads
      httpReq.setFollowRedirects(false);

      HttpResponse result = null;
      try {
        result = httpReq.execute();
        int responseCode = result.getStatusCode();
        if (responseCode == 201 || responseCode == 200) {
          throw new StorageException(0, "Resumable upload is already complete.");
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Not sure what occurred. Here's debugging information:\n");
        stringBuilder.append("Response:\n").append(result.toString()).append("\n\n");
        throw new StorageException(0, stringBuilder.toString());
      } catch (HttpResponseException ioEx) {
        int responseCode = ioEx.getStatusCode();
        if (responseCode == 308 && ioEx.getHeaders().getRange() == null) {
          // No progress has been made.
          return 0;
        } else if (responseCode == 308 && ioEx.getHeaders().getRange() != null) {
          // API returns last byte received offset
          String rangeBuilder = ioEx.getHeaders().getRange();
          // Return next byte offset by adding 1 to last byte received offset
          return Long.parseLong(rangeBuilder.substring(rangeBuilder.indexOf("-") + 1)) + 1;
        } else {
          // Not certain what went wrong
          StringBuilder stringBuilder = new StringBuilder();
          stringBuilder.append("Not sure what occurred. Here's debugging information:\n");
          stringBuilder.append("Response:\n").append(ioEx.toString()).append("\n\n");
          throw new StorageException(0, stringBuilder.toString());
        }
      } finally {
        if (result != null) {
          result.disconnect();
        }
      }
    } catch (IOException ioEx) {
      throw translateException(ioEx);
    }
  }

  @Override
  public StorageObject writeWithResponse(
      String uploadIdentifier,
      byte[] writeBuffer,
      int writeBufferOffset,
      long destinationOffset,
      int writeLength,
      boolean isLastChunk) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    StorageObject updatedBlob = null;
    try {
      if (writeLength == 0 && !isLastChunk) {
        return updatedBlob;
      }
      GenericUrl genericUrl = new GenericUrl(uploadIdentifier);
      HttpRequest httpReq =
          storageService
              .getRequestFactory()
              .buildPutRequest(genericUrl, new ByteArrayContent(null, writeBuffer, writeBufferOffset, writeLength));
      long limit = destinationOffset + writeLength;
      StringBuilder rangeBuilder = new StringBuilder("bytes ");
      if (writeLength == 0) {
        rangeBuilder.append('*');
      } else {
        rangeBuilder.append(destinationOffset).append('-').append(limit - 1);
      }
      rangeBuilder.append('/');
      if (isLastChunk) {
        rangeBuilder.append(limit);
      } else {
        rangeBuilder.append('*');
      }
      httpReq.getHeaders().setContentRange(rangeBuilder.toString());
      if (isLastChunk) {
        httpReq.setParser(storageService.getObjectParser());
      }
      int responseCode;
      String message;
      IOException caughtException = null;
      HttpResponse result = null;
      try {
        result = httpReq.execute();
        responseCode = result.getStatusCode();
        message = result.getStatusMessage();
        String detectedContentType = result.getContentType();
        if (isLastChunk
            && (responseCode == 200 || responseCode == 201)
            && detectedContentType != null
            && detectedContentType.startsWith("application/json")) {
          updatedBlob = result.parseAs(StorageObject.class);
        }
      } catch (HttpResponseException ioEx) {
        caughtException = ioEx;
        responseCode = ioEx.getStatusCode();
        message = ioEx.getStatusMessage();
      } finally {
        if (result != null) {
          result.disconnect();
        }
      }
      if (!isLastChunk && responseCode != 308 || isLastChunk && !(responseCode == 200 || responseCode == 201)) {
        if (caughtException != null) {
          throw caughtException;
        }
        GoogleJsonError apiError = new GoogleJsonError();
        apiError.setCode(responseCode);
        apiError.setMessage(message);
        throw translateException(apiError);
      }
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
    return updatedBlob;
  }

  @Override
  public String open(StorageObject object, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      String kmsKeyName = object.getKmsKeyName();
      if (kmsKeyName != null && kmsKeyName.contains("cryptoKeyVersions")) {
        object.setKmsKeyName("");
      }
      Insert requestObj = storageService.objects().insert(object.getBucket(), object);
      GenericUrl genericUrl = requestObj.buildHttpRequest().getUrl();
      String scheme = genericUrl.getScheme();
      String host = genericUrl.getHost();
      int port = genericUrl.getPort();
      port = port > 0 ? port : genericUrl.toURL().getDefaultPort();
      String path = "/upload" + genericUrl.getRawPath();
      genericUrl = new GenericUrl(scheme + "://" + host + ":" + port + path);
      genericUrl.set("uploadType", "resumable");
      genericUrl.set("name", object.getName());
      for (Option option : configOptions.keySet()) {
        Object requestContent = option.get(configOptions);
        if (requestContent != null) {
          genericUrl.set(option.value(), requestContent.toString());
        }
      }
      JsonFactory jsonParserFactory = storageService.getJsonFactory();
      HttpRequestFactory httpFactory = storageService.getRequestFactory();
      HttpRequest httpReq =
          httpFactory.buildPostRequest(genericUrl, new JsonHttpContent(jsonParserFactory, object));
      HttpHeaders outgoingHeaders = httpReq.getHeaders();
      outgoingHeaders.set("X-Upload-Content-Type", getContentType(object, configOptions));
      String encryptionKey = Option.CUSTOMER_SUPPLIED_KEY.getString(configOptions);
      if (encryptionKey != null) {
        BaseEncoding base64Encoder = BaseEncoding.base64();
        HashFunction checksumFunction = Hashing.sha256();
        outgoingHeaders.set("x-goog-encryption-algorithm", "AES256");
        outgoingHeaders.set("x-goog-encryption-key", encryptionKey);
        outgoingHeaders.set(
            "x-goog-encryption-key-sha256",
            base64Encoder.encode(checksumFunction.hashBytes(base64Encoder.decode(encryptionKey)).asBytes()));
      }
      HttpResponse result = httpReq.execute();
      if (result.getStatusCode() != 200) {
        GoogleJsonError apiError = new GoogleJsonError();
        apiError.setCode(result.getStatusCode());
        apiError.setMessage(result.getStatusMessage());
        throw translateException(apiError);
      }
      return result.getHeaders().getLocation();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public String open(String signedUrlString) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      GenericUrl genericUrl = new GenericUrl(signedUrlString);
      genericUrl.set("uploadType", "resumable");
      String byteParamsString = "";
      byte[] dataBytes = new byte[byteParamsString.length()];
      HttpRequestFactory httpFactory = storageService.getRequestFactory();
      HttpRequest httpReq =
          httpFactory.buildPostRequest(
                  genericUrl, new ByteArrayContent("", dataBytes, 0, dataBytes.length));
      HttpHeaders outgoingHeaders = httpReq.getHeaders();
      outgoingHeaders.set("X-Upload-Content-Type", "");
      outgoingHeaders.set("x-goog-resumable", "start");
      HttpResponse result = httpReq.execute();
      if (result.getStatusCode() != 201) {
        GoogleJsonError apiError = new GoogleJsonError();
        apiError.setCode(result.getStatusCode());
        apiError.setMessage(result.getStatusMessage());
        throw translateException(apiError);
      }
      return result.getHeaders().getLocation();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public RewriteResponse openRewrite(RewriteRequest rewriteOpRequest) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return rewriteObject(rewriteOpRequest, null);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public RewriteResponse continueRewrite(RewriteResponse priorRewriteResponse) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return rewriteObject(priorRewriteResponse.rewriteRequest, priorRewriteResponse.rewriteToken);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private RewriteResponse rewriteObject(RewriteRequest requestObj, String rewriteToken) {
    try {
      String projectIdentifier = Option.USER_PROJECT.getString(requestObj.sourceOptions);
      if (projectIdentifier == null) {
        projectIdentifier = Option.USER_PROJECT.getString(requestObj.targetOptions);
      }

      Long maxBytesPerRewrite =
          requestObj.megabytesRewrittenPerCall != null ? requestObj.megabytesRewrittenPerCall * ONE_MEGABYTE : null;
      Storage.Objects.Rewrite rewriteOperation =
          storageService
              .objects()
              .rewrite(
                  requestObj.source.getBucket(),
                  requestObj.source.getName(),
                  requestObj.target.getBucket(),
                  requestObj.target.getName(),
                  requestObj.overrideInfo ? requestObj.target : null)
              .setSourceGeneration(requestObj.source.getGeneration())
              .setRewriteToken(rewriteToken)
              .setMaxBytesRewrittenPerCall(maxBytesPerRewrite)
              .setProjection(DEFAULT_PROJECTION)
              .setIfSourceMetagenerationMatch(
                  Option.IF_SOURCE_METAGENERATION_MATCH.getLong(requestObj.sourceOptions))
              .setIfSourceMetagenerationNotMatch(
                  Option.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(requestObj.sourceOptions))
              .setIfSourceGenerationMatch(
                  Option.IF_SOURCE_GENERATION_MATCH.getLong(requestObj.sourceOptions))
              .setIfSourceGenerationNotMatch(
                  Option.IF_SOURCE_GENERATION_NOT_MATCH.getLong(requestObj.sourceOptions))
              .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(requestObj.targetOptions))
              .setIfMetagenerationNotMatch(
                  Option.IF_METAGENERATION_NOT_MATCH.getLong(requestObj.targetOptions))
              .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(requestObj.targetOptions))
              .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(requestObj.targetOptions))
              .setDestinationPredefinedAcl(Option.PREDEFINED_ACL.getString(requestObj.targetOptions))
              .setUserProject(projectIdentifier)
              .setDestinationKmsKeyName(Option.KMS_KEY_NAME.getString(requestObj.targetOptions));
      HttpHeaders outgoingHeaders = rewriteOperation.getRequestHeaders();
      setEncryptionHeaders(outgoingHeaders, SOURCE_DATA_ENCRYPTION_PREFIX, requestObj.sourceOptions);
      setEncryptionHeaders(outgoingHeaders, DATA_ENCRYPTION_PREFIX, requestObj.targetOptions);
      com.google.api.services.storage.model.RewriteResponse rewriteResult = rewriteOperation.execute();
      return new RewriteResponse(
              requestObj,
          rewriteResult.getResource(),
          rewriteResult.getObjectSize().longValue(),
          rewriteResult.getDone(),
          rewriteResult.getRewriteToken(),
          rewriteResult.getTotalBytesRewritten().longValue());
    } catch (IOException ioEx) {
      telemetryTracer.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    }
  }

  @Override
  public BucketAccessControl getAcl(String bucketName, String entity, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .bucketAccessControls()
          .get(bucketName, entity)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String bucketName, String entity, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      storageService
          .bucketAccessControls()
          .delete(bucketName, entity)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
      return true;
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl createAcl(BucketAccessControl acl, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .bucketAccessControls()
          .insert(acl.getBucket(), acl)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl patchAcl(BucketAccessControl acl, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .bucketAccessControls()
          .patch(acl.getBucket(), acl.getEntity(), acl)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<BucketAccessControl> listAcls(String bucketName, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .bucketAccessControls()
          .list(bucketName)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute()
          .getItems();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getDefaultAcl(String bucketName, String entity) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService.defaultObjectAccessControls().get(bucketName, entity).execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteDefaultAcl(String bucketName, String entity) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      storageService.defaultObjectAccessControls().delete(bucketName, entity).execute();
      return true;
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createDefaultAcl(ObjectAccessControl acl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService.defaultObjectAccessControls().insert(acl.getBucket(), acl).execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchDefaultAcl(ObjectAccessControl acl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .defaultObjectAccessControls()
          .patch(acl.getBucket(), acl.getEntity(), acl)
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listDefaultAcls(String bucketName) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService.defaultObjectAccessControls().list(bucketName).execute().getItems();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getAcl(String bucketName, String object, Long objectGeneration, String entity) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .objectAccessControls()
          .get(bucketName, object, entity)
          .setGeneration(objectGeneration)
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String bucketName, String object, Long objectGeneration, String entity) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      storageService
          .objectAccessControls()
          .delete(bucketName, object, entity)
          .setGeneration(objectGeneration)
          .execute();
      return true;
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createAcl(ObjectAccessControl acl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .objectAccessControls()
          .insert(acl.getBucket(), acl.getObject(), acl)
          .setGeneration(acl.getGeneration())
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchAcl(ObjectAccessControl acl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .objectAccessControls()
          .patch(acl.getBucket(), acl.getObject(), acl.getEntity(), acl)
          .setGeneration(acl.getGeneration())
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listAcls(String bucketName, String object, Long objectGeneration) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .objectAccessControls()
          .list(bucketName, object)
          .setGeneration(objectGeneration)
          .execute()
          .getItems();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKey createHmacKey(String serviceAccountAddr, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    String projectIdentifier = Option.PROJECT_ID.getString(configOptions);
    if (projectIdentifier == null) {
      projectIdentifier = this.configOptions.getProjectId();
    }
    try {
      return storageService
          .projects()
          .hmacKeys()
          .create(projectIdentifier, serviceAccountAddr)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    String projectIdentifier = Option.PROJECT_ID.getString(configOptions);
    if (projectIdentifier == null) {
      projectIdentifier = this.configOptions.getProjectId();
    }
    try {
      HmacKeysMetadata hmacKeysMetadata =
          storageService
              .projects()
              .hmacKeys()
              .list(projectIdentifier)
              .setServiceAccountEmail(Option.SERVICE_ACCOUNT_EMAIL.getString(configOptions))
              .setPageToken(Option.PAGE_TOKEN.getString(configOptions))
              .setMaxResults(Option.MAX_RESULTS.getLong(configOptions))
              .setShowDeletedKeys(Option.SHOW_DELETED_KEYS.getBoolean(configOptions))
              .execute();
      return Tuple.<String, Iterable<HmacKeyMetadata>>of(
          hmacKeysMetadata.getNextPageToken(), hmacKeysMetadata.getItems());
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata getHmacKey(String accessId, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    String projectIdentifier = Option.PROJECT_ID.getString(configOptions);
    if (projectIdentifier == null) {
      projectIdentifier = this.configOptions.getProjectId();
    }
    try {
      return storageService
          .projects()
          .hmacKeys()
          .get(projectIdentifier, accessId)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    String projectIdentifier = hmacKeyMetadata.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.configOptions.getProjectId();
    }
    try {
      return storageService
          .projects()
          .hmacKeys()
          .update(projectIdentifier, hmacKeyMetadata.getAccessId(), hmacKeyMetadata)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void deleteHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    String projectIdentifier = hmacKeyMetadata.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.configOptions.getProjectId();
    }
    try {
      storageService
          .projects()
          .hmacKeys()
          .delete(projectIdentifier, hmacKeyMetadata.getAccessId())
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy getIamPolicy(String bucketName, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      Storage.Buckets.GetIamPolicy iamPolicyGetter =
          storageService
              .buckets()
              .getIamPolicy(bucketName)
              .setUserProject(Option.USER_PROJECT.getString(configOptions));
      if (null != Option.REQUESTED_POLICY_VERSION.getLong(configOptions)) {
        iamPolicyGetter.setOptionsRequestedPolicyVersion(
            Option.REQUESTED_POLICY_VERSION.getLong(configOptions).intValue());
      }
      return iamPolicyGetter.execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy setIamPolicy(String bucketName, Policy policy, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .buckets()
          .setIamPolicy(bucketName, policy)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public TestIamPermissionsResponse testIamPermissions(
          String bucketName, List<String> requestedPermissions, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .buckets()
          .testIamPermissions(bucketName, requestedPermissions)
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteNotification(String bucketName, String notificationId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      storageService.notifications().delete(bucketName, notificationId).execute();
      return true;
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      StorageException storageServiceException = translateException(ioEx);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<Notification> listNotifications(String bucketName) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService.notifications().list(bucketName).execute().getItems();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Notification createNotification(String bucketName, Notification notificationId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService.notifications().insert(bucketName, notificationId).execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket lockRetentionPolicy(Bucket bucketName, Map<Option, ?> configOptions) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService
          .buckets()
          .lockRetentionPolicy(bucketName.getName(), Option.IF_METAGENERATION_MATCH.getLong(configOptions))
          .setUserProject(Option.USER_PROJECT.getString(configOptions))
          .execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ServiceAccount getServiceAccount(String projectIdentifier) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
    Scope currentScope = telemetryTracer.withSpan(currentSpan);
    try {
      return storageService.projects().serviceAccount().get(projectIdentifier).execute();
    } catch (IOException ioEx) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
      throw translateException(ioEx);
    } finally {
      currentScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }
}
