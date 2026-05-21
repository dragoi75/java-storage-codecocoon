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

import static com.google.cloud.storage.StorageBucket.BucketSourceOptions.asGetOptionsArray;
import static com.google.cloud.storage.StorageBucket.BucketSourceOptions.asSourceOptionsArray;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
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

    private final StorageClientOptions storageClientConfig;

    private transient Storage backendClient;

    /**
     * Class for specifying bucket source options when {@code Bucket} methods are used.
     */
    public static class BucketSourceOptions extends AbstractOption {

        private static final long serialVersionUID = 6928872234155522371L;

        private BucketSourceOptions(StorageRpcClient.StorageOption rpcSetting) {
            super(rpcSetting, null);
        }

        private BucketSourceOptions(StorageRpcClient.StorageOption rpcSetting, Object input) {
            super(rpcSetting, input);
        }

        private Storage.BucketSourceOptions asSourceOption(BucketMetadata bucketMetadata) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return Storage.BucketSourceOptions.withMetagenerationMatch(bucketMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return Storage.BucketSourceOptions.withMetagenerationNotMatch(bucketMetadata.getMetageneration());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private Storage.GetBucketOption asGetOption(BucketMetadata bucketMetadata) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return Storage.GetBucketOption.withMetagenerationMatch(bucketMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return Storage.GetBucketOption.withMetagenerationNotMatch(bucketMetadata.getMetageneration());
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

        static Storage.BucketSourceOptions[] asSourceOptionsArray(BucketMetadata bucketMetadata, BucketSourceOptions... storageClientConfig) {
            Storage.BucketSourceOptions[] normalizedSourceArray = new Storage.BucketSourceOptions[storageClientConfig.length];
            int i = 0;
            for (BucketSourceOptions sourceSetting : storageClientConfig) {
                normalizedSourceArray[i++] = sourceSetting.asSourceOption(bucketMetadata);
            }
            return normalizedSourceArray;
        }

        static Storage.GetBucketOption[] asGetOptionsArray(BucketMetadata bucketMetadata, BucketSourceOptions... storageClientConfig) {
            Storage.GetBucketOption[] normalizedSourceArray = new Storage.GetBucketOption[storageClientConfig.length];
            int i = 0;
            for (BucketSourceOptions sourceSetting : storageClientConfig) {
                normalizedSourceArray[i++] = sourceSetting.asGetOption(bucketMetadata);
            }
            return normalizedSourceArray;
        }
    }

    /**
     * Class for specifying blob target options when {@code Bucket} methods are used.
     */
    public static class BlobTargetOptions extends AbstractOption {

        private static final Function<BlobTargetOptions, StorageRpcClient.StorageOption> TO_ENUM_FN = new Function<BlobTargetOptions, StorageRpcClient.StorageOption>() {

            @Override
            public StorageRpcClient.StorageOption apply(BlobTargetOptions blobTargetOption) {
                return blobTargetOption.getRpcOption();
            }
        };

        private static final long serialVersionUID = 8345296337342509425L;

        private BlobTargetOptions(StorageRpcClient.StorageOption rpcSetting, Object input) {
            super(rpcSetting, input);
        }

        private Tuple<BlobMetadata, Storage.BlobUploadOption> asTargetOption(BlobMetadata blobMetadata) {
            BlobId blobIdentifier = blobMetadata.getBlobId();
            switch(getRpcOption()) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, Storage.BlobUploadOption.withPredefinedAcl((Storage.PredefinedAccessControlList) getValue()));
                case IF_GENERATION_MATCH:
                    blobIdentifier = BlobId.from(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.toInfoBuilder().setBlobId(blobIdentifier).buildObject(), Storage.BlobUploadOption.withGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    blobIdentifier = BlobId.from(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.toInfoBuilder().setBlobId(blobIdentifier).buildObject(), Storage.BlobUploadOption.withGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.toInfoBuilder().setMetageneration((Long) getValue()).buildObject(), Storage.BlobUploadOption.withMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.toInfoBuilder().setMetageneration((Long) getValue()).buildObject(), Storage.BlobUploadOption.withMetagenerationNotMatch());
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
        public static BlobTargetOptions withPredefinedAcl(Storage.PredefinedAccessControlList accessControlList) {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.PREDEFINED_ACL, accessControlList);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #ifGenerationMatch(long)} or {@link
         * #ifGenerationNotMatch(long)}.
         */
        public static BlobTargetOptions ifDoesNotExist() {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #ifGenerationNotMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobTargetOptions ifGenerationMatch(long genId) {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, genId);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value. This option can not be provided
         * together with {@link #ifGenerationMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobTargetOptions ifGenerationNotMatch(long genId) {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, genId);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #ifMetagenerationNotMatch(long)}.
         */
        public static BlobTargetOptions ifMetagenerationMatch(long metaGenId) {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaGenId);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #ifMetagenerationMatch(long)}.
         */
        public static BlobTargetOptions ifMetagenerationNotMatch(long metaGenId) {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGenId);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobTargetOptions withEncryptionKey(Key encryptionKeyObj) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKeyObj.getEncoded());
            return new BlobTargetOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKeyObj the AES256 encoded in base64
         */
        public static BlobTargetOptions withEncryptionKey(String encryptionKeyObj) {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKeyObj);
        }

        /**
         * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
         *
         * @param kmsKeyIdentifier the KMS key resource id
         */
        public static BlobTargetOptions withKmsKeyName(String kmsKeyIdentifier) {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.KMS_KEY_NAME, kmsKeyIdentifier);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobTargetOptions withUserProject(String projectId) {
            return new BlobTargetOptions(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
        }

        static Tuple<BlobMetadata, Storage.BlobUploadOption[]> asTargetOptionsTuple(BlobMetadata blobMetadata, BlobTargetOptions... storageClientConfig) {
            Set<StorageRpcClient.StorageOption> storageOptionCollection = Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageClientConfig), TO_ENUM_FN));
            checkArgument(!(storageOptionCollection.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH) && storageOptionCollection.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(storageOptionCollection.contains(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH) && storageOptionCollection.contains(StorageRpcClient.StorageOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            Storage.BlobUploadOption[] normalizedSourceArray = new Storage.BlobUploadOption[storageClientConfig.length];
            BlobMetadata targetMetadata = blobMetadata;
            int i = 0;
            for (BlobTargetOptions sourceSetting : storageClientConfig) {
                Tuple<BlobMetadata, Storage.BlobUploadOption> metadataUploadPair = sourceSetting.asTargetOption(targetMetadata);
                targetMetadata = metadataUploadPair.x();
                normalizedSourceArray[i++] = metadataUploadPair.y();
            }
            return Tuple.of(targetMetadata, normalizedSourceArray);
        }
    }

    /**
     * Class for specifying blob write options when {@code Bucket} methods are used.
     */
    public static class BlobWriteOptions implements Serializable {

        private static final Function<BlobWriteOptions, Storage.BlobWriteOptions.StorageOption> TO_ENUM_FN = new Function<BlobWriteOptions, Storage.BlobWriteOptions.StorageOption>() {

            @Override
            public Storage.BlobWriteOptions.StorageOption apply(BlobWriteOptions blobWriteOption) {
                return blobWriteOption.sourceSetting;
            }
        };

        private static final long serialVersionUID = 4722190734541993114L;

        private final Storage.BlobWriteOptions.StorageOption sourceSetting;

        private final Object input;

        private Tuple<BlobMetadata, Storage.BlobWriteOptions> asWriteOption(BlobMetadata blobMetadata) {
            BlobId blobIdentifier = blobMetadata.getBlobId();
            switch(sourceSetting) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, Storage.BlobWriteOptions.withPredefinedAcl((Storage.PredefinedAccessControlList) input));
                case IF_GENERATION_MATCH:
                    blobIdentifier = BlobId.from(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) input);
                    return Tuple.of(blobMetadata.toInfoBuilder().setBlobId(blobIdentifier).buildObject(), Storage.BlobWriteOptions.withGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    blobIdentifier = BlobId.from(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) input);
                    return Tuple.of(blobMetadata.toInfoBuilder().setBlobId(blobIdentifier).buildObject(), Storage.BlobWriteOptions.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.toInfoBuilder().setMetageneration((Long) input).buildObject(), Storage.BlobWriteOptions.ifMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.toInfoBuilder().setMetageneration((Long) input).buildObject(), Storage.BlobWriteOptions.ifMetagenerationNotMatch());
                case IF_MD5_MATCH:
                    return Tuple.of(blobMetadata.toInfoBuilder().setMd5((String) input).buildObject(), Storage.BlobWriteOptions.ifMd5Match());
                case IF_CRC32C_MATCH:
                    return Tuple.of(blobMetadata.toInfoBuilder().setCrc32c((String) input).buildObject(), Storage.BlobWriteOptions.ifCrc32cMatch());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, Storage.BlobWriteOptions.customerSuppliedKey((String) input));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, Storage.BlobWriteOptions.withKmsKeyName((String) input));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, Storage.BlobWriteOptions.withUserProject((String) input));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private BlobWriteOptions(Storage.BlobWriteOptions.StorageOption sourceSetting, Object input) {
            this.sourceSetting = sourceSetting;
            this.input = input;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceSetting, input);
        }

        @Override
        public boolean equals(Object candidate) {
            if (null == candidate) {
                return false;
            }
            if (!(candidate instanceof BlobWriteOptions)) {
                return false;
            }
            final BlobWriteOptions rhs = (BlobWriteOptions) candidate;
            return rhs.sourceSetting == this.sourceSetting && Objects.equals(this.input, rhs.input);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobWriteOptions withPredefinedAcl(Storage.PredefinedAccessControlList accessControlList) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.PREDEFINED_ACL, accessControlList);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #ifGenerationMatch(long)} or {@link
         * #ifGenerationNotMatch(long)}.
         */
        public static BlobWriteOptions ifDoesNotExist() {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #ifGenerationNotMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobWriteOptions ifGenerationMatch(long genId) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, genId);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches the provided value. This option can not be provided together
         * with {@link #ifGenerationMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobWriteOptions ifGenerationNotMatch(long genId) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH, genId);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #ifMetagenerationNotMatch(long)}.
         */
        public static BlobWriteOptions ifMetagenerationMatch(long metaGenId) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH, metaGenId);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #ifMetagenerationMatch(long)}.
         */
        public static BlobWriteOptions ifMetagenerationNotMatch(long metaGenId) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGenId);
        }

        /**
         * Returns an option for blob's data MD5 hash match. If this option is used the request will
         * fail if blobs' data MD5 hash does not match the provided value.
         */
        public static BlobWriteOptions ifMd5Match(String checksum) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.IF_MD5_MATCH, checksum);
        }

        /**
         * Returns an option for blob's data CRC32C checksum match. If this option is used the request
         * will fail if blobs' data CRC32C checksum does not match the provided value.
         */
        public static BlobWriteOptions ifCrc32cMatch(String checksum) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.IF_CRC32C_MATCH, checksum);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobWriteOptions withEncryptionKey(Key encryptionKeyObj) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKeyObj.getEncoded());
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKeyObj the AES256 encoded in base64
         */
        public static BlobWriteOptions withEncryptionKey(String encryptionKeyObj) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKeyObj);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobWriteOptions withUserProject(String projectId) {
            return new BlobWriteOptions(Storage.BlobWriteOptions.StorageOption.USER_PROJECT, projectId);
        }

        static Tuple<BlobMetadata, Storage.BlobWriteOptions[]> asWriteOptionsTuple(BlobMetadata blobMetadata, BlobWriteOptions... storageClientConfig) {
            Set<Storage.BlobWriteOptions.StorageOption> storageOptionCollection = Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageClientConfig), TO_ENUM_FN));
            checkArgument(!(storageOptionCollection.contains(Storage.BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH) && storageOptionCollection.contains(Storage.BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(storageOptionCollection.contains(Storage.BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH) && storageOptionCollection.contains(Storage.BlobWriteOptions.StorageOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            Storage.BlobWriteOptions[] normalizedSourceArray = new Storage.BlobWriteOptions[storageClientConfig.length];
            BlobMetadata writeMetadata = blobMetadata;
            int i = 0;
            for (BlobWriteOptions sourceSetting : storageClientConfig) {
                Tuple<BlobMetadata, Storage.BlobWriteOptions> metadataWritePair = sourceSetting.asWriteOption(writeMetadata);
                writeMetadata = metadataWritePair.x();
                normalizedSourceArray[i++] = metadataWritePair.y();
            }
            return Tuple.of(writeMetadata, normalizedSourceArray);
        }
    }

    /**
     * Builder for {@code Bucket}.
     */
    public static class BucketInfoBuilder extends BucketBuilder {

        private final Storage backendClient;

        private final BucketBuilderImpl bucketBuilder;

        BucketInfoBuilder(StorageBucket storageBucket) {
            this.backendClient = storageBucket.backendClient;
            this.bucketBuilder = new BucketBuilderImpl(storageBucket);
        }

        @Override
        public StorageBucket.BucketInfoBuilder setName(String bucketName) {
            bucketBuilder.setName(bucketName);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setGeneratedId(String generatedIdentifier) {
            bucketBuilder.setGeneratedId(generatedIdentifier);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setOwner(AccessControlEntry.AbstractEntity entityRef) {
            bucketBuilder.setOwner(entityRef);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setSelfLink(String resourceLink) {
            bucketBuilder.setSelfLink(resourceLink);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setVersioningEnabled(Boolean isEnabled) {
            bucketBuilder.setVersioningEnabled(isEnabled);
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
        public StorageBucket.BucketInfoBuilder setNotFoundPage(String errorDocument) {
            bucketBuilder.setNotFoundPage(errorDocument);
            return this;
        }

        @Override
        @Deprecated
        public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends DeletionRule> deleteDefinitions) {
            bucketBuilder.setDeleteRules(deleteDefinitions);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deleteDefinitions) {
            bucketBuilder.setLifecycleRules(deleteDefinitions);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder clearLifecycleRules() {
            bucketBuilder.clearLifecycleRules();
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setRpo(RecoveryPointObjective recoveryPointObjective) {
            bucketBuilder.setRpo(recoveryPointObjective);
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
        StorageBucket.BucketInfoBuilder setEtag(String checksum) {
            bucketBuilder.setEtag(checksum);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setCreateTime(Long creationTimestamp) {
            bucketBuilder.setCreateTime(creationTimestamp);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setUpdateTime(Long lastModified) {
            bucketBuilder.setUpdateTime(lastModified);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setMetageneration(Long metaGenId) {
            bucketBuilder.setMetageneration(metaGenId);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setCors(Iterable<CorsConfiguration> crossOriginPolicies) {
            bucketBuilder.setCors(crossOriginPolicies);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setAcl(Iterable<AccessControlEntry> accessControlList) {
            bucketBuilder.setAcl(accessControlList);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<AccessControlEntry> accessControlList) {
            bucketBuilder.setDefaultAcl(accessControlList);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> tagMap) {
            bucketBuilder.setLabels(tagMap);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultKmsKeyName(String primaryKmsKey) {
            bucketBuilder.setDefaultKmsKeyName(primaryKmsKey);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultEventBasedHold(Boolean eventHoldByDefault) {
            bucketBuilder.setDefaultEventBasedHold(eventHoldByDefault);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long retentionStartTime) {
            bucketBuilder.setRetentionEffectiveTime(retentionStartTime);
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
        this.storageClientConfig = backendClient.getOptions();
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
    public boolean bucketExists(BucketSourceOptions... storageClientConfig) {
        int optCount = storageClientConfig.length;
        Storage.GetBucketOption[] resolvedOptions = Arrays.copyOf(asGetOptionsArray(this, storageClientConfig), optCount + 1);
        resolvedOptions[optCount] = Storage.GetBucketOption.withFields();
        return null != backendClient.get(getName(), resolvedOptions);
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
     * @param storageClientConfig bucket read options
     * @return a {@code Bucket} object with latest information or {@code null} if not found
     * @throws StorageServiceException upon failure
     */
    public StorageBucket refresh(BucketSourceOptions... storageClientConfig) {
        return backendClient.get(getName(), asGetOptionsArray(this, storageClientConfig));
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
     * @param storageClientConfig update options
     * @return a {@code Bucket} object with updated information
     * @throws StorageServiceException upon failure
     */
    public StorageBucket updateBucket(Storage.BucketTargetOptions... storageClientConfig) {
        return backendClient.update(this, storageClientConfig);
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
     * @param storageClientConfig bucket delete options
     * @return {@code true} if bucket was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    public boolean deleteBucket(BucketSourceOptions... storageClientConfig) {
        return backendClient.delete(getName(), asSourceOptionsArray(this, storageClientConfig));
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
     * @param storageClientConfig options for listing blobs
     * @throws StorageServiceException upon failure
     */
    public Page<StorageObject> listObjects(BlobListOptions... storageClientConfig) {
        return backendClient.list(getName(), storageClientConfig);
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
     * @param storageClientConfig blob search options
     * @throws StorageServiceException upon failure
     */
    public StorageObject get(String objectName, BlobGetOptions... storageClientConfig) {
        return backendClient.get(BlobId.from(getName(), objectName), storageClientConfig);
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
        List<BlobId> objectIds = Lists.newArrayListWithCapacity(objectNames.length + 2);
        objectIds.add(BlobId.from(getName(), firstObjectName));
        objectIds.add(BlobId.from(getName(), secondObjectName));
        for (String objectName : objectNames) {
            objectIds.add(BlobId.from(getName(), objectName));
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
        ImmutableList.Builder<BlobId> idFactory = ImmutableList.builder();
        for (String objectName : objectNames) {
            idFactory.add(BlobId.from(getName(), objectName));
        }
        return backendClient.get(idFactory.build());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#newWriter(Storage.BlobWriteOptions...)} is
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
     * @param dataBytes the blob content
     * @param mimeType the blob content type
     * @param storageClientConfig options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectName, byte[] dataBytes, String mimeType, BlobTargetOptions... storageClientConfig) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobId.from(getName(), objectName)).setContentType(mimeType).buildObject();
        Tuple<BlobMetadata, Storage.BlobUploadOption[]> metadataUploadPair = BlobTargetOptions.asTargetOptionsTuple(blobMetadata, storageClientConfig);
        return backendClient.create(metadataUploadPair.x(), dataBytes, metadataUploadPair.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#newWriter(Storage.BlobWriteOptions...)} is
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
     * @param dataBytes the blob content as a stream
     * @param mimeType the blob content type
     * @param storageClientConfig options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectName, InputStream dataBytes, String mimeType, BlobWriteOptions... storageClientConfig) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobId.from(getName(), objectName)).setContentType(mimeType).buildObject();
        Tuple<BlobMetadata, Storage.BlobWriteOptions[]> metadataWritePair = StorageBucket.BlobWriteOptions.asWriteOptionsTuple(blobMetadata, storageClientConfig);
        return backendClient.create(metadataWritePair.x(), dataBytes, metadataWritePair.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#newWriter(Storage.BlobWriteOptions...)} is
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
     * @param dataBytes the blob content
     * @param storageClientConfig options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectName, byte[] dataBytes, BlobTargetOptions... storageClientConfig) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobId.from(getName(), objectName)).buildObject();
        Tuple<BlobMetadata, Storage.BlobUploadOption[]> metadataUploadPair = BlobTargetOptions.asTargetOptionsTuple(blobMetadata, storageClientConfig);
        return backendClient.create(metadataUploadPair.x(), dataBytes, metadataUploadPair.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#newWriter(Storage.BlobWriteOptions...)} is
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
     * @param dataBytes the blob content as a stream
     * @param storageClientConfig options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectName, InputStream dataBytes, BlobWriteOptions... storageClientConfig) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobId.from(getName(), objectName)).buildObject();
        Tuple<BlobMetadata, Storage.BlobWriteOptions[]> metadataWritePair = StorageBucket.BlobWriteOptions.asWriteOptionsTuple(blobMetadata, storageClientConfig);
        return backendClient.create(metadataWritePair.x(), dataBytes, metadataWritePair.y());
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
    public AccessControlEntry getAcl(AccessControlEntry.AbstractEntity principal) {
        return backendClient.getAcl(getName(), principal);
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
    public boolean removeAcl(AccessControlEntry.AbstractEntity principal) {
        return backendClient.deleteAcl(getName(), principal);
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
    public AccessControlEntry addAcl(AccessControlEntry accessControlList) {
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
    public AccessControlEntry updateAccessControl(AccessControlEntry accessControlList) {
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
    public List<AccessControlEntry> listAcl() {
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
    public AccessControlEntry getDefaultAcl(AccessControlEntry.AbstractEntity principal) {
        return backendClient.getDefaultAcl(getName(), principal);
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
    public boolean removeDefaultAcl(AccessControlEntry.AbstractEntity principal) {
        return backendClient.deleteDefaultAcl(getName(), principal);
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
    public AccessControlEntry addDefaultAcl(AccessControlEntry accessControlList) {
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
    public AccessControlEntry updateDefaultAccessControl(AccessControlEntry accessControlList) {
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
    public List<AccessControlEntry> listDefaultAcl() {
        return backendClient.listDefaultAcls(getName());
    }

    /**
     * Locks bucket retention policy. Requires a local metageneration value in the request. Review
     * example below.
     *
     * <p>Accepts an optional userProject {@link Storage.BucketTargetOptions} option which defines the project
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
    public StorageBucket lockRetention(BucketTargetOptions... storageClientConfig) {
        return backendClient.lockRetentionPolicy(this, storageClientConfig);
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
        StorageBucket rhs = (StorageBucket) candidate;
        return Objects.equals(toProto(), rhs.toProto()) && Objects.equals(storageClientConfig, rhs.storageClientConfig);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), storageClientConfig);
    }

    private void readObject(ObjectInputStream objectStream) throws IOException, ClassNotFoundException {
        objectStream.defaultReadObject();
        this.backendClient = storageClientConfig.getService();
    }

    static StorageBucket fromProto(Storage backendClient, com.google.api.services.storage.model.Bucket protoMessage) {
        return new StorageBucket(backendClient, new BucketBuilderImpl(BucketMetadata.fromProto(protoMessage)));
    }
}
