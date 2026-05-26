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
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteOperationResponse;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Google Storage blob copy writer. A {@code CopyWriter} object allows to copy both blob's data and
 * information. To override source blob's information supply a {@code BlobInfo} to the {@code
 * CopyRequest} using either {@link StorageService.CopyOperationRequest.CopyOperationBuilder#setTarget(BlobMetadata,
 * StorageService.BlobUploadOption...)} or {@link StorageService.CopyOperationRequest.CopyOperationBuilder#setTarget(BlobMetadata,
 * Iterable)}.
 *
 * <p>This class holds the result of a copy request. If source and destination blobs share the same
 * location and storage class the copy is completed in one RPC call otherwise one or more {@link
 * #copyNextChunk} calls are necessary to complete the copy. In addition, {@link ResumableCopyWriter#getResult()}
 * can be used to automatically complete the copy and return information on the newly created blob.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
 */
public class ResumableCopyWriter implements Restorable<ResumableCopyWriter> {

    private final StorageSettings storageSettings;

    private final StorageRpcClient rpcClient;

    private RewriteOperationResponse rewriteResult;

    static class RewriteStateImpl implements RestorableState<ResumableCopyWriter>, Serializable {

        private static final long serialVersionUID = 1693964441435822700L;

        private final StorageSettings storageSettings;

        private final BlobIdentifier blobIdentifier;

        private final Map<StorageRpcClient.StorageOption, ?> blobOptions;

        private final boolean replaceExisting;

        private final BlobMetadata destinationBlob;

        private final Map<StorageRpcClient.StorageOption, ?> destinationOptions;

        private final BlobMetadata finalMetadata;

        private final long sizeBytes;

        private final boolean completedFlag;

        private final String operationToken;

        private final long bytesCopiedTotal;

        private final Long megabytesPerChunk;

        static class RewriteResultBuilder {

            private final StorageSettings storageSettings;

            private final BlobIdentifier blobIdentifier;

            private final Map<StorageRpcClient.StorageOption, ?> blobOptions;

            private final boolean replaceExisting;

            private final BlobMetadata destinationBlob;

            private final Map<StorageRpcClient.StorageOption, ?> destinationOptions;

            private BlobMetadata finalMetadata;

            private long sizeBytes;

            private boolean completedFlag;

            private String operationToken;

            private long bytesCopiedTotal;

            private Long megabytesPerChunk;

            RewriteResultBuilder setMegabytesCopiedPerChunk(Long megabytesPerChunk) {
                this.megabytesPerChunk = megabytesPerChunk;
                return this;
            }

            RestorableState<ResumableCopyWriter> buildResult() {
                return new RewriteStateImpl(this);
            }

            RewriteResultBuilder setRewriteToken(String operationToken) {
                this.operationToken = operationToken;
                return this;
            }

            RewriteResultBuilder setBlobSize(long sizeBytes) {
                this.sizeBytes = sizeBytes;
                return this;
            }

            RewriteResultBuilder setTotalBytesRewritten(long bytesRewrittenTotal) {
                this.bytesCopiedTotal = bytesRewrittenTotal;
                return this;
            }

            RewriteResultBuilder setIsDone(boolean completedFlag) {
                this.completedFlag = completedFlag;
                return this;
            }

            private RewriteResultBuilder(StorageSettings storageSettings, BlobIdentifier blobIdentifier, Map<StorageRpcClient.StorageOption, ?> blobOptions, boolean replaceExisting, BlobMetadata destinationBlob, Map<StorageRpcClient.StorageOption, ?> destinationOptions) {
                this.storageSettings = storageSettings;
                this.blobIdentifier = blobIdentifier;
                this.blobOptions = blobOptions;
                this.replaceExisting = replaceExisting;
                this.destinationBlob = destinationBlob;
                this.destinationOptions = destinationOptions;
            }

            RewriteResultBuilder setResult(BlobMetadata finalMetadata) {
                this.finalMetadata = finalMetadata;
                return this;
            }

        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("source", blobIdentifier).add("overrideInfo", replaceExisting).add("target", destinationBlob).add("result", finalMetadata).add("blobSize", sizeBytes).add("isDone", completedFlag).add("rewriteToken", operationToken).add("totalBytesCopied", bytesCopiedTotal).add("megabytesCopiedPerChunk", megabytesPerChunk).toString();
        }

        RewriteStateImpl(RewriteResultBuilder resultCreator) {
            this.storageSettings = resultCreator.storageSettings;
            this.blobIdentifier = resultCreator.blobIdentifier;
            this.blobOptions = resultCreator.blobOptions;
            this.replaceExisting = resultCreator.replaceExisting;
            this.destinationBlob = resultCreator.destinationBlob;
            this.destinationOptions = resultCreator.destinationOptions;
            this.finalMetadata = resultCreator.finalMetadata;
            this.sizeBytes = resultCreator.sizeBytes;
            this.completedFlag = resultCreator.completedFlag;
            this.operationToken = resultCreator.operationToken;
            this.bytesCopiedTotal = resultCreator.bytesCopiedTotal;
            this.megabytesPerChunk = resultCreator.megabytesPerChunk;
        }

        @Override
        public boolean equals(Object other) {
            if (null == other) {
                return false;
            }
            if (!(other instanceof RewriteStateImpl)) {
                return false;
            }
            final RewriteStateImpl thatState = (RewriteStateImpl) other;
            return Objects.equals(this.storageSettings, thatState.storageSettings) && Objects.equals(this.blobIdentifier, thatState.blobIdentifier) && Objects.equals(this.blobOptions, thatState.blobOptions) && Objects.equals(this.replaceExisting, thatState.replaceExisting) && Objects.equals(this.destinationBlob, thatState.destinationBlob) && Objects.equals(this.destinationOptions, thatState.destinationOptions) && Objects.equals(this.finalMetadata, thatState.finalMetadata) && Objects.equals(this.operationToken, thatState.operationToken) && Objects.equals(this.megabytesPerChunk, thatState.megabytesPerChunk) && thatState.sizeBytes == this.sizeBytes && thatState.completedFlag == this.completedFlag && thatState.bytesCopiedTotal == this.bytesCopiedTotal;
        }

        static RewriteResultBuilder newResultBuilder(StorageSettings storageSettings, BlobIdentifier blobIdentifier, Map<StorageRpcClient.StorageOption, ?> blobOptions, boolean replaceExisting, BlobMetadata destinationBlob, Map<StorageRpcClient.StorageOption, ?> destinationOptions) {
            return new RewriteResultBuilder(storageSettings, blobIdentifier, blobOptions, replaceExisting, destinationBlob, destinationOptions);
        }

        @Override
        public ResumableCopyWriter restore() {
            StorageRpcClient.ObjectRewriteRequest rewriteReq = new StorageRpcClient.ObjectRewriteRequest(blobIdentifier.toProto(), blobOptions, replaceExisting, destinationBlob.toProto(), destinationOptions, megabytesPerChunk);
            RewriteOperationResponse rewriteResult = new RewriteOperationResponse(rewriteReq, null != finalMetadata ? finalMetadata.toProto() : null, sizeBytes, completedFlag, operationToken, bytesCopiedTotal);
            return new ResumableCopyWriter(storageSettings, rewriteResult);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storageSettings, blobIdentifier, blobOptions, replaceExisting, destinationBlob, destinationOptions, finalMetadata, sizeBytes, completedFlag, megabytesPerChunk, operationToken, bytesCopiedTotal);
        }

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
                this.rewriteResult = runWithRetries(new Callable<RewriteOperationResponse>() {

                    @Override
                    public StorageRpcClient.RewriteOperationResponse call() {
                        return rpcClient.continueRewrite(rewriteResult);
                    }
                }, storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, storageSettings.getClock());
            } catch (RetryHelper.RetryHelperException ex) {
                throw StorageServiceException.translateThenThrow(ex);
            }
        }
    }

    /**
     * Returns {@code true} if blob copy has finished, {@code false} otherwise.
     */
    public boolean isDone() {
        return rewriteResult.isDone;
    }

    /**
     * Returns the size of the blob being copied.
     */
    public long getBlobSize() {
        return rewriteResult.blobSize;
    }

    @Override
    public RestorableState<ResumableCopyWriter> capture() {
        return RewriteStateImpl.newResultBuilder(storageSettings, BlobIdentifier.fromProto(rewriteResult.rewriteRequest.source), rewriteResult.rewriteRequest.sourceOptions, rewriteResult.rewriteRequest.overrideInfo, BlobMetadata.fromProto(rewriteResult.rewriteRequest.target), rewriteResult.rewriteRequest.targetOptions).setResult(null != rewriteResult.result ? BlobMetadata.fromProto(rewriteResult.result) : null).setBlobSize(getBlobSize()).setIsDone(isDone()).setMegabytesCopiedPerChunk(rewriteResult.rewriteRequest.megabytesRewrittenPerCall).setRewriteToken(rewriteResult.rewriteToken).setTotalBytesRewritten(getTotalBytesCopied()).buildResult();
    }

    ResumableCopyWriter(StorageSettings storageSettings, RewriteOperationResponse rewriteResult) {
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
    public CloudStorageObject getResult() {
        while (!isDone()) {
            copyNextChunk();
        }
        return CloudStorageObject.fromProto(storageSettings.getService(), rewriteResult.result);
    }

}
