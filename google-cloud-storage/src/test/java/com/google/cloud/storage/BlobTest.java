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

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
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
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.AccessControlEntry.ProjectInfo;
import com.google.cloud.storage.AccessControlEntry.UserIdentity;
import com.google.cloud.storage.StorageObject.BlobReadOption;
import com.google.cloud.storage.Storage.BlobWriteOptions;
import com.google.cloud.storage.Storage.ChunkedCopyRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import java.net.URL;
import java.security.Key;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.easymock.Capture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BlobTest {

  private static final AccessControlEntry ACL = AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), AccessControlEntry.RoleType.OWNER);
  private static final AccessControlEntry OTHER_ACL = AccessControlEntry.create(new AccessControlEntry.ProjectInfo(ProjectInfo.ProjectMemberRole.OWNERS, "p"), AccessControlEntry.RoleType.READER);
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
  private static final UserIdentity OWNER = new UserIdentity("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long SIZE = 1024L;
  private static final Long UPDATE_TIME = DELETE_TIME - 1L;
  private static final Long CREATE_TIME = UPDATE_TIME - 1L;
  private static final Long CUSTOM_TIME = CREATE_TIME - 1L;
  private static final StorageTier STORAGE_CLASS = StorageTier.COLDLINE;
  private static final Long TIME_STORAGE_CLASS_UPDATED = CREATE_TIME;
  private static final String ENCRYPTION_ALGORITHM = "AES256";
  private static final String KEY_SHA256 = "keySha";
  private static final BlobMetadata.CustomerEncryptionMetadata CUSTOMER_ENCRYPTION =
      new BlobMetadata.CustomerEncryptionMetadata(ENCRYPTION_ALGORITHM, KEY_SHA256);
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
          .setCustomTime(CUSTOM_TIME)
          .setStorageClass(STORAGE_CLASS)
          .setTimeStorageClassUpdated(TIME_STORAGE_CLASS_UPDATED)
          .setCustomerEncryption(CUSTOMER_ENCRYPTION)
          .setKmsKeyName(KMS_KEY_NAME)
          .setEventBasedHold(EVENT_BASED_HOLD)
          .setTemporaryHold(TEMPORARY_HOLD)
          .setRetentionExpirationTime(RETENTION_EXPIRATION_TIME)
          .buildObject();
  private static final BlobMetadata BLOB_INFO =
      BlobMetadata.newBuilder("b", "n", 12345678L).setMetageneration(42L).buildObject();
  private static final BlobMetadata BLOB_INFO_NO_GENERATION =
      BlobMetadata.newBuilder(BLOB_INFO.getBucket(), BLOB_INFO.getName())
          .setMetageneration(42L)
          .buildObject();
  private static final BlobMetadata DIRECTORY_INFO =
      BlobMetadata.newBuilder("b", "n/").setSize(0L).setIsDirectory(true).buildObject();
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

  private Storage storage;
  private StorageObject blob;
  private StorageObject expectedBlob;
  private Storage serviceMockReturnsOptions = createMock(Storage.class);
  private StorageClientOptions mockOptions = createMock(StorageClientOptions.class);
  private final RetryAlgorithmManager retryAlgorithmManager =
      StorageClientOptions.getDefaultInstance().getRetryAlgorithmManager();

  @Before
  public void setUp() {
    storage = createStrictMock(Storage.class);
  }

  @After
  public void tearDown() throws Exception {
    verify(storage);
  }

  private void initializeExpectedBlob() {
    expect(serviceMockReturnsOptions.getOptions()).andReturn(mockOptions).anyTimes();
    expect(mockOptions.getRetryAlgorithmManager()).andReturn(retryAlgorithmManager).anyTimes();
    replay(mockOptions);
    replay(serviceMockReturnsOptions);
    expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
  }

  private void initializeBlob() {
    blob = new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
  }

  @Test
  public void testExists_True() throws Exception {
    initializeExpectedBlob();
    Storage.BlobGetOptions[] expectedOptions = {Storage.BlobGetOptions.selectFields()};
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.get(expectedBlob.getBlobId(), expectedOptions)).andReturn(expectedBlob);
    replay(storage);
    initializeBlob();
    assertTrue(blob.blobExists());
  }

  @Test
  public void testExists_False() throws Exception {
    Storage.BlobGetOptions[] expectedOptions = {Storage.BlobGetOptions.selectFields()};
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.get(BLOB_INFO.getBlobId(), expectedOptions)).andReturn(null);
    replay(storage);
    initializeBlob();
    assertFalse(blob.blobExists());
  }

  @Test
  public void testContent() throws Exception {
    initializeExpectedBlob();
    byte[] content = {1, 2};
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.readAllBytes(BLOB_INFO.getBlobId())).andReturn(content);
    replay(storage);
    initializeBlob();
    assertArrayEquals(content, blob.getContent());
  }

  @Test
  public void testContentWithDecryptionKey() throws Exception {
    initializeExpectedBlob();
    byte[] content = {1, 2};
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(
            storage.readAllBytes(
                BLOB_INFO.getBlobId(), Storage.BlobSourceOptions.customerDecryptionKey(BASE64_KEY)))
        .andReturn(content)
        .times(2);
    replay(storage);
    initializeBlob();
    assertArrayEquals(content, blob.getContent(BlobReadOption.customerDecryptionKey(BASE64_KEY)));
    assertArrayEquals(content, blob.getContent(BlobReadOption.customerDecryptionKey(KEY)));
  }

  @Test
  public void testReload() throws Exception {
    initializeExpectedBlob();
    StorageObject expectedReloadedBlob = expectedBlob.toInfoBuilder().setCacheControl("c").buildObject();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.get(BLOB_INFO_NO_GENERATION.getBlobId(), new Storage.BlobGetOptions[0]))
        .andReturn(expectedReloadedBlob);
    replay(storage);
    initializeBlob();
    StorageObject updatedBlob = blob.reloadFromStorage();
    assertEquals(expectedReloadedBlob, updatedBlob);
  }

  @Test
  public void testReloadNull() throws Exception {
    initializeExpectedBlob();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.get(BLOB_INFO_NO_GENERATION.getBlobId(), new Storage.BlobGetOptions[0]))
        .andReturn(null);
    replay(storage);
    initializeBlob();
    StorageObject reloadedBlob = blob.reloadFromStorage();
    assertNull(reloadedBlob);
  }

  @Test
  public void testReloadWithOptions() throws Exception {
    initializeExpectedBlob();
    StorageObject expectedReloadedBlob = expectedBlob.toInfoBuilder().setCacheControl("c").buildObject();
    Storage.BlobGetOptions[] options = {Storage.BlobGetOptions.ifMetagenerationMatch(42L)};
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.get(BLOB_INFO_NO_GENERATION.getBlobId(), options))
        .andReturn(expectedReloadedBlob);
    replay(storage);
    initializeBlob();
    StorageObject updatedBlob = blob.reloadFromStorage(StorageObject.BlobReadOption.ifMetagenerationMatch());
    assertEquals(expectedReloadedBlob, updatedBlob);
  }

  @Test
  public void testUpdate() throws Exception {
    initializeExpectedBlob();
    StorageObject expectedUpdatedBlob = expectedBlob.toInfoBuilder().setCacheControl("c").buildObject();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.update(eq(expectedUpdatedBlob), new Storage.BlobUploadOption[0]))
        .andReturn(expectedUpdatedBlob);
    replay(storage);
    initializeBlob();
    StorageObject updatedBlob = new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(expectedUpdatedBlob));
    StorageObject actualUpdatedBlob = updatedBlob.updateInStorage();
    assertEquals(expectedUpdatedBlob, actualUpdatedBlob);
  }

  @Test
  public void testDelete() throws Exception {
    initializeExpectedBlob();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.delete(BLOB_INFO.getBlobId(), new Storage.BlobSourceOptions[0])).andReturn(true);
    replay(storage);
    initializeBlob();
    assertTrue(blob.deleteFromStorage());
  }

  @Test
  public void testCopyToBucket() throws Exception {
    initializeExpectedBlob();
    BlobMetadata target = BlobMetadata.newBuilder(BlobId.from("bt", "n")).buildObject();
    ObjectCopyWriter copyWriter = createMock(ObjectCopyWriter.class);
    Capture<Storage.ChunkedCopyRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    ObjectCopyWriter returnedCopyWriter = blob.copyToTarget("bt");
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(BLOB_INFO_NO_GENERATION.getBlobId(), capturedCopyRequest.getValue().getSource());
    assertEquals(target, capturedCopyRequest.getValue().getTarget());
    assertFalse(capturedCopyRequest.getValue().getOverrideInfo());
    assertTrue(capturedCopyRequest.getValue().getSourceOptions().isEmpty());
    assertTrue(capturedCopyRequest.getValue().getTargetOptions().isEmpty());
  }

  @Test
  public void testCopyTo() throws Exception {
    initializeExpectedBlob();
    BlobMetadata target = BlobMetadata.newBuilder(BlobId.from("bt", "nt")).buildObject();
    ObjectCopyWriter copyWriter = createMock(ObjectCopyWriter.class);
    Capture<Storage.ChunkedCopyRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    ObjectCopyWriter returnedCopyWriter = blob.copyToTarget("bt", "nt");
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(BLOB_INFO_NO_GENERATION.getBlobId(), capturedCopyRequest.getValue().getSource());
    assertEquals(target, capturedCopyRequest.getValue().getTarget());
    assertFalse(capturedCopyRequest.getValue().getOverrideInfo());
    assertTrue(capturedCopyRequest.getValue().getSourceOptions().isEmpty());
    assertTrue(capturedCopyRequest.getValue().getTargetOptions().isEmpty());
  }

  @Test
  public void testCopyToBlobId() throws Exception {
    initializeExpectedBlob();
    BlobMetadata target = BlobMetadata.newBuilder(BlobId.from("bt", "nt")).buildObject();
    BlobId targetId = BlobId.from("bt", "nt");
    ObjectCopyWriter copyWriter = createMock(ObjectCopyWriter.class);
    Capture<ChunkedCopyRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    ObjectCopyWriter returnedCopyWriter = blob.copyToTarget(targetId);
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(BLOB_INFO_NO_GENERATION.getBlobId(), capturedCopyRequest.getValue().getSource());
    assertEquals(target, capturedCopyRequest.getValue().getTarget());
    assertFalse(capturedCopyRequest.getValue().getOverrideInfo());
    assertTrue(capturedCopyRequest.getValue().getSourceOptions().isEmpty());
    assertTrue(capturedCopyRequest.getValue().getTargetOptions().isEmpty());
  }

  @Test
  public void testReader() throws Exception {
    initializeExpectedBlob();
    ReadChannel channel = createMock(ReadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.reader(BLOB_INFO.getBlobId())).andReturn(channel);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newReader());
  }

  @Test
  public void testReaderWithDecryptionKey() throws Exception {
    initializeExpectedBlob();
    ReadChannel channel = createMock(ReadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(
            storage.reader(
                BLOB_INFO.getBlobId(), Storage.BlobSourceOptions.customerDecryptionKey(BASE64_KEY)))
        .andReturn(channel)
        .times(2);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newReader(BlobReadOption.customerDecryptionKey(BASE64_KEY)));
    assertSame(channel, blob.newReader(BlobReadOption.customerDecryptionKey(KEY)));
  }

  @Test
  public void testWriter() throws Exception {
    initializeExpectedBlob();
    BlobWriteChannel channel = createMock(BlobWriteChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.writer(eq(expectedBlob))).andReturn(channel);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newWriter());
  }

  @Test
  public void testWriterWithEncryptionKey() throws Exception {
    initializeExpectedBlob();
    BlobWriteChannel channel = createMock(BlobWriteChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.writer(eq(expectedBlob), eq(BlobWriteOptions.customerSuppliedKey(BASE64_KEY))))
        .andReturn(channel)
        .times(2);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newWriter(BlobWriteOptions.customerSuppliedKey(BASE64_KEY)));
    assertSame(channel, blob.newWriter(BlobWriteOptions.customerSuppliedKey(KEY)));
  }

  @Test
  public void testWriterWithKmsKeyName() throws Exception {
    initializeExpectedBlob();
    BlobWriteChannel channel = createMock(BlobWriteChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.writer(eq(expectedBlob), eq(BlobWriteOptions.withKmsKeyName(KMS_KEY_NAME))))
        .andReturn(channel);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newWriter(BlobWriteOptions.withKmsKeyName(KMS_KEY_NAME)));
  }

  @Test
  public void testSignUrl() throws Exception {
    initializeExpectedBlob();
    URL url = new URL("http://localhost:123/bla");
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.signUrl(expectedBlob, 100, TimeUnit.SECONDS)).andReturn(url);
    replay(storage);
    initializeBlob();
    assertEquals(url, blob.generateSignedUrl(100, TimeUnit.SECONDS));
  }

  @Test
  public void testGetAcl() throws Exception {
    initializeExpectedBlob();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.getAcl(BLOB_INFO.getBlobId(), UserIdentity.allAuthenticatedUsers())).andReturn(ACL);
    replay(storage);
    initializeBlob();
    assertEquals(ACL, blob.getAcl(UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteAcl() throws Exception {
    initializeExpectedBlob();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.deleteAcl(BLOB_INFO.getBlobId(), UserIdentity.allAuthenticatedUsers()))
        .andReturn(true);
    replay(storage);
    initializeBlob();
    assertTrue(blob.deleteAclEntry(UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateAcl() throws Exception {
    initializeExpectedBlob();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    expect(storage.createAcl(BLOB_INFO.getBlobId(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBlob();
    assertEquals(returnedAcl, blob.createAclEntry(ACL));
  }

  @Test
  public void testUpdateAcl() throws Exception {
    initializeExpectedBlob();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    expect(storage.updateAcl(BLOB_INFO.getBlobId(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBlob();
    assertEquals(returnedAcl, blob.updateAclEntry(ACL));
  }

  @Test
  public void testListAcls() throws Exception {
    initializeExpectedBlob();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(storage.listAcls(BLOB_INFO.getBlobId())).andReturn(ACLS);
    replay(storage);
    initializeBlob();
    assertEquals(ACLS, blob.listAclEntries());
  }

  @Test
  public void testToBuilder() {
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    expect(mockOptions.getRetryAlgorithmManager()).andReturn(retryAlgorithmManager).anyTimes();
    replay(storage);
    replay(mockOptions);
    StorageObject fullBlob = new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(FULL_BLOB_INFO));
    assertEquals(fullBlob, fullBlob.toInfoBuilder().buildObject());
    StorageObject simpleBlob = new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
    assertEquals(simpleBlob, simpleBlob.toInfoBuilder().buildObject());
    StorageObject directory = new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(DIRECTORY_INFO));
    assertEquals(directory, directory.toInfoBuilder().buildObject());
  }

  @Test
  public void testBuilder() {
    initializeExpectedBlob();
    expect(storage.getOptions()).andReturn(mockOptions).anyTimes();
    replay(storage);
    StorageObject.BlobInfoBuilder builder = new StorageObject.BlobInfoBuilder(new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO)));
    StorageObject blob =
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
            .setCustomTime(CUSTOM_TIME)
            .setStorageClass(STORAGE_CLASS)
            .setTimeStorageClassUpdated(TIME_STORAGE_CLASS_UPDATED)
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
            .buildObject();
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
    assertEquals(CUSTOM_TIME, blob.getCustomTime());
    assertEquals(STORAGE_CLASS, blob.getStorageClass());
    assertEquals(TIME_STORAGE_CLASS_UPDATED, blob.getTimeStorageClassUpdated());
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
    builder = new StorageObject.BlobInfoBuilder(new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(DIRECTORY_INFO)));
    blob = builder.setBlobId(BlobId.from("b", "n/")).setIsDirectory(true).setSize(0L).buildObject();
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
    assertNull(blob.getStorageClass());
    assertNull(blob.getTimeStorageClassUpdated());
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
    assertNull(blob.getCustomTime());
    assertTrue(blob.isDirectory());
  }
}
