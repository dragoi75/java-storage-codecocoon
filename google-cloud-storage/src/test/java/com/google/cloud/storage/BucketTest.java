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

import static com.google.cloud.storage.AccessControlEntry.UserRole.WRITER;
import static com.google.common.truth.Truth.assertThat;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.api.gax.paging.Page;
import com.google.cloud.PageImpl;
import com.google.cloud.storage.AccessControlEntry.ProjectInfo;
import com.google.cloud.storage.AccessControlEntry.UserRole;
import com.google.cloud.storage.AccessControlEntry.UserPrincipal;
import com.google.cloud.storage.BucketInfo.LifecycleRuleDefinition.LifecycleRuleCondition;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.Key;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BucketTest {

  private static final AccessControlEntry ACL = AccessControlEntry.create(UserPrincipal.allAuthenticatedUsers(), UserRole.OWNER);
  private static final AccessControlEntry OTHER_ACL = AccessControlEntry.create(new ProjectInfo(ProjectInfo.ProjectUserRole.OWNERS, "p"), AccessControlEntry.UserRole.READER);
  private static final List<AccessControlEntry> ACLS = ImmutableList.of(ACL, OTHER_ACL);
  private static final String ETAG = "0xFF00";
  private static final String GENERATED_ID = "B/N:1";
  private static final Long META_GENERATION = 10L;
  private static final UserPrincipal OWNER = new UserPrincipal("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long CREATE_TIME = System.currentTimeMillis();
  private static final Long UPDATE_TIME = CREATE_TIME - 1L;
  private static final List<Cors> CORS = Collections.singletonList(Cors.newBuilder().build());
  private static final List<AccessControlEntry> DEFAULT_ACL =
      Collections.singletonList(AccessControlEntry.create(UserPrincipal.allAuthenticatedUsers(), WRITER));

  @SuppressWarnings({"unchecked", "deprecation"})
  private static final List<? extends BucketInfo.DeletionRule> DELETE_RULES =
      Collections.singletonList(new BucketInfo.AgeBasedDeletionRule(5));

  private static final List<? extends BucketInfo.LifecycleRuleDefinition> LIFECYCLE_RULES =
      Collections.singletonList(
          new BucketInfo.LifecycleRuleDefinition(
              BucketInfo.LifecycleRuleDefinition.AbstractLifecycleAction.newRemoveAction(),
              LifecycleRuleCondition.newLifecycleRuleBuilder().setAge(5).buildLifecycleCondition()));
  private static final String INDEX_PAGE = "index.html";
  private static final String NOT_FOUND_PAGE = "error.html";
  private static final String LOCATION = "ASIA";
  private static final StorageTier STORAGE_CLASS = StorageTier.STANDARD;
  private static final String DEFAULT_KMS_KEY_NAME =
      "projects/p/locations/kr-loc/keyRings/kr/cryptoKeys/key";
  private static final Boolean VERSIONING_ENABLED = true;
  private static final Map<String, String> BUCKET_LABELS = ImmutableMap.of("label1", "value1");
  private static final Boolean REQUESTER_PAYS = true;
  private static final String USER_PROJECT = "test-project";
  private static final Boolean DEFAULT_EVENT_BASED_HOLD = true;
  private static final Long RETENTION_EFFECTIVE_TIME = 10L;
  private static final Long RETENTION_PERIOD = 10L;
  private static final Boolean RETENTION_POLICY_IS_LOCKED = false;
  private static final List<String> LOCATION_TYPES =
      ImmutableList.of("multi-region", "region", "dual-region");
  private static final String LOCATION_TYPE = "multi-region";

  @SuppressWarnings({"unchecked", "deprecation"})
  private static final BucketInfo FULL_BUCKET_INFO =
      BucketInfo.newBucketBuilder("b")
          .setAcl(ACLS)
          .setEtag(ETAG)
          .setGeneratedId(GENERATED_ID)
          .setMetageneration(META_GENERATION)
          .setOwner(OWNER)
          .setSelfLink(SELF_LINK)
          .setCors(CORS)
          .setCreateTime(CREATE_TIME)
          .setUpdateTime(UPDATE_TIME)
          .setDefaultAcl(DEFAULT_ACL)
          .setDeleteRules(DELETE_RULES)
          .setLifecycleRules(LIFECYCLE_RULES)
          .setIndexPage(INDEX_PAGE)
          .setNotFoundPage(NOT_FOUND_PAGE)
          .setLocation(LOCATION)
          .setStorageClass(STORAGE_CLASS)
          .setVersioningEnabled(VERSIONING_ENABLED)
          .setLabels(BUCKET_LABELS)
          .setRequesterPays(REQUESTER_PAYS)
          .setDefaultKmsKeyName(DEFAULT_KMS_KEY_NAME)
          .setDefaultEventBasedHold(DEFAULT_EVENT_BASED_HOLD)
          .setRetentionEffectiveTime(RETENTION_EFFECTIVE_TIME)
          .setRetentionPeriod(RETENTION_PERIOD)
          .setRetentionPolicyIsLocked(RETENTION_POLICY_IS_LOCKED)
          .buildBucketInfo();

  private static final BucketInfo BUCKET_INFO =
      BucketInfo.newBucketBuilder("b").setMetageneration(42L).buildBucketInfo();
  private static final String CONTENT_TYPE = "text/plain";
  private static final String BASE64_KEY = "JVzfVl8NLD9FjedFuStegjRfES5ll5zc59CIXw572OA=";
  private static final Key KEY =
      new SecretKeySpec(BaseEncoding.base64().decode(BASE64_KEY), "AES256");

  private CloudStorageClient storage;
  private CloudStorageClient serviceMockReturnsOptions = createMock(CloudStorageClient.class);
  private StorageClientOptions mockOptions = createMock(StorageClientOptions.class);
  private StorageBucket bucket;
  private StorageBucket expectedBucket;
  private List<StorageBlob> blobResults;

  @Before
  public void setUp() {
    storage = createStrictMock(CloudStorageClient.class);
  }

  @After
  public void tearDown() throws Exception {
    verify(storage);
  }

  private void initializeExpectedBucket(int optionsCalls) {
    expect(serviceMockReturnsOptions.getOptions()).andReturn(mockOptions).times(optionsCalls);
    replay(serviceMockReturnsOptions);
    expectedBucket = new StorageBucket(serviceMockReturnsOptions, new BucketInfo.BucketBuilderImpl(BUCKET_INFO));
    blobResults =
        ImmutableList.of(
            new StorageBlob(
                serviceMockReturnsOptions,
                new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n1").buildObject())),
            new StorageBlob(
                serviceMockReturnsOptions,
                new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n2").buildObject())),
            new StorageBlob(
                serviceMockReturnsOptions,
                new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n3").buildObject())));
  }

  private void initializeBucket() {
    bucket = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO));
  }

  @Test
  public void testExists_True() throws Exception {
    initializeExpectedBucket(4);
    CloudStorageClient.BucketGetOptions[] expectedOptions = {CloudStorageClient.BucketGetOptions.selectFields()};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName(), expectedOptions)).andReturn(expectedBucket);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.bucketExists());
  }

  @Test
  public void testExists_False() throws Exception {
    initializeExpectedBucket(4);
    CloudStorageClient.BucketGetOptions[] expectedOptions = {CloudStorageClient.BucketGetOptions.selectFields()};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName(), expectedOptions)).andReturn(null);
    replay(storage);
    initializeBucket();
    assertFalse(bucket.bucketExists());
  }

  @Test
  public void testReload() throws Exception {
    initializeExpectedBucket(5);
    BucketInfo updatedInfo = BUCKET_INFO.toBucketBuilder().setNotFoundPage("p").buildBucketInfo();
    StorageBucket expectedUpdatedBucket =
        new StorageBucket(serviceMockReturnsOptions, new BucketInfo.BucketBuilderImpl(updatedInfo));
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(updatedInfo.getName())).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = bucket.reloadFromStorage();
    assertEquals(expectedUpdatedBucket, updatedBucket);
  }

  @Test
  public void testReloadNull() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName())).andReturn(null);
    replay(storage);
    initializeBucket();
    assertNull(bucket.reloadFromStorage());
  }

  @Test
  public void testReloadWithOptions() throws Exception {
    initializeExpectedBucket(5);
    BucketInfo updatedInfo = BUCKET_INFO.toBucketBuilder().setNotFoundPage("p").buildBucketInfo();
    StorageBucket expectedUpdatedBucket =
        new StorageBucket(serviceMockReturnsOptions, new BucketInfo.BucketBuilderImpl(updatedInfo));
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(updatedInfo.getName(), CloudStorageClient.BucketGetOptions.ifMetagenerationMatch(42L)))
        .andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = bucket.reloadFromStorage(StorageBucket.BucketSourceOptionSpec.withMetagenerationMatch());
    assertEquals(expectedUpdatedBucket, updatedBucket);
  }

  @Test
  public void testUpdate() throws Exception {
    initializeExpectedBucket(5);
    StorageBucket expectedUpdatedBucket = expectedBucket.toBucketBuilder().setNotFoundPage("p").buildBucketInfo();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.updateBucket();
    assertEquals(expectedUpdatedBucket, actualUpdatedBucket);
  }

  @Test
  public void testDelete() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.delete(BUCKET_INFO.getName())).andReturn(true);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.deleteBucket());
  }

  @Test
  public void testList() throws Exception {
    initializeExpectedBucket(4);
    PageImpl<StorageBlob> expectedBlobPage = new PageImpl<>(null, "c", blobResults);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.list(BUCKET_INFO.getName())).andReturn(expectedBlobPage);
    replay(storage);
    initializeBucket();
    Page<StorageBlob> blobPage = bucket.listObjects();
    Iterator<StorageBlob> blobInfoIterator = blobPage.getValues().iterator();
    Iterator<StorageBlob> blobIterator = blobPage.getValues().iterator();
    while (blobInfoIterator.hasNext() && blobIterator.hasNext()) {
      assertEquals(blobInfoIterator.next(), blobIterator.next());
    }
    assertFalse(blobInfoIterator.hasNext());
    assertFalse(blobIterator.hasNext());
    assertEquals(expectedBlobPage.getNextPageToken(), blobPage.getNextPageToken());
  }

  @Test
  public void testGet() throws Exception {
    initializeExpectedBucket(5);
    StorageBlob expectedBlob =
        new StorageBlob(
            serviceMockReturnsOptions,
            new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n").buildObject()));
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BlobIdentifier.create(expectedBucket.getName(), "n"), new CloudStorageClient.BlobGetOptions[0]))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob = bucket.get("n");
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testGetAllArray() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    List<BlobIdentifier> blobIds =
        Lists.transform(
            blobResults,
            new Function<StorageBlob, BlobIdentifier>() {
              @Override
              public BlobIdentifier apply(StorageBlob blob) {
                return blob.getBlobId();
              }
            });
    expect(storage.get(blobIds)).andReturn(blobResults);
    replay(storage);
    initializeBucket();
    assertEquals(blobResults, bucket.get("n1", "n2", "n3"));
  }

  @Test
  public void testGetAllIterable() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    List<BlobIdentifier> blobIds =
        Lists.transform(
            blobResults,
            new Function<StorageBlob, BlobIdentifier>() {
              @Override
              public BlobIdentifier apply(StorageBlob blob) {
                return blob.getBlobId();
              }
            });
    expect(storage.get(blobIds)).andReturn(blobResults);
    replay(storage);
    initializeBucket();
    assertEquals(blobResults, bucket.get(ImmutableList.of("n1", "n2", "n3")));
  }

  @Test
  public void testCreate() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder("b", "n").setContentType(CONTENT_TYPE).buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content)).andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob = bucket.createBlob("n", content, CONTENT_TYPE);
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateNoContentType() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder("b", "n").buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content)).andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob = bucket.createBlob("n", content);
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithOptions() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobIdentifier.create("b", "n", 42L))
            .setContentType(CONTENT_TYPE)
            .setMetageneration(24L)
            .buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    CloudStorageClient.PredefinedAccessControlList acl = CloudStorageClient.PredefinedAccessControlList.ALL_AUTHENTICATED_USERS;
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.create(
                info,
                content,
                CloudStorageClient.BlobUploadOptions.ifGenerationMatch(),
                CloudStorageClient.BlobUploadOptions.ifMetagenerationMatch(),
                CloudStorageClient.BlobUploadOptions.withPredefinedAcl(acl),
                CloudStorageClient.BlobUploadOptions.customerSuppliedKey(BASE64_KEY),
                CloudStorageClient.BlobUploadOptions.withUserProject(USER_PROJECT)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob =
        bucket.createBlob(
            "n",
            content,
            CONTENT_TYPE,
            StorageBucket.BlobUploadOption.withGenerationMatch(42L),
            StorageBucket.BlobUploadOption.withMetagenerationMatch(24L),
            StorageBucket.BlobUploadOption.withPredefinedAcl(acl),
            StorageBucket.BlobUploadOption.withEncryptionKey(BASE64_KEY),
            StorageBucket.BlobUploadOption.withUserProject(USER_PROJECT));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithEncryptionKey() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder(BlobIdentifier.create("b", "n")).setContentType(CONTENT_TYPE).buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, CloudStorageClient.BlobUploadOptions.customerSuppliedKey(KEY)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob =
        bucket.createBlob("n", content, CONTENT_TYPE, StorageBucket.BlobUploadOption.withEncryptionKey(KEY));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithKmsKeyName() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder(BlobIdentifier.create("b", "n")).setContentType(CONTENT_TYPE).buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, CloudStorageClient.BlobUploadOptions.withKmsKeyName(DEFAULT_KMS_KEY_NAME)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob =
        bucket.createBlob(
            "n", content, CONTENT_TYPE, StorageBucket.BlobUploadOption.withKmsKeyName(DEFAULT_KMS_KEY_NAME));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateNotExists() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobIdentifier.create("b", "n", 0L)).setContentType(CONTENT_TYPE).buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, CloudStorageClient.BlobUploadOptions.ifGenerationMatch()))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob = bucket.createBlob("n", content, CONTENT_TYPE, StorageBucket.BlobUploadOption.doesNotExistOption());
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithWrongGenerationOptions() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    replay(storage);
    initializeBucket();
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    try {
      bucket.createBlob(
          "n",
          content,
          CONTENT_TYPE,
          StorageBucket.BlobUploadOption.withGenerationMatch(42L),
          StorageBucket.BlobUploadOption.withGenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testCreateWithWrongMetagenerationOptions() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    replay(storage);
    initializeBucket();
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    try {
      bucket.createBlob(
          "n",
          content,
          CONTENT_TYPE,
          StorageBucket.BlobUploadOption.withMetagenerationMatch(42L),
          StorageBucket.BlobUploadOption.withMetagenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStream() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder("b", "n").setContentType(CONTENT_TYPE).buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent)).andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob = bucket.createBlob("n", streamContent, CONTENT_TYPE);
    assertEquals(expectedBlob, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStreamNoContentType() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder("b", "n").buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent)).andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob = bucket.createBlob("n", streamContent);
    assertEquals(expectedBlob, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStreamWithOptions() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobIdentifier.create("b", "n", 42L))
            .setContentType(CONTENT_TYPE)
            .setMetageneration(24L)
            .setCrc32c("crc")
            .setMd5("md5")
            .buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    CloudStorageClient.PredefinedAccessControlList acl = CloudStorageClient.PredefinedAccessControlList.ALL_AUTHENTICATED_USERS;
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.create(
                info,
                streamContent,
                CloudStorageClient.BlobWriteOptions.ifGenerationMatch(),
                CloudStorageClient.BlobWriteOptions.ifMetagenerationMatch(),
                CloudStorageClient.BlobWriteOptions.withPredefinedAcl(acl),
                CloudStorageClient.BlobWriteOptions.ifCrc32cMatch(),
                CloudStorageClient.BlobWriteOptions.ifMd5Match(),
                CloudStorageClient.BlobWriteOptions.customerSuppliedKey(BASE64_KEY),
                CloudStorageClient.BlobWriteOptions.withUserProject(USER_PROJECT)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob =
        bucket.createBlob(
            "n",
            streamContent,
            CONTENT_TYPE,
            StorageBucket.BlobWriteOptions.withGenerationMatch(42L),
            StorageBucket.BlobWriteOptions.withMetagenerationMatch(24L),
            StorageBucket.BlobWriteOptions.withPredefinedAcl(acl),
            StorageBucket.BlobWriteOptions.crc32cMatchOption("crc"),
            StorageBucket.BlobWriteOptions.md5MatchOption("md5"),
            StorageBucket.BlobWriteOptions.withEncryptionKey(BASE64_KEY),
            StorageBucket.BlobWriteOptions.userProjectOption(USER_PROJECT));
    assertEquals(expectedBlob, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStreamWithEncryptionKey() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder(BlobIdentifier.create("b", "n")).setContentType(CONTENT_TYPE).buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent, CloudStorageClient.BlobWriteOptions.customerSuppliedKey(KEY)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob =
        bucket.createBlob("n", streamContent, CONTENT_TYPE, StorageBucket.BlobWriteOptions.withEncryptionKey(KEY));
    assertEquals(expectedBlob, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStreamNotExists() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobIdentifier.create("b", "n", 0L)).setContentType(CONTENT_TYPE).buildObject();
    StorageBlob expectedBlob = new StorageBlob(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent, CloudStorageClient.BlobWriteOptions.ifGenerationMatch()))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageBlob blob =
        bucket.createBlob("n", streamContent, CONTENT_TYPE, StorageBucket.BlobWriteOptions.doesNotExistOption());
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateFromStreamWithWrongGenerationOptions() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    replay(storage);
    initializeBucket();
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    try {
      bucket.createBlob(
          "n",
          streamContent,
          CONTENT_TYPE,
          StorageBucket.BlobWriteOptions.withGenerationMatch(42L),
          StorageBucket.BlobWriteOptions.withGenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testCreateFromStreamWithWrongMetagenerationOptions() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    replay(storage);
    initializeBucket();
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    try {
      bucket.createBlob(
          "n",
          streamContent,
          CONTENT_TYPE,
          StorageBucket.BlobWriteOptions.withMetagenerationMatch(42L),
          StorageBucket.BlobWriteOptions.withMetagenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testGetAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.getAcl(BUCKET_INFO.getName(), UserPrincipal.allAuthenticatedUsers())).andReturn(ACL);
    replay(storage);
    initializeBucket();
    assertEquals(ACL, bucket.getAcl(UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.deleteAcl(BUCKET_INFO.getName(), UserPrincipal.allAuthenticatedUsers()))
        .andReturn(true);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.removeAcl(UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
    expect(storage.createAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.addAcl(ACL));
  }

  @Test
  public void testUpdateAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
    expect(storage.updateAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.modifyAcl(ACL));
  }

  @Test
  public void testListAcls() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.listAcls(BUCKET_INFO.getName())).andReturn(ACLS);
    replay(storage);
    initializeBucket();
    assertEquals(ACLS, bucket.listAcl());
  }

  @Test
  public void testGetDefaultAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.getDefaultAcl(BUCKET_INFO.getName(), UserPrincipal.allAuthenticatedUsers()))
        .andReturn(ACL);
    replay(storage);
    initializeBucket();
    assertEquals(ACL, bucket.getDefaultAcl(UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteDefaultAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.deleteDefaultAcl(BUCKET_INFO.getName(), UserPrincipal.allAuthenticatedUsers()))
        .andReturn(true);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.removeDefaultAcl(UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateDefaultAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
    expect(storage.createDefaultAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.addDefaultAcl(ACL));
  }

  @Test
  public void testUpdateDefaultAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
    expect(storage.updateDefaultAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.modifyDefaultAcl(ACL));
  }

  @Test
  public void testListDefaultAcls() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.listDefaultAcls(BUCKET_INFO.getName())).andReturn(ACLS);
    replay(storage);
    initializeBucket();
    assertEquals(ACLS, bucket.listDefaultAcl());
  }

  @Test
  public void testLockRetention() throws Exception {
    initializeExpectedBucket(5);
    StorageBucket expectedRetentionLockedBucket =
        expectedBucket
            .toBucketBuilder()
            .setRetentionPeriod(RETENTION_PERIOD)
            .setRetentionPolicyIsLocked(true)
            .buildBucketInfo();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(
            storage.lockRetentionPolicy(
                expectedRetentionLockedBucket,
                CloudStorageClient.BucketTargetOptions.ifMetagenerationMatch(),
                CloudStorageClient.BucketTargetOptions.withUserProject(USER_PROJECT)))
        .andReturn(expectedRetentionLockedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket lockedRetentionPolicyBucket =
        new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(expectedRetentionLockedBucket));
    StorageBucket actualRetentionLockedBucket =
        lockedRetentionPolicyBucket.lockRetention(
            CloudStorageClient.BucketTargetOptions.ifMetagenerationMatch(),
            CloudStorageClient.BucketTargetOptions.withUserProject(USER_PROJECT));
    assertEquals(expectedRetentionLockedBucket, actualRetentionLockedBucket);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testToBuilder() {
    expect(storage.getOptions()).andReturn(mockOptions).times(4);
    replay(storage);
    StorageBucket fullBucket = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(FULL_BUCKET_INFO));
    assertEquals(fullBucket, fullBucket.toBucketBuilder().buildBucketInfo());
    StorageBucket simpleBlob = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO));
    assertEquals(simpleBlob, simpleBlob.toBucketBuilder().buildBucketInfo());
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testBuilder() {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions).times(4);
    replay(storage);
    StorageBucket.BucketInfoBuilder builder =
        new StorageBucket.BucketInfoBuilder(new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO)));
    StorageBucket bucket =
        builder
            .setAcl(ACLS)
            .setEtag(ETAG)
            .setGeneratedId(GENERATED_ID)
            .setMetageneration(META_GENERATION)
            .setOwner(OWNER)
            .setSelfLink(SELF_LINK)
            .setCors(CORS)
            .setCreateTime(CREATE_TIME)
            .setUpdateTime(UPDATE_TIME)
            .setDefaultAcl(DEFAULT_ACL)
            .setDeleteRules(DELETE_RULES)
            .setLifecycleRules(LIFECYCLE_RULES)
            .setIndexPage(INDEX_PAGE)
            .setNotFoundPage(NOT_FOUND_PAGE)
            .setLocation(LOCATION)
            .setLocationType(LOCATION_TYPE)
            .setStorageClass(STORAGE_CLASS)
            .setVersioningEnabled(VERSIONING_ENABLED)
            .setLabels(BUCKET_LABELS)
            .setRequesterPays(REQUESTER_PAYS)
            .setDefaultKmsKeyName(DEFAULT_KMS_KEY_NAME)
            .setDefaultEventBasedHold(DEFAULT_EVENT_BASED_HOLD)
            .setRetentionEffectiveTime(RETENTION_EFFECTIVE_TIME)
            .setRetentionPeriod(RETENTION_PERIOD)
            .setRetentionPolicyIsLocked(RETENTION_POLICY_IS_LOCKED)
            .buildBucketInfo();
    assertEquals("b", bucket.getName());
    assertEquals(ACLS, bucket.getAcl());
    assertEquals(ETAG, bucket.getEtag());
    assertEquals(GENERATED_ID, bucket.getGeneratedId());
    assertEquals(META_GENERATION, bucket.getMetageneration());
    assertEquals(OWNER, bucket.getOwner());
    assertEquals(SELF_LINK, bucket.getSelfLink());
    assertEquals(CREATE_TIME, bucket.getCreateTime());
    assertEquals(UPDATE_TIME, bucket.getUpdateTime());
    assertEquals(CORS, bucket.getCors());
    assertEquals(DEFAULT_ACL, bucket.getDefaultAcl());
    assertEquals(DELETE_RULES, bucket.getDeleteRules());
    assertEquals(LIFECYCLE_RULES, bucket.getLifecycleRules());
    assertEquals(INDEX_PAGE, bucket.getIndexPage());
    assertEquals(NOT_FOUND_PAGE, bucket.getNotFoundPage());
    assertEquals(LOCATION, bucket.getLocation());
    assertEquals(STORAGE_CLASS, bucket.getStorageClass());
    assertEquals(VERSIONING_ENABLED, bucket.isVersioningEnabled());
    assertEquals(BUCKET_LABELS, bucket.getLabels());
    assertEquals(REQUESTER_PAYS, bucket.isRequesterPays());
    assertEquals(DEFAULT_KMS_KEY_NAME, bucket.getDefaultKmsKeyName());
    assertEquals(DEFAULT_EVENT_BASED_HOLD, bucket.getDefaultEventBasedHold());
    assertEquals(RETENTION_EFFECTIVE_TIME, bucket.getRetentionEffectiveTime());
    assertEquals(RETENTION_PERIOD, bucket.getRetentionPeriod());
    assertEquals(RETENTION_POLICY_IS_LOCKED, bucket.isRetentionPolicyLocked());
    assertEquals(storage.getOptions(), bucket.getStorage().getOptions());
    assertTrue(LOCATION_TYPES.contains(LOCATION_TYPE));
  }

  @Test
  public void testDeleteLifecycleRules() {
    initializeExpectedBucket(6);
    StorageBucket bucket =
        new StorageBucket(serviceMockReturnsOptions, new BucketInfo.BucketBuilderImpl(FULL_BUCKET_INFO));
    assertThat(bucket.getLifecycleRules()).hasSize(1);
    StorageBucket expectedUpdatedBucket = bucket.toBucketBuilder().deleteLifecycleRules().buildBucketInfo();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.updateBucket();
    assertThat(actualUpdatedBucket.getLifecycleRules()).hasSize(0);
  }

  @Test
  public void testUpdateBucketLogging() {
    initializeExpectedBucket(6);
    BucketInfo.LoggingConfig logging =
        BucketInfo.LoggingConfig.newLogConfigBuilder()
            .setLogBucket("logs-bucket")
            .setLogObjectPrefix("test-logs")
            .buildLoggingConfig();
    BucketInfo bucketInfo = BucketInfo.newBucketBuilder("b").setLogging(logging).buildBucketInfo();
    StorageBucket bucket = new StorageBucket(serviceMockReturnsOptions, new BucketInfo.BucketBuilderImpl(bucketInfo));
    assertThat(bucket.getLogging().getLogBucket()).isEqualTo("logs-bucket");
    assertThat(bucket.getLogging().getLogObjectPrefix()).isEqualTo("test-logs");
    StorageBucket expectedUpdatedBucket = bucket.toBucketBuilder().setLogging(null).buildBucketInfo();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.updateBucket();
    assertThat(actualUpdatedBucket.getLogging().getLogBucket()).isNull();
    assertThat(actualUpdatedBucket.getLogging().getLogObjectPrefix()).isNull();
  }

  @Test
  public void testRemoveBucketCORS() {
    initializeExpectedBucket(6);
    List<Cors.Origin> origins = ImmutableList.of(Cors.Origin.of("http://cloud.google.com"));
    List<HttpRequestMethod> httpMethods = ImmutableList.of(HttpRequestMethod.GET);
    List<String> responseHeaders = ImmutableList.of("Content-Type");
    Cors cors =
        Cors.newBuilder()
            .setOrigins(origins)
            .setMethods(httpMethods)
            .setResponseHeaders(responseHeaders)
            .setMaxAgeSeconds(100)
            .build();
    BucketInfo bucketInfo = BucketInfo.newBucketBuilder("b").setCors(ImmutableList.of(cors)).buildBucketInfo();
    StorageBucket bucket = new StorageBucket(serviceMockReturnsOptions, new BucketInfo.BucketBuilderImpl(bucketInfo));
    assertThat(bucket.getCors()).isNotNull();
    assertThat(bucket.getCors().get(0).getMaxAgeSeconds()).isEqualTo(100);
    assertThat(bucket.getCors().get(0).getMethods()).isEqualTo(httpMethods);
    assertThat(bucket.getCors().get(0).getOrigins()).isEqualTo(origins);
    assertThat(bucket.getCors().get(0).getResponseHeaders()).isEqualTo(responseHeaders);

    // Remove bucket CORS configuration.
    StorageBucket expectedUpdatedBucket = bucket.toBucketBuilder().setCors(null).buildBucketInfo();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.updateBucket();
    assertThat(actualUpdatedBucket.getCors()).isEmpty();
  }
}
