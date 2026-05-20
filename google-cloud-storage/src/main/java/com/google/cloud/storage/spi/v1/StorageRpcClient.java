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
import com.google.cloud.storage.StorageServiceException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@InternalApi
public interface StorageRpcClient extends ServiceRpc {

  // These options are part of the Google Cloud storage header options
  enum StorageOption {
    PREDEFINED_ACL("predefinedAcl"),
    PREDEFINED_DEFAULT_OBJECT_ACL("predefinedDefaultObjectAcl"),
    IF_METAGENERATION_MATCH("ifMetagenerationMatch"),
    IF_METAGENERATION_NOT_MATCH("ifMetagenerationNotMatch"),
    IF_GENERATION_MATCH("ifGenerationMatch"),
    IF_GENERATION_NOT_MATCH("ifGenerationNotMatch"),
    IF_SOURCE_METAGENERATION_MATCH("ifSourceMetagenerationMatch"),
    IF_SOURCE_METAGENERATION_NOT_MATCH("ifSourceMetagenerationNotMatch"),
    IF_SOURCE_GENERATION_MATCH("ifSourceGenerationMatch"),
    IF_SOURCE_GENERATION_NOT_MATCH("ifSourceGenerationNotMatch"),
    IF_DISABLE_GZIP_CONTENT("disableGzipContent"),
    PREFIX("prefix"),
    PROJECT_ID("projectId"),
    PROJECTION("projection"),
    MAX_RESULTS("maxResults"),
    PAGE_TOKEN("pageToken"),
    DELIMITER("delimiter"),
    START_OFF_SET("startOffset"),
    END_OFF_SET("endOffset"),
    VERSIONS("versions"),
    FIELDS("fields"),
    CUSTOMER_SUPPLIED_KEY("customerSuppliedKey"),
    USER_PROJECT("userProject"),
    KMS_KEY_NAME("kmsKeyName"),
    SERVICE_ACCOUNT_EMAIL("serviceAccount"),
    SHOW_DELETED_KEYS("showDeletedKeys"),
    REQUESTED_POLICY_VERSION("optionsRequestedPolicyVersion"),
    DETECT_CONTENT_TYPE("detectContentType");

    private final String stringContent;

    StorageOption(String stringContent) {
      this.stringContent = stringContent;
    }

    public String getValue() {
      return stringContent;
    }

    @SuppressWarnings("unchecked")
    <T> T get(Map<StorageOption, ?> settingsMap) {
      return (T) settingsMap.get(this);
    }

    String getString(Map<StorageOption, ?> settingsMap) {
      return get(settingsMap);
    }

    Long getLong(Map<StorageOption, ?> settingsMap) {
      return get(settingsMap);
    }

    Boolean getBoolean(Map<StorageOption, ?> settingsMap) {
      return get(settingsMap);
    }
  }

  class ObjectRewriteRequest {

    public final StorageObject source;
    public final Map<StorageOption, ?> sourceOptions;
    public final boolean overrideInfo;
    public final StorageObject target;
    public final Map<StorageOption, ?> targetOptions;
    public final Long megabytesRewrittenPerCall;

    public ObjectRewriteRequest(
        StorageObject originObject,
        Map<StorageOption, ?> originSettingsMap,
        boolean forceReplace,
        StorageObject destinationObject,
        Map<StorageOption, ?> destinationSettingsMap,
        Long mbPerCall) {
      this.source = originObject;
      this.sourceOptions = originSettingsMap;
      this.overrideInfo = forceReplace;
      this.target = destinationObject;
      this.targetOptions = destinationSettingsMap;
      this.megabytesRewrittenPerCall = mbPerCall;
    }

    @Override
    public boolean equals(Object otherObjectCandidate) {
      if (otherObjectCandidate == null) {
        return false;
      }
      if (!(otherObjectCandidate instanceof ObjectRewriteRequest)) {
        return false;
      }
      final ObjectRewriteRequest thatRequest = (ObjectRewriteRequest) otherObjectCandidate;
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
        ObjectRewriteRequest rewriteOperation,
        StorageObject outputObject,
        long objectSize,
        boolean completed,
        String continuationToken,
        long totalBytes) {
      this.rewriteRequest = rewriteOperation;
      this.result = outputObject;
      this.blobSize = objectSize;
      this.isDone = completed;
      this.rewriteToken = continuationToken;
      this.totalBytesRewritten = totalBytes;
    }

    @Override
    public boolean equals(Object otherObjectCandidate) {
      if (otherObjectCandidate == null) {
        return false;
      }
      if (!(otherObjectCandidate instanceof RewriteResult)) {
        return false;
      }
      final RewriteResult thatRequest = (RewriteResult) otherObjectCandidate;
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
   * @throws StorageServiceException upon failure
   */
  Bucket create(Bucket bucket, Map<StorageOption, ?> options);

  /**
   * Creates a new storage object.
   *
   * @throws StorageServiceException upon failure
   */
  StorageObject create(StorageObject object, InputStream content, Map<StorageOption, ?> options);

  /**
   * Lists the project's buckets.
   *
   * @throws StorageServiceException upon failure
   */
  Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> options);

  /**
   * Lists the bucket's blobs.
   *
   * @throws StorageServiceException upon failure
   */
  Tuple<String, Iterable<StorageObject>> list(String bucket, Map<StorageOption, ?> options);

  /**
   * Returns the requested bucket or {@code null} if not found.
   *
   * @throws StorageServiceException upon failure
   */
  Bucket get(Bucket bucket, Map<StorageOption, ?> options);

  /**
   * Returns the requested storage object or {@code null} if not found.
   *
   * @throws StorageServiceException upon failure
   */
  StorageObject get(StorageObject object, Map<StorageOption, ?> options);

  /**
   * Updates bucket information.
   *
   * @throws StorageServiceException upon failure
   */
  Bucket patch(Bucket bucket, Map<StorageOption, ?> options);

  /**
   * Updates the storage object's information. Original metadata are merged with metadata in the
   * provided {@code storageObject}.
   *
   * @throws StorageServiceException upon failure
   */
  StorageObject patch(StorageObject storageObject, Map<StorageOption, ?> options);

  /**
   * Deletes the requested bucket.
   *
   * @return {@code true} if the bucket was deleted, {@code false} if it was not found
   * @throws StorageServiceException upon failure
   */
  boolean delete(Bucket bucket, Map<StorageOption, ?> options);

  /**
   * Deletes the requested storage object.
   *
   * @return {@code true} if the storage object was deleted, {@code false} if it was not found
   * @throws StorageServiceException upon failure
   */
  boolean delete(StorageObject object, Map<StorageOption, ?> options);

  /** Creates an empty batch. */
  RpcRequestBatch createBatch();

  /**
   * Sends a compose request.
   *
   * @throws StorageServiceException upon failure
   */
  StorageObject compose(
      Iterable<StorageObject> sources, StorageObject target, Map<StorageOption, ?> targetOptions);

  /**
   * Reads all the bytes from a storage object.
   *
   * @throws StorageServiceException upon failure
   */
  byte[] load(StorageObject storageObject, Map<StorageOption, ?> options);

  /**
   * Reads the given amount of bytes from a storage object at the given position.
   *
   * @throws StorageServiceException upon failure
   */
  Tuple<String, byte[]> read(StorageObject from, Map<StorageOption, ?> options, long position, int bytes);

  /**
   * Reads all the bytes from a storage object at the given position in to outputstream using direct
   * download.
   *
   * @return number of bytes downloaded, returns 0 if position higher than length.
   * @throws StorageServiceException upon failure
   */
  long read(StorageObject from, Map<StorageOption, ?> options, long position, OutputStream outputStream);

  /**
   * Opens a resumable upload channel for a given storage object.
   *
   * @throws StorageServiceException upon failure
   */
  String open(StorageObject object, Map<StorageOption, ?> options);

  /**
   * Opens a resumable upload channel for a given signedURL.
   *
   * @throws StorageServiceException upon failure
   */
  String open(String signedURL);

  /**
   * Writes the provided bytes to a storage object at the provided location.
   *
   * @throws StorageServiceException upon failure
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
   * @param uploadToken resumable upload ID URL
   * @throws StorageServiceException upon failure
   */
  long getCurrentUploadOffset(String uploadToken);

  /**
   * Writes the provided bytes to a storage object at the provided location. If {@code last=true}
   * returns metadata of the updated object, otherwise returns null.
   *
   * @param uploadToken resumable upload ID
   * @param buffer a portion of the content
   * @param bufferOffset starting position in the {@code toWrite} array
   * @param destinationOffset starting position in the destination data
   * @param len the number of bytes to be uploaded
   * @param isFinal true, if {@code toWrite} is the final content portion
   * @throws StorageServiceException upon failure
   * @return
   */
  StorageObject writeWithResponse(
      String uploadToken,
      byte[] buffer,
      int bufferOffset,
      long destinationOffset,
      int len,
      boolean isFinal);

  /**
   * Sends a rewrite request to open a rewrite channel.
   *
   * @throws StorageServiceException upon failure
   */
  RewriteResult openRewrite(ObjectRewriteRequest rewriteRequest);

  /**
   * Continues rewriting on an already open rewrite channel.
   *
   * @throws StorageServiceException upon failure
   */
  RewriteResult continueRewrite(RewriteResult previousResponse);

  /**
   * Returns the ACL entry for the specified entity on the specified bucket or {@code null} if not
   * found.
   *
   * @throws StorageServiceException upon failure
   */
  BucketAccessControl getAcl(String bucket, String entity, Map<StorageOption, ?> options);

  /**
   * Deletes the ACL entry for the specified entity on the specified bucket.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageServiceException upon failure
   */
  boolean deleteAcl(String bucket, String entity, Map<StorageOption, ?> options);

  /**
   * Creates a new ACL entry on the specified bucket.
   *
   * @throws StorageServiceException upon failure
   */
  BucketAccessControl createAcl(BucketAccessControl acl, Map<StorageOption, ?> options);

  /**
   * Updates an ACL entry on the specified bucket.
   *
   * @throws StorageServiceException upon failure
   */
  BucketAccessControl patchAcl(BucketAccessControl acl, Map<StorageOption, ?> options);

  /**
   * Lists the ACL entries for the provided bucket.
   *
   * @throws StorageServiceException upon failure
   */
  List<BucketAccessControl> listAcls(String bucket, Map<StorageOption, ?> options);

  /**
   * Returns the default object ACL entry for the specified entity on the specified bucket or {@code
   * null} if not found.
   *
   * @throws StorageServiceException upon failure
   */
  ObjectAccessControl getDefaultAcl(String bucket, String entity);

  /**
   * Deletes the default object ACL entry for the specified entity on the specified bucket.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageServiceException upon failure
   */
  boolean deleteDefaultAcl(String bucket, String entity);

  /**
   * Creates a new default object ACL entry on the specified bucket.
   *
   * @throws StorageServiceException upon failure
   */
  ObjectAccessControl createDefaultAcl(ObjectAccessControl acl);

  /**
   * Updates a default object ACL entry on the specified bucket.
   *
   * @throws StorageServiceException upon failure
   */
  ObjectAccessControl patchDefaultAcl(ObjectAccessControl acl);

  /**
   * Lists the default object ACL entries for the provided bucket.
   *
   * @throws StorageServiceException upon failure
   */
  List<ObjectAccessControl> listDefaultAcls(String bucket);

  /**
   * Returns the ACL entry for the specified entity on the specified object or {@code null} if not
   * found.
   *
   * @throws StorageServiceException upon failure
   */
  ObjectAccessControl getAcl(String bucket, String object, Long generation, String entity);

  /**
   * Deletes the ACL entry for the specified entity on the specified object.
   *
   * @return {@code true} if the ACL was deleted, {@code false} if it was not found
   * @throws StorageServiceException upon failure
   */
  boolean deleteAcl(String bucket, String object, Long generation, String entity);

  /**
   * Creates a new ACL entry on the specified object.
   *
   * @throws StorageServiceException upon failure
   */
  ObjectAccessControl createAcl(ObjectAccessControl acl);

  /**
   * Updates an ACL entry on the specified object.
   *
   * @throws StorageServiceException upon failure
   */
  ObjectAccessControl patchAcl(ObjectAccessControl acl);

  /**
   * Lists the ACL entries for the provided object.
   *
   * @throws StorageServiceException upon failure
   */
  List<ObjectAccessControl> listAcls(String bucket, String object, Long generation);

  /**
   * Creates a new HMAC key for the provided service account email.
   *
   * @throws StorageServiceException upon failure
   */
  HmacKey createHmacKey(String serviceAccountEmail, Map<StorageOption, ?> options);

  /**
   * Lists the HMAC keys for the provided service account email.
   *
   * @throws StorageServiceException upon failure
   */
  Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> options);

  /**
   * Updates an HMAC key for the provided metadata object and returns the updated object. Only
   * updates the State field.
   *
   * @throws StorageServiceException upon failure
   */
  HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<StorageOption, ?> options);

  /**
   * Returns the HMAC key associated with the provided access id.
   *
   * @throws StorageServiceException upon failure
   */
  HmacKeyMetadata getHmacKey(String accessId, Map<StorageOption, ?> options);

  /**
   * Deletes the HMAC key associated with the provided metadata object.
   *
   * @throws StorageServiceException upon failure
   */
  void deleteHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<StorageOption, ?> options);

  /**
   * Returns the IAM policy for the specified bucket.
   *
   * @throws StorageServiceException upon failure
   */
  Policy getIamPolicy(String bucket, Map<StorageOption, ?> options);

  /**
   * Updates the IAM policy for the specified bucket.
   *
   * @throws StorageServiceException upon failure
   */
  Policy setIamPolicy(String bucket, Policy policy, Map<StorageOption, ?> options);

  /**
   * Tests whether the caller holds the specified permissions for the specified bucket.
   *
   * @throws StorageServiceException upon failure
   */
  TestIamPermissionsResponse testIamPermissions(
      String bucket, List<String> permissions, Map<StorageOption, ?> options);

  /**
   * Deletes the notification with the specified name on the specified object.
   *
   * @return {@code true} if the notification was deleted, {@code false} if it was not found
   * @throws StorageServiceException upon failure
   */
  boolean deleteNotification(String bucket, String notification);

  /**
   * List the notifications for the provided bucket.
   *
   * @return a list of {@link Notification} objects that exist on the bucket.
   * @throws StorageServiceException upon failure
   */
  List<Notification> listNotifications(String bucket);

  /**
   * Creates a notification with the specified entity on the specified bucket.
   *
   * @return the notification that was created.
   * @throws StorageServiceException upon failure
   */
  Notification createNotification(String bucket, Notification notification);

  /**
   * Lock retention policy for the provided bucket.
   *
   * @return a {@code Bucket} object of the locked bucket
   * @throws StorageServiceException upon failure
   */
  Bucket lockRetentionPolicy(Bucket bucket, Map<StorageOption, ?> options);

  /**
   * Returns the service account associated with the given project.
   *
   * @return the ID of the project to fetch the service account for.
   * @throws StorageServiceException upon failure
   */
  ServiceAccount getServiceAccount(String projectId);
}
