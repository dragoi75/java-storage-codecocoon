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
import com.google.api.client.util.Preconditions;
import com.google.api.gax.retrying.ResultRetryAlgorithm;
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

/**
 * Default implementation for ReadChannel.
 */
class BlobReadChannel implements ReadChannel {

    private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;

    private final StorageClientOptions serviceOptions;

    private final BlobId blob;

    private final Map<StorageRpcClient.StorageOption, ?> requestOptions;

    private final RetryAlgorithmManager retryAlgorithmManager;

    private String lastEtag;

    private long position;

    private boolean isOpen;

    private boolean endOfStream;

    private int chunkSize = DEFAULT_CHUNK_SIZE;

    private final StorageRpcClient storageRpc;

    private final StorageObject storageObject;

    private int bufferPos;

    private byte[] buffer;

    private long limit;

    BlobReadChannel(StorageClientOptions serviceOptions, BlobId blob, Map<StorageRpcClient.StorageOption, ?> requestOptions) {
        this.serviceOptions = serviceOptions;
        this.blob = blob;
        this.requestOptions = requestOptions;
        this.retryAlgorithmManager = serviceOptions.getRetryAlgorithmManager();
        isOpen = true;
        storageRpc = serviceOptions.getStorageRpcV1();
        storageObject = blob.toProto();
        this.limit = Long.MAX_VALUE;
    }

    @Override
    public RestorableState<ReadChannel> capture() {
        StateImpl.Builder builder = StateImpl.builder(serviceOptions, blob, requestOptions).setPosition(position).setIsOpen(isOpen).setEndOfStream(endOfStream).setChunkSize(chunkSize).setLimit(limit);
        if (null != buffer) {
            builder.setPosition(position + bufferPos);
            builder.setEndOfStream(false);
        }
        return builder.build();
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public void close() {
        if (isOpen) {
            buffer = null;
            isOpen = false;
        }
    }

    private void validateOpen() throws ClosedChannelException {
        if (!isOpen) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public void seek(long position) throws IOException {
        validateOpen();
        this.position = position;
        buffer = null;
        bufferPos = 0;
        endOfStream = false;
    }

    @Override
    public void setChunkSize(int chunkSize) {
        this.chunkSize = 0 >= chunkSize ? DEFAULT_CHUNK_SIZE : chunkSize;
    }

    @Override
    public int read(ByteBuffer byteBuffer) throws IOException {
        validateOpen();
        if (null == buffer) {
            if (endOfStream) {
                return -1;
            }
            final int toRead = Math.toIntExact(Math.min(limit - position, Math.max(byteBuffer.remaining(), chunkSize)));
            if (0 >= toRead) {
                endOfStream = true;
                return -1;
            }
            try {
                ResultRetryAlgorithm<?> algorithm = retryAlgorithmManager.getForObjectsGet(storageObject, requestOptions);
                Tuple<String, byte[]> result = runWithRetries(() -> storageRpc.read(storageObject, requestOptions, position, toRead), serviceOptions.getRetrySettings(), algorithm, serviceOptions.getClock());
                String etag = result.x();
                byte[] bytes = result.y();
                if (0 < bytes.length && null != lastEtag && !Objects.equals(etag, lastEtag)) {
                    throw new IOException("Blob " + blob + " was updated while reading");
                }
                lastEtag = etag;
                buffer = bytes;
            } catch (RetryHelper.RetryHelperException e) {
                throw new IOException(e);
            }
            if (buffer.length < toRead) {
                endOfStream = true;
                if (0 == buffer.length) {
                    buffer = null;
                    return -1;
                }
            }
        }
        int toWrite = Math.min(buffer.length - bufferPos, byteBuffer.remaining());
        byteBuffer.put(buffer, bufferPos, toWrite);
        bufferPos += toWrite;
        if (buffer.length <= bufferPos) {
            position += buffer.length;
            buffer = null;
            bufferPos = 0;
        }
        return toWrite;
    }

    @Override
    public ReadChannel limit(long limit) {
        Preconditions.checkArgument(0 <= limit, "Limit must be >= 0");
        this.limit = limit;
        return this;
    }

    @Override
    public long limit() {
        return limit;
    }

    static class StateImpl implements RestorableState<ReadChannel>, Serializable {

        private static final long serialVersionUID = 3889420316004453706L;

        private final StorageClientOptions serviceOptions;

        private final BlobId blob;

        private final Map<StorageRpcClient.StorageOption, ?> requestOptions;

        private final String lastEtag;

        private final long position;

        private final boolean isOpen;

        private final boolean endOfStream;

        private final int chunkSize;

        private final long limit;

        StateImpl(Builder builder) {
            this.serviceOptions = builder.serviceOptions;
            this.blob = builder.blob;
            this.requestOptions = builder.requestOptions;
            this.lastEtag = builder.lastEtag;
            this.position = builder.position;
            this.isOpen = builder.isOpen;
            this.endOfStream = builder.endOfStream;
            this.chunkSize = builder.chunkSize;
            this.limit = builder.limit;
        }

        static class Builder {

            private final StorageClientOptions serviceOptions;

            private final BlobId blob;

            private final Map<StorageRpcClient.StorageOption, ?> requestOptions;

            private String lastEtag;

            private long position;

            private boolean isOpen;

            private boolean endOfStream;

            private int chunkSize;

            private long limit;

            private Builder(StorageClientOptions options, BlobId blob, Map<StorageRpcClient.StorageOption, ?> reqOptions) {
                this.serviceOptions = options;
                this.blob = blob;
                this.requestOptions = reqOptions;
            }

            Builder setLastEtag(String lastEtag) {
                this.lastEtag = lastEtag;
                return this;
            }

            Builder setPosition(long position) {
                this.position = position;
                return this;
            }

            Builder setIsOpen(boolean isOpen) {
                this.isOpen = isOpen;
                return this;
            }

            Builder setEndOfStream(boolean endOfStream) {
                this.endOfStream = endOfStream;
                return this;
            }

            Builder setChunkSize(int chunkSize) {
                this.chunkSize = chunkSize;
                return this;
            }

            Builder setLimit(long limit) {
                this.limit = limit;
                return this;
            }

            RestorableState<ReadChannel> build() {
                return new StateImpl(this);
            }
        }

        static Builder builder(StorageClientOptions options, BlobId blob, Map<StorageRpcClient.StorageOption, ?> reqOptions) {
            return new Builder(options, blob, reqOptions);
        }

        @Override
        public ReadChannel restore() {
            BlobReadChannel channel = new BlobReadChannel(serviceOptions, blob, requestOptions);
            channel.lastEtag = lastEtag;
            channel.position = position;
            channel.isOpen = isOpen;
            channel.endOfStream = endOfStream;
            channel.chunkSize = chunkSize;
            channel.limit = limit;
            return channel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(serviceOptions, blob, requestOptions, lastEtag, position, isOpen, endOfStream, chunkSize, limit);
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj) {
                return false;
            }
            if (!(obj instanceof StateImpl)) {
                return false;
            }
            final StateImpl other = (StateImpl) obj;
            return Objects.equals(this.serviceOptions, other.serviceOptions) && Objects.equals(this.blob, other.blob) && Objects.equals(this.requestOptions, other.requestOptions) && Objects.equals(this.lastEtag, other.lastEtag) && other.position == this.position && other.isOpen == this.isOpen && other.endOfStream == this.endOfStream && other.chunkSize == this.chunkSize && other.limit == this.limit;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("blob", blob).add("position", position).add("isOpen", isOpen).add("endOfStream", endOfStream).add("limit", limit).toString();
        }
    }
}
