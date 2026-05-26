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
class BlobInputChannel implements ReadChannel {

    private static final int DEFAULT_READ_CHUNK_SIZE = 2 * 1024 * 1024;

    private final StorageSettings storageSettings;

    private final BlobIdentifier identifier;

    private final Map<StorageRpcClient.StorageOption, ?> optionsMap;

    private String previousEtag;

    private long offset;

    private boolean openFlag;

    private boolean eofReached;

    private int segmentSize = DEFAULT_READ_CHUNK_SIZE;

    private final StorageRpcClient rpcClient;

    private final StorageObject objectMetadata;

    private int bufferIndex;

    private byte[] dataBuffer;

    static class BlobReadState implements RestorableState<ReadChannel>, Serializable {

        private static final long serialVersionUID = 3889420316004453706L;

        private final StorageSettings storageSettings;

        private final BlobIdentifier identifier;

        private final Map<StorageRpcClient.StorageOption, ?> optionsMap;

        private final String previousEtag;

        private final long offset;

        private final boolean openFlag;

        private final boolean eofReached;

        private final int segmentSize;

        static class BlobReadRequestBuilder {

            private final StorageSettings storageSettings;

            private final BlobIdentifier identifier;

            private final Map<StorageRpcClient.StorageOption, ?> optionsMap;

            private String previousEtag;

            private long offset;

            private boolean openFlag;

            private boolean eofReached;

            private int segmentSize;

            BlobReadRequestBuilder setEndOfStream(boolean eofReached) {
                this.eofReached = eofReached;
                return this;
            }

            BlobReadRequestBuilder setChunkSize(int segmentSize) {
                this.segmentSize = segmentSize;
                return this;
            }

            BlobReadRequestBuilder setPosition(long offset) {
                this.offset = offset;
                return this;
            }

            RestorableState<ReadChannel> buildRequest() {
                return new BlobReadState(this);
            }

            BlobReadRequestBuilder setLastEtag(String previousEtag) {
                this.previousEtag = previousEtag;
                return this;
            }

            BlobReadRequestBuilder setIsOpen(boolean openFlag) {
                this.openFlag = openFlag;
                return this;
            }

            private BlobReadRequestBuilder(StorageSettings storageSettings, BlobIdentifier identifier, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
                this.storageSettings = storageSettings;
                this.identifier = identifier;
                this.optionsMap = optionsMap;
            }

        }

        @Override
        public int hashCode() {
            return Objects.hash(storageSettings, identifier, optionsMap, previousEtag, offset, openFlag, eofReached, segmentSize);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("blob", identifier).add("position", offset).add("isOpen", openFlag).add("endOfStream", eofReached).toString();
        }

        @Override
        public boolean equals(Object otherObject) {
            if (null == otherObject) {
                return false;
            }
            if (!(otherObject instanceof BlobReadState)) {
                return false;
            }
            final BlobReadState otherState = (BlobReadState) otherObject;
            return Objects.equals(this.storageSettings, otherState.storageSettings) && Objects.equals(this.identifier, otherState.identifier) && Objects.equals(this.optionsMap, otherState.optionsMap) && Objects.equals(this.previousEtag, otherState.previousEtag) && otherState.offset == this.offset && otherState.openFlag == this.openFlag && otherState.eofReached == this.eofReached && otherState.segmentSize == this.segmentSize;
        }

        @Override
        public ReadChannel restore() {
            BlobInputChannel inputChannel = new BlobInputChannel(storageSettings, identifier, optionsMap);
            inputChannel.previousEtag = previousEtag;
            inputChannel.offset = offset;
            inputChannel.openFlag = openFlag;
            inputChannel.eofReached = eofReached;
            inputChannel.segmentSize = segmentSize;
            return inputChannel;
        }

        BlobReadState(BlobReadRequestBuilder requestMaker) {
            this.storageSettings = requestMaker.storageSettings;
            this.identifier = requestMaker.identifier;
            this.optionsMap = requestMaker.optionsMap;
            this.previousEtag = requestMaker.previousEtag;
            this.offset = requestMaker.offset;
            this.openFlag = requestMaker.openFlag;
            this.eofReached = requestMaker.eofReached;
            this.segmentSize = requestMaker.segmentSize;
        }

        static BlobReadRequestBuilder newBuilder(StorageSettings storageSettings, BlobIdentifier identifier, Map<StorageRpcClient.StorageOption, ?> requestOptions) {
            return new BlobReadRequestBuilder(storageSettings, identifier, requestOptions);
        }

    }

    private void ensureOpen() throws ClosedChannelException {
        if (!openFlag) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public void setChunkSize(int segmentSize) {
        this.segmentSize = 0 >= segmentSize ? DEFAULT_READ_CHUNK_SIZE : segmentSize;
    }

    @Override
    public int read(ByteBuffer destBuffer) throws IOException {
        ensureOpen();
        if (null == dataBuffer) {
            if (eofReached) {
                return -1;
            }
            final int bytesRemaining = Math.max(destBuffer.remaining(), segmentSize);
            try {
                Tuple<String, byte[]> readTuple = runWithRetries(new Callable<Tuple<String, byte[]>>() {

                    @Override
                    public Tuple<String, byte[]> call() {
                        return rpcClient.read(objectMetadata, optionsMap, offset, bytesRemaining);
                    }
                }, storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, storageSettings.getClock());
                if (0 < readTuple.y().length && null != previousEtag && !Objects.equals(readTuple.x(), previousEtag)) {
                    StringBuilder msgBuffer = new StringBuilder();
                    msgBuffer.append("Blob ").append(identifier).append(" was updated while reading");
                    throw new StorageServiceException(0, msgBuffer.toString());
                }
                previousEtag = readTuple.x();
                dataBuffer = readTuple.y();
            } catch (RetryHelper.RetryHelperException ex) {
                throw StorageServiceException.translateThenThrow(ex);
            }
            if (dataBuffer.length < bytesRemaining) {
                eofReached = true;
                if (0 == dataBuffer.length) {
                    dataBuffer = null;
                    return -1;
                }
            }
        }
        int bytesToWrite = Math.min(dataBuffer.length - bufferIndex, destBuffer.remaining());
        destBuffer.put(dataBuffer, bufferIndex, bytesToWrite);
        bufferIndex += bytesToWrite;
        if (dataBuffer.length <= bufferIndex) {
            offset += dataBuffer.length;
            dataBuffer = null;
            bufferIndex = 0;
        }
        return bytesToWrite;
    }

    @Override
    public void seek(long offset) throws IOException {
        ensureOpen();
        this.offset = offset;
        dataBuffer = null;
        bufferIndex = 0;
        eofReached = false;
    }

    @Override
    public void close() {
        if (openFlag) {
            dataBuffer = null;
            openFlag = false;
        }
    }

    BlobInputChannel(StorageSettings storageSettings, BlobIdentifier identifier, Map<StorageRpcClient.StorageOption, ?> optionsMap) {
        this.storageSettings = storageSettings;
        this.identifier = identifier;
        this.optionsMap = optionsMap;
        openFlag = true;
        rpcClient = storageSettings.getStorageRpcV1();
        objectMetadata = identifier.toProto();
    }

    @Override
    public boolean isOpen() {
        return openFlag;
    }

    @Override
    public RestorableState<ReadChannel> capture() {
        BlobReadState.BlobReadRequestBuilder requestMaker = BlobReadState.newBuilder(storageSettings, identifier, optionsMap).setPosition(offset).setIsOpen(openFlag).setEndOfStream(eofReached).setChunkSize(segmentSize);
        if (null != dataBuffer) {
            requestMaker.setPosition(offset + bufferIndex);
            requestMaker.setEndOfStream(false);
        }
        return requestMaker.buildRequest();
    }

}
