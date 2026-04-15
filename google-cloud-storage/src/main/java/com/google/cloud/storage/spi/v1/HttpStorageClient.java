/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy from the License at
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
import com.google.api.client.http.HttpStatusCodes;
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
  private static final String ENCRYPTION_HEADER_PREFIX = "x-goog-encryption-";
  private static final String SOURCE_ENCRYPTION_HEADER = "x-goog-copy-source-encryption-";

  // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
  private static final int HTTP_STATUS_REQUESTED_RANGE_NOT_SATISFIABLE = 416;

  private final StorageOptions requestParams;
  private final Storage backendStore;
  private final Tracer traceProvider = Tracing.getTracer();
  private final CensusHttpModule telemetryModule;
  private final HttpRequestInitializer batchInit;

  private static final long ONE_MEGABYTE_IN_BYTES = 1024L * 1024L;
  private static final FileNameMap NAME_TO_MIME_MAP = URLConnection.getFileNameMap();

  public HttpStorageClient(StorageOptions requestParams) {
    HttpTransportOptions httpTransportSettings = (HttpTransportOptions) requestParams.getTransportOptions();
    HttpTransport httpTransportClient = httpTransportSettings.getHttpTransportFactory().create();
    HttpRequestInitializer requestInitializer = httpTransportSettings.getHttpRequestInitializer(requestParams);
    this.requestParams = requestParams;

    // Open Census initialization
    telemetryModule = new CensusHttpModule(traceProvider, true);
    requestInitializer = telemetryModule.getHttpRequestInitializer(requestInitializer);
    batchInit = telemetryModule.getHttpRequestInitializer(null);
    backendStore =
        new Storage.Builder(httpTransportClient, new JacksonFactory(), requestInitializer)
            .setRootUrl(requestParams.getHost())
            .setApplicationName(requestParams.getApplicationName())
            .build();
  }

  private class RpcRequestBatch implements RpcBatch {

    // Batch size is limited as, due to some current service implementation details, the service
    // performs better if the batches are split for better distribution. See
    // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
    // background.
    private static final int BATCH_SIZE_LIMIT = 100;

    private final Storage backendStore;
    private final LinkedList<BatchRequest> batchQueue;
    private int activeBatchSize;

    private RpcRequestBatch(Storage backendStore) {
      this.backendStore = backendStore;
      batchQueue = new LinkedList<>();
      // add OpenCensus HttpRequestInitializer
      batchQueue.add(backendStore.batch(batchInit));
    }

    @Override
    public void addDelete(
            StorageObject blobObject, RpcBatch.Callback<Void> resultCallback, Map<RequestOption, ?> requestParams) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          batchQueue.add(backendStore.batch());
          activeBatchSize = 0;
        }
        deleteObjectCall(blobObject, requestParams).queue(batchQueue.getLast(), createJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioCause) {
        throw translateException(ioCause);
      }
    }

    @Override
    public void addPatch(
        StorageObject blobObject,
        RpcBatch.Callback<StorageObject> resultCallback,
        Map<RequestOption, ?> requestParams) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          batchQueue.add(backendStore.batch());
          activeBatchSize = 0;
        }
        patchObject(blobObject, requestParams).queue(batchQueue.getLast(), createJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioCause) {
        throw translateException(ioCause);
      }
    }

    @Override
    public void addGet(
        StorageObject blobObject,
        RpcBatch.Callback<StorageObject> resultCallback,
        Map<RequestOption, ?> requestParams) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          batchQueue.add(backendStore.batch());
          activeBatchSize = 0;
        }
        getCall(blobObject, requestParams).queue(batchQueue.getLast(), createJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioCause) {
        throw translateException(ioCause);
      }
    }

    @Override
    public void submit() {
      Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
      Scope contextCloser = traceProvider.withSpan(operationContext);
      try {
        operationContext.putAttribute("batch size", AttributeValue.longAttributeValue(batchQueue.size()));
        for (BatchRequest requestBatch : batchQueue) {
          // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
          // Here we only add a annotation to at least know how much time each batch takes.
          operationContext.addAnnotation("Execute batch request");
          requestBatch.setBatchUrl(
              new GenericUrl(String.format("%s/batch/storage/v1", requestParams.getHost())));
          requestBatch.execute();
        }
      } catch (IOException ioCause) {
        operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
        throw translateException(ioCause);
      } finally {
        contextCloser.close();
        operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
      }
    }
  }

  private static <T> JsonBatchCallback<T> createJsonBatchCallback(final RpcBatch.Callback<T> resultCallback) {
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

  private static StorageException translateException(IOException ioCause) {
    return new StorageException(ioCause);
  }

  private static StorageException translateException(GoogleJsonError ioCause) {
    return new StorageException(ioCause);
  }

  private static void setEncryptionHeaders(
          HttpHeaders requestHeadersObj, String prefixHeaderKey, Map<RequestOption, ?> requestParams) {
    String encryptionKeyValue = RequestOption.CUSTOMER_SUPPLIED_KEY.getString(requestParams);
    if (encryptionKeyValue != null) {
      BaseEncoding baseEncodingImpl = BaseEncoding.base64();
      HashFunction digestFunction = Hashing.sha256();
      requestHeadersObj.set(prefixHeaderKey + "algorithm", "AES256");
      requestHeadersObj.set(prefixHeaderKey + "key", encryptionKeyValue);
      requestHeadersObj.set(
          prefixHeaderKey + "key-sha256",
          baseEncodingImpl.encode(digestFunction.hashBytes(baseEncodingImpl.decode(encryptionKeyValue)).asBytes()));
    }
  }

  /** Helper method to start a span. */
  private Span beginSpan(String operationName) {
    return traceProvider
        .spanBuilder(operationName)
        .setRecordEvents(telemetryModule.isRecordEvents())
        .startSpan();
  }

  @Override
  public Bucket create(Bucket storageBucket, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .buckets()
          .insert(this.requestParams.getProjectId(), storageBucket)
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestParams))
          .setPredefinedDefaultObjectAcl(RequestOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject create(
          StorageObject blobObject, final InputStream inputContentStream, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      Storage.Objects.Insert insertRequest =
          backendStore
              .objects()
              .insert(
                  blobObject.getBucket(),
                      blobObject,
                  new InputStreamContent(determineContentType(blobObject, requestParams), inputContentStream));
      insertRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
      Boolean gzipDisabledFlag = RequestOption.IF_DISABLE_GZIP_CONTENT.getBoolean(requestParams);
      if (gzipDisabledFlag != null) {
        insertRequest.setDisableGZipContent(gzipDisabledFlag);
      }
      setEncryptionHeaders(insertRequest.getRequestHeaders(), ENCRYPTION_HEADER_PREFIX, requestParams);
      return insertRequest
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestParams))
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
          .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
          .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestParams))
          .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestParams))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .setKmsKeyName(RequestOption.KMS_KEY_NAME.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<Bucket>> list(Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      Buckets bucketCollection =
          backendStore
              .buckets()
              .list(this.requestParams.getProjectId())
              .setProjection(DEFAULT_PROJECTION)
              .setPrefix(RequestOption.PREFIX.getString(requestParams))
              .setMaxResults(RequestOption.MAX_RESULTS.getLong(requestParams))
              .setPageToken(RequestOption.PAGE_TOKEN.getString(requestParams))
              .setFields(RequestOption.FIELDS.getString(requestParams))
              .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
              .execute();
      return Tuple.<String, Iterable<Bucket>>of(bucketCollection.getNextPageToken(), bucketCollection.getItems());
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<StorageObject>> list(final String storageBucket, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      Objects objectCollection =
          backendStore
              .objects()
              .list(storageBucket)
              .setProjection(DEFAULT_PROJECTION)
              .setVersions(RequestOption.VERSIONS.getBoolean(requestParams))
              .setDelimiter(RequestOption.DELIMITER.getString(requestParams))
              .setStartOffset(RequestOption.START_OFF_SET.getString(requestParams))
              .setEndOffset(RequestOption.END_OFF_SET.getString(requestParams))
              .setPrefix(RequestOption.PREFIX.getString(requestParams))
              .setMaxResults(RequestOption.MAX_RESULTS.getLong(requestParams))
              .setPageToken(RequestOption.PAGE_TOKEN.getString(requestParams))
              .setFields(RequestOption.FIELDS.getString(requestParams))
              .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
              .execute();
      Iterable<StorageObject> storedObjectsIterable =
          Iterables.concat(
              firstNonNull(objectCollection.getItems(), ImmutableList.<StorageObject>of()),
              objectCollection.getPrefixes() != null
                  ? Lists.transform(objectCollection.getPrefixes(), storageObjectFromPrefix(storageBucket))
                  : ImmutableList.<StorageObject>of());
      return Tuple.of(objectCollection.getNextPageToken(), storedObjectsIterable);
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private static String determineContentType(StorageObject storageEntity, Map<RequestOption, ?> requestParams) {
    String detectedContentType = storageEntity.getContentType();
    if (detectedContentType != null) {
      return detectedContentType;
    }

    if (Boolean.TRUE == RequestOption.DETECT_CONTENT_TYPE.get(requestParams)) {
      detectedContentType = NAME_TO_MIME_MAP.getContentTypeFor(storageEntity.getName().toLowerCase(Locale.ENGLISH));
    }

    return firstNonNull(detectedContentType, "application/octet-stream");
  }

  private static Function<String, StorageObject> storageObjectFromPrefix(final String storageBucket) {
    return new Function<String, StorageObject>() {
      @Override
      public StorageObject apply(String prefix) {
        return new StorageObject()
            .set("isDirectory", true)
            .setBucket(storageBucket)
            .setName(prefix)
            .setSize(BigInteger.ZERO);
      }
    };
  }

  @Override
  public Bucket get(Bucket storageBucket, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .buckets()
          .get(storageBucket.getName())
          .setProjection(DEFAULT_PROJECTION)
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
          .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
          .setFields(RequestOption.FIELDS.getString(requestParams))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Get getCall(StorageObject storageEntity, Map<RequestOption, ?> requestParams)
      throws IOException {
    Storage.Objects.Get getRequestObj = backendStore.objects().get(storageEntity.getBucket(), storageEntity.getName());
    setEncryptionHeaders(getRequestObj.getRequestHeaders(), ENCRYPTION_HEADER_PREFIX, requestParams);
    return getRequestObj.setGeneration(storageEntity.getGeneration())
        .setProjection(DEFAULT_PROJECTION)
        .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
        .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
        .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestParams))
        .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestParams))
        .setFields(RequestOption.FIELDS.getString(requestParams))
        .setUserProject(RequestOption.USER_PROJECT.getString(requestParams));
  }

  @Override
  public StorageObject get(StorageObject storageEntity, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return getCall(storageEntity, requestParams).execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket patch(Bucket storageBucket, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      String responseProjection = RequestOption.PROJECTION.getString(requestParams);
      if (storageBucket.getIamConfiguration() != null
          && storageBucket.getIamConfiguration().getBucketPolicyOnly() != null
          && storageBucket.getIamConfiguration().getBucketPolicyOnly().getEnabled() != null
          && storageBucket.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
        // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
        // the request
        storageBucket.setDefaultObjectAcl(null);
        storageBucket.setAcl(null);

        if (responseProjection == null) {
          responseProjection = NO_ACL_PROJECTION;
        }
      }
      return backendStore
          .buckets()
          .patch(storageBucket.getName(), storageBucket)
          .setProjection(responseProjection == null ? DEFAULT_PROJECTION : responseProjection)
          .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestParams))
          .setPredefinedDefaultObjectAcl(RequestOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(requestParams))
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
          .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Patch patchObject(StorageObject blobObject, Map<RequestOption, ?> requestParams)
      throws IOException {
    return backendStore
        .objects()
        .patch(blobObject.getBucket(), blobObject.getName(), blobObject)
        .setProjection(DEFAULT_PROJECTION)
        .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestParams))
        .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
        .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
        .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestParams))
        .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestParams))
        .setUserProject(RequestOption.USER_PROJECT.getString(requestParams));
  }

  @Override
  public StorageObject patch(StorageObject blobObject, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return patchObject(blobObject, requestParams).execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean delete(Bucket storageBucket, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      backendStore
          .buckets()
          .delete(storageBucket.getName())
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
          .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
      return true;
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Delete deleteObjectCall(StorageObject blobEntity, Map<RequestOption, ?> requestParams)
      throws IOException {
    return backendStore
        .objects()
        .delete(blobEntity.getBucket(), blobEntity.getName())
        .setGeneration(blobEntity.getGeneration())
        .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
        .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
        .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestParams))
        .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestParams))
        .setUserProject(RequestOption.USER_PROJECT.getString(requestParams));
  }

  @Override
  public boolean delete(StorageObject blobEntity, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      deleteObjectCall(blobEntity, requestParams).execute();
      return true;
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject compose(
          Iterable<StorageObject> sourceObjectsIterable, StorageObject destinationObject, Map<RequestOption, ?> targetRequestParams) {
    ComposeRequest composeRequest = new ComposeRequest();
    composeRequest.setDestination(destinationObject);
    List<ComposeRequest.SourceObjects> sourceList = new ArrayList<>();
    for (StorageObject componentObject : sourceObjectsIterable) {
      ComposeRequest.SourceObjects sourceEntry = new ComposeRequest.SourceObjects();
      sourceEntry.setName(componentObject.getName());
      Long generationId = componentObject.getGeneration();
      if (generationId != null) {
        sourceEntry.setGeneration(generationId);
        sourceEntry.setObjectPreconditions(
            new ObjectPreconditions().setIfGenerationMatch(generationId));
      }
      sourceList.add(sourceEntry);
    }
    composeRequest.setSourceObjects(sourceList);
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .objects()
          .compose(destinationObject.getBucket(), destinationObject.getName(), composeRequest)
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(targetRequestParams))
          .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(targetRequestParams))
          .setUserProject(RequestOption.USER_PROJECT.getString(targetRequestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public byte[] load(StorageObject sourceObject, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      Storage.Objects.Get getCall =
          backendStore
              .objects()
              .get(sourceObject.getBucket(), sourceObject.getName())
              .setGeneration(sourceObject.getGeneration())
              .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
              .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
              .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestParams))
              .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestParams))
              .setUserProject(RequestOption.USER_PROJECT.getString(requestParams));
      setEncryptionHeaders(getCall.getRequestHeaders(), ENCRYPTION_HEADER_PREFIX, requestParams);
      ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
      getCall.executeMedia().download(outputBuffer);
      return outputBuffer.toByteArray();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public RpcBatch createBatch() {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BATCH);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return new RpcRequestBatch(backendStore);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Get buildReadRequest(StorageObject sourceObject, Map<RequestOption, ?> requestParams) throws IOException {
    Get readRequest =
        backendStore
            .objects()
            .get(sourceObject.getBucket(), sourceObject.getName())
            .setGeneration(sourceObject.getGeneration())
            .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
            .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
            .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestParams))
            .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestParams))
            .setUserProject(RequestOption.USER_PROJECT.getString(requestParams));
    setEncryptionHeaders(readRequest.getRequestHeaders(), ENCRYPTION_HEADER_PREFIX, requestParams);
    readRequest.setReturnRawInputStream(true);
    return readRequest;
  }

  @Override
  public long read(
          StorageObject sourceObject, Map<RequestOption, ?> requestParams, long readPosition, OutputStream targetOutputStream) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      Get readRequest = buildReadRequest(sourceObject, requestParams);
      readRequest.getMediaHttpDownloader().setBytesDownloaded(readPosition);
      readRequest.getMediaHttpDownloader().setDirectDownloadEnabled(true);
      readRequest.executeMediaAndDownloadTo(targetOutputStream);
      return readRequest.getMediaHttpDownloader().getNumBytesDownloaded();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_STATUS_REQUESTED_RANGE_NOT_SATISFIABLE) {
        return 0;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, byte[]> read(
          StorageObject sourceObject, Map<RequestOption, ?> requestParams, long readPosition, int byteCount) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      checkArgument(readPosition >= 0, "Position should be non-negative, is " + readPosition);
      Get readRequest = buildReadRequest(sourceObject, requestParams);
      StringBuilder rangeBuilder = new StringBuilder();
      rangeBuilder.append("bytes=").append(readPosition).append("-").append(readPosition + byteCount - 1);
      HttpHeaders requestHeadersObj = readRequest.getRequestHeaders();
      requestHeadersObj.setRange(rangeBuilder.toString());
      ByteArrayOutputStream byteOutBuffer = new ByteArrayOutputStream(byteCount);
      readRequest.executeMedia().download(byteOutBuffer);
      String entityTag = readRequest.getLastResponseHeaders().getETag();
      return Tuple.of(entityTag, byteOutBuffer.toByteArray());
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = StorageException.translate(ioCause);
      if (storageServiceError.getCode() == HTTP_STATUS_REQUESTED_RANGE_NOT_SATISFIABLE) {
        return Tuple.of(null, new byte[0]);
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void write(
      String uploadIdentifier,
      byte[] writeBuffer,
      int writeOffset,
      long destinationOffset,
      int writeLength,
      boolean isFinalChunk) {
    writeWithResponse(uploadIdentifier, writeBuffer, writeOffset, destinationOffset, writeLength, isFinalChunk);
  }

  @Override
  public long getCurrentUploadOffset(String uploadIdentifier) {
    try {
      GenericUrl requestUrlObj = new GenericUrl(uploadIdentifier);
      HttpRequest outgoingRequest =
          backendStore.getRequestFactory().buildPutRequest(requestUrlObj, new EmptyContent());

      outgoingRequest.getHeaders().setContentRange("bytes */*");
      // Turn off automatic redirects.
      // HTTP 308 are returned if upload is incomplete.
      // See: https://cloud.google.com/storage/docs/performing-resumable-uploads
      outgoingRequest.setFollowRedirects(false);

      HttpResponse resultValue = null;
      try {
        resultValue = outgoingRequest.execute();
        int statusCode = resultValue.getStatusCode();
        if (HttpStatusCodes.isSuccess(statusCode)) {
          // Upload completed successfully
          return -1;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Not sure what occurred. Here's debugging information:\n");
        builder.append("Response:\n").append(resultValue.toString()).append("\n\n");
        throw new StorageException(0, builder.toString());
      } catch (HttpResponseException ioCause) {
        int statusCode = ioCause.getStatusCode();
        if (statusCode == 308) {
          if (ioCause.getHeaders().getRange() == null) {
            // No progress has been made.
            return 0;
          }
          // API returns last byte received offset
          String rangeBuilder = ioCause.getHeaders().getRange();
          // Return next byte offset by adding 1 to last byte received offset
          return Long.parseLong(rangeBuilder.substring(rangeBuilder.indexOf("-") + 1)) + 1;
        } else {
          // Something else occurred like a 5xx so translateException and throw.
          throw translateException(ioCause);
        }
      } finally {
        if (resultValue != null) {
          resultValue.disconnect();
        }
      }
    } catch (IOException ioCause) {
      throw translateException(ioCause);
    }
  }

  @Override
  public StorageObject writeWithResponse(
      String uploadIdentifier,
      byte[] writeBuffer,
      int writeOffset,
      long destinationOffset,
      int writeLength,
      boolean isFinalChunk) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    StorageObject updatedObject = null;
    try {
      if (writeLength == 0 && !isFinalChunk) {
        return updatedObject;
      }
      GenericUrl requestUrlObj = new GenericUrl(uploadIdentifier);
      HttpRequest outgoingRequest =
          backendStore
              .getRequestFactory()
              .buildPutRequest(requestUrlObj, new ByteArrayContent(null, writeBuffer, writeOffset, writeLength));
      long byteLimit = destinationOffset + writeLength;
      StringBuilder rangeBuilder = new StringBuilder("bytes ");
      if (writeLength == 0) {
        rangeBuilder.append('*');
      } else {
        rangeBuilder.append(destinationOffset).append('-').append(byteLimit - 1);
      }
      rangeBuilder.append('/');
      if (isFinalChunk) {
        rangeBuilder.append(byteLimit);
      } else {
        rangeBuilder.append('*');
      }
      outgoingRequest.getHeaders().setContentRange(rangeBuilder.toString());
      if (isFinalChunk) {
        outgoingRequest.setParser(backendStore.getObjectParser());
      }
      int statusCode;
      String responseMessage;
      IOException ioCause = null;
      HttpResponse resultValue = null;
      try {
        resultValue = outgoingRequest.execute();
        statusCode = resultValue.getStatusCode();
        responseMessage = resultValue.getStatusMessage();
        String detectedContentType = resultValue.getContentType();
        if (isFinalChunk
            && (statusCode == 200 || statusCode == 201)
            && detectedContentType != null
            && detectedContentType.startsWith("application/json")) {
          updatedObject = resultValue.parseAs(StorageObject.class);
        }
      } catch (HttpResponseException ioError) {
        ioCause = ioError;
        statusCode = ioError.getStatusCode();
        responseMessage = ioError.getStatusMessage();
      } finally {
        if (resultValue != null) {
          resultValue.disconnect();
        }
      }
      if (!isFinalChunk && statusCode != 308 || isFinalChunk && !(statusCode == 200 || statusCode == 201)) {
        if (ioCause != null) {
          throw ioCause;
        }
        GoogleJsonError jsonErrorDetail = new GoogleJsonError();
        jsonErrorDetail.setCode(statusCode);
        jsonErrorDetail.setMessage(responseMessage);
        throw translateException(jsonErrorDetail);
      }
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
    return updatedObject;
  }

  @Override
  public String open(StorageObject storageEntity, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      String encryptionKeyName = storageEntity.getKmsKeyName();
      if (encryptionKeyName != null && encryptionKeyName.contains("cryptoKeyVersions")) {
        storageEntity.setKmsKeyName("");
      }
      Insert readRequest =
          backendStore
              .objects()
              .insert(storageEntity.getBucket(), storageEntity)
              .setName(storageEntity.getName())
              .setProjection(RequestOption.PROJECTION.getString(requestParams))
              .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestParams))
              .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
              .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestParams))
              .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestParams))
              .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestParams))
              .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
              .setKmsKeyName(RequestOption.KMS_KEY_NAME.getString(requestParams));
      GenericUrl requestUrlObj = readRequest.buildHttpRequestUrl();
      requestUrlObj.setRawPath("/upload" + requestUrlObj.getRawPath());
      requestUrlObj.set("uploadType", "resumable");

      JsonFactory jsonParserFactory = backendStore.getJsonFactory();
      HttpRequestFactory httpRequestFactory = backendStore.getRequestFactory();
      HttpRequest outgoingRequest =
          httpRequestFactory.buildPostRequest(requestUrlObj, new JsonHttpContent(jsonParserFactory, storageEntity));
      HttpHeaders requestHeadersObj = outgoingRequest.getHeaders();
      requestHeadersObj.set("X-Upload-Content-Type", determineContentType(storageEntity, requestParams));
      setEncryptionHeaders(requestHeadersObj, "x-goog-encryption-", requestParams);
      HttpResponse resultValue = outgoingRequest.execute();
      if (resultValue.getStatusCode() != 200) {
        GoogleJsonError jsonErrorDetail = new GoogleJsonError();
        jsonErrorDetail.setCode(resultValue.getStatusCode());
        jsonErrorDetail.setMessage(resultValue.getStatusMessage());
        throw translateException(jsonErrorDetail);
      }
      return resultValue.getHeaders().getLocation();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public String open(String signedUrlString) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      GenericUrl requestUrlObj = new GenericUrl(signedUrlString);
      requestUrlObj.set("uploadType", "resumable");
      String bytesParamString = "";
      byte[] byteArrayBuffer = new byte[bytesParamString.length()];
      HttpRequestFactory httpRequestFactory = backendStore.getRequestFactory();
      HttpRequest outgoingRequest =
          httpRequestFactory.buildPostRequest(
                  requestUrlObj, new ByteArrayContent("", byteArrayBuffer, 0, byteArrayBuffer.length));
      HttpHeaders requestHeadersObj = outgoingRequest.getHeaders();
      requestHeadersObj.set("X-Upload-Content-Type", "");
      requestHeadersObj.set("x-goog-resumable", "start");
      HttpResponse resultValue = outgoingRequest.execute();
      if (resultValue.getStatusCode() != 201) {
        GoogleJsonError jsonErrorDetail = new GoogleJsonError();
        jsonErrorDetail.setCode(resultValue.getStatusCode());
        jsonErrorDetail.setMessage(resultValue.getStatusMessage());
        throw translateException(jsonErrorDetail);
      }
      return resultValue.getHeaders().getLocation();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageRpc.RewriteResult openRewrite(ObjectRewriteRequest objectRewriteOp) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return rewriteObject(objectRewriteOp, null);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageRpc.RewriteResult continueRewrite(RewriteResult prevRewriteResult) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return rewriteObject(prevRewriteResult.rewriteRequest, prevRewriteResult.rewriteToken);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private RewriteResult rewriteObject(ObjectRewriteRequest readRequest, String rewriteToken) {
    try {
      String projectIdentifier = RequestOption.USER_PROJECT.getString(readRequest.sourceOptions);
      if (projectIdentifier == null) {
        projectIdentifier = RequestOption.USER_PROJECT.getString(readRequest.targetOptions);
      }

      Long maxRewrittenBytesPerCall =
          readRequest.megabytesRewrittenPerCall != null ? readRequest.megabytesRewrittenPerCall * ONE_MEGABYTE_IN_BYTES : null;
      Storage.Objects.Rewrite rewriteOperation =
          backendStore
              .objects()
              .rewrite(
                  readRequest.source.getBucket(),
                  readRequest.source.getName(),
                  readRequest.target.getBucket(),
                  readRequest.target.getName(),
                  readRequest.overrideInfo ? readRequest.target : null)
              .setSourceGeneration(readRequest.source.getGeneration())
              .setRewriteToken(rewriteToken)
              .setMaxBytesRewrittenPerCall(maxRewrittenBytesPerCall)
              .setProjection(DEFAULT_PROJECTION)
              .setIfSourceMetagenerationMatch(
                  RequestOption.IF_SOURCE_METAGENERATION_MATCH.getLong(readRequest.sourceOptions))
              .setIfSourceMetagenerationNotMatch(
                  RequestOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(readRequest.sourceOptions))
              .setIfSourceGenerationMatch(
                  RequestOption.IF_SOURCE_GENERATION_MATCH.getLong(readRequest.sourceOptions))
              .setIfSourceGenerationNotMatch(
                  RequestOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(readRequest.sourceOptions))
              .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(readRequest.targetOptions))
              .setIfMetagenerationNotMatch(
                  RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(readRequest.targetOptions))
              .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(readRequest.targetOptions))
              .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(readRequest.targetOptions))
              .setDestinationPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(readRequest.targetOptions))
              .setUserProject(projectIdentifier)
              .setDestinationKmsKeyName(RequestOption.KMS_KEY_NAME.getString(readRequest.targetOptions));
      HttpHeaders requestHeadersObj = rewriteOperation.getRequestHeaders();
      setEncryptionHeaders(requestHeadersObj, SOURCE_ENCRYPTION_HEADER, readRequest.sourceOptions);
      setEncryptionHeaders(requestHeadersObj, ENCRYPTION_HEADER_PREFIX, readRequest.targetOptions);
      com.google.api.services.storage.model.RewriteResponse rewriteResult = rewriteOperation.execute();
      return new RewriteResult(
              readRequest,
          rewriteResult.getResource(),
          rewriteResult.getObjectSize().longValue(),
          rewriteResult.getDone(),
          rewriteResult.getRewriteToken(),
          rewriteResult.getTotalBytesRewritten().longValue());
    } catch (IOException ioCause) {
      traceProvider.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    }
  }

  @Override
  public BucketAccessControl getAcl(String storageBucket, String entityId, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .bucketAccessControls()
          .get(storageBucket, entityId)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String storageBucket, String entityId, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      backendStore
          .bucketAccessControls()
          .delete(storageBucket, entityId)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
      return true;
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl createAcl(BucketAccessControl accessControl, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .bucketAccessControls()
          .insert(accessControl.getBucket(), accessControl)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl patchAcl(BucketAccessControl accessControl, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .bucketAccessControls()
          .patch(accessControl.getBucket(), accessControl.getEntity(), accessControl)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<BucketAccessControl> listAcls(String storageBucket, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .bucketAccessControls()
          .list(storageBucket)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute()
          .getItems();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getDefaultAcl(String storageBucket, String entityId) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore.defaultObjectAccessControls().get(storageBucket, entityId).execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteDefaultAcl(String storageBucket, String entityId) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      backendStore.defaultObjectAccessControls().delete(storageBucket, entityId).execute();
      return true;
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControl) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore.defaultObjectAccessControls().insert(accessControl.getBucket(), accessControl).execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControl) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .defaultObjectAccessControls()
          .patch(accessControl.getBucket(), accessControl.getEntity(), accessControl)
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listDefaultAcls(String storageBucket) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore.defaultObjectAccessControls().list(storageBucket).execute().getItems();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getAcl(String storageBucket, String storageEntity, Long generationId, String entityId) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .objectAccessControls()
          .get(storageBucket, storageEntity, entityId)
          .setGeneration(generationId)
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String storageBucket, String storageEntity, Long generationId, String entityId) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      backendStore
          .objectAccessControls()
          .delete(storageBucket, storageEntity, entityId)
          .setGeneration(generationId)
          .execute();
      return true;
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createAcl(ObjectAccessControl accessControl) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .objectAccessControls()
          .insert(accessControl.getBucket(), accessControl.getObject(), accessControl)
          .setGeneration(accessControl.getGeneration())
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchAcl(ObjectAccessControl accessControl) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .objectAccessControls()
          .patch(accessControl.getBucket(), accessControl.getObject(), accessControl.getEntity(), accessControl)
          .setGeneration(accessControl.getGeneration())
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listAcls(String storageBucket, String storageEntity, Long generationId) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .objectAccessControls()
          .list(storageBucket, storageEntity)
          .setGeneration(generationId)
          .execute()
          .getItems();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKey createHmacKey(String serviceAccountAddr, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    String projectIdentifier = RequestOption.PROJECT_ID.getString(requestParams);
    if (projectIdentifier == null) {
      projectIdentifier = this.requestParams.getProjectId();
    }
    try {
      return backendStore
          .projects()
          .hmacKeys()
          .create(projectIdentifier, serviceAccountAddr)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    String projectIdentifier = RequestOption.PROJECT_ID.getString(requestParams);
    if (projectIdentifier == null) {
      projectIdentifier = this.requestParams.getProjectId();
    }
    try {
      HmacKeysMetadata hmacKeysInfo =
          backendStore
              .projects()
              .hmacKeys()
              .list(projectIdentifier)
              .setServiceAccountEmail(RequestOption.SERVICE_ACCOUNT_EMAIL.getString(requestParams))
              .setPageToken(RequestOption.PAGE_TOKEN.getString(requestParams))
              .setMaxResults(RequestOption.MAX_RESULTS.getLong(requestParams))
              .setShowDeletedKeys(RequestOption.SHOW_DELETED_KEYS.getBoolean(requestParams))
              .execute();
      return Tuple.<String, Iterable<HmacKeyMetadata>>of(
          hmacKeysInfo.getNextPageToken(), hmacKeysInfo.getItems());
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata getHmacKey(String accessIdentifier, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    String projectIdentifier = RequestOption.PROJECT_ID.getString(requestParams);
    if (projectIdentifier == null) {
      projectIdentifier = this.requestParams.getProjectId();
    }
    try {
      return backendStore
          .projects()
          .hmacKeys()
          .get(projectIdentifier, accessIdentifier)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyMeta, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    String projectIdentifier = hmacKeyMeta.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.requestParams.getProjectId();
    }
    try {
      return backendStore
          .projects()
          .hmacKeys()
          .update(projectIdentifier, hmacKeyMeta.getAccessId(), hmacKeyMeta)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void deleteHmacKey(HmacKeyMetadata hmacKeyMeta, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    String projectIdentifier = hmacKeyMeta.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.requestParams.getProjectId();
    }
    try {
      backendStore
          .projects()
          .hmacKeys()
          .delete(projectIdentifier, hmacKeyMeta.getAccessId())
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy getIamPolicy(String storageBucket, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      Storage.Buckets.GetIamPolicy iamPolicyRequest =
          backendStore
              .buckets()
              .getIamPolicy(storageBucket)
              .setUserProject(RequestOption.USER_PROJECT.getString(requestParams));
      if (null != RequestOption.REQUESTED_POLICY_VERSION.getLong(requestParams)) {
        iamPolicyRequest.setOptionsRequestedPolicyVersion(
            RequestOption.REQUESTED_POLICY_VERSION.getLong(requestParams).intValue());
      }
      return iamPolicyRequest.execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy setIamPolicy(String storageBucket, Policy iamPolicy, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .buckets()
          .setIamPolicy(storageBucket, iamPolicy)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public TestIamPermissionsResponse testIamPermissions(
          String storageBucket, List<String> permissionList, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .buckets()
          .testIamPermissions(storageBucket, permissionList)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteNotification(String storageBucket, String notificationId) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      backendStore.notifications().delete(storageBucket, notificationId).execute();
      return true;
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      StorageException storageServiceError = translateException(ioCause);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<Notification> listNotifications(String storageBucket) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore.notifications().list(storageBucket).execute().getItems();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Notification createNotification(String storageBucket, Notification notificationId) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore.notifications().insert(storageBucket, notificationId).execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket lockRetentionPolicy(Bucket storageBucket, Map<RequestOption, ?> requestParams) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore
          .buckets()
          .lockRetentionPolicy(storageBucket.getName(), RequestOption.IF_METAGENERATION_MATCH.getLong(requestParams))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestParams))
          .execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ServiceAccount getServiceAccount(String projectIdentifier) {
    Span operationContext = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
    Scope contextCloser = traceProvider.withSpan(operationContext);
    try {
      return backendStore.projects().serviceAccount().get(projectIdentifier).execute();
    } catch (IOException ioCause) {
      operationContext.setStatus(Status.UNKNOWN.withDescription(ioCause.getMessage()));
      throw translateException(ioCause);
    } finally {
      contextCloser.close();
      operationContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }
}
