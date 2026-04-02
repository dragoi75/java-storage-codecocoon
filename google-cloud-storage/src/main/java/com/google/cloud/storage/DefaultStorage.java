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

final class DefaultStorage extends BaseService<StorageOptions> implements CloudStorage {

  private static final byte[] ZERO_LENGTH_BYTE_ARRAY = {};
  private static final String MD5_OF_EMPTY_BYTES = "1B2M2Y8AsgTpgAmY7PhCfg==";
  private static final String CRC32C_OF_EMPTY_BYTES = "AAAAAA==";
  private static final String PATH_SEPARATOR = "/";
  /** Signed URLs are only supported through the GCS XML API endpoint. */
  private static final String STORAGE_XML_SCHEME = "https";

  private static final String STORAGE_XML_HOST = "storage.googleapis.com";

  private static final Function<Tuple<CloudStorage, Boolean>, Boolean> DELETE_OPERATION_FUNCTION =
      new Function<Tuple<CloudStorage, Boolean>, Boolean>() {
        @Override
        public Boolean apply(Tuple<CloudStorage, Boolean> tuple) {
          return tuple.y();
        }
      };

  private final StorageRpc rpcClient;

  DefaultStorage(StorageOptions params) {
    super(params);
    rpcClient = params.getStorageRpcV1();
  }

  @Override
  public Bucket create(BucketInfo bucketMetadata, BucketTargetOptions... params) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(bucketMetadata, params);
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
  public Blob create(BlobInfo blobMetadata, BlobUploadOption... params) {
    BlobInfo modifiedInfo =
        blobMetadata
            .toBuilder()
            .setMd5(MD5_OF_EMPTY_BYTES)
            .setCrc32c(CRC32C_OF_EMPTY_BYTES)
            .build();
    return createBlobInternal(modifiedInfo, ZERO_LENGTH_BYTE_ARRAY, params);
  }

  @Override
  public Blob create(BlobInfo blobMetadata, byte[] data, BlobUploadOption... params) {
    data = firstNonNull(data, ZERO_LENGTH_BYTE_ARRAY);
    BlobInfo modifiedInfo =
        blobMetadata
            .toBuilder()
            .setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(data).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(Ints.toByteArray(Hashing.crc32c().hashBytes(data).asInt())))
            .build();
    return createBlobInternal(modifiedInfo, data, params);
  }

  @Override
  public Blob create(
          BlobInfo blobMetadata, byte[] data, int startOffset, int chunkLength, BlobUploadOption... params) {
    data = firstNonNull(data, ZERO_LENGTH_BYTE_ARRAY);
    byte[] subArray = Arrays.copyOfRange(data, startOffset, startOffset + chunkLength);
    BlobInfo modifiedInfo =
        blobMetadata
            .toBuilder()
            .setMd5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(subArray).asBytes()))
            .setCrc32c(
                BaseEncoding.base64()
                    .encode(Ints.toByteArray(Hashing.crc32c().hashBytes(subArray).asInt())))
            .build();
    return createBlobInternal(modifiedInfo, subArray, params);
  }

  @Override
  @Deprecated
  public Blob create(BlobInfo blobMetadata, InputStream data, BlobWriteOptions... params) {
    Tuple<BlobInfo, BlobUploadOption[]> targetOptions = BlobUploadOption.convertToUploadOptions(blobMetadata, params);
    StorageObject blobPb = targetOptions.x().toPb();
    Map<StorageRpc.Option, ?> optionMapping = getOptionMap(targetOptions.x(), targetOptions.y());
    InputStream inputStreamSource =
        firstNonNull(data, new ByteArrayInputStream(ZERO_LENGTH_BYTE_ARRAY));
    // retries are not safe when the input is an InputStream, so we can't retry.
    return Blob.fromPb(this, rpcClient.create(blobPb, inputStreamSource, optionMapping));
  }

  private Blob createBlobInternal(BlobInfo metadata, final byte[] data, BlobUploadOption... params) {
    Preconditions.checkNotNull(data);
    final StorageObject blobPb = metadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(metadata, params);
    try {
      return Blob.fromPb(
          this,
          runWithRetries(
              new Callable<StorageObject>() {
                @Override
                public StorageObject call() {
                  return rpcClient.create(blobPb, new ByteArrayInputStream(data), optionMapping);
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
  public Bucket get(String bucketNameString, GetBucketOption... params) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.of(bucketNameString).toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
    try {
      com.google.api.services.storage.model.Bucket response =
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
      return response == null ? null : Bucket.fromPb(this, response);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Blob get(String bucketNameString, String blobName, BlobGetOptions... params) {
    return get(BlobId.of(bucketNameString, blobName), params);
  }

  @Override
  public Blob get(BlobId blobName, BlobGetOptions... params) {
    final StorageObject storedRepresentation = blobName.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobName, params);
    try {
      StorageObject objectResource =
          runWithRetries(
              new Callable<StorageObject>() {
                @Override
                public StorageObject call() {
                  return rpcClient.get(storedRepresentation, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return objectResource == null ? null : Blob.fromPb(this, objectResource);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Blob get(BlobId blobName) {
    return get(blobName, new BlobGetOptions[0]);
  }

  private static class BucketPageRetriever implements NextPageFetcher<Bucket> {

    private static final long SERIAL_VERSION_UID = 5850406828803613729L;
    private final Map<StorageRpc.Option, ?> requestParams;
    private final StorageOptions serviceParams;

    BucketPageRetriever(
            StorageOptions serviceParams, String pageCursor, Map<StorageRpc.Option, ?> requestOptionMap) {
      this.requestParams =
          PageImpl.nextRequestOptions(StorageRpc.Option.PAGE_TOKEN, pageCursor, requestOptionMap);
      this.serviceParams = serviceParams;
    }

    @Override
    public Page<Bucket> getNextPage() {
      return listAllBuckets(serviceParams, requestParams);
    }
  }

  private static class BlobPageIterator implements NextPageFetcher<Blob> {

    private static final long SERIAL_VERSION_UID = 81807334445874098L;
    private final Map<StorageRpc.Option, ?> requestParams;
    private final StorageOptions serviceParams;
    private final String bucketNameString;

    BlobPageIterator(
        String bucketNameString,
        StorageOptions serviceParams,
        String pageCursor,
        Map<StorageRpc.Option, ?> requestOptionMap) {
      this.requestParams =
          PageImpl.nextRequestOptions(StorageRpc.Option.PAGE_TOKEN, pageCursor, requestOptionMap);
      this.serviceParams = serviceParams;
      this.bucketNameString = bucketNameString;
    }

    @Override
    public Page<Blob> getNextPage() {
      return listBlobsInBucket(bucketNameString, serviceParams, requestParams);
    }
  }

  private static class HmacKeyMetadataPageIterator implements NextPageFetcher<HmacKeyMetadata> {

    private static final long SERIAL_VERSION_UID = 308012320541700881L;
    private final StorageOptions serviceParams;
    private final Map<StorageRpc.Option, ?> params;

    HmacKeyMetadataPageIterator(StorageOptions serviceParams, Map<StorageRpc.Option, ?> params) {
      this.serviceParams = serviceParams;
      this.params = params;
    }

    @Override
    public Page<HmacKeyMetadata> getNextPage() {
      return listHmacKeyMetadata(serviceParams, params);
    }
  }

  @Override
  public Page<Bucket> list(BucketListOptions... params) {
    return listAllBuckets(getOptions(), getOptionMap(params));
  }

  @Override
  public Page<Blob> list(final String bucketNameString, BlobListOptions... params) {
    return listBlobsInBucket(bucketNameString, getOptions(), getOptionMap(params));
  }

  private static Page<Bucket> listAllBuckets(
          final StorageOptions serviceParams, final Map<StorageRpc.Option, ?> optionMapping) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> operationResult =
          runWithRetries(
              new Callable<
                  Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {
                @Override
                public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>
                    call() {
                  return serviceParams.getStorageRpcV1().list(optionMapping);
                }
              },
              serviceParams.getRetrySettings(),
              EXCEPTION_HANDLER,
              serviceParams.getClock());
      String pageCursor = operationResult.x();
      Iterable<Bucket> buckets =
          operationResult.y() == null
              ? ImmutableList.<Bucket>of()
              : Iterables.transform(
                  operationResult.y(),
                  new Function<com.google.api.services.storage.model.Bucket, Bucket>() {
                    @Override
                    public Bucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                      return Bucket.fromPb(serviceParams.getService(), bucketPb);
                    }
                  });
      return new PageImpl<>(
          new BucketPageRetriever(serviceParams, pageCursor, optionMapping), pageCursor, buckets);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  private static Page<Blob> listBlobsInBucket(
      final String bucketNameString,
      final StorageOptions serviceParams,
      final Map<StorageRpc.Option, ?> optionMapping) {
    try {
      Tuple<String, Iterable<StorageObject>> operationResult =
          runWithRetries(
              new Callable<Tuple<String, Iterable<StorageObject>>>() {
                @Override
                public Tuple<String, Iterable<StorageObject>> call() {
                  return serviceParams.getStorageRpcV1().list(bucketNameString, optionMapping);
                }
              },
              serviceParams.getRetrySettings(),
              EXCEPTION_HANDLER,
              serviceParams.getClock());
      String pageCursor = operationResult.x();
      Iterable<Blob> blobs =
          operationResult.y() == null
              ? ImmutableList.<Blob>of()
              : Iterables.transform(
                  operationResult.y(),
                  new Function<StorageObject, Blob>() {
                    @Override
                    public Blob apply(StorageObject storageObject) {
                      return Blob.fromPb(serviceParams.getService(), storageObject);
                    }
                  });
      return new PageImpl<>(
          new BlobPageIterator(bucketNameString, serviceParams, pageCursor, optionMapping), pageCursor, blobs);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Bucket update(BucketInfo bucketMetadata, BucketTargetOptions... params) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(bucketMetadata, params);
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
  public Blob update(BlobInfo blobMetadata, BlobUploadOption... params) {
    final StorageObject objectResource = blobMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobMetadata, params);
    try {
      return Blob.fromPb(
          this,
          runWithRetries(
              new Callable<StorageObject>() {
                @Override
                public StorageObject call() {
                  return rpcClient.patch(objectResource, optionMapping);
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
  public boolean delete(String bucketNameString, BucketSourceOptions... params) {
    final com.google.api.services.storage.model.Bucket bucketProto = BucketInfo.of(bucketNameString).toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
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
  public boolean delete(String bucketNameString, String blobName, BlobSourceOptions... params) {
    return delete(BlobId.of(bucketNameString, blobName), params);
  }

  @Override
  public boolean delete(BlobId blobName, BlobSourceOptions... params) {
    final StorageObject objectResource = blobName.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobName, params);
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.delete(objectResource, optionMapping);
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
  public boolean delete(BlobId blobName) {
    return delete(blobName, new BlobSourceOptions[0]);
  }

  @Override
  public Blob compose(final ComposeBlobsRequest composeRequest) {
    final List<StorageObject> sources =
        Lists.newArrayListWithCapacity(composeRequest.getSourceBlobs().size());
    for (ComposeBlobsRequest.SourceObject sourceBlob : composeRequest.getSourceBlobs()) {
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
    final Map<StorageRpc.Option, ?> targetOptions =
        getOptionMap(
            composeRequest.getTarget().getGeneration(),
            composeRequest.getTarget().getMetageneration(),
            composeRequest.getTargetOptions());
    try {
      return Blob.fromPb(
          this,
          runWithRetries(
              new Callable<StorageObject>() {
                @Override
                public StorageObject call() {
                  return rpcClient.compose(sources, target, targetOptions);
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
  public CopyWriter copy(final CopyOperationRequest copyRequest) {
    final StorageObject source = copyRequest.getSource().toPb();
    final Map<StorageRpc.Option, ?> sourceOptions =
        getOptionMap(
            copyRequest.getSource().getGeneration(), null, copyRequest.getSourceOptions(), true);
    final StorageObject targetObject = copyRequest.getTarget().toPb();
    final Map<StorageRpc.Option, ?> targetOptions =
        getOptionMap(
            copyRequest.getTarget().getGeneration(),
            copyRequest.getTarget().getMetageneration(),
            copyRequest.getTargetOptions());
    try {
      RewriteResponse rewriteResponse =
          runWithRetries(
              new Callable<RewriteResponse>() {
                @Override
                public RewriteResponse call() {
                  return rpcClient.openRewrite(
                      new StorageRpc.RewriteRequest(
                          source,
                          sourceOptions,
                          copyRequest.getOverrideInfo(),
                          targetObject,
                          targetOptions,
                          copyRequest.getMegabytesCopiedPerChunk()));
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return new CopyWriter(getOptions(), rewriteResponse);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public byte[] readAllBytes(String bucketNameString, String blobName, BlobSourceOptions... params) {
    return readAllBytes(BlobId.of(bucketNameString, blobName), params);
  }

  @Override
  public byte[] readAllBytes(BlobId blobName, BlobSourceOptions... params) {
    final StorageObject objectResource = blobName.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobName, params);
    try {
      return runWithRetries(
          new Callable<byte[]>() {
            @Override
            public byte[] call() {
              return rpcClient.load(objectResource, optionMapping);
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
  public ReadChannel reader(String bucketNameString, String blobName, BlobSourceOptions... params) {
    Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
    return new BlobReadChannel(getOptions(), BlobId.of(bucketNameString, blobName), optionMapping);
  }

  @Override
  public ReadChannel reader(BlobId blobName, BlobSourceOptions... params) {
    Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobName, params);
    return new BlobReadChannel(getOptions(), blobName, optionMapping);
  }

  @Override
  public BlobWriteChannel writer(BlobInfo blobMetadata, BlobWriteOptions... params) {
    Tuple<BlobInfo, BlobUploadOption[]> targetOptions = BlobUploadOption.convertToUploadOptions(blobMetadata, params);
    return createWriter(targetOptions.x(), targetOptions.y());
  }

  @Override
  public BlobWriteChannel writer(URL signedURL) {
    return new BlobWriteChannel(getOptions(), signedURL);
  }

  private BlobWriteChannel createWriter(BlobInfo blobMetadata, BlobUploadOption... params) {
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(blobMetadata, params);
    return new BlobWriteChannel(getOptions(), blobMetadata, optionMapping);
  }

  @Override
  public URL signUrl(BlobInfo blobMetadata, long duration, TimeUnit unit, UrlSigningOption... params) {
    EnumMap<UrlSigningOption.RequestOption, Object> requestOptionMap = Maps.newEnumMap(UrlSigningOption.RequestOption.class);
    for (UrlSigningOption requestOption : params) {
      requestOptionMap.put(requestOption.getOption(), requestOption.getValue());
    }

    boolean usesV2 =
        getPreferredSignatureVersion(requestOptionMap).equals(UrlSigningOption.SignatureProtocolVersion.V2);
    boolean usesV4 =
        getPreferredSignatureVersion(requestOptionMap).equals(UrlSigningOption.SignatureProtocolVersion.V4);

    ServiceAccountSigner serviceSigner =
        (ServiceAccountSigner) requestOptionMap.get(UrlSigningOption.RequestOption.SERVICE_ACCOUNT_CRED);
    if (serviceSigner == null) {
      checkState(
          this.getOptions().getCredentials() instanceof ServiceAccountSigner,
          "Signing key was not provided and could not be derived");
      serviceSigner = (ServiceAccountSigner) this.getOptions().getCredentials();
    }

    long expiryTime =
        usesV4
            ? TimeUnit.SECONDS.convert(unit.toMillis(duration), TimeUnit.MILLISECONDS)
            : TimeUnit.SECONDS.convert(
                getOptions().getClock().millisTime() + unit.toMillis(duration),
                TimeUnit.MILLISECONDS);

    checkArgument(
        !(requestOptionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)
            && requestOptionMap.containsKey(UrlSigningOption.RequestOption.PATH_STYLE)),
        "Cannot specify both the VIRTUAL_HOSTED_STYLE and PATH_STYLE SignUrlOptions together.");

    String targetBucketName = getSlashlessBucketNameFromBlobInfo(blobMetadata);
    String encodedBlobName = "";
    if (!Strings.isNullOrEmpty(blobMetadata.getName())) {
      encodedBlobName = Rfc3986UriEncode(blobMetadata.getName(), false);
    }

    boolean preferPathStyle = preferPathStyleForSignedUrl(requestOptionMap);

    String storageXmlHost =
        preferPathStyle
            ? STORAGE_XML_SCHEME + "://" + getBaseStorageHostName(requestOptionMap)
            : STORAGE_XML_SCHEME + "://" + targetBucketName + "." + getBaseStorageHostName(requestOptionMap);

    String signedPath =
        preferPathStyle
            ? buildResourceUriPath(targetBucketName, encodedBlobName, requestOptionMap)
            : buildResourceUriPath("", encodedBlobName, requestOptionMap);

    URI resourceUri = URI.create(signedPath);
    // For V2 signing, even if we don't specify the bucket in the URI path, we still need the
    // canonical resource string that we'll sign to include the bucket.
    URI signingPath =
        usesV2 ? URI.create(buildResourceUriPath(targetBucketName, encodedBlobName, requestOptionMap)) : resourceUri;

    try {
      SignatureMetadata signatureMetadata =
          buildSignatureMetadata(
                  requestOptionMap, blobMetadata, expiryTime, signingPath, serviceSigner.getAccount());
      String unsigPayload = signatureMetadata.buildUnsignedPayload();
      byte[] signedBytes = serviceSigner.sign(unsigPayload.getBytes(UTF_8));
      StringBuilder stringBuilder = new StringBuilder();
      stringBuilder.append(storageXmlHost).append(resourceUri);

      if (usesV4) {
        BaseEncoding baseEncoding = BaseEncoding.base16().lowerCase();
        String signedString = URLEncoder.encode(baseEncoding.encode(signedBytes), UTF_8.name());
        String version4Query = signatureMetadata.buildV4QueryString();

        stringBuilder.append('?');
        if (!Strings.isNullOrEmpty(version4Query)) {
          stringBuilder.append(version4Query).append('&');
        }
        stringBuilder.append("X-Goog-Signature=").append(signedString);
      } else {
        BaseEncoding baseEncoding = BaseEncoding.base64();
        String signedString = URLEncoder.encode(baseEncoding.encode(signedBytes), UTF_8.name());
        String version2Query = signatureMetadata.buildV2QueryString();

        stringBuilder.append('?');
        if (!Strings.isNullOrEmpty(version2Query)) {
          stringBuilder.append(version2Query).append('&');
        }
        stringBuilder.append("GoogleAccessId=").append(serviceSigner.getAccount());
        stringBuilder.append("&Expires=").append(expiryTime);
        stringBuilder.append("&Signature=").append(signedString);
      }

      return new URL(stringBuilder.toString());

    } catch (MalformedURLException | UnsupportedEncodingException urlException) {
      throw new IllegalStateException(urlException);
    }
  }

  private String buildResourceUriPath(
      String cleanBucketName,
      String encodedBlobName,
      EnumMap<UrlSigningOption.RequestOption, Object> requestOptionMap) {
    if (Strings.isNullOrEmpty(cleanBucketName)) {
      if (Strings.isNullOrEmpty(encodedBlobName)) {
        return PATH_SEPARATOR;
      }
      if (encodedBlobName.startsWith(PATH_SEPARATOR)) {
        return encodedBlobName;
      }
      return PATH_SEPARATOR + encodedBlobName;
    }

    StringBuilder pathStringBuilder = new StringBuilder();
    pathStringBuilder.append(PATH_SEPARATOR).append(cleanBucketName);
    if (Strings.isNullOrEmpty(encodedBlobName)) {
      boolean usesV2 =
          getPreferredSignatureVersion(requestOptionMap).equals(UrlSigningOption.SignatureProtocolVersion.V2);
      // If using virtual-hosted style URLs with V2 signing, the path string for a bucket resource
      // must end with a forward slash.
      if (requestOptionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE) && usesV2) {
        pathStringBuilder.append(PATH_SEPARATOR);
      }
      return pathStringBuilder.toString();
    }
    if (!encodedBlobName.startsWith(PATH_SEPARATOR)) {
      pathStringBuilder.append(PATH_SEPARATOR);
    }
    pathStringBuilder.append(encodedBlobName);
    return pathStringBuilder.toString();
  }

  private UrlSigningOption.SignatureProtocolVersion getPreferredSignatureVersion(
      EnumMap<UrlSigningOption.RequestOption, Object> requestOptionMap) {
    // Check for an explicitly specified version in the map.
    for (UrlSigningOption.SignatureProtocolVersion signatureVersion : UrlSigningOption.SignatureProtocolVersion.values()) {
      if (signatureVersion.equals(requestOptionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION))) {
        return signatureVersion;
      }
    }
    // TODO(#6362): V2 is the default, and thus can be specified either explicitly or implicitly
    // Change this to V4 once we make it the default.
    return UrlSigningOption.SignatureProtocolVersion.V2;
  }

  private boolean preferPathStyleForSignedUrl(EnumMap<UrlSigningOption.RequestOption, Object> requestOptionMap) {
    // TODO(#6362): If we decide to change the default style used to generate URLs, switch this
    // logic to return false unless PATH_STYLE was explicitly specified.
    if (requestOptionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
      return false;
    }
    return true;
  }

  /**
   * Builds signature info.
   *
   * @param requestOptionMap the option map
   * @param blobMetadata the blob info
   * @param expiryTime the expiration in seconds
   * @param resourceUri the resource URI
   * @param accountEmail the account email
   * @return signature info
   */
  private SignatureMetadata buildSignatureMetadata(
      Map<UrlSigningOption.RequestOption, Object> requestOptionMap,
      BlobInfo blobMetadata,
      long expiryTime,
      URI resourceUri,
      String accountEmail) {

    HttpMethod httpVerb =
        requestOptionMap.containsKey(UrlSigningOption.RequestOption.HTTP_METHOD)
            ? (HttpMethod) requestOptionMap.get(UrlSigningOption.RequestOption.HTTP_METHOD)
            : HttpMethod.GET;

    SignatureMetadata.CanonicalStringBuilder signatureInfoBuilder =
        new SignatureMetadata.CanonicalStringBuilder(httpVerb, expiryTime, resourceUri);

    if (firstNonNull((Boolean) requestOptionMap.get(UrlSigningOption.RequestOption.MD5), false)) {
      checkArgument(blobMetadata.getMd5() != null, "Blob is missing a value for md5");
      signatureInfoBuilder.setContentMd5(blobMetadata.getMd5());
    }

    if (firstNonNull((Boolean) requestOptionMap.get(UrlSigningOption.RequestOption.CONTENT_TYPE), false)) {
      checkArgument(blobMetadata.getContentType() != null, "Blob is missing a value for content-type");
      signatureInfoBuilder.setContentType(blobMetadata.getContentType());
    }

    signatureInfoBuilder.setSignatureVersion(
        (UrlSigningOption.SignatureProtocolVersion) requestOptionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));

    signatureInfoBuilder.setAccountEmail(accountEmail);

    signatureInfoBuilder.setTimestamp(getOptions().getClock().millisTime());

    ImmutableMap.Builder<String, String> extHeadersBuilder =
        new ImmutableMap.Builder<String, String>();

    boolean usesV4 =
        UrlSigningOption.SignatureProtocolVersion.V4.equals(
            requestOptionMap.get(UrlSigningOption.RequestOption.SIGNATURE_VERSION));
    if (usesV4) { // We don't sign the host header for V2 signed URLs; only do this for V4.
      // Add the host here first, allowing it to be overridden in the EXT_HEADERS option below.
      if (requestOptionMap.containsKey(UrlSigningOption.RequestOption.VIRTUAL_HOSTED_STYLE)) {
        extHeadersBuilder.put(
            "host",
            getSlashlessBucketNameFromBlobInfo(blobMetadata) + "." + getBaseStorageHostName(requestOptionMap));
      } else if (requestOptionMap.containsKey(UrlSigningOption.RequestOption.HOST_NAME)) {
        extHeadersBuilder.put("host", getBaseStorageHostName(requestOptionMap));
      }
    }

    if (requestOptionMap.containsKey(UrlSigningOption.RequestOption.EXT_HEADERS)) {
      extHeadersBuilder.putAll(
          (Map<String, String>) requestOptionMap.get(UrlSigningOption.RequestOption.EXT_HEADERS));
    }

    ImmutableMap.Builder<String, String> queryParamsBuilder =
        new ImmutableMap.Builder<String, String>();
    if (requestOptionMap.containsKey(UrlSigningOption.RequestOption.QUERY_PARAMS)) {
      queryParamsBuilder.putAll(
          (Map<String, String>) requestOptionMap.get(UrlSigningOption.RequestOption.QUERY_PARAMS));
    }

    return signatureInfoBuilder
        .setCanonicalizedExtensionHeaders((Map<String, String>) extHeadersBuilder.build())
        .setCanonicalizedQueryParams((Map<String, String>) queryParamsBuilder.build())
        .buildSignatureMetadata();
  }

  private String getSlashlessBucketNameFromBlobInfo(BlobInfo blobMetadata) {
    // The bucket name itself should never contain a forward slash. However, parts already existed
    // in the code to check for this, so we remove the forward slashes to be safe here.
    return CharMatcher.anyOf(PATH_SEPARATOR).trimFrom(blobMetadata.getBucket());
  }

  /** Returns the hostname used to send requests to Cloud CloudStorage, e.g. "storage.googleapis.com". */
  private String getBaseStorageHostName(Map<UrlSigningOption.RequestOption, Object> requestOptionMap) {
    String specifiedBaseHostName = (String) requestOptionMap.get(UrlSigningOption.RequestOption.HOST_NAME);
    if (!Strings.isNullOrEmpty(specifiedBaseHostName)) {
      return specifiedBaseHostName.replaceFirst("http(s)?://", "");
    }
    return STORAGE_XML_HOST;
  }

  @Override
  public List<Blob> get(BlobId... blobIdList) {
    return get(Arrays.asList(blobIdList));
  }

  @Override
  public List<Blob> get(Iterable<BlobId> blobIdList) {
    StorageBatch storageBatch = batch();
    final List<Blob> resultList = Lists.newArrayList();
    for (BlobId blobName : blobIdList) {
      storageBatch
          .get(blobName)
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
  public List<Blob> update(BlobInfo... blobMetadataArray) {
    return update(Arrays.asList(blobMetadataArray));
  }

  @Override
  public List<Blob> update(Iterable<BlobInfo> blobMetadataArray) {
    StorageBatch storageBatch = batch();
    final List<Blob> resultList = Lists.newArrayList();
    for (BlobInfo blobMetadata : blobMetadataArray) {
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
  public Acl getAcl(final String bucketNameString, final Entity principal, BucketSourceOptions... params) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
      BucketAccessControl response =
          runWithRetries(
              new Callable<BucketAccessControl>() {
                @Override
                public BucketAccessControl call() {
                  return rpcClient.getAcl(bucketNameString, principal.toPb(), optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return response == null ? null : Acl.fromPb(response);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl getAcl(final String bucketNameString, final Entity principal) {
    return getAcl(bucketNameString, principal, new BucketSourceOptions[0]);
  }

  @Override
  public boolean deleteAcl(
          final String bucketNameString, final Entity principal, BucketSourceOptions... params) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteAcl(bucketNameString, principal.toPb(), optionMapping);
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
  public boolean deleteAcl(final String bucketNameString, final Entity principal) {
    return deleteAcl(bucketNameString, principal, new BucketSourceOptions[0]);
  }

  @Override
  public Acl createAcl(String bucketNameString, Acl accessControl, BucketSourceOptions... params) {
    final BucketAccessControl aclProto = accessControl.toBucketPb().setBucket(bucketNameString);
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
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
  public Acl createAcl(String bucketNameString, Acl accessControl) {
    return createAcl(bucketNameString, accessControl, new BucketSourceOptions[0]);
  }

  @Override
  public Acl updateAcl(String bucketNameString, Acl accessControl, BucketSourceOptions... params) {
    final BucketAccessControl aclProto = accessControl.toBucketPb().setBucket(bucketNameString);
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
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
  public Acl updateAcl(String bucketNameString, Acl accessControl) {
    return updateAcl(bucketNameString, accessControl, new BucketSourceOptions[0]);
  }

  @Override
  public List<Acl> listAcls(final String bucketNameString, BucketSourceOptions... params) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
      List<BucketAccessControl> response =
          runWithRetries(
              new Callable<List<BucketAccessControl>>() {
                @Override
                public List<BucketAccessControl> call() {
                  return rpcClient.listAcls(bucketNameString, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(response, Acl.FROM_BUCKET_PB_FUNCTION);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public List<Acl> listAcls(final String bucketNameString) {
    return listAcls(bucketNameString, new BucketSourceOptions[0]);
  }

  @Override
  public Acl getDefaultAcl(final String bucketNameString, final Entity principal) {
    try {
      ObjectAccessControl response =
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.getDefaultAcl(bucketNameString, principal.toPb());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return response == null ? null : Acl.fromPb(response);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public boolean deleteDefaultAcl(final String bucketNameString, final Entity principal) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteDefaultAcl(bucketNameString, principal.toPb());
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
  public Acl createDefaultAcl(String bucketNameString, Acl accessControl) {
    final ObjectAccessControl aclProto = accessControl.toObjectPb().setBucket(bucketNameString);
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
  public Acl updateDefaultAcl(String bucketNameString, Acl accessControl) {
    final ObjectAccessControl aclProto = accessControl.toObjectPb().setBucket(bucketNameString);
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
  public List<Acl> listDefaultAcls(final String bucketNameString) {
    try {
      List<ObjectAccessControl> response =
          runWithRetries(
              new Callable<List<ObjectAccessControl>>() {
                @Override
                public List<ObjectAccessControl> call() {
                  return rpcClient.listDefaultAcls(bucketNameString);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(response, Acl.FROM_OBJECT_PB_FUNCTION);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Acl getAcl(final BlobId blobName, final Entity principal) {
    try {
      ObjectAccessControl response =
          runWithRetries(
              new Callable<ObjectAccessControl>() {
                @Override
                public ObjectAccessControl call() {
                  return rpcClient.getAcl(
                      blobName.getBucket(), blobName.getName(), blobName.getGeneration(), principal.toPb());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return response == null ? null : Acl.fromPb(response);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public boolean deleteAcl(final BlobId blobName, final Entity principal) {
    try {
      return runWithRetries(
          new Callable<Boolean>() {
            @Override
            public Boolean call() {
              return rpcClient.deleteAcl(
                  blobName.getBucket(), blobName.getName(), blobName.getGeneration(), principal.toPb());
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
  public Acl createAcl(final BlobId blobName, final Acl accessControl) {
    final ObjectAccessControl aclProto =
        accessControl.toObjectPb()
            .setBucket(blobName.getBucket())
            .setObject(blobName.getName())
            .setGeneration(blobName.getGeneration());
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
  public Acl updateAcl(BlobId blobName, Acl accessControl) {
    final ObjectAccessControl aclProto =
        accessControl.toObjectPb()
            .setBucket(blobName.getBucket())
            .setObject(blobName.getName())
            .setGeneration(blobName.getGeneration());
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
  public List<Acl> listAcls(final BlobId blobName) {
    try {
      List<ObjectAccessControl> response =
          runWithRetries(
              new Callable<List<ObjectAccessControl>>() {
                @Override
                public List<ObjectAccessControl> call() {
                  return rpcClient.listAcls(
                      blobName.getBucket(), blobName.getName(), blobName.getGeneration());
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return Lists.transform(response, Acl.FROM_OBJECT_PB_FUNCTION);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  public HmacKey createHmacKey(
          final ServiceAccount targetServiceAccount, final HmacKeyCreationOption... params) {
    try {
      return HmacKey.fromPb(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKey>() {
                @Override
                public com.google.api.services.storage.model.HmacKey call() {
                  return rpcClient.createHmacKey(targetServiceAccount.getEmail(), getOptionMap(params));
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
  public Page<HmacKeyMetadata> listHmacKeys(ListHmacKeysOptions... params) {
    return listHmacKeyMetadata(getOptions(), getOptionMap(params));
  }

  @Override
  public HmacKeyMetadata getHmacKey(final String accessIdentifier, final GetHmacKeyRequestOption... params) {
    try {
      return HmacKeyMetadata.fromPb(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {
                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                  return rpcClient.getHmacKey(accessIdentifier, getOptionMap(params));
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
          final HmacKeyMetadata hmacMetadata, final HmacKeyUpdateOption... params) {
    try {
      return HmacKeyMetadata.fromPb(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.HmacKeyMetadata>() {
                @Override
                public com.google.api.services.storage.model.HmacKeyMetadata call() {
                  return rpcClient.updateHmacKey(hmacMetadata.toPb(), getOptionMap(params));
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
      final HmacKey.HmacKeyState hmacKeyState,
      final HmacKeyUpdateOption... params) {
    HmacKeyMetadata modifiedMetadata =
        HmacKeyMetadata.newBuilder(hmacMetadata.getServiceAccount())
            .setProjectId(hmacMetadata.getProjectId())
            .setAccessId(hmacMetadata.getAccessId())
            .setState(hmacKeyState)
            .build();
    return updateHmacKeyMetadata(modifiedMetadata, params);
  }

  @Override
  public void deleteHmacKey(final HmacKeyMetadata hmacMetadataRecord, final RemoveHmacKeyOption... params) {
    try {
      runWithRetries(
          new Callable<Void>() {
            @Override
            public Void call() {
              rpcClient.deleteHmacKey(hmacMetadataRecord.toPb(), getOptionMap(params));
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
          final StorageOptions serviceParams, final Map<StorageRpc.Option, ?> params) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>> operationResult =
          runWithRetries(
              new Callable<
                  Tuple<
                      String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>>() {
                @Override
                public Tuple<
                        String, Iterable<com.google.api.services.storage.model.HmacKeyMetadata>>
                    call() {
                  return serviceParams.getStorageRpcV1().listHmacKeys(params);
                }
              },
              serviceParams.getRetrySettings(),
              EXCEPTION_HANDLER,
              serviceParams.getClock());
      String pageCursor = operationResult.x();
      final Iterable<HmacKeyMetadata> hmacMetadataRecord =
          operationResult.y() == null
              ? ImmutableList.<HmacKeyMetadata>of()
              : Iterables.transform(
                  operationResult.y(),
                  new Function<
                      com.google.api.services.storage.model.HmacKeyMetadata, HmacKeyMetadata>() {
                    @Override
                    public HmacKeyMetadata apply(
                        com.google.api.services.storage.model.HmacKeyMetadata metadataPb) {
                      return HmacKeyMetadata.fromPb(metadataPb);
                    }
                  });
      return new PageImpl<>(
          new HmacKeyMetadataPageIterator(serviceParams, params), pageCursor, hmacMetadataRecord);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  @Override
  public Policy getIamPolicy(final String bucketNameString, BucketSourceOptions... params) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
      return convertFromApiPolicy(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Policy>() {
                @Override
                public com.google.api.services.storage.model.Policy call() {
                  return rpcClient.getIamPolicy(bucketNameString, optionMapping);
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
          final String bucketNameString, final Policy iamPolicy, BucketSourceOptions... params) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
      return convertFromApiPolicy(
          runWithRetries(
              new Callable<com.google.api.services.storage.model.Policy>() {
                @Override
                public com.google.api.services.storage.model.Policy call() {
                  return rpcClient.setIamPolicy(bucketNameString, convertToApiPolicy(iamPolicy), optionMapping);
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
          final String bucketNameString, final List<String> permissionList, BucketSourceOptions... params) {
    try {
      final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(params);
      TestIamPermissionsResponse serviceResponse =
          runWithRetries(
              new Callable<TestIamPermissionsResponse>() {
                @Override
                public TestIamPermissionsResponse call() {
                  return rpcClient.testIamPermissions(bucketNameString, permissionList, optionMapping);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      final Set<String> grantedPermissions =
          serviceResponse.getPermissions() != null
              ? ImmutableSet.copyOf(serviceResponse.getPermissions())
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
  public Bucket lockRetentionPolicy(BucketInfo bucketMetadata, BucketTargetOptions... params) {
    final com.google.api.services.storage.model.Bucket bucketProto = bucketMetadata.toPb();
    final Map<StorageRpc.Option, ?> optionMapping = getOptionMap(bucketMetadata, params);
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
  public ServiceAccount getServiceAccount(final String targetProjectId) {
    try {
      com.google.api.services.storage.model.ServiceAccount response =
          runWithRetries(
              new Callable<com.google.api.services.storage.model.ServiceAccount>() {
                @Override
                public com.google.api.services.storage.model.ServiceAccount call() {
                  return rpcClient.getServiceAccount(targetProjectId);
                }
              },
              getOptions().getRetrySettings(),
              EXCEPTION_HANDLER,
              getOptions().getClock());
      return response == null ? null : ServiceAccount.fromPb(response);
    } catch (RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  private static <T> void addOptionToMap(
          StorageRpc.Option requestOption, T fallbackValue, Map<StorageRpc.Option, Object> optionMapData) {
    addOptionToMap(requestOption, requestOption, fallbackValue, optionMapData);
  }

  private static <T> void addOptionToMap(
      StorageRpc.Option requestedOption,
      StorageRpc.Option insertionOption,
      T fallbackValue,
      Map<StorageRpc.Option, Object> optionMapData) {
    if (optionMapData.containsKey(requestedOption)) {
      @SuppressWarnings("unchecked")
      T paramValue = (T) optionMapData.remove(requestedOption);
      checkArgument(
          paramValue != null || fallbackValue != null,
          "StorageOption " + requestedOption.value() + " is missing a value");
      paramValue = firstNonNull(paramValue, fallbackValue);
      optionMapData.put(insertionOption, paramValue);
    }
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(
          Long generationId, Long metaGenId, Iterable<? extends Option> params) {
    return getOptionMap(generationId, metaGenId, params, false);
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(
      Long generationId,
      Long metaGenId,
      Iterable<? extends Option> params,
      boolean markAsSource) {
    Map<StorageRpc.Option, Object> tempMap = Maps.newEnumMap(StorageRpc.Option.class);
    for (Option requestOption : params) {
      Object previous = tempMap.put(requestOption.getRpcOption(), requestOption.getValue());
      checkArgument(previous == null, "Duplicate option %s", requestOption);
    }
    Boolean paramValue = (Boolean) tempMap.remove(DELIMITER);
    if (Boolean.TRUE.equals(paramValue)) {
      tempMap.put(DELIMITER, PATH_SEPARATOR);
    }
    if (markAsSource) {
      addOptionToMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, generationId, tempMap);
      addOptionToMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, generationId, tempMap);
      addOptionToMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGenId, tempMap);
      addOptionToMap(
          IF_METAGENERATION_NOT_MATCH, IF_SOURCE_METAGENERATION_NOT_MATCH, metaGenId, tempMap);
    } else {
      addOptionToMap(IF_GENERATION_MATCH, generationId, tempMap);
      addOptionToMap(IF_GENERATION_NOT_MATCH, generationId, tempMap);
      addOptionToMap(IF_METAGENERATION_MATCH, metaGenId, tempMap);
      addOptionToMap(IF_METAGENERATION_NOT_MATCH, metaGenId, tempMap);
    }
    return ImmutableMap.copyOf(tempMap);
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(Option... params) {
    return getOptionMap(null, null, Arrays.asList(params));
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(
          Long generationId, Long metaGenId, Option... params) {
    return getOptionMap(generationId, metaGenId, Arrays.asList(params));
  }

  private static Map<StorageRpc.Option, ?> getOptionMap(BucketInfo bucketMetadata, Option... params) {
    return getOptionMap(null, bucketMetadata.getMetageneration(), params);
  }

  static Map<StorageRpc.Option, ?> getOptionMap(BlobInfo blobMetadata, Option... params) {
    return getOptionMap(blobMetadata.getGeneration(), blobMetadata.getMetageneration(), params);
  }

  static Map<StorageRpc.Option, ?> getOptionMap(BlobId blobIdentifier, Option... params) {
    return getOptionMap(blobIdentifier.getGeneration(), null, params);
  }
}
