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
import com.google.cloud.Restorable;
import com.google.cloud.RestorableState;
import com.google.cloud.RetryHelper;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Google Storage blob copy writer. A {@code CopyWriter} object allows to copy both blob's data and
 * information. To override source blob's information supply a {@code BlobInfo} to the {@code
 * CopyRequest} using either {@link Storage.CopyOperationRequest.DataTransferBuilder#setTarget(BlobInfo,
 * Storage.BlobUploadOption...)} or {@link Storage.CopyOperationRequest.DataTransferBuilder#setTarget(BlobInfo,
 * Iterable)}.
 *
 * <p>This class holds the result of a copy request. If source and destination blobs share the same
 * location and storage class the copy is completed in one RPC call otherwise one or more {@link
 * #copyNextChunk} calls are necessary to complete the copy. In addition, {@link ChunkedCopyWriter#getResult()}
 * can be used to automatically complete the copy and return information on the newly created blob.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
 */
public class ChunkedCopyWriter implements Restorable<ChunkedCopyWriter> {

    private final StorageSettings storageSettings;

    private final StorageRpcClient rpcClient;

    private StorageRpcClient.RewriteOperationResult rewriteResult;

    ChunkedCopyWriter(StorageSettings storageSettings, StorageRpcClient.RewriteOperationResult rewriteResult) {
        this.storageSettings = storageSettings;
        this.rewriteResult = rewriteResult;
        this.rpcClient = storageSettings.getStorageRpcV1();
    }

    /**
     * Returns the updated information for the written blob. Calling this method when {@code isDone()}
     * is {@code false} will block until all pending chunks are copied.
     *
     * <p>This method has the same effect of doing:
     *
     * <pre>{@code
     * while (!copyWriter.isDone()) {
     *    copyWriter.copyChunk();
     * }
     * }</pre>
     *
     * @throws StorageServiceException upon failure
     */
    public StorageObject getResult() {
        while (!isDone()) {
            copyNextChunk();
        }
        return StorageObject.fromProto(storageSettings.getService(), rewriteResult.result);
    }

    /**
     * Returns the size of the blob being copied.
     */
    public long getBlobSize() {
        return rewriteResult.blobSize;
    }

    /**
     * Returns {@code true} if blob copy has finished, {@code false} otherwise.
     */
    public boolean isDone() {
        return rewriteResult.isDone;
    }

    /**
     * Returns the number of bytes copied.
     */
    public long getTotalBytesCopied() {
        return rewriteResult.totalBytesRewritten;
    }

    /**
     * Copies the next chunk of the blob. An RPC is issued only if copy has not finished yet ({@link
     * #isDone} returns {@code false}).
     *
     * @throws StorageServiceException upon failure
     */
    public void copyNextChunk() {
        if (!isDone()) {
            try {
                this.rewriteResult = runWithRetries(new Callable<StorageRpcClient.RewriteOperationResult>() {

                    @Override
                    public StorageRpcClient.RewriteOperationResult call() {
                        return rpcClient.continueRewrite(rewriteResult);
                    }
                }, storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, storageSettings.getClock());
            } catch (RetryHelper.RetryHelperException retryException) {
                throw StorageServiceException.translateAndRethrow(retryException);
            }
        }
    }

    @Override
    public RestorableState<ChunkedCopyWriter> capture() {
        return BlobRewriteStateImpl.newResponseBuilder(storageSettings, BlobIdentifier.fromProto(rewriteResult.rewriteRequest.source), rewriteResult.rewriteRequest.sourceOptions, rewriteResult.rewriteRequest.overrideInfo, BlobInfo.fromProto(rewriteResult.rewriteRequest.target), rewriteResult.rewriteRequest.targetOptions).setResult(null != rewriteResult.result ? BlobInfo.fromProto(rewriteResult.result) : null).setBlobSize(getBlobSize()).setIsDone(isDone()).setMegabytesCopiedPerChunk(rewriteResult.rewriteRequest.megabytesRewrittenPerCall).setRewriteToken(rewriteResult.rewriteToken).setTotalBytesRewritten(getTotalBytesCopied()).buildRewriteResponse();
    }

    static class BlobRewriteStateImpl implements RestorableState<ChunkedCopyWriter>, Serializable {

        private static final long serialVersionUID = 1693964441435822700L;

        private final StorageSettings storageSettings;

        private final BlobIdentifier originBlob;

        private final Map<StorageRpcClient.StorageOption, ?> srcOptions;

        private final boolean shouldOverride;

        private final BlobInfo destinationBlob;

        private final Map<StorageRpcClient.StorageOption, ?> destOptions;

        private final BlobInfo finalBlob;

        private final long blobLength;

        private final boolean isCompleted;

        private final String continuationToken;

        private final long totalCopiedBytes;

        private final Long megabytesPerChunk;

        BlobRewriteStateImpl(RewriteResponseBuilder responseFactory) {
            this.storageSettings = responseFactory.storageSettings;
            this.originBlob = responseFactory.originBlob;
            this.srcOptions = responseFactory.srcOptions;
            this.shouldOverride = responseFactory.shouldOverride;
            this.destinationBlob = responseFactory.destinationBlob;
            this.destOptions = responseFactory.destOptions;
            this.finalBlob = responseFactory.finalBlob;
            this.blobLength = responseFactory.blobLength;
            this.isCompleted = responseFactory.isCompleted;
            this.continuationToken = responseFactory.continuationToken;
            this.totalCopiedBytes = responseFactory.totalCopiedBytes;
            this.megabytesPerChunk = responseFactory.megabytesPerChunk;
        }

        static class RewriteResponseBuilder {

            private final StorageSettings storageSettings;

            private final BlobIdentifier originBlob;

            private final Map<StorageRpcClient.StorageOption, ?> srcOptions;

            private final boolean shouldOverride;

            private final BlobInfo destinationBlob;

            private final Map<StorageRpcClient.StorageOption, ?> destOptions;

            private BlobInfo finalBlob;

            private long blobLength;

            private boolean isCompleted;

            private String continuationToken;

            private long totalCopiedBytes;

            private Long megabytesPerChunk;

            private RewriteResponseBuilder(StorageSettings settings, BlobIdentifier originBlob, Map<StorageRpcClient.StorageOption, ?> srcOptions, boolean shouldOverride, BlobInfo destinationBlob, Map<StorageRpcClient.StorageOption, ?> destOptions) {
                this.storageSettings = settings;
                this.originBlob = originBlob;
                this.srcOptions = srcOptions;
                this.shouldOverride = shouldOverride;
                this.destinationBlob = destinationBlob;
                this.destOptions = destOptions;
            }

            RewriteResponseBuilder setResult(BlobInfo finalBlob) {
                this.finalBlob = finalBlob;
                return this;
            }

            RewriteResponseBuilder setBlobSize(long blobLength) {
                this.blobLength = blobLength;
                return this;
            }

            RewriteResponseBuilder setIsDone(boolean isCompleted) {
                this.isCompleted = isCompleted;
                return this;
            }

            RewriteResponseBuilder setRewriteToken(String continuationToken) {
                this.continuationToken = continuationToken;
                return this;
            }

            RewriteResponseBuilder setTotalBytesRewritten(long bytesCopiedTotal) {
                this.totalCopiedBytes = bytesCopiedTotal;
                return this;
            }

            RewriteResponseBuilder setMegabytesCopiedPerChunk(Long megabytesPerChunk) {
                this.megabytesPerChunk = megabytesPerChunk;
                return this;
            }

            RestorableState<ChunkedCopyWriter> buildRewriteResponse() {
                return new BlobRewriteStateImpl(this);
            }
        }

        static RewriteResponseBuilder newResponseBuilder(StorageSettings settings, BlobIdentifier originBlob, Map<StorageRpcClient.StorageOption, ?> srcOptions, boolean shouldOverride, BlobInfo destinationBlob, Map<StorageRpcClient.StorageOption, ?> destOptions) {
            return new RewriteResponseBuilder(settings, originBlob, srcOptions, shouldOverride, destinationBlob, destOptions);
        }

        @Override
        public ChunkedCopyWriter restore() {
            StorageRpcClient.RewriteOperationRequest operationRequest = new StorageRpcClient.RewriteOperationRequest(originBlob.toProto(), srcOptions, shouldOverride, destinationBlob.toProto(), destOptions, megabytesPerChunk);
            StorageRpcClient.RewriteOperationResult rewriteResult = new StorageRpcClient.RewriteOperationResult(operationRequest, null != finalBlob ? finalBlob.toProto() : null, blobLength, isCompleted, continuationToken, totalCopiedBytes);
            return new ChunkedCopyWriter(storageSettings, rewriteResult);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storageSettings, originBlob, srcOptions, shouldOverride, destinationBlob, destOptions, finalBlob, blobLength, isCompleted, megabytesPerChunk, continuationToken, totalCopiedBytes);
        }

        @Override
        public boolean equals(Object otherObject) {
            if (null == otherObject) {
                return false;
            }
            if (!(otherObject instanceof BlobRewriteStateImpl)) {
                return false;
            }
            final BlobRewriteStateImpl thatState = (BlobRewriteStateImpl) otherObject;
            return Objects.equals(this.storageSettings, thatState.storageSettings) && Objects.equals(this.originBlob, thatState.originBlob) && Objects.equals(this.srcOptions, thatState.srcOptions) && Objects.equals(this.shouldOverride, thatState.shouldOverride) && Objects.equals(this.destinationBlob, thatState.destinationBlob) && Objects.equals(this.destOptions, thatState.destOptions) && Objects.equals(this.finalBlob, thatState.finalBlob) && Objects.equals(this.continuationToken, thatState.continuationToken) && Objects.equals(this.megabytesPerChunk, thatState.megabytesPerChunk) && thatState.blobLength == this.blobLength && thatState.isCompleted == this.isCompleted && thatState.totalCopiedBytes == this.totalCopiedBytes;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("source", originBlob).add("overrideInfo", shouldOverride).add("target", destinationBlob).add("result", finalBlob).add("blobSize", blobLength).add("isDone", isCompleted).add("rewriteToken", continuationToken).add("totalBytesCopied", totalCopiedBytes).add("megabytesCopiedPerChunk", megabytesPerChunk).toString();
        }
    }
}
