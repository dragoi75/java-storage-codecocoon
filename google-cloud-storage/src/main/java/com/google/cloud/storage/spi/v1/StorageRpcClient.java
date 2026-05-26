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
        DETECT_CONTENT_TYPE("detectContentType"),
        RETURN_RAW_INPUT_STREAM("returnRawInputStream");

        private final String optionContent;

        Boolean getBoolean(Map<StorageOption, ?> optionMap) {
            return get(optionMap);
        }

        String getString(Map<StorageOption, ?> optionMap) {
            return get(optionMap);
        }

        Long getLong(Map<StorageOption, ?> optionMap) {
            return get(optionMap);
        }

        StorageOption(String optionContent) {
            this.optionContent = optionContent;
        }

        @SuppressWarnings("unchecked")
        <T> T get(Map<StorageOption, ?> optionMap) {
            return (T) optionMap.get(this);
        }

        public String getValue() {
            return optionContent;
        }

    }

    class RewriteOperationRequest {

        public final StorageObject source;

        public final Map<StorageOption, ?> sourceOptions;

        public final boolean overrideInfo;

        public final StorageObject target;

        public final Map<StorageOption, ?> targetOptions;

        public final Long megabytesRewrittenPerCall;

        @Override
        public int hashCode() {
            return Objects.hash(source, sourceOptions, overrideInfo, target, targetOptions, megabytesRewrittenPerCall);
        }

        public RewriteOperationRequest(StorageObject srcObject, Map<StorageOption, ?> srcOptionMap, boolean shouldOverride, StorageObject destObject, Map<StorageOption, ?> destOptionMap, Long mbRewrittenPerCall) {
            this.source = srcObject;
            this.sourceOptions = srcOptionMap;
            this.overrideInfo = shouldOverride;
            this.target = destObject;
            this.targetOptions = destOptionMap;
            this.megabytesRewrittenPerCall = mbRewrittenPerCall;
        }

        @Override
        public boolean equals(Object otherObject) {
            if (null == otherObject) {
                return false;
            }
            if (!(otherObject instanceof RewriteOperationRequest)) {
                return false;
            }
            final RewriteOperationRequest thatRequest = (RewriteOperationRequest) otherObject;
            return Objects.equals(this.source, thatRequest.source) && Objects.equals(this.sourceOptions, thatRequest.sourceOptions) && Objects.equals(this.overrideInfo, thatRequest.overrideInfo) && Objects.equals(this.target, thatRequest.target) && Objects.equals(this.targetOptions, thatRequest.targetOptions) && Objects.equals(this.megabytesRewrittenPerCall, thatRequest.megabytesRewrittenPerCall);
        }

    }

    class RewriteResult {

        public final RewriteOperationRequest rewriteRequest;

        public final StorageObject result;

        public final long blobSize;

        public final boolean isDone;

        public final String rewriteToken;

        public final long totalBytesRewritten;

        @Override
        public int hashCode() {
            return Objects.hash(rewriteRequest, result, blobSize, isDone, rewriteToken, totalBytesRewritten);
        }

        @Override
        public boolean equals(Object otherObject) {
            if (null == otherObject) {
                return false;
            }
            if (!(otherObject instanceof RewriteResult)) {
                return false;
            }
            final RewriteResult thatRequest = (RewriteResult) otherObject;
            return Objects.equals(this.rewriteRequest, thatRequest.rewriteRequest) && Objects.equals(this.result, thatRequest.result) && Objects.equals(this.rewriteToken, thatRequest.rewriteToken) && thatRequest.blobSize == this.blobSize && Objects.equals(this.isDone, thatRequest.isDone) && thatRequest.totalBytesRewritten == this.totalBytesRewritten;
        }

        public RewriteResult(RewriteOperationRequest operationRequest, StorageObject outputObject, long objectSize, boolean completed, String continuationToken, long bytesRewrittenTotal) {
            this.rewriteRequest = operationRequest;
            this.result = outputObject;
            this.blobSize = objectSize;
            this.isDone = completed;
            this.rewriteToken = continuationToken;
            this.totalBytesRewritten = bytesRewrittenTotal;
        }

    }

    /**
     * Returns the ACL entry for the specified entity on the specified object or {@code null} if not
     * found.
     *
     * @throws StorageServiceException upon failure
     */
    ObjectAccessControl getAcl(String bucket, String object, Long generation, String entity);

    /**
     * Attempts to retrieve the StorageObject from a completed resumable upload. When a resumable
     * upload completes, the response will be the up-to-date StorageObject metadata. This up-to-date
     * metadata can then be used to validate the total size of the object along with new generation
     * and other information.
     *
     * <p>If for any reason, the response to the final PUT to a resumable upload is not received, this
     * method can be used to query for the up-to-date StorageObject. If the upload is complete, this
     * method can be used to access the StorageObject independently from any other liveness or
     * conditional criteria requirements that are otherwise applicable when using {@link
     * #get(StorageObject, Map)}.
     *
     * @param uploadToken resumable upload ID URL
     * @param totalSize the total number of bytes that should have been written.
     * @throws StorageServiceException if the upload is incomplete or does not exist
     */
    StorageObject queryCompletedResumableUpload(String uploadToken, long totalSize);

    /**
     * Creates a new default object ACL entry on the specified bucket.
     *
     * @throws StorageServiceException upon failure
     */
    ObjectAccessControl createDefaultAcl(ObjectAccessControl acl);

    /**
     * Updates bucket information.
     *
     * @throws StorageServiceException upon failure
     */
    Bucket patch(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Requests current byte offset from Cloud Storage API. Used to recover from a failure in some
     * bytes were committed successfully to the open resumable session.
     *
     * @param uploadToken resumable upload ID URL
     * @throws StorageServiceException upon failure
     */
    long getCurrentUploadOffset(String uploadToken);

    /**
     * Updates an ACL entry on the specified bucket.
     *
     * @throws StorageServiceException upon failure
     */
    BucketAccessControl patchAcl(BucketAccessControl acl, Map<StorageOption, ?> options);

    /**
     * Retrieves the list of notifications associated with the bucket.
     *
     * @return a list of {@link Notification} objects that exist on the bucket.
     * @throws StorageServiceException upon failure
     */
    List<Notification> listNotifications(String bucket);

    /**
     * Creates the notification for a given bucket.
     *
     * @return the created notification.
     * @throws StorageServiceException upon failure
     */
    Notification createNotification(String bucket, Notification notification);

    /**
     * Returns the service account associated with the given project.
     *
     * @return the ID of the project to fetch the service account for.
     * @throws StorageServiceException upon failure
     */
    ServiceAccount getServiceAccount(String projectId);

    /**
     * Continues rewriting on an already open rewrite channel.
     *
     * @throws StorageServiceException upon failure
     */
    RewriteResult continueRewrite(RewriteResult previousResponse);

    /**
     * Opens a resumable upload channel for a given signedURL.
     *
     * @throws StorageServiceException upon failure
     */
    String open(String signedURL);

    /**
     * Deletes the ACL entry for the specified entity on the specified object.
     *
     * @return {@code true} if the ACL was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean deleteAcl(String bucket, String object, Long generation, String entity);

    /**
     * Reads all the bytes from a storage object.
     *
     * @throws StorageServiceException upon failure
     */
    byte[] load(StorageObject storageObject, Map<StorageOption, ?> options);

    /**
     * Deletes the default object ACL entry for the specified entity on the specified bucket.
     *
     * @return {@code true} if the ACL was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean deleteDefaultAcl(String bucket, String entity);

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
     * Deletes the notification with the specified id on the bucket.
     *
     * @return {@code true} if the notification has been deleted, {@code false} if not found
     * @throws StorageServiceException upon failure
     */
    boolean deleteNotification(String bucket, String id);

    /**
     * Creates an empty batch.
     */
    RpcBatch createBatch();

    /**
     * Returns the IAM policy for the specified bucket.
     *
     * @throws StorageServiceException upon failure
     */
    Policy getIamPolicy(String bucket, Map<StorageOption, ?> options);

    /**
     * Lists the bucket's blobs.
     *
     * @throws StorageServiceException upon failure
     */
    Tuple<String, Iterable<StorageObject>> list(String bucket, Map<StorageOption, ?> options);

    /**
     * Returns the HMAC key associated with the provided access id.
     *
     * @throws StorageServiceException upon failure
     */
    HmacKeyMetadata getHmacKey(String accessId, Map<StorageOption, ?> options);

    /**
     * Gets the notification with the specified id.
     *
     * @return the {@code Notification} object with the given id or {@code null} if not found
     * @throws StorageServiceException upon failure
     */
    Notification getNotification(String bucket, String id);

    /**
     * Lists the ACL entries for the provided object.
     *
     * @throws StorageServiceException upon failure
     */
    List<ObjectAccessControl> listAcls(String bucket, String object, Long generation);

    /**
     * Updates an HMAC key for the provided metadata object and returns the updated object. Only
     * updates the State field.
     *
     * @throws StorageServiceException upon failure
     */
    HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<StorageOption, ?> options);

    /**
     * Returns the default object ACL entry for the specified entity on the specified bucket or {@code
     * null} if not found.
     *
     * @throws StorageServiceException upon failure
     */
    ObjectAccessControl getDefaultAcl(String bucket, String entity);

    /**
     * Updates the IAM policy for the specified bucket.
     *
     * @throws StorageServiceException upon failure
     */
    Policy setIamPolicy(String bucket, Policy policy, Map<StorageOption, ?> options);

    /**
     * Lists the HMAC keys for the provided service account email.
     *
     * @throws StorageServiceException upon failure
     */
    Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> options);

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
    StorageObject writeWithResponse(String uploadToken, byte[] buffer, int bufferOffset, long destinationOffset, int len, boolean isFinal);

    /**
     * Opens a resumable upload channel for a given storage object.
     *
     * @throws StorageServiceException upon failure
     */
    String open(StorageObject object, Map<StorageOption, ?> options);

    /**
     * Reads all the bytes from a storage object at the given position in to outputstream using direct
     * download.
     *
     * @return number of bytes downloaded, returns 0 if position higher than length.
     * @throws StorageServiceException upon failure
     */
    long read(StorageObject from, Map<StorageOption, ?> options, long position, OutputStream outputStream);

    /**
     * Lists the ACL entries for the provided bucket.
     *
     * @throws StorageServiceException upon failure
     */
    List<BucketAccessControl> listAcls(String bucket, Map<StorageOption, ?> options);

    /**
     * Lists the project's buckets.
     *
     * @throws StorageServiceException upon failure
     */
    Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> options);

    /**
     * Returns the ACL entry for the specified entity on the specified bucket or {@code null} if not
     * found.
     *
     * @throws StorageServiceException upon failure
     */
    BucketAccessControl getAcl(String bucket, String entity, Map<StorageOption, ?> options);

    /**
     * Sends a compose request.
     *
     * @throws StorageServiceException upon failure
     */
    StorageObject compose(Iterable<StorageObject> sources, StorageObject target, Map<StorageOption, ?> targetOptions);

    /**
     * Creates a new ACL entry on the specified bucket.
     *
     * @throws StorageServiceException upon failure
     */
    BucketAccessControl createAcl(BucketAccessControl acl, Map<StorageOption, ?> options);

    /**
     * Updates a default object ACL entry on the specified bucket.
     *
     * @throws StorageServiceException upon failure
     */
    ObjectAccessControl patchDefaultAcl(ObjectAccessControl acl);

    /**
     * Sends a rewrite request to open a rewrite channel.
     *
     * @throws StorageServiceException upon failure
     */
    RewriteResult openRewrite(RewriteOperationRequest rewriteRequest);

    /**
     * Returns the requested bucket or {@code null} if not found.
     *
     * @throws StorageServiceException upon failure
     */
    Bucket get(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Tests whether the caller holds the specified permissions for the specified bucket.
     *
     * @throws StorageServiceException upon failure
     */
    TestIamPermissionsResponse testIamPermissions(String bucket, List<String> permissions, Map<StorageOption, ?> options);

    /**
     * Lists the default object ACL entries for the provided bucket.
     *
     * @throws StorageServiceException upon failure
     */
    List<ObjectAccessControl> listDefaultAcls(String bucket);

    /**
     * Writes the provided bytes to a storage object at the provided location.
     *
     * @throws StorageServiceException upon failure
     */
    void write(String uploadId, byte[] toWrite, int toWriteOffset, long destOffset, int length, boolean last);

    /**
     * Lock retention policy for the provided bucket.
     *
     * @return a {@code Bucket} object of the locked bucket
     * @throws StorageServiceException upon failure
     */
    Bucket lockRetentionPolicy(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Deletes the HMAC key associated with the provided metadata object.
     *
     * @throws StorageServiceException upon failure
     */
    void deleteHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<StorageOption, ?> options);

    /**
     * Deletes the requested bucket.
     *
     * @return {@code true} if the bucket was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean delete(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Creates a new HMAC key for the provided service account email.
     *
     * @throws StorageServiceException upon failure
     */
    HmacKey createHmacKey(String serviceAccountEmail, Map<StorageOption, ?> options);

    /**
     * Reads the given amount of bytes from a storage object at the given position.
     *
     * @throws StorageServiceException upon failure
     */
    Tuple<String, byte[]> read(StorageObject from, Map<StorageOption, ?> options, long position, int bytes);

    /**
     * Deletes the requested storage object.
     *
     * @return {@code true} if the storage object was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean delete(StorageObject object, Map<StorageOption, ?> options);

    /**
     * Creates a new storage object.
     *
     * @throws StorageServiceException upon failure
     */
    StorageObject create(StorageObject object, InputStream content, Map<StorageOption, ?> options);

    /**
     * Returns the requested storage object or {@code null} if not found.
     *
     * @throws StorageServiceException upon failure
     */
    StorageObject get(StorageObject object, Map<StorageOption, ?> options);

    /**
     * Creates a new bucket.
     *
     * @throws StorageServiceException upon failure
     */
    Bucket create(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Updates the storage object's information. Original metadata are merged with metadata in the
     * provided {@code storageObject}.
     *
     * @throws StorageServiceException upon failure
     */
    StorageObject patch(StorageObject storageObject, Map<StorageOption, ?> options);

    /**
     * Deletes the ACL entry for the specified entity on the specified bucket.
     *
     * @return {@code true} if the ACL was deleted, {@code false} if it was not found
     * @throws StorageServiceException upon failure
     */
    boolean deleteAcl(String bucket, String entity, Map<StorageOption, ?> options);

}
