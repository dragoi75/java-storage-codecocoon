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
import com.google.cloud.storage.AccessControlEntry.ProtoEntity;
import com.google.cloud.storage.CloudStorageClient.BlobUploadOption;
import com.google.cloud.storage.CloudStorageClient.BlobWriteOptions;
import com.google.cloud.storage.CloudStorageClient.CopyOperationRequest;
import com.google.cloud.storage.CloudStorageClient.UrlSigningOption;
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
 * An object in Google Cloud Storage. A {@code Blob} object includes the {@code BlobId} instance,
 * the set of properties inherited from the {@link BlobMetadata} class and the {@code Storage} instance.
 * The class provides methods to perform operations on the object. Reading a property value does not
 * issue any RPC calls. The object content is not stored within the {@code Blob} instance.
 * Operations that access the content issue one or multiple RPC calls, depending on the content
 * size.
 *
 * <p>Objects of this class are immutable. Operations that modify the blob like {@link #updateInStorage} and
 * {@link #copyToDestination} return a new object. Any changes to the object in Google Cloud Storage made after
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
public class StorageObject extends BlobMetadata {

    private static final long serialVersionUID = -6806832496717441434L;

    private final StorageClientOptions clientSettings;

    private transient CloudStorageClient cloudClient;

    static final Function<Tuple<CloudStorageClient, com.google.api.services.storage.model.StorageObject>, StorageObject> BLOB_FROM_PB_FUNCTION = new Function<Tuple<CloudStorageClient, com.google.api.services.storage.model.StorageObject>, StorageObject>() {

        @Override
        public StorageObject apply(Tuple<CloudStorageClient, com.google.api.services.storage.model.StorageObject> pb) {
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

        private BlobSourceOptions(StorageRpcClient.StorageOption rpcSetting, Object payload) {
            super(rpcSetting, payload);
        }

        private CloudStorageClient.BlobReadOption asSourceOptions(BlobMetadata blobMetadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return CloudStorageClient.BlobReadOption.ifGenerationMatch(blobMetadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return CloudStorageClient.BlobReadOption.generationNotMatch(blobMetadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return CloudStorageClient.BlobReadOption.ifMetagenerationMatch(blobMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return CloudStorageClient.BlobReadOption.ifMetagenerationNotMatch(blobMetadata.getMetageneration());
                case CUSTOMER_SUPPLIED_KEY:
                    return CloudStorageClient.BlobReadOption.withDecryptionKey((String) getValue());
                case USER_PROJECT:
                    return CloudStorageClient.BlobReadOption.withUserProject((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private CloudStorageClient.BlobGetOptions toBlobGetOption(BlobMetadata blobMetadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return CloudStorageClient.BlobGetOptions.ifGenerationMatch(blobMetadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return CloudStorageClient.BlobGetOptions.generationNotMatch(blobMetadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return CloudStorageClient.BlobGetOptions.ifMetagenerationMatch(blobMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return CloudStorageClient.BlobGetOptions.ifMetagenerationNotMatch(blobMetadata.getMetageneration());
                case USER_PROJECT:
                    return CloudStorageClient.BlobGetOptions.withUserProject((String) getValue());
                case CUSTOMER_SUPPLIED_KEY:
                    return CloudStorageClient.BlobGetOptions.withDecryptionKey((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for blob's generation match. If this option is used the request will fail
         * if generation does not match.
         */
        public static BlobSourceOptions ifGenerationMatch() {
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
        public static BlobSourceOptions ifMetagenerationMatch() {
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
        public static BlobSourceOptions withDecryptionKey(Key decryptionMaterial) {
            String base64Encoded = BaseEncoding.base64().encode(decryptionMaterial.getEncoded());
            return new BlobSourceOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, base64Encoded);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param decryptionMaterial the AES256 encoded in base64
         */
        public static BlobSourceOptions withDecryptionKey(String decryptionMaterial) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, decryptionMaterial);
        }

        /**
         * Returns an option for blob's billing user project. This option is used only if the blob's
         * bucket has requester_pays flag enabled.
         */
        public static BlobSourceOptions userProject(String projectId) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
        }

        static CloudStorageClient.BlobReadOption[] toSourceOptionsArray(BlobMetadata blobMetadata, BlobSourceOptions... clientSettings) {
            CloudStorageClient.BlobReadOption[] readOptionArray = new CloudStorageClient.BlobReadOption[clientSettings.length];
            int idx = 0;
            for (BlobSourceOptions sourceChoice : clientSettings) {
                readOptionArray[idx++] = sourceChoice.asSourceOptions(blobMetadata);
            }
            return readOptionArray;
        }

        static CloudStorageClient.BlobGetOptions[] toGetOptionsArray(BlobMetadata blobMetadata, BlobSourceOptions... clientSettings) {
            CloudStorageClient.BlobGetOptions[] readOptionArray = new CloudStorageClient.BlobGetOptions[clientSettings.length];
            int idx = 0;
            for (BlobSourceOptions sourceChoice : clientSettings) {
                readOptionArray[idx++] = sourceChoice.toBlobGetOption(blobMetadata);
            }
            return readOptionArray;
        }
    }

    /**
     * Downloads this blob to the given file path using specified blob read options.
     *
     * @param destination destination
     * @param clientSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public void downloadToPath(Path destination, BlobSourceOptions... clientSettings) {
        try (OutputStream outStream = Files.newOutputStream(destination)) {
            downloadToPath(outStream, clientSettings);
        } catch (IOException ioEx) {
            throw new StorageServiceException(ioEx);
        }
    }

    /**
     * Downloads this blob to the given output stream using specified blob read options.
     *
     * @param outStream
     * @param clientSettings
     */
    public void downloadToPath(OutputStream outStream, BlobSourceOptions... clientSettings) {
        final CountingOutputStream countingStream = new CountingOutputStream(outStream);
        final StorageRpcClient rpcClient = this.clientSettings.getStorageRpcV1();
        final Map<StorageRpcClient.StorageOption, ?> requestParams = StorageServiceImpl.buildOptionMap(getBlobId(), clientSettings);
        try {
            runWithRetries(callable(new Runnable() {

                @Override
                public void run() {
                    rpcClient.read(getBlobId().toProto(), requestParams, countingStream.getCount(), countingStream);
                }
            }), this.clientSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, this.clientSettings.getClock());
        } catch (RetryHelper.RetryHelperException ioEx) {
            StorageServiceException.translateThenThrow(ioEx);
        }
    }

    /**
     * Downloads this blob to the given file path.
     *
     * <p>This method is replaced with {@link #downloadToPath(Path, BlobSourceOptions...)}, but is kept
     * here for binary compatibility with the older versions of the client library.
     *
     * @param destination destination
     * @throws StorageServiceException upon failure
     */
    public void downloadToPath(Path destination) {
        this.downloadToPath(destination, new BlobSourceOptions[0]);
    }

    /**
     * Builder for {@code Blob}.
     */
    public static class BlobInfoBuilder extends BlobMetadataBuilder {

        private final CloudStorageClient cloudClient;

        private final BlobMetadataBuilderImpl metadataBuilder;

        BlobInfoBuilder(StorageObject storageObject) {
            this.cloudClient = storageObject.getStorage();
            this.metadataBuilder = new BlobMetadataBuilderImpl(storageObject);
        }

        @Override
        public StorageObject.BlobInfoBuilder setBlobId(BlobIdentifier blobIdentifier) {
            metadataBuilder.setBlobId(blobIdentifier);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setGeneratedId(String generatedIdentifier) {
            metadataBuilder.setGeneratedId(generatedIdentifier);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentType(String mimeType) {
            metadataBuilder.setContentType(mimeType);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentDisposition(String disposition) {
            metadataBuilder.setContentDisposition(disposition);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentLanguage(String languageTag) {
            metadataBuilder.setContentLanguage(languageTag);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentEncoding(String encoding) {
            metadataBuilder.setContentEncoding(encoding);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setComponentCount(Integer numComponents) {
            metadataBuilder.setComponentCount(numComponents);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCacheControl(String cacheDirective) {
            metadataBuilder.setCacheControl(cacheDirective);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setAcl(List<AccessControlEntry> accessControlList) {
            metadataBuilder.setAcl(accessControlList);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setOwner(ProtoEntity entityPrincipal) {
            metadataBuilder.setOwner(entityPrincipal);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setSize(Long lengthBytes) {
            metadataBuilder.setSize(lengthBytes);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setEtag(String entityTag) {
            metadataBuilder.setEtag(entityTag);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setSelfLink(String resourceLink) {
            metadataBuilder.setSelfLink(resourceLink);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMd5(String md5Digest) {
            metadataBuilder.setMd5(md5Digest);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMd5FromHexString(String md5Hex) {
            metadataBuilder.setMd5FromHexString(md5Hex);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCrc32c(String crcChecksum) {
            metadataBuilder.setCrc32c(crcChecksum);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCrc32cFromHexString(String crc32cHex) {
            metadataBuilder.setCrc32cFromHexString(crc32cHex);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setMediaLink(String mediaUrl) {
            metadataBuilder.setMediaLink(mediaUrl);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMetadata(Map<String, String> meta) {
            metadataBuilder.setMetadata(meta);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setStorageClass(StorageClassType storageTier) {
            metadataBuilder.setStorageClass(storageTier);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setTimeStorageClassUpdated(Long storageClassUpdateTime) {
            metadataBuilder.setTimeStorageClassUpdated(storageClassUpdateTime);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setMetageneration(Long metaGenerationNumber) {
            metadataBuilder.setMetageneration(metaGenerationNumber);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setDeleteTime(Long deletionTime) {
            metadataBuilder.setDeleteTime(deletionTime);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setUpdateTime(Long lastUpdateTime) {
            metadataBuilder.setUpdateTime(lastUpdateTime);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setCreateTime(Long creationTime) {
            metadataBuilder.setCreateTime(creationTime);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCustomTime(Long customTimestamp) {
            metadataBuilder.setCustomTime(customTimestamp);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
            metadataBuilder.setIsDirectory(directoryFlag);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryptionInfo encryptionDetails) {
            metadataBuilder.setCustomerEncryption(encryptionDetails);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setKmsKeyName(String kmsKeyId) {
            metadataBuilder.setKmsKeyName(kmsKeyId);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
            metadataBuilder.setEventBasedHold(eventHold);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setTemporaryHold(Boolean temporaryHoldFlag) {
            metadataBuilder.setTemporaryHold(temporaryHoldFlag);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
            metadataBuilder.setRetentionExpirationTime(retentionExpiryTime);
            return this;
        }

        @Override
        public StorageObject buildMetadata() {
            return new StorageObject(cloudClient, metadataBuilder);
        }
    }

    StorageObject(CloudStorageClient cloudClient, BlobMetadataBuilderImpl metadataBuilder) {
        super(metadataBuilder);
        this.cloudClient = checkNotNull(cloudClient);
        this.clientSettings = cloudClient.getOptions();
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
     * @param clientSettings blob read options
     * @return true if this blob exists, false otherwise
     * @throws StorageServiceException upon failure
     */
    public boolean existsInStorage(BlobSourceOptions... clientSettings) {
        int arrayLength = clientSettings.length;
        CloudStorageClient.BlobGetOptions[] resolvedGetOptions = Arrays.copyOf(toGetOptionsArray(this, clientSettings), arrayLength + 1);
        resolvedGetOptions[arrayLength] = CloudStorageClient.BlobGetOptions.selectFields();
        return null != cloudClient.get(getBlobId(), resolvedGetOptions);
    }

    /**
     * Returns this blob's content.
     *
     * <p>Example of reading all bytes of the blob, if its generation matches the {@link
     * StorageObject#getGeneration()} value, otherwise a {@link StorageServiceException} is thrown.
     *
     * <pre>{@code
     * byte[] content = blob.getContent(BlobSourceOption.generationMatch());
     * }</pre>
     *
     * @param clientSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public byte[] getContent(BlobSourceOptions... clientSettings) {
        return cloudClient.readAllBytes(getBlobId(), toSourceOptionsArray(this, clientSettings));
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
     * @param clientSettings preconditions to use on reload, see <a
     *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/get">https://cloud.google.com/storage/docs/json_api/v1/objects/get</a>
     *     for more information.
     * @return a {@code Blob} object with latest information or {@code null} if no longer exists.
     * @throws StorageServiceException upon failure
     */
    public StorageObject reloadFromStorage(BlobSourceOptions... clientSettings) {
        // BlobId with generation unset is needed to retrieve the latest version of the Blob
        BlobIdentifier baseIdentifier = BlobIdentifier.from(getBucket(), getName());
        return cloudClient.get(baseIdentifier, toGetOptionsArray(this, clientSettings));
    }

    /**
     * Updates the blob properties. The {@code options} parameter contains the preconditions for
     * applying the update. To update the properties call {@link #toBuilderCopy()}, set the properties you
     * want to change, build the new {@code Blob} instance, and then call {@link
     * #updateInStorage(CloudStorageClient.BlobUploadOption...)}.
     *
     * <p>The property update details are described in {@link CloudStorageClient#update(BlobMetadata)}. {@link
     * CloudStorageClient#update(BlobMetadata, BlobUploadOption...)} describes how to specify preconditions.
     *
     * <p>Example of updating the content type:
     *
     * <pre>{@code
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * Blob blob = storage.get(blobId);
     * blob.toBuilder().setContentType("text/plain").build().update();
     * }</pre>
     *
     * @param clientSettings preconditions to apply the update
     * @return the updated {@code Blob}
     * @throws StorageServiceException upon failure
     * @see <a
     *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
     */
    public StorageObject updateInStorage(CloudStorageClient.BlobUploadOption... clientSettings) {
        return cloudClient.update(this, clientSettings);
    }

    /**
     * Deletes this blob.
     *
     * <p>Example of deleting the blob, if its generation matches the {@link StorageObject#getGeneration()}
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
     * @param clientSettings blob delete options
     * @return {@code true} if blob was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    public boolean deleteFromStorage(BlobSourceOptions... clientSettings) {
        return cloudClient.delete(getBlobId(), toSourceOptionsArray(this, clientSettings));
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
     * @param clientSettings source blob options
     * @return a {@link BlobRewriteWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public BlobRewriteWriter copyToDestination(BlobIdentifier destinationBlobId, BlobSourceOptions... clientSettings) {
        CopyOperationRequest copyOperation = CopyOperationRequest.builder().setSource(getBucket(), getName()).setSourceOptions(toSourceOptionsArray(this, clientSettings)).setTarget(destinationBlobId).buildRequest();
        return cloudClient.copy(copyOperation);
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
     * @param clientSettings source blob options
     * @return a {@link BlobRewriteWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public BlobRewriteWriter copyToDestination(String destinationBucket, BlobSourceOptions... clientSettings) {
        return copyToDestination(destinationBucket, getName(), clientSettings);
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
     * @param clientSettings source blob options
     * @return a {@link BlobRewriteWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public BlobRewriteWriter copyToDestination(String destinationBucket, String destinationBlobId, BlobSourceOptions... clientSettings) {
        return copyToDestination(BlobIdentifier.from(destinationBucket, destinationBlobId), clientSettings);
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
     * @param clientSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public ReadChannel newReader(BlobSourceOptions... clientSettings) {
        return cloudClient.reader(getBlobId(), toSourceOptionsArray(this, clientSettings));
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
     * @param clientSettings target blob options
     * @throws StorageServiceException upon failure
     */
    public WriteChannel newWriter(BlobWriteOptions... clientSettings) {
        return cloudClient.writer(this, clientSettings);
    }

    /**
     * Generates a signed URL for this blob. If you want to allow access for a fixed amount of time to
     * this blob, you can use this method to generate a URL that is only valid within a certain time
     * period. This is particularly useful if you don't want publicly accessible blobs, but also don't
     * want to require users to explicitly log in. Signing a URL requires a service account signer. If
     * an instance of {@link com.google.auth.ServiceAccountSigner} was passed to {@link
     * StorageClientOptions}' builder via {@code setCredentials(Credentials)} or the default credentials are
     * being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set or your
     * application is running in App Engine, then {@code signUrl} will use that credentials to sign
     * the URL. If the credentials passed to {@link StorageClientOptions} do not implement {@link
     * ServiceAccountSigner} (this is the case, for instance, for Compute Engine credentials and
     * Google Cloud SDK credentials) then {@code signUrl} will throw an {@link IllegalStateException}
     * unless an implementation of {@link ServiceAccountSigner} is passed using the {@link
     * UrlSigningOption#withSigner(ServiceAccountSigner)} option.
     *
     * <p>A service account signer is looked for in the following order:
     *
     * <ol>
     *   <li>The signer passed with the option {@link UrlSigningOption#withSigner(ServiceAccountSigner)}
     *   <li>The credentials passed to {@link StorageClientOptions}
     *   <li>The default credentials, if no credentials were passed to {@link StorageClientOptions}
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
     * @param expirationMillis time until the signed URL expires, expressed in {@code unit}. The finer
     *     granularity supported is 1 second, finer granularities will be truncated
     * @param timeScale time unit of the {@code duration} parameter
     * @param clientSettings optional URL signing options
     * @return a signed URL for this blob and the specified options
     * @throws IllegalStateException if {@link UrlSigningOption#withSigner(ServiceAccountSigner)} was not
     *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
     *     StorageClientOptions}
     * @throws IllegalArgumentException if {@code SignUrlOption.withMd5()} option is used and {@code
     *     blobInfo.md5()} is {@code null}
     * @throws IllegalArgumentException if {@code SignUrlOption.withContentType()} option is used and
     *     {@code blobInfo.contentType()} is {@code null}
     * @throws SigningException if the attempt to sign the URL failed
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
     */
    public URL signUrlWithExpiration(long expirationMillis, TimeUnit timeScale, UrlSigningOption... clientSettings) {
        return cloudClient.signUrl(this, expirationMillis, timeScale, clientSettings);
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
    public AccessControlEntry getAcl(ProtoEntity protoPrincipal) {
        return cloudClient.getAcl(getBlobId(), protoPrincipal);
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
    public boolean deleteAclEntry(ProtoEntity protoPrincipal) {
        return cloudClient.deleteAcl(getBlobId(), protoPrincipal);
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
    public AccessControlEntry createAclEntry(AccessControlEntry accessControlList) {
        return cloudClient.createAcl(getBlobId(), accessControlList);
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
    public AccessControlEntry updateAclEntry(AccessControlEntry accessControlList) {
        return cloudClient.updateAcl(getBlobId(), accessControlList);
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
    public List<AccessControlEntry> listAclEntries() {
        return cloudClient.listAcls(getBlobId());
    }

    /**
     * Returns the blob's {@code Storage} object used to issue requests.
     */
    public CloudStorageClient getStorage() {
        return cloudClient;
    }

    @Override
    public StorageObject.BlobInfoBuilder toBuilderCopy() {
        return new BlobInfoBuilder(this);
    }

    /**
     * Returns true if obj instance {@code Blob.toPb()} metadata representation and {@code
     * Blob.options} instance of StorageOptions are both equal.
     */
    @Override
    public final boolean equals(Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (null == otherObject || !otherObject.getClass().equals(StorageObject.class)) {
            return false;
        }
        StorageObject comparedInstance = (StorageObject) otherObject;
        return Objects.equals(toProto(), comparedInstance.toProto()) && Objects.equals(clientSettings, comparedInstance.clientSettings);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), clientSettings);
    }

    private void readObject(ObjectInputStream objectStream) throws IOException, ClassNotFoundException {
        objectStream.defaultReadObject();
        this.cloudClient = clientSettings.getService();
    }

    static StorageObject fromProto(CloudStorageClient cloudClient, com.google.api.services.storage.model.StorageObject sourceObject) {
        BlobMetadata metadata = BlobMetadata.fromProto(sourceObject);
        return new StorageObject(cloudClient, new BlobMetadataBuilderImpl(metadata));
    }
}
