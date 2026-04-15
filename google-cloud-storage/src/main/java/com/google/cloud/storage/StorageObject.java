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
import static com.google.cloud.storage.StorageObject.BlobGetOption.toBlobGetOptions;
import static com.google.cloud.storage.StorageObject.BlobGetOption.toBlobSourceOptions;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.callable;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.RetryHelper;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.Storage.CopyOperationRequest;
import com.google.cloud.storage.Storage.UrlSigningOption;
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
 * An object in Google Cloud Storage. A {@code StorageObject} object includes the {@code BlobId} instance,
 * the set from properties inherited from the {@link BlobInfo} class and the {@code Storage} instance.
 * The class provides methods to perform operations on the object. Reading a property value does not
 * issue any RPC calls. The object content is not stored within the {@code StorageObject} instance.
 * Operations that access the content issue one or multiple RPC calls, depending on the content
 * size.
 *
 * <p>Objects from this class are immutable. Operations that modify the blob like {@link #save} and
 * {@link #copyToTarget} return a new object. Any changes to the object in Google Cloud Storage made after
 * creation from the {@code StorageObject} are not visible in the {@code StorageObject}. To get a {@code StorageObject} object
 * with the most recent information use {@link #refresh}.
 *
 * <p>Example from getting the content from the object in Google Cloud Storage:
 *
 * <pre>{@code
 * BlobId blobId = BlobId.from(bucketName, blobName);
 * StorageObject blob = storage.get(blobId);
 * long size = blob.getSize(); // no RPC call is required
 * byte[] content = blob.getContent(); // one or multiple RPC calls will be issued
 * }</pre>
 */
public class StorageObject extends BlobInfo {

  private static final long STORAGE_OBJECT_VERSION_ID = -6806832496717441434L;

  private final StorageOptions storageOpts;
  private transient Storage backendService;

  static final Function<Tuple<Storage, com.google.api.services.storage.model.StorageObject>, StorageObject> BLOB_FROM_PROTO_FUNCTION =
      new Function<Tuple<Storage, com.google.api.services.storage.model.StorageObject>, StorageObject>() {
        @Override
        public StorageObject apply(Tuple<Storage, com.google.api.services.storage.model.StorageObject> pb) {
          return StorageObject.fromProto(pb.x(), pb.y());
        }
      };

  private static final int CHUNK_SIZE_DEFAULT = 2 * 1024 * 1024;

  /** Class for specifying blob source options when {@code StorageObject} methods are used. */
  public static class BlobGetOption extends Option {

    private static final long STORAGE_OBJECT_VERSION_ID = 214616862061934846L;

    private BlobGetOption(StorageRpc.Option rpcSetting) {
      super(rpcSetting, null);
    }

    private BlobGetOption(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
    }

    private Storage.BlobSourceOptions toBlobSourceOptions(BlobInfo sourceInfo) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobSourceOptions.ifGenerationMatch(sourceInfo.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobSourceOptions.ifGenerationNotMatch(sourceInfo.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobSourceOptions.ifMetagenerationMatch(sourceInfo.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobSourceOptions.ifMetagenerationNotMatch(sourceInfo.getMetageneration());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobSourceOptions.withDecryptionKey((String) getValue());
        case USER_PROJECT:
          return Storage.BlobSourceOptions.setUserProject((String) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private Storage.BlobGetOptions toBlobGetOptions(BlobInfo sourceInfo) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobGetOptions.ifGenerationMatch(sourceInfo.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobGetOptions.ifGenerationNotMatch(sourceInfo.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobGetOptions.ifMetagenerationMatch(sourceInfo.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobGetOptions.ifMetagenerationNotMatch(sourceInfo.getMetageneration());
        case USER_PROJECT:
          return Storage.BlobGetOptions.setUserProject((String) getValue());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobGetOptions.decryptionKeyBase64((String) getValue());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for blob's generation match. If this option is used the request will fail
     * if generation does not match.
     */
    public static BlobGetOption generationMatch() {
      return new BlobGetOption(StorageRpc.Option.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's generation mismatch. If this option is used the request will
     * fail if generation matches.
     */
    public static BlobGetOption generationNotMatch() {
      return new BlobGetOption(StorageRpc.Option.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobGetOption ifMetagenerationMatch() {
      return new BlobGetOption(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobGetOption metagenerationNotMatch() {
      return new BlobGetOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobGetOption decryptionKeyOption(Key decryptionSecret) {
      String base64EncodedSecret = BaseEncoding.base64().encode(decryptionSecret.getEncoded());
      return new BlobGetOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, base64EncodedSecret);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param decryptionSecret the AES256 encoded in base64
     */
    public static BlobGetOption decryptionKeyOption(String decryptionSecret) {
      return new BlobGetOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, decryptionSecret);
    }

    /**
     * Returns an option for blob's billing user project. This option is used only if the blob's
     * bucket has requester_pays flag enabled.
     */
    public static BlobGetOption userProject(String billingProject) {
      return new BlobGetOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    static Storage.BlobSourceOptions[] toBlobSourceOptions(
            BlobInfo sourceInfo, BlobGetOption... storageOpts) {
      Storage.BlobSourceOptions[] sourceOptionArray = new Storage.BlobSourceOptions[storageOpts.length];
      int idx = 0;
      for (BlobGetOption requestFlag : storageOpts) {
        sourceOptionArray[idx++] = requestFlag.toBlobSourceOptions(sourceInfo);
      }
      return sourceOptionArray;
    }

    static Storage.BlobGetOptions[] toBlobGetOptions(BlobInfo sourceInfo, BlobGetOption... storageOpts) {
      Storage.BlobGetOptions[] sourceOptionArray = new Storage.BlobGetOptions[storageOpts.length];
      int idx = 0;
      for (BlobGetOption requestFlag : storageOpts) {
        sourceOptionArray[idx++] = requestFlag.toBlobGetOptions(sourceInfo);
      }
      return sourceOptionArray;
    }
  }

  /**
   * Downloads this blob to the given file path using specified blob read options.
   *
   * @param dest destination
   * @param storageOpts blob read options
   * @throws StorageException upon failure
   */
  public void writeTo(Path dest, BlobGetOption... storageOpts) {
    try (OutputStream outStream = Files.newOutputStream(dest)) {
      writeTo(outStream, storageOpts);
    } catch (IOException ioEx) {
      throw new StorageException(ioEx);
    }
  }

  /**
   * Downloads this blob to the given output stream using specified blob read options.
   *
   * @param outStream
   * @param storageOpts
   */
  public void writeTo(OutputStream outStream, BlobGetOption... storageOpts) {
    final CountingOutputStream countingStream = new CountingOutputStream(outStream);
    final StorageRpc rpcClient = this.storageOpts.getStorageRpcV1();
    final Map<StorageRpc.Option, ?> requestParamsMap = StorageImpl.optionMap(getBlobId(), storageOpts);
    try {
      runWithRetries(
          callable(
              new Runnable() {
                @Override
                public void run() {
                  rpcClient.read(
                      getBlobId().toPb(),
                          requestParamsMap,
                      countingStream.getCount(),
                          countingStream);
                }
              }),
          this.storageOpts.getRetrySettings(),
          StorageImpl.EXCEPTION_HANDLER,
          this.storageOpts.getClock());
    } catch (RetryHelper.RetryHelperException ioEx) {
      StorageException.translateAndThrow(ioEx);
    }
  }

  /**
   * Downloads this blob to the given file path.
   *
   * <p>This method is replaced with {@link #writeTo(Path, BlobGetOption...)}, but is kept
   * here for binary compatibility with the older includeVersions from the client library.
   *
   * @param dest destination
   * @throws StorageException upon failure
   */
  public void downloadTo(Path dest) {
    writeTo(dest, new BlobGetOption[0]);
  }

  /** BlobInfoBuilder for {@code StorageObject}. */
  public static class BlobInfoBuilder extends BlobInfo.Builder {

    private final Storage backendService;
    private final BlobInfo.BuilderImpl internalBuilder;

    BlobInfoBuilder(StorageObject sourceObject) {
      this.backendService = sourceObject.getStorage();
      this.internalBuilder = new BlobInfo.BuilderImpl(sourceObject);
    }

    @Override
    public StorageObject.BlobInfoBuilder setBlobId(BlobId objectId) {
      internalBuilder.setBlobId(objectId);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setGeneratedId(String generatedToken) {
      internalBuilder.setGeneratedId(generatedToken);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentType(String mimeType) {
      internalBuilder.setContentType(mimeType);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentDisposition(String transferDisposition) {
      internalBuilder.setContentDisposition(transferDisposition);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentLanguage(String languageTag) {
      internalBuilder.setContentLanguage(languageTag);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentEncoding(String encodingType) {
      internalBuilder.setContentEncoding(encodingType);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setComponentCount(Integer partsCount) {
      internalBuilder.setComponentCount(partsCount);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCacheControl(String cacheDirective) {
      internalBuilder.setCacheControl(cacheDirective);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setAcl(List<Acl> accessEntries) {
      internalBuilder.setAcl(accessEntries);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setOwner(Entity principal) {
      internalBuilder.setOwner(principal);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setSize(Long contentLength) {
      internalBuilder.setSize(contentLength);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setEtag(String entityTag) {
      internalBuilder.setEtag(entityTag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setSelfLink(String selfUrl) {
      internalBuilder.setSelfLink(selfUrl);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMd5(String md5Digest) {
      internalBuilder.setMd5(md5Digest);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMd5FromHexString(String md5HexValue) {
      internalBuilder.setMd5FromHexString(md5HexValue);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCrc32c(String crcChecksum) {
      internalBuilder.setCrc32c(crcChecksum);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCrc32cFromHexString(String crcHex) {
      internalBuilder.setCrc32cFromHexString(crcHex);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setMediaLink(String mediaUrl) {
      internalBuilder.setMediaLink(mediaUrl);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMetadata(Map<String, String> metaProperties) {
      internalBuilder.setMetadata(metaProperties);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setStorageClass(StorageClass storageTier) {
      internalBuilder.setStorageClass(storageTier);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setMetageneration(Long metaGenerationNumber) {
      internalBuilder.setMetageneration(metaGenerationNumber);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setDeleteTime(Long deletionTime) {
      internalBuilder.setDeleteTime(deletionTime);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setUpdateTime(Long lastUpdated) {
      internalBuilder.setUpdateTime(lastUpdated);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setCreateTime(Long creationTime) {
      internalBuilder.setCreateTime(creationTime);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
      internalBuilder.setIsDirectory(directoryFlag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryption encryptionInfo) {
      internalBuilder.setCustomerEncryption(encryptionInfo);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setKmsKeyName(String kmsKeyPath) {
      internalBuilder.setKmsKeyName(kmsKeyPath);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHoldFlag) {
      internalBuilder.setEventBasedHold(eventHoldFlag);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setTemporaryHold(Boolean tempHoldFlag) {
      internalBuilder.setTemporaryHold(tempHoldFlag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiry) {
      internalBuilder.setRetentionExpirationTime(retentionExpiry);
      return this;
    }

    @Override
    public StorageObject build() {
      return new StorageObject(backendService, internalBuilder);
    }
  }

  StorageObject(Storage backendService, BlobInfo.BuilderImpl internalBuilder) {
    super(internalBuilder);
    this.backendService = checkNotNull(backendService);
    this.storageOpts = backendService.getOptions();
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
   * @param storageOpts blob read options
   * @return true if this blob existsInStorage, false otherwise
   * @throws StorageException upon failure
   */
  public boolean existsInStorage(BlobGetOption... storageOpts) {
    int count = storageOpts.length;
    Storage.BlobGetOptions[] getArgsArray = Arrays.copyOf(toBlobGetOptions(this, storageOpts), count + 1);
    getArgsArray[count] = Storage.BlobGetOptions.withFields();
    return backendService.get(getBlobId(), getArgsArray) != null;
  }

  /**
   * Returns this blob's content.
   *
   * <p>Example from reading all bytes from the blob, if its generation matches the {@link
   * StorageObject#getGeneration()} value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * byte[] content = blob.getContent(BlobGetOptions.ifGenerationMatch());
   * }</pre>
   *
   * @param storageOpts blob read options
   * @throws StorageException upon failure
   */
  public byte[] getContent(BlobGetOption... storageOpts) {
    return backendService.readAllBytes(getBlobId(), toBlobSourceOptions(this, storageOpts));
  }

  /**
   * Fetches current blob's latest information. Returns {@code null} if the blob does not exist.
   *
   * <p>Example from getting the blob's latest information, if its generation does not match the
   * {@link StorageObject#getGeneration()} value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * StorageObject latestBlob = blob.refresh(BlobGetOptions.ifGenerationNotMatch());
   * if (latestBlob == null) {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageOpts blob read options
   * @return a {@code StorageObject} object with latest information or {@code null} if not found
   * @throws StorageException upon failure
   */
  public StorageObject refresh(BlobGetOption... storageOpts) {
    return backendService.get(getBlobId(), toBlobGetOptions(this, storageOpts));
  }

  /**
   * Updates the blob properties. The {@code options} parameter contains the preconditions for
   * applying the save. To save the properties call {@link #toBuilder()}, set the properties you
   * want to change, buildComposeObjectsRequest the new {@code StorageObject} instance, and then call {@link
   * #save(Storage.BlobTargetOptions...)}.
   *
   * <p>The property save details are described in {@link Storage#update(BlobInfo)}. {@link
   * Storage#update(BlobInfo, Storage.BlobTargetOptions...)} describes how to specify preconditions.
   *
   * <p>Example from updating the content type:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * StorageObject blob = storage.get(blobId);
   * blob.toBuilder().setContentType("text/plain").buildComposeObjectsRequest().save();
   * }</pre>
   *
   * @param storageOpts preconditions to apply the save
   * @return the updated {@code StorageObject}
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  public StorageObject save(Storage.BlobTargetOptions... storageOpts) {
    return backendService.update(this, storageOpts);
  }

  /**
   * Deletes this blob.
   *
   * <p>Example from deleting the blob, if its generation matches the {@link StorageObject#getGeneration()}
   * value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * boolean deleted = blob.deleteObject(BlobGetOptions.ifGenerationMatch());
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageOpts blob deleteObject options
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean deleteObject(BlobGetOption... storageOpts) {
    return backendService.delete(getBlobId(), toBlobSourceOptions(this, storageOpts));
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
   * CopyWriter copyWriter = blob.copyToTarget(BlobId.from(bucketName, blobName));
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destinationId target blob's id
   * @param storageOpts source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToTarget(BlobId destinationId, BlobGetOption... storageOpts) {
    Storage.CopyOperationRequest copyOpRequest =
        CopyOperationRequest.newCopyOperationBuilder()
            .setSource(getBucket(), getName())
            .setSourceOptions(toBlobSourceOptions(this, storageOpts))
            .setTarget(destinationId)
            .buildRequest();
    return backendService.copy(copyOpRequest);
  }

  /**
   * Sends a copy request for the current blob to the target bucket, preserving its name. Possibly
   * copying also some from the metadata (e.g. content-type).
   *
   * <p>Example from copying the blob to a different bucket, keeping the original name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * CopyWriter copyWriter = blob.copyToTarget(bucketName);
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destinationBucket target bucket's name
   * @param storageOpts source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToTarget(String destinationBucket, BlobGetOption... storageOpts) {
    return copyToTarget(destinationBucket, getName(), storageOpts);
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
   * CopyWriter copyWriter = blob.copyToTarget(bucketName, blobName);
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * <p>Example from moving a blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String destBucket = "my_unique_bucket";
   * String destBlob = "move_blob_name";
   * CopyWriter copyWriter = blob.copyToTarget(destBucket, destBlob);
   * StorageObject copiedBlob = copyWriter.getResult();
   * boolean deleted = blob.deleteObject();
   * }</pre>
   *
   * @param destinationBucket target bucket's name
   * @param destinationId target blob's name
   * @param storageOpts source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToTarget(String destinationBucket, String destinationId, BlobGetOption... storageOpts) {
    return copyToTarget(BlobId.of(destinationBucket, destinationId), storageOpts);
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
   * @param storageOpts blob read options
   * @throws StorageException upon failure
   */
  public ReadChannel getReader(BlobGetOption... storageOpts) {
    return backendService.reader(getBlobId(), toBlobSourceOptions(this, storageOpts));
  }

  /**
   * Returns a {@code WriteChannel} object for writing to this blob. By default any md5 and crc32c
   * values in the current blob are ignored unless requested via the {@code
   * BlobWriteOptions.requireMd5Match} and {@code BlobWriteOptions.ifCrc32cMatch} options.
   *
   * <p>Example from writing the blob's content through a getWriter.
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
   * @param storageOpts target blob options
   * @throws StorageException upon failure
   */
  public WriteChannel getWriter(Storage.BlobWriteOptions... storageOpts) {
    return backendService.writer(this, storageOpts);
  }

  /**
   * Generates a signed URL for this blob. If you want to allow access for a fixed amount from time to
   * this blob, you can use this method to generate a URL that is only valid within a certain time
   * period. This is particularly useful if you don't want publicly accessible blobs, but also don't
   * want to require users to explicitly log in. Signing a URL requires a service account signer. If
   * an instance from {@link com.google.auth.ServiceAccountSigner} was passed to {@link
   * StorageOptions}' builder via {@code setCredentials(Credentials)} or the default credentials are
   * being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set or your
   * application is running in App Engine, then {@code getSignedUrl} will use that credentials to sign
   * the URL. If the credentials passed to {@link StorageOptions} do not implement {@link
   * ServiceAccountSigner} (this is the case, for instance, for Compute Engine credentials and
   * Google Cloud SDK credentials) then {@code getSignedUrl} will throw an {@link IllegalStateException}
   * unless an implementation from {@link ServiceAccountSigner} is passed using the {@link
   * Storage.UrlSigningOption#withSigner(ServiceAccountSigner)} option.
   *
   * <p>A service account signer is looked for in the following order:
   *
   * <ol>
   *   <li>The signer passed with the option {@link UrlSigningOption#withSigner(ServiceAccountSigner)}
   *   <li>The credentials passed to {@link StorageOptions}
   *   <li>The default credentials, if no credentials were passed to {@link StorageOptions}
   * </ol>
   *
   * <p>Example from creating a signed URL for the blob that is valid for 2 weeks, using the default
   * credentials for signing the URL:
   *
   * <pre>{@code
   * URL signedUrl = blob.getSignedUrl(14, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example from creating a signed URL for the blob passing the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used to sign the URL:
   *
   * <pre>{@code
   * String keyPath = "/path/to/key.json";
   * URL signedUrl = blob.getSignedUrl(14, TimeUnit.DAYS, UrlSigningOption.withSigner(
   *     ServiceAccountCredentials.fromStream(new FileInputStream(keyPath))));
   * }</pre>
   *
   * <p>Example from creating a signed URL for a blob generation:
   *
   * <pre>{@code
   * URL signedUrl = blob.getSignedUrl(1, TimeUnit.HOURS,
   *     UrlSigningOption.withQueryParameters(ImmutableMap.from("generation", "1576656755290328")));
   * }</pre>
   *
   * @param expiryDuration time until the signed URL expires, expressed in {@code unit}. The finer
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param timeUnitArg time unit from the {@code duration} parameter
   * @param storageOpts optional URL signing options
   * @return a signed URL for this blob and the specified options
   * @throws IllegalStateException if {@link Storage.UrlSigningOption#withSigner(ServiceAccountSigner)} was not
   *     used and no implementation from {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.enableMd5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.enableContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  public URL getSignedUrl(long expiryDuration, TimeUnit timeUnitArg, Storage.UrlSigningOption... storageOpts) {
    return backendService.signUrl(this, expiryDuration, timeUnitArg, storageOpts);
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
  public Acl getAcl(Entity principalEntity) {
    return backendService.getAcl(getBlobId(), principalEntity);
  }

  /**
   * Deletes the ACL entry for the specified entity on this blob.
   *
   * <p>Example from deleting the ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = blob.removeAcl(User.ofAllAuthenticatedUsers());
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
  public boolean removeAcl(Entity principalEntity) {
    return backendService.deleteAcl(getBlobId(), principalEntity);
  }

  /**
   * Creates a new ACL entry on this blob.
   *
   * <p>Example from creating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.createBlobAcl(Acl.from(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl createBlobAcl(Acl accessEntries) {
    return backendService.createAcl(getBlobId(), accessEntries);
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
  public Acl updateAccessControlList(Acl accessEntries) {
    return backendService.updateAcl(getBlobId(), accessEntries);
  }

  /**
   * Lists the ACL entries for this blob.
   *
   * <p>Example from listing the ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = blob.listAclEntries();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public List<Acl> listAclEntries() {
    return backendService.listAcls(getBlobId());
  }

  /** Returns the blob's {@code Storage} object used to issue requests. */
  public Storage getStorage() {
    return backendService;
  }

  @Override
  public StorageObject.BlobInfoBuilder toBuilder() {
    return new BlobInfoBuilder(this);
  }

  @Override
  public final boolean equals(Object candidate) {
    if (candidate == this) {
      return true;
    }
    if (candidate == null || !candidate.getClass().equals(StorageObject.class)) {
      return false;
    }
    StorageObject otherObject = (StorageObject) candidate;
    return Objects.equals(toPb(), otherObject.toPb()) && Objects.equals(storageOpts, otherObject.storageOpts);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), storageOpts);
  }

  private void readObjectData(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
    inputStream.defaultReadObject();
    this.backendService = storageOpts.getService();
  }

  static StorageObject fromProto(Storage backendService, com.google.api.services.storage.model.StorageObject sourceObject) {
    BlobInfo blobMetadata = BlobInfo.fromPb(sourceObject);
    return new StorageObject(backendService, new BlobInfo.BuilderImpl(blobMetadata));
  }
}
