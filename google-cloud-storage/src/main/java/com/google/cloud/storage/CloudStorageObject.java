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
import static com.google.cloud.storage.CloudStorageObject.BlobSourceOptions.toGetOptionsArray;
import static com.google.cloud.storage.CloudStorageObject.BlobSourceOptions.toSourceOptionsArray;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.callable;
import com.google.api.services.storage.model.StorageObject;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.RetryHelper;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AclEntry.AbstractEntity;
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
 * {@link #copyToTarget} return a new object. To get a {@code Blob} object with the most recent
 * information use {@link #reloadFromStorage}. {@code Blob} adds a layer of service-related functionality over
 * {@link BlobMetadata}.
 */
public class CloudStorageObject extends BlobMetadata {

    private static final long serialVersionUID = -6806832496717441434L;

    private final StorageSettings storageSettings;

    private transient StorageService serviceClient;

    static final Function<Tuple<StorageService, StorageObject>, CloudStorageObject> BLOB_FROM_PB_FUNCTION = new Function<Tuple<StorageService, StorageObject>, CloudStorageObject>() {

        @Override
        public CloudStorageObject apply(Tuple<StorageService, StorageObject> pb) {
            return CloudStorageObject.fromProto(pb.x(), pb.y());
        }
    };

    private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;

    /**
     * Class for specifying blob source options when {@code Blob} methods are used.
     */
    public static class BlobSourceOptions extends AbstractOption {

        private static final long serialVersionUID = 214616862061934846L;

        private BlobSourceOptions(StorageRpcClient.StorageOption transportOption) {
            super(transportOption, null);
        }

        private BlobSourceOptions(StorageRpcClient.StorageOption transportOption, Object payload) {
            super(transportOption, payload);
        }

        private StorageService.BlobReadOption toBlobReadOptions(BlobMetadata metadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return StorageService.BlobReadOption.ifGenerationMatch(metadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return StorageService.BlobReadOption.generationNotMatch(metadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return StorageService.BlobReadOption.ifMetagenerationMatch(metadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return StorageService.BlobReadOption.ifMetagenerationNotMatch(metadata.getMetageneration());
                case CUSTOMER_SUPPLIED_KEY:
                    return StorageService.BlobReadOption.withDecryptionKey((String) getValue());
                case USER_PROJECT:
                    return StorageService.BlobReadOption.withUserProject((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private StorageService.BlobGetOptions toBlobGetOption(BlobMetadata metadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return StorageService.BlobGetOptions.ifGenerationMatch(metadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return StorageService.BlobGetOptions.generationNotMatch(metadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return StorageService.BlobGetOptions.ifMetagenerationMatch(metadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return StorageService.BlobGetOptions.ifMetagenerationNotMatch(metadata.getMetageneration());
                case USER_PROJECT:
                    return StorageService.BlobGetOptions.withUserProject((String) getValue());
                case CUSTOMER_SUPPLIED_KEY:
                    return StorageService.BlobGetOptions.withDecryptionKey((String) getValue());
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
        public static BlobSourceOptions customerSuppliedKey(Key customerKeyMaterial) {
            String encodedKey = BaseEncoding.base64().encode(customerKeyMaterial.getEncoded());
            return new BlobSourceOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param customerKeyMaterial the AES256 encoded in base64
         */
        public static BlobSourceOptions customerSuppliedKey(String customerKeyMaterial) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, customerKeyMaterial);
        }

        /**
         * Returns an option for blob's billing user project. This option is used only if the blob's
         * bucket has requester_pays flag enabled.
         */
        public static BlobSourceOptions userProject(String projectId) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
        }

        static StorageService.BlobReadOption[] toSourceOptionsArray(BlobMetadata metadata, BlobSourceOptions... storageSettings) {
            StorageService.BlobReadOption[] readOptionsArray = new StorageService.BlobReadOption[storageSettings.length];
            int i = 0;
            for (BlobSourceOptions srcOption : storageSettings) {
                readOptionsArray[i++] = srcOption.toBlobReadOptions(metadata);
            }
            return readOptionsArray;
        }

        static StorageService.BlobGetOptions[] toGetOptionsArray(BlobMetadata metadata, BlobSourceOptions... storageSettings) {
            StorageService.BlobGetOptions[] readOptionsArray = new StorageService.BlobGetOptions[storageSettings.length];
            int i = 0;
            for (BlobSourceOptions srcOption : storageSettings) {
                readOptionsArray[i++] = srcOption.toBlobGetOption(metadata);
            }
            return readOptionsArray;
        }
    }

    /**
     * Downloads this blob to the given file path using specified blob read options.
     *
     * @param targetPath destination
     * @param storageSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public void downloadTo(Path targetPath, BlobSourceOptions... storageSettings) {
        try (OutputStream out = Files.newOutputStream(targetPath)) {
            downloadTo(out, storageSettings);
        } catch (IOException ioEx) {
            throw new StorageServiceException(ioEx);
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
        final StorageRpcClient rpcClient = this.storageSettings.getStorageRpcV1();
        final Map<StorageRpcClient.StorageOption, ?> requestOptionsMap = StorageServiceImpl.buildOptionMap(getBlobId(), storageSettings);
        try {
            runWithRetries(callable(new Runnable() {

                @Override
                public void run() {
                    rpcClient.read(getBlobId().toProto(), requestOptionsMap, countingOut.getCount(), countingOut);
                }
            }), this.storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, this.storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException ioEx) {
            StorageServiceException.translateThenThrow(ioEx);
        }
    }

    /**
     * Downloads this blob to the given file path.
     *
     * <p>This method is replaced with {@link #downloadTo(Path, BlobSourceOptions...)}, but is kept
     * here for binary compatibility with the older versions of the client library.
     *
     * @param targetPath destination
     * @throws StorageServiceException upon failure
     */
    public void downloadTo(Path targetPath) {
        downloadTo(targetPath, new BlobSourceOptions[0]);
    }

    /**
     * Builder for {@code Blob}.
     */
    public static class BlobInfoBuilder extends StorageObjectBuilder {

        private final StorageService serviceClient;

        private final BlobInfoBuilderImpl builderImpl;

        BlobInfoBuilder(CloudStorageObject cloudObject) {
            this.serviceClient = cloudObject.getStorage();
            this.builderImpl = new BlobInfoBuilderImpl(cloudObject);
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setBlobId(BlobIdentifier objectId) {
            builderImpl.setBlobId(objectId);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setGeneratedId(String generatedIdentifier) {
            builderImpl.setGeneratedId(generatedIdentifier);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setContentType(String mimeType) {
            builderImpl.setContentType(mimeType);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setContentDisposition(String dispositionType) {
            builderImpl.setContentDisposition(dispositionType);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setContentLanguage(String languageTag) {
            builderImpl.setContentLanguage(languageTag);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setContentEncoding(String encoding) {
            builderImpl.setContentEncoding(encoding);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setComponentCount(Integer numComponents) {
            builderImpl.setComponentCount(numComponents);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setCacheControl(String cacheDirective) {
            builderImpl.setCacheControl(cacheDirective);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setAcl(List<AclEntry> accessControlList) {
            builderImpl.setAcl(accessControlList);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setOwner(AclEntry.AbstractEntity principal) {
            builderImpl.setOwner(principal);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setSize(Long lengthBytes) {
            builderImpl.setSize(lengthBytes);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setEtag(String entityTag) {
            builderImpl.setEtag(entityTag);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setSelfLink(String selfUrl) {
            builderImpl.setSelfLink(selfUrl);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setMd5(String md5Hash) {
            builderImpl.setMd5(md5Hash);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setMd5FromHexString(String md5Hex) {
            builderImpl.setMd5FromHexString(md5Hex);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setCrc32c(String crc32cChecksum) {
            builderImpl.setCrc32c(crc32cChecksum);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setCrc32cFromHexString(String crc32cHex) {
            builderImpl.setCrc32cFromHexString(crc32cHex);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setMediaLink(String mediaUrl) {
            builderImpl.setMediaLink(mediaUrl);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setMetadata(Map<String, String> customMetadata) {
            builderImpl.setMetadata(customMetadata);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setStorageClass(StorageClass storageTier) {
            builderImpl.setStorageClass(storageTier);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setMetageneration(Long metaGenerationNumber) {
            builderImpl.setMetageneration(metaGenerationNumber);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setDeleteTime(Long deletionTime) {
            builderImpl.setDeleteTime(deletionTime);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setUpdateTime(Long updatedAt) {
            builderImpl.setUpdateTime(updatedAt);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setCreateTime(Long creationTime) {
            builderImpl.setCreateTime(creationTime);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
            builderImpl.setIsDirectory(directoryFlag);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryptionInfo encryptionInfo) {
            builderImpl.setCustomerEncryption(encryptionInfo);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setKmsKeyName(String kmsKey) {
            builderImpl.setKmsKeyName(kmsKey);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
            builderImpl.setEventBasedHold(eventHold);
            return this;
        }

        @Override
        public CloudStorageObject.BlobInfoBuilder setTemporaryHold(Boolean tempHold) {
            builderImpl.setTemporaryHold(tempHold);
            return this;
        }

        @Override
        CloudStorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiry) {
            builderImpl.setRetentionExpirationTime(retentionExpiry);
            return this;
        }

        @Override
        public CloudStorageObject buildObject() {
            return new CloudStorageObject(serviceClient, builderImpl);
        }
    }

    CloudStorageObject(StorageService serviceClient, BlobInfoBuilderImpl builderImpl) {
        super(builderImpl);
        this.serviceClient = checkNotNull(serviceClient);
        this.storageSettings = serviceClient.getOptions();
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
     * @throws StorageServiceException upon failure
     */
    public boolean existsInStorage(BlobSourceOptions... storageSettings) {
        int len = storageSettings.length;
        StorageService.BlobGetOptions[] getRequestOptions = Arrays.copyOf(toGetOptionsArray(this, storageSettings), len + 1);
        getRequestOptions[len] = StorageService.BlobGetOptions.selectFields();
        return null != serviceClient.get(getBlobId(), getRequestOptions);
    }

    /**
     * Returns this blob's content.
     *
     * <p>Example of reading all bytes of the blob, if its generation matches the {@link
     * CloudStorageObject#getGeneration()} value, otherwise a {@link StorageServiceException} is thrown.
     *
     * <pre>{@code
     * byte[] content = blob.getContent(BlobSourceOption.generationMatch());
     * }</pre>
     *
     * @param storageSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public byte[] getContent(BlobSourceOptions... storageSettings) {
        return serviceClient.readAllBytes(getBlobId(), toSourceOptionsArray(this, storageSettings));
    }

    /**
     * Fetches current blob's latest information. Returns {@code null} if the blob does not exist.
     *
     * <p>Example of getting the blob's latest information, if its generation does not match the
     * {@link CloudStorageObject#getGeneration()} value, otherwise a {@link StorageServiceException} is thrown.
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
     * @throws StorageServiceException upon failure
     */
    public CloudStorageObject reloadFromStorage(BlobSourceOptions... storageSettings) {
        return serviceClient.get(getBlobId(), toGetOptionsArray(this, storageSettings));
    }

    /**
     * Updates the blob's information. Bucket or blob's name cannot be changed by this method. If you
     * want to rename the blob or move it to a different bucket use the {@link #copyToTarget} and {@link
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
     * @throws StorageServiceException upon failure
     */
    public CloudStorageObject updateInStorage(BlobUploadOption... storageSettings) {
        return serviceClient.update(this, storageSettings);
    }

    /**
     * Deletes this blob.
     *
     * <p>Example of deleting the blob, if its generation matches the {@link CloudStorageObject#getGeneration()}
     * value, otherwise a {@link StorageServiceException} is thrown.
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
     * @throws StorageServiceException upon failure
     */
    public boolean deleteFromStorage(BlobSourceOptions... storageSettings) {
        return serviceClient.delete(getBlobId(), toSourceOptionsArray(this, storageSettings));
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
     * @param destinationIdentifier target blob's id
     * @param storageSettings source blob options
     * @return a {@link ResumableCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ResumableCopyWriter copyToTarget(BlobIdentifier destinationIdentifier, BlobSourceOptions... storageSettings) {
        StorageService.CopyOperationRequest copyOperation = StorageService.CopyOperationRequest.newCopyBuilder().setSource(getBucket(), getName()).setSourceOptions(toSourceOptionsArray(this, storageSettings)).setTarget(destinationIdentifier).buildCopyRequest();
        return serviceClient.copy(copyOperation);
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
     * @return a {@link ResumableCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ResumableCopyWriter copyToTarget(String destinationBucket, BlobSourceOptions... storageSettings) {
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
     * @param destinationIdentifier target blob's name
     * @param storageSettings source blob options
     * @return a {@link ResumableCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ResumableCopyWriter copyToTarget(String destinationBucket, String destinationIdentifier, BlobSourceOptions... storageSettings) {
        return copyToTarget(BlobIdentifier.create(destinationBucket, destinationIdentifier), storageSettings);
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
     * @throws StorageServiceException upon failure
     */
    public ReadChannel openReader(BlobSourceOptions... storageSettings) {
        return serviceClient.reader(getBlobId(), toSourceOptionsArray(this, storageSettings));
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
     * @throws StorageServiceException upon failure
     */
    public WriteChannel openWriter(StorageService.BlobWriteOptions... storageSettings) {
        return serviceClient.writer(this, storageSettings);
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
     * credentials for signing the URL:
     *
     * <pre>{@code
     * URL signedUrl = blob.signUrl(14, TimeUnit.DAYS);
     * }</pre>
     *
     * <p>Example of creating a signed URL for the blob passing the {@link
     * UrlSigningOption#signUsing(ServiceAccountSigner)} option, that will be used to sign the URL:
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
     * @param validityPeriod time until the signed URL expires, expressed in {@code unit}. The finer
     *     granularity supported is 1 second, finer granularities will be truncated
     * @param timeScale time unit of the {@code duration} parameter
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
    public URL generateSignedUrl(long validityPeriod, TimeUnit timeScale, StorageService.UrlSigningOption... storageSettings) {
        return serviceClient.signUrl(this, validityPeriod, timeScale, storageSettings);
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
     * @throws StorageServiceException upon failure
     */
    public AclEntry getAcl(AclEntry.AbstractEntity principal) {
        return serviceClient.getAcl(getBlobId(), principal);
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
     * @throws StorageServiceException upon failure
     */
    public boolean removeAcl(AbstractEntity principal) {
        return serviceClient.deleteAcl(getBlobId(), principal);
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
     * @throws StorageServiceException upon failure
     */
    public AclEntry addAcl(AclEntry accessControlList) {
        return serviceClient.createAcl(getBlobId(), accessControlList);
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
     * @throws StorageServiceException upon failure
     */
    public AclEntry updateAclEntry(AclEntry accessControlList) {
        return serviceClient.updateAcl(getBlobId(), accessControlList);
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
     * @throws StorageServiceException upon failure
     */
    public List<AclEntry> listAclEntries() {
        return serviceClient.listAcls(getBlobId());
    }

    /**
     * Returns the blob's {@code Storage} object used to issue requests.
     */
    public StorageService getStorage() {
        return serviceClient;
    }

    @Override
    public CloudStorageObject.BlobInfoBuilder toBlobBuilder() {
        return new BlobInfoBuilder(this);
    }

    @Override
    public final boolean equals(Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (null == otherObject || !otherObject.getClass().equals(CloudStorageObject.class)) {
            return false;
        }
        CloudStorageObject comparedStorageObject = (CloudStorageObject) otherObject;
        return Objects.equals(toProto(), comparedStorageObject.toProto()) && Objects.equals(storageSettings, comparedStorageObject.storageSettings);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), storageSettings);
    }

    private void readObject(ObjectInputStream objectInputStream) throws IOException, ClassNotFoundException {
        objectInputStream.defaultReadObject();
        this.serviceClient = storageSettings.getService();
    }

    static CloudStorageObject fromProto(StorageService serviceClient, StorageObject protoObject) {
        BlobMetadata blobMetadata = BlobMetadata.fromProto(protoObject);
        return new CloudStorageObject(serviceClient, new BlobInfoBuilderImpl(blobMetadata));
    }
}
