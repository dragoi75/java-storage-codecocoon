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
import com.google.cloud.storage.spi.v1.StorageServiceRpc;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;

/** Write channel implementation to upload Google Cloud Storage blobs. */
class BlobUploadChannel extends BaseWriteChannel<StorageClientOptions, BlobMetadata> {

  BlobUploadChannel(StorageClientOptions clientConfig, BlobMetadata metadata, Map<StorageServiceRpc.StorageOption, ?> storageSettingsMap) {
    this(clientConfig, metadata, openChannel(clientConfig, metadata, storageSettingsMap));
  }

  BlobUploadChannel(StorageClientOptions clientConfig, URL secureUrl) {
    this(clientConfig, openChannel(secureUrl, clientConfig));
  }

  BlobUploadChannel(StorageClientOptions clientConfig, BlobMetadata metadataRecord, String transferId) {
    super(clientConfig, metadataRecord, transferId);
  }

  BlobUploadChannel(StorageClientOptions clientConfig, String transferId) {
    super(clientConfig, null, transferId);
  }

  // Contains metadata of the updated object or null if upload is not completed.
  private StorageObject storedRecord;

  // Detect if flushBuffer() is being retried or not.
  // TODO: I don't think this is thread safe, and there's probably a better way to detect a retry
  // occuring.
  private boolean isReattempting = false;

  boolean isRetrying() {
    return isReattempting;
  }

  StorageObject getStorageObject() {
    return storedRecord;
  }

  @Override
  protected void flushBuffer(final int byteCount, final boolean isFinal) {
    try {
      runWithRetries(
          callable(
              new Runnable() {
                @Override
                public void run() {
                  if (!isRetrying()) {
                    // Enable isRetrying state to reduce number of calls to getCurrentUploadOffset()
                    isReattempting = true;
                    storedRecord =
                        getOptions()
                            .getStorageRpcV1()
                            .writeWithResponse(
                                getUploadId(), getBuffer(), 0, getPosition(), byteCount, isFinal);
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
                    int retryChunkLength = byteCount - driftOffset;

                    if (localNextByteOffset == 0 && remoteNextByteOffset == 0
                        || localNextByteOffset == remoteNextByteOffset) {
                      // Case 1 and 2
                      storedRecord =
                          getOptions()
                              .getStorageRpcV1()
                              .writeWithResponse(
                                  getUploadId(), getBuffer(), 0, getPosition(), byteCount, isFinal);
                    } else if (localNextByteOffset < remoteNextByteOffset
                        && driftOffset < getChunkSize()) {
                      // Case 3
                      storedRecord =
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
                      isReattempting = false;
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
                      throw new StorageServiceException(0, sb.toString());
                    }
                  }
                  // Request was successful and retrying state is now disabled.
                  isReattempting = false;
                }
              }),
          getOptions().getRetrySettings(),
          DefaultStorage.EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageServiceException.translateAndRethrow(retryException);
    }
  }

  protected UploadStateImpl.UploadBuilder stateBuilder() {
    return UploadStateImpl.newBuilder(getOptions(), getEntity(), getUploadId());
  }

  private static String openChannel(
      final StorageClientOptions clientConfig,
      final BlobMetadata metadata,
      final Map<StorageServiceRpc.StorageOption, ?> storageSettingsMap) {
    try {
      return runWithRetries(
          new Callable<String>() {
            @Override
            public String call() {
              return clientConfig.getStorageRpcV1().open(metadata.toProto(), storageSettingsMap);
            }
          },
          clientConfig.getRetrySettings(),
          DefaultStorage.EXCEPTION_HANDLER,
          clientConfig.getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageServiceException.translateAndRethrow(retryException);
    }
  }

  private static String openChannel(final URL secureUrl, final StorageClientOptions clientConfig) {
    try {
      return runWithRetries(
          new Callable<String>() {
            @Override
            public String call() {
              if (!isValidSignedURL(secureUrl.getQuery())) {
                throw new StorageServiceException(2, "invalid signedURL");
              }
              return clientConfig.getStorageRpcV1().open(secureUrl.toString());
            }
          },
          clientConfig.getRetrySettings(),
          DefaultStorage.EXCEPTION_HANDLER,
          clientConfig.getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageServiceException.translateAndRethrow(retryException);
    }
  }

  private static boolean isValidSignedURL(String queryParams) {
    boolean validResult = true;
    if (queryParams.startsWith("X-Goog-Algorithm=")) {
      if (!queryParams.contains("&X-Goog-Credential=")
          || !queryParams.contains("&X-Goog-Date=")
          || !queryParams.contains("&X-Goog-Expires=")
          || !queryParams.contains("&X-Goog-SignedHeaders=")
          || !queryParams.contains("&X-Goog-Signature=")) {
        validResult = false;
      }
    } else if (queryParams.startsWith("GoogleAccessId=")) {
      if (!queryParams.contains("&Expires=") || !queryParams.contains("&Signature=")) {
        validResult = false;
      }
    } else {
      validResult = false;
    }
    return validResult;
  }

  static class UploadStateImpl extends BaseWriteChannel.BaseState<StorageClientOptions, BlobMetadata> {

    private static final long serialVersionUID = -9028324143780151286L;

    UploadStateImpl(UploadBuilder uploadConfigurator) {
      super(uploadConfigurator);
    }

    static class UploadBuilder extends BaseWriteChannel.BaseState.Builder<StorageClientOptions, BlobMetadata> {

      private UploadBuilder(StorageClientOptions clientConfig, BlobMetadata metadataRecord, String transferId) {
        super(clientConfig, metadataRecord, transferId);
      }

      @Override
      public RestorableState<WriteChannel> build() {
        return new UploadStateImpl(this);
      }
    }

    static UploadBuilder newBuilder(StorageClientOptions clientConfig, BlobMetadata metadataRecord, String transferId) {
      return new UploadBuilder(clientConfig, metadataRecord, transferId);
    }

    @Override
    public WriteChannel restore() {
      BlobUploadChannel uploadHandle = new BlobUploadChannel(serviceOptions, entity, uploadId);
      uploadHandle.restore(this);
      return uploadHandle;
    }
  }
}
