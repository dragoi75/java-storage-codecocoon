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
import com.google.cloud.storage.Storage.BlobGetOptions;
import com.google.cloud.storage.Storage.BlobSourceOptions;
import com.google.cloud.storage.Storage.BlobUploadOption;
import com.google.cloud.storage.spi.v1.RpcBatch;
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
public class StorageOperationBatch {

    private final RpcBatch rpcBundle;

    private final StorageRpcClient rpcClient;

    private final StorageClientOptions clientConfig;

    /**
     * Submits this batch for processing using a single RPC request.
     */
    public void submitBatch() {
        rpcBundle.submit();
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<Boolean> remove(String containerName, String objectName, BlobSourceOptions... clientConfig) {
        return remove(BlobId.from(containerName, objectName), clientConfig);
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link Storage#get(BlobId, BlobGetOptions...)}. Calling {@link
     * StorageBatchResult#get()} on the return value yields the requested {@link StorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<StorageObject> get(BlobId objectName, BlobGetOptions... clientConfig) {
        StorageBatchResult<StorageObject> removalOutcome = new StorageBatchResult<>();
        RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> completionHandler = createFetchCallback(this.clientConfig, removalOutcome);
        Map<StorageRpcClient.StorageOption, ?> settingMap = StorageImpl.optionMap(objectName, clientConfig);
        rpcBundle.addGet(objectName.toProto(), completionHandler, settingMap);
        return removalOutcome;
    }

    private RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> createPatchCallback(final StorageClientOptions clientConfig, final StorageBatchResult<StorageObject> removalOutcome) {
        return new RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>() {

            @Override
            public void onSuccess(com.google.api.services.storage.model.StorageObject response) {
                removalOutcome.success(null == response ? null : StorageObject.fromProto(clientConfig.getService(), response));
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError) {
                removalOutcome.error(new StorageServiceException(googleJsonError));
            }
        };
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link Storage#get(BlobId, BlobGetOptions...)}. Calling {@link
     * StorageBatchResult#get()} on the return value yields the requested {@link StorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<StorageObject> get(String containerName, String objectName, BlobGetOptions... clientConfig) {
        return get(BlobId.from(containerName, objectName), clientConfig);
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<Boolean> remove(BlobId objectName, BlobSourceOptions... clientConfig) {
        StorageBatchResult<Boolean> removalOutcome = new StorageBatchResult<>();
        RpcBatch.Callback<Void> completionHandler = createDeletionCallback(removalOutcome);
        Map<StorageRpcClient.StorageOption, ?> settingMap = StorageImpl.optionMap(objectName, clientConfig);
        rpcBundle.addDelete(objectName.toProto(), completionHandler, settingMap);
        return removalOutcome;
    }

    private RpcBatch.Callback<Void> createDeletionCallback(final StorageBatchResult<Boolean> removalOutcome) {
        return new RpcBatch.Callback<Void>() {

            @Override
            public void onSuccess(Void response) {
                removalOutcome.success(true);
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError) {
                StorageServiceException serviceException = new StorageServiceException(googleJsonError);
                if (HTTP_NOT_FOUND != serviceException.getCode()) {
                    removalOutcome.error(serviceException);
                } else {
                    removalOutcome.success(false);
                }
            }
        };
    }

    /**
     * Adds a request representing the "update blob" operation to this batch. The {@code options} can
     * be used in the same way as for {@link Storage#update(BlobMetadata, BlobUploadOption...)}. Calling
     * {@link StorageBatchResult#get()} on the return value yields the updated {@link StorageObject} if
     * successful, or throws a {@link StorageServiceException} if the operation failed.
     */
    public StorageBatchResult<StorageObject> modify(BlobMetadata metadata, BlobUploadOption... clientConfig) {
        StorageBatchResult<StorageObject> removalOutcome = new StorageBatchResult<>();
        RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> completionHandler = createPatchCallback(this.clientConfig, removalOutcome);
        Map<StorageRpcClient.StorageOption, ?> settingMap = StorageImpl.optionMap(metadata, clientConfig);
        rpcBundle.addPatch(metadata.toProto(), completionHandler, settingMap);
        return removalOutcome;
    }

    StorageOperationBatch(StorageClientOptions clientConfig) {
        this.clientConfig = clientConfig;
        this.rpcClient = clientConfig.getStorageRpcV1();
        this.rpcBundle = rpcClient.createBatch();
    }

    private RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> createFetchCallback(final StorageClientOptions clientConfig, final StorageBatchResult<StorageObject> removalOutcome) {
        return new RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>() {

            @Override
            public void onSuccess(com.google.api.services.storage.model.StorageObject response) {
                removalOutcome.success(null == response ? null : StorageObject.fromProto(clientConfig.getService(), response));
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError) {
                StorageServiceException serviceException = new StorageServiceException(googleJsonError);
                if (HTTP_NOT_FOUND != serviceException.getCode()) {
                    removalOutcome.error(serviceException);
                } else {
                    removalOutcome.success(null);
                }
            }
        };
    }

    @VisibleForTesting
    StorageClientOptions getOptions() {
        return clientConfig;
    }

    @VisibleForTesting
    Object getBatch() {
        return rpcBundle;
    }

    @VisibleForTesting
    StorageRpcClient getStorageRpc() {
        return rpcClient;
    }

}
