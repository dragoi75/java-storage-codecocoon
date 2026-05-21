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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.cloud.ReadChannel;
import com.google.cloud.RestorableState;
import com.google.cloud.Tuple;
import com.google.cloud.storage.spi.StorageRpcProvider;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BlobReadChannelTest {

  private static final String BUCKET_NAME = "b";
  private static final String BLOB_NAME = "n";
  private static final BlobIdentifier BLOB_ID = BlobIdentifier.from(BUCKET_NAME, BLOB_NAME, -1L);
  private static final Map<StorageRpcClient.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();
  private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;
  private static final int CUSTOM_CHUNK_SIZE = 2 * 1024 * 1024;
  private static final Random RANDOM = new Random();

  private StorageClientOptions options;
  private StorageRpcProvider rpcFactoryMock;
  private StorageRpcClient storageRpcMock;
  private BlobReadStream reader;

  @Before
  public void setUp() {
    rpcFactoryMock = createMock(StorageRpcProvider.class);
    storageRpcMock = createMock(StorageRpcClient.class);
    expect(rpcFactoryMock.create(anyObject(StorageClientOptions.class))).andReturn(storageRpcMock);
    replay(rpcFactoryMock);
    options =
        StorageClientOptions.newServiceClientBuilder()
            .setProjectId("projectId")
            .setServiceRpcFactory(rpcFactoryMock)
            .build();
  }

  @After
  public void tearDown() {
    verify(rpcFactoryMock, storageRpcMock);
  }

  @Test
  public void testCreate() {
    replay(storageRpcMock);
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    assertTrue(reader.isOpen());
  }

  @Test
  public void testReadBuffered() throws IOException {
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    byte[] result = randomByteArray(DEFAULT_CHUNK_SIZE);
    ByteBuffer firstReadBuffer = ByteBuffer.allocate(42);
    ByteBuffer secondReadBuffer = ByteBuffer.allocate(42);
    expect(storageRpcMock.read(BLOB_ID.toProto(), EMPTY_RPC_OPTIONS, 0, DEFAULT_CHUNK_SIZE))
        .andReturn(Tuple.of("etag", result));
    replay(storageRpcMock);
    reader.read(firstReadBuffer);
    reader.read(secondReadBuffer);
    assertArrayEquals(Arrays.copyOf(result, firstReadBuffer.capacity()), firstReadBuffer.array());
    assertArrayEquals(
        Arrays.copyOfRange(
            result,
            firstReadBuffer.capacity(),
            firstReadBuffer.capacity() + secondReadBuffer.capacity()),
        secondReadBuffer.array());
  }

  @Test
  public void testReadBig() throws IOException {
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    reader.setChunkSize(CUSTOM_CHUNK_SIZE);
    byte[] firstResult = randomByteArray(DEFAULT_CHUNK_SIZE);
    byte[] secondResult = randomByteArray(DEFAULT_CHUNK_SIZE);
    ByteBuffer firstReadBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE);
    ByteBuffer secondReadBuffer = ByteBuffer.allocate(42);
    expect(storageRpcMock.read(BLOB_ID.toProto(), EMPTY_RPC_OPTIONS, 0, DEFAULT_CHUNK_SIZE))
        .andReturn(Tuple.of("etag", firstResult));
    expect(
            storageRpcMock.read(
                BLOB_ID.toProto(), EMPTY_RPC_OPTIONS, DEFAULT_CHUNK_SIZE, CUSTOM_CHUNK_SIZE))
        .andReturn(Tuple.of("etag", secondResult));
    replay(storageRpcMock);
    reader.read(firstReadBuffer);
    reader.read(secondReadBuffer);
    assertArrayEquals(firstResult, firstReadBuffer.array());
    assertArrayEquals(
        Arrays.copyOf(secondResult, secondReadBuffer.capacity()), secondReadBuffer.array());
  }

  @Test
  public void testReadFinish() throws IOException {
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    byte[] result = {};
    ByteBuffer readBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE);
    expect(storageRpcMock.read(BLOB_ID.toProto(), EMPTY_RPC_OPTIONS, 0, DEFAULT_CHUNK_SIZE))
        .andReturn(Tuple.of("etag", result));
    replay(storageRpcMock);
    assertEquals(-1, reader.read(readBuffer));
  }

  @Test
  public void testSeek() throws IOException {
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    reader.seek(42);
    byte[] result = randomByteArray(DEFAULT_CHUNK_SIZE);
    ByteBuffer readBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE);
    expect(storageRpcMock.read(BLOB_ID.toProto(), EMPTY_RPC_OPTIONS, 42, DEFAULT_CHUNK_SIZE))
        .andReturn(Tuple.of("etag", result));
    replay(storageRpcMock);
    reader.read(readBuffer);
    assertArrayEquals(result, readBuffer.array());
  }

  @Test
  public void testClose() {
    replay(storageRpcMock);
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    assertTrue(reader.isOpen());
    reader.close();
    assertFalse(reader.isOpen());
  }

  @Test
  public void testReadClosed() throws IOException {
    replay(storageRpcMock);
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    reader.close();
    try {
      ByteBuffer readBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE);
      reader.read(readBuffer);
      fail("Expected BlobReadChannel read to throw ClosedChannelException");
    } catch (ClosedChannelException ex) {
      // expected
    }
  }

  @Test
  public void testReadGenerationChanged() throws IOException {
    BlobIdentifier blobId = BlobIdentifier.from(BUCKET_NAME, BLOB_NAME);
    reader = new BlobReadStream(options, blobId, EMPTY_RPC_OPTIONS);
    byte[] firstResult = randomByteArray(DEFAULT_CHUNK_SIZE);
    byte[] secondResult = randomByteArray(DEFAULT_CHUNK_SIZE);
    ByteBuffer firstReadBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE);
    ByteBuffer secondReadBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE);
    expect(storageRpcMock.read(blobId.toProto(), EMPTY_RPC_OPTIONS, 0, DEFAULT_CHUNK_SIZE))
        .andReturn(Tuple.of("etag1", firstResult));
    expect(
            storageRpcMock.read(
                blobId.toProto(), EMPTY_RPC_OPTIONS, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_SIZE))
        .andReturn(Tuple.of("etag2", secondResult));
    replay(storageRpcMock);
    reader.read(firstReadBuffer);
    try {
      reader.read(secondReadBuffer);
      fail("Expected ReadChannel read to throw StorageException");
    } catch (IOException ex) {
      StringBuilder messageBuilder = new StringBuilder();
      messageBuilder.append("Blob ").append(blobId).append(" was updated while reading");
      assertEquals(messageBuilder.toString(), ex.getMessage());
    }
  }

  @Test
  public void testSaveAndRestore() throws IOException {
    byte[] firstResult = randomByteArray(DEFAULT_CHUNK_SIZE);
    byte[] secondResult = randomByteArray(DEFAULT_CHUNK_SIZE);
    ByteBuffer firstReadBuffer = ByteBuffer.allocate(42);
    ByteBuffer secondReadBuffer = ByteBuffer.allocate(DEFAULT_CHUNK_SIZE);
    expect(storageRpcMock.read(BLOB_ID.toProto(), EMPTY_RPC_OPTIONS, 0, DEFAULT_CHUNK_SIZE))
        .andReturn(Tuple.of("etag", firstResult));
    expect(storageRpcMock.read(BLOB_ID.toProto(), EMPTY_RPC_OPTIONS, 42, DEFAULT_CHUNK_SIZE))
        .andReturn(Tuple.of("etag", secondResult));
    replay(storageRpcMock);
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    reader.read(firstReadBuffer);
    RestorableState<ReadChannel> readerState = reader.capture();
    ReadChannel restoredReader = readerState.restore();
    restoredReader.read(secondReadBuffer);
    assertArrayEquals(
        Arrays.copyOf(firstResult, firstReadBuffer.capacity()), firstReadBuffer.array());
    assertArrayEquals(secondResult, secondReadBuffer.array());
  }

  @Test
  public void testStateEquals() {
    replay(storageRpcMock);
    reader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    @SuppressWarnings("resource") // avoid closing when you don't want partial writes to GCS
    ReadChannel secondReader = new BlobReadStream(options, BLOB_ID, EMPTY_RPC_OPTIONS);
    RestorableState<ReadChannel> state = reader.capture();
    RestorableState<ReadChannel> secondState = secondReader.capture();
    assertEquals(state, secondState);
    assertEquals(state.hashCode(), secondState.hashCode());
    assertEquals(state.toString(), secondState.toString());
  }

  private static byte[] randomByteArray(int size) {
    byte[] byteArray = new byte[size];
    RANDOM.nextBytes(byteArray);
    return byteArray;
  }
}
