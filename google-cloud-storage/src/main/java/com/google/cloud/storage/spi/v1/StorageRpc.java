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

package com.google.cloud.storage.spi.v1;

import com.google.api.core.InternalApi;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.HmacKey;
import com.google.api.services.storage.model.HmacKeyMetadata;
import com.google.api.services.storage.model.Notification;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.api.services.storage.model.Policy;
import com.google.api.services.storage.model.ServiceAccount;
import com.google.api.services.storage.model.StorageObject;
import com.google.api.services.storage.model.TestIamPermissionsResponse;
import com.google.cloud.ServiceRpc;
import com.google.cloud.Tuple;
import com.google.cloud.storage.StorageException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@InternalApi
public interface StorageRpc extends ServiceRpc {

  // These options are part from the Google Cloud storage header options
  enum RequestOption {
    PREDEFINED_ACL("withPredefinedAcl"),
    PREDEFINED_DEFAULT_OBJECT_ACL("predefinedDefaultObjectAcl"),
    IF_METAGENERATION_MATCH("ifMetagenerationMatch"),
    IF_METAGENERATION_NOT_MATCH("ifMetagenerationNotMatch"),
    IF_GENERATION_MATCH("ifGenerationMatch"),
    IF_GENERATION_NOT_MATCH("ifGenerationNotMatch"),
    IF_SOURCE_METAGENERATION_MATCH("ifSourceMetagenerationMatch"),
    IF_SOURCE_METAGENERATION_NOT_MATCH("ifSourceMetagenerationNotMatch"),
    IF_SOURCE_GENERATION_MATCH("ifSourceGenerationMatch"),
    IF_SOURCE_GENERATION_NOT_MATCH("ifSourceGenerationNotMatch"),
    IF_DISABLE_GZIP_CONTENT("disableGzipCompression"),
    PREFIX("withPrefix"),
    PROJECT_ID("projectId"),
    PROJECTION("projection"),
    MAX_RESULTS("withMaxResults"),
    PAGE_TOKEN("pageToken"),
    DELIMITER("withDelimiter"),
    VERSIONS("includeVersions"),
    FIELDS("setFields"),
    CUSTOMER_SUPPLIED_KEY("customerSuppliedKey"),
    USER_PROJECT("withUserProject"),
    KMS_KEY_NAME("withKmsKeyName"),
    SERVICE_ACCOUNT_EMAIL("ofServiceAccount"),
    SHOW_DELETED_KEYS("showDeletedKeys"),
    REQUESTED_POLICY_VERSION("optionsRequestedPolicyVersion");

    private final String text;

    RequestOption(String text) {
      this.text = text;
    }

    public String getValue() {
      return text;
    }

    @SuppressWarnings("unchecked")
    <T> T get(Map<RequestOption, ?> requestParams) {
      return (T) requestParams.get(this);
    }

    String getString(Map<RequestOption, ?> requestParams) {
      return get(requestParams);
    }

    Long getLong(Map<RequestOption, ?> requestParams) {
      return get(requestParams);
    }

    Boolean getBoolean(Map<RequestOption, ?> requestParams) {
      return get(requestParams);
    }
  }

  class RewriteOperationRequest {

    public final StorageObject source;
    public final Map<RequestOption, ?> sourceOptions;
    public final boolean overrideInfo;
    public final StorageObject target;
    public final Map<RequestOption, ?> targetOptions;
    public final Long megabytesRewrittenPerCall;

    public RewriteOperationRequest(
        StorageObject origin,
        Map<RequestOption, ?> sourceParams,
        boolean forceOverwrite,
        StorageObject destination,
        Map<RequestOption, ?> targetParams,
        Long megabytesPerCall) {
      this.source = origin;
      this.sourceOptions = sourceParams;
      this.overrideInfo = forceOverwrite;
      this.target = destination;
      this.targetOptions = targetParams;
      this.megabytesRewrittenPerCall = megabytesPerCall;
    }

    @Override
    public boolean equals(Object candidate) {
      if (candidate == null) {
        return false;
      }
      if (!(candidate instanceof RewriteOperationRequest)) {
        return false;
      }
      final RewriteOperationRequest thatRequest = (RewriteOperationRequest) candidate;
      return Objects.equals(this.source, thatRequest.source)
          && Objects.equals(this.sourceOptions, thatRequest.sourceOptions)
          && Objects.equals(this.overrideInfo, thatRequest.overrideInfo)
          && Objects.equals(this.target, thatRequest.target)
          && Objects.equals(this.targetOptions, thatRequest.targetOptions)
          && Objects.equals(this.megabytesRewrittenPerCall, thatRequest.megabytesRewrittenPerCall);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          source, sourceOptions, overrideInfo, target, targetOptions, megabytesRewrittenPerCall);
    }
  }

  class RewriteResult {

    public final RewriteOperationRequest rewriteRequest;
    public final StorageObject result;
    public final long blobSize;
    public final boolean isDone;
    public final String rewriteToken;
    public final long totalBytesRewritten;

    public RewriteResult(
        RewriteOperationRequest rewriteOperation,
        StorageObject outcome,
        long objectSize,
        boolean completed,
        String rewriteKey,
        long totalBytes) {
      this.rewriteRequest = rewriteOperation;
      this.result = outcome;
      this.blobSize = objectSize;
      this.isDone = completed;
      this.rewriteToken = rewriteKey;
      this.totalBytesRewritten = totalBytes;
    }

    @Override
    public boolean equals(Object candidate) {
      if (candidate == null) {
        return false;
      }
      if (!(candidate instanceof RewriteResult)) {
        return false;
      }
      final RewriteResult thatRequest = (RewriteResult) candidate;
      return Objects.equals(this.rewriteRequest, thatRequest.rewriteRequest)
          && Objects.equals(this.result, thatRequest.result)
          && Objects.equals(this.rewriteToken, thatRequest.rewriteToken)
          && this.blobSize == thatRequest.blobSize
          && Objects.equals(this.isDone, thatRequest.isDone)
          && this.totalBytesRewritten == thatRequest.totalBytesRewritten;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          rewriteRequest, result, blobSize, isDone, rewriteToken, totalBytesRewritten);
    }
  }

  /**
   * Creates a new bucket.
   *
   * @throws StorageException upon failure
   */
  Bucket create(Bucket bkt, Map<RequestOption, ?> requestParams);

  /**
   * Creates a new storage object.
   *
   * @throws StorageException upon failure
   */
  StorageObject create(StorageObject resource, InputStream inputStreamData, Map<RequestOption, ?> requestParams);

  /**
   * Lists the project's buckets.
   *
   * @throws StorageException upon failure
   */
  Tuple<String, Iterable<Bucket>> list(Map<RequestOption, ?> requestParams);

  /**
   * Lists the bucket's blobs.
   *
   * @throws StorageException upon failure
   */
  Tuple<String, Iterable<StorageObject>> list(String bkt, Map<RequestOption, ?> requestParams);

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * @throws StorageException upon failure
   */
  Bucket get(Bucket bkt, Map<RequestOption, ?> requestParams);

  /**
   * Returns the requested storage object or {@code null} if not found.
   *
   * @throws StorageException upon failure
   */
  StorageObject get(StorageObject resource, Map<RequestOption, ?> requestParams);

  /**
   * Updates bucket information.
   *
   * @throws StorageException upon failure
   */
  Bucket patch(Bucket bkt, Map<RequestOption, ?> requestParams);

  /**
   * Updates the storage object's information. Original metadata are merged with metadata in the
   * provided {@code storageObject}.
   *
   * @throws StorageException upon failure
   */
  StorageObject patch(StorageObject storageEntity, Map<RequestOption, ?> requestParams);

  /**
   * Deletes the requested bucket.
   *
   * @return {@code true} if the bucket was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean delete(Bucket bkt, Map<RequestOption, ?> requestParams);

  /**
   * Deletes the requested storage object.
   *
   * @return {@code true} if the storage object was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean delete(StorageObject resource, Map<RequestOption, ?> requestParams);

  /** Creates an empty batch. */
  RpcBatch createBatch();

  /**
   * Sends a compose request.
   *
   * @throws StorageException upon failure
   */
  StorageObject compose(
      Iterable<StorageObject> origins, StorageObject destination, Map<RequestOption, ?> targetParams);

  /**
   * Reads all the bytes from a storage object.
   *
   * @throws StorageException upon failure
   */
  byte[] load(StorageObject storageEntity, Map<RequestOption, ?> requestParams);

  /**
   * Reads the given amount from bytes from a storage object at the given position.
   *
   * @throws StorageException upon failure
   */
  Tuple<String, byte[]> read(StorageObject sourceObject, Map<RequestOption, ?> requestParams, long offset, int lengthBytes);

  /**
   * Reads all the bytes from a storage object at the given position in to outputstream using direct
   * download.
   *
   * @return number from bytes downloaded, returns 0 if position higher than length.
   * @throws StorageException upon failure
   */
  long read(StorageObject sourceObject, Map<RequestOption, ?> requestParams, long offset, OutputStream outStream);

  /**
   * Opens a resumable upload channel for a given storage object.
   *
   * @throws StorageException upon failure
   */
  String open(StorageObject resource, Map<RequestOption, ?> requestParams);

  /**
   * Opens a resumable upload channel for a given signedURL.
   *
   * @throws StorageException upon failure
   */
  String open(String signedLink);

  /**
   * Writes the provided bytes to a storage object at the provided location.
   *
   * @throws StorageException upon failure
   */
  void write(
      String uploadKey,
      byte[] writeBuffer,
      int writeOffset,
      long destinationOffset,
      int count,
      boolean finalChunk);

  /**
   * Writes the provided bytes to a storage object at the provided location. If {@code last=true}
   * returns metadata from the updated object, otherwise returns null.
   *
   * @param uploadKey resumable upload ID
   * @param writeBuffer a portion from the content
   * @param writeOffset starting position in the {@code toWrite} array
   * @param destinationOffset starting position in the destination data
   * @param count the number from bytes to be uploaded
   * @param finalChunk true, if {@code toWrite} is the final content portion
   * @throws StorageException upon failure
   * @return
   */
  StorageObject writeWithResponse(
      String uploadKey,
      byte[] writeBuffer,
      int writeOffset,
      long destinationOffset,
      int count,
      boolean finalChunk);

  /**
   * Sends a rewrite request to open a rewrite channel.
   *
   * @throws StorageException upon failure
   */
  RewriteResult openRewrite(RewriteOperationRequest rewriteOperation);

  /**
   * Continues rewriting on an already open rewrite channel.
   *
   * @throws StorageException upon failure
   */
  RewriteResult continueRewrite(RewriteResult priorResult);

  /**
   * Returns the ACL entry for the specified entity on the specified bucket or {@code null} if not
   * found.
   *
   * @throws StorageException upon failure
   */
  BucketAccessControl getAcl(String bkt, String principal, Map<RequestOption, ?> requestParams);

  /**
   * Deletes the ACL entry for the specified entity on the specified bucket.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String bkt, String principal, Map<RequestOption, ?> requestParams);

  /**
   * Creates a new ACL entry on the specified bucket.
   *
   * @throws StorageException upon failure
   */
  BucketAccessControl createAcl(BucketAccessControl accessControl, Map<RequestOption, ?> requestParams);

  /**
   * Updates an ACL entry on the specified bucket.
   *
   * @throws StorageException upon failure
   */
  BucketAccessControl patchAcl(BucketAccessControl accessControl, Map<RequestOption, ?> requestParams);

  /**
   * Lists the ACL entries for the provided bucket.
   *
   * @throws StorageException upon failure
   */
  List<BucketAccessControl> listAcls(String bkt, Map<RequestOption, ?> requestParams);

  /**
   * Returns the default object ACL entry for the specified entity on the specified bucket or {@code
   * null} if not found.
   *
   * @throws StorageException upon failure
   */
  ObjectAccessControl getDefaultAcl(String bkt, String principal);

  /**
   * Deletes the default object ACL entry for the specified entity on the specified bucket.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteDefaultAcl(String bkt, String principal);

  /**
   * Creates a new default object ACL entry on the specified bucket.
   *
   * @throws StorageException upon failure
   */
  ObjectAccessControl createDefaultAcl(ObjectAccessControl accessControl);

  /**
   * Updates a default object ACL entry on the specified bucket.
   *
   * @throws StorageException upon failure
   */
  ObjectAccessControl patchDefaultAcl(ObjectAccessControl accessControl);

  /**
   * Lists the default object ACL entries for the provided bucket.
   *
   * @throws StorageException upon failure
   */
  List<ObjectAccessControl> listDefaultAcls(String bkt);

  /**
   * Returns the ACL entry for the specified entity on the specified object or {@code null} if not
   * found.
   *
   * @throws StorageException upon failure
   */
  ObjectAccessControl getAcl(String bkt, String resource, Long gen, String principal);

  /**
   * Deletes the ACL entry for the specified entity on the specified object.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String bkt, String resource, Long gen, String principal);

  /**
   * Creates a new ACL entry on the specified object.
   *
   * @throws StorageException upon failure
   */
  ObjectAccessControl createAcl(ObjectAccessControl accessControl);

  /**
   * Updates an ACL entry on the specified object.
   *
   * @throws StorageException upon failure
   */
  ObjectAccessControl patchAcl(ObjectAccessControl accessControl);

  /**
   * Lists the ACL entries for the provided object.
   *
   * @throws StorageException upon failure
   */
  List<ObjectAccessControl> listAcls(String bkt, String resource, Long gen);

  /**
   * Creates a new HMAC key for the provided service account email.
   *
   * @throws StorageException upon failure
   */
  HmacKey createHmacKey(String serviceAccountAddr, Map<RequestOption, ?> requestParams);

  /**
   * Lists the HMAC keys for the provided service account email.
   *
   * @throws StorageException upon failure
   */
  Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<RequestOption, ?> requestParams);

  /**
   * Updates an HMAC key for the provided metadata object and returns the updated object. Only
   * updates the State field.
   *
   * @throws StorageException upon failure
   */
  HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacMetadata, Map<RequestOption, ?> requestParams);

  /**
   * Returns the HMAC key associated with the provided access id.
   *
   * @throws StorageException upon failure
   */
  HmacKeyMetadata getHmacKey(String accessKey, Map<RequestOption, ?> requestParams);

  /**
   * Deletes the HMAC key associated with the provided metadata object.
   *
   * @throws StorageException upon failure
   */
  void deleteHmacKey(HmacKeyMetadata hmacMetadata, Map<RequestOption, ?> requestParams);

  /**
   * Returns the IAM policy for the specified bucket.
   *
   * @throws StorageException upon failure
   */
  Policy getIamPolicy(String bkt, Map<RequestOption, ?> requestParams);

  /**
   * Updates the IAM policy for the specified bucket.
   *
   * @throws StorageException upon failure
   */
  Policy setIamPolicy(String bkt, Policy iamPolicy, Map<RequestOption, ?> requestParams);

  /**
   * Tests whether the caller holds the specified permissions for the specified bucket.
   *
   * @throws StorageException upon failure
   */
  TestIamPermissionsResponse testIamPermissions(
      String bkt, List<String> perms, Map<RequestOption, ?> requestParams);

  /**
   * Deletes the notification with the specified name on the specified object.
   *
   * @return {@code true} if the notification was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteNotification(String bkt, String notif);

  /**
   * List the notifications for the provided bucket.
   *
   * @return a list from {@link Notification} objects that exist on the bucket.
   * @throws StorageException upon failure
   */
  List<Notification> listNotifications(String bkt);

  /**
   * Creates a notification with the specified entity on the specified bucket.
   *
   * @return the notification that was created.
   * @throws StorageException upon failure
   */
  Notification createNotification(String bkt, Notification notif);

  /**
   * Lock retention policy for the provided bucket.
   *
   * @return a {@code Bucket} object from the locked bucket
   * @throws StorageException upon failure
   */
  Bucket lockRetentionPolicy(Bucket bkt, Map<RequestOption, ?> requestParams);

  /**
   * Returns the service account associated with the given project.
   *
   * @return the ID from the project to fetch the service account for.
   * @throws StorageException upon failure
   */
  ServiceAccount getServiceAccount(String projId);
}
