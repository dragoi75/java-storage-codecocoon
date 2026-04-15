/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy from the License at
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

import static com.google.cloud.storage.StorageBucket.SourceBucketOption.toBucketGetOptions;
import static com.google.cloud.storage.StorageBucket.SourceBucketOption.toBucketSourceOptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.Storage.BlobGetOption;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.Storage.BucketTargetOption;
import com.google.cloud.storage.spi.v1.StorageRpc;
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
 * <p>Objects from this class are immutable. Operations that modify the bucket like {@link #updateBucket}
 * return a new object. To get a {@code StorageBucket} object with the most recent information use {@link
 * #reloadFromStorage}. {@code StorageBucket} adds a layer from service-related functionality over {@link BucketInfo}.
 */
public class StorageBucket extends BucketInfo {

  private static final long BUCKET_SERIAL_ID = 8574601739542252586L;

  private final StorageOptions storageSettings;
  private transient Storage backendClient;

  /** Class for specifying bucket source options when {@code StorageBucket} methods are used. */
  public static class SourceBucketOption extends Option {

    private static final long BUCKET_SERIAL_ID = 6928872234155522371L;

    private SourceBucketOption(StorageRpc.Option rpcSetting) {
      super(rpcSetting, null);
    }

    private SourceBucketOption(StorageRpc.Option rpcSetting, Object paramValue) {
      super(rpcSetting, paramValue);
    }

    private Storage.BucketSourceOption toBucketSourceOption(BucketInfo sourceInfo) {
      switch (getRpcOption()) {
        case IF_METAGENERATION_MATCH:
          return Storage.BucketSourceOption.metagenerationMatch(sourceInfo.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BucketSourceOption.metagenerationNotMatch(sourceInfo.getMetageneration());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private Storage.BucketGetOption toBucketGetOption(BucketInfo sourceInfo) {
      switch (getRpcOption()) {
        case IF_METAGENERATION_MATCH:
          return Storage.BucketGetOption.metagenerationMatch(sourceInfo.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return Storage.BucketGetOption.metagenerationNotMatch(sourceInfo.getMetageneration());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static SourceBucketOption ifMetagenerationMatch() {
      return new SourceBucketOption(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static SourceBucketOption metagenerationNotMatch() {
      return new SourceBucketOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static SourceBucketOption userProject(String projectId) {
      return new SourceBucketOption(StorageRpc.Option.USER_PROJECT, projectId);
    }

    static Storage.BucketSourceOption[] toBucketSourceOptions(
            BucketInfo sourceInfo, SourceBucketOption... storageSettings) {
      Storage.BucketSourceOption[] sourceOptionArr =
          new Storage.BucketSourceOption[storageSettings.length];
      int idx = 0;
      for (SourceBucketOption srcOpt : storageSettings) {
        sourceOptionArr[idx++] = srcOpt.toBucketSourceOption(sourceInfo);
      }
      return sourceOptionArr;
    }

    static Storage.BucketGetOption[] toBucketGetOptions(
            BucketInfo sourceInfo, SourceBucketOption... storageSettings) {
      Storage.BucketGetOption[] sourceOptionArr = new Storage.BucketGetOption[storageSettings.length];
      int idx = 0;
      for (SourceBucketOption srcOpt : storageSettings) {
        sourceOptionArr[idx++] = srcOpt.toBucketGetOption(sourceInfo);
      }
      return sourceOptionArr;
    }
  }

  /** Class for specifying blob target options when {@code StorageBucket} methods are used. */
  public static class BlobUploadOption extends Option {

    private static final Function<BlobUploadOption, StorageRpc.Option> TO_OPTION =
        new Function<BlobUploadOption, StorageRpc.Option>() {
          @Override
          public StorageRpc.Option apply(BlobUploadOption blobTargetOption) {
            return blobTargetOption.getRpcOption();
          }
        };
    private static final long BUCKET_SERIAL_ID = 8345296337342509425L;

    private BlobUploadOption(StorageRpc.Option rpcSetting, Object paramValue) {
      super(rpcSetting, paramValue);
    }

    private Tuple<BlobInfo, Storage.BlobTargetOption> toBlobTargetOption(BlobInfo targetInfo) {
      BlobId targetBlobId = targetInfo.getBlobId();
      switch (getRpcOption()) {
        case PREDEFINED_ACL:
          return Tuple.of(
                  targetInfo, Storage.BlobTargetOption.predefinedAcl((Storage.PredefinedAcl) getValue()));
        case IF_GENERATION_MATCH:
          targetBlobId = BlobId.of(targetBlobId.getBucket(), targetBlobId.getName(), (Long) getValue());
          return Tuple.of(
              targetInfo.toBuilder().setBlobId(targetBlobId).build(),
              Storage.BlobTargetOption.generationMatch());
        case IF_GENERATION_NOT_MATCH:
          targetBlobId = BlobId.of(targetBlobId.getBucket(), targetBlobId.getName(), (Long) getValue());
          return Tuple.of(
              targetInfo.toBuilder().setBlobId(targetBlobId).build(),
              Storage.BlobTargetOption.generationNotMatch());
        case IF_METAGENERATION_MATCH:
          return Tuple.of(
              targetInfo.toBuilder().setMetageneration((Long) getValue()).build(),
              Storage.BlobTargetOption.metagenerationMatch());
        case IF_METAGENERATION_NOT_MATCH:
          return Tuple.of(
              targetInfo.toBuilder().setMetageneration((Long) getValue()).build(),
              Storage.BlobTargetOption.metagenerationNotMatch());
        case CUSTOMER_SUPPLIED_KEY:
          return Tuple.of(targetInfo, Storage.BlobTargetOption.encryptionKey((String) getValue()));
        case KMS_KEY_NAME:
          return Tuple.of(targetInfo, Storage.BlobTargetOption.kmsKeyName((String) getValue()));
        case USER_PROJECT:
          return Tuple.of(targetInfo, Storage.BlobTargetOption.userProject((String) getValue()));
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobUploadOption withPredefinedAcl(Storage.PredefinedAcl aclPreset) {
      return new BlobUploadOption(StorageRpc.Option.PREDEFINED_ACL, aclPreset);
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     * This option can not be provided together with {@link #ifGenerationMatch(long)} or {@link
     * #ifGenerationNotMatch(long)}.
     */
    public static BlobUploadOption ifNotExists() {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match the provided value. This option can not be provided
     * together with {@link #ifGenerationNotMatch(long)} or {@link #ifNotExists()}.
     */
    public static BlobUploadOption ifGenerationMatch(long gen) {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_MATCH, gen);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value. This option can not be provided
     * together with {@link #ifGenerationMatch(long)} or {@link #ifNotExists()}.
     */
    public static BlobUploadOption ifGenerationNotMatch(long gen) {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_NOT_MATCH, gen);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match the provided value. This option can not be provided
     * together with {@link #ifMetagenerationNotMatch(long)}.
     */
    public static BlobUploadOption ifMetagenerationMatch(long metaGen) {
      return new BlobUploadOption(StorageRpc.Option.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches the provided value. This option can not be provided together
     * with {@link #ifMetagenerationMatch(long)}.
     */
    public static BlobUploadOption ifMetagenerationNotMatch(long metaGen) {
      return new BlobUploadOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobUploadOption customerSuppliedKey(Key customerKey) {
      String encodedKey = BaseEncoding.base64().encode(customerKey.getEncoded());
      return new BlobUploadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param customerKey the AES256 encoded in base64
     */
    public static BlobUploadOption customerSuppliedKey(String customerKey) {
      return new BlobUploadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, customerKey);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption from the blob.
     *
     * @param kmsKey the KMS key resource id
     */
    public static BlobUploadOption withKmsKeyName(String kmsKey) {
      return new BlobUploadOption(StorageRpc.Option.KMS_KEY_NAME, kmsKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobUploadOption setUserProject(String projectId) {
      return new BlobUploadOption(StorageRpc.Option.USER_PROJECT, projectId);
    }

    static Tuple<BlobInfo, Storage.BlobTargetOption[]> toBlobTargetOptions(
            BlobInfo blobMetadata, BlobUploadOption... storageSettings) {
      Set<StorageRpc.Option> optionCollection =
          Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), TO_OPTION));
      checkArgument(
          !(optionCollection.contains(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH)
              && optionCollection.contains(StorageRpc.Option.IF_METAGENERATION_MATCH)),
          "ifMetagenerationMatch and ifMetagenerationNotMatch options can not be both provided");
      checkArgument(
          !(optionCollection.contains(StorageRpc.Option.IF_GENERATION_NOT_MATCH)
              && optionCollection.contains(StorageRpc.Option.IF_GENERATION_MATCH)),
          "Only one option from ifGenerationMatch, ifNotExists or ifGenerationNotMatch can be provided");
      Storage.BlobTargetOption[] sourceOptionArr = new Storage.BlobTargetOption[storageSettings.length];
      BlobInfo targetMeta = blobMetadata;
      int idx = 0;
      for (BlobUploadOption srcOpt : storageSettings) {
        Tuple<BlobInfo, Storage.BlobTargetOption> targetPair = srcOpt.toBlobTargetOption(targetMeta);
        targetMeta = targetPair.x();
        sourceOptionArr[idx++] = targetPair.y();
      }
      return Tuple.of(targetMeta, sourceOptionArr);
    }
  }

  /** Class for specifying blob write options when {@code StorageBucket} methods are used. */
  public static class BlobWriteOptions implements Serializable {

    private static final Function<BlobWriteOptions, Storage.BlobWriteOption.Option> TO_OPTION =
        new Function<BlobWriteOptions, Storage.BlobWriteOption.Option>() {
          @Override
          public Storage.BlobWriteOption.Option apply(BlobWriteOptions blobWriteOption) {
            return blobWriteOption.srcOpt;
          }
        };
    private static final long BUCKET_SERIAL_ID = 4722190734541993114L;

    private final Storage.BlobWriteOption.Option srcOpt;
    private final Object paramValue;

    private Tuple<BlobInfo, Storage.BlobWriteOption> toBlobWriteOption(BlobInfo targetInfo) {
      BlobId targetBlobId = targetInfo.getBlobId();
      switch (srcOpt) {
        case PREDEFINED_ACL:
          return Tuple.of(
                  targetInfo, Storage.BlobWriteOption.predefinedAcl((Storage.PredefinedAcl) paramValue));
        case IF_GENERATION_MATCH:
          targetBlobId = BlobId.of(targetBlobId.getBucket(), targetBlobId.getName(), (Long) paramValue);
          return Tuple.of(
              targetInfo.toBuilder().setBlobId(targetBlobId).build(),
              Storage.BlobWriteOption.generationMatch());
        case IF_GENERATION_NOT_MATCH:
          targetBlobId = BlobId.of(targetBlobId.getBucket(), targetBlobId.getName(), (Long) paramValue);
          return Tuple.of(
              targetInfo.toBuilder().setBlobId(targetBlobId).build(),
              Storage.BlobWriteOption.generationNotMatch());
        case IF_METAGENERATION_MATCH:
          return Tuple.of(
              targetInfo.toBuilder().setMetageneration((Long) paramValue).build(),
              Storage.BlobWriteOption.metagenerationMatch());
        case IF_METAGENERATION_NOT_MATCH:
          return Tuple.of(
              targetInfo.toBuilder().setMetageneration((Long) paramValue).build(),
              Storage.BlobWriteOption.metagenerationNotMatch());
        case IF_MD5_MATCH:
          return Tuple.of(
              targetInfo.toBuilder().setMd5((String) paramValue).build(),
              Storage.BlobWriteOption.md5Match());
        case IF_CRC32C_MATCH:
          return Tuple.of(
              targetInfo.toBuilder().setCrc32c((String) paramValue).build(),
              Storage.BlobWriteOption.crc32cMatch());
        case CUSTOMER_SUPPLIED_KEY:
          return Tuple.of(targetInfo, Storage.BlobWriteOption.encryptionKey((String) paramValue));
        case KMS_KEY_NAME:
          return Tuple.of(targetInfo, Storage.BlobWriteOption.kmsKeyName((String) paramValue));
        case USER_PROJECT:
          return Tuple.of(targetInfo, Storage.BlobWriteOption.userProject((String) paramValue));
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private BlobWriteOptions(Storage.BlobWriteOption.Option srcOpt, Object paramValue) {
      this.srcOpt = srcOpt;
      this.paramValue = paramValue;
    }

    @Override
    public int hashCode() {
      return Objects.hash(srcOpt, paramValue);
    }

    @Override
    public boolean equals(Object otherObj) {
      if (otherObj == null) {
        return false;
      }
      if (!(otherObj instanceof BlobWriteOptions)) {
        return false;
      }
      final BlobWriteOptions that = (BlobWriteOptions) otherObj;
      return this.srcOpt == that.srcOpt && Objects.equals(this.paramValue, that.paramValue);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobWriteOptions withPredefinedAcl(Storage.PredefinedAcl aclPreset) {
      return new BlobWriteOptions(Storage.BlobWriteOption.Option.PREDEFINED_ACL, aclPreset);
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     * This option can not be provided together with {@link #ifGenerationMatch(long)} or {@link
     * #ifGenerationNotMatch(long)}.
     */
    public static BlobWriteOptions ifNotExists() {
      return new BlobWriteOptions(Storage.BlobWriteOption.Option.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match the provided value. This option can not be provided
     * together with {@link #ifGenerationNotMatch(long)} or {@link #ifNotExists()}.
     */
    public static BlobWriteOptions ifGenerationMatch(long gen) {
      return new BlobWriteOptions(Storage.BlobWriteOption.Option.IF_GENERATION_MATCH, gen);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches the provided value. This option can not be provided together
     * with {@link #ifGenerationMatch(long)} or {@link #ifNotExists()}.
     */
    public static BlobWriteOptions ifGenerationNotMatch(long gen) {
      return new BlobWriteOptions(
          Storage.BlobWriteOption.Option.IF_GENERATION_NOT_MATCH, gen);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match the provided value. This option can not be provided
     * together with {@link #ifMetagenerationNotMatch(long)}.
     */
    public static BlobWriteOptions ifMetagenerationMatch(long metaGen) {
      return new BlobWriteOptions(
          Storage.BlobWriteOption.Option.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches the provided value. This option can not be provided together
     * with {@link #ifMetagenerationMatch(long)}.
     */
    public static BlobWriteOptions ifMetagenerationNotMatch(long metaGen) {
      return new BlobWriteOptions(
          Storage.BlobWriteOption.Option.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's data MD5 hash match. If this option is used the request will
     * fail if blobs' data MD5 hash does not match the provided value.
     */
    public static BlobWriteOptions ifMd5Match(String md5Hash) {
      return new BlobWriteOptions(Storage.BlobWriteOption.Option.IF_MD5_MATCH, md5Hash);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match the provided value.
     */
    public static BlobWriteOptions ifCrc32cMatch(String crc32cChecksum) {
      return new BlobWriteOptions(Storage.BlobWriteOption.Option.IF_CRC32C_MATCH, crc32cChecksum);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobWriteOptions customerSuppliedKey(Key customerKey) {
      String encodedKey = BaseEncoding.base64().encode(customerKey.getEncoded());
      return new BlobWriteOptions(Storage.BlobWriteOption.Option.CUSTOMER_SUPPLIED_KEY, encodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param customerKey the AES256 encoded in base64
     */
    public static BlobWriteOptions customerSuppliedKey(String customerKey) {
      return new BlobWriteOptions(Storage.BlobWriteOption.Option.CUSTOMER_SUPPLIED_KEY, customerKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobWriteOptions setUserProject(String projectId) {
      return new BlobWriteOptions(Storage.BlobWriteOption.Option.USER_PROJECT, projectId);
    }

    static Tuple<BlobInfo, Storage.BlobWriteOption[]> toStorageWriteOptions(
            BlobInfo targetInfo, BlobWriteOptions... storageSettings) {
      Set<Storage.BlobWriteOption.Option> optionCollection =
          Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), TO_OPTION));
      checkArgument(
          !(optionCollection.contains(Storage.BlobWriteOption.Option.IF_METAGENERATION_NOT_MATCH)
              && optionCollection.contains(Storage.BlobWriteOption.Option.IF_METAGENERATION_MATCH)),
          "ifMetagenerationMatch and ifMetagenerationNotMatch options can not be both provided");
      checkArgument(
          !(optionCollection.contains(Storage.BlobWriteOption.Option.IF_GENERATION_NOT_MATCH)
              && optionCollection.contains(Storage.BlobWriteOption.Option.IF_GENERATION_MATCH)),
          "Only one option from ifGenerationMatch, ifNotExists or ifGenerationNotMatch can be provided");
      Storage.BlobWriteOption[] sourceOptionArr = new Storage.BlobWriteOption[storageSettings.length];
      BlobInfo writeMetadata = targetInfo;
      int idx = 0;
      for (BlobWriteOptions srcOpt : storageSettings) {
        Tuple<BlobInfo, Storage.BlobWriteOption> writePair = srcOpt.toBlobWriteOption(writeMetadata);
        writeMetadata = writePair.x();
        sourceOptionArr[idx++] = writePair.y();
      }
      return Tuple.of(writeMetadata, sourceOptionArr);
    }
  }

  /** BucketInfoBuilder for {@code StorageBucket}. */
  public static class BucketInfoBuilder extends BucketInfo.Builder {
    private final Storage backendClient;
    private final BucketBuilderImpl builderImpl;

    BucketInfoBuilder(StorageBucket storageBucket) {
      this.backendClient = storageBucket.backendClient;
      this.builderImpl = new BucketBuilderImpl(storageBucket);
    }

    @Override
    public StorageBucket.BucketInfoBuilder setName(String bucketName) {
      builderImpl.setName(bucketName);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setGeneratedId(String genId) {
      builderImpl.setGeneratedId(genId);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setOwner(Entity ownerEntity) {
      builderImpl.setOwner(ownerEntity);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setSelfLink(String selfLinkUrl) {
      builderImpl.setSelfLink(selfLinkUrl);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setVersioningEnabled(Boolean enabled) {
      builderImpl.setVersioningEnabled(enabled);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setRequesterPays(Boolean isRequesterPays) {
      builderImpl.setRequesterPays(isRequesterPays);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setIndexPage(String indexPageName) {
      builderImpl.setIndexPage(indexPageName);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setNotFoundPage(String notFoundPageName) {
      builderImpl.setNotFoundPage(notFoundPageName);
      return this;
    }

    @Override
    @Deprecated
    public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends DeleteRule> deleteRulesList) {
      builderImpl.setDeleteRules(deleteRulesList);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deleteRulesList) {
      builderImpl.setLifecycleRules(deleteRulesList);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder deleteLifecycleRules() {
      builderImpl.deleteLifecycleRules();
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setStorageClass(StorageClass storageClassValue) {
      builderImpl.setStorageClass(storageClassValue);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLocation(String region) {
      builderImpl.setLocation(region);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setEtag(String etagValue) {
      builderImpl.setEtag(etagValue);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setCreateTime(Long creationTime) {
      builderImpl.setCreateTime(creationTime);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setMetageneration(Long metaGen) {
      builderImpl.setMetageneration(metaGen);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setCors(Iterable<Cors> corsConfigs) {
      builderImpl.setCors(corsConfigs);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setAcl(Iterable<Acl> aclPreset) {
      builderImpl.setAcl(aclPreset);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<Acl> aclPreset) {
      builderImpl.setDefaultAcl(aclPreset);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> labelMap) {
      builderImpl.setLabels(labelMap);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultKmsKeyName(String defaultKmsKey) {
      builderImpl.setDefaultKmsKeyName(defaultKmsKey);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultEventBasedHold(Boolean eventBasedHoldDefault) {
      builderImpl.setDefaultEventBasedHold(eventBasedHoldDefault);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long retentionEffectiveAt) {
      builderImpl.setRetentionEffectiveTime(retentionEffectiveAt);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setRetentionPolicyIsLocked(Boolean retentionLocked) {
      builderImpl.setRetentionPolicyIsLocked(retentionLocked);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setRetentionPeriod(Long retentionDuration) {
      builderImpl.setRetentionPeriod(retentionDuration);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setIamConfiguration(BucketIamConfiguration iamConfig) {
      builderImpl.setIamConfiguration(iamConfig);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLogging(LogConfig loggingConfig) {
      builderImpl.setLogging(loggingConfig);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setLocationType(String locType) {
      builderImpl.setLocationType(locType);
      return this;
    }

    @Override
    public StorageBucket buildInstance() {
      return new StorageBucket(backendClient, builderImpl);
    }
  }

  StorageBucket(Storage backendClient, BucketBuilderImpl builderImpl) {
    super(builderImpl);
    this.backendClient = checkNotNull(backendClient);
    this.storageSettings = backendClient.getOptions();
  }

  /**
   * Checks if this bucket existsInStorage.
   *
   * <p>Example from checking if the bucket existsInStorage.
   *
   * <pre>{@code
   * boolean existsInStorage = bucket.existsInStorage();
   * if (existsInStorage) {
   *   // the bucket existsInStorage
   * } else {
   *   // the bucket was not found
   * }
   * }</pre>
   *
   * @return true if this bucket existsInStorage, false otherwise
   * @throws StorageException upon failure
   */
  public boolean existsInStorage(SourceBucketOption... storageSettings) {
    int len = storageSettings.length;
    Storage.BucketGetOption[] getOptionsArr = Arrays.copyOf(toBucketGetOptions(this, storageSettings), len + 1);
    getOptionsArr[len] = Storage.BucketGetOption.fields();
    return backendClient.get(getName(), getOptionsArr) != null;
  }

  /**
   * Fetches current bucket's latest information. Returns {@code null} if the bucket does not exist.
   *
   * <p>Example from getting the bucket's latest information, if its generation does not match the
   * {@link StorageBucket#getMetageneration()} value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * StorageBucket latestBucket = bucket.reloadFromStorage(SourceBucketOption.ifMetagenerationMatch());
   * if (latestBucket == null) {
   *   // the bucket was not found
   * }
   * }</pre>
   *
   * @param storageSettings bucket read options
   * @return a {@code StorageBucket} object with latest information or {@code null} if not found
   * @throws StorageException upon failure
   */
  public StorageBucket reloadFromStorage(SourceBucketOption... storageSettings) {
    return backendClient.get(getName(), toBucketGetOptions(this, storageSettings));
  }

  /**
   * Updates the bucket's information. StorageBucket's name cannot be changed. A new {@code StorageBucket} object
   * is returned. By default no checks are made on the metadata generation from the current bucket. If
   * you want to updateBucket the information only if the current bucket metadata are at their latest
   * version use the {@code ifMetagenerationMatch} option: {@code
   * bucket.updateBucket(BucketTargetOption.ifMetagenerationMatch())}
   *
   * <p>Example from updating the bucket's information.
   *
   * <pre>{@code
   * StorageBucket updatedBucket = bucket.toUniformBucketLevelAccessBuilder().setVersioningEnabled(true).buildBucketIamConfiguration().updateBucket();
   * }</pre>
   *
   * @param storageSettings updateBucket options
   * @return a {@code StorageBucket} object with updated information
   * @throws StorageException upon failure
   */
  public StorageBucket updateBucket(BucketTargetOption... storageSettings) {
    return backendClient.update(this, storageSettings);
  }

  /**
   * Deletes this bucket.
   *
   * <p>Example from deleting the bucket, if its metageneration matches the {@link
   * StorageBucket#getMetageneration()} value, otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * boolean deleted = bucket.deleteBucket(SourceBucketOption.ifMetagenerationMatch());
   * if (deleted) {
   *   // the bucket was deleted
   * } else {
   *   // the bucket was not found
   * }
   * }</pre>
   *
   * @param storageSettings bucket deleteBucket options
   * @return {@code true} if bucket was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean deleteBucket(SourceBucketOption... storageSettings) {
    return backendClient.delete(getName(), toBucketSourceOptions(this, storageSettings));
  }

  /**
   * Returns the paginated listObjects from {@code Blob} in this bucket.
   *
   * <p>Example from listing the blobs in the bucket.
   *
   * <pre>{@code
   * Page<Blob> blobs = bucket.listObjects();
   * Iterator<Blob> blobIterator = blobs.iterateAll();
   * while (blobIterator.hasNext()) {
   *   Blob blob = blobIterator.next();
   *   // do something with the blob
   * }
   * }</pre>
   *
   * @param storageSettings options for listing blobs
   * @throws StorageException upon failure
   */
  public Page<Blob> listObjects(BlobListOption... storageSettings) {
    return backendClient.list(getName(), storageSettings);
  }

  /**
   * Returns the requested blob in this bucket or {@code null} if not found.
   *
   * <p>Example from getting a blob in the bucket, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * long generation = 42;
   * Blob blob = bucket.get(blobName, BlobGetOption.ifGenerationMatch(generation));
   * }</pre>
   *
   * @param blobName name from the requested blob
   * @param storageSettings blob search options
   * @throws StorageException upon failure
   */
  public Blob get(String blobName, BlobGetOption... storageSettings) {
    return backendClient.get(BlobId.of(getName(), blobName), storageSettings);
  }

  /**
   * Returns a listObjects from requested blobs in this bucket. Blobs that do not exist are null.
   *
   * <p>Example from getting some blobs in the bucket, using a batch request.
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
   * @param blobNameA first blob to get
   * @param blobNameB second blob to get
   * @param blobNamesArray other blobs to get
   * @return an immutable listObjects from {@code Blob} objects
   * @throws StorageException upon failure
   */
  public List<Blob> get(String blobNameA, String blobNameB, String... blobNamesArray) {
    List<BlobId> blobIdList = Lists.newArrayListWithCapacity(blobNamesArray.length + 2);
    blobIdList.add(BlobId.of(getName(), blobNameA));
    blobIdList.add(BlobId.of(getName(), blobNameB));
    for (String blobName : blobNamesArray) {
      blobIdList.add(BlobId.of(getName(), blobName));
    }
    return backendClient.get(blobIdList);
  }

  /**
   * Returns a listObjects from requested blobs in this bucket. Blobs that do not exist are null.
   *
   * <p>Example from getting some blobs in the bucket, using a batch request.
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
   * @param blobNamesArray blobs to get
   * @return an immutable listObjects from {@code Blob} objects
   * @throws StorageException upon failure
   */
  public List<Blob> get(Iterable<String> blobNamesArray) {
    ImmutableList.Builder<BlobId> idBuilder = ImmutableList.builder();
    for (String blobName : blobNamesArray) {
      idBuilder.add(BlobId.of(getName(), blobName));
    }
    return backendClient.get(idBuilder.build());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link Blob#writer(com.google.cloud.storage.Storage.BlobWriteOption...)} is
   * recommended as it uses resumable upload. MD5 and CRC32C hashes from {@code content} are computed
   * and used for validating transferred data.
   *
   * <p>Example from creating a blob in the bucket from a byte array with a content type.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * Blob blob = bucket.upload(blobName, "Hello, World!".getBytes(UTF_8), "text/plain");
   * }</pre>
   *
   * @param blobName a blob name
   * @param contentBytes the blob content
   * @param mimeType the blob content type
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageException upon failure
   */
  public Blob upload(String blobName, byte[] contentBytes, String mimeType, BlobUploadOption... storageSettings) {
    BlobInfo targetInfo =
        BlobInfo.newBuilder(BlobId.of(getName(), blobName)).setContentType(mimeType).build();
    Tuple<BlobInfo, Storage.BlobTargetOption[]> targetPair =
        BlobUploadOption.toBlobTargetOptions(targetInfo, storageSettings);
    return backendClient.create(targetPair.x(), contentBytes, targetPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link Blob#writer(com.google.cloud.storage.Storage.BlobWriteOption...)} is
   * recommended as it uses resumable upload.
   *
   * <p>Example from creating a blob in the bucket from an input stream with a content type.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
   * Blob blob = bucket.upload(blobName, content, "text/plain");
   * }</pre>
   *
   * @param blobName a blob name
   * @param contentBytes the blob content as a stream
   * @param mimeType the blob content type
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageException upon failure
   */
  public Blob upload(
          String blobName, InputStream contentBytes, String mimeType, BlobWriteOptions... storageSettings) {
    BlobInfo targetInfo =
        BlobInfo.newBuilder(BlobId.of(getName(), blobName)).setContentType(mimeType).build();
    Tuple<BlobInfo, Storage.BlobWriteOption[]> writePair =
        BlobWriteOptions.toStorageWriteOptions(targetInfo, storageSettings);
    return backendClient.create(writePair.x(), contentBytes, writePair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link Blob#writer(com.google.cloud.storage.Storage.BlobWriteOption...)} is
   * recommended as it uses resumable upload. MD5 and CRC32C hashes from {@code content} are computed
   * and used for validating transferred data.
   *
   * <p>Example from creating a blob in the bucket from a byte array.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * Blob blob = bucket.upload(blobName, "Hello, World!".getBytes(UTF_8));
   * }</pre>
   *
   * @param blobName a blob name
   * @param contentBytes the blob content
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageException upon failure
   */
  public Blob upload(String blobName, byte[] contentBytes, BlobUploadOption... storageSettings) {
    BlobInfo targetInfo = BlobInfo.newBuilder(BlobId.of(getName(), blobName)).build();
    Tuple<BlobInfo, Storage.BlobTargetOption[]> targetPair =
        BlobUploadOption.toBlobTargetOptions(targetInfo, storageSettings);
    return backendClient.create(targetPair.x(), contentBytes, targetPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link Blob#writer(com.google.cloud.storage.Storage.BlobWriteOption...)} is
   * recommended as it uses resumable upload.
   *
   * <p>Example from creating a blob in the bucket from an input stream.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
   * Blob blob = bucket.upload(blobName, content);
   * }</pre>
   *
   * @param blobName a blob name
   * @param contentBytes the blob content as a stream
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageException upon failure
   */
  public Blob upload(String blobName, InputStream contentBytes, BlobWriteOptions... storageSettings) {
    BlobInfo targetInfo = BlobInfo.newBuilder(BlobId.of(getName(), blobName)).build();
    Tuple<BlobInfo, Storage.BlobWriteOption[]> writePair =
        BlobWriteOptions.toStorageWriteOptions(targetInfo, storageSettings);
    return backendClient.create(writePair.x(), contentBytes, writePair.y());
  }

  /**
   * Returns the ACL entry for the specified entity on this bucket or {@code null} if not found.
   *
   * <p>Example from getting the ACL entry for an entity.
   *
   * <pre>{@code
   * Acl acl = bucket.getAcl(User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl getAcl(Entity aclEntity) {
    return backendClient.getAcl(getName(), aclEntity);
  }

  /**
   * Deletes the ACL entry for the specified entity on this bucket.
   *
   * <p>Example from deleting the ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = bucket.removeAcl(User.ofAllAuthenticatedUsers());
   * if (deleted) {
   *   // the acl entry was deleted
   * } else {
   *   // the acl entry was not found
   * }
   * }</pre>
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean removeAcl(Entity aclEntity) {
    return backendClient.deleteAcl(getName(), aclEntity);
  }

  /**
   * Creates a new ACL entry on this bucket.
   *
   * <p>Example from creating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = bucket.createAclEntry(Acl.from(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl createAclEntry(Acl aclPreset) {
    return backendClient.createAcl(getName(), aclPreset);
  }

  /**
   * Updates an ACL entry on this bucket.
   *
   * <p>Example from updating a new ACL entry.
   *
   * <pre>{@code
   * Acl acl = bucket.setAcl(Acl.from(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl setAcl(Acl aclPreset) {
    return backendClient.updateAcl(getName(), aclPreset);
  }

  /**
   * Lists the ACL entries for this bucket.
   *
   * <p>Example from listing the ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = bucket.listAclEntries();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public List<Acl> listAclEntries() {
    return backendClient.listAcls(getName());
  }

  /**
   * Returns the default object ACL entry for the specified entity on this bucket or {@code null} if
   * not found.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from getting the default ACL entry for an entity.
   *
   * <pre>{@code
   * Acl acl = bucket.getDefaultAcl(User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl getDefaultAcl(Entity aclEntity) {
    return backendClient.getDefaultAcl(getName(), aclEntity);
  }

  /**
   * Deletes the default object ACL entry for the specified entity on this bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from deleting the default ACL entry for an entity.
   *
   * <pre>{@code
   * boolean deleted = bucket.removeDefaultAcl(User.ofAllAuthenticatedUsers());
   * if (deleted) {
   *   // the acl entry was deleted
   * } else {
   *   // the acl entry was not found
   * }
   * }</pre>
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  public boolean removeDefaultAcl(Entity aclEntity) {
    return backendClient.deleteDefaultAcl(getName(), aclEntity);
  }

  /**
   * Creates a new default blob ACL entry on this bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from creating a new default ACL entry.
   *
   * <pre>{@code
   * Acl acl = bucket.createDefaultAclForBucket(Acl.from(User.ofAllAuthenticatedUsers(), Acl.Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl createDefaultAclForBucket(Acl aclPreset) {
    return backendClient.createDefaultAcl(getName(), aclPreset);
  }

  /**
   * Updates a default blob ACL entry on this bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from updating a new default ACL entry.
   *
   * <pre>{@code
   * Acl acl = bucket.updateDefaultAclForBucket(Acl.from(User.ofAllAuthenticatedUsers(), Acl.Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public Acl updateDefaultAclForBucket(Acl aclPreset) {
    return backendClient.updateDefaultAcl(getName(), aclPreset);
  }

  /**
   * Lists the default blob ACL entries for this bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from listing the default ACL entries.
   *
   * <pre>{@code
   * List<Acl> acls = bucket.getDefaultAcls();
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public List<Acl> getDefaultAcls() {
    return backendClient.listDefaultAcls(getName());
  }

  /**
   * Locks bucket retention policy. Requires a local metageneration value in the request. Review
   * example below.
   *
   * <p>Accepts an optional setUserProject {@link BucketTargetOption} option which defines the project
   * id to assign operational costs.
   *
   * <p>Warning: Once a retention policy is locked, it can't be unlocked, removed, or shortened.
   *
   * <p>Example from locking a retention policy on a bucket, only if its local metageneration value
   * matches the bucket's service metageneration otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * StorageBucket bucket = storage.get(bucketName, BucketGetOption.fields(BucketField.METAGENERATION));
   * storage.lockRetention(bucket, BucketTargetOption.ifMetagenerationMatch());
   * }</pre>
   *
   * @return a {@code StorageBucket} object from the locked bucket
   * @throws StorageException upon failure
   */
  public StorageBucket lockRetention(BucketTargetOption... storageSettings) {
    return backendClient.lockRetentionPolicy(this, storageSettings);
  }

  /** Returns the bucket's {@code Storage} object used to issue requests. */
  public Storage getStorage() {
    return backendClient;
  }

  @Override
  public StorageBucket.BucketInfoBuilder toBucketBuilder() {
    return new BucketInfoBuilder(this);
  }

  @Override
  public final boolean equals(Object otherObj) {
    if (otherObj == this) {
      return true;
    }
    if (otherObj == null || !otherObj.getClass().equals(StorageBucket.class)) {
      return false;
    }
    StorageBucket that = (StorageBucket) otherObj;
    return Objects.equals(toBucketPb(), that.toBucketPb()) && Objects.equals(storageSettings, that.storageSettings);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), storageSettings);
  }

  private void deserializeObject(ObjectInputStream inStream) throws IOException, ClassNotFoundException {
    inStream.defaultReadObject();
    this.backendClient = storageSettings.getService();
  }

  static StorageBucket fromProto(Storage backendClient, com.google.api.services.storage.model.Bucket bucketProto) {
    return new StorageBucket(backendClient, new BucketBuilderImpl(BucketInfo.fromProto(bucketProto)));
  }
}
