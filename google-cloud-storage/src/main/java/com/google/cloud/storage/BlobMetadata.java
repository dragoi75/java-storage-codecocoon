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

    static final Function<BlobMetadata, com.google.api.services.storage.model.StorageObject> INFO_TO_PROTO_FUNCTION = new Function<BlobMetadata, com.google.api.services.storage.model.StorageObject>() {

        @Override
        public com.google.api.services.storage.model.StorageObject apply(BlobMetadata blobInfo) {
            return blobInfo.toProto();
        }
    };

    private static final long serialVersionUID = -5625857076205028976L;

    private final BlobIdentifier blobIdentifier;

    private final String generatedIdentifier;

    private final String selfUri;

    private final String cacheDirective;

    private final List<AccessControlEntry> accessControlList;

    private final AccessControlEntry.ProtoEntity principalEntity;

    private final Long dataSize;

    private final String entityTag;

    private final String contentDigest;

    private final String crcChecksum;

    private final Long customTimestamp;

    private final String mediaUrl;

    private final Map<String, String> metaMap;

    private final Long metaGenerationNumber;

    private final Long deletionTime;

    private final Long lastUpdated;

    private final Long creationTime;

    private final String mimeType;

    private final String encoding;

    private final String disposition;

    private final String language;

    private final StorageClassType storageTier;

    private final Long storageClassUpdateTime;

    private final Integer componentsCount;

    private final boolean directoryFlag;

    private final CustomerEncryptionInfo clientEncryptionInfo;

    private final String kmsKeyPath;

    private final Boolean eventHold;

    private final Boolean tempHold;

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
    public static class CustomerEncryptionInfo implements Serializable {

        private static final long serialVersionUID = -2133042982786959351L;

        private final String cipherAlgorithm;

        private final String keyFingerprint;

        CustomerEncryptionInfo(String cipherAlgorithm, String keyFingerprint) {
            this.cipherAlgorithm = cipherAlgorithm;
            this.keyFingerprint = keyFingerprint;
        }

        /**
         * Returns the algorithm used to encrypt the blob.
         */
        public String getEncryptionAlgorithm() {
            return cipherAlgorithm;
        }

        /**
         * Returns the SHA256 hash of the encryption key.
         */
        public String getKeySha256() {
            return keyFingerprint;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("encryptionAlgorithm", getEncryptionAlgorithm()).add("keySha256", getKeySha256()).toString();
        }

        @Override
        public final int hashCode() {
            return Objects.hash(cipherAlgorithm, keyFingerprint);
        }

        @Override
        public final boolean equals(Object otherObject) {
            return this == otherObject || null != otherObject && otherObject.getClass().equals(CustomerEncryptionInfo.class) && Objects.equals(toProto(), ((CustomerEncryptionInfo) otherObject).toProto());
        }

        com.google.api.services.storage.model.StorageObject.CustomerEncryption toProto() {
            return new com.google.api.services.storage.model.StorageObject.CustomerEncryption().setEncryptionAlgorithm(cipherAlgorithm).setKeySha256(keyFingerprint);
        }

        static CustomerEncryptionInfo fromProto(com.google.api.services.storage.model.StorageObject.CustomerEncryption customerEncryptionProto) {
            return new CustomerEncryptionInfo(customerEncryptionProto.getEncryptionAlgorithm(), customerEncryptionProto.getKeySha256());
        }
    }

    /**
     * Builder for {@code BlobInfo}.
     */
    public abstract static class BlobMetadataBuilder {

        /**
         * Sets the blob identity.
         */
        public abstract BlobMetadataBuilder setBlobId(BlobIdentifier blobId);

        abstract BlobMetadataBuilder setGeneratedId(String generatedId);

        /**
         * Sets the blob's data content type.
         *
         * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
         */
        public abstract BlobMetadataBuilder setContentType(String contentType);

        /**
         * Sets the blob's data content disposition.
         *
         * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
         */
        public abstract BlobMetadataBuilder setContentDisposition(String contentDisposition);

        /**
         * Sets the blob's data content language.
         *
         * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
         */
        public abstract BlobMetadataBuilder setContentLanguage(String contentLanguage);

        /**
         * Sets the blob's data content encoding.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
         */
        public abstract BlobMetadataBuilder setContentEncoding(String contentEncoding);

        abstract BlobMetadataBuilder setComponentCount(Integer componentCount);

        /**
         * Sets the blob's data cache control.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Cache-Control</a>
         */
        public abstract BlobMetadataBuilder setCacheControl(String cacheControl);

        /**
         * Sets the blob's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract BlobMetadataBuilder setAcl(List<AccessControlEntry> acl);

        abstract BlobMetadataBuilder setOwner(AccessControlEntry.ProtoEntity owner);

        abstract BlobMetadataBuilder setSize(Long size);

        abstract BlobMetadataBuilder setEtag(String etag);

        abstract BlobMetadataBuilder setSelfLink(String selfLink);

        /**
         * Sets the MD5 hash of blob's data. MD5 value must be encoded in base64.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract BlobMetadataBuilder setMd5(String md5);

        /**
         * Sets the MD5 hash of blob's data from hex string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         * @throws IllegalArgumentException when given an invalid hexadecimal value.
         */
        public abstract BlobMetadataBuilder setMd5FromHexString(String md5HexString);

        /**
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> encoded in
         * base64 in big-endian order.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract BlobMetadataBuilder setCrc32c(String crc32c);

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
        public BlobMetadataBuilder setCustomTime(Long customTime) {
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
        public abstract BlobMetadataBuilder setCrc32cFromHexString(String crc32cHexString);

        abstract BlobMetadataBuilder setMediaLink(String mediaLink);

        /**
         * Sets the blob's storage class.
         */
        public abstract BlobMetadataBuilder setStorageClass(StorageClassType storageClass);

        /**
         * Sets the modification time of an object's storage class. Once set it can't be unset directly,
         * the only way is to rewrite the object with the desired storage class.
         */
        public BlobMetadataBuilder setTimeStorageClassUpdated(Long timeStorageClassUpdated) {
            throw new UnsupportedOperationException("Override setTimeStorageClassUpdated with your own implementation," + " or use com.google.cloud.storage.Blob.");
        }

        /**
         * Sets the blob's user provided metadata.
         */
        public abstract BlobMetadataBuilder setMetadata(Map<String, String> metadata);

        abstract BlobMetadataBuilder setMetageneration(Long metageneration);

        abstract BlobMetadataBuilder setDeleteTime(Long deleteTime);

        abstract BlobMetadataBuilder setUpdateTime(Long updateTime);

        abstract BlobMetadataBuilder setCreateTime(Long createTime);

        abstract BlobMetadataBuilder setIsDirectory(boolean isDirectory);

        abstract BlobMetadataBuilder setCustomerEncryption(CustomerEncryptionInfo customerEncryption);

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
        abstract BlobMetadataBuilder setKmsKeyName(String kmsKeyName);

        /**
         * Sets the blob's event-based hold.
         */
        @BetaApi
        public abstract BlobMetadataBuilder setEventBasedHold(Boolean eventBasedHold);

        /**
         * Sets the blob's temporary hold.
         */
        @BetaApi
        public abstract BlobMetadataBuilder setTemporaryHold(Boolean temporaryHold);

        @BetaApi
        abstract BlobMetadataBuilder setRetentionExpirationTime(Long retentionExpirationTime);

        /**
         * Creates a {@code BlobInfo} object.
         */
        public abstract BlobMetadata buildMetadata();
    }

    static final class BlobMetadataBuilderImpl extends BlobMetadataBuilder {

        private final String hexValues = "0123456789abcdef";

        private BlobIdentifier blobIdentifier;

        private String generatedIdentifier;

        private String mimeType;

        private String encoding;

        private String disposition;

        private String language;

        private Integer componentsCount;

        private String cacheDirective;

        private List<AccessControlEntry> accessControlList;

        private AccessControlEntry.ProtoEntity principalEntity;

        private Long dataSize;

        private String entityTag;

        private String selfUri;

        private String contentDigest;

        private String crcChecksum;

        private Long customTimestamp;

        private String mediaUrl;

        private Map<String, String> metaMap;

        private Long metaGenerationNumber;

        private Long deletionTime;

        private Long lastUpdated;

        private Long creationTime;

        private Boolean directoryFlag;

        private CustomerEncryptionInfo clientEncryptionInfo;

        private StorageClassType storageTier;

        private Long storageClassUpdateTime;

        private String kmsKeyPath;

        private Boolean eventHold;

        private Boolean tempHold;

        private Long retentionExpiryTime;

        BlobMetadataBuilderImpl(BlobIdentifier blobIdentifier) {
            this.blobIdentifier = blobIdentifier;
        }

        BlobMetadataBuilderImpl(BlobMetadata blobMetadata) {
            blobIdentifier = blobMetadata.blobIdentifier;
            generatedIdentifier = blobMetadata.generatedIdentifier;
            cacheDirective = blobMetadata.cacheDirective;
            encoding = blobMetadata.encoding;
            mimeType = blobMetadata.mimeType;
            disposition = blobMetadata.disposition;
            language = blobMetadata.language;
            componentsCount = blobMetadata.componentsCount;
            clientEncryptionInfo = blobMetadata.clientEncryptionInfo;
            accessControlList = blobMetadata.accessControlList;
            principalEntity = blobMetadata.principalEntity;
            dataSize = blobMetadata.dataSize;
            entityTag = blobMetadata.entityTag;
            selfUri = blobMetadata.selfUri;
            contentDigest = blobMetadata.contentDigest;
            crcChecksum = blobMetadata.crcChecksum;
            customTimestamp = blobMetadata.customTimestamp;
            mediaUrl = blobMetadata.mediaUrl;
            metaMap = blobMetadata.metaMap;
            metaGenerationNumber = blobMetadata.metaGenerationNumber;
            deletionTime = blobMetadata.deletionTime;
            lastUpdated = blobMetadata.lastUpdated;
            creationTime = blobMetadata.creationTime;
            directoryFlag = blobMetadata.directoryFlag;
            storageTier = blobMetadata.storageTier;
            storageClassUpdateTime = blobMetadata.storageClassUpdateTime;
            kmsKeyPath = blobMetadata.kmsKeyPath;
            eventHold = blobMetadata.eventHold;
            tempHold = blobMetadata.tempHold;
            retentionExpiryTime = blobMetadata.retentionExpiryTime;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setBlobId(BlobIdentifier blobIdentifier) {
            this.blobIdentifier = checkNotNull(blobIdentifier);
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setContentType(String mimeType) {
            this.mimeType = firstNonNull(mimeType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setContentDisposition(String disposition) {
            this.disposition = firstNonNull(disposition, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setContentLanguage(String language) {
            this.language = firstNonNull(language, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setContentEncoding(String encoding) {
            this.encoding = firstNonNull(encoding, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setComponentCount(Integer componentsCount) {
            this.componentsCount = componentsCount;
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setCacheControl(String cacheDirective) {
            this.cacheDirective = firstNonNull(cacheDirective, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setAcl(List<AccessControlEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setOwner(AccessControlEntry.ProtoEntity principalEntity) {
            this.principalEntity = principalEntity;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setSize(Long dataSize) {
            this.dataSize = dataSize;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setSelfLink(String selfUri) {
            this.selfUri = selfUri;
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setMd5(String contentDigest) {
            this.contentDigest = firstNonNull(contentDigest, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setMd5FromHexString(String hexDigest) {
            if (null == hexDigest) {
                return this;
            }
            if (0 != hexDigest.length() % 2) {
                throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
            }
            String md5HexLower = hexDigest.toLowerCase();
            ByteBuffer md5Buffer = ByteBuffer.allocate(md5HexLower.length() / 2);
            int charPos = 0;
            while (md5HexLower.length() > charPos) {
                int highOrderBits = this.hexValues.indexOf(md5HexLower.charAt(charPos));
                int lowOrderBits = this.hexValues.indexOf(md5HexLower.charAt(charPos + 1));
                if (-1 == highOrderBits || -1 == lowOrderBits) {
                    throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
                }
                md5Buffer.put((byte) (highOrderBits << 4 | lowOrderBits));
                charPos += 2;
            }
            this.contentDigest = BaseEncoding.base64().encode(md5Buffer.array());
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setCrc32c(String crcChecksum) {
            this.crcChecksum = firstNonNull(crcChecksum, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setCustomTime(Long customTimestamp) {
            this.customTimestamp = customTimestamp;
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setCrc32cFromHexString(String checksum32Hex) {
            if (null == checksum32Hex) {
                return this;
            }
            if (0 != checksum32Hex.length() % 2) {
                throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
            }
            String crc32cHexLower = checksum32Hex.toLowerCase();
            ByteBuffer crc32cBuffer = ByteBuffer.allocate(crc32cHexLower.length() / 2);
            int charPos = 0;
            while (crc32cHexLower.length() > charPos) {
                int highOrderBits = this.hexValues.indexOf(crc32cHexLower.charAt(charPos));
                int lowOrderBits = this.hexValues.indexOf(crc32cHexLower.charAt(charPos + 1));
                if (-1 == highOrderBits || -1 == lowOrderBits) {
                    throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
                }
                crc32cBuffer.put((byte) (highOrderBits << 4 | lowOrderBits));
                charPos += 2;
            }
            this.crcChecksum = BaseEncoding.base64().encode(crc32cBuffer.array());
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setMediaLink(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setMetadata(Map<String, String> metaMap) {
            if (null == metaMap) {
                this.metaMap = (Map<String, String>) Data.nullOf(EmptyImmutableMap.class);
            } else {
                this.metaMap = new HashMap<>(metaMap);
            }
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setStorageClass(StorageClassType storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setTimeStorageClassUpdated(Long storageClassUpdateTime) {
            this.storageClassUpdateTime = storageClassUpdateTime;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setMetageneration(Long metaGenerationNumber) {
            this.metaGenerationNumber = metaGenerationNumber;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setDeleteTime(Long deletionTime) {
            this.deletionTime = deletionTime;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setUpdateTime(Long lastUpdated) {
            this.lastUpdated = lastUpdated;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setCreateTime(Long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setIsDirectory(boolean directoryFlag) {
            this.directoryFlag = directoryFlag;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setCustomerEncryption(CustomerEncryptionInfo clientEncryptionInfo) {
            this.clientEncryptionInfo = clientEncryptionInfo;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setKmsKeyName(String kmsKeyPath) {
            this.kmsKeyPath = kmsKeyPath;
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setEventBasedHold(Boolean eventHold) {
            this.eventHold = eventHold;
            return this;
        }

        @Override
        public BlobMetadata.BlobMetadataBuilder setTemporaryHold(Boolean tempHold) {
            this.tempHold = tempHold;
            return this;
        }

        @Override
        BlobMetadata.BlobMetadataBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
            this.retentionExpiryTime = retentionExpiryTime;
            return this;
        }

        @Override
        public BlobMetadata buildMetadata() {
            checkNotNull(blobIdentifier);
            return new BlobMetadata(this);
        }
    }

    BlobMetadata(BlobMetadataBuilderImpl blobMetadataBuilder) {
        blobIdentifier = blobMetadataBuilder.blobIdentifier;
        generatedIdentifier = blobMetadataBuilder.generatedIdentifier;
        cacheDirective = blobMetadataBuilder.cacheDirective;
        encoding = blobMetadataBuilder.encoding;
        mimeType = blobMetadataBuilder.mimeType;
        disposition = blobMetadataBuilder.disposition;
        language = blobMetadataBuilder.language;
        componentsCount = blobMetadataBuilder.componentsCount;
        clientEncryptionInfo = blobMetadataBuilder.clientEncryptionInfo;
        accessControlList = blobMetadataBuilder.accessControlList;
        principalEntity = blobMetadataBuilder.principalEntity;
        dataSize = blobMetadataBuilder.dataSize;
        entityTag = blobMetadataBuilder.entityTag;
        selfUri = blobMetadataBuilder.selfUri;
        contentDigest = blobMetadataBuilder.contentDigest;
        crcChecksum = blobMetadataBuilder.crcChecksum;
        customTimestamp = blobMetadataBuilder.customTimestamp;
        mediaUrl = blobMetadataBuilder.mediaUrl;
        metaMap = blobMetadataBuilder.metaMap;
        metaGenerationNumber = blobMetadataBuilder.metaGenerationNumber;
        deletionTime = blobMetadataBuilder.deletionTime;
        lastUpdated = blobMetadataBuilder.lastUpdated;
        creationTime = blobMetadataBuilder.creationTime;
        directoryFlag = firstNonNull(blobMetadataBuilder.directoryFlag, Boolean.FALSE);
        storageTier = blobMetadataBuilder.storageTier;
        storageClassUpdateTime = blobMetadataBuilder.storageClassUpdateTime;
        kmsKeyPath = blobMetadataBuilder.kmsKeyPath;
        eventHold = blobMetadataBuilder.eventHold;
        tempHold = blobMetadataBuilder.tempHold;
        retentionExpiryTime = blobMetadataBuilder.retentionExpiryTime;
    }

    /**
     * Returns the blob's identity.
     */
    public BlobIdentifier getBlobId() {
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
        return Data.isNull(cacheDirective) ? null : cacheDirective;
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
    public AccessControlEntry.ProtoEntity getOwner() {
        return principalEntity;
    }

    /**
     * Returns the content length of the data in bytes.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.13">Content-Length</a>
     */
    public Long getSize() {
        return dataSize;
    }

    /**
     * Returns the blob's data content type.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
     */
    public String getContentType() {
        return Data.isNull(mimeType) ? null : mimeType;
    }

    /**
     * Returns the blob's data content encoding.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
     */
    public String getContentEncoding() {
        return Data.isNull(encoding) ? null : encoding;
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
     * {@link CloudStorageClient#compose(CloudStorageClient.ComposeBlobsRequest)} operation and are limited to a count of 1024,
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
        return Data.isNull(contentDigest) ? null : contentDigest;
    }

    /**
     * Returns the MD5 hash of blob's data decoded to string.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5ToHexString() {
        if (null == contentDigest) {
            return null;
        }
        byte[] md5Bytes = BaseEncoding.base64().decode(contentDigest);
        StringBuilder sb = new StringBuilder();
        for (byte byteVal : md5Bytes) {
            sb.append(String.format("%02x", byteVal & 0xff));
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
        return Data.isNull(crcChecksum) ? null : crcChecksum;
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
        if (null == crcChecksum) {
            return null;
        }
        byte[] crc32cBytes = BaseEncoding.base64().decode(crcChecksum);
        StringBuilder sb = new StringBuilder();
        for (byte byteVal : crc32cBytes) {
            sb.append(String.format("%02x", byteVal & 0xff));
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
        return metaGenerationNumber;
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
        return lastUpdated;
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
     * blob is returned by {@link CloudStorageClient#list(String, CloudStorageClient.BlobListOptions...)} when the {@link
     * CloudStorageClient.BlobListOptions#onlyCurrentDirectory()} option is used. When this is the case only {@link
     * #getBlobId()} and {@link #getSize()} are set for the current blob: {@link BlobIdentifier#getName()}
     * ends with the '/' character, {@link BlobIdentifier#getGeneration()} returns {@code null} and {@link
     * #getSize()} is {@code 0}.
     */
    public boolean isDirectory() {
        return directoryFlag;
    }

    /**
     * Returns information on the customer-supplied encryption key, if the blob is encrypted using
     * such a key.
     */
    public CustomerEncryptionInfo getCustomerEncryption() {
        return clientEncryptionInfo;
    }

    /**
     * Returns the storage class of the blob.
     */
    public StorageClassType getStorageClass() {
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
     * CloudStorageClient.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * CloudStorageClient#get(BlobIdentifier, CloudStorageClient.BlobGetOptions...)} and event-based hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * CloudStorageClient.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * CloudStorageClient#get(BlobIdentifier, CloudStorageClient.BlobGetOptions...)}, but event-based hold for the blob is not
     * enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * CloudStorageClient.BlobMetadataField#EVENT_BASED_HOLD} is not selected in a {@link
     * CloudStorageClient#get(BlobIdentifier, CloudStorageClient.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * BlobMetadataBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * CloudStorageClient#update(BlobMetadata, CloudStorageClient.BlobUploadOption...)} in which case the value of event-based
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
     * CloudStorageClient.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * CloudStorageClient#get(BlobIdentifier, CloudStorageClient.BlobGetOptions...)} and temporary hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * CloudStorageClient.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * CloudStorageClient#get(BlobIdentifier, CloudStorageClient.BlobGetOptions...)}, but temporary hold for the blob is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * CloudStorageClient.BlobMetadataField#TEMPORARY_HOLD} is not selected in a {@link
     * CloudStorageClient#get(BlobIdentifier, CloudStorageClient.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * BlobMetadataBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * CloudStorageClient#update(BlobMetadata, CloudStorageClient.BlobUploadOption...)} in which case the value of temporary
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getTemporaryHold() {
        return Data.<Boolean>isNull(tempHold) ? null : tempHold;
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
    public BlobMetadataBuilder toBuilderCopy() {
        return new BlobMetadataBuilderImpl(this);
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
    public boolean equals(Object otherObject) {
        return this == otherObject || null != otherObject && otherObject.getClass().equals(BlobMetadata.class) && Objects.equals(toProto(), ((BlobMetadata) otherObject).toProto());
    }

    com.google.api.services.storage.model.StorageObject toProto() {
        com.google.api.services.storage.model.StorageObject storageObj = blobIdentifier.toProto();
        if (null != accessControlList) {
            storageObj.setAcl(Lists.transform(accessControlList, new Function<AccessControlEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != deletionTime) {
            storageObj.setTimeDeleted(new DateTime(deletionTime));
        }
        if (null != lastUpdated) {
            storageObj.setUpdated(new DateTime(lastUpdated));
        }
        if (null != creationTime) {
            storageObj.setTimeCreated(new DateTime(creationTime));
        }
        if (null != customTimestamp) {
            storageObj.setCustomTime(new DateTime(customTimestamp));
        }
        if (null != dataSize) {
            storageObj.setSize(BigInteger.valueOf(dataSize));
        }
        if (null != principalEntity) {
            storageObj.setOwner(new Owner().setEntity(principalEntity.toProto()));
        }
        if (null != storageTier) {
            storageObj.setStorageClass(storageTier.toString());
        }
        if (null != storageClassUpdateTime) {
            storageObj.setTimeStorageClassUpdated(new DateTime(storageClassUpdateTime));
        }
        Map<String, String> protoMetadata = metaMap;
        if (null != metaMap && !Data.isNull(metaMap)) {
            protoMetadata = Maps.newHashMapWithExpectedSize(metaMap.size());
            for (Map.Entry<String, String> mapEntry : metaMap.entrySet()) {
                protoMetadata.put(mapEntry.getKey(), firstNonNull(mapEntry.getValue(), Data.<String>nullOf(String.class)));
            }
        }
        if (null != clientEncryptionInfo) {
            storageObj.setCustomerEncryption(clientEncryptionInfo.toProto());
        }
        if (null != retentionExpiryTime) {
            storageObj.setRetentionExpirationTime(new DateTime(retentionExpiryTime));
        }
        storageObj.setKmsKeyName(kmsKeyPath);
        storageObj.setEventBasedHold(eventHold);
        storageObj.setTemporaryHold(tempHold);
        storageObj.setMetadata(protoMetadata);
        storageObj.setCacheControl(cacheDirective);
        storageObj.setContentEncoding(encoding);
        storageObj.setCrc32c(crcChecksum);
        storageObj.setContentType(mimeType);
        storageObj.setMd5Hash(contentDigest);
        storageObj.setMediaLink(mediaUrl);
        storageObj.setMetageneration(metaGenerationNumber);
        storageObj.setContentDisposition(disposition);
        storageObj.setComponentCount(componentsCount);
        storageObj.setContentLanguage(language);
        storageObj.setEtag(entityTag);
        storageObj.setId(generatedIdentifier);
        storageObj.setSelfLink(selfUri);
        return storageObj;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static BlobMetadataBuilder newBuilder(BucketInfo bucketDetails, String objectLabel) {
        return newBuilder(bucketDetails.getName(), objectLabel);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static BlobMetadataBuilder newBuilder(String storageContainer, String objectLabel) {
        return newBuilder(BlobIdentifier.from(storageContainer, objectLabel));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static BlobMetadataBuilder newBuilder(BucketInfo bucketDetails, String objectLabel, Long version) {
        return newBuilder(bucketDetails.getName(), objectLabel, version);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static BlobMetadataBuilder newBuilder(String storageContainer, String objectLabel, Long version) {
        return newBuilder(BlobIdentifier.from(storageContainer, objectLabel, version));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided value.
     */
    public static BlobMetadataBuilder newBuilder(BlobIdentifier blobIdentifier) {
        return new BlobMetadataBuilderImpl(blobIdentifier);
    }

    static BlobMetadata fromProto(com.google.api.services.storage.model.StorageObject storageObj) {
        BlobMetadataBuilder blobMetadataBuilder = newBuilder(BlobIdentifier.fromProto(storageObj));
        if (null != storageObj.getCacheControl()) {
            blobMetadataBuilder.setCacheControl(storageObj.getCacheControl());
        }
        if (null != storageObj.getContentEncoding()) {
            blobMetadataBuilder.setContentEncoding(storageObj.getContentEncoding());
        }
        if (null != storageObj.getCrc32c()) {
            blobMetadataBuilder.setCrc32c(storageObj.getCrc32c());
        }
        if (null != storageObj.getContentType()) {
            blobMetadataBuilder.setContentType(storageObj.getContentType());
        }
        if (null != storageObj.getMd5Hash()) {
            blobMetadataBuilder.setMd5(storageObj.getMd5Hash());
        }
        if (null != storageObj.getMediaLink()) {
            blobMetadataBuilder.setMediaLink(storageObj.getMediaLink());
        }
        if (null != storageObj.getMetageneration()) {
            blobMetadataBuilder.setMetageneration(storageObj.getMetageneration());
        }
        if (null != storageObj.getContentDisposition()) {
            blobMetadataBuilder.setContentDisposition(storageObj.getContentDisposition());
        }
        if (null != storageObj.getComponentCount()) {
            blobMetadataBuilder.setComponentCount(storageObj.getComponentCount());
        }
        if (null != storageObj.getContentLanguage()) {
            blobMetadataBuilder.setContentLanguage(storageObj.getContentLanguage());
        }
        if (null != storageObj.getEtag()) {
            blobMetadataBuilder.setEtag(storageObj.getEtag());
        }
        if (null != storageObj.getId()) {
            blobMetadataBuilder.setGeneratedId(storageObj.getId());
        }
        if (null != storageObj.getSelfLink()) {
            blobMetadataBuilder.setSelfLink(storageObj.getSelfLink());
        }
        if (null != storageObj.getMetadata()) {
            blobMetadataBuilder.setMetadata(storageObj.getMetadata());
        }
        if (null != storageObj.getTimeDeleted()) {
            blobMetadataBuilder.setDeleteTime(storageObj.getTimeDeleted().getValue());
        }
        if (null != storageObj.getUpdated()) {
            blobMetadataBuilder.setUpdateTime(storageObj.getUpdated().getValue());
        }
        if (null != storageObj.getTimeCreated()) {
            blobMetadataBuilder.setCreateTime(storageObj.getTimeCreated().getValue());
        }
        if (null != storageObj.getCustomTime()) {
            blobMetadataBuilder.setCustomTime(storageObj.getCustomTime().getValue());
        }
        if (null != storageObj.getSize()) {
            blobMetadataBuilder.setSize(storageObj.getSize().longValue());
        }
        if (null != storageObj.getOwner()) {
            blobMetadataBuilder.setOwner(AccessControlEntry.ProtoEntity.fromProto(storageObj.getOwner().getEntity()));
        }
        if (null != storageObj.getAcl()) {
            blobMetadataBuilder.setAcl(Lists.transform(storageObj.getAcl(), new Function<ObjectAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                    return AccessControlEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (storageObj.containsKey("isDirectory")) {
            blobMetadataBuilder.setIsDirectory(Boolean.TRUE);
        }
        if (null != storageObj.getCustomerEncryption()) {
            blobMetadataBuilder.setCustomerEncryption(CustomerEncryptionInfo.fromProto(storageObj.getCustomerEncryption()));
        }
        if (null != storageObj.getStorageClass()) {
            blobMetadataBuilder.setStorageClass(StorageClassType.fromValue(storageObj.getStorageClass()));
        }
        if (null != storageObj.getTimeStorageClassUpdated()) {
            blobMetadataBuilder.setTimeStorageClassUpdated(storageObj.getTimeStorageClassUpdated().getValue());
        }
        if (null != storageObj.getKmsKeyName()) {
            blobMetadataBuilder.setKmsKeyName(storageObj.getKmsKeyName());
        }
        if (null != storageObj.getEventBasedHold()) {
            blobMetadataBuilder.setEventBasedHold(storageObj.getEventBasedHold());
        }
        if (null != storageObj.getTemporaryHold()) {
            blobMetadataBuilder.setTemporaryHold(storageObj.getTemporaryHold());
        }
        if (null != storageObj.getRetentionExpirationTime()) {
            blobMetadataBuilder.setRetentionExpirationTime(storageObj.getRetentionExpirationTime().getValue());
        }
        return blobMetadataBuilder.buildMetadata();
    }
}
