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

/**
 * Default implementation for ReadChannel.
 */
class BlobReaderChannel implements ReadChannel {

    private static final int DEFAULT_BLOCK_SIZE = 2 * 1024 * 1024;

    private final StorageSettings storageSettings;

    private final BlobIdentifier objectIdentifier;

    private final Map<StorageRpcClient.StorageOption, ?> requestParams;

    private String previousEtag;

    private long offset;

    private boolean openFlag;

    private boolean streamEnded;

    private int blockSize = DEFAULT_BLOCK_SIZE;

    private final StorageRpcClient rpcClient;

    private final StorageObject storageEntity;

    private int byteIndex;

    private byte[] byteStore;

    static class StateSnapshotImpl implements RestorableState<ReadChannel>, Serializable {

        private static final long serialVersionUID = 3889420316004453706L;

        private final StorageSettings storageSettings;

        private final BlobIdentifier objectIdentifier;

        private final Map<StorageRpcClient.StorageOption, ?> requestParams;

        private final String previousEtag;

        private final long offset;

        private final boolean openFlag;

        private final boolean streamEnded;

        private final int blockSize;

        static class BlobStreamBuilder {

            private final StorageSettings storageSettings;

            private final BlobIdentifier objectIdentifier;

            private final Map<StorageRpcClient.StorageOption, ?> requestParams;

            private String previousEtag;

            private long offset;

            private boolean openFlag;

            private boolean streamEnded;

            private int blockSize;

            BlobStreamBuilder setChunkSize(int blockSize) {
                this.blockSize = blockSize;
                return this;
            }

            BlobStreamBuilder setEndOfStream(boolean streamEnded) {
                this.streamEnded = streamEnded;
                return this;
            }

            RestorableState<ReadChannel> buildSnapshot() {
                return new StateSnapshotImpl(this);
            }

            BlobStreamBuilder setPosition(long offset) {
                this.offset = offset;
                return this;
            }

            BlobStreamBuilder setIsOpen(boolean openFlag) {
                this.openFlag = openFlag;
                return this;
            }

            BlobStreamBuilder setLastEtag(String previousEtag) {
                this.previousEtag = previousEtag;
                return this;
            }

            private BlobStreamBuilder(StorageSettings storageSettings, BlobIdentifier objectIdentifier, Map<StorageRpcClient.StorageOption, ?> requestParams) {
                this.storageSettings = storageSettings;
                this.objectIdentifier = objectIdentifier;
                this.requestParams = requestParams;
            }

        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("blob", objectIdentifier).add("position", offset).add("isOpen", openFlag).add("endOfStream", streamEnded).toString();
        }

        @Override
        public boolean equals(Object otherObject) {
            if (null == otherObject) {
                return false;
            }
            if (!(otherObject instanceof StateSnapshotImpl)) {
                return false;
            }
            final StateSnapshotImpl thatSnapshot = (StateSnapshotImpl) otherObject;
            return Objects.equals(this.storageSettings, thatSnapshot.storageSettings) && Objects.equals(this.objectIdentifier, thatSnapshot.objectIdentifier) && Objects.equals(this.requestParams, thatSnapshot.requestParams) && Objects.equals(this.previousEtag, thatSnapshot.previousEtag) && thatSnapshot.offset == this.offset && thatSnapshot.openFlag == this.openFlag && thatSnapshot.streamEnded == this.streamEnded && thatSnapshot.blockSize == this.blockSize;
        }

        StateSnapshotImpl(BlobStreamBuilder streamCreator) {
            this.storageSettings = streamCreator.storageSettings;
            this.objectIdentifier = streamCreator.objectIdentifier;
            this.requestParams = streamCreator.requestParams;
            this.previousEtag = streamCreator.previousEtag;
            this.offset = streamCreator.offset;
            this.openFlag = streamCreator.openFlag;
            this.streamEnded = streamCreator.streamEnded;
            this.blockSize = streamCreator.blockSize;
        }

        @Override
        public ReadChannel restore() {
            BlobReaderChannel reader = new BlobReaderChannel(storageSettings, objectIdentifier, requestParams);
            reader.previousEtag = previousEtag;
            reader.offset = offset;
            reader.openFlag = openFlag;
            reader.streamEnded = streamEnded;
            reader.blockSize = blockSize;
            return reader;
        }

        static BlobStreamBuilder newBuilder(StorageSettings storageSettings, BlobIdentifier objectIdentifier, Map<StorageRpcClient.StorageOption, ?> requestOptions) {
            return new BlobStreamBuilder(storageSettings, objectIdentifier, requestOptions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storageSettings, objectIdentifier, requestParams, previousEtag, offset, openFlag, streamEnded, blockSize);
        }

    }

    @Override
    public void setChunkSize(int blockSize) {
        this.blockSize = 0 >= blockSize ? DEFAULT_BLOCK_SIZE : blockSize;
    }

    @Override
    public boolean isOpen() {
        return openFlag;
    }

    @Override
    public int read(ByteBuffer byteStore) throws IOException {
        ensureOpen();
        if (null == this.byteStore) {
            if (streamEnded) {
                return -1;
            }
            final int remaining = Math.max(byteStore.remaining(), blockSize);
            try {
                Tuple<String, byte[]> readTuple = runWithRetries(new Callable<Tuple<String, byte[]>>() {

                    @Override
                    public Tuple<String, byte[]> call() {
                        return rpcClient.read(storageEntity, requestParams, offset, remaining);
                    }
                }, storageSettings.getRetrySettings(), DefaultStorageImpl.EXCEPTION_HANDLER, storageSettings.getClock());
                if (0 < readTuple.y().length && null != previousEtag && !Objects.equals(readTuple.x(), previousEtag)) {
                    StringBuilder msgAggregator = new StringBuilder();
                    msgAggregator.append("Blob ").append(objectIdentifier).append(" was updated while reading");
                    throw new StorageOperationException(0, msgAggregator.toString());
                }
                previousEtag = readTuple.x();
                this.byteStore = readTuple.y();
            } catch (RetryHelper.RetryHelperException retryException) {
                throw StorageOperationException.translateAndRethrow(retryException);
            }
            if (this.byteStore.length < remaining) {
                streamEnded = true;
                if (0 == this.byteStore.length) {
                    this.byteStore = null;
                    return -1;
                }
            }
        }
        int writeCount = Math.min(this.byteStore.length - byteIndex, byteStore.remaining());
        byteStore.put(this.byteStore, byteIndex, writeCount);
        byteIndex += writeCount;
        if (this.byteStore.length <= byteIndex) {
            offset += this.byteStore.length;
            this.byteStore = null;
            byteIndex = 0;
        }
        return writeCount;
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
        byteStore = null;
        byteIndex = 0;
        streamEnded = false;
    }

    @Override
    public void close() {
        if (openFlag) {
            byteStore = null;
            openFlag = false;
        }
    }

    BlobReaderChannel(StorageSettings storageSettings, BlobIdentifier objectIdentifier, Map<StorageRpcClient.StorageOption, ?> requestParams) {
        this.storageSettings = storageSettings;
        this.objectIdentifier = objectIdentifier;
        this.requestParams = requestParams;
        openFlag = true;
        rpcClient = storageSettings.getStorageRpcV1();
        storageEntity = objectIdentifier.toStorageObject();
    }

    @Override
    public RestorableState<ReadChannel> capture() {
        StateSnapshotImpl.BlobStreamBuilder streamCreator = StateSnapshotImpl.newBuilder(storageSettings, objectIdentifier, requestParams).setPosition(offset).setIsOpen(openFlag).setEndOfStream(streamEnded).setChunkSize(blockSize);
        if (null != byteStore) {
            streamCreator.setPosition(offset + byteIndex);
            streamCreator.setEndOfStream(false);
        }
        return streamCreator.buildSnapshot();
    }

}
