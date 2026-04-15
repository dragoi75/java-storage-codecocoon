/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy from the License at
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
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.DELIMITER;
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.IF_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.IF_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.IF_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.IF_METAGENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.IF_SOURCE_GENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.IF_SOURCE_GENERATION_NOT_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.IF_SOURCE_METAGENERATION_MATCH;
import static com.google.cloud.storage.spi.v1.StorageRpc.Option.IF_SOURCE_METAGENERATION_NOT_MATCH;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.gax.paging.Page;
import com.google.api.gax.retrying.ResultRetryAlgorithm;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.api.services.storage.model.StorageObject;
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
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.HmacKey.HmacKeyMetadata;
import com.google.cloud.storage.PostPolicyV4.ConditionV4Type;
import com.google.cloud.storage.PostPolicyV4.PostConditionsV4;
import com.google.cloud.storage.PostPolicyV4.PostFieldsV4;
import com.google.cloud.storage.PostPolicyV4.PostPolicyV4Document;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.cloud.storage.spi.v1.StorageRpc.RewriteRequest;
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
import java.util.function.Function;

final class StorageServiceImpl extends BaseService<StorageOptions> implements Storage {

  private static final byte[] ZERO_LENGTH_BYTE_ARRAY = {};
  private static final String EMPTY_MD5_HASH = "1B2M2Y8AsgTpgAmY7PhCfg==";
  private static final String EMPTY_CRC32C_HASH = "AAAAAA==";
  private static final String PATH_SEPARATOR = "/";
  /** Signed URLs are only supported through the GCS XML API endpoint. */
  private static final String STORAGE_XML_SCHEME = "https";

  private static final String STORAGE_XML_HOST = "storage.googleapis.com";

  private static final int BUFFER_SIZE_DEFAULT = 15 * 1024 * 1024;
  private static final int BUFFER_SIZE_MIN = 256 * 1024;

  private final RetryAlgorithmManager retryManager;
  private final StorageRpc storageBackendRpc;

  StorageServiceImpl(StorageOptions serviceOptions) {
    super(serviceOptions);
    this.retryManager = serviceOptions.getRetryAlgorithmManager();
    this.storageBackendRpc = serviceOptions.getStorageRpcV1();
  }

  @Override
  public Bucket create(BucketInfo info, BucketOption... serviceOptions) {
    final com.google.api.services.storage.model.Bucket bucketProto = info.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(info, serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketsCreate(bucketProto, optionMap);
    return execute(
            retryAlgorithm, () -> storageBackendRpc.create(bucketProto, optionMap), (builder) -> Bucket.fromPb(this, builder));
  }

  @Override
  public StorageBlob create(BlobInfo blobMetadata, BlobUploadOption... serviceOptions) {
    BlobInfo updatedMetadata =
        blobMetadata
            .toBuilder()
            .setMd5(EMPTY_MD5_HASH)
            .setCrc32c(EMPTY_CRC32C_HASH)
            .build();
    return createInternal(updatedMetadata, ZERO_LENGTH_BYTE_ARRAY, 0, 0, serviceOptions);
  }

  @Override
  public StorageBlob create(BlobInfo blobMetadata, byte[] data, BlobUploadOption... serviceOptions) {
    data = firstNonNull(data, ZERO_LENGTH_BYTE_ARRAY);
    BlobInfo updatedMetadata =
        blobMetadata
            .toBuilder()
            .setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(data).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(Ints.toByteArray(Hashing.crc32c().hashBytes(data).asInt())))
            .build();
    return createInternal(updatedMetadata, data, 0, data.length, serviceOptions);
  }

  @Override
  public StorageBlob create(
          BlobInfo blobMetadata, byte[] data, int startOffset, int count, BlobUploadOption... serviceOptions) {
    data = firstNonNull(data, ZERO_LENGTH_BYTE_ARRAY);
    BlobInfo updatedMetadata =
        blobMetadata
            .toBuilder()
            .setMd5(
                BaseEncoding.base64()
                    .encode(Hashing.md5().hashBytes(data, startOffset, count).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(
                        Ints.toByteArray(
                            Hashing.crc32c().hashBytes(data, startOffset, count).asInt())))
            .build();
    return createInternal(updatedMetadata, data, startOffset, count, serviceOptions);
  }

  @Override
  @Deprecated
  public StorageBlob create(BlobInfo blobMetadata, InputStream data, BlobWriteOptions... serviceOptions) {
    Tuple<BlobInfo, BlobUploadOption[]> targetTuple = BlobUploadOption.toBlobUploadOptions(blobMetadata, serviceOptions);
    StorageObject objectProto = targetTuple.x().toPb();
    Map<StorageRpc.Option, ?> optionMap = optionsMap(targetTuple.x(), targetTuple.y());
    InputStream inputStream =
        firstNonNull(data, new ByteArrayInputStream(ZERO_LENGTH_BYTE_ARRAY));
    // retries are not safe when the input is an InputStream, so we can't retry.
    return StorageBlob.fromProto(this, storageBackendRpc.create(objectProto, inputStream, optionMap));
  }

  private StorageBlob createInternal(
      BlobInfo blobInfoParam,
      final byte[] data,
      final int startOffset,
      final int count,
      BlobUploadOption... serviceOptions) {
    Preconditions.checkNotNull(data);
    final StorageObject objectProto = blobInfoParam.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(blobInfoParam, serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectsCreate(objectProto, optionMap);
    return execute(
            retryAlgorithm,
        () ->
            storageBackendRpc.create(
                    objectProto, new ByteArrayInputStream(data, startOffset, count), optionMap),
        (x) -> StorageBlob.fromProto(this, x));
  }

  @Override
  public StorageBlob createFrom(BlobInfo blobMetadata, Path resourceUri, BlobWriteOptions... serviceOptions)
      throws IOException {
    return createFrom(blobMetadata, resourceUri, BUFFER_SIZE_DEFAULT, serviceOptions);
  }

  @Override
  public StorageBlob createFrom(BlobInfo blobMetadata, Path resourceUri, int chunkSize, BlobWriteOptions... serviceOptions)
      throws IOException {
    if (Files.isDirectory(resourceUri)) {
      throw new StorageException(0, resourceUri + " is a directory");
    }
    try (InputStream inStream = Files.newInputStream(resourceUri)) {
      return createFrom(blobMetadata, inStream, chunkSize, serviceOptions);
    }
  }

  @Override
  public StorageBlob createFrom(BlobInfo blobMetadata, InputStream data, BlobWriteOptions... serviceOptions)
      throws IOException {
    return createFrom(blobMetadata, data, BUFFER_SIZE_DEFAULT, serviceOptions);
  }

  @Override
  public StorageBlob createFrom(
          BlobInfo blobMetadata, InputStream data, int chunkSize, BlobWriteOptions... serviceOptions)
      throws IOException {

    BlobWriteChannel writeChannel;
    try (WriteChannel uploader = writer(blobMetadata, serviceOptions)) {
      writeChannel = (BlobWriteChannel) uploader;
      uploadStream(Channels.newChannel(data), uploader, chunkSize);
    }
    StorageObject objectMessage = writeChannel.getStorageObject();
    return StorageBlob.fromProto(this, objectMessage);
  }

  /*
   * Uploads the given content to the storage using specified write channel and the given buffer
   * size. This method does not close any channels.
   */
  private static void uploadStream(ReadableByteChannel readChannel, WriteChannel writeChannel, int chunkSize)
      throws IOException {
    chunkSize = Math.max(chunkSize, BUFFER_SIZE_MIN);
    ByteBuffer byteBuffer = ByteBuffer.allocate(chunkSize);
    writeChannel.setChunkSize(chunkSize);

    while (readChannel.read(byteBuffer) >= 0) {
      byteBuffer.flip();
      writeChannel.write(byteBuffer);
      byteBuffer.clear();
    }
  }

  @Override
  public Bucket get(String bucketName, BucketGetOptions... serviceOptions) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.of(bucketName).toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketsGet(bucketProto, optionMap);
    return execute(
            retryAlgorithm, () -> storageBackendRpc.get(bucketProto, optionMap), (builder) -> Bucket.fromPb(this, builder));
  }

  @Override
  public StorageBlob get(String bucketName, String blobName, BlobGetOptions... serviceOptions) {
    return get(BlobId.of(bucketName, blobName), serviceOptions);
  }

  @Override
  public StorageBlob get(BlobId blobName, BlobGetOptions... serviceOptions) {
    final StorageObject retrievedObject = blobName.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(blobName, serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectsGet(retrievedObject, optionMap);
    return execute(
            retryAlgorithm, () -> storageBackendRpc.get(retrievedObject, optionMap), (x) -> StorageBlob.fromProto(this, x));
  }

  @Override
  public StorageBlob get(BlobId blobName) {
    return get(blobName, new BlobGetOptions[0]);
  }

  private static class BucketPageRetriever implements NextPageFetcher<Bucket> {

    private static final long SERIAL_VERSION_UID = 5850406828803613729L;
    private final Map<StorageRpc.Option, ?> requestOptionsMap;
    private final StorageOptions storageOptions;

    BucketPageRetriever(
            StorageOptions storageOptions, String pageCursor, Map<StorageRpc.Option, ?> optionsMap) {
      this.requestOptionsMap =
          PageImpl.nextRequestOptions(StorageRpc.Option.PAGE_TOKEN, pageCursor, optionsMap);
      this.storageOptions = storageOptions;
    }

    @Override
    public Page<Bucket> getNextPage() {
      return listBucketsPage(storageOptions, requestOptionsMap);
    }
  }

  private static class BlobPageRetriever implements NextPageFetcher<StorageBlob> {

    private static final long SERIAL_VERSION_UID = 81807334445874098L;
    private final Map<StorageRpc.Option, ?> requestOptionsMap;
    private final StorageOptions storageOptions;
    private final String bucketName;

    BlobPageRetriever(
        String bucketName,
        StorageOptions storageOptions,
        String pageCursor,
        Map<StorageRpc.Option, ?> optionsMap) {
      this.requestOptionsMap =
          PageImpl.nextRequestOptions(StorageRpc.Option.PAGE_TOKEN, pageCursor, optionsMap);
      this.storageOptions = storageOptions;
      this.bucketName = bucketName;
    }

    @Override
    public Page<StorageBlob> getNextPage() {
      return listBlobsInBucket(bucketName, storageOptions, requestOptionsMap);
    }
  }

  private static class HmacKeyMetadataPager implements NextPageFetcher<HmacKeyMetadata> {

    private static final long SERIAL_VERSION_UID = 308012320541700881L;
    private final StorageOptions storageOptions;
    private final RetryAlgorithmManager retryManager;
    private final Map<StorageRpc.Option, ?> serviceOptions;

    HmacKeyMetadataPager(
        StorageOptions storageOptions,
        RetryAlgorithmManager retryManager,
        Map<StorageRpc.Option, ?> serviceOptions) {
      this.storageOptions = storageOptions;
      this.retryManager = retryManager;
      this.serviceOptions = serviceOptions;
    }

    @Override
    public Page<HmacKeyMetadata> getNextPage() {
      return listHmacKeyMetadata(storageOptions, retryManager, serviceOptions);
    }
  }

  @Override
  public Page<Bucket> list(BucketListOptions... serviceOptions) {
    return listBucketsPage(getOptions(), optionsMap(serviceOptions));
  }

  @Override
  public Page<StorageBlob> list(final String bucketName, BlobListOptions... serviceOptions) {
    return listBlobsInBucket(bucketName, getOptions(), optionsMap(serviceOptions));
  }

  private static Page<Bucket> listBucketsPage(
          final StorageOptions storageOptions, final Map<StorageRpc.Option, ?> optionMap) {
    ResultRetryAlgorithm<?> retryAlgorithm =
        storageOptions.getRetryAlgorithmManager().getForBucketsList(optionMap);
    return Retrying.run(
            storageOptions,
            retryAlgorithm,
        () -> storageOptions.getStorageRpcV1().list(optionMap),
        (operationResult) -> {
          String pageCursor = operationResult.x();
          Iterable<Bucket> bucketList =
              operationResult.y() == null
                  ? ImmutableList.of()
                  : Iterables.transform(
                      operationResult.y(), bucketProto -> Bucket.fromPb(storageOptions.getService(), bucketProto));
          return new PageImpl<>(
              new BucketPageRetriever(storageOptions, pageCursor, optionMap), pageCursor, bucketList);
        });
  }

  private static Page<StorageBlob> listBlobsInBucket(
      final String bucketName,
      final StorageOptions storageOptions,
      final Map<StorageRpc.Option, ?> optionMap) {
    ResultRetryAlgorithm<?> retryAlgorithm =
        storageOptions.getRetryAlgorithmManager().getForObjectsList(bucketName, optionMap);
    return Retrying.run(
            storageOptions,
            retryAlgorithm,
        () -> storageOptions.getStorageRpcV1().list(bucketName, optionMap),
        (operationResult) -> {
          String pageCursor = operationResult.x();
          Iterable<StorageBlob> blobList =
              operationResult.y() == null
                  ? ImmutableList.of()
                  : Iterables.transform(
                      operationResult.y(),
                      storageObject -> StorageBlob.fromProto(storageOptions.getService(), storageObject));
          return new PageImpl<>(
              new BlobPageRetriever(bucketName, storageOptions, pageCursor, optionMap), pageCursor, blobList);
        });
  }

  @Override
  public Bucket update(BucketInfo info, BucketOption... serviceOptions) {
    final com.google.api.services.storage.model.Bucket bucketProto = info.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(info, serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketsUpdate(bucketProto, optionMap);
    return execute(
            retryAlgorithm, () -> storageBackendRpc.patch(bucketProto, optionMap), (x) -> Bucket.fromPb(this, x));
  }

  @Override
  public StorageBlob update(BlobInfo blobMetadata, BlobUploadOption... serviceOptions) {
    final StorageObject storageObject = blobMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(blobMetadata, serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectsUpdate(storageObject, optionMap);
    return execute(
            retryAlgorithm, () -> storageBackendRpc.patch(storageObject, optionMap), (x) -> StorageBlob.fromProto(this, x));
  }

  @Override
  public StorageBlob update(BlobInfo blobMetadata) {
    return update(blobMetadata, new BlobUploadOption[0]);
  }

  @Override
  public boolean delete(String bucketName, BucketRequestOption... serviceOptions) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.of(bucketName).toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketsDelete(bucketProto, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.delete(bucketProto, optionMap), Function.identity());
  }

  @Override
  public boolean delete(String bucketName, String blobName, BlobReadOption... serviceOptions) {
    return delete(BlobId.of(bucketName, blobName), serviceOptions);
  }

  @Override
  public boolean delete(BlobId blobName, BlobReadOption... serviceOptions) {
    final StorageObject storageObject = blobName.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(blobName, serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectsDelete(storageObject, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.delete(storageObject, optionMap), Function.identity());
  }

  @Override
  public boolean delete(BlobId blobName) {
    return delete(blobName, new BlobReadOption[0]);
  }

  @Override
  public StorageBlob compose(final ComposeBlobsRequest composeRequest) {
    final List<StorageObject> sources =
        Lists.newArrayListWithCapacity(composeRequest.getSourceBlobs().size());
    for (ComposeBlobsRequest.SourceBlobInfo sourceBlob : composeRequest.getSourceBlobs()) {
      sources.add(
          BlobInfo.newBuilder(
                  BlobId.of(
                      composeRequest.getTarget().getBucket(),
                      sourceBlob.getName(),
                      sourceBlob.getGeneration()))
              .build()
              .toPb());
    }
    final StorageObject target = composeRequest.getTarget().toPb();
    final Map<StorageRpc.Option, ?> targetTuple =
        optionsMap(
            composeRequest.getTarget().getGeneration(),
            composeRequest.getTarget().getMetageneration(),
            composeRequest.getTargetOptions());
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectsCompose(sources, target, targetTuple);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.compose(sources, target, targetTuple),
        (x) -> StorageBlob.fromProto(this, x));
  }

  @Override
  public CopyWriter copy(final CopyOperationRequest copyRequest) {
    final StorageObject sourceObject = copyRequest.getSource().toPb();
    final Map<StorageRpc.Option, ?> sourceOptionMap =
        optionsMap(
            copyRequest.getSource().getGeneration(), null, copyRequest.getSourceOptions(), true);
    final StorageObject targetStorageObject = copyRequest.getTarget().toPb();
    final Map<StorageRpc.Option, ?> targetTuple =
        optionsMap(
            copyRequest.getTarget().getGeneration(),
            copyRequest.getTarget().getMetageneration(),
            copyRequest.getTargetOptions());
    RewriteRequest rewriteReq =
        new RewriteRequest(
                sourceObject,
                sourceOptionMap,
            copyRequest.getOverrideInfo(),
                targetStorageObject,
                targetTuple,
            copyRequest.getMegabytesCopiedPerChunk());
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForObjectsRewrite(rewriteReq);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.openRewrite(rewriteReq),
        (resolver) -> new CopyWriter(getOptions(), resolver));
  }

  @Override
  public byte[] readAllBytes(String bucketName, String blobName, BlobReadOption... serviceOptions) {
    return readAllBytes(BlobId.of(bucketName, blobName), serviceOptions);
  }

  @Override
  public byte[] readAllBytes(BlobId blobName, BlobReadOption... serviceOptions) {
    final StorageObject storageObject = blobName.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(blobName, serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectsGet(storageObject, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.load(storageObject, optionMap), Function.identity());
  }

  @Override
  public StorageBatch batch() {
    return new StorageBatch(this.getOptions());
  }

  @Override
  public ReadChannel reader(String bucketName, String blobName, BlobReadOption... serviceOptions) {
    Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    return new BlobReadChannel(getOptions(), BlobId.of(bucketName, blobName), optionMap);
  }

  @Override
  public ReadChannel reader(BlobId blobName, BlobReadOption... serviceOptions) {
    Map<StorageRpc.Option, ?> optionMap = optionsMap(blobName, serviceOptions);
    return new BlobReadChannel(getOptions(), blobName, optionMap);
  }

  @Override
  public BlobWriteChannel writer(BlobInfo blobMetadata, BlobWriteOptions... serviceOptions) {
    Tuple<BlobInfo, BlobUploadOption[]> targetTuple = BlobUploadOption.toBlobUploadOptions(blobMetadata, serviceOptions);
    return createWriter(targetTuple.x(), targetTuple.y());
  }

  @Override
  public BlobWriteChannel writer(URL signedURL) {
    ResultRetryAlgorithm<?> forResumableUploadSessionCreate =
        retryManager.getForResumableUploadSessionCreate(
            Collections
                .emptyMap()); // TODO: is it possible to know if a signed url is configured to have
    // a constraint which makes it idempotent?
    return BlobWriteChannel.newBuilder()
        .setStorageOptions(getOptions())
        .setUploadIdSupplier(
            ResumableMedia.startUploadForSignedUrl(
                getOptions(), signedURL, forResumableUploadSessionCreate))
        .setAlgorithmForWrite(retryManager.getForResumableUploadSessionWrite(optionsMap()))
        .build();
  }

  private BlobWriteChannel createWriter(BlobInfo blobMetadata, BlobUploadOption... serviceOptions) {
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(blobMetadata, serviceOptions);
    return BlobWriteChannel.newBuilder()
        .setStorageOptions(getOptions())
        .setUploadIdSupplier(
            ResumableMedia.startUploadForBlobInfo(
                getOptions(),
                    blobMetadata,
                    optionMap,
                retryManager.getForResumableUploadSessionCreate(optionMap)))
        .setAlgorithmForWrite(retryManager.getForResumableUploadSessionWrite(optionMap))
        .build();
  }

  @Override
  public URL signUrl(BlobInfo blobMetadata, long timeDuration, TimeUnit timeUnit, UrlSigningOption... serviceOptions) {
    EnumMap<UrlSigningOption.RequestOption, Object> optionsMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
    for (UrlSigningOption signOption : serviceOptions) {
      optionsMap.put(signOption.getOption(), signOption.getValue());
    }

    boolean version2Enabled =
        getPreferredSignatureVersion(optionsMap).equals(UrlSigningOption.SignatureSchemeVersion.V2);
    boolean version4Enabled =
        getPreferredSignatureVersion(optionsMap).equals(UrlSigningOption.SignatureSchemeVersion.V4);

    ServiceAccountSigner signerCredentials =
        (ServiceAccountSigner) optionsMap.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
    if (signerCredentials == null) {
      checkState(
          this.getOptions().getCredentials() instanceof ServiceAccountSigner,
          "Signing key was not provided and could not be derived");
      signerCredentials = (ServiceAccountSigner) this.getOptions().getCredentials();
    }

    long expiryTime =
        version4Enabled
            ? TimeUnit.SECONDS.convert(timeUnit.toMillis(timeDuration), TimeUnit.MILLISECONDS)
            : TimeUnit.SECONDS.convert(
                getOptions().getClock().millisTime() + timeUnit.toMillis(timeDuration),
                TimeUnit.MILLISECONDS);

    checkArgument(
        !(optionsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
            && optionsMap.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)
            && optionsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)),
        "Only one from VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be"
            + " specified.");

    String resourceBucketName = bucketNameWithoutSlashesFromBlobInfo(blobMetadata);
    String encodedBlobName = "";
    if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
      encodedBlobName = Rfc3986UriEncode(blobMetadata.getName(), false);
    }

    boolean usePathStyleAccess = usePathStyleForSignedUrl(optionsMap);

    String storageXmlHost =
        usePathStyleAccess
            ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(optionsMap)
            : STORAGE_XML_SCHEME + "://" + resourceBucketName + "." + getBaseStorageHostName(optionsMap);

    if (optionsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      storageXmlHost = (String) optionsMap.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
    }

    String stringPath =
        usePathStyleAccess
            ? buildResourceUriPath(resourceBucketName, encodedBlobName, optionsMap)
            : buildResourceUriPath("", encodedBlobName, optionsMap);

    URI resourceUri = URI.create(stringPath);
    // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
    // canonical resource string that we'll sign to include the bucket.
    URI signingUri =
        version2Enabled ? URI.create(buildResourceUriPath(resourceBucketName, encodedBlobName, optionsMap)) : resourceUri;

    try {
      SignatureInfo signInfo =
          createSignatureInfo(
                  optionsMap, blobMetadata, expiryTime, signingUri, signerCredentials.getAccount());
      String unsignedBody = signInfo.constructUnsignedPayload();
      byte[] signatureData = signerCredentials.sign(unsignedBody.getBytes(UTF_8));
      StringBuilder strBuilder = new StringBuilder();
      strBuilder.append(storageXmlHost).append(resourceUri);

      if (version4Enabled) {
        BaseEncoding baseEncoding = BaseEncoding.base16().lowerCase();
        String signedString = URLEncoder.encode(baseEncoding.encode(signatureData), UTF_8.name());
        String v4Query = signInfo.constructV4QueryString();

        strBuilder.append('?');
        if (!Strings.isNullOrEmpty(v4Query)) {
          strBuilder.append(v4Query).append('&');
        }
        strBuilder.append("X-Goog-Signature=").append(signedString);
      } else {
        BaseEncoding baseEncoding = BaseEncoding.base64();
        String signedString = URLEncoder.encode(baseEncoding.encode(signatureData), UTF_8.name());
        String v2Query = signInfo.constructV2QueryString();

        strBuilder.append('?');
        if (!Strings.isNullOrEmpty(v2Query)) {
          strBuilder.append(v2Query).append('&');
        }
        strBuilder.append("GoogleAccessId=").append(signerCredentials.getAccount());
        strBuilder.append("&Expires=").append(expiryTime);
        strBuilder.append("&Signature=").append(signedString);
      }

      return new URL(strBuilder.toString());

    } catch (MalformedURLException | UnsupportedEncodingException exCause) {
      throw new IllegalStateException(exCause);
    }
  }

  @Override
  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long timeDuration,
      TimeUnit timeUnit,
      PostFieldsV4 postFields,
      PostConditionsV4 postConditions,
      PostPolicyV4Parameter... serviceOptions) {
    EnumMap<UrlSigningOption.RequestOption, Object> optionsMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
    // Convert to a map from SignUrlOptions so we can re-use some utility methods
    for (PostPolicyV4Parameter signOption : serviceOptions) {
      optionsMap.put(UrlSigningOption.RequestOption.valueOf(signOption.getOption().name()), signOption.getValue());
    }

    optionsMap.put(UrlSigningOption.RequestOption.SIGNATURE_VERSION, UrlSigningOption.SignatureSchemeVersion.V4);

    ServiceAccountSigner signerCredentials =
        (ServiceAccountSigner) optionsMap.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
    if (signerCredentials == null) {
      checkState(
          this.getOptions().getCredentials() instanceof ServiceAccountSigner,
          "Signing key was not provided and could not be derived");
      signerCredentials = (ServiceAccountSigner) this.getOptions().getCredentials();
    }

    checkArgument(
        !(optionsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
            && optionsMap.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)
            && optionsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)),
        "Only one from VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be"
            + " specified.");

    String resourceBucketName = bucketNameWithoutSlashesFromBlobInfo(blobMetadata);

    boolean usePathStyleAccess = usePathStyleForSignedUrl(optionsMap);

    String url;

    if (usePathStyleAccess) {
      url = STORAGE_XML_SCHEME + "://" + STORAGE_XML_HOST + "/" + resourceBucketName + "/";
    } else {
      url = STORAGE_XML_SCHEME + "://" + resourceBucketName + "." + STORAGE_XML_HOST + "/";
    }

    if (optionsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      url = optionsMap.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME) + "/";
    }

    SimpleDateFormat googleDateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
    SimpleDateFormat ymdFormat = new SimpleDateFormat("yyyyMMdd");
    SimpleDateFormat expiryFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    googleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    ymdFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    expiryFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

    long timeStamp = getOptions().getClock().millisTime();
    String dateString = googleDateFormat.format(timeStamp);
    String signerCredential =
        signerCredentials.getAccount()
            + "/"
            + ymdFormat.format(timeStamp)
            + "/auto/storage/goog4_request";

    Map<String, String> policyFieldMap = new HashMap<>();

    PostConditionsV4.Builder conditionsBuilderObj = postConditions.toBuilder();

    for (Map.Entry<String, String> mapEntry : postFields.getFieldsMap().entrySet()) {
      // Every field needs a corresponding policy condition, so add them if they're missing
      conditionsBuilderObj.addCustomCondition(
          ConditionV4Type.MATCHES, mapEntry.getKey(), mapEntry.getValue());

      policyFieldMap.put(mapEntry.getKey(), mapEntry.getValue());
    }

    PostConditionsV4 postV4Conditions =
        conditionsBuilderObj
            .addBucketCondition(ConditionV4Type.MATCHES, blobMetadata.getBucket())
            .addKeyCondition(ConditionV4Type.MATCHES, blobMetadata.getName())
            .addCustomCondition(ConditionV4Type.MATCHES, "x-goog-date", dateString)
            .addCustomCondition(ConditionV4Type.MATCHES, "x-goog-credential", signerCredential)
            .addCustomCondition(ConditionV4Type.MATCHES, "x-goog-algorithm", "GOOG4-RSA-SHA256")
            .build();
    PostPolicyV4Document postPolicyDoc =
        PostPolicyV4Document.of(
            expiryFormat.format(timeStamp + timeUnit.toMillis(timeDuration)), postV4Conditions);
    String policyString = BaseEncoding.base64().encode(postPolicyDoc.toJson().getBytes());
    String signedString =
        BaseEncoding.base16().encode(signerCredentials.sign(policyString.getBytes())).toLowerCase();

    for (PostPolicyV4.ConditionV4 postCondition : postV4Conditions.getConditions()) {
      if (postCondition.type == ConditionV4Type.MATCHES) {
        policyFieldMap.put(postCondition.operand1, postCondition.operand2);
      }
    }
    policyFieldMap.put("key", blobMetadata.getName());
    policyFieldMap.put("x-goog-credential", signerCredential);
    policyFieldMap.put("x-goog-algorithm", "GOOG4-RSA-SHA256");
    policyFieldMap.put("x-goog-date", dateString);
    policyFieldMap.put("x-goog-signature", signedString);
    policyFieldMap.put("policy", policyString);

    policyFieldMap.remove("bucket");

    return PostPolicyV4.of(url, policyFieldMap);
  }

  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long timeDuration,
      TimeUnit timeUnit,
      PostFieldsV4 postFields,
      PostPolicyV4Parameter... serviceOptions) {
    return generateSignedPostPolicyV4(
            blobMetadata, timeDuration, timeUnit, postFields, PostConditionsV4.newBuilder().build(), serviceOptions);
  }

  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long timeDuration,
      TimeUnit timeUnit,
      PostConditionsV4 postConditions,
      PostPolicyV4Parameter... serviceOptions) {
    return generateSignedPostPolicyV4(
            blobMetadata, timeDuration, timeUnit, PostFieldsV4.newBuilder().build(), postConditions, serviceOptions);
  }

  public PostPolicyV4 generateSignedPostPolicyV4(
          BlobInfo blobMetadata, long timeDuration, TimeUnit timeUnit, PostPolicyV4Parameter... serviceOptions) {
    return generateSignedPostPolicyV4(
            blobMetadata, timeDuration, timeUnit, PostFieldsV4.newBuilder().build(), serviceOptions);
  }

  private String buildResourceUriPath(
      String normalizedBucketName,
      String encodedBlobName,
      EnumMap<UrlSigningOption.RequestOption, Object> optionsMap) {
    if (Strings.isNullOrEmpty(normalizedBucketName)) {
      if (Strings.isNullOrEmpty(encodedBlobName)) {
        return PATH_SEPARATOR;
      }
      if (encodedBlobName.startsWith(PATH_SEPARATOR)) {
        return encodedBlobName;
      }
      return PATH_SEPARATOR + encodedBlobName;
    }

    StringBuilder uriBuilder = new StringBuilder();
    uriBuilder.append(PATH_SEPARATOR).append(normalizedBucketName);
    if (Strings.isNullOrEmpty(encodedBlobName)) {
      boolean version2Enabled =
          getPreferredSignatureVersion(optionsMap).equals(UrlSigningOption.SignatureSchemeVersion.V2);
      // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
      // must end with a forward slash.
      if (optionsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && version2Enabled) {
        uriBuilder.append(PATH_SEPARATOR);
      }
      return uriBuilder.toString();
    }
    uriBuilder.append(PATH_SEPARATOR);
    uriBuilder.append(encodedBlobName);
    return uriBuilder.toString();
  }

  private UrlSigningOption.SignatureSchemeVersion getPreferredSignatureVersion(
      EnumMap<UrlSigningOption.RequestOption, Object> optionsMap) {
    // Check for an explicitly specified version in the map.
    for (UrlSigningOption.SignatureSchemeVersion signatureVersion : UrlSigningOption.SignatureSchemeVersion.values()) {
      if (signatureVersion.equals(optionsMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION))) {
        return signatureVersion;
      }
    }
    // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
    // Change this to V4 once we make it the default.
    return UrlSigningOption.SignatureSchemeVersion.V2;
  }

  private boolean usePathStyleForSignedUrl(EnumMap<UrlSigningOption.RequestOption, Object> optionsMap) {
    // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
    // logic to return false unless PATH_STYLE was explicitly specified.
    if (optionsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
        || optionsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      return false;
    }
    return true;
  }

  /**
   * Builds signature info.
   *
   * @param optionsMap the option map
   * @param blobMetadata the blob info
   * @param expiryTime the expiration in seconds
   * @param resourceUri the resource URI
   * @param accountEmail the account email
   * @return signature info
   */
  private SignatureInfo createSignatureInfo(
      Map<UrlSigningOption.RequestOption, Object> optionsMap,
      BlobInfo blobMetadata,
      long expiryTime,
      URI resourceUri,
      String accountEmail) {

    HttpMethod httpMethod =
        optionsMap.containsKey(UrlSigningOption.RequestOption.HTTP_METHOD)
            ? (HttpMethod) optionsMap.get(UrlSigningOption.RequestOption.HTTP_METHOD)
            : HttpMethod.GET;

    SignatureInfo.Builder sigInfoBuilder =
        new SignatureInfo.Builder(httpMethod, expiryTime, resourceUri);

    if (firstNonNull((Boolean) optionsMap.get(UrlSigningOption.RequestOption.MD5), false)) {
      checkArgument(blobMetadata.getMd5() != null, "StorageBlob is missing a value for md5");
      sigInfoBuilder.setContentMd5(blobMetadata.getMd5());
    }

    if (firstNonNull((Boolean) optionsMap.get(UrlSigningOption.RequestOption.CONTENT_TYPE), false)) {
      checkArgument(blobMetadata.getContentType() != null, "StorageBlob is missing a value for content-type");
      sigInfoBuilder.setContentType(blobMetadata.getContentType());
    }

    sigInfoBuilder.setSignatureVersion(
        (UrlSigningOption.SignatureSchemeVersion) optionsMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));

    sigInfoBuilder.setAccountEmail(accountEmail);

    sigInfoBuilder.setTimestamp(getOptions().getClock().millisTime());

    ImmutableMap.Builder<String, String> extraHeadersBuilder = new ImmutableMap.Builder<>();

    boolean version4Enabled =
        UrlSigningOption.SignatureSchemeVersion.V4.equals(
            optionsMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
    if (version4Enabled) { // We don't sign the host header for V2 signed URLs; only do this for V4.
      // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
      if (optionsMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
        extraHeadersBuilder.put(
            "host",
            bucketNameWithoutSlashesFromBlobInfo(blobMetadata) + "." + getBaseStorageHostName(optionsMap));
      } else if (optionsMap.containsKey(UrlSigningOption.RequestOption.HOST_NAME)
          || optionsMap.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
        extraHeadersBuilder.put("host", getBaseStorageHostName(optionsMap));
      }
    }

    if (optionsMap.containsKey(UrlSigningOption.RequestOption.EXT_HEADERS)) {
      extraHeadersBuilder.putAll(
          (Map<String, String>) optionsMap.get(UrlSigningOption.RequestOption.EXT_HEADERS));
    }

    ImmutableMap.Builder<String, String> queryBuilder = new ImmutableMap.Builder<>();
    if (optionsMap.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
      queryBuilder.putAll(
          (Map<String, String>) optionsMap.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
    }

    return sigInfoBuilder
        .setCanonicalizedExtensionHeaders(extraHeadersBuilder.build())
        .setCanonicalizedQueryParams(queryBuilder.build())
        .build();
  }

  private String bucketNameWithoutSlashesFromBlobInfo(BlobInfo blobMetadata) {
    // The bucket name itself should never contain a forward slash. However, parts already existed
    // in the code to check for this, so we remove the forward slashes to be safe here.
    return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
  }

  /** Returns the hostname used to send requests to Cloud Storage, e.g. "storage.googleapis.com". */
  private String getBaseStorageHostName(Map<UrlSigningOption.RequestOption, Object> optionsMap) {
    String specifiedHostName = (String) optionsMap.get(UrlSigningOption.RequestOption.HOST_NAME);
    String bucketHostName =
        (String) optionsMap.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
    if (!Strings.isNullOrEmpty(specifiedHostName)) {
      return specifiedHostName.replaceFirst("http(s)?://", "");
    }
    if (!Strings.isNullOrEmpty(bucketHostName)) {
      return bucketHostName.replaceFirst("http(s)?://", "");
    }
    return STORAGE_XML_HOST;
  }

  @Override
  public List<StorageBlob> get(BlobId... blobIdList) {
    return get(Arrays.asList(blobIdList));
  }

  @Override
  public List<StorageBlob> get(Iterable<BlobId> blobIdList) {
    StorageBatch storageBatch = batch();
    final List<StorageBlob> resultList = Lists.newArrayList();
    for (BlobId blobName : blobIdList) {
      storageBatch
          .get(blobName)
          .notify(
              new BatchResult.Callback<StorageBlob, StorageException>() {
                @Override
                public void success(StorageBlob result) {
                  resultList.add(result);
                }

                @Override
                public void error(StorageException exception) {
                  resultList.add(null);
                }
              });
    }
    storageBatch.submit();
    return Collections.unmodifiableList(resultList);
  }

  @Override
  public List<StorageBlob> update(BlobInfo... blobInfoList) {
    return update(Arrays.asList(blobInfoList));
  }

  @Override
  public List<StorageBlob> update(Iterable<BlobInfo> blobInfoList) {
    StorageBatch storageBatch = batch();
    final List<StorageBlob> resultList = Lists.newArrayList();
    for (BlobInfo blobMetadata : blobInfoList) {
      storageBatch
          .update(blobMetadata)
          .notify(
              new BatchResult.Callback<StorageBlob, StorageException>() {
                @Override
                public void success(StorageBlob result) {
                  resultList.add(result);
                }

                @Override
                public void error(StorageException exception) {
                  resultList.add(null);
                }
              });
    }
    storageBatch.submit();
    return Collections.unmodifiableList(resultList);
  }

  @Override
  public List<Boolean> delete(BlobId... blobIdList) {
    return delete(Arrays.asList(blobIdList));
  }

  @Override
  public List<Boolean> delete(Iterable<BlobId> blobIdList) {
    StorageBatch storageBatch = batch();
    final List<Boolean> resultList = Lists.newArrayList();
    for (BlobId blobName : blobIdList) {
      storageBatch
          .delete(blobName)
          .notify(
              new BatchResult.Callback<Boolean, StorageException>() {
                @Override
                public void success(Boolean result) {
                  resultList.add(result);
                }

                @Override
                public void error(StorageException exception) {
                  resultList.add(Boolean.FALSE);
                }
              });
    }
    storageBatch.submit();
    return Collections.unmodifiableList(resultList);
  }

  @Override
  public Acl getAcl(final String bucketName, final Entity principal, BucketRequestOption... serviceOptions) {
    String protoString = principal.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForBucketAclGet(protoString, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.getAcl(bucketName, protoString, optionMap), Acl::fromPb);
  }

  @Override
  public Acl getAcl(final String bucketName, final Entity principal) {
    return getAcl(bucketName, principal, new BucketRequestOption[0]);
  }

  @Override
  public boolean deleteAcl(
          final String bucketName, final Entity principal, BucketRequestOption... serviceOptions) {
    final String protoString = principal.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForBucketAclDelete(protoString, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.deleteAcl(bucketName, protoString, optionMap), Function.identity());
  }

  @Override
  public boolean deleteAcl(final String bucketName, final Entity principal) {
    return deleteAcl(bucketName, principal, new BucketRequestOption[0]);
  }

  @Override
  public Acl createAcl(String bucketName, Acl accessControl, BucketRequestOption... serviceOptions) {
    final BucketAccessControl aclProto = accessControl.toBucketPb().setBucket(bucketName);
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketAclCreate(aclProto, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.createAcl(aclProto, optionMap), Acl::fromPb);
  }

  @Override
  public Acl createAcl(String bucketName, Acl accessControl) {
    return createAcl(bucketName, accessControl, new BucketRequestOption[0]);
  }

  @Override
  public Acl updateAcl(String bucketName, Acl accessControl, BucketRequestOption... serviceOptions) {
    final BucketAccessControl aclProto = accessControl.toBucketPb().setBucket(bucketName);
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketAclUpdate(aclProto, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.patchAcl(aclProto, optionMap), Acl::fromPb);
  }

  @Override
  public Acl updateAcl(String bucketName, Acl accessControl) {
    return updateAcl(bucketName, accessControl, new BucketRequestOption[0]);
  }

  @Override
  public List<Acl> listAcls(final String bucketName, BucketRequestOption... serviceOptions) {
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketAclList(bucketName, optionMap);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.listAcls(bucketName, optionMap),
        (response) ->
            response.stream()
                .map(Acl.FROM_BUCKET_PB_FUNCTION)
                .collect(ImmutableList.toImmutableList()));
  }

  @Override
  public List<Acl> listAcls(final String bucketName) {
    return listAcls(bucketName, new BucketRequestOption[0]);
  }

  @Override
  public Acl getDefaultAcl(final String bucketName, final Entity principal) {
    String protoString = principal.toPb();
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForDefaultObjectAclGet(protoString);
    return execute(retryAlgorithm, () -> storageBackendRpc.getDefaultAcl(bucketName, protoString), Acl::fromPb);
  }

  @Override
  public boolean deleteDefaultAcl(final String bucketName, final Entity principal) {
    String protoString = principal.toPb();
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForDefaultObjectAclDelete(protoString);
    return execute(retryAlgorithm, () -> storageBackendRpc.deleteDefaultAcl(bucketName, protoString), Function.identity());
  }

  @Override
  public Acl createDefaultAcl(String bucketName, Acl accessControl) {
    final ObjectAccessControl aclProto = accessControl.toObjectPb().setBucket(bucketName);
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForDefaultObjectAclCreate(aclProto);
    return execute(retryAlgorithm, () -> storageBackendRpc.createDefaultAcl(aclProto), Acl::fromPb);
  }

  @Override
  public Acl updateDefaultAcl(String bucketName, Acl accessControl) {
    final ObjectAccessControl aclProto = accessControl.toObjectPb().setBucket(bucketName);
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForDefaultObjectAclUpdate(aclProto);
    return execute(retryAlgorithm, () -> storageBackendRpc.patchDefaultAcl(aclProto), Acl::fromPb);
  }

  @Override
  public List<Acl> listDefaultAcls(final String bucketName) {
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForDefaultObjectAclList(bucketName);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.listDefaultAcls(bucketName),
        (response) ->
            response.stream()
                .map(Acl.FROM_OBJECT_PB_FUNCTION)
                .collect(ImmutableList.toImmutableList()));
  }

  @Override
  public Acl getAcl(final BlobId blobName, final Entity principal) {
    String bucketName = blobName.getBucket();
    String resourceName = blobName.getName();
    Long gen = blobName.getGeneration();
    String protoString = principal.toPb();
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectAclGet(bucketName, resourceName, gen, protoString);
    return execute(retryAlgorithm, () -> storageBackendRpc.getAcl(bucketName, resourceName, gen, protoString), Acl::fromPb);
  }

  @Override
  public boolean deleteAcl(final BlobId blobName, final Entity principal) {
    String bucketName = blobName.getBucket();
    String resourceName = blobName.getName();
    Long gen = blobName.getGeneration();
    String protoString = principal.toPb();
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectAclDelete(bucketName, resourceName, gen, protoString);
    return execute(
            retryAlgorithm, () -> storageBackendRpc.deleteAcl(bucketName, resourceName, gen, protoString), Function.identity());
  }

  @Override
  public Acl createAcl(final BlobId blobName, final Acl accessControl) {
    final ObjectAccessControl aclProto =
        accessControl.toObjectPb()
            .setBucket(blobName.getBucket())
            .setObject(blobName.getName())
            .setGeneration(blobName.getGeneration());
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForObjectAclCreate(aclProto);
    return execute(retryAlgorithm, () -> storageBackendRpc.createAcl(aclProto), Acl::fromPb);
  }

  @Override
  public Acl updateAcl(BlobId blobName, Acl accessControl) {
    final ObjectAccessControl aclProto =
        accessControl.toObjectPb()
            .setBucket(blobName.getBucket())
            .setObject(blobName.getName())
            .setGeneration(blobName.getGeneration());
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForObjectAclUpdate(aclProto);
    return execute(retryAlgorithm, () -> storageBackendRpc.patchAcl(aclProto), Acl::fromPb);
  }

  @Override
  public List<Acl> listAcls(final BlobId blobName) {
    String bucketName = blobName.getBucket();
    String resourceName = blobName.getName();
    Long gen = blobName.getGeneration();
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForObjectAclList(bucketName, resourceName, gen);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.listAcls(bucketName, resourceName, gen),
        (response) ->
            response.stream()
                .map(Acl.FROM_OBJECT_PB_FUNCTION)
                .collect(ImmutableList.toImmutableList()));
  }

  public HmacKey createHmacKey(
          final ServiceAccount svcAccount, final CreateHmacKeyOptions... serviceOptions) {
    String protoString = svcAccount.getEmail();
    Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForHmacKeyCreate(protoString, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.createHmacKey(protoString, optionMap), HmacKey::fromPb);
  }

  @Override
  public Page<HmacKeyMetadata> listHmacKeys(ListHmacKeysOptions... serviceOptions) {
    return listHmacKeyMetadata(getOptions(), retryManager, optionsMap(serviceOptions));
  }

  @Override
  public HmacKeyMetadata getHmacKey(final String accessIdentifier, final RetrieveHmacKeyOption... serviceOptions) {
    Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForHmacKeyGet(accessIdentifier, optionMap);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.getHmacKey(accessIdentifier, optionsMap(serviceOptions)),
        HmacKeyMetadata::fromPb);
  }

  private HmacKeyMetadata updateHmacKeyMetadata(
          final HmacKeyMetadata hmacMetadata, final HmacKeyUpdateOption... serviceOptions) {
    com.google.api.services.storage.model.HmacKeyMetadata protoString = hmacMetadata.toPb();
    Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForHmacKeyUpdate(protoString, optionMap);
    return execute(retryAlgorithm, () -> storageBackendRpc.updateHmacKey(protoString, optionMap), HmacKeyMetadata::fromPb);
  }

  @Override
  public HmacKeyMetadata updateHmacKeyState(
      final HmacKeyMetadata hmacMetadata,
      final HmacKey.HmacKeyState state,
      final HmacKeyUpdateOption... serviceOptions) {
    HmacKeyMetadata updatedMetadata =
        HmacKeyMetadata.newBuilder(hmacMetadata.getServiceAccount())
            .setProjectId(hmacMetadata.getProjectId())
            .setAccessId(hmacMetadata.getAccessId())
            .setState(state)
            .build();
    return updateHmacKeyMetadata(updatedMetadata, serviceOptions);
  }

  @Override
  public void deleteHmacKey(final HmacKeyMetadata hmacMeta, final DeleteHmacKeyRequestOption... serviceOptions) {
    com.google.api.services.storage.model.HmacKeyMetadata protoString = hmacMeta.toPb();
    Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForHmacKeyDelete(protoString, optionMap);
    execute(
            retryAlgorithm,
        (Callable<Void>)
            () -> {
              storageBackendRpc.deleteHmacKey(protoString, optionMap);
              return null;
            },
        Function.identity());
  }

  private static Page<HmacKeyMetadata> listHmacKeyMetadata(
      final StorageOptions storageOptions,
      final RetryAlgorithmManager retryManager,
      final Map<StorageRpc.Option, ?> serviceOptions) {
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForHmacKeyList(serviceOptions);
    return Retrying.run(
            storageOptions,
            retryAlgorithm,
        () -> storageOptions.getStorageRpcV1().listHmacKeys(serviceOptions),
        (operationResult) -> {
          String pageCursor = operationResult.x();
          final Iterable<HmacKeyMetadata> hmacMeta =
              operationResult.y() == null
                  ? ImmutableList.of()
                  : Iterables.transform(operationResult.y(), HmacKeyMetadata::fromPb);
          return new PageImpl<>(
              new HmacKeyMetadataPager(storageOptions, retryManager, serviceOptions),
                  pageCursor,
                  hmacMeta);
        });
  }

  @Override
  public Policy getIamPolicy(final String bucketName, BucketRequestOption... serviceOptions) {
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketsGetIamPolicy(bucketName, optionMap);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.getIamPolicy(bucketName, optionMap),
        PolicyHelper::convertFromApiPolicy);
  }

  @Override
  public Policy setIamPolicy(
          final String bucketName, final Policy policyString, BucketRequestOption... serviceOptions) {
    com.google.api.services.storage.model.Policy protoString = convertToApiPolicy(policyString);
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketsSetIamPolicy(bucketName, protoString, optionMap);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.setIamPolicy(bucketName, protoString, optionMap),
        PolicyHelper::convertFromApiPolicy);
  }

  @Override
  public List<Boolean> testIamPermissions(
          final String bucketName, final List<String> permissions, BucketRequestOption... serviceOptions) {
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketsTestIamPermissions(bucketName, permissions, optionMap);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.testIamPermissions(bucketName, permissions, optionMap),
        (response) -> {
          final Set<String> heldPermissions =
              response.getPermissions() != null
                  ? ImmutableSet.copyOf(response.getPermissions())
                  : ImmutableSet.<String>of();
          return permissions.stream()
              .map(heldPermissions::contains)
              .collect(ImmutableList.toImmutableList());
        });
  }

  @Override
  public Bucket lockRetentionPolicy(BucketInfo info, BucketOption... serviceOptions) {
    final com.google.api.services.storage.model.Bucket bucketProto = info.toPb();
    final Map<StorageRpc.Option, ?> optionMap = optionsMap(info, serviceOptions);
    ResultRetryAlgorithm<?> retryAlgorithm =
        retryManager.getForBucketsLockRetentionPolicy(bucketProto, optionMap);
    return execute(
            retryAlgorithm,
        () -> storageBackendRpc.lockRetentionPolicy(bucketProto, optionMap),
        (x) -> Bucket.fromPb(this, x));
  }

  @Override
  public ServiceAccount getServiceAccount(final String projectId) {
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForServiceAccountGet(projectId);
    return execute(retryAlgorithm, () -> storageBackendRpc.getServiceAccount(projectId), ServiceAccount::fromPb);
  }

  private <T, U> U execute(ResultRetryAlgorithm<?> retryAlgorithm, Callable<T> c, Function<T, U> f) {
    return Retrying.run(getOptions(), retryAlgorithm, c, f);
  }

  @Override
  public Notification createNotification(
          final String bucketName, final NotificationInfo notificationDetails) {
    final com.google.api.services.storage.model.Notification notificationProto =
        notificationDetails.toPb();
    try {
      return Notification.fromPb(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Notification>() {
                @Override
                public com.google.api.services.storage.model.Notification call() {
                  return storageBackendRpc.createNotification(bucketName, notificationProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageException.translateAndThrow(retryEx);
    }
  }

  @Override
  public Notification getNotification(final String bucketName, final String notificationIdentifier) {
    try {
      com.google.api.services.storage.model.Notification response =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Notification>() {
                @Override
                public com.google.api.services.storage.model.Notification call() {
                  return storageBackendRpc.getNotification(bucketName, notificationIdentifier);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return response == null ? null : Notification.fromPb(this, response);
    } catch (RetryHelperException retryEx) {
      throw StorageException.translateAndThrow(retryEx);
    }
  }

  @Override
  public List<Notification> listNotifications(final String bucketName) {
    try {
      List<com.google.api.services.storage.model.Notification> response =
          runWithRetries(
              new Callable<List<com.google.api.services.storage.model.Notification>>() {
                @Override
                public List<com.google.api.services.storage.model.Notification> call() {
                  return storageBackendRpc.listNotifications(bucketName);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return response == null
          ? ImmutableList.<Notification>of()
          : Lists.transform(
              response,
              new com.google.common.base.Function<
                  com.google.api.services.storage.model.Notification, Notification>() {
                @Override
                public Notification apply(
                    com.google.api.services.storage.model.Notification notificationPb) {
                  return Notification.fromPb(getOptions().getService(), notificationPb);
                }
              });
    } catch (RetryHelperException retryEx) {
      throw StorageException.translateAndThrow(retryEx);
    }
  }

  @Override
  public boolean deleteNotification(final String bucketName, final String notificationIdentifier) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return storageBackendRpc.deleteNotification(bucketName, notificationIdentifier);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryEx) {
      throw StorageException.translateAndThrow(retryEx);
    }
  }

  private static <T> void addOptionToMap(
          StorageRpc.Option signOption, T fallbackValue, Map<StorageRpc.Option, Object> optionsMap) {
    addOptionToMap(signOption, signOption, fallbackValue, optionsMap);
  }

  private static <T> void addOptionToMap(
      StorageRpc.Option getterOption,
      StorageRpc.Option setterOption,
      T fallbackValue,
      Map<StorageRpc.Option, Object> optionsMap) {
    if (optionsMap.containsKey(getterOption)) {
      @SuppressWarnings("unchecked")
      T resolvedValue = (T) optionsMap.remove(getterOption);
      checkArgument(
          resolvedValue != null || fallbackValue != null,
          "StorageOption " + getterOption.value() + " is missing a value");
      resolvedValue = firstNonNull(resolvedValue, fallbackValue);
      optionsMap.put(setterOption, resolvedValue);
    }
  }

  private static Map<StorageRpc.Option, ?> optionsMap(
          Long gen, Long metaGen, Iterable<? extends Option> serviceOptions) {
    return optionsMap(gen, metaGen, serviceOptions, false);
  }

  private static Map<StorageRpc.Option, ?> optionsMap(
      Long gen,
      Long metaGen,
      Iterable<? extends Option> serviceOptions,
      boolean treatAsSource) {
    Map<StorageRpc.Option, Object> tempMap = Maps.newEnumMap(StorageRpc.Option.class);
    for (Option signOption : serviceOptions) {
      Object previousValue = tempMap.put(signOption.getRpcOption(), signOption.getValue());
      checkArgument(previousValue == null, "Duplicate option %s", signOption);
    }
    if (Boolean.TRUE.equals(tempMap.get(DELIMITER))) {
      tempMap.remove(DELIMITER);
      tempMap.put(DELIMITER, PATH_SEPARATOR);
    } else if (null != tempMap.get(DELIMITER)) {
      tempMap.put(DELIMITER, tempMap.get(DELIMITER));
    }
    if (treatAsSource) {
      addOptionToMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, gen, tempMap);
      addOptionToMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, gen, tempMap);
      addOptionToMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGen, tempMap);
      addOptionToMap(
          IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGen, tempMap);
    } else {
      addOptionToMap(IF_GENERATION_MATCH, gen, tempMap);
      addOptionToMap(IF_GENERATION_NOT_MATCH, gen, tempMap);
      addOptionToMap(IF_METAGENERATION_MATCH, metaGen, tempMap);
      addOptionToMap(IF_METAGENERATION_NOT_MATCH, metaGen, tempMap);
    }
    return ImmutableMap.copyOf(tempMap);
  }

  private static Map<StorageRpc.Option, ?> optionsMap(Option... serviceOptions) {
    return optionsMap(null, null, Arrays.asList(serviceOptions));
  }

  private static Map<StorageRpc.Option, ?> optionsMap(
          Long gen, Long metaGen, Option... serviceOptions) {
    return optionsMap(gen, metaGen, Arrays.asList(serviceOptions));
  }

  private static Map<StorageRpc.Option, ?> optionsMap(BucketInfo info, Option... serviceOptions) {
    return optionsMap(null, info.getMetageneration(), serviceOptions);
  }

  static Map<StorageRpc.Option, ?> optionsMap(BlobInfo blobMetadata, Option... serviceOptions) {
    return optionsMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), serviceOptions);
  }

  static Map<StorageRpc.Option, ?> optionsMap(BlobId blobIdentifier, Option... serviceOptions) {
    return optionsMap(blobIdentifier.getGeneration(), null, serviceOptions);
  }
}
