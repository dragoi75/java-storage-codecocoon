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
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import com.google.cloud.storage.spi.v1.CloudStorageRpc.RewriteOperationRequest;
import com.google.cloud.storage.spi.v1.CloudStorageRpc.RewriteOperationResponse;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Google Storage blob copy writer. A {@code CopyWriter} object allows to copy both blob's data and
 * information. To override source blob's information supply a {@code BlobInfo} to the {@code
 * CopyRequest} using either {@link StorageClient.CopyOperationRequest.CopyOperationBuilder#setTarget(BlobMetadata,
 * StorageClient.BlobUploadOption...)} or {@link StorageClient.CopyOperationRequest.CopyOperationBuilder#setTarget(BlobMetadata,
 * Iterable)}.
 *
 * <p>This class holds the result of a copy request. If source and destination blobs share the same
 * location and storage class the copy is completed in one RPC call otherwise one or more {@link
 * #continueRewrite} calls are necessary to complete the copy. In addition, {@link BlobCopyWriter#getResult()}
 * can be used to automatically complete the copy and return information on the newly created blob.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
 */
public class BlobCopyWriter implements Restorable<BlobCopyWriter> {

  private final StorageSettings storageSettings;
  private final CloudStorageRpc cloudRpcClient;
  private RewriteOperationResponse rewriteResult;

  BlobCopyWriter(StorageSettings storageSettings, RewriteOperationResponse rewriteResult) {
    this.storageSettings = storageSettings;
    this.rewriteResult = rewriteResult;
    this.cloudRpcClient = storageSettings.getStorageRpcV1();
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
  public StorageBlob getResult() {
    while (!isDone()) {
      continueRewrite();
    }
    return StorageBlob.fromProto(storageSettings.getService(), rewriteResult.result);
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
   * @throws StorageOperationException upon failure
   */
  public void continueRewrite() {
    if (!isDone()) {
      try {
        this.rewriteResult =
            runWithRetries(
                new Callable<RewriteOperationResponse>() {
                  @Override
                  public CloudStorageRpc.RewriteOperationResponse call() {
                    return cloudRpcClient.continueRewrite(rewriteResult);
                  }
                },
                storageSettings.getRetrySettings(),
                StorageClientImpl.EXCEPTION_HANDLER,
                storageSettings.getClock());
      } catch (RetryHelper.RetryHelperException retryException) {
        throw StorageOperationException.translateAndRethrow(retryException);
      }
    }
  }

  @Override
  public RestorableState<BlobCopyWriter> capture() {
    return RewriteOperationStateImpl.newBlobRewriteBuilder(
                    storageSettings,
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
        .buildState();
  }

  static class RewriteOperationStateImpl implements RestorableState<BlobCopyWriter>, Serializable {

    private static final long serialVersionUID = 1693964441435822700L;

    private final StorageSettings storageSettings;
    private final BlobIdentifier blobIdentifier;
    private final Map<CloudStorageRpc.StorageOption, ?> blobOptions;
    private final boolean forceReplaceFlag;
    private final BlobMetadata destinationMetadata;
    private final Map<CloudStorageRpc.StorageOption, ?> destinationOptions;
    private final BlobMetadata outcomeMetadata;
    private final long contentLength;
    private final boolean completedFlag;
    private final String continuationToken;
    private final long bytesCopiedTotal;
    private final Long mbPerChunk;

    RewriteOperationStateImpl(BlobRewriteBuilder rewriteFactory) {
      this.storageSettings = rewriteFactory.storageSettings;
      this.blobIdentifier = rewriteFactory.blobIdentifier;
      this.blobOptions = rewriteFactory.blobOptions;
      this.forceReplaceFlag = rewriteFactory.forceReplaceFlag;
      this.destinationMetadata = rewriteFactory.destinationMetadata;
      this.destinationOptions = rewriteFactory.destinationOptions;
      this.outcomeMetadata = rewriteFactory.outcomeMetadata;
      this.contentLength = rewriteFactory.contentLength;
      this.completedFlag = rewriteFactory.completedFlag;
      this.continuationToken = rewriteFactory.continuationToken;
      this.bytesCopiedTotal = rewriteFactory.bytesCopiedTotal;
      this.mbPerChunk = rewriteFactory.mbPerChunk;
    }

    static class BlobRewriteBuilder {

      private final StorageSettings storageSettings;
      private final BlobIdentifier blobIdentifier;
      private final Map<CloudStorageRpc.StorageOption, ?> blobOptions;
      private final boolean forceReplaceFlag;
      private final BlobMetadata destinationMetadata;
      private final Map<CloudStorageRpc.StorageOption, ?> destinationOptions;
      private BlobMetadata outcomeMetadata;
      private long contentLength;
      private boolean completedFlag;
      private String continuationToken;
      private long bytesCopiedTotal;
      private Long mbPerChunk;

      private BlobRewriteBuilder(
          StorageSettings storageSettings,
          BlobIdentifier blobIdentifier,
          Map<CloudStorageRpc.StorageOption, ?> blobOptions,
          boolean forceReplaceFlag,
          BlobMetadata destinationMetadata,
          Map<CloudStorageRpc.StorageOption, ?> destinationOptions) {
        this.storageSettings = storageSettings;
        this.blobIdentifier = blobIdentifier;
        this.blobOptions = blobOptions;
        this.forceReplaceFlag = forceReplaceFlag;
        this.destinationMetadata = destinationMetadata;
        this.destinationOptions = destinationOptions;
      }

      BlobRewriteBuilder setResult(BlobMetadata outcomeMetadata) {
        this.outcomeMetadata = outcomeMetadata;
        return this;
      }

      BlobRewriteBuilder setBlobSize(long contentLength) {
        this.contentLength = contentLength;
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

      BlobRewriteBuilder setTotalBytesRewritten(long bytesCopiedTotal) {
        this.bytesCopiedTotal = bytesCopiedTotal;
        return this;
      }

      BlobRewriteBuilder setMegabytesCopiedPerChunk(Long mbPerChunk) {
        this.mbPerChunk = mbPerChunk;
        return this;
      }

      RestorableState<BlobCopyWriter> buildState() {
        return new RewriteOperationStateImpl(this);
      }
    }

    static BlobRewriteBuilder newBlobRewriteBuilder(
        StorageSettings storageSettings,
        BlobIdentifier blobIdentifier,
        Map<CloudStorageRpc.StorageOption, ?> blobOptions,
        boolean forceReplaceFlag,
        BlobMetadata destinationMetadata,
        Map<CloudStorageRpc.StorageOption, ?> destinationOptions) {
      return new BlobRewriteBuilder(storageSettings, blobIdentifier, blobOptions, forceReplaceFlag, destinationMetadata, destinationOptions);
    }

    @Override
    public BlobCopyWriter restore() {
      RewriteOperationRequest operationRequest =
          new CloudStorageRpc.RewriteOperationRequest(
              blobIdentifier.toProto(),
                  blobOptions,
                  forceReplaceFlag,
              destinationMetadata.toProto(),
                  destinationOptions,
                  mbPerChunk);
      RewriteOperationResponse rewriteResult =
          new RewriteOperationResponse(
                  operationRequest,
              outcomeMetadata != null ? outcomeMetadata.toProto() : null,
                  contentLength,
                  completedFlag,
                  continuationToken,
                  bytesCopiedTotal);
      return new BlobCopyWriter(storageSettings, rewriteResult);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
              storageSettings,
              blobIdentifier,
              blobOptions,
              forceReplaceFlag,
              destinationMetadata,
              destinationOptions,
              outcomeMetadata,
              contentLength,
              completedFlag,
              mbPerChunk,
              continuationToken,
              bytesCopiedTotal);
    }

    @Override
    public boolean equals(Object otherObject) {
      if (otherObject == null) {
        return false;
      }
      if (!(otherObject instanceof RewriteOperationStateImpl)) {
        return false;
      }
      final RewriteOperationStateImpl thatState = (RewriteOperationStateImpl) otherObject;
      return Objects.equals(this.storageSettings, thatState.storageSettings)
          && Objects.equals(this.blobIdentifier, thatState.blobIdentifier)
          && Objects.equals(this.blobOptions, thatState.blobOptions)
          && Objects.equals(this.forceReplaceFlag, thatState.forceReplaceFlag)
          && Objects.equals(this.destinationMetadata, thatState.destinationMetadata)
          && Objects.equals(this.destinationOptions, thatState.destinationOptions)
          && Objects.equals(this.outcomeMetadata, thatState.outcomeMetadata)
          && Objects.equals(this.continuationToken, thatState.continuationToken)
          && Objects.equals(this.mbPerChunk, thatState.mbPerChunk)
          && this.contentLength == thatState.contentLength
          && this.completedFlag == thatState.completedFlag
          && this.bytesCopiedTotal == thatState.bytesCopiedTotal;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("source", blobIdentifier)
          .add("overrideInfo", forceReplaceFlag)
          .add("target", destinationMetadata)
          .add("result", outcomeMetadata)
          .add("blobSize", contentLength)
          .add("isDone", completedFlag)
          .add("rewriteToken", continuationToken)
          .add("totalBytesCopied", bytesCopiedTotal)
          .add("megabytesCopiedPerChunk", mbPerChunk)
          .toString();
    }
  }
}
