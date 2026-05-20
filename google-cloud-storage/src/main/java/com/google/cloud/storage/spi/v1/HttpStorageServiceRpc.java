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
import com.google.cloud.storage.StorageOperationException;
import com.google.cloud.storage.StorageSettings;
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
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class HttpStorageServiceRpc implements StorageRpcClient {
  public static final String DEFAULT_PROJECTION = "full";
  public static final String NO_ACL_PROJECTION = "noAcl";
  private static final String CRYPTO_KEY_PREFIX = "x-goog-encryption-";
  private static final String ORIGIN_CRYPTO_KEY_PREFIX = "x-goog-copy-source-encryption-";

  // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
  private static final int SC_RANGE_NOT_SATISFIABLE = 416;

  private final StorageSettings storageSettings;
  private final Storage backendStore;
  private final Tracer telemetry = Tracing.getTracer();
  private final CensusHttpModule httpTelemetryModule;
  private final HttpRequestInitializer requestInitializer;

  private static final long BYTES_PER_MB = 1024L * 1024L;

  public HttpStorageServiceRpc(StorageSettings storageSettings) {
    HttpTransportOptions httpTransportConfig = (HttpTransportOptions) storageSettings.getTransportOptions();
    HttpTransport httpLayer = httpTransportConfig.getHttpTransportFactory().create();
    HttpRequestInitializer requestInit = httpTransportConfig.getHttpRequestInitializer(storageSettings);
    this.storageSettings = storageSettings;

    // Open Census initialization
    httpTelemetryModule = new CensusHttpModule(telemetry, true);
    requestInit = httpTelemetryModule.getHttpRequestInitializer(requestInit);
    requestInitializer = httpTelemetryModule.getHttpRequestInitializer(null);
    HttpStorageRpcSpans.registerAllSpanNamesForCollection();

    backendStore =
        new Storage.Builder(httpLayer, new JacksonFactory(), requestInit)
            .setRootUrl(storageSettings.getHost())
            .setApplicationName(storageSettings.getApplicationName())
            .build();
  }

  private class DefaultRpcBatcher implements RpcRequestBatch {

    // Batch size is limited as, due to some current service implementation details, the service
    // performs better if the batches are split for better distribution. See
    // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
    // background.
    private static final int BATCH_SIZE_LIMIT = 100;

    private final Storage backendStore;
    private final LinkedList<BatchRequest> requestQueue;
    private int currentBatchCount;

    private DefaultRpcBatcher(Storage backendStore) {
      this.backendStore = backendStore;
      requestQueue = new LinkedList<>();
      // add OpenCensus HttpRequestInitializer
      requestQueue.add(backendStore.batch(requestInitializer));
    }

    @Override
    public void addDeleteRequest(
            StorageObject objectInfo, ResultCallback<Void> resultHandler, Map<StorageOption, ?> storageSettings) {
      try {
        if (currentBatchCount == BATCH_SIZE_LIMIT) {
          requestQueue.add(backendStore.batch());
          currentBatchCount = 0;
        }
        createDeleteRequest(objectInfo, storageSettings).queue(requestQueue.getLast(), asJsonCallback(resultHandler));
        currentBatchCount++;
      } catch (IOException ioe) {
        throw translateException(ioe);
      }
    }

    @Override
    public void addPatchRequest(
        StorageObject objectInfo,
        ResultCallback<StorageObject> resultHandler,
        Map<StorageOption, ?> storageSettings) {
      try {
        if (currentBatchCount == BATCH_SIZE_LIMIT) {
          requestQueue.add(backendStore.batch());
          currentBatchCount = 0;
        }
        createPatchRequest(objectInfo, storageSettings).queue(requestQueue.getLast(), asJsonCallback(resultHandler));
        currentBatchCount++;
      } catch (IOException ioe) {
        throw translateException(ioe);
      }
    }

    @Override
    public void addGetRequest(
        StorageObject objectInfo,
        ResultCallback<StorageObject> resultHandler,
        Map<StorageOption, ?> storageSettings) {
      try {
        if (currentBatchCount == BATCH_SIZE_LIMIT) {
          requestQueue.add(backendStore.batch());
          currentBatchCount = 0;
        }
        getCall(objectInfo, storageSettings).queue(requestQueue.getLast(), asJsonCallback(resultHandler));
        currentBatchCount++;
      } catch (IOException ioe) {
        throw translateException(ioe);
      }
    }

    @Override
    public void submitBatch() {
      Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
      Scope scopeHandle = telemetry.withSpan(traceCtx);
      try {
        traceCtx.putAttribute("batch size", AttributeValue.longAttributeValue(requestQueue.size()));
        for (BatchRequest requestBatch : requestQueue) {
          // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
          // Here we only add a annotation to at least know how much time each batch takes.
          traceCtx.addAnnotation("Execute batch request");
          requestBatch.setBatchUrl(
              new GenericUrl(String.format("%s/batch/storage/v1", storageSettings.getHost())));
          requestBatch.execute();
        }
      } catch (IOException ioe) {
        traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
        throw translateException(ioe);
      } finally {
        scopeHandle.close();
        traceCtx.end();
      }
    }
  }

  private static <T> JsonBatchCallback<T> asJsonCallback(final RpcRequestBatch.ResultCallback<T> resultHandler) {
    return new JsonBatchCallback<T>() {
      @Override
      public void onSuccess(T response, HttpHeaders httpHeaders) throws IOException {
        resultHandler.handleSuccess(response);
      }

      @Override
      public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders)
          throws IOException {
        resultHandler.handleFailure(googleJsonError);
      }
    };
  }

  private static StorageOperationException translateException(IOException ioException) {
    return new StorageOperationException(ioException);
  }

  private static StorageOperationException translateException(GoogleJsonError ioException) {
    return new StorageOperationException(ioException);
  }

  private static void setEncryptionHeaders(
          HttpHeaders httpFields, String encryptionKeyMarker, Map<StorageOption, ?> storageSettings) {
    String secretId = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(storageSettings);
    if (secretId != null) {
      BaseEncoding binaryEncoder = BaseEncoding.base64();
      HashFunction digestFunction = Hashing.sha256();
      httpFields.set(encryptionKeyMarker + "algorithm", "AES256");
      httpFields.set(encryptionKeyMarker + "key", secretId);
      httpFields.set(
          encryptionKeyMarker + "key-sha256",
          binaryEncoder.encode(digestFunction.hashBytes(binaryEncoder.decode(secretId)).asBytes()));
    }
  }

  /** Helper method to start a span. */
  private Span beginSpan(String operationName) {
    return telemetry
        .spanBuilder(operationName)
        .setRecordEvents(httpTelemetryModule.isRecordEvents())
        .startSpan();
  }

  @Override
  public Bucket create(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .buckets()
          .insert(this.storageSettings.getProjectId(), container)
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings))
          .setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public StorageObject create(
          StorageObject objectInfo, final InputStream inputStream, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      Storage.Objects.Insert uploadRequest =
          backendStore
              .objects()
              .insert(
                  objectInfo.getBucket(),
                      objectInfo,
                  new InputStreamContent(objectInfo.getContentType(), inputStream));
      uploadRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
      Boolean disableCompression = StorageOption.IF_DISABLE_GZIP_CONTENT.getBoolean(storageSettings);
      if (disableCompression != null) {
        uploadRequest.setDisableGZipContent(disableCompression);
      }
      setEncryptionHeaders(uploadRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, storageSettings);
      return uploadRequest
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings))
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
          .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .setKmsKeyName(StorageOption.KMS_KEY_NAME.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      Buckets collectionPage =
          backendStore
              .buckets()
              .list(this.storageSettings.getProjectId())
              .setProjection(DEFAULT_PROJECTION)
              .setPrefix(StorageOption.PREFIX.getString(storageSettings))
              .setMaxResults(StorageOption.MAX_RESULTS.getLong(storageSettings))
              .setPageToken(StorageOption.PAGE_TOKEN.getString(storageSettings))
              .setFields(StorageOption.FIELDS.getString(storageSettings))
              .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
              .execute();
      return Tuple.<String, Iterable<Bucket>>of(collectionPage.getNextPageToken(), collectionPage.getItems());
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Tuple<String, Iterable<StorageObject>> list(final String container, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      Objects objectPage =
          backendStore
              .objects()
              .list(container)
              .setProjection(DEFAULT_PROJECTION)
              .setVersions(StorageOption.VERSIONS.getBoolean(storageSettings))
              .setDelimiter(StorageOption.DELIMITER.getString(storageSettings))
              .setPrefix(StorageOption.PREFIX.getString(storageSettings))
              .setMaxResults(StorageOption.MAX_RESULTS.getLong(storageSettings))
              .setPageToken(StorageOption.PAGE_TOKEN.getString(storageSettings))
              .setFields(StorageOption.FIELDS.getString(storageSettings))
              .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
              .execute();
      Iterable<StorageObject> storedItems =
          Iterables.concat(
              firstNonNull(objectPage.getItems(), ImmutableList.<StorageObject>of()),
              objectPage.getPrefixes() != null
                  ? Lists.transform(objectPage.getPrefixes(), storageObjectFromPrefix(container))
                  : ImmutableList.<StorageObject>of());
      return Tuple.of(objectPage.getNextPageToken(), storedItems);
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  private static Function<String, StorageObject> storageObjectFromPrefix(final String container) {
    return new Function<String, StorageObject>() {
      @Override
      public StorageObject apply(String prefix) {
        return new StorageObject()
            .set("isDirectory", true)
            .setBucket(container)
            .setName(prefix)
            .setSize(BigInteger.ZERO);
      }
    };
  }

  @Override
  public Bucket get(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .buckets()
          .get(container.getName())
          .setProjection(DEFAULT_PROJECTION)
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setFields(StorageOption.FIELDS.getString(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  private Storage.Objects.Get getCall(StorageObject storageBlob, Map<StorageOption, ?> storageSettings)
      throws IOException {
    Storage.Objects.Get fetchRequest = backendStore.objects().get(storageBlob.getBucket(), storageBlob.getName());
    setEncryptionHeaders(fetchRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, storageSettings);
    return fetchRequest.setGeneration(storageBlob.getGeneration())
        .setProjection(DEFAULT_PROJECTION)
        .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
        .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
        .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
        .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
        .setFields(StorageOption.FIELDS.getString(storageSettings))
        .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
  }

  @Override
  public StorageObject get(StorageObject storageBlob, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return getCall(storageBlob, storageSettings).execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Bucket patch(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      String fieldsSelector = StorageOption.PROJECTION.getString(storageSettings);
      if (container.getIamConfiguration() != null
          && container.getIamConfiguration().getBucketPolicyOnly() != null
          && container.getIamConfiguration().getBucketPolicyOnly().getEnabled() != null
          && container.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
        // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
        // the request
        container.setDefaultObjectAcl(null);
        container.setAcl(null);

        if (fieldsSelector == null) {
          fieldsSelector = NO_ACL_PROJECTION;
        }
      }
      return backendStore
          .buckets()
          .patch(container.getName(), container)
          .setProjection(fieldsSelector == null ? DEFAULT_PROJECTION : fieldsSelector)
          .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings))
          .setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(storageSettings))
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  private Storage.Objects.Patch createPatchRequest(StorageObject objectInfo, Map<StorageOption, ?> storageSettings)
      throws IOException {
    return backendStore
        .objects()
        .patch(objectInfo.getBucket(), objectInfo.getName(), objectInfo)
        .setProjection(DEFAULT_PROJECTION)
        .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings))
        .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
        .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
        .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
        .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
        .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
  }

  @Override
  public StorageObject patch(StorageObject objectInfo, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return createPatchRequest(objectInfo, storageSettings).execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public boolean delete(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      backendStore
          .buckets()
          .delete(container.getName())
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
      return true;
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  private Storage.Objects.Delete createDeleteRequest(StorageObject storageItem, Map<StorageOption, ?> storageSettings)
      throws IOException {
    return backendStore
        .objects()
        .delete(storageItem.getBucket(), storageItem.getName())
        .setGeneration(storageItem.getGeneration())
        .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
        .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
        .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
        .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
        .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
  }

  @Override
  public boolean delete(StorageObject storageItem, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      createDeleteRequest(storageItem, storageSettings).execute();
      return true;
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public StorageObject compose(
          Iterable<StorageObject> sourceItems, StorageObject destinationItem, Map<StorageOption, ?> destinationParams) {
    ComposeRequest composePayload = new ComposeRequest();
    composePayload.setDestination(destinationItem);
    List<ComposeRequest.SourceObjects> sourcesList = new ArrayList<>();
    for (StorageObject storageItem : sourceItems) {
      ComposeRequest.SourceObjects objectEntry = new ComposeRequest.SourceObjects();
      objectEntry.setName(storageItem.getName());
      Long genId = storageItem.getGeneration();
      if (genId != null) {
        objectEntry.setGeneration(genId);
        objectEntry.setObjectPreconditions(
            new ObjectPreconditions().setIfGenerationMatch(genId));
      }
      sourcesList.add(objectEntry);
    }
    composePayload.setSourceObjects(sourcesList);
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .objects()
          .compose(destinationItem.getBucket(), destinationItem.getName(), composePayload)
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(destinationParams))
          .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(destinationParams))
          .setUserProject(StorageOption.USER_PROJECT.getString(destinationParams))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public byte[] load(StorageObject sourceObj, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      Storage.Objects.Get getReq =
          backendStore
              .objects()
              .get(sourceObj.getBucket(), sourceObj.getName())
              .setGeneration(sourceObj.getGeneration())
              .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
              .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
              .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
              .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
              .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
      setEncryptionHeaders(getReq.getRequestHeaders(), CRYPTO_KEY_PREFIX, storageSettings);
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      getReq.executeMedia().download(buffer);
      return buffer.toByteArray();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public RpcRequestBatch createBatch() {
    return new DefaultRpcBatcher(backendStore);
  }

  private Get createGetRequest(StorageObject sourceObj, Map<StorageOption, ?> storageSettings) throws IOException {
    Get getCall =
        backendStore
            .objects()
            .get(sourceObj.getBucket(), sourceObj.getName())
            .setGeneration(sourceObj.getGeneration())
            .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
            .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
            .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
            .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
            .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
    setEncryptionHeaders(getCall.getRequestHeaders(), CRYPTO_KEY_PREFIX, storageSettings);
    getCall.setReturnRawInputStream(true);
    return getCall;
  }

  @Override
  public long read(
          StorageObject sourceObj, Map<StorageOption, ?> storageSettings, long pos, OutputStream destinationStream) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      Get getCall = createGetRequest(sourceObj, storageSettings);
      getCall.getMediaHttpDownloader().setBytesDownloaded(pos);
      getCall.getMediaHttpDownloader().setDirectDownloadEnabled(true);
      getCall.executeMediaAndDownloadTo(destinationStream);
      return getCall.getMediaHttpDownloader().getNumBytesDownloaded();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == SC_RANGE_NOT_SATISFIABLE) {
        return 0;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Tuple<String, byte[]> read(
          StorageObject sourceObj, Map<StorageOption, ?> storageSettings, long pos, int length) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      checkArgument(pos >= 0, "Position should be non-negative, is %d", pos);
      Get getCall = createGetRequest(sourceObj, storageSettings);
      StringBuilder headerBuf = new StringBuilder();
      headerBuf.append("bytes=").append(pos).append("-").append(pos + length - 1);
      HttpHeaders httpHeaders = getCall.getRequestHeaders();
      httpHeaders.setRange(headerBuf.toString());
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(length);
      getCall.executeMedia().download(buffer);
      String entityTag = getCall.getLastResponseHeaders().getETag();
      return Tuple.of(entityTag, buffer.toByteArray());
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == SC_RANGE_NOT_SATISFIABLE) {
        return Tuple.of(null, new byte[0]);
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public void write(
      String uploadToken,
      byte[] writeBuffer,
      int bufferOffset,
      long destinationOffset,
      int byteCount,
      boolean isFinal) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      if (byteCount == 0 && !isFinal) {
        return;
      }
      GenericUrl requestUrl = new GenericUrl(uploadToken);
      HttpRequest outboundRequest =
          backendStore
              .getRequestFactory()
              .buildPutRequest(requestUrl, new ByteArrayContent(null, writeBuffer, bufferOffset, byteCount));
      long maxBytes = destinationOffset + byteCount;
      StringBuilder headerBuf = new StringBuilder("bytes ");
      if (byteCount == 0) {
        headerBuf.append('*');
      } else {
        headerBuf.append(destinationOffset).append('-').append(maxBytes - 1);
      }
      headerBuf.append('/');
      if (isFinal) {
        headerBuf.append(maxBytes);
      } else {
        headerBuf.append('*');
      }
      outboundRequest.getHeaders().setContentRange(headerBuf.toString());
      int statusCode;
      String statusMessage;
      IOException ioException = null;
      HttpResponse resultData = null;
      try {
        resultData = outboundRequest.execute();
        statusCode = resultData.getStatusCode();
        statusMessage = resultData.getStatusMessage();
      } catch (HttpResponseException ioe) {
        ioException = ioe;
        statusCode = ioe.getStatusCode();
        statusMessage = ioe.getStatusMessage();
      } finally {
        if (resultData != null) {
          resultData.disconnect();
        }
      }
      if (!isFinal && statusCode != 308 || isFinal && !(statusCode == 200 || statusCode == 201)) {
        if (ioException != null) {
          throw ioException;
        }
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(statusCode);
        jsonError.setMessage(statusMessage);
        throw translateException(jsonError);
      }
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public String open(StorageObject storageBlob, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      Insert getCall = backendStore.objects().insert(storageBlob.getBucket(), storageBlob);
      GenericUrl requestUrl = getCall.buildHttpRequest().getUrl();
      String urlScheme = requestUrl.getScheme();
      String hostname = requestUrl.getHost();
      int serverPort = requestUrl.getPort();
      serverPort = serverPort > 0 ? serverPort : requestUrl.toURL().getDefaultPort();
      String resourcePath = "/upload" + requestUrl.getRawPath();
      requestUrl = new GenericUrl(urlScheme + "://" + hostname + ":" + serverPort + resourcePath);
      requestUrl.set("uploadType", "resumable");
      requestUrl.set("name", storageBlob.getName());
      for (StorageOption storageChoice : storageSettings.keySet()) {
        Object inputStream = storageChoice.get(storageSettings);
        if (inputStream != null) {
          requestUrl.set(storageChoice.getValue(), inputStream.toString());
        }
      }
      JsonFactory jsonParser = backendStore.getJsonFactory();
      HttpRequestFactory httpRequestFactory = backendStore.getRequestFactory();
      HttpRequest outboundRequest =
          httpRequestFactory.buildPostRequest(requestUrl, new JsonHttpContent(jsonParser, storageBlob));
      HttpHeaders httpHeaders = outboundRequest.getHeaders();
      httpHeaders.set(
          "X-Upload-Content-Type",
          firstNonNull(storageBlob.getContentType(), "application/octet-stream"));
      String secretId = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(storageSettings);
      if (secretId != null) {
        BaseEncoding binaryEncoder = BaseEncoding.base64();
        HashFunction digestFunction = Hashing.sha256();
        httpHeaders.set("x-goog-encryption-algorithm", "AES256");
        httpHeaders.set("x-goog-encryption-key", secretId);
        httpHeaders.set(
            "x-goog-encryption-key-sha256",
            binaryEncoder.encode(digestFunction.hashBytes(binaryEncoder.decode(secretId)).asBytes()));
      }
      HttpResponse resultData = outboundRequest.execute();
      if (resultData.getStatusCode() != 200) {
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(resultData.getStatusCode());
        jsonError.setMessage(resultData.getStatusMessage());
        throw translateException(jsonError);
      }
      return resultData.getHeaders().getLocation();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public String open(String signedUrl) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      GenericUrl requestUrl = new GenericUrl(signedUrl);
      requestUrl.set("uploadType", "resumable");
      String paramsAsString = "";
      byte[] byteArray = new byte[paramsAsString.length()];
      HttpRequestFactory httpRequestFactory = backendStore.getRequestFactory();
      HttpRequest outboundRequest =
          httpRequestFactory.buildPostRequest(
                  requestUrl, new ByteArrayContent("", byteArray, 0, byteArray.length));
      HttpHeaders httpHeaders = outboundRequest.getHeaders();
      httpHeaders.set("X-Upload-Content-Type", "");
      httpHeaders.set("x-goog-resumable", "start");
      HttpResponse resultData = outboundRequest.execute();
      if (resultData.getStatusCode() != 201) {
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(resultData.getStatusCode());
        jsonError.setMessage(resultData.getStatusMessage());
        throw translateException(jsonError);
      }
      return resultData.getHeaders().getLocation();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public StorageRpcClient.RewriteOperationResult openRewrite(ObjectRewriteRequest rewriteReq) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return performRewrite(rewriteReq, null);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public StorageRpcClient.RewriteOperationResult continueRewrite(RewriteOperationResult prevRewriteResult) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return performRewrite(prevRewriteResult.rewriteRequest, prevRewriteResult.rewriteToken);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  private RewriteOperationResult performRewrite(ObjectRewriteRequest getCall, String authToken) {
    try {
      String userProjectId = StorageOption.USER_PROJECT.getString(getCall.sourceOptions);
      if (userProjectId == null) {
        userProjectId = StorageOption.USER_PROJECT.getString(getCall.targetOptions);
      }

      Long maxBytesPerCall =
          getCall.megabytesRewrittenPerCall != null ? getCall.megabytesRewrittenPerCall * BYTES_PER_MB : null;
      Storage.Objects.Rewrite rewriteOperation =
          backendStore
              .objects()
              .rewrite(
                  getCall.source.getBucket(),
                  getCall.source.getName(),
                  getCall.target.getBucket(),
                  getCall.target.getName(),
                  getCall.overrideInfo ? getCall.target : null)
              .setSourceGeneration(getCall.source.getGeneration())
              .setRewriteToken(authToken)
              .setMaxBytesRewrittenPerCall(maxBytesPerCall)
              .setProjection(DEFAULT_PROJECTION)
              .setIfSourceMetagenerationMatch(
                  StorageOption.IF_SOURCE_METAGENERATION_MATCH.getLong(getCall.sourceOptions))
              .setIfSourceMetagenerationNotMatch(
                  StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(getCall.sourceOptions))
              .setIfSourceGenerationMatch(
                  StorageOption.IF_SOURCE_GENERATION_MATCH.getLong(getCall.sourceOptions))
              .setIfSourceGenerationNotMatch(
                  StorageOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(getCall.sourceOptions))
              .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(getCall.targetOptions))
              .setIfMetagenerationNotMatch(
                  StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(getCall.targetOptions))
              .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(getCall.targetOptions))
              .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(getCall.targetOptions))
              .setDestinationPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(getCall.targetOptions))
              .setUserProject(userProjectId)
              .setDestinationKmsKeyName(StorageOption.KMS_KEY_NAME.getString(getCall.targetOptions));
      HttpHeaders httpHeaders = rewriteOperation.getRequestHeaders();
      setEncryptionHeaders(httpHeaders, ORIGIN_CRYPTO_KEY_PREFIX, getCall.sourceOptions);
      setEncryptionHeaders(httpHeaders, CRYPTO_KEY_PREFIX, getCall.targetOptions);
      com.google.api.services.storage.model.RewriteResponse rewriteResult = rewriteOperation.execute();
      return new RewriteOperationResult(
              getCall,
          rewriteResult.getResource(),
          rewriteResult.getObjectSize().longValue(),
          rewriteResult.getDone(),
          rewriteResult.getRewriteToken(),
          rewriteResult.getTotalBytesRewritten().longValue());
    } catch (IOException ioe) {
      telemetry.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    }
  }

  @Override
  public BucketAccessControl getAcl(String container, String entityId, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .bucketAccessControls()
          .get(container, entityId)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public boolean deleteAcl(String container, String entityId, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      backendStore
          .bucketAccessControls()
          .delete(container, entityId)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
      return true;
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public BucketAccessControl createAcl(BucketAccessControl accessControlEntry, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .bucketAccessControls()
          .insert(accessControlEntry.getBucket(), accessControlEntry)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public BucketAccessControl patchAcl(BucketAccessControl accessControlEntry, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .bucketAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getEntity(), accessControlEntry)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public List<BucketAccessControl> listAcls(String container, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .bucketAccessControls()
          .list(container)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute()
          .getItems();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public ObjectAccessControl getDefaultAcl(String container, String entityId) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore.defaultObjectAccessControls().get(container, entityId).execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public boolean deleteDefaultAcl(String container, String entityId) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      backendStore.defaultObjectAccessControls().delete(container, entityId).execute();
      return true;
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControlEntry) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore.defaultObjectAccessControls().insert(accessControlEntry.getBucket(), accessControlEntry).execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControlEntry) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .defaultObjectAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getEntity(), accessControlEntry)
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public List<ObjectAccessControl> listDefaultAcls(String container) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore.defaultObjectAccessControls().list(container).execute().getItems();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public ObjectAccessControl getAcl(String container, String storageBlob, Long genId, String entityId) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .objectAccessControls()
          .get(container, storageBlob, entityId)
          .setGeneration(genId)
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public boolean deleteAcl(String container, String storageBlob, Long genId, String entityId) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      backendStore
          .objectAccessControls()
          .delete(container, storageBlob, entityId)
          .setGeneration(genId)
          .execute();
      return true;
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public ObjectAccessControl createAcl(ObjectAccessControl accessControlEntry) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .objectAccessControls()
          .insert(accessControlEntry.getBucket(), accessControlEntry.getObject(), accessControlEntry)
          .setGeneration(accessControlEntry.getGeneration())
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public ObjectAccessControl patchAcl(ObjectAccessControl accessControlEntry) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .objectAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getObject(), accessControlEntry.getEntity(), accessControlEntry)
          .setGeneration(accessControlEntry.getGeneration())
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public List<ObjectAccessControl> listAcls(String container, String storageBlob, Long genId) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .objectAccessControls()
          .list(container, storageBlob)
          .setGeneration(genId)
          .execute()
          .getItems();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public HmacKey createHmacKey(String principalEmail, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return backendStore
          .projects()
          .hmacKeys()
          .create(projectIdentifier, principalEmail)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      HmacKeysMetadata keysMetadata =
          backendStore
              .projects()
              .hmacKeys()
              .list(projectIdentifier)
              .setServiceAccountEmail(StorageOption.SERVICE_ACCOUNT_EMAIL.getString(storageSettings))
              .setPageToken(StorageOption.PAGE_TOKEN.getString(storageSettings))
              .setMaxResults(StorageOption.MAX_RESULTS.getLong(storageSettings))
              .setShowDeletedKeys(StorageOption.SHOW_DELETED_KEYS.getBoolean(storageSettings))
              .execute();
      return Tuple.<String, Iterable<HmacKeyMetadata>>of(
          keysMetadata.getNextPageToken(), keysMetadata.getItems());
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public HmacKeyMetadata getHmacKey(String keyId, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return backendStore
          .projects()
          .hmacKeys()
          .get(projectIdentifier, keyId)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public HmacKeyMetadata updateHmacKey(HmacKeyMetadata keyMetadata, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    String projectIdentifier = keyMetadata.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return backendStore
          .projects()
          .hmacKeys()
          .update(projectIdentifier, keyMetadata.getAccessId(), keyMetadata)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public void deleteHmacKey(HmacKeyMetadata keyMetadata, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    String projectIdentifier = keyMetadata.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      backendStore
          .projects()
          .hmacKeys()
          .delete(projectIdentifier, keyMetadata.getAccessId())
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Policy getIamPolicy(String container, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .buckets()
          .getIamPolicy(container)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Policy setIamPolicy(String container, Policy iamConfig, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .buckets()
          .setIamPolicy(container, iamConfig)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public TestIamPermissionsResponse testIamPermissions(
          String container, List<String> requestedActions, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .buckets()
          .testIamPermissions(container, requestedActions)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public boolean deleteNotification(String container, String alertIdentifier) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      backendStore.notifications().delete(container, alertIdentifier).execute();
      return true;
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      StorageOperationException storageError = translateException(ioe);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public List<Notification> listNotifications(String container) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore.notifications().list(container).execute().getItems();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Notification createNotification(String container, Notification alertIdentifier) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore.notifications().insert(container, alertIdentifier).execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public Bucket lockRetentionPolicy(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore
          .buckets()
          .lockRetentionPolicy(container.getName(), StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }

  @Override
  public ServiceAccount getServiceAccount(String projectIdentifier) {
    Span traceCtx = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
    Scope scopeHandle = telemetry.withSpan(traceCtx);
    try {
      return backendStore.projects().serviceAccount().get(projectIdentifier).execute();
    } catch (IOException ioe) {
      traceCtx.setStatus(Status.UNKNOWN.withDescription(ioe.getMessage()));
      throw translateException(ioe);
    } finally {
      scopeHandle.close();
      traceCtx.end();
    }
  }
}
