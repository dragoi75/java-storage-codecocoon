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
import com.google.cloud.storage.spi.v1.StorageRpcClient.ObjectRewriteRequest;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteResult;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Google Storage blob copy writer. A {@code CopyWriter} object allows to copy both blob's data and
 * information. To override source blob's information supply a {@code BlobInfo} to the {@code
 * CopyRequest} using either {@link CloudStorageClient.CopyOperationRequest.CopyOperationBuilder#setTarget(BlobMetadata,
 * CloudStorageClient.BlobUploadOption...)} or {@link CloudStorageClient.CopyOperationRequest.CopyOperationBuilder#setTarget(BlobMetadata,
 * Iterable)}.
 *
 * <p>This class holds the result of a copy request. If source and destination blobs share the same
 * location and storage class the copy is completed in one RPC call otherwise one or more {@link
 * #copySegment} calls are necessary to complete the copy. In addition, {@link BlobRewriteWriter#getResult()}
 * can be used to automatically complete the copy and return information on the newly created blob.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
 */
public class BlobRewriteWriter implements Restorable<BlobRewriteWriter> {

    private final StorageClientOptions clientOptions;

    private final StorageRpcClient rpcClient;

    private RewriteResult rewriteResult;

    BlobRewriteWriter(StorageClientOptions clientOptions, RewriteResult rewriteResult) {
        this.clientOptions = clientOptions;
        this.rewriteResult = rewriteResult;
        this.rpcClient = clientOptions.getStorageRpcV1();
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
            copySegment();
        }
        return StorageObject.fromProto(clientOptions.getService(), rewriteResult.result);
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
    public void copySegment() {
        if (!isDone()) {
            try {
                this.rewriteResult = runWithRetries(new Callable<RewriteResult>() {

                    @Override
                    public StorageRpcClient.RewriteResult call() {
                        return rpcClient.continueRewrite(rewriteResult);
                    }
                }, clientOptions.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, clientOptions.getClock());
            } catch (RetryHelper.RetryHelperException retryException) {
                throw StorageServiceException.translateThenThrow(retryException);
            }
        }
    }

    @Override
    public RestorableState<BlobRewriteWriter> capture() {
        return RewriteStateImpl.newBlobRewriteBuilder(clientOptions, BlobIdentifier.fromProto(rewriteResult.rewriteRequest.source), rewriteResult.rewriteRequest.sourceOptions, rewriteResult.rewriteRequest.overrideInfo, BlobMetadata.fromProto(rewriteResult.rewriteRequest.target), rewriteResult.rewriteRequest.targetOptions).setResult(null != rewriteResult.result ? BlobMetadata.fromProto(rewriteResult.result) : null).setBlobSize(getBlobSize()).setIsDone(isDone()).setMegabytesCopiedPerChunk(rewriteResult.rewriteRequest.megabytesRewrittenPerCall).setRewriteToken(rewriteResult.rewriteToken).setTotalBytesRewritten(getTotalBytesCopied()).buildState();
    }

    static class RewriteStateImpl implements RestorableState<BlobRewriteWriter>, Serializable {

        private static final long serialVersionUID = 1693964441435822700L;

        private final StorageClientOptions clientOptions;

        private final BlobIdentifier originBlobId;

        private final Map<StorageRpcClient.StorageOption, ?> originOptions;

        private final boolean overwriteFlag;

        private final BlobMetadata destinationMetadata;

        private final Map<StorageRpcClient.StorageOption, ?> destinationOptions;

        private final BlobMetadata outcomeMetadata;

        private final long totalBytes;

        private final boolean completedFlag;

        private final String continuationToken;

        private final long bytesCopiedTotal;

        private final Long megabytesPerChunk;

        RewriteStateImpl(BlobRewriteBuilder rewriteCreator) {
            this.clientOptions = rewriteCreator.clientOptions;
            this.originBlobId = rewriteCreator.originBlobId;
            this.originOptions = rewriteCreator.originOptions;
            this.overwriteFlag = rewriteCreator.overwriteFlag;
            this.destinationMetadata = rewriteCreator.destinationMetadata;
            this.destinationOptions = rewriteCreator.destinationOptions;
            this.outcomeMetadata = rewriteCreator.outcomeMetadata;
            this.totalBytes = rewriteCreator.totalBytes;
            this.completedFlag = rewriteCreator.completedFlag;
            this.continuationToken = rewriteCreator.continuationToken;
            this.bytesCopiedTotal = rewriteCreator.bytesCopiedTotal;
            this.megabytesPerChunk = rewriteCreator.megabytesPerChunk;
        }

        static class BlobRewriteBuilder {

            private final StorageClientOptions clientOptions;

            private final BlobIdentifier originBlobId;

            private final Map<StorageRpcClient.StorageOption, ?> originOptions;

            private final boolean overwriteFlag;

            private final BlobMetadata destinationMetadata;

            private final Map<StorageRpcClient.StorageOption, ?> destinationOptions;

            private BlobMetadata outcomeMetadata;

            private long totalBytes;

            private boolean completedFlag;

            private String continuationToken;

            private long bytesCopiedTotal;

            private Long megabytesPerChunk;

            private BlobRewriteBuilder(StorageClientOptions clientConfig, BlobIdentifier originBlobId, Map<StorageRpcClient.StorageOption, ?> originOptions, boolean overwriteFlag, BlobMetadata destinationMetadata, Map<StorageRpcClient.StorageOption, ?> destinationOptions) {
                this.clientOptions = clientConfig;
                this.originBlobId = originBlobId;
                this.originOptions = originOptions;
                this.overwriteFlag = overwriteFlag;
                this.destinationMetadata = destinationMetadata;
                this.destinationOptions = destinationOptions;
            }

            BlobRewriteBuilder setResult(BlobMetadata outcomeMetadata) {
                this.outcomeMetadata = outcomeMetadata;
                return this;
            }

            BlobRewriteBuilder setBlobSize(long totalBytes) {
                this.totalBytes = totalBytes;
                return this;
            }

            BlobRewriteBuilder setIsDone(boolean completedFlag) {
                this.completedFlag = completedFlag;
                return this;
            }

            BlobRewriteBuilder setRewriteToken(String continuationToken) {
                this.continuationToken = continuationToken;
                return this;
            }

            BlobRewriteBuilder setTotalBytesRewritten(long bytesRewritten) {
                this.bytesCopiedTotal = bytesRewritten;
                return this;
            }

            BlobRewriteBuilder setMegabytesCopiedPerChunk(Long megabytesPerChunk) {
                this.megabytesPerChunk = megabytesPerChunk;
                return this;
            }

            RestorableState<BlobRewriteWriter> buildState() {
                return new RewriteStateImpl(this);
            }
        }

        static BlobRewriteBuilder newBlobRewriteBuilder(StorageClientOptions clientConfig, BlobIdentifier originBlobId, Map<StorageRpcClient.StorageOption, ?> originOptions, boolean overwriteFlag, BlobMetadata destinationMetadata, Map<StorageRpcClient.StorageOption, ?> destinationOptions) {
            return new BlobRewriteBuilder(clientConfig, originBlobId, originOptions, overwriteFlag, destinationMetadata, destinationOptions);
        }

        @Override
        public BlobRewriteWriter restore() {
            ObjectRewriteRequest objectRequest = new ObjectRewriteRequest(originBlobId.toProto(), originOptions, overwriteFlag, destinationMetadata.toProto(), destinationOptions, megabytesPerChunk);
            RewriteResult rewriteResult = new RewriteResult(objectRequest, null != outcomeMetadata ? outcomeMetadata.toProto() : null, totalBytes, completedFlag, continuationToken, bytesCopiedTotal);
            return new BlobRewriteWriter(clientOptions, rewriteResult);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientOptions, originBlobId, originOptions, overwriteFlag, destinationMetadata, destinationOptions, outcomeMetadata, totalBytes, completedFlag, megabytesPerChunk, continuationToken, bytesCopiedTotal);
        }

        @Override
        public boolean equals(Object otherObject) {
            if (null == otherObject) {
                return false;
            }
            if (!(otherObject instanceof RewriteStateImpl)) {
                return false;
            }
            final RewriteStateImpl otherState = (RewriteStateImpl) otherObject;
            return Objects.equals(this.clientOptions, otherState.clientOptions) && Objects.equals(this.originBlobId, otherState.originBlobId) && Objects.equals(this.originOptions, otherState.originOptions) && Objects.equals(this.overwriteFlag, otherState.overwriteFlag) && Objects.equals(this.destinationMetadata, otherState.destinationMetadata) && Objects.equals(this.destinationOptions, otherState.destinationOptions) && Objects.equals(this.outcomeMetadata, otherState.outcomeMetadata) && Objects.equals(this.continuationToken, otherState.continuationToken) && Objects.equals(this.megabytesPerChunk, otherState.megabytesPerChunk) && otherState.totalBytes == this.totalBytes && otherState.completedFlag == this.completedFlag && otherState.bytesCopiedTotal == this.bytesCopiedTotal;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("source", originBlobId).add("overrideInfo", overwriteFlag).add("target", destinationMetadata).add("result", outcomeMetadata).add("blobSize", totalBytes).add("isDone", completedFlag).add("rewriteToken", continuationToken).add("totalBytesCopied", bytesCopiedTotal).add("megabytesCopiedPerChunk", megabytesPerChunk).toString();
        }
    }
}
