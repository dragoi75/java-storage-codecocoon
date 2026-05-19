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
import com.google.cloud.storage.Storage.BlobSourceSettings;
import com.google.cloud.storage.Storage.BlobUploadOption;
import com.google.cloud.storage.spi.v1.RpcBatchRequest;
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

  private final RpcBatchRequest rpcRequestGroup;
  private final StorageRpcClient rpcClient;
  private final StorageSettings storageSettings;

  StorageOperationBatch(StorageSettings storageSettings) {
    this.storageSettings = storageSettings;
    this.rpcClient = storageSettings.getStorageRpcV1();
    this.rpcRequestGroup = rpcClient.createBatch();
  }

  @VisibleForTesting
  Object getBatch() {
    return rpcRequestGroup;
  }

  @VisibleForTesting
  StorageRpcClient getStorageRpc() {
    return rpcClient;
  }

  @VisibleForTesting
  StorageSettings getOptions() {
    return storageSettings;
  }

  /**
   * Adds a request representing the "delete blob" operation to this batch. Calling {@link
   * BatchStorageResult#get()} on the return value yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
   * failed.
   */
  public BatchStorageResult<Boolean> deleteBlob(
          String containerName, String objectName, Storage.BlobSourceSettings... storageSettings) {
    return deleteBlob(BlobIdentifier.create(containerName, objectName), storageSettings);
  }

  /**
   * Adds a request representing the "delete blob" operation to this batch. Calling {@link
   * BatchStorageResult#get()} on the return value yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
   * failed.
   */
  public BatchStorageResult<Boolean> deleteBlob(BlobIdentifier objectName, BlobSourceSettings... storageSettings) {
    BatchStorageResult<Boolean> deleteOutcome = new BatchStorageResult<>();
    RpcBatchRequest.CompletionHandler<Void> completionHandler = createDeleteHandler(deleteOutcome);
    Map<StorageRpcClient.StorageOption, ?> settingsMap = StorageServiceImpl.createOptionMap(objectName, storageSettings);
    rpcRequestGroup.addDeleteOperation(objectName.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /**
   * Adds a request representing the "update blob" operation to this batch. The {@code options} can
   * be used in the same way as for {@link Storage#update(BlobInfo, BlobUploadOption...)}. Calling
   * {@link BatchStorageResult#get()} on the return value yields the updated {@link StorageObject} if
   * successful, or throws a {@link StorageServiceException} if the operation failed.
   */
  public BatchStorageResult<StorageObject> patch(BlobInfo objectInfo, BlobUploadOption... storageSettings) {
    BatchStorageResult<StorageObject> deleteOutcome = new BatchStorageResult<>();
    RpcBatchRequest.CompletionHandler<com.google.api.services.storage.model.StorageObject> completionHandler = createUpdateHandler(this.storageSettings, deleteOutcome);
    Map<StorageRpcClient.StorageOption, ?> settingsMap = StorageServiceImpl.createOptionMap(objectInfo, storageSettings);
    rpcRequestGroup.addPatchOperation(objectInfo.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link Storage#get(BlobIdentifier, BlobGetOptions...)}. Calling {@link
   * BatchStorageResult#get()} on the return value yields the requested {@link StorageObject} if successful,
   * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
   * failed.
   */
  public BatchStorageResult<StorageObject> get(String containerName, String objectName, Storage.BlobGetOptions... storageSettings) {
    return get(BlobIdentifier.create(containerName, objectName), storageSettings);
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link Storage#get(BlobIdentifier, Storage.BlobGetOptions...)}. Calling {@link
   * BatchStorageResult#get()} on the return value yields the requested {@link StorageObject} if successful,
   * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
   * failed.
   */
  public BatchStorageResult<StorageObject> get(BlobIdentifier objectName, BlobGetOptions... storageSettings) {
    BatchStorageResult<StorageObject> deleteOutcome = new BatchStorageResult<>();
    RpcBatchRequest.CompletionHandler<com.google.api.services.storage.model.StorageObject> completionHandler = createGetHandler(this.storageSettings, deleteOutcome);
    Map<StorageRpcClient.StorageOption, ?> settingsMap = StorageServiceImpl.createOptionMap(objectName, storageSettings);
    rpcRequestGroup.addGetRequest(objectName.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /** Submits this batch for processing using a single RPC request. */
  public void execute() {
    rpcRequestGroup.submitBatch();
  }

  private RpcBatchRequest.CompletionHandler<Void> createDeleteHandler(final BatchStorageResult<Boolean> deleteOutcome) {
    return new RpcBatchRequest.CompletionHandler<Void>() {
      @Override
      public void handleSuccess(Void response) {
        deleteOutcome.success(true);
      }

      @Override
      public void handleFailure(GoogleJsonError googleJsonError) {
        StorageServiceException serviceException = new StorageServiceException(googleJsonError);
        if (serviceException.getCode() == HTTP_NOT_FOUND) {
          deleteOutcome.success(false);
        } else {
          deleteOutcome.error(serviceException);
        }
      }
    };
  }

  private RpcBatchRequest.CompletionHandler<com.google.api.services.storage.model.StorageObject> createGetHandler(
          final StorageSettings serviceSettings, final BatchStorageResult<StorageObject> deleteOutcome) {
    return new RpcBatchRequest.CompletionHandler<com.google.api.services.storage.model.StorageObject>() {
      @Override
      public void handleSuccess(com.google.api.services.storage.model.StorageObject response) {
        deleteOutcome.success(
            response == null ? null : StorageObject.fromProto(serviceSettings.getService(), response));
      }

      @Override
      public void handleFailure(GoogleJsonError googleJsonError) {
        StorageServiceException serviceException = new StorageServiceException(googleJsonError);
        if (serviceException.getCode() == HTTP_NOT_FOUND) {
          deleteOutcome.success(null);
        } else {
          deleteOutcome.error(serviceException);
        }
      }
    };
  }

  private RpcBatchRequest.CompletionHandler<com.google.api.services.storage.model.StorageObject> createUpdateHandler(
          final StorageSettings serviceSettings, final BatchStorageResult<StorageObject> deleteOutcome) {
    return new RpcBatchRequest.CompletionHandler<com.google.api.services.storage.model.StorageObject>() {
      @Override
      public void handleSuccess(com.google.api.services.storage.model.StorageObject response) {
        deleteOutcome.success(
            response == null ? null : StorageObject.fromProto(serviceSettings.getService(), response));
      }

      @Override
      public void handleFailure(GoogleJsonError googleJsonError) {
        deleteOutcome.error(new StorageServiceException(googleJsonError));
      }
    };
  }
}
