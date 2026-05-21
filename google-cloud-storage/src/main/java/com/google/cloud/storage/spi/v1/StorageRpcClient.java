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
import com.google.cloud.storage.StorageOperationException;
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
        VERSIONS("versions"),
        FIELDS("fields"),
        CUSTOMER_SUPPLIED_KEY("customerSuppliedKey"),
        USER_PROJECT("userProject"),
        KMS_KEY_NAME("kmsKeyName"),
        SERVICE_ACCOUNT_EMAIL("serviceAccount"),
        SHOW_DELETED_KEYS("showDeletedKeys");

        private final String optionContent;

        StorageOption(String optionContent) {
            this.optionContent = optionContent;
        }

        public String getValue() {
            return optionContent;
        }

        @SuppressWarnings("unchecked")
        <T> T get(Map<StorageOption, ?> optionMap) {
            return (T) optionMap.get(this);
        }

        String getString(Map<StorageOption, ?> optionMap) {
            return get(optionMap);
        }

        Long getLong(Map<StorageOption, ?> optionMap) {
            return get(optionMap);
        }

        Boolean getBoolean(Map<StorageOption, ?> optionMap) {
            return get(optionMap);
        }
    }

    class ObjectRewriteRequest {

        public final StorageObject source;

        public final Map<StorageOption, ?> sourceOptions;

        public final boolean overrideInfo;

        public final StorageObject target;

        public final Map<StorageOption, ?> targetOptions;

        public final Long megabytesRewrittenPerCall;

        public ObjectRewriteRequest(StorageObject srcObject, Map<StorageOption, ?> srcOptionMap, boolean shouldOverride, StorageObject destObject, Map<StorageOption, ?> destOptionMap, Long megabytesPerCall) {
            this.source = srcObject;
            this.sourceOptions = srcOptionMap;
            this.overrideInfo = shouldOverride;
            this.target = destObject;
            this.targetOptions = destOptionMap;
            this.megabytesRewrittenPerCall = megabytesPerCall;
        }

        @Override
        public boolean equals(Object otherObjectRef) {
            if (null == otherObjectRef) {
                return false;
            }
            if (!(otherObjectRef instanceof ObjectRewriteRequest)) {
                return false;
            }
            final ObjectRewriteRequest thatRequest = (ObjectRewriteRequest) otherObjectRef;
            return Objects.equals(this.source, thatRequest.source) && Objects.equals(this.sourceOptions, thatRequest.sourceOptions) && Objects.equals(this.overrideInfo, thatRequest.overrideInfo) && Objects.equals(this.target, thatRequest.target) && Objects.equals(this.targetOptions, thatRequest.targetOptions) && Objects.equals(this.megabytesRewrittenPerCall, thatRequest.megabytesRewrittenPerCall);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, sourceOptions, overrideInfo, target, targetOptions, megabytesRewrittenPerCall);
        }
    }

    class RewriteOperationResult {

        public final ObjectRewriteRequest rewriteRequest;

        public final StorageObject result;

        public final long blobSize;

        public final boolean isDone;

        public final String rewriteToken;

        public final long totalBytesRewritten;

        public RewriteOperationResult(ObjectRewriteRequest request, StorageObject returnedObject, long sizeInBytes, boolean completed, String continuationToken, long bytesRewrittenTotal) {
            this.rewriteRequest = request;
            this.result = returnedObject;
            this.blobSize = sizeInBytes;
            this.isDone = completed;
            this.rewriteToken = continuationToken;
            this.totalBytesRewritten = bytesRewrittenTotal;
        }

        @Override
        public boolean equals(Object otherObjectRef) {
            if (null == otherObjectRef) {
                return false;
            }
            if (!(otherObjectRef instanceof RewriteOperationResult)) {
                return false;
            }
            final RewriteOperationResult thatRequest = (RewriteOperationResult) otherObjectRef;
            return Objects.equals(this.rewriteRequest, thatRequest.rewriteRequest) && Objects.equals(this.result, thatRequest.result) && Objects.equals(this.rewriteToken, thatRequest.rewriteToken) && thatRequest.blobSize == this.blobSize && Objects.equals(this.isDone, thatRequest.isDone) && thatRequest.totalBytesRewritten == this.totalBytesRewritten;
        }

        @Override
        public int hashCode() {
            return Objects.hash(rewriteRequest, result, blobSize, isDone, rewriteToken, totalBytesRewritten);
        }
    }

    /**
     * Creates a new bucket.
     *
     * @throws StorageOperationException upon failure
     */
    Bucket create(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Creates a new storage object.
     *
     * @throws StorageOperationException upon failure
     */
    StorageObject create(StorageObject object, InputStream content, Map<StorageOption, ?> options);

    /**
     * Lists the project's buckets.
     *
     * @throws StorageOperationException upon failure
     */
    Tuple<String, Iterable<Bucket>> list(Map<StorageOption, ?> options);

    /**
     * Lists the bucket's blobs.
     *
     * @throws StorageOperationException upon failure
     */
    Tuple<String, Iterable<StorageObject>> list(String bucket, Map<StorageOption, ?> options);

    /**
     * Returns the requested bucket or {@code null} if not found.
     *
     * @throws StorageOperationException upon failure
     */
    Bucket get(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Returns the requested storage object or {@code null} if not found.
     *
     * @throws StorageOperationException upon failure
     */
    StorageObject get(StorageObject object, Map<StorageOption, ?> options);

    /**
     * Updates bucket information.
     *
     * @throws StorageOperationException upon failure
     */
    Bucket patch(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Updates the storage object's information. Original metadata are merged with metadata in the
     * provided {@code storageObject}.
     *
     * @throws StorageOperationException upon failure
     */
    StorageObject patch(StorageObject storageObject, Map<StorageOption, ?> options);

    /**
     * Deletes the requested bucket.
     *
     * @return {@code true} if the bucket was deleted, {@code false} if it was not found
     * @throws StorageOperationException upon failure
     */
    boolean delete(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Deletes the requested storage object.
     *
     * @return {@code true} if the storage object was deleted, {@code false} if it was not found
     * @throws StorageOperationException upon failure
     */
    boolean delete(StorageObject object, Map<StorageOption, ?> options);

    /**
     * Creates an empty batch.
     */
    RpcRequestBatch createBatch();

    /**
     * Sends a compose request.
     *
     * @throws StorageOperationException upon failure
     */
    StorageObject compose(Iterable<StorageObject> sources, StorageObject target, Map<StorageOption, ?> targetOptions);

    /**
     * Reads all the bytes from a storage object.
     *
     * @throws StorageOperationException upon failure
     */
    byte[] load(StorageObject storageObject, Map<StorageOption, ?> options);

    /**
     * Reads the given amount of bytes from a storage object at the given position.
     *
     * @throws StorageOperationException upon failure
     */
    Tuple<String, byte[]> read(StorageObject from, Map<StorageOption, ?> options, long position, int bytes);

    /**
     * Reads all the bytes from a storage object at the given position in to outputstream using direct
     * download.
     *
     * @return number of bytes downloaded, returns 0 if position higher than length.
     * @throws StorageOperationException upon failure
     */
    long read(StorageObject from, Map<StorageOption, ?> options, long position, OutputStream outputStream);

    /**
     * Opens a resumable upload channel for a given storage object.
     *
     * @throws StorageOperationException upon failure
     */
    String open(StorageObject object, Map<StorageOption, ?> options);

    /**
     * Opens a resumable upload channel for a given signedURL.
     *
     * @throws StorageOperationException upon failure
     */
    String open(String signedURL);

    /**
     * Writes the provided bytes to a storage object at the provided location.
     *
     * @throws StorageOperationException upon failure
     */
    void write(String uploadId, byte[] toWrite, int toWriteOffset, long destOffset, int length, boolean last);

    /**
     * Sends a rewrite request to open a rewrite channel.
     *
     * @throws StorageOperationException upon failure
     */
    RewriteOperationResult openRewrite(ObjectRewriteRequest rewriteRequest);

    /**
     * Continues rewriting on an already open rewrite channel.
     *
     * @throws StorageOperationException upon failure
     */
    RewriteOperationResult continueRewrite(RewriteOperationResult previousResponse);

    /**
     * Returns the ACL entry for the specified entity on the specified bucket or {@code null} if not
     * found.
     *
     * @throws StorageOperationException upon failure
     */
    BucketAccessControl getAcl(String bucket, String entity, Map<StorageOption, ?> options);

    /**
     * Deletes the ACL entry for the specified entity on the specified bucket.
     *
     * @return {@code true} if the ACL was deleted, {@code false} if it was not found
     * @throws StorageOperationException upon failure
     */
    boolean deleteAcl(String bucket, String entity, Map<StorageOption, ?> options);

    /**
     * Creates a new ACL entry on the specified bucket.
     *
     * @throws StorageOperationException upon failure
     */
    BucketAccessControl createAcl(BucketAccessControl acl, Map<StorageOption, ?> options);

    /**
     * Updates an ACL entry on the specified bucket.
     *
     * @throws StorageOperationException upon failure
     */
    BucketAccessControl patchAcl(BucketAccessControl acl, Map<StorageOption, ?> options);

    /**
     * Lists the ACL entries for the provided bucket.
     *
     * @throws StorageOperationException upon failure
     */
    List<BucketAccessControl> listAcls(String bucket, Map<StorageOption, ?> options);

    /**
     * Returns the default object ACL entry for the specified entity on the specified bucket or {@code
     * null} if not found.
     *
     * @throws StorageOperationException upon failure
     */
    ObjectAccessControl getDefaultAcl(String bucket, String entity);

    /**
     * Deletes the default object ACL entry for the specified entity on the specified bucket.
     *
     * @return {@code true} if the ACL was deleted, {@code false} if it was not found
     * @throws StorageOperationException upon failure
     */
    boolean deleteDefaultAcl(String bucket, String entity);

    /**
     * Creates a new default object ACL entry on the specified bucket.
     *
     * @throws StorageOperationException upon failure
     */
    ObjectAccessControl createDefaultAcl(ObjectAccessControl acl);

    /**
     * Updates a default object ACL entry on the specified bucket.
     *
     * @throws StorageOperationException upon failure
     */
    ObjectAccessControl patchDefaultAcl(ObjectAccessControl acl);

    /**
     * Lists the default object ACL entries for the provided bucket.
     *
     * @throws StorageOperationException upon failure
     */
    List<ObjectAccessControl> listDefaultAcls(String bucket);

    /**
     * Returns the ACL entry for the specified entity on the specified object or {@code null} if not
     * found.
     *
     * @throws StorageOperationException upon failure
     */
    ObjectAccessControl getAcl(String bucket, String object, Long generation, String entity);

    /**
     * Deletes the ACL entry for the specified entity on the specified object.
     *
     * @return {@code true} if the ACL was deleted, {@code false} if it was not found
     * @throws StorageOperationException upon failure
     */
    boolean deleteAcl(String bucket, String object, Long generation, String entity);

    /**
     * Creates a new ACL entry on the specified object.
     *
     * @throws StorageOperationException upon failure
     */
    ObjectAccessControl createAcl(ObjectAccessControl acl);

    /**
     * Updates an ACL entry on the specified object.
     *
     * @throws StorageOperationException upon failure
     */
    ObjectAccessControl patchAcl(ObjectAccessControl acl);

    /**
     * Lists the ACL entries for the provided object.
     *
     * @throws StorageOperationException upon failure
     */
    List<ObjectAccessControl> listAcls(String bucket, String object, Long generation);

    /**
     * Creates a new HMAC key for the provided service account email.
     *
     * @throws StorageOperationException upon failure
     */
    HmacKey createHmacKey(String serviceAccountEmail, Map<StorageOption, ?> options);

    /**
     * Lists the HMAC keys for the provided service account email.
     *
     * @throws StorageOperationException upon failure
     */
    Tuple<String, Iterable<HmacKeyMetadata>> listHmacKeys(Map<StorageOption, ?> options);

    /**
     * Updates an HMAC key for the provided metadata object and returns the updated object. Only
     * updates the State field.
     *
     * @throws StorageOperationException upon failure
     */
    HmacKeyMetadata updateHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<StorageOption, ?> options);

    /**
     * Returns the HMAC key associated with the provided access id.
     *
     * @throws StorageOperationException upon failure
     */
    HmacKeyMetadata getHmacKey(String accessId, Map<StorageOption, ?> options);

    /**
     * Deletes the HMAC key associated with the provided metadata object.
     *
     * @throws StorageOperationException upon failure
     */
    void deleteHmacKey(HmacKeyMetadata hmacKeyMetadata, Map<StorageOption, ?> options);

    /**
     * Returns the IAM policy for the specified bucket.
     *
     * @throws StorageOperationException upon failure
     */
    Policy getIamPolicy(String bucket, Map<StorageOption, ?> options);

    /**
     * Updates the IAM policy for the specified bucket.
     *
     * @throws StorageOperationException upon failure
     */
    Policy setIamPolicy(String bucket, Policy policy, Map<StorageOption, ?> options);

    /**
     * Tests whether the caller holds the specified permissions for the specified bucket.
     *
     * @throws StorageOperationException upon failure
     */
    TestIamPermissionsResponse testIamPermissions(String bucket, List<String> permissions, Map<StorageOption, ?> options);

    /**
     * Deletes the notification with the specified name on the specified object.
     *
     * @return {@code true} if the notification was deleted, {@code false} if it was not found
     * @throws StorageOperationException upon failure
     */
    boolean deleteNotification(String bucket, String notification);

    /**
     * List the notifications for the provided bucket.
     *
     * @return a list of {@link Notification} objects that exist on the bucket.
     * @throws StorageOperationException upon failure
     */
    List<Notification> listNotifications(String bucket);

    /**
     * Creates a notification with the specified entity on the specified bucket.
     *
     * @return the notification that was created.
     * @throws StorageOperationException upon failure
     */
    Notification createNotification(String bucket, Notification notification);

    /**
     * Lock retention policy for the provided bucket.
     *
     * @return a {@code Bucket} object of the locked bucket
     * @throws StorageOperationException upon failure
     */
    Bucket lockRetentionPolicy(Bucket bucket, Map<StorageOption, ?> options);

    /**
     * Returns the service account associated with the given project.
     *
     * @return the ID of the project to fetch the service account for.
     * @throws StorageOperationException upon failure
     */
    ServiceAccount getServiceAccount(String projectId);
}
