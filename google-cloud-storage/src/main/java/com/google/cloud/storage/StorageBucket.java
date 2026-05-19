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

import static com.google.cloud.storage.StorageBucket.BucketSourceOptions.toBucketGetOptions;
import static com.google.cloud.storage.StorageBucket.BucketSourceOptions.toBucketRequestOptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
import com.google.cloud.storage.StorageService.BlobListOptions;
import com.google.cloud.storage.StorageService.BucketTargetOptions;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.security.Key;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A Google cloud storage bucket.
 *
 * <p>Objects of this class are immutable. Operations that modify the bucket like {@link #updateBucket}
 * return a new object. To get a {@code Bucket} object with the most recent information use {@link
 * #refresh}. {@code Bucket} adds a layer of service-related functionality over {@link BucketMetadata}.
 */
public class StorageBucket extends BucketMetadata {

  private static final long serialVersionUID = 8574601739542252586L;

  private final StorageSettings storageSettings;
  private transient StorageService serviceClient;

  /** Class for specifying bucket source options when {@code Bucket} methods are used. */
  public static class BucketSourceOptions extends AbstractOption {

    private static final long serialVersionUID = 6928872234155522371L;

    private BucketSourceOptions(StorageRpcClient.StorageOption remoteOption) {
      super(remoteOption, null);
    }

    private BucketSourceOptions(StorageRpcClient.StorageOption remoteOption, Object payload) {
      super(remoteOption, payload);
    }

    private StorageService.BucketRequestOption toBucketRequestOption(BucketMetadata bucketMeta) {
      switch (getRpcOption()) {
        case IF_METAGENERATION_MATCH:
          return StorageService.BucketRequestOption.ifMetagenerationMatch(bucketMeta.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return StorageService.BucketRequestOption.ifMetagenerationNotMatch(bucketMeta.getMetageneration());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private StorageService.BucketGetOptions toBucketGetOption(BucketMetadata bucketMeta) {
      switch (getRpcOption()) {
        case IF_METAGENERATION_MATCH:
          return StorageService.BucketGetOptions.ifMetagenerationMatch(bucketMeta.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return StorageService.BucketGetOptions.ifMetagenerationNotMatch(bucketMeta.getMetageneration());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BucketSourceOptions ifMetagenerationMatch() {
      return new BucketSourceOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static BucketSourceOptions metagenerationNotMatch() {
      return new BucketSourceOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketSourceOptions userProject(String projectId) {
      return new BucketSourceOptions(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
    }

    static StorageService.BucketRequestOption[] toBucketRequestOptions(
            BucketMetadata bucketMeta, BucketSourceOptions... storageSettings) {
      StorageService.BucketRequestOption[] requestOptions =
          new StorageService.BucketRequestOption[storageSettings.length];
      int idx = 0;
      for (BucketSourceOptions srcOpts : storageSettings) {
        requestOptions[idx++] = srcOpts.toBucketRequestOption(bucketMeta);
      }
      return requestOptions;
    }

    static StorageService.BucketGetOptions[] toBucketGetOptions(
            BucketMetadata bucketMeta, BucketSourceOptions... storageSettings) {
      StorageService.BucketGetOptions[] requestOptions = new StorageService.BucketGetOptions[storageSettings.length];
      int idx = 0;
      for (BucketSourceOptions srcOpts : storageSettings) {
        requestOptions[idx++] = srcOpts.toBucketGetOption(bucketMeta);
      }
      return requestOptions;
    }
  }

  /** Class for specifying blob target options when {@code Bucket} methods are used. */
  public static class BlobUploadOption extends AbstractOption {

    private static final Function<BlobUploadOption, StorageRpcClient.StorageOption> TO_STORAGE_OPTION =
        new Function<BlobUploadOption, StorageRpcClient.StorageOption>() {
          @Override
          public StorageRpcClient.StorageOption apply(BlobUploadOption blobTargetOption) {
            return blobTargetOption.getRpcOption();
          }
        };
    private static final long serialVersionUID = 8345296337342509425L;

    private BlobUploadOption(StorageRpcClient.StorageOption remoteOption, Object payload) {
      super(remoteOption, payload);
    }

    private Tuple<BlobMetadata, StorageService.BlobUploadOption> toUploadOption(BlobMetadata blobMeta) {
      BlobIdentifier blobIdentifier = blobMeta.getBlobId();
      switch (getRpcOption()) {
        case PREDEFINED_ACL:
          return Tuple.of(
                  blobMeta, StorageService.BlobUploadOption.withPredefinedAcl((StorageService.PredefinedAccessControlList) getValue()));
        case IF_GENERATION_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
          return Tuple.of(
              blobMeta.toBlobBuilder().setBlobId(blobIdentifier).buildObject(),
              StorageService.BlobUploadOption.ifGenerationMatch());
        case IF_GENERATION_NOT_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
          return Tuple.of(
              blobMeta.toBlobBuilder().setBlobId(blobIdentifier).buildObject(),
              StorageService.BlobUploadOption.ifGenerationNotMatch());
        case IF_METAGENERATION_MATCH:
          return Tuple.of(
              blobMeta.toBlobBuilder().setMetageneration((Long) getValue()).buildObject(),
              StorageService.BlobUploadOption.ifMetagenerationMatch());
        case IF_METAGENERATION_NOT_MATCH:
          return Tuple.of(
              blobMeta.toBlobBuilder().setMetageneration((Long) getValue()).buildObject(),
              StorageService.BlobUploadOption.ifMetagenerationNotMatch());
        case CUSTOMER_SUPPLIED_KEY:
          return Tuple.of(blobMeta, StorageService.BlobUploadOption.customerEncryptionKey((String) getValue()));
        case KMS_KEY_NAME:
          return Tuple.of(blobMeta, StorageService.BlobUploadOption.kmsKey((String) getValue()));
        case USER_PROJECT:
          return Tuple.of(blobMeta, StorageService.BlobUploadOption.withUserProject((String) getValue()));
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobUploadOption createPredefinedAcl(StorageService.PredefinedAccessControlList accessList) {
      return new BlobUploadOption(StorageRpcClient.StorageOption.PREDEFINED_ACL, accessList);
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     * This option can not be provided together with {@link #generationMatchOption(long)} or {@link
     * #generationNotMatchOption(long)}.
     */
    public static BlobUploadOption doesNotExistOption() {
      return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match the provided value. This option can not be provided
     * together with {@link #generationNotMatchOption(long)} or {@link #doesNotExistOption()}.
     */
    public static BlobUploadOption generationMatchOption(long gen) {
      return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, gen);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value. This option can not be provided
     * together with {@link #generationMatchOption(long)} or {@link #doesNotExistOption()}.
     */
    public static BlobUploadOption generationNotMatchOption(long gen) {
      return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, gen);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match the provided value. This option can not be provided
     * together with {@link #metagenerationNotMatchOption(long)}.
     */
    public static BlobUploadOption metagenerationMatchOption(long metaGen) {
      return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches the provided value. This option can not be provided together
     * with {@link #metagenerationMatchOption(long)}.
     */
    public static BlobUploadOption metagenerationNotMatchOption(long metaGen) {
      return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobUploadOption withEncryptionKey(Key encryptionKey) {
      String base64EncodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
      return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param encryptionKey the AES256 encoded in base64
     */
    public static BlobUploadOption withEncryptionKey(String encryptionKey) {
      return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
     *
     * @param kmsKeyId the KMS key resource id
     */
    public static BlobUploadOption withKmsKeyName(String kmsKeyId) {
      return new BlobUploadOption(StorageRpcClient.StorageOption.KMS_KEY_NAME, kmsKeyId);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobUploadOption withUserProject(String projectId) {
      return new BlobUploadOption(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
    }

    static Tuple<BlobMetadata, StorageService.BlobUploadOption[]> toBlobUploadOptions(
            BlobMetadata metadata, BlobUploadOption... storageSettings) {
      Set<StorageRpcClient.StorageOption> optionCollection =
          Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), TO_STORAGE_OPTION));
      checkArgument(
          !(optionCollection.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH)
              && optionCollection.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH)),
          "metagenerationMatch and metagenerationNotMatch options can not be both provided");
      checkArgument(
          !(optionCollection.contains(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH)
              && optionCollection.contains(StorageRpcClient.StorageOption.IF_GENERATION_MATCH)),
          "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
      StorageService.BlobUploadOption[] requestOptions = new StorageService.BlobUploadOption[storageSettings.length];
      BlobMetadata blobMetadata = metadata;
      int idx = 0;
      for (BlobUploadOption srcOpts : storageSettings) {
        Tuple<BlobMetadata, StorageService.BlobUploadOption> metadataUploadPair = srcOpts.toUploadOption(blobMetadata);
        blobMetadata = metadataUploadPair.x();
        requestOptions[idx++] = metadataUploadPair.y();
      }
      return Tuple.of(blobMetadata, requestOptions);
    }
  }

  /** Class for specifying blob write options when {@code Bucket} methods are used. */
  public static class BlobWriteSetting implements Serializable {

    private static final Function<BlobWriteSetting, StorageService.BlobWriteOptions.StorageOption> TO_STORAGE_OPTION =
        new Function<BlobWriteSetting, StorageService.BlobWriteOptions.StorageOption>() {
          @Override
          public StorageService.BlobWriteOptions.StorageOption apply(BlobWriteSetting blobWriteOption) {
            return blobWriteOption.srcOpts;
          }
        };
    private static final long serialVersionUID = 4722190734541993114L;

    private final StorageService.BlobWriteOptions.StorageOption srcOpts;
    private final Object payload;

    private Tuple<BlobMetadata, StorageService.BlobWriteOptions> toBlobWriteOption(BlobMetadata blobMeta) {
      BlobIdentifier blobIdentifier = blobMeta.getBlobId();
      switch (srcOpts) {
        case PREDEFINED_ACL:
          return Tuple.of(
                  blobMeta, StorageService.BlobWriteOptions.withPredefinedAcl((StorageService.PredefinedAccessControlList) payload));
        case IF_GENERATION_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) payload);
          return Tuple.of(
              blobMeta.toBlobBuilder().setBlobId(blobIdentifier).buildObject(),
              StorageService.BlobWriteOptions.ifGenerationMatch());
        case IF_GENERATION_NOT_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) payload);
          return Tuple.of(
              blobMeta.toBlobBuilder().setBlobId(blobIdentifier).buildObject(),
              StorageService.BlobWriteOptions.ifGenerationNotMatch());
        case IF_METAGENERATION_MATCH:
          return Tuple.of(
              blobMeta.toBlobBuilder().setMetageneration((Long) payload).buildObject(),
              StorageService.BlobWriteOptions.ifMetagenerationMatch());
        case IF_METAGENERATION_NOT_MATCH:
          return Tuple.of(
              blobMeta.toBlobBuilder().setMetageneration((Long) payload).buildObject(),
              StorageService.BlobWriteOptions.ifMetagenerationNotMatch());
        case IF_MD5_MATCH:
          return Tuple.of(
              blobMeta.toBlobBuilder().setMd5((String) payload).buildObject(),
              StorageService.BlobWriteOptions.ifMd5Match());
        case IF_CRC32C_MATCH:
          return Tuple.of(
              blobMeta.toBlobBuilder().setCrc32c((String) payload).buildObject(),
              StorageService.BlobWriteOptions.ifCrc32cMatch());
        case CUSTOMER_SUPPLIED_KEY:
          return Tuple.of(blobMeta, StorageService.BlobWriteOptions.withEncryptionKey((String) payload));
        case KMS_KEY_NAME:
          return Tuple.of(blobMeta, StorageService.BlobWriteOptions.withKmsKeyName((String) payload));
        case USER_PROJECT:
          return Tuple.of(blobMeta, StorageService.BlobWriteOptions.withUserProject((String) payload));
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption srcOpts, Object payload) {
      this.srcOpts = srcOpts;
      this.payload = payload;
    }

    @Override
    public int hashCode() {
      return Objects.hash(srcOpts, payload);
    }

    @Override
    public boolean equals(Object other) {
      if (other == null) {
        return false;
      }
      if (!(other instanceof BlobWriteSetting)) {
        return false;
      }
      final BlobWriteSetting thatSetting = (BlobWriteSetting) other;
      return this.srcOpts == thatSetting.srcOpts && Objects.equals(this.payload, thatSetting.payload);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobWriteSetting createPredefinedAcl(StorageService.PredefinedAccessControlList accessList) {
      return new BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption.PREDEFINED_ACL, accessList);
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     * This option can not be provided together with {@link #generationMatchOption(long)} or {@link
     * #generationNotMatchOption(long)}.
     */
    public static BlobWriteSetting doesNotExistOption() {
      return new BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match the provided value. This option can not be provided
     * together with {@link #generationNotMatchOption(long)} or {@link #doesNotExistOption()}.
     */
    public static BlobWriteSetting generationMatchOption(long gen) {
      return new BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, gen);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches the provided value. This option can not be provided together
     * with {@link #generationMatchOption(long)} or {@link #doesNotExistOption()}.
     */
    public static BlobWriteSetting generationNotMatchOption(long gen) {
      return new BlobWriteSetting(
          StorageService.BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH, gen);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match the provided value. This option can not be provided
     * together with {@link #metagenerationNotMatchOption(long)}.
     */
    public static BlobWriteSetting metagenerationMatchOption(long metaGen) {
      return new BlobWriteSetting(
          StorageService.BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches the provided value. This option can not be provided together
     * with {@link #metagenerationMatchOption(long)}.
     */
    public static BlobWriteSetting metagenerationNotMatchOption(long metaGen) {
      return new BlobWriteSetting(
          StorageService.BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's data MD5 hash match. If this option is used the request will
     * fail if blobs' data MD5 hash does not match the provided value.
     */
    public static BlobWriteSetting md5MatchOption(String hashDigest) {
      return new BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption.IF_MD5_MATCH, hashDigest);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match the provided value.
     */
    public static BlobWriteSetting crc32cMatchOption(String checksum) {
      return new BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption.IF_CRC32C_MATCH, checksum);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobWriteSetting withEncryptionKey(Key encryptionKey) {
      String base64EncodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
      return new BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param encryptionKey the AES256 encoded in base64
     */
    public static BlobWriteSetting withEncryptionKey(String encryptionKey) {
      return new BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobWriteSetting withUserProject(String projectId) {
      return new BlobWriteSetting(StorageService.BlobWriteOptions.StorageOption.USER_PROJECT, projectId);
    }

    static Tuple<BlobMetadata, StorageService.BlobWriteOptions[]> toBlobWriteOptions(
            BlobMetadata blobMetadata, BlobWriteSetting... storageSettings) {
      Set<StorageService.BlobWriteOptions.StorageOption> optionCollection =
          Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), TO_STORAGE_OPTION));
      checkArgument(
          !(optionCollection.contains(StorageService.BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH)
              && optionCollection.contains(StorageService.BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH)),
          "metagenerationMatch and metagenerationNotMatch options can not be both provided");
      checkArgument(
          !(optionCollection.contains(StorageService.BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH)
              && optionCollection.contains(StorageService.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH)),
          "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
      StorageService.BlobWriteOptions[] requestOptions = new StorageService.BlobWriteOptions[storageSettings.length];
      BlobMetadata writeMetadata = blobMetadata;
      int idx = 0;
      for (BlobWriteSetting srcOpts : storageSettings) {
        Tuple<BlobMetadata, StorageService.BlobWriteOptions> metadataWithOptionsPair = srcOpts.toBlobWriteOption(writeMetadata);
        writeMetadata = metadataWithOptionsPair.x();
        requestOptions[idx++] = metadataWithOptionsPair.y();
      }
      return Tuple.of(writeMetadata, requestOptions);
    }
  }

  /** Builder for {@code Bucket}. */
  public static class BucketInfoBuilder extends BucketBuilder {
    private final StorageService serviceClient;
    private final BucketBuilderImpl bucketBuilder;

    BucketInfoBuilder(StorageBucket targetContainer) {
      this.serviceClient = targetContainer.serviceClient;
      this.bucketBuilder = new BucketBuilderImpl(targetContainer);
    }

    @Override
    public StorageBucket.BucketInfoBuilder setName(String newLabel) {
      bucketBuilder.setName(newLabel);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setGeneratedId(String generatedIdentifier) {
      bucketBuilder.setGeneratedId(generatedIdentifier);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setOwner(AclEntry.AbstractEntity entityHolder) {
      bucketBuilder.setOwner(entityHolder);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setSelfLink(String resourceUrl) {
      bucketBuilder.setSelfLink(resourceUrl);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setVersioningEnabled(Boolean versioningActive) {
      bucketBuilder.setVersioningEnabled(versioningActive);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setRequesterPays(Boolean requesterBilling) {
      bucketBuilder.setRequesterPays(requesterBilling);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setIndexPage(String indexDocument) {
      bucketBuilder.setIndexPage(indexDocument);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setNotFoundPage(String errorPage404) {
      bucketBuilder.setNotFoundPage(errorPage404);
      return this;
    }

    @Override
    @Deprecated
    public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends DeleteActionRule> deleteRuleSet) {
      bucketBuilder.setDeleteRules(deleteRuleSet);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deleteRuleSet) {
      bucketBuilder.setLifecycleRules(deleteRuleSet);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setStorageClass(StorageClass storageTier) {
      bucketBuilder.setStorageClass(storageTier);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLocation(String region) {
      bucketBuilder.setLocation(region);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setEtag(String entityTag) {
      bucketBuilder.setEtag(entityTag);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setCreateTime(Long creationTime) {
      bucketBuilder.setCreateTime(creationTime);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setMetageneration(Long metaGen) {
      bucketBuilder.setMetageneration(metaGen);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setCors(Iterable<Cors> corsSettings) {
      bucketBuilder.setCors(corsSettings);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setAcl(Iterable<AclEntry> accessList) {
      bucketBuilder.setAcl(accessList);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<AclEntry> accessList) {
      bucketBuilder.setDefaultAcl(accessList);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> metadataLabels) {
      bucketBuilder.setLabels(metadataLabels);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultKmsKeyName(String defaultKmsKey) {
      bucketBuilder.setDefaultKmsKeyName(defaultKmsKey);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultEventBasedHold(Boolean eventBasedHoldDefault) {
      bucketBuilder.setDefaultEventBasedHold(eventBasedHoldDefault);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long retentionEffectiveTimestamp) {
      bucketBuilder.setRetentionEffectiveTime(retentionEffectiveTimestamp);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setRetentionPolicyIsLocked(Boolean retentionLocked) {
      bucketBuilder.setRetentionPolicyIsLocked(retentionLocked);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setRetentionPeriod(Long retentionDuration) {
      bucketBuilder.setRetentionPeriod(retentionDuration);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setIamConfiguration(IamSettings iamSettings) {
      bucketBuilder.setIamConfiguration(iamSettings);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLogging(BucketLogging loggingSettings) {
      bucketBuilder.setLogging(loggingSettings);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setLocationType(String locationScope) {
      bucketBuilder.setLocationType(locationScope);
      return this;
    }

    @Override
    public StorageBucket buildBucket() {
      return new StorageBucket(serviceClient, bucketBuilder);
    }
  }

  StorageBucket(StorageService serviceClient, BucketBuilderImpl bucketBuilder) {
    super(bucketBuilder);
    this.serviceClient = checkNotNull(serviceClient);
    this.storageSettings = serviceClient.getOptions();
  }

  /**
   * Checks if this bucket exists.
   *
   * <p>Example of checking if the bucket exists.
   *
   * <pre>{@code
   * boolean exists = bucket.exists();
   * if (exists) {
   *   // the bucket exists
   * } else {
   *   // the bucket was not found
   * }
   * }</pre>
   *
   * @return true if this bucket exists, false otherwise
   * @throws StorageServiceException upon failure
   */
  public boolean bucketExists(BucketSourceOptions... storageSettings) {
    int optionsLength = storageSettings.length;
    StorageService.BucketGetOptions[] getOptionArray = Arrays.copyOf(toBucketGetOptions(this, storageSettings), optionsLength + 1);
    getOptionArray[optionsLength] = StorageService.BucketGetOptions.selectFields();
    return serviceClient.get(getName(), getOptionArray) != null;
  }

  /**
   * Fetches current bucket's latest information. Returns {@code null} if the bucket does not exist.
   *
   * <p>Example of getting the bucket's latest information, if its generation does not match the
   * {@link StorageBucket#getMetageneration()} value, otherwise a {@link StorageServiceException} is thrown.
   *
   * <pre>{@code
   * Bucket latestBucket = bucket.reload(BucketSourceOption.metagenerationMatch());
   * if (latestBucket == null) {
   *   // the bucket was not found
   * }
   * }</pre>
   *
   * @param storageSettings bucket read options
   * @return a {@code Bucket} object with latest information or {@code null} if not found
   * @throws StorageServiceException upon failure
   */
  public StorageBucket refresh(BucketSourceOptions... storageSettings) {
    return serviceClient.get(getName(), toBucketGetOptions(this, storageSettings));
  }

  /**
   * Updates the bucket's information. Bucket's name cannot be changed. A new {@code Bucket} object
   * is returned. By default no checks are made on the metadata generation of the current bucket. If
   * you want to update the information only if the current bucket metadata are at their latest
   * version use the {@code metagenerationMatch} option: {@code
   * bucket.update(BucketTargetOption.metagenerationMatch())}
   *
   * <p>Example of updating the bucket's information.
   *
   * <pre>{@code
   * Bucket updatedBucket = bucket.toBuilder().setVersioningEnabled(true).build().update();
   * }</pre>
   *
   * @param storageSettings update options
   * @return a {@code Bucket} object with updated information
   * @throws StorageServiceException upon failure
   */
  public StorageBucket updateBucket(BucketTargetOptions... storageSettings) {
    return serviceClient.update(this, storageSettings);
  }

  /**
   * Deletes this bucket.
   *
   * <p>Example of deleting the bucket, if its metageneration matches the {@link
   * StorageBucket#getMetageneration()} value, otherwise a {@link StorageServiceException} is thrown.
   *
   * <pre>{@code
   * boolean deleted = bucket.delete(BucketSourceOption.metagenerationMatch());
   * if (deleted) {
   *   // the bucket was deleted
   * } else {
   *   // the bucket was not found
   * }
   * }</pre>
   *
   * @param storageSettings bucket delete options
   * @return {@code true} if bucket was deleted, {@code false} if it was not found
   * @throws StorageServiceException upon failure
   */
  public boolean deleteBucket(BucketSourceOptions... storageSettings) {
    return serviceClient.delete(getName(), toBucketRequestOptions(this, storageSettings));
  }

  /**
   * Returns the paginated list of {@code Blob} in this bucket.
   *
   * <p>Example of listing the blobs in the bucket.
   *
   * <pre>{@code
   * Page<Blob> blobs = bucket.list();
   * Iterator<Blob> blobIterator = blobs.iterateAll();
   * while (blobIterator.hasNext()) {
   *   Blob blob = blobIterator.next();
   *   // do something with the blob
   * }
   * }</pre>
   *
   * @param storageSettings options for listing blobs
   * @throws StorageServiceException upon failure
   */
  public Page<CloudStorageObject> listObjects(BlobListOptions... storageSettings) {
    return serviceClient.list(getName(), storageSettings);
  }

  /**
   * Returns the requested blob in this bucket or {@code null} if not found.
   *
   * <p>Example of getting a blob in the bucket, only if its metageneration matches a value,
   * otherwise a {@link StorageServiceException} is thrown.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * long generation = 42;
   * Blob blob = bucket.get(blobName, BlobGetOption.generationMatch(generation));
   * }</pre>
   *
   * @param blobName name of the requested blob
   * @param storageSettings blob search options
   * @throws StorageServiceException upon failure
   */
  public CloudStorageObject get(String blobName, StorageService.BlobGetOptions... storageSettings) {
    return serviceClient.get(BlobIdentifier.create(getName(), blobName), storageSettings);
  }

  /**
   * Returns a list of requested blobs in this bucket. Blobs that do not exist are null.
   *
   * <p>Example of getting some blobs in the bucket, using a batch request.
   *
   * <pre>{@code
   * String blobName1 = "my_blob_name1";
   * String blobName2 = "my_blob_name2";
   * List<Blob> blobs = bucket.get(blobName1, blobName2);
   * for (Blob blob : blobs) {
   *   if (blob == null) {
   *     // the blob was not found
   *   }
   * }
   * }</pre>
   *
   * @param primaryBlobName first blob to get
   * @param secondaryBlobName second blob to get
   * @param blobNameList other blobs to get
   * @return an immutable list of {@code Blob} objects
   * @throws StorageServiceException upon failure
   */
  public List<CloudStorageObject> get(String primaryBlobName, String secondaryBlobName, String... blobNameList) {
    List<BlobIdentifier> blobIdentifiersList = Lists.newArrayListWithCapacity(blobNameList.length + 2);
    blobIdentifiersList.add(BlobIdentifier.create(getName(), primaryBlobName));
    blobIdentifiersList.add(BlobIdentifier.create(getName(), secondaryBlobName));
    for (String objectName : blobNameList) {
      blobIdentifiersList.add(BlobIdentifier.create(getName(), objectName));
    }
    return serviceClient.get(blobIdentifiersList);
  }

  /**
   * Returns a list of requested blobs in this bucket. Blobs that do not exist are null.
   *
   * <p>Example of getting some blobs in the bucket, using a batch request.
   *
   * <pre>{@code
   * String blobName1 = "my_blob_name1";
   * String blobName2 = "my_blob_name2";
   * List<String> blobNames = new LinkedList<>();
   * blobNames.add(blobName1);
   * blobNames.add(blobName2);
   * List<Blob> blobs = bucket.get(blobNames);
   * for (Blob blob : blobs) {
   *   if (blob == null) {
   *     // the blob was not found
   *   }
   * }
   * }</pre>
   *
   * @param blobNameList blobs to get
   * @return an immutable list of {@code Blob} objects
   * @throws StorageServiceException upon failure
   */
  public List<CloudStorageObject> get(Iterable<String> blobNameList) {
    ImmutableList.Builder<BlobIdentifier> identifierFactory = ImmutableList.builder();
    for (String objectName : blobNameList) {
      identifierFactory.add(BlobIdentifier.create(getName(), objectName));
    }
    return serviceClient.get(identifierFactory.build());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link CloudStorageObject#openWriter(StorageService.BlobWriteOptions...)} is
   * recommended as it uses resumable upload. MD5 and CRC32C hashes of {@code content} are computed
   * and used for validating transferred data.
   *
   * <p>Example of creating a blob in the bucket from a byte array with a content type.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * Blob blob = bucket.create(blobName, "Hello, World!".getBytes(UTF_8), "text/plain");
   * }</pre>
   *
   * @param blobName a blob name
   * @param payload the blob content
   * @param mimeType the blob content type
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageServiceException upon failure
   */
  public CloudStorageObject createBlob(String blobName, byte[] payload, String mimeType, BlobUploadOption... storageSettings) {
    BlobMetadata blobMeta =
        BlobMetadata.newBuilder(BlobIdentifier.create(getName(), blobName)).setContentType(mimeType).buildObject();
    Tuple<BlobMetadata, StorageService.BlobUploadOption[]> metadataUploadPair =
        StorageBucket.BlobUploadOption.toBlobUploadOptions(blobMeta, storageSettings);
    return serviceClient.create(metadataUploadPair.x(), payload, metadataUploadPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link CloudStorageObject#openWriter(StorageService.BlobWriteOptions...)} is
   * recommended as it uses resumable upload.
   *
   * <p>Example of creating a blob in the bucket from an input stream with a content type.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
   * Blob blob = bucket.create(blobName, content, "text/plain");
   * }</pre>
   *
   * @param blobName a blob name
   * @param payload the blob content as a stream
   * @param mimeType the blob content type
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageServiceException upon failure
   */
  public CloudStorageObject createBlob(
          String blobName, InputStream payload, String mimeType, BlobWriteSetting... storageSettings) {
    BlobMetadata blobMeta =
        BlobMetadata.newBuilder(BlobIdentifier.create(getName(), blobName)).setContentType(mimeType).buildObject();
    Tuple<BlobMetadata, StorageService.BlobWriteOptions[]> metadataWithOptionsPair =
        BlobWriteSetting.toBlobWriteOptions(blobMeta, storageSettings);
    return serviceClient.create(metadataWithOptionsPair.x(), payload, metadataWithOptionsPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link CloudStorageObject#openWriter(StorageService.BlobWriteOptions...)} is
   * recommended as it uses resumable upload. MD5 and CRC32C hashes of {@code content} are computed
   * and used for validating transferred data.
   *
   * <p>Example of creating a blob in the bucket from a byte array.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * Blob blob = bucket.create(blobName, "Hello, World!".getBytes(UTF_8));
   * }</pre>
   *
   * @param blobName a blob name
   * @param payload the blob content
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageServiceException upon failure
   */
  public CloudStorageObject createBlob(String blobName, byte[] payload, BlobUploadOption... storageSettings) {
    BlobMetadata blobMeta = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), blobName)).buildObject();
    Tuple<BlobMetadata, StorageService.BlobUploadOption[]> metadataUploadPair =
        StorageBucket.BlobUploadOption.toBlobUploadOptions(blobMeta, storageSettings);
    return serviceClient.create(metadataUploadPair.x(), payload, metadataUploadPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link CloudStorageObject#openWriter(StorageService.BlobWriteOptions...)} is
   * recommended as it uses resumable upload.
   *
   * <p>Example of creating a blob in the bucket from an input stream.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
   * Blob blob = bucket.create(blobName, content);
   * }</pre>
   *
   * @param blobName a blob name
   * @param payload the blob content as a stream
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageServiceException upon failure
   */
  public CloudStorageObject createBlob(String blobName, InputStream payload, BlobWriteSetting... storageSettings) {
    BlobMetadata blobMeta = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), blobName)).buildObject();
    Tuple<BlobMetadata, StorageService.BlobWriteOptions[]> metadataWithOptionsPair =
        BlobWriteSetting.toBlobWriteOptions(blobMeta, storageSettings);
    return serviceClient.create(metadataWithOptionsPair.x(), payload, metadataWithOptionsPair.y());
  }

  /**
   * Returns the ACL entry for the specified entity on this bucket or {@code null} if not found.
   *
   * <p>Example of getting the ACL entry for an entity.
   *
   * <pre>{@code
   * Acl acl = bucket.getAcl(User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * @throws StorageServiceException upon failure
   */
  public AclEntry getAcl(AclEntry.AbstractEntity principal) {
    return serviceClient.getAcl(getName(), principal);
  }

  /**
   * Deletes the ACL entry for the specified entity on this bucket.
   *
   * <p>Example of deleting the ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = bucket.deleteAcl(User.ofAllAuthenticatedUsers());
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
  public boolean removeAcl(AclEntry.AbstractEntity principal) {
    return serviceClient.deleteAcl(getName(), principal);
  }

  /**
   * Creates a new ACL entry on this bucket.
   *
   * <p>Example of creating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = bucket.createAcl(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageServiceException upon failure
   */
  public AclEntry createAccessControl(AclEntry accessList) {
    return serviceClient.createAcl(getName(), accessList);
  }

  /**
   * Updates an ACL entry on this bucket.
   *
   * <p>Example of updating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = bucket.updateAcl(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageServiceException upon failure
   */
  public AclEntry updateAccessControl(AclEntry accessList) {
    return serviceClient.updateAcl(getName(), accessList);
  }

  /**
   * Lists the ACL entries for this bucket.
   *
   * <p>Example of listing the ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = bucket.listAcls();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageServiceException upon failure
   */
  public List<AclEntry> listAclEntries() {
    return serviceClient.listAcls(getName());
  }

  /**
   * Returns the default object ACL entry for the specified entity on this bucket or {@code null} if
   * not found.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example of getting the default ACL entry for an entity.
   *
   * <pre>{@code
   * Acl acl = bucket.getDefaultAcl(User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * @throws StorageServiceException upon failure
   */
  public AclEntry getDefaultAcl(AclEntry.AbstractEntity principal) {
    return serviceClient.getDefaultAcl(getName(), principal);
  }

  /**
   * Deletes the default object ACL entry for the specified entity on this bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example of deleting the default ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = bucket.deleteDefaultAcl(User.ofAllAuthenticatedUsers());
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
  public boolean removeDefaultAcl(AclEntry.AbstractEntity principal) {
    return serviceClient.deleteDefaultAcl(getName(), principal);
  }

  /**
   * Creates a new default blob ACL entry on this bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example of creating a new default ACL entry.
   *
   * <pre>{@code
   * Acl acl = bucket.createDefaultAcl(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageServiceException upon failure
   */
  public AclEntry addDefaultAcl(AclEntry accessList) {
    return serviceClient.createDefaultAcl(getName(), accessList);
  }

  /**
   * Updates a default blob ACL entry on this bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example of updating a new default ACL entry.
   *
   * <pre>{@code
   * Acl acl = bucket.updateDefaultAcl(Acl.of(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageServiceException upon failure
   */
  public AclEntry updateDefaultAccessControl(AclEntry accessList) {
    return serviceClient.updateDefaultAcl(getName(), accessList);
  }

  /**
   * Lists the default blob ACL entries for this bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example of listing the default ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = bucket.listDefaultAcls();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageServiceException upon failure
   */
  public List<AclEntry> listDefaultAclEntries() {
    return serviceClient.listDefaultAcls(getName());
  }

  /**
   * Locks bucket retention policy. Requires a local metageneration value in the request. Review
   * example below.
   *
   * <p>Accepts an optional userProject {@link StorageService.BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Warning: Once a retention policy is locked, it can't be unlocked, removed, or shortened.
   *
   * <p>Example of locking a retention policy on a bucket, only if its local metageneration value
   * matches the bucket's service metageneration otherwise a {@link StorageServiceException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * Bucket bucket = storage.get(bucketName, BucketGetOption.fields(BucketField.METAGENERATION));
   * storage.lockRetentionPolicy(bucket, BucketTargetOption.metagenerationMatch());
   * }</pre>
   *
   * @return a {@code Bucket} object of the locked bucket
   * @throws StorageServiceException upon failure
   */
  public StorageBucket lockRetention(BucketTargetOptions... storageSettings) {
    return serviceClient.lockRetentionPolicy(this, storageSettings);
  }

  /** Returns the bucket's {@code Storage} object used to issue requests. */
  public StorageService getStorage() {
    return serviceClient;
  }

  @Override
  public StorageBucket.BucketInfoBuilder toBucketBuilder() {
    return new BucketInfoBuilder(this);
  }

  @Override
  public final boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (other == null || !other.getClass().equals(StorageBucket.class)) {
      return false;
    }
    StorageBucket thatSetting = (StorageBucket) other;
    return Objects.equals(toProto(), thatSetting.toProto()) && Objects.equals(storageSettings, thatSetting.storageSettings);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), storageSettings);
  }

  private void readObject(ObjectInputStream objectStream) throws IOException, ClassNotFoundException {
    objectStream.defaultReadObject();
    this.serviceClient = storageSettings.getService();
  }

  static StorageBucket fromProto(StorageService serviceClient, com.google.api.services.storage.model.Bucket bucketProto) {
    return new StorageBucket(serviceClient, new BucketBuilderImpl(BucketMetadata.fromProto(bucketProto)));
  }
}
