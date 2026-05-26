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

import static com.google.cloud.storage.StorageBucket.BucketSourceParameter.toBucketGetOptions;
import static com.google.cloud.storage.StorageBucket.BucketSourceParameter.toBucketSourceOptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.api.gax.paging.Page;
import com.google.cloud.Tuple;
import com.google.cloud.storage.AccessControlEntry.TypedEntity;
import com.google.cloud.storage.StorageClient.BlobListOptions;
import com.google.cloud.storage.StorageClient.BucketTargetRequestOption;
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
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

    private transient StorageClient client;

    /**
     * Class for specifying bucket source options when {@code Bucket} methods are used.
     */
    public static class BucketSourceParameter extends RpcOptionWrapper {

        private static final long serialVersionUID = 6928872234155522371L;

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BucketSourceParameter withMetagenerationMatch() {
            return new BucketSourceParameter(CloudStorageRpc.StorageOption.IF_METAGENERATION_MATCH);
        }

        static StorageClient.BucketSourceOptions[] toBucketSourceOptions(BucketMetadata bucketMetadata, BucketSourceParameter... storageSettings) {
            StorageClient.BucketSourceOptions[] sourceOptionsArray = new StorageClient.BucketSourceOptions[storageSettings.length];
            int i = 0;
            for (BucketSourceParameter param : storageSettings) {
                sourceOptionsArray[i++] = param.toBucketSourceOption(bucketMetadata);
            }
            return sourceOptionsArray;
        }

        static StorageClient.BucketGetOptions[] toBucketGetOptions(BucketMetadata bucketMetadata, BucketSourceParameter... storageSettings) {
            StorageClient.BucketGetOptions[] sourceOptionsArray = new StorageClient.BucketGetOptions[storageSettings.length];
            int i = 0;
            for (BucketSourceParameter param : storageSettings) {
                sourceOptionsArray[i++] = param.toBucketGetOption(bucketMetadata);
            }
            return sourceOptionsArray;
        }

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if metageneration matches.
         */
        public static BucketSourceParameter metagenerationNotMatch() {
            return new BucketSourceParameter(CloudStorageRpc.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        private StorageClient.BucketGetOptions toBucketGetOption(BucketMetadata bucketMetadata) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return StorageClient.BucketGetOptions.withMetagenerationMatch(bucketMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return StorageClient.BucketGetOptions.withMetagenerationNotMatch(bucketMetadata.getMetageneration());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        private BucketSourceParameter(CloudStorageRpc.StorageOption rpcSetting) {
            super(rpcSetting, null);
        }

        private BucketSourceParameter(CloudStorageRpc.StorageOption rpcSetting, Object obj) {
            super(rpcSetting, obj);
        }

        private StorageClient.BucketSourceOptions toBucketSourceOption(BucketMetadata bucketMetadata) {
            switch(getRpcOption()) {
                case IF_METAGENERATION_MATCH:
                    return StorageClient.BucketSourceOptions.withMetagenerationMatch(bucketMetadata.getMetageneration());
                case IF_METAGENERATION_NOT_MATCH:
                    return StorageClient.BucketSourceOptions.withMetagenerationNotMatch(bucketMetadata.getMetageneration());
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BucketSourceParameter userProject(String projectId) {
            return new BucketSourceParameter(CloudStorageRpc.StorageOption.USER_PROJECT, projectId);
        }

    }

    /**
     * Class for specifying blob target options when {@code Bucket} methods are used.
     */
    public static class BlobUploadOption extends RpcOptionWrapper {

        private static final Function<BlobUploadOption, CloudStorageRpc.StorageOption> BLOB_TO_STORAGE_FN = new Function<BlobUploadOption, CloudStorageRpc.StorageOption>() {

            @Override
            public CloudStorageRpc.StorageOption apply(BlobUploadOption blobTargetOption) {
                return blobTargetOption.getRpcOption();
            }
        };

        private static final long serialVersionUID = 8345296337342509425L;

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
         * #withGenerationNotMatch(long)}.
         */
        public static BlobUploadOption ifDoesNotExist() {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #withMetagenerationNotMatch(long)}.
         */
        public static BlobUploadOption withMetagenerationMatch(long metaGeneration) {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.IF_METAGENERATION_MATCH, metaGeneration);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #withMetagenerationMatch(long)}.
         */
        public static BlobUploadOption withMetagenerationNotMatch(long metaGeneration) {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.IF_METAGENERATION_NOT_MATCH, metaGeneration);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value. This option can not be provided
         * together with {@link #withGenerationMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobUploadOption withGenerationNotMatch(long generationId) {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.IF_GENERATION_NOT_MATCH, generationId);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobUploadOption withEncryptionKey(Key encryptionKeyObj) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKeyObj.getEncoded());
            return new BlobUploadOption(CloudStorageRpc.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        static Tuple<BlobMetadata, StorageClient.BlobUploadOption[]> toUploadTargetOptions(BlobMetadata blobMetadata, BlobUploadOption... storageSettings) {
            Set<CloudStorageRpc.StorageOption> storageOptionSet = Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), BLOB_TO_STORAGE_FN));
            checkArgument(!(storageOptionSet.contains(CloudStorageRpc.StorageOption.IF_METAGENERATION_NOT_MATCH) && storageOptionSet.contains(CloudStorageRpc.StorageOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(storageOptionSet.contains(CloudStorageRpc.StorageOption.IF_GENERATION_NOT_MATCH) && storageOptionSet.contains(CloudStorageRpc.StorageOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            StorageClient.BlobUploadOption[] sourceOptionsArray = new StorageClient.BlobUploadOption[storageSettings.length];
            BlobMetadata targetInfo = blobMetadata;
            int i = 0;
            for (BlobUploadOption param : storageSettings) {
                Tuple<BlobMetadata, StorageClient.BlobUploadOption> uploadTarget = param.toUploadTargetOption(targetInfo);
                targetInfo = uploadTarget.x();
                sourceOptionsArray[i++] = uploadTarget.y();
            }
            return Tuple.of(targetInfo, sourceOptionsArray);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKeyObj the AES256 encoded in base64
         */
        public static BlobUploadOption withEncryptionKey(String encryptionKeyObj) {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKeyObj);
        }

        /**
         * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
         *
         * @param kmsKeyId the KMS key resource id
         */
        public static BlobUploadOption withKmsKeyName(String kmsKeyId) {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.KMS_KEY_NAME, kmsKeyId);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobUploadOption withPredefinedAcl(StorageClient.PredefinedAccessControlList accessControlList) {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.PREDEFINED_ACL, accessControlList);
        }

        private BlobUploadOption(CloudStorageRpc.StorageOption rpcSetting, Object obj) {
            super(rpcSetting, obj);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobUploadOption withUserProject(String projectId) {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.USER_PROJECT, projectId);
        }

        private Tuple<BlobMetadata, StorageClient.BlobUploadOption> toUploadTargetOption(BlobMetadata blobMetadata) {
            BlobIdentifier blobIdentifier = blobMetadata.getBlobId();
            switch(getRpcOption()) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, StorageClient.BlobUploadOption.withPredefinedAcl((StorageClient.PredefinedAccessControlList) getValue()));
                case IF_GENERATION_MATCH:
                    blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setBlobId(blobIdentifier).buildStorageObject(), StorageClient.BlobUploadOption.ifGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) getValue());
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setBlobId(blobIdentifier).buildStorageObject(), StorageClient.BlobUploadOption.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setMetageneration((Long) getValue()).buildStorageObject(), StorageClient.BlobUploadOption.withMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setMetageneration((Long) getValue()).buildStorageObject(), StorageClient.BlobUploadOption.withMetagenerationNotMatch());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, StorageClient.BlobUploadOption.withEncryptionKey((String) getValue()));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, StorageClient.BlobUploadOption.withKmsKeyName((String) getValue()));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, StorageClient.BlobUploadOption.withUserProject((String) getValue()));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #withGenerationNotMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobUploadOption withGenerationMatch(long generationId) {
            return new BlobUploadOption(CloudStorageRpc.StorageOption.IF_GENERATION_MATCH, generationId);
        }

    }

    /**
     * Class for specifying blob write options when {@code Bucket} methods are used.
     */
    public static class BlobWriteSetting implements Serializable {

        private static final Function<BlobWriteSetting, StorageClient.BlobWriteSetting.RequestOption> BLOB_TO_STORAGE_FN = new Function<BlobWriteSetting, StorageClient.BlobWriteSetting.RequestOption>() {

            @Override
            public StorageClient.BlobWriteSetting.RequestOption apply(BlobWriteSetting blobWriteOption) {
                return blobWriteOption.param;
            }
        };

        private static final long serialVersionUID = 4722190734541993114L;

        private final StorageClient.BlobWriteSetting.RequestOption param;

        private final Object obj;

        /**
         * Returns an option for blob's data CRC32C checksum match. If this option is used the request
         * will fail if blobs' data CRC32C checksum does not match the provided value.
         */
        public static BlobWriteSetting withCrc32cMatch(String crc32c) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.IF_CRC32C_MATCH, crc32c);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobWriteSetting withPredefinedAcl(StorageClient.PredefinedAccessControlList accessControlList) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.PREDEFINED_ACL, accessControlList);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobWriteSetting withUserProject(String projectId) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.USER_PROJECT, projectId);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobWriteSetting withEncryptionKey(Key encryptionKeyObj) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKeyObj.getEncoded());
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches the provided value. This option can not be provided together
         * with {@link #withMetagenerationMatch(long)}.
         */
        public static BlobWriteSetting withMetagenerationNotMatch(long metaGeneration) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.IF_METAGENERATION_NOT_MATCH, metaGeneration);
        }

        /**
         * Returns an option for blob's data MD5 hash match. If this option is used the request will
         * fail if blobs' data MD5 hash does not match the provided value.
         */
        public static BlobWriteSetting withMd5Match(String md5) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.IF_MD5_MATCH, md5);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches the provided value. This option can not be provided together
         * with {@link #withGenerationMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobWriteSetting withGenerationNotMatch(long generationId) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.IF_GENERATION_NOT_MATCH, generationId);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKeyObj the AES256 encoded in base64
         */
        public static BlobWriteSetting withEncryptionKey(String encryptionKeyObj) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.CUSTOMER_SUPPLIED_KEY, encryptionKeyObj);
        }

        static Tuple<BlobMetadata, StorageClient.BlobWriteSetting[]> toBlobWriteOptions(BlobMetadata blobMetadata, BlobWriteSetting... storageSettings) {
            Set<StorageClient.BlobWriteSetting.RequestOption> storageOptionSet = Sets.immutableEnumSet(Lists.transform(Arrays.asList(storageSettings), BLOB_TO_STORAGE_FN));
            checkArgument(!(storageOptionSet.contains(StorageClient.BlobWriteSetting.RequestOption.IF_METAGENERATION_NOT_MATCH) && storageOptionSet.contains(StorageClient.BlobWriteSetting.RequestOption.IF_METAGENERATION_MATCH)), "metagenerationMatch and metagenerationNotMatch options can not be both provided");
            checkArgument(!(storageOptionSet.contains(StorageClient.BlobWriteSetting.RequestOption.IF_GENERATION_NOT_MATCH) && storageOptionSet.contains(StorageClient.BlobWriteSetting.RequestOption.IF_GENERATION_MATCH)), "Only one option of generationMatch, doesNotExist or generationNotMatch can be provided");
            StorageClient.BlobWriteSetting[] sourceOptionsArray = new StorageClient.BlobWriteSetting[storageSettings.length];
            BlobMetadata writeInfo = blobMetadata;
            int i = 0;
            for (BlobWriteSetting param : storageSettings) {
                Tuple<BlobMetadata, StorageClient.BlobWriteSetting> writeBundle = param.toBlobWriteOption(writeInfo);
                writeInfo = writeBundle.x();
                sourceOptionsArray[i++] = writeBundle.y();
            }
            return Tuple.of(writeInfo, sourceOptionsArray);
        }

        @Override
        public boolean equals(Object otherObj) {
            if (null == otherObj) {
                return false;
            }
            if (!(otherObj instanceof BlobWriteSetting)) {
                return false;
            }
            final BlobWriteSetting otherBucket = (BlobWriteSetting) otherObj;
            return otherBucket.param == this.param && Objects.equals(this.obj, otherBucket.obj);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match the provided value. This option can not be provided
         * together with {@link #withMetagenerationNotMatch(long)}.
         */
        public static BlobWriteSetting withMetagenerationMatch(long metaGeneration) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.IF_METAGENERATION_MATCH, metaGeneration);
        }

        private Tuple<BlobMetadata, StorageClient.BlobWriteSetting> toBlobWriteOption(BlobMetadata blobMetadata) {
            BlobIdentifier blobIdentifier = blobMetadata.getBlobId();
            switch(param) {
                case PREDEFINED_ACL:
                    return Tuple.of(blobMetadata, StorageClient.BlobWriteSetting.withPredefinedAcl((StorageClient.PredefinedAccessControlList) obj));
                case IF_GENERATION_MATCH:
                    blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) obj);
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setBlobId(blobIdentifier).buildStorageObject(), StorageClient.BlobWriteSetting.ifGenerationMatch());
                case IF_GENERATION_NOT_MATCH:
                    blobIdentifier = BlobIdentifier.create(blobIdentifier.getBucket(), blobIdentifier.getName(), (Long) obj);
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setBlobId(blobIdentifier).buildStorageObject(), StorageClient.BlobWriteSetting.ifGenerationNotMatch());
                case IF_METAGENERATION_MATCH:
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setMetageneration((Long) obj).buildStorageObject(), StorageClient.BlobWriteSetting.ifMetagenerationMatch());
                case IF_METAGENERATION_NOT_MATCH:
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setMetageneration((Long) obj).buildStorageObject(), StorageClient.BlobWriteSetting.ifMetagenerationNotMatch());
                case IF_MD5_MATCH:
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setMd5((String) obj).buildStorageObject(), StorageClient.BlobWriteSetting.md5Equals());
                case IF_CRC32C_MATCH:
                    return Tuple.of(blobMetadata.toBlobInfoBuilder().setCrc32c((String) obj).buildStorageObject(), StorageClient.BlobWriteSetting.crc32cEquals());
                case CUSTOMER_SUPPLIED_KEY:
                    return Tuple.of(blobMetadata, StorageClient.BlobWriteSetting.customerSuppliedKey((String) obj));
                case KMS_KEY_NAME:
                    return Tuple.of(blobMetadata, StorageClient.BlobWriteSetting.kmsKey((String) obj));
                case USER_PROJECT:
                    return Tuple.of(blobMetadata, StorageClient.BlobWriteSetting.userProjectId((String) obj));
                default:
                    throw new AssertionError("Unexpected enum value");
            }
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match the provided value. This option can not be provided
         * together with {@link #withGenerationNotMatch(long)} or {@link #ifDoesNotExist()}.
         */
        public static BlobWriteSetting withGenerationMatch(long generationId) {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.IF_GENERATION_MATCH, generationId);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         * This option can not be provided together with {@link #withGenerationMatch(long)} or {@link
         * #withGenerationNotMatch(long)}.
         */
        public static BlobWriteSetting ifDoesNotExist() {
            return new BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption.IF_GENERATION_MATCH, 0L);
        }

        @Override
        public int hashCode() {
            return Objects.hash(param, obj);
        }

        private BlobWriteSetting(StorageClient.BlobWriteSetting.RequestOption param, Object obj) {
            this.param = param;
            this.obj = obj;
        }

    }

    /**
     * Builder for {@code Bucket}.
     */
    public static class BucketInfoBuilder extends BucketBuilder {

        private final StorageClient client;

        private final BucketBuilderImpl bucketInfoBuilder;

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultAcl(Iterable<AccessControlEntry> accessControlList) {
            bucketInfoBuilder.setDefaultAcl(accessControlList);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLabels(Map<String, String> tagMap) {
            bucketInfoBuilder.setLabels(tagMap);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setName(String name) {
            bucketInfoBuilder.setName(name);
            return this;
        }

        @Override
        public StorageBucket buildBucket() {
            return new StorageBucket(client, bucketInfoBuilder);
        }

        @Override
        StorageBucket.BucketInfoBuilder setGeneratedId(String generatedId) {
            bucketInfoBuilder.setGeneratedId(generatedId);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setNotFoundPage(String errorPage) {
            bucketInfoBuilder.setNotFoundPage(errorPage);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setLocationType(String locationClass) {
            bucketInfoBuilder.setLocationType(locationClass);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLogging(LoggingConfig loggingConfig) {
            bucketInfoBuilder.setLogging(loggingConfig);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setRetentionEffectiveTime(Long retentionStartTime) {
            bucketInfoBuilder.setRetentionEffectiveTime(retentionStartTime);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setAcl(Iterable<AccessControlEntry> accessControlList) {
            bucketInfoBuilder.setAcl(accessControlList);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setSelfLink(String resourceUrl) {
            bucketInfoBuilder.setSelfLink(resourceUrl);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setCreateTime(Long creationTime) {
            bucketInfoBuilder.setCreateTime(creationTime);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setCors(Iterable<Cors> corsSettings) {
            bucketInfoBuilder.setCors(corsSettings);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setIamConfiguration(BucketIamConfiguration iamConfig) {
            bucketInfoBuilder.setIamConfiguration(iamConfig);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultKmsKeyName(String primaryKmsKeyName) {
            bucketInfoBuilder.setDefaultKmsKeyName(primaryKmsKeyName);
            return this;
        }

        @Override
        @Deprecated
        public StorageBucket.BucketInfoBuilder setDeleteRules(Iterable<? extends AbstractDeleteRule> deleteConditions) {
            bucketInfoBuilder.setDeleteRules(deleteConditions);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setIndexPage(String indexDocument) {
            bucketInfoBuilder.setIndexPage(indexDocument);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setOwner(TypedEntity principalEntity) {
            bucketInfoBuilder.setOwner(principalEntity);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setMetageneration(Long metaGeneration) {
            bucketInfoBuilder.setMetageneration(metaGeneration);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setEtag(String entityTag) {
            bucketInfoBuilder.setEtag(entityTag);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setDefaultEventBasedHold(Boolean eventHoldDefault) {
            bucketInfoBuilder.setDefaultEventBasedHold(eventHoldDefault);
            return this;
        }

        @Override
        StorageBucket.BucketInfoBuilder setRetentionPolicyIsLocked(Boolean retentionLocked) {
            bucketInfoBuilder.setRetentionPolicyIsLocked(retentionLocked);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLocation(String region) {
            bucketInfoBuilder.setLocation(region);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setRequesterPays(Boolean requesterBilled) {
            bucketInfoBuilder.setRequesterPays(requesterBilled);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setStorageClass(StorageTier storageTier) {
            bucketInfoBuilder.setStorageClass(storageTier);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deleteConditions) {
            bucketInfoBuilder.setLifecycleRules(deleteConditions);
            return this;
        }

        BucketInfoBuilder(StorageBucket bucket) {
            this.client = bucket.client;
            this.bucketInfoBuilder = new BucketBuilderImpl(bucket);
        }

        @Override
        public StorageBucket.BucketInfoBuilder setVersioningEnabled(Boolean versioningOn) {
            bucketInfoBuilder.setVersioningEnabled(versioningOn);
            return this;
        }

        @Override
        public StorageBucket.BucketInfoBuilder setRetentionPeriod(Long retentionDuration) {
            bucketInfoBuilder.setRetentionPeriod(retentionDuration);
            return this;
        }

    }

    /**
     * Returns the bucket's {@code Storage} object used to issue requests.
     */
    public StorageClient getStorage() {
        return client;
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
    public StorageBucket refresh(BucketSourceParameter... storageSettings) {
        return client.get(getName(), toBucketGetOptions(this, storageSettings));
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageBlob#openWriter(StorageClient.BlobWriteSetting...)} is
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
     * @param dataBytes the blob content
     * @param mimeType the blob content type
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageOperationException upon failure
     */
    public StorageBlob createBlob(String blobName, byte[] dataBytes, String mimeType, BlobUploadOption... storageSettings) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), blobName)).setContentType(mimeType).buildStorageObject();
        Tuple<BlobMetadata, StorageClient.BlobUploadOption[]> uploadTarget = BlobUploadOption.toUploadTargetOptions(blobMetadata, storageSettings);
        return client.create(uploadTarget.x(), dataBytes, uploadTarget.y());
    }

    /**
     * Locks bucket retention policy. Requires a local metageneration value in the request. Review
     * example below.
     *
     * <p>Accepts an optional userProject {@link BucketTargetRequestOption} option which defines the project
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
    public StorageBucket lockRetention(BucketTargetRequestOption... storageSettings) {
        return client.lockRetentionPolicy(this, storageSettings);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(super.hashCode(), storageSettings);
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
    public AccessControlEntry modifyDefaultAcl(AccessControlEntry accessControlList) {
        return client.updateDefaultAcl(getName(), accessControlList);
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
    public List<AccessControlEntry> getDefaultAcls() {
        return client.listDefaultAcls(getName());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageBlob#openWriter(StorageClient.BlobWriteSetting...)} is
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
     * @param dataBytes the blob content as a stream
     * @param mimeType the blob content type
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageOperationException upon failure
     */
    public StorageBlob createBlob(String blobName, InputStream dataBytes, String mimeType, BlobWriteSetting... storageSettings) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), blobName)).setContentType(mimeType).buildStorageObject();
        Tuple<BlobMetadata, StorageClient.BlobWriteSetting[]> writeBundle = BlobWriteSetting.toBlobWriteOptions(blobMetadata, storageSettings);
        return client.create(writeBundle.x(), dataBytes, writeBundle.y());
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
    public boolean removeDefaultAcl(TypedEntity principalEntity) {
        return client.deleteDefaultAcl(getName(), principalEntity);
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
    public AccessControlEntry modifyAcl(AccessControlEntry accessControlList) {
        return client.updateAcl(getName(), accessControlList);
    }

    @Override
    public final boolean equals(Object otherObj) {
        if (this == otherObj) {
            return true;
        }
        if (null == otherObj || !otherObj.getClass().equals(StorageBucket.class)) {
            return false;
        }
        StorageBucket otherBucket = (StorageBucket) otherObj;
        return Objects.equals(toProto(), otherBucket.toProto()) && Objects.equals(storageSettings, otherBucket.storageSettings);
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
    public Page<StorageBlob> listBlobs(BlobListOptions... storageSettings) {
        return client.list(getName(), storageSettings);
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
    public AccessControlEntry getDefaultAcl(TypedEntity principalEntity) {
        return client.getDefaultAcl(getName(), principalEntity);
    }

    static StorageBucket fromProto(StorageClient client, com.google.api.services.storage.model.Bucket bucketProto) {
        return new StorageBucket(client, new BucketBuilderImpl(BucketMetadata.fromProto(bucketProto)));
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
     * @throws StorageOperationException upon failure
     */
    public List<StorageBlob> get(Iterable<String> blobNameList) {
        ImmutableList.Builder<BlobIdentifier> idBuilder = ImmutableList.builder();
        for (String objectKey : blobNameList) {
            idBuilder.add(BlobIdentifier.create(getName(), objectKey));
        }
        return client.get(idBuilder.build());
    }

    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        this.client = storageSettings.getService();
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageBlob#openWriter(StorageClient.BlobWriteSetting...)} is
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
     * @param dataBytes the blob content
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageOperationException upon failure
     */
    public StorageBlob createBlob(String blobName, byte[] dataBytes, BlobUploadOption... storageSettings) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), blobName)).buildStorageObject();
        Tuple<BlobMetadata, StorageClient.BlobUploadOption[]> uploadTarget = BlobUploadOption.toUploadTargetOptions(blobMetadata, storageSettings);
        return client.create(uploadTarget.x(), dataBytes, uploadTarget.y());
    }

    /**
     * Creates a new blob in this bucket. Direct upload is used to upload {@code content}. For large
     * content, {@link StorageBlob#openWriter(StorageClient.BlobWriteSetting...)} is
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
     * @param dataBytes the blob content as a stream
     * @param storageSettings options for blob creation
     * @return a complete blob information
     * @throws StorageOperationException upon failure
     */
    public StorageBlob createBlob(String blobName, InputStream dataBytes, BlobWriteSetting... storageSettings) {
        BlobMetadata blobMetadata = BlobMetadata.newBuilder(BlobIdentifier.create(getName(), blobName)).buildStorageObject();
        Tuple<BlobMetadata, StorageClient.BlobWriteSetting[]> writeBundle = BlobWriteSetting.toBlobWriteOptions(blobMetadata, storageSettings);
        return client.create(writeBundle.x(), dataBytes, writeBundle.y());
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
    public List<AccessControlEntry> getAcls() {
        return client.listAcls(getName());
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
    public StorageBucket updateBucket(BucketTargetRequestOption... storageSettings) {
        return client.update(this, storageSettings);
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
     * @param blobName name of the requested blob
     * @param storageSettings blob search options
     * @throws StorageOperationException upon failure
     */
    public StorageBlob get(String blobName, StorageClient.BlobGetOptions... storageSettings) {
        return client.get(BlobIdentifier.create(getName(), blobName), storageSettings);
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
     * @throws StorageOperationException upon failure
     */
    public List<StorageBlob> get(String primaryBlobName, String secondaryBlobName, String... blobNameList) {
        List<BlobIdentifier> blobIdentifiers = Lists.newArrayListWithCapacity(blobNameList.length + 2);
        blobIdentifiers.add(BlobIdentifier.create(getName(), primaryBlobName));
        blobIdentifiers.add(BlobIdentifier.create(getName(), secondaryBlobName));
        for (String objectKey : blobNameList) {
            blobIdentifiers.add(BlobIdentifier.create(getName(), objectKey));
        }
        return client.get(blobIdentifiers);
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
    public AccessControlEntry addAcl(AccessControlEntry accessControlList) {
        return client.createAcl(getName(), accessControlList);
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
    public boolean deleteBucket(BucketSourceParameter... storageSettings) {
        return client.delete(getName(), toBucketSourceOptions(this, storageSettings));
    }

    @Override
    public StorageBucket.BucketInfoBuilder toBucketBuilder() {
        return new BucketInfoBuilder(this);
    }

    StorageBucket(StorageClient client, BucketBuilderImpl bucketInfoBuilder) {
        super(bucketInfoBuilder);
        this.client = checkNotNull(client);
        this.storageSettings = client.getOptions();
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
    public boolean bucketExists(BucketSourceParameter... storageSettings) {
        int optionsLength = storageSettings.length;
        StorageClient.BucketGetOptions[] requestedOptions = Arrays.copyOf(toBucketGetOptions(this, storageSettings), optionsLength + 1);
        requestedOptions[optionsLength] = StorageClient.BucketGetOptions.withFields();
        return null != client.get(getName(), requestedOptions);
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
    public boolean removeAcl(TypedEntity principalEntity) {
        return client.deleteAcl(getName(), principalEntity);
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
    public AccessControlEntry getAcl(TypedEntity principalEntity) {
        return client.getAcl(getName(), principalEntity);
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
    public AccessControlEntry addDefaultAcl(AccessControlEntry accessControlList) {
        return client.createDefaultAcl(getName(), accessControlList);
    }

}
