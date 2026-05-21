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
import java.net.FileNameMap;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HttpStorageRpc implements CloudStorageRpcClient {

    public static final String DEFAULT_PROJECTION = "full";

    public static final String NO_ACL_PROJECTION = "noAcl";

    private static final String ENCRYPTION_KEY_PREFIX = "x-goog-encryption-";

    private static final String SOURCE_ENCRYPTION_KEY_PREFIX = "x-goog-copy-source-encryption-";

    // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
    private static final int SC_REQUESTED_RANGE_NOT_SATISFIABLE = 416;

    private final StorageSettings options;

    private final Storage storage;

    private final Tracer tracer = Tracing.getTracer();

    private final CensusHttpModule censusHttpModule;

    private final HttpRequestInitializer batchRequestInitializer;

    private static final long MEGABYTE = 1024L * 1024L;

    private static final FileNameMap FILE_NAME_MAP = URLConnection.getFileNameMap();

    public HttpStorageRpc(StorageSettings options) {
        HttpTransportOptions transportOptions = (HttpTransportOptions) options.getTransportOptions();
        HttpTransport transport = transportOptions.getHttpTransportFactory().create();
        HttpRequestInitializer initializer = transportOptions.getHttpRequestInitializer(options);
        this.options = options;
        // Open Census initialization
        censusHttpModule = new CensusHttpModule(tracer, true);
        initializer = censusHttpModule.getHttpRequestInitializer(initializer);
        batchRequestInitializer = censusHttpModule.getHttpRequestInitializer(null);
        storage = new Storage.Builder(transport, new JacksonFactory(), initializer).setRootUrl(options.getHost()).setApplicationName(options.getApplicationName()).build();
    }

    private class DefaultRpcBatch implements RpcBatch {

        // Batch size is limited as, due to some current service implementation details, the service
        // performs better if the batches are split for better distribution. See
        // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
        // background.
        private static final int MAX_BATCH_SIZE = 100;

        private final Storage storage;

        private final LinkedList<BatchRequest> batches;

        private int currentBatchSize;

        private DefaultRpcBatch(Storage storage) {
            this.storage = storage;
            batches = new LinkedList<>();
            // add OpenCensus HttpRequestInitializer
            batches.add(storage.batch(batchRequestInitializer));
        }

        @Override
        public void addDelete(StorageObject storageObject, RpcBatch.Callback<Void> callback, Map<StorageOption, ?> options) {
            try {
                if (MAX_BATCH_SIZE == currentBatchSize) {
                    batches.add(storage.batch());
                    currentBatchSize = 0;
                }
                deleteCall(storageObject, options).queue(batches.getLast(), toJsonCallback(callback));
                currentBatchSize += 1;
            } catch (IOException ex) {
                throw translate(ex);
            }
        }

        @Override
        public void addPatch(StorageObject storageObject, RpcBatch.Callback<StorageObject> callback, Map<StorageOption, ?> options) {
            try {
                if (MAX_BATCH_SIZE == currentBatchSize) {
                    batches.add(storage.batch());
                    currentBatchSize = 0;
                }
                patchCall(storageObject, options).queue(batches.getLast(), toJsonCallback(callback));
                currentBatchSize += 1;
            } catch (IOException ex) {
                throw translate(ex);
            }
        }

        @Override
        public void addGet(StorageObject storageObject, RpcBatch.Callback<StorageObject> callback, Map<StorageOption, ?> options) {
            try {
                if (MAX_BATCH_SIZE == currentBatchSize) {
                    batches.add(storage.batch());
                    currentBatchSize = 0;
                }
                getCall(storageObject, options).queue(batches.getLast(), toJsonCallback(callback));
                currentBatchSize += 1;
            } catch (IOException ex) {
                throw translate(ex);
            }
        }

        @Override
        public void submit() {
            Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
            Scope scope = tracer.withSpan(span);
            try {
                span.putAttribute("batch size", AttributeValue.longAttributeValue(batches.size()));
                for (BatchRequest batch : batches) {
                    // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
                    // Here we only add a annotation to at least know how much time each batch takes.
                    span.addAnnotation("Execute batch request");
                    batch.setBatchUrl(new GenericUrl(String.format("%s/batch/storage/v1", options.getHost())));
                    batch.execute();
                }
            } catch (IOException ex) {
                span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
                throw translate(ex);
            } finally {
                scope.close();
                span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
            }
        }
    }

    private static <T> JsonBatchCallback<T> toJsonCallback(final RpcBatch.Callback<T> callback) {
        return new JsonBatchCallback<T>() {

            @Override
            public void onSuccess(T response, HttpHeaders httpHeaders) throws IOException {
                callback.onSuccess(response);
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders) throws IOException {
                callback.onFailure(googleJsonError);
            }
        };
    }

    private static StorageOperationException translate(IOException exception) {
        return new StorageOperationException(exception);
    }

    private static StorageOperationException translate(GoogleJsonError exception) {
        return new StorageOperationException(exception);
    }

    private static void setEncryptionHeaders(HttpHeaders headers, String headerPrefix, Map<StorageOption, ?> options) {
        String key = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(options);
        if (null != key) {
            BaseEncoding base64 = BaseEncoding.base64();
            HashFunction hashFunction = Hashing.sha256();
            headers.set(headerPrefix + "algorithm", "AES256");
            headers.set(headerPrefix + "key", key);
            headers.set(headerPrefix + "key-sha256", base64.encode(hashFunction.hashBytes(base64.decode(key)).asBytes()));
        }
    }

    /**
     * Helper method to start a span.
     */
    private Span startSpan(String spanName) {
        return tracer.spanBuilder(spanName).setRecordEvents(censusHttpModule.isRecordEvents()).startSpan();
    }

    @Override
    public Bucket create(Bucket bucket, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.buckets().insert(this.options.getProjectId(), bucket).setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(options)).setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public StorageObject create(StorageObject storageObject, final InputStream content, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
        Scope scope = tracer.withSpan(span);
        try {
            Storage.Objects.Insert insert = storage.objects().insert(storageObject.getBucket(), storageObject, new InputStreamContent(detectContentType(storageObject, options), content));
            insert.getMediaHttpUploader().setDirectUploadEnabled(true);
            Boolean disableGzipContent = StorageOption.IF_DISABLE_GZIP_CONTENT.getBoolean(options);
            if (null != disableGzipContent) {
                insert.setDisableGZipContent(disableGzipContent);
            }
            setEncryptionHeaders(insert.getRequestHeaders(), ENCRYPTION_KEY_PREFIX, options);
            return insert.setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(options)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(options)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(options)).setUserProject(StorageOption.USER_PROJECT.getString(options)).setKmsKeyName(StorageOption.KMS_KEY_NAME.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
        Scope scope = tracer.withSpan(span);
        try {
            Buckets buckets = storage.buckets().list(this.options.getProjectId()).setProjection(DEFAULT_PROJECTION).setPrefix(StorageOption.PREFIX.getString(options)).setMaxResults(StorageOption.MAX_RESULTS.getLong(options)).setPageToken(StorageOption.PAGE_TOKEN.getString(options)).setFields(StorageOption.FIELDS.getString(options)).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
            return Tuple.<String, Iterable<Bucket>>of(buckets.getNextPageToken(), buckets.getItems());
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Tuple<String, Iterable<StorageObject>> list(final String bucket, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
        Scope scope = tracer.withSpan(span);
        try {
            Objects objects = storage.objects().list(bucket).setProjection(DEFAULT_PROJECTION).setVersions(StorageOption.VERSIONS.getBoolean(options)).setDelimiter(StorageOption.DELIMITER.getString(options)).setStartOffset(StorageOption.START_OFF_SET.getString(options)).setEndOffset(StorageOption.END_OFF_SET.getString(options)).setPrefix(StorageOption.PREFIX.getString(options)).setMaxResults(StorageOption.MAX_RESULTS.getLong(options)).setPageToken(StorageOption.PAGE_TOKEN.getString(options)).setFields(StorageOption.FIELDS.getString(options)).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
            Iterable<StorageObject> storageObjects = Iterables.concat(firstNonNull(objects.getItems(), ImmutableList.<StorageObject>of()), null != objects.getPrefixes() ? Lists.transform(objects.getPrefixes(), objectFromPrefix(bucket)) : ImmutableList.<StorageObject>of());
            return Tuple.of(objects.getNextPageToken(), storageObjects);
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private static String detectContentType(StorageObject object, Map<StorageOption, ?> options) {
        String contentType = object.getContentType();
        if (null != contentType) {
            return contentType;
        }
        if (StorageOption.DETECT_CONTENT_TYPE.get(options) == Boolean.TRUE) {
            contentType = FILE_NAME_MAP.getContentTypeFor(object.getName().toLowerCase(Locale.ENGLISH));
        }
        return firstNonNull(contentType, "application/octet-stream");
    }

    private static Function<String, StorageObject> objectFromPrefix(final String bucket) {
        return new Function<String, StorageObject>() {

            @Override
            public StorageObject apply(String prefix) {
                return new StorageObject().set("isDirectory", true).setBucket(bucket).setName(prefix).setSize(BigInteger.ZERO);
            }
        };
    }

    @Override
    public Bucket get(Bucket bucket, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.buckets().get(bucket.getName()).setProjection(DEFAULT_PROJECTION).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setFields(StorageOption.FIELDS.getString(options)).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return null;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private Storage.Objects.Get getCall(StorageObject object, Map<StorageOption, ?> options) throws IOException {
        Storage.Objects.Get get = storage.objects().get(object.getBucket(), object.getName());
        setEncryptionHeaders(get.getRequestHeaders(), ENCRYPTION_KEY_PREFIX, options);
        return get.setGeneration(object.getGeneration()).setProjection(DEFAULT_PROJECTION).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(options)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(options)).setFields(StorageOption.FIELDS.getString(options)).setUserProject(StorageOption.USER_PROJECT.getString(options));
    }

    @Override
    public StorageObject get(StorageObject object, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
        Scope scope = tracer.withSpan(span);
        try {
            return getCall(object, options).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return null;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Bucket patch(Bucket bucket, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
        Scope scope = tracer.withSpan(span);
        try {
            String projection = StorageOption.PROJECTION.getString(options);
            if (null != bucket.getIamConfiguration() && null != bucket.getIamConfiguration().getBucketPolicyOnly() && null != bucket.getIamConfiguration().getBucketPolicyOnly().getEnabled() && bucket.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
                // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
                // the request
                bucket.setDefaultObjectAcl(null);
                bucket.setAcl(null);
                if (null == projection) {
                    projection = NO_ACL_PROJECTION;
                }
            }
            return storage.buckets().patch(bucket.getName(), bucket).setProjection(null == projection ? DEFAULT_PROJECTION : projection).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(options)).setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(options)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private Storage.Objects.Patch patchCall(StorageObject storageObject, Map<StorageOption, ?> options) throws IOException {
        return storage.objects().patch(storageObject.getBucket(), storageObject.getName(), storageObject).setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(options)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(options)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(options)).setUserProject(StorageOption.USER_PROJECT.getString(options));
    }

    @Override
    public StorageObject patch(StorageObject storageObject, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
        Scope scope = tracer.withSpan(span);
        try {
            return patchCall(storageObject, options).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public boolean delete(Bucket bucket, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
        Scope scope = tracer.withSpan(span);
        try {
            storage.buckets().delete(bucket.getName()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
            return true;
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return false;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private Storage.Objects.Delete deleteCall(StorageObject blob, Map<StorageOption, ?> options) throws IOException {
        return storage.objects().delete(blob.getBucket(), blob.getName()).setGeneration(blob.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(options)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(options)).setUserProject(StorageOption.USER_PROJECT.getString(options));
    }

    @Override
    public boolean delete(StorageObject blob, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
        Scope scope = tracer.withSpan(span);
        try {
            deleteCall(blob, options).execute();
            return true;
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return false;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public StorageObject compose(Iterable<StorageObject> sources, StorageObject target, Map<StorageOption, ?> targetOptions) {
        ComposeRequest request = new ComposeRequest();
        request.setDestination(target);
        List<ComposeRequest.SourceObjects> sourceObjects = new ArrayList<>();
        for (StorageObject source : sources) {
            ComposeRequest.SourceObjects sourceObject = new ComposeRequest.SourceObjects();
            sourceObject.setName(source.getName());
            Long generation = source.getGeneration();
            if (null != generation) {
                sourceObject.setGeneration(generation);
                sourceObject.setObjectPreconditions(new ObjectPreconditions().setIfGenerationMatch(generation));
            }
            sourceObjects.add(sourceObject);
        }
        request.setSourceObjects(sourceObjects);
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.objects().compose(target.getBucket(), target.getName(), request).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(targetOptions)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(targetOptions)).setUserProject(StorageOption.USER_PROJECT.getString(targetOptions)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public byte[] load(StorageObject from, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
        Scope scope = tracer.withSpan(span);
        try {
            Storage.Objects.Get getRequest = storage.objects().get(from.getBucket(), from.getName()).setGeneration(from.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(options)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(options)).setUserProject(StorageOption.USER_PROJECT.getString(options));
            setEncryptionHeaders(getRequest.getRequestHeaders(), ENCRYPTION_KEY_PREFIX, options);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            getRequest.executeMedia().download(out);
            return out.toByteArray();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public RpcBatch createBatch() {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BATCH);
        Scope scope = tracer.withSpan(span);
        try {
            return new DefaultRpcBatch(storage);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private Get createReadRequest(StorageObject from, Map<StorageOption, ?> options) throws IOException {
        Get req = storage.objects().get(from.getBucket(), from.getName()).setGeneration(from.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(options)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(options)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(options)).setUserProject(StorageOption.USER_PROJECT.getString(options));
        setEncryptionHeaders(req.getRequestHeaders(), ENCRYPTION_KEY_PREFIX, options);
        req.setReturnRawInputStream(true);
        return req;
    }

    @Override
    public long read(StorageObject from, Map<StorageOption, ?> options, long position, OutputStream outputStream) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
        Scope scope = tracer.withSpan(span);
        try {
            Get req = createReadRequest(from, options);
            req.getMediaHttpDownloader().setBytesDownloaded(position);
            req.getMediaHttpDownloader().setDirectDownloadEnabled(true);
            req.executeMediaAndDownloadTo(outputStream);
            return req.getMediaHttpDownloader().getNumBytesDownloaded();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (SC_REQUESTED_RANGE_NOT_SATISFIABLE == serviceException.getCode()) {
                return 0;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Tuple<String, byte[]> read(StorageObject from, Map<StorageOption, ?> options, long position, int bytes) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
        Scope scope = tracer.withSpan(span);
        try {
            checkArgument(0 <= position, "Position should be non-negative, is " + position);
            Get req = createReadRequest(from, options);
            StringBuilder range = new StringBuilder();
            range.append("bytes=").append(position).append("-").append(position + bytes - 1);
            HttpHeaders requestHeaders = req.getRequestHeaders();
            requestHeaders.setRange(range.toString());
            ByteArrayOutputStream output = new ByteArrayOutputStream(bytes);
            req.executeMedia().download(output);
            String etag = req.getLastResponseHeaders().getETag();
            return Tuple.of(etag, output.toByteArray());
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = StorageOperationException.translateException(ex);
            if (SC_REQUESTED_RANGE_NOT_SATISFIABLE == serviceException.getCode()) {
                return Tuple.of(null, new byte[0]);
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public void write(String uploadId, byte[] toWrite, int toWriteOffset, long destOffset, int length, boolean last) {
        writeWithResponse(uploadId, toWrite, toWriteOffset, destOffset, length, last);
    }

    @Override
    public StorageObject writeWithResponse(String uploadId, byte[] toWrite, int toWriteOffset, long destOffset, int length, boolean last) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
        Scope scope = tracer.withSpan(span);
        StorageObject updatedBlob = null;
        try {
            if (0 == length && !last) {
                return updatedBlob;
            }
            GenericUrl url = new GenericUrl(uploadId);
            HttpRequest httpRequest = storage.getRequestFactory().buildPutRequest(url, new ByteArrayContent(null, toWrite, toWriteOffset, length));
            long limit = destOffset + length;
            StringBuilder range = new StringBuilder("bytes ");
            if (0 != length) {
                range.append(destOffset).append('-').append(limit - 1);
            } else {
                range.append('*');
            }
            range.append('/');
            if (!last) {
                range.append('*');
            } else {
                range.append(limit);
            }
            httpRequest.getHeaders().setContentRange(range.toString());
            if (last) {
                httpRequest.setParser(storage.getObjectParser());
            }
            int code;
            String message;
            IOException exception = null;
            HttpResponse response = null;
            try {
                response = httpRequest.execute();
                code = response.getStatusCode();
                message = response.getStatusMessage();
                String contentType = response.getContentType();
                if (last && (200 == code || 201 == code) && null != contentType && contentType.startsWith("application/json")) {
                    updatedBlob = response.parseAs(StorageObject.class);
                }
            } catch (HttpResponseException ex) {
                exception = ex;
                code = ex.getStatusCode();
                message = ex.getStatusMessage();
            } finally {
                if (null != response) {
                    response.disconnect();
                }
            }
            if (!last && 308 != code || last && !(200 == code || 201 == code)) {
                if (null != exception) {
                    throw exception;
                }
                GoogleJsonError error = new GoogleJsonError();
                error.setCode(code);
                error.setMessage(message);
                throw translate(error);
            }
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
        return updatedBlob;
    }

    @Override
    public String open(StorageObject object, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
        Scope scope = tracer.withSpan(span);
        try {
            Insert req = storage.objects().insert(object.getBucket(), object);
            GenericUrl url = req.buildHttpRequest().getUrl();
            String scheme = url.getScheme();
            String host = url.getHost();
            int port = url.getPort();
            port = 0 < port ? port : url.toURL().getDefaultPort();
            String path = "/upload" + url.getRawPath();
            url = new GenericUrl(scheme + "://" + host + ":" + port + path);
            url.set("uploadType", "resumable");
            url.set("name", object.getName());
            for (StorageOption option : options.keySet()) {
                Object content = option.get(options);
                if (null != content) {
                    url.set(option.getValue(), content.toString());
                }
            }
            JsonFactory jsonFactory = storage.getJsonFactory();
            HttpRequestFactory requestFactory = storage.getRequestFactory();
            HttpRequest httpRequest = requestFactory.buildPostRequest(url, new JsonHttpContent(jsonFactory, object));
            HttpHeaders requestHeaders = httpRequest.getHeaders();
            requestHeaders.set("X-Upload-Content-Type", detectContentType(object, options));
            String key = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(options);
            if (null != key) {
                BaseEncoding base64 = BaseEncoding.base64();
                HashFunction hashFunction = Hashing.sha256();
                requestHeaders.set("x-goog-encryption-algorithm", "AES256");
                requestHeaders.set("x-goog-encryption-key", key);
                requestHeaders.set("x-goog-encryption-key-sha256", base64.encode(hashFunction.hashBytes(base64.decode(key)).asBytes()));
            }
            HttpResponse response = httpRequest.execute();
            if (200 != response.getStatusCode()) {
                GoogleJsonError error = new GoogleJsonError();
                error.setCode(response.getStatusCode());
                error.setMessage(response.getStatusMessage());
                throw translate(error);
            }
            return response.getHeaders().getLocation();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public String open(String signedURL) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
        Scope scope = tracer.withSpan(span);
        try {
            GenericUrl url = new GenericUrl(signedURL);
            url.set("uploadType", "resumable");
            String bytesArrayParameters = "";
            byte[] bytesArray = new byte[bytesArrayParameters.length()];
            HttpRequestFactory requestFactory = storage.getRequestFactory();
            HttpRequest httpRequest = requestFactory.buildPostRequest(url, new ByteArrayContent("", bytesArray, 0, bytesArray.length));
            HttpHeaders requestHeaders = httpRequest.getHeaders();
            requestHeaders.set("X-Upload-Content-Type", "");
            requestHeaders.set("x-goog-resumable", "start");
            HttpResponse response = httpRequest.execute();
            if (201 != response.getStatusCode()) {
                GoogleJsonError error = new GoogleJsonError();
                error.setCode(response.getStatusCode());
                error.setMessage(response.getStatusMessage());
                throw translate(error);
            }
            return response.getHeaders().getLocation();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public CloudStorageRpcClient.RewriteOperationResponse openRewrite(RewriteOperationRequest rewriteRequest) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
        Scope scope = tracer.withSpan(span);
        try {
            return rewrite(rewriteRequest, null);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public CloudStorageRpcClient.RewriteOperationResponse continueRewrite(RewriteOperationResponse previousResponse) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
        Scope scope = tracer.withSpan(span);
        try {
            return rewrite(previousResponse.rewriteRequest, previousResponse.rewriteToken);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private RewriteOperationResponse rewrite(RewriteOperationRequest req, String token) {
        try {
            String userProject = StorageOption.USER_PROJECT.getString(req.sourceOptions);
            if (null == userProject) {
                userProject = StorageOption.USER_PROJECT.getString(req.targetOptions);
            }
            Long maxBytesRewrittenPerCall = null != req.megabytesRewrittenPerCall ? req.megabytesRewrittenPerCall * MEGABYTE : null;
            Storage.Objects.Rewrite rewrite = storage.objects().rewrite(req.source.getBucket(), req.source.getName(), req.target.getBucket(), req.target.getName(), req.overrideInfo ? req.target : null).setSourceGeneration(req.source.getGeneration()).setRewriteToken(token).setMaxBytesRewrittenPerCall(maxBytesRewrittenPerCall).setProjection(DEFAULT_PROJECTION).setIfSourceMetagenerationMatch(StorageOption.IF_SOURCE_METAGENERATION_MATCH.getLong(req.sourceOptions)).setIfSourceMetagenerationNotMatch(StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(req.sourceOptions)).setIfSourceGenerationMatch(StorageOption.IF_SOURCE_GENERATION_MATCH.getLong(req.sourceOptions)).setIfSourceGenerationNotMatch(StorageOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(req.sourceOptions)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(req.targetOptions)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(req.targetOptions)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(req.targetOptions)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(req.targetOptions)).setDestinationPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(req.targetOptions)).setUserProject(userProject).setDestinationKmsKeyName(StorageOption.KMS_KEY_NAME.getString(req.targetOptions));
            HttpHeaders requestHeaders = rewrite.getRequestHeaders();
            setEncryptionHeaders(requestHeaders, SOURCE_ENCRYPTION_KEY_PREFIX, req.sourceOptions);
            setEncryptionHeaders(requestHeaders, ENCRYPTION_KEY_PREFIX, req.targetOptions);
            com.google.api.services.storage.model.RewriteResponse rewriteResponse = rewrite.execute();
            return new RewriteOperationResponse(req, rewriteResponse.getResource(), rewriteResponse.getObjectSize().longValue(), rewriteResponse.getDone(), rewriteResponse.getRewriteToken(), rewriteResponse.getTotalBytesRewritten().longValue());
        } catch (IOException ex) {
            tracer.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        }
    }

    @Override
    public BucketAccessControl getAcl(String bucket, String entity, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.bucketAccessControls().get(bucket, entity).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return null;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public boolean deleteAcl(String bucket, String entity, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            storage.bucketAccessControls().delete(bucket, entity).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
            return true;
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return false;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public BucketAccessControl createAcl(BucketAccessControl acl, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.bucketAccessControls().insert(acl.getBucket(), acl).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public BucketAccessControl patchAcl(BucketAccessControl acl, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.bucketAccessControls().patch(acl.getBucket(), acl.getEntity(), acl).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public List<BucketAccessControl> listAcls(String bucket, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.bucketAccessControls().list(bucket).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute().getItems();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl getDefaultAcl(String bucket, String entity) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.defaultObjectAccessControls().get(bucket, entity).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return null;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public boolean deleteDefaultAcl(String bucket, String entity) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            storage.defaultObjectAccessControls().delete(bucket, entity).execute();
            return true;
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return false;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl createDefaultAcl(ObjectAccessControl acl) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.defaultObjectAccessControls().insert(acl.getBucket(), acl).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl patchDefaultAcl(ObjectAccessControl acl) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.defaultObjectAccessControls().patch(acl.getBucket(), acl.getEntity(), acl).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public List<ObjectAccessControl> listDefaultAcls(String bucket) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.defaultObjectAccessControls().list(bucket).execute().getItems();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl getAcl(String bucket, String object, Long generation, String entity) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.objectAccessControls().get(bucket, object, entity).setGeneration(generation).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return null;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public boolean deleteAcl(String bucket, String object, Long generation, String entity) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            storage.objectAccessControls().delete(bucket, object, entity).setGeneration(generation).execute();
            return true;
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return false;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl createAcl(ObjectAccessControl acl) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.objectAccessControls().insert(acl.getBucket(), acl.getObject(), acl).setGeneration(acl.getGeneration()).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl patchAcl(ObjectAccessControl acl) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.objectAccessControls().patch(acl.getBucket(), acl.getObject(), acl.getEntity(), acl).setGeneration(acl.getGeneration()).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public List<ObjectAccessControl> listAcls(String bucket, String object, Long generation) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.objectAccessControls().list(bucket, object).setGeneration(generation).execute().getItems();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public HmacKey createHmacKey(String serviceAccountEmail, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
        Scope scope = tracer.withSpan(span);
        String projectId = StorageOption.PROJECT_ID.getString(options);
        if (null == projectId) {
            projectId = this.options.getProjectId();
        }
        try {
            return storage.projects().hmacKeys().create(projectId, serviceAccountEmail).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
        Scope scope = tracer.withSpan(span);
        String projectId = StorageOption.PROJECT_ID.getString(options);
        if (null == projectId) {
            projectId = this.options.getProjectId();
        }
        try {
            HmacKeysMetadata hmacKeysMetadata = storage.projects().hmacKeys().list(projectId).setServiceAccountEmail(StorageOption.SERVICE_ACCOUNT_EMAIL.getString(options)).setPageToken(StorageOption.PAGE_TOKEN.getString(options)).setMaxResults(StorageOption.MAX_RESULTS.getLong(options)).setShowDeletedKeys(StorageOption.SHOW_DELETED_KEYS.getBoolean(options)).execute();
            return Tuple.<String, Iterable<HmacKeyMetadata>>of(hmacKeysMetadata.getNextPageToken(), hmacKeysMetadata.getItems());
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public HmacKeyMetadata getHmacKey(String accessId, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
        Scope scope = tracer.withSpan(span);
        String projectId = StorageOption.PROJECT_ID.getString(options);
        if (null == projectId) {
            projectId = this.options.getProjectId();
        }
        try {
            return storage.projects().hmacKeys().get(projectId, accessId).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
        Scope scope = tracer.withSpan(span);
        String projectId = hmacKeyMetadata.getProjectId();
        if (null == projectId) {
            projectId = this.options.getProjectId();
        }
        try {
            return storage.projects().hmacKeys().update(projectId, hmacKeyMetadata.getAccessId(), hmacKeyMetadata).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public void deleteHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
        Scope scope = tracer.withSpan(span);
        String projectId = hmacKeyMetadata.getProjectId();
        if (null == projectId) {
            projectId = this.options.getProjectId();
        }
        try {
            storage.projects().hmacKeys().delete(projectId, hmacKeyMetadata.getAccessId()).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Policy getIamPolicy(String bucket, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
        Scope scope = tracer.withSpan(span);
        try {
            Storage.Buckets.GetIamPolicy getIamPolicy = storage.buckets().getIamPolicy(bucket).setUserProject(StorageOption.USER_PROJECT.getString(options));
            if (StorageOption.REQUESTED_POLICY_VERSION.getLong(options) != null) {
                getIamPolicy.setOptionsRequestedPolicyVersion(StorageOption.REQUESTED_POLICY_VERSION.getLong(options).intValue());
            }
            return getIamPolicy.execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Policy setIamPolicy(String bucket, Policy policy, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.buckets().setIamPolicy(bucket, policy).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public TestIamPermissionsResponse testIamPermissions(String bucket, List<String> permissions, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.buckets().testIamPermissions(bucket, permissions).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public boolean deleteNotification(String bucket, String notification) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
        Scope scope = tracer.withSpan(span);
        try {
            storage.notifications().delete(bucket, notification).execute();
            return true;
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            StorageOperationException serviceException = translate(ex);
            if (HTTP_NOT_FOUND == serviceException.getCode()) {
                return false;
            }
            throw serviceException;
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public List<Notification> listNotifications(String bucket) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.notifications().list(bucket).execute().getItems();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Notification createNotification(String bucket, Notification notification) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.notifications().insert(bucket, notification).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Bucket lockRetentionPolicy(Bucket bucket, Map<StorageOption, ?> options) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.buckets().lockRetentionPolicy(bucket.getName(), StorageOption.IF_METAGENERATION_MATCH.getLong(options)).setUserProject(StorageOption.USER_PROJECT.getString(options)).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ServiceAccount getServiceAccount(String projectId) {
        Span span = startSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
        Scope scope = tracer.withSpan(span);
        try {
            return storage.projects().serviceAccount().get(projectId).execute();
        } catch (IOException ex) {
            span.setStatus(Status.UNKNOWN.withDescription(ex.getMessage()));
            throw translate(ex);
        } finally {
            scope.close();
            span.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }
}
