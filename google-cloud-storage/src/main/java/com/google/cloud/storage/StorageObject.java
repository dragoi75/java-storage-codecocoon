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
import static com.google.cloud.storage.StorageObject.BlobSourceOptions.toFetchOptionsArray;
import static com.google.cloud.storage.StorageObject.BlobSourceOptions.toStorageSourceOptionsArray;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.callable;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AccessControlEntry.AbstractEntity;
import com.google.cloud.storage.StorageService.BlobUploadOption;
import com.google.cloud.storage.StorageService.UrlSigningOption;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
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
 * {@link #copyToDestination} return a new object. To get a {@code Blob} object with the most recent
 * information use {@link #reloadFromStorage}. {@code Blob} adds a layer of service-related functionality over
 * {@link BlobMetadata}.
 */
public class StorageObject extends BlobMetadata {

    private static final long serialVersionUID = -6806832496717441434L;

    private final StorageSettings storageSettings;

    private transient StorageService storageService;

    static final Function<Tuple<StorageService, com.google.api.services.storage.model.StorageObject>, StorageObject> BLOB_FROM_PB_FUNCTION = new Function<Tuple<StorageService, com.google.api.services.storage.model.StorageObject>, StorageObject>() {

        @Override
        public StorageObject apply(Tuple<StorageService, com.google.api.services.storage.model.StorageObject> pb) {
            return StorageObject.fromProto(pb.x(), pb.y());
        }
    };

    private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;

    /**
     * Class for specifying blob source options when {@code Blob} methods are used.
     */
    public static class BlobSourceOptions extends AbstractOption {

        private static final long serialVersionUID = 214616862061934846L;

        private BlobSourceOptions(StorageRpcClient.StorageOption rpcSetting) {
            super(rpcSetting, null);
        }

        private BlobSourceOptions(StorageRpcClient.StorageOption rpcSetting, Object inputObject) {
            super(rpcSetting, inputObject);
        }

        private StorageService.BlobSourceOptions toStorageSourceOptions(BlobMetadata blobMetadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return StorageService.BlobSourceOptions.ifGenerationMatch(blobMetadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return StorageService.BlobSourceOptions.generationNotMatch(blobMetadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return StorageService.BlobSourceOptions.ifMetagenerationMatch(blobMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return StorageService.BlobSourceOptions.ifMetagenerationNotMatch(blobMetadata.getMetageneration());
                case CUSTOMER_SUPPLIED_KEY:
                    return StorageService.BlobSourceOptions.customerDecryptionKey((String) getValue());
                case USER_PROJECT:
                    return StorageService.BlobSourceOptions.withUserProject((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private StorageService.BlobFetchOption toFetchOption(BlobMetadata blobMetadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return StorageService.BlobFetchOption.ifGenerationMatch(blobMetadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return StorageService.BlobFetchOption.generationNotMatch(blobMetadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return StorageService.BlobFetchOption.ifMetagenerationMatch(blobMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return StorageService.BlobFetchOption.ifMetagenerationNotMatch(blobMetadata.getMetageneration());
                case USER_PROJECT:
                    return StorageService.BlobFetchOption.withUserProject((String) getValue());
                case CUSTOMER_SUPPLIED_KEY:
                    return StorageService.BlobFetchOption.customerDecryptionKey((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for blob's generation match. If this option is used the request will fail
         * if generation does not match.
         */
        public static BlobSourceOptions generationMatch() {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH);
        }

        /**
         * Returns an option for blob's generation mismatch. If this option is used the request will
         * fail if generation matches.
         */
        public static BlobSourceOptions generationNotMatch() {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BlobSourceOptions metagenerationMatchOption() {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches.
         */
        public static BlobSourceOptions metagenerationNotMatch() {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobSourceOptions withDecryptionKey(Key decryptionKey) {
            String base64EncodedKey = BaseEncoding.base64().encode(decryptionKey.getEncoded());
            return new BlobSourceOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param decryptionKey the AES256 encoded in base64
         */
        public static BlobSourceOptions withDecryptionKey(String decryptionKey) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, decryptionKey);
        }

        /**
         * Returns an option for blob's billing user project. This option is used only if the blob's
         * bucket has requester_pays flag enabled.
         */
        public static BlobSourceOptions userProject(String projectId) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
        }

        static StorageService.BlobSourceOptions[] toStorageSourceOptionsArray(BlobMetadata blobMetadata, BlobSourceOptions... storageSettings) {
            StorageService.BlobSourceOptions[] convertedSourceOptions = new StorageService.BlobSourceOptions[storageSettings.length];
            int i = 0;
            for (BlobSourceOptions srcOption : storageSettings) {
                convertedSourceOptions[i++] = srcOption.toStorageSourceOptions(blobMetadata);
            }
            return convertedSourceOptions;
        }

        static StorageService.BlobFetchOption[] toFetchOptionsArray(BlobMetadata blobMetadata, BlobSourceOptions... storageSettings) {
            StorageService.BlobFetchOption[] convertedSourceOptions = new StorageService.BlobFetchOption[storageSettings.length];
            int i = 0;
            for (BlobSourceOptions srcOption : storageSettings) {
                convertedSourceOptions[i++] = srcOption.toFetchOption(blobMetadata);
            }
            return convertedSourceOptions;
        }
    }

    /**
     * Downloads this blob to the given file path using specified blob read options.
     *
     * @param targetPath destination
     * @param storageSettings blob read options
     * @throws StorageOperationException upon failure
     */
    public void downloadTo(Path targetPath, BlobSourceOptions... storageSettings) {
        try (OutputStream out = Files.newOutputStream(targetPath)) {
            downloadTo(out, storageSettings);
        } catch (IOException ioException) {
            throw new StorageOperationException(ioException);
        }
    }

    /**
     * Downloads this blob to the given output stream using specified blob read options.
     *
     * @param out
     * @param storageSettings
     */
    public void downloadTo(OutputStream out, BlobSourceOptions... storageSettings) {
        final CountingOutputStream countingOut = new CountingOutputStream(out);
        final StorageRpcClient storageRpcClient = this.storageSettings.getStorageRpcV1();
        final Map<StorageRpcClient.StorageOption, ?> requestOptionsMap = DefaultStorageImpl.buildOptionMap(getBlobId(), storageSettings);
        runWithRetries(callable(new Runnable() {

            @Override
            public void run() {
                storageRpcClient.read(getBlobId().toStorageObject(), requestOptionsMap, countingOut.getCount(), countingOut);
            }
        }), this.storageSettings.getRetrySettings(), DefaultStorageImpl.EXCEPTION_HANDLER, this.storageSettings.getClock());
    }

    /**
     * Downloads this blob to the given file path.
     *
     * <p>This method is replaced with {@link #downloadTo(Path, BlobSourceOptions...)}, but is kept
     * here for binary compatibility with the older versions of the client library.
     *
     * @param targetPath destination
     * @throws StorageOperationException upon failure
     */
    public void downloadTo(Path targetPath) {
        downloadTo(targetPath, new BlobSourceOptions[0]);
    }

    /**
     * Builder for {@code Blob}.
     */
    public static class BlobInfoBuilder extends CloudStorageObjectBuilder {

        private final StorageService storageService;

        private final BlobInfoBuilderImpl infoBuilderImpl;

        BlobInfoBuilder(StorageObject sourceBlob) {
            this.storageService = sourceBlob.getStorage();
            this.infoBuilderImpl = new BlobInfoBuilderImpl(sourceBlob);
        }

        @Override
        public StorageObject.BlobInfoBuilder setBlobId(BlobIdentifier identifier) {
            infoBuilderImpl.setBlobId(identifier);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setGeneratedId(String assignedId) {
            infoBuilderImpl.setGeneratedId(assignedId);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentType(String mimeType) {
            infoBuilderImpl.setContentType(mimeType);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentDisposition(String attachmentDisposition) {
            infoBuilderImpl.setContentDisposition(attachmentDisposition);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentLanguage(String languageTag) {
            infoBuilderImpl.setContentLanguage(languageTag);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentEncoding(String encodingScheme) {
            infoBuilderImpl.setContentEncoding(encodingScheme);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setComponentCount(Integer partCount) {
            infoBuilderImpl.setComponentCount(partCount);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCacheControl(String cachingPolicy) {
            infoBuilderImpl.setCacheControl(cachingPolicy);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setAcl(List<AccessControlEntry> accessEntries) {
            infoBuilderImpl.setAcl(accessEntries);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setOwner(AccessControlEntry.AbstractEntity owningEntity) {
            infoBuilderImpl.setOwner(owningEntity);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setSize(Long byteCount) {
            infoBuilderImpl.setSize(byteCount);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setEtag(String entityTag) {
            infoBuilderImpl.setEtag(entityTag);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setSelfLink(String resourceUrl) {
            infoBuilderImpl.setSelfLink(resourceUrl);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMd5(String checksum) {
            infoBuilderImpl.setMd5(checksum);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMd5FromHexString(String hexDigest) {
            infoBuilderImpl.setMd5FromHexString(hexDigest);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCrc32c(String crcValue) {
            infoBuilderImpl.setCrc32c(crcValue);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCrc32cFromHexString(String crcHex) {
            infoBuilderImpl.setCrc32cFromHexString(crcHex);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setMediaLink(String mediaUrl) {
            infoBuilderImpl.setMediaLink(mediaUrl);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMetadata(Map<String, String> metaMap) {
            infoBuilderImpl.setMetadata(metaMap);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setStorageClass(StorageClassType storageTier) {
            infoBuilderImpl.setStorageClass(storageTier);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setMetageneration(Long generationNumber) {
            infoBuilderImpl.setMetageneration(generationNumber);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setDeleteTime(Long deletionTimestamp) {
            infoBuilderImpl.setDeleteTime(deletionTimestamp);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setUpdateTime(Long lastUpdatedAt) {
            infoBuilderImpl.setUpdateTime(lastUpdatedAt);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setCreateTime(Long creationTimestamp) {
            infoBuilderImpl.setCreateTime(creationTimestamp);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
            infoBuilderImpl.setIsDirectory(directoryFlag);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryptionSettings encryptionConfig) {
            infoBuilderImpl.setCustomerEncryption(encryptionConfig);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setKmsKeyName(String encryptionKeyName) {
            infoBuilderImpl.setKmsKeyName(encryptionKeyName);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
            infoBuilderImpl.setEventBasedHold(eventHold);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setTemporaryHold(Boolean tempHold) {
            infoBuilderImpl.setTemporaryHold(tempHold);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiry) {
            infoBuilderImpl.setRetentionExpirationTime(retentionExpiry);
            return this;
        }

        @Override
        public StorageObject buildObject() {
            return new StorageObject(storageService, infoBuilderImpl);
        }
    }

    StorageObject(StorageService storageService, BlobInfoBuilderImpl infoBuilderImpl) {
        super(infoBuilderImpl);
        this.storageService = checkNotNull(storageService);
        this.storageSettings = storageService.getOptions();
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
    public boolean existsInStorage(BlobSourceOptions... storageSettings) {
        int optsCount = storageSettings.length;
        StorageService.BlobFetchOption[] fetchParams = Arrays.copyOf(toFetchOptionsArray(this, storageSettings), optsCount + 1);
        fetchParams[optsCount] = StorageService.BlobFetchOption.fieldsSelector();
        return null != storageService.get(getBlobId(), fetchParams);
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
        return storageService.readAllBytes(getBlobId(), toStorageSourceOptionsArray(this, storageSettings));
    }

    /**
     * Fetches current blob's latest information. Returns {@code null} if the blob does not exist.
     *
     * <p>Example of getting the blob's latest information, if its generation does not match the
     * {@link StorageObject#getGeneration()} value, otherwise a {@link StorageOperationException} is thrown.
     *
     * <pre>{@code
     * Blob latestBlob = blob.reload(BlobSourceOption.generationNotMatch());
     * if (latestBlob == null) {
     *   // the blob was not found
     * }
     * }</pre>
     *
     * @param storageSettings blob read options
     * @return a {@code Blob} object with latest information or {@code null} if not found
     * @throws StorageOperationException upon failure
     */
    public StorageObject reloadFromStorage(BlobSourceOptions... storageSettings) {
        return storageService.get(getBlobId(), toFetchOptionsArray(this, storageSettings));
    }

    /**
     * Updates the blob's information. Bucket or blob's name cannot be changed by this method. If you
     * want to rename the blob or move it to a different bucket use the {@link #copyToDestination} and {@link
     * #deleteFromStorage} operations. A new {@code Blob} object is returned. By default no checks are made on
     * the metadata generation of the current blob. If you want to update the information only if the
     * current blob metadata are at their latest version use the {@code metagenerationMatch} option:
     * {@code newBlob.update(BlobTargetOption.metagenerationMatch())}.
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
     * Blob blob = storage.update(BlobInfo.newBuilder(bucketName, blobName)
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
     * Blob blob = storage.update(BlobInfo.newBuilder(bucketName, blobName)
     *     .setMetadata(newMetadata)
     *     .build());
     * }</pre>
     *
     * @param storageSettings update options
     * @return a {@code Blob} object with updated information
     * @throws StorageOperationException upon failure
     */
    public StorageObject updateInStorage(BlobUploadOption... storageSettings) {
        return storageService.update(this, storageSettings);
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
        return storageService.delete(getBlobId(), toStorageSourceOptionsArray(this, storageSettings));
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
     * @param destinationBlob target blob's id
     * @param storageSettings source blob options
     * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageOperationException upon failure
     */
    public BlobCopyWriter copyToDestination(BlobIdentifier destinationBlob, BlobSourceOptions... storageSettings) {
        StorageService.CopyOperationRequest transferSpec = StorageService.CopyOperationRequest.newCopyOperationBuilder().setSource(getBucket(), getName()).setSourceOptions(toStorageSourceOptionsArray(this, storageSettings)).setTarget(destinationBlob).buildRequest();
        return storageService.copy(transferSpec);
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
     * @param destinationName target bucket's name
     * @param storageSettings source blob options
     * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageOperationException upon failure
     */
    public BlobCopyWriter copyToDestination(String destinationName, BlobSourceOptions... storageSettings) {
        return copyToDestination(destinationName, getName(), storageSettings);
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
     * @param destinationName target bucket's name
     * @param destinationBlob target blob's name
     * @param storageSettings source blob options
     * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageOperationException upon failure
     */
    public BlobCopyWriter copyToDestination(String destinationName, String destinationBlob, BlobSourceOptions... storageSettings) {
        return copyToDestination(BlobIdentifier.create(destinationName, destinationBlob), storageSettings);
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
        return storageService.reader(getBlobId(), toStorageSourceOptionsArray(this, storageSettings));
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
     *   try {
     *     writer.write(ByteBuffer.wrap(content, 0, content.length));
     *   } catch (Exception ex) {
     *     // handle exception
     *   }
     * }
     * }</pre>
     *
     * @param storageSettings target blob options
     * @throws StorageOperationException upon failure
     */
    public WriteChannel openWriter(StorageService.BlobWriteSetting... storageSettings) {
        return storageService.writer(this, storageSettings);
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
     * UrlSigningOption#signUsing(ServiceAccountSigner)} option.
     *
     * <p>A service account signer is looked for in the following order:
     *
     * <ol>
     *   <li>The signer passed with the option {@link UrlSigningOption#signUsing(ServiceAccountSigner)}
     *   <li>The credentials passed to {@link StorageSettings}
     *   <li>The default credentials, if no credentials were passed to {@link StorageSettings}
     * </ol>
     *
     * <p>Example of creating a signed URL for the blob that is valid for 2 weeks, using the default
     * credentials for signing the URL.
     *
     * <pre>{@code
     * URL signedUrl = blob.signUrl(14, TimeUnit.DAYS);
     * }</pre>
     *
     * <p>Example of creating a signed URL for the blob passing the {@link
     * StorageService.UrlSigningOption#signUsing(ServiceAccountSigner)} option, that will be used to sign the URL.
     *
     * <pre>{@code
     * String keyPath = "/path/to/key.json";
     * URL signedUrl = blob.signUrl(14, TimeUnit.DAYS, SignUrlOption.signWith(
     *     ServiceAccountCredentials.fromStream(new FileInputStream(keyPath))));
     * }</pre>
     *
     * @param timeout time until the signed URL expires, expressed in {@code unit}. The finer
     *     granularity supported is 1 second, finer granularities will be truncated
     * @param timeMeasure time unit of the {@code duration} parameter
     * @param storageSettings optional URL signing options
     * @return a signed URL for this blob and the specified options
     * @throws IllegalStateException if {@link UrlSigningOption#signUsing(ServiceAccountSigner)} was not
     *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
     *     StorageSettings}
     * @throws IllegalArgumentException if {@code SignUrlOption.withMd5()} option is used and {@code
     *     blobInfo.md5()} is {@code null}
     * @throws IllegalArgumentException if {@code SignUrlOption.withContentType()} option is used and
     *     {@code blobInfo.contentType()} is {@code null}
     * @throws SigningException if the attempt to sign the URL failed
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
     */
    public URL generateSignedUrl(long timeout, TimeUnit timeMeasure, UrlSigningOption... storageSettings) {
        return storageService.signUrl(this, timeout, timeMeasure, storageSettings);
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
    public AccessControlEntry getAcl(AccessControlEntry.AbstractEntity principal) {
        return storageService.getAcl(getBlobId(), principal);
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
    public boolean removeAcl(AbstractEntity principal) {
        return storageService.deleteAcl(getBlobId(), principal);
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
    public AccessControlEntry addAcl(AccessControlEntry accessEntries) {
        return storageService.createAcl(getBlobId(), accessEntries);
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
    public AccessControlEntry modifyAcl(AccessControlEntry accessEntries) {
        return storageService.updateAcl(getBlobId(), accessEntries);
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
    public List<AccessControlEntry> listAclEntries() {
        return storageService.listAcls(getBlobId());
    }

    /**
     * Returns the blob's {@code Storage} object used to issue requests.
     */
    public StorageService getStorage() {
        return storageService;
    }

    @Override
    public StorageObject.BlobInfoBuilder asBuilder() {
        return new BlobInfoBuilder(this);
    }

    @Override
    public final boolean equals(Object candidateObject) {
        if (this == candidateObject) {
            return true;
        }
        if (null == candidateObject || !candidateObject.getClass().equals(StorageObject.class)) {
            return false;
        }
        StorageObject thatInstance = (StorageObject) candidateObject;
        return Objects.equals(toProto(), thatInstance.toProto()) && Objects.equals(storageSettings, thatInstance.storageSettings);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), storageSettings);
    }

    private void readObject(ObjectInputStream objectInputStream) throws IOException, ClassNotFoundException {
        objectInputStream.defaultReadObject();
        this.storageService = storageSettings.getService();
    }

    static StorageObject fromProto(StorageService storageService, com.google.api.services.storage.model.StorageObject protoObject) {
        BlobMetadata metadata = BlobMetadata.fromProto(protoObject);
        return new StorageObject(storageService, new BlobInfoBuilderImpl(metadata));
    }
}
