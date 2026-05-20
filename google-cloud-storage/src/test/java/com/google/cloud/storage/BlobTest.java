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
import static org.junit.Assert.fail;

import com.google.api.core.ApiClock;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.StorageObject.BlobSourceOptions;
import com.google.cloud.storage.CloudStorageClient.BlobWriteOptions;
import com.google.cloud.storage.CloudStorageClient.CopyOperationRequest;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
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

  private static final AccessControlEntry ACL = AccessControlEntry.create(AccessControlEntry.UserIdentity.allAuthenticatedUsers(), AccessControlEntry.UserRole.OWNER);
  private static final AccessControlEntry OTHER_ACL = AccessControlEntry.create(new AccessControlEntry.ProjectInfo(AccessControlEntry.ProjectInfo.ProjectRoleType.OWNERS, "p"), AccessControlEntry.UserRole.READER);
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
  private static final AccessControlEntry.UserIdentity OWNER = new AccessControlEntry.UserIdentity("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long SIZE = 1024L;
  private static final Long UPDATE_TIME = DELETE_TIME - 1L;
  private static final Long CREATE_TIME = UPDATE_TIME - 1L;
  private static final Long CUSTOM_TIME = CREATE_TIME - 1L;
  private static final StorageClassType STORAGE_CLASS = StorageClassType.COLDLINE;
  private static final Long TIME_STORAGE_CLASS_UPDATED = CREATE_TIME;
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
          .setCustomTime(CUSTOM_TIME)
          .setStorageClass(STORAGE_CLASS)
          .setTimeStorageClassUpdated(TIME_STORAGE_CLASS_UPDATED)
          .setCustomerEncryption(CUSTOMER_ENCRYPTION)
          .setKmsKeyName(KMS_KEY_NAME)
          .setEventBasedHold(EVENT_BASED_HOLD)
          .setTemporaryHold(TEMPORARY_HOLD)
          .setRetentionExpirationTime(RETENTION_EXPIRATION_TIME)
          .buildMetadata();
  private static final BlobMetadata BLOB_INFO =
      BlobMetadata.newBuilder("b", "n", 12345678L).setMetageneration(42L).buildMetadata();
  private static final BlobMetadata BLOB_INFO_NO_GENERATION =
      BlobMetadata.newBuilder(BLOB_INFO.getBucket(), BLOB_INFO.getName())
          .setMetageneration(42L)
          .buildMetadata();
  private static final BlobMetadata DIRECTORY_INFO =
      BlobMetadata.newBuilder("b", "n/").setSize(0L).setIsDirectory(true).buildMetadata();
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

  private CloudStorageClient storage;
  private StorageObject blob;
  private StorageObject expectedBlob;
  private CloudStorageClient serviceMockReturnsOptions = createMock(CloudStorageClient.class);
  private StorageClientOptions mockOptions = createMock(StorageClientOptions.class);

  @Before
  public void setUp() {
    storage = createStrictMock(CloudStorageClient.class);
  }

  @After
  public void tearDown() throws Exception {
    verify(storage);
  }

  private void initializeExpectedBlob(int optionsCalls) {
    expect(serviceMockReturnsOptions.getOptions()).andReturn(mockOptions).times(optionsCalls);
    replay(serviceMockReturnsOptions);
    expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobMetadataBuilderImpl(BLOB_INFO));
  }

  private void initializeBlob() {
    blob = new StorageObject(storage, new BlobMetadata.BlobMetadataBuilderImpl(BLOB_INFO));
  }

  @Test
  public void testExists_True() throws Exception {
    initializeExpectedBlob(1);
    CloudStorageClient.BlobGetOptions[] expectedOptions = {CloudStorageClient.BlobGetOptions.selectFields()};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(expectedBlob.getBlobId(), expectedOptions)).andReturn(expectedBlob);
    replay(storage);
    initializeBlob();
    assertTrue(blob.existsInStorage());
  }

  @Test
  public void testExists_False() throws Exception {
    CloudStorageClient.BlobGetOptions[] expectedOptions = {CloudStorageClient.BlobGetOptions.selectFields()};
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
                BLOB_INFO.getBlobId(), CloudStorageClient.BlobReadOption.withDecryptionKey(BASE64_KEY)))
        .andReturn(content)
        .times(2);
    replay(storage);
    initializeBlob();
    assertArrayEquals(content, blob.getContent(BlobSourceOptions.withDecryptionKey(BASE64_KEY)));
    assertArrayEquals(content, blob.getContent(StorageObject.BlobSourceOptions.withDecryptionKey(KEY)));
  }

  @Test
  public void testReload() throws Exception {
    initializeExpectedBlob(2);
    StorageObject expectedReloadedBlob = expectedBlob.toBuilderCopy().setCacheControl("c").buildMetadata();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BLOB_INFO_NO_GENERATION.getBlobId(), new CloudStorageClient.BlobGetOptions[0]))
        .andReturn(expectedReloadedBlob);
    replay(storage);
    initializeBlob();
    StorageObject updatedBlob = blob.reloadFromStorage();
    assertEquals(expectedReloadedBlob, updatedBlob);
  }

  @Test
  public void testReloadNull() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BLOB_INFO_NO_GENERATION.getBlobId(), new CloudStorageClient.BlobGetOptions[0]))
        .andReturn(null);
    replay(storage);
    initializeBlob();
    StorageObject reloadedBlob = blob.reloadFromStorage();
    assertNull(reloadedBlob);
  }

  @Test
  public void testReloadWithOptions() throws Exception {
    initializeExpectedBlob(2);
    StorageObject expectedReloadedBlob = expectedBlob.toBuilderCopy().setCacheControl("c").buildMetadata();
    CloudStorageClient.BlobGetOptions[] options = {CloudStorageClient.BlobGetOptions.ifMetagenerationMatch(42L)};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BLOB_INFO_NO_GENERATION.getBlobId(), options))
        .andReturn(expectedReloadedBlob);
    replay(storage);
    initializeBlob();
    StorageObject updatedBlob = blob.reloadFromStorage(BlobSourceOptions.ifMetagenerationMatch());
    assertEquals(expectedReloadedBlob, updatedBlob);
  }

  @Test
  public void testUpdate() throws Exception {
    initializeExpectedBlob(2);
    StorageObject expectedUpdatedBlob = expectedBlob.toBuilderCopy().setCacheControl("c").buildMetadata();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(eq(expectedUpdatedBlob), new CloudStorageClient.BlobUploadOption[0]))
        .andReturn(expectedUpdatedBlob);
    replay(storage);
    initializeBlob();
    StorageObject updatedBlob = new StorageObject(storage, new BlobMetadata.BlobMetadataBuilderImpl(expectedUpdatedBlob));
    StorageObject actualUpdatedBlob = updatedBlob.updateInStorage();
    assertEquals(expectedUpdatedBlob, actualUpdatedBlob);
  }

  @Test
  public void testDelete() throws Exception {
    initializeExpectedBlob(2);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.delete(BLOB_INFO.getBlobId(), new CloudStorageClient.BlobReadOption[0])).andReturn(true);
    replay(storage);
    initializeBlob();
    assertTrue(blob.deleteFromStorage());
  }

  @Test
  public void testCopyToBucket() throws Exception {
    initializeExpectedBlob(2);
    BlobMetadata target = BlobMetadata.newBuilder(BlobIdentifier.from("bt", "n")).buildMetadata();
    BlobRewriteWriter copyWriter = createMock(BlobRewriteWriter.class);
    Capture<CloudStorageClient.CopyOperationRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    BlobRewriteWriter returnedCopyWriter = blob.copyToDestination("bt");
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(BLOB_INFO_NO_GENERATION.getBlobId(), capturedCopyRequest.getValue().getSource());
    assertEquals(target, capturedCopyRequest.getValue().getTarget());
    assertFalse(capturedCopyRequest.getValue().getOverrideInfo());
    assertTrue(capturedCopyRequest.getValue().getSourceOptions().isEmpty());
    assertTrue(capturedCopyRequest.getValue().getTargetOptions().isEmpty());
  }

  @Test
  public void testCopyTo() throws Exception {
    initializeExpectedBlob(2);
    BlobMetadata target = BlobMetadata.newBuilder(BlobIdentifier.from("bt", "nt")).buildMetadata();
    BlobRewriteWriter copyWriter = createMock(BlobRewriteWriter.class);
    Capture<CopyOperationRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    BlobRewriteWriter returnedCopyWriter = blob.copyToDestination("bt", "nt");
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(BLOB_INFO_NO_GENERATION.getBlobId(), capturedCopyRequest.getValue().getSource());
    assertEquals(target, capturedCopyRequest.getValue().getTarget());
    assertFalse(capturedCopyRequest.getValue().getOverrideInfo());
    assertTrue(capturedCopyRequest.getValue().getSourceOptions().isEmpty());
    assertTrue(capturedCopyRequest.getValue().getTargetOptions().isEmpty());
  }

  @Test
  public void testCopyToBlobId() throws Exception {
    initializeExpectedBlob(2);
    BlobMetadata target = BlobMetadata.newBuilder(BlobIdentifier.from("bt", "nt")).buildMetadata();
    BlobIdentifier targetId = BlobIdentifier.from("bt", "nt");
    BlobRewriteWriter copyWriter = createMock(BlobRewriteWriter.class);
    Capture<CopyOperationRequest> capturedCopyRequest = Capture.newInstance();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.copy(capture(capturedCopyRequest))).andReturn(copyWriter);
    replay(storage);
    initializeBlob();
    BlobRewriteWriter returnedCopyWriter = blob.copyToDestination(targetId);
    assertEquals(copyWriter, returnedCopyWriter);
    assertEquals(BLOB_INFO_NO_GENERATION.getBlobId(), capturedCopyRequest.getValue().getSource());
    assertEquals(target, capturedCopyRequest.getValue().getTarget());
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
    assertSame(channel, blob.newReader());
  }

  @Test
  public void testReaderWithDecryptionKey() throws Exception {
    initializeExpectedBlob(2);
    ReadChannel channel = createMock(ReadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.reader(
                BLOB_INFO.getBlobId(), CloudStorageClient.BlobReadOption.withDecryptionKey(BASE64_KEY)))
        .andReturn(channel)
        .times(2);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newReader(BlobSourceOptions.withDecryptionKey(BASE64_KEY)));
    assertSame(channel, blob.newReader(StorageObject.BlobSourceOptions.withDecryptionKey(KEY)));
  }

  @Test
  public void testWriter() throws Exception {
    initializeExpectedBlob(2);
    BlobUploadChannel channel = createMock(BlobUploadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.writer(eq(expectedBlob))).andReturn(channel);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newWriter());
  }

  @Test
  public void testWriterWithEncryptionKey() throws Exception {
    initializeExpectedBlob(2);
    BlobUploadChannel channel = createMock(BlobUploadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.writer(eq(expectedBlob), eq(BlobWriteOptions.withEncryptionKey(BASE64_KEY))))
        .andReturn(channel)
        .times(2);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newWriter(CloudStorageClient.BlobWriteOptions.withEncryptionKey(BASE64_KEY)));
    assertSame(channel, blob.newWriter(CloudStorageClient.BlobWriteOptions.withEncryptionKey(KEY)));
  }

  @Test
  public void testWriterWithKmsKeyName() throws Exception {
    initializeExpectedBlob(2);
    BlobUploadChannel channel = createMock(BlobUploadChannel.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.writer(eq(expectedBlob), eq(CloudStorageClient.BlobWriteOptions.withKmsKeyName(KMS_KEY_NAME))))
        .andReturn(channel);
    replay(storage);
    initializeBlob();
    assertSame(channel, blob.newWriter(CloudStorageClient.BlobWriteOptions.withKmsKeyName(KMS_KEY_NAME)));
  }

  @Test
  public void testSignUrl() throws Exception {
    initializeExpectedBlob(2);
    URL url = new URL("http://localhost:123/bla");
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.signUrl(expectedBlob, 100, TimeUnit.SECONDS)).andReturn(url);
    replay(storage);
    initializeBlob();
    assertEquals(url, blob.signUrlWithExpiration(100, TimeUnit.SECONDS));
  }

  @Test
  public void testGetAcl() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.getAcl(BLOB_INFO.getBlobId(), AccessControlEntry.UserIdentity.allAuthenticatedUsers())).andReturn(ACL);
    replay(storage);
    initializeBlob();
    assertEquals(ACL, blob.getAcl(AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteAcl() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.deleteAcl(BLOB_INFO.getBlobId(), AccessControlEntry.UserIdentity.allAuthenticatedUsers()))
        .andReturn(true);
    replay(storage);
    initializeBlob();
    assertTrue(blob.deleteAclEntry(AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateAcl() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
    expect(storage.createAcl(BLOB_INFO.getBlobId(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBlob();
    assertEquals(returnedAcl, blob.createAclEntry(ACL));
  }

  @Test
  public void testUpdateAcl() throws Exception {
    initializeExpectedBlob(1);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
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
    StorageObject fullBlob = new StorageObject(storage, new BlobMetadata.BlobMetadataBuilderImpl(FULL_BLOB_INFO));
    assertEquals(fullBlob, fullBlob.toBuilderCopy().buildMetadata());
    StorageObject simpleBlob = new StorageObject(storage, new BlobMetadata.BlobMetadataBuilderImpl(BLOB_INFO));
    assertEquals(simpleBlob, simpleBlob.toBuilderCopy().buildMetadata());
    StorageObject directory = new StorageObject(storage, new BlobMetadata.BlobMetadataBuilderImpl(DIRECTORY_INFO));
    assertEquals(directory, directory.toBuilderCopy().buildMetadata());
  }

  @Test
  public void testBuilder() {
    initializeExpectedBlob(4);
    expect(storage.getOptions()).andReturn(mockOptions).times(6);
    replay(storage);
    StorageObject.BlobInfoBuilder builder = new StorageObject.BlobInfoBuilder(new StorageObject(storage, new BlobMetadata.BlobMetadataBuilderImpl(BLOB_INFO)));
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
            .buildMetadata();
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
    builder = new StorageObject.BlobInfoBuilder(new StorageObject(storage, new BlobMetadata.BlobMetadataBuilderImpl(DIRECTORY_INFO)));
    blob = builder.setBlobId(BlobIdentifier.from("b", "n/")).setIsDirectory(true).setSize(0L).buildMetadata();
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

  private StorageRpcClient prepareForDownload() {
    StorageRpcClient mockStorageRpc = createNiceMock(StorageRpcClient.class);
    expect(storage.getOptions()).andReturn(mockOptions);
    replay(storage);
    expect(mockOptions.getStorageRpcV1()).andReturn(mockStorageRpc);
    expect(mockOptions.getRetrySettings()).andReturn(RETRY_SETTINGS);
    expect(mockOptions.getClock()).andReturn(API_CLOCK);
    replay(mockOptions);
    blob = new StorageObject(storage, new BlobMetadata.BlobMetadataBuilderImpl(BLOB_INFO));
    return mockStorageRpc;
  }

  @Test
  public void testDownloadTo() throws Exception {
    final byte[] expected = {1, 2};
    StorageRpcClient mockStorageRpc = prepareForDownload();
    expect(
            mockStorageRpc.read(
                anyObject(com.google.api.services.storage.model.StorageObject.class),
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
  public void testDownloadToWithRetries() throws Exception {
    final byte[] expected = {1, 2};
    StorageRpcClient mockStorageRpc = prepareForDownload();
    expect(
            mockStorageRpc.read(
                anyObject(com.google.api.services.storage.model.StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected[0]);
                throw new StorageServiceException(504, "error");
              }
            });
    expect(
            mockStorageRpc.read(
                anyObject(com.google.api.services.storage.model.StorageObject.class),
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

  @Test
  public void testDownloadToWithException() throws Exception {
    StorageRpcClient mockStorageRpc = prepareForDownload();
    Exception exception = new IllegalStateException("test");
    expect(
            mockStorageRpc.read(
                anyObject(com.google.api.services.storage.model.StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andThrow(exception);
    replay(mockStorageRpc);
    File file = File.createTempFile("blob", ".tmp");
    try {
      blob.downloadTo(file.toPath());
      fail();
    } catch (StorageServiceException e) {
      assertSame(exception, e.getCause());
    }
  }
}
