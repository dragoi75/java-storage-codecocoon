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
import static com.google.cloud.storage.PolicyHelper.convertFromApiPolicy;
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
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.HmacKey.HmacKeyMetadata;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.cloud.storage.spi.v1.StorageRpc.RewriteResponse;
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

final class DefaultStorage extends BaseService<StorageOptions> implements StorageClient {

  private static final byte[] ZERO_LENGTH_BYTE_ARRAY = {};
  private static final String MD5_OF_ZERO_BYTES = "1B2M2Y8AsgTpgAmY7PhCfg==";
  private static final String CRC32C_OF_ZERO_BYTES = "AAAAAA==";
  private static final String PATH_SEPARATOR = "/";
  /** Signed URLs are only supported through the GCS XML API endpoint. */
  private static final String STORAGE_XML_SCHEME = "https";

  private static final String STORAGE_XML_HOST = "storage.googleapis.com";

  private static final Function<Tuple<StorageClient, Boolean>, Boolean> DELETION_FUNCTION =
      new Function<Tuple<StorageClient, Boolean>, Boolean>() {
        @Override
        public Boolean apply(Tuple<StorageClient, Boolean> tuple) {
          return tuple.y();
        }
      };

  private final StorageRpc rpcClient;

  DefaultStorage(StorageOptions opts) {
    super(opts);
    rpcClient = opts.getStorageRpcV1();
  }

  @Override
  public Bucket create(BucketInfo bucketMetadata, BucketTargetOptions... opts) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(bucketMetadata, opts);
    try {
      return Bucket.fromPb(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return rpcClient.create(bucketProto, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Blob create(BlobInfo blobMetadata, BlobUploadOption... opts) {
    BlobInfo updatedBlob =
        blobMetadata
            .toBuilder()
            .setMd5(MD5_OF_ZERO_BYTES)
            .setCrc32c(CRC32C_OF_ZERO_BYTES)
            .build();
    return createInternal(updatedBlob, ZERO_LENGTH_BYTE_ARRAY, opts);
  }

  @Override
  public Blob create(BlobInfo blobMetadata, byte[] data, BlobUploadOption... opts) {
    data = firstNonNull(data, ZERO_LENGTH_BYTE_ARRAY);
    BlobInfo updatedBlob =
        blobMetadata
            .toBuilder()
            .setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(data).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(Ints.toByteArray(Hashing.crc32c().hashBytes(data).asInt())))
            .build();
    return createInternal(updatedBlob, data, opts);
  }

  @Override
  public Blob create(
          BlobInfo blobMetadata, byte[] data, int startOffset, int dataLength, BlobUploadOption... opts) {
    data = firstNonNull(data, ZERO_LENGTH_BYTE_ARRAY);
    byte[] chunk = Arrays.copyOfRange(data, startOffset, startOffset + dataLength);
    BlobInfo updatedBlob =
        blobMetadata
            .toBuilder()
            .setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(chunk).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(Ints.toByteArray(Hashing.crc32c().hashBytes(chunk).asInt())))
            .build();
    return createInternal(updatedBlob, chunk, opts);
  }

  @Override
  @Deprecated
  public Blob create(BlobInfo blobMetadata, InputStream data, WriteBlobOption... opts) {
    Tuple<BlobInfo, BlobUploadOption[]> targetParams = BlobUploadOption.convertOptions(blobMetadata, opts);
    StorageObject storageObjectProto = targetParams.x().toPb();
    Map<StorageRpc.Option, ?> optionMapping = getOptionMap(targetParams.x(), targetParams.y());
    InputStream inputStream =
        firstNonNull(data, new ByteArrayInputStream(ZERO_LENGTH_BYTE_ARRAY));
    // retries are not safe when the input is an InputStream, so we can't retry.
    return Blob.fromPb(this, rpcClient.create(storageObjectProto, inputStream, optionMapping));
  }

  private Blob createInternal(BlobInfo blobInfoParam, final byte[] data, BlobUploadOption... opts) {
    Preconditions.checkNotNull(data);
    final StorageObject storageObjectProto = blobInfoParam.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobInfoParam, opts);
    try {
      return Blob.fromPb(
          this,
          runWithRetries(
              new Callable<StorageObject>() {
                @Override
                public StorageObject call() {
                  return rpcClient.create(storageObjectProto, new ByteArrayInputStream(data), optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Bucket get(String bucketName, BucketGetOptions... opts) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.of(bucketName).toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
    try {
      com.google.api.services.storage.model.Bucket bucketAnswer =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return rpcClient.get(bucketProto, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return bucketAnswer == null ? null : Bucket.fromPb(this, bucketAnswer);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Blob get(String bucketName, String objectName, BlobGetOptions... opts) {
    return get(BlobId.of(bucketName, objectName), opts);
  }

  @Override
  public Blob get(BlobId objectName, BlobGetOptions... opts) {
    final StorageObject persistedObject = objectName.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(objectName, opts);
    try {
      StorageObject storageEntity =
          runWithRetries(
              new Callable<StorageObject>() {
                @Override
                public StorageObject call() {
                  return rpcClient.get(persistedObject, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return storageEntity == null ? null : Blob.fromPb(this, storageEntity);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Blob get(BlobId objectName) {
    return get(objectName, new BlobGetOptions[0]);
  }

  private static class BucketPageRetriever implements NextPageFetcher<Bucket> {

    private static final long SERIAL_UID = 5850406828803613729L;
    private final Map<StorageRpc.Option, ?> requestParams;
    private final StorageOptions serviceSettings;

    BucketPageRetriever(
            StorageOptions serviceSettings, String cursorToken, Map<StorageRpc.Option, ?> optionMapping) {
      this.requestParams =
          PageImpl.nextRequestOptions(StorageRpc.Option.PAGE_TOKEN, cursorToken, optionMapping);
      this.serviceSettings = serviceSettings;
    }

    @Override
    public Page<Bucket> getNextPage() {
      return listAllBuckets(serviceSettings, requestParams);
    }
  }

  private static class BlobPageIterator implements NextPageFetcher<Blob> {

    private static final long SERIAL_UID = 81807334445874098L;
    private final Map<StorageRpc.Option, ?> requestParams;
    private final StorageOptions serviceSettings;
    private final String bucketName;

    BlobPageIterator(
        String bucketName,
        StorageOptions serviceSettings,
        String cursorToken,
        Map<StorageRpc.Option, ?> optionMapping) {
      this.requestParams =
          PageImpl.nextRequestOptions(StorageRpc.Option.PAGE_TOKEN, cursorToken, optionMapping);
      this.serviceSettings = serviceSettings;
      this.bucketName = bucketName;
    }

    @Override
    public Page<Blob> getNextPage() {
      return listBlobsInBucket(bucketName, serviceSettings, requestParams);
    }
  }

  private static class HmacKeyMetadataPageRetriever implements NextPageFetcher<HmacKeyMetadata> {

    private static final long SERIAL_UID = 308012320541700881L;
    private final StorageOptions serviceSettings;
    private final Map<StorageRpc.Option, ?> opts;

    HmacKeyMetadataPageRetriever(StorageOptions serviceSettings, Map<StorageRpc.Option, ?> opts) {
      this.serviceSettings = serviceSettings;
      this.opts = opts;
    }

    @Override
    public Page<HmacKeyMetadata> getNextPage() {
      return listHmacKeyMetadata(serviceSettings, opts);
    }
  }

  @Override
  public Page<Bucket> list(BucketListOptions... opts) {
    return listAllBuckets(getOptions(), getOptionMap(opts));
  }

  @Override
  public Page<Blob> list(final String bucketName, BlobListOptions... opts) {
    return listBlobsInBucket(bucketName, getOptions(), getOptionMap(opts));
  }

  private static Page<Bucket> listAllBuckets(
          final StorageOptions serviceSettings, final Map<StorageRpc.Option, ?> optionMapping) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> resultTuple =
          runWithRetries(
              new Callable<
                  Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {
                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>
                    call() {
                  return serviceSettings.getStorageRpcV1().list(optionMapping);
                }
              },
              serviceSettings.getRetrySettings(),
              EXCEPTION_HANDLER,
              serviceSettings.getClock());
      String cursorToken = resultTuple.x();
      Iterable<Bucket> bucketList =
          resultTuple.y() == null
              ? ImmutableList.<Bucket>of()
              : Iterables.transform(
                  resultTuple.y(),
                  new Function<com.google.api.services.storage.model.Bucket, Bucket>() {
                    @Override
                    public Bucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                      return Bucket.fromPb(serviceSettings.getService(), bucketPb);
                    }
                  });
      return new PageImpl<>(
          new BucketPageRetriever(serviceSettings, cursorToken, optionMapping), cursorToken, bucketList);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  private static Page<Blob> listBlobsInBucket(
      final String bucketName,
      final StorageOptions serviceSettings,
      final Map<StorageRpc.Option, ?> optionMapping) {
    try {
      Tuple<String, Iterable<StorageObject>> resultTuple =
          runWithRetries(
              new Callable<Tuple<String, Iterable<StorageObject>>>() {
                @Override
                public Tuple<String, Iterable<StorageObject>> call() {
                  return serviceSettings.getStorageRpcV1().list(bucketName, optionMapping);
                }
              },
              serviceSettings.getRetrySettings(),
              EXCEPTION_HANDLER,
              serviceSettings.getClock());
      String cursorToken = resultTuple.x();
      Iterable<Blob> blobList =
          resultTuple.y() == null
              ? ImmutableList.<Blob>of()
              : Iterables.transform(
                  resultTuple.y(),
                  new Function<StorageObject, Blob>() {
                    @Override
                    public Blob apply(StorageObject storageObject) {
                      return Blob.fromPb(serviceSettings.getService(), storageObject);
                    }
                  });
      return new PageImpl<>(
          new BlobPageIterator(bucketName, serviceSettings, cursorToken, optionMapping), cursorToken, blobList);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Bucket update(BucketInfo bucketMetadata, BucketTargetOptions... opts) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(bucketMetadata, opts);
    try {
      return Bucket.fromPb(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return rpcClient.patch(bucketProto, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Blob update(BlobInfo blobMetadata, BlobUploadOption... opts) {
    final StorageObject storageEntity = blobMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobMetadata, opts);
    try {
      return Blob.fromPb(
          this,
          runWithRetries(
              new Callable<StorageObject>() {
                @Override
                public StorageObject call() {
                  return rpcClient.patch(storageEntity, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Blob update(BlobInfo blobMetadata) {
    return update(blobMetadata, new BlobUploadOption[0]);
  }

  @Override
  public boolean delete(String bucketName, BucketSourceOptions... opts) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.of(bucketName).toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.delete(bucketProto, optionMapping);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public boolean delete(String bucketName, String objectName, BlobSourceOptions... opts) {
    return delete(BlobId.of(bucketName, objectName), opts);
  }

  @Override
  public boolean delete(BlobId objectName, BlobSourceOptions... opts) {
    final StorageObject storageEntity = objectName.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(objectName, opts);
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.delete(storageEntity, optionMapping);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public boolean delete(BlobId objectName) {
    return delete(objectName, new BlobSourceOptions[0]);
  }

  @Override
  public Blob compose(final ComposeObjectsRequest composeReq) {
    final List<StorageObject> sourceList =
        Lists.newArrayListWithCapacity(composeReq.getSourceBlobs().size());
    for (ComposeObjectsRequest.SourceObject sourceObject : composeReq.getSourceBlobs()) {
      sourceList.add(
          BlobInfo.newBuilder(
                  BlobId.of(
                      composeReq.getTarget().getBucket(),
                      sourceObject.getName(),
                      sourceObject.getGeneration()))
              .build()
              .toPb());
    }
    final StorageObject targetObject = composeReq.getTarget().toPb();
    final Map<StorageRpc.Option, ?> targetParams =
        getOptionMap(
            composeReq.getTarget().getGeneration(),
            composeReq.getTarget().getMetageneration(),
            composeReq.getTargetOptions());
    try {
      return Blob.fromPb(
          this,
          runWithRetries(
              new Callable<StorageObject>() {
                @Override
                public StorageObject call() {
                  return rpcClient.compose(sourceList, targetObject, targetParams);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public CopyWriter copy(final CopyJobRequest copyReq) {
    final StorageObject sourceObject = copyReq.getSource().toPb();
    final Map<StorageRpc.Option, ?> sourceOptionMap =
        getOptionMap(
            copyReq.getSource().getGeneration(), null, copyReq.getSourceOptions(), true);
    final StorageObject targetEntity = copyReq.getTarget().toPb();
    final Map<StorageRpc.Option, ?> targetParams =
        getOptionMap(
            copyReq.getTarget().getGeneration(),
            copyReq.getTarget().getMetageneration(),
            copyReq.getTargetOptions());
    try {
      RewriteResponse rewriteResult =
          runWithRetries(
              new Callable<RewriteResponse>() {
                @Override
                public RewriteResponse call() {
                  return rpcClient.openRewrite(
                      new StorageRpc.RewriteRequest(
                              sourceObject,
                              sourceOptionMap,
                          copyReq.getOverrideInfo(),
                              targetEntity,
                              targetParams,
                          copyReq.getMegabytesCopiedPerChunk()));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return new CopyWriter(getOptions(), rewriteResult);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public byte[] readAllBytes(String bucketName, String objectName, BlobSourceOptions... opts) {
    return readAllBytes(BlobId.of(bucketName, objectName), opts);
  }

  @Override
  public byte[] readAllBytes(BlobId objectName, BlobSourceOptions... opts) {
    final StorageObject storageEntity = objectName.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(objectName, opts);
    try {
      return runWithRetries(
          new Callable<byte[]>() {
            @Override
            public byte[] call() {
              return rpcClient.load(storageEntity, optionMapping);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public StorageBatch batch() {
    return new StorageBatch(this.getOptions());
  }

  @Override
  public ReadChannel reader(String bucketName, String objectName, BlobSourceOptions... opts) {
    Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
    return new BlobReadChannel(getOptions(), BlobId.of(bucketName, objectName), optionMapping);
  }

  @Override
  public ReadChannel reader(BlobId objectName, BlobSourceOptions... opts) {
    Map<StorageRpc.Option, ?> optionMapping = getOptionMap(objectName, opts);
    return new BlobReadChannel(getOptions(), objectName, optionMapping);
  }

  @Override
  public BlobWriteChannel writer(BlobInfo blobMetadata, WriteBlobOption... opts) {
    Tuple<BlobInfo, BlobUploadOption[]> targetParams = BlobUploadOption.convertOptions(blobMetadata, opts);
    return getWriter(targetParams.x(), targetParams.y());
  }

  @Override
  public BlobWriteChannel writer(URL signedUrl) {
    return new BlobWriteChannel(getOptions(), signedUrl);
  }

  private BlobWriteChannel getWriter(BlobInfo blobMetadata, BlobUploadOption... opts) {
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobMetadata, opts);
    return new BlobWriteChannel(getOptions(), blobMetadata, optionMapping);
  }

  @Override
  public URL signUrl(BlobInfo blobMetadata, long expirationDuration, TimeUnit timeUnit, UrlSigningOption... opts) {
    EnumMap<UrlSigningOption.RequestOption, Object> optionMapping = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
    for (UrlSigningOption signingOption : opts) {
      optionMapping.put(signingOption.getOption(), signingOption.getValue());
    }

    boolean useV2 =
        getPreferredSignatureVersion(optionMapping).equals(UrlSigningOption.SignatureSchemeVersion.V2);
    boolean useV4 =
        getPreferredSignatureVersion(optionMapping).equals(UrlSigningOption.SignatureSchemeVersion.V4);

    ServiceAccountSigner serviceAccountSigner =
        (ServiceAccountSigner) optionMapping.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
    if (serviceAccountSigner == null) {
      checkState(
          this.getOptions().getCredentials() instanceof ServiceAccountSigner,
          "Signing key was not provided and could not be derived");
      serviceAccountSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
    }

    long expiryTime =
        useV4
            ? TimeUnit.SECONDS.convert(timeUnit.toMillis(expirationDuration), TimeUnit.MILLISECONDS)
            : TimeUnit.SECONDS.convert(
                getOptions().getClock().millisTime() + timeUnit.toMillis(expirationDuration),
                TimeUnit.MILLISECONDS);

    checkArgument(
        !(optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
            && optionMapping.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)),
        "Cannot specify both the VIRTUAL_HOSTED_STYLE and PATH_STYLE SignUrlOptions together.");

    String bucket = bucketNameWithoutSlashesFromBlobInfo(blobMetadata);
    String encodedBlobName = "";
    if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
      encodedBlobName = Rfc3986UriEncode(blobMetadata.getName(), false);
    }

    boolean usePathStyleUrls = usePathStyleForSignedUrl(optionMapping);

    String storageXmlHost =
        usePathStyleUrls
            ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(optionMapping)
            : STORAGE_XML_SCHEME + "://" + bucket + "." + getBaseStorageHostName(optionMapping);

    String stringPath =
        usePathStyleUrls
            ? buildResourceUriPath(bucket, encodedBlobName, optionMapping)
            : buildResourceUriPath("", encodedBlobName, optionMapping);

    URI resourceUri = URI.create(stringPath);
    // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
    // canonical resource string that we'll sign to include the bucket.
    URI signingPath =
        useV2 ? URI.create(buildResourceUriPath(bucket, encodedBlobName, optionMapping)) : resourceUri;

    try {
      SignatureDetails signatureDetails =
          createSignatureDetails(
                  optionMapping, blobMetadata, expiryTime, signingPath, serviceAccountSigner.getAccount());
      String unsignedBody = signatureDetails.buildUnsignedPayload();
      byte[] signatureData = serviceAccountSigner.sign(unsignedBody.getBytes(UTF_8));
      StringBuilder stringBuilder = new StringBuilder();
      stringBuilder.append(storageXmlHost).append(resourceUri);

      if (useV4) {
        BaseEncoding baseEncoding = BaseEncoding.base16().lowerCase();
        String signedString = URLEncoder.encode(baseEncoding.encode(signatureData), UTF_8.name());
        String v4Query = signatureDetails.buildV4QueryString();

        stringBuilder.append('?');
        if (!Strings.isNullOrEmpty(v4Query)) {
          stringBuilder.append(v4Query).append('&');
        }
        stringBuilder.append("X-Goog-Signature=").append(signedString);
      } else {
        BaseEncoding baseEncoding = BaseEncoding.base64();
        String signedString = URLEncoder.encode(baseEncoding.encode(signatureData), UTF_8.name());
        String v2Query = signatureDetails.buildV2QueryString();

        stringBuilder.append('?');
        if (!Strings.isNullOrEmpty(v2Query)) {
          stringBuilder.append(v2Query).append('&');
        }
        stringBuilder.append("GoogleAccessId=").append(serviceAccountSigner.getAccount());
        stringBuilder.append("&Expires=").append(expiryTime);
        stringBuilder.append("&Signature=").append(signedString);
      }

      return new URL(stringBuilder.toString());

    } catch (MalformedURLException | UnsupportedEncodingException urlOrEncodingException) {
      throw new IllegalStateException(urlOrEncodingException);
    }
  }

  private String buildResourceUriPath(
      String bareBucketName,
      String encodedBlobName,
      EnumMap<UrlSigningOption.RequestOption, Object> optionMapping) {
    if (Strings.isNullOrEmpty(bareBucketName)) {
      if (Strings.isNullOrEmpty(encodedBlobName)) {
        return PATH_SEPARATOR;
      }
      if (encodedBlobName.startsWith(PATH_SEPARATOR)) {
        return encodedBlobName;
      }
      return PATH_SEPARATOR + encodedBlobName;
    }

    StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append(PATH_SEPARATOR).append(bareBucketName);
    if (Strings.isNullOrEmpty(encodedBlobName)) {
      boolean useV2 =
          getPreferredSignatureVersion(optionMapping).equals(UrlSigningOption.SignatureSchemeVersion.V2);
      // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
      // must end with a forward slash.
      if (optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && useV2) {
        stringBuilder.append(PATH_SEPARATOR);
      }
      return stringBuilder.toString();
    }
    if (!encodedBlobName.startsWith(PATH_SEPARATOR)) {
      stringBuilder.append(PATH_SEPARATOR);
    }
    stringBuilder.append(encodedBlobName);
    return stringBuilder.toString();
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
    if (optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
      return false;
    }
    return true;
  }

  /**
   * Builds signature info.
   *
   * @param optionMapping the option map
   * @param blobMetadata the blob info
   * @param expiryTime the expiration in seconds
   * @param resourceUri the resource URI
   * @param signingAccountEmail the account email
   * @return signature info
   */
  private SignatureDetails createSignatureDetails(
      Map<UrlSigningOption.RequestOption, Object> optionMapping,
      BlobInfo blobMetadata,
      long expiryTime,
      URI resourceUri,
      String signingAccountEmail) {

    HttpMethod requestMethod =
        optionMapping.containsKey(UrlSigningOption.RequestOption.HTTP_METHOD)
            ? (HttpMethod) optionMapping.get(UrlSigningOption.RequestOption.HTTP_METHOD)
            : HttpMethod.GET;

    SignatureDetails.SignatureBuilder signatureBuilder =
        new SignatureDetails.SignatureBuilder(requestMethod, expiryTime, resourceUri);

    if (firstNonNull((Boolean) optionMapping.get(UrlSigningOption.RequestOption.MD5), false)) {
      checkArgument(blobMetadata.getMd5() != null, "Blob is missing a value for md5");
      signatureBuilder.setContentMd5(blobMetadata.getMd5());
    }

    if (firstNonNull((Boolean) optionMapping.get(UrlSigningOption.RequestOption.CONTENT_TYPE), false)) {
      checkArgument(blobMetadata.getContentType() != null, "Blob is missing a value for content-type");
      signatureBuilder.setContentType(blobMetadata.getContentType());
    }

    signatureBuilder.setSignatureVersion(
        (UrlSigningOption.SignatureSchemeVersion) optionMapping.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));

    signatureBuilder.setAccountEmail(signingAccountEmail);

    signatureBuilder.setTimestamp(getOptions().getClock().millisTime());

    ImmutableMap.Builder<String, String> extraHeadersBuilder =
        new ImmutableMap.Builder<String, String>();

    boolean useV4 =
        UrlSigningOption.SignatureSchemeVersion.V4.equals(
            optionMapping.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
    if (useV4) { // We don't sign the host header for V2 signed URLs; only do this for V4.
      // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
      if (optionMapping.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
        extraHeadersBuilder.put(
            "host",
            bucketNameWithoutSlashesFromBlobInfo(blobMetadata) + "." + getBaseStorageHostName(optionMapping));
      } else if (optionMapping.containsKey(UrlSigningOption.RequestOption.HOST_NAME)) {
        extraHeadersBuilder.put("host", getBaseStorageHostName(optionMapping));
      }
    }

    if (optionMapping.containsKey(UrlSigningOption.RequestOption.EXT_HEADERS)) {
      extraHeadersBuilder.putAll(
          (Map<String, String>) optionMapping.get(UrlSigningOption.RequestOption.EXT_HEADERS));
    }

    ImmutableMap.Builder<String, String> queryBuilder =
        new ImmutableMap.Builder<String, String>();
    if (optionMapping.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
      queryBuilder.putAll(
          (Map<String, String>) optionMapping.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
    }

    return signatureBuilder
        .setCanonicalizedExtensionHeaders((Map<String, String>) extraHeadersBuilder.build())
        .setCanonicalizedQueryParams((Map<String, String>) queryBuilder.build())
        .buildSignature();
  }

  private String bucketNameWithoutSlashesFromBlobInfo(BlobInfo blobMetadata) {
    // The bucket name itself should never contain a forward slash. However, parts already existed
    // in the code to check for this, so we remove the forward slashes to be safe here.
    return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
  }

  /** Returns the hostname used to send requests to Cloud StorageClient, e.g. "storage.googleapis.com". */
  private String getBaseStorageHostName(Map<UrlSigningOption.RequestOption, Object> optionMapping) {
    String explicitBaseHost = (String) optionMapping.get(UrlSigningOption.RequestOption.HOST_NAME);
    if (!Strings.isNullOrEmpty(explicitBaseHost)) {
      return explicitBaseHost.replaceFirst("http(s)?://", "");
    }
    return STORAGE_XML_HOST;
  }

  @Override
  public List<Blob> get(BlobId... blobIdArray) {
    return get(Arrays.asList(blobIdArray));
  }

  @Override
  public List<Blob> get(Iterable<BlobId> blobIdArray) {
    StorageBatch storageBatch = batch();
    final List<Blob> resultList = Lists.newArrayList();
    for (BlobId objectName : blobIdArray) {
      storageBatch
          .get(objectName)
          .notify(
              new BatchResult.Callback<Blob, StorageException>() {
                @Override
                public void success(Blob result) {
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
  public List<Blob> update(BlobInfo... blobInfoList) {
    return update(Arrays.asList(blobInfoList));
  }

  @Override
  public List<Blob> update(Iterable<BlobInfo> blobInfoList) {
    StorageBatch storageBatch = batch();
    final List<Blob> resultList = Lists.newArrayList();
    for (BlobInfo blobMetadata : blobInfoList) {
      storageBatch
          .update(blobMetadata)
          .notify(
              new BatchResult.Callback<Blob, StorageException>() {
                @Override
                public void success(Blob result) {
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
  public List<Boolean> delete(BlobId... blobIdArray) {
    return delete(Arrays.asList(blobIdArray));
  }

  @Override
  public List<Boolean> delete(Iterable<BlobId> blobIdArray) {
    StorageBatch storageBatch = batch();
    final List<Boolean> resultList = Lists.newArrayList();
    for (BlobId objectName : blobIdArray) {
      storageBatch
          .delete(objectName)
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
  public Acl getAcl(final String bucketName, final Entity aclEntity, BucketSourceOptions... opts) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
      BucketAccessControl bucketAnswer =
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return rpcClient.getAcl(bucketName, aclEntity.toPb(), optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return bucketAnswer == null ? null : Acl.fromPb(bucketAnswer);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl getAcl(final String bucketName, final Entity aclEntity) {
    return getAcl(bucketName, aclEntity, new BucketSourceOptions[0]);
  }

  @Override
  public boolean deleteAcl(
          final String bucketName, final Entity aclEntity, BucketSourceOptions... opts) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteAcl(bucketName, aclEntity.toPb(), optionMapping);
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public boolean deleteAcl(final String bucketName, final Entity aclEntity) {
    return deleteAcl(bucketName, aclEntity, new BucketSourceOptions[0]);
  }

  @Override
  public Acl createAcl(String bucketName, Acl accessControl, BucketSourceOptions... opts) {
    final BucketAccessControl aclProto = accessControl.toBucketPb().setBucket(bucketName);
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
      return Acl.fromPb(
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return rpcClient.createAcl(aclProto, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl createAcl(String bucketName, Acl accessControl) {
    return createAcl(bucketName, accessControl, new BucketSourceOptions[0]);
  }

  @Override
  public Acl updateAcl(String bucketName, Acl accessControl, BucketSourceOptions... opts) {
    final BucketAccessControl aclProto = accessControl.toBucketPb().setBucket(bucketName);
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
      return Acl.fromPb(
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return rpcClient.patchAcl(aclProto, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl updateAcl(String bucketName, Acl accessControl) {
    return updateAcl(bucketName, accessControl, new BucketSourceOptions[0]);
  }

  @Override
  public List<Acl> listAcls(final String bucketName, BucketSourceOptions... opts) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
      List<BucketAccessControl> bucketAnswer =
          runWithRetries(
              new Callable<List<BucketAccessControl>>() {
                @Override
                public List<BucketAccessControl> call() {
                  return rpcClient.listAcls(bucketName, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(bucketAnswer, Acl.FROM_BUCKET_PB_FUNCTION);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public List<Acl> listAcls(final String bucketName) {
    return listAcls(bucketName, new BucketSourceOptions[0]);
  }

  @Override
  public Acl getDefaultAcl(final String bucketName, final Entity aclEntity) {
    try {
      ObjectAccessControl bucketAnswer =
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.getDefaultAcl(bucketName, aclEntity.toPb());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return bucketAnswer == null ? null : Acl.fromPb(bucketAnswer);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public boolean deleteDefaultAcl(final String bucketName, final Entity aclEntity) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteDefaultAcl(bucketName, aclEntity.toPb());
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl createDefaultAcl(String bucketName, Acl accessControl) {
    final ObjectAccessControl aclProto = accessControl.toObjectPb().setBucket(bucketName);
    try {
      return Acl.fromPb(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.createDefaultAcl(aclProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl updateDefaultAcl(String bucketName, Acl accessControl) {
    final ObjectAccessControl aclProto = accessControl.toObjectPb().setBucket(bucketName);
    try {
      return Acl.fromPb(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.patchDefaultAcl(aclProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public List<Acl> listDefaultAcls(final String bucketName) {
    try {
      List<ObjectAccessControl> bucketAnswer =
          runWithRetries(
              new Callable<List<ObjectAccessControl>>() {
                @Override
                public List<ObjectAccessControl> call() {
                  return rpcClient.listDefaultAcls(bucketName);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(bucketAnswer, Acl.FROM_OBJECT_PB_FUNCTION);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl getAcl(final BlobId objectName, final Entity aclEntity) {
    try {
      ObjectAccessControl bucketAnswer =
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.getAcl(
                      objectName.getBucket(), objectName.getName(), objectName.getGeneration(), aclEntity.toPb());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return bucketAnswer == null ? null : Acl.fromPb(bucketAnswer);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public boolean deleteAcl(final BlobId objectName, final Entity aclEntity) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteAcl(
                  objectName.getBucket(), objectName.getName(), objectName.getGeneration(), aclEntity.toPb());
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl createAcl(final BlobId objectName, final Acl accessControl) {
    final ObjectAccessControl aclProto =
        accessControl.toObjectPb()
            .setBucket(objectName.getBucket())
            .setObject(objectName.getName())
            .setGeneration(objectName.getGeneration());
    try {
      return Acl.fromPb(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.createAcl(aclProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl updateAcl(BlobId objectName, Acl accessControl) {
    final ObjectAccessControl aclProto =
        accessControl.toObjectPb()
            .setBucket(objectName.getBucket())
            .setObject(objectName.getName())
            .setGeneration(objectName.getGeneration());
    try {
      return Acl.fromPb(
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.patchAcl(aclProto);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public List<Acl> listAcls(final BlobId objectName) {
    try {
      List<ObjectAccessControl> bucketAnswer =
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
      return Lists.transform(bucketAnswer, Acl.FROM_OBJECT_PB_FUNCTION);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  public HmacKey createHmacKey(
          final ServiceAccount serviceAccountInfo, final HmacKeyCreationOption... opts) {
    try {
      return HmacKey.fromPb(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKey>() {
                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                  return rpcClient.createHmacKey(serviceAccountInfo.getEmail(), getOptionMap(opts));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Page<HmacKeyMetadata> listHmacKeys(ListHmacKeysOptions... opts) {
    return listHmacKeyMetadata(getOptions(), getOptionMap(opts));
  }

  @Override
  public HmacKeyMetadata getHmacKey(final String accessIdentifier, final HmacKeyRetrievalOption... opts) {
    try {
      return HmacKeyMetadata.fromPb(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {
                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                  return rpcClient.getHmacKey(accessIdentifier, getOptionMap(opts));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  private HmacKeyMetadata updateHmacKeyMetadata(
          final HmacKeyMetadata hmacMetadata, final HmacKeyUpdateOption... opts) {
    try {
      return HmacKeyMetadata.fromPb(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {
                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                  return rpcClient.updateHmacKey(hmacMetadata.toPb(), getOptionMap(opts));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public HmacKeyMetadata updateHmacKeyState(
      final HmacKeyMetadata hmacMetadata,
      final HmacKey.HmacKeyState hmacState,
      final HmacKeyUpdateOption... opts) {
    HmacKeyMetadata updatedHmacMetadata =
        HmacKeyMetadata.newBuilder(hmacMetadata.getServiceAccount())
            .setProjectId(hmacMetadata.getProjectId())
            .setAccessId(hmacMetadata.getAccessId())
            .setState(hmacState)
            .build();
    return updateHmacKeyMetadata(updatedHmacMetadata, opts);
  }

  @Override
  public void deleteHmacKey(final HmacKeyMetadata hmacMetadata, final DeleteHmacKeyRequestOption... opts) {
    try {
      runWithRetries(
          new Callable<Void>() {
            @Override
            public Void call() {
              rpcClient.deleteHmacKey(hmacMetadata.toPb(), getOptionMap(opts));
              return null;
            }
          },
          getOptions().getRetrySettings(),
          EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  private static Page<HmacKeyMetadata> listHmacKeyMetadata(
          final StorageOptions serviceSettings, final Map<StorageRpc.Option, ?> opts) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> resultTuple =
          runWithRetries(
              new Callable<
                  Tuple<
                      String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {
                @Override
                public Tuple<
                        String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>
                    call() {
                  return serviceSettings.getStorageRpcV1().listHmacKeys(opts);
                }
              },
              serviceSettings.getRetrySettings(),
              EXCEPTION_HANDLER,
              serviceSettings.getClock());
      String cursorToken = resultTuple.x();
      final Iterable<HmacKeyMetadata> hmacMetadata =
          resultTuple.y() == null
              ? ImmutableList.<HmacKeyMetadata>of()
              : Iterables.transform(
                  resultTuple.y(),
                  new Function<
                      com.google.api.services.storage.model.HmacKeyMetadata, HmacKeyMetadata>() {
                    @Override
                    public HmacKeyMetadata apply(
                        com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                      return HmacKeyMetadata.fromPb(metadataPb);
                    }
                  });
      return new PageImpl<>(
          new HmacKeyMetadataPageRetriever(serviceSettings, opts), cursorToken, hmacMetadata);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Policy getIamPolicy(final String bucketName, BucketSourceOptions... opts) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
      return convertFromApiPolicy(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Policy>() {
                @Override
                public com.google.api.services.storage.model.Policy call() {
                  return rpcClient.getIamPolicy(bucketName, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Policy setIamPolicy(
          final String bucketName, final Policy iamPolicy, BucketSourceOptions... opts) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
      return convertFromApiPolicy(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Policy>() {
                @Override
                public com.google.api.services.storage.model.Policy call() {
                  return rpcClient.setIamPolicy(bucketName, convertToApiPolicy(iamPolicy), optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public List<Boolean> testIamPermissions(
          final String bucketName, final List<String> permissionList, BucketSourceOptions... opts) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(opts);
      TestIamPermissionsResponse iamResponse =
          runWithRetries(
              new Callable<TestIamPermissionsResponse>() {
                @Override
                public TestIamPermissionsResponse call() {
                  return rpcClient.testIamPermissions(bucketName, permissionList, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      final Set<String> grantedPermissions =
          iamResponse.getPermissions() != null
              ? ImmutableSet.copyOf(iamResponse.getPermissions())
              : ImmutableSet.<String>of();
      return Lists.transform(
              permissionList,
          new Function<String, Boolean>() {
            @Override
            public Boolean apply(String permission) {
              return grantedPermissions.contains(permission);
            }
          });
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Bucket lockRetentionPolicy(BucketInfo bucketMetadata, BucketTargetOptions... opts) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(bucketMetadata, opts);
    try {
      return Bucket.fromPb(
          this,
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Bucket>() {
                @Override
                public com.google.api.services.storage.model.Bucket call() {
                  return rpcClient.lockRetentionPolicy(bucketProto, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock()));
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public ServiceAccount getServiceAccount(final String projectIdentifier) {
    try {
      com.google.api.services.storage.model.ServiceAccount bucketAnswer =
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
      return bucketAnswer == null ? null : ServiceAccount.fromPb(bucketAnswer);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  private static <T> void addOptionToMap(
          StorageRpc.Option signingOption, T valueIfAbsent, Map<StorageRpc.Option, Object> optionMap) {
    addOptionToMap(signingOption, signingOption, valueIfAbsent, optionMap);
  }

  private static <T> void addOptionToMap(
      StorageRpc.Option fetchOption,
      StorageRpc.Option applyOption,
      T valueIfAbsent,
      Map<StorageRpc.Option, Object> optionMap) {
    if (optionMap.containsKey(fetchOption)) {
      @SuppressWarnings("unchecked")
      T foundValue = (T) optionMap.remove(fetchOption);
      checkArgument(
          foundValue != null || valueIfAbsent != null,
          "StorageOption " + fetchOption.value() + " is missing a value");
      foundValue = firstNonNull(foundValue, valueIfAbsent);
      optionMap.put(applyOption, foundValue);
    }
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(
          Long generationNumber, Long metaGen, Iterable<? extends Option> opts) {
    return getOptionMap(generationNumber, metaGen, opts, false);
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(
      Long generationNumber,
      Long metaGen,
      Iterable<? extends Option> opts,
      boolean treatAsSource) {
    Map<StorageRpc.Option, Object> tempMap = Maps.newEnumMap(StorageRpc.Option.class);
    for (Option signingOption : opts) {
      Object previousValue = tempMap.put(signingOption.getRpcOption(), signingOption.getValue());
      checkArgument(previousValue == null, "Duplicate option %s", signingOption);
    }
    Boolean foundValue = (Boolean) tempMap.remove(DELIMITER);
    if (Boolean.TRUE.equals(foundValue)) {
      tempMap.put(DELIMITER, PATH_SEPARATOR);
    }
    if (treatAsSource) {
      addOptionToMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, generationNumber, tempMap);
      addOptionToMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, generationNumber, tempMap);
      addOptionToMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGen, tempMap);
      addOptionToMap(
          IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGen, tempMap);
    } else {
      addOptionToMap(IF_GENERATION_MATCH, generationNumber, tempMap);
      addOptionToMap(IF_GENERATION_NOT_MATCH, generationNumber, tempMap);
      addOptionToMap(IF_METAGENERATION_MATCH, metaGen, tempMap);
      addOptionToMap(IF_METAGENERATION_NOT_MATCH, metaGen, tempMap);
    }
    return ImmutableMap.copyOf(tempMap);
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(Option... opts) {
    return getOptionMap(null, null, Arrays.asList(opts));
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(
          Long generationNumber, Long metaGen, Option... opts) {
    return getOptionMap(generationNumber, metaGen, Arrays.asList(opts));
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(BucketInfo bucketMetadata, Option... opts) {
    return getOptionMap(null, bucketMetadata.getMetageneration(), opts);
  }

  static Map<StorageRpc.Option, ?> getOptionMap(BlobInfo blobMetadata, Option... opts) {
    return getOptionMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), opts);
  }

  static Map<StorageRpc.Option, ?> getOptionMap(BlobId objectIdentifier, Option... opts) {
    return getOptionMap(objectIdentifier.getGeneration(), null, opts);
  }
}
