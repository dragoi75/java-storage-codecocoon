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

import static com.google.cloud.storage.StorageBucket.BucketSourceSetting.toBucketGetOptions;
import static com.google.cloud.storage.StorageBucket.BucketSourceSetting.toBucketSourceOptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
import com.google.cloud.storage.AclEntry.BaseEntity;
import com.google.cloud.storage.Storage.BlobGetOptions;
import com.google.cloud.storage.Storage.BlobListOptions;
import com.google.cloud.storage.Storage.BucketTargetOptions;
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

    private transient Storage backendClient;

    /**
     * Class for specifying bucket source options when {@code Bucket} methods are used.
     */
    public static class BucketSourceSetting extends AbstractOption {

        private static final long serialVersionUID = 6928872234155522371L;

        private BucketSourceSetting(StorageRpcClient.StorageOption remoteOption) {
            super(remoteOption, null);
        }

        private BucketSourceSetting(StorageRpcClient.StorageOption remoteOption, Object inputObj) {
            super(remoteOption, inputObj);
        }

        private Storage.BucketSourceRequestOption toBucketSourceOption(BucketMetadata bucketMeta) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return Storage.BucketSourceRequestOption.ifMetagenerationMatch(bucketMeta.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return Storage.BucketSourceRequestOption.ifMetagenerationNotMatch(bucketMeta.getMetageneration());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private Storage.BucketGetOptions toBucketGetOption(BucketMetadata bucketMeta) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return Storage.BucketGetOptions.ifMetagenerationMatch(bucketMeta.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return Storage.BucketGetOptions.ifMetagenerationNotMatch(bucketMeta.getMetageneration());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BucketSourceSetting withMetagenerationMatch() {
            return new BucketSourceSetting(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if metageneration matches.
         */
        public static BucketSourceSetting metagenerationNotMatch() {
            return new BucketSourceSetting(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BucketSourceSetting userProject(String projectId) {
            return new BucketSourceSetting(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
        }

        static Storage.BucketSourceRequestOption[] toBucketSourceOptions(BucketMetadata bucketMeta, BucketSourceSetting... storageSettings) {
            Storage.BucketSourceRequestOption[] convertedRequests = new Storage.BucketSourceRequestOption[storageSettings.length];
            int idx = 0;
            for (BucketSourceSetting setting : storageSettings) {
                convertedRequests[idx++] = setting.toBucketSourceOption(bucketMeta);
            }
            return convertedRequests;
        }

        static Storage.BucketGetOptions[] toBucketGetOptions(BucketMetadata bucketMeta, BucketSourceSetting... storageSettings) {
            Storage.BucketGetOptions[] convertedRequests = new Storage.BucketGetOptions[storageSettings.length];
            int idx = 0;
            for (BucketSourceSetting setting : storageSettings) {
                convertedRequests[idx++] = setting.toBucketGetOption(bucketMeta);
            }
            return convertedRequests;
        }
    }

    /**
     * Class for specifying blob target options when {@code Bucket} methods are used.
     */
    public static class BlobUploadOption extends AbstractOption {

        private static final Function<BlobUploadOption, StorageRpcClient.StorageOption> CONVERTER_FUNCTION = new Function<BlobUploadOption, StorageRpcClient.StorageOption>() {

            @Override
            public StorageRpcClient.StorageOption apply(BlobUploadOption blobTargetOption) {
                return blobTargetOption.getRpcOption();
            }
        };

        private static final long serialVersionUID = 8345296337342509425L;

        private BlobUploadOption(StorageRpcClient.StorageOption remoteOption, Object inputObj) {
            super(remoteOption, inputObj);
        }

        private Tuple<BlobInfo, Storage.BlobUploadOption> toBlobTargetOption(BlobInfo blobMetadata) {
            BlobIdentifier id = blobMetadata.getBlobId();
            switch(getRpcOption()) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, Storage.BlobUploadOption.withPredefinedAcl((Storage.PredefinedAccessControlList) getValue()));
                case IF_GENERATION_MATCH:
                    id = BlobIdentifier.create(id.getBucket(), id.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.asBuilder().setBlobId(id).buildMetadata(), Storage.BlobUploadOption.ifGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    id = BlobIdentifier.create(id.getBucket(), id.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.asBuilder().setBlobId(id).buildMetadata(), Storage.BlobUploadOption.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMetageneration((Long) getValue()).buildMetadata(), Storage.BlobUploadOption.ifMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMetageneration((Long) getValue()).buildMetadata(), Storage.BlobUploadOption.ifMetagenerationNotMatch());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, Storage.BlobUploadOption.withEncryptionKey((String) getValue()));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, Storage.BlobUploadOption.withKmsKeyName((String) getValue()));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, Storage.BlobUploadOption.withUserProject((String) getValue()));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobUploadOption withPredefinedAcl(Storage.PredefinedAccessControlList accessControlList) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.PREDEFINED_ACL, accessControlList);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
         * #withGenerationNotMatch(long)}.
         */
        public static BlobUploadOption notExists() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #withGenerationNotMatch(long)} or {@link #notExists()}.
         */
        public static BlobUploadOption withGenerationMatch(long gen) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, gen);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value. This option can not be provided
         * together with {@link #withGenerationMatch(long)} or {@link #notExists()}.
         */
        public static BlobUploadOption withGenerationNotMatch(long gen) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, gen);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #withMetagenerationNotMatch(long)}.
         */
        public static BlobUploadOption withMetagenerationMatch(long metaGen) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaGen);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #withMetagenerationMatch(long)}.
         */
        public static BlobUploadOption withMetagenerationNotMatch(long metaGen) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGen);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobUploadOption withEncryptionKey(Key encryptionMaterial) {
            String base64EncodedStr = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedStr);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionMaterial the AES256 encoded in base64
         */
        public static BlobUploadOption withEncryptionKey(String encryptionMaterial) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
        }

        /**
         * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
         *
         * @param kmsResourceName the KMS key resource id
         */
        public static BlobUploadOption withKmsKeyName(String kmsResourceName) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.KMS_KEY_NAME, kmsResourceName);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobUploadOption withUserProject(String projectId) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
        }

        static Tuple<BlobInfo, Storage.BlobUploadOption[]> toBlobTargetOptions(BlobInfo blobMetadata, BlobUploadOption... storageSettings) {
            Set<StorageRpcClient.StorageOption> storageOptions = Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), CONVERTER_FUNCTION));
            checkArgument(!(storageOptions.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH) && storageOptions.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(storageOptions.contains(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH) && storageOptions.contains(StorageRpcClient.StorageOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            Storage.BlobUploadOption[] convertedRequests = new Storage.BlobUploadOption[storageSettings.length];
            BlobInfo blobTargetInfo = blobMetadata;
            int idx = 0;
            for (BlobUploadOption setting : storageSettings) {
                Tuple<BlobInfo, Storage.BlobUploadOption> blobTuple = setting.toBlobTargetOption(blobTargetInfo);
                blobTargetInfo = blobTuple.x();
                convertedRequests[idx++] = blobTuple.y();
            }
            return Tuple.of(blobTargetInfo, convertedRequests);
        }
    }

    /**
     * Class for specifying blob write options when {@code Bucket} methods are used.
     */
    public static class BlobWriteSetting implements Serializable {

        private static final Function<BlobWriteSetting, Storage.BlobWriteOptions.BlobTargetOption> CONVERTER_FUNCTION = new Function<BlobWriteSetting, Storage.BlobWriteOptions.BlobTargetOption>() {

            @Override
            public Storage.BlobWriteOptions.BlobTargetOption apply(BlobWriteSetting blobWriteOption) {
                return blobWriteOption.setting;
            }
        };

        private static final long serialVersionUID = 4722190734541993114L;

        private final Storage.BlobWriteOptions.BlobTargetOption setting;

        private final Object inputObj;

        private Tuple<BlobInfo, Storage.BlobWriteOptions> toBlobWriteOption(BlobInfo blobMetadata) {
            BlobIdentifier id = blobMetadata.getBlobId();
            switch(setting) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, Storage.BlobWriteOptions.withPredefinedAcl((Storage.PredefinedAccessControlList) inputObj));
                case IF_GENERATION_MATCH:
                    id = BlobIdentifier.create(id.getBucket(), id.getName(), (Long) inputObj);
                    return Tuple.of(blobMetadata.asBuilder().setBlobId(id).buildMetadata(), Storage.BlobWriteOptions.ifGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    id = BlobIdentifier.create(id.getBucket(), id.getName(), (Long) inputObj);
                    return Tuple.of(blobMetadata.asBuilder().setBlobId(id).buildMetadata(), Storage.BlobWriteOptions.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMetageneration((Long) inputObj).buildMetadata(), Storage.BlobWriteOptions.ifMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMetageneration((Long) inputObj).buildMetadata(), Storage.BlobWriteOptions.ifMetagenerationNotMatch());
                case IF_MD5_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMd5((String) inputObj).buildMetadata(), Storage.BlobWriteOptions.ifMd5Match());
                case IF_CRC32C_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setCrc32c((String) inputObj).buildMetadata(), Storage.BlobWriteOptions.ifCrc32cMatch());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, Storage.BlobWriteOptions.withEncryptionKey((String) inputObj));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, Storage.BlobWriteOptions.withKmsKeyName((String) inputObj));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, Storage.BlobWriteOptions.withUserProject((String) inputObj));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption setting, Object inputObj) {
            this.setting = setting;
            this.inputObj = inputObj;
        }

        @Override
        public int hashCode() {
            return Objects.hash(setting, inputObj);
        }

        @Override
        public boolean equals(Object candidate) {
            if (null == candidate) {
                return false;
            }
            if (!(candidate instanceof BlobWriteSetting)) {
                return false;
            }
            final BlobWriteSetting thatSetting = (BlobWriteSetting) candidate;
            return thatSetting.setting == this.setting && Objects.equals(this.inputObj, thatSetting.inputObj);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobWriteSetting withPredefinedAcl(Storage.PredefinedAccessControlList accessControlList) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.PREDEFINED_ACL, accessControlList);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #generationMatchOption(long)} or {@link
         * #generationNotMatchOption(long)}.
         */
        public static BlobWriteSetting doesNotExistOption() {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #generationNotMatchOption(long)} or {@link #doesNotExistOption()}.
         */
        public static BlobWriteSetting generationMatchOption(long gen) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.IF_GENERATION_MATCH, gen);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches the provided value. This option can not be provided together
         * with {@link #generationMatchOption(long)} or {@link #doesNotExistOption()}.
         */
        public static BlobWriteSetting generationNotMatchOption(long gen) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.IF_GENERATION_NOT_MATCH, gen);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #metagenerationNotMatchOption(long)}.
         */
        public static BlobWriteSetting metagenerationMatchOption(long metaGen) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.IF_METAGENERATION_MATCH, metaGen);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #metagenerationMatchOption(long)}.
         */
        public static BlobWriteSetting metagenerationNotMatchOption(long metaGen) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.IF_METAGENERATION_NOT_MATCH, metaGen);
        }

        /**
         * Returns an option for blob's data MD5 hash match. If this option is used the request will
         * fail if blobs' data MD5 hash does not match the provided value.
         */
        public static BlobWriteSetting md5MatchOption(String checksum) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.IF_MD5_MATCH, checksum);
        }

        /**
         * Returns an option for blob's data CRC32C checksum match. If this option is used the request
         * will fail if blobs' data CRC32C checksum does not match the provided value.
         */
        public static BlobWriteSetting crc32cMatchOption(String crcChecksum) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.IF_CRC32C_MATCH, crcChecksum);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobWriteSetting withEncryptionKey(Key encryptionMaterial) {
            String base64EncodedStr = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.CUSTOMER_SUPPLIED_KEY, base64EncodedStr);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionMaterial the AES256 encoded in base64
         */
        public static BlobWriteSetting withEncryptionKey(String encryptionMaterial) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobWriteSetting withUserProject(String projectId) {
            return new BlobWriteSetting(Storage.BlobWriteOptions.BlobTargetOption.USER_PROJECT, projectId);
        }

        static Tuple<BlobInfo, Storage.BlobWriteOptions[]> toBlobWriteOptions(BlobInfo blobMetadata, BlobWriteSetting... storageSettings) {
            Set<Storage.BlobWriteOptions.BlobTargetOption> storageOptions = Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), CONVERTER_FUNCTION));
            checkArgument(!(storageOptions.contains(Storage.BlobWriteOptions.BlobTargetOption.IF_METAGENERATION_NOT_MATCH) && storageOptions.contains(Storage.BlobWriteOptions.BlobTargetOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(storageOptions.contains(Storage.BlobWriteOptions.BlobTargetOption.IF_GENERATION_NOT_MATCH) && storageOptions.contains(Storage.BlobWriteOptions.BlobTargetOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            Storage.BlobWriteOptions[] convertedRequests = new Storage.BlobWriteOptions[storageSettings.length];
            BlobInfo targetBlobMetadata = blobMetadata;
            int idx = 0;
            for (BlobWriteSetting setting : storageSettings) {
                Tuple<BlobInfo, Storage.BlobWriteOptions> blobTuple = setting.toBlobWriteOption(targetBlobMetadata);
                targetBlobMetadata = blobTuple.x();
                convertedRequests[idx++] = blobTuple.y();
            }
            return Tuple.of(targetBlobMetadata, convertedRequests);
        }
    }

    /**
     * Builder for {@code Bucket}.
     */
    public static class BucketInfoBuilder extends BucketBuilder {

        private final Storage backendClient;

        private final BucketBuilderImpl bucketBuilder;

        BucketInfoBuilder(StorageBucket container) {
            this.backendClient = container.backendClient;
            this.bucketBuilder = new BucketBuilderImpl(container);
        }

        @Override
        public StorageBucket.BucketInfoBuilder setName(String label) {
            bucketBuilder.setName(label);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setGeneratedId(String generatedIdentifier) {
            bucketBuilder.setGeneratedId(generatedIdentifier);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setOwner(BaseEntity principal) {
            bucketBuilder.setOwner(principal);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setSelfLink(String resourceUri) {
            bucketBuilder.setSelfLink(resourceUri);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setVersioningEnabled(Boolean isActive) {
            bucketBuilder.setVersioningEnabled(isActive);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setRequesterPays(Boolean requesterCharged) {
            bucketBuilder.setRequesterPays(requesterCharged);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setIndexPage(String indexDocument) {
            bucketBuilder.setIndexPage(indexDocument);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setNotFoundPage(String missingPage) {
            bucketBuilder.setNotFoundPage(missingPage);
            return this;
        }

        @Override
        @Deprecated
        public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends DeletionRule> deletionSpecs) {
            bucketBuilder.setDeleteRules(deletionSpecs);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecycleRuleSpec> deletionSpecs) {
            bucketBuilder.setLifecycleRules(deletionSpecs);
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
        StorageBucket.BucketInfoBuilder setCreateTime(Long createdAt) {
            bucketBuilder.setCreateTime(createdAt);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setMetageneration(Long metaGen) {
            bucketBuilder.setMetageneration(metaGen);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setCors(Iterable<CorsConfig> crossOriginConfigs) {
            bucketBuilder.setCors(crossOriginConfigs);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setAcl(Iterable<AclEntry> accessControlList) {
            bucketBuilder.setAcl(accessControlList);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<AclEntry> accessControlList) {
            bucketBuilder.setDefaultAcl(accessControlList);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> tags) {
            bucketBuilder.setLabels(tags);
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
        StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long retentionStart) {
            bucketBuilder.setRetentionEffectiveTime(retentionStart);
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
        public StorageBucket.BucketInfoBuilder setIamConfiguration(BucketIamConfiguration iamConfig) {
            bucketBuilder.setIamConfiguration(iamConfig);
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
        public StorageBucket buildBucket() {
            return new StorageBucket(backendClient, bucketBuilder);
        }
    }

    StorageBucket(Storage backendClient, BucketBuilderImpl bucketBuilder) {
        super(bucketBuilder);
        this.backendClient = checkNotNull(backendClient);
        this.storageSettings = backendClient.getOptions();
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
    public boolean bucketExists(BucketSourceSetting... storageSettings) {
        int count = storageSettings.length;
        Storage.BucketGetOptions[] fetchOptions = Arrays.copyOf(toBucketGetOptions(this, storageSettings), count + 1);
        fetchOptions[count] = Storage.BucketGetOptions.withFields();
        return null != backendClient.get(getName(), fetchOptions);
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
    public StorageBucket refresh(BucketSourceSetting... storageSettings) {
        return backendClient.get(getName(), toBucketGetOptions(this, storageSettings));
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
        return backendClient.update(this, storageSettings);
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
    public boolean deleteBucket(BucketSourceSetting... storageSettings) {
        return backendClient.delete(getName(), toBucketSourceOptions(this, storageSettings));
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
    public Page<StorageObject> listObjects(BlobListOptions... storageSettings) {
        return backendClient.list(getName(), storageSettings);
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
     * @param objectKey name of the requested blob
     * @param storageSettings blob search options
     * @throws StorageServiceException upon failure
     */
    public StorageObject get(String objectKey, BlobGetOptions... storageSettings) {
        return backendClient.get(BlobIdentifier.create(getName(), objectKey), storageSettings);
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
     * @throws StorageServiceException upon failure
     */
    public List<StorageObject> get(String firstObjectName, String secondObjectName, String... objectNames) {
        List<BlobIdentifier> objectIds = Lists.newArrayListWithCapacity(objectNames.length + 2);
        objectIds.add(BlobIdentifier.create(getName(), firstObjectName));
        objectIds.add(BlobIdentifier.create(getName(), secondObjectName));
        for (String objectKey : objectNames) {
            objectIds.add(BlobIdentifier.create(getName(), objectKey));
        }
        return backendClient.get(objectIds);
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
    public List<StorageObject> get(Iterable<String> objectNames) {
        ImmutableList.Builder<BlobIdentifier> identifierCreator = ImmutableList.builder();
        for (String objectKey : objectNames) {
            identifierCreator.add(BlobIdentifier.create(getName(), objectKey));
        }
        return backendClient.get(identifierCreator.build());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#writeChannel(Storage.BlobWriteOptions...)} is
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
     * @param objectKey a blob name
     * @param payload the blob content
     * @param mimeType the blob content type
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectKey, byte[] payload, String mimeType, BlobUploadOption... storageSettings) {
        BlobInfo blobMetadata = BlobInfo.newBuilder(BlobIdentifier.create(getName(), objectKey)).setContentType(mimeType).buildMetadata();
        Tuple<BlobInfo, Storage.BlobUploadOption[]> blobTuple = StorageBucket.BlobUploadOption.toBlobTargetOptions(blobMetadata, storageSettings);
        return backendClient.create(blobTuple.x(), payload, blobTuple.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#writeChannel(Storage.BlobWriteOptions...)} is
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
     * @param objectKey a blob name
     * @param payload the blob content as a stream
     * @param mimeType the blob content type
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectKey, InputStream payload, String mimeType, BlobWriteSetting... storageSettings) {
        BlobInfo blobMetadata = BlobInfo.newBuilder(BlobIdentifier.create(getName(), objectKey)).setContentType(mimeType).buildMetadata();
        Tuple<BlobInfo, Storage.BlobWriteOptions[]> blobTuple = BlobWriteSetting.toBlobWriteOptions(blobMetadata, storageSettings);
        return backendClient.create(blobTuple.x(), payload, blobTuple.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#writeChannel(Storage.BlobWriteOptions...)} is
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
     * @param objectKey a blob name
     * @param payload the blob content
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectKey, byte[] payload, BlobUploadOption... storageSettings) {
        BlobInfo blobMetadata = BlobInfo.newBuilder(BlobIdentifier.create(getName(), objectKey)).buildMetadata();
        Tuple<BlobInfo, Storage.BlobUploadOption[]> blobTuple = StorageBucket.BlobUploadOption.toBlobTargetOptions(blobMetadata, storageSettings);
        return backendClient.create(blobTuple.x(), payload, blobTuple.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#writeChannel(Storage.BlobWriteOptions...)} is
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
     * @param objectKey a blob name
     * @param payload the blob content as a stream
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectKey, InputStream payload, BlobWriteSetting... storageSettings) {
        BlobInfo blobMetadata = BlobInfo.newBuilder(BlobIdentifier.create(getName(), objectKey)).buildMetadata();
        Tuple<BlobInfo, Storage.BlobWriteOptions[]> blobTuple = BlobWriteSetting.toBlobWriteOptions(blobMetadata, storageSettings);
        return backendClient.create(blobTuple.x(), payload, blobTuple.y());
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
    public AclEntry getAcl(AclEntry.BaseEntity subject) {
        return backendClient.getAcl(getName(), subject);
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
    public boolean removeAcl(AclEntry.BaseEntity subject) {
        return backendClient.deleteAcl(getName(), subject);
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
    public AclEntry addAcl(AclEntry accessControlList) {
        return backendClient.createAcl(getName(), accessControlList);
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
    public AclEntry modifyAcl(AclEntry accessControlList) {
        return backendClient.updateAcl(getName(), accessControlList);
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
    public List<AclEntry> listAccessControls() {
        return backendClient.listAcls(getName());
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
    public AclEntry getDefaultAcl(BaseEntity subject) {
        return backendClient.getDefaultAcl(getName(), subject);
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
    public boolean removeDefaultAcl(AclEntry.BaseEntity subject) {
        return backendClient.deleteDefaultAcl(getName(), subject);
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
    public AclEntry addDefaultAcl(AclEntry accessControlList) {
        return backendClient.createDefaultAcl(getName(), accessControlList);
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
    public AclEntry modifyDefaultAcl(AclEntry accessControlList) {
        return backendClient.updateDefaultAcl(getName(), accessControlList);
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
    public List<AclEntry> listDefaultAccessControls() {
        return backendClient.listDefaultAcls(getName());
    }

    /**
     * Locks bucket retention policy. Requires a local metageneration value in the request. Review
     * example below.
     *
     * <p>Accepts an optional userProject {@link BucketTargetOptions} option which defines the project
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
    public StorageBucket freezeRetentionPolicy(BucketTargetOptions... storageSettings) {
        return backendClient.lockRetentionPolicy(this, storageSettings);
    }

    /**
     * Returns the bucket's {@code Storage} object used to issue requests.
     */
    public Storage getStorage() {
        return backendClient;
    }

    @Override
    public StorageBucket.BucketInfoBuilder asBuilder() {
        return new BucketInfoBuilder(this);
    }

    @Override
    public final boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (null == candidate || !candidate.getClass().equals(StorageBucket.class)) {
            return false;
        }
        StorageBucket thatSetting = (StorageBucket) candidate;
        return Objects.equals(toProto(), thatSetting.toProto()) && Objects.equals(storageSettings, thatSetting.storageSettings);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), storageSettings);
    }

    private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
        inputStream.defaultReadObject();
        this.backendClient = storageSettings.getService();
    }

    static StorageBucket fromProto(Storage backendClient, com.google.api.services.storage.model.Bucket bucketProto) {
        return new StorageBucket(backendClient, new BucketBuilderImpl(BucketMetadata.fromProto(bucketProto)));
    }
}
