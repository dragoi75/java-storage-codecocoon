/*
 * Copyright 2016 Google LLC
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

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.cloud.storage.CloudStorageClient.BlobGetOptions;
import com.google.cloud.storage.CloudStorageClient.BlobReadOption;
import com.google.cloud.storage.CloudStorageClient.BlobUploadOption;
import com.google.cloud.storage.spi.v1.RpcRequestBatch;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.annotations.VisibleForTesting;
import java.util.Map;

/**
 * A batch of operations to be submitted to Google Cloud Storage using a single RPC request.
 *
 * <p>Example of using a batch request to delete, update and get a blob:
 *
 * <pre>{@code
 * StorageBatch batch = storage.batch();
 * BlobId firstBlob = BlobId.of("bucket", "blob1"));
 * BlobId secondBlob = BlobId.of("bucket", "blob2"));
 * batch.delete(firstBlob).notify(new BatchResult.Callback<Boolean, StorageException>() {
 *   public void success(Boolean result) {
 *     // deleted successfully
 *   }
 *
 *   public void error(StorageException exception) {
 *     // delete failed
 *   }
 * });
 * batch.update(BlobInfo.builder(secondBlob).contentType("text/plain").build());
 * StorageBatchResult<Blob> result = batch.get(secondBlob);
 * batch.submit();
 * Blob blob = result.get(); // returns get result or throws StorageException
 * }</pre>
 */
public class StorageRpcBatch {

    private final RpcRequestBatch rpcRequestBundle;

    private final StorageRpcClient storageClient;

    private final StorageClientOptions clientConfig;

    private RpcRequestBatch.ResultCallback<Void> buildDeleteCallback(final StorageBatchResult<Boolean> deleteOutcome) {
        return new RpcRequestBatch.ResultCallback<Void>() {

            @Override
            public void handleSuccess(Void response) {
                deleteOutcome.success(true);
            }

            @Override
            public void handleFailure(GoogleJsonError googleJsonError) {
                StorageServiceException serviceException = new StorageServiceException(googleJsonError);
                if (HTTP_NOT_FOUND != serviceException.getCode()) {
                    deleteOutcome.error(serviceException);
                } else {
                    deleteOutcome.success(false);
                }
            }
        };
    }

    /**
     * Submits this batch for processing using a single RPC request.
     */
    public void submitBatch() {
        rpcRequestBundle.submitBatch();
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<Boolean> remove(String containerName, String objectName, BlobReadOption... clientConfig) {
        return remove(BlobIdentifier.from(containerName, objectName), clientConfig);
    }

    @VisibleForTesting
    StorageRpcClient getStorageRpc() {
        return storageClient;
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link CloudStorageClient#get(BlobIdentifier, BlobGetOptions...)}. Calling {@link
     * StorageBatchResult#get()} on the return value yields the requested {@link StorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<StorageObject> get(String containerName, String objectName, BlobGetOptions... clientConfig) {
        return get(BlobIdentifier.from(containerName, objectName), clientConfig);
    }

    private RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject> buildUpdateCallback(final StorageClientOptions clientConfig, final StorageBatchResult<StorageObject> deleteOutcome) {
        return new RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject>() {

            @Override
            public void handleSuccess(com.google.api.services.storage.model.StorageObject response) {
                deleteOutcome.success(null == response ? null : StorageObject.fromProto(clientConfig.getService(), response));
            }

            @Override
            public void handleFailure(GoogleJsonError googleJsonError) {
                deleteOutcome.error(new StorageServiceException(googleJsonError));
            }
        };
    }

    @VisibleForTesting
    StorageClientOptions getOptions() {
        return clientConfig;
    }

    private RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject> buildGetCallback(final StorageClientOptions clientConfig, final StorageBatchResult<StorageObject> deleteOutcome) {
        return new RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject>() {

            @Override
            public void handleSuccess(com.google.api.services.storage.model.StorageObject response) {
                deleteOutcome.success(null == response ? null : StorageObject.fromProto(clientConfig.getService(), response));
            }

            @Override
            public void handleFailure(GoogleJsonError googleJsonError) {
                StorageServiceException serviceException = new StorageServiceException(googleJsonError);
                if (HTTP_NOT_FOUND != serviceException.getCode()) {
                    deleteOutcome.error(serviceException);
                } else {
                    deleteOutcome.success(null);
                }
            }
        };
    }

    /**
     * Adds a request representing the "update blob" operation to this batch. The {@code options} can
     * be used in the same way as for {@link CloudStorageClient#update(BlobMetadata, BlobUploadOption...)}. Calling
     * {@link StorageBatchResult#get()} on the return value yields the updated {@link StorageObject} if
     * successful, or throws a {@link StorageServiceException} if the operation failed.
     */
    public StorageBatchResult<StorageObject> modify(BlobMetadata objectMetadata, BlobUploadOption... clientConfig) {
        StorageBatchResult<StorageObject> deleteOutcome = new StorageBatchResult<>();
        RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject> completionHandler = buildUpdateCallback(this.clientConfig, deleteOutcome);
        Map<StorageRpcClient.StorageOption, ?> settingsMap = StorageServiceImpl.buildOptionMap(objectMetadata, clientConfig);
        rpcRequestBundle.addPatchOperation(objectMetadata.toProto(), completionHandler, settingsMap);
        return deleteOutcome;
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link CloudStorageClient#get(BlobIdentifier, BlobGetOptions...)}. Calling {@link
     * StorageBatchResult#get()} on the return value yields the requested {@link StorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<StorageObject> get(BlobIdentifier objectName, BlobGetOptions... clientConfig) {
        StorageBatchResult<StorageObject> deleteOutcome = new StorageBatchResult<>();
        RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject> completionHandler = buildGetCallback(this.clientConfig, deleteOutcome);
        Map<StorageRpcClient.StorageOption, ?> settingsMap = StorageServiceImpl.buildOptionMap(objectName, clientConfig);
        rpcRequestBundle.addGetRequest(objectName.toProto(), completionHandler, settingsMap);
        return deleteOutcome;
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<Boolean> remove(BlobIdentifier objectName, BlobReadOption... clientConfig) {
        StorageBatchResult<Boolean> deleteOutcome = new StorageBatchResult<>();
        RpcRequestBatch.ResultCallback<Void> completionHandler = buildDeleteCallback(deleteOutcome);
        Map<StorageRpcClient.StorageOption, ?> settingsMap = StorageServiceImpl.buildOptionMap(objectName, clientConfig);
        rpcRequestBundle.addDeleteOperation(objectName.toProto(), completionHandler, settingsMap);
        return deleteOutcome;
    }

    @VisibleForTesting
    Object getBatch() {
        return rpcRequestBundle;
    }

    StorageRpcBatch(StorageClientOptions clientConfig) {
        this.clientConfig = clientConfig;
        this.storageClient = clientConfig.getStorageRpcV1();
        this.rpcRequestBundle = storageClient.createBatch();
    }

}
