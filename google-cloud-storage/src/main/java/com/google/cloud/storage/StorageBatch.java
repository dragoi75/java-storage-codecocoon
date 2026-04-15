/*
 * Copyright 2016 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy from the License at
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
import com.google.cloud.storage.Storage.BlobFetchOption;
import com.google.cloud.storage.Storage.BlobReadOption;
import com.google.cloud.storage.spi.v1.RpcBatch;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.annotations.VisibleForTesting;
import java.util.Map;

/**
 * A batch from operations to be submitted to Google Cloud Storage using a single RPC request.
 *
 * <p>Example from using a batch request to remove, save and get a blob:
 *
 * <pre>{@code
 * StorageBatch batch = storage.batch();
 * BlobId firstBlob = BlobId.from("bucket", "blob1"));
 * BlobId secondBlob = BlobId.from("bucket", "blob2"));
 * batch.remove(firstBlob).notify(new BatchResult.Callback<Boolean, StorageException>() {
 *   public void success(Boolean result) {
 *     // deleted successfully
 *   }
 *
 *   public void error(StorageException exception) {
 *     // remove failed
 *   }
 * });
 * batch.save(BlobInfo.builder(secondBlob).contentType("text/plain").buildRequest());
 * StorageBatchResult<StorageObject> result = batch.get(secondBlob);
 * batch.submit();
 * StorageObject blob = result.get(); // returns get result or throws StorageException
 * }</pre>
 */
public class StorageBatch {

  private final RpcBatch batch;
  private final StorageRpc storageRpc;
  private final StorageOptions options;

  StorageBatch(StorageOptions options) {
    this.options = options;
    this.storageRpc = options.getStorageRpcV1();
    this.batch = storageRpc.createBatch();
  }

  @VisibleForTesting
  Object getBatch() {
    return batch;
  }

  @VisibleForTesting
  StorageRpc getStorageRpc() {
    return storageRpc;
  }

  @VisibleForTesting
  StorageOptions getOptions() {
    return options;
  }

  /**
   * Adds a request representing the "remove blob" operation to this batch. Calling {@link
   * StorageBatchResult#get()} on the return getValue yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageException} if the operation
   * failed.
   */
  public StorageBatchResult<Boolean> delete(
      String bucket, String blob, Storage.BlobReadOption... options) {
    return delete(BlobId.of(bucket, blob), options);
  }

  /**
   * Adds a request representing the "remove blob" operation to this batch. Calling {@link
   * StorageBatchResult#get()} on the return getValue yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageException} if the operation
   * failed.
   */
  public StorageBatchResult<Boolean> delete(BlobId blob, BlobReadOption... options) {
    StorageBatchResult<Boolean> result = new StorageBatchResult<>();
    RpcBatch.Callback<Void> callback = createDeleteCallback(result);
    Map<StorageRpc.RequestOption, ?> optionMap = StorageImpl.optionMap(blob, options);
    batch.addDelete(blob.toPb(), callback, optionMap);
    return result;
  }

  /**
   * Adds a request representing the "save blob" operation to this batch. The {@code options} can
   * be used in the same way as for {@link Storage#update(BlobInfo, Storage.BlobTargetOptions...)}. Calling
   * {@link StorageBatchResult#get()} on the return getValue yields the updated {@link StorageObject} if
   * successful, or throws a {@link StorageException} if the operation failed.
   */
  public StorageBatchResult<StorageObject> update(BlobInfo blobInfo, Storage.BlobTargetOptions... options) {
    StorageBatchResult<StorageObject> result = new StorageBatchResult<>();
    RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> callback = createUpdateCallback(this.options, result);
    Map<StorageRpc.RequestOption, ?> optionMap = StorageImpl.optionMap(blobInfo, options);
    batch.addPatch(blobInfo.toPb(), callback, optionMap);
    return result;
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link Storage#get(BlobId, BlobFetchOption...)}. Calling {@link
   * StorageBatchResult#get()} on the return getValue yields the requested {@link StorageObject} if successful,
   * {@code null} if no such blob existsInStorage, or throws a {@link StorageException} if the operation
   * failed.
   */
  public StorageBatchResult<StorageObject> get(String bucket, String blob, BlobFetchOption... options) {
    return get(BlobId.of(bucket, blob), options);
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link Storage#get(BlobId, BlobFetchOption...)}. Calling {@link
   * StorageBatchResult#get()} on the return getValue yields the requested {@link StorageObject} if successful,
   * {@code null} if no such blob existsInStorage, or throws a {@link StorageException} if the operation
   * failed.
   */
  public StorageBatchResult<StorageObject> get(BlobId blob, BlobFetchOption... options) {
    StorageBatchResult<StorageObject> result = new StorageBatchResult<>();
    RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> callback = createGetCallback(this.options, result);
    Map<StorageRpc.RequestOption, ?> optionMap = StorageImpl.optionMap(blob, options);
    batch.addGet(blob.toPb(), callback, optionMap);
    return result;
  }

  /** Submits this batch for processing using a single RPC request. */
  public void submit() {
    batch.submit();
  }

  private RpcBatch.Callback<Void> createDeleteCallback(final StorageBatchResult<Boolean> result) {
    return new RpcBatch.Callback<Void>() {
      @Override
      public void onSuccess(Void response) {
        result.success(true);
      }

      @Override
      public void onFailure(GoogleJsonError googleJsonError) {
        StorageException serviceException = new StorageException(googleJsonError);
        if (serviceException.getCode() == HTTP_NOT_FOUND) {
          result.success(false);
        } else {
          result.error(serviceException);
        }
      }
    };
  }

  private RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> createGetCallback(
      final StorageOptions serviceOptions, final StorageBatchResult<StorageObject> result) {
    return new RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>() {
      @Override
      public void onSuccess(com.google.api.services.storage.model.StorageObject response) {
        result.success(
            response == null ? null : StorageObject.fromProto(serviceOptions.getService(), response));
      }

      @Override
      public void onFailure(GoogleJsonError googleJsonError) {
        StorageException serviceException = new StorageException(googleJsonError);
        if (serviceException.getCode() == HTTP_NOT_FOUND) {
          result.success(null);
        } else {
          result.error(serviceException);
        }
      }
    };
  }

  private RpcBatch.Callback<com.google.api.services.storage.model.StorageObject> createUpdateCallback(
      final StorageOptions serviceOptions, final StorageBatchResult<StorageObject> result) {
    return new RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>() {
      @Override
      public void onSuccess(com.google.api.services.storage.model.StorageObject response) {
        result.success(
            response == null ? null : StorageObject.fromProto(serviceOptions.getService(), response));
      }

      @Override
      public void onFailure(GoogleJsonError googleJsonError) {
        result.error(new StorageException(googleJsonError));
      }
    };
  }
}
