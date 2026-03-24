/*
 * Copyright 2022 Google LLC
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

import static com.google.cloud.storage.ByteSizeConstants._15MiB;
import static com.google.cloud.storage.ByteSizeConstants._256KiB;
import static com.google.cloud.storage.Utils.bucketNameCodec;
import static com.google.cloud.storage.Utils.ifNonNull;
import static com.google.cloud.storage.Utils.projectNameCodec;
import static com.google.cloud.storage.Utils.todo;
import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import com.google.api.core.ApiFuture;
import com.google.api.core.BetaApi;
import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.paging.AbstractPage;
import com.google.api.gax.paging.Page;
import com.google.api.gax.retrying.ResultRetryAlgorithm;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.ApiExceptionFactory;
import com.google.api.gax.rpc.ApiExceptions;
import com.google.api.gax.rpc.StatusCode;
import com.google.api.gax.rpc.UnaryCallable;
import com.google.api.gax.rpc.UnimplementedException;
import com.google.cloud.BaseService;
import com.google.cloud.Policy;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.BufferedWritableByteChannelSession.BufferedWritableByteChannel;
import com.google.cloud.storage.Conversions.Decoder;
import com.google.cloud.storage.HmacKey.HmacKeyMetadata;
import com.google.cloud.storage.HmacKey.HmacKeyState;
import com.google.cloud.storage.PostPolicyV4.PostConditionsV4;
import com.google.cloud.storage.PostPolicyV4.PostFieldsV4;
import com.google.cloud.storage.UnbufferedReadableByteChannelSession.UnbufferedReadableByteChannel;
import com.google.cloud.storage.UnbufferedWritableByteChannelSession.UnbufferedWritableByteChannel;
import com.google.cloud.storage.UnifiedOpts.BucketListOpt;
import com.google.cloud.storage.UnifiedOpts.BucketSourceOpt;
import com.google.cloud.storage.UnifiedOpts.BucketTargetOpt;
import com.google.cloud.storage.UnifiedOpts.HmacKeyListOpt;
import com.google.cloud.storage.UnifiedOpts.HmacKeySourceOpt;
import com.google.cloud.storage.UnifiedOpts.HmacKeyTargetOpt;
import com.google.cloud.storage.UnifiedOpts.Mapper;
import com.google.cloud.storage.UnifiedOpts.ObjectListOpt;
import com.google.cloud.storage.UnifiedOpts.ObjectSourceOpt;
import com.google.cloud.storage.UnifiedOpts.ObjectTargetOpt;
import com.google.cloud.storage.UnifiedOpts.Opts;
import com.google.cloud.storage.UnifiedOpts.ProjectId;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Message;
import com.google.storage.v2.ComposeObjectRequest;
import com.google.storage.v2.ComposeObjectRequest.SourceObject;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.CreateHmacKeyRequest;
import com.google.storage.v2.DeleteBucketRequest;
import com.google.storage.v2.DeleteHmacKeyRequest;
import com.google.storage.v2.DeleteObjectRequest;
import com.google.storage.v2.GetBucketRequest;
import com.google.storage.v2.GetHmacKeyRequest;
import com.google.storage.v2.GetObjectRequest;
import com.google.storage.v2.GetServiceAccountRequest;
import com.google.storage.v2.ListBucketsRequest;
import com.google.storage.v2.ListHmacKeysRequest;
import com.google.storage.v2.ListObjectsRequest;
import com.google.storage.v2.Object;
import com.google.storage.v2.ProjectName;
import com.google.storage.v2.ReadObjectRequest;
import com.google.storage.v2.RewriteObjectRequest;
import com.google.storage.v2.RewriteResponse;
import com.google.storage.v2.StorageClient;
import com.google.storage.v2.StorageClient.ListBucketsPage;
import com.google.storage.v2.StorageClient.ListBucketsPagedResponse;
import com.google.storage.v2.StorageClient.ListHmacKeysPage;
import com.google.storage.v2.StorageClient.ListHmacKeysPagedResponse;
import com.google.storage.v2.StorageClient.ListObjectsPage;
import com.google.storage.v2.StorageClient.ListObjectsPagedResponse;
import com.google.storage.v2.UpdateBucketRequest;
import com.google.storage.v2.UpdateHmacKeyRequest;
import com.google.storage.v2.UpdateObjectRequest;
import com.google.storage.v2.WriteObjectRequest;
import com.google.storage.v2.WriteObjectResponse;
import com.google.storage.v2.WriteObjectSpec;
import io.grpc.Status.Code;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators.AbstractSpliterator;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@BetaApi
final class GrpcStorageImpl extends BaseService<StorageOptions> implements Storage {

  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  private static final Set<OpenOption> READ_OPEN_OPTIONS = ImmutableSet.of(StandardOpenOption.READ);
  private static final Set<OpenOption> WRITE_OPEN_OPTIONS =
      ImmutableSet.of(
          StandardOpenOption.WRITE,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
  /**
   * For use in {@link #getRetryableStatusCodes (ResultRetryAlgorithm)}. Resolve all codes and
   * construct corresponding ApiExceptions.
   *
   * <p>Constructing the exceptions will walk the stack for each one. In order to avoid the stack
   * walking overhead for every Code for every invocation of read, construct the set of exceptions
   * only once and keep in this value.
   */
  private static final Set<StorageException> API_CODE_EXCEPTIONS =
      Arrays.stream(StatusCode.Code.values())
          .map(GrpcStorageImpl::getGrpcStatusCode)
          .map(c -> ApiExceptionFactory.createException(null, c, false))
          .map(StorageException::asStorageException)
          .collect(Collectors.toSet());

  private final StorageClient storageConnector;
  private final GrpcConverters grpcConverters;
  private final GrpcRetryAlgorithmManager retryPolicyManager;
  private final SyntaxDecoder syntaxParser;

  @Deprecated private final ProjectId projectIdDefault;

  GrpcStorageImpl(GrpcStorageOptions grpcOptions, StorageClient storageConnector) {
    super(grpcOptions);
    this.storageConnector = storageConnector;
    this.grpcConverters = Conversions.grpc();
    this.retryPolicyManager = grpcOptions.getRetryAlgorithmManager();
    this.syntaxParser = new SyntaxDecoder();
    this.projectIdDefault = UnifiedOpts.projectId(grpcOptions.getProjectId());
  }

  @Override
  public void close() throws Exception {
    try (StorageClient clientInstance = storageConnector) {
      clientInstance.shutdownNow();
      org.threeten.bp.Duration shutdownTimeout =
          getOptions().getTerminationAwaitDuration();
      clientInstance.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public Bucket create(BucketInfo bucketMetadata, BucketTargetOption... grpcOptions) {
    Opts<BucketTargetOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(bucketMetadata);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    com.google.storage.v2.Bucket bucketModel = grpcConverters.bucketInfoCodec().encode(bucketMetadata);
    CreateBucketRequest.Builder bucketBuilder =
        CreateBucketRequest.newBuilder()
            .setBucket(bucketModel)
            .setBucketId(bucketMetadata.getName())
            .setParent(ProjectName.format(getOptions().getProjectId()));
    CreateBucketRequest createBucketRequest = bucketOpts.createBucketsRequest().apply(bucketBuilder).build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> storageConnector.createBucketCallable().call(createBucketRequest, callContext),
        syntaxParser.bucketModel);
  }

  @Override
  public Blob create(BlobInfo blobMetadata, BlobTargetOption... grpcOptions) {
    return create(blobMetadata, null, grpcOptions);
  }

  @Override
  public Blob create(BlobInfo blobMetadata, byte[] dataBytes, BlobTargetOption... grpcOptions) {
    dataBytes = firstNonNull(dataBytes, EMPTY_BYTE_ARRAY);
    return create(blobMetadata, dataBytes, 0, dataBytes.length, grpcOptions);
  }

  @Override
  public Blob create(
      BlobInfo blobMetadata, byte[] dataBytes, int startOffset, int dataLength, BlobTargetOption... grpcOptions) {
    requireNonNull(blobMetadata, "blobInfoCodec must be non null");
    requireNonNull(dataBytes, "content must be non null");
    Opts<ObjectTargetOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobMetadata);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    WriteObjectRequest createBucketRequest = getWriteObjectRequest(blobMetadata, bucketOpts);
    try {
      UnbufferedWritableByteChannelSession<WriteObjectResponse> writeSession =
          ResumableMedia.gapic()
              .write()
              .byteChannel(
                  storageConnector.writeObjectCallable().withDefaultCallContext(callContext))
              .setByteStringStrategy(ByteStringStrategy.noCopy())
              .setHasher(Hasher.enabled())
              .direct()
              .unbuffered()
              .setRequest(createBucketRequest)
              .build();

      try (UnbufferedWritableByteChannel ctx = writeSession.open()) {
        ctx.write(ByteBuffer.wrap(dataBytes, startOffset, dataLength));
      }
      return getBlob(writeSession.getResult());
    } catch (Exception err) {
      throw StorageException.coalesce(err);
    }
  }

  @Override
  public Blob create(BlobInfo blobMetadata, InputStream dataBytes, BlobWriteOption... grpcOptions) {
    try {
      return createFrom(blobMetadata, dataBytes, grpcOptions);
    } catch (IOException err) {
      throw StorageException.coalesce(err);
    }
  }

  @Override
  public Blob createFrom(BlobInfo blobMetadata, Path filePath, BlobWriteOption... grpcOptions)
      throws IOException {
    return createFrom(blobMetadata, filePath, _15MiB, grpcOptions);
  }

  @Override
  public Blob createFrom(BlobInfo blobMetadata, Path filePath, int readBufferSize, BlobWriteOption... grpcOptions)
      throws IOException {
    requireNonNull(filePath, "path must be non null");
    if (Files.isDirectory(filePath)) {
      throw new StorageException(0, filePath + " is a directory");
    }

    Opts<ObjectTargetOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobMetadata);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    WriteObjectRequest createBucketRequest = getWriteObjectRequest(blobMetadata, bucketOpts);

    GapicWritableByteChannelSessionBuilder channelSessionFactory =
        ResumableMedia.gapic()
            .write()
            .byteChannel(
                storageConnector.writeObjectCallable().withDefaultCallContext(callContext))
            .setHasher(Hasher.enabled())
            .setByteStringStrategy(ByteStringStrategy.noCopy());

    BufferedWritableByteChannelSession<WriteObjectResponse> writeSession;
    long fileSize = Files.size(filePath);
    if (fileSize < readBufferSize) {
      // ignore the bufferSize argument if the file is smaller than it
      writeSession =
          channelSessionFactory.direct().buffered(Buffers.allocate(fileSize)).setRequest(createBucketRequest).build();
    } else {
      ApiFuture<ResumableWrite> startFuture =
          ResumableMedia.gapic()
              .write()
              .resumableWrite(
                  storageConnector
                      .startResumableWriteCallable()
                      .withDefaultCallContext(callContext),
                  createBucketRequest);
      writeSession =
          channelSessionFactory
              .resumable()
              .buffered(Buffers.allocateAligned(readBufferSize, _256KiB))
              .setStartAsync(startFuture)
              .build();
    }

    try (SeekableByteChannel sourceChannel = Files.newByteChannel(filePath, READ_OPEN_OPTIONS);
        BufferedWritableByteChannel destinationChannel = writeSession.open()) {
      ByteStreams.copy(sourceChannel, destinationChannel);
    } catch (Exception err) {
      throw StorageException.coalesce(err);
    }
    return getBlob(writeSession.getResult());
  }

  @Override
  public Blob createFrom(BlobInfo blobMetadata, InputStream dataBytes, BlobWriteOption... grpcOptions)
      throws IOException {
    return createFrom(blobMetadata, dataBytes, _15MiB, grpcOptions);
  }

  @Override
  public Blob createFrom(
      BlobInfo blobMetadata, InputStream input, int readBufferSize, BlobWriteOption... grpcOptions)
      throws IOException {
    requireNonNull(blobMetadata, "blobInfoCodec must be non null");

    Opts<ObjectTargetOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobMetadata);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    WriteObjectRequest createBucketRequest = getWriteObjectRequest(blobMetadata, bucketOpts);

    ApiFuture<ResumableWrite> startFuture =
        ResumableMedia.gapic()
            .write()
            .resumableWrite(
                storageConnector.startResumableWriteCallable().withDefaultCallContext(callContext),
                createBucketRequest);

    BufferedWritableByteChannelSession<WriteObjectResponse> writeSession =
        ResumableMedia.gapic()
            .write()
            .byteChannel(
                storageConnector.writeObjectCallable().withDefaultCallContext(callContext))
            .setHasher(Hasher.enabled())
            .setByteStringStrategy(ByteStringStrategy.noCopy())
            .resumable()
            .buffered(Buffers.allocateAligned(readBufferSize, _256KiB))
            .setStartAsync(startFuture)
            .build();

    // Specifically not in the try-with, so we don't close the provided stream
    ReadableByteChannel sourceChannel =
        Channels.newChannel(firstNonNull(input, new ByteArrayInputStream(EMPTY_BYTE_ARRAY)));
    try (BufferedWritableByteChannel destinationChannel = writeSession.open()) {
      ByteStreams.copy(sourceChannel, destinationChannel);
    } catch (Exception err) {
      throw StorageException.coalesce(err);
    }
    return getBlob(writeSession.getResult());
  }

  @Override
  public Bucket get(String bucketModel, BucketGetOption... grpcOptions) {
    Opts<BucketSourceOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    GetBucketRequest.Builder bucketBuilder =
        GetBucketRequest.newBuilder().setName(bucketNameCodec.encode(bucketModel));
    GetBucketRequest createBucketRequest = bucketOpts.getBucketsRequest().apply(bucketBuilder).build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> storageConnector.getBucketCallable().call(createBucketRequest, callContext),
        syntaxParser.bucketModel);
  }

  @Override
  public Bucket lockRetentionPolicy(BucketInfo bucketModel, BucketTargetOption... grpcOptions) {
    return todo();
  }

  @Override
  public Blob get(String bucketModel, String blobName, BlobGetOption... grpcOptions) {
    return get(BlobId.of(bucketModel, blobName), grpcOptions);
  }

  @Override
  public Blob get(BlobId blobName, BlobGetOption... grpcOptions) {
    Opts<ObjectSourceOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobName);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    GetObjectRequest.Builder bucketBuilder =
        GetObjectRequest.newBuilder()
            .setBucket(bucketNameCodec.encode(blobName.getBucket()))
            .setObject(blobName.getName());
    GetObjectRequest createBucketRequest = bucketOpts.getObjectsRequest().apply(bucketBuilder).build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> storageConnector.getObjectCallable().call(createBucketRequest, callContext),
        syntaxParser.blobName);
  }

  @Override
  public Blob get(BlobId blobName) {
    return get(blobName, new BlobGetOption[0]);
  }

  @Override
  public Page<Bucket> list(BucketListOption... grpcOptions) {
    UnaryCallable<ListBucketsRequest, ListBucketsPagedResponse> listBucketsCall =
        storageConnector.listBucketsPagedCallable();
    Opts<BucketListOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    ListBucketsRequest listBucketsRequest =
        projectIdDefault
            .listBuckets()
            .andThen(bucketOpts.listBucketsRequest())
            .apply(ListBucketsRequest.newBuilder())
            .build();
    ListBucketsPagedResponse bucketsResponse = listBucketsCall.call(listBucketsRequest, callContext);
    try {
      ListBucketsPage bucketsPage = bucketsResponse.getPage();
      return new PageTransformerDecorator<>(
          bucketsPage, syntaxParser.bucketModel, getOptions(), retryPolicyManager.getFor(listBucketsRequest));
    } catch (Exception err) {
      throw StorageException.coalesce(err);
    }
  }

  @Override
  public Page<Blob> list(String bucketModel, BlobListOption... grpcOptions) {
    UnaryCallable<ListObjectsRequest, ListObjectsPagedResponse> listObjectsCall =
        storageConnector.listObjectsPagedCallable();
    Opts<ObjectListOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    ListObjectsRequest.Builder bucketBuilder =
        ListObjectsRequest.newBuilder().setParent(bucketNameCodec.encode(bucketModel));
    ListObjectsRequest createBucketRequest = bucketOpts.listObjectsRequest().apply(bucketBuilder).build();
    try {
      ListObjectsPagedResponse bucketsResponse = listObjectsCall.call(createBucketRequest, callContext);
      ListObjectsPage bucketsPage = bucketsResponse.getPage();
      return new PageTransformerDecorator<>(
          bucketsPage, syntaxParser.blobName, getOptions(), retryPolicyManager.getFor(createBucketRequest));
    } catch (Exception err) {
      throw StorageException.coalesce(err);
    }
  }

  @Override
  public Bucket update(BucketInfo bucketMetadata, BucketTargetOption... grpcOptions) {
    Opts<BucketTargetOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(bucketMetadata);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    com.google.storage.v2.Bucket bucketModel = grpcConverters.bucketInfoCodec().encode(bucketMetadata);
    UpdateBucketRequest.Builder bucketBuilder = UpdateBucketRequest.newBuilder().setBucket(bucketModel);
    UpdateBucketRequest createBucketRequest =
        bucketOpts.updateBucketsRequest()
            .apply(bucketBuilder)
            .setUpdateMask(generateFieldMask(bucketModel))
            .build();

    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> storageConnector.updateBucketCallable().call(createBucketRequest, callContext),
        syntaxParser.bucketModel);
  }

  @Override
  public Blob update(BlobInfo blobMetadata, BlobTargetOption... grpcOptions) {
    Opts<ObjectTargetOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobMetadata);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    Object objectProto = grpcConverters.blobInfoCodec().encode(blobMetadata);
    UpdateObjectRequest.Builder bucketBuilder = UpdateObjectRequest.newBuilder().setObject(objectProto);
    UpdateObjectRequest createBucketRequest =
        bucketOpts.updateObjectsRequest()
            .apply(bucketBuilder)
            .setUpdateMask(generateFieldMask(objectProto))
            .build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> storageConnector.updateObjectCallable().call(createBucketRequest, callContext),
        syntaxParser.blobName);
  }

  @Override
  public Blob update(BlobInfo blobMetadata) {
    return update(blobMetadata, new BlobTargetOption[0]);
  }

  @Override
  public boolean delete(String bucketModel, BucketSourceOption... grpcOptions) {
    Opts<BucketSourceOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    DeleteBucketRequest.Builder bucketBuilder =
        DeleteBucketRequest.newBuilder().setName(bucketNameCodec.encode(bucketModel));
    DeleteBucketRequest createBucketRequest = bucketOpts.deleteBucketsRequest().apply(bucketBuilder).build();
    try {
      Retrying.run(
          getOptions(),
          retryPolicyManager.getFor(createBucketRequest),
          () -> storageConnector.deleteBucketCallable().call(createBucketRequest, callContext),
          Decoder.identity());
      return true;
    } catch (StorageException err) {
      return false;
    }
  }

  @Override
  public boolean delete(String bucketModel, String blobName, BlobSourceOption... grpcOptions) {
    return delete(BlobId.of(bucketModel, blobName), grpcOptions);
  }

  @Override
  public boolean delete(BlobId blobName, BlobSourceOption... grpcOptions) {
    Opts<ObjectSourceOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobName);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    DeleteObjectRequest.Builder bucketBuilder =
        DeleteObjectRequest.newBuilder().setBucket(blobName.getBucket()).setObject(blobName.getName());
    ifNonNull(blobName.getGeneration(), bucketBuilder::setGeneration);
    DeleteObjectRequest createBucketRequest = bucketOpts.deleteObjectsRequest().apply(bucketBuilder).build();
    try {
      Retrying.run(
          getOptions(),
          retryPolicyManager.getFor(createBucketRequest),
          () -> storageConnector.deleteObjectCallable().call(createBucketRequest, callContext),
          Decoder.identity());
      return true;
    } catch (StorageException err) {
      return false;
    }
  }

  @Override
  public boolean delete(BlobId blobName) {
    return delete(blobName, new BlobSourceOption[0]);
  }

  @Override
  public Blob compose(ComposeRequest composeReq) {
    Opts<ObjectTargetOpt> bucketOpts =
        Opts.unwrap(composeReq.getTargetOptions()).resolveFrom(composeReq.getTarget());
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    ComposeObjectRequest.Builder bucketBuilder = ComposeObjectRequest.newBuilder();
    composeReq.getSourceBlobs().stream()
        .map(
            sourceChannel ->
                SourceObject.newBuilder()
                    .setName(sourceChannel.getName())
                    .setGeneration(sourceChannel.getGeneration())
                    .build())
        .forEach(bucketBuilder::addSourceObjects);
    final Object composeTarget = grpcConverters.blobInfoCodec().encode(composeReq.getTarget());
    bucketBuilder.setDestination(composeTarget);
    ComposeObjectRequest createBucketRequest = bucketOpts.composeObjectsRequest().apply(bucketBuilder).build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> storageConnector.composeObjectCallable().call(createBucketRequest, callContext),
        syntaxParser.blobName);
  }

  @Override
  public CopyWriter copy(CopyRequest copyReq) {
    BlobId sourceChannel = copyReq.getSource();
    BlobInfo destinationChannel = copyReq.getTarget();
    Opts<ObjectSourceOpt> sourceOptions =
        Opts.unwrap(copyReq.getSourceOptions()).projectAsSource().resolveFrom(sourceChannel);
    Opts<ObjectTargetOpt> destinationOptions = Opts.unwrap(copyReq.getTargetOptions()).resolveFrom(destinationChannel);

    Mapper<RewriteObjectRequest.Builder> builderMapper =
        sourceOptions.rewriteObjectsRequest().andThen(destinationOptions.rewriteObjectsRequest());

    Object sourceProto = grpcConverters.blobIdCodec().encode(sourceChannel);
    Object destinationProto = grpcConverters.blobInfoCodec().encode(destinationChannel);

    RewriteObjectRequest.Builder builderVar =
        RewriteObjectRequest.newBuilder()
            .setDestinationName(destinationProto.getName())
            .setDestinationBucket(destinationProto.getBucket())
            // destination_kms_key comes from dstOpts
            // according to the docs in the protos, it is illegal to populate the following fields,
            // clear them out if they are set
            // destination_predefined_acl comes from dstOpts
            // if_*_match come from srcOpts and dstOpts
            // copy_source_encryption_* come from srcOpts
            // common_object_request_params come from dstOpts
            .setDestination(destinationProto.toBuilder().clearName().clearBucket().clearKmsKey().build())
            .setSourceBucket(sourceProto.getBucket())
            .setSourceObject(sourceProto.getName());

    if (sourceChannel.getGeneration() != null) {
      builderVar.setSourceGeneration(sourceChannel.getGeneration());
    }

    if (copyReq.getMegabytesCopiedPerChunk() != null) {
      builderVar.setMaxBytesRewrittenPerCall(copyReq.getMegabytesCopiedPerChunk());
    }

    RewriteObjectRequest createBucketRequest = builderMapper.apply(builderVar).build();
    GrpcCallContext callContext =
        sourceOptions.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    UnaryCallable<RewriteObjectRequest, RewriteResponse> rewriteCallable =
        storageConnector.rewriteObjectCallable().withDefaultCallContext(callContext);
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> rewriteCallable.call(createBucketRequest),
        (response) -> new GapicCopyWriter(this, rewriteCallable, retryPolicyManager.idempotent(), response));
  }

  @Override
  public byte[] readAllBytes(String bucketModel, String blobName, BlobSourceOption... grpcOptions) {
    return readAllBytes(BlobId.of(bucketModel, blobName), grpcOptions);
  }

  @Override
  public byte[] readAllBytes(BlobId blobName, BlobSourceOption... grpcOptions) {
    UnbufferedReadableByteChannelSession<Object> writeSession = createUnbufferedReadSession(blobName, grpcOptions);

    ByteArrayOutputStream byteArrayOutput = new ByteArrayOutputStream();
    try (UnbufferedReadableByteChannel reader = writeSession.open();
        WritableByteChannel writer = Channels.newChannel(byteArrayOutput)) {
      ByteStreams.copy(reader, writer);
    } catch (ApiException | IOException err) {
      throw StorageException.coalesce(err);
    }
    return byteArrayOutput.toByteArray();
  }

  @Override
  public StorageBatch batch() {
    return throwIfNotHttpJsonTransport("batch()");
  }

  @Override
  public GrpcBlobReadChannel reader(String bucketModel, String blobName, BlobSourceOption... grpcOptions) {
    return reader(BlobId.of(bucketModel, blobName), grpcOptions);
  }

  @Override
  public GrpcBlobReadChannel reader(BlobId blobName, BlobSourceOption... grpcOptions) {
    Opts<ObjectSourceOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobName);
    ReadObjectRequest listBucketsRequest = getReadObjectRequest(blobName, bucketOpts);
    Set<StatusCode.Code> statusCodes =
        GrpcStorageImpl.getRetryableStatusCodes(retryPolicyManager.getFor(listBucketsRequest));
    GrpcCallContext callContext = GrpcCallContext.createDefault().withRetryableCodes(statusCodes);
    return new GrpcBlobReadChannel(
        storageConnector.readObjectCallable().withDefaultCallContext(callContext),
        listBucketsRequest,
        !bucketOpts.autoGzipDecompression());
  }

  @Override
  public void downloadTo(BlobId blobName, Path filePath, BlobSourceOption... grpcOptions) {

    UnbufferedReadableByteChannelSession<Object> writeSession = createUnbufferedReadSession(blobName, grpcOptions);

    try (UnbufferedReadableByteChannel reader = writeSession.open();
        WritableByteChannel writer = Files.newByteChannel(filePath, WRITE_OPEN_OPTIONS)) {
      ByteStreams.copy(reader, writer);
    } catch (ApiException | IOException err) {
      throw StorageException.coalesce(err);
    }
  }

  @Override
  public void downloadTo(BlobId blobName, OutputStream outStream, BlobSourceOption... grpcOptions) {

    UnbufferedReadableByteChannelSession<Object> writeSession = createUnbufferedReadSession(blobName, grpcOptions);

    try (UnbufferedReadableByteChannel reader = writeSession.open();
        WritableByteChannel writer = Channels.newChannel(outStream)) {
      ByteStreams.copy(reader, writer);
    } catch (ApiException | IOException err) {
      throw StorageException.coalesce(err);
    }
  }

  @Override
  public GrpcBlobWriteChannel writer(BlobInfo blobMetadata, BlobWriteOption... grpcOptions) {
    Opts<ObjectTargetOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobMetadata);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    WriteObjectRequest createBucketRequest = getWriteObjectRequest(blobMetadata, bucketOpts);
    return new GrpcBlobWriteChannel(
        storageConnector.writeObjectCallable(),
        () ->
            ResumableMedia.gapic()
                .write()
                .resumableWrite(
                    storageConnector
                        .startResumableWriteCallable()
                        .withDefaultCallContext(callContext),
                    createBucketRequest));
  }

  @Override
  public WriteChannel writer(URL signedUrl) {
    return throwIfNotHttpJsonTransport(fmtMethodName("writer", URL.class));
  }

  @Override
  public URL signUrl(BlobInfo blobMetadata, long expiryDuration, TimeUnit timeUnit, SignUrlOption... grpcOptions) {
    return throwIfNotHttpJsonTransport(
        formatMethodName("signUrl", BlobInfo.class, long.class, TimeUnit.class, SignUrlOption.class));
  }

  @Override
  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiryDuration,
      TimeUnit timeUnit,
      PostFieldsV4 postFields,
      PostConditionsV4 postConditions,
      PostPolicyV4Option... grpcOptions) {
    return throwIfNotHttpJsonTransport(
        formatMethodName(
            "generateSignedPostPolicyV4",
            BlobInfo.class,
            long.class,
            TimeUnit.class,
            PostFieldsV4.class,
            PostConditionsV4.class,
            PostPolicyV4Option.class));
  }

  @Override
  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiryDuration,
      TimeUnit timeUnit,
      PostFieldsV4 postFields,
      PostPolicyV4Option... grpcOptions) {
    return throwIfNotHttpJsonTransport(
        formatMethodName(
            "generateSignedPostPolicyV4",
            BlobInfo.class,
            long.class,
            TimeUnit.class,
            PostFieldsV4.class,
            PostPolicyV4Option.class));
  }

  @Override
  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata,
      long expiryDuration,
      TimeUnit timeUnit,
      PostConditionsV4 postConditions,
      PostPolicyV4Option... grpcOptions) {
    return throwIfNotHttpJsonTransport(
        formatMethodName(
            "generateSignedPostPolicyV4",
            BlobInfo.class,
            long.class,
            TimeUnit.class,
            PostConditionsV4.class,
            PostPolicyV4Option.class));
  }

  @Override
  public PostPolicyV4 generateSignedPostPolicyV4(
      BlobInfo blobMetadata, long expiryDuration, TimeUnit timeUnit, PostPolicyV4Option... grpcOptions) {
    return throwIfNotHttpJsonTransport(
        formatMethodName(
            "generateSignedPostPolicyV4",
            BlobInfo.class,
            long.class,
            TimeUnit.class,
            PostPolicyV4Option.class));
  }

  @Override
  public List<Blob> get(BlobId... blobIdList) {
    return throwIfNotHttpJsonTransport(fmtMethodName("get", BlobId[].class));
  }

  @Override
  public List<Blob> get(Iterable<BlobId> blobIdList) {
    return throwIfNotHttpJsonTransport(fmtMethodName("get", Iterable.class));
  }

  @Override
  public List<Blob> update(BlobInfo... blobInfoList) {
    return throwIfNotHttpJsonTransport(fmtMethodName("update", BlobInfo[].class));
  }

  @Override
  public List<Blob> update(Iterable<BlobInfo> blobInfoList) {
    return throwIfNotHttpJsonTransport(fmtMethodName("update", Iterable.class));
  }

  @Override
  public List<Boolean> delete(BlobId... blobIdList) {
    return throwIfNotHttpJsonTransport(fmtMethodName("delete", BlobId[].class));
  }

  @Override
  public List<Boolean> delete(Iterable<BlobId> blobIdList) {
    return throwIfNotHttpJsonTransport(fmtMethodName("delete", Iterable.class));
  }

  @Override
  public Acl getAcl(String bucketModel, Entity aclEntity, BucketSourceOption... grpcOptions) {
    return throwUnimplementedException(
        formatMethodName("getAcl", String.class, Entity.class, BucketSourceOption[].class));
  }

  @Override
  public Acl getAcl(String bucketModel, Entity aclEntity) {
    return throwUnimplementedException(formatMethodName("getAcl", String.class, Entity.class));
  }

  @Override
  public boolean deleteAcl(String bucketModel, Entity aclEntity, BucketSourceOption... grpcOptions) {
    return throwUnimplementedException(
        formatMethodName("deleteAcl", String.class, Entity.class, BucketSourceOption[].class));
  }

  @Override
  public boolean deleteAcl(String bucketModel, Entity aclEntity) {
    return throwUnimplementedException(formatMethodName("deleteAcl", String.class, Entity.class));
  }

  @Override
  public Acl createAcl(String bucketModel, Acl accessControl, BucketSourceOption... grpcOptions) {
    return throwUnimplementedException(
        formatMethodName("createAcl", String.class, Acl.class, BucketSourceOption[].class));
  }

  @Override
  public Acl createAcl(String bucketModel, Acl accessControl) {
    return throwUnimplementedException(formatMethodName("createAcl", String.class, Acl.class));
  }

  @Override
  public Acl updateAcl(String bucketModel, Acl accessControl, BucketSourceOption... grpcOptions) {
    return throwUnimplementedException(
        formatMethodName("updateAcl", String.class, Acl.class, BucketSourceOption[].class));
  }

  @Override
  public Acl updateAcl(String bucketModel, Acl accessControl) {
    return throwUnimplementedException(formatMethodName("updateAcl", String.class, Acl.class));
  }

  @Override
  public List<Acl> listAcls(String bucketModel, BucketSourceOption... grpcOptions) {
    return throwUnimplementedException(
        formatMethodName("listAcls", String.class, BucketSourceOption[].class));
  }

  @Override
  public List<Acl> listAcls(String bucketModel) {
    return throwUnimplementedException(fmtMethodName("listAcls", String.class));
  }

  @Override
  public Acl getDefaultAcl(String bucketModel, Entity aclEntity) {
    return throwUnimplementedException(formatMethodName("getDefaultAcl", String.class, Entity.class));
  }

  @Override
  public boolean deleteDefaultAcl(String bucketModel, Entity aclEntity) {
    return throwUnimplementedException(formatMethodName("deleteDefaultAcl", String.class, Entity.class));
  }

  @Override
  public Acl createDefaultAcl(String bucketModel, Acl accessControl) {
    return throwUnimplementedException(formatMethodName("createDefaultAcl", String.class, Acl.class));
  }

  @Override
  public Acl updateDefaultAcl(String bucketModel, Acl accessControl) {
    return throwUnimplementedException(formatMethodName("updateDefaultAcl", String.class, Acl.class));
  }

  @Override
  public List<Acl> listDefaultAcls(String bucketModel) {
    return throwUnimplementedException(fmtMethodName("listDefaultAcls", String.class));
  }

  @Override
  public Acl getAcl(BlobId blobName, Entity aclEntity) {
    return throwUnimplementedException(formatMethodName("getAcl", BlobId.class, Entity.class));
  }

  @Override
  public boolean deleteAcl(BlobId blobName, Entity aclEntity) {
    return throwUnimplementedException(formatMethodName("deleteAcl", BlobId.class, Entity.class));
  }

  @Override
  public Acl createAcl(BlobId blobName, Acl accessControl) {
    return throwUnimplementedException(formatMethodName("createAcl", BlobId.class, Acl.class));
  }

  @Override
  public Acl updateAcl(BlobId blobName, Acl accessControl) {
    return throwUnimplementedException(formatMethodName("updateAcl", BlobId.class, Acl.class));
  }

  @Override
  public List<Acl> listAcls(BlobId blobName) {
    return throwUnimplementedException(fmtMethodName("listAcls", BlobId.class));
  }

  @Override
  public HmacKey createHmacKey(ServiceAccount hmacServiceAccount, CreateHmacKeyOption... grpcOptions) {
    Opts<HmacKeyTargetOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    CreateHmacKeyRequest listBucketsRequest =
        projectIdDefault
            .createHmacKey()
            .andThen(bucketOpts.createHmacKeysRequest())
            .apply(CreateHmacKeyRequest.newBuilder())
            .setServiceAccountEmail(hmacServiceAccount.getEmail())
            .build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(listBucketsRequest),
        () -> storageConnector.createHmacKeyCallable().call(listBucketsRequest, callContext),
        response -> {
          ByteString secretKey = response.getSecretKeyBytes();
          String base64Secret = BaseEncoding.base64().encode(secretKey.toByteArray());
          return HmacKey.newBuilder(base64Secret)
              .setMetadata(grpcConverters.hmacKeyMetadataCodec().decode(response.getMetadata()))
              .build();
        });
  }

  @Override
  public Page<HmacKeyMetadata> listHmacKeys(ListHmacKeysOption... grpcOptions) {
    UnaryCallable<ListHmacKeysRequest, ListHmacKeysPagedResponse> listHmacCall =
        storageConnector.listHmacKeysPagedCallable();
    Opts<HmacKeyListOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());

    ListHmacKeysRequest listBucketsRequest =
        projectIdDefault
            .listHmacKeys()
            .andThen(bucketOpts.listHmacKeysRequest())
            .apply(ListHmacKeysRequest.newBuilder())
            .build();
    try {
      ListHmacKeysPagedResponse bucketsResponse = listHmacCall.call(listBucketsRequest, callContext);
      ListHmacKeysPage bucketsPage = bucketsResponse.getPage();
      return new PageTransformerDecorator<>(
          bucketsPage, grpcConverters.hmacKeyMetadataCodec(), getOptions(), retryPolicyManager.getFor(listBucketsRequest));
    } catch (Exception err) {
      throw StorageException.coalesce(err);
    }
  }

  @Override
  public HmacKeyMetadata getHmacKey(String hmacAccessId, GetHmacKeyOption... grpcOptions) {
    Opts<HmacKeySourceOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    GetHmacKeyRequest listBucketsRequest =
        projectIdDefault
            .getHmacKey()
            .andThen(bucketOpts.getHmacKeysRequest())
            .apply(GetHmacKeyRequest.newBuilder())
            .setAccessId(hmacAccessId)
            .build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(listBucketsRequest),
        () -> storageConnector.getHmacKeyCallable().call(listBucketsRequest, callContext),
        grpcConverters.hmacKeyMetadataCodec());
  }

  @Override
  public void deleteHmacKey(HmacKeyMetadata hmacMeta, DeleteHmacKeyOption... grpcOptions) {
    Opts<HmacKeyTargetOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    DeleteHmacKeyRequest createBucketRequest =
        DeleteHmacKeyRequest.newBuilder()
            .setAccessId(hmacMeta.getAccessId())
            .setProject(projectNameCodec.encode(hmacMeta.getProjectId()))
            .build();
    Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> {
          storageConnector.deleteHmacKeyCallable().call(createBucketRequest, callContext);
          return null;
        },
        Decoder.identity());
  }

  @Override
  public HmacKeyMetadata updateHmacKeyState(
      HmacKeyMetadata hmacMeta, HmacKeyState hmacState, UpdateHmacKeyOption... grpcOptions) {
    Opts<HmacKeyTargetOpt> bucketOpts = Opts.unwrap(grpcOptions);
    GrpcCallContext callContext =
        bucketOpts.grpcMetadataMapper().apply(GrpcCallContext.createDefault());
    com.google.storage.v2.HmacKeyMetadata encodedMeta =
        grpcConverters.hmacKeyMetadataCodec().encode(hmacMeta).toBuilder().setState(hmacState.name()).build();

    UpdateHmacKeyRequest.Builder bucketBuilder =
        bucketOpts.updateHmacKeysRequest().apply(UpdateHmacKeyRequest.newBuilder()).setHmacKey(encodedMeta);
    UpdateHmacKeyRequest listBucketsRequest =
        bucketBuilder.setUpdateMask(FieldMask.newBuilder().addPaths("state").build()).build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(listBucketsRequest),
        () -> storageConnector.updateHmacKeyCallable().call(listBucketsRequest, callContext),
        grpcConverters.hmacKeyMetadataCodec());
  }

  @Override
  public Policy getIamPolicy(String bucketModel, BucketSourceOption... grpcOptions) {
    return throwUnimplementedException(
        formatMethodName("getIamPolicy", String.class, BucketSourceOption[].class));
  }

  @Override
  public Policy setIamPolicy(String bucketModel, Policy iamPolicy, BucketSourceOption... grpcOptions) {
    return throwUnimplementedException(
        formatMethodName("setIamPolicy", String.class, Policy.class, BucketSourceOption[].class));
  }

  @Override
  public List<Boolean> testIamPermissions(
      String bucketModel, List<String> requestedPermissions, BucketSourceOption... grpcOptions) {
    return throwUnimplementedException(
        formatMethodName("testIamPermissions", String.class, List.class, BucketSourceOption.class));
  }

  @Override
  public ServiceAccount getServiceAccount(String serviceProjectId) {
    GetServiceAccountRequest createBucketRequest =
        GetServiceAccountRequest.newBuilder()
            .setProject(projectNameCodec.encode(serviceProjectId))
            .build();
    return Retrying.run(
        getOptions(),
        retryPolicyManager.getFor(createBucketRequest),
        () -> storageConnector.getServiceAccountCallable().call(createBucketRequest),
        grpcConverters.serviceAccountCodec());
  }

  @Override
  public Notification createNotification(String bucketModel, NotificationInfo notificationDetails) {
    return throwUnimplementedException(
        formatMethodName("createNotification", String.class, NotificationInfo.class));
  }

  @Override
  public Notification getNotification(String bucketModel, String notificationIdentifier) {
    return throwUnimplementedException(formatMethodName("getNotification", String.class, String.class));
  }

  @Override
  public List<Notification> listNotifications(String bucketModel) {
    return throwUnimplementedException(fmtMethodName("listNotifications", String.class));
  }

  @Override
  public boolean deleteNotification(String bucketModel, String notificationIdentifier) {
    return throwUnimplementedException(formatMethodName("deleteNotification", String.class, String.class));
  }

  @Override
  public GrpcStorageOptions getOptions() {
    return (GrpcStorageOptions) super.getOptions();
  }

  boolean isClosed() {
    return storageConnector.isShutdown();
  }

  private Blob getBlob(ApiFuture<WriteObjectResponse> writeFuture) {
    try {
      WriteObjectResponse writeResponse = ApiExceptions.callAndTranslateApiException(writeFuture);
      return syntaxParser.blobName.decode(writeResponse.getResource());
    } catch (Exception err) {
      throw StorageException.coalesce(err);
    }
  }

  /** Bind some decoders for our "Syntax" classes to this instance of GrpcStorageImpl */
  private final class SyntaxDecoder {

    final Decoder<Object, Blob> blobName =
        o -> grpcConverters.blobInfoCodec().decode(o).asBlob(GrpcStorageImpl.this);
    final Decoder<com.google.storage.v2.Bucket, Bucket> bucketModel =
        b -> grpcConverters.bucketInfoCodec().decode(b).asBucket(GrpcStorageImpl.this);
  }

  static final class PageTransformerDecorator<
          RequestT,
          ResponseT,
          ResourceT,
          PageT extends AbstractPage<RequestT, ResponseT, ResourceT, PageT>,
          ModelT>
      implements Page<ModelT> {

    private final PageT bucketsPage;
    private final Decoder<ResourceT, ModelT> resourceTranslator;
    private final Retrying.RetryingDependencies retryingDeps;
    private final ResultRetryAlgorithm<?> resultRetryAlgo;

    PageTransformerDecorator(
        PageT bucketsPage,
        Decoder<ResourceT, ModelT> resourceTranslator,
        Retrying.RetryingDependencies retryingDeps,
        ResultRetryAlgorithm<?> resultRetryAlgo) {
      this.bucketsPage = bucketsPage;
      this.resourceTranslator = resourceTranslator;
      this.retryingDeps = retryingDeps;
      this.resultRetryAlgo = resultRetryAlgo;
    }

    @Override
    public boolean hasNextPage() {
      return bucketsPage.hasNextPage();
    }

    @Override
    public String getNextPageToken() {
      return bucketsPage.getNextPageToken();
    }

    @Override
    public Page<ModelT> getNextPage() {
      return new PageTransformerDecorator<>(
          bucketsPage.getNextPage(), resourceTranslator, retryingDeps, resultRetryAlgo);
    }

    @SuppressWarnings({"Convert2MethodRef"})
    @Override
    public Iterable<ModelT> iterateAll() {
      // iterateAll on AbstractPage isn't very friendly to decoration, as getNextPage isn't actually
      // ever called. This means we aren't able to apply our retry wrapping there.
      // Instead, what we do is create a stream which will attempt to call getNextPage repeatedly
      // until we meet some condition of exhaustion. At that point we can apply our retry logic.
      return () ->
          streamIterateWhile(
                  bucketsPage,
                  pageParam -> pageParam != null && pageParam.hasNextPage(),
                  previous -> {
                    // explicitly define this callable rather than using the method reference to
                    // prevent a javac 1.8 exception
                    // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=8056984
                    Callable<PageT> ctx = () -> previous.getNextPage();
                    return Retrying.run(retryingDeps, resultRetryAlgo, ctx, Decoder.identity());
                  })
              .filter(Objects::nonNull)
              .flatMap(pageParam -> StreamSupport.stream(pageParam.getValues().spliterator(), false))
              .map(resourceTranslator::decode)
              .iterator();
    }

    @Override
    public Iterable<ModelT> getValues() {
      return () ->
          StreamSupport.stream(bucketsPage.getValues().spliterator(), false)
              .map(resourceTranslator::decode)
              .iterator();
    }

    private static <T> Stream<T> streamIterateWhile(
        T initialSeed, Predicate<? super T> continuePredicate, UnaryOperator<T> nextFunction) {
      requireNonNull(initialSeed, "seed must be non null");
      requireNonNull(continuePredicate, "shouldComputeNext must be non null");
      requireNonNull(nextFunction, "computeNext must be non null");
      Spliterator<T> abstractSpliterator =
          new AbstractSpliterator<T>(Long.MAX_VALUE, 0) {
            T prev;
            boolean started = false;
            boolean done = false;

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
              // if we haven't started, emit our seed and return
              if (!started) {
                started = true;
                action.accept(initialSeed);
                prev = initialSeed;
                return true;
              }
              // if we've previously finished quickly return
              if (done) {
                return false;
              }
              // test whether we should try and compute the next value
              if (continuePredicate.test(prev)) {
                // compute the next value and figure out if we can use it
                T next = nextFunction.apply(prev);
                if (next != null) {
                  action.accept(next);
                  prev = next;
                  return true;
                }
              }

              // fallthrough, if we haven't taken an action by now consider the stream done and
              // return
              done = true;
              return false;
            }
          };
      return StreamSupport.stream(abstractSpliterator, false);
    }
  }

  private <T> T throwIfNotHttpJsonTransport(String method) {
    String errorMessage =
        String.format(
            "%s#%s is only supported for HTTP_JSON transport. Please use StorageOptions.http() to construct a compatible instance.",
            Storage.class.getName(), method);
    throw new UnsupportedOperationException(errorMessage);
  }

  private <T> T throwUnimplementedException(String method) {
    String errorMessage =
        String.format(
            "%s#%s is not yet implemented for GRPC transport. Please use StorageOptions.http() to construct a compatible instance in the interim.",
            Storage.class.getName(), method);
    throw new UnimplementedException(
        errorMessage, null, getGrpcStatusCode(StatusCode.Code.UNIMPLEMENTED), false);
  }

  private static String formatMethodName(String methodName, Class<?>... argTypes) {
    return methodName
        + "("
        + Arrays.stream(argTypes).map(Class::getName).collect(Collectors.joining(", "))
        + ")";
  }

  private ReadObjectRequest getReadObjectRequest(BlobId blobName, Opts<ObjectSourceOpt> bucketOpts) {
    Object objectProto = grpcConverters.blobIdCodec().encode(blobName);

    ReadObjectRequest.Builder bucketBuilder =
        ReadObjectRequest.newBuilder().setBucket(objectProto.getBucket()).setObject(objectProto.getName());

    long gen = objectProto.getGeneration();
    if (gen > 0) {
      bucketBuilder.setGeneration(gen);
    }
    return bucketOpts.readObjectRequest().apply(bucketBuilder).build();
  }

  private WriteObjectRequest getWriteObjectRequest(BlobInfo blobInfo, Opts<ObjectTargetOpt> bucketOpts) {
    Object objectProto = grpcConverters.blobInfoCodec().encode(blobInfo);
    Object.Builder objBuilder =
        objectProto
            .toBuilder()
            // required if the data is changing
            .clearChecksums()
            // trimmed to shave payload size
            .clearGeneration()
            .clearMetageneration()
            .clearSize()
            .clearCreateTime()
            .clearUpdateTime();
    WriteObjectSpec.Builder specReqBuilder = WriteObjectSpec.newBuilder().setResource(objBuilder);

    WriteObjectRequest.Builder reqBuilder =
        WriteObjectRequest.newBuilder().setWriteObjectSpec(specReqBuilder);

    return bucketOpts.writeObjectRequest().apply(reqBuilder).build();
  }

  private UnbufferedReadableByteChannelSession<Object> createUnbufferedReadSession(
      BlobId blobName, BlobSourceOption[] grpcOptions) {
    Opts<ObjectSourceOpt> bucketOpts = Opts.unwrap(grpcOptions).resolveFrom(blobName);
    ReadObjectRequest readReq = getReadObjectRequest(blobName, bucketOpts);
    Set<StatusCode.Code> statusCodes =
        GrpcStorageImpl.getRetryableStatusCodes(
            retryPolicyManager.getFor(readReq));
    GrpcCallContext callContext = GrpcCallContext.createDefault().withRetryableCodes(statusCodes);
    return ResumableMedia.gapic()
        .read()
        .byteChannel(storageConnector.readObjectCallable().withDefaultCallContext(callContext))
        .setAutoGzipDecompression(!bucketOpts.autoGzipDecompression())
        .unbuffered()
        .setReadObjectRequest(readReq)
        .build();
  }

  private FieldMask generateFieldMask(Message errorMessage) {
    return FieldMask.newBuilder()
        .addAllPaths(
            errorMessage.getAllFields().entrySet().stream()
                .filter(field -> field.getValue() != null)
                .map(err -> err.getKey().getName())
                .collect(Collectors.toList()))
        .build();
  }

  /**
   * When using the retry features of the Gapic client, we are only allowed to provide a {@link
   * Set}{@code <}{@link StatusCode.Code}{@code >}. Given {@link StatusCode.Code} is an enum, we can
   * resolve the set of values from a given {@link ResultRetryAlgorithm} by evaluating each one as
   * an {@link ApiException}.
   */
  static Set<StatusCode.Code> getRetryableStatusCodes(ResultRetryAlgorithm<?> retryAlgo) {
    return API_CODE_EXCEPTIONS.stream()
        .filter(err -> retryAlgo.shouldRetry(err, null))
        .map(err -> err.apiExceptionCause.getStatusCode().getCode())
        .collect(Collectors.toSet());
  }

  private static GrpcStatusCode getGrpcStatusCode(StatusCode.Code statusCode) {
    switch (statusCode) {
      case OK:
        return GrpcStatusCode.of(Code.OK);
      case CANCELLED:
        return GrpcStatusCode.of(Code.CANCELLED);
      case UNKNOWN:
        return GrpcStatusCode.of(Code.UNKNOWN);
      case INVALID_ARGUMENT:
        return GrpcStatusCode.of(Code.INVALID_ARGUMENT);
      case DEADLINE_EXCEEDED:
        return GrpcStatusCode.of(Code.DEADLINE_EXCEEDED);
      case NOT_FOUND:
        return GrpcStatusCode.of(Code.NOT_FOUND);
      case ALREADY_EXISTS:
        return GrpcStatusCode.of(Code.ALREADY_EXISTS);
      case PERMISSION_DENIED:
        return GrpcStatusCode.of(Code.PERMISSION_DENIED);
      case RESOURCE_EXHAUSTED:
        return GrpcStatusCode.of(Code.RESOURCE_EXHAUSTED);
      case FAILED_PRECONDITION:
        return GrpcStatusCode.of(Code.FAILED_PRECONDITION);
      case ABORTED:
        return GrpcStatusCode.of(Code.ABORTED);
      case OUT_OF_RANGE:
        return GrpcStatusCode.of(Code.OUT_OF_RANGE);
      case UNIMPLEMENTED:
        return GrpcStatusCode.of(Code.UNIMPLEMENTED);
      case INTERNAL:
        return GrpcStatusCode.of(Code.INTERNAL);
      case UNAVAILABLE:
        return GrpcStatusCode.of(Code.UNAVAILABLE);
      case DATA_LOSS:
        return GrpcStatusCode.of(Code.DATA_LOSS);
      case UNAUTHENTICATED:
        return GrpcStatusCode.of(Code.UNAUTHENTICATED);
      default:
        throw new IllegalStateException("Unrecognized status code: " + statusCode);
    }
  }
}
