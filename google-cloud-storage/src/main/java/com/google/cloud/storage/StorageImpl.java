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
import static com.google.cloud.storage.PolicyHelper.convertToApiPolicy;
import static com.google.cloud.storage.SignedUrlEncodingHelper.Rfc3986UriEncode;
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
import static java.util.concurrent.Executors.callable;
import com.google.api.gax.paging.Page;
import com.google.api.gax.retrying.ResultRetryAlgorithm;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.ObjectAccessControl;
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
import com.google.cloud.storage.AccessControlEntry.AbstractEntity;
import com.google.cloud.storage.HmacSecretKey.HmacKeyDetails;
import com.google.cloud.storage.S3PostPolicyV4.ConditionTypeV4;
import com.google.cloud.storage.S3PostPolicyV4.PostConditionsV4Model;
import com.google.cloud.storage.S3PostPolicyV4.PostPolicyV4Payload;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.base.CharMatcher;
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
import com.google.common.io.CountingOutputStream;
import com.google.common.primitives.Ints;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.function.Function;

final class StorageImpl extends BaseService<StorageClientOptions> implements Storage {

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

    private final RetryAlgorithmManager retryAlgorithmManager;

    private final StorageRpcClient storageRpc;

    StorageImpl(StorageClientOptions options) {
        super(options);
        this.retryAlgorithmManager = options.getRetryAlgorithmManager();
        this.storageRpc = options.getStorageRpcV1();
    }

    @Override
    public StorageBucket create(BucketMetadata bucketInfo, BucketTargetOptions... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = bucketInfo.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(bucketInfo, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketsCreate(bucketPb, optionsMap);
        return run(algorithm, () -> storageRpc.create(bucketPb, optionsMap), (b) -> StorageBucket.fromProto(this, b));
    }

    @Override
    public StorageObject create(BlobMetadata blobInfo, BlobUploadOption... options) {
        BlobMetadata updatedInfo = blobInfo.toInfoBuilder().setMd5(EMPTY_BYTE_ARRAY_MD5).setCrc32c(EMPTY_BYTE_ARRAY_CRC32C).buildObject();
        return internalCreate(updatedInfo, EMPTY_BYTE_ARRAY, 0, 0, options);
    }

    @Override
    public StorageObject create(BlobMetadata blobInfo, byte[] content, BlobUploadOption... options) {
        content = firstNonNull(content, EMPTY_BYTE_ARRAY);
        BlobMetadata updatedInfo = blobInfo.toInfoBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(content).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(content).asInt()))).buildObject();
        return internalCreate(updatedInfo, content, 0, content.length, options);
    }

    @Override
    public StorageObject create(BlobMetadata blobInfo, byte[] content, int offset, int length, BlobUploadOption... options) {
        content = firstNonNull(content, EMPTY_BYTE_ARRAY);
        BlobMetadata updatedInfo = blobInfo.toInfoBuilder().setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(content, offset, length).asBytes())).setCrc32c(BaseEncoding.base64().encode(Ints.toByteArray(Hashing.crc32c().hashBytes(content, offset, length).asInt()))).buildObject();
        return internalCreate(updatedInfo, content, offset, length, options);
    }

    @Override
    @Deprecated
    public StorageObject create(BlobMetadata blobInfo, InputStream content, BlobWriteOptions... options) {
        Tuple<BlobMetadata, BlobUploadOption[]> targetOptions = BlobUploadOption.toBlobUploadOptions(blobInfo, options);
        com.google.api.services.storage.model.StorageObject blobPb = targetOptions.x().toProto();
        Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(targetOptions.x(), targetOptions.y());
        InputStream inputStreamParam = firstNonNull(content, new ByteArrayInputStream(EMPTY_BYTE_ARRAY));
        // retries are not safe when the input is an InputStream, so we can't retry.
        return StorageObject.fromProto(this, storageRpc.create(blobPb, inputStreamParam, optionsMap));
    }

    private StorageObject internalCreate(BlobMetadata info, final byte[] content, final int offset, final int length, BlobUploadOption... options) {
        Preconditions.checkNotNull(content);
        final com.google.api.services.storage.model.StorageObject blobPb = info.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(info, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsCreate(blobPb, optionsMap);
        return run(algorithm, () -> storageRpc.create(blobPb, new ByteArrayInputStream(content, offset, length), optionsMap), (x) -> StorageObject.fromProto(this, x));
    }

    @Override
    public StorageObject createFrom(BlobMetadata blobInfo, Path path, BlobWriteOptions... options) throws IOException {
        return createFrom(blobInfo, path, DEFAULT_BUFFER_SIZE, options);
    }

    @Override
    public StorageObject createFrom(BlobMetadata blobInfo, Path path, int bufferSize, BlobWriteOptions... options) throws IOException {
        if (Files.isDirectory(path)) {
            throw new StorageServiceException(0, path + " is a directory");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return createFrom(blobInfo, input, bufferSize, options);
        }
    }

    @Override
    public StorageObject createFrom(BlobMetadata blobInfo, InputStream content, BlobWriteOptions... options) throws IOException {
        return createFrom(blobInfo, content, DEFAULT_BUFFER_SIZE, options);
    }

    @Override
    public StorageObject createFrom(BlobMetadata blobInfo, InputStream content, int bufferSize, BlobWriteOptions... options) throws IOException {
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
    public StorageBucket get(String bucket, GetBucketOption... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = BucketMetadata.ofName(bucket).toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketsGet(bucketPb, optionsMap);
        return run(algorithm, () -> storageRpc.get(bucketPb, optionsMap), (b) -> StorageBucket.fromProto(this, b));
    }

    @Override
    public StorageObject get(String bucket, String blob, BlobGetOptions... options) {
        return get(BlobId.from(bucket, blob), options);
    }

    @Override
    public StorageObject get(BlobId blob, BlobGetOptions... options) {
        final com.google.api.services.storage.model.StorageObject storedObject = blob.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(blob, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsGet(storedObject, optionsMap);
        return run(algorithm, () -> storageRpc.get(storedObject, optionsMap), (x) -> StorageObject.fromProto(this, x));
    }

    @Override
    public StorageObject get(BlobId blob) {
        return get(blob, new BlobGetOptions[0]);
    }

    private static class BucketPageFetcher implements NextPageFetcher<StorageBucket> {

        private static final long serialVersionUID = 5850406828803613729L;

        private final Map<StorageRpcClient.StorageOption, ?> requestOptions;

        private final StorageClientOptions serviceOptions;

        BucketPageFetcher(StorageClientOptions serviceOptions, String cursor, Map<StorageRpcClient.StorageOption, ?> optionMap) {
            this.requestOptions = PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, cursor, optionMap);
            this.serviceOptions = serviceOptions;
        }

        @Override
        public Page<StorageBucket> getNextPage() {
            return listBuckets(serviceOptions, requestOptions);
        }
    }

    private static class BlobPageFetcher implements NextPageFetcher<StorageObject> {

        private static final long serialVersionUID = 81807334445874098L;

        private final Map<StorageRpcClient.StorageOption, ?> requestOptions;

        private final StorageClientOptions serviceOptions;

        private final String bucket;

        BlobPageFetcher(String bucket, StorageClientOptions serviceOptions, String cursor, Map<StorageRpcClient.StorageOption, ?> optionMap) {
            this.requestOptions = PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, cursor, optionMap);
            this.serviceOptions = serviceOptions;
            this.bucket = bucket;
        }

        @Override
        public Page<StorageObject> getNextPage() {
            return listBlobs(bucket, serviceOptions, requestOptions);
        }
    }

    private static class HmacKeyMetadataPageFetcher implements NextPageFetcher<HmacKeyDetails> {

        private static final long serialVersionUID = 308012320541700881L;

        private final StorageClientOptions serviceOptions;

        private final RetryAlgorithmManager retryAlgorithmManager;

        private final Map<StorageRpcClient.StorageOption, ?> options;

        HmacKeyMetadataPageFetcher(StorageClientOptions serviceOptions, RetryAlgorithmManager retryAlgorithmManager, Map<StorageRpcClient.StorageOption, ?> options) {
            this.serviceOptions = serviceOptions;
            this.retryAlgorithmManager = retryAlgorithmManager;
            this.options = options;
        }

        @Override
        public Page<HmacSecretKey.HmacKeyDetails> getNextPage() {
            return listHmacKeys(serviceOptions, retryAlgorithmManager, options);
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

    private static Page<StorageBucket> listBuckets(final StorageClientOptions serviceOptions, final Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        ResultRetryAlgorithm<?> algorithm = serviceOptions.getRetryAlgorithmManager().getForBucketsList(optionsMap);
        return Retrying.run(serviceOptions, algorithm, () -> serviceOptions.getStorageRpcV1().list(optionsMap), (result) -> {
            String cursor = result.x();
            Iterable<StorageBucket> buckets = null == result.y() ? ImmutableList.of() : Iterables.transform(result.y(), bucketPb -> StorageBucket.fromProto(serviceOptions.getService(), bucketPb));
            return new PageImpl<>(new BucketPageFetcher(serviceOptions, cursor, optionsMap), cursor, buckets);
        });
    }

    private static Page<StorageObject> listBlobs(final String bucket, final StorageClientOptions serviceOptions, final Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        ResultRetryAlgorithm<?> algorithm = serviceOptions.getRetryAlgorithmManager().getForObjectsList(bucket, optionsMap);
        return Retrying.run(serviceOptions, algorithm, () -> serviceOptions.getStorageRpcV1().list(bucket, optionsMap), (result) -> {
            String cursor = result.x();
            Iterable<StorageObject> blobs = null == result.y() ? ImmutableList.of() : Iterables.transform(result.y(), storageObject -> StorageObject.fromProto(serviceOptions.getService(), storageObject));
            return new PageImpl<>(new BlobPageFetcher(bucket, serviceOptions, cursor, optionsMap), cursor, blobs);
        });
    }

    @Override
    public StorageBucket update(BucketMetadata bucketInfo, BucketTargetOptions... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = bucketInfo.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(bucketInfo, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketsUpdate(bucketPb, optionsMap);
        return run(algorithm, () -> storageRpc.patch(bucketPb, optionsMap), (x) -> StorageBucket.fromProto(this, x));
    }

    @Override
    public StorageObject update(BlobMetadata blobInfo, BlobUploadOption... options) {
        final com.google.api.services.storage.model.StorageObject storageObject = blobInfo.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(blobInfo, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsUpdate(storageObject, optionsMap);
        return run(algorithm, () -> storageRpc.patch(storageObject, optionsMap), (x) -> StorageObject.fromProto(this, x));
    }

    @Override
    public StorageObject update(BlobMetadata blobInfo) {
        return update(blobInfo, new BlobUploadOption[0]);
    }

    @Override
    public boolean delete(String bucket, BucketSourceOptions... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = BucketMetadata.ofName(bucket).toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketsDelete(bucketPb, optionsMap);
        return run(algorithm, () -> storageRpc.delete(bucketPb, optionsMap), Function.identity());
    }

    @Override
    public boolean delete(String bucket, String blob, BlobSourceOptions... options) {
        return delete(BlobId.from(bucket, blob), options);
    }

    @Override
    public boolean delete(BlobId blob, BlobSourceOptions... options) {
        final com.google.api.services.storage.model.StorageObject storageObject = blob.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(blob, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsDelete(storageObject, optionsMap);
        return run(algorithm, () -> storageRpc.delete(storageObject, optionsMap), Function.identity());
    }

    @Override
    public boolean delete(BlobId blob) {
        return delete(blob, new BlobSourceOptions[0]);
    }

    @Override
    public StorageObject compose(final ComposeBlobsRequest composeRequest) {
        final List<com.google.api.services.storage.model.StorageObject> sources = Lists.newArrayListWithCapacity(composeRequest.getSourceBlobs().size());
        for (ComposeBlobsRequest.SourceBlobInfo sourceBlob : composeRequest.getSourceBlobs()) {
            sources.add(BlobMetadata.newBuilder(BlobId.from(composeRequest.getTarget().getBucket(), sourceBlob.getName(), sourceBlob.getGeneration())).buildObject().toProto());
        }
        final com.google.api.services.storage.model.StorageObject target = composeRequest.getTarget().toProto();
        final Map<StorageRpcClient.StorageOption, ?> targetOptions = optionMap(composeRequest.getTarget().getGeneration(), composeRequest.getTarget().getMetageneration(), composeRequest.getTargetOptions());
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsCompose(sources, target, targetOptions);
        return run(algorithm, () -> storageRpc.compose(sources, target, targetOptions), (x) -> StorageObject.fromProto(this, x));
    }

    @Override
    public ObjectCopyWriter copy(final ChunkedCopyRequest copyRequest) {
        final com.google.api.services.storage.model.StorageObject source = copyRequest.getSource().toProto();
        final Map<StorageRpcClient.StorageOption, ?> sourceOptions = optionMap(copyRequest.getSource().getGeneration(), null, copyRequest.getSourceOptions(), true);
        final com.google.api.services.storage.model.StorageObject targetObject = copyRequest.getTarget().toProto();
        final Map<StorageRpcClient.StorageOption, ?> targetOptions = optionMap(copyRequest.getTarget().getGeneration(), copyRequest.getTarget().getMetageneration(), copyRequest.getTargetOptions());
        StorageRpcClient.RewriteOperationRequest rewriteRequest = new StorageRpcClient.RewriteOperationRequest(source, sourceOptions, copyRequest.getOverrideInfo(), targetObject, targetOptions, copyRequest.getMegabytesCopiedPerChunk());
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsRewrite(rewriteRequest);
        return run(algorithm, () -> storageRpc.openRewrite(rewriteRequest), (r) -> new ObjectCopyWriter(getOptions(), r));
    }

    @Override
    public byte[] readAllBytes(String bucket, String blob, BlobSourceOptions... options) {
        return readAllBytes(BlobId.from(bucket, blob), options);
    }

    @Override
    public byte[] readAllBytes(BlobId blob, BlobSourceOptions... options) {
        final com.google.api.services.storage.model.StorageObject storageObject = blob.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(blob, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsGet(storageObject, optionsMap);
        return run(algorithm, () -> storageRpc.load(storageObject, optionsMap), Function.identity());
    }

    @Override
    public StorageOperationBatch batch() {
        return new StorageOperationBatch(this.getOptions());
    }

    @Override
    public ReadChannel reader(String bucket, String blob, BlobSourceOptions... options) {
        Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        return new BlobReadChannel(getOptions(), BlobId.from(bucket, blob), optionsMap);
    }

    @Override
    public ReadChannel reader(BlobId blob, BlobSourceOptions... options) {
        Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(blob, options);
        return new BlobReadChannel(getOptions(), blob, optionsMap);
    }

    @Override
    public void downloadTo(BlobId blob, Path path, BlobSourceOptions... options) {
        try (OutputStream outputStream = Files.newOutputStream(path)) {
            downloadTo(blob, outputStream, options);
        } catch (IOException e) {
            throw new StorageServiceException(e);
        }
    }

    @Override
    public void downloadTo(BlobId blob, OutputStream outputStream, BlobSourceOptions... options) {
        final CountingOutputStream countingOutputStream = new CountingOutputStream(outputStream);
        final com.google.api.services.storage.model.StorageObject pb = blob.toProto();
        final Map<StorageRpcClient.StorageOption, ?> requestOptions = optionMap(blob, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsGet(pb, requestOptions);
        Retrying.run(getOptions(), algorithm, callable(() -> {
            storageRpc.read(pb, requestOptions, countingOutputStream.getCount(), countingOutputStream);
        }), Function.identity());
    }

    @Override
    public BlobWriteChannel writer(BlobMetadata blobInfo, BlobWriteOptions... options) {
        Tuple<BlobMetadata, BlobUploadOption[]> targetOptions = BlobUploadOption.toBlobUploadOptions(blobInfo, options);
        return writer(targetOptions.x(), targetOptions.y());
    }

    @Override
    public BlobWriteChannel writer(URL signedURL) {
        // TODO: is it possible to know if a signed url is configured to have
        ResultRetryAlgorithm<?> // TODO: is it possible to know if a signed url is configured to have
        // TODO: is it possible to know if a signed url is configured to have
        forResumableUploadSessionCreate = retryAlgorithmManager.getForResumableUploadSessionCreate(Collections.emptyMap());
        // a constraint which makes it idempotent?
        return BlobWriteChannel.newBuilder().setStorageOptions(getOptions()).setUploadIdSupplier(ResumableMedia.startUploadForSignedUrl(getOptions(), signedURL, forResumableUploadSessionCreate)).setAlgorithmForWrite(retryAlgorithmManager.getForResumableUploadSessionWrite(optionMap())).build();
    }

    private BlobWriteChannel writer(BlobMetadata blobInfo, BlobUploadOption... options) {
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(blobInfo, options);
        return BlobWriteChannel.newBuilder().setStorageOptions(getOptions()).setUploadIdSupplier(ResumableMedia.startUploadForBlobInfo(getOptions(), blobInfo, optionsMap, retryAlgorithmManager.getForResumableUploadSessionCreate(optionsMap))).setAlgorithmForWrite(retryAlgorithmManager.getForResumableUploadSessionWrite(optionsMap)).build();
    }

    @Override
    public URL signUrl(BlobMetadata blobInfo, long duration, TimeUnit unit, UrlSigningOption... options) {
        EnumMap<UrlSigningOption.RequestOption, Object> optionMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
        for (UrlSigningOption option : options) {
            optionMap.put(option.getOption(), option.getValue());
        }
        boolean isV2 = getPreferredSignatureVersion(optionMap).equals(UrlSigningOption.SigningVersion.V2);
        boolean isV4 = getPreferredSignatureVersion(optionMap).equals(UrlSigningOption.SigningVersion.V4);
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
    public S3PostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobInfo, long duration, TimeUnit unit, S3PostPolicyV4.PostFieldsMapV4 fields, PostConditionsV4Model conditions, PostPolicyV4Parameter... options) {
        EnumMap<UrlSigningOption.RequestOption, Object> optionMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
        // Convert to a map of SignUrlOptions so we can re-use some utility methods
        for (PostPolicyV4Parameter option : options) {
            optionMap.put(UrlSigningOption.RequestOption.valueOf(option.getOption().name()), option.getValue());
        }
        optionMap.put(UrlSigningOption.RequestOption.SIGNATURE_VERSION, UrlSigningOption.SigningVersion.V4);
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
        S3PostPolicyV4.PostConditionsV4Model.PostPolicyBuilder conditionsBuilder = conditions.toPolicyBuilder();
        for (Map.Entry<String, String> entry : fields.getFieldsMap().entrySet()) {
            // Every field needs a corresponding policy condition, so add them if they're missing
            conditionsBuilder.addCustom(S3PostPolicyV4.ConditionTypeV4.MATCHES, entry.getKey(), entry.getValue());
            policyFields.put(entry.getKey(), entry.getValue());
        }
        PostConditionsV4Model v4Conditions = conditionsBuilder.addBucket(S3PostPolicyV4.ConditionTypeV4.MATCHES, blobInfo.getBucket()).addKey(S3PostPolicyV4.ConditionTypeV4.MATCHES, blobInfo.getName()).addCustom(S3PostPolicyV4.ConditionTypeV4.MATCHES, "x-goog-date", date).addCustom(ConditionTypeV4.MATCHES, "x-goog-credential", signingCredential).addCustom(S3PostPolicyV4.ConditionTypeV4.MATCHES, "x-goog-algorithm", "GOOG4-RSA-SHA256").buildModel();
        PostPolicyV4Payload document = S3PostPolicyV4.PostPolicyV4Payload.create(expirationFormat.format(timestamp + unit.toMillis(duration)), v4Conditions);
        String policy = BaseEncoding.base64().encode(document.toJsonString().getBytes());
        String signature = BaseEncoding.base16().encode(credentials.sign(policy.getBytes())).toLowerCase();
        for (S3PostPolicyV4.BinaryConditionV4 condition : v4Conditions.getConditions()) {
            if (ConditionTypeV4.MATCHES == condition.type) {
                policyFields.put(condition.operand1, condition.operand2);
            }
        }
        policyFields.put("key", blobInfo.getName());
        policyFields.put("x-goog-credential", signingCredential);
        policyFields.put("x-goog-algorithm", "GOOG4-RSA-SHA256");
        policyFields.put("x-goog-date", date);
        policyFields.put("x-goog-signature", signature);
        policyFields.put("policy", policy);
        policyFields.remove("bucket");
        return S3PostPolicyV4.create(url, policyFields);
    }

    public S3PostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobInfo, long duration, TimeUnit unit, S3PostPolicyV4.PostFieldsMapV4 fields, PostPolicyV4Parameter... options) {
        return generateSignedPostPolicyV4(blobInfo, duration, unit, fields, PostConditionsV4Model.newPolicyBuilder().buildModel(), options);
    }

    public S3PostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobInfo, long duration, TimeUnit unit, PostConditionsV4Model conditions, PostPolicyV4Parameter... options) {
        return generateSignedPostPolicyV4(blobInfo, duration, unit, S3PostPolicyV4.PostFieldsMapV4.newObjectMetadataBuilder().buildMap(), conditions, options);
    }

    public S3PostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobInfo, long duration, TimeUnit unit, PostPolicyV4Parameter... options) {
        return generateSignedPostPolicyV4(blobInfo, duration, unit, S3PostPolicyV4.PostFieldsMapV4.newObjectMetadataBuilder().buildMap(), options);
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
            boolean isV2 = getPreferredSignatureVersion(optionMap).equals(UrlSigningOption.SigningVersion.V2);
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

    private UrlSigningOption.SigningVersion getPreferredSignatureVersion(EnumMap<UrlSigningOption.RequestOption, Object> optionMap) {
        // Check for an explicitly specified version in the map.
        for (UrlSigningOption.SigningVersion version : UrlSigningOption.SigningVersion.values()) {
            if (version.equals(optionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION))) {
                return version;
            }
        }
        // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
        // Change this to V4 once we make it the default.
        return UrlSigningOption.SigningVersion.V2;
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
    private SignatureInfo buildSignatureInfo(Map<UrlSigningOption.RequestOption, Object> optionMap, BlobMetadata blobInfo, long expiration, URI path, String accountEmail) {
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
        signatureInfoBuilder.setSignatureVersion((UrlSigningOption.SigningVersion) optionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
        signatureInfoBuilder.setAccountEmail(accountEmail);
        signatureInfoBuilder.setTimestamp(getOptions().getClock().millisTime());
        ImmutableMap.Builder<String, String> extHeadersBuilder = new ImmutableMap.Builder<>();
        boolean isV4 = UrlSigningOption.SigningVersion.V4.equals(optionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
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
        ImmutableMap.Builder<String, String> queryParamsBuilder = new ImmutableMap.Builder<>();
        if (optionMap.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
            queryParamsBuilder.putAll((Map<String, String>) optionMap.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
        }
        return signatureInfoBuilder.setCanonicalizedExtensionHeaders(extHeadersBuilder.build()).setCanonicalizedQueryParams(queryParamsBuilder.build()).build();
    }

    private String slashlessBucketNameFromBlobInfo(BlobMetadata blobInfo) {
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
    public List<StorageObject> get(BlobId... blobIds) {
        return get(Arrays.asList(blobIds));
    }

    @Override
    public List<StorageObject> get(Iterable<BlobId> blobIds) {
        StorageOperationBatch batch = batch();
        final List<StorageObject> results = Lists.newArrayList();
        for (BlobId blob : blobIds) {
            batch.get(blob).notify(new BatchResult.Callback<StorageObject, StorageServiceException>() {

                @Override
                public void success(StorageObject result) {
                    results.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    results.add(null);
                }
            });
        }
        batch.submitBatch();
        return Collections.unmodifiableList(results);
    }

    @Override
    public List<StorageObject> update(BlobMetadata... blobInfos) {
        return update(Arrays.asList(blobInfos));
    }

    @Override
    public List<StorageObject> update(Iterable<BlobMetadata> blobInfos) {
        StorageOperationBatch batch = batch();
        final List<StorageObject> results = Lists.newArrayList();
        for (BlobMetadata blobInfo : blobInfos) {
            batch.modify(blobInfo).notify(new BatchResult.Callback<StorageObject, StorageServiceException>() {

                @Override
                public void success(StorageObject result) {
                    results.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    results.add(null);
                }
            });
        }
        batch.submitBatch();
        return Collections.unmodifiableList(results);
    }

    @Override
    public List<Boolean> delete(BlobId... blobIds) {
        return delete(Arrays.asList(blobIds));
    }

    @Override
    public List<Boolean> delete(Iterable<BlobId> blobIds) {
        StorageOperationBatch batch = batch();
        final List<Boolean> results = Lists.newArrayList();
        for (BlobId blob : blobIds) {
            batch.remove(blob).notify(new BatchResult.Callback<Boolean, StorageServiceException>() {

                @Override
                public void success(Boolean result) {
                    results.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                    results.add(Boolean.FALSE);
                }
            });
        }
        batch.submitBatch();
        return Collections.unmodifiableList(results);
    }

    @Override
    public AccessControlEntry getAcl(final String bucket, final AbstractEntity entity, BucketSourceOptions... options) {
        String pb = entity.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketAclGet(pb, optionsMap);
        return run(algorithm, () -> storageRpc.getAcl(bucket, pb, optionsMap), AccessControlEntry::fromProto);
    }

    @Override
    public AccessControlEntry getAcl(final String bucket, final AbstractEntity entity) {
        return getAcl(bucket, entity, new BucketSourceOptions[0]);
    }

    @Override
    public boolean deleteAcl(final String bucket, final AbstractEntity entity, BucketSourceOptions... options) {
        final String pb = entity.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketAclDelete(pb, optionsMap);
        return run(algorithm, () -> storageRpc.deleteAcl(bucket, pb, optionsMap), Function.identity());
    }

    @Override
    public boolean deleteAcl(final String bucket, final AbstractEntity entity) {
        return deleteAcl(bucket, entity, new BucketSourceOptions[0]);
    }

    @Override
    public AccessControlEntry createAcl(String bucket, AccessControlEntry acl, BucketSourceOptions... options) {
        final BucketAccessControl aclPb = acl.toBucketProto().setBucket(bucket);
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketAclCreate(aclPb, optionsMap);
        return run(algorithm, () -> storageRpc.createAcl(aclPb, optionsMap), AccessControlEntry::fromProto);
    }

    @Override
    public AccessControlEntry createAcl(String bucket, AccessControlEntry acl) {
        return createAcl(bucket, acl, new BucketSourceOptions[0]);
    }

    @Override
    public AccessControlEntry updateAcl(String bucket, AccessControlEntry acl, BucketSourceOptions... options) {
        final BucketAccessControl aclPb = acl.toBucketProto().setBucket(bucket);
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketAclUpdate(aclPb, optionsMap);
        return run(algorithm, () -> storageRpc.patchAcl(aclPb, optionsMap), AccessControlEntry::fromProto);
    }

    @Override
    public AccessControlEntry updateAcl(String bucket, AccessControlEntry acl) {
        return updateAcl(bucket, acl, new BucketSourceOptions[0]);
    }

    @Override
    public List<AccessControlEntry> listAcls(final String bucket, BucketSourceOptions... options) {
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketAclList(bucket, optionsMap);
        return run(algorithm, () -> storageRpc.listAcls(bucket, optionsMap), (answer) -> answer.stream().map(AccessControlEntry.FROM_BUCKET_PROTO_FUNCTION).collect(ImmutableList.toImmutableList()));
    }

    @Override
    public List<AccessControlEntry> listAcls(final String bucket) {
        return listAcls(bucket, new BucketSourceOptions[0]);
    }

    @Override
    public AccessControlEntry getDefaultAcl(final String bucket, final AbstractEntity entity) {
        String pb = entity.toProto();
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForDefaultObjectAclGet(pb);
        return run(algorithm, () -> storageRpc.getDefaultAcl(bucket, pb), AccessControlEntry::fromProto);
    }

    @Override
    public boolean deleteDefaultAcl(final String bucket, final AbstractEntity entity) {
        String pb = entity.toProto();
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForDefaultObjectAclDelete(pb);
        return run(algorithm, () -> storageRpc.deleteDefaultAcl(bucket, pb), Function.identity());
    }

    @Override
    public AccessControlEntry createDefaultAcl(String bucket, AccessControlEntry acl) {
        final ObjectAccessControl aclPb = acl.toObjectProto().setBucket(bucket);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForDefaultObjectAclCreate(aclPb);
        return run(algorithm, () -> storageRpc.createDefaultAcl(aclPb), AccessControlEntry::fromProto);
    }

    @Override
    public AccessControlEntry updateDefaultAcl(String bucket, AccessControlEntry acl) {
        final ObjectAccessControl aclPb = acl.toObjectProto().setBucket(bucket);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForDefaultObjectAclUpdate(aclPb);
        return run(algorithm, () -> storageRpc.patchDefaultAcl(aclPb), AccessControlEntry::fromProto);
    }

    @Override
    public List<AccessControlEntry> listDefaultAcls(final String bucket) {
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForDefaultObjectAclList(bucket);
        return run(algorithm, () -> storageRpc.listDefaultAcls(bucket), (answer) -> answer.stream().map(AccessControlEntry.FROM_OBJECT_PROTO_FUNCTION).collect(ImmutableList.toImmutableList()));
    }

    @Override
    public AccessControlEntry getAcl(final BlobId blob, final AbstractEntity entity) {
        String bucket = blob.getBucket();
        String name = blob.getName();
        Long generation = blob.getGeneration();
        String pb = entity.toProto();
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectAclGet(bucket, name, generation, pb);
        return run(algorithm, () -> storageRpc.getAcl(bucket, name, generation, pb), AccessControlEntry::fromProto);
    }

    @Override
    public boolean deleteAcl(final BlobId blob, final AbstractEntity entity) {
        String bucket = blob.getBucket();
        String name = blob.getName();
        Long generation = blob.getGeneration();
        String pb = entity.toProto();
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectAclDelete(bucket, name, generation, pb);
        return run(algorithm, () -> storageRpc.deleteAcl(bucket, name, generation, pb), Function.identity());
    }

    @Override
    public AccessControlEntry createAcl(final BlobId blob, final AccessControlEntry acl) {
        final ObjectAccessControl aclPb = acl.toObjectProto().setBucket(blob.getBucket()).setObject(blob.getName()).setGeneration(blob.getGeneration());
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectAclCreate(aclPb);
        return run(algorithm, () -> storageRpc.createAcl(aclPb), AccessControlEntry::fromProto);
    }

    @Override
    public AccessControlEntry updateAcl(BlobId blob, AccessControlEntry acl) {
        final ObjectAccessControl aclPb = acl.toObjectProto().setBucket(blob.getBucket()).setObject(blob.getName()).setGeneration(blob.getGeneration());
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectAclUpdate(aclPb);
        return run(algorithm, () -> storageRpc.patchAcl(aclPb), AccessControlEntry::fromProto);
    }

    @Override
    public List<AccessControlEntry> listAcls(final BlobId blob) {
        String bucket = blob.getBucket();
        String name = blob.getName();
        Long generation = blob.getGeneration();
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectAclList(bucket, name, generation);
        return run(algorithm, () -> storageRpc.listAcls(bucket, name, generation), (answer) -> answer.stream().map(AccessControlEntry.FROM_OBJECT_PROTO_FUNCTION).collect(ImmutableList.toImmutableList()));
    }

    public HmacSecretKey createHmacKey(final ServiceAccountInfo serviceAccount, final HmacKeyCreationOption... options) {
        String pb = serviceAccount.getEmail();
        Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForHmacKeyCreate(pb, optionsMap);
        return run(algorithm, () -> storageRpc.createHmacKey(pb, optionsMap), HmacSecretKey::fromProto);
    }

    @Override
    public Page<HmacKeyDetails> listHmacKeys(ListHmacKeysOptions... options) {
        return listHmacKeys(getOptions(), retryAlgorithmManager, optionMap(options));
    }

    @Override
    public HmacSecretKey.HmacKeyDetails getHmacKey(final String accessId, final RetrieveHmacKeyOption... options) {
        Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForHmacKeyGet(accessId, optionsMap);
        return run(algorithm, () -> storageRpc.getHmacKey(accessId, optionMap(options)), HmacSecretKey.HmacKeyDetails::fromProto);
    }

    private HmacSecretKey.HmacKeyDetails updateHmacKey(final HmacSecretKey.HmacKeyDetails hmacKeyMetadata, final HmacKeyUpdateOption... options) {
        com.google.api.services.storage.model.HmacKeyMetadata pb = hmacKeyMetadata.toProto();
        Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForHmacKeyUpdate(pb, optionsMap);
        return run(algorithm, () -> storageRpc.updateHmacKey(pb, optionsMap), HmacKeyDetails::fromProto);
    }

    @Override
    public HmacSecretKey.HmacKeyDetails updateHmacKeyState(final HmacSecretKey.HmacKeyDetails hmacKeyMetadata, final HmacSecretKey.HmacKeyStatus state, final HmacKeyUpdateOption... options) {
        HmacSecretKey.HmacKeyDetails updatedMetadata = HmacKeyDetails.newServiceAccountBuilder(hmacKeyMetadata.getServiceAccount()).setProjectId(hmacKeyMetadata.getProjectId()).setAccessId(hmacKeyMetadata.getAccessId()).setState(state).buildHmacKeyDetails();
        return updateHmacKey(updatedMetadata, options);
    }

    @Override
    public void deleteHmacKey(final HmacKeyDetails metadata, final DeleteHmacKeyRequestOption... options) {
        com.google.api.services.storage.model.HmacKeyMetadata pb = metadata.toProto();
        Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForHmacKeyDelete(pb, optionsMap);
        run(algorithm, (Callable<Void>) () -> {
            storageRpc.deleteHmacKey(pb, optionsMap);
            return null;
        }, Function.identity());
    }

    private static Page<HmacKeyDetails> listHmacKeys(final StorageClientOptions serviceOptions, final RetryAlgorithmManager retryAlgorithmManager, final Map<StorageRpcClient.StorageOption, ?> options) {
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForHmacKeyList(options);
        return Retrying.run(serviceOptions, algorithm, () -> serviceOptions.getStorageRpcV1().listHmacKeys(options), (result) -> {
            String cursor = result.x();
            final Iterable<HmacKeyDetails> metadata = null == result.y() ? ImmutableList.of() : Iterables.transform(result.y(), HmacKeyDetails::fromProto);
            return new PageImpl<>(new HmacKeyMetadataPageFetcher(serviceOptions, retryAlgorithmManager, options), cursor, metadata);
        });
    }

    @Override
    public Policy getIamPolicy(final String bucket, BucketSourceOptions... options) {
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketsGetIamPolicy(bucket, optionsMap);
        return run(algorithm, () -> storageRpc.getIamPolicy(bucket, optionsMap), PolicyHelper::convertFromApiPolicy);
    }

    @Override
    public Policy setIamPolicy(final String bucket, final Policy policy, BucketSourceOptions... options) {
        com.google.api.services.storage.model.Policy pb = convertToApiPolicy(policy);
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketsSetIamPolicy(bucket, pb, optionsMap);
        return run(algorithm, () -> storageRpc.setIamPolicy(bucket, pb, optionsMap), PolicyHelper::convertFromApiPolicy);
    }

    @Override
    public List<Boolean> testIamPermissions(final String bucket, final List<String> permissions, BucketSourceOptions... options) {
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketsTestIamPermissions(bucket, permissions, optionsMap);
        return run(algorithm, () -> storageRpc.testIamPermissions(bucket, permissions, optionsMap), (response) -> {
            final Set<String> heldPermissions = null != response.getPermissions() ? ImmutableSet.copyOf(response.getPermissions()) : ImmutableSet.<String>of();
            return permissions.stream().map(heldPermissions::contains).collect(ImmutableList.toImmutableList());
        });
    }

    @Override
    public StorageBucket lockRetentionPolicy(BucketMetadata bucketInfo, BucketTargetOptions... options) {
        final com.google.api.services.storage.model.Bucket bucketPb = bucketInfo.toProto();
        final Map<StorageRpcClient.StorageOption, ?> optionsMap = optionMap(bucketInfo, options);
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForBucketsLockRetentionPolicy(bucketPb, optionsMap);
        return run(algorithm, () -> storageRpc.lockRetentionPolicy(bucketPb, optionsMap), (x) -> StorageBucket.fromProto(this, x));
    }

    @Override
    public ServiceAccountInfo getServiceAccount(final String projectId) {
        ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForServiceAccountGet(projectId);
        return run(algorithm, () -> storageRpc.getServiceAccount(projectId), ServiceAccountInfo::fromProto);
    }

    private <T, U> U run(ResultRetryAlgorithm<?> algorithm, Callable<T> c, Function<T, U> f) {
        return Retrying.run(getOptions(), algorithm, c, f);
    }

    @Override
    public StorageNotification createNotification(final String bucket, final NotificationMetadata notificationInfo) {
        final com.google.api.services.storage.model.Notification notificationPb = notificationInfo.toProto();
        try {
            return StorageNotification.fromProto(this, runWithRetries(new Callable<com.google.api.services.storage.model.Notification>() {

                @Override
                public com.google.api.services.storage.model.Notification call() {
                    return storageRpc.createNotification(bucket, notificationPb);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock()));
        } catch (RetryHelperException e) {
            throw StorageServiceException.translateAndRethrow(e);
        }
    }

    @Override
    public StorageNotification getNotification(final String bucket, final String notificationId) {
        try {
            com.google.api.services.storage.model.Notification answer = runWithRetries(new Callable<com.google.api.services.storage.model.Notification>() {

                @Override
                public com.google.api.services.storage.model.Notification call() {
                    return storageRpc.getNotification(bucket, notificationId);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == answer ? null : StorageNotification.fromProto(this, answer);
        } catch (RetryHelperException e) {
            throw StorageServiceException.translateAndRethrow(e);
        }
    }

    @Override
    public List<StorageNotification> listNotifications(final String bucket) {
        try {
            List<com.google.api.services.storage.model.Notification> answer = runWithRetries(new Callable<List<com.google.api.services.storage.model.Notification>>() {

                @Override
                public List<com.google.api.services.storage.model.Notification> call() {
                    return storageRpc.listNotifications(bucket);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
            return null == answer ? ImmutableList.<StorageNotification>of() : Lists.transform(answer, new com.google.common.base.Function<com.google.api.services.storage.model.Notification, StorageNotification>() {

                @Override
                public StorageNotification apply(com.google.api.services.storage.model.Notification notificationPb) {
                    return StorageNotification.fromProto(getOptions().getService(), notificationPb);
                }
            });
        } catch (RetryHelperException e) {
            throw StorageServiceException.translateAndRethrow(e);
        }
    }

    @Override
    public boolean deleteNotification(final String bucket, final String notificationId) {
        try {
            return runWithRetries(new Callable<Boolean>() {

                @Override
                public Boolean call() {
                    return storageRpc.deleteNotification(bucket, notificationId);
                }
            }, getOptions().getRetrySettings(), EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelperException e) {
            throw StorageServiceException.translateAndRethrow(e);
        }
    }

    private static <T> void addToOptionMap(StorageRpcClient.StorageOption option, T defaultValue, Map<StorageRpcClient.StorageOption, Object> map) {
        addToOptionMap(option, option, defaultValue, map);
    }

    private static <T> void addToOptionMap(StorageRpcClient.StorageOption getOption, StorageRpcClient.StorageOption putOption, T defaultValue, Map<StorageRpcClient.StorageOption, Object> map) {
        if (map.containsKey(getOption)) {
            @SuppressWarnings("unchecked")
            T value = (T) map.remove(getOption);
            checkArgument(null != value || null != defaultValue, "Option " + getOption.getValue() + " is missing a value");
            value = firstNonNull(value, defaultValue);
            map.put(putOption, value);
        }
    }

    private static Map<StorageRpcClient.StorageOption, ?> optionMap(Long generation, Long metaGeneration, Iterable<? extends AbstractOption> options) {
        return optionMap(generation, metaGeneration, options, false);
    }

    private static Map<StorageRpcClient.StorageOption, ?> optionMap(Long generation, Long metaGeneration, Iterable<? extends AbstractOption> options, boolean useAsSource) {
        Map<StorageRpcClient.StorageOption, Object> temp = Maps.newEnumMap(StorageRpcClient.StorageOption.class);
        for (AbstractOption option : options) {
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

    private static Map<StorageRpcClient.StorageOption, ?> optionMap(AbstractOption... options) {
        return optionMap(null, null, Arrays.asList(options));
    }

    private static Map<StorageRpcClient.StorageOption, ?> optionMap(Long generation, Long metaGeneration, AbstractOption... options) {
        return optionMap(generation, metaGeneration, Arrays.asList(options));
    }

    private static Map<StorageRpcClient.StorageOption, ?> optionMap(BucketMetadata bucketInfo, AbstractOption... options) {
        return optionMap(null, bucketInfo.getMetageneration(), options);
    }

    static Map<StorageRpcClient.StorageOption, ?> optionMap(BlobMetadata blobInfo, AbstractOption... options) {
        return optionMap(blobInfo.getGeneration(), blobInfo.getMetageneration(), options);
    }

    static Map<StorageRpcClient.StorageOption, ?> optionMap(BlobId blobId, AbstractOption... options) {
        return optionMap(blobId.getGeneration(), null, options);
    }
}
