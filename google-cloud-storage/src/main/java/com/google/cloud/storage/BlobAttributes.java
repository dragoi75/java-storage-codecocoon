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
public class BlobAttributes implements Serializable {

    static final Function<BlobAttributes, com.google.api.services.storage.model.StorageObject> ATTRIBUTES_TO_PB_FUNCTION = new Function<BlobAttributes, com.google.api.services.storage.model.StorageObject>() {

        @Override
        public com.google.api.services.storage.model.StorageObject apply(BlobAttributes blobInfo) {
            return blobInfo.toProto();
        }
    };

    private static final long serialVersionUID = -5625857076205028976L;

    private final BlobIdentifier blobIdentifier;

    private final String generatedIdentifier;

    private final String selfUrl;

    private final String cacheDirective;

    private final List<AclEntry> accessControlList;

    private final AclEntry.TypedEntity ownerEntity;

    private final Long contentSize;

    private final String entityTag;

    private final String md5Hash;

    private final String crc32cChecksum;

    private final String mediaUrl;

    private final Map<String, String> metaMap;

    private final Long metaGeneration;

    private final Long deletionTime;

    private final Long lastUpdatedTime;

    private final Long creationTime;

    private final String mimeType;

    private final String encodingScheme;

    private final String dispositionType;

    private final String contentLocale;

    private final StorageClassType storageTier;

    private final Integer partCount;

    private final boolean directoryFlag;

    private final CustomerEncryptionInfo customerEncryptionInfo;

    private final String kmsKey;

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

        private final String algorithm;

        private final String keyHashSha256;

        @Override
        public final int hashCode() {
            return Objects.hash(algorithm, keyHashSha256);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("encryptionAlgorithm", getEncryptionAlgorithm()).add("keySha256", getKeySha256()).toString();
        }

        @Override
        public final boolean equals(Object other) {
            return this == other || null != other && other.getClass().equals(CustomerEncryptionInfo.class) && Objects.equals(toProto(), ((CustomerEncryptionInfo) other).toProto());
        }

        com.google.api.services.storage.model.StorageObject.CustomerEncryption toProto() {
            return new com.google.api.services.storage.model.StorageObject.CustomerEncryption().setEncryptionAlgorithm(algorithm).setKeySha256(keyHashSha256);
        }

        static CustomerEncryptionInfo fromProto(com.google.api.services.storage.model.StorageObject.CustomerEncryption customerEncryptionProto) {
            return new CustomerEncryptionInfo(customerEncryptionProto.getEncryptionAlgorithm(), customerEncryptionProto.getKeySha256());
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
            return keyHashSha256;
        }

        CustomerEncryptionInfo(String algorithm, String keyHashSha256) {
            this.algorithm = algorithm;
            this.keyHashSha256 = keyHashSha256;
        }

    }

    /**
     * Builder for {@code BlobInfo}.
     */
    public abstract static class StorageObjectBuilder {

        abstract StorageObjectBuilder setCustomerEncryption(CustomerEncryptionInfo customerEncryption);

        abstract StorageObjectBuilder setIsDirectory(boolean isDirectory);

        abstract StorageObjectBuilder setDeleteTime(Long deleteTime);

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

        abstract StorageObjectBuilder setSize(Long size);

        abstract StorageObjectBuilder setEtag(String etag);

        abstract StorageObjectBuilder setMediaLink(String mediaLink);

        /**
         * Sets the MD5 hash of blob's data. MD5 value must be encoded in base64.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract StorageObjectBuilder setMd5(String md5);

        abstract StorageObjectBuilder setOwner(AclEntry.TypedEntity owner);

        /**
         * Sets the blob's temporary hold.
         */
        @BetaApi
        public abstract StorageObjectBuilder setTemporaryHold(Boolean temporaryHold);

        /**
         * Sets the blob's storage class.
         */
        public abstract StorageObjectBuilder setStorageClass(StorageClassType storageClass);

        /**
         * Sets the blob's data content type.
         *
         * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
         */
        public abstract StorageObjectBuilder setContentType(String contentType);

        /**
         * Sets the MD5 hash of blob's data from hex string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         * @throws IllegalArgumentException when given an invalid hexadecimal value.
         */
        public abstract StorageObjectBuilder setMd5FromHexString(String md5HexString);

        abstract StorageObjectBuilder setKmsKeyName(String kmsKeyName);

        abstract StorageObjectBuilder setComponentCount(Integer componentCount);

        abstract StorageObjectBuilder setCreateTime(Long createTime);

        abstract StorageObjectBuilder setGeneratedId(String generatedId);

        /**
         * Sets the blob's event-based hold.
         */
        @BetaApi
        public abstract StorageObjectBuilder setEventBasedHold(Boolean eventBasedHold);

        /**
         * Creates a {@code BlobInfo} object.
         */
        public abstract BlobAttributes buildObject();

        /**
         * Sets the blob's user provided metadata.
         */
        public abstract StorageObjectBuilder setMetadata(Map<String, String> metadata);

        /**
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> encoded in
         * base64 in big-endian order.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract StorageObjectBuilder setCrc32c(String crc32c);

        @BetaApi
        abstract StorageObjectBuilder setRetentionExpirationTime(Long retentionExpirationTime);

        /**
         * Sets the blob's data content encoding.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
         */
        public abstract StorageObjectBuilder setContentEncoding(String contentEncoding);

        abstract StorageObjectBuilder setMetageneration(Long metageneration);

        /**
         * Sets the blob's data cache control.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Cache-Control</a>
         */
        public abstract StorageObjectBuilder setCacheControl(String cacheControl);

        abstract StorageObjectBuilder setSelfLink(String selfLink);

        abstract StorageObjectBuilder setUpdateTime(Long updateTime);

        /**
         * Sets the blob's data content disposition.
         *
         * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
         */
        public abstract StorageObjectBuilder setContentDisposition(String contentDisposition);

        /**
         * Sets the blob identity.
         */
        public abstract StorageObjectBuilder setBlobId(BlobIdentifier blobId);

        /**
         * Sets the blob's data content language.
         *
         * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
         */
        public abstract StorageObjectBuilder setContentLanguage(String contentLanguage);

        /**
         * Sets the blob's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract StorageObjectBuilder setAcl(List<AclEntry> acl);

    }

    static final class BlobInfoBuilderImpl extends StorageObjectBuilder {

        private final String hexValues = "0123456789abcdef";

        private BlobIdentifier blobIdentifier;

        private String generatedIdentifier;

        private String mimeType;

        private String encodingScheme;

        private String dispositionType;

        private String contentLocale;

        private Integer partCount;

        private String cacheDirective;

        private List<AclEntry> accessControlList;

        private AclEntry.TypedEntity ownerEntity;

        private Long contentSize;

        private String entityTag;

        private String selfUrl;

        private String md5Hash;

        private String crc32cChecksum;

        private String mediaUrl;

        private Map<String, String> metaMap;

        private Long metaGeneration;

        private Long deletionTime;

        private Long lastUpdatedTime;

        private Long creationTime;

        private Boolean directoryFlag;

        private CustomerEncryptionInfo customerEncryptionInfo;

        private StorageClassType storageTier;

        private String kmsKey;

        private Boolean eventHold;

        private Boolean tempHold;

        private Long retentionExpiryTime;

        @Override
        public BlobAttributes.StorageObjectBuilder setMd5FromHexString(String hexDigest) {
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
            this.md5Hash = BaseEncoding.base64().encode(digestByteBuffer.array());
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setEventBasedHold(Boolean eventHold) {
            this.eventHold = eventHold;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setIsDirectory(boolean directoryFlag) {
            this.directoryFlag = directoryFlag;
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setAcl(List<AclEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setOwner(AclEntry.TypedEntity ownerEntity) {
            this.ownerEntity = ownerEntity;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setMediaLink(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setContentLanguage(String contentLocale) {
            this.contentLocale = firstNonNull(contentLocale, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setCreateTime(Long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setUpdateTime(Long lastUpdatedTime) {
            this.lastUpdatedTime = lastUpdatedTime;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setDeleteTime(Long deletionTime) {
            this.deletionTime = deletionTime;
            return this;
        }

        BlobInfoBuilderImpl(BlobAttributes blobAttributes) {
            blobIdentifier = blobAttributes.blobIdentifier;
            generatedIdentifier = blobAttributes.generatedIdentifier;
            cacheDirective = blobAttributes.cacheDirective;
            encodingScheme = blobAttributes.encodingScheme;
            mimeType = blobAttributes.mimeType;
            dispositionType = blobAttributes.dispositionType;
            contentLocale = blobAttributes.contentLocale;
            partCount = blobAttributes.partCount;
            customerEncryptionInfo = blobAttributes.customerEncryptionInfo;
            accessControlList = blobAttributes.accessControlList;
            ownerEntity = blobAttributes.ownerEntity;
            contentSize = blobAttributes.contentSize;
            entityTag = blobAttributes.entityTag;
            selfUrl = blobAttributes.selfUrl;
            md5Hash = blobAttributes.md5Hash;
            crc32cChecksum = blobAttributes.crc32cChecksum;
            mediaUrl = blobAttributes.mediaUrl;
            metaMap = blobAttributes.metaMap;
            metaGeneration = blobAttributes.metaGeneration;
            deletionTime = blobAttributes.deletionTime;
            lastUpdatedTime = blobAttributes.lastUpdatedTime;
            creationTime = blobAttributes.creationTime;
            directoryFlag = blobAttributes.directoryFlag;
            storageTier = blobAttributes.storageTier;
            kmsKey = blobAttributes.kmsKey;
            eventHold = blobAttributes.eventHold;
            tempHold = blobAttributes.tempHold;
            retentionExpiryTime = blobAttributes.retentionExpiryTime;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setContentDisposition(String dispositionType) {
            this.dispositionType = firstNonNull(dispositionType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
            this.retentionExpiryTime = retentionExpiryTime;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setMetageneration(Long metaGeneration) {
            this.metaGeneration = metaGeneration;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setKmsKeyName(String kmsKey) {
            this.kmsKey = kmsKey;
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setStorageClass(StorageClassType storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setSize(Long contentSize) {
            this.contentSize = contentSize;
            return this;
        }

        @Override
        public BlobAttributes buildObject() {
            checkNotNull(blobIdentifier);
            return new BlobAttributes(this);
        }

        @Override
        BlobAttributes.StorageObjectBuilder setSelfLink(String selfUrl) {
            this.selfUrl = selfUrl;
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setTemporaryHold(Boolean tempHold) {
            this.tempHold = tempHold;
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setContentEncoding(String encodingScheme) {
            this.encodingScheme = firstNonNull(encodingScheme, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setCacheControl(String cacheDirective) {
            this.cacheDirective = firstNonNull(cacheDirective, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setCrc32c(String crc32cChecksum) {
            this.crc32cChecksum = firstNonNull(crc32cChecksum, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setCrc32cFromHexString(String crcHex) {
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
                int highBits = this.hexValues.indexOf(crcHexLower.charAt(charPos));
                int lowBits = this.hexValues.indexOf(crcHexLower.charAt(charPos + 1));
                if (-1 == highBits || -1 == lowBits) {
                    throw new IllegalArgumentException("each byte must be represented by 2 valid hexadecimal characters");
                }
                crcBuffer.put((byte) (highBits << 4 | lowBits));
                charPos += 2;
            }
            this.crc32cChecksum = BaseEncoding.base64().encode(crcBuffer.array());
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setComponentCount(Integer partCount) {
            this.partCount = partCount;
            return this;
        }

        @Override
        BlobAttributes.StorageObjectBuilder setCustomerEncryption(CustomerEncryptionInfo customerEncryptionInfo) {
            this.customerEncryptionInfo = customerEncryptionInfo;
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setMd5(String md5Hash) {
            this.md5Hash = firstNonNull(md5Hash, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setBlobId(BlobIdentifier blobIdentifier) {
            this.blobIdentifier = checkNotNull(blobIdentifier);
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setContentType(String mimeType) {
            this.mimeType = firstNonNull(mimeType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobAttributes.StorageObjectBuilder setMetadata(Map<String, String> metaMap) {
            if (null == metaMap) {
                this.metaMap = (Map<String, String>) Data.nullOf(EmptyImmutableMap.class);
            } else {
                this.metaMap = new HashMap<>(metaMap);
            }
            return this;
        }

        BlobInfoBuilderImpl(BlobIdentifier blobIdentifier) {
            this.blobIdentifier = blobIdentifier;
        }

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
     * Returns the blob's data content encoding.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
     */
    public String getContentEncoding() {
        return Data.isNull(encodingScheme) ? null : encodingScheme;
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
     * Returns the MD5 hash of blob's data encoded in base64.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5() {
        return Data.isNull(md5Hash) ? null : md5Hash;
    }

    /**
     * Returns the storage class of the blob.
     */
    public StorageClassType getStorageClass() {
        return storageTier;
    }

    /**
     * Returns {@code true} if the current blob represents a directory. This can only happen if the
     * blob is returned by {@link StorageClient#list(String, StorageClient.BlobListOptions...)} when the {@link
     * StorageClient.BlobListOptions#useCurrentDirectory()} option is used. When this is the case only {@link
     * #getBlobId()} and {@link #getSize()} are set for the current blob: {@link BlobIdentifier#getName()}
     * ends with the '/' character, {@link BlobIdentifier#getGeneration()} returns {@code null} and {@link
     * #getSize()} is {@code 0}.
     */
    public boolean isDirectory() {
        return directoryFlag;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageClient.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * StorageClient#get(BlobIdentifier, StorageClient.BlobGetOptions...)} and event-based hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageClient.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * StorageClient#get(BlobIdentifier, StorageClient.BlobGetOptions...)}, but event-based hold for the blob is not
     * enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageClient.BlobMetadataField#EVENT_BASED_HOLD} is not selected in a {@link
     * StorageClient#get(BlobIdentifier, StorageClient.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * StorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * StorageClient#update(BlobAttributes, StorageClient.BlobUploadOption...)} in which case the value of event-based
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getEventBasedHold() {
        return Data.<Boolean>isNull(eventHold) ? null : eventHold;
    }

    /**
     * Returns the blob's name.
     */
    public String getName() {
        return getBlobId().getName();
    }

    /**
     * Returns the deletion time of the blob expressed as the number of milliseconds since the Unix
     * epoch.
     */
    public Long getDeleteTime() {
        return deletionTime;
    }

    /**
     * Returns blob's user provided metadata.
     */
    public Map<String, String> getMetadata() {
        return null == metaMap || Data.isNull(metaMap) ? null : Collections.unmodifiableMap(metaMap);
    }

    /**
     * Returns the service-generated for the blob.
     */
    public String getGeneratedId() {
        return generatedIdentifier;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(String container, String identifier, Long genId) {
        return newBuilder(BlobIdentifier.create(container, identifier, genId));
    }

    /**
     * Returns information on the customer-supplied encryption key, if the blob is encrypted using
     * such a key.
     */
    public CustomerEncryptionInfo getCustomerEncryption() {
        return customerEncryptionInfo;
    }

    /**
     * Returns the blob's identity.
     */
    public BlobIdentifier getBlobId() {
        return blobIdentifier;
    }

    com.google.api.services.storage.model.StorageObject toProto() {
        com.google.api.services.storage.model.StorageObject storedObj = blobIdentifier.toStorageObject();
        if (null != accessControlList) {
            storedObj.setAcl(Lists.transform(accessControlList, new Function<AclEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AclEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != deletionTime) {
            storedObj.setTimeDeleted(new DateTime(deletionTime));
        }
        if (null != lastUpdatedTime) {
            storedObj.setUpdated(new DateTime(lastUpdatedTime));
        }
        if (null != creationTime) {
            storedObj.setTimeCreated(new DateTime(creationTime));
        }
        if (null != contentSize) {
            storedObj.setSize(BigInteger.valueOf(contentSize));
        }
        if (null != ownerEntity) {
            storedObj.setOwner(new Owner().setEntity(ownerEntity.toProtoString()));
        }
        if (null != storageTier) {
            storedObj.setStorageClass(storageTier.toString());
        }
        Map<String, String> protoMeta = metaMap;
        if (null != metaMap && !Data.isNull(metaMap)) {
            protoMeta = Maps.newHashMapWithExpectedSize(metaMap.size());
            for (Map.Entry<String, String> keyValuePair : metaMap.entrySet()) {
                protoMeta.put(keyValuePair.getKey(), firstNonNull(keyValuePair.getValue(), Data.<String>nullOf(String.class)));
            }
        }
        if (null != customerEncryptionInfo) {
            storedObj.setCustomerEncryption(customerEncryptionInfo.toProto());
        }
        if (null != retentionExpiryTime) {
            storedObj.setRetentionExpirationTime(new DateTime(retentionExpiryTime));
        }
        storedObj.setKmsKeyName(kmsKey);
        storedObj.setEventBasedHold(eventHold);
        storedObj.setTemporaryHold(tempHold);
        storedObj.setMetadata(protoMeta);
        storedObj.setCacheControl(cacheDirective);
        storedObj.setContentEncoding(encodingScheme);
        storedObj.setCrc32c(crc32cChecksum);
        storedObj.setContentType(mimeType);
        storedObj.setMd5Hash(md5Hash);
        storedObj.setMediaLink(mediaUrl);
        storedObj.setMetageneration(metaGeneration);
        storedObj.setContentDisposition(dispositionType);
        storedObj.setComponentCount(partCount);
        storedObj.setContentLanguage(contentLocale);
        storedObj.setEtag(entityTag);
        storedObj.setId(generatedIdentifier);
        storedObj.setSelfLink(selfUrl);
        return storedObj;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).add("size", getSize()).add("content-type", getContentType()).add("metadata", getMetadata()).toString();
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(BucketInfo containerDetails, String identifier) {
        return newBuilder(containerDetails.getName(), identifier);
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
        byte[] md5Digest = BaseEncoding.base64().decode(md5Hash);
        StringBuilder hexAccumulator = new StringBuilder();
        for (byte byteVal : md5Digest) {
            hexAccumulator.append(String.format("%02x", byteVal & 0xff));
        }
        return hexAccumulator.toString();
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(String container, String identifier) {
        return newBuilder(BlobIdentifier.create(container, identifier));
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
        byte[] crcBytes = BaseEncoding.base64().decode(crc32cChecksum);
        StringBuilder hexAccumulator = new StringBuilder();
        for (byte byteVal : crcBytes) {
            hexAccumulator.append(String.format("%02x", byteVal & 0xff));
        }
        return hexAccumulator.toString();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || null != other && other.getClass().equals(BlobAttributes.class) && Objects.equals(toProto(), ((BlobAttributes) other).toProto());
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(BucketInfo containerDetails, String identifier, Long genId) {
        return newBuilder(containerDetails.getName(), identifier, genId);
    }

    /**
     * Returns the number of components that make up this blob. Components are accumulated through the
     * {@link StorageClient#compose(StorageClient.ComposeBlobsRequest)} operation and are limited to a count of 1024,
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
     * Returns the blob's data content type.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
     */
    public String getContentType() {
        return Data.isNull(mimeType) ? null : mimeType;
    }

    /**
     * Returns the name of the containing bucket.
     */
    public String getBucket() {
        return getBlobId().getBucket();
    }

    /**
     * Returns the last modification time of the blob's metadata expressed as the number of
     * milliseconds since the Unix epoch.
     */
    public Long getUpdateTime() {
        return lastUpdatedTime;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided value.
     */
    public static StorageObjectBuilder newBuilder(BlobIdentifier blobIdentifier) {
        return new BlobInfoBuilderImpl(blobIdentifier);
    }

    /**
     * Returns the blob's data content disposition.
     *
     * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
     */
    public String getContentDisposition() {
        return Data.isNull(dispositionType) ? null : dispositionType;
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

    /**
     * Returns the Cloud KMS key used to encrypt the blob, if any.
     */
    public String getKmsKeyName() {
        return kmsKey;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageClient.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * StorageClient#get(BlobIdentifier, StorageClient.BlobGetOptions...)} and temporary hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageClient.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * StorageClient#get(BlobIdentifier, StorageClient.BlobGetOptions...)}, but temporary hold for the blob is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageClient.BlobMetadataField#TEMPORARY_HOLD} is not selected in a {@link
     * StorageClient#get(BlobIdentifier, StorageClient.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * StorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * StorageClient#update(BlobAttributes, StorageClient.BlobUploadOption...)} in which case the value of temporary
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getTemporaryHold() {
        return Data.<Boolean>isNull(tempHold) ? null : tempHold;
    }

    @Override
    public int hashCode() {
        return Objects.hash(blobIdentifier);
    }

    /**
     * Returns the blob's media download link.
     */
    public String getMediaLink() {
        return mediaUrl;
    }

    static BlobAttributes fromProto(com.google.api.services.storage.model.StorageObject storedObj) {
        StorageObjectBuilder blobInfoCreator = newBuilder(BlobIdentifier.fromProto(storedObj));
        if (null != storedObj.getCacheControl()) {
            blobInfoCreator.setCacheControl(storedObj.getCacheControl());
        }
        if (null != storedObj.getContentEncoding()) {
            blobInfoCreator.setContentEncoding(storedObj.getContentEncoding());
        }
        if (null != storedObj.getCrc32c()) {
            blobInfoCreator.setCrc32c(storedObj.getCrc32c());
        }
        if (null != storedObj.getContentType()) {
            blobInfoCreator.setContentType(storedObj.getContentType());
        }
        if (null != storedObj.getMd5Hash()) {
            blobInfoCreator.setMd5(storedObj.getMd5Hash());
        }
        if (null != storedObj.getMediaLink()) {
            blobInfoCreator.setMediaLink(storedObj.getMediaLink());
        }
        if (null != storedObj.getMetageneration()) {
            blobInfoCreator.setMetageneration(storedObj.getMetageneration());
        }
        if (null != storedObj.getContentDisposition()) {
            blobInfoCreator.setContentDisposition(storedObj.getContentDisposition());
        }
        if (null != storedObj.getComponentCount()) {
            blobInfoCreator.setComponentCount(storedObj.getComponentCount());
        }
        if (null != storedObj.getContentLanguage()) {
            blobInfoCreator.setContentLanguage(storedObj.getContentLanguage());
        }
        if (null != storedObj.getEtag()) {
            blobInfoCreator.setEtag(storedObj.getEtag());
        }
        if (null != storedObj.getId()) {
            blobInfoCreator.setGeneratedId(storedObj.getId());
        }
        if (null != storedObj.getSelfLink()) {
            blobInfoCreator.setSelfLink(storedObj.getSelfLink());
        }
        if (null != storedObj.getMetadata()) {
            blobInfoCreator.setMetadata(storedObj.getMetadata());
        }
        if (null != storedObj.getTimeDeleted()) {
            blobInfoCreator.setDeleteTime(storedObj.getTimeDeleted().getValue());
        }
        if (null != storedObj.getUpdated()) {
            blobInfoCreator.setUpdateTime(storedObj.getUpdated().getValue());
        }
        if (null != storedObj.getTimeCreated()) {
            blobInfoCreator.setCreateTime(storedObj.getTimeCreated().getValue());
        }
        if (null != storedObj.getSize()) {
            blobInfoCreator.setSize(storedObj.getSize().longValue());
        }
        if (null != storedObj.getOwner()) {
            blobInfoCreator.setOwner(AclEntry.TypedEntity.fromProto(storedObj.getOwner().getEntity()));
        }
        if (null != storedObj.getAcl()) {
            blobInfoCreator.setAcl(Lists.transform(storedObj.getAcl(), new Function<ObjectAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(ObjectAccessControl objectAccessControl) {
                    return AclEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (storedObj.containsKey("isDirectory")) {
            blobInfoCreator.setIsDirectory(Boolean.TRUE);
        }
        if (null != storedObj.getCustomerEncryption()) {
            blobInfoCreator.setCustomerEncryption(CustomerEncryptionInfo.fromProto(storedObj.getCustomerEncryption()));
        }
        if (null != storedObj.getStorageClass()) {
            blobInfoCreator.setStorageClass(StorageClassType.fromValue(storedObj.getStorageClass()));
        }
        if (null != storedObj.getKmsKeyName()) {
            blobInfoCreator.setKmsKeyName(storedObj.getKmsKeyName());
        }
        if (null != storedObj.getEventBasedHold()) {
            blobInfoCreator.setEventBasedHold(storedObj.getEventBasedHold());
        }
        if (null != storedObj.getTemporaryHold()) {
            blobInfoCreator.setTemporaryHold(storedObj.getTemporaryHold());
        }
        if (null != storedObj.getRetentionExpirationTime()) {
            blobInfoCreator.setRetentionExpirationTime(storedObj.getRetentionExpirationTime().getValue());
        }
        return blobInfoCreator.buildObject();
    }

    /**
     * Returns the URI of this blob as a string.
     */
    public String getSelfLink() {
        return selfUrl;
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
     * Returns the blob's data content language.
     *
     * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
     */
    public String getContentLanguage() {
        return Data.isNull(contentLocale) ? null : contentLocale;
    }

    /**
     * Returns the creation time of the blob expressed as the number of milliseconds since the Unix
     * epoch.
     */
    public Long getCreateTime() {
        return creationTime;
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
     * Returns the blob's data cache control.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Cache-Control</a>
     */
    public String getCacheControl() {
        return Data.isNull(cacheDirective) ? null : cacheDirective;
    }

    /**
     * Returns blob's data generation. Used for blob versioning.
     */
    public Long getGeneration() {
        return getBlobId().getGeneration();
    }

    /**
     * Returns the blob's owner. This will always be the uploader of the blob.
     */
    public AclEntry.TypedEntity getOwner() {
        return ownerEntity;
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

    BlobAttributes(BlobInfoBuilderImpl blobInfoCreator) {
        blobIdentifier = blobInfoCreator.blobIdentifier;
        generatedIdentifier = blobInfoCreator.generatedIdentifier;
        cacheDirective = blobInfoCreator.cacheDirective;
        encodingScheme = blobInfoCreator.encodingScheme;
        mimeType = blobInfoCreator.mimeType;
        dispositionType = blobInfoCreator.dispositionType;
        contentLocale = blobInfoCreator.contentLocale;
        partCount = blobInfoCreator.partCount;
        customerEncryptionInfo = blobInfoCreator.customerEncryptionInfo;
        accessControlList = blobInfoCreator.accessControlList;
        ownerEntity = blobInfoCreator.ownerEntity;
        contentSize = blobInfoCreator.contentSize;
        entityTag = blobInfoCreator.entityTag;
        selfUrl = blobInfoCreator.selfUrl;
        md5Hash = blobInfoCreator.md5Hash;
        crc32cChecksum = blobInfoCreator.crc32cChecksum;
        mediaUrl = blobInfoCreator.mediaUrl;
        metaMap = blobInfoCreator.metaMap;
        metaGeneration = blobInfoCreator.metaGeneration;
        deletionTime = blobInfoCreator.deletionTime;
        lastUpdatedTime = blobInfoCreator.lastUpdatedTime;
        creationTime = blobInfoCreator.creationTime;
        directoryFlag = firstNonNull(blobInfoCreator.directoryFlag, Boolean.FALSE);
        storageTier = blobInfoCreator.storageTier;
        kmsKey = blobInfoCreator.kmsKey;
        eventHold = blobInfoCreator.eventHold;
        tempHold = blobInfoCreator.tempHold;
        retentionExpiryTime = blobInfoCreator.retentionExpiryTime;
    }

    /**
     * Returns a builder for the current blob.
     */
    public StorageObjectBuilder asBuilder() {
        return new BlobInfoBuilderImpl(this);
    }

}
