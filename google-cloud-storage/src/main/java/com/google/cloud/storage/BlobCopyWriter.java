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
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
import com.google.cloud.storage.spi.v1.StorageServiceRpc.ObjectRewriteRequest;
import com.google.cloud.storage.spi.v1.StorageServiceRpc.RewriteResult;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Google Storage blob copy writer. A {@code CopyWriter} object allows to copy both blob's data and
 * information. To override source blob's information supply a {@code BlobInfo} to the {@code
 * CopyRequest} using either {@link CloudStorageClient.CopyOperationRequest.CopyJobBuilder#setTarget(BlobMetadata,
 * CloudStorageClient.BlobUploadOptions...)} or {@link CloudStorageClient.CopyOperationRequest.CopyJobBuilder#setTarget(BlobMetadata,
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

  private final StorageClientOptions storageOptions;
  private final StorageServiceRpc serviceRpc;
  private RewriteResult rewriteResult;

  BlobCopyWriter(StorageClientOptions storageOptions, RewriteResult rewriteResult) {
    this.storageOptions = storageOptions;
    this.rewriteResult = rewriteResult;
    this.serviceRpc = storageOptions.getStorageRpcV1();
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
  public StorageBlob getResult() {
    while (!isDone()) {
      copyNextChunk();
    }
    return StorageBlob.fromProto(storageOptions.getService(), rewriteResult.result);
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
  public void copyNextChunk() {
    if (!isDone()) {
      try {
        this.rewriteResult =
            runWithRetries(
                new Callable<StorageServiceRpc.RewriteResult>() {
                  @Override
                  public StorageServiceRpc.RewriteResult call() {
                    return serviceRpc.continueRewrite(rewriteResult);
                  }
                },
                storageOptions.getRetrySettings(),
                DefaultStorage.EXCEPTION_HANDLER,
                storageOptions.getClock());
      } catch (RetryHelper.RetryHelperException retryException) {
        throw StorageServiceException.translateAndRethrow(retryException);
      }
    }
  }

  @Override
  public RestorableState<BlobCopyWriter> capture() {
    return RewriteOperationState.newRewriteResponseBuilder(
                    storageOptions,
            BlobIdentifier.fromProto(rewriteResult.rewriteRequest.source),
            rewriteResult.rewriteRequest.sourceOptions,
            rewriteResult.rewriteRequest.overrideInfo,
            BlobMetadata.fromPb(rewriteResult.rewriteRequest.target),
            rewriteResult.rewriteRequest.targetOptions)
        .setResult(rewriteResult.result != null ? BlobMetadata.fromPb(rewriteResult.result) : null)
        .setBlobSize(getBlobSize())
        .setIsDone(isDone())
        .setMegabytesCopiedPerChunk(rewriteResult.rewriteRequest.megabytesRewrittenPerCall)
        .setRewriteToken(rewriteResult.rewriteToken)
        .setTotalBytesRewritten(getTotalBytesCopied())
        .buildRewriteResponse();
  }

  static class RewriteOperationState implements RestorableState<BlobCopyWriter>, Serializable {

    private static final long serialVersionUID = 1693964441435822700L;

    private final StorageClientOptions storageOptions;
    private final BlobIdentifier originBlob;
    private final Map<StorageServiceRpc.StorageOption, ?> inputOptions;
    private final boolean shouldOverride;
    private final BlobMetadata destinationBlob;
    private final Map<StorageServiceRpc.StorageOption, ?> destinationOptions;
    private final BlobMetadata outcomeMetadata;
    private final long blobLength;
    private final boolean completed;
    private final String continuationToken;
    private final long bytesCopiedTotal;
    private final Long mbPerChunk;

    RewriteOperationState(RewriteResponseBuilder responseBuilder) {
      this.storageOptions = responseBuilder.storageOptions;
      this.originBlob = responseBuilder.originBlob;
      this.inputOptions = responseBuilder.inputOptions;
      this.shouldOverride = responseBuilder.shouldOverride;
      this.destinationBlob = responseBuilder.destinationBlob;
      this.destinationOptions = responseBuilder.destinationOptions;
      this.outcomeMetadata = responseBuilder.outcomeMetadata;
      this.blobLength = responseBuilder.blobLength;
      this.completed = responseBuilder.completed;
      this.continuationToken = responseBuilder.continuationToken;
      this.bytesCopiedTotal = responseBuilder.bytesCopiedTotal;
      this.mbPerChunk = responseBuilder.mbPerChunk;
    }

    static class RewriteResponseBuilder {

      private final StorageClientOptions storageOptions;
      private final BlobIdentifier originBlob;
      private final Map<StorageServiceRpc.StorageOption, ?> inputOptions;
      private final boolean shouldOverride;
      private final BlobMetadata destinationBlob;
      private final Map<StorageServiceRpc.StorageOption, ?> destinationOptions;
      private BlobMetadata outcomeMetadata;
      private long blobLength;
      private boolean completed;
      private String continuationToken;
      private long bytesCopiedTotal;
      private Long mbPerChunk;

      private RewriteResponseBuilder(
          StorageClientOptions clientOptions,
          BlobIdentifier originBlob,
          Map<StorageServiceRpc.StorageOption, ?> inputOptions,
          boolean shouldOverride,
          BlobMetadata destinationBlob,
          Map<StorageServiceRpc.StorageOption, ?> destinationOptions) {
        this.storageOptions = clientOptions;
        this.originBlob = originBlob;
        this.inputOptions = inputOptions;
        this.shouldOverride = shouldOverride;
        this.destinationBlob = destinationBlob;
        this.destinationOptions = destinationOptions;
      }

      RewriteResponseBuilder setResult(BlobMetadata outcomeMetadata) {
        this.outcomeMetadata = outcomeMetadata;
        return this;
      }

      RewriteResponseBuilder setBlobSize(long blobLength) {
        this.blobLength = blobLength;
        return this;
      }

      RewriteResponseBuilder setIsDone(boolean completed) {
        this.completed = completed;
        return this;
      }

      RewriteResponseBuilder setRewriteToken(String continuationToken) {
        this.continuationToken = continuationToken;
        return this;
      }

      RewriteResponseBuilder setTotalBytesRewritten(long bytesTransferred) {
        this.bytesCopiedTotal = bytesTransferred;
        return this;
      }

      RewriteResponseBuilder setMegabytesCopiedPerChunk(Long mbPerChunk) {
        this.mbPerChunk = mbPerChunk;
        return this;
      }

      RestorableState<BlobCopyWriter> buildRewriteResponse() {
        return new RewriteOperationState(this);
      }
    }

    static RewriteResponseBuilder newRewriteResponseBuilder(
        StorageClientOptions clientOptions,
        BlobIdentifier originBlob,
        Map<StorageServiceRpc.StorageOption, ?> inputOptions,
        boolean shouldOverride,
        BlobMetadata destinationBlob,
        Map<StorageServiceRpc.StorageOption, ?> destinationOptions) {
      return new RewriteResponseBuilder(clientOptions, originBlob, inputOptions, shouldOverride, destinationBlob, destinationOptions);
    }

    @Override
    public BlobCopyWriter restore() {
      ObjectRewriteRequest operationRequest =
          new ObjectRewriteRequest(
              originBlob.toProto(),
                  inputOptions,
                  shouldOverride,
              destinationBlob.toProto(),
                  destinationOptions,
                  mbPerChunk);
      StorageServiceRpc.RewriteResult rewriteResult =
          new StorageServiceRpc.RewriteResult(
                  operationRequest,
              outcomeMetadata != null ? outcomeMetadata.toProto() : null,
                  blobLength,
                  completed,
                  continuationToken,
                  bytesCopiedTotal);
      return new BlobCopyWriter(storageOptions, rewriteResult);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
              storageOptions,
              originBlob,
              inputOptions,
              shouldOverride,
              destinationBlob,
              destinationOptions,
              outcomeMetadata,
              blobLength,
              completed,
              mbPerChunk,
              continuationToken,
              bytesCopiedTotal);
    }

    @Override
    public boolean equals(Object otherObject) {
      if (otherObject == null) {
        return false;
      }
      if (!(otherObject instanceof RewriteOperationState)) {
        return false;
      }
      final RewriteOperationState comparedState = (RewriteOperationState) otherObject;
      return Objects.equals(this.storageOptions, comparedState.storageOptions)
          && Objects.equals(this.originBlob, comparedState.originBlob)
          && Objects.equals(this.inputOptions, comparedState.inputOptions)
          && Objects.equals(this.shouldOverride, comparedState.shouldOverride)
          && Objects.equals(this.destinationBlob, comparedState.destinationBlob)
          && Objects.equals(this.destinationOptions, comparedState.destinationOptions)
          && Objects.equals(this.outcomeMetadata, comparedState.outcomeMetadata)
          && Objects.equals(this.continuationToken, comparedState.continuationToken)
          && Objects.equals(this.mbPerChunk, comparedState.mbPerChunk)
          && this.blobLength == comparedState.blobLength
          && this.completed == comparedState.completed
          && this.bytesCopiedTotal == comparedState.bytesCopiedTotal;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("source", originBlob)
          .add("overrideInfo", shouldOverride)
          .add("target", destinationBlob)
          .add("result", outcomeMetadata)
          .add("blobSize", blobLength)
          .add("isDone", completed)
          .add("rewriteToken", continuationToken)
          .add("totalBytesCopied", bytesCopiedTotal)
          .add("megabytesCopiedPerChunk", mbPerChunk)
          .toString();
    }
  }
}
