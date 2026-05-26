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

import static com.google.cloud.storage.StorageBucket.BucketSourceOptionConfig.toBucketGetOptions;
import static com.google.cloud.storage.StorageBucket.BucketSourceOptionConfig.toBucketFilterOptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
import com.google.cloud.storage.AccessControlEntry.AbstractEntity;
import com.google.cloud.storage.StorageService.BlobFetchOption;
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
 * <p>Objects of this class are immutable. Operations that modify the bucket like {@link #modify}
 * return a new object. To get a {@code Bucket} object with the most recent information use {@link
 * #refresh}. {@code Bucket} adds a layer of service-related functionality over {@link BucketMetadata}.
 */
public class StorageBucket extends BucketMetadata {

    private static final long serialVersionUID = 8574601739542252586L;

    private final StorageSettings storageSettings;

    private transient StorageService service;

    /**
     * Class for specifying bucket source options when {@code Bucket} methods are used.
     */
    public static class BucketSourceOptionConfig extends AbstractOption {

        private static final long serialVersionUID = 6928872234155522371L;

        private StorageService.BucketFilterOption toBucketFilterOption(BucketMetadata bucketMetadata) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return StorageService.BucketFilterOption.ifMetagenerationMatch(bucketMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return StorageService.BucketFilterOption.ifMetagenerationNotMatch(bucketMetadata.getMetageneration());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        static StorageService.BucketFilterOption[] toBucketFilterOptions(BucketMetadata bucketMetadata, BucketSourceOptionConfig... storageSettings) {
            StorageService.BucketFilterOption[] filterOptions = new StorageService.BucketFilterOption[storageSettings.length];
            int i = 0;
            for (BucketSourceOptionConfig config : storageSettings) {
                filterOptions[i++] = config.toBucketFilterOption(bucketMetadata);
            }
            return filterOptions;
        }

        static StorageService.GetBucketOption[] toBucketGetOptions(BucketMetadata bucketMetadata, BucketSourceOptionConfig... storageSettings) {
            StorageService.GetBucketOption[] filterOptions = new StorageService.GetBucketOption[storageSettings.length];
            int i = 0;
            for (BucketSourceOptionConfig config : storageSettings) {
                filterOptions[i++] = config.toBucketGetOption(bucketMetadata);
            }
            return filterOptions;
        }

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if metageneration matches.
         */
        public static BucketSourceOptionConfig metagenerationNotMatch() {
            return new BucketSourceOptionConfig(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        private BucketSourceOptionConfig(StorageRpcClient.StorageOption rpcChoice, Object val) {
            super(rpcChoice, val);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BucketSourceOptionConfig userProject(String projectOwner) {
            return new BucketSourceOptionConfig(StorageRpcClient.StorageOption.USER_PROJECT, projectOwner);
        }

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BucketSourceOptionConfig ifMetagenerationMatch() {
            return new BucketSourceOptionConfig(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        private StorageService.GetBucketOption toBucketGetOption(BucketMetadata bucketMetadata) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return StorageService.GetBucketOption.ifMetagenerationMatch(bucketMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return StorageService.GetBucketOption.ifMetagenerationNotMatch(bucketMetadata.getMetageneration());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private BucketSourceOptionConfig(StorageRpcClient.StorageOption rpcChoice) {
            super(rpcChoice, null);
        }

    }

    /**
     * Class for specifying blob target options when {@code Bucket} methods are used.
     */
    public static class BlobUploadOption extends AbstractOption {

        private static final Function<BlobUploadOption, StorageRpcClient.StorageOption> TO_STORAGE_OPTION = new Function<BlobUploadOption, StorageRpcClient.StorageOption>() {

            @Override
            public StorageRpcClient.StorageOption apply(BlobUploadOption blobTargetOption) {
                return blobTargetOption.getRpcOption();
            }
        };

        private static final long serialVersionUID = 8345296337342509425L;

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value. This option can not be provided
         * together with {@link #withGenerationMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobUploadOption withGenerationNotMatch(long generationMatch) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, generationMatch);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobUploadOption withUserProject(String projectOwner) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.USER_PROJECT, projectOwner);
        }

        static Tuple<BlobMetadata, StorageService.BlobUploadOption[]> toUploadTargetOptions(BlobMetadata blobMetadata, BlobUploadOption... storageSettings) {
            Set<StorageRpcClient.StorageOption> storageOptions = Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), TO_STORAGE_OPTION));
            checkArgument(!(storageOptions.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH) && storageOptions.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(storageOptions.contains(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH) && storageOptions.contains(StorageRpcClient.StorageOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            StorageService.BlobUploadOption[] filterOptions = new StorageService.BlobUploadOption[storageSettings.length];
            BlobMetadata destinationMetadata = blobMetadata;
            int i = 0;
            for (BlobUploadOption config : storageSettings) {
                Tuple<BlobMetadata, StorageService.BlobUploadOption> metaUploadPair = config.toUploadTargetOption(destinationMetadata);
                destinationMetadata = metaUploadPair.x();
                filterOptions[i++] = metaUploadPair.y();
            }
            return Tuple.of(destinationMetadata, filterOptions);
        }

        /**
         * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
         *
         * @param kmsKey the KMS key resource id
         */
        public static BlobUploadOption withKmsKeyName(String kmsKey) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.KMS_KEY_NAME, kmsKey);
        }

        private BlobUploadOption(StorageRpcClient.StorageOption rpcChoice, Object val) {
            super(rpcChoice, val);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #withMetagenerationNotMatch(long)}.
         */
        public static BlobUploadOption withMetagenerationMatch(long metagenerationMatch) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metagenerationMatch);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
         * #withGenerationNotMatch(long)}.
         */
        public static BlobUploadOption ifDoesNotExist() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKeyObj the AES256 encoded in base64
         */
        public static BlobUploadOption withEncryptionKey(String encryptionKeyObj) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKeyObj);
        }

        private Tuple<BlobMetadata, StorageService.BlobUploadOption> toUploadTargetOption(BlobMetadata blobMetadata) {
            BlobIdentifier identifier = blobMetadata.getBlobId();
            switch(getRpcOption()) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, StorageService.BlobUploadOption.withPredefinedAcl((StorageService.PredefinedAccessControlList) getValue()));
                case IF_GENERATION_MATCH:
                    identifier = BlobIdentifier.create(identifier.getBucket(), identifier.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.asBuilder().setBlobId(identifier).buildObject(), StorageService.BlobUploadOption.ifGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    identifier = BlobIdentifier.create(identifier.getBucket(), identifier.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.asBuilder().setBlobId(identifier).buildObject(), StorageService.BlobUploadOption.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMetageneration((Long) getValue()).buildObject(), StorageService.BlobUploadOption.ifMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMetageneration((Long) getValue()).buildObject(), StorageService.BlobUploadOption.ifMetagenerationNotMatch());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, StorageService.BlobUploadOption.customerSuppliedKey((String) getValue()));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, StorageService.BlobUploadOption.withKmsKeyName((String) getValue()));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, StorageService.BlobUploadOption.withUserProject((String) getValue()));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #withGenerationNotMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobUploadOption withGenerationMatch(long generationMatch) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, generationMatch);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobUploadOption withPredefinedAcl(StorageService.PredefinedAccessControlList predefinedAclSetting) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.PREDEFINED_ACL, predefinedAclSetting);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobUploadOption withEncryptionKey(Key encryptionKeyObj) {
            String base64EncodedKey = BaseEncoding.base64().encode(encryptionKeyObj.getEncoded());
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #withMetagenerationMatch(long)}.
         */
        public static BlobUploadOption withMetagenerationNotMatch(long metagenerationMatch) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metagenerationMatch);
        }

    }

    /**
     * Class for specifying blob write options when {@code Bucket} methods are used.
     */
    public static class BlobWriteSetting implements Serializable {

        private static final Function<BlobWriteSetting, StorageService.BlobWriteSetting.ObjectOption> TO_STORAGE_OPTION = new Function<BlobWriteSetting, StorageService.BlobWriteSetting.ObjectOption>() {

            @Override
            public StorageService.BlobWriteSetting.ObjectOption apply(BlobWriteSetting blobWriteOption) {
                return blobWriteOption.config;
            }
        };

        private static final long serialVersionUID = 4722190734541993114L;

        private final StorageService.BlobWriteSetting.ObjectOption config;

        private final Object val;

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches the provided value. This option can not be provided together
         * with {@link #withGenerationMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobWriteSetting withGenerationNotMatch(long generationMatch) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.IF_GENERATION_NOT_MATCH, generationMatch);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobWriteSetting withPredefinedAcl(StorageService.PredefinedAccessControlList predefinedAclSetting) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.PREDEFINED_ACL, predefinedAclSetting);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobWriteSetting withUserProject(String projectOwner) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.USER_PROJECT, projectOwner);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
         * #withGenerationNotMatch(long)}.
         */
        public static BlobWriteSetting ifDoesNotExist() {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data MD5 hash match. If this option is used the request will
         * fail if blobs' data MD5 hash does not match the provided value.
         */
        public static BlobWriteSetting withMd5Match(String checksum) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.IF_MD5_MATCH, checksum);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobWriteSetting withEncryptionKey(Key encryptionKeyObj) {
            String base64EncodedKey = BaseEncoding.base64().encode(encryptionKeyObj.getEncoded());
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.CUSTOMER_SUPPLIED_KEY, base64EncodedKey);
        }

        static Tuple<BlobMetadata, StorageService.BlobWriteSetting[]> toBlobWriteOptions(BlobMetadata blobMetadata, BlobWriteSetting... storageSettings) {
            Set<StorageService.BlobWriteSetting.ObjectOption> storageOptions = Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), TO_STORAGE_OPTION));
            checkArgument(!(storageOptions.contains(StorageService.BlobWriteSetting.ObjectOption.IF_METAGENERATION_NOT_MATCH) && storageOptions.contains(StorageService.BlobWriteSetting.ObjectOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(storageOptions.contains(StorageService.BlobWriteSetting.ObjectOption.IF_GENERATION_NOT_MATCH) && storageOptions.contains(StorageService.BlobWriteSetting.ObjectOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            StorageService.BlobWriteSetting[] filterOptions = new StorageService.BlobWriteSetting[storageSettings.length];
            BlobMetadata writeMetadata = blobMetadata;
            int i = 0;
            for (BlobWriteSetting config : storageSettings) {
                Tuple<BlobMetadata, StorageService.BlobWriteSetting> metaSettingPair = config.toBlobWriteSetting(writeMetadata);
                writeMetadata = metaSettingPair.x();
                filterOptions[i++] = metaSettingPair.y();
            }
            return Tuple.of(writeMetadata, filterOptions);
        }

        @Override
        public boolean equals(Object otherObject) {
            if (null == otherObject) {
                return false;
            }
            if (!(otherObject instanceof BlobWriteSetting)) {
                return false;
            }
            final BlobWriteSetting thatSetting = (BlobWriteSetting) otherObject;
            return thatSetting.config == this.config && Objects.equals(this.val, thatSetting.val);
        }

        /**
         * Returns an option for blob's data CRC32C checksum match. If this option is used the request
         * will fail if blobs' data CRC32C checksum does not match the provided value.
         */
        public static BlobWriteSetting withCrc32cMatch(String crcChecksum) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.IF_CRC32C_MATCH, crcChecksum);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKeyObj the AES256 encoded in base64
         */
        public static BlobWriteSetting withEncryptionKey(String encryptionKeyObj) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.CUSTOMER_SUPPLIED_KEY, encryptionKeyObj);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #withGenerationNotMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobWriteSetting withGenerationMatch(long generationMatch) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.IF_GENERATION_MATCH, generationMatch);
        }

        private BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption config, Object val) {
            this.config = config;
            this.val = val;
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #withMetagenerationNotMatch(long)}.
         */
        public static BlobWriteSetting withMetagenerationMatch(long metagenerationMatch) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.IF_METAGENERATION_MATCH, metagenerationMatch);
        }

        private Tuple<BlobMetadata, StorageService.BlobWriteSetting> toBlobWriteSetting(BlobMetadata blobMetadata) {
            BlobIdentifier identifier = blobMetadata.getBlobId();
            switch(config) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, StorageService.BlobWriteSetting.withPredefinedAcl((StorageService.PredefinedAccessControlList) val));
                case IF_GENERATION_MATCH:
                    identifier = BlobIdentifier.create(identifier.getBucket(), identifier.getName(), (Long) val);
                    return Tuple.of(blobMetadata.asBuilder().setBlobId(identifier).buildObject(), StorageService.BlobWriteSetting.ifGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    identifier = BlobIdentifier.create(identifier.getBucket(), identifier.getName(), (Long) val);
                    return Tuple.of(blobMetadata.asBuilder().setBlobId(identifier).buildObject(), StorageService.BlobWriteSetting.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMetageneration((Long) val).buildObject(), StorageService.BlobWriteSetting.ifMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMetageneration((Long) val).buildObject(), StorageService.BlobWriteSetting.ifMetagenerationNotMatch());
                case IF_MD5_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setMd5((String) val).buildObject(), StorageService.BlobWriteSetting.ifMd5Match());
                case IF_CRC32C_MATCH:
                    return Tuple.of(blobMetadata.asBuilder().setCrc32c((String) val).buildObject(), StorageService.BlobWriteSetting.ifCrc32cMatch());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, StorageService.BlobWriteSetting.customerSuppliedKey((String) val));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, StorageService.BlobWriteSetting.withKmsKeyName((String) val));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, StorageService.BlobWriteSetting.withUserProject((String) val));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #withMetagenerationMatch(long)}.
         */
        public static BlobWriteSetting withMetagenerationNotMatch(long metagenerationMatch) {
            return new BlobWriteSetting(StorageService.BlobWriteSetting.ObjectOption.IF_METAGENERATION_NOT_MATCH, metagenerationMatch);
        }

        @Override
        public int hashCode() {
            return Objects.hash(config, val);
        }

    }

    /**
     * Builder for {@code Bucket}.
     */
    public static class BucketInfoBuilder extends AbstractBuilder {

        private final StorageService service;

        private final BucketBuilderImpl bucketBuilder;

        @Override
        public StorageBucket.BucketInfoBuilder setIamConfiguration(BucketIamConfiguration accessControlConfig) {
            bucketBuilder.setIamConfiguration(accessControlConfig);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<AccessControlEntry> predefinedAclSetting) {
            bucketBuilder.setDefaultAcl(predefinedAclSetting);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setNotFoundPage(String error404Page) {
            bucketBuilder.setNotFoundPage(error404Page);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setIndexPage(String indexDocument) {
            bucketBuilder.setIndexPage(indexDocument);
            return this;
        }

        @Override
        public StorageBucket buildInstance() {
            return new StorageBucket(service, bucketBuilder);
        }

        @Override
        StorageBucket.BucketInfoBuilder setMetageneration(Long metagenerationMatch) {
            bucketBuilder.setMetageneration(metagenerationMatch);
            return this;
        }

        @Override
        @Deprecated
        public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends DeletionRule> deletionPolicies) {
            bucketBuilder.setDeleteRules(deletionPolicies);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLogging(LoggingConfig logConfig) {
            bucketBuilder.setLogging(logConfig);
            return this;
        }

        BucketInfoBuilder(StorageBucket sourceBucket) {
            this.service = sourceBucket.service;
            this.bucketBuilder = new BucketBuilderImpl(sourceBucket);
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultEventBasedHold(Boolean eventBasedHoldActive) {
            bucketBuilder.setDefaultEventBasedHold(eventBasedHoldActive);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultKmsKeyName(String primaryEncryptionKey) {
            bucketBuilder.setDefaultKmsKeyName(primaryEncryptionKey);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setAcl(Iterable<AccessControlEntry> predefinedAclSetting) {
            bucketBuilder.setAcl(predefinedAclSetting);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setRequesterPays(Boolean billToCaller) {
            bucketBuilder.setRequesterPays(billToCaller);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deletionPolicies) {
            bucketBuilder.setLifecycleRules(deletionPolicies);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setLocationType(String regionCategory) {
            bucketBuilder.setLocationType(regionCategory);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setRetentionPolicyIsLocked(Boolean policyLockedFlag) {
            bucketBuilder.setRetentionPolicyIsLocked(policyLockedFlag);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> tagMap) {
            bucketBuilder.setLabels(tagMap);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setVersioningEnabled(Boolean versioningOn) {
            bucketBuilder.setVersioningEnabled(versioningOn);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setGeneratedId(String generatedIdentifier) {
            bucketBuilder.setGeneratedId(generatedIdentifier);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setRetentionPeriod(Long lockDuration) {
            bucketBuilder.setRetentionPeriod(lockDuration);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long policyEffectiveTimestamp) {
            bucketBuilder.setRetentionEffectiveTime(policyEffectiveTimestamp);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setSelfLink(String resourceUrl) {
            bucketBuilder.setSelfLink(resourceUrl);
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
        public StorageBucket.BucketInfoBuilder setName(String bucketLabel) {
            bucketBuilder.setName(bucketLabel);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setOwner(AbstractEntity resourcePrincipal) {
            bucketBuilder.setOwner(resourcePrincipal);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLocation(String region) {
            bucketBuilder.setLocation(region);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setCors(Iterable<CorsConfiguration> crossOriginConfigs) {
            bucketBuilder.setCors(crossOriginConfigs);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setStorageClass(StorageClassType tierType) {
            bucketBuilder.setStorageClass(tierType);
            return this;
        }

    }

    /**
     * Returns the bucket's {@code Storage} object used to issue requests.
     */
    public StorageService getStorage() {
        return service;
    }

    @Override
    public final boolean equals(Object otherObject) {
        if (this == otherObject) {
            return true;
        }
        if (null == otherObject || !otherObject.getClass().equals(StorageBucket.class)) {
            return false;
        }
        StorageBucket thatSetting = (StorageBucket) otherObject;
        return Objects.equals(toProto(), thatSetting.toProto()) && Objects.equals(storageSettings, thatSetting.storageSettings);
    }

    static StorageBucket fromProto(StorageService service, com.google.api.services.storage.model.Bucket bucketProto) {
        return new StorageBucket(service, new BucketBuilderImpl(BucketMetadata.fromProto(bucketProto)));
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
    public boolean removeDefaultAcl(AbstractEntity principal) {
        return service.deleteDefaultAcl(getName(), principal);
    }

    @Override
    public StorageBucket.BucketInfoBuilder toBucketBuilder() {
        return new BucketInfoBuilder(this);
    }

    private void readObject(ObjectInputStream objectStream) throws IOException, ClassNotFoundException {
        objectStream.defaultReadObject();
        this.service = storageSettings.getService();
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#openWriter(StorageService.BlobWriteSetting...)} is
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
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageOperationException upon failure
     */
    public StorageObject createBlob(String objectName, InputStream payloadBytes, BlobWriteSetting... storageSettings) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), objectName)).buildObject();
        Tuple<BlobMetadata, StorageService.BlobWriteSetting[]> metaSettingPair = BlobWriteSetting.toBlobWriteOptions(blobMetadata, storageSettings);
        return service.create(metaSettingPair.x(), payloadBytes, metaSettingPair.y());
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
    public AccessControlEntry modifyAcl(AccessControlEntry predefinedAclSetting) {
        return service.updateAcl(getName(), predefinedAclSetting);
    }

    StorageBucket(StorageService service, BucketBuilderImpl bucketBuilder) {
        super(bucketBuilder);
        this.service = checkNotNull(service);
        this.storageSettings = service.getOptions();
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
    public AccessControlEntry addAcl(AccessControlEntry predefinedAclSetting) {
        return service.createAcl(getName(), predefinedAclSetting);
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#openWriter(StorageService.BlobWriteSetting...)} is
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
     * @param mediaType the blob content type
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageOperationException upon failure
     */
    public StorageObject createBlob(String objectName, byte[] payloadBytes, String mediaType, BlobUploadOption... storageSettings) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), objectName)).setContentType(mediaType).buildObject();
        Tuple<BlobMetadata, StorageService.BlobUploadOption[]> metaUploadPair = BlobUploadOption.toUploadTargetOptions(blobMetadata, storageSettings);
        return service.create(metaUploadPair.x(), payloadBytes, metaUploadPair.y());
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
    public List<AccessControlEntry> listDefaultAclEntries() {
        return service.listDefaultAcls(getName());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#openWriter(StorageService.BlobWriteSetting...)} is
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
     * @param mediaType the blob content type
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageOperationException upon failure
     */
    public StorageObject createBlob(String objectName, InputStream payloadBytes, String mediaType, BlobWriteSetting... storageSettings) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), objectName)).setContentType(mediaType).buildObject();
        Tuple<BlobMetadata, StorageService.BlobWriteSetting[]> metaSettingPair = BlobWriteSetting.toBlobWriteOptions(blobMetadata, storageSettings);
        return service.create(metaSettingPair.x(), payloadBytes, metaSettingPair.y());
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
    public boolean removeAcl(AbstractEntity principal) {
        return service.deleteAcl(getName(), principal);
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
    public AccessControlEntry addDefaultAcl(AccessControlEntry predefinedAclSetting) {
        return service.createDefaultAcl(getName(), predefinedAclSetting);
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
    public StorageBucket refresh(BucketSourceOptionConfig... storageSettings) {
        return service.get(getName(), toBucketGetOptions(this, storageSettings));
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
        List<BlobIdentifier> objectIds = Lists.newArrayListWithCapacity(objectNames.length + 2);
        objectIds.add(BlobIdentifier.create(getName(), firstObjectName));
        objectIds.add(BlobIdentifier.create(getName(), secondObjectName));
        for (String objectName : objectNames) {
            objectIds.add(BlobIdentifier.create(getName(), objectName));
        }
        return service.get(objectIds);
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
    public Page<StorageObject> listObjects(BlobListOptions... storageSettings) {
        return service.list(getName(), storageSettings);
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#openWriter(StorageService.BlobWriteSetting...)} is
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
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageOperationException upon failure
     */
    public StorageObject createBlob(String objectName, byte[] payloadBytes, BlobUploadOption... storageSettings) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), objectName)).buildObject();
        Tuple<BlobMetadata, StorageService.BlobUploadOption[]> metaUploadPair = BlobUploadOption.toUploadTargetOptions(blobMetadata, storageSettings);
        return service.create(metaUploadPair.x(), payloadBytes, metaUploadPair.y());
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
        ImmutableList.Builder<BlobIdentifier> identifierFactory = ImmutableList.builder();
        for (String objectName : objectNames) {
            identifierFactory.add(BlobIdentifier.create(getName(), objectName));
        }
        return service.get(identifierFactory.build());
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
    public AccessControlEntry getAcl(AbstractEntity principal) {
        return service.getAcl(getName(), principal);
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
    public AccessControlEntry getDefaultAcl(AbstractEntity principal) {
        return service.getDefaultAcl(getName(), principal);
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
    public AccessControlEntry modifyDefaultAcl(AccessControlEntry predefinedAclSetting) {
        return service.updateDefaultAcl(getName(), predefinedAclSetting);
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
    public StorageBucket lockRetention(BucketTargetOptions... storageSettings) {
        return service.lockRetentionPolicy(this, storageSettings);
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
    public boolean remove(BucketSourceOptionConfig... storageSettings) {
        return service.delete(getName(), toBucketFilterOptions(this, storageSettings));
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), storageSettings);
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
    public List<AccessControlEntry> listAclEntries() {
        return service.listAcls(getName());
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
    public StorageObject get(String objectName, BlobFetchOption... storageSettings) {
        return service.get(BlobIdentifier.create(getName(), objectName), storageSettings);
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
    public boolean bucketExists(BucketSourceOptionConfig... storageSettings) {
        int count = storageSettings.length;
        StorageService.GetBucketOption[] fetchConfigs = Arrays.copyOf(toBucketGetOptions(this, storageSettings), count + 1);
        fetchConfigs[count] = StorageService.GetBucketOption.selectFields();
        return null != service.get(getName(), fetchConfigs);
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
    public StorageBucket modify(BucketTargetOptions... storageSettings) {
        return service.update(this, storageSettings);
    }

}
