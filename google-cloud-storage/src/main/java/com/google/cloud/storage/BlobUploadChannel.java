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

  BlobUploadChannel(StorageOptions storageConfig, BlobInfo objectInfo, Map<StorageRpc.Option, ?> settingMap) {
    this(storageConfig, objectInfo, openChannel(storageConfig, objectInfo, settingMap));
  }

  BlobUploadChannel(StorageOptions storageConfig, URL accessLink) {
    this(storageConfig, openUploadChannel(accessLink, storageConfig));
  }

  BlobUploadChannel(StorageOptions storageConfig, BlobInfo objectMetadata, String uploadToken) {
    super(storageConfig, objectMetadata, uploadToken);
  }

  BlobUploadChannel(StorageOptions storageConfig, String uploadToken) {
    super(storageConfig, null, uploadToken);
  }

  // Contains metadata of the updated object or null if upload is not completed.
  private StorageObject remoteObject;

  // Detect if flushBuffer() is being retried or not.
  // TODO: I don't think this is thread safe, and there's probably a better way to detect a retry
  // occuring.
  private boolean retryInProgress = false;
  private boolean detectingFinalSegment = false;

  boolean isRetrying() {
    return retryInProgress;
  }

  StorageObject getStorageObject() {
    return remoteObject;
  }

  private StorageObject uploadChunk(
      int segmentOffset, int segmentLength, long streamOffset, boolean isFinal) {
    return getOptions()
        .getStorageRpcV1()
        .writeWithResponse(getUploadId(), getBuffer(), segmentOffset, streamOffset, segmentLength, isFinal);
  }

  private long getRemotePosition() {
    return getOptions().getStorageRpcV1().getCurrentUploadOffset(getUploadId());
  }

  private StorageObject getRemoteStorageObject() {
    return getOptions().getStorageRpcV1().get(getEntity().toPb(), null);
  }

  private StorageException createUnrecoverableUploadException(
      int segmentOffset, int segmentLength, long localOffset, long remoteOffset, boolean isFinal) {
    StringBuilder messageBuilder = new StringBuilder();
    messageBuilder.append("Unable to recover in upload.\n");
    messageBuilder.append(
        "This may be a symptom of multiple clients uploading to the same upload session.\n\n");
    messageBuilder.append("For debugging purposes:\n");
    messageBuilder.append("uploadId: ").append(getUploadId()).append('\n');
    messageBuilder.append("chunkOffset: ").append(segmentOffset).append('\n');
    messageBuilder.append("chunkLength: ").append(segmentLength).append('\n');
    messageBuilder.append("localOffset: ").append(localOffset).append('\n');
    messageBuilder.append("remoteOffset: ").append(remoteOffset).append('\n');
    messageBuilder.append("lastChunk: ").append(isFinal).append("\n\n");
    return new StorageException(0, messageBuilder.toString());
  }

  // Retriable interruption occurred.
  // Variables:
  // chunk = getBuffer()
  // localNextByteOffset == getPosition()
  // chunkSize = getChunkSize()
  //
  // Case 1: localNextByteOffset == remoteNextByteOffset:
  // Retrying the entire chunk
  //
  // Case 2: localNextByteOffset < remoteNextByteOffset
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
  // Case 3: localNextByteOffset < remoteNextByteOffset
  //            && driftOffset == chunkSize:
  // Special case of Case 2.
  // If chunkSize is equal to driftOffset then remoteNextByteOffset has moved on
  // to the next chunk.
  //
  // Case 4: localNextByteOffset < remoteNextByteOffset
  //            && driftOffset > chunkSize:
  // Throw exception as remoteNextByteOffset has drifted beyond the retriable
  // chunk maintained in memory. This is not possible unless there's multiple
  // clients uploading to the same resumable upload session.
  //
  // Case 5: localNextByteOffset > remoteNextByteOffset:
  // For completeness, this case is not possible because it would require retrying
  // a 400 status code which is not allowed.
  //
  // Case 6: remoteNextByteOffset==-1 && last == true
  // Upload is complete and retry occurred in the "last" chunk. Data sent was
  // received by the service.
  //
  // Case 7: remoteNextByteOffset==-1 && last == false && !checkingForLastChunk
  // Not last chunk and are not checkingForLastChunk, allow for the client to
  // catch up to final chunk which meets
  // Case 6.
  //
  // Case 8: remoteNextByteOffset==-1 && last == false && checkingForLastChunk
  // Not last chunk and checkingForLastChunk means this is the second time we
  // hit this case, meaning the upload was completed by a different client.
  //
  // Case 9: Only possible if the client local offset continues beyond the remote
  // offset which is not possible.
  //
  @Override
  protected void flushBuffer(final int byteCount, final boolean finalSegment) {
    try {
      runWithRetries(
          callable(
              new Runnable() {
                @Override
                public void run() {
                  // Get remote offset from API
                  final long localPosition = getPosition();
                  // For each request it should be possible to retry from its location in this code
                  final long remotePosition = isRetrying() ? getRemotePosition() : getPosition();
                  final int chunkOffset = (int) (remotePosition - localPosition);
                  final int chunkLength = byteCount - chunkOffset;
                  final boolean uploadAlreadyComplete = remotePosition == -1;
                  // Enable isRetrying state to reduce number of calls to getRemotePosition()
                  if (!isRetrying()) {
                    retryInProgress = true;
                  }
                  if (uploadAlreadyComplete && finalSegment) {
                    // Case 6
                    // Request object metadata if not available
                    if (remoteObject == null) {
                      remoteObject = getRemoteStorageObject();
                    }
                    // Verify that with the final chunk we match the blob length
                    if (remoteObject.getSize().longValue() != getPosition() + byteCount) {
                      throw createUnrecoverableUploadException(
                          chunkOffset, chunkLength, localPosition, remotePosition, finalSegment);
                    }
                    retryInProgress = false;
                  } else if (uploadAlreadyComplete && !finalSegment && !detectingFinalSegment) {
                    // Case 7
                    // Make sure this is the second to last chunk.
                    detectingFinalSegment = true;
                    // Continue onto next chunk in case this is the last chunk
                  } else if (localPosition <= remotePosition && chunkOffset < getChunkSize()) {
                    // Case 1 && Case 2
                    // We are in a position to send a chunk
                    remoteObject =
                        uploadChunk(chunkOffset, chunkLength, remotePosition, finalSegment);
                    retryInProgress = false;
                  } else if (localPosition < remotePosition && chunkOffset == getChunkSize()) {
                    // Case 3
                    // Continue to next chunk to catch up with remotePosition we are one chunk
                    // behind
                    retryInProgress = false;
                  } else {
                    // Case 4 && Case 8 && Case 9
                    throw createUnrecoverableUploadException(
                        chunkOffset, chunkLength, localPosition, remotePosition, finalSegment);
                  }
                }
              }),
          getOptions().getRetrySettings(),
          StorageImpl.EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelper.RetryHelperException ex) {
      throw StorageException.translateAndThrow(ex);
    }
  }

  protected UploadStateImpl.ChunkedUploadBuilder stateBuilder() {
    return UploadStateImpl.newBuilder(getOptions(), getEntity(), getUploadId());
  }

  private static String openChannel(
      final StorageOptions storageConfig,
      final BlobInfo objectInfo,
      final Map<StorageRpc.Option, ?> settingMap) {
    try {
      return runWithRetries(
          new Callable<String>() {
            @Override
            public String call() {
              return storageConfig.getStorageRpcV1().open(objectInfo.toPb(), settingMap);
            }
          },
          storageConfig.getRetrySettings(),
          StorageImpl.EXCEPTION_HANDLER,
          storageConfig.getClock());
    } catch (RetryHelper.RetryHelperException ex) {
      throw StorageException.translateAndThrow(ex);
    }
  }

  private static String openUploadChannel(final URL accessLink, final StorageOptions storageConfig) {
    try {
      return runWithRetries(
          new Callable<String>() {
            @Override
            public String call() {
              if (!isValidSignedURL(accessLink.getQuery())) {
                throw new StorageException(2, "invalid signedURL");
              }
              return storageConfig.getStorageRpcV1().open(accessLink.toString());
            }
          },
          storageConfig.getRetrySettings(),
          StorageImpl.EXCEPTION_HANDLER,
          storageConfig.getClock());
    } catch (RetryHelper.RetryHelperException ex) {
      throw StorageException.translateAndThrow(ex);
    }
  }

  private static boolean isValidSignedURL(String signatureQuery) {
    boolean validFlag = true;
    if (signatureQuery.startsWith("X-Goog-Algorithm=")) {
      if (!signatureQuery.contains("&X-Goog-Credential=")
          || !signatureQuery.contains("&X-Goog-Date=")
          || !signatureQuery.contains("&X-Goog-Expires=")
          || !signatureQuery.contains("&X-Goog-SignedHeaders=")
          || !signatureQuery.contains("&X-Goog-Signature=")) {
        validFlag = false;
      }
    } else if (signatureQuery.startsWith("GoogleAccessId=")) {
      if (!signatureQuery.contains("&Expires=") || !signatureQuery.contains("&Signature=")) {
        validFlag = false;
      }
    } else {
      validFlag = false;
    }
    return validFlag;
  }

  static class UploadStateImpl extends BaseWriteChannel.BaseState<StorageOptions, BlobInfo> {

    private static final long SERIAL_UID = -9028324143780151286L;

    UploadStateImpl(ChunkedUploadBuilder chunkedCreator) {
      super(chunkedCreator);
    }

    static class ChunkedUploadBuilder extends BaseWriteChannel.BaseState.Builder<StorageOptions, BlobInfo> {

      private ChunkedUploadBuilder(StorageOptions storageConfig, BlobInfo objectMetadata, String uploadToken) {
        super(storageConfig, objectMetadata, uploadToken);
      }

      @Override
      public RestorableState<WriteChannel> build() {
        return new UploadStateImpl(this);
      }
    }

    static ChunkedUploadBuilder newBuilder(StorageOptions storageConfig, BlobInfo objectMetadata, String uploadToken) {
      return new ChunkedUploadBuilder(storageConfig, objectMetadata, uploadToken);
    }

    @Override
    public WriteChannel restore() {
      BlobUploadChannel restoredChannel = new BlobUploadChannel(serviceOptions, entity, uploadId);
      restoredChannel.restore(this);
      return restoredChannel;
    }
  }
}
