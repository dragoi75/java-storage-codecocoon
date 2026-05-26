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
import static com.google.cloud.storage.StorageObject.BlobReadOption.toGetOptionsArray;
import static com.google.cloud.storage.StorageObject.BlobReadOption.toSourceOptionsArray;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.Executors.callable;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.ReadChannel;
import com.google.cloud.RetryHelper;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AclEntry.BaseEntity;
import com.google.cloud.storage.Storage.BlobWriteOptions;
import com.google.cloud.storage.Storage.CopyOperationRequest;
import com.google.cloud.storage.Storage.UrlSigningOption;
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
 * the set of properties inherited from the {@link BlobInfo} class and the {@code Storage} instance.
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
public class StorageObject extends BlobInfo {

    private static final long serialVersionUID = -6806832496717441434L;

    private final StorageSettings storageSettings;

    private transient Storage backendClient;

    static final Function<Tuple<Storage, com.google.api.services.storage.model.StorageObject>, StorageObject> BLOB_FROM_PB_FUNCTION = new Function<Tuple<Storage, com.google.api.services.storage.model.StorageObject>, StorageObject>() {

        @Override
        public StorageObject apply(Tuple<Storage, com.google.api.services.storage.model.StorageObject> pb) {
            return StorageObject.fromProto(pb.x(), pb.y());
        }
    };

    private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;

    /**
     * Class for specifying blob source options when {@code Blob} methods are used.
     */
    public static class BlobReadOption extends AbstractOption {

        private static final long serialVersionUID = 214616862061934846L;

        /**
         * Returns an option for blob's generation mismatch. If this option is used the request will
         * fail if generation matches.
         */
        public static BlobReadOption generationNotMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param decryptionSecret the AES256 encoded in base64
         */
        public static BlobReadOption withDecryptionKey(String decryptionSecret) {
            return new BlobReadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, decryptionSecret);
        }

        private Storage.BlobGetOptions toBlobGetOption(BlobInfo blobMetadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return Storage.BlobGetOptions.ifGenerationMatch(blobMetadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return Storage.BlobGetOptions.generationNotMatch(blobMetadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return Storage.BlobGetOptions.ifMetagenerationMatch(blobMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return Storage.BlobGetOptions.ifMetagenerationNotMatch(blobMetadata.getMetageneration());
                case USER_PROJECT:
                    return Storage.BlobGetOptions.withUserProject((String) getValue());
                case CUSTOMER_SUPPLIED_KEY:
                    return Storage.BlobGetOptions.withDecryptionKey((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobReadOption withDecryptionKey(Key decryptionSecret) {
            String base64EncodedString = BaseEncoding.base64().encode(decryptionSecret.getEncoded());
            return new BlobReadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedString);
        }

        static Storage.BlobSourceSettings[] toSourceOptionsArray(BlobInfo blobMetadata, BlobReadOption... storageSettings) {
            Storage.BlobSourceSettings[] blobSourceSettingsArray = new Storage.BlobSourceSettings[storageSettings.length];
            int idx = 0;
            for (BlobReadOption readEntry : storageSettings) {
                blobSourceSettingsArray[idx++] = readEntry.toBlobSourceOptions(blobMetadata);
            }
            return blobSourceSettingsArray;
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches.
         */
        public static BlobReadOption metagenerationNotMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's generation match. If this option is used the request will fail
         * if generation does not match.
         */
        public static BlobReadOption generationMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH);
        }

        /**
         * Returns an option for blob's billing user project. This option is used only if the blob's
         * bucket has requester_pays flag enabled.
         */
        public static BlobReadOption userProject(String projectId) {
            return new BlobReadOption(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
        }

        private Storage.BlobSourceSettings toBlobSourceOptions(BlobInfo blobMetadata) {
            switch(getRpcOption()) {
                case IF_GENERATION_MATCH:
                    return Storage.BlobSourceSettings.ifGenerationMatch(blobMetadata.getGeneration());
                case IF_GENERATION_NOT_MATCH:
                    return Storage.BlobSourceSettings.generationNotMatch(blobMetadata.getGeneration());
                case IF_METAGENERATION_MATCH:
                    return Storage.BlobSourceSettings.ifMetagenerationMatch(blobMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return Storage.BlobSourceSettings.ifMetagenerationNotMatch(blobMetadata.getMetageneration());
                case CUSTOMER_SUPPLIED_KEY:
                    return Storage.BlobSourceSettings.withDecryptionKey((String) getValue());
                case USER_PROJECT:
                    return Storage.BlobSourceSettings.withUserProject((String) getValue());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private BlobReadOption(StorageRpcClient.StorageOption rpcSetting) {
            super(rpcSetting, null);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BlobReadOption ifMetagenerationMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        static Storage.BlobGetOptions[] toGetOptionsArray(BlobInfo blobMetadata, BlobReadOption... storageSettings) {
            Storage.BlobGetOptions[] blobSourceSettingsArray = new Storage.BlobGetOptions[storageSettings.length];
            int idx = 0;
            for (BlobReadOption readEntry : storageSettings) {
                blobSourceSettingsArray[idx++] = readEntry.toBlobGetOption(blobMetadata);
            }
            return blobSourceSettingsArray;
        }

        private BlobReadOption(StorageRpcClient.StorageOption rpcSetting, Object payload) {
            super(rpcSetting, payload);
        }

    }

    /**
     * Builder for {@code Blob}.
     */
    public static class BlobInfoBuilder extends BlobMetadataBuilder {

        private final Storage backendClient;

        private final StorageObjectBuilder objectBuilder;

        @Override
        StorageObject.BlobInfoBuilder setOwner(BaseEntity ownerEntity) {
            objectBuilder.setOwner(ownerEntity);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCrc32cFromHexString(String crc32cHex) {
            objectBuilder.setCrc32cFromHexString(crc32cHex);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCrc32c(String crc32cChecksum) {
            objectBuilder.setCrc32c(crc32cChecksum);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setIsDirectory(boolean directoryFlag) {
            objectBuilder.setIsDirectory(directoryFlag);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setAcl(List<AclEntry> accessControlList) {
            objectBuilder.setAcl(accessControlList);
            return this;
        }

        @Override
        public StorageObject buildMetadata() {
            return new StorageObject(backendClient, objectBuilder);
        }

        @Override
        StorageObject.BlobInfoBuilder setEtag(String entityTag) {
            objectBuilder.setEtag(entityTag);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setBlobId(BlobIdentifier blobIdentifier) {
            objectBuilder.setBlobId(blobIdentifier);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setEventBasedHold(Boolean eventHold) {
            objectBuilder.setEventBasedHold(eventHold);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setTemporaryHold(Boolean tempHold) {
            objectBuilder.setTemporaryHold(tempHold);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setKmsKeyName(String kmsKey) {
            objectBuilder.setKmsKeyName(kmsKey);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMetadata(Map<String, String> metadataMap) {
            objectBuilder.setMetadata(metadataMap);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setDeleteTime(Long deletionTime) {
            objectBuilder.setDeleteTime(deletionTime);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setCustomerEncryption(CustomerEncryptionInfo customerEncryptionInfo) {
            objectBuilder.setCustomerEncryption(customerEncryptionInfo);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
            objectBuilder.setRetentionExpirationTime(retentionExpiryTime);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMd5FromHexString(String md5Hex) {
            objectBuilder.setMd5FromHexString(md5Hex);
            return this;
        }

        BlobInfoBuilder(StorageObject storageObject) {
            this.backendClient = storageObject.getStorage();
            this.objectBuilder = new StorageObjectBuilder(storageObject);
        }

        @Override
        StorageObject.BlobInfoBuilder setMediaLink(String mediaUrl) {
            objectBuilder.setMediaLink(mediaUrl);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentDisposition(String dispositionType) {
            objectBuilder.setContentDisposition(dispositionType);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setGeneratedId(String generatedIdentifier) {
            objectBuilder.setGeneratedId(generatedIdentifier);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setComponentCount(Integer partCount) {
            objectBuilder.setComponentCount(partCount);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentEncoding(String encodingScheme) {
            objectBuilder.setContentEncoding(encodingScheme);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setCacheControl(String cacheDirective) {
            objectBuilder.setCacheControl(cacheDirective);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setCreateTime(Long creationTime) {
            objectBuilder.setCreateTime(creationTime);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setSelfLink(String selfUri) {
            objectBuilder.setSelfLink(selfUri);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setMd5(String md5Digest) {
            objectBuilder.setMd5(md5Digest);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentLanguage(String languageTag) {
            objectBuilder.setContentLanguage(languageTag);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setStorageClass(StorageTier storageTier) {
            objectBuilder.setStorageClass(storageTier);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setUpdateTime(Long lastUpdateTime) {
            objectBuilder.setUpdateTime(lastUpdateTime);
            return this;
        }

        @Override
        public StorageObject.BlobInfoBuilder setContentType(String mimeType) {
            objectBuilder.setContentType(mimeType);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setSize(Long lengthBytes) {
            objectBuilder.setSize(lengthBytes);
            return this;
        }

        @Override
        StorageObject.BlobInfoBuilder setMetageneration(Long metaGeneration) {
            objectBuilder.setMetageneration(metaGeneration);
            return this;
        }

    }

    private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
        inputStream.defaultReadObject();
        this.backendClient = storageSettings.getService();
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
    public ReadChannel readChannel(BlobReadOption... storageSettings) {
        return backendClient.reader(getBlobId(), toSourceOptionsArray(this, storageSettings));
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
    public AclEntry getAcl(BaseEntity principal) {
        return backendClient.getAcl(getBlobId(), principal);
    }

    @Override
    public final boolean equals(Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (null == otherObject || !otherObject.getClass().equals(StorageObject.class)) {
            return false;
        }
        StorageObject comparedObject = (StorageObject) otherObject;
        return Objects.equals(toProto(), comparedObject.toProto()) && Objects.equals(storageSettings, comparedObject.storageSettings);
    }

    /**
     * Generates a signed URL for this blob. If you want to allow access for a fixed amount of time to
     * this blob, you can use this method to generate a URL that is only valid within a certain time
     * period. This is particularly useful if you don't want publicly accessible blobs, but also don't
     * want to require users to explicitly log in. Signing a URL requires a service account signer. If
     * an instance of {@link ServiceAccountSigner} was passed to {@link
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
     * @param expiryTime time until the signed URL expires, expressed in {@code unit}. The finer
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
    public URL generateSignedUrl(long expiryTime, TimeUnit timeScale, UrlSigningOption... storageSettings) {
        return backendClient.signUrl(this, expiryTime, timeScale, storageSettings);
    }

    /**
     * Downloads this blob to the given file path using specified blob read options.
     *
     * @param targetPath destination
     * @param storageSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public void downloadTo(Path targetPath, BlobReadOption... storageSettings) {
        try (OutputStream outStream = Files.newOutputStream(targetPath)) {
            downloadTo(outStream, storageSettings);
        } catch (IOException ioException) {
            throw new StorageServiceException(ioException);
        }
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
     * @return a {@link ChunkedCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ChunkedCopyWriter copyToBlob(BlobIdentifier destinationBlob, BlobReadOption... storageSettings) {
        CopyOperationRequest transferOperation = CopyOperationRequest.createBuilder().setSource(getBucket(), getName()).setSourceOptions(toSourceOptionsArray(this, storageSettings)).setTarget(destinationBlob).buildCopyOperationRequest();
        return backendClient.copy(transferOperation);
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
     * @param destinationBlob target blob's name
     * @param storageSettings source blob options
     * @return a {@link ChunkedCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ChunkedCopyWriter copyToBlob(String destinationBucket, String destinationBlob, BlobReadOption... storageSettings) {
        return copyToBlob(BlobIdentifier.create(destinationBucket, destinationBlob), storageSettings);
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
    public boolean existsInStorage(BlobReadOption... storageSettings) {
        int optionsLength = storageSettings.length;
        Storage.BlobGetOptions[] retrievalOptions = Arrays.copyOf(toGetOptionsArray(this, storageSettings), optionsLength + 1);
        retrievalOptions[optionsLength] = Storage.BlobGetOptions.selectFields();
        return null != backendClient.get(getBlobId(), retrievalOptions);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), storageSettings);
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
        return backendClient.updateAcl(getBlobId(), accessControlList);
    }

    /**
     * Fetches current blob's latest information. Returns {@code null} if the blob does not exist.
     *
     * <p>Example of getting the blob's latest information, if its generation does not match the
     * {@link StorageObject#getGeneration()} value, otherwise a {@link StorageServiceException} is thrown.
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
    public StorageObject reloadFromStorage(BlobReadOption... storageSettings) {
        return backendClient.get(getBlobId(), toGetOptionsArray(this, storageSettings));
    }

    /**
     * Returns the blob's {@code Storage} object used to issue requests.
     */
    public Storage getStorage() {
        return backendClient;
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
        return backendClient.listAcls(getBlobId());
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
     * @param storageSettings blob read options
     * @throws StorageServiceException upon failure
     */
    public byte[] getContent(BlobReadOption... storageSettings) {
        return backendClient.readAllBytes(getBlobId(), toSourceOptionsArray(this, storageSettings));
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
     * @return a {@link ChunkedCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     */
    public ChunkedCopyWriter copyToBlob(String destinationBucket, BlobReadOption... storageSettings) {
        return copyToBlob(destinationBucket, getName(), storageSettings);
    }

    static StorageObject fromProto(Storage backendClient, com.google.api.services.storage.model.StorageObject protoObject) {
        BlobInfo blobMetadata = BlobInfo.fromProto(protoObject);
        return new StorageObject(backendClient, new StorageObjectBuilder(blobMetadata));
    }

    @Override
    public StorageObject.BlobInfoBuilder asBuilder() {
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
     * @param storageSettings blob delete options
     * @return {@code true} if blob was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    public boolean deleteFromStorage(BlobReadOption... storageSettings) {
        return backendClient.delete(getBlobId(), toSourceOptionsArray(this, storageSettings));
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
    public AclEntry createAclEntry(AclEntry accessControlList) {
        return backendClient.createAcl(getBlobId(), accessControlList);
    }

    /**
     * Downloads this blob to the given output stream using specified blob read options.
     *
     * @param outStream
     * @param storageSettings
     */
    public void downloadTo(OutputStream outStream, BlobReadOption... storageSettings) {
        final CountingOutputStream countingStream = new CountingOutputStream(outStream);
        final StorageRpcClient rpcClient = this.storageSettings.getStorageRpcV1();
        final Map<StorageRpcClient.StorageOption, ?> requestParams = StorageServiceImpl.createOptionMap(getBlobId(), storageSettings);
        try {
            runWithRetries(callable(new Runnable() {

                @Override
                public void run() {
                    rpcClient.read(getBlobId().toProto(), requestParams, countingStream.getCount(), countingStream);
                }
            }), this.storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, this.storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException ioException) {
            StorageServiceException.translateAndRethrow(ioException);
        }
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
    public WriteChannel writeChannel(BlobWriteOptions... storageSettings) {
        return backendClient.writer(this, storageSettings);
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
    public boolean deleteAclForEntity(BaseEntity principal) {
        return backendClient.deleteAcl(getBlobId(), principal);
    }

    /**
     * Updates the blob properties. The {@code options} parameter contains the preconditions for
     * applying the update. To update the properties call {@link #asBuilder()}, set the properties you
     * want to change, build the new {@code Blob} instance, and then call {@link
     * #updateInStorage(Storage.BlobUploadOption...)}.
     *
     * <p>The property update details are described in {@link Storage#update(BlobInfo)}. {@link
     * Storage#update(BlobInfo, Storage.BlobUploadOption...)} describes how to specify preconditions.
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
     * @throws StorageServiceException upon failure
     * @see <a
     *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
     */
    public StorageObject updateInStorage(Storage.BlobUploadOption... storageSettings) {
        return backendClient.update(this, storageSettings);
    }

    StorageObject(Storage backendClient, StorageObjectBuilder objectBuilder) {
        super(objectBuilder);
        this.backendClient = checkNotNull(backendClient);
        this.storageSettings = backendClient.getOptions();
    }

    /**
     * Downloads this blob to the given file path.
     *
     * <p>This method is replaced with {@link #downloadTo(Path, BlobReadOption...)}, but is kept
     * here for binary compatibility with the older versions of the client library.
     *
     * @param targetPath destination
     * @throws StorageServiceException upon failure
     */
    public void downloadTo(Path targetPath) {
        downloadTo(targetPath, new BlobReadOption[0]);
    }

}
