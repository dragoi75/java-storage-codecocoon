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
class BlobInputStream implements ReadChannel {

    private static final int STANDARD_CHUNK_SIZE = 2 * 1024 * 1024;

    private final StorageSettings storageSettings;

    private final BlobIdentifier objectId;

    private final Map<StorageRpcClient.StorageOption, ?> optionsMap;

    private String previousEtag;

    private long cursor;

    private boolean openFlag;

    private boolean streamEnded;

    private int segmentSize = STANDARD_CHUNK_SIZE;

    private final StorageRpcClient rpcClient;

    private final StorageObject objectData;

    private int positionInBuffer;

    private byte[] dataBuffer;

    static class StreamStateImpl implements RestorableState<ReadChannel>, Serializable {

        private static final long serialVersionUID = 3889420316004453706L;

        private final StorageSettings storageSettings;

        private final BlobIdentifier objectId;

        private final Map<StorageRpcClient.StorageOption, ?> optionsMap;

        private final String previousEtag;

        private final long cursor;

        private final boolean openFlag;

        private final boolean streamEnded;

        private final int segmentSize;

        static class BlobStreamBuilder {

            private final StorageSettings storageSettings;

            private final BlobIdentifier objectId;

            private final Map<StorageRpcClient.StorageOption, ?> optionsMap;

            private String previousEtag;

            private long cursor;

            private boolean openFlag;

            private boolean streamEnded;

            private int segmentSize;

            RestorableState<ReadChannel> buildStream() {
                return new StreamStateImpl(this);
            }

            BlobStreamBuilder setChunkSize(int segmentSize) {
                this.segmentSize = segmentSize;
                return this;
            }

            BlobStreamBuilder setEndOfStream(boolean streamEnded) {
                this.streamEnded = streamEnded;
                return this;
            }

            BlobStreamBuilder setPosition(long cursor) {
                this.cursor = cursor;
                return this;
            }

            private BlobStreamBuilder(StorageSettings settings, BlobIdentifier objectId, Map<StorageRpcClient.StorageOption, ?> requestParams) {
                this.storageSettings = settings;
                this.objectId = objectId;
                this.optionsMap = requestParams;
            }

            BlobStreamBuilder setIsOpen(boolean openFlag) {
                this.openFlag = openFlag;
                return this;
            }

            BlobStreamBuilder setLastEtag(String previousEtag) {
                this.previousEtag = previousEtag;
                return this;
            }

        }

        @Override
        public int hashCode() {
            return Objects.hash(storageSettings, objectId, optionsMap, previousEtag, cursor, openFlag, streamEnded, segmentSize);
        }

        @Override
        public boolean equals(Object other) {
            if (null == other) {
                return false;
            }
            if (!(other instanceof StreamStateImpl)) {
                return false;
            }
            final StreamStateImpl comparedState = (StreamStateImpl) other;
            return Objects.equals(this.storageSettings, comparedState.storageSettings) && Objects.equals(this.objectId, comparedState.objectId) && Objects.equals(this.optionsMap, comparedState.optionsMap) && Objects.equals(this.previousEtag, comparedState.previousEtag) && comparedState.cursor == this.cursor && comparedState.openFlag == this.openFlag && comparedState.streamEnded == this.streamEnded && comparedState.segmentSize == this.segmentSize;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("blob", objectId).add("position", cursor).add("isOpen", openFlag).add("endOfStream", streamEnded).toString();
        }

        StreamStateImpl(BlobStreamBuilder streamFactory) {
            this.storageSettings = streamFactory.storageSettings;
            this.objectId = streamFactory.objectId;
            this.optionsMap = streamFactory.optionsMap;
            this.previousEtag = streamFactory.previousEtag;
            this.cursor = streamFactory.cursor;
            this.openFlag = streamFactory.openFlag;
            this.streamEnded = streamFactory.streamEnded;
            this.segmentSize = streamFactory.segmentSize;
        }

        @Override
        public ReadChannel restore() {
            BlobInputStream inputStream = new BlobInputStream(storageSettings, objectId, optionsMap);
            inputStream.previousEtag = previousEtag;
            inputStream.cursor = cursor;
            inputStream.openFlag = openFlag;
            inputStream.streamEnded = streamEnded;
            inputStream.segmentSize = segmentSize;
            return inputStream;
        }

        static BlobStreamBuilder newBuilder(StorageSettings settings, BlobIdentifier objectId, Map<StorageRpcClient.StorageOption, ?> requestParams) {
            return new BlobStreamBuilder(settings, objectId, requestParams);
        }

    }

    @Override
    public void seek(long cursor) throws IOException {
        ensureOpen();
        this.cursor = cursor;
        dataBuffer = null;
        positionInBuffer = 0;
        streamEnded = false;
    }

    @Override
    public void setChunkSize(int segmentSize) {
        this.segmentSize = 0 >= segmentSize ? STANDARD_CHUNK_SIZE : segmentSize;
    }

    @Override
    public int read(ByteBuffer dstBuffer) throws IOException {
        ensureOpen();
        if (null == dataBuffer) {
            if (streamEnded) {
                return -1;
            }
            final int bytesToRead = Math.max(dstBuffer.remaining(), segmentSize);
            try {
                Tuple<String, byte[]> readOutcome = runWithRetries(new Callable<Tuple<String, byte[]>>() {

                    @Override
                    public Tuple<String, byte[]> call() {
                        return rpcClient.read(objectData, optionsMap, cursor, bytesToRead);
                    }
                }, storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, storageSettings.getClock());
                if (0 < readOutcome.y().length && null != previousEtag && !Objects.equals(readOutcome.x(), previousEtag)) {
                    StringBuilder messageBuf = new StringBuilder();
                    messageBuf.append("Blob ").append(objectId).append(" was updated while reading");
                    throw new IOException(messageBuf.toString());
                }
                previousEtag = readOutcome.x();
                dataBuffer = readOutcome.y();
            } catch (RetryHelper.RetryHelperException retryEx) {
                throw new IOException(retryEx);
            }
            if (dataBuffer.length < bytesToRead) {
                streamEnded = true;
                if (0 == dataBuffer.length) {
                    dataBuffer = null;
                    return -1;
                }
            }
        }
        int bytesToWrite = Math.min(dataBuffer.length - positionInBuffer, dstBuffer.remaining());
        dstBuffer.put(dataBuffer, positionInBuffer, bytesToWrite);
        positionInBuffer += bytesToWrite;
        if (dataBuffer.length <= positionInBuffer) {
            cursor += dataBuffer.length;
            dataBuffer = null;
            positionInBuffer = 0;
        }
        return bytesToWrite;
    }

    @Override
    public RestorableState<ReadChannel> capture() {
        StreamStateImpl.BlobStreamBuilder streamFactory = StreamStateImpl.newBuilder(storageSettings, objectId, optionsMap).setPosition(cursor).setIsOpen(openFlag).setEndOfStream(streamEnded).setChunkSize(segmentSize);
        if (null != dataBuffer) {
            streamFactory.setPosition(cursor + positionInBuffer);
            streamFactory.setEndOfStream(false);
        }
        return streamFactory.buildStream();
    }

    @Override
    public void close() {
        if (openFlag) {
            dataBuffer = null;
            openFlag = false;
        }
    }

    @Override
    public boolean isOpen() {
        return openFlag;
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!openFlag) {
            throw new ClosedChannelException();
        }
    }

    BlobInputStream(StorageSettings storageSettings, BlobIdentifier objectId, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        this.storageSettings = storageSettings;
        this.objectId = objectId;
        this.optionsMap = optionsMap;
        openFlag = true;
        rpcClient = storageSettings.getStorageRpcV1();
        objectData = objectId.toProto();
    }

}
