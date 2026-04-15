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
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.base.MoreObjects;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Default implementation for ReadChannel. */
class ReadableBlobChannel implements ReadChannel {

  private static final int STANDARD_BLOCK_SIZE = 2 * 1024 * 1024;

  private final StorageOptions storageOptions;
  private final BlobId resourceId;
  private final Map<StorageRpc.Option, ?> fetchOptions;
  private String previousEtag;
  private long offset;
  private boolean open;
  private boolean eofReached;
  private int blockSize = STANDARD_BLOCK_SIZE;

  private final StorageRpc rpcClient;
  private final StorageObject objectMetadata;
  private int bufferIndex;
  private byte[] payload;

  ReadableBlobChannel(
          StorageOptions storageOptions, BlobId resourceId, Map<StorageRpc.Option, ?> fetchOptions) {
    this.storageOptions = storageOptions;
    this.resourceId = resourceId;
    this.fetchOptions = fetchOptions;
    open = true;
    rpcClient = storageOptions.getStorageRpcV1();
    objectMetadata = resourceId.toPb();
  }

  @Override
  public RestorableState<ReadChannel> capture() {
    BlobReadStateImpl.BlobDownloadOptionsBuilder downloadOptions =
        BlobReadStateImpl.toBuilder(storageOptions, resourceId, fetchOptions)
            .setPosition(offset)
            .setIsOpen(open)
            .setEndOfStream(eofReached)
            .setChunkSize(blockSize);
    if (payload != null) {
      downloadOptions.setPosition(offset + bufferIndex);
      downloadOptions.setEndOfStream(false);
    }
    return downloadOptions.buildBlobReadState();
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    if (open) {
      payload = null;
      open = false;
    }
  }

  private void ensureOpen() throws ClosedChannelException {
    if (!open) {
      throw new ClosedChannelException();
    }
  }

  @Override
  public void seek(long offset) throws IOException {
    ensureOpen();
    this.offset = offset;
    payload = null;
    bufferIndex = 0;
    eofReached = false;
  }

  @Override
  public void setChunkSize(int blockSize) {
    this.blockSize = blockSize <= 0 ? STANDARD_BLOCK_SIZE : blockSize;
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    ensureOpen();
    if (payload == null) {
      if (eofReached) {
        return -1;
      }
      final int remaining = Math.max(dst.remaining(), blockSize);
      try {
        Tuple<String, byte[]> responsePair =
            runWithRetries(
                new Callable<Tuple<String, byte[]>>() {
                  @Override
                  public Tuple<String, byte[]> call() {
                    return rpcClient.read(objectMetadata, fetchOptions, offset, remaining);
                  }
                },
                storageOptions.getRetrySettings(),
                StorageImpl.EXCEPTION_HANDLER,
                storageOptions.getClock());
        if (responsePair.y().length > 0 && previousEtag != null && !Objects.equals(responsePair.x(), previousEtag)) {
          StringBuilder messageBuf = new StringBuilder();
          messageBuf.append("Blob ").append(resourceId).append(" was updated while reading");
          throw new StorageException(0, messageBuf.toString());
        }
        previousEtag = responsePair.x();
        payload = responsePair.y();
      } catch (RetryHelper.RetryHelperException ex) {
        throw StorageException.translateAndThrow(ex);
      }
      if (remaining > payload.length) {
        eofReached = true;
        if (payload.length == 0) {
          payload = null;
          return -1;
        }
      }
    }
    int bytesRemaining = Math.min(payload.length - bufferIndex, dst.remaining());
    dst.put(payload, bufferIndex, bytesRemaining);
    bufferIndex += bytesRemaining;
    if (bufferIndex >= payload.length) {
      offset += payload.length;
      payload = null;
      bufferIndex = 0;
    }
    return bytesRemaining;
  }

  static class BlobReadStateImpl implements RestorableState<ReadChannel>, Serializable {

    private static final long SERIAL_UID = 3889420316004453706L;

    private final StorageOptions storageOptions;
    private final BlobId resourceId;
    private final Map<StorageRpc.Option, ?> fetchOptions;
    private final String previousEtag;
    private final long offset;
    private final boolean open;
    private final boolean eofReached;
    private final int blockSize;

    BlobReadStateImpl(BlobDownloadOptionsBuilder downloadOptions) {
      this.storageOptions = downloadOptions.storageOptions;
      this.resourceId = downloadOptions.resourceId;
      this.fetchOptions = downloadOptions.fetchOptions;
      this.previousEtag = downloadOptions.previousEtag;
      this.offset = downloadOptions.offset;
      this.open = downloadOptions.open;
      this.eofReached = downloadOptions.eofReached;
      this.blockSize = downloadOptions.blockSize;
    }

    static class BlobDownloadOptionsBuilder {
      private final StorageOptions storageOptions;
      private final BlobId resourceId;
      private final Map<StorageRpc.Option, ?> fetchOptions;
      private String previousEtag;
      private long offset;
      private boolean open;
      private boolean eofReached;
      private int blockSize;

      private BlobDownloadOptionsBuilder(StorageOptions storageOpts, BlobId resourceId, Map<StorageRpc.Option, ?> optionsMap) {
        this.storageOptions = storageOpts;
        this.resourceId = resourceId;
        this.fetchOptions = optionsMap;
      }

      BlobDownloadOptionsBuilder setLastEtag(String previousEtag) {
        this.previousEtag = previousEtag;
        return this;
      }

      BlobDownloadOptionsBuilder setPosition(long offset) {
        this.offset = offset;
        return this;
      }

      BlobDownloadOptionsBuilder setIsOpen(boolean open) {
        this.open = open;
        return this;
      }

      BlobDownloadOptionsBuilder setEndOfStream(boolean eofReached) {
        this.eofReached = eofReached;
        return this;
      }

      BlobDownloadOptionsBuilder setChunkSize(int blockSize) {
        this.blockSize = blockSize;
        return this;
      }

      RestorableState<ReadChannel> buildBlobReadState() {
        return new BlobReadStateImpl(this);
      }
    }

    static BlobDownloadOptionsBuilder toBuilder(
            StorageOptions storageOpts, BlobId resourceId, Map<StorageRpc.Option, ?> optionsMap) {
      return new BlobDownloadOptionsBuilder(storageOpts, resourceId, optionsMap);
    }

    @Override
    public ReadChannel restore() {
      ReadableBlobChannel reader = new ReadableBlobChannel(storageOptions, resourceId, fetchOptions);
      reader.previousEtag = previousEtag;
      reader.offset = offset;
      reader.open = open;
      reader.eofReached = eofReached;
      reader.blockSize = blockSize;
      return reader;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
              storageOptions, resourceId, fetchOptions, previousEtag, offset, open, eofReached, blockSize);
    }

    @Override
    public boolean equals(Object candidate) {
      if (candidate == null) {
        return false;
      }
      if (!(candidate instanceof BlobReadStateImpl)) {
        return false;
      }
      final BlobReadStateImpl thatState = (BlobReadStateImpl) candidate;
      return Objects.equals(this.storageOptions, thatState.storageOptions)
          && Objects.equals(this.resourceId, thatState.resourceId)
          && Objects.equals(this.fetchOptions, thatState.fetchOptions)
          && Objects.equals(this.previousEtag, thatState.previousEtag)
          && this.offset == thatState.offset
          && this.open == thatState.open
          && this.eofReached == thatState.eofReached
          && this.blockSize == thatState.blockSize;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("blob", resourceId)
          .add("position", offset)
          .add("isOpen", open)
          .add("endOfStream", eofReached)
          .toString();
    }
  }
}
