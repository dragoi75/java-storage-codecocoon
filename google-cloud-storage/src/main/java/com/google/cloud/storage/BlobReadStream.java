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
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
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
class BlobReadStream implements ReadChannel {

    private static final int DEFAULT_SEGMENT_SIZE = 2 * 1024 * 1024;

    private final StorageSettings storageSettings;

    private final BlobIdentifier objectIdentifier;

    private final Map<CloudStorageRpc.StorageOption, ?> operationParams;

    private String previousVersionTag;

    private long currentIndex;

    private boolean openFlag;

    private boolean streamEnded;

    private int segmentSize = DEFAULT_SEGMENT_SIZE;

    private final CloudStorageRpc cloudRpcClient;

    private final StorageObject objectMetadata;

    private int bufferIndex;

    private byte[] bytesArray;

    static class StateSnapshot implements RestorableState<ReadChannel>, Serializable {

        private static final long serialVersionUID = 3889420316004453706L;

        private final StorageSettings storageSettings;

        private final BlobIdentifier objectIdentifier;

        private final Map<CloudStorageRpc.StorageOption, ?> operationParams;

        private final String previousVersionTag;

        private final long currentIndex;

        private final boolean openFlag;

        private final boolean streamEnded;

        private final int segmentSize;

        static class BlobReadRequestBuilder {

            private final StorageSettings storageSettings;

            private final BlobIdentifier objectIdentifier;

            private final Map<CloudStorageRpc.StorageOption, ?> operationParams;

            private String previousVersionTag;

            private long currentIndex;

            private boolean openFlag;

            private boolean streamEnded;

            private int segmentSize;

            BlobReadRequestBuilder setIsOpen(boolean openFlag) {
                this.openFlag = openFlag;
                return this;
            }

            BlobReadRequestBuilder setChunkSize(int segmentSize) {
                this.segmentSize = segmentSize;
                return this;
            }

            RestorableState<ReadChannel> buildSnapshot() {
                return new StateSnapshot(this);
            }

            BlobReadRequestBuilder setEndOfStream(boolean streamEnded) {
                this.streamEnded = streamEnded;
                return this;
            }

            BlobReadRequestBuilder setLastEtag(String previousVersionTag) {
                this.previousVersionTag = previousVersionTag;
                return this;
            }

            BlobReadRequestBuilder setPosition(long currentIndex) {
                this.currentIndex = currentIndex;
                return this;
            }

            private BlobReadRequestBuilder(StorageSettings storageSettings, BlobIdentifier objectIdentifier, Map<CloudStorageRpc.StorageOption, ?> requestParams) {
                this.storageSettings = storageSettings;
                this.objectIdentifier = objectIdentifier;
                this.operationParams = requestParams;
            }

        }

        @Override
        public int hashCode() {
            return Objects.hash(storageSettings, objectIdentifier, operationParams, previousVersionTag, currentIndex, openFlag, streamEnded, segmentSize);
        }

        @Override
        public boolean equals(Object candidate) {
            if (null == candidate) {
                return false;
            }
            if (!(candidate instanceof StateSnapshot)) {
                return false;
            }
            final StateSnapshot thatSnapshot = (StateSnapshot) candidate;
            return Objects.equals(this.storageSettings, thatSnapshot.storageSettings) && Objects.equals(this.objectIdentifier, thatSnapshot.objectIdentifier) && Objects.equals(this.operationParams, thatSnapshot.operationParams) && Objects.equals(this.previousVersionTag, thatSnapshot.previousVersionTag) && thatSnapshot.currentIndex == this.currentIndex && thatSnapshot.openFlag == this.openFlag && thatSnapshot.streamEnded == this.streamEnded && thatSnapshot.segmentSize == this.segmentSize;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("blob", objectIdentifier).add("position", currentIndex).add("isOpen", openFlag).add("endOfStream", streamEnded).toString();
        }

        StateSnapshot(BlobReadRequestBuilder requestMaker) {
            this.storageSettings = requestMaker.storageSettings;
            this.objectIdentifier = requestMaker.objectIdentifier;
            this.operationParams = requestMaker.operationParams;
            this.previousVersionTag = requestMaker.previousVersionTag;
            this.currentIndex = requestMaker.currentIndex;
            this.openFlag = requestMaker.openFlag;
            this.streamEnded = requestMaker.streamEnded;
            this.segmentSize = requestMaker.segmentSize;
        }

        static BlobReadRequestBuilder newBuilder(StorageSettings storageSettings, BlobIdentifier objectIdentifier, Map<CloudStorageRpc.StorageOption, ?> requestParams) {
            return new BlobReadRequestBuilder(storageSettings, objectIdentifier, requestParams);
        }

        @Override
        public ReadChannel restore() {
            BlobReadStream stream = new BlobReadStream(storageSettings, objectIdentifier, operationParams);
            stream.previousVersionTag = previousVersionTag;
            stream.currentIndex = currentIndex;
            stream.openFlag = openFlag;
            stream.streamEnded = streamEnded;
            stream.segmentSize = segmentSize;
            return stream;
        }

    }

    @Override
    public void close() {
        if (openFlag) {
            bytesArray = null;
            openFlag = false;
        }
    }

    @Override
    public void seek(long currentIndex) throws IOException {
        ensureOpen();
        this.currentIndex = currentIndex;
        bytesArray = null;
        bufferIndex = 0;
        streamEnded = false;
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!openFlag) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public void setChunkSize(int segmentSize) {
        this.segmentSize = 0 >= segmentSize ? DEFAULT_SEGMENT_SIZE : segmentSize;
    }

    @Override
    public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        if (null == bytesArray) {
            if (streamEnded) {
                return -1;
            }
            final int numToRead = Math.max(destination.remaining(), segmentSize);
            try {
                Tuple<String, byte[]> readResult = runWithRetries(new Callable<Tuple<String, byte[]>>() {

                    @Override
                    public Tuple<String, byte[]> call() {
                        return cloudRpcClient.read(objectMetadata, operationParams, currentIndex, numToRead);
                    }
                }, storageSettings.getRetrySettings(), StorageClientImpl.EXCEPTION_HANDLER, storageSettings.getClock());
                if (0 < readResult.y().length && null != previousVersionTag && !Objects.equals(readResult.x(), previousVersionTag)) {
                    StringBuilder msgAccumulator = new StringBuilder();
                    msgAccumulator.append("Blob ").append(objectIdentifier).append(" was updated while reading");
                    throw new StorageOperationException(0, msgAccumulator.toString());
                }
                previousVersionTag = readResult.x();
                bytesArray = readResult.y();
            } catch (RetryHelper.RetryHelperException exception) {
                throw StorageOperationException.translateAndRethrow(exception);
            }
            if (bytesArray.length < numToRead) {
                streamEnded = true;
                if (0 == bytesArray.length) {
                    bytesArray = null;
                    return -1;
                }
            }
        }
        int bytesToWrite = Math.min(bytesArray.length - bufferIndex, destination.remaining());
        destination.put(bytesArray, bufferIndex, bytesToWrite);
        bufferIndex += bytesToWrite;
        if (bytesArray.length <= bufferIndex) {
            currentIndex += bytesArray.length;
            bytesArray = null;
            bufferIndex = 0;
        }
        return bytesToWrite;
    }

    @Override
    public RestorableState<ReadChannel> capture() {
        StateSnapshot.BlobReadRequestBuilder requestMaker = StateSnapshot.newBuilder(storageSettings, objectIdentifier, operationParams).setPosition(currentIndex).setIsOpen(openFlag).setEndOfStream(streamEnded).setChunkSize(segmentSize);
        if (null != bytesArray) {
            requestMaker.setPosition(currentIndex + bufferIndex);
            requestMaker.setEndOfStream(false);
        }
        return requestMaker.buildSnapshot();
    }

    BlobReadStream(StorageSettings storageSettings, BlobIdentifier objectIdentifier, Map<CloudStorageRpc.StorageOption, ?> operationParams) {
        this.storageSettings = storageSettings;
        this.objectIdentifier = objectIdentifier;
        this.operationParams = operationParams;
        openFlag = true;
        cloudRpcClient = storageSettings.getStorageRpcV1();
        objectMetadata = objectIdentifier.toProto();
    }

    @Override
    public boolean isOpen() {
        return openFlag;
    }

}
