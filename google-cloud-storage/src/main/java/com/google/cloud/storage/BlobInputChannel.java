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

import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.ReadChannel;
import com.google.cloud.RestorableState;
import com.google.cloud.RetryHelper;
import com.google.cloud.Tuple;
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
import com.google.common.base.MoreObjects;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Default implementation for ReadChannel. */
class BlobInputChannel implements ReadChannel {

  private static final int STANDARD_BLOCK_SIZE = 2 * 1024 * 1024;

  private final StorageClientOptions clientConfig;
  private final BlobIdentifier objectId;
  private final Map<StorageServiceRpc.StorageOption, ?> requestParams;
  private String previousVersionTag;
  private long currentOffset;
  private boolean openFlag;
  private boolean eofReached;
  private int blockSize = STANDARD_BLOCK_SIZE;

  private final StorageServiceRpc rpcClient;
  private final StorageObject objectMetadata;
  private int bufIndex;
  private byte[] byteArray;

  BlobInputChannel(
          StorageClientOptions clientConfig, BlobIdentifier objectId, Map<StorageServiceRpc.StorageOption, ?> requestParams) {
    this.clientConfig = clientConfig;
    this.objectId = objectId;
    this.requestParams = requestParams;
    openFlag = true;
    rpcClient = clientConfig.getStorageRpcV1();
    objectMetadata = objectId.toProto();
  }

  @Override
  public RestorableState<ReadChannel> capture() {
    DefaultState.BlobStreamStateBuilder stateCreator =
        DefaultState.newBuilder(clientConfig, objectId, requestParams)
            .setPosition(currentOffset)
            .setIsOpen(openFlag)
            .setEndOfStream(eofReached)
            .setChunkSize(blockSize);
    if (byteArray != null) {
      stateCreator.setPosition(currentOffset + bufIndex);
      stateCreator.setEndOfStream(false);
    }
    return stateCreator.buildState();
  }

  @Override
  public boolean isOpen() {
    return openFlag;
  }

  @Override
  public void close() {
    if (openFlag) {
      byteArray = null;
      openFlag = false;
    }
  }

  private void ensureOpen() throws ClosedChannelException {
    if (!openFlag) {
      throw new ClosedChannelException();
    }
  }

  @Override
  public void seek(long currentOffset) throws IOException {
    ensureOpen();
    this.currentOffset = currentOffset;
    byteArray = null;
    bufIndex = 0;
    eofReached = false;
  }

  @Override
  public void setChunkSize(int blockSize) {
    this.blockSize = blockSize <= 0 ? STANDARD_BLOCK_SIZE : blockSize;
  }

  @Override
  public int read(ByteBuffer nioBuffer) throws IOException {
    ensureOpen();
    if (byteArray == null) {
      if (eofReached) {
        return -1;
      }
      final int remaining = Math.max(nioBuffer.remaining(), blockSize);
      try {
        Tuple<String, byte[]> readOutcome =
            runWithRetries(
                new Callable<Tuple<String, byte[]>>() {
                  @Override
                  public Tuple<String, byte[]> call() {
                    return rpcClient.read(objectMetadata, requestParams, currentOffset, remaining);
                  }
                },
                clientConfig.getRetrySettings(),
                DefaultStorage.EXCEPTION_HANDLER,
                clientConfig.getClock());
        if (readOutcome.y().length > 0 && previousVersionTag != null && !Objects.equals(readOutcome.x(), previousVersionTag)) {
          StringBuilder msgAccumulator = new StringBuilder();
          msgAccumulator.append("Blob ").append(objectId).append(" was updated while reading");
          throw new IOException(msgAccumulator.toString());
        }
        previousVersionTag = readOutcome.x();
        byteArray = readOutcome.y();
      } catch (RetryHelper.RetryHelperException retryException) {
        throw new IOException(retryException);
      }
      if (remaining > byteArray.length) {
        eofReached = true;
        if (byteArray.length == 0) {
          byteArray = null;
          return -1;
        }
      }
    }
    int bytesPending = Math.min(byteArray.length - bufIndex, nioBuffer.remaining());
    nioBuffer.put(byteArray, bufIndex, bytesPending);
    bufIndex += bytesPending;
    if (bufIndex >= byteArray.length) {
      currentOffset += byteArray.length;
      byteArray = null;
      bufIndex = 0;
    }
    return bytesPending;
  }

  static class DefaultState implements RestorableState<ReadChannel>, Serializable {

    private static final long serialVersionUID = 3889420316004453706L;

    private final StorageClientOptions clientConfig;
    private final BlobIdentifier objectId;
    private final Map<StorageServiceRpc.StorageOption, ?> requestParams;
    private final String previousVersionTag;
    private final long currentOffset;
    private final boolean openFlag;
    private final boolean eofReached;
    private final int blockSize;

    DefaultState(BlobStreamStateBuilder stateCreator) {
      this.clientConfig = stateCreator.clientConfig;
      this.objectId = stateCreator.objectId;
      this.requestParams = stateCreator.requestParams;
      this.previousVersionTag = stateCreator.previousVersionTag;
      this.currentOffset = stateCreator.currentOffset;
      this.openFlag = stateCreator.openFlag;
      this.eofReached = stateCreator.eofReached;
      this.blockSize = stateCreator.blockSize;
    }

    static class BlobStreamStateBuilder {
      private final StorageClientOptions clientConfig;
      private final BlobIdentifier objectId;
      private final Map<StorageServiceRpc.StorageOption, ?> requestParams;
      private String previousVersionTag;
      private long currentOffset;
      private boolean openFlag;
      private boolean eofReached;
      private int blockSize;

      private BlobStreamStateBuilder(StorageClientOptions clientConfig, BlobIdentifier objectId, Map<StorageServiceRpc.StorageOption, ?> requestParams) {
        this.clientConfig = clientConfig;
        this.objectId = objectId;
        this.requestParams = requestParams;
      }

      BlobStreamStateBuilder setLastEtag(String previousVersionTag) {
        this.previousVersionTag = previousVersionTag;
        return this;
      }

      BlobStreamStateBuilder setPosition(long currentOffset) {
        this.currentOffset = currentOffset;
        return this;
      }

      BlobStreamStateBuilder setIsOpen(boolean openFlag) {
        this.openFlag = openFlag;
        return this;
      }

      BlobStreamStateBuilder setEndOfStream(boolean eofReached) {
        this.eofReached = eofReached;
        return this;
      }

      BlobStreamStateBuilder setChunkSize(int blockSize) {
        this.blockSize = blockSize;
        return this;
      }

      RestorableState<ReadChannel> buildState() {
        return new DefaultState(this);
      }
    }

    static BlobStreamStateBuilder newBuilder(
            StorageClientOptions clientConfig, BlobIdentifier objectId, Map<StorageServiceRpc.StorageOption, ?> requestParams) {
      return new BlobStreamStateBuilder(clientConfig, objectId, requestParams);
    }

    @Override
    public ReadChannel restore() {
      BlobInputChannel inputStream = new BlobInputChannel(clientConfig, objectId, requestParams);
      inputStream.previousVersionTag = previousVersionTag;
      inputStream.currentOffset = currentOffset;
      inputStream.openFlag = openFlag;
      inputStream.eofReached = eofReached;
      inputStream.blockSize = blockSize;
      return inputStream;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
              clientConfig, objectId, requestParams, previousVersionTag, currentOffset, openFlag, eofReached, blockSize);
    }

    @Override
    public boolean equals(Object candidate) {
      if (candidate == null) {
        return false;
      }
      if (!(candidate instanceof DefaultState)) {
        return false;
      }
      final DefaultState thatState = (DefaultState) candidate;
      return Objects.equals(this.clientConfig, thatState.clientConfig)
          && Objects.equals(this.objectId, thatState.objectId)
          && Objects.equals(this.requestParams, thatState.requestParams)
          && Objects.equals(this.previousVersionTag, thatState.previousVersionTag)
          && this.currentOffset == thatState.currentOffset
          && this.openFlag == thatState.openFlag
          && this.eofReached == thatState.eofReached
          && this.blockSize == thatState.blockSize;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("blob", objectId)
          .add("position", currentOffset)
          .add("isOpen", openFlag)
          .add("endOfStream", eofReached)
          .toString();
    }
  }
}
