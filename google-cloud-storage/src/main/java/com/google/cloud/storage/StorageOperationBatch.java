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
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.storage.StorageService.BlobGetOptions;
import com.google.cloud.storage.StorageService.BlobUploadOption;
import com.google.cloud.storage.spi.v1.RpcBatchBuilder;
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

    private final RpcBatchBuilder rpcBuilder;

    private final StorageRpcClient rpcClient;

    private final StorageSettings settings;

    StorageOperationBatch(StorageSettings settings) {
        this.settings = settings;
        this.rpcClient = settings.getStorageRpcV1();
        this.rpcBuilder = rpcClient.createBatch();
    }

    @VisibleForTesting
    Object getBatch() {
        return rpcBuilder;
    }

    @VisibleForTesting
    StorageRpcClient getStorageRpc() {
        return rpcClient;
    }

    @VisibleForTesting
    StorageSettings getOptions() {
        return settings;
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<Boolean> deleteBlob(String containerName, String objectName, StorageService.BlobReadOption... settings) {
        return deleteBlob(BlobIdentifier.create(containerName, objectName), settings);
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<Boolean> deleteBlob(BlobIdentifier objectName, StorageService.BlobReadOption... settings) {
        StorageBatchResult<Boolean> deleteOutcome = new StorageBatchResult<>();
        RpcBatchBuilder.ResultCallback<Void> resultHandler = buildDeleteCallback(deleteOutcome);
        Map<StorageRpcClient.StorageOption, ?> storageSettingsMap = StorageServiceImpl.buildOptionMap(objectName, settings);
        rpcBuilder.addDeletion(objectName.toProto(), resultHandler, storageSettingsMap);
        return deleteOutcome;
    }

    /**
     * Adds a request representing the "update blob" operation to this batch. The {@code options} can
     * be used in the same way as for {@link StorageService#update(BlobMetadata, BlobUploadOption...)}. Calling
     * {@link StorageBatchResult#get()} on the return value yields the updated {@link CloudStorageObject} if
     * successful, or throws a {@link StorageServiceException} if the operation failed.
     */
    public StorageBatchResult<CloudStorageObject> updateBlob(BlobMetadata metadata, BlobUploadOption... settings) {
        StorageBatchResult<CloudStorageObject> deleteOutcome = new StorageBatchResult<>();
        RpcBatchBuilder.ResultCallback<StorageObject> resultHandler = buildUpdateCallback(this.settings, deleteOutcome);
        Map<StorageRpcClient.StorageOption, ?> storageSettingsMap = StorageServiceImpl.buildOptionMap(metadata, settings);
        rpcBuilder.addUpdate(metadata.toProto(), resultHandler, storageSettingsMap);
        return deleteOutcome;
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link StorageService#get(BlobIdentifier, StorageService.BlobGetOptions...)}. Calling {@link
     * StorageBatchResult#get()} on the return value yields the requested {@link CloudStorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<CloudStorageObject> get(String containerName, String objectName, BlobGetOptions... settings) {
        return get(BlobIdentifier.create(containerName, objectName), settings);
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link StorageService#get(BlobIdentifier, BlobGetOptions...)}. Calling {@link
     * StorageBatchResult#get()} on the return value yields the requested {@link CloudStorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
     * failed.
     */
    public StorageBatchResult<CloudStorageObject> get(BlobIdentifier objectName, StorageService.BlobGetOptions... settings) {
        StorageBatchResult<CloudStorageObject> deleteOutcome = new StorageBatchResult<>();
        RpcBatchBuilder.ResultCallback<StorageObject> resultHandler = buildGetCallback(this.settings, deleteOutcome);
        Map<StorageRpcClient.StorageOption, ?> storageSettingsMap = StorageServiceImpl.buildOptionMap(objectName, settings);
        rpcBuilder.addFetch(objectName.toProto(), resultHandler, storageSettingsMap);
        return deleteOutcome;
    }

    /**
     * Submits this batch for processing using a single RPC request.
     */
    public void submitBatch() {
        rpcBuilder.execute();
    }

    private RpcBatchBuilder.ResultCallback<Void> buildDeleteCallback(final StorageBatchResult<Boolean> deleteOutcome) {
        return new RpcBatchBuilder.ResultCallback<Void>() {

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

    private RpcBatchBuilder.ResultCallback<StorageObject> buildGetCallback(final StorageSettings serviceSettings, final StorageBatchResult<CloudStorageObject> deleteOutcome) {
        return new RpcBatchBuilder.ResultCallback<StorageObject>() {

            @Override
            public void handleSuccess(StorageObject response) {
                deleteOutcome.success(null == response ? null : CloudStorageObject.fromProto(serviceSettings.getService(), response));
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

    private RpcBatchBuilder.ResultCallback<StorageObject> buildUpdateCallback(final StorageSettings serviceSettings, final StorageBatchResult<CloudStorageObject> deleteOutcome) {
        return new RpcBatchBuilder.ResultCallback<StorageObject>() {

            @Override
            public void handleSuccess(StorageObject response) {
                deleteOutcome.success(null == response ? null : CloudStorageObject.fromProto(serviceSettings.getService(), response));
            }

            @Override
            public void handleFailure(GoogleJsonError googleJsonError) {
                deleteOutcome.error(new StorageServiceException(googleJsonError));
            }
        };
    }
}
