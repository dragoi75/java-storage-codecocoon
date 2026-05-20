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
import com.google.cloud.storage.StorageClient.BlobGetOptions;
import com.google.cloud.storage.StorageClient.BlobSourceOptions;
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import com.google.cloud.storage.spi.v1.RpcRequestBatch;
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

  private final RpcRequestBatch rpcRequests;
  private final CloudStorageRpc cloudClient;
  private final StorageSettings settings;

  StorageOperationBatch(StorageSettings settings) {
    this.settings = settings;
    this.cloudClient = settings.getStorageRpcV1();
    this.rpcRequests = cloudClient.createBatch();
  }

  @VisibleForTesting
  Object getBatch() {
    return rpcRequests;
  }

  @VisibleForTesting
  CloudStorageRpc getStorageRpc() {
    return cloudClient;
  }

  @VisibleForTesting
  StorageSettings getOptions() {
    return settings;
  }

  /**
   * Adds a request representing the "delete blob" operation to this batch. Calling {@link
   * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageOperationException} if the operation
   * failed.
   */
  public StorageBatchResult<Boolean> deleteBlob(
          String containerName, String objectName, BlobSourceOptions... settings) {
    return deleteBlob(BlobIdentifier.create(containerName, objectName), settings);
  }

  /**
   * Adds a request representing the "delete blob" operation to this batch. Calling {@link
   * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageOperationException} if the operation
   * failed.
   */
  public StorageBatchResult<Boolean> deleteBlob(BlobIdentifier objectName, BlobSourceOptions... settings) {
    StorageBatchResult<Boolean> deleteOutcome = new StorageBatchResult<>();
    RpcRequestBatch.ResultHandler<Void> completionHandler = createDeleteHandler(deleteOutcome);
    Map<CloudStorageRpc.StorageOption, ?> settingsMap = StorageClientImpl.buildOptionMap(objectName, settings);
    rpcRequests.addDeleteRequest(objectName.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /**
   * Adds a request representing the "update blob" operation to this batch. The {@code options} can
   * be used in the same way as for {@link StorageClient#update(BlobMetadata, StorageClient.BlobUploadOption...)}. Calling
   * {@link StorageBatchResult#get()} on the return value yields the updated {@link StorageBlob} if
   * successful, or throws a {@link StorageOperationException} if the operation failed.
   */
  public StorageBatchResult<StorageBlob> updateBlob(BlobMetadata objectMetadata, StorageClient.BlobUploadOption... settings) {
    StorageBatchResult<StorageBlob> deleteOutcome = new StorageBatchResult<>();
    RpcRequestBatch.ResultHandler<StorageObject> completionHandler = createUpdateHandler(this.settings, deleteOutcome);
    Map<CloudStorageRpc.StorageOption, ?> settingsMap = StorageClientImpl.buildOptionMap(objectMetadata, settings);
    rpcRequests.addPatchRequest(objectMetadata.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link StorageClient#get(BlobIdentifier, StorageClient.BlobGetOptions...)}. Calling {@link
   * StorageBatchResult#get()} on the return value yields the requested {@link StorageBlob} if successful,
   * {@code null} if no such blob exists, or throws a {@link StorageOperationException} if the operation
   * failed.
   */
  public StorageBatchResult<StorageBlob> get(String containerName, String objectName, StorageClient.BlobGetOptions... settings) {
    return get(BlobIdentifier.create(containerName, objectName), settings);
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link StorageClient#get(BlobIdentifier, StorageClient.BlobGetOptions...)}. Calling {@link
   * StorageBatchResult#get()} on the return value yields the requested {@link StorageBlob} if successful,
   * {@code null} if no such blob exists, or throws a {@link StorageOperationException} if the operation
   * failed.
   */
  public StorageBatchResult<StorageBlob> get(BlobIdentifier objectName, BlobGetOptions... settings) {
    StorageBatchResult<StorageBlob> deleteOutcome = new StorageBatchResult<>();
    RpcRequestBatch.ResultHandler<StorageObject> completionHandler = createGetHandler(this.settings, deleteOutcome);
    Map<CloudStorageRpc.StorageOption, ?> settingsMap = StorageClientImpl.buildOptionMap(objectName, settings);
    rpcRequests.addGetRequest(objectName.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /** Submits this batch for processing using a single RPC request. */
  public void submitBatch() {
    rpcRequests.submitBatch();
  }

  private RpcRequestBatch.ResultHandler<Void> createDeleteHandler(final StorageBatchResult<Boolean> deleteOutcome) {
    return new RpcRequestBatch.ResultHandler<Void>() {
      @Override
      public void handleSuccess(Void response) {
        deleteOutcome.success(true);
      }

      @Override
      public void handleFailure(GoogleJsonError googleJsonError) {
        StorageOperationException serviceException = new StorageOperationException(googleJsonError);
        if (serviceException.getCode() == HTTP_NOT_FOUND) {
          deleteOutcome.success(false);
        } else {
          deleteOutcome.error(serviceException);
        }
      }
    };
  }

  private RpcRequestBatch.ResultHandler<StorageObject> createGetHandler(
          final StorageSettings serviceSettings, final StorageBatchResult<StorageBlob> deleteOutcome) {
    return new RpcRequestBatch.ResultHandler<StorageObject>() {
      @Override
      public void handleSuccess(StorageObject response) {
        deleteOutcome.success(
            response == null ? null : StorageBlob.fromProto(serviceSettings.getService(), response));
      }

      @Override
      public void handleFailure(GoogleJsonError googleJsonError) {
        StorageOperationException serviceException = new StorageOperationException(googleJsonError);
        if (serviceException.getCode() == HTTP_NOT_FOUND) {
          deleteOutcome.success(null);
        } else {
          deleteOutcome.error(serviceException);
        }
      }
    };
  }

  private RpcRequestBatch.ResultHandler<StorageObject> createUpdateHandler(
          final StorageSettings serviceSettings, final StorageBatchResult<StorageBlob> deleteOutcome) {
    return new RpcRequestBatch.ResultHandler<StorageObject>() {
      @Override
      public void handleSuccess(StorageObject response) {
        deleteOutcome.success(
            response == null ? null : StorageBlob.fromProto(serviceSettings.getService(), response));
      }

      @Override
      public void handleFailure(GoogleJsonError googleJsonError) {
        deleteOutcome.error(new StorageOperationException(googleJsonError));
      }
    };
  }
}
