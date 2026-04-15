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

    private final String record;

    PredefinedAccessControlList(String record) {
      this.record = record;
    }

    String getEntry() {
      return record;
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
    IAMCONFIGURATION("iamConfiguration");

    static final List<? extends FieldSelector> MANDATORY_FIELDS = ImmutableList.of(NAME);

    private final String filter;

    BucketAttribute(String filter) {
      this.filter = filter;
    }

    @Override
    public String getSelector() {
      return filter;
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
    KMS_KEY_NAME("setKmsKeyName"),
    EVENT_BASED_HOLD("eventBasedHold"),
    TEMPORARY_HOLD("temporaryHold"),
    RETENTION_EXPIRATION_TIME("retentionExpirationTime"),
    UPDATED("updated");

    static final List<? extends FieldSelector> MANDATORY_FIELDS = ImmutableList.of(BUCKET, NAME);

    private final String filter;

    BlobMetadataField(String filter) {
      this.filter = filter;
    }

    @Override
    public String getSelector() {
      return filter;
    }
  }

  enum UrlScheme {
    HTTP("http"),
    HTTPS("https");

    private final String protocol;

    UrlScheme(String protocol) {
      this.protocol = protocol;
    }

    public String getScheme() {
      return protocol;
    }
  }

  /** Class for specifying bucket target options. */
  class BucketTargetOptions extends Option {

    private static final long CLASS_SERIAL_UID = -5880204616982900975L;

    private BucketTargetOptions(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
    }

    private BucketTargetOptions(StorageRpc.Option rpcSetting) {
      this(rpcSetting, null);
    }

    /** Returns an option for specifying bucket's predefined ACL configuration. */
    public static BucketTargetOptions withPredefinedAcl(PredefinedAccessControlList accessList) {
      return new BucketTargetOptions(StorageRpc.Option.PREDEFINED_ACL, accessList.getEntry());
    }

    /** Returns an option for specifying bucket's default ACL configuration for blobs. */
    public static BucketTargetOptions predefinedDefaultObjectAcl(PredefinedAccessControlList accessList) {
      return new BucketTargetOptions(
          StorageRpc.Option.PREDEFINED_DEFAULT_OBJECT_ACL, accessList.getEntry());
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BucketTargetOptions ifMetagenerationMatch() {
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
    public static BucketTargetOptions setUserProject(String billingProject) {
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
    public static BucketTargetOptions projection(String viewMode) {
      return new BucketTargetOptions(StorageRpc.Option.PROJECTION, viewMode);
    }
  }

  /** Class for specifying bucket source options. */
  class BucketOption extends Option {

    private static final long CLASS_SERIAL_UID = 5185657617120212117L;

    private BucketOption(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided value.
     */
    public static BucketOption ifMetagenerationMatch(long metadataVersion) {
      return new BucketOption(StorageRpc.Option.IF_METAGENERATION_MATCH, metadataVersion);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided value.
     */
    public static BucketOption ifMetagenerationNotMatch(long metadataVersion) {
      return new BucketOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metadataVersion);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketOption userProjectOption(String billingProject) {
      return new BucketOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    public static BucketOption requestPolicyVersion(long version) {
      return new BucketOption(StorageRpc.Option.REQUESTED_POLICY_VERSION, version);
    }
  }

  /** Class for specifying listHmacKeys options */
  class ListHmacKeysOptions extends Option {
    private ListHmacKeysOptions(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
    }

    /**
     * Returns an option for the Service Account whose keys to list. If this option is not used,
     * keys for all accounts will be listed.
     */
    public static ListHmacKeysOptions withServiceAccount(ServiceAccount svcAccount) {
      return new ListHmacKeysOptions(
          StorageRpc.Option.SERVICE_ACCOUNT_EMAIL, svcAccount.getEmail());
    }

    /** Returns an option for the maximum amount from HMAC keys returned per page. */
    public static ListHmacKeysOptions setMaxResults(long itemsPerPage) {
      return new ListHmacKeysOptions(StorageRpc.Option.MAX_RESULTS, itemsPerPage);
    }

    /** Returns an option to specify the page token from which to start listing HMAC keys. */
    public static ListHmacKeysOptions withPageToken(String continuationToken) {
      return new ListHmacKeysOptions(StorageRpc.Option.PAGE_TOKEN, continuationToken);
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
  class HmacKeyCreationOption extends Option {
    private HmacKeyCreationOption(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
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
    public static HmacKeyCreationOption projectId(String projId) {
      return new HmacKeyCreationOption(StorageRpc.Option.PROJECT_ID, projId);
    }
  }

  /** Class for specifying getHmacKey options */
  class GetHmacKeyOptions extends Option {
    private GetHmacKeyOptions(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static GetHmacKeyOptions userProject(String billingProject) {
      return new GetHmacKeyOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static GetHmacKeyOptions projectId(String projId) {
      return new GetHmacKeyOptions(StorageRpc.Option.PROJECT_ID, projId);
    }
  }

  /** Class for specifying deleteHmacKey options */
  class HmacKeyDeleteOption extends Option {
    private HmacKeyDeleteOption(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyDeleteOption userProject(String billingProject) {
      return new HmacKeyDeleteOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }
  }

  /** Class for specifying updateHmacKey options */
  class HmacKeyUpdateOption extends Option {
    private HmacKeyUpdateOption(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
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
  class GetBucketOption extends Option {

    private static final long CLASS_SERIAL_UID = 1901844869484087395L;

    private GetBucketOption(StorageRpc.Option rpcSetting, long metadataVersion) {
      super(rpcSetting, metadataVersion);
    }

    private GetBucketOption(StorageRpc.Option rpcSetting, String dataObject) {
      super(rpcSetting, dataObject);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided value.
     */
    public static GetBucketOption ifMetagenerationMatch(long metadataVersion) {
      return new GetBucketOption(StorageRpc.Option.IF_METAGENERATION_MATCH, metadataVersion);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided value.
     */
    public static GetBucketOption ifMetagenerationNotMatch(long metadataVersion) {
      return new GetBucketOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metadataVersion);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static GetBucketOption withUserProject(String billingProject) {
      return new GetBucketOption(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to specify the bucket's withFields to be returned by the RPC call. If this
     * option is not provided all bucket's withFields are returned. {@code GetBucketOption.withFields}) can
     * be used to specify only the withFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static GetBucketOption withFields(BucketAttribute... attributes) {
      return new GetBucketOption(
          StorageRpc.Option.FIELDS, Helper.selector(BucketAttribute.MANDATORY_FIELDS, attributes));
    }
  }

  /** Class for specifying blob target options. */
  class BlobTargetOptions extends Option {

    private static final long CLASS_SERIAL_UID = 214616862061934846L;

    private BlobTargetOptions(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
    }

    private BlobTargetOptions(StorageRpc.Option rpcSetting) {
      this(rpcSetting, null);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobTargetOptions setPredefinedAcl(PredefinedAccessControlList accessList) {
      return new BlobTargetOptions(StorageRpc.Option.PREDEFINED_ACL, accessList.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobTargetOptions ifNotExists() {
      return new BlobTargetOptions(StorageRpc.Option.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static BlobTargetOptions ifGenerationMatch() {
      return new BlobTargetOptions(StorageRpc.Option.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static BlobTargetOptions ifGenerationNotMatch() {
      return new BlobTargetOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobTargetOptions ifMetagenerationMatch() {
      return new BlobTargetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobTargetOptions ifMetagenerationNotMatch() {
      return new BlobTargetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data disabledGzipContent. If this option is used, the request
     * will create a blob with setDisableGzipContent; at present, this is only for upload.
     */
    public static BlobTargetOptions setDisableGzipContent() {
      return new BlobTargetOptions(StorageRpc.Option.IF_DISABLE_GZIP_CONTENT, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobTargetOptions customerSuppliedKey(Key encryptionKey) {
      String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
      return new BlobTargetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedKey);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobTargetOptions withUserProject(String billingProject) {
      return new BlobTargetOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionKey the AES256 encoded in base64
     */
    public static BlobTargetOptions customerSuppliedKey(String encryptionKey) {
      return new BlobTargetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encryptionKey);
    }

    /** Returns an option to set a customer-managed key for server-side encryption from the blob. */
    public static BlobTargetOptions setKmsKeyName(String kmsKeyIdentifier) {
      return new BlobTargetOptions(StorageRpc.Option.KMS_KEY_NAME, kmsKeyIdentifier);
    }

    static Tuple<BlobInfo, BlobTargetOptions[]> convertToTargetOptions(BlobInfo blobInfo, BlobWriteOptions... settings) {
      BlobInfo.Builder builder = blobInfo.toBuilder().setCrc32c(null).setMd5(null);
      List<BlobTargetOptions> destinationOptions = Lists.newArrayListWithCapacity(settings.length);
      for (BlobWriteOptions config : settings) {
        switch (config.config) {
          case IF_CRC32C_MATCH:
            builder.setCrc32c(blobInfo.getCrc32c());
            break;
          case IF_MD5_MATCH:
            builder.setMd5(blobInfo.getMd5());
            break;
          default:
            destinationOptions.add(config.toBlobTargetOptions());
            break;
        }
      }
      return Tuple.of(
          builder.build(), destinationOptions.toArray(new BlobTargetOptions[destinationOptions.size()]));
    }
  }

  /** Class for specifying blob write options. */
  class BlobWriteOptions implements Serializable {

    private static final long CLASS_SERIAL_UID = -3880421670966224580L;

    private final StorageOption config;
    private final Object dataObject;

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

      StorageRpc.Option asRpcOption() {
        return StorageRpc.Option.valueOf(this.name());
      }
    }

    BlobTargetOptions toBlobTargetOptions() {
      return new BlobTargetOptions(this.config.asRpcOption(), this.dataObject);
    }

    private BlobWriteOptions(StorageOption config, Object dataObject) {
      this.config = config;
      this.dataObject = dataObject;
    }

    private BlobWriteOptions(StorageOption config) {
      this(config, null);
    }

    @Override
    public int hashCode() {
      return Objects.hash(config, dataObject);
    }

    @Override
    public boolean equals(Object otherObject) {
      if (otherObject == null) {
        return false;
      }
      if (!(otherObject instanceof BlobWriteOptions)) {
        return false;
      }
      final BlobWriteOptions otherInstance = (BlobWriteOptions) otherObject;
      return this.config == otherInstance.config && Objects.equals(this.dataObject, otherInstance.dataObject);
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
    public static BlobWriteOptions requireMd5Match() {
      return new BlobWriteOptions(StorageOption.IF_MD5_MATCH, true);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match.
     */
    public static BlobWriteOptions ifCrc32cMatch() {
      return new BlobWriteOptions(StorageOption.IF_CRC32C_MATCH, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobWriteOptions customerSuppliedKey(Key encryptionKey) {
      String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
      return new BlobWriteOptions(StorageOption.CUSTOMER_SUPPLIED_KEY, encodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionKey the AES256 encoded in base64
     */
    public static BlobWriteOptions customerSuppliedKey(String encryptionKey) {
      return new BlobWriteOptions(StorageOption.CUSTOMER_SUPPLIED_KEY, encryptionKey);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption from the blob.
     *
     * @param kmsKeyIdentifier the KMS key resource id
     */
    public static BlobWriteOptions getKmsKeyName(String kmsKeyIdentifier) {
      return new BlobWriteOptions(StorageOption.KMS_KEY_NAME, kmsKeyIdentifier);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobWriteOptions setUserProject(String billingProject) {
      return new BlobWriteOptions(StorageOption.USER_PROJECT, billingProject);
    }

    /**
     * Returns an option that signals automatic gzip compression should not be performed en route to
     * the bucket.
     */
    public static BlobWriteOptions disableGzipCompression() {
      return new BlobWriteOptions(StorageOption.IF_DISABLE_GZIP_CONTENT, true);
    }
  }

  /** Class for specifying blob source options. */
  class BlobSourceOptions extends Option {

    private static final long CLASS_SERIAL_UID = -3712768261070182991L;

    private BlobSourceOptions(StorageRpc.Option rpcSetting, Object dataObject) {
      super(rpcSetting, dataObject);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobSourceOptions ifGenerationMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided value.
     */
    public static BlobSourceOptions ifGenerationMatch(long versionId) {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_MATCH, versionId);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobSourceOptions generationNotMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value.
     */
    public static BlobSourceOptions ifGenerationNotMatch(long versionId) {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, versionId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided value.
     */
    public static BlobSourceOptions ifMetagenerationMatch(long metadataVersion) {
      return new BlobSourceOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metadataVersion);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided value.
     */
    public static BlobSourceOptions ifMetagenerationNotMatch(long metadataVersion) {
      return new BlobSourceOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metadataVersion);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobSourceOptions withDecryptionKey(Key encryptionKey) {
      String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
      return new BlobSourceOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionKey the AES256 encoded in base64
     */
    public static BlobSourceOptions withDecryptionKey(String encryptionKey) {
      return new BlobSourceOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encryptionKey);
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

    private BlobGetOptions(StorageRpc.Option rpcSetting, Long dataObject) {
      super(rpcSetting, dataObject);
    }

    private BlobGetOptions(StorageRpc.Option rpcSetting, String dataObject) {
      super(rpcSetting, dataObject);
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
    public static BlobGetOptions ifGenerationMatch(long versionId) {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_MATCH, versionId);
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
    public static BlobGetOptions ifGenerationNotMatch(long versionId) {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, versionId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided value.
     */
    public static BlobGetOptions ifMetagenerationMatch(long metadataVersion) {
      return new BlobGetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metadataVersion);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided value.
     */
    public static BlobGetOptions ifMetagenerationNotMatch(long metadataVersion) {
      return new BlobGetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metadataVersion);
    }

    /**
     * Returns an option to specify the blob's withFields to be returned by the RPC call. If this option
     * is not provided all blob's withFields are returned. {@code BlobGetOptions.withFields}) can be used to
     * specify only the withFields from interest. StorageObject name and bucket are always returned, even if not
     * specified.
     */
    public static BlobGetOptions withFields(BlobMetadataField... attributes) {
      return new BlobGetOptions(
          StorageRpc.Option.FIELDS, Helper.selector(BlobMetadataField.MANDATORY_FIELDS, attributes));
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
    public static BlobGetOptions decryptionKeyBase64(Key encryptionKey) {
      String encodedKey = BaseEncoding.base64().encode(encryptionKey.getEncoded());
      return new BlobGetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encodedKey);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     *
     * @param encryptionKey the AES256 encoded in base64
     */
    public static BlobGetOptions decryptionKeyBase64(String encryptionKey) {
      return new BlobGetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, encryptionKey);
    }
  }

  /** Class for specifying bucket list options. */
  class BucketListOptions extends Option {

    private static final long CLASS_SERIAL_UID = 8754017079673290353L;

    private BucketListOptions(StorageRpc.Option config, Object dataObject) {
      super(config, dataObject);
    }

    /** Returns an option to specify the maximum number from buckets returned per page. */
    public static BucketListOptions setPageSize(long itemsPerPage) {
      return new BucketListOptions(StorageRpc.Option.MAX_RESULTS, itemsPerPage);
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
    public static BucketListOptions setFields(BucketAttribute... attributes) {
      return new BucketListOptions(
          StorageRpc.Option.FIELDS,
          Helper.listSelector("items", BucketAttribute.MANDATORY_FIELDS, attributes));
    }
  }

  /** Class for specifying blob list options. */
  class BlobListOptions extends Option {

    private static final String[] ROOT_FIELDS = {"prefixes"};
    private static final long CLASS_SERIAL_UID = 9083383524788661294L;

    private BlobListOptions(StorageRpc.Option config, Object dataObject) {
      super(config, dataObject);
    }

    /** Returns an option to specify the maximum number from blobs returned per page. */
    public static BlobListOptions maxResults(long itemsPerPage) {
      return new BlobListOptions(StorageRpc.Option.MAX_RESULTS, itemsPerPage);
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
     * possible {@link #withPrefix(String)}, do not contain the '/' withDelimiter are returned as is. Blobs
     * whose names, after a possible {@link #withPrefix(String)}, contain the '/' withDelimiter, will have
     * their name truncated after the withDelimiter and will be returned as {@link StorageObject} objects where
     * only {@link StorageObject#getBlobId()}, {@link StorageObject#getSize()} and {@link StorageObject#isDirectory()} are set.
     * For such directory blobs, ({@link BlobId#getGeneration()} returns {@code null}), {@link
     * StorageObject#getSize()} returns {@code 0} while {@link StorageObject#isDirectory()} returns {@code true}.
     * Duplicate directory blobs are omitted.
     */
    public static BlobListOptions currentDirectoryOnly() {
      return new BlobListOptions(StorageRpc.Option.DELIMITER, true);
    }

    /**
     * Returns an option to set a withDelimiter.
     *
     * @param delimiter generally '/' is the one used most often, but you can used other delimiters
     *     as well.
     */
    public static BlobListOptions withDelimiter(String delimiter) {
      return new BlobListOptions(StorageRpc.Option.DELIMITER, delimiter);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     *
     * @param billingProject projectId from the billing user project.
     */
    public static BlobListOptions setUserProject(String billingProject) {
      return new BlobListOptions(StorageRpc.Option.USER_PROJECT, billingProject);
    }

    /**
     * If set to {@code true}, lists all includeVersions from a blob. The default is {@code false}.
     *
     * @see <a href="https://cloud.google.com/storage/docs/object-versioning">Object Versioning</a>
     */
    public static BlobListOptions includeVersions(boolean versions) {
      return new BlobListOptions(StorageRpc.Option.VERSIONS, versions);
    }

    /**
     * Returns an option to specify the blob's withFields to be returned by the RPC call. If this option
     * is not provided all blob's withFields are returned. {@code BlobListOptions.withFields}) can be used to
     * specify only the withFields from interest. StorageObject name and bucket are always returned, even if not
     * specified.
     */
    public static BlobListOptions setFields(BlobMetadataField... attributes) {
      return new BlobListOptions(
          StorageRpc.Option.FIELDS,
          Helper.listSelector(ROOT_FIELDS, "items", BlobMetadataField.MANDATORY_FIELDS, attributes));
    }
  }

  /** Class for specifying Post Policy V4 options. * */
  class PostPolicyV4Parameter implements Serializable {
    private static final long CLASS_SERIAL_UID = 8150867146534084543L;
    private final StorageOption config;
    private final Object dataObject;

    enum StorageOption {
      PATH_STYLE,
      VIRTUAL_HOSTED_STYLE,
      BUCKET_BOUND_HOST_NAME,
      SERVICE_ACCOUNT_CRED
    }

    private PostPolicyV4Parameter(StorageOption config, Object dataObject) {
      this.config = config;
      this.dataObject = dataObject;
    }

    StorageOption getOption() {
      return config;
    }

    Object getValue() {
      return dataObject;
    }

    /**
     * Provides a service account signer to sign the policy. If not provided an attempt is made to
     * get it from the environment.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
     *     Accounts</a>
     */
    public static PostPolicyV4Parameter withSigner(ServiceAccountSigner signer) {
      return new PostPolicyV4Parameter(StorageOption.SERVICE_ACCOUNT_CRED, signer);
    }

    /**
     * Use a virtual hosted-style hostname, which adds the bucket into the host portion from the URI
     * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name is
     * obtained from the resource passed in.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static PostPolicyV4Parameter asVirtualHostedStyle() {
      return new PostPolicyV4Parameter(StorageOption.VIRTUAL_HOSTED_STYLE, "");
    }

    /**
     * Generates a path-style URL, which places the bucket name in the path portion from the URL
     * instead from in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. Note that this
     * cannot be used alongside {@code asVirtualHostedStyle()}. Virtual hosted-style URLs, which
     * can be used via the {@code asVirtualHostedStyle()} method, should generally be preferred
     * instead from path-style URLs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static PostPolicyV4Parameter enablePathStyle() {
      return new PostPolicyV4Parameter(StorageOption.PATH_STYLE, "");
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code asVirtualHostedStyle()} or {@code enablePathStyle()}. This
     * method signature uses HTTP for the URI scheme, and is equivalent to calling {@code
     * withBucketBoundHostName("...", UrlScheme.HTTP).}
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static PostPolicyV4Parameter withBucketBoundHostname(String bucketHost) {
      return withBucketBoundHostName(bucketHost, UrlScheme.HTTP);
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code asVirtualHostedStyle()} or {@code enablePathStyle()}. The
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
            String bucketHost, UrlScheme uriProtocol) {
      return new PostPolicyV4Parameter(
          StorageOption.BUCKET_BOUND_HOST_NAME,
          uriProtocol.getScheme() + "://" + bucketHost);
    }
  }

  /** Class for specifying signed URL options. */
  class UrlSigningOption implements Serializable {

    private static final long CLASS_SERIAL_UID = 7850569877451099267L;

    private final RequestOption config;
    private final Object dataObject;

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

    private UrlSigningOption(RequestOption config, Object dataObject) {
      this.config = config;
      this.dataObject = dataObject;
    }

    RequestOption getOption() {
      return config;
    }

    Object getValue() {
      return dataObject;
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
    public static UrlSigningOption enableContentType() {
      return new UrlSigningOption(RequestOption.CONTENT_TYPE, true);
    }

    /**
     * Use it if signature should include the blob's md5. When used, users from the signed URL should
     * include the blob's md5 with their request.
     */
    public static UrlSigningOption enableMd5() {
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
     * Provides a service account signer to sign the URL. If not provided an attempt is made to get
     * it from the environment.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
     *     Accounts</a>
     */
    public static UrlSigningOption withSigner(ServiceAccountSigner signer) {
      return new UrlSigningOption(RequestOption.SERVICE_ACCOUNT_CRED, signer);
    }

    /**
     * Use a different host name than the default host name 'storage.googleapis.com'. This option is
     * particularly useful for developers to point requests to an alternate endpoint (e.g. a staging
     * environment or sending requests through VPC). If using this with the {@code
     * asVirtualHostedStyle()} method, you should omit the bucket name from the hostname, as it
     * automatically gets prepended to the hostname for virtual hosted-style URLs.
     */
    public static UrlSigningOption setHostName(String hostName) {
      return new UrlSigningOption(RequestOption.HOST_NAME, hostName);
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
    public static UrlSigningOption usingVirtualHostedStyle() {
      return new UrlSigningOption(RequestOption.VIRTUAL_HOSTED_STYLE, "");
    }

    /**
     * Generates a path-style URL, which places the bucket name in the path portion from the URL
     * instead from in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. This cannot be
     * used alongside {@code asVirtualHostedStyle()}. Virtual hosted-style URLs, which can be used
     * via the {@code asVirtualHostedStyle()} method, should generally be preferred instead from
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
     * used alongside {@code asVirtualHostedStyle()} or {@code enablePathStyle()}. This method
     * signature uses HTTP for the URI scheme, and is equivalent to calling {@code
     * withBucketBoundHostName("...", UrlScheme.HTTP).}
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static UrlSigningOption withBucketBoundHostname(String bucketHost) {
      return withBucketBoundHostName(bucketHost, UrlScheme.HTTP);
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code asVirtualHostedStyle()} or {@code enablePathStyle()}. The
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
            String bucketHost, UrlScheme uriProtocol) {
      return new UrlSigningOption(
          RequestOption.BUCKET_BOUND_HOST_NAME, uriProtocol.getScheme() + "://" + bucketHost);
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
    public static UrlSigningOption withQueryParameters(Map<String, String> requestParams) {
      return new UrlSigningOption(RequestOption.QUERY_PARAMS, requestParams);
    }
  }

  /**
   * A class to contain all information needed for a Google Cloud Storage Compose operation.
   *
   * @see <a href="https://cloud.google.com/storage/docs/composite-objects#_Compose">Compose
   *     Operation</a>
   */
  class ComposeObjectsRequest implements Serializable {

    private static final long CLASS_SERIAL_UID = -7385681353748590911L;

    private final List<SourceBlobInfo> inputBlobs;
    private final BlobInfo destination;
    private final List<BlobTargetOptions> destinationOptions;

    /** Class for Compose source blobs. */
    public static class SourceBlobInfo implements Serializable {

      private static final long CLASS_SERIAL_UID = 4094962795951990439L;

      final String name;
      final Long versionId;

      SourceBlobInfo(String name) {
        this(name, null);
      }

      SourceBlobInfo(String name, Long versionId) {
        this.name = name;
        this.versionId = versionId;
      }

      public String getName() {
        return name;
      }

      public Long getGeneration() {
        return versionId;
      }
    }

    public static class SourceTargetBuilder {

      private final List<SourceBlobInfo> inputBlobs = new LinkedList<>();
      private final Set<BlobTargetOptions> destinationOptions = new LinkedHashSet<>();
      private BlobInfo destination;

      /** Add source blobs for compose operation. */
      public SourceTargetBuilder addSources(Iterable<String> objects) {
        for (String object : objects) {
          inputBlobs.add(new SourceBlobInfo(object));
        }
        return this;
      }

      /** Add source blobs for compose operation. */
      public SourceTargetBuilder addSources(String... objects) {
        return addSources(Arrays.asList(objects));
      }

      /** Add a source with a specific generation to match. */
      public SourceTargetBuilder addSources(String object, long versionId) {
        inputBlobs.add(new SourceBlobInfo(object, versionId));
        return this;
      }

      /** Sets compose operation's target blob. */
      public SourceTargetBuilder setTarget(BlobInfo destination) {
        this.destination = destination;
        return this;
      }

      /** Sets compose operation's target blob options. */
      public SourceTargetBuilder setTargetOptions(BlobTargetOptions... settings) {
        Collections.addAll(destinationOptions, settings);
        return this;
      }

      /** Sets compose operation's target blob options. */
      public SourceTargetBuilder setTargetOptions(Iterable<BlobTargetOptions> settings) {
        Iterables.addAll(destinationOptions, settings);
        return this;
      }

      /** Creates a {@code ComposeObjectsRequest} object. */
      public ComposeObjectsRequest buildComposeObjectsRequest() {
        checkArgument(!inputBlobs.isEmpty());
        checkNotNull(destination);
        return new ComposeObjectsRequest(this);
      }
    }

    private ComposeObjectsRequest(SourceTargetBuilder requestBuilder) {
      inputBlobs = ImmutableList.copyOf(requestBuilder.inputBlobs);
      destination = requestBuilder.destination;
      destinationOptions = ImmutableList.copyOf(requestBuilder.destinationOptions);
    }

    /** Returns compose operation's source blobs. */
    public List<SourceBlobInfo> getSourceBlobs() {
      return inputBlobs;
    }

    /** Returns compose operation's target blob. */
    public BlobInfo getTarget() {
      return destination;
    }

    /** Returns compose operation's target blob's options. */
    public List<BlobTargetOptions> getTargetOptions() {
      return destinationOptions;
    }

    /**
     * Creates a {@code ComposeObjectsRequest} object.
     *
     * @param origins source blobs names
     * @param destination target blob
     */
    public static ComposeObjectsRequest from(Iterable<String> origins, BlobInfo destination) {
      return createBuilder().setTarget(destination).addSources(origins).buildComposeObjectsRequest();
    }

    /**
     * Creates a {@code ComposeObjectsRequest} object.
     *
     * @param bucketName name from the bucket where the compose operation takes place
     * @param origins source blobs names
     * @param destination target blob name
     */
    public static ComposeObjectsRequest of(String bucketName, Iterable<String> origins, String destination) {
      return from(origins, BlobInfo.newBuilder(BlobId.of(bucketName, destination)).build());
    }

    /** Returns a {@code ComposeObjectsRequest} builder. */
    public static SourceTargetBuilder createBuilder() {
      return new SourceTargetBuilder();
    }
  }

  /** A class to contain all information needed for a Google Cloud Storage Copy operation. */
  class CopyOperationRequest implements Serializable {

    private static final long CLASS_SERIAL_UID = -4498650529476219937L;

    private final BlobId origin;
    private final List<BlobSourceOptions> originOptions;
    private final boolean overwriteFlag;
    private final BlobInfo destination;
    private final List<BlobTargetOptions> destinationOptions;
    private final Long mbPerChunk;

    public static class CopyOperationBuilder {

      private final Set<BlobSourceOptions> originOptions = new LinkedHashSet<>();
      private final Set<BlobTargetOptions> destinationOptions = new LinkedHashSet<>();
      private BlobId origin;
      private boolean overwriteFlag;
      private BlobInfo destination;
      private Long mbPerChunk;

      /**
       * Sets the blob to copy given bucket and blob name.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSource(String bucketName, String object) {
        this.origin = BlobId.of(bucketName, object);
        return this;
      }

      /**
       * Sets the blob to copy given a {@link BlobId}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSource(BlobId origin) {
        this.origin = origin;
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSourceOptions(BlobSourceOptions... settings) {
        Collections.addAll(originOptions, settings);
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSourceOptions(Iterable<BlobSourceOptions> settings) {
        Iterables.addAll(originOptions, settings);
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId destinationId) {
        this.overwriteFlag = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source, except for those
       * options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId destinationId, BlobTargetOptions... settings) {
        this.overwriteFlag = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        Collections.addAll(destinationOptions, settings);
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
      public CopyOperationBuilder setTarget(BlobInfo destination, BlobTargetOptions... settings) {
        this.overwriteFlag = true;
        this.destination = checkNotNull(destination);
        Collections.addAll(destinationOptions, settings);
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
      public CopyOperationBuilder setTarget(BlobInfo destination, Iterable<BlobTargetOptions> settings) {
        this.overwriteFlag = true;
        this.destination = checkNotNull(destination);
        Iterables.addAll(destinationOptions, settings);
        return this;
      }

      /**
       * Sets the copy target and target options. Target blob information is copied from source,
       * except for those options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId destinationId, Iterable<BlobTargetOptions> settings) {
        this.overwriteFlag = false;
        this.destination = BlobInfo.newBuilder(destinationId).build();
        Iterables.addAll(destinationOptions, settings);
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
      public CopyOperationRequest buildRequest() {
        return new CopyOperationRequest(this);
      }
    }

    private CopyOperationRequest(CopyOperationBuilder requestBuilder) {
      origin = checkNotNull(requestBuilder.origin);
      originOptions = ImmutableList.copyOf(requestBuilder.originOptions);
      overwriteFlag = requestBuilder.overwriteFlag;
      destination = checkNotNull(requestBuilder.destination);
      destinationOptions = ImmutableList.copyOf(requestBuilder.destinationOptions);
      mbPerChunk = requestBuilder.mbPerChunk;
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
      return overwriteFlag;
    }

    /** Returns blob's target options. */
    public List<BlobTargetOptions> getTargetOptions() {
      return destinationOptions;
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
      return newCopyOperationBuilder().setSource(originBucket, originBlob).setTarget(destination).buildRequest();
    }

    /**
     * Creates a copy request. {@code target} parameter is used to replace source blob information
     * (e.g. {@code contentType}, {@code contentLanguage}). Target blob information is set exactly
     * to {@code target}, no information is inherited from the source blob.
     *
     * @param sourceBlobId a {@code BlobId} object for the source blob
     * @param destination a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId sourceBlobId, BlobInfo destination) {
      return newCopyOperationBuilder().setSource(sourceBlobId).setTarget(destination).buildRequest();
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
      return CopyOperationRequest.newCopyOperationBuilder()
          .setSource(originBucket, originBlob)
          .setTarget(BlobId.of(originBucket, destinationBlob))
          .buildRequest();
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
      return newCopyOperationBuilder().setSource(originBucket, originBlob).setTarget(destination).buildRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBlobId a {@code BlobId} object for the source blob
     * @param destinationBlob name from the target blob, in the same bucket from the source blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId sourceBlobId, String destinationBlob) {
      return CopyOperationRequest.newCopyOperationBuilder()
          .setSource(sourceBlobId)
          .setTarget(BlobId.of(sourceBlobId.getBucket(), destinationBlob))
          .buildRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBlobId a {@code BlobId} object for the source blob
     * @param targetBlobId a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId sourceBlobId, BlobId targetBlobId) {
      return CopyOperationRequest.newCopyOperationBuilder().setSource(sourceBlobId).setTarget(targetBlobId).buildRequest();
    }

    /** Creates a builder for {@code CopyOperationRequest} objects. */
    public static CopyOperationBuilder newCopyOperationBuilder() {
      return new CopyOperationBuilder();
    }
  }

  /**
   * Creates a new bucket.
   *
   * <p>Accepts an optional setUserProject {@link BucketTargetOptions} option which defines the project
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
   * Bucket bucket = storage.create(BucketInfo.createBuilder(bucketName)
   *     // See here for possible values: http://g.co/cloud/storage/docs/storage-classes
   *     .setStorageClass(StorageClass.COLDLINE)
   *     // Possible values: http://g.co/cloud/storage/docs/bucket-locations#location-mr
   *     .setLocation("asia")
   *     .buildComposeObjectsRequest());
   * }</pre>
   *
   * @return a complete bucket
   * @throws StorageException upon failure
   */
  Bucket create(BucketInfo bucketInfo, BucketTargetOptions... settings);

  /**
   * Creates a new blob with no content.
   *
   * <p>Example from creating a blob with no content.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId).setContentType("text/plain").buildComposeObjectsRequest();
   * StorageObject blob = storage.create(blobInfo);
   * }</pre>
   *
   * @return a [@code StorageObject} with complete information
   * @throws StorageException upon failure
   */
  StorageObject create(BlobInfo objectInfo, BlobTargetOptions... settings);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. MD5 and CRC32C hashes from {@code content}
   * are computed and used for validating transferred data. Accepts an optional setUserProject {@link
   * BlobGetOptions} option which defines the project id to assign operational costs.
   *
   * <p>Example from creating a blob from a byte array.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId).setContentType("text/plain").buildComposeObjectsRequest();
   * StorageObject blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8));
   * }</pre>
   *
   * @return a [@code StorageObject} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  StorageObject create(BlobInfo objectInfo, byte[] data, BlobTargetOptions... settings);

  /**
   * Creates a new blob with the sub array from the given byte array. Direct upload is used to upload
   * {@code content}. For large content, {@link #writer} is recommended as it uses resumable upload.
   * MD5 and CRC32C hashes from {@code content} are computed and used for validating transferred data.
   * Accepts a setUserProject {@link BlobGetOptions} option, which defines the project id to assign
   * operational costs.
   *
   * <p>Example from creating a blob from a byte array.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId).setContentType("text/plain").buildComposeObjectsRequest();
   * StorageObject blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8), 7, 5);
   * }</pre>
   *
   * @return a [@code StorageObject} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  StorageObject create(
          BlobInfo objectInfo, byte[] data, int startOffset, int size, BlobTargetOptions... settings);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. By default any md5 and crc32c values in
   * the given {@code blobInfo} are ignored unless requested via the {@code
   * BlobWriteOptions.requireMd5Match} and {@code BlobWriteOptions.ifCrc32cMatch} options. The given input
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
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId).setContentType("text/plain").buildComposeObjectsRequest();
   * StorageObject blob = storage.create(blobInfo, content);
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
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId)
   *     .setContentType("text/plain")
   *     .buildComposeObjectsRequest();
   * StorageObject blob = storage.create(blobInfo, content, BlobWriteOptions.customerSuppliedKey(customerSuppliedKey));
   * }</pre>
   *
   * @return a [@code StorageObject} with complete information
   * @throws StorageException upon failure
   */
  @Deprecated
  StorageObject create(BlobInfo objectInfo, InputStream data, BlobWriteOptions... settings);

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * <p>Accepts an optional setUserProject {@link GetBucketOption} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example from getting information on a bucket, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * Bucket bucket = storage.get(bucketName,
   *     GetBucketOption.ifMetagenerationMatch(bucketMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Bucket get(String bucketName, GetBucketOption... settings);

  /**
   * Locks bucket retention policy. Requires a local metageneration value in the request. Review
   * example below.
   *
   * <p>Accepts an optional setUserProject {@link BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Warning: Once a retention policy is locked, it can't be unlocked, removed, or shortened.
   *
   * <p>Example from locking a retention policy on a bucket, only if its local metageneration value
   * matches the bucket's service metageneration otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Bucket bucket = storage.get(bucketName, GetBucketOption.withFields(BucketAttribute.METAGENERATION));
   * storage.lockRetentionPolicy(bucket, BucketTargetOptions.ifMetagenerationMatch());
   * }</pre>
   *
   * @return a {@code Bucket} object from the locked bucket
   * @throws StorageException upon failure
   */
  Bucket lockRetentionPolicy(BucketInfo bucketName, BucketTargetOptions... settings);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional setUserProject {@link BlobGetOptions} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example from getting information on a blob, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * StorageObject blob = storage.get(bucketName, blobName,
   *     BlobGetOptions.ifMetagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  StorageObject get(String bucketName, String object, BlobGetOptions... settings);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional setUserProject {@link BlobGetOptions} option which defines the project id to
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
   * StorageObject blob = storage.get(blobId, BlobGetOptions.ifMetagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * <p>Example from getting information on a blob encrypted using Customer Supplied Encryption Keys,
   * only if supplied Decrpytion Key decrypts the blob successfully, otherwise a {@link
   * StorageException} is thrown. For more information review
   *
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/encryption/customer-supplied-keys#encrypted-elements">Encrypted
   *     Elements</a>
   *     <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String blobEncryptionKey = "";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * StorageObject blob = storage.get(blobId, BlobGetOptions.withDecryptionKey(blobEncryptionKey));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  StorageObject get(BlobId object, BlobGetOptions... settings);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Example from getting information on a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * StorageObject blob = storage.get(blobId);
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  StorageObject get(BlobId object);

  /**
   * Lists the project's buckets.
   *
   * <p>Example from listing buckets, specifying the page size and a name withPrefix.
   *
   * <pre>{@code
   * String withPrefix = "bucket_";
   * Page<Bucket> buckets = storage.list(BucketListOptions.setPageSize(100),
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
   * Lists the bucket's blobs. If the {@link BlobListOptions#currentDirectoryOnly()} option is provided,
   * results are returned in a directory-like mode.
   *
   * <p>Example from listing blobs in a provided directory.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String directory = "my_directory/";
   * Page<StorageObject> blobs = storage.list(bucketName, BlobListOptions.currentDirectoryOnly(),
   *     BlobListOptions.withPrefix(directory));
   * Iterator<StorageObject> blobIterator = blobs.iterateAll().iterator();
   * while (blobIterator.hasNext()) {
   *   StorageObject blob = blobIterator.next();
   *   // do something with the blob
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Page<StorageObject> list(String bucketName, BlobListOptions... settings);

  /**
   * Updates bucket information.
   *
   * <p>Accepts an optional setUserProject {@link BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from updating bucket information.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * BucketInfo bucketInfo = BucketInfo.createBuilder(bucketName).setVersioningEnabled(true).buildComposeObjectsRequest();
   * Bucket bucket = storage.save(bucketInfo);
   * }</pre>
   *
   * @return the updated bucket
   * @throws StorageException upon failure
   */
  Bucket update(BucketInfo bucketInfo, BucketTargetOptions... settings);

  /**
   * Updates the blob properties if the preconditions specified by {@code options} are met. The
   * property save works as described in {@link #update(BlobInfo)}.
   *
   * <p>{@code options} parameter can contain the preconditions for applying the save. E.g. save
   * from the blob properties might be required only if the properties have not been updated
   * externally. {@code StorageException} with the code {@code 412} is thrown if preconditions fail.
   *
   * <p>Example from updating the content type only if the properties are not updated externally:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId).setContentType("text/plain").buildComposeObjectsRequest();
   * StorageObject blob = storage.create(blobInfo);
   *
   * doSomething();
   *
   * BlobInfo save = blob.toBuilder().setContentType("multipart/form-data").buildComposeObjectsRequest();
   * Storage.BlobTargetOptions option = Storage.BlobTargetOptions.ifMetagenerationMatch();
   * try {
   *   storage.save(save, option);
   * } catch (StorageException e) {
   *   if (e.getCode() == 412) {
   *     // the properties were updated externally
   *   } else {
   *     throw e;
   *   }
   * }
   * }</pre>
   *
   * @param objectInfo information to save
   * @param settings preconditions to apply the save
   * @return the updated blob
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  StorageObject update(BlobInfo objectInfo, BlobTargetOptions... settings);

  /**
   * Updates the properties from the blob. This method issues an RPC request to merge the current blob
   * properties with the properties in the provided {@code blobInfo}. Properties not defined in
   * {@code blobInfo} will not be updated. To unset a blob property this property in {@code
   * blobInfo} should be explicitly set to {@code null}.
   *
   * <p>Bucket or blob's name cannot be changed by this method. If you want to rename the blob or
   * move it to a different bucket use the {@link StorageObject#copyToTarget} and {@link #delete} operations.
   *
   * <p>Property save alters the blob metadata generation and doesn't alter the blob generation.
   *
   * <p>Example from how to save blob's user provided metadata and unset the content type:
   *
   * <pre>{@code
   * Map<String, String> metadataUpdate = new HashMap<>();
   * metadataUpdate.put("keyToAdd", "new value");
   * metadataUpdate.put("keyToRemove", null);
   * BlobInfo blobUpdate = BlobInfo.createBuilder(bucketName, blobName)
   *     .setMetadata(metadataUpdate)
   *     .setContentType(null)
   *     .buildComposeObjectsRequest();
   * StorageObject blob = storage.save(blobUpdate);
   * }</pre>
   *
   * @param objectInfo information to save
   * @return the updated blob
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  StorageObject update(BlobInfo objectInfo);

  /**
   * Deletes the requested bucket.
   *
   * <p>Accepts an optional setUserProject {@link BucketOption} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from deleting a bucket, only if its metageneration matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * boolean deleted = storage.deleteObject(bucketName,
   *     BucketOption.ifMetagenerationMatch(bucketMetageneration));
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
  boolean delete(String bucketName, BucketOption... settings);

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
   * boolean deleted = storage.deleteObject(bucketName, blobName,
   *     BlobGetOptions.ifGenerationMatch(blobGeneration));
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
  boolean delete(String bucketName, String object, BlobSourceOptions... settings);

  /**
   * Deletes the requested blob.
   *
   * <p>Accepts an optional setUserProject {@link BlobSourceOptions} option which defines the project id
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
   * boolean deleted = storage.deleteObject(blobId, BlobGetOptions.ifGenerationMatch(blobGeneration));
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
  boolean delete(BlobId object, BlobSourceOptions... settings);

  /**
   * Deletes the requested blob.
   *
   * <p>Example from deleting a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * boolean deleted = storage.deleteObject(blobId);
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
   * <p>Accepts an optional setUserProject {@link BlobTargetOptions} option which defines the project id
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
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId).setContentType("text/plain").buildComposeObjectsRequest();
   * ComposeObjectsRequest request = ComposeObjectsRequest.createBuilder()
   *     .setTarget(blobInfo)
   *     .addSources(sourceBlob1)
   *     .addSources(sourceBlob2)
   *     .buildComposeObjectsRequest();
   * StorageObject blob = storage.compose(request);
   * }</pre>
   *
   * @return the composed blob
   * @throws StorageException upon failure
   */
  StorageObject compose(ComposeObjectsRequest composeObjectsRequest);

  /**
   * Sends a copy request. This method copies both blob's data and information. To override source
   * blob's information supply a {@code BlobInfo} to the {@code CopyOperationRequest} using either {@link
   * CopyOperationRequest.CopyOperationBuilder#setTarget(BlobInfo, BlobTargetOptions...)} or {@link
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
   * CopyOperationRequest request = CopyOperationRequest.createBuilder()
   *     .setSource(BlobId.from(bucketName, blobName))
   *     .setTarget(BlobId.from(bucketName, copyBlobName))
   *     .buildComposeObjectsRequest();
   * StorageObject blob = storage.copy(request).getResult();
   * }</pre>
   *
   * <p>Example from copying a blob in chunks.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * CopyOperationRequest request = CopyOperationRequest.createBuilder()
   *     .setSource(BlobId.from(bucketName, blobName))
   *     .setTarget(BlobId.from(bucketName, copyBlobName))
   *     .buildComposeObjectsRequest();
   * CopyWriter copyWriter = storage.copy(request);
   * while (!copyWriter.isDone()) {
   *   copyWriter.copyChunk();
   * }
   * StorageObject blob = copyWriter.getResult();
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
   * CopyOperationRequest request = CopyOperationRequest.createBuilder()
   *     .setSource(blobId)
   *     .setSourceOptions(BlobGetOptions.withDecryptionKey(oldEncryptionKey))
   *     .setTarget(blobId, BlobTargetOptions.customerSuppliedKey(newEncryptionKey))
   *     .buildComposeObjectsRequest();
   * StorageObject blob = storage.copy(request).getResult();
   * }</pre>
   *
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
   */
  CopyWriter copy(CopyOperationRequest copyOperationRequest);

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
   *     BlobGetOptions.ifGenerationMatch(blobGeneration));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
   */
  byte[] readAllBytes(String bucketName, String object, BlobSourceOptions... settings);

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
   *     bucketName, blobName, BlobGetOptions.withDecryptionKey(withDecryptionKey));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
   */
  byte[] readAllBytes(BlobId object, BlobSourceOptions... settings);

  /**
   * Creates a new empty batch for grouping multiple service calls in one underlying RPC call.
   *
   * <p>Example from using a batch request to deleteObject, save and get a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * StorageBatch batch = storage.batch();
   * BlobId firstBlob = BlobId.from(bucketName, blobName1);
   * BlobId secondBlob = BlobId.from(bucketName, blobName2);
   * batch.deleteObject(firstBlob).notify(new BatchResult.Callback<Boolean, StorageException>() {
   *   public void success(Boolean result) {
   *     // deleted successfully
   *   }
   *
   *   public void error(StorageException exception) {
   *     // deleteObject failed
   *   }
   * });
   * batch.save(BlobInfo.createBuilder(secondBlob).setContentType("text/plain").buildComposeObjectsRequest());
   * StorageBatchResult<StorageObject> result = batch.get(secondBlob);
   * batch.submit();
   * StorageObject blob = result.get(); // returns get result or throws StorageException
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
  ReadChannel reader(String bucketName, String object, BlobSourceOptions... settings);

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
  ReadChannel reader(BlobId object, BlobSourceOptions... settings);

  /**
   * Creates a blob and return a channel for writing its content. By default any md5 and crc32c
   * values in the given {@code blobInfo} are ignored unless requested via the {@code
   * BlobWriteOptions.requireMd5Match} and {@code BlobWriteOptions.ifCrc32cMatch} options.
   *
   * <p>Example from writing a blob's content through a getWriter.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId).setContentType("text/plain").buildComposeObjectsRequest();
   * try (WriteChannel getWriter = storage.getWriter(blobInfo)) {
   *   try {
   *     getWriter.write(ByteBuffer.wrap(content, 0, content.length));
   *   } catch (Exception ex) {
   *     // handle exception
   *   }
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  WriteChannel writer(BlobInfo objectInfo, BlobWriteOptions... settings);

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
   * BlobInfo blobInfo = BlobInfo.createBuilder(blobId).setContentType("text/plain").buildComposeObjectsRequest();
   * URL signedURL = storage.getSignedUrl(
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
  WriteChannel writer(URL presignedUrl);

  /**
   * Generates a signed URL for a blob. If you have a blob that you want to allow access to for a
   * fixed amount from time, you can use this method to generate a URL that is only valid within a
   * certain time period. This is particularly useful if you don't want publicly accessible blobs,
   * but also don't want to require users to explicitly log in. Signing a URL requires a service
   * account signer. If an instance from {@link com.google.auth.ServiceAccountSigner} was passed to
   * {@link StorageOptions}' builder via {@code setCredentials(Credentials)} or the default
   * credentials are being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS}
   * is set or your application is running in App Engine, then {@code getSignedUrl} will use that
   * credentials to sign the URL. If the credentials passed to {@link StorageOptions} do not
   * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
   * credentials) then {@code getSignedUrl} will throw an {@link IllegalStateException} unless an
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
   * URL signedUrl = storage.getSignedUrl(
   *     BlobInfo.createBuilder(bucketName, blobName).buildComposeObjectsRequest(),
   *     7, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#withSignatureV4()} option,
   * which enables V4 signing:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * URL signedUrl = storage.getSignedUrl(
   *     BlobInfo.createBuilder(bucketName, blobName).buildComposeObjectsRequest(),
   *     7, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.withSignatureV4());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#usingVirtualHostedStyle()}
   * option, which specifies the bucket name in the hostname from the URI, rather than in the path:
   *
   * <pre>{@code
   * URL signedUrl = storage.getSignedUrl(
   *     BlobInfo.createBuilder(bucketName, blobName).buildComposeObjectsRequest(),
   *     1, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.asVirtualHostedStyle());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#usePathStyle()} option,
   * which specifies the bucket name in path portion from the URI, rather than in the hostname:
   *
   * <pre>{@code
   * URL signedUrl = storage.getSignedUrl(
   *     BlobInfo.createBuilder(bucketName, blobName).buildComposeObjectsRequest(),
   *     1, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.enablePathStyle());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link
   * UrlSigningOption#withSigner(ServiceAccountSigner)} option, that will be used for signing the URL:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String kfPath = "/path/to/keyfile.json";
   * URL signedUrl = storage.getSignedUrl(
   *     BlobInfo.createBuilder(bucketName, blobName).buildComposeObjectsRequest(),
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
   * URL signedUrl = storage.getSignedUrl(
   *     BlobInfo.createBuilder(bucketName, blobName, generation).buildComposeObjectsRequest(),
   *     7, TimeUnit.DAYS,
   *     UrlSigningOption.withQueryParameters(ImmutableMap.from("generation", String.valueOf(generation))));
   * }</pre>
   *
   * @param objectInfo the blob associated with the signed URL
   * @param timeout time until the signed URL expires, expressed in {@code unit}. The finest
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param timeUnit time unit from the {@code duration} parameter
   * @param settings optional URL signing options
   * @throws IllegalStateException if {@link UrlSigningOption#withSigner(ServiceAccountSigner)} was not
   *     used and no implementation from {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.enableMd5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.enableContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  URL signUrl(BlobInfo objectInfo, long timeout, TimeUnit timeUnit, UrlSigningOption... settings);

  /**
   * Generates a URL and a map from withFields that can be specified in an HTML form to submit a POST
   * request. The returned map includes a signature which must be provided with the request.
   * Generating a presigned POST policy requires a service account signer. If an instance from {@link
   * com.google.auth.ServiceAccountSigner} was passed to {@link StorageOptions}' builder via {@code
   * setCredentials(Credentials)} or the default credentials are being used and the environment
   * variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set, generatPresignedPostPolicyV4 will use
   * that credentials to sign the URL. If the credentials passed to {@link StorageOptions} do not
   * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
   * credentials) then {@code getSignedUrl} will throw an {@link IllegalStateException} unless an
   * implementation from {@link ServiceAccountSigner} is passed using the {@link
   * PostPolicyV4Parameter#withSigner(ServiceAccountSigner)} option.
   *
   * <p>Example from generating a presigned post policy which has the condition that only jpeg images
   * can be uploaded, and applies the public read acl to each image uploaded, and making the POST
   * request:
   *
   * <pre>{@code
   * PostFieldsV4 withFields = PostFieldsV4.createBuilder().setAcl("public-read").buildComposeObjectsRequest();
   * PostConditionsV4 conditions = PostConditionsV4.createBuilder().addContentTypeCondition(ConditionV4Type.MATCHES, "image/jpeg").buildComposeObjectsRequest();
   *
   * PostPolicyV4 policy = storage.generateSignedPostPolicyV4(
   *     BlobInfo.createBuilder("my-bucket", "my-object").buildComposeObjectsRequest(),
   *     7, TimeUnit.DAYS, withFields, conditions);
   *
   * HttpClient client = HttpClientBuilder.create().buildComposeObjectsRequest();
   * HttpPost request = new HttpPost(policy.getUrl());
   * MultipartEntityBuilder builder = MultipartEntityBuilder.create();
   *
   * for (Map.Entry<String, String> entry : policy.getFields().entrySet()) {
   *     builder.addTextBody(entry.getKey(), entry.getValue());
   * }
   * File file = new File("path/to/your/file/to/upload");
   * builder.addBinaryBody("file", new FileInputStream(file), ContentType.APPLICATION_OCTET_STREAM, file.getName());
   * request.setEntity(builder.buildComposeObjectsRequest());
   * client.execute(request);
   * }</pre>
   *
   * @param objectInfo the blob uploaded in the form
   * @param attributes the withFields specified in the form
   * @param postConditions which conditions every upload must satisfy
   * @param timeout how long until the form expires, in milliseconds
   * @param settings optional post policy options
   * @see <a
   *     href="https://cloud.google.com/storage/docs/xml-api/post-object#usage_and_examples">POST
   *     Object</a>
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo objectInfo,
      long timeout,
      TimeUnit timeUnit,
      PostFieldsV4 attributes,
      PostConditionsV4 postConditions,
      PostPolicyV4Parameter... settings);

  /**
   * Generates a presigned post policy without any conditions. Automatically creates required
   * conditions. See full documentation for generateSignedPostPolicyV4( BlobInfo blobInfo, long
   * duration, TimeUnit unit, PostFieldsV4 withFields, PostConditionsV4 conditions,
   * PostPolicyV4Parameter... options) above.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo objectInfo,
      long timeout,
      TimeUnit timeUnit,
      PostFieldsV4 attributes,
      PostPolicyV4Parameter... settings);

  /**
   * Generates a presigned post policy without any withFields. Automatically creates required withFields.
   * See full documentation for generateSignedPostPolicyV4( BlobInfo blobInfo, long duration,
   * TimeUnit unit, PostFieldsV4 withFields, PostConditionsV4 conditions, PostPolicyV4Parameter... options)
   * above.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo objectInfo,
      long timeout,
      TimeUnit timeUnit,
      PostConditionsV4 postConditions,
      PostPolicyV4Parameter... settings);

  /**
   * Generates a presigned post policy without any withFields or conditions. Automatically creates
   * required withFields and conditions. See full documentation for generateSignedPostPolicyV4( BlobInfo
   * blobInfo, long duration, TimeUnit unit, PostFieldsV4 withFields, PostConditionsV4 conditions,
   * PostPolicyV4Parameter... options) above.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
          BlobInfo objectInfo, long timeout, TimeUnit timeUnit, PostPolicyV4Parameter... settings);

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
   * List<StorageObject> blobs = storage.get(firstBlob, secondBlob);
   * }</pre>
   *
   * @param objectIds blobs to get
   * @return an immutable list from {@code StorageObject} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageObject> get(BlobId... objectIds);

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
   * List<StorageObject> blobs = storage.get(blobIds);
   * }</pre>
   *
   * @param objectIds blobs to get
   * @return an immutable list from {@code StorageObject} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageObject> get(Iterable<BlobId> objectIds);

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
   * StorageObject firstBlob = storage.get(bucketName, blobName1);
   * StorageObject secondBlob = storage.get(bucketName, blobName2);
   * List<StorageObject> updatedBlobs = storage.save(
   *     firstBlob.toBuilder().setContentType("text/plain").buildComposeObjectsRequest(),
   *     secondBlob.toBuilder().setContentType("text/plain").buildComposeObjectsRequest());
   * }</pre>
   *
   * @param objectInfos blobs to save
   * @return an immutable list from {@code StorageObject} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageObject> update(BlobInfo... objectInfos);

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
   * StorageObject firstBlob = storage.get(bucketName, blobName1);
   * StorageObject secondBlob = storage.get(bucketName, blobName2);
   * List<BlobInfo> blobs = new LinkedList<>();
   * blobs.add(firstBlob.toBuilder().setContentType("text/plain").buildComposeObjectsRequest());
   * blobs.add(secondBlob.toBuilder().setContentType("text/plain").buildComposeObjectsRequest());
   * List<StorageObject> updatedBlobs = storage.save(blobs);
   * }</pre>
   *
   * @param objectInfos blobs to save
   * @return an immutable list from {@code StorageObject} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageObject> update(Iterable<BlobInfo> objectInfos);

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
   * List<Boolean> deleted = storage.deleteObject(firstBlob, secondBlob);
   * }</pre>
   *
   * @param objectIds blobs to deleteObject
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
   * List<Boolean> deleted = storage.deleteObject(blobIds);
   * }</pre>
   *
   * @param objectIds blobs to deleteObject
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
   * BucketOption userProjectOption = BucketOption.setUserProject("myProject");
   * Acl acl = storage.getAcl(bucketName, new User(userEmail), userProjectOption);
   * }</pre>
   *
   * @param bucketName name from the bucket where the getAcl operation takes place
   * @param entity ACL entity to fetch
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl getAcl(String bucketName, Entity entity, BucketOption... settings);

  /** @see #getAcl(String, Entity, BucketOption...) */
  Acl getAcl(String bucketName, Entity entity);

  /**
   * Deletes the ACL entry for the specified entity on the specified bucket.
   *
   * <p>Example from deleting the ACL entry for an entity on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * boolean deleted = storage.removeAcl(bucketName, User.ofAllAuthenticatedUsers());
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
   * BucketOption setUserProject = BucketOption.setUserProject("myProject");
   * boolean deleted = storage.removeAcl(bucketName, User.ofAllAuthenticatedUsers(), setUserProject);
   * }</pre>
   *
   * @param bucketName name from the bucket to deleteObject an ACL from
   * @param entity ACL entity to deleteObject
   * @param settings extra parameters to apply to this operation
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String bucketName, Entity entity, BucketOption... settings);

  /** @see #deleteAcl(String, Entity, BucketOption...) */
  boolean deleteAcl(String bucketName, Entity entity);

  /**
   * Creates a new ACL entry on the specified bucket.
   *
   * <p>Example from creating a new ACL entry on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.createBlobAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER));
   * }</pre>
   *
   * <p>Example from creating a new ACL entry on a requester_pays bucket with a user_project option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.createBlobAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER),
   *     BucketOption.setUserProject("myProject"));
   * }</pre>
   *
   * @param bucketName name from the bucket for which an ACL should be created
   * @param accessList ACL to create
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl createAcl(String bucketName, Acl accessList, BucketOption... settings);

  /** @see #createAcl(String, Acl, BucketOption...) */
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
   *     BucketOption.setUserProject("myProject"));
   * }</pre>
   *
   * @param bucketName name from the bucket where the updateAccessControlList operation takes place
   * @param accessList ACL to save
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl updateAcl(String bucketName, Acl accessList, BucketOption... settings);

  /** @see #updateAcl(String, Acl, BucketOption...) */
  Acl updateAcl(String bucketName, Acl accessList);

  /**
   * Lists the ACL entries for the provided bucket.
   *
   * <p>Example from listing the ACL entries for a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * List<Acl> acls = storage.listAclEntries(bucketName);
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
   * List<Acl> acls = storage.listAclEntries(bucketName, BucketOption.setUserProject("myProject"));
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @param bucketName the name from the bucket to list ACLs for
   * @param settings any number from BucketSourceOptions to apply to this operation
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(String bucketName, BucketOption... settings);

  /** @see #listAcls(String, BucketOption...) */
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
  Acl getDefaultAcl(String bucketName, Entity entity);

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
  boolean deleteDefaultAcl(String bucketName, Entity entity);

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
  Acl getAcl(BlobId object, Entity entity);

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
   * boolean deleted = storage.removeAcl(blobId, User.ofAllAuthenticatedUsers());
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
  boolean deleteAcl(BlobId object, Entity entity);

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
   * Acl acl = storage.createBlobAcl(blobId, Acl.from(User.ofAllAuthenticatedUsers(), Role.READER));
   * }</pre>
   *
   * <p>Example from updating a blob to be public-read.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName, blobGeneration);
   * Acl acl = storage.createBlobAcl(blobId, Acl.from(User.ofAllUsers(), Role.READER));
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
   * List<Acl> acls = storage.listAclEntries(blobId);
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
   * ServiceAccount withServiceAccount = ServiceAccount.from("my-service-account@google.com");
   *
   * HmacKey hmacKey = storage.createHmacKey(withServiceAccount);
   *
   * String secretKey = hmacKey.getSecretKey();
   * HmacKey.HmacKeyMetadata metadata = hmacKey.getMetadata();
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  HmacKey createHmacKey(ServiceAccount svcAccount, HmacKeyCreationOption... settings);

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
   * ServiceAccount withServiceAccount = ServiceAccount.from("my-service-account@google.com");
   *
   * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
   *     Storage.ListHmacKeysOptions.withServiceAccount(withServiceAccount),
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
  HmacKeyMetadata getHmacKey(String accessIdentifier, GetHmacKeyOptions... settings);

  /**
   * Deletes an HMAC key. Note that only an {@code INACTIVE} key can be deleted. Attempting to
   * deleteObject a key whose {@code HmacKey.HmacKeyState} is anything other than {@code INACTIVE} will
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
  void deleteHmacKey(HmacKeyMetadata hmacMetadata, HmacKeyDeleteOption... settings);

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
      final HmacKey.HmacKeyState keyState,
      HmacKeyUpdateOption... settings);

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
   * @param bucketName name from the bucket where the getIamPolicy operation takes place
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy getIamPolicy(String bucketName, BucketOption... settings);

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
   *             .buildComposeObjectsRequest());
   * }</pre>
   *
   * @param bucketName name from the bucket where the setIamPolicy operation takes place
   * @param iamPolicy policy to be set on the specified bucket
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy setIamPolicy(String bucketName, Policy iamPolicy, BucketOption... settings);

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
   * @param bucketName name from the bucket where the testIamPermissions operation takes place
   * @param accessPermissions list from permissions to test on the bucket
   * @param settings extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  List<Boolean> testIamPermissions(
          String bucketName, List<String> accessPermissions, BucketOption... settings);

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
}
