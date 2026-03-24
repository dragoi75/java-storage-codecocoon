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
import com.google.cloud.storage.FormPostPolicyV4.PostConditionsV4Dto;
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

  enum PredefinedAclEntry {
    AUTHENTICATED_READ("authenticatedRead"),
    ALL_AUTHENTICATED_USERS("allAuthenticatedUsers"),
    PRIVATE("private"),
    PROJECT_PRIVATE("projectPrivate"),
    PUBLIC_READ("publicRead"),
    PUBLIC_READ_WRITE("publicReadWrite"),
    BUCKET_OWNER_READ("bucketOwnerRead"),
    BUCKET_OWNER_FULL_CONTROL("bucketOwnerFullControl");

    private final String entry;

    PredefinedAclEntry(String entry) {
      this.entry = entry;
    }

    String getEntry() {
      return entry;
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

    static final List<? extends FieldSelector> REQUIRED_FIELDS = ImmutableList.of(NAME);

    private final String selector;

    BucketMetadataField(String selector) {
      this.selector = selector;
    }

    @Override
    public String getSelector() {
      return selector;
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

    static final List<? extends FieldSelector> REQUIRED_FIELDS = ImmutableList.of(BUCKET, NAME);

    private final String selector;

    BlobMetadataField(String selector) {
      this.selector = selector;
    }

    @Override
    public String getSelector() {
      return selector;
    }
  }

  enum UriProtocol {
    HTTP("http"),
    HTTPS("https");

    private final String scheme;

    UriProtocol(String scheme) {
      this.scheme = scheme;
    }

    public String getScheme() {
      return scheme;
    }
  }

  /** Class for specifying bucket target options. */
  class TargetBucketOption extends Option {

    private static final long serialVersionUID = -5880204616982900975L;

    private TargetBucketOption(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    private TargetBucketOption(StorageRpc.RequestOption rpcOption) {
      this(rpcOption, null);
    }

    /** Returns an option for specifying bucket's predefined ACL configuration. */
    public static TargetBucketOption withPredefinedAcl(PredefinedAclEntry acl) {
      return new TargetBucketOption(StorageRpc.RequestOption.PREDEFINED_ACL, acl.getEntry());
    }

    /** Returns an option for specifying bucket's default ACL configuration for blobs. */
    public static TargetBucketOption predefinedDefaultObjectAcl(PredefinedAclEntry acl) {
      return new TargetBucketOption(
          StorageRpc.RequestOption.PREDEFINED_DEFAULT_OBJECT_ACL, acl.getEntry());
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static TargetBucketOption ifMetagenerationMatch() {
      return new TargetBucketOption(StorageRpc.RequestOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static TargetBucketOption ifMetagenerationNotMatch() {
      return new TargetBucketOption(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     */
    public static TargetBucketOption withUserProject(String userProject) {
      return new TargetBucketOption(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to define the projection in the API request. In some cases this option may
     * be needed to be set to `noAcl` to omit ACL data from the response. The default getValue is
     * `full`
     *
     * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/buckets/patch">Buckets:
     *     patch</a>
     */
    public static TargetBucketOption projection(String projection) {
      return new TargetBucketOption(StorageRpc.RequestOption.PROJECTION, projection);
    }
  }

  /** Class for specifying bucket source options. */
  class BucketSourceOptions extends Option {

    private static final long serialVersionUID = 5185657617120212117L;

    private BucketSourceOptions(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided getValue.
     */
    public static BucketSourceOptions withMetagenerationMatch(long metageneration) {
      return new BucketSourceOptions(StorageRpc.RequestOption.IF_METAGENERATION_MATCH, metageneration);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided getValue.
     */
    public static BucketSourceOptions ifMetagenerationNotMatch(long metageneration) {
      return new BucketSourceOptions(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH, metageneration);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketSourceOptions setUserProject(String userProject) {
      return new BucketSourceOptions(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    public static BucketSourceOptions withRequestedPolicyVersion(long version) {
      return new BucketSourceOptions(StorageRpc.RequestOption.REQUESTED_POLICY_VERSION, version);
    }
  }

  /** Class for specifying listHmacKeys options */
  class HmacKeyListOption extends Option {
    private HmacKeyListOption(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for the Service Account whose keys to list. If this option is not used,
     * keys for all accounts will be listed.
     */
    public static HmacKeyListOption ofServiceAccount(ServiceAccount serviceAccount) {
      return new HmacKeyListOption(
          StorageRpc.RequestOption.SERVICE_ACCOUNT_EMAIL, serviceAccount.getEmail());
    }

    /** Returns an option for the maximum amount from HMAC keys returned per page. */
    public static HmacKeyListOption withMaxResults(long pageSize) {
      return new HmacKeyListOption(StorageRpc.RequestOption.MAX_RESULTS, pageSize);
    }

    /** Returns an option to specify the page token from which to start listing HMAC keys. */
    public static HmacKeyListOption pageToken(String pageToken) {
      return new HmacKeyListOption(StorageRpc.RequestOption.PAGE_TOKEN, pageToken);
    }

    /**
     * Returns an option to specify whether to show deleted keys in the result. This option is false
     * by default.
     */
    public static HmacKeyListOption showDeletedKeys(boolean showDeletedKeys) {
      return new HmacKeyListOption(StorageRpc.RequestOption.SHOW_DELETED_KEYS, showDeletedKeys);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyListOption userProject(String userProject) {
      return new HmacKeyListOption(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static HmacKeyListOption projectId(String projectId) {
      return new HmacKeyListOption(StorageRpc.RequestOption.PROJECT_ID, projectId);
    }
  }

  /** Class for specifying createHmacKey options */
  class CreateHmacKeyOptions extends Option {
    private CreateHmacKeyOptions(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static CreateHmacKeyOptions userProject(String userProject) {
      return new CreateHmacKeyOptions(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static CreateHmacKeyOptions projectId(String projectId) {
      return new CreateHmacKeyOptions(StorageRpc.RequestOption.PROJECT_ID, projectId);
    }
  }

  /** Class for specifying getHmacKey options */
  class HmacKeyRetrievalOption extends Option {
    private HmacKeyRetrievalOption(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyRetrievalOption userProject(String userProject) {
      return new HmacKeyRetrievalOption(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static HmacKeyRetrievalOption projectId(String projectId) {
      return new HmacKeyRetrievalOption(StorageRpc.RequestOption.PROJECT_ID, projectId);
    }
  }

  /** Class for specifying deleteHmacKey options */
  class HmacKeyDeleteOption extends Option {
    private HmacKeyDeleteOption(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyDeleteOption userProject(String userProject) {
      return new HmacKeyDeleteOption(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }
  }

  /** Class for specifying updateHmacKey options */
  class HmacKeyUpdateOption extends Option {
    private HmacKeyUpdateOption(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyUpdateOption userProject(String userProject) {
      return new HmacKeyUpdateOption(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }
  }

  /** Class for specifying bucket get options. */
  class BucketGetOptions extends Option {

    private static final long serialVersionUID = 1901844869484087395L;

    private BucketGetOptions(StorageRpc.RequestOption rpcOption, long metageneration) {
      super(rpcOption, metageneration);
    }

    private BucketGetOptions(StorageRpc.RequestOption rpcOption, String value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided getValue.
     */
    public static BucketGetOptions ifMetagenerationMatch(long metageneration) {
      return new BucketGetOptions(StorageRpc.RequestOption.IF_METAGENERATION_MATCH, metageneration);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided getValue.
     */
    public static BucketGetOptions ifMetagenerationNotMatch(long metageneration) {
      return new BucketGetOptions(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH, metageneration);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketGetOptions setUserProject(String userProject) {
      return new BucketGetOptions(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the bucket's setFields to be returned by the RPC call. If this
     * option is not provided all bucket's setFields are returned. {@code BucketGetOptions.setFields}) can
     * be used to specify only the setFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static BucketGetOptions setFields(BucketMetadataField... fields) {
      return new BucketGetOptions(
          StorageRpc.RequestOption.FIELDS, Helper.selector(BucketMetadataField.REQUIRED_FIELDS, fields));
    }
  }

  /** Class for specifying blob target options. */
  class BlobUploadOption extends Option {

    private static final long serialVersionUID = 214616862061934846L;

    private BlobUploadOption(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    private BlobUploadOption(StorageRpc.RequestOption rpcOption) {
      this(rpcOption, null);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobUploadOption withPredefinedAcl(PredefinedAclEntry acl) {
      return new BlobUploadOption(StorageRpc.RequestOption.PREDEFINED_ACL, acl.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobUploadOption ifDoesNotExist() {
      return new BlobUploadOption(StorageRpc.RequestOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static BlobUploadOption ifGenerationMatch() {
      return new BlobUploadOption(StorageRpc.RequestOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static BlobUploadOption ifGenerationNotMatch() {
      return new BlobUploadOption(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobUploadOption ifMetagenerationMatch() {
      return new BlobUploadOption(StorageRpc.RequestOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobUploadOption ifMetagenerationNotMatch() {
      return new BlobUploadOption(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data disabledGzipContent. If this option is used, the request
     * will create a blob with disableGzipCompression; at present, this is only for upload.
     */
    public static BlobUploadOption disableGzipCompression() {
      return new BlobUploadOption(StorageRpc.RequestOption.IF_DISABLE_GZIP_CONTENT, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobUploadOption encryptionKeyOption(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new BlobUploadOption(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobUploadOption withUserProject(String userProject) {
      return new BlobUploadOption(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static BlobUploadOption customerSuppliedKey(String key) {
      return new BlobUploadOption(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, key);
    }

    /** Returns an option to set a customer-managed key for server-side encryption from the blob. */
    public static BlobUploadOption withKmsKeyName(String kmsKeyName) {
      return new BlobUploadOption(StorageRpc.RequestOption.KMS_KEY_NAME, kmsKeyName);
    }

    static Tuple<BlobInfo, BlobUploadOption[]> convertOptions(BlobInfo info, ObjectWriteOption... options) {
      BlobInfo.Builder infoBuilder = info.toBuilder().setCrc32c(null).setMd5(null);
      List<BlobUploadOption> targetOptions = Lists.newArrayListWithCapacity(options.length);
      for (ObjectWriteOption option : options) {
        switch (option.option) {
          case IF_CRC32C_MATCH:
            infoBuilder.setCrc32c(info.getCrc32c());
            break;
          case IF_MD5_MATCH:
            infoBuilder.setMd5(info.getMd5());
            break;
          default:
            targetOptions.add(option.toBlobUploadOption());
            break;
        }
      }
      return Tuple.of(
          infoBuilder.build(), targetOptions.toArray(new BlobUploadOption[targetOptions.size()]));
    }
  }

  /** Class for specifying blob write options. */
  class ObjectWriteOption implements Serializable {

    private static final long serialVersionUID = -3880421670966224580L;

    private final StorageOption option;
    private final Object value;

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

      StorageRpc.RequestOption toRequestOption() {
        return StorageRpc.RequestOption.valueOf(this.name());
      }
    }

    BlobUploadOption toBlobUploadOption() {
      return new BlobUploadOption(this.option.toRequestOption(), this.value);
    }

    private ObjectWriteOption(StorageOption option, Object value) {
      this.option = option;
      this.value = value;
    }

    private ObjectWriteOption(StorageOption option) {
      this(option, null);
    }

    @Override
    public int hashCode() {
      return Objects.hash(option, value);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (!(obj instanceof ObjectWriteOption)) {
        return false;
      }
      final ObjectWriteOption other = (ObjectWriteOption) obj;
      return this.option == other.option && Objects.equals(this.value, other.value);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static ObjectWriteOption withPredefinedAcl(PredefinedAclEntry acl) {
      return new ObjectWriteOption(StorageOption.PREDEFINED_ACL, acl.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static ObjectWriteOption ifNotExists() {
      return new ObjectWriteOption(StorageOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static ObjectWriteOption ifGenerationMatch() {
      return new ObjectWriteOption(StorageOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static ObjectWriteOption ifGenerationNotMatch() {
      return new ObjectWriteOption(StorageOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static ObjectWriteOption ifMetagenerationMatch() {
      return new ObjectWriteOption(StorageOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static ObjectWriteOption ifMetagenerationNotMatch() {
      return new ObjectWriteOption(StorageOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data MD5 hash match. If this option is used the request will
     * fail if blobs' data MD5 hash does not match.
     */
    public static ObjectWriteOption ifMd5Match() {
      return new ObjectWriteOption(StorageOption.IF_MD5_MATCH, true);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match.
     */
    public static ObjectWriteOption ifCrc32cMatch() {
      return new ObjectWriteOption(StorageOption.IF_CRC32C_MATCH, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static ObjectWriteOption customerSuppliedKey(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new ObjectWriteOption(StorageOption.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static ObjectWriteOption customerSuppliedKey(String key) {
      return new ObjectWriteOption(StorageOption.CUSTOMER_SUPPLIED_KEY, key);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption from the blob.
     *
     * @param kmsKeyName the KMS key resource id
     */
    public static ObjectWriteOption kmsKey(String kmsKeyName) {
      return new ObjectWriteOption(StorageOption.KMS_KEY_NAME, kmsKeyName);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static ObjectWriteOption withUserProject(String userProject) {
      return new ObjectWriteOption(StorageOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option that signals automatic gzip compression should not be performed en route to
     * the bucket.
     */
    public static ObjectWriteOption withGzipDisabled() {
      return new ObjectWriteOption(StorageOption.IF_DISABLE_GZIP_CONTENT, true);
    }
  }

  /** Class for specifying blob source options. */
  class BlobSourceOptions extends Option {

    private static final long serialVersionUID = -3712768261070182991L;

    private BlobSourceOptions(StorageRpc.RequestOption rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation getValue to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobSourceOptions ifGenerationMatch() {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_GENERATION_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided getValue.
     */
    public static BlobSourceOptions ifGenerationMatch(long generation) {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_GENERATION_MATCH, generation);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation getValue to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobSourceOptions generationNotMatch() {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided getValue.
     */
    public static BlobSourceOptions ifGenerationNotMatch(long generation) {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH, generation);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided getValue.
     */
    public static BlobSourceOptions ifMetagenerationMatch(long metageneration) {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_METAGENERATION_MATCH, metageneration);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided getValue.
     */
    public static BlobSourceOptions ifMetagenerationNotMatch(long metageneration) {
      return new BlobSourceOptions(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH, metageneration);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobSourceOptions withDecryptionKey(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new BlobSourceOptions(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static BlobSourceOptions customerSuppliedKey(String key) {
      return new BlobSourceOptions(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, key);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobSourceOptions withUserProject(String userProject) {
      return new BlobSourceOptions(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }
  }

  /** Class for specifying blob get options. */
  class BlobGetOptions extends Option {

    private static final long serialVersionUID = 803817709703661480L;

    private BlobGetOptions(StorageRpc.RequestOption rpcOption, Long value) {
      super(rpcOption, value);
    }

    private BlobGetOptions(StorageRpc.RequestOption rpcOption, String value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation getValue to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobGetOptions ifGenerationMatch() {
      return new BlobGetOptions(StorageRpc.RequestOption.IF_GENERATION_MATCH, (Long) null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided getValue.
     */
    public static BlobGetOptions ifGenerationMatch(long generation) {
      return new BlobGetOptions(StorageRpc.RequestOption.IF_GENERATION_MATCH, generation);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation getValue to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobGetOptions generationNotMatch() {
      return new BlobGetOptions(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH, (Long) null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided getValue.
     */
    public static BlobGetOptions ifGenerationNotMatch(long generation) {
      return new BlobGetOptions(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH, generation);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided getValue.
     */
    public static BlobGetOptions ifMetagenerationMatch(long metageneration) {
      return new BlobGetOptions(StorageRpc.RequestOption.IF_METAGENERATION_MATCH, metageneration);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided getValue.
     */
    public static BlobGetOptions ifMetagenerationNotMatch(long metageneration) {
      return new BlobGetOptions(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH, metageneration);
    }

    /**
     * Returns an option to specify the blob's setFields to be returned by the RPC call. If this option
     * is not provided all blob's setFields are returned. {@code BlobGetOptions.setFields}) can be used to
     * specify only the setFields from interest. Blob name and bucket are always returned, even if not
     * specified.
     */
    public static BlobGetOptions setFields(BlobMetadataField... fields) {
      return new BlobGetOptions(
          StorageRpc.RequestOption.FIELDS, Helper.selector(BlobMetadataField.REQUIRED_FIELDS, fields));
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobGetOptions withUserProject(String userProject) {
      return new BlobGetOptions(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     */
    public static BlobGetOptions withDecryptionKey(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new BlobGetOptions(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static BlobGetOptions customerSuppliedKey(String key) {
      return new BlobGetOptions(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, key);
    }
  }

  /** Class for specifying bucket list options. */
  class ListBucketsOption extends Option {

    private static final long serialVersionUID = 8754017079673290353L;

    private ListBucketsOption(StorageRpc.RequestOption option, Object value) {
      super(option, value);
    }

    /** Returns an option to specify the maximum number from buckets returned per page. */
    public static ListBucketsOption maxResults(long pageSize) {
      return new ListBucketsOption(StorageRpc.RequestOption.MAX_RESULTS, pageSize);
    }

    /** Returns an option to specify the page token from which to start listing buckets. */
    public static ListBucketsOption pageToken(String pageToken) {
      return new ListBucketsOption(StorageRpc.RequestOption.PAGE_TOKEN, pageToken);
    }

    /**
     * Returns an option to set a withPrefix to filter results to buckets whose names begin with this
     * withPrefix.
     */
    public static ListBucketsOption withPrefix(String prefix) {
      return new ListBucketsOption(StorageRpc.RequestOption.PREFIX, prefix);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static ListBucketsOption withUserProject(String userProject) {
      return new ListBucketsOption(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the bucket's setFields to be returned by the RPC call. If this
     * option is not provided all bucket's setFields are returned. {@code ListBucketsOption.setFields}) can
     * be used to specify only the setFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static ListBucketsOption withFields(BucketMetadataField... fields) {
      return new ListBucketsOption(
          StorageRpc.RequestOption.FIELDS,
          Helper.listSelector("items", BucketMetadataField.REQUIRED_FIELDS, fields));
    }
  }

  /** Class for specifying blob list options. */
  class BlobListOptions extends Option {

    private static final String[] TOP_LEVEL_FIELDS = {"prefixes"};
    private static final long serialVersionUID = 9083383524788661294L;

    private BlobListOptions(StorageRpc.RequestOption option, Object value) {
      super(option, value);
    }

    /** Returns an option to specify the maximum number from blobs returned per page. */
    public static BlobListOptions pageLimit(long pageSize) {
      return new BlobListOptions(StorageRpc.RequestOption.MAX_RESULTS, pageSize);
    }

    /** Returns an option to specify the page token from which to start listing blobs. */
    public static BlobListOptions pageToken(String pageToken) {
      return new BlobListOptions(StorageRpc.RequestOption.PAGE_TOKEN, pageToken);
    }

    /**
     * Returns an option to set a withPrefix to filter results to blobs whose names begin with this
     * withPrefix.
     */
    public static BlobListOptions withPrefix(String prefix) {
      return new BlobListOptions(StorageRpc.RequestOption.PREFIX, prefix);
    }

    /**
     * If specified, results are returned in a directory-like mode. Blobs whose names, after a
     * possible {@link #withPrefix (String)}, do not contain the '/' withDelimiter are returned as is. Blobs
     * whose names, after a possible {@link #withPrefix (String)}, contain the '/' withDelimiter, will have
     * their name truncated after the withDelimiter and will be returned as {@link Blob} objects where
     * only {@link Blob#getBlobId()}, {@link Blob#getSize()} and {@link Blob#isDirectory()} are set.
     * For such directory blobs, ({@link BlobId#getGeneration()} returns {@code null}), {@link
     * Blob#getSize()} returns {@code 0} while {@link Blob#isDirectory()} returns {@code true}.
     * Duplicate directory blobs are omitted.
     */
    public static BlobListOptions currentDirectoryOptions() {
      return new BlobListOptions(StorageRpc.RequestOption.DELIMITER, true);
    }

    /**
     * Returns an option to set a withDelimiter.
     *
     * @param delimiter generally '/' is the one used most often, but you can used other delimiters
     *     as well.
     */
    public static BlobListOptions withDelimiter(String delimiter) {
      return new BlobListOptions(StorageRpc.RequestOption.DELIMITER, delimiter);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     *
     * @param userProject projectId from the billing user project.
     */
    public static BlobListOptions setUserProject(String userProject) {
      return new BlobListOptions(StorageRpc.RequestOption.USER_PROJECT, userProject);
    }

    /**
     * If set to {@code true}, lists all includeVersions from a blob. The default is {@code false}.
     *
     * @see <a href="https://cloud.google.com/storage/docs/object-versioning">Object Versioning</a>
     */
    public static BlobListOptions includeVersions(boolean versions) {
      return new BlobListOptions(StorageRpc.RequestOption.VERSIONS, versions);
    }

    /**
     * Returns an option to specify the blob's setFields to be returned by the RPC call. If this option
     * is not provided all blob's setFields are returned. {@code BlobListOptions.setFields}) can be used to
     * specify only the setFields from interest. Blob name and bucket are always returned, even if not
     * specified.
     */
    public static BlobListOptions withFields(BlobMetadataField... fields) {
      return new BlobListOptions(
          StorageRpc.RequestOption.FIELDS,
          Helper.listSelector(TOP_LEVEL_FIELDS, "items", BlobMetadataField.REQUIRED_FIELDS, fields));
    }
  }

  /** Class for specifying Post Policy V4 options. * */
  class PostPolicyV4Parameter implements Serializable {
    private static final long serialVersionUID = 8150867146534084543L;
    private final StorageOption option;
    private final Object value;

    enum StorageOption {
      PATH_STYLE,
      VIRTUAL_HOSTED_STYLE,
      BUCKET_BOUND_HOST_NAME,
      SERVICE_ACCOUNT_CRED
    }

    private PostPolicyV4Parameter(StorageOption option, Object value) {
      this.option = option;
      this.value = value;
    }

    StorageOption getOption() {
      return option;
    }

    Object getValue() {
      return value;
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
    public static PostPolicyV4Parameter usePathStyle() {
      return new PostPolicyV4Parameter(StorageOption.PATH_STYLE, "");
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code asVirtualHostedStyle()} or {@code usePathStyle()}. This
     * method signature uses HTTP for the URI scheme, and is equivalent to calling {@code
     * withBucketBoundHostName("...", UriProtocol.HTTP).}
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static PostPolicyV4Parameter withBucketBoundHostname(String bucketBoundHostname) {
      return withBucketBoundHostName(bucketBoundHostname, UriProtocol.HTTP);
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code asVirtualHostedStyle()} or {@code usePathStyle()}. The
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
        String bucketBoundHostname, UriProtocol uriScheme) {
      return new PostPolicyV4Parameter(
          StorageOption.BUCKET_BOUND_HOST_NAME,
          uriScheme.getScheme() + "://" + bucketBoundHostname);
    }
  }

  /** Class for specifying signed URL options. */
  class UrlSigningOption implements Serializable {

    private static final long serialVersionUID = 7850569877451099267L;

    private final RequestOption option;
    private final Object value;

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

    private UrlSigningOption(RequestOption option, Object value) {
      this.option = option;
      this.value = value;
    }

    RequestOption getOption() {
      return option;
    }

    Object getValue() {
      return value;
    }

    /**
     * The HTTP method to be used with the signed URL. If this method is not called, defaults to
     * GET.
     */
    public static UrlSigningOption withHttpMethod(HttpMethod httpMethod) {
      return new UrlSigningOption(RequestOption.HTTP_METHOD, httpMethod);
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
    public static UrlSigningOption withExtraHeaders(Map<String, String> extHeaders) {
      return new UrlSigningOption(RequestOption.EXT_HEADERS, extHeaders);
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
    public static UrlSigningOption signUsing(ServiceAccountSigner signer) {
      return new UrlSigningOption(RequestOption.SERVICE_ACCOUNT_CRED, signer);
    }

    /**
     * Use a different host name than the default host name 'storage.googleapis.com'. This option is
     * particularly useful for developers to point requests to an alternate endpoint (e.g. a staging
     * environment or sending requests through VPC). If using this with the {@code
     * asVirtualHostedStyle()} method, you should omit the bucket name from the hostname, as it
     * automatically gets prepended to the hostname for virtual hosted-style URLs.
     */
    public static UrlSigningOption withHostname(String hostName) {
      return new UrlSigningOption(RequestOption.HOST_NAME, hostName);
    }

    /**
     * Use a virtual hosted-style hostname, which adds the bucket into the host portion from the URI
     * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name is
     * obtained from the resource passed in. For V4 signing, this also sets the "host" header in the
     * canonicalized extension headers to the virtual hosted-style host, unless that header is
     * supplied via the {@code withExtraHeaders()} method.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static UrlSigningOption useVirtualHostedStyle() {
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
    public static UrlSigningOption pathStyle() {
      return new UrlSigningOption(RequestOption.PATH_STYLE, "");
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. This cannot be
     * used alongside {@code asVirtualHostedStyle()} or {@code usePathStyle()}. This method
     * signature uses HTTP for the URI scheme, and is equivalent to calling {@code
     * withBucketBoundHostName("...", UriProtocol.HTTP).}
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static UrlSigningOption withBucketBoundHostname(String bucketBoundHostname) {
      return withBucketBoundHostName(bucketBoundHostname, UriProtocol.HTTP);
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code asVirtualHostedStyle()} or {@code usePathStyle()}. The
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
        String bucketBoundHostname, UriProtocol uriScheme) {
      return new UrlSigningOption(
          RequestOption.BUCKET_BOUND_HOST_NAME, uriScheme.getScheme() + "://" + bucketBoundHostname);
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
    public static UrlSigningOption withQueryParameters(Map<String, String> queryParams) {
      return new UrlSigningOption(RequestOption.QUERY_PARAMS, queryParams);
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

    private final List<SourceBlobInfo> sourceBlobs;
    private final BlobInfo target;
    private final List<BlobUploadOption> targetOptions;

    /** Class for Compose source blobs. */
    public static class SourceBlobInfo implements Serializable {

      private static final long serialVersionUID = 4094962795951990439L;

      final String name;
      final Long generation;

      SourceBlobInfo(String name) {
        this(name, null);
      }

      SourceBlobInfo(String name, Long generation) {
        this.name = name;
        this.generation = generation;
      }

      public String getName() {
        return name;
      }

      public Long getGeneration() {
        return generation;
      }
    }

    public static class TargetBuilder {

      private final List<SourceBlobInfo> sourceBlobs = new LinkedList<>();
      private final Set<BlobUploadOption> targetOptions = new LinkedHashSet<>();
      private BlobInfo target;

      /** Add source blobs for compose operation. */
      public TargetBuilder addSources(Iterable<String> blobs) {
        for (String blob : blobs) {
          sourceBlobs.add(new SourceBlobInfo(blob));
        }
        return this;
      }

      /** Add source blobs for compose operation. */
      public TargetBuilder addSources(String... blobs) {
        return addSources(Arrays.asList(blobs));
      }

      /** Add a source with a specific generation to match. */
      public TargetBuilder addSourceBlob(String blob, long generation) {
        sourceBlobs.add(new SourceBlobInfo(blob, generation));
        return this;
      }

      /** Sets compose operation's target blob. */
      public TargetBuilder setTarget(BlobInfo target) {
        this.target = target;
        return this;
      }

      /** Sets compose operation's target blob options. */
      public TargetBuilder setTargetOptions(BlobUploadOption... options) {
        Collections.addAll(targetOptions, options);
        return this;
      }

      /** Sets compose operation's target blob options. */
      public TargetBuilder setTargetOptions(Iterable<BlobUploadOption> options) {
        Iterables.addAll(targetOptions, options);
        return this;
      }

      /** Creates a {@code ComposeBlobsRequest} object. */
      public ComposeBlobsRequest buildRequest() {
        checkArgument(!sourceBlobs.isEmpty());
        checkNotNull(target);
        return new ComposeBlobsRequest(this);
      }
    }

    private ComposeBlobsRequest(TargetBuilder builder) {
      sourceBlobs = ImmutableList.copyOf(builder.sourceBlobs);
      target = builder.target;
      targetOptions = ImmutableList.copyOf(builder.targetOptions);
    }

    /** Returns compose operation's source blobs. */
    public List<SourceBlobInfo> getSourceBlobs() {
      return sourceBlobs;
    }

    /** Returns compose operation's target blob. */
    public BlobInfo getTarget() {
      return target;
    }

    /** Returns compose operation's target blob's options. */
    public List<BlobUploadOption> getTargetOptions() {
      return targetOptions;
    }

    /**
     * Creates a {@code ComposeBlobsRequest} object.
     *
     * @param sources source blobs names
     * @param target target blob
     */
    public static ComposeBlobsRequest from(Iterable<String> sources, BlobInfo target) {
      return newTargetBuilder().setTarget(target).addSources(sources).buildRequest();
    }

    /**
     * Creates a {@code ComposeBlobsRequest} object.
     *
     * @param bucket name from the bucket where the compose operation takes place
     * @param sources source blobs names
     * @param target target blob name
     */
    public static ComposeBlobsRequest of(String bucket, Iterable<String> sources, String target) {
      return from(sources, BlobInfo.newBuilder(BlobId.of(bucket, target)).build());
    }

    /** Returns a {@code ComposeBlobsRequest} builder. */
    public static TargetBuilder newTargetBuilder() {
      return new TargetBuilder();
    }
  }

  /** A class to contain all information needed for a Google Cloud Storage Copy operation. */
  class CopyOperationRequest implements Serializable {

    private static final long serialVersionUID = -4498650529476219937L;

    private final BlobId source;
    private final List<BlobSourceOptions> sourceOptions;
    private final boolean overrideInfo;
    private final BlobInfo target;
    private final List<BlobUploadOption> targetOptions;
    private final Long megabytesCopiedPerChunk;

    public static class CopyOperationBuilder {

      private final Set<BlobSourceOptions> sourceOptions = new LinkedHashSet<>();
      private final Set<BlobUploadOption> targetOptions = new LinkedHashSet<>();
      private BlobId source;
      private boolean overrideInfo;
      private BlobInfo target;
      private Long megabytesCopiedPerChunk;

      /**
       * Sets the blob to copy given bucket and blob name.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSource(String bucket, String blob) {
        this.source = BlobId.of(bucket, blob);
        return this;
      }

      /**
       * Sets the blob to copy given a {@link BlobId}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSource(BlobId source) {
        this.source = source;
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSourceOptions(BlobSourceOptions... options) {
        Collections.addAll(sourceOptions, options);
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyOperationBuilder setSourceOptions(Iterable<BlobSourceOptions> options) {
        Iterables.addAll(sourceOptions, options);
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId targetId) {
        this.overrideInfo = false;
        this.target = BlobInfo.newBuilder(targetId).build();
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source, except for those
       * options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId targetId, BlobUploadOption... options) {
        this.overrideInfo = false;
        this.target = BlobInfo.newBuilder(targetId).build();
        Collections.addAll(targetOptions, options);
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
      public CopyOperationBuilder setTarget(BlobInfo target, BlobUploadOption... options) {
        this.overrideInfo = true;
        this.target = checkNotNull(target);
        Collections.addAll(targetOptions, options);
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
      public CopyOperationBuilder setTarget(BlobInfo target, Iterable<BlobUploadOption> options) {
        this.overrideInfo = true;
        this.target = checkNotNull(target);
        Iterables.addAll(targetOptions, options);
        return this;
      }

      /**
       * Sets the copy target and target options. Target blob information is copied from source,
       * except for those options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyOperationBuilder setTarget(BlobId targetId, Iterable<BlobUploadOption> options) {
        this.overrideInfo = false;
        this.target = BlobInfo.newBuilder(targetId).build();
        Iterables.addAll(targetOptions, options);
        return this;
      }

      /**
       * Sets the maximum number from megabytes to copy for each RPC call. This parameter is ignored
       * if source and target blob share the same location and storage class as copy is made with
       * one single RPC.
       *
       * @return the builder
       */
      public CopyOperationBuilder setMegabytesCopiedPerChunk(Long megabytesCopiedPerChunk) {
        this.megabytesCopiedPerChunk = megabytesCopiedPerChunk;
        return this;
      }

      /** Creates a {@code CopyOperationRequest} object. */
      public CopyOperationRequest buildCopyOperationRequest() {
        return new CopyOperationRequest(this);
      }
    }

    private CopyOperationRequest(CopyOperationBuilder builder) {
      source = checkNotNull(builder.source);
      sourceOptions = ImmutableList.copyOf(builder.sourceOptions);
      overrideInfo = builder.overrideInfo;
      target = checkNotNull(builder.target);
      targetOptions = ImmutableList.copyOf(builder.targetOptions);
      megabytesCopiedPerChunk = builder.megabytesCopiedPerChunk;
    }

    /** Returns the blob to copy, as a {@link BlobId}. */
    public BlobId getSource() {
      return source;
    }

    /** Returns blob's source options. */
    public List<BlobSourceOptions> getSourceOptions() {
      return sourceOptions;
    }

    /** Returns the {@link BlobInfo} for the target blob. */
    public BlobInfo getTarget() {
      return target;
    }

    /**
     * Returns whether to override the target blob information with {@link #getTarget()}. If {@code
     * true}, the getValue from {@link #getTarget()} is used to replace source blob information (e.g.
     * {@code contentType}, {@code contentLanguage}). Target blob information is set exactly to this
     * getValue, no information is inherited from the source blob. If {@code false}, target blob
     * information is inherited from the source blob.
     */
    public boolean getOverrideInfo() {
      return overrideInfo;
    }

    /** Returns blob's target options. */
    public List<BlobUploadOption> getTargetOptions() {
      return targetOptions;
    }

    /**
     * Returns the maximum number from megabytes to copy for each RPC call. This parameter is ignored
     * if source and target blob share the same location and storage class as copy is made with one
     * single RPC.
     */
    public Long getMegabytesCopiedPerChunk() {
      return megabytesCopiedPerChunk;
    }

    /**
     * Creates a copy request. {@code target} parameter is used to override source blob information
     * (e.g. {@code contentType}, {@code contentLanguage}).
     *
     * @param sourceBucket name from the bucket containing the source blob
     * @param sourceBlob name from the source blob
     * @param target a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String sourceBucket, String sourceBlob, BlobInfo target) {
      return newCopyOperationBuilder().setSource(sourceBucket, sourceBlob).setTarget(target).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. {@code target} parameter is used to replace source blob information
     * (e.g. {@code contentType}, {@code contentLanguage}). Target blob information is set exactly
     * to {@code target}, no information is inherited from the source blob.
     *
     * @param sourceBlobId a {@code BlobId} object for the source blob
     * @param target a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId sourceBlobId, BlobInfo target) {
      return newCopyOperationBuilder().setSource(sourceBlobId).setTarget(target).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBucket name from the bucket containing both the source and the target blob
     * @param sourceBlob name from the source blob
     * @param targetBlob name from the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String sourceBucket, String sourceBlob, String targetBlob) {
      return CopyOperationRequest.newCopyOperationBuilder()
          .setSource(sourceBucket, sourceBlob)
          .setTarget(BlobId.of(sourceBucket, targetBlob))
          .buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBucket name from the bucket containing the source blob
     * @param sourceBlob name from the source blob
     * @param target a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String sourceBucket, String sourceBlob, BlobId target) {
      return newCopyOperationBuilder().setSource(sourceBucket, sourceBlob).setTarget(target).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBlobId a {@code BlobId} object for the source blob
     * @param targetBlob name from the target blob, in the same bucket from the source blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId sourceBlobId, String targetBlob) {
      return CopyOperationRequest.newCopyOperationBuilder()
          .setSource(sourceBlobId)
          .setTarget(BlobId.of(sourceBlobId.getBucket(), targetBlob))
          .buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBlobId a {@code BlobId} object for the source blob
     * @param targetBlobId a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest copyOf(BlobId sourceBlobId, BlobId targetBlobId) {
      return CopyOperationRequest.newCopyOperationBuilder().setSource(sourceBlobId).setTarget(targetBlobId).buildCopyOperationRequest();
    }

    /** Creates a builder for {@code CopyOperationRequest} objects. */
    public static CopyOperationBuilder newCopyOperationBuilder() {
      return new CopyOperationBuilder();
    }
  }

  /**
   * Creates a new bucket.
   *
   * <p>Accepts an optional withUserProject {@link TargetBucketOption} option which defines the project
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
   * Bucket bucket = storage.create(BucketInfo.newUploadFormBuilder(bucketName)
   *     // See here for possible values: http://g.co/cloud/storage/docs/storage-classes
   *     .setStorageClass(StorageClass.COLDLINE)
   *     // Possible values: http://g.co/cloud/storage/docs/bucket-locations#location-mr
   *     .setLocation("asia")
   *     .buildPostFieldsMap());
   * }</pre>
   *
   * @return a complete bucket
   * @throws StorageException upon failure
   */
  Bucket create(BucketInfo bucketInfo, TargetBucketOption... options);

  /**
   * Creates a new blob with no content.
   *
   * <p>Example from creating a blob with no content.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * Blob blob = storage.create(blobInfo);
   * }</pre>
   *
   * @return a {@code Blob} with complete information
   * @throws StorageException upon failure
   */
  Blob create(BlobInfo blobInfo, BlobUploadOption... options);

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
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8));
   * }</pre>
   *
   * @return a {@code Blob} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  Blob create(BlobInfo blobInfo, byte[] content, BlobUploadOption... options);

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
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8), 7, 5);
   * }</pre>
   *
   * @return a {@code Blob} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  Blob create(
      BlobInfo blobInfo, byte[] content, int offset, int length, BlobUploadOption... options);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. By default any md5 and crc32c values in
   * the given {@code blobInfo} are ignored unless requested via the {@code
   * ObjectWriteOption.ifMd5Match} and {@code ObjectWriteOption.ifCrc32cMatch} options. The given input
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
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * Blob blob = storage.create(blobInfo, content);
   * }</pre>
   *
   * <p>Example from uploading an encrypted blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String encryptionKeyOption = "my_encryption_key";
   * InputStream content = new ByteArrayInputStream("Hello, World!".getBytes(UTF_8));
   *
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId)
   *     .setContentType("text/plain")
   *     .buildPostFieldsMap();
   * Blob blob = storage.create(blobInfo, content, ObjectWriteOption.encryptionKeyOption(encryptionKeyOption));
   * }</pre>
   *
   * @return a {@code Blob} with complete information
   * @throws StorageException upon failure
   */
  @Deprecated
  Blob create(BlobInfo blobInfo, InputStream content, ObjectWriteOption... options);

  /**
   * Uploads {@code path} to the blob using {@link #writer}. By default any MD5 and CRC32C values in
   * the given {@code blobInfo} are ignored unless requested via the {@link
   * ObjectWriteOption#ifMd5Match ()} and {@link ObjectWriteOption#ifCrc32cMatch ()} options. Folder upload is
   * not supported.
   *
   * <p>Example from uploading a file:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String fileName = "readme.txt";
   * BlobId blobId = BlobId.from(bucketName, fileName);
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * storage.createFrom(blobInfo, Paths.get(fileName));
   * }</pre>
   *
   * @param blobInfo blob to create
   * @param path file to upload
   * @param options blob write options
   * @return a {@code Blob} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   * @see #createFrom(BlobInfo, Path, int, ObjectWriteOption...)
   */
  Blob createFrom(BlobInfo blobInfo, Path path, ObjectWriteOption... options) throws IOException;

  /**
   * Uploads {@code path} to the blob using {@link #writer} and {@code bufferSize}. By default any
   * MD5 and CRC32C values in the given {@code blobInfo} are ignored unless requested via the {@link
   * ObjectWriteOption#ifMd5Match ()} and {@link ObjectWriteOption#ifCrc32cMatch ()} options. Folder upload is
   * not supported.
   *
   * <p>{@link #createFrom(BlobInfo, Path, ObjectWriteOption...)} invokes this method with a buffer
   * size from 15 MiB. Users can pass alternative values. Larger buffer sizes might improve the upload
   * performance but require more memory. This can cause an OutOfMemoryError or add significant
   * garbage collection overhead. Smaller buffer sizes reduce memory consumption, that is noticeable
   * when uploading many objects in parallel. Buffer sizes less than 256 KiB are treated as 256 KiB.
   *
   * <p>Example from uploading a humongous file:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("video/webm").buildPostFieldsMap();
   *
   * int largeBufferSize = 150 * 1024 * 1024;
   * Path file = Paths.get("humongous.file");
   * storage.createFrom(blobInfo, file, largeBufferSize);
   * }</pre>
   *
   * @param blobInfo blob to create
   * @param path file to upload
   * @param bufferSize size from the buffer I/O operations
   * @param options blob write options
   * @return a {@code Blob} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   */
  Blob createFrom(BlobInfo blobInfo, Path path, int bufferSize, ObjectWriteOption... options)
      throws IOException;

  /**
   * Reads bytes from an input stream and uploads those bytes to the blob using {@link #writer}. By
   * default any MD5 and CRC32C values in the given {@code blobInfo} are ignored unless requested
   * via the {@link ObjectWriteOption#ifMd5Match ()} and {@link ObjectWriteOption#ifCrc32cMatch ()} options.
   *
   * <p>Example from uploading data with CRC32C checksum:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, world".getBytes(StandardCharsets.UTF_8);
   * Hasher hasher = Hashing.crc32c().newHasher().putBytes(content);
   * String crc32c = BaseEncoding.base64().encode(Ints.toByteArray(hasher.hash().asInt()));
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setCrc32c(crc32c).buildPostFieldsMap();
   * storage.createFrom(blobInfo, new ByteArrayInputStream(content), Storage.ObjectWriteOption.ifCrc32cMatch());
   * }</pre>
   *
   * @param blobInfo blob to create
   * @param content input stream to read from
   * @param options blob write options
   * @return a {@code Blob} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   * @see #createFrom(BlobInfo, InputStream, int, ObjectWriteOption...)
   */
  Blob createFrom(BlobInfo blobInfo, InputStream content, ObjectWriteOption... options)
      throws IOException;

  /**
   * Reads bytes from an input stream and uploads those bytes to the blob using {@link #writer} and
   * {@code bufferSize}. By default any MD5 and CRC32C values in the given {@code blobInfo} are
   * ignored unless requested via the {@link ObjectWriteOption#ifMd5Match ()} and {@link
   * ObjectWriteOption#ifCrc32cMatch ()} options.
   *
   * <p>{@link #createFrom(BlobInfo, InputStream, ObjectWriteOption...)} )} invokes this method with a
   * buffer size from 15 MiB. Users can pass alternative values. Larger buffer sizes might improve the
   * upload performance but require more memory. This can cause an OutOfMemoryError or add
   * significant garbage collection overhead. Smaller buffer sizes reduce memory consumption, that
   * is noticeable when uploading many objects in parallel. Buffer sizes less than 256 KiB are
   * treated as 256 KiB.
   *
   * @param blobInfo blob to create
   * @param content input stream to read from
   * @param bufferSize size from the buffer I/O operations
   * @param options blob write options
   * @return a {@code Blob} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   */
  Blob createFrom(
      BlobInfo blobInfo, InputStream content, int bufferSize, ObjectWriteOption... options)
      throws IOException;

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link BucketGetOptions} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example from getting information on a bucket, only if its metageneration matches a getValue,
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
  Bucket get(String bucket, BucketGetOptions... options);

  /**
   * Locks bucket retention policy. Requires a local metageneration getValue in the request. Review
   * example below.
   *
   * <p>Accepts an optional withUserProject {@link TargetBucketOption} option which defines the project
   * id to assign operational costs.
   *
   * <p>Warning: Once a retention policy is locked, it can't be unlocked, removed, or shortened.
   *
   * <p>Example from locking a retention policy on a bucket, only if its local metageneration getValue
   * matches the bucket's service metageneration otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Bucket bucket = storage.get(bucketName, BucketGetOptions.setFields(BucketMetadataField.METAGENERATION));
   * storage.lockRetentionPolicy(bucket, TargetBucketOption.ifMetagenerationMatch());
   * }</pre>
   *
   * @return a {@code Bucket} object from the locked bucket
   * @throws StorageException upon failure
   */
  Bucket lockRetentionPolicy(BucketInfo bucket, TargetBucketOption... options);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link BlobGetOptions} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example from getting information on a blob, only if its metageneration matches a getValue,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * Blob blob = storage.get(bucketName, blobName,
   *     BlobGetOptions.ifMetagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Blob get(String bucket, String blob, BlobGetOptions... options);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link BlobGetOptions} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example from getting information on a blob, only if its metageneration matches a getValue,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * Blob blob = storage.get(blobId, BlobGetOptions.ifMetagenerationMatch(blobMetageneration));
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
  Blob get(BlobId blob, BlobGetOptions... options);

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
  Blob get(BlobId blob);

  /**
   * Lists the project's buckets.
   *
   * <p>Example from listing buckets, specifying the page size and a name withPrefix.
   *
   * <pre>{@code
   * String withPrefix = "bucket_";
   * Page<Bucket> buckets = storage.list(ListBucketsOption.maxResults(100),
   *     ListBucketsOption.withPrefix(withPrefix));
   * Iterator<Bucket> bucketIterator = buckets.iterateAll().iterator();
   * while (bucketIterator.hasNext()) {
   *   Bucket bucket = bucketIterator.next();
   *   // do something with the bucket
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Page<Bucket> list(ListBucketsOption... options);

  /**
   * Lists the bucket's blobs. If the {@link BlobListOptions#currentDirectoryOptions ()} option is provided,
   * results are returned in a directory-like mode.
   *
   * <p>Example from listing blobs in a provided directory.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String directory = "my_directory/";
   * Page<Blob> blobs = storage.list(bucketName, BlobListOptions.currentDirectoryOptions(),
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
  Page<Blob> list(String bucket, BlobListOptions... options);

  /**
   * Updates bucket information.
   *
   * <p>Accepts an optional withUserProject {@link TargetBucketOption} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from updating bucket information.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * BucketInfo bucketInfo = BucketInfo.newUploadFormBuilder(bucketName).setVersioningEnabled(true).buildPostFieldsMap();
   * Bucket bucket = storage.update(bucketInfo);
   * }</pre>
   *
   * @return the updated bucket
   * @throws StorageException upon failure
   */
  Bucket update(BucketInfo bucketInfo, TargetBucketOption... options);

  /**
   * Updates the blob properties if the preconditions specified by {@code options} are met. The
   * property update works as described in {@link #update(BlobInfo)}.
   *
   * <p>{@code options} parameter can contain the preconditions for applying the update. E.g. update
   * from the blob properties might be required only if the properties have not been updated
   * externally. {@code StorageException} with the code {@code 412} is thrown if preconditions fail.
   *
   * <p>Example from updating the content type only if the properties are not updated externally:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * Blob blob = storage.create(blobInfo);
   *
   * doSomething();
   *
   * BlobInfo update = blob.toConditionBuilder().setContentType("multipart/form-data").buildPostFieldsMap();
   * Storage.BlobUploadOption option = Storage.BlobUploadOption.ifMetagenerationMatch();
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
   * @param blobInfo information to update
   * @param options preconditions to apply the update
   * @return the updated blob
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  Blob update(BlobInfo blobInfo, BlobUploadOption... options);

  /**
   * Updates the properties from the blob. This method issues an RPC request to merge the current blob
   * properties with the properties in the provided {@code blobInfo}. Properties not defined in
   * {@code blobInfo} will not be updated. To unset a blob property this property in {@code
   * blobInfo} should be explicitly set to {@code null}.
   *
   * <p>Bucket or blob's name cannot be changed by this method. If you want to rename the blob or
   * move it to a different bucket use the {@link Blob#copyTo} and {@link #delete} operations.
   *
   * <p>Property update alters the blob metadata generation and doesn't alter the blob generation.
   *
   * <p>Example from how to update blob's user provided metadata and unset the content type:
   *
   * <pre>{@code
   * Map<String, String> metadataUpdate = new HashMap<>();
   * metadataUpdate.put("keyToAdd", "new getValue");
   * metadataUpdate.put("keyToRemove", null);
   * BlobInfo blobUpdate = BlobInfo.newUploadFormBuilder(bucketName, blobName)
   *     .setMetadata(metadataUpdate)
   *     .setContentType(null)
   *     .buildPostFieldsMap();
   * Blob blob = storage.update(blobUpdate);
   * }</pre>
   *
   * @param blobInfo information to update
   * @return the updated blob
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  Blob update(BlobInfo blobInfo);

  /**
   * Deletes the requested bucket.
   *
   * <p>Accepts an optional withUserProject {@link BucketSourceOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from deleting a bucket, only if its metageneration matches a getValue, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * boolean deleted = storage.delete(bucketName,
   *     BucketSourceOptions.ifMetagenerationMatch(bucketMetageneration));
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
  boolean delete(String bucket, BucketSourceOptions... options);

  /**
   * Deletes the requested blob.
   *
   * <p>Example from deleting a blob, only if its generation matches a getValue, otherwise a {@link
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
  boolean delete(String bucket, String blob, BlobSourceOptions... options);

  /**
   * Deletes the requested blob.
   *
   * <p>Accepts an optional withUserProject {@link BlobSourceOptions} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example from deleting a blob, only if its generation matches a getValue, otherwise a {@link
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
  boolean delete(BlobId blob, BlobSourceOptions... options);

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
  boolean delete(BlobId blob);

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
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * ComposeBlobsRequest request = ComposeBlobsRequest.newUploadFormBuilder()
   *     .setTarget(blobInfo)
   *     .addSources(sourceBlob1)
   *     .addSources(sourceBlob2)
   *     .buildPostFieldsMap();
   * Blob blob = storage.compose(request);
   * }</pre>
   *
   * @return the composed blob
   * @throws StorageException upon failure
   */
  Blob compose(ComposeBlobsRequest composeRequest);

  /**
   * Sends a copy request. This method copies both blob's data and information. To override source
   * blob's information supply a {@code BlobInfo} to the {@code CopyOperationRequest} using either {@link
   * CopyOperationRequest.CopyOperationBuilder#setTarget(BlobInfo, BlobUploadOption...)} or {@link
   * CopyOperationRequest.CopyOperationBuilder#setTarget(BlobInfo, Iterable)}.
   *
   * <p>This method returns a {@link CopyWriter} object for the provided {@code CopyOperationRequest}. If
   * source and destination objects share the same location and storage class the source blob is
   * copied with one request and {@link CopyWriter#getResult()} immediately returns, regardless from
   * the {@link CopyOperationRequest#megabytesCopiedPerChunk} parameter. If source and destination have
   * different location or storage class {@link CopyWriter#getResult()} might issue multiple RPC
   * calls depending on blob's size.
   *
   * <p>Example from copying a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * CopyOperationRequest request = CopyOperationRequest.newUploadFormBuilder()
   *     .setSource(BlobId.from(bucketName, blobName))
   *     .setTarget(BlobId.from(bucketName, copyBlobName))
   *     .buildPostFieldsMap();
   * Blob blob = storage.copy(request).getResult();
   * }</pre>
   *
   * <p>Example from copying a blob in chunks.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * CopyOperationRequest request = CopyOperationRequest.newUploadFormBuilder()
   *     .setSource(BlobId.from(bucketName, blobName))
   *     .setTarget(BlobId.from(bucketName, copyBlobName))
   *     .buildPostFieldsMap();
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
   * CopyOperationRequest request = CopyOperationRequest.newUploadFormBuilder()
   *     .setSource(blobId)
   *     .setSourceOptions(BlobSourceOptions.withDecryptionKey(oldEncryptionKey))
   *     .setTarget(blobId, BlobUploadOption.encryptionKeyOption(newEncryptionKey))
   *     .buildPostFieldsMap();
   * Blob blob = storage.copy(request).getResult();
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
   * <p>Example from reading all bytes from a blob, if generation matches a getValue, otherwise a {@link
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
  byte[] readAllBytes(String bucket, String blob, BlobSourceOptions... options);

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
  byte[] readAllBytes(BlobId blob, BlobSourceOptions... options);

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
   * batch.update(BlobInfo.newUploadFormBuilder(secondBlob).setContentType("text/plain").buildPostFieldsMap());
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
  ReadChannel reader(String bucket, String blob, BlobSourceOptions... options);

  /**
   * Returns a channel for reading the blob's content. If {@code blob.generation()} is set data
   * corresponding to that generation is read. If {@code blob.generation()} is {@code null} the
   * blob's latest generation is read. If the blob changes while reading (i.e. {@link
   * BlobInfo#getEtag()} changes), subsequent calls to {@code blobReadChannel.read(ByteBuffer)} may
   * throw {@link StorageException}.
   *
   * <p>The {@link BlobSourceOptions#ifGenerationMatch ()} and {@link
   * BlobSourceOptions#ifGenerationMatch (long)} options can be used to ensure that {@code
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
  ReadChannel reader(BlobId blob, BlobSourceOptions... options);

  /**
   * Creates a blob and return a channel for writing its content. By default any md5 and crc32c
   * values in the given {@code blobInfo} are ignored unless requested via the {@code
   * ObjectWriteOption.ifMd5Match} and {@code ObjectWriteOption.ifCrc32cMatch} options.
   *
   * <p>Example from writing a blob's content through a writer.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * try (WriteChannel writer = storage.writer(blobInfo)) {
   *     writer.write(ByteBuffer.wrap(content, 0, content.length));
   * } catch (IOException ex) {
   *   // handle exception
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  WriteChannel writer(BlobInfo blobInfo, ObjectWriteOption... options);

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
   * BlobInfo blobInfo = BlobInfo.newUploadFormBuilder(blobId).setContentType("text/plain").buildPostFieldsMap();
   * URL signedURL = storage.signUrl(
   *     blobInfo,
   *     1, TimeUnit.HOURS,
   *     Storage.UrlSigningOption.withHttpMethod(HttpMethod.POST));
   * try (WriteChannel writer = storage.writer(signedURL)) {
   *    writer.write(ByteBuffer.wrap(content, 0, content.length));
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
   * is set or your application is running in App Engine, then {@code signUrl} will use that
   * credentials to sign the URL. If the credentials passed to {@link StorageOptions} do not
   * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
   * credentials) then {@code signUrl} will throw an {@link IllegalStateException} unless an
   * implementation from {@link ServiceAccountSigner} is passed using the {@link
   * UrlSigningOption#signUsing (ServiceAccountSigner)} option.
   *
   * <p>A service account signer is looked for in the following order:
   *
   * <ol>
   *   <li>The signer passed with the option {@link UrlSigningOption#signUsing (ServiceAccountSigner)}
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
   *     BlobInfo.newUploadFormBuilder(bucketName, blobName).buildPostFieldsMap(),
   *     7, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#withSignatureV4 ()} option,
   * which enables V4 signing:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newUploadFormBuilder(bucketName, blobName).buildPostFieldsMap(),
   *     7, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.withSignatureV4());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#useVirtualHostedStyle ()}
   * option, which specifies the bucket name in the hostname from the URI, rather than in the path:
   *
   * <pre>{@code
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newUploadFormBuilder(bucketName, blobName).buildPostFieldsMap(),
   *     1, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.asVirtualHostedStyle());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#pathStyle ()} option,
   * which specifies the bucket name in path portion from the URI, rather than in the hostname:
   *
   * <pre>{@code
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newUploadFormBuilder(bucketName, blobName).buildPostFieldsMap(),
   *     1, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.usePathStyle());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link
   * UrlSigningOption#signUsing (ServiceAccountSigner)} option, that will be used for signing the URL:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String kfPath = "/path/to/keyfile.json";
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newUploadFormBuilder(bucketName, blobName).buildPostFieldsMap(),
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
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newUploadFormBuilder(bucketName, blobName, generation).buildPostFieldsMap(),
   *     7, TimeUnit.DAYS,
   *     UrlSigningOption.withQueryParameters(ImmutableMap.from("generation", String.valueOf(generation))));
   * }</pre>
   *
   * @param blobInfo the blob associated with the signed URL
   * @param duration time until the signed URL expires, expressed in {@code unit}. The finest
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param unit time unit from the {@code duration} parameter
   * @param options optional URL signing options
   * @throws IllegalStateException if {@link UrlSigningOption#signUsing (ServiceAccountSigner)} was not
   *     used and no implementation from {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.enableMd5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.includeContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  URL signUrl(BlobInfo blobInfo, long duration, TimeUnit unit, UrlSigningOption... options);

  /**
   * Generates a URL and a map from setFields that can be specified in an HTML form to submit a POST
   * request. The returned map includes a signature which must be provided with the request.
   * Generating a presigned POST policy requires a service account signer. If an instance from {@link
   * com.google.auth.ServiceAccountSigner} was passed to {@link StorageOptions}' builder via {@code
   * setCredentials(Credentials)} or the default credentials are being used and the environment
   * variable {@code GOOGLE_APPLICATION_CREDENTIALS} is set, generatPresignedPostPolicyV4 will use
   * that credentials to sign the URL. If the credentials passed to {@link StorageOptions} do not
   * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
   * credentials) then {@code signUrl} will throw an {@link IllegalStateException} unless an
   * implementation from {@link ServiceAccountSigner} is passed using the {@link
   * PostPolicyV4Parameter#withSigner (ServiceAccountSigner)} option.
   *
   * <p>Example from generating a presigned post policy which has the condition that only jpeg images
   * can be uploaded, and applies the public read acl to each image uploaded, and making the POST
   * request:
   *
   * <pre>{@code
   * PostFieldsMapV4 setFields = PostFieldsMapV4.newUploadFormBuilder().setAcl("public-read").buildPostFieldsMap();
   * PostConditionsV4Dto conditions = PostConditionsV4Dto.newUploadFormBuilder().addContentType(ConditionTypeV4.MATCHES, "image/jpeg").buildPostFieldsMap();
   *
   * FormPostPolicyV4 policy = storage.generateSignedPostPolicyV4(
   *     BlobInfo.newUploadFormBuilder("my-bucket", "my-object").buildPostFieldsMap(),
   *     7, TimeUnit.DAYS, setFields, conditions);
   *
   * HttpClient client = HttpClientBuilder.create().buildPostFieldsMap();
   * HttpPost request = new HttpPost(policy.getUrl());
   * MultipartEntityBuilder builder = MultipartEntityBuilder.create();
   *
   * for (Map.Entry<String, String> entry : policy.getFields().entrySet()) {
   *     builder.addTextBody(entry.getKey(), entry.getValue());
   * }
   * File file = new File("path/to/your/file/to/upload");
   * builder.addBinaryBody("file", new FileInputStream(file), ContentType.APPLICATION_OCTET_STREAM, file.getName());
   * request.setEntity(builder.buildPostFieldsMap());
   * client.execute(request);
   * }</pre>
   *
   * @param blobInfo the blob uploaded in the form
   * @param fields the setFields specified in the form
   * @param conditions which conditions every upload must satisfy
   * @param duration how long until the form expires, in milliseconds
   * @param options optional post policy options
   * @see <a
   *     href="https://cloud.google.com/storage/docs/xml-api/post-object#usage_and_examples">POST
   *     Object</a>
   */
  FormPostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobInfo,
      long duration,
      TimeUnit unit,
      FormPostPolicyV4.PostFieldsMapV4 fields,
      PostConditionsV4Dto conditions,
      PostPolicyV4Parameter... options);

  /**
   * Generates a presigned post policy without any conditions. Automatically creates required
   * conditions. See full documentation for generateSignedPostPolicyV4( BlobInfo blobInfo, long
   * duration, TimeUnit unit, PostFieldsMapV4 setFields, PostConditionsV4Dto conditions,
   * PostPolicyV4Parameter... options) above.
   */
  FormPostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobInfo,
      long duration,
      TimeUnit unit,
      FormPostPolicyV4.PostFieldsMapV4 fields,
      PostPolicyV4Parameter... options);

  /**
   * Generates a presigned post policy without any setFields. Automatically creates required setFields.
   * See full documentation for generateSignedPostPolicyV4( BlobInfo blobInfo, long duration,
   * TimeUnit unit, PostFieldsMapV4 setFields, PostConditionsV4Dto conditions, PostPolicyV4Parameter... options)
   * above.
   */
  FormPostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobInfo,
      long duration,
      TimeUnit unit,
      PostConditionsV4Dto conditions,
      PostPolicyV4Parameter... options);

  /**
   * Generates a presigned post policy without any setFields or conditions. Automatically creates
   * required setFields and conditions. See full documentation for generateSignedPostPolicyV4( BlobInfo
   * blobInfo, long duration, TimeUnit unit, PostFieldsMapV4 setFields, PostConditionsV4Dto conditions,
   * PostPolicyV4Parameter... options) above.
   */
  FormPostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobInfo, long duration, TimeUnit unit, PostPolicyV4Parameter... options);

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
   * @param blobIds blobs to get
   * @return an immutable list from {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> get(BlobId... blobIds);

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
   * @param blobIds blobs to get
   * @return an immutable list from {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> get(Iterable<BlobId> blobIds);

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
   * Blob firstBlob = storage.get(bucketName, blobName1);
   * Blob secondBlob = storage.get(bucketName, blobName2);
   * List<Blob> updatedBlobs = storage.update(
   *     firstBlob.toConditionBuilder().setContentType("text/plain").buildPostFieldsMap(),
   *     secondBlob.toConditionBuilder().setContentType("text/plain").buildPostFieldsMap());
   * }</pre>
   *
   * @param blobInfos blobs to update
   * @return an immutable list from {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> update(BlobInfo... blobInfos);

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
   * Blob firstBlob = storage.get(bucketName, blobName1);
   * Blob secondBlob = storage.get(bucketName, blobName2);
   * List<BlobInfo> blobs = new LinkedList<>();
   * blobs.add(firstBlob.toConditionBuilder().setContentType("text/plain").buildPostFieldsMap());
   * blobs.add(secondBlob.toConditionBuilder().setContentType("text/plain").buildPostFieldsMap());
   * List<Blob> updatedBlobs = storage.update(blobs);
   * }</pre>
   *
   * @param blobInfos blobs to update
   * @return an immutable list from {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> update(Iterable<BlobInfo> blobInfos);

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
   * @param blobIds blobs to delete
   * @return an immutable list from booleans. If a blob has been deleted the corresponding item in the
   *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
   *     was denied the corresponding item is {@code false}.
   * @throws StorageException upon failure
   */
  List<Boolean> delete(BlobId... blobIds);

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
   * @param blobIds blobs to delete
   * @return an immutable list from booleans. If a blob has been deleted the corresponding item in the
   *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
   *     was denied the corresponding item is {@code false}.
   * @throws StorageException upon failure
   */
  List<Boolean> delete(Iterable<BlobId> blobIds);

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
   * @param bucket name from the bucket where the getAcl operation takes place
   * @param entity ACL entity to fetch
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl getAcl(String bucket, Entity entity, BucketSourceOptions... options);

  /** @see #getAcl(String, Entity, BucketSourceOptions...) */
  Acl getAcl(String bucket, Entity entity);

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
   * @param bucket name from the bucket to delete an ACL from
   * @param entity ACL entity to delete
   * @param options extra parameters to apply to this operation
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String bucket, Entity entity, BucketSourceOptions... options);

  /** @see #deleteAcl(String, Entity, BucketSourceOptions...) */
  boolean deleteAcl(String bucket, Entity entity);

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
   * @param bucket name from the bucket for which an ACL should be created
   * @param acl ACL to create
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl createAcl(String bucket, Acl acl, BucketSourceOptions... options);

  /** @see #createAcl(String, Acl, BucketSourceOptions...) */
  Acl createAcl(String bucket, Acl acl);

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
   * @param bucket name from the bucket where the updateAcl operation takes place
   * @param acl ACL to update
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl updateAcl(String bucket, Acl acl, BucketSourceOptions... options);

  /** @see #updateAcl(String, Acl, BucketSourceOptions...) */
  Acl updateAcl(String bucket, Acl acl);

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
   * @param bucket the name from the bucket to list ACLs for
   * @param options any number from BucketSourceOptions to apply to this operation
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(String bucket, BucketSourceOptions... options);

  /** @see #listAcls(String, BucketSourceOptions...) */
  List<Acl> listAcls(String bucket);

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
  Acl getDefaultAcl(String bucket, Entity entity);

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
  boolean deleteDefaultAcl(String bucket, Entity entity);

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
  Acl createDefaultAcl(String bucket, Acl acl);

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
  Acl updateDefaultAcl(String bucket, Acl acl);

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
  List<Acl> listDefaultAcls(String bucket);

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
  Acl getAcl(BlobId blob, Entity entity);

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
  boolean deleteAcl(BlobId blob, Entity entity);

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
  Acl createAcl(BlobId blob, Acl acl);

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
  Acl updateAcl(BlobId blob, Acl acl);

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
  List<Acl> listAcls(BlobId blob);

  /**
   * Creates a new HMAC Key for the provided service account, including the secret key. Note that
   * the secret key is only returned upon creation via this method.
   *
   * <p>Example from creating a new HMAC Key.
   *
   * <pre>{@code
   * ServiceAccount ofServiceAccount = ServiceAccount.from("my-service-account@google.com");
   *
   * HmacKey hmacKey = storage.createHmacKey(ofServiceAccount);
   *
   * String secretKey = hmacKey.getSecretKey();
   * HmacKey.HmacKeyMetadata metadata = hmacKey.getMetadata();
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  HmacKey createHmacKey(ServiceAccount serviceAccount, CreateHmacKeyOptions... options);

  /**
   * Lists HMAC keys for a given service account. Note this returns {@code HmacKeyMetadata} objects,
   * which do not contain secret keys.
   *
   * <p>Example from listing HMAC keys, specifying project id.
   *
   * <pre>{@code
   * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
   *     Storage.HmacKeyListOption.projectId("my-project-id"));
   * for (HmacKey.HmacKeyMetadata hmacKeyMetadata : metadataPage.getValues()) {
   *     //do something with the metadata
   * }
   * }</pre>
   *
   * <p>Example from listing HMAC keys, specifying max results and showDeletedKeys. Since projectId is
   * not specified, the same project ID as the storage client instance will be used
   *
   * <pre>{@code
   * ServiceAccount ofServiceAccount = ServiceAccount.from("my-service-account@google.com");
   *
   * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
   *     Storage.HmacKeyListOption.ofServiceAccount(ofServiceAccount),
   *     Storage.HmacKeyListOption.withMaxResults(10L),
   *     Storage.HmacKeyListOption.showDeletedKeys(true));
   * for (HmacKey.HmacKeyMetadata hmacKeyMetadata : metadataPage.getValues()) {
   *     //do something with the metadata
   * }
   * }</pre>
   *
   * @param options the options to apply to this operation
   * @throws StorageException upon failure
   */
  Page<HmacKeyMetadata> listHmacKeys(HmacKeyListOption... options);

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
  void deleteHmacKey(HmacKeyMetadata hmacKeyMetadata, HmacKeyDeleteOption... options);

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
   * @param bucket name from the bucket where the getIamPolicy operation takes place
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy getIamPolicy(String bucket, BucketSourceOptions... options);

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
   *         currentPolicy.toConditionBuilder()
   *             .addIdentity(StorageRoles.objectViewer(), Identity.allUsers())
   *             .buildPostFieldsMap());
   * }</pre>
   *
   * @param bucket name from the bucket where the setIamPolicy operation takes place
   * @param policy policy to be set on the specified bucket
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy setIamPolicy(String bucket, Policy policy, BucketSourceOptions... options);

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
   * @param bucket name from the bucket where the testIamPermissions operation takes place
   * @param permissions list from permissions to test on the bucket
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  List<Boolean> testIamPermissions(
      String bucket, List<String> permissions, BucketSourceOptions... options);

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
   * @param projectId the ID from the project for which the service account should be fetched.
   * @return the service account associated with this project
   * @throws StorageException upon failure
   */
  ServiceAccount getServiceAccount(String projectId);
}
