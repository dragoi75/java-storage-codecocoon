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

import com.google.cloud.Restorable;
import com.google.cloud.RestorableState;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteOperationRequest;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteResult;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Google Storage blob copy writer. A {@code CopyWriter} object allows to copy both blob's data and
 * information. To override source blob's information supply a {@code BlobInfo} to the {@code
 * CopyRequest} using either {@link Storage.ChunkedCopyRequest.CopyOperationBuilder#setTarget(BlobMetadata,
 * Storage.BlobUploadOption...)} or {@link Storage.ChunkedCopyRequest.CopyOperationBuilder#setTarget(BlobMetadata,
 * Iterable)}.
 *
 * <p>This class holds the result of a copy request. If source and destination blobs share the same
 * location and storage class the copy is completed in one RPC call otherwise one or more {@link
 * #copySegment} calls are necessary to complete the copy. In addition, {@link ObjectCopyWriter#getResult()}
 * can be used to automatically complete the copy and return information on the newly created blob.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
 */
public class ObjectCopyWriter implements Restorable<ObjectCopyWriter> {

  private final StorageClientOptions clientOptions;
  private final StorageRpcClient rpcClient;
  private RewriteResult rewriteResult;

  ObjectCopyWriter(StorageClientOptions clientOptions, RewriteResult rewriteResult) {
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

  /** Returns the size of the blob being copied. */
  public long getBlobSize() {
    return rewriteResult.blobSize;
  }

  /** Returns {@code true} if blob copy has finished, {@code false} otherwise. */
  public boolean isDone() {
    return rewriteResult.isDone;
  }

  /** Returns the number of bytes copied. */
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
      StorageRpcClient.RewriteOperationRequest operationRequest = rewriteResult.rewriteRequest;
      this.rewriteResult =
          Retrying.run(
                  clientOptions,
              clientOptions.getRetryAlgorithmManager().getForObjectsRewrite(operationRequest),
              () -> rpcClient.continueRewrite(rewriteResult),
              Function.identity());
    }
  }

  @Override
  public RestorableState<ObjectCopyWriter> capture() {
    return RewriteState.createBuilder(
                    clientOptions,
            BlobId.fromProto(rewriteResult.rewriteRequest.source),
            rewriteResult.rewriteRequest.sourceOptions,
            rewriteResult.rewriteRequest.overrideInfo,
            BlobMetadata.fromProto(rewriteResult.rewriteRequest.target),
            rewriteResult.rewriteRequest.targetOptions)
        .setResult(rewriteResult.result != null ? BlobMetadata.fromProto(rewriteResult.result) : null)
        .setBlobSize(getBlobSize())
        .setIsDone(isDone())
        .setMegabytesCopiedPerChunk(rewriteResult.rewriteRequest.megabytesRewrittenPerCall)
        .setRewriteToken(rewriteResult.rewriteToken)
        .setTotalBytesRewritten(getTotalBytesCopied())
        .create();
  }

  static class RewriteState implements RestorableState<ObjectCopyWriter>, Serializable {

    private static final long serialVersionUID = 1693964441435822700L;

    private final StorageClientOptions clientOptions;
    private final BlobId originBlobId;
    private final Map<StorageRpcClient.StorageOption, ?> originOptions;
    private final boolean forceOverwrite;
    private final BlobMetadata destinationBlob;
    private final Map<StorageRpcClient.StorageOption, ?> destinationOptions;
    private final BlobMetadata finalMetadata;
    private final long contentLength;
    private final boolean completed;
    private final String operationToken;
    private final long bytesTransferredTotal;
    private final Long megabytesPerChunk;

    RewriteState(RewriteResponseBuilder responseFactory) {
      this.clientOptions = responseFactory.clientOptions;
      this.originBlobId = responseFactory.originBlobId;
      this.originOptions = responseFactory.originOptions;
      this.forceOverwrite = responseFactory.forceOverwrite;
      this.destinationBlob = responseFactory.destinationBlob;
      this.destinationOptions = responseFactory.destinationOptions;
      this.finalMetadata = responseFactory.finalMetadata;
      this.contentLength = responseFactory.contentLength;
      this.completed = responseFactory.completed;
      this.operationToken = responseFactory.operationToken;
      this.bytesTransferredTotal = responseFactory.bytesTransferredTotal;
      this.megabytesPerChunk = responseFactory.megabytesPerChunk;
    }

    static class RewriteResponseBuilder {

      private final StorageClientOptions clientOptions;
      private final BlobId originBlobId;
      private final Map<StorageRpcClient.StorageOption, ?> originOptions;
      private final boolean forceOverwrite;
      private final BlobMetadata destinationBlob;
      private final Map<StorageRpcClient.StorageOption, ?> destinationOptions;
      private BlobMetadata finalMetadata;
      private long contentLength;
      private boolean completed;
      private String operationToken;
      private long bytesTransferredTotal;
      private Long megabytesPerChunk;

      private RewriteResponseBuilder(
          StorageClientOptions clientOptions,
          BlobId originBlobId,
          Map<StorageRpcClient.StorageOption, ?> originOptions,
          boolean forceOverwrite,
          BlobMetadata destinationBlob,
          Map<StorageRpcClient.StorageOption, ?> destinationOptions) {
        this.clientOptions = clientOptions;
        this.originBlobId = originBlobId;
        this.originOptions = originOptions;
        this.forceOverwrite = forceOverwrite;
        this.destinationBlob = destinationBlob;
        this.destinationOptions = destinationOptions;
      }

      RewriteResponseBuilder setResult(BlobMetadata finalMetadata) {
        this.finalMetadata = finalMetadata;
        return this;
      }

      RewriteResponseBuilder setBlobSize(long contentLength) {
        this.contentLength = contentLength;
        return this;
      }

      RewriteResponseBuilder setIsDone(boolean completed) {
        this.completed = completed;
        return this;
      }

      RewriteResponseBuilder setRewriteToken(String operationToken) {
        this.operationToken = operationToken;
        return this;
      }

      RewriteResponseBuilder setTotalBytesRewritten(long rewrittenBytesCount) {
        this.bytesTransferredTotal = rewrittenBytesCount;
        return this;
      }

      RewriteResponseBuilder setMegabytesCopiedPerChunk(Long megabytesPerChunk) {
        this.megabytesPerChunk = megabytesPerChunk;
        return this;
      }

      RestorableState<ObjectCopyWriter> create() {
        return new RewriteState(this);
      }
    }

    static RewriteResponseBuilder createBuilder(
        StorageClientOptions clientOptions,
        BlobId originBlobId,
        Map<StorageRpcClient.StorageOption, ?> originOptions,
        boolean forceOverwrite,
        BlobMetadata destinationBlob,
        Map<StorageRpcClient.StorageOption, ?> destinationOptions) {
      return new RewriteResponseBuilder(clientOptions, originBlobId, originOptions, forceOverwrite, destinationBlob, destinationOptions);
    }

    @Override
    public ObjectCopyWriter restore() {
      RewriteOperationRequest operationRequest =
          new RewriteOperationRequest(
              originBlobId.toProto(),
                  originOptions,
                  forceOverwrite,
              destinationBlob.toProto(),
                  destinationOptions,
                  megabytesPerChunk);
      RewriteResult rewriteResult =
          new StorageRpcClient.RewriteResult(
                  operationRequest,
              finalMetadata != null ? finalMetadata.toProto() : null,
                  contentLength,
                  completed,
                  operationToken,
                  bytesTransferredTotal);
      return new ObjectCopyWriter(clientOptions, rewriteResult);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
              clientOptions,
              originBlobId,
              originOptions,
              forceOverwrite,
              destinationBlob,
              destinationOptions,
              finalMetadata,
              contentLength,
              completed,
              megabytesPerChunk,
              operationToken,
              bytesTransferredTotal);
    }

    @Override
    public boolean equals(Object candidate) {
      if (candidate == null) {
        return false;
      }
      if (!(candidate instanceof RewriteState)) {
        return false;
      }
      final RewriteState thatState = (RewriteState) candidate;
      return Objects.equals(this.clientOptions, thatState.clientOptions)
          && Objects.equals(this.originBlobId, thatState.originBlobId)
          && Objects.equals(this.originOptions, thatState.originOptions)
          && Objects.equals(this.forceOverwrite, thatState.forceOverwrite)
          && Objects.equals(this.destinationBlob, thatState.destinationBlob)
          && Objects.equals(this.destinationOptions, thatState.destinationOptions)
          && Objects.equals(this.finalMetadata, thatState.finalMetadata)
          && Objects.equals(this.operationToken, thatState.operationToken)
          && Objects.equals(this.megabytesPerChunk, thatState.megabytesPerChunk)
          && this.contentLength == thatState.contentLength
          && this.completed == thatState.completed
          && this.bytesTransferredTotal == thatState.bytesTransferredTotal;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("source", originBlobId)
          .add("overrideInfo", forceOverwrite)
          .add("target", destinationBlob)
          .add("result", finalMetadata)
          .add("blobSize", contentLength)
          .add("isDone", completed)
          .add("rewriteToken", operationToken)
          .add("totalBytesCopied", bytesTransferredTotal)
          .add("megabytesCopiedPerChunk", megabytesPerChunk)
          .toString();
    }
  }
}
