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

    static final Function<BlobMetadata, StorageObject> INFO_TO_PROTO_FN = new Function<BlobMetadata, StorageObject>() {

        @Override
        public StorageObject apply(BlobMetadata blobInfo) {
            return blobInfo.toProto();
        }
    };

    private static final long serialVersionUID = -5625857076205028976L;

    private final BlobIdentifier blobIdentifier;

    private final String generatedIdentifier;

    private final String resourceLink;

    private final String cacheDirective;

    private final List<AclEntry> accessControlList;

    private final AclEntry.AbstractEntity resourceOwner;

    private final Long contentSize;

    private final String entityTag;

    private final String checksum;

    private final String crcChecksum;

    private final String mediaUrl;

    private final Map<String, String> customAttributes;

    private final Long metaGenerationNumber;

    private final Long deletionTimestamp;

    private final Long lastUpdateTime;

    private final Long creationTime;

    private final String mimeType;

    private final String contentCharset;

    private final String dispositionType;

    private final String language;

    private final StorageClass storageTier;

    private final Integer partsCount;

    private final boolean directoryFlag;

    private final CustomerEncryptionInfo encryptionInfo;

    private final String kmsKeyId;

    private final Boolean eventHold;

    private final Boolean temporaryLock;

    private final Long retentionExpiresAt;

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

        private final String keyFingerprint;

        CustomerEncryptionInfo(String algorithm, String keyFingerprint) {
            this.algorithm = algorithm;
            this.keyFingerprint = keyFingerprint;
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
            return keyFingerprint;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("encryptionAlgorithm", getEncryptionAlgorithm()).add("keySha256", getKeySha256()).toString();
        }

        @Override
        public final int hashCode() {
            return Objects.hash(algorithm, keyFingerprint);
        }

        @Override
        public final boolean equals(Object other) {
            return this == other || null != other && other.getClass().equals(CustomerEncryptionInfo.class) && Objects.equals(toProto(), ((CustomerEncryptionInfo) other).toProto());
        }

        StorageObject.CustomerEncryption toProto() {
            return new StorageObject.CustomerEncryption().setEncryptionAlgorithm(algorithm).setKeySha256(keyFingerprint);
        }

        static CustomerEncryptionInfo fromProto(StorageObject.CustomerEncryption encryptionProto) {
            return new CustomerEncryptionInfo(encryptionProto.getEncryptionAlgorithm(), encryptionProto.getKeySha256());
        }
    }

    /**
     * Builder for {@code BlobInfo}.
     */
    public abstract static class StorageObjectBuilder {

        /**
         * Sets the blob identity.
         */
        public abstract StorageObjectBuilder setBlobId(BlobIdentifier blobId);

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
        public abstract StorageObjectBuilder setAcl(List<AclEntry> acl);

        abstract StorageObjectBuilder setOwner(AclEntry.AbstractEntity owner);

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
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> from hex
         * string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract StorageObjectBuilder setCrc32cFromHexString(String crc32cHexString);

        abstract StorageObjectBuilder setMediaLink(String mediaLink);

        /**
         * Sets the blob's storage class.
         */
        public abstract StorageObjectBuilder setStorageClass(StorageClass storageClass);

        /**
         * Sets the blob's user provided metadata.
         */
        public abstract StorageObjectBuilder setMetadata(Map<String, String> metadata);

        abstract StorageObjectBuilder setMetageneration(Long metageneration);

        abstract StorageObjectBuilder setDeleteTime(Long deleteTime);

        abstract StorageObjectBuilder setUpdateTime(Long updateTime);

        abstract StorageObjectBuilder setCreateTime(Long createTime);

        abstract StorageObjectBuilder setIsDirectory(boolean isDirectory);

        abstract StorageObjectBuilder setCustomerEncryption(CustomerEncryptionInfo customerEncryption);

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

        private BlobIdentifier blobIdentifier;

        private String generatedIdentifier;

        private String mimeType;

        private String contentCharset;

        private String dispositionType;

        private String language;

        private Integer partsCount;

        private String cacheDirective;

        private List<AclEntry> accessControlList;

        private AclEntry.AbstractEntity resourceOwner;

        private Long contentSize;

        private String entityTag;

        private String resourceLink;

        private String checksum;

        private String crcChecksum;

        private String mediaUrl;

        private Map<String, String> customAttributes;

        private Long metaGenerationNumber;

        private Long deletionTimestamp;

        private Long lastUpdateTime;

        private Long creationTime;

        private Boolean directoryFlag;

        private CustomerEncryptionInfo encryptionInfo;

        private StorageClass storageTier;

        private String kmsKeyId;

        private Boolean eventHold;

        private Boolean temporaryLock;

        private Long retentionExpiresAt;

        BlobInfoBuilderImpl(BlobIdentifier blobIdentifier) {
            this.blobIdentifier = blobIdentifier;
        }

        BlobInfoBuilderImpl(BlobMetadata blobMetadata) {
            blobIdentifier = blobMetadata.blobIdentifier;
            generatedIdentifier = blobMetadata.generatedIdentifier;
            cacheDirective = blobMetadata.cacheDirective;
            contentCharset = blobMetadata.contentCharset;
            mimeType = blobMetadata.mimeType;
            dispositionType = blobMetadata.dispositionType;
            language = blobMetadata.language;
            partsCount = blobMetadata.partsCount;
            encryptionInfo = blobMetadata.encryptionInfo;
            accessControlList = blobMetadata.accessControlList;
            resourceOwner = blobMetadata.resourceOwner;
            contentSize = blobMetadata.contentSize;
            entityTag = blobMetadata.entityTag;
            resourceLink = blobMetadata.resourceLink;
            checksum = blobMetadata.checksum;
            crcChecksum = blobMetadata.crcChecksum;
            mediaUrl = blobMetadata.mediaUrl;
            customAttributes = blobMetadata.customAttributes;
            metaGenerationNumber = blobMetadata.metaGenerationNumber;
            deletionTimestamp = blobMetadata.deletionTimestamp;
            lastUpdateTime = blobMetadata.lastUpdateTime;
            creationTime = blobMetadata.creationTime;
            directoryFlag = blobMetadata.directoryFlag;
            storageTier = blobMetadata.storageTier;
            kmsKeyId = blobMetadata.kmsKeyId;
            eventHold = blobMetadata.eventHold;
            temporaryLock = blobMetadata.temporaryLock;
            retentionExpiresAt = blobMetadata.retentionExpiresAt;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setBlobId(BlobIdentifier blobIdentifier) {
            this.blobIdentifier = checkNotNull(blobIdentifier);
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentType(String mimeType) {
            this.mimeType = firstNonNull(mimeType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentDisposition(String dispositionType) {
            this.dispositionType = firstNonNull(dispositionType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentLanguage(String language) {
            this.language = firstNonNull(language, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentEncoding(String contentCharset) {
            this.contentCharset = firstNonNull(contentCharset, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setComponentCount(Integer partsCount) {
            this.partsCount = partsCount;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCacheControl(String cacheDirective) {
            this.cacheDirective = firstNonNull(cacheDirective, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setAcl(List<AclEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setOwner(AclEntry.AbstractEntity resourceOwner) {
            this.resourceOwner = resourceOwner;
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
        BlobMetadata.StorageObjectBuilder setSelfLink(String resourceLink) {
            this.resourceLink = resourceLink;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setMd5(String checksum) {
            this.checksum = firstNonNull(checksum, Data.<String>nullOf(String.class));
            return this;
        }

        public StorageObjectBuilder setMd5FromHexString(String hexDigest) {
            if (null == hexDigest) {
                return this;
            }
            byte[] byteArray = new BigInteger(hexDigest, 16).toByteArray();
            int leadingZeroCount = byteArray.length - hexDigest.length() / 2;
            if (0 < leadingZeroCount) {
                byteArray = Arrays.copyOfRange(byteArray, leadingZeroCount, byteArray.length);
            }
            this.checksum = BaseEncoding.base64().encode(byteArray);
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCrc32c(String crcChecksum) {
            this.crcChecksum = firstNonNull(crcChecksum, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCrc32cFromHexString(String crcHex) {
            if (null == crcHex) {
                return this;
            }
            byte[] byteArray = new BigInteger(crcHex, 16).toByteArray();
            int leadingZeroCount = byteArray.length - crcHex.length() / 2;
            if (0 < leadingZeroCount) {
                byteArray = Arrays.copyOfRange(byteArray, leadingZeroCount, byteArray.length);
            }
            this.crcChecksum = BaseEncoding.base64().encode(byteArray);
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setMediaLink(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setMetadata(Map<String, String> customAttributes) {
            if (null == customAttributes) {
                this.customAttributes = (Map<String, String>) Data.nullOf(EmptyImmutableMap.class);
            } else {
                this.customAttributes = new HashMap<>(customAttributes);
            }
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setStorageClass(StorageClass storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setMetageneration(Long metaGenerationNumber) {
            this.metaGenerationNumber = metaGenerationNumber;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setDeleteTime(Long deletionTimestamp) {
            this.deletionTimestamp = deletionTimestamp;
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
        BlobMetadata.StorageObjectBuilder setCustomerEncryption(CustomerEncryptionInfo encryptionInfo) {
            this.encryptionInfo = encryptionInfo;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setKmsKeyName(String kmsKeyId) {
            this.kmsKeyId = kmsKeyId;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setEventBasedHold(Boolean eventHold) {
            this.eventHold = eventHold;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setTemporaryHold(Boolean temporaryLock) {
            this.temporaryLock = temporaryLock;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setRetentionExpirationTime(Long retentionExpiresAt) {
            this.retentionExpiresAt = retentionExpiresAt;
            return this;
        }

        @Override
        public BlobMetadata buildObject() {
            checkNotNull(blobIdentifier);
            return new BlobMetadata(this);
        }
    }

    BlobMetadata(BlobInfoBuilderImpl blobInfoFactory) {
        blobIdentifier = blobInfoFactory.blobIdentifier;
        generatedIdentifier = blobInfoFactory.generatedIdentifier;
        cacheDirective = blobInfoFactory.cacheDirective;
        contentCharset = blobInfoFactory.contentCharset;
        mimeType = blobInfoFactory.mimeType;
        dispositionType = blobInfoFactory.dispositionType;
        language = blobInfoFactory.language;
        partsCount = blobInfoFactory.partsCount;
        encryptionInfo = blobInfoFactory.encryptionInfo;
        accessControlList = blobInfoFactory.accessControlList;
        resourceOwner = blobInfoFactory.resourceOwner;
        contentSize = blobInfoFactory.contentSize;
        entityTag = blobInfoFactory.entityTag;
        resourceLink = blobInfoFactory.resourceLink;
        checksum = blobInfoFactory.checksum;
        crcChecksum = blobInfoFactory.crcChecksum;
        mediaUrl = blobInfoFactory.mediaUrl;
        customAttributes = blobInfoFactory.customAttributes;
        metaGenerationNumber = blobInfoFactory.metaGenerationNumber;
        deletionTimestamp = blobInfoFactory.deletionTimestamp;
        lastUpdateTime = blobInfoFactory.lastUpdateTime;
        creationTime = blobInfoFactory.creationTime;
        directoryFlag = firstNonNull(blobInfoFactory.directoryFlag, Boolean.FALSE);
        storageTier = blobInfoFactory.storageTier;
        kmsKeyId = blobInfoFactory.kmsKeyId;
        eventHold = blobInfoFactory.eventHold;
        temporaryLock = blobInfoFactory.temporaryLock;
        retentionExpiresAt = blobInfoFactory.retentionExpiresAt;
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
    public List<AclEntry> getAcl() {
        return accessControlList;
    }

    /**
     * Returns the blob's owner. This will always be the uploader of the blob.
     */
    public AclEntry.AbstractEntity getOwner() {
        return resourceOwner;
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
        return Data.isNull(mimeType) ? null : mimeType;
    }

    /**
     * Returns the blob's data content encoding.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
     */
    public String getContentEncoding() {
        return Data.isNull(contentCharset) ? null : contentCharset;
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
     * Returns the blob's data content language.
     *
     * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
     */
    public String getContentLanguage() {
        return Data.isNull(language) ? null : language;
    }

    /**
     * Returns the number of components that make up this blob. Components are accumulated through the
     * {@link StorageService#compose(StorageService.ComposeBlobsRequest)} operation and are limited to a count of 1024,
     * counting 1 for each non-composite component blob and componentCount for each composite
     * component blob. This value is set only for composite blobs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/composite-objects#_Count">Component Count
     *     Property</a>
     */
    public Integer getComponentCount() {
        return partsCount;
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
        return resourceLink;
    }

    /**
     * Returns the MD5 hash of blob's data encoded in base64.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5() {
        return Data.isNull(checksum) ? null : checksum;
    }

    /**
     * Returns the MD5 hash of blob's data decoded to string.
     *
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
     *     Best Practices</a>
     */
    public String getMd5ToHexString() {
        if (null == checksum) {
            return null;
        }
        byte[] hashBytes = BaseEncoding.base64().decode(checksum);
        StringBuilder sb = new StringBuilder();
        for (byte byteVal : hashBytes) {
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
        byte[] crcBytes = BaseEncoding.base64().decode(crcChecksum);
        StringBuilder sb = new StringBuilder();
        for (byte byteVal : crcBytes) {
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
        return null == customAttributes || Data.isNull(customAttributes) ? null : Collections.unmodifiableMap(customAttributes);
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
        return deletionTimestamp;
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
     * Returns {@code true} if the current blob represents a directory. This can only happen if the
     * blob is returned by {@link StorageService#list(String, StorageService.BlobListOptions...)} when the {@link
     * StorageService.BlobListOptions#useCurrentDirectory()} option is used. When this is the case only {@link
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
        return encryptionInfo;
    }

    /**
     * Returns the storage class of the blob.
     */
    public StorageClass getStorageClass() {
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
     * StorageService#get(BlobIdentifier, StorageService.BlobGetOptions...)} and event-based hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BlobMetadataField#EVENT_BASED_HOLD} is selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobGetOptions...)}, but event-based hold for the blob is not
     * enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BlobMetadataField#EVENT_BASED_HOLD} is not selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * StorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
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
     * StorageService#get(BlobIdentifier, StorageService.BlobGetOptions...)} and temporary hold for the blob is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BlobMetadataField#TEMPORARY_HOLD} is selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobGetOptions...)}, but temporary hold for the blob is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BlobMetadataField#TEMPORARY_HOLD} is not selected in a {@link
     * StorageService#get(BlobIdentifier, StorageService.BlobGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} event-based hold is explicitly set to false using in a {@link
     * StorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * StorageService#update(BlobMetadata, StorageService.BlobUploadOption...)} in which case the value of temporary
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getTemporaryHold() {
        return Data.<Boolean>isNull(temporaryLock) ? null : temporaryLock;
    }

    /**
     * Returns the retention expiration time of the blob as {@code Long}, if a retention period is
     * defined. If retention period is not defined this value returns {@code null}
     */
    @BetaApi
    public Long getRetentionExpirationTime() {
        return Data.<Long>isNull(retentionExpiresAt) ? null : retentionExpiresAt;
    }

    /**
     * Returns a builder for the current blob.
     */
    public StorageObjectBuilder toBlobBuilder() {
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

    StorageObject toProto() {
        StorageObject protoObject = blobIdentifier.toProto();
        if (null != accessControlList) {
            protoObject.setAcl(Lists.transform(accessControlList, new Function<AclEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AclEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != deletionTimestamp) {
            protoObject.setTimeDeleted(new DateTime(deletionTimestamp));
        }
        if (null != lastUpdateTime) {
            protoObject.setUpdated(new DateTime(lastUpdateTime));
        }
        if (null != creationTime) {
            protoObject.setTimeCreated(new DateTime(creationTime));
        }
        if (null != contentSize) {
            protoObject.setSize(BigInteger.valueOf(contentSize));
        }
        if (null != resourceOwner) {
            protoObject.setOwner(new Owner().setEntity(resourceOwner.toProto()));
        }
        if (null != storageTier) {
            protoObject.setStorageClass(storageTier.toString());
        }
        Map<String, String> protoMetadata = customAttributes;
        if (null != customAttributes && !Data.isNull(customAttributes)) {
            protoMetadata = Maps.newHashMapWithExpectedSize(customAttributes.size());
            for (Map.Entry<String, String> mapEntry : customAttributes.entrySet()) {
                protoMetadata.put(mapEntry.getKey(), firstNonNull(mapEntry.getValue(), Data.<String>nullOf(String.class)));
            }
        }
        if (null != encryptionInfo) {
            protoObject.setCustomerEncryption(encryptionInfo.toProto());
        }
        if (null != retentionExpiresAt) {
            protoObject.setRetentionExpirationTime(new DateTime(retentionExpiresAt));
        }
        protoObject.setKmsKeyName(kmsKeyId);
        protoObject.setEventBasedHold(eventHold);
        protoObject.setTemporaryHold(temporaryLock);
        protoObject.setMetadata(protoMetadata);
        protoObject.setCacheControl(cacheDirective);
        protoObject.setContentEncoding(contentCharset);
        protoObject.setCrc32c(crcChecksum);
        protoObject.setContentType(mimeType);
        protoObject.setMd5Hash(checksum);
        protoObject.setMediaLink(mediaUrl);
        protoObject.setMetageneration(metaGenerationNumber);
        protoObject.setContentDisposition(dispositionType);
        protoObject.setComponentCount(partsCount);
        protoObject.setContentLanguage(language);
        protoObject.setEtag(entityTag);
        protoObject.setId(generatedIdentifier);
        protoObject.setSelfLink(resourceLink);
        return protoObject;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(BucketMetadata containerMetadata, String objectLabel) {
        return newBuilder(containerMetadata.getName(), objectLabel);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(String containerId, String objectLabel) {
        return newBuilder(BlobIdentifier.create(containerId, objectLabel));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(BucketMetadata containerMetadata, String objectLabel, Long versionNumber) {
        return newBuilder(containerMetadata.getName(), objectLabel, versionNumber);
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(String containerId, String objectLabel, Long versionNumber) {
        return newBuilder(BlobIdentifier.create(containerId, objectLabel, versionNumber));
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided value.
     */
    public static StorageObjectBuilder newBuilder(BlobIdentifier blobIdentifier) {
        return new BlobInfoBuilderImpl(blobIdentifier);
    }

    static BlobMetadata fromProto(StorageObject protoObject) {
        StorageObjectBuilder blobInfoFactory = newBuilder(BlobIdentifier.fromProto(protoObject));
        if (null != protoObject.getCacheControl()) {
            blobInfoFactory.setCacheControl(protoObject.getCacheControl());
        }
        if (null != protoObject.getContentEncoding()) {
            blobInfoFactory.setContentEncoding(protoObject.getContentEncoding());
        }
        if (null != protoObject.getCrc32c()) {
            blobInfoFactory.setCrc32c(protoObject.getCrc32c());
        }
        if (null != protoObject.getContentType()) {
            blobInfoFactory.setContentType(protoObject.getContentType());
        }
        if (null != protoObject.getMd5Hash()) {
            blobInfoFactory.setMd5(protoObject.getMd5Hash());
        }
        if (null != protoObject.getMediaLink()) {
            blobInfoFactory.setMediaLink(protoObject.getMediaLink());
        }
        if (null != protoObject.getMetageneration()) {
            blobInfoFactory.setMetageneration(protoObject.getMetageneration());
        }
        if (null != protoObject.getContentDisposition()) {
            blobInfoFactory.setContentDisposition(protoObject.getContentDisposition());
        }
        if (null != protoObject.getComponentCount()) {
            blobInfoFactory.setComponentCount(protoObject.getComponentCount());
        }
        if (null != protoObject.getContentLanguage()) {
            blobInfoFactory.setContentLanguage(protoObject.getContentLanguage());
        }
        if (null != protoObject.getEtag()) {
            blobInfoFactory.setEtag(protoObject.getEtag());
        }
        if (null != protoObject.getId()) {
            blobInfoFactory.setGeneratedId(protoObject.getId());
        }
        if (null != protoObject.getSelfLink()) {
            blobInfoFactory.setSelfLink(protoObject.getSelfLink());
        }
        if (null != protoObject.getMetadata()) {
            blobInfoFactory.setMetadata(protoObject.getMetadata());
        }
        if (null != protoObject.getTimeDeleted()) {
            blobInfoFactory.setDeleteTime(protoObject.getTimeDeleted().getValue());
        }
        if (null != protoObject.getUpdated()) {
            blobInfoFactory.setUpdateTime(protoObject.getUpdated().getValue());
        }
        if (null != protoObject.getTimeCreated()) {
            blobInfoFactory.setCreateTime(protoObject.getTimeCreated().getValue());
        }
        if (null != protoObject.getSize()) {
            blobInfoFactory.setSize(protoObject.getSize().longValue());
        }
        if (null != protoObject.getOwner()) {
            blobInfoFactory.setOwner(AclEntry.AbstractEntity.fromProto(protoObject.getOwner().getEntity()));
        }
        if (null != protoObject.getAcl()) {
            blobInfoFactory.setAcl(Lists.transform(protoObject.getAcl(), new Function<ObjectAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(ObjectAccessControl objectAccessControl) {
                    return AclEntry.fromObjectPb(objectAccessControl);
                }
            }));
        }
        if (protoObject.containsKey("isDirectory")) {
            blobInfoFactory.setIsDirectory(Boolean.TRUE);
        }
        if (null != protoObject.getCustomerEncryption()) {
            blobInfoFactory.setCustomerEncryption(CustomerEncryptionInfo.fromProto(protoObject.getCustomerEncryption()));
        }
        if (null != protoObject.getStorageClass()) {
            blobInfoFactory.setStorageClass(StorageClass.valueOf(protoObject.getStorageClass()));
        }
        if (null != protoObject.getKmsKeyName()) {
            blobInfoFactory.setKmsKeyName(protoObject.getKmsKeyName());
        }
        if (null != protoObject.getEventBasedHold()) {
            blobInfoFactory.setEventBasedHold(protoObject.getEventBasedHold());
        }
        if (null != protoObject.getTemporaryHold()) {
            blobInfoFactory.setTemporaryHold(protoObject.getTemporaryHold());
        }
        if (null != protoObject.getRetentionExpirationTime()) {
            blobInfoFactory.setRetentionExpirationTime(protoObject.getRetentionExpirationTime().getValue());
        }
        return blobInfoFactory.buildObject();
    }
}
