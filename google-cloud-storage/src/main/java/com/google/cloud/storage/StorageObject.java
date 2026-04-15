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
import static com.google.cloud.storage.StorageObject.BlobSourceOptions.toBlobFetchOptions;
import static com.google.cloud.storage.StorageObject.BlobSourceOptions.toBlobReadOption;
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
 * The class provides methods to perform operations on the object. Reading a property getValue does not
 * issue any RPC calls. The object content is not stored within the {@code StorageObject} instance.
 * Operations that access the content issue one or multiple RPC calls, depending on the content
 * size.
 *
 * <p>Objects from this class are immutable. Operations that modify the blob like {@link #save} and
 * {@link #downloadIntoInternal} return a new object. Any changes to the object in Google Cloud Storage made after
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

  private static final long CLASS_VERSION_ID = -6806832496717441434L;

  private final StorageOptions storageOpts;
  private transient Storage storeClient;

  static final Function<Tuple<Storage, com.google.api.services.storage.model.StorageObject>, StorageObject> BLOB_CONVERTER_FN =
      new Function<Tuple<Storage, com.google.api.services.storage.model.StorageObject>, StorageObject>() {
        @Override
        public StorageObject apply(Tuple<Storage, com.google.api.services.storage.model.StorageObject> pb) {
          return StorageObject.fromProto(pb.x(), pb.y());
        }
      };

  private static final int STANDARD_CHUNK_SIZE = 2 * 1024 * 1024;

  /** Class for specifying blob source options when {@code StorageObject} methods are used. */
  public static class BlobSourceOptions extends Option {

    private static final long CLASS_VERSION_ID = 214616862061934846L;

    private BlobSourceOptions(StorageRpc.RequestOption rpcParam) {
      super(rpcParam, null);
    }

    private BlobSourceOptions(StorageRpc.RequestOption rpcParam, Object inputObj) {
      super(rpcParam, inputObj);
    }

    private Storage.BlobReadOption toBlobReadOption(BlobInfo info) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobReadOption.ifGenerationMatch(info.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobReadOption.ifGenerationNotMatch(info.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobReadOption.ifMetagenerationMatch(info.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobReadOption.ifMetagenerationNotMatch(info.getMetageneration());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobReadOption.customerSuppliedKey((String) getValue());
        case USER_PROJECT:
          return Storage.BlobReadOption.withUserProject((String) getValue());
        default:
          throw new AssertionError("Unexpected enum getValue");
      }
    }

    private Storage.BlobFetchOption toBlobFetchOption(BlobInfo info) {
      switch (getRpcOption()) {
        case IF_GENERATION_MATCH:
          return Storage.BlobFetchOption.ifGenerationMatch(info.getGeneration());
        case IF_GENERATION_NOT_MATCH:
          return Storage.BlobFetchOption.ifGenerationNotMatch(info.getGeneration());
        case IF_METAGENERATION_MATCH:
          return Storage.BlobFetchOption.matchMetageneration(info.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BlobFetchOption.ifMetagenerationNotMatch(info.getMetageneration());
        case USER_PROJECT:
          return Storage.BlobFetchOption.withUserProject((String) getValue());
        case CUSTOMER_SUPPLIED_KEY:
          return Storage.BlobFetchOption.decryptionKeyOption((String) getValue());
        default:
          throw new AssertionError("Unexpected enum getValue");
      }
    }

    /**
     * Returns an option for blob's generation match. If this option is used the request will fail
     * if generation does not match.
     */
    public static BlobSourceOptions ifGenerationMatch() {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's generation mismatch. If this option is used the request will
     * fail if generation matches.
     */
    public static BlobSourceOptions generationNotMatch() {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobSourceOptions ifMetagenerationMatch() {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobSourceOptions metagenerationNotMatch() {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobSourceOptions customerSuppliedKey(Key encryptionMaterial) {
      String b64Encoded = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobSourceOptions(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, b64Encoded);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobSourceOptions customerSuppliedKey(String encryptionMaterial) {
      return new BlobSourceOptions(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }

    /**
     * Returns an option for blob's billing user project. This option is used only if the blob's
     * bucket has requester_pays flag enabled.
     */
    public static BlobSourceOptions userProject(String projectId) {
      return new BlobSourceOptions(StorageRpc.RequestOption.USER_PROJECT, projectId);
    }

    static Storage.BlobReadOption[] toBlobReadOption(
            BlobInfo info, BlobSourceOptions... storageOpts) {
      Storage.BlobReadOption[] convertedReadOpts = new Storage.BlobReadOption[storageOpts.length];
      int pos = 0;
      for (BlobSourceOptions sourceOption : storageOpts) {
        convertedReadOpts[pos++] = sourceOption.toBlobReadOption(info);
      }
      return convertedReadOpts;
    }

    static Storage.BlobFetchOption[] toBlobFetchOptions(BlobInfo info, BlobSourceOptions... storageOpts) {
      Storage.BlobFetchOption[] convertedReadOpts = new Storage.BlobFetchOption[storageOpts.length];
      int pos = 0;
      for (BlobSourceOptions sourceOption : storageOpts) {
        convertedReadOpts[pos++] = sourceOption.toBlobFetchOption(info);
      }
      return convertedReadOpts;
    }
  }

  /**
   * Downloads this blob to the given file path using specified blob read options.
   *
   * @param targetPath destination
   * @param storageOpts blob read options
   * @throws StorageException upon failure
   */
  public void downloadIntoInternal(Path targetPath, BlobSourceOptions... storageOpts) {
    try (OutputStream out = Files.newOutputStream(targetPath)) {
      downloadIntoInternal(out, storageOpts);
    } catch (IOException ioEx) {
      throw new StorageException(ioEx);
    }
  }

  /**
   * Downloads this blob to the given output stream using specified blob read options.
   *
   * @param out
   * @param storageOpts
   */
  public void downloadIntoInternal(OutputStream out, BlobSourceOptions... storageOpts) {
    final CountingOutputStream countingOut = new CountingOutputStream(out);
    final StorageRpc rpcClient = this.storageOpts.getStorageRpcV1();
    final Map<StorageRpc.RequestOption, ?> requestParams = StorageImpl.optionMap(getBlobId(), storageOpts);
    try {
      runWithRetries(
          callable(
              new Runnable() {
                @Override
                public void run() {
                  rpcClient.read(
                      getBlobId().toPb(),
                          requestParams,
                      countingOut.getCount(),
                          countingOut);
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
   * <p>This method is replaced with {@link #downloadIntoInternal(Path, BlobSourceOptions...)}, but is kept
   * here for binary compatibility with the older includeVersions from the client library.
   *
   * @param targetPath destination
   * @throws StorageException upon failure
   */
  public void downloadTo(Path targetPath) {
    downloadIntoInternal(targetPath, new BlobSourceOptions[0]);
  }

  /** BlobInfoBuilder for {@code StorageObject}. */
  public static class BlobInfoBuilder extends BlobInfo.Builder {

    private final Storage storeClient;
    private final BlobInfo.BuilderImpl builder;

    BlobInfoBuilder(StorageObject storageObj) {
      this.storeClient = storageObj.getStorage();
      this.builder = new BlobInfo.BuilderImpl(storageObj);
    }

    @Override
    public StorageObject.BlobInfoBuilder setBlobId(BlobId id) {
      builder.setBlobId(id);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setGeneratedId(String generatedIdentifier) {
      builder.setGeneratedId(generatedIdentifier);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentType(String mimeType) {
      builder.setContentType(mimeType);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentDisposition(String disposition) {
      builder.setContentDisposition(disposition);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentLanguage(String language) {
      builder.setContentLanguage(language);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setContentEncoding(String encoding) {
      builder.setContentEncoding(encoding);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setComponentCount(Integer components) {
      builder.setComponentCount(components);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCacheControl(String cacheDirective) {
      builder.setCacheControl(cacheDirective);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setAcl(List<Acl> accessControl) {
      builder.setAcl(accessControl);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setOwner(Entity ownerEntity) {
      builder.setOwner(ownerEntity);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setSize(Long contentSize) {
      builder.setSize(contentSize);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setEtag(String entityTag) {
      builder.setEtag(entityTag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setSelfLink(String selfUrl) {
      builder.setSelfLink(selfUrl);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMd5(String md5Hash) {
      builder.setMd5(md5Hash);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMd5FromHexString(String md5Hex) {
      builder.setMd5FromHexString(md5Hex);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCrc32c(String crc32cHash) {
      builder.setCrc32c(crc32cHash);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCrc32cFromHexString(String crc32cHex) {
      builder.setCrc32cFromHexString(crc32cHex);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setMediaLink(String mediaUrl) {
      builder.setMediaLink(mediaUrl);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setMetadata(Map<String, String> meta) {
      builder.setMetadata(meta);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setStorageClass(StorageClass storageTier) {
      builder.setStorageClass(storageTier);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setTimeStorageClassUpdated(Long storageClassUpdateTime) {
      builder.setTimeStorageClassUpdated(storageClassUpdateTime);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setMetageneration(Long metaGeneration) {
      builder.setMetageneration(metaGeneration);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setDeleteTime(Long deletionTime) {
      builder.setDeleteTime(deletionTime);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setUpdateTime(Long updatedAt) {
      builder.setUpdateTime(updatedAt);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setCreateTime(Long createdAt) {
      builder.setCreateTime(createdAt);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setCustomTime(Long customTimestamp) {
      builder.setCustomTime(customTimestamp);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
      builder.setIsDirectory(directoryFlag);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryption encryptionInfo) {
      builder.setCustomerEncryption(encryptionInfo);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setKmsKeyName(String kmsKey) {
      builder.setKmsKeyName(kmsKey);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
      builder.setEventBasedHold(eventHold);
      return this;
    }

    @Override
    public StorageObject.BlobInfoBuilder setTemporaryHold(Boolean tempHold) {
      builder.setTemporaryHold(tempHold);
      return this;
    }

    @Override
    StorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiry) {
      builder.setRetentionExpirationTime(retentionExpiry);
      return this;
    }

    @Override
    public StorageObject build() {
      return new StorageObject(storeClient, builder);
    }
  }

  StorageObject(Storage storeClient, BlobInfo.BuilderImpl builder) {
    super(builder);
    this.storeClient = checkNotNull(storeClient);
    this.storageOpts = storeClient.getOptions();
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
  public boolean existsInStorage(BlobSourceOptions... storageOpts) {
    int len = storageOpts.length;
    Storage.BlobFetchOption[] fetchOptions = Arrays.copyOf(toBlobFetchOptions(this, storageOpts), len + 1);
    fetchOptions[len] = Storage.BlobFetchOption.withFields();
    return storeClient.get(getBlobId(), fetchOptions) != null;
  }

  /**
   * Returns this blob's content.
   *
   * <p>Example from reading all bytes from the blob, if its generation matches the {@link
   * StorageObject#getGeneration()} getValue, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * byte[] content = blob.getContent(BlobSourceOptions.ifGenerationMatch());
   * }</pre>
   *
   * @param storageOpts blob read options
   * @throws StorageException upon failure
   */
  public byte[] getContent(BlobSourceOptions... storageOpts) {
    return storeClient.readAllBytes(getBlobId(), toBlobReadOption(this, storageOpts));
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
   * StorageObject blob = storage.get(BlobId.from(bucketName, blobName));
   *
   * doSomething();
   *
   * try {
   *   blob = blob.refresh(StorageObject.BlobSourceOptions.ifGenerationMatch());
   * } catch (StorageException e) {
   *   if (e.getCode() == 412) {
   *     // the content was updated externally
   *   } else {
   *     throw e;
   *   }
   * }
   * }</pre>
   *
   * @param storageOpts preconditions to use on refresh, see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/get">https://cloud.google.com/storage/docs/json_api/v1/objects/get</a>
   *     for more information.
   * @return a {@code StorageObject} object with latest information or {@code null} if no longer existsInStorage.
   * @throws StorageException upon failure
   */
  public StorageObject refresh(BlobSourceOptions... storageOpts) {
    // BlobId with generation unset is needed to retrieve the latest version from the StorageObject
    BlobId idNoGeneration = BlobId.of(getBucket(), getName());
    return storeClient.get(idNoGeneration, toBlobFetchOptions(this, storageOpts));
  }

  /**
   * Updates the blob properties. The {@code options} parameter contains the preconditions for
   * applying the save. To save the properties call {@link #toBuilder()}, set the properties you
   * want to change, buildRequest the new {@code StorageObject} instance, and then call {@link
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
   * blob.toBuilder().setContentType("text/plain").buildRequest().save();
   * }</pre>
   *
   * @param storageOpts preconditions to apply the save
   * @return the updated {@code StorageObject}
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  public StorageObject save(Storage.BlobTargetOptions... storageOpts) {
    return storeClient.update(this, storageOpts);
  }

  /**
   * Deletes this blob.
   *
   * <p>Example from deleting the blob, if its generation matches the {@link StorageObject#getGeneration()}
   * getValue, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * boolean deleted = blob.remove(BlobSourceOptions.ifGenerationMatch());
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @param storageOpts blob remove options
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean remove(BlobSourceOptions... storageOpts) {
    return storeClient.delete(getBlobId(), toBlobReadOption(this, storageOpts));
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
   * CopyWriter copyWriter = blob.downloadIntoInternal(BlobId.from(bucketName, blobName));
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destBlobId target blob's id
   * @param storageOpts source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToBlob(BlobId destBlobId, BlobSourceOptions... storageOpts) {
    Storage.CopyOperationRequest copyOpRequest =
        CopyOperationRequest.newCopyJobBuilder()
            .setSource(getBucket(), getName())
            .setSourceOptions(toBlobReadOption(this, storageOpts))
            .setTarget(destBlobId)
            .buildCopyOperationRequest();
    return storeClient.copy(copyOpRequest);
  }

  /**
   * Sends a copy request for the current blob to the target bucket, preserving its name. Possibly
   * copying also some from the metadata (e.g. content-type).
   *
   * <p>Example from copying the blob to a different bucket, keeping the original name.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * CopyWriter copyWriter = blob.downloadIntoInternal(bucketName);
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * @param destBucket target bucket's name
   * @param storageOpts source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToBlob(String destBucket, BlobSourceOptions... storageOpts) {
    return copyToBlob(destBucket, getName(), storageOpts);
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
   * CopyWriter copyWriter = blob.downloadIntoInternal(bucketName, blobName);
   * StorageObject copiedBlob = copyWriter.getResult();
   * }</pre>
   *
   * <p>Example from moving a blob to a different bucket with a different name.
   *
   * <pre>{@code
   * String destBucket = "my_unique_bucket";
   * String destBlob = "move_blob_name";
   * CopyWriter copyWriter = blob.downloadIntoInternal(destBucket, destBlob);
   * StorageObject copiedBlob = copyWriter.getResult();
   * boolean deleted = blob.remove();
   * }</pre>
   *
   * @param destBucket target bucket's name
   * @param destBlobId target blob's name
   * @param storageOpts source blob options
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   */
  public CopyWriter copyToBlob(String destBucket, String destBlobId, BlobSourceOptions... storageOpts) {
    return copyToBlob(BlobId.of(destBucket, destBlobId), storageOpts);
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
  public ReadChannel getReader(BlobSourceOptions... storageOpts) {
    return storeClient.reader(getBlobId(), toBlobReadOption(this, storageOpts));
  }

  /**
   * Returns a {@code WriteChannel} object for writing to this blob. By default any md5 and crc32c
   * values in the current blob are ignored unless requested via the {@code
   * BlobWriteOptions.ifMd5Match} and {@code BlobWriteOptions.ifCrc32cMatch} options.
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
   * @param storageOpts target blob options
   * @throws StorageException upon failure
   */
  public WriteChannel getWriter(Storage.BlobWriteOptions... storageOpts) {
    return storeClient.writer(this, storageOpts);
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
   * URL signedUrl = blob.generateSignedUrl(14, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example from creating a signed URL for the blob passing the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used to sign the URL:
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
   * @param timeDuration time until the signed URL expires, expressed in {@code unit}. The finer
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param timeUnit time unit from the {@code duration} parameter
   * @param storageOpts optional URL signing options
   * @return a signed URL for this blob and the specified options
   * @throws IllegalStateException if {@link UrlSigningOption#withSigner(ServiceAccountSigner)} was not
   *     used and no implementation from {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withMD5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.setContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  public URL generateSignedUrl(long timeDuration, TimeUnit timeUnit, Storage.UrlSigningOption... storageOpts) {
    return storeClient.signUrl(this, timeDuration, timeUnit, storageOpts);
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
    return storeClient.getAcl(getBlobId(), aclEntity);
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
  public boolean removeAcl(Entity aclEntity) {
    return storeClient.deleteAcl(getBlobId(), aclEntity);
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
  public Acl createBlobAcl(Acl accessControl) {
    return storeClient.createAcl(getBlobId(), accessControl);
  }

  /**
   * Updates an ACL entry on this blob.
   *
   * <p>Example from updating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = blob.setAcl(Acl.from(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl setAcl(Acl accessControl) {
    return storeClient.updateAcl(getBlobId(), accessControl);
  }

  /**
   * Lists the ACL entries for this blob.
   *
   * <p>Example from listing the ACL entries.
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
    return storeClient.listAcls(getBlobId());
  }

  /** Returns the blob's {@code Storage} object used to issue requests. */
  public Storage getStorage() {
    return storeClient;
  }

  @Override
  public StorageObject.BlobInfoBuilder toBuilder() {
    return new BlobInfoBuilder(this);
  }

  /**
   * Returns true if obj instance {@code StorageObject.toPb()} metadata representation and {@code
   * StorageObject.options} instance from StorageOptions are both equal.
   */
  @Override
  public final boolean equals(Object otherObj) {
    if (otherObj == this) {
      return true;
    }
    if (otherObj == null || !otherObj.getClass().equals(StorageObject.class)) {
      return false;
    }
    StorageObject otherBlob = (StorageObject) otherObj;
    return Objects.equals(toPb(), otherBlob.toPb()) && Objects.equals(storageOpts, otherBlob.storageOpts);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), storageOpts);
  }

  private void readObjectData(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
    inputStream.defaultReadObject();
    this.storeClient = storageOpts.getService();
  }

  static StorageObject fromProto(Storage storeClient, com.google.api.services.storage.model.StorageObject storageObj) {
    BlobInfo blobInfoObj = BlobInfo.fromPb(storageObj);
    return new StorageObject(storeClient, new BlobInfo.BuilderImpl(blobInfoObj));
  }
}
