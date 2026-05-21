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
import com.google.api.services.storage.model.StorageObject;
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
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Google Storage object metadata.
 *
 * @see <a href="https://cloud.google.com/storage/docs/concepts-techniques#concepts">Concepts and
 *     Terminology</a>
 */
public class BlobMetadata implements Serializable {

    static final Function<BlobMetadata, StorageObject> METADATA_TO_PROTO_FN = new Function<BlobMetadata, StorageObject>() {

        @Override
        public StorageObject apply(BlobMetadata blobInfo) {
            return blobInfo.toProto();
        }
    };

    private static final long serialVersionUID = -5625857076205028976L;

    private final BlobIdentifier id;

    private final String generatedIdentifier;

    private final String selfUri;

    private final String cacheDirective;

    private final List<AccessControlEntry> accessControlList;

    private final AccessControlEntry.AbstractEntity entityPrincipal;

    private final Long sizeBytes;

    private final String entityTag;

    private final String contentChecksum;

    private final String crcChecksum;

    private final String mediaUrl;

    private final Map<String, String> metaMap;

    private final Long metaGenerationNumber;

    private final Long deletionTime;

    private final Long lastUpdatedTime;

    private final Long creationTime;

    private final String mimeType;

    private final String encoding;

    private final String disposition;

    private final String language;

    private final StorageClassType storageTier;

    private final Integer componentsCount;

    private final boolean directoryFlag;

    private final CustomerEncryptionSettings encryptionSettings;

    private final String kmsKeyId;

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
    public static class CustomerEncryptionSettings implements Serializable {

        private static final long serialVersionUID = -2133042982786959351L;

        private final String algorithm;

        private final String keyHash;

        CustomerEncryptionSettings(String algorithm, String keyHash) {
            this.algorithm = algorithm;
            this.keyHash = keyHash;
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

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("encryptionAlgorithm", getEncryptionAlgorithm()).add("keySha256", getKeySha256()).toString();
        }

        @Override
        public final int hashCode() {
            return Objects.hash(algorithm, keyHash);
        }

        @Override
        public final boolean equals(Object other) {
            return this == other || null != other && other.getClass().equals(CustomerEncryptionSettings.class) && Objects.equals(toProto(), ((CustomerEncryptionSettings) other).toProto());
        }

        StorageObject.CustomerEncryption toProto() {
            return new StorageObject.CustomerEncryption().setEncryptionAlgorithm(algorithm).setKeySha256(keyHash);
        }

        static CustomerEncryptionSettings fromProto(StorageObject.CustomerEncryption protoEncryption) {
            return new CustomerEncryptionSettings(protoEncryption.getEncryptionAlgorithm(), protoEncryption.getKeySha256());
        }
    }

    /**
     * Builder for {@code BlobInfo}.
     */
    public abstract static class CloudStorageObjectBuilder {

        /**
         * Sets the blob identity.
         */
        public abstract CloudStorageObjectBuilder setBlobId(BlobIdentifier blobId);

        abstract CloudStorageObjectBuilder setGeneratedId(String generatedId);

        /**
         * Sets the blob's data content type.
         *
         * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
         */
        public abstract CloudStorageObjectBuilder setContentType(String contentType);

        /**
         * Sets the blob's data content disposition.
         *
         * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
         */
        public abstract CloudStorageObjectBuilder setContentDisposition(String contentDisposition);

        /**
         * Sets the blob's data content language.
         *
         * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
         */
        public abstract CloudStorageObjectBuilder setContentLanguage(String contentLanguage);

        /**
         * Sets the blob's data content encoding.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
         */
        public abstract CloudStorageObjectBuilder setContentEncoding(String contentEncoding);

        abstract CloudStorageObjectBuilder setComponentCount(Integer componentCount);

        /**
         * Sets the blob's data cache control.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Cache-Control</a>
         */
        public abstract CloudStorageObjectBuilder setCacheControl(String cacheControl);

        /**
         * Sets the blob's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract CloudStorageObjectBuilder setAcl(List<AccessControlEntry> acl);

        abstract CloudStorageObjectBuilder setOwner(AccessControlEntry.AbstractEntity owner);

        abstract CloudStorageObjectBuilder setSize(Long size);

        abstract CloudStorageObjectBuilder setEtag(String etag);

        abstract CloudStorageObjectBuilder setSelfLink(String selfLink);

        /**
         * Sets the MD5 hash of blob's data. MD5 value must be encoded in base64.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract CloudStorageObjectBuilder setMd5(String md5);

        /**
         * Sets the MD5 hash of blob's data from hex string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract CloudStorageObjectBuilder setMd5FromHexString(String md5HexString);

        /**
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> encoded in
         * base64 in big-endian order.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract CloudStorageObjectBuilder setCrc32c(String crc32c);

        /**
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> from hex
         * string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract CloudStorageObjectBuilder setCrc32cFromHexString(String crc32cHexString);

        abstract CloudStorageObjectBuilder setMediaLink(String mediaLink);

        /**
         * Sets the blob's storage class.
         */
        public abstract CloudStorageObjectBuilder setStorageClass(StorageClassType storageClass);

        /**
         * Sets the blob's user provided metadata.
         */
        public abstract CloudStorageObjectBuilder setMetadata(Map<String, String> metadata);

        abstract CloudStorageObjectBuilder setMetageneration(Long metageneration);

        abstract CloudStorageObjectBuilder setDeleteTime(Long deleteTime);

        abstract CloudStorageObjectBuilder setUpdateTime(Long updateTime);

        abstract CloudStorageObjectBuilder setCreateTime(Long createTime);

        abstract CloudStorageObjectBuilder setIsDirectory(boolean isDirectory);

        abstract CloudStorageObjectBuilder setCustomerEncryption(CustomerEncryptionSettings customerEncryption);

        abstract CloudStorageObjectBuilder setKmsKeyName(String kmsKeyName);

        /**
         * Sets the blob's event-based hold.
         */
        @BetaApi
        public abstract CloudStorageObjectBuilder setEventBasedHold(Boolean eventBasedHold);

        /**
         * Sets the blob's temporary hold.
         */
        @BetaApi
        public abstract CloudStorageObjectBuilder setTemporaryHold(Boolean temporaryHold);

        @BetaApi
        abstract CloudStorageObjectBuilder setRetentionExpirationTime(Long retentionExpirationTime);

        /**
         * Creates a {@code BlobInfo} object.
         */
        public abstract BlobMetadata buildObject();
    }

    static final class BlobInfoBuilderImpl extends CloudStorageObjectBuilder {

        private BlobIdentifier id;

        private String generatedIdentifier;

        private String mimeType;

        private String encoding;

        private String disposition;

        private String language;

        private Integer componentsCount;

        private String cacheDirective;

        private List<AccessControlEntry> accessControlList;

        private AccessControlEntry.AbstractEntity entityPrincipal;

        private Long sizeBytes;

        private String entityTag;

        private String selfUri;

        private String contentChecksum;

        private String crcChecksum;

        private String mediaUrl;

        private Map<String, String> metaMap;

        private Long metaGenerationNumber;

        private Long deletionTime;

        private Long lastUpdatedTime;

        private Long creationTime;

        private Boolean directoryFlag;

        private CustomerEncryptionSettings encryptionSettings;

        private StorageClassType storageTier;

        private String kmsKeyId;

        private Boolean eventHold;

        private Boolean tempHold;

        private Long retentionExpiryTime;

        BlobInfoBuilderImpl(BlobIdentifier id) {
            this.id = id;
        }

        BlobInfoBuilderImpl(BlobMetadata blobMetadata) {
            id = blobMetadata.id;
            generatedIdentifier = blobMetadata.generatedIdentifier;
            cacheDirective = blobMetadata.cacheDirective;
            encoding = blobMetadata.encoding;
            mimeType = blobMetadata.mimeType;
            disposition = blobMetadata.disposition;
            language = blobMetadata.language;
            componentsCount = blobMetadata.componentsCount;
            encryptionSettings = blobMetadata.encryptionSettings;
            accessControlList = blobMetadata.accessControlList;
            entityPrincipal = blobMetadata.entityPrincipal;
            sizeBytes = blobMetadata.sizeBytes;
            entityTag = blobMetadata.entityTag;
            selfUri = blobMetadata.selfUri;
            contentChecksum = blobMetadata.contentChecksum;
            crcChecksum = blobMetadata.crcChecksum;
            mediaUrl = blobMetadata.mediaUrl;
            metaMap = blobMetadata.metaMap;
            metaGenerationNumber = blobMetadata.metaGenerationNumber;
            deletionTime = blobMetadata.deletionTime;
            lastUpdatedTime = blobMetadata.lastUpdatedTime;
            creationTime = blobMetadata.creationTime;
            directoryFlag = blobMetadata.directoryFlag;
            storageTier = blobMetadata.storageTier;
            kmsKeyId = blobMetadata.kmsKeyId;
            eventHold = blobMetadata.eventHold;
            tempHold = blobMetadata.tempHold;
            retentionExpiryTime = blobMetadata.retentionExpiryTime;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setBlobId(BlobIdentifier id) {
            this.id = checkNotNull(id);
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setContentType(String mimeType) {
            this.mimeType = firstNonNull(mimeType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setContentDisposition(String disposition) {
            this.disposition = firstNonNull(disposition, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setContentLanguage(String language) {
            this.language = firstNonNull(language, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setContentEncoding(String encoding) {
            this.encoding = firstNonNull(encoding, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setComponentCount(Integer componentsCount) {
            this.componentsCount = componentsCount;
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setCacheControl(String cacheDirective) {
            this.cacheDirective = firstNonNull(cacheDirective, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setAcl(List<AccessControlEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setOwner(AccessControlEntry.AbstractEntity entityPrincipal) {
            this.entityPrincipal = entityPrincipal;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setSize(Long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setSelfLink(String selfUri) {
            this.selfUri = selfUri;
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setMd5(String contentChecksum) {
            this.contentChecksum = firstNonNull(contentChecksum, Data.<String>nullOf(String.class));
            return this;
        }

        public CloudStorageObjectBuilder setMd5FromHexString(String hashHex) {
            if (null == hashHex) {
                return this;
            }
            byte[] inputBytes = new BigInteger(hashHex, 16).toByteArray();
            int leadingZerosCount = inputBytes.length - hashHex.length() / 2;
            if (0 < leadingZerosCount) {
                inputBytes = Arrays.copyOfRange(inputBytes, leadingZerosCount, inputBytes.length);
            }
            this.contentChecksum = BaseEncoding.base64().encode(inputBytes);
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setCrc32c(String crcChecksum) {
            this.crcChecksum = firstNonNull(crcChecksum, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setCrc32cFromHexString(String crcHex) {
            if (null == crcHex) {
                return this;
            }
            byte[] inputBytes = new BigInteger(crcHex, 16).toByteArray();
            int leadingZerosCount = inputBytes.length - crcHex.length() / 2;
            if (0 < leadingZerosCount) {
                inputBytes = Arrays.copyOfRange(inputBytes, leadingZerosCount, inputBytes.length);
            }
            this.crcChecksum = BaseEncoding.base64().encode(inputBytes);
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setMediaLink(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setMetadata(Map<String, String> metaMap) {
            if (null == metaMap) {
                this.metaMap = (Map<String, String>) Data.nullOf(EmptyImmutableMap.class);
            } else {
                this.metaMap = new HashMap<>(metaMap);
            }
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setStorageClass(StorageClassType storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setMetageneration(Long metaGenerationNumber) {
            this.metaGenerationNumber = metaGenerationNumber;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setDeleteTime(Long deletionTime) {
            this.deletionTime = deletionTime;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setUpdateTime(Long lastUpdatedTime) {
            this.lastUpdatedTime = lastUpdatedTime;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setCreateTime(Long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setIsDirectory(boolean directoryFlag) {
            this.directoryFlag = directoryFlag;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setCustomerEncryption(CustomerEncryptionSettings encryptionSettings) {
            this.encryptionSettings = encryptionSettings;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setKmsKeyName(String kmsKeyId) {
            this.kmsKeyId = kmsKeyId;
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setEventBasedHold(Boolean eventHold) {
            this.eventHold = eventHold;
            return this;
        }

        @Override
        public BlobMetadata.CloudStorageObjectBuilder setTemporaryHold(Boolean tempHold) {
            this.tempHold = tempHold;
            return this;
        }

        @Override
        BlobMetadata.CloudStorageObjectBuilder setRetentionExpirationTime(Long retentionExpiryTime) {
            this.retentionExpiryTime = retentionExpiryTime;
            return this;
        }

        @Override
        public BlobMetadata buildObject() {
            checkNotNull(id);
            return new BlobMetadata(this);
        }
    }

    BlobMetadata(BlobInfoBuilderImpl blobInfoBuilder) {
        id = blobInfoBuilder.id;
        generatedIdentifier = blobInfoBuilder.generatedIdentifier;
        cacheDirective = blobInfoBuilder.cacheDirective;
        encoding = blobInfoBuilder.encoding;
        mimeType = blobInfoBuilder.mimeType;
        disposition = blobInfoBuilder.disposition;
        language = blobInfoBuilder.language;
        componentsCount = blobInfoBuilder.componentsCount;
        encryptionSettings = blobInfoBuilder.encryptionSettings;
        accessControlList = blobInfoBuilder.accessControlList;
        entityPrincipal = blobInfoBuilder.entityPrincipal;
        sizeBytes = blobInfoBuilder.sizeBytes;
        entityTag = blobInfoBuilder.entityTag;
        selfUri = blobInfoBuilder.selfUri;
        contentChecksum = blobInfoBuilder.contentChecksum;
        crcChecksum = blobInfoBuilder.crcChecksum;
        mediaUrl = blobInfoBuilder.mediaUrl;
        metaMap = blobInfoBuilder.metaMap;
        metaGenerationNumber = blobInfoBuilder.metaGenerationNumber;
        deletionTime = blobInfoBuilder.deletionTime;
        lastUpdatedTime = blobInfoBuilder.lastUpdatedTime;
        creationTime = blobInfoBuilder.creationTime;
        directoryFlag = firstNonNull(blobInfoBuilder.directoryFlag, Boolean.FALSE);
        storageTier = blobInfoBuilder.storageTier;
        kmsKeyId = blobInfoBuilder.kmsKeyId;
        eventHold = blobInfoBuilder.eventHold;
        tempHold = blobInfoBuilder.tempHold;
        retentionExpiryTime = blobInfoBuilder.retentionExpiryTime;
    }

    /**
     * Returns the blob's identity.
     */
    public BlobIdentifier getBlobId() {
        return id;
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
    public AccessControlEntry.AbstractEntity getOwner() {
        return entityPrincipal;
    }

    /**
     * Returns the content length of the data in bytes.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.13">Content-Length</a>
     */
    public Long getSize() {
        return sizeBytes;
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
     * {@link StorageService#compose(StorageService.ComposeObjectRequest)} operation and are limited to a count of 1024,
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
        return Data.isNull(contentChecksum) ? null : contentChecksum;
    }

    /**
     * Returns the MD5 hash of blob's data decoded to string.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5ToHexString() {
        if (null == contentChecksum) {
            return null;
        }
        byte[] decodedHash = BaseEncoding.base64().decode(contentChecksum);
        StringBuilder hexStringBuilder = new StringBuilder();
        for (byte byteValue : decodedHash) {
            hexStringBuilder.append(String.format("%02x", byteValue & 0xff));
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
        byte[] decodedCrc = BaseEncoding.base64().decode(crcChecksum);
        StringBuilder hexStringBuilder = new StringBuilder();
        for (byte byteValue : decodedCrc) {
            hexStringBuilder.append(String.format("%02x", byteValue & 0xff));
        }
        return hexStringBuilder.toString();
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
     * Returns the deletion time of the blob.
     */
    public Long getDeleteTime() {
        return deletionTime;
    }

    /**
     * Returns the last modification time of the blob's metadata.
     */
    public Long getUpdateTime() {
        return lastUpdatedTime;
    }

    /**
     * Returns the creation time of the blob.
     */
    public Long getCreateTime() {
        return creationTime;
    }

    /**
     * Returns {@code true} if the current blob represents a directory. This can only happen if the
     * blob is returned by {@link StorageService#list(String, StorageService.BlobListOptions...)} when the {@link
     * StorageService.BlobListOptions#currentDirectoryOnly()} option is used. When this is the case only {@link
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
    public CustomerEncryptionSettings getCustomerEncryption() {
        return encryptionSettings;
    }

    /**
     * Returns the storage class of the blob.
     */
    public StorageClassType getStorageClass() {
        return storageTier;
    }

    /**
     * Returns the Cloud KMS key used to encrypt the blob, if any.
     */
    public String getKmsKeyName() {
        return kmsKeyId;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageService.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobFetchOption...)} and event-based hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobFetchOption...)}, but event-based hold for the blob is not
     * enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BlobMetadataField#EVENT_BASED_HOLD} is not selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobFetchOption...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * CloudStorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * StorageService#update(BlobMetadata, StorageService.BlobUploadOption...)} in which case the value of event-based
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
     * StorageService.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobFetchOption...)} and temporary hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobFetchOption...)}, but temporary hold for the blob is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BlobMetadataField#TEMPORARY_HOLD} is not selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobFetchOption...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * CloudStorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * StorageService#update(BlobMetadata, StorageService.BlobUploadOption...)} in which case the value of temporary
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
    public CloudStorageObjectBuilder asBuilder() {
        return new BlobInfoBuilderImpl(this);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).add("size", getSize()).add("content-type", getContentType()).add("metadata", getMetadata()).toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || null != other && other.getClass().equals(BlobMetadata.class) && Objects.equals(toProto(), ((BlobMetadata) other).toProto());
    }

    StorageObject toProto() {
        StorageObject protoObject = id.toStorageObject();
        if (null != accessControlList) {
            protoObject.setAcl(Lists.transform(accessControlList, new Function<AccessControlEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != deletionTime) {
            protoObject.setTimeDeleted(new DateTime(deletionTime));
        }
        if (null != lastUpdatedTime) {
            protoObject.setUpdated(new DateTime(lastUpdatedTime));
        }
        if (null != creationTime) {
            protoObject.setTimeCreated(new DateTime(creationTime));
        }
        if (null != sizeBytes) {
            protoObject.setSize(BigInteger.valueOf(sizeBytes));
        }
        if (null != entityPrincipal) {
            protoObject.setOwner(new Owner().setEntity(entityPrincipal.toProto()));
        }
        if (null != storageTier) {
            protoObject.setStorageClass(storageTier.toString());
        }
        Map<String, String> protoMetadata = metaMap;
        if (null != metaMap && !Data.isNull(metaMap)) {
            protoMetadata = Maps.newHashMapWithExpectedSize(metaMap.size());
            for (Map.Entry<String, String> mapEntry : metaMap.entrySet()) {
                protoMetadata.put(mapEntry.getKey(), firstNonNull(mapEntry.getValue(), Data.<String>nullOf(String.class)));
            }
        }
        if (null != encryptionSettings) {
            protoObject.setCustomerEncryption(encryptionSettings.toProto());
        }
        if (null != retentionExpiryTime) {
            protoObject.setRetentionExpirationTime(new DateTime(retentionExpiryTime));
        }
        protoObject.setKmsKeyName(kmsKeyId);
        protoObject.setEventBasedHold(eventHold);
        protoObject.setTemporaryHold(tempHold);
        protoObject.setMetadata(protoMetadata);
        protoObject.setCacheControl(cacheDirective);
        protoObject.setContentEncoding(encoding);
        protoObject.setCrc32c(crcChecksum);
        protoObject.setContentType(mimeType);
        protoObject.setMd5Hash(contentChecksum);
        protoObject.setMediaLink(mediaUrl);
        protoObject.setMetageneration(metaGenerationNumber);
        protoObject.setContentDisposition(disposition);
        protoObject.setComponentCount(componentsCount);
        protoObject.setContentLanguage(language);
        protoObject.setEtag(entityTag);
        protoObject.setId(generatedIdentifier);
        protoObject.setSelfLink(selfUri);
        return protoObject;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static CloudStorageObjectBuilder newBuilder(BucketMetadata bucketMetadata, String objectName) {
        return newBuilder(bucketMetadata.getName(), objectName);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static CloudStorageObjectBuilder newBuilder(String bucketName, String objectName) {
        return newBuilder(BlobIdentifier.create(bucketName, objectName));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static CloudStorageObjectBuilder newBuilder(BucketMetadata bucketMetadata, String objectName, Long generationId) {
        return newBuilder(bucketMetadata.getName(), objectName, generationId);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static CloudStorageObjectBuilder newBuilder(String bucketName, String objectName, Long generationId) {
        return newBuilder(BlobIdentifier.create(bucketName, objectName, generationId));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided value.
     */
    public static CloudStorageObjectBuilder newBuilder(BlobIdentifier id) {
        return new BlobInfoBuilderImpl(id);
    }

    static BlobMetadata fromProto(StorageObject protoObject) {
        CloudStorageObjectBuilder blobInfoBuilder = newBuilder(BlobIdentifier.fromStorageObject(protoObject));
        if (null != protoObject.getCacheControl()) {
            blobInfoBuilder.setCacheControl(protoObject.getCacheControl());
        }
        if (null != protoObject.getContentEncoding()) {
            blobInfoBuilder.setContentEncoding(protoObject.getContentEncoding());
        }
        if (null != protoObject.getCrc32c()) {
            blobInfoBuilder.setCrc32c(protoObject.getCrc32c());
        }
        if (null != protoObject.getContentType()) {
            blobInfoBuilder.setContentType(protoObject.getContentType());
        }
        if (null != protoObject.getMd5Hash()) {
            blobInfoBuilder.setMd5(protoObject.getMd5Hash());
        }
        if (null != protoObject.getMediaLink()) {
            blobInfoBuilder.setMediaLink(protoObject.getMediaLink());
        }
        if (null != protoObject.getMetageneration()) {
            blobInfoBuilder.setMetageneration(protoObject.getMetageneration());
        }
        if (null != protoObject.getContentDisposition()) {
            blobInfoBuilder.setContentDisposition(protoObject.getContentDisposition());
        }
        if (null != protoObject.getComponentCount()) {
            blobInfoBuilder.setComponentCount(protoObject.getComponentCount());
        }
        if (null != protoObject.getContentLanguage()) {
            blobInfoBuilder.setContentLanguage(protoObject.getContentLanguage());
        }
        if (null != protoObject.getEtag()) {
            blobInfoBuilder.setEtag(protoObject.getEtag());
        }
        if (null != protoObject.getId()) {
            blobInfoBuilder.setGeneratedId(protoObject.getId());
        }
        if (null != protoObject.getSelfLink()) {
            blobInfoBuilder.setSelfLink(protoObject.getSelfLink());
        }
        if (null != protoObject.getMetadata()) {
            blobInfoBuilder.setMetadata(protoObject.getMetadata());
        }
        if (null != protoObject.getTimeDeleted()) {
            blobInfoBuilder.setDeleteTime(protoObject.getTimeDeleted().getValue());
        }
        if (null != protoObject.getUpdated()) {
            blobInfoBuilder.setUpdateTime(protoObject.getUpdated().getValue());
        }
        if (null != protoObject.getTimeCreated()) {
            blobInfoBuilder.setCreateTime(protoObject.getTimeCreated().getValue());
        }
        if (null != protoObject.getSize()) {
            blobInfoBuilder.setSize(protoObject.getSize().longValue());
        }
        if (null != protoObject.getOwner()) {
            blobInfoBuilder.setOwner(AccessControlEntry.AbstractEntity.fromProto(protoObject.getOwner().getEntity()));
        }
        if (null != protoObject.getAcl()) {
            blobInfoBuilder.setAcl(Lists.transform(protoObject.getAcl(), new Function<ObjectAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                    return AccessControlEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (protoObject.containsKey("isDirectory")) {
            blobInfoBuilder.setIsDirectory(Boolean.TRUE);
        }
        if (null != protoObject.getCustomerEncryption()) {
            blobInfoBuilder.setCustomerEncryption(CustomerEncryptionSettings.fromProto(protoObject.getCustomerEncryption()));
        }
        if (null != protoObject.getStorageClass()) {
            blobInfoBuilder.setStorageClass(StorageClassType.fromValue(protoObject.getStorageClass()));
        }
        if (null != protoObject.getKmsKeyName()) {
            blobInfoBuilder.setKmsKeyName(protoObject.getKmsKeyName());
        }
        if (null != protoObject.getEventBasedHold()) {
            blobInfoBuilder.setEventBasedHold(protoObject.getEventBasedHold());
        }
        if (null != protoObject.getTemporaryHold()) {
            blobInfoBuilder.setTemporaryHold(protoObject.getTemporaryHold());
        }
        if (null != protoObject.getRetentionExpirationTime()) {
            blobInfoBuilder.setRetentionExpirationTime(protoObject.getRetentionExpirationTime().getValue());
        }
        return blobInfoBuilder.buildObject();
    }
}
