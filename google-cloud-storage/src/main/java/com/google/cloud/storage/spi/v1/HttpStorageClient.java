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
import static com.google.common.base.Preconditions.checkNotNull;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.media.MediaHttpDownloader;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.EmptyContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpExecuteInterceptor;
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
import java.util.UUID;
import javax.annotation.Nullable;

public class HttpStorageClient implements StorageRpc {
  public static final String DEFAULT_PROJECTION = "full";
  public static final String NO_ACL_PROJECTION = "noAcl";
  private static final String ENCRYPTION_HEADER_PREFIX = "x-goog-encryption-";
  private static final String SOURCE_ENCRYPTION_HEADER = "x-goog-copy-source-encryption-";

  // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
  private static final int HTTP_STATUS_RANGE_NOT_SATISFIABLE = 416;

  private final StorageOptions storageSettings;
  private final Storage backendService;
  private final Tracer traceProvider = Tracing.getTracer();
  private final CensusHttpModule telemetryModule;
  private final HttpRequestInitializer batchReqInitializer;

  private static final long BYTES_IN_MEGABYTE = 1024L * 1024L;
  private static final FileNameMap MIME_FILE_MAP = URLConnection.getFileNameMap();

  public HttpStorageClient(StorageOptions storageSettings) {
    HttpTransportOptions httpTransportConfig = (HttpTransportOptions) storageSettings.getTransportOptions();
    HttpTransport httpTransportClient = httpTransportConfig.getHttpTransportFactory().create();
    HttpRequestInitializer requestInitializer = httpTransportConfig.getHttpRequestInitializer(storageSettings);
    this.storageSettings = storageSettings;

    // Open Census initialization
    telemetryModule = new CensusHttpModule(traceProvider, true);
    requestInitializer = telemetryModule.getHttpRequestInitializer(requestInitializer);
    requestInitializer = new InvocationIdGenerator(requestInitializer);
    batchReqInitializer = telemetryModule.getHttpRequestInitializer(null);
    backendService =
        new Storage.Builder(httpTransportClient, new JacksonFactory(), requestInitializer)
            .setRootUrl(storageSettings.getHost())
            .setApplicationName(storageSettings.getApplicationName())
            .build();
  }

  private static final class InvocationIdGenerator implements HttpRequestInitializer {
    @Nullable HttpRequestInitializer requestInitializer;

    private InvocationIdGenerator(@Nullable HttpRequestInitializer requestInitializer) {
      this.requestInitializer = requestInitializer;
    }

    @Override
    public void initialize(HttpRequest httpReq) throws IOException {
      checkNotNull(httpReq);
      if (this.requestInitializer != null) {
        this.requestInitializer.initialize(httpReq);
      }
      httpReq.setInterceptor(new InvocationIdPropagator(httpReq.getInterceptor()));
    }
  }

  private static final class InvocationIdPropagator implements HttpExecuteInterceptor {
    @Nullable HttpExecuteInterceptor executeInterceptor;

    private InvocationIdPropagator(@Nullable HttpExecuteInterceptor executeInterceptor) {
      this.executeInterceptor = executeInterceptor;
    }

    @Override
    public void intercept(HttpRequest httpReq) throws IOException {
      checkNotNull(httpReq);
      if (this.executeInterceptor != null) {
        this.executeInterceptor.intercept(httpReq);
      }
      UUID callUuid = HttpRpcContext.getInstance().getInvocationId();
      final String signingSecret = "Signature="; // For V2 and V4 signedURLs
      final String constructedUrl = httpReq.getUrl().build();
      if (callUuid != null && !constructedUrl.contains(signingSecret)) {
        HttpHeaders httpHeadersMap = httpReq.getHeaders();
        String previousValue = (String) httpHeadersMap.get("x-goog-api-client");
        String invocationHeaderEntry = "gccl-invocation-id/" + callUuid;
        final String updatedValue;
        if (previousValue != null && !previousValue.isEmpty()) {
          updatedValue = previousValue + " " + invocationHeaderEntry;
        } else {
          updatedValue = invocationHeaderEntry;
        }
        httpHeadersMap.set("x-goog-api-client", updatedValue);
      }
    }
  }

  private class RpcBatch implements com.google.cloud.storage.spi.v1.RpcBatch {

    // Batch size is limited as, due to some current service implementation details, the service
    // performs better if the batches are split for better distribution. See
    // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
    // background.
    private static final int BATCH_SIZE_LIMIT = 100;

    private final Storage backendService;
    private final LinkedList<BatchRequest> pendingBatches;
    private int activeBatchSize;

    private RpcBatch(Storage backendService) {
      this.backendService = backendService;
      pendingBatches = new LinkedList<>();
      // add OpenCensus HttpRequestInitializer
      pendingBatches.add(backendService.batch(batchReqInitializer));
    }

    @Override
    public void addDelete(
        StorageObject objectMetadata, com.google.cloud.storage.spi.v1.RpcBatch.Callback<Void> resultCallback, Map<Option, ?> storageSettings) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          pendingBatches.add(backendService.batch());
          activeBatchSize = 0;
        }
        createDeleteCall(objectMetadata, storageSettings).queue(pendingBatches.getLast(), toJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioException) {
        throw translateException(ioException);
      }
    }

    @Override
    public void addPatch(
        StorageObject objectMetadata,
        com.google.cloud.storage.spi.v1.RpcBatch.Callback<StorageObject> resultCallback,
        Map<Option, ?> storageSettings) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          pendingBatches.add(backendService.batch());
          activeBatchSize = 0;
        }
        patchObjectCall(objectMetadata, storageSettings).queue(pendingBatches.getLast(), toJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioException) {
        throw translateException(ioException);
      }
    }

    @Override
    public void addGet(
        StorageObject objectMetadata,
        com.google.cloud.storage.spi.v1.RpcBatch.Callback<StorageObject> resultCallback,
        Map<Option, ?> storageSettings) {
      try {
        if (activeBatchSize == BATCH_SIZE_LIMIT) {
          pendingBatches.add(backendService.batch());
          activeBatchSize = 0;
        }
        getCall(objectMetadata, storageSettings).queue(pendingBatches.getLast(), toJsonBatchCallback(resultCallback));
        activeBatchSize++;
      } catch (IOException ioException) {
        throw translateException(ioException);
      }
    }

    @Override
    public void submit() {
      Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
      Scope traceScope = traceProvider.withSpan(traceSpan);
      try {
        traceSpan.putAttribute("batch size", AttributeValue.longAttributeValue(pendingBatches.size()));
        for (BatchRequest batchRequest : pendingBatches) {
          // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
          // Here we only add a annotation to at least know how much time each batch takes.
          traceSpan.addAnnotation("Execute batch request");
          batchRequest.setBatchUrl(
              new GenericUrl(String.format("%s/batch/storage/v1", storageSettings.getHost())));
          batchRequest.execute();
        }
      } catch (IOException ioException) {
        traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
        throw translateException(ioException);
      } finally {
        traceScope.close();
        traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
      }
    }
  }

  private static <T> JsonBatchCallback<T> toJsonBatchCallback(final com.google.cloud.storage.spi.v1.RpcBatch.Callback<T> resultCallback) {
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

  private static StorageException translateException(IOException ioExceptionDetail) {
    return StorageException.translate(ioExceptionDetail);
  }

  private static StorageException toStorageException(GoogleJsonError ioExceptionDetail) {
    return new StorageException(ioExceptionDetail);
  }

  private static void setEncryptionHeaders(
      HttpHeaders httpHeadersMap, String encryptionHeaderPrefix, Map<Option, ?> storageSettings) {
    String encryptionKeyValue = Option.CUSTOMER_SUPPLIED_KEY.getString(storageSettings);
    if (encryptionKeyValue != null) {
      BaseEncoding base64Encoder = BaseEncoding.base64();
      HashFunction digestFunction = Hashing.sha256();
      httpHeadersMap.set(encryptionHeaderPrefix + "algorithm", "AES256");
      httpHeadersMap.set(encryptionHeaderPrefix + "key", encryptionKeyValue);
      httpHeadersMap.set(
          encryptionHeaderPrefix + "key-sha256",
          base64Encoder.encode(digestFunction.hashBytes(base64Encoder.decode(encryptionKeyValue)).asBytes()));
    }
  }

  /** Helper method to start a span. */
  private Span beginSpan(String traceName) {
    return traceProvider
        .spanBuilder(traceName)
        .setRecordEvents(telemetryModule.isRecordEvents())
        .startSpan();
  }

  @Override
  public Bucket create(Bucket storageBucket, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .buckets()
          .insert(this.storageSettings.getProjectId(), storageBucket)
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(Option.PREDEFINED_ACL.getString(storageSettings))
          .setPredefinedDefaultObjectAcl(Option.PREDEFINED_DEFAULT_OBJECT_ACL.getString(storageSettings))
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject create(
      StorageObject objectMetadata, final InputStream inputContent, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      Storage.Objects.Insert insertRequest =
          backendService
              .objects()
              .insert(
                  objectMetadata.getBucket(),
                  objectMetadata,
                  new InputStreamContent(determineContentType(objectMetadata, storageSettings), inputContent));
      insertRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
      Boolean gzipDisabledFlag = Option.IF_DISABLE_GZIP_CONTENT.getBoolean(storageSettings);
      if (gzipDisabledFlag != null) {
        insertRequest.setDisableGZipContent(gzipDisabledFlag);
      }
      setEncryptionHeaders(insertRequest.getRequestHeaders(), ENCRYPTION_HEADER_PREFIX, storageSettings);
      return insertRequest
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(Option.PREDEFINED_ACL.getString(storageSettings))
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(storageSettings))
          .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .setKmsKeyName(Option.KMS_KEY_NAME.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<Bucket>> list(Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      Buckets bucketList =
          backendService
              .buckets()
              .list(this.storageSettings.getProjectId())
              .setProjection(DEFAULT_PROJECTION)
              .setPrefix(Option.PREFIX.getString(storageSettings))
              .setMaxResults(Option.MAX_RESULTS.getLong(storageSettings))
              .setPageToken(Option.PAGE_TOKEN.getString(storageSettings))
              .setFields(Option.FIELDS.getString(storageSettings))
              .setUserProject(Option.USER_PROJECT.getString(storageSettings))
              .execute();
      return Tuple.<String, Iterable<Bucket>>of(bucketList.getNextPageToken(), bucketList.getItems());
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<StorageObject>> list(final String storageBucket, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      Objects objects =
          backendService
              .objects()
              .list(storageBucket)
              .setProjection(DEFAULT_PROJECTION)
              .setVersions(Option.VERSIONS.getBoolean(storageSettings))
              .setDelimiter(Option.DELIMITER.getString(storageSettings))
              .setStartOffset(Option.START_OFF_SET.getString(storageSettings))
              .setEndOffset(Option.END_OFF_SET.getString(storageSettings))
              .setPrefix(Option.PREFIX.getString(storageSettings))
              .setMaxResults(Option.MAX_RESULTS.getLong(storageSettings))
              .setPageToken(Option.PAGE_TOKEN.getString(storageSettings))
              .setFields(Option.FIELDS.getString(storageSettings))
              .setUserProject(Option.USER_PROJECT.getString(storageSettings))
              .execute();
      Iterable<StorageObject> objectIterable =
          Iterables.concat(
              firstNonNull(objects.getItems(), ImmutableList.<StorageObject>of()),
              objects.getPrefixes() != null
                  ? Lists.transform(objects.getPrefixes(), storageObjectFromPrefix(storageBucket))
                  : ImmutableList.<StorageObject>of());
      return Tuple.of(objects.getNextPageToken(), objectIterable);
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private static String determineContentType(StorageObject storageObjectRef, Map<Option, ?> storageSettings) {
    String resolvedContentType = storageObjectRef.getContentType();
    if (resolvedContentType != null) {
      return resolvedContentType;
    }

    if (Boolean.TRUE == Option.DETECT_CONTENT_TYPE.get(storageSettings)) {
      resolvedContentType = MIME_FILE_MAP.getContentTypeFor(storageObjectRef.getName().toLowerCase(Locale.ENGLISH));
    }

    return firstNonNull(resolvedContentType, "application/octet-stream");
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
  public Bucket get(Bucket storageBucket, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .buckets()
          .get(storageBucket.getName())
          .setProjection(DEFAULT_PROJECTION)
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setFields(Option.FIELDS.getString(storageSettings))
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Get getCall(StorageObject storageObjectRef, Map<Option, ?> storageSettings)
      throws IOException {
    Storage.Objects.Get getRequestCall = backendService.objects().get(storageObjectRef.getBucket(), storageObjectRef.getName());
    setEncryptionHeaders(getRequestCall.getRequestHeaders(), ENCRYPTION_HEADER_PREFIX, storageSettings);
    return getRequestCall.setGeneration(storageObjectRef.getGeneration())
        .setProjection(DEFAULT_PROJECTION)
        .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
        .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
        .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(storageSettings))
        .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
        .setFields(Option.FIELDS.getString(storageSettings))
        .setUserProject(Option.USER_PROJECT.getString(storageSettings));
  }

  @Override
  public StorageObject get(StorageObject storageObjectRef, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return getCall(storageObjectRef, storageSettings).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket patch(Bucket storageBucket, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      String projection = Option.PROJECTION.getString(storageSettings);
      if (storageBucket.getIamConfiguration() != null
          && storageBucket.getIamConfiguration().getBucketPolicyOnly() != null
          && storageBucket.getIamConfiguration().getBucketPolicyOnly().getEnabled() != null
          && storageBucket.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
        // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
        // the request
        storageBucket.setDefaultObjectAcl(null);
        storageBucket.setAcl(null);

        if (projection == null) {
          projection = NO_ACL_PROJECTION;
        }
      }
      return backendService
          .buckets()
          .patch(storageBucket.getName(), storageBucket)
          .setProjection(projection == null ? DEFAULT_PROJECTION : projection)
          .setPredefinedAcl(Option.PREDEFINED_ACL.getString(storageSettings))
          .setPredefinedDefaultObjectAcl(Option.PREDEFINED_DEFAULT_OBJECT_ACL.getString(storageSettings))
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Patch patchObjectCall(StorageObject objectMetadata, Map<Option, ?> storageSettings)
      throws IOException {
    return backendService
        .objects()
        .patch(objectMetadata.getBucket(), objectMetadata.getName(), objectMetadata)
        .setProjection(DEFAULT_PROJECTION)
        .setPredefinedAcl(Option.PREDEFINED_ACL.getString(storageSettings))
        .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
        .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
        .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(storageSettings))
        .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
        .setUserProject(Option.USER_PROJECT.getString(storageSettings));
  }

  @Override
  public StorageObject patch(StorageObject objectMetadata, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return patchObjectCall(objectMetadata, storageSettings).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean delete(Bucket storageBucket, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      backendService
          .buckets()
          .delete(storageBucket.getName())
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Delete createDeleteCall(StorageObject blob, Map<Option, ?> storageSettings)
      throws IOException {
    return backendService
        .objects()
        .delete(blob.getBucket(), blob.getName())
        .setGeneration(blob.getGeneration())
        .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
        .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
        .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(storageSettings))
        .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
        .setUserProject(Option.USER_PROJECT.getString(storageSettings));
  }

  @Override
  public boolean delete(StorageObject blob, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      createDeleteCall(blob, storageSettings).execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject compose(
      Iterable<StorageObject> sources, StorageObject target, Map<Option, ?> targetOptions) {
    ComposeRequest httpReq = new ComposeRequest();
    httpReq.setDestination(target);
    List<ComposeRequest.SourceObjects> sourceObjects = new ArrayList<>();
    for (StorageObject source : sources) {
      ComposeRequest.SourceObjects sourceObject = new ComposeRequest.SourceObjects();
      sourceObject.setName(source.getName());
      Long generation = source.getGeneration();
      if (generation != null) {
        sourceObject.setGeneration(generation);
        sourceObject.setObjectPreconditions(
            new ObjectPreconditions().setIfGenerationMatch(generation));
      }
      sourceObjects.add(sourceObject);
    }
    httpReq.setSourceObjects(sourceObjects);
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .objects()
          .compose(target.getBucket(), target.getName(), httpReq)
          .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(targetOptions))
          .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(targetOptions))
          .setUserProject(Option.USER_PROJECT.getString(targetOptions))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public byte[] load(StorageObject from, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      Storage.Objects.Get getReq =
          backendService
              .objects()
              .get(from.getBucket(), from.getName())
              .setGeneration(from.getGeneration())
              .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
              .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
              .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(storageSettings))
              .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
              .setUserProject(Option.USER_PROJECT.getString(storageSettings));
      setEncryptionHeaders(getReq.getRequestHeaders(), ENCRYPTION_HEADER_PREFIX, storageSettings);
      ByteArrayOutputStream resultOut = new ByteArrayOutputStream();
      getReq.executeMedia().download(resultOut);
      return resultOut.toByteArray();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public com.google.cloud.storage.spi.v1.RpcBatch createBatch() {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BATCH);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return new RpcBatch(backendService);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Get buildReadRequest(StorageObject from, Map<Option, ?> storageSettings) throws IOException {
    Get httpReqLocal =
        backendService
            .objects()
            .get(from.getBucket(), from.getName())
            .setGeneration(from.getGeneration())
            .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
            .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
            .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(storageSettings))
            .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
            .setUserProject(Option.USER_PROJECT.getString(storageSettings));
    setEncryptionHeaders(httpReqLocal.getRequestHeaders(), ENCRYPTION_HEADER_PREFIX, storageSettings);
    return httpReqLocal;
  }

  @Override
  public long read(
      StorageObject from, Map<Option, ?> storageSettings, long readPosition, OutputStream destOutputStream) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      Get httpReqLocal = buildReadRequest(from, storageSettings);
      Boolean returnRawStreamFlag = Option.RETURN_RAW_INPUT_STREAM.getBoolean(storageSettings);
      if (returnRawStreamFlag != null) {
        httpReqLocal.setReturnRawInputStream(returnRawStreamFlag);
      } else {
        httpReqLocal.setReturnRawInputStream(false);
      }

      if (readPosition > 0) {
        httpReqLocal.getRequestHeaders().setRange(String.format("bytes=%d-", readPosition));
      }
      MediaHttpDownloader mediaDownloader = httpReqLocal.getMediaHttpDownloader();
      mediaDownloader.setDirectDownloadEnabled(true);
      httpReqLocal.executeMedia().download(destOutputStream);
      return mediaDownloader.getNumBytesDownloaded();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_STATUS_RANGE_NOT_SATISFIABLE) {
        return 0;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, byte[]> read(
      StorageObject from, Map<Option, ?> storageSettings, long readPosition, int numBytes) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      checkArgument(readPosition >= 0, "Position should be non-negative, is " + readPosition);
      Get httpReqLocal = buildReadRequest(from, storageSettings);
      Boolean returnRawStreamFlag = Option.RETURN_RAW_INPUT_STREAM.getBoolean(storageSettings);
      if (returnRawStreamFlag != null) {
        httpReqLocal.setReturnRawInputStream(returnRawStreamFlag);
      } else {
        httpReqLocal.setReturnRawInputStream(true);
      }
      StringBuilder rangeBuilder = new StringBuilder();
      rangeBuilder.append("bytes=").append(readPosition).append("-").append(readPosition + numBytes - 1);
      HttpHeaders reqHeaders = httpReqLocal.getRequestHeaders();
      reqHeaders.setRange(rangeBuilder.toString());
      ByteArrayOutputStream byteOutput = new ByteArrayOutputStream(numBytes);
      httpReqLocal.executeMedia().download(byteOutput);
      String entityTag = httpReqLocal.getLastResponseHeaders().getETag();
      return Tuple.of(entityTag, byteOutput.toByteArray());
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = StorageException.translate(ioException);
      if (storageServiceException.getCode() == HTTP_STATUS_RANGE_NOT_SATISFIABLE) {
        return Tuple.of(null, new byte[0]);
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void write(
      String resumableUploadId,
      byte[] writeBuffer,
      int writeBufferOffset,
      long targetOffset,
      int writeLength,
      boolean isFinalChunk) {
    writeWithResponse(resumableUploadId, writeBuffer, writeBufferOffset, targetOffset, writeLength, isFinalChunk);
  }

  @Override
  public long getCurrentUploadOffset(String resumableUploadId) {
    try {
      GenericUrl requestUrl = new GenericUrl(resumableUploadId);
      HttpRequest requestObj =
          backendService.getRequestFactory().buildPutRequest(requestUrl, new EmptyContent());

      requestObj.getHeaders().setContentRange("bytes */*");
      // Turn off automatic redirects.
      // HTTP 308 are returned if upload is incomplete.
      // See: https://cloud.google.com/storage/docs/performing-resumable-uploads
      requestObj.setFollowRedirects(false);

      HttpResponse httpResponse = null;
      try {
        httpResponse = requestObj.execute();
        int responseCode = httpResponse.getStatusCode();
        if (HttpStatusCodes.isSuccess(responseCode)) {
          // Upload completed successfully
          return -1;
        }
        StringBuilder stringBuilderBuf = new StringBuilder();
        stringBuilderBuf.append("Not sure what occurred. Here's debugging information:\n");
        stringBuilderBuf.append("Response:\n").append(httpResponse.toString()).append("\n\n");
        throw new StorageException(0, stringBuilderBuf.toString());
      } catch (HttpResponseException ioException) {
        int responseCode = ioException.getStatusCode();
        if (responseCode == 308) {
          if (ioException.getHeaders().getRange() == null) {
            // No progress has been made.
            return 0;
          }
          // API returns last byte received offset
          String rangeBuilder = ioException.getHeaders().getRange();
          // Return next byte offset by adding 1 to last byte received offset
          return Long.parseLong(rangeBuilder.substring(rangeBuilder.indexOf("-") + 1)) + 1;
        } else {
          // Something else occurred like a 5xx so translateException and throw.
          throw toStorageException(ioException);
        }
      } finally {
        if (httpResponse != null) {
          httpResponse.disconnect();
        }
      }
    } catch (IOException ioException) {
      throw translateException(ioException);
    }
  }

  @Override
  public StorageObject queryCompletedResumableUpload(String resumableUploadId, long totalSizeBytes) {
    try {
      GenericUrl requestUrl = new GenericUrl(resumableUploadId);
      HttpRequest httpReqLocal = backendService.getRequestFactory().buildPutRequest(requestUrl, new EmptyContent());
      httpReqLocal.getHeaders().setContentRange(String.format("bytes */%s", totalSizeBytes));
      httpReqLocal.setParser(backendService.getObjectParser());
      HttpResponse httpResponse = httpReqLocal.execute();
      // If the response is 200
      if (httpResponse.getStatusCode() == 200) {
        return httpResponse.parseAs(StorageObject.class);
      } else {
        throw createStorageException(httpResponse.getStatusCode(), httpResponse.getStatusMessage());
      }
    } catch (IOException ioException) {
      throw translateException(ioException);
    }
  }

  @Override
  public StorageObject writeWithResponse(
      String resumableUploadId,
      byte[] writeBuffer,
      int writeBufferOffset,
      long targetOffset,
      int writeLength,
      boolean isFinalChunk) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    StorageObject updatedBlob = null;
    try {
      if (writeLength == 0 && !isFinalChunk) {
        return updatedBlob;
      }
      GenericUrl requestUrl = new GenericUrl(resumableUploadId);
      HttpRequest requestObj =
          backendService
              .getRequestFactory()
              .buildPutRequest(requestUrl, new ByteArrayContent(null, writeBuffer, writeBufferOffset, writeLength));
      long limit = targetOffset + writeLength;
      StringBuilder rangeBuilder = new StringBuilder("bytes ");
      if (writeLength == 0) {
        rangeBuilder.append('*');
      } else {
        rangeBuilder.append(targetOffset).append('-').append(limit - 1);
      }
      rangeBuilder.append('/');
      if (isFinalChunk) {
        rangeBuilder.append(limit);
      } else {
        rangeBuilder.append('*');
      }
      requestObj.getHeaders().setContentRange(rangeBuilder.toString());
      if (isFinalChunk) {
        requestObj.setParser(backendService.getObjectParser());
      }
      int responseCode;
      String message;
      IOException ioExceptionDetail = null;
      HttpResponse httpResponse = null;
      try {
        httpResponse = requestObj.execute();
        responseCode = httpResponse.getStatusCode();
        message = httpResponse.getStatusMessage();
        String resolvedContentType = httpResponse.getContentType();
        if (isFinalChunk
            && (responseCode == 200 || responseCode == 201)
            && resolvedContentType != null
            && resolvedContentType.startsWith("application/json")) {
          updatedBlob = httpResponse.parseAs(StorageObject.class);
        }
      } catch (HttpResponseException ioException) {
        ioExceptionDetail = ioException;
        responseCode = ioException.getStatusCode();
        message = ioException.getStatusMessage();
      } finally {
        if (httpResponse != null) {
          httpResponse.disconnect();
        }
      }
      if (!isFinalChunk && responseCode != 308 || isFinalChunk && !(responseCode == 200 || responseCode == 201)) {
        if (ioExceptionDetail != null) {
          throw ioExceptionDetail;
        }
        throw createStorageException(responseCode, message);
      }
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
    return updatedBlob;
  }

  @Override
  public String open(StorageObject storageObjectRef, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      String encryptionKeyName = storageObjectRef.getKmsKeyName();
      if (encryptionKeyName != null && encryptionKeyName.contains("cryptoKeyVersions")) {
        storageObjectRef.setKmsKeyName("");
      }
      Insert httpReqLocal =
          backendService
              .objects()
              .insert(storageObjectRef.getBucket(), storageObjectRef)
              .setName(storageObjectRef.getName())
              .setProjection(Option.PROJECTION.getString(storageSettings))
              .setPredefinedAcl(Option.PREDEFINED_ACL.getString(storageSettings))
              .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
              .setIfMetagenerationNotMatch(Option.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
              .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(storageSettings))
              .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
              .setUserProject(Option.USER_PROJECT.getString(storageSettings))
              .setKmsKeyName(Option.KMS_KEY_NAME.getString(storageSettings));
      GenericUrl requestUrl = httpReqLocal.buildHttpRequestUrl();
      requestUrl.setRawPath("/upload" + requestUrl.getRawPath());
      requestUrl.set("uploadType", "resumable");

      JsonFactory jsonParserFactory = backendService.getJsonFactory();
      HttpRequestFactory httpReqFactory = backendService.getRequestFactory();
      HttpRequest requestObj =
          httpReqFactory.buildPostRequest(requestUrl, new JsonHttpContent(jsonParserFactory, storageObjectRef));
      HttpHeaders reqHeaders = requestObj.getHeaders();
      reqHeaders.set("X-Upload-Content-Type", determineContentType(storageObjectRef, storageSettings));
      setEncryptionHeaders(reqHeaders, "x-goog-encryption-", storageSettings);
      HttpResponse httpResponse = requestObj.execute();
      if (httpResponse.getStatusCode() != 200) {
        throw createStorageException(httpResponse.getStatusCode(), httpResponse.getStatusMessage());
      }
      return httpResponse.getHeaders().getLocation();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public String open(String signedLink) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      GenericUrl requestUrl = new GenericUrl(signedLink);
      requestUrl.set("uploadType", "resumable");
      String byteArrayParams = "";
      byte[] byteArrayBuf = new byte[byteArrayParams.length()];
      HttpRequestFactory httpReqFactory = backendService.getRequestFactory();
      HttpRequest requestObj =
          httpReqFactory.buildPostRequest(
              requestUrl, new ByteArrayContent("", byteArrayBuf, 0, byteArrayBuf.length));
      HttpHeaders reqHeaders = requestObj.getHeaders();
      reqHeaders.set("X-Upload-Content-Type", "");
      reqHeaders.set("x-goog-resumable", "start");
      // Using the x-goog-api-client header causes a signature mismatch with signed URLs generated
      // outside the Java storage client
      reqHeaders.remove("x-goog-api-client");

      HttpResponse httpResponse = requestObj.execute();
      if (httpResponse.getStatusCode() != 201) {
        throw createStorageException(httpResponse.getStatusCode(), httpResponse.getStatusMessage());
      }
      return httpResponse.getHeaders().getLocation();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public RewriteResponse openRewrite(RewriteRequest rewriteReqObj) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return rewriteObject(rewriteReqObj, null);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public RewriteResponse continueRewrite(RewriteResponse priorRewriteResponse) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return rewriteObject(priorRewriteResponse.rewriteRequest, priorRewriteResponse.rewriteToken);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private RewriteResponse rewriteObject(RewriteRequest httpReqLocal, String rewriteToken) {
    try {
      String projectForUser = Option.USER_PROJECT.getString(httpReqLocal.sourceOptions);
      if (projectForUser == null) {
        projectForUser = Option.USER_PROJECT.getString(httpReqLocal.targetOptions);
      }

      Long maxBytesPerRewrite =
          httpReqLocal.megabytesRewrittenPerCall != null ? httpReqLocal.megabytesRewrittenPerCall * BYTES_IN_MEGABYTE : null;
      Storage.Objects.Rewrite rewriteOp =
          backendService
              .objects()
              .rewrite(
                  httpReqLocal.source.getBucket(),
                  httpReqLocal.source.getName(),
                  httpReqLocal.target.getBucket(),
                  httpReqLocal.target.getName(),
                  httpReqLocal.overrideInfo ? httpReqLocal.target : null)
              .setSourceGeneration(httpReqLocal.source.getGeneration())
              .setRewriteToken(rewriteToken)
              .setMaxBytesRewrittenPerCall(maxBytesPerRewrite)
              .setProjection(DEFAULT_PROJECTION)
              .setIfSourceMetagenerationMatch(
                  Option.IF_SOURCE_METAGENERATION_MATCH.getLong(httpReqLocal.sourceOptions))
              .setIfSourceMetagenerationNotMatch(
                  Option.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(httpReqLocal.sourceOptions))
              .setIfSourceGenerationMatch(
                  Option.IF_SOURCE_GENERATION_MATCH.getLong(httpReqLocal.sourceOptions))
              .setIfSourceGenerationNotMatch(
                  Option.IF_SOURCE_GENERATION_NOT_MATCH.getLong(httpReqLocal.sourceOptions))
              .setIfMetagenerationMatch(Option.IF_METAGENERATION_MATCH.getLong(httpReqLocal.targetOptions))
              .setIfMetagenerationNotMatch(
                  Option.IF_METAGENERATION_NOT_MATCH.getLong(httpReqLocal.targetOptions))
              .setIfGenerationMatch(Option.IF_GENERATION_MATCH.getLong(httpReqLocal.targetOptions))
              .setIfGenerationNotMatch(Option.IF_GENERATION_NOT_MATCH.getLong(httpReqLocal.targetOptions))
              .setDestinationPredefinedAcl(Option.PREDEFINED_ACL.getString(httpReqLocal.targetOptions))
              .setUserProject(projectForUser)
              .setDestinationKmsKeyName(Option.KMS_KEY_NAME.getString(httpReqLocal.targetOptions));
      HttpHeaders reqHeaders = rewriteOp.getRequestHeaders();
      setEncryptionHeaders(reqHeaders, SOURCE_ENCRYPTION_HEADER, httpReqLocal.sourceOptions);
      setEncryptionHeaders(reqHeaders, ENCRYPTION_HEADER_PREFIX, httpReqLocal.targetOptions);
      com.google.api.services.storage.model.RewriteResponse rewriteResp = rewriteOp.execute();
      return new RewriteResponse(
          httpReqLocal,
          rewriteResp.getResource(),
          rewriteResp.getObjectSize().longValue(),
          rewriteResp.getDone(),
          rewriteResp.getRewriteToken(),
          rewriteResp.getTotalBytesRewritten().longValue());
    } catch (IOException ioException) {
      traceProvider.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    }
  }

  @Override
  public BucketAccessControl getAcl(String storageBucket, String aclEntity, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .bucketAccessControls()
          .get(storageBucket, aclEntity)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String storageBucket, String aclEntity, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      backendService
          .bucketAccessControls()
          .delete(storageBucket, aclEntity)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl createAcl(BucketAccessControl accessControlEntry, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .bucketAccessControls()
          .insert(accessControlEntry.getBucket(), accessControlEntry)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl patchAcl(BucketAccessControl accessControlEntry, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .bucketAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getEntity(), accessControlEntry)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<BucketAccessControl> listAcls(String storageBucket, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .bucketAccessControls()
          .list(storageBucket)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute()
          .getItems();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getDefaultAcl(String storageBucket, String aclEntity) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService.defaultObjectAccessControls().get(storageBucket, aclEntity).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteDefaultAcl(String storageBucket, String aclEntity) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      backendService.defaultObjectAccessControls().delete(storageBucket, aclEntity).execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControlEntry) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService.defaultObjectAccessControls().insert(accessControlEntry.getBucket(), accessControlEntry).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControlEntry) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .defaultObjectAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getEntity(), accessControlEntry)
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listDefaultAcls(String storageBucket) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService.defaultObjectAccessControls().list(storageBucket).execute().getItems();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getAcl(String storageBucket, String storageObjectRef, Long generation, String aclEntity) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .objectAccessControls()
          .get(storageBucket, storageObjectRef, aclEntity)
          .setGeneration(generation)
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String storageBucket, String storageObjectRef, Long generation, String aclEntity) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      backendService
          .objectAccessControls()
          .delete(storageBucket, storageObjectRef, aclEntity)
          .setGeneration(generation)
          .execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createAcl(ObjectAccessControl accessControlEntry) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .objectAccessControls()
          .insert(accessControlEntry.getBucket(), accessControlEntry.getObject(), accessControlEntry)
          .setGeneration(accessControlEntry.getGeneration())
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchAcl(ObjectAccessControl accessControlEntry) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .objectAccessControls()
          .patch(accessControlEntry.getBucket(), accessControlEntry.getObject(), accessControlEntry.getEntity(), accessControlEntry)
          .setGeneration(accessControlEntry.getGeneration())
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listAcls(String storageBucket, String storageObjectRef, Long generation) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .objectAccessControls()
          .list(storageBucket, storageObjectRef)
          .setGeneration(generation)
          .execute()
          .getItems();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKey createHmacKey(String serviceAcctEmail, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    String projectIdentifier = Option.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return backendService
          .projects()
          .hmacKeys()
          .create(projectIdentifier, serviceAcctEmail)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    String projectIdentifier = Option.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      HmacKeysMetadata hmacKeysInfo =
          backendService
              .projects()
              .hmacKeys()
              .list(projectIdentifier)
              .setServiceAccountEmail(Option.SERVICE_ACCOUNT_EMAIL.getString(storageSettings))
              .setPageToken(Option.PAGE_TOKEN.getString(storageSettings))
              .setMaxResults(Option.MAX_RESULTS.getLong(storageSettings))
              .setShowDeletedKeys(Option.SHOW_DELETED_KEYS.getBoolean(storageSettings))
              .setUserProject(Option.USER_PROJECT.getString(storageSettings))
              .execute();
      return Tuple.<String, Iterable<HmacKeyMetadata>>of(
          hmacKeysInfo.getNextPageToken(), hmacKeysInfo.getItems());
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata getHmacKey(String hmacAccessId, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    String projectIdentifier = Option.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return backendService
          .projects()
          .hmacKeys()
          .get(projectIdentifier, hmacAccessId)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyInfo, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    String projectIdentifier = hmacKeyInfo.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return backendService
          .projects()
          .hmacKeys()
          .update(projectIdentifier, hmacKeyInfo.getAccessId(), hmacKeyInfo)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void deleteHmacKey(HmacKeyMetadata hmacKeyInfo, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    String projectIdentifier = hmacKeyInfo.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      backendService
          .projects()
          .hmacKeys()
          .delete(projectIdentifier, hmacKeyInfo.getAccessId())
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy getIamPolicy(String storageBucket, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      Storage.Buckets.GetIamPolicy iamPolicyRequest =
          backendService
              .buckets()
              .getIamPolicy(storageBucket)
              .setUserProject(Option.USER_PROJECT.getString(storageSettings));
      if (null != Option.REQUESTED_POLICY_VERSION.getLong(storageSettings)) {
        iamPolicyRequest.setOptionsRequestedPolicyVersion(
            Option.REQUESTED_POLICY_VERSION.getLong(storageSettings).intValue());
      }
      return iamPolicyRequest.execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy setIamPolicy(String storageBucket, Policy iamPolicy, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .buckets()
          .setIamPolicy(storageBucket, iamPolicy)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public TestIamPermissionsResponse testIamPermissions(
      String storageBucket, List<String> permissionList, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .buckets()
          .testIamPermissions(storageBucket, permissionList)
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteNotification(String storageBucket, String notificationObject) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      backendService.notifications().delete(storageBucket, notificationObject).execute();
      return true;
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<Notification> listNotifications(String storageBucket) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService.notifications().list(storageBucket).execute().getItems();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Notification createNotification(String storageBucket, Notification notificationObject) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService.notifications().insert(storageBucket, notificationObject).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Notification getNotification(String storageBucket, String notificationObject) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_NOTIFICATION);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService.notifications().get(storageBucket, notificationObject).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageException storageServiceException = translateException(ioException);
      if (storageServiceException.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceException;
    } finally {
      traceScope.close();
      traceSpan.end();
    }
  }

  @Override
  public Bucket lockRetentionPolicy(Bucket storageBucket, Map<Option, ?> storageSettings) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService
          .buckets()
          .lockRetentionPolicy(storageBucket.getName(), Option.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setUserProject(Option.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ServiceAccount getServiceAccount(String projectIdentifier) {
    Span traceSpan = beginSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
    Scope traceScope = traceProvider.withSpan(traceSpan);
    try {
      return backendService.projects().serviceAccount().get(projectIdentifier).execute();
    } catch (IOException ioException) {
      traceSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      traceScope.close();
      traceSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private static StorageException createStorageException(int respStatusCode, String respStatusMessage) {
    GoogleJsonError jsonError = new GoogleJsonError();
    jsonError.setCode(respStatusCode);
    jsonError.setMessage(respStatusMessage);
    return toStorageException(jsonError);
  }
}
