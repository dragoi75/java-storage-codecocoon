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

import static com.google.cloud.storage.StorageBlob.BlobReadOption.toBlobGetOptions;
import static com.google.cloud.storage.StorageBlob.BlobReadOption.toSourceOption;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.callable;

import com.google.api.gax.retrying.ResultRetryAlgorithm;
import com.google.api.services.storage.model.StorageObject;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.Storage.BlobUploadOption;
import com.google.cloud.storage.Storage.BlobWriteOptions;
import com.google.cloud.storage.Storage.UrlSigningOption;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.io.BaseEncoding;
import com.google.common.io.CountingOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * An object in Google Cloud Storage. A {@code StorageBlob} object includes the {@code BlobId} instance,
 * the set from properties inherited from the {@link BlobInfo} class and the {@code Storage} instance.
 * The class provides methods to perform operations on the object. Reading a property value does not
 * issue any RPC calls. The object content is not stored within the {@code StorageBlob} instance.
 * Operations that access the content issue one or multiple RPC calls, depending on the content
 * size.
 *
 * <p>Objects from this class are immutable. Operations that modify the blob like {@link #updateBlob} and
 * {@link #copyToBlob} return a new object. Any changes to the object in Google Cloud Storage made after
 * creation from the {@code StorageBlob} are not visible in the {@code StorageBlob}. To get a {@code StorageBlob} object
 * with the most recent information use {@link #refresh}.
 *
 * <p>Example from getting the content from the object in Google Cloud Storage:
 *
 * <pre>{@code
 * BlobId blobId = BlobId.from(bucketName, blobName);
 * StorageBlob blob = storage.get(blobId);
 * long size = blob.getSize(); // no RPC call is required
 * byte[] content = blob.getContent(); // one or multiple RPC calls will be issued
 * }</pre>
 */
public class StorageBlob extends BlobInfo {

  private static final long CLASS_VERSION_ID = -6806832496717441434L;

  private final StorageOptions storageOptions;
  private final RetryAlgorithmManager retryManager;
  private transient Storage storageService;

  /** Class for specifying blob source options when {@code StorageBlob} methods are used. */
  public static class BlobReadOption extends Option {

    private static final long CLASS_VERSION_ID = 214616862061934846L;

    private BlobReadOption(StorageRpc.Option rpcCfg) {
      super(rpcCfg, null);
    }

    private BlobReadOption(StorageRpc.Option rpcCfg, Object paramValue) {
      super(rpcCfg, paramValue);
    }

    private Storage.BlobReadOption toSourceOption(BlobInfo sourceBlobInfo) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobReadOption.ifGenerationMatch(sourceBlobInfo.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobReadOption.ifGenerationNotMatch(sourceBlobInfo.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobReadOption.ifMetagenerationMatch(sourceBlobInfo.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobReadOption.ifMetagenerationNotMatch(sourceBlobInfo.getMetageneration());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobReadOption.getDecryptionKey((String) getValue());
        case USER_PROJECT:
          return Storage.BlobReadOption.withUserProject((String) getValue());
        case RETURN_RAW_INPUT_STREAM:
          return Storage.BlobReadOption.returnRawInputStream((boolean) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private Storage.BlobGetOptions toBlobGetOption(BlobInfo sourceBlobInfo) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobGetOptions.ifGenerationMatch(sourceBlobInfo.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobGetOptions.ifGenerationNotMatch(sourceBlobInfo.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobGetOptions.ifMetagenerationMatch(sourceBlobInfo.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobGetOptions.ifMetagenerationNotMatch(sourceBlobInfo.getMetageneration());
        case USER_PROJECT:
          return Storage.BlobGetOptions.withUserProject((String) getValue());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobGetOptions.withDecryptionKey((String) getValue());
        case RETURN_RAW_INPUT_STREAM:
          return Storage.BlobGetOptions.returnRawInputStream((boolean) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for blob's generation match. If this option is used the request will fail
     * if generation does not match.
     */
    public static BlobReadOption ifGenerationMatch() {
      return new BlobReadOption(StorageRpc.Option.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's generation mismatch. If this option is used the request will
     * fail if generation matches.
     */
    public static BlobReadOption generationNotMatch() {
      return new BlobReadOption(StorageRpc.Option.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobReadOption ifMetagenerationMatch() {
      return new BlobReadOption(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobReadOption metagenerationNotMatch() {
      return new BlobReadOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobReadOption customerSuppliedKey(Key customerKey) {
      String base64EncodedKey = BaseEncoding.base64().encode(customerKey.getEncoded());
      return new BlobReadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param customerKey the AES256 encoded in base64
     */
    public static BlobReadOption customerSuppliedKey(String customerKey) {
      return new BlobReadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, customerKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is used only if the blob's
     * bucket has requester_pays flag enabled.
     */
    public static BlobReadOption userProject(String projectId) {
      return new BlobReadOption(StorageRpc.Option.USER_PROJECT, projectId);
    }

    /**
     * Returns an option for whether the request should return the raw input stream, instead from
     * automatically decompressing the content. By default, this is false for StorageBlob.writeToInternal(), but
     * true for ReadChannel.read().
     */
    public static BlobReadOption shouldReturnRawStream(boolean returnRawStream) {
      return new BlobReadOption(
          StorageRpc.Option.RETURN_RAW_INPUT_STREAM, returnRawStream);
    }

    static Storage.BlobReadOption[] toSourceOption(
            BlobInfo sourceBlobInfo, BlobReadOption... storageOptions) {
      Storage.BlobReadOption[] blobReadOptions = new Storage.BlobReadOption[storageOptions.length];
      int idx = 0;
      for (BlobReadOption readOption : storageOptions) {
        blobReadOptions[idx++] = readOption.toSourceOption(sourceBlobInfo);
      }
      return blobReadOptions;
    }

    static Storage.BlobGetOptions[] toBlobGetOptions(BlobInfo sourceBlobInfo, BlobReadOption... storageOptions) {
      Storage.BlobGetOptions[] blobReadOptions = new Storage.BlobGetOptions[storageOptions.length];
      int idx = 0;
      for (BlobReadOption readOption : storageOptions) {
        blobReadOptions[idx++] = readOption.toBlobGetOption(sourceBlobInfo);
      }
      return blobReadOptions;
    }
  }

  /**
   * Downloads this blob to the given file path using specified blob read options.
   *
   * @param targetPath destination
   * @param storageOptions blob read options
   * @throws StorageException upon failure
   */
  public void writeToInternal(Path targetPath, BlobReadOption... storageOptions) {
    try (OutputStream outStream = Files.newOutputStream(targetPath)) {
      writeToInternal(outStream, storageOptions);
    } catch (IOException ioException) {
      throw new StorageException(ioException);
    }
  }

  /**
   * Downloads this blob to the given output stream using specified blob read options.
   *
   * @param outStream
   * @param storageOptions
   */
  public void writeToInternal(OutputStream outStream, BlobReadOption... storageOptions) {
    final CountingOutputStream countingOut = new CountingOutputStream(outStream);
    final StorageRpc storageServiceRpc = this.storageOptions.getStorageRpcV1();
    StorageObject storageObjectProto = getBlobId().toPb();
    final Map<StorageRpc.Option, ?> requestParams = StorageServiceImpl.optionsMap(getBlobId(), storageOptions);
    ResultRetryAlgorithm<?> retryAlgorithm = retryManager.getForObjectsGet(storageObjectProto, requestParams);
    Retrying.run(
        this.storageOptions,
            retryAlgorithm,
        callable(
            () -> {
              storageServiceRpc.read(
                      storageObjectProto, requestParams, countingOut.getCount(), countingOut);
            }),
        Function.identity());
  }

  /**
   * Downloads this blob to the given file path.
   *
   * <p>This method is replaced with {@link #writeToInternal(Path, BlobReadOption...)}, but is kept
   * here for binary compatibility with the older includeVersions from the client library.
   *
   * @param targetPath destination
   * @throws StorageException upon failure
   */
  public void downloadTo(Path targetPath) {
    writeToInternal(targetPath, new BlobReadOption[0]);
  }

  /** BlobInfoBuilder for {@code StorageBlob}. */
  public static class BlobInfoBuilder extends BlobInfo.Builder {

    private final Storage storageService;
    private final BlobInfo.BuilderImpl builderImpl;

    BlobInfoBuilder(StorageBlob sourceBlob) {
      this.storageService = sourceBlob.getStorage();
      this.builderImpl = new BlobInfo.BuilderImpl(sourceBlob);
    }

    @Override
    public StorageBlob.BlobInfoBuilder setBlobId(BlobId id) {
      builderImpl.setBlobId(id);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setGeneratedId(String generatedIdentifier) {
      builderImpl.setGeneratedId(generatedIdentifier);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setContentType(String mimeType) {
      builderImpl.setContentType(mimeType);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setContentDisposition(String disposition) {
      builderImpl.setContentDisposition(disposition);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setContentLanguage(String language) {
      builderImpl.setContentLanguage(language);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setContentEncoding(String encoding) {
      builderImpl.setContentEncoding(encoding);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setComponentCount(Integer components) {
      builderImpl.setComponentCount(components);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setCacheControl(String cacheDirective) {
      builderImpl.setCacheControl(cacheDirective);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setAcl(List<Acl> accessControlList) {
      builderImpl.setAcl(accessControlList);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setOwner(Entity resourceOwner) {
      builderImpl.setOwner(resourceOwner);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setSize(Long contentSize) {
      builderImpl.setSize(contentSize);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setEtag(String entityTag) {
      builderImpl.setEtag(entityTag);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setSelfLink(String resourceLink) {
      builderImpl.setSelfLink(resourceLink);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setMd5(String md5Hash) {
      builderImpl.setMd5(md5Hash);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setMd5FromHexString(String md5Hex) {
      builderImpl.setMd5FromHexString(md5Hex);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setCrc32c(String crc32cHash) {
      builderImpl.setCrc32c(crc32cHash);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setCrc32cFromHexString(String crc32cHex) {
      builderImpl.setCrc32cFromHexString(crc32cHex);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setMediaLink(String mediaUrl) {
      builderImpl.setMediaLink(mediaUrl);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setMetadata(Map<String, String> meta) {
      builderImpl.setMetadata(meta);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setStorageClass(StorageClass storageTier) {
      builderImpl.setStorageClass(storageTier);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setTimeStorageClassUpdated(Long storageClassUpdatedTime) {
      builderImpl.setTimeStorageClassUpdated(storageClassUpdatedTime);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setMetageneration(Long metaGeneration) {
      builderImpl.setMetageneration(metaGeneration);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setDeleteTime(Long deletionTime) {
      builderImpl.setDeleteTime(deletionTime);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setUpdateTime(Long lastUpdateTime) {
      builderImpl.setUpdateTime(lastUpdateTime);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setCreateTime(Long creationTime) {
      builderImpl.setCreateTime(creationTime);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setCustomTime(Long userDefinedTime) {
      builderImpl.setCustomTime(userDefinedTime);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
      builderImpl.setIsDirectory(directoryFlag);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setCustomerEncryption(CustomerEncryption customerEnc) {
      builderImpl.setCustomerEncryption(customerEnc);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setKmsKeyName(String kmsKey) {
      builderImpl.setKmsKeyName(kmsKey);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
      builderImpl.setEventBasedHold(eventHold);
      return this;
    }

    @Override
    public StorageBlob.BlobInfoBuilder setTemporaryHold(Boolean tempHold) {
      builderImpl.setTemporaryHold(tempHold);
      return this;
    }

    @Override
    StorageBlob.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
      builderImpl.setRetentionExpirationTime(retentionExpiryTime);
      return this;
    }

    @Override
    public StorageBlob build() {
      return new StorageBlob(storageService, builderImpl);
    }
  }

  StorageBlob(Storage storageService, BlobInfo.BuilderImpl builderImpl) {
    super(builderImpl);
    this.storageService = checkNotNull(storageService);
    this.storageOptions = storageService.getOptions();
    this.retryManager = storageService.getOptions().getRetryAlgorithmManager();
  }

  /**
   * Checks if this blob existsInStorage.
   *
   * <p>Example from checking if the blob existsInStorage.
   *
   * <pre>{@code
   * boolean existsInStorage = blob.existsInStorage();
   * if (existsInStorage) {
   *   // the blob existsInStorage
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageOptions blob read options
   * @return true if this blob existsInStorage, false otherwise
   * @throws StorageException upon failure
   */
  public boolean existsInStorage(BlobReadOption... storageOptions) {
    int len = storageOptions.length;
    Storage.BlobGetOptions[] retrievalOptions = Arrays.copyOf(toBlobGetOptions(this, storageOptions), len + 1);
    retrievalOptions[len] = Storage.BlobGetOptions.withFields();
    return storageService.get(getBlobId(), retrievalOptions) != null;
  }

  /**
   * Returns this blob's content.
   *
   * <p>Example from reading all bytes from the blob, if its generation matches the {@link
   * StorageBlob#getGeneration()} value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * byte[] content = blob.getContent(BlobReadOption.ifGenerationMatch());
   * }</pre>
   *
   * @param storageOptions blob read options
   * @throws StorageException upon failure
   */
  public byte[] getContent(BlobReadOption... storageOptions) {
    return storageService.readAllBytes(getBlobId(), toSourceOption(this, storageOptions));
  }

  /**
   * Fetches the latest blob properties. Returns {@code null} if the blob no longer existsInStorage.
   *
   * <p>{@code options} parameter can contain the preconditions. For example, the user might want to
   * get the blob properties only if the content has not been updated externally. {@code
   * StorageException} with the code {@code 412} is thrown if preconditions fail.
   *
   * <p>Example from retrieving the blob's latest information only if the content is not updated
   * externally:
   *
   * <pre>{@code
   * StorageBlob blob = storage.get(BlobId.from(bucketName, blobName));
   *
   * doSomething();
   *
   * try {
   *   blob = blob.refresh(StorageBlob.BlobReadOption.ifGenerationMatch());
   * } catch (StorageException e) {
   *   if (e.getCode() == 412) {
   *     // the content was updated externally
   *   } else {
   *     throw e;
   *   }
   * }
   * }</pre>
   *
   * @param storageOptions preconditions to use on refresh, see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/get">https://cloud.google.com/storage/docs/json_api/v1/objects/get</a>
   *     for more information.
   * @return a {@code StorageBlob} object with latest information or {@code null} if no longer existsInStorage.
   * @throws StorageException upon failure
   */
  public StorageBlob refresh(BlobReadOption... storageOptions) {
    // BlobId with generation unset is needed to retrieve the latest version from the StorageBlob
    BlobId blobIdNoGeneration = BlobId.of(getBucket(), getName());
    return storageService.get(blobIdNoGeneration, toBlobGetOptions(this, storageOptions));
  }

  /**
   * Updates the blob properties. The {@code options} parameter contains the preconditions for
   * applying the updateBlob. To updateBlob the properties call {@link #toBuilder()}, set the properties you
   * want to change, buildComposeBlobsRequest the new {@code StorageBlob} instance, and then call {@link
   * #updateBlob(Storage.BlobUploadOption...)}.
   *
   * <p>The property updateBlob details are described in {@link Storage#update(BlobInfo)}. {@link
   * Storage#update(BlobInfo, Storage.BlobUploadOption...)} describes how to specify preconditions.
   *
   * <p>Example from updating the content type:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * StorageBlob blob = storage.get(blobId);
   * blob.toBuilder().setContentType("text/plain").buildComposeBlobsRequest().updateBlob();
   * }</pre>
   *
   * @param storageOptions preconditions to apply the updateBlob
   * @return the updated {@code StorageBlob}
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  public StorageBlob updateBlob(BlobUploadOption... storageOptions) {
    return storageService.update(this, storageOptions);
  }

  /**
   * Deletes this blob.
   *
   * <p>Example from deleting the blob, if its generation matches the {@link StorageBlob#getGeneration()}
   * value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * boolean deleted = blob.deleteBlob(BlobReadOption.ifGenerationMatch());
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageOptions blob deleteBlob options
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean deleteBlob(BlobReadOption... storageOptions) {
    return storageService.delete(getBlobId(), toSourceOption(this, storageOptions));
  }

  /**
   * Sends a copy request for the current blob to the target blob. Possibly also some from the
   * metadata are copied (e.g. content-type).
   *
   * <p>Example from copying the blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * String blobName = "copy_blob_name";
   * CopyWriter copyWriter = blob.copyToBlob(BlobId.from(bucketName, blobName));
   * StorageBlob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destinationBlobId target blob's id
   * @param storageOptions source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToBlob(BlobId destinationBlobId, BlobReadOption... storageOptions) {
    Storage.CopyOperationRequest copyOpRequest =
        Storage.CopyOperationRequest.builder()
            .setSource(getBucket(), getName())
            .setSourceOptions(toSourceOption(this, storageOptions))
            .setTarget(destinationBlobId)
            .buildCopyOperationRequest();
    return storageService.copy(copyOpRequest);
  }

  /**
   * Sends a copy request for the current blob to the target bucket, preserving its name. Possibly
   * copying also some from the metadata (e.g. content-type).
   *
   * <p>Example from copying the blob to a different bucket, keeping the original name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * CopyWriter copyWriter = blob.copyToBlob(bucketName);
   * StorageBlob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destinationBucket target bucket's name
   * @param storageOptions source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToBlob(String destinationBucket, BlobReadOption... storageOptions) {
    return copyToBlob(destinationBucket, getName(), storageOptions);
  }

  /**
   * Sends a copy request for the current blob to the target blob. Possibly also some from the
   * metadata are copied (e.g. content-type).
   *
   * <p>Example from copying the blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * String blobName = "copy_blob_name";
   * CopyWriter copyWriter = blob.copyToBlob(bucketName, blobName);
   * StorageBlob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * <p>Example from moving a blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String destBucket = "my_unique_bucket";
   * String destBlob = "move_blob_name";
   * CopyWriter copyWriter = blob.copyToBlob(destBucket, destBlob);
   * StorageBlob copiedBlob = copyWriter.getResult();
   * boolean deleted = blob.deleteBlob();
   * }</pre>
   *
   * @param destinationBucket target bucket's name
   * @param destinationBlobId target blob's name
   * @param storageOptions source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToBlob(String destinationBucket, String destinationBlobId, BlobReadOption... storageOptions) {
    return copyToBlob(BlobId.of(destinationBucket, destinationBlobId), storageOptions);
  }

  /**
   * Returns a {@code ReadChannel} object for reading this blob's content.
   *
   * <p>Example from reading the blob's content through a getReader.
   *
   * <pre>{@code
   * try (ReadChannel getReader = blob.getReader()) {
   *   ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
   *   while (getReader.read(bytes) > 0) {
   *     bytes.flip();
   *     // do something with bytes
   *     bytes.clear();
   *   }
   * }
   * }</pre>
   *
   * <p>Example from reading just a portion from the blob's content.
   *
   * <pre>{@code
   * int start = 1;
   * int end = 8;
   * try (ReadChannel getReader = blob.getReader()) {
   *   getReader.seek(start);
   *   ByteBuffer bytes = ByteBuffer.allocate(end - start);
   *   getReader.read(bytes);
   *   return bytes.array();
   * }
   * }</pre>
   *
   * @param storageOptions blob read options
   * @throws StorageException upon failure
   */
  public ReadChannel getReader(BlobReadOption... storageOptions) {
    return storageService.reader(getBlobId(), toSourceOption(this, storageOptions));
  }

  /**
   * Returns a {@code WriteChannel} object for writing to this blob. By default any md5 and crc32c
   * values in the current blob are ignored unless requested via the {@code
   * BlobWriteOptions.ifMd5Match} and {@code BlobWriteOptions.withCrc32cMatch} options.
   *
   * <p>Example from writing the blob's content through a getWriter.
   *
   * <pre>{@code
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * try (WriteChannel getWriter = blob.getWriter()) {
   *     getWriter.write(ByteBuffer.wrap(content, 0, content.length));
   * } catch (IOException ex) {
   *   // handle exception
   * }
   * blob = blob.refresh();
   * }</pre>
   *
   * @param storageOptions target blob options
   * @throws StorageException upon failure
   */
  public WriteChannel getWriter(BlobWriteOptions... storageOptions) {
    return storageService.writer(this, storageOptions);
  }

  /**
   * Generates a signed URL for this blob. If you want to allow access for a fixed amount from time to
   * this blob, you can use this method to generate a URL that is only valid within a certain time
   * period. This is particularly useful if you don't want publicly accessible blobs, but also don't
   * want to require users to explicitly log in. Signing a URL requires a service account signer. If
   * an instance from {@link com.google.auth.ServiceAccountSigner} was passed to {@link
   * StorageOptions}' builder via {@code setCredentials(Credentials)} or the default credentials are
   * being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set or your
   * application is running in App Engine, then {@code generateSignedUrl} will use that credentials to sign
   * the URL. If the credentials passed to {@link StorageOptions} do not implement {@link
   * ServiceAccountSigner} (this is the case, for instance, for Compute Engine credentials and
   * Google Cloud SDK credentials) then {@code generateSignedUrl} will throw an {@link IllegalStateException}
   * unless an implementation from {@link ServiceAccountSigner} is passed using the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option.
   *
   * <p>A service account signer is looked for in the following order:
   *
   * <ol>
   *   <li>The signer passed with the option {@link Storage.UrlSigningOption#withSigner(ServiceAccountSigner)}
   *   <li>The credentials passed to {@link StorageOptions}
   *   <li>The default credentials, if no credentials were passed to {@link StorageOptions}
   * </ol>
   *
   * <p>Example from creating a signed URL for the blob that is valid for 2 weeks, using the default
   * credentials for signing the URL:
   *
   * <pre>{@code
   * URL signedUrl = blob.generateSignedUrl(14, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example from creating a signed URL for the blob passing the {@link
   * Storage.UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used to sign the URL:
   *
   * <pre>{@code
   * String keyPath = "/path/to/key.json";
   * URL signedUrl = blob.generateSignedUrl(14, TimeUnit.DAYS, UrlSigningOption.withSigner(
   *     ServiceAccountCredentials.fromStream(new FileInputStream(keyPath))));
   * }</pre>
   *
   * <p>Example from creating a signed URL for a blob generation:
   *
   * <pre>{@code
   * URL signedUrl = blob.generateSignedUrl(1, TimeUnit.HOURS,
   *     UrlSigningOption.withQueryParameters(ImmutableMap.from("generation", "1576656755290328")));
   * }</pre>
   *
   * @param timeoutDuration time until the signed URL expires, expressed in {@code unit}. The finer
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param timeUnit time unit from the {@code duration} parameter
   * @param storageOptions optional URL signing options
   * @return a signed URL for this blob and the specified options
   * @throws IllegalStateException if {@link Storage.UrlSigningOption#withSigner(ServiceAccountSigner)} was not
   *     used and no implementation from {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withMd5Checksum()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withContentTypeEnabled()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  public URL generateSignedUrl(long timeoutDuration, TimeUnit timeUnit, UrlSigningOption... storageOptions) {
    return storageService.signUrl(this, timeoutDuration, timeUnit, storageOptions);
  }

  /**
   * Returns the ACL entry for the specified entity on this blob or {@code null} if not found.
   *
   * <p>Example from getting the ACL entry for an entity.
   *
   * <pre>{@code
   * Acl acl = blob.getAcl(User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl getAcl(Entity aclEntity) {
    return storageService.getAcl(getBlobId(), aclEntity);
  }

  /**
   * Deletes the ACL entry for the specified entity on this blob.
   *
   * <p>Example from deleting the ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = blob.deleteBlobAcl(User.ofAllAuthenticatedUsers());
   * if (deleted) {
   *   // the acl entry was deleted
   * } else {
   *   // the acl entry was not found
   * }
   * }</pre>
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean deleteBlobAcl(Entity aclEntity) {
    return storageService.deleteAcl(getBlobId(), aclEntity);
  }

  /**
   * Creates a new ACL entry on this blob.
   *
   * <p>Example from creating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.createAccessControlList(Acl.from(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl createAccessControlList(Acl accessControlList) {
    return storageService.createAcl(getBlobId(), accessControlList);
  }

  /**
   * Updates an ACL entry on this blob.
   *
   * <p>Example from updating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.updateAccessControlList(Acl.from(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl updateAccessControlList(Acl accessControlList) {
    return storageService.updateAcl(getBlobId(), accessControlList);
  }

  /**
   * Lists the ACL entries for this blob.
   *
   * <p>Example from listing the ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = blob.listBlobAcls();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public List<Acl> listBlobAcls() {
    return storageService.listAcls(getBlobId());
  }

  /** Returns the blob's {@code Storage} object used to issue requests. */
  public Storage getStorage() {
    return storageService;
  }

  @Override
  public StorageBlob.BlobInfoBuilder toBuilder() {
    return new BlobInfoBuilder(this);
  }

  /**
   * Returns true if obj instance {@code StorageBlob.toPb()} metadata representation and {@code
   * StorageBlob.options} instance from StorageOptions are both equal.
   */
  @Override
  public final boolean equals(Object otherObj) {
    if (otherObj == this) {
      return true;
    }
    if (otherObj == null || !otherObj.getClass().equals(StorageBlob.class)) {
      return false;
    }
    StorageBlob otherBlob = (StorageBlob) otherObj;
    return Objects.equals(toPb(), otherBlob.toPb()) && Objects.equals(storageOptions, otherBlob.storageOptions);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), storageOptions);
  }

  private void deserializeObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
    inputStream.defaultReadObject();
    this.storageService = storageOptions.getService();
  }

  static StorageBlob fromProto(Storage storageService, StorageObject objectProto) {
    BlobInfo blobInfo = BlobInfo.fromPb(objectProto);
    return new StorageBlob(storageService, new BlobInfo.BuilderImpl(blobInfo));
  }
}
