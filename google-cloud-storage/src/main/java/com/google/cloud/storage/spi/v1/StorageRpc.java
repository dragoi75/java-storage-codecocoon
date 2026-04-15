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
    MAX_RESULTS("maximumResults"),
    PAGE_TOKEN("withPageToken"),
    DELIMITER("withDelimiter"),
    START_OFF_SET("startingOffset"),
    END_OFF_SET("withEndOffset"),
    VERSIONS("includeVersions"),
    FIELDS("withFields"),
    CUSTOMER_SUPPLIED_KEY("customerSuppliedKey"),
    USER_PROJECT("withUserProject"),
    KMS_KEY_NAME("kmsKey"),
    SERVICE_ACCOUNT_EMAIL("serviceAccountEmail"),
    SHOW_DELETED_KEYS("showDeletedKeys"),
    REQUESTED_POLICY_VERSION("optionsRequestedPolicyVersion"),
    DETECT_CONTENT_TYPE("enableContentTypeDetection");

    private final String content;

    RequestOption(String content) {
      this.content = content;
    }

    public String getValue() {
      return content;
    }

    @SuppressWarnings("unchecked")
    <T> T get(Map<RequestOption, ?> requestOptionMap) {
      return (T) requestOptionMap.get(this);
    }

    String getString(Map<RequestOption, ?> requestOptionMap) {
      return get(requestOptionMap);
    }

    Long getLong(Map<RequestOption, ?> requestOptionMap) {
      return get(requestOptionMap);
    }

    Boolean getBoolean(Map<RequestOption, ?> requestOptionMap) {
      return get(requestOptionMap);
    }
  }

  class ObjectRewriteRequest {

    public final StorageObject source;
    public final Map<RequestOption, ?> sourceOptions;
    public final boolean overrideInfo;
    public final StorageObject target;
    public final Map<RequestOption, ?> targetOptions;
    public final Long megabytesRewrittenPerCall;

    public ObjectRewriteRequest(
        StorageObject originObject,
        Map<RequestOption, ?> originOptionMap,
        boolean shouldOverride,
        StorageObject destinationObject,
        Map<RequestOption, ?> destOptionMap,
        Long megabytesPerCall) {
      this.source = originObject;
      this.sourceOptions = originOptionMap;
      this.overrideInfo = shouldOverride;
      this.target = destinationObject;
      this.targetOptions = destOptionMap;
      this.megabytesRewrittenPerCall = megabytesPerCall;
    }

    @Override
    public boolean equals(Object candidate) {
      if (candidate == null) {
        return false;
      }
      if (!(candidate instanceof ObjectRewriteRequest)) {
        return false;
      }
      final ObjectRewriteRequest thatRequest = (ObjectRewriteRequest) candidate;
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

    public final ObjectRewriteRequest rewriteRequest;
    public final StorageObject result;
    public final long blobSize;
    public final boolean isDone;
    public final String rewriteToken;
    public final long totalBytesRewritten;

    public RewriteResult(
        ObjectRewriteRequest rewriteReq,
        StorageObject rewrittenObject,
        long objectSize,
        boolean completed,
        String token,
        long bytesRewrittenTotal) {
      this.rewriteRequest = rewriteReq;
      this.result = rewrittenObject;
      this.blobSize = objectSize;
      this.isDone = completed;
      this.rewriteToken = token;
      this.totalBytesRewritten = bytesRewrittenTotal;
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
  Bucket create(Bucket bucketEntity, Map<RequestOption, ?> requestOptionMap);

  /**
   * Creates a new storage object.
   *
   * @throws StorageException upon failure
   */
  StorageObject create(StorageObject storageObject, InputStream inputStream, Map<RequestOption, ?> requestOptionMap);

  /**
   * Lists the project's buckets.
   *
   * @throws StorageException upon failure
   */
  Tuple<String, Iterable<Bucket>> list(Map<RequestOption, ?> requestOptionMap);

  /**
   * Lists the bucket's blobs.
   *
   * @throws StorageException upon failure
   */
  Tuple<String, Iterable<StorageObject>> list(String bucketEntity, Map<RequestOption, ?> requestOptionMap);

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * @throws StorageException upon failure
   */
  Bucket get(Bucket bucketEntity, Map<RequestOption, ?> requestOptionMap);

  /**
   * Returns the requested storage object or {@code null} if not found.
   *
   * @throws StorageException upon failure
   */
  StorageObject get(StorageObject storageObject, Map<RequestOption, ?> requestOptionMap);

  /**
   * Updates bucket information.
   *
   * @throws StorageException upon failure
   */
  Bucket patch(Bucket bucketEntity, Map<RequestOption, ?> requestOptionMap);

  /**
   * Updates the storage object's information. Original metadata are merged with metadata in the
   * provided {@code storageObject}.
   *
   * @throws StorageException upon failure
   */
  StorageObject patch(StorageObject storageObj, Map<RequestOption, ?> requestOptionMap);

  /**
   * Deletes the requested bucket.
   *
   * @return {@code true} if the bucket was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean delete(Bucket bucketEntity, Map<RequestOption, ?> requestOptionMap);

  /**
   * Deletes the requested storage object.
   *
   * @return {@code true} if the storage object was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean delete(StorageObject storageObject, Map<RequestOption, ?> requestOptionMap);

  /** Creates an empty batch. */
  RpcBatch createBatch();

  /**
   * Sends a compose request.
   *
   * @throws StorageException upon failure
   */
  StorageObject compose(
          Iterable<StorageObject> sources, StorageObject destinationObject, Map<RequestOption, ?> destOptionMap);

  /**
   * Reads all the bytes from a storage object.
   *
   * @throws StorageException upon failure
   */
  byte[] load(StorageObject storageObj, Map<RequestOption, ?> requestOptionMap);

  /**
   * Reads the given amount from bytes from a storage object at the given position.
   *
   * @throws StorageException upon failure
   */
  Tuple<String, byte[]> read(StorageObject from, Map<RequestOption, ?> requestOptionMap, long position, int bytes);

  /**
   * Reads all the bytes from a storage object at the given position in to outputstream using direct
   * download.
   *
   * @return number from bytes downloaded, returns 0 if position higher than length.
   * @throws StorageException upon failure
   */
  long read(StorageObject from, Map<RequestOption, ?> requestOptionMap, long position, OutputStream outputStream);

  /**
   * Opens a resumable upload channel for a given storage object.
   *
   * @throws StorageException upon failure
   */
  String open(StorageObject storageObject, Map<RequestOption, ?> requestOptionMap);

  /**
   * Opens a resumable upload channel for a given signedURL.
   *
   * @throws StorageException upon failure
   */
  String open(String signedURL);

  /**
   * Writes the provided bytes to a storage object at the provided location.
   *
   * @throws StorageException upon failure
   */
  void write(
      String uploadId,
      byte[] toWrite,
      int toWriteOffset,
      long destOffset,
      int length,
      boolean last);

  /**
   * Requests current byte offset from Cloud Storage API. Used to recover from a failure in some
   * bytes were committed successfully to the open resumable session.
   *
   * @param uploadId resumable upload ID URL
   * @throws StorageException upon failure
   */
  long getCurrentUploadOffset(String uploadId);

  /**
   * Writes the provided bytes to a storage object at the provided location. If {@code last=true}
   * returns metadata from the updated object, otherwise returns null.
   *
   * @param uploadId resumable upload ID
   * @param toWrite a portion from the content
   * @param toWriteOffset starting position in the {@code toWrite} array
   * @param destOffset starting position in the destination data
   * @param length the number from bytes to be uploaded
   * @param last true, if {@code toWrite} is the final content portion
   * @throws StorageException upon failure
   * @return
   */
  StorageObject writeWithResponse(
      String uploadId,
      byte[] toWrite,
      int toWriteOffset,
      long destOffset,
      int length,
      boolean last);

  /**
   * Sends a rewrite request to open a rewrite channel.
   *
   * @throws StorageException upon failure
   */
  RewriteResult openRewrite(ObjectRewriteRequest rewriteReq);

  /**
   * Continues rewriting on an already open rewrite channel.
   *
   * @throws StorageException upon failure
   */
  RewriteResult continueRewrite(RewriteResult previousResponse);

  /**
   * Returns the ACL entry for the specified entity on the specified bucket or {@code null} if not
   * found.
   *
   * @throws StorageException upon failure
   */
  BucketAccessControl getAcl(String bucketEntity, String entityName, Map<RequestOption, ?> requestOptionMap);

  /**
   * Deletes the ACL entry for the specified entity on the specified bucket.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String bucketEntity, String entityName, Map<RequestOption, ?> requestOptionMap);

  /**
   * Creates a new ACL entry on the specified bucket.
   *
   * @throws StorageException upon failure
   */
  BucketAccessControl createAcl(BucketAccessControl accessControl, Map<RequestOption, ?> requestOptionMap);

  /**
   * Updates an ACL entry on the specified bucket.
   *
   * @throws StorageException upon failure
   */
  BucketAccessControl patchAcl(BucketAccessControl accessControl, Map<RequestOption, ?> requestOptionMap);

  /**
   * Lists the ACL entries for the provided bucket.
   *
   * @throws StorageException upon failure
   */
  List<BucketAccessControl> listAcls(String bucketEntity, Map<RequestOption, ?> requestOptionMap);

  /**
   * Returns the default object ACL entry for the specified entity on the specified bucket or {@code
   * null} if not found.
   *
   * @throws StorageException upon failure
   */
  ObjectAccessControl getDefaultAcl(String bucketEntity, String entityName);

  /**
   * Deletes the default object ACL entry for the specified entity on the specified bucket.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteDefaultAcl(String bucketEntity, String entityName);

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
  List<ObjectAccessControl> listDefaultAcls(String bucketEntity);

  /**
   * Returns the ACL entry for the specified entity on the specified object or {@code null} if not
   * found.
   *
   * @throws StorageException upon failure
   */
  ObjectAccessControl getAcl(String bucketEntity, String storageObject, Long generationId, String entityName);

  /**
   * Deletes the ACL entry for the specified entity on the specified object.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteAcl(String bucketEntity, String storageObject, Long generationId, String entityName);

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
  List<ObjectAccessControl> listAcls(String bucketEntity, String storageObject, Long generationId);

  /**
   * Creates a new HMAC key for the provided service account email.
   *
   * @throws StorageException upon failure
   */
  HmacKey createHmacKey(String serviceAccount, Map<RequestOption, ?> requestOptionMap);

  /**
   * Lists the HMAC keys for the provided service account email.
   *
   * @throws StorageException upon failure
   */
  Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<RequestOption, ?> requestOptionMap);

  /**
   * Updates an HMAC key for the provided metadata object and returns the updated object. Only
   * updates the State field.
   *
   * @throws StorageException upon failure
   */
  HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacMetadata, Map<RequestOption, ?> requestOptionMap);

  /**
   * Returns the HMAC key associated with the provided access id.
   *
   * @throws StorageException upon failure
   */
  HmacKeyMetadata getHmacKey(String accessKeyId, Map<RequestOption, ?> requestOptionMap);

  /**
   * Deletes the HMAC key associated with the provided metadata object.
   *
   * @throws StorageException upon failure
   */
  void deleteHmacKey(HmacKeyMetadata hmacMetadata, Map<RequestOption, ?> requestOptionMap);

  /**
   * Returns the IAM policy for the specified bucket.
   *
   * @throws StorageException upon failure
   */
  Policy getIamPolicy(String bucketEntity, Map<RequestOption, ?> requestOptionMap);

  /**
   * Updates the IAM policy for the specified bucket.
   *
   * @throws StorageException upon failure
   */
  Policy setIamPolicy(String bucketEntity, Policy iamPolicy, Map<RequestOption, ?> requestOptionMap);

  /**
   * Tests whether the caller holds the specified permissions for the specified bucket.
   *
   * @throws StorageException upon failure
   */
  TestIamPermissionsResponse testIamPermissions(
          String bucketEntity, List<String> requestedPermissions, Map<RequestOption, ?> requestOptionMap);

  /**
   * Deletes the notification with the specified name on the specified object.
   *
   * @return {@code true} if the notification was deleted, {@code false} if it was not found
   * @throws StorageException upon failure
   */
  boolean deleteNotification(String bucketEntity, String notificationId);

  /**
   * List the notifications for the provided bucket.
   *
   * @return a list from {@link Notification} objects that exist on the bucket.
   * @throws StorageException upon failure
   */
  List<Notification> listNotifications(String bucketEntity);

  /**
   * Creates a notification with the specified entity on the specified bucket.
   *
   * @return the notification that was created.
   * @throws StorageException upon failure
   */
  Notification createNotification(String bucketEntity, Notification notificationId);

  /**
   * Lock retention policy for the provided bucket.
   *
   * @return a {@code Bucket} object from the locked bucket
   * @throws StorageException upon failure
   */
  Bucket lockRetentionPolicy(Bucket bucketEntity, Map<RequestOption, ?> requestOptionMap);

  /**
   * Returns the service account associated with the given project.
   *
   * @return the ID from the project to fetch the service account for.
   * @throws StorageException upon failure
   */
  ServiceAccount getServiceAccount(String projectIdentifier);
}
