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
import com.google.cloud.storage.CloudStorageClient.BlobGetOptions;
import com.google.cloud.storage.CloudStorageClient.BlobReadOption;
import com.google.cloud.storage.CloudStorageClient.BlobUploadOptions;
import com.google.cloud.storage.spi.v1.RpcRequestBatch;
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
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

  private final RpcRequestBatch requestBundle;
  private final StorageServiceRpc storageService;
  private final StorageClientOptions clientConfig;

  StorageOperationBatch(StorageClientOptions clientConfig) {
    this.clientConfig = clientConfig;
    this.storageService = clientConfig.getStorageRpcV1();
    this.requestBundle = storageService.createBatch();
  }

  @VisibleForTesting
  Object getBatch() {
    return requestBundle;
  }

  @VisibleForTesting
  StorageServiceRpc getStorageRpc() {
    return storageService;
  }

  @VisibleForTesting
  StorageClientOptions getOptions() {
    return clientConfig;
  }

  /**
   * Adds a request representing the "delete blob" operation to this batch. Calling {@link
   * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
   * failed.
   */
  public StorageBatchResult<Boolean> remove(
          String containerName, String objectName, CloudStorageClient.BlobReadOption... clientConfig) {
    return remove(BlobIdentifier.create(containerName, objectName), clientConfig);
  }

  /**
   * Adds a request representing the "delete blob" operation to this batch. Calling {@link
   * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageServiceException} if the operation
   * failed.
   */
  public StorageBatchResult<Boolean> remove(BlobIdentifier objectName, BlobReadOption... clientConfig) {
    StorageBatchResult<Boolean> deleteOutcome = new StorageBatchResult<>();
    RpcRequestBatch.CompletionHandler<Void> completionHandler = buildDeleteCallback(deleteOutcome);
    Map<StorageServiceRpc.StorageOption, ?> settingsMap = DefaultStorage.buildOptionMap(objectName, clientConfig);
    requestBundle.addDeleteRequest(objectName.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /**
   * Adds a request representing the "update blob" operation to this batch. The {@code options} can
   * be used in the same way as for {@link CloudStorageClient#update(BlobMetadata, CloudStorageClient.BlobUploadOptions...)}. Calling
   * {@link StorageBatchResult#get()} on the return value yields the updated {@link StorageBlob} if
   * successful, or throws a {@link StorageServiceException} if the operation failed.
   */
  public StorageBatchResult<StorageBlob> patch(BlobMetadata objectMetadata, BlobUploadOptions... clientConfig) {
    StorageBatchResult<StorageBlob> deleteOutcome = new StorageBatchResult<>();
    RpcRequestBatch.CompletionHandler<StorageObject> completionHandler = buildUpdateCallback(this.clientConfig, deleteOutcome);
    Map<StorageServiceRpc.StorageOption, ?> settingsMap = DefaultStorage.buildOptionMap(objectMetadata, clientConfig);
    requestBundle.addPatchRequest(objectMetadata.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link CloudStorageClient#get(BlobIdentifier, BlobGetOptions...)}. Calling {@link
   * StorageBatchResult#get()} on the return value yields the requested {@link StorageBlob} if successful,
   * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
   * failed.
   */
  public StorageBatchResult<StorageBlob> get(String containerName, String objectName, CloudStorageClient.BlobGetOptions... clientConfig) {
    return get(BlobIdentifier.create(containerName, objectName), clientConfig);
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link CloudStorageClient#get(BlobIdentifier, BlobGetOptions...)}. Calling {@link
   * StorageBatchResult#get()} on the return value yields the requested {@link StorageBlob} if successful,
   * {@code null} if no such blob exists, or throws a {@link StorageServiceException} if the operation
   * failed.
   */
  public StorageBatchResult<StorageBlob> get(BlobIdentifier objectName, CloudStorageClient.BlobGetOptions... clientConfig) {
    StorageBatchResult<StorageBlob> deleteOutcome = new StorageBatchResult<>();
    RpcRequestBatch.CompletionHandler<StorageObject> completionHandler = buildGetCallback(this.clientConfig, deleteOutcome);
    Map<StorageServiceRpc.StorageOption, ?> settingsMap = DefaultStorage.buildOptionMap(objectName, clientConfig);
    requestBundle.addGetRequest(objectName.toProto(), completionHandler, settingsMap);
    return deleteOutcome;
  }

  /** Submits this batch for processing using a single RPC request. */
  public void execute() {
    requestBundle.submitBatch();
  }

  private RpcRequestBatch.CompletionHandler<Void> buildDeleteCallback(final StorageBatchResult<Boolean> deleteOutcome) {
    return new RpcRequestBatch.CompletionHandler<Void>() {
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

  private RpcRequestBatch.CompletionHandler<StorageObject> buildGetCallback(
          final StorageClientOptions clientConfig, final StorageBatchResult<StorageBlob> deleteOutcome) {
    return new RpcRequestBatch.CompletionHandler<StorageObject>() {
      @Override
      public void handleSuccess(StorageObject response) {
        deleteOutcome.success(
            response == null ? null : StorageBlob.fromProto(clientConfig.getService(), response));
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

  private RpcRequestBatch.CompletionHandler<StorageObject> buildUpdateCallback(
          final StorageClientOptions clientConfig, final StorageBatchResult<StorageBlob> deleteOutcome) {
    return new RpcRequestBatch.CompletionHandler<StorageObject>() {
      @Override
      public void handleSuccess(StorageObject response) {
        deleteOutcome.success(
            response == null ? null : StorageBlob.fromProto(clientConfig.getService(), response));
      }

      @Override
      public void handleFailure(GoogleJsonError googleJsonError) {
        deleteOutcome.error(new StorageServiceException(googleJsonError));
      }
    };
  }
}
