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

public class HttpStorageClient implements StorageRpcClient {

    public static final String DEFAULT_PROJECTION = "full";

    public static final String NO_ACL_PROJECTION = "noAcl";

    private static final String ENCRYPTION_PREFIX = "x-goog-encryption-";

    private static final String SOURCE_CRYPTO_KEY_PREFIX = "x-goog-copy-source-encryption-";

    // declare this HttpStatus code here as it's not included in java.net.HttpURLConnection
    private static final int SC_RANGE_NOT_SATISFIABLE = 416;

    private final StorageClientOptions clientConfig;

    private final Storage dataStore;

    private final Tracer telemetry = Tracing.getTracer();

    private final CensusHttpModule monitoringModule;

    private final HttpRequestInitializer requestInitializer;

    private static final long ONE_MEGABYTE = 1024L * 1024L;

    private static final FileNameMap NAME_TO_MIME_MAP = URLConnection.getFileNameMap();

    private class DefaultRpcBatcher implements RpcRequestBatch {

        // Batch size is limited as, due to some current service implementation details, the service
        // performs better if the batches are split for better distribution. See
        // https://github.com/googleapis/google-cloud-java/pull/952#issuecomment-213466772 for
        // background.
        private static final int BATCH_MAX_SIZE = 100;

        private final Storage dataStore;

        private final LinkedList<BatchRequest> batchQueue;

        private int currentBatchCount;

        @Override
        public void submitBatch() {
            Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_BATCH_SUBMIT);
            Scope tracingScope = telemetry.withSpan(spanContext);
            try {
                spanContext.putAttribute("batch size", AttributeValue.longAttributeValue(batchQueue.size()));
                for (BatchRequest batchRequest : batchQueue) {
                    // TODO(hailongwen@): instrument 'google-api-java-client' to further break down the span.
                    // Here we only add a annotation to at least know how much time each batch takes.
                    spanContext.addAnnotation("Execute batch request");
                    batchRequest.setBatchUrl(new GenericUrl(String.format("%s/batch/storage/v1", clientConfig.getHost())));
                    batchRequest.execute();
                }
            } catch (IOException ioException) {
                spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
                throw toStorageServiceException(ioException);
            } finally {
                tracingScope.close();
                spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
            }
        }

        @Override
        public void addPatchOperation(StorageObject objectMetadata, ResultCallback<StorageObject> completionHandler, Map<StorageOption, ?> clientConfig) {
            try {
                if (BATCH_MAX_SIZE == currentBatchCount) {
                    batchQueue.add(dataStore.batch());
                    currentBatchCount = 0;
                }
                buildPatchCall(objectMetadata, clientConfig).queue(batchQueue.getLast(), createJsonCallback(completionHandler));
                currentBatchCount += 1;
            } catch (IOException ioException) {
                throw toStorageServiceException(ioException);
            }
        }

        @Override
        public void addDeleteOperation(StorageObject objectMetadata, ResultCallback<Void> completionHandler, Map<StorageOption, ?> clientConfig) {
            try {
                if (BATCH_MAX_SIZE == currentBatchCount) {
                    batchQueue.add(dataStore.batch());
                    currentBatchCount = 0;
                }
                buildDeleteCall(objectMetadata, clientConfig).queue(batchQueue.getLast(), createJsonCallback(completionHandler));
                currentBatchCount += 1;
            } catch (IOException ioException) {
                throw toStorageServiceException(ioException);
            }
        }

        @Override
        public void addGetRequest(StorageObject objectMetadata, ResultCallback<StorageObject> completionHandler, Map<StorageOption, ?> clientConfig) {
            try {
                if (BATCH_MAX_SIZE == currentBatchCount) {
                    batchQueue.add(dataStore.batch());
                    currentBatchCount = 0;
                }
                getCall(objectMetadata, clientConfig).queue(batchQueue.getLast(), createJsonCallback(completionHandler));
                currentBatchCount += 1;
            } catch (IOException ioException) {
                throw toStorageServiceException(ioException);
            }
        }

        private DefaultRpcBatcher(Storage dataStore) {
            this.dataStore = dataStore;
            batchQueue = new LinkedList<>();
            // add OpenCensus HttpRequestInitializer
            batchQueue.add(dataStore.batch(requestInitializer));
        }

    }

    @Override
    public StorageObject patch(StorageObject objectMetadata, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return buildPatchCall(objectMetadata, clientConfig).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControl) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_DEFAULT_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.defaultObjectAccessControls().patch(accessControl.getBucket(), accessControl.getEntity(), accessControl).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Policy getIamPolicy(String container, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_IAM_POLICY);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            Storage.Buckets.GetIamPolicy iamPolicyRequest = dataStore.buckets().getIamPolicy(container).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
            if (StorageOption.REQUESTED_POLICY_VERSION.getLong(clientConfig) != null) {
                iamPolicyRequest.setOptionsRequestedPolicyVersion(StorageOption.REQUESTED_POLICY_VERSION.getLong(clientConfig).intValue());
            }
            return iamPolicyRequest.execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Policy setIamPolicy(String container, Policy accessControl, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_SET_BUCKET_IAM_POLICY);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.buckets().setIamPolicy(container, accessControl).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_HMAC_KEYS);
        Scope tracingScope = telemetry.withSpan(spanContext);
        String tenantId = StorageOption.PROJECT_ID.getString(clientConfig);
        if (null == tenantId) {
            tenantId = this.clientConfig.getProjectId();
        }
        try {
            HmacKeysMetadata hmacMetadata = dataStore.projects().hmacKeys().list(tenantId).setServiceAccountEmail(StorageOption.SERVICE_ACCOUNT_EMAIL.getString(clientConfig)).setPageToken(StorageOption.PAGE_TOKEN.getString(clientConfig)).setMaxResults(StorageOption.MAX_RESULTS.getLong(clientConfig)).setShowDeletedKeys(StorageOption.SHOW_DELETED_KEYS.getBoolean(clientConfig)).execute();
            return Tuple.<String, Iterable<HmacKeyMetadata>>of(hmacMetadata.getNextPageToken(), hmacMetadata.getItems());
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public RpcRequestBatch createBatch() {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BATCH);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return new DefaultRpcBatcher(dataStore);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Bucket get(Bucket container, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.buckets().get(container.getName()).setProjection(DEFAULT_PROJECTION).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setFields(StorageOption.FIELDS.getString(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return null;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl createAcl(ObjectAccessControl accessControl) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.objectAccessControls().insert(accessControl.getBucket(), accessControl.getObject(), accessControl).setGeneration(accessControl.getGeneration()).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public StorageRpcClient.RewriteResult openRewrite(ObjectRewriteRequest rewriteOp) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN_REWRITE);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return executeRewrite(rewriteOp, null);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public long read(StorageObject sourceObject, Map<StorageOption, ?> clientConfig, long offset, OutputStream destinationStream) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            Get getOperation = buildReadRequest(sourceObject, clientConfig);
            getOperation.getMediaHttpDownloader().setBytesDownloaded(offset);
            getOperation.getMediaHttpDownloader().setDirectDownloadEnabled(true);
            getOperation.executeMediaAndDownloadTo(destinationStream);
            return getOperation.getMediaHttpDownloader().getNumBytesDownloaded();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (SC_RANGE_NOT_SATISFIABLE == storageFailure.getCode()) {
                return 0;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    public HttpStorageClient(StorageClientOptions clientConfig) {
        HttpTransportOptions transportConfig = (HttpTransportOptions) clientConfig.getTransportOptions();
        HttpTransport httpConnection = transportConfig.getHttpTransportFactory().create();
        HttpRequestInitializer requestInit = transportConfig.getHttpRequestInitializer(clientConfig);
        this.clientConfig = clientConfig;
        // Open Census initialization
        monitoringModule = new CensusHttpModule(telemetry, true);
        requestInit = monitoringModule.getHttpRequestInitializer(requestInit);
        requestInitializer = monitoringModule.getHttpRequestInitializer(null);
        dataStore = new Storage.Builder(httpConnection, new JacksonFactory(), requestInit).setRootUrl(clientConfig.getHost()).setApplicationName(clientConfig.getApplicationName()).build();
    }

    @Override
    public BucketAccessControl getAcl(String container, String principal, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_BUCKET_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.bucketAccessControls().get(container, principal).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return null;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private Storage.Objects.Delete buildDeleteCall(StorageObject storageEntry, Map<StorageOption, ?> clientConfig) throws IOException {
        return dataStore.objects().delete(storageEntry.getBucket(), storageEntry.getName()).setGeneration(storageEntry.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
    }

    @Override
    public HmacKey createHmacKey(String principalEmail, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_HMAC_KEY);
        Scope tracingScope = telemetry.withSpan(spanContext);
        String tenantId = StorageOption.PROJECT_ID.getString(clientConfig);
        if (null == tenantId) {
            tenantId = this.clientConfig.getProjectId();
        }
        try {
            return dataStore.projects().hmacKeys().create(tenantId, principalEmail).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl getAcl(String container, String obj, Long version, String principal) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.objectAccessControls().get(container, obj, principal).setGeneration(version).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return null;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControl) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT_DEFAULT_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.defaultObjectAccessControls().insert(accessControl.getBucket(), accessControl).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public TestIamPermissionsResponse testIamPermissions(String container, List<String> accessList, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_TEST_BUCKET_IAM_PERMISSIONS);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.buckets().testIamPermissions(container, accessList).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public StorageObject compose(Iterable<StorageObject> storageObjects, StorageObject destinationObject, Map<StorageOption, ?> destinationParams) {
        ComposeRequest composePayload = new ComposeRequest();
        composePayload.setDestination(destinationObject);
        List<ComposeRequest.SourceObjects> objectList = new ArrayList<>();
        for (StorageObject originObject : storageObjects) {
            ComposeRequest.SourceObjects objectEntry = new ComposeRequest.SourceObjects();
            objectEntry.setName(originObject.getName());
            Long version = originObject.getGeneration();
            if (null != version) {
                objectEntry.setGeneration(version);
                objectEntry.setObjectPreconditions(new ObjectPreconditions().setIfGenerationMatch(version));
            }
            objectList.add(objectEntry);
        }
        composePayload.setSourceObjects(objectList);
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_COMPOSE);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.objects().compose(destinationObject.getBucket(), destinationObject.getName(), composePayload).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(destinationParams)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(destinationParams)).setUserProject(StorageOption.USER_PROJECT.getString(destinationParams)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private Storage.Objects.Patch buildPatchCall(StorageObject objectMetadata, Map<StorageOption, ?> clientConfig) throws IOException {
        return dataStore.objects().patch(objectMetadata.getBucket(), objectMetadata.getName(), objectMetadata).setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
    }

    @Override
    public BucketAccessControl patchAcl(BucketAccessControl accessControl, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.bucketAccessControls().patch(accessControl.getBucket(), accessControl.getEntity(), accessControl).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public List<ObjectAccessControl> listAcls(String container, String obj, Long version) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_ACLS);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.objectAccessControls().list(container, obj).setGeneration(version).execute().getItems();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public boolean deleteDefaultAcl(String container, String principal) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_DEFAULT_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            dataStore.defaultObjectAccessControls().delete(container, principal).execute();
            return true;
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return false;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public boolean deleteAcl(String container, String principal, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            dataStore.bucketAccessControls().delete(container, principal).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
            return true;
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return false;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public String open(String signedUrlParam) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            GenericUrl endpoint = new GenericUrl(signedUrlParam);
            endpoint.set("uploadType", "resumable");
            String bytesParamString = "";
            byte[] dataBytes = new byte[bytesParamString.length()];
            HttpRequestFactory httpRequestFactory = dataStore.getRequestFactory();
            HttpRequest httpCall = httpRequestFactory.buildPostRequest(endpoint, new ByteArrayContent("", dataBytes, 0, dataBytes.length));
            HttpHeaders httpMetadata = httpCall.getHeaders();
            httpMetadata.set("X-Upload-Content-Type", "");
            httpMetadata.set("x-goog-resumable", "start");
            HttpResponse result = httpCall.execute();
            if (201 != result.getStatusCode()) {
                GoogleJsonError googleJsonPayload = new GoogleJsonError();
                googleJsonPayload.setCode(result.getStatusCode());
                googleJsonPayload.setMessage(result.getStatusMessage());
                throw toStorageServiceException(googleJsonPayload);
            }
            return result.getHeaders().getLocation();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl getDefaultAcl(String container, String principal) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT_DEFAULT_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.defaultObjectAccessControls().get(container, principal).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return null;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public void write(String transferId, byte[] dataChunk, int sourceOffset, long targetOffset, int count, boolean isFinal) {
        writeWithResponse(transferId, dataChunk, sourceOffset, targetOffset, count, isFinal);
    }

    private Get getCall(StorageObject obj, Map<StorageOption, ?> clientConfig) throws IOException {
        Get fetchRequest = dataStore.objects().get(obj.getBucket(), obj.getName());
        setEncryptionHeaders(fetchRequest.getRequestHeaders(), ENCRYPTION_PREFIX, clientConfig);
        return fetchRequest.setGeneration(obj.getGeneration()).setProjection(DEFAULT_PROJECTION).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig)).setFields(StorageOption.FIELDS.getString(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
    }

    @Override
    public List<BucketAccessControl> listAcls(String container, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKET_ACLS);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.bucketAccessControls().list(container).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute().getItems();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ServiceAccount getServiceAccount(String tenantId) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_SERVICE_ACCOUNT);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.projects().serviceAccount().get(tenantId).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private RewriteResult executeRewrite(ObjectRewriteRequest getOperation, String authToken) {
        try {
            String projectOverride = StorageOption.USER_PROJECT.getString(getOperation.sourceOptions);
            if (null == projectOverride) {
                projectOverride = StorageOption.USER_PROJECT.getString(getOperation.targetOptions);
            }
            Long maxBytesPerCall = null != getOperation.megabytesRewrittenPerCall ? getOperation.megabytesRewrittenPerCall * ONE_MEGABYTE : null;
            Storage.Objects.Rewrite rewriteOperation = dataStore.objects().rewrite(getOperation.source.getBucket(), getOperation.source.getName(), getOperation.target.getBucket(), getOperation.target.getName(), getOperation.overrideInfo ? getOperation.target : null).setSourceGeneration(getOperation.source.getGeneration()).setRewriteToken(authToken).setMaxBytesRewrittenPerCall(maxBytesPerCall).setProjection(DEFAULT_PROJECTION).setIfSourceMetagenerationMatch(StorageOption.IF_SOURCE_METAGENERATION_MATCH.getLong(getOperation.sourceOptions)).setIfSourceMetagenerationNotMatch(StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH.getLong(getOperation.sourceOptions)).setIfSourceGenerationMatch(StorageOption.IF_SOURCE_GENERATION_MATCH.getLong(getOperation.sourceOptions)).setIfSourceGenerationNotMatch(StorageOption.IF_SOURCE_GENERATION_NOT_MATCH.getLong(getOperation.sourceOptions)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(getOperation.targetOptions)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(getOperation.targetOptions)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(getOperation.targetOptions)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(getOperation.targetOptions)).setDestinationPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(getOperation.targetOptions)).setUserProject(projectOverride).setDestinationKmsKeyName(StorageOption.KMS_KEY_NAME.getString(getOperation.targetOptions));
            HttpHeaders httpMetadata = rewriteOperation.getRequestHeaders();
            setEncryptionHeaders(httpMetadata, SOURCE_CRYPTO_KEY_PREFIX, getOperation.sourceOptions);
            setEncryptionHeaders(httpMetadata, ENCRYPTION_PREFIX, getOperation.targetOptions);
            com.google.api.services.storage.model.RewriteResponse responsePayload = rewriteOperation.execute();
            return new RewriteResult(getOperation, responsePayload.getResource(), responsePayload.getObjectSize().longValue(), responsePayload.getDone(), responsePayload.getRewriteToken(), responsePayload.getTotalBytesRewritten().longValue());
        } catch (IOException ioException) {
            telemetry.getCurrentSpan().setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        }
    }

    @Override
    public List<ObjectAccessControl> listDefaultAcls(String container) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECT_DEFAULT_ACLS);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.defaultObjectAccessControls().list(container).execute().getItems();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private static StorageServiceException toStorageServiceException(GoogleJsonError ioError) {
        return new StorageServiceException(ioError);
    }

    @Override
    public long getCurrentUploadOffset(String transferId) {
        try {
            GenericUrl endpoint = new GenericUrl(transferId);
            HttpRequest httpCall = dataStore.getRequestFactory().buildPutRequest(endpoint, new EmptyContent());
            httpCall.getHeaders().setContentRange("bytes */*");
            // Turn off automatic redirects.
            // HTTP 308 are returned if upload is incomplete.
            // See: https://cloud.google.com/storage/docs/performing-resumable-uploads
            httpCall.setFollowRedirects(false);
            HttpResponse result = null;
            try {
                result = httpCall.execute();
                int status = result.getStatusCode();
                if (HttpStatusCodes.isSuccess(status)) {
                    // Upload completed successfully
                    return -1;
                }
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("Not sure what occurred. Here's debugging information:\n");
                stringBuilder.append("Response:\n").append(result.toString()).append("\n\n");
                throw new StorageServiceException(0, stringBuilder.toString());
            } catch (HttpResponseException ioException) {
                int status = ioException.getStatusCode();
                if (308 != status) {
                    // Something else occurred like a 5xx so translate and throw.
                    throw toStorageServiceException(ioException);
                } else {
                    if (null == ioException.getHeaders().getRange()) {
                        // No progress has been made.
                        return 0;
                    }
                    // API returns last byte received offset
                    String headerValueBuilder = ioException.getHeaders().getRange();
                    // Return next byte offset by adding 1 to last byte received offset
                    return Long.parseLong(headerValueBuilder.substring(headerValueBuilder.indexOf("-") + 1)) + 1;
                }
            } finally {
                if (null != result) {
                    result.disconnect();
                }
            }
        } catch (IOException ioException) {
            throw toStorageServiceException(ioException);
        }
    }

    @Override
    public boolean deleteAcl(String container, String obj, Long version, String principal) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            dataStore.objectAccessControls().delete(container, obj, principal).setGeneration(version).execute();
            return true;
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return false;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Tuple<String, Iterable<StorageObject>> list(final String container, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_OBJECTS);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            Objects objectCollection = dataStore.objects().list(container).setProjection(DEFAULT_PROJECTION).setVersions(StorageOption.VERSIONS.getBoolean(clientConfig)).setDelimiter(StorageOption.DELIMITER.getString(clientConfig)).setStartOffset(StorageOption.START_OFF_SET.getString(clientConfig)).setEndOffset(StorageOption.END_OFF_SET.getString(clientConfig)).setPrefix(StorageOption.PREFIX.getString(clientConfig)).setMaxResults(StorageOption.MAX_RESULTS.getLong(clientConfig)).setPageToken(StorageOption.PAGE_TOKEN.getString(clientConfig)).setFields(StorageOption.FIELDS.getString(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
            Iterable<StorageObject> objectIterable = Iterables.concat(firstNonNull(objectCollection.getItems(), ImmutableList.<StorageObject>of()), null != objectCollection.getPrefixes() ? Lists.transform(objectCollection.getPrefixes(), storageObjectFromPrefix(container)) : ImmutableList.<StorageObject>of());
            return Tuple.of(objectCollection.getNextPageToken(), objectIterable);
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public BucketAccessControl createAcl(BucketAccessControl accessControl, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.bucketAccessControls().insert(accessControl.getBucket(), accessControl).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public HmacKeyMetadata getHmacKey(String keyId, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_HMAC_KEY);
        Scope tracingScope = telemetry.withSpan(spanContext);
        String tenantId = StorageOption.PROJECT_ID.getString(clientConfig);
        if (null == tenantId) {
            tenantId = this.clientConfig.getProjectId();
        }
        try {
            return dataStore.projects().hmacKeys().get(tenantId, keyId).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Bucket lockRetentionPolicy(Bucket container, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_LOCK_RETENTION_POLICY);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.buckets().lockRetentionPolicy(container.getName(), StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public void deleteHmacKey(HmacKeyMetadata hmacMetadata, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_HMAC_KEY);
        Scope tracingScope = telemetry.withSpan(spanContext);
        String tenantId = hmacMetadata.getProjectId();
        if (null == tenantId) {
            tenantId = this.clientConfig.getProjectId();
        }
        try {
            dataStore.projects().hmacKeys().delete(tenantId, hmacMetadata.getAccessId()).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public boolean delete(StorageObject storageEntry, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_OBJECT);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            buildDeleteCall(storageEntry, clientConfig).execute();
            return true;
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return false;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public byte[] load(StorageObject sourceObject, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LOAD);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            Get readOperation = dataStore.objects().get(sourceObject.getBucket(), sourceObject.getName()).setGeneration(sourceObject.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
            setEncryptionHeaders(readOperation.getRequestHeaders(), ENCRYPTION_PREFIX, clientConfig);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            readOperation.executeMedia().download(buffer);
            return buffer.toByteArray();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private static StorageServiceException toStorageServiceException(IOException ioError) {
        return new StorageServiceException(ioError);
    }

    private static <T> JsonBatchCallback<T> createJsonCallback(final RpcRequestBatch.ResultCallback<T> completionHandler) {
        return new JsonBatchCallback<T>() {

            @Override
            public void onSuccess(T response, HttpHeaders httpHeaders) throws IOException {
                completionHandler.handleSuccess(response);
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders) throws IOException {
                completionHandler.handleFailure(googleJsonError);
            }
        };
    }

    @Override
    public boolean deleteNotification(String container, String alertId) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_NOTIFICATION);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            dataStore.notifications().delete(container, alertId).execute();
            return true;
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return false;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Tuple<String, byte[]> read(StorageObject sourceObject, Map<StorageOption, ?> clientConfig, long offset, int length) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_READ);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            checkArgument(0 <= offset, "Position should be non-negative, is " + offset);
            Get getOperation = buildReadRequest(sourceObject, clientConfig);
            StringBuilder headerValueBuilder = new StringBuilder();
            headerValueBuilder.append("bytes=").append(offset).append("-").append(offset + length - 1);
            HttpHeaders httpMetadata = getOperation.getRequestHeaders();
            httpMetadata.setRange(headerValueBuilder.toString());
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream(length);
            getOperation.executeMedia().download(byteStream);
            String entityChecksum = getOperation.getLastResponseHeaders().getETag();
            return Tuple.of(entityChecksum, byteStream.toByteArray());
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = StorageServiceException.translateToStorageException(ioException);
            if (SC_RANGE_NOT_SATISFIABLE == storageFailure.getCode()) {
                return Tuple.of(null, new byte[0]);
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private Get buildReadRequest(StorageObject sourceObject, Map<StorageOption, ?> clientConfig) throws IOException {
        Get getOperation = dataStore.objects().get(sourceObject.getBucket(), sourceObject.getName()).setGeneration(sourceObject.getGeneration()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig));
        setEncryptionHeaders(getOperation.getRequestHeaders(), ENCRYPTION_PREFIX, clientConfig);
        getOperation.setReturnRawInputStream(true);
        return getOperation;
    }

    private static void setEncryptionHeaders(HttpHeaders httpFields, String encKeyTag, Map<StorageOption, ?> clientConfig) {
        String secretId = StorageOption.CUSTOMER_SUPPLIED_KEY.getString(clientConfig);
        if (null != secretId) {
            BaseEncoding b64Encoder = BaseEncoding.base64();
            HashFunction digestFunc = Hashing.sha256();
            httpFields.set(encKeyTag + "algorithm", "AES256");
            httpFields.set(encKeyTag + "key", secretId);
            httpFields.set(encKeyTag + "key-sha256", b64Encoder.encode(digestFunc.hashBytes(b64Encoder.decode(secretId)).asBytes()));
        }
    }

    @Override
    public Bucket create(Bucket container, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_BUCKET);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.buckets().insert(this.clientConfig.getProjectId(), container).setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig)).setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public ObjectAccessControl patchAcl(ObjectAccessControl accessControl) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_OBJECT_ACL);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.objectAccessControls().patch(accessControl.getBucket(), accessControl.getObject(), accessControl.getEntity(), accessControl).setGeneration(accessControl.getGeneration()).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public List<Notification> listNotifications(String container) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_NOTIFICATIONS);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.notifications().list(container).execute().getItems();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public String open(StorageObject obj, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_OPEN);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            String kmsKey = obj.getKmsKeyName();
            if (null != kmsKey && kmsKey.contains("cryptoKeyVersions")) {
                obj.setKmsKeyName("");
            }
            Insert getOperation = dataStore.objects().insert(obj.getBucket(), obj).setName(obj.getName()).setProjection(StorageOption.PROJECTION.getString(clientConfig)).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).setKmsKeyName(StorageOption.KMS_KEY_NAME.getString(clientConfig));
            GenericUrl endpoint = getOperation.buildHttpRequestUrl();
            endpoint.setRawPath("/upload" + endpoint.getRawPath());
            endpoint.set("uploadType", "resumable");
            JsonFactory jsonFactoryInstance = dataStore.getJsonFactory();
            HttpRequestFactory httpRequestFactory = dataStore.getRequestFactory();
            HttpRequest httpCall = httpRequestFactory.buildPostRequest(endpoint, new JsonHttpContent(jsonFactoryInstance, obj));
            HttpHeaders httpMetadata = httpCall.getHeaders();
            httpMetadata.set("X-Upload-Content-Type", resolveContentType(obj, clientConfig));
            setEncryptionHeaders(httpMetadata, "x-goog-encryption-", clientConfig);
            HttpResponse result = httpCall.execute();
            if (200 != result.getStatusCode()) {
                GoogleJsonError googleJsonPayload = new GoogleJsonError();
                googleJsonPayload.setCode(result.getStatusCode());
                googleJsonPayload.setMessage(result.getStatusMessage());
                throw toStorageServiceException(googleJsonPayload);
            }
            return result.getHeaders().getLocation();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_LIST_BUCKETS);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            Buckets listResponse = dataStore.buckets().list(this.clientConfig.getProjectId()).setProjection(DEFAULT_PROJECTION).setPrefix(StorageOption.PREFIX.getString(clientConfig)).setMaxResults(StorageOption.MAX_RESULTS.getLong(clientConfig)).setPageToken(StorageOption.PAGE_TOKEN.getString(clientConfig)).setFields(StorageOption.FIELDS.getString(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
            return Tuple.<String, Iterable<Bucket>>of(listResponse.getNextPageToken(), listResponse.getItems());
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Notification createNotification(String container, Notification alertId) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_NOTIFICATION);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return dataStore.notifications().insert(container, alertId).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacMetadata, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_UPDATE_HMAC_KEY);
        Scope tracingScope = telemetry.withSpan(spanContext);
        String tenantId = hmacMetadata.getProjectId();
        if (null == tenantId) {
            tenantId = this.clientConfig.getProjectId();
        }
        try {
            return dataStore.projects().hmacKeys().update(tenantId, hmacMetadata.getAccessId(), hmacMetadata).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public StorageObject writeWithResponse(String transferId, byte[] dataChunk, int sourceOffset, long targetOffset, int count, boolean isFinal) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_WRITE);
        Scope tracingScope = telemetry.withSpan(spanContext);
        StorageObject newStorageObject = null;
        try {
            if (0 == count && !isFinal) {
                return newStorageObject;
            }
            GenericUrl endpoint = new GenericUrl(transferId);
            HttpRequest httpCall = dataStore.getRequestFactory().buildPutRequest(endpoint, new ByteArrayContent(null, dataChunk, sourceOffset, count));
            long maxBytes = targetOffset + count;
            StringBuilder headerValueBuilder = new StringBuilder("bytes ");
            if (0 != count) {
                headerValueBuilder.append(targetOffset).append('-').append(maxBytes - 1);
            } else {
                headerValueBuilder.append('*');
            }
            headerValueBuilder.append('/');
            if (!isFinal) {
                headerValueBuilder.append('*');
            } else {
                headerValueBuilder.append(maxBytes);
            }
            httpCall.getHeaders().setContentRange(headerValueBuilder.toString());
            if (isFinal) {
                httpCall.setParser(dataStore.getObjectParser());
            }
            int status;
            String errorText;
            IOException ioError = null;
            HttpResponse result = null;
            try {
                result = httpCall.execute();
                status = result.getStatusCode();
                errorText = result.getStatusMessage();
                String mimeType = result.getContentType();
                if (isFinal && (200 == status || 201 == status) && null != mimeType && mimeType.startsWith("application/json")) {
                    newStorageObject = result.parseAs(StorageObject.class);
                }
            } catch (HttpResponseException ioException) {
                ioError = ioException;
                status = ioException.getStatusCode();
                errorText = ioException.getStatusMessage();
            } finally {
                if (null != result) {
                    result.disconnect();
                }
            }
            if (!isFinal && 308 != status || isFinal && !(200 == status || 201 == status)) {
                if (null != ioError) {
                    throw ioError;
                }
                GoogleJsonError googleJsonPayload = new GoogleJsonError();
                googleJsonPayload.setCode(status);
                googleJsonPayload.setMessage(errorText);
                throw toStorageServiceException(googleJsonPayload);
            }
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
        return newStorageObject;
    }

    @Override
    public StorageObject create(StorageObject objectMetadata, final InputStream inputStream, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CREATE_OBJECT);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            Insert uploadAction = dataStore.objects().insert(objectMetadata.getBucket(), objectMetadata, new InputStreamContent(resolveContentType(objectMetadata, clientConfig), inputStream));
            uploadAction.getMediaHttpUploader().setDirectUploadEnabled(true);
            Boolean gzipDisabled = StorageOption.IF_DISABLE_GZIP_CONTENT.getBoolean(clientConfig);
            if (null != gzipDisabled) {
                uploadAction.setDisableGZipContent(gzipDisabled);
            }
            setEncryptionHeaders(uploadAction.getRequestHeaders(), ENCRYPTION_PREFIX, clientConfig);
            return uploadAction.setProjection(DEFAULT_PROJECTION).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setIfGenerationMatch(StorageOption.IF_GENERATION_MATCH.getLong(clientConfig)).setIfGenerationNotMatch(StorageOption.IF_GENERATION_NOT_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).setKmsKeyName(StorageOption.KMS_KEY_NAME.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
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
    public StorageRpcClient.RewriteResult continueRewrite(RewriteResult previousResult) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_CONTINUE_REWRITE);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return executeRewrite(previousResult.rewriteRequest, previousResult.rewriteToken);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public StorageObject get(StorageObject obj, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_GET_OBJECT);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            return getCall(obj, clientConfig).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return null;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    @Override
    public Bucket patch(Bucket container, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_PATCH_BUCKET);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            String selectFields = StorageOption.PROJECTION.getString(clientConfig);
            if (null != container.getIamConfiguration() && null != container.getIamConfiguration().getBucketPolicyOnly() && null != container.getIamConfiguration().getBucketPolicyOnly().getEnabled() && container.getIamConfiguration().getBucketPolicyOnly().getEnabled()) {
                // If BucketPolicyOnly is enabled, patch calls will fail if ACL information is included in
                // the request
                container.setDefaultObjectAcl(null);
                container.setAcl(null);
                if (null == selectFields) {
                    selectFields = NO_ACL_PROJECTION;
                }
            }
            return dataStore.buckets().patch(container.getName(), container).setProjection(null == selectFields ? DEFAULT_PROJECTION : selectFields).setPredefinedAcl(StorageOption.PREDEFINED_ACL.getString(clientConfig)).setPredefinedDefaultObjectAcl(StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL.getString(clientConfig)).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            throw toStorageServiceException(ioException);
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    private static String resolveContentType(StorageObject obj, Map<StorageOption, ?> clientConfig) {
        String mimeType = obj.getContentType();
        if (null != mimeType) {
            return mimeType;
        }
        if (StorageOption.DETECT_CONTENT_TYPE.get(clientConfig) == Boolean.TRUE) {
            mimeType = NAME_TO_MIME_MAP.getContentTypeFor(obj.getName().toLowerCase(Locale.ENGLISH));
        }
        return firstNonNull(mimeType, "application/octet-stream");
    }

    @Override
    public boolean delete(Bucket container, Map<StorageOption, ?> clientConfig) {
        Span spanContext = createSpan(HttpStorageRpcSpans.SPAN_NAME_DELETE_BUCKET);
        Scope tracingScope = telemetry.withSpan(spanContext);
        try {
            dataStore.buckets().delete(container.getName()).setIfMetagenerationMatch(StorageOption.IF_METAGENERATION_MATCH.getLong(clientConfig)).setIfMetagenerationNotMatch(StorageOption.IF_METAGENERATION_NOT_MATCH.getLong(clientConfig)).setUserProject(StorageOption.USER_PROJECT.getString(clientConfig)).execute();
            return true;
        } catch (IOException ioException) {
            spanContext.setStatus(Status.UNKNOWN.withDescription(ioException.getMessage()));
            StorageServiceException storageFailure = toStorageServiceException(ioException);
            if (HTTP_NOT_FOUND == storageFailure.getCode()) {
                return false;
            }
            throw storageFailure;
        } finally {
            tracingScope.close();
            spanContext.end(HttpStorageRpcSpans.END_SPAN_OPTIONS);
        }
    }

    /**
     * Helper method to start a span.
     */
    private Span createSpan(String operationName) {
        return telemetry.spanBuilder(operationName).setRecordEvents(monitoringModule.isRecordEvents()).startSpan();
    }

}
