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
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;

/** Write channel implementation to upload Google Cloud Storage blobs. */
class BlobUploadChannel extends BaseWriteChannel<StorageClientOptions, BlobMetadata> {

  BlobUploadChannel(StorageClientOptions clientConfig, BlobMetadata objectMetadata, Map<StorageRpcClient.StorageOption, ?> settingsByOption) {
    this(clientConfig, objectMetadata, openUploadSession(clientConfig, objectMetadata, settingsByOption));
  }

  BlobUploadChannel(StorageClientOptions clientConfig, URL presignedUri) {
    this(clientConfig, openUploadSession(presignedUri, clientConfig));
  }

  BlobUploadChannel(StorageClientOptions clientConfig, BlobMetadata objectInfo, String sessionId) {
    super(clientConfig, objectInfo, sessionId);
  }

  BlobUploadChannel(StorageClientOptions clientConfig, String sessionId) {
    super(clientConfig, null, sessionId);
  }

  // Contains metadata of the updated object or null if upload is not completed.
  private StorageObject objectEntry;

  // Detect if flushBuffer() is being retried or not.
  // TODO: I don't think this is thread safe, and there's probably a better way to detect a retry
  // occuring.
  private boolean retryInProgress = false;
  private boolean verifyingFinalChunk = false;

  boolean isRetrying() {
    return retryInProgress;
  }

  StorageObject getStorageObject() {
    return objectEntry;
  }

  private StorageObject sendChunk(
          int segmentOffset, int segmentLength, long currentOffset, boolean isFinal) {
    return getOptions()
        .getStorageRpcV1()
        .writeWithResponse(getUploadId(), getBuffer(), segmentOffset, currentOffset, segmentLength, isFinal);
  }

  private long getRemotePosition() {
    return getOptions().getStorageRpcV1().getCurrentUploadOffset(getUploadId());
  }

  private StorageObject getRemoteStorageObject() {
    return getOptions().getStorageRpcV1().get(getEntity().toProto(), null);
  }

  private StorageServiceException buildUnrecoverableStateException(
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
    return new StorageServiceException(0, messageBuilder.toString());
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
  protected void flushBuffer(final int count, final boolean finalSegment) {
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
                  final int chunkLength = count - chunkOffset;
                  final boolean uploadAlreadyComplete = remotePosition == -1;
                  // Enable isRetrying state to reduce number of calls to getRemotePosition()
                  if (!isRetrying()) {
                    retryInProgress = true;
                  }
                  if (uploadAlreadyComplete && finalSegment) {
                    // Case 6
                    // Request object metadata if not available
                    if (objectEntry == null) {
                      objectEntry = getRemoteStorageObject();
                    }
                    // Verify that with the final chunk we match the blob length
                    if (objectEntry.getSize().longValue() != getPosition() + count) {
                      throw buildUnrecoverableStateException(
                          chunkOffset, chunkLength, localPosition, remotePosition, finalSegment);
                    }
                    retryInProgress = false;
                  } else if (uploadAlreadyComplete && !finalSegment && !verifyingFinalChunk) {
                    // Case 7
                    // Make sure this is the second to last chunk.
                    verifyingFinalChunk = true;
                    // Continue onto next chunk in case this is the last chunk
                  } else if (localPosition <= remotePosition && chunkOffset < getChunkSize()) {
                    // Case 1 && Case 2
                    // We are in a position to send a chunk
                    objectEntry =
                        sendChunk(chunkOffset, chunkLength, remotePosition, finalSegment);
                    retryInProgress = false;
                  } else if (localPosition < remotePosition && chunkOffset == getChunkSize()) {
                    // Case 3
                    // Continue to next chunk to catch up with remotePosition we are one chunk
                    // behind
                    retryInProgress = false;
                  } else {
                    // Case 4 && Case 8 && Case 9
                    throw buildUnrecoverableStateException(
                        chunkOffset, chunkLength, localPosition, remotePosition, finalSegment);
                  }
                }
              }),
          getOptions().getRetrySettings(),
          StorageServiceImpl.EXCEPTION_HANDLER,
          getOptions().getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  protected UploadStateImpl.ChunkedUploadBuilder stateBuilder() {
    return UploadStateImpl.newBuilder(getOptions(), getEntity(), getUploadId());
  }

  private static String openUploadSession(
      final StorageClientOptions clientConfig,
      final BlobMetadata objectMetadata,
      final Map<StorageRpcClient.StorageOption, ?> settingsByOption) {
    try {
      return runWithRetries(
          new Callable<String>() {
            @Override
            public String call() {
              return clientConfig.getStorageRpcV1().open(objectMetadata.toProto(), settingsByOption);
            }
          },
          clientConfig.getRetrySettings(),
          StorageServiceImpl.EXCEPTION_HANDLER,
          clientConfig.getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  private static String openUploadSession(final URL presignedUri, final StorageClientOptions clientConfig) {
    try {
      return runWithRetries(
          new Callable<String>() {
            @Override
            public String call() {
              if (!isValidSignedURL(presignedUri.getQuery())) {
                throw new StorageServiceException(2, "invalid signedURL");
              }
              return clientConfig.getStorageRpcV1().open(presignedUri.toString());
            }
          },
          clientConfig.getRetrySettings(),
          StorageServiceImpl.EXCEPTION_HANDLER,
          clientConfig.getClock());
    } catch (RetryHelper.RetryHelperException retryException) {
      throw StorageServiceException.translateThenThrow(retryException);
    }
  }

  private static boolean isValidSignedURL(String queryString) {
    boolean signatureValid = true;
    if (queryString.startsWith("X-Goog-Algorithm=")) {
      if (!queryString.contains("&X-Goog-Credential=")
          || !queryString.contains("&X-Goog-Date=")
          || !queryString.contains("&X-Goog-Expires=")
          || !queryString.contains("&X-Goog-SignedHeaders=")
          || !queryString.contains("&X-Goog-Signature=")) {
        signatureValid = false;
      }
    } else if (queryString.startsWith("GoogleAccessId=")) {
      if (!queryString.contains("&Expires=") || !queryString.contains("&Signature=")) {
        signatureValid = false;
      }
    } else {
      signatureValid = false;
    }
    return signatureValid;
  }

  static class UploadStateImpl extends BaseWriteChannel.BaseState<StorageClientOptions, BlobMetadata> {

    private static final long serialVersionUID = -9028324143780151286L;

    UploadStateImpl(ChunkedUploadBuilder chunkedUploadConfig) {
      super(chunkedUploadConfig);
    }

    static class ChunkedUploadBuilder extends BaseWriteChannel.BaseState.Builder<StorageClientOptions, BlobMetadata> {

      private ChunkedUploadBuilder(StorageClientOptions clientConfig, BlobMetadata objectInfo, String sessionId) {
        super(clientConfig, objectInfo, sessionId);
      }

      @Override
      public RestorableState<WriteChannel> build() {
        return new UploadStateImpl(this);
      }
    }

    static ChunkedUploadBuilder newBuilder(StorageClientOptions clientConfig, BlobMetadata objectInfo, String sessionId) {
      return new ChunkedUploadBuilder(clientConfig, objectInfo, sessionId);
    }

    @Override
    public WriteChannel restore() {
      BlobUploadChannel blobUploadStream = new BlobUploadChannel(serviceOptions, entity, uploadId);
      blobUploadStream.restore(this);
      return blobUploadStream;
    }
  }
}
