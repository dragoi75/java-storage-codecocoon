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
import com.google.cloud.storage.StorageService.BlobFetchOption;
import com.google.cloud.storage.StorageService.BlobUploadOption;
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
public class StorageBatchOperation {

    private final RpcRequestBatch requestGroup;

    private final StorageRpcClient storageClient;

    private final StorageSettings settings;

    private RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject> createUpdateResultCallback(final StorageSettings serviceSettings, final StorageBatchOutcome<StorageObject> outcome) {
        return new RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject>() {

            @Override
            public void handleSuccess(com.google.api.services.storage.model.StorageObject response) {
                outcome.success(null == response ? null : StorageObject.fromProto(serviceSettings.getService(), response));
            }

            @Override
            public void handleFailure(GoogleJsonError googleJsonError) {
                outcome.error(new StorageOperationException(googleJsonError));
            }
        };
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link StorageService#get(BlobIdentifier, BlobFetchOption...)}. Calling {@link
     * StorageBatchOutcome#get()} on the return value yields the requested {@link StorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageOperationException} if the operation
     * failed.
     */
    public StorageBatchOutcome<StorageObject> get(String containerName, String objectName, BlobFetchOption... settings) {
        return get(BlobIdentifier.create(containerName, objectName), settings);
    }

    private RpcRequestBatch.ResultCallback<Void> createDeleteResultCallback(final StorageBatchOutcome<Boolean> outcome) {
        return new RpcRequestBatch.ResultCallback<Void>() {

            @Override
            public void handleSuccess(Void response) {
                outcome.success(true);
            }

            @Override
            public void handleFailure(GoogleJsonError googleJsonError) {
                StorageOperationException serviceException = new StorageOperationException(googleJsonError);
                if (HTTP_NOT_FOUND != serviceException.getCode()) {
                    outcome.error(serviceException);
                } else {
                    outcome.success(false);
                }
            }
        };
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchOutcome#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageOperationException} if the operation
     * failed.
     */
    public StorageBatchOutcome<Boolean> deleteBlob(String containerName, String objectName, StorageService.BlobSourceOptions... settings) {
        return deleteBlob(BlobIdentifier.create(containerName, objectName), settings);
    }

    private RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject> createGetResultCallback(final StorageSettings serviceSettings, final StorageBatchOutcome<StorageObject> outcome) {
        return new RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject>() {

            @Override
            public void handleSuccess(com.google.api.services.storage.model.StorageObject response) {
                outcome.success(null == response ? null : StorageObject.fromProto(serviceSettings.getService(), response));
            }

            @Override
            public void handleFailure(GoogleJsonError googleJsonError) {
                StorageOperationException serviceException = new StorageOperationException(googleJsonError);
                if (HTTP_NOT_FOUND != serviceException.getCode()) {
                    outcome.error(serviceException);
                } else {
                    outcome.success(null);
                }
            }
        };
    }

    /**
     * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
     * used in the same way as for {@link StorageService#get(BlobIdentifier, BlobFetchOption...)}. Calling {@link
     * StorageBatchOutcome#get()} on the return value yields the requested {@link StorageObject} if successful,
     * {@code null} if no such blob exists, or throws a {@link StorageOperationException} if the operation
     * failed.
     */
    public StorageBatchOutcome<StorageObject> get(BlobIdentifier objectName, BlobFetchOption... settings) {
        StorageBatchOutcome<StorageObject> outcome = new StorageBatchOutcome<>();
        RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject> completionHandler = createGetResultCallback(this.settings, outcome);
        Map<StorageRpcClient.StorageOption, ?> settingMap = DefaultStorageImpl.buildOptionMap(objectName, settings);
        requestGroup.addGetRequest(objectName.toStorageObject(), completionHandler, settingMap);
        return outcome;
    }

    @VisibleForTesting
    StorageSettings getOptions() {
        return settings;
    }

    /**
     * Adds a request representing the "delete blob" operation to this batch. Calling {@link
     * StorageBatchOutcome#get()} on the return value yields {@code true} upon successful deletion,
     * {@code false} if the blob was not found, or throws a {@link StorageOperationException} if the operation
     * failed.
     */
    public StorageBatchOutcome<Boolean> deleteBlob(BlobIdentifier objectName, StorageService.BlobSourceOptions... settings) {
        StorageBatchOutcome<Boolean> outcome = new StorageBatchOutcome<>();
        RpcRequestBatch.ResultCallback<Void> completionHandler = createDeleteResultCallback(outcome);
        Map<StorageRpcClient.StorageOption, ?> settingMap = DefaultStorageImpl.buildOptionMap(objectName, settings);
        requestGroup.addDeleteRequest(objectName.toStorageObject(), completionHandler, settingMap);
        return outcome;
    }

    /**
     * Adds a request representing the "update blob" operation to this batch. The {@code options} can
     * be used in the same way as for {@link StorageService#update(BlobMetadata, BlobUploadOption...)}. Calling
     * {@link StorageBatchOutcome#get()} on the return value yields the updated {@link StorageObject} if
     * successful, or throws a {@link StorageOperationException} if the operation failed.
     */
    public StorageBatchOutcome<StorageObject> updateBlob(BlobMetadata objectMetadata, BlobUploadOption... settings) {
        StorageBatchOutcome<StorageObject> outcome = new StorageBatchOutcome<>();
        RpcRequestBatch.ResultCallback<com.google.api.services.storage.model.StorageObject> completionHandler = createUpdateResultCallback(this.settings, outcome);
        Map<StorageRpcClient.StorageOption, ?> settingMap = DefaultStorageImpl.buildOptionMap(objectMetadata, settings);
        requestGroup.addPatchRequest(objectMetadata.toProto(), completionHandler, settingMap);
        return outcome;
    }

    @VisibleForTesting
    Object getBatch() {
        return requestGroup;
    }

    @VisibleForTesting
    StorageRpcClient getStorageRpc() {
        return storageClient;
    }

    StorageBatchOperation(StorageSettings settings) {
        this.settings = settings;
        this.storageClient = settings.getStorageRpcV1();
        this.requestGroup = storageClient.createBatch();
    }

    /**
     * Submits this batch for processing using a single RPC request.
     */
    public void submitBatch() {
        requestGroup.submitBatch();
    }

}
