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
import static com.google.cloud.storage.StorageBlob.BlobSourceOptions.convertToGetOptions;
import static com.google.cloud.storage.StorageBlob.BlobSourceOptions.convertToSourceOptions;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.callable;
import com.google.api.services.storage.model.StorageObject;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.RetryHelper;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.CloudStorageClient.BlobUploadOptions;
import com.google.cloud.storage.CloudStorageClient.BlobWriteOptions;
import com.google.cloud.storage.CloudStorageClient.UrlSigningOption;
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
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
 * {@link #copyToBlob} return a new object. Any changes to the object in Google Cloud Storage made after
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
public class StorageBlob extends BlobMetadata {

    private static final long serialVersionUID = -6806832496717441434L;

    private final StorageClientOptions clientSettings;

    private transient CloudStorageClient cloudClient;

    static final Function<Tuple<CloudStorageClient, StorageObject>, StorageBlob> BLOB_FROM_PB_FUNCTION = new Function<Tuple<CloudStorageClient, StorageObject>, StorageBlob>() {

        @Override
        public StorageBlob apply(Tuple<CloudStorageClient, StorageObject> pb) {
            return StorageBlob.fromProto(pb.x(), pb.y());
        }
    };

    private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;

    /**
     * Class for specifying blob source options when {@code Blob} methods are used.
     */
    public static class BlobSourceOptions extends OptionDescriptor {

        private static final long serialVersionUID = 214616862061934846L;

        private BlobSourceOptions(StorageServiceRpc.StorageOption rpcSetting) {
            super(rpcSetting, null);
        }

        private BlobSourceOptions(StorageServiceRpc.StorageOption rpcSetting, Object input) {
            super(rpcSetting, input);
        }

        private CloudStorageClient.BlobReadOption toReadOptions(BlobMetadata metadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return CloudStorageClient.BlobReadOption.ifGenerationMatch(metadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return CloudStorageClient.BlobReadOption.generationNotMatch(metadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return CloudStorageClient.BlobReadOption.ifMetagenerationMatch(metadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return CloudStorageClient.BlobReadOption.ifMetagenerationNotMatch(metadata.getMetageneration());
                case CUSTOMER_SUPPLIED_KEY:
                    return CloudStorageClient.BlobReadOption.customerSuppliedKey((String) getValue());
                case USER_PROJECT:
                    return CloudStorageClient.BlobReadOption.withUserProject((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private CloudStorageClient.BlobGetOptions toBlobGetOption(BlobMetadata metadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return CloudStorageClient.BlobGetOptions.ifGenerationMatch(metadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return CloudStorageClient.BlobGetOptions.generationNotMatch(metadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return CloudStorageClient.BlobGetOptions.ifMetagenerationMatch(metadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return CloudStorageClient.BlobGetOptions.ifMetagenerationNotMatch(metadata.getMetageneration());
                case USER_PROJECT:
                    return CloudStorageClient.BlobGetOptions.withUserProject((String) getValue());
                case CUSTOMER_SUPPLIED_KEY:
                    return CloudStorageClient.BlobGetOptions.decryptionKeyBase64((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for blob's generation match. If this option is used the request will fail
         * if generation does not match.
         */
        public static BlobSourceOptions ifGenerationMatch() {
            return new BlobSourceOptions(StorageServiceRpc.StorageOption.IF_GENERATION_MATCH);
        }

        /**
         * Returns an option for blob's generation mismatch. If this option is used the request will
         * fail if generation matches.
         */
        public static BlobSourceOptions generationNotMatch() {
            return new BlobSourceOptions(StorageServiceRpc.StorageOption.IF_GENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BlobSourceOptions ifMetagenerationMatch() {
            return new BlobSourceOptions(StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches.
         */
        public static BlobSourceOptions metagenerationNotMatch() {
            return new BlobSourceOptions(StorageServiceRpc.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobSourceOptions decryptionKeyBase64(Key decryptionKey) {
            String encodedKeyBase64 = BaseEncoding.base64().encode(decryptionKey.getEncoded());
            return new BlobSourceOptions(StorageServiceRpc.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKeyBase64);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param decryptionKey the AES256 encoded in base64
         */
        public static BlobSourceOptions decryptionKeyBase64(String decryptionKey) {
            return new BlobSourceOptions(StorageServiceRpc.StorageOption.CUSTOMER_SUPPLIED_KEY, decryptionKey);
        }

        /**
         * Returns an option for blob's billing user project. This option is used only if the blob's
         * bucket has requester_pays flag enabled.
         */
        public static BlobSourceOptions userProject(String projectId) {
            return new BlobSourceOptions(StorageServiceRpc.StorageOption.USER_PROJECT, projectId);
        }

        static CloudStorageClient.BlobReadOption[] convertToSourceOptions(BlobMetadata metadata, BlobSourceOptions... clientSettings) {
            CloudStorageClient.BlobReadOption[] readOptionArray = new CloudStorageClient.BlobReadOption[clientSettings.length];
            int idx = 0;
            for (BlobSourceOptions srcOpt : clientSettings) {
                readOptionArray[idx++] = srcOpt.toReadOptions(metadata);
            }
            return readOptionArray;
        }

        static CloudStorageClient.BlobGetOptions[] convertToGetOptions(BlobMetadata metadata, BlobSourceOptions... clientSettings) {
            CloudStorageClient.BlobGetOptions[] readOptionArray = new CloudStorageClient.BlobGetOptions[clientSettings.length];
            int idx = 0;
            for (BlobSourceOptions srcOpt : clientSettings) {
                readOptionArray[idx++] = srcOpt.toBlobGetOption(metadata);
            }
            return readOptionArray;
        }
    }

    /**
     * Downloads this blob to the given file path using specified blob read options.
     *
     * @param targetPath destination
     * @param clientSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public void downloadToPath(Path targetPath, BlobSourceOptions... clientSettings) {
        try (OutputStream outStream = Files.newOutputStream(targetPath)) {
            downloadToPath(outStream, clientSettings);
        } catch (IOException ioException) {
            throw new StorageServiceException(ioException);
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
        final StorageServiceRpc rpcService = this.clientSettings.getStorageRpcV1();
        final Map<StorageServiceRpc.StorageOption, ?> requestParams = DefaultStorage.buildOptionMap(getBlobId(), clientSettings);
        try {
            runWithRetries(callable(new Runnable() {

                @Override
                public void run() {
                    rpcService.read(getBlobId().toProto(), requestParams, countingStream.getCount(), countingStream);
                }
            }), this.clientSettings.getRetrySettings(), DefaultStorage.EXCEPTION_HANDLER, this.clientSettings.getClock());
        } catch (RetryHelper.RetryHelperException ioException) {
            StorageServiceException.translateAndRethrow(ioException);
        }
    }

    /**
     * Downloads this blob to the given file path.
     *
     * <p>This method is replaced with {@link #downloadToPath(Path, BlobSourceOptions...)}, but is kept
     * here for binary compatibility with the older versions of the client library.
     *
     * @param targetPath destination
     * @throws StorageServiceException upon failure
     */
    public void downloadToPath(Path targetPath) {
        this.downloadToPath(targetPath, new BlobSourceOptions[0]);
    }

    /**
     * Builder for {@code Blob}.
     */
    public static class BlobInfoBuilder extends StorageObjectBuilder {

        private final CloudStorageClient cloudClient;

        private final BlobInfoBuilderImpl builderImpl;

        BlobInfoBuilder(StorageBlob sourceObject) {
            this.cloudClient = sourceObject.getStorage();
            this.builderImpl = new BlobInfoBuilderImpl(sourceObject);
        }

        @Override
        public StorageBlob.BlobInfoBuilder setBlobId(BlobIdentifier blobIdentifier) {
            builderImpl.setBlobId(blobIdentifier);
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
        public StorageBlob.BlobInfoBuilder setContentDisposition(String dispositionHeader) {
            builderImpl.setContentDisposition(dispositionHeader);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setContentLanguage(String languageTag) {
            builderImpl.setContentLanguage(languageTag);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setContentEncoding(String encodingScheme) {
            builderImpl.setContentEncoding(encodingScheme);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setComponentCount(Integer partCount) {
            builderImpl.setComponentCount(partCount);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setCacheControl(String cacheDirective) {
            builderImpl.setCacheControl(cacheDirective);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setAcl(List<AccessControlEntry> accessControlList) {
            builderImpl.setAcl(accessControlList);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setOwner(AccessControlEntry.TypedEntity principalEntity) {
            builderImpl.setOwner(principalEntity);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setSize(Long lengthBytes) {
            builderImpl.setSize(lengthBytes);
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
        public StorageBlob.BlobInfoBuilder setMd5(String contentHash) {
            builderImpl.setMd5(contentHash);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setMd5FromHexString(String digestHex) {
            builderImpl.setMd5FromHexString(digestHex);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setCrc32c(String crcChecksum) {
            builderImpl.setCrc32c(crcChecksum);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setCrc32cFromHexString(String hexChecksum) {
            builderImpl.setCrc32cFromHexString(hexChecksum);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setMediaLink(String mediaUrl) {
            builderImpl.setMediaLink(mediaUrl);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setMetadata(Map<String, String> metaTags) {
            builderImpl.setMetadata(metaTags);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setStorageClass(StorageTier storageTier) {
            builderImpl.setStorageClass(storageTier);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setTimeStorageClassUpdated(Long storageTierUpdateTime) {
            builderImpl.setTimeStorageClassUpdated(storageTierUpdateTime);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setMetageneration(Long metadataVersion) {
            builderImpl.setMetageneration(metadataVersion);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setDeleteTime(Long deletionTimestamp) {
            builderImpl.setDeleteTime(deletionTimestamp);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setUpdateTime(Long timeUpdated) {
            builderImpl.setUpdateTime(timeUpdated);
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
        StorageBlob.BlobInfoBuilder setCustomerEncryption(CustomerEncryptionInfo encryptionInfo) {
            builderImpl.setCustomerEncryption(encryptionInfo);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setKmsKeyName(String keyResourceName) {
            builderImpl.setKmsKeyName(keyResourceName);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
            builderImpl.setEventBasedHold(eventHold);
            return this;
        }

        @Override
        public StorageBlob.BlobInfoBuilder setTemporaryHold(Boolean temporaryLock) {
            builderImpl.setTemporaryHold(temporaryLock);
            return this;
        }

        @Override
        StorageBlob.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiry) {
            builderImpl.setRetentionExpirationTime(retentionExpiry);
            return this;
        }

        @Override
        public StorageBlob buildObject() {
            return new StorageBlob(cloudClient, builderImpl);
        }
    }

    StorageBlob(CloudStorageClient cloudClient, BlobInfoBuilderImpl builderImpl) {
        super(builderImpl);
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
    public boolean blobExists(BlobSourceOptions... clientSettings) {
        int bytesRead = clientSettings.length;
        CloudStorageClient.BlobGetOptions[] retrievalParams = Arrays.copyOf(convertToGetOptions(this, clientSettings), bytesRead + 1);
        retrievalParams[bytesRead] = CloudStorageClient.BlobGetOptions.selectFields();
        return null != cloudClient.get(getBlobId(), retrievalParams);
    }

    /**
     * Returns this blob's content.
     *
     * <p>Example of reading all bytes of the blob, if its generation matches the {@link
     * StorageBlob#getGeneration()} value, otherwise a {@link StorageServiceException} is thrown.
     *
     * <pre>{@code
     * byte[] content = blob.getContent(BlobSourceOption.generationMatch());
     * }</pre>
     *
     * @param clientSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public byte[] getContent(BlobSourceOptions... clientSettings) {
        return cloudClient.readAllBytes(getBlobId(), convertToSourceOptions(this, clientSettings));
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
    public StorageBlob reloadFromStorage(BlobSourceOptions... clientSettings) {
        // BlobId with generation unset is needed to retrieve the latest version of the Blob
        BlobIdentifier identifierWithoutGeneration = BlobIdentifier.create(getBucket(), getName());
        return cloudClient.get(identifierWithoutGeneration, convertToGetOptions(this, clientSettings));
    }

    /**
     * Updates the blob properties. The {@code options} parameter contains the preconditions for
     * applying the update. To update the properties call {@link #asBuilder()}, set the properties you
     * want to change, build the new {@code Blob} instance, and then call {@link
     * #updateInStorage(BlobUploadOptions...)}.
     *
     * <p>The property update details are described in {@link CloudStorageClient#update(BlobMetadata)}. {@link
     * CloudStorageClient#update(BlobMetadata, BlobUploadOptions...)} describes how to specify preconditions.
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
    public StorageBlob updateInStorage(BlobUploadOptions... clientSettings) {
        return cloudClient.update(this, clientSettings);
    }

    /**
     * Deletes this blob.
     *
     * <p>Example of deleting the blob, if its generation matches the {@link StorageBlob#getGeneration()}
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
        return cloudClient.delete(getBlobId(), convertToSourceOptions(this, clientSettings));
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
     * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public BlobCopyWriter copyToBlob(BlobIdentifier destinationBlobId, BlobSourceOptions... clientSettings) {
        CloudStorageClient.CopyOperationRequest transferRequest = CloudStorageClient.CopyOperationRequest.builder().setSource(getBucket(), getName()).setSourceOptions(convertToSourceOptions(this, clientSettings)).setTarget(destinationBlobId).create();
        return cloudClient.copy(transferRequest);
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
     * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public BlobCopyWriter copyToBlob(String destinationBucket, BlobSourceOptions... clientSettings) {
        return copyToBlob(destinationBucket, getName(), clientSettings);
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
     * @return a {@link BlobCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public BlobCopyWriter copyToBlob(String destinationBucket, String destinationBlobId, BlobSourceOptions... clientSettings) {
        return copyToBlob(BlobIdentifier.create(destinationBucket, destinationBlobId), clientSettings);
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
    public ReadChannel openReader(BlobSourceOptions... clientSettings) {
        return cloudClient.reader(getBlobId(), convertToSourceOptions(this, clientSettings));
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
    public WriteChannel openWriter(BlobWriteOptions... clientSettings) {
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
     * CloudStorageClient.UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used to sign the URL:
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
     * @param expiryTime time until the signed URL expires, expressed in {@code unit}. The finer
     *     granularity supported is 1 second, finer granularities will be truncated
     * @param timeMeasure time unit of the {@code duration} parameter
     * @param clientSettings optional URL signing options
     * @return a signed URL for this blob and the specified options
     * @throws IllegalStateException if {@link CloudStorageClient.UrlSigningOption#withSigner(ServiceAccountSigner)} was not
     *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
     *     StorageClientOptions}
     * @throws IllegalArgumentException if {@code SignUrlOption.withMd5()} option is used and {@code
     *     blobInfo.md5()} is {@code null}
     * @throws IllegalArgumentException if {@code SignUrlOption.withContentType()} option is used and
     *     {@code blobInfo.contentType()} is {@code null}
     * @throws SigningException if the attempt to sign the URL failed
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
     */
    public URL createSignedUrl(long expiryTime, TimeUnit timeMeasure, CloudStorageClient.UrlSigningOption... clientSettings) {
        return cloudClient.signUrl(this, expiryTime, timeMeasure, clientSettings);
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
    public AccessControlEntry getAcl(AccessControlEntry.TypedEntity principal) {
        return cloudClient.getAcl(getBlobId(), principal);
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
    public boolean removeAcl(AccessControlEntry.TypedEntity principal) {
        return cloudClient.deleteAcl(getBlobId(), principal);
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
    public AccessControlEntry addAcl(AccessControlEntry accessControlList) {
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
    public AccessControlEntry updateAccessControl(AccessControlEntry accessControlList) {
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
    public List<AccessControlEntry> listAccessControls() {
        return cloudClient.listAcls(getBlobId());
    }

    /**
     * Returns the blob's {@code Storage} object used to issue requests.
     */
    public CloudStorageClient getStorage() {
        return cloudClient;
    }

    @Override
    public StorageBlob.BlobInfoBuilder asBuilder() {
        return new BlobInfoBuilder(this);
    }

    /**
     * Returns true if obj instance {@code Blob.toPb()} metadata representation and {@code
     * Blob.options} instance of StorageOptions are both equal.
     */
    @Override
    public final boolean equals(Object otherValue) {
        if (this == otherValue) {
            return true;
        }
        if (null == otherValue || !otherValue.getClass().equals(StorageBlob.class)) {
            return false;
        }
        StorageBlob comparedBlob = (StorageBlob) otherValue;
        return Objects.equals(toProto(), comparedBlob.toProto()) && Objects.equals(clientSettings, comparedBlob.clientSettings);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), clientSettings);
    }

    private void readObject(ObjectInputStream objectStream) throws IOException, ClassNotFoundException {
        objectStream.defaultReadObject();
        this.cloudClient = clientSettings.getService();
    }

    static StorageBlob fromProto(CloudStorageClient cloudClient, StorageObject protoObject) {
        BlobMetadata metadata = BlobMetadata.fromPb(protoObject);
        return new StorageBlob(cloudClient, new BlobInfoBuilderImpl(metadata));
    }
}
