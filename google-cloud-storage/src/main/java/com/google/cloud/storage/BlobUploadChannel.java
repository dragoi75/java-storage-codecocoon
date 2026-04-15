/*
 * Copyright 2015 Google LLC
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

import static com.google.cloud.RetryHelper.runWithRetries;
import static java.util.concurrent.Executors.callable;

import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.BaseWriteChannel;
import com.google.cloud.RestorableState;
import com.google.cloud.RetryHelper;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.spi.v1.StorageRpc;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;

/** Write channel implementation to upload Google Cloud Storage blobs. */
class BlobUploadChannel extends BaseWriteChannel<StorageOptions, BlobInfo> {

  BlobUploadChannel(StorageOptions storageConfig, BlobInfo objectInfo, Map<StorageRpc.Option, ?> configMap) {
    this(storageConfig, objectInfo, openUploadChannel(storageConfig, objectInfo, configMap));
  }

  BlobUploadChannel(StorageOptions storageConfig, URL signedLink) {
    this(storageConfig, openUploadChannel(signedLink, storageConfig));
  }

  BlobUploadChannel(StorageOptions storageConfig, BlobInfo objectMetadata, String transferId) {
    super(storageConfig, objectMetadata, transferId);
  }

  BlobUploadChannel(StorageOptions storageConfig, String transferId) {
    super(storageConfig, null, transferId);
  }

  // Contains metadata of the updated object or null if upload is not completed.
  private StorageObject storedObject;

  // Detect if flushBuffer() is being retried or not.
  // TODO: I don't think this is thread safe, and there's probably a better way to detect a retry
  // occuring.
  private boolean retryInProgress = false;

  boolean isRetrying() {
    return retryInProgress;
  }

  StorageObject getStorageObject() {
    return storedObject;
  }

  @Override
  protected void flushBuffer(final int chunkSize, final boolean isFinal) {
    try {
      runWithRetries(
          callable(
              new Runnable() {
                @Override
                public void run() {
                  if (!isRetrying()) {
                    // Enable isRetrying state to reduce number of calls to getCurrentUploadOffset()
                    retryInProgress = true;
                    storedObject =
                        getOptions()
                            .getStorageRpcV1()
                            .writeWithResponse(
                                getUploadId(), getBuffer(), 0, getPosition(), chunkSize, isFinal);
                  } else {
                    // Retriable interruption occurred.
                    // Variables:
                    // chunk = getBuffer()
                    // localNextByteOffset == getPosition()
                    // chunkSize = getChunkSize()
                    //
                    // Case 1: localNextByteOffset == 0 && remoteNextByteOffset == 0:
                    // we are retrying from first chunk start from 0 offset.
                    //
                    // Case 2: localNextByteOffset == remoteNextByteOffset:
                    // Special case of Case 1 when a chunk is retried.
                    //
                    // Case 3: localNextByteOffset < remoteNextByteOffset
                    //             && driftOffset < chunkSize:
                    // Upload progressed and localNextByteOffset is not in-sync with
                    // remoteNextByteOffset and driftOffset is less than chunkSize.
                    // driftOffset must be less than chunkSize for it to retry using
                    // chunk maintained in memory.
                    // Find the driftOffset by subtracting localNextByteOffset from
                    // remoteNextByteOffset.
                    // Use driftOffset to determine where to restart from using the chunk in
                    // memory.
                    //
                    // Case 4: localNextByteOffset < remoteNextByteOffset
                    //            && driftOffset == chunkSize:
                    // Special case of Case 3.
                    // If chunkSize is equal to driftOffset then remoteNextByteOffset has moved on
                    // to the next chunk.
                    //
                    // Case 5: localNextByteOffset < remoteNextByteOffset
                    //            && driftOffset > chunkSize:
                    // Throw exception as remoteNextByteOffset has drifted beyond the retriable
                    // chunk maintained in memory. This is not possible unless there's multiple
                    // clients uploading to the same resumable upload session.
                    //
                    // Case 6: localNextByteOffset > remoteNextByteOffset:
                    // For completeness, this case is not possible because it would require retrying
                    // a 400 status code which is not allowed.
                    //
                    // Get remote offset from API
                    long remoteNextByteOffset =
                        getOptions().getStorageRpcV1().getCurrentUploadOffset(getUploadId());
                    long localNextByteOffset = getPosition();
                    int driftOffset = (int) (remoteNextByteOffset - localNextByteOffset);
                    int retryChunkLength = chunkSize - driftOffset;

                    if (localNextByteOffset == 0 && remoteNextByteOffset == 0
                        || localNextByteOffset == remoteNextByteOffset) {
                      // Case 1 and 2
                      storedObject =
                          getOptions()
                              .getStorageRpcV1()
                              .writeWithResponse(
                                  getUploadId(), getBuffer(), 0, getPosition(), chunkSize, isFinal);
                    } else if (localNextByteOffset < remoteNextByteOffset
                        && driftOffset < getChunkSize()) {
                      // Case 3
                      storedObject =
                          getOptions()
                              .getStorageRpcV1()
                              .writeWithResponse(
                                  getUploadId(),
                                  getBuffer(),
                                  driftOffset,
                                  remoteNextByteOffset,
                                  retryChunkLength,
                                      isFinal);
                    } else if (localNextByteOffset < remoteNextByteOffset
                        && driftOffset == getChunkSize()) {
                      // Case 4
                      // Continue to next chunk
                      retryInProgress = false;
                      return;
                    } else {
                      // Case 5
                      StringBuilder sb = new StringBuilder();
                      sb.append(
                          "Remote offset has progressed beyond starting byte offset of next chunk.");
                      sb.append(
                          "This may be a symptom of multiple clients uploading to the same upload session.\n\n");
                      sb.append("For debugging purposes:\n");
                      sb.append("uploadId: ").append(getUploadId()).append('\n');
                      sb.append("localNextByteOffset: ").append(localNextByteOffset).append('\n');
                      sb.append("remoteNextByteOffset: ").append(remoteNextByteOffset).append('\n');
                      sb.append("driftOffset: ").append(driftOffset).append("\n\n");
                      throw new StorageException(0, sb.toString());
                    }
                  }
                  // Request was successful and retrying state is now disabled.
                  retryInProgress = false;
                }
              }),
          getOptions().getRetrySettings(),
          StorageImpl.EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  protected UploadStateImpl.ChunkedUploadBuilder stateBuilder() {
    return UploadStateImpl.chunkedUploadBuilder(getOptions(), getEntity(), getUploadId());
  }

  private static String openUploadChannel(
      final StorageOptions storageConfig,
      final BlobInfo objectInfo,
      final Map<StorageRpc.Option, ?> configMap) {
    try {
      return runWithRetries(
          new Callable<String>() {
            @Override
            public String call() {
              return storageConfig.getStorageRpcV1().open(objectInfo.toPb(), configMap);
            }
          },
          storageConfig.getRetrySettings(),
          StorageImpl.EXCEPTION_HANDLER,
          storageConfig.getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  private static String openUploadChannel(final URL signedLink, final StorageOptions storageConfig) {
    try {
      return runWithRetries(
          new Callable<String>() {
            @Override
            public String call() {
              if (!isValidSignedURL(signedLink.getQuery())) {
                throw new StorageException(2, "invalid signedURL");
              }
              return storageConfig.getStorageRpcV1().open(signedLink.toString());
            }
          },
          storageConfig.getRetrySettings(),
          StorageImpl.EXCEPTION_HANDLER,
          storageConfig.getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageException.translateAndThrow(retryException);
    }
  }

  private static boolean isValidSignedURL(String queryString) {
    boolean valid = true;
    if (queryString.startsWith("X-Goog-Algorithm=")) {
      if (!queryString.contains("&X-Goog-Credential=")
          || !queryString.contains("&X-Goog-Date=")
          || !queryString.contains("&X-Goog-Expires=")
          || !queryString.contains("&X-Goog-SignedHeaders=")
          || !queryString.contains("&X-Goog-Signature=")) {
        valid = false;
      }
    } else if (queryString.startsWith("GoogleAccessId=")) {
      if (!queryString.contains("&Expires=") || !queryString.contains("&Signature=")) {
        valid = false;
      }
    } else {
      valid = false;
    }
    return valid;
  }

  static class UploadStateImpl extends BaseWriteChannel.BaseState<StorageOptions, BlobInfo> {

    private static final long UPLOAD_STATE_SERIAL_UID = -9028324143780151286L;

    UploadStateImpl(ChunkedUploadBuilder chunkedBuilder) {
      super(chunkedBuilder);
    }

    static class ChunkedUploadBuilder extends BaseWriteChannel.BaseState.Builder<StorageOptions, BlobInfo> {

      private ChunkedUploadBuilder(StorageOptions storageConfig, BlobInfo objectMetadata, String transferId) {
        super(storageConfig, objectMetadata, transferId);
      }

      @Override
      public RestorableState<WriteChannel> build() {
        return new UploadStateImpl(this);
      }
    }

    static ChunkedUploadBuilder chunkedUploadBuilder(StorageOptions storageConfig, BlobInfo objectMetadata, String transferId) {
      return new ChunkedUploadBuilder(storageConfig, objectMetadata, transferId);
    }

    @Override
    public WriteChannel restore() {
      BlobUploadChannel uploadStream = new BlobUploadChannel(serviceOptions, entity, uploadId);
      uploadStream.restore(this);
      return uploadStream;
    }
  }
}
