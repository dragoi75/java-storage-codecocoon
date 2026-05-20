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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.cloud.RestorableState;
import com.google.cloud.ServiceOptions;
import com.google.cloud.storage.spi.StorageRpcFactory;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteOperationRequest;
import com.google.cloud.storage.spi.v1.StorageRpcClient.RewriteResult;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CopyWriterTest {

  private static final String SOURCE_BUCKET_NAME = "b";
  private static final String SOURCE_BLOB_NAME = "n";
  private static final String DESTINATION_BUCKET_NAME = "b1";
  private static final String DESTINATION_BLOB_NAME = "n1";
  private static final BlobId BLOB_ID = BlobId.from(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME);
  private static final BlobMetadata BLOB_INFO =
      BlobMetadata.newBuilder(DESTINATION_BUCKET_NAME, DESTINATION_BLOB_NAME).buildObject();
  private static final BlobMetadata RESULT_INFO =
      BlobMetadata.newBuilder(DESTINATION_BUCKET_NAME, DESTINATION_BLOB_NAME)
          .setContentType("type")
          .buildObject();
  private static final Map<StorageRpcClient.StorageOption, ?> EMPTY_OPTIONS = ImmutableMap.of();
  private static final RewriteOperationRequest REQUEST_WITH_OBJECT =
      new RewriteOperationRequest(
          BLOB_ID.toProto(), EMPTY_OPTIONS, true, BLOB_INFO.toProto(), EMPTY_OPTIONS, null);
  private static final RewriteOperationRequest REQUEST_WITHOUT_OBJECT =
      new RewriteOperationRequest(
          BLOB_ID.toProto(), EMPTY_OPTIONS, false, BLOB_INFO.toProto(), EMPTY_OPTIONS, null);
  private static final RewriteResult RESPONSE_WITH_OBJECT =
      new RewriteResult(REQUEST_WITH_OBJECT, null, 42L, false, "token", 21L);
  private static final StorageRpcClient.RewriteResult RESPONSE_WITHOUT_OBJECT =
      new RewriteResult(REQUEST_WITHOUT_OBJECT, null, 42L, false, "token", 21L);
  private static final RewriteResult RESPONSE_WITH_OBJECT_DONE =
      new RewriteResult(REQUEST_WITH_OBJECT, RESULT_INFO.toProto(), 42L, true, "token", 42L);
  private static final RewriteResult RESPONSE_WITHOUT_OBJECT_DONE =
      new RewriteResult(REQUEST_WITHOUT_OBJECT, RESULT_INFO.toProto(), 42L, true, "token", 42L);

  private StorageClientOptions options;
  private StorageRpcFactory rpcFactoryMock;
  private StorageRpcClient storageRpcMock;
  private ObjectCopyWriter copyWriter;
  private StorageObject result;

  @Before
  public void setUp() {
    rpcFactoryMock = createMock(StorageRpcFactory.class);
    storageRpcMock = createMock(StorageRpcClient.class);
    expect(rpcFactoryMock.create(anyObject(StorageClientOptions.class))).andReturn(storageRpcMock);
    replay(rpcFactoryMock);
    options =
        StorageClientOptions.newStorageClientBuilder()
            .setProjectId("projectid")
            .setServiceRpcFactory(rpcFactoryMock)
            .setRetrySettings(ServiceOptions.getNoRetrySettings())
            .build();
    result = new StorageObject(options.getService(), new BlobMetadata.BlobInfoBuilderImpl(RESULT_INFO));
  }

  @After
  public void tearDown() throws Exception {
    verify(rpcFactoryMock, storageRpcMock);
  }

  @Test
  public void testRewriteWithObject() {
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITH_OBJECT))
        .andReturn(RESPONSE_WITH_OBJECT_DONE);
    EasyMock.replay(storageRpcMock);
    copyWriter = new ObjectCopyWriter(options, RESPONSE_WITH_OBJECT);
    assertEquals(result, copyWriter.getResult());
    assertTrue(copyWriter.isDone());
    assertEquals(42L, copyWriter.getTotalBytesCopied());
    assertEquals(42L, copyWriter.getBlobSize());
  }

  @Test
  public void testRewriteWithoutObject() {
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITHOUT_OBJECT))
        .andReturn(RESPONSE_WITHOUT_OBJECT_DONE);
    EasyMock.replay(storageRpcMock);
    copyWriter = new ObjectCopyWriter(options, RESPONSE_WITHOUT_OBJECT);
    assertEquals(result, copyWriter.getResult());
    assertTrue(copyWriter.isDone());
    assertEquals(42L, copyWriter.getTotalBytesCopied());
    assertEquals(42L, copyWriter.getBlobSize());
  }

  @Test
  public void testRewriteWithObjectMultipleRequests() {
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITH_OBJECT))
        .andReturn(RESPONSE_WITH_OBJECT);
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITH_OBJECT))
        .andReturn(RESPONSE_WITH_OBJECT_DONE);
    EasyMock.replay(storageRpcMock);
    copyWriter = new ObjectCopyWriter(options, RESPONSE_WITH_OBJECT);
    assertEquals(result, copyWriter.getResult());
    assertTrue(copyWriter.isDone());
    assertEquals(42L, copyWriter.getTotalBytesCopied());
    assertEquals(42L, copyWriter.getBlobSize());
  }

  @Test
  public void testRewriteWithoutObjectMultipleRequests() {
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITHOUT_OBJECT))
        .andReturn(RESPONSE_WITHOUT_OBJECT);
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITHOUT_OBJECT))
        .andReturn(RESPONSE_WITHOUT_OBJECT_DONE);
    EasyMock.replay(storageRpcMock);
    copyWriter = new ObjectCopyWriter(options, RESPONSE_WITHOUT_OBJECT);
    assertEquals(result, copyWriter.getResult());
    assertTrue(copyWriter.isDone());
    assertEquals(42L, copyWriter.getTotalBytesCopied());
    assertEquals(42L, copyWriter.getBlobSize());
  }

  @Test
  public void testSaveAndRestoreWithObject() {
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITH_OBJECT))
        .andReturn(RESPONSE_WITH_OBJECT);
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITH_OBJECT))
        .andReturn(RESPONSE_WITH_OBJECT_DONE);
    EasyMock.replay(storageRpcMock);
    copyWriter = new ObjectCopyWriter(options, RESPONSE_WITH_OBJECT);
    copyWriter.copySegment();
    assertTrue(!copyWriter.isDone());
    assertEquals(21L, copyWriter.getTotalBytesCopied());
    assertEquals(42L, copyWriter.getBlobSize());
    RestorableState<ObjectCopyWriter> rewriterState = copyWriter.capture();
    ObjectCopyWriter restoredRewriter = rewriterState.restore();
    assertEquals(result, restoredRewriter.getResult());
    assertTrue(restoredRewriter.isDone());
    assertEquals(42L, restoredRewriter.getTotalBytesCopied());
    assertEquals(42L, restoredRewriter.getBlobSize());
  }

  @Test
  public void testSaveAndRestoreWithoutObject() {
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITHOUT_OBJECT))
        .andReturn(RESPONSE_WITHOUT_OBJECT);
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITHOUT_OBJECT))
        .andReturn(RESPONSE_WITHOUT_OBJECT_DONE);
    EasyMock.replay(storageRpcMock);
    copyWriter = new ObjectCopyWriter(options, RESPONSE_WITHOUT_OBJECT);
    copyWriter.copySegment();
    assertTrue(!copyWriter.isDone());
    assertEquals(21L, copyWriter.getTotalBytesCopied());
    assertEquals(42L, copyWriter.getBlobSize());
    RestorableState<ObjectCopyWriter> rewriterState = copyWriter.capture();
    ObjectCopyWriter restoredRewriter = rewriterState.restore();
    assertEquals(result, restoredRewriter.getResult());
    assertTrue(restoredRewriter.isDone());
    assertEquals(42L, restoredRewriter.getTotalBytesCopied());
    assertEquals(42L, restoredRewriter.getBlobSize());
  }

  @Test
  public void testSaveAndRestoreWithResult() {
    EasyMock.expect(storageRpcMock.continueRewrite(RESPONSE_WITH_OBJECT))
        .andReturn(RESPONSE_WITH_OBJECT_DONE);
    EasyMock.replay(storageRpcMock);
    copyWriter = new ObjectCopyWriter(options, RESPONSE_WITH_OBJECT);
    copyWriter.copySegment();
    assertEquals(result, copyWriter.getResult());
    assertTrue(copyWriter.isDone());
    assertEquals(42L, copyWriter.getTotalBytesCopied());
    assertEquals(42L, copyWriter.getBlobSize());
    RestorableState<ObjectCopyWriter> rewriterState = copyWriter.capture();
    ObjectCopyWriter restoredRewriter = rewriterState.restore();
    assertEquals(result, restoredRewriter.getResult());
    assertTrue(restoredRewriter.isDone());
    assertEquals(42L, restoredRewriter.getTotalBytesCopied());
    assertEquals(42L, restoredRewriter.getBlobSize());
  }
}
