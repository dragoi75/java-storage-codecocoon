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
import static com.google.cloud.storage.StorageObject.BlobSourceOptions.toBlobGetOptions;
import static com.google.cloud.storage.StorageObject.BlobSourceOptions.toBlobSourceOption;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.callable;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.cloud.storage.Storage.CopyRequest;
import com.google.cloud.storage.Storage.SignUrlOption;
import com.google.cloud.storage.spi.v1.StorageRpc;
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
 * A Google cloud storage object.
 *
 * <p>Objects of this class are immutable. Operations that modify the blob like {@link #updateInStorage} and
 * {@link #copyToTarget} return a new object. To get a {@code StorageObject} object with the most recent
 * information use {@link #refresh}. {@code StorageObject} adds a layer of service-related functionality over
 * {@link BlobInfo}.
 */
public class StorageObject extends BlobInfo {

  private static final long CLASS_VERSION_UID = -6806832496717441434L;

  private final StorageOptions storageSettings;
  private transient Storage cloudStore;

  static final Function<Tuple<Storage, com.google.api.services.storage.model.StorageObject>, StorageObject> BLOB_FROM_PAIR_FUNCTION =
      new Function<Tuple<Storage, com.google.api.services.storage.model.StorageObject>, StorageObject>() {
        @Override
        public StorageObject apply(Tuple<Storage, com.google.api.services.storage.model.StorageObject> pb) {
          return StorageObject.fromProto(pb.x(), pb.y());
        }
      };

  private static final int CHUNK_SIZE_DEFAULT = 2 * 1024 * 1024;

  /** Class for specifying blob source options when {@code StorageObject} methods are used. */
  public static class BlobSourceOptions extends Option {

    private static final long CLASS_VERSION_UID = 214616862061934846L;

    private BlobSourceOptions(StorageRpc.Option rpcParam) {
      super(rpcParam, null);
    }

    private BlobSourceOptions(StorageRpc.Option rpcParam, Object inputValue) {
      super(rpcParam, inputValue);
    }

    private Storage.BlobSourceOption toBlobSourceOption(BlobInfo info) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobSourceOption.generationMatch(info.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobSourceOption.generationNotMatch(info.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobSourceOption.metagenerationMatch(info.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobSourceOption.metagenerationNotMatch(info.getMetageneration());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobSourceOption.decryptionKey((String) getValue());
        case USER_PROJECT:
          return Storage.BlobSourceOption.userProject((String) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private Storage.BlobGetOption toBlobGetOption(BlobInfo info) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobGetOption.generationMatch(info.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobGetOption.generationNotMatch(info.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobGetOption.metagenerationMatch(info.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobGetOption.metagenerationNotMatch(info.getMetageneration());
        case USER_PROJECT:
          return Storage.BlobGetOption.userProject((String) getValue());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobGetOption.decryptionKey((String) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for blob's generation match. If this option is used the request will fail
     * if generation does not match.
     */
    public static BlobSourceOptions generationMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's generation mismatch. If this option is used the request will
     * fail if generation matches.
     */
    public static BlobSourceOptions generationNotMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobSourceOptions ifMetagenerationMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobSourceOptions metagenerationNotMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobSourceOptions customerSuppliedKey(Key customerSecret) {
      String encodedKeyString = BaseEncoding.base64().encode(customerSecret.getEncoded());
      return new BlobSourceOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedKeyString);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param customerSecret the AES256 encoded in base64
     */
    public static BlobSourceOptions customerSuppliedKey(String customerSecret) {
      return new BlobSourceOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, customerSecret);
    }

    /**
     * Returns an option for blob's billing user project. This option is used only if the blob's
     * bucket has requester_pays flag enabled.
     */
    public static BlobSourceOptions userProject(String userProjectId) {
      return new BlobSourceOptions(StorageRpc.Option.USER_PROJECT, userProjectId);
    }

    static Storage.BlobSourceOption[] toBlobSourceOption(
            BlobInfo info, BlobSourceOptions... storageSettings) {
      Storage.BlobSourceOption[] mappedOptions = new Storage.BlobSourceOption[storageSettings.length];
      int idx = 0;
      for (BlobSourceOptions opt : storageSettings) {
        mappedOptions[idx++] = opt.toBlobSourceOption(info);
      }
      return mappedOptions;
    }

    static Storage.BlobGetOption[] toBlobGetOptions(BlobInfo info, BlobSourceOptions... storageSettings) {
      Storage.BlobGetOption[] mappedOptions = new Storage.BlobGetOption[storageSettings.length];
      int idx = 0;
      for (BlobSourceOptions opt : storageSettings) {
        mappedOptions[idx++] = opt.toBlobGetOption(info);
      }
      return mappedOptions;
    }
  }

  /**
   * Downloads this blob to the given file path using specified blob read options.
   *
   * @param destinationPath destination
   * @param storageSettings blob read options
   * @throws StorageException upon failure
   */
  public void downloadInto(Path destinationPath, BlobSourceOptions... storageSettings) {
    try (OutputStream outStream = Files.newOutputStream(destinationPath)) {
      downloadInto(outStream, storageSettings);
    } catch (IOException ioEx) {
      throw new StorageException(ioEx);
    }
  }

  /**
   * Downloads this blob to the given output stream using specified blob read options.
   *
   * @param outStream
   * @param storageSettings
   */
  public void downloadInto(OutputStream outStream, BlobSourceOptions... storageSettings) {
    final CountingOutputStream countingOut = new CountingOutputStream(outStream);
    final StorageRpc rpcClient = this.storageSettings.getStorageRpcV1();
    final Map<StorageRpc.Option, ?> requestOptionsMap = StorageImpl.optionMap(getBlobId(), storageSettings);
    runWithRetries(
        callable(
            new Runnable() {
              @Override
              public void run() {
                rpcClient.read(
                    getBlobId().toPb(),
                        requestOptionsMap,
                    countingOut.getCount(),
                        countingOut);
              }
            }),
        this.storageSettings.getRetrySettings(),
        StorageImpl.EXCEPTION_HANDLER,
        this.storageSettings.getClock());
  }

  /**
   * Downloads this blob to the given file path.
   *
   * <p>This method is replaced with {@link #downloadInto(Path, BlobSourceOptions...)}, but is kept
   * here for binary compatibility with the older versions of the client library.
   *
   * @param destinationPath destination
   * @throws StorageException upon failure
   */
  public void downloadTo(Path destinationPath) {
    downloadInto(destinationPath, new BlobSourceOptions[0]);
  }

  /** BlobInfoBuilder for {@code StorageObject}. */
  public static class BlobInfoBuilder extends BlobInfo.Builder {

    private final Storage cloudStore;
    private final BlobInfo.BuilderImpl blobInfoBuilder;

    BlobInfoBuilder(StorageObject sourceObject) {
      this.cloudStore = sourceObject.getStorage();
      this.blobInfoBuilder = new BlobInfo.BuilderImpl(sourceObject);
    }

    @Override
    public StorageObject.BlobInfoBuilder setBlobId(BlobId identifier) {
      blobInfoBuilder.setBlobId(identifier);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setGeneratedId(String generatedIdentifier) {
      blobInfoBuilder.setGeneratedId(generatedIdentifier);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentType(String mimeType) {
      blobInfoBuilder.setContentType(mimeType);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentDisposition(String disposition) {
      blobInfoBuilder.setContentDisposition(disposition);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentLanguage(String language) {
      blobInfoBuilder.setContentLanguage(language);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentEncoding(String encoding) {
      blobInfoBuilder.setContentEncoding(encoding);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setComponentCount(Integer componentTotal) {
      blobInfoBuilder.setComponentCount(componentTotal);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCacheControl(String cachePolicy) {
      blobInfoBuilder.setCacheControl(cachePolicy);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setAcl(List<Acl> accessControl) {
      blobInfoBuilder.setAcl(accessControl);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setOwner(Entity ownerEntity) {
      blobInfoBuilder.setOwner(ownerEntity);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setSize(Long contentSize) {
      blobInfoBuilder.setSize(contentSize);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setEtag(String entityTag) {
      blobInfoBuilder.setEtag(entityTag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setSelfLink(String resourceLink) {
      blobInfoBuilder.setSelfLink(resourceLink);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMd5(String md5Hash) {
      blobInfoBuilder.setMd5(md5Hash);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMd5FromHexString(String md5Hex) {
      blobInfoBuilder.setMd5FromHexString(md5Hex);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCrc32c(String crc32cHash) {
      blobInfoBuilder.setCrc32c(crc32cHash);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCrc32cFromHexString(String crc32cHex) {
      blobInfoBuilder.setCrc32cFromHexString(crc32cHex);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setMediaLink(String mediaUrl) {
      blobInfoBuilder.setMediaLink(mediaUrl);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMetadata(Map<String, String> metaMap) {
      blobInfoBuilder.setMetadata(metaMap);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setStorageClass(StorageClass storageTier) {
      blobInfoBuilder.setStorageClass(storageTier);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setMetageneration(Long metaGeneration) {
      blobInfoBuilder.setMetageneration(metaGeneration);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setDeleteTime(Long deletionTime) {
      blobInfoBuilder.setDeleteTime(deletionTime);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setUpdateTime(Long lastUpdateTime) {
      blobInfoBuilder.setUpdateTime(lastUpdateTime);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setCreateTime(Long creationTime) {
      blobInfoBuilder.setCreateTime(creationTime);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
      blobInfoBuilder.setIsDirectory(directoryFlag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryption customerEnc) {
      blobInfoBuilder.setCustomerEncryption(customerEnc);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setKmsKeyName(String kmsKeyIdentifier) {
      blobInfoBuilder.setKmsKeyName(kmsKeyIdentifier);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
      blobInfoBuilder.setEventBasedHold(eventHold);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setTemporaryHold(Boolean tempHold) {
      blobInfoBuilder.setTemporaryHold(tempHold);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
      blobInfoBuilder.setRetentionExpirationTime(retentionExpiryTime);
      return this;
    }

    @Override
    public StorageObject build() {
      return new StorageObject(cloudStore, blobInfoBuilder);
    }
  }

  StorageObject(Storage cloudStore, BlobInfo.BuilderImpl blobInfoBuilder) {
    super(blobInfoBuilder);
    this.cloudStore = checkNotNull(cloudStore);
    this.storageSettings = cloudStore.getOptions();
  }

  /**
   * Checks if this blob existsInStorage.
   *
   * <p>Example of checking if the blob existsInStorage.
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
   * @param storageSettings blob read options
   * @return true if this blob existsInStorage, false otherwise
   * @throws StorageException upon failure
   */
  public boolean existsInStorage(BlobSourceOptions... storageSettings) {
    int len = storageSettings.length;
    Storage.BlobGetOption[] retrievalOptions = Arrays.copyOf(toBlobGetOptions(this, storageSettings), len + 1);
    retrievalOptions[len] = Storage.BlobGetOption.fields();
    return cloudStore.get(getBlobId(), retrievalOptions) != null;
  }

  /**
   * Returns this blob's content.
   *
   * <p>Example of reading all bytes of the blob, if its generation matches the {@link
   * StorageObject#getGeneration()} value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * byte[] content = blob.getContent(BlobSourceOptions.generationMatch());
   * }</pre>
   *
   * @param storageSettings blob read options
   * @throws StorageException upon failure
   */
  public byte[] getContent(BlobSourceOptions... storageSettings) {
    return cloudStore.readAllBytes(getBlobId(), toBlobSourceOption(this, storageSettings));
  }

  /**
   * Fetches current blob's latest information. Returns {@code null} if the blob does not exist.
   *
   * <p>Example of getting the blob's latest information, if its generation does not match the
   * {@link StorageObject#getGeneration()} value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * StorageObject latestBlob = blob.refresh(BlobSourceOptions.generationNotMatch());
   * if (latestBlob == null) {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageSettings blob read options
   * @return a {@code StorageObject} object with latest information or {@code null} if not found
   * @throws StorageException upon failure
   */
  public StorageObject refresh(BlobSourceOptions... storageSettings) {
    return cloudStore.get(getBlobId(), toBlobGetOptions(this, storageSettings));
  }

  /**
   * Updates the blob's information. Bucket or blob's name cannot be changed by this method. If you
   * want to rename the blob or move it to a different bucket use the {@link #copyToTarget} and {@link
   * #remove} operations. A new {@code StorageObject} object is returned. By default no checks are made on
   * the metadata generation of the current blob. If you want to updateInStorage the information only if the
   * current blob metadata are at their latest version use the {@code ifMetagenerationMatch} option:
   * {@code newBlob.updateInStorage(BlobTargetOption.ifMetagenerationMatch())}.
   *
   * <p>Original metadata are merged with metadata in the provided {@code blobInfo}. If the original
   * metadata already contains a key specified in the provided {@code blobInfo's} metadata map, it
   * will be replaced by the new value. Removing metadata can be done by setting that metadata's
   * value to {@code null}.
   *
   * <p>Example of adding new metadata values or updating existing ones.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * String blobName = "my_blob_name";
   * Map<String, String> newMetadata = new HashMap<>();
   * newMetadata.put("keyToAddOrUpdate", "value");
   * StorageObject blob = storage.updateInStorage(BlobInfo.newBuilder(bucketName, blobName)
   *     .setMetadata(newMetadata)
   *     .build());
   * }</pre>
   *
   * <p>Example of removing metadata values.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * String blobName = "my_blob_name";
   * Map<String, String> newMetadata = new HashMap<>();
   * newMetadata.put("keyToRemove", null);
   * StorageObject blob = storage.updateInStorage(BlobInfo.newBuilder(bucketName, blobName)
   *     .setMetadata(newMetadata)
   *     .build());
   * }</pre>
   *
   * @param storageSettings updateInStorage options
   * @return a {@code StorageObject} object with updated information
   * @throws StorageException upon failure
   */
  public StorageObject updateInStorage(BlobTargetOption... storageSettings) {
    return cloudStore.update(this, storageSettings);
  }

  /**
   * Deletes this blob.
   *
   * <p>Example of deleting the blob, if its generation matches the {@link StorageObject#getGeneration()}
   * value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * boolean deleted = blob.remove(BlobSourceOptions.generationMatch());
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageSettings blob remove options
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean remove(BlobSourceOptions... storageSettings) {
    return cloudStore.delete(getBlobId(), toBlobSourceOption(this, storageSettings));
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
   * CopyWriter copyWriter = blob.copyToTarget(BlobId.of(bucketName, blobName));
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destinationBlobId target blob's id
   * @param storageSettings source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToTarget(BlobId destinationBlobId, BlobSourceOptions... storageSettings) {
    CopyRequest copyReq =
        CopyRequest.newBuilder()
            .setSource(getBucket(), getName())
            .setSourceOptions(toBlobSourceOption(this, storageSettings))
            .setTarget(destinationBlobId)
            .build();
    return cloudStore.copy(copyReq);
  }

  /**
   * Sends a copy request for the current blob to the target bucket, preserving its name. Possibly
   * copying also some of the metadata (e.g. content-type).
   *
   * <p>Example of copying the blob to a different bucket, keeping the original name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * CopyWriter copyWriter = blob.copyToTarget(bucketName);
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destinationBucket target bucket's name
   * @param storageSettings source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToTarget(String destinationBucket, BlobSourceOptions... storageSettings) {
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
   * CopyWriter copyWriter = blob.copyToTarget(bucketName, blobName);
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * <p>Example of moving a blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String destBucket = "my_unique_bucket";
   * String destBlob = "move_blob_name";
   * CopyWriter copyWriter = blob.copyToTarget(destBucket, destBlob);
   * StorageObject copiedBlob = copyWriter.getResult();
   * boolean deleted = blob.remove();
   * }</pre>
   *
   * @param destinationBucket target bucket's name
   * @param destinationBlobId target blob's name
   * @param storageSettings source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToTarget(String destinationBucket, String destinationBlobId, BlobSourceOptions... storageSettings) {
    return copyToTarget(BlobId.of(destinationBucket, destinationBlobId), storageSettings);
  }

  /**
   * Returns a {@code ReadChannel} object for reading this blob's content.
   *
   * <p>Example of reading the blob's content through a getReader.
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
   * <p>Example of reading just a portion of the blob's content.
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
   * @param storageSettings blob read options
   * @throws StorageException upon failure
   */
  public ReadChannel getReader(BlobSourceOptions... storageSettings) {
    return cloudStore.reader(getBlobId(), toBlobSourceOption(this, storageSettings));
  }

  /**
   * Returns a {@code WriteChannel} object for writing to this blob. By default any md5 and crc32c
   * values in the current blob are ignored unless requested via the {@code
   * BlobWriteOption.md5Match} and {@code BlobWriteOption.crc32cMatch} options.
   *
   * <p>Example of writing the blob's content through a getWriter.
   *
   * <pre>{@code
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * try (WriteChannel getWriter = blob.getWriter()) {
   *   try {
   *     getWriter.write(ByteBuffer.wrap(content, 0, content.length));
   *   } catch (Exception ex) {
   *     // handle exception
   *   }
   * }
   * }</pre>
   *
   * @param storageSettings target blob options
   * @throws StorageException upon failure
   */
  public WriteChannel getWriter(BlobWriteOption... storageSettings) {
    return cloudStore.writer(this, storageSettings);
  }

  /**
   * Generates a signed URL for this blob. If you want to allow access for a fixed amount of time to
   * this blob, you can use this method to generate a URL that is only valid within a certain time
   * period. This is particularly useful if you don't want publicly accessible blobs, but also don't
   * want to require users to explicitly log in. Signing a URL requires a service account signer. If
   * an instance of {@link com.google.auth.ServiceAccountSigner} was passed to {@link
   * StorageOptions}' builder via {@code setCredentials(Credentials)} or the default credentials are
   * being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set or your
   * application is running in App Engine, then {@code generateSignedUrl} will use that credentials to sign
   * the URL. If the credentials passed to {@link StorageOptions} do not implement {@link
   * ServiceAccountSigner} (this is the case, for instance, for Compute Engine credentials and
   * Google Cloud SDK credentials) then {@code generateSignedUrl} will throw an {@link IllegalStateException}
   * unless an implementation of {@link ServiceAccountSigner} is passed using the {@link
   * SignUrlOption#signWith(ServiceAccountSigner)} option.
   *
   * <p>A service account signer is looked for in the following order:
   *
   * <ol>
   *   <li>The signer passed with the option {@link SignUrlOption#signWith(ServiceAccountSigner)}
   *   <li>The credentials passed to {@link StorageOptions}
   *   <li>The default credentials, if no credentials were passed to {@link StorageOptions}
   * </ol>
   *
   * <p>Example of creating a signed URL for the blob that is valid for 2 weeks, using the default
   * credentials for signing the URL:
   *
   * <pre>{@code
   * URL signedUrl = blob.generateSignedUrl(14, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example of creating a signed URL for the blob passing the {@link
   * SignUrlOption#signWith(ServiceAccountSigner)} option, that will be used to sign the URL:
   *
   * <pre>{@code
   * String keyPath = "/path/to/key.json";
   * URL signedUrl = blob.generateSignedUrl(14, TimeUnit.DAYS, SignUrlOption.signWith(
   *     ServiceAccountCredentials.fromStream(new FileInputStream(keyPath))));
   * }</pre>
   *
   * <p>Example of creating a signed URL for a blob generation:
   *
   * <pre>{@code
   * URL signedUrl = blob.generateSignedUrl(1, TimeUnit.HOURS,
   *     SignUrlOption.withQueryParams(ImmutableMap.of("generation", "1576656755290328")));
   * }</pre>
   *
   * @param timeDuration time until the signed URL expires, expressed in {@code unit}. The finer
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param timeUnit time unit of the {@code duration} parameter
   * @param storageSettings optional URL signing options
   * @return a signed URL for this blob and the specified options
   * @throws IllegalStateException if {@link SignUrlOption#signWith(ServiceAccountSigner)} was not
   *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code SignUrlOption.withMd5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code SignUrlOption.withContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  public URL generateSignedUrl(long timeDuration, TimeUnit timeUnit, SignUrlOption... storageSettings) {
    return cloudStore.signUrl(this, timeDuration, timeUnit, storageSettings);
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
   * @throws StorageException upon failure
   */
  public Acl getAcl(Entity entityPrincipal) {
    return cloudStore.getAcl(getBlobId(), entityPrincipal);
  }

  /**
   * Deletes the ACL entry for the specified entity on this blob.
   *
   * <p>Example of deleting the ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = blob.removeAclEntry(User.ofAllAuthenticatedUsers());
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
  public boolean removeAclEntry(Entity entityPrincipal) {
    return cloudStore.deleteAcl(getBlobId(), entityPrincipal);
  }

  /**
   * Creates a new ACL entry on this blob.
   *
   * <p>Example of creating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.createAclForBlob(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl createAclForBlob(Acl accessControl) {
    return cloudStore.createAcl(getBlobId(), accessControl);
  }

  /**
   * Updates an ACL entry on this blob.
   *
   * <p>Example of updating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.updateAccessControlList(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl updateAccessControlList(Acl accessControl) {
    return cloudStore.updateAcl(getBlobId(), accessControl);
  }

  /**
   * Lists the ACL entries for this blob.
   *
   * <p>Example of listing the ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = blob.getAcls();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public List<Acl> getAcls() {
    return cloudStore.listAcls(getBlobId());
  }

  /** Returns the blob's {@code Storage} object used to issue requests. */
  public Storage getStorage() {
    return cloudStore;
  }

  @Override
  public StorageObject.BlobInfoBuilder toBuilder() {
    return new BlobInfoBuilder(this);
  }

  @Override
  public final boolean equals(Object otherObj) {
    if (otherObj == this) {
      return true;
    }
    if (otherObj == null || !otherObj.getClass().equals(StorageObject.class)) {
      return false;
    }
    StorageObject otherStorageObject = (StorageObject) otherObj;
    return Objects.equals(toPb(), otherStorageObject.toPb()) && Objects.equals(storageSettings, otherStorageObject.storageSettings);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), storageSettings);
  }

  private void readObjectData(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
    inputStream.defaultReadObject();
    this.cloudStore = storageSettings.getService();
  }

  static StorageObject fromProto(Storage cloudStore, com.google.api.services.storage.model.StorageObject storageEntity) {
    BlobInfo blobInfoLocal = BlobInfo.fromPb(storageEntity);
    return new StorageObject(cloudStore, new BlobInfo.BuilderImpl(blobInfoLocal));
  }
}
