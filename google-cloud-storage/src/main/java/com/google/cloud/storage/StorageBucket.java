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
import static com.google.cloud.storage.StorageBucket.BucketSourceOptions.toBucketSourceOptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
import com.google.cloud.storage.AccessControlEntry.ProtoEntity;
import com.google.cloud.storage.CloudStorageClient.BlobGetOptions;
import com.google.cloud.storage.CloudStorageClient.BucketTargetOptions;
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
 * #refresh}. {@code Bucket} adds a layer of service-related functionality over {@link BucketInfo}.
 */
public class StorageBucket extends BucketInfo {

    private static final long serialVersionUID = 8574601739542252586L;

    private final StorageClientOptions clientConfig;

    private transient CloudStorageClient cloudClient;

    /**
     * Class for specifying bucket source options when {@code Bucket} methods are used.
     */
    public static class BucketSourceOptions extends AbstractOption {

        private static final long serialVersionUID = 6928872234155522371L;

        private BucketSourceOptions(StorageRpcClient.StorageOption rpcSetting) {
            super(rpcSetting, null);
        }

        private BucketSourceOptions(StorageRpcClient.StorageOption rpcSetting, Object obj) {
            super(rpcSetting, obj);
        }

        private CloudStorageClient.BucketOption toBucketSourceOption(BucketInfo bucketMetadata) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return CloudStorageClient.BucketOption.ifMetagenerationMatch(bucketMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return CloudStorageClient.BucketOption.ifMetagenerationNotMatch(bucketMetadata.getMetageneration());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private CloudStorageClient.BucketGetOptions toBucketGetOption(BucketInfo bucketMetadata) {
            switch(getRpcOption()) {
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
        public static BucketSourceOptions requireMetagenerationMatch() {
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

        static CloudStorageClient.BucketOption[] toBucketSourceOptions(BucketInfo bucketMetadata, BucketSourceOptions... clientConfig) {
            CloudStorageClient.BucketOption[] convertedArray = new CloudStorageClient.BucketOption[clientConfig.length];
            int idx = 0;
            for (BucketSourceOptions srcOption : clientConfig) {
                convertedArray[idx++] = srcOption.toBucketSourceOption(bucketMetadata);
            }
            return convertedArray;
        }

        static CloudStorageClient.BucketGetOptions[] toBucketGetOptions(BucketInfo bucketMetadata, BucketSourceOptions... clientConfig) {
            CloudStorageClient.BucketGetOptions[] convertedArray = new CloudStorageClient.BucketGetOptions[clientConfig.length];
            int idx = 0;
            for (BucketSourceOptions srcOption : clientConfig) {
                convertedArray[idx++] = srcOption.toBucketGetOption(bucketMetadata);
            }
            return convertedArray;
        }
    }

    /**
     * Class for specifying blob target options when {@code Bucket} methods are used.
     */
    public static class BlobUploadOption extends AbstractOption {

        private static final Function<BlobUploadOption, StorageRpcClient.StorageOption> TO_ENUM_NAME = new Function<BlobUploadOption, StorageRpcClient.StorageOption>() {

            @Override
            public StorageRpcClient.StorageOption apply(BlobUploadOption blobTargetOption) {
                return blobTargetOption.getRpcOption();
            }
        };

        private static final long serialVersionUID = 8345296337342509425L;

        private BlobUploadOption(StorageRpcClient.StorageOption rpcSetting, Object obj) {
            super(rpcSetting, obj);
        }

        private Tuple<BlobMetadata, CloudStorageClient.BlobUploadOption> toUploadTargetOption(BlobMetadata blobMetadata) {
            BlobIdentifier blobIdentifier = blobMetadata.getBlobId();
            switch(getRpcOption()) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, CloudStorageClient.BlobUploadOption.presetAcl((CloudStorageClient.PredefinedAccessControl) getValue()));
                case IF_GENERATION_MATCH:
                    blobIdentifier = BlobIdentifier.from(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.toBuilderCopy().setBlobId(blobIdentifier).buildMetadata(), CloudStorageClient.BlobUploadOption.ifGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    blobIdentifier = BlobIdentifier.from(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.toBuilderCopy().setBlobId(blobIdentifier).buildMetadata(), CloudStorageClient.BlobUploadOption.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.toBuilderCopy().setMetageneration((Long) getValue()).buildMetadata(), CloudStorageClient.BlobUploadOption.ifMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.toBuilderCopy().setMetageneration((Long) getValue()).buildMetadata(), CloudStorageClient.BlobUploadOption.ifMetagenerationNotMatch());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, CloudStorageClient.BlobUploadOption.customerSuppliedKey((String) getValue()));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, CloudStorageClient.BlobUploadOption.kmsKey((String) getValue()));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, CloudStorageClient.BlobUploadOption.userProjectId((String) getValue()));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobUploadOption presetAcl(CloudStorageClient.PredefinedAccessControl accessControl) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.PREDEFINED_ACL, accessControl);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
         * #withGenerationNotMatch(long)}.
         */
        public static BlobUploadOption ensureDoesNotExist() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #withGenerationNotMatch(long)} or {@link #ensureDoesNotExist()}.
         */
        public static BlobUploadOption withGenerationMatch(long generationValue) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, generationValue);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value. This option can not be provided
         * together with {@link #withGenerationMatch(long)} or {@link #ensureDoesNotExist()}.
         */
        public static BlobUploadOption withGenerationNotMatch(long generationValue) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, generationValue);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #withMetagenerationNotMatch(long)}.
         */
        public static BlobUploadOption withMetagenerationMatch(long metaGeneration) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaGeneration);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #withMetagenerationMatch(long)}.
         */
        public static BlobUploadOption withMetagenerationNotMatch(long metaGeneration) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGeneration);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobUploadOption withEncryptionKey(Key encryptionKey) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
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
         * @param kmsKeyPath the KMS key resource id
         */
        public static BlobUploadOption withKmsKeyName(String kmsKeyPath) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.KMS_KEY_NAME, kmsKeyPath);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobUploadOption withUserProject(String projectId) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.USER_PROJECT, projectId);
        }

        static Tuple<BlobMetadata, CloudStorageClient.BlobUploadOption[]> toBlobTargetOptions(BlobMetadata inputInfo, BlobUploadOption... clientConfig) {
            Set<StorageRpcClient.StorageOption> optionCollection = Sets.immutableEnumSet(Lists.transform(Arrays.asList(clientConfig), TO_ENUM_NAME));
            checkArgument(!(optionCollection.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH) && optionCollection.contains(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(optionCollection.contains(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH) && optionCollection.contains(StorageRpcClient.StorageOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            CloudStorageClient.BlobUploadOption[] convertedArray = new CloudStorageClient.BlobUploadOption[clientConfig.length];
            BlobMetadata blobMetadata = inputInfo;
            int idx = 0;
            for (BlobUploadOption srcOption : clientConfig) {
                Tuple<BlobMetadata, CloudStorageClient.BlobUploadOption> metadataPair = srcOption.toUploadTargetOption(blobMetadata);
                blobMetadata = metadataPair.x();
                convertedArray[idx++] = metadataPair.y();
            }
            return Tuple.of(blobMetadata, convertedArray);
        }
    }

    /**
     * Class for specifying blob write options when {@code Bucket} methods are used.
     */
    public static class BlobWriteSettings implements Serializable {

        private static final Function<BlobWriteSettings, CloudStorageClient.BlobWriteOptions.ObjectWriteOption> TO_ENUM_NAME = new Function<BlobWriteSettings, CloudStorageClient.BlobWriteOptions.ObjectWriteOption>() {

            @Override
            public CloudStorageClient.BlobWriteOptions.ObjectWriteOption apply(BlobWriteSettings blobWriteOption) {
                return blobWriteOption.srcOption;
            }
        };

        private static final long serialVersionUID = 4722190734541993114L;

        private final CloudStorageClient.BlobWriteOptions.ObjectWriteOption srcOption;

        private final Object obj;

        private Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions> toObjectWriteOption(BlobMetadata blobMetadata) {
            BlobIdentifier blobIdentifier = blobMetadata.getBlobId();
            switch(srcOption) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, CloudStorageClient.BlobWriteOptions.presetAcl((CloudStorageClient.PredefinedAccessControl) obj));
                case IF_GENERATION_MATCH:
                    blobIdentifier = BlobIdentifier.from(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) obj);
                    return Tuple.of(blobMetadata.toBuilderCopy().setBlobId(blobIdentifier).buildMetadata(), CloudStorageClient.BlobWriteOptions.ifGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    blobIdentifier = BlobIdentifier.from(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) obj);
                    return Tuple.of(blobMetadata.toBuilderCopy().setBlobId(blobIdentifier).buildMetadata(), CloudStorageClient.BlobWriteOptions.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.toBuilderCopy().setMetageneration((Long) obj).buildMetadata(), CloudStorageClient.BlobWriteOptions.ifMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.toBuilderCopy().setMetageneration((Long) obj).buildMetadata(), CloudStorageClient.BlobWriteOptions.ifMetagenerationNotMatch());
                case IF_MD5_MATCH:
                    return Tuple.of(blobMetadata.toBuilderCopy().setMd5((String) obj).buildMetadata(), CloudStorageClient.BlobWriteOptions.ifMd5Match());
                case IF_CRC32C_MATCH:
                    return Tuple.of(blobMetadata.toBuilderCopy().setCrc32c((String) obj).buildMetadata(), CloudStorageClient.BlobWriteOptions.ifCrc32cMatch());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, CloudStorageClient.BlobWriteOptions.withEncryptionKey((String) obj));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, CloudStorageClient.BlobWriteOptions.withKmsKeyName((String) obj));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, CloudStorageClient.BlobWriteOptions.withUserProject((String) obj));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption srcOption, Object obj) {
            this.srcOption = srcOption;
            this.obj = obj;
        }

        @Override
        public int hashCode() {
            return Objects.hash(srcOption, obj);
        }

        @Override
        public boolean equals(Object candidate) {
            if (null == candidate) {
                return false;
            }
            if (!(candidate instanceof BlobWriteSettings)) {
                return false;
            }
            final BlobWriteSettings rhsSettings = (BlobWriteSettings) candidate;
            return rhsSettings.srcOption == this.srcOption && Objects.equals(this.obj, rhsSettings.obj);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobWriteSettings presetAcl(CloudStorageClient.PredefinedAccessControl accessControl) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.PREDEFINED_ACL, accessControl);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
         * #withGenerationNotMatch(long)}.
         */
        public static BlobWriteSettings ensureDoesNotExist() {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #withGenerationNotMatch(long)} or {@link #ensureDoesNotExist()}.
         */
        public static BlobWriteSettings withGenerationMatch(long generationValue) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_GENERATION_MATCH, generationValue);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches the provided value. This option can not be provided together
         * with {@link #withGenerationMatch(long)} or {@link #ensureDoesNotExist()}.
         */
        public static BlobWriteSettings withGenerationNotMatch(long generationValue) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_GENERATION_NOT_MATCH, generationValue);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #withMetagenerationNotMatch(long)}.
         */
        public static BlobWriteSettings withMetagenerationMatch(long metaGeneration) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_METAGENERATION_MATCH, metaGeneration);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #withMetagenerationMatch(long)}.
         */
        public static BlobWriteSettings withMetagenerationNotMatch(long metaGeneration) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_METAGENERATION_NOT_MATCH, metaGeneration);
        }

        /**
         * Returns an option for blob's data MD5 hash match. If this option is used the request will
         * fail if blobs' data MD5 hash does not match the provided value.
         */
        public static BlobWriteSettings withMd5Match(String checksum) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_MD5_MATCH, checksum);
        }

        /**
         * Returns an option for blob's data CRC32C checksum match. If this option is used the request
         * will fail if blobs' data CRC32C checksum does not match the provided value.
         */
        public static BlobWriteSettings withCrc32cMatch(String crcChecksum) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_CRC32C_MATCH, crcChecksum);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobWriteSettings withEncryptionKey(Key encryptionKey) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKey the AES256 encoded in base64
         */
        public static BlobWriteSettings withEncryptionKey(String encryptionKey) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobWriteSettings withUserProject(String projectId) {
            return new BlobWriteSettings(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.USER_PROJECT, projectId);
        }

        static Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions[]> toBlobWriteOptions(BlobMetadata blobMetadata, BlobWriteSettings... clientConfig) {
            Set<CloudStorageClient.BlobWriteOptions.ObjectWriteOption> optionCollection = Sets.immutableEnumSet(Lists.transform(Arrays.asList(clientConfig), TO_ENUM_NAME));
            checkArgument(!(optionCollection.contains(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_METAGENERATION_NOT_MATCH) && optionCollection.contains(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(optionCollection.contains(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_GENERATION_NOT_MATCH) && optionCollection.contains(CloudStorageClient.BlobWriteOptions.ObjectWriteOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            CloudStorageClient.BlobWriteOptions[] convertedArray = new CloudStorageClient.BlobWriteOptions[clientConfig.length];
            BlobMetadata writeMetadata = blobMetadata;
            int idx = 0;
            for (BlobWriteSettings srcOption : clientConfig) {
                Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions> operationPair = srcOption.toObjectWriteOption(writeMetadata);
                writeMetadata = operationPair.x();
                convertedArray[idx++] = operationPair.y();
            }
            return Tuple.of(writeMetadata, convertedArray);
        }
    }

    /**
     * Builder for {@code Bucket}.
     */
    public static class BucketInfoBuilder extends BucketInfo.Builder {

        private final CloudStorageClient cloudClient;

        private final BucketBuilderImpl builder;

        BucketInfoBuilder(StorageBucket bucketHandle) {
            this.cloudClient = bucketHandle.cloudClient;
            this.builder = new BucketBuilderImpl(bucketHandle);
        }

        @Override
        public StorageBucket.BucketInfoBuilder setName(String label) {
            builder.setName(label);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setGeneratedId(String createdIdentifier) {
            builder.setGeneratedId(createdIdentifier);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setOwner(ProtoEntity entityHolder) {
            builder.setOwner(entityHolder);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setSelfLink(String resourceUrl) {
            builder.setSelfLink(resourceUrl);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setVersioningEnabled(Boolean versioningEnabled) {
            builder.setVersioningEnabled(versioningEnabled);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setRequesterPays(Boolean requesterCharged) {
            builder.setRequesterPays(requesterCharged);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setIndexPage(String indexDocument) {
            builder.setIndexPage(indexDocument);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setNotFoundPage(String errorPage) {
            builder.setNotFoundPage(errorPage);
            return this;
        }

        @Override
        @Deprecated
        public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends DeletionRule> deletionSet) {
            builder.setDeleteRules(deletionSet);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deletionSet) {
            builder.setLifecycleRules(deletionSet);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder deleteLifecycleRules() {
            builder.deleteLifecycleRules();
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setStorageClass(StorageClassType storageTier) {
            builder.setStorageClass(storageTier);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLocation(String region) {
            builder.setLocation(region);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setEtag(String entityTag) {
            builder.setEtag(entityTag);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setCreateTime(Long createdAt) {
            builder.setCreateTime(createdAt);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setUpdateTime(Long updatedAt) {
            builder.setUpdateTime(updatedAt);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setMetageneration(Long metaGeneration) {
            builder.setMetageneration(metaGeneration);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setCors(Iterable<Cors> crossOriginConfigs) {
            builder.setCors(crossOriginConfigs);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setAcl(Iterable<AccessControlEntry> accessControl) {
            builder.setAcl(accessControl);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<AccessControlEntry> accessControl) {
            builder.setDefaultAcl(accessControl);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> metadata) {
            builder.setLabels(metadata);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultKmsKeyName(String kmsKeyIdentifier) {
            builder.setDefaultKmsKeyName(kmsKeyIdentifier);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultEventBasedHold(Boolean eventHoldDefault) {
            builder.setDefaultEventBasedHold(eventHoldDefault);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long retentionStartTime) {
            builder.setRetentionEffectiveTime(retentionStartTime);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setRetentionPolicyIsLocked(Boolean retentionLocked) {
            builder.setRetentionPolicyIsLocked(retentionLocked);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setRetentionPeriod(Long retentionDuration) {
            builder.setRetentionPeriod(retentionDuration);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setIamConfiguration(BucketIamConfiguration iamConfig) {
            builder.setIamConfiguration(iamConfig);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLogging(LoggingConfig logConfig) {
            builder.setLogging(logConfig);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setLocationType(String regionType) {
            builder.setLocationType(regionType);
            return this;
        }

        @Override
        public StorageBucket buildBucketInfo() {
            return new StorageBucket(cloudClient, builder);
        }
    }

    StorageBucket(CloudStorageClient cloudClient, BucketBuilderImpl builder) {
        super(builder);
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
    public boolean bucketExists(BucketSourceOptions... clientConfig) {
        int size = clientConfig.length;
        CloudStorageClient.BucketGetOptions[] resolvedGetParams = Arrays.copyOf(toBucketGetOptions(this, clientConfig), size + 1);
        resolvedGetParams[size] = CloudStorageClient.BucketGetOptions.selectedFields();
        return null != cloudClient.get(getName(), resolvedGetParams);
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
    public StorageBucket refresh(BucketSourceOptions... clientConfig) {
        return cloudClient.get(getName(), toBucketGetOptions(this, clientConfig));
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
    public StorageBucket modify(CloudStorageClient.BucketTargetOptions... clientConfig) {
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
    public boolean remove(BucketSourceOptions... clientConfig) {
        return cloudClient.delete(getName(), toBucketSourceOptions(this, clientConfig));
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
    public Page<StorageObject> listAll(CloudStorageClient.BlobListOptions... clientConfig) {
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
    public StorageObject get(String objectName, BlobGetOptions... clientConfig) {
        return cloudClient.get(BlobIdentifier.from(getName(), objectName), clientConfig);
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
        List<BlobIdentifier> blobIdentifierList = Lists.newArrayListWithCapacity(objectNames.length + 2);
        blobIdentifierList.add(BlobIdentifier.from(getName(), firstObjectName));
        blobIdentifierList.add(BlobIdentifier.from(getName(), secondObjectName));
        for (String name : objectNames) {
            blobIdentifierList.add(BlobIdentifier.from(getName(), name));
        }
        return cloudClient.get(blobIdentifierList);
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
        ImmutableList.Builder<BlobIdentifier> idFactory = ImmutableList.builder();
        for (String name : objectNames) {
            idFactory.add(BlobIdentifier.from(getName(), name));
        }
        return cloudClient.get(idFactory.build());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#newWriter(CloudStorageClient.BlobWriteOptions...)} is
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
     * @param data the blob content
     * @param mimeType the blob content type
     * @param clientConfig options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectName, byte[] data, String mimeType, BlobUploadOption... clientConfig) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.from(getName(), objectName)).setContentType(mimeType).buildMetadata();
        Tuple<BlobMetadata, CloudStorageClient.BlobUploadOption[]> metadataPair = StorageBucket.BlobUploadOption.toBlobTargetOptions(blobMetadata, clientConfig);
        return cloudClient.create(metadataPair.x(), data, metadataPair.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#newWriter(CloudStorageClient.BlobWriteOptions...)} is
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
     * @param data the blob content as a stream
     * @param mimeType the blob content type
     * @param clientConfig options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectName, InputStream data, String mimeType, BlobWriteSettings... clientConfig) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.from(getName(), objectName)).setContentType(mimeType).buildMetadata();
        Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions[]> operationPair = BlobWriteSettings.toBlobWriteOptions(blobMetadata, clientConfig);
        return cloudClient.create(operationPair.x(), data, operationPair.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#newWriter(CloudStorageClient.BlobWriteOptions...)} is
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
     * @param data the blob content
     * @param clientConfig options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectName, byte[] data, BlobUploadOption... clientConfig) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.from(getName(), objectName)).buildMetadata();
        Tuple<BlobMetadata, CloudStorageClient.BlobUploadOption[]> metadataPair = StorageBucket.BlobUploadOption.toBlobTargetOptions(blobMetadata, clientConfig);
        return cloudClient.create(metadataPair.x(), data, metadataPair.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageObject#newWriter(CloudStorageClient.BlobWriteOptions...)} is
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
     * @param data the blob content as a stream
     * @param clientConfig options for blob creation
     * @return a complete blob information
     * @throws StorageServiceException upon failure
     */
    public StorageObject createBlob(String objectName, InputStream data, BlobWriteSettings... clientConfig) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.from(getName(), objectName)).buildMetadata();
        Tuple<BlobMetadata, CloudStorageClient.BlobWriteOptions[]> operationPair = BlobWriteSettings.toBlobWriteOptions(blobMetadata, clientConfig);
        return cloudClient.create(operationPair.x(), data, operationPair.y());
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
    public AccessControlEntry getAcl(ProtoEntity principal) {
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
    public boolean removeAcl(ProtoEntity principal) {
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
    public AccessControlEntry addAcl(AccessControlEntry accessControl) {
        return cloudClient.createAcl(getName(), accessControl);
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
    public AccessControlEntry modifyAcl(AccessControlEntry accessControl) {
        return cloudClient.updateAcl(getName(), accessControl);
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
    public List<AccessControlEntry> listAllAcls() {
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
    public AccessControlEntry getDefaultAcl(ProtoEntity principal) {
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
    public boolean removeDefaultAcl(ProtoEntity principal) {
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
    public AccessControlEntry addDefaultAcl(AccessControlEntry accessControl) {
        return cloudClient.createDefaultAcl(getName(), accessControl);
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
    public AccessControlEntry modifyDefaultAcl(AccessControlEntry accessControl) {
        return cloudClient.updateDefaultAcl(getName(), accessControl);
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
    public List<AccessControlEntry> listAllDefaultAcls() {
        return cloudClient.listDefaultAcls(getName());
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
    public StorageBucket lockRetention(BucketTargetOptions... clientConfig) {
        return cloudClient.lockRetentionPolicy(this, clientConfig);
    }

    /**
     * Returns the bucket's {@code Storage} object used to issue requests.
     */
    public CloudStorageClient getStorage() {
        return cloudClient;
    }

    @Override
    public StorageBucket.BucketInfoBuilder toBucketBuilder() {
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
        StorageBucket rhsSettings = (StorageBucket) candidate;
        return Objects.equals(toProto(), rhsSettings.toProto()) && Objects.equals(clientConfig, rhsSettings.clientConfig);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), clientConfig);
    }

    private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
        inputStream.defaultReadObject();
        this.cloudClient = clientConfig.getService();
    }

    static StorageBucket fromProto(CloudStorageClient cloudClient, com.google.api.services.storage.model.Bucket storageProto) {
        return new StorageBucket(cloudClient, new BucketBuilderImpl(BucketInfo.fromProto(storageProto)));
    }
}
