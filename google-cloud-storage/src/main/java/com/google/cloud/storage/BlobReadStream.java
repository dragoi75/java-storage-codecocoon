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
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.base.MoreObjects;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Default implementation for ReadChannel. */
class BlobReadStream implements ReadChannel {

  private static final int BASE_CHUNK_SIZE = 2 * 1024 * 1024;

  private final StorageClientOptions clientOptions;
  private final BlobIdentifier objectIdentifier;
  private final Map<StorageRpcClient.StorageOption, ?> operationOptions;
  private String previousEtag;
  private long offset;
  private boolean openFlag;
  private boolean eofReached;
  private int blockSize = BASE_CHUNK_SIZE;

  private final StorageRpcClient rpcClient;
  private final StorageObject storedItem;
  private int bufferIndex;
  private byte[] dataArray;

  BlobReadStream(
          StorageClientOptions clientOptions, BlobIdentifier objectIdentifier, Map<StorageRpcClient.StorageOption, ?> operationOptions) {
    this.clientOptions = clientOptions;
    this.objectIdentifier = objectIdentifier;
    this.operationOptions = operationOptions;
    openFlag = true;
    rpcClient = clientOptions.getStorageRpcV1();
    storedItem = objectIdentifier.toProto();
  }

  @Override
  public RestorableState<ReadChannel> capture() {
    BlobStreamState.BlobStreamBuilder streamFactory =
        BlobStreamState.newBuilder(clientOptions, objectIdentifier, operationOptions)
            .setPosition(offset)
            .setIsOpen(openFlag)
            .setEndOfStream(eofReached)
            .setChunkSize(blockSize);
    if (dataArray != null) {
      streamFactory.setPosition(offset + bufferIndex);
      streamFactory.setEndOfStream(false);
    }
    return streamFactory.buildState();
  }

  @Override
  public boolean isOpen() {
    return openFlag;
  }

  @Override
  public void close() {
    if (openFlag) {
      dataArray = null;
      openFlag = false;
    }
  }

  private void ensureOpen() throws ClosedChannelException {
    if (!openFlag) {
      throw new ClosedChannelException();
    }
  }

  @Override
  public void seek(long offset) throws IOException {
    ensureOpen();
    this.offset = offset;
    dataArray = null;
    bufferIndex = 0;
    eofReached = false;
  }

  @Override
  public void setChunkSize(int blockSize) {
    this.blockSize = blockSize <= 0 ? BASE_CHUNK_SIZE : blockSize;
  }

  @Override
  public int read(ByteBuffer nioBuffer) throws IOException {
    ensureOpen();
    if (dataArray == null) {
      if (eofReached) {
        return -1;
      }
      final int bytesRemaining = Math.max(nioBuffer.remaining(), blockSize);
      try {
        Tuple<String, byte[]> fetchTuple =
            runWithRetries(
                new Callable<Tuple<String, byte[]>>() {
                  @Override
                  public Tuple<String, byte[]> call() {
                    return rpcClient.read(storedItem, operationOptions, offset, bytesRemaining);
                  }
                },
                clientOptions.getRetrySettings(),
                StorageServiceImpl.EXCEPTION_HANDLER,
                clientOptions.getClock());
        if (fetchTuple.y().length > 0 && previousEtag != null && !Objects.equals(fetchTuple.x(), previousEtag)) {
          StringBuilder textAccumulator = new StringBuilder();
          textAccumulator.append("Blob ").append(objectIdentifier).append(" was updated while reading");
          throw new IOException(textAccumulator.toString());
        }
        previousEtag = fetchTuple.x();
        dataArray = fetchTuple.y();
      } catch (RetryHelper.RetryHelperException ex) {
        throw new IOException(ex);
      }
      if (bytesRemaining > dataArray.length) {
        eofReached = true;
        if (dataArray.length == 0) {
          dataArray = null;
          return -1;
        }
      }
    }
    int bytesPending = Math.min(dataArray.length - bufferIndex, nioBuffer.remaining());
    nioBuffer.put(dataArray, bufferIndex, bytesPending);
    bufferIndex += bytesPending;
    if (bufferIndex >= dataArray.length) {
      offset += dataArray.length;
      dataArray = null;
      bufferIndex = 0;
    }
    return bytesPending;
  }

  static class BlobStreamState implements RestorableState<ReadChannel>, Serializable {

    private static final long serialVersionUID = 3889420316004453706L;

    private final StorageClientOptions clientOptions;
    private final BlobIdentifier objectIdentifier;
    private final Map<StorageRpcClient.StorageOption, ?> operationOptions;
    private final String previousEtag;
    private final long offset;
    private final boolean openFlag;
    private final boolean eofReached;
    private final int blockSize;

    BlobStreamState(BlobStreamBuilder streamFactory) {
      this.clientOptions = streamFactory.clientOptions;
      this.objectIdentifier = streamFactory.objectIdentifier;
      this.operationOptions = streamFactory.operationOptions;
      this.previousEtag = streamFactory.previousEtag;
      this.offset = streamFactory.offset;
      this.openFlag = streamFactory.openFlag;
      this.eofReached = streamFactory.eofReached;
      this.blockSize = streamFactory.blockSize;
    }

    static class BlobStreamBuilder {
      private final StorageClientOptions clientOptions;
      private final BlobIdentifier objectIdentifier;
      private final Map<StorageRpcClient.StorageOption, ?> operationOptions;
      private String previousEtag;
      private long offset;
      private boolean openFlag;
      private boolean eofReached;
      private int blockSize;

      private BlobStreamBuilder(StorageClientOptions clientConfig, BlobIdentifier objectIdentifier, Map<StorageRpcClient.StorageOption, ?> requestParams) {
        this.clientOptions = clientConfig;
        this.objectIdentifier = objectIdentifier;
        this.operationOptions = requestParams;
      }

      BlobStreamBuilder setLastEtag(String previousEtag) {
        this.previousEtag = previousEtag;
        return this;
      }

      BlobStreamBuilder setPosition(long offset) {
        this.offset = offset;
        return this;
      }

      BlobStreamBuilder setIsOpen(boolean openFlag) {
        this.openFlag = openFlag;
        return this;
      }

      BlobStreamBuilder setEndOfStream(boolean eofReached) {
        this.eofReached = eofReached;
        return this;
      }

      BlobStreamBuilder setChunkSize(int blockSize) {
        this.blockSize = blockSize;
        return this;
      }

      RestorableState<ReadChannel> buildState() {
        return new BlobStreamState(this);
      }
    }

    static BlobStreamBuilder newBuilder(
            StorageClientOptions clientConfig, BlobIdentifier objectIdentifier, Map<StorageRpcClient.StorageOption, ?> requestParams) {
      return new BlobStreamBuilder(clientConfig, objectIdentifier, requestParams);
    }

    @Override
    public ReadChannel restore() {
      BlobReadStream readStream = new BlobReadStream(clientOptions, objectIdentifier, operationOptions);
      readStream.previousEtag = previousEtag;
      readStream.offset = offset;
      readStream.openFlag = openFlag;
      readStream.eofReached = eofReached;
      readStream.blockSize = blockSize;
      return readStream;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
              clientOptions, objectIdentifier, operationOptions, previousEtag, offset, openFlag, eofReached, blockSize);
    }

    @Override
    public boolean equals(Object otherObject) {
      if (otherObject == null) {
        return false;
      }
      if (!(otherObject instanceof BlobStreamState)) {
        return false;
      }
      final BlobStreamState thatState = (BlobStreamState) otherObject;
      return Objects.equals(this.clientOptions, thatState.clientOptions)
          && Objects.equals(this.objectIdentifier, thatState.objectIdentifier)
          && Objects.equals(this.operationOptions, thatState.operationOptions)
          && Objects.equals(this.previousEtag, thatState.previousEtag)
          && this.offset == thatState.offset
          && this.openFlag == thatState.openFlag
          && this.eofReached == thatState.eofReached
          && this.blockSize == thatState.blockSize;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("blob", objectIdentifier)
          .add("position", offset)
          .add("isOpen", openFlag)
          .add("endOfStream", eofReached)
          .toString();
    }
  }
}
