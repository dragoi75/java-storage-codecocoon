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

    private final String aclValue;

    PredefinedAccessControlList(String aclValue) {
      this.aclValue = aclValue;
    }

    String getEntry() {
      return aclValue;
    }
  }

  enum BucketFields implements FieldSelector {
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

    private final String fieldPattern;

    BucketFields(String fieldPattern) {
      this.fieldPattern = fieldPattern;
    }

    @Override
    public String getSelector() {
      return fieldPattern;
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
    KMS_KEY_NAME("kmsKey"),
    EVENT_BASED_HOLD("eventBasedHold"),
    TEMPORARY_HOLD("temporaryHold"),
    RETENTION_EXPIRATION_TIME("retentionExpirationTime"),
    UPDATED("updated"),
    CUSTOM_TIME("customTime"),
    TIME_STORAGE_CLASS_UPDATED("timeStorageClassUpdated");

    static final List<? extends FieldSelector> MANDATORY_SELECTORS = ImmutableList.of(BUCKET, NAME);

    private final String fieldPattern;

    BlobMetadataField(String fieldPattern) {
      this.fieldPattern = fieldPattern;
    }

    @Override
    public String getSelector() {
      return fieldPattern;
    }
  }

  enum UrlScheme {
    HTTP("http"),
    HTTPS("https");

    private final String uriProtocol;

    UrlScheme(String uriProtocol) {
      this.uriProtocol = uriProtocol;
    }

    public String getScheme() {
      return uriProtocol;
    }
  }

  /** Class for specifying bucket target options. */
  class BucketTargetOptions extends Option {

    private static final long CLASS_VERSION_UID = -5880204616982900975L;

    private BucketTargetOptions(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    private BucketTargetOptions(StorageRpc.RequestOption requestOption) {
      this(requestOption, null);
    }

    /** Returns an option for specifying bucket's predefined ACL configuration. */
    public static BucketTargetOptions withPredefinedAcl(PredefinedAccessControlList accessControl) {
      return new BucketTargetOptions(StorageRpc.RequestOption.PREDEFINED_ACL, accessControl.getEntry());
    }

    /** Returns an option for specifying bucket's default ACL configuration for blobs. */
    public static BucketTargetOptions predefinedDefaultObjectAcl(PredefinedAccessControlList accessControl) {
      return new BucketTargetOptions(
          StorageRpc.RequestOption.PREDEFINED_DEFAULT_OBJECT_ACL, accessControl.getEntry());
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BucketTargetOptions ifMetagenerationMatch() {
      return new BucketTargetOptions(StorageRpc.RequestOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if metageneration matches.
     */
    public static BucketTargetOptions ifMetagenerationNotMatch() {
      return new BucketTargetOptions(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     */
    public static BucketTargetOptions withUserProject(String requesterProject) {
      return new BucketTargetOptions(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option to define the projection in the API request. In some cases this option may
     * be needed to be set to `noAcl` to omit ACL data from the response. The default getValue is
     * `full`
     *
     * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/buckets/patch">Buckets:
     *     patch</a>
     */
    public static BucketTargetOptions projection(String outputView) {
      return new BucketTargetOptions(StorageRpc.RequestOption.PROJECTION, outputView);
    }
  }

  /** Class for specifying bucket source options. */
  class BucketReadOption extends Option {

    private static final long CLASS_VERSION_UID = 5185657617120212117L;

    private BucketReadOption(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided getValue.
     */
    public static BucketReadOption ifMetagenerationMatch(long metaGen) {
      return new BucketReadOption(StorageRpc.RequestOption.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided getValue.
     */
    public static BucketReadOption ifMetagenerationNotMatch(long metaGen) {
      return new BucketReadOption(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketReadOption withUserProject(String requesterProject) {
      return new BucketReadOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    public static BucketReadOption requestPolicyVersion(long policyVersion) {
      return new BucketReadOption(StorageRpc.RequestOption.REQUESTED_POLICY_VERSION, policyVersion);
    }
  }

  /** Class for specifying listHmacKeys options */
  class HmacKeysListOption extends Option {
    private HmacKeysListOption(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option for the Service Account whose keys to list. If this option is not used,
     * keys for all accounts will be listed.
     */
    public static HmacKeysListOption serviceAccountEmail(ServiceAccount signerAccount) {
      return new HmacKeysListOption(
          StorageRpc.RequestOption.SERVICE_ACCOUNT_EMAIL, signerAccount.getEmail());
    }

    /** Returns an option for the maximum amount from HMAC keys returned per page. */
    public static HmacKeysListOption maximumResults(long maxItems) {
      return new HmacKeysListOption(StorageRpc.RequestOption.MAX_RESULTS, maxItems);
    }

    /** Returns an option to specify the page token from which to start listing HMAC keys. */
    public static HmacKeysListOption withPageToken(String continuationToken) {
      return new HmacKeysListOption(StorageRpc.RequestOption.PAGE_TOKEN, continuationToken);
    }

    /**
     * Returns an option to specify whether to show deleted keys in the result. This option is false
     * by default.
     */
    public static HmacKeysListOption showDeletedKeys(boolean includeDeleted) {
      return new HmacKeysListOption(StorageRpc.RequestOption.SHOW_DELETED_KEYS, includeDeleted);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeysListOption userProject(String requesterProject) {
      return new HmacKeysListOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static HmacKeysListOption projectId(String projectIdentifier) {
      return new HmacKeysListOption(StorageRpc.RequestOption.PROJECT_ID, projectIdentifier);
    }
  }

  /** Class for specifying createHmacKey options */
  class HmacKeyCreateOption extends Option {
    private HmacKeyCreateOption(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyCreateOption userProject(String requesterProject) {
      return new HmacKeyCreateOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static HmacKeyCreateOption projectId(String projectIdentifier) {
      return new HmacKeyCreateOption(StorageRpc.RequestOption.PROJECT_ID, projectIdentifier);
    }
  }

  /** Class for specifying getHmacKey options */
  class GetHmacKeyRequestOption extends Option {
    private GetHmacKeyRequestOption(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static GetHmacKeyRequestOption userProject(String requesterProject) {
      return new GetHmacKeyRequestOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option to specify the Project ID for this request. If not specified, defaults to
     * Application Default Credentials.
     */
    public static GetHmacKeyRequestOption projectId(String projectIdentifier) {
      return new GetHmacKeyRequestOption(StorageRpc.RequestOption.PROJECT_ID, projectIdentifier);
    }
  }

  /** Class for specifying deleteHmacKey options */
  class HmacKeyDeletionOption extends Option {
    private HmacKeyDeletionOption(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyDeletionOption userProject(String requesterProject) {
      return new HmacKeyDeletionOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }
  }

  /** Class for specifying updateHmacKey options */
  class HmacKeyUpdateOption extends Option {
    private HmacKeyUpdateOption(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option to specify the project to be billed for this request. Required for
     * Requester Pays buckets.
     */
    public static HmacKeyUpdateOption userProject(String requesterProject) {
      return new HmacKeyUpdateOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }
  }

  /** Class for specifying bucket get options. */
  class GetBucketOption extends Option {

    private static final long CLASS_VERSION_UID = 1901844869484087395L;

    private GetBucketOption(StorageRpc.RequestOption requestOption, long metaGen) {
      super(requestOption, metaGen);
    }

    private GetBucketOption(StorageRpc.RequestOption requestOption, String payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option for bucket's metageneration match. If this option is used the request will
     * fail if bucket's metageneration does not match the provided getValue.
     */
    public static GetBucketOption ifMetagenerationMatch(long metaGen) {
      return new GetBucketOption(StorageRpc.RequestOption.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for bucket's metageneration mismatch. If this option is used the request
     * will fail if bucket's metageneration matches the provided getValue.
     */
    public static GetBucketOption ifMetagenerationNotMatch(long metaGen) {
      return new GetBucketOption(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static GetBucketOption withUserProject(String requesterProject) {
      return new GetBucketOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option to specify the bucket's withFields to be returned by the RPC call. If this
     * option is not provided all bucket's withFields are returned. {@code GetBucketOption.withFields}) can
     * be used to specify only the withFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static GetBucketOption withFields(BucketFields... fieldNames) {
      return new GetBucketOption(
          StorageRpc.RequestOption.FIELDS, Helper.selector(BucketFields.MANDATORY_SELECTORS, fieldNames));
    }
  }

  /** Class for specifying blob target options. */
  class BlobTargetOptions extends Option {

    private static final long CLASS_VERSION_UID = 214616862061934846L;

    private BlobTargetOptions(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    private BlobTargetOptions(StorageRpc.RequestOption requestOption) {
      this(requestOption, null);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobTargetOptions withPredefinedAcl(PredefinedAccessControlList accessControl) {
      return new BlobTargetOptions(StorageRpc.RequestOption.PREDEFINED_ACL, accessControl.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobTargetOptions ifNotExists() {
      return new BlobTargetOptions(StorageRpc.RequestOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static BlobTargetOptions ifGenerationMatch() {
      return new BlobTargetOptions(StorageRpc.RequestOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static BlobTargetOptions ifGenerationNotMatch() {
      return new BlobTargetOptions(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobTargetOptions ifMetagenerationMatch() {
      return new BlobTargetOptions(StorageRpc.RequestOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobTargetOptions ifMetagenerationNotMatch() {
      return new BlobTargetOptions(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data disabledGzipContent. If this option is used, the request
     * will create a blob with disableGzipCompression; at present, this is only for upload.
     */
    public static BlobTargetOptions disableGzipCompression() {
      return new BlobTargetOptions(StorageRpc.RequestOption.IF_DISABLE_GZIP_CONTENT, true);
    }

    /**
     * Returns an option for detecting content type. If this option is used, the content type is
     * detected from the blob name if not explicitly set. This option is on the client side only, it
     * does not appear in a RPC call.
     */
    public static BlobTargetOptions enableContentTypeDetection() {
      return new BlobTargetOptions(StorageRpc.RequestOption.DETECT_CONTENT_TYPE, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobTargetOptions customerSuppliedKey(Key encryptionMaterial) {
      String base64EncodedString = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobTargetOptions(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, base64EncodedString);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobTargetOptions setUserProject(String requesterProject) {
      return new BlobTargetOptions(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobTargetOptions customerSuppliedKey(String encryptionMaterial) {
      return new BlobTargetOptions(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }

    /** Returns an option to set a customer-managed key for server-side encryption from the blob. */
    public static BlobTargetOptions kmsKey(String kmsResourceName) {
      return new BlobTargetOptions(StorageRpc.RequestOption.KMS_KEY_NAME, kmsResourceName);
    }

    static Tuple<BlobInfo, BlobTargetOptions[]> toTargetOptions(BlobInfo blobMetadata, BlobWriteOptions... writeOptions) {
      BlobInfo.Builder metadataBuilder = blobMetadata.toBuilder().setCrc32c(null).setMd5(null);
      List<BlobTargetOptions> destinationOptions = Lists.newArrayListWithCapacity(writeOptions.length);
      for (BlobWriteOptions requestParam : writeOptions) {
        switch (requestParam.requestParam) {
          case IF_CRC32C_MATCH:
            metadataBuilder.setCrc32c(blobMetadata.getCrc32c());
            break;
          case IF_MD5_MATCH:
            metadataBuilder.setMd5(blobMetadata.getMd5());
            break;
          default:
            destinationOptions.add(requestParam.toBlobTargetOptions());
            break;
        }
      }
      return Tuple.of(
          metadataBuilder.build(), destinationOptions.toArray(new BlobTargetOptions[destinationOptions.size()]));
    }
  }

  /** Class for specifying blob write options. */
  class BlobWriteOptions implements Serializable {

    private static final long CLASS_VERSION_UID = -3880421670966224580L;

    private final ObjectOption requestParam;
    private final Object payloadObject;

    enum ObjectOption {
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

      StorageRpc.RequestOption toRequestOption() {
        return StorageRpc.RequestOption.valueOf(this.name());
      }
    }

    BlobTargetOptions toBlobTargetOptions() {
      return new BlobTargetOptions(this.requestParam.toRequestOption(), this.payloadObject);
    }

    private BlobWriteOptions(ObjectOption requestParam, Object payloadObject) {
      this.requestParam = requestParam;
      this.payloadObject = payloadObject;
    }

    private BlobWriteOptions(ObjectOption requestParam) {
      this(requestParam, null);
    }

    @Override
    public int hashCode() {
      return Objects.hash(requestParam, payloadObject);
    }

    @Override
    public boolean equals(Object compareObject) {
      if (compareObject == null) {
        return false;
      }
      if (!(compareObject instanceof BlobWriteOptions)) {
        return false;
      }
      final BlobWriteOptions otherOptions = (BlobWriteOptions) compareObject;
      return this.requestParam == otherOptions.requestParam && Objects.equals(this.payloadObject, otherOptions.payloadObject);
    }

    /** Returns an option for specifying blob's predefined ACL configuration. */
    public static BlobWriteOptions setPredefinedAcl(PredefinedAccessControlList accessControl) {
      return new BlobWriteOptions(ObjectOption.PREDEFINED_ACL, accessControl.getEntry());
    }

    /**
     * Returns an option that causes an operation to succeed only if the target blob does not exist.
     */
    public static BlobWriteOptions ifNotExists() {
      return new BlobWriteOptions(ObjectOption.IF_GENERATION_MATCH, 0L);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if generation does not match.
     */
    public static BlobWriteOptions ifGenerationMatch() {
      return new BlobWriteOptions(ObjectOption.IF_GENERATION_MATCH);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if generation matches.
     */
    public static BlobWriteOptions ifGenerationNotMatch() {
      return new BlobWriteOptions(ObjectOption.IF_GENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if metageneration does not match.
     */
    public static BlobWriteOptions ifMetagenerationMatch() {
      return new BlobWriteOptions(ObjectOption.IF_METAGENERATION_MATCH);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if metageneration matches.
     */
    public static BlobWriteOptions ifMetagenerationNotMatch() {
      return new BlobWriteOptions(ObjectOption.IF_METAGENERATION_NOT_MATCH);
    }

    /**
     * Returns an option for blob's data MD5 hash match. If this option is used the request will
     * fail if blobs' data MD5 hash does not match.
     */
    public static BlobWriteOptions ifMd5Match() {
      return new BlobWriteOptions(ObjectOption.IF_MD5_MATCH, true);
    }

    /**
     * Returns an option for blob's data CRC32C checksum match. If this option is used the request
     * will fail if blobs' data CRC32C checksum does not match.
     */
    public static BlobWriteOptions ifCrc32cMatch() {
      return new BlobWriteOptions(ObjectOption.IF_CRC32C_MATCH, true);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobWriteOptions getEncryptionKey(Key encryptionMaterial) {
      String base64EncodedString = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobWriteOptions(ObjectOption.CUSTOMER_SUPPLIED_KEY, base64EncodedString);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobWriteOptions getEncryptionKey(String encryptionMaterial) {
      return new BlobWriteOptions(ObjectOption.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }

    /**
     * Returns an option to set a customer-managed KMS key for server-side encryption from the blob.
     *
     * @param kmsResourceName the KMS key resource id
     */
    public static BlobWriteOptions withKmsKeyName(String kmsResourceName) {
      return new BlobWriteOptions(ObjectOption.KMS_KEY_NAME, kmsResourceName);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobWriteOptions withUserProject(String requesterProject) {
      return new BlobWriteOptions(ObjectOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option that signals automatic gzip compression should not be performed en route to
     * the bucket.
     */
    public static BlobWriteOptions disableGzipCompression() {
      return new BlobWriteOptions(ObjectOption.IF_DISABLE_GZIP_CONTENT, true);
    }

    /**
     * Returns an option for detecting content type. If this option is used, the content type is
     * detected from the blob name if not explicitly set. This option is on the client side only, it
     * does not appear in a RPC call.
     */
    public static BlobWriteOptions enableContentTypeDetection() {
      return new BlobWriteOptions(ObjectOption.DETECT_CONTENT_TYPE, true);
    }
  }

  /** Class for specifying blob source options. */
  class BlobReadOption extends Option {

    private static final long CLASS_VERSION_UID = -3712768261070182991L;

    private BlobReadOption(StorageRpc.RequestOption requestOption, Object payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation getValue to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobReadOption ifGenerationMatch() {
      return new BlobReadOption(StorageRpc.RequestOption.IF_GENERATION_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided getValue.
     */
    public static BlobReadOption ifGenerationMatch(long genId) {
      return new BlobReadOption(StorageRpc.RequestOption.IF_GENERATION_MATCH, genId);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation getValue to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobReadOption generationNotMatch() {
      return new BlobReadOption(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH, null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided getValue.
     */
    public static BlobReadOption ifGenerationNotMatch(long genId) {
      return new BlobReadOption(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH, genId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided getValue.
     */
    public static BlobReadOption ifMetagenerationMatch(long metaGen) {
      return new BlobReadOption(StorageRpc.RequestOption.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided getValue.
     */
    public static BlobReadOption ifMetagenerationNotMatch(long metaGen) {
      return new BlobReadOption(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     */
    public static BlobReadOption customerSuppliedKey(Key encryptionMaterial) {
      String base64EncodedString = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobReadOption(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, base64EncodedString);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side encryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobReadOption customerSuppliedKey(String encryptionMaterial) {
      return new BlobReadOption(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobReadOption withUserProject(String requesterProject) {
      return new BlobReadOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }
  }

  /** Class for specifying blob get options. */
  class BlobFetchOption extends Option {

    private static final long CLASS_VERSION_UID = 803817709703661480L;

    private BlobFetchOption(StorageRpc.RequestOption requestOption, Long payloadObject) {
      super(requestOption, payloadObject);
    }

    private BlobFetchOption(StorageRpc.RequestOption requestOption, String payloadObject) {
      super(requestOption, payloadObject);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match. The generation getValue to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobFetchOption ifGenerationMatch() {
      return new BlobFetchOption(StorageRpc.RequestOption.IF_GENERATION_MATCH, (Long) null);
    }

    /**
     * Returns an option for blob's data generation match. If this option is used the request will
     * fail if blob's generation does not match the provided getValue.
     */
    public static BlobFetchOption ifGenerationMatch(long genId) {
      return new BlobFetchOption(StorageRpc.RequestOption.IF_GENERATION_MATCH, genId);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches. The generation getValue to compare with the actual
     * blob's generation is taken from a source {@link BlobId} object. When this option is passed to
     * a {@link Storage} method and {@link BlobId#getGeneration()} is {@code null} or no {@link
     * BlobId} is provided an exception is thrown.
     */
    public static BlobFetchOption generationNotMatch() {
      return new BlobFetchOption(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH, (Long) null);
    }

    /**
     * Returns an option for blob's data generation mismatch. If this option is used the request
     * will fail if blob's generation matches the provided getValue.
     */
    public static BlobFetchOption ifGenerationNotMatch(long genId) {
      return new BlobFetchOption(StorageRpc.RequestOption.IF_GENERATION_NOT_MATCH, genId);
    }

    /**
     * Returns an option for blob's metageneration match. If this option is used the request will
     * fail if blob's metageneration does not match the provided getValue.
     */
    public static BlobFetchOption matchMetageneration(long metaGen) {
      return new BlobFetchOption(StorageRpc.RequestOption.IF_METAGENERATION_MATCH, metaGen);
    }

    /**
     * Returns an option for blob's metageneration mismatch. If this option is used the request will
     * fail if blob's metageneration matches the provided getValue.
     */
    public static BlobFetchOption ifMetagenerationNotMatch(long metaGen) {
      return new BlobFetchOption(StorageRpc.RequestOption.IF_METAGENERATION_NOT_MATCH, metaGen);
    }

    /**
     * Returns an option to specify the blob's withFields to be returned by the RPC call. If this option
     * is not provided all blob's withFields are returned. {@code BlobFetchOption.withFields}) can be used to
     * specify only the withFields from interest. StorageObject name and bucket are always returned, even if not
     * specified.
     */
    public static BlobFetchOption withFields(BlobMetadataField... fieldNames) {
      return new BlobFetchOption(
          StorageRpc.RequestOption.FIELDS, Helper.selector(BlobMetadataField.MANDATORY_SELECTORS, fieldNames));
    }

    /**
     * Returns an option for blob's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BlobFetchOption withUserProject(String requesterProject) {
      return new BlobFetchOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     */
    public static BlobFetchOption decryptionKeyOption(Key encryptionMaterial) {
      String base64EncodedString = BaseEncoding.base64().encode(encryptionMaterial.getEncoded());
      return new BlobFetchOption(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, base64EncodedString);
    }

    /**
     * Returns an option to set a customer-supplied AES256 key for server-side decryption from the
     * blob.
     *
     * @param encryptionMaterial the AES256 encoded in base64
     */
    public static BlobFetchOption decryptionKeyOption(String encryptionMaterial) {
      return new BlobFetchOption(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, encryptionMaterial);
    }
  }

  /** Class for specifying bucket list options. */
  class BucketListOptions extends Option {

    private static final long CLASS_VERSION_UID = 8754017079673290353L;

    private BucketListOptions(StorageRpc.RequestOption requestParam, Object payloadObject) {
      super(requestParam, payloadObject);
    }

    /** Returns an option to specify the maximum number from buckets returned per page. */
    public static BucketListOptions maxResults(long maxItems) {
      return new BucketListOptions(StorageRpc.RequestOption.MAX_RESULTS, maxItems);
    }

    /** Returns an option to specify the page token from which to start listing buckets. */
    public static BucketListOptions pageToken(String continuationToken) {
      return new BucketListOptions(StorageRpc.RequestOption.PAGE_TOKEN, continuationToken);
    }

    /**
     * Returns an option to set a withPrefix to filter results to buckets whose names begin with this
     * withPrefix.
     */
    public static BucketListOptions withPrefix(String namePrefix) {
      return new BucketListOptions(StorageRpc.RequestOption.PREFIX, namePrefix);
    }

    /**
     * Returns an option for bucket's billing user project. This option is only used by the buckets
     * with 'requester_pays' flag.
     */
    public static BucketListOptions withUserProject(String requesterProject) {
      return new BucketListOptions(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * Returns an option to specify the bucket's withFields to be returned by the RPC call. If this
     * option is not provided all bucket's withFields are returned. {@code BucketListOptions.withFields}) can
     * be used to specify only the withFields from interest. Bucket name is always returned, even if not
     * specified.
     */
    public static BucketListOptions fieldNames(BucketFields... fieldNames) {
      return new BucketListOptions(
          StorageRpc.RequestOption.FIELDS,
          Helper.listSelector("items", BucketFields.MANDATORY_SELECTORS, fieldNames));
    }
  }

  /** Class for specifying blob list options. */
  class ListBlobsOption extends Option {

    private static final String[] ROOT_FIELDS = {"prefixes"};
    private static final long CLASS_VERSION_UID = 9083383524788661294L;

    private ListBlobsOption(StorageRpc.RequestOption requestParam, Object payloadObject) {
      super(requestParam, payloadObject);
    }

    /** Returns an option to specify the maximum number from blobs returned per page. */
    public static ListBlobsOption maxResults(long maxItems) {
      return new ListBlobsOption(StorageRpc.RequestOption.MAX_RESULTS, maxItems);
    }

    /** Returns an option to specify the page token from which to start listing blobs. */
    public static ListBlobsOption pageToken(String continuationToken) {
      return new ListBlobsOption(StorageRpc.RequestOption.PAGE_TOKEN, continuationToken);
    }

    /**
     * Returns an option to set a withPrefix to filter results to blobs whose names begin with this
     * withPrefix.
     */
    public static ListBlobsOption withPrefix(String namePrefix) {
      return new ListBlobsOption(StorageRpc.RequestOption.PREFIX, namePrefix);
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
    public static ListBlobsOption currentDirectoryOption() {
      return new ListBlobsOption(StorageRpc.RequestOption.DELIMITER, true);
    }

    /**
     * Returns an option to set a withDelimiter.
     *
     * @param delimiter generally '/' is the one used most often, but you can used other delimiters
     *     as well.
     */
    public static ListBlobsOption withDelimiter(String delimiter) {
      return new ListBlobsOption(StorageRpc.RequestOption.DELIMITER, delimiter);
    }

    /**
     * Returns an option to set a startingOffset to filter results to objects whose names are
     * lexicographically equal to or after startingOffset. If withEndOffset is also set, the objects listed
     * have names between startingOffset (inclusive) and withEndOffset (exclusive).
     *
     * @param startOffset startingOffset to filter the results
     */
    public static ListBlobsOption startingOffset(String startOffset) {
      return new ListBlobsOption(StorageRpc.RequestOption.START_OFF_SET, startOffset);
    }

    /**
     * Returns an option to set a withEndOffset to filter results to objects whose names are
     * lexicographically before withEndOffset. If startingOffset is also set, the objects listed have names
     * between startingOffset (inclusive) and withEndOffset (exclusive).
     *
     * @param endOffset withEndOffset to filter the results
     */
    public static ListBlobsOption withEndOffset(String endOffset) {
      return new ListBlobsOption(StorageRpc.RequestOption.END_OFF_SET, endOffset);
    }

    /**
     * Returns an option to define the billing user project. This option is required by buckets with
     * `requester_pays` flag enabled to assign operation costs.
     *
     * @param requesterProject projectId from the billing user project.
     */
    public static ListBlobsOption withUserProject(String requesterProject) {
      return new ListBlobsOption(StorageRpc.RequestOption.USER_PROJECT, requesterProject);
    }

    /**
     * If set to {@code true}, lists all includeVersions from a blob. The default is {@code false}.
     *
     * @see <a href="https://cloud.google.com/storage/docs/object-versioning">Object Versioning</a>
     */
    public static ListBlobsOption includeVersions(boolean versions) {
      return new ListBlobsOption(StorageRpc.RequestOption.VERSIONS, versions);
    }

    /**
     * Returns an option to specify the blob's withFields to be returned by the RPC call. If this option
     * is not provided all blob's withFields are returned. {@code ListBlobsOption.withFields}) can be used to
     * specify only the withFields from interest. StorageObject name and bucket are always returned, even if not
     * specified.
     */
    public static ListBlobsOption withFields(BlobMetadataField... fieldNames) {
      return new ListBlobsOption(
          StorageRpc.RequestOption.FIELDS,
          Helper.listSelector(ROOT_FIELDS, "items", BlobMetadataField.MANDATORY_SELECTORS, fieldNames));
    }
  }

  /** Class for specifying Post Policy V4 options. * */
  class PostPolicyV4Parameter implements Serializable {
    private static final long CLASS_VERSION_UID = 8150867146534084543L;
    private final StorageOption requestParam;
    private final Object payloadObject;

    enum StorageOption {
      PATH_STYLE,
      VIRTUAL_HOSTED_STYLE,
      BUCKET_BOUND_HOST_NAME,
      SERVICE_ACCOUNT_CRED
    }

    private PostPolicyV4Parameter(StorageOption requestParam, Object payloadObject) {
      this.requestParam = requestParam;
      this.payloadObject = payloadObject;
    }

    StorageOption getOption() {
      return requestParam;
    }

    Object getValue() {
      return payloadObject;
    }

    /**
     * Provides a service account signer to sign the policy. If not provided an attempt is made to
     * get it from the environment.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication#service_accounts">Service
     *     Accounts</a>
     */
    public static PostPolicyV4Parameter withSigner(ServiceAccountSigner serviceSigner) {
      return new PostPolicyV4Parameter(StorageOption.SERVICE_ACCOUNT_CRED, serviceSigner);
    }

    /**
     * Use a virtual hosted-style hostname, which adds the bucket into the host portion from the URI
     * rather than the path, e.g. 'https://mybucket.storage.googleapis.com/...'. The bucket name is
     * obtained from the resource passed in.
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints">Request Endpoints</a>
     */
    public static PostPolicyV4Parameter withVirtualHostedStyleEnabled() {
      return new PostPolicyV4Parameter(StorageOption.VIRTUAL_HOSTED_STYLE, "");
    }

    /**
     * Generates a path-style URL, which places the bucket name in the path portion from the URL
     * instead from in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. Note that this
     * cannot be used alongside {@code withVirtualHostedStyleEnabled()}. Virtual hosted-style URLs, which
     * can be used via the {@code withVirtualHostedStyleEnabled()} method, should generally be preferred
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
     * cannot be used alongside {@code withVirtualHostedStyleEnabled()} or {@code usePathStyle()}. This
     * method signature uses HTTP for the URI scheme, and is equivalent to calling {@code
     * withBucketBoundHostName("...", UrlScheme.HTTP).}
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static PostPolicyV4Parameter withBucketBoundHostname(String bucketHostname) {
      return withBucketBoundHostName(bucketHostname, UrlScheme.HTTP);
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code withVirtualHostedStyleEnabled()} or {@code usePathStyle()}. The
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
            String bucketHostname, UrlScheme uriProtocol) {
      return new PostPolicyV4Parameter(
          StorageOption.BUCKET_BOUND_HOST_NAME,
          uriProtocol.getScheme() + "://" + bucketHostname);
    }
  }

  /** Class for specifying signed URL options. */
  class UrlSigningOption implements Serializable {

    private static final long CLASS_VERSION_UID = 7850569877451099267L;

    private final RequestOption requestParam;
    private final Object payloadObject;

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

    enum SignatureProtocolVersion {
      V2,
      V4
    }

    private UrlSigningOption(RequestOption requestParam, Object payloadObject) {
      this.requestParam = requestParam;
      this.payloadObject = payloadObject;
    }

    RequestOption getOption() {
      return requestParam;
    }

    Object getValue() {
      return payloadObject;
    }

    /**
     * The HTTP method to be used with the signed URL. If this method is not called, defaults to
     * GET.
     */
    public static UrlSigningOption requestMethod(HttpMethod requestMethod) {
      return new UrlSigningOption(RequestOption.HTTP_METHOD, requestMethod);
    }

    /**
     * Use it if signature should include the blob's content-type. When used, users from the signed
     * URL should include the blob's content-type with their request. If using this URL from a
     * browser, you must include a content type that matches what the browser will send.
     */
    public static UrlSigningOption setContentType() {
      return new UrlSigningOption(RequestOption.CONTENT_TYPE, true);
    }

    /**
     * Use it if signature should include the blob's md5. When used, users from the signed URL should
     * include the blob's md5 with their request.
     */
    public static UrlSigningOption withMD5() {
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
      return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SignatureProtocolVersion.V2);
    }

    /**
     * Use if signature version should be V4. Note that V4 Signed URLs can't have an expiration
     * longer than 7 days. V2 will be the default if neither this or {@code withSignatureV2()} is
     * called.
     */
    public static UrlSigningOption withSignatureV4() {
      return new UrlSigningOption(RequestOption.SIGNATURE_VERSION, SignatureProtocolVersion.V4);
    }

    /**
     * Provides a service account signer to sign the URL. If not provided an attempt is made to get
     * it from the environment.
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
     * environment or sending requests through VPC). If using this with the {@code
     * withVirtualHostedStyleEnabled()} method, you should omit the bucket name from the hostname, as it
     * automatically gets prepended to the hostname for virtual hosted-style URLs.
     */
    public static UrlSigningOption withHostname(String serverHost) {
      return new UrlSigningOption(RequestOption.HOST_NAME, serverHost);
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
    public static UrlSigningOption virtualHostedStyle() {
      return new UrlSigningOption(RequestOption.VIRTUAL_HOSTED_STYLE, "");
    }

    /**
     * Generates a path-style URL, which places the bucket name in the path portion from the URL
     * instead from in the hostname, e.g 'https://storage.googleapis.com/mybucket/...'. This cannot be
     * used alongside {@code withVirtualHostedStyleEnabled()}. Virtual hosted-style URLs, which can be used
     * via the {@code withVirtualHostedStyleEnabled()} method, should generally be preferred instead from
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
     * used alongside {@code withVirtualHostedStyleEnabled()} or {@code usePathStyle()}. This method
     * signature uses HTTP for the URI scheme, and is equivalent to calling {@code
     * withBucketBoundHostName("...", UrlScheme.HTTP).}
     *
     * @see <a href="https://cloud.google.com/storage/docs/request-endpoints#cname">CNAME
     *     Redirects</a>
     * @see <a
     *     href="https://cloud.google.com/load-balancing/docs/https/adding-backend-buckets-to-load-balancers">
     *     GCLB Redirects</a>
     */
    public static UrlSigningOption withBucketBoundHostname(String bucketHostname) {
      return withBucketBoundHostName(bucketHostname, UrlScheme.HTTP);
    }

    /**
     * Use a bucket-bound hostname, which replaces the storage.googleapis.com host with the name from
     * a CNAME bucket, e.g. a bucket named 'gcs-subdomain.my.domain.tld', or a Google Cloud Load
     * Balancer which routes to a bucket you own, e.g. 'my-load-balancer-domain.tld'. Note that this
     * cannot be used alongside {@code withVirtualHostedStyleEnabled()} or {@code usePathStyle()}. The
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
            String bucketHostname, UrlScheme uriProtocol) {
      return new UrlSigningOption(
          RequestOption.BUCKET_BOUND_HOST_NAME, uriProtocol.getScheme() + "://" + bucketHostname);
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
   * A class to contain all information needed for a Google Cloud Storage Compose operation.
   *
   * @see <a href="https://cloud.google.com/storage/docs/composite-objects#_Compose">Compose
   *     Operation</a>
   */
  class ComposeBlobRequest implements Serializable {

    private static final long CLASS_VERSION_UID = -7385681353748590911L;

    private final List<SourceObject> sourceObjects;
    private final BlobInfo destinationBlob;
    private final List<BlobTargetOptions> destinationOptions;

    /** Class for Compose source blobs. */
    public static class SourceObject implements Serializable {

      private static final long CLASS_VERSION_UID = 4094962795951990439L;

      final String objectName;
      final Long genId;

      SourceObject(String objectName) {
        this(objectName, null);
      }

      SourceObject(String objectName, Long genId) {
        this.objectName = objectName;
        this.genId = genId;
      }

      public String getName() {
        return objectName;
      }

      public Long getGeneration() {
        return genId;
      }
    }

    public static class TargetBuilder {

      private final List<SourceObject> sourceObjects = new LinkedList<>();
      private final Set<BlobTargetOptions> destinationOptions = new LinkedHashSet<>();
      private BlobInfo destinationBlob;

      /** Add source blobs for compose operation. */
      public TargetBuilder addSources(Iterable<String> blobList) {
        for (String blobName : blobList) {
          sourceObjects.add(new SourceObject(blobName));
        }
        return this;
      }

      /** Add source blobs for compose operation. */
      public TargetBuilder addSources(String... blobList) {
        return addSources(Arrays.asList(blobList));
      }

      /** Add a source with a specific generation to match. */
      public TargetBuilder addSources(String blobName, long genId) {
        sourceObjects.add(new SourceObject(blobName, genId));
        return this;
      }

      /** Sets compose operation's target blob. */
      public TargetBuilder setTarget(BlobInfo destinationBlob) {
        this.destinationBlob = destinationBlob;
        return this;
      }

      /** Sets compose operation's target blob options. */
      public TargetBuilder setTargetOptions(BlobTargetOptions... writeOptions) {
        Collections.addAll(destinationOptions, writeOptions);
        return this;
      }

      /** Sets compose operation's target blob options. */
      public TargetBuilder setTargetOptions(Iterable<BlobTargetOptions> writeOptions) {
        Iterables.addAll(destinationOptions, writeOptions);
        return this;
      }

      /** Creates a {@code ComposeBlobRequest} object. */
      public ComposeBlobRequest buildRequest() {
        checkArgument(!sourceObjects.isEmpty());
        checkNotNull(destinationBlob);
        return new ComposeBlobRequest(this);
      }
    }

    private ComposeBlobRequest(TargetBuilder requestBuilder) {
      sourceObjects = ImmutableList.copyOf(requestBuilder.sourceObjects);
      destinationBlob = requestBuilder.destinationBlob;
      destinationOptions = ImmutableList.copyOf(requestBuilder.destinationOptions);
    }

    /** Returns compose operation's source blobs. */
    public List<SourceObject> getSourceBlobs() {
      return sourceObjects;
    }

    /** Returns compose operation's target blob. */
    public BlobInfo getTarget() {
      return destinationBlob;
    }

    /** Returns compose operation's target blob's options. */
    public List<BlobTargetOptions> getTargetOptions() {
      return destinationOptions;
    }

    /**
     * Creates a {@code ComposeBlobRequest} object.
     *
     * @param sourceList source blobs names
     * @param destinationBlob target blob
     */
    public static ComposeBlobRequest from(Iterable<String> sourceList, BlobInfo destinationBlob) {
      return newTargetBuilder().setTarget(destinationBlob).addSources(sourceList).buildRequest();
    }

    /**
     * Creates a {@code ComposeBlobRequest} object.
     *
     * @param bucketName name from the bucket where the compose operation takes place
     * @param sourceList source blobs names
     * @param destinationBlob target blob name
     */
    public static ComposeBlobRequest of(String bucketName, Iterable<String> sourceList, String destinationBlob) {
      return from(sourceList, BlobInfo.newBuilder(BlobId.of(bucketName, destinationBlob)).build());
    }

    /** Returns a {@code ComposeBlobRequest} builder. */
    public static TargetBuilder newTargetBuilder() {
      return new TargetBuilder();
    }
  }

  /** A class to contain all information needed for a Google Cloud Storage Copy operation. */
  class CopyOperationRequest implements Serializable {

    private static final long CLASS_VERSION_UID = -4498650529476219937L;

    private final BlobId sourceBlobId;
    private final List<BlobReadOption> readOptions;
    private final boolean replaceMetadata;
    private final BlobInfo destinationBlob;
    private final List<BlobTargetOptions> destinationOptions;
    private final Long mbPerChunk;

    public static class CopyJobBuilder {

      private final Set<BlobReadOption> readOptions = new LinkedHashSet<>();
      private final Set<BlobTargetOptions> destinationOptions = new LinkedHashSet<>();
      private BlobId sourceBlobId;
      private boolean replaceMetadata;
      private BlobInfo destinationBlob;
      private Long mbPerChunk;

      /**
       * Sets the blob to copy given bucket and blob name.
       *
       * @return the builder
       */
      public CopyJobBuilder setSource(String bucketName, String blobName) {
        this.sourceBlobId = BlobId.of(bucketName, blobName);
        return this;
      }

      /**
       * Sets the blob to copy given a {@link BlobId}.
       *
       * @return the builder
       */
      public CopyJobBuilder setSource(BlobId sourceBlobId) {
        this.sourceBlobId = sourceBlobId;
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyJobBuilder setSourceOptions(BlobReadOption... writeOptions) {
        Collections.addAll(readOptions, writeOptions);
        return this;
      }

      /**
       * Sets blob's source options.
       *
       * @return the builder
       */
      public CopyJobBuilder setSourceOptions(Iterable<BlobReadOption> writeOptions) {
        Iterables.addAll(readOptions, writeOptions);
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source.
       *
       * @return the builder
       */
      public CopyJobBuilder setTarget(BlobId destinationId) {
        this.replaceMetadata = false;
        this.destinationBlob = BlobInfo.newBuilder(destinationId).build();
        return this;
      }

      /**
       * Sets the copy target. Target blob information is copied from source, except for those
       * options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyJobBuilder setTarget(BlobId destinationId, BlobTargetOptions... writeOptions) {
        this.replaceMetadata = false;
        this.destinationBlob = BlobInfo.newBuilder(destinationId).build();
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
      public CopyJobBuilder setTarget(BlobInfo destinationBlob, BlobTargetOptions... writeOptions) {
        this.replaceMetadata = true;
        this.destinationBlob = checkNotNull(destinationBlob);
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
      public CopyJobBuilder setTarget(BlobInfo destinationBlob, Iterable<BlobTargetOptions> writeOptions) {
        this.replaceMetadata = true;
        this.destinationBlob = checkNotNull(destinationBlob);
        Iterables.addAll(destinationOptions, writeOptions);
        return this;
      }

      /**
       * Sets the copy target and target options. Target blob information is copied from source,
       * except for those options specified in {@code options}.
       *
       * @return the builder
       */
      public CopyJobBuilder setTarget(BlobId destinationId, Iterable<BlobTargetOptions> writeOptions) {
        this.replaceMetadata = false;
        this.destinationBlob = BlobInfo.newBuilder(destinationId).build();
        Iterables.addAll(destinationOptions, writeOptions);
        return this;
      }

      /**
       * Sets the maximum number from megabytes to copy for each RPC call. This parameter is ignored
       * if source and target blob share the same location and storage class as copy is made with
       * one single RPC.
       *
       * @return the builder
       */
      public CopyJobBuilder setMegabytesCopiedPerChunk(Long mbPerChunk) {
        this.mbPerChunk = mbPerChunk;
        return this;
      }

      /** Creates a {@code CopyOperationRequest} object. */
      public CopyOperationRequest buildCopyOperationRequest() {
        return new CopyOperationRequest(this);
      }
    }

    private CopyOperationRequest(CopyJobBuilder requestBuilder) {
      sourceBlobId = checkNotNull(requestBuilder.sourceBlobId);
      readOptions = ImmutableList.copyOf(requestBuilder.readOptions);
      replaceMetadata = requestBuilder.replaceMetadata;
      destinationBlob = checkNotNull(requestBuilder.destinationBlob);
      destinationOptions = ImmutableList.copyOf(requestBuilder.destinationOptions);
      mbPerChunk = requestBuilder.mbPerChunk;
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
      return destinationBlob;
    }

    /**
     * Returns whether to override the target blob information with {@link #getTarget()}. If {@code
     * true}, the getValue from {@link #getTarget()} is used to replace source blob information (e.g.
     * {@code contentType}, {@code contentLanguage}). Target blob information is set exactly to this
     * getValue, no information is inherited from the source blob. If {@code false}, target blob
     * information is inherited from the source blob.
     */
    public boolean getOverrideInfo() {
      return replaceMetadata;
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
     * @param originBlobName name from the source blob
     * @param destinationBlob a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String originBucket, String originBlobName, BlobInfo destinationBlob) {
      return newCopyJobBuilder().setSource(originBucket, originBlobName).setTarget(destinationBlob).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. {@code target} parameter is used to replace source blob information
     * (e.g. {@code contentType}, {@code contentLanguage}). Target blob information is set exactly
     * to {@code target}, no information is inherited from the source blob.
     *
     * @param originBlobId a {@code BlobId} object for the source blob
     * @param destinationBlob a {@code BlobInfo} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId originBlobId, BlobInfo destinationBlob) {
      return newCopyJobBuilder().setSource(originBlobId).setTarget(destinationBlob).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originBucket name from the bucket containing both the source and the target blob
     * @param originBlobName name from the source blob
     * @param destinationBlobName name from the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String originBucket, String originBlobName, String destinationBlobName) {
      return CopyOperationRequest.newCopyJobBuilder()
          .setSource(originBucket, originBlobName)
          .setTarget(BlobId.of(originBucket, destinationBlobName))
          .buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originBucket name from the bucket containing the source blob
     * @param originBlobName name from the source blob
     * @param destinationBlob a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(String originBucket, String originBlobName, BlobId destinationBlob) {
      return newCopyJobBuilder().setSource(originBucket, originBlobName).setTarget(destinationBlob).buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originBlobId a {@code BlobId} object for the source blob
     * @param destinationBlobName name from the target blob, in the same bucket from the source blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId originBlobId, String destinationBlobName) {
      return CopyOperationRequest.newCopyJobBuilder()
          .setSource(originBlobId)
          .setTarget(BlobId.of(originBlobId.getBucket(), destinationBlobName))
          .buildCopyOperationRequest();
    }

    /**
     * Creates a copy request. Target blob information is copied from source.
     *
     * @param originBlobId a {@code BlobId} object for the source blob
     * @param destinationBlobId a {@code BlobId} object for the target blob
     * @return a copy request
     */
    public static CopyOperationRequest from(BlobId originBlobId, BlobId destinationBlobId) {
      return CopyOperationRequest.newCopyJobBuilder().setSource(originBlobId).setTarget(destinationBlobId).buildCopyOperationRequest();
    }

    /** Creates a builder for {@code CopyOperationRequest} objects. */
    public static CopyJobBuilder newCopyJobBuilder() {
      return new CopyJobBuilder();
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
   * Bucket bucket = storage.create(BucketInfo.newTargetBuilder(bucketName)
   *     // See here for possible values: http://g.co/cloud/storage/docs/storage-classes
   *     .setStorageClass(StorageClass.COLDLINE)
   *     // Possible values: http://g.co/cloud/storage/docs/bucket-locations#location-mr
   *     .setLocation("asia")
   *     .buildRequest());
   * }</pre>
   *
   * @return a complete bucket
   * @throws StorageException upon failure
   */
  Bucket create(BucketInfo bucketMetadata, BucketTargetOptions... writeOptions);

  /**
   * Creates a new blob with no content.
   *
   * <p>Example from creating a blob with no content.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
   * StorageObject blob = storage.create(blobInfo);
   * }</pre>
   *
   * @return a {@code StorageObject} with complete information
   * @throws StorageException upon failure
   */
  StorageObject create(BlobInfo blobMetadata, BlobTargetOptions... writeOptions);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. MD5 and CRC32C hashes from {@code content}
   * are computed and used for validating transferred data. Accepts an optional withUserProject {@link
   * BlobFetchOption} option which defines the project id to assign operational costs. The content
   * type is detected from the blob name if not explicitly set.
   *
   * <p>Example from creating a blob from a byte array:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
   * StorageObject blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8));
   * }</pre>
   *
   * @return a {@code StorageObject} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  StorageObject create(BlobInfo blobMetadata, byte[] byteContent, BlobTargetOptions... writeOptions);

  /**
   * Creates a new blob with the sub array from the given byte array. Direct upload is used to upload
   * {@code content}. For large content, {@link #writer} is recommended as it uses resumable upload.
   * MD5 and CRC32C hashes from {@code content} are computed and used for validating transferred data.
   * Accepts a withUserProject {@link BlobFetchOption} option, which defines the project id to assign
   * operational costs.
   *
   * <p>Example from creating a blob from a byte array:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
   * StorageObject blob = storage.create(blobInfo, "Hello, World!".getBytes(UTF_8), 7, 5);
   * }</pre>
   *
   * @return a {@code StorageObject} with complete information
   * @throws StorageException upon failure
   * @see <a href="https://cloud.google.com/storage/docs/hashes-etags">Hashes and ETags</a>
   */
  StorageObject create(
          BlobInfo blobMetadata, byte[] byteContent, int startOffset, int dataLength, BlobTargetOptions... writeOptions);

  /**
   * Creates a new blob. Direct upload is used to upload {@code content}. For large content, {@link
   * #writer} is recommended as it uses resumable upload. By default any MD5 and CRC32C values in
   * the given {@code blobInfo} are ignored unless requested via the {@code
   * BlobWriteOptions.ifMd5Match} and {@code BlobWriteOptions.ifCrc32cMatch} options. The given input
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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId)
   *     .setContentType("text/plain")
   *     .buildRequest();
   * StorageObject blob = storage.create(blobInfo, content, BlobWriteOptions.customerSuppliedKey(customerSuppliedKey));
   * }</pre>
   *
   * @return a {@code StorageObject} with complete information
   * @throws StorageException upon failure
   */
  @Deprecated
  StorageObject create(BlobInfo blobMetadata, InputStream byteContent, BlobWriteOptions... writeOptions);

  /**
   * Uploads {@code path} to the blob using {@link #writer}. By default any MD5 and CRC32C values in
   * the given {@code blobInfo} are ignored unless requested via the {@link
   * BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#ifCrc32cMatch()} options. Folder upload is
   * not supported.
   *
   * <p>Example from uploading a file:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String fileName = "readme.txt";
   * BlobId blobId = BlobId.from(bucketName, fileName);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
   * storage.createFrom(blobInfo, Paths.get(fileName));
   * }</pre>
   *
   * @param blobMetadata blob to create
   * @param filePath file to upload
   * @param writeOptions blob write options
   * @return a {@code StorageObject} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   * @see #createFrom(BlobInfo, Path, int, BlobWriteOptions...)
   */
  StorageObject createFrom(BlobInfo blobMetadata, Path filePath, BlobWriteOptions... writeOptions) throws IOException;

  /**
   * Uploads {@code path} to the blob using {@link #writer} and {@code bufferSize}. By default any
   * MD5 and CRC32C values in the given {@code blobInfo} are ignored unless requested via the {@link
   * BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#ifCrc32cMatch()} options. Folder upload is
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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("video/webm").buildRequest();
   *
   * int largeBufferSize = 150 * 1024 * 1024;
   * Path file = Paths.get("humongous.file");
   * storage.createFrom(blobInfo, file, largeBufferSize);
   * }</pre>
   *
   * @param blobMetadata blob to create
   * @param filePath file to upload
   * @param readBufferSize size from the buffer I/O operations
   * @param writeOptions blob write options
   * @return a {@code StorageObject} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   */
  StorageObject createFrom(BlobInfo blobMetadata, Path filePath, int readBufferSize, BlobWriteOptions... writeOptions)
      throws IOException;

  /**
   * Reads bytes from an input stream and uploads those bytes to the blob using {@link #writer}. By
   * default any MD5 and CRC32C values in the given {@code blobInfo} are ignored unless requested
   * via the {@link BlobWriteOptions#ifMd5Match()} and {@link BlobWriteOptions#ifCrc32cMatch()} options.
   *
   * <p>Example from uploading data with CRC32C checksum:
   *
   * <pre>{@code
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, world".getBytes(StandardCharsets.UTF_8);
   * Hasher hasher = Hashing.crc32c().newHasher().putBytes(content);
   * String crc32c = BaseEncoding.base64().encode(Ints.toByteArray(hasher.hash().asInt()));
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setCrc32c(crc32c).buildRequest();
   * storage.createFrom(blobInfo, new ByteArrayInputStream(content), Storage.BlobWriteOptions.ifCrc32cMatch());
   * }</pre>
   *
   * @param blobMetadata blob to create
   * @param byteContent input stream to read from
   * @param writeOptions blob write options
   * @return a {@code StorageObject} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   * @see #createFrom(BlobInfo, InputStream, int, BlobWriteOptions...)
   */
  StorageObject createFrom(BlobInfo blobMetadata, InputStream byteContent, BlobWriteOptions... writeOptions)
      throws IOException;

  /**
   * Reads bytes from an input stream and uploads those bytes to the blob using {@link #writer} and
   * {@code bufferSize}. By default any MD5 and CRC32C values in the given {@code blobInfo} are
   * ignored unless requested via the {@link BlobWriteOptions#ifMd5Match()} and {@link
   * BlobWriteOptions#ifCrc32cMatch()} options.
   *
   * <p>{@link #createFrom(BlobInfo, InputStream, BlobWriteOptions...)} )} invokes this method with a
   * buffer size from 15 MiB. Users can pass alternative values. Larger buffer sizes might improve the
   * upload performance but require more memory. This can cause an OutOfMemoryError or add
   * significant garbage collection overhead. Smaller buffer sizes reduce memory consumption, that
   * is noticeable when uploading many objects in parallel. Buffer sizes less than 256 KiB are
   * treated as 256 KiB.
   *
   * @param blobMetadata blob to create
   * @param byteContent input stream to read from
   * @param readBufferSize size from the buffer I/O operations
   * @param writeOptions blob write options
   * @return a {@code StorageObject} with complete information
   * @throws IOException on I/O error
   * @throws StorageException on server side error
   */
  StorageObject createFrom(
          BlobInfo blobMetadata, InputStream byteContent, int readBufferSize, BlobWriteOptions... writeOptions)
      throws IOException;

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link GetBucketOption} option which defines the project id
   * to assign operational costs.
   *
   * <p>Example from getting information on a bucket, only if its metageneration matches a getValue,
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
  Bucket get(String bucketName, GetBucketOption... writeOptions);

  /**
   * Locks bucket retention policy. Requires a local metageneration getValue in the request. Review
   * example below.
   *
   * <p>Accepts an optional withUserProject {@link BucketTargetOptions} option which defines the project
   * id to assign operational costs.
   *
   * <p>Warning: Once a retention policy is locked, it can't be unlocked, removed, or shortened.
   *
   * <p>Example from locking a retention policy on a bucket, only if its local metageneration getValue
   * matches the bucket's service metageneration otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Bucket bucket = storage.get(bucketName, GetBucketOption.withFields(BucketFields.METAGENERATION));
   * storage.lockRetentionPolicy(bucket, BucketTargetOptions.ifMetagenerationMatch());
   * }</pre>
   *
   * @return a {@code Bucket} object from the locked bucket
   * @throws StorageException upon failure
   */
  Bucket lockRetentionPolicy(BucketInfo bucketName, BucketTargetOptions... writeOptions);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link BlobFetchOption} option which defines the project id to
   * assign operational costs.
   *
   * <p>Example from getting information on a blob, only if its metageneration matches a getValue,
   * otherwise a {@link StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * long blobMetageneration = 42;
   * StorageObject blob = storage.get(bucketName, blobName,
   *     BlobFetchOption.ifMetagenerationMatch(blobMetageneration));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  StorageObject get(String bucketName, String blobName, BlobFetchOption... writeOptions);

  /**
   * Returns the requested blob or {@code null} if not found.
   *
   * <p>Accepts an optional withUserProject {@link BlobFetchOption} option which defines the project id to
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
   * StorageObject blob = storage.get(blobId, BlobFetchOption.ifMetagenerationMatch(blobMetageneration));
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
   * StorageObject blob = storage.get(blobId, BlobFetchOption.customerSuppliedKey(blobEncryptionKey));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  StorageObject get(BlobId blobName, BlobFetchOption... writeOptions);

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
  StorageObject get(BlobId blobName);

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
  Page<Bucket> list(BucketListOptions... writeOptions);

  /**
   * Lists the bucket's blobs. If the {@link ListBlobsOption#currentDirectoryOption()} option is provided,
   * results are returned in a directory-like mode.
   *
   * <p>Example from listing blobs in a provided directory.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String directory = "my_directory/";
   * Page<StorageObject> blobs = storage.list(bucketName, ListBlobsOption.currentDirectoryOption(),
   *     ListBlobsOption.withPrefix(directory));
   * Iterator<StorageObject> blobIterator = blobs.iterateAll().iterator();
   * while (blobIterator.hasNext()) {
   *   StorageObject blob = blobIterator.next();
   *   // do something with the blob
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Page<StorageObject> list(String bucketName, ListBlobsOption... writeOptions);

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
   * BucketInfo bucketInfo = BucketInfo.newTargetBuilder(bucketName).setVersioningEnabled(true).buildRequest();
   * Bucket bucket = storage.save(bucketInfo);
   * }</pre>
   *
   * @return the updated bucket
   * @throws StorageException upon failure
   */
  Bucket update(BucketInfo bucketMetadata, BucketTargetOptions... writeOptions);

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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
   * StorageObject blob = storage.create(blobInfo);
   *
   * doSomething();
   *
   * BlobInfo save = blob.toBuilder().setContentType("multipart/form-data").buildRequest();
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
   * @param blobMetadata information to save
   * @param writeOptions preconditions to apply the save
   * @return the updated blob
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  StorageObject update(BlobInfo blobMetadata, BlobTargetOptions... writeOptions);

  /**
   * Updates the properties from the blob. This method issues an RPC request to merge the current blob
   * properties with the properties in the provided {@code blobInfo}. Properties not defined in
   * {@code blobInfo} will not be updated. To unset a blob property this property in {@code
   * blobInfo} should be explicitly set to {@code null}.
   *
   * <p>Bucket or blob's name cannot be changed by this method. If you want to rename the blob or
   * move it to a different bucket use the {@link StorageObject#downloadIntoInternal} and {@link #delete} operations.
   *
   * <p>Property save alters the blob metadata generation and doesn't alter the blob generation.
   *
   * <p>Example from how to save blob's user provided metadata and unset the content type:
   *
   * <pre>{@code
   * Map<String, String> metadataUpdate = new HashMap<>();
   * metadataUpdate.put("keyToAdd", "new getValue");
   * metadataUpdate.put("keyToRemove", null);
   * BlobInfo blobUpdate = BlobInfo.newTargetBuilder(bucketName, blobName)
   *     .setMetadata(metadataUpdate)
   *     .setContentType(null)
   *     .buildRequest();
   * StorageObject blob = storage.save(blobUpdate);
   * }</pre>
   *
   * @param blobMetadata information to save
   * @return the updated blob
   * @throws StorageException upon failure
   * @see <a
   *     href="https://cloud.google.com/storage/docs/json_api/v1/objects/update">https://cloud.google.com/storage/docs/json_api/v1/objects/update</a>
   */
  StorageObject update(BlobInfo blobMetadata);

  /**
   * Deletes the requested bucket.
   *
   * <p>Accepts an optional withUserProject {@link BucketReadOption} option which defines the project
   * id to assign operational costs.
   *
   * <p>Example from deleting a bucket, only if its metageneration matches a getValue, otherwise a {@link
   * StorageException} is thrown.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * long bucketMetageneration = 42;
   * boolean deleted = storage.remove(bucketName,
   *     BucketReadOption.ifMetagenerationMatch(bucketMetageneration));
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
  boolean delete(String bucketName, BucketReadOption... writeOptions);

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
   * boolean deleted = storage.remove(bucketName, blobName,
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
  boolean delete(String bucketName, String blobName, BlobReadOption... writeOptions);

  /**
   * Deletes the requested blob.
   *
   * <p>Accepts an optional withUserProject {@link BlobReadOption} option which defines the project id
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
   * boolean deleted = storage.remove(blobId, BlobSourceOptions.ifGenerationMatch(blobGeneration));
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
  boolean delete(BlobId blobName, BlobReadOption... writeOptions);

  /**
   * Deletes the requested blob.
   *
   * <p>Example from deleting a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * boolean deleted = storage.remove(blobId);
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
  boolean delete(BlobId blobName);

  /**
   * Sends a compose request.
   *
   * <p>Accepts an optional withUserProject {@link BlobTargetOptions} option which defines the project id
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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
   * ComposeBlobRequest request = ComposeBlobRequest.newTargetBuilder()
   *     .setTarget(blobInfo)
   *     .addSources(sourceBlob1)
   *     .addSources(sourceBlob2)
   *     .buildRequest();
   * StorageObject blob = storage.compose(request);
   * }</pre>
   *
   * @return the composed blob
   * @throws StorageException upon failure
   */
  StorageObject compose(ComposeBlobRequest composeRequest);

  /**
   * Sends a copy request. This method copies both blob's data and information. To override source
   * blob's information supply a {@code BlobInfo} to the {@code CopyOperationRequest} using either {@link
   * CopyOperationRequest.CopyJobBuilder#setTarget(BlobInfo, BlobTargetOptions...)} or {@link
   * CopyOperationRequest.CopyJobBuilder#setTarget(BlobInfo, Iterable)}.
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
   *     .buildRequest();
   * StorageObject blob = storage.copy(request).getResult();
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
   *     .buildRequest();
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
   * CopyOperationRequest request = CopyOperationRequest.newTargetBuilder()
   *     .setSource(blobId)
   *     .setSourceOptions(BlobSourceOptions.customerSuppliedKey(oldEncryptionKey))
   *     .setTarget(blobId, BlobTargetOptions.customerSuppliedKey(newEncryptionKey))
   *     .buildRequest();
   * StorageObject blob = storage.copy(request).getResult();
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
  byte[] readAllBytes(String bucketName, String blobName, BlobReadOption... writeOptions);

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
   * String customerSuppliedKey = "my_encryption_key";
   * byte[] content = storage.readAllBytes(
   *     bucketName, blobName, BlobSourceOptions.customerSuppliedKey(customerSuppliedKey));
   * }</pre>
   *
   * @return the blob's content
   * @throws StorageException upon failure
   */
  byte[] readAllBytes(BlobId blobName, BlobReadOption... writeOptions);

  /**
   * Creates a new empty batch for grouping multiple service calls in one underlying RPC call.
   *
   * <p>Example from using a batch request to remove, save and get a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName1 = "my-blob-name1";
   * String blobName2 = "my-blob-name2";
   * StorageBatch batch = storage.batch();
   * BlobId firstBlob = BlobId.from(bucketName, blobName1);
   * BlobId secondBlob = BlobId.from(bucketName, blobName2);
   * batch.remove(firstBlob).notify(new BatchResult.Callback<Boolean, StorageException>() {
   *   public void success(Boolean result) {
   *     // deleted successfully
   *   }
   *
   *   public void error(StorageException exception) {
   *     // remove failed
   *   }
   * });
   * batch.save(BlobInfo.newTargetBuilder(secondBlob).setContentType("text/plain").buildRequest());
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
  ReadChannel reader(String bucketName, String blobName, BlobReadOption... writeOptions);

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
  ReadChannel reader(BlobId blobName, BlobReadOption... writeOptions);

  /**
   * Creates a blob and returns a channel for writing its content. By default any MD5 and CRC32C
   * values in the given {@code blobInfo} are ignored unless requested via the {@code
   * BlobWriteOptions.ifMd5Match} and {@code BlobWriteOptions.ifCrc32cMatch} options.
   *
   * <p>Example from writing a blob's content through a getWriter:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * BlobId blobId = BlobId.from(bucketName, blobName);
   * byte[] content = "Hello, World!".getBytes(UTF_8);
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
   * try (WriteChannel getWriter = storage.getWriter(blobInfo)) {
   *     getWriter.write(ByteBuffer.wrap(content, 0, content.length));
   * } catch (IOException ex) {
   *   // handle exception
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  WriteChannel writer(BlobInfo blobMetadata, BlobWriteOptions... writeOptions);

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
   * BlobInfo blobInfo = BlobInfo.newTargetBuilder(blobId).setContentType("text/plain").buildRequest();
   * URL signedURL = storage.generateSignedUrl(
   *     blobInfo,
   *     1, TimeUnit.HOURS,
   *     Storage.UrlSigningOption.requestMethod(HttpMethod.POST));
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
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildRequest(),
   *     7, TimeUnit.DAYS);
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#withSignatureV4()} option,
   * which enables V4 signing:
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * String blobName = "my-blob-name";
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildRequest(),
   *     7, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.withSignatureV4());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#virtualHostedStyle()}
   * option, which specifies the bucket name in the hostname from the URI, rather than in the path:
   *
   * <pre>{@code
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildRequest(),
   *     1, TimeUnit.DAYS,
   *     Storage.UrlSigningOption.withVirtualHostedStyleEnabled());
   * }</pre>
   *
   * <p>Example from creating a signed URL passing the {@link UrlSigningOption#usePathStyle()} option,
   * which specifies the bucket name in path portion from the URI, rather than in the hostname:
   *
   * <pre>{@code
   * URL signedUrl = storage.generateSignedUrl(
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildRequest(),
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
   *     BlobInfo.newTargetBuilder(bucketName, blobName).buildRequest(),
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
   *     BlobInfo.newTargetBuilder(bucketName, blobName, generation).buildRequest(),
   *     7, TimeUnit.DAYS,
   *     UrlSigningOption.withQueryParameters(ImmutableMap.from("generation", String.valueOf(generation))));
   * }</pre>
   *
   * @param blobMetadata the blob associated with the signed URL
   * @param expiryDuration time until the signed URL expires, expressed in {@code unit}. The finest
   *     granularity supported is 1 second, finer granularities will be truncated
   * @param timeUnit time unit from the {@code duration} parameter
   * @param writeOptions optional URL signing options
   * @throws IllegalStateException if {@link UrlSigningOption#withSigner(ServiceAccountSigner)} was not
   *     used and no implementation from {@link ServiceAccountSigner} was provided to {@link
   *     StorageOptions}
   * @throws IllegalArgumentException if {@code UrlSigningOption.withMD5()} option is used and {@code
   *     blobInfo.md5()} is {@code null}
   * @throws IllegalArgumentException if {@code UrlSigningOption.setContentType()} option is used and
   *     {@code blobInfo.contentType()} is {@code null}
   * @throws SigningException if the attempt to sign the URL failed
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed-URLs</a>
   */
  URL signUrl(BlobInfo blobMetadata, long expiryDuration, TimeUnit timeUnit, UrlSigningOption... writeOptions);

  /**
   * Generates a URL and a map from withFields that can be specified in an HTML form to submit a POST
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
   * PostFieldsV4 withFields = PostFieldsV4.newTargetBuilder().setAcl("public-read").buildRequest();
   * PostConditionsV4 conditions = PostConditionsV4.newTargetBuilder().addContentTypeCondition(ConditionV4Type.MATCHES, "image/jpeg").buildRequest();
   *
   * PostPolicyV4 policy = storage.generateSignedPostPolicyV4(
   *     BlobInfo.newTargetBuilder("my-bucket", "my-object").buildRequest(),
   *     7, TimeUnit.DAYS, withFields, conditions);
   *
   * HttpClient client = HttpClientBuilder.create().buildRequest();
   * HttpPost request = new HttpPost(policy.getUrl());
   * MultipartEntityBuilder builder = MultipartEntityBuilder.create();
   *
   * for (Map.Entry<String, String> entry : policy.getFields().entrySet()) {
   *     builder.addTextBody(entry.getKey(), entry.getValue());
   * }
   * File file = new File("path/to/your/file/to/upload");
   * builder.addBinaryBody("file", new FileInputStream(file), ContentType.APPLICATION_OCTET_STREAM, file.getName());
   * request.setEntity(builder.buildRequest());
   * client.execute(request);
   * }</pre>
   *
   * @param blobMetadata the blob uploaded in the form
   * @param expiryDuration time before expiration
   * @param timeUnit duration time unit
   * @param fieldNames the withFields specified in the form
   * @param postConditions which conditions every upload must satisfy
   * @param expiryDuration how long until the form expires, in milliseconds
   * @param writeOptions optional post policy options
   * @see <a
   *     href="https://cloud.google.com/storage/docs/xml-api/post-object#usage_and_examples">POST
   *     Object</a>
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiryDuration,
      TimeUnit timeUnit,
      PostFieldsV4 fieldNames,
      PostConditionsV4 postConditions,
      PostPolicyV4Parameter... writeOptions);

  /**
   * Generates a presigned post policy without any conditions. Automatically creates required
   * conditions. See full documentation for {@link #generateSignedPostPolicyV4(BlobInfo, long,
   * TimeUnit, PostPolicyV4.PostFieldsV4, PostPolicyV4.PostConditionsV4, PostPolicyV4Parameter...)}.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiryDuration,
      TimeUnit timeUnit,
      PostFieldsV4 fieldNames,
      PostPolicyV4Parameter... writeOptions);

  /**
   * Generates a presigned post policy without any withFields. Automatically creates required withFields.
   * See full documentation for {@link #generateSignedPostPolicyV4(BlobInfo, long, TimeUnit,
   * PostPolicyV4.PostFieldsV4, PostPolicyV4.PostConditionsV4, PostPolicyV4Parameter...)}.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiryDuration,
      TimeUnit timeUnit,
      PostConditionsV4 postConditions,
      PostPolicyV4Parameter... writeOptions);

  /**
   * Generates a presigned post policy without any withFields or conditions. Automatically creates
   * required withFields and conditions. See full documentation for {@link
   * #generateSignedPostPolicyV4(BlobInfo, long, TimeUnit, PostPolicyV4.PostFieldsV4,
   * PostPolicyV4.PostConditionsV4, PostPolicyV4Parameter...)}.
   */
  PostPolicyV4 generateSignedPostPolicyV4(
          BlobInfo blobMetadata, long expiryDuration, TimeUnit timeUnit, PostPolicyV4Parameter... writeOptions);

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
   * @param blobIdList blobs to get
   * @return an immutable list from {@code StorageObject} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageObject> get(BlobId... blobIdList);

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
   * @param blobIdList blobs to get
   * @return an immutable list from {@code StorageObject} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageObject> get(Iterable<BlobId> blobIdList);

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
   *     firstBlob.toBuilder().setContentType("text/plain").buildRequest(),
   *     secondBlob.toBuilder().setContentType("text/plain").buildRequest());
   * }</pre>
   *
   * @param blobInfoList blobs to save
   * @return an immutable list from {@code StorageObject} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageObject> update(BlobInfo... blobInfoList);

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
   * blobs.add(firstBlob.toBuilder().setContentType("text/plain").buildRequest());
   * blobs.add(secondBlob.toBuilder().setContentType("text/plain").buildRequest());
   * List<StorageObject> updatedBlobs = storage.save(blobs);
   * }</pre>
   *
   * @param blobInfoList blobs to save
   * @return an immutable list from {@code StorageObject} objects. If a blob does not exist or access to it has
   *     been denied the corresponding item in the list is {@code null}.
   * @throws StorageException upon failure
   */
  List<StorageObject> update(Iterable<BlobInfo> blobInfoList);

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
   * List<Boolean> deleted = storage.remove(firstBlob, secondBlob);
   * }</pre>
   *
   * @param blobIdList blobs to remove
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
   * List<Boolean> deleted = storage.remove(blobIds);
   * }</pre>
   *
   * @param blobIdList blobs to remove
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
   * BucketReadOption userProjectOption = BucketReadOption.withUserProject("myProject");
   * Acl acl = storage.getAcl(bucketName, new User(userEmail), userProjectOption);
   * }</pre>
   *
   * @param bucketName name from the bucket where the getAcl operation takes place
   * @param aclEntity ACL entity to fetch
   * @param writeOptions extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl getAcl(String bucketName, Entity aclEntity, BucketReadOption... writeOptions);

  /** @see #getAcl(String, Entity, BucketReadOption...) */
  Acl getAcl(String bucketName, Entity aclEntity);

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
   * BucketReadOption withUserProject = BucketReadOption.withUserProject("myProject");
   * boolean deleted = storage.removeAcl(bucketName, User.ofAllAuthenticatedUsers(), withUserProject);
   * }</pre>
   *
   * @param bucketName name from the bucket to remove an ACL from
   * @param aclEntity ACL entity to remove
   * @param writeOptions extra parameters to apply to this operation
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String bucketName, Entity aclEntity, BucketReadOption... writeOptions);

  /** @see #deleteAcl(String, Entity, BucketReadOption...) */
  boolean deleteAcl(String bucketName, Entity aclEntity);

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
   *     BucketReadOption.withUserProject("myProject"));
   * }</pre>
   *
   * @param bucketName name from the bucket for which an ACL should be created
   * @param accessControl ACL to create
   * @param writeOptions extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl createAcl(String bucketName, Acl accessControl, BucketReadOption... writeOptions);

  /** @see #createAcl(String, Acl, BucketReadOption...) */
  Acl createAcl(String bucketName, Acl accessControl);

  /**
   * Updates an ACL entry on the specified bucket.
   *
   * <p>Example from updating a new ACL entry on a bucket.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.setAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER));
   * }</pre>
   *
   * <p>Example from updating a new ACL entry on a requester_pays bucket with a user_project option.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * Acl acl = storage.setAcl(bucketName, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER),
   *     BucketReadOption.withUserProject("myProject"));
   * }</pre>
   *
   * @param bucketName name from the bucket where the setAcl operation takes place
   * @param accessControl ACL to save
   * @param writeOptions extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Acl updateAcl(String bucketName, Acl accessControl, BucketReadOption... writeOptions);

  /** @see #updateAcl(String, Acl, BucketReadOption...) */
  Acl updateAcl(String bucketName, Acl accessControl);

  /**
   * Lists the ACL entries for the provided bucket.
   *
   * <p>Example from listing the ACL entries for a blob.
   *
   * <pre>{@code
   * String bucketName = "my-unique-bucket";
   * List<Acl> acls = storage.getAcls(bucketName);
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
   * List<Acl> acls = storage.getAcls(bucketName, BucketReadOption.withUserProject("myProject"));
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @param bucketName the name from the bucket to list ACLs for
   * @param writeOptions any number from BucketSourceOptions to apply to this operation
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(String bucketName, BucketReadOption... writeOptions);

  /** @see #listAcls(String, BucketReadOption...) */
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
  Acl getDefaultAcl(String bucketName, Entity aclEntity);

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
  boolean deleteDefaultAcl(String bucketName, Entity aclEntity);

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
  Acl createDefaultAcl(String bucketName, Acl accessControl);

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
  Acl updateDefaultAcl(String bucketName, Acl accessControl);

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
  Acl getAcl(BlobId blobName, Entity aclEntity);

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
  boolean deleteAcl(BlobId blobName, Entity aclEntity);

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
  Acl createAcl(BlobId blobName, Acl accessControl);

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
   * Acl acl = storage.setAcl(blobId, Acl.from(User.ofAllAuthenticatedUsers(), Role.OWNER));
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  Acl updateAcl(BlobId blobName, Acl accessControl);

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
   * List<Acl> acls = storage.getAcls(blobId);
   * for (Acl acl : acls) {
   *   // do something with ACL entry
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  List<Acl> listAcls(BlobId blobName);

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
  HmacKey createHmacKey(ServiceAccount signerAccount, HmacKeyCreateOption... writeOptions);

  /**
   * Lists HMAC keys for a given service account. Note this returns {@code HmacKeyMetadata} objects,
   * which do not contain secret keys.
   *
   * <p>Example from listing HMAC keys, specifying project id.
   *
   * <pre>{@code
   * Page<HmacKey.HmacKeyMetadata> metadataPage = storage.listHmacKeys(
   *     Storage.HmacKeysListOption.projectId("my-project-id"));
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
   *     Storage.HmacKeysListOption.serviceAccountEmail(serviceAccountEmail),
   *     Storage.HmacKeysListOption.maximumResults(10L),
   *     Storage.HmacKeysListOption.showDeletedKeys(true));
   * for (HmacKey.HmacKeyMetadata hmacKeyMetadata : metadataPage.getValues()) {
   *     //do something with the metadata
   * }
   * }</pre>
   *
   * @param writeOptions the options to apply to this operation
   * @throws StorageException upon failure
   */
  Page<HmacKeyMetadata> listHmacKeys(HmacKeysListOption... writeOptions);

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
  HmacKeyMetadata getHmacKey(String accessIdentifier, GetHmacKeyRequestOption... writeOptions);

  /**
   * Deletes an HMAC key. Note that only an {@code INACTIVE} key can be deleted. Attempting to
   * remove a key whose {@code HmacKey.HmacKeyState} is anything other than {@code INACTIVE} will
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
  void deleteHmacKey(HmacKeyMetadata hmacMetadata, HmacKeyDeletionOption... writeOptions);

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
      final HmacKey.HmacKeyState targetState,
      HmacKeyUpdateOption... writeOptions);

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
   * @param writeOptions extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy getIamPolicy(String bucketName, BucketReadOption... writeOptions);

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
   *             .buildRequest());
   * }</pre>
   *
   * @param bucketName name from the bucket where the setIamPolicy operation takes place
   * @param iamPolicy policy to be set on the specified bucket
   * @param writeOptions extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  Policy setIamPolicy(String bucketName, Policy iamPolicy, BucketReadOption... writeOptions);

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
   * @param permissionList list from permissions to test on the bucket
   * @param writeOptions extra parameters to apply to this operation
   * @throws StorageException upon failure
   */
  List<Boolean> testIamPermissions(
          String bucketName, List<String> permissionList, BucketReadOption... writeOptions);

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
   * @param projectIdentifier the ID from the project for which the service account should be fetched.
   * @return the service account associated with this project
   * @throws StorageException upon failure
   */
  ServiceAccount getServiceAccount(String projectIdentifier);
}
