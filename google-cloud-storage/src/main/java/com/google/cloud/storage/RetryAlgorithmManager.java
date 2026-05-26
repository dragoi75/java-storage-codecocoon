/*
 * Copyright 2021 Google LLC
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

import com.google.api.gax.retrying.ResultRetryAlgorithm;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.HmacKeyMetadata;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.api.services.storage.model.Policy;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteOperationRequest;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

final class RetryAlgorithmManager implements Serializable {

    private static final long serialVersionUID = -8615379702537758604L;

    private final StorageRetryStrategy retryStrategy;

    public ResultRetryAlgorithm<?> getForObjectsCompose(List<StorageObject> sources, StorageObject target, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return optionsMap.containsKey(StorageRpcClient.StorageOption.IF_GENERATION_MATCH) ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectsDelete(StorageObject pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return optionsMap.containsKey(StorageRpcClient.StorageOption.IF_GENERATION_MATCH) ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForDefaultObjectAclUpdate(ObjectAccessControl pb) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectsUpdate(StorageObject pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return optionsMap.containsKey(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH) ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectsRewrite(RewriteOperationRequest pb) {
        return pb.targetOptions.containsKey(StorageRpcClient.StorageOption.IF_GENERATION_MATCH) ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsList(Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectAclList(String bucket, String name, Long generation) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectsList(String bucket, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForHmacKeyList(Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsCreate(Bucket pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsDelete(Bucket pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForServiceAccountGet(String pb) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForHmacKeyDelete(HmacKeyMetadata pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsUpdate(Bucket pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        // TODO: Include etag when it is supported by the library
        return optionsMap.containsKey(StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH) ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectsCreate(StorageObject pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        if (null != pb.getGeneration() && 0 == pb.getGeneration()) {
            return retryStrategy.getIdempotentHandler();
        }
        return optionsMap.containsKey(StorageRpcClient.StorageOption.IF_GENERATION_MATCH) ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    RetryAlgorithmManager(StorageRetryStrategy retryStrategy) {
        this.retryStrategy = retryStrategy;
    }

    public ResultRetryAlgorithm<?> getForResumableUploadSessionCreate(Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return optionsMap.containsKey(StorageRpcClient.StorageOption.IF_GENERATION_MATCH) ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForDefaultObjectAclGet(String pb) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectsGet(StorageObject pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForHmacKeyUpdate(HmacKeyMetadata pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        // TODO: Include etag when it is supported by the library
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForDefaultObjectAclList(String pb) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectAclCreate(ObjectAccessControl aclPb) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectAclDelete(String bucket, String name, Long generation, String pb) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsLockRetentionPolicy(Bucket pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        // Always idempotent because IfMetagenerationMatch is required
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForResumableUploadSessionWrite(Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return optionsMap.containsKey(StorageRpcClient.StorageOption.IF_GENERATION_MATCH) ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsTestIamPermissions(String bucket, List<String> permissions, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketAclUpdate(BucketAccessControl pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForDefaultObjectAclCreate(ObjectAccessControl pb) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForDefaultObjectAclDelete(String pb) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForHmacKeyCreate(String pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectAclGet(String bucket, String name, Long generation, String pb) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsGet(Bucket pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsGetIamPolicy(String bucket, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketAclCreate(BucketAccessControl pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketAclGet(String pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForObjectAclUpdate(ObjectAccessControl aclPb) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForHmacKeyGet(String accessId, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketsSetIamPolicy(String bucket, Policy pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return null != pb.getEtag() ? retryStrategy.getIdempotentHandler() : retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketAclDelete(String pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getNonidempotentHandler();
    }

    public ResultRetryAlgorithm<?> getForBucketAclList(String pb, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        return retryStrategy.getIdempotentHandler();
    }

}
