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
import static com.google.cloud.storage.ApiPolicyConverter.convertApiPolicyToPolicy;
import static com.google.cloud.storage.ApiPolicyConverter.convertPolicyToApiPolicy;
import static com.google.cloud.storage.SignedUrlEncoderHelper.rfc3986UriEncode;
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
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AccessControlEntry.ProtoEntity;
import com.google.cloud.storage.HmacSecretKey.HmacKeyInfo;
import com.google.cloud.storage.S3PostPolicyV4.PostConditionsVersion4;
import com.google.cloud.storage.S3PostPolicyV4.PostFieldsMapV4;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteResult;
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

final class StorageServiceImpl extends BaseService<StorageClientOptions> implements CloudStorageClient {

  private static final byte[] ZERO_LENGTH_BYTE_ARRAY = {};
  private static final String EMPTY_BYTES_MD5 = "1B2M2Y8AsgTpgAmY7PhCfg==";
  private static final String EMPTY_BYTES_CRC32C = "AAAAAA==";
  private static final String PATH_SEPARATOR = "/";
  /** Signed URLs are only supported through the GCS XML API endpoint. */
  private static final String STORAGE_XML_SCHEME = "https";

  private static final String STORAGE_XML_HOST = "storage.googleapis.com";

  private static final int BUFFER_SIZE_DEFAULT = 15 * 1024 * 1024;
  private static final int MINIMUM_BUFFER_SIZE = 256 * 1024;

  private static final Function<Tuple<CloudStorageClient, Boolean>, Boolean> DELETE_FUNCTION =
      new Function<Tuple<CloudStorageClient, Boolean>, Boolean>() {
        @Override
        public Boolean apply(Tuple<CloudStorageClient, Boolean> tuple) {
          return tuple.y();
        }
      };

  private final StorageRpcClient rpcClient;

  StorageServiceImpl(StorageClientOptions clientConfig) {
    super(clientConfig);
    rpcClient = clientConfig.getStorageRpcV1();
  }

  @Override
  public StorageBucket create(BucketInfo bucketMetadata, BucketTargetOptions... clientConfig) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(bucketMetadata, clientConfig);
    try {
      return StorageBucket.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return rpcClient.create(bucketProto, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageObject create(BlobMetadata blobMetadata, BlobUploadOption... clientConfig) {
    BlobMetadata updatedMetadata =
        blobMetadata
            .toBuilderCopy()
            .setMd5(EMPTY_BYTES_MD5)
            .setCrc32c(EMPTY_BYTES_CRC32C)
            .buildMetadata();
    return createInternal(updatedMetadata, ZERO_LENGTH_BYTE_ARRAY, 0, 0, clientConfig);
  }

  @Override
  public StorageObject create(BlobMetadata blobMetadata, byte[] dataBytes, BlobUploadOption... clientConfig) {
    dataBytes = firstNonNull(dataBytes, ZERO_LENGTH_BYTE_ARRAY);
    BlobMetadata updatedMetadata =
        blobMetadata
            .toBuilderCopy()
            .setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(dataBytes).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(Ints.toByteArray(Hashing.crc32c().hashBytes(dataBytes).asInt())))
            .buildMetadata();
    return createInternal(updatedMetadata, dataBytes, 0, dataBytes.length, clientConfig);
  }

  @Override
  public StorageObject create(
          BlobMetadata blobMetadata, byte[] dataBytes, int startIndex, int size, BlobUploadOption... clientConfig) {
    dataBytes = firstNonNull(dataBytes, ZERO_LENGTH_BYTE_ARRAY);
    BlobMetadata updatedMetadata =
        blobMetadata
            .toBuilderCopy()
            .setMd5(
                BaseEncoding.base64()
                    .encode(Hashing.md5().hashBytes(dataBytes, startIndex, size).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(
                        Ints.toByteArray(
                            Hashing.crc32c().hashBytes(dataBytes, startIndex, size).asInt())))
            .buildMetadata();
    return createInternal(updatedMetadata, dataBytes, startIndex, size, clientConfig);
  }

  @Override
  @Deprecated
  public StorageObject create(BlobMetadata blobMetadata, InputStream dataBytes, BlobWriteOptions... clientConfig) {
    Tuple<BlobMetadata, BlobUploadOption[]> targetParams = BlobUploadOption.convertWriteOptions(blobMetadata, clientConfig);
    com.google.api.services.storage.model.StorageObject blobProto = targetParams.x().toProto();
    Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(targetParams.x(), targetParams.y());
    InputStream inputStream =
        firstNonNull(dataBytes, new ByteArrayInputStream(ZERO_LENGTH_BYTE_ARRAY));
    // retries are not safe when the input is an InputStream, so we can't retry.
    return StorageObject.fromProto(this, rpcClient.create(blobProto, inputStream, storageOptionMap));
  }

  private StorageObject createInternal(
      BlobMetadata blobMetadata,
      final byte[] dataBytes,
      final int startIndex,
      final int size,
      BlobUploadOption... clientConfig) {
    Preconditions.checkNotNull(dataBytes);
    final com.google.api.services.storage.model.StorageObject blobProto = blobMetadata.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(blobMetadata, clientConfig);
    try {
      return StorageObject.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.StorageObject>() {
                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                  return rpcClient.create(
                          blobProto, new ByteArrayInputStream(dataBytes, startIndex, size), storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageObject createFrom(BlobMetadata blobMetadata, Path fileLocation, BlobWriteOptions... clientConfig)
      throws IOException {
    return createFrom(blobMetadata, fileLocation, BUFFER_SIZE_DEFAULT, clientConfig);
  }

  @Override
  public StorageObject createFrom(BlobMetadata blobMetadata, Path fileLocation, int chunkSize, BlobWriteOptions... clientConfig)
      throws IOException {
    if (Files.isDirectory(fileLocation)) {
      throw new StorageServiceException(0, fileLocation + " is a directory");
    }
    try (InputStream stream = Files.newInputStream(fileLocation)) {
      return createFrom(blobMetadata, stream, chunkSize, clientConfig);
    }
  }

  @Override
  public StorageObject createFrom(BlobMetadata blobMetadata, InputStream dataBytes, BlobWriteOptions... clientConfig)
      throws IOException {
    return createFrom(blobMetadata, dataBytes, BUFFER_SIZE_DEFAULT, clientConfig);
  }

  @Override
  public StorageObject createFrom(
          BlobMetadata blobMetadata, InputStream dataBytes, int chunkSize, BlobWriteOptions... clientConfig)
      throws IOException {

    BlobUploadChannel uploadChannel;
    try (WriteChannel writeChannel = writer(blobMetadata, clientConfig)) {
      uploadChannel = (BlobUploadChannel) writeChannel;
      performUpload(Channels.newChannel(dataBytes), writeChannel, chunkSize);
    }
    com.google.api.services.storage.model.StorageObject storageProto = uploadChannel.getStorageObject();
    return StorageObject.fromProto(this, storageProto);
  }

  /*
   * Uploads the given content to the storage using specified write channel and the given buffer
   * size. This method does not close any channels.
   */
  private static void performUpload(ReadableByteChannel readChannel, WriteChannel writeChannel, int chunkSize)
      throws IOException {
    chunkSize = Math.max(chunkSize, MINIMUM_BUFFER_SIZE);
    ByteBuffer byteStore = ByteBuffer.allocate(chunkSize);
    writeChannel.setChunkSize(chunkSize);

    while (readChannel.read(byteStore) >= 0) {
      byteStore.flip();
      writeChannel.write(byteStore);
      byteStore.clear();
    }
  }

  @Override
  public StorageBucket get(String containerName, BucketGetOptions... clientConfig) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.ofName(containerName).toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
    try {
      com.google.api.services.storage.model.Bucket result =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return rpcClient.get(bucketProto, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return result == null ? null : StorageBucket.fromProto(this, result);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageObject get(String containerName, String objectName, BlobGetOptions... clientConfig) {
    return get(BlobIdentifier.from(containerName, objectName), clientConfig);
  }

  @Override
  public StorageObject get(BlobIdentifier objectName, BlobGetOptions... clientConfig) {
    final com.google.api.services.storage.model.StorageObject retrievedObject = objectName.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(objectName, clientConfig);
    try {
      com.google.api.services.storage.model.StorageObject foundObject =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.StorageObject>() {
                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                  return rpcClient.get(retrievedObject, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return foundObject == null ? null : StorageObject.fromProto(this, foundObject);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageObject get(BlobIdentifier objectName) {
    return get(objectName, new BlobGetOptions[0]);
  }

  private static class BucketPageRetriever implements NextPageFetcher<StorageBucket> {

    private static final long serialVersionUID = 5850406828803613729L;
    private final Map<StorageRpcClient.StorageOption, ?> requestParams;
    private final StorageClientOptions clientConfig;

    BucketPageRetriever(
            StorageClientOptions clientConfig, String pageCursor, Map<StorageRpcClient.StorageOption, ?> optionsLookup) {
      this.requestParams =
          PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, pageCursor, optionsLookup);
      this.clientConfig = clientConfig;
    }

    @Override
    public Page<StorageBucket> getNextPage() {
      return listAllBuckets(clientConfig, requestParams);
    }
  }

  private static class BlobPageIterator implements NextPageFetcher<StorageObject> {

    private static final long serialVersionUID = 81807334445874098L;
    private final Map<StorageRpcClient.StorageOption, ?> requestParams;
    private final StorageClientOptions clientConfig;
    private final String containerName;

    BlobPageIterator(
        String containerName,
        StorageClientOptions clientConfig,
        String pageCursor,
        Map<StorageRpcClient.StorageOption, ?> optionsLookup) {
      this.requestParams =
          PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, pageCursor, optionsLookup);
      this.clientConfig = clientConfig;
      this.containerName = containerName;
    }

    @Override
    public Page<StorageObject> getNextPage() {
      return listAllBlobs(containerName, clientConfig, requestParams);
    }
  }

  private static class HmacKeyMetadataPageRetriever implements NextPageFetcher<HmacSecretKey.HmacKeyInfo> {

    private static final long serialVersionUID = 308012320541700881L;
    private final StorageClientOptions clientConfig;
    private final Map<StorageRpcClient.StorageOption, ?> storageClientConfig;

    HmacKeyMetadataPageRetriever(StorageClientOptions clientConfig, Map<StorageRpcClient.StorageOption, ?> storageClientConfig) {
      this.clientConfig = clientConfig;
      this.storageClientConfig = storageClientConfig;
    }

    @Override
    public Page<HmacKeyInfo> getNextPage() {
      return listAllHmacKeys(clientConfig, storageClientConfig);
    }
  }

  @Override
  public Page<StorageBucket> list(BucketListOptions... clientConfig) {
    return listAllBuckets(getOptions(), buildOptionMap(clientConfig));
  }

  @Override
  public Page<StorageObject> list(final String containerName, BlobListOptions... clientConfig) {
    return listAllBlobs(containerName, getOptions(), buildOptionMap(clientConfig));
  }

  private static Page<StorageBucket> listAllBuckets(
          final StorageClientOptions clientConfig, final Map<StorageRpcClient.StorageOption, ?> storageOptionMap) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> listOutcome =
          runWithRetries(
              new Callable<
                  Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {
                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>
                    call() {
                  return clientConfig.getStorageRpcV1().list(storageOptionMap);
                }
              },
              clientConfig.getRetrySettings(),
              EXCEPTION_HANDLER,
              clientConfig.getClock());
      String pageCursor = listOutcome.x();
      Iterable<StorageBucket> storageBuckets =
          listOutcome.y() == null
              ? ImmutableList.<StorageBucket>of()
              : Iterables.transform(
                  listOutcome.y(),
                  new Function<com.google.api.services.storage.model.Bucket, StorageBucket>() {
                    @Override
                    public StorageBucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                      return StorageBucket.fromProto(clientConfig.getService(), bucketPb);
                    }
                  });
      return new PageImpl<>(
          new BucketPageRetriever(clientConfig, pageCursor, storageOptionMap), pageCursor, storageBuckets);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  private static Page<StorageObject> listAllBlobs(
      final String containerName,
      final StorageClientOptions clientConfig,
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> listOutcome =
          runWithRetries(
              new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>>>() {
                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> call() {
                  return clientConfig.getStorageRpcV1().list(containerName, storageOptionMap);
                }
              },
              clientConfig.getRetrySettings(),
              EXCEPTION_HANDLER,
              clientConfig.getClock());
      String pageCursor = listOutcome.x();
      Iterable<StorageObject> storageObjects =
          listOutcome.y() == null
              ? ImmutableList.<StorageObject>of()
              : Iterables.transform(
                  listOutcome.y(),
                  new Function<com.google.api.services.storage.model.StorageObject, StorageObject>() {
                    @Override
                    public StorageObject apply(com.google.api.services.storage.model.StorageObject storageObject) {
                      return StorageObject.fromProto(clientConfig.getService(), storageObject);
                    }
                  });
      return new PageImpl<>(
          new BlobPageIterator(containerName, clientConfig, pageCursor, storageOptionMap), pageCursor, storageObjects);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageBucket update(BucketInfo bucketMetadata, BucketTargetOptions... clientConfig) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(bucketMetadata, clientConfig);
    try {
      return StorageBucket.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return rpcClient.patch(bucketProto, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageObject update(BlobMetadata blobMetadata, BlobUploadOption... clientConfig) {
    final com.google.api.services.storage.model.StorageObject retrievedObject = blobMetadata.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(blobMetadata, clientConfig);
    try {
      return StorageObject.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.StorageObject>() {
                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                  return rpcClient.patch(retrievedObject, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageObject update(BlobMetadata blobMetadata) {
    return update(blobMetadata, new BlobUploadOption[0]);
  }

  @Override
  public boolean delete(String containerName, BucketOption... clientConfig) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.ofName(containerName).toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.delete(bucketProto, storageOptionMap);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public boolean delete(String containerName, String objectName, BlobReadOption... clientConfig) {
    return delete(BlobIdentifier.from(containerName, objectName), clientConfig);
  }

  @Override
  public boolean delete(BlobIdentifier objectName, BlobReadOption... clientConfig) {
    final com.google.api.services.storage.model.StorageObject retrievedObject = objectName.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(objectName, clientConfig);
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.delete(retrievedObject, storageOptionMap);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public boolean delete(BlobIdentifier objectName) {
    return delete(objectName, new BlobReadOption[0]);
  }

  @Override
  public StorageObject compose(final ComposeBlobsRequest composeReq) {
    final List<com.google.api.services.storage.model.StorageObject> sourceList =
        Lists.newArrayListWithCapacity(composeReq.getSourceBlobs().size());
    for (ComposeBlobsRequest.SourceBlobMetadata sourceEntry : composeReq.getSourceBlobs()) {
      sourceList.add(
          BlobMetadata.newBuilder(
                  BlobIdentifier.from(
                      composeReq.getTarget().getBucket(),
                      sourceEntry.getName(),
                      sourceEntry.getGeneration()))
              .buildMetadata()
              .toProto());
    }
    final com.google.api.services.storage.model.StorageObject destinationObject = composeReq.getTarget().toProto();
    final Map<StorageRpcClient.StorageOption, ?> targetParams =
        optionMap(
            composeReq.getTarget().getGeneration(),
            composeReq.getTarget().getMetageneration(),
            composeReq.getTargetOptions());
    try {
      return StorageObject.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.StorageObject>() {
                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                  return rpcClient.compose(sourceList, destinationObject, targetParams);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public BlobRewriteWriter copy(final CopyOperationRequest copyReq) {
    final com.google.api.services.storage.model.StorageObject originObject = copyReq.getSource().toProto();
    final Map<StorageRpcClient.StorageOption, ?> originOptionMap =
        optionMap(
            copyReq.getSource().getGeneration(), null, copyReq.getSourceOptions(), true);
    final com.google.api.services.storage.model.StorageObject destinationObject = copyReq.getTarget().toProto();
    final Map<StorageRpcClient.StorageOption, ?> targetParams =
        optionMap(
            copyReq.getTarget().getGeneration(),
            copyReq.getTarget().getMetageneration(),
            copyReq.getTargetOptions());
    try {
      RewriteResult rewriteResult =
          runWithRetries(
              new Callable<StorageRpcClient.RewriteResult>() {
                @Override
                public StorageRpcClient.RewriteResult call() {
                  return rpcClient.openRewrite(
                      new StorageRpcClient.ObjectRewriteRequest(
                              originObject,
                              originOptionMap,
                          copyReq.getOverrideInfo(),
                              destinationObject,
                              targetParams,
                          copyReq.getMegabytesCopiedPerChunk()));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return new BlobRewriteWriter(getOptions(), rewriteResult);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public byte[] readAllBytes(String containerName, String objectName, BlobReadOption... clientConfig) {
    return readAllBytes(BlobIdentifier.from(containerName, objectName), clientConfig);
  }

  @Override
  public byte[] readAllBytes(BlobIdentifier objectName, BlobReadOption... clientConfig) {
    final com.google.api.services.storage.model.StorageObject retrievedObject = objectName.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(objectName, clientConfig);
    try {
      return runWithRetries(
          new Callable<byte[]>() {
            @Override
            public byte[] call() {
              return rpcClient.load(retrievedObject, storageOptionMap);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageRpcBatch batch() {
    return new StorageRpcBatch(this.getOptions());
  }

  @Override
  public ReadChannel reader(String containerName, String objectName, BlobReadOption... clientConfig) {
    Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
    return new BlobReadStream(getOptions(), BlobIdentifier.from(containerName, objectName), storageOptionMap);
  }

  @Override
  public ReadChannel reader(BlobIdentifier objectName, BlobReadOption... clientConfig) {
    Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(objectName, clientConfig);
    return new BlobReadStream(getOptions(), objectName, storageOptionMap);
  }

  @Override
  public BlobUploadChannel writer(BlobMetadata blobMetadata, BlobWriteOptions... clientConfig) {
    Tuple<BlobMetadata, BlobUploadOption[]> targetParams = BlobUploadOption.convertWriteOptions(blobMetadata, clientConfig);
    return createWriter(targetParams.x(), targetParams.y());
  }

  @Override
  public BlobUploadChannel writer(URL presignedLink) {
    return new BlobUploadChannel(getOptions(), presignedLink);
  }

  private BlobUploadChannel createWriter(BlobMetadata blobMetadata, BlobUploadOption... clientConfig) {
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(blobMetadata, clientConfig);
    return new BlobUploadChannel(getOptions(), blobMetadata, storageOptionMap);
  }

  @Override
  public URL signUrl(BlobMetadata blobMetadata, long expiryDuration, TimeUnit timeMeasure, UrlSigningOption... clientConfig) {
    EnumMap<UrlSigningOption.RequestOption, Object> optionsLookup = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
    for (UrlSigningOption signingChoice : clientConfig) {
      optionsLookup.put(signingChoice.getOption(), signingChoice.getValue());
    }

    boolean versionTwoEnabled =
        getPreferredSignatureVersion(optionsLookup).equals(UrlSigningOption.SignatureProtocolVersion.V2);
    boolean versionFourEnabled =
        getPreferredSignatureVersion(optionsLookup).equals(UrlSigningOption.SignatureProtocolVersion.V4);

    ServiceAccountSigner serviceSigner =
        (ServiceAccountSigner) optionsLookup.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
    if (serviceSigner == null) {
      checkState(
          this.getOptions().getCredentials() instanceof ServiceAccountSigner,
          "Signing key was not provided and could not be derived");
      serviceSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
    }

    long expiryTimestamp =
        versionFourEnabled
            ? TimeUnit.SECONDS.convert(timeMeasure.toMillis(expiryDuration), TimeUnit.MILLISECONDS)
            : TimeUnit.SECONDS.convert(
                getOptions().getClock().millisTime() + timeMeasure.toMillis(expiryDuration),
                TimeUnit.MILLISECONDS);

    checkArgument(
        !(optionsLookup.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
            && optionsLookup.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)
            && optionsLookup.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)),
        "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be"
            + " specified.");

    String containerName = getSlashlessBucketName(blobMetadata);
    String escapedObjectName = "";
    if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
      escapedObjectName = rfc3986UriEncode(blobMetadata.getName(), false);
    }

    boolean enablePathStyle = usePathStyleForSignedUrl(optionsLookup);

    String xmlHost =
        enablePathStyle
            ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(optionsLookup)
            : STORAGE_XML_SCHEME + "://" + containerName + "." + getBaseStorageHostName(optionsLookup);

    if (optionsLookup.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      xmlHost = (String) optionsLookup.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
    }

    String servicePath =
        enablePathStyle
            ? buildResourceUriPath(containerName, escapedObjectName, optionsLookup)
            : buildResourceUriPath("", escapedObjectName, optionsLookup);

    URI fileLocation = URI.create(servicePath);
    // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
    // canonical resource string that we'll sign to include the bucket.
    URI signingUri =
        versionTwoEnabled ? URI.create(buildResourceUriPath(containerName, escapedObjectName, optionsLookup)) : fileLocation;

    try {
      SignatureDetails signatureDetails =
          buildCanonicalSignature(
                  optionsLookup, blobMetadata, expiryTimestamp, signingUri, serviceSigner.getAccount());
      String payloadToSign = signatureDetails.buildUnsignedPayload();
      byte[] signatureData = serviceSigner.sign(payloadToSign.getBytes(UTF_8));
      StringBuilder stringBuilder = new StringBuilder();
      stringBuilder.append(xmlHost).append(fileLocation);

      if (versionFourEnabled) {
        BaseEncoding baseEncoder = BaseEncoding.base16().lowerCase();
        String computedSignature = URLEncoder.encode(baseEncoder.encode(signatureData), UTF_8.name());
        String queryV4 = signatureDetails.buildV4QueryString();

        stringBuilder.append('?');
        if (!Strings.isNullOrEmpty(queryV4)) {
          stringBuilder.append(queryV4).append('&');
        }
        stringBuilder.append("X-Goog-Signature=").append(computedSignature);
      } else {
        BaseEncoding baseEncoder = BaseEncoding.base64();
        String computedSignature = URLEncoder.encode(baseEncoder.encode(signatureData), UTF_8.name());
        String v2Params = signatureDetails.buildV2QueryString();

        stringBuilder.append('?');
        if (!Strings.isNullOrEmpty(v2Params)) {
          stringBuilder.append(v2Params).append('&');
        }
        stringBuilder.append("GoogleAccessId=").append(serviceSigner.getAccount());
        stringBuilder.append("&Expires=").append(expiryTimestamp);
        stringBuilder.append("&Signature=").append(computedSignature);
      }

      return new URL(stringBuilder.toString());

    } catch (MalformedURLException | UnsupportedEncodingException exceptionCause) {
      throw new IllegalStateException(exceptionCause);
    }
  }

  @Override
  public S3PostPolicyV4 generateSignedPostPolicyV4(
      BlobMetadata blobMetadata,
      long expiryDuration,
      TimeUnit timeMeasure,
      S3PostPolicyV4.PostFieldsMapV4 formFields,
      PostConditionsVersion4 postConditions,
      PostPolicyV4FormField... clientConfig) {
    EnumMap<UrlSigningOption.RequestOption, Object> optionsLookup = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
    // Convert to a map of SignUrlOptions so we can re-use some utility methods
    for (PostPolicyV4FormField signingChoice : clientConfig) {
      optionsLookup.put(UrlSigningOption.RequestOption.valueOf(signingChoice.getOption().name()), signingChoice.getValue());
    }

    optionsLookup.put(UrlSigningOption.RequestOption.SIGNATURE_VERSION, UrlSigningOption.SignatureProtocolVersion.V4);

    ServiceAccountSigner serviceSigner =
        (ServiceAccountSigner) optionsLookup.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
    if (serviceSigner == null) {
      checkState(
          this.getOptions().getCredentials() instanceof ServiceAccountSigner,
          "Signing key was not provided and could not be derived");
      serviceSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
    }

    checkArgument(
        !(optionsLookup.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
            && optionsLookup.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)
            && optionsLookup.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)),
        "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be"
            + " specified.");

    String containerName = getSlashlessBucketName(blobMetadata);

    boolean enablePathStyle = usePathStyleForSignedUrl(optionsLookup);

    String endpointUrl;

    if (enablePathStyle) {
      endpointUrl = STORAGE_XML_SCHEME + "://" + STORAGE_XML_HOST + "/" + containerName + "/";
    } else {
      endpointUrl = STORAGE_XML_SCHEME + "://" + containerName + "." + STORAGE_XML_HOST + "/";
    }

    if (optionsLookup.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      endpointUrl = optionsLookup.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME) + "/";
    }

    SimpleDateFormat googleDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
    SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
    SimpleDateFormat expiryFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    googleDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    expiryFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));

    long currentTimestamp = getOptions().getClock().millisTime();
    String formattedDate = googleDateFormatter.format(currentTimestamp);
    String signingKey =
        serviceSigner.getAccount()
            + "/"
            + ymdFormatter.format(currentTimestamp)
            + "/auto/storage/goog4_request";

    Map<String, String> policyMap = new HashMap<>();

    S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder policyBuilder = postConditions.asBuilder();

    for (Map.Entry<String, String> mapEntry : formFields.getFieldsMap().entrySet()) {
      // Every field needs a corresponding policy condition, so add them if they're missing
      policyBuilder.addCustom(
          S3PostPolicyV4.ConditionV4Kind.MATCHES, mapEntry.getKey(), mapEntry.getValue());

      policyMap.put(mapEntry.getKey(), mapEntry.getValue());
    }

    S3PostPolicyV4.PostConditionsVersion4 version4Conditions =
        policyBuilder
            .addBucket(S3PostPolicyV4.ConditionV4Kind.MATCHES, blobMetadata.getBucket())
            .addKey(S3PostPolicyV4.ConditionV4Kind.MATCHES, blobMetadata.getName())
            .addCustom(S3PostPolicyV4.ConditionV4Kind.MATCHES, "x-goog-date", formattedDate)
            .addCustom(S3PostPolicyV4.ConditionV4Kind.MATCHES, "x-goog-credential", signingKey)
            .addCustom(S3PostPolicyV4.ConditionV4Kind.MATCHES, "x-goog-algorithm", "GOOG4-RSA-SHA256")
            .buildConditions();
    S3PostPolicyV4.PostPolicyV4JsonDocument jsonDocument =
        S3PostPolicyV4.PostPolicyV4JsonDocument.create(
            expiryFormatter.format(currentTimestamp + timeMeasure.toMillis(expiryDuration)), version4Conditions);
    String policyJson = BaseEncoding.base64().encode(jsonDocument.toJsonString().getBytes());
    String computedSignature =
        BaseEncoding.base16().encode(serviceSigner.sign(policyJson.getBytes())).toLowerCase();

    for (S3PostPolicyV4.ConditionalExpressionV4 conditionalExpression : version4Conditions.getConditions()) {
      if (conditionalExpression.type == S3PostPolicyV4.ConditionV4Kind.MATCHES) {
        policyMap.put(conditionalExpression.operand1, conditionalExpression.operand2);
      }
    }
    policyMap.put("key", blobMetadata.getName());
    policyMap.put("x-goog-credential", signingKey);
    policyMap.put("x-goog-algorithm", "GOOG4-RSA-SHA256");
    policyMap.put("x-goog-date", formattedDate);
    policyMap.put("x-goog-signature", computedSignature);
    policyMap.put("policy", policyJson);

    policyMap.remove("bucket");

    return S3PostPolicyV4.from(endpointUrl, policyMap);
  }

  public S3PostPolicyV4 generateSignedPostPolicyV4(
      BlobMetadata blobMetadata,
      long expiryDuration,
      TimeUnit timeMeasure,
      S3PostPolicyV4.PostFieldsMapV4 formFields,
      PostPolicyV4FormField... clientConfig) {
    return generateSignedPostPolicyV4(
            blobMetadata, expiryDuration, timeMeasure, formFields, PostConditionsVersion4.createBuilder().buildConditions(), clientConfig);
  }

  public S3PostPolicyV4 generateSignedPostPolicyV4(
      BlobMetadata blobMetadata,
      long expiryDuration,
      TimeUnit timeMeasure,
      S3PostPolicyV4.PostConditionsVersion4 postConditions,
      PostPolicyV4FormField... clientConfig) {
    return generateSignedPostPolicyV4(
            blobMetadata, expiryDuration, timeMeasure, PostFieldsMapV4.createBuilder().buildMap(), postConditions, clientConfig);
  }

  public S3PostPolicyV4 generateSignedPostPolicyV4(
          BlobMetadata blobMetadata, long expiryDuration, TimeUnit timeMeasure, PostPolicyV4FormField... clientConfig) {
    return generateSignedPostPolicyV4(
            blobMetadata, expiryDuration, timeMeasure, S3PostPolicyV4.PostFieldsMapV4.createBuilder().buildMap(), clientConfig);
  }

  private String buildResourceUriPath(
      String cleanContainer,
      String escapedObjectName,
      EnumMap<UrlSigningOption.RequestOption, Object> optionsLookup) {
    if (Strings.isNullOrEmpty(cleanContainer)) {
      if (Strings.isNullOrEmpty(escapedObjectName)) {
        return PATH_SEPARATOR;
      }
      if (escapedObjectName.startsWith(PATH_SEPARATOR)) {
        return escapedObjectName;
      }
      return PATH_SEPARATOR + escapedObjectName;
    }

    StringBuilder uriBuilder = new StringBuilder();
    uriBuilder.append(PATH_SEPARATOR).append(cleanContainer);
    if (Strings.isNullOrEmpty(escapedObjectName)) {
      boolean versionTwoEnabled =
          getPreferredSignatureVersion(optionsLookup).equals(UrlSigningOption.SignatureProtocolVersion.V2);
      // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
      // must end with a forward slash.
      if (optionsLookup.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && versionTwoEnabled) {
        uriBuilder.append(PATH_SEPARATOR);
      }
      return uriBuilder.toString();
    }
    uriBuilder.append(PATH_SEPARATOR);
    uriBuilder.append(escapedObjectName);
    return uriBuilder.toString();
  }

  private UrlSigningOption.SignatureProtocolVersion getPreferredSignatureVersion(
      EnumMap<UrlSigningOption.RequestOption, Object> optionsLookup) {
    // Check for an explicitly specified version in the map.
    for (UrlSigningOption.SignatureProtocolVersion signatureProtocol : UrlSigningOption.SignatureProtocolVersion.values()) {
      if (signatureProtocol.equals(optionsLookup.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION))) {
        return signatureProtocol;
      }
    }
    // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
    // Change this to V4 once we make it the default.
    return UrlSigningOption.SignatureProtocolVersion.V2;
  }

  private boolean usePathStyleForSignedUrl(EnumMap<UrlSigningOption.RequestOption, Object> optionsLookup) {
    // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
    // logic to return false unless PATH_STYLE was explicitly specified.
    if (optionsLookup.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
        || optionsLookup.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      return false;
    }
    return true;
  }

  /**
   * Builds signature info.
   *
   * @param optionsLookup the option map
   * @param blobMetadata the blob info
   * @param expiryTimestamp the expiration in seconds
   * @param fileLocation the resource URI
   * @param servicePrincipalEmail the account email
   * @return signature info
   */
  private SignatureDetails buildCanonicalSignature(
      Map<UrlSigningOption.RequestOption, Object> optionsLookup,
      BlobMetadata blobMetadata,
      long expiryTimestamp,
      URI fileLocation,
      String servicePrincipalEmail) {

    HttpRequestMethod httpMethod =
        optionsLookup.containsKey(UrlSigningOption.RequestOption.HTTP_METHOD)
            ? (HttpRequestMethod) optionsLookup.get(UrlSigningOption.RequestOption.HTTP_METHOD)
            : HttpRequestMethod.GET;

    SignatureDetails.CanonicalStringBuilder canonicalBuilder =
        new SignatureDetails.CanonicalStringBuilder(httpMethod, expiryTimestamp, fileLocation);

    if (firstNonNull((Boolean) optionsLookup.get(UrlSigningOption.RequestOption.MD5), false)) {
      checkArgument(blobMetadata.getMd5() != null, "Blob is missing a value for md5");
      canonicalBuilder.setContentMd5(blobMetadata.getMd5());
    }

    if (firstNonNull((Boolean) optionsLookup.get(UrlSigningOption.RequestOption.CONTENT_TYPE), false)) {
      checkArgument(blobMetadata.getContentType() != null, "Blob is missing a value for content-type");
      canonicalBuilder.setContentType(blobMetadata.getContentType());
    }

    canonicalBuilder.setSignatureVersion(
        (UrlSigningOption.SignatureProtocolVersion) optionsLookup.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));

    canonicalBuilder.setAccountEmail(servicePrincipalEmail);

    canonicalBuilder.setTimestamp(getOptions().getClock().millisTime());

    ImmutableMap.Builder<String, String> additionalHeadersBuilder =
        new ImmutableMap.Builder<String, String>();

    boolean versionFourEnabled =
        UrlSigningOption.SignatureProtocolVersion.V4.equals(
            optionsLookup.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
    if (versionFourEnabled) { // We don't sign the host header for V2 signed URLs; only do this for V4.
      // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
      if (optionsLookup.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
        additionalHeadersBuilder.put(
            "host",
            getSlashlessBucketName(blobMetadata) + "." + getBaseStorageHostName(optionsLookup));
      } else if (optionsLookup.containsKey(UrlSigningOption.RequestOption.HOST_NAME)
          || optionsLookup.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
        additionalHeadersBuilder.put("host", getBaseStorageHostName(optionsLookup));
      }
    }

    if (optionsLookup.containsKey(UrlSigningOption.RequestOption.EXT_HEADERS)) {
      additionalHeadersBuilder.putAll(
          (Map<String, String>) optionsLookup.get(UrlSigningOption.RequestOption.EXT_HEADERS));
    }

    ImmutableMap.Builder<String, String> queryStringBuilder =
        new ImmutableMap.Builder<String, String>();
    if (optionsLookup.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
      queryStringBuilder.putAll(
          (Map<String, String>) optionsLookup.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
    }

    return canonicalBuilder
        .setCanonicalizedExtensionHeaders((Map<String, String>) additionalHeadersBuilder.build())
        .setCanonicalizedQueryParams((Map<String, String>) queryStringBuilder.build())
        .buildCanonicalString();
  }

  private String getSlashlessBucketName(BlobMetadata blobMetadata) {
    // The bucket name itself should never contain a forward slash. However, parts already existed
    // in the code to check for this, so we remove the forward slashes to be safe here.
    return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
  }

  /** Returns the hostname used to send requests to Cloud Storage, e.g. "storage.googleapis.com". */
  private String getBaseStorageHostName(Map<UrlSigningOption.RequestOption, Object> optionsLookup) {
    String explicitHost = (String) optionsLookup.get(UrlSigningOption.RequestOption.HOST_NAME);
    String containerScopedHost =
        (String) optionsLookup.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
    if (!Strings.isNullOrEmpty(explicitHost)) {
      return explicitHost.replaceFirst("http(s)?://", "");
    }
    if (!Strings.isNullOrEmpty(containerScopedHost)) {
      return containerScopedHost.replaceFirst("http(s)?://", "");
    }
    return STORAGE_XML_HOST;
  }

  @Override
  public List<StorageObject> get(BlobIdentifier... objectIdentifiers) {
    return get(Arrays.asList(objectIdentifiers));
  }

  @Override
  public List<StorageObject> get(Iterable<BlobIdentifier> objectIdentifiers) {
    StorageRpcBatch rpcGroup = batch();
    final List<StorageObject> retrievedObjects = Lists.newArrayList();
    for (BlobIdentifier objectName : objectIdentifiers) {
      rpcGroup
          .get(objectName)
          .notify(
              new BatchResult.Callback<StorageObject, StorageServiceException>() {
                @Override
                public void success(StorageObject result) {
                  retrievedObjects.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                  retrievedObjects.add(null);
                }
              });
    }
    rpcGroup.submitBatch();
    return Collections.unmodifiableList(retrievedObjects);
  }

  @Override
  public List<StorageObject> update(BlobMetadata... metadataArray) {
    return update(Arrays.asList(metadataArray));
  }

  @Override
  public List<StorageObject> update(Iterable<BlobMetadata> metadataArray) {
    StorageRpcBatch rpcGroup = batch();
    final List<StorageObject> retrievedObjects = Lists.newArrayList();
    for (BlobMetadata blobMetadata : metadataArray) {
      rpcGroup
          .modify(blobMetadata)
          .notify(
              new BatchResult.Callback<StorageObject, StorageServiceException>() {
                @Override
                public void success(StorageObject result) {
                  retrievedObjects.add(result);
                }

                @Override
                public void error(StorageServiceException exception) {
                  retrievedObjects.add(null);
                }
              });
    }
    rpcGroup.submitBatch();
    return Collections.unmodifiableList(retrievedObjects);
  }

  @Override
  public List<Boolean> delete(BlobIdentifier... objectIdentifiers) {
    return delete(Arrays.asList(objectIdentifiers));
  }

  @Override
  public List<Boolean> delete(Iterable<BlobIdentifier> objectIdentifiers) {
    StorageRpcBatch rpcGroup = batch();
    final List<Boolean> retrievedObjects = Lists.newArrayList();
    for (BlobIdentifier objectName : objectIdentifiers) {
      rpcGroup
          .remove(objectName)
          .notify(
              new BatchResult.Callback<Boolean, StorageServiceException>() {
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
    rpcGroup.submitBatch();
    return Collections.unmodifiableList(retrievedObjects);
  }

  @Override
  public AccessControlEntry getAcl(final String containerName, final AccessControlEntry.ProtoEntity protoEntity, BucketOption... clientConfig) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
      BucketAccessControl result =
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return rpcClient.getAcl(containerName, protoEntity.toProto(), storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return result == null ? null : AccessControlEntry.fromProto(result);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public AccessControlEntry getAcl(final String containerName, final AccessControlEntry.ProtoEntity protoEntity) {
    return getAcl(containerName, protoEntity, new BucketOption[0]);
  }

  @Override
  public boolean deleteAcl(
          final String containerName, final AccessControlEntry.ProtoEntity protoEntity, BucketOption... clientConfig) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteAcl(containerName, protoEntity.toProto(), storageOptionMap);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public boolean deleteAcl(final String containerName, final AccessControlEntry.ProtoEntity protoEntity) {
    return deleteAcl(containerName, protoEntity, new BucketOption[0]);
  }

  @Override
  public AccessControlEntry createAcl(String containerName, AccessControlEntry accessControlEntry, BucketOption... clientConfig) {
    final BucketAccessControl bucketAcl = accessControlEntry.toBucketProto().setBucket(containerName);
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
      return AccessControlEntry.fromProto(
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return rpcClient.createAcl(bucketAcl, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public AccessControlEntry createAcl(String containerName, AccessControlEntry accessControlEntry) {
    return createAcl(containerName, accessControlEntry, new BucketOption[0]);
  }

  @Override
  public AccessControlEntry updateAcl(String containerName, AccessControlEntry accessControlEntry, BucketOption... clientConfig) {
    final BucketAccessControl bucketAcl = accessControlEntry.toBucketProto().setBucket(containerName);
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
      return AccessControlEntry.fromProto(
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return rpcClient.patchAcl(bucketAcl, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public AccessControlEntry updateAcl(String containerName, AccessControlEntry accessControlEntry) {
    return updateAcl(containerName, accessControlEntry, new BucketOption[0]);
  }

  @Override
  public List<AccessControlEntry> listAcls(final String containerName, BucketOption... clientConfig) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
      List<BucketAccessControl> result =
          runWithRetries(
              new Callable<List<BucketAccessControl>>() {
                @Override
                public List<BucketAccessControl> call() {
                  return rpcClient.listAcls(containerName, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(result, AccessControlEntry.FROM_BUCKET_PB_TO_ENTRY);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public List<AccessControlEntry> listAcls(final String containerName) {
    return listAcls(containerName, new BucketOption[0]);
  }

  @Override
  public AccessControlEntry getDefaultAcl(final String containerName, final AccessControlEntry.ProtoEntity protoEntity) {
    try {
      ObjectAccessControl result =
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.getDefaultAcl(containerName, protoEntity.toProto());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return result == null ? null : AccessControlEntry.fromProto(result);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public boolean deleteDefaultAcl(final String containerName, final AccessControlEntry.ProtoEntity protoEntity) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteDefaultAcl(containerName, protoEntity.toProto());
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public AccessControlEntry createDefaultAcl(String containerName, AccessControlEntry accessControlEntry) {
    final ObjectAccessControl bucketAcl = accessControlEntry.toObjectProto().setBucket(containerName);
    try {
      return AccessControlEntry.fromProto(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.createDefaultAcl(bucketAcl);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public AccessControlEntry updateDefaultAcl(String containerName, AccessControlEntry accessControlEntry) {
    final ObjectAccessControl bucketAcl = accessControlEntry.toObjectProto().setBucket(containerName);
    try {
      return AccessControlEntry.fromProto(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.patchDefaultAcl(bucketAcl);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public List<AccessControlEntry> listDefaultAcls(final String containerName) {
    try {
      List<ObjectAccessControl> result =
          runWithRetries(
              new Callable<List<ObjectAccessControl>>() {
                @Override
                public List<ObjectAccessControl> call() {
                  return rpcClient.listDefaultAcls(containerName);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(result, AccessControlEntry.FROM_OBJECT_PB_TO_ENTRY);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public AccessControlEntry getAcl(final BlobIdentifier objectName, final ProtoEntity protoEntity) {
    try {
      ObjectAccessControl result =
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.getAcl(
                      objectName.getBucket(), objectName.getName(), objectName.getGeneration(), protoEntity.toProto());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return result == null ? null : AccessControlEntry.fromProto(result);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public boolean deleteAcl(final BlobIdentifier objectName, final AccessControlEntry.ProtoEntity protoEntity) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteAcl(
                  objectName.getBucket(), objectName.getName(), objectName.getGeneration(), protoEntity.toProto());
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public AccessControlEntry createAcl(final BlobIdentifier objectName, final AccessControlEntry accessControlEntry) {
    final ObjectAccessControl bucketAcl =
        accessControlEntry.toObjectProto()
            .setBucket(objectName.getBucket())
            .setObject(objectName.getName())
            .setGeneration(objectName.getGeneration());
    try {
      return AccessControlEntry.fromProto(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.createAcl(bucketAcl);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public AccessControlEntry updateAcl(BlobIdentifier objectName, AccessControlEntry accessControlEntry) {
    final ObjectAccessControl bucketAcl =
        accessControlEntry.toObjectProto()
            .setBucket(objectName.getBucket())
            .setObject(objectName.getName())
            .setGeneration(objectName.getGeneration());
    try {
      return AccessControlEntry.fromProto(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.patchAcl(bucketAcl);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public List<AccessControlEntry> listAcls(final BlobIdentifier objectName) {
    try {
      List<ObjectAccessControl> result =
          runWithRetries(
              new Callable<List<ObjectAccessControl>>() {
                @Override
                public List<ObjectAccessControl> call() {
                  return rpcClient.listAcls(
                      objectName.getBucket(), objectName.getName(), objectName.getGeneration());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(result, AccessControlEntry.FROM_OBJECT_PB_TO_ENTRY);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  public HmacSecretKey createHmacKey(
          final ServiceAccountInfo accountInfo, final CreateHmacKeyRequestOption... clientConfig) {
    try {
      return HmacSecretKey.fromProto(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKey>() {
                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                  return rpcClient.createHmacKey(accountInfo.getEmail(), buildOptionMap(clientConfig));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public Page<HmacKeyInfo> listHmacKeys(ListHmacKeysOptions... clientConfig) {
    return listAllHmacKeys(getOptions(), buildOptionMap(clientConfig));
  }

  @Override
  public HmacSecretKey.HmacKeyInfo getHmacKey(final String accessKey, final GetHmacKeyRequestOption... clientConfig) {
    try {
      return HmacSecretKey.HmacKeyInfo.fromProto(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {
                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                  return rpcClient.getHmacKey(accessKey, buildOptionMap(clientConfig));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  private HmacSecretKey.HmacKeyInfo modifyHmacKey(
          final HmacKeyInfo hmacInfo, final HmacKeyUpdateOption... clientConfig) {
    try {
      return HmacKeyInfo.fromProto(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {
                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                  return rpcClient.updateHmacKey(hmacInfo.toProto(), buildOptionMap(clientConfig));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public HmacSecretKey.HmacKeyInfo updateHmacKeyState(
      final HmacKeyInfo hmacInfo,
      final HmacSecretKey.HmacKeyStatus hmacStatus,
      final HmacKeyUpdateOption... clientConfig) {
    HmacKeyInfo resultMetadata =
        HmacSecretKey.HmacKeyInfo.newServiceAccountBuilder(hmacInfo.getServiceAccount())
            .setProjectId(hmacInfo.getProjectId())
            .setAccessId(hmacInfo.getAccessId())
            .setState(hmacStatus)
            .create();
    return modifyHmacKey(resultMetadata, clientConfig);
  }

  @Override
  public void deleteHmacKey(final HmacSecretKey.HmacKeyInfo keyInfo, final DeleteHmacKeyRequestOption... clientConfig) {
    try {
      runWithRetries(
          new Callable<Void>() {
            @Override
            public Void call() {
              rpcClient.deleteHmacKey(keyInfo.toProto(), buildOptionMap(clientConfig));
              return null;
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  private static Page<HmacKeyInfo> listAllHmacKeys(
          final StorageClientOptions clientConfig, final Map<StorageRpcClient.StorageOption, ?> storageClientConfig) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> listOutcome =
          runWithRetries(
              new Callable<
                  Tuple<
                      String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {
                @Override
                public Tuple<
                        String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>
                    call() {
                  return clientConfig.getStorageRpcV1().listHmacKeys(storageClientConfig);
                }
              },
              clientConfig.getRetrySettings(),
              EXCEPTION_HANDLER,
              clientConfig.getClock());
      String pageCursor = listOutcome.x();
      final Iterable<HmacKeyInfo> keyInfo =
          listOutcome.y() == null
              ? ImmutableList.<HmacSecretKey.HmacKeyInfo>of()
              : Iterables.transform(
                  listOutcome.y(),
                  new Function<
                      com.google.api.services.storage.model.HmacKeyMetadata, HmacSecretKey.HmacKeyInfo>() {
                    @Override
                    public HmacSecretKey.HmacKeyInfo apply(
                        com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                      return HmacSecretKey.HmacKeyInfo.fromProto(metadataPb);
                    }
                  });
      return new PageImpl<>(
          new HmacKeyMetadataPageRetriever(clientConfig, storageClientConfig), pageCursor, keyInfo);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public Policy getIamPolicy(final String containerName, BucketOption... clientConfig) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
      return convertApiPolicyToPolicy(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Policy>() {
                @Override
                public com.google.api.services.storage.model.Policy call() {
                  return rpcClient.getIamPolicy(containerName, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public Policy setIamPolicy(
          final String containerName, final Policy policyJson, BucketOption... clientConfig) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
      return convertApiPolicyToPolicy(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Policy>() {
                @Override
                public com.google.api.services.storage.model.Policy call() {
                  return rpcClient.setIamPolicy(containerName, convertPolicyToApiPolicy(policyJson), storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public List<Boolean> testIamPermissions(
          final String containerName, final List<String> actions, BucketOption... clientConfig) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(clientConfig);
      TestIamPermissionsResponse iamPermissionsResult =
          runWithRetries(
              new Callable<TestIamPermissionsResponse>() {
                @Override
                public TestIamPermissionsResponse call() {
                  return rpcClient.testIamPermissions(containerName, actions, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      final Set<String> retainedPermissions =
          iamPermissionsResult.getPermissions() != null
              ? ImmutableSet.copyOf(iamPermissionsResult.getPermissions())
              : ImmutableSet.<String>of();
      return Lists.transform(
              actions,
          new Function<String, Boolean>() {
            @Override
            public Boolean apply(String permission) {
              return retainedPermissions.contains(permission);
            }
          });
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public StorageBucket lockRetentionPolicy(BucketInfo bucketMetadata, BucketTargetOptions... clientConfig) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionMap = buildOptionMap(bucketMetadata, clientConfig);
    try {
      return StorageBucket.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return rpcClient.lockRetentionPolicy(bucketProto, storageOptionMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  @Override
  public ServiceAccountInfo getServiceAccount(final String projectIdentifier) {
    try {
      com.google.api.services.storage.model.ServiceAccount result =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.ServiceAccount>() {
                @Override
                public com.google.api.services.storage.model.ServiceAccount call() {
                  return rpcClient.getServiceAccount(projectIdentifier);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return result == null ? null : ServiceAccountInfo.fromProto(result);
    } catch (RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  private static <T> void addOptionToMap(
          StorageRpcClient.StorageOption signingChoice, T fallbackValue, Map<StorageRpcClient.StorageOption, Object> optionMapping) {
    addOptionToMap(signingChoice, signingChoice, fallbackValue, optionMapping);
  }

  private static <T> void addOptionToMap(
      StorageRpcClient.StorageOption requestedOption,
      StorageRpcClient.StorageOption destinationOption,
      T fallbackValue,
      Map<StorageRpcClient.StorageOption, Object> optionMapping) {
    if (optionMapping.containsKey(requestedOption)) {
      @SuppressWarnings("unchecked")
      T item = (T) optionMapping.remove(requestedOption);
      checkArgument(
          item != null || fallbackValue != null,
          "Option " + requestedOption.getValue() + " is missing a value");
      item = firstNonNull(item, fallbackValue);
      optionMapping.put(destinationOption, item);
    }
  }

  private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(
          Long genId, Long metaGen, Iterable<? extends AbstractOption> clientConfig) {
    return buildOptionMap(genId, metaGen, clientConfig, false);
  }

  private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(
      Long genId,
      Long metaGen,
      Iterable<? extends AbstractOption> clientConfig,
      boolean treatAsSource) {
    Map<StorageRpcClient.StorageOption, Object> interimMap = Maps.newEnumMap(StorageRpcClient.StorageOption.class);
    for (AbstractOption signingChoice : clientConfig) {
      Object priorEntry = interimMap.put(signingChoice.getRpcOption(), signingChoice.getValue());
      checkArgument(priorEntry == null, "Duplicate option %s", signingChoice);
    }
    if (Boolean.TRUE.equals(interimMap.get(DELIMITER))) {
      interimMap.remove(DELIMITER);
      interimMap.put(DELIMITER, PATH_SEPARATOR);
    } else if (null != interimMap.get(DELIMITER)) {
      interimMap.put(DELIMITER, interimMap.get(DELIMITER));
    }
    if (treatAsSource) {
      addOptionToMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, genId, interimMap);
      addOptionToMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, genId, interimMap);
      addOptionToMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGen, interimMap);
      addOptionToMap(
          IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGen, interimMap);
    } else {
      addOptionToMap(IF_GENERATION_MATCH, genId, interimMap);
      addOptionToMap(IF_GENERATION_NOT_MATCH, genId, interimMap);
      addOptionToMap(IF_METAGENERATION_MATCH, metaGen, interimMap);
      addOptionToMap(IF_METAGENERATION_NOT_MATCH, metaGen, interimMap);
    }
    return ImmutableMap.copyOf(interimMap);
  }

  private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(AbstractOption... clientConfig) {
    return optionMap(null, null, Arrays.asList(clientConfig));
  }

  private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(
          Long genId, Long metaGen, AbstractOption... clientConfig) {
    return optionMap(genId, metaGen, Arrays.asList(clientConfig));
  }

  private static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BucketInfo bucketMetadata, AbstractOption... clientConfig) {
    return buildOptionMap(null, bucketMetadata.getMetageneration(), clientConfig);
  }

  static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BlobMetadata blobMetadata, AbstractOption... clientConfig) {
    return buildOptionMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), clientConfig);
  }

  static Map<StorageRpcClient.StorageOption, ?> buildOptionMap(BlobIdentifier blobIdentifier, AbstractOption... clientConfig) {
    return buildOptionMap(blobIdentifier.getGeneration(), null, clientConfig);
  }
}
