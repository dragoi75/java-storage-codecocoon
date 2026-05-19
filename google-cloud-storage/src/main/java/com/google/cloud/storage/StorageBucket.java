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

import static com.google.cloud.storage.StorageBucket.BucketSourceOptionSpec.convertToGetOptions;
import static com.google.cloud.storage.StorageBucket.BucketSourceOptionSpec.convertToSourceOptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
import com.google.cloud.storage.AccessControlEntry.TypedEntity;
import com.google.cloud.storage.CloudStorageClient.BlobGetOptions;
import com.google.cloud.storage.CloudStorageClient.BucketTargetOptions;
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
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
 * #reloadFromStorage}. {@code Bucket} adds a layer of service-related functionality over {@link BucketInfo}.
 */
public class StorageBucket extends BucketInfo {

  private static final long serialVersionUID = 8574601739542252586L;

  private final StorageClientOptions clientConfig;
  private transient CloudStorageClient cloudClient;

  /** Class for specifying bucket source options when {@code Bucket} methods are used. */
  public static class BucketSourceOptionSpec extends OptionDescriptor {

    private static final long serialVersionUID = 6928872234155522371L;

    private BucketSourceOptionSpec(StorageServiceRpc.StorageOption requestOption) {
      super(requestOption, null);
    }

    private BucketSourceOptionSpec(StorageServiceRpc.StorageOption requestOption, Object obj) {
      super(requestOption, obj);
    }

    private CloudStorageClient.BucketSourceRequestOption toBucketSourceOption(BucketInfo bucketMetadata) {
      switch (getRpcOption()) {
        case IF_METAGENERATION_MATCH:
          return CloudStorageClient.BucketSourceRequestOption.ifMetagenerationMatch(bucketMetadata.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return CloudStorageClient.BucketSourceRequestOption.ifMetagenerationNotMatch(bucketMetadata.getMetageneration());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private CloudStorageClient.BucketGetOptions toBucketGetOption(BucketInfo bucketMetadata) {
      switch (getRpcOption()) {
        case IF_METAGENERATION_MATCH:
          return CloudStorageClient.BucketGetOptions.ifMetagenerationMatch(bucketMetadata.getMetageneration());
        case IF_METAGENERATION_NOT_MATCH:
          return CloudStorageClient.BucketGetOptions.ifMetagenerationNotMatch(bucketMetadata.getMetageneration());
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BucketSourceOptionSpec withMetagenerationMatch() {
      return new BucketSourceOptionSpec(StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static BucketSourceOptionSpec metagenerationNotMatch() {
      return new BucketSourceOptionSpec(StorageServiceRpc.StorageOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketSourceOptionSpec userProject(String projectId) {
      return new BucketSourceOptionSpec(StorageServiceRpc.StorageOption.USER_PROJECT, projectId);
    }

    static CloudStorageClient.BucketSourceRequestOption[] convertToSourceOptions(
            BucketInfo bucketMetadata, BucketSourceOptionSpec... clientConfig) {
      CloudStorageClient.BucketSourceRequestOption[] sourceRequests =
          new CloudStorageClient.BucketSourceRequestOption[clientConfig.length];
      int i = 0;
      for (BucketSourceOptionSpec spec : clientConfig) {
        sourceRequests[i++] = spec.toBucketSourceOption(bucketMetadata);
      }
      return sourceRequests;
    }

    static CloudStorageClient.BucketGetOptions[] convertToGetOptions(
            BucketInfo bucketMetadata, BucketSourceOptionSpec... clientConfig) {
      CloudStorageClient.BucketGetOptions[] sourceRequests = new CloudStorageClient.BucketGetOptions[clientConfig.length];
      int i = 0;
      for (BucketSourceOptionSpec spec : clientConfig) {
        sourceRequests[i++] = spec.toBucketGetOption(bucketMetadata);
      }
      return sourceRequests;
    }
  }

  /** Class for specifying blob target options when {@code Bucket} methods are used. */
  public static class BlobUploadOption extends OptionDescriptor {

    private static final Function<BlobUploadOption, StorageServiceRpc.StorageOption> TO_STORAGE_OPTION =
        new Function<BlobUploadOption, StorageServiceRpc.StorageOption>() {
          @Override
          public StorageServiceRpc.StorageOption apply(BlobUploadOption blobTargetOption) {
            return blobTargetOption.getRpcOption();
          }
        };
    private static final long serialVersionUID = 8345296337342509425L;

    private BlobUploadOption(StorageServiceRpc.StorageOption requestOption, Object obj) {
      super(requestOption, obj);
    }

    private Tuple<BlobMetadata, CloudStorageClient.BlobUploadOptions> toUploadTargetOption(BlobMetadata blobMetadata) {
      BlobIdentifier blobIdentifier = blobMetadata.getBlobId();
      switch (getRpcOption()) {
        case PREDEFINED_ACL:
          return Tuple.of(
                  blobMetadata, CloudStorageClient.BlobUploadOptions.withPredefinedAcl((CloudStorageClient.PredefinedAccessControlList) getValue()));
        case IF_GENERATION_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
          return Tuple.of(
              blobMetadata.asBuilder().setBlobId(blobIdentifier).buildObject(),
              CloudStorageClient.BlobUploadOptions.ifGenerationMatch());
        case IF_GENERATION_NOT_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
          return Tuple.of(
              blobMetadata.asBuilder().setBlobId(blobIdentifier).buildObject(),
              CloudStorageClient.BlobUploadOptions.ifGenerationNotMatch());
        case IF_METAGENERATION_MATCH:
          return Tuple.of(
              blobMetadata.asBuilder().setMetageneration((Long) getValue()).buildObject(),
              CloudStorageClient.BlobUploadOptions.ifMetagenerationMatch());
        case IF_METAGENERATION_NOT_MATCH:
          return Tuple.of(
              blobMetadata.asBuilder().setMetageneration((Long) getValue()).buildObject(),
              CloudStorageClient.BlobUploadOptions.ifMetagenerationNotMatch());
        case CUSTOMER_SUPPLIED_KEY:
          return Tuple.of(blobMetadata, CloudStorageClient.BlobUploadOptions.customerSuppliedKey((String) getValue()));
        case KMS_KEY_NAME:
          return Tuple.of(blobMetadata, CloudStorageClient.BlobUploadOptions.withKmsKeyName((String) getValue()));
        case USER_PROJECT:
          return Tuple.of(blobMetadata, CloudStorageClient.BlobUploadOptions.withUserProject((String) getValue()));
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobUploadOption withPredefinedAcl(CloudStorageClient.PredefinedAccessControlList predefinedAccess) {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.PREDEFINED_ACL, predefinedAccess);
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
     * #withGenerationNotMatch(long)}.
     */
    public static BlobUploadOption doesNotExistOption() {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match the provided value. This option can not be provided
     * together with {@link #withGenerationNotMatch(long)} or {@link #doesNotExistOption()}.
     */
    public static BlobUploadOption withGenerationMatch(long generationId) {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.IF_GENERATION_MATCH, generationId);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value. This option can not be provided
     * together with {@link #withGenerationMatch(long)} or {@link #doesNotExistOption()}.
     */
    public static BlobUploadOption withGenerationNotMatch(long generationId) {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.IF_GENERATION_NOT_MATCH, generationId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match the provided value. This option can not be provided
     * together with {@link #withMetagenerationNotMatch(long)}.
     */
    public static BlobUploadOption withMetagenerationMatch(long metaGenerationId) {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, metaGenerationId);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches the provided value. This option can not be provided together
     * with {@link #withMetagenerationMatch(long)}.
     */
    public static BlobUploadOption withMetagenerationNotMatch(long metaGenerationId) {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGenerationId);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobUploadOption withEncryptionKey(Key encryptionKey) {
      String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
      return new BlobUploadOption(StorageServiceRpc.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param encryptionKey the AES256 encoded in base64
     */
    public static BlobUploadOption withEncryptionKey(String encryptionKey) {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
     *
     * @param kmsKey the KMS key resource id
     */
    public static BlobUploadOption withKmsKeyName(String kmsKey) {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.KMS_KEY_NAME, kmsKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobUploadOption withUserProject(String projectId) {
      return new BlobUploadOption(StorageServiceRpc.StorageOption.USER_PROJECT, projectId);
    }

    static Tuple<BlobMetadata, CloudStorageClient.BlobUploadOptions[]> convertToTargetOptions(
            BlobMetadata blobMetadata, BlobUploadOption... clientConfig) {
      Set<StorageServiceRpc.StorageOption> storageOptionSet =
          Sets.immutableEnumSet(Lists.transform(Arrays.asList(clientConfig), TO_STORAGE_OPTION));
      checkArgument(
          !(storageOptionSet.contains(StorageServiceRpc.StorageOption.IF_METAGENERATION_NOT_MATCH)
              && storageOptionSet.contains(StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH)),
          "metagenerationMatch and metagenerationNotMatch options can not be both provided");
      checkArgument(
          !(storageOptionSet.contains(StorageServiceRpc.StorageOption.IF_GENERATION_NOT_MATCH)
              && storageOptionSet.contains(StorageServiceRpc.StorageOption.IF_GENERATION_MATCH)),
          "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
      CloudStorageClient.BlobUploadOptions[] sourceRequests = new CloudStorageClient.BlobUploadOptions[clientConfig.length];
      BlobMetadata targetMetadata = blobMetadata;
      int i = 0;
      for (BlobUploadOption spec : clientConfig) {
        Tuple<BlobMetadata, CloudStorageClient.BlobUploadOptions> destinationPair = spec.toUploadTargetOption(targetMetadata);
        targetMetadata = destinationPair.x();
        sourceRequests[i++] = destinationPair.y();
      }
      return Tuple.of(targetMetadata, sourceRequests);
    }
  }

  /** Class for specifying blob write options when {@code Bucket} methods are used. */
  public static class BlobWriteOptions implements Serializable {

    private static final Function<BlobWriteOptions, CloudStorageClient.BlobWriteOptions.StorageOption> TO_STORAGE_OPTION =
        new Function<BlobWriteOptions, CloudStorageClient.BlobWriteOptions.StorageOption>() {
          @Override
          public CloudStorageClient.BlobWriteOptions.StorageOption apply(BlobWriteOptions blobWriteOption) {
            return blobWriteOption.spec;
          }
        };
    private static final long serialVersionUID = 4722190734541993114L;

    private final CloudStorageClient.BlobWriteOptions.StorageOption spec;
    private final Object obj;

    private Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions> toBlobWriteOption(BlobMetadata blobMetadata) {
      BlobIdentifier blobIdentifier = blobMetadata.getBlobId();
      switch (spec) {
        case PREDEFINED_ACL:
          return Tuple.of(
                  blobMetadata, CloudStorageClient.BlobWriteOptions.withPredefinedAcl((CloudStorageClient.PredefinedAccessControlList) obj));
        case IF_GENERATION_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) obj);
          return Tuple.of(
              blobMetadata.asBuilder().setBlobId(blobIdentifier).buildObject(),
              CloudStorageClient.BlobWriteOptions.ifGenerationMatch());
        case IF_GENERATION_NOT_MATCH:
          blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) obj);
          return Tuple.of(
              blobMetadata.asBuilder().setBlobId(blobIdentifier).buildObject(),
              CloudStorageClient.BlobWriteOptions.ifGenerationNotMatch());
        case IF_METAGENERATION_MATCH:
          return Tuple.of(
              blobMetadata.asBuilder().setMetageneration((Long) obj).buildObject(),
              CloudStorageClient.BlobWriteOptions.ifMetagenerationMatch());
        case IF_METAGENERATION_NOT_MATCH:
          return Tuple.of(
              blobMetadata.asBuilder().setMetageneration((Long) obj).buildObject(),
              CloudStorageClient.BlobWriteOptions.ifMetagenerationNotMatch());
        case IF_MD5_MATCH:
          return Tuple.of(
              blobMetadata.asBuilder().setMd5((String) obj).buildObject(),
              CloudStorageClient.BlobWriteOptions.ifMd5Match());
        case IF_CRC32C_MATCH:
          return Tuple.of(
              blobMetadata.asBuilder().setCrc32c((String) obj).buildObject(),
              CloudStorageClient.BlobWriteOptions.ifCrc32cMatch());
        case CUSTOMER_SUPPLIED_KEY:
          return Tuple.of(blobMetadata, CloudStorageClient.BlobWriteOptions.customerSuppliedKey((String) obj));
        case KMS_KEY_NAME:
          return Tuple.of(blobMetadata, CloudStorageClient.BlobWriteOptions.withKmsKeyName((String) obj));
        case USER_PROJECT:
          return Tuple.of(blobMetadata, CloudStorageClient.BlobWriteOptions.withUserProject((String) obj));
        default:
          throw new AssertionError("Unexpected enum value");
      }
    }

    private BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption spec, Object obj) {
      this.spec = spec;
      this.obj = obj;
    }

    @Override
    public int hashCode() {
      return Objects.hash(spec, obj);
    }

    @Override
    public boolean equals(Object candidate) {
      if (candidate == null) {
        return false;
      }
      if (!(candidate instanceof BlobWriteOptions)) {
        return false;
      }
      final BlobWriteOptions that = (BlobWriteOptions) candidate;
      return this.spec == that.spec && Objects.equals(this.obj, that.obj);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobWriteOptions withPredefinedAcl(CloudStorageClient.PredefinedAccessControlList predefinedAccess) {
      return new BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption.PREDEFINED_ACL, predefinedAccess);
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
     * #withGenerationNotMatch(long)}.
     */
    public static BlobWriteOptions doesNotExistOption() {
      return new BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match the provided value. This option can not be provided
     * together with {@link #withGenerationNotMatch(long)} or {@link #doesNotExistOption()}.
     */
    public static BlobWriteOptions withGenerationMatch(long generationId) {
      return new BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, generationId);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches the provided value. This option can not be provided together
     * with {@link #withGenerationMatch(long)} or {@link #doesNotExistOption()}.
     */
    public static BlobWriteOptions withGenerationNotMatch(long generationId) {
      return new BlobWriteOptions(
          CloudStorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH, generationId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match the provided value. This option can not be provided
     * together with {@link #withMetagenerationNotMatch(long)}.
     */
    public static BlobWriteOptions withMetagenerationMatch(long metaGenerationId) {
      return new BlobWriteOptions(
          CloudStorageClient.BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH, metaGenerationId);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches the provided value. This option can not be provided together
     * with {@link #withMetagenerationMatch(long)}.
     */
    public static BlobWriteOptions withMetagenerationNotMatch(long metaGenerationId) {
      return new BlobWriteOptions(
          CloudStorageClient.BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGenerationId);
    }

    /**
     * Returns an option for blob's data MD5 hash match. If this option is used the request will
     * fail if blobs' data MD5 hash does not match the provided value.
     */
    public static BlobWriteOptions md5MatchOption(String base64Digest) {
      return new BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption.IF_MD5_MATCH, base64Digest);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match the provided value.
     */
    public static BlobWriteOptions crc32cMatchOption(String crcDigest) {
      return new BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption.IF_CRC32C_MATCH, crcDigest);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobWriteOptions withEncryptionKey(Key encryptionKey) {
      String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
      return new BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param encryptionKey the AES256 encoded in base64
     */
    public static BlobWriteOptions withEncryptionKey(String encryptionKey) {
      return new BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobWriteOptions userProjectOption(String projectId) {
      return new BlobWriteOptions(CloudStorageClient.BlobWriteOptions.StorageOption.USER_PROJECT, projectId);
    }

    static Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions[]> convertToWriteOptions(
            BlobMetadata blobMetadata, BlobWriteOptions... clientConfig) {
      Set<CloudStorageClient.BlobWriteOptions.StorageOption> storageOptionSet =
          Sets.immutableEnumSet(Lists.transform(Arrays.asList(clientConfig), TO_STORAGE_OPTION));
      checkArgument(
          !(storageOptionSet.contains(CloudStorageClient.BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH)
              && storageOptionSet.contains(CloudStorageClient.BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH)),
          "metagenerationMatch and metagenerationNotMatch options can not be both provided");
      checkArgument(
          !(storageOptionSet.contains(CloudStorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH)
              && storageOptionSet.contains(CloudStorageClient.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH)),
          "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
      CloudStorageClient.BlobWriteOptions[] sourceRequests = new CloudStorageClient.BlobWriteOptions[clientConfig.length];
      BlobMetadata writeMetadata = blobMetadata;
      int i = 0;
      for (BlobWriteOptions spec : clientConfig) {
        Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions> pairEntry = spec.toBlobWriteOption(writeMetadata);
        writeMetadata = pairEntry.x();
        sourceRequests[i++] = pairEntry.y();
      }
      return Tuple.of(writeMetadata, sourceRequests);
    }
  }

  /** Builder for {@code Bucket}. */
  public static class BucketInfoBuilder extends BucketInfo.Builder {
    private final CloudStorageClient cloudClient;
    private final BucketBuilderImpl bucketBuilder;

    BucketInfoBuilder(StorageBucket container) {
      this.cloudClient = container.cloudClient;
      this.bucketBuilder = new BucketBuilderImpl(container);
    }

    @Override
    public StorageBucket.BucketInfoBuilder setName(String id) {
      bucketBuilder.setName(id);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setGeneratedId(String generatedIdentifier) {
      bucketBuilder.setGeneratedId(generatedIdentifier);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setOwner(AccessControlEntry.TypedEntity entityHolder) {
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
    public StorageBucket.BucketInfoBuilder setRequesterPays(Boolean billingRequired) {
      bucketBuilder.setRequesterPays(billingRequired);
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
    public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends DeletionRule> deletePolicyList) {
      bucketBuilder.setDeleteRules(deletePolicyList);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deletePolicyList) {
      bucketBuilder.setLifecycleRules(deletePolicyList);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder deleteLifecycleRules() {
      bucketBuilder.deleteLifecycleRules();
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setStorageClass(StorageTier storageTier) {
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
    StorageBucket.BucketInfoBuilder setCreateTime(Long creationTimestamp) {
      bucketBuilder.setCreateTime(creationTimestamp);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setUpdateTime(Long lastModifiedTime) {
      bucketBuilder.setUpdateTime(lastModifiedTime);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setMetageneration(Long metaGenerationId) {
      bucketBuilder.setMetageneration(metaGenerationId);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setCors(Iterable<Cors> crossOriginConfig) {
      bucketBuilder.setCors(crossOriginConfig);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setAcl(Iterable<AccessControlEntry> predefinedAccess) {
      bucketBuilder.setAcl(predefinedAccess);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<AccessControlEntry> predefinedAccess) {
      bucketBuilder.setDefaultAcl(predefinedAccess);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> tagMap) {
      bucketBuilder.setLabels(tagMap);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultKmsKeyName(String defaultKeyName) {
      bucketBuilder.setDefaultKmsKeyName(defaultKeyName);
      return this;
    }

    @Override
    public StorageBucket.BucketInfoBuilder setDefaultEventBasedHold(Boolean eventHoldDefault) {
      bucketBuilder.setDefaultEventBasedHold(eventHoldDefault);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long retentionStartTime) {
      bucketBuilder.setRetentionEffectiveTime(retentionStartTime);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setRetentionPolicyIsLocked(Boolean retentionLockedFlag) {
      bucketBuilder.setRetentionPolicyIsLocked(retentionLockedFlag);
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
    public StorageBucket.BucketInfoBuilder setLogging(LoggingConfig logConfig) {
      bucketBuilder.setLogging(logConfig);
      return this;
    }

    @Override
    StorageBucket.BucketInfoBuilder setLocationType(String regionType) {
      bucketBuilder.setLocationType(regionType);
      return this;
    }

    @Override
    public StorageBucket buildBucketInfo() {
      return new StorageBucket(cloudClient, bucketBuilder);
    }
  }

  StorageBucket(CloudStorageClient cloudClient, BucketBuilderImpl bucketBuilder) {
    super(bucketBuilder);
    this.cloudClient = checkNotNull(cloudClient);
    this.clientConfig = cloudClient.getOptions();
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
  public boolean bucketExists(BucketSourceOptionSpec... clientConfig) {
    int count = clientConfig.length;
    CloudStorageClient.BucketGetOptions[] getOptionArray = Arrays.copyOf(convertToGetOptions(this, clientConfig), count + 1);
    getOptionArray[count] = CloudStorageClient.BucketGetOptions.selectFields();
    return cloudClient.get(getName(), getOptionArray) != null;
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
   * @param clientConfig bucket read options
   * @return a {@code Bucket} object with latest information or {@code null} if not found
   * @throws StorageServiceException upon failure
   */
  public StorageBucket reloadFromStorage(BucketSourceOptionSpec... clientConfig) {
    return cloudClient.get(getName(), convertToGetOptions(this, clientConfig));
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
   * @param clientConfig update options
   * @return a {@code Bucket} object with updated information
   * @throws StorageServiceException upon failure
   */
  public StorageBucket updateBucket(CloudStorageClient.BucketTargetOptions... clientConfig) {
    return cloudClient.update(this, clientConfig);
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
   * @param clientConfig bucket delete options
   * @return {@code true} if bucket was deleted, {@code false} if it was not found
   * @throws StorageServiceException upon failure
   */
  public boolean deleteBucket(BucketSourceOptionSpec... clientConfig) {
    return cloudClient.delete(getName(), convertToSourceOptions(this, clientConfig));
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
   * @param clientConfig options for listing blobs
   * @throws StorageServiceException upon failure
   */
  public Page<StorageBlob> listObjects(CloudStorageClient.BlobListOptions... clientConfig) {
    return cloudClient.list(getName(), clientConfig);
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
   * @param objectName name of the requested blob
   * @param clientConfig blob search options
   * @throws StorageServiceException upon failure
   */
  public StorageBlob get(String objectName, BlobGetOptions... clientConfig) {
    return cloudClient.get(BlobIdentifier.create(getName(), objectName), clientConfig);
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
   * @param firstBlobName first blob to get
   * @param secondBlobName second blob to get
   * @param objectNames other blobs to get
   * @return an immutable list of {@code Blob} objects
   * @throws StorageServiceException upon failure
   */
  public List<StorageBlob> get(String firstBlobName, String secondBlobName, String... objectNames) {
    List<BlobIdentifier> blobIdentifiers = Lists.newArrayListWithCapacity(objectNames.length + 2);
    blobIdentifiers.add(BlobIdentifier.create(getName(), firstBlobName));
    blobIdentifiers.add(BlobIdentifier.create(getName(), secondBlobName));
    for (String objectName : objectNames) {
      blobIdentifiers.add(BlobIdentifier.create(getName(), objectName));
    }
    return cloudClient.get(blobIdentifiers);
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
   * @throws StorageServiceException upon failure
   */
  public List<StorageBlob> get(Iterable<String> objectNames) {
    ImmutableList.Builder<BlobIdentifier> identifierAssembler = ImmutableList.builder();
    for (String objectName : objectNames) {
      identifierAssembler.add(BlobIdentifier.create(getName(), objectName));
    }
    return cloudClient.get(identifierAssembler.build());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link StorageBlob#openWriter(CloudStorageClient.BlobWriteOptions...)} is
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
   * @param payloadBytes the blob content
   * @param mimeType the blob content type
   * @param clientConfig options for blob creation
   * @return a complete blob information
   * @throws StorageServiceException upon failure
   */
  public StorageBlob createBlob(String objectName, byte[] payloadBytes, String mimeType, BlobUploadOption... clientConfig) {
    BlobMetadata blobMetadata =
        BlobMetadata.newBuilder(BlobIdentifier.create(getName(), objectName)).setContentType(mimeType).buildObject();
    Tuple<BlobMetadata, CloudStorageClient.BlobUploadOptions[]> destinationPair =
        BlobUploadOption.convertToTargetOptions(blobMetadata, clientConfig);
    return cloudClient.create(destinationPair.x(), payloadBytes, destinationPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link StorageBlob#openWriter(CloudStorageClient.BlobWriteOptions...)} is
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
   * @param payloadBytes the blob content as a stream
   * @param mimeType the blob content type
   * @param clientConfig options for blob creation
   * @return a complete blob information
   * @throws StorageServiceException upon failure
   */
  public StorageBlob createBlob(
          String objectName, InputStream payloadBytes, String mimeType, BlobWriteOptions... clientConfig) {
    BlobMetadata blobMetadata =
        BlobMetadata.newBuilder(BlobIdentifier.create(getName(), objectName)).setContentType(mimeType).buildObject();
    Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions[]> pairEntry =
        StorageBucket.BlobWriteOptions.convertToWriteOptions(blobMetadata, clientConfig);
    return cloudClient.create(pairEntry.x(), payloadBytes, pairEntry.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link StorageBlob#openWriter(CloudStorageClient.BlobWriteOptions...)} is
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
   * @param payloadBytes the blob content
   * @param clientConfig options for blob creation
   * @return a complete blob information
   * @throws StorageServiceException upon failure
   */
  public StorageBlob createBlob(String objectName, byte[] payloadBytes, BlobUploadOption... clientConfig) {
    BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), objectName)).buildObject();
    Tuple<BlobMetadata, CloudStorageClient.BlobUploadOptions[]> destinationPair =
        BlobUploadOption.convertToTargetOptions(blobMetadata, clientConfig);
    return cloudClient.create(destinationPair.x(), payloadBytes, destinationPair.y());
  }

  /**
   * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
   * content, {@link StorageBlob#openWriter(CloudStorageClient.BlobWriteOptions...)} is
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
   * @param payloadBytes the blob content as a stream
   * @param clientConfig options for blob creation
   * @return a complete blob information
   * @throws StorageServiceException upon failure
   */
  public StorageBlob createBlob(String objectName, InputStream payloadBytes, BlobWriteOptions... clientConfig) {
    BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), objectName)).buildObject();
    Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions[]> pairEntry =
        StorageBucket.BlobWriteOptions.convertToWriteOptions(blobMetadata, clientConfig);
    return cloudClient.create(pairEntry.x(), payloadBytes, pairEntry.y());
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
  public AccessControlEntry getAcl(TypedEntity principal) {
    return cloudClient.getAcl(getName(), principal);
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
  public boolean removeAcl(AccessControlEntry.TypedEntity principal) {
    return cloudClient.deleteAcl(getName(), principal);
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
  public AccessControlEntry addAcl(AccessControlEntry predefinedAccess) {
    return cloudClient.createAcl(getName(), predefinedAccess);
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
  public AccessControlEntry modifyAcl(AccessControlEntry predefinedAccess) {
    return cloudClient.updateAcl(getName(), predefinedAccess);
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
  public List<AccessControlEntry> listAcl() {
    return cloudClient.listAcls(getName());
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
  public AccessControlEntry getDefaultAcl(TypedEntity principal) {
    return cloudClient.getDefaultAcl(getName(), principal);
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
  public boolean removeDefaultAcl(AccessControlEntry.TypedEntity principal) {
    return cloudClient.deleteDefaultAcl(getName(), principal);
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
  public AccessControlEntry addDefaultAcl(AccessControlEntry predefinedAccess) {
    return cloudClient.createDefaultAcl(getName(), predefinedAccess);
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
  public AccessControlEntry modifyDefaultAcl(AccessControlEntry predefinedAccess) {
    return cloudClient.updateDefaultAcl(getName(), predefinedAccess);
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
  public List<AccessControlEntry> listDefaultAcl() {
    return cloudClient.listDefaultAcls(getName());
  }

  /**
   * Locks bucket retention policy. Requires a local metageneration value in the request. Review
   * example below.
   *
   * <p>Accepts an optional userProject {@link CloudStorageClient.BucketTargetOptions} option which defines the project
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
  public StorageBucket lockRetention(BucketTargetOptions... clientConfig) {
    return cloudClient.lockRetentionPolicy(this, clientConfig);
  }

  /** Returns the bucket's {@code Storage} object used to issue requests. */
  public CloudStorageClient getStorage() {
    return cloudClient;
  }

  @Override
  public StorageBucket.BucketInfoBuilder toBucketBuilder() {
    return new BucketInfoBuilder(this);
  }

  @Override
  public final boolean equals(Object candidate) {
    if (candidate == this) {
      return true;
    }
    if (candidate == null || !candidate.getClass().equals(StorageBucket.class)) {
      return false;
    }
    StorageBucket that = (StorageBucket) candidate;
    return Objects.equals(toProto(), that.toProto()) && Objects.equals(clientConfig, that.clientConfig);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(super.hashCode(), clientConfig);
  }

  private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
    inputStream.defaultReadObject();
    this.cloudClient = clientConfig.getService();
  }

  static StorageBucket fromProto(CloudStorageClient cloudClient, com.google.api.services.storage.model.Bucket protoMessage) {
    return new StorageBucket(cloudClient, new BucketBuilderImpl(BucketInfo.fromProto(protoMessage)));
  }
}
