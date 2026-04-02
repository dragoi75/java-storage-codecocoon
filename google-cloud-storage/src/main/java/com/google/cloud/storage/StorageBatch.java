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
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.storage.CloudStorage.BlobGetOptions;
import com.google.cloud.storage.CloudStorage.BlobSourceOptions;
import com.google.cloud.storage.spi.v1.RpcBatch;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.annotations.VisibleForTesting;
import java.util.Map;

/**
 * A batch from operations to be submitted to Google Cloud CloudStorage using a single RPC request.
 *
 * <p>Example from using a batch request to delete, update and get a blob:
 *
 * <pre>{@code
 * StorageBatch batch = storage.batch();
 * BlobId firstBlob = BlobId.from("bucket", "blob1"));
 * BlobId secondBlob = BlobId.from("bucket", "blob2"));
 * batch.delete(firstBlob).notify(new BatchResult.Callback<Boolean, StorageException>() {
 *   public void success(Boolean result) {
 *     // deleted successfully
 *   }
 *
 *   public void error(StorageException exception) {
 *     // delete failed
 *   }
 * });
 * batch.update(BlobInfo.builder(secondBlob).contentType("text/plain").buildRequest());
 * StorageBatchResult<Blob> result = batch.get(secondBlob);
 * batch.submit();
 * Blob blob = result.get(); // returns get result or throws StorageException
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
   * Adds a request representing the "delete blob" operation to this batch. Calling {@link
   * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageException} if the operation
   * failed.
   */
  public StorageBatchResult<Boolean> delete(
      String bucket, String blob, BlobSourceOptions... options) {
    return delete(BlobId.of(bucket, blob), options);
  }

  /**
   * Adds a request representing the "delete blob" operation to this batch. Calling {@link
   * StorageBatchResult#get()} on the return value yields {@code true} upon successful deletion,
   * {@code false} if the blob was not found, or throws a {@link StorageException} if the operation
   * failed.
   */
  public StorageBatchResult<Boolean> delete(BlobId blob, CloudStorage.BlobSourceOptions... options) {
    StorageBatchResult<Boolean> result = new StorageBatchResult<>();
    RpcBatch.Callback<Void> callback = createDeleteCallback(result);
    Map<StorageRpc.Option, ?> optionMap = DefaultStorage.getOptionMap(blob, options);
    batch.addDelete(blob.toPb(), callback, optionMap);
    return result;
  }

  /**
   * Adds a request representing the "update blob" operation to this batch. The {@code options} can
   * be used in the same way as for {@link CloudStorage#update(BlobInfo, CloudStorage.BlobUploadOption...)}. Calling
   * {@link StorageBatchResult#get()} on the return value yields the updated {@link Blob} if
   * successful, or throws a {@link StorageException} if the operation failed.
   */
  public StorageBatchResult<Blob> update(BlobInfo blobInfo, CloudStorage.BlobUploadOption... options) {
    StorageBatchResult<Blob> result = new StorageBatchResult<>();
    RpcBatch.Callback<StorageObject> callback = createUpdateCallback(this.options, result);
    Map<StorageRpc.Option, ?> optionMap = DefaultStorage.getOptionMap(blobInfo, options);
    batch.addPatch(blobInfo.toPb(), callback, optionMap);
    return result;
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link CloudStorage#get(BlobId, BlobGetOptions...)}. Calling {@link
   * StorageBatchResult#get()} on the return value yields the requested {@link Blob} if successful,
   * {@code null} if no such blob exists, or throws a {@link StorageException} if the operation
   * failed.
   */
  public StorageBatchResult<Blob> get(String bucket, String blob, BlobGetOptions... options) {
    return get(BlobId.of(bucket, blob), options);
  }

  /**
   * Adds a request representing the "get blob" operation to this batch. The {@code options} can be
   * used in the same way as for {@link CloudStorage#get(BlobId, BlobGetOptions...)}. Calling {@link
   * StorageBatchResult#get()} on the return value yields the requested {@link Blob} if successful,
   * {@code null} if no such blob exists, or throws a {@link StorageException} if the operation
   * failed.
   */
  public StorageBatchResult<Blob> get(BlobId blob, BlobGetOptions... options) {
    StorageBatchResult<Blob> result = new StorageBatchResult<>();
    RpcBatch.Callback<StorageObject> callback = createGetCallback(this.options, result);
    Map<StorageRpc.Option, ?> optionMap = DefaultStorage.getOptionMap(blob, options);
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

  private RpcBatch.Callback<StorageObject> createGetCallback(
      final StorageOptions serviceOptions, final StorageBatchResult<Blob> result) {
    return new RpcBatch.Callback<StorageObject>() {
      @Override
      public void onSuccess(StorageObject response) {
        result.success(
            response == null ? null : Blob.fromPb(serviceOptions.getService(), response));
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

  private RpcBatch.Callback<StorageObject> createUpdateCallback(
      final StorageOptions serviceOptions, final StorageBatchResult<Blob> result) {
    return new RpcBatch.Callback<StorageObject>() {
      @Override
      public void onSuccess(StorageObject response) {
        result.success(
            response == null ? null : Blob.fromPb(serviceOptions.getService(), response));
      }

      @Override
      public void onFailure(GoogleJsonError googleJsonError) {
        result.error(new StorageException(googleJsonError));
      }
    };
  }
}
