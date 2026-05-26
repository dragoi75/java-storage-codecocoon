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
import com.google.cloud.storage.StorageClient.BlobGetOptions;
import com.google.cloud.storage.StorageClient.BlobSourceOptions;
import com.google.cloud.storage.spi.v1.RpcBatch;
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient;
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

    private final RpcBatch rpcGroup;

    private final CloudStorageRpcClient cloudClient;

    private final StorageSettings storageSettings;

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageOperationException} if the operation
     * failed.
     */
    public StorageBatchResult<Boolean> remove(String containerName, String objectName, BlobSourceOptions... storageSettings) {
        return remove(BlobIdentifier.create(containerName, objectName), storageSettings);
    }

    private RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> buildUpdateCallback(final StorageSettings serviceSettings, final StorageBatchResult<StorageObject> deleteOutcome) {
        return new RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>() {

            @Override
            public void onSuccess(com.google.api.services.storage.model.StorageObject response) {
                deleteOutcome.success(null == response ? null : StorageObject.fromProto(serviceSettings.getService(), response));
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError) {
                deleteOutcome.error(new StorageOperationException(googleJsonError));
            }
        };
    }

    private RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> buildGetCallback(final StorageSettings serviceSettings, final StorageBatchResult<StorageObject> deleteOutcome) {
        return new RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>() {

            @Override
            public void onSuccess(com.google.api.services.storage.model.StorageObject response) {
                deleteOutcome.success(null == response ? null : StorageObject.fromProto(serviceSettings.getService(), response));
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError) {
                StorageOperationException serviceException = new StorageOperationException(googleJsonError);
                if (HTTP_NOT_FOUND != serviceException.getCode()) {
                    deleteOutcome.error(serviceException);
                } else {
                    deleteOutcome.success(null);
                }
            }
        };
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link StorageClient#get(BlobIdentifier, BlobGetOptions...)}. Calling {@link
     * StorageBatchResult#get()} on the return value yields the requested {@link StorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageOperationException} if the operation
     * failed.
     */
    public StorageBatchResult<StorageObject> get(BlobIdentifier objectName, BlobGetOptions... storageSettings) {
        StorageBatchResult<StorageObject> deleteOutcome = new StorageBatchResult<>();
        RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> completionHandler = buildGetCallback(this.storageSettings, deleteOutcome);
        Map<CloudStorageRpcClient.StorageOption, ?> settingsMap = StorageImpl.optionMap(objectName, storageSettings);
        rpcGroup.addGet(objectName.toStorageObject(), completionHandler, settingsMap);
        return deleteOutcome;
    }

    /**
     * Submits this batch for processing using a single RPC request.
     */
    public void commit() {
        rpcGroup.submit();
    }

    @VisibleForTesting
    CloudStorageRpcClient getStorageRpc() {
        return cloudClient;
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageOperationException} if the operation
     * failed.
     */
    public StorageBatchResult<Boolean> remove(BlobIdentifier objectName, BlobSourceOptions... storageSettings) {
        StorageBatchResult<Boolean> deleteOutcome = new StorageBatchResult<>();
        RpcBatch.Callback<Void> completionHandler = buildDeleteCallback(deleteOutcome);
        Map<CloudStorageRpcClient.StorageOption, ?> settingsMap = StorageImpl.optionMap(objectName, storageSettings);
        rpcGroup.addDelete(objectName.toStorageObject(), completionHandler, settingsMap);
        return deleteOutcome;
    }

    /**
     * Adds a request representing the "update blob" operation to this batch. The {@code options} can
     * be used in the same way as for {@link StorageClient#update(BlobAttributes, StorageClient.BlobUploadOption...)}. Calling
     * {@link StorageBatchResult#get()} on the return value yields the updated {@link StorageObject} if
     * successful, or throws a {@link StorageOperationException} if the operation failed.
     */
    public StorageBatchResult<StorageObject> patch(BlobAttributes objectAttributes, StorageClient.BlobUploadOption... storageSettings) {
        StorageBatchResult<StorageObject> deleteOutcome = new StorageBatchResult<>();
        RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> completionHandler = buildUpdateCallback(this.storageSettings, deleteOutcome);
        Map<CloudStorageRpcClient.StorageOption, ?> settingsMap = StorageImpl.optionMap(objectAttributes, storageSettings);
        rpcGroup.addPatch(objectAttributes.toProto(), completionHandler, settingsMap);
        return deleteOutcome;
    }

    @VisibleForTesting
    StorageSettings getOptions() {
        return storageSettings;
    }

    @VisibleForTesting
    Object getBatch() {
        return rpcGroup;
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link StorageClient#get(BlobIdentifier, BlobGetOptions...)}. Calling {@link
     * StorageBatchResult#get()} on the return value yields the requested {@link StorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageOperationException} if the operation
     * failed.
     */
    public StorageBatchResult<StorageObject> get(String containerName, String objectName, BlobGetOptions... storageSettings) {
        return get(BlobIdentifier.create(containerName, objectName), storageSettings);
    }

    private RpcBatch.Callback<Void> buildDeleteCallback(final StorageBatchResult<Boolean> deleteOutcome) {
        return new RpcBatch.Callback<Void>() {

            @Override
            public void onSuccess(Void response) {
                deleteOutcome.success(true);
            }

            @Override
            public void onFailure(GoogleJsonError googleJsonError) {
                StorageOperationException serviceException = new StorageOperationException(googleJsonError);
                if (HTTP_NOT_FOUND != serviceException.getCode()) {
                    deleteOutcome.error(serviceException);
                } else {
                    deleteOutcome.success(false);
                }
            }
        };
    }

    StorageOperationBatch(StorageSettings storageSettings) {
        this.storageSettings = storageSettings;
        this.cloudClient = storageSettings.getStorageRpcV1();
        this.rpcGroup = cloudClient.createBatch();
    }

}
