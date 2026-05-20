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
import static com.google.cloud.storage.StorageObject.BlobSourceOptions.toGetOptionsArray;
import static com.google.cloud.storage.StorageObject.BlobSourceOptions.toSourceOptionsArray;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.callable;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.RetryHelper;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AclEntry.TypedEntity;
import com.google.cloud.storage.StorageClient.DataCopyRequest;
import com.google.cloud.storage.StorageClient.UrlSigningOption;
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient;
import com.google.common.base.Function;
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

/**
 * An object in Google Cloud Storage. A {@code Blob} object includes the {@code BlobId} instance,
 * the set of properties inherited from the {@link BlobAttributes} class and the {@code Storage} instance.
 * The class provides methods to perform operations on the object. Reading a property value does not
 * issue any RPC calls. The object content is not stored within the {@code Blob} instance.
 * Operations that access the content issue one or multiple RPC calls, depending on the content
 * size.
 *
 * <p>Objects of this class are immutable. Operations that modify the blob like {@link #updateInStorage} and
 * {@link #copyToTarget} return a new object. Any changes to the object in Google Cloud Storage made after
 * creation of the {@code Blob} are not visible in the {@code Blob}. To get a {@code Blob} object
 * with the most recent information use {@link #reloadFromStorage}.
 *
 * <p>Example of getting the content of the object in Google Cloud Storage:
 *
 * <pre>{@code
 * BlobId blobId = BlobId.of(bucketName, blobName);
 * Blob blob = storage.get(blobId);
 * long size = blob.getSize(); // no RPC call is required
 * byte[] content = blob.getContent(); // one or multiple RPC calls will be issued
 * }</pre>
 */
public class StorageObject extends BlobAttributes {

  private static final long serialVersionUID = -6806832496717441434L;

  private final StorageSettings storageSettings;
  private transient StorageClient storageClient;

  static final Function<Tuple<StorageClient, com.google.api.services.storage.model.StorageObject>, StorageObject> BLOB_FROM_PB_FUNCTION =
      new Function<Tuple<StorageClient, com.google.api.services.storage.model.StorageObject>, StorageObject>() {
        @Override
        public StorageObject apply(Tuple<StorageClient, com.google.api.services.storage.model.StorageObject> pb) {
          return StorageObject.fromProto(pb.x(), pb.y());
        }
      };

  private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;

  /** Class for specifying blob source options when {@code Blob} methods are used. */
  public static class BlobSourceOptions extends RpcOptionEntry {

    private static final long serialVersionUID = 214616862061934846L;

    private BlobSourceOptions(CloudStorageRpcClient.StorageOption rpcSetting) {
      super(rpcSetting, null);
    }

    private BlobSourceOptions(CloudStorageRpcClient.StorageOption rpcSetting, Object providedObj) {
      super(rpcSetting, providedObj);
    }

    private StorageClient.BlobSourceOptions toStorageSourceOptions(BlobAttributes blobAttributes) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return StorageClient.BlobSourceOptions.ifGenerationMatch(blobAttributes.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return StorageClient.BlobSourceOptions.generationNotMatch(blobAttributes.getGeneration());
        case IF_METAGENERATION_MATCH:
          return StorageClient.BlobSourceOptions.ifMetagenerationMatch(blobAttributes.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return StorageClient.BlobSourceOptions.ifMetagenerationNotMatch(blobAttributes.getMetageneration());
        case CUSTOMER_SUPPLIED_KEY:
          return StorageClient.BlobSourceOptions.customerSuppliedKey((String) getValue());
        case USER_PROJECT:
          return StorageClient.BlobSourceOptions.withUserProject((String) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private StorageClient.BlobGetOptions toStorageGetOption(BlobAttributes blobAttributes) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return StorageClient.BlobGetOptions.ifGenerationMatch(blobAttributes.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return StorageClient.BlobGetOptions.generationNotMatch(blobAttributes.getGeneration());
        case IF_METAGENERATION_MATCH:
          return StorageClient.BlobGetOptions.ifMetagenerationMatch(blobAttributes.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return StorageClient.BlobGetOptions.ifMetagenerationNotMatch(blobAttributes.getMetageneration());
        case USER_PROJECT:
          return StorageClient.BlobGetOptions.withUserProject((String) getValue());
        case CUSTOMER_SUPPLIED_KEY:
          return StorageClient.BlobGetOptions.decryptionKeyBase64((String) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for blob's generation match. If this option is used the request will fail
     * if generation does not match.
     */
    public static BlobSourceOptions ifGenerationMatch() {
      return new BlobSourceOptions(CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's generation mismatch. If this option is used the request will
     * fail if generation matches.
     */
    public static BlobSourceOptions generationNotMatch() {
      return new BlobSourceOptions(CloudStorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobSourceOptions ifMetagenerationMatch() {
      return new BlobSourceOptions(CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobSourceOptions metagenerationNotMatch() {
      return new BlobSourceOptions(CloudStorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobSourceOptions withDecryptionKey(Key decryptionKey) {
      String base64EncodedKey = BaseEncoding.base64().encode(decryptionKey.getEncoded());
      return new BlobSourceOptions(CloudStorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param decryptionKey the AES256 encoded in base64
     */
    public static BlobSourceOptions withDecryptionKey(String decryptionKey) {
      return new BlobSourceOptions(CloudStorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, decryptionKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is used only if the blob's
     * bucket has requester_pays flag enabled.
     */
    public static BlobSourceOptions userProject(String userProjectId) {
      return new BlobSourceOptions(CloudStorageRpcClient.StorageOption.USER_PROJECT, userProjectId);
    }

    static StorageClient.BlobSourceOptions[] toSourceOptionsArray(
            BlobAttributes blobAttributes, BlobSourceOptions... storageSettings) {
      StorageClient.BlobSourceOptions[] normalizedOptions = new StorageClient.BlobSourceOptions[storageSettings.length];
      int i = 0;
      for (BlobSourceOptions singleOpt : storageSettings) {
        normalizedOptions[i++] = singleOpt.toStorageSourceOptions(blobAttributes);
      }
      return normalizedOptions;
    }

    static StorageClient.BlobGetOptions[] toGetOptionsArray(BlobAttributes blobAttributes, BlobSourceOptions... storageSettings) {
      StorageClient.BlobGetOptions[] normalizedOptions = new StorageClient.BlobGetOptions[storageSettings.length];
      int i = 0;
      for (BlobSourceOptions singleOpt : storageSettings) {
        normalizedOptions[i++] = singleOpt.toStorageGetOption(blobAttributes);
      }
      return normalizedOptions;
    }
  }

  /**
   * Downloads this blob to the given file path using specified blob read options.
   *
   * @param destPath destination
   * @param storageSettings blob read options
   * @throws StorageOperationException upon failure
   */
  public void downloadToPath(Path destPath, BlobSourceOptions... storageSettings) {
    try (OutputStream outStream = Files.newOutputStream(destPath)) {
      downloadToPath(outStream, storageSettings);
    } catch (IOException ioException) {
      throw new StorageOperationException(ioException);
    }
  }

  /**
   * Downloads this blob to the given output stream using specified blob read options.
   *
   * @param outStream
   * @param storageSettings
   */
  public void downloadToPath(OutputStream outStream, BlobSourceOptions... storageSettings) {
    final CountingOutputStream countingOut = new CountingOutputStream(outStream);
    final CloudStorageRpcClient rpcClient = this.storageSettings.getStorageRpcV1();
    final Map<CloudStorageRpcClient.StorageOption, ?> requestParams = StorageImpl.optionMap(getBlobId(), storageSettings);
    try {
      runWithRetries(
          callable(
              new Runnable() {
                @Override
                public void run() {
                  rpcClient.read(
                      getBlobId().toStorageObject(),
                          requestParams,
                      countingOut.getCount(),
                          countingOut);
                }
              }),
          this.storageSettings.getRetrySettings(),
          StorageImpl.EXCEPTION_HANDLER,
          this.storageSettings.getClock());
    } catch (RetryHelper.RetryHelperException ioException) {
      StorageOperationException.translateAndRethrow(ioException);
    }
  }

  /**
   * Downloads this blob to the given file path.
   *
   * <p>This method is replaced with {@link #downloadToPath(Path, BlobSourceOptions...)}, but is kept
   * here for binary compatibility with the older versions of the client library.
   *
   * @param destPath destination
   * @throws StorageOperationException upon failure
   */
  public void downloadToPath(Path destPath) {
    this.downloadToPath(destPath, new BlobSourceOptions[0]);
  }

  /** Builder for {@code Blob}. */
  public static class BlobInfoBuilder extends StorageObjectBuilder {

    private final StorageClient storageClient;
    private final BlobInfoBuilderImpl builderImpl;

    BlobInfoBuilder(StorageObject storageObjectParam) {
      this.storageClient = storageObjectParam.getStorage();
      this.builderImpl = new BlobInfoBuilderImpl(storageObjectParam);
    }

    @Override
    public StorageObject.BlobInfoBuilder setBlobId(BlobIdentifier blobIdentifier) {
      builderImpl.setBlobId(blobIdentifier);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setGeneratedId(String generatedToken) {
      builderImpl.setGeneratedId(generatedToken);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentType(String mimeType) {
      builderImpl.setContentType(mimeType);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentDisposition(String dispositionMode) {
      builderImpl.setContentDisposition(dispositionMode);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentLanguage(String languageTag) {
      builderImpl.setContentLanguage(languageTag);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentEncoding(String encoding) {
      builderImpl.setContentEncoding(encoding);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setComponentCount(Integer partCount) {
      builderImpl.setComponentCount(partCount);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCacheControl(String cacheHeader) {
      builderImpl.setCacheControl(cacheHeader);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setAcl(List<AclEntry> accessControlList) {
      builderImpl.setAcl(accessControlList);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setOwner(TypedEntity principalEntity) {
      builderImpl.setOwner(principalEntity);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setSize(Long lengthBytes) {
      builderImpl.setSize(lengthBytes);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setEtag(String entityTag) {
      builderImpl.setEtag(entityTag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setSelfLink(String resourceLink) {
      builderImpl.setSelfLink(resourceLink);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMd5(String digestSum) {
      builderImpl.setMd5(digestSum);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMd5FromHexString(String checksumHex) {
      builderImpl.setMd5FromHexString(checksumHex);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCrc32c(String crcChecksum) {
      builderImpl.setCrc32c(crcChecksum);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCrc32cFromHexString(String crcHexString) {
      builderImpl.setCrc32cFromHexString(crcHexString);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setMediaLink(String mediaUrl) {
      builderImpl.setMediaLink(mediaUrl);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMetadata(Map<String, String> metaMap) {
      builderImpl.setMetadata(metaMap);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setStorageClass(StorageClassType storageTier) {
      builderImpl.setStorageClass(storageTier);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setMetageneration(Long generationNumber) {
      builderImpl.setMetageneration(generationNumber);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setDeleteTime(Long deletionTimestamp) {
      builderImpl.setDeleteTime(deletionTimestamp);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setUpdateTime(Long lastModified) {
      builderImpl.setUpdateTime(lastModified);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setCreateTime(Long creationTimestamp) {
      builderImpl.setCreateTime(creationTimestamp);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
      builderImpl.setIsDirectory(directoryFlag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryptionInfo encryptionInfo) {
      builderImpl.setCustomerEncryption(encryptionInfo);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setKmsKeyName(String keyResourceName) {
      builderImpl.setKmsKeyName(keyResourceName);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
      builderImpl.setEventBasedHold(eventHold);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setTemporaryHold(Boolean tempHold) {
      builderImpl.setTemporaryHold(tempHold);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
      builderImpl.setRetentionExpirationTime(retentionExpiryTime);
      return this;
    }

    @Override
    public StorageObject buildObject() {
      return new StorageObject(storageClient, builderImpl);
    }
  }

  StorageObject(StorageClient storageClient, BlobInfoBuilderImpl builderImpl) {
    super(builderImpl);
    this.storageClient = checkNotNull(storageClient);
    this.storageSettings = storageClient.getOptions();
  }

  /**
   * Checks if this blob exists.
   *
   * <p>Example of checking if the blob exists.
   *
   * <pre>{@code
   * boolean exists = blob.exists();
   * if (exists) {
   *   // the blob exists
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageSettings blob read options
   * @return true if this blob exists, false otherwise
   * @throws StorageOperationException upon failure
   */
  public boolean blobExists(BlobSourceOptions... storageSettings) {
    int len = storageSettings.length;
    StorageClient.BlobGetOptions[] getOpts = Arrays.copyOf(toGetOptionsArray(this, storageSettings), len + 1);
    getOpts[len] = StorageClient.BlobGetOptions.selectFields();
    return storageClient.get(getBlobId(), getOpts) != null;
  }

  /**
   * Returns this blob's content.
   *
   * <p>Example of reading all bytes of the blob, if its generation matches the {@link
   * StorageObject#getGeneration()} value, otherwise a {@link StorageOperationException} is thrown.
   *
   * <pre>{@code
   * byte[] content = blob.getContent(BlobSourceOption.generationMatch());
   * }</pre>
   *
   * @param storageSettings blob read options
   * @throws StorageOperationException upon failure
   */
  public byte[] getContent(BlobSourceOptions... storageSettings) {
    return storageClient.readAllBytes(getBlobId(), toSourceOptionsArray(this, storageSettings));
  }

  /**
   * Fetches the latest blob properties. Returns {@code null} if the blob no longer exists.
   *
   * <p>{@code options} parameter can contain the preconditions. For example, the user might want to
   * get the blob properties only if the content has not been updated externally. {@code
   * StorageException} with the code {@code 412} is thrown if preconditions fail.
   *
   * <p>Example of retrieving the blob's latest information only if the content is not updated
   * externally:
   *
   * <pre>{@code
   * Blob blob = storage.get(BlobId.of(bucketName, blobName));
   *
   * doSomething();
   *
   * try {
   *   blob = blob.reload(Blob.BlobSourceOption.generationMatch());
   * } catch (StorageException e) {
   *   if (e.getCode() == 412) {
   *     // the content was updated externally
   *   } else {
   *     throw e;
   *   }
   * }
   * }</pre>
   *
   * @param storageSettings preconditions to use on reload, see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/get">https://cloud.google.com/storage/docs/json_api/v1/objects/get</a>
   *     for more information.
   * @return a {@code Blob} object with latest information or {@code null} if no longer exists.
   * @throws StorageOperationException upon failure
   */
  public StorageObject reloadFromStorage(BlobSourceOptions... storageSettings) {
    // BlobId with generation unset is needed to retrieve the latest version of the Blob
    BlobIdentifier baseId = BlobIdentifier.create(getBucket(), getName());
    return storageClient.get(baseId, toGetOptionsArray(this, storageSettings));
  }

  /**
   * Updates the blob properties. The {@code options} parameter contains the preconditions for
   * applying the update. To update the properties call {@link #asBuilder()}, set the properties you
   * want to change, build the new {@code Blob} instance, and then call {@link
   * #updateInStorage(StorageClient.BlobUploadOption...)}.
   *
   * <p>The property update details are described in {@link StorageClient#update(BlobAttributes)}. {@link
   * StorageClient#update(BlobAttributes, StorageClient.BlobUploadOption...)} describes how to specify preconditions.
   *
   * <p>Example of updating the content type:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.of(bucketName, blobName);
   * Blob blob = storage.get(blobId);
   * blob.toBuilder().setContentType("text/plain").build().update();
   * }</pre>
   *
   * @param storageSettings preconditions to apply the update
   * @return the updated {@code Blob}
   * @throws StorageOperationException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  public StorageObject updateInStorage(StorageClient.BlobUploadOption... storageSettings) {
    return storageClient.update(this, storageSettings);
  }

  /**
   * Deletes this blob.
   *
   * <p>Example of deleting the blob, if its generation matches the {@link StorageObject#getGeneration()}
   * value, otherwise a {@link StorageOperationException} is thrown.
   *
   * <pre>{@code
   * boolean deleted = blob.delete(BlobSourceOption.generationMatch());
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageSettings blob delete options
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageOperationException upon failure
   */
  public boolean deleteFromStorage(BlobSourceOptions... storageSettings) {
    return storageClient.delete(getBlobId(), toSourceOptionsArray(this, storageSettings));
  }

  /**
   * Sends a copy request for the current blob to the target blob. Possibly also some of the
   * metadata are copied (e.g. content-type).
   *
   * <p>Example of copying the blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * String blobName = "copy_blob_name";
   * CopyWriter copyWriter = blob.copyTo(BlobId.of(bucketName, blobName));
   * Blob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destinationBlobId target blob's id
   * @param storageSettings source blob options
   * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageOperationException upon failure
   */
  public BlobCopyWriter copyToTarget(BlobIdentifier destinationBlobId, BlobSourceOptions... storageSettings) {
    StorageClient.DataCopyRequest transferRequest =
        DataCopyRequest.builder()
            .setSource(getBucket(), getName())
            .setSourceOptions(toSourceOptionsArray(this, storageSettings))
            .setTarget(destinationBlobId)
            .buildCopyRequest();
    return storageClient.copy(transferRequest);
  }

  /**
   * Sends a copy request for the current blob to the target bucket, preserving its name. Possibly
   * copying also some of the metadata (e.g. content-type).
   *
   * <p>Example of copying the blob to a different bucket, keeping the original name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * CopyWriter copyWriter = blob.copyTo(bucketName);
   * Blob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destinationBucket target bucket's name
   * @param storageSettings source blob options
   * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageOperationException upon failure
   */
  public BlobCopyWriter copyToTarget(String destinationBucket, BlobSourceOptions... storageSettings) {
    return copyToTarget(destinationBucket, getName(), storageSettings);
  }

  /**
   * Sends a copy request for the current blob to the target blob. Possibly also some of the
   * metadata are copied (e.g. content-type).
   *
   * <p>Example of copying the blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * String blobName = "copy_blob_name";
   * CopyWriter copyWriter = blob.copyTo(bucketName, blobName);
   * Blob copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * <p>Example of moving a blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String destBucket = "my_unique_bucket";
   * String destBlob = "move_blob_name";
   * CopyWriter copyWriter = blob.copyTo(destBucket, destBlob);
   * Blob copiedBlob = copyWriter.getResult();
   * boolean deleted = blob.delete();
   * }</pre>
   *
   * @param destinationBucket target bucket's name
   * @param destinationBlobId target blob's name
   * @param storageSettings source blob options
   * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageOperationException upon failure
   */
  public BlobCopyWriter copyToTarget(String destinationBucket, String destinationBlobId, BlobSourceOptions... storageSettings) {
    return copyToTarget(BlobIdentifier.create(destinationBucket, destinationBlobId), storageSettings);
  }

  /**
   * Returns a {@code ReadChannel} object for reading this blob's content.
   *
   * <p>Example of reading the blob's content through a reader.
   *
   * <pre>{@code
   * try (ReadChannel reader = blob.reader()) {
   *   ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
   *   while (reader.read(bytes) > 0) {
   *     bytes.flip();
   *     // do something with bytes
   *     bytes.clear();
   *   }
   * }
   * }</pre>
   *
   * <p>Example of reading just a portion of the blob's content.
   *
   * <pre>{@code
   * int start = 1;
   * int end = 8;
   * try (ReadChannel reader = blob.reader()) {
   *   reader.seek(start);
   *   ByteBuffer bytes = ByteBuffer.allocate(end - start);
   *   reader.read(bytes);
   *   return bytes.array();
   * }
   * }</pre>
   *
   * @param storageSettings blob read options
   * @throws StorageOperationException upon failure
   */
  public ReadChannel openReader(BlobSourceOptions... storageSettings) {
    return storageClient.reader(getBlobId(), toSourceOptionsArray(this, storageSettings));
  }

  /**
   * Returns a {@code WriteChannel} object for writing to this blob. By default any md5 and crc32c
   * values in the current blob are ignored unless requested via the {@code
   * BlobWriteOption.md5Match} and {@code BlobWriteOption.crc32cMatch} options.
   *
   * <p>Example of writing the blob's content through a writer.
   *
   * <pre>{@code
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * try (WriteChannel writer = blob.writer()) {
   *     writer.write(ByteBuffer.wrap(content, 0, content.length));
   * } catch (IOException ex) {
   *   // handle exception
   * }
   * blob = blob.reload();
   * }</pre>
   *
   * @param storageSettings target blob options
   * @throws StorageOperationException upon failure
   */
  public WriteChannel openWriter(StorageClient.BlobWriteOptions... storageSettings) {
    return storageClient.writer(this, storageSettings);
  }

  /**
   * Generates a signed URL for this blob. If you want to allow access for a fixed amount of time to
   * this blob, you can use this method to generate a URL that is only valid within a certain time
   * period. This is particularly useful if you don't want publicly accessible blobs, but also don't
   * want to require users to explicitly log in. Signing a URL requires a service account signer. If
   * an instance of {@link com.google.auth.ServiceAccountSigner} was passed to {@link
   * StorageSettings}' builder via {@code setCredentials(Credentials)} or the default credentials are
   * being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set or your
   * application is running in App Engine, then {@code signUrl} will use that credentials to sign
   * the URL. If the credentials passed to {@link StorageSettings} do not implement {@link
   * ServiceAccountSigner} (this is the case, for instance, for Compute Engine credentials and
   * Google Cloud SDK credentials) then {@code signUrl} will throw an {@link IllegalStateException}
   * unless an implementation of {@link ServiceAccountSigner} is passed using the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option.
   *
   * <p>A service account signer is looked for in the following order:
   *
   * <ol>
   *   <li>The signer passed with the option {@link StorageClient.UrlSigningOption#withSigner(ServiceAccountSigner)}
   *   <li>The credentials passed to {@link StorageSettings}
   *   <li>The default credentials, if no credentials were passed to {@link StorageSettings}
   * </ol>
   *
   * <p>Example of creating a signed URL for the blob that is valid for 2 weeks, using the default
   * credentials for signing the URL:
   *
   * <pre>{@code
   * URL signedUrl = blob.signUrl(14, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example of creating a signed URL for the blob passing the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used to sign the URL:
   *
   * <pre>{@code
   * String keyPath = "/path/to/key.json";
   * URL signedUrl = blob.signUrl(14, TimeUnit.DAYS, SignUrlOption.signWith(
   *     ServiceAccountCredentials.fromStream(new FileInputStream(keyPath))));
   * }</pre>
   *
   * <p>Example of creating a signed URL for a blob generation:
   *
   * <pre>{@code
   * URL signedUrl = blob.signUrl(1, TimeUnit.HOURS,
   *     SignUrlOption.withQueryParams(ImmutableMap.of("generation", "1576656755290328")));
   * }</pre>
   *
   * @param timeToLive time until the signed URL expires, expressed in {@code unit}. The finer
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param timeMeasure time unit of the {@code duration} parameter
   * @param storageSettings optional URL signing options
   * @return a signed URL for this blob and the specified options
   * @throws IllegalStateException if {@link StorageClient.UrlSigningOption#withSigner(ServiceAccountSigner)} was not
   *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
   *     StorageSettings}
   * @throws IllegalArgumentException if {@code SignUrlOption.withMd5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code SignUrlOption.withContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  public URL generateSignedUrl(long timeToLive, TimeUnit timeMeasure, StorageClient.UrlSigningOption... storageSettings) {
    return storageClient.signUrl(this, timeToLive, timeMeasure, storageSettings);
  }

  /**
   * Returns the ACL entry for the specified entity on this blob or {@code null} if not found.
   *
   * <p>Example of getting the ACL entry for an entity.
   *
   * <pre>{@code
   * Acl acl = blob.getAcl(User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * @throws StorageOperationException upon failure
   */
  public AclEntry getAcl(AclEntry.TypedEntity principal) {
    return storageClient.getAcl(getBlobId(), principal);
  }

  /**
   * Deletes the ACL entry for the specified entity on this blob.
   *
   * <p>Example of deleting the ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = blob.deleteAcl(User.ofAllAuthenticatedUsers());
   * if (deleted) {
   *   // the acl entry was deleted
   * } else {
   *   // the acl entry was not found
   * }
   * }</pre>
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageOperationException upon failure
   */
  public boolean removeAcl(TypedEntity principal) {
    return storageClient.deleteAcl(getBlobId(), principal);
  }

  /**
   * Creates a new ACL entry on this blob.
   *
   * <p>Example of creating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.createAcl(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageOperationException upon failure
   */
  public AclEntry addAcl(AclEntry accessControlList) {
    return storageClient.createAcl(getBlobId(), accessControlList);
  }

  /**
   * Updates an ACL entry on this blob.
   *
   * <p>Example of updating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.updateAcl(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageOperationException upon failure
   */
  public AclEntry updateAclEntry(AclEntry accessControlList) {
    return storageClient.updateAcl(getBlobId(), accessControlList);
  }

  /**
   * Lists the ACL entries for this blob.
   *
   * <p>Example of listing the ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = blob.listAcls();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageOperationException upon failure
   */
  public List<AclEntry> listAclEntries() {
    return storageClient.listAcls(getBlobId());
  }

  /** Returns the blob's {@code Storage} object used to issue requests. */
  public StorageClient getStorage() {
    return storageClient;
  }

  @Override
  public StorageObject.BlobInfoBuilder asBuilder() {
    return new BlobInfoBuilder(this);
  }

  /**
   * Returns true if obj instance {@code Blob.toPb()} metadata representation and {@code
   * Blob.options} instance of StorageOptions are both equal.
   */
  @Override
  public final boolean equals(Object otherObject) {
    if (otherObject == this) {
      return true;
    }
    if (otherObject == null || !otherObject.getClass().equals(StorageObject.class)) {
      return false;
    }
    StorageObject thatObject = (StorageObject) otherObject;
    return Objects.equals(toProto(), thatObject.toProto()) && Objects.equals(storageSettings, thatObject.storageSettings);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), storageSettings);
  }

  private void readObject(ObjectInputStream objInputStream) throws IOException, ClassNotFoundException {
    objInputStream.defaultReadObject();
    this.storageClient = storageSettings.getService();
  }

  static StorageObject fromProto(StorageClient storageClient, com.google.api.services.storage.model.StorageObject protoWrapper) {
    BlobAttributes attributes = BlobAttributes.fromProto(protoWrapper);
    return new StorageObject(storageClient, new BlobInfoBuilderImpl(attributes));
  }
}
