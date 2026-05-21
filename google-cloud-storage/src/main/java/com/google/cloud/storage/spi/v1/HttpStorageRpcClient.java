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

    private static final String CRYPTO_KEY_PREFIX = "x-goog-encryption-";

    private static final String ORIGIN_CRYPTO_KEY_PREFIX = "x-goog-copy-source-encryption-";

    // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
    private static final int RANGE_NOT_SATISFIABLE_STATUS = 416;

    private final StorageSettings settings;

    private final Storage storageBackend;

    private final Tracer telemetry = Tracing.getTracer();

    private final CensusHttpModule metricsHttpModule;

    private final HttpRequestInitializer batchInitializer;

    private static final long ONE_MEGABYTE = 1024L * 1024L;

    public HttpStorageRpcClient(StorageSettings settings) {
        HttpTransportOptions transportConfig = (HttpTransportOptions) settings.getTransportOptions();
        HttpTransport httpTransport = transportConfig.getHttpTransportFactory().create();
        HttpRequestInitializer requestSetup = transportConfig.getHttpRequestInitializer(settings);
        this.settings = settings;
        // Open Census initialization
        metricsHttpModule = new CensusHttpModule(telemetry, true);
        requestSetup = metricsHttpModule.getHttpRequestInitializer(requestSetup);
        batchInitializer = metricsHttpModule.getHttpRequestInitializer(null);
        HttpStorageRpcSpans.registerAllSpanNamesForCollection();
        storageBackend = new Storage.Builder(httpTransport, new JacksonFactory(), requestSetup).setRootUrl(settings.getHost()).setApplicationName(settings.getApplicationName()).build();
    }

    private class DefaultRpcBatcher implements RpcBatchBuilder {

        // Batch size is limited as, due to some current service implementation details, the service
        // performs better if the batches are split for better distribution. See
        // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
        // background.
        private static final int BATCH_MAX_SIZE = 100;

        private final Storage storageBackend;

        private final LinkedList<BatchRequest> batchQueue;

        private int currentBatchCount;

        private DefaultRpcBatcher(Storage storageBackend) {
            this.storageBackend = storageBackend;
            batchQueue = new LinkedList<>();
            // add OpenCensus HttpRequestInitializer
            batchQueue.add(storageBackend.batch(batchInitializer));
        }

        @Override
        public void addDeletion(StorageObject objectToDelete, ResultCallback<Void> resultHandler, Map<StorageOption, ?> settings) {
            try {
                if (BATCH_MAX_SIZE == currentBatchCount) {
                    batchQueue.add(storageBackend.batch());
                    currentBatchCount = 0;
                }
                buildDeleteCall(objectToDelete, settings).queue(batchQueue.getLast(), toJsonBatchCallback(resultHandler));
                currentBatchCount += 1;
            } catch (IOException ioException) {
                throw toStorageException(ioException);
            }
        }

        @Override
        public void addUpdate(StorageObject objectToDelete, ResultCallback<StorageObject> resultHandler, Map<StorageOption, ?> settings) {
            try {
                if (BATCH_MAX_SIZE == currentBatchCount) {
                    batchQueue.add(storageBackend.batch());
                    currentBatchCount = 0;
                }
                buildPatchCall(objectToDelete, settings).queue(batchQueue.getLast(), toJsonBatchCallback(resultHandler));
                currentBatchCount += 1;
            } catch (IOException ioException) {
                throw toStorageException(ioException);
            }
        }

        @Override
        public void addFetch(StorageObject objectToDelete, ResultCallback<StorageObject> resultHandler, Map<StorageOption, ?> settings) {
            try {
                if (BATCH_MAX_SIZE == currentBatchCount) {
                    batchQueue.add(storageBackend.batch());
                    currentBatchCount = 0;
                }
                getCall(objectToDelete, settings).queue(batchQueue.getLast(), toJsonBatchCallback(resultHandler));
                currentBatchCount += 1;
            } catch (IOException ioException) {
                throw toStorageException(ioException);
            }
        }

        @Override
        public void execute() {
            Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
            Scope currentScope = telemetry.withSpan(currentSpan);
            try {
                currentSpan.putAttribute("batch size", AttributeValue.longAttributeValue(batchQueue.size()));
                for (BatchRequest batchRequest : batchQueue) {
                    // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
                    // Here we only add a annotation to at least know how much time each batch takes.
                    currentSpan.addAnnotation("Execute batch request");
                    batchRequest.setBatchUrl(new GenericUrl(String.format("%s/batch/storage/v1", settings.getHost())));
                    batchRequest.execute();
                }
            } catch (IOException ioException) {
                currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
                throw toStorageException(ioException);
            } finally {
                currentScope.close();
                currentSpan.end();
            }
        }
    }

    private static <T> JsonBatchCallback<T> toJsonBatchCallback(final RpcBatchBuilder.ResultCallback<T> resultHandler) {
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

    private static StorageServiceException toStorageException(IOException ioException) {
        return new StorageServiceException(ioException);
    }

    private static StorageServiceException toStorageException(GoogleJsonError ioException) {
        return new StorageServiceException(ioException);
    }

    private static void setEncryptionHeaders(HttpHeaders httpFields, String fieldPrefix, Map<StorageOption, ?> settings) {
        String secretValue = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(settings);
        if (null != secretValue) {
            BaseEncoding encoder = BaseEncoding.base64();
            HashFunction digestFunction = Hashing.sha256();
            httpFields.set(fieldPrefix + "algorithm", "AES256");
            httpFields.set(fieldPrefix + "key", secretValue);
            httpFields.set(fieldPrefix + "key-sha256", encoder.encode(digestFunction.hashBytes(encoder.decode(secretValue)).asBytes()));
        }
    }

    /**
     * Helper method to start a span.
     */
    private Span startTraceSpan(String traceName) {
        return telemetry.spanBuilder(traceName).setRecordEvents(metricsHttpModule.isRecordEvents()).startSpan();
    }

    @Override
    public Bucket create(Bucket container, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.buckets().insert(this.settings.getProjectId(), container).setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(settings)).setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public StorageObject create(StorageObject objectToDelete, final InputStream inputStream, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            Storage.Objects.Insert createCall = storageBackend.objects().insert(objectToDelete.getBucket(), objectToDelete, new InputStreamContent(objectToDelete.getContentType(), inputStream));
            createCall.getMediaHttpUploader().setDirectUploadEnabled(true);
            Boolean disableCompression = StorageOption.IF_DISABLE_GZIP_CONTENT.getBoolean(settings);
            if (null != disableCompression) {
                createCall.setDisableGZipContent(disableCompression);
            }
            setEncryptionHeaders(createCall.getRequestHeaders(), CRYPTO_KEY_PREFIX, settings);
            return createCall.setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(settings)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(settings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings)).setKmsKeyName(StorageOption.KMS_KEY_NAME.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            Buckets containerCollection = storageBackend.buckets().list(this.settings.getProjectId()).setProjection(DEFAULT_PROJECTION).setPrefix(StorageOption.PREFIX.getString(settings)).setMaxResults(StorageOption.MAX_RESULTS.getLong(settings)).setPageToken(StorageOption.PAGE_TOKEN.getString(settings)).setFields(StorageOption.FIELDS.getString(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
            return Tuple.<String, Iterable<Bucket>>of(containerCollection.getNextPageToken(), containerCollection.getItems());
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Tuple<String, Iterable<StorageObject>> list(final String container, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            Objects itemCollection = storageBackend.objects().list(container).setProjection(DEFAULT_PROJECTION).setVersions(StorageOption.VERSIONS.getBoolean(settings)).setDelimiter(StorageOption.DELIMITER.getString(settings)).setPrefix(StorageOption.PREFIX.getString(settings)).setMaxResults(StorageOption.MAX_RESULTS.getLong(settings)).setPageToken(StorageOption.PAGE_TOKEN.getString(settings)).setFields(StorageOption.FIELDS.getString(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
            Iterable<StorageObject> storedEntities = Iterables.concat(firstNonNull(itemCollection.getItems(), ImmutableList.<StorageObject>of()), null != itemCollection.getPrefixes() ? Lists.transform(itemCollection.getPrefixes(), storageObjectFromPrefix(container)) : ImmutableList.<StorageObject>of());
            return Tuple.of(itemCollection.getNextPageToken(), storedEntities);
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
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
    public Bucket get(Bucket container, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.buckets().get(container.getName()).setProjection(DEFAULT_PROJECTION).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setFields(StorageOption.FIELDS.getString(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return null;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    private Storage.Objects.Get getCall(StorageObject blobItem, Map<StorageOption, ?> settings) throws IOException {
        Storage.Objects.Get fetchRequest = storageBackend.objects().get(blobItem.getBucket(), blobItem.getName());
        setEncryptionHeaders(fetchRequest.getRequestHeaders(), CRYPTO_KEY_PREFIX, settings);
        return fetchRequest.setGeneration(blobItem.getGeneration()).setProjection(DEFAULT_PROJECTION).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(settings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(settings)).setFields(StorageOption.FIELDS.getString(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings));
    }

    @Override
    public StorageObject get(StorageObject blobItem, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return getCall(blobItem, settings).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return null;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Bucket patch(Bucket container, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            String viewMode = StorageOption.PROJECTION.getString(settings);
            if (null != container.getIamConfiguration() && null != container.getIamConfiguration().getBucketPolicyOnly() && null != container.getIamConfiguration().getBucketPolicyOnly().getEnabled() && container.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
                // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
                // the request
                container.setDefaultObjectAcl(null);
                container.setAcl(null);
                if (null == viewMode) {
                    viewMode = NO_ACL_PROJECTION;
                }
            }
            return storageBackend.buckets().patch(container.getName(), container).setProjection(null == viewMode ? DEFAULT_PROJECTION : viewMode).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(settings)).setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(settings)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    private Storage.Objects.Patch buildPatchCall(StorageObject objectToDelete, Map<StorageOption, ?> settings) throws IOException {
        return storageBackend.objects().patch(objectToDelete.getBucket(), objectToDelete.getName(), objectToDelete).setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(settings)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(settings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings));
    }

    @Override
    public StorageObject patch(StorageObject objectToDelete, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return buildPatchCall(objectToDelete, settings).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public boolean delete(Bucket container, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            storageBackend.buckets().delete(container.getName()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
            return true;
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return false;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    private Storage.Objects.Delete buildDeleteCall(StorageObject blobEntry, Map<StorageOption, ?> settings) throws IOException {
        return storageBackend.objects().delete(blobEntry.getBucket(), blobEntry.getName()).setGeneration(blobEntry.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(settings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings));
    }

    @Override
    public boolean delete(StorageObject blobEntry, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            buildDeleteCall(blobEntry, settings).execute();
            return true;
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return false;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public StorageObject compose(Iterable<StorageObject> inputBlobs, StorageObject destinationBlob, Map<StorageOption, ?> destinationParams) {
        ComposeRequest composePayload = new ComposeRequest();
        composePayload.setDestination(destinationBlob);
        List<ComposeRequest.SourceObjects> sourceList = new ArrayList<>();
        for (StorageObject originObject : inputBlobs) {
            ComposeRequest.SourceObjects entryItem = new ComposeRequest.SourceObjects();
            entryItem.setName(originObject.getName());
            Long versionNumber = originObject.getGeneration();
            if (null != versionNumber) {
                entryItem.setGeneration(versionNumber);
                entryItem.setObjectPreconditions(new ObjectPreconditions().setIfGenerationMatch(versionNumber));
            }
            sourceList.add(entryItem);
        }
        composePayload.setSourceObjects(sourceList);
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.objects().compose(destinationBlob.getBucket(), destinationBlob.getName(), composePayload).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(destinationParams)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(destinationParams)).setUserProject(StorageOption.USER_PROJECT.getString(destinationParams)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public byte[] load(StorageObject sourceObject, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            Storage.Objects.Get getCall = storageBackend.objects().get(sourceObject.getBucket(), sourceObject.getName()).setGeneration(sourceObject.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(settings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings));
            setEncryptionHeaders(getCall.getRequestHeaders(), CRYPTO_KEY_PREFIX, settings);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            getCall.executeMedia().download(buffer);
            return buffer.toByteArray();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public RpcBatchBuilder createBatch() {
        return new DefaultRpcBatcher(storageBackend);
    }

    private Get buildReadRequest(StorageObject sourceObject, Map<StorageOption, ?> settings) throws IOException {
        Get getOperation = storageBackend.objects().get(sourceObject.getBucket(), sourceObject.getName()).setGeneration(sourceObject.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(settings)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(settings)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings));
        setEncryptionHeaders(getOperation.getRequestHeaders(), CRYPTO_KEY_PREFIX, settings);
        getOperation.setReturnRawInputStream(true);
        return getOperation;
    }

    @Override
    public long read(StorageObject sourceObject, Map<StorageOption, ?> settings, long offset, OutputStream destinationStream) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            Get getOperation = buildReadRequest(sourceObject, settings);
            getOperation.getMediaHttpDownloader().setBytesDownloaded(offset);
            getOperation.getMediaHttpDownloader().setDirectDownloadEnabled(true);
            getOperation.executeMediaAndDownloadTo(destinationStream);
            return getOperation.getMediaHttpDownloader().getNumBytesDownloaded();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (RANGE_NOT_SATISFIABLE_STATUS == storageError.getCode()) {
                return 0;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Tuple<String, byte[]> read(StorageObject sourceObject, Map<StorageOption, ?> settings, long offset, int byteCount) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            checkArgument(0 <= offset, "Position should be non-negative, is " + offset);
            Get getOperation = buildReadRequest(sourceObject, settings);
            StringBuilder headerBuilder = new StringBuilder();
            headerBuilder.append("bytes=").append(offset).append("-").append(offset + byteCount - 1);
            HttpHeaders httpHeaders = getOperation.getRequestHeaders();
            httpHeaders.setRange(headerBuilder.toString());
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream(byteCount);
            getOperation.executeMedia().download(byteBuffer);
            String entityTag = getOperation.getLastResponseHeaders().getETag();
            return Tuple.of(entityTag, byteBuffer.toByteArray());
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = StorageServiceException.translateException(ioException);
            if (RANGE_NOT_SATISFIABLE_STATUS == storageError.getCode()) {
                return Tuple.of(null, new byte[0]);
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public void write(String uploadToken, byte[] writeBuffer, int bufferOffset, long destinationOffset, int chunkLength, boolean isFinal) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            if (0 == chunkLength && !isFinal) {
                return;
            }
            GenericUrl requestUrl = new GenericUrl(uploadToken);
            HttpRequest preparedRequest = storageBackend.getRequestFactory().buildPutRequest(requestUrl, new ByteArrayContent(null, writeBuffer, bufferOffset, chunkLength));
            long maxLimit = destinationOffset + chunkLength;
            StringBuilder headerBuilder = new StringBuilder("bytes ");
            if (0 != chunkLength) {
                headerBuilder.append(destinationOffset).append('-').append(maxLimit - 1);
            } else {
                headerBuilder.append('*');
            }
            headerBuilder.append('/');
            if (!isFinal) {
                headerBuilder.append('*');
            } else {
                headerBuilder.append(maxLimit);
            }
            preparedRequest.getHeaders().setContentRange(headerBuilder.toString());
            int statusCode;
            String statusMessage;
            IOException ioException = null;
            HttpResponse result = null;
            try {
                result = preparedRequest.execute();
                statusCode = result.getStatusCode();
                statusMessage = result.getStatusMessage();
            } catch (HttpResponseException ioError) {
                ioException = ioError;
                statusCode = ioError.getStatusCode();
                statusMessage = ioError.getStatusMessage();
            } finally {
                if (null != result) {
                    result.disconnect();
                }
            }
            if (!isFinal && 308 != statusCode || isFinal && !(200 == statusCode || 201 == statusCode)) {
                if (null != ioException) {
                    throw ioException;
                }
                GoogleJsonError jsonError = new GoogleJsonError();
                jsonError.setCode(statusCode);
                jsonError.setMessage(statusMessage);
                throw toStorageException(jsonError);
            }
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public String open(StorageObject blobItem, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            Insert getOperation = storageBackend.objects().insert(blobItem.getBucket(), blobItem);
            GenericUrl requestUrl = getOperation.buildHttpRequest().getUrl();
            String urlScheme = requestUrl.getScheme();
            String hostName = requestUrl.getHost();
            int portNumber = requestUrl.getPort();
            portNumber = 0 < portNumber ? portNumber : requestUrl.toURL().getDefaultPort();
            String requestPath = "/upload" + requestUrl.getRawPath();
            requestUrl = new GenericUrl(urlScheme + "://" + hostName + ":" + portNumber + requestPath);
            requestUrl.set("uploadType", "resumable");
            requestUrl.set("name", blobItem.getName());
            for (StorageOption storageOption : settings.keySet()) {
                Object inputStream = storageOption.get(settings);
                if (null != inputStream) {
                    requestUrl.set(storageOption.getValue(), inputStream.toString());
                }
            }
            JsonFactory jsonParserFactory = storageBackend.getJsonFactory();
            HttpRequestFactory httpRequestFactory = storageBackend.getRequestFactory();
            HttpRequest preparedRequest = httpRequestFactory.buildPostRequest(requestUrl, new JsonHttpContent(jsonParserFactory, blobItem));
            HttpHeaders httpHeaders = preparedRequest.getHeaders();
            httpHeaders.set("X-Upload-Content-Type", firstNonNull(blobItem.getContentType(), "application/octet-stream"));
            String secretValue = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(settings);
            if (null != secretValue) {
                BaseEncoding encoder = BaseEncoding.base64();
                HashFunction digestFunction = Hashing.sha256();
                httpHeaders.set("x-goog-encryption-algorithm", "AES256");
                httpHeaders.set("x-goog-encryption-key", secretValue);
                httpHeaders.set("x-goog-encryption-key-sha256", encoder.encode(digestFunction.hashBytes(encoder.decode(secretValue)).asBytes()));
            }
            HttpResponse result = preparedRequest.execute();
            if (200 != result.getStatusCode()) {
                GoogleJsonError jsonError = new GoogleJsonError();
                jsonError.setCode(result.getStatusCode());
                jsonError.setMessage(result.getStatusMessage());
                throw toStorageException(jsonError);
            }
            return result.getHeaders().getLocation();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public String open(String presignedLink) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            GenericUrl requestUrl = new GenericUrl(presignedLink);
            requestUrl.set("uploadType", "resumable");
            String encodedBytesParams = "";
            byte[] payloadBytes = new byte[encodedBytesParams.length()];
            HttpRequestFactory httpRequestFactory = storageBackend.getRequestFactory();
            HttpRequest preparedRequest = httpRequestFactory.buildPostRequest(requestUrl, new ByteArrayContent("", payloadBytes, 0, payloadBytes.length));
            HttpHeaders httpHeaders = preparedRequest.getHeaders();
            httpHeaders.set("X-Upload-Content-Type", "");
            httpHeaders.set("x-goog-resumable", "start");
            HttpResponse result = preparedRequest.execute();
            if (201 != result.getStatusCode()) {
                GoogleJsonError jsonError = new GoogleJsonError();
                jsonError.setCode(result.getStatusCode());
                jsonError.setMessage(result.getStatusMessage());
                throw toStorageException(jsonError);
            }
            return result.getHeaders().getLocation();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public StorageRpcClient.RewriteOperationResponse openRewrite(ObjectRewriteRequest objectRewriteSpec) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return performRewrite(objectRewriteSpec, null);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public StorageRpcClient.RewriteOperationResponse continueRewrite(RewriteOperationResponse priorRewriteResult) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return performRewrite(priorRewriteResult.rewriteRequest, priorRewriteResult.rewriteToken);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    private RewriteOperationResponse performRewrite(ObjectRewriteRequest getOperation, String authToken) {
        try {
            String projectIdentifier = StorageOption.USER_PROJECT.getString(getOperation.sourceOptions);
            if (null == projectIdentifier) {
                projectIdentifier = StorageOption.USER_PROJECT.getString(getOperation.targetOptions);
            }
            Long maxBytesPerCall = null != getOperation.megabytesRewrittenPerCall ? getOperation.megabytesRewrittenPerCall * ONE_MEGABYTE : null;
            Storage.Objects.Rewrite rewriteOperation = storageBackend.objects().rewrite(getOperation.source.getBucket(), getOperation.source.getName(), getOperation.target.getBucket(), getOperation.target.getName(), getOperation.overrideInfo ? getOperation.target : null).setSourceGeneration(getOperation.source.getGeneration()).setRewriteToken(authToken).setMaxBytesRewrittenPerCall(maxBytesPerCall).setProjection(DEFAULT_PROJECTION).setIfSourceMetagenerationMatch(StorageOption.IF_SOURCE_METAGENERATION_MATCH.getLong(getOperation.sourceOptions)).setIfSourceMetagenerationNotMatch(StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(getOperation.sourceOptions)).setIfSourceGenerationMatch(StorageOption.IF_SOURCE_GENERATION_MATCH.getLong(getOperation.sourceOptions)).setIfSourceGenerationNotMatch(StorageOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(getOperation.sourceOptions)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(getOperation.targetOptions)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(getOperation.targetOptions)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(getOperation.targetOptions)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(getOperation.targetOptions)).setDestinationPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(getOperation.targetOptions)).setUserProject(projectIdentifier).setDestinationKmsKeyName(StorageOption.KMS_KEY_NAME.getString(getOperation.targetOptions));
            HttpHeaders httpHeaders = rewriteOperation.getRequestHeaders();
            setEncryptionHeaders(httpHeaders, ORIGIN_CRYPTO_KEY_PREFIX, getOperation.sourceOptions);
            setEncryptionHeaders(httpHeaders, CRYPTO_KEY_PREFIX, getOperation.targetOptions);
            com.google.api.services.storage.model.RewriteResponse rewriteResult = rewriteOperation.execute();
            return new RewriteOperationResponse(getOperation, rewriteResult.getResource(), rewriteResult.getObjectSize().longValue(), rewriteResult.getDone(), rewriteResult.getRewriteToken(), rewriteResult.getTotalBytesRewritten().longValue());
        } catch (IOException ioException) {
            telemetry.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        }
    }

    @Override
    public BucketAccessControl getAcl(String container, String entityId, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.bucketAccessControls().get(container, entityId).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return null;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public boolean deleteAcl(String container, String entityId, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            storageBackend.bucketAccessControls().delete(container, entityId).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
            return true;
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return false;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public BucketAccessControl createAcl(BucketAccessControl accessControl, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.bucketAccessControls().insert(accessControl.getBucket(), accessControl).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public BucketAccessControl patchAcl(BucketAccessControl accessControl, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.bucketAccessControls().patch(accessControl.getBucket(), accessControl.getEntity(), accessControl).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public List<BucketAccessControl> listAcls(String container, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.bucketAccessControls().list(container).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute().getItems();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public ObjectAccessControl getDefaultAcl(String container, String entityId) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.defaultObjectAccessControls().get(container, entityId).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return null;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public boolean deleteDefaultAcl(String container, String entityId) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            storageBackend.defaultObjectAccessControls().delete(container, entityId).execute();
            return true;
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return false;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControl) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.defaultObjectAccessControls().insert(accessControl.getBucket(), accessControl).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControl) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.defaultObjectAccessControls().patch(accessControl.getBucket(), accessControl.getEntity(), accessControl).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public List<ObjectAccessControl> listDefaultAcls(String container) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.defaultObjectAccessControls().list(container).execute().getItems();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public ObjectAccessControl getAcl(String container, String blobItem, Long versionNumber, String entityId) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.objectAccessControls().get(container, blobItem, entityId).setGeneration(versionNumber).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return null;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public boolean deleteAcl(String container, String blobItem, Long versionNumber, String entityId) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            storageBackend.objectAccessControls().delete(container, blobItem, entityId).setGeneration(versionNumber).execute();
            return true;
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return false;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public ObjectAccessControl createAcl(ObjectAccessControl accessControl) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.objectAccessControls().insert(accessControl.getBucket(), accessControl.getObject(), accessControl).setGeneration(accessControl.getGeneration()).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public ObjectAccessControl patchAcl(ObjectAccessControl accessControl) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.objectAccessControls().patch(accessControl.getBucket(), accessControl.getObject(), accessControl.getEntity(), accessControl).setGeneration(accessControl.getGeneration()).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public List<ObjectAccessControl> listAcls(String container, String blobItem, Long versionNumber) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.objectAccessControls().list(container, blobItem).setGeneration(versionNumber).execute().getItems();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public HmacKey createHmacKey(String accountEmail, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
        Scope currentScope = telemetry.withSpan(currentSpan);
        String projectIdentifier = StorageOption.PROJECT_ID.getString(settings);
        if (null == projectIdentifier) {
            projectIdentifier = this.settings.getProjectId();
        }
        try {
            return storageBackend.projects().hmacKeys().create(projectIdentifier, accountEmail).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
        Scope currentScope = telemetry.withSpan(currentSpan);
        String projectIdentifier = StorageOption.PROJECT_ID.getString(settings);
        if (null == projectIdentifier) {
            projectIdentifier = this.settings.getProjectId();
        }
        try {
            HmacKeysMetadata hmacKeysInfo = storageBackend.projects().hmacKeys().list(projectIdentifier).setServiceAccountEmail(StorageOption.SERVICE_ACCOUNT_EMAIL.getString(settings)).setPageToken(StorageOption.PAGE_TOKEN.getString(settings)).setMaxResults(StorageOption.MAX_RESULTS.getLong(settings)).setShowDeletedKeys(StorageOption.SHOW_DELETED_KEYS.getBoolean(settings)).execute();
            return Tuple.<String, Iterable<HmacKeyMetadata>>of(hmacKeysInfo.getNextPageToken(), hmacKeysInfo.getItems());
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public HmacKeyMetadata getHmacKey(String accessIdentifier, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
        Scope currentScope = telemetry.withSpan(currentSpan);
        String projectIdentifier = StorageOption.PROJECT_ID.getString(settings);
        if (null == projectIdentifier) {
            projectIdentifier = this.settings.getProjectId();
        }
        try {
            return storageBackend.projects().hmacKeys().get(projectIdentifier, accessIdentifier).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyInfo, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
        Scope currentScope = telemetry.withSpan(currentSpan);
        String projectIdentifier = hmacKeyInfo.getProjectId();
        if (null == projectIdentifier) {
            projectIdentifier = this.settings.getProjectId();
        }
        try {
            return storageBackend.projects().hmacKeys().update(projectIdentifier, hmacKeyInfo.getAccessId(), hmacKeyInfo).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public void deleteHmacKey(HmacKeyMetadata hmacKeyInfo, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
        Scope currentScope = telemetry.withSpan(currentSpan);
        String projectIdentifier = hmacKeyInfo.getProjectId();
        if (null == projectIdentifier) {
            projectIdentifier = this.settings.getProjectId();
        }
        try {
            storageBackend.projects().hmacKeys().delete(projectIdentifier, hmacKeyInfo.getAccessId()).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Policy getIamPolicy(String container, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            Storage.Buckets.GetIamPolicy iamPolicyRequest = storageBackend.buckets().getIamPolicy(container).setUserProject(StorageOption.USER_PROJECT.getString(settings));
            if (StorageOption.REQUESTED_POLICY_VERSION.getLong(settings) != null) {
                iamPolicyRequest.setOptionsRequestedPolicyVersion(StorageOption.REQUESTED_POLICY_VERSION.getLong(settings).intValue());
            }
            return iamPolicyRequest.execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Policy setIamPolicy(String container, Policy accessControl, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.buckets().setIamPolicy(container, accessControl).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public TestIamPermissionsResponse testIamPermissions(String container, List<String> permissionList, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.buckets().testIamPermissions(container, permissionList).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public boolean deleteNotification(String container, String alertId) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            storageBackend.notifications().delete(container, alertId).execute();
            return true;
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageError = toStorageException(ioException);
            if (HTTP_NOT_FOUND == storageError.getCode()) {
                return false;
            }
            throw storageError;
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public List<Notification> listNotifications(String container) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.notifications().list(container).execute().getItems();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Notification createNotification(String container, Notification alertId) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.notifications().insert(container, alertId).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public Bucket lockRetentionPolicy(Bucket container, Map<StorageOption, ?> settings) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.buckets().lockRetentionPolicy(container.getName(), StorageOption.IF_METAGENERATION_MATCH.getLong(settings)).setUserProject(StorageOption.USER_PROJECT.getString(settings)).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }

    @Override
    public ServiceAccount getServiceAccount(String projectIdentifier) {
        Span currentSpan = startTraceSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
        Scope currentScope = telemetry.withSpan(currentSpan);
        try {
            return storageBackend.projects().serviceAccount().get(projectIdentifier).execute();
        } catch (IOException ioException) {
            currentSpan.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageException(ioException);
        } finally {
            currentScope.close();
            currentSpan.end();
        }
    }
}
