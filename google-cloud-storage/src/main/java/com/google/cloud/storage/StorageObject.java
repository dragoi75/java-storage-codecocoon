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

import static com.google.cloud.storage.StorageObject.BlobReadOption.toBlobGetOptionsArray;
import static com.google.cloud.storage.StorageObject.BlobReadOption.convertToSourceOptions;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AccessControlEntry.AbstractEntity;
import com.google.cloud.storage.Storage.BlobUploadOption;
import com.google.cloud.storage.Storage.BlobWriteOptions;
import com.google.cloud.storage.Storage.ChunkedCopyRequest;
import com.google.cloud.storage.Storage.UrlSigningOption;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.net.URL;
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
public class StorageObject extends BlobMetadata {

    private static final long serialVersionUID = -6806832496717441434L;

    private final StorageClientOptions clientConfig;

    private final RetryAlgorithmManager backoffManager;

    private transient Storage backendService;

    /**
     * Class for specifying blob source options when {@code Blob} methods are used.
     */
    public static class BlobReadOption extends AbstractOption {

        private static final long serialVersionUID = 214616862061934846L;

        private Storage.BlobGetOptions toBlobGetOption(BlobMetadata metadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return Storage.BlobGetOptions.ifGenerationMatch(metadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return Storage.BlobGetOptions.generationNotMatch(metadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return Storage.BlobGetOptions.ifMetagenerationMatch(metadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return Storage.BlobGetOptions.ifMetagenerationNotMatch(metadata.getMetageneration());
                case USER_PROJECT:
                    return Storage.BlobGetOptions.withUserProject((String) getValue());
                case CUSTOMER_SUPPLIED_KEY:
                    return Storage.BlobGetOptions.customerDecryptionKey((String) getValue());
                case RETURN_RAW_INPUT_STREAM:
                    return Storage.BlobGetOptions.returnRawInputStream((boolean) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        static Storage.BlobGetOptions[] toBlobGetOptionsArray(BlobMetadata metadata, BlobReadOption... clientConfig) {
            Storage.BlobGetOptions[] convertedSourceArray = new Storage.BlobGetOptions[clientConfig.length];
            int idx = 0;
            for (BlobReadOption readChoice : clientConfig) {
                convertedSourceArray[idx++] = readChoice.toBlobGetOption(metadata);
            }
            return convertedSourceArray;
        }

        private BlobReadOption(StorageRpcClient.StorageOption rpcSetting, Object inputObject) {
            super(rpcSetting, inputObject);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobReadOption customerDecryptionKey(Key decryptionKey) {
            String encodedKeyBase64 = BaseEncoding.base64().encode(decryptionKey.getEncoded());
            return new BlobReadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKeyBase64);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches.
         */
        public static BlobReadOption metagenerationNotMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        private BlobReadOption(StorageRpcClient.StorageOption rpcSetting) {
            super(rpcSetting, null);
        }

        static Storage.BlobSourceOptions[] convertToSourceOptions(BlobMetadata metadata, BlobReadOption... clientConfig) {
            Storage.BlobSourceOptions[] convertedSourceArray = new Storage.BlobSourceOptions[clientConfig.length];
            int idx = 0;
            for (BlobReadOption readChoice : clientConfig) {
                convertedSourceArray[idx++] = readChoice.toBlobSourceOptions(metadata);
            }
            return convertedSourceArray;
        }

        /**
         * Returns an option for whether the request should return the raw input stream, instead of
         * automatically decompressing the content. By default, this is false for Blob.downloadTo(), but
         * true for ReadChannel.read().
         */
        public static BlobReadOption shouldReturnRawInputStream(boolean returnRawStream) {
            return new BlobReadOption(StorageRpcClient.StorageOption.RETURN_RAW_INPUT_STREAM, returnRawStream);
        }

        /**
         * Returns an option for blob's billing user project. This option is used only if the blob's
         * bucket has requester_pays flag enabled.
         */
        public static BlobReadOption userProject(String billingProject) {
            return new BlobReadOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param decryptionKey the AES256 encoded in base64
         */
        public static BlobReadOption customerDecryptionKey(String decryptionKey) {
            return new BlobReadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, decryptionKey);
        }

        /**
         * Returns an option for blob's generation mismatch. If this option is used the request will
         * fail if generation matches.
         */
        public static BlobReadOption generationNotMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's generation match. If this option is used the request will fail
         * if generation does not match.
         */
        public static BlobReadOption ifGenerationMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BlobReadOption ifMetagenerationMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        private Storage.BlobSourceOptions toBlobSourceOptions(BlobMetadata metadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return Storage.BlobSourceOptions.ifGenerationMatch(metadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return Storage.BlobSourceOptions.generationNotMatch(metadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return Storage.BlobSourceOptions.ifMetagenerationMatch(metadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return Storage.BlobSourceOptions.ifMetagenerationNotMatch(metadata.getMetageneration());
                case CUSTOMER_SUPPLIED_KEY:
                    return Storage.BlobSourceOptions.customerDecryptionKey((String) getValue());
                case USER_PROJECT:
                    return Storage.BlobSourceOptions.withUserProject((String) getValue());
                case RETURN_RAW_INPUT_STREAM:
                    return Storage.BlobSourceOptions.returnRawInputStream((boolean) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

    }

    /**
     * Builder for {@code Blob}.
     */
    public static class BlobInfoBuilder extends StorageObjectBuilder {

        private final Storage backendService;

        private final BlobInfoBuilderImpl metadataBuilder;

        @Override
        StorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
            metadataBuilder.setIsDirectory(directoryFlag);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMetadata(Map<String, String> metaMap) {
            metadataBuilder.setMetadata(metaMap);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setDeleteTime(Long deletionTime) {
            metadataBuilder.setDeleteTime(deletionTime);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setMediaLink(String contentUrl) {
            metadataBuilder.setMediaLink(contentUrl);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setCreateTime(Long creationTime) {
            metadataBuilder.setCreateTime(creationTime);
            return this;
        }

        BlobInfoBuilder(StorageObject storageObject) {
            this.backendService = storageObject.getStorage();
            this.metadataBuilder = new BlobInfoBuilderImpl(storageObject);
        }

        @Override
        public StorageObject.BlobInfoBuilder setStorageClass(StorageTier storageTier) {
            metadataBuilder.setStorageClass(storageTier);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setTemporaryHold(Boolean tempHold) {
            metadataBuilder.setTemporaryHold(tempHold);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentLanguage(String languageTag) {
            metadataBuilder.setContentLanguage(languageTag);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryptionMetadata encryptionMetadata) {
            metadataBuilder.setCustomerEncryption(encryptionMetadata);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setComponentCount(Integer partCount) {
            metadataBuilder.setComponentCount(partCount);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setKmsKeyName(String kmsKey) {
            metadataBuilder.setKmsKeyName(kmsKey);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCacheControl(String cacheDirective) {
            metadataBuilder.setCacheControl(cacheDirective);
            return this;
        }

        @Override
        public StorageObject buildObject() {
            return new StorageObject(backendService, metadataBuilder);
        }

        @Override
        StorageObject.BlobInfoBuilder setSelfLink(String resourceUri) {
            metadataBuilder.setSelfLink(resourceUri);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMd5(String contentHash) {
            metadataBuilder.setMd5(contentHash);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setTimeStorageClassUpdated(Long storageClassUpdateTime) {
            metadataBuilder.setTimeStorageClassUpdated(storageClassUpdateTime);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setGeneratedId(String generatedIdentifier) {
            metadataBuilder.setGeneratedId(generatedIdentifier);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
            metadataBuilder.setRetentionExpirationTime(retentionExpiryTime);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setEtag(String entityTag) {
            metadataBuilder.setEtag(entityTag);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setOwner(AbstractEntity principalEntity) {
            metadataBuilder.setOwner(principalEntity);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
            metadataBuilder.setEventBasedHold(eventHold);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setMetageneration(Long metaVersion) {
            metadataBuilder.setMetageneration(metaVersion);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentType(String mimeType) {
            metadataBuilder.setContentType(mimeType);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCustomTime(Long customTimestamp) {
            metadataBuilder.setCustomTime(customTimestamp);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setSize(Long byteSize) {
            metadataBuilder.setSize(byteSize);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCrc32cFromHexString(String checksumHexValue) {
            metadataBuilder.setCrc32cFromHexString(checksumHexValue);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentDisposition(String disposition) {
            metadataBuilder.setContentDisposition(disposition);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setUpdateTime(Long lastUpdateTime) {
            metadataBuilder.setUpdateTime(lastUpdateTime);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setAcl(List<AccessControlEntry> accessList) {
            metadataBuilder.setAcl(accessList);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setBlobId(BlobId identifier) {
            metadataBuilder.setBlobId(identifier);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCrc32c(String contentChecksum) {
            metadataBuilder.setCrc32c(contentChecksum);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentEncoding(String encoding) {
            metadataBuilder.setContentEncoding(encoding);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMd5FromHexString(String hashHexValue) {
            metadataBuilder.setMd5FromHexString(hashHexValue);
            return this;
        }

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
    public AccessControlEntry updateAclEntry(AccessControlEntry accessList) {
        return backendService.updateAcl(getBlobId(), accessList);
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
     * @param clientConfig source blob options
     * @return a {@link ObjectCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ObjectCopyWriter copyToTarget(String destinationBucket, BlobReadOption... clientConfig) {
        return copyToTarget(destinationBucket, getName(), clientConfig);
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
        return backendService.listAcls(getBlobId());
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
     * @param clientConfig source blob options
     * @return a {@link ObjectCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ObjectCopyWriter copyToTarget(String destinationBucket, String destinationBlobId, BlobReadOption... clientConfig) {
        return copyToTarget(BlobId.from(destinationBucket, destinationBlobId), clientConfig);
    }

    private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
        inputStream.defaultReadObject();
        this.backendService = clientConfig.getService();
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
     * @param clientConfig blob read options
     * @return true if this blob exists, false otherwise
     * @throws StorageServiceException upon failure
     */
    public boolean blobExists(BlobReadOption... clientConfig) {
        int bufLen = clientConfig.length;
        Storage.BlobGetOptions[] retrieveOptions = Arrays.copyOf(toBlobGetOptionsArray(this, clientConfig), bufLen + 1);
        retrieveOptions[bufLen] = Storage.BlobGetOptions.selectFields();
        return null != backendService.get(getBlobId(), retrieveOptions);
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
    public AccessControlEntry createAclEntry(AccessControlEntry accessList) {
        return backendService.createAcl(getBlobId(), accessList);
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
        StorageObject comparedObject = (StorageObject) otherObject;
        return Objects.equals(toProto(), comparedObject.toProto()) && Objects.equals(clientConfig, comparedObject.clientConfig);
    }

    @Override
    public StorageObject.BlobInfoBuilder toInfoBuilder() {
        return new BlobInfoBuilder(this);
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
     * @param clientConfig blob delete options
     * @return {@code true} if blob was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    public boolean deleteFromStorage(BlobReadOption... clientConfig) {
        return backendService.delete(getBlobId(), convertToSourceOptions(this, clientConfig));
    }

    /**
     * Downloads this blob to the given file path using specified blob read options.
     *
     * @param destinationFile destination
     * @param clientConfig blob read options
     * @throws StorageServiceException upon failure
     */
    public void downloadToPath(Path destinationFile, BlobReadOption... clientConfig) {
        backendService.downloadTo(getBlobId(), destinationFile, BlobReadOption.convertToSourceOptions(this, clientConfig));
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), clientConfig);
    }

    /**
     * Downloads this blob to the given file path.
     *
     * <p>This method is replaced with {@link #downloadToPath(Path, BlobReadOption...)}, but is kept
     * here for binary compatibility with the older versions of the client library.
     *
     * @param destinationFile destination
     * @throws StorageServiceException upon failure
     */
    public void downloadToPath(Path destinationFile) {
        this.downloadToPath(destinationFile, new BlobReadOption[0]);
    }

    static StorageObject fromProto(Storage backendService, com.google.api.services.storage.model.StorageObject sourceObject) {
        BlobMetadata metadata = BlobMetadata.fromProto(sourceObject);
        return new StorageObject(backendService, new BlobInfoBuilderImpl(metadata));
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
     * @param clientConfig blob read options
     * @throws StorageServiceException upon failure
     */
    public byte[] getContent(BlobReadOption... clientConfig) {
        return backendService.readAllBytes(getBlobId(), convertToSourceOptions(this, clientConfig));
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
     * @param clientConfig source blob options
     * @return a {@link ObjectCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ObjectCopyWriter copyToTarget(BlobId destinationBlobId, BlobReadOption... clientConfig) {
        ChunkedCopyRequest copyJob = ChunkedCopyRequest.newCopyOperationBuilder().setSource(getBucket(), getName()).setSourceOptions(convertToSourceOptions(this, clientConfig)).setTarget(destinationBlobId).buildCopyRequest();
        return backendService.copy(copyJob);
    }

    StorageObject(Storage backendService, BlobInfoBuilderImpl metadataBuilder) {
        super(metadataBuilder);
        this.backendService = checkNotNull(backendService);
        this.clientConfig = backendService.getOptions();
        this.backoffManager = backendService.getOptions().getRetryAlgorithmManager();
    }

    /**
     * Generates a signed URL for this blob. If you want to allow access for a fixed amount of time to
     * this blob, you can use this method to generate a URL that is only valid within a certain time
     * period. This is particularly useful if you don't want publicly accessible blobs, but also don't
     * want to require users to explicitly log in. Signing a URL requires a service account signer. If
     * an instance of {@link ServiceAccountSigner} was passed to {@link
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
     * @param timeSpan time until the signed URL expires, expressed in {@code unit}. The finer
     *     granularity supported is 1 second, finer granularities will be truncated
     * @param timeScale time unit of the {@code duration} parameter
     * @param clientConfig optional URL signing options
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
    public URL generateSignedUrl(long timeSpan, TimeUnit timeScale, UrlSigningOption... clientConfig) {
        return backendService.signUrl(this, timeSpan, timeScale, clientConfig);
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
     * @param clientConfig blob read options
     * @throws StorageServiceException upon failure
     */
    public ReadChannel newReader(BlobReadOption... clientConfig) {
        return backendService.reader(getBlobId(), convertToSourceOptions(this, clientConfig));
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
     * @param clientConfig preconditions to use on reload, see <a
     *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/get">https://cloud.google.com/storage/docs/json_api/v1/objects/get</a>
     *     for more information.
     * @return a {@code Blob} object with latest information or {@code null} if no longer exists.
     * @throws StorageServiceException upon failure
     */
    public StorageObject reloadFromStorage(BlobReadOption... clientConfig) {
        // BlobId with generation unset is needed to retrieve the latest version of the Blob
        BlobId idNoGeneration = BlobId.from(getBucket(), getName());
        return backendService.get(idNoGeneration, toBlobGetOptionsArray(this, clientConfig));
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
     * @param clientConfig target blob options
     * @throws StorageServiceException upon failure
     */
    public WriteChannel newWriter(BlobWriteOptions... clientConfig) {
        return backendService.writer(this, clientConfig);
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
    public AccessControlEntry getAcl(AbstractEntity principal) {
        return backendService.getAcl(getBlobId(), principal);
    }

    /**
     * Updates the blob properties. The {@code options} parameter contains the preconditions for
     * applying the update. To update the properties call {@link #toInfoBuilder()}, set the properties you
     * want to change, build the new {@code Blob} instance, and then call {@link
     * #updateInStorage(BlobUploadOption...)}.
     *
     * <p>The property update details are described in {@link Storage#update(BlobMetadata)}. {@link
     * Storage#update(BlobMetadata, BlobUploadOption...)} describes how to specify preconditions.
     *
     * <p>Example of updating the content type:
     *
     * <pre>{@code
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * Blob blob = storage.get(blobId);
     * blob.toBuilder().setContentType("text/plain").build().update();
     * }</pre>
     *
     * @param clientConfig preconditions to apply the update
     * @return the updated {@code Blob}
     * @throws StorageServiceException upon failure
     * @see <a
     *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
     */
    public StorageObject updateInStorage(BlobUploadOption... clientConfig) {
        return backendService.update(this, clientConfig);
    }

    /**
     * Returns the blob's {@code Storage} object used to issue requests.
     */
    public Storage getStorage() {
        return backendService;
    }

    /**
     * Downloads this blob to the given output stream using specified blob read options.
     *
     * @param outStream
     * @param clientConfig
     */
    public void downloadToPath(OutputStream outStream, BlobReadOption... clientConfig) {
        backendService.downloadTo(getBlobId(), outStream, BlobReadOption.convertToSourceOptions(this, clientConfig));
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
    public boolean deleteAclEntry(AbstractEntity principal) {
        return backendService.deleteAcl(getBlobId(), principal);
    }

}
