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
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient;
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient.RewriteOperationResponse;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Google Storage blob copy writer. A {@code CopyWriter} object allows to copy both blob's data and
 * information. To override source blob's information supply a {@code BlobInfo} to the {@code
 * CopyRequest} using either {@link StorageClient.DataCopyRequest.CopyOperationBuilder#setTarget(BlobAttributes,
 * StorageClient.BlobUploadOption...)} or {@link StorageClient.DataCopyRequest.CopyOperationBuilder#setTarget(BlobAttributes,
 * Iterable)}.
 *
 * <p>This class holds the result of a copy request. If source and destination blobs share the same
 * location and storage class the copy is completed in one RPC call otherwise one or more {@link
 * #copyNextChunk} calls are necessary to complete the copy. In addition, {@link BlobCopyWriter#getResult()}
 * can be used to automatically complete the copy and return information on the newly created blob.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
 */
public class BlobCopyWriter implements Restorable<BlobCopyWriter> {

    private final StorageSettings storageSettings;

    private final CloudStorageRpcClient cloudStorageClient;

    private CloudStorageRpcClient.RewriteOperationResponse rewriteResult;

    BlobCopyWriter(StorageSettings storageSettings, CloudStorageRpcClient.RewriteOperationResponse rewriteResult) {
        this.storageSettings = storageSettings;
        this.rewriteResult = rewriteResult;
        this.cloudStorageClient = storageSettings.getStorageRpcV1();
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
     * @throws StorageOperationException upon failure
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
     * @throws StorageOperationException upon failure
     */
    public void copyNextChunk() {
        if (!isDone()) {
            try {
                this.rewriteResult = runWithRetries(new Callable<CloudStorageRpcClient.RewriteOperationResponse>() {

                    @Override
                    public CloudStorageRpcClient.RewriteOperationResponse call() {
                        return cloudStorageClient.continueRewrite(rewriteResult);
                    }
                }, storageSettings.getRetrySettings(), StorageImpl.EXCEPTION_HANDLER, storageSettings.getClock());
            } catch (RetryHelper.RetryHelperException retryException) {
                throw StorageOperationException.translateAndRethrow(retryException);
            }
        }
    }

    @Override
    public RestorableState<BlobCopyWriter> capture() {
        return PersistentRewriteState.createBuilder(storageSettings, BlobIdentifier.fromProto(rewriteResult.rewriteRequest.source), rewriteResult.rewriteRequest.sourceOptions, rewriteResult.rewriteRequest.overrideInfo, BlobAttributes.fromProto(rewriteResult.rewriteRequest.target), rewriteResult.rewriteRequest.targetOptions).setResult(null != rewriteResult.result ? BlobAttributes.fromProto(rewriteResult.result) : null).setBlobSize(getBlobSize()).setIsDone(isDone()).setMegabytesCopiedPerChunk(rewriteResult.rewriteRequest.megabytesRewrittenPerCall).setRewriteToken(rewriteResult.rewriteToken).setTotalBytesRewritten(getTotalBytesCopied()).buildResponse();
    }

    static class PersistentRewriteState implements RestorableState<BlobCopyWriter>, Serializable {

        private static final long serialVersionUID = 1693964441435822700L;

        private final StorageSettings storageSettings;

        private final BlobIdentifier originIdentifier;

        private final Map<CloudStorageRpcClient.StorageOption, ?> originOptions;

        private final boolean shouldReplace;

        private final BlobAttributes destination;

        private final Map<CloudStorageRpcClient.StorageOption, ?> destinationOptions;

        private final BlobAttributes finalAttributes;

        private final long contentSize;

        private final boolean isComplete;

        private final String operationToken;

        private final long bytesTransferred;

        private final Long mbPerChunk;

        PersistentRewriteState(RewriteResponseBuilder responseFactory) {
            this.storageSettings = responseFactory.storageSettings;
            this.originIdentifier = responseFactory.originIdentifier;
            this.originOptions = responseFactory.originOptions;
            this.shouldReplace = responseFactory.shouldReplace;
            this.destination = responseFactory.destination;
            this.destinationOptions = responseFactory.destinationOptions;
            this.finalAttributes = responseFactory.finalAttributes;
            this.contentSize = responseFactory.contentSize;
            this.isComplete = responseFactory.isComplete;
            this.operationToken = responseFactory.operationToken;
            this.bytesTransferred = responseFactory.bytesTransferred;
            this.mbPerChunk = responseFactory.mbPerChunk;
        }

        static class RewriteResponseBuilder {

            private final StorageSettings storageSettings;

            private final BlobIdentifier originIdentifier;

            private final Map<CloudStorageRpcClient.StorageOption, ?> originOptions;

            private final boolean shouldReplace;

            private final BlobAttributes destination;

            private final Map<CloudStorageRpcClient.StorageOption, ?> destinationOptions;

            private BlobAttributes finalAttributes;

            private long contentSize;

            private boolean isComplete;

            private String operationToken;

            private long bytesTransferred;

            private Long mbPerChunk;

            private RewriteResponseBuilder(StorageSettings settings, BlobIdentifier originIdentifier, Map<CloudStorageRpcClient.StorageOption, ?> originOptions, boolean shouldReplace, BlobAttributes destination, Map<CloudStorageRpcClient.StorageOption, ?> destinationOptions) {
                this.storageSettings = settings;
                this.originIdentifier = originIdentifier;
                this.originOptions = originOptions;
                this.shouldReplace = shouldReplace;
                this.destination = destination;
                this.destinationOptions = destinationOptions;
            }

            RewriteResponseBuilder setResult(BlobAttributes finalAttributes) {
                this.finalAttributes = finalAttributes;
                return this;
            }

            RewriteResponseBuilder setBlobSize(long contentSize) {
                this.contentSize = contentSize;
                return this;
            }

            RewriteResponseBuilder setIsDone(boolean isComplete) {
                this.isComplete = isComplete;
                return this;
            }

            RewriteResponseBuilder setRewriteToken(String operationToken) {
                this.operationToken = operationToken;
                return this;
            }

            RewriteResponseBuilder setTotalBytesRewritten(long bytesTransferredTotal) {
                this.bytesTransferred = bytesTransferredTotal;
                return this;
            }

            RewriteResponseBuilder setMegabytesCopiedPerChunk(Long mbPerChunk) {
                this.mbPerChunk = mbPerChunk;
                return this;
            }

            RestorableState<BlobCopyWriter> buildResponse() {
                return new PersistentRewriteState(this);
            }
        }

        static RewriteResponseBuilder createBuilder(StorageSettings settings, BlobIdentifier originIdentifier, Map<CloudStorageRpcClient.StorageOption, ?> originOptions, boolean shouldReplace, BlobAttributes destination, Map<CloudStorageRpcClient.StorageOption, ?> destinationOptions) {
            return new RewriteResponseBuilder(settings, originIdentifier, originOptions, shouldReplace, destination, destinationOptions);
        }

        @Override
        public BlobCopyWriter restore() {
            CloudStorageRpcClient.RewriteOperationRequest operationRequest = new CloudStorageRpcClient.RewriteOperationRequest(originIdentifier.toStorageObject(), originOptions, shouldReplace, destination.toProto(), destinationOptions, mbPerChunk);
            CloudStorageRpcClient.RewriteOperationResponse rewriteResult = new RewriteOperationResponse(operationRequest, null != finalAttributes ? finalAttributes.toProto() : null, contentSize, isComplete, operationToken, bytesTransferred);
            return new BlobCopyWriter(storageSettings, rewriteResult);
        }

        @Override
        public int hashCode() {
            return Objects.hash(storageSettings, originIdentifier, originOptions, shouldReplace, destination, destinationOptions, finalAttributes, contentSize, isComplete, mbPerChunk, operationToken, bytesTransferred);
        }

        @Override
        public boolean equals(Object otherObject) {
            if (null == otherObject) {
                return false;
            }
            if (!(otherObject instanceof PersistentRewriteState)) {
                return false;
            }
            final PersistentRewriteState comparedState = (PersistentRewriteState) otherObject;
            return Objects.equals(this.storageSettings, comparedState.storageSettings) && Objects.equals(this.originIdentifier, comparedState.originIdentifier) && Objects.equals(this.originOptions, comparedState.originOptions) && Objects.equals(this.shouldReplace, comparedState.shouldReplace) && Objects.equals(this.destination, comparedState.destination) && Objects.equals(this.destinationOptions, comparedState.destinationOptions) && Objects.equals(this.finalAttributes, comparedState.finalAttributes) && Objects.equals(this.operationToken, comparedState.operationToken) && Objects.equals(this.mbPerChunk, comparedState.mbPerChunk) && comparedState.contentSize == this.contentSize && comparedState.isComplete == this.isComplete && comparedState.bytesTransferred == this.bytesTransferred;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("source", originIdentifier).add("overrideInfo", shouldReplace).add("target", destination).add("result", finalAttributes).add("blobSize", contentSize).add("isDone", isComplete).add("rewriteToken", operationToken).add("totalBytesCopied", bytesTransferred).add("megabytesCopiedPerChunk", mbPerChunk).toString();
        }
    }
}
