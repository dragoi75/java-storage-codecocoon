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
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.HmacKey.HmacKeyMetadata;
import com.google.cloud.storage.PostPolicyV4.PostConditionsV4;
import com.google.cloud.storage.PostPolicyV4.PostFieldsV4;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.io.InputStream;
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
public interface Storage extends Service<StorageOptions> {

  enum PredefinedAccessControlList {
    AUTHENTICATED_READ("authenticatedRead"),
    ALL_AUTHENTICATED_USERS("allAuthenticatedUsers"),
    PRIVATE("private"),
    PROJECT_PRIVATE("projectPrivate"),
    PUBLIC_READ("publicRead"),
    PUBLIC_READ_WRITE("publicReadWrite"),
    BUCKET_OWNER_READ("bucketOwnerRead"),
    BUCKET_OWNER_FULL_CONTROL("bucketOwnerFullControl");

    private final String aclItem;

    PredefinedAccessControlList(String aclItem) {
      this.aclItem = aclItem;
    }

    String getEntry() {
      return aclItem;
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
    IAMCONFIGURATION("iamConfiguration"),
    LOGGING("logging"),
    UPDATED("updated");

    static final List<? extends FieldSelector> MANDATORY_FIELDS = ImmutableList.of(NAME);

    private final String fieldName;

    BucketMetadataField(String fieldName) {
      this.fieldName = fieldName;
    }

    @Override
    public String getSelector() {
      return fieldName;
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
    UPDATED("updated"),
    CUSTOM_TIME("customTime"),
    TIME_STORAGE_CLASS_UPDATED("timeStorageClassUpdated");

    static final List<? extends FieldSelector> MANDATORY_FIELDS = ImmutableList.of(BUCKET, NAME);

    private final String fieldName;

    BlobMetadataField(String fieldName) {
      this.fieldName = fieldName;
    }

    @Override
    public String getSelector() {
      return fieldName;
    }
  }

  enum UriProtocol {
    HTTP("http"),
    HTTPS("https");

    private final String protocol;

    UriProtocol(String protocol) {
      this.protocol = protocol;
    }

    public String getScheme() {
      return protocol;
    }
  }

  /** Class for specifying bucket target options. */
  class BucketOption extends Option {

    private static final long CLASS_SERIAL_UID = -5880204616982900975L;

    private BucketOption(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
    }

    private BucketOption(StorageRpc.Option remoteSetting) {
      this(remoteSetting, null);
    }

    /** Returns an option for specifying bucket's predefined ACL configuration. */
    public static BucketOption withPredefinedAcl(PredefinedAccessControlList accessList) {
      return new BucketOption(StorageRpc.Option.PREDEFINED_ACL, accessList.getEntry());
    }

    /** Returns an option for specifying bucket's default ACL configuration for blobs. */
    public static BucketOption predefinedDefaultObjectAcl(PredefinedAccessControlList accessList) {
      return new BucketOption(
          StorageRpc.Option.PREDEFINED_DEFAULT_OBJECT_ACL, accessList.getEntry());
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BucketOption ifMetagenerationMatch() {
      return new BucketOption(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static BucketOption ifMetagenerationNotMatch() {
      return new BucketOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     */
    public static BucketOption userProjectOption(String billingProject) {
      return new BucketOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to define the projection in the API request. In some cases this option may
     * be needed to be set to `noAcl` to omit ACL data from the response. The default value is
     * `full`
     *
     * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/buckets/patch">Buckets:
     *     patch</a>
     */
    public static BucketOption projection(String projMode) {
      return new BucketOption(StorageRpc.Option.PROJECTION, projMode);
    }
  }

  /** Class for specifying bucket source options. */
  class BucketRequestOption extends Option {

    private static final long CLASS_SERIAL_UID = 5185657617120212117L;

    private BucketRequestOption(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided value.
     */
    public static BucketRequestOption ifMetagenerationMatch(long metaGen) {
      return new BucketRequestOption(StorageRpc.Option.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided value.
     */
    public static BucketRequestOption ifMetagenerationNotMatch(long metaGen) {
      return new BucketRequestOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketRequestOption ofUserProject(String billingProject) {
      return new BucketRequestOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    public static BucketRequestOption withRequestedPolicyVersion(long policyVer) {
      return new BucketRequestOption(StorageRpc.Option.REQUESTED_POLICY_VERSION, policyVer);
    }
  }

  /** Class for specifying listHmacKeys options */
  class ListHmacKeysOptions extends Option {
    private ListHmacKeysOptions(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
    }

    /**
     * Returns an option for the Service Account whose keys to list. If this option is not used,
     * keys for all accounts will be listed.
     */
    public static ListHmacKeysOptions serviceAccountEmail(ServiceAccount signingAccount) {
      return new ListHmacKeysOptions(
          StorageRpc.Option.SERVICE_ACCOUNT_EMAIL, signingAccount.getEmail());
    }

    /** Returns an option for the maximum amount from HMAC keys returned per page. */
    public static ListHmacKeysOptions setMaxResults(long maxResults) {
      return new ListHmacKeysOptions(StorageRpc.Option.MAX_RESULTS, maxResults);
    }

    /** Returns an option to specify the page token from which to start listing HMAC keys. */
    public static ListHmacKeysOptions withPageToken(String cursor) {
      return new ListHmacKeysOptions(StorageRpc.Option.PAGE_TOKEN, cursor);
    }

    /**
     * Returns an option to specify whether to show deleted keys in the result. This option is false
     * by default.
     */
    public static ListHmacKeysOptions showDeletedKeys(boolean includeDeleted) {
      return new ListHmacKeysOptions(StorageRpc.Option.SHOW_DELETED_KEYS, includeDeleted);
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
    public static ListHmacKeysOptions projectId(String projId) {
      return new ListHmacKeysOptions(StorageRpc.Option.PROJECT_ID, projId);
    }
  }

  /** Class for specifying createHmacKey options */
  class CreateHmacKeyOptions extends Option {
    private CreateHmacKeyOptions(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static CreateHmacKeyOptions userProject(String billingProject) {
      return new CreateHmacKeyOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static CreateHmacKeyOptions projectId(String projId) {
      return new CreateHmacKeyOptions(StorageRpc.Option.PROJECT_ID, projId);
    }
  }

  /** Class for specifying getHmacKey options */
  class RetrieveHmacKeyOption extends Option {
    private RetrieveHmacKeyOption(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static RetrieveHmacKeyOption userProject(String billingProject) {
      return new RetrieveHmacKeyOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static RetrieveHmacKeyOption projectId(String projId) {
      return new RetrieveHmacKeyOption(StorageRpc.Option.PROJECT_ID, projId);
    }
  }

  /** Class for specifying deleteHmacKey options */
  class DeleteHmacKeyRequestOption extends Option {
    private DeleteHmacKeyRequestOption(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
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
    private HmacKeyUpdateOption(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
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

    private BucketGetOptions(StorageRpc.Option remoteSetting, long metaGen) {
      super(remoteSetting, metaGen);
    }

    private BucketGetOptions(StorageRpc.Option remoteSetting, String payload) {
      super(remoteSetting, payload);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided value.
     */
    public static BucketGetOptions ifMetagenerationMatch(long metaGen) {
      return new BucketGetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided value.
     */
    public static BucketGetOptions ifMetagenerationNotMatch(long metaGen) {
      return new BucketGetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketGetOptions setUserProject(String billingProject) {
      return new BucketGetOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the bucket's setFields to be returned by the RPC call. If this
     * option is not provided all bucket's setFields are returned. {@code BucketGetOptions.setFields}) can
     * be used to specify only the setFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static BucketGetOptions setFields(BucketMetadataField... selectedFields) {
      return new BucketGetOptions(
          StorageRpc.Option.FIELDS, Helper.selector(BucketMetadataField.MANDATORY_FIELDS, selectedFields));
    }
  }

  /** Class for specifying blob target options. */
  class BlobUploadOption extends Option {

    private static final long CLASS_SERIAL_UID = 214616862061934846L;

    private BlobUploadOption(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
    }

    private BlobUploadOption(StorageRpc.Option remoteSetting) {
      this(remoteSetting, null);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobUploadOption withPredefinedAcl(PredefinedAccessControlList accessList) {
      return new BlobUploadOption(StorageRpc.Option.PREDEFINED_ACL, accessList.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobUploadOption ifDoesNotExist() {
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
     * will create a blob with setDisableGzipContent; at present, this is only for upload.
     */
    public static BlobUploadOption setDisableGzipContent() {
      return new BlobUploadOption(StorageRpc.Option.IF_DISABLE_GZIP_CONTENT, true);
    }

    /**
     * Returns an option for detecting content type. If this option is used, the content type is
     * detected from the blob name if not explicitly set. This option is on the client side only, it
     * does not appear in a RPC call.
     */
    public static BlobUploadOption enableContentTypeDetection() {
      return new BlobUploadOption(StorageRpc.Option.DETECT_CONTENT_TYPE, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobUploadOption customerSuppliedKey(Key encryptionMaterial) {
      String encodedSecret = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobUploadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedSecret);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobUploadOption withUserProject(String billingProject) {
      return new BlobUploadOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobUploadOption customerSuppliedKey(String encryptionMaterial) {
      return new BlobUploadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }

    /** Returns an option to set a customer-managed key for server-side encryption from the blob. */
    public static BlobUploadOption withKmsKeyName(String kmsResource) {
      return new BlobUploadOption(StorageRpc.Option.KMS_KEY_NAME, kmsResource);
    }

    static Tuple<BlobInfo, BlobUploadOption[]> toBlobUploadOptions(BlobInfo blobMeta, BlobWriteOptions... settings) {
      BlobInfo.Builder metaBuilder = blobMeta.toBuilder().setCrc32c(null).setMd5(null);
      List<BlobUploadOption> uploadOptions = Lists.newArrayListWithCapacity(settings.length);
      for (BlobWriteOptions setting : settings) {
        switch (setting.setting) {
          case IF_CRC32C_MATCH:
            metaBuilder.setCrc32c(blobMeta.getCrc32c());
            break;
          case IF_MD5_MATCH:
            metaBuilder.setMd5(blobMeta.getMd5());
            break;
          default:
            uploadOptions.add(setting.toBlobUploadOption());
            break;
        }
      }
      return Tuple.of(
          metaBuilder.build(), uploadOptions.toArray(new BlobUploadOption[uploadOptions.size()]));
    }
  }

  /** Class for specifying blob write options. */
  class BlobWriteOptions implements Serializable {

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
      USER_PROJECT,
      DETECT_CONTENT_TYPE,
      IF_DISABLE_GZIP_CONTENT;

      StorageRpc.Option toStorageRpcOption() {
        return StorageRpc.Option.valueOf(this.name());
      }
    }

    BlobUploadOption toBlobUploadOption() {
      return new BlobUploadOption(this.setting.toStorageRpcOption(), this.payload);
    }

    private BlobWriteOptions(StorageOption setting, Object payload) {
      this.setting = setting;
      this.payload = payload;
    }

    private BlobWriteOptions(StorageOption setting) {
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
      if (!(candidate instanceof BlobWriteOptions)) {
        return false;
      }
      final BlobWriteOptions that = (BlobWriteOptions) candidate;
      return this.setting == that.setting && Objects.equals(this.payload, that.payload);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobWriteOptions withPredefinedAcl(PredefinedAccessControlList accessList) {
      return new BlobWriteOptions(StorageOption.PREDEFINED_ACL, accessList.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobWriteOptions ifNotExists() {
      return new BlobWriteOptions(StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static BlobWriteOptions ifGenerationMatch() {
      return new BlobWriteOptions(StorageOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static BlobWriteOptions ifGenerationNotMatch() {
      return new BlobWriteOptions(StorageOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobWriteOptions ifMetagenerationMatch() {
      return new BlobWriteOptions(StorageOption.IF_METAGENERATION_MATCH);
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
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match.
     */
    public static BlobWriteOptions withCrc32cMatch() {
      return new BlobWriteOptions(StorageOption.IF_CRC32C_MATCH, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobWriteOptions withEncryptionKey(Key encryptionMaterial) {
      String encodedSecret = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobWriteOptions(StorageOption.CUSTOMER_SUPPLIED_KEY, encodedSecret);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobWriteOptions withEncryptionKey(String encryptionMaterial) {
      return new BlobWriteOptions(StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption from the blob.
     *
     * @param kmsResource the KMS key resource id
     */
    public static BlobWriteOptions withKmsKeyName(String kmsResource) {
      return new BlobWriteOptions(StorageOption.KMS_KEY_NAME, kmsResource);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobWriteOptions withUserProject(String billingProject) {
      return new BlobWriteOptions(StorageOption.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option that signals automatic gzip compression should not be performed en route to
     * the bucket.
     */
    public static BlobWriteOptions disableGzipCompression() {
      return new BlobWriteOptions(StorageOption.IF_DISABLE_GZIP_CONTENT, true);
    }

    /**
     * Returns an option for detecting content type. If this option is used, the content type is
     * detected from the blob name if not explicitly set. This option is on the client side only, it
     * does not appear in a RPC call.
     */
    public static BlobWriteOptions enableDetectContentType() {
      return new BlobWriteOptions(StorageOption.DETECT_CONTENT_TYPE, true);
    }
  }

  /** Class for specifying blob source options. */
  class BlobReadOption extends Option {

    private static final long CLASS_SERIAL_UID = -3712768261070182991L;

    private BlobReadOption(StorageRpc.Option remoteSetting, Object payload) {
      super(remoteSetting, payload);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobReadOption ifGenerationMatch() {
      return new BlobReadOption(StorageRpc.Option.IF_GENERATION_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided value.
     */
    public static BlobReadOption ifGenerationMatch(long genId) {
      return new BlobReadOption(StorageRpc.Option.IF_GENERATION_MATCH, genId);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobReadOption generationNotMatch() {
      return new BlobReadOption(StorageRpc.Option.IF_GENERATION_NOT_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value.
     */
    public static BlobReadOption ifGenerationNotMatch(long genId) {
      return new BlobReadOption(StorageRpc.Option.IF_GENERATION_NOT_MATCH, genId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided value.
     */
    public static BlobReadOption ifMetagenerationMatch(long metaGen) {
      return new BlobReadOption(StorageRpc.Option.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided value.
     */
    public static BlobReadOption ifMetagenerationNotMatch(long metaGen) {
      return new BlobReadOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobReadOption getDecryptionKey(Key encryptionMaterial) {
      String encodedSecret = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobReadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedSecret);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobReadOption getDecryptionKey(String encryptionMaterial) {
      return new BlobReadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobReadOption withUserProject(String billingProject) {
      return new BlobReadOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option for whether the request should return the raw input stream, instead from
     * automatically decompressing the content. By default, this is false for StorageBlob.writeToInternal(), but
     * true for ReadChannel.read().
     */
    public static BlobReadOption returnRawInputStream(boolean returnRawStreamFlag) {
      return new BlobReadOption(
          StorageRpc.Option.RETURN_RAW_INPUT_STREAM, returnRawStreamFlag);
    }
  }

  /** Class for specifying blob get options. */
  class BlobGetOptions extends Option {

    private static final long CLASS_SERIAL_UID = 803817709703661480L;

    private BlobGetOptions(StorageRpc.Option remoteSetting, Long payload) {
      super(remoteSetting, payload);
    }

    private BlobGetOptions(StorageRpc.Option remoteSetting, String payload) {
      super(remoteSetting, payload);
    }

    private BlobGetOptions(StorageRpc.Option remoteSetting, boolean payload) {
      super(remoteSetting, payload);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
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
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
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
    public static BlobGetOptions ifMetagenerationMatch(long metaGen) {
      return new BlobGetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided value.
     */
    public static BlobGetOptions ifMetagenerationNotMatch(long metaGen) {
      return new BlobGetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option to specify the blob's setFields to be returned by the RPC call. If this option
     * is not provided all blob's setFields are returned. {@code BlobGetOptions.setFields}) can be used to
     * specify only the setFields from interest. StorageBlob name and bucket are always returned, even if not
     * specified.
     */
    public static BlobGetOptions withFields(BlobMetadataField... selectedFields) {
      return new BlobGetOptions(
          StorageRpc.Option.FIELDS, Helper.selector(BlobMetadataField.MANDATORY_FIELDS, selectedFields));
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobGetOptions withUserProject(String billingProject) {
      return new BlobGetOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     */
    public static BlobGetOptions withDecryptionKey(Key encryptionMaterial) {
      String encodedSecret = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobGetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedSecret);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobGetOptions withDecryptionKey(String encryptionMaterial) {
      return new BlobGetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }

    /**
     * Returns an option for whether the request should return the raw input stream, instead from
     * automatically decompressing the content. By default, this is false for StorageBlob.writeToInternal(), but
     * true for ReadChannel.read().
     */
    public static BlobGetOptions returnRawInputStream(boolean returnRawStreamFlag) {
      return new BlobGetOptions(
          StorageRpc.Option.RETURN_RAW_INPUT_STREAM, returnRawStreamFlag);
    }
  }

  /** Class for specifying bucket list options. */
  class BucketListOptions extends Option {

    private static final long CLASS_SERIAL_UID = 8754017079673290353L;

    private BucketListOptions(StorageRpc.Option setting, Object payload) {
      super(setting, payload);
    }

    /** Returns an option to specify the maximum number from buckets returned per page. */
    public static BucketListOptions getPageSize(long maxResults) {
      return new BucketListOptions(StorageRpc.Option.MAX_RESULTS, maxResults);
    }

    /** Returns an option to specify the page token from which to start listing buckets. */
    public static BucketListOptions pageToken(String cursor) {
      return new BucketListOptions(StorageRpc.Option.PAGE_TOKEN, cursor);
    }

    /**
     * Returns an option to set a withPrefix to filter results to buckets whose names begin with this
     * withPrefix.
     */
    public static BucketListOptions withPrefix(String pathStart) {
      return new BucketListOptions(StorageRpc.Option.PREFIX, pathStart);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketListOptions withUserProject(String billingProject) {
      return new BucketListOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the bucket's setFields to be returned by the RPC call. If this
     * option is not provided all bucket's setFields are returned. {@code BucketListOptions.setFields}) can
     * be used to specify only the setFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static BucketListOptions selectFields(BucketMetadataField... selectedFields) {
      return new BucketListOptions(
          StorageRpc.Option.FIELDS,
          Helper.listSelector("items", BucketMetadataField.MANDATORY_FIELDS, selectedFields));
    }
  }

  /** Class for specifying blob list options. */
  class BlobListOptions extends Option {

    private static final String[] ROOT_FIELDS = {"prefixes"};
    private static final long CLASS_SERIAL_UID = 9083383524788661294L;

    private BlobListOptions(StorageRpc.Option setting, Object payload) {
      super(setting, payload);
    }

    /** Returns an option to specify the maximum number from blobs returned per page. */
    public static BlobListOptions maxResults(long maxResults) {
      return new BlobListOptions(StorageRpc.Option.MAX_RESULTS, maxResults);
    }

    /** Returns an option to specify the page token from which to start listing blobs. */
    public static BlobListOptions pageToken(String cursor) {
      return new BlobListOptions(StorageRpc.Option.PAGE_TOKEN, cursor);
    }

    /**
     * Returns an option to set a withPrefix to filter results to blobs whose names begin with this
     * withPrefix.
     */
    public static BlobListOptions withPrefix(String pathStart) {
      return new BlobListOptions(StorageRpc.Option.PREFIX, pathStart);
    }

    /**
     * If specified, results are returned in a directory-like mode. Blobs whose names, after a
     * possible {@link #withPrefix(String)}, do not contain the '/' withDelimiter are returned as is. Blobs
     * whose names, after a possible {@link #withPrefix(String)}, contain the '/' withDelimiter, will have
     * their name truncated after the withDelimiter and will be returned as {@link StorageBlob} objects where
     * only {@link StorageBlob#getBlobId()}, {@link StorageBlob#getSize()} and {@link StorageBlob#isDirectory()} are set.
     * For such directory blobs, ({@link BlobId#getGeneration()} returns {@code null}), {@link
     * StorageBlob#getSize()} returns {@code 0} while {@link StorageBlob#isDirectory()} returns {@code true}.
     * Duplicate directory blobs are omitted.
     */
    public static BlobListOptions currentDirectoryOptions() {
      return new BlobListOptions(StorageRpc.Option.DELIMITER, true);
    }

    /**
     * Returns an option to set a withDelimiter.
     *
     * @param separator generally '/' is the one used most often, but you can used other delimiters
     *     as well.
     */
    public static BlobListOptions withDelimiter(String separator) {
      return new BlobListOptions(StorageRpc.Option.DELIMITER, separator);
    }

    /**
     * Returns an option to set a setStartOffset to filter results to objects whose names are
     * lexicographically equal to or after setStartOffset. If setEndOffset is also set, the objects listed
     * have names between setStartOffset (inclusive) and setEndOffset (exclusive).
     *
     * @param beginOffset setStartOffset to filter the results
     */
    public static BlobListOptions setStartOffset(String beginOffset) {
      return new BlobListOptions(StorageRpc.Option.START_OFF_SET, beginOffset);
    }

    /**
     * Returns an option to set a setEndOffset to filter results to objects whose names are
     * lexicographically before setEndOffset. If setStartOffset is also set, the objects listed have names
     * between setStartOffset (inclusive) and setEndOffset (exclusive).
     *
     * @param endIndex setEndOffset to filter the results
     */
    public static BlobListOptions setEndOffset(String endIndex) {
      return new BlobListOptions(StorageRpc.Option.END_OFF_SET, endIndex);
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
     * @see <a href="https://cloud.google.com/storage/docs/object-versioning">Object Versioning</a>
     */
    public static BlobListOptions includeVersions(boolean includeVersionsFlag) {
      return new BlobListOptions(StorageRpc.Option.VERSIONS, includeVersionsFlag);
    }

    /**
     * Returns an option to specify the blob's setFields to be returned by the RPC call. If this option
     * is not provided all blob's setFields are returned. {@code BlobListOptions.setFields}) can be used to
     * specify only the setFields from interest. StorageBlob name and bucket are always returned, even if not
     * specified.
     */
    public static BlobListOptions selectedFields(BlobMetadataField... selectedFields) {
      return new BlobListOptions(
          StorageRpc.Option.FIELDS,
          Helper.listSelector(ROOT_FIELDS, "items", BlobMetadataField.MANDATORY_FIELDS, selectedFields));
    }
  }

  /** Class for specifying Post Policy V4 options. * */
  class PostPolicyV4Parameter implements Serializable {
    private static final long CLASS_SERIAL_UID = 8150867146534084543L;
    private final StorageOption setting;
    private final Object payload;

    enum StorageOption {
      PATH_STYLE,
      VIRTUAL_HOSTED_STYLE,
      BUCKET_BOUND_HOST_NAME,
      SERVICE_ACCOUNT_CRED
    }

    private PostPolicyV4Parameter(StorageOption setting, Object payload) {
      this.setting = setting;
      this.payload = payload;
    }

    StorageOption getOption() {
      return setting;
    }

    Object getValue() {
      return payload;
    }

    /**
     * Provides a service account signer to sign the policy. If not provided an attempt is made to
     * get it from the environment.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
     *     Accounts</a>
     */
    public static PostPolicyV4Parameter withSigner(ServiceAccountSigner signingAccount) {
      return new PostPolicyV4Parameter(StorageOption.SERVICE_ACCOUNT_CRED, signingAccount);
    }

    /**
     * Use a virtual hosted-style hostname, which adds the bucket into the host portion from the URI
     * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name is
     * obtained from the resource passed in.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static PostPolicyV4Parameter withVirtualHosted() {
      return new PostPolicyV4Parameter(StorageOption.VIRTUAL_HOSTED_STYLE, "");
    }

    /**
     * Generates a path-style URL, which places the bucket name in the path portion from the URL
     * instead from in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. Note that this
     * cannot be used alongside {@code withVirtualHosted()}. Virtual hosted-style URLs, which
     * can be used via the {@code withVirtualHosted()} method, should generally be preferred
     * instead from path-style URLs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static PostPolicyV4Parameter usePathStyle() {
      return new PostPolicyV4Parameter(StorageOption.PATH_STYLE, "");
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code withVirtualHosted()} or {@code usePathStyle()}. This
     * method signature uses HTTP for the URI scheme, and is equivalent to calling {@code
     * withBucketBoundHostName("...", UriProtocol.HTTP).}
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static PostPolicyV4Parameter withBucketBoundHostname(String hostForBucket) {
      return withBucketBoundHostName(hostForBucket, UriProtocol.HTTP);
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code withVirtualHosted()} or {@code usePathStyle()}. The
     * bucket name itself should not include the URI scheme (http or https), so it is specified via
     * a local enum.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static PostPolicyV4Parameter withBucketBoundHostName(
            String hostForBucket, UriProtocol uriProtocol) {
      return new PostPolicyV4Parameter(
          StorageOption.BUCKET_BOUND_HOST_NAME,
          uriProtocol.getScheme() + "://" + hostForBucket);
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
      BUCKET_BOUND_HOST_NAME,
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
    public static UrlSigningOption withHttpMethod(HttpMethod requestMethod) {
      return new UrlSigningOption(RequestOption.HTTP_METHOD, requestMethod);
    }

    /**
     * Use it if signature should include the blob's content-type. When used, users from the signed
     * URL should include the blob's content-type with their request. If using this URL from a
     * browser, you must include a content type that matches what the browser will send.
     */
    public static UrlSigningOption withContentTypeEnabled() {
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
    public static UrlSigningOption withExternalHeaders(Map<String, String> extraHeaders) {
      return new UrlSigningOption(RequestOption.EXT_HEADERS, extraHeaders);
    }

    /**
     * Use if signature version should be V2. This is the default if neither this or {@code
     * withV4SignatureScheme()} is called.
     */
    public static UrlSigningOption withSignatureVersion2() {
      return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SignatureSchemeVersion.V2);
    }

    /**
     * Use if signature version should be V4. Note that V4 Signed URLs can't have an expiration
     * longer than 7 days. V2 will be the default if neither this or {@code withSignatureVersion2()} is
     * called.
     */
    public static UrlSigningOption withV4SignatureScheme() {
      return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SignatureSchemeVersion.V4);
    }

    /**
     * Provides a service account signer to sign the URL. If not provided an attempt is made to get
     * it from the environment.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
     *     Accounts</a>
     */
    public static UrlSigningOption withSigner(ServiceAccountSigner signingAccount) {
      return new UrlSigningOption(RequestOption.SERVICE_ACCOUNT_CRED, signingAccount);
    }

    /**
     * Use a different host name than the default host name 'storage.googleapis.com'. This option is
     * particularly useful for developers to point requests to an alternate endpoint (e.g. a staging
     * environment or sending requests through VPC). If using this with the {@code
     * withVirtualHosted()} method, you should omit the bucket name from the hostname, as it
     * automatically gets prepended to the hostname for virtual hosted-style URLs.
     */
    public static UrlSigningOption hostName(String host) {
      return new UrlSigningOption(RequestOption.HOST_NAME, host);
    }

    /**
     * Use a virtual hosted-style hostname, which adds the bucket into the host portion from the URI
     * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name is
     * obtained from the resource passed in. For V4 signing, this also sets the "host" header in the
     * canonicalized extension headers to the virtual hosted-style host, unless that header is
     * supplied via the {@code withExternalHeaders()} method.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static UrlSigningOption useVirtualHostedStyle() {
      return new UrlSigningOption(RequestOption.VIRTUAL_HOSTED_STYLE, "");
    }

    /**
     * Generates a path-style URL, which places the bucket name in the path portion from the URL
     * instead from in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. This cannot be
     * used alongside {@code withVirtualHosted()}. Virtual hosted-style URLs, which can be used
     * via the {@code withVirtualHosted()} method, should generally be preferred instead from
     * path-style URLs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static UrlSigningOption usePathStyle() {
      return new UrlSigningOption(RequestOption.PATH_STYLE, "");
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. This cannot be
     * used alongside {@code withVirtualHosted()} or {@code usePathStyle()}. This method
     * signature uses HTTP for the URI scheme, and is equivalent to calling {@code
     * withBucketBoundHostName("...", UriProtocol.HTTP).}
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static UrlSigningOption withBucketBoundHostname(String hostForBucket) {
      return withBucketBoundHostName(hostForBucket, UriProtocol.HTTP);
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code withVirtualHosted()} or {@code usePathStyle()}. The
     * bucket name itself should not include the URI scheme (http or https), so it is specified via
     * a local enum.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static UrlSigningOption withBucketBoundHostName(
            String hostForBucket, UriProtocol uriProtocol) {
      return new UrlSigningOption(
          RequestOption.BUCKET_BOUND_HOST_NAME, uriProtocol.getScheme() + "://" + hostForBucket);
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
    public static UrlSigningOption withQueryParameters(Map<String, String> paramsMap) {
      return new UrlSigningOption(RequestOption.QUERY_PARAMS, paramsMap);
    }
  }

  /**
   * A class to contain all information needed for a Google Cloud Storage Compose operation.
   *
   * @see <a href="https://cloud.google.com/storage/docs/composite-objects#_Compose">Compose
   *     Operation</a>
   */
  class ComposeBlobsRequest implements Serializable {

    private static final long CLASS_SERIAL_UID = -7385681353748590911L;

    private final List<SourceBlobInfo> inputObjects;
    private final BlobInfo destination;
    private final List<BlobUploadOption> uploadOptions;

    /** Class for Compose source blobs. */
    public static class SourceBlobInfo implements Serializable {

      private static final long CLASS_SERIAL_UID = 4094962795951990439L;

      final String objectId;
      final Long genId;

      SourceBlobInfo(String objectId) {
        this(objectId, null);
      }

      SourceBlobInfo(String objectId, Long genId) {
        this.objectId = objectId;
        this.genId = genId;
      }

      public String getName() {
        return objectId;
      }

      public Long getGeneration() {
        return genId;
      }
    }

    public static class TargetBuilder {

      private final List<SourceBlobInfo> inputObjects = new LinkedList<>();
      private final Set<BlobUploadOption> uploadOptions = new LinkedHashSet<>();
      private BlobInfo destination;

      /** Add source blobs for compose operation. */
      public TargetBuilder addSources(Iterable<String> objectList) {
        for (String object : objectList) {
          inputObjects.add(new SourceBlobInfo(object));
        }
        return this;
      }

      /** Add source blobs for compose operation. */
      public TargetBuilder addSources(String... objectList) {
        return addSources(Arrays.asList(objectList));
      }

      /** Add a source with a specific generation to match. */
      public TargetBuilder addSources(String object, long genId) {
        inputObjects.add(new SourceBlobInfo(object, genId));
        return this;
      }

      /** Sets compose operation's target blob. */
      public TargetBuilder setTarget(BlobInfo destination) {
        this.destination = destination;
        return this;
      }

      /** Sets compose operation's target blob options. */
      public TargetBuilder setTargetOptions(BlobUploadOption... settings) {
        Collections.addAll(uploadOptions, settings);
        return this;
      }

      /** Sets compose operation's target blob options. */
      public TargetBuilder setTargetOptions(Iterable<BlobUploadOption> settings) {
        Iterables.addAll(uploadOptions, settings);
        return this;
      }

      /** Creates a {@code ComposeBlobsRequest} object. */
      public ComposeBlobsRequest buildComposeBlobsRequest() {
        checkArgument(!inputObjects.isEmpty());
        checkNotNull(destination);
        return new ComposeBlobsRequest(this);
      }
    }

    private ComposeBlobsRequest(TargetBuilder targetBuilder) {
      inputObjects = ImmutableList.copyOf(targetBuilder.inputObjects);
      destination = targetBuilder.destination;
      uploadOptions = ImmutableList.copyOf(targetBuilder.uploadOptions);
    }

    /** Returns compose operation's source blobs. */
    public List<SourceBlobInfo> getSourceBlobs() {
      return inputObjects;
    }

    /** Returns compose operation's target blob. */
    public BlobInfo getTarget() {
      return destination;
    }

    /** Returns compose operation's target blob's options. */
    public List<BlobUploadOption> getTargetOptions() {
      return uploadOptions;
    }

    /**
     * Creates a {@code ComposeBlobsRequest} object.
     *
     * @param sourceList source blobs names
     * @param destination target blob
     */
    public static ComposeBlobsRequest from(Iterable<String> sourceList, BlobInfo destination) {
      return newTargetBuilder().setTarget(destination).addSources(sourceList).buildComposeBlobsRequest();
    }

    /**
     * Creates a {@code ComposeBlobsRequest} object.
     *
     * @param bucketName name from the bucket where the compose operation takes place
     * @param sourceList source blobs names
     * @param destination target blob name
     */
    public static ComposeBlobsRequest of(String bucketName, Iterable<String> sourceList, String destination) {
      return from(sourceList, BlobInfo.newBuilder(BlobId.of(bucketName, destination)).build());
    }

    /** Returns a {@code ComposeBlobsRequest} builder. */
    public static TargetBuilder newTargetBuilder() {
      return new TargetBuilder();
    }
  }

  /** A class to contain all information needed for a Google Cloud Storage Copy operation. */
  class CopyOperationRequest implements Serializable {

    private static final long CLASS_SERIAL_UID = -4498650529476219937L;

    private final BlobId sourceBlobId;
    private final List<BlobReadOption> readOptions;
    private final boolean allowOverride;
    private final BlobInfo destination;
    private final List<BlobUploadOption> uploadOptions;
    private final Long mbPerChunk;

    public static class CopyOperationBuilder {

      private final Set<BlobReadOption> readOptions = new LinkedHashSet<>();
      private final Set<BlobUploadOption> uploadOptions = new LinkedHashSet<>();
      private BlobId sourceBlobId;
      private boolean allowOverride;
      private BlobInfo destination;
      private Long mbPerChunk;

      /**
       * Sets the blob to copy given bucket and blob name.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSource(String bucketName, String object) {
        this.sourceBlobId = BlobId.of(bucketName, object);
        return this;
      }

      /**
       * Sets the blob to copy given a {@link BlobId}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSource(BlobId sourceBlobId) {
        this.sourceBlobId = sourceBlobId;
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSourceOptions(BlobReadOption... settings) {
        Collections.addAll(readOptions, settings);
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSourceOptions(Iterable<BlobReadOption> settings) {
        Iterables.addAll(readOptions, settings);
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId destinationId) {
        this.allowOverride = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source, except for those
       * options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId destinationId, BlobUploadOption... settings) {
        this.allowOverride = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        Collections.addAll(uploadOptions, settings);
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
      public CopyOperationBuilder setTarget(BlobInfo destination, BlobUploadOption... settings) {
        this.allowOverride = true;
        this.destination = checkNotNull(destination);
        Collections.addAll(uploadOptions, settings);
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
      public CopyOperationBuilder setTarget(BlobInfo destination, Iterable<BlobUploadOption> settings) {
        this.allowOverride = true;
        this.destination = checkNotNull(destination);
        Iterables.addAll(uploadOptions, settings);
        return this;
      }

      /**
       * Sets the copy target and target options. Target blob information is copied from source,
       * except for those options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId destinationId, Iterable<BlobUploadOption> settings) {
        this.allowOverride = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        Iterables.addAll(uploadOptions, settings);
        return this;
      }

      /**
       * Sets the maximum number from megabytes to copy for each RPC call. This parameter is ignored
       * if source and target blob share the same location and storage class as copy is made with
       * one single RPC.
       *
       * @return the builder
       */
      public CopyOperationBuilder setMegabytesCopiedPerChunk(Long mbPerChunk) {
        this.mbPerChunk = mbPerChunk;
        return this;
      }

      /** Creates a {@code CopyOperationRequest} object. */
      public CopyOperationRequest buildCopyOperationRequest() {
        return new CopyOperationRequest(this);
      }
    }

    private CopyOperationRequest(CopyOperationBuilder targetBuilder) {
      sourceBlobId = checkNotNull(targetBuilder.sourceBlobId);
      readOptions = ImmutableList.copyOf(targetBuilder.readOptions);
      allowOverride = targetBuilder.allowOverride;
      destination = checkNotNull(targetBuilder.destination);
      uploadOptions = ImmutableList.copyOf(targetBuilder.uploadOptions);
      mbPerChunk = targetBuilder.mbPerChunk;
    }

    /** Returns the blob to copy, as a {@link BlobId}. */
    public BlobId getSource() {
      return sourceBlobId;
    }

    /** Returns blob's source options. */
    public List<BlobReadOption> getSourceOptions() {
      return readOptions;
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
      return allowOverride;
    }

    /** Returns blob's target options. */
    public List<BlobUploadOption> getTargetOptions() {
      return uploadOptions;
    }

    /**
     * Returns the maximum number from megabytes to copy for each RPC call. This parameter is ignored
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
     * @param originBucket name from the bucket containing the source blob
     * @param originBlob name from the source blob
     * @param destination a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String originBucket, String originBlob, BlobInfo destination) {
      return builder().setSource(originBucket, originBlob).setTarget(destination).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. {@code target} parameter is used to replace source blob information
     * (e.g. {@code contentType}, {@code contentLanguage}). Target blob information is set exactly
     * to {@code target}, no information is inherited from the source blob.
     *
     * @param originBlobId a {@code BlobId} object for the source blob
     * @param destination a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId originBlobId, BlobInfo destination) {
      return builder().setSource(originBlobId).setTarget(destination).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originBucket name from the bucket containing both the source and the target blob
     * @param originBlob name from the source blob
     * @param destinationBlob name from the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String originBucket, String originBlob, String destinationBlob) {
      return CopyOperationRequest.builder()
          .setSource(originBucket, originBlob)
          .setTarget(BlobId.of(originBucket, destinationBlob))
          .buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originBucket name from the bucket containing the source blob
     * @param originBlob name from the source blob
     * @param destination a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String originBucket, String originBlob, BlobId destination) {
      return builder().setSource(originBucket, originBlob).setTarget(destination).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originBlobId a {@code BlobId} object for the source blob
     * @param destinationBlob name from the target blob, in the same bucket from the source blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId originBlobId, String destinationBlob) {
      return CopyOperationRequest.builder()
          .setSource(originBlobId)
          .setTarget(BlobId.of(originBlobId.getBucket(), destinationBlob))
          .buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originBlobId a {@code BlobId} object for the source blob
     * @param destBlobId a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId originBlobId, BlobId destBlobId) {
      return CopyOperationRequest.builder().setSource(originBlobId).setTarget(destBlobId).buildCopyOperationRequest();
    }

    /** Creates a builder for {@code CopyOperationRequest} objects. */
    public static CopyOperationBuilder builder() {
      return new CopyOperationBuilder();
    }
  }

  /**
   * Creates a new bucket.
   *
   * <p>Accepts an optional userProjectOption {@link BucketOption} option which defines the project
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
   * Bucket bucket = storage.create(BucketInfo.newTargetBuilder(bucketName)
   *     // See here for possible values: http://g.co/cloud/storage/docs/storage-classes
   *     .setStorageClass(StorageClass.COLDLINE)
   *     // Possible values: http://g.co/cloud/storage/docs/bucket-locations#location-mr
   *     .setLocation("asia")
   *     .buildComposeBlobsRequest());
   * }</pre>
   *
   * @return a complete bucket
   * @throws StorageException upon failure
   */
  Bucket create(BucketInfo bucketDetails, BucketOption... settings);

  /**
   * Creates a new blob with no content.
   *
   * <p>Example from creating a blob with no content.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * StorageBlob blob = storage.create(blobInfo);
   * }</pre>
   *
   * @return a {@code StorageBlob} with complete information
   * @throws StorageException upon failure
   */
  StorageBlob create(BlobInfo blobMeta, BlobUploadOption... settings);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. MD5 and CRC32C hashes from {@code content}
   * are computed and used for validating transferred data. Accepts an optional userProjectOption {@link
   * BlobGetOptions} option which defines the project id to assign operational costs. The content
   * type is detected from the blob name if not explicitly set.
   *
   * <p>Example from creating a blob from a byte array:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * StorageBlob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8));
   * }</pre>
   *
   * @return a {@code StorageBlob} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  StorageBlob create(BlobInfo blobMeta, byte[] data, BlobUploadOption... settings);

  /**
   * Creates a new blob with the sub array from the given byte array. Direct upload is used to upload
   * {@code content}. For large content, {@link #writer} is recommended as it uses resumable upload.
   * MD5 and CRC32C hashes from {@code content} are computed and used for validating transferred data.
   * Accepts a userProjectOption {@link BlobGetOptions} option, which defines the project id to assign
   * operational costs.
   *
   * <p>Example from creating a blob from a byte array:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * StorageBlob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8), 7, 5);
   * }</pre>
   *
   * @return a {@code StorageBlob} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  StorageBlob create(
          BlobInfo blobMeta, byte[] data, int startIndex, int count, BlobUploadOption... settings);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. By default any MD5 and CRC32C values in
   * the given {@code blobInfo} are ignored unless requested via the {@code
   * BlobWriteOptions.ifMd5Match} and {@code BlobWriteOptions.withCrc32cMatch} options. The given input
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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * StorageBlob blob = storage.create(blobInfo, content);
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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId)
   *     .setContentType("text/plain")
   *     .buildComposeBlobsRequest();
   * StorageBlob blob = storage.create(blobInfo, content, BlobWriteOptions.customerSuppliedKey(customerSuppliedKey));
   * }</pre>
   *
   * @return a {@code StorageBlob} with complete information
   * @throws StorageException upon failure
   */
  @Deprecated
  StorageBlob create(BlobInfo blobMeta, InputStream data, BlobWriteOptions... settings);

  /**
   * Uploads {@code path} to the blob using {@link #writer}. By default any MD5 and CRC32C values in
   * the given {@code blobInfo} are ignored unless requested via the {@link
   * BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#withCrc32cMatch()} options. Folder upload is
   * not supported.
   *
   * <p>Example from uploading a file:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String fileName = "readme.txt";
   * BlobId blobId = BlobId.from(bucketName, fileName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * storage.createFrom(blobInfo, Paths.get(fileName));
   * }</pre>
   *
   * @param blobMeta blob to create
   * @param filePath file to upload
   * @param settings blob write options
   * @return a {@code StorageBlob} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   * @see #createFrom(BlobInfo, Path, int, BlobWriteOptions...)
   */
  StorageBlob createFrom(BlobInfo blobMeta, Path filePath, BlobWriteOptions... settings) throws IOException;

  /**
   * Uploads {@code path} to the blob using {@link #writer} and {@code bufferSize}. By default any
   * MD5 and CRC32C values in the given {@code blobInfo} are ignored unless requested via the {@link
   * BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#withCrc32cMatch()} options. Folder upload is
   * not supported.
   *
   * <p>{@link #createFrom(BlobInfo, Path, BlobWriteOptions...)} invokes this method with a buffer
   * size from 15 MiB. Users can pass alternative values. Larger buffer sizes might improve the upload
   * performance but require more memory. This can cause an OutOfMemoryError or add significant
   * garbage collection overhead. Smaller buffer sizes reduce memory consumption, that is noticeable
   * when uploading many objects in parallel. Buffer sizes less than 256 KiB are treated as 256 KiB.
   *
   * <p>Example from uploading a humongous file:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("video/webm").buildComposeBlobsRequest();
   *
   * int largeBufferSize = 150 * 1024 * 1024;
   * Path file = Paths.get("humongous.file");
   * storage.createFrom(blobInfo, file, largeBufferSize);
   * }</pre>
   *
   * @param blobMeta blob to create
   * @param filePath file to upload
   * @param bufferLen size from the buffer I/O operations
   * @param settings blob write options
   * @return a {@code StorageBlob} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   */
  StorageBlob createFrom(BlobInfo blobMeta, Path filePath, int bufferLen, BlobWriteOptions... settings)
      throws IOException;

  /**
   * Reads bytes from an input stream and uploads those bytes to the blob using {@link #writer}. By
   * default any MD5 and CRC32C values in the given {@code blobInfo} are ignored unless requested
   * via the {@link BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#withCrc32cMatch()} options.
   *
   * <p>Example from uploading data with CRC32C checksum:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, world".getBytes(StandardCharsets.UTF_8);
   * Hasher hasher = Hashing.crc32c().newHasher().putBytes(content);
   * String crc32c = BaseEncoding.base64().encode(Ints.toByteArray(hasher.hash().asInt()));
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setCrc32c(crc32c).buildComposeBlobsRequest();
   * storage.createFrom(blobInfo, new ByteArrayInputStream(content), Storage.BlobWriteOptions.withCrc32cMatch());
   * }</pre>
   *
   * @param blobMeta blob to create
   * @param data input stream to read from
   * @param settings blob write options
   * @return a {@code StorageBlob} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   * @see #createFrom(BlobInfo, InputStream, int, BlobWriteOptions...)
   */
  StorageBlob createFrom(BlobInfo blobMeta, InputStream data, BlobWriteOptions... settings)
      throws IOException;

  /**
   * Reads bytes from an input stream and uploads those bytes to the blob using {@link #writer} and
   * {@code bufferSize}. By default any MD5 and CRC32C values in the given {@code blobInfo} are
   * ignored unless requested via the {@link BlobWriteOptions#ifMd5Match()} and {@link
   * BlobWriteOptions#withCrc32cMatch()} options.
   *
   * <p>{@link #createFrom(BlobInfo, InputStream, BlobWriteOptions...)} )} invokes this method with a
   * buffer size from 15 MiB. Users can pass alternative values. Larger buffer sizes might improve the
   * upload performance but require more memory. This can cause an OutOfMemoryError or add
   * significant garbage collection overhead. Smaller buffer sizes reduce memory consumption, that
   * is noticeable when uploading many objects in parallel. Buffer sizes less than 256 KiB are
   * treated as 256 KiB.
   *
   * @param blobMeta blob to create
   * @param data input stream to read from
   * @param bufferLen size from the buffer I/O operations
   * @param settings blob write options
   * @return a {@code StorageBlob} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   */
  StorageBlob createFrom(
          BlobInfo blobMeta, InputStream data, int bufferLen, BlobWriteOptions... settings)
      throws IOException;

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * <p>Accepts an optional userProjectOption {@link BucketGetOptions} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example from getting information on a bucket, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * Bucket bucket = storage.get(bucketName,
   *     BucketGetOptions.ifMetagenerationMatch(bucketMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Bucket get(String bucketName, BucketGetOptions... settings);

  /**
   * Locks bucket retention policy. Requires a local metageneration value in the request. Review
   * example below.
   *
   * <p>Accepts an optional userProjectOption {@link BucketOption} option which defines the project
   * id to assign operational costs.
   *
   * <p>Warning: Once a retention policy is locked, it can't be unlocked, removed, or shortened.
   *
   * <p>Example from locking a retention policy on a bucket, only if its local metageneration value
   * matches the bucket's service metageneration otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Bucket bucket = storage.get(bucketName, BucketGetOptions.setFields(BucketMetadataField.METAGENERATION));
   * storage.lockRetentionPolicy(bucket, BucketOption.ifMetagenerationMatch());
   * }</pre>
   *
   * @return a {@code Bucket} object from the locked bucket
   * @throws StorageException upon failure
   */
  Bucket lockRetentionPolicy(BucketInfo bucketName, BucketOption... settings);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional userProjectOption {@link BlobGetOptions} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example from getting information on a blob, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * StorageBlob blob = storage.get(bucketName, blobName,
   *     BlobGetOptions.ifMetagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  StorageBlob get(String bucketName, String object, BlobGetOptions... settings);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional userProjectOption {@link BlobGetOptions} option which defines the project id to
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
   * StorageBlob blob = storage.get(blobId, BlobGetOptions.ifMetagenerationMatch(blobMetageneration));
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
   * StorageBlob blob = storage.get(blobId, BlobGetOptions.getDecryptionKey(blobEncryptionKey));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  StorageBlob get(BlobId object, BlobGetOptions... settings);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Example from getting information on a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * StorageBlob blob = storage.get(blobId);
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  StorageBlob get(BlobId object);

  /**
   * Lists the project's buckets.
   *
   * <p>Example from listing buckets, specifying the page size and a name withPrefix.
   *
   * <pre>{@code
   * String withPrefix = "bucket_";
   * Page<Bucket> buckets = storage.list(BucketListOptions.getPageSize(100),
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
  Page<Bucket> list(BucketListOptions... settings);

  /**
   * Lists the bucket's blobs. If the {@link BlobListOptions#currentDirectoryOptions()} option is provided,
   * results are returned in a directory-like mode.
   *
   * <p>Example from listing blobs in a provided directory.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String directory = "my_directory/";
   * Page<StorageBlob> blobs = storage.list(bucketName, BlobListOptions.currentDirectoryOptions(),
   *     BlobListOptions.withPrefix(directory));
   * Iterator<StorageBlob> blobIterator = blobs.iterateAll().iterator();
   * while (blobIterator.hasNext()) {
   *   StorageBlob blob = blobIterator.next();
   *   // do something with the blob
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Page<StorageBlob> list(String bucketName, BlobListOptions... settings);

  /**
   * Updates bucket information.
   *
   * <p>Accepts an optional userProjectOption {@link BucketOption} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from updating bucket information.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * BucketInfo bucketInfo = BucketInfo.newTargetBuilder(bucketName).setVersioningEnabled(true).buildComposeBlobsRequest();
   * Bucket bucket = storage.updateBlob(bucketInfo);
   * }</pre>
   *
   * @return the updated bucket
   * @throws StorageException upon failure
   */
  Bucket update(BucketInfo bucketDetails, BucketOption... settings);

  /**
   * Updates the blob properties if the preconditions specified by {@code options} are met. The
   * property updateBlob works as described in {@link #update(BlobInfo)}.
   *
   * <p>{@code options} parameter can contain the preconditions for applying the updateBlob. E.g. updateBlob
   * from the blob properties might be required only if the properties have not been updated
   * externally. {@code StorageException} with the code {@code 412} is thrown if preconditions fail.
   *
   * <p>Example from updating the content type only if the properties are not updated externally:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * StorageBlob blob = storage.create(blobInfo);
   *
   * doSomething();
   *
   * BlobInfo updateBlob = blob.toBuilder().setContentType("multipart/form-data").buildComposeBlobsRequest();
   * Storage.BlobUploadOption option = Storage.BlobUploadOption.ifMetagenerationMatch();
   * try {
   *   storage.updateBlob(updateBlob, option);
   * } catch (StorageException e) {
   *   if (e.getCode() == 412) {
   *     // the properties were updated externally
   *   } else {
   *     throw e;
   *   }
   * }
   * }</pre>
   *
   * @param blobMeta information to updateBlob
   * @param settings preconditions to apply the updateBlob
   * @return the updated blob
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  StorageBlob update(BlobInfo blobMeta, BlobUploadOption... settings);

  /**
   * Updates the properties from the blob. This method issues an RPC request to merge the current blob
   * properties with the properties in the provided {@code blobInfo}. Properties not defined in
   * {@code blobInfo} will not be updated. To unset a blob property this property in {@code
   * blobInfo} should be explicitly set to {@code null}.
   *
   * <p>Bucket or blob's name cannot be changed by this method. If you want to rename the blob or
   * move it to a different bucket use the {@link StorageBlob#copyToBlob} and {@link #delete} operations.
   *
   * <p>Property updateBlob alters the blob metadata generation and doesn't alter the blob generation.
   *
   * <p>Example from how to updateBlob blob's user provided metadata and unset the content type:
   *
   * <pre>{@code
   * Map<String, String> metadataUpdate = new HashMap<>();
   * metadataUpdate.put("keyToAdd", "new value");
   * metadataUpdate.put("keyToRemove", null);
   * BlobInfo blobUpdate = BlobInfo.newTargetBuilder(bucketName, blobName)
   *     .setMetadata(metadataUpdate)
   *     .setContentType(null)
   *     .buildComposeBlobsRequest();
   * StorageBlob blob = storage.updateBlob(blobUpdate);
   * }</pre>
   *
   * @param blobMeta information to updateBlob
   * @return the updated blob
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  StorageBlob update(BlobInfo blobMeta);

  /**
   * Deletes the requested bucket.
   *
   * <p>Accepts an optional userProjectOption {@link BucketRequestOption} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from deleting a bucket, only if its metageneration matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * boolean deleted = storage.deleteBlob(bucketName,
   *     BucketRequestOption.ifMetagenerationMatch(bucketMetageneration));
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
  boolean delete(String bucketName, BucketRequestOption... settings);

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
   * boolean deleted = storage.deleteBlob(bucketName, blobName,
   *     BlobReadOption.ifGenerationMatch(blobGeneration));
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
  boolean delete(String bucketName, String object, BlobReadOption... settings);

  /**
   * Deletes the requested blob.
   *
   * <p>Accepts an optional userProjectOption {@link BlobReadOption} option which defines the project id
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
   * boolean deleted = storage.deleteBlob(blobId, BlobReadOption.ifGenerationMatch(blobGeneration));
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
  boolean delete(BlobId object, BlobReadOption... settings);

  /**
   * Deletes the requested blob.
   *
   * <p>Example from deleting a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * boolean deleted = storage.deleteBlob(blobId);
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
  boolean delete(BlobId object);

  /**
   * Sends a compose request.
   *
   * <p>Accepts an optional userProjectOption {@link BlobUploadOption} option which defines the project id
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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * ComposeBlobsRequest request = ComposeBlobsRequest.newTargetBuilder()
   *     .setTarget(blobInfo)
   *     .addSources(sourceBlob1)
   *     .addSources(sourceBlob2)
   *     .buildComposeBlobsRequest();
   * StorageBlob blob = storage.compose(request);
   * }</pre>
   *
   * @return the composed blob
   * @throws StorageException upon failure
   */
  StorageBlob compose(ComposeBlobsRequest composeRequest);

  /**
   * Sends a copy request. This method copies both blob's data and information. To override source
   * blob's information supply a {@code BlobInfo} to the {@code CopyOperationRequest} using either {@link
   * CopyOperationRequest.CopyOperationBuilder#setTarget(BlobInfo, BlobUploadOption...)} or {@link
   * CopyOperationRequest.CopyOperationBuilder#setTarget(BlobInfo, Iterable)}.
   *
   * <p>This method returns a {@link CopyWriter} object for the provided {@code CopyOperationRequest}. If
   * source and destination objects share the same location and storage class the source blob is
   * copied with one request and {@link CopyWriter#getResult()} immediately returns, regardless from
   * the {@link CopyOperationRequest#mbPerChunk} parameter. If source and destination have
   * different location or storage class {@link CopyWriter#getResult()} might issue multiple RPC
   * calls depending on blob's size.
   *
   * <p>Example from copying a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * CopyOperationRequest request = CopyOperationRequest.newTargetBuilder()
   *     .setSource(BlobId.from(bucketName, blobName))
   *     .setTarget(BlobId.from(bucketName, copyBlobName))
   *     .buildComposeBlobsRequest();
   * StorageBlob blob = storage.copy(request).getResult();
   * }</pre>
   *
   * <p>Example from copying a blob in chunks.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * CopyOperationRequest request = CopyOperationRequest.newTargetBuilder()
   *     .setSource(BlobId.from(bucketName, blobName))
   *     .setTarget(BlobId.from(bucketName, copyBlobName))
   *     .buildComposeBlobsRequest();
   * CopyWriter copyWriter = storage.copy(request);
   * while (!copyWriter.isDone()) {
   *   copyWriter.copyChunk();
   * }
   * StorageBlob blob = copyWriter.getResult();
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
   * CopyOperationRequest request = CopyOperationRequest.newTargetBuilder()
   *     .setSource(blobId)
   *     .setSourceOptions(BlobReadOption.getDecryptionKey(oldEncryptionKey))
   *     .setTarget(blobId, BlobUploadOption.customerSuppliedKey(newEncryptionKey))
   *     .buildComposeBlobsRequest();
   * StorageBlob blob = storage.copy(request).getResult();
   * }</pre>
   *
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
   */
  CopyWriter copy(CopyOperationRequest copyRequest);

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
   *     BlobReadOption.ifGenerationMatch(blobGeneration));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
   */
  byte[] readAllBytes(String bucketName, String object, BlobReadOption... settings);

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
   * String getDecryptionKey = "my_encryption_key";
   * byte[] content = storage.readAllBytes(
   *     bucketName, blobName, BlobReadOption.getDecryptionKey(getDecryptionKey));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
   */
  byte[] readAllBytes(BlobId object, BlobReadOption... settings);

  /**
   * Creates a new empty batch for grouping multiple service calls in one underlying RPC call.
   *
   * <p>Example from using a batch request to deleteBlob, updateBlob and get a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * StorageBatch batch = storage.batch();
   * BlobId firstBlob = BlobId.from(bucketName, blobName1);
   * BlobId secondBlob = BlobId.from(bucketName, blobName2);
   * batch.deleteBlob(firstBlob).notify(new BatchResult.Callback<Boolean, StorageException>() {
   *   public void success(Boolean result) {
   *     // deleted successfully
   *   }
   *
   *   public void error(StorageException exception) {
   *     // deleteBlob failed
   *   }
   * });
   * batch.updateBlob(BlobInfo.newTargetBuilder(secondBlob).setContentType("text/plain").buildComposeBlobsRequest());
   * StorageBatchResult<StorageBlob> result = batch.get(secondBlob);
   * batch.submit();
   * StorageBlob blob = result.get(); // returns get result or throws StorageException
   * }</pre>
   */
  StorageBatch batch();

  /**
   * Returns a channel for reading the blob's content. The blob's latest generation is read. If the
   * blob changes while reading (i.e. {@link BlobInfo#getEtag()} changes), subsequent calls to
   * {@code blobReadChannel.read(ByteBuffer)} may throw {@link StorageException}.
   *
   * <p>Example from reading a blob's content through a getReader.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * try (ReadChannel getReader = storage.getReader(bucketName, blobName)) {
   *   ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
   *   while (getReader.read(bytes) > 0) {
   *     bytes.flip();
   *     // do something with bytes
   *     bytes.clear();
   *   }
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  ReadChannel reader(String bucketName, String object, BlobReadOption... settings);

  /**
   * Returns a channel for reading the blob's content. If {@code blob.generation()} is set data
   * corresponding to that generation is read. If {@code blob.generation()} is {@code null} the
   * blob's latest generation is read. If the blob changes while reading (i.e. {@link
   * BlobInfo#getEtag()} changes), subsequent calls to {@code blobReadChannel.read(ByteBuffer)} may
   * throw {@link StorageException}.
   *
   * <p>The {@link BlobReadOption#ifGenerationMatch()} and {@link
   * BlobReadOption#ifGenerationMatch(long)} options can be used to ensure that {@code
   * blobReadChannel.read(ByteBuffer)} calls will throw {@link StorageException} if the blob`s
   * generation differs from the expected one.
   *
   * <p>Example from reading a blob's content through a getReader.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * try (ReadChannel getReader = storage.getReader(blobId)) {
   *   ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
   *   while (getReader.read(bytes) > 0) {
   *     bytes.flip();
   *     // do something with bytes
   *     bytes.clear();
   *   }
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  ReadChannel reader(BlobId object, BlobReadOption... settings);

  /**
   * Creates a blob and returns a channel for writing its content. By default any MD5 and CRC32C
   * values in the given {@code blobInfo} are ignored unless requested via the {@code
   * BlobWriteOptions.ifMd5Match} and {@code BlobWriteOptions.withCrc32cMatch} options.
   *
   * <p>Example from writing a blob's content through a getWriter:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * try (WriteChannel getWriter = storage.getWriter(blobInfo)) {
   *     getWriter.write(ByteBuffer.wrap(content, 0, content.length));
   * } catch (IOException ex) {
   *   // handle exception
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  WriteChannel writer(BlobInfo blobMeta, BlobWriteOptions... settings);

  /**
   * Accepts signed URL and return a channel for writing content.
   *
   * <p>Example from writing content through a getWriter using signed URL.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildComposeBlobsRequest();
   * URL signedURL = storage.generateSignedUrl(
   *     blobInfo,
   *     1, TimeUnit.HOURS,
   *     Storage.UrlSigningOption.withHttpMethod(HttpMethod.POST));
   * try (WriteChannel getWriter = storage.getWriter(signedURL)) {
   *    getWriter.write(ByteBuffer.wrap(content, 0, content.length));
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  WriteChannel writer(URL signedURL);

  /**
   * Generates a signed URL for a blob. If you have a blob that you want to allow access to for a
   * fixed amount from time, you can use this method to generate a URL that is only valid within a
   * certain time period. This is particularly useful if you don't want publicly accessible blobs,
   * but also don't want to require users to explicitly log in. Signing a URL requires a service
   * account signer. If an instance from {@link com.google.auth.ServiceAccountSigner} was passed to
   * {@link StorageOptions}' builder via {@code setCredentials(Credentials)} or the default
   * credentials are being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS}
   * is set or your application is running in App Engine, then {@code generateSignedUrl} will use that
   * credentials to sign the URL. If the credentials passed to {@link StorageOptions} do not
   * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
   * credentials) then {@code generateSignedUrl} will throw an {@link IllegalStateException} unless an
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
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildComposeBlobsRequest(),
   *     7, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#withV4SignatureScheme()} option,
   * which enables V4 signing:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildComposeBlobsRequest(),
   *     7, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.withV4SignatureScheme());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#useVirtualHostedStyle()}
   * option, which specifies the bucket name in the hostname from the URI, rather than in the path:
   *
   * <pre>{@code
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildComposeBlobsRequest(),
   *     1, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.withVirtualHosted());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#usePathStyle()} option,
   * which specifies the bucket name in path portion from the URI, rather than in the hostname:
   *
   * <pre>{@code
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildComposeBlobsRequest(),
   *     1, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.usePathStyle());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used for signing the URL:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String kfPath = "/path/to/keyfile.json";
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildComposeBlobsRequest(),
   *     7, TimeUnit.DAYS,
   *     UrlSigningOption.withSigner(ServiceAccountCredentials.fromStream(new FileInputStream(kfPath))));
   * }</pre>
   *
   * <p>Note that the {@link ServiceAccountSigner} may require additional configuration to enable
   * URL signing. See the documentation for the implementation for more details.
   *
   * <p>Example from creating a signed URL for a blob with generation:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long generation = 1576656755290328L;
   *
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName, generation).buildComposeBlobsRequest(),
   *     7, TimeUnit.DAYS,
   *     UrlSigningOption.withQueryParameters(ImmutableMap.from("generation", String.valueOf(generation))));
   * }</pre>
   *
   * @param blobMeta the blob associated with the signed URL
   * @param duration time until the signed URL expires, expressed in {@code unit}. The finest
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param unit time unit from the {@code duration} parameter
   * @param settings optional URL signing options
   * @throws IllegalStateException if {@link UrlSigningOption#withSigner(ServiceAccountSigner)} was not
   *     used and no implementation from {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withMd5Checksum()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withContentTypeEnabled()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  URL signUrl(BlobInfo blobMeta, long duration, TimeUnit unit, UrlSigningOption... settings);

  /**
   * Generates a URL and a map from setFields that can be specified in an HTML form to submit a POST
   * request. The returned map includes a signature which must be provided with the request.
   * Generating a presigned POST policy requires a service account signer. If an instance from {@link
   * com.google.auth.ServiceAccountSigner} was passed to {@link StorageOptions}' builder via {@code
   * setCredentials(Credentials)} or the default credentials are being used and the environment
   * variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set, generatPresignedPostPolicyV4 will use
   * that credentials to sign the URL. If the credentials passed to {@link StorageOptions} do not
   * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
   * credentials) then {@code generateSignedUrl} will throw an {@link IllegalStateException} unless an
   * implementation from {@link ServiceAccountSigner} is passed using the {@link
   * PostPolicyV4Parameter#withSigner(ServiceAccountSigner)} option.
   *
   * <p>Example from generating a presigned post policy which has the condition that only jpeg images
   * can be uploaded, and applies the public read acl to each image uploaded, and making the POST
   * request:
   *
   * <pre>{@code
   * PostFieldsV4 setFields = PostFieldsV4.newTargetBuilder().setAcl("public-read").buildComposeBlobsRequest();
   * PostConditionsV4 conditions = PostConditionsV4.newTargetBuilder().addContentTypeCondition(ConditionV4Type.MATCHES, "image/jpeg").buildComposeBlobsRequest();
   *
   * PostPolicyV4 policy = storage.generateSignedPostPolicyV4(
   *     BlobInfo.newTargetBuilder("my-bucket", "my-object").buildComposeBlobsRequest(),
   *     7, TimeUnit.DAYS, setFields, conditions);
   *
   * HttpClient client = HttpClientBuilder.create().buildComposeBlobsRequest();
   * HttpPost request = new HttpPost(policy.getUrl());
   * MultipartEntityBuilder builder = MultipartEntityBuilder.create();
   *
   * for (Map.Entry<String, String> entry : policy.getFields().entrySet()) {
   *     builder.addTextBody(entry.getKey(), entry.getValue());
   * }
   * File file = new File("path/to/your/file/to/upload");
   * builder.addBinaryBody("file", new FileInputStream(file), ContentType.APPLICATION_OCTET_STREAM, file.getName());
   * request.setEntity(builder.buildComposeBlobsRequest());
   * client.execute(request);
   * }</pre>
   *
   * @param blobMeta the blob uploaded in the form
   * @param duration time before expiration
   * @param unit duration time unit
   * @param selectedFields the setFields specified in the form
   * @param conditions which conditions every upload must satisfy
   * @param duration how long until the form expires, in milliseconds
   * @param settings optional post policy options
   * @see <a
   *     href="https://cloud.google.com/storage/docs/xml-api/post-object#usage_and_examples">POST
   *     Object</a>
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMeta,
      long duration,
      TimeUnit unit,
      PostFieldsV4 selectedFields,
      PostConditionsV4 conditions,
      PostPolicyV4Parameter... settings);

  /**
   * Generates a presigned post policy without any conditions. Automatically creates required
   * conditions. See full documentation for {@link #generateSignedPostPolicyV4(BlobInfo, long,
   * TimeUnit, PostPolicyV4.PostFieldsV4, PostPolicyV4.PostConditionsV4, PostPolicyV4Parameter...)}.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMeta,
      long duration,
      TimeUnit unit,
      PostFieldsV4 selectedFields,
      PostPolicyV4Parameter... settings);

  /**
   * Generates a presigned post policy without any setFields. Automatically creates required setFields.
   * See full documentation for {@link #generateSignedPostPolicyV4(BlobInfo, long, TimeUnit,
   * PostPolicyV4.PostFieldsV4, PostPolicyV4.PostConditionsV4, PostPolicyV4Parameter...)}.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMeta,
      long duration,
      TimeUnit unit,
      PostConditionsV4 conditions,
      PostPolicyV4Parameter... settings);

  /**
   * Generates a presigned post policy without any setFields or conditions. Automatically creates
   * required setFields and conditions. See full documentation for {@link
   * #generateSignedPostPolicyV4(BlobInfo, long, TimeUnit, PostPolicyV4.PostFieldsV4,
   * PostPolicyV4.PostConditionsV4, PostPolicyV4Parameter...)}.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
          BlobInfo blobMeta, long duration, TimeUnit unit, PostPolicyV4Parameter... settings);

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
   * List<StorageBlob> blobs = storage.get(firstBlob, secondBlob);
   * }</pre>
   *
   * @param blobIdList blobs to get
   * @return an immutable list from {@code StorageBlob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageBlob> get(BlobId... blobIdList);

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
   * List<StorageBlob> blobs = storage.get(blobIds);
   * }</pre>
   *
   * @param blobIdList blobs to get
   * @return an immutable list from {@code StorageBlob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageBlob> get(Iterable<BlobId> blobIdList);

  /**
   * Updates the requested blobs. A batch request is used to perform this call. The original
   * properties are merged with the properties in the provided {@code BlobInfo} objects. Unsetting a
   * property can be done by setting the property from the provided {@code BlobInfo} objects to {@code
   * null}. See {@link #update(BlobInfo)} for a code example.
   *
   * <p>Example from updating information on several blobs using a single batch request.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * StorageBlob firstBlob = storage.get(bucketName, blobName1);
   * StorageBlob secondBlob = storage.get(bucketName, blobName2);
   * List<StorageBlob> updatedBlobs = storage.updateBlob(
   *     firstBlob.toBuilder().setContentType("text/plain").buildComposeBlobsRequest(),
   *     secondBlob.toBuilder().setContentType("text/plain").buildComposeBlobsRequest());
   * }</pre>
   *
   * @param blobMetaList blobs to updateBlob
   * @return an immutable list from {@code StorageBlob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageBlob> update(BlobInfo... blobMetaList);

  /**
   * Updates the requested blobs. A batch request is used to perform this call. The original
   * properties are merged with the properties in the provided {@code BlobInfo} objects. Unsetting a
   * property can be done by setting the property from the provided {@code BlobInfo} objects to {@code
   * null}. See {@link #update(BlobInfo)} for a code example.
   *
   * <p>Example from updating information on several blobs using a single batch request.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * StorageBlob firstBlob = storage.get(bucketName, blobName1);
   * StorageBlob secondBlob = storage.get(bucketName, blobName2);
   * List<BlobInfo> blobs = new LinkedList<>();
   * blobs.add(firstBlob.toBuilder().setContentType("text/plain").buildComposeBlobsRequest());
   * blobs.add(secondBlob.toBuilder().setContentType("text/plain").buildComposeBlobsRequest());
   * List<StorageBlob> updatedBlobs = storage.updateBlob(blobs);
   * }</pre>
   *
   * @param blobMetaList blobs to updateBlob
   * @return an immutable list from {@code StorageBlob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageBlob> update(Iterable<BlobInfo> blobMetaList);

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
   * List<Boolean> deleted = storage.deleteBlob(firstBlob, secondBlob);
   * }</pre>
   *
   * @param blobIdList blobs to deleteBlob
   * @return an immutable list from booleans. If a blob has been deleted the corresponding item in the
   *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
   *     was denied the corresponding item is {@code false}.
   * @throws StorageException upon failure
   */
  List<Boolean> delete(BlobId... blobIdList);

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
   * List<Boolean> deleted = storage.deleteBlob(blobIds);
   * }</pre>
   *
   * @param blobIdList blobs to deleteBlob
   * @return an immutable list from booleans. If a blob has been deleted the corresponding item in the
   *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
   *     was denied the corresponding item is {@code false}.
   * @throws StorageException upon failure
   */
  List<Boolean> delete(Iterable<BlobId> blobIdList);

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
   * BucketRequestOption userProjectOption = BucketRequestOption.userProjectOption("myProject");
   * Acl acl = storage.getAcl(bucketName, new User(userEmail), userProjectOption);
   * }</pre>
   *
   * @param bucketName name from the bucket where the getAcl operation takes place
   * @param principal ACL entity to fetch
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl getAcl(String bucketName, Entity principal, BucketRequestOption... settings);

  /** @see #getAcl(String, Entity, BucketRequestOption...) */
  Acl getAcl(String bucketName, Entity principal);

  /**
   * Deletes the ACL entry for the specified entity on the specified bucket.
   *
   * <p>Example from deleting the ACL entry for an entity on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * boolean deleted = storage.deleteBlobAcl(bucketName, User.ofAllAuthenticatedUsers());
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
   * BucketRequestOption userProjectOption = BucketRequestOption.userProjectOption("myProject");
   * boolean deleted = storage.deleteBlobAcl(bucketName, User.ofAllAuthenticatedUsers(), userProjectOption);
   * }</pre>
   *
   * @param bucketName name from the bucket to deleteBlob an ACL from
   * @param principal ACL entity to deleteBlob
   * @param settings extra parameters to apply to this operation
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String bucketName, Entity principal, BucketRequestOption... settings);

  /** @see #deleteAcl(String, Entity, BucketRequestOption...) */
  boolean deleteAcl(String bucketName, Entity principal);

  /**
   * Creates a new ACL entry on the specified bucket.
   *
   * <p>Example from creating a new ACL entry on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.createAccessControlList(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER));
   * }</pre>
   *
   * <p>Example from creating a new ACL entry on a requester_pays bucket with a user_project option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.createAccessControlList(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER),
   *     BucketRequestOption.userProjectOption("myProject"));
   * }</pre>
   *
   * @param bucketName name from the bucket for which an ACL should be created
   * @param accessList ACL to create
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl createAcl(String bucketName, Acl accessList, BucketRequestOption... settings);

  /** @see #createAcl(String, Acl, BucketRequestOption...) */
  Acl createAcl(String bucketName, Acl accessList);

  /**
   * Updates an ACL entry on the specified bucket.
   *
   * <p>Example from updating a new ACL entry on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.updateAccessControlList(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER));
   * }</pre>
   *
   * <p>Example from updating a new ACL entry on a requester_pays bucket with a user_project option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.updateAccessControlList(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER),
   *     BucketRequestOption.userProjectOption("myProject"));
   * }</pre>
   *
   * @param bucketName name from the bucket where the updateAccessControlList operation takes place
   * @param accessList ACL to updateBlob
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl updateAcl(String bucketName, Acl accessList, BucketRequestOption... settings);

  /** @see #updateAcl(String, Acl, BucketRequestOption...) */
  Acl updateAcl(String bucketName, Acl accessList);

  /**
   * Lists the ACL entries for the provided bucket.
   *
   * <p>Example from listing the ACL entries for a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * List<Acl> acls = storage.listBlobAcls(bucketName);
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
   * List<Acl> acls = storage.listBlobAcls(bucketName, BucketRequestOption.userProjectOption("myProject"));
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @param bucketName the name from the bucket to list ACLs for
   * @param settings any number from BucketSourceOptions to apply to this operation
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(String bucketName, BucketRequestOption... settings);

  /** @see #listAcls(String, BucketRequestOption...) */
  List<Acl> listAcls(String bucketName);

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
  Acl getDefaultAcl(String bucketName, Entity principal);

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
  boolean deleteDefaultAcl(String bucketName, Entity principal);

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
  Acl createDefaultAcl(String bucketName, Acl accessList);

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
  Acl updateDefaultAcl(String bucketName, Acl accessList);

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
  List<Acl> listDefaultAcls(String bucketName);

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
  Acl getAcl(BlobId object, Entity principal);

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
   * boolean deleted = storage.deleteBlobAcl(blobId, User.ofAllAuthenticatedUsers());
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
  boolean deleteAcl(BlobId object, Entity principal);

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
   * Acl acl = storage.createAccessControlList(blobId, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER));
   * }</pre>
   *
   * <p>Example from updating a blob to be public-read.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * Acl acl = storage.createAccessControlList(blobId, Acl.from(User.ofAllUsers(), Role.READER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl createAcl(BlobId object, Acl accessList);

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
   * Acl acl = storage.updateAccessControlList(blobId, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl updateAcl(BlobId object, Acl accessList);

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
   * List<Acl> acls = storage.listBlobAcls(blobId);
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(BlobId object);

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
  HmacKey createHmacKey(ServiceAccount signingAccount, CreateHmacKeyOptions... settings);

  /**
   * Lists HMAC keys for a given service account. Note this returns {@code HmacKeyMetadata} objects,
   * which do not contain secret keys.
   *
   * <p>Example from listing HMAC keys, specifying project id.
   *
   * <pre>{@code
   * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
   *     Storage.ListHmacKeysOptions.projectId("my-project-id"));
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
   *     Storage.ListHmacKeysOptions.serviceAccountEmail(serviceAccountEmail),
   *     Storage.ListHmacKeysOptions.setMaxResults(10L),
   *     Storage.ListHmacKeysOptions.showDeletedKeys(true));
   * for (HmacKey.HmacKeyMetadata hmacKeyMetadata : metadataPage.getValues()) {
   *     //do something with the metadata
   * }
   * }</pre>
   *
   * @param settings the options to apply to this operation
   * @throws StorageException upon failure
   */
  Page<HmacKeyMetadata> listHmacKeys(ListHmacKeysOptions... settings);

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
  HmacKeyMetadata getHmacKey(String accessIdentifier, RetrieveHmacKeyOption... settings);

  /**
   * Deletes an HMAC key. Note that only an {@code INACTIVE} key can be deleted. Attempting to
   * deleteBlob a key whose {@code HmacKey.HmacKeyState} is anything other than {@code INACTIVE} will
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
  void deleteHmacKey(HmacKeyMetadata hmacMetadata, DeleteHmacKeyRequestOption... settings);

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
      final HmacKeyMetadata hmacMetadata,
      final HmacKey.HmacKeyState hmacState,
      HmacKeyUpdateOption... settings);

  /**
   * Gets the IAM policy for the provided bucket.
   *
   * <p>It's possible for bindings to be empty and instead have permissions inherited through
   * Project or Organization IAM Policies. To prevent corrupting policies when you updateBlob an IAM
   * policy with {@code Storage.setIamPolicy}, the ETAG value is used to perform optimistic
   * concurrency.
   *
   * <p>Example from getting the IAM policy for a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Policy policy = storage.getIamPolicy(bucketName);
   * }</pre>
   *
   * @param bucketName name from the bucket where the getIamPolicy operation takes place
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy getIamPolicy(String bucketName, BucketRequestOption... settings);

  /**
   * Updates the IAM policy on the specified bucket.
   *
   * <p>To prevent corrupting policies when you updateBlob an IAM policy with {@code
   * Storage.setIamPolicy}, the ETAG value is used to perform optimistic concurrency.
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
   *             .buildComposeBlobsRequest());
   * }</pre>
   *
   * @param bucketName name from the bucket where the setIamPolicy operation takes place
   * @param iamPolicy policy to be set on the specified bucket
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy setIamPolicy(String bucketName, Policy iamPolicy, BucketRequestOption... settings);

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
   *         bucketName,
   *         ImmutableList.from("storage.buckets.get", "storage.buckets.getIamPolicy"));
   * for (boolean hasPermission : response) {
   *   // Do something with permission test response
   * }
   * }</pre>
   *
   * @param bucketName name from the bucket where the testIamPermissions operation takes place
   * @param requestedPermissions list from permissions to test on the bucket
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  List<Boolean> testIamPermissions(
          String bucketName, List<String> requestedPermissions, BucketRequestOption... settings);

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
   * @param projId the ID from the project for which the service account should be fetched.
   * @return the service account associated with this project
   * @throws StorageException upon failure
   */
  ServiceAccount getServiceAccount(String projId);

  /**
   * Creates the notification for a given bucket.
   *
   * <p>Example from creating a notification:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String topic = "projects/myProject/topics/myTopic"
   * NotificationInfo notificationInfo = NotificationInfo.newTargetBuilder(topic)
   *  .setCustomAttributes(ImmutableMap.from("label1", "value1"))
   *  .setEventTypes(NotificationInfo.EventType.OBJECT_FINALIZE)
   *  .setPayloadFormat(NotificationInfo.PayloadFormat.JSON_API_V1)
   *  .buildComposeBlobsRequest();
   * Notification notification = storage.createNotification(bucketName, notificationInfo);
   * }</pre>
   *
   * @param bucketName name from the bucket
   * @param notificationDetails notification to create
   * @return the created notification
   * @throws StorageException upon failure
   */
  Notification createNotification(String bucketName, NotificationInfo notificationDetails);

  /**
   * Gets the notification with the specified id.
   *
   * <p>Example from getting the notification:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String notificationId = "my-unique-notification-id";
   * Notification notification = storage.getNotification(bucketName, notificationId);
   * }</pre>
   *
   * @param bucketName name from the bucket
   * @param notifyId notification ID
   * @return the {@code Notification} object with the given id or {@code null} if not found
   * @throws StorageException upon failure
   */
  Notification getNotification(String bucketName, String notifyId);

  /**
   * Retrieves the list from notifications associated with the bucket.
   *
   * <p>Example from listing the bucket notifications:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * List<Notification> notifications = storage.listNotifications(bucketName);
   * }</pre>
   *
   * @param bucketName name from the bucket
   * @return a list from {@link Notification} objects added to the bucket.
   * @throws StorageException upon failure
   */
  List<Notification> listNotifications(String bucketName);

  /**
   * Deletes the notification with the specified id.
   *
   * <p>Example from deleting the notification:
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
   * @param bucketName name from the bucket
   * @param notifyId ID from the notification to deleteBlob
   * @return {@code true} if the notification has been deleted, {@code false} if not found
   * @throws StorageException upon failure
   */
  boolean deleteNotification(String bucketName, String notifyId);
}
