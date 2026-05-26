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
import com.google.cloud.storage.AclEntry.AbstractEntity;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
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
public interface StorageService extends Service<StorageSettings> {

    enum PredefinedAccessControlList {

        AUTHENTICATED_READ("authenticatedRead"),
        ALL_AUTHENTICATED_USERS("allAuthenticatedUsers"),
        PRIVATE("private"),
        PROJECT_PRIVATE("projectPrivate"),
        PUBLIC_READ("publicRead"),
        PUBLIC_READ_WRITE("publicReadWrite"),
        BUCKET_OWNER_READ("bucketOwnerRead"),
        BUCKET_OWNER_FULL_CONTROL("bucketOwnerFullControl");

        private final String accessItem;

        String getEntry() {
            return accessItem;
        }

        PredefinedAccessControlList(String accessItem) {
            this.accessItem = accessItem;
        }

    }

    enum BucketMetadataField implements FieldSelector {

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
        IAMCONFIGURATION("iamConfiguration");

        static final List<? extends FieldSelector> MANDATORY_SELECTORS = ImmutableList.of(NAME);

        private final String fieldKey;

        @Override
        public String getSelector() {
            return fieldKey;
        }

        BucketMetadataField(String fieldKey) {
            this.fieldKey = fieldKey;
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
        UPDATED("updated");

        static final List<? extends FieldSelector> MANDATORY_SELECTORS = ImmutableList.of(BUCKET, NAME);

        private final String fieldKey;

        @Override
        public String getSelector() {
            return fieldKey;
        }

        BlobMetadataField(String fieldKey) {
            this.fieldKey = fieldKey;
        }

    }

    enum UriSchemeType {

        HTTP("http"), HTTPS("https");

        private final String protocol;

        public String getScheme() {
            return protocol;
        }

        UriSchemeType(String protocol) {
            this.protocol = protocol;
        }

    }

    /**
     * Class for specifying bucket target options.
     */
    class BucketTargetOptions extends AbstractOption {

        private static final long serialVersionUID = -5880204616982900975L;

        /**
         * Returns an option to define the billing user project. This option is required by buckets with
         * `requester_pays` flag enabled to assign operation costs.
         */
        public static BucketTargetOptions forUserProject(String billingProject) {
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

        private BucketTargetOptions(StorageRpcClient.StorageOption transportOption) {
            this(transportOption, null);
        }

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if metageneration matches.
         */
        public static BucketTargetOptions ifMetagenerationNotMatch() {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for specifying bucket's default ACL configuration for blobs.
         */
        public static BucketTargetOptions predefinedDefaultObjectAcl(PredefinedAccessControlList accessControl) {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.PREDEFINED_DEFAULT_OBJECT_ACL, accessControl.getEntry());
        }

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BucketTargetOptions ifMetagenerationMatch() {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        /**
         * Returns an option for specifying bucket's predefined ACL configuration.
         */
        public static BucketTargetOptions withPredefinedAcl(PredefinedAccessControlList accessControl) {
            return new BucketTargetOptions(StorageRpcClient.StorageOption.PREDEFINED_ACL, accessControl.getEntry());
        }

        private BucketTargetOptions(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

    }

    /**
     * Class for specifying bucket source options.
     */
    class BucketRequestOption extends AbstractOption {

        private static final long serialVersionUID = 5185657617120212117L;

        /**
         * Returns an option for bucket's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BucketRequestOption setUserProject(String billingProject) {
            return new BucketRequestOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if bucket's metageneration matches the provided value.
         */
        public static BucketRequestOption ifMetagenerationNotMatch(long metaVersion) {
            return new BucketRequestOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaVersion);
        }

        public static BucketRequestOption requestPolicyVersion(long policyRevision) {
            return new BucketRequestOption(StorageRpcClient.StorageOption.REQUESTED_POLICY_VERSION, policyRevision);
        }

        private BucketRequestOption(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if bucket's metageneration does not match the provided value.
         */
        public static BucketRequestOption ifMetagenerationMatch(long metaVersion) {
            return new BucketRequestOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaVersion);
        }

    }

    /**
     * Class for specifying listHmacKeys options
     */
    class HmacKeyListOption extends AbstractOption {

        /**
         * Returns an option to specify whether to show deleted keys in the result. This option is false
         * by default.
         */
        public static HmacKeyListOption showDeletedKeys(boolean includeDeleted) {
            return new HmacKeyListOption(StorageRpcClient.StorageOption.SHOW_DELETED_KEYS, includeDeleted);
        }

        /**
         * Returns an option to specify the page token from which to start listing HMAC keys.
         */
        public static HmacKeyListOption pageCursor(String cursorToken) {
            return new HmacKeyListOption(StorageRpcClient.StorageOption.PAGE_TOKEN, cursorToken);
        }

        /**
         * Returns an option to specify the Project ID for this request. If not specified, defaults to
         * Application Default Credentials.
         */
        public static HmacKeyListOption projectId(String projectIdentifier) {
            return new HmacKeyListOption(StorageRpcClient.StorageOption.PROJECT_ID, projectIdentifier);
        }

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static HmacKeyListOption userProject(String billingProject) {
            return new HmacKeyListOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        private HmacKeyListOption(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

        /**
         * Returns an option for the Service Account whose keys to list. If this option is not used,
         * keys for all accounts will be listed.
         */
        public static HmacKeyListOption serviceAccountEmail(ServiceAccountInfo accountInfo) {
            return new HmacKeyListOption(StorageRpcClient.StorageOption.SERVICE_ACCOUNT_EMAIL, accountInfo.getEmail());
        }

        /**
         * Returns an option for the maximum amount of HMAC keys returned per page.
         */
        public static HmacKeyListOption maxItems(long maxResults) {
            return new HmacKeyListOption(StorageRpcClient.StorageOption.MAX_RESULTS, maxResults);
        }

    }

    /**
     * Class for specifying createHmacKey options
     */
    class HmacKeyCreationOption extends AbstractOption {

        /**
         * Returns an option to specify the Project ID for this request. If not specified, defaults to
         * Application Default Credentials.
         */
        public static HmacKeyCreationOption projectId(String projectIdentifier) {
            return new HmacKeyCreationOption(StorageRpcClient.StorageOption.PROJECT_ID, projectIdentifier);
        }

        private HmacKeyCreationOption(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static HmacKeyCreationOption userProject(String billingProject) {
            return new HmacKeyCreationOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

    }

    /**
     * Class for specifying getHmacKey options
     */
    class GetHmacKeyRequestOption extends AbstractOption {

        /**
         * Returns an option to specify the Project ID for this request. If not specified, defaults to
         * Application Default Credentials.
         */
        public static GetHmacKeyRequestOption projectId(String projectIdentifier) {
            return new GetHmacKeyRequestOption(StorageRpcClient.StorageOption.PROJECT_ID, projectIdentifier);
        }

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static GetHmacKeyRequestOption userProject(String billingProject) {
            return new GetHmacKeyRequestOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        private GetHmacKeyRequestOption(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

    }

    /**
     * Class for specifying deleteHmacKey options
     */
    class HmacKeyDeleteOption extends AbstractOption {

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static HmacKeyDeleteOption userProject(String billingProject) {
            return new HmacKeyDeleteOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        private HmacKeyDeleteOption(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

    }

    /**
     * Class for specifying updateHmacKey options
     */
    class HmacKeyUpdateOption extends AbstractOption {

        /**
         * Returns an option to specify the project to be billed for this request. Required for
         * Requester Pays buckets.
         */
        public static HmacKeyUpdateOption userProject(String billingProject) {
            return new HmacKeyUpdateOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        private HmacKeyUpdateOption(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

    }

    /**
     * Class for specifying bucket get options.
     */
    class BucketGetOptions extends AbstractOption {

        private static final long serialVersionUID = 1901844869484087395L;

        /**
         * Returns an option for bucket's metageneration mismatch. If this option is used the request
         * will fail if bucket's metageneration matches the provided value.
         */
        public static BucketGetOptions ifMetagenerationNotMatch(long metaVersion) {
            return new BucketGetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaVersion);
        }

        /**
         * Returns an option to specify the bucket's fields to be returned by the RPC call. If this
         * option is not provided all bucket's fields are returned. {@code BucketGetOption.fields}) can
         * be used to specify only the fields of interest. Bucket name is always returned, even if not
         * specified.
         */
        public static BucketGetOptions selectFields(BucketMetadataField... selectedMetadata) {
            return new BucketGetOptions(StorageRpcClient.StorageOption.FIELDS, Helper.selector(BucketMetadataField.MANDATORY_SELECTORS, selectedMetadata));
        }

        /**
         * Returns an option for bucket's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BucketGetOptions userProjectOption(String billingProject) {
            return new BucketGetOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        private BucketGetOptions(StorageRpcClient.StorageOption transportOption, long metaVersion) {
            super(transportOption, metaVersion);
        }

        private BucketGetOptions(StorageRpcClient.StorageOption transportOption, String paramObject) {
            super(transportOption, paramObject);
        }

        /**
         * Returns an option for bucket's metageneration match. If this option is used the request will
         * fail if bucket's metageneration does not match the provided value.
         */
        public static BucketGetOptions ifMetagenerationMatch(long metaVersion) {
            return new BucketGetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaVersion);
        }

    }

    /**
     * Class for specifying blob target options.
     */
    class BlobUploadOption extends AbstractOption {

        private static final long serialVersionUID = 214616862061934846L;

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobUploadOption withUserProject(String billingProject) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        static Tuple<BlobMetadata, BlobUploadOption[]> toTargetOptions(BlobMetadata blobMetadata, BlobWriteOptions... writeOptions) {
            BlobMetadata.StorageObjectBuilder objectBuilder = blobMetadata.toBlobBuilder().setCrc32c(null).setMd5(null);
            List<BlobUploadOption> destinationOptions = Lists.newArrayListWithCapacity(writeOptions.length);
            for (BlobWriteOptions writeOption : writeOptions) {
                switch(writeOption.writeOption) {
                    case IF_CRC32C_MATCH:
                        objectBuilder.setCrc32c(blobMetadata.getCrc32c());
                        break;
                    case IF_MD5_MATCH:
                        objectBuilder.setMd5(blobMetadata.getMd5());
                        break;
                    default:
                        destinationOptions.add(writeOption.asTargetOption());
                        break;
                }
            }
            return Tuple.of(objectBuilder.buildObject(), destinationOptions.toArray(new BlobUploadOption[destinationOptions.size()]));
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches.
         */
        public static BlobUploadOption ifGenerationNotMatch() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BlobUploadOption ifMetagenerationMatch() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH);
        }

        /**
         * Returns an option to set a customer-managed key for server-side encryption of the blob.
         */
        public static BlobUploadOption kmsKey(String kmsKeyId) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.KMS_KEY_NAME, kmsKeyId);
        }

        /**
         * Returns an option for blob's data disabledGzipContent. If this option is used, the request
         * will create a blob with disableGzipContent; at present, this is only for upload.
         */
        public static BlobUploadOption disableGzip() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_DISABLE_GZIP_CONTENT, true);
        }

        private BlobUploadOption(StorageRpcClient.StorageOption transportOption) {
            this(transportOption, null);
        }

        private BlobUploadOption(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match.
         */
        public static BlobUploadOption ifGenerationMatch() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKey the AES256 encoded in base64
         */
        public static BlobUploadOption customerEncryptionKey(String encryptionKey) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobUploadOption customerEncryptionKey(Key encryptionKey) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
            return new BlobUploadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobUploadOption withPredefinedAcl(PredefinedAccessControlList accessControl) {
            return new BlobUploadOption(StorageRpcClient.StorageOption.PREDEFINED_ACL, accessControl.getEntry());
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches.
         */
        public static BlobUploadOption ifMetagenerationNotMatch() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         */
        public static BlobUploadOption ifNotExists() {
            return new BlobUploadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L);
        }

    }

    /**
     * Class for specifying blob write options.
     */
    class BlobWriteOptions implements Serializable {

        private static final long serialVersionUID = -3880421670966224580L;

        private final StorageOption writeOption;

        private final Object paramObject;

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
            IF_DISABLE_GZIP_CONTENT;

            StorageRpcClient.StorageOption asRpcOption() {
                return StorageRpcClient.StorageOption.valueOf(this.name());
            }
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if generation matches.
         */
        public static BlobWriteOptions ifGenerationNotMatch() {
            return new BlobWriteOptions(StorageOption.IF_GENERATION_NOT_MATCH);
        }

        /**
         * Returns an option that signals automatic gzip compression should not be performed en route to
         * the bucket.
         */
        public static BlobWriteOptions disableGzip() {
            return new BlobWriteOptions(StorageOption.IF_DISABLE_GZIP_CONTENT, true);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobWriteOptions withUserProject(String billingProject) {
            return new BlobWriteOptions(StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option for blob's data CRC32C checksum match. If this option is used the request
         * will fail if blobs' data CRC32C checksum does not match.
         */
        public static BlobWriteOptions ifCrc32cMatch() {
            return new BlobWriteOptions(StorageOption.IF_CRC32C_MATCH, true);
        }

        /**
         * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
         *
         * @param kmsKeyId the KMS key resource id
         */
        public static BlobWriteOptions withKmsKeyName(String kmsKeyId) {
            return new BlobWriteOptions(StorageOption.KMS_KEY_NAME, kmsKeyId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(writeOption, paramObject);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if generation does not match.
         */
        public static BlobWriteOptions ifGenerationMatch() {
            return new BlobWriteOptions(StorageOption.IF_GENERATION_MATCH);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if metageneration matches.
         */
        public static BlobWriteOptions ifMetagenerationNotMatch() {
            return new BlobWriteOptions(StorageOption.IF_METAGENERATION_NOT_MATCH);
        }

        /**
         * Returns an option for blob's data MD5 hash match. If this option is used the request will
         * fail if blobs' data MD5 hash does not match.
         */
        public static BlobWriteOptions ifMd5Match() {
            return new BlobWriteOptions(StorageOption.IF_MD5_MATCH, true);
        }

        /**
         * Returns an option that causes an operation to succeed only if the target blob does not exist.
         */
        public static BlobWriteOptions ifNotExists() {
            return new BlobWriteOptions(StorageOption.IF_GENERATION_MATCH, 0L);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKey the AES256 encoded in base64
         */
        public static BlobWriteOptions withEncryptionKey(String encryptionKey) {
            return new BlobWriteOptions(StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
        }

        BlobUploadOption asTargetOption() {
            return new BlobUploadOption(this.writeOption.asRpcOption(), this.paramObject);
        }

        private BlobWriteOptions(StorageOption writeOption, Object paramObject) {
            this.writeOption = writeOption;
            this.paramObject = paramObject;
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if metageneration does not match.
         */
        public static BlobWriteOptions ifMetagenerationMatch() {
            return new BlobWriteOptions(StorageOption.IF_METAGENERATION_MATCH);
        }

        private BlobWriteOptions(StorageOption writeOption) {
            this(writeOption, null);
        }

        @Override
        public boolean equals(Object other) {
            if (null == other) {
                return false;
            }
            if (!(other instanceof BlobWriteOptions)) {
                return false;
            }
            final BlobWriteOptions that = (BlobWriteOptions) other;
            return that.writeOption == this.writeOption && Objects.equals(this.paramObject, that.paramObject);
        }

        /**
         * Returns an option for specifying blob's predefined ACL configuration.
         */
        public static BlobWriteOptions withPredefinedAcl(PredefinedAccessControlList accessControl) {
            return new BlobWriteOptions(StorageOption.PREDEFINED_ACL, accessControl.getEntry());
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobWriteOptions withEncryptionKey(Key encryptionKey) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
            return new BlobWriteOptions(StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

    }

    /**
     * Class for specifying blob source options.
     */
    class BlobReadOption extends AbstractOption {

        private static final long serialVersionUID = -3712768261070182991L;

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if blob's metageneration matches the provided value.
         */
        public static BlobReadOption ifMetagenerationNotMatch(long metaVersion) {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaVersion);
        }

        /**
         * Returns an option for blob's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BlobReadOption withUserProject(String billingProject) {
            return new BlobReadOption(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         *
         * @param encryptionKey the AES256 encoded in base64
         */
        public static BlobReadOption withDecryptionKey(String encryptionKey) {
            return new BlobReadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
         * blob.
         */
        public static BlobReadOption withDecryptionKey(Key encryptionKey) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
            return new BlobReadOption(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if blob's metageneration does not match the provided value.
         */
        public static BlobReadOption ifMetagenerationMatch(long metaVersion) {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaVersion);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if blob's generation does not match. The generation value to compare with the actual
         * blob's generation is taken from a source {@link BlobIdentifier} object. When this option is passed to
         * a {@link StorageService} method and {@link BlobIdentifier#getGeneration()} is {@code null} or no {@link
         * BlobIdentifier} is provided an exception is thrown.
         */
        public static BlobReadOption ifGenerationMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, null);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value.
         */
        public static BlobReadOption generationNotMatch(long genId) {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, genId);
        }

        private BlobReadOption(StorageRpcClient.StorageOption transportOption, Object paramObject) {
            super(transportOption, paramObject);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches. The generation value to compare with the actual
         * blob's generation is taken from a source {@link BlobIdentifier} object. When this option is passed to
         * a {@link StorageService} method and {@link BlobIdentifier#getGeneration()} is {@code null} or no {@link
         * BlobIdentifier} is provided an exception is thrown.
         */
        public static BlobReadOption generationNotMatch() {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, null);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if blob's generation does not match the provided value.
         */
        public static BlobReadOption ifGenerationMatch(long genId) {
            return new BlobReadOption(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, genId);
        }

    }

    /**
     * Class for specifying blob get options.
     */
    class BlobGetOptions extends AbstractOption {

        private static final long serialVersionUID = 803817709703661480L;

        /**
         * Returns an option to specify the blob's fields to be returned by the RPC call. If this option
         * is not provided all blob's fields are returned. {@code BlobGetOption.fields}) can be used to
         * specify only the fields of interest. Blob name and bucket are always returned, even if not
         * specified.
         */
        public static BlobGetOptions selectFields(BlobMetadataField... selectedMetadata) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.FIELDS, Helper.selector(BlobMetadataField.MANDATORY_SELECTORS, selectedMetadata));
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if blob's generation does not match the provided value.
         */
        public static BlobGetOptions ifGenerationMatch(long genId) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, genId);
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
         *
         * @param encryptionKey the AES256 encoded in base64
         */
        public static BlobGetOptions withDecryptionKey(String encryptionKey) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches. The generation value to compare with the actual
         * blob's generation is taken from a source {@link BlobIdentifier} object. When this option is passed to
         * a {@link StorageService} method and {@link BlobIdentifier#getGeneration()} is {@code null} or no {@link
         * BlobIdentifier} is provided an exception is thrown.
         */
        public static BlobGetOptions generationNotMatch() {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, (Long) null);
        }

        /**
         * Returns an option for blob's data generation mismatch. If this option is used the request
         * will fail if blob's generation matches the provided value.
         */
        public static BlobGetOptions generationNotMatch(long genId) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_GENERATION_NOT_MATCH, genId);
        }

        /**
         * Returns an option for blob's metageneration match. If this option is used the request will
         * fail if blob's metageneration does not match the provided value.
         */
        public static BlobGetOptions ifMetagenerationMatch(long metaVersion) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, metaVersion);
        }

        /**
         * Returns an option to set a customer-supplied AES256 key for server-side decryption of the
         * blob.
         */
        public static BlobGetOptions withDecryptionKey(Key encryptionKey) {
            String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
            return new BlobGetOptions(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
        }

        /**
         * Returns an option for blob's data generation match. If this option is used the request will
         * fail if blob's generation does not match. The generation value to compare with the actual
         * blob's generation is taken from a source {@link BlobIdentifier} object. When this option is passed to
         * a {@link StorageService} method and {@link BlobIdentifier#getGeneration()} is {@code null} or no {@link
         * BlobIdentifier} is provided an exception is thrown.
         */
        public static BlobGetOptions ifGenerationMatch() {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_GENERATION_MATCH, (Long) null);
        }

        /**
         * Returns an option for blob's metageneration mismatch. If this option is used the request will
         * fail if blob's metageneration matches the provided value.
         */
        public static BlobGetOptions ifMetagenerationNotMatch(long metaVersion) {
            return new BlobGetOptions(StorageRpcClient.StorageOption.IF_METAGENERATION_NOT_MATCH, metaVersion);
        }

        private BlobGetOptions(StorageRpcClient.StorageOption transportOption, Long paramObject) {
            super(transportOption, paramObject);
        }

        private BlobGetOptions(StorageRpcClient.StorageOption transportOption, String paramObject) {
            super(transportOption, paramObject);
        }

    }

    /**
     * Class for specifying bucket list options.
     */
    class BucketListOptions extends AbstractOption {

        private static final long serialVersionUID = 8754017079673290353L;

        /**
         * Returns an option to specify the bucket's fields to be returned by the RPC call. If this
         * option is not provided all bucket's fields are returned. {@code BucketListOption.fields}) can
         * be used to specify only the fields of interest. Bucket name is always returned, even if not
         * specified.
         */
        public static BucketListOptions selectFields(BucketMetadataField... selectedMetadata) {
            return new BucketListOptions(StorageRpcClient.StorageOption.FIELDS, Helper.listSelector("items", BucketMetadataField.MANDATORY_SELECTORS, selectedMetadata));
        }

        /**
         * Returns an option for bucket's billing user project. This option is only used by the buckets
         * with 'requester_pays' flag.
         */
        public static BucketListOptions withUserProject(String billingProject) {
            return new BucketListOptions(StorageRpcClient.StorageOption.USER_PROJECT, billingProject);
        }

        /**
         * Returns an option to specify the maximum number of buckets returned per page.
         */
        public static BucketListOptions withPageSize(long maxResults) {
            return new BucketListOptions(StorageRpcClient.StorageOption.MAX_RESULTS, maxResults);
        }

        private BucketListOptions(StorageRpcClient.StorageOption writeOption, Object paramObject) {
            super(writeOption, paramObject);
        }

        /**
         * Returns an option to set a prefix to filter results to buckets whose names begin with this
         * prefix.
         */
        public static BucketListOptions withPrefix(String nameStartsWith) {
            return new BucketListOptions(StorageRpcClient.StorageOption.PREFIX, nameStartsWith);
        }

        /**
         * Returns an option to specify the page token from which to start listing buckets.
         */
        public static BucketListOptions pageToken(String cursorToken) {
            return new BucketListOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, cursorToken);
        }

    }

    /**
     * Class for specifying blob list options.
     */
    class BlobListOptions extends AbstractOption {

        private static final String[] TOP_LEVEL_ATTRIBUTES = { "prefixes" };

        private static final long serialVersionUID = 9083383524788661294L;

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
         * Returns an option to specify the page token from which to start listing blobs.
         */
        public static BlobListOptions pageToken(String cursorToken) {
            return new BlobListOptions(StorageRpcClient.StorageOption.PAGE_TOKEN, cursorToken);
        }

        /**
         * If set to {@code true}, lists all versions of a blob. The default is {@code false}.
         *
         * @see <a href ="https://cloud.google.com/storage/docs/object-versioning">Object Versioning</a>
         */
        public static BlobListOptions includeVersions(boolean includeRevisions) {
            return new BlobListOptions(StorageRpcClient.StorageOption.VERSIONS, includeRevisions);
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
         * If specified, results are returned in a directory-like mode. Blobs whose names, after a
         * possible {@link #withPrefix(String)}, do not contain the '/' delimiter are returned as is. Blobs
         * whose names, after a possible {@link #withPrefix(String)}, contain the '/' delimiter, will have
         * their name truncated after the delimiter and will be returned as {@link CloudStorageObject} objects where
         * only {@link CloudStorageObject#getBlobId()}, {@link CloudStorageObject#getSize()} and {@link CloudStorageObject#isDirectory()} are set.
         * For such directory blobs, ({@link BlobIdentifier#getGeneration()} returns {@code null}), {@link
         * CloudStorageObject#getSize()} returns {@code 0} while {@link CloudStorageObject#isDirectory()} returns {@code true}.
         * Duplicate directory blobs are omitted.
         */
        public static BlobListOptions useCurrentDirectory() {
            return new BlobListOptions(StorageRpcClient.StorageOption.DELIMITER, true);
        }

        /**
         * Returns an option to specify the blob's fields to be returned by the RPC call. If this option
         * is not provided all blob's fields are returned. {@code BlobListOption.fields}) can be used to
         * specify only the fields of interest. Blob name and bucket are always returned, even if not
         * specified.
         */
        public static BlobListOptions selectFields(BlobMetadataField... selectedMetadata) {
            return new BlobListOptions(StorageRpcClient.StorageOption.FIELDS, Helper.listSelector(TOP_LEVEL_ATTRIBUTES, "items", BlobMetadataField.MANDATORY_SELECTORS, selectedMetadata));
        }

        /**
         * Returns an option to set a prefix to filter results to blobs whose names begin with this
         * prefix.
         */
        public static BlobListOptions withPrefix(String nameStartsWith) {
            return new BlobListOptions(StorageRpcClient.StorageOption.PREFIX, nameStartsWith);
        }

        /**
         * Returns an option to specify the maximum number of blobs returned per page.
         */
        public static BlobListOptions withPageSize(long maxResults) {
            return new BlobListOptions(StorageRpcClient.StorageOption.MAX_RESULTS, maxResults);
        }

        private BlobListOptions(StorageRpcClient.StorageOption writeOption, Object paramObject) {
            super(writeOption, paramObject);
        }

    }

    /**
     * Class for specifying signed URL options.
     */
    class UrlSigningOption implements Serializable {

        private static final long serialVersionUID = 7850569877451099267L;

        private final HttpRequestOption writeOption;

        private final Object paramObject;

        enum HttpRequestOption {

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

        /**
         * Provides a service account signer to sign the URL. If not provided an attempt will be made to
         * get it from the environment.
         *
         * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
         *     Accounts</a>
         */
        public static UrlSigningOption signUsing(ServiceAccountSigner signingAccount) {
            return new UrlSigningOption(HttpRequestOption.SERVICE_ACCOUNT_CRED, signingAccount);
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
        public static UrlSigningOption withBucketBoundHostname(String bucketHost) {
            return withBucketBoundHostname(bucketHost, UriSchemeType.HTTP);
        }

        /**
         * Generate a path-style URL, which places the bucket name in the path portion of the URL
         * instead of in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. Note that this
         * cannot be used alongside {@code withVirtualHostedStyle()}. Virtual hosted-style URLs, which
         * can be used via the {@code withVirtualHostedStyle()} method, should generally be preferred
         * instead of path-style URLs.
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
         */
        public static UrlSigningOption usePathStyle() {
            return new UrlSigningOption(HttpRequestOption.PATH_STYLE, "");
        }

        private UrlSigningOption(HttpRequestOption writeOption, Object paramObject) {
            this.writeOption = writeOption;
            this.paramObject = paramObject;
        }

        /**
         * Use it if signature should include the blob's md5. When used, users of the signed URL should
         * include the blob's md5 with their request.
         */
        public static UrlSigningOption includeMd5() {
            return new UrlSigningOption(HttpRequestOption.MD5, true);
        }

        /**
         * Use a virtual hosted-style hostname, which adds the bucket into the host portion of the URI
         * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name
         * will be obtained from the resource passed in. For V4 signing, this also sets the "host"
         * header in the canonicalized extension headers to the virtual hosted-style host, unless that
         * header is supplied via the {@code withExtHeaders()} method.
         *
         * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
         */
        public static UrlSigningOption useVirtualHostedStyle() {
            return new UrlSigningOption(HttpRequestOption.VIRTUAL_HOSTED_STYLE, "");
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
        public static UrlSigningOption includeQueryParams(Map<String, String> queryParameters) {
            return new UrlSigningOption(HttpRequestOption.QUERY_PARAMS, queryParameters);
        }

        /**
         * Use a different host name than the default host name 'storage.googleapis.com'. This option is
         * particularly useful for developers to point requests to an alternate endpoint (e.g. a staging
         * environment or sending requests through VPC). Note that if using this with the {@code
         * withVirtualHostedStyle()} method, you should omit the bucket name from the hostname, as it
         * automatically gets prepended to the hostname for virtual hosted-style URLs.
         */
        public static UrlSigningOption setHostName(String domainName) {
            return new UrlSigningOption(HttpRequestOption.HOST_NAME, domainName);
        }

        /**
         * The HTTP method to be used with the signed URL. If this method is not called, defaults to
         * GET.
         */
        public static UrlSigningOption httpVerb(HttpRequestMethod requestMethod) {
            return new UrlSigningOption(HttpRequestOption.HTTP_METHOD, requestMethod);
        }

        /**
         * Use if signature version should be V4. Note that V4 Signed URLs can't have an expiration
         * longer than 7 days. V2 will be the default if neither this or {@code withV2Signature()} is
         * called.
         */
        public static UrlSigningOption useV4Signature() {
            return new UrlSigningOption(HttpRequestOption.SIGNATURE_VERSION, SigningVersion.V4);
        }

        /**
         * Use if signature version should be V2. This is the default if neither this or {@code
         * withV4Signature()} is called.
         */
        public static UrlSigningOption useV2Signature() {
            return new UrlSigningOption(HttpRequestOption.SIGNATURE_VERSION, SigningVersion.V2);
        }

        HttpRequestOption getOption() {
            return writeOption;
        }

        /**
         * Use it if signature should include the blob's content-type. When used, users of the signed
         * URL should include the blob's content-type with their request. If using this URL from a
         * browser, you must include a content type that matches what the browser will send.
         */
        public static UrlSigningOption withContentTypeHeader() {
            return new UrlSigningOption(HttpRequestOption.CONTENT_TYPE, true);
        }

        /**
         * Use it if signature should include the blob's canonicalized extended headers. When used,
         * users of the signed URL should include the canonicalized extended headers with their request.
         *
         * @see <a href="https://cloud.google.com/storage/docs/xml-api/reference-headers">Request
         *     Headers</a>
         */
        public static UrlSigningOption withExtraHeaders(Map<String, String> extraHeadersMap) {
            return new UrlSigningOption(HttpRequestOption.EXT_HEADERS, extraHeadersMap);
        }

        Object getValue() {
            return paramObject;
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
        public static UrlSigningOption withBucketBoundHostname(String bucketHost, UriSchemeType uriProtocol) {
            return new UrlSigningOption(HttpRequestOption.BUCKET_BOUND_HOST_NAME, uriProtocol.getScheme() + "://" + bucketHost);
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

        private final List<SourceBlobMetadata> sourceObjects;

        private final BlobMetadata destination;

        private final List<BlobUploadOption> destinationOptions;

        /**
         * Class for Compose source blobs.
         */
        public static class SourceBlobMetadata implements Serializable {

            private static final long serialVersionUID = 4094962795951990439L;

            final String blobId;

            final Long genId;

            public String getName() {
                return blobId;
            }

            public Long getGeneration() {
                return genId;
            }

            SourceBlobMetadata(String blobId) {
                this(blobId, null);
            }

            SourceBlobMetadata(String blobId, Long genId) {
                this.blobId = blobId;
                this.genId = genId;
            }

        }

        public static class BlobTransferBuilder {

            private final List<SourceBlobMetadata> sourceObjects = new LinkedList<>();

            private final Set<BlobUploadOption> destinationOptions = new LinkedHashSet<>();

            private BlobMetadata destination;

            /**
             * Creates a {@code ComposeRequest} object.
             */
            public ComposeBlobsRequest buildComposeRequest() {
                checkArgument(!sourceObjects.isEmpty());
                checkNotNull(destination);
                return new ComposeBlobsRequest(this);
            }

            /**
             * Sets compose operation's target blob options.
             */
            public BlobTransferBuilder setTargetOptions(Iterable<BlobUploadOption> writeOptions) {
                Iterables.addAll(destinationOptions, writeOptions);
                return this;
            }

            /**
             * Sets compose operation's target blob.
             */
            public BlobTransferBuilder setTarget(BlobMetadata destination) {
                this.destination = destination;
                return this;
            }

            /**
             * Add source blobs for compose operation.
             */
            public BlobTransferBuilder addSources(Iterable<String> sourceNames) {
                for (String sourceName : sourceNames) {
                    sourceObjects.add(new SourceBlobMetadata(sourceName));
                }
                return this;
            }

            /**
             * Sets compose operation's target blob options.
             */
            public BlobTransferBuilder setTargetOptions(BlobUploadOption... writeOptions) {
                Collections.addAll(destinationOptions, writeOptions);
                return this;
            }

            /**
             * Add a source with a specific generation to match.
             */
            public BlobTransferBuilder addSources(String sourceName, long genId) {
                sourceObjects.add(new SourceBlobMetadata(sourceName, genId));
                return this;
            }

            /**
             * Add source blobs for compose operation.
             */
            public BlobTransferBuilder addSources(String... sourceNames) {
                return addSources(Arrays.asList(sourceNames));
            }

        }

        /**
         * Returns compose operation's target blob's options.
         */
        public List<BlobUploadOption> getTargetOptions() {
            return destinationOptions;
        }

        /**
         * Creates a {@code ComposeRequest} object.
         *
         * @param containerName name of the bucket where the compose operation takes place
         * @param sourceList source blobs names
         * @param destination target blob name
         */
        public static ComposeBlobsRequest of(String containerName, Iterable<String> sourceList, String destination) {
            return of(sourceList, BlobMetadata.newBuilder(BlobIdentifier.create(containerName, destination)).buildObject());
        }

        /**
         * Creates a {@code ComposeRequest} object.
         *
         * @param sourceList source blobs names
         * @param destination target blob
         */
        public static ComposeBlobsRequest of(Iterable<String> sourceList, BlobMetadata destination) {
            return newBlobTransferBuilder().setTarget(destination).addSources(sourceList).buildComposeRequest();
        }

        /**
         * Returns compose operation's target blob.
         */
        public BlobMetadata getTarget() {
            return destination;
        }

        /**
         * Returns a {@code ComposeRequest} builder.
         */
        public static BlobTransferBuilder newBlobTransferBuilder() {
            return new BlobTransferBuilder();
        }

        /**
         * Returns compose operation's source blobs.
         */
        public List<SourceBlobMetadata> getSourceBlobs() {
            return sourceObjects;
        }

        private ComposeBlobsRequest(BlobTransferBuilder transferCreator) {
            sourceObjects = ImmutableList.copyOf(transferCreator.sourceObjects);
            destination = transferCreator.destination;
            destinationOptions = ImmutableList.copyOf(transferCreator.destinationOptions);
        }

    }

    /**
     * A class to contain all information needed for a Google Cloud Storage Copy operation.
     */
    class CopyOperationRequest implements Serializable {

        private static final long serialVersionUID = -4498650529476219937L;

        private final BlobIdentifier srcIdentifier;

        private final List<BlobReadOption> readOptsList;

        private final boolean overwriteFlag;

        private final BlobMetadata destination;

        private final List<BlobUploadOption> destinationOptions;

        private final Long chunkSizeMegabytes;

        public static class CopyOperationBuilder {

            private final Set<BlobReadOption> readOptsList = new LinkedHashSet<>();

            private final Set<BlobUploadOption> destinationOptions = new LinkedHashSet<>();

            private BlobIdentifier srcIdentifier;

            private boolean overwriteFlag;

            private BlobMetadata destination;

            private Long chunkSizeMegabytes;

            /**
             * Sets the maximum number of megabytes to copy for each RPC call. This parameter is ignored
             * if source and target blob share the same location and storage class as copy is made with
             * one single RPC.
             *
             * @return the builder
             */
            public CopyOperationBuilder setMegabytesCopiedPerChunk(Long chunkSizeMegabytes) {
                this.chunkSizeMegabytes = chunkSizeMegabytes;
                return this;
            }

            /**
             * Creates a {@code CopyRequest} object.
             */
            public CopyOperationRequest buildCopyRequest() {
                return new CopyOperationRequest(this);
            }

            /**
             * Sets the copy target. Target blob information is copied from source, except for those
             * options specified in {@code options}.
             *
             * @return the builder
             */
            public CopyOperationBuilder setTarget(BlobIdentifier destId, BlobUploadOption... writeOptions) {
                this.overwriteFlag = false;
                this.destination = BlobMetadata.newBuilder(destId).buildObject();
                Collections.addAll(destinationOptions, writeOptions);
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
            public CopyOperationBuilder setTarget(BlobMetadata destination, Iterable<BlobUploadOption> writeOptions) {
                this.overwriteFlag = true;
                this.destination = checkNotNull(destination);
                Iterables.addAll(destinationOptions, writeOptions);
                return this;
            }

            /**
             * Sets blob's source options.
             *
             * @return the builder
             */
            public CopyOperationBuilder setSourceOptions(BlobReadOption... writeOptions) {
                Collections.addAll(readOptsList, writeOptions);
                return this;
            }

            /**
             * Sets the copy target and target options. Target blob information is copied from source,
             * except for those options specified in {@code options}.
             *
             * @return the builder
             */
            public CopyOperationBuilder setTarget(BlobIdentifier destId, Iterable<BlobUploadOption> writeOptions) {
                this.overwriteFlag = false;
                this.destination = BlobMetadata.newBuilder(destId).buildObject();
                Iterables.addAll(destinationOptions, writeOptions);
                return this;
            }

            /**
             * Sets the blob to copy given bucket and blob name.
             *
             * @return the builder
             */
            public CopyOperationBuilder setSource(String containerName, String sourceName) {
                this.srcIdentifier = BlobIdentifier.create(containerName, sourceName);
                return this;
            }

            /**
             * Sets blob's source options.
             *
             * @return the builder
             */
            public CopyOperationBuilder setSourceOptions(Iterable<BlobReadOption> writeOptions) {
                Iterables.addAll(readOptsList, writeOptions);
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
            public CopyOperationBuilder setTarget(BlobMetadata destination, BlobUploadOption... writeOptions) {
                this.overwriteFlag = true;
                this.destination = checkNotNull(destination);
                Collections.addAll(destinationOptions, writeOptions);
                return this;
            }

            /**
             * Sets the copy target. Target blob information is copied from source.
             *
             * @return the builder
             */
            public CopyOperationBuilder setTarget(BlobIdentifier destId) {
                this.overwriteFlag = false;
                this.destination = BlobMetadata.newBuilder(destId).buildObject();
                return this;
            }

            /**
             * Sets the blob to copy given a {@link BlobIdentifier}.
             *
             * @return the builder
             */
            public CopyOperationBuilder setSource(BlobIdentifier srcIdentifier) {
                this.srcIdentifier = srcIdentifier;
                return this;
            }

        }

        /**
         * Creates a builder for {@code CopyRequest} objects.
         */
        public static CopyOperationBuilder newCopyBuilder() {
            return new CopyOperationBuilder();
        }

        /**
         * Returns the maximum number of megabytes to copy for each RPC call. This parameter is ignored
         * if source and target blob share the same location and storage class as copy is made with one
         * single RPC.
         */
        public Long getMegabytesCopiedPerChunk() {
            return chunkSizeMegabytes;
        }

        /**
         * Creates a copy request. Target blob information is copied from source.
         *
         * @param originBlobIdentifier a {@code BlobId} object for the source blob
         * @param destinationBlobName name of the target blob, in the same bucket of the source blob
         * @return a copy request
         */
        public static CopyOperationRequest from(BlobIdentifier originBlobIdentifier, String destinationBlobName) {
            return CopyOperationRequest.newCopyBuilder().setSource(originBlobIdentifier).setTarget(BlobIdentifier.create(originBlobIdentifier.getBucket(), destinationBlobName)).buildCopyRequest();
        }

        /**
         * Creates a copy request. Target blob information is copied from source.
         *
         * @param originContainer name of the bucket containing both the source and the target blob
         * @param originObject name of the source blob
         * @param destinationBlobName name of the target blob
         * @return a copy request
         */
        public static CopyOperationRequest from(String originContainer, String originObject, String destinationBlobName) {
            return CopyOperationRequest.newCopyBuilder().setSource(originContainer, originObject).setTarget(BlobIdentifier.create(originContainer, destinationBlobName)).buildCopyRequest();
        }

        /**
         * Creates a copy request. Target blob information is copied from source.
         *
         * @param originContainer name of the bucket containing the source blob
         * @param originObject name of the source blob
         * @param destination a {@code BlobId} object for the target blob
         * @return a copy request
         */
        public static CopyOperationRequest from(String originContainer, String originObject, BlobIdentifier destination) {
            return newCopyBuilder().setSource(originContainer, originObject).setTarget(destination).buildCopyRequest();
        }

        /**
         * Returns blob's source options.
         */
        public List<BlobReadOption> getSourceOptions() {
            return readOptsList;
        }

        /**
         * Creates a copy request. Target blob information is copied from source.
         *
         * @param originBlobIdentifier a {@code BlobId} object for the source blob
         * @param destinationBlobIdentifier a {@code BlobId} object for the target blob
         * @return a copy request
         */
        public static CopyOperationRequest from(BlobIdentifier originBlobIdentifier, BlobIdentifier destinationBlobIdentifier) {
            return CopyOperationRequest.newCopyBuilder().setSource(originBlobIdentifier).setTarget(destinationBlobIdentifier).buildCopyRequest();
        }

        /**
         * Creates a copy request. {@code target} parameter is used to replace source blob information
         * (e.g. {@code contentType}, {@code contentLanguage}). Target blob information is set exactly
         * to {@code target}, no information is inherited from the source blob.
         *
         * @param originBlobIdentifier a {@code BlobId} object for the source blob
         * @param destination a {@code BlobInfo} object for the target blob
         * @return a copy request
         */
        public static CopyOperationRequest from(BlobIdentifier originBlobIdentifier, BlobMetadata destination) {
            return newCopyBuilder().setSource(originBlobIdentifier).setTarget(destination).buildCopyRequest();
        }

        /**
         * Returns the {@link BlobMetadata} for the target blob.
         */
        public BlobMetadata getTarget() {
            return destination;
        }

        private CopyOperationRequest(CopyOperationBuilder transferCreator) {
            srcIdentifier = checkNotNull(transferCreator.srcIdentifier);
            readOptsList = ImmutableList.copyOf(transferCreator.readOptsList);
            overwriteFlag = transferCreator.overwriteFlag;
            destination = checkNotNull(transferCreator.destination);
            destinationOptions = ImmutableList.copyOf(transferCreator.destinationOptions);
            chunkSizeMegabytes = transferCreator.chunkSizeMegabytes;
        }

        /**
         * Returns blob's target options.
         */
        public List<BlobUploadOption> getTargetOptions() {
            return destinationOptions;
        }

        /**
         * Returns whether to override the target blob information with {@link #getTarget()}. If {@code
         * true}, the value of {@link #getTarget()} is used to replace source blob information (e.g.
         * {@code contentType}, {@code contentLanguage}). Target blob information is set exactly to this
         * value, no information is inherited from the source blob. If {@code false}, target blob
         * information is inherited from the source blob.
         */
        public boolean getOverrideInfo() {
            return overwriteFlag;
        }

        /**
         * Creates a copy request. {@code target} parameter is used to override source blob information
         * (e.g. {@code contentType}, {@code contentLanguage}).
         *
         * @param originContainer name of the bucket containing the source blob
         * @param originObject name of the source blob
         * @param destination a {@code BlobInfo} object for the target blob
         * @return a copy request
         */
        public static CopyOperationRequest from(String originContainer, String originObject, BlobMetadata destination) {
            return newCopyBuilder().setSource(originContainer, originObject).setTarget(destination).buildCopyRequest();
        }

        /**
         * Returns the blob to copy, as a {@link BlobIdentifier}.
         */
        public BlobIdentifier getSource() {
            return srcIdentifier;
        }

    }

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
     * @return a [@code Blob} with complete information
     * @throws StorageServiceException upon failure
     */
    CloudStorageObject create(BlobMetadata blobInfo, BlobUploadOption... options);

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
    boolean delete(BlobIdentifier blob);

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
     * Returns a channel for reading the blob's content. If {@code blob.generation()} is set data
     * corresponding to that generation is read. If {@code blob.generation()} is {@code null} the
     * blob's latest generation is read. If the blob changes while reading (i.e. {@link
     * BlobMetadata#getEtag()} changes), subsequent calls to {@code blobReadChannel.read(ByteBuffer)} may
     * throw {@link StorageServiceException}.
     *
     * <p>The {@link BlobReadOption#ifGenerationMatch()} and {@link
     * BlobReadOption#ifGenerationMatch(long)} options can be used to ensure that {@code
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
    ReadChannel reader(BlobIdentifier blob, BlobReadOption... options);

    /**
     * @see #listAcls(String, BucketRequestOption...)
     */
    List<AclEntry> listAcls(String bucket);

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
    CloudStorageObject get(String bucket, String blob, BlobGetOptions... options);

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
     * @param containerName the name of the bucket to list ACLs for
     * @param writeOptions any number of BucketSourceOptions to apply to this operation
     * @throws StorageServiceException upon failure
     */
    List<AclEntry> listAcls(String containerName, BucketRequestOption... writeOptions);

    /**
     * @see #createAcl(String, AclEntry, BucketRequestOption...)
     */
    AclEntry createAcl(String bucket, AclEntry acl);

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
     * @param containerName name of the bucket where the getAcl operation takes place
     * @param principal ACL entity to fetch
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    AclEntry getAcl(String containerName, AbstractEntity principal, BucketRequestOption... writeOptions);

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
    List<Boolean> delete(Iterable<BlobIdentifier> objectIds);

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
    CloudStorageObject compose(ComposeBlobsRequest composeRequest);

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
    void deleteHmacKey(HmacSecretKey.HmacKeyInfo hmacKeyMetadata, HmacKeyDeleteOption... options);

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
     * Updates blob information. Original metadata are merged with metadata in the provided {@code
     * blobInfo}. If the original metadata already contains a key specified in the provided {@code
     * blobInfo's} metadata map, it will be replaced by the new value. Removing metadata can be done
     * by setting that metadata's value to {@code null}.
     *
     * <p>Example of adding new metadata values or updating existing ones.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * Map<String, String> newMetadata = new HashMap<>();
     * newMetadata.put("keyToAddOrUpdate", "value");
     * Blob blob = storage.update(BlobInfo.newBuilder(bucketName, blobName)
     *     .setMetadata(newMetadata)
     *     .build());
     * }</pre>
     *
     * <p>Example of removing metadata values.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * Map<String, String> newMetadata = new HashMap<>();
     * newMetadata.put("keyToRemove", null);
     * Blob blob = storage.update(BlobInfo.newBuilder(bucketName, blobName)
     *     .setMetadata(newMetadata)
     *     .build());
     * }</pre>
     *
     * @return the updated blob
     * @throws StorageServiceException upon failure
     */
    CloudStorageObject update(BlobMetadata blobInfo);

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
    AclEntry getDefaultAcl(String bucket, AbstractEntity entity);

    /**
     * Updates the IAM policy on the specified bucket.
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
     * @param containerName name of the bucket where the setIamPolicy operation takes place
     * @param accessRules policy to be set on the specified bucket
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    Policy setIamPolicy(String containerName, Policy accessRules, BucketRequestOption... writeOptions);

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
    AclEntry getAcl(BlobIdentifier blob, AbstractEntity entity);

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
     * @param containerName name of the bucket where the updateAcl operation takes place
     * @param accessControl ACL to update
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    AclEntry updateAcl(String containerName, AclEntry accessControl, BucketRequestOption... writeOptions);

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
     * Updates the requested blobs. A batch request is used to perform this call. Original metadata
     * are merged with metadata in the provided {@code BlobInfo} objects. To replace metadata instead
     * you first have to unset them. Unsetting metadata can be done by setting the provided {@code
     * BlobInfo} objects metadata to {@code null}. See {@link #update(BlobMetadata)} for a code example.
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
     * @param metadataList blobs to update
     * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
     *     been denied the corresponding item in the list is {@code null}.
     * @throws StorageServiceException upon failure
     */
    List<CloudStorageObject> update(Iterable<BlobMetadata> metadataList);

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
    boolean deleteAcl(BlobIdentifier blob, AbstractEntity entity);

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
    List<CloudStorageObject> get(Iterable<BlobIdentifier> objectIds);

    /**
     * Creates a blob and return a channel for writing its content. By default any md5 and crc32c
     * values in the given {@code blobInfo} are ignored unless requested via the {@code
     * BlobWriteOption.md5Match} and {@code BlobWriteOption.crc32cMatch} options.
     *
     * <p>Example of writing a blob's content through a writer.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * byte[] content = "Hello, World!".getBytes(UTF_8);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * try (WriteChannel writer = storage.writer(blobInfo)) {
     *   try {
     *     writer.write(ByteBuffer.wrap(content, 0, content.length));
     *   } catch (Exception ex) {
     *     // handle exception
     *   }
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    WriteChannel writer(BlobMetadata blobInfo, BlobWriteOptions... options);

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
     * @param containerName name of the bucket to delete an ACL from
     * @param principal ACL entity to delete
     * @param writeOptions extra parameters to apply to this operation
     * @return {@code true} if the ACL was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean deleteAcl(String containerName, AbstractEntity principal, BucketRequestOption... writeOptions);

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
     *         bucket,
     *         ImmutableList.of("storage.buckets.get", "storage.buckets.getIamPolicy"));
     * for (boolean hasPermission : response) {
     *   // Do something with permission test response
     * }
     * }</pre>
     *
     * @param containerName name of the bucket where the testIamPermissions operation takes place
     * @param requestedActions list of permissions to test on the bucket
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    List<Boolean> testIamPermissions(String containerName, List<String> requestedActions, BucketRequestOption... writeOptions);

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
    HmacSecretKey.HmacKeyInfo updateHmacKeyState(final HmacSecretKey.HmacKeyInfo hmacKeyMetadata, final HmacSecretKey.HmacKeyStatus state, HmacKeyUpdateOption... options);

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
     * @param containerName name of the bucket for which an ACL should be created
     * @param accessControl ACL to create
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    AclEntry createAcl(String containerName, AclEntry accessControl, BucketRequestOption... writeOptions);

    /**
     * Gets the IAM policy for the provided bucket.
     *
     * <p>Example of getting the IAM policy for a bucket.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * Policy policy = storage.getIamPolicy(bucketName);
     * }</pre>
     *
     * @param containerName name of the bucket where the getIamPolicy operation takes place
     * @param writeOptions extra parameters to apply to this operation
     * @throws StorageServiceException upon failure
     */
    Policy getIamPolicy(String containerName, BucketRequestOption... writeOptions);

    /**
     * Updates blob information. Original metadata are merged with metadata in the provided {@code
     * blobInfo}. To replace metadata instead you first have to unset them. Unsetting metadata can be
     * done by setting the provided {@code blobInfo}'s metadata to {@code null}. Accepts an optional
     * userProject {@link BlobUploadOption} option which defines the project id to assign operational
     * costs.
     *
     * <p>Example of udating a blob, only if the blob's metageneration matches a value, otherwise a
     * {@link StorageServiceException} is thrown.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * Blob blob = storage.get(bucketName, blobName);
     * BlobInfo updatedInfo = blob.toBuilder().setContentType("text/plain").build();
     * storage.update(updatedInfo, BlobTargetOption.metagenerationMatch());
     * }</pre>
     *
     * @return the updated blob
     * @throws StorageServiceException upon failure
     */
    CloudStorageObject update(BlobMetadata blobInfo, BlobUploadOption... options);

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
    HmacSecretKey.HmacKeyInfo getHmacKey(String accessId, GetHmacKeyRequestOption... options);

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
    Page<CloudStorageObject> list(String bucket, BlobListOptions... options);

    /**
     * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
     * #writer} is recommended as it uses resumable upload. MD5 and CRC32C hashes of {@code content}
     * are computed and used for validating transferred data. Accepts an optional userProject {@link
     * BlobGetOptions} option which defines the project id to assign operational costs.
     *
     * <p>Example of creating a blob from a byte array.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8));
     * }</pre>
     *
     * @return a [@code Blob} with complete information
     * @throws StorageServiceException upon failure
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
     */
    CloudStorageObject create(BlobMetadata blobInfo, byte[] content, BlobUploadOption... options);

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
    Page<HmacSecretKey.HmacKeyInfo> listHmacKeys(HmacKeyListOption... writeOptions);

    /**
     * @see #deleteAcl(String, AbstractEntity, BucketRequestOption...)
     */
    boolean deleteAcl(String bucket, AbstractEntity entity);

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
    AclEntry updateAcl(BlobIdentifier blob, AclEntry acl);

    /**
     * Deletes the requested blob.
     *
     * <p>Accepts an optional userProject {@link BlobReadOption} option which defines the project id
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
    boolean delete(BlobIdentifier blob, BlobReadOption... options);

    /**
     * @see #updateAcl(String, AclEntry, BucketRequestOption...)
     */
    AclEntry updateAcl(String bucket, AclEntry acl);

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
    AclEntry createDefaultAcl(String bucket, AclEntry acl);

    /**
     * Generates a signed URL for a blob. If you have a blob that you want to allow access to for a
     * fixed amount of time, you can use this method to generate a URL that is only valid within a
     * certain time period. This is particularly useful if you don't want publicly accessible blobs,
     * but also don't want to require users to explicitly log in. Signing a URL requires a service
     * account signer. If an instance of {@link ServiceAccountSigner} was passed to
     * {@link StorageSettings}' builder via {@code setCredentials(Credentials)} or the default
     * credentials are being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS}
     * is set or your application is running in App Engine, then {@code signUrl} will use that
     * credentials to sign the URL. If the credentials passed to {@link StorageSettings} do not
     * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
     * credentials) then {@code signUrl} will throw an {@link IllegalStateException} unless an
     * implementation of {@link ServiceAccountSigner} is passed using the {@link
     * UrlSigningOption#signUsing(ServiceAccountSigner)} option.
     *
     * <p>A service account signer is looked for in the following order:
     *
     * <ol>
     *   <li>The signer passed with the option {@link UrlSigningOption#signUsing(ServiceAccountSigner)}
     *   <li>The credentials passed to {@link StorageSettings}
     *   <li>The default credentials, if no credentials were passed to {@link StorageSettings}
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
     * <p>Example of creating a signed URL passing the {@link UrlSigningOption#useVirtualHostedStyle()}
     * option, which specifies the bucket name in the hostname of the URI, rather than in the path:
     *
     * <pre>{@code
     * URL signedUrl = storage.signUrl(
     *     BlobInfo.newBuilder(bucketName, blobName).build(),
     *     1, TimeUnit.DAYS,
     *     Storage.SignUrlOption.withVirtualHostedStyle());
     * }</pre>
     *
     * <p>Example of creating a signed URL passing the {@link UrlSigningOption#usePathStyle()} option,
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
     * UrlSigningOption#signUsing(ServiceAccountSigner)} option, that will be used for signing the URL:
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
     * @param expiry time until the signed URL expires, expressed in {@code unit}. The finest
     *     granularity supported is 1 second, finer granularities will be truncated
     * @param timeScale time unit of the {@code duration} parameter
     * @param writeOptions optional URL signing options
     * @throws IllegalStateException if {@link UrlSigningOption#signUsing(ServiceAccountSigner)} was not
     *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
     *     StorageSettings}
     * @throws IllegalArgumentException if {@code SignUrlOption.withMd5()} option is used and {@code
     *     blobInfo.md5()} is {@code null}
     * @throws IllegalArgumentException if {@code SignUrlOption.withContentType()} option is used and
     *     {@code blobInfo.contentType()} is {@code null}
     * @throws SigningException if the attempt to sign the URL failed
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
     */
    URL signUrl(BlobMetadata blobMetadata, long expiry, TimeUnit timeScale, UrlSigningOption... writeOptions);

    /**
     * Deletes the requested bucket.
     *
     * <p>Accepts an optional userProject {@link BucketRequestOption} option which defines the project
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
    boolean delete(String bucket, BucketRequestOption... options);

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
    List<AclEntry> listAcls(BlobIdentifier blob);

    /**
     * Updates the requested blobs. A batch request is used to perform this call. Original metadata
     * are merged with metadata in the provided {@code BlobInfo} objects. To replace metadata instead
     * you first have to unset them. Unsetting metadata can be done by setting the provided {@code
     * BlobInfo} objects metadata to {@code null}. See {@link #update(BlobMetadata)} for a code example.
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
     * @param metadataList blobs to update
     * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
     *     been denied the corresponding item in the list is {@code null}.
     * @throws StorageServiceException upon failure
     */
    List<CloudStorageObject> update(BlobMetadata... metadataList);

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
    List<Boolean> delete(BlobIdentifier... objectIds);

    /**
     * @see #getAcl(String, AbstractEntity, BucketRequestOption...)
     */
    AclEntry getAcl(String bucket, AbstractEntity entity);

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
    List<CloudStorageObject> get(BlobIdentifier... objectIds);

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
    ReadChannel reader(String bucket, String blob, BlobReadOption... options);

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
    AclEntry createAcl(BlobIdentifier blob, AclEntry acl);

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
    AclEntry updateDefaultAcl(String bucket, AclEntry acl);

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
    byte[] readAllBytes(String bucket, String blob, BlobReadOption... options);

    /**
     * Sends a copy request. This method copies both blob's data and information. To override source
     * blob's information supply a {@code BlobInfo} to the {@code CopyRequest} using either {@link
     * CopyOperationRequest.CopyOperationBuilder#setTarget(BlobMetadata, BlobUploadOption...)} or {@link
     * CopyOperationRequest.CopyOperationBuilder#setTarget(BlobMetadata, Iterable)}.
     *
     * <p>This method returns a {@link ResumableCopyWriter} object for the provided {@code CopyRequest}. If
     * source and destination objects share the same location and storage class the source blob is
     * copied with one request and {@link ResumableCopyWriter#getResult()} immediately returns, regardless of
     * the {@link CopyOperationRequest#chunkSizeMegabytes} parameter. If source and destination have
     * different location or storage class {@link ResumableCopyWriter#getResult()} might issue multiple RPC
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
     * @return a {@link ResumableCopyWriter} object that can be used to get information on the newly created
     *     blob or to complete the copy if more than one RPC request is needed
     * @throws StorageServiceException upon failure
     * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
     */
    ResumableCopyWriter copy(CopyOperationRequest copyRequest);

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
    boolean delete(String bucket, String blob, BlobReadOption... options);

    /**
     * Returns the requested bucket or {@code null} if not found.
     *
     * <p>Accepts an optional userProject {@link BucketGetOptions} option which defines the project id
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
    StorageBucket get(String bucket, BucketGetOptions... options);

    /**
     * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
     * #writer} is recommended as it uses resumable upload. By default any md5 and crc32c values in
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
     * @return a [@code Blob} with complete information
     * @throws StorageServiceException upon failure
     */
    @Deprecated
    CloudStorageObject create(BlobMetadata blobInfo, InputStream content, BlobWriteOptions... options);

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
    CloudStorageObject get(BlobIdentifier blob);

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
    List<AclEntry> listDefaultAcls(String bucket);

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
    byte[] readAllBytes(BlobIdentifier blob, BlobReadOption... options);

    /**
     * Creates a new blob with the sub array of the given byte array. Direct upload is used to upload
     * {@code content}. For large content, {@link #writer} is recommended as it uses resumable upload.
     * MD5 and CRC32C hashes of {@code content} are computed and used for validating transferred data.
     * Accepts a userProject {@link BlobGetOptions} option, which defines the project id to assign
     * operational costs.
     *
     * <p>Example of creating a blob from a byte array.
     *
     * <pre>{@code
     * String bucketName = "my-unique-bucket";
     * String blobName = "my-blob-name";
     * BlobId blobId = BlobId.of(bucketName, blobName);
     * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();
     * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8), 7, 5);
     * }</pre>
     *
     * @return a [@code Blob} with complete information
     * @throws StorageServiceException upon failure
     * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
     */
    CloudStorageObject create(BlobMetadata blobInfo, byte[] content, int offset, int length, BlobUploadOption... options);

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
    CloudStorageObject get(BlobIdentifier blob, BlobGetOptions... options);

}
