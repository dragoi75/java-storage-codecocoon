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
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class HttpStorageClient implements StorageRpc {
  public static final String DEFAULT_PROJECTION = "full";
  public static final String NO_ACL_PROJECTION = "noAcl";
  private static final String CRYPTO_KEY_PREFIX = "x-goog-encryption-";
  private static final String SRC_CRYPTO_KEY_PREFIX = "x-goog-copy-source-encryption-";

  // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
  private static final int SC_RANGE_NOT_SATISFIABLE = 416;

  private final StorageOptions requestConfig;
  private final Storage dataStore;
  private final Tracer traceAgent = Tracing.getTracer();
  private final CensusHttpModule censusModule;
  private final HttpRequestInitializer batchInit;

  private static final long ONE_MB = 1024L * 1024L;

  public HttpStorageClient(StorageOptions requestConfig) {
    HttpTransportOptions httpTransportConfig = (HttpTransportOptions) requestConfig.getTransportOptions();
    HttpTransport httpTransportClient = httpTransportConfig.getHttpTransportFactory().create();
    HttpRequestInitializer requestInitializer = httpTransportConfig.getHttpRequestInitializer(requestConfig);
    this.requestConfig = requestConfig;

    // Open Census initialization
    censusModule = new CensusHttpModule(traceAgent, true);
    requestInitializer = censusModule.getHttpRequestInitializer(requestInitializer);
    batchInit = censusModule.getHttpRequestInitializer(null);
    dataStore =
        new Storage.Builder(httpTransportClient, new JacksonFactory(), requestInitializer)
            .setRootUrl(requestConfig.getHost())
            .setApplicationName(requestConfig.getApplicationName())
            .build();
  }

  private class DefaultRpcRequestBatch implements RpcBatch {

    // Batch size is limited as, due to some current service implementation details, the service
    // performs better if the batches are split for better distribution. See
    // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
    // background.
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;

    private final Storage dataStore;
    private final LinkedList<BatchRequest> batchList;
    private int batchSizeCurrent;

    private DefaultRpcRequestBatch(Storage dataStore) {
      this.dataStore = dataStore;
      batchList = new LinkedList<>();
      // add OpenCensus HttpRequestInitializer
      batchList.add(dataStore.batch(batchInit));
    }

    @Override
    public void addDelete(
        StorageObject blob, RpcBatch.Callback<Void> resultCallback, Map<RequestOption, ?> requestConfig) {
      try {
        if (batchSizeCurrent == DEFAULT_MAX_BATCH_SIZE) {
          batchList.add(dataStore.batch());
          batchSizeCurrent = 0;
        }
        buildDeleteCall(blob, requestConfig).queue(batchList.getLast(), toJsonBatchCallback(resultCallback));
        batchSizeCurrent++;
      } catch (IOException ioError) {
        throw translateException(ioError);
      }
    }

    @Override
    public void addPatch(
        StorageObject blob,
        RpcBatch.Callback<StorageObject> resultCallback,
        Map<RequestOption, ?> requestConfig) {
      try {
        if (batchSizeCurrent == DEFAULT_MAX_BATCH_SIZE) {
          batchList.add(dataStore.batch());
          batchSizeCurrent = 0;
        }
        patchObjectCall(blob, requestConfig).queue(batchList.getLast(), toJsonBatchCallback(resultCallback));
        batchSizeCurrent++;
      } catch (IOException ioError) {
        throw translateException(ioError);
      }
    }

    @Override
    public void addGet(
        StorageObject blob,
        RpcBatch.Callback<StorageObject> resultCallback,
        Map<RequestOption, ?> requestConfig) {
      try {
        if (batchSizeCurrent == DEFAULT_MAX_BATCH_SIZE) {
          batchList.add(dataStore.batch());
          batchSizeCurrent = 0;
        }
        getCall(blob, requestConfig).queue(batchList.getLast(), toJsonBatchCallback(resultCallback));
        batchSizeCurrent++;
      } catch (IOException ioError) {
        throw translateException(ioError);
      }
    }

    @Override
    public void submit() {
      Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
      Scope spanScope = traceAgent.withSpan(currentSpan);
      try {
        currentSpan.putAttribute("batch size", AttributeValue.longAttributeValue(batchList.size()));
        for (BatchRequest requestBatch : batchList) {
          // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
          // Here we only add a annotation to at least know how much time each batch takes.
          currentSpan.addAnnotation("Execute batch request");
          requestBatch.setBatchUrl(
              new GenericUrl(String.format("%s/batch/storage/v1", requestConfig.getHost())));
          requestBatch.execute();
        }
      } catch (IOException ioError) {
        currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
        throw translateException(ioError);
      } finally {
        spanScope.close();
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

  private static StorageException translateException(IOException ioException) {
    return new StorageException(ioException);
  }

  private static StorageException translateException(GoogleJsonError ioException) {
    return new StorageException(ioException);
  }

  private static void setEncryptionHeaders(
      HttpHeaders httpHdrs, String prefixForHeaders, Map<RequestOption, ?> requestConfig) {
    String encryptionKey = RequestOption.CUSTOMER_SUPPLIED_KEY.getString(requestConfig);
    if (encryptionKey != null) {
      BaseEncoding baseEncoding = BaseEncoding.base64();
      HashFunction digestFunction = Hashing.sha256();
      httpHdrs.set(prefixForHeaders + "algorithm", "AES256");
      httpHdrs.set(prefixForHeaders + "key", encryptionKey);
      httpHdrs.set(
          prefixForHeaders + "key-sha256",
          baseEncoding.encode(digestFunction.hashBytes(baseEncoding.decode(encryptionKey)).asBytes()));
    }
  }

  /** Helper method to start a span. */
  private Span beginSpan(String operationName) {
    return traceAgent
        .spanBuilder(operationName)
        .setRecordEvents(censusModule.isRecordEvents())
        .startSpan();
  }

  @Override
  public Bucket create(Bucket bucketResource, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .buckets()
          .insert(this.requestConfig.getProjectId(), bucketResource)
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestConfig))
          .setPredefinedDefaultObjectAcl(RequestOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject create(
      StorageObject blob, final InputStream inputContent, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      Storage.Objects.Insert insertRequest =
          dataStore
              .objects()
              .insert(
                  blob.getBucket(),
                  blob,
                  new InputStreamContent(blob.getContentType(), inputContent));
      insertRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
      Boolean gzipDisabled = RequestOption.IF_DISABLE_GZIP_CONTENT.getBoolean(requestConfig);
      if (gzipDisabled != null) {
        insertRequest.setDisableGZipContent(gzipDisabled);
      }
      setEncryptionHeaders(insertRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, requestConfig);
      return insertRequest
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestConfig))
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
          .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
          .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestConfig))
          .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestConfig))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .setKmsKeyName(RequestOption.KMS_KEY_NAME.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<Bucket>> list(Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      Buckets bucketCollection =
          dataStore
              .buckets()
              .list(this.requestConfig.getProjectId())
              .setProjection(DEFAULT_PROJECTION)
              .setPrefix(RequestOption.PREFIX.getString(requestConfig))
              .setMaxResults(RequestOption.MAX_RESULTS.getLong(requestConfig))
              .setPageToken(RequestOption.PAGE_TOKEN.getString(requestConfig))
              .setFields(RequestOption.FIELDS.getString(requestConfig))
              .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
              .execute();
      return Tuple.<String, Iterable<Bucket>>of(bucketCollection.getNextPageToken(), bucketCollection.getItems());
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<StorageObject>> list(final String bucketResource, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      Objects objectsPage =
          dataStore
              .objects()
              .list(bucketResource)
              .setProjection(DEFAULT_PROJECTION)
              .setVersions(RequestOption.VERSIONS.getBoolean(requestConfig))
              .setDelimiter(RequestOption.DELIMITER.getString(requestConfig))
              .setPrefix(RequestOption.PREFIX.getString(requestConfig))
              .setMaxResults(RequestOption.MAX_RESULTS.getLong(requestConfig))
              .setPageToken(RequestOption.PAGE_TOKEN.getString(requestConfig))
              .setFields(RequestOption.FIELDS.getString(requestConfig))
              .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
              .execute();
      Iterable<StorageObject> objectIterable =
          Iterables.concat(
              firstNonNull(objectsPage.getItems(), ImmutableList.<StorageObject>of()),
              objectsPage.getPrefixes() != null
                  ? Lists.transform(objectsPage.getPrefixes(), storageObjectFromPrefix(bucketResource))
                  : ImmutableList.<StorageObject>of());
      return Tuple.of(objectsPage.getNextPageToken(), objectIterable);
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private static Function<String, StorageObject> storageObjectFromPrefix(final String bucketResource) {
    return new Function<String, StorageObject>() {
      @Override
      public StorageObject apply(String prefix) {
        return new StorageObject()
            .set("isDirectory", true)
            .setBucket(bucketResource)
            .setName(prefix)
            .setSize(BigInteger.ZERO);
      }
    };
  }

  @Override
  public Bucket get(Bucket bucketResource, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .buckets()
          .get(bucketResource.getName())
          .setProjection(DEFAULT_PROJECTION)
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
          .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
          .setFields(RequestOption.FIELDS.getString(requestConfig))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Get getCall(StorageObject blob, Map<RequestOption, ?> requestConfig)
      throws IOException {
    Storage.Objects.Get getRequest = dataStore.objects().get(blob.getBucket(), blob.getName());
    setEncryptionHeaders(getRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, requestConfig);
    return getRequest.setGeneration(blob.getGeneration())
        .setProjection(DEFAULT_PROJECTION)
        .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
        .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
        .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestConfig))
        .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestConfig))
        .setFields(RequestOption.FIELDS.getString(requestConfig))
        .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig));
  }

  @Override
  public StorageObject get(StorageObject blob, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return getCall(blob, requestConfig).execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket patch(Bucket bucketResource, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      String projectionType = RequestOption.PROJECTION.getString(requestConfig);
      if (bucketResource.getIamConfiguration() != null
          && bucketResource.getIamConfiguration().getBucketPolicyOnly() != null
          && bucketResource.getIamConfiguration().getBucketPolicyOnly().getEnabled() != null
          && bucketResource.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
        // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
        // the request
        bucketResource.setDefaultObjectAcl(null);
        bucketResource.setAcl(null);

        if (projectionType == null) {
          projectionType = NO_ACL_PROJECTION;
        }
      }
      return dataStore
          .buckets()
          .patch(bucketResource.getName(), bucketResource)
          .setProjection(projectionType == null ? DEFAULT_PROJECTION : projectionType)
          .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestConfig))
          .setPredefinedDefaultObjectAcl(RequestOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(requestConfig))
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
          .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Patch patchObjectCall(StorageObject blob, Map<RequestOption, ?> requestConfig)
      throws IOException {
    return dataStore
        .objects()
        .patch(blob.getBucket(), blob.getName(), blob)
        .setProjection(DEFAULT_PROJECTION)
        .setPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(requestConfig))
        .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
        .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
        .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestConfig))
        .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestConfig))
        .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig));
  }

  @Override
  public StorageObject patch(StorageObject blob, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return patchObjectCall(blob, requestConfig).execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean delete(Bucket bucketResource, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      dataStore
          .buckets()
          .delete(bucketResource.getName())
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
          .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
      return true;
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Delete buildDeleteCall(StorageObject objectBlob, Map<RequestOption, ?> requestConfig)
      throws IOException {
    return dataStore
        .objects()
        .delete(objectBlob.getBucket(), objectBlob.getName())
        .setGeneration(objectBlob.getGeneration())
        .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
        .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
        .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestConfig))
        .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestConfig))
        .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig));
  }

  @Override
  public boolean delete(StorageObject objectBlob, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      buildDeleteCall(objectBlob, requestConfig).execute();
      return true;
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject compose(
      Iterable<StorageObject> sourceIterable, StorageObject targetBlob, Map<RequestOption, ?> targetConfig) {
    ComposeRequest composeRequest = new ComposeRequest();
    composeRequest.setDestination(targetBlob);
    List<ComposeRequest.SourceObjects> sourceList = new ArrayList<>();
    for (StorageObject sourceBlob : sourceIterable) {
      ComposeRequest.SourceObjects componentSource = new ComposeRequest.SourceObjects();
      componentSource.setName(sourceBlob.getName());
      Long generationId = sourceBlob.getGeneration();
      if (generationId != null) {
        componentSource.setGeneration(generationId);
        componentSource.setObjectPreconditions(
            new ObjectPreconditions().setIfGenerationMatch(generationId));
      }
      sourceList.add(componentSource);
    }
    composeRequest.setSourceObjects(sourceList);
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .objects()
          .compose(targetBlob.getBucket(), targetBlob.getName(), composeRequest)
          .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(targetConfig))
          .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(targetConfig))
          .setUserProject(RequestOption.USER_PROJECT.getString(targetConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public byte[] load(StorageObject sourceBlob, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      Storage.Objects.Get getCall =
          dataStore
              .objects()
              .get(sourceBlob.getBucket(), sourceBlob.getName())
              .setGeneration(sourceBlob.getGeneration())
              .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
              .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
              .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestConfig))
              .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestConfig))
              .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig));
      setEncryptionHeaders(getCall.getRequestHeaders(), CRYPTO_KEY_PREFIX, requestConfig);
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      getCall.executeMedia().download(outputStream);
      return outputStream.toByteArray();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public RpcBatch createBatch() {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BATCH);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return new DefaultRpcRequestBatch(dataStore);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Get buildReadRequest(StorageObject sourceBlob, Map<RequestOption, ?> requestConfig) throws IOException {
    Get getRequest =
        dataStore
            .objects()
            .get(sourceBlob.getBucket(), sourceBlob.getName())
            .setGeneration(sourceBlob.getGeneration())
            .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
            .setIfMetagenerationNotMatch(RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(requestConfig))
            .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(requestConfig))
            .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(requestConfig))
            .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig));
    setEncryptionHeaders(getRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, requestConfig);
    getRequest.setReturnRawInputStream(true);
    return getRequest;
  }

  @Override
  public long read(
      StorageObject sourceBlob, Map<RequestOption, ?> requestConfig, long readPosition, OutputStream targetStream) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      Get getRequest = buildReadRequest(sourceBlob, requestConfig);
      getRequest.getMediaHttpDownloader().setBytesDownloaded(readPosition);
      getRequest.getMediaHttpDownloader().setDirectDownloadEnabled(true);
      getRequest.executeMediaAndDownloadTo(targetStream);
      return getRequest.getMediaHttpDownloader().getNumBytesDownloaded();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == SC_RANGE_NOT_SATISFIABLE) {
        return 0;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, byte[]> read(
      StorageObject sourceBlob, Map<RequestOption, ?> requestConfig, long readPosition, int byteCount) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      checkArgument(readPosition >= 0, "Position should be non-negative, is " + readPosition);
      Get getRequest = buildReadRequest(sourceBlob, requestConfig);
      StringBuilder rangeBuilder = new StringBuilder();
      rangeBuilder.append("bytes=").append(readPosition).append("-").append(readPosition + byteCount - 1);
      HttpHeaders reqHeaders = getRequest.getRequestHeaders();
      reqHeaders.setRange(rangeBuilder.toString());
      ByteArrayOutputStream byteOut = new ByteArrayOutputStream(byteCount);
      getRequest.executeMedia().download(byteOut);
      String entityTag = getRequest.getLastResponseHeaders().getETag();
      return Tuple.of(entityTag, byteOut.toByteArray());
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = StorageException.translate(ioError);
      if (storageError.getCode() == SC_RANGE_NOT_SATISFIABLE) {
        return Tuple.of(null, new byte[0]);
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void write(
      String uploadIdentifier,
      byte[] writeBuffer,
      int writeOffset,
      long destinationOffset,
      int count,
      boolean isLast) {
    writeWithResponse(uploadIdentifier, writeBuffer, writeOffset, destinationOffset, count, isLast);
  }

  @Override
  public StorageObject writeWithResponse(
      String uploadIdentifier,
      byte[] writeBuffer,
      int writeOffset,
      long destinationOffset,
      int count,
      boolean isLast) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    StorageObject updatedObject = null;
    try {
      if (count == 0 && !isLast) {
        return updatedObject;
      }
      GenericUrl genericUrl = new GenericUrl(uploadIdentifier);
      HttpRequest request =
          dataStore
              .getRequestFactory()
              .buildPutRequest(genericUrl, new ByteArrayContent(null, writeBuffer, writeOffset, count));
      long maxLimit = destinationOffset + count;
      StringBuilder rangeBuilder = new StringBuilder("bytes ");
      if (count == 0) {
        rangeBuilder.append('*');
      } else {
        rangeBuilder.append(destinationOffset).append('-').append(maxLimit - 1);
      }
      rangeBuilder.append('/');
      if (isLast) {
        rangeBuilder.append(maxLimit);
      } else {
        rangeBuilder.append('*');
      }
      request.getHeaders().setContentRange(rangeBuilder.toString());
      if (isLast) {
        request.setParser(dataStore.getObjectParser());
      }
      int statusCode;
      String statusMessage;
      IOException ioException = null;
      HttpResponse result = null;
      try {
        result = request.execute();
        statusCode = result.getStatusCode();
        statusMessage = result.getStatusMessage();
        String mimeType = result.getContentType();
        if (isLast
            && (statusCode == 200 || statusCode == 201)
            && mimeType != null
            && mimeType.startsWith("application/json")) {
          updatedObject = result.parseAs(StorageObject.class);
        }
      } catch (HttpResponseException ioError) {
        ioException = ioError;
        statusCode = ioError.getStatusCode();
        statusMessage = ioError.getStatusMessage();
      } finally {
        if (result != null) {
          result.disconnect();
        }
      }
      if (!isLast && statusCode != 308 || isLast && !(statusCode == 200 || statusCode == 201)) {
        if (ioException != null) {
          throw ioException;
        }
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(statusCode);
        jsonError.setMessage(statusMessage);
        throw translateException(jsonError);
      }
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
    return updatedObject;
  }

  @Override
  public String open(StorageObject blob, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      Insert getRequest = dataStore.objects().insert(blob.getBucket(), blob);
      GenericUrl genericUrl = getRequest.buildHttpRequest().getUrl();
      String urlScheme = genericUrl.getScheme();
      String requestHost = genericUrl.getHost();
      int requestPort = genericUrl.getPort();
      requestPort = requestPort > 0 ? requestPort : genericUrl.toURL().getDefaultPort();
      String requestPath = "/upload" + genericUrl.getRawPath();
      genericUrl = new GenericUrl(urlScheme + "://" + requestHost + ":" + requestPort + requestPath);
      genericUrl.set("uploadType", "resumable");
      genericUrl.set("name", blob.getName());
      for (RequestOption requestOption : requestConfig.keySet()) {
        Object inputContent = requestOption.get(requestConfig);
        if (inputContent != null) {
          genericUrl.set(requestOption.getValue(), inputContent.toString());
        }
      }
      JsonFactory jsonParserFactory = dataStore.getJsonFactory();
      HttpRequestFactory httpRequestFactory = dataStore.getRequestFactory();
      HttpRequest request =
          httpRequestFactory.buildPostRequest(genericUrl, new JsonHttpContent(jsonParserFactory, blob));
      HttpHeaders reqHeaders = request.getHeaders();
      reqHeaders.set(
          "X-Upload-Content-Type",
          firstNonNull(blob.getContentType(), "application/octet-stream"));
      String encryptionKey = RequestOption.CUSTOMER_SUPPLIED_KEY.getString(requestConfig);
      if (encryptionKey != null) {
        BaseEncoding baseEncoding = BaseEncoding.base64();
        HashFunction digestFunction = Hashing.sha256();
        reqHeaders.set("x-goog-encryption-algorithm", "AES256");
        reqHeaders.set("x-goog-encryption-key", encryptionKey);
        reqHeaders.set(
            "x-goog-encryption-key-sha256",
            baseEncoding.encode(digestFunction.hashBytes(baseEncoding.decode(encryptionKey)).asBytes()));
      }
      HttpResponse result = request.execute();
      if (result.getStatusCode() != 200) {
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(result.getStatusCode());
        jsonError.setMessage(result.getStatusMessage());
        throw translateException(jsonError);
      }
      return result.getHeaders().getLocation();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public String open(String signedUrl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      GenericUrl genericUrl = new GenericUrl(signedUrl);
      genericUrl.set("uploadType", "resumable");
      String bytesParams = "";
      byte[] byteArray = new byte[bytesParams.length()];
      HttpRequestFactory httpRequestFactory = dataStore.getRequestFactory();
      HttpRequest request =
          httpRequestFactory.buildPostRequest(
              genericUrl, new ByteArrayContent("", byteArray, 0, byteArray.length));
      HttpHeaders reqHeaders = request.getHeaders();
      reqHeaders.set("X-Upload-Content-Type", "");
      reqHeaders.set("x-goog-resumable", "start");
      HttpResponse result = request.execute();
      if (result.getStatusCode() != 201) {
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(result.getStatusCode());
        jsonError.setMessage(result.getStatusMessage());
        throw translateException(jsonError);
      }
      return result.getHeaders().getLocation();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageRpc.RewriteResult openRewrite(RewriteOperationRequest rewriteOpRequest) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return executeRewrite(rewriteOpRequest, null);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageRpc.RewriteResult continueRewrite(RewriteResult prevRewriteResult) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return executeRewrite(prevRewriteResult.rewriteRequest, prevRewriteResult.rewriteToken);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private RewriteResult executeRewrite(RewriteOperationRequest getRequest, String rewriteToken) {
    try {
      String userProjectId = RequestOption.USER_PROJECT.getString(getRequest.sourceOptions);
      if (userProjectId == null) {
        userProjectId = RequestOption.USER_PROJECT.getString(getRequest.targetOptions);
      }

      Long maxBytesPerCall =
          getRequest.megabytesRewrittenPerCall != null ? getRequest.megabytesRewrittenPerCall * ONE_MB : null;
      Storage.Objects.Rewrite rewriteOp =
          dataStore
              .objects()
              .rewrite(
                  getRequest.source.getBucket(),
                  getRequest.source.getName(),
                  getRequest.target.getBucket(),
                  getRequest.target.getName(),
                  getRequest.overrideInfo ? getRequest.target : null)
              .setSourceGeneration(getRequest.source.getGeneration())
              .setRewriteToken(rewriteToken)
              .setMaxBytesRewrittenPerCall(maxBytesPerCall)
              .setProjection(DEFAULT_PROJECTION)
              .setIfSourceMetagenerationMatch(
                  RequestOption.IF_SOURCE_METAGENERATION_MATCH.getLong(getRequest.sourceOptions))
              .setIfSourceMetagenerationNotMatch(
                  RequestOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(getRequest.sourceOptions))
              .setIfSourceGenerationMatch(
                  RequestOption.IF_SOURCE_GENERATION_MATCH.getLong(getRequest.sourceOptions))
              .setIfSourceGenerationNotMatch(
                  RequestOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(getRequest.sourceOptions))
              .setIfMetagenerationMatch(RequestOption.IF_METAGENERATION_MATCH.getLong(getRequest.targetOptions))
              .setIfMetagenerationNotMatch(
                  RequestOption.IF_METAGENERATION_NOT_MATCH.getLong(getRequest.targetOptions))
              .setIfGenerationMatch(RequestOption.IF_GENERATION_MATCH.getLong(getRequest.targetOptions))
              .setIfGenerationNotMatch(RequestOption.IF_GENERATION_NOT_MATCH.getLong(getRequest.targetOptions))
              .setDestinationPredefinedAcl(RequestOption.PREDEFINED_ACL.getString(getRequest.targetOptions))
              .setUserProject(userProjectId)
              .setDestinationKmsKeyName(RequestOption.KMS_KEY_NAME.getString(getRequest.targetOptions));
      HttpHeaders reqHeaders = rewriteOp.getRequestHeaders();
      setEncryptionHeaders(reqHeaders, SRC_CRYPTO_KEY_PREFIX, getRequest.sourceOptions);
      setEncryptionHeaders(reqHeaders, CRYPTO_KEY_PREFIX, getRequest.targetOptions);
      com.google.api.services.storage.model.RewriteResponse rewriteResult = rewriteOp.execute();
      return new RewriteResult(
          getRequest,
          rewriteResult.getResource(),
          rewriteResult.getObjectSize().longValue(),
          rewriteResult.getDone(),
          rewriteResult.getRewriteToken(),
          rewriteResult.getTotalBytesRewritten().longValue());
    } catch (IOException ioError) {
      traceAgent.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    }
  }

  @Override
  public BucketAccessControl getAcl(String bucketResource, String entityId, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .bucketAccessControls()
          .get(bucketResource, entityId)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String bucketResource, String entityId, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      dataStore
          .bucketAccessControls()
          .delete(bucketResource, entityId)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
      return true;
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl createAcl(BucketAccessControl accessControl, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .bucketAccessControls()
          .insert(accessControl.getBucket(), accessControl)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl patchAcl(BucketAccessControl accessControl, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .bucketAccessControls()
          .patch(accessControl.getBucket(), accessControl.getEntity(), accessControl)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<BucketAccessControl> listAcls(String bucketResource, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .bucketAccessControls()
          .list(bucketResource)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute()
          .getItems();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getDefaultAcl(String bucketResource, String entityId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore.defaultObjectAccessControls().get(bucketResource, entityId).execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteDefaultAcl(String bucketResource, String entityId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      dataStore.defaultObjectAccessControls().delete(bucketResource, entityId).execute();
      return true;
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore.defaultObjectAccessControls().insert(accessControl.getBucket(), accessControl).execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .defaultObjectAccessControls()
          .patch(accessControl.getBucket(), accessControl.getEntity(), accessControl)
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listDefaultAcls(String bucketResource) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore.defaultObjectAccessControls().list(bucketResource).execute().getItems();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getAcl(String bucketResource, String blob, Long generationId, String entityId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .objectAccessControls()
          .get(bucketResource, blob, entityId)
          .setGeneration(generationId)
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String bucketResource, String blob, Long generationId, String entityId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      dataStore
          .objectAccessControls()
          .delete(bucketResource, blob, entityId)
          .setGeneration(generationId)
          .execute();
      return true;
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createAcl(ObjectAccessControl accessControl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .objectAccessControls()
          .insert(accessControl.getBucket(), accessControl.getObject(), accessControl)
          .setGeneration(accessControl.getGeneration())
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchAcl(ObjectAccessControl accessControl) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .objectAccessControls()
          .patch(accessControl.getBucket(), accessControl.getObject(), accessControl.getEntity(), accessControl)
          .setGeneration(accessControl.getGeneration())
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listAcls(String bucketResource, String blob, Long generationId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .objectAccessControls()
          .list(bucketResource, blob)
          .setGeneration(generationId)
          .execute()
          .getItems();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKey createHmacKey(String serviceAccountId, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    String projectIdentifier = RequestOption.PROJECT_ID.getString(requestConfig);
    if (projectIdentifier == null) {
      projectIdentifier = this.requestConfig.getProjectId();
    }
    try {
      return dataStore
          .projects()
          .hmacKeys()
          .create(projectIdentifier, serviceAccountId)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    String projectIdentifier = RequestOption.PROJECT_ID.getString(requestConfig);
    if (projectIdentifier == null) {
      projectIdentifier = this.requestConfig.getProjectId();
    }
    try {
      HmacKeysMetadata hmacMeta =
          dataStore
              .projects()
              .hmacKeys()
              .list(projectIdentifier)
              .setServiceAccountEmail(RequestOption.SERVICE_ACCOUNT_EMAIL.getString(requestConfig))
              .setPageToken(RequestOption.PAGE_TOKEN.getString(requestConfig))
              .setMaxResults(RequestOption.MAX_RESULTS.getLong(requestConfig))
              .setShowDeletedKeys(RequestOption.SHOW_DELETED_KEYS.getBoolean(requestConfig))
              .execute();
      return Tuple.<String, Iterable<HmacKeyMetadata>>of(
          hmacMeta.getNextPageToken(), hmacMeta.getItems());
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata getHmacKey(String accessIdentifier, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    String projectIdentifier = RequestOption.PROJECT_ID.getString(requestConfig);
    if (projectIdentifier == null) {
      projectIdentifier = this.requestConfig.getProjectId();
    }
    try {
      return dataStore
          .projects()
          .hmacKeys()
          .get(projectIdentifier, accessIdentifier)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacMeta, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    String projectIdentifier = hmacMeta.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.requestConfig.getProjectId();
    }
    try {
      return dataStore
          .projects()
          .hmacKeys()
          .update(projectIdentifier, hmacMeta.getAccessId(), hmacMeta)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void deleteHmacKey(HmacKeyMetadata hmacMeta, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    String projectIdentifier = hmacMeta.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.requestConfig.getProjectId();
    }
    try {
      dataStore
          .projects()
          .hmacKeys()
          .delete(projectIdentifier, hmacMeta.getAccessId())
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy getIamPolicy(String bucketResource, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      Storage.Buckets.GetIamPolicy iamPolicyRequest =
          dataStore
              .buckets()
              .getIamPolicy(bucketResource)
              .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig));
      if (null != RequestOption.REQUESTED_POLICY_VERSION.getLong(requestConfig)) {
        iamPolicyRequest.setOptionsRequestedPolicyVersion(
            RequestOption.REQUESTED_POLICY_VERSION.getLong(requestConfig).intValue());
      }
      return iamPolicyRequest.execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy setIamPolicy(String bucketResource, Policy iamPolicy, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .buckets()
          .setIamPolicy(bucketResource, iamPolicy)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public TestIamPermissionsResponse testIamPermissions(
      String bucketResource, List<String> permissionList, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .buckets()
          .testIamPermissions(bucketResource, permissionList)
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteNotification(String bucketResource, String notificationId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      dataStore.notifications().delete(bucketResource, notificationId).execute();
      return true;
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      StorageException storageError = translateException(ioError);
      if (storageError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageError;
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<Notification> listNotifications(String bucketResource) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore.notifications().list(bucketResource).execute().getItems();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Notification createNotification(String bucketResource, Notification notificationId) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore.notifications().insert(bucketResource, notificationId).execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket lockRetentionPolicy(Bucket bucketResource, Map<RequestOption, ?> requestConfig) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore
          .buckets()
          .lockRetentionPolicy(bucketResource.getName(), RequestOption.IF_METAGENERATION_MATCH.getLong(requestConfig))
          .setUserProject(RequestOption.USER_PROJECT.getString(requestConfig))
          .execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ServiceAccount getServiceAccount(String projectIdentifier) {
    Span currentSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
    Scope spanScope = traceAgent.withSpan(currentSpan);
    try {
      return dataStore.projects().serviceAccount().get(projectIdentifier).execute();
    } catch (IOException ioError) {
      currentSpan.setStatus(Status.UNKNOWN.withDescription(ioError.getMessage()));
      throw translateException(ioError);
    } finally {
      spanScope.close();
      currentSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }
}
