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
import static com.google.cloud.storage.PolicyConverter.convertPolicyFromApi;
import static com.google.cloud.storage.PolicyConverter.convertPolicyToApi;
import static com.google.cloud.storage.SignedUrlEncoder.rfc3986UriEncode;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.DELIMITER;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.IF_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.IF_SOURCE_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.IF_SOURCE_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.IF_SOURCE_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpcClient.StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH;
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
import com.google.cloud.storage.AclEntry.AbstractEntity;
import com.google.cloud.storage.HmacSecretKey.HmacKeyInfo;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteOperationResponse;
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

final class StorageServiceImpl extends BaseService<StorageSettings> implements StorageService {

    private static final byte[] ZERO_LENGTH_BYTE_ARRAY = {};

    private static final String EMPTY_BYTES_MD5_HASH = "1B2M2Y8AsgTpgAmY7PhCfg==";

    private static final String EMPTY_BYTES_CRC32C = "AAAAAA==";

    private static final String PATH_SEPARATOR = "/";

    /**
     * Signed URLs are only supported through the GCS XML API endpoint.
     */
    private static final String STORAGE_XML_SCHEME = "https";

    private static final String STORAGE_XML_HOST = "storage.googleapis.com";

    private static final Function<Tuple<StorageService, Boolean>, Boolean> DELETE_FUNCTION = new Function<Tuple<StorageService, Boolean>, Boolean>() {

        @Override
        public Boolean apply(Tuple<StorageService, Boolean> tuple) {
            return tuple.y();
        }
    };

    private final StorageRpcClient rpcClient;

    private static class BucketPageRetriever implements NextPageFetcher<StorageBucket> {

        private static final long serialVersionUID = 5850406828803613729L;

        private final Map<StorageRpcClient.StorageOption, ?> pageRequestOptions;

        private final StorageSettings storageSettings;

        @Override
        public Page<StorageBucket> getNextPage() {
            return listAllBuckets(storageSettings, pageRequestOptions);
        }

        BucketPageRetriever(StorageSettings storageSettings, String pageCursor, Map<StorageRpcClient.StorageOption, ?> storageOptionMap) {
            this.pageRequestOptions = PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, pageCursor, storageOptionMap);
            this.storageSettings = storageSettings;
        }

    }

    private static class BlobPageIterator implements NextPageFetcher<CloudStorageObject> {

        private static final long serialVersionUID = 81807334445874098L;

        private final Map<StorageRpcClient.StorageOption, ?> pageRequestOptions;

        private final StorageSettings storageSettings;

        private final String containerName;

        @Override
        public Page<CloudStorageObject> getNextPage() {
            return listAllBlobs(containerName, storageSettings, pageRequestOptions);
        }

        BlobPageIterator(String containerName, StorageSettings storageSettings, String pageCursor, Map<StorageRpcClient.StorageOption, ?> storageOptionMap) {
            this.pageRequestOptions = PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, pageCursor, storageOptionMap);
            this.storageSettings = storageSettings;
            this.containerName = containerName;
        }

    }

    private static class HmacKeyMetadataPageRetriever implements NextPageFetcher<HmacSecretKey.HmacKeyInfo> {

        private static final long serialVersionUID = 308012320541700881L;

        private final StorageSettings storageSettings;

        private final Map<StorageRpcClient.StorageOption, ?> settings;

        @Override
        public Page<HmacKeyInfo> getNextPage() {
            return listAllHmacKeys(storageSettings, settings);
        }

        HmacKeyMetadataPageRetriever(StorageSettings storageSettings, Map<StorageRpcClient.StorageOption, ?> settings) {
            this.storageSettings = storageSettings;
            this.settings = settings;
        }

    }

    @Override
    public ChunkedBlobWriter writer(BlobMetadata blobMetadata, BlobWriteOptions... settings) {
        Tuple<BlobMetadata, BlobUploadOption[]> targetPair = BlobUploadOption.toTargetOptions(blobMetadata, settings);
        return newBlobWriter(targetPair.x(), targetPair.y());
    }

    public HmacSecretKey createHmacKey(final ServiceAccountInfo accountInfo, final HmacKeyCreationOption... settings) {
        try {
            return HmacSecretKey.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKey>() {

                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                    return rpcClient.createHmacKey(accountInfo.getEmail(), buildOptionMap(settings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public CloudStorageObject update(BlobMetadata blobMetadata, BlobUploadOption... settings) {
        final StorageObject storageObj = blobMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(blobMetadata, settings);
        try {
            return CloudStorageObject.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return rpcClient.patch(storageObj, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public Policy setIamPolicy(final String containerName, final Policy accessControl, BucketRequestOption... settings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
            return convertPolicyFromApi(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return rpcClient.setIamPolicy(containerName, convertPolicyToApi(accessControl), optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public void deleteHmacKey(final HmacKeyInfo keyInfo, final HmacKeyDeleteOption... settings) {
        try {
            runWithRetries(new Callable<Void>() {

                @Override
                public Void call() {
                    rpcClient.deleteHmacKey(keyInfo.toProto(), buildOptionMap(settings));
                    return null;
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public List<AclEntry> listAcls(final String containerName, BucketRequestOption... settings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
            List<BucketAccessControl> bucketResult = runWithRetries(new Callable<List<BucketAccessControl>>() {

                @Override
                public List<BucketAccessControl> call() {
                    return rpcClient.listAcls(containerName, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(bucketResult, AclEntry.FROM_BUCKET_PROTO_FUNCTION);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(Long gen, Long metaGen, AbstractOption... settings) {
        return buildOptionMap(gen, metaGen, Arrays.asList(settings));
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(Long gen, Long metaGen, Iterable<? extends AbstractOption> settings, boolean treatAsSource) {
        Map<StorageRpcClient.StorageOption, Object> temporaryMap = Maps.newEnumMap(StorageRpcClient.StorageOption.class);
        for (AbstractOption signingMode : settings) {
            Object previousValue = temporaryMap.put(signingMode.getRpcOption(), signingMode.getValue());
            checkArgument(null == previousValue, "Duplicate option %s", signingMode);
        }
        if (!Boolean.TRUE.equals(temporaryMap.get(DELIMITER))) {
            if (temporaryMap.get(DELIMITER) != null) {
                temporaryMap.put(DELIMITER, temporaryMap.get(DELIMITER));
            }
        } else {
            temporaryMap.remove(DELIMITER);
            temporaryMap.put(DELIMITER, PATH_SEPARATOR);
        }
        if (!treatAsSource) {
            putInOptionMap(IF_GENERATION_MATCH, gen, temporaryMap);
            putInOptionMap(IF_GENERATION_NOT_MATCH, gen, temporaryMap);
            putInOptionMap(IF_METAGENERATION_MATCH, metaGen, temporaryMap);
            putInOptionMap(IF_METAGENERATION_NOT_MATCH, metaGen, temporaryMap);
        } else {
            putInOptionMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, gen, temporaryMap);
            putInOptionMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, gen, temporaryMap);
            putInOptionMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGen, temporaryMap);
            putInOptionMap(IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGen, temporaryMap);
        }
        return ImmutableMap.copyOf(temporaryMap);
    }

    private static <T> void putInOptionMap(StorageRpcClient.StorageOption signingMode, T fallbackValue, Map<StorageRpcClient.StorageOption, Object> optionStorage) {
        putInOptionMap(signingMode, signingMode, fallbackValue, optionStorage);
    }

    @Override
    public ReadChannel reader(BlobIdentifier blobName, BlobReadOption... settings) {
        Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(blobName, settings);
        return new BlobInputChannel(getOptions(), blobName, optionToValueMap);
    }

    @Override
    public CloudStorageObject get(BlobIdentifier blobName, BlobGetOptions... settings) {
        final StorageObject storageObjectProto = blobName.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(blobName, settings);
        try {
            StorageObject storageObj = runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return rpcClient.get(storageObjectProto, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == storageObj ? null : CloudStorageObject.fromProto(this, storageObj);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    private static Page<StorageBucket> listAllBuckets(final StorageSettings storageSettings, final Map<StorageRpcClient.StorageOption, ?> optionToValueMap) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> listResult = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> call() {
                    return storageSettings.getStorageRpcV1().list(optionToValueMap);
                }
            }, storageSettings.getRetrySettings(), EXCEPTION_HANDLER, storageSettings.getClock());
            String pageCursor = listResult.x();
            Iterable<StorageBucket> storageBuckets = null == listResult.y() ? ImmutableList.<StorageBucket>of() : Iterables.transform(listResult.y(), new Function<com.google.api.services.storage.model.Bucket, StorageBucket>() {

                @Override
                public StorageBucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                    return StorageBucket.fromProto(storageSettings.getService(), bucketPb);
                }
            });
            return new PageImpl<>(new BucketPageRetriever(storageSettings, pageCursor, optionToValueMap), pageCursor, storageBuckets);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    private static Page<CloudStorageObject> listAllBlobs(final String containerName, final StorageSettings storageSettings, final Map<StorageRpcClient.StorageOption, ?> optionToValueMap) {
        try {
            Tuple<String, Iterable<StorageObject>> listResult = runWithRetries(new Callable<Tuple<String, Iterable<StorageObject>>>() {

                @Override
                public Tuple<String, Iterable<StorageObject>> call() {
                    return storageSettings.getStorageRpcV1().list(containerName, optionToValueMap);
                }
            }, storageSettings.getRetrySettings(), EXCEPTION_HANDLER, storageSettings.getClock());
            String pageCursor = listResult.x();
            Iterable<CloudStorageObject> cloudObjects = null == listResult.y() ? ImmutableList.<CloudStorageObject>of() : Iterables.transform(listResult.y(), new Function<StorageObject, CloudStorageObject>() {

                @Override
                public CloudStorageObject apply(StorageObject storageObject) {
                    return CloudStorageObject.fromProto(storageSettings.getService(), storageObject);
                }
            });
            return new PageImpl<>(new BlobPageIterator(containerName, storageSettings, pageCursor, optionToValueMap), pageCursor, cloudObjects);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BlobIdentifier blobIdentifier, AbstractOption... settings) {
        return buildOptionMap(blobIdentifier.getGeneration(), null, settings);
    }

    @Override
    public List<Boolean> testIamPermissions(final String containerName, final List<String> permList, BucketRequestOption... settings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
            TestIamPermissionsResponse permissionsResult = runWithRetries(new Callable<TestIamPermissionsResponse>() {

                @Override
                public TestIamPermissionsResponse call() {
                    return rpcClient.testIamPermissions(containerName, permList, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            final Set<String> activePerms = null != permissionsResult.getPermissions() ? ImmutableSet.copyOf(permissionsResult.getPermissions()) : ImmutableSet.<String>of();
            return Lists.transform(permList, new Function<String, Boolean>() {

                @Override
                public Boolean apply(String permission) {
                    return activePerms.contains(permission);
                }
            });
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public StorageBucket get(String containerName, BucketGetOptions... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = BucketMetadata.create(containerName).toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
        try {
            com.google.api.services.storage.model.Bucket bucketResult = runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return rpcClient.get(bucketProto, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == bucketResult ? null : StorageBucket.fromProto(this, bucketResult);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public AclEntry getDefaultAcl(final String containerName, final AbstractEntity accessEntity) {
        try {
            ObjectAccessControl bucketResult = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.getDefaultAcl(containerName, accessEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == bucketResult ? null : AclEntry.fromObjectPb(bucketResult);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public AclEntry createAcl(String containerName, AclEntry accessControlEntry) {
        return createAcl(containerName, accessControlEntry, new BucketRequestOption[0]);
    }

    @Override
    public Policy getIamPolicy(final String containerName, BucketRequestOption... settings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
            return convertPolicyFromApi(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return rpcClient.getIamPolicy(containerName, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public List<Boolean> delete(Iterable<BlobIdentifier> blobIdentifierArray) {
        StorageOperationBatch operationBatch = batch();
        final List<Boolean> retrievedObjects = Lists.newArrayList();
        for (BlobIdentifier blobName : blobIdentifierArray) {
            operationBatch.deleteBlob(blobName).notify(new BatchResult.Callback<Boolean, StorageServiceException>() {

                @Override
                public void success(Boolean result) {
                    retrievedObjects.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    retrievedObjects.add(Boolean.FALSE);
                }
            });
        }
        operationBatch.submitBatch();
        return Collections.unmodifiableList(retrievedObjects);
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(Long gen, Long metaGen, Iterable<? extends AbstractOption> settings) {
        return buildOptionMap(gen, metaGen, settings, false);
    }

    @Override
    public URL signUrl(BlobMetadata blobMetadata, long expiry, TimeUnit timeUnit, UrlSigningOption... settings) {
        EnumMap<UrlSigningOption.HttpRequestOption, Object> storageOptionMap = Maps.newEnumMap(UrlSigningOption.HttpRequestOption.class);
        for (UrlSigningOption signingMode : settings) {
            storageOptionMap.put(signingMode.getOption(), signingMode.getValue());
        }
        boolean v2Enabled = getPreferredSignatureVersion(storageOptionMap).equals(UrlSigningOption.SigningVersion.V2);
        boolean v4Enabled = getPreferredSignatureVersion(storageOptionMap).equals(UrlSigningOption.SigningVersion.V4);
        ServiceAccountSigner serviceAccountSigner = (ServiceAccountSigner) storageOptionMap.get(UrlSigningOption.HttpRequestOption.SERVICE_ACCOUNT_CRED);
        if (null == serviceAccountSigner) {
            checkState(this.getOptions().getCredentials() instanceof ServiceAccountSigner, "Signing key was not provided and could not be derived");
            serviceAccountSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
        }
        long expiryTime = v4Enabled ? TimeUnit.SECONDS.convert(timeUnit.toMillis(expiry), TimeUnit.MILLISECONDS) : TimeUnit.SECONDS.convert(getOptions().getClock().millisTime() + timeUnit.toMillis(expiry), TimeUnit.MILLISECONDS);
        checkArgument(!(storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.VIRTUAL_HOSTED_STYLE) && storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.PATH_STYLE) && storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.BUCKET_BOUND_HOST_NAME)), "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be" + " specified.");
        String storageBucket = getSlashlessBucketNameFromBlobInfo(blobMetadata);
        String encodedBlobPath = "";
        if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
            encodedBlobPath = rfc3986UriEncode(blobMetadata.getName(), false);
        }
        boolean pathStyleEnabled = shouldUsePathStyle(storageOptionMap);
        String storageXmlHost = pathStyleEnabled ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(storageOptionMap) : STORAGE_XML_SCHEME + "://" + storageBucket + "." + getBaseStorageHostName(storageOptionMap);
        if (storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.BUCKET_BOUND_HOST_NAME)) {
            storageXmlHost = (String) storageOptionMap.get(UrlSigningOption.HttpRequestOption.BUCKET_BOUND_HOST_NAME);
        }
        String resourcePath = pathStyleEnabled ? buildResourceUriPath(storageBucket, encodedBlobPath, storageOptionMap) : buildResourceUriPath("", encodedBlobPath, storageOptionMap);
        URI resourceUri = URI.create(resourcePath);
        // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
        // canonical resource string that we'll sign to include the bucket.
        URI signingUri = v2Enabled ? URI.create(buildResourceUriPath(storageBucket, encodedBlobPath, storageOptionMap)) : resourceUri;
        try {
            SigningContext signingContext = buildSignedUrlInfo(storageOptionMap, blobMetadata, expiryTime, signingUri, serviceAccountSigner.getAccount());
            String rawPayload = signingContext.buildUnsignedPayload();
            byte[] rawSignature = serviceAccountSigner.sign(rawPayload.getBytes(UTF_8));
            StringBuilder stringAccumulator = new StringBuilder();
            stringAccumulator.append(storageXmlHost).append(resourceUri);
            if (!v4Enabled) {
                BaseEncoding base64Encoder = BaseEncoding.base64();
                String computedSignature = URLEncoder.encode(base64Encoder.encode(rawSignature), UTF_8.name());
                String v2Query = signingContext.buildV2QueryString();
                stringAccumulator.append('?');
                if (!Strings.isNullOrEmpty(v2Query)) {
                    stringAccumulator.append(v2Query).append('&');
                }
                stringAccumulator.append("GoogleAccessId=").append(serviceAccountSigner.getAccount());
                stringAccumulator.append("&Expires=").append(expiryTime);
                stringAccumulator.append("&Signature=").append(computedSignature);
            } else {
                BaseEncoding base64Encoder = BaseEncoding.base16().lowerCase();
                String computedSignature = URLEncoder.encode(base64Encoder.encode(rawSignature), UTF_8.name());
                String v4Query = signingContext.buildV4QueryString();
                stringAccumulator.append('?');
                if (!Strings.isNullOrEmpty(v4Query)) {
                    stringAccumulator.append(v4Query).append('&');
                }
                stringAccumulator.append("X-Goog-Signature=").append(computedSignature);
            }
            return new URL(stringAccumulator.toString());
        } catch (MalformedURLException | UnsupportedEncodingException cause) {
            throw new IllegalStateException(cause);
        }
    }

    @Override
    public HmacSecretKey.HmacKeyInfo updateHmacKeyState(final HmacKeyInfo keyInfo, final HmacSecretKey.HmacKeyStatus keyStatus, final HmacKeyUpdateOption... settings) {
        HmacKeyInfo newMetadata = HmacKeyInfo.builder(keyInfo.getServiceAccount()).setProjectId(keyInfo.getProjectId()).setAccessId(keyInfo.getAccessId()).setState(keyStatus).create();
        return updateHmacKeyInfo(newMetadata, settings);
    }

    @Override
    public AclEntry updateAcl(String containerName, AclEntry accessControlEntry, BucketRequestOption... settings) {
        final BucketAccessControl accessControlProto = accessControlEntry.toBucketProto().setBucket(containerName);
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
            return AclEntry.fromObjectPb(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return rpcClient.patchAcl(accessControlProto, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    /**
     * Returns the hostname used to send requests to Cloud Storage, e.g. "storage.googleapis.com".
     */
    private String getBaseStorageHostName(Map<UrlSigningOption.HttpRequestOption, Object> storageOptionMap) {
        String explicitHostName = (String) storageOptionMap.get(UrlSigningOption.HttpRequestOption.HOST_NAME);
        String bucketScopedHostName = (String) storageOptionMap.get(UrlSigningOption.HttpRequestOption.BUCKET_BOUND_HOST_NAME);
        if (!Strings.isNullOrEmpty(explicitHostName)) {
            return explicitHostName.replaceFirst("http(s)?://", "");
        }
        if (!Strings.isNullOrEmpty(bucketScopedHostName)) {
            return bucketScopedHostName.replaceFirst("http(s)?://", "");
        }
        return STORAGE_XML_HOST;
    }

    @Override
    public CloudStorageObject compose(final ComposeBlobsRequest composeOperation) {
        final List<StorageObject> sourceList = Lists.newArrayListWithCapacity(composeOperation.getSourceBlobs().size());
        for (ComposeBlobsRequest.SourceBlobMetadata sourceMetadata : composeOperation.getSourceBlobs()) {
            sourceList.add(BlobMetadata.newBuilder(BlobIdentifier.create(composeOperation.getTarget().getBucket(), sourceMetadata.getName(), sourceMetadata.getGeneration())).buildObject().toProto());
        }
        final StorageObject destinationObject = composeOperation.getTarget().toProto();
        final Map<StorageRpcClient.StorageOption, ?> targetPair = buildOptionMap(composeOperation.getTarget().getGeneration(), composeOperation.getTarget().getMetageneration(), composeOperation.getTargetOptions());
        try {
            return CloudStorageObject.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return rpcClient.compose(sourceList, destinationObject, targetPair);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public Page<HmacKeyInfo> listHmacKeys(HmacKeyListOption... settings) {
        return listAllHmacKeys(getOptions(), buildOptionMap(settings));
    }

    @Override
    public AclEntry getAcl(final String containerName, final AbstractEntity accessEntity, BucketRequestOption... settings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
            BucketAccessControl bucketResult = runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return rpcClient.getAcl(containerName, accessEntity.toProto(), optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == bucketResult ? null : AclEntry.fromObjectPb(bucketResult);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public CloudStorageObject update(BlobMetadata blobMetadata) {
        return update(blobMetadata, new BlobUploadOption[0]);
    }

    @Override
    public AclEntry createDefaultAcl(String containerName, AclEntry accessControlEntry) {
        final ObjectAccessControl accessControlProto = accessControlEntry.toObjectProto().setBucket(containerName);
        try {
            return AclEntry.fromObjectPb(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.createDefaultAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public Page<StorageBucket> list(BucketListOptions... settings) {
        return listAllBuckets(getOptions(), buildOptionMap(settings));
    }

    @Override
    public HmacSecretKey.HmacKeyInfo getHmacKey(final String hmacKeyId, final GetHmacKeyRequestOption... settings) {
        try {
            return HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return rpcClient.getHmacKey(hmacKeyId, buildOptionMap(settings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    /**
     * Builds signature info.
     *
     * @param storageOptionMap the option map
     * @param blobMetadata the blob info
     * @param expiryTime the expiration in seconds
     * @param resourceUri the resource URI
     * @param serviceAccountEmail the account email
     * @return signature info
     */
    private SigningContext buildSignedUrlInfo(Map<UrlSigningOption.HttpRequestOption, Object> storageOptionMap, BlobMetadata blobMetadata, long expiryTime, URI resourceUri, String serviceAccountEmail) {
        HttpRequestMethod httpMethod = storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.HTTP_METHOD) ? (HttpRequestMethod) storageOptionMap.get(UrlSigningOption.HttpRequestOption.HTTP_METHOD) : HttpRequestMethod.GET;
        SigningContext.SignedUrlBuilder signedUrlBuilder = new SigningContext.SignedUrlBuilder(httpMethod, expiryTime, resourceUri);
        if (firstNonNull((Boolean) storageOptionMap.get(UrlSigningOption.HttpRequestOption.MD5), false)) {
            checkArgument(null != blobMetadata.getMd5(), "Blob is missing a value for md5");
            signedUrlBuilder.setContentMd5(blobMetadata.getMd5());
        }
        if (firstNonNull((Boolean) storageOptionMap.get(UrlSigningOption.HttpRequestOption.CONTENT_TYPE), false)) {
            checkArgument(null != blobMetadata.getContentType(), "Blob is missing a value for content-type");
            signedUrlBuilder.setContentType(blobMetadata.getContentType());
        }
        signedUrlBuilder.setSignatureVersion((UrlSigningOption.SigningVersion) storageOptionMap.get(UrlSigningOption.HttpRequestOption.SIGNATURE_VERSION));
        signedUrlBuilder.setAccountEmail(serviceAccountEmail);
        signedUrlBuilder.setTimestamp(getOptions().getClock().millisTime());
        ImmutableMap.Builder<String, String> extraHeadersBuilder = new ImmutableMap.Builder<String, String>();
        boolean v4Enabled = UrlSigningOption.SigningVersion.V4.equals(storageOptionMap.get(UrlSigningOption.HttpRequestOption.SIGNATURE_VERSION));
        if (v4Enabled) {
            // We don't sign the host header for V2 signed URLs; only do this for V4.
            // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
            if (!storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.VIRTUAL_HOSTED_STYLE)) {
                if (storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.HOST_NAME) || storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.BUCKET_BOUND_HOST_NAME)) {
                    extraHeadersBuilder.put("host", getBaseStorageHostName(storageOptionMap));
                }
            } else {
                extraHeadersBuilder.put("host", getSlashlessBucketNameFromBlobInfo(blobMetadata) + "." + getBaseStorageHostName(storageOptionMap));
            }
        }
        if (storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.EXT_HEADERS)) {
            extraHeadersBuilder.putAll((Map<String, String>) storageOptionMap.get(UrlSigningOption.HttpRequestOption.EXT_HEADERS));
        }
        ImmutableMap.Builder<String, String> paramsBuilder = new ImmutableMap.Builder<String, String>();
        if (storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.QUERY_PARAMS)) {
            paramsBuilder.putAll((Map<String, String>) storageOptionMap.get(UrlSigningOption.HttpRequestOption.QUERY_PARAMS));
        }
        return signedUrlBuilder.setCanonicalizedExtensionHeaders((Map<String, String>) extraHeadersBuilder.build()).setCanonicalizedQueryParams((Map<String, String>) paramsBuilder.build()).buildSignedUrl();
    }

    @Override
    public CloudStorageObject get(String containerName, String blobName, BlobGetOptions... settings) {
        return get(BlobIdentifier.create(containerName, blobName), settings);
    }

    private boolean shouldUsePathStyle(EnumMap<UrlSigningOption.HttpRequestOption, Object> storageOptionMap) {
        // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
        // logic to return false unless PATH_STYLE was explicitly specified.
        if (storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.VIRTUAL_HOSTED_STYLE) || storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.BUCKET_BOUND_HOST_NAME)) {
            return false;
        }
        return true;
    }

    @Override
    public List<CloudStorageObject> get(BlobIdentifier... blobIdentifierArray) {
        return get(Arrays.asList(blobIdentifierArray));
    }

    @Override
    public AclEntry getAcl(final BlobIdentifier blobName, final AbstractEntity accessEntity) {
        try {
            ObjectAccessControl bucketResult = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.getAcl(blobName.getBucket(), blobName.getName(), blobName.getGeneration(), accessEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == bucketResult ? null : AclEntry.fromObjectPb(bucketResult);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public boolean deleteAcl(final BlobIdentifier blobName, final AbstractEntity accessEntity) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.deleteAcl(blobName.getBucket(), blobName.getName(), blobName.getGeneration(), accessEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public AclEntry updateAcl(BlobIdentifier blobName, AclEntry accessControlEntry) {
        final ObjectAccessControl accessControlProto = accessControlEntry.toObjectProto().setBucket(blobName.getBucket()).setObject(blobName.getName()).setGeneration(blobName.getGeneration());
        try {
            return AclEntry.fromObjectPb(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.patchAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public boolean delete(BlobIdentifier blobName, BlobReadOption... settings) {
        final StorageObject storageObj = blobName.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(blobName, settings);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.delete(storageObj, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    @Deprecated
    public CloudStorageObject create(BlobMetadata blobMetadata, InputStream dataBytes, BlobWriteOptions... settings) {
        Tuple<BlobMetadata, BlobUploadOption[]> targetPair = BlobUploadOption.toTargetOptions(blobMetadata, settings);
        StorageObject blobProto = targetPair.x().toProto();
        Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(targetPair.x(), targetPair.y());
        InputStream inStream = firstNonNull(dataBytes, new ByteArrayInputStream(ZERO_LENGTH_BYTE_ARRAY));
        // retries are not safe when the input is an InputStream, so we can't retry.
        return CloudStorageObject.fromProto(this, rpcClient.create(blobProto, inStream, optionToValueMap));
    }

    @Override
    public List<AclEntry> listAcls(final String containerName) {
        return listAcls(containerName, new BucketRequestOption[0]);
    }

    @Override
    public ReadChannel reader(String containerName, String blobName, BlobReadOption... settings) {
        Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
        return new BlobInputChannel(getOptions(), BlobIdentifier.create(containerName, blobName), optionToValueMap);
    }

    @Override
    public List<CloudStorageObject> get(Iterable<BlobIdentifier> blobIdentifierArray) {
        StorageOperationBatch operationBatch = batch();
        final List<CloudStorageObject> retrievedObjects = Lists.newArrayList();
        for (BlobIdentifier blobName : blobIdentifierArray) {
            operationBatch.get(blobName).notify(new BatchResult.Callback<CloudStorageObject, StorageServiceException>() {

                @Override
                public void success(CloudStorageObject result) {
                    retrievedObjects.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    retrievedObjects.add(null);
                }
            });
        }
        operationBatch.submitBatch();
        return Collections.unmodifiableList(retrievedObjects);
    }

    @Override
    public StorageBucket lockRetentionPolicy(BucketMetadata bucketMetadata, BucketTargetOptions... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(bucketMetadata, settings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return rpcClient.lockRetentionPolicy(bucketProto, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    private static <T> void putInOptionMap(StorageRpcClient.StorageOption requestedOption, StorageRpcClient.StorageOption targetOption, T fallbackValue, Map<StorageRpcClient.StorageOption, Object> optionStorage) {
        if (optionStorage.containsKey(requestedOption)) {
            @SuppressWarnings("unchecked")
            T resolvedValue = (T) optionStorage.remove(requestedOption);
            checkArgument(null != resolvedValue || null != fallbackValue, "Option " + requestedOption.getValue() + " is missing a value");
            resolvedValue = firstNonNull(resolvedValue, fallbackValue);
            optionStorage.put(targetOption, resolvedValue);
        }
    }

    @Override
    public StorageOperationBatch batch() {
        return new StorageOperationBatch(this.getOptions());
    }

    @Override
    public CloudStorageObject create(BlobMetadata blobMetadata, BlobUploadOption... settings) {
        BlobMetadata updatedMetadata = blobMetadata.toBlobBuilder().setMd5(EMPTY_BYTES_MD5_HASH).setCrc32c(EMPTY_BYTES_CRC32C).buildObject();
        return createInternal(updatedMetadata, ZERO_LENGTH_BYTE_ARRAY, settings);
    }

    @Override
    public byte[] readAllBytes(String containerName, String blobName, BlobReadOption... settings) {
        return readAllBytes(BlobIdentifier.create(containerName, blobName), settings);
    }

    @Override
    public AclEntry createAcl(String containerName, AclEntry accessControlEntry, BucketRequestOption... settings) {
        final BucketAccessControl accessControlProto = accessControlEntry.toBucketProto().setBucket(containerName);
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
            return AclEntry.fromObjectPb(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return rpcClient.createAcl(accessControlProto, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public List<CloudStorageObject> update(Iterable<BlobMetadata> blobMetadataArray) {
        StorageOperationBatch operationBatch = batch();
        final List<CloudStorageObject> retrievedObjects = Lists.newArrayList();
        for (BlobMetadata blobMetadata : blobMetadataArray) {
            operationBatch.updateBlob(blobMetadata).notify(new BatchResult.Callback<CloudStorageObject, StorageServiceException>() {

                @Override
                public void success(CloudStorageObject result) {
                    retrievedObjects.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    retrievedObjects.add(null);
                }
            });
        }
        operationBatch.submitBatch();
        return Collections.unmodifiableList(retrievedObjects);
    }

    @Override
    public CloudStorageObject create(BlobMetadata blobMetadata, byte[] dataBytes, int startIndex, int count, BlobUploadOption... settings) {
        dataBytes = firstNonNull(dataBytes, ZERO_LENGTH_BYTE_ARRAY);
        byte[] chunkBytes = Arrays.copyOfRange(dataBytes, startIndex, startIndex + count);
        BlobMetadata updatedMetadata = blobMetadata.toBlobBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(chunkBytes).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(chunkBytes).asInt()))).buildObject();
        return createInternal(updatedMetadata, chunkBytes, settings);
    }

    private static Page<HmacKeyInfo> listAllHmacKeys(final StorageSettings storageSettings, final Map<StorageRpcClient.StorageOption, ?> settings) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> listResult = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> call() {
                    return storageSettings.getStorageRpcV1().listHmacKeys(settings);
                }
            }, storageSettings.getRetrySettings(), EXCEPTION_HANDLER, storageSettings.getClock());
            String pageCursor = listResult.x();
            final Iterable<HmacKeyInfo> keyInfo = null == listResult.y() ? ImmutableList.<HmacKeyInfo>of() : Iterables.transform(listResult.y(), new Function<com.google.api.services.storage.model.HmacKeyMetadata, HmacKeyInfo>() {

                @Override
                public HmacSecretKey.HmacKeyInfo apply(com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                    return HmacKeyInfo.fromProto(metadataPb);
                }
            });
            return new PageImpl<>(new HmacKeyMetadataPageRetriever(storageSettings, settings), pageCursor, keyInfo);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public AclEntry createAcl(final BlobIdentifier blobName, final AclEntry accessControlEntry) {
        final ObjectAccessControl accessControlProto = accessControlEntry.toObjectProto().setBucket(blobName.getBucket()).setObject(blobName.getName()).setGeneration(blobName.getGeneration());
        try {
            return AclEntry.fromObjectPb(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.createAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public List<Boolean> delete(BlobIdentifier... blobIdentifierArray) {
        return delete(Arrays.asList(blobIdentifierArray));
    }

    @Override
    public List<AclEntry> listAcls(final BlobIdentifier blobName) {
        try {
            List<ObjectAccessControl> bucketResult = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return rpcClient.listAcls(blobName.getBucket(), blobName.getName(), blobName.getGeneration());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(bucketResult, AclEntry.FROM_OBJECT_PROTO_FUNCTION);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public boolean deleteAcl(final String containerName, final AbstractEntity accessEntity) {
        return deleteAcl(containerName, accessEntity, new BucketRequestOption[0]);
    }

    @Override
    public AclEntry updateAcl(String containerName, AclEntry accessControlEntry) {
        return updateAcl(containerName, accessControlEntry, new BucketRequestOption[0]);
    }

    @Override
    public StorageBucket update(BucketMetadata bucketMetadata, BucketTargetOptions... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(bucketMetadata, settings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return rpcClient.patch(bucketProto, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    private CloudStorageObject createInternal(BlobMetadata metadata, final byte[] dataBytes, BlobUploadOption... settings) {
        Preconditions.checkNotNull(dataBytes);
        final StorageObject blobProto = metadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(metadata, settings);
        try {
            return CloudStorageObject.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return rpcClient.create(blobProto, new ByteArrayInputStream(dataBytes), optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public CloudStorageObject create(BlobMetadata blobMetadata, byte[] dataBytes, BlobUploadOption... settings) {
        dataBytes = firstNonNull(dataBytes, ZERO_LENGTH_BYTE_ARRAY);
        BlobMetadata updatedMetadata = blobMetadata.toBlobBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(dataBytes).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(dataBytes).asInt()))).buildObject();
        return createInternal(updatedMetadata, dataBytes, settings);
    }

    @Override
    public boolean deleteDefaultAcl(final String containerName, final AbstractEntity accessEntity) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.deleteDefaultAcl(containerName, accessEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public StorageBucket create(BucketMetadata bucketMetadata, BucketTargetOptions... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(bucketMetadata, settings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return rpcClient.create(bucketProto, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public AclEntry getAcl(final String containerName, final AbstractEntity accessEntity) {
        return getAcl(containerName, accessEntity, new BucketRequestOption[0]);
    }

    private UrlSigningOption.SigningVersion getPreferredSignatureVersion(EnumMap<UrlSigningOption.HttpRequestOption, Object> storageOptionMap) {
        // Check for an explicitly specified version in the map.
        for (UrlSigningOption.SigningVersion signingVersion : UrlSigningOption.SigningVersion.values()) {
            if (signingVersion.equals(storageOptionMap.get(UrlSigningOption.HttpRequestOption.SIGNATURE_VERSION))) {
                return signingVersion;
            }
        }
        // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
        // Change this to V4 once we make it the default.
        return UrlSigningOption.SigningVersion.V2;
    }

    @Override
    public byte[] readAllBytes(BlobIdentifier blobName, BlobReadOption... settings) {
        final StorageObject storageObj = blobName.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(blobName, settings);
        try {
            return runWithRetries(new Callable<byte[]>() {

                @Override
                public byte[] call() {
                    return rpcClient.load(storageObj, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public boolean deleteAcl(final String containerName, final AbstractEntity accessEntity, BucketRequestOption... settings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.deleteAcl(containerName, accessEntity.toProto(), optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    private String getSlashlessBucketNameFromBlobInfo(BlobMetadata blobMetadata) {
        // The bucket name itself should never contain a forward slash. However, parts already existed
        // in the code to check for this, so we remove the forward slashes to be safe here.
        return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(AbstractOption... settings) {
        return buildOptionMap(null, null, Arrays.asList(settings));
    }

    @Override
    public List<CloudStorageObject> update(BlobMetadata... blobMetadataArray) {
        return update(Arrays.asList(blobMetadataArray));
    }

    @Override
    public CloudStorageObject get(BlobIdentifier blobName) {
        return get(blobName, new BlobGetOptions[0]);
    }

    @Override
    public boolean delete(String containerName, String blobName, BlobReadOption... settings) {
        return delete(BlobIdentifier.create(containerName, blobName), settings);
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BucketMetadata bucketMetadata, AbstractOption... settings) {
        return buildOptionMap(null, bucketMetadata.getMetageneration(), settings);
    }

    StorageServiceImpl(StorageSettings settings) {
        super(settings);
        rpcClient = settings.getStorageRpcV1();
    }

    @Override
    public boolean delete(String containerName, BucketRequestOption... settings) {
        final com.google.api.services.storage.model.Bucket bucketProto = BucketMetadata.create(containerName).toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(settings);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.delete(bucketProto, optionToValueMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public List<AclEntry> listDefaultAcls(final String containerName) {
        try {
            List<ObjectAccessControl> bucketResult = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return rpcClient.listDefaultAcls(containerName);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(bucketResult, AclEntry.FROM_OBJECT_PROTO_FUNCTION);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    private String buildResourceUriPath(String bucketWithoutSlash, String encodedBlobPath, EnumMap<UrlSigningOption.HttpRequestOption, Object> storageOptionMap) {
        if (Strings.isNullOrEmpty(bucketWithoutSlash)) {
            if (Strings.isNullOrEmpty(encodedBlobPath)) {
                return PATH_SEPARATOR;
            }
            if (encodedBlobPath.startsWith(PATH_SEPARATOR)) {
                return encodedBlobPath;
            }
            return PATH_SEPARATOR + encodedBlobPath;
        }
        StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(PATH_SEPARATOR).append(bucketWithoutSlash);
        if (Strings.isNullOrEmpty(encodedBlobPath)) {
            boolean v2Enabled = getPreferredSignatureVersion(storageOptionMap).equals(UrlSigningOption.SigningVersion.V2);
            // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
            // must end with a forward slash.
            if (storageOptionMap.containsKey(UrlSigningOption.HttpRequestOption.VIRTUAL_HOSTED_STYLE) && v2Enabled) {
                uriBuilder.append(PATH_SEPARATOR);
            }
            return uriBuilder.toString();
        }
        uriBuilder.append(PATH_SEPARATOR);
        uriBuilder.append(encodedBlobPath);
        return uriBuilder.toString();
    }

    @Override
    public ChunkedBlobWriter writer(URL signedUrl) {
        return new ChunkedBlobWriter(getOptions(), signedUrl);
    }

    @Override
    public ServiceAccountInfo getServiceAccount(final String projectKey) {
        try {
            com.google.api.services.storage.model.ServiceAccount bucketResult = runWithRetries(new Callable<com.google.api.services.storage.model.ServiceAccount>() {

                @Override
                public com.google.api.services.storage.model.ServiceAccount call() {
                    return rpcClient.getServiceAccount(projectKey);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == bucketResult ? null : ServiceAccountInfo.fromProto(bucketResult);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    private ChunkedBlobWriter newBlobWriter(BlobMetadata blobMetadata, BlobUploadOption... settings) {
        final Map<StorageRpcClient.StorageOption, ?> optionToValueMap = buildOptionMap(blobMetadata, settings);
        return new ChunkedBlobWriter(getOptions(), blobMetadata, optionToValueMap);
    }

    @Override
    public AclEntry updateDefaultAcl(String containerName, AclEntry accessControlEntry) {
        final ObjectAccessControl accessControlProto = accessControlEntry.toObjectProto().setBucket(containerName);
        try {
            return AclEntry.fromObjectPb(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.patchDefaultAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public Page<CloudStorageObject> list(final String containerName, BlobListOptions... settings) {
        return listAllBlobs(containerName, getOptions(), buildOptionMap(settings));
    }

    @Override
    public boolean delete(BlobIdentifier blobName) {
        return delete(blobName, new BlobReadOption[0]);
    }

    static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BlobMetadata blobMetadata, AbstractOption... settings) {
        return buildOptionMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), settings);
    }

    private HmacKeyInfo updateHmacKeyInfo(final HmacKeyInfo keyInfo, final HmacKeyUpdateOption... settings) {
        try {
            return HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return rpcClient.updateHmacKey(keyInfo.toProto(), buildOptionMap(settings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

    @Override
    public ResumableCopyWriter copy(final CopyOperationRequest copyOperation) {
        final StorageObject originObject = copyOperation.getSource().toProto();
        final Map<StorageRpcClient.StorageOption, ?> originOptions = buildOptionMap(copyOperation.getSource().getGeneration(), null, copyOperation.getSourceOptions(), true);
        final StorageObject destinationObject = copyOperation.getTarget().toProto();
        final Map<StorageRpcClient.StorageOption, ?> targetPair = buildOptionMap(copyOperation.getTarget().getGeneration(), copyOperation.getTarget().getMetageneration(), copyOperation.getTargetOptions());
        try {
            RewriteOperationResponse rewriteResult = runWithRetries(new Callable<RewriteOperationResponse>() {

                @Override
                public StorageRpcClient.RewriteOperationResponse call() {
                    return rpcClient.openRewrite(new StorageRpcClient.ObjectRewriteRequest(originObject, originOptions, copyOperation.getOverrideInfo(), destinationObject, targetPair, copyOperation.getMegabytesCopiedPerChunk()));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return new ResumableCopyWriter(getOptions(), rewriteResult);
        } catch (RetryHelperException ex) {
            throw StorageServiceException.translateThenThrow(ex);
        }
    }

}
