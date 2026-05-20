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
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.api.core.ApiClock;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.AccessControlEntry.ProjectEntity.ProjectMemberRole;
import com.google.cloud.storage.AccessControlEntry.UserPrincipal;
import com.google.cloud.storage.StorageClient.BlobWriteSetting;
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import java.io.File;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.security.Key;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.easymock.Capture;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BlobTest {

  private static final AccessControlEntry ACL = AccessControlEntry.create(UserPrincipal.allAuthenticatedUsers(), AccessControlEntry.AccessRole.OWNER);
  private static final AccessControlEntry OTHER_ACL = AccessControlEntry.create(new AccessControlEntry.ProjectEntity(ProjectMemberRole.OWNERS, "p"), AccessControlEntry.AccessRole.READER);
  private static final List<AccessControlEntry> ACLS = ImmutableList.of(ACL, OTHER_ACL);
  private static final Integer COMPONENT_COUNT = 2;
  private static final String CONTENT_TYPE = "text/html";
  private static final String CACHE_CONTROL = "cache";
  private static final String CONTENT_DISPOSITION = "content-disposition";
  private static final String CONTENT_ENCODING = "UTF-8";
  private static final String CONTENT_LANGUAGE = "En";
  private static final String CRC32 = "FF00";
  private static final String CRC32_HEX_STRING = "145d34";
  private static final Long DELETE_TIME = System.currentTimeMillis();
  private static final String ETAG = "0xFF00";
  private static final Long GENERATION = 1L;
  private static final String GENERATED_ID = "B/N:1";
  private static final String MD5 = "FF00";
  private static final String MD5_HEX_STRING = "145d34";
  private static final String MEDIA_LINK = "http://media/b/n";
  private static final Map<String, String> METADATA = ImmutableMap.of("n1", "v1", "n2", "v2");
  private static final Long META_GENERATION = 10L;
  private static final UserPrincipal OWNER = new UserPrincipal("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long SIZE = 1024L;
  private static final Long UPDATE_TIME = DELETE_TIME - 1L;
  private static final Long CREATE_TIME = UPDATE_TIME - 1L;
  private static final String ENCRYPTION_ALGORITHM = "AES256";
  private static final String KEY_SHA256 = "keySha";
  private static final BlobMetadata.CustomerEncryptionInfo CUSTOMER_ENCRYPTION =
      new BlobMetadata.CustomerEncryptionInfo(ENCRYPTION_ALGORITHM, KEY_SHA256);
  private static final String KMS_KEY_NAME =
      "projects/p/locations/kr-loc/keyRings/kr/cryptoKeys/key";
  private static final Boolean EVENT_BASED_HOLD = true;
  private static final Boolean TEMPORARY_HOLD = true;
  private static final Long RETENTION_EXPIRATION_TIME = 10L;
  private static final BlobMetadata FULL_BLOB_INFO =
      BlobMetadata.newBuilder("b", "n", GENERATION)
          .setAcl(ACLS)
          .setComponentCount(COMPONENT_COUNT)
          .setContentType(CONTENT_TYPE)
          .setCacheControl(CACHE_CONTROL)
          .setContentDisposition(CONTENT_DISPOSITION)
          .setContentEncoding(CONTENT_ENCODING)
          .setContentLanguage(CONTENT_LANGUAGE)
          .setCrc32c(CRC32)
          .setDeleteTime(DELETE_TIME)
          .setEtag(ETAG)
          .setGeneratedId(GENERATED_ID)
          .setMd5(MD5)
          .setMediaLink(MEDIA_LINK)
          .setMetadata(METADATA)
          .setMetageneration(META_GENERATION)
          .setOwner(OWNER)
          .setSelfLink(SELF_LINK)
          .setSize(SIZE)
          .setUpdateTime(UPDATE_TIME)
          .setCreateTime(CREATE_TIME)
          .setCustomerEncryption(CUSTOMER_ENCRYPTION)
          .setKmsKeyName(KMS_KEY_NAME)
          .setEventBasedHold(EVENT_BASED_HOLD)
          .setTemporaryHold(TEMPORARY_HOLD)
          .setRetentionExpirationTime(RETENTION_EXPIRATION_TIME)
          .buildStorageObject();
  private static final BlobMetadata BLOB_INFO =
      BlobMetadata.newBuilder("b", "n").setMetageneration(42L).buildStorageObject();
  private static final BlobMetadata DIRECTORY_INFO =
      BlobMetadata.newBuilder("b", "n/").setSize(0L).setIsDirectory(true).buildStorageObject();
  private static final String BASE64_KEY = "JVzfVl8NLD9FjedFuStegjRfES5ll5zc59CIXw572OA=";
  private static final Key KEY =
      new SecretKeySpec(BaseEncoding.base64().decode(BASE64_KEY), "AES256");

  // This retrying setting is used by test testDownloadWithRetries. This unit test is setup
  // to write one byte and then throw retryable exception, it then writes another bytes on
  // second call succeeds.
  private static final RetrySettings RETRY_SETTINGS =
      RetrySettings.newBuilder().setMaxAttempts(2).build();
  private static final ApiClock API_CLOCK =
      new ApiClock() {
        @Override
        public long nanoTime() {
          return 42_000_000_000L;
        }

        @Override
        public long millisTime() {
          return 42_000L;
        }
      };

  private StorageClient storage;
  private StorageBlob blob;
  private StorageBlob expectedBlob;
  private StorageClient serviceMockReturnsOptions = createMock(StorageClient.class);
  private StorageSettings mockOptions = createMock(StorageSettings.class);

  @Before
  public void setUp() {
    storage = createStrictMock(StorageClient.class);
  }

  @After
  public void tearDown() throws Exception {
    verify(storage);
  }

  private void initializeExpectedBlob(int optionsCalls) {
    expect(serviceMockReturnsOptions.getOptions()).andReturn(mockOptions).times(optionsCalls);
    replay(serviceMockReturnsOptions);
    expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
  }

  private void initializeBlob() {
    blob = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
  }

  @Test
  public void testExists_True() throws Exception {
    initializeExpectedBlob(1);
    StorageClient.BlobGetOptions[] expectedOptions = {StorageClient.BlobGetOptions.fieldsSelector()};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(expectedBlob.getBlobId(), expectedOptions)).andReturn(expectedBlob);
    replay(storage);
    initializeBlob();
    assertTrue(blob.existsInStorage());
  }

  @Test
  public void testExists_False() throws Exception {
    StorageClient.BlobGetOptions[] expectedOptions = {StorageClient.BlobGetOptions.fieldsSelector()};
    expect(storage.getOptions()).andReturn(null);
    expect(storage.get(BLOB_INFO.getBlobId(), expectedOptions)).andReturn(null);
    replay(storage);
    initializeBlob();
    assertFalse(blob.existsInStorage());
  }

  @Test
  public void testContent() throws Exception {
    initializeExpectedBlob(2);
    byte[] content = {1, 2};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.readAllBytes(BLOB_INFO.getBlobId())).andReturn(content);
    replay(storage);
    initializeBlob();
    assertArrayEquals(content, blob.getContent());
  }

  @Test
  public void testContentWithDecryptionKey() throws Exception {
    initializeExpectedBlob(2);
    byte[] content = {1, 2};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.readAllBytes(
                BLOB_INFO.getBlobId(), StorageClient.BlobSourceOptions.decryptionKeyBase64(BASE64_KEY)))
        .andReturn(content)
        .times(2);
    replay(storage);
    initializeBlob();
    assertArrayEquals(content, blob.getContent(StorageBlob.BlobSourceOptions.withDecryptionKey(BASE64_KEY)));
    assertArrayEquals(content, blob.getContent(StorageBlob.BlobSourceOptions.withDecryptionKey(KEY)));
  }

  @Test
  public void testReload() throws Exception {
    initializeExpectedBlob(2);
    StorageBlob expectedReloadedBlob = expectedBlob.toBlobInfoBuilder().setCacheControl("c").buildStorageObject();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BLOB_INFO.getBlobId(), new StorageClient.BlobGetOptions[0]))
        .andReturn(expectedReloadedBlob);
    replay(storage);
    initializeBlob();
    StorageBlob updatedBlob = blob.reloadFromStorage();
    assertEquals(expectedReloadedBlob, updatedBlob);
  }

  @Test
  public void testReloadNull() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BLOB_INFO.getBlobId(), new StorageClient.BlobGetOptions[0])).andReturn(null);
    replay(storage);
    initializeBlob();
    StorageBlob reloadedBlob = blob.reloadFromStorage();
    assertNull(reloadedBlob);
  }

  @Test
  public void testReloadWithOptions() throws Exception {
    initializeExpectedBlob(2);
    StorageBlob expectedReloadedBlob = expectedBlob.toBlobInfoBuilder().setCacheControl("c").buildStorageObject();
    StorageClient.BlobGetOptions[] options = {StorageClient.BlobGetOptions.ifMetagenerationMatch(42L)};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BLOB_INFO.getBlobId(), options)).andReturn(expectedReloadedBlob);
    replay(storage);
    initializeBlob();
    StorageBlob updatedBlob = blob.reloadFromStorage(StorageBlob.BlobSourceOptions.withMetagenerationMatch());
    assertEquals(expectedReloadedBlob, updatedBlob);
  }

  @Test
  public void testUpdate() throws Exception {
    initializeExpectedBlob(2);
    StorageBlob expectedUpdatedBlob = expectedBlob.toBlobInfoBuilder().setCacheControl("c").buildStorageObject();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(eq(expectedUpdatedBlob), new StorageClient.BlobUploadOption[0]))
        .andReturn(expectedUpdatedBlob);
    replay(storage);
    initializeBlob();
    StorageBlob updatedBlob = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(expectedUpdatedBlob));
    StorageBlob actualUpdatedBlob = updatedBlob.updateBlob();
    assertEquals(expectedUpdatedBlob, actualUpdatedBlob);
  }

  @Test
  public void testDelete() throws Exception {
    initializeExpectedBlob(2);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.delete(BLOB_INFO.getBlobId(), new StorageClient.BlobSourceOptions[0])).andReturn(true);
    replay(storage);
    initializeBlob();
    assertTrue(blob.deleteBlob());
  }

  @Test
  public void testCopyToBucket() throws Exception {
    initializeExpectedBlob(2);
    BlobMetadata target = BlobMetadata.newBuilder(BlobIdentifier.create("bt", "n")).buildStorageObject();
    BlobCopyWriter copyWriter = createMock(BlobCopyWriter.class);
    Capture<StorageClient.CopyOperationRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    BlobCopyWriter returnedCopyWriter = blob.copyToTarget("bt");
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(capturedCopyRequest.getValue().getSource(), blob.getBlobId());
    assertEquals(capturedCopyRequest.getValue().getTarget(), target);
    assertFalse(capturedCopyRequest.getValue().getOverrideInfo());
    assertTrue(capturedCopyRequest.getValue().getSourceOptions().isEmpty());
    assertTrue(capturedCopyRequest.getValue().getTargetOptions().isEmpty());
  }

  @Test
  public void testCopyTo() throws Exception {
    initializeExpectedBlob(2);
    BlobMetadata target = BlobMetadata.newBuilder(BlobIdentifier.create("bt", "nt")).buildStorageObject();
    BlobCopyWriter copyWriter = createMock(BlobCopyWriter.class);
    Capture<StorageClient.CopyOperationRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    BlobCopyWriter returnedCopyWriter = blob.copyToTarget("bt", "nt");
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(capturedCopyRequest.getValue().getSource(), blob.getBlobId());
    assertEquals(capturedCopyRequest.getValue().getTarget(), target);
    assertFalse(capturedCopyRequest.getValue().getOverrideInfo());
    assertTrue(capturedCopyRequest.getValue().getSourceOptions().isEmpty());
    assertTrue(capturedCopyRequest.getValue().getTargetOptions().isEmpty());
  }

  @Test
  public void testCopyToBlobId() throws Exception {
    initializeExpectedBlob(2);
    BlobMetadata target = BlobMetadata.newBuilder(BlobIdentifier.create("bt", "nt")).buildStorageObject();
    BlobIdentifier targetId = BlobIdentifier.create("bt", "nt");
    BlobCopyWriter copyWriter = createMock(BlobCopyWriter.class);
    Capture<StorageClient.CopyOperationRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    BlobCopyWriter returnedCopyWriter = blob.copyToTarget(targetId);
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(capturedCopyRequest.getValue().getSource(), blob.getBlobId());
    assertEquals(capturedCopyRequest.getValue().getTarget(), target);
    assertFalse(capturedCopyRequest.getValue().getOverrideInfo());
    assertTrue(capturedCopyRequest.getValue().getSourceOptions().isEmpty());
    assertTrue(capturedCopyRequest.getValue().getTargetOptions().isEmpty());
  }

  @Test
  public void testReader() throws Exception {
    initializeExpectedBlob(2);
    ReadChannel channel = createMock(ReadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.reader(BLOB_INFO.getBlobId())).andReturn(channel);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.openReader());
  }

  @Test
  public void testReaderWithDecryptionKey() throws Exception {
    initializeExpectedBlob(2);
    ReadChannel channel = createMock(ReadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.reader(
                BLOB_INFO.getBlobId(), StorageClient.BlobSourceOptions.decryptionKeyBase64(BASE64_KEY)))
        .andReturn(channel)
        .times(2);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.openReader(StorageBlob.BlobSourceOptions.withDecryptionKey(BASE64_KEY)));
    assertSame(channel, blob.openReader(StorageBlob.BlobSourceOptions.withDecryptionKey(KEY)));
  }

  @Test
  public void testWriter() throws Exception {
    initializeExpectedBlob(2);
    BlobUploadChannel channel = createMock(BlobUploadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.writer(eq(expectedBlob))).andReturn(channel);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.openWriter());
  }

  @Test
  public void testWriterWithEncryptionKey() throws Exception {
    initializeExpectedBlob(2);
    BlobUploadChannel channel = createMock(BlobUploadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.writer(eq(expectedBlob), eq(BlobWriteSetting.customerSuppliedKey(BASE64_KEY))))
        .andReturn(channel)
        .times(2);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.openWriter(BlobWriteSetting.customerSuppliedKey(BASE64_KEY)));
    assertSame(channel, blob.openWriter(BlobWriteSetting.customerSuppliedKey(KEY)));
  }

  @Test
  public void testWriterWithKmsKeyName() throws Exception {
    initializeExpectedBlob(2);
    BlobUploadChannel channel = createMock(BlobUploadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.writer(eq(expectedBlob), eq(BlobWriteSetting.kmsKey(KMS_KEY_NAME))))
        .andReturn(channel);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.openWriter(BlobWriteSetting.kmsKey(KMS_KEY_NAME)));
  }

  @Test
  public void testSignUrl() throws Exception {
    initializeExpectedBlob(2);
    URL url = new URL("http://localhost:123/bla");
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.signUrl(expectedBlob, 100, TimeUnit.SECONDS)).andReturn(url);
    replay(storage);
    initializeBlob();
    assertEquals(url, blob.generateSignedUrl(100, TimeUnit.SECONDS));
  }

  @Test
  public void testGetAcl() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.getAcl(BLOB_INFO.getBlobId(), UserPrincipal.allAuthenticatedUsers())).andReturn(ACL);
    replay(storage);
    initializeBlob();
    assertEquals(ACL, blob.getAcl(AccessControlEntry.UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteAcl() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.deleteAcl(BLOB_INFO.getBlobId(), UserPrincipal.allAuthenticatedUsers()))
        .andReturn(true);
    replay(storage);
    initializeBlob();
    assertTrue(blob.deleteAclEntry(UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateAcl() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").create();
    expect(storage.createAcl(BLOB_INFO.getBlobId(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBlob();
    assertEquals(returnedAcl, blob.createAclEntry(ACL));
  }

  @Test
  public void testUpdateAcl() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").create();
    expect(storage.updateAcl(BLOB_INFO.getBlobId(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBlob();
    assertEquals(returnedAcl, blob.updateAclEntry(ACL));
  }

  @Test
  public void testListAcls() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.listAcls(BLOB_INFO.getBlobId())).andReturn(ACLS);
    replay(storage);
    initializeBlob();
    assertEquals(ACLS, blob.listAclEntries());
  }

  @Test
  public void testToBuilder() {
    expect(storage.getOptions()).andReturn(mockOptions).times(6);
    replay(storage);
    StorageBlob fullBlob = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(FULL_BLOB_INFO));
    assertEquals(fullBlob, fullBlob.toBlobInfoBuilder().buildStorageObject());
    StorageBlob simpleBlob = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
    assertEquals(simpleBlob, simpleBlob.toBlobInfoBuilder().buildStorageObject());
    StorageBlob directory = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(DIRECTORY_INFO));
    assertEquals(directory, directory.toBlobInfoBuilder().buildStorageObject());
  }

  @Test
  public void testBuilder() {
    initializeExpectedBlob(4);
    expect(storage.getOptions()).andReturn(mockOptions).times(6);
    replay(storage);
    StorageBlob.BlobInfoBuilder builder = new StorageBlob.BlobInfoBuilder(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO)));
    StorageBlob blob =
        builder
            .setAcl(ACLS)
            .setComponentCount(COMPONENT_COUNT)
            .setContentType(CONTENT_TYPE)
            .setCacheControl(CACHE_CONTROL)
            .setContentDisposition(CONTENT_DISPOSITION)
            .setContentEncoding(CONTENT_ENCODING)
            .setContentLanguage(CONTENT_LANGUAGE)
            .setCrc32c(CRC32)
            .setCreateTime(CREATE_TIME)
            .setCustomerEncryption(CUSTOMER_ENCRYPTION)
            .setKmsKeyName(KMS_KEY_NAME)
            .setEventBasedHold(EVENT_BASED_HOLD)
            .setTemporaryHold(TEMPORARY_HOLD)
            .setRetentionExpirationTime(RETENTION_EXPIRATION_TIME)
            .setDeleteTime(DELETE_TIME)
            .setEtag(ETAG)
            .setGeneratedId(GENERATED_ID)
            .setMd5(MD5)
            .setMediaLink(MEDIA_LINK)
            .setMetadata(METADATA)
            .setMetageneration(META_GENERATION)
            .setOwner(OWNER)
            .setSelfLink(SELF_LINK)
            .setSize(SIZE)
            .setUpdateTime(UPDATE_TIME)
            .buildStorageObject();
    assertEquals("b", blob.getBucket());
    assertEquals("n", blob.getName());
    assertEquals(ACLS, blob.getAcl());
    assertEquals(COMPONENT_COUNT, blob.getComponentCount());
    assertEquals(CONTENT_TYPE, blob.getContentType());
    assertEquals(CACHE_CONTROL, blob.getCacheControl());
    assertEquals(CONTENT_DISPOSITION, blob.getContentDisposition());
    assertEquals(CONTENT_ENCODING, blob.getContentEncoding());
    assertEquals(CONTENT_LANGUAGE, blob.getContentLanguage());
    assertEquals(CRC32, blob.getCrc32c());
    assertEquals(CRC32_HEX_STRING, blob.getCrc32cToHexString());
    assertEquals(CREATE_TIME, blob.getCreateTime());
    assertEquals(CUSTOMER_ENCRYPTION, blob.getCustomerEncryption());
    assertEquals(KMS_KEY_NAME, blob.getKmsKeyName());
    assertEquals(EVENT_BASED_HOLD, blob.getEventBasedHold());
    assertEquals(TEMPORARY_HOLD, blob.getTemporaryHold());
    assertEquals(RETENTION_EXPIRATION_TIME, blob.getRetentionExpirationTime());
    assertEquals(DELETE_TIME, blob.getDeleteTime());
    assertEquals(ETAG, blob.getEtag());
    assertEquals(GENERATED_ID, blob.getGeneratedId());
    assertEquals(MD5, blob.getMd5());
    assertEquals(MD5_HEX_STRING, blob.getMd5ToHexString());
    assertEquals(MEDIA_LINK, blob.getMediaLink());
    assertEquals(METADATA, blob.getMetadata());
    assertEquals(META_GENERATION, blob.getMetageneration());
    assertEquals(OWNER, blob.getOwner());
    assertEquals(SELF_LINK, blob.getSelfLink());
    assertEquals(SIZE, blob.getSize());
    assertEquals(UPDATE_TIME, blob.getUpdateTime());
    assertEquals(storage.getOptions(), blob.getStorage().getOptions());
    assertFalse(blob.isDirectory());
    builder = new StorageBlob.BlobInfoBuilder(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(DIRECTORY_INFO)));
    blob = builder.setBlobId(BlobIdentifier.create("b", "n/")).setIsDirectory(true).setSize(0L).buildStorageObject();
    assertEquals("b", blob.getBucket());
    assertEquals("n/", blob.getName());
    assertNull(blob.getAcl());
    assertNull(blob.getComponentCount());
    assertNull(blob.getContentType());
    assertNull(blob.getCacheControl());
    assertNull(blob.getContentDisposition());
    assertNull(blob.getContentEncoding());
    assertNull(blob.getContentLanguage());
    assertNull(blob.getCrc32c());
    assertNull(blob.getCrc32cToHexString());
    assertNull(blob.getCreateTime());
    assertNull(blob.getCustomerEncryption());
    assertNull(blob.getKmsKeyName());
    assertNull(blob.getEventBasedHold());
    assertNull(blob.getTemporaryHold());
    assertNull(blob.getRetentionExpirationTime());
    assertNull(blob.getDeleteTime());
    assertNull(blob.getEtag());
    assertNull(blob.getGeneratedId());
    assertNull(blob.getMd5());
    assertNull(blob.getMd5ToHexString());
    assertNull(blob.getMediaLink());
    assertNull(blob.getMetadata());
    assertNull(blob.getMetageneration());
    assertNull(blob.getOwner());
    assertNull(blob.getSelfLink());
    assertEquals(0L, (long) blob.getSize());
    assertNull(blob.getUpdateTime());
    assertTrue(blob.isDirectory());
  }

  @Test
  public void testDownload() throws Exception {
    final byte[] expected = {1, 2};
    CloudStorageRpc mockStorageRpc = createNiceMock(CloudStorageRpc.class);
    expect(storage.getOptions()).andReturn(mockOptions).times(1);
    replay(storage);
    expect(mockOptions.getStorageRpcV1()).andReturn(mockStorageRpc);
    expect(mockOptions.getRetrySettings()).andReturn(RETRY_SETTINGS);
    expect(mockOptions.getClock()).andReturn(API_CLOCK);
    replay(mockOptions);
    blob = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
    expect(
            mockStorageRpc.read(
                anyObject(StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected);
                return 2l;
              }
            });
    replay(mockStorageRpc);
    File file = File.createTempFile("blob", ".tmp");
    blob.downloadTo(file.toPath());
    byte actual[] = Files.readAllBytes(file.toPath());
    assertArrayEquals(expected, actual);
  }

  @Test
  public void testDownloadWithRetries() throws Exception {
    final byte[] expected = {1, 2};
    CloudStorageRpc mockStorageRpc = createNiceMock(CloudStorageRpc.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    replay(storage);
    expect(mockOptions.getStorageRpcV1()).andReturn(mockStorageRpc);
    expect(mockOptions.getRetrySettings()).andReturn(RETRY_SETTINGS);
    expect(mockOptions.getClock()).andReturn(API_CLOCK);
    replay(mockOptions);
    blob = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
    expect(
            mockStorageRpc.read(
                anyObject(StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected[0]);
                throw new StorageOperationException(504, "error");
              }
            });
    expect(
            mockStorageRpc.read(
                anyObject(StorageObject.class),
                anyObject(Map.class),
                eq(1l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected[1]);
                return 1l;
              }
            });
    replay(mockStorageRpc);
    File file = File.createTempFile("blob", ".tmp");
    blob.downloadTo(file.toPath());
    byte actual[] = Files.readAllBytes(file.toPath());
    assertArrayEquals(expected, actual);
  }
}
