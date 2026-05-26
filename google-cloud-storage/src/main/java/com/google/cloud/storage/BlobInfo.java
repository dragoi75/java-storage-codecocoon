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
public class BlobInfo implements Serializable {

    static final Function<BlobInfo, com.google.api.services.storage.model.StorageObject> INFO_TO_PROTO_FN = new Function<BlobInfo, com.google.api.services.storage.model.StorageObject>() {

        @Override
        public com.google.api.services.storage.model.StorageObject apply(BlobInfo blobInfo) {
            return blobInfo.toProto();
        }
    };

    private static final long serialVersionUID = -5625857076205028976L;

    private final BlobIdentifier identifier;

    private final String generatedIdentifier;

    private final String selfUri;

    private final String cacheDirective;

    private final List<AclEntry> accessControlList;

    private final AclEntry.BaseEntity owningEntity;

    private final Long contentSize;

    private final String entityTag;

    private final String md5Checksum;

    private final String crc32cChecksum;

    private final String mediaUrl;

    private final Map<String, String> meta;

    private final Long metaGeneration;

    private final Long deletionTime;

    private final Long lastUpdateTime;

    private final Long creationTime;

    private final String mimeType;

    private final String encoding;

    private final String contentDispositionHeader;

    private final String language;

    private final StorageTier storageTier;

    private final Integer partCount;

    private final boolean directoryFlag;

    private final CustomerEncryptionInfo encryptionInfo;

    private final String kmsKey;

    private final Boolean eventHold;

    private final Boolean tempHold;

    private final Long retentionExpireTime;

    /**
     * This class is meant for internal use only. Users are discouraged from using this class.
     */
    public static final class UnmodifiableEmptyMap<K, V> extends AbstractMap<K, V> {

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

        private final String algorithm;

        private final String keyHash;

        com.google.api.services.storage.model.StorageObject.CustomerEncryption toProto() {
            return new com.google.api.services.storage.model.StorageObject.CustomerEncryption().setEncryptionAlgorithm(algorithm).setKeySha256(keyHash);
        }

        @Override
        public final int hashCode() {
            return Objects.hash(algorithm, keyHash);
        }

        @Override
        public final boolean equals(Object other) {
            return this == other || null != other && other.getClass().equals(CustomerEncryptionInfo.class) && Objects.equals(toProto(), ((CustomerEncryptionInfo) other).toProto());
        }

        static CustomerEncryptionInfo fromProto(com.google.api.services.storage.model.StorageObject.CustomerEncryption customerEncryptionProto) {
            return new CustomerEncryptionInfo(customerEncryptionProto.getEncryptionAlgorithm(), customerEncryptionProto.getKeySha256());
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("encryptionAlgorithm", getEncryptionAlgorithm()).add("keySha256", getKeySha256()).toString();
        }

        /**
         * Returns the algorithm used to encrypt the blob.
         */
        public String getEncryptionAlgorithm() {
            return algorithm;
        }

        /**
         * Returns the SHA256 hash of the encryption key.
         */
        public String getKeySha256() {
            return keyHash;
        }

        CustomerEncryptionInfo(String algorithm, String keyHash) {
            this.algorithm = algorithm;
            this.keyHash = keyHash;
        }

    }

    /**
     * Builder for {@code BlobInfo}.
     */
    public abstract static class BlobMetadataBuilder {

        abstract BlobMetadataBuilder setEtag(String etag);

        /**
         * Creates a {@code BlobInfo} object.
         */
        public abstract BlobInfo buildMetadata();

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

        /**
         * Sets the blob's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract BlobMetadataBuilder setAcl(List<AclEntry> acl);

        /**
         * Sets the blob's event-based hold.
         */
        @BetaApi
        public abstract BlobMetadataBuilder setEventBasedHold(Boolean eventBasedHold);

        /**
         * Sets the MD5 hash of blob's data. MD5 value must be encoded in base64.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract BlobMetadataBuilder setMd5(String md5);

        abstract BlobMetadataBuilder setOwner(AclEntry.BaseEntity owner);

        /**
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> encoded in
         * base64 in big-endian order.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract BlobMetadataBuilder setCrc32c(String crc32c);

        abstract BlobMetadataBuilder setUpdateTime(Long updateTime);

        abstract BlobMetadataBuilder setMediaLink(String mediaLink);

        abstract BlobMetadataBuilder setKmsKeyName(String kmsKeyName);

        abstract BlobMetadataBuilder setSize(Long size);

        abstract BlobMetadataBuilder setSelfLink(String selfLink);

        /**
         * Sets the blob's temporary hold.
         */
        @BetaApi
        public abstract BlobMetadataBuilder setTemporaryHold(Boolean temporaryHold);

        /**
         * Sets the blob's storage class.
         */
        public abstract BlobMetadataBuilder setStorageClass(StorageTier storageClass);

        @BetaApi
        abstract BlobMetadataBuilder setRetentionExpirationTime(Long retentionExpirationTime);

        abstract BlobMetadataBuilder setMetageneration(Long metageneration);

        abstract BlobMetadataBuilder setComponentCount(Integer componentCount);

        abstract BlobMetadataBuilder setDeleteTime(Long deleteTime);

        /**
         * Sets the blob's data content language.
         *
         * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
         */
        public abstract BlobMetadataBuilder setContentLanguage(String contentLanguage);

        abstract BlobMetadataBuilder setGeneratedId(String generatedId);

        abstract BlobMetadataBuilder setIsDirectory(boolean isDirectory);

        /**
         * Sets the blob's data content encoding.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
         */
        public abstract BlobMetadataBuilder setContentEncoding(String contentEncoding);

        /**
         * Sets the blob's data content disposition.
         *
         * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
         */
        public abstract BlobMetadataBuilder setContentDisposition(String contentDisposition);

        abstract BlobMetadataBuilder setCustomerEncryption(CustomerEncryptionInfo customerEncryption);

        /**
         * Sets the blob's user provided metadata.
         */
        public abstract BlobMetadataBuilder setMetadata(Map<String, String> metadata);

        /**
         * Sets the blob's data cache control.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Cache-Control</a>
         */
        public abstract BlobMetadataBuilder setCacheControl(String cacheControl);

        /**
         * Sets the MD5 hash of blob's data from hex string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         * @throws IllegalArgumentException when given an invalid hexadecimal value.
         */
        public abstract BlobMetadataBuilder setMd5FromHexString(String md5HexString);

        abstract BlobMetadataBuilder setCreateTime(Long createTime);

        /**
         * Sets the blob identity.
         */
        public abstract BlobMetadataBuilder setBlobId(BlobIdentifier blobId);

        /**
         * Sets the blob's data content type.
         *
         * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
         */
        public abstract BlobMetadataBuilder setContentType(String contentType);

    }

    static final class StorageObjectBuilder extends BlobMetadataBuilder {

        private final String hexValues = "0123456789abcdef";

        private BlobIdentifier identifier;

        private String generatedIdentifier;

        private String mimeType;

        private String encoding;

        private String contentDispositionHeader;

        private String language;

        private Integer partCount;

        private String cacheDirective;

        private List<AclEntry> accessControlList;

        private AclEntry.BaseEntity owningEntity;

        private Long contentSize;

        private String entityTag;

        private String selfUri;

        private String md5Checksum;

        private String crc32cChecksum;

        private String mediaUrl;

        private Map<String, String> meta;

        private Long metaGeneration;

        private Long deletionTime;

        private Long lastUpdateTime;

        private Long creationTime;

        private Boolean directoryFlag;

        private CustomerEncryptionInfo encryptionInfo;

        private StorageTier storageTier;

        private String kmsKey;

        private Boolean eventHold;

        private Boolean tempHold;

        private Long retentionExpireTime;

        @Override
        BlobInfo.BlobMetadataBuilder setCustomerEncryption(CustomerEncryptionInfo encryptionInfo) {
            this.encryptionInfo = encryptionInfo;
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setCrc32cFromHexString(String crcHex) {
            if (null == crcHex) {
                return this;
            }
            if (0 != crcHex.length() % 2) {
                throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
            }
            String crc32cHexLower = crcHex.toLowerCase();
            ByteBuffer crc32cBuffer = ByteBuffer.allocate(crc32cHexLower.length() / 2);
            int charPos = 0;
            while (crc32cHexLower.length() > charPos) {
                int highBits = this.hexValues.indexOf(crc32cHexLower.charAt(charPos));
                int lowBits = this.hexValues.indexOf(crc32cHexLower.charAt(charPos + 1));
                if (-1 == highBits || -1 == lowBits) {
                    throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
                }
                crc32cBuffer.put((byte) (highBits << 4 | lowBits));
                charPos += 2;
            }
            this.crc32cChecksum = BaseEncoding.base64().encode(crc32cBuffer.array());
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setStorageClass(StorageTier storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setMediaLink(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setIsDirectory(boolean directoryFlag) {
            this.directoryFlag = directoryFlag;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setRetentionExpirationTime(Long retentionExpireTime) {
            this.retentionExpireTime = retentionExpireTime;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setSize(Long contentSize) {
            this.contentSize = contentSize;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setCrc32c(String crc32cChecksum) {
            this.crc32cChecksum = firstNonNull(crc32cChecksum, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setMd5(String md5Checksum) {
            this.md5Checksum = firstNonNull(md5Checksum, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setKmsKeyName(String kmsKey) {
            this.kmsKey = kmsKey;
            return this;
        }

        @Override
        public BlobInfo buildMetadata() {
            checkNotNull(identifier);
            return new BlobInfo(this);
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setAcl(List<AclEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setDeleteTime(Long deletionTime) {
            this.deletionTime = deletionTime;
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setMetadata(Map<String, String> meta) {
            if (null == meta) {
                this.meta = (Map<String, String>) Data.nullOf(UnmodifiableEmptyMap.class);
            } else {
                this.meta = new HashMap<>(meta);
            }
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setComponentCount(Integer partCount) {
            this.partCount = partCount;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setCreateTime(Long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setContentEncoding(String encoding) {
            this.encoding = firstNonNull(encoding, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setTemporaryHold(Boolean tempHold) {
            this.tempHold = tempHold;
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setContentType(String mimeType) {
            this.mimeType = firstNonNull(mimeType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setBlobId(BlobIdentifier identifier) {
            this.identifier = checkNotNull(identifier);
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setContentLanguage(String language) {
            this.language = firstNonNull(language, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setOwner(AclEntry.BaseEntity owningEntity) {
            this.owningEntity = owningEntity;
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setContentDisposition(String contentDispositionHeader) {
            this.contentDispositionHeader = firstNonNull(contentDispositionHeader, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setMd5FromHexString(String hexDigest) {
            if (null == hexDigest) {
                return this;
            }
            if (0 != hexDigest.length() % 2) {
                throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
            }
            String hexStringLower = hexDigest.toLowerCase();
            ByteBuffer digestByteBuffer = ByteBuffer.allocate(hexStringLower.length() / 2);
            int charPos = 0;
            while (hexStringLower.length() > charPos) {
                int highBits = this.hexValues.indexOf(hexStringLower.charAt(charPos));
                int lowBits = this.hexValues.indexOf(hexStringLower.charAt(charPos + 1));
                if (-1 == highBits || -1 == lowBits) {
                    throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
                }
                digestByteBuffer.put((byte) (highBits << 4 | lowBits));
                charPos += 2;
            }
            this.md5Checksum = BaseEncoding.base64().encode(digestByteBuffer.array());
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setSelfLink(String selfUri) {
            this.selfUri = selfUri;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setUpdateTime(Long lastUpdateTime) {
            this.lastUpdateTime = lastUpdateTime;
            return this;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setMetageneration(Long metaGeneration) {
            this.metaGeneration = metaGeneration;
            return this;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setEventBasedHold(Boolean eventHold) {
            this.eventHold = eventHold;
            return this;
        }

        StorageObjectBuilder(BlobInfo blobDetails) {
            identifier = blobDetails.identifier;
            generatedIdentifier = blobDetails.generatedIdentifier;
            cacheDirective = blobDetails.cacheDirective;
            encoding = blobDetails.encoding;
            mimeType = blobDetails.mimeType;
            contentDispositionHeader = blobDetails.contentDispositionHeader;
            language = blobDetails.language;
            partCount = blobDetails.partCount;
            encryptionInfo = blobDetails.encryptionInfo;
            accessControlList = blobDetails.accessControlList;
            owningEntity = blobDetails.owningEntity;
            contentSize = blobDetails.contentSize;
            entityTag = blobDetails.entityTag;
            selfUri = blobDetails.selfUri;
            md5Checksum = blobDetails.md5Checksum;
            crc32cChecksum = blobDetails.crc32cChecksum;
            mediaUrl = blobDetails.mediaUrl;
            meta = blobDetails.meta;
            metaGeneration = blobDetails.metaGeneration;
            deletionTime = blobDetails.deletionTime;
            lastUpdateTime = blobDetails.lastUpdateTime;
            creationTime = blobDetails.creationTime;
            directoryFlag = blobDetails.directoryFlag;
            storageTier = blobDetails.storageTier;
            kmsKey = blobDetails.kmsKey;
            eventHold = blobDetails.eventHold;
            tempHold = blobDetails.tempHold;
            retentionExpireTime = blobDetails.retentionExpireTime;
        }

        @Override
        BlobInfo.BlobMetadataBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        StorageObjectBuilder(BlobIdentifier identifier) {
            this.identifier = identifier;
        }

        @Override
        public BlobInfo.BlobMetadataBuilder setCacheControl(String cacheDirective) {
            this.cacheDirective = firstNonNull(cacheDirective, Data.<String>nullOf(String.class));
            return this;
        }

    }

    /**
     * Returns the URI of this blob as a string.
     */
    public String getSelfLink() {
        return selfUri;
    }

    /**
     * Returns the deletion time of the blob expressed as the number of milliseconds since the Unix
     * epoch.
     */
    public Long getDeleteTime() {
        return deletionTime;
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
     * Returns the blob's access control configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AclEntry> getAcl() {
        return accessControlList;
    }

    /**
     * Returns the retention expiration time of the blob as {@code Long}, if a retention period is
     * defined. If retention period is not defined this value returns {@code null}
     */
    @BetaApi
    public Long getRetentionExpirationTime() {
        return Data.<Long>isNull(retentionExpireTime) ? null : retentionExpireTime;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static BlobMetadataBuilder newBuilder(BucketMetadata bucketMetadataObj, String objectName) {
        return newBuilder(bucketMetadataObj.getName(), objectName);
    }

    /**
     * Returns blob's user provided metadata.
     */
    public Map<String, String> getMetadata() {
        return null == meta || Data.isNull(meta) ? null : Collections.unmodifiableMap(meta);
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * Storage#get(BlobIdentifier, Storage.BlobGetOptions...)} and event-based hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * Storage#get(BlobIdentifier, Storage.BlobGetOptions...)}, but event-based hold for the blob is not
     * enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BlobMetadataField#EVENT_BASED_HOLD} is not selected in a {@link
     * Storage#get(BlobIdentifier, Storage.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * BlobMetadataBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * Storage#update(BlobInfo, Storage.BlobUploadOption...)} in which case the value of event-based
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getEventBasedHold() {
        return Data.<Boolean>isNull(eventHold) ? null : eventHold;
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
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided value.
     */
    public static BlobMetadataBuilder newBuilder(BlobIdentifier identifier) {
        return new StorageObjectBuilder(identifier);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static BlobMetadataBuilder newBuilder(String bucketName, String objectName, Long generationId) {
        return newBuilder(BlobIdentifier.create(bucketName, objectName, generationId));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static BlobMetadataBuilder newBuilder(String bucketName, String objectName) {
        return newBuilder(BlobIdentifier.create(bucketName, objectName));
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
        if (null == crc32cChecksum) {
            return null;
        }
        byte[] crc32cBytes = BaseEncoding.base64().decode(crc32cChecksum);
        StringBuilder hexStringBuilder = new StringBuilder();
        for (byte byteVal : crc32cBytes) {
            hexStringBuilder.append(String.format("%02x", byteVal & 0xff));
        }
        return hexStringBuilder.toString();
    }

    /**
     * Returns a builder for the current blob.
     */
    public BlobMetadataBuilder asBuilder() {
        return new StorageObjectBuilder(this);
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * Storage#get(BlobIdentifier, Storage.BlobGetOptions...)} and temporary hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * Storage#get(BlobIdentifier, Storage.BlobGetOptions...)}, but temporary hold for the blob is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BlobMetadataField#TEMPORARY_HOLD} is not selected in a {@link
     * Storage#get(BlobIdentifier, Storage.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * BlobMetadataBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * Storage#update(BlobInfo, Storage.BlobUploadOption...)} in which case the value of temporary
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getTemporaryHold() {
        return Data.<Boolean>isNull(tempHold) ? null : tempHold;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || null != other && other.getClass().equals(BlobInfo.class) && Objects.equals(toProto(), ((BlobInfo) other).toProto());
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static BlobMetadataBuilder newBuilder(BucketMetadata bucketMetadataObj, String objectName, Long generationId) {
        return newBuilder(bucketMetadataObj.getName(), objectName, generationId);
    }

    /**
     * Returns the blob's name.
     */
    public String getName() {
        return getBlobId().getName();
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
     * Returns the MD5 hash of blob's data decoded to string.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5ToHexString() {
        if (null == md5Checksum) {
            return null;
        }
        byte[] md5Bytes = BaseEncoding.base64().decode(md5Checksum);
        StringBuilder hexStringBuilder = new StringBuilder();
        for (byte byteVal : md5Bytes) {
            hexStringBuilder.append(String.format("%02x", byteVal & 0xff));
        }
        return hexStringBuilder.toString();
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
        return Data.isNull(crc32cChecksum) ? null : crc32cChecksum;
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier);
    }

    /**
     * Returns the service-generated for the blob.
     */
    public String getGeneratedId() {
        return generatedIdentifier;
    }

    /**
     * Returns the blob's owner. This will always be the uploader of the blob.
     */
    public AclEntry.BaseEntity getOwner() {
        return owningEntity;
    }

    /**
     * Returns the blob's media download link.
     */
    public String getMediaLink() {
        return mediaUrl;
    }

    /**
     * Returns the storage class of the blob.
     */
    public StorageTier getStorageClass() {
        return storageTier;
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
     * Returns the last modification time of the blob's metadata expressed as the number of
     * milliseconds since the Unix epoch.
     */
    public Long getUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * Returns the blob's identity.
     */
    public BlobIdentifier getBlobId() {
        return identifier;
    }

    static BlobInfo fromProto(com.google.api.services.storage.model.StorageObject storageProto) {
        BlobMetadataBuilder storageBuilder = newBuilder(BlobIdentifier.fromProto(storageProto));
        if (null != storageProto.getCacheControl()) {
            storageBuilder.setCacheControl(storageProto.getCacheControl());
        }
        if (null != storageProto.getContentEncoding()) {
            storageBuilder.setContentEncoding(storageProto.getContentEncoding());
        }
        if (null != storageProto.getCrc32c()) {
            storageBuilder.setCrc32c(storageProto.getCrc32c());
        }
        if (null != storageProto.getContentType()) {
            storageBuilder.setContentType(storageProto.getContentType());
        }
        if (null != storageProto.getMd5Hash()) {
            storageBuilder.setMd5(storageProto.getMd5Hash());
        }
        if (null != storageProto.getMediaLink()) {
            storageBuilder.setMediaLink(storageProto.getMediaLink());
        }
        if (null != storageProto.getMetageneration()) {
            storageBuilder.setMetageneration(storageProto.getMetageneration());
        }
        if (null != storageProto.getContentDisposition()) {
            storageBuilder.setContentDisposition(storageProto.getContentDisposition());
        }
        if (null != storageProto.getComponentCount()) {
            storageBuilder.setComponentCount(storageProto.getComponentCount());
        }
        if (null != storageProto.getContentLanguage()) {
            storageBuilder.setContentLanguage(storageProto.getContentLanguage());
        }
        if (null != storageProto.getEtag()) {
            storageBuilder.setEtag(storageProto.getEtag());
        }
        if (null != storageProto.getId()) {
            storageBuilder.setGeneratedId(storageProto.getId());
        }
        if (null != storageProto.getSelfLink()) {
            storageBuilder.setSelfLink(storageProto.getSelfLink());
        }
        if (null != storageProto.getMetadata()) {
            storageBuilder.setMetadata(storageProto.getMetadata());
        }
        if (null != storageProto.getTimeDeleted()) {
            storageBuilder.setDeleteTime(storageProto.getTimeDeleted().getValue());
        }
        if (null != storageProto.getUpdated()) {
            storageBuilder.setUpdateTime(storageProto.getUpdated().getValue());
        }
        if (null != storageProto.getTimeCreated()) {
            storageBuilder.setCreateTime(storageProto.getTimeCreated().getValue());
        }
        if (null != storageProto.getSize()) {
            storageBuilder.setSize(storageProto.getSize().longValue());
        }
        if (null != storageProto.getOwner()) {
            storageBuilder.setOwner(AclEntry.BaseEntity.fromProto(storageProto.getOwner().getEntity()));
        }
        if (null != storageProto.getAcl()) {
            storageBuilder.setAcl(Lists.transform(storageProto.getAcl(), new Function<ObjectAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(ObjectAccessControl objectAccessControl) {
                    return AclEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (storageProto.containsKey("isDirectory")) {
            storageBuilder.setIsDirectory(Boolean.TRUE);
        }
        if (null != storageProto.getCustomerEncryption()) {
            storageBuilder.setCustomerEncryption(CustomerEncryptionInfo.fromProto(storageProto.getCustomerEncryption()));
        }
        if (null != storageProto.getStorageClass()) {
            storageBuilder.setStorageClass(StorageTier.fromValue(storageProto.getStorageClass()));
        }
        if (null != storageProto.getKmsKeyName()) {
            storageBuilder.setKmsKeyName(storageProto.getKmsKeyName());
        }
        if (null != storageProto.getEventBasedHold()) {
            storageBuilder.setEventBasedHold(storageProto.getEventBasedHold());
        }
        if (null != storageProto.getTemporaryHold()) {
            storageBuilder.setTemporaryHold(storageProto.getTemporaryHold());
        }
        if (null != storageProto.getRetentionExpirationTime()) {
            storageBuilder.setRetentionExpirationTime(storageProto.getRetentionExpirationTime().getValue());
        }
        return storageBuilder.buildMetadata();
    }

    /**
     * Returns the creation time of the blob expressed as the number of milliseconds since the Unix
     * epoch.
     */
    public Long getCreateTime() {
        return creationTime;
    }

    BlobInfo(StorageObjectBuilder storageBuilder) {
        identifier = storageBuilder.identifier;
        generatedIdentifier = storageBuilder.generatedIdentifier;
        cacheDirective = storageBuilder.cacheDirective;
        encoding = storageBuilder.encoding;
        mimeType = storageBuilder.mimeType;
        contentDispositionHeader = storageBuilder.contentDispositionHeader;
        language = storageBuilder.language;
        partCount = storageBuilder.partCount;
        encryptionInfo = storageBuilder.encryptionInfo;
        accessControlList = storageBuilder.accessControlList;
        owningEntity = storageBuilder.owningEntity;
        contentSize = storageBuilder.contentSize;
        entityTag = storageBuilder.entityTag;
        selfUri = storageBuilder.selfUri;
        md5Checksum = storageBuilder.md5Checksum;
        crc32cChecksum = storageBuilder.crc32cChecksum;
        mediaUrl = storageBuilder.mediaUrl;
        meta = storageBuilder.meta;
        metaGeneration = storageBuilder.metaGeneration;
        deletionTime = storageBuilder.deletionTime;
        lastUpdateTime = storageBuilder.lastUpdateTime;
        creationTime = storageBuilder.creationTime;
        directoryFlag = firstNonNull(storageBuilder.directoryFlag, Boolean.FALSE);
        storageTier = storageBuilder.storageTier;
        kmsKey = storageBuilder.kmsKey;
        eventHold = storageBuilder.eventHold;
        tempHold = storageBuilder.tempHold;
        retentionExpireTime = storageBuilder.retentionExpireTime;
    }

    /**
     * Returns the MD5 hash of blob's data encoded in base64.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5() {
        return Data.isNull(md5Checksum) ? null : md5Checksum;
    }

    com.google.api.services.storage.model.StorageObject toProto() {
        com.google.api.services.storage.model.StorageObject storageProto = identifier.toProto();
        if (null != accessControlList) {
            storageProto.setAcl(Lists.transform(accessControlList, new Function<AclEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AclEntry acl) {
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
        if (null != contentSize) {
            storageProto.setSize(BigInteger.valueOf(contentSize));
        }
        if (null != owningEntity) {
            storageProto.setOwner(new Owner().setEntity(owningEntity.toProto()));
        }
        if (null != storageTier) {
            storageProto.setStorageClass(storageTier.toString());
        }
        Map<String, String> protoMetadata = meta;
        if (null != meta && !Data.isNull(meta)) {
            protoMetadata = Maps.newHashMapWithExpectedSize(meta.size());
            for (Map.Entry<String, String> mapEntry : meta.entrySet()) {
                protoMetadata.put(mapEntry.getKey(), firstNonNull(mapEntry.getValue(), Data.<String>nullOf(String.class)));
            }
        }
        if (null != encryptionInfo) {
            storageProto.setCustomerEncryption(encryptionInfo.toProto());
        }
        if (null != retentionExpireTime) {
            storageProto.setRetentionExpirationTime(new DateTime(retentionExpireTime));
        }
        storageProto.setKmsKeyName(kmsKey);
        storageProto.setEventBasedHold(eventHold);
        storageProto.setTemporaryHold(tempHold);
        storageProto.setMetadata(protoMetadata);
        storageProto.setCacheControl(cacheDirective);
        storageProto.setContentEncoding(encoding);
        storageProto.setCrc32c(crc32cChecksum);
        storageProto.setContentType(mimeType);
        storageProto.setMd5Hash(md5Checksum);
        storageProto.setMediaLink(mediaUrl);
        storageProto.setMetageneration(metaGeneration);
        storageProto.setContentDisposition(contentDispositionHeader);
        storageProto.setComponentCount(partCount);
        storageProto.setContentLanguage(language);
        storageProto.setEtag(entityTag);
        storageProto.setId(generatedIdentifier);
        storageProto.setSelfLink(selfUri);
        return storageProto;
    }

    /**
     * Returns the number of components that make up this blob. Components are accumulated through the
     * {@link Storage#compose(Storage.ComposeObjectsRequest)} operation and are limited to a count of 1024,
     * counting 1 for each non-composite component blob and componentCount for each composite
     * component blob. This value is set only for composite blobs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/composite-objects#_Count">Component Count
     *     Property</a>
     */
    public Integer getComponentCount() {
        return partCount;
    }

    /**
     * Returns {@code true} if the current blob represents a directory. This can only happen if the
     * blob is returned by {@link Storage#list(String, Storage.BlobListOptions...)} when the {@link
     * Storage.BlobListOptions#useCurrentDirectory()} option is used. When this is the case only {@link
     * #getBlobId()} and {@link #getSize()} are set for the current blob: {@link BlobIdentifier#getName()}
     * ends with the '/' character, {@link BlobIdentifier#getGeneration()} returns {@code null} and {@link
     * #getSize()} is {@code 0}.
     */
    public boolean isDirectory() {
        return directoryFlag;
    }

    /**
     * Returns the blob's data content disposition.
     *
     * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
     */
    public String getContentDisposition() {
        return Data.isNull(contentDispositionHeader) ? null : contentDispositionHeader;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).add("size", getSize()).add("content-type", getContentType()).add("metadata", getMetadata()).toString();
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
     * Returns the name of the containing bucket.
     */
    public String getBucket() {
        return getBlobId().getBucket();
    }

    /**
     * Returns information on the customer-supplied encryption key, if the blob is encrypted using
     * such a key.
     */
    public CustomerEncryptionInfo getCustomerEncryption() {
        return encryptionInfo;
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
     * Returns the content length of the data in bytes.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.13">Content-Length</a>
     */
    public Long getSize() {
        return contentSize;
    }

    /**
     * Returns the Cloud KMS key used to encrypt the blob, if any.
     */
    public String getKmsKeyName() {
        return kmsKey;
    }

    /**
     * Returns blob's data generation. Used for blob versioning.
     */
    public Long getGeneration() {
        return getBlobId().getGeneration();
    }

}
