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
import static com.google.cloud.storage.SignedUrlEncoder.encodeRfc3986Uri;
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
import com.google.cloud.storage.HmacSecretKey.HmacKeyInfo;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteOperationResult;
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

final class DefaultStorageImpl extends BaseService<StorageSettings> implements StorageService {

    private static final byte[] ZERO_LENGTH_BYTE_ARRAY = {};

    private static final String EMPTY_BYTES_MD5 = "1B2M2Y8AsgTpgAmY7PhCfg==";

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

    DefaultStorageImpl(StorageSettings storageSettings) {
        super(storageSettings);
        rpcClient = storageSettings.getStorageRpcV1();
    }

    @Override
    public StorageBucket create(BucketMetadata bucketMetadata, BucketTargetOptions... storageSettings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(bucketMetadata, storageSettings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return rpcClient.create(bucketProto, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageObject create(BlobMetadata blobMetadata, BlobUploadOption... storageSettings) {
        BlobMetadata updatedMetadata = blobMetadata.asBuilder().setMd5(EMPTY_BYTES_MD5).setCrc32c(EMPTY_BYTES_CRC32C).buildObject();
        return createInternal(updatedMetadata, ZERO_LENGTH_BYTE_ARRAY, storageSettings);
    }

    @Override
    public StorageObject create(BlobMetadata blobMetadata, byte[] inputData, BlobUploadOption... storageSettings) {
        inputData = firstNonNull(inputData, ZERO_LENGTH_BYTE_ARRAY);
        BlobMetadata updatedMetadata = blobMetadata.asBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(inputData).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(inputData).asInt()))).buildObject();
        return createInternal(updatedMetadata, inputData, storageSettings);
    }

    @Override
    public StorageObject create(BlobMetadata blobMetadata, byte[] inputData, int startIndex, int count, BlobUploadOption... storageSettings) {
        inputData = firstNonNull(inputData, ZERO_LENGTH_BYTE_ARRAY);
        byte[] chunkBytes = Arrays.copyOfRange(inputData, startIndex, startIndex + count);
        BlobMetadata updatedMetadata = blobMetadata.asBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(chunkBytes).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(chunkBytes).asInt()))).buildObject();
        return createInternal(updatedMetadata, chunkBytes, storageSettings);
    }

    @Override
    @Deprecated
    public StorageObject create(BlobMetadata blobMetadata, InputStream inputData, BlobWriteSetting... storageSettings) {
        Tuple<BlobMetadata, BlobUploadOption[]> targetPair = BlobUploadOption.toTargetOptions(blobMetadata, storageSettings);
        com.google.api.services.storage.model.StorageObject blobProto = targetPair.x().toProto();
        Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(targetPair.x(), targetPair.y());
        InputStream inStream = firstNonNull(inputData, new ByteArrayInputStream(ZERO_LENGTH_BYTE_ARRAY));
        // retries are not safe when the input is an InputStream, so we can't retry.
        return StorageObject.fromProto(this, rpcClient.create(blobProto, inStream, optionMap));
    }

    private StorageObject createInternal(BlobMetadata blobMetadata, final byte[] inputData, BlobUploadOption... storageSettings) {
        Preconditions.checkNotNull(inputData);
        final com.google.api.services.storage.model.StorageObject blobProto = blobMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(blobMetadata, storageSettings);
        try {
            return StorageObject.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.StorageObject>() {

                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                    return rpcClient.create(blobProto, new ByteArrayInputStream(inputData), optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBucket get(String containerName, GetBucketOption... storageSettings) {
        final com.google.api.services.storage.model.Bucket bucketProto = BucketMetadata.ofName(containerName).toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
        try {
            com.google.api.services.storage.model.Bucket resultBucket = runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return rpcClient.get(bucketProto, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : StorageBucket.fromProto(this, resultBucket);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageObject get(String containerName, String objectIdentifier, BlobFetchOption... storageSettings) {
        return get(BlobIdentifier.create(containerName, objectIdentifier), storageSettings);
    }

    @Override
    public StorageObject get(BlobIdentifier objectIdentifier, BlobFetchOption... storageSettings) {
        final com.google.api.services.storage.model.StorageObject storedEntity = objectIdentifier.toStorageObject();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(objectIdentifier, storageSettings);
        try {
            com.google.api.services.storage.model.StorageObject objectEntity = runWithRetries(new Callable<com.google.api.services.storage.model.StorageObject>() {

                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                    return rpcClient.get(storedEntity, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == objectEntity ? null : StorageObject.fromProto(this, objectEntity);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageObject get(BlobIdentifier objectIdentifier) {
        return get(objectIdentifier, new BlobFetchOption[0]);
    }

    private static class BucketPageIterator implements NextPageFetcher<StorageBucket> {

        private static final long serialVersionUID = 5850406828803613729L;

        private final Map<StorageRpcClient.StorageOption, ?> requestOptionMap;

        private final StorageSettings serviceSettings;

        BucketPageIterator(StorageSettings serviceSettings, String pageCursor, Map<StorageRpcClient.StorageOption, ?> storageOptionMap) {
            this.requestOptionMap = PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, pageCursor, storageOptionMap);
            this.serviceSettings = serviceSettings;
        }

        @Override
        public Page<StorageBucket> getNextPage() {
            return listAllBuckets(serviceSettings, requestOptionMap);
        }
    }

    private static class BlobPageIterator implements NextPageFetcher<StorageObject> {

        private static final long serialVersionUID = 81807334445874098L;

        private final Map<StorageRpcClient.StorageOption, ?> requestOptionMap;

        private final StorageSettings serviceSettings;

        private final String containerName;

        BlobPageIterator(String containerName, StorageSettings serviceSettings, String pageCursor, Map<StorageRpcClient.StorageOption, ?> storageOptionMap) {
            this.requestOptionMap = PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, pageCursor, storageOptionMap);
            this.serviceSettings = serviceSettings;
            this.containerName = containerName;
        }

        @Override
        public Page<StorageObject> getNextPage() {
            return listAllBlobs(containerName, serviceSettings, requestOptionMap);
        }
    }

    private static class HmacKeyMetadataPageRetriever implements NextPageFetcher<HmacSecretKey.HmacKeyInfo> {

        private static final long serialVersionUID = 308012320541700881L;

        private final StorageSettings serviceSettings;

        private final Map<StorageRpcClient.StorageOption, ?> storageSettings;

        HmacKeyMetadataPageRetriever(StorageSettings serviceSettings, Map<StorageRpcClient.StorageOption, ?> storageSettings) {
            this.serviceSettings = serviceSettings;
            this.storageSettings = storageSettings;
        }

        @Override
        public Page<HmacKeyInfo> getNextPage() {
            return listAllHmacKeys(serviceSettings, storageSettings);
        }
    }

    @Override
    public Page<StorageBucket> list(BucketListOptions... storageSettings) {
        return listAllBuckets(getOptions(), buildOptionMap(storageSettings));
    }

    @Override
    public Page<StorageObject> list(final String containerName, BlobListOptions... storageSettings) {
        return listAllBlobs(containerName, getOptions(), buildOptionMap(storageSettings));
    }

    private static Page<StorageBucket> listAllBuckets(final StorageSettings serviceSettings, final Map<StorageRpcClient.StorageOption, ?> optionMap) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> bucketTuple = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> call() {
                    return serviceSettings.getStorageRpcV1().list(optionMap);
                }
            }, serviceSettings.getRetrySettings(), EXCEPTION_HANDLER, serviceSettings.getClock());
            String pageCursor = bucketTuple.x();
            Iterable<StorageBucket> bucketList = null == bucketTuple.y() ? ImmutableList.<StorageBucket>of() : Iterables.transform(bucketTuple.y(), new Function<com.google.api.services.storage.model.Bucket, StorageBucket>() {

                @Override
                public StorageBucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                    return StorageBucket.fromProto(serviceSettings.getService(), bucketPb);
                }
            });
            return new PageImpl<>(new BucketPageIterator(serviceSettings, pageCursor, optionMap), pageCursor, bucketList);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    private static Page<StorageObject> listAllBlobs(final String containerName, final StorageSettings serviceSettings, final Map<StorageRpcClient.StorageOption, ?> optionMap) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> bucketTuple = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> call() {
                    return serviceSettings.getStorageRpcV1().list(containerName, optionMap);
                }
            }, serviceSettings.getRetrySettings(), EXCEPTION_HANDLER, serviceSettings.getClock());
            String pageCursor = bucketTuple.x();
            Iterable<StorageObject> blobList = null == bucketTuple.y() ? ImmutableList.<StorageObject>of() : Iterables.transform(bucketTuple.y(), new Function<com.google.api.services.storage.model.StorageObject, StorageObject>() {

                @Override
                public StorageObject apply(com.google.api.services.storage.model.StorageObject storageObject) {
                    return StorageObject.fromProto(serviceSettings.getService(), storageObject);
                }
            });
            return new PageImpl<>(new BlobPageIterator(containerName, serviceSettings, pageCursor, optionMap), pageCursor, blobList);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBucket update(BucketMetadata bucketMetadata, BucketTargetOptions... storageSettings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(bucketMetadata, storageSettings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return rpcClient.patch(bucketProto, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageObject update(BlobMetadata blobMetadata, BlobUploadOption... storageSettings) {
        final com.google.api.services.storage.model.StorageObject objectEntity = blobMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(blobMetadata, storageSettings);
        try {
            return StorageObject.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.StorageObject>() {

                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                    return rpcClient.patch(objectEntity, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageObject update(BlobMetadata blobMetadata) {
        return update(blobMetadata, new BlobUploadOption[0]);
    }

    @Override
    public boolean delete(String containerName, BucketFilterOption... storageSettings) {
        final com.google.api.services.storage.model.Bucket bucketProto = BucketMetadata.ofName(containerName).toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.delete(bucketProto, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public boolean delete(String containerName, String objectIdentifier, BlobSourceOptions... storageSettings) {
        return delete(BlobIdentifier.create(containerName, objectIdentifier), storageSettings);
    }

    @Override
    public boolean delete(BlobIdentifier objectIdentifier, BlobSourceOptions... storageSettings) {
        final com.google.api.services.storage.model.StorageObject objectEntity = objectIdentifier.toStorageObject();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(objectIdentifier, storageSettings);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.delete(objectEntity, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public boolean delete(BlobIdentifier objectIdentifier) {
        return delete(objectIdentifier, new BlobSourceOptions[0]);
    }

    @Override
    public StorageObject compose(final ComposeObjectRequest mergeRequest) {
        final List<com.google.api.services.storage.model.StorageObject> inputObjects = Lists.newArrayListWithCapacity(mergeRequest.getSourceBlobs().size());
        for (ComposeObjectRequest.SourceBlobMetadata originBlob : mergeRequest.getSourceBlobs()) {
            inputObjects.add(BlobMetadata.newBuilder(BlobIdentifier.create(mergeRequest.getTarget().getBucket(), originBlob.getName(), originBlob.getGeneration())).buildObject().toProto());
        }
        final com.google.api.services.storage.model.StorageObject destinationObject = mergeRequest.getTarget().toProto();
        final Map<StorageRpcClient.StorageOption, ?> targetPair = buildOptionMap(mergeRequest.getTarget().getGeneration(), mergeRequest.getTarget().getMetageneration(), mergeRequest.getTargetOptions());
        try {
            return StorageObject.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.StorageObject>() {

                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                    return rpcClient.compose(inputObjects, destinationObject, targetPair);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public BlobCopyWriter copy(final CopyOperationRequest transferRequest) {
        final com.google.api.services.storage.model.StorageObject originObject = transferRequest.getSource().toStorageObject();
        final Map<StorageRpcClient.StorageOption, ?> originOptions = buildOptionMap(transferRequest.getSource().getGeneration(), null, transferRequest.getSourceOptions(), true);
        final com.google.api.services.storage.model.StorageObject destinationObject = transferRequest.getTarget().toProto();
        final Map<StorageRpcClient.StorageOption, ?> targetPair = buildOptionMap(transferRequest.getTarget().getGeneration(), transferRequest.getTarget().getMetageneration(), transferRequest.getTargetOptions());
        try {
            RewriteOperationResult rewriteOutcome = runWithRetries(new Callable<RewriteOperationResult>() {

                @Override
                public StorageRpcClient.RewriteOperationResult call() {
                    return rpcClient.openRewrite(new StorageRpcClient.ObjectRewriteRequest(originObject, originOptions, transferRequest.getOverrideInfo(), destinationObject, targetPair, transferRequest.getMegabytesCopiedPerChunk()));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return new BlobCopyWriter(getOptions(), rewriteOutcome);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public byte[] readAllBytes(String containerName, String objectIdentifier, BlobSourceOptions... storageSettings) {
        return readAllBytes(BlobIdentifier.create(containerName, objectIdentifier), storageSettings);
    }

    @Override
    public byte[] readAllBytes(BlobIdentifier objectIdentifier, BlobSourceOptions... storageSettings) {
        final com.google.api.services.storage.model.StorageObject objectEntity = objectIdentifier.toStorageObject();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(objectIdentifier, storageSettings);
        try {
            return runWithRetries(new Callable<byte[]>() {

                @Override
                public byte[] call() {
                    return rpcClient.load(objectEntity, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBatchOperation batch() {
        return new StorageBatchOperation(this.getOptions());
    }

    @Override
    public ReadChannel reader(String containerName, String objectIdentifier, BlobSourceOptions... storageSettings) {
        Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
        return new BlobReaderChannel(getOptions(), BlobIdentifier.create(containerName, objectIdentifier), optionMap);
    }

    @Override
    public ReadChannel reader(BlobIdentifier objectIdentifier, BlobSourceOptions... storageSettings) {
        Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(objectIdentifier, storageSettings);
        return new BlobReaderChannel(getOptions(), objectIdentifier, optionMap);
    }

    @Override
    public BlobUploadChannel writer(BlobMetadata blobMetadata, BlobWriteSetting... storageSettings) {
        Tuple<BlobMetadata, BlobUploadOption[]> targetPair = BlobUploadOption.toTargetOptions(blobMetadata, storageSettings);
        return createWriter(targetPair.x(), targetPair.y());
    }

    @Override
    public BlobUploadChannel writer(URL signedUri) {
        return new BlobUploadChannel(getOptions(), signedUri);
    }

    private BlobUploadChannel createWriter(BlobMetadata blobMetadata, BlobUploadOption... storageSettings) {
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(blobMetadata, storageSettings);
        return new BlobUploadChannel(getOptions(), blobMetadata, optionMap);
    }

    @Override
    public URL signUrl(BlobMetadata blobMetadata, long timePeriod, TimeUnit timeUnitType, UrlSigningOption... storageSettings) {
        EnumMap<UrlSigningOption.RequestOption, Object> storageOptionMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
        for (UrlSigningOption urlSigningMode : storageSettings) {
            storageOptionMap.put(urlSigningMode.getOption(), urlSigningMode.getValue());
        }
        boolean v2Enabled = getPreferredSignatureVersion(storageOptionMap).equals(UrlSigningOption.SigningVersion.V2);
        boolean v4Enabled = getPreferredSignatureVersion(storageOptionMap).equals(UrlSigningOption.SigningVersion.V4);
        ServiceAccountSigner serviceAccountSigner = (ServiceAccountSigner) storageOptionMap.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
        if (null == serviceAccountSigner) {
            checkState(this.getOptions().getCredentials() instanceof ServiceAccountSigner, "Signing key was not provided and could not be derived");
            serviceAccountSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
        }
        long expiryTime = v4Enabled ? TimeUnit.SECONDS.convert(timeUnitType.toMillis(timePeriod), TimeUnit.MILLISECONDS) : TimeUnit.SECONDS.convert(getOptions().getClock().millisTime() + timeUnitType.toMillis(timePeriod), TimeUnit.MILLISECONDS);
        checkArgument(!(storageOptionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && storageOptionMap.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)), "Cannot specify both the VIRTUAL_HOSTED_STYLE and PATH_STYLE SignUrlOptions together.");
        String storageBucket = getBucketNameWithoutSlashes(blobMetadata);
        String encodedObjectName = "";
        if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
            encodedObjectName = encodeRfc3986Uri(blobMetadata.getName(), false);
        }
        boolean pathStyleEnabled = usePathStyleForSignedUrl(storageOptionMap);
        String storageXmlEndpoint = pathStyleEnabled ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(storageOptionMap) : STORAGE_XML_SCHEME + "://" + storageBucket + "." + getBaseStorageHostName(storageOptionMap);
        String signingPath = pathStyleEnabled ? buildResourceUriPath(storageBucket, encodedObjectName, storageOptionMap) : buildResourceUriPath("", encodedObjectName, storageOptionMap);
        URI resourceUri = URI.create(signingPath);
        // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
        // canonical resource string that we'll sign to include the bucket.
        URI signingUri = v2Enabled ? URI.create(buildResourceUriPath(storageBucket, encodedObjectName, storageOptionMap)) : resourceUri;
        try {
            SigningInfo signingDetails = createSignatureInfo(storageOptionMap, blobMetadata, expiryTime, signingUri, serviceAccountSigner.getAccount());
            String rawPayload = signingDetails.createUnsignedPayload();
            byte[] sigBytes = serviceAccountSigner.sign(rawPayload.getBytes(UTF_8));
            StringBuilder stringAccumulator = new StringBuilder();
            stringAccumulator.append(storageXmlEndpoint).append(resourceUri);
            if (!v4Enabled) {
                BaseEncoding baseCodec = BaseEncoding.base64();
                String signedString = URLEncoder.encode(baseCodec.encode(sigBytes), UTF_8.name());
                String v2QueryParams = signingDetails.buildV2QueryString();
                stringAccumulator.append('?');
                if (!Strings.isNullOrEmpty(v2QueryParams)) {
                    stringAccumulator.append(v2QueryParams).append('&');
                }
                stringAccumulator.append("GoogleAccessId=").append(serviceAccountSigner.getAccount());
                stringAccumulator.append("&Expires=").append(expiryTime);
                stringAccumulator.append("&Signature=").append(signedString);
            } else {
                BaseEncoding baseCodec = BaseEncoding.base16().lowerCase();
                String signedString = URLEncoder.encode(baseCodec.encode(sigBytes), UTF_8.name());
                String v4QueryParams = signingDetails.buildV4QueryString();
                stringAccumulator.append('?');
                if (!Strings.isNullOrEmpty(v4QueryParams)) {
                    stringAccumulator.append(v4QueryParams).append('&');
                }
                stringAccumulator.append("X-Goog-Signature=").append(signedString);
            }
            return new URL(stringAccumulator.toString());
        } catch (MalformedURLException | UnsupportedEncodingException caughtException) {
            throw new IllegalStateException(caughtException);
        }
    }

    private String buildResourceUriPath(String bucketSegment, String encodedObjectName, EnumMap<UrlSigningOption.RequestOption, Object> storageOptionMap) {
        if (Strings.isNullOrEmpty(bucketSegment)) {
            if (Strings.isNullOrEmpty(encodedObjectName)) {
                return PATH_SEPARATOR;
            }
            if (encodedObjectName.startsWith(PATH_SEPARATOR)) {
                return encodedObjectName;
            }
            return PATH_SEPARATOR + encodedObjectName;
        }
        StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(PATH_SEPARATOR).append(bucketSegment);
        if (Strings.isNullOrEmpty(encodedObjectName)) {
            boolean v2Enabled = getPreferredSignatureVersion(storageOptionMap).equals(UrlSigningOption.SigningVersion.V2);
            // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
            // must end with a forward slash.
            if (storageOptionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && v2Enabled) {
                uriBuilder.append(PATH_SEPARATOR);
            }
            return uriBuilder.toString();
        }
        if (!encodedObjectName.startsWith(PATH_SEPARATOR)) {
            uriBuilder.append(PATH_SEPARATOR);
        }
        uriBuilder.append(encodedObjectName);
        return uriBuilder.toString();
    }

    private UrlSigningOption.SigningVersion getPreferredSignatureVersion(EnumMap<UrlSigningOption.RequestOption, Object> storageOptionMap) {
        // Check for an explicitly specified version in the map.
        for (UrlSigningOption.SigningVersion signingScheme : UrlSigningOption.SigningVersion.values()) {
            if (signingScheme.equals(storageOptionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION))) {
                return signingScheme;
            }
        }
        // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
        // Change this to V4 once we make it the default.
        return UrlSigningOption.SigningVersion.V2;
    }

    private boolean usePathStyleForSignedUrl(EnumMap<UrlSigningOption.RequestOption, Object> storageOptionMap) {
        // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
        // logic to return false unless PATH_STYLE was explicitly specified.
        if (storageOptionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
            return false;
        }
        return true;
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
    private SigningInfo createSignatureInfo(Map<UrlSigningOption.RequestOption, Object> storageOptionMap, BlobMetadata blobMetadata, long expiryTime, URI resourceUri, String serviceAccountEmail) {
        HttpRequestMethod httpMethod = storageOptionMap.containsKey(UrlSigningOption.RequestOption.HTTP_METHOD) ? (HttpRequestMethod) storageOptionMap.get(UrlSigningOption.RequestOption.HTTP_METHOD) : HttpRequestMethod.GET;
        SigningInfo.RequestSignatureBuilder signatureBuilder = new SigningInfo.RequestSignatureBuilder(httpMethod, expiryTime, resourceUri);
        if (firstNonNull((Boolean) storageOptionMap.get(UrlSigningOption.RequestOption.MD5), false)) {
            checkArgument(null != blobMetadata.getMd5(), "Blob is missing a value for md5");
            signatureBuilder.setContentMd5(blobMetadata.getMd5());
        }
        if (firstNonNull((Boolean) storageOptionMap.get(UrlSigningOption.RequestOption.CONTENT_TYPE), false)) {
            checkArgument(null != blobMetadata.getContentType(), "Blob is missing a value for content-type");
            signatureBuilder.setContentType(blobMetadata.getContentType());
        }
        signatureBuilder.setSignatureVersion((UrlSigningOption.SigningVersion) storageOptionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
        signatureBuilder.setAccountEmail(serviceAccountEmail);
        signatureBuilder.setTimestamp(getOptions().getClock().millisTime());
        ImmutableMap.Builder<String, String> extraHeadersBuilder = new ImmutableMap.Builder<String, String>();
        boolean v4Enabled = UrlSigningOption.SigningVersion.V4.equals(storageOptionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
        if (v4Enabled) {
            // We don't sign the host header for V2 signed URLs; only do this for V4.
            // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
            if (!storageOptionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
                if (storageOptionMap.containsKey(UrlSigningOption.RequestOption.HOST_NAME)) {
                    extraHeadersBuilder.put("host", getBaseStorageHostName(storageOptionMap));
                }
            } else {
                extraHeadersBuilder.put("host", getBucketNameWithoutSlashes(blobMetadata) + "." + getBaseStorageHostName(storageOptionMap));
            }
        }
        if (storageOptionMap.containsKey(UrlSigningOption.RequestOption.EXT_HEADERS)) {
            extraHeadersBuilder.putAll((Map<String, String>) storageOptionMap.get(UrlSigningOption.RequestOption.EXT_HEADERS));
        }
        ImmutableMap.Builder<String, String> paramsBuilder = new ImmutableMap.Builder<String, String>();
        if (storageOptionMap.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
            paramsBuilder.putAll((Map<String, String>) storageOptionMap.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
        }
        return signatureBuilder.setCanonicalizedExtensionHeaders((Map<String, String>) extraHeadersBuilder.build()).setCanonicalizedQueryParams((Map<String, String>) paramsBuilder.build()).buildSigningInfo();
    }

    private String getBucketNameWithoutSlashes(BlobMetadata blobMetadata) {
        // The bucket name itself should never contain a forward slash. However, parts already existed
        // in the code to check for this, so we remove the forward slashes to be safe here.
        return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
    }

    /**
     * Returns the hostname used to send requests to Cloud Storage, e.g. "storage.googleapis.com".
     */
    private String getBaseStorageHostName(Map<UrlSigningOption.RequestOption, Object> storageOptionMap) {
        String explicitBaseHost = (String) storageOptionMap.get(UrlSigningOption.RequestOption.HOST_NAME);
        if (!Strings.isNullOrEmpty(explicitBaseHost)) {
            return explicitBaseHost.replaceFirst("http(s)?://", "");
        }
        return STORAGE_XML_HOST;
    }

    @Override
    public List<StorageObject> get(BlobIdentifier... identifiers) {
        return get(Arrays.asList(identifiers));
    }

    @Override
    public List<StorageObject> get(Iterable<BlobIdentifier> identifiers) {
        StorageBatchOperation batchOp = batch();
        final List<StorageObject> storageResults = Lists.newArrayList();
        for (BlobIdentifier objectIdentifier : identifiers) {
            batchOp.get(objectIdentifier).notify(new BatchResult.Callback<StorageObject, StorageOperationException>() {

                @Override
                public void success(StorageObject result) {
                    storageResults.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    storageResults.add(null);
                }
            });
        }
        batchOp.submitBatch();
        return Collections.unmodifiableList(storageResults);
    }

    @Override
    public List<StorageObject> update(BlobMetadata... metadataArray) {
        return update(Arrays.asList(metadataArray));
    }

    @Override
    public List<StorageObject> update(Iterable<BlobMetadata> metadataArray) {
        StorageBatchOperation batchOp = batch();
        final List<StorageObject> storageResults = Lists.newArrayList();
        for (BlobMetadata blobMetadata : metadataArray) {
            batchOp.updateBlob(blobMetadata).notify(new BatchResult.Callback<StorageObject, StorageOperationException>() {

                @Override
                public void success(StorageObject result) {
                    storageResults.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    storageResults.add(null);
                }
            });
        }
        batchOp.submitBatch();
        return Collections.unmodifiableList(storageResults);
    }

    @Override
    public List<Boolean> delete(BlobIdentifier... identifiers) {
        return delete(Arrays.asList(identifiers));
    }

    @Override
    public List<Boolean> delete(Iterable<BlobIdentifier> identifiers) {
        StorageBatchOperation batchOp = batch();
        final List<Boolean> storageResults = Lists.newArrayList();
        for (BlobIdentifier objectIdentifier : identifiers) {
            batchOp.deleteBlob(objectIdentifier).notify(new BatchResult.Callback<Boolean, StorageOperationException>() {

                @Override
                public void success(Boolean result) {
                    storageResults.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    storageResults.add(Boolean.FALSE);
                }
            });
        }
        batchOp.submitBatch();
        return Collections.unmodifiableList(storageResults);
    }

    @Override
    public AccessControlEntry getAcl(final String containerName, final AccessControlEntry.AbstractEntity aclEntity, BucketFilterOption... storageSettings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
            BucketAccessControl resultBucket = runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return rpcClient.getAcl(containerName, aclEntity.toProto(), optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : AccessControlEntry.fromProto(resultBucket);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry getAcl(final String containerName, final AccessControlEntry.AbstractEntity aclEntity) {
        return getAcl(containerName, aclEntity, new BucketFilterOption[0]);
    }

    @Override
    public boolean deleteAcl(final String containerName, final AccessControlEntry.AbstractEntity aclEntity, BucketFilterOption... storageSettings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.deleteAcl(containerName, aclEntity.toProto(), optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public boolean deleteAcl(final String containerName, final AccessControlEntry.AbstractEntity aclEntity) {
        return deleteAcl(containerName, aclEntity, new BucketFilterOption[0]);
    }

    @Override
    public AccessControlEntry createAcl(String containerName, AccessControlEntry accessControlEntry, BucketFilterOption... storageSettings) {
        final BucketAccessControl accessControlProto = accessControlEntry.toBucketProto().setBucket(containerName);
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
            return AccessControlEntry.fromProto(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return rpcClient.createAcl(accessControlProto, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry createAcl(String containerName, AccessControlEntry accessControlEntry) {
        return createAcl(containerName, accessControlEntry, new BucketFilterOption[0]);
    }

    @Override
    public AccessControlEntry updateAcl(String containerName, AccessControlEntry accessControlEntry, BucketFilterOption... storageSettings) {
        final BucketAccessControl accessControlProto = accessControlEntry.toBucketProto().setBucket(containerName);
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
            return AccessControlEntry.fromProto(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return rpcClient.patchAcl(accessControlProto, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry updateAcl(String containerName, AccessControlEntry accessControlEntry) {
        return updateAcl(containerName, accessControlEntry, new BucketFilterOption[0]);
    }

    @Override
    public List<AccessControlEntry> listAcls(final String containerName, BucketFilterOption... storageSettings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
            List<BucketAccessControl> resultBucket = runWithRetries(new Callable<List<BucketAccessControl>>() {

                @Override
                public List<BucketAccessControl> call() {
                    return rpcClient.listAcls(containerName, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(resultBucket, AccessControlEntry.BUCKET_PB_TO_ACCESS_CONTROL_ENTRY);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public List<AccessControlEntry> listAcls(final String containerName) {
        return listAcls(containerName, new BucketFilterOption[0]);
    }

    @Override
    public AccessControlEntry getDefaultAcl(final String containerName, final AccessControlEntry.AbstractEntity aclEntity) {
        try {
            ObjectAccessControl resultBucket = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.getDefaultAcl(containerName, aclEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : AccessControlEntry.fromProto(resultBucket);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public boolean deleteDefaultAcl(final String containerName, final AccessControlEntry.AbstractEntity aclEntity) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.deleteDefaultAcl(containerName, aclEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry createDefaultAcl(String containerName, AccessControlEntry accessControlEntry) {
        final ObjectAccessControl accessControlProto = accessControlEntry.toObjectProto().setBucket(containerName);
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.createDefaultAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry updateDefaultAcl(String containerName, AccessControlEntry accessControlEntry) {
        final ObjectAccessControl accessControlProto = accessControlEntry.toObjectProto().setBucket(containerName);
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.patchDefaultAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public List<AccessControlEntry> listDefaultAcls(final String containerName) {
        try {
            List<ObjectAccessControl> resultBucket = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return rpcClient.listDefaultAcls(containerName);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(resultBucket, AccessControlEntry.OBJECT_PB_TO_ACCESS_CONTROL_ENTRY);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry getAcl(final BlobIdentifier objectIdentifier, final AccessControlEntry.AbstractEntity aclEntity) {
        try {
            ObjectAccessControl resultBucket = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.getAcl(objectIdentifier.getBucket(), objectIdentifier.getName(), objectIdentifier.getGeneration(), aclEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : AccessControlEntry.fromProto(resultBucket);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public boolean deleteAcl(final BlobIdentifier objectIdentifier, final AccessControlEntry.AbstractEntity aclEntity) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return rpcClient.deleteAcl(objectIdentifier.getBucket(), objectIdentifier.getName(), objectIdentifier.getGeneration(), aclEntity.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry createAcl(final BlobIdentifier objectIdentifier, final AccessControlEntry accessControlEntry) {
        final ObjectAccessControl accessControlProto = accessControlEntry.toObjectProto().setBucket(objectIdentifier.getBucket()).setObject(objectIdentifier.getName()).setGeneration(objectIdentifier.getGeneration());
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.createAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry updateAcl(BlobIdentifier objectIdentifier, AccessControlEntry accessControlEntry) {
        final ObjectAccessControl accessControlProto = accessControlEntry.toObjectProto().setBucket(objectIdentifier.getBucket()).setObject(objectIdentifier.getName()).setGeneration(objectIdentifier.getGeneration());
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return rpcClient.patchAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public List<AccessControlEntry> listAcls(final BlobIdentifier objectIdentifier) {
        try {
            List<ObjectAccessControl> resultBucket = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return rpcClient.listAcls(objectIdentifier.getBucket(), objectIdentifier.getName(), objectIdentifier.getGeneration());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(resultBucket, AccessControlEntry.OBJECT_PB_TO_ACCESS_CONTROL_ENTRY);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    public HmacSecretKey createHmacKey(final ServiceAccountInfo accountInfo, final HmacKeyCreationOption... storageSettings) {
        try {
            return HmacSecretKey.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKey>() {

                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                    return rpcClient.createHmacKey(accountInfo.getEmail(), buildOptionMap(storageSettings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public Page<HmacSecretKey.HmacKeyInfo> listHmacKeys(ListHmacKeysOptions... storageSettings) {
        return listAllHmacKeys(getOptions(), buildOptionMap(storageSettings));
    }

    @Override
    public HmacSecretKey.HmacKeyInfo getHmacKey(final String keyId, final HmacKeyRetrievalOption... storageSettings) {
        try {
            return HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return rpcClient.getHmacKey(keyId, buildOptionMap(storageSettings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    private HmacKeyInfo updateHmacKeyMetadata(final HmacKeyInfo keyInfo, final HmacKeyUpdateOption... storageSettings) {
        try {
            return HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return rpcClient.updateHmacKey(keyInfo.toProto(), buildOptionMap(storageSettings));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public HmacSecretKey.HmacKeyInfo updateHmacKeyState(final HmacKeyInfo keyInfo, final HmacSecretKey.HmacKeyStatus keyStatus, final HmacKeyUpdateOption... storageSettings) {
        HmacKeyInfo updatedInfo = HmacSecretKey.HmacKeyInfo.createBuilder(keyInfo.getServiceAccount()).setProjectId(keyInfo.getProjectId()).setAccessId(keyInfo.getAccessId()).setState(keyStatus).buildInfo();
        return updateHmacKeyMetadata(updatedInfo, storageSettings);
    }

    @Override
    public void deleteHmacKey(final HmacKeyInfo keyInfo, final HmacKeyDeleteOption... storageSettings) {
        try {
            runWithRetries(new Callable<Void>() {

                @Override
                public Void call() {
                    rpcClient.deleteHmacKey(keyInfo.toProto(), buildOptionMap(storageSettings));
                    return null;
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    private static Page<HmacKeyInfo> listAllHmacKeys(final StorageSettings serviceSettings, final Map<StorageRpcClient.StorageOption, ?> storageSettings) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> bucketTuple = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> call() {
                    return serviceSettings.getStorageRpcV1().listHmacKeys(storageSettings);
                }
            }, serviceSettings.getRetrySettings(), EXCEPTION_HANDLER, serviceSettings.getClock());
            String pageCursor = bucketTuple.x();
            final Iterable<HmacSecretKey.HmacKeyInfo> keyInfo = null == bucketTuple.y() ? ImmutableList.<HmacSecretKey.HmacKeyInfo>of() : Iterables.transform(bucketTuple.y(), new Function<com.google.api.services.storage.model.HmacKeyMetadata, HmacKeyInfo>() {

                @Override
                public HmacSecretKey.HmacKeyInfo apply(com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                    return HmacKeyInfo.fromProto(metadataPb);
                }
            });
            return new PageImpl<>(new HmacKeyMetadataPageRetriever(serviceSettings, storageSettings), pageCursor, keyInfo);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public Policy getIamPolicy(final String containerName, BucketFilterOption... storageSettings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
            return fromApiPolicy(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return rpcClient.getIamPolicy(containerName, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public Policy setIamPolicy(final String containerName, final Policy accessControl, BucketFilterOption... storageSettings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
            return fromApiPolicy(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return rpcClient.setIamPolicy(containerName, toApiPolicy(accessControl), optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public List<Boolean> testIamPermissions(final String containerName, final List<String> requestedPerms, BucketFilterOption... storageSettings) {
        try {
            final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(storageSettings);
            TestIamPermissionsResponse iamResult = runWithRetries(new Callable<TestIamPermissionsResponse>() {

                @Override
                public TestIamPermissionsResponse call() {
                    return rpcClient.testIamPermissions(containerName, requestedPerms, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            final Set<String> grantedPerms = null != iamResult.getPermissions() ? ImmutableSet.copyOf(iamResult.getPermissions()) : ImmutableSet.<String>of();
            return Lists.transform(requestedPerms, new Function<String, Boolean>() {

                @Override
                public Boolean apply(String permission) {
                    return grantedPerms.contains(permission);
                }
            });
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBucket lockRetentionPolicy(BucketMetadata bucketMetadata, BucketTargetOptions... storageSettings) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionMap = buildOptionMap(bucketMetadata, storageSettings);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return rpcClient.lockRetentionPolicy(bucketProto, optionMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    @Override
    public ServiceAccountInfo getServiceAccount(final String projectName) {
        try {
            com.google.api.services.storage.model.ServiceAccount resultBucket = runWithRetries(new Callable<com.google.api.services.storage.model.ServiceAccount>() {

                @Override
                public com.google.api.services.storage.model.ServiceAccount call() {
                    return rpcClient.getServiceAccount(projectName);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == resultBucket ? null : ServiceAccountInfo.fromProto(resultBucket);
        } catch (RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    private static <T> void addOptionToMap(StorageRpcClient.StorageOption urlSigningMode, T fallbackValue, Map<StorageRpcClient.StorageOption, Object> optionsStore) {
        addOptionToMap(urlSigningMode, urlSigningMode, fallbackValue, optionsStore);
    }

    private static <T> void addOptionToMap(StorageRpcClient.StorageOption requestedOption, StorageRpcClient.StorageOption targetOption, T fallbackValue, Map<StorageRpcClient.StorageOption, Object> optionsStore) {
        if (optionsStore.containsKey(requestedOption)) {
            @SuppressWarnings("unchecked")
            T resolvedValue = (T) optionsStore.remove(requestedOption);
            checkArgument(null != resolvedValue || null != fallbackValue, "Option " + requestedOption.getValue() + " is missing a value");
            resolvedValue = firstNonNull(resolvedValue, fallbackValue);
            optionsStore.put(targetOption, resolvedValue);
        }
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(Long genId, Long metaGen, Iterable<? extends AbstractOption> storageSettings) {
        return buildOptionMap(genId, metaGen, storageSettings, false);
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(Long genId, Long metaGen, Iterable<? extends AbstractOption> storageSettings, boolean treatAsSource) {
        Map<StorageRpcClient.StorageOption, Object> transientMap = Maps.newEnumMap(StorageRpcClient.StorageOption.class);
        for (AbstractOption urlSigningMode : storageSettings) {
            Object previousValue = transientMap.put(urlSigningMode.getRpcOption(), urlSigningMode.getValue());
            checkArgument(null == previousValue, "Duplicate option %s", urlSigningMode);
        }
        Boolean resolvedValue = (Boolean) transientMap.remove(DELIMITER);
        if (Boolean.TRUE.equals(resolvedValue)) {
            transientMap.put(DELIMITER, PATH_SEPARATOR);
        }
        if (!treatAsSource) {
            addOptionToMap(IF_GENERATION_MATCH, genId, transientMap);
            addOptionToMap(IF_GENERATION_NOT_MATCH, genId, transientMap);
            addOptionToMap(IF_METAGENERATION_MATCH, metaGen, transientMap);
            addOptionToMap(IF_METAGENERATION_NOT_MATCH, metaGen, transientMap);
        } else {
            addOptionToMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, genId, transientMap);
            addOptionToMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, genId, transientMap);
            addOptionToMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGen, transientMap);
            addOptionToMap(IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGen, transientMap);
        }
        return ImmutableMap.copyOf(transientMap);
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(AbstractOption... storageSettings) {
        return buildOptionMap(null, null, Arrays.asList(storageSettings));
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(Long genId, Long metaGen, AbstractOption... storageSettings) {
        return buildOptionMap(genId, metaGen, Arrays.asList(storageSettings));
    }

    private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BucketMetadata bucketMetadata, AbstractOption... storageSettings) {
        return buildOptionMap(null, bucketMetadata.getMetageneration(), storageSettings);
    }

    static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BlobMetadata blobMetadata, AbstractOption... storageSettings) {
        return buildOptionMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), storageSettings);
    }

    static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BlobIdentifier blobIdentifier, AbstractOption... storageSettings) {
        return buildOptionMap(blobIdentifier.getGeneration(), null, storageSettings);
    }
}
