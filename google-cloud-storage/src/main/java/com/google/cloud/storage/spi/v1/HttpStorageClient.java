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

public class HttpStorageClient implements CloudStorageRpc {

    public static final String DEFAULT_PROJECTION = "full";

    public static final String NO_ACL_PROJECTION = "noAcl";

    private static final String CRYPTO_KEY_PREFIX = "x-goog-encryption-";

    private static final String SOURCE_CRYPTO_PREFIX = "x-goog-copy-source-encryption-";

    // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
    private static final int STATUS_REQUESTED_RANGE_NOT_SATISFIABLE = 416;

    private final StorageSettings storageSettings;

    private final Storage backendStore;

    private final Tracer telemetry = Tracing.getTracer();

    private final CensusHttpModule metricsModule;

    private final HttpRequestInitializer requestInitializer;

    private static final long BYTES_PER_MB = 1024L * 1024L;

    public HttpStorageClient(StorageSettings storageSettings) {
        HttpTransportOptions transportConfig = (HttpTransportOptions) storageSettings.getTransportOptions();
        HttpTransport httpClient = transportConfig.getHttpTransportFactory().create();
        HttpRequestInitializer requestInit = transportConfig.getHttpRequestInitializer(storageSettings);
        this.storageSettings = storageSettings;
        // Open Census initialization
        metricsModule = new CensusHttpModule(telemetry, true);
        requestInit = metricsModule.getHttpRequestInitializer(requestInit);
        requestInitializer = metricsModule.getHttpRequestInitializer(null);
        HttpStorageRpcSpans.registerAllSpanNamesForCollection();
        backendStore = new Storage.Builder(httpClient, new JacksonFactory(), requestInit).setRootUrl(storageSettings.getHost()).setApplicationName(storageSettings.getApplicationName()).build();
    }

    private class RpcBatchProcessor implements RpcRequestBatch {

        // Batch size is limited as, due to some current service implementation details, the service
        // performs better if the batches are split for better distribution. See
        // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
        // background.
        private static final int BATCH_SIZE_LIMIT = 100;

        private final Storage backendStore;

        private final LinkedList<BatchRequest> batchQueue;

        private int currentSize;

        private RpcBatchProcessor(Storage backendStore) {
            this.backendStore = backendStore;
            batchQueue = new LinkedList<>();
            // add OpenCensus HttpRequestInitializer
            batchQueue.add(backendStore.batch(requestInitializer));
        }

        @Override
        public void addDeleteRequest(StorageObject objectMetadata, ResultHandler<Void> resultHandler, Map<StorageOption, ?> storageSettings) {
            try {
                if (BATCH_SIZE_LIMIT == currentSize) {
                    batchQueue.add(backendStore.batch());
                    currentSize = 0;
                }
                buildDeleteCall(objectMetadata, storageSettings).queue(batchQueue.getLast(), createJsonCallback(resultHandler));
                currentSize += 1;
            } catch (IOException ioEx) {
                throw translateException(ioEx);
            }
        }

        @Override
        public void addPatchRequest(StorageObject objectMetadata, ResultHandler<StorageObject> resultHandler, Map<StorageOption, ?> storageSettings) {
            try {
                if (BATCH_SIZE_LIMIT == currentSize) {
                    batchQueue.add(backendStore.batch());
                    currentSize = 0;
                }
                buildPatchCall(objectMetadata, storageSettings).queue(batchQueue.getLast(), createJsonCallback(resultHandler));
                currentSize += 1;
            } catch (IOException ioEx) {
                throw translateException(ioEx);
            }
        }

        @Override
        public void addGetRequest(StorageObject objectMetadata, ResultHandler<StorageObject> resultHandler, Map<StorageOption, ?> storageSettings) {
            try {
                if (BATCH_SIZE_LIMIT == currentSize) {
                    batchQueue.add(backendStore.batch());
                    currentSize = 0;
                }
                getCall(objectMetadata, storageSettings).queue(batchQueue.getLast(), createJsonCallback(resultHandler));
                currentSize += 1;
            } catch (IOException ioEx) {
                throw translateException(ioEx);
            }
        }

        @Override
        public void submitBatch() {
            Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
            Scope contextHandle = telemetry.withSpan(traceContext);
            try {
                traceContext.putAttribute("batch size", AttributeValue.longAttributeValue(batchQueue.size()));
                for (BatchRequest rpcRequest : batchQueue) {
                    // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
                    // Here we only add a annotation to at least know how much time each batch takes.
                    traceContext.addAnnotation("Execute batch request");
                    rpcRequest.setBatchUrl(new GenericUrl(String.format("%s/batch/storage/v1", storageSettings.getHost())));
                    rpcRequest.execute();
                }
            } catch (IOException ioEx) {
                traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
                throw translateException(ioEx);
            } finally {
                contextHandle.close();
                traceContext.end();
            }
        }
    }

    private static <T> JsonBatchCallback<T> createJsonCallback(final RpcRequestBatch.ResultHandler<T> resultHandler) {
        return new JsonBatchCallback<T>() {

            @Override
            public void onSuccess(T response, HttpHeaders httpHeaders) throws IOException {
                resultHandler.handleSuccess(response);
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders) throws IOException {
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

    private static void setEncryptionHeaders(HttpHeaders httpMetadata, String encryptionPrefix, Map<StorageOption, ?> storageSettings) {
        String secretValue = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(storageSettings);
        if (null != secretValue) {
            BaseEncoding binaryEncoder = BaseEncoding.base64();
            HashFunction hasher = Hashing.sha256();
            httpMetadata.set(encryptionPrefix + "algorithm", "AES256");
            httpMetadata.set(encryptionPrefix + "key", secretValue);
            httpMetadata.set(encryptionPrefix + "key-sha256", binaryEncoder.encode(hasher.hashBytes(binaryEncoder.decode(secretValue)).asBytes()));
        }
    }

    /**
     * Helper method to start a span.
     */
    private Span createSpan(String operationName) {
        return telemetry.spanBuilder(operationName).setRecordEvents(metricsModule.isRecordEvents()).startSpan();
    }

    @Override
    public Bucket create(Bucket container, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.buckets().insert(this.storageSettings.getProjectId(), container).setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings)).setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public StorageObject create(StorageObject objectMetadata, final InputStream inputStream, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            Storage.Objects.Insert createRequest = backendStore.objects().insert(objectMetadata.getBucket(), objectMetadata, new InputStreamContent(objectMetadata.getContentType(), inputStream));
            createRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
            Boolean skipGzipCompression = StorageOption.IF_DISABLE_GZIP_CONTENT.getBoolean(storageSettings);
            if (null != skipGzipCompression) {
                createRequest.setDisableGZipContent(skipGzipCompression);
            }
            setEncryptionHeaders(createRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, storageSettings);
            return createRequest.setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).setKmsKeyName(StorageOption.KMS_KEY_NAME.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            Buckets containerCollection = backendStore.buckets().list(this.storageSettings.getProjectId()).setProjection(DEFAULT_PROJECTION).setPrefix(StorageOption.PREFIX.getString(storageSettings)).setMaxResults(StorageOption.MAX_RESULTS.getLong(storageSettings)).setPageToken(StorageOption.PAGE_TOKEN.getString(storageSettings)).setFields(StorageOption.FIELDS.getString(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
            return Tuple.<String, Iterable<Bucket>>of(containerCollection.getNextPageToken(), containerCollection.getItems());
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Tuple<String, Iterable<StorageObject>> list(final String container, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            Objects objectCollection = backendStore.objects().list(container).setProjection(DEFAULT_PROJECTION).setVersions(StorageOption.VERSIONS.getBoolean(storageSettings)).setDelimiter(StorageOption.DELIMITER.getString(storageSettings)).setPrefix(StorageOption.PREFIX.getString(storageSettings)).setMaxResults(StorageOption.MAX_RESULTS.getLong(storageSettings)).setPageToken(StorageOption.PAGE_TOKEN.getString(storageSettings)).setFields(StorageOption.FIELDS.getString(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
            Iterable<StorageObject> objectIterable = Iterables.concat(firstNonNull(objectCollection.getItems(), ImmutableList.<StorageObject>of()), null != objectCollection.getPrefixes() ? Lists.transform(objectCollection.getPrefixes(), storageObjectFromPrefix(container)) : ImmutableList.<StorageObject>of());
            return Tuple.of(objectCollection.getNextPageToken(), objectIterable);
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    private static Function<String, StorageObject> storageObjectFromPrefix(final String container) {
        return new Function<String, StorageObject>() {

            @Override
            public StorageObject apply(String prefix) {
                return new StorageObject().set("isDirectory", true).setBucket(container).setName(prefix).setSize(BigInteger.ZERO);
            }
        };
    }

    @Override
    public Bucket get(Bucket container, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.buckets().get(container.getName()).setProjection(DEFAULT_PROJECTION).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setFields(StorageOption.FIELDS.getString(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return null;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    private Storage.Objects.Get getCall(StorageObject storageItem, Map<StorageOption, ?> storageSettings) throws IOException {
        Storage.Objects.Get retrievalRequest = backendStore.objects().get(storageItem.getBucket(), storageItem.getName());
        setEncryptionHeaders(retrievalRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, storageSettings);
        return retrievalRequest.setGeneration(storageItem.getGeneration()).setProjection(DEFAULT_PROJECTION).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings)).setFields(StorageOption.FIELDS.getString(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
    }

    @Override
    public StorageObject get(StorageObject storageItem, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return getCall(storageItem, storageSettings).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return null;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Bucket patch(Bucket container, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            String fieldMask = StorageOption.PROJECTION.getString(storageSettings);
            if (null != container.getIamConfiguration() && null != container.getIamConfiguration().getBucketPolicyOnly() && null != container.getIamConfiguration().getBucketPolicyOnly().getEnabled() && container.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
                // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
                // the request
                container.setDefaultObjectAcl(null);
                container.setAcl(null);
                if (null == fieldMask) {
                    fieldMask = NO_ACL_PROJECTION;
                }
            }
            return backendStore.buckets().patch(container.getName(), container).setProjection(null == fieldMask ? DEFAULT_PROJECTION : fieldMask).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings)).setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(storageSettings)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    private Storage.Objects.Patch buildPatchCall(StorageObject objectMetadata, Map<StorageOption, ?> storageSettings) throws IOException {
        return backendStore.objects().patch(objectMetadata.getBucket(), objectMetadata.getName(), objectMetadata).setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(storageSettings)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
    }

    @Override
    public StorageObject patch(StorageObject objectMetadata, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return buildPatchCall(objectMetadata, storageSettings).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public boolean delete(Bucket container, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            backendStore.buckets().delete(container.getName()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
            return true;
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return false;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    private Storage.Objects.Delete buildDeleteCall(StorageObject deleteTarget, Map<StorageOption, ?> storageSettings) throws IOException {
        return backendStore.objects().delete(deleteTarget.getBucket(), deleteTarget.getName()).setGeneration(deleteTarget.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
    }

    @Override
    public boolean delete(StorageObject deleteTarget, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            buildDeleteCall(deleteTarget, storageSettings).execute();
            return true;
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return false;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public StorageObject compose(Iterable<StorageObject> inputItems, StorageObject destinationItem, Map<StorageOption, ?> destinationParams) {
        ComposeRequest composePayload = new ComposeRequest();
        composePayload.setDestination(destinationItem);
        List<ComposeRequest.SourceObjects> sourceList = new ArrayList<>();
        for (StorageObject storageItem : inputItems) {
            ComposeRequest.SourceObjects objectElement = new ComposeRequest.SourceObjects();
            objectElement.setName(storageItem.getName());
            Long versionNumber = storageItem.getGeneration();
            if (null != versionNumber) {
                objectElement.setGeneration(versionNumber);
                objectElement.setObjectPreconditions(new ObjectPreconditions().setIfGenerationMatch(versionNumber));
            }
            sourceList.add(objectElement);
        }
        composePayload.setSourceObjects(sourceList);
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.objects().compose(destinationItem.getBucket(), destinationItem.getName(), composePayload).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(destinationParams)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(destinationParams)).setUserProject(StorageOption.USER_PROJECT.getString(destinationParams)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public byte[] load(StorageObject originObject, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            Storage.Objects.Get retrievalRequest = backendStore.objects().get(originObject.getBucket(), originObject.getName()).setGeneration(originObject.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
            setEncryptionHeaders(retrievalRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, storageSettings);
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            retrievalRequest.executeMedia().download(byteStream);
            return byteStream.toByteArray();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public RpcRequestBatch createBatch() {
        return new RpcBatchProcessor(backendStore);
    }

    private Get buildReadRequest(StorageObject originObject, Map<StorageOption, ?> storageSettings) throws IOException {
        Get readRequest = backendStore.objects().get(originObject.getBucket(), originObject.getName()).setGeneration(originObject.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(storageSettings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(storageSettings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
        setEncryptionHeaders(readRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, storageSettings);
        readRequest.setReturnRawInputStream(true);
        return readRequest;
    }

    @Override
    public long read(StorageObject originObject, Map<StorageOption, ?> storageSettings, long offset, OutputStream destStream) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            Get readRequest = buildReadRequest(originObject, storageSettings);
            readRequest.getMediaHttpDownloader().setBytesDownloaded(offset);
            readRequest.getMediaHttpDownloader().setDirectDownloadEnabled(true);
            readRequest.executeMediaAndDownloadTo(destStream);
            return readRequest.getMediaHttpDownloader().getNumBytesDownloaded();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (STATUS_REQUESTED_RANGE_NOT_SATISFIABLE == operationError.getCode()) {
                return 0;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Tuple<String, byte[]> read(StorageObject originObject, Map<StorageOption, ?> storageSettings, long offset, int length) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            checkArgument(0 <= offset, "Position should be non-negative, is " + offset);
            Get readRequest = buildReadRequest(originObject, storageSettings);
            StringBuilder headerBuilder = new StringBuilder();
            headerBuilder.append("bytes=").append(offset).append("-").append(offset + length - 1);
            HttpHeaders httpHeaderCollection = readRequest.getRequestHeaders();
            httpHeaderCollection.setRange(headerBuilder.toString());
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream(length);
            readRequest.executeMedia().download(byteBuffer);
            String tagValue = readRequest.getLastResponseHeaders().getETag();
            return Tuple.of(tagValue, byteBuffer.toByteArray());
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = StorageOperationException.translateException(ioEx);
            if (STATUS_REQUESTED_RANGE_NOT_SATISFIABLE == operationError.getCode()) {
                return Tuple.of(null, new byte[0]);
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public void write(String transferId, byte[] writeBuffer, int bufferOffset, long targetOffset, int byteCount, boolean isFinal) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            if (0 == byteCount && !isFinal) {
                return;
            }
            GenericUrl endpoint = new GenericUrl(transferId);
            HttpRequest request = backendStore.getRequestFactory().buildPutRequest(endpoint, new ByteArrayContent(null, writeBuffer, bufferOffset, byteCount));
            long maxBytes = targetOffset + byteCount;
            StringBuilder headerBuilder = new StringBuilder("bytes ");
            if (0 != byteCount) {
                headerBuilder.append(targetOffset).append('-').append(maxBytes - 1);
            } else {
                headerBuilder.append('*');
            }
            headerBuilder.append('/');
            if (!isFinal) {
                headerBuilder.append('*');
            } else {
                headerBuilder.append(maxBytes);
            }
            request.getHeaders().setContentRange(headerBuilder.toString());
            int status;
            String statusText;
            IOException ioException = null;
            HttpResponse result = null;
            try {
                result = request.execute();
                status = result.getStatusCode();
                statusText = result.getStatusMessage();
            } catch (HttpResponseException ioEx) {
                ioException = ioEx;
                status = ioEx.getStatusCode();
                statusText = ioEx.getStatusMessage();
            } finally {
                if (null != result) {
                    result.disconnect();
                }
            }
            if (!isFinal && 308 != status || isFinal && !(200 == status || 201 == status)) {
                if (null != ioException) {
                    throw ioException;
                }
                GoogleJsonError jsonFault = new GoogleJsonError();
                jsonFault.setCode(status);
                jsonFault.setMessage(statusText);
                throw translateException(jsonFault);
            }
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public String open(StorageObject storageItem, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            Insert readRequest = backendStore.objects().insert(storageItem.getBucket(), storageItem);
            GenericUrl endpoint = readRequest.buildHttpRequest().getUrl();
            String protocol = endpoint.getScheme();
            String serverName = endpoint.getHost();
            int portNumber = endpoint.getPort();
            portNumber = 0 < portNumber ? portNumber : endpoint.toURL().getDefaultPort();
            String resourceLocation = "/upload" + endpoint.getRawPath();
            endpoint = new GenericUrl(protocol + "://" + serverName + ":" + portNumber + resourceLocation);
            endpoint.set("uploadType", "resumable");
            endpoint.set("name", storageItem.getName());
            for (StorageOption storageChoice : storageSettings.keySet()) {
                Object inputStream = storageChoice.get(storageSettings);
                if (null != inputStream) {
                    endpoint.set(storageChoice.getValue(), inputStream.toString());
                }
            }
            JsonFactory jsonParser = backendStore.getJsonFactory();
            HttpRequestFactory requestMaker = backendStore.getRequestFactory();
            HttpRequest request = requestMaker.buildPostRequest(endpoint, new JsonHttpContent(jsonParser, storageItem));
            HttpHeaders httpHeaderCollection = request.getHeaders();
            httpHeaderCollection.set("X-Upload-Content-Type", firstNonNull(storageItem.getContentType(), "application/octet-stream"));
            String secretValue = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(storageSettings);
            if (null != secretValue) {
                BaseEncoding binaryEncoder = BaseEncoding.base64();
                HashFunction hasher = Hashing.sha256();
                httpHeaderCollection.set("x-goog-encryption-algorithm", "AES256");
                httpHeaderCollection.set("x-goog-encryption-key", secretValue);
                httpHeaderCollection.set("x-goog-encryption-key-sha256", binaryEncoder.encode(hasher.hashBytes(binaryEncoder.decode(secretValue)).asBytes()));
            }
            HttpResponse result = request.execute();
            if (200 != result.getStatusCode()) {
                GoogleJsonError jsonFault = new GoogleJsonError();
                jsonFault.setCode(result.getStatusCode());
                jsonFault.setMessage(result.getStatusMessage());
                throw translateException(jsonFault);
            }
            return result.getHeaders().getLocation();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public String open(String signedUrl) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            GenericUrl endpoint = new GenericUrl(signedUrl);
            endpoint.set("uploadType", "resumable");
            String parameterString = "";
            byte[] rawBytes = new byte[parameterString.length()];
            HttpRequestFactory requestMaker = backendStore.getRequestFactory();
            HttpRequest request = requestMaker.buildPostRequest(endpoint, new ByteArrayContent("", rawBytes, 0, rawBytes.length));
            HttpHeaders httpHeaderCollection = request.getHeaders();
            httpHeaderCollection.set("X-Upload-Content-Type", "");
            httpHeaderCollection.set("x-goog-resumable", "start");
            HttpResponse result = request.execute();
            if (201 != result.getStatusCode()) {
                GoogleJsonError jsonFault = new GoogleJsonError();
                jsonFault.setCode(result.getStatusCode());
                jsonFault.setMessage(result.getStatusMessage());
                throw translateException(jsonFault);
            }
            return result.getHeaders().getLocation();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public CloudStorageRpc.RewriteOperationResponse openRewrite(RewriteOperationRequest operationRequest) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return performRewrite(operationRequest, null);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public CloudStorageRpc.RewriteOperationResponse continueRewrite(RewriteOperationResponse priorResponse) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return performRewrite(priorResponse.rewriteRequest, priorResponse.rewriteToken);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    private RewriteOperationResponse performRewrite(RewriteOperationRequest readRequest, String authToken) {
        try {
            String projectOwner = StorageOption.USER_PROJECT.getString(readRequest.sourceOptions);
            if (null == projectOwner) {
                projectOwner = StorageOption.USER_PROJECT.getString(readRequest.targetOptions);
            }
            Long maxBytesPerCall = null != readRequest.megabytesRewrittenPerCall ? readRequest.megabytesRewrittenPerCall * BYTES_PER_MB : null;
            Storage.Objects.Rewrite rewriteOp = backendStore.objects().rewrite(readRequest.source.getBucket(), readRequest.source.getName(), readRequest.target.getBucket(), readRequest.target.getName(), readRequest.overrideInfo ? readRequest.target : null).setSourceGeneration(readRequest.source.getGeneration()).setRewriteToken(authToken).setMaxBytesRewrittenPerCall(maxBytesPerCall).setProjection(DEFAULT_PROJECTION).setIfSourceMetagenerationMatch(StorageOption.IF_SOURCE_METAGENERATION_MATCH.getLong(readRequest.sourceOptions)).setIfSourceMetagenerationNotMatch(StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(readRequest.sourceOptions)).setIfSourceGenerationMatch(StorageOption.IF_SOURCE_GENERATION_MATCH.getLong(readRequest.sourceOptions)).setIfSourceGenerationNotMatch(StorageOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(readRequest.sourceOptions)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(readRequest.targetOptions)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(readRequest.targetOptions)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(readRequest.targetOptions)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(readRequest.targetOptions)).setDestinationPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(readRequest.targetOptions)).setUserProject(projectOwner).setDestinationKmsKeyName(StorageOption.KMS_KEY_NAME.getString(readRequest.targetOptions));
            HttpHeaders httpHeaderCollection = rewriteOp.getRequestHeaders();
            setEncryptionHeaders(httpHeaderCollection, SOURCE_CRYPTO_PREFIX, readRequest.sourceOptions);
            setEncryptionHeaders(httpHeaderCollection, CRYPTO_KEY_PREFIX, readRequest.targetOptions);
            com.google.api.services.storage.model.RewriteResponse rewriteResult = rewriteOp.execute();
            return new RewriteOperationResponse(readRequest, rewriteResult.getResource(), rewriteResult.getObjectSize().longValue(), rewriteResult.getDone(), rewriteResult.getRewriteToken(), rewriteResult.getTotalBytesRewritten().longValue());
        } catch (IOException ioEx) {
            telemetry.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        }
    }

    @Override
    public BucketAccessControl getAcl(String container, String entityId, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.bucketAccessControls().get(container, entityId).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return null;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public boolean deleteAcl(String container, String entityId, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            backendStore.bucketAccessControls().delete(container, entityId).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
            return true;
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return false;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public BucketAccessControl createAcl(BucketAccessControl accessControlEntry, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.bucketAccessControls().insert(accessControlEntry.getBucket(), accessControlEntry).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public BucketAccessControl patchAcl(BucketAccessControl accessControlEntry, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.bucketAccessControls().patch(accessControlEntry.getBucket(), accessControlEntry.getEntity(), accessControlEntry).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public List<BucketAccessControl> listAcls(String container, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.bucketAccessControls().list(container).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute().getItems();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public ObjectAccessControl getDefaultAcl(String container, String entityId) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.defaultObjectAccessControls().get(container, entityId).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return null;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public boolean deleteDefaultAcl(String container, String entityId) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            backendStore.defaultObjectAccessControls().delete(container, entityId).execute();
            return true;
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return false;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControlEntry) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.defaultObjectAccessControls().insert(accessControlEntry.getBucket(), accessControlEntry).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControlEntry) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.defaultObjectAccessControls().patch(accessControlEntry.getBucket(), accessControlEntry.getEntity(), accessControlEntry).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public List<ObjectAccessControl> listDefaultAcls(String container) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.defaultObjectAccessControls().list(container).execute().getItems();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public ObjectAccessControl getAcl(String container, String storageItem, Long versionNumber, String entityId) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.objectAccessControls().get(container, storageItem, entityId).setGeneration(versionNumber).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return null;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public boolean deleteAcl(String container, String storageItem, Long versionNumber, String entityId) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            backendStore.objectAccessControls().delete(container, storageItem, entityId).setGeneration(versionNumber).execute();
            return true;
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return false;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public ObjectAccessControl createAcl(ObjectAccessControl accessControlEntry) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.objectAccessControls().insert(accessControlEntry.getBucket(), accessControlEntry.getObject(), accessControlEntry).setGeneration(accessControlEntry.getGeneration()).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public ObjectAccessControl patchAcl(ObjectAccessControl accessControlEntry) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.objectAccessControls().patch(accessControlEntry.getBucket(), accessControlEntry.getObject(), accessControlEntry.getEntity(), accessControlEntry).setGeneration(accessControlEntry.getGeneration()).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public List<ObjectAccessControl> listAcls(String container, String storageItem, Long versionNumber) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.objectAccessControls().list(container, storageItem).setGeneration(versionNumber).execute().getItems();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public HmacKey createHmacKey(String accountEmail, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
        Scope contextHandle = telemetry.withSpan(traceContext);
        String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
        if (null == projectIdentifier) {
            projectIdentifier = this.storageSettings.getProjectId();
        }
        try {
            return backendStore.projects().hmacKeys().create(projectIdentifier, accountEmail).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
        Scope contextHandle = telemetry.withSpan(traceContext);
        String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
        if (null == projectIdentifier) {
            projectIdentifier = this.storageSettings.getProjectId();
        }
        try {
            HmacKeysMetadata hmacCollectionInfo = backendStore.projects().hmacKeys().list(projectIdentifier).setServiceAccountEmail(StorageOption.SERVICE_ACCOUNT_EMAIL.getString(storageSettings)).setPageToken(StorageOption.PAGE_TOKEN.getString(storageSettings)).setMaxResults(StorageOption.MAX_RESULTS.getLong(storageSettings)).setShowDeletedKeys(StorageOption.SHOW_DELETED_KEYS.getBoolean(storageSettings)).execute();
            return Tuple.<String, Iterable<HmacKeyMetadata>>of(hmacCollectionInfo.getNextPageToken(), hmacCollectionInfo.getItems());
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public HmacKeyMetadata getHmacKey(String accessIdentifier, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
        Scope contextHandle = telemetry.withSpan(traceContext);
        String projectIdentifier = StorageOption.PROJECT_ID.getString(storageSettings);
        if (null == projectIdentifier) {
            projectIdentifier = this.storageSettings.getProjectId();
        }
        try {
            return backendStore.projects().hmacKeys().get(projectIdentifier, accessIdentifier).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyInfo, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
        Scope contextHandle = telemetry.withSpan(traceContext);
        String projectIdentifier = hmacKeyInfo.getProjectId();
        if (null == projectIdentifier) {
            projectIdentifier = this.storageSettings.getProjectId();
        }
        try {
            return backendStore.projects().hmacKeys().update(projectIdentifier, hmacKeyInfo.getAccessId(), hmacKeyInfo).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public void deleteHmacKey(HmacKeyMetadata hmacKeyInfo, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
        Scope contextHandle = telemetry.withSpan(traceContext);
        String projectIdentifier = hmacKeyInfo.getProjectId();
        if (null == projectIdentifier) {
            projectIdentifier = this.storageSettings.getProjectId();
        }
        try {
            backendStore.projects().hmacKeys().delete(projectIdentifier, hmacKeyInfo.getAccessId()).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Policy getIamPolicy(String container, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            Storage.Buckets.GetIamPolicy iamPolicyRequest = backendStore.buckets().getIamPolicy(container).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings));
            if (StorageOption.REQUESTED_POLICY_VERSION.getLong(storageSettings) != null) {
                iamPolicyRequest.setOptionsRequestedPolicyVersion(StorageOption.REQUESTED_POLICY_VERSION.getLong(storageSettings).intValue());
            }
            return iamPolicyRequest.execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Policy setIamPolicy(String container, Policy iamPolicy, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.buckets().setIamPolicy(container, iamPolicy).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public TestIamPermissionsResponse testIamPermissions(String container, List<String> permissionList, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.buckets().testIamPermissions(container, permissionList).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public boolean deleteNotification(String container, String notificationId) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            backendStore.notifications().delete(container, notificationId).execute();
            return true;
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            StorageOperationException operationError = translateException(ioEx);
            if (HTTP_NOT_FOUND == operationError.getCode()) {
                return false;
            }
            throw operationError;
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public List<Notification> listNotifications(String container) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.notifications().list(container).execute().getItems();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Notification createNotification(String container, Notification notificationId) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.notifications().insert(container, notificationId).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public Bucket lockRetentionPolicy(Bucket container, Map<StorageOption, ?> storageSettings) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.buckets().lockRetentionPolicy(container.getName(), StorageOption.IF_METAGENERATION_MATCH.getLong(storageSettings)).setUserProject(StorageOption.USER_PROJECT.getString(storageSettings)).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }

    @Override
    public ServiceAccount getServiceAccount(String projectIdentifier) {
        Span traceContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
        Scope contextHandle = telemetry.withSpan(traceContext);
        try {
            return backendStore.projects().serviceAccount().get(projectIdentifier).execute();
        } catch (IOException ioEx) {
            traceContext.setStatus(Status.UNKNOWN.withDescription(ioEx.getMessage()));
            throw translateException(ioEx);
        } finally {
            contextHandle.close();
            traceContext.end();
        }
    }
}
