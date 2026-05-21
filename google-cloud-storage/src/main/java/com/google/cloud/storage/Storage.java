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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.api.core.InternalExtensionOnly;
import com.google.api.gax.paging.Page;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.ServiceAccountSigner.SigningException;
import com.google.cloud.FieldSelector;
import com.google.cloud.FieldSelector.Helper;
import com.google.cloud.Policy;
import com.google.cloud.ReadChannel;
import com.google.cloud.Service;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AccessControlEntry.AbstractEntity;
import com.google.cloud.storage.HmacSecretKey.HmacKeyDetails;
import com.google.cloud.storage.S3PostPolicyV4.PostConditionsV4Model;
import com.google.cloud.storage.S3PostPolicyV4.PostFieldsMapV4;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URL;
import java.nio.file.Path;
import java.security.Key;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * An interface for Google Cloud Storage.
 *
 * @see <a href="https://cloud.google.com/storage/docs">Google Cloud Storage</a>
 */
@InternalExtensionOnly
public interface Storage extends Service<StorageClientOptions> {

    enum PredefinedAccessControlList {

        AUTHENTICATED_READ("authenticatedRead"),
        ALL_AUTHENTICATED_USERS("allAuthenticatedUsers"),
        PRIVATE("private"),
        PROJECT_PRIVATE("projectPrivate"),
        PUBLIC_READ("publicRead"),
        PUBLIC_READ_WRITE("publicReadWrite"),
        BUCKET_OWNER_READ("bucketOwnerRead"),
        BUCKET_OWNER_FULL_CONTROL("bucketOwnerFullControl");

        private final String accessRule;

        PredefinedAccessControlList(String accessRule) {
            this.accessRule = accessRule;
        }

        String getEntry() {
            return accessRule;
        }
    }

    enum BucketAttribute implements FieldSelector {

        ID("id"),
        SELF_LINK("selfLink"),
        NAME("name"),
        TIME_CREATED("timeCreated"),
        METAGENERATION("metageneration"),
        ACL("acl"),
        DEFAULT_OBJECT_ACL("defaultObjectAcl"),
        OWNER("owner"),
        LABELS("labels"),
        LOCATION("location"),
        LOCATION_TYPE("locationType"),
        WEBSITE("website"),
        VERSIONING("versioning"),
        CORS("cors"),
        LIFECYCLE("lifecycle"),
        STORAGE_CLASS("storageClass"),
        ETAG("etag"),
        ENCRYPTION("encryption"),
        BILLING("billing"),
        DEFAULT_EVENT_BASED_HOLD("defaultEventBasedHold"),
        RETENTION_POLICY("retentionPolicy"),
        IAMCONFIGURATION("iamConfiguration"),
        LOGGING("logging"),
        UPDATED("updated");

        static final List<? extends FieldSelector> MANDATORY_SELECTORS = ImmutableList.of(NAME);

        private final String fieldKey;

        BucketAttribute(String fieldKey) {
            this.fieldKey = fieldKey;
        }

        @Override
        public String getSelector() {
            return fieldKey;
        }
    }

    enum BlobMetadataField implements FieldSelector {

        ACL("acl"),
        BUCKET("bucket"),
        CACHE_CONTROL("cacheControl"),
        COMPONENT_COUNT("componentCount"),
        CONTENT_DISPOSITION("contentDisposition"),
        CONTENT_ENCODING("contentEncoding"),
        CONTENT_LANGUAGE("contentLanguage"),
        CONTENT_TYPE("contentType"),
        CRC32C("crc32c"),
        ETAG("etag"),
        GENERATION("generation"),
        ID("id"),
        KIND("kind"),
        MD5HASH("md5Hash"),
        MEDIA_LINK("mediaLink"),
        METADATA("metadata"),
        METAGENERATION("metageneration"),
        NAME("name"),
        OWNER("owner"),
        SELF_LINK("selfLink"),
        SIZE("size"),
        STORAGE_CLASS("storageClass"),
        TIME_DELETED("timeDeleted"),
        TIME_CREATED("timeCreated"),
        KMS_KEY_NAME("kmsKeyName"),
        EVENT_BASED_HOLD("eventBasedHold"),
        TEMPORARY_HOLD("temporaryHold"),
        RETENTION_EXPIRATION_TIME("retentionExpirationTime"),
        UPDATED("updated"),
        CUSTOM_TIME("customTime"),
        TIME_STORAGE_CLASS_UPDATED("timeStorageClassUpdated");

        static final List<? extends FieldSelector> MANDATORY_SELECTORS = ImmutableList.of(BUCKET, NAME);

        private final String fieldKey;

        BlobMetadataField(String fieldKey) {
            this.fieldKey = fieldKey;
        }

        @Override
        public String getSelector() {
            return fieldKey;
        }
    }

    enum UriSchemeType {

        HTTP("http"), HTTPS("https");

        private final String protocolName;

        UriSchemeType(String protocolName) {
            this.protocolName = protocolName;
        }

        public String getScheme() {
            return protocolName;
        }
    }

    /**
     * Class for specifying bucket target options.
     */
    class BucketTargetOptions extends AbstractOption {

        private static final long serialVersionUID = -5880204616982900975L;

        private BucketTargetOptions(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        private BucketTargetOptions(StorageRpcClient.StorageOption storageOption) {
            this(storageOption, null);
        }

        /**
         * Returns an option for specifying bucket's predefined ACL configuration.
         */
        public static BucketTargetOptions withPredefinedAcl(PredefinedAccessControlList accessControlList) {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.PREDEFINED_ACL, accessControlList.getEntry());
        }

        /**
         * Returns an option for specifying bucket's default ACL configuration for blobs.
         */
        public static BucketTargetOptions predefinedDefaultObjectAcl(PredefinedAccessControlList accessControlList) {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL, accessControlList.getEntry());
        }

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BucketTargetOptions withMetagenerationMatch() {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if metageneration matches.
         */
        public static BucketTargetOptions withMetagenerationNotMatch() {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option to define the billing user project. This option is required by buckets with
         * `requester_pays` flag enabled to assign operation costs.
         */
        public static BucketTargetOptions withUserProject(String billingProject) {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to define the projection in the API request. In some cases this option may
         * be needed to be set to `noAcl` to omit ACL data from the response. The default value is
         * `full`
         *
         * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/buckets/patch">Buckets:
         *     patch</a>
         */
        public static BucketTargetOptions projection(String responseView) {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.PROJECTION, responseView);
        }
    }

    /**
     * Class for specifying bucket source options.
     */
    class BucketSourceOptions extends AbstractOption {

        private static final long serialVersionUID = 5185657617120212117L;

        private BucketSourceOptions(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if bucket's metageneration does not match the provided value.
         */
        public static BucketSourceOptions withMetagenerationMatch(long generationId) {
            return new BucketSourceOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, generationId);
        }

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if bucket's metageneration matches the provided value.
         */
        public static BucketSourceOptions withMetagenerationNotMatch(long generationId) {
            return new BucketSourceOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, generationId);
        }

        /**
         * Returns an option for bucket's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BucketSourceOptions withUserProject(String billingProject) {
            return new BucketSourceOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        public static BucketSourceOptions withRequestedPolicyVersion(long policyRevision) {
            return new BucketSourceOptions(StorageRpcClient.StorageOption.REQUESTED_POLICY_VERSION, policyRevision);
        }
    }

    /**
     * Class for specifying listHmacKeys options
     */
    class ListHmacKeysOptions extends AbstractOption {

        private ListHmacKeysOptions(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option for the Service Account whose keys to list. If this option is not used,
         * keys for all accounts will be listed.
         */
        public static ListHmacKeysOptions withServiceAccount(ServiceAccountInfo accountIdentity) {
            return new ListHmacKeysOptions(StorageRpcClient.StorageOption.SERVICE_ACCOUNT_EMAIL, accountIdentity.getEmail());
        }

        /**
         * Returns an option for the maximum amount of HMAC keys returned per page.
         */
        public static ListHmacKeysOptions maxResults(long resultsPerPage) {
            return new ListHmacKeysOptions(StorageRpcClient.StorageOption.MAX_RESULTS, resultsPerPage);
        }

        /**
         * Returns an option to specify the page token from which to start listing HMAC keys.
         */
        public static ListHmacKeysOptions pageToken(String paginationToken) {
            return new ListHmacKeysOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, paginationToken);
        }

        /**
         * Returns an option to specify whether to show deleted keys in the result. This option is false
         * by default.
         */
        public static ListHmacKeysOptions showDeletedKeys(boolean includeDeleted) {
            return new ListHmacKeysOptions(StorageRpcClient.StorageOption.SHOW_DELETED_KEYS, includeDeleted);
        }

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static ListHmacKeysOptions userProject(String billingProject) {
            return new ListHmacKeysOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to specify the Project ID for this request. If not specified, defaults to
         * Application Default Credentials.
         */
        public static ListHmacKeysOptions projectId(String projectIdentifier) {
            return new ListHmacKeysOptions(StorageRpcClient.StorageOption.PROJECT_ID, projectIdentifier);
        }
    }

    /**
     * Class for specifying createHmacKey options
     */
    class HmacKeyCreationOption extends AbstractOption {

        private HmacKeyCreationOption(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static HmacKeyCreationOption userProject(String billingProject) {
            return new HmacKeyCreationOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to specify the Project ID for this request. If not specified, defaults to
         * Application Default Credentials.
         */
        public static HmacKeyCreationOption projectId(String projectIdentifier) {
            return new HmacKeyCreationOption(StorageRpcClient.StorageOption.PROJECT_ID, projectIdentifier);
        }
    }

    /**
     * Class for specifying getHmacKey options
     */
    class RetrieveHmacKeyOption extends AbstractOption {

        private RetrieveHmacKeyOption(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static RetrieveHmacKeyOption userProject(String billingProject) {
            return new RetrieveHmacKeyOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to specify the Project ID for this request. If not specified, defaults to
         * Application Default Credentials.
         */
        public static RetrieveHmacKeyOption projectId(String projectIdentifier) {
            return new RetrieveHmacKeyOption(StorageRpcClient.StorageOption.PROJECT_ID, projectIdentifier);
        }
    }

    /**
     * Class for specifying deleteHmacKey options
     */
    class DeleteHmacKeyRequestOption extends AbstractOption {

        private DeleteHmacKeyRequestOption(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static DeleteHmacKeyRequestOption userProject(String billingProject) {
            return new DeleteHmacKeyRequestOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }
    }

    /**
     * Class for specifying updateHmacKey options
     */
    class HmacKeyUpdateOption extends AbstractOption {

        private HmacKeyUpdateOption(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static HmacKeyUpdateOption userProject(String billingProject) {
            return new HmacKeyUpdateOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }
    }

    /**
     * Class for specifying bucket get options.
     */
    class GetBucketOption extends AbstractOption {

        private static final long serialVersionUID = 1901844869484087395L;

        private GetBucketOption(StorageRpcClient.StorageOption storageOption, long generationId) {
            super(storageOption, generationId);
        }

        private GetBucketOption(StorageRpcClient.StorageOption storageOption, String optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if bucket's metageneration does not match the provided value.
         */
        public static GetBucketOption withMetagenerationMatch(long generationId) {
            return new GetBucketOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, generationId);
        }

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if bucket's metageneration matches the provided value.
         */
        public static GetBucketOption withMetagenerationNotMatch(long generationId) {
            return new GetBucketOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, generationId);
        }

        /**
         * Returns an option for bucket's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static GetBucketOption withUserProject(String billingProject) {
            return new GetBucketOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to specify the bucket's fields to be returned by the RPC call. If this
         * option is not provided all bucket's fields are returned. {@code BucketGetOption.fields}) can
         * be used to specify only the fields of interest. Bucket name is always returned, even if not
         * specified.
         */
        public static GetBucketOption withFields(BucketAttribute... selectedAttributes) {
            return new GetBucketOption(StorageRpcClient.StorageOption.FIELDS, Helper.selector(BucketAttribute.MANDATORY_SELECTORS, selectedAttributes));
        }
    }

    /**
     * Class for specifying blob target options.
     */
    class BlobUploadOption extends AbstractOption {

        private static final long serialVersionUID = 214616862061934846L;

        private BlobUploadOption(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        private BlobUploadOption(StorageRpcClient.StorageOption storageOption) {
            this(storageOption, null);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobUploadOption withPredefinedAcl(PredefinedAccessControlList accessControlList) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.PREDEFINED_ACL, accessControlList.getEntry());
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         */
        public static BlobUploadOption ifDoesNotExist() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match.
         */
        public static BlobUploadOption withGenerationMatch() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches.
         */
        public static BlobUploadOption withGenerationNotMatch() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BlobUploadOption withMetagenerationMatch() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches.
         */
        public static BlobUploadOption withMetagenerationNotMatch() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's data disabledGzipContent. If this option is used, the request
         * will create a blob with disableGzipContent; at present, this is only for upload.
         */
        public static BlobUploadOption disableGzip() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_DISABLE_GZIP_CONTENT, true);
        }

        /**
         * Returns an option for detecting content type. If this option is used, the content type is
         * detected from the blob name if not explicitly set. This option is on the client side only, it
         * does not appear in a RPC call.
         */
        public static BlobUploadOption enableContentTypeDetection() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.DETECT_CONTENT_TYPE, true);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobUploadOption withEncryptionKey(Key cryptoKey) {
            String encodedKey = BaseEncoding.base64().encode(cryptoKey.getEncoded());
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobUploadOption withUserProject(String billingProject) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param cryptoKey the AES256 encoded in base64
         */
        public static BlobUploadOption withEncryptionKey(String cryptoKey) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, cryptoKey);
        }

        /**
         * Returns an option to set a customer-managed key for server-side encryption of the blob.
         */
        public static BlobUploadOption withKmsKeyName(String kmsKeyIdentifier) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.KMS_KEY_NAME, kmsKeyIdentifier);
        }

        static Tuple<BlobMetadata, BlobUploadOption[]> toBlobUploadOptions(BlobMetadata blobMetadata, BlobWriteOptions... writeOptions) {
            BlobMetadata.StorageObjectBuilder storageObjectBuilder = blobMetadata.toInfoBuilder().setCrc32c(null).setMd5(null);
            List<BlobUploadOption> uploadOptionsList = Lists.newArrayListWithCapacity(writeOptions.length);
            for (BlobWriteOptions writeOption : writeOptions) {
                switch(writeOption.writeOption) {
                    case IF_CRC32C_MATCH:
                        storageObjectBuilder.setCrc32c(blobMetadata.getCrc32c());
                        break;
                    case IF_MD5_MATCH:
                        storageObjectBuilder.setMd5(blobMetadata.getMd5());
                        break;
                    default:
                        uploadOptionsList.add(writeOption.toUploadOption());
                        break;
                }
            }
            return Tuple.of(storageObjectBuilder.buildObject(), uploadOptionsList.toArray(new BlobUploadOption[uploadOptionsList.size()]));
        }
    }

    /**
     * Class for specifying blob write options.
     */
    class BlobWriteOptions implements Serializable {

        private static final long serialVersionUID = -3880421670966224580L;

        private final StorageOption writeOption;

        private final Object optionPayload;

        enum StorageOption {

            PREDEFINED_ACL,
            IF_GENERATION_MATCH,
            IF_GENERATION_NOT_MATCH,
            IF_METAGENERATION_MATCH,
            IF_METAGENERATION_NOT_MATCH,
            IF_MD5_MATCH,
            IF_CRC32C_MATCH,
            CUSTOMER_SUPPLIED_KEY,
            KMS_KEY_NAME,
            USER_PROJECT,
            DETECT_CONTENT_TYPE,
            IF_DISABLE_GZIP_CONTENT;

            StorageRpcClient.StorageOption toStorageRpcOption() {
                return StorageRpcClient.StorageOption.valueOf(this.name());
            }
        }

        BlobUploadOption toUploadOption() {
            return new BlobUploadOption(this.writeOption.toStorageRpcOption(), this.optionPayload);
        }

        private BlobWriteOptions(StorageOption writeOption, Object optionPayload) {
            this.writeOption = writeOption;
            this.optionPayload = optionPayload;
        }

        private BlobWriteOptions(StorageOption writeOption) {
            this(writeOption, null);
        }

        @Override
        public int hashCode() {
            return Objects.hash(writeOption, optionPayload);
        }

        @Override
        public boolean equals(Object candidate) {
            if (null == candidate) {
                return false;
            }
            if (!(candidate instanceof BlobWriteOptions)) {
                return false;
            }
            final BlobWriteOptions otherOptions = (BlobWriteOptions) candidate;
            return otherOptions.writeOption == this.writeOption && Objects.equals(this.optionPayload, otherOptions.optionPayload);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobWriteOptions withPredefinedAcl(PredefinedAccessControlList accessControlList) {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.PREDEFINED_ACL, accessControlList.getEntry());
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         */
        public static BlobWriteOptions ifDoesNotExist() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match.
         */
        public static BlobWriteOptions withGenerationMatch() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.IF_GENERATION_MATCH);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches.
         */
        public static BlobWriteOptions ifGenerationNotMatch() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.IF_GENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BlobWriteOptions ifMetagenerationMatch() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.IF_METAGENERATION_MATCH);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches.
         */
        public static BlobWriteOptions ifMetagenerationNotMatch() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's data MD5 hash match. If this option is used the request will
         * fail if blobs' data MD5 hash does not match.
         */
        public static BlobWriteOptions ifMd5Match() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.IF_MD5_MATCH, true);
        }

        /**
         * Returns an option for blob's data CRC32C checksum match. If this option is used the request
         * will fail if blobs' data CRC32C checksum does not match.
         */
        public static BlobWriteOptions ifCrc32cMatch() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.IF_CRC32C_MATCH, true);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobWriteOptions customerSuppliedKey(Key cryptoKey) {
            String encodedKey = BaseEncoding.base64().encode(cryptoKey.getEncoded());
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param cryptoKey the AES256 encoded in base64
         */
        public static BlobWriteOptions customerSuppliedKey(String cryptoKey) {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.CUSTOMER_SUPPLIED_KEY, cryptoKey);
        }

        /**
         * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
         *
         * @param kmsKeyIdentifier the KMS key resource id
         */
        public static BlobWriteOptions withKmsKeyName(String kmsKeyIdentifier) {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.KMS_KEY_NAME, kmsKeyIdentifier);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobWriteOptions withUserProject(String billingProject) {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option that signals automatic gzip compression should not be performed en route to
         * the bucket.
         */
        public static BlobWriteOptions disableGzip() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.IF_DISABLE_GZIP_CONTENT, true);
        }

        /**
         * Returns an option for detecting content type. If this option is used, the content type is
         * detected from the blob name if not explicitly set. This option is on the client side only, it
         * does not appear in a RPC call.
         */
        public static BlobWriteOptions autoDetectContentType() {
            return new BlobWriteOptions(BlobWriteOptions.StorageOption.DETECT_CONTENT_TYPE, true);
        }
    }

    /**
     * Class for specifying blob source options.
     */
    class BlobSourceOptions extends AbstractOption {

        private static final long serialVersionUID = -3712768261070182991L;

        private BlobSourceOptions(StorageRpcClient.StorageOption storageOption, Object optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if blob's generation does not match. The generation value to compare with the actual
         * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
         * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
         * BlobId} is provided an exception is thrown.
         */
        public static BlobSourceOptions ifGenerationMatch() {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, null);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if blob's generation does not match the provided value.
         */
        public static BlobSourceOptions ifGenerationMatch(long versionId) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, versionId);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches. The generation value to compare with the actual
         * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
         * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
         * BlobId} is provided an exception is thrown.
         */
        public static BlobSourceOptions generationNotMatch() {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, null);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value.
         */
        public static BlobSourceOptions generationNotMatch(long versionId) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, versionId);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if blob's metageneration does not match the provided value.
         */
        public static BlobSourceOptions ifMetagenerationMatch(long generationId) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, generationId);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if blob's metageneration matches the provided value.
         */
        public static BlobSourceOptions ifMetagenerationNotMatch(long generationId) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, generationId);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobSourceOptions customerDecryptionKey(Key cryptoKey) {
            String encodedKey = BaseEncoding.base64().encode(cryptoKey.getEncoded());
            return new BlobSourceOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param cryptoKey the AES256 encoded in base64
         */
        public static BlobSourceOptions customerDecryptionKey(String cryptoKey) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, cryptoKey);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobSourceOptions withUserProject(String billingProject) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option for whether the request should return the raw input stream, instead of
         * automatically decompressing the content. By default, this is false for Blob.downloadTo(), but
         * true for ReadChannel.read().
         */
        public static BlobSourceOptions returnRawInputStream(boolean returnRawStream) {
            return new BlobSourceOptions(StorageRpcClient.StorageOption.RETURN_RAW_INPUT_STREAM, returnRawStream);
        }
    }

    /**
     * Class for specifying blob get options.
     */
    class BlobGetOptions extends AbstractOption {

        private static final long serialVersionUID = 803817709703661480L;

        private BlobGetOptions(StorageRpcClient.StorageOption storageOption, Long optionPayload) {
            super(storageOption, optionPayload);
        }

        private BlobGetOptions(StorageRpcClient.StorageOption storageOption, String optionPayload) {
            super(storageOption, optionPayload);
        }

        private BlobGetOptions(StorageRpcClient.StorageOption storageOption, boolean optionPayload) {
            super(storageOption, optionPayload);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if blob's generation does not match. The generation value to compare with the actual
         * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
         * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
         * BlobId} is provided an exception is thrown.
         */
        public static BlobGetOptions ifGenerationMatch() {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, (Long) null);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if blob's generation does not match the provided value.
         */
        public static BlobGetOptions ifGenerationMatch(long versionId) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, versionId);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches. The generation value to compare with the actual
         * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
         * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
         * BlobId} is provided an exception is thrown.
         */
        public static BlobGetOptions generationNotMatch() {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, (Long) null);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value.
         */
        public static BlobGetOptions generationNotMatch(long versionId) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, versionId);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if blob's metageneration does not match the provided value.
         */
        public static BlobGetOptions ifMetagenerationMatch(long generationId) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, generationId);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if blob's metageneration matches the provided value.
         */
        public static BlobGetOptions ifMetagenerationNotMatch(long generationId) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, generationId);
        }

        /**
         * Returns an option to specify the blob's fields to be returned by the RPC call. If this option
         * is not provided all blob's fields are returned. {@code BlobGetOption.fields}) can be used to
         * specify only the fields of interest. Blob name and bucket are always returned, even if not
         * specified.
         */
        public static BlobGetOptions selectFields(BlobMetadataField... selectedAttributes) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.FIELDS, Helper.selector(BlobMetadataField.MANDATORY_SELECTORS, selectedAttributes));
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobGetOptions withUserProject(String billingProject) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side decryption of the
         * blob.
         */
        public static BlobGetOptions customerDecryptionKey(Key cryptoKey) {
            String encodedKey = BaseEncoding.base64().encode(cryptoKey.getEncoded());
            return new BlobGetOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side decryption of the
         * blob.
         *
         * @param cryptoKey the AES256 encoded in base64
         */
        public static BlobGetOptions customerDecryptionKey(String cryptoKey) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, cryptoKey);
        }

        /**
         * Returns an option for whether the request should return the raw input stream, instead of
         * automatically decompressing the content. By default, this is false for Blob.downloadTo(), but
         * true for ReadChannel.read().
         */
        public static BlobGetOptions returnRawInputStream(boolean returnRawStream) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.RETURN_RAW_INPUT_STREAM, returnRawStream);
        }
    }

    /**
     * Class for specifying bucket list options.
     */
    class BucketListOptions extends AbstractOption {

        private static final long serialVersionUID = 8754017079673290353L;

        private BucketListOptions(StorageRpcClient.StorageOption writeOption, Object optionPayload) {
            super(writeOption, optionPayload);
        }

        /**
         * Returns an option to specify the maximum number of buckets returned per page.
         */
        public static BucketListOptions withPageSize(long resultsPerPage) {
            return new BucketListOptions(StorageRpcClient.StorageOption.MAX_RESULTS, resultsPerPage);
        }

        /**
         * Returns an option to specify the page token from which to start listing buckets.
         */
        public static BucketListOptions pageToken(String paginationToken) {
            return new BucketListOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, paginationToken);
        }

        /**
         * Returns an option to set a prefix to filter results to buckets whose names begin with this
         * prefix.
         */
        public static BucketListOptions withPrefix(String nameFilter) {
            return new BucketListOptions(StorageRpcClient.StorageOption.PREFIX, nameFilter);
        }

        /**
         * Returns an option for bucket's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BucketListOptions withUserProject(String billingProject) {
            return new BucketListOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to specify the bucket's fields to be returned by the RPC call. If this
         * option is not provided all bucket's fields are returned. {@code BucketListOption.fields}) can
         * be used to specify only the fields of interest. Bucket name is always returned, even if not
         * specified.
         */
        public static BucketListOptions selectFields(BucketAttribute... selectedAttributes) {
            return new BucketListOptions(StorageRpcClient.StorageOption.FIELDS, Helper.listSelector("items", BucketAttribute.MANDATORY_SELECTORS, selectedAttributes));
        }
    }

    /**
     * Class for specifying blob list options.
     */
    class BlobListOptions extends AbstractOption {

        private static final String[] TOP_FIELDS = { "prefixes" };

        private static final long serialVersionUID = 9083383524788661294L;

        private BlobListOptions(StorageRpcClient.StorageOption writeOption, Object optionPayload) {
            super(writeOption, optionPayload);
        }

        /**
         * Returns an option to specify the maximum number of blobs returned per page.
         */
        public static BlobListOptions withPageSize(long resultsPerPage) {
            return new BlobListOptions(StorageRpcClient.StorageOption.MAX_RESULTS, resultsPerPage);
        }

        /**
         * Returns an option to specify the page token from which to start listing blobs.
         */
        public static BlobListOptions pageToken(String paginationToken) {
            return new BlobListOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, paginationToken);
        }

        /**
         * Returns an option to set a prefix to filter results to blobs whose names begin with this
         * prefix.
         */
        public static BlobListOptions withPrefix(String nameFilter) {
            return new BlobListOptions(StorageRpcClient.StorageOption.PREFIX, nameFilter);
        }

        /**
         * If specified, results are returned in a directory-like mode. Blobs whose names, after a
         * possible {@link #withPrefix(String)}, do not contain the '/' delimiter are returned as is. Blobs
         * whose names, after a possible {@link #withPrefix(String)}, contain the '/' delimiter, will have
         * their name truncated after the delimiter and will be returned as {@link StorageObject} objects where
         * only {@link StorageObject#getBlobId()}, {@link StorageObject#getSize()} and {@link StorageObject#isDirectory()} are set.
         * For such directory blobs, ({@link BlobId#getGeneration()} returns {@code null}), {@link
         * StorageObject#getSize()} returns {@code 0} while {@link StorageObject#isDirectory()} returns {@code true}.
         * Duplicate directory blobs are omitted.
         */
        public static BlobListOptions useCurrentDirectory() {
            return new BlobListOptions(StorageRpcClient.StorageOption.DELIMITER, true);
        }

        /**
         * Returns an option to set a delimiter.
         *
         * @param separator generally '/' is the one used most often, but you can used other delimiters
         *     as well.
         */
        public static BlobListOptions withDelimiter(String separator) {
            return new BlobListOptions(StorageRpcClient.StorageOption.DELIMITER, separator);
        }

        /**
         * Returns an option to set a startOffset to filter results to objects whose names are
         * lexicographically equal to or after startOffset. If endOffset is also set, the objects listed
         * have names between startOffset (inclusive) and endOffset (exclusive).
         *
         * @param startPoint startOffset to filter the results
         */
        public static BlobListOptions withStartOffset(String startPoint) {
            return new BlobListOptions(StorageRpcClient.StorageOption.START_OFF_SET, startPoint);
        }

        /**
         * Returns an option to set a endOffset to filter results to objects whose names are
         * lexicographically before endOffset. If startOffset is also set, the objects listed have names
         * between startOffset (inclusive) and endOffset (exclusive).
         *
         * @param endPoint endOffset to filter the results
         */
        public static BlobListOptions withEndOffset(String endPoint) {
            return new BlobListOptions(StorageRpcClient.StorageOption.END_OFF_SET, endPoint);
        }

        /**
         * Returns an option to define the billing user project. This option is required by buckets with
         * `requester_pays` flag enabled to assign operation costs.
         *
         * @param billingProject projectId of the billing user project.
         */
        public static BlobListOptions withUserProject(String billingProject) {
            return new BlobListOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * If set to {@code true}, lists all versions of a blob. The default is {@code false}.
         *
         * @see <a href="https://cloud.google.com/storage/docs/object-versioning">Object Versioning</a>
         */
        public static BlobListOptions withVersions(boolean includeRevisions) {
            return new BlobListOptions(StorageRpcClient.StorageOption.VERSIONS, includeRevisions);
        }

        /**
         * Returns an option to specify the blob's fields to be returned by the RPC call. If this option
         * is not provided all blob's fields are returned. {@code BlobListOption.fields}) can be used to
         * specify only the fields of interest. Blob name and bucket are always returned, even if not
         * specified.
         */
        public static BlobListOptions withFields(BlobMetadataField... selectedAttributes) {
            return new BlobListOptions(StorageRpcClient.StorageOption.FIELDS, Helper.listSelector(TOP_FIELDS, "items", BlobMetadataField.MANDATORY_SELECTORS, selectedAttributes));
        }
    }

    /**
     * Class for specifying Post Policy V4 options. *
     */
    class PostPolicyV4Parameter implements Serializable {

        private static final long serialVersionUID = 8150867146534084543L;

        private final StorageOption writeOption;

        private final Object optionPayload;

        enum StorageOption {

            PATH_STYLE, VIRTUAL_HOSTED_STYLE, BUCKET_BOUND_HOST_NAME, SERVICE_ACCOUNT_CRED
        }

        private PostPolicyV4Parameter(StorageOption writeOption, Object optionPayload) {
            this.writeOption = writeOption;
            this.optionPayload = optionPayload;
        }

        StorageOption getOption() {
            return writeOption;
        }

        Object getValue() {
            return optionPayload;
        }

        /**
         * Provides a service account signer to sign the policy. If not provided an attempt is made to
         * get it from the environment.
         *
         * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
         *     Accounts</a>
         */
        public static PostPolicyV4Parameter withSigner(ServiceAccountSigner serviceAccountPrincipal) {
            return new PostPolicyV4Parameter(StorageOption.SERVICE_ACCOUNT_CRED, serviceAccountPrincipal);
        }

        /**
         * Use a virtual hosted-style hostname, which adds the bucket into the host portion of the URI
         * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name is
         * obtained from the resource passed in.
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
         */
        public static PostPolicyV4Parameter enableVirtualHostedStyle() {
            return new PostPolicyV4Parameter(StorageOption.VIRTUAL_HOSTED_STYLE, "");
        }

        /**
         * Generates a path-style URL, which places the bucket name in the path portion of the URL
         * instead of in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. Note that this
         * cannot be used alongside {@code withVirtualHostedStyle()}. Virtual hosted-style URLs, which
         * can be used via the {@code withVirtualHostedStyle()} method, should generally be preferred
         * instead of path-style URLs.
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
         */
        public static PostPolicyV4Parameter enablePathStyle() {
            return new PostPolicyV4Parameter(StorageOption.PATH_STYLE, "");
        }

        /**
         * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name of
         * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
         * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
         * cannot be used alongside {@code withVirtualHostedStyle()} or {@code withPathStyle()}. This
         * method signature uses HTTP for the URI scheme, and is equivalent to calling {@code
         * withBucketBoundHostname("...", UriScheme.HTTP).}
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
         *     Redirects</a>
         * @see <a
         *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
         *     GCLB Redirects</a>
         */
        public static PostPolicyV4Parameter withBucketBoundHostname(String hostForBucket) {
            return withBucketBoundHostname(hostForBucket, UriSchemeType.HTTP);
        }

        /**
         * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name of
         * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
         * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
         * cannot be used alongside {@code withVirtualHostedStyle()} or {@code withPathStyle()}. The
         * bucket name itself should not include the URI scheme (http or https), so it is specified via
         * a local enum.
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
         *     Redirects</a>
         * @see <a
         *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
         *     GCLB Redirects</a>
         */
        public static PostPolicyV4Parameter withBucketBoundHostname(String hostForBucket, UriSchemeType schemeType) {
            return new PostPolicyV4Parameter(StorageOption.BUCKET_BOUND_HOST_NAME, schemeType.getScheme() + "://" + hostForBucket);
        }
    }

    /**
     * Class for specifying signed URL options.
     */
    class UrlSigningOption implements Serializable {

        private static final long serialVersionUID = 7850569877451099267L;

        private final RequestOption writeOption;

        private final Object optionPayload;

        enum RequestOption {

            HTTP_METHOD,
            CONTENT_TYPE,
            MD5,
            EXT_HEADERS,
            SERVICE_ACCOUNT_CRED,
            SIGNATURE_VERSION,
            HOST_NAME,
            PATH_STYLE,
            VIRTUAL_HOSTED_STYLE,
            BUCKET_BOUND_HOST_NAME,
            QUERY_PARAMS
        }

        enum SigningVersion {

            V2, V4
        }

        private UrlSigningOption(RequestOption writeOption, Object optionPayload) {
            this.writeOption = writeOption;
            this.optionPayload = optionPayload;
        }

        RequestOption getOption() {
            return writeOption;
        }

        Object getValue() {
            return optionPayload;
        }

        /**
         * The HTTP method to be used with the signed URL. If this method is not called, defaults to
         * GET.
         */
        public static UrlSigningOption withHttpMethod(HttpRequestMethod requestMethod) {
            return new UrlSigningOption(RequestOption.HTTP_METHOD, requestMethod);
        }

        /**
         * Use it if signature should include the blob's content-type. When used, users of the signed
         * URL should include the blob's content-type with their request. If using this URL from a
         * browser, you must include a content type that matches what the browser will send.
         */
        public static UrlSigningOption includeContentType() {
            return new UrlSigningOption(RequestOption.CONTENT_TYPE, true);
        }

        /**
         * Use it if signature should include the blob's md5. When used, users of the signed URL should
         * include the blob's md5 with their request.
         */
        public static UrlSigningOption includeMd5() {
            return new UrlSigningOption(RequestOption.MD5, true);
        }

        /**
         * Use it if signature should include the blob's canonicalized extended headers. When used,
         * users of the signed URL should include the canonicalized extended headers with their request.
         *
         * @see <a href="https://cloud.google.com/storage/docs/xml-api/reference-headers">Request
         *     Headers</a>
         */
        public static UrlSigningOption includeExtHeaders(Map<String, String> additionalHeaders) {
            return new UrlSigningOption(RequestOption.EXT_HEADERS, additionalHeaders);
        }

        /**
         * Use if signature version should be V2. This is the default if neither this or {@code
         * withV4Signature()} is called.
         */
        public static UrlSigningOption useV2Signature() {
            return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SigningVersion.V2);
        }

        /**
         * Use if signature version should be V4. Note that V4 Signed URLs can't have an expiration
         * longer than 7 days. V2 will be the default if neither this or {@code withV2Signature()} is
         * called.
         */
        public static UrlSigningOption useV4Signature() {
            return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SigningVersion.V4);
        }

        /**
         * Provides a service account signer to sign the URL. If not provided an attempt is made to get
         * it from the environment.
         *
         * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
         *     Accounts</a>
         */
        public static UrlSigningOption withSigner(ServiceAccountSigner serviceAccountPrincipal) {
            return new UrlSigningOption(RequestOption.SERVICE_ACCOUNT_CRED, serviceAccountPrincipal);
        }

        /**
         * Use a different host name than the default host name 'storage.googleapis.com'. This option is
         * particularly useful for developers to point requests to an alternate endpoint (e.g. a staging
         * environment or sending requests through VPC). If using this with the {@code
         * withVirtualHostedStyle()} method, you should omit the bucket name from the hostname, as it
         * automatically gets prepended to the hostname for virtual hosted-style URLs.
         */
        public static UrlSigningOption setHostName(String hostAddress) {
            return new UrlSigningOption(RequestOption.HOST_NAME, hostAddress);
        }

        /**
         * Use a virtual hosted-style hostname, which adds the bucket into the host portion of the URI
         * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name is
         * obtained from the resource passed in. For V4 signing, this also sets the "host" header in the
         * canonicalized extension headers to the virtual hosted-style host, unless that header is
         * supplied via the {@code withExtHeaders()} method.
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
         */
        public static UrlSigningOption enableVirtualHostedStyle() {
            return new UrlSigningOption(RequestOption.VIRTUAL_HOSTED_STYLE, "");
        }

        /**
         * Generates a path-style URL, which places the bucket name in the path portion of the URL
         * instead of in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. This cannot be
         * used alongside {@code withVirtualHostedStyle()}. Virtual hosted-style URLs, which can be used
         * via the {@code withVirtualHostedStyle()} method, should generally be preferred instead of
         * path-style URLs.
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
         */
        public static UrlSigningOption enablePathStyle() {
            return new UrlSigningOption(RequestOption.PATH_STYLE, "");
        }

        /**
         * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name of
         * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
         * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. This cannot be
         * used alongside {@code withVirtualHostedStyle()} or {@code withPathStyle()}. This method
         * signature uses HTTP for the URI scheme, and is equivalent to calling {@code
         * withBucketBoundHostname("...", UriScheme.HTTP).}
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
         *     Redirects</a>
         * @see <a
         *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
         *     GCLB Redirects</a>
         */
        public static UrlSigningOption withBucketBoundHostname(String hostForBucket) {
            return withBucketBoundHostname(hostForBucket, UriSchemeType.HTTP);
        }

        /**
         * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name of
         * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
         * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
         * cannot be used alongside {@code withVirtualHostedStyle()} or {@code withPathStyle()}. The
         * bucket name itself should not include the URI scheme (http or https), so it is specified via
         * a local enum.
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
         *     Redirects</a>
         * @see <a
         *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
         *     GCLB Redirects</a>
         */
        public static UrlSigningOption withBucketBoundHostname(String hostForBucket, UriSchemeType schemeType) {
            return new UrlSigningOption(RequestOption.BUCKET_BOUND_HOST_NAME, schemeType.getScheme() + "://" + hostForBucket);
        }

        /**
         * Use if the URL should contain additional query parameters.
         *
         * <p>Warning: For V2 Signed URLs, it is possible for query parameters to be altered after the
         * URL has been signed, as the parameters are not used to compute the signature. The V4 signing
         * method should be preferred when supplying additional query parameters, as the parameters
         * cannot be added, removed, or otherwise altered after a V4 signature is generated.
         *
         * @see <a href="https://cloud.google.com/storage/docs/authentication/canonical-requests">
         *     Canonical Requests</a>
         * @see <a href="https://cloud.google.com/storage/docs/access-control/signed-urls-v2">V2 Signing
         *     Process</a>
         */
        public static UrlSigningOption setQueryParams(Map<String, String> queryParameters) {
            return new UrlSigningOption(RequestOption.QUERY_PARAMS, queryParameters);
        }
    }

    /**
     * A class to contain all information needed for a Google Cloud Storage Compose operation.
     *
     * @see <a href="https://cloud.google.com/storage/docs/composite-objects#_Compose">Compose
     *     Operation</a>
     */
    class ComposeBlobsRequest implements Serializable {

        private static final long serialVersionUID = -7385681353748590911L;

        private final List<SourceBlobInfo> inputBlobs;

        private final BlobMetadata destinationBlob;

        private final List<BlobUploadOption> uploadOptionsList;

        /**
         * Class for Compose source blobs.
         */
        public static class SourceBlobInfo implements Serializable {

            private static final long serialVersionUID = 4094962795951990439L;

            final String blobId;

            final Long versionId;

            SourceBlobInfo(String blobId) {
                this(blobId, null);
            }

            SourceBlobInfo(String blobId, Long versionId) {
                this.blobId = blobId;
                this.versionId = versionId;
            }

            public String getName() {
                return blobId;
            }

            public Long getGeneration() {
                return versionId;
            }
        }

        public static class SourceTargetBuilder {

            private final List<SourceBlobInfo> inputBlobs = new LinkedList<>();

            private final Set<BlobUploadOption> uploadOptionsList = new LinkedHashSet<>();

            private BlobMetadata destinationBlob;

            /**
             * Add source blobs for compose operation.
             */
            public SourceTargetBuilder addSources(Iterable<String> items) {
                for (String sourceName : items) {
                    inputBlobs.add(new SourceBlobInfo(sourceName));
                }
                return this;
            }

            /**
             * Add source blobs for compose operation.
             */
            public SourceTargetBuilder addSources(String... items) {
                return addSources(Arrays.asList(items));
            }

            /**
             * Add a source with a specific generation to match.
             */
            public SourceTargetBuilder addSources(String sourceName, long versionId) {
                inputBlobs.add(new SourceBlobInfo(sourceName, versionId));
                return this;
            }

            /**
             * Sets compose operation's target blob.
             */
            public SourceTargetBuilder setTarget(BlobMetadata destinationBlob) {
                this.destinationBlob = destinationBlob;
                return this;
            }

            /**
             * Sets compose operation's target blob options.
             */
            public SourceTargetBuilder setTargetOptions(BlobUploadOption... writeOptions) {
                Collections.addAll(uploadOptionsList, writeOptions);
                return this;
            }

            /**
             * Sets compose operation's target blob options.
             */
            public SourceTargetBuilder setTargetOptions(Iterable<BlobUploadOption> writeOptions) {
                Iterables.addAll(uploadOptionsList, writeOptions);
                return this;
            }

            /**
             * Creates a {@code ComposeRequest} object.
             */
            public ComposeBlobsRequest buildRequest() {
                checkArgument(!inputBlobs.isEmpty());
                checkNotNull(destinationBlob);
                return new ComposeBlobsRequest(this);
            }
        }

        private ComposeBlobsRequest(SourceTargetBuilder sourceTarget) {
            inputBlobs = ImmutableList.copyOf(sourceTarget.inputBlobs);
            destinationBlob = sourceTarget.destinationBlob;
            uploadOptionsList = ImmutableList.copyOf(sourceTarget.uploadOptionsList);
        }

        /**
         * Returns compose operation's source blobs.
         */
        public List<SourceBlobInfo> getSourceBlobs() {
            return inputBlobs;
        }

        /**
         * Returns compose operation's target blob.
         */
        public BlobMetadata getTarget() {
            return destinationBlob;
        }

        /**
         * Returns compose operation's target blob's options.
         */
        public List<BlobUploadOption> getTargetOptions() {
            return uploadOptionsList;
        }

        /**
         * Creates a {@code ComposeRequest} object.
         *
         * @param inputs source blobs names
         * @param destinationBlob target blob
         */
        public static ComposeBlobsRequest of(Iterable<String> inputs, BlobMetadata destinationBlob) {
            return newSourceTargetBuilder().setTarget(destinationBlob).addSources(inputs).buildRequest();
        }

        /**
         * Creates a {@code ComposeRequest} object.
         *
         * @param storageName name of the bucket where the compose operation takes place
         * @param inputs source blobs names
         * @param destinationBlob target blob name
         */
        public static ComposeBlobsRequest of(String storageName, Iterable<String> inputs, String destinationBlob) {
            return of(inputs, BlobMetadata.newBuilder(BlobId.from(storageName, destinationBlob)).buildObject());
        }

        /**
         * Returns a {@code ComposeRequest} builder.
         */
        public static SourceTargetBuilder newSourceTargetBuilder() {
            return new SourceTargetBuilder();
        }
    }

    /**
     * A class to contain all information needed for a Google Cloud Storage Copy operation.
     */
    class ChunkedCopyRequest implements Serializable {

        private static final long serialVersionUID = -4498650529476219937L;

        private final BlobId originBlobId;

        private final List<BlobSourceOptions> readOptions;

        private final boolean forceMetadata;

        private final BlobMetadata destinationBlob;

        private final List<BlobUploadOption> uploadOptionsList;

        private final Long mbPerChunk;

        public static class CopyOperationBuilder {

            private final Set<BlobSourceOptions> readOptions = new LinkedHashSet<>();

            private final Set<BlobUploadOption> uploadOptionsList = new LinkedHashSet<>();

            private BlobId originBlobId;

            private boolean forceMetadata;

            private BlobMetadata destinationBlob;

            private Long mbPerChunk;

            /**
             * Sets the blob to copy given bucket and blob name.
             *
             * @return the builder
             */
            public CopyOperationBuilder setSource(String storageName, String sourceName) {
                this.originBlobId = BlobId.from(storageName, sourceName);
                return this;
            }

            /**
             * Sets the blob to copy given a {@link BlobId}.
             *
             * @return the builder
             */
            public CopyOperationBuilder setSource(BlobId originBlobId) {
                this.originBlobId = originBlobId;
                return this;
            }

            /**
             * Sets blob's source options.
             *
             * @return the builder
             */
            public CopyOperationBuilder setSourceOptions(BlobSourceOptions... writeOptions) {
                Collections.addAll(readOptions, writeOptions);
                return this;
            }

            /**
             * Sets blob's source options.
             *
             * @return the builder
             */
            public CopyOperationBuilder setSourceOptions(Iterable<BlobSourceOptions> writeOptions) {
                Iterables.addAll(readOptions, writeOptions);
                return this;
            }

            /**
             * Sets the copy target. Target blob information is copied from source.
             *
             * @return the builder
             */
            public CopyOperationBuilder setTarget(BlobId destinationId) {
                this.forceMetadata = false;
                this.destinationBlob = BlobMetadata.newBuilder(destinationId).buildObject();
                return this;
            }

            /**
             * Sets the copy target. Target blob information is copied from source, except for those
             * options specified in {@code options}.
             *
             * @return the builder
             */
            public CopyOperationBuilder setTarget(BlobId destinationId, BlobUploadOption... writeOptions) {
                this.forceMetadata = false;
                this.destinationBlob = BlobMetadata.newBuilder(destinationId).buildObject();
                Collections.addAll(uploadOptionsList, writeOptions);
                return this;
            }

            /**
             * Sets the copy target and target options. {@code target} parameter is used to override
             * source blob information (e.g. {@code contentType}, {@code contentLanguage}). Target blob
             * information is set exactly to {@code target}, no information is inherited from the source
             * blob.
             *
             * @return the builder
             */
            public CopyOperationBuilder setTarget(BlobMetadata destinationBlob, BlobUploadOption... writeOptions) {
                this.forceMetadata = true;
                this.destinationBlob = checkNotNull(destinationBlob);
                Collections.addAll(uploadOptionsList, writeOptions);
                return this;
            }

            /**
             * Sets the copy target and target options. {@code target} parameter is used to override
             * source blob information (e.g. {@code contentType}, {@code contentLanguage}). Target blob
             * information is set exactly to {@code target}, no information is inherited from the source
             * blob.
             *
             * @return the builder
             */
            public CopyOperationBuilder setTarget(BlobMetadata destinationBlob, Iterable<BlobUploadOption> writeOptions) {
                this.forceMetadata = true;
                this.destinationBlob = checkNotNull(destinationBlob);
                Iterables.addAll(uploadOptionsList, writeOptions);
                return this;
            }

            /**
             * Sets the copy target and target options. Target blob information is copied from source,
             * except for those options specified in {@code options}.
             *
             * @return the builder
             */
            public CopyOperationBuilder setTarget(BlobId destinationId, Iterable<BlobUploadOption> writeOptions) {
                this.forceMetadata = false;
                this.destinationBlob = BlobMetadata.newBuilder(destinationId).buildObject();
                Iterables.addAll(uploadOptionsList, writeOptions);
                return this;
            }

            /**
             * Sets the maximum number of megabytes to copy for each RPC call. This parameter is ignored
             * if source and target blob share the same location and storage class as copy is made with
             * one single RPC.
             *
             * @return the builder
             */
            public CopyOperationBuilder setMegabytesCopiedPerChunk(Long mbPerChunk) {
                this.mbPerChunk = mbPerChunk;
                return this;
            }

            /**
             * Creates a {@code CopyRequest} object.
             */
            public ChunkedCopyRequest buildCopyRequest() {
                return new ChunkedCopyRequest(this);
            }
        }

        private ChunkedCopyRequest(CopyOperationBuilder sourceTarget) {
            originBlobId = checkNotNull(sourceTarget.originBlobId);
            readOptions = ImmutableList.copyOf(sourceTarget.readOptions);
            forceMetadata = sourceTarget.forceMetadata;
            destinationBlob = checkNotNull(sourceTarget.destinationBlob);
            uploadOptionsList = ImmutableList.copyOf(sourceTarget.uploadOptionsList);
            mbPerChunk = sourceTarget.mbPerChunk;
        }

        /**
         * Returns the blob to copy, as a {@link BlobId}.
         */
        public BlobId getSource() {
            return originBlobId;
        }

        /**
         * Returns blob's source options.
         */
        public List<BlobSourceOptions> getSourceOptions() {
            return readOptions;
        }

        /**
         * Returns the {@link BlobMetadata} for the target blob.
         */
        public BlobMetadata getTarget() {
            return destinationBlob;
        }

        /**
         * Returns whether to override the target blob information with {@link #getTarget()}. If {@code
         * true}, the value of {@link #getTarget()} is used to replace source blob information (e.g.
         * {@code contentType}, {@code contentLanguage}). Target blob information is set exactly to this
         * value, no information is inherited from the source blob. If {@code false}, target blob
         * information is inherited from the source blob.
         */
        public boolean getOverrideInfo() {
            return forceMetadata;
        }

        /**
         * Returns blob's target options.
         */
        public List<BlobUploadOption> getTargetOptions() {
            return uploadOptionsList;
        }

        /**
         * Returns the maximum number of megabytes to copy for each RPC call. This parameter is ignored
         * if source and target blob share the same location and storage class as copy is made with one
         * single RPC.
         */
        public Long getMegabytesCopiedPerChunk() {
            return mbPerChunk;
        }

        /**
         * Creates a copy request. {@code target} parameter is used to override source blob information
         * (e.g. {@code contentType}, {@code contentLanguage}).
         *
         * @param srcBucket name of the bucket containing the source blob
         * @param srcBlobName name of the source blob
         * @param destinationBlob a {@code BlobInfo} object for the target blob
         * @return a copy request
         */
        public static ChunkedCopyRequest create(String srcBucket, String srcBlobName, BlobMetadata destinationBlob) {
            return newCopyOperationBuilder().setSource(srcBucket, srcBlobName).setTarget(destinationBlob).buildCopyRequest();
        }

        /**
         * Creates a copy request. {@code target} parameter is used to replace source blob information
         * (e.g. {@code contentType}, {@code contentLanguage}). Target blob information is set exactly
         * to {@code target}, no information is inherited from the source blob.
         *
         * @param srcBlobId a {@code BlobId} object for the source blob
         * @param destinationBlob a {@code BlobInfo} object for the target blob
         * @return a copy request
         */
        public static ChunkedCopyRequest create(BlobId srcBlobId, BlobMetadata destinationBlob) {
            return newCopyOperationBuilder().setSource(srcBlobId).setTarget(destinationBlob).buildCopyRequest();
        }

        /**
         * Creates a copy request. Target blob information is copied from source.
         *
         * @param srcBucket name of the bucket containing both the source and the target blob
         * @param srcBlobName name of the source blob
         * @param destBlob name of the target blob
         * @return a copy request
         */
        public static ChunkedCopyRequest create(String srcBucket, String srcBlobName, String destBlob) {
            return ChunkedCopyRequest.newCopyOperationBuilder().setSource(srcBucket, srcBlobName).setTarget(BlobId.from(srcBucket, destBlob)).buildCopyRequest();
        }

        /**
         * Creates a copy request. Target blob information is copied from source.
         *
         * @param srcBucket name of the bucket containing the source blob
         * @param srcBlobName name of the source blob
         * @param destinationBlob a {@code BlobId} object for the target blob
         * @return a copy request
         */
        public static ChunkedCopyRequest create(String srcBucket, String srcBlobName, BlobId destinationBlob) {
            return newCopyOperationBuilder().setSource(srcBucket, srcBlobName).setTarget(destinationBlob).buildCopyRequest();
        }

        /**
         * Creates a copy request. Target blob information is copied from source.
         *
         * @param srcBlobId a {@code BlobId} object for the source blob
         * @param destBlob name of the target blob, in the same bucket of the source blob
         * @return a copy request
         */
        public static ChunkedCopyRequest create(BlobId srcBlobId, String destBlob) {
            return ChunkedCopyRequest.newCopyOperationBuilder().setSource(srcBlobId).setTarget(BlobId.from(srcBlobId.getBucket(), destBlob)).buildCopyRequest();
        }

        /**
         * Creates a copy request. Target blob information is copied from source.
         *
         * @param srcBlobId a {@code BlobId} object for the source blob
         * @param destBlobId a {@code BlobId} object for the target blob
         * @return a copy request
         */
        public static ChunkedCopyRequest create(BlobId srcBlobId, BlobId destBlobId) {
            return ChunkedCopyRequest.newCopyOperationBuilder().setSource(srcBlobId).setTarget(destBlobId).buildCopyRequest();
        }

        /**
         * Creates a builder for {@code CopyRequest} objects.
         */
        public static CopyOperationBuilder newCopyOperationBuilder() {
            return new CopyOperationBuilder();
        }
    }

    /**
     * Creates a new bucket.
     *
     * <p>Accepts an optional userProject {@link BucketTargetOptions} option which defines the project
     * id to assign operational costs.
     *
     * <p>Example of creating a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Bucket bucket = storage.create(BucketInfo.of(bucketName));
     * }</pre>
     *
     * <p>Example of creating a bucket with storage class and location.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Bucket bucket = storage.create(BucketInfo.newBuilder(bucketName)
     *     // See here for possible values: http://g.co/cloud/storage/docs/storage-classes
     *     .setStorageClass(StorageClass.COLDLINE)
     *     // Possible values: http://g.co/cloud/storage/docs/bucket-locations#location-mr
     *     .setLocation("asia")
     *     .build());
     * }</pre>
     *
     * @return a complete bucket
     * @throws StorageServiceException upon failure
     */
    StorageBucket create(BucketMetadata bucketInfo, BucketTargetOptions... options);

    /**
     * Creates a new blob with no content.
     *
     * <p>Example of creating a blob with no content.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * Blob blob = storage.create(blobInfo);
     * }</pre>
     *
     * @return a {@code Blob} with complete information
     * @throws StorageServiceException upon failure
     */
    StorageObject create(BlobMetadata blobInfo, BlobUploadOption... options);

    /**
     * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
     * #writer} is recommended as it uses resumable upload. MD5 and CRC32C hashes of {@code content}
     * are computed and used for validating transferred data. Accepts an optional userProject {@link
     * BlobGetOptions} option which defines the project id to assign operational costs. The content
     * type is detected from the blob name if not explicitly set.
     *
     * <p>Example of creating a blob from a byte array:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8));
     * }</pre>
     *
     * @return a {@code Blob} with complete information
     * @throws StorageServiceException upon failure
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
     */
    StorageObject create(BlobMetadata blobInfo, byte[] content, BlobUploadOption... options);

    /**
     * Creates a new blob with the sub array of the given byte array. Direct upload is used to upload
     * {@code content}. For large content, {@link #writer} is recommended as it uses resumable upload.
     * MD5 and CRC32C hashes of {@code content} are computed and used for validating transferred data.
     * Accepts a userProject {@link BlobGetOptions} option, which defines the project id to assign
     * operational costs.
     *
     * <p>Example of creating a blob from a byte array:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8), 7, 5);
     * }</pre>
     *
     * @return a {@code Blob} with complete information
     * @throws StorageServiceException upon failure
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
     */
    StorageObject create(BlobMetadata blobInfo, byte[] content, int offset, int length, BlobUploadOption... options);

    /**
     * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
     * #writer} is recommended as it uses resumable upload. By default any MD5 and CRC32C values in
     * the given {@code blobInfo} are ignored unless requested via the {@code
     * BlobWriteOption.md5Match} and {@code BlobWriteOption.crc32cMatch} options. The given input
     * stream is closed upon success.
     *
     * <p>This method is marked as {@link Deprecated} because it cannot safely retry, given that it
     * accepts an {@link InputStream} which can only be consumed once.
     *
     * <p>Example of creating a blob from an input stream.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * Blob blob = storage.create(blobInfo, content);
     * }</pre>
     *
     * <p>Example of uploading an encrypted blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String encryptionKey = "my_encryption_key";
     * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
     *
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
     *     .setContentType("text/plain")
     *     .build();
     * Blob blob = storage.create(blobInfo, content, BlobWriteOption.encryptionKey(encryptionKey));
     * }</pre>
     *
     * @return a {@code Blob} with complete information
     * @throws StorageServiceException upon failure
     */
    @Deprecated
    StorageObject create(BlobMetadata blobInfo, InputStream content, BlobWriteOptions... options);

    /**
     * Uploads {@code path} to the blob using {@link #writer}. By default any MD5 and CRC32C values in
     * the given {@code blobInfo} are ignored unless requested via the {@link
     * BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#ifCrc32cMatch()} options. Folder upload is
     * not supported.
     *
     * <p>Example of uploading a file:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String fileName = "readme.txt";
     * BlobId blobId = BlobId.of(bucketName, fileName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * storage.createFrom(blobInfo, Paths.get(fileName));
     * }</pre>
     *
     * @param blobMetadata blob to create
     * @param fileLocation file to upload
     * @param writeOptions blob write options
     * @return a {@code Blob} with complete information
     * @throws IOException on I/O error
     * @throws StorageServiceException on server side error
     * @see #createFrom(BlobMetadata, Path, int, BlobWriteOptions...)
     */
    StorageObject createFrom(BlobMetadata blobMetadata, Path fileLocation, BlobWriteOptions... writeOptions) throws IOException;

    /**
     * Uploads {@code path} to the blob using {@link #writer} and {@code bufferSize}. By default any
     * MD5 and CRC32C values in the given {@code blobInfo} are ignored unless requested via the {@link
     * BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#ifCrc32cMatch()} options. Folder upload is
     * not supported.
     *
     * <p>{@link #createFrom(BlobMetadata, Path, BlobWriteOptions...)} invokes this method with a buffer
     * size of 15 MiB. Users can pass alternative values. Larger buffer sizes might improve the upload
     * performance but require more memory. This can cause an OutOfMemoryError or add significant
     * garbage collection overhead. Smaller buffer sizes reduce memory consumption, that is noticeable
     * when uploading many objects in parallel. Buffer sizes less than 256 KiB are treated as 256 KiB.
     *
     * <p>Example of uploading a humongous file:
     *
     * <pre>{@code
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("video/webm").build();
     *
     * int largeBufferSize = 150 * 1024 * 1024;
     * Path file = Paths.get("humongous.file");
     * storage.createFrom(blobInfo, file, largeBufferSize);
     * }</pre>
     *
     * @param blobMetadata blob to create
     * @param fileLocation file to upload
     * @param chunkSize size of the buffer I/O operations
     * @param writeOptions blob write options
     * @return a {@code Blob} with complete information
     * @throws IOException on I/O error
     * @throws StorageServiceException on server side error
     */
    StorageObject createFrom(BlobMetadata blobMetadata, Path fileLocation, int chunkSize, BlobWriteOptions... writeOptions) throws IOException;

    /**
     * Reads bytes from an input stream and uploads those bytes to the blob using {@link #writer}. By
     * default any MD5 and CRC32C values in the given {@code blobInfo} are ignored unless requested
     * via the {@link BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#ifCrc32cMatch()} options.
     *
     * <p>Example of uploading data with CRC32C checksum:
     *
     * <pre>{@code
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * byte[] content = "Hello, world".getBytes(StandardCharsets.UTF_8);
     * Hasher hasher = Hashing.crc32c().newHasher().putBytes(content);
     * String crc32c = BaseEncoding.base64().encode(Ints.toByteArray(hasher.hash().asInt()));
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setCrc32c(crc32c).build();
     * storage.createFrom(blobInfo, new ByteArrayInputStream(content), Storage.BlobWriteOption.crc32cMatch());
     * }</pre>
     *
     * @param blobMetadata blob to create
     * @param dataBytes input stream to read from
     * @param writeOptions blob write options
     * @return a {@code Blob} with complete information
     * @throws IOException on I/O error
     * @throws StorageServiceException on server side error
     * @see #createFrom(BlobMetadata, InputStream, int, BlobWriteOptions...)
     */
    StorageObject createFrom(BlobMetadata blobMetadata, InputStream dataBytes, BlobWriteOptions... writeOptions) throws IOException;

    /**
     * Reads bytes from an input stream and uploads those bytes to the blob using {@link #writer} and
     * {@code bufferSize}. By default any MD5 and CRC32C values in the given {@code blobInfo} are
     * ignored unless requested via the {@link BlobWriteOptions#ifMd5Match()} and {@link
     * BlobWriteOptions#ifCrc32cMatch()} options.
     *
     * <p>{@link #createFrom(BlobMetadata, InputStream, BlobWriteOptions...)} )} invokes this method with a
     * buffer size of 15 MiB. Users can pass alternative values. Larger buffer sizes might improve the
     * upload performance but require more memory. This can cause an OutOfMemoryError or add
     * significant garbage collection overhead. Smaller buffer sizes reduce memory consumption, that
     * is noticeable when uploading many objects in parallel. Buffer sizes less than 256 KiB are
     * treated as 256 KiB.
     *
     * @param blobMetadata blob to create
     * @param dataBytes input stream to read from
     * @param chunkSize size of the buffer I/O operations
     * @param writeOptions blob write options
     * @return a {@code Blob} with complete information
     * @throws IOException on I/O error
     * @throws StorageServiceException on server side error
     */
    StorageObject createFrom(BlobMetadata blobMetadata, InputStream dataBytes, int chunkSize, BlobWriteOptions... writeOptions) throws IOException;

    /**
     * Returns the requested bucket or {@code null} if not found.
     *
     * <p>Accepts an optional userProject {@link GetBucketOption} option which defines the project id
     * to assign operational costs.
     *
     * <p>Example of getting information on a bucket, only if its metageneration matches a value,
     * otherwise a {@link StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * long bucketMetageneration = 42;
     * Bucket bucket = storage.get(bucketName,
     *     BucketGetOption.metagenerationMatch(bucketMetageneration));
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    StorageBucket get(String bucket, GetBucketOption... options);

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
     * String bucketName = "my-unique-bucket";
     * Bucket bucket = storage.get(bucketName, BucketGetOption.fields(BucketField.METAGENERATION));
     * storage.lockRetentionPolicy(bucket, BucketTargetOption.metagenerationMatch());
     * }</pre>
     *
     * @return a {@code Bucket} object of the locked bucket
     * @throws StorageServiceException upon failure
     */
    StorageBucket lockRetentionPolicy(BucketMetadata bucket, BucketTargetOptions... options);

    /**
     * Returns the requested blob or {@code null} if not found.
     *
     * <p>Accepts an optional userProject {@link BlobGetOptions} option which defines the project id to
     * assign operational costs.
     *
     * <p>Example of getting information on a blob, only if its metageneration matches a value,
     * otherwise a {@link StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobMetageneration = 42;
     * Blob blob = storage.get(bucketName, blobName,
     *     BlobGetOption.metagenerationMatch(blobMetageneration));
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    StorageObject get(String bucket, String blob, BlobGetOptions... options);

    /**
     * Returns the requested blob or {@code null} if not found.
     *
     * <p>Accepts an optional userProject {@link BlobGetOptions} option which defines the project id to
     * assign operational costs.
     *
     * <p>Example of getting information on a blob, only if its metageneration matches a value,
     * otherwise a {@link StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobMetageneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * Blob blob = storage.get(blobId, BlobGetOption.metagenerationMatch(blobMetageneration));
     * }</pre>
     *
     * <p>Example of getting information on a blob encrypted using Customer Supplied Encryption Keys,
     * only if supplied Decrpytion Key decrypts the blob successfully, otherwise a {@link
     * StorageServiceException} is thrown. For more information review
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/encryption/customer-supplied-keys#encrypted-elements">Encrypted
     *     Elements</a>
     *     <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String blobEncryptionKey = "";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * Blob blob = storage.get(blobId, BlobGetOption.decryptionKey(blobEncryptionKey));
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    StorageObject get(BlobId blob, BlobGetOptions... options);

    /**
     * Returns the requested blob or {@code null} if not found.
     *
     * <p>Example of getting information on a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * Blob blob = storage.get(blobId);
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    StorageObject get(BlobId blob);

    /**
     * Lists the project's buckets.
     *
     * <p>Example of listing buckets, specifying the page size and a name prefix.
     *
     * <pre>{@code
     * String prefix = "bucket_";
     * Page<Bucket> buckets = storage.list(BucketListOption.pageSize(100),
     *     BucketListOption.prefix(prefix));
     * Iterator<Bucket> bucketIterator = buckets.iterateAll().iterator();
     * while (bucketIterator.hasNext()) {
     *   Bucket bucket = bucketIterator.next();
     *   // do something with the bucket
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    Page<StorageBucket> list(BucketListOptions... options);

    /**
     * Lists the bucket's blobs. If the {@link BlobListOptions#useCurrentDirectory()} option is provided,
     * results are returned in a directory-like mode.
     *
     * <p>Example of listing blobs in a provided directory.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String directory = "my_directory/";
     * Page<Blob> blobs = storage.list(bucketName, BlobListOption.currentDirectory(),
     *     BlobListOption.prefix(directory));
     * Iterator<Blob> blobIterator = blobs.iterateAll().iterator();
     * while (blobIterator.hasNext()) {
     *   Blob blob = blobIterator.next();
     *   // do something with the blob
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    Page<StorageObject> list(String bucket, BlobListOptions... options);

    /**
     * Updates bucket information.
     *
     * <p>Accepts an optional userProject {@link BucketTargetOptions} option which defines the project
     * id to assign operational costs.
     *
     * <p>Example of updating bucket information.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * BucketInfo bucketInfo = BucketInfo.newBuilder(bucketName).setVersioningEnabled(true).build();
     * Bucket bucket = storage.update(bucketInfo);
     * }</pre>
     *
     * @return the updated bucket
     * @throws StorageServiceException upon failure
     */
    StorageBucket update(BucketMetadata bucketInfo, BucketTargetOptions... options);

    /**
     * Updates the blob properties if the preconditions specified by {@code options} are met. The
     * property update works as described in {@link #update(BlobMetadata)}.
     *
     * <p>{@code options} parameter can contain the preconditions for applying the update. E.g. update
     * of the blob properties might be required only if the properties have not been updated
     * externally. {@code StorageException} with the code {@code 412} is thrown if preconditions fail.
     *
     * <p>Example of updating the content type only if the properties are not updated externally:
     *
     * <pre>{@code
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * Blob blob = storage.create(blobInfo);
     *
     * doSomething();
     *
     * BlobInfo update = blob.toBuilder().setContentType("multipart/form-data").build();
     * Storage.BlobTargetOption option = Storage.BlobTargetOption.metagenerationMatch();
     * try {
     *   storage.update(update, option);
     * } catch (StorageException e) {
     *   if (e.getCode() == 412) {
     *     // the properties were updated externally
     *   } else {
     *     throw e;
     *   }
     * }
     * }</pre>
     *
     * @param blobMetadata information to update
     * @param writeOptions preconditions to apply the update
     * @return the updated blob
     * @throws StorageServiceException upon failure
     * @see <a
     *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
     */
    StorageObject update(BlobMetadata blobMetadata, BlobUploadOption... writeOptions);

    /**
     * Updates the properties of the blob. This method issues an RPC request to merge the current blob
     * properties with the properties in the provided {@code blobInfo}. Properties not defined in
     * {@code blobInfo} will not be updated. To unset a blob property this property in {@code
     * blobInfo} should be explicitly set to {@code null}.
     *
     * <p>Bucket or blob's name cannot be changed by this method. If you want to rename the blob or
     * move it to a different bucket use the {@link StorageObject#copyToTarget} and {@link #delete} operations.
     *
     * <p>Property update alters the blob metadata generation and doesn't alter the blob generation.
     *
     * <p>Example of how to update blob's user provided metadata and unset the content type:
     *
     * <pre>{@code
     * Map<String, String> metadataUpdate = new HashMap<>();
     * metadataUpdate.put("keyToAdd", "new value");
     * metadataUpdate.put("keyToRemove", null);
     * BlobInfo blobUpdate = BlobInfo.newBuilder(bucketName, blobName)
     *     .setMetadata(metadataUpdate)
     *     .setContentType(null)
     *     .build();
     * Blob blob = storage.update(blobUpdate);
     * }</pre>
     *
     * @param blobMetadata information to update
     * @return the updated blob
     * @throws StorageServiceException upon failure
     * @see <a
     *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
     */
    StorageObject update(BlobMetadata blobMetadata);

    /**
     * Deletes the requested bucket.
     *
     * <p>Accepts an optional userProject {@link BucketSourceOptions} option which defines the project
     * id to assign operational costs.
     *
     * <p>Example of deleting a bucket, only if its metageneration matches a value, otherwise a {@link
     * StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * long bucketMetageneration = 42;
     * boolean deleted = storage.delete(bucketName,
     *     BucketSourceOption.metagenerationMatch(bucketMetageneration));
     * if (deleted) {
     *   // the bucket was deleted
     * } else {
     *   // the bucket was not found
     * }
     * }</pre>
     *
     * @return {@code true} if bucket was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean delete(String bucket, BucketSourceOptions... options);

    /**
     * Deletes the requested blob.
     *
     * <p>Example of deleting a blob, only if its generation matches a value, otherwise a {@link
     * StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * boolean deleted = storage.delete(bucketName, blobName,
     *     BlobSourceOption.generationMatch(blobGeneration));
     * if (deleted) {
     *   // the blob was deleted
     * } else {
     *   // the blob was not found
     * }
     * }</pre>
     *
     * @return {@code true} if blob was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean delete(String bucket, String blob, BlobSourceOptions... options);

    /**
     * Deletes the requested blob.
     *
     * <p>Accepts an optional userProject {@link BlobSourceOptions} option which defines the project id
     * to assign operational costs.
     *
     * <p>Example of deleting a blob, only if its generation matches a value, otherwise a {@link
     * StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * boolean deleted = storage.delete(blobId, BlobSourceOption.generationMatch(blobGeneration));
     * if (deleted) {
     *   // the blob was deleted
     * } else {
     *   // the blob was not found
     * }
     * }</pre>
     *
     * @return {@code true} if blob was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean delete(BlobId blob, BlobSourceOptions... options);

    /**
     * Deletes the requested blob.
     *
     * <p>Example of deleting a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * boolean deleted = storage.delete(blobId);
     * if (deleted) {
     *   // the blob was deleted
     * } else {
     *   // the blob was not found
     * }
     * }</pre>
     *
     * @return {@code true} if blob was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean delete(BlobId blob);

    /**
     * Sends a compose request.
     *
     * <p>Accepts an optional userProject {@link BlobUploadOption} option which defines the project id
     * to assign operational costs.
     *
     * <p>Example of composing two blobs.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String sourceBlob1 = "source_blob_1";
     * String sourceBlob2 = "source_blob_2";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * ComposeRequest request = ComposeRequest.newBuilder()
     *     .setTarget(blobInfo)
     *     .addSource(sourceBlob1)
     *     .addSource(sourceBlob2)
     *     .build();
     * Blob blob = storage.compose(request);
     * }</pre>
     *
     * @return the composed blob
     * @throws StorageServiceException upon failure
     */
    StorageObject compose(ComposeBlobsRequest composeRequest);

    /**
     * Sends a copy request. This method copies both blob's data and information. To override source
     * blob's information supply a {@code BlobInfo} to the {@code CopyRequest} using either {@link
     * ChunkedCopyRequest.CopyOperationBuilder#setTarget(BlobMetadata, BlobUploadOption...)} or {@link
     * ChunkedCopyRequest.CopyOperationBuilder#setTarget(BlobMetadata, Iterable)}.
     *
     * <p>This method returns a {@link ObjectCopyWriter} object for the provided {@code CopyRequest}. If
     * source and destination objects share the same location and storage class the source blob is
     * copied with one request and {@link ObjectCopyWriter#getResult()} immediately returns, regardless of
     * the {@link ChunkedCopyRequest#mbPerChunk} parameter. If source and destination have
     * different location or storage class {@link ObjectCopyWriter#getResult()} might issue multiple RPC
     * calls depending on blob's size.
     *
     * <p>Example of copying a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String copyBlobName = "copy_blob_name";
     * CopyRequest request = CopyRequest.newBuilder()
     *     .setSource(BlobId.of(bucketName, blobName))
     *     .setTarget(BlobId.of(bucketName, copyBlobName))
     *     .build();
     * Blob blob = storage.copy(request).getResult();
     * }</pre>
     *
     * <p>Example of copying a blob in chunks.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String copyBlobName = "copy_blob_name";
     * CopyRequest request = CopyRequest.newBuilder()
     *     .setSource(BlobId.of(bucketName, blobName))
     *     .setTarget(BlobId.of(bucketName, copyBlobName))
     *     .build();
     * CopyWriter copyWriter = storage.copy(request);
     * while (!copyWriter.isDone()) {
     *   copyWriter.copyChunk();
     * }
     * Blob blob = copyWriter.getResult();
     * }</pre>
     *
     * <p>Example of rotating the encryption key of a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String oldEncryptionKey = "old_encryption_key";
     * String newEncryptionKey = "new_encryption_key";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * CopyRequest request = CopyRequest.newBuilder()
     *     .setSource(blobId)
     *     .setSourceOptions(BlobSourceOption.decryptionKey(oldEncryptionKey))
     *     .setTarget(blobId, BlobTargetOption.encryptionKey(newEncryptionKey))
     *     .build();
     * Blob blob = storage.copy(request).getResult();
     * }</pre>
     *
     * @return a {@link ObjectCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
     */
    ObjectCopyWriter copy(ChunkedCopyRequest copyRequest);

    /**
     * Reads all the bytes from a blob.
     *
     * <p>Example of reading all bytes of a blob, if generation matches a value, otherwise a {@link
     * StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42";
     * byte[] content = storage.readAllBytes(bucketName, blobName,
     *     BlobSourceOption.generationMatch(blobGeneration));
     * }</pre>
     *
     * @return the blob's content
     * @throws StorageServiceException upon failure
     */
    byte[] readAllBytes(String bucket, String blob, BlobSourceOptions... options);

    /**
     * Reads all the bytes from a blob.
     *
     * <p>Example of reading all bytes of a blob's specific generation, otherwise a {@link
     * StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName, blobGeneration);
     * byte[] content = storage.readAllBytes(blobId);
     * }</pre>
     *
     * <p>Example of reading all bytes of an encrypted blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String decryptionKey = "my_encryption_key";
     * byte[] content = storage.readAllBytes(
     *     bucketName, blobName, BlobSourceOption.decryptionKey(decryptionKey));
     * }</pre>
     *
     * @return the blob's content
     * @throws StorageServiceException upon failure
     */
    byte[] readAllBytes(BlobId blob, BlobSourceOptions... options);

    /**
     * Creates a new empty batch for grouping multiple service calls in one underlying RPC call.
     *
     * <p>Example of using a batch request to delete, update and get a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName1 = "my-blob-name1";
     * String blobName2 = "my-blob-name2";
     * StorageBatch batch = storage.batch();
     * BlobId firstBlob = BlobId.of(bucketName, blobName1);
     * BlobId secondBlob = BlobId.of(bucketName, blobName2);
     * batch.delete(firstBlob).notify(new BatchResult.Callback<Boolean, StorageException>() {
     *   public void success(Boolean result) {
     *     // deleted successfully
     *   }
     *
     *   public void error(StorageException exception) {
     *     // delete failed
     *   }
     * });
     * batch.update(BlobInfo.newBuilder(secondBlob).setContentType("text/plain").build());
     * StorageBatchResult<Blob> result = batch.get(secondBlob);
     * batch.submit();
     * Blob blob = result.get(); // returns get result or throws StorageException
     * }</pre>
     */
    StorageOperationBatch batch();

    /**
     * Returns a channel for reading the blob's content. The blob's latest generation is read. If the
     * blob changes while reading (i.e. {@link BlobMetadata#getEtag()} changes), subsequent calls to
     * {@code blobReadChannel.read(ByteBuffer)} may throw {@link StorageServiceException}.
     *
     * <p>Example of reading a blob's content through a reader.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * try (ReadChannel reader = storage.reader(bucketName, blobName)) {
     *   ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
     *   while (reader.read(bytes) > 0) {
     *     bytes.flip();
     *     // do something with bytes
     *     bytes.clear();
     *   }
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    ReadChannel reader(String bucket, String blob, BlobSourceOptions... options);

    /**
     * Returns a channel for reading the blob's content. If {@code blob.generation()} is set data
     * corresponding to that generation is read. If {@code blob.generation()} is {@code null} the
     * blob's latest generation is read. If the blob changes while reading (i.e. {@link
     * BlobMetadata#getEtag()} changes), subsequent calls to {@code blobReadChannel.read(ByteBuffer)} may
     * throw {@link StorageServiceException}.
     *
     * <p>The {@link BlobSourceOptions#ifGenerationMatch()} and {@link
     * BlobSourceOptions#ifGenerationMatch(long)} options can be used to ensure that {@code
     * blobReadChannel.read(ByteBuffer)} calls will throw {@link StorageServiceException} if the blob`s
     * generation differs from the expected one.
     *
     * <p>Example of reading a blob's content through a reader.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * try (ReadChannel reader = storage.reader(blobId)) {
     *   ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
     *   while (reader.read(bytes) > 0) {
     *     bytes.flip();
     *     // do something with bytes
     *     bytes.clear();
     *   }
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    ReadChannel reader(BlobId blob, BlobSourceOptions... options);

    /**
     * Downloads the given blob to the given path using specified blob read options.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * Path destination = Paths.get("my-blob-destination.txt");
     * downloadTo(blobId, destination);
     * // do stuff with destination
     * }</pre>
     *
     * @param sourceName
     * @param fileLocation
     * @param writeOptions
     * @throws StorageServiceException upon failure
     */
    void downloadTo(BlobId sourceName, Path fileLocation, BlobSourceOptions... writeOptions);

    /**
     * Downloads the given blob to the given output stream using specified blob read options.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * Path destination = Paths.get("my-blob-destination.txt");
     * try (OutputStream outputStream = Files.newOutputStream(path)) {
     *  downloadTo(blob, outputStream);
     *  // do stuff with destination
     * }
     * }</pre>
     *
     * @param sourceName
     * @param out
     * @param writeOptions
     */
    void downloadTo(BlobId sourceName, OutputStream out, BlobSourceOptions... writeOptions);

    /**
     * Creates a blob and returns a channel for writing its content. By default any MD5 and CRC32C
     * values in the given {@code blobInfo} are ignored unless requested via the {@code
     * BlobWriteOption.md5Match} and {@code BlobWriteOption.crc32cMatch} options.
     *
     * <p>Example of writing a blob's content through a writer:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * byte[] content = "Hello, World!".getBytes(UTF_8);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * try (WriteChannel writer = storage.writer(blobInfo)) {
     *     writer.write(ByteBuffer.wrap(content, 0, content.length));
     * } catch (IOException ex) {
     *   // handle exception
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    WriteChannel writer(BlobMetadata blobInfo, BlobWriteOptions... options);

    /**
     * Accepts signed URL and return a channel for writing content.
     *
     * <p>Example of writing content through a writer using signed URL.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * byte[] content = "Hello, World!".getBytes(UTF_8);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * URL signedURL = storage.signUrl(
     *     blobInfo,
     *     1, TimeUnit.HOURS,
     *     Storage.SignUrlOption.httpMethod(HttpMethod.POST));
     * try (WriteChannel writer = storage.writer(signedURL)) {
     *    writer.write(ByteBuffer.wrap(content, 0, content.length));
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    WriteChannel writer(URL signedURL);

    /**
     * Generates a signed URL for a blob. If you have a blob that you want to allow access to for a
     * fixed amount of time, you can use this method to generate a URL that is only valid within a
     * certain time period. This is particularly useful if you don't want publicly accessible blobs,
     * but also don't want to require users to explicitly log in. Signing a URL requires a service
     * account signer. If an instance of {@link com.google.auth.ServiceAccountSigner} was passed to
     * {@link StorageClientOptions}' builder via {@code setCredentials(Credentials)} or the default
     * credentials are being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS}
     * is set or your application is running in App Engine, then {@code signUrl} will use that
     * credentials to sign the URL. If the credentials passed to {@link StorageClientOptions} do not
     * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
     * credentials) then {@code signUrl} will throw an {@link IllegalStateException} unless an
     * implementation of {@link ServiceAccountSigner} is passed using the {@link
     * UrlSigningOption#withSigner(ServiceAccountSigner)} option.
     *
     * <p>A service account signer is looked for in the following order:
     *
     * <ol>
     *   <li>The signer passed with the option {@link UrlSigningOption#withSigner(ServiceAccountSigner)}
     *   <li>The credentials passed to {@link StorageClientOptions}
     *   <li>The default credentials, if no credentials were passed to {@link StorageClientOptions}
     * </ol>
     *
     * <p>Example of creating a signed URL that is valid for 1 week, using the default credentials for
     * signing the URL, the default signing method (V2), and the default URL style (path-style):
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * URL signedUrl = storage.signUrl(
     *     BlobInfo.newBuilder(bucketName, blobName).build(),
     *     7, TimeUnit.DAYS);
     * }</pre>
     *
     * <p>Example of creating a signed URL passing the {@link UrlSigningOption#useV4Signature()} option,
     * which enables V4 signing:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * URL signedUrl = storage.signUrl(
     *     BlobInfo.newBuilder(bucketName, blobName).build(),
     *     7, TimeUnit.DAYS,
     *     Storage.SignUrlOption.withV4Signature());
     * }</pre>
     *
     * <p>Example of creating a signed URL passing the {@link UrlSigningOption#enableVirtualHostedStyle()}
     * option, which specifies the bucket name in the hostname of the URI, rather than in the path:
     *
     * <pre>{@code
     * URL signedUrl = storage.signUrl(
     *     BlobInfo.newBuilder(bucketName, blobName).build(),
     *     1, TimeUnit.DAYS,
     *     Storage.SignUrlOption.withVirtualHostedStyle());
     * }</pre>
     *
     * <p>Example of creating a signed URL passing the {@link UrlSigningOption#enablePathStyle()} option,
     * which specifies the bucket name in path portion of the URI, rather than in the hostname:
     *
     * <pre>{@code
     * URL signedUrl = storage.signUrl(
     *     BlobInfo.newBuilder(bucketName, blobName).build(),
     *     1, TimeUnit.DAYS,
     *     Storage.SignUrlOption.withPathStyle());
     * }</pre>
     *
     * <p>Example of creating a signed URL passing the {@link
     * UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used for signing the URL:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String kfPath = "/path/to/keyfile.json";
     * URL signedUrl = storage.signUrl(
     *     BlobInfo.newBuilder(bucketName, blobName).build(),
     *     7, TimeUnit.DAYS,
     *     SignUrlOption.signWith(ServiceAccountCredentials.fromStream(new FileInputStream(kfPath))));
     * }</pre>
     *
     * <p>Note that the {@link ServiceAccountSigner} may require additional configuration to enable
     * URL signing. See the documentation for the implementation for more details.
     *
     * <p>Example of creating a signed URL for a blob with generation:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long generation = 1576656755290328L;
     *
     * URL signedUrl = storage.signUrl(
     *     BlobInfo.newBuilder(bucketName, blobName, generation).build(),
     *     7, TimeUnit.DAYS,
     *     SignUrlOption.withQueryParams(ImmutableMap.of("generation", String.valueOf(generation))));
     * }</pre>
     *
     * @param blobMetadata the blob associated with the signed URL
     * @param timeout time until the signed URL expires, expressed in {@code unit}. The finest
     *     granularity supported is 1 second, finer granularities will be truncated
     * @param timeScale time unit of the {@code duration} parameter
     * @param writeOptions optional URL signing options
     * @throws IllegalStateException if {@link UrlSigningOption#withSigner(ServiceAccountSigner)} was not
     *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
     *     StorageClientOptions}
     * @throws IllegalArgumentException if {@code SignUrlOption.withMd5()} option is used and {@code
     *     blobInfo.md5()} is {@code null}
     * @throws IllegalArgumentException if {@code SignUrlOption.withContentType()} option is used and
     *     {@code blobInfo.contentType()} is {@code null}
     * @throws SigningException if the attempt to sign the URL failed
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
     */
    URL signUrl(BlobMetadata blobMetadata, long timeout, TimeUnit timeScale, UrlSigningOption... writeOptions);

    /**
     * Generates a URL and a map of fields that can be specified in an HTML form to submit a POST
     * request. The returned map includes a signature which must be provided with the request.
     * Generating a presigned POST policy requires a service account signer. If an instance of {@link
     * com.google.auth.ServiceAccountSigner} was passed to {@link StorageClientOptions}' builder via {@code
     * setCredentials(Credentials)} or the default credentials are being used and the environment
     * variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set, generatPresignedPostPolicyV4 will use
     * that credentials to sign the URL. If the credentials passed to {@link StorageClientOptions} do not
     * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
     * credentials) then {@code signUrl} will throw an {@link IllegalStateException} unless an
     * implementation of {@link ServiceAccountSigner} is passed using the {@link
     * PostPolicyV4Parameter#withSigner(ServiceAccountSigner)} option.
     *
     * <p>Example of generating a presigned post policy which has the condition that only jpeg images
     * can be uploaded, and applies the public read acl to each image uploaded, and making the POST
     * request:
     *
     * <pre>{@code
     * PostFieldsV4 fields = PostFieldsV4.newBuilder().setAcl("public-read").build();
     * PostConditionsV4 conditions = PostConditionsV4.newBuilder().addContentTypeCondition(ConditionV4Type.MATCHES, "image/jpeg").build();
     *
     * PostPolicyV4 policy = storage.generateSignedPostPolicyV4(
     *     BlobInfo.newBuilder("my-bucket", "my-object").build(),
     *     7, TimeUnit.DAYS, fields, conditions);
     *
     * HttpClient client = HttpClientBuilder.create().build();
     * HttpPost request = new HttpPost(policy.getUrl());
     * MultipartEntityBuilder builder = MultipartEntityBuilder.create();
     *
     * for (Map.Entry<String, String> entry : policy.getFields().entrySet()) {
     *     builder.addTextBody(entry.getKey(), entry.getValue());
     * }
     * File file = new File("path/to/your/file/to/upload");
     * builder.addBinaryBody("file", new FileInputStream(file), ContentType.APPLICATION_OCTET_STREAM, file.getName());
     * request.setEntity(builder.build());
     * client.execute(request);
     * }</pre>
     *
     * @param blobMetadata the blob uploaded in the form
     * @param timeout time before expiration
     * @param timeScale duration time unit
     * @param selectedAttributes the fields specified in the form
     * @param postConstraints which conditions every upload must satisfy
     * @param timeout how long until the form expires, in milliseconds
     * @param writeOptions optional post policy options
     * @see <a
     *     href="https://cloud.google.com/storage/docs/xml-api/post-object#usage_and_examples">POST
     *     Object</a>
     */
    S3PostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobMetadata, long timeout, TimeUnit timeScale, PostFieldsMapV4 selectedAttributes, PostConditionsV4Model postConstraints, PostPolicyV4Parameter... writeOptions);

    /**
     * Generates a presigned post policy without any conditions. Automatically creates required
     * conditions. See full documentation for {@link #generateSignedPostPolicyV4(BlobMetadata, long,
     * TimeUnit, PostFieldsMapV4, S3PostPolicyV4.PostConditionsV4Model, PostPolicyV4Parameter...)}.
     */
    S3PostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobInfo, long duration, TimeUnit unit, PostFieldsMapV4 fields, PostPolicyV4Parameter... options);

    /**
     * Generates a presigned post policy without any fields. Automatically creates required fields.
     * See full documentation for {@link #generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit,
     * PostFieldsMapV4, PostConditionsV4Model, PostPolicyV4Parameter...)}.
     */
    S3PostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobInfo, long duration, TimeUnit unit, PostConditionsV4Model conditions, PostPolicyV4Parameter... options);

    /**
     * Generates a presigned post policy without any fields or conditions. Automatically creates
     * required fields and conditions. See full documentation for {@link
     * #generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit, PostFieldsMapV4,
     * S3PostPolicyV4.PostConditionsV4Model, PostPolicyV4Parameter...)}.
     */
    S3PostPolicyV4 generateSignedPostPolicyV4(BlobMetadata blobInfo, long duration, TimeUnit unit, PostPolicyV4Parameter... options);

    /**
     * Gets the requested blobs. A batch request is used to perform this call.
     *
     * <p>Example of getting information on several blobs using a single batch request.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName1 = "my-blob-name1";
     * String blobName2 = "my-blob-name2";
     * BlobId firstBlob = BlobId.of(bucketName, blobName1);
     * BlobId secondBlob = BlobId.of(bucketName, blobName2);
     * List<Blob> blobs = storage.get(firstBlob, secondBlob);
     * }</pre>
     *
     * @param objectIds blobs to get
     * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
     *     been denied the corresponding item in the list is {@code null}.
     * @throws StorageServiceException upon failure
     */
    List<StorageObject> get(BlobId... objectIds);

    /**
     * Gets the requested blobs. A batch request is used to perform this call.
     *
     * <p>Example of getting information on several blobs using a single batch request.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName1 = "my-blob-name1";
     * String blobName2 = "my-blob-name2";
     * List<BlobId> blobIds = new LinkedList<>();
     * blobIds.add(BlobId.of(bucketName, blobName1));
     * blobIds.add(BlobId.of(bucketName, blobName2));
     * List<Blob> blobs = storage.get(blobIds);
     * }</pre>
     *
     * @param objectIds blobs to get
     * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
     *     been denied the corresponding item in the list is {@code null}.
     * @throws StorageServiceException upon failure
     */
    List<StorageObject> get(Iterable<BlobId> objectIds);

    /**
     * Updates the requested blobs. A batch request is used to perform this call. The original
     * properties are merged with the properties in the provided {@code BlobInfo} objects. Unsetting a
     * property can be done by setting the property of the provided {@code BlobInfo} objects to {@code
     * null}. See {@link #update(BlobMetadata)} for a code example.
     *
     * <p>Example of updating information on several blobs using a single batch request.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName1 = "my-blob-name1";
     * String blobName2 = "my-blob-name2";
     * Blob firstBlob = storage.get(bucketName, blobName1);
     * Blob secondBlob = storage.get(bucketName, blobName2);
     * List<Blob> updatedBlobs = storage.update(
     *     firstBlob.toBuilder().setContentType("text/plain").build(),
     *     secondBlob.toBuilder().setContentType("text/plain").build());
     * }</pre>
     *
     * @param metadataArray blobs to update
     * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
     *     been denied the corresponding item in the list is {@code null}.
     * @throws StorageServiceException upon failure
     */
    List<StorageObject> update(BlobMetadata... metadataArray);

    /**
     * Updates the requested blobs. A batch request is used to perform this call. The original
     * properties are merged with the properties in the provided {@code BlobInfo} objects. Unsetting a
     * property can be done by setting the property of the provided {@code BlobInfo} objects to {@code
     * null}. See {@link #update(BlobMetadata)} for a code example.
     *
     * <p>Example of updating information on several blobs using a single batch request.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName1 = "my-blob-name1";
     * String blobName2 = "my-blob-name2";
     * Blob firstBlob = storage.get(bucketName, blobName1);
     * Blob secondBlob = storage.get(bucketName, blobName2);
     * List<BlobInfo> blobs = new LinkedList<>();
     * blobs.add(firstBlob.toBuilder().setContentType("text/plain").build());
     * blobs.add(secondBlob.toBuilder().setContentType("text/plain").build());
     * List<Blob> updatedBlobs = storage.update(blobs);
     * }</pre>
     *
     * @param metadataArray blobs to update
     * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
     *     been denied the corresponding item in the list is {@code null}.
     * @throws StorageServiceException upon failure
     */
    List<StorageObject> update(Iterable<BlobMetadata> metadataArray);

    /**
     * Deletes the requested blobs. A batch request is used to perform this call.
     *
     * <p>Example of deleting several blobs using a single batch request.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName1 = "my-blob-name1";
     * String blobName2 = "my-blob-name2";
     * BlobId firstBlob = BlobId.of(bucketName, blobName1);
     * BlobId secondBlob = BlobId.of(bucketName, blobName2);
     * List<Boolean> deleted = storage.delete(firstBlob, secondBlob);
     * }</pre>
     *
     * @param objectIds blobs to delete
     * @return an immutable list of booleans. If a blob has been deleted the corresponding item in the
     *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
     *     was denied the corresponding item is {@code false}.
     * @throws StorageServiceException upon failure
     */
    List<Boolean> delete(BlobId... objectIds);

    /**
     * Deletes the requested blobs. A batch request is used to perform this call.
     *
     * <p>Example of deleting several blobs using a single batch request.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName1 = "my-blob-name1";
     * String blobName2 = "my-blob-name2";
     * List<BlobId> blobIds = new LinkedList<>();
     * blobIds.add(BlobId.of(bucketName, blobName1));
     * blobIds.add(BlobId.of(bucketName, blobName2));
     * List<Boolean> deleted = storage.delete(blobIds);
     * }</pre>
     *
     * @param objectIds blobs to delete
     * @return an immutable list of booleans. If a blob has been deleted the corresponding item in the
     *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
     *     was denied the corresponding item is {@code false}.
     * @throws StorageServiceException upon failure
     */
    List<Boolean> delete(Iterable<BlobId> objectIds);

    /**
     * Returns the ACL entry for the specified entity on the specified bucket or {@code null} if not
     * found.
     *
     * <p>Example of getting the ACL entry for an entity on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Acl acl = storage.getAcl(bucketName, User.ofAllAuthenticatedUsers());
     * }</pre>
     *
     * <p>Example of getting the ACL entry for a specific user on a requester_pays bucket with a
     * user_project option.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String userEmail = "google-cloud-java-tests@java-docs-samples-tests.iam.gserviceaccount.com";
     * BucketSourceOption userProjectOption = BucketSourceOption.userProject("myProject");
     * Acl acl = storage.getAcl(bucketName, new User(userEmail), userProjectOption);
     * }</pre>
     *
     * @param storageName name of the bucket where the getAcl operation takes place
     * @param principal ACL entity to fetch
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry getAcl(String storageName, AbstractEntity principal, BucketSourceOptions... writeOptions);

    /**
     * @see #getAcl(String, AbstractEntity, BucketSourceOptions...)
     */
    AccessControlEntry getAcl(String bucket, AccessControlEntry.AbstractEntity entity);

    /**
     * Deletes the ACL entry for the specified entity on the specified bucket.
     *
     * <p>Example of deleting the ACL entry for an entity on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * boolean deleted = storage.deleteAcl(bucketName, User.ofAllAuthenticatedUsers());
     * if (deleted) {
     *   // the acl entry was deleted
     * } else {
     *   // the acl entry was not found
     * }
     * }</pre>
     *
     * <p>Example of deleting the ACL entry for a specific user on a requester_pays bucket with a
     * user_project option.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * BucketSourceOption userProject = BucketSourceOption.userProject("myProject");
     * boolean deleted = storage.deleteAcl(bucketName, User.ofAllAuthenticatedUsers(), userProject);
     * }</pre>
     *
     * @param storageName name of the bucket to delete an ACL from
     * @param principal ACL entity to delete
     * @param writeOptions extra parameters to apply to this operation
     * @return {@code true} if the ACL was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean deleteAcl(String storageName, AbstractEntity principal, BucketSourceOptions... writeOptions);

    /**
     * @see #deleteAcl(String, AbstractEntity, BucketSourceOptions...)
     */
    boolean deleteAcl(String bucket, AbstractEntity entity);

    /**
     * Creates a new ACL entry on the specified bucket.
     *
     * <p>Example of creating a new ACL entry on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Acl acl = storage.createAcl(bucketName, Acl.of(User.ofAllAuthenticatedUsers(), Role.READER));
     * }</pre>
     *
     * <p>Example of creating a new ACL entry on a requester_pays bucket with a user_project option.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Acl acl = storage.createAcl(bucketName, Acl.of(User.ofAllAuthenticatedUsers(), Role.READER),
     *     BucketSourceOption.userProject("myProject"));
     * }</pre>
     *
     * @param storageName name of the bucket for which an ACL should be created
     * @param accessControlList ACL to create
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry createAcl(String storageName, AccessControlEntry accessControlList, BucketSourceOptions... writeOptions);

    /**
     * @see #createAcl(String, AccessControlEntry, BucketSourceOptions...)
     */
    AccessControlEntry createAcl(String bucket, AccessControlEntry acl);

    /**
     * Updates an ACL entry on the specified bucket.
     *
     * <p>Example of updating a new ACL entry on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Acl acl = storage.updateAcl(bucketName, Acl.of(User.ofAllAuthenticatedUsers(), Role.OWNER));
     * }</pre>
     *
     * <p>Example of updating a new ACL entry on a requester_pays bucket with a user_project option.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Acl acl = storage.updateAcl(bucketName, Acl.of(User.ofAllAuthenticatedUsers(), Role.OWNER),
     *     BucketSourceOption.userProject("myProject"));
     * }</pre>
     *
     * @param storageName name of the bucket where the updateAcl operation takes place
     * @param accessControlList ACL to update
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry updateAcl(String storageName, AccessControlEntry accessControlList, BucketSourceOptions... writeOptions);

    /**
     * @see #updateAcl(String, AccessControlEntry, BucketSourceOptions...)
     */
    AccessControlEntry updateAcl(String bucket, AccessControlEntry acl);

    /**
     * Lists the ACL entries for the provided bucket.
     *
     * <p>Example of listing the ACL entries for a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * List<Acl> acls = storage.listAcls(bucketName);
     * for (Acl acl : acls) {
     *   // do something with ACL entry
     * }
     * }</pre>
     *
     * <p>Example of listing the ACL entries for a blob in a requester_pays bucket with a user_project
     * option.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * List<Acl> acls = storage.listAcls(bucketName, BucketSourceOption.userProject("myProject"));
     * for (Acl acl : acls) {
     *   // do something with ACL entry
     * }
     * }</pre>
     *
     * @param storageName the name of the bucket to list ACLs for
     * @param writeOptions any number of BucketSourceOptions to apply to this operation
     * @throws StorageServiceException upon failure
     */
    List<AccessControlEntry> listAcls(String storageName, BucketSourceOptions... writeOptions);

    /**
     * @see #listAcls(String, BucketSourceOptions...)
     */
    List<AccessControlEntry> listAcls(String bucket);

    /**
     * Returns the default object ACL entry for the specified entity on the specified bucket or {@code
     * null} if not found.
     *
     * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
     * blob.
     *
     * <p>Example of getting the default ACL entry for an entity on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Acl acl = storage.getDefaultAcl(bucketName, User.ofAllAuthenticatedUsers());
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry getDefaultAcl(String bucket, AbstractEntity entity);

    /**
     * Deletes the default object ACL entry for the specified entity on the specified bucket.
     *
     * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
     * blob.
     *
     * <p>Example of deleting the default ACL entry for an entity on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * boolean deleted = storage.deleteDefaultAcl(bucketName, User.ofAllAuthenticatedUsers());
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
    boolean deleteDefaultAcl(String bucket, AbstractEntity entity);

    /**
     * Creates a new default blob ACL entry on the specified bucket.
     *
     * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
     * blob.
     *
     * <p>Example of creating a new default ACL entry on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Acl acl =
     *     storage.createDefaultAcl(bucketName, Acl.of(User.ofAllAuthenticatedUsers(), Role.READER));
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry createDefaultAcl(String bucket, AccessControlEntry acl);

    /**
     * Updates a default blob ACL entry on the specified bucket.
     *
     * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
     * blob.
     *
     * <p>Example of updating a new default ACL entry on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Acl acl =
     *     storage.updateDefaultAcl(bucketName, Acl.of(User.ofAllAuthenticatedUsers(), Role.OWNER));
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry updateDefaultAcl(String bucket, AccessControlEntry acl);

    /**
     * Lists the default blob ACL entries for the provided bucket.
     *
     * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
     * blob.
     *
     * <p>Example of listing the default ACL entries for a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * List<Acl> acls = storage.listDefaultAcls(bucketName);
     * for (Acl acl : acls) {
     *   // do something with ACL entry
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    List<AccessControlEntry> listDefaultAcls(String bucket);

    /**
     * Returns the ACL entry for the specified entity on the specified blob or {@code null} if not
     * found.
     *
     * <p>Example of getting the ACL entry for an entity on a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName, blobGeneration);
     * Acl acl = storage.getAcl(blobId, User.ofAllAuthenticatedUsers());
     * }</pre>
     *
     * <p>Example of getting the ACL entry for a specific user on a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * String userEmail = "google-cloud-java-tests@java-docs-samples-tests.iam.gserviceaccount.com";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * Acl acl = storage.getAcl(blobId, new User(userEmail));
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry getAcl(BlobId blob, AbstractEntity entity);

    /**
     * Deletes the ACL entry for the specified entity on the specified blob.
     *
     * <p>Example of deleting the ACL entry for an entity on a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName, blobGeneration);
     * boolean deleted = storage.deleteAcl(blobId, User.ofAllAuthenticatedUsers());
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
    boolean deleteAcl(BlobId blob, AbstractEntity entity);

    /**
     * Creates a new ACL entry on the specified blob.
     *
     * <p>Example of creating a new ACL entry on a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName, blobGeneration);
     * Acl acl = storage.createAcl(blobId, Acl.of(User.ofAllAuthenticatedUsers(), Role.READER));
     * }</pre>
     *
     * <p>Example of updating a blob to be public-read.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName, blobGeneration);
     * Acl acl = storage.createAcl(blobId, Acl.of(User.ofAllUsers(), Role.READER));
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry createAcl(BlobId blob, AccessControlEntry acl);

    /**
     * Updates an ACL entry on the specified blob.
     *
     * <p>Example of updating a new ACL entry on a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName, blobGeneration);
     * Acl acl = storage.updateAcl(blobId, Acl.of(User.ofAllAuthenticatedUsers(), Role.OWNER));
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    AccessControlEntry updateAcl(BlobId blob, AccessControlEntry acl);

    /**
     * Lists the ACL entries for the provided blob.
     *
     * <p>Example of listing the ACL entries for a blob.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * long blobGeneration = 42;
     * BlobId blobId = BlobId.of(bucketName, blobName, blobGeneration);
     * List<Acl> acls = storage.listAcls(blobId);
     * for (Acl acl : acls) {
     *   // do something with ACL entry
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    List<AccessControlEntry> listAcls(BlobId blob);

    /**
     * Creates a new HMAC Key for the provided service account, including the secret key. Note that
     * the secret key is only returned upon creation via this method.
     *
     * <p>Example of creating a new HMAC Key.
     *
     * <pre>{@code
     * ServiceAccount serviceAccount = ServiceAccount.of("my-service-account@google.com");
     *
     * HmacKey hmacKey = storage.createHmacKey(serviceAccount);
     *
     * String secretKey = hmacKey.getSecretKey();
     * HmacKey.HmacKeyMetadata metadata = hmacKey.getMetadata();
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    HmacSecretKey createHmacKey(ServiceAccountInfo serviceAccount, HmacKeyCreationOption... options);

    /**
     * Lists HMAC keys for a given service account. Note this returns {@code HmacKeyMetadata} objects,
     * which do not contain secret keys.
     *
     * <p>Example of listing HMAC keys, specifying project id.
     *
     * <pre>{@code
     * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
     *     Storage.ListHmacKeysOption.projectId("my-project-id"));
     * for (HmacKey.HmacKeyMetadata hmacKeyMetadata : metadataPage.getValues()) {
     *     //do something with the metadata
     * }
     * }</pre>
     *
     * <p>Example of listing HMAC keys, specifying max results and showDeletedKeys. Since projectId is
     * not specified, the same project ID as the storage client instance will be used
     *
     * <pre>{@code
     * ServiceAccount serviceAccount = ServiceAccount.of("my-service-account@google.com");
     *
     * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
     *     Storage.ListHmacKeysOption.serviceAccount(serviceAccount),
     *     Storage.ListHmacKeysOption.maxResults(10L),
     *     Storage.ListHmacKeysOption.showDeletedKeys(true));
     * for (HmacKey.HmacKeyMetadata hmacKeyMetadata : metadataPage.getValues()) {
     *     //do something with the metadata
     * }
     * }</pre>
     *
     * @param writeOptions the options to apply to this operation
     * @throws StorageServiceException upon failure
     */
    Page<HmacSecretKey.HmacKeyDetails> listHmacKeys(ListHmacKeysOptions... writeOptions);

    /**
     * Gets an HMAC key given its access id. Note that this returns a {@code HmacKeyMetadata} object,
     * which does not contain the secret key.
     *
     * <p>Example of getting an HMAC key. Since projectId isn't specified, the same project ID as the
     * storage client instance will be used.
     *
     * <pre>{@code
     * String hmacKeyAccessId = "my-access-id";
     * HmacKey.HmackeyMetadata hmacKeyMetadata = storage.getHmacKey(hmacKeyAccessId);
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    HmacSecretKey.HmacKeyDetails getHmacKey(String accessId, RetrieveHmacKeyOption... options);

    /**
     * Deletes an HMAC key. Note that only an {@code INACTIVE} key can be deleted. Attempting to
     * delete a key whose {@code HmacKey.HmacKeyState} is anything other than {@code INACTIVE} will
     * fail.
     *
     * <p>Example of updating an HMAC key's state to INACTIVE and then deleting it.
     *
     * <pre>{@code
     * String hmacKeyAccessId = "my-access-id";
     * HmacKey.HmacKeyMetadata hmacKeyMetadata = storage.getHmacKey(hmacKeyAccessId);
     *
     * storage.updateHmacKeyState(hmacKeyMetadata, HmacKey.HmacKeyState.INACTIVE);
     * storage.deleteHmacKey(hmacKeyMetadata);
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    void deleteHmacKey(HmacSecretKey.HmacKeyDetails hmacKeyMetadata, DeleteHmacKeyRequestOption... options);

    /**
     * Updates the state of an HMAC key and returns the updated metadata.
     *
     * <p>Example of updating the state of an HMAC key.
     *
     * <pre>{@code
     * String hmacKeyAccessId = "my-access-id";
     * HmacKey.HmacKeyMetadata hmacKeyMetadata = storage.getHmacKey(hmacKeyAccessId);
     *
     * storage.updateHmacKeyState(hmacKeyMetadata, HmacKey.HmacKeyState.INACTIVE);
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    HmacKeyDetails updateHmacKeyState(final HmacSecretKey.HmacKeyDetails hmacKeyMetadata, final HmacSecretKey.HmacKeyStatus state, HmacKeyUpdateOption... options);

    /**
     * Gets the IAM policy for the provided bucket.
     *
     * <p>It's possible for bindings to be empty and instead have permissions inherited through
     * Project or Organization IAM Policies. To prevent corrupting policies when you update an IAM
     * policy with {@code Storage.setIamPolicy}, the ETAG value is used to perform optimistic
     * concurrency.
     *
     * <p>Example of getting the IAM policy for a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Policy policy = storage.getIamPolicy(bucketName);
     * }</pre>
     *
     * @param storageName name of the bucket where the getIamPolicy operation takes place
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    Policy getIamPolicy(String storageName, BucketSourceOptions... writeOptions);

    /**
     * Updates the IAM policy on the specified bucket.
     *
     * <p>To prevent corrupting policies when you update an IAM policy with {@code
     * Storage.setIamPolicy}, the ETAG value is used to perform optimistic concurrency.
     *
     * <p>Example of updating the IAM policy on a bucket.
     *
     * <pre>{@code
     * // We want to make all objects in our bucket publicly readable.
     * String bucketName = "my-unique-bucket";
     * Policy currentPolicy = storage.getIamPolicy(bucketName);
     * Policy updatedPolicy =
     *     storage.setIamPolicy(
     *         bucketName,
     *         currentPolicy.toBuilder()
     *             .addIdentity(StorageRoles.objectViewer(), Identity.allUsers())
     *             .build());
     * }</pre>
     *
     * @param storageName name of the bucket where the setIamPolicy operation takes place
     * @param accessRules policy to be set on the specified bucket
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    Policy setIamPolicy(String storageName, Policy accessRules, BucketSourceOptions... writeOptions);

    /**
     * Tests whether the caller holds the permissions on the specified bucket. Returns a list of
     * booleans in the same placement and order in which the permissions were specified.
     *
     * <p>Example of testing permissions on a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * List<Boolean> response =
     *     storage.testIamPermissions(
     *         bucketName,
     *         ImmutableList.of("storage.buckets.get", "storage.buckets.getIamPolicy"));
     * for (boolean hasPermission : response) {
     *   // Do something with permission test response
     * }
     * }</pre>
     *
     * @param storageName name of the bucket where the testIamPermissions operation takes place
     * @param allowedActions list of permissions to test on the bucket
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    List<Boolean> testIamPermissions(String storageName, List<String> allowedActions, BucketSourceOptions... writeOptions);

    /**
     * Returns the service account associated with the given project.
     *
     * <p>Example of getting a service account.
     *
     * <pre>{@code
     * String projectId = "test@gmail.com";
     * ServiceAccount account = storage.getServiceAccount(projectId);
     * }</pre>
     *
     * @param projectIdentifier the ID of the project for which the service account should be fetched.
     * @return the service account associated with this project
     * @throws StorageServiceException upon failure
     */
    ServiceAccountInfo getServiceAccount(String projectIdentifier);

    /**
     * Creates the notification for a given bucket.
     *
     * <p>Example of creating a notification:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String topic = "projects/myProject/topics/myTopic"
     * NotificationInfo notificationInfo = NotificationInfo.newBuilder(topic)
     *  .setCustomAttributes(ImmutableMap.of("label1", "value1"))
     *  .setEventTypes(NotificationInfo.EventType.OBJECT_FINALIZE)
     *  .setPayloadFormat(NotificationInfo.PayloadFormat.JSON_API_V1)
     *  .build();
     * Notification notification = storage.createNotification(bucketName, notificationInfo);
     * }</pre>
     *
     * @param storageName name of the bucket
     * @param notificationDetails notification to create
     * @return the created notification
     * @throws StorageServiceException upon failure
     */
    StorageNotification createNotification(String storageName, NotificationMetadata notificationDetails);

    /**
     * Gets the notification with the specified id.
     *
     * <p>Example of getting the notification:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String notificationId = "my-unique-notification-id";
     * Notification notification = storage.getNotification(bucketName, notificationId);
     * }</pre>
     *
     * @param storageName name of the bucket
     * @param notificationIdentifier notification ID
     * @return the {@code Notification} object with the given id or {@code null} if not found
     * @throws StorageServiceException upon failure
     */
    StorageNotification getNotification(String storageName, String notificationIdentifier);

    /**
     * Retrieves the list of notifications associated with the bucket.
     *
     * <p>Example of listing the bucket notifications:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * List<Notification> notifications = storage.listNotifications(bucketName);
     * }</pre>
     *
     * @param storageName name of the bucket
     * @return a list of {@link StorageNotification} objects added to the bucket.
     * @throws StorageServiceException upon failure
     */
    List<StorageNotification> listNotifications(String storageName);

    /**
     * Deletes the notification with the specified id.
     *
     * <p>Example of deleting the notification:
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String notificationId = "my-unique-notification-id";
     * boolean deleted = storage.deleteNotification(bucketName, notificationId);
     * if (deleted) {
     *   // the notification was deleted
     * } else {
     *   // the notification was not found
     * }
     * }</pre>
     *
     * @param storageName name of the bucket
     * @param notificationIdentifier ID of the notification to delete
     * @return {@code true} if the notification has been deleted, {@code false} if not found
     * @throws StorageServiceException upon failure
     */
    boolean deleteNotification(String storageName, String notificationIdentifier);
}
