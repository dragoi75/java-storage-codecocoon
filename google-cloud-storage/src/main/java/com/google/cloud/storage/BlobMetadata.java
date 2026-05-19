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
 * deal with existing Storage objects the API includes the {@link StorageBlob} class which extends {@code
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

  static final Function<BlobMetadata, StorageObject> METADATA_TO_PB_FUNCTION =
      new Function<BlobMetadata, StorageObject>() {
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
  private final List<AccessControlEntry> accessControlList;
  private final AccessControlEntry.TypedEntity ownerEntity;
  private final Long contentSize;
  private final String entityTag;
  private final String md5Digest;
  private final String crc32cChecksum;
  private final Long customTimestamp;
  private final String mediaUrl;
  private final Map<String, String> customMeta;
  private final Long metaGeneration;
  private final Long deletionTime;
  private final Long lastUpdatedTime;
  private final Long creationTime;
  private final String mimeType;
  private final String contentEncodingType;
  private final String dispositionHeader;
  private final String languageTag;
  private final StorageTier storageTier;
  private final Long storageClassUpdateTime;
  private final Integer partsCount;
  private final boolean directoryFlag;
  private final CustomerEncryptionInfo encryptionInfo;
  private final String kmsKeyId;
  private final Boolean eventHoldFlag;
  private final Boolean tempHoldFlag;
  private final Long retentionExpiryTime;

  /** This class is meant for internal use only. Users are discouraged from using this class. */
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
    private final String keySha256Hash;

    CustomerEncryptionInfo(String algorithm, String keySha256Hash) {
      this.algorithm = algorithm;
      this.keySha256Hash = keySha256Hash;
    }

    /** Returns the algorithm used to encrypt the blob. */
    public String getEncryptionAlgorithm() {
      return algorithm;
    }

    /** Returns the SHA256 hash of the encryption key. */
    public String getKeySha256() {
      return keySha256Hash;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("encryptionAlgorithm", getEncryptionAlgorithm())
          .add("keySha256", getKeySha256())
          .toString();
    }

    @Override
    public final int hashCode() {
      return Objects.hash(algorithm, keySha256Hash);
    }

    @Override
    public final boolean equals(Object other) {
      return other == this
          || other != null
              && other.getClass().equals(CustomerEncryptionInfo.class)
              && Objects.equals(toProto(), ((CustomerEncryptionInfo) other).toProto());
    }

    StorageObject.CustomerEncryption toProto() {
      return new StorageObject.CustomerEncryption()
          .setEncryptionAlgorithm(algorithm)
          .setKeySha256(keySha256Hash);
    }

    static CustomerEncryptionInfo fromProto(StorageObject.CustomerEncryption customerEncryptionProto) {
      return new CustomerEncryptionInfo(
          customerEncryptionProto.getEncryptionAlgorithm(), customerEncryptionProto.getKeySha256());
    }
  }

  /** Builder for {@code BlobInfo}. */
  public abstract static class StorageObjectBuilder {

    /** Sets the blob identity. */
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
    public abstract StorageObjectBuilder setAcl(List<AccessControlEntry> acl);

    abstract StorageObjectBuilder setOwner(AccessControlEntry.TypedEntity owner);

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
      throw new UnsupportedOperationException(
          "Override setCustomTime with your own implementation,"
              + " or use com.google.cloud.storage.Blob.");
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

    /** Sets the blob's storage class. */
    public abstract StorageObjectBuilder setStorageClass(StorageTier storageClass);

    /**
     * Sets the modification time of an object's storage class. Once set it can't be unset directly,
     * the only way is to rewrite the object with the desired storage class.
     */
    public StorageObjectBuilder setTimeStorageClassUpdated(Long timeStorageClassUpdated) {
      throw new UnsupportedOperationException(
          "Override setTimeStorageClassUpdated with your own implementation,"
              + " or use com.google.cloud.storage.Blob.");
    }

    /** Sets the blob's user provided metadata. */
    public abstract StorageObjectBuilder setMetadata(Map<String, String> metadata);

    abstract StorageObjectBuilder setMetageneration(Long metageneration);

    abstract StorageObjectBuilder setDeleteTime(Long deleteTime);

    abstract StorageObjectBuilder setUpdateTime(Long updateTime);

    abstract StorageObjectBuilder setCreateTime(Long createTime);

    abstract StorageObjectBuilder setIsDirectory(boolean isDirectory);

    abstract StorageObjectBuilder setCustomerEncryption(CustomerEncryptionInfo customerEncryption);

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

    /** Sets the blob's event-based hold. */
    @BetaApi
    public abstract StorageObjectBuilder setEventBasedHold(Boolean eventBasedHold);

    /** Sets the blob's temporary hold. */
    @BetaApi
    public abstract StorageObjectBuilder setTemporaryHold(Boolean temporaryHold);

    @BetaApi
    abstract StorageObjectBuilder setRetentionExpirationTime(Long retentionExpirationTime);

    /** Creates a {@code BlobInfo} object. */
    public abstract BlobMetadata buildObject();
  }

  static final class BlobInfoBuilderImpl extends StorageObjectBuilder {
    private final String hexValues = "0123456789abcdef";
    private BlobIdentifier blobIdentifier;
    private String generatedIdentifier;
    private String mimeType;
    private String contentEncodingType;
    private String dispositionHeader;
    private String languageTag;
    private Integer partsCount;
    private String cacheDirective;
    private List<AccessControlEntry> accessControlList;
    private AccessControlEntry.TypedEntity ownerEntity;
    private Long contentSize;
    private String entityTag;
    private String resourceLink;
    private String md5Digest;
    private String crc32cChecksum;
    private Long customTimestamp;
    private String mediaUrl;
    private Map<String, String> customMeta;
    private Long metaGeneration;
    private Long deletionTime;
    private Long lastUpdatedTime;
    private Long creationTime;
    private Boolean directoryFlag;
    private CustomerEncryptionInfo encryptionInfo;
    private StorageTier storageTier;
    private Long storageClassUpdateTime;
    private String kmsKeyId;
    private Boolean eventHoldFlag;
    private Boolean tempHoldFlag;
    private Long retentionExpiryTime;

    BlobInfoBuilderImpl(BlobIdentifier blobIdentifier) {
      this.blobIdentifier = blobIdentifier;
    }

    BlobInfoBuilderImpl(BlobMetadata blobMetadata) {
      blobIdentifier = blobMetadata.blobIdentifier;
      generatedIdentifier = blobMetadata.generatedIdentifier;
      cacheDirective = blobMetadata.cacheDirective;
      contentEncodingType = blobMetadata.contentEncodingType;
      mimeType = blobMetadata.mimeType;
      dispositionHeader = blobMetadata.dispositionHeader;
      languageTag = blobMetadata.languageTag;
      partsCount = blobMetadata.partsCount;
      encryptionInfo = blobMetadata.encryptionInfo;
      accessControlList = blobMetadata.accessControlList;
      ownerEntity = blobMetadata.ownerEntity;
      contentSize = blobMetadata.contentSize;
      entityTag = blobMetadata.entityTag;
      resourceLink = blobMetadata.resourceLink;
      md5Digest = blobMetadata.md5Digest;
      crc32cChecksum = blobMetadata.crc32cChecksum;
      customTimestamp = blobMetadata.customTimestamp;
      mediaUrl = blobMetadata.mediaUrl;
      customMeta = blobMetadata.customMeta;
      metaGeneration = blobMetadata.metaGeneration;
      deletionTime = blobMetadata.deletionTime;
      lastUpdatedTime = blobMetadata.lastUpdatedTime;
      creationTime = blobMetadata.creationTime;
      directoryFlag = blobMetadata.directoryFlag;
      storageTier = blobMetadata.storageTier;
      storageClassUpdateTime = blobMetadata.storageClassUpdateTime;
      kmsKeyId = blobMetadata.kmsKeyId;
      eventHoldFlag = blobMetadata.eventHoldFlag;
      tempHoldFlag = blobMetadata.tempHoldFlag;
      retentionExpiryTime = blobMetadata.retentionExpiryTime;
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
    public BlobMetadata.StorageObjectBuilder setContentDisposition(String dispositionHeader) {
      this.dispositionHeader = firstNonNull(dispositionHeader, Data.<String>nullOf(String.class));
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setContentLanguage(String languageTag) {
      this.languageTag = firstNonNull(languageTag, Data.<String>nullOf(String.class));
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setContentEncoding(String contentEncodingType) {
      this.contentEncodingType = firstNonNull(contentEncodingType, Data.<String>nullOf(String.class));
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
    public BlobMetadata.StorageObjectBuilder setAcl(List<AccessControlEntry> accessControlList) {
      this.accessControlList = accessControlList != null ? ImmutableList.copyOf(accessControlList) : null;
      return this;
    }

    @Override
    BlobMetadata.StorageObjectBuilder setOwner(AccessControlEntry.TypedEntity ownerEntity) {
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
    BlobMetadata.StorageObjectBuilder setSelfLink(String resourceLink) {
      this.resourceLink = resourceLink;
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setMd5(String md5Digest) {
      this.md5Digest = firstNonNull(md5Digest, Data.<String>nullOf(String.class));
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setMd5FromHexString(String md5Hex) {
      if (md5Hex == null) {
        return this;
      }
      if (md5Hex.length() % 2 != 0) {
        throw new IllegalArgumentException(
            "each byte must be represented by 2 valid hexadecimal characters");
      }
      String md5HexLower = md5Hex.toLowerCase();
      ByteBuffer md5Buffer = ByteBuffer.allocate(md5HexLower.length() / 2);
      for (int charPos = 0; charPos < md5HexLower.length(); charPos += 2) {
        int highBits = this.hexValues.indexOf(md5HexLower.charAt(charPos));
        int lowBits = this.hexValues.indexOf(md5HexLower.charAt(charPos + 1));
        if (highBits == -1 || lowBits == -1) {
          throw new IllegalArgumentException(
              "each byte must be represented by 2 valid hexadecimal characters");
        }
        md5Buffer.put((byte) (highBits << 4 | lowBits));
      }
      this.md5Digest = BaseEncoding.base64().encode(md5Buffer.array());
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setCrc32c(String crc32cChecksum) {
      this.crc32cChecksum = firstNonNull(crc32cChecksum, Data.<String>nullOf(String.class));
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setCustomTime(Long customTimestamp) {
      this.customTimestamp = customTimestamp;
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setCrc32cFromHexString(String crc32cHex) {
      if (crc32cHex == null) {
        return this;
      }
      if (crc32cHex.length() % 2 != 0) {
        throw new IllegalArgumentException(
            "each byte must be represented by 2 valid hexadecimal characters");
      }
      String crc32cHexLower = crc32cHex.toLowerCase();
      ByteBuffer crc32cBuffer = ByteBuffer.allocate(crc32cHexLower.length() / 2);
      for (int charPos = 0; charPos < crc32cHexLower.length(); charPos += 2) {
        int highBits = this.hexValues.indexOf(crc32cHexLower.charAt(charPos));
        int lowBits =
            this.hexValues.indexOf(crc32cHexLower.charAt(charPos + 1));
        if (highBits == -1 || lowBits == -1) {
          throw new IllegalArgumentException(
              "each byte must be represented by 2 valid hexadecimal characters");
        }
        crc32cBuffer.put((byte) (highBits << 4 | lowBits));
      }
      this.crc32cChecksum = BaseEncoding.base64().encode(crc32cBuffer.array());
      return this;
    }

    @Override
    BlobMetadata.StorageObjectBuilder setMediaLink(String mediaUrl) {
      this.mediaUrl = mediaUrl;
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setMetadata(Map<String, String> customMeta) {
      if (customMeta != null) {
        this.customMeta = new HashMap<>(customMeta);
      } else {
        this.customMeta = (Map<String, String>) Data.nullOf(EmptyImmutableMap.class);
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
    BlobMetadata.StorageObjectBuilder setUpdateTime(Long lastUpdatedTime) {
      this.lastUpdatedTime = lastUpdatedTime;
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
    public BlobMetadata.StorageObjectBuilder setEventBasedHold(Boolean eventHoldFlag) {
      this.eventHoldFlag = eventHoldFlag;
      return this;
    }

    @Override
    public BlobMetadata.StorageObjectBuilder setTemporaryHold(Boolean tempHoldFlag) {
      this.tempHoldFlag = tempHoldFlag;
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
    cacheDirective = blobBuilder.cacheDirective;
    contentEncodingType = blobBuilder.contentEncodingType;
    mimeType = blobBuilder.mimeType;
    dispositionHeader = blobBuilder.dispositionHeader;
    languageTag = blobBuilder.languageTag;
    partsCount = blobBuilder.partsCount;
    encryptionInfo = blobBuilder.encryptionInfo;
    accessControlList = blobBuilder.accessControlList;
    ownerEntity = blobBuilder.ownerEntity;
    contentSize = blobBuilder.contentSize;
    entityTag = blobBuilder.entityTag;
    resourceLink = blobBuilder.resourceLink;
    md5Digest = blobBuilder.md5Digest;
    crc32cChecksum = blobBuilder.crc32cChecksum;
    customTimestamp = blobBuilder.customTimestamp;
    mediaUrl = blobBuilder.mediaUrl;
    customMeta = blobBuilder.customMeta;
    metaGeneration = blobBuilder.metaGeneration;
    deletionTime = blobBuilder.deletionTime;
    lastUpdatedTime = blobBuilder.lastUpdatedTime;
    creationTime = blobBuilder.creationTime;
    directoryFlag = firstNonNull(blobBuilder.directoryFlag, Boolean.FALSE);
    storageTier = blobBuilder.storageTier;
    storageClassUpdateTime = blobBuilder.storageClassUpdateTime;
    kmsKeyId = blobBuilder.kmsKeyId;
    eventHoldFlag = blobBuilder.eventHoldFlag;
    tempHoldFlag = blobBuilder.tempHoldFlag;
    retentionExpiryTime = blobBuilder.retentionExpiryTime;
  }

  /** Returns the blob's identity. */
  public BlobIdentifier getBlobId() {
    return blobIdentifier;
  }

  /** Returns the name of the containing bucket. */
  public String getBucket() {
    return getBlobId().getBucket();
  }

  /** Returns the service-generated for the blob. */
  public String getGeneratedId() {
    return generatedIdentifier;
  }

  /** Returns the blob's name. */
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

  /** Returns the blob's owner. This will always be the uploader of the blob. */
  public AccessControlEntry.TypedEntity getOwner() {
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
    return Data.isNull(mimeType) ? null : mimeType;
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
    return Data.isNull(dispositionHeader) ? null : dispositionHeader;
  }

  /**
   * Returns the blob's data content language.
   *
   * @see <a href="http://tools.ietf.org/html/bcp47">Content-Language</a>
   */
  public String getContentLanguage() {
    return Data.isNull(languageTag) ? null : languageTag;
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

  /** Returns the URI of this blob as a string. */
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
    return Data.isNull(md5Digest) ? null : md5Digest;
  }

  /**
   * Returns the MD5 hash of blob's data decoded to string.
   *
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags#_JSONAPI">Hashes and ETags:
   *     Best Practices</a>
   */
  public String getMd5ToHexString() {
    if (md5Digest == null) {
      return null;
    }
    byte[] decodedMd5Bytes = BaseEncoding.base64().decode(md5Digest);
    StringBuilder sb = new StringBuilder();
    for (byte byteVal : decodedMd5Bytes) {
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
    return Data.isNull(crc32cChecksum) ? null : crc32cChecksum;
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
    if (crc32cChecksum == null) {
      return null;
    }
    byte[] decodedCrc32c = BaseEncoding.base64().decode(crc32cChecksum);
    StringBuilder sb = new StringBuilder();
    for (byte byteVal : decodedCrc32c) {
      sb.append(String.format("%02x", byteVal & 0xff));
    }
    return sb.toString();
  }

  /** Returns the blob's media download link. */
  public String getMediaLink() {
    return mediaUrl;
  }

  /** Returns blob's user provided metadata. */
  public Map<String, String> getMetadata() {
    return customMeta == null || Data.isNull(customMeta) ? null : Collections.unmodifiableMap(customMeta);
  }

  /** Returns blob's data generation. Used for blob versioning. */
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
    return lastUpdatedTime;
  }

  /**
   * Returns the creation time of the blob expressed as the number of milliseconds since the Unix
   * epoch.
   */
  public Long getCreateTime() {
    return creationTime;
  }

  /** Returns the custom time specified by the user for an object. */
  public Long getCustomTime() {
    return customTimestamp;
  }

  /**
   * Returns {@code true} if the current blob represents a directory. This can only happen if the
   * blob is returned by {@link CloudStorageClient#list(String, CloudStorageClient.BlobListOptions...)} when the {@link
   * CloudStorageClient.BlobListOptions#restrictToCurrentDirectory()} option is used. When this is the case only {@link
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

  /** Returns the storage class of the blob. */
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

  /** Returns the Cloud KMS key used to encrypt the blob, if any. */
  public String getKmsKeyName() {
    return kmsKeyId;
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
   * StorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
   * CloudStorageClient#update(BlobMetadata, CloudStorageClient.BlobUploadOptions...)} in which case the value of event-based
   * hold will remain {@code false} for the given instance.
   */
  @BetaApi
  public Boolean getEventBasedHold() {
    return Data.<Boolean>isNull(eventHoldFlag) ? null : eventHoldFlag;
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
   * StorageObjectBuilder#setEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
   * CloudStorageClient#update(BlobMetadata, CloudStorageClient.BlobUploadOptions...)} in which case the value of temporary
   * hold will remain {@code false} for the given instance.
   */
  @BetaApi
  public Boolean getTemporaryHold() {
    return Data.<Boolean>isNull(tempHoldFlag) ? null : tempHoldFlag;
  }

  /**
   * Returns the retention expiration time of the blob as {@code Long}, if a retention period is
   * defined. If retention period is not defined this value returns {@code null}
   */
  @BetaApi
  public Long getRetentionExpirationTime() {
    return Data.<Long>isNull(retentionExpiryTime) ? null : retentionExpiryTime;
  }

  /** Returns a builder for the current blob. */
  public StorageObjectBuilder asBuilder() {
    return new BlobInfoBuilderImpl(this);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("bucket", getBucket())
        .add("name", getName())
        .add("generation", getGeneration())
        .add("size", getSize())
        .add("content-type", getContentType())
        .add("metadata", getMetadata())
        .toString();
  }

  @Override
  public int hashCode() {
    return Objects.hash(blobIdentifier);
  }

  @Override
  public boolean equals(Object other) {
    return other == this
        || other != null
            && other.getClass().equals(BlobMetadata.class)
            && Objects.equals(toProto(), ((BlobMetadata) other).toProto());
  }

  StorageObject toProto() {
    StorageObject protoStorage = blobIdentifier.toProto();
    if (accessControlList != null) {
      protoStorage.setAcl(
          Lists.transform(
                  accessControlList,
              new Function<AccessControlEntry, ObjectAccessControl>() {
                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                  return acl.toObjectProto();
                }
              }));
    }
    if (deletionTime != null) {
      protoStorage.setTimeDeleted(new DateTime(deletionTime));
    }
    if (lastUpdatedTime != null) {
      protoStorage.setUpdated(new DateTime(lastUpdatedTime));
    }
    if (creationTime != null) {
      protoStorage.setTimeCreated(new DateTime(creationTime));
    }
    if (customTimestamp != null) {
      protoStorage.setCustomTime(new DateTime(customTimestamp));
    }
    if (contentSize != null) {
      protoStorage.setSize(BigInteger.valueOf(contentSize));
    }
    if (ownerEntity != null) {
      protoStorage.setOwner(new Owner().setEntity(ownerEntity.toProto()));
    }
    if (storageTier != null) {
      protoStorage.setStorageClass(storageTier.toString());
    }
    if (storageClassUpdateTime != null) {
      protoStorage.setTimeStorageClassUpdated(new DateTime(storageClassUpdateTime));
    }

    Map<String, String> protoMetadata = customMeta;
    if (customMeta != null && !Data.isNull(customMeta)) {
      protoMetadata = Maps.newHashMapWithExpectedSize(customMeta.size());
      for (Map.Entry<String, String> mapEntry : customMeta.entrySet()) {
        protoMetadata.put(
            mapEntry.getKey(), firstNonNull(mapEntry.getValue(), Data.<String>nullOf(String.class)));
      }
    }
    if (encryptionInfo != null) {
      protoStorage.setCustomerEncryption(encryptionInfo.toProto());
    }
    if (retentionExpiryTime != null) {
      protoStorage.setRetentionExpirationTime(new DateTime(retentionExpiryTime));
    }
    protoStorage.setKmsKeyName(kmsKeyId);
    protoStorage.setEventBasedHold(eventHoldFlag);
    protoStorage.setTemporaryHold(tempHoldFlag);
    protoStorage.setMetadata(protoMetadata);
    protoStorage.setCacheControl(cacheDirective);
    protoStorage.setContentEncoding(contentEncodingType);
    protoStorage.setCrc32c(crc32cChecksum);
    protoStorage.setContentType(mimeType);
    protoStorage.setMd5Hash(md5Digest);
    protoStorage.setMediaLink(mediaUrl);
    protoStorage.setMetageneration(metaGeneration);
    protoStorage.setContentDisposition(dispositionHeader);
    protoStorage.setComponentCount(partsCount);
    protoStorage.setContentLanguage(languageTag);
    protoStorage.setEtag(entityTag);
    protoStorage.setId(generatedIdentifier);
    protoStorage.setSelfLink(resourceLink);
    return protoStorage;
  }

  /** Returns a {@code BlobInfo} builder where blob identity is set using the provided values. */
  public static StorageObjectBuilder newBuilder(BucketInfo bucket, String objectKey) {
    return newBuilder(bucket.getName(), objectKey);
  }

  /** Returns a {@code BlobInfo} builder where blob identity is set using the provided values. */
  public static StorageObjectBuilder newBuilder(String containerName, String objectKey) {
    return newBuilder(BlobIdentifier.create(containerName, objectKey));
  }

  /** Returns a {@code BlobInfo} builder where blob identity is set using the provided values. */
  public static StorageObjectBuilder newBuilder(BucketInfo bucket, String objectKey, Long version) {
    return newBuilder(bucket.getName(), objectKey, version);
  }

  /** Returns a {@code BlobInfo} builder where blob identity is set using the provided values. */
  public static StorageObjectBuilder newBuilder(String containerName, String objectKey, Long version) {
    return newBuilder(BlobIdentifier.create(containerName, objectKey, version));
  }

  /** Returns a {@code BlobInfo} builder where blob identity is set using the provided value. */
  public static StorageObjectBuilder newBuilder(BlobIdentifier blobIdentifier) {
    return new BlobInfoBuilderImpl(blobIdentifier);
  }

  static BlobMetadata fromPb(StorageObject protoStorage) {
    StorageObjectBuilder blobBuilder = newBuilder(BlobIdentifier.fromProto(protoStorage));
    if (protoStorage.getCacheControl() != null) {
      blobBuilder.setCacheControl(protoStorage.getCacheControl());
    }
    if (protoStorage.getContentEncoding() != null) {
      blobBuilder.setContentEncoding(protoStorage.getContentEncoding());
    }
    if (protoStorage.getCrc32c() != null) {
      blobBuilder.setCrc32c(protoStorage.getCrc32c());
    }
    if (protoStorage.getContentType() != null) {
      blobBuilder.setContentType(protoStorage.getContentType());
    }
    if (protoStorage.getMd5Hash() != null) {
      blobBuilder.setMd5(protoStorage.getMd5Hash());
    }
    if (protoStorage.getMediaLink() != null) {
      blobBuilder.setMediaLink(protoStorage.getMediaLink());
    }
    if (protoStorage.getMetageneration() != null) {
      blobBuilder.setMetageneration(protoStorage.getMetageneration());
    }
    if (protoStorage.getContentDisposition() != null) {
      blobBuilder.setContentDisposition(protoStorage.getContentDisposition());
    }
    if (protoStorage.getComponentCount() != null) {
      blobBuilder.setComponentCount(protoStorage.getComponentCount());
    }
    if (protoStorage.getContentLanguage() != null) {
      blobBuilder.setContentLanguage(protoStorage.getContentLanguage());
    }
    if (protoStorage.getEtag() != null) {
      blobBuilder.setEtag(protoStorage.getEtag());
    }
    if (protoStorage.getId() != null) {
      blobBuilder.setGeneratedId(protoStorage.getId());
    }
    if (protoStorage.getSelfLink() != null) {
      blobBuilder.setSelfLink(protoStorage.getSelfLink());
    }
    if (protoStorage.getMetadata() != null) {
      blobBuilder.setMetadata(protoStorage.getMetadata());
    }
    if (protoStorage.getTimeDeleted() != null) {
      blobBuilder.setDeleteTime(protoStorage.getTimeDeleted().getValue());
    }
    if (protoStorage.getUpdated() != null) {
      blobBuilder.setUpdateTime(protoStorage.getUpdated().getValue());
    }
    if (protoStorage.getTimeCreated() != null) {
      blobBuilder.setCreateTime(protoStorage.getTimeCreated().getValue());
    }
    if (protoStorage.getCustomTime() != null) {
      blobBuilder.setCustomTime(protoStorage.getCustomTime().getValue());
    }
    if (protoStorage.getSize() != null) {
      blobBuilder.setSize(protoStorage.getSize().longValue());
    }
    if (protoStorage.getOwner() != null) {
      blobBuilder.setOwner(AccessControlEntry.TypedEntity.fromProto(protoStorage.getOwner().getEntity()));
    }
    if (protoStorage.getAcl() != null) {
      blobBuilder.setAcl(
          Lists.transform(
              protoStorage.getAcl(),
              new Function<ObjectAccessControl, AccessControlEntry>() {
                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                  return AccessControlEntry.fromProto(objectAccessControl);
                }
              }));
    }
    if (protoStorage.containsKey("isDirectory")) {
      blobBuilder.setIsDirectory(Boolean.TRUE);
    }
    if (protoStorage.getCustomerEncryption() != null) {
      blobBuilder.setCustomerEncryption(
          CustomerEncryptionInfo.fromProto(protoStorage.getCustomerEncryption()));
    }
    if (protoStorage.getStorageClass() != null) {
      blobBuilder.setStorageClass(StorageTier.fromValue(protoStorage.getStorageClass()));
    }
    if (protoStorage.getTimeStorageClassUpdated() != null) {
      blobBuilder.setTimeStorageClassUpdated(protoStorage.getTimeStorageClassUpdated().getValue());
    }
    if (protoStorage.getKmsKeyName() != null) {
      blobBuilder.setKmsKeyName(protoStorage.getKmsKeyName());
    }
    if (protoStorage.getEventBasedHold() != null) {
      blobBuilder.setEventBasedHold(protoStorage.getEventBasedHold());
    }
    if (protoStorage.getTemporaryHold() != null) {
      blobBuilder.setTemporaryHold(protoStorage.getTemporaryHold());
    }
    if (protoStorage.getRetentionExpirationTime() != null) {
      blobBuilder.setRetentionExpirationTime(protoStorage.getRetentionExpirationTime().getValue());
    }
    return blobBuilder.buildObject();
  }
}
