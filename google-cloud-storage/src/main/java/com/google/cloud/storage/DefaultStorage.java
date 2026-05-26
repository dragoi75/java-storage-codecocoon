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
import static com.google.cloud.storage.PolicyConverter.buildPolicyFromApi;
import static com.google.cloud.storage.PolicyConverter.buildApiPolicy;
import static com.google.cloud.storage.SignedUrlEncoderHelper.rfc3986UriEncode;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.DELIMITER;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.IF_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.IF_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.IF_METAGENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.IF_SOURCE_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.IF_SOURCE_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.IF_SOURCE_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageServiceRpc.StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH;
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
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AccessControlEntry.TypedEntity;
import com.google.cloud.storage.FormPostPolicyV4.ConditionV4Operator;
import com.google.cloud.storage.FormPostPolicyV4.PostConditionsVersion4;
import com.google.cloud.storage.FormPostPolicyV4.PostFieldsMapV4;
import com.google.cloud.storage.FormPostPolicyV4.PostPolicyV4DocumentModel;
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

final class DefaultStorage extends BaseService<StorageClientOptions> implements CloudStorageClient {

    private static final byte[] ZERO_LENGTH_BYTES = {};

    private static final String MD5_EMPTY_HASH = "1B2M2Y8AsgTpgAmY7PhCfg==";

    private static final String CRC32C_EMPTY_HASH = "AAAAAA==";

    private static final String PATH_SEPARATOR = "/";

    /**
     * Signed URLs are only supported through the GCS XML API endpoint.
     */
    private static final String STORAGE_XML_SCHEME = "https";

    private static final String STORAGE_XML_HOST = "storage.googleapis.com";

    private static final int DEFAULT_IO_BUFFER_SIZE = 15 * 1024 * 1024;

    private static final int MINIMUM_BUFFER_SIZE = 256 * 1024;

    private static final Function<Tuple<CloudStorageClient, Boolean>, Boolean> DELETE_FUNCTION = new Function<Tuple<CloudStorageClient, Boolean>, Boolean>() {

        @Override
        public Boolean apply(Tuple<CloudStorageClient, Boolean> tuple) {
            return tuple.y();
        }
    };

    private final StorageServiceRpc storageServiceClient;

    private static class BucketPageRetriever implements NextPageFetcher<StorageBucket> {

        private static final long serialVersionUID = 5850406828803613729L;

        private final Map<StorageServiceRpc.StorageOption, ?> requestParams;

        private final StorageClientOptions clientOptions;

        @Override
        public Page<StorageBucket> getNextPage() {
            return listAllBuckets(clientOptions, requestParams);
        }

        BucketPageRetriever(StorageClientOptions clientOptions, String pageToken, Map<StorageServiceRpc.StorageOption, ?> optionsMap) {
            this.requestParams = PageImpl.nextRequestOptions(StorageServiceRpc.StorageOption.PAGE_TOKEN, pageToken, optionsMap);
            this.clientOptions = clientOptions;
        }

    }

    private static class BlobPageIterator implements NextPageFetcher<StorageBlob> {

        private static final long serialVersionUID = 81807334445874098L;

        private final Map<StorageServiceRpc.StorageOption, ?> requestParams;

        private final StorageClientOptions clientOptions;

        private final String containerName;

        @Override
        public Page<StorageBlob> getNextPage() {
            return listAllBlobs(containerName, clientOptions, requestParams);
        }

        BlobPageIterator(String containerName, StorageClientOptions clientOptions, String pageToken, Map<StorageServiceRpc.StorageOption, ?> optionsMap) {
            this.requestParams = PageImpl.nextRequestOptions(StorageServiceRpc.StorageOption.PAGE_TOKEN, pageToken, optionsMap);
            this.clientOptions = clientOptions;
            this.containerName = containerName;
        }

    }

    private static class HmacKeyMetadataPaginator implements NextPageFetcher<HmacSecretKey.HmacKeyInfo> {

        private static final long serialVersionUID = 308012320541700881L;

        private final StorageClientOptions clientOptions;

        private final Map<StorageServiceRpc.StorageOption, ?> clientParams;

        @Override
        public Page<HmacSecretKey.HmacKeyInfo> getNextPage() {
            return listAllHmacKeys(clientOptions, clientParams);
        }

        HmacKeyMetadataPaginator(StorageClientOptions clientOptions, Map<StorageServiceRpc.StorageOption, ?> clientParams) {
            this.clientOptions = clientOptions;
            this.clientParams = clientParams;
        }

    }

    @Override
    public HmacSecretKey.HmacKeyInfo getHmacKey(final String keyId, final GetHmacKeyRequestOption... clientParams) {
        try {
            return HmacSecretKey.HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return storageServiceClient.getHmacKey(keyId, buildOptionMap(clientParams));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    private String slashlessBucketNameFrom(BlobMetadata blobMetadata) {
        // The bucket name itself should never contain a forward slash. However, parts already existed
        // in the code to check for this, so we remove the forward slashes to be safe here.
        return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
    }

    @Override
    public StorageBlob createFrom(BlobMetadata blobMetadata, Path fileLocation, int chunkSize, BlobWriteOptions... clientParams) throws IOException {
        if (Files.isDirectory(fileLocation)) {
            throw new StorageServiceException(0, fileLocation + " is a directory");
        }
        try (InputStream sourceStream = Files.newInputStream(fileLocation)) {
            return createFrom(blobMetadata, sourceStream, chunkSize, clientParams);
        }
    }

    private static Page<HmacSecretKey.HmacKeyInfo> listAllHmacKeys(final StorageClientOptions clientOptions, final Map<StorageServiceRpc.StorageOption, ?> clientParams) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> fetchResult = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> call() {
                    return clientOptions.getStorageRpcV1().listHmacKeys(clientParams);
                }
            }, clientOptions.getRetrySettings(), EXCEPTION_HANDLER, clientOptions.getClock());
            String pageToken = fetchResult.x();
            final Iterable<HmacSecretKey.HmacKeyInfo> keyInfo = null == fetchResult.y() ? ImmutableList.<HmacSecretKey.HmacKeyInfo>of() : Iterables.transform(fetchResult.y(), new Function<com.google.api.services.storage.model.HmacKeyMetadata, HmacSecretKey.HmacKeyInfo>() {

                @Override
                public HmacSecretKey.HmacKeyInfo apply(com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                    return HmacSecretKey.HmacKeyInfo.fromProto(metadataPb);
                }
            });
            return new PageImpl<>(new HmacKeyMetadataPaginator(clientOptions, clientParams), pageToken, keyInfo);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    private static Page<StorageBucket> listAllBuckets(final StorageClientOptions clientOptions, final Map<StorageServiceRpc.StorageOption, ?> optionsMapping) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> fetchResult = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> call() {
                    return clientOptions.getStorageRpcV1().list(optionsMapping);
                }
            }, clientOptions.getRetrySettings(), EXCEPTION_HANDLER, clientOptions.getClock());
            String pageToken = fetchResult.x();
            Iterable<StorageBucket> storageEntries = null == fetchResult.y() ? ImmutableList.<StorageBucket>of() : Iterables.transform(fetchResult.y(), new Function<com.google.api.services.storage.model.Bucket, StorageBucket>() {

                @Override
                public StorageBucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                    return StorageBucket.fromProto(clientOptions.getService(), bucketPb);
                }
            });
            return new PageImpl<>(new BucketPageRetriever(clientOptions, pageToken, optionsMapping), pageToken, storageEntries);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    static Map<StorageServiceRpc.StorageOption, ?> buildOptionMap(BlobIdentifier blobIdentifier, OptionDescriptor... clientParams) {
        return buildOptionMap(blobIdentifier.getGeneration(), null, clientParams);
    }

    private static Map<StorageServiceRpc.StorageOption, ?> buildOptionMap(OptionDescriptor... clientParams) {
        return buildOptionMap(null, null, Arrays.asList(clientParams));
    }

    @Override
    public AccessControlEntry createAcl(String containerName, AccessControlEntry accessEntry, BucketSourceRequestOption... clientParams) {
        final BucketAccessControl accessControlProto = accessEntry.toBucketProto().setBucket(containerName);
        try {
            final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
            return AccessControlEntry.fromProto(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return storageServiceClient.createAcl(accessControlProto, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public Page<HmacSecretKey.HmacKeyInfo> listHmacKeys(HmacKeysListOption... clientParams) {
        return listAllHmacKeys(getOptions(), buildOptionMap(clientParams));
    }

    @Override
    public boolean deleteAcl(final String containerName, final TypedEntity principal, BucketSourceRequestOption... clientParams) {
        try {
            final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageServiceClient.deleteAcl(containerName, principal.toProto(), optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    private static Map<StorageServiceRpc.StorageOption, ?> buildOptionMap(Long genNumber, Long metaGen, OptionDescriptor... clientParams) {
        return buildOptionMap(genNumber, metaGen, Arrays.asList(clientParams));
    }

    private static Page<StorageBlob> listAllBlobs(final String containerName, final StorageClientOptions clientOptions, final Map<StorageServiceRpc.StorageOption, ?> optionsMapping) {
        try {
            Tuple<String, Iterable<StorageObject>> fetchResult = runWithRetries(new Callable<Tuple<String, Iterable<StorageObject>>>() {

                @Override
                public Tuple<String, Iterable<StorageObject>> call() {
                    return clientOptions.getStorageRpcV1().list(containerName, optionsMapping);
                }
            }, clientOptions.getRetrySettings(), EXCEPTION_HANDLER, clientOptions.getClock());
            String pageToken = fetchResult.x();
            Iterable<StorageBlob> storageEntries = null == fetchResult.y() ? ImmutableList.<StorageBlob>of() : Iterables.transform(fetchResult.y(), new Function<StorageObject, StorageBlob>() {

                @Override
                public StorageBlob apply(StorageObject storageObject) {
                    return StorageBlob.fromProto(clientOptions.getService(), storageObject);
                }
            });
            return new PageImpl<>(new BlobPageIterator(containerName, clientOptions, pageToken, optionsMapping), pageToken, storageEntries);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public BlobUploadChannel writer(BlobMetadata blobMetadata, BlobWriteOptions... clientParams) {
        Tuple<BlobMetadata, BlobUploadOptions[]> blobTargetPair = BlobUploadOptions.convertToTargetOptions(blobMetadata, clientParams);
        return newWriter(blobTargetPair.x(), blobTargetPair.y());
    }

    @Override
    public StorageBlob get(BlobIdentifier objectName, BlobGetOptions... clientParams) {
        final StorageObject persistedObject = objectName.toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(objectName, clientParams);
        try {
            StorageObject storedItem = runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return storageServiceClient.get(persistedObject, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == storedItem ? null : StorageBlob.fromProto(this, storedItem);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBlob get(BlobIdentifier objectName) {
        return get(objectName, new BlobGetOptions[0]);
    }

    @Override
    public StorageBlob create(BlobMetadata blobMetadata, BlobUploadOptions... clientParams) {
        BlobMetadata updatedMetadata = blobMetadata.asBuilder().setMd5(MD5_EMPTY_HASH).setCrc32c(CRC32C_EMPTY_HASH).buildObject();
        return createInternal(updatedMetadata, ZERO_LENGTH_BYTES, 0, 0, clientParams);
    }

    @Override
    public List<StorageBlob> get(BlobIdentifier... identifiers) {
        return get(Arrays.asList(identifiers));
    }

    @Override
    public AccessControlEntry createAcl(String containerName, AccessControlEntry accessEntry) {
        return createAcl(containerName, accessEntry, new BucketSourceRequestOption[0]);
    }

    DefaultStorage(StorageClientOptions clientParams) {
        super(clientParams);
        storageServiceClient = clientParams.getStorageRpcV1();
    }

    private static <T> void putOptionIntoMap(StorageServiceRpc.StorageOption requestedOption, StorageServiceRpc.StorageOption optionToStore, T fallbackValue, Map<StorageServiceRpc.StorageOption, Object> storageOptionLookup) {
        if (storageOptionLookup.containsKey(requestedOption)) {
            @SuppressWarnings("unchecked")
            T resolvedVal = (T) storageOptionLookup.remove(requestedOption);
            checkArgument(null != resolvedVal || null != fallbackValue, "Option " + requestedOption.getValue() + " is missing a value");
            resolvedVal = firstNonNull(resolvedVal, fallbackValue);
            storageOptionLookup.put(optionToStore, resolvedVal);
        }
    }

    @Override
    public boolean deleteAcl(final String containerName, final TypedEntity principal) {
        return deleteAcl(containerName, principal, new BucketSourceRequestOption[0]);
    }

    private String buildResourceUriPath(String sanitizedBucketName, String escapedObjectName, EnumMap<UrlSigningOption.ServiceOption, Object> optionsMap) {
        if (Strings.isNullOrEmpty(sanitizedBucketName)) {
            if (Strings.isNullOrEmpty(escapedObjectName)) {
                return PATH_SEPARATOR;
            }
            if (escapedObjectName.startsWith(PATH_SEPARATOR)) {
                return escapedObjectName;
            }
            return PATH_SEPARATOR + escapedObjectName;
        }
        StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(PATH_SEPARATOR).append(sanitizedBucketName);
        if (Strings.isNullOrEmpty(escapedObjectName)) {
            boolean useVersion2 = getPreferredSignatureVersion(optionsMap).equals(UrlSigningOption.SignatureSchemeVersion.V2);
            // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
            // must end with a forward slash.
            if (optionsMap.containsKey(UrlSigningOption.ServiceOption.VIRTUAL_HOSTED_STYLE) && useVersion2) {
                uriBuilder.append(PATH_SEPARATOR);
            }
            return uriBuilder.toString();
        }
        uriBuilder.append(PATH_SEPARATOR);
        uriBuilder.append(escapedObjectName);
        return uriBuilder.toString();
    }

    @Override
    public List<StorageBlob> update(Iterable<BlobMetadata> metadataArray) {
        StorageOperationBatch operationBatch = batch();
        final List<StorageBlob> retrievedBlobs = Lists.newArrayList();
        for (BlobMetadata blobMetadata : metadataArray) {
            operationBatch.patch(blobMetadata).notify(new BatchResult.Callback<StorageBlob, StorageServiceException>() {

                @Override
                public void success(StorageBlob result) {
                    retrievedBlobs.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    retrievedBlobs.add(null);
                }
            });
        }
        operationBatch.execute();
        return Collections.unmodifiableList(retrievedBlobs);
    }

    @Override
    public ServiceAccountIdentity getServiceAccount(final String projectIdentifier) {
        try {
            com.google.api.services.storage.model.ServiceAccount result = runWithRetries(new Callable<com.google.api.services.storage.model.ServiceAccount>() {

                @Override
                public com.google.api.services.storage.model.ServiceAccount call() {
                    return storageServiceClient.getServiceAccount(projectIdentifier);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == result ? null : ServiceAccountIdentity.fromServiceAccountModel(result);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public List<StorageBlob> update(BlobMetadata... metadataArray) {
        return update(Arrays.asList(metadataArray));
    }

    @Override
    public StorageBucket get(String containerName, BucketGetOptions... clientParams) {
        final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.ofName(containerName).toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
        try {
            com.google.api.services.storage.model.Bucket result = runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return storageServiceClient.get(bucketProto, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == result ? null : StorageBucket.fromProto(this, result);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry getAcl(final BlobIdentifier objectName, final TypedEntity principal) {
        try {
            ObjectAccessControl result = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageServiceClient.getAcl(objectName.getBucket(), objectName.getName(), objectName.getGeneration(), principal.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == result ? null : AccessControlEntry.fromProto(result);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    public FormPostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobMetadata, long expiryDuration, TimeUnit timeScale, PostConditionsVersion4 constraints, PostPolicyV4Parameter... clientParams) {
        return generateSignedPostPolicyV4(blobMetadata, expiryDuration, timeScale, PostFieldsMapV4.builder().create(), constraints, clientParams);
    }

    private static Map<StorageServiceRpc.StorageOption, ?> buildOptionMap(Long genNumber, Long metaGen, Iterable<? extends OptionDescriptor> clientParams) {
        return buildOptionMap(genNumber, metaGen, clientParams, false);
    }

    @Override
    public StorageBucket create(BucketInfo bucketMetadata, BucketTargetOptions... clientParams) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(bucketMetadata, clientParams);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return storageServiceClient.create(bucketProto, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    private static Map<StorageServiceRpc.StorageOption, ?> buildOptionMap(Long genNumber, Long metaGen, Iterable<? extends OptionDescriptor> clientParams, boolean treatAsSource) {
        Map<StorageServiceRpc.StorageOption, Object> intermediateMap = Maps.newEnumMap(StorageServiceRpc.StorageOption.class);
        for (OptionDescriptor signingChoice : clientParams) {
            Object previousValue = intermediateMap.put(signingChoice.getRpcOption(), signingChoice.getValue());
            checkArgument(null == previousValue, "Duplicate option %s", signingChoice);
        }
        if (!Boolean.TRUE.equals(intermediateMap.get(DELIMITER))) {
            if (intermediateMap.get(DELIMITER) != null) {
                intermediateMap.put(DELIMITER, intermediateMap.get(DELIMITER));
            }
        } else {
            intermediateMap.remove(DELIMITER);
            intermediateMap.put(DELIMITER, PATH_SEPARATOR);
        }
        if (!treatAsSource) {
            putOptionIntoMap(IF_GENERATION_MATCH, genNumber, intermediateMap);
            putOptionIntoMap(IF_GENERATION_NOT_MATCH, genNumber, intermediateMap);
            putOptionIntoMap(IF_METAGENERATION_MATCH, metaGen, intermediateMap);
            putOptionIntoMap(IF_METAGENERATION_NOT_MATCH, metaGen, intermediateMap);
        } else {
            putOptionIntoMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, genNumber, intermediateMap);
            putOptionIntoMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, genNumber, intermediateMap);
            putOptionIntoMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGen, intermediateMap);
            putOptionIntoMap(IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGen, intermediateMap);
        }
        return ImmutableMap.copyOf(intermediateMap);
    }

    @Override
    public BlobCopyWriter copy(final CopyOperationRequest copyOperation) {
        final StorageObject originObject = copyOperation.getSource().toProto();
        final Map<StorageServiceRpc.StorageOption, ?> originOptionMap = buildOptionMap(copyOperation.getSource().getGeneration(), null, copyOperation.getSourceOptions(), true);
        final StorageObject destinationObject = copyOperation.getTarget().toProto();
        final Map<StorageServiceRpc.StorageOption, ?> blobTargetPair = buildOptionMap(copyOperation.getTarget().getGeneration(), copyOperation.getTarget().getMetageneration(), copyOperation.getTargetOptions());
        try {
            StorageServiceRpc.RewriteResult rewriteResult = runWithRetries(new Callable<StorageServiceRpc.RewriteResult>() {

                @Override
                public StorageServiceRpc.RewriteResult call() {
                    return storageServiceClient.openRewrite(new StorageServiceRpc.ObjectRewriteRequest(originObject, originOptionMap, copyOperation.getOverrideInfo(), destinationObject, blobTargetPair, copyOperation.getMegabytesCopiedPerChunk()));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return new BlobCopyWriter(getOptions(), rewriteResult);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public HmacSecretKey.HmacKeyInfo updateHmacKeyState(final HmacSecretKey.HmacKeyInfo keyInfo, final HmacSecretKey.HmacKeyStatus keyStatus, final UpdateHmacKeyRpcOption... clientParams) {
        HmacSecretKey.HmacKeyInfo modifiedInfo = HmacSecretKey.HmacKeyInfo.newServiceAccountKeyBuilder(keyInfo.getServiceAccount()).setProjectId(keyInfo.getProjectId()).setAccessId(keyInfo.getAccessId()).setState(keyStatus).buildHmacKeyInfo();
        return modifyHmacKey(modifiedInfo, clientParams);
    }

    @Override
    @Deprecated
    public StorageBlob create(BlobMetadata blobMetadata, InputStream dataBytes, BlobWriteOptions... clientParams) {
        Tuple<BlobMetadata, BlobUploadOptions[]> blobTargetPair = BlobUploadOptions.convertToTargetOptions(blobMetadata, clientParams);
        StorageObject blobProto = blobTargetPair.x().toProto();
        Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(blobTargetPair.x(), blobTargetPair.y());
        InputStream inStream = firstNonNull(dataBytes, new ByteArrayInputStream(ZERO_LENGTH_BYTES));
        // retries are not safe when the input is an InputStream, so we can't retry.
        return StorageBlob.fromProto(this, storageServiceClient.create(blobProto, inStream, optionsMapping));
    }

    @Override
    public StorageBlob get(String containerName, String objectName, BlobGetOptions... clientParams) {
        return get(BlobIdentifier.create(containerName, objectName), clientParams);
    }

    @Override
    public List<Boolean> testIamPermissions(final String containerName, final List<String> requestedPerms, BucketSourceRequestOption... clientParams) {
        try {
            final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
            TestIamPermissionsResponse iamPermissionsResult = runWithRetries(new Callable<TestIamPermissionsResponse>() {

                @Override
                public TestIamPermissionsResponse call() {
                    return storageServiceClient.testIamPermissions(containerName, requestedPerms, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            final Set<String> retainedPermissions = null != iamPermissionsResult.getPermissions() ? ImmutableSet.copyOf(iamPermissionsResult.getPermissions()) : ImmutableSet.<String>of();
            return Lists.transform(requestedPerms, new Function<String, Boolean>() {

                @Override
                public Boolean apply(String permission) {
                    return retainedPermissions.contains(permission);
                }
            });
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry getAcl(final String containerName, final TypedEntity principal) {
        return getAcl(containerName, principal, new BucketSourceRequestOption[0]);
    }

    @Override
    public AccessControlEntry createDefaultAcl(String containerName, AccessControlEntry accessEntry) {
        final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(containerName);
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageServiceClient.createDefaultAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    public HmacSecretKey createHmacKey(final ServiceAccountIdentity accountIdentity, final HmacKeyCreationOption... clientParams) {
        try {
            return HmacSecretKey.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKey>() {

                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                    return storageServiceClient.createHmacKey(accountIdentity.getEmail(), buildOptionMap(clientParams));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public Policy setIamPolicy(final String containerName, final Policy policyJson, BucketSourceRequestOption... clientParams) {
        try {
            final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
            return buildPolicyFromApi(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return storageServiceClient.setIamPolicy(containerName, buildApiPolicy(policyJson), optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBucket lockRetentionPolicy(BucketInfo bucketMetadata, BucketTargetOptions... clientParams) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(bucketMetadata, clientParams);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return storageServiceClient.lockRetentionPolicy(bucketProto, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    private boolean usePathStyleForSignedUrl(EnumMap<UrlSigningOption.ServiceOption, Object> optionsMap) {
        // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
        // logic to return false unless PATH_STYLE was explicitly specified.
        if (optionsMap.containsKey(UrlSigningOption.ServiceOption.VIRTUAL_HOSTED_STYLE) || optionsMap.containsKey(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME)) {
            return false;
        }
        return true;
    }

    @Override
    public StorageBlob createFrom(BlobMetadata blobMetadata, InputStream dataBytes, int chunkSize, BlobWriteOptions... clientParams) throws IOException {
        BlobUploadChannel uploadSession;
        try (WriteChannel outputChannel = writer(blobMetadata, clientParams)) {
            uploadSession = (BlobUploadChannel) outputChannel;
            uploadStreamHelper(Channels.newChannel(dataBytes), outputChannel, chunkSize);
        }
        StorageObject storageProto = uploadSession.getStorageObject();
        return StorageBlob.fromProto(this, storageProto);
    }

    @Override
    public StorageBlob create(BlobMetadata blobMetadata, byte[] dataBytes, int startIndex, int totalCount, BlobUploadOptions... clientParams) {
        dataBytes = firstNonNull(dataBytes, ZERO_LENGTH_BYTES);
        BlobMetadata updatedMetadata = blobMetadata.asBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(dataBytes, startIndex, totalCount).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(dataBytes, startIndex, totalCount).asInt()))).buildObject();
        return createInternal(updatedMetadata, dataBytes, startIndex, totalCount, clientParams);
    }

    @Override
    public List<StorageBlob> get(Iterable<BlobIdentifier> identifiers) {
        StorageOperationBatch operationBatch = batch();
        final List<StorageBlob> retrievedBlobs = Lists.newArrayList();
        for (BlobIdentifier objectName : identifiers) {
            operationBatch.get(objectName).notify(new BatchResult.Callback<StorageBlob, StorageServiceException>() {

                @Override
                public void success(StorageBlob result) {
                    retrievedBlobs.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    retrievedBlobs.add(null);
                }
            });
        }
        operationBatch.execute();
        return Collections.unmodifiableList(retrievedBlobs);
    }

    private static <T> void putOptionIntoMap(StorageServiceRpc.StorageOption signingChoice, T fallbackValue, Map<StorageServiceRpc.StorageOption, Object> storageOptionLookup) {
        putOptionIntoMap(signingChoice, signingChoice, fallbackValue, storageOptionLookup);
    }

    @Override
    public AccessControlEntry updateAcl(String containerName, AccessControlEntry accessEntry) {
        return updateAcl(containerName, accessEntry, new BucketSourceRequestOption[0]);
    }

    @Override
    public List<Boolean> delete(BlobIdentifier... identifiers) {
        return delete(Arrays.asList(identifiers));
    }

    private static Map<StorageServiceRpc.StorageOption, ?> buildOptionMap(BucketInfo bucketMetadata, OptionDescriptor... clientParams) {
        return buildOptionMap(null, bucketMetadata.getMetageneration(), clientParams);
    }

    @Override
    public Policy getIamPolicy(final String containerName, BucketSourceRequestOption... clientParams) {
        try {
            final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
            return buildPolicyFromApi(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return storageServiceClient.getIamPolicy(containerName, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBlob compose(final ComposeBlobsRequest composeOperation) {
        final List<StorageObject> sourceList = Lists.newArrayListWithCapacity(composeOperation.getSourceBlobs().size());
        for (ComposeBlobsRequest.VersionedSourceBlob versionedSource : composeOperation.getSourceBlobs()) {
            sourceList.add(BlobMetadata.newBuilder(BlobIdentifier.create(composeOperation.getTarget().getBucket(), versionedSource.getName(), versionedSource.getGeneration())).buildObject().toProto());
        }
        final StorageObject destinationObject = composeOperation.getTarget().toProto();
        final Map<StorageServiceRpc.StorageOption, ?> blobTargetPair = buildOptionMap(composeOperation.getTarget().getGeneration(), composeOperation.getTarget().getMetageneration(), composeOperation.getTargetOptions());
        try {
            return StorageBlob.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return storageServiceClient.compose(sourceList, destinationObject, blobTargetPair);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public List<AccessControlEntry> listAcls(final BlobIdentifier objectName) {
        try {
            List<ObjectAccessControl> result = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return storageServiceClient.listAcls(objectName.getBucket(), objectName.getName(), objectName.getGeneration());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(result, AccessControlEntry.OBJECT_PB_TO_ENTRY_FN);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public boolean delete(BlobIdentifier objectName) {
        return delete(objectName, new BlobReadOption[0]);
    }

    @Override
    public List<Boolean> delete(Iterable<BlobIdentifier> identifiers) {
        StorageOperationBatch operationBatch = batch();
        final List<Boolean> retrievedBlobs = Lists.newArrayList();
        for (BlobIdentifier objectName : identifiers) {
            operationBatch.remove(objectName).notify(new BatchResult.Callback<Boolean, StorageServiceException>() {

                @Override
                public void success(Boolean result) {
                    retrievedBlobs.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    retrievedBlobs.add(Boolean.FALSE);
                }
            });
        }
        operationBatch.execute();
        return Collections.unmodifiableList(retrievedBlobs);
    }

    @Override
    public byte[] readAllBytes(String containerName, String objectName, BlobReadOption... clientParams) {
        return readAllBytes(BlobIdentifier.create(containerName, objectName), clientParams);
    }

    @Override
    public boolean deleteDefaultAcl(final String containerName, final TypedEntity principal) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageServiceClient.deleteDefaultAcl(containerName, principal.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public Page<StorageBlob> list(final String containerName, BlobListOptions... clientParams) {
        return listAllBlobs(containerName, getOptions(), buildOptionMap(clientParams));
    }

    @Override
    public boolean delete(BlobIdentifier objectName, BlobReadOption... clientParams) {
        final StorageObject storedItem = objectName.toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(objectName, clientParams);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageServiceClient.delete(storedItem, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public List<AccessControlEntry> listDefaultAcls(final String containerName) {
        try {
            List<ObjectAccessControl> result = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return storageServiceClient.listDefaultAcls(containerName);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(result, AccessControlEntry.OBJECT_PB_TO_ENTRY_FN);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBlob create(BlobMetadata blobMetadata, byte[] dataBytes, BlobUploadOptions... clientParams) {
        dataBytes = firstNonNull(dataBytes, ZERO_LENGTH_BYTES);
        BlobMetadata updatedMetadata = blobMetadata.asBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(dataBytes).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(dataBytes).asInt()))).buildObject();
        return createInternal(updatedMetadata, dataBytes, 0, dataBytes.length, clientParams);
    }

    @Override
    public List<AccessControlEntry> listAcls(final String containerName, BucketSourceRequestOption... clientParams) {
        try {
            final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
            List<BucketAccessControl> result = runWithRetries(new Callable<List<BucketAccessControl>>() {

                @Override
                public List<BucketAccessControl> call() {
                    return storageServiceClient.listAcls(containerName, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(result, AccessControlEntry.BUCKET_PB_TO_ENTRY_FN);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public void deleteHmacKey(final HmacSecretKey.HmacKeyInfo keyInfo, final DeleteHmacKeyOptions... clientParams) {
        try {
            runWithRetries(new Callable<Void>() {

                @Override
                public Void call() {
                    storageServiceClient.deleteHmacKey(keyInfo.toProto(), buildOptionMap(clientParams));
                    return null;
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    static Map<StorageServiceRpc.StorageOption, ?> buildOptionMap(BlobMetadata blobMetadata, OptionDescriptor... clientParams) {
        return buildOptionMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), clientParams);
    }

    @Override
    public AccessControlEntry updateAcl(String containerName, AccessControlEntry accessEntry, BucketSourceRequestOption... clientParams) {
        final BucketAccessControl accessControlProto = accessEntry.toBucketProto().setBucket(containerName);
        try {
            final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
            return AccessControlEntry.fromProto(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return storageServiceClient.patchAcl(accessControlProto, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry updateAcl(BlobIdentifier objectName, AccessControlEntry accessEntry) {
        final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(objectName.getBucket()).setObject(objectName.getName()).setGeneration(objectName.getGeneration());
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageServiceClient.patchAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public ReadChannel reader(BlobIdentifier objectName, BlobReadOption... clientParams) {
        Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(objectName, clientParams);
        return new BlobInputChannel(getOptions(), objectName, optionsMapping);
    }

    @Override
    public ReadChannel reader(String containerName, String objectName, BlobReadOption... clientParams) {
        Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
        return new BlobInputChannel(getOptions(), BlobIdentifier.create(containerName, objectName), optionsMapping);
    }

    @Override
    public StorageBlob update(BlobMetadata blobMetadata, BlobUploadOptions... clientParams) {
        final StorageObject storedItem = blobMetadata.toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(blobMetadata, clientParams);
        try {
            return StorageBlob.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return storageServiceClient.patch(storedItem, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    private HmacSecretKey.HmacKeyInfo modifyHmacKey(final HmacSecretKey.HmacKeyInfo keyInfo, final UpdateHmacKeyRpcOption... clientParams) {
        try {
            return HmacSecretKey.HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return storageServiceClient.updateHmacKey(keyInfo.toProto(), buildOptionMap(clientParams));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public boolean delete(String containerName, BucketSourceRequestOption... clientParams) {
        final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.ofName(containerName).toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageServiceClient.delete(bucketProto, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public AccessControlEntry getDefaultAcl(final String containerName, final TypedEntity principal) {
        try {
            ObjectAccessControl result = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageServiceClient.getDefaultAcl(containerName, principal.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == result ? null : AccessControlEntry.fromProto(result);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBucket update(BucketInfo bucketMetadata, BucketTargetOptions... clientParams) {
        final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(bucketMetadata, clientParams);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return storageServiceClient.patch(bucketProto, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBlob createFrom(BlobMetadata blobMetadata, InputStream dataBytes, BlobWriteOptions... clientParams) throws IOException {
        return createFrom(blobMetadata, dataBytes, DEFAULT_IO_BUFFER_SIZE, clientParams);
    }

    private BlobUploadChannel newWriter(BlobMetadata blobMetadata, BlobUploadOptions... clientParams) {
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(blobMetadata, clientParams);
        return new BlobUploadChannel(getOptions(), blobMetadata, optionsMapping);
    }

    @Override
    public boolean deleteAcl(final BlobIdentifier objectName, final TypedEntity principal) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageServiceClient.deleteAcl(objectName.getBucket(), objectName.getName(), objectName.getGeneration(), principal.toProto());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBlob update(BlobMetadata blobMetadata) {
        return update(blobMetadata, new BlobUploadOptions[0]);
    }

    @Override
    public List<AccessControlEntry> listAcls(final String containerName) {
        return listAcls(containerName, new BucketSourceRequestOption[0]);
    }

    @Override
    public AccessControlEntry updateDefaultAcl(String containerName, AccessControlEntry accessEntry) {
        final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(containerName);
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageServiceClient.patchDefaultAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public FormPostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobMetadata, long expiryDuration, TimeUnit timeScale, PostFieldsMapV4 formData, PostConditionsVersion4 constraints, PostPolicyV4Parameter... clientParams) {
        EnumMap<UrlSigningOption.ServiceOption, Object> optionsMap = Maps.newEnumMap(UrlSigningOption.ServiceOption.class);
        // Convert to a map of SignUrlOptions so we can re-use some utility methods
        for (PostPolicyV4Parameter signingChoice : clientParams) {
            optionsMap.put(UrlSigningOption.ServiceOption.valueOf(signingChoice.getOption().name()), signingChoice.getValue());
        }
        optionsMap.put(UrlSigningOption.ServiceOption.SIGNATURE_VERSION, UrlSigningOption.SignatureSchemeVersion.V4);
        ServiceAccountSigner signer = (ServiceAccountSigner) optionsMap.get(UrlSigningOption.ServiceOption.SERVICE_ACCOUNT_CRED);
        if (null == signer) {
            checkState(this.getOptions().getCredentials() instanceof ServiceAccountSigner, "Signing key was not provided and could not be derived");
            signer = (ServiceAccountSigner) this.getOptions().getCredentials();
        }
        checkArgument(!(optionsMap.containsKey(UrlSigningOption.ServiceOption.VIRTUAL_HOSTED_STYLE) && optionsMap.containsKey(UrlSigningOption.ServiceOption.PATH_STYLE) && optionsMap.containsKey(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME)), "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be" + " specified.");
        String containerName = slashlessBucketNameFrom(blobMetadata);
        boolean enablePathStyle = usePathStyleForSignedUrl(optionsMap);
        String endpoint;
        if (!enablePathStyle) {
            endpoint = STORAGE_XML_SCHEME + "://" + containerName + "." + STORAGE_XML_HOST + "/";
        } else {
            endpoint = STORAGE_XML_SCHEME + "://" + STORAGE_XML_HOST + "/" + containerName + "/";
        }
        if (optionsMap.containsKey(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME)) {
            endpoint = optionsMap.get(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME) + "/";
        }
        SimpleDateFormat isoDateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat expiryFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        isoDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        expiryFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        long epochMillis = getOptions().getClock().millisTime();
        String dateStr = isoDateFormat.format(epochMillis);
        String signingKey = signer.getAccount() + "/" + ymdFormatter.format(epochMillis) + "/auto/storage/goog4_request";
        Map<String, String> policyMap = new HashMap<>();
        PostConditionsVersion4.ConditionsBuilder conditionBuilder = constraints.asBuilder();
        for (Map.Entry<String, String> mapEntry : formData.getFieldsMap().entrySet()) {
            // Every field needs a corresponding policy condition, so add them if they're missing
            conditionBuilder.addCustom(ConditionV4Operator.MATCHES, mapEntry.getKey(), mapEntry.getValue());
            policyMap.put(mapEntry.getKey(), mapEntry.getValue());
        }
        PostConditionsVersion4 conditionsV4 = conditionBuilder.addBucket(ConditionV4Operator.MATCHES, blobMetadata.getBucket()).addKey(ConditionV4Operator.MATCHES, blobMetadata.getName()).addCustom(ConditionV4Operator.MATCHES, "x-goog-date", dateStr).addCustom(ConditionV4Operator.MATCHES, "x-goog-credential", signingKey).addCustom(ConditionV4Operator.MATCHES, "x-goog-algorithm", "GOOG4-RSA-SHA256").create();
        PostPolicyV4DocumentModel doc = PostPolicyV4DocumentModel.create(expiryFormat.format(epochMillis + timeScale.toMillis(expiryDuration)), conditionsV4);
        String policyJson = BaseEncoding.base64().encode(doc.toJsonString().getBytes());
        String sigValue = BaseEncoding.base16().encode(signer.sign(policyJson.getBytes())).toLowerCase();
        for (FormPostPolicyV4.BinaryConditionV4 binaryCondition : conditionsV4.getConditions()) {
            if (ConditionV4Operator.MATCHES == binaryCondition.type) {
                policyMap.put(binaryCondition.operand1, binaryCondition.operand2);
            }
        }
        policyMap.put("key", blobMetadata.getName());
        policyMap.put("x-goog-credential", signingKey);
        policyMap.put("x-goog-algorithm", "GOOG4-RSA-SHA256");
        policyMap.put("x-goog-date", dateStr);
        policyMap.put("x-goog-signature", sigValue);
        policyMap.put("policy", policyJson);
        policyMap.remove("bucket");
        return FormPostPolicyV4.create(endpoint, policyMap);
    }

    @Override
    public byte[] readAllBytes(BlobIdentifier objectName, BlobReadOption... clientParams) {
        final StorageObject storedItem = objectName.toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(objectName, clientParams);
        try {
            return runWithRetries(new Callable<byte[]>() {

                @Override
                public byte[] call() {
                    return storageServiceClient.load(storedItem, optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public Page<StorageBucket> list(BucketListOptions... clientParams) {
        return listAllBuckets(getOptions(), buildOptionMap(clientParams));
    }

    @Override
    public boolean delete(String containerName, String objectName, BlobReadOption... clientParams) {
        return delete(BlobIdentifier.create(containerName, objectName), clientParams);
    }

    /*
   * Uploads the given content to the storage using specified write channel and the given buffer
   * size. This method does not close any channels.
   */
    private static void uploadStreamHelper(ReadableByteChannel inputChannel, WriteChannel outputChannel, int chunkSize) throws IOException {
        chunkSize = Math.max(chunkSize, MINIMUM_BUFFER_SIZE);
        ByteBuffer byteContainer = ByteBuffer.allocate(chunkSize);
        outputChannel.setChunkSize(chunkSize);
        while (0 <= inputChannel.read(byteContainer)) {
            byteContainer.flip();
            outputChannel.write(byteContainer);
            byteContainer.clear();
        }
    }

    @Override
    public BlobUploadChannel writer(URL presignedUri) {
        return new BlobUploadChannel(getOptions(), presignedUri);
    }

    @Override
    public StorageOperationBatch batch() {
        return new StorageOperationBatch(this.getOptions());
    }

    private StorageBlob createInternal(BlobMetadata metadata, final byte[] dataBytes, final int startIndex, final int totalCount, BlobUploadOptions... clientParams) {
        Preconditions.checkNotNull(dataBytes);
        final StorageObject blobProto = metadata.toProto();
        final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(metadata, clientParams);
        try {
            return StorageBlob.fromProto(this, runWithRetries(new Callable<StorageObject>() {

                @Override
                public StorageObject call() {
                    return storageServiceClient.create(blobProto, new ByteArrayInputStream(dataBytes, startIndex, totalCount), optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    @Override
    public StorageBlob createFrom(BlobMetadata blobMetadata, Path fileLocation, BlobWriteOptions... clientParams) throws IOException {
        return createFrom(blobMetadata, fileLocation, DEFAULT_IO_BUFFER_SIZE, clientParams);
    }

    @Override
    public AccessControlEntry createAcl(final BlobIdentifier objectName, final AccessControlEntry accessEntry) {
        final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(objectName.getBucket()).setObject(objectName.getName()).setGeneration(objectName.getGeneration());
        try {
            return AccessControlEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageServiceClient.createAcl(accessControlProto);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    /**
     * Returns the hostname used to send requests to Cloud Storage, e.g. "storage.googleapis.com".
     */
    private String getBaseStorageHostName(Map<UrlSigningOption.ServiceOption, Object> optionsMap) {
        String overrideHost = (String) optionsMap.get(UrlSigningOption.ServiceOption.HOST_NAME);
        String bucketScopedHost = (String) optionsMap.get(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME);
        if (!Strings.isNullOrEmpty(overrideHost)) {
            return overrideHost.replaceFirst("http(s)?://", "");
        }
        if (!Strings.isNullOrEmpty(bucketScopedHost)) {
            return bucketScopedHost.replaceFirst("http(s)?://", "");
        }
        return STORAGE_XML_HOST;
    }

    public FormPostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobMetadata, long expiryDuration, TimeUnit timeScale, PostPolicyV4Parameter... clientParams) {
        return generateSignedPostPolicyV4(blobMetadata, expiryDuration, timeScale, PostFieldsMapV4.builder().create(), clientParams);
    }

    private UrlSigningOption.SignatureSchemeVersion getPreferredSignatureVersion(EnumMap<UrlSigningOption.ServiceOption, Object> optionsMap) {
        // Check for an explicitly specified version in the map.
        for (UrlSigningOption.SignatureSchemeVersion signatureScheme : UrlSigningOption.SignatureSchemeVersion.values()) {
            if (signatureScheme.equals(optionsMap.get(UrlSigningOption.ServiceOption.SIGNATURE_VERSION))) {
                return signatureScheme;
            }
        }
        // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
        // Change this to V4 once we make it the default.
        return UrlSigningOption.SignatureSchemeVersion.V2;
    }

    /**
     * Builds signature info.
     *
     * @param optionsMap the option map
     * @param blobMetadata the blob info
     * @param expiryTimestamp the expiration in seconds
     * @param fileLocation the resource URI
     * @param serviceAccountEmail the account email
     * @return signature info
     */
    private SigningContext buildSigningInfo(Map<UrlSigningOption.ServiceOption, Object> optionsMap, BlobMetadata blobMetadata, long expiryTimestamp, URI fileLocation, String serviceAccountEmail) {
        HttpRequestMethod httpMethod = optionsMap.containsKey(UrlSigningOption.ServiceOption.HTTP_METHOD) ? (HttpRequestMethod) optionsMap.get(UrlSigningOption.ServiceOption.HTTP_METHOD) : HttpRequestMethod.GET;
        SigningContext.RequestSignatureBuilder signatureBuilder = new SigningContext.RequestSignatureBuilder(httpMethod, expiryTimestamp, fileLocation);
        if (firstNonNull((Boolean) optionsMap.get(UrlSigningOption.ServiceOption.MD5), false)) {
            checkArgument(null != blobMetadata.getMd5(), "Blob is missing a value for md5");
            signatureBuilder.setContentMd5(blobMetadata.getMd5());
        }
        if (firstNonNull((Boolean) optionsMap.get(UrlSigningOption.ServiceOption.CONTENT_TYPE), false)) {
            checkArgument(null != blobMetadata.getContentType(), "Blob is missing a value for content-type");
            signatureBuilder.setContentType(blobMetadata.getContentType());
        }
        signatureBuilder.setSignatureVersion((UrlSigningOption.SignatureSchemeVersion) optionsMap.get(UrlSigningOption.ServiceOption.SIGNATURE_VERSION));
        signatureBuilder.setAccountEmail(serviceAccountEmail);
        signatureBuilder.setTimestamp(getOptions().getClock().millisTime());
        ImmutableMap.Builder<String, String> extraHeadersBuilder = new ImmutableMap.Builder<String, String>();
        boolean useVersion4 = UrlSigningOption.SignatureSchemeVersion.V4.equals(optionsMap.get(UrlSigningOption.ServiceOption.SIGNATURE_VERSION));
        if (useVersion4) {
            // We don't sign the host header for V2 signed URLs; only do this for V4.
            // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
            if (!optionsMap.containsKey(UrlSigningOption.ServiceOption.VIRTUAL_HOSTED_STYLE)) {
                if (optionsMap.containsKey(UrlSigningOption.ServiceOption.HOST_NAME) || optionsMap.containsKey(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME)) {
                    extraHeadersBuilder.put("host", getBaseStorageHostName(optionsMap));
                }
            } else {
                extraHeadersBuilder.put("host", slashlessBucketNameFrom(blobMetadata) + "." + getBaseStorageHostName(optionsMap));
            }
        }
        if (optionsMap.containsKey(UrlSigningOption.ServiceOption.EXT_HEADERS)) {
            extraHeadersBuilder.putAll((Map<String, String>) optionsMap.get(UrlSigningOption.ServiceOption.EXT_HEADERS));
        }
        ImmutableMap.Builder<String, String> queryBuilder = new ImmutableMap.Builder<String, String>();
        if (optionsMap.containsKey(UrlSigningOption.ServiceOption.QUERY_PARAMS)) {
            queryBuilder.putAll((Map<String, String>) optionsMap.get(UrlSigningOption.ServiceOption.QUERY_PARAMS));
        }
        return signatureBuilder.setCanonicalizedExtensionHeaders((Map<String, String>) extraHeadersBuilder.build()).setCanonicalizedQueryParams((Map<String, String>) queryBuilder.build()).buildSignature();
    }

    @Override
    public AccessControlEntry getAcl(final String containerName, final TypedEntity principal, BucketSourceRequestOption... clientParams) {
        try {
            final Map<StorageServiceRpc.StorageOption, ?> optionsMapping = buildOptionMap(clientParams);
            BucketAccessControl result = runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return storageServiceClient.getAcl(containerName, principal.toProto(), optionsMapping);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == result ? null : AccessControlEntry.fromProto(result);
        } catch (RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    public FormPostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobMetadata, long expiryDuration, TimeUnit timeScale, PostFieldsMapV4 formData, PostPolicyV4Parameter... clientParams) {
        return generateSignedPostPolicyV4(blobMetadata, expiryDuration, timeScale, formData, PostConditionsVersion4.builder().create(), clientParams);
    }

    @Override
    public URL signUrl(BlobMetadata blobMetadata, long expiryDuration, TimeUnit timeScale, UrlSigningOption... clientParams) {
        EnumMap<UrlSigningOption.ServiceOption, Object> optionsMap = Maps.newEnumMap(UrlSigningOption.ServiceOption.class);
        for (UrlSigningOption signingChoice : clientParams) {
            optionsMap.put(signingChoice.getOption(), signingChoice.getValue());
        }
        boolean useVersion2 = getPreferredSignatureVersion(optionsMap).equals(UrlSigningOption.SignatureSchemeVersion.V2);
        boolean useVersion4 = getPreferredSignatureVersion(optionsMap).equals(UrlSigningOption.SignatureSchemeVersion.V4);
        ServiceAccountSigner signer = (ServiceAccountSigner) optionsMap.get(UrlSigningOption.ServiceOption.SERVICE_ACCOUNT_CRED);
        if (null == signer) {
            checkState(this.getOptions().getCredentials() instanceof ServiceAccountSigner, "Signing key was not provided and could not be derived");
            signer = (ServiceAccountSigner) this.getOptions().getCredentials();
        }
        long expiryTimestamp = useVersion4 ? TimeUnit.SECONDS.convert(timeScale.toMillis(expiryDuration), TimeUnit.MILLISECONDS) : TimeUnit.SECONDS.convert(getOptions().getClock().millisTime() + timeScale.toMillis(expiryDuration), TimeUnit.MILLISECONDS);
        checkArgument(!(optionsMap.containsKey(UrlSigningOption.ServiceOption.VIRTUAL_HOSTED_STYLE) && optionsMap.containsKey(UrlSigningOption.ServiceOption.PATH_STYLE) && optionsMap.containsKey(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME)), "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be" + " specified.");
        String containerName = slashlessBucketNameFrom(blobMetadata);
        String escapedObjectName = "";
        if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
            escapedObjectName = rfc3986UriEncode(blobMetadata.getName(), false);
        }
        boolean enablePathStyle = usePathStyleForSignedUrl(optionsMap);
        String xmlHost = enablePathStyle ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(optionsMap) : STORAGE_XML_SCHEME + "://" + containerName + "." + getBaseStorageHostName(optionsMap);
        if (optionsMap.containsKey(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME)) {
            xmlHost = (String) optionsMap.get(UrlSigningOption.ServiceOption.BUCKET_BOUND_HOST_NAME);
        }
        String storagePath = enablePathStyle ? buildResourceUriPath(containerName, escapedObjectName, optionsMap) : buildResourceUriPath("", escapedObjectName, optionsMap);
        URI fileLocation = URI.create(storagePath);
        // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
        // canonical resource string that we'll sign to include the bucket.
        URI signingUri = useVersion2 ? URI.create(buildResourceUriPath(containerName, escapedObjectName, optionsMap)) : fileLocation;
        try {
            SigningContext signingContext = buildSigningInfo(optionsMap, blobMetadata, expiryTimestamp, signingUri, signer.getAccount());
            String payloadToSign = signingContext.buildUnsignedPayload();
            byte[] signatureData = signer.sign(payloadToSign.getBytes(UTF_8));
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(xmlHost).append(fileLocation);
            if (!useVersion4) {
                BaseEncoding baseCodec = BaseEncoding.base64();
                String sigValue = URLEncoder.encode(baseCodec.encode(signatureData), UTF_8.name());
                String queryV2 = signingContext.buildV2QueryString();
                stringBuilder.append('?');
                if (!Strings.isNullOrEmpty(queryV2)) {
                    stringBuilder.append(queryV2).append('&');
                }
                stringBuilder.append("GoogleAccessId=").append(signer.getAccount());
                stringBuilder.append("&Expires=").append(expiryTimestamp);
                stringBuilder.append("&Signature=").append(sigValue);
            } else {
                BaseEncoding baseCodec = BaseEncoding.base16().lowerCase();
                String sigValue = URLEncoder.encode(baseCodec.encode(signatureData), UTF_8.name());
                String queryV4 = signingContext.buildV4QueryString();
                stringBuilder.append('?');
                if (!Strings.isNullOrEmpty(queryV4)) {
                    stringBuilder.append(queryV4).append('&');
                }
                stringBuilder.append("X-Goog-Signature=").append(sigValue);
            }
            return new URL(stringBuilder.toString());
        } catch (MalformedURLException | UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

}
