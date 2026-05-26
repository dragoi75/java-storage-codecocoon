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
package com.google.cloud.storage;

import static com.google.cloud.RetryHelper.runWithRetries;
import static com.google.cloud.storage.PolicyConverter.fromApiPolicy;
import static com.google.cloud.storage.PolicyConverter.toApiPolicy;
import static com.google.cloud.storage.SignedUrlEncoderUtil.rfc3986UriEncode;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.DELIMITER;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.IF_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.IF_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.IF_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.IF_METAGENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.IF_SOURCE_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.IF_SOURCE_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.IF_SOURCE_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpc.StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import com.google.api.gax.paging.Page;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.api.services.storage.model.StorageObject;
import com.google.api.services.storage.model.TestIamPermissionsResponse;
import com.google.auth.ServiceAccountSigner;
import com.google.cloud.BaseService;
import com.google.cloud.BatchResult;
import com.google.cloud.PageImpl;
import com.google.cloud.PageImpl.NextPageFetcher;
import com.google.cloud.Policy;
import com.google.cloud.ReadChannel;
import com.google.cloud.RetryHelper.RetryHelperException;
import com.google.cloud.Tuple;
import com.google.cloud.storage.AccessControlEntry.TypedEntity;
import com.google.cloud.storage.HmacSecretKey.HmacKeyInfo;
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import com.google.cloud.storage.spi.v1.CloudStorageRpc.RewriteOperationResponse;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Ints;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

final class StorageClientImpl extends BaseService<StorageSettings> implements StorageClient {

    private static final byte[] ZERO_LENGTH_BYTES = {};

    private static final String ZERO_LENGTH_BYTES_MD5 = "1B2M2Y8AsgTpgAmY7PhCfg==";

    private static final String ZERO_LENGTH_BYTES_CRC32C = "AAAAAA==";

    private static final String PATH_SEPARATOR = "/";

    /**
     * Signed URLs are only supported through the GCS XML API endpoint.
     */
    private static final String STORAGE_XML_SCHEME = "https";

    private static final String STORAGE_XML_HOST = "storage.googleapis.com";

    private static final Function<Tuple<StorageClient, Boolean>, Boolean> DELETE_FUNCTION = new Function<Tuple<StorageClient, Boolean>, Boolean>() {

        @Override
        public Boolean apply(Tuple<StorageClient, Boolean> tuple) {
            return tuple.y();
        }
    };

    private final CloudStorageRpc cloudStorageClient;

    private static class BucketPageRetriever implements NextPageFetcher<StorageBucket> {

        private static final long serialVersionUID = 5850406828803613729L;

        private final Map<CloudStorageRpc.StorageOption, ?> requestSettings;

        private final StorageSettings serviceSettings;

        @Override
        public Page<StorageBucket> getNextPage() {
            return listAllBuckets(serviceSettings, requestSettings);
        }

        BucketPageRetriever(StorageSettings serviceSettings, String pageToken, Map<CloudStorageRpc.StorageOption, ?> settingsMap) {
            this.requestSettings = PageImpl.nextRequestOptions(CloudStorageRpc.StorageOption.PAGE_TOKEN, pageToken, settingsMap);
            this.serviceSettings = serviceSettings;
        }

    }

    private static class BlobPageIterator implements NextPageFetcher<StorageBlob> {

        private static final long serialVersionUID = 81807334445874098L;

        private final Map<CloudStorageRpc.StorageOption, ?> requestSettings;

        private final StorageSettings serviceSettings;

        private final String containerName;

        @Override
        public Page<StorageBlob> getNextPage() {
            return listAllBlobs(containerName, serviceSettings, requestSettings);
        }

        BlobPageIterator(String containerName, StorageSettings serviceSettings, String pageToken, Map<CloudStorageRpc.StorageOption, ?> settingsMap) {
            this.requestSettings = PageImpl.nextRequestOptions(CloudStorageRpc.StorageOption.PAGE_TOKEN, pageToken, settingsMap);
            this.serviceSettings = serviceSettings;
            this.containerName = containerName;
        }

    }

    private static class HmacKeyMetadataPageRetriever implements NextPageFetcher<HmacKeyInfo> {

        private static final long serialVersionUID = 308012320541700881L;

        private final StorageSettings serviceSettings;

        private final Map<CloudStorageRpc.StorageOption, ?> settings;

        @Override
        public Page<HmacKeyInfo> getNextPage() {
            return listAllHmacKeys(serviceSettings, settings);
        }

        HmacKeyMetadataPageRetriever(StorageSettings serviceSettings, Map<CloudStorageRpc.StorageOption, ?> settings) {
            this.serviceSettings = serviceSettings;
            this.settings = settings;
        }

    }

    @Override
    public boolean deleteAcl(final BlobIdentifier objectName, final TypedEntity aclEntity) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return cloudStorageClient.deleteAcl(objectName.getBucket(), objectName.getName(), objectName.getGeneration(), aclEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public Policy setIamPolicy(final String containerName, final Policy iamSettings, BucketSourceOptions... settings) {
        try {
            final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
            return fromApiPolicy(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return cloudStorageClient.setIamPolicy(containerName, toApiPolicy(iamSettings), storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public ReadChannel reader(String containerName, String objectName, BlobSourceOptions... settings) {
        Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
        return new BlobReadStream(getOptions(), BlobIdentifier.create(containerName, objectName), storageOptionMap);
    }

    @Override
    public List<StorageBlob> get(BlobIdentifier... identifiers) {
        return get(Arrays.asList(identifiers));
    }

    @Override
    public HmacSecretKey.HmacKeyInfo updateHmacKeyState(final HmacKeyInfo keyInfo, final HmacSecretKey.HmacKeyStatus status, final HmacKeyUpdateOption... settings) {
        HmacKeyInfo updatedInfo = HmacKeyInfo.createBuilder(keyInfo.getServiceAccount()).setProjectId(keyInfo.getProjectId()).setAccessId(keyInfo.getAccessId()).setState(status).create();
        return modifyHmacKey(updatedInfo, settings);
    }

    @Override
    public boolean delete(BlobIdentifier objectName, BlobSourceOptions... settings) {
        final StorageObject persistedObject = objectName.toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(objectName, settings);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return cloudStorageClient.delete(persistedObject, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public List<Boolean> testIamPermissions(final String containerName, final List<String> permsList, BucketSourceOptions... settings) {
        try {
            final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
            TestIamPermissionsResponse iamResult = runWithRetries(new Callable<TestIamPermissionsResponse>() {

                @Override
                public TestIamPermissionsResponse call() {
                    return cloudStorageClient.testIamPermissions(containerName, permsList, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            final Set<String> retainedPerms = null != iamResult.getPermissions() ? ImmutableSet.copyOf(iamResult.getPermissions()) : ImmutableSet.<String>of();
            return Lists.transform(permsList, new Function<String, Boolean>() {

                @Override
                public Boolean apply(String permission) {
                    return retainedPerms.contains(permission);
                }
            });
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public HmacSecretKey.HmacKeyInfo getHmacKey(final String keyId, final RetrieveHmacKeyOption... settings) {
        try {
            return HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return cloudStorageClient.getHmacKey(keyId, buildOptionMap(settings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public byte[] readAllBytes(String containerName, String objectName, BlobSourceOptions... settings) {
        return readAllBytes(BlobIdentifier.create(containerName, objectName), settings);
    }

    @Override
    public AccessControlEntry getAcl(final BlobIdentifier objectName, final TypedEntity aclEntity) {
        try {
            ObjectAccessControl resultBucket = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return cloudStorageClient.getAcl(objectName.getBucket(), objectName.getName(), objectName.getGeneration(), aclEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : AccessControlEntry.fromProto(resultBucket);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public List<AccessControlEntry> listDefaultAcls(final String containerName) {
        try {
            List<ObjectAccessControl> resultBucket = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return cloudStorageClient.listDefaultAcls(containerName);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(resultBucket, AccessControlEntry.OBJECT_PB_TO_ACE_FN);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public StorageOperationBatch batch() {
        return new StorageOperationBatch(this.getOptions());
    }

    private static Map<CloudStorageRpc.StorageOption, ?> buildOptionMap(BucketMetadata bucketMetadata, RpcOptionWrapper... settings) {
        return buildOptionMap(null, bucketMetadata.getMetageneration(), settings);
    }

    private static Map<CloudStorageRpc.StorageOption, ?> buildOptionMap(Long genId, Long metaGen, Iterable<? extends RpcOptionWrapper> settings, boolean treatAsSource) {
        Map<CloudStorageRpc.StorageOption, Object> storageOptionMap = Maps.newEnumMap(CloudStorageRpc.StorageOption.class);
        for (RpcOptionWrapper signingMode : settings) {
            Object previousValue = storageOptionMap.put(signingMode.getRpcOption(), signingMode.getValue());
            checkArgument(null == previousValue, "Duplicate option %s", signingMode);
        }
        if (!Boolean.TRUE.equals(storageOptionMap.get(DELIMITER))) {
            if (storageOptionMap.get(DELIMITER) != null) {
                storageOptionMap.put(DELIMITER, storageOptionMap.get(DELIMITER));
            }
        } else {
            storageOptionMap.remove(DELIMITER);
            storageOptionMap.put(DELIMITER, PATH_SEPARATOR);
        }
        if (!treatAsSource) {
            putToOptionMap(IF_GENERATION_MATCH, genId, storageOptionMap);
            putToOptionMap(IF_GENERATION_NOT_MATCH, genId, storageOptionMap);
            putToOptionMap(IF_METAGENERATION_MATCH, metaGen, storageOptionMap);
            putToOptionMap(IF_METAGENERATION_NOT_MATCH, metaGen, storageOptionMap);
        } else {
            putToOptionMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, genId, storageOptionMap);
            putToOptionMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, genId, storageOptionMap);
            putToOptionMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGen, storageOptionMap);
            putToOptionMap(IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGen, storageOptionMap);
        }
        return ImmutableMap.copyOf(storageOptionMap);
    }

    @Override
    public boolean delete(BlobIdentifier objectName) {
        return delete(objectName, new BlobSourceOptions[0]);
    }

    @Override
    public List<StorageBlob> update(Iterable<BlobMetadata> metadataArray) {
        StorageOperationBatch operationBatch = batch();
        final List<StorageBlob> retrievedBlobs = Lists.newArrayList();
        for (BlobMetadata blobMetadata : metadataArray) {
            operationBatch.updateBlob(blobMetadata).notify(new BatchResult.Callback<StorageBlob, StorageOperationException>() {

                @Override
                public void success(StorageBlob result) {
                    retrievedBlobs.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    retrievedBlobs.add(null);
                }
            });
        }
        operationBatch.submitBatch();
        return Collections.unmodifiableList(retrievedBlobs);
    }

    @Override
    public boolean deleteAcl(final String containerName, final TypedEntity aclEntity) {
        return deleteAcl(containerName, aclEntity, new BucketSourceOptions[0]);
    }

    @Override
    public byte[] readAllBytes(BlobIdentifier objectName, BlobSourceOptions... settings) {
        final StorageObject persistedObject = objectName.toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(objectName, settings);
        try {
            return runWithRetries(new Callable<byte[]>() {

                @Override
                public byte[] call() {
                    return cloudStorageClient.load(persistedObject, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public boolean deleteAcl(final String containerName, final TypedEntity aclEntity, BucketSourceOptions... settings) {
        try {
            final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return cloudStorageClient.deleteAcl(containerName, aclEntity.toProto(), storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public AccessControlEntry createDefaultAcl(String containerName, AccessControlEntry accessEntry) {
        final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(containerName);
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return cloudStorageClient.createDefaultAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private StorageBlob createInternal(BlobMetadata blobMetadata, final byte[] dataBytes, BlobUploadOption... settings) {
        Preconditions.checkNotNull(dataBytes);
        final StorageObject objectProto = blobMetadata.toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(blobMetadata, settings);
        try {
            return StorageBlob.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return cloudStorageClient.create(objectProto, new ByteArrayInputStream(dataBytes), storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public ServiceAccountInfo getServiceAccount(final String projectIdentifier) {
        try {
            com.google.api.services.storage.model.ServiceAccount resultBucket = runWithRetries(new Callable<com.google.api.services.storage.model.ServiceAccount>() {

                @Override
                public com.google.api.services.storage.model.ServiceAccount call() {
                    return cloudStorageClient.getServiceAccount(projectIdentifier);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : ServiceAccountInfo.fromProto(resultBucket);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private HmacKeyInfo modifyHmacKey(final HmacKeyInfo keyInfo, final HmacKeyUpdateOption... settings) {
        try {
            return HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return cloudStorageClient.updateHmacKey(keyInfo.toProto(), buildOptionMap(settings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public AccessControlEntry updateAcl(String containerName, AccessControlEntry accessEntry) {
        return updateAcl(containerName, accessEntry, new BucketSourceOptions[0]);
    }

    private static Page<StorageBucket> listAllBuckets(final StorageSettings serviceSettings, final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> pageCursorAndBuckets = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> call() {
                    return serviceSettings.getStorageRpcV1().list(storageOptionMap);
                }
            }, serviceSettings.getRetrySettings(), EXCEPTION_HANDLER, serviceSettings.getClock());
            String pageToken = pageCursorAndBuckets.x();
            Iterable<StorageBucket> storageBuckets = null == pageCursorAndBuckets.y() ? ImmutableList.<StorageBucket>of() : Iterables.transform(pageCursorAndBuckets.y(), new Function<com.google.api.services.storage.model.Bucket, StorageBucket>() {

                @Override
                public StorageBucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                    return StorageBucket.fromProto(serviceSettings.getService(), bucketPb);
                }
            });
            return new PageImpl<>(new BucketPageRetriever(serviceSettings, pageToken, storageOptionMap), pageToken, storageBuckets);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private static Map<CloudStorageRpc.StorageOption, ?> buildOptionMap(Long genId, Long metaGen, RpcOptionWrapper... settings) {
        return buildOptionMap(genId, metaGen, Arrays.asList(settings));
    }

    private static Map<CloudStorageRpc.StorageOption, ?> buildOptionMap(Long genId, Long metaGen, Iterable<? extends RpcOptionWrapper> settings) {
        return buildOptionMap(genId, metaGen, settings, false);
    }

    private static Page<HmacKeyInfo> listAllHmacKeys(final StorageSettings serviceSettings, final Map<CloudStorageRpc.StorageOption, ?> settings) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> pageCursorAndBuckets = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> call() {
                    return serviceSettings.getStorageRpcV1().listHmacKeys(settings);
                }
            }, serviceSettings.getRetrySettings(), EXCEPTION_HANDLER, serviceSettings.getClock());
            String pageToken = pageCursorAndBuckets.x();
            final Iterable<HmacKeyInfo> keyInfo = null == pageCursorAndBuckets.y() ? ImmutableList.<HmacKeyInfo>of() : Iterables.transform(pageCursorAndBuckets.y(), new Function<com.google.api.services.storage.model.HmacKeyMetadata, HmacKeyInfo>() {

                @Override
                public HmacSecretKey.HmacKeyInfo apply(com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                    return HmacKeyInfo.fromProto(metadataPb);
                }
            });
            return new PageImpl<>(new HmacKeyMetadataPageRetriever(serviceSettings, settings), pageToken, keyInfo);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    /**
     * Builds signature info.
     *
     * @param settingsMap the option map
     * @param blobMetadata the blob info
     * @param expiryMillis the expiration in seconds
     * @param resourceUri the resource URI
     * @param emailAddress the account email
     * @return signature info
     */
    private SignatureMetadata buildSignatureMetadata(Map<UrlSigningOption.RequestOption, Object> settingsMap, BlobMetadata blobMetadata, long expiryMillis, URI resourceUri, String emailAddress) {
        HttpRequestMethod requestMethod = settingsMap.containsKey(UrlSigningOption.RequestOption.HTTP_METHOD) ? (HttpRequestMethod) settingsMap.get(UrlSigningOption.RequestOption.HTTP_METHOD) : HttpRequestMethod.GET;
        SignatureMetadata.CanonicalRequestBuilder canonicalBuilder = new SignatureMetadata.CanonicalRequestBuilder(requestMethod, expiryMillis, resourceUri);
        if (firstNonNull((Boolean) settingsMap.get(UrlSigningOption.RequestOption.MD5), false)) {
            checkArgument(null != blobMetadata.getMd5(), "Blob is missing a value for md5");
            canonicalBuilder.setContentMd5(blobMetadata.getMd5());
        }
        if (firstNonNull((Boolean) settingsMap.get(UrlSigningOption.RequestOption.CONTENT_TYPE), false)) {
            checkArgument(null != blobMetadata.getContentType(), "Blob is missing a value for content-type");
            canonicalBuilder.setContentType(blobMetadata.getContentType());
        }
        canonicalBuilder.setSignatureVersion((UrlSigningOption.SignatureProtocolVersion) settingsMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
        canonicalBuilder.setAccountEmail(emailAddress);
        canonicalBuilder.setTimestamp(getOptions().getClock().millisTime());
        ImmutableMap.Builder<String, String> extraHeadersBuilder = new ImmutableMap.Builder<String, String>();
        boolean useV4 = UrlSigningOption.SignatureProtocolVersion.V4.equals(settingsMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
        if (useV4) {
            // We don't sign the host header for V2 signed URLs; only do this for V4.
            // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
            if (!settingsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
                if (settingsMap.containsKey(UrlSigningOption.RequestOption.HOST_NAME) || settingsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
                    extraHeadersBuilder.put("host", getBaseStorageHostName(settingsMap));
                }
            } else {
                extraHeadersBuilder.put("host", bucketNameWithoutSlashes(blobMetadata) + "." + getBaseStorageHostName(settingsMap));
            }
        }
        if (settingsMap.containsKey(UrlSigningOption.RequestOption.EXT_HEADERS)) {
            extraHeadersBuilder.putAll((Map<String, String>) settingsMap.get(UrlSigningOption.RequestOption.EXT_HEADERS));
        }
        ImmutableMap.Builder<String, String> paramsBuilder = new ImmutableMap.Builder<String, String>();
        if (settingsMap.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
            paramsBuilder.putAll((Map<String, String>) settingsMap.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
        }
        return canonicalBuilder.setCanonicalizedExtensionHeaders((Map<String, String>) extraHeadersBuilder.build()).setCanonicalizedQueryParams((Map<String, String>) paramsBuilder.build()).buildCanonicalRequest();
    }

    public HmacSecretKey createHmacKey(final ServiceAccountInfo accountInfo, final CreateHmacKeyRequestOption... settings) {
        try {
            return HmacSecretKey.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKey>() {

                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                    return cloudStorageClient.createHmacKey(accountInfo.getEmail(), buildOptionMap(settings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private UrlSigningOption.SignatureProtocolVersion getPreferredSignatureVersion(EnumMap<UrlSigningOption.RequestOption, Object> settingsMap) {
        // Check for an explicitly specified version in the map.
        for (UrlSigningOption.SignatureProtocolVersion sigProtocol : UrlSigningOption.SignatureProtocolVersion.values()) {
            if (sigProtocol.equals(settingsMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION))) {
                return sigProtocol;
            }
        }
        // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
        // Change this to V4 once we make it the default.
        return UrlSigningOption.SignatureProtocolVersion.V2;
    }

    @Override
    public StorageBucket lockRetentionPolicy(BucketMetadata bucketMetadata, BucketTargetRequestOption... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(bucketMetadata, settings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return cloudStorageClient.lockRetentionPolicy(bucketProto, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    StorageClientImpl(StorageSettings settings) {
        super(settings);
        cloudStorageClient = settings.getStorageRpcV1();
    }

    private static <T> void putToOptionMap(CloudStorageRpc.StorageOption requestedOption, CloudStorageRpc.StorageOption targetOption, T fallbackValue, Map<CloudStorageRpc.StorageOption, Object> optionRegistry) {
        if (optionRegistry.containsKey(requestedOption)) {
            @SuppressWarnings("unchecked")
            T resolvedVal = (T) optionRegistry.remove(requestedOption);
            checkArgument(null != resolvedVal || null != fallbackValue, "Option " + requestedOption.getValue() + " is missing a value");
            resolvedVal = firstNonNull(resolvedVal, fallbackValue);
            optionRegistry.put(targetOption, resolvedVal);
        }
    }

    @Override
    public List<Boolean> delete(Iterable<BlobIdentifier> identifiers) {
        StorageOperationBatch operationBatch = batch();
        final List<Boolean> retrievedBlobs = Lists.newArrayList();
        for (BlobIdentifier objectName : identifiers) {
            operationBatch.deleteBlob(objectName).notify(new BatchResult.Callback<Boolean, StorageOperationException>() {

                @Override
                public void success(Boolean result) {
                    retrievedBlobs.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    retrievedBlobs.add(Boolean.FALSE);
                }
            });
        }
        operationBatch.submitBatch();
        return Collections.unmodifiableList(retrievedBlobs);
    }

    @Override
    public List<AccessControlEntry> listAcls(final String containerName) {
        return listAcls(containerName, new BucketSourceOptions[0]);
    }

    @Override
    public Page<HmacKeyInfo> listHmacKeys(ListHmacKeysOptions... settings) {
        return listAllHmacKeys(getOptions(), buildOptionMap(settings));
    }

    @Override
    public Policy getIamPolicy(final String containerName, BucketSourceOptions... settings) {
        try {
            final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
            return fromApiPolicy(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return cloudStorageClient.getIamPolicy(containerName, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public List<StorageBlob> get(Iterable<BlobIdentifier> identifiers) {
        StorageOperationBatch operationBatch = batch();
        final List<StorageBlob> retrievedBlobs = Lists.newArrayList();
        for (BlobIdentifier objectName : identifiers) {
            operationBatch.get(objectName).notify(new BatchResult.Callback<StorageBlob, StorageOperationException>() {

                @Override
                public void success(StorageBlob result) {
                    retrievedBlobs.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    retrievedBlobs.add(null);
                }
            });
        }
        operationBatch.submitBatch();
        return Collections.unmodifiableList(retrievedBlobs);
    }

    static Map<CloudStorageRpc.StorageOption, ?> buildOptionMap(BlobMetadata blobMetadata, RpcOptionWrapper... settings) {
        return buildOptionMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), settings);
    }

    @Override
    public StorageBucket create(BucketMetadata bucketMetadata, BucketTargetRequestOption... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(bucketMetadata, settings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return cloudStorageClient.create(bucketProto, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public BlobUploadChannel writer(URL signedUrl) {
        return new BlobUploadChannel(getOptions(), signedUrl);
    }

    private static Map<CloudStorageRpc.StorageOption, ?> buildOptionMap(RpcOptionWrapper... settings) {
        return buildOptionMap(null, null, Arrays.asList(settings));
    }

    @Override
    public List<StorageBlob> update(BlobMetadata... metadataArray) {
        return update(Arrays.asList(metadataArray));
    }

    @Override
    public AccessControlEntry getAcl(final String containerName, final TypedEntity aclEntity) {
        return getAcl(containerName, aclEntity, new BucketSourceOptions[0]);
    }

    @Override
    public AccessControlEntry createAcl(final BlobIdentifier objectName, final AccessControlEntry accessEntry) {
        final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(objectName.getBucket()).setObject(objectName.getName()).setGeneration(objectName.getGeneration());
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return cloudStorageClient.createAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public StorageBlob get(BlobIdentifier objectName) {
        return get(objectName, new BlobGetOptions[0]);
    }

    @Override
    public StorageBlob create(BlobMetadata blobMetadata, BlobUploadOption... settings) {
        BlobMetadata updatedMetadata = blobMetadata.toBlobInfoBuilder().setMd5(ZERO_LENGTH_BYTES_MD5).setCrc32c(ZERO_LENGTH_BYTES_CRC32C).buildStorageObject();
        return createInternal(updatedMetadata, ZERO_LENGTH_BYTES, settings);
    }

    @Override
    public BlobCopyWriter copy(final CopyOperationRequest copyOperationSpec) {
        final StorageObject originObject = copyOperationSpec.getSource().toProto();
        final Map<CloudStorageRpc.StorageOption, ?> originOptions = buildOptionMap(copyOperationSpec.getSource().getGeneration(), null, copyOperationSpec.getSourceOptions(), true);
        final StorageObject destinationObj = copyOperationSpec.getTarget().toProto();
        final Map<CloudStorageRpc.StorageOption, ?> targetPair = buildOptionMap(copyOperationSpec.getTarget().getGeneration(), copyOperationSpec.getTarget().getMetageneration(), copyOperationSpec.getTargetOptions());
        try {
            RewriteOperationResponse rewriteResult = runWithRetries(new Callable<RewriteOperationResponse>() {

                @Override
                public CloudStorageRpc.RewriteOperationResponse call() {
                    return cloudStorageClient.openRewrite(new CloudStorageRpc.RewriteOperationRequest(originObject, originOptions, copyOperationSpec.getOverrideInfo(), destinationObj, targetPair, copyOperationSpec.getMegabytesCopiedPerChunk()));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return new BlobCopyWriter(getOptions(), rewriteResult);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public StorageBlob compose(final ComposeBlobsRequest mergeBlobsSpec) {
        final List<StorageObject> sourceList = Lists.newArrayListWithCapacity(mergeBlobsSpec.getSourceBlobs().size());
        for (ComposeBlobsRequest.StorageBlob inputBlob : mergeBlobsSpec.getSourceBlobs()) {
            sourceList.add(BlobMetadata.newBuilder(BlobIdentifier.create(mergeBlobsSpec.getTarget().getBucket(), inputBlob.getName(), inputBlob.getGeneration())).buildStorageObject().toProto());
        }
        final StorageObject destinationObject = mergeBlobsSpec.getTarget().toProto();
        final Map<CloudStorageRpc.StorageOption, ?> targetPair = buildOptionMap(mergeBlobsSpec.getTarget().getGeneration(), mergeBlobsSpec.getTarget().getMetageneration(), mergeBlobsSpec.getTargetOptions());
        try {
            return StorageBlob.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return cloudStorageClient.compose(sourceList, destinationObject, targetPair);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public Page<StorageBucket> list(BucketListOptions... settings) {
        return listAllBuckets(getOptions(), buildOptionMap(settings));
    }

    @Override
    public StorageBlob update(BlobMetadata blobMetadata) {
        return update(blobMetadata, new BlobUploadOption[0]);
    }

    @Override
    public ReadChannel reader(BlobIdentifier objectName, BlobSourceOptions... settings) {
        Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(objectName, settings);
        return new BlobReadStream(getOptions(), objectName, storageOptionMap);
    }

    private String bucketNameWithoutSlashes(BlobMetadata blobMetadata) {
        // The bucket name itself should never contain a forward slash. However, parts already existed
        // in the code to check for this, so we remove the forward slashes to be safe here.
        return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
    }

    @Override
    public BlobUploadChannel writer(BlobMetadata blobMetadata, BlobWriteSetting... settings) {
        Tuple<BlobMetadata, BlobUploadOption[]> targetPair = BlobUploadOption.convertOptions(blobMetadata, settings);
        return createWriter(targetPair.x(), targetPair.y());
    }

    @Override
    public StorageBlob update(BlobMetadata blobMetadata, BlobUploadOption... settings) {
        final StorageObject persistedObject = blobMetadata.toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(blobMetadata, settings);
        try {
            return StorageBlob.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return cloudStorageClient.patch(persistedObject, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public StorageBlob create(BlobMetadata blobMetadata, byte[] dataBytes, BlobUploadOption... settings) {
        dataBytes = firstNonNull(dataBytes, ZERO_LENGTH_BYTES);
        BlobMetadata updatedMetadata = blobMetadata.toBlobInfoBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(dataBytes).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(dataBytes).asInt()))).buildStorageObject();
        return createInternal(updatedMetadata, dataBytes, settings);
    }

    @Override
    public StorageBucket update(BucketMetadata bucketMetadata, BucketTargetRequestOption... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(bucketMetadata, settings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return cloudStorageClient.patch(bucketProto, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public AccessControlEntry getAcl(final String containerName, final TypedEntity aclEntity, BucketSourceOptions... settings) {
        try {
            final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
            BucketAccessControl resultBucket = runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return cloudStorageClient.getAcl(containerName, aclEntity.toProto(), storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : AccessControlEntry.fromProto(resultBucket);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public AccessControlEntry updateAcl(String containerName, AccessControlEntry accessEntry, BucketSourceOptions... settings) {
        final BucketAccessControl accessControlProto = accessEntry.toBucketProto().setBucket(containerName);
        try {
            final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
            return AccessControlEntry.fromProto(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return cloudStorageClient.patchAcl(accessControlProto, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public boolean delete(String containerName, String objectName, BlobSourceOptions... settings) {
        return delete(BlobIdentifier.create(containerName, objectName), settings);
    }

    static Map<CloudStorageRpc.StorageOption, ?> buildOptionMap(BlobIdentifier blobIdentifier, RpcOptionWrapper... settings) {
        return buildOptionMap(blobIdentifier.getGeneration(), null, settings);
    }

    @Override
    public void deleteHmacKey(final HmacKeyInfo keyInfo, final HmacKeyDeletionOption... settings) {
        try {
            runWithRetries(new Callable<Void>() {

                @Override
                public Void call() {
                    cloudStorageClient.deleteHmacKey(keyInfo.toProto(), buildOptionMap(settings));
                    return null;
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public URL signUrl(BlobMetadata blobMetadata, long timeout, TimeUnit timeUnit, UrlSigningOption... settings) {
        EnumMap<UrlSigningOption.RequestOption, Object> settingsMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
        for (UrlSigningOption signingMode : settings) {
            settingsMap.put(signingMode.getOption(), signingMode.getValue());
        }
        boolean useV2 = getPreferredSignatureVersion(settingsMap).equals(UrlSigningOption.SignatureProtocolVersion.V2);
        boolean useV4 = getPreferredSignatureVersion(settingsMap).equals(UrlSigningOption.SignatureProtocolVersion.V4);
        ServiceAccountSigner serviceAccountSigner = (ServiceAccountSigner) settingsMap.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
        if (null == serviceAccountSigner) {
            checkState(this.getOptions().getCredentials() instanceof ServiceAccountSigner, "Signing key was not provided and could not be derived");
            serviceAccountSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
        }
        long expiryMillis = useV4 ? TimeUnit.SECONDS.convert(timeUnit.toMillis(timeout), TimeUnit.MILLISECONDS) : TimeUnit.SECONDS.convert(getOptions().getClock().millisTime() + timeUnit.toMillis(timeout), TimeUnit.MILLISECONDS);
        checkArgument(!(settingsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && settingsMap.containsKey(UrlSigningOption.RequestOption.PATH_STYLE) && settingsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)), "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be" + " specified.");
        String bucketId = bucketNameWithoutSlashes(blobMetadata);
        String encodedObjectName = "";
        if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
            encodedObjectName = rfc3986UriEncode(blobMetadata.getName(), false);
        }
        boolean pathStyleEnabled = usePathStyleForSignedUrl(settingsMap);
        String xmlHostName = pathStyleEnabled ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(settingsMap) : STORAGE_XML_SCHEME + "://" + bucketId + "." + getBaseStorageHostName(settingsMap);
        if (settingsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
            xmlHostName = (String) settingsMap.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
        }
        String signedPath = pathStyleEnabled ? buildResourceUriPath(bucketId, encodedObjectName, settingsMap) : buildResourceUriPath("", encodedObjectName, settingsMap);
        URI resourceUri = URI.create(signedPath);
        // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
        // canonical resource string that we'll sign to include the bucket.
        URI signingUri = useV2 ? URI.create(buildResourceUriPath(bucketId, encodedObjectName, settingsMap)) : resourceUri;
        try {
            SignatureMetadata signatureMetadata = buildSignatureMetadata(settingsMap, blobMetadata, expiryMillis, signingUri, serviceAccountSigner.getAccount());
            String payloadToSign = signatureMetadata.buildUnsignedPayload();
            byte[] signedBytes = serviceAccountSigner.sign(payloadToSign.getBytes(UTF_8));
            StringBuilder pathBuilder = new StringBuilder();
            pathBuilder.append(xmlHostName).append(resourceUri);
            if (!useV4) {
                BaseEncoding base64Codec = BaseEncoding.base64();
                String sigBase64 = URLEncoder.encode(base64Codec.encode(signedBytes), UTF_8.name());
                String v2QueryParams = signatureMetadata.buildV2QueryString();
                pathBuilder.append('?');
                if (!Strings.isNullOrEmpty(v2QueryParams)) {
                    pathBuilder.append(v2QueryParams).append('&');
                }
                pathBuilder.append("GoogleAccessId=").append(serviceAccountSigner.getAccount());
                pathBuilder.append("&Expires=").append(expiryMillis);
                pathBuilder.append("&Signature=").append(sigBase64);
            } else {
                BaseEncoding base64Codec = BaseEncoding.base16().lowerCase();
                String sigBase64 = URLEncoder.encode(base64Codec.encode(signedBytes), UTF_8.name());
                String v4QueryParams = signatureMetadata.buildV4QueryString();
                pathBuilder.append('?');
                if (!Strings.isNullOrEmpty(v4QueryParams)) {
                    pathBuilder.append(v4QueryParams).append('&');
                }
                pathBuilder.append("X-Goog-Signature=").append(sigBase64);
            }
            return new URL(pathBuilder.toString());
        } catch (MalformedURLException | UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public AccessControlEntry updateDefaultAcl(String containerName, AccessControlEntry accessEntry) {
        final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(containerName);
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return cloudStorageClient.patchDefaultAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public AccessControlEntry updateAcl(BlobIdentifier objectName, AccessControlEntry accessEntry) {
        final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(objectName.getBucket()).setObject(objectName.getName()).setGeneration(objectName.getGeneration());
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return cloudStorageClient.patchAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    /**
     * Returns the hostname used to send requests to Cloud Storage, e.g. "storage.googleapis.com".
     */
    private String getBaseStorageHostName(Map<UrlSigningOption.RequestOption, Object> settingsMap) {
        String explicitBaseHost = (String) settingsMap.get(UrlSigningOption.RequestOption.HOST_NAME);
        String bucketScopedHost = (String) settingsMap.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
        if (!Strings.isNullOrEmpty(explicitBaseHost)) {
            return explicitBaseHost.replaceFirst("http(s)?://", "");
        }
        if (!Strings.isNullOrEmpty(bucketScopedHost)) {
            return bucketScopedHost.replaceFirst("http(s)?://", "");
        }
        return STORAGE_XML_HOST;
    }

    private static <T> void putToOptionMap(CloudStorageRpc.StorageOption signingMode, T fallbackValue, Map<CloudStorageRpc.StorageOption, Object> optionRegistry) {
        putToOptionMap(signingMode, signingMode, fallbackValue, optionRegistry);
    }

    @Override
    public Page<StorageBlob> list(final String containerName, BlobListOptions... settings) {
        return listAllBlobs(containerName, getOptions(), buildOptionMap(settings));
    }

    @Override
    public AccessControlEntry getDefaultAcl(final String containerName, final TypedEntity aclEntity) {
        try {
            ObjectAccessControl resultBucket = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return cloudStorageClient.getDefaultAcl(containerName, aclEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : AccessControlEntry.fromProto(resultBucket);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public AccessControlEntry createAcl(String containerName, AccessControlEntry accessEntry) {
        return createAcl(containerName, accessEntry, new BucketSourceOptions[0]);
    }

    private String buildResourceUriPath(String normalizedBucket, String encodedObjectName, EnumMap<UrlSigningOption.RequestOption, Object> settingsMap) {
        if (Strings.isNullOrEmpty(normalizedBucket)) {
            if (Strings.isNullOrEmpty(encodedObjectName)) {
                return PATH_SEPARATOR;
            }
            if (encodedObjectName.startsWith(PATH_SEPARATOR)) {
                return encodedObjectName;
            }
            return PATH_SEPARATOR + encodedObjectName;
        }
        StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(PATH_SEPARATOR).append(normalizedBucket);
        if (Strings.isNullOrEmpty(encodedObjectName)) {
            boolean useV2 = getPreferredSignatureVersion(settingsMap).equals(UrlSigningOption.SignatureProtocolVersion.V2);
            // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
            // must end with a forward slash.
            if (settingsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && useV2) {
                uriBuilder.append(PATH_SEPARATOR);
            }
            return uriBuilder.toString();
        }
        uriBuilder.append(PATH_SEPARATOR);
        uriBuilder.append(encodedObjectName);
        return uriBuilder.toString();
    }

    @Override
    public StorageBucket get(String containerName, BucketGetOptions... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = BucketMetadata.ofName(containerName).toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
        try {
            com.google.api.services.storage.model.Bucket resultBucket = runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return cloudStorageClient.get(bucketProto, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : StorageBucket.fromProto(this, resultBucket);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public StorageBlob create(BlobMetadata blobMetadata, byte[] dataBytes, int startOffset, int chunkLength, BlobUploadOption... settings) {
        dataBytes = firstNonNull(dataBytes, ZERO_LENGTH_BYTES);
        byte[] subArray = Arrays.copyOfRange(dataBytes, startOffset, startOffset + chunkLength);
        BlobMetadata updatedMetadata = blobMetadata.toBlobInfoBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(subArray).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(subArray).asInt()))).buildStorageObject();
        return createInternal(updatedMetadata, subArray, settings);
    }

    @Override
    public StorageBlob get(BlobIdentifier objectName, BlobGetOptions... settings) {
        final StorageObject persistedObject = objectName.toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(objectName, settings);
        try {
            StorageObject objectRecord = runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return cloudStorageClient.get(persistedObject, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == objectRecord ? null : StorageBlob.fromProto(this, objectRecord);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public AccessControlEntry createAcl(String containerName, AccessControlEntry accessEntry, BucketSourceOptions... settings) {
        final BucketAccessControl accessControlProto = accessEntry.toBucketProto().setBucket(containerName);
        try {
            final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
            return AccessControlEntry.fromProto(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return cloudStorageClient.createAcl(accessControlProto, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private boolean usePathStyleForSignedUrl(EnumMap<UrlSigningOption.RequestOption, Object> settingsMap) {
        // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
        // logic to return false unless PATH_STYLE was explicitly specified.
        if (settingsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) || settingsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
            return false;
        }
        return true;
    }

    @Override
    @Deprecated
    public StorageBlob create(BlobMetadata blobMetadata, InputStream dataBytes, BlobWriteSetting... settings) {
        Tuple<BlobMetadata, BlobUploadOption[]> targetPair = BlobUploadOption.convertOptions(blobMetadata, settings);
        StorageObject objectProto = targetPair.x().toProto();
        Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(targetPair.x(), targetPair.y());
        InputStream inputStreamRef = firstNonNull(dataBytes, new ByteArrayInputStream(ZERO_LENGTH_BYTES));
        // retries are not safe when the input is an InputStream, so we can't retry.
        return StorageBlob.fromProto(this, cloudStorageClient.create(objectProto, inputStreamRef, storageOptionMap));
    }

    @Override
    public boolean delete(String containerName, BucketSourceOptions... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = BucketMetadata.ofName(containerName).toProto();
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return cloudStorageClient.delete(bucketProto, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private BlobUploadChannel createWriter(BlobMetadata blobMetadata, BlobUploadOption... settings) {
        final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(blobMetadata, settings);
        return new BlobUploadChannel(getOptions(), blobMetadata, storageOptionMap);
    }

    @Override
    public List<AccessControlEntry> listAcls(final BlobIdentifier objectName) {
        try {
            List<ObjectAccessControl> resultBucket = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return cloudStorageClient.listAcls(objectName.getBucket(), objectName.getName(), objectName.getGeneration());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(resultBucket, AccessControlEntry.OBJECT_PB_TO_ACE_FN);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private static Page<StorageBlob> listAllBlobs(final String containerName, final StorageSettings serviceSettings, final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap) {
        try {
            Tuple<String, Iterable<StorageObject>> pageCursorAndBuckets = runWithRetries(new Callable<Tuple<String, Iterable<StorageObject>>>() {

                @Override
                public Tuple<String, Iterable<StorageObject>> call() {
                    return serviceSettings.getStorageRpcV1().list(containerName, storageOptionMap);
                }
            }, serviceSettings.getRetrySettings(), EXCEPTION_HANDLER, serviceSettings.getClock());
            String pageToken = pageCursorAndBuckets.x();
            Iterable<StorageBlob> storageBlobs = null == pageCursorAndBuckets.y() ? ImmutableList.<StorageBlob>of() : Iterables.transform(pageCursorAndBuckets.y(), new Function<StorageObject, StorageBlob>() {

                @Override
                public StorageBlob apply(StorageObject storageObject) {
                    return StorageBlob.fromProto(serviceSettings.getService(), storageObject);
                }
            });
            return new PageImpl<>(new BlobPageIterator(containerName, serviceSettings, pageToken, storageOptionMap), pageToken, storageBlobs);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public List<AccessControlEntry> listAcls(final String containerName, BucketSourceOptions... settings) {
        try {
            final Map<CloudStorageRpc.StorageOption, ?> storageOptionMap = buildOptionMap(settings);
            List<BucketAccessControl> resultBucket = runWithRetries(new Callable<List<BucketAccessControl>>() {

                @Override
                public List<BucketAccessControl> call() {
                    return cloudStorageClient.listAcls(containerName, storageOptionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(resultBucket, AccessControlEntry.BUCKET_PB_TO_ACE_FN);
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    @Override
    public List<Boolean> delete(BlobIdentifier... identifiers) {
        return delete(Arrays.asList(identifiers));
    }

    @Override
    public StorageBlob get(String containerName, String objectName, BlobGetOptions... settings) {
        return get(BlobIdentifier.create(containerName, objectName), settings);
    }

    @Override
    public boolean deleteDefaultAcl(final String containerName, final TypedEntity aclEntity) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return cloudStorageClient.deleteDefaultAcl(containerName, aclEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

}
