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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.api.client.util.Data;
import com.google.api.client.util.DateTime;
import com.google.api.core.BetaApi;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.api.services.storage.model.StorageObject.Owner;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Information about an object in Google Cloud Storage. A {@code BlobInfo} object includes the
 * {@code BlobId} instance and the set of properties, such as the blob's access control
 * configuration, user provided metadata, the CRC32C checksum, etc. Instances of this class are used
 * to create a new object in Google Cloud Storage or update the properties of an existing object. To
 * deal with existing Storage objects the API includes the {@link StorageObject} class which extends {@code
 * BlobInfo} and declares methods to perform operations on the object. Neither {@code BlobInfo} nor
 * {@code Blob} instances keep the object content, just the object properties.
 *
 * <p>Example of usage {@code BlobInfo} to create an object in Google Cloud Storage:
 *
 * <pre>{@code
 * BlobId blobId = BlobId.of(bucketName, blobName);
 * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
 * Blob blob = storage.create(blobInfo, "Hello, world".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * @see <a href="https://cloud.google.com/storage/docs/concepts-techniques#concepts">Concepts and
 *     Terminology</a>
 */
public class BlobMetadata implements Serializable {

    static final Function<BlobMetadata, com.google.api.services.storage.model.StorageObject> BLOB_METADATA_TO_PB_FN = new Function<BlobMetadata, com.google.api.services.storage.model.StorageObject>() {

        @Override
        public com.google.api.services.storage.model.StorageObject apply(BlobMetadata blobInfo) {
            return blobInfo.toProto();
        }
    };

    private static final long serialVersionUID = -5625857076205028976L;

    private final BlobId blobIdentifier;

    private final String generatedIdentifier;

    private final String selfUri;

    private final String cacheControlHeader;

    private final List<AccessControlEntry> accessControlList;

    private final AccessControlEntry.AbstractEntity ownerEntity;

    private final Long contentSize;

    private final String entityTag;

    private final String md5Hash;

    private final String crc32cValue;

    private final Long customTimestamp;

    private final String mediaUrl;

    private final Map<String, String> metaMap;

    private final Long metaGeneration;

    private final Long deletionTime;

    private final Long lastUpdateTime;

    private final Long creationTime;

    private final String mediaType;

    private final String contentEncodingType;

    private final String disposition;

    private final String language;

    private final StorageTier storageTier;

    private final Long storageClassUpdateTime;

    private final Integer componentsCount;

    private final boolean directoryFlag;

    private final CustomerEncryptionMetadata customerEncryptionMetadata;

    private final String kmsKeyPath;

    private final Boolean eventHold;

    private final Boolean temporaryHoldFlag;

    private final Long retentionExpiryTime;

    /**
     * This class is meant for internal use only. Users are discouraged from using this class.
     */
    public static final class EmptyImmutableMap<K, V> extends AbstractMap<K, V> {

        @Override
        public Set<Entry<K, V>> entrySet() {
            return ImmutableSet.of();
        }
    }

    /**
     * Objects of this class hold information on the customer-supplied encryption key, if the blob is
     * encrypted using such a key.
     */
    public static class CustomerEncryptionMetadata implements Serializable {

        private static final long serialVersionUID = -2133042982786959351L;

        private final String encryptionAlgorithmName;

        private final String keySha256Hash;

        CustomerEncryptionMetadata(String encryptionAlgorithmName, String keySha256Hash) {
            this.encryptionAlgorithmName = encryptionAlgorithmName;
            this.keySha256Hash = keySha256Hash;
        }

        /**
         * Returns the algorithm used to encrypt the blob.
         */
        public String getEncryptionAlgorithm() {
            return encryptionAlgorithmName;
        }

        /**
         * Returns the SHA256 hash of the encryption key.
         */
        public String getKeySha256() {
            return keySha256Hash;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("encryptionAlgorithm", getEncryptionAlgorithm()).add("keySha256", getKeySha256()).toString();
        }

        @Override
        public final int hashCode() {
            return Objects.hash(encryptionAlgorithmName, keySha256Hash);
        }

        @Override
        public final boolean equals(Object other) {
            return this == other || null != other && other.getClass().equals(CustomerEncryptionMetadata.class) && Objects.equals(toProto(), ((CustomerEncryptionMetadata) other).toProto());
        }

        com.google.api.services.storage.model.StorageObject.CustomerEncryption toProto() {
            return new com.google.api.services.storage.model.StorageObject.CustomerEncryption().setEncryptionAlgorithm(encryptionAlgorithmName).setKeySha256(keySha256Hash);
        }

        static CustomerEncryptionMetadata fromProto(com.google.api.services.storage.model.StorageObject.CustomerEncryption customerEncryptionProto) {
            return new CustomerEncryptionMetadata(customerEncryptionProto.getEncryptionAlgorithm(), customerEncryptionProto.getKeySha256());
        }
    }

    /**
     * Builder for {@code BlobInfo}.
     */
    public abstract static class StorageObjectBuilder {

        /**
         * Sets the blob identity.
         */
        public abstract StorageObjectBuilder setBlobId(BlobId blobId);

        abstract StorageObjectBuilder setGeneratedId(String generatedId);

        /**
         * Sets the blob's data content type.
         *
         * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
         */
        public abstract StorageObjectBuilder setContentType(String contentType);

        /**
         * Sets the blob's data content disposition.
         *
         * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
         */
        public abstract StorageObjectBuilder setContentDisposition(String contentDisposition);

        /**
         * Sets the blob's data content language.
         *
         * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
         */
        public abstract StorageObjectBuilder setContentLanguage(String contentLanguage);

        /**
         * Sets the blob's data content encoding.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
         */
        public abstract StorageObjectBuilder setContentEncoding(String contentEncoding);

        abstract StorageObjectBuilder setComponentCount(Integer componentCount);

        /**
         * Sets the blob's data cache control.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Cache-Control</a>
         */
        public abstract StorageObjectBuilder setCacheControl(String cacheControl);

        /**
         * Sets the blob's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract StorageObjectBuilder setAcl(List<AccessControlEntry> acl);

        abstract StorageObjectBuilder setOwner(AccessControlEntry.AbstractEntity owner);

        abstract StorageObjectBuilder setSize(Long size);

        abstract StorageObjectBuilder setEtag(String etag);

        abstract StorageObjectBuilder setSelfLink(String selfLink);

        /**
         * Sets the MD5 hash of blob's data. MD5 value must be encoded in base64.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract StorageObjectBuilder setMd5(String md5);

        /**
         * Sets the MD5 hash of blob's data from hex string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         * @throws IllegalArgumentException when given an invalid hexadecimal value.
         */
        public abstract StorageObjectBuilder setMd5FromHexString(String md5HexString);

        /**
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> encoded in
         * base64 in big-endian order.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract StorageObjectBuilder setCrc32c(String crc32c);

        /**
         * Sets the custom time for an object. Once set it can't be unset and only changed to a custom
         * datetime in the future. To unset the custom time, you must either perform a rewrite operation
         * or upload the data again.
         *
         * <p>Example of setting the custom time.
         *
         * <pre>{@code
         * String bucketName = "my-unique-bucket";
         * String blobName = "my-blob-name";
         * long customTime = 1598423868301L;
         * BlobInfo blob = BlobInfo.newBuilder(bucketName, blobName).setCustomTime(customTime).build();
         * }</pre>
         */
        public StorageObjectBuilder setCustomTime(Long customTime) {
            throw new UnsupportedOperationException("Override setCustomTime with your own implementation," + " or use com.google.cloud.storage.Blob.");
        }

        /**
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> from hex
         * string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         * @throws IllegalArgumentException when given an invalid hexadecimal value.
         */
        public abstract StorageObjectBuilder setCrc32cFromHexString(String crc32cHexString);

        abstract StorageObjectBuilder setMediaLink(String mediaLink);

        /**
         * Sets the blob's storage class.
         */
        public abstract StorageObjectBuilder setStorageClass(StorageTier storageClass);

        /**
         * Sets the modification time of an object's storage class. Once set it can't be unset directly,
         * the only way is to rewrite the object with the desired storage class.
         */
        public StorageObjectBuilder setTimeStorageClassUpdated(Long timeStorageClassUpdated) {
            throw new UnsupportedOperationException("Override setTimeStorageClassUpdated with your own implementation," + " or use com.google.cloud.storage.Blob.");
        }

        /**
         * Sets the blob's user provided metadata.
         */
        public abstract StorageObjectBuilder setMetadata(Map<String, String> metadata);

        abstract StorageObjectBuilder setMetageneration(Long metageneration);

        abstract StorageObjectBuilder setDeleteTime(Long deleteTime);

        abstract StorageObjectBuilder setUpdateTime(Long updateTime);

        abstract StorageObjectBuilder setCreateTime(Long createTime);

        abstract StorageObjectBuilder setIsDirectory(boolean isDirectory);

        abstract StorageObjectBuilder setCustomerEncryption(CustomerEncryptionMetadata customerEncryption);

        /**
         * Sets a customer-managed key for server-side encryption of the blob. Note that when a KMS key
         * is used to encrypt Cloud Storage object, object resource metadata will store the version of
         * the KMS cryptographic. If a {@code Blob} with KMS Key metadata is used to upload a new
         * version of the object then the existing kmsKeyName version value can't be used in the upload
         * request and the client instead ignores it.
         *
         * <p>Example of setting the KMS key name
         *
         * <pre>{@code
         * String bucketName = "my-unique-bucket";
         * String blobName = "my-blob-name";
         * String kmsKeyName = "projects/project-id/locations/us/keyRings/lab1/cryptoKeys/test-key"
         * BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, blobName).build();
         * Blob blob = storage.create(blobInfo, Storage.BlobTargetOption.kmsKeyName(kmsKeyName));
         * }</pre>
         */
        abstract StorageObjectBuilder setKmsKeyName(String kmsKeyName);

        /**
         * Sets the blob's event-based hold.
         */
        @BetaApi
        public abstract StorageObjectBuilder setEventBasedHold(Boolean eventBasedHold);

        /**
         * Sets the blob's temporary hold.
         */
        @BetaApi
        public abstract StorageObjectBuilder setTemporaryHold(Boolean temporaryHold);

        @BetaApi
        abstract StorageObjectBuilder setRetentionExpirationTime(Long retentionExpirationTime);

        /**
         * Creates a {@code BlobInfo} object.
         */
        public abstract BlobMetadata buildObject();
    }

    static final class BlobInfoBuilderImpl extends StorageObjectBuilder {

        private final String hexValues = "0123456789abcdef";

        private BlobId blobIdentifier;

        private String generatedIdentifier;

        private String mediaType;

        private String contentEncodingType;

        private String disposition;

        private String language;

        private Integer componentsCount;

        private String cacheControlHeader;

        private List<AccessControlEntry> accessControlList;

        private AccessControlEntry.AbstractEntity ownerEntity;

        private Long contentSize;

        private String entityTag;

        private String selfUri;

        private String md5Hash;

        private String crc32cValue;

        private Long customTimestamp;

        private String mediaUrl;

        private Map<String, String> metaMap;

        private Long metaGeneration;

        private Long deletionTime;

        private Long lastUpdateTime;

        private Long creationTime;

        private Boolean directoryFlag;

        private CustomerEncryptionMetadata customerEncryptionMetadata;

        private StorageTier storageTier;

        private Long storageClassUpdateTime;

        private String kmsKeyPath;

        private Boolean eventHold;

        private Boolean temporaryHoldFlag;

        private Long retentionExpiryTime;

        BlobInfoBuilderImpl(BlobId blobIdentifier) {
            this.blobIdentifier = blobIdentifier;
        }

        BlobInfoBuilderImpl(BlobMetadata blobMetadata) {
            blobIdentifier = blobMetadata.blobIdentifier;
            generatedIdentifier = blobMetadata.generatedIdentifier;
            cacheControlHeader = blobMetadata.cacheControlHeader;
            contentEncodingType = blobMetadata.contentEncodingType;
            mediaType = blobMetadata.mediaType;
            disposition = blobMetadata.disposition;
            language = blobMetadata.language;
            componentsCount = blobMetadata.componentsCount;
            customerEncryptionMetadata = blobMetadata.customerEncryptionMetadata;
            accessControlList = blobMetadata.accessControlList;
            ownerEntity = blobMetadata.ownerEntity;
            contentSize = blobMetadata.contentSize;
            entityTag = blobMetadata.entityTag;
            selfUri = blobMetadata.selfUri;
            md5Hash = blobMetadata.md5Hash;
            crc32cValue = blobMetadata.crc32cValue;
            customTimestamp = blobMetadata.customTimestamp;
            mediaUrl = blobMetadata.mediaUrl;
            metaMap = blobMetadata.metaMap;
            metaGeneration = blobMetadata.metaGeneration;
            deletionTime = blobMetadata.deletionTime;
            lastUpdateTime = blobMetadata.lastUpdateTime;
            creationTime = blobMetadata.creationTime;
            directoryFlag = blobMetadata.directoryFlag;
            storageTier = blobMetadata.storageTier;
            storageClassUpdateTime = blobMetadata.storageClassUpdateTime;
            kmsKeyPath = blobMetadata.kmsKeyPath;
            eventHold = blobMetadata.eventHold;
            temporaryHoldFlag = blobMetadata.temporaryHoldFlag;
            retentionExpiryTime = blobMetadata.retentionExpiryTime;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setBlobId(BlobId blobIdentifier) {
            this.blobIdentifier = checkNotNull(blobIdentifier);
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentType(String mediaType) {
            this.mediaType = firstNonNull(mediaType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentDisposition(String disposition) {
            this.disposition = firstNonNull(disposition, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentLanguage(String language) {
            this.language = firstNonNull(language, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentEncoding(String contentEncodingType) {
            this.contentEncodingType = firstNonNull(contentEncodingType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setComponentCount(Integer componentsCount) {
            this.componentsCount = componentsCount;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCacheControl(String cacheControlHeader) {
            this.cacheControlHeader = firstNonNull(cacheControlHeader, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setAcl(List<AccessControlEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setOwner(AccessControlEntry.AbstractEntity ownerEntity) {
            this.ownerEntity = ownerEntity;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setSize(Long contentSize) {
            this.contentSize = contentSize;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setSelfLink(String selfUri) {
            this.selfUri = selfUri;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setMd5(String md5Hash) {
            this.md5Hash = firstNonNull(md5Hash, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setMd5FromHexString(String hexDigest) {
            if (null == hexDigest) {
                return this;
            }
            if (0 != hexDigest.length() % 2) {
                throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
            }
            String hexDigestLower = hexDigest.toLowerCase();
            ByteBuffer md5Buffer = ByteBuffer.allocate(hexDigestLower.length() / 2);
            int charPos = 0;
            while (hexDigestLower.length() > charPos) {
                int hiBits = this.hexValues.indexOf(hexDigestLower.charAt(charPos));
                int loBits = this.hexValues.indexOf(hexDigestLower.charAt(charPos + 1));
                if (-1 == hiBits || -1 == loBits) {
                    throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
                }
                md5Buffer.put((byte) (hiBits << 4 | loBits));
                charPos += 2;
            }
            this.md5Hash = BaseEncoding.base64().encode(md5Buffer.array());
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCrc32c(String crc32cValue) {
            this.crc32cValue = firstNonNull(crc32cValue, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCustomTime(Long customTimestamp) {
            this.customTimestamp = customTimestamp;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCrc32cFromHexString(String crcHex) {
            if (null == crcHex) {
                return this;
            }
            if (0 != crcHex.length() % 2) {
                throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
            }
            String crcHexLower = crcHex.toLowerCase();
            ByteBuffer crcBuffer = ByteBuffer.allocate(crcHexLower.length() / 2);
            int charPos = 0;
            while (crcHexLower.length() > charPos) {
                int hiBits = this.hexValues.indexOf(crcHexLower.charAt(charPos));
                int loBits = this.hexValues.indexOf(crcHexLower.charAt(charPos + 1));
                if (-1 == hiBits || -1 == loBits) {
                    throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
                }
                crcBuffer.put((byte) (hiBits << 4 | loBits));
                charPos += 2;
            }
            this.crc32cValue = BaseEncoding.base64().encode(crcBuffer.array());
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setMediaLink(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setMetadata(Map<String, String> metaMap) {
            if (null == metaMap) {
                this.metaMap = (Map<String, String>) Data.nullOf(EmptyImmutableMap.class);
            } else {
                this.metaMap = new HashMap<>(metaMap);
            }
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setStorageClass(StorageTier storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setTimeStorageClassUpdated(Long storageClassUpdateTime) {
            this.storageClassUpdateTime = storageClassUpdateTime;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setMetageneration(Long metaGeneration) {
            this.metaGeneration = metaGeneration;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setDeleteTime(Long deletionTime) {
            this.deletionTime = deletionTime;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setUpdateTime(Long lastUpdateTime) {
            this.lastUpdateTime = lastUpdateTime;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setCreateTime(Long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setIsDirectory(boolean directoryFlag) {
            this.directoryFlag = directoryFlag;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setCustomerEncryption(CustomerEncryptionMetadata customerEncryptionMetadata) {
            this.customerEncryptionMetadata = customerEncryptionMetadata;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setKmsKeyName(String kmsKeyPath) {
            this.kmsKeyPath = kmsKeyPath;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setEventBasedHold(Boolean eventHold) {
            this.eventHold = eventHold;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setTemporaryHold(Boolean temporaryHoldFlag) {
            this.temporaryHoldFlag = temporaryHoldFlag;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
            this.retentionExpiryTime = retentionExpiryTime;
            return this;
        }

        @Override
        public BlobMetadata buildObject() {
            checkNotNull(blobIdentifier);
            return new BlobMetadata(this);
        }
    }

    BlobMetadata(BlobInfoBuilderImpl blobBuilder) {
        blobIdentifier = blobBuilder.blobIdentifier;
        generatedIdentifier = blobBuilder.generatedIdentifier;
        cacheControlHeader = blobBuilder.cacheControlHeader;
        contentEncodingType = blobBuilder.contentEncodingType;
        mediaType = blobBuilder.mediaType;
        disposition = blobBuilder.disposition;
        language = blobBuilder.language;
        componentsCount = blobBuilder.componentsCount;
        customerEncryptionMetadata = blobBuilder.customerEncryptionMetadata;
        accessControlList = blobBuilder.accessControlList;
        ownerEntity = blobBuilder.ownerEntity;
        contentSize = blobBuilder.contentSize;
        entityTag = blobBuilder.entityTag;
        selfUri = blobBuilder.selfUri;
        md5Hash = blobBuilder.md5Hash;
        crc32cValue = blobBuilder.crc32cValue;
        customTimestamp = blobBuilder.customTimestamp;
        mediaUrl = blobBuilder.mediaUrl;
        metaMap = blobBuilder.metaMap;
        metaGeneration = blobBuilder.metaGeneration;
        deletionTime = blobBuilder.deletionTime;
        lastUpdateTime = blobBuilder.lastUpdateTime;
        creationTime = blobBuilder.creationTime;
        directoryFlag = firstNonNull(blobBuilder.directoryFlag, Boolean.FALSE);
        storageTier = blobBuilder.storageTier;
        storageClassUpdateTime = blobBuilder.storageClassUpdateTime;
        kmsKeyPath = blobBuilder.kmsKeyPath;
        eventHold = blobBuilder.eventHold;
        temporaryHoldFlag = blobBuilder.temporaryHoldFlag;
        retentionExpiryTime = blobBuilder.retentionExpiryTime;
    }

    /**
     * Returns the blob's identity.
     */
    public BlobId getBlobId() {
        return blobIdentifier;
    }

    /**
     * Returns the name of the containing bucket.
     */
    public String getBucket() {
        return getBlobId().getBucket();
    }

    /**
     * Returns the service-generated for the blob.
     */
    public String getGeneratedId() {
        return generatedIdentifier;
    }

    /**
     * Returns the blob's name.
     */
    public String getName() {
        return getBlobId().getName();
    }

    /**
     * Returns the blob's data cache control.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Cache-Control</a>
     */
    public String getCacheControl() {
        return Data.isNull(cacheControlHeader) ? null : cacheControlHeader;
    }

    /**
     * Returns the blob's access control configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AccessControlEntry> getAcl() {
        return accessControlList;
    }

    /**
     * Returns the blob's owner. This will always be the uploader of the blob.
     */
    public AccessControlEntry.AbstractEntity getOwner() {
        return ownerEntity;
    }

    /**
     * Returns the content length of the data in bytes.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.13">Content-Length</a>
     */
    public Long getSize() {
        return contentSize;
    }

    /**
     * Returns the blob's data content type.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
     */
    public String getContentType() {
        return Data.isNull(mediaType) ? null : mediaType;
    }

    /**
     * Returns the blob's data content encoding.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
     */
    public String getContentEncoding() {
        return Data.isNull(contentEncodingType) ? null : contentEncodingType;
    }

    /**
     * Returns the blob's data content disposition.
     *
     * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
     */
    public String getContentDisposition() {
        return Data.isNull(disposition) ? null : disposition;
    }

    /**
     * Returns the blob's data content language.
     *
     * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
     */
    public String getContentLanguage() {
        return Data.isNull(language) ? null : language;
    }

    /**
     * Returns the number of components that make up this blob. Components are accumulated through the
     * {@link Storage#compose(Storage.ComposeBlobsRequest)} operation and are limited to a count of 1024,
     * counting 1 for each non-composite component blob and componentCount for each composite
     * component blob. This value is set only for composite blobs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/composite-objects#_Count">Component Count
     *     Property</a>
     */
    public Integer getComponentCount() {
        return componentsCount;
    }

    /**
     * Returns HTTP 1.1 Entity tag for the blob.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return entityTag;
    }

    /**
     * Returns the URI of this blob as a string.
     */
    public String getSelfLink() {
        return selfUri;
    }

    /**
     * Returns the MD5 hash of blob's data encoded in base64.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5() {
        return Data.isNull(md5Hash) ? null : md5Hash;
    }

    /**
     * Returns the MD5 hash of blob's data decoded to string.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5ToHexString() {
        if (null == md5Hash) {
            return null;
        }
        byte[] decodedDigest = BaseEncoding.base64().decode(md5Hash);
        StringBuilder sb = new StringBuilder();
        for (byte inputByte : decodedDigest) {
            sb.append(String.format("%02x", inputByte & 0xff));
        }
        return sb.toString();
    }

    /**
     * Returns the CRC32C checksum of blob's data as described in <a
     * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> encoded in
     * base64 in big-endian order.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getCrc32c() {
        return Data.isNull(crc32cValue) ? null : crc32cValue;
    }

    /**
     * Returns the CRC32C checksum of blob's data as described in <a
     * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> decoded to
     * string.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getCrc32cToHexString() {
        if (null == crc32cValue) {
            return null;
        }
        byte[] decodedCrc = BaseEncoding.base64().decode(crc32cValue);
        StringBuilder sb = new StringBuilder();
        for (byte inputByte : decodedCrc) {
            sb.append(String.format("%02x", inputByte & 0xff));
        }
        return sb.toString();
    }

    /**
     * Returns the blob's media download link.
     */
    public String getMediaLink() {
        return mediaUrl;
    }

    /**
     * Returns blob's user provided metadata.
     */
    public Map<String, String> getMetadata() {
        return null == metaMap || Data.isNull(metaMap) ? null : Collections.unmodifiableMap(metaMap);
    }

    /**
     * Returns blob's data generation. Used for blob versioning.
     */
    public Long getGeneration() {
        return getBlobId().getGeneration();
    }

    /**
     * Returns blob's metageneration. Used for preconditions and for detecting changes in metadata. A
     * metageneration number is only meaningful in the context of a particular generation of a
     * particular blob.
     */
    public Long getMetageneration() {
        return metaGeneration;
    }

    /**
     * Returns the deletion time of the blob expressed as the number of milliseconds since the Unix
     * epoch.
     */
    public Long getDeleteTime() {
        return deletionTime;
    }

    /**
     * Returns the last modification time of the blob's metadata expressed as the number of
     * milliseconds since the Unix epoch.
     */
    public Long getUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * Returns the creation time of the blob expressed as the number of milliseconds since the Unix
     * epoch.
     */
    public Long getCreateTime() {
        return creationTime;
    }

    /**
     * Returns the custom time specified by the user for an object.
     */
    public Long getCustomTime() {
        return customTimestamp;
    }

    /**
     * Returns {@code true} if the current blob represents a directory. This can only happen if the
     * blob is returned by {@link Storage#list(String, Storage.BlobListOptions...)} when the {@link
     * Storage.BlobListOptions#useCurrentDirectory()} option is used. When this is the case only {@link
     * #getBlobId()} and {@link #getSize()} are set for the current blob: {@link BlobId#getName()}
     * ends with the '/' character, {@link BlobId#getGeneration()} returns {@code null} and {@link
     * #getSize()} is {@code 0}.
     */
    public boolean isDirectory() {
        return directoryFlag;
    }

    /**
     * Returns information on the customer-supplied encryption key, if the blob is encrypted using
     * such a key.
     */
    public CustomerEncryptionMetadata getCustomerEncryption() {
        return customerEncryptionMetadata;
    }

    /**
     * Returns the storage class of the blob.
     */
    public StorageTier getStorageClass() {
        return storageTier;
    }

    /**
     * Returns the time that the object's storage class was last changed or the time of the object
     * creation.
     */
    public Long getTimeStorageClassUpdated() {
        return storageClassUpdateTime;
    }

    /**
     * Returns the Cloud KMS key used to encrypt the blob, if any.
     */
    public String getKmsKeyName() {
        return kmsKeyPath;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * Storage#get(BlobId, Storage.BlobGetOptions...)} and event-based hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * Storage#get(BlobId, Storage.BlobGetOptions...)}, but event-based hold for the blob is not
     * enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BlobMetadataField#EVENT_BASED_HOLD} is not selected in a {@link
     * Storage#get(BlobId, Storage.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * StorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * Storage#update(BlobMetadata, Storage.BlobUploadOption...)} in which case the value of event-based
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getEventBasedHold() {
        return Data.<Boolean>isNull(eventHold) ? null : eventHold;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * Storage#get(BlobId, Storage.BlobGetOptions...)} and temporary hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * Storage#get(BlobId, Storage.BlobGetOptions...)}, but temporary hold for the blob is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BlobMetadataField#TEMPORARY_HOLD} is not selected in a {@link
     * Storage#get(BlobId, Storage.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * StorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * Storage#update(BlobMetadata, Storage.BlobUploadOption...)} in which case the value of temporary
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getTemporaryHold() {
        return Data.<Boolean>isNull(temporaryHoldFlag) ? null : temporaryHoldFlag;
    }

    /**
     * Returns the retention expiration time of the blob as {@code Long}, if a retention period is
     * defined. If retention period is not defined this value returns {@code null}
     */
    @BetaApi
    public Long getRetentionExpirationTime() {
        return Data.<Long>isNull(retentionExpiryTime) ? null : retentionExpiryTime;
    }

    /**
     * Returns a builder for the current blob.
     */
    public StorageObjectBuilder toInfoBuilder() {
        return new BlobInfoBuilderImpl(this);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).add("size", getSize()).add("content-type", getContentType()).add("metadata", getMetadata()).toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(blobIdentifier);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || null != other && other.getClass().equals(BlobMetadata.class) && Objects.equals(toProto(), ((BlobMetadata) other).toProto());
    }

    com.google.api.services.storage.model.StorageObject toProto() {
        com.google.api.services.storage.model.StorageObject storageProto = blobIdentifier.toProto();
        if (null != accessControlList) {
            storageProto.setAcl(Lists.transform(accessControlList, new Function<AccessControlEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != deletionTime) {
            storageProto.setTimeDeleted(new DateTime(deletionTime));
        }
        if (null != lastUpdateTime) {
            storageProto.setUpdated(new DateTime(lastUpdateTime));
        }
        if (null != creationTime) {
            storageProto.setTimeCreated(new DateTime(creationTime));
        }
        if (null != customTimestamp) {
            storageProto.setCustomTime(new DateTime(customTimestamp));
        }
        if (null != contentSize) {
            storageProto.setSize(BigInteger.valueOf(contentSize));
        }
        if (null != ownerEntity) {
            storageProto.setOwner(new Owner().setEntity(ownerEntity.toProto()));
        }
        if (null != storageTier) {
            storageProto.setStorageClass(storageTier.toString());
        }
        if (null != storageClassUpdateTime) {
            storageProto.setTimeStorageClassUpdated(new DateTime(storageClassUpdateTime));
        }
        Map<String, String> protoMetadata = metaMap;
        if (null != metaMap && !Data.isNull(metaMap)) {
            protoMetadata = Maps.newHashMapWithExpectedSize(metaMap.size());
            for (Map.Entry<String, String> mapEntry : metaMap.entrySet()) {
                protoMetadata.put(mapEntry.getKey(), firstNonNull(mapEntry.getValue(), Data.<String>nullOf(String.class)));
            }
        }
        if (null != customerEncryptionMetadata) {
            storageProto.setCustomerEncryption(customerEncryptionMetadata.toProto());
        }
        if (null != retentionExpiryTime) {
            storageProto.setRetentionExpirationTime(new DateTime(retentionExpiryTime));
        }
        storageProto.setKmsKeyName(kmsKeyPath);
        storageProto.setEventBasedHold(eventHold);
        storageProto.setTemporaryHold(temporaryHoldFlag);
        storageProto.setMetadata(protoMetadata);
        storageProto.setCacheControl(cacheControlHeader);
        storageProto.setContentEncoding(contentEncodingType);
        storageProto.setCrc32c(crc32cValue);
        storageProto.setContentType(mediaType);
        storageProto.setMd5Hash(md5Hash);
        storageProto.setMediaLink(mediaUrl);
        storageProto.setMetageneration(metaGeneration);
        storageProto.setContentDisposition(disposition);
        storageProto.setComponentCount(componentsCount);
        storageProto.setContentLanguage(language);
        storageProto.setEtag(entityTag);
        storageProto.setId(generatedIdentifier);
        storageProto.setSelfLink(selfUri);
        return storageProto;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(BucketMetadata bucketMeta, String objectLabel) {
        return newBuilder(bucketMeta.getName(), objectLabel);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(String storageContainer, String objectLabel) {
        return newBuilder(BlobId.from(storageContainer, objectLabel));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(BucketMetadata bucketMeta, String objectLabel, Long version) {
        return newBuilder(bucketMeta.getName(), objectLabel, version);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(String storageContainer, String objectLabel, Long version) {
        return newBuilder(BlobId.from(storageContainer, objectLabel, version));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided value.
     */
    public static StorageObjectBuilder newBuilder(BlobId blobIdentifier) {
        return new BlobInfoBuilderImpl(blobIdentifier);
    }

    static BlobMetadata fromProto(com.google.api.services.storage.model.StorageObject storageProto) {
        StorageObjectBuilder blobBuilder = newBuilder(BlobId.fromProto(storageProto));
        if (null != storageProto.getCacheControl()) {
            blobBuilder.setCacheControl(storageProto.getCacheControl());
        }
        if (null != storageProto.getContentEncoding()) {
            blobBuilder.setContentEncoding(storageProto.getContentEncoding());
        }
        if (null != storageProto.getCrc32c()) {
            blobBuilder.setCrc32c(storageProto.getCrc32c());
        }
        if (null != storageProto.getContentType()) {
            blobBuilder.setContentType(storageProto.getContentType());
        }
        if (null != storageProto.getMd5Hash()) {
            blobBuilder.setMd5(storageProto.getMd5Hash());
        }
        if (null != storageProto.getMediaLink()) {
            blobBuilder.setMediaLink(storageProto.getMediaLink());
        }
        if (null != storageProto.getMetageneration()) {
            blobBuilder.setMetageneration(storageProto.getMetageneration());
        }
        if (null != storageProto.getContentDisposition()) {
            blobBuilder.setContentDisposition(storageProto.getContentDisposition());
        }
        if (null != storageProto.getComponentCount()) {
            blobBuilder.setComponentCount(storageProto.getComponentCount());
        }
        if (null != storageProto.getContentLanguage()) {
            blobBuilder.setContentLanguage(storageProto.getContentLanguage());
        }
        if (null != storageProto.getEtag()) {
            blobBuilder.setEtag(storageProto.getEtag());
        }
        if (null != storageProto.getId()) {
            blobBuilder.setGeneratedId(storageProto.getId());
        }
        if (null != storageProto.getSelfLink()) {
            blobBuilder.setSelfLink(storageProto.getSelfLink());
        }
        if (null != storageProto.getMetadata()) {
            blobBuilder.setMetadata(storageProto.getMetadata());
        }
        if (null != storageProto.getTimeDeleted()) {
            blobBuilder.setDeleteTime(storageProto.getTimeDeleted().getValue());
        }
        if (null != storageProto.getUpdated()) {
            blobBuilder.setUpdateTime(storageProto.getUpdated().getValue());
        }
        if (null != storageProto.getTimeCreated()) {
            blobBuilder.setCreateTime(storageProto.getTimeCreated().getValue());
        }
        if (null != storageProto.getCustomTime()) {
            blobBuilder.setCustomTime(storageProto.getCustomTime().getValue());
        }
        if (null != storageProto.getSize()) {
            blobBuilder.setSize(storageProto.getSize().longValue());
        }
        if (null != storageProto.getOwner()) {
            blobBuilder.setOwner(AccessControlEntry.AbstractEntity.fromProto(storageProto.getOwner().getEntity()));
        }
        if (null != storageProto.getAcl()) {
            blobBuilder.setAcl(Lists.transform(storageProto.getAcl(), new Function<ObjectAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                    return AccessControlEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (storageProto.containsKey("isDirectory")) {
            blobBuilder.setIsDirectory(Boolean.TRUE);
        }
        if (null != storageProto.getCustomerEncryption()) {
            blobBuilder.setCustomerEncryption(CustomerEncryptionMetadata.fromProto(storageProto.getCustomerEncryption()));
        }
        if (null != storageProto.getStorageClass()) {
            blobBuilder.setStorageClass(StorageTier.of(storageProto.getStorageClass()));
        }
        if (null != storageProto.getTimeStorageClassUpdated()) {
            blobBuilder.setTimeStorageClassUpdated(storageProto.getTimeStorageClassUpdated().getValue());
        }
        if (null != storageProto.getKmsKeyName()) {
            blobBuilder.setKmsKeyName(storageProto.getKmsKeyName());
        }
        if (null != storageProto.getEventBasedHold()) {
            blobBuilder.setEventBasedHold(storageProto.getEventBasedHold());
        }
        if (null != storageProto.getTemporaryHold()) {
            blobBuilder.setTemporaryHold(storageProto.getTemporaryHold());
        }
        if (null != storageProto.getRetentionExpirationTime()) {
            blobBuilder.setRetentionExpirationTime(storageProto.getRetentionExpirationTime().getValue());
        }
        return blobBuilder.buildObject();
    }
}
