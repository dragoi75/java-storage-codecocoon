/*
 * Copyright 2016 Google LLC
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.storage.spi.v1.RpcRequestBatch;
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class StorageBatchTest {

  private static final BlobIdentifier BLOB_ID = BlobIdentifier.create("b1", "n1");
  private static final BlobIdentifier BLOB_ID_COMPLETE = BlobIdentifier.create("b1", "n1", 42L);
  private static final BlobMetadata BLOB_INFO = BlobMetadata.newBuilder(BLOB_ID).buildObject();
  private static final BlobMetadata BLOB_INFO_COMPLETE =
      BlobMetadata.newBuilder(BLOB_ID_COMPLETE).setMetageneration(42L).buildObject();
  private static final CloudStorageClient.BlobGetOptions[] BLOB_GET_OPTIONS = {
    CloudStorageClient.BlobGetOptions.ifGenerationMatch(42L), CloudStorageClient.BlobGetOptions.ifMetagenerationMatch(42L)
  };
  private static final CloudStorageClient.BlobReadOption[] BLOB_SOURCE_OPTIONS = {
    CloudStorageClient.BlobReadOption.ifGenerationMatch(42L), CloudStorageClient.BlobReadOption.ifMetagenerationMatch(42L)
  };
  private static final CloudStorageClient.BlobUploadOptions[] BLOB_TARGET_OPTIONS = {
    CloudStorageClient.BlobUploadOptions.ifGenerationMatch(), CloudStorageClient.BlobUploadOptions.ifMetagenerationMatch()
  };
  private static final GoogleJsonError GOOGLE_JSON_ERROR = new GoogleJsonError();

  private StorageClientOptions optionsMock;
  private StorageServiceRpc storageRpcMock;
  private RpcRequestBatch batchMock;
  private StorageOperationBatch storageBatch;
  private final CloudStorageClient storage = EasyMock.createStrictMock(CloudStorageClient.class);

  @Before
  public void setUp() {
    optionsMock = EasyMock.createMock(StorageClientOptions.class);
    storageRpcMock = EasyMock.createMock(StorageServiceRpc.class);
    batchMock = EasyMock.createMock(RpcRequestBatch.class);
    EasyMock.expect(optionsMock.getStorageRpcV1()).andReturn(storageRpcMock);
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    EasyMock.replay(optionsMock, storageRpcMock, batchMock, storage);
    storageBatch = new StorageOperationBatch(optionsMock);
  }

  @After
  public void tearDown() {
    EasyMock.verify(batchMock, storageRpcMock, optionsMock, storage);
  }

  @Test
  public void testConstructor() {
    assertSame(batchMock, storageBatch.getBatch());
    assertSame(optionsMock, storageBatch.getOptions());
    assertSame(storageRpcMock, storageBatch.getStorageRpc());
  }

  @Test
  public void testDelete() {
    EasyMock.reset(batchMock);
    Capture<RpcRequestBatch.CompletionHandler<Void>> callback = Capture.newInstance();
    batchMock.addDeleteRequest(
        EasyMock.eq(BLOB_INFO.toProto()),
        EasyMock.capture(callback),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.replay(batchMock);
    StorageBatchResult<Boolean> batchResult =
        storageBatch.remove(BLOB_ID.getBucket(), BLOB_ID.getName());
    assertNotNull(callback.getValue());
    try {
      batchResult.get();
      fail("No result available yet.");
    } catch (IllegalStateException ex) {
      // expected
    }
    // testing error here, success is tested with options
    RpcRequestBatch.CompletionHandler<Void> capturedCallback = callback.getValue();
    capturedCallback.handleFailure(GOOGLE_JSON_ERROR);
    try {
      batchResult.get();
      fail("Should throw a StorageExcetion on error.");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testDeleteWithOptions() {
    EasyMock.reset(batchMock);
    Capture<RpcRequestBatch.CompletionHandler<Void>> callback = Capture.newInstance();
    Capture<Map<StorageServiceRpc.StorageOption, Object>> capturedOptions = Capture.newInstance();
    batchMock.addDeleteRequest(
        EasyMock.eq(BLOB_INFO.toProto()),
        EasyMock.capture(callback),
        EasyMock.capture(capturedOptions));
    EasyMock.replay(batchMock);
    StorageBatchResult<Boolean> batchResult = storageBatch.remove(BLOB_ID, BLOB_SOURCE_OPTIONS);
    assertNotNull(callback.getValue());
    assertEquals(2, capturedOptions.getValue().size());
    for (CloudStorageClient.BlobReadOption option : BLOB_SOURCE_OPTIONS) {
      assertEquals(option.getValue(), capturedOptions.getValue().get(option.getRpcOption()));
    }
    RpcRequestBatch.CompletionHandler<Void> capturedCallback = callback.getValue();
    capturedCallback.handleSuccess(null);
    assertTrue(batchResult.get());
  }

  @Test
  public void testUpdate() {
    EasyMock.reset(batchMock);
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback = Capture.newInstance();
    batchMock.addPatchRequest(
        EasyMock.eq(BLOB_INFO.toProto()),
        EasyMock.capture(callback),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.replay(batchMock);
    StorageBatchResult<StorageBlob> batchResult = storageBatch.patch(BLOB_INFO);
    assertNotNull(callback.getValue());
    try {
      batchResult.get();
      fail("No result available yet.");
    } catch (IllegalStateException ex) {
      // expected
    }
    // testing error here, success is tested with options
    RpcRequestBatch.CompletionHandler<StorageObject> capturedCallback = callback.getValue();
    capturedCallback.handleFailure(GOOGLE_JSON_ERROR);
    try {
      batchResult.get();
      fail("Should throw a StorageExcetion on error.");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testUpdateWithOptions() {
    EasyMock.reset(storage, batchMock, optionsMock);
    EasyMock.expect(storage.getOptions()).andReturn(optionsMock).times(2);
    EasyMock.expect(optionsMock.getService()).andReturn(storage);
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback = Capture.newInstance();
    Capture<Map<StorageServiceRpc.StorageOption, Object>> capturedOptions = Capture.newInstance();
    batchMock.addPatchRequest(
        EasyMock.eq(BLOB_INFO_COMPLETE.toProto()),
        EasyMock.capture(callback),
        EasyMock.capture(capturedOptions));
    EasyMock.replay(batchMock, storage, optionsMock);
    StorageBatchResult<StorageBlob> batchResult =
        storageBatch.patch(BLOB_INFO_COMPLETE, BLOB_TARGET_OPTIONS);
    assertNotNull(callback.getValue());
    assertEquals(2, capturedOptions.getValue().size());
    assertEquals(42L, capturedOptions.getValue().get(BLOB_TARGET_OPTIONS[0].getRpcOption()));
    assertEquals(42L, capturedOptions.getValue().get(BLOB_TARGET_OPTIONS[1].getRpcOption()));
    RpcRequestBatch.CompletionHandler<StorageObject> capturedCallback = callback.getValue();
    capturedCallback.handleSuccess(BLOB_INFO.toProto());
    assertEquals(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO)), batchResult.get());
  }

  @Test
  public void testGet() {
    EasyMock.reset(batchMock);
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback = Capture.newInstance();
    batchMock.addGetRequest(
        EasyMock.eq(BLOB_INFO.toProto()),
        EasyMock.capture(callback),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.replay(batchMock);
    StorageBatchResult<StorageBlob> batchResult = storageBatch.get(BLOB_ID.getBucket(), BLOB_ID.getName());
    assertNotNull(callback.getValue());
    try {
      batchResult.get();
      fail("No result available yet.");
    } catch (IllegalStateException ex) {
      // expected
    }
    // testing error here, success is tested with options
    RpcRequestBatch.CompletionHandler<StorageObject> capturedCallback = callback.getValue();
    capturedCallback.handleFailure(GOOGLE_JSON_ERROR);
    try {
      batchResult.get();
      fail("Should throw a StorageExcetion on error.");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testGetWithOptions() {
    EasyMock.reset(storage, batchMock, optionsMock);
    EasyMock.expect(storage.getOptions()).andReturn(optionsMock).times(2);
    EasyMock.expect(optionsMock.getService()).andReturn(storage);
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback = Capture.newInstance();
    Capture<Map<StorageServiceRpc.StorageOption, Object>> capturedOptions = Capture.newInstance();
    batchMock.addGetRequest(
        EasyMock.eq(BLOB_INFO.toProto()),
        EasyMock.capture(callback),
        EasyMock.capture(capturedOptions));
    EasyMock.replay(storage, batchMock, optionsMock);
    StorageBatchResult<StorageBlob> batchResult = storageBatch.get(BLOB_ID, BLOB_GET_OPTIONS);
    assertNotNull(callback.getValue());
    assertEquals(2, capturedOptions.getValue().size());
    for (CloudStorageClient.BlobGetOptions option : BLOB_GET_OPTIONS) {
      assertEquals(option.getValue(), capturedOptions.getValue().get(option.getRpcOption()));
    }
    RpcRequestBatch.CompletionHandler<StorageObject> capturedCallback = callback.getValue();
    capturedCallback.handleSuccess(BLOB_INFO.toProto());
    assertEquals(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO)), batchResult.get());
  }
}
