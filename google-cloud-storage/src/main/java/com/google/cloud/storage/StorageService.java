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
 * An interface for Google Cloud StorageService.
 *
 * @see <a href="https://cloud.google.com/storage/docs">Google Cloud StorageService</a>
 */
public interface StorageService extends Service<StorageOptions> {

  enum PredefinedAccessControlList {
    AUTHENTICATED_READ("authenticatedRead"),
    ALL_AUTHENTICATED_USERS("allAuthenticatedUsers"),
    PRIVATE("private"),
    PROJECT_PRIVATE("projectPrivate"),
    PUBLIC_READ("publicRead"),
    PUBLIC_READ_WRITE("publicReadWrite"),
    BUCKET_OWNER_READ("bucketOwnerRead"),
    BUCKET_OWNER_FULL_CONTROL("bucketOwnerFullControl");

    private final String entry;

    PredefinedAccessControlList(String entry) {
      this.entry = entry;
    }

    String getEntry() {
      return entry;
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

    static final List<? extends FieldSelector> REQUIRED_FIELDS = ImmutableList.of(NAME);

    private final String selector;

    BucketAttribute(String selector) {
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
    KMS_KEY_NAME("kmsKeyName"),
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

  /** Class for specifying bucket target options. */
  class BucketTargetOptions extends Option {

    private static final long serialVersionUID = -5880204616982900975L;

    private BucketTargetOptions(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    private BucketTargetOptions(StorageRpc.Option rpcOption) {
      this(rpcOption, null);
    }

    /** Returns an option for specifying bucket's predefined ACL configuration. */
    public static BucketTargetOptions predefinedAcl(PredefinedAccessControlList acl) {
      return new BucketTargetOptions(StorageRpc.Option.PREDEFINED_ACL, acl.getEntry());
    }

    /** Returns an option for specifying bucket's default ACL configuration for blobs. */
    public static BucketTargetOptions predefinedDefaultObjectAcl(PredefinedAccessControlList acl) {
      return new BucketTargetOptions(
          StorageRpc.Option.PREDEFINED_DEFAULT_OBJECT_ACL, acl.getEntry());
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BucketTargetOptions metagenerationMatch() {
      return new BucketTargetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static BucketTargetOptions metagenerationNotMatch() {
      return new BucketTargetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     */
    public static BucketTargetOptions userProject(String userProject) {
      return new BucketTargetOptions(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to define the projection in the API request. In some cases this option may
     * be needed to be set to `noAcl` to omit ACL data from the response. The default value is
     * `full`
     *
     * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/buckets/patch">Buckets:
     *     patch</a>
     */
    public static BucketTargetOptions projection(String projection) {
      return new BucketTargetOptions(StorageRpc.Option.PROJECTION, projection);
    }
  }

  /** Class for specifying bucket source options. */
  class BucketSourceOptions extends Option {

    private static final long serialVersionUID = 5185657617120212117L;

    private BucketSourceOptions(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided value.
     */
    public static BucketSourceOptions metagenerationMatch(long metageneration) {
      return new BucketSourceOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metageneration);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided value.
     */
    public static BucketSourceOptions metagenerationNotMatch(long metageneration) {
      return new BucketSourceOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metageneration);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketSourceOptions userProject(String userProject) {
      return new BucketSourceOptions(StorageRpc.Option.USER_PROJECT, userProject);
    }
  }

  /** Class for specifying listHmacKeys options */
  class ListHmacKeysOptions extends Option {
    private ListHmacKeysOptions(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for the Service Account whose keys to list. If this option is not used,
     * keys for all accounts will be listed.
     */
    public static ListHmacKeysOptions serviceAccount(ServiceAccount serviceAccount) {
      return new ListHmacKeysOptions(
          StorageRpc.Option.SERVICE_ACCOUNT_EMAIL, serviceAccount.getEmail());
    }

    /** Returns an option for the maximum amount of HMAC keys returned per page. */
    public static ListHmacKeysOptions maxResults(long pageSize) {
      return new ListHmacKeysOptions(StorageRpc.Option.MAX_RESULTS, pageSize);
    }

    /** Returns an option to specify the page token from which to start listing HMAC keys. */
    public static ListHmacKeysOptions pageToken(String pageToken) {
      return new ListHmacKeysOptions(StorageRpc.Option.PAGE_TOKEN, pageToken);
    }

    /**
     * Returns an option to specify whether to show deleted keys in the result. This option is false
     * by default.
     */
    public static ListHmacKeysOptions showDeletedKeys(boolean showDeletedKeys) {
      return new ListHmacKeysOptions(StorageRpc.Option.SHOW_DELETED_KEYS, showDeletedKeys);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static ListHmacKeysOptions userProject(String userProject) {
      return new ListHmacKeysOptions(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static ListHmacKeysOptions projectId(String projectId) {
      return new ListHmacKeysOptions(StorageRpc.Option.PROJECT_ID, projectId);
    }
  }

  /** Class for specifying createHmacKey options */
  class HmacKeyCreateOption extends Option {
    private HmacKeyCreateOption(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyCreateOption userProject(String userProject) {
      return new HmacKeyCreateOption(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static HmacKeyCreateOption projectId(String projectId) {
      return new HmacKeyCreateOption(StorageRpc.Option.PROJECT_ID, projectId);
    }
  }

  /** Class for specifying getHmacKey options */
  class RetrieveHmacKeyOption extends Option {
    private RetrieveHmacKeyOption(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static RetrieveHmacKeyOption userProject(String userProject) {
      return new RetrieveHmacKeyOption(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static RetrieveHmacKeyOption projectId(String projectId) {
      return new RetrieveHmacKeyOption(StorageRpc.Option.PROJECT_ID, projectId);
    }
  }

  /** Class for specifying deleteHmacKey options */
  class DeleteHmacKeyRequestOption extends Option {
    private DeleteHmacKeyRequestOption(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static DeleteHmacKeyRequestOption userProject(String userProject) {
      return new DeleteHmacKeyRequestOption(StorageRpc.Option.USER_PROJECT, userProject);
    }
  }

  /** Class for specifying updateHmacKey options */
  class HmacKeyUpdateOption extends Option {
    private HmacKeyUpdateOption(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyUpdateOption userProject(String userProject) {
      return new HmacKeyUpdateOption(StorageRpc.Option.USER_PROJECT, userProject);
    }
  }

  /** Class for specifying bucket get options. */
  class GetBucketOption extends Option {

    private static final long serialVersionUID = 1901844869484087395L;

    private GetBucketOption(StorageRpc.Option rpcOption, long metageneration) {
      super(rpcOption, metageneration);
    }

    private GetBucketOption(StorageRpc.Option rpcOption, String value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided value.
     */
    public static GetBucketOption metagenerationMatch(long metageneration) {
      return new GetBucketOption(StorageRpc.Option.IF_METAGENERATION_MATCH, metageneration);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided value.
     */
    public static GetBucketOption metagenerationNotMatch(long metageneration) {
      return new GetBucketOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metageneration);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static GetBucketOption userProject(String userProject) {
      return new GetBucketOption(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the bucket's fields to be returned by the RPC call. If this
     * option is not provided all bucket's fields are returned. {@code GetBucketOption.fields}) can
     * be used to specify only the fields of interest. Bucket name is always returned, even if not
     * specified.
     */
    public static GetBucketOption fields(BucketAttribute... fields) {
      return new GetBucketOption(
          StorageRpc.Option.FIELDS, Helper.selector(BucketAttribute.REQUIRED_FIELDS, fields));
    }
  }

  /** Class for specifying blob target options. */
  class BlobUploadOption extends Option {

    private static final long serialVersionUID = 214616862061934846L;

    private BlobUploadOption(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    private BlobUploadOption(StorageRpc.Option rpcOption) {
      this(rpcOption, null);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobUploadOption predefinedAcl(PredefinedAccessControlList acl) {
      return new BlobUploadOption(StorageRpc.Option.PREDEFINED_ACL, acl.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobUploadOption doesNotExist() {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static BlobUploadOption generationMatch() {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static BlobUploadOption generationNotMatch() {
      return new BlobUploadOption(StorageRpc.Option.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobUploadOption metagenerationMatch() {
      return new BlobUploadOption(StorageRpc.Option.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobUploadOption metagenerationNotMatch() {
      return new BlobUploadOption(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data disabledGzipContent. If this option is used, the request
     * will create a blob with disableGzipContent; at present, this is only for upload.
     */
    public static BlobUploadOption disableGzipContent() {
      return new BlobUploadOption(StorageRpc.Option.IF_DISABLE_GZIP_CONTENT, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobUploadOption encryptionKey(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new BlobUploadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobUploadOption userProject(String userProject) {
      return new BlobUploadOption(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static BlobUploadOption encryptionKey(String key) {
      return new BlobUploadOption(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, key);
    }

    /** Returns an option to set a customer-managed key for server-side encryption of the blob. */
    public static BlobUploadOption kmsKeyName(String kmsKeyName) {
      return new BlobUploadOption(StorageRpc.Option.KMS_KEY_NAME, kmsKeyName);
    }

    static Tuple<BlobInfo, BlobUploadOption[]> convert(BlobInfo info, BlobWriteOptions... options) {
      BlobInfo.Builder infoBuilder = info.toBuilder().setCrc32c(null).setMd5(null);
      List<BlobUploadOption> targetOptions = Lists.newArrayListWithCapacity(options.length);
      for (BlobWriteOptions option : options) {
        switch (option.option) {
          case IF_CRC32C_MATCH:
            infoBuilder.setCrc32c(info.getCrc32c());
            break;
          case IF_MD5_MATCH:
            infoBuilder.setMd5(info.getMd5());
            break;
          default:
            targetOptions.add(option.toTargetOption());
            break;
        }
      }
      return Tuple.of(
          infoBuilder.build(), targetOptions.toArray(new BlobUploadOption[targetOptions.size()]));
    }
  }

  /** Class for specifying blob write options. */
  class BlobWriteOptions implements Serializable {

    private static final long serialVersionUID = -3880421670966224580L;

    private final RequestOption option;
    private final Object value;

    enum RequestOption {
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

      StorageRpc.Option toRpcOption() {
        return StorageRpc.Option.valueOf(this.name());
      }
    }

    BlobUploadOption toTargetOption() {
      return new BlobUploadOption(this.option.toRpcOption(), this.value);
    }

    private BlobWriteOptions(RequestOption option, Object value) {
      this.option = option;
      this.value = value;
    }

    private BlobWriteOptions(RequestOption option) {
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
      if (!(obj instanceof BlobWriteOptions)) {
        return false;
      }
      final BlobWriteOptions other = (BlobWriteOptions) obj;
      return this.option == other.option && Objects.equals(this.value, other.value);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobWriteOptions predefinedAcl(PredefinedAccessControlList acl) {
      return new BlobWriteOptions(RequestOption.PREDEFINED_ACL, acl.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobWriteOptions doesNotExist() {
      return new BlobWriteOptions(RequestOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static BlobWriteOptions generationMatch() {
      return new BlobWriteOptions(RequestOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static BlobWriteOptions generationNotMatch() {
      return new BlobWriteOptions(RequestOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobWriteOptions metagenerationMatch() {
      return new BlobWriteOptions(RequestOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobWriteOptions metagenerationNotMatch() {
      return new BlobWriteOptions(RequestOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data MD5 hash match. If this option is used the request will
     * fail if blobs' data MD5 hash does not match.
     */
    public static BlobWriteOptions md5Match() {
      return new BlobWriteOptions(RequestOption.IF_MD5_MATCH, true);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match.
     */
    public static BlobWriteOptions crc32cMatch() {
      return new BlobWriteOptions(RequestOption.IF_CRC32C_MATCH, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobWriteOptions encryptionKey(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new BlobWriteOptions(RequestOption.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static BlobWriteOptions encryptionKey(String key) {
      return new BlobWriteOptions(RequestOption.CUSTOMER_SUPPLIED_KEY, key);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption of the blob.
     *
     * @param kmsKeyName the KMS key resource id
     */
    public static BlobWriteOptions kmsKeyName(String kmsKeyName) {
      return new BlobWriteOptions(RequestOption.KMS_KEY_NAME, kmsKeyName);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobWriteOptions userProject(String userProject) {
      return new BlobWriteOptions(RequestOption.USER_PROJECT, userProject);
    }
  }

  /** Class for specifying blob source options. */
  class BlobSourceOptions extends Option {

    private static final long serialVersionUID = -3712768261070182991L;

    private BlobSourceOptions(StorageRpc.Option rpcOption, Object value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link StorageService} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobSourceOptions generationMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided value.
     */
    public static BlobSourceOptions generationMatch(long generation) {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_MATCH, generation);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link StorageService} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobSourceOptions generationNotMatch() {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value.
     */
    public static BlobSourceOptions generationNotMatch(long generation) {
      return new BlobSourceOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, generation);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided value.
     */
    public static BlobSourceOptions metagenerationMatch(long metageneration) {
      return new BlobSourceOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metageneration);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided value.
     */
    public static BlobSourceOptions metagenerationNotMatch(long metageneration) {
      return new BlobSourceOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metageneration);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     */
    public static BlobSourceOptions decryptionKey(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new BlobSourceOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption of the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static BlobSourceOptions decryptionKey(String key) {
      return new BlobSourceOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, key);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobSourceOptions userProject(String userProject) {
      return new BlobSourceOptions(StorageRpc.Option.USER_PROJECT, userProject);
    }
  }

  /** Class for specifying blob get options. */
  class BlobGetOptions extends Option {

    private static final long serialVersionUID = 803817709703661480L;

    private BlobGetOptions(StorageRpc.Option rpcOption, Long value) {
      super(rpcOption, value);
    }

    private BlobGetOptions(StorageRpc.Option rpcOption, String value) {
      super(rpcOption, value);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link StorageService} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobGetOptions generationMatch() {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_MATCH, (Long) null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided value.
     */
    public static BlobGetOptions generationMatch(long generation) {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_MATCH, generation);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation value to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link StorageService} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobGetOptions generationNotMatch() {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, (Long) null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided value.
     */
    public static BlobGetOptions generationNotMatch(long generation) {
      return new BlobGetOptions(StorageRpc.Option.IF_GENERATION_NOT_MATCH, generation);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided value.
     */
    public static BlobGetOptions metagenerationMatch(long metageneration) {
      return new BlobGetOptions(StorageRpc.Option.IF_METAGENERATION_MATCH, metageneration);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided value.
     */
    public static BlobGetOptions metagenerationNotMatch(long metageneration) {
      return new BlobGetOptions(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH, metageneration);
    }

    /**
     * Returns an option to specify the blob's fields to be returned by the RPC call. If this option
     * is not provided all blob's fields are returned. {@code BlobGetOptions.fields}) can be used to
     * specify only the fields of interest. Blob name and bucket are always returned, even if not
     * specified.
     */
    public static BlobGetOptions fields(BlobMetadataField... fields) {
      return new BlobGetOptions(
          StorageRpc.Option.FIELDS, Helper.selector(BlobMetadataField.REQUIRED_FIELDS, fields));
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobGetOptions userProject(String userProject) {
      return new BlobGetOptions(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption of the
     * blob.
     */
    public static BlobGetOptions decryptionKey(Key key) {
      String base64Key = BaseEncoding.base64().encode(key.getEncoded());
      return new BlobGetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, base64Key);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption of the
     * blob.
     *
     * @param key the AES256 encoded in base64
     */
    public static BlobGetOptions decryptionKey(String key) {
      return new BlobGetOptions(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, key);
    }
  }

  /** Class for specifying bucket list options. */
  class BucketListOptions extends Option {

    private static final long serialVersionUID = 8754017079673290353L;

    private BucketListOptions(StorageRpc.Option option, Object value) {
      super(option, value);
    }

    /** Returns an option to specify the maximum number of buckets returned per page. */
    public static BucketListOptions pageSize(long pageSize) {
      return new BucketListOptions(StorageRpc.Option.MAX_RESULTS, pageSize);
    }

    /** Returns an option to specify the page token from which to start listing buckets. */
    public static BucketListOptions pageToken(String pageToken) {
      return new BucketListOptions(StorageRpc.Option.PAGE_TOKEN, pageToken);
    }

    /**
     * Returns an option to set a prefix to filter results to buckets whose names begin with this
     * prefix.
     */
    public static BucketListOptions prefix(String prefix) {
      return new BucketListOptions(StorageRpc.Option.PREFIX, prefix);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketListOptions userProject(String userProject) {
      return new BucketListOptions(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * Returns an option to specify the bucket's fields to be returned by the RPC call. If this
     * option is not provided all bucket's fields are returned. {@code BucketListOptions.fields}) can
     * be used to specify only the fields of interest. Bucket name is always returned, even if not
     * specified.
     */
    public static BucketListOptions fields(BucketAttribute... fields) {
      return new BucketListOptions(
          StorageRpc.Option.FIELDS,
          Helper.listSelector("items", BucketAttribute.REQUIRED_FIELDS, fields));
    }
  }

  /** Class for specifying blob list options. */
  class BlobListOptions extends Option {

    private static final String[] TOP_LEVEL_FIELDS = {"prefixes"};
    private static final long serialVersionUID = 9083383524788661294L;

    private BlobListOptions(StorageRpc.Option option, Object value) {
      super(option, value);
    }

    /** Returns an option to specify the maximum number of blobs returned per page. */
    public static BlobListOptions pageSize(long pageSize) {
      return new BlobListOptions(StorageRpc.Option.MAX_RESULTS, pageSize);
    }

    /** Returns an option to specify the page token from which to start listing blobs. */
    public static BlobListOptions pageToken(String pageToken) {
      return new BlobListOptions(StorageRpc.Option.PAGE_TOKEN, pageToken);
    }

    /**
     * Returns an option to set a prefix to filter results to blobs whose names begin with this
     * prefix.
     */
    public static BlobListOptions prefix(String prefix) {
      return new BlobListOptions(StorageRpc.Option.PREFIX, prefix);
    }

    /**
     * If specified, results are returned in a directory-like mode. Blobs whose names, after a
     * possible {@link #prefix(String)}, do not contain the '/' delimiter are returned as is. Blobs
     * whose names, after a possible {@link #prefix(String)}, contain the '/' delimiter, will have
     * their name truncated after the delimiter and will be returned as {@link Blob} objects where
     * only {@link Blob#getBlobId()}, {@link Blob#getSize()} and {@link Blob#isDirectory()} are set.
     * For such directory blobs, ({@link BlobId#getGeneration()} returns {@code null}), {@link
     * Blob#getSize()} returns {@code 0} while {@link Blob#isDirectory()} returns {@code true}.
     * Duplicate directory blobs are omitted.
     */
    public static BlobListOptions currentDirectory() {
      return new BlobListOptions(StorageRpc.Option.DELIMITER, true);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     *
     * @param userProject projectId of the billing user project.
     */
    public static BlobListOptions userProject(String userProject) {
      return new BlobListOptions(StorageRpc.Option.USER_PROJECT, userProject);
    }

    /**
     * If set to {@code true}, lists all versions of a blob. The default is {@code false}.
     *
     * @see <a href ="https://cloud.google.com/storage/docs/object-versioning">Object Versioning</a>
     */
    public static BlobListOptions versions(boolean versions) {
      return new BlobListOptions(StorageRpc.Option.VERSIONS, versions);
    }

    /**
     * Returns an option to specify the blob's fields to be returned by the RPC call. If this option
     * is not provided all blob's fields are returned. {@code BlobListOptions.fields}) can be used to
     * specify only the fields of interest. Blob name and bucket are always returned, even if not
     * specified.
     */
    public static BlobListOptions fields(BlobMetadataField... fields) {
      return new BlobListOptions(
          StorageRpc.Option.FIELDS,
          Helper.listSelector(TOP_LEVEL_FIELDS, "items", BlobMetadataField.REQUIRED_FIELDS, fields));
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
    public static UrlSigningOption httpMethod(HttpMethod httpMethod) {
      return new UrlSigningOption(RequestOption.HTTP_METHOD, httpMethod);
    }

    /**
     * Use it if signature should include the blob's content-type. When used, users of the signed
     * URL should include the blob's content-type with their request. If using this URL from a
     * browser, you must include a content type that matches what the browser will send.
     */
    public static UrlSigningOption withContentType() {
      return new UrlSigningOption(RequestOption.CONTENT_TYPE, true);
    }

    /**
     * Use it if signature should include the blob's md5. When used, users of the signed URL should
     * include the blob's md5 with their request.
     */
    public static UrlSigningOption withMd5() {
      return new UrlSigningOption(RequestOption.MD5, true);
    }

    /**
     * Use it if signature should include the blob's canonicalized extended headers. When used,
     * users of the signed URL should include the canonicalized extended headers with their request.
     *
     * @see <a href="https://cloud.google.com/storage/docs/xml-api/reference-headers">Request
     *     Headers</a>
     */
    public static UrlSigningOption withExtHeaders(Map<String, String> extHeaders) {
      return new UrlSigningOption(RequestOption.EXT_HEADERS, extHeaders);
    }

    /**
     * Use if signature version should be V2. This is the default if neither this or {@code
     * withV4Signature()} is called.
     */
    public static UrlSigningOption withV2Signature() {
      return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SignatureSchemeVersion.V2);
    }

    /**
     * Use if signature version should be V4. Note that V4 Signed URLs can't have an expiration
     * longer than 7 days. V2 will be the default if neither this or {@code withV2Signature()} is
     * called.
     */
    public static UrlSigningOption withV4Signature() {
      return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SignatureSchemeVersion.V4);
    }

    /**
     * Provides a service account signer to sign the URL. If not provided an attempt will be made to
     * get it from the environment.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
     *     Accounts</a>
     */
    public static UrlSigningOption signWith(ServiceAccountSigner signer) {
      return new UrlSigningOption(RequestOption.SERVICE_ACCOUNT_CRED, signer);
    }

    /**
     * Use a different host name than the default host name 'storage.googleapis.com'. This option is
     * particularly useful for developers to point requests to an alternate endpoint (e.g. a staging
     * environment or sending requests through VPC). Note that if using this with the {@code
     * withVirtualHostedStyle()} method, you should omit the bucket name from the hostname, as it
     * automatically gets prepended to the hostname for virtual hosted-style URLs.
     */
    public static UrlSigningOption withHostName(String hostName) {
      return new UrlSigningOption(RequestOption.HOST_NAME, hostName);
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
    public static UrlSigningOption withVirtualHostedStyle() {
      return new UrlSigningOption(RequestOption.VIRTUAL_HOSTED_STYLE, "");
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
    public static UrlSigningOption withPathStyle() {
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
    public static UrlSigningOption withQueryParams(Map<String, String> queryParams) {
      return new UrlSigningOption(RequestOption.QUERY_PARAMS, queryParams);
    }
  }

  /**
   * A class to contain all information needed for a Google Cloud StorageService Compose operation.
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

    public static class BlobTransferBuilder {

      private final List<SourceBlobInfo> sourceBlobs = new LinkedList<>();
      private final Set<BlobUploadOption> targetOptions = new LinkedHashSet<>();
      private BlobInfo target;

      /** Add source blobs for compose operation. */
      public BlobTransferBuilder addSource(Iterable<String> blobs) {
        for (String blob : blobs) {
          sourceBlobs.add(new SourceBlobInfo(blob));
        }
        return this;
      }

      /** Add source blobs for compose operation. */
      public BlobTransferBuilder addSource(String... blobs) {
        return addSource(Arrays.asList(blobs));
      }

      /** Add a source with a specific generation to match. */
      public BlobTransferBuilder addSource(String blob, long generation) {
        sourceBlobs.add(new SourceBlobInfo(blob, generation));
        return this;
      }

      /** Sets compose operation's target blob. */
      public BlobTransferBuilder setTarget(BlobInfo target) {
        this.target = target;
        return this;
      }

      /** Sets compose operation's target blob options. */
      public BlobTransferBuilder setTargetOptions(BlobUploadOption... options) {
        Collections.addAll(targetOptions, options);
        return this;
      }

      /** Sets compose operation's target blob options. */
      public BlobTransferBuilder setTargetOptions(Iterable<BlobUploadOption> options) {
        Iterables.addAll(targetOptions, options);
        return this;
      }

      /** Creates a {@code ComposeBlobsRequest} object. */
      public ComposeBlobsRequest build() {
        checkArgument(!sourceBlobs.isEmpty());
        checkNotNull(target);
        return new ComposeBlobsRequest(this);
      }
    }

    private ComposeBlobsRequest(BlobTransferBuilder builder) {
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
    public static ComposeBlobsRequest of(Iterable<String> sources, BlobInfo target) {
      return newBuilder().setTarget(target).addSource(sources).build();
    }

    /**
     * Creates a {@code ComposeBlobsRequest} object.
     *
     * @param bucket name of the bucket where the compose operation takes place
     * @param sources source blobs names
     * @param target target blob name
     */
    public static ComposeBlobsRequest of(String bucket, Iterable<String> sources, String target) {
      return of(sources, BlobInfo.newBuilder(BlobId.of(bucket, target)).build());
    }

    /** Returns a {@code ComposeBlobsRequest} builder. */
    public static BlobTransferBuilder newBuilder() {
      return new BlobTransferBuilder();
    }
  }

  /** A class to contain all information needed for a Google Cloud StorageService Copy operation. */
  class DataCopyRequest implements Serializable {

    private static final long serialVersionUID = -4498650529476219937L;

    private final BlobId source;
    private final List<BlobSourceOptions> sourceOptions;
    private final boolean overrideInfo;
    private final BlobInfo target;
    private final List<BlobUploadOption> targetOptions;
    private final Long megabytesCopiedPerChunk;

    public static class ChunkedCopyBuilder {

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
      public ChunkedCopyBuilder setSource(String bucket, String blob) {
        this.source = BlobId.of(bucket, blob);
        return this;
      }

      /**
       * Sets the blob to copy given a {@link BlobId}.
       *
       * @return the builder
       */
      public ChunkedCopyBuilder setSource(BlobId source) {
        this.source = source;
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public ChunkedCopyBuilder setSourceOptions(BlobSourceOptions... options) {
        Collections.addAll(sourceOptions, options);
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public ChunkedCopyBuilder setSourceOptions(Iterable<BlobSourceOptions> options) {
        Iterables.addAll(sourceOptions, options);
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source.
       *
       * @return the builder
       */
      public ChunkedCopyBuilder setTarget(BlobId targetId) {
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
      public ChunkedCopyBuilder setTarget(BlobId targetId, BlobUploadOption... options) {
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
      public ChunkedCopyBuilder setTarget(BlobInfo target, BlobUploadOption... options) {
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
      public ChunkedCopyBuilder setTarget(BlobInfo target, Iterable<BlobUploadOption> options) {
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
      public ChunkedCopyBuilder setTarget(BlobId targetId, Iterable<BlobUploadOption> options) {
        this.overrideInfo = false;
        this.target = BlobInfo.newBuilder(targetId).build();
        Iterables.addAll(targetOptions, options);
        return this;
      }

      /**
       * Sets the maximum number of megabytes to copy for each RPC call. This parameter is ignored
       * if source and target blob share the same location and storage class as copy is made with
       * one single RPC.
       *
       * @return the builder
       */
      public ChunkedCopyBuilder setMegabytesCopiedPerChunk(Long megabytesCopiedPerChunk) {
        this.megabytesCopiedPerChunk = megabytesCopiedPerChunk;
        return this;
      }

      /** Creates a {@code DataCopyRequest} object. */
      public DataCopyRequest build() {
        return new DataCopyRequest(this);
      }
    }

    private DataCopyRequest(ChunkedCopyBuilder builder) {
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
     * true}, the value of {@link #getTarget()} is used to replace source blob information (e.g.
     * {@code contentType}, {@code contentLanguage}). Target blob information is set exactly to this
     * value, no information is inherited from the source blob. If {@code false}, target blob
     * information is inherited from the source blob.
     */
    public boolean overrideInfo() {
      return overrideInfo;
    }

    /** Returns blob's target options. */
    public List<BlobUploadOption> getTargetOptions() {
      return targetOptions;
    }

    /**
     * Returns the maximum number of megabytes to copy for each RPC call. This parameter is ignored
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
     * @param sourceBucket name of the bucket containing the source blob
     * @param sourceBlob name of the source blob
     * @param target a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static DataCopyRequest of(String sourceBucket, String sourceBlob, BlobInfo target) {
      return newBuilder().setSource(sourceBucket, sourceBlob).setTarget(target).build();
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
    public static DataCopyRequest of(BlobId sourceBlobId, BlobInfo target) {
      return newBuilder().setSource(sourceBlobId).setTarget(target).build();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBucket name of the bucket containing both the source and the target blob
     * @param sourceBlob name of the source blob
     * @param targetBlob name of the target blob
     * @return a copy request
     */
    public static DataCopyRequest of(String sourceBucket, String sourceBlob, String targetBlob) {
      return DataCopyRequest.newBuilder()
          .setSource(sourceBucket, sourceBlob)
          .setTarget(BlobId.of(sourceBucket, targetBlob))
          .build();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBucket name of the bucket containing the source blob
     * @param sourceBlob name of the source blob
     * @param target a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static DataCopyRequest of(String sourceBucket, String sourceBlob, BlobId target) {
      return newBuilder().setSource(sourceBucket, sourceBlob).setTarget(target).build();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBlobId a {@code BlobId} object for the source blob
     * @param targetBlob name of the target blob, in the same bucket of the source blob
     * @return a copy request
     */
    public static DataCopyRequest of(BlobId sourceBlobId, String targetBlob) {
      return DataCopyRequest.newBuilder()
          .setSource(sourceBlobId)
          .setTarget(BlobId.of(sourceBlobId.getBucket(), targetBlob))
          .build();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param sourceBlobId a {@code BlobId} object for the source blob
     * @param targetBlobId a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static DataCopyRequest of(BlobId sourceBlobId, BlobId targetBlobId) {
      return DataCopyRequest.newBuilder().setSource(sourceBlobId).setTarget(targetBlobId).build();
    }

    /** Creates a builder for {@code DataCopyRequest} objects. */
    public static ChunkedCopyBuilder newBuilder() {
      return new ChunkedCopyBuilder();
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
   *     .buildSignatureMetadata());
   * }</pre>
   *
   * @return a complete bucket
   * @throws StorageException upon failure
   */
  Bucket create(BucketInfo bucketInfo, BucketTargetOptions... options);

  /**
   * Creates a new blob with no content.
   *
   * <p>Example of creating a blob with no content.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.of(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").buildSignatureMetadata();
   * Blob blob = storage.create(blobInfo);
   * }</pre>
   *
   * @return a [@code Blob} with complete information
   * @throws StorageException upon failure
   */
  Blob create(BlobInfo blobInfo, BlobUploadOption... options);

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
   * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").buildSignatureMetadata();
   * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8));
   * }</pre>
   *
   * @return a [@code Blob} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  Blob create(BlobInfo blobInfo, byte[] content, BlobUploadOption... options);

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
   * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").buildSignatureMetadata();
   * Blob blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8), 7, 5);
   * }</pre>
   *
   * @return a [@code Blob} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  Blob create(
      BlobInfo blobInfo, byte[] content, int offset, int length, BlobUploadOption... options);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. By default any md5 and crc32c values in
   * the given {@code blobInfo} are ignored unless requested via the {@code
   * BlobWriteOptions.md5Match} and {@code BlobWriteOptions.crc32cMatch} options. The given input
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
   * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").buildSignatureMetadata();
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
   *     .buildSignatureMetadata();
   * Blob blob = storage.create(blobInfo, content, BlobWriteOptions.encryptionKey(encryptionKey));
   * }</pre>
   *
   * @return a [@code Blob} with complete information
   * @throws StorageException upon failure
   */
  @Deprecated
  Blob create(BlobInfo blobInfo, InputStream content, BlobWriteOptions... options);

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * <p>Accepts an optional userProject {@link GetBucketOption} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example of getting information on a bucket, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * Bucket bucket = storage.get(bucketName,
   *     GetBucketOption.metagenerationMatch(bucketMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Bucket get(String bucket, GetBucketOption... options);

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
   * matches the bucket's service metageneration otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Bucket bucket = storage.get(bucketName, GetBucketOption.fields(BucketAttribute.METAGENERATION));
   * storage.lockRetentionPolicy(bucket, BucketTargetOptions.metagenerationMatch());
   * }</pre>
   *
   * @return a {@code Bucket} object of the locked bucket
   * @throws StorageException upon failure
   */
  Bucket lockRetentionPolicy(BucketInfo bucket, BucketTargetOptions... options);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional userProject {@link BlobGetOptions} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example of getting information on a blob, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * Blob blob = storage.get(bucketName, blobName,
   *     BlobGetOptions.metagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Blob get(String bucket, String blob, BlobGetOptions... options);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional userProject {@link BlobGetOptions} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example of getting information on a blob, only if its metageneration matches a value,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * BlobId blobId = BlobId.of(bucketName, blobName);
   * Blob blob = storage.get(blobId, BlobGetOptions.metagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * <p>Example of getting information on a blob encrypted using Customer Supplied Encryption Keys,
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
   * BlobId blobId = BlobId.of(bucketName, blobName);
   * Blob blob = storage.get(blobId, BlobGetOptions.decryptionKey(blobEncryptionKey));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Blob get(BlobId blob, BlobGetOptions... options);

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
   * @throws StorageException upon failure
   */
  Blob get(BlobId blob);

  /**
   * Lists the project's buckets.
   *
   * <p>Example of listing buckets, specifying the page size and a name prefix.
   *
   * <pre>{@code
   * String prefix = "bucket_";
   * Page<Bucket> buckets = storage.list(BucketListOptions.pageSize(100),
   *     BucketListOptions.prefix(prefix));
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
   * Lists the bucket's blobs. If the {@link BlobListOptions#currentDirectory()} option is provided,
   * results are returned in a directory-like mode.
   *
   * <p>Example of listing blobs in a provided directory.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String directory = "my_directory/";
   * Page<Blob> blobs = storage.list(bucketName, BlobListOptions.currentDirectory(),
   *     BlobListOptions.prefix(directory));
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
   * <p>Accepts an optional userProject {@link BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example of updating bucket information.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * BucketInfo bucketInfo = BucketInfo.newBuilder(bucketName).setVersioningEnabled(true).buildSignatureMetadata();
   * Bucket bucket = storage.update(bucketInfo);
   * }</pre>
   *
   * @return the updated bucket
   * @throws StorageException upon failure
   */
  Bucket update(BucketInfo bucketInfo, BucketTargetOptions... options);

  /**
   * Updates blob information. Original metadata are merged with metadata in the provided {@code
   * blobInfo}. To replace metadata instead you first have to unset them. Unsetting metadata can be
   * done by setting the provided {@code blobInfo}'s metadata to {@code null}. Accepts an optional
   * userProject {@link BlobUploadOption} option which defines the project id to assign operational
   * costs.
   *
   * <p>Example of udating a blob, only if the blob's metageneration matches a value, otherwise a
   * {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * Blob blob = storage.get(bucketName, blobName);
   * BlobInfo updatedInfo = blob.toBuilder().setContentType("text/plain").buildSignatureMetadata();
   * storage.update(updatedInfo, BlobUploadOption.metagenerationMatch());
   * }</pre>
   *
   * @return the updated blob
   * @throws StorageException upon failure
   */
  Blob update(BlobInfo blobInfo, BlobUploadOption... options);

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
   *     .buildSignatureMetadata());
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
   *     .buildSignatureMetadata());
   * }</pre>
   *
   * @return the updated blob
   * @throws StorageException upon failure
   */
  Blob update(BlobInfo blobInfo);

  /**
   * Deletes the requested bucket.
   *
   * <p>Accepts an optional userProject {@link BucketSourceOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example of deleting a bucket, only if its metageneration matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * boolean deleted = storage.delete(bucketName,
   *     BucketSourceOptions.metagenerationMatch(bucketMetageneration));
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
   * <p>Example of deleting a blob, only if its generation matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * boolean deleted = storage.delete(bucketName, blobName,
   *     BlobSourceOptions.generationMatch(blobGeneration));
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
   * <p>Accepts an optional userProject {@link BlobSourceOptions} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example of deleting a blob, only if its generation matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42;
   * BlobId blobId = BlobId.of(bucketName, blobName);
   * boolean deleted = storage.delete(blobId, BlobSourceOptions.generationMatch(blobGeneration));
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
   * @throws StorageException upon failure
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
   * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").buildSignatureMetadata();
   * ComposeBlobsRequest request = ComposeBlobsRequest.newBuilder()
   *     .setTarget(blobInfo)
   *     .addSource(sourceBlob1)
   *     .addSource(sourceBlob2)
   *     .buildSignatureMetadata();
   * Blob blob = storage.compose(request);
   * }</pre>
   *
   * @return the composed blob
   * @throws StorageException upon failure
   */
  Blob compose(ComposeBlobsRequest composeRequest);

  /**
   * Sends a copy request. This method copies both blob's data and information. To override source
   * blob's information supply a {@code BlobInfo} to the {@code DataCopyRequest} using either {@link
   * DataCopyRequest.ChunkedCopyBuilder#setTarget(BlobInfo, BlobUploadOption...)} or {@link
   * DataCopyRequest.ChunkedCopyBuilder#setTarget(BlobInfo, Iterable)}.
   *
   * <p>This method returns a {@link CopyWriter} object for the provided {@code DataCopyRequest}. If
   * source and destination objects share the same location and storage class the source blob is
   * copied with one request and {@link CopyWriter#getResult()} immediately returns, regardless of
   * the {@link DataCopyRequest#megabytesCopiedPerChunk} parameter. If source and destination have
   * different location or storage class {@link CopyWriter#getResult()} might issue multiple RPC
   * calls depending on blob's size.
   *
   * <p>Example of copying a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * DataCopyRequest request = DataCopyRequest.newBuilder()
   *     .setSource(BlobId.of(bucketName, blobName))
   *     .setTarget(BlobId.of(bucketName, copyBlobName))
   *     .buildSignatureMetadata();
   * Blob blob = storage.copy(request).getResult();
   * }</pre>
   *
   * <p>Example of copying a blob in chunks.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String copyBlobName = "copy_blob_name";
   * DataCopyRequest request = DataCopyRequest.newBuilder()
   *     .setSource(BlobId.of(bucketName, blobName))
   *     .setTarget(BlobId.of(bucketName, copyBlobName))
   *     .buildSignatureMetadata();
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
   * DataCopyRequest request = DataCopyRequest.newBuilder()
   *     .setSource(blobId)
   *     .setSourceOptions(BlobSourceOptions.decryptionKey(oldEncryptionKey))
   *     .setTarget(blobId, BlobUploadOption.encryptionKey(newEncryptionKey))
   *     .buildSignatureMetadata();
   * Blob blob = storage.copy(request).getResult();
   * }</pre>
   *
   * @return a {@link CopyWriter} object that can be used to get information on the newly created
   *     blob or to complete the copy if more than one RPC request is needed
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
   */
  CopyWriter copy(DataCopyRequest copyRequest);

  /**
   * Reads all the bytes from a blob.
   *
   * <p>Example of reading all bytes of a blob, if generation matches a value, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobGeneration = 42";
   * byte[] content = storage.readAllBytes(bucketName, blobName,
   *     BlobSourceOptions.generationMatch(blobGeneration));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
   */
  byte[] readAllBytes(String bucket, String blob, BlobSourceOptions... options);

  /**
   * Reads all the bytes from a blob.
   *
   * <p>Example of reading all bytes of a blob's specific generation, otherwise a {@link
   * StorageException} is thrown.
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
   *     bucketName, blobName, BlobSourceOptions.decryptionKey(decryptionKey));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
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
   * batch.update(BlobInfo.newBuilder(secondBlob).setContentType("text/plain").buildSignatureMetadata());
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
   * <p>The {@link BlobSourceOptions#generationMatch()} and {@link
   * BlobSourceOptions#generationMatch(long)} options can be used to ensure that {@code
   * blobReadChannel.read(ByteBuffer)} calls will throw {@link StorageException} if the blob`s
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
   * @throws StorageException upon failure
   */
  ReadChannel reader(BlobId blob, BlobSourceOptions... options);

  /**
   * Creates a blob and return a channel for writing its content. By default any md5 and crc32c
   * values in the given {@code blobInfo} are ignored unless requested via the {@code
   * BlobWriteOptions.md5Match} and {@code BlobWriteOptions.crc32cMatch} options.
   *
   * <p>Example of writing a blob's content through a writer.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.of(bucketName, blobName);
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").buildSignatureMetadata();
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
  WriteChannel writer(BlobInfo blobInfo, BlobWriteOptions... options);

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
   * BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").buildSignatureMetadata();
   * URL signedURL = storage.signUrl(
   *     blobInfo,
   *     1, TimeUnit.HOURS,
   *     StorageService.UrlSigningOption.httpMethod(HttpMethod.POST));
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
   * fixed amount of time, you can use this method to generate a URL that is only valid within a
   * certain time period. This is particularly useful if you don't want publicly accessible blobs,
   * but also don't want to require users to explicitly log in. Signing a URL requires a service
   * account signer. If an instance of {@link com.google.auth.ServiceAccountSigner} was passed to
   * {@link StorageOptions}' builder via {@code setCredentials(Credentials)} or the default
   * credentials are being used and the environment variable {@code GOOGLE_APPLICATION_CREDENTIALS}
   * is set or your application is running in App Engine, then {@code signUrl} will use that
   * credentials to sign the URL. If the credentials passed to {@link StorageOptions} do not
   * implement {@link ServiceAccountSigner} (this is the case, for instance, for Google Cloud SDK
   * credentials) then {@code signUrl} will throw an {@link IllegalStateException} unless an
   * implementation of {@link ServiceAccountSigner} is passed using the {@link
   * UrlSigningOption#signWith(ServiceAccountSigner)} option.
   *
   * <p>A service account signer is looked for in the following order:
   *
   * <ol>
   *   <li>The signer passed with the option {@link UrlSigningOption#signWith(ServiceAccountSigner)}
   *   <li>The credentials passed to {@link StorageOptions}
   *   <li>The default credentials, if no credentials were passed to {@link StorageOptions}
   * </ol>
   *
   * <p>Example of creating a signed URL that is valid for 1 week, using the default credentials for
   * signing the URL, the default signing method (V2), and the default URL style (path-style):
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newBuilder(bucketName, blobName).buildSignatureMetadata(),
   *     7, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example of creating a signed URL passing the {@link UrlSigningOption#withV4Signature()} option,
   * which enables V4 signing:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newBuilder(bucketName, blobName).buildSignatureMetadata(),
   *     7, TimeUnit.DAYS,
   *     StorageService.UrlSigningOption.withV4Signature());
   * }</pre>
   *
   * <p>Example of creating a signed URL passing the {@link UrlSigningOption#withVirtualHostedStyle()}
   * option, which specifies the bucket name in the hostname of the URI, rather than in the path:
   *
   * <pre>{@code
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newBuilder(bucketName, blobName).buildSignatureMetadata(),
   *     1, TimeUnit.DAYS,
   *     StorageService.UrlSigningOption.withVirtualHostedStyle());
   * }</pre>
   *
   * <p>Example of creating a signed URL passing the {@link UrlSigningOption#withPathStyle()} option,
   * which specifies the bucket name in path portion of the URI, rather than in the hostname:
   *
   * <pre>{@code
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newBuilder(bucketName, blobName).buildSignatureMetadata(),
   *     1, TimeUnit.DAYS,
   *     StorageService.UrlSigningOption.withPathStyle());
   * }</pre>
   *
   * <p>Example of creating a signed URL passing the {@link
   * UrlSigningOption#signWith(ServiceAccountSigner)} option, that will be used for signing the URL.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * String kfPath = "/path/to/keyfile.json";
   * URL signedUrl = storage.signUrl(
   *     BlobInfo.newBuilder(bucketName, blobName).buildSignatureMetadata(),
   *     7, TimeUnit.DAYS,
   *     UrlSigningOption.signWith(ServiceAccountCredentials.fromStream(new FileInputStream(kfPath))));
   * }</pre>
   *
   * <p>Note that the {@link ServiceAccountSigner} may require additional configuration to enable
   * URL signing. See the documentation for the implementation for more details.
   *
   * @param blobInfo the blob associated with the signed URL
   * @param duration time until the signed URL expires, expressed in {@code unit}. The finest
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param unit time unit of the {@code duration} parameter
   * @param options optional URL signing options
   * @throws IllegalStateException if {@link UrlSigningOption#signWith(ServiceAccountSigner)} was not
   *     used and no implementation of {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withMd5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  URL signUrl(BlobInfo blobInfo, long duration, TimeUnit unit, UrlSigningOption... options);

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
   * @param blobIds blobs to get
   * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> get(BlobId... blobIds);

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
   * @param blobIds blobs to get
   * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> get(Iterable<BlobId> blobIds);

  /**
   * Updates the requested blobs. A batch request is used to perform this call. Original metadata
   * are merged with metadata in the provided {@code BlobInfo} objects. To replace metadata instead
   * you first have to unset them. Unsetting metadata can be done by setting the provided {@code
   * BlobInfo} objects metadata to {@code null}. See {@link #update(BlobInfo)} for a code example.
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
   *     firstBlob.toBuilder().setContentType("text/plain").buildSignatureMetadata(),
   *     secondBlob.toBuilder().setContentType("text/plain").buildSignatureMetadata());
   * }</pre>
   *
   * @param blobInfos blobs to update
   * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> update(BlobInfo... blobInfos);

  /**
   * Updates the requested blobs. A batch request is used to perform this call. Original metadata
   * are merged with metadata in the provided {@code BlobInfo} objects. To replace metadata instead
   * you first have to unset them. Unsetting metadata can be done by setting the provided {@code
   * BlobInfo} objects metadata to {@code null}. See {@link #update(BlobInfo)} for a code example.
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
   * blobs.add(firstBlob.toBuilder().setContentType("text/plain").buildSignatureMetadata());
   * blobs.add(secondBlob.toBuilder().setContentType("text/plain").buildSignatureMetadata());
   * List<Blob> updatedBlobs = storage.update(blobs);
   * }</pre>
   *
   * @param blobInfos blobs to update
   * @return an immutable list of {@code Blob} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<Blob> update(Iterable<BlobInfo> blobInfos);

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
   * @param blobIds blobs to delete
   * @return an immutable list of booleans. If a blob has been deleted the corresponding item in the
   *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
   *     was denied the corresponding item is {@code false}.
   * @throws StorageException upon failure
   */
  List<Boolean> delete(BlobId... blobIds);

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
   * @param blobIds blobs to delete
   * @return an immutable list of booleans. If a blob has been deleted the corresponding item in the
   *     list is {@code true}. If a blob was not found, deletion failed or access to the resource
   *     was denied the corresponding item is {@code false}.
   * @throws StorageException upon failure
   */
  List<Boolean> delete(Iterable<BlobId> blobIds);

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
   * BucketSourceOptions userProjectOption = BucketSourceOptions.userProject("myProject");
   * Acl acl = storage.getAcl(bucketName, new User(userEmail), userProjectOption);
   * }</pre>
   *
   * @param bucket name of the bucket where the getAcl operation takes place
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
   * BucketSourceOptions userProject = BucketSourceOptions.userProject("myProject");
   * boolean deleted = storage.deleteAcl(bucketName, User.ofAllAuthenticatedUsers(), userProject);
   * }</pre>
   *
   * @param bucket name of the bucket to delete an ACL from
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
   *     BucketSourceOptions.userProject("myProject"));
   * }</pre>
   *
   * @param bucket name of the bucket for which an ACL should be created
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
   *     BucketSourceOptions.userProject("myProject"));
   * }</pre>
   *
   * @param bucket name of the bucket where the updateAcl operation takes place
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
   * List<Acl> acls = storage.listAcls(bucketName, BucketSourceOptions.userProject("myProject"));
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @param bucket the name of the bucket to list ACLs for
   * @param options any number of BucketSourceOptions to apply to this operation
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
   * <p>Example of getting the default ACL entry for an entity on a bucket.
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
   * @throws StorageException upon failure
   */
  boolean deleteDefaultAcl(String bucket, Entity entity);

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
   * @throws StorageException upon failure
   */
  Acl createDefaultAcl(String bucket, Acl acl);

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
   * @throws StorageException upon failure
   */
  Acl updateDefaultAcl(String bucket, Acl acl);

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
   * @throws StorageException upon failure
   */
  List<Acl> listDefaultAcls(String bucket);

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
   * @throws StorageException upon failure
   */
  Acl getAcl(BlobId blob, Entity entity);

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
   * @throws StorageException upon failure
   */
  boolean deleteAcl(BlobId blob, Entity entity);

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
   * @throws StorageException upon failure
   */
  Acl createAcl(BlobId blob, Acl acl);

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
   * @throws StorageException upon failure
   */
  Acl updateAcl(BlobId blob, Acl acl);

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
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(BlobId blob);

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
   * @throws StorageException upon failure
   */
  HmacKey createHmacKey(ServiceAccount serviceAccount, HmacKeyCreateOption... options);

  /**
   * Lists HMAC keys for a given service account. Note this returns {@code HmacKeyMetadata} objects,
   * which do not contain secret keys.
   *
   * <p>Example of listing HMAC keys, specifying project id.
   *
   * <pre>{@code
   * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
   *     StorageService.ListHmacKeysOptions.projectId("my-project-id"));
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
   *     StorageService.ListHmacKeysOptions.serviceAccount(serviceAccount),
   *     StorageService.ListHmacKeysOptions.maxResults(10L),
   *     StorageService.ListHmacKeysOptions.showDeletedKeys(true));
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
   * <p>Example of getting an HMAC key. Since projectId isn't specified, the same project ID as the
   * storage client instance will be used.
   *
   * <pre>{@code
   * String hmacKeyAccessId = "my-access-id";
   * HmacKey.HmackeyMetadata hmacKeyMetadata = storage.getHmacKey(hmacKeyAccessId);
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  HmacKeyMetadata getHmacKey(String accessId, RetrieveHmacKeyOption... options);

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
   * @throws StorageException upon failure
   */
  void deleteHmacKey(HmacKeyMetadata hmacKeyMetadata, DeleteHmacKeyRequestOption... options);

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
   * @throws StorageException upon failure
   */
  HmacKeyMetadata updateHmacKeyState(
      final HmacKeyMetadata hmacKeyMetadata,
      final HmacKey.HmacKeyState state,
      HmacKeyUpdateOption... options);
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
   * @param bucket name of the bucket where the getIamPolicy operation takes place
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy getIamPolicy(String bucket, BucketSourceOptions... options);

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
   *             .buildSignatureMetadata());
   * }</pre>
   *
   * @param bucket name of the bucket where the setIamPolicy operation takes place
   * @param policy policy to be set on the specified bucket
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy setIamPolicy(String bucket, Policy policy, BucketSourceOptions... options);

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
   * @param bucket name of the bucket where the testIamPermissions operation takes place
   * @param permissions list of permissions to test on the bucket
   * @param options extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  List<Boolean> testIamPermissions(
      String bucket, List<String> permissions, BucketSourceOptions... options);

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
   * @param projectId the ID of the project for which the service account should be fetched.
   * @return the service account associated with this project
   * @throws StorageException upon failure
   */
  ServiceAccount getServiceAccount(String projectId);
}
