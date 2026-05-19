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
import com.google.cloud.storage.AclEntry.BaseEntity;
import com.google.cloud.storage.HmacSecretKey.HmacKeyInfo;
import com.google.cloud.storage.PostPolicyV4.ConditionV4Type;
import com.google.cloud.storage.PostPolicyV4.PostConditionsV4;
import com.google.cloud.storage.PostPolicyV4.PostFieldsV4;
import com.google.cloud.storage.PostPolicyV4.PostPolicyV4Document;
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

final class StorageServiceImpl extends BaseService<StorageSettings> implements Storage {

  private static final byte[] ZERO_LENGTH_BYTES = {};
  private static final String ZERO_BYTES_MD5 = "1B2M2Y8AsgTpgAmY7PhCfg==";
  private static final String ZERO_BYTES_CRC32C = "AAAAAA==";
  private static final String PATH_SEPARATOR = "/";
  /** Signed URLs are only supported through the GCS XML API endpoint. */
  private static final String STORAGE_XML_SCHEME = "https";

  private static final String STORAGE_XML_HOSTNAME = "storage.googleapis.com";

  private static final Function<Tuple<Storage, Boolean>, Boolean> DELETE_FUNCTION =
      new Function<Tuple<Storage, Boolean>, Boolean>() {
        @Override
        public Boolean apply(Tuple<Storage, Boolean> tuple) {
          return tuple.y();
        }
      };

  private final StorageRpcClient storageClient;

  StorageServiceImpl(StorageSettings settings) {
    super(settings);
    storageClient = settings.getStorageRpcV1();
  }

  @Override
  public StorageBucket create(BucketMetadata bucketMeta, BucketTargetOptions... settings) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMeta.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(bucketMeta, settings);
    try {
      return StorageBucket.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return storageClient.create(bucketProto, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageObject create(BlobInfo blobMetadata, BlobUploadOption... settings) {
    BlobInfo updatedBlobInfo =
        blobMetadata
            .asBuilder()
            .setMd5(ZERO_BYTES_MD5)
            .setCrc32c(ZERO_BYTES_CRC32C)
            .buildMetadata();
    return createInternal(updatedBlobInfo, ZERO_LENGTH_BYTES, settings);
  }

  @Override
  public StorageObject create(BlobInfo blobMetadata, byte[] data, BlobUploadOption... settings) {
    data = firstNonNull(data, ZERO_LENGTH_BYTES);
    BlobInfo updatedBlobInfo =
        blobMetadata
            .asBuilder()
            .setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(data).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(Ints.toByteArray(Hashing.crc32c().hashBytes(data).asInt())))
            .buildMetadata();
    return createInternal(updatedBlobInfo, data, settings);
  }

  @Override
  public StorageObject create(
          BlobInfo blobMetadata, byte[] data, int startOffset, int lengthBytes, BlobUploadOption... settings) {
    data = firstNonNull(data, ZERO_LENGTH_BYTES);
    byte[] subBytes = Arrays.copyOfRange(data, startOffset, startOffset + lengthBytes);
    BlobInfo updatedBlobInfo =
        blobMetadata
            .asBuilder()
            .setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(subBytes).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(Ints.toByteArray(Hashing.crc32c().hashBytes(subBytes).asInt())))
            .buildMetadata();
    return createInternal(updatedBlobInfo, subBytes, settings);
  }

  @Override
  @Deprecated
  public StorageObject create(BlobInfo blobMetadata, InputStream data, BlobWriteOptions... settings) {
    Tuple<BlobInfo, BlobUploadOption[]> blobTargetOptions = BlobUploadOption.convertOptions(blobMetadata, settings);
    com.google.api.services.storage.model.StorageObject blobProto = blobTargetOptions.x().toProto();
    Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(blobTargetOptions.x(), blobTargetOptions.y());
    InputStream inputStream =
        firstNonNull(data, new ByteArrayInputStream(ZERO_LENGTH_BYTES));
    // retries are not safe when the input is an InputStream, so we can't retry.
    return StorageObject.fromProto(this, storageClient.create(blobProto, inputStream, storageOptionsMap));
  }

  private StorageObject createInternal(BlobInfo blobMetadata, final byte[] data, BlobUploadOption... settings) {
    Preconditions.checkNotNull(data);
    final com.google.api.services.storage.model.StorageObject blobProto = blobMetadata.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(blobMetadata, settings);
    try {
      return StorageObject.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.StorageObject>() {
                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                  return storageClient.create(blobProto, new ByteArrayInputStream(data), storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageBucket get(String containerName, BucketGetOptions... settings) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketMetadata.ofName(containerName).toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
    try {
      com.google.api.services.storage.model.Bucket resultBucket =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return storageClient.get(bucketProto, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return resultBucket == null ? null : StorageBucket.fromProto(this, resultBucket);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageObject get(String containerName, String objectName, BlobGetOptions... settings) {
    return get(BlobIdentifier.create(containerName, objectName), settings);
  }

  @Override
  public StorageObject get(BlobIdentifier objectName, BlobGetOptions... settings) {
    final com.google.api.services.storage.model.StorageObject persistedObject = objectName.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(objectName, settings);
    try {
      com.google.api.services.storage.model.StorageObject storageItem =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.StorageObject>() {
                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                  return storageClient.get(persistedObject, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return storageItem == null ? null : StorageObject.fromProto(this, storageItem);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageObject get(BlobIdentifier objectName) {
    return get(objectName, new BlobGetOptions[0]);
  }

  private static class BucketPageIterator implements NextPageFetcher<StorageBucket> {

    private static final long serialVersionUID = 5850406828803613729L;
    private final Map<StorageRpcClient.StorageOption, ?> pageParams;
    private final StorageSettings storageSettings;

    BucketPageIterator(
            StorageSettings storageSettings, String pageToken, Map<StorageRpcClient.StorageOption, ?> optionMapping) {
      this.pageParams =
          PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, pageToken, optionMapping);
      this.storageSettings = storageSettings;
    }

    @Override
    public Page<StorageBucket> getNextPage() {
      return listAllBuckets(storageSettings, pageParams);
    }
  }

  private static class BlobPageRetriever implements NextPageFetcher<StorageObject> {

    private static final long serialVersionUID = 81807334445874098L;
    private final Map<StorageRpcClient.StorageOption, ?> pageParams;
    private final StorageSettings storageSettings;
    private final String containerName;

    BlobPageRetriever(
        String containerName,
        StorageSettings storageSettings,
        String pageToken,
        Map<StorageRpcClient.StorageOption, ?> optionMapping) {
      this.pageParams =
          PageImpl.nextRequestOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, pageToken, optionMapping);
      this.storageSettings = storageSettings;
      this.containerName = containerName;
    }

    @Override
    public Page<StorageObject> getNextPage() {
      return listAllBlobs(containerName, storageSettings, pageParams);
    }
  }

  private static class HmacKeyMetadataPaginator implements NextPageFetcher<HmacKeyInfo> {

    private static final long serialVersionUID = 308012320541700881L;
    private final StorageSettings storageSettings;
    private final Map<StorageRpcClient.StorageOption, ?> settings;

    HmacKeyMetadataPaginator(StorageSettings storageSettings, Map<StorageRpcClient.StorageOption, ?> settings) {
      this.storageSettings = storageSettings;
      this.settings = settings;
    }

    @Override
    public Page<HmacKeyInfo> getNextPage() {
      return listAllHmacKeys(storageSettings, settings);
    }
  }

  @Override
  public Page<StorageBucket> list(BucketListOptions... settings) {
    return listAllBuckets(getOptions(), createOptionMap(settings));
  }

  @Override
  public Page<StorageObject> list(final String containerName, BlobListOptions... settings) {
    return listAllBlobs(containerName, getOptions(), createOptionMap(settings));
  }

  private static Page<StorageBucket> listAllBuckets(
          final StorageSettings storageSettings, final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> bucketsPage =
          runWithRetries(
              new Callable<
                  Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {
                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>
                    call() {
                  return storageSettings.getStorageRpcV1().list(storageOptionsMap);
                }
              },
              storageSettings.getRetrySettings(),
              EXCEPTION_HANDLER,
              storageSettings.getClock());
      String pageToken = bucketsPage.x();
      Iterable<StorageBucket> storageBucketCollection =
          bucketsPage.y() == null
              ? ImmutableList.<StorageBucket>of()
              : Iterables.transform(
                  bucketsPage.y(),
                  new Function<com.google.api.services.storage.model.Bucket, StorageBucket>() {
                    @Override
                    public StorageBucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                      return StorageBucket.fromProto(storageSettings.getService(), bucketPb);
                    }
                  });
      return new PageImpl<>(
          new BucketPageIterator(storageSettings, pageToken, storageOptionsMap), pageToken, storageBucketCollection);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  private static Page<StorageObject> listAllBlobs(
      final String containerName,
      final StorageSettings storageSettings,
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> bucketsPage =
          runWithRetries(
              new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>>>() {
                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> call() {
                  return storageSettings.getStorageRpcV1().list(containerName, storageOptionsMap);
                }
              },
              storageSettings.getRetrySettings(),
              EXCEPTION_HANDLER,
              storageSettings.getClock());
      String pageToken = bucketsPage.x();
      Iterable<StorageObject> objectCollection =
          bucketsPage.y() == null
              ? ImmutableList.<StorageObject>of()
              : Iterables.transform(
                  bucketsPage.y(),
                  new Function<com.google.api.services.storage.model.StorageObject, StorageObject>() {
                    @Override
                    public StorageObject apply(com.google.api.services.storage.model.StorageObject storageObject) {
                      return StorageObject.fromProto(storageSettings.getService(), storageObject);
                    }
                  });
      return new PageImpl<>(
          new BlobPageRetriever(containerName, storageSettings, pageToken, storageOptionsMap), pageToken, objectCollection);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageBucket update(BucketMetadata bucketMeta, BucketTargetOptions... settings) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMeta.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(bucketMeta, settings);
    try {
      return StorageBucket.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return storageClient.patch(bucketProto, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageObject update(BlobInfo blobMetadata, BlobUploadOption... settings) {
    final com.google.api.services.storage.model.StorageObject storageItem = blobMetadata.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(blobMetadata, settings);
    try {
      return StorageObject.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.StorageObject>() {
                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                  return storageClient.patch(storageItem, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageObject update(BlobInfo blobMetadata) {
    return update(blobMetadata, new BlobUploadOption[0]);
  }

  @Override
  public boolean delete(String containerName, BucketSourceRequestOption... settings) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketMetadata.ofName(containerName).toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return storageClient.delete(bucketProto, storageOptionsMap);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public boolean delete(String containerName, String objectName, BlobSourceSettings... settings) {
    return delete(BlobIdentifier.create(containerName, objectName), settings);
  }

  @Override
  public boolean delete(BlobIdentifier objectName, BlobSourceSettings... settings) {
    final com.google.api.services.storage.model.StorageObject storageItem = objectName.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(objectName, settings);
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return storageClient.delete(storageItem, storageOptionsMap);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public boolean delete(BlobIdentifier objectName) {
    return delete(objectName, new BlobSourceSettings[0]);
  }

  @Override
  public StorageObject compose(final ComposeObjectsRequest composeOperation) {
    final List<com.google.api.services.storage.model.StorageObject> sourceObjects =
        Lists.newArrayListWithCapacity(composeOperation.getSourceBlobs().size());
    for (ComposeObjectsRequest.SourceBlobMetadata sourceMetadata : composeOperation.getSourceBlobs()) {
      sourceObjects.add(
          BlobInfo.newBuilder(
                  BlobIdentifier.create(
                      composeOperation.getTarget().getBucket(),
                      sourceMetadata.getName(),
                      sourceMetadata.getGeneration()))
              .buildMetadata()
              .toProto());
    }
    final com.google.api.services.storage.model.StorageObject destinationObject = composeOperation.getTarget().toProto();
    final Map<StorageRpcClient.StorageOption, ?> blobTargetOptions =
        optionMap(
            composeOperation.getTarget().getGeneration(),
            composeOperation.getTarget().getMetageneration(),
            composeOperation.getTargetOptions());
    try {
      return StorageObject.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.StorageObject>() {
                @Override
                public com.google.api.services.storage.model.StorageObject call() {
                  return storageClient.compose(sourceObjects, destinationObject, blobTargetOptions);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public ChunkedCopyWriter copy(final CopyOperationRequest copyOperation) {
    final com.google.api.services.storage.model.StorageObject originObject = copyOperation.getSource().toProto();
    final Map<StorageRpcClient.StorageOption, ?> originOptions =
        optionMap(
            copyOperation.getSource().getGeneration(), null, copyOperation.getSourceOptions(), true);
    final com.google.api.services.storage.model.StorageObject destinationObject = copyOperation.getTarget().toProto();
    final Map<StorageRpcClient.StorageOption, ?> blobTargetOptions =
        optionMap(
            copyOperation.getTarget().getGeneration(),
            copyOperation.getTarget().getMetageneration(),
            copyOperation.getTargetOptions());
    try {
      RewriteOperationResult rewriteResult =
          runWithRetries(
              new Callable<StorageRpcClient.RewriteOperationResult>() {
                @Override
                public StorageRpcClient.RewriteOperationResult call() {
                  return storageClient.openRewrite(
                      new StorageRpcClient.RewriteOperationRequest(
                              originObject,
                              originOptions,
                          copyOperation.getOverrideInfo(),
                              destinationObject,
                              blobTargetOptions,
                          copyOperation.getMegabytesCopiedPerChunk()));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return new ChunkedCopyWriter(getOptions(), rewriteResult);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public byte[] readAllBytes(String containerName, String objectName, BlobSourceSettings... settings) {
    return readAllBytes(BlobIdentifier.create(containerName, objectName), settings);
  }

  @Override
  public byte[] readAllBytes(BlobIdentifier objectName, BlobSourceSettings... settings) {
    final com.google.api.services.storage.model.StorageObject storageItem = objectName.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(objectName, settings);
    try {
      return runWithRetries(
          new Callable<byte[]>() {
            @Override
            public byte[] call() {
              return storageClient.load(storageItem, storageOptionsMap);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageOperationBatch batch() {
    return new StorageOperationBatch(this.getOptions());
  }

  @Override
  public ReadChannel reader(String containerName, String objectName, BlobSourceSettings... settings) {
    Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
    return new BlobInputStream(getOptions(), BlobIdentifier.create(containerName, objectName), storageOptionsMap);
  }

  @Override
  public ReadChannel reader(BlobIdentifier objectName, BlobSourceSettings... settings) {
    Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(objectName, settings);
    return new BlobInputStream(getOptions(), objectName, storageOptionsMap);
  }

  @Override
  public BlobUploadChannel writer(BlobInfo blobMetadata, BlobWriteOptions... settings) {
    Tuple<BlobInfo, BlobUploadOption[]> blobTargetOptions = BlobUploadOption.convertOptions(blobMetadata, settings);
    return newWriter(blobTargetOptions.x(), blobTargetOptions.y());
  }

  @Override
  public BlobUploadChannel writer(URL signedUri) {
    return new BlobUploadChannel(getOptions(), signedUri);
  }

  private BlobUploadChannel newWriter(BlobInfo blobMetadata, BlobUploadOption... settings) {
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(blobMetadata, settings);
    return new BlobUploadChannel(getOptions(), blobMetadata, storageOptionsMap);
  }

  @Override
  public URL signUrl(BlobInfo blobMetadata, long expiry, TimeUnit timeScale, UrlSigningOption... settings) {
    EnumMap<UrlSigningOption.RequestOption, Object> optionMapping = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
    for (UrlSigningOption signingMode : settings) {
      optionMapping.put(signingMode.getOption(), signingMode.getValue());
    }

    boolean legacySigningEnabled =
        getPreferredSignatureVersion(optionMapping).equals(UrlSigningOption.SignatureSchemeVersion.V2);
    boolean enhancedSigningEnabled =
        getPreferredSignatureVersion(optionMapping).equals(UrlSigningOption.SignatureSchemeVersion.V4);

    ServiceAccountSigner serviceAccountSigner =
        (ServiceAccountSigner) optionMapping.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
    if (serviceAccountSigner == null) {
      checkState(
          this.getOptions().getCredentials() instanceof ServiceAccountSigner,
          "Signing key was not provided and could not be derived");
      serviceAccountSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
    }

    long expiryEpoch =
        enhancedSigningEnabled
            ? TimeUnit.SECONDS.convert(timeScale.toMillis(expiry), TimeUnit.MILLISECONDS)
            : TimeUnit.SECONDS.convert(
                getOptions().getClock().millisTime() + timeScale.toMillis(expiry),
                TimeUnit.MILLISECONDS);

    checkArgument(
        !(optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
            && optionMapping.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)
            && optionMapping.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)),
        "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be"
            + " specified.");

    String bucketId = getSlashlessBucketName(blobMetadata);
    String escapedObjectName = "";
    if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
      escapedObjectName = rfc3986UriEncode(blobMetadata.getName(), false);
    }

    boolean pathStyleEnabled = usePathStyleForSignedUrl(optionMapping);

    String xmlHost =
        pathStyleEnabled
            ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(optionMapping)
            : STORAGE_XML_SCHEME + "://" + bucketId + "." + getBaseStorageHostName(optionMapping);

    if (optionMapping.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      xmlHost = (String) optionMapping.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
    }

    String signedPath =
        pathStyleEnabled
            ? buildResourcePath(bucketId, escapedObjectName, optionMapping)
            : buildResourcePath("", escapedObjectName, optionMapping);

    URI resourceUri = URI.create(signedPath);
    // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
    // canonical resource string that we'll sign to include the bucket.
    URI signingUri =
        legacySigningEnabled ? URI.create(buildResourcePath(bucketId, escapedObjectName, optionMapping)) : resourceUri;

    try {
      SignatureMetadata signingMetadata =
          buildCanonicalSignature(
                  optionMapping, blobMetadata, expiryEpoch, signingUri, serviceAccountSigner.getAccount());
      String rawPayload = signingMetadata.buildUnsignedPayload();
      byte[] sigBytes = serviceAccountSigner.sign(rawPayload.getBytes(UTF_8));
      StringBuilder stringBuilder = new StringBuilder();
      stringBuilder.append(xmlHost).append(resourceUri);

      if (enhancedSigningEnabled) {
        BaseEncoding base64Encoder = BaseEncoding.base16().lowerCase();
        String encodedSignature = URLEncoder.encode(base64Encoder.encode(sigBytes), UTF_8.name());
        String signedQuery = signingMetadata.buildV4QueryString();

        stringBuilder.append('?');
        if (!Strings.isNullOrEmpty(signedQuery)) {
          stringBuilder.append(signedQuery).append('&');
        }
        stringBuilder.append("X-Goog-Signature=").append(encodedSignature);
      } else {
        BaseEncoding base64Encoder = BaseEncoding.base64();
        String encodedSignature = URLEncoder.encode(base64Encoder.encode(sigBytes), UTF_8.name());
        String legacyQuery = signingMetadata.buildV2QueryString();

        stringBuilder.append('?');
        if (!Strings.isNullOrEmpty(legacyQuery)) {
          stringBuilder.append(legacyQuery).append('&');
        }
        stringBuilder.append("GoogleAccessId=").append(serviceAccountSigner.getAccount());
        stringBuilder.append("&Expires=").append(expiryEpoch);
        stringBuilder.append("&Signature=").append(encodedSignature);
      }

      return new URL(stringBuilder.toString());

    } catch (MalformedURLException | UnsupportedEncodingException caughtException) {
      throw new IllegalStateException(caughtException);
    }
  }

  @Override
  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiry,
      TimeUnit timeScale,
      PostFieldsV4 formFields,
      PostConditionsV4 policyConditions,
      PostPolicyV4Parameter... settings) {
    EnumMap<UrlSigningOption.RequestOption, Object> optionMapping = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
    // Convert to a map of SignUrlOptions so we can re-use some utility methods
    for (PostPolicyV4Parameter signingMode : settings) {
      optionMapping.put(UrlSigningOption.RequestOption.valueOf(signingMode.getOption().name()), signingMode.getValue());
    }

    optionMapping.put(UrlSigningOption.RequestOption.SIGNATURE_VERSION, UrlSigningOption.SignatureSchemeVersion.V4);

    ServiceAccountSigner serviceAccountSigner =
        (ServiceAccountSigner) optionMapping.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
    if (serviceAccountSigner == null) {
      checkState(
          this.getOptions().getCredentials() instanceof ServiceAccountSigner,
          "Signing key was not provided and could not be derived");
      serviceAccountSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
    }

    checkArgument(
        !(optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
            && optionMapping.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)
            && optionMapping.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)),
        "Only one of VIRTUAL_HOSTED_STYLE, PATH_STYLE, or BUCKET_BOUND_HOST_NAME SignUrlOptions can be"
            + " specified.");

    String bucketId = getSlashlessBucketName(blobMetadata);

    boolean pathStyleEnabled = usePathStyleForSignedUrl(optionMapping);

    String endpointUrl;

    if (pathStyleEnabled) {
      endpointUrl = STORAGE_XML_SCHEME + "://" + STORAGE_XML_HOSTNAME + "/" + bucketId + "/";
    } else {
      endpointUrl = STORAGE_XML_SCHEME + "://" + bucketId + "." + STORAGE_XML_HOSTNAME + "/";
    }

    if (optionMapping.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      endpointUrl = optionMapping.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME) + "/";
    }

    SimpleDateFormat iso8601Formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
    SimpleDateFormat dateYmdFormat = new SimpleDateFormat("yyyyMMdd");
    SimpleDateFormat expiryFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    iso8601Formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    dateYmdFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    expiryFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));

    long nowMillis = getOptions().getClock().millisTime();
    String formattedDate = iso8601Formatter.format(nowMillis);
    String signerKey =
        serviceAccountSigner.getAccount()
            + "/"
            + dateYmdFormat.format(nowMillis)
            + "/auto/storage/goog4_request";

    Map<String, String> policyFieldMap = new HashMap<>();

    PostConditionsV4.Builder postConditionsBuilder = policyConditions.asBuilder();

    for (Map.Entry<String, String> mapPair : formFields.getFieldsMap().entrySet()) {
      // Every field needs a corresponding policy condition, so add them if they're missing
      postConditionsBuilder.addCondition(
          ConditionV4Type.MATCHES, mapPair.getKey(), mapPair.getValue());

      policyFieldMap.put(mapPair.getKey(), mapPair.getValue());
    }

    PostConditionsV4 postConditions =
        postConditionsBuilder
            .withBucketCondition(ConditionV4Type.MATCHES, blobMetadata.getBucket())
            .withKeyCondition(ConditionV4Type.MATCHES, blobMetadata.getName())
            .addCondition(ConditionV4Type.MATCHES, "x-goog-date", formattedDate)
            .addCondition(ConditionV4Type.MATCHES, "x-goog-credential", signerKey)
            .addCondition(ConditionV4Type.MATCHES, "x-goog-algorithm", "GOOG4-RSA-SHA256")
            .buildInstance();
    PostPolicyV4Document postPolicy =
        PostPolicyV4Document.create(
            expiryFormatter.format(nowMillis + timeScale.toMillis(expiry)), postConditions);
    String encodedPolicy = BaseEncoding.base64().encode(postPolicy.toJsonString().getBytes());
    String encodedSignature =
        BaseEncoding.base16().encode(serviceAccountSigner.sign(encodedPolicy.getBytes())).toLowerCase();

    for (PostPolicyV4.ConditionV4 postCondition : postConditions.getConditions()) {
      if (postCondition.conditionCategory == ConditionV4Type.MATCHES) {
        policyFieldMap.put(postCondition.leftOperand, postCondition.rightOperand);
      }
    }
    policyFieldMap.put("key", blobMetadata.getName());
    policyFieldMap.put("x-goog-credential", signerKey);
    policyFieldMap.put("x-goog-algorithm", "GOOG4-RSA-SHA256");
    policyFieldMap.put("x-goog-date", formattedDate);
    policyFieldMap.put("x-goog-signature", encodedSignature);
    policyFieldMap.put("policy", encodedPolicy);

    policyFieldMap.remove("bucket");

    return PostPolicyV4.create(endpointUrl, policyFieldMap);
  }

  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiry,
      TimeUnit timeScale,
      PostFieldsV4 formFields,
      PostPolicyV4Parameter... settings) {
    return generateSignedPostPolicyV4(
            blobMetadata, expiry, timeScale, formFields, PostConditionsV4.builder().buildInstance(), settings);
  }

  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiry,
      TimeUnit timeScale,
      PostConditionsV4 postConditions,
      PostPolicyV4Parameter... settings) {
    return generateSignedPostPolicyV4(
            blobMetadata, expiry, timeScale, PostFieldsV4.builder().buildInstance(), postConditions, settings);
  }

  public PostPolicyV4 generateSignedPostPolicyV4(
          BlobInfo blobMetadata, long expiry, TimeUnit timeScale, PostPolicyV4Parameter... settings) {
    return generateSignedPostPolicyV4(
            blobMetadata, expiry, timeScale, PostFieldsV4.builder().buildInstance(), settings);
  }

  private String buildResourcePath(
      String bucketNameNoSlash,
      String escapedObjectName,
      EnumMap<UrlSigningOption.RequestOption, Object> optionMapping) {
    if (Strings.isNullOrEmpty(bucketNameNoSlash)) {
      if (Strings.isNullOrEmpty(escapedObjectName)) {
        return PATH_SEPARATOR;
      }
      if (escapedObjectName.startsWith(PATH_SEPARATOR)) {
        return escapedObjectName;
      }
      return PATH_SEPARATOR + escapedObjectName;
    }

    StringBuilder resourcePathBuilder = new StringBuilder();
    resourcePathBuilder.append(PATH_SEPARATOR).append(bucketNameNoSlash);
    if (Strings.isNullOrEmpty(escapedObjectName)) {
      boolean legacySigningEnabled =
          getPreferredSignatureVersion(optionMapping).equals(UrlSigningOption.SignatureSchemeVersion.V2);
      // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
      // must end with a forward slash.
      if (optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && legacySigningEnabled) {
        resourcePathBuilder.append(PATH_SEPARATOR);
      }
      return resourcePathBuilder.toString();
    }
    resourcePathBuilder.append(PATH_SEPARATOR);
    resourcePathBuilder.append(escapedObjectName);
    return resourcePathBuilder.toString();
  }

  private UrlSigningOption.SignatureSchemeVersion getPreferredSignatureVersion(
      EnumMap<UrlSigningOption.RequestOption, Object> optionMapping) {
    // Check for an explicitly specified version in the map.
    for (UrlSigningOption.SignatureSchemeVersion signatureVersion : UrlSigningOption.SignatureSchemeVersion.values()) {
      if (signatureVersion.equals(optionMapping.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION))) {
        return signatureVersion;
      }
    }
    // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
    // Change this to V4 once we make it the default.
    return UrlSigningOption.SignatureSchemeVersion.V2;
  }

  private boolean usePathStyleForSignedUrl(EnumMap<UrlSigningOption.RequestOption, Object> optionMapping) {
    // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
    // logic to return false unless PATH_STYLE was explicitly specified.
    if (optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
        || optionMapping.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
      return false;
    }
    return true;
  }

  /**
   * Builds signature info.
   *
   * @param optionMapping the option map
   * @param blobMetadata the blob info
   * @param expiryEpoch the expiration in seconds
   * @param resourceUri the resource URI
   * @param serviceAccountEmail the account email
   * @return signature info
   */
  private SignatureMetadata buildCanonicalSignature(
      Map<UrlSigningOption.RequestOption, Object> optionMapping,
      BlobInfo blobMetadata,
      long expiryEpoch,
      URI resourceUri,
      String serviceAccountEmail) {

    HttpRequestMethod httpMethod =
        optionMapping.containsKey(UrlSigningOption.RequestOption.HTTP_METHOD)
            ? (HttpRequestMethod) optionMapping.get(UrlSigningOption.RequestOption.HTTP_METHOD)
            : HttpRequestMethod.GET;

    SignatureMetadata.CanonicalStringBuilder signatureStringBuilder =
        new SignatureMetadata.CanonicalStringBuilder(httpMethod, expiryEpoch, resourceUri);

    if (firstNonNull((Boolean) optionMapping.get(UrlSigningOption.RequestOption.MD5), false)) {
      checkArgument(blobMetadata.getMd5() != null, "Blob is missing a value for md5");
      signatureStringBuilder.setContentMd5(blobMetadata.getMd5());
    }

    if (firstNonNull((Boolean) optionMapping.get(UrlSigningOption.RequestOption.CONTENT_TYPE), false)) {
      checkArgument(blobMetadata.getContentType() != null, "Blob is missing a value for content-type");
      signatureStringBuilder.setContentType(blobMetadata.getContentType());
    }

    signatureStringBuilder.setSignatureVersion(
        (UrlSigningOption.SignatureSchemeVersion) optionMapping.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));

    signatureStringBuilder.setAccountEmail(serviceAccountEmail);

    signatureStringBuilder.setTimestamp(getOptions().getClock().millisTime());

    ImmutableMap.Builder<String, String> extensionHeadersBuilder =
        new ImmutableMap.Builder<String, String>();

    boolean enhancedSigningEnabled =
        UrlSigningOption.SignatureSchemeVersion.V4.equals(
            optionMapping.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
    if (enhancedSigningEnabled) { // We don't sign the host header for V2 signed URLs; only do this for V4.
      // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
      if (optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
        extensionHeadersBuilder.put(
            "host",
            getSlashlessBucketName(blobMetadata) + "." + getBaseStorageHostName(optionMapping));
      } else if (optionMapping.containsKey(UrlSigningOption.RequestOption.HOST_NAME)
          || optionMapping.containsKey(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME)) {
        extensionHeadersBuilder.put("host", getBaseStorageHostName(optionMapping));
      }
    }

    if (optionMapping.containsKey(UrlSigningOption.RequestOption.EXT_HEADERS)) {
      extensionHeadersBuilder.putAll(
          (Map<String, String>) optionMapping.get(UrlSigningOption.RequestOption.EXT_HEADERS));
    }

    ImmutableMap.Builder<String, String> paramsBuilder =
        new ImmutableMap.Builder<String, String>();
    if (optionMapping.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
      paramsBuilder.putAll(
          (Map<String, String>) optionMapping.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
    }

    return signatureStringBuilder
        .setCanonicalizedExtensionHeaders((Map<String, String>) extensionHeadersBuilder.build())
        .setCanonicalizedQueryParams((Map<String, String>) paramsBuilder.build())
        .buildCanonicalString();
  }

  private String getSlashlessBucketName(BlobInfo blobMetadata) {
    // The bucket name itself should never contain a forward slash. However, parts already existed
    // in the code to check for this, so we remove the forward slashes to be safe here.
    return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
  }

  /** Returns the hostname used to send requests to Cloud Storage, e.g. "storage.googleapis.com". */
  private String getBaseStorageHostName(Map<UrlSigningOption.RequestOption, Object> optionMapping) {
    String explicitBaseHost = (String) optionMapping.get(UrlSigningOption.RequestOption.HOST_NAME);
    String bucketScopedHostName =
        (String) optionMapping.get(UrlSigningOption.RequestOption.BUCKET_BOUND_HOST_NAME);
    if (!Strings.isNullOrEmpty(explicitBaseHost)) {
      return explicitBaseHost.replaceFirst("http(s)?://", "");
    }
    if (!Strings.isNullOrEmpty(bucketScopedHostName)) {
      return bucketScopedHostName.replaceFirst("http(s)?://", "");
    }
    return STORAGE_XML_HOSTNAME;
  }

  @Override
  public List<StorageObject> get(BlobIdentifier... blobIdentifiers) {
    return get(Arrays.asList(blobIdentifiers));
  }

  @Override
  public List<StorageObject> get(Iterable<BlobIdentifier> blobIdentifiers) {
    StorageOperationBatch operationBatch = batch();
    final List<StorageObject> retrievedObjects = Lists.newArrayList();
    for (BlobIdentifier objectName : blobIdentifiers) {
      operationBatch
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
    operationBatch.execute();
    return Collections.unmodifiableList(retrievedObjects);
  }

  @Override
  public List<StorageObject> update(BlobInfo... blobMetadataArray) {
    return update(Arrays.asList(blobMetadataArray));
  }

  @Override
  public List<StorageObject> update(Iterable<BlobInfo> blobMetadataArray) {
    StorageOperationBatch operationBatch = batch();
    final List<StorageObject> retrievedObjects = Lists.newArrayList();
    for (BlobInfo blobMetadata : blobMetadataArray) {
      operationBatch
          .patch(blobMetadata)
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
    operationBatch.execute();
    return Collections.unmodifiableList(retrievedObjects);
  }

  @Override
  public List<Boolean> delete(BlobIdentifier... blobIdentifiers) {
    return delete(Arrays.asList(blobIdentifiers));
  }

  @Override
  public List<Boolean> delete(Iterable<BlobIdentifier> blobIdentifiers) {
    StorageOperationBatch operationBatch = batch();
    final List<Boolean> retrievedObjects = Lists.newArrayList();
    for (BlobIdentifier objectName : blobIdentifiers) {
      operationBatch
          .deleteBlob(objectName)
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
    operationBatch.execute();
    return Collections.unmodifiableList(retrievedObjects);
  }

  @Override
  public AclEntry getAcl(final String containerName, final BaseEntity entityInfo, BucketSourceRequestOption... settings) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
      BucketAccessControl resultBucket =
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return storageClient.getAcl(containerName, entityInfo.toProto(), storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return resultBucket == null ? null : AclEntry.fromProto(resultBucket);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public AclEntry getAcl(final String containerName, final BaseEntity entityInfo) {
    return getAcl(containerName, entityInfo, new BucketSourceRequestOption[0]);
  }

  @Override
  public boolean deleteAcl(
          final String containerName, final BaseEntity entityInfo, BucketSourceRequestOption... settings) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return storageClient.deleteAcl(containerName, entityInfo.toProto(), storageOptionsMap);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public boolean deleteAcl(final String containerName, final BaseEntity entityInfo) {
    return deleteAcl(containerName, entityInfo, new BucketSourceRequestOption[0]);
  }

  @Override
  public AclEntry createAcl(String containerName, AclEntry accessEntry, BucketSourceRequestOption... settings) {
    final BucketAccessControl accessControlProto = accessEntry.toBucketProto().setBucket(containerName);
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
      return AclEntry.fromProto(
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return storageClient.createAcl(accessControlProto, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public AclEntry createAcl(String containerName, AclEntry accessEntry) {
    return createAcl(containerName, accessEntry, new BucketSourceRequestOption[0]);
  }

  @Override
  public AclEntry updateAcl(String containerName, AclEntry accessEntry, BucketSourceRequestOption... settings) {
    final BucketAccessControl accessControlProto = accessEntry.toBucketProto().setBucket(containerName);
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
      return AclEntry.fromProto(
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return storageClient.patchAcl(accessControlProto, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public AclEntry updateAcl(String containerName, AclEntry accessEntry) {
    return updateAcl(containerName, accessEntry, new BucketSourceRequestOption[0]);
  }

  @Override
  public List<AclEntry> listAcls(final String containerName, BucketSourceRequestOption... settings) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
      List<BucketAccessControl> resultBucket =
          runWithRetries(
              new Callable<List<BucketAccessControl>>() {
                @Override
                public List<BucketAccessControl> call() {
                  return storageClient.listAcls(containerName, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(resultBucket, AclEntry.BUCKET_PB_TO_ACL_ENTRY_FN);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public List<AclEntry> listAcls(final String containerName) {
    return listAcls(containerName, new BucketSourceRequestOption[0]);
  }

  @Override
  public AclEntry getDefaultAcl(final String containerName, final BaseEntity entityInfo) {
    try {
      ObjectAccessControl resultBucket =
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return storageClient.getDefaultAcl(containerName, entityInfo.toProto());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return resultBucket == null ? null : AclEntry.fromProto(resultBucket);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public boolean deleteDefaultAcl(final String containerName, final BaseEntity entityInfo) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return storageClient.deleteDefaultAcl(containerName, entityInfo.toProto());
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public AclEntry createDefaultAcl(String containerName, AclEntry accessEntry) {
    final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(containerName);
    try {
      return AclEntry.fromProto(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return storageClient.createDefaultAcl(accessControlProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public AclEntry updateDefaultAcl(String containerName, AclEntry accessEntry) {
    final ObjectAccessControl accessControlProto = accessEntry.toObjectProto().setBucket(containerName);
    try {
      return AclEntry.fromProto(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return storageClient.patchDefaultAcl(accessControlProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public List<AclEntry> listDefaultAcls(final String containerName) {
    try {
      List<ObjectAccessControl> resultBucket =
          runWithRetries(
              new Callable<List<ObjectAccessControl>>() {
                @Override
                public List<ObjectAccessControl> call() {
                  return storageClient.listDefaultAcls(containerName);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(resultBucket, AclEntry.OBJECT_PB_TO_ACL_ENTRY_FN);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public AclEntry getAcl(final BlobIdentifier objectName, final AclEntry.BaseEntity entityInfo) {
    try {
      ObjectAccessControl resultBucket =
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return storageClient.getAcl(
                      objectName.getBucket(), objectName.getName(), objectName.getGeneration(), entityInfo.toProto());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return resultBucket == null ? null : AclEntry.fromProto(resultBucket);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public boolean deleteAcl(final BlobIdentifier objectName, final BaseEntity entityInfo) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return storageClient.deleteAcl(
                  objectName.getBucket(), objectName.getName(), objectName.getGeneration(), entityInfo.toProto());
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public AclEntry createAcl(final BlobIdentifier objectName, final AclEntry accessEntry) {
    final ObjectAccessControl accessControlProto =
        accessEntry.toObjectProto()
            .setBucket(objectName.getBucket())
            .setObject(objectName.getName())
            .setGeneration(objectName.getGeneration());
    try {
      return AclEntry.fromProto(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return storageClient.createAcl(accessControlProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public AclEntry updateAcl(BlobIdentifier objectName, AclEntry accessEntry) {
    final ObjectAccessControl accessControlProto =
        accessEntry.toObjectProto()
            .setBucket(objectName.getBucket())
            .setObject(objectName.getName())
            .setGeneration(objectName.getGeneration());
    try {
      return AclEntry.fromProto(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return storageClient.patchAcl(accessControlProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public List<AclEntry> listAcls(final BlobIdentifier objectName) {
    try {
      List<ObjectAccessControl> resultBucket =
          runWithRetries(
              new Callable<List<ObjectAccessControl>>() {
                @Override
                public List<ObjectAccessControl> call() {
                  return storageClient.listAcls(
                      objectName.getBucket(), objectName.getName(), objectName.getGeneration());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(resultBucket, AclEntry.OBJECT_PB_TO_ACL_ENTRY_FN);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  public HmacSecretKey createHmacKey(
          final ServiceAccountInfo accountInfo, final HmacKeyCreationOption... settings) {
    try {
      return HmacSecretKey.fromProto(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKey>() {
                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                  return storageClient.createHmacKey(accountInfo.getEmail(), createOptionMap(settings));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public Page<HmacKeyInfo> listHmacKeys(ListHmacKeysOptions... settings) {
    return listAllHmacKeys(getOptions(), createOptionMap(settings));
  }

  @Override
  public HmacSecretKey.HmacKeyInfo getHmacKey(final String hmacKeyId, final GetHmacKeyOptions... settings) {
    try {
      return HmacKeyInfo.fromProto(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {
                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                  return storageClient.getHmacKey(hmacKeyId, createOptionMap(settings));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  private HmacKeyInfo updateHmacKeyMetadata(
          final HmacKeyInfo keyInfo, final UpdateHmacKeyOptions... settings) {
    try {
      return HmacKeyInfo.fromProto(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {
                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                  return storageClient.updateHmacKey(keyInfo.toProto(), createOptionMap(settings));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public HmacSecretKey.HmacKeyInfo updateHmacKeyState(
      final HmacKeyInfo keyInfo,
      final HmacSecretKey.HmacKeyStatus keyStatus,
      final UpdateHmacKeyOptions... settings) {
    HmacSecretKey.HmacKeyInfo newMetadata =
        HmacSecretKey.HmacKeyInfo.newHmacKeyBuilder(keyInfo.getServiceAccount())
            .setProjectId(keyInfo.getProjectId())
            .setAccessId(keyInfo.getAccessId())
            .setState(keyStatus)
            .buildHmacKeyInfo();
    return updateHmacKeyMetadata(newMetadata, settings);
  }

  @Override
  public void deleteHmacKey(final HmacKeyInfo keyInfo, final DeleteHmacKeyOptions... settings) {
    try {
      runWithRetries(
          new Callable<Void>() {
            @Override
            public Void call() {
              storageClient.deleteHmacKey(keyInfo.toProto(), createOptionMap(settings));
              return null;
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  private static Page<HmacSecretKey.HmacKeyInfo> listAllHmacKeys(
          final StorageSettings storageSettings, final Map<StorageRpcClient.StorageOption, ?> settings) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> bucketsPage =
          runWithRetries(
              new Callable<
                  Tuple<
                      String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {
                @Override
                public Tuple<
                        String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>
                    call() {
                  return storageSettings.getStorageRpcV1().listHmacKeys(settings);
                }
              },
              storageSettings.getRetrySettings(),
              EXCEPTION_HANDLER,
              storageSettings.getClock());
      String pageToken = bucketsPage.x();
      final Iterable<HmacKeyInfo> keyInfo =
          bucketsPage.y() == null
              ? ImmutableList.<HmacKeyInfo>of()
              : Iterables.transform(
                  bucketsPage.y(),
                  new Function<
                      com.google.api.services.storage.model.HmacKeyMetadata, HmacKeyInfo>() {
                    @Override
                    public HmacSecretKey.HmacKeyInfo apply(
                        com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                      return HmacKeyInfo.fromProto(metadataPb);
                    }
                  });
      return new PageImpl<>(
          new HmacKeyMetadataPaginator(storageSettings, settings), pageToken, keyInfo);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public Policy getIamPolicy(final String containerName, BucketSourceRequestOption... settings) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
      return fromApiPolicy(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Policy>() {
                @Override
                public com.google.api.services.storage.model.Policy call() {
                  return storageClient.getIamPolicy(containerName, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public Policy setIamPolicy(
          final String containerName, final Policy encodedPolicy, BucketSourceRequestOption... settings) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
      return fromApiPolicy(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Policy>() {
                @Override
                public com.google.api.services.storage.model.Policy call() {
                  return storageClient.setIamPolicy(containerName, toApiPolicy(encodedPolicy), storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public List<Boolean> testIamPermissions(
          final String containerName, final List<String> requestedActions, BucketSourceRequestOption... settings) {
    try {
      final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(settings);
      TestIamPermissionsResponse iamResult =
          runWithRetries(
              new Callable<TestIamPermissionsResponse>() {
                @Override
                public TestIamPermissionsResponse call() {
                  return storageClient.testIamPermissions(containerName, requestedActions, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      final Set<String> grantedActions =
          iamResult.getPermissions() != null
              ? ImmutableSet.copyOf(iamResult.getPermissions())
              : ImmutableSet.<String>of();
      return Lists.transform(
              requestedActions,
          new Function<String, Boolean>() {
            @Override
            public Boolean apply(String permission) {
              return grantedActions.contains(permission);
            }
          });
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public StorageBucket lockRetentionPolicy(BucketMetadata bucketMeta, BucketTargetOptions... settings) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMeta.toProto();
    final Map<StorageRpcClient.StorageOption, ?> storageOptionsMap = createOptionMap(bucketMeta, settings);
    try {
      return StorageBucket.fromProto(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return storageClient.lockRetentionPolicy(bucketProto, storageOptionsMap);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  @Override
  public ServiceAccountInfo getServiceAccount(final String projectIdentifier) {
    try {
      com.google.api.services.storage.model.ServiceAccount resultBucket =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.ServiceAccount>() {
                @Override
                public com.google.api.services.storage.model.ServiceAccount call() {
                  return storageClient.getServiceAccount(projectIdentifier);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return resultBucket == null ? null : ServiceAccountInfo.fromProto(resultBucket);
    } catch (RetryHelperException retryEx) {
      throw StorageServiceException.translateAndRethrow(retryEx);
    }
  }

  private static <T> void putOptionIfAbsent(
          StorageRpcClient.StorageOption signingMode, T fallbackValue, Map<StorageRpcClient.StorageOption, Object> optionMap) {
    putOptionIfAbsent(signingMode, signingMode, fallbackValue, optionMap);
  }

  private static <T> void putOptionIfAbsent(
      StorageRpcClient.StorageOption requestedOption,
      StorageRpcClient.StorageOption insertOption,
      T fallbackValue,
      Map<StorageRpcClient.StorageOption, Object> optionMap) {
    if (optionMap.containsKey(requestedOption)) {
      @SuppressWarnings("unchecked")
      T resolvedValue = (T) optionMap.remove(requestedOption);
      checkArgument(
          resolvedValue != null || fallbackValue != null,
          "Option " + requestedOption.getValue() + " is missing a value");
      resolvedValue = firstNonNull(resolvedValue, fallbackValue);
      optionMap.put(insertOption, resolvedValue);
    }
  }

  private static Map<StorageRpcClient.StorageOption, ?> createOptionMap(
          Long expectedGeneration, Long expectedMetaGeneration, Iterable<? extends AbstractOption> settings) {
    return createOptionMap(expectedGeneration, expectedMetaGeneration, settings, false);
  }

  private static Map<StorageRpcClient.StorageOption, ?> createOptionMap(
      Long expectedGeneration,
      Long expectedMetaGeneration,
      Iterable<? extends AbstractOption> settings,
      boolean treatAsSource) {
    Map<StorageRpcClient.StorageOption, Object> interimMap = Maps.newEnumMap(StorageRpcClient.StorageOption.class);
    for (AbstractOption signingMode : settings) {
      Object previousValue = interimMap.put(signingMode.getRpcOption(), signingMode.getValue());
      checkArgument(previousValue == null, "Duplicate option %s", signingMode);
    }
    if (Boolean.TRUE.equals(interimMap.get(DELIMITER))) {
      interimMap.remove(DELIMITER);
      interimMap.put(DELIMITER, PATH_SEPARATOR);
    } else if (null != interimMap.get(DELIMITER)) {
      interimMap.put(DELIMITER, interimMap.get(DELIMITER));
    }
    if (treatAsSource) {
      putOptionIfAbsent(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, expectedGeneration, interimMap);
      putOptionIfAbsent(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, expectedGeneration, interimMap);
      putOptionIfAbsent(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, expectedMetaGeneration, interimMap);
      putOptionIfAbsent(
          IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, expectedMetaGeneration, interimMap);
    } else {
      putOptionIfAbsent(IF_GENERATION_MATCH, expectedGeneration, interimMap);
      putOptionIfAbsent(IF_GENERATION_NOT_MATCH, expectedGeneration, interimMap);
      putOptionIfAbsent(IF_METAGENERATION_MATCH, expectedMetaGeneration, interimMap);
      putOptionIfAbsent(IF_METAGENERATION_NOT_MATCH, expectedMetaGeneration, interimMap);
    }
    return ImmutableMap.copyOf(interimMap);
  }

  private static Map<StorageRpcClient.StorageOption, ?> createOptionMap(AbstractOption... settings) {
    return optionMap(null, null, Arrays.asList(settings));
  }

  private static Map<StorageRpcClient.StorageOption, ?> createOptionMap(
          Long expectedGeneration, Long expectedMetaGeneration, AbstractOption... settings) {
    return optionMap(expectedGeneration, expectedMetaGeneration, Arrays.asList(settings));
  }

  private static Map<StorageRpcClient.StorageOption, ?> createOptionMap(BucketMetadata bucketMeta, AbstractOption... settings) {
    return createOptionMap(null, bucketMeta.getMetageneration(), settings);
  }

  static Map<StorageRpcClient.StorageOption, ?> createOptionMap(BlobInfo blobMetadata, AbstractOption... settings) {
    return createOptionMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), settings);
  }

  static Map<StorageRpcClient.StorageOption, ?> createOptionMap(BlobIdentifier blobIdentifier, AbstractOption... settings) {
    return createOptionMap(blobIdentifier.getGeneration(), null, settings);
  }
}
