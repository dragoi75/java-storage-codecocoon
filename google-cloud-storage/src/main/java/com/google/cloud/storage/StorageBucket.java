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

import static com.google.cloud.storage.StorageBucket.BucketSourceOptions.toGetOptionArray;
import static com.google.cloud.storage.StorageBucket.BucketSourceOptions.toSourceOptionArray;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
import com.google.cloud.storage.AclEntry.TypedEntity;
import com.google.cloud.storage.StorageClient.BlobListOptions;
import com.google.cloud.storage.StorageClient.BucketTargetOptions;
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient;
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
 * <p>Objects of this class are immutable. Operations that modify the bucket like {@link #modify}
 * return a new object. To get a {@code Bucket} object with the most recent information use {@link
 * #refresh}. {@code Bucket} adds a layer of service-related functionality over {@link BucketInfo}.
 */
public class StorageBucket extends BucketInfo {

  private static final long serialVersionUID = 8574601739542252586L;

  private final StorageSettings storageSettings;
  private transient StorageClient storageClient;

  /** Class for specifying bucket source options when {@code Bucket} methods are used. */
  public static class BucketSourceOptions extends RpcOptionEntry {

    private static final long serialVersionUID = 6928872234155522371L;

    private BucketSourceOptions(CloudStorageRpcClient.StorageOption storageOption) {
      super(storageOption, null);
    }

    private BucketSourceOptions(CloudStorageRpcClient.StorageOption storageOption, Object obj) {
      super(storageOption, obj);
    }

    private StorageClient.BucketSourceRequestOption asSourceOption(BucketInfo bucketAttributes) {
      switch (getRpcOption()) {
        case IF_METAGENERATION_MATCH:
          return StorageClient.BucketSourceRequestOption.withMetagenerationMatch(bucketAttributes.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return StorageClient.BucketSourceRequestOption.withMetagenerationNotMatch(bucketAttributes.getMetageneration());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private StorageClient.BucketGetOptions asGetOption(BucketInfo bucketAttributes) {
      switch (getRpcOption()) {
        case IF_METAGENERATION_MATCH:
          return StorageClient.BucketGetOptions.withMetagenerationMatch(bucketAttributes.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return StorageClient.BucketGetOptions.withMetagenerationNotMatch(bucketAttributes.getMetageneration());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BucketSourceOptions requireMetagenerationMatch() {
      return new BucketSourceOptions(CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static BucketSourceOptions metagenerationNotMatch() {
      return new BucketSourceOptions(CloudStorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketSourceOptions userProject(String projectId) {
      return new BucketSourceOptions(CloudStorageRpcClient.StorageOption.USER_PROJECT, projectId);
    }

    static StorageClient.BucketSourceRequestOption[] toSourceOptionArray(
            BucketInfo bucketAttributes, BucketSourceOptions... storageSettings) {
      StorageClient.BucketSourceRequestOption[] convertedRequests =
          new StorageClient.BucketSourceRequestOption[storageSettings.length];
      int idx = 0;
      for (BucketSourceOptions opt : storageSettings) {
        convertedRequests[idx++] = opt.asSourceOption(bucketAttributes);
      }
      return convertedRequests;
    }

    static StorageClient.BucketGetOptions[] toGetOptionArray(
            BucketInfo bucketAttributes, BucketSourceOptions... storageSettings) {
      StorageClient.BucketGetOptions[] convertedRequests = new StorageClient.BucketGetOptions[storageSettings.length];
      int idx = 0;
      for (BucketSourceOptions opt : storageSettings) {
        convertedRequests[idx++] = opt.asGetOption(bucketAttributes);
      }
      return convertedRequests;
    }
  }

  /** Class for specifying blob target options when {@code Bucket} methods are used. */
  public static class BlobUploadOption extends RpcOptionEntry {

    private static final Function<BlobUploadOption, CloudStorageRpcClient.StorageOption> TO_ENUM_FUNC =
        new Function<BlobUploadOption, CloudStorageRpcClient.StorageOption>() {
          @Override
          public CloudStorageRpcClient.StorageOption apply(BlobUploadOption blobTargetOption) {
            return blobTargetOption.getRpcOption();
          }
        };
    private static final long serialVersionUID = 8345296337342509425L;

    private BlobUploadOption(CloudStorageRpcClient.StorageOption storageOption, Object obj) {
      super(storageOption, obj);
    }

    private Tuple<BlobAttributes, StorageClient.BlobUploadOption> asTargetOption(BlobAttributes blobAttributes) {
      BlobIdentifier blobIdentifier = blobAttributes.getBlobId();
      switch (getRpcOption()) {
        case PREDEFINED_ACL:
          return Tuple.of(
                  blobAttributes, StorageClient.BlobUploadOption.withPredefinedAcl((StorageClient.PredefinedAccessControlList) getValue()));
        case IF_GENERATION_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
          return Tuple.of(
              blobAttributes.asBuilder().setBlobId(blobIdentifier).buildObject(),
              StorageClient.BlobUploadOption.withGenerationMatch());
        case IF_GENERATION_NOT_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
          return Tuple.of(
              blobAttributes.asBuilder().setBlobId(blobIdentifier).buildObject(),
              StorageClient.BlobUploadOption.withGenerationNotMatch());
        case IF_METAGENERATION_MATCH:
          return Tuple.of(
              blobAttributes.asBuilder().setMetageneration((Long) getValue()).buildObject(),
              StorageClient.BlobUploadOption.withMetagenerationMatch());
        case IF_METAGENERATION_NOT_MATCH:
          return Tuple.of(
              blobAttributes.asBuilder().setMetageneration((Long) getValue()).buildObject(),
              StorageClient.BlobUploadOption.withMetagenerationNotMatch());
        case CUSTOMER_SUPPLIED_KEY:
          return Tuple.of(blobAttributes, StorageClient.BlobUploadOption.withEncryptionKey((String) getValue()));
        case KMS_KEY_NAME:
          return Tuple.of(blobAttributes, StorageClient.BlobUploadOption.withKmsKeyName((String) getValue()));
        case USER_PROJECT:
          return Tuple.of(blobAttributes, StorageClient.BlobUploadOption.withUserProject((String) getValue()));
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobUploadOption withPredefinedAcl(StorageClient.PredefinedAccessControlList accessControlList) {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.PREDEFINED_ACL, accessControlList);
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     * This option can not be provided together with {@link #requireGenerationMatch(long)} or {@link
     * #requireGenerationNotMatch(long)}.
     */
    public static BlobUploadOption ifDoesNotExist() {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match the provided value. This option can not be provided
     * together with {@link #requireGenerationNotMatch(long)} or {@link #ifDoesNotExist()}.
     */
    public static BlobUploadOption requireGenerationMatch(long gen) {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH, gen);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value. This option can not be provided
     * together with {@link #requireGenerationMatch(long)} or {@link #ifDoesNotExist()}.
     */
    public static BlobUploadOption requireGenerationNotMatch(long gen) {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, gen);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match the provided value. This option can not be provided
     * together with {@link #requireMetagenerationNotMatch(long)}.
     */
    public static BlobUploadOption requireMetagenerationMatch(long metaGen) {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches the provided value. This option can not be provided together
     * with {@link #requireMetagenerationMatch(long)}.
     */
    public static BlobUploadOption requireMetagenerationNotMatch(long metaGen) {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobUploadOption withEncryptionKey(Key encryptionKeyObj) {
      String base64EncodedKey = BaseEncoding.base64().encode(encryptionKeyObj.getEncoded());
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param encryptionKeyObj the AES256 encoded in base64
     */
    public static BlobUploadOption withEncryptionKey(String encryptionKeyObj) {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKeyObj);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
     *
     * @param kmsKey the KMS key resource id
     */
    public static BlobUploadOption withKmsKeyName(String kmsKey) {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.KMS_KEY_NAME, kmsKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobUploadOption withUserProject(String projectId) {
      return new BlobUploadOption(CloudStorageRpcClient.StorageOption.USER_PROJECT, projectId);
    }

    static Tuple<BlobAttributes, StorageClient.BlobUploadOption[]> toTargetOptionArray(
            BlobAttributes blobAttributes, BlobUploadOption... storageSettings) {
      Set<CloudStorageRpcClient.StorageOption> storageSet =
          Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), TO_ENUM_FUNC));
      checkArgument(
          !(storageSet.contains(CloudStorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH)
              && storageSet.contains(CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH)),
          "metagenerationMatch and metagenerationNotMatch options can not be both provided");
      checkArgument(
          !(storageSet.contains(CloudStorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH)
              && storageSet.contains(CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH)),
          "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
      StorageClient.BlobUploadOption[] convertedRequests = new StorageClient.BlobUploadOption[storageSettings.length];
      BlobAttributes destinationAttributes = blobAttributes;
      int idx = 0;
      for (BlobUploadOption opt : storageSettings) {
        Tuple<BlobAttributes, StorageClient.BlobUploadOption> destinationPair = opt.asTargetOption(destinationAttributes);
        destinationAttributes = destinationPair.x();
        convertedRequests[idx++] = destinationPair.y();
      }
      return Tuple.of(destinationAttributes, convertedRequests);
    }
  }

  /** Class for specifying blob write options when {@code Bucket} methods are used. */
  public static class BlobWriteOptions implements Serializable {

    private static final Function<BlobWriteOptions, StorageClient.BlobWriteOptions.StorageOption> TO_ENUM_FUNC =
        new Function<BlobWriteOptions, StorageClient.BlobWriteOptions.StorageOption>() {
          @Override
          public StorageClient.BlobWriteOptions.StorageOption apply(BlobWriteOptions blobWriteOption) {
            return blobWriteOption.opt;
          }
        };
    private static final long serialVersionUID = 4722190734541993114L;

    private final StorageClient.BlobWriteOptions.StorageOption opt;
    private final Object obj;

    private Tuple<BlobAttributes, StorageClient.BlobWriteOptions> asWriteOption(BlobAttributes blobAttributes) {
      BlobIdentifier blobIdentifier = blobAttributes.getBlobId();
      switch (opt) {
        case PREDEFINED_ACL:
          return Tuple.of(
                  blobAttributes, StorageClient.BlobWriteOptions.withPredefinedAcl((StorageClient.PredefinedAccessControlList) obj));
        case IF_GENERATION_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) obj);
          return Tuple.of(
              blobAttributes.asBuilder().setBlobId(blobIdentifier).buildObject(),
              StorageClient.BlobWriteOptions.ifGenerationMatch());
        case IF_GENERATION_NOT_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) obj);
          return Tuple.of(
              blobAttributes.asBuilder().setBlobId(blobIdentifier).buildObject(),
              StorageClient.BlobWriteOptions.ifGenerationNotMatch());
        case IF_METAGENERATION_MATCH:
          return Tuple.of(
              blobAttributes.asBuilder().setMetageneration((Long) obj).buildObject(),
              StorageClient.BlobWriteOptions.ifMetagenerationMatch());
        case IF_METAGENERATION_NOT_MATCH:
          return Tuple.of(
              blobAttributes.asBuilder().setMetageneration((Long) obj).buildObject(),
              StorageClient.BlobWriteOptions.ifMetagenerationNotMatch());
        case IF_MD5_MATCH:
          return Tuple.of(
              blobAttributes.asBuilder().setMd5((String) obj).buildObject(),
              StorageClient.BlobWriteOptions.ifMd5Match());
        case IF_CRC32C_MATCH:
          return Tuple.of(
              blobAttributes.asBuilder().setCrc32c((String) obj).buildObject(),
              StorageClient.BlobWriteOptions.ifCrc32cMatch());
        case CUSTOMER_SUPPLIED_KEY:
          return Tuple.of(blobAttributes, StorageClient.BlobWriteOptions.customerSuppliedKey((String) obj));
        case KMS_KEY_NAME:
          return Tuple.of(blobAttributes, StorageClient.BlobWriteOptions.kmsKey((String) obj));
        case USER_PROJECT:
          return Tuple.of(blobAttributes, StorageClient.BlobWriteOptions.withUserProject((String) obj));
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption opt, Object obj) {
      this.opt = opt;
      this.obj = obj;
    }

    @Override
    public int hashCode() {
      return Objects.hash(opt, obj);
    }

    @Override
    public boolean equals(Object otherObject) {
      if (otherObject == null) {
        return false;
      }
      if (!(otherObject instanceof BlobWriteOptions)) {
        return false;
      }
      final BlobWriteOptions thatInstance = (BlobWriteOptions) otherObject;
      return this.opt == thatInstance.opt && Objects.equals(this.obj, thatInstance.obj);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobWriteOptions withPredefinedAcl(StorageClient.PredefinedAccessControlList accessControlList) {
      return new BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption.PREDEFINED_ACL, accessControlList);
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     * This option can not be provided together with {@link #requireGenerationMatch(long)} or {@link
     * #requireGenerationNotMatch(long)}.
     */
    public static BlobWriteOptions ifDoesNotExist() {
      return new BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match the provided value. This option can not be provided
     * together with {@link #requireGenerationNotMatch(long)} or {@link #ifDoesNotExist()}.
     */
    public static BlobWriteOptions requireGenerationMatch(long gen) {
      return new BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, gen);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches the provided value. This option can not be provided together
     * with {@link #requireGenerationMatch(long)} or {@link #ifDoesNotExist()}.
     */
    public static BlobWriteOptions requireGenerationNotMatch(long gen) {
      return new BlobWriteOptions(
          StorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH, gen);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match the provided value. This option can not be provided
     * together with {@link #requireMetagenerationNotMatch(long)}.
     */
    public static BlobWriteOptions requireMetagenerationMatch(long metaGen) {
      return new BlobWriteOptions(
          StorageClient.BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches the provided value. This option can not be provided together
     * with {@link #requireMetagenerationMatch(long)}.
     */
    public static BlobWriteOptions requireMetagenerationNotMatch(long metaGen) {
      return new BlobWriteOptions(
          StorageClient.BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's data MD5 hash match. If this option is used the request will
     * fail if blobs' data MD5 hash does not match the provided value.
     */
    public static BlobWriteOptions requireMd5Match(String md5Checksum) {
      return new BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption.IF_MD5_MATCH, md5Checksum);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match the provided value.
     */
    public static BlobWriteOptions requireCrc32cMatch(String crcChecksum) {
      return new BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption.IF_CRC32C_MATCH, crcChecksum);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobWriteOptions withEncryptionKey(Key encryptionKeyObj) {
      String base64EncodedKey = BaseEncoding.base64().encode(encryptionKeyObj.getEncoded());
      return new BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param encryptionKeyObj the AES256 encoded in base64
     */
    public static BlobWriteOptions withEncryptionKey(String encryptionKeyObj) {
      return new BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKeyObj);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobWriteOptions withUserProject(String projectId) {
      return new BlobWriteOptions(StorageClient.BlobWriteOptions.StorageOption.USER_PROJECT, projectId);
    }

    static Tuple<BlobAttributes, StorageClient.BlobWriteOptions[]> toWriteOptionArray(
            BlobAttributes blobAttributes, BlobWriteOptions... storageSettings) {
      Set<StorageClient.BlobWriteOptions.StorageOption> storageSet =
          Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), TO_ENUM_FUNC));
      checkArgument(
          !(storageSet.contains(StorageClient.BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH)
              && storageSet.contains(StorageClient.BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH)),
          "metagenerationMatch and metagenerationNotMatch options can not be both provided");
      checkArgument(
          !(storageSet.contains(StorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH)
              && storageSet.contains(StorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH)),
          "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
      StorageClient.BlobWriteOptions[] convertedRequests = new StorageClient.BlobWriteOptions[storageSettings.length];
      BlobAttributes writeAttributes = blobAttributes;
      int idx = 0;
      for (BlobWriteOptions opt : storageSettings) {
        Tuple<BlobAttributes, StorageClient.BlobWriteOptions> writePair = opt.asWriteOption(writeAttributes);
        writeAttributes = writePair.x();
        convertedRequests[idx++] = writePair.y();
      }
      return Tuple.of(writeAttributes, convertedRequests);
    }
  }

  /** Builder for {@code Bucket}. */
  public static class BucketInfoBuilder extends BucketInfo.Builder {
    private final StorageClient storageClient;
    private final BucketBuilderImpl bucketBuilderImpl;

    BucketInfoBuilder(StorageBucket storageBucket) {
      this.storageClient = storageBucket.storageClient;
      this.bucketBuilderImpl = new BucketBuilderImpl(storageBucket);
    }

    @Override
    public StorageBucket.BucketInfoBuilder setName(String bucketName) {
      bucketBuilderImpl.setName(bucketName);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setGeneratedId(String generatedIdentifier) {
      bucketBuilderImpl.setGeneratedId(generatedIdentifier);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setOwner(AclEntry.TypedEntity entityPrincipal) {
      bucketBuilderImpl.setOwner(entityPrincipal);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setSelfLink(String resourceUrl) {
      bucketBuilderImpl.setSelfLink(resourceUrl);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setVersioningEnabled(Boolean versioningFlag) {
      bucketBuilderImpl.setVersioningEnabled(versioningFlag);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setRequesterPays(Boolean chargeOnAccess) {
      bucketBuilderImpl.setRequesterPays(chargeOnAccess);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setIndexPage(String landingDocument) {
      bucketBuilderImpl.setIndexPage(landingDocument);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setNotFoundPage(String error404Page) {
      bucketBuilderImpl.setNotFoundPage(error404Page);
      return this;
    }

    @Override
    @Deprecated
    public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends DeletionRule> deletionPolicies) {
      bucketBuilderImpl.setDeleteRules(deletionPolicies);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecyclePolicy> deletionPolicies) {
      bucketBuilderImpl.setLifecycleRules(deletionPolicies);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder deleteLifecycleRules() {
      bucketBuilderImpl.deleteLifecycleRules();
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setStorageClass(StorageClassType storageTier) {
      bucketBuilderImpl.setStorageClass(storageTier);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLocation(String region) {
      bucketBuilderImpl.setLocation(region);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setEtag(String entityTag) {
      bucketBuilderImpl.setEtag(entityTag);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setCreateTime(Long createdAt) {
      bucketBuilderImpl.setCreateTime(createdAt);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setMetageneration(Long metaGen) {
      bucketBuilderImpl.setMetageneration(metaGen);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setCors(Iterable<CorsConfiguration> crossOriginConfigs) {
      bucketBuilderImpl.setCors(crossOriginConfigs);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setAcl(Iterable<AclEntry> accessControlList) {
      bucketBuilderImpl.setAcl(accessControlList);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<AclEntry> accessControlList) {
      bucketBuilderImpl.setDefaultAcl(accessControlList);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> metaMap) {
      bucketBuilderImpl.setLabels(metaMap);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultKmsKeyName(String kmsKey) {
      bucketBuilderImpl.setDefaultKmsKeyName(kmsKey);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultEventBasedHold(Boolean autoEventHold) {
      bucketBuilderImpl.setDefaultEventBasedHold(autoEventHold);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long retentionStartTime) {
      bucketBuilderImpl.setRetentionEffectiveTime(retentionStartTime);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setRetentionPolicyIsLocked(Boolean retentionLocked) {
      bucketBuilderImpl.setRetentionPolicyIsLocked(retentionLocked);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setRetentionPeriod(Long retentionDuration) {
      bucketBuilderImpl.setRetentionPeriod(retentionDuration);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setIamConfiguration(BucketIamConfiguration identityAccessConfig) {
      bucketBuilderImpl.setIamConfiguration(identityAccessConfig);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLogging(LoggingConfig logConfig) {
      bucketBuilderImpl.setLogging(logConfig);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setLocationType(String regionType) {
      bucketBuilderImpl.setLocationType(regionType);
      return this;
    }

    @Override
    public StorageBucket buildInstance() {
      return new StorageBucket(storageClient, bucketBuilderImpl);
    }
  }

  StorageBucket(StorageClient storageClient, BucketBuilderImpl bucketBuilderImpl) {
    super(bucketBuilderImpl);
    this.storageClient = checkNotNull(storageClient);
    this.storageSettings = storageClient.getOptions();
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
   * @throws StorageOperationException upon failure
   */
  public boolean doesExist(BucketSourceOptions... storageSettings) {
    int count = storageSettings.length;
    StorageClient.BucketGetOptions[] getFlags = Arrays.copyOf(toGetOptionArray(this, storageSettings), count + 1);
    getFlags[count] = StorageClient.BucketGetOptions.withFields();
    return storageClient.get(getName(), getFlags) != null;
  }

  /**
   * Fetches current bucket's latest information. Returns {@code null} if the bucket does not exist.
   *
   * <p>Example of getting the bucket's latest information, if its generation does not match the
   * {@link StorageBucket#getMetageneration()} value, otherwise a {@link StorageOperationException} is thrown.
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
   * @throws StorageOperationException upon failure
   */
  public StorageBucket refresh(BucketSourceOptions... storageSettings) {
    return storageClient.get(getName(), toGetOptionArray(this, storageSettings));
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
   * @throws StorageOperationException upon failure
   */
  public StorageBucket modify(StorageClient.BucketTargetOptions... storageSettings) {
    return storageClient.update(this, storageSettings);
  }

  /**
   * Deletes this bucket.
   *
   * <p>Example of deleting the bucket, if its metageneration matches the {@link
   * StorageBucket#getMetageneration()} value, otherwise a {@link StorageOperationException} is thrown.
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
   * @throws StorageOperationException upon failure
   */
  public boolean remove(BucketSourceOptions... storageSettings) {
    return storageClient.delete(getName(), toSourceOptionArray(this, storageSettings));
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
   * @throws StorageOperationException upon failure
   */
  public Page<StorageObject> listAll(BlobListOptions... storageSettings) {
    return storageClient.list(getName(), storageSettings);
  }

  /**
   * Returns the requested blob in this bucket or {@code null} if not found.
   *
   * <p>Example of getting a blob in the bucket, only if its metageneration matches a value,
   * otherwise a {@link StorageOperationException} is thrown.
   *
   * <pre>{@code
   * String blobName = "my_blob_name";
   * long generation = 42;
   * Blob blob = bucket.get(blobName, BlobGetOption.generationMatch(generation));
   * }</pre>
   *
   * @param objectName name of the requested blob
   * @param storageSettings blob search options
   * @throws StorageOperationException upon failure
   */
  public StorageObject get(String objectName, StorageClient.BlobGetOptions... storageSettings) {
    return storageClient.get(BlobIdentifier.create(getName(), objectName), storageSettings);
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
   * @param firstObjectName first blob to get
   * @param secondObjectName second blob to get
   * @param objectNames other blobs to get
   * @return an immutable list of {@code Blob} objects
   * @throws StorageOperationException upon failure
   */
  public List<StorageObject> get(String firstObjectName, String secondObjectName, String... objectNames) {
    List<BlobIdentifier> identifiers = Lists.newArrayListWithCapacity(objectNames.length + 2);
    identifiers.add(BlobIdentifier.create(getName(), firstObjectName));
    identifiers.add(BlobIdentifier.create(getName(), secondObjectName));
    for (String objectKey : objectNames) {
      identifiers.add(BlobIdentifier.create(getName(), objectKey));
    }
    return storageClient.get(identifiers);
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
   * @param objectNames blobs to get
   * @return an immutable list of {@code Blob} objects
   * @throws StorageOperationException upon failure
   */
  public List<StorageObject> get(Iterable<String> objectNames) {
    ImmutableList.Builder<BlobIdentifier> idAssembler = ImmutableList.builder();
    for (String objectKey : objectNames) {
      idAssembler.add(BlobIdentifier.create(getName(), objectKey));
    }
    return storageClient.get(idAssembler.build());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link StorageObject#openWriter(StorageClient.BlobWriteOptions...)} is
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
   * @param objectName a blob name
   * @param payload the blob content
   * @param mimeType the blob content type
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageOperationException upon failure
   */
  public StorageObject createBlob(String objectName, byte[] payload, String mimeType, BlobUploadOption... storageSettings) {
    BlobAttributes blobAttributes =
        BlobAttributes.newBuilder(BlobIdentifier.create(getName(), objectName)).setContentType(mimeType).buildObject();
    Tuple<BlobAttributes, StorageClient.BlobUploadOption[]> destinationPair =
        StorageBucket.BlobUploadOption.toTargetOptionArray(blobAttributes, storageSettings);
    return storageClient.create(destinationPair.x(), payload, destinationPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link StorageObject#openWriter(StorageClient.BlobWriteOptions...)} is
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
   * @param objectName a blob name
   * @param payload the blob content as a stream
   * @param mimeType the blob content type
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageOperationException upon failure
   */
  public StorageObject createBlob(
          String objectName, InputStream payload, String mimeType, BlobWriteOptions... storageSettings) {
    BlobAttributes blobAttributes =
        BlobAttributes.newBuilder(BlobIdentifier.create(getName(), objectName)).setContentType(mimeType).buildObject();
    Tuple<BlobAttributes, StorageClient.BlobWriteOptions[]> writePair =
        StorageBucket.BlobWriteOptions.toWriteOptionArray(blobAttributes, storageSettings);
    return storageClient.create(writePair.x(), payload, writePair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link StorageObject#openWriter(StorageClient.BlobWriteOptions...)} is
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
   * @param objectName a blob name
   * @param payload the blob content
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageOperationException upon failure
   */
  public StorageObject createBlob(String objectName, byte[] payload, BlobUploadOption... storageSettings) {
    BlobAttributes blobAttributes = BlobAttributes.newBuilder(BlobIdentifier.create(getName(), objectName)).buildObject();
    Tuple<BlobAttributes, StorageClient.BlobUploadOption[]> destinationPair =
        StorageBucket.BlobUploadOption.toTargetOptionArray(blobAttributes, storageSettings);
    return storageClient.create(destinationPair.x(), payload, destinationPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link StorageObject#openWriter(StorageClient.BlobWriteOptions...)} is
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
   * @param objectName a blob name
   * @param payload the blob content as a stream
   * @param storageSettings options for blob creation
   * @return a complete blob information
   * @throws StorageOperationException upon failure
   */
  public StorageObject createBlob(String objectName, InputStream payload, BlobWriteOptions... storageSettings) {
    BlobAttributes blobAttributes = BlobAttributes.newBuilder(BlobIdentifier.create(getName(), objectName)).buildObject();
    Tuple<BlobAttributes, StorageClient.BlobWriteOptions[]> writePair =
        StorageBucket.BlobWriteOptions.toWriteOptionArray(blobAttributes, storageSettings);
    return storageClient.create(writePair.x(), payload, writePair.y());
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
   * @throws StorageOperationException upon failure
   */
  public AclEntry getAcl(AclEntry.TypedEntity principal) {
    return storageClient.getAcl(getName(), principal);
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
   * @throws StorageOperationException upon failure
   */
  public boolean removeAcl(AclEntry.TypedEntity principal) {
    return storageClient.deleteAcl(getName(), principal);
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
   * @throws StorageOperationException upon failure
   */
  public AclEntry addAcl(AclEntry accessControlList) {
    return storageClient.createAcl(getName(), accessControlList);
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
   * @throws StorageOperationException upon failure
   */
  public AclEntry modifyAcl(AclEntry accessControlList) {
    return storageClient.updateAcl(getName(), accessControlList);
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
   * @throws StorageOperationException upon failure
   */
  public List<AclEntry> listAccessControls() {
    return storageClient.listAcls(getName());
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
   * @throws StorageOperationException upon failure
   */
  public AclEntry getDefaultAcl(TypedEntity principal) {
    return storageClient.getDefaultAcl(getName(), principal);
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
   * @throws StorageOperationException upon failure
   */
  public boolean removeDefaultAcl(AclEntry.TypedEntity principal) {
    return storageClient.deleteDefaultAcl(getName(), principal);
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
   * @throws StorageOperationException upon failure
   */
  public AclEntry addDefaultAcl(AclEntry accessControlList) {
    return storageClient.createDefaultAcl(getName(), accessControlList);
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
   * @throws StorageOperationException upon failure
   */
  public AclEntry modifyDefaultAcl(AclEntry accessControlList) {
    return storageClient.updateDefaultAcl(getName(), accessControlList);
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
   * @throws StorageOperationException upon failure
   */
  public List<AclEntry> listDefaultAccessControls() {
    return storageClient.listDefaultAcls(getName());
  }

  /**
   * Locks bucket retention policy. Requires a local metageneration value in the request. Review
   * example below.
   *
   * <p>Accepts an optional userProject {@link StorageClient.BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Warning: Once a retention policy is locked, it can't be unlocked, removed, or shortened.
   *
   * <p>Example of locking a retention policy on a bucket, only if its local metageneration value
   * matches the bucket's service metageneration otherwise a {@link StorageOperationException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my_unique_bucket";
   * Bucket bucket = storage.get(bucketName, BucketGetOption.fields(BucketField.METAGENERATION));
   * storage.lockRetentionPolicy(bucket, BucketTargetOption.metagenerationMatch());
   * }</pre>
   *
   * @return a {@code Bucket} object of the locked bucket
   * @throws StorageOperationException upon failure
   */
  public StorageBucket applyRetentionLock(BucketTargetOptions... storageSettings) {
    return storageClient.lockRetentionPolicy(this, storageSettings);
  }

  /** Returns the bucket's {@code Storage} object used to issue requests. */
  public StorageClient getStorage() {
    return storageClient;
  }

  @Override
  public StorageBucket.BucketInfoBuilder toBuilderCopy() {
    return new BucketInfoBuilder(this);
  }

  @Override
  public final boolean equals(Object otherObject) {
    if (otherObject == this) {
      return true;
    }
    if (otherObject == null || !otherObject.getClass().equals(StorageBucket.class)) {
      return false;
    }
    StorageBucket thatInstance = (StorageBucket) otherObject;
    return Objects.equals(toProto(), thatInstance.toProto()) && Objects.equals(storageSettings, thatInstance.storageSettings);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), storageSettings);
  }

  private void readObject(ObjectInputStream objectStream) throws IOException, ClassNotFoundException {
    objectStream.defaultReadObject();
    this.storageClient = storageSettings.getService();
  }

  static StorageBucket fromProto(StorageClient storageClient, com.google.api.services.storage.model.Bucket protoBucket) {
    return new StorageBucket(storageClient, new BucketBuilderImpl(BucketInfo.fromProto(protoBucket)));
  }
}
