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
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteOperationResult;
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
 * #copyNextChunk} calls are necessary to complete the copy. In addition, {@link BlobCopyWriter#getResult()}
 * can be used to automatically complete the copy and return information on the newly created blob.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
 */
public class BlobCopyWriter implements Restorable<BlobCopyWriter> {

  private final StorageSettings storageSettings;
  private final StorageRpcClient storageClient;
  private RewriteOperationResult operationResult;

  BlobCopyWriter(StorageSettings storageSettings, RewriteOperationResult operationResult) {
    this.storageSettings = storageSettings;
    this.operationResult = operationResult;
    this.storageClient = storageSettings.getStorageRpcV1();
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
    return StorageObject.fromProto(storageSettings.getService(), operationResult.result);
  }

  /** Returns the size of the blob being copied. */
  public long getBlobSize() {
    return operationResult.blobSize;
  }

  /** Returns {@code true} if blob copy has finished, {@code false} otherwise. */
  public boolean isDone() {
    return operationResult.isDone;
  }

  /** Returns the number of bytes copied. */
  public long getTotalBytesCopied() {
    return operationResult.totalBytesRewritten;
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
        this.operationResult =
            runWithRetries(
                new Callable<StorageRpcClient.RewriteOperationResult>() {
                  @Override
                  public StorageRpcClient.RewriteOperationResult call() {
                    return storageClient.continueRewrite(operationResult);
                  }
                },
                storageSettings.getRetrySettings(),
                DefaultStorageImpl.EXCEPTION_HANDLER,
                storageSettings.getClock());
      } catch (RetryHelper.RetryHelperException retryException) {
        throw StorageOperationException.translateAndRethrow(retryException);
      }
    }
  }

  @Override
  public RestorableState<BlobCopyWriter> capture() {
    return RewriteStateImpl.createBuilder(
                    storageSettings,
            BlobIdentifier.fromStorageObject(operationResult.rewriteRequest.source),
            operationResult.rewriteRequest.sourceOptions,
            operationResult.rewriteRequest.overrideInfo,
            BlobMetadata.fromProto(operationResult.rewriteRequest.target),
            operationResult.rewriteRequest.targetOptions)
        .setResult(operationResult.result != null ? BlobMetadata.fromProto(operationResult.result) : null)
        .setBlobSize(getBlobSize())
        .setIsDone(isDone())
        .setMegabytesCopiedPerChunk(operationResult.rewriteRequest.megabytesRewrittenPerCall)
        .setRewriteToken(operationResult.rewriteToken)
        .setTotalBytesRewritten(getTotalBytesCopied())
        .buildRewriteState();
  }

  static class RewriteStateImpl implements RestorableState<BlobCopyWriter>, Serializable {

    private static final long serialVersionUID = 1693964441435822700L;

    private final StorageSettings storageSettings;
    private final BlobIdentifier originId;
    private final Map<StorageRpcClient.StorageOption, ?> originOptions;
    private final boolean overrideMetadata;
    private final BlobMetadata destination;
    private final Map<StorageRpcClient.StorageOption, ?> destinationOptions;
    private final BlobMetadata resultMetadata;
    private final long sizeBytes;
    private final boolean completed;
    private final String continuationToken;
    private final long totalCopiedBytes;
    private final Long megabytesPerChunk;

    RewriteStateImpl(RewriteResponseBuilder responseBuilder) {
      this.storageSettings = responseBuilder.storageSettings;
      this.originId = responseBuilder.originId;
      this.originOptions = responseBuilder.originOptions;
      this.overrideMetadata = responseBuilder.overrideMetadata;
      this.destination = responseBuilder.destination;
      this.destinationOptions = responseBuilder.destinationOptions;
      this.resultMetadata = responseBuilder.resultMetadata;
      this.sizeBytes = responseBuilder.sizeBytes;
      this.completed = responseBuilder.completed;
      this.continuationToken = responseBuilder.continuationToken;
      this.totalCopiedBytes = responseBuilder.totalCopiedBytes;
      this.megabytesPerChunk = responseBuilder.megabytesPerChunk;
    }

    static class RewriteResponseBuilder {

      private final StorageSettings storageSettings;
      private final BlobIdentifier originId;
      private final Map<StorageRpcClient.StorageOption, ?> originOptions;
      private final boolean overrideMetadata;
      private final BlobMetadata destination;
      private final Map<StorageRpcClient.StorageOption, ?> destinationOptions;
      private BlobMetadata resultMetadata;
      private long sizeBytes;
      private boolean completed;
      private String continuationToken;
      private long totalCopiedBytes;
      private Long megabytesPerChunk;

      private RewriteResponseBuilder(
          StorageSettings settings,
          BlobIdentifier originId,
          Map<StorageRpcClient.StorageOption, ?> originOptions,
          boolean overrideMetadata,
          BlobMetadata destination,
          Map<StorageRpcClient.StorageOption, ?> destinationOptions) {
        this.storageSettings = settings;
        this.originId = originId;
        this.originOptions = originOptions;
        this.overrideMetadata = overrideMetadata;
        this.destination = destination;
        this.destinationOptions = destinationOptions;
      }

      RewriteResponseBuilder setResult(BlobMetadata resultMetadata) {
        this.resultMetadata = resultMetadata;
        return this;
      }

      RewriteResponseBuilder setBlobSize(long sizeBytes) {
        this.sizeBytes = sizeBytes;
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

      RewriteResponseBuilder setTotalBytesRewritten(long totalCopiedBytes) {
        this.totalCopiedBytes = totalCopiedBytes;
        return this;
      }

      RewriteResponseBuilder setMegabytesCopiedPerChunk(Long megabytesPerChunk) {
        this.megabytesPerChunk = megabytesPerChunk;
        return this;
      }

      RestorableState<BlobCopyWriter> buildRewriteState() {
        return new RewriteStateImpl(this);
      }
    }

    static RewriteResponseBuilder createBuilder(
        StorageSettings settings,
        BlobIdentifier originId,
        Map<StorageRpcClient.StorageOption, ?> originOptions,
        boolean overrideMetadata,
        BlobMetadata destination,
        Map<StorageRpcClient.StorageOption, ?> destinationOptions) {
      return new RewriteResponseBuilder(settings, originId, originOptions, overrideMetadata, destination, destinationOptions);
    }

    @Override
    public BlobCopyWriter restore() {
      ObjectRewriteRequest objectRequest =
          new ObjectRewriteRequest(
              originId.toStorageObject(),
                  originOptions,
                  overrideMetadata,
              destination.toProto(),
                  destinationOptions,
                  megabytesPerChunk);
      StorageRpcClient.RewriteOperationResult operationResult =
          new RewriteOperationResult(
                  objectRequest,
              resultMetadata != null ? resultMetadata.toProto() : null,
                  sizeBytes,
                  completed,
                  continuationToken,
                  totalCopiedBytes);
      return new BlobCopyWriter(storageSettings, operationResult);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
              storageSettings,
              originId,
              originOptions,
              overrideMetadata,
              destination,
              destinationOptions,
              resultMetadata,
              sizeBytes,
              completed,
              megabytesPerChunk,
              continuationToken,
              totalCopiedBytes);
    }

    @Override
    public boolean equals(Object otherCandidate) {
      if (otherCandidate == null) {
        return false;
      }
      if (!(otherCandidate instanceof RewriteStateImpl)) {
        return false;
      }
      final RewriteStateImpl thatState = (RewriteStateImpl) otherCandidate;
      return Objects.equals(this.storageSettings, thatState.storageSettings)
          && Objects.equals(this.originId, thatState.originId)
          && Objects.equals(this.originOptions, thatState.originOptions)
          && Objects.equals(this.overrideMetadata, thatState.overrideMetadata)
          && Objects.equals(this.destination, thatState.destination)
          && Objects.equals(this.destinationOptions, thatState.destinationOptions)
          && Objects.equals(this.resultMetadata, thatState.resultMetadata)
          && Objects.equals(this.continuationToken, thatState.continuationToken)
          && Objects.equals(this.megabytesPerChunk, thatState.megabytesPerChunk)
          && this.sizeBytes == thatState.sizeBytes
          && this.completed == thatState.completed
          && this.totalCopiedBytes == thatState.totalCopiedBytes;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("source", originId)
          .add("overrideInfo", overrideMetadata)
          .add("target", destination)
          .add("result", resultMetadata)
          .add("blobSize", sizeBytes)
          .add("isDone", completed)
          .add("rewriteToken", continuationToken)
          .add("totalBytesCopied", totalCopiedBytes)
          .add("megabytesCopiedPerChunk", megabytesPerChunk)
          .toString();
    }
  }
}
