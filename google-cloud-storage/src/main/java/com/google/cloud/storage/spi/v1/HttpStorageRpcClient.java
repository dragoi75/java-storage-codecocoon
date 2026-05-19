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
import com.google.cloud.storage.StorageServiceException;
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

public class HttpStorageRpcClient implements StorageRpcClient {
  public static final String DEFAULT_PROJECTION = "full";
  public static final String NO_ACL_PROJECTION = "noAcl";
  private static final String ENCRYPTION_PREFIX = "x-goog-encryption-";
  private static final String SOURCE_ENCRYPTION_PREFIX = "x-goog-copy-source-encryption-";

  // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
  private static final int HTTP_SC_RANGE_NOT_SATISFIABLE = 416;

  private final StorageSettings storageSettings;
  private final Storage storageService;
  private final Tracer requestTracer = Tracing.getTracer();
  private final CensusHttpModule httpCensusModule;
  private final HttpRequestInitializer requestInitializer;

  private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

  public HttpStorageRpcClient(StorageSettings storageSettings) {
    HttpTransportOptions transportOpts = (HttpTransportOptions) storageSettings.getTransportOptions();
    HttpTransport httpTransport = transportOpts.getHttpTransportFactory().create();
    HttpRequestInitializer requestInit = transportOpts.getHttpRequestInitializer(storageSettings);
    this.storageSettings = storageSettings;

    // Open Census initialization
    httpCensusModule = new CensusHttpModule(requestTracer, true);
    requestInit = httpCensusModule.getHttpRequestInitializer(requestInit);
    requestInitializer = httpCensusModule.getHttpRequestInitializer(null);
    storageService =
        new Storage.Builder(httpTransport, new JacksonFactory(), requestInit)
            .setRootUrl(storageSettings.getHost())
            .setApplicationName(storageSettings.getApplicationName())
            .build();
  }

  private class DefaultRpcBatchExecutor implements RpcBatchRequest {

    // Batch size is limited as, due to some current service implementation details, the service
    // performs better if the batches are split for better distribution. See
    // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
    // background.
    private static final int BATCH_SIZE_LIMIT = 100;

    private final Storage storageService;
    private final LinkedList<BatchRequest> batchQueue;
    private int batchSizeCurrent;

    private DefaultRpcBatchExecutor(Storage storageService) {
      this.storageService = storageService;
      batchQueue = new LinkedList<>();
      // add OpenCensus HttpRequestInitializer
      batchQueue.add(storageService.batch(requestInitializer));
    }

    @Override
    public void addDeleteOperation(
            StorageObject targetObject, CompletionHandler<Void> completionHandler, Map<StorageOption, ?> storageSettings) {
      try {
        if (batchSizeCurrent == BATCH_SIZE_LIMIT) {
          batchQueue.add(storageService.batch());
          batchSizeCurrent = 0;
        }
        createDeleteCall(targetObject, storageSettings).queue(batchQueue.getLast(), asJsonCallback(completionHandler));
        batchSizeCurrent++;
      } catch (IOException ioException) {
        throw translateException(ioException);
      }
    }

    @Override
    public void addPatchOperation(
        StorageObject targetObject,
        CompletionHandler<StorageObject> completionHandler,
        Map<StorageOption, ?> storageSettings) {
      try {
        if (batchSizeCurrent == BATCH_SIZE_LIMIT) {
          batchQueue.add(storageService.batch());
          batchSizeCurrent = 0;
        }
        createPatchCall(targetObject, storageSettings).queue(batchQueue.getLast(), asJsonCallback(completionHandler));
        batchSizeCurrent++;
      } catch (IOException ioException) {
        throw translateException(ioException);
      }
    }

    @Override
    public void addGetRequest(
        StorageObject targetObject,
        CompletionHandler<StorageObject> completionHandler,
        Map<StorageOption, ?> storageSettings) {
      try {
        if (batchSizeCurrent == BATCH_SIZE_LIMIT) {
          batchQueue.add(storageService.batch());
          batchSizeCurrent = 0;
        }
        getCall(targetObject, storageSettings).queue(batchQueue.getLast(), asJsonCallback(completionHandler));
        batchSizeCurrent++;
      } catch (IOException ioException) {
        throw translateException(ioException);
      }
    }

    @Override
    public void submitBatch() {
      Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
      Scope spanScope = requestTracer.withSpan(batchSpan);
      try {
        batchSpan.putAttribute("batch size", AttributeValue.longAttributeValue(batchQueue.size()));
        for (BatchRequest batchRequest : batchQueue) {
          // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
          // Here we only add a annotation to at least know how much time each batch takes.
          batchSpan.addAnnotation("Execute batch request");
          batchRequest.setBatchUrl(
              new GenericUrl(String.format("%s/batch/storage/v1", storageSettings.getHost())));
          batchRequest.execute();
        }
      } catch (IOException ioException) {
        batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
        throw translateException(ioException);
      } finally {
        spanScope.close();
        batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
      }
    }
  }

  private static <T> JsonBatchCallback<T> asJsonCallback(final RpcBatchRequest.CompletionHandler<T> completionHandler) {
    return new JsonBatchCallback<T>() {
      @Override
      public void onSuccess(T response, HttpHeaders httpHeaders) throws IOException {
        completionHandler.handleSuccess(response);
      }

      @Override
      public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders)
          throws IOException {
        completionHandler.handleFailure(googleJsonError);
      }
    };
  }

  private static StorageServiceException translateException(IOException ioException) {
    return new StorageServiceException(ioException);
  }

  private static StorageServiceException translateException(GoogleJsonError ioException) {
    return new StorageServiceException(ioException);
  }

  private static void setEncryptionHeaders(
          HttpHeaders httpMetadata, String hdrPrefix, Map<StorageOption, ?> storageSettings) {
    String secretValue = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(storageSettings);
    if (secretValue != null) {
      BaseEncoding baseEncoder = BaseEncoding.base64();
      HashFunction digestFn = Hashing.sha256();
      httpMetadata.set(hdrPrefix + "algorithm", "AES256");
      httpMetadata.set(hdrPrefix + "key", secretValue);
      httpMetadata.set(
          hdrPrefix + "key-sha256",
          baseEncoder.encode(digestFn.hashBytes(baseEncoder.decode(secretValue)).asBytes()));
    }
  }

  /** Helper method to start a span. */
  private Span createSpan(String operationName) {
    return requestTracer
        .spanBuilder(operationName)
        .setRecordEvents(httpCensusModule.isRecordEvents())
        .startSpan();
  }

  @Override
  public Bucket create(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .buckets()
          .insert(this.storageSettings.getProjectId(), container)
          .setProjection(DEFAULT_PROJECTION)
          .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings))
          .setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject create(
          StorageObject targetObject, final InputStream dataStream, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      Storage.Objects.Insert uploadRequest =
          storageService
              .objects()
              .insert(
                  targetObject.getBucket(),
                      targetObject,
                  new InputStreamContent(targetObject.getContentType(), dataStream));
      uploadRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
      Boolean gzipDisabled = StorageOption.IF_DISABLE_GZIP_CONTENT.getBoolean(storageSettings);
      if (gzipDisabled != null) {
        uploadRequest.setDisableGZipContent(gzipDisabled);
      }
      setEncryptionHeaders(uploadRequest.getRequestHeaders(), ENCRYPTION_PREFIX, storageSettings);
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
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      Buckets collection =
          storageService
              .buckets()
              .list(this.storageSettings.getProjectId())
              .setProjection(DEFAULT_PROJECTION)
              .setPrefix(StorageOption.PREFIX.getString(storageSettings))
              .setMaxResults(StorageOption.MAX_RESULTS.getLong(storageSettings))
              .setPageToken(StorageOption.PAGE_TOKEN.getString(storageSettings))
              .setFields(StorageOption.FIELDS.getString(storageSettings))
              .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
              .execute();
      return Tuple.<String, Iterable<Bucket>>of(collection.getNextPageToken(), collection.getItems());
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<StorageObject>> list(final String container, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      Objects itemList =
          storageService
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
      Iterable<StorageObject> itemIterable =
          Iterables.concat(
              firstNonNull(itemList.getItems(), ImmutableList.<StorageObject>of()),
              itemList.getPrefixes() != null
                  ? Lists.transform(itemList.getPrefixes(), storageObjectFromPrefix(container))
                  : ImmutableList.<StorageObject>of());
      return Tuple.of(itemList.getNextPageToken(), itemIterable);
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
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
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .buckets()
          .get(container.getName())
          .setProjection(DEFAULT_PROJECTION)
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setFields(StorageOption.FIELDS.getString(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Get getCall(StorageObject storageItem, Map<StorageOption, ?> storageSettings)
      throws IOException {
    Storage.Objects.Get fetchRequest = storageService.objects().get(storageItem.getBucket(), storageItem.getName());
    setEncryptionHeaders(fetchRequest.getRequestHeaders(), ENCRYPTION_PREFIX, storageSettings);
    return fetchRequest.setGeneration(storageItem.getGeneration())
        .setProjection(DEFAULT_PROJECTION)
        .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
        .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
        .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
        .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
        .setFields(StorageOption.FIELDS.getString(storageSettings))
        .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
  }

  @Override
  public StorageObject get(StorageObject storageItem, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return getCall(storageItem, storageSettings).execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket patch(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      String projectionMode = StorageOption.PROJECTION.getString(storageSettings);
      if (container.getIamConfiguration() != null
          && container.getIamConfiguration().getBucketPolicyOnly() != null
          && container.getIamConfiguration().getBucketPolicyOnly().getEnabled() != null
          && container.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
        // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
        // the request
        container.setDefaultObjectAcl(null);
        container.setAcl(null);

        if (projectionMode == null) {
          projectionMode = NO_ACL_PROJECTION;
        }
      }
      return storageService
          .buckets()
          .patch(container.getName(), container)
          .setProjection(projectionMode == null ? DEFAULT_PROJECTION : projectionMode)
          .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings))
          .setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(storageSettings))
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Patch createPatchCall(StorageObject targetObject, Map<StorageOption, ?> storageSettings)
      throws IOException {
    return storageService
        .objects()
        .patch(targetObject.getBucket(), targetObject.getName(), targetObject)
        .setProjection(DEFAULT_PROJECTION)
        .setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings))
        .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
        .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
        .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
        .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
        .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
  }

  @Override
  public StorageObject patch(StorageObject targetObject, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return createPatchCall(targetObject, storageSettings).execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean delete(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      storageService
          .buckets()
          .delete(container.getName())
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
      return true;
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Storage.Objects.Delete createDeleteCall(StorageObject storageItem, Map<StorageOption, ?> storageSettings)
      throws IOException {
    return storageService
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
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      createDeleteCall(storageItem, storageSettings).execute();
      return true;
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageObject compose(
          Iterable<StorageObject> sourceItems, StorageObject destination, Map<StorageOption, ?> targetOptionMap) {
    ComposeRequest composeOperation = new ComposeRequest();
    composeOperation.setDestination(destination);
    List<ComposeRequest.SourceObjects> sourceList = new ArrayList<>();
    for (StorageObject storageItem : sourceItems) {
      ComposeRequest.SourceObjects objectEntry = new ComposeRequest.SourceObjects();
      objectEntry.setName(storageItem.getName());
      Long genNumber = storageItem.getGeneration();
      if (genNumber != null) {
        objectEntry.setGeneration(genNumber);
        objectEntry.setObjectPreconditions(
            new ObjectPreconditions().setIfGenerationMatch(genNumber));
      }
      sourceList.add(objectEntry);
    }
    composeOperation.setSourceObjects(sourceList);
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .objects()
          .compose(destination.getBucket(), destination.getName(), composeOperation)
          .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(targetOptionMap))
          .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(targetOptionMap))
          .setUserProject(StorageOption.USER_PROJECT.getString(targetOptionMap))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public byte[] load(StorageObject sourceRef, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      Storage.Objects.Get getOperation =
          storageService
              .objects()
              .get(sourceRef.getBucket(), sourceRef.getName())
              .setGeneration(sourceRef.getGeneration())
              .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
              .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
              .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
              .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
              .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
      setEncryptionHeaders(getOperation.getRequestHeaders(), ENCRYPTION_PREFIX, storageSettings);
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
      getOperation.executeMedia().download(byteStream);
      return byteStream.toByteArray();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public RpcBatchRequest createBatch() {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BATCH);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return new DefaultRpcBatchExecutor(storageService);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private Get buildReadRequest(StorageObject sourceRef, Map<StorageOption, ?> storageSettings) throws IOException {
    Get getOperation =
        storageService
            .objects()
            .get(sourceRef.getBucket(), sourceRef.getName())
            .setGeneration(sourceRef.getGeneration())
            .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
            .setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings))
            .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings))
            .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings))
            .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
    setEncryptionHeaders(getOperation.getRequestHeaders(), ENCRYPTION_PREFIX, storageSettings);
    getOperation.setReturnRawInputStream(true);
    return getOperation;
  }

  @Override
  public long read(
          StorageObject sourceRef, Map<StorageOption, ?> storageSettings, long offset, OutputStream destStream) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      Get getOperation = buildReadRequest(sourceRef, storageSettings);
      getOperation.getMediaHttpDownloader().setBytesDownloaded(offset);
      getOperation.getMediaHttpDownloader().setDirectDownloadEnabled(true);
      getOperation.executeMediaAndDownloadTo(destStream);
      return getOperation.getMediaHttpDownloader().getNumBytesDownloaded();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_SC_RANGE_NOT_SATISFIABLE) {
        return 0;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, byte[]> read(
          StorageObject sourceRef, Map<StorageOption, ?> storageSettings, long offset, int byteCount) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      checkArgument(offset >= 0, "Position should be non-negative, is " + offset);
      Get getOperation = buildReadRequest(sourceRef, storageSettings);
      StringBuilder intervalBuilder = new StringBuilder();
      intervalBuilder.append("bytes=").append(offset).append("-").append(offset + byteCount - 1);
      HttpHeaders headersCollection = getOperation.getRequestHeaders();
      headersCollection.setRange(intervalBuilder.toString());
      ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream(byteCount);
      getOperation.executeMedia().download(byteArrayStream);
      String entityTag = getOperation.getLastResponseHeaders().getETag();
      return Tuple.of(entityTag, byteArrayStream.toByteArray());
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = StorageServiceException.translateException(ioException);
      if (storageServiceError.getCode() == HTTP_SC_RANGE_NOT_SATISFIABLE) {
        return Tuple.of(null, new byte[0]);
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void write(
      String uploadIdentifier,
      byte[] writeBuffer,
      int bufferOffset,
      long destinationOffset,
      int size,
      boolean isFinal) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      if (size == 0 && !isFinal) {
        return;
      }
      GenericUrl targetUrl = new GenericUrl(uploadIdentifier);
      HttpRequest request =
          storageService
              .getRequestFactory()
              .buildPutRequest(targetUrl, new ByteArrayContent(null, writeBuffer, bufferOffset, size));
      long maxLimit = destinationOffset + size;
      StringBuilder intervalBuilder = new StringBuilder("bytes ");
      if (size == 0) {
        intervalBuilder.append('*');
      } else {
        intervalBuilder.append(destinationOffset).append('-').append(maxLimit - 1);
      }
      intervalBuilder.append('/');
      if (isFinal) {
        intervalBuilder.append(maxLimit);
      } else {
        intervalBuilder.append('*');
      }
      request.getHeaders().setContentRange(intervalBuilder.toString());
      int statusCode;
      String responseMessage;
      IOException ioException = null;
      HttpResponse result = null;
      try {
        result = request.execute();
        statusCode = result.getStatusCode();
        responseMessage = result.getStatusMessage();
      } catch (HttpResponseException ioError) {
        ioException = ioError;
        statusCode = ioError.getStatusCode();
        responseMessage = ioError.getStatusMessage();
      } finally {
        if (result != null) {
          result.disconnect();
        }
      }
      if (!isFinal && statusCode != 308 || isFinal && !(statusCode == 200 || statusCode == 201)) {
        if (ioException != null) {
          throw ioException;
        }
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(statusCode);
        jsonError.setMessage(responseMessage);
        throw translateException(jsonError);
      }
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public String open(StorageObject storageItem, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      Insert getOperation = storageService.objects().insert(storageItem.getBucket(), storageItem);
      GenericUrl targetUrl = getOperation.buildHttpRequest().getUrl();
      String urlScheme = targetUrl.getScheme();
      String hostName = targetUrl.getHost();
      int serverPort = targetUrl.getPort();
      serverPort = serverPort > 0 ? serverPort : targetUrl.toURL().getDefaultPort();
      String resourcePath = "/upload" + targetUrl.getRawPath();
      targetUrl = new GenericUrl(urlScheme + "://" + hostName + ":" + serverPort + resourcePath);
      targetUrl.set("uploadType", "resumable");
      targetUrl.set("name", storageItem.getName());
      for (StorageOption storageOption : storageSettings.keySet()) {
        Object dataStream = storageOption.get(storageSettings);
        if (dataStream != null) {
          targetUrl.set(storageOption.getValue(), dataStream.toString());
        }
      }
      JsonFactory jsonParserFactory = storageService.getJsonFactory();
      HttpRequestFactory httpRequestFactory = storageService.getRequestFactory();
      HttpRequest request =
          httpRequestFactory.buildPostRequest(targetUrl, new JsonHttpContent(jsonParserFactory, storageItem));
      HttpHeaders headersCollection = request.getHeaders();
      headersCollection.set(
          "X-Upload-Content-Type",
          firstNonNull(storageItem.getContentType(), "application/octet-stream"));
      String secretValue = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(storageSettings);
      if (secretValue != null) {
        BaseEncoding baseEncoder = BaseEncoding.base64();
        HashFunction digestFn = Hashing.sha256();
        headersCollection.set("x-goog-encryption-algorithm", "AES256");
        headersCollection.set("x-goog-encryption-key", secretValue);
        headersCollection.set(
            "x-goog-encryption-key-sha256",
            baseEncoder.encode(digestFn.hashBytes(baseEncoder.decode(secretValue)).asBytes()));
      }
      HttpResponse result = request.execute();
      if (result.getStatusCode() != 200) {
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(result.getStatusCode());
        jsonError.setMessage(result.getStatusMessage());
        throw translateException(jsonError);
      }
      return result.getHeaders().getLocation();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public String open(String presignedLink) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      GenericUrl targetUrl = new GenericUrl(presignedLink);
      targetUrl.set("uploadType", "resumable");
      String byteParams = "";
      byte[] rawBytes = new byte[byteParams.length()];
      HttpRequestFactory httpRequestFactory = storageService.getRequestFactory();
      HttpRequest request =
          httpRequestFactory.buildPostRequest(
                  targetUrl, new ByteArrayContent("", rawBytes, 0, rawBytes.length));
      HttpHeaders headersCollection = request.getHeaders();
      headersCollection.set("X-Upload-Content-Type", "");
      headersCollection.set("x-goog-resumable", "start");
      HttpResponse result = request.execute();
      if (result.getStatusCode() != 201) {
        GoogleJsonError jsonError = new GoogleJsonError();
        jsonError.setCode(result.getStatusCode());
        jsonError.setMessage(result.getStatusMessage());
        throw translateException(jsonError);
      }
      return result.getHeaders().getLocation();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageRpcClient.RewriteOperationResult openRewrite(RewriteOperationRequest operationReq) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return performRewrite(operationReq, null);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public StorageRpcClient.RewriteOperationResult continueRewrite(RewriteOperationResult priorResult) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return performRewrite(priorResult.rewriteRequest, priorResult.rewriteToken);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  private RewriteOperationResult performRewrite(RewriteOperationRequest getOperation, String authKey) {
    try {
      String projectId = StorageOption.USER_PROJECT.getString(getOperation.sourceOptions);
      if (projectId == null) {
        projectId = StorageOption.USER_PROJECT.getString(getOperation.targetOptions);
      }

      Long maxBytesPerCall =
          getOperation.megabytesRewrittenPerCall != null ? getOperation.megabytesRewrittenPerCall * BYTES_PER_MEGABYTE : null;
      Storage.Objects.Rewrite operationInstance =
          storageService
              .objects()
              .rewrite(
                  getOperation.source.getBucket(),
                  getOperation.source.getName(),
                  getOperation.target.getBucket(),
                  getOperation.target.getName(),
                  getOperation.overrideInfo ? getOperation.target : null)
              .setSourceGeneration(getOperation.source.getGeneration())
              .setRewriteToken(authKey)
              .setMaxBytesRewrittenPerCall(maxBytesPerCall)
              .setProjection(DEFAULT_PROJECTION)
              .setIfSourceMetagenerationMatch(
                  StorageOption.IF_SOURCE_METAGENERATION_MATCH.getLong(getOperation.sourceOptions))
              .setIfSourceMetagenerationNotMatch(
                  StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(getOperation.sourceOptions))
              .setIfSourceGenerationMatch(
                  StorageOption.IF_SOURCE_GENERATION_MATCH.getLong(getOperation.sourceOptions))
              .setIfSourceGenerationNotMatch(
                  StorageOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(getOperation.sourceOptions))
              .setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(getOperation.targetOptions))
              .setIfMetagenerationNotMatch(
                  StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(getOperation.targetOptions))
              .setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(getOperation.targetOptions))
              .setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(getOperation.targetOptions))
              .setDestinationPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(getOperation.targetOptions))
              .setUserProject(projectId)
              .setDestinationKmsKeyName(StorageOption.KMS_KEY_NAME.getString(getOperation.targetOptions));
      HttpHeaders headersCollection = operationInstance.getRequestHeaders();
      setEncryptionHeaders(headersCollection, SOURCE_ENCRYPTION_PREFIX, getOperation.sourceOptions);
      setEncryptionHeaders(headersCollection, ENCRYPTION_PREFIX, getOperation.targetOptions);
      com.google.api.services.storage.model.RewriteResponse operationResult = operationInstance.execute();
      return new RewriteOperationResult(
              getOperation,
          operationResult.getResource(),
          operationResult.getObjectSize().longValue(),
          operationResult.getDone(),
          operationResult.getRewriteToken(),
          operationResult.getTotalBytesRewritten().longValue());
    } catch (IOException ioException) {
      requestTracer.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    }
  }

  @Override
  public BucketAccessControl getAcl(String container, String principalId, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .bucketAccessControls()
          .get(container, principalId)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String container, String principalId, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      storageService
          .bucketAccessControls()
          .delete(container, principalId)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
      return true;
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl createAcl(BucketAccessControl bucketAccessControl, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .bucketAccessControls()
          .insert(bucketAccessControl.getBucket(), bucketAccessControl)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public BucketAccessControl patchAcl(BucketAccessControl bucketAccessControl, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .bucketAccessControls()
          .patch(bucketAccessControl.getBucket(), bucketAccessControl.getEntity(), bucketAccessControl)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<BucketAccessControl> listAcls(String container, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .bucketAccessControls()
          .list(container)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute()
          .getItems();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getDefaultAcl(String container, String principalId) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService.defaultObjectAccessControls().get(container, principalId).execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteDefaultAcl(String container, String principalId) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      storageService.defaultObjectAccessControls().delete(container, principalId).execute();
      return true;
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createDefaultAcl(ObjectAccessControl bucketAccessControl) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService.defaultObjectAccessControls().insert(bucketAccessControl.getBucket(), bucketAccessControl).execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchDefaultAcl(ObjectAccessControl bucketAccessControl) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .defaultObjectAccessControls()
          .patch(bucketAccessControl.getBucket(), bucketAccessControl.getEntity(), bucketAccessControl)
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listDefaultAcls(String container) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService.defaultObjectAccessControls().list(container).execute().getItems();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl getAcl(String container, String storageItem, Long genNumber, String principalId) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .objectAccessControls()
          .get(container, storageItem, principalId)
          .setGeneration(genNumber)
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return null;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteAcl(String container, String storageItem, Long genNumber, String principalId) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      storageService
          .objectAccessControls()
          .delete(container, storageItem, principalId)
          .setGeneration(genNumber)
          .execute();
      return true;
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl createAcl(ObjectAccessControl bucketAccessControl) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .objectAccessControls()
          .insert(bucketAccessControl.getBucket(), bucketAccessControl.getObject(), bucketAccessControl)
          .setGeneration(bucketAccessControl.getGeneration())
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ObjectAccessControl patchAcl(ObjectAccessControl bucketAccessControl) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .objectAccessControls()
          .patch(bucketAccessControl.getBucket(), bucketAccessControl.getObject(), bucketAccessControl.getEntity(), bucketAccessControl)
          .setGeneration(bucketAccessControl.getGeneration())
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<ObjectAccessControl> listAcls(String container, String storageItem, Long genNumber) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .objectAccessControls()
          .list(container, storageItem)
          .setGeneration(genNumber)
          .execute()
          .getItems();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKey createHmacKey(String accountEmail, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return storageService
          .projects()
          .hmacKeys()
          .create(projectIdentifier, accountEmail)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      HmacKeysMetadata credentialsMetadata =
          storageService
              .projects()
              .hmacKeys()
              .list(projectIdentifier)
              .setServiceAccountEmail(StorageOption.SERVICE_ACCOUNT_EMAIL.getString(storageSettings))
              .setPageToken(StorageOption.PAGE_TOKEN.getString(storageSettings))
              .setMaxResults(StorageOption.MAX_RESULTS.getLong(storageSettings))
              .setShowDeletedKeys(StorageOption.SHOW_DELETED_KEYS.getBoolean(storageSettings))
              .execute();
      return Tuple.<String, Iterable<HmacKeyMetadata>>of(
          credentialsMetadata.getNextPageToken(), credentialsMetadata.getItems());
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata getHmacKey(String credentialKey, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return storageService
          .projects()
          .hmacKeys()
          .get(projectIdentifier, credentialKey)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public HmacKeyMetadata updateHmacKey(HmacKeyMetadata keyMetadata, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    String projectIdentifier = keyMetadata.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      return storageService
          .projects()
          .hmacKeys()
          .update(projectIdentifier, keyMetadata.getAccessId(), keyMetadata)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public void deleteHmacKey(HmacKeyMetadata keyMetadata, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    String projectIdentifier = keyMetadata.getProjectId();
    if (projectIdentifier == null) {
      projectIdentifier = this.storageSettings.getProjectId();
    }
    try {
      storageService
          .projects()
          .hmacKeys()
          .delete(projectIdentifier, keyMetadata.getAccessId())
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy getIamPolicy(String container, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      Storage.Buckets.GetIamPolicy policyRequest =
          storageService
              .buckets()
              .getIamPolicy(container)
              .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
      if (null != StorageOption.REQUESTED_POLICY_VERSION.getLong(storageSettings)) {
        policyRequest.setOptionsRequestedPolicyVersion(
            StorageOption.REQUESTED_POLICY_VERSION.getLong(storageSettings).intValue());
      }
      return policyRequest.execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Policy setIamPolicy(String container, Policy accessControl, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .buckets()
          .setIamPolicy(container, accessControl)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public TestIamPermissionsResponse testIamPermissions(
          String container, List<String> allowedActions, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .buckets()
          .testIamPermissions(container, allowedActions)
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public boolean deleteNotification(String container, String alertId) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      storageService.notifications().delete(container, alertId).execute();
      return true;
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      StorageServiceException storageServiceError = translateException(ioException);
      if (storageServiceError.getCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw storageServiceError;
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public List<Notification> listNotifications(String container) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService.notifications().list(container).execute().getItems();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Notification createNotification(String container, Notification alertId) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService.notifications().insert(container, alertId).execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public Bucket lockRetentionPolicy(Bucket container, Map<StorageOption, ?> storageSettings) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService
          .buckets()
          .lockRetentionPolicy(container.getName(), StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings))
          .setUserProject(StorageOption.USER_PROJECT.getString(storageSettings))
          .execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }

  @Override
  public ServiceAccount getServiceAccount(String projectIdentifier) {
    Span batchSpan = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
    Scope spanScope = requestTracer.withSpan(batchSpan);
    try {
      return storageService.projects().serviceAccount().get(projectIdentifier).execute();
    } catch (IOException ioException) {
      batchSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
      throw translateException(ioException);
    } finally {
      spanScope.close();
      batchSpan.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
    }
  }
}
