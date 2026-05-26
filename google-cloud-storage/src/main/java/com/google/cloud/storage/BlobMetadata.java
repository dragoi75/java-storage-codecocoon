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

    static final Function<BlobMetadata, StorageObject> INFO_TO_PB_MAPPER = new Function<BlobMetadata, StorageObject>() {

        @Override
        public StorageObject apply(BlobMetadata blobInfo) {
            return blobInfo.toProto();
        }
    };

    private static final long serialVersionUID = -5625857076205028976L;

    private final BlobIdentifier blobIdentifier;

    private final String generatedIdentifier;

    private final String selfUrl;

    private final String cacheControlHeader;

    private final List<AccessControlEntry> accessControlList;

    private final AccessControlEntry.TypedEntity principalEntity;

    private final Long contentLength;

    private final String entityTag;

    private final String contentDigest;

    private final String crcChecksum;

    private final String mediaUrl;

    private final Map<String, String> customAttributes;

    private final Long metaVersion;

    private final Long deletionTimestamp;

    private final Long lastUpdateTimestamp;

    private final Long creationTimestamp;

    private final String mimeType;

    private final String encodingType;

    private final String dispositionType;

    private final String contentLocale;

    private final StorageTier storageTier;

    private final Integer partsCount;

    private final boolean directoryFlag;

    private final CustomerEncryptionInfo encryptionInfo;

    private final String kmsKey;

    private final Boolean eventHold;

    private final Boolean tempHold;

    private final Long retentionExpiry;

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

        private final String algorithmName;

        private final String keyDigest;

        StorageObject.CustomerEncryption toProto() {
            return new StorageObject.CustomerEncryption().setEncryptionAlgorithm(algorithmName).setKeySha256(keyDigest);
        }

        @Override
        public final int hashCode() {
            return Objects.hash(algorithmName, keyDigest);
        }

        static CustomerEncryptionInfo fromProto(StorageObject.CustomerEncryption encryptionProto) {
            return new CustomerEncryptionInfo(encryptionProto.getEncryptionAlgorithm(), encryptionProto.getKeySha256());
        }

        /**
         * Returns the algorithm used to encrypt the blob.
         */
        public String getEncryptionAlgorithm() {
            return algorithmName;
        }

        @Override
        public final boolean equals(Object other) {
            return this == other || null != other && other.getClass().equals(CustomerEncryptionInfo.class) && Objects.equals(toProto(), ((CustomerEncryptionInfo) other).toProto());
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("encryptionAlgorithm", getEncryptionAlgorithm()).add("keySha256", getKeySha256()).toString();
        }

        /**
         * Returns the SHA256 hash of the encryption key.
         */
        public String getKeySha256() {
            return keyDigest;
        }

        CustomerEncryptionInfo(String algorithmName, String keyDigest) {
            this.algorithmName = algorithmName;
            this.keyDigest = keyDigest;
        }

    }

    /**
     * Builder for {@code BlobInfo}.
     */
    public abstract static class StorageObjectBuilder {

        @BetaApi
        abstract StorageObjectBuilder setRetentionExpirationTime(Long retentionExpirationTime);

        abstract StorageObjectBuilder setSelfLink(String selfLink);

        /**
         * Sets the MD5 hash of blob's data from hex string.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract StorageObjectBuilder setMd5FromHexString(String md5HexString);

        abstract StorageObjectBuilder setUpdateTime(Long updateTime);

        abstract StorageObjectBuilder setComponentCount(Integer componentCount);

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
         * Creates a {@code BlobInfo} object.
         */
        public abstract BlobMetadata buildStorageObject();

        /**
         * Sets the blob's user provided metadata.
         */
        public abstract StorageObjectBuilder setMetadata(Map<String, String> metadata);

        /**
         * Sets the blob's event-based hold.
         */
        @BetaApi
        public abstract StorageObjectBuilder setEventBasedHold(Boolean eventBasedHold);

        abstract StorageObjectBuilder setSize(Long size);

        abstract StorageObjectBuilder setIsDirectory(boolean isDirectory);

        abstract StorageObjectBuilder setCustomerEncryption(CustomerEncryptionInfo customerEncryption);

        /**
         * Sets the MD5 hash of blob's data. MD5 value must be encoded in base64.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract StorageObjectBuilder setMd5(String md5);

        /**
         * Sets the blob's storage class.
         */
        public abstract StorageObjectBuilder setStorageClass(StorageTier storageClass);

        /**
         * Sets the CRC32C checksum of blob's data as described in <a
         * href="http://tools.ietf.org/html/rfc4960#appendix-B">RFC 4960, Appendix B;</a> encoded in
         * base64 in big-endian order.
         *
         * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
         *     Best Practices</a>
         */
        public abstract StorageObjectBuilder setCrc32c(String crc32c);

        abstract StorageObjectBuilder setMetageneration(Long metageneration);

        /**
         * Sets the blob's data content type.
         *
         * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.17">Content-Type</a>
         */
        public abstract StorageObjectBuilder setContentType(String contentType);

        /**
         * Sets the blob's temporary hold.
         */
        @BetaApi
        public abstract StorageObjectBuilder setTemporaryHold(Boolean temporaryHold);

        /**
         * Sets the blob identity.
         */
        public abstract StorageObjectBuilder setBlobId(BlobIdentifier blobId);

        abstract StorageObjectBuilder setGeneratedId(String generatedId);

        abstract StorageObjectBuilder setKmsKeyName(String kmsKeyName);

        /**
         * Sets the blob's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract StorageObjectBuilder setAcl(List<AccessControlEntry> acl);

        abstract StorageObjectBuilder setDeleteTime(Long deleteTime);

        /**
         * Sets the blob's data content language.
         *
         * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
         */
        public abstract StorageObjectBuilder setContentLanguage(String contentLanguage);

        abstract StorageObjectBuilder setEtag(String etag);

        abstract StorageObjectBuilder setOwner(AccessControlEntry.TypedEntity owner);

        /**
         * Sets the blob's data cache control.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">Cache-Control</a>
         */
        public abstract StorageObjectBuilder setCacheControl(String cacheControl);

        abstract StorageObjectBuilder setCreateTime(Long createTime);

        /**
         * Sets the blob's data content disposition.
         *
         * @see <a href="https://tools.ietf.org/html/rfc6266">Content-Disposition</a>
         */
        public abstract StorageObjectBuilder setContentDisposition(String contentDisposition);

        /**
         * Sets the blob's data content encoding.
         *
         * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
         */
        public abstract StorageObjectBuilder setContentEncoding(String contentEncoding);

    }

    static final class BlobInfoBuilderImpl extends StorageObjectBuilder {

        private BlobIdentifier blobIdentifier;

        private String generatedIdentifier;

        private String mimeType;

        private String encodingType;

        private String dispositionType;

        private String contentLocale;

        private Integer partsCount;

        private String cacheControlHeader;

        private List<AccessControlEntry> accessControlList;

        private AccessControlEntry.TypedEntity principalEntity;

        private Long contentLength;

        private String entityTag;

        private String selfUrl;

        private String contentDigest;

        private String crcChecksum;

        private String mediaUrl;

        private Map<String, String> customAttributes;

        private Long metaVersion;

        private Long deletionTimestamp;

        private Long lastUpdateTimestamp;

        private Long creationTimestamp;

        private Boolean directoryFlag;

        private CustomerEncryptionInfo encryptionInfo;

        private StorageTier storageTier;

        private String kmsKey;

        private Boolean eventHold;

        private Boolean tempHold;

        private Long retentionExpiry;

        @Override
        BlobMetadata.StorageObjectBuilder setUpdateTime(Long lastUpdateTimestamp) {
            this.lastUpdateTimestamp = lastUpdateTimestamp;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setMetadata(Map<String, String> customAttributes) {
            if (null == customAttributes) {
                this.customAttributes = (Map<String, String>) Data.nullOf(UnmodifiableEmptyMap.class);
            } else {
                this.customAttributes = new HashMap<>(customAttributes);
            }
            return this;
        }

        BlobInfoBuilderImpl(BlobMetadata blobMetadata) {
            blobIdentifier = blobMetadata.blobIdentifier;
            generatedIdentifier = blobMetadata.generatedIdentifier;
            cacheControlHeader = blobMetadata.cacheControlHeader;
            encodingType = blobMetadata.encodingType;
            mimeType = blobMetadata.mimeType;
            dispositionType = blobMetadata.dispositionType;
            contentLocale = blobMetadata.contentLocale;
            partsCount = blobMetadata.partsCount;
            encryptionInfo = blobMetadata.encryptionInfo;
            accessControlList = blobMetadata.accessControlList;
            principalEntity = blobMetadata.principalEntity;
            contentLength = blobMetadata.contentLength;
            entityTag = blobMetadata.entityTag;
            selfUrl = blobMetadata.selfUrl;
            contentDigest = blobMetadata.contentDigest;
            crcChecksum = blobMetadata.crcChecksum;
            mediaUrl = blobMetadata.mediaUrl;
            customAttributes = blobMetadata.customAttributes;
            metaVersion = blobMetadata.metaVersion;
            deletionTimestamp = blobMetadata.deletionTimestamp;
            lastUpdateTimestamp = blobMetadata.lastUpdateTimestamp;
            creationTimestamp = blobMetadata.creationTimestamp;
            directoryFlag = blobMetadata.directoryFlag;
            storageTier = blobMetadata.storageTier;
            kmsKey = blobMetadata.kmsKey;
            eventHold = blobMetadata.eventHold;
            tempHold = blobMetadata.tempHold;
            retentionExpiry = blobMetadata.retentionExpiry;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentLanguage(String contentLocale) {
            this.contentLocale = firstNonNull(contentLocale, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setCreateTime(Long creationTimestamp) {
            this.creationTimestamp = creationTimestamp;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setMd5(String contentDigest) {
            this.contentDigest = firstNonNull(contentDigest, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setMediaLink(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setSelfLink(String selfUrl) {
            this.selfUrl = selfUrl;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setRetentionExpirationTime(Long retentionExpiry) {
            this.retentionExpiry = retentionExpiry;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCacheControl(String cacheControlHeader) {
            this.cacheControlHeader = firstNonNull(cacheControlHeader, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setMetageneration(Long metaVersion) {
            this.metaVersion = metaVersion;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCrc32cFromHexString(String crcHexString) {
            if (null == crcHexString) {
                return this;
            }
            byte[] digestBytes = new BigInteger(crcHexString, 16).toByteArray();
            int leadingZeros = digestBytes.length - crcHexString.length() / 2;
            if (0 < leadingZeros) {
                digestBytes = Arrays.copyOfRange(digestBytes, leadingZeros, digestBytes.length);
            }
            this.crcChecksum = BaseEncoding.base64().encode(digestBytes);
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setComponentCount(Integer partsCount) {
            this.partsCount = partsCount;
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
        public BlobMetadata.StorageObjectBuilder setStorageClass(StorageTier storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setAcl(List<AccessControlEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentType(String mimeType) {
            this.mimeType = firstNonNull(mimeType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setDeleteTime(Long deletionTimestamp) {
            this.deletionTimestamp = deletionTimestamp;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setBlobId(BlobIdentifier blobIdentifier) {
            this.blobIdentifier = checkNotNull(blobIdentifier);
            return this;
        }

        @Override
        public BlobMetadata buildStorageObject() {
            checkNotNull(blobIdentifier);
            return new BlobMetadata(this);
        }

        @Override
        BlobMetadata.StorageObjectBuilder setSize(Long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        public StorageObjectBuilder setMd5FromHexString(String digestHexString) {
            if (null == digestHexString) {
                return this;
            }
            byte[] digestBytes = new BigInteger(digestHexString, 16).toByteArray();
            int leadingZeros = digestBytes.length - digestHexString.length() / 2;
            if (0 < leadingZeros) {
                digestBytes = Arrays.copyOfRange(digestBytes, leadingZeros, digestBytes.length);
            }
            this.contentDigest = BaseEncoding.base64().encode(digestBytes);
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setKmsKeyName(String kmsKey) {
            this.kmsKey = kmsKey;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setOwner(AccessControlEntry.TypedEntity principalEntity) {
            this.principalEntity = principalEntity;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setCrc32c(String crcChecksum) {
            this.crcChecksum = firstNonNull(crcChecksum, Data.<String>nullOf(String.class));
            return this;
        }

        BlobInfoBuilderImpl(BlobIdentifier blobIdentifier) {
            this.blobIdentifier = blobIdentifier;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setTemporaryHold(Boolean tempHold) {
            this.tempHold = tempHold;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentEncoding(String encodingType) {
            this.encodingType = firstNonNull(encodingType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setContentDisposition(String dispositionType) {
            this.dispositionType = firstNonNull(dispositionType, Data.<String>nullOf(String.class));
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        BlobMetadata.StorageObjectBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        public BlobMetadata.StorageObjectBuilder setEventBasedHold(Boolean eventHold) {
            this.eventHold = eventHold;
            return this;
        }

    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(BucketMetadata bucketMetadata, String objectId) {
        return newBuilder(bucketMetadata.getName(), objectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blobIdentifier);
    }

    /**
     * Returns the URI of this blob as a string.
     */
    public String getSelfLink() {
        return selfUrl;
    }

    /**
     * Returns the last modification time of the blob's metadata expressed as the number of
     * milliseconds since the Unix epoch.
     */
    public Long getUpdateTime() {
        return lastUpdateTimestamp;
    }

    /**
     * Returns the retention expiration time of the blob as {@code Long}, if a retention period is
     * defined. If retention period is not defined this value returns {@code null}
     */
    @BetaApi
    public Long getRetentionExpirationTime() {
        return Data.<Long>isNull(retentionExpiry) ? null : retentionExpiry;
    }

    /**
     * Returns the content length of the data in bytes.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.13">Content-Length</a>
     */
    public Long getSize() {
        return contentLength;
    }

    /**
     * Returns the blob's owner. This will always be the uploader of the blob.
     */
    public AccessControlEntry.TypedEntity getOwner() {
        return principalEntity;
    }

    /**
     * Returns the service-generated for the blob.
     */
    public String getGeneratedId() {
        return generatedIdentifier;
    }

    StorageObject toProto() {
        StorageObject protoObject = blobIdentifier.toProto();
        if (null != accessControlList) {
            protoObject.setAcl(Lists.transform(accessControlList, new Function<AccessControlEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != deletionTimestamp) {
            protoObject.setTimeDeleted(new DateTime(deletionTimestamp));
        }
        if (null != lastUpdateTimestamp) {
            protoObject.setUpdated(new DateTime(lastUpdateTimestamp));
        }
        if (null != creationTimestamp) {
            protoObject.setTimeCreated(new DateTime(creationTimestamp));
        }
        if (null != contentLength) {
            protoObject.setSize(BigInteger.valueOf(contentLength));
        }
        if (null != principalEntity) {
            protoObject.setOwner(new Owner().setEntity(principalEntity.toProto()));
        }
        if (null != storageTier) {
            protoObject.setStorageClass(storageTier.toString());
        }
        Map<String, String> protoMetadataMap = customAttributes;
        if (null != customAttributes && !Data.isNull(customAttributes)) {
            protoMetadataMap = Maps.newHashMapWithExpectedSize(customAttributes.size());
            for (Map.Entry<String, String> mapPair : customAttributes.entrySet()) {
                protoMetadataMap.put(mapPair.getKey(), firstNonNull(mapPair.getValue(), Data.<String>nullOf(String.class)));
            }
        }
        if (null != encryptionInfo) {
            protoObject.setCustomerEncryption(encryptionInfo.toProto());
        }
        if (null != retentionExpiry) {
            protoObject.setRetentionExpirationTime(new DateTime(retentionExpiry));
        }
        protoObject.setKmsKeyName(kmsKey);
        protoObject.setEventBasedHold(eventHold);
        protoObject.setTemporaryHold(tempHold);
        protoObject.setMetadata(protoMetadataMap);
        protoObject.setCacheControl(cacheControlHeader);
        protoObject.setContentEncoding(encodingType);
        protoObject.setCrc32c(crcChecksum);
        protoObject.setContentType(mimeType);
        protoObject.setMd5Hash(contentDigest);
        protoObject.setMediaLink(mediaUrl);
        protoObject.setMetageneration(metaVersion);
        protoObject.setContentDisposition(dispositionType);
        protoObject.setComponentCount(partsCount);
        protoObject.setContentLanguage(contentLocale);
        protoObject.setEtag(entityTag);
        protoObject.setId(generatedIdentifier);
        protoObject.setSelfLink(selfUrl);
        return protoObject;
    }

    static BlobMetadata fromPb(StorageObject protoObject) {
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
            blobInfoFactory.setOwner(AccessControlEntry.TypedEntity.fromProto(protoObject.getOwner().getEntity()));
        }
        if (null != protoObject.getAcl()) {
            blobInfoFactory.setAcl(Lists.transform(protoObject.getAcl(), new Function<ObjectAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                    return AccessControlEntry.fromProto(objectAccessControl);
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
            blobInfoFactory.setStorageClass(StorageTier.fromValue(protoObject.getStorageClass()));
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
        return blobInfoFactory.buildStorageObject();
    }

    /**
     * Returns blob's metageneration. Used for preconditions and for detecting changes in metadata. A
     * metageneration number is only meaningful in the context of a particular generation of a
     * particular blob.
     */
    public Long getMetageneration() {
        return metaVersion;
    }

    /**
     * Returns the deletion time of the blob expressed as the number of milliseconds since the Unix
     * epoch.
     */
    public Long getDeleteTime() {
        return deletionTimestamp;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided value.
     */
    public static StorageObjectBuilder newBuilder(BlobIdentifier blobIdentifier) {
        return new BlobInfoBuilderImpl(blobIdentifier);
    }

    /**
     * Returns blob's data generation. Used for blob versioning.
     */
    public Long getGeneration() {
        return getBlobId().getGeneration();
    }

    /**
     * Returns blob's user provided metadata.
     */
    public Map<String, String> getMetadata() {
        return null == customAttributes || Data.isNull(customAttributes) ? null : Collections.unmodifiableMap(customAttributes);
    }

    /**
     * Returns the Cloud KMS key used to encrypt the blob, if any.
     */
    public String getKmsKeyName() {
        return kmsKey;
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
        StringBuilder sb = new StringBuilder();
        for (byte currentByte : decodedCrc) {
            sb.append(String.format("%02x", currentByte & 0xff));
        }
        return sb.toString();
    }

    /**
     * Returns the blob's data content encoding.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7231#section-3.1.2.2">Content-Encoding</a>
     */
    public String getContentEncoding() {
        return Data.isNull(encodingType) ? null : encodingType;
    }

    /**
     * Returns the blob's identity.
     */
    public BlobIdentifier getBlobId() {
        return blobIdentifier;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(String containerId, String objectId, Long versionNumber) {
        return newBuilder(BlobIdentifier.create(containerId, objectId, versionNumber));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).add("size", getSize()).add("content-type", getContentType()).add("metadata", getMetadata()).toString();
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
     * StorageClient#update(BlobMetadata, StorageClient.BlobUploadOption...)} in which case the value of event-based
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getEventBasedHold() {
        return Data.<Boolean>isNull(eventHold) ? null : eventHold;
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
     * Returns the blob's access control configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AccessControlEntry> getAcl() {
        return accessControlList;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(BucketMetadata bucketMetadata, String objectId, Long versionNumber) {
        return newBuilder(bucketMetadata.getName(), objectId, versionNumber);
    }

    /**
     * Returns the name of the containing bucket.
     */
    public String getBucket() {
        return getBlobId().getBucket();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || null != other && other.getClass().equals(BlobMetadata.class) && Objects.equals(toProto(), ((BlobMetadata) other).toProto());
    }

    /**
     * Returns the creation time of the blob expressed as the number of milliseconds since the Unix
     * epoch.
     */
    public Long getCreateTime() {
        return creationTimestamp;
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
     * StorageClient#update(BlobMetadata, StorageClient.BlobUploadOption...)} in which case the value of temporary
     * hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getTemporaryHold() {
        return Data.<Boolean>isNull(tempHold) ? null : tempHold;
    }

    /**
     * Returns {@code true} if the current blob represents a directory. This can only happen if the
     * blob is returned by {@link StorageClient#list(String, StorageClient.BlobListOptions...)} when the {@link
     * StorageClient.BlobListOptions#currentDir()} option is used. When this is the case only {@link
     * #getBlobId()} and {@link #getSize()} are set for the current blob: {@link BlobIdentifier#getName()}
     * ends with the '/' character, {@link BlobIdentifier#getGeneration()} returns {@code null} and {@link
     * #getSize()} is {@code 0}.
     */
    public boolean isDirectory() {
        return directoryFlag;
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
     * Returns the storage class of the blob.
     */
    public StorageTier getStorageClass() {
        return storageTier;
    }

    /**
     * Returns information on the customer-supplied encryption key, if the blob is encrypted using
     * such a key.
     */
    public CustomerEncryptionInfo getCustomerEncryption() {
        return encryptionInfo;
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
        return partsCount;
    }

    /**
     * Returns a {@code BlobInfo} builder where blob identity is set using the provided values.
     */
    public static StorageObjectBuilder newBuilder(String containerId, String objectId) {
        return newBuilder(BlobIdentifier.create(containerId, objectId));
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
     * Returns a builder for the current blob.
     */
    public StorageObjectBuilder toBlobInfoBuilder() {
        return new BlobInfoBuilderImpl(this);
    }

    /**
     * Returns the blob's name.
     */
    public String getName() {
        return getBlobId().getName();
    }

    BlobMetadata(BlobInfoBuilderImpl blobInfoFactory) {
        blobIdentifier = blobInfoFactory.blobIdentifier;
        generatedIdentifier = blobInfoFactory.generatedIdentifier;
        cacheControlHeader = blobInfoFactory.cacheControlHeader;
        encodingType = blobInfoFactory.encodingType;
        mimeType = blobInfoFactory.mimeType;
        dispositionType = blobInfoFactory.dispositionType;
        contentLocale = blobInfoFactory.contentLocale;
        partsCount = blobInfoFactory.partsCount;
        encryptionInfo = blobInfoFactory.encryptionInfo;
        accessControlList = blobInfoFactory.accessControlList;
        principalEntity = blobInfoFactory.principalEntity;
        contentLength = blobInfoFactory.contentLength;
        entityTag = blobInfoFactory.entityTag;
        selfUrl = blobInfoFactory.selfUrl;
        contentDigest = blobInfoFactory.contentDigest;
        crcChecksum = blobInfoFactory.crcChecksum;
        mediaUrl = blobInfoFactory.mediaUrl;
        customAttributes = blobInfoFactory.customAttributes;
        metaVersion = blobInfoFactory.metaVersion;
        deletionTimestamp = blobInfoFactory.deletionTimestamp;
        lastUpdateTimestamp = blobInfoFactory.lastUpdateTimestamp;
        creationTimestamp = blobInfoFactory.creationTimestamp;
        directoryFlag = firstNonNull(blobInfoFactory.directoryFlag, Boolean.FALSE);
        storageTier = blobInfoFactory.storageTier;
        kmsKey = blobInfoFactory.kmsKey;
        eventHold = blobInfoFactory.eventHold;
        tempHold = blobInfoFactory.tempHold;
        retentionExpiry = blobInfoFactory.retentionExpiry;
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
        byte[] decodedDigest = BaseEncoding.base64().decode(contentDigest);
        StringBuilder sb = new StringBuilder();
        for (byte currentByte : decodedDigest) {
            sb.append(String.format("%02x", currentByte & 0xff));
        }
        return sb.toString();
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
     * Returns HTTP 1.1 Entity tag for the blob.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return entityTag;
    }

    /**
     * Returns the blob's media download link.
     */
    public String getMediaLink() {
        return mediaUrl;
    }

}
