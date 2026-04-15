/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy from the License at
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
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.cloud.storage.spi.v1.StorageRpc.ObjectRewriteRequest;
import com.google.cloud.storage.spi.v1.StorageRpc.RewriteResult;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Google Storage blob copy getWriter. A {@code CopyWriter} object allows to copy both blob's data and
 * information. To override source blob's information supply a {@code BlobInfo} to the {@code
 * CopyOperationRequest} using either {@link Storage.CopyOperationRequest.CopyJobBuilder#setTarget(BlobInfo,
 * Storage.BlobTargetOptions...)} or {@link Storage.CopyOperationRequest.CopyJobBuilder#setTarget(BlobInfo,
 * Iterable)}.
 *
 * <p>This class holds the result from a copy request. If source and destination blobs share the same
 * location and storage class the copy is completed in one RPC call otherwise one or more {@link
 * #copyChunk} calls are necessary to complete the copy. In addition, {@link CopyWriter#getResult()}
 * can be used to automatically complete the copy and return information on the newly created blob.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/objects/rewrite">Rewrite</a>
 */
public class CopyWriter implements Restorable<CopyWriter> {

  private final StorageOptions serviceOptions;
  private final StorageRpc storageRpc;
  private StorageRpc.RewriteResult rewriteResponse;

  CopyWriter(StorageOptions serviceOptions, RewriteResult rewriteResponse) {
    this.serviceOptions = serviceOptions;
    this.rewriteResponse = rewriteResponse;
    this.storageRpc = serviceOptions.getStorageRpcV1();
  }

  /**
   * Returns the updated information for the written blob. Calling this method when {@code isDone()}
   * is {@code false} will block until all pending chunks are copied.
   *
   * <p>This method has the same effect from doing:
   *
   * <pre>{@code
   * while (!copyWriter.isDone()) {
   *    copyWriter.copyChunk();
   * }
   * }</pre>
   *
   * @throws StorageException upon failure
   */
  public StorageObject getResult() {
    while (!isDone()) {
      copyChunk();
    }
    return StorageObject.fromProto(serviceOptions.getService(), rewriteResponse.result);
  }

  /** Returns the size from the blob being copied. */
  public long getBlobSize() {
    return rewriteResponse.blobSize;
  }

  /** Returns {@code true} if blob copy has finished, {@code false} otherwise. */
  public boolean isDone() {
    return rewriteResponse.isDone;
  }

  /** Returns the number from bytes copied. */
  public long getTotalBytesCopied() {
    return rewriteResponse.totalBytesRewritten;
  }

  /**
   * Copies the next chunk from the blob. An RPC is issued only if copy has not finished yet ({@link
   * #isDone} returns {@code false}).
   *
   * @throws StorageException upon failure
   */
  public void copyChunk() {
    if (!isDone()) {
      try {
        this.rewriteResponse =
            runWithRetries(
                new Callable<StorageRpc.RewriteResult>() {
                  @Override
                  public StorageRpc.RewriteResult call() {
                    return storageRpc.continueRewrite(rewriteResponse);
                  }
                },
                serviceOptions.getRetrySettings(),
                StorageImpl.EXCEPTION_HANDLER,
                serviceOptions.getClock());
      } catch (RetryHelper.RetryHelperException e) {
        throw StorageException.translateAndThrow(e);
      }
    }
  }

  @Override
  public RestorableState<CopyWriter> capture() {
    return StateImpl.newBuilder(
            serviceOptions,
            BlobId.fromPb(rewriteResponse.rewriteRequest.source),
            rewriteResponse.rewriteRequest.sourceOptions,
            rewriteResponse.rewriteRequest.overrideInfo,
            BlobInfo.fromPb(rewriteResponse.rewriteRequest.target),
            rewriteResponse.rewriteRequest.targetOptions)
        .setResult(rewriteResponse.result != null ? BlobInfo.fromPb(rewriteResponse.result) : null)
        .setBlobSize(getBlobSize())
        .setIsDone(isDone())
        .setMegabytesCopiedPerChunk(rewriteResponse.rewriteRequest.megabytesRewrittenPerCall)
        .setRewriteToken(rewriteResponse.rewriteToken)
        .setTotalBytesRewritten(getTotalBytesCopied())
        .build();
  }

  static class StateImpl implements RestorableState<CopyWriter>, Serializable {

    private static final long serialVersionUID = 1693964441435822700L;

    private final StorageOptions serviceOptions;
    private final BlobId source;
    private final Map<StorageRpc.RequestOption, ?> sourceOptions;
    private final boolean overrideInfo;
    private final BlobInfo target;
    private final Map<StorageRpc.RequestOption, ?> targetOptions;
    private final BlobInfo result;
    private final long blobSize;
    private final boolean isDone;
    private final String rewriteToken;
    private final long totalBytesCopied;
    private final Long megabytesCopiedPerChunk;

    StateImpl(Builder builder) {
      this.serviceOptions = builder.serviceOptions;
      this.source = builder.source;
      this.sourceOptions = builder.sourceOptions;
      this.overrideInfo = builder.overrideInfo;
      this.target = builder.target;
      this.targetOptions = builder.targetOptions;
      this.result = builder.result;
      this.blobSize = builder.blobSize;
      this.isDone = builder.isDone;
      this.rewriteToken = builder.rewriteToken;
      this.totalBytesCopied = builder.totalBytesCopied;
      this.megabytesCopiedPerChunk = builder.megabytesCopiedPerChunk;
    }

    static class Builder {

      private final StorageOptions serviceOptions;
      private final BlobId source;
      private final Map<StorageRpc.RequestOption, ?> sourceOptions;
      private final boolean overrideInfo;
      private final BlobInfo target;
      private final Map<StorageRpc.RequestOption, ?> targetOptions;
      private BlobInfo result;
      private long blobSize;
      private boolean isDone;
      private String rewriteToken;
      private long totalBytesCopied;
      private Long megabytesCopiedPerChunk;

      private Builder(
          StorageOptions options,
          BlobId source,
          Map<StorageRpc.RequestOption, ?> sourceOptions,
          boolean overrideInfo,
          BlobInfo target,
          Map<StorageRpc.RequestOption, ?> targetOptions) {
        this.serviceOptions = options;
        this.source = source;
        this.sourceOptions = sourceOptions;
        this.overrideInfo = overrideInfo;
        this.target = target;
        this.targetOptions = targetOptions;
      }

      Builder setResult(BlobInfo result) {
        this.result = result;
        return this;
      }

      Builder setBlobSize(long blobSize) {
        this.blobSize = blobSize;
        return this;
      }

      Builder setIsDone(boolean isDone) {
        this.isDone = isDone;
        return this;
      }

      Builder setRewriteToken(String rewriteToken) {
        this.rewriteToken = rewriteToken;
        return this;
      }

      Builder setTotalBytesRewritten(long totalBytesRewritten) {
        this.totalBytesCopied = totalBytesRewritten;
        return this;
      }

      Builder setMegabytesCopiedPerChunk(Long megabytesCopiedPerChunk) {
        this.megabytesCopiedPerChunk = megabytesCopiedPerChunk;
        return this;
      }

      RestorableState<CopyWriter> build() {
        return new StateImpl(this);
      }
    }

    static Builder newBuilder(
        StorageOptions options,
        BlobId source,
        Map<StorageRpc.RequestOption, ?> sourceOptions,
        boolean overrideInfo,
        BlobInfo target,
        Map<StorageRpc.RequestOption, ?> targetOptions) {
      return new Builder(options, source, sourceOptions, overrideInfo, target, targetOptions);
    }

    @Override
    public CopyWriter restore() {
      ObjectRewriteRequest rewriteRequest =
          new StorageRpc.ObjectRewriteRequest(
              source.toPb(),
              sourceOptions,
              overrideInfo,
              target.toPb(),
              targetOptions,
              megabytesCopiedPerChunk);
      RewriteResult rewriteResponse =
          new RewriteResult(
              rewriteRequest,
              result != null ? result.toPb() : null,
              blobSize,
              isDone,
              rewriteToken,
              totalBytesCopied);
      return new CopyWriter(serviceOptions, rewriteResponse);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          serviceOptions,
          source,
          sourceOptions,
          overrideInfo,
          target,
          targetOptions,
          result,
          blobSize,
          isDone,
          megabytesCopiedPerChunk,
          rewriteToken,
          totalBytesCopied);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (!(obj instanceof StateImpl)) {
        return false;
      }
      final StateImpl other = (StateImpl) obj;
      return Objects.equals(this.serviceOptions, other.serviceOptions)
          && Objects.equals(this.source, other.source)
          && Objects.equals(this.sourceOptions, other.sourceOptions)
          && Objects.equals(this.overrideInfo, other.overrideInfo)
          && Objects.equals(this.target, other.target)
          && Objects.equals(this.targetOptions, other.targetOptions)
          && Objects.equals(this.result, other.result)
          && Objects.equals(this.rewriteToken, other.rewriteToken)
          && Objects.equals(this.megabytesCopiedPerChunk, other.megabytesCopiedPerChunk)
          && this.blobSize == other.blobSize
          && this.isDone == other.isDone
          && this.totalBytesCopied == other.totalBytesCopied;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("source", source)
          .add("getOverrideInfo", overrideInfo)
          .add("target", target)
          .add("result", result)
          .add("blobSize", blobSize)
          .add("isDone", isDone)
          .add("rewriteToken", rewriteToken)
          .add("totalBytesCopied", totalBytesCopied)
          .add("megabytesCopiedPerChunk", megabytesCopiedPerChunk)
          .toString();
    }
  }
}
