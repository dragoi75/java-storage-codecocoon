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
import static com.google.cloud.storage.PolicyHelper.convertFromApiPolicy;
import static com.google.cloud.storage.PolicyHelper.convertToApiPolicy;
import static com.google.cloud.storage.SignedUrlEncodingHelper.Rfc3986UriEncode;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.DELIMITER;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.IF_SOURCE_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.IF_SOURCE_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.IF_SOURCE_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.CloudStorageRpcClient.StorageOption.IF_SOURCE_METAGENERATION_NOT_MATCH;
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
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AclEntry.TypedEntity;
import com.google.cloud.storage.HmacSecretKey.HmacKeyInfo;
import com.google.cloud.storage.PostPolicyVersion4.ConditionTypeV4;
import com.google.cloud.storage.PostPolicyVersion4.PostConditionsVersion4;
import com.google.cloud.storage.PostPolicyVersion4.PostFieldsVersion4;
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient;
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

final class StorageImpl extends BaseService<StorageSettings> implements StorageClient {

    private static final byte[] EMPTY_BYTE_ARRAY = {};

    private static final String EMPTY_BYTE_ARRAY_MD5 = "1B2M2Y8AsgTpgAmY7PhCfg==";

    private static final String EMPTY_BYTE_ARRAY_CRC32C = "AAAAAA==";

    private static final String PATH_DELIMITER = "/";

    /**
     * Signed URLs are only supported through the GCS XML API endpoint.
     */
    private static final String STORAGE_XML_URI_SCHEME = "https";

    private static final String STORAGE_XML_URI_HOST_NAME = "storage.googleapis.com";

    private static final int DEFAULT_BUFFER_SIZE = 15 * 1024 * 1024;

    private static final int MIN_BUFFER_SIZE = 256 * 1024;

    private static final Function<Tuple<StorageClient, Boolean>, Boolean> DELETE_FUNCTION = new Function<Tuple<StorageClient, Boolean>, Boolean>() {

        @Override
        public Boolean apply(Tuple<StorageClient, Boolean> tuple) {
            return tuple.y();
        }
    };

    private final CloudStorageRpcClient storageRpc;

    StorageImpl(StorageSettings options) {
        super(options);
        storageRpc = options.getStorageRpcV1();
    }

    @Override
    public StorageBucket create(BucketInfo bucketInfo, BucketTargetOptions... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = bucketInfo.toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(bucketInfo, options);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return storageRpc.create(bucketPb, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageObject create(BlobAttributes blobInfo, BlobUploadOption... options) {
        BlobAttributes updatedInfo = blobInfo.asBuilder().setMd5(EMPTY_BYTE_ARRAY_MD5).setCrc32c(EMPTY_BYTE_ARRAY_CRC32C).buildObject();
        return internalCreate(updatedInfo, EMPTY_BYTE_ARRAY, options);
    }

    @Override
    public StorageObject create(BlobAttributes blobInfo, byte[] content, BlobUploadOption... options) {
        content = firstNonNull(content, EMPTY_BYTE_ARRAY);
        BlobAttributes updatedInfo = blobInfo.asBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(content).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(content).asInt()))).buildObject();
        return internalCreate(updatedInfo, content, options);
    }

    @Override
    public StorageObject create(BlobAttributes blobInfo, byte[] content, int offset, int length, BlobUploadOption... options) {
        content = firstNonNull(content, EMPTY_BYTE_ARRAY);
        byte[] subContent = Arrays.copyOfRange(content, offset, offset + length);
        BlobAttributes updatedInfo = blobInfo.asBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(subContent).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(subContent).asInt()))).buildObject();
        return internalCreate(updatedInfo, subContent, options);
    }

    @Override
    @Deprecated
    public StorageObject create(BlobAttributes blobInfo, InputStream content, BlobWriteOptions... options) {
        Tuple<BlobAttributes, BlobUploadOption[]> targetOptions = BlobUploadOption.convertOptions(blobInfo, options);
        com.google.api.services.storage.model.StorageObject blobPb = targetOptions.x().toProto();
        Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(targetOptions.x(), targetOptions.y());
        InputStream inputStreamParam = firstNonNull(content, new ByteArrayInputStream(EMPTY_BYTE_ARRAY));
        // retries are not safe when the input is an InputStream, so we can't retry.
        return StorageObject.fromProto(this, storageRpc.create(blobPb, inputStreamParam, optionsMap));
    }

    private StorageObject internalCreate(BlobAttributes info, final byte[] content, BlobUploadOption... options) {
        Preconditions.checkNotNull(content);
        final com.google.api.services.storage.model.StorageObject blobPb = info.toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(info, options);
        try {
            return StorageObject.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.StorageObject>() {

                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                    return storageRpc.create(blobPb, new ByteArrayInputStream(content), optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageObject createFrom(BlobAttributes blobInfo, Path path, BlobWriteOptions... options) throws IOException {
        return createFrom(blobInfo, path, DEFAULT_BUFFER_SIZE, options);
    }

    @Override
    public StorageObject createFrom(BlobAttributes blobInfo, Path path, int bufferSize, BlobWriteOptions... options) throws IOException {
        if (Files.isDirectory(path)) {
            throw new StorageOperationException(0, path + " is a directory");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return createFrom(blobInfo, input, bufferSize, options);
        }
    }

    @Override
    public StorageObject createFrom(BlobAttributes blobInfo, InputStream content, BlobWriteOptions... options) throws IOException {
        return createFrom(blobInfo, content, DEFAULT_BUFFER_SIZE, options);
    }

    @Override
    public StorageObject createFrom(BlobAttributes blobInfo, InputStream content, int bufferSize, BlobWriteOptions... options) throws IOException {
        BlobWriteChannel blobWriteChannel;
        try (WriteChannel writer = writer(blobInfo, options)) {
            blobWriteChannel = (BlobWriteChannel) writer;
            uploadHelper(Channels.newChannel(content), writer, bufferSize);
        }
        com.google.api.services.storage.model.StorageObject objectProto = blobWriteChannel.getStorageObject();
        return StorageObject.fromProto(this, objectProto);
    }

    /*
   * Uploads the given content to the storage using specified write channel and the given buffer
   * size. This method does not close any channels.
   */
    private static void uploadHelper(ReadableByteChannel reader, WriteChannel writer, int bufferSize) throws IOException {
        bufferSize = Math.max(bufferSize, MIN_BUFFER_SIZE);
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        writer.setChunkSize(bufferSize);
        while (0 <= reader.read(buffer)) {
            buffer.flip();
            writer.write(buffer);
            buffer.clear();
        }
    }

    @Override
    public StorageBucket get(String bucket, BucketGetOptions... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = BucketInfo.ofName(bucket).toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        try {
            com.google.api.services.storage.model.Bucket answer = runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return storageRpc.get(bucketPb, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == answer ? null : StorageBucket.fromProto(this, answer);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageObject get(String bucket, String blob, BlobGetOptions... options) {
        return get(BlobIdentifier.create(bucket, blob), options);
    }

    @Override
    public StorageObject get(BlobIdentifier blob, BlobGetOptions... options) {
        final com.google.api.services.storage.model.StorageObject storedObject = blob.toStorageObject();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(blob, options);
        try {
            com.google.api.services.storage.model.StorageObject storageObject = runWithRetries(new Callable<com.google.api.services.storage.model.StorageObject>() {

                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                    return storageRpc.get(storedObject, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == storageObject ? null : StorageObject.fromProto(this, storageObject);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageObject get(BlobIdentifier blob) {
        return get(blob, new BlobGetOptions[0]);
    }

    private static class BucketPageFetcher implements NextPageFetcher<StorageBucket> {

        private static final long serialVersionUID = 5850406828803613729L;

        private final Map<CloudStorageRpcClient.StorageOption, ?> requestOptions;

        private final StorageSettings serviceOptions;

        BucketPageFetcher(StorageSettings serviceOptions, String cursor, Map<CloudStorageRpcClient.StorageOption, ?> optionMap) {
            this.requestOptions = PageImpl.nextRequestOptions(CloudStorageRpcClient.StorageOption.PAGE_TOKEN, cursor, optionMap);
            this.serviceOptions = serviceOptions;
        }

        @Override
        public Page<StorageBucket> getNextPage() {
            return listBuckets(serviceOptions, requestOptions);
        }
    }

    private static class BlobPageFetcher implements NextPageFetcher<StorageObject> {

        private static final long serialVersionUID = 81807334445874098L;

        private final Map<CloudStorageRpcClient.StorageOption, ?> requestOptions;

        private final StorageSettings serviceOptions;

        private final String bucket;

        BlobPageFetcher(String bucket, StorageSettings serviceOptions, String cursor, Map<CloudStorageRpcClient.StorageOption, ?> optionMap) {
            this.requestOptions = PageImpl.nextRequestOptions(CloudStorageRpcClient.StorageOption.PAGE_TOKEN, cursor, optionMap);
            this.serviceOptions = serviceOptions;
            this.bucket = bucket;
        }

        @Override
        public Page<StorageObject> getNextPage() {
            return listBlobs(bucket, serviceOptions, requestOptions);
        }
    }

    private static class HmacKeyMetadataPageFetcher implements NextPageFetcher<HmacSecretKey.HmacKeyInfo> {

        private static final long serialVersionUID = 308012320541700881L;

        private final StorageSettings serviceOptions;

        private final Map<CloudStorageRpcClient.StorageOption, ?> options;

        HmacKeyMetadataPageFetcher(StorageSettings serviceOptions, Map<CloudStorageRpcClient.StorageOption, ?> options) {
            this.serviceOptions = serviceOptions;
            this.options = options;
        }

        @Override
        public Page<HmacSecretKey.HmacKeyInfo> getNextPage() {
            return listHmacKeys(serviceOptions, options);
        }
    }

    @Override
    public Page<StorageBucket> list(BucketListOptions... options) {
        return listBuckets(getOptions(), optionMap(options));
    }

    @Override
    public Page<StorageObject> list(final String bucket, BlobListOptions... options) {
        return listBlobs(bucket, getOptions(), optionMap(options));
    }

    private static Page<StorageBucket> listBuckets(final StorageSettings serviceOptions, final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> result = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> call() {
                    return serviceOptions.getStorageRpcV1().list(optionsMap);
                }
            }, serviceOptions.getRetrySettings(), EXCEPTION_HANDLER, serviceOptions.getClock());
            String cursor = result.x();
            Iterable<StorageBucket> buckets = null == result.y() ? ImmutableList.<StorageBucket>of() : Iterables.transform(result.y(), new Function<com.google.api.services.storage.model.Bucket, StorageBucket>() {

                @Override
                public StorageBucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                    return StorageBucket.fromProto(serviceOptions.getService(), bucketPb);
                }
            });
            return new PageImpl<>(new BucketPageFetcher(serviceOptions, cursor, optionsMap), cursor, buckets);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    private static Page<StorageObject> listBlobs(final String bucket, final StorageSettings serviceOptions, final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> result = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> call() {
                    return serviceOptions.getStorageRpcV1().list(bucket, optionsMap);
                }
            }, serviceOptions.getRetrySettings(), EXCEPTION_HANDLER, serviceOptions.getClock());
            String cursor = result.x();
            Iterable<StorageObject> blobs = null == result.y() ? ImmutableList.<StorageObject>of() : Iterables.transform(result.y(), new Function<com.google.api.services.storage.model.StorageObject, StorageObject>() {

                @Override
                public StorageObject apply(com.google.api.services.storage.model.StorageObject storageObject) {
                    return StorageObject.fromProto(serviceOptions.getService(), storageObject);
                }
            });
            return new PageImpl<>(new BlobPageFetcher(bucket, serviceOptions, cursor, optionsMap), cursor, blobs);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageBucket update(BucketInfo bucketInfo, BucketTargetOptions... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = bucketInfo.toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(bucketInfo, options);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return storageRpc.patch(bucketPb, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageObject update(BlobAttributes blobInfo, BlobUploadOption... options) {
        final com.google.api.services.storage.model.StorageObject storageObject = blobInfo.toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(blobInfo, options);
        try {
            return StorageObject.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.StorageObject>() {

                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                    return storageRpc.patch(storageObject, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageObject update(BlobAttributes blobInfo) {
        return update(blobInfo, new BlobUploadOption[0]);
    }

    @Override
    public boolean delete(String bucket, BucketSourceRequestOption... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = BucketInfo.ofName(bucket).toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageRpc.delete(bucketPb, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public boolean delete(String bucket, String blob, BlobSourceOptions... options) {
        return delete(BlobIdentifier.create(bucket, blob), options);
    }

    @Override
    public boolean delete(BlobIdentifier blob, BlobSourceOptions... options) {
        final com.google.api.services.storage.model.StorageObject storageObject = blob.toStorageObject();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(blob, options);
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageRpc.delete(storageObject, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public boolean delete(BlobIdentifier blob) {
        return delete(blob, new BlobSourceOptions[0]);
    }

    @Override
    public StorageObject compose(final ComposeBlobsRequest composeRequest) {
        final List<com.google.api.services.storage.model.StorageObject> sources = Lists.newArrayListWithCapacity(composeRequest.getSourceBlobs().size());
        for (ComposeBlobsRequest.SourceBlobIdentifier sourceBlob : composeRequest.getSourceBlobs()) {
            sources.add(BlobAttributes.newBuilder(BlobIdentifier.create(composeRequest.getTarget().getBucket(), sourceBlob.getName(), sourceBlob.getGeneration())).buildObject().toProto());
        }
        final com.google.api.services.storage.model.StorageObject target = composeRequest.getTarget().toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> targetOptions = optionMap(composeRequest.getTarget().getGeneration(), composeRequest.getTarget().getMetageneration(), composeRequest.getTargetOptions());
        try {
            return StorageObject.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.StorageObject>() {

                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                    return storageRpc.compose(sources, target, targetOptions);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public BlobCopyWriter copy(final DataCopyRequest copyRequest) {
        final com.google.api.services.storage.model.StorageObject source = copyRequest.getSource().toStorageObject();
        final Map<CloudStorageRpcClient.StorageOption, ?> sourceOptions = optionMap(copyRequest.getSource().getGeneration(), null, copyRequest.getSourceOptions(), true);
        final com.google.api.services.storage.model.StorageObject targetObject = copyRequest.getTarget().toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> targetOptions = optionMap(copyRequest.getTarget().getGeneration(), copyRequest.getTarget().getMetageneration(), copyRequest.getTargetOptions());
        try {
            CloudStorageRpcClient.RewriteOperationResponse rewriteResponse = runWithRetries(new Callable<CloudStorageRpcClient.RewriteOperationResponse>() {

                @Override
                public CloudStorageRpcClient.RewriteOperationResponse call() {
                    return storageRpc.openRewrite(new CloudStorageRpcClient.RewriteOperationRequest(source, sourceOptions, copyRequest.getOverrideInfo(), targetObject, targetOptions, copyRequest.getMegabytesCopiedPerChunk()));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return new BlobCopyWriter(getOptions(), rewriteResponse);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public byte[] readAllBytes(String bucket, String blob, BlobSourceOptions... options) {
        return readAllBytes(BlobIdentifier.create(bucket, blob), options);
    }

    @Override
    public byte[] readAllBytes(BlobIdentifier blob, BlobSourceOptions... options) {
        final com.google.api.services.storage.model.StorageObject storageObject = blob.toStorageObject();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(blob, options);
        try {
            return runWithRetries(new Callable<byte[]>() {

                @Override
                public byte[] call() {
                    return storageRpc.load(storageObject, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageOperationBatch batch() {
        return new StorageOperationBatch(this.getOptions());
    }

    @Override
    public ReadChannel reader(String bucket, String blob, BlobSourceOptions... options) {
        Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        return new BlobReadChannel(getOptions(), BlobIdentifier.create(bucket, blob), optionsMap);
    }

    @Override
    public ReadChannel reader(BlobIdentifier blob, BlobSourceOptions... options) {
        Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(blob, options);
        return new BlobReadChannel(getOptions(), blob, optionsMap);
    }

    @Override
    public BlobWriteChannel writer(BlobAttributes blobInfo, BlobWriteOptions... options) {
        Tuple<BlobAttributes, BlobUploadOption[]> targetOptions = BlobUploadOption.convertOptions(blobInfo, options);
        return writer(targetOptions.x(), targetOptions.y());
    }

    @Override
    public BlobWriteChannel writer(URL signedURL) {
        return new BlobWriteChannel(getOptions(), signedURL);
    }

    private BlobWriteChannel writer(BlobAttributes blobInfo, BlobUploadOption... options) {
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(blobInfo, options);
        return new BlobWriteChannel(getOptions(), blobInfo, optionsMap);
    }

    @Override
    public URL signUrl(BlobAttributes blobInfo, long duration, TimeUnit unit, UrlSigningOption... options) {
        EnumMap<UrlSigningOption.RequestOption, Object> optionMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
        for (UrlSigningOption option : options) {
            optionMap.put(option.getOption(), option.getValue());
        }
        boolean isV2 = getPreferredSignatureVersion(optionMap).equals(UrlSigningOption.SignatureSchemeVersion.V2);
        boolean isV4 = getPreferredSignatureVersion(optionMap).equals(UrlSigningOption.SignatureSchemeVersion.V4);
        ServiceAccountSigner credentials = (ServiceAccountSigner) optionMap.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
        if (null == credentials) {
            checkState(this.getOptions().getCredentials() instanceof ServiceAccountSigner, "Signing key was not provided and could not be derived");
            credentials = (ServiceAccountSigner) this.getOptions().getCredentials();
        }
        long expiration = isV4 ? TimeUnit.SECONDS.convert(unit.toMillis(duration), TimeUnit.MILLISECONDS) : TimeUnit.SECONDS.convert(getOptions().getClock().millisTime() + unit.toMillis(duration), TimeUnit.MILLISECONDS);
        checkArgument(!(optionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && optionMap.containsKey(UrlSigningOption.RequestOption.PATH_STYLE) && optionMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)), "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be" + " specified.");
        String bucketName = slashlessBucketNameFromBlobInfo(blobInfo);
        String escapedBlobName = "";
        if (!Strings.isNullOrEmpty(blobInfo.getName())) {
            escapedBlobName = Rfc3986UriEncode(blobInfo.getName(), false);
        }
        boolean usePathStyle = shouldUsePathStyleForSignedUrl(optionMap);
        String storageXmlHostName = usePathStyle ? STORAGE_XML_URI_SCHEME + "://" + getBaseStorageHostName(optionMap) : STORAGE_XML_URI_SCHEME + "://" + bucketName + "." + getBaseStorageHostName(optionMap);
        if (optionMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
            storageXmlHostName = (String) optionMap.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
        }
        String stPath = usePathStyle ? constructResourceUriPath(bucketName, escapedBlobName, optionMap) : constructResourceUriPath("", escapedBlobName, optionMap);
        URI path = URI.create(stPath);
        // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
        // canonical resource string that we'll sign to include the bucket.
        URI pathForSigning = isV2 ? URI.create(constructResourceUriPath(bucketName, escapedBlobName, optionMap)) : path;
        try {
            SignatureInfo signatureInfo = buildSignatureInfo(optionMap, blobInfo, expiration, pathForSigning, credentials.getAccount());
            String unsignedPayload = signatureInfo.constructUnsignedPayload();
            byte[] signatureBytes = credentials.sign(unsignedPayload.getBytes(UTF_8));
            StringBuilder stBuilder = new StringBuilder();
            stBuilder.append(storageXmlHostName).append(path);
            if (!isV4) {
                BaseEncoding encoding = BaseEncoding.base64();
                String signature = URLEncoder.encode(encoding.encode(signatureBytes), UTF_8.name());
                String v2QueryString = signatureInfo.constructV2QueryString();
                stBuilder.append('?');
                if (!Strings.isNullOrEmpty(v2QueryString)) {
                    stBuilder.append(v2QueryString).append('&');
                }
                stBuilder.append("GoogleAccessId=").append(credentials.getAccount());
                stBuilder.append("&Expires=").append(expiration);
                stBuilder.append("&Signature=").append(signature);
            } else {
                BaseEncoding encoding = BaseEncoding.base16().lowerCase();
                String signature = URLEncoder.encode(encoding.encode(signatureBytes), UTF_8.name());
                String v4QueryString = signatureInfo.constructV4QueryString();
                stBuilder.append('?');
                if (!Strings.isNullOrEmpty(v4QueryString)) {
                    stBuilder.append(v4QueryString).append('&');
                }
                stBuilder.append("X-Goog-Signature=").append(signature);
            }
            return new URL(stBuilder.toString());
        } catch (MalformedURLException | UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public PostPolicyVersion4 generateSignedPostPolicyV4(BlobAttributes blobInfo, long duration, TimeUnit unit, PostFieldsVersion4 fields, PostConditionsVersion4 conditions, PostPolicyV4Parameter... options) {
        EnumMap<UrlSigningOption.RequestOption, Object> optionMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
        // Convert to a map of SignUrlOptions so we can re-use some utility methods
        for (PostPolicyV4Parameter option : options) {
            optionMap.put(UrlSigningOption.RequestOption.valueOf(option.getOption().name()), option.getValue());
        }
        optionMap.put(UrlSigningOption.RequestOption.SIGNATURE_VERSION, UrlSigningOption.SignatureSchemeVersion.V4);
        ServiceAccountSigner credentials = (ServiceAccountSigner) optionMap.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
        if (null == credentials) {
            checkState(this.getOptions().getCredentials() instanceof ServiceAccountSigner, "Signing key was not provided and could not be derived");
            credentials = (ServiceAccountSigner) this.getOptions().getCredentials();
        }
        checkArgument(!(optionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && optionMap.containsKey(UrlSigningOption.RequestOption.PATH_STYLE) && optionMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)), "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be" + " specified.");
        String bucketName = slashlessBucketNameFromBlobInfo(blobInfo);
        boolean usePathStyle = shouldUsePathStyleForSignedUrl(optionMap);
        String url;
        if (!usePathStyle) {
            url = STORAGE_XML_URI_SCHEME + "://" + bucketName + "." + STORAGE_XML_URI_HOST_NAME + "/";
        } else {
            url = STORAGE_XML_URI_SCHEME + "://" + STORAGE_XML_URI_HOST_NAME + "/" + bucketName + "/";
        }
        if (optionMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
            url = optionMap.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME) + "/";
        }
        SimpleDateFormat googDateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        SimpleDateFormat yearMonthDayFormat = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat expirationFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        googDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        yearMonthDayFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        expirationFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        long timestamp = getOptions().getClock().millisTime();
        String date = googDateFormat.format(timestamp);
        String signingCredential = credentials.getAccount() + "/" + yearMonthDayFormat.format(timestamp) + "/auto/storage/goog4_request";
        Map<String, String> policyFields = new HashMap<>();
        PostConditionsVersion4.ConditionsBuilder conditionsBuilder = conditions.asBuilder();
        for (Map.Entry<String, String> entry : fields.getFieldsMap().entrySet()) {
            // Every field needs a corresponding policy condition, so add them if they're missing
            conditionsBuilder.addCondition(ConditionTypeV4.MATCHES, entry.getKey(), entry.getValue());
            policyFields.put(entry.getKey(), entry.getValue());
        }
        PostConditionsVersion4 v4Conditions = conditionsBuilder.addBucket(PostPolicyVersion4.ConditionTypeV4.MATCHES, blobInfo.getBucket()).addKey(PostPolicyVersion4.ConditionTypeV4.MATCHES, blobInfo.getName()).addCondition(PostPolicyVersion4.ConditionTypeV4.MATCHES, "x-goog-date", date).addCondition(ConditionTypeV4.MATCHES, "x-goog-credential", signingCredential).addCondition(PostPolicyVersion4.ConditionTypeV4.MATCHES, "x-goog-algorithm", "GOOG4-RSA-SHA256").create();
        PostPolicyVersion4.PostPolicyV4Payload document = PostPolicyVersion4.PostPolicyV4Payload.create(expirationFormat.format(timestamp + unit.toMillis(duration)), v4Conditions);
        String policy = BaseEncoding.base64().encode(document.toJsonString().getBytes());
        String signature = BaseEncoding.base16().encode(credentials.sign(policy.getBytes())).toLowerCase();
        for (PostPolicyVersion4.BinaryCondition condition : v4Conditions.getConditions()) {
            if (PostPolicyVersion4.ConditionTypeV4.MATCHES == condition.conditionKind) {
                policyFields.put(condition.leftOperand, condition.rightOperand);
            }
        }
        policyFields.put("key", blobInfo.getName());
        policyFields.put("x-goog-credential", signingCredential);
        policyFields.put("x-goog-algorithm", "GOOG4-RSA-SHA256");
        policyFields.put("x-goog-date", date);
        policyFields.put("x-goog-signature", signature);
        policyFields.put("policy", policy);
        policyFields.remove("bucket");
        return PostPolicyVersion4.create(url, policyFields);
    }

    public PostPolicyVersion4 generateSignedPostPolicyV4(BlobAttributes blobInfo, long duration, TimeUnit unit, PostFieldsVersion4 fields, PostPolicyV4Parameter... options) {
        return generateSignedPostPolicyV4(blobInfo, duration, unit, fields, PostConditionsVersion4.builder().create(), options);
    }

    public PostPolicyVersion4 generateSignedPostPolicyV4(BlobAttributes blobInfo, long duration, TimeUnit unit, PostConditionsVersion4 conditions, PostPolicyV4Parameter... options) {
        return generateSignedPostPolicyV4(blobInfo, duration, unit, PostPolicyVersion4.PostFieldsVersion4.builder().create(), conditions, options);
    }

    public PostPolicyVersion4 generateSignedPostPolicyV4(BlobAttributes blobInfo, long duration, TimeUnit unit, PostPolicyV4Parameter... options) {
        return generateSignedPostPolicyV4(blobInfo, duration, unit, PostFieldsVersion4.builder().create(), options);
    }

    private String constructResourceUriPath(String slashlessBucketName, String escapedBlobName, EnumMap<UrlSigningOption.RequestOption, Object> optionMap) {
        if (Strings.isNullOrEmpty(slashlessBucketName)) {
            if (Strings.isNullOrEmpty(escapedBlobName)) {
                return PATH_DELIMITER;
            }
            if (escapedBlobName.startsWith(PATH_DELIMITER)) {
                return escapedBlobName;
            }
            return PATH_DELIMITER + escapedBlobName;
        }
        StringBuilder pathBuilder = new StringBuilder();
        pathBuilder.append(PATH_DELIMITER).append(slashlessBucketName);
        if (Strings.isNullOrEmpty(escapedBlobName)) {
            boolean isV2 = getPreferredSignatureVersion(optionMap).equals(UrlSigningOption.SignatureSchemeVersion.V2);
            // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
            // must end with a forward slash.
            if (optionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && isV2) {
                pathBuilder.append(PATH_DELIMITER);
            }
            return pathBuilder.toString();
        }
        pathBuilder.append(PATH_DELIMITER);
        pathBuilder.append(escapedBlobName);
        return pathBuilder.toString();
    }

    private UrlSigningOption.SignatureSchemeVersion getPreferredSignatureVersion(EnumMap<UrlSigningOption.RequestOption, Object> optionMap) {
        // Check for an explicitly specified version in the map.
        for (UrlSigningOption.SignatureSchemeVersion version : UrlSigningOption.SignatureSchemeVersion.values()) {
            if (version.equals(optionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION))) {
                return version;
            }
        }
        // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
        // Change this to V4 once we make it the default.
        return UrlSigningOption.SignatureSchemeVersion.V2;
    }

    private boolean shouldUsePathStyleForSignedUrl(EnumMap<UrlSigningOption.RequestOption, Object> optionMap) {
        // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
        // logic to return false unless PATH_STYLE was explicitly specified.
        if (optionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) || optionMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
            return false;
        }
        return true;
    }

    /**
     * Builds signature info.
     *
     * @param optionMap the option map
     * @param blobInfo the blob info
     * @param expiration the expiration in seconds
     * @param path the resource URI
     * @param accountEmail the account email
     * @return signature info
     */
    private SignatureInfo buildSignatureInfo(Map<UrlSigningOption.RequestOption, Object> optionMap, BlobAttributes blobInfo, long expiration, URI path, String accountEmail) {
        HttpRequestMethod httpVerb = optionMap.containsKey(UrlSigningOption.RequestOption.HTTP_METHOD) ? (HttpRequestMethod) optionMap.get(UrlSigningOption.RequestOption.HTTP_METHOD) : HttpRequestMethod.GET;
        SignatureInfo.Builder signatureInfoBuilder = new SignatureInfo.Builder(httpVerb, expiration, path);
        if (firstNonNull((Boolean) optionMap.get(UrlSigningOption.RequestOption.MD5), false)) {
            checkArgument(null != blobInfo.getMd5(), "Blob is missing a value for md5");
            signatureInfoBuilder.setContentMd5(blobInfo.getMd5());
        }
        if (firstNonNull((Boolean) optionMap.get(UrlSigningOption.RequestOption.CONTENT_TYPE), false)) {
            checkArgument(null != blobInfo.getContentType(), "Blob is missing a value for content-type");
            signatureInfoBuilder.setContentType(blobInfo.getContentType());
        }
        signatureInfoBuilder.setSignatureVersion((UrlSigningOption.SignatureSchemeVersion) optionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
        signatureInfoBuilder.setAccountEmail(accountEmail);
        signatureInfoBuilder.setTimestamp(getOptions().getClock().millisTime());
        ImmutableMap.Builder<String, String> extHeadersBuilder = new ImmutableMap.Builder<String, String>();
        boolean isV4 = UrlSigningOption.SignatureSchemeVersion.V4.equals(optionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
        if (isV4) {
            // We don't sign the host header for V2 signed URLs; only do this for V4.
            // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
            if (!optionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
                if (optionMap.containsKey(UrlSigningOption.RequestOption.HOST_NAME) || optionMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
                    extHeadersBuilder.put("host", getBaseStorageHostName(optionMap));
                }
            } else {
                extHeadersBuilder.put("host", slashlessBucketNameFromBlobInfo(blobInfo) + "." + getBaseStorageHostName(optionMap));
            }
        }
        if (optionMap.containsKey(UrlSigningOption.RequestOption.EXT_HEADERS)) {
            extHeadersBuilder.putAll((Map<String, String>) optionMap.get(UrlSigningOption.RequestOption.EXT_HEADERS));
        }
        ImmutableMap.Builder<String, String> queryParamsBuilder = new ImmutableMap.Builder<String, String>();
        if (optionMap.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
            queryParamsBuilder.putAll((Map<String, String>) optionMap.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
        }
        return signatureInfoBuilder.setCanonicalizedExtensionHeaders((Map<String, String>) extHeadersBuilder.build()).setCanonicalizedQueryParams((Map<String, String>) queryParamsBuilder.build()).build();
    }

    private String slashlessBucketNameFromBlobInfo(BlobAttributes blobInfo) {
        // The bucket name itself should never contain a forward slash. However, parts already existed
        // in the code to check for this, so we remove the forward slashes to be safe here.
        return CharMatcher.anyOf(PATH_DELIMITER).trimFrom(blobInfo.getBucket());
    }

    /**
     * Returns the hostname used to send requests to Cloud Storage, e.g. "storage.googleapis.com".
     */
    private String getBaseStorageHostName(Map<UrlSigningOption.RequestOption, Object> optionMap) {
        String specifiedBaseHostName = (String) optionMap.get(UrlSigningOption.RequestOption.HOST_NAME);
        String bucketBoundHostName = (String) optionMap.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
        if (!Strings.isNullOrEmpty(specifiedBaseHostName)) {
            return specifiedBaseHostName.replaceFirst("http(s)?://", "");
        }
        if (!Strings.isNullOrEmpty(bucketBoundHostName)) {
            return bucketBoundHostName.replaceFirst("http(s)?://", "");
        }
        return STORAGE_XML_URI_HOST_NAME;
    }

    @Override
    public List<StorageObject> get(BlobIdentifier... blobIds) {
        return get(Arrays.asList(blobIds));
    }

    @Override
    public List<StorageObject> get(Iterable<BlobIdentifier> blobIds) {
        StorageOperationBatch batch = batch();
        final List<StorageObject> results = Lists.newArrayList();
        for (BlobIdentifier blob : blobIds) {
            batch.get(blob).notify(new BatchResult.Callback<StorageObject, StorageOperationException>() {

                @Override
                public void success(StorageObject result) {
                    results.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    results.add(null);
                }
            });
        }
        batch.commit();
        return Collections.unmodifiableList(results);
    }

    @Override
    public List<StorageObject> update(BlobAttributes... blobInfos) {
        return update(Arrays.asList(blobInfos));
    }

    @Override
    public List<StorageObject> update(Iterable<BlobAttributes> blobInfos) {
        StorageOperationBatch batch = batch();
        final List<StorageObject> results = Lists.newArrayList();
        for (BlobAttributes blobInfo : blobInfos) {
            batch.patch(blobInfo).notify(new BatchResult.Callback<StorageObject, StorageOperationException>() {

                @Override
                public void success(StorageObject result) {
                    results.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    results.add(null);
                }
            });
        }
        batch.commit();
        return Collections.unmodifiableList(results);
    }

    @Override
    public List<Boolean> delete(BlobIdentifier... blobIds) {
        return delete(Arrays.asList(blobIds));
    }

    @Override
    public List<Boolean> delete(Iterable<BlobIdentifier> blobIds) {
        StorageOperationBatch batch = batch();
        final List<Boolean> results = Lists.newArrayList();
        for (BlobIdentifier blob : blobIds) {
            batch.remove(blob).notify(new BatchResult.Callback<Boolean, StorageOperationException>() {

                @Override
                public void success(Boolean result) {
                    results.add(result);
                }

                @Override
                public void error(StorageOperationException exception) {
                    results.add(Boolean.FALSE);
                }
            });
        }
        batch.commit();
        return Collections.unmodifiableList(results);
    }

    @Override
    public AclEntry getAcl(final String bucket, final AclEntry.TypedEntity entity, BucketSourceRequestOption... options) {
        try {
            final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
            BucketAccessControl answer = runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return storageRpc.getAcl(bucket, entity.toProtoString(), optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == answer ? null : AclEntry.fromProto(answer);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public AclEntry getAcl(final String bucket, final AclEntry.TypedEntity entity) {
        return getAcl(bucket, entity, new BucketSourceRequestOption[0]);
    }

    @Override
    public boolean deleteAcl(final String bucket, final AclEntry.TypedEntity entity, BucketSourceRequestOption... options) {
        try {
            final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageRpc.deleteAcl(bucket, entity.toProtoString(), optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public boolean deleteAcl(final String bucket, final TypedEntity entity) {
        return deleteAcl(bucket, entity, new BucketSourceRequestOption[0]);
    }

    @Override
    public AclEntry createAcl(String bucket, AclEntry acl, BucketSourceRequestOption... options) {
        final BucketAccessControl aclPb = acl.toBucketProto().setBucket(bucket);
        try {
            final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
            return AclEntry.fromProto(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return storageRpc.createAcl(aclPb, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public AclEntry createAcl(String bucket, AclEntry acl) {
        return createAcl(bucket, acl, new BucketSourceRequestOption[0]);
    }

    @Override
    public AclEntry updateAcl(String bucket, AclEntry acl, BucketSourceRequestOption... options) {
        final BucketAccessControl aclPb = acl.toBucketProto().setBucket(bucket);
        try {
            final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
            return AclEntry.fromProto(runWithRetries(new Callable<BucketAccessControl>() {

                @Override
                public BucketAccessControl call() {
                    return storageRpc.patchAcl(aclPb, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public AclEntry updateAcl(String bucket, AclEntry acl) {
        return updateAcl(bucket, acl, new BucketSourceRequestOption[0]);
    }

    @Override
    public List<AclEntry> listAcls(final String bucket, BucketSourceRequestOption... options) {
        try {
            final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
            List<BucketAccessControl> answer = runWithRetries(new Callable<List<BucketAccessControl>>() {

                @Override
                public List<BucketAccessControl> call() {
                    return storageRpc.listAcls(bucket, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(answer, AclEntry.FROM_BUCKET_PROTO_FN);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public List<AclEntry> listAcls(final String bucket) {
        return listAcls(bucket, new BucketSourceRequestOption[0]);
    }

    @Override
    public AclEntry getDefaultAcl(final String bucket, final AclEntry.TypedEntity entity) {
        try {
            ObjectAccessControl answer = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageRpc.getDefaultAcl(bucket, entity.toProtoString());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == answer ? null : AclEntry.fromProto(answer);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public boolean deleteDefaultAcl(final String bucket, final AclEntry.TypedEntity entity) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageRpc.deleteDefaultAcl(bucket, entity.toProtoString());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public AclEntry createDefaultAcl(String bucket, AclEntry acl) {
        final ObjectAccessControl aclPb = acl.toObjectProto().setBucket(bucket);
        try {
            return AclEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageRpc.createDefaultAcl(aclPb);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public AclEntry updateDefaultAcl(String bucket, AclEntry acl) {
        final ObjectAccessControl aclPb = acl.toObjectProto().setBucket(bucket);
        try {
            return AclEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageRpc.patchDefaultAcl(aclPb);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public List<AclEntry> listDefaultAcls(final String bucket) {
        try {
            List<ObjectAccessControl> answer = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return storageRpc.listDefaultAcls(bucket);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(answer, AclEntry.FROM_OBJECT_PROTO_FN);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public AclEntry getAcl(final BlobIdentifier blob, final AclEntry.TypedEntity entity) {
        try {
            ObjectAccessControl answer = runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageRpc.getAcl(blob.getBucket(), blob.getName(), blob.getGeneration(), entity.toProtoString());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == answer ? null : AclEntry.fromProto(answer);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public boolean deleteAcl(final BlobIdentifier blob, final AclEntry.TypedEntity entity) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageRpc.deleteAcl(blob.getBucket(), blob.getName(), blob.getGeneration(), entity.toProtoString());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public AclEntry createAcl(final BlobIdentifier blob, final AclEntry acl) {
        final ObjectAccessControl aclPb = acl.toObjectProto().setBucket(blob.getBucket()).setObject(blob.getName()).setGeneration(blob.getGeneration());
        try {
            return AclEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageRpc.createAcl(aclPb);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public AclEntry updateAcl(BlobIdentifier blob, AclEntry acl) {
        final ObjectAccessControl aclPb = acl.toObjectProto().setBucket(blob.getBucket()).setObject(blob.getName()).setGeneration(blob.getGeneration());
        try {
            return AclEntry.fromProto(runWithRetries(new Callable<ObjectAccessControl>() {

                @Override
                public ObjectAccessControl call() {
                    return storageRpc.patchAcl(aclPb);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public List<AclEntry> listAcls(final BlobIdentifier blob) {
        try {
            List<ObjectAccessControl> answer = runWithRetries(new Callable<List<ObjectAccessControl>>() {

                @Override
                public List<ObjectAccessControl> call() {
                    return storageRpc.listAcls(blob.getBucket(), blob.getName(), blob.getGeneration());
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return Lists.transform(answer, AclEntry.FROM_OBJECT_PROTO_FN);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    public HmacSecretKey createHmacKey(final ServiceAccountInfo serviceAccount, final HmacKeyCreationOption... options) {
        try {
            return HmacSecretKey.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKey>() {

                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                    return storageRpc.createHmacKey(serviceAccount.getEmail(), optionMap(options));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public Page<HmacKeyInfo> listHmacKeys(ListHmacKeysOptions... options) {
        return listHmacKeys(getOptions(), optionMap(options));
    }

    @Override
    public HmacSecretKey.HmacKeyInfo getHmacKey(final String accessId, final GetHmacKeyRequestOption... options) {
        try {
            return HmacSecretKey.HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return storageRpc.getHmacKey(accessId, optionMap(options));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    private HmacKeyInfo updateHmacKey(final HmacSecretKey.HmacKeyInfo hmacKeyMetadata, final UpdateHmacKeyOptions... options) {
        try {
            return HmacKeyInfo.fromProto(runWithRetries(new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {

                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                    return storageRpc.updateHmacKey(hmacKeyMetadata.toProto(), optionMap(options));
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public HmacSecretKey.HmacKeyInfo updateHmacKeyState(final HmacSecretKey.HmacKeyInfo hmacKeyMetadata, final HmacSecretKey.HmacKeyStatus state, final UpdateHmacKeyOptions... options) {
        HmacKeyInfo updatedMetadata = HmacSecretKey.HmacKeyInfo.newAccessKeyBuilder(hmacKeyMetadata.getServiceAccount()).setProjectId(hmacKeyMetadata.getProjectId()).setAccessId(hmacKeyMetadata.getAccessId()).setState(state).buildHmacKeyInfo();
        return updateHmacKey(updatedMetadata, options);
    }

    @Override
    public void deleteHmacKey(final HmacKeyInfo metadata, final HmacKeyDeletionOption... options) {
        try {
            runWithRetries(new Callable<Void>() {

                @Override
                public Void call() {
                    storageRpc.deleteHmacKey(metadata.toProto(), optionMap(options));
                    return null;
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    private static Page<HmacSecretKey.HmacKeyInfo> listHmacKeys(final StorageSettings serviceOptions, final Map<CloudStorageRpcClient.StorageOption, ?> options) {
        try {
            Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> result = runWithRetries(new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {

                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> call() {
                    return serviceOptions.getStorageRpcV1().listHmacKeys(options);
                }
            }, serviceOptions.getRetrySettings(), EXCEPTION_HANDLER, serviceOptions.getClock());
            String cursor = result.x();
            final Iterable<HmacSecretKey.HmacKeyInfo> metadata = null == result.y() ? ImmutableList.<HmacKeyInfo>of() : Iterables.transform(result.y(), new Function<com.google.api.services.storage.model.HmacKeyMetadata, HmacKeyInfo>() {

                @Override
                public HmacSecretKey.HmacKeyInfo apply(com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                    return HmacSecretKey.HmacKeyInfo.fromProto(metadataPb);
                }
            });
            return new PageImpl<>(new HmacKeyMetadataPageFetcher(serviceOptions, options), cursor, metadata);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public Policy getIamPolicy(final String bucket, BucketSourceRequestOption... options) {
        try {
            final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
            return convertFromApiPolicy(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return storageRpc.getIamPolicy(bucket, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public Policy setIamPolicy(final String bucket, final Policy policy, BucketSourceRequestOption... options) {
        try {
            final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
            return convertFromApiPolicy(runWithRetries(new Callable<com.google.api.services.storage.model.Policy>() {

                @Override
                public com.google.api.services.storage.model.Policy call() {
                    return storageRpc.setIamPolicy(bucket, convertToApiPolicy(policy), optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public List<Boolean> testIamPermissions(final String bucket, final List<String> permissions, BucketSourceRequestOption... options) {
        try {
            final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
            TestIamPermissionsResponse response = runWithRetries(new Callable<TestIamPermissionsResponse>() {

                @Override
                public TestIamPermissionsResponse call() {
                    return storageRpc.testIamPermissions(bucket, permissions, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            final Set<String> heldPermissions = null != response.getPermissions() ? ImmutableSet.copyOf(response.getPermissions()) : ImmutableSet.<String>of();
            return Lists.transform(permissions, new Function<String, Boolean>() {

                @Override
                public Boolean apply(String permission) {
                    return heldPermissions.contains(permission);
                }
            });
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageBucket lockRetentionPolicy(BucketInfo bucketInfo, BucketTargetOptions... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = bucketInfo.toProto();
        final Map<CloudStorageRpcClient.StorageOption, ?> optionsMap = optionMap(bucketInfo, options);
        try {
            return StorageBucket.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Bucket>() {

                @Override
                public com.google.api.services.storage.model.Bucket call() {
                    return storageRpc.lockRetentionPolicy(bucketPb, optionsMap);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    @Override
    public ServiceAccountInfo getServiceAccount(final String projectId) {
        try {
            com.google.api.services.storage.model.ServiceAccount answer = runWithRetries(new Callable<com.google.api.services.storage.model.ServiceAccount>() {

                @Override
                public com.google.api.services.storage.model.ServiceAccount call() {
                    return storageRpc.getServiceAccount(projectId);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == answer ? null : ServiceAccountInfo.fromProto(answer);
        } catch (RetryHelperException e) {
            throw StorageOperationException.translateAndRethrow(e);
        }
    }

    private static <T> void addToOptionMap(CloudStorageRpcClient.StorageOption option, T defaultValue, Map<CloudStorageRpcClient.StorageOption, Object> map) {
        addToOptionMap(option, option, defaultValue, map);
    }

    private static <T> void addToOptionMap(CloudStorageRpcClient.StorageOption getOption, CloudStorageRpcClient.StorageOption putOption, T defaultValue, Map<CloudStorageRpcClient.StorageOption, Object> map) {
        if (map.containsKey(getOption)) {
            @SuppressWarnings("unchecked")
            T value = (T) map.remove(getOption);
            checkArgument(null != value || null != defaultValue, "Option " + getOption.getValue() + " is missing a value");
            value = firstNonNull(value, defaultValue);
            map.put(putOption, value);
        }
    }

    private static Map<CloudStorageRpcClient.StorageOption, ?> optionMap(Long generation, Long metaGeneration, Iterable<? extends RpcOptionEntry> options) {
        return optionMap(generation, metaGeneration, options, false);
    }

    private static Map<CloudStorageRpcClient.StorageOption, ?> optionMap(Long generation, Long metaGeneration, Iterable<? extends RpcOptionEntry> options, boolean useAsSource) {
        Map<CloudStorageRpcClient.StorageOption, Object> temp = Maps.newEnumMap(CloudStorageRpcClient.StorageOption.class);
        for (RpcOptionEntry option : options) {
            Object prev = temp.put(option.getRpcOption(), option.getValue());
            checkArgument(null == prev, "Duplicate option %s", option);
        }
        if (!Boolean.TRUE.equals(temp.get(DELIMITER))) {
            if (temp.get(DELIMITER) != null) {
                temp.put(DELIMITER, temp.get(DELIMITER));
            }
        } else {
            temp.remove(DELIMITER);
            temp.put(DELIMITER, PATH_DELIMITER);
        }
        if (!useAsSource) {
            addToOptionMap(IF_GENERATION_MATCH, generation, temp);
            addToOptionMap(IF_GENERATION_NOT_MATCH, generation, temp);
            addToOptionMap(IF_METAGENERATION_MATCH, metaGeneration, temp);
            addToOptionMap(IF_METAGENERATION_NOT_MATCH, metaGeneration, temp);
        } else {
            addToOptionMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, generation, temp);
            addToOptionMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, generation, temp);
            addToOptionMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGeneration, temp);
            addToOptionMap(IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGeneration, temp);
        }
        return ImmutableMap.copyOf(temp);
    }

    private static Map<CloudStorageRpcClient.StorageOption, ?> optionMap(RpcOptionEntry... options) {
        return optionMap(null, null, Arrays.asList(options));
    }

    private static Map<CloudStorageRpcClient.StorageOption, ?> optionMap(Long generation, Long metaGeneration, RpcOptionEntry... options) {
        return optionMap(generation, metaGeneration, Arrays.asList(options));
    }

    private static Map<CloudStorageRpcClient.StorageOption, ?> optionMap(BucketInfo bucketInfo, RpcOptionEntry... options) {
        return optionMap(null, bucketInfo.getMetageneration(), options);
    }

    static Map<CloudStorageRpcClient.StorageOption, ?> optionMap(BlobAttributes blobInfo, RpcOptionEntry... options) {
        return optionMap(blobInfo.getGeneration(), blobInfo.getMetageneration(), options);
    }

    static Map<CloudStorageRpcClient.StorageOption, ?> optionMap(BlobIdentifier blobId, RpcOptionEntry... options) {
        return optionMap(blobId.getGeneration(), null, options);
    }
}
