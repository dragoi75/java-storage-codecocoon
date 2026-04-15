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
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.HmacKey.HmacKeyMetadata;
import com.google.cloud.storage.spi.v1.StorageRpc;
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
 * An interface for Google Cloud StorageClient.
 *
 * @see <a href="https://cloud.google.com/storage/docs">Google Cloud StorageClient</a>
 */
public interface StorageClient extends Service<StorageOptions> {

  enum PredefinedAccessControlList {
    AUTHENTICATED_READ("authenticatedRead"),
    ALL_AUTHENTICATED_USERS("allAuthenticatedUsers"),
    PRIVATE("private"),
    PROJECT_PRIVATE("projectPrivate"),
    PUBLIC_READ("publicRead"),
    PUBLIC_READ_WRITE("publicReadWrite"),
    BUCKET_OWNER_READ("bucketOwnerRead"),
    BUCKET_OWNER_FULL_CONTROL("bucketOwnerFullControl");

    private final String aclRecord;

    PredefinedAccessControlList(String aclRecord) {
      this.aclRecord = aclRecord;
    }

    String getEntry() {
      return aclRecord;
    }
  }

  enum BucketProperty implements FieldSelector {
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

    static final List<? extends FieldSelector> MANDATORY_FIELDS = ImmutableList.of(NAME);

    private final String fieldKey;

    BucketProperty(String fieldKey) {
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
    KMS_KEY_NAME("withKmsKeyName"),
    EVENT_BASED_HOLD("eventBasedHold"),
    TEMPORARY_HOLD("temporaryHold"),
    RETENTION_EXPIRATION_TIME("retentionExpirationTime"),
    UPDATED("updated");

    static final List<? extends FieldSelector> MANDATORY_FIELDS = ImmutableList.of(BUCKET, NAME);

    private final String fieldKey;

    BlobMetadataField(String fieldKey) {
      this.fieldKey = fieldKey;
    }

    @Override
    public String getSelector() {
      return fieldKey;
    }
  }

  /** Class for specifying bucket target options. */
  class BucketTargetOptions extends Option {

    private static final long CLASS_SERIAL_UID = -5880204616982900975L;

    private BucketTargetOptions(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    private BucketTargetOptions(StorageRpc.Option rpcSetting) {
      this(rpcSetting, null);
    }

    /** Returns an option for specifying bucket's predefined ACL configuration. */
    public static BucketTargetOptions withPredefinedAcl(PredefinedAccessControlList accessControlList) {
      return new BucketTargetOptions(StorageRpc.Option.PREDEFINED_ACL, accessControlList.getEntry());
    }

    /** Returns an option for specifying bucket's default ACL configuration for blobs. */
    public static BucketTargetOptions predefinedDefaultObjectAcl(PredefinedAccessControlList accessControlList) {
      return new BucketTargetOptions(
          StorageRpc.Option.PREDEFINED_DEFAULT_OBJECT_ACL, accessControlList.getEntry());
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BucketTargetOptions withMetagenerationMatch() {
      return new BucketTargetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static BucketTargetOptions ifMetagenerationNotMatch() {
      return new BucketTargetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     */
    public static BucketTargetOptions withUserProject(String billingProject) {
      return new BucketTargetOptions(StorageRpc.Option.USER_PROJECT, billingProject);
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
      return new BucketTargetOptions(StorageRpc.Option.PROJECTION, responseView);
    }
  }

  /** Class for specifying bucket source options. */
  class BucketSourceOptions extends Option {

    private static final long CLASS_SERIAL_UID = 5185657617120212117L;

    private BucketSourceOptions(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided value.
     */
    public static BucketSourceOptions withMetagenerationMatch(long metadataGeneration) {
      return new BucketSourceOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metadataGeneration);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided value.
     */
    public static BucketSourceOptions ifMetagenerationNotMatch(long metadataGeneration) {
      return new BucketSourceOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metadataGeneration);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketSourceOptions setUserProject(String billingProject) {
      return new BucketSourceOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }
  }

  /** Class for specifying listHmacKeys options */
  class ListHmacKeysOptions extends Option {
    private ListHmacKeysOptions(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option for the Service Account whose keys to list. If this option is not used,
     * keys for all accounts will be listed.
     */
    public static ListHmacKeysOptions serviceAccountEmail(ServiceAccount serviceAcct) {
      return new ListHmacKeysOptions(
          StorageRpc.Option.SERVICE_ACCOUNT_EMAIL, serviceAcct.getEmail());
    }

    /** Returns an option for the maximum amount from HMAC keys returned per page. */
    public static ListHmacKeysOptions setMaxResults(long maxResults) {
      return new ListHmacKeysOptions(StorageRpc.Option.MAX_RESULTS, maxResults);
    }

    /** Returns an option to specify the page token from which to start listing HMAC keys. */
    public static ListHmacKeysOptions setPageToken(String continuationToken) {
      return new ListHmacKeysOptions(StorageRpc.Option.PAGE_TOKEN, continuationToken);
    }

    /**
     * Returns an option to specify whether to show deleted keys in the result. This option is false
     * by default.
     */
    public static ListHmacKeysOptions showDeletedKeys(boolean includeDeletedKeys) {
      return new ListHmacKeysOptions(StorageRpc.Option.SHOW_DELETED_KEYS, includeDeletedKeys);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static ListHmacKeysOptions userProject(String billingProject) {
      return new ListHmacKeysOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static ListHmacKeysOptions projectId(String gcpProjectId) {
      return new ListHmacKeysOptions(StorageRpc.Option.PROJECT_ID, gcpProjectId);
    }
  }

  /** Class for specifying createHmacKey options */
  class HmacKeyCreationOption extends Option {
    private HmacKeyCreationOption(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyCreationOption userProject(String billingProject) {
      return new HmacKeyCreationOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static HmacKeyCreationOption projectId(String gcpProjectId) {
      return new HmacKeyCreationOption(StorageRpc.Option.PROJECT_ID, gcpProjectId);
    }
  }

  /** Class for specifying getHmacKey options */
  class HmacKeyRetrievalOption extends Option {
    private HmacKeyRetrievalOption(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyRetrievalOption userProject(String billingProject) {
      return new HmacKeyRetrievalOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static HmacKeyRetrievalOption projectId(String gcpProjectId) {
      return new HmacKeyRetrievalOption(StorageRpc.Option.PROJECT_ID, gcpProjectId);
    }
  }

  /** Class for specifying deleteHmacKey options */
  class DeleteHmacKeyRequestOption extends Option {
    private DeleteHmacKeyRequestOption(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static DeleteHmacKeyRequestOption userProject(String billingProject) {
      return new DeleteHmacKeyRequestOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }
  }

  /** Class for specifying updateHmacKey options */
  class HmacKeyUpdateOption extends Option {
    private HmacKeyUpdateOption(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyUpdateOption userProject(String billingProject) {
      return new HmacKeyUpdateOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }
  }

  /** Class for specifying bucket get options. */
  class BucketGetOptions extends Option {

    private static final long CLASS_SERIAL_UID = 1901844869484087395L;

    private BucketGetOptions(StorageRpc.Option rpcSetting, long metadataGeneration) {
      super(rpcSetting, metadataGeneration);
    }

    private BucketGetOptions(StorageRpc.Option rpcSetting, String payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided value.
     */
    public static BucketGetOptions ifMetagenerationMatch(long metadataGeneration) {
      return new BucketGetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metadataGeneration);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided value.
     */
    public static BucketGetOptions ifMetagenerationNotMatch(long metadataGeneration) {
      return new BucketGetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metadataGeneration);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketGetOptions userProject(String billingProject) {
      return new BucketGetOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the bucket's withFields to be returned by the RPC call. If this
     * option is not provided all bucket's withFields are returned. {@code BucketGetOptions.withFields}) can
     * be used to specify only the withFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static BucketGetOptions withFields(BucketProperty... selectedProperties) {
      return new BucketGetOptions(
          StorageRpc.Option.FIELDS, Helper.selector(BucketProperty.MANDATORY_FIELDS, selectedProperties));
    }
  }

  /** Class for specifying blob target options. */
  class BlobUploadOption extends Option {

    private static final long CLASS_SERIAL_UID = 214616862061934846L;

    private BlobUploadOption(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    private BlobUploadOption(StorageRpc.Option rpcSetting) {
      this(rpcSetting, null);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobUploadOption predefinedAclOption(PredefinedAccessControlList accessControlList) {
      return new BlobUploadOption(StorageRpc.Option.PREDEFINED_ACL, accessControlList.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobUploadOption ifNotExists() {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static BlobUploadOption ifGenerationMatch() {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static BlobUploadOption ifGenerationNotMatch() {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobUploadOption ifMetagenerationMatch() {
      return new BlobUploadOption(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobUploadOption ifMetagenerationNotMatch() {
      return new BlobUploadOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data disabledGzipContent. If this option is used, the request
     * will create a blob with disableGzipCompression; at present, this is only for upload.
     */
    public static BlobUploadOption disableGzipCompression() {
      return new BlobUploadOption(StorageRpc.Option.IF_DISABLE_GZIP_CONTENT, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobUploadOption customerSuppliedKey(Key secretMaterial) {
      String encodedSecret = BaseEncoding.base64().encode(secretMaterial.getEncoded());
      return new BlobUploadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedSecret);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobUploadOption getUserProject(String billingProject) {
      return new BlobUploadOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param secretMaterial the AES256 encoded in base64
     */
    public static BlobUploadOption customerSuppliedKey(String secretMaterial) {
      return new BlobUploadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, secretMaterial);
    }

    /** Returns an option to set a customer-managed key for server-side encryption from the blob. */
    public static BlobUploadOption withKmsKeyName(String kmsKeyResource) {
      return new BlobUploadOption(StorageRpc.Option.KMS_KEY_NAME, kmsKeyResource);
    }

    static Tuple<BlobInfo, BlobUploadOption[]> convertOptions(BlobInfo blobMetadata, WriteBlobOption... options) {
      BlobInfo.Builder metadataBuilder = blobMetadata.toBuilder().setCrc32c(null).setMd5(null);
      List<BlobUploadOption> destinationOptions = Lists.newArrayListWithCapacity(options.length);
      for (WriteBlobOption setting : options) {
        switch (setting.setting) {
          case IF_CRC32C_MATCH:
            metadataBuilder.setCrc32c(blobMetadata.getCrc32c());
            break;
          case IF_MD5_MATCH:
            metadataBuilder.setMd5(blobMetadata.getMd5());
            break;
          default:
            destinationOptions.add(setting.toBlobUploadOption());
            break;
        }
      }
      return Tuple.of(
          metadataBuilder.build(), destinationOptions.toArray(new BlobUploadOption[destinationOptions.size()]));
    }
  }

  /** Class for specifying blob write options. */
  class WriteBlobOption implements Serializable {

    private static final long CLASS_SERIAL_UID = -3880421670966224580L;

    private final StorageOption setting;
    private final Object payload;

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
      USER_PROJECT;

      StorageRpc.Option asRpcOption() {
        return StorageRpc.Option.valueOf(this.name());
      }
    }

    BlobUploadOption toBlobUploadOption() {
      return new BlobUploadOption(this.setting.asRpcOption(), this.payload);
    }

    private WriteBlobOption(StorageOption setting, Object payload) {
      this.setting = setting;
      this.payload = payload;
    }

    private WriteBlobOption(StorageOption setting) {
      this(setting, null);
    }

    @Override
    public int hashCode() {
      return Objects.hash(setting, payload);
    }

    @Override
    public boolean equals(Object candidate) {
      if (candidate == null) {
        return false;
      }
      if (!(candidate instanceof WriteBlobOption)) {
        return false;
      }
      final WriteBlobOption comparisonOption = (WriteBlobOption) candidate;
      return this.setting == comparisonOption.setting && Objects.equals(this.payload, comparisonOption.payload);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static WriteBlobOption withPredefinedAcl(PredefinedAccessControlList accessControlList) {
      return new WriteBlobOption(StorageOption.PREDEFINED_ACL, accessControlList.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static WriteBlobOption ifDoesNotExist() {
      return new WriteBlobOption(StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static WriteBlobOption ifGenerationMatch() {
      return new WriteBlobOption(StorageOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static WriteBlobOption ifGenerationNotMatch() {
      return new WriteBlobOption(StorageOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static WriteBlobOption ifMetagenerationMatch() {
      return new WriteBlobOption(StorageOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static WriteBlobOption ifMetagenerationNotMatch() {
      return new WriteBlobOption(StorageOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data MD5 hash match. If this option is used the request will
     * fail if blobs' data MD5 hash does not match.
     */
    public static WriteBlobOption ifMd5Match() {
      return new WriteBlobOption(StorageOption.IF_MD5_MATCH, true);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match.
     */
    public static WriteBlobOption ifCrc32cMatch() {
      return new WriteBlobOption(StorageOption.IF_CRC32C_MATCH, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static WriteBlobOption customerSuppliedKey(Key secretMaterial) {
      String encodedSecret = BaseEncoding.base64().encode(secretMaterial.getEncoded());
      return new WriteBlobOption(StorageOption.CUSTOMER_SUPPLIED_KEY, encodedSecret);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param secretMaterial the AES256 encoded in base64
     */
    public static WriteBlobOption customerSuppliedKey(String secretMaterial) {
      return new WriteBlobOption(StorageOption.CUSTOMER_SUPPLIED_KEY, secretMaterial);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption from the blob.
     *
     * @param kmsKeyResource the KMS key resource id
     */
    public static WriteBlobOption withKmsKeyName(String kmsKeyResource) {
      return new WriteBlobOption(StorageOption.KMS_KEY_NAME, kmsKeyResource);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static WriteBlobOption withUserProject(String billingProject) {
      return new WriteBlobOption(StorageOption.USER_PROJECT, billingProject);
    }
  }

  /** Class for specifying blob source options. */
  class BlobSourceOptions extends Option {

    private static final long CLASS_SERIAL_UID = -3712768261070182991L;

    private BlobSourceOptions(StorageRpc.Option rpcSetting, Object payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link StorageClient} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobSourceOptions ifGenerationMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided value.
     */
    public static BlobSourceOptions ifGenerationMatch(long genId) {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_MATCH, genId);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link StorageClient} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobSourceOptions generationNotMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value.
     */
    public static BlobSourceOptions ifGenerationNotMatch(long genId) {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, genId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided value.
     */
    public static BlobSourceOptions ifMetagenerationMatch(long metadataGeneration) {
      return new BlobSourceOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metadataGeneration);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided value.
     */
    public static BlobSourceOptions ifMetagenerationNotMatch(long metadataGeneration) {
      return new BlobSourceOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metadataGeneration);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobSourceOptions withDecryptionKey(Key secretMaterial) {
      String encodedSecret = BaseEncoding.base64().encode(secretMaterial.getEncoded());
      return new BlobSourceOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedSecret);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param secretMaterial the AES256 encoded in base64
     */
    public static BlobSourceOptions withDecryptionKey(String secretMaterial) {
      return new BlobSourceOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, secretMaterial);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobSourceOptions setUserProject(String billingProject) {
      return new BlobSourceOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }
  }

  /** Class for specifying blob get options. */
  class BlobGetOptions extends Option {

    private static final long CLASS_SERIAL_UID = 803817709703661480L;

    private BlobGetOptions(StorageRpc.Option rpcSetting, Long payload) {
      super(rpcSetting, payload);
    }

    private BlobGetOptions(StorageRpc.Option rpcSetting, String payload) {
      super(rpcSetting, payload);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link StorageClient} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobGetOptions ifGenerationMatch() {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_MATCH, (Long) null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided value.
     */
    public static BlobGetOptions ifGenerationMatch(long genId) {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_MATCH, genId);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link StorageClient} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobGetOptions generationNotMatch() {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, (Long) null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value.
     */
    public static BlobGetOptions ifGenerationNotMatch(long genId) {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, genId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided value.
     */
    public static BlobGetOptions ifMetagenerationMatch(long metadataGeneration) {
      return new BlobGetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metadataGeneration);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided value.
     */
    public static BlobGetOptions ifMetagenerationNotMatch(long metadataGeneration) {
      return new BlobGetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metadataGeneration);
    }

    /**
     * Returns an option to specify the blob's withFields to be returned by the RPC call. If this option
     * is not provided all blob's withFields are returned. {@code BlobGetOptions.withFields}) can be used to
     * specify only the withFields from interest. Blob name and bucket are always returned, even if not
     * specified.
     */
    public static BlobGetOptions setFields(BlobMetadataField... selectedProperties) {
      return new BlobGetOptions(
          StorageRpc.Option.FIELDS, Helper.selector(BlobMetadataField.MANDATORY_FIELDS, selectedProperties));
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobGetOptions setUserProject(String billingProject) {
      return new BlobGetOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     */
    public static BlobGetOptions withDecryptionKey(Key secretMaterial) {
      String encodedSecret = BaseEncoding.base64().encode(secretMaterial.getEncoded());
      return new BlobGetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedSecret);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     *
     * @param secretMaterial the AES256 encoded in base64
     */
    public static BlobGetOptions withDecryptionKey(String secretMaterial) {
      return new BlobGetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, secretMaterial);
    }
  }

  /** Class for specifying bucket list options. */
  class BucketListOptions extends Option {

    private static final long CLASS_SERIAL_UID = 8754017079673290353L;

    private BucketListOptions(StorageRpc.Option setting, Object payload) {
      super(setting, payload);
    }

    /** Returns an option to specify the maximum number from buckets returned per page. */
    public static BucketListOptions maxResults(long maxResults) {
      return new BucketListOptions(StorageRpc.Option.MAX_RESULTS, maxResults);
    }

    /** Returns an option to specify the page token from which to start listing buckets. */
    public static BucketListOptions pageToken(String continuationToken) {
      return new BucketListOptions(StorageRpc.Option.PAGE_TOKEN, continuationToken);
    }

    /**
     * Returns an option to set a withPrefix to filter results to buckets whose names begin with this
     * withPrefix.
     */
    public static BucketListOptions withPrefix(String prefix) {
      return new BucketListOptions(StorageRpc.Option.PREFIX, prefix);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketListOptions setUserProject(String billingProject) {
      return new BucketListOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the bucket's withFields to be returned by the RPC call. If this
     * option is not provided all bucket's withFields are returned. {@code BucketListOptions.withFields}) can
     * be used to specify only the withFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static BucketListOptions withFields(BucketProperty... selectedProperties) {
      return new BucketListOptions(
          StorageRpc.Option.FIELDS,
          Helper.listSelector("items", BucketProperty.MANDATORY_FIELDS, selectedProperties));
    }
  }

  /** Class for specifying blob list options. */
  class BlobListOptions extends Option {

    private static final String[] PRIMARY_FIELDS = {"prefixes"};
    private static final long CLASS_SERIAL_UID = 9083383524788661294L;

    private BlobListOptions(StorageRpc.Option setting, Object payload) {
      super(setting, payload);
    }

    /** Returns an option to specify the maximum number from blobs returned per page. */
    public static BlobListOptions maxResults(long maxResults) {
      return new BlobListOptions(StorageRpc.Option.MAX_RESULTS, maxResults);
    }

    /** Returns an option to specify the page token from which to start listing blobs. */
    public static BlobListOptions pageToken(String continuationToken) {
      return new BlobListOptions(StorageRpc.Option.PAGE_TOKEN, continuationToken);
    }

    /**
     * Returns an option to set a withPrefix to filter results to blobs whose names begin with this
     * withPrefix.
     */
    public static BlobListOptions withPrefix(String prefix) {
      return new BlobListOptions(StorageRpc.Option.PREFIX, prefix);
    }

    /**
     * If specified, results are returned in a directory-like mode. Blobs whose names, after a
     * possible {@link #withPrefix(String)}, do not contain the '/' delimiter are returned as is. Blobs
     * whose names, after a possible {@link #withPrefix(String)}, contain the '/' delimiter, will have
     * their name truncated after the delimiter and will be returned as {@link Blob} objects where
     * only {@link Blob#getBlobId()}, {@link Blob#getSize()} and {@link Blob#isDirectory()} are set.
     * For such directory blobs, ({@link BlobId#getGeneration()} returns {@code null}), {@link
     * Blob#getSize()} returns {@code 0} while {@link Blob#isDirectory()} returns {@code true}.
     * Duplicate directory blobs are omitted.
     */
    public static BlobListOptions currentDirectoryOnly() {
      return new BlobListOptions(StorageRpc.Option.DELIMITER, true);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     *
     * @param billingProject projectId from the billing user project.
     */
    public static BlobListOptions withUserProject(String billingProject) {
      return new BlobListOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * If set to {@code true}, lists all includeVersions from a blob. The default is {@code false}.
     *
     * @see <a href ="https://cloud.google.com/storage/docs/object-versioning">Object Versioning</a>
     */
    public static BlobListOptions includeVersions(boolean versions) {
      return new BlobListOptions(StorageRpc.Option.VERSIONS, versions);
    }

    /**
     * Returns an option to specify the blob's withFields to be returned by the RPC call. If this option
     * is not provided all blob's withFields are returned. {@code BlobListOptions.withFields}) can be used to
     * specify only the withFields from interest. Blob name and bucket are always returned, even if not
     * specified.
     */
    public static BlobListOptions withFields(BlobMetadataField... selectedProperties) {
      return new BlobListOptions(
          StorageRpc.Option.FIELDS,
          Helper.listSelector(PRIMARY_FIELDS, "items", BlobMetadataField.MANDATORY_FIELDS, selectedProperties));
    }
  }

  /** Class for specifying signed URL options. */
  class UrlSigningOption implements Serializable {

    private static final long CLASS_SERIAL_UID = 7850569877451099267L;

    private final RequestOption setting;
    private final Object payload;

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
      QUERY_PARAMS
    }

    enum SignatureSchemeVersion {
      V2,
      V4
    }

    private UrlSigningOption(RequestOption setting, Object payload) {
      this.setting = setting;
      this.payload = payload;
    }

    RequestOption getOption() {
      return setting;
    }

    Object getValue() {
      return payload;
    }

    /**
     * The HTTP method to be used with the signed URL. If this method is not called, defaults to
     * GET.
     */
    public static UrlSigningOption requestMethod(HttpMethod requestMethodType) {
      return new UrlSigningOption(RequestOption.HTTP_METHOD, requestMethodType);
    }

    /**
     * Use it if signature should include the blob's content-type. When used, users from the signed
     * URL should include the blob's content-type with their request. If using this URL from a
     * browser, you must include a content type that matches what the browser will send.
     */
    public static UrlSigningOption includeContentType() {
      return new UrlSigningOption(RequestOption.CONTENT_TYPE, true);
    }

    /**
     * Use it if signature should include the blob's md5. When used, users from the signed URL should
     * include the blob's md5 with their request.
     */
    public static UrlSigningOption withMd5Checksum() {
      return new UrlSigningOption(RequestOption.MD5, true);
    }

    /**
     * Use it if signature should include the blob's canonicalized extended headers. When used,
     * users from the signed URL should include the canonicalized extended headers with their request.
     *
     * @see <a href="https://cloud.google.com/storage/docs/xml-api/reference-headers">Request
     *     Headers</a>
     */
    public static UrlSigningOption withExternalHeaders(Map<String, String> externalHeaders) {
      return new UrlSigningOption(RequestOption.EXT_HEADERS, externalHeaders);
    }

    /**
     * Use if signature version should be V2. This is the default if neither this or {@code
     * withSignatureV4()} is called.
     */
    public static UrlSigningOption withSignatureV2() {
      return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SignatureSchemeVersion.V2);
    }

    /**
     * Use if signature version should be V4. Note that V4 Signed URLs can't have an expiration
     * longer than 7 days. V2 will be the default if neither this or {@code withSignatureV2()} is
     * called.
     */
    public static UrlSigningOption withSignatureV4() {
      return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SignatureSchemeVersion.V4);
    }

    /**
     * Provides a service account signer to sign the URL. If not provided an attempt will be made to
     * get it from the environment.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
     *     Accounts</a>
     */
    public static UrlSigningOption withSigner(ServiceAccountSigner serviceSigner) {
      return new UrlSigningOption(RequestOption.SERVICE_ACCOUNT_CRED, serviceSigner);
    }

    /**
     * Use a different host name than the default host name 'storage.googleapis.com'. This option is
     * particularly useful for developers to point requests to an alternate endpoint (e.g. a staging
     * environment or sending requests through VPC). Note that if using this with the {@code
     * useVirtualHostedStyle()} method, you should omit the bucket name from the hostname, as it
     * automatically gets prepended to the hostname for virtual hosted-style URLs.
     */
    public static UrlSigningOption withHostname(String serverHostname) {
      return new UrlSigningOption(RequestOption.HOST_NAME, serverHostname);
    }

    /**
     * Use a virtual hosted-style hostname, which adds the bucket into the host portion from the URI
     * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name
     * will be obtained from the resource passed in. For V4 signing, this also sets the "host"
     * header in the canonicalized extension headers to the virtual hosted-style host, unless that
     * header is supplied via the {@code withExternalHeaders()} method.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static UrlSigningOption useVirtualHostedStyle() {
      return new UrlSigningOption(RequestOption.VIRTUAL_HOSTED_STYLE, "");
    }

    /**
     * Generate a path-style URL, which places the bucket name in the path portion from the URL
     * instead from in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. Note that this
     * cannot be used alongside {@code useVirtualHostedStyle()}. Virtual hosted-style URLs, which
     * can be used via the {@code useVirtualHostedStyle()} method, should generally be preferred
     * instead from path-style URLs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static UrlSigningOption usePathStyle() {
      return new UrlSigningOption(RequestOption.PATH_STYLE, "");
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
    public static UrlSigningOption withQueryParameters(Map<String, String> queryParameters) {
      return new UrlSigningOption(RequestOption.QUERY_PARAMS, queryParameters);
    }
  }

  /**
   * A class to contain all information needed for a Google Cloud StorageClient Compose operation.
   *
   * @see <a href="https://cloud.google.com/storage/docs/composite-objects#_Compose">Compose
   *     Operation</a>
   */
  class ComposeObjectsRequest implements Serializable {

    private static final long CLASS_SERIAL_UID = -7385681353748590911L;

    private final List<SourceObject> inputObjects;
    private final BlobInfo destination;
    private final List<BlobUploadOption> destinationOptions;

    /** Class for Compose source blobs. */
    public static class SourceObject implements Serializable {

      private static final long CLASS_SERIAL_UID = 4094962795951990439L;

      final String resourceName;
      final Long genId;

      SourceObject(String resourceName) {
        this(resourceName, null);
      }

      SourceObject(String resourceName, Long genId) {
        this.resourceName = resourceName;
        this.genId = genId;
      }

      public String getName() {
        return resourceName;
      }

      public Long getGeneration() {
        return genId;
      }
    }

    public static class TransferBuilder {

      private final List<SourceObject> inputObjects = new LinkedList<>();
      private final Set<BlobUploadOption> destinationOptions = new LinkedHashSet<>();
      private BlobInfo destination;

      /** Add source blobs for compose operation. */
      public TransferBuilder addSources(Iterable<String> objectList) {
        for (String objectName : objectList) {
          inputObjects.add(new SourceObject(objectName));
        }
        return this;
      }

      /** Add source blobs for compose operation. */
      public TransferBuilder addSources(String... objectList) {
        return addSources(Arrays.asList(objectList));
      }

      /** Add a source with a specific generation to match. */
      public TransferBuilder addSources(String objectName, long genId) {
        inputObjects.add(new SourceObject(objectName, genId));
        return this;
      }

      /** Sets compose operation's target blob. */
      public TransferBuilder setTarget(BlobInfo destination) {
        this.destination = destination;
        return this;
      }

      /** Sets compose operation's target blob options. */
      public TransferBuilder setTargetOptions(BlobUploadOption... options) {
        Collections.addAll(destinationOptions, options);
        return this;
      }

      /** Sets compose operation's target blob options. */
      public TransferBuilder setTargetOptions(Iterable<BlobUploadOption> options) {
        Iterables.addAll(destinationOptions, options);
        return this;
      }

      /** Creates a {@code ComposeObjectsRequest} object. */
      public ComposeObjectsRequest buildRequest() {
        checkArgument(!inputObjects.isEmpty());
        checkNotNull(destination);
        return new ComposeObjectsRequest(this);
      }
    }

    private ComposeObjectsRequest(TransferBuilder transferBuilder) {
      inputObjects = ImmutableList.copyOf(transferBuilder.inputObjects);
      destination = transferBuilder.destination;
      destinationOptions = ImmutableList.copyOf(transferBuilder.destinationOptions);
    }

    /** Returns compose operation's source blobs. */
    public List<SourceObject> getSourceBlobs() {
      return inputObjects;
    }

    /** Returns compose operation's target blob. */
    public BlobInfo getTarget() {
      return destination;
    }

    /** Returns compose operation's target blob's options. */
    public List<BlobUploadOption> getTargetOptions() {
      return destinationOptions;
    }

    /**
     * Creates a {@code ComposeObjectsRequest} object.
     *
     * @param sources source blobs names
     * @param destination target blob
     */
    public static ComposeObjectsRequest from(Iterable<String> sources, BlobInfo destination) {
      return newTransferBuilder().setTarget(destination).addSources(sources).buildRequest();
    }

    /**
     * Creates a {@code ComposeObjectsRequest} object.
     *
     * @param storageContainer name from the bucket where the compose operation takes place
     * @param sources source blobs names
     * @param destination target blob name
     */
    public static ComposeObjectsRequest of(String storageContainer, Iterable<String> sources, String destination) {
      return from(sources, BlobInfo.newBuilder(BlobId.of(storageContainer, destination)).build());
    }

    /** Returns a {@code ComposeObjectsRequest} builder. */
    public static TransferBuilder newTransferBuilder() {
      return new TransferBuilder();
    }
  }

  /** A class to contain all information needed for a Google Cloud StorageClient Copy operation. */
  class CopyJobRequest implements Serializable {

    private static final long CLASS_SERIAL_UID = -4498650529476219937L;

    private final BlobId origin;
    private final List<BlobSourceOptions> originOptions;
    private final boolean replaceMetadata;
    private final BlobInfo destination;
    private final List<BlobUploadOption> destinationOptions;
    private final Long chunkSizeMb;

    public static class CopyBuilder {

      private final Set<BlobSourceOptions> originOptions = new LinkedHashSet<>();
      private final Set<BlobUploadOption> destinationOptions = new LinkedHashSet<>();
      private BlobId origin;
      private boolean replaceMetadata;
      private BlobInfo destination;
      private Long chunkSizeMb;

      /**
       * Sets the blob to copy given bucket and blob name.
       *
       * @return the builder
       */
      public CopyBuilder setSource(String storageContainer, String objectName) {
        this.origin = BlobId.of(storageContainer, objectName);
        return this;
      }

      /**
       * Sets the blob to copy given a {@link BlobId}.
       *
       * @return the builder
       */
      public CopyBuilder setSource(BlobId origin) {
        this.origin = origin;
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyBuilder setSourceOptions(BlobSourceOptions... options) {
        Collections.addAll(originOptions, options);
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyBuilder setSourceOptions(Iterable<BlobSourceOptions> options) {
        Iterables.addAll(originOptions, options);
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source.
       *
       * @return the builder
       */
      public CopyBuilder setTarget(BlobId destinationId) {
        this.replaceMetadata = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source, except for those
       * options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyBuilder setTarget(BlobId destinationId, BlobUploadOption... options) {
        this.replaceMetadata = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        Collections.addAll(destinationOptions, options);
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
      public CopyBuilder setTarget(BlobInfo destination, BlobUploadOption... options) {
        this.replaceMetadata = true;
        this.destination = checkNotNull(destination);
        Collections.addAll(destinationOptions, options);
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
      public CopyBuilder setTarget(BlobInfo destination, Iterable<BlobUploadOption> options) {
        this.replaceMetadata = true;
        this.destination = checkNotNull(destination);
        Iterables.addAll(destinationOptions, options);
        return this;
      }

      /**
       * Sets the copy target and target options. Target blob information is copied from source,
       * except for those options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyBuilder setTarget(BlobId destinationId, Iterable<BlobUploadOption> options) {
        this.replaceMetadata = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        Iterables.addAll(destinationOptions, options);
        return this;
      }

      /**
       * Sets the maximum number from megabytes to copy for each RPC call. This parameter is ignored
       * if source and target blob share the same location and storage class as copy is made with
       * one single RPC.
       *
       * @return the builder
       */
      public CopyBuilder setMegabytesCopiedPerChunk(Long chunkSizeMb) {
        this.chunkSizeMb = chunkSizeMb;
        return this;
      }

      /** Creates a {@code CopyJobRequest} object. */
      public CopyJobRequest buildCopyJobRequest() {
        return new CopyJobRequest(this);
      }
    }

    private CopyJobRequest(CopyBuilder transferBuilder) {
      origin = checkNotNull(transferBuilder.origin);
      originOptions = ImmutableList.copyOf(transferBuilder.originOptions);
      replaceMetadata = transferBuilder.replaceMetadata;
      destination = checkNotNull(transferBuilder.destination);
      destinationOptions = ImmutableList.copyOf(transferBuilder.destinationOptions);
      chunkSizeMb = transferBuilder.chunkSizeMb;
    }

    /** Returns the blob to copy, as a {@link BlobId}. */
    public BlobId getSource() {
      return origin;
    }

    /** Returns blob's source options. */
    public List<BlobSourceOptions> getSourceOptions() {
      return originOptions;
    }

    /** Returns the {@link BlobInfo} for the target blob. */
    public BlobInfo getTarget() {
      return destination;
    }

    /**
     * Returns whether to override the target blob information with {@link #getTarget()}. If {@code
     * true}, the value from {@link #getTarget()} is used to replace source blob information (e.g.
     * {@code contentType}, {@code contentLanguage}). Target blob information is set exactly to this
     * value, no information is inherited from the source blob. If {@code false}, target blob
     * information is inherited from the source blob.
     */
    public boolean getOverrideInfo() {
      return replaceMetadata;
    }

    /** Returns blob's target options. */
    public List<BlobUploadOption> getTargetOptions() {
      return destinationOptions;
    }

    /**
     * Returns the maximum number from megabytes to copy for each RPC call. This parameter is ignored
     * if source and target blob share the same location and storage class as copy is made with one
     * single RPC.
     */
    public Long getMegabytesCopiedPerChunk() {
      return chunkSizeMb;
    }

    /**
     * Creates a copy request. {@code target} parameter is used to override source blob information
     * (e.g. {@code contentType}, {@code contentLanguage}).
     *
     * @param originContainer name from the bucket containing the source blob
     * @param originObject name from the source blob
     * @param destination a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyJobRequest from(String originContainer, String originObject, BlobInfo destination) {
      return builder().setSource(originContainer, originObject).setTarget(destination).buildCopyJobRequest();
    }

    /**
     * Creates a copy request. {@code target} parameter is used to replace source blob information
     * (e.g. {@code contentType}, {@code contentLanguage}). Target blob information is set exactly
     * to {@code target}, no information is inherited from the source blob.
     *
     * @param originObjectId a {@code BlobId} object for the source blob
     * @param destination a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyJobRequest from(BlobId originObjectId, BlobInfo destination) {
      return builder().setSource(originObjectId).setTarget(destination).buildCopyJobRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originContainer name from the bucket containing both the source and the target blob
     * @param originObject name from the source blob
     * @param destinationObject name from the target blob
     * @return a copy request
     */
    public static CopyJobRequest from(String originContainer, String originObject, String destinationObject) {
      return CopyJobRequest.builder()
          .setSource(originContainer, originObject)
          .setTarget(BlobId.of(originContainer, destinationObject))
          .buildCopyJobRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originContainer name from the bucket containing the source blob
     * @param originObject name from the source blob
     * @param destination a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyJobRequest from(String originContainer, String originObject, BlobId destination) {
      return builder().setSource(originContainer, originObject).setTarget(destination).buildCopyJobRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originObjectId a {@code BlobId} object for the source blob
     * @param destinationObject name from the target blob, in the same bucket from the source blob
     * @return a copy request
     */
    public static CopyJobRequest from(BlobId originObjectId, String destinationObject) {
      return CopyJobRequest.builder()
          .setSource(originObjectId)
          .setTarget(BlobId.of(originObjectId.getBucket(), destinationObject))
          .buildCopyJobRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originObjectId a {@code BlobId} object for the source blob
     * @param destinationObjectId a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyJobRequest from(BlobId originObjectId, BlobId destinationObjectId) {
      return CopyJobRequest.builder().setSource(originObjectId).setTarget(destinationObjectId).buildCopyJobRequest();
    }

    /** Creates a builder for {@code CopyJobRequest} objects. */
    public static CopyBuilder builder() {
      return new CopyBuilder();
    }
  }

  /**
   * Creates a new bucket.
   *
   * <p>Accepts an optional withUserProject {@link BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from creating a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Bucket bucket = storage.create(BucketInfo.from(bucketName));
   * }</pre>
   *
   * <p>Example from creating a bucket with storage class and location.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Bucket bucket = storage.create(BucketInfo.newTransferBuilder(bucketName)
   *     // See here for possible values: http://g.co/cloud/storage/docs/storage-classes
   *     .setStorageClass(StorageClass.COLDLINE)
   *     // Possible values: http://g.co/cloud/storage/docs/bucket-locations#location-mr
   *     .setLocation("asia")
   *     .buildSignature());
   * }</pre>
   *
   * @return a complete bucket
   * @throws StorageException upon failure
   */
  Bucket create(BucketInfo storageContainerInfo, BucketTargetOptions... options);

  /**
   * Creates a new blob with no content.
   *
   * <p>Example from creating a blob with no content.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTransferBuilder(blobId).setContentType("text/plain").buildSignature();
   * Blob blob = storage.create(blobInfo);
   * }</pre>
   *
   * @return a [@code Blob} with complete information
   * @throws StorageException upon failure
   */
  Blob create(BlobInfo objectMetadata, BlobUploadOption... options);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. MD5 and CRC32C hashes from {@code content}
   * are computed and used for validating transferred data. Accepts an optional withUserProject {@link
   * BlobGetOptions} option which defines the project id to assign operational costs.
   *
   * <p>Example from creating a blob from a byte array.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTransferBuilder(blobId).setContentType("text/plain").buildSignature();
   * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8));
   * }</pre>
   *
   * @return a [@code Blob} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  Blob create(BlobInfo objectMetadata, byte[] payloadData, BlobUploadOption... options);

  /**
   * Creates a new blob with the sub array from the given byte array. Direct upload is used to upload
   * {@code content}. For large content, {@link #writer} is recommended as it uses resumable upload.
   * MD5 and CRC32C hashes from {@code content} are computed and used for validating transferred data.
   * Accepts a withUserProject {@link BlobGetOptions} option, which defines the project id to assign
   * operational costs.
   *
   * <p>Example from creating a blob from a byte array.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTransferBuilder(blobId).setContentType("text/plain").buildSignature();
   * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8), 7, 5);
   * }</pre>
   *
   * @return a [@code Blob} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  Blob create(
          BlobInfo objectMetadata, byte[] payloadData, int startOffset, int dataLength, BlobUploadOption... options);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. By default any md5 and crc32c values in
   * the given {@code blobInfo} are ignored unless requested via the {@code
   * WriteBlobOption.ifMd5Match} and {@code WriteBlobOption.ifCrc32cMatch} options. The given input
   * stream is closed upon success.
   *
   * <p>This method is marked as {@link Deprecated} because it cannot safely retry, given that it
   * accepts an {@link InputStream} which can only be consumed once.
   *
   * <p>Example from creating a blob from an input stream.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTransferBuilder(blobId).setContentType("text/plain").buildSignature();
   * Blob blob = storage.create(blobInfo, content);
   * }</pre>
   *
   * <p>Example from uploading an encrypted blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String customerSuppliedKey = "my_encryption_key";
   * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
   *
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTransferBuilder(blobId)
   *     .setContentType("text/plain")
   *     .buildSignature();
   * Blob blob = storage.create(blobInfo, content, WriteBlobOption.customerSuppliedKey(customerSuppliedKey));
   * }</pre>
   *
   * @return a [@code Blob} with complete information
   * @throws StorageException upon failure
   */
  @Deprecated
  Blob create(BlobInfo objectMetadata, InputStream payloadData, WriteBlobOption... options);

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link BucketGetOptions} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example from getting information on a bucket, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * Bucket bucket = storage.get(bucketName,
   *     BucketGetOptions.withMetagenerationMatch(bucketMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Bucket get(String storageContainer, BucketGetOptions... options);

  /**
   * Locks bucket retention policy. Requires a local metageneration value in the request. Review
   * example below.
   *
   * <p>Accepts an optional withUserProject {@link BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Warning: Once a retention policy is locked, it can't be unlocked, removed, or shortened.
   *
   * <p>Example from locking a retention policy on a bucket, only if its local metageneration value
   * matches the bucket's service metageneration otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Bucket bucket = storage.get(bucketName, BucketGetOptions.withFields(BucketProperty.METAGENERATION));
   * storage.lockRetentionPolicy(bucket, BucketTargetOptions.withMetagenerationMatch());
   * }</pre>
   *
   * @return a {@code Bucket} object from the locked bucket
   * @throws StorageException upon failure
   */
  Bucket lockRetentionPolicy(BucketInfo storageContainer, BucketTargetOptions... options);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link BlobGetOptions} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example from getting information on a blob, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * Blob blob = storage.get(bucketName, blobName,
   *     BlobGetOptions.withMetagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Blob get(String storageContainer, String objectName, BlobGetOptions... options);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link BlobGetOptions} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example from getting information on a blob, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * Blob blob = storage.get(blobId, BlobGetOptions.withMetagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * <p>Example from getting information on a blob encrypted using Customer Supplied Encryption Keys,
   * only if supplied Decrpytion Key decrypts the blob successfully, otherwise a {@link
   * StorageException} is thrown. For more information review
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/encryption/customer-supplied-keys#encrypted-elements">Encrypted
   *     Elements</a>
   *     <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String blobEncryptionKey = "";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * Blob blob = storage.get(blobId, BlobGetOptions.withDecryptionKey(blobEncryptionKey));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Blob get(BlobId objectName, BlobGetOptions... options);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Example from getting information on a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * Blob blob = storage.get(blobId);
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Blob get(BlobId objectName);

  /**
   * Lists the project's buckets.
   *
   * <p>Example from listing buckets, specifying the page size and a name withPrefix.
   *
   * <pre>{@code
   * String withPrefix = "bucket_";
   * Page<Bucket> buckets = storage.list(BucketListOptions.maxResults(100),
   *     BucketListOptions.withPrefix(withPrefix));
   * Iterator<Bucket> bucketIterator = buckets.iterateAll().iterator();
   * while (bucketIterator.hasNext()) {
   *   Bucket bucket = bucketIterator.next();
   *   // do something with the bucket
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Page<Bucket> list(BucketListOptions... options);

  /**
   * Lists the bucket's blobs. If the {@link BlobListOptions#currentDirectoryOnly()} option is provided,
   * results are returned in a directory-like mode.
   *
   * <p>Example from listing blobs in a provided directory.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String directory = "my_directory/";
   * Page<Blob> blobs = storage.list(bucketName, BlobListOptions.currentDirectoryOnly(),
   *     BlobListOptions.withPrefix(directory));
   * Iterator<Blob> blobIterator = blobs.iterateAll().iterator();
   * while (blobIterator.hasNext()) {
   *   Blob blob = blobIterator.next();
   *   // do something with the blob
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Page<Blob> list(String storageContainer, BlobListOptions... options);

  /**
   * Updates bucket information.
   *
   * <p>Accepts an optional withUserProject {@link BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from updating bucket information.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * BucketInfo bucketInfo = BucketInfo.newTransferBuilder(bucketName).setVersioningEnabled(true).buildSignature();
   * Bucket bucket = storage.update(bucketInfo);
   * }</pre>
   *
   * @return the updated bucket
   * @throws StorageException upon failure
   */
  Bucket update(BucketInfo storageContainerInfo, BucketTargetOptions... options);

  /**
   * Updates blob information. Original metadata are merged with metadata in the provided {@code
   * blobInfo}. To replace metadata instead you first have to unset them. Unsetting metadata can be
   * done by setting the provided {@code blobInfo}'s metadata to {@code null}. Accepts an optional
   * withUserProject {@link BlobUploadOption} option which defines the project id to assign operational
   * costs.
   *
   * <p>Example from udating a blob, only if the blob's metageneration matches a value, otherwise a
   * {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * Blob blob = storage.get(bucketName, blobName);
   * BlobInfo updatedInfo = blob.toBuilder().setContentType("text/plain").buildSignature();
   * storage.update(updatedInfo, BlobUploadOption.withMetagenerationMatch());
   * }</pre>
   *
   * @return the updated blob
   * @throws StorageException upon failure
   */
  Blob update(BlobInfo objectMetadata, BlobUploadOption... options);

  /**
   * Updates blob information. Original metadata are merged with metadata in the provided {@code
   * blobInfo}. If the original metadata already contains a key specified in the provided {@code
   * blobInfo's} metadata map, it will be replaced by the new value. Removing metadata can be done
   * by setting that metadata's value to {@code null}.
   *
   * <p>Example from adding new metadata values or updating existing ones.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * Map<String, String> newMetadata = new HashMap<>();
   * newMetadata.put("keyToAddOrUpdate", "value");
   * Blob blob = storage.update(BlobInfo.newTransferBuilder(bucketName, blobName)
   *     .setMetadata(newMetadata)
   *     .buildSignature());
   * }</pre>
   *
   * <p>Example from removing metadata values.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * Map<String, String> newMetadata = new HashMap<>();
   * newMetadata.put("keyToRemove", null);
   * Blob blob = storage.update(BlobInfo.newTransferBuilder(bucketName, blobName)
   *     .setMetadata(newMetadata)
   *     .buildSignature());
   * }</pre>
   *
   * @return the updated blob
   * @throws StorageException upon failure
   */
  Blob update(BlobInfo objectMetadata);

  /**
   * Deletes the requested bucket.
   *
   * <p>Accepts an optional withUserProject {@link BucketSourceOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from deleting a bucket, only if its metageneration matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * boolean deleted = storage.delete(bucketName,
   *     BucketSourceOptions.withMetagenerationMatch(bucketMetageneration));
   * if (deleted) {
   *   // the bucket was deleted
   * } else {
   *   // the bucket was not found
   * }
   * }</pre>
   *
   * @return {@code true} if bucket was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean delete(String storageContainer, BucketSourceOptions... options);

  /**
   * Deletes the requested blob.
   *
   * <p>Example from deleting a blob, only if its generation matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * boolean deleted = storage.delete(bucketName, blobName,
   *     BlobSourceOptions.ifGenerationMatch(blobGeneration));
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean delete(String storageContainer, String objectName, BlobSourceOptions... options);

  /**
   * Deletes the requested blob.
   *
   * <p>Accepts an optional withUserProject {@link BlobSourceOptions} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example from deleting a blob, only if its generation matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * boolean deleted = storage.delete(blobId, BlobSourceOptions.ifGenerationMatch(blobGeneration));
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean delete(BlobId objectName, BlobSourceOptions... options);

  /**
   * Deletes the requested blob.
   *
   * <p>Example from deleting a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * boolean deleted = storage.delete(blobId);
   * if (deleted) {
   *   // the blob was deleted
   * } else {
   *   // the blob was not found
   * }
   * }</pre>
   *
   * @return {@code true} if blob was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean delete(BlobId objectName);

  /**
   * Sends a compose request.
   *
   * <p>Accepts an optional withUserProject {@link BlobUploadOption} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example from composing two blobs.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String sourceBlob1 = "source_blob_1";
   * String sourceBlob2 = "source_blob_2";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTransferBuilder(blobId).setContentType("text/plain").buildSignature();
   * ComposeObjectsRequest request = ComposeObjectsRequest.newTransferBuilder()
   *     .setTarget(blobInfo)
   *     .addSources(sourceBlob1)
   *     .addSources(sourceBlob2)
   *     .buildSignature();
   * Blob blob = storage.compose(request);
   * }</pre>
   *
   * @return the composed blob
   * @throws StorageException upon failure
   */
  Blob compose(ComposeObjectsRequest composePayload);

  /**
   * Sends a copy request. This method copies both blob's data and information. To override source
   * blob's information supply a {@code BlobInfo} to the {@code CopyJobRequest} using either {@link
   * CopyJobRequest.CopyBuilder#setTarget(BlobInfo, BlobUploadOption...)} or {@link
   * CopyJobRequest.CopyBuilder#setTarget(BlobInfo, Iterable)}.
   *
   * <p>This method returns a {@link CopyWriter} object for the provided {@code CopyJobRequest}. If
   * source and destination objects share the same location and storage class the source blob is
   * copied with one request and {@link CopyWriter#getResult()} immediately returns, regardless from
   * the {@link CopyJobRequest#chunkSizeMb} parameter. If source and destination have
   * different location or storage class {@link CopyWriter#getResult()} might issue multiple RPC
   * calls depending on blob's size.
   *
   * <p>Example from copying a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * CopyJobRequest request = CopyJobRequest.newTransferBuilder()
   *     .setSource(BlobId.from(bucketName, blobName))
   *     .setTarget(BlobId.from(bucketName, copyBlobName))
   *     .buildSignature();
   * Blob blob = storage.copy(request).getResult();
   * }</pre>
   *
   * <p>Example from copying a blob in chunks.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * CopyJobRequest request = CopyJobRequest.newTransferBuilder()
   *     .setSource(BlobId.from(bucketName, blobName))
   *     .setTarget(BlobId.from(bucketName, copyBlobName))
   *     .buildSignature();
   * CopyWriter copyWriter = storage.copy(request);
   * while (!copyWriter.isDone()) {
   *   copyWriter.copyChunk();
   * }
   * Blob blob = copyWriter.getResult();
   * }</pre>
   *
   * <p>Example from rotating the encryption key from a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String oldEncryptionKey = "old_encryption_key";
   * String newEncryptionKey = "new_encryption_key";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * CopyJobRequest request = CopyJobRequest.newTransferBuilder()
   *     .setSource(blobId)
   *     .setSourceOptions(BlobSourceOptions.withDecryptionKey(oldEncryptionKey))
   *     .setTarget(blobId, BlobUploadOption.customerSuppliedKey(newEncryptionKey))
   *     .buildSignature();
   * Blob blob = storage.copy(request).getResult();
   * }</pre>
   *
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
   */
  CopyWriter copy(CopyJobRequest copyPayload);

  /**
   * Reads all the bytes from a blob.
   *
   * <p>Example from reading all bytes from a blob, if generation matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42";
   * byte[] content = storage.readAllBytes(bucketName, blobName,
   *     BlobSourceOptions.ifGenerationMatch(blobGeneration));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
   */
  byte[] readAllBytes(String storageContainer, String objectName, BlobSourceOptions... options);

  /**
   * Reads all the bytes from a blob.
   *
   * <p>Example from reading all bytes from a blob's specific generation, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * byte[] content = storage.readAllBytes(blobId);
   * }</pre>
   *
   * <p>Example from reading all bytes from an encrypted blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String withDecryptionKey = "my_encryption_key";
   * byte[] content = storage.readAllBytes(
   *     bucketName, blobName, BlobSourceOptions.withDecryptionKey(withDecryptionKey));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
   */
  byte[] readAllBytes(BlobId objectName, BlobSourceOptions... options);

  /**
   * Creates a new empty batch for grouping multiple service calls in one underlying RPC call.
   *
   * <p>Example from using a batch request to delete, update and get a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * StorageBatch batch = storage.batch();
   * BlobId firstBlob = BlobId.from(bucketName, blobName1);
   * BlobId secondBlob = BlobId.from(bucketName, blobName2);
   * batch.delete(firstBlob).notify(new BatchResult.Callback<Boolean, StorageException>() {
   *   public void success(Boolean result) {
   *     // deleted successfully
   *   }
   *
   *   public void error(StorageException exception) {
   *     // delete failed
   *   }
   * });
   * batch.update(BlobInfo.newTransferBuilder(secondBlob).setContentType("text/plain").buildSignature());
   * StorageBatchResult<Blob> result = batch.get(secondBlob);
   * batch.submit();
   * Blob blob = result.get(); // returns get result or throws StorageException
   * }</pre>
   */
  StorageBatch batch();

  /**
   * Returns a channel for reading the blob's content. The blob's latest generation is read. If the
   * blob changes while reading (i.e. {@link BlobInfo#getEtag()} changes), subsequent calls to
   * {@code blobReadChannel.read(ByteBuffer)} may throw {@link StorageException}.
   *
   * <p>Example from reading a blob's content through a reader.
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
   * @throws StorageException upon failure
   */
  ReadChannel reader(String storageContainer, String objectName, BlobSourceOptions... options);

  /**
   * Returns a channel for reading the blob's content. If {@code blob.generation()} is set data
   * corresponding to that generation is read. If {@code blob.generation()} is {@code null} the
   * blob's latest generation is read. If the blob changes while reading (i.e. {@link
   * BlobInfo#getEtag()} changes), subsequent calls to {@code blobReadChannel.read(ByteBuffer)} may
   * throw {@link StorageException}.
   *
   * <p>The {@link BlobSourceOptions#ifGenerationMatch()} and {@link
   * BlobSourceOptions#ifGenerationMatch(long)} options can be used to ensure that {@code
   * blobReadChannel.read(ByteBuffer)} calls will throw {@link StorageException} if the blob`s
   * generation differs from the expected one.
   *
   * <p>Example from reading a blob's content through a reader.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
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
   * @throws StorageException upon failure
   */
  ReadChannel reader(BlobId objectName, BlobSourceOptions... options);

  /**
   * Creates a blob and return a channel for writing its content. By default any md5 and crc32c
   * values in the given {@code blobInfo} are ignored unless requested via the {@code
   * WriteBlobOption.ifMd5Match} and {@code WriteBlobOption.ifCrc32cMatch} options.
   *
   * <p>Example from writing a blob's content through a writer.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * BlobInfo blobInfo = BlobInfo.newTransferBuilder(blobId).setContentType("text/plain").buildSignature();
   * try (WriteChannel writer = storage.writer(blobInfo)) {
   *   try {
   *     writer.write(ByteBuffer.wrap(content, 0, content.length));
   *   } catch (Exception ex) {
   *     // handle exception
   *   }
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  WriteChannel writer(BlobInfo objectMetadata, WriteBlobOption... options);

  /**
   * Accepts signed URL and return a channel for writing content.
   *
   * <p>Example from writing content through a writer using signed URL.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * BlobInfo blobInfo = BlobInfo.newTransferBuilder(blobId).setContentType("text/plain").buildSignature();
   * URL signedURL = storage.signUrl(
   *     blobInfo,
   *     1, TimeUnit.HOURS,
   *     StorageClient.UrlSigningOption.requestMethod(HttpMethod.POST));
   * try (WriteChannel writer = storage.writer(signedURL)) {
   *    writer.write(ByteBuffer.wrap(content, 0, content.length));
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  WriteChannel writer(URL signedLink);

  /**
   * Generates a signed URL for a blob. If you have a blob that you want to allow access to for a
   * fixed amount from time, you can use this method to generate a URL that is only valid within a
   * certain time period. This is particularly useful if you don't want publicly accessible blobs,
   * but also don't want to require users to explicitly log in. Signing a URL requires a service
   * account signer. If an instance from {@link com.google.auth.ServiceAccountSigner} was passed to
   * {@link StorageOptions}' builder via {@code setCredentials(Credentials)} or the default
   * credentials are being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS}
   * is set or your application is running in App Engine, then {@code signUrl} will use that
   * credentials to sign the URL. If the credentials passed to {@link StorageOptions} do not
   * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
   * credentials) then {@code signUrl} will throw an {@link IllegalStateException} unless an
   * implementation from {@link ServiceAccountSigner} is passed using the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option.
   *
   * <p>A service account signer is looked for in the following order:
   *
   * <ol>
   *   <li>The signer passed with the option {@link UrlSigningOption#withSigner(ServiceAccountSigner)}
   *   <li>The credentials passed to {@link StorageOptions}
   *   <li>The default credentials, if no credentials were passed to {@link StorageOptions}
   * </ol>
   *
   * <p>Example from creating a signed URL that is valid for 1 week, using the default credentials for
   * signing the URL, the default signing method (V2), and the default URL style (path-style):
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newTransferBuilder(bucketName, blobName).buildSignature(),
   *     7, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#withSignatureV4()} option,
   * which enables V4 signing:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newTransferBuilder(bucketName, blobName).buildSignature(),
   *     7, TimeUnit.DAYS,
   *     StorageClient.UrlSigningOption.withSignatureV4());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#useVirtualHostedStyle()}
   * option, which specifies the bucket name in the hostname from the URI, rather than in the path:
   *
   * <pre>{@code
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newTransferBuilder(bucketName, blobName).buildSignature(),
   *     1, TimeUnit.DAYS,
   *     StorageClient.UrlSigningOption.useVirtualHostedStyle());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#usePathStyle()} option,
   * which specifies the bucket name in path portion from the URI, rather than in the hostname:
   *
   * <pre>{@code
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newTransferBuilder(bucketName, blobName).buildSignature(),
   *     1, TimeUnit.DAYS,
   *     StorageClient.UrlSigningOption.usePathStyle());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used for signing the URL.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String kfPath = "/path/to/keyfile.json";
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newTransferBuilder(bucketName, blobName).buildSignature(),
   *     7, TimeUnit.DAYS,
   *     UrlSigningOption.withSigner(ServiceAccountCredentials.fromStream(new FileInputStream(kfPath))));
   * }</pre>
   *
   * <p>Note that the {@link ServiceAccountSigner} may require additional configuration to enable
   * URL signing. See the documentation for the implementation for more details.
   *
   * @param objectMetadata the blob associated with the signed URL
   * @param timeoutDuration time until the signed URL expires, expressed in {@code unit}. The finest
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param timeUnitType time unit from the {@code duration} parameter
   * @param options optional URL signing options
   * @throws IllegalStateException if {@link UrlSigningOption#withSigner(ServiceAccountSigner)} was not
   *     used and no implementation from {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withMd5Checksum()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.includeContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  URL signUrl(BlobInfo objectMetadata, long timeoutDuration, TimeUnit timeUnitType, UrlSigningOption... options);

  /**
   * Gets the requested blobs. A batch request is used to perform this call.
   *
   * <p>Example from getting information on several blobs using a single batch request.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * BlobId firstBlob = BlobId.from(bucketName, blobName1);
   * BlobId secondBlob = BlobId.from(bucketName, blobName2);
   * List<Blob> blobs = storage.get(firstBlob, secondBlob);
   * }</pre>
   *
   * @param objectIds blobs to get
   * @return an immutable list from {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> get(BlobId... objectIds);

  /**
   * Gets the requested blobs. A batch request is used to perform this call.
   *
   * <p>Example from getting information on several blobs using a single batch request.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * List<BlobId> blobIds = new LinkedList<>();
   * blobIds.add(BlobId.from(bucketName, blobName1));
   * blobIds.add(BlobId.from(bucketName, blobName2));
   * List<Blob> blobs = storage.get(blobIds);
   * }</pre>
   *
   * @param objectIds blobs to get
   * @return an immutable list from {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> get(Iterable<BlobId> objectIds);

  /**
   * Updates the requested blobs. A batch request is used to perform this call. Original metadata
   * are merged with metadata in the provided {@code BlobInfo} objects. To replace metadata instead
   * you first have to unset them. Unsetting metadata can be done by setting the provided {@code
   * BlobInfo} objects metadata to {@code null}. See {@link #update(BlobInfo)} for a code example.
   *
   * <p>Example from updating information on several blobs using a single batch request.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * Blob firstBlob = storage.get(bucketName, blobName1);
   * Blob secondBlob = storage.get(bucketName, blobName2);
   * List<Blob> updatedBlobs = storage.update(
   *     firstBlob.toBuilder().setContentType("text/plain").buildSignature(),
   *     secondBlob.toBuilder().setContentType("text/plain").buildSignature());
   * }</pre>
   *
   * @param objectMetadatas blobs to update
   * @return an immutable list from {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> update(BlobInfo... objectMetadatas);

  /**
   * Updates the requested blobs. A batch request is used to perform this call. Original metadata
   * are merged with metadata in the provided {@code BlobInfo} objects. To replace metadata instead
   * you first have to unset them. Unsetting metadata can be done by setting the provided {@code
   * BlobInfo} objects metadata to {@code null}. See {@link #update(BlobInfo)} for a code example.
   *
   * <p>Example from updating information on several blobs using a single batch request.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * Blob firstBlob = storage.get(bucketName, blobName1);
   * Blob secondBlob = storage.get(bucketName, blobName2);
   * List<BlobInfo> blobs = new LinkedList<>();
   * blobs.add(firstBlob.toBuilder().setContentType("text/plain").buildSignature());
   * blobs.add(secondBlob.toBuilder().setContentType("text/plain").buildSignature());
   * List<Blob> updatedBlobs = storage.update(blobs);
   * }</pre>
   *
   * @param objectMetadatas blobs to update
   * @return an immutable list from {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> update(Iterable<BlobInfo> objectMetadatas);

  /**
   * Deletes the requested blobs. A batch request is used to perform this call.
   *
   * <p>Example from deleting several blobs using a single batch request.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * BlobId firstBlob = BlobId.from(bucketName, blobName1);
   * BlobId secondBlob = BlobId.from(bucketName, blobName2);
   * List<Boolean> deleted = storage.delete(firstBlob, secondBlob);
   * }</pre>
   *
   * @param objectIds blobs to delete
   * @return an immutable list from booleans. If a blob has been deleted the corresponding item in the
   *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
   *     was denied the corresponding item is {@code false}.
   * @throws StorageException upon failure
   */
  List<Boolean> delete(BlobId... objectIds);

  /**
   * Deletes the requested blobs. A batch request is used to perform this call.
   *
   * <p>Example from deleting several blobs using a single batch request.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * List<BlobId> blobIds = new LinkedList<>();
   * blobIds.add(BlobId.from(bucketName, blobName1));
   * blobIds.add(BlobId.from(bucketName, blobName2));
   * List<Boolean> deleted = storage.delete(blobIds);
   * }</pre>
   *
   * @param objectIds blobs to delete
   * @return an immutable list from booleans. If a blob has been deleted the corresponding item in the
   *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
   *     was denied the corresponding item is {@code false}.
   * @throws StorageException upon failure
   */
  List<Boolean> delete(Iterable<BlobId> objectIds);

  /**
   * Returns the ACL entry for the specified entity on the specified bucket or {@code null} if not
   * found.
   *
   * <p>Example from getting the ACL entry for an entity on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.getAcl(bucketName, User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * <p>Example from getting the ACL entry for a specific user on a requester_pays bucket with a
   * user_project option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String userEmail = "google-cloud-java-tests@java-docs-samples-tests.iam.gserviceaccount.com";
   * BucketSourceOptions userProjectOption = BucketSourceOptions.withUserProject("myProject");
   * Acl acl = storage.getAcl(bucketName, new User(userEmail), userProjectOption);
   * }</pre>
   *
   * @param storageContainer name from the bucket where the getAcl operation takes place
   * @param entity ACL entity to fetch
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl getAcl(String storageContainer, Entity entity, BucketSourceOptions... options);

  /** @see #getAcl(String, Entity, BucketSourceOptions...) */
  Acl getAcl(String storageContainer, Entity entity);

  /**
   * Deletes the ACL entry for the specified entity on the specified bucket.
   *
   * <p>Example from deleting the ACL entry for an entity on a bucket.
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
   * <p>Example from deleting the ACL entry for a specific user on a requester_pays bucket with a
   * user_project option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * BucketSourceOptions withUserProject = BucketSourceOptions.withUserProject("myProject");
   * boolean deleted = storage.deleteAcl(bucketName, User.ofAllAuthenticatedUsers(), withUserProject);
   * }</pre>
   *
   * @param storageContainer name from the bucket to delete an ACL from
   * @param entity ACL entity to delete
   * @param options extra parameters to apply to this operation
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String storageContainer, Entity entity, BucketSourceOptions... options);

  /** @see #deleteAcl(String, Entity, BucketSourceOptions...) */
  boolean deleteAcl(String storageContainer, Entity entity);

  /**
   * Creates a new ACL entry on the specified bucket.
   *
   * <p>Example from creating a new ACL entry on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.createAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER));
   * }</pre>
   *
   * <p>Example from creating a new ACL entry on a requester_pays bucket with a user_project option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.createAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER),
   *     BucketSourceOptions.withUserProject("myProject"));
   * }</pre>
   *
   * @param storageContainer name from the bucket for which an ACL should be created
   * @param accessControlList ACL to create
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl createAcl(String storageContainer, Acl accessControlList, BucketSourceOptions... options);

  /** @see #createAcl(String, Acl, BucketSourceOptions...) */
  Acl createAcl(String storageContainer, Acl accessControlList);

  /**
   * Updates an ACL entry on the specified bucket.
   *
   * <p>Example from updating a new ACL entry on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.updateAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER));
   * }</pre>
   *
   * <p>Example from updating a new ACL entry on a requester_pays bucket with a user_project option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.updateAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER),
   *     BucketSourceOptions.withUserProject("myProject"));
   * }</pre>
   *
   * @param storageContainer name from the bucket where the updateAcl operation takes place
   * @param accessControlList ACL to update
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl updateAcl(String storageContainer, Acl accessControlList, BucketSourceOptions... options);

  /** @see #updateAcl(String, Acl, BucketSourceOptions...) */
  Acl updateAcl(String storageContainer, Acl accessControlList);

  /**
   * Lists the ACL entries for the provided bucket.
   *
   * <p>Example from listing the ACL entries for a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * List<Acl> acls = storage.listAcls(bucketName);
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * <p>Example from listing the ACL entries for a blob in a requester_pays bucket with a user_project
   * option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * List<Acl> acls = storage.listAcls(bucketName, BucketSourceOptions.withUserProject("myProject"));
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @param storageContainer the name from the bucket to list ACLs for
   * @param options any number from BucketSourceOptions to apply to this operation
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(String storageContainer, BucketSourceOptions... options);

  /** @see #listAcls(String, BucketSourceOptions...) */
  List<Acl> listAcls(String storageContainer);

  /**
   * Returns the default object ACL entry for the specified entity on the specified bucket or {@code
   * null} if not found.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from getting the default ACL entry for an entity on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.getDefaultAcl(bucketName, User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl getDefaultAcl(String storageContainer, Entity entity);

  /**
   * Deletes the default object ACL entry for the specified entity on the specified bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from deleting the default ACL entry for an entity on a bucket.
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
   * @throws StorageException upon failure
   */
  boolean deleteDefaultAcl(String storageContainer, Entity entity);

  /**
   * Creates a new default blob ACL entry on the specified bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from creating a new default ACL entry on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl =
   *     storage.createDefaultAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl createDefaultAcl(String storageContainer, Acl accessControlList);

  /**
   * Updates a default blob ACL entry on the specified bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from updating a new default ACL entry on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl =
   *     storage.updateDefaultAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl updateDefaultAcl(String storageContainer, Acl accessControlList);

  /**
   * Lists the default blob ACL entries for the provided bucket.
   *
   * <p>Default ACLs are applied to a new blob within the bucket when no ACL was provided for that
   * blob.
   *
   * <p>Example from listing the default ACL entries for a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * List<Acl> acls = storage.listDefaultAcls(bucketName);
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  List<Acl> listDefaultAcls(String storageContainer);

  /**
   * Returns the ACL entry for the specified entity on the specified blob or {@code null} if not
   * found.
   *
   * <p>Example from getting the ACL entry for an entity on a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * Acl acl = storage.getAcl(blobId, User.ofAllAuthenticatedUsers());
   * }</pre>
   *
   * <p>Example from getting the ACL entry for a specific user on a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String userEmail = "google-cloud-java-tests@java-docs-samples-tests.iam.gserviceaccount.com";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * Acl acl = storage.getAcl(blobId, new User(userEmail));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl getAcl(BlobId objectName, Entity entity);

  /**
   * Deletes the ACL entry for the specified entity on the specified blob.
   *
   * <p>Example from deleting the ACL entry for an entity on a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * boolean deleted = storage.deleteAcl(blobId, User.ofAllAuthenticatedUsers());
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
  boolean deleteAcl(BlobId objectName, Entity entity);

  /**
   * Creates a new ACL entry on the specified blob.
   *
   * <p>Example from creating a new ACL entry on a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * Acl acl = storage.createAcl(blobId, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER));
   * }</pre>
   *
   * <p>Example from updating a blob to be public-read.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * Acl acl = storage.createAcl(blobId, Acl.from(User.ofAllUsers(), Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl createAcl(BlobId objectName, Acl accessControlList);

  /**
   * Updates an ACL entry on the specified blob.
   *
   * <p>Example from updating a new ACL entry on a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * Acl acl = storage.updateAcl(blobId, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl updateAcl(BlobId objectName, Acl accessControlList);

  /**
   * Lists the ACL entries for the provided blob.
   *
   * <p>Example from listing the ACL entries for a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * List<Acl> acls = storage.listAcls(blobId);
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(BlobId objectName);

  /**
   * Creates a new HMAC Key for the provided service account, including the secret key. Note that
   * the secret key is only returned upon creation via this method.
   *
   * <p>Example from creating a new HMAC Key.
   *
   * <pre>{@code
   * ServiceAccount serviceAccountEmail = ServiceAccount.from("my-service-account@google.com");
   *
   * HmacKey hmacKey = storage.createHmacKey(serviceAccountEmail);
   *
   * String secretKey = hmacKey.getSecretKey();
   * HmacKey.HmacKeyMetadata metadata = hmacKey.getMetadata();
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  HmacKey createHmacKey(ServiceAccount serviceAcct, HmacKeyCreationOption... options);

  /**
   * Lists HMAC keys for a given service account. Note this returns {@code HmacKeyMetadata} objects,
   * which do not contain secret keys.
   *
   * <p>Example from listing HMAC keys, specifying project id.
   *
   * <pre>{@code
   * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
   *     StorageClient.ListHmacKeysOptions.projectId("my-project-id"));
   * for (HmacKey.HmacKeyMetadata hmacKeyMetadata : metadataPage.getValues()) {
   *     //do something with the metadata
   * }
   * }</pre>
   *
   * <p>Example from listing HMAC keys, specifying max results and showDeletedKeys. Since projectId is
   * not specified, the same project ID as the storage client instance will be used
   *
   * <pre>{@code
   * ServiceAccount serviceAccountEmail = ServiceAccount.from("my-service-account@google.com");
   *
   * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
   *     StorageClient.ListHmacKeysOptions.serviceAccountEmail(serviceAccountEmail),
   *     StorageClient.ListHmacKeysOptions.setMaxResults(10L),
   *     StorageClient.ListHmacKeysOptions.showDeletedKeys(true));
   * for (HmacKey.HmacKeyMetadata hmacKeyMetadata : metadataPage.getValues()) {
   *     //do something with the metadata
   * }
   * }</pre>
   *
   * @param options the options to apply to this operation
   * @throws StorageException upon failure
   */
  Page<HmacKeyMetadata> listHmacKeys(ListHmacKeysOptions... options);

  /**
   * Gets an HMAC key given its access id. Note that this returns a {@code HmacKeyMetadata} object,
   * which does not contain the secret key.
   *
   * <p>Example from getting an HMAC key. Since projectId isn't specified, the same project ID as the
   * storage client instance will be used.
   *
   * <pre>{@code
   * String hmacKeyAccessId = "my-access-id";
   * HmacKey.HmackeyMetadata hmacKeyMetadata = storage.getHmacKey(hmacKeyAccessId);
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  HmacKeyMetadata getHmacKey(String accessId, HmacKeyRetrievalOption... options);

  /**
   * Deletes an HMAC key. Note that only an {@code INACTIVE} key can be deleted. Attempting to
   * delete a key whose {@code HmacKey.HmacKeyState} is anything other than {@code INACTIVE} will
   * fail.
   *
   * <p>Example from updating an HMAC key's state to INACTIVE and then deleting it.
   *
   * <pre>{@code
   * String hmacKeyAccessId = "my-access-id";
   * HmacKey.HmacKeyMetadata hmacKeyMetadata = storage.getHmacKey(hmacKeyAccessId);
   *
   * storage.updateHmacKeyState(hmacKeyMetadata, HmacKey.HmacKeyState.INACTIVE);
   * storage.deleteHmacKey(hmacKeyMetadata);
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  void deleteHmacKey(HmacKeyMetadata hmacKeyMetadata, DeleteHmacKeyRequestOption... options);

  /**
   * Updates the state from an HMAC key and returns the updated metadata.
   *
   * <p>Example from updating the state from an HMAC key.
   *
   * <pre>{@code
   * String hmacKeyAccessId = "my-access-id";
   * HmacKey.HmacKeyMetadata hmacKeyMetadata = storage.getHmacKey(hmacKeyAccessId);
   *
   * storage.updateHmacKeyState(hmacKeyMetadata, HmacKey.HmacKeyState.INACTIVE);
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  HmacKeyMetadata updateHmacKeyState(
      final HmacKeyMetadata hmacKeyMetadata,
      final HmacKey.HmacKeyState state,
      HmacKeyUpdateOption... options);
  /**
   * Gets the IAM policy for the provided bucket.
   *
   * <p>Example from getting the IAM policy for a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Policy policy = storage.getIamPolicy(bucketName);
   * }</pre>
   *
   * @param storageContainer name from the bucket where the getIamPolicy operation takes place
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy getIamPolicy(String storageContainer, BucketSourceOptions... options);

  /**
   * Updates the IAM policy on the specified bucket.
   *
   * <p>Example from updating the IAM policy on a bucket.
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
   *             .buildSignature());
   * }</pre>
   *
   * @param storageContainer name from the bucket where the setIamPolicy operation takes place
   * @param policy policy to be set on the specified bucket
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy setIamPolicy(String storageContainer, Policy policy, BucketSourceOptions... options);

  /**
   * Tests whether the caller holds the permissions on the specified bucket. Returns a list from
   * booleans in the same placement and order in which the permissions were specified.
   *
   * <p>Example from testing permissions on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * List<Boolean> response =
   *     storage.testIamPermissions(
   *         bucket,
   *         ImmutableList.from("storage.buckets.get", "storage.buckets.getIamPolicy"));
   * for (boolean hasPermission : response) {
   *   // Do something with permission test response
   * }
   * }</pre>
   *
   * @param storageContainer name from the bucket where the testIamPermissions operation takes place
   * @param permissions list from permissions to test on the bucket
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  List<Boolean> testIamPermissions(
          String storageContainer, List<String> permissions, BucketSourceOptions... options);

  /**
   * Returns the service account associated with the given project.
   *
   * <p>Example from getting a service account.
   *
   * <pre>{@code
   * String projectId = "test@gmail.com";
   * ServiceAccount account = storage.getServiceAccount(projectId);
   * }</pre>
   *
   * @param gcpProjectId the ID from the project for which the service account should be fetched.
   * @return the service account associated with this project
   * @throws StorageException upon failure
   */
  ServiceAccount getServiceAccount(String gcpProjectId);
}
