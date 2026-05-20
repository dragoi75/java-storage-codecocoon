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

import static com.google.cloud.storage.AccessControlEntry.RoleType.WRITER;
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
import com.google.cloud.storage.AccessControlEntry.RoleType;
import com.google.cloud.storage.AccessControlEntry.UserIdentity;
import com.google.cloud.storage.BucketMetadata.DaysToLiveRule;
import com.google.cloud.storage.BucketMetadata.DeletionRule;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition;
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

  private static final AccessControlEntry ACL = AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), RoleType.OWNER);
  private static final AccessControlEntry OTHER_ACL = AccessControlEntry.create(new AccessControlEntry.ProjectInfo(AccessControlEntry.ProjectInfo.ProjectMemberRole.OWNERS, "p"), RoleType.READER);
  private static final List<AccessControlEntry> ACLS = ImmutableList.of(ACL, OTHER_ACL);
  private static final String ETAG = "0xFF00";
  private static final String GENERATED_ID = "B/N:1";
  private static final Long META_GENERATION = 10L;
  private static final UserIdentity OWNER = new UserIdentity("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long CREATE_TIME = System.currentTimeMillis();
  private static final Long UPDATE_TIME = CREATE_TIME - 1L;
  private static final List<CorsConfiguration> CORS = Collections.singletonList(CorsConfiguration.newCorsConfigurationBuilder().buildCorsConfiguration());
  private static final List<AccessControlEntry> DEFAULT_ACL =
      Collections.singletonList(AccessControlEntry.create(AccessControlEntry.UserIdentity.allAuthenticatedUsers(), WRITER));

  @SuppressWarnings({"unchecked", "deprecation"})
  private static final List<? extends DeletionRule> DELETE_RULES =
      Collections.singletonList(new DaysToLiveRule(5));

  private static final List<? extends LifecycleRuleDefinition> LIFECYCLE_RULES =
      Collections.singletonList(
          new BucketMetadata.LifecycleRuleDefinition(
              BucketMetadata.LifecycleRuleDefinition.LifecycleRuleAction.newRemoveAction(),
              LifecycleRuleCondition.builder().setAge(5).buildCondition()));
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
  private static final BucketMetadata FULL_BUCKET_INFO =
      BucketMetadata.newBucketBuilder("b")
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
          .buildBucket();

  private static final BucketMetadata BUCKET_INFO =
      BucketMetadata.newBucketBuilder("b").setMetageneration(42L).buildBucket();
  private static final String CONTENT_TYPE = "text/plain";
  private static final String BASE64_KEY = "JVzfVl8NLD9FjedFuStegjRfES5ll5zc59CIXw572OA=";
  private static final Key KEY =
      new SecretKeySpec(BaseEncoding.base64().decode(BASE64_KEY), "AES256");
  private final RetryAlgorithmManager retryAlgorithmManager =
      StorageClientOptions.getDefaultInstance().getRetryAlgorithmManager();

  private Storage storage;
  private Storage serviceMockReturnsOptions = createMock(Storage.class);
  private StorageClientOptions mockOptions = createMock(StorageClientOptions.class);
  private StorageBucket bucket;
  private StorageBucket expectedBucket;
  private List<StorageObject> blobResults;

  @Before
  public void setUp() {
    storage = createStrictMock(Storage.class);
  }

  @After
  public void tearDown() throws Exception {
    verify(storage);
  }

  private void initializeExpectedBucket() {
    expect(serviceMockReturnsOptions.getOptions()).andReturn(mockOptions).anyTimes();
    replay(serviceMockReturnsOptions);
    expect(mockOptions.getRetryAlgorithmManager()).andReturn(retryAlgorithmManager).anyTimes();
    replay(mockOptions);
    expectedBucket = new StorageBucket(serviceMockReturnsOptions, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO));
    blobResults =
        ImmutableList.of(
            new StorageObject(
                serviceMockReturnsOptions,
                new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n1").buildObject())),
            new StorageObject(
                serviceMockReturnsOptions,
                new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n2").buildObject())),
            new StorageObject(
                serviceMockReturnsOptions,
                new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n3").buildObject())));
  }

  private void initializeBucket() {
    bucket = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO));
  }

  @Test
  public void testExists_True() throws Exception {
    initializeExpectedBucket();
    Storage.GetBucketOption[] expectedOptions = {Storage.GetBucketOption.withFields()};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName(), expectedOptions)).andReturn(expectedBucket);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.bucketExists());
  }

  @Test
  public void testExists_False() throws Exception {
    initializeExpectedBucket();
    Storage.GetBucketOption[] expectedOptions = {Storage.GetBucketOption.withFields()};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName(), expectedOptions)).andReturn(null);
    replay(storage);
    initializeBucket();
    assertFalse(bucket.bucketExists());
  }

  @Test
  public void testReload() throws Exception {
    initializeExpectedBucket();
    BucketMetadata updatedInfo = BUCKET_INFO.asBuilder().setNotFoundPage("p").buildBucket();
    StorageBucket expectedUpdatedBucket =
        new StorageBucket(serviceMockReturnsOptions, new BucketMetadata.BucketBuilderImpl(updatedInfo));
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(updatedInfo.getName())).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = bucket.refresh();
    assertEquals(expectedUpdatedBucket, updatedBucket);
  }

  @Test
  public void testReloadNull() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName())).andReturn(null);
    replay(storage);
    initializeBucket();
    assertNull(bucket.refresh());
  }

  @Test
  public void testReloadWithOptions() throws Exception {
    initializeExpectedBucket();
    BucketMetadata updatedInfo = BUCKET_INFO.asBuilder().setNotFoundPage("p").buildBucket();
    StorageBucket expectedUpdatedBucket =
        new StorageBucket(serviceMockReturnsOptions, new BucketMetadata.BucketBuilderImpl(updatedInfo));
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(updatedInfo.getName(), Storage.GetBucketOption.withMetagenerationMatch(42L)))
        .andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = bucket.refresh(StorageBucket.BucketSourceOptions.ifMetagenerationMatch());
    assertEquals(expectedUpdatedBucket, updatedBucket);
  }

  @Test
  public void testUpdate() throws Exception {
    initializeExpectedBucket();
    StorageBucket expectedUpdatedBucket = expectedBucket.asBuilder().setNotFoundPage("p").buildBucket();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.updateBucket();
    assertEquals(expectedUpdatedBucket, actualUpdatedBucket);
  }

  @Test
  public void testDelete() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.delete(BUCKET_INFO.getName())).andReturn(true);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.deleteBucket());
  }

  @Test
  public void testList() throws Exception {
    initializeExpectedBucket();
    PageImpl<StorageObject> expectedBlobPage = new PageImpl<>(null, "c", blobResults);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.list(BUCKET_INFO.getName())).andReturn(expectedBlobPage);
    replay(storage);
    initializeBucket();
    Page<StorageObject> blobPage = bucket.listObjects();
    Iterator<StorageObject> blobInfoIterator = blobPage.getValues().iterator();
    Iterator<StorageObject> blobIterator = blobPage.getValues().iterator();
    while (blobInfoIterator.hasNext() && blobIterator.hasNext()) {
      assertEquals(blobInfoIterator.next(), blobIterator.next());
    }
    assertFalse(blobInfoIterator.hasNext());
    assertFalse(blobIterator.hasNext());
    assertEquals(expectedBlobPage.getNextPageToken(), blobPage.getNextPageToken());
  }

  @Test
  public void testGet() throws Exception {
    initializeExpectedBucket();
    StorageObject expectedBlob =
        new StorageObject(
            serviceMockReturnsOptions,
            new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n").buildObject()));
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BlobId.from(expectedBucket.getName(), "n"), new Storage.BlobGetOptions[0]))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob = bucket.get("n");
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testGetAllArray() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    List<BlobId> blobIds =
        Lists.transform(
            blobResults,
            new Function<StorageObject, BlobId>() {
              @Override
              public BlobId apply(StorageObject blob) {
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
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    List<BlobId> blobIds =
        Lists.transform(
            blobResults,
            new Function<StorageObject, BlobId>() {
              @Override
              public BlobId apply(StorageObject blob) {
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
    initializeExpectedBucket();
    BlobMetadata info = BlobMetadata.newBuilder("b", "n").setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content)).andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob = bucket.createBlob("n", content, CONTENT_TYPE);
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateNoContentType() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info = BlobMetadata.newBuilder("b", "n").buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content)).andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob = bucket.createBlob("n", content);
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithOptions() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobId.from("b", "n", 42L))
            .setContentType(CONTENT_TYPE)
            .setMetageneration(24L)
            .buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    Storage.PredefinedAccessControlList acl = Storage.PredefinedAccessControlList.ALL_AUTHENTICATED_USERS;
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.create(
                info,
                content,
                Storage.BlobUploadOption.withGenerationMatch(),
                Storage.BlobUploadOption.withMetagenerationMatch(),
                Storage.BlobUploadOption.withPredefinedAcl(acl),
                Storage.BlobUploadOption.withEncryptionKey(BASE64_KEY),
                Storage.BlobUploadOption.withUserProject(USER_PROJECT)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob(
            "n",
            content,
            CONTENT_TYPE,
            StorageBucket.BlobTargetOptions.ifGenerationMatch(42L),
            StorageBucket.BlobTargetOptions.ifMetagenerationMatch(24L),
            StorageBucket.BlobTargetOptions.withPredefinedAcl(acl),
            StorageBucket.BlobTargetOptions.withEncryptionKey(BASE64_KEY),
            StorageBucket.BlobTargetOptions.withUserProject(USER_PROJECT));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithEncryptionKey() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info = BlobMetadata.newBuilder(BlobId.from("b", "n")).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, Storage.BlobUploadOption.withEncryptionKey(KEY)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob("n", content, CONTENT_TYPE, StorageBucket.BlobTargetOptions.withEncryptionKey(KEY));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithKmsKeyName() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info = BlobMetadata.newBuilder(BlobId.from("b", "n")).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, Storage.BlobUploadOption.withKmsKeyName(DEFAULT_KMS_KEY_NAME)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob(
            "n", content, CONTENT_TYPE, StorageBucket.BlobTargetOptions.withKmsKeyName(DEFAULT_KMS_KEY_NAME));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateNotExists() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobId.from("b", "n", 0L)).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, Storage.BlobUploadOption.withGenerationMatch()))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob = bucket.createBlob("n", content, CONTENT_TYPE, StorageBucket.BlobTargetOptions.ifDoesNotExist());
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithWrongGenerationOptions() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    replay(storage);
    initializeBucket();
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    try {
      bucket.createBlob(
          "n",
          content,
          CONTENT_TYPE,
          StorageBucket.BlobTargetOptions.ifGenerationMatch(42L),
          StorageBucket.BlobTargetOptions.ifGenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testCreateWithWrongMetagenerationOptions() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    replay(storage);
    initializeBucket();
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    try {
      bucket.createBlob(
          "n",
          content,
          CONTENT_TYPE,
          StorageBucket.BlobTargetOptions.ifMetagenerationMatch(42L),
          StorageBucket.BlobTargetOptions.ifMetagenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStream() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info = BlobMetadata.newBuilder("b", "n").setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent)).andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob = bucket.createBlob("n", streamContent, CONTENT_TYPE);
    assertEquals(expectedBlob, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStreamNoContentType() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info = BlobMetadata.newBuilder("b", "n").buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent)).andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob = bucket.createBlob("n", streamContent);
    assertEquals(expectedBlob, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStreamWithOptions() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobId.from("b", "n", 42L))
            .setContentType(CONTENT_TYPE)
            .setMetageneration(24L)
            .setCrc32c("crc")
            .setMd5("md5")
            .buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    Storage.PredefinedAccessControlList acl = Storage.PredefinedAccessControlList.ALL_AUTHENTICATED_USERS;
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.create(
                info,
                streamContent,
                Storage.BlobWriteOptions.withGenerationMatch(),
                Storage.BlobWriteOptions.ifMetagenerationMatch(),
                Storage.BlobWriteOptions.withPredefinedAcl(acl),
                Storage.BlobWriteOptions.ifCrc32cMatch(),
                Storage.BlobWriteOptions.ifMd5Match(),
                Storage.BlobWriteOptions.customerSuppliedKey(BASE64_KEY),
                Storage.BlobWriteOptions.withUserProject(USER_PROJECT)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob(
            "n",
            streamContent,
            CONTENT_TYPE,
            StorageBucket.BlobWriteOptions.ifGenerationMatch(42L),
            StorageBucket.BlobWriteOptions.ifMetagenerationMatch(24L),
            StorageBucket.BlobWriteOptions.withPredefinedAcl(acl),
            StorageBucket.BlobWriteOptions.ifCrc32cMatch("crc"),
            StorageBucket.BlobWriteOptions.ifMd5Match("md5"),
            StorageBucket.BlobWriteOptions.withEncryptionKey(BASE64_KEY),
            StorageBucket.BlobWriteOptions.withUserProject(USER_PROJECT));
    assertEquals(expectedBlob, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStreamWithEncryptionKey() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info = BlobMetadata.newBuilder(BlobId.from("b", "n")).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent, Storage.BlobWriteOptions.customerSuppliedKey(KEY)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob("n", streamContent, CONTENT_TYPE, StorageBucket.BlobWriteOptions.withEncryptionKey(KEY));
    assertEquals(expectedBlob, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateFromStreamNotExists() throws Exception {
    initializeExpectedBucket();
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobId.from("b", "n", 0L)).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent, Storage.BlobWriteOptions.withGenerationMatch()))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob("n", streamContent, CONTENT_TYPE, StorageBucket.BlobWriteOptions.ifDoesNotExist());
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateFromStreamWithWrongGenerationOptions() throws Exception {
    initializeExpectedBucket();
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
          StorageBucket.BlobWriteOptions.ifGenerationMatch(42L),
          StorageBucket.BlobWriteOptions.ifGenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testCreateFromStreamWithWrongMetagenerationOptions() throws Exception {
    initializeExpectedBucket();
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
          StorageBucket.BlobWriteOptions.ifMetagenerationMatch(42L),
          StorageBucket.BlobWriteOptions.ifMetagenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testGetAcl() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.getAcl(BUCKET_INFO.getName(), AccessControlEntry.UserIdentity.allAuthenticatedUsers())).andReturn(ACL);
    replay(storage);
    initializeBucket();
    assertEquals(ACL, bucket.getAcl(AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteAcl() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.deleteAcl(BUCKET_INFO.getName(), AccessControlEntry.UserIdentity.allAuthenticatedUsers()))
        .andReturn(true);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.removeAcl(UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateAcl() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    expect(storage.createAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.addAcl(ACL));
  }

  @Test
  public void testUpdateAcl() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    expect(storage.updateAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.updateAccessControl(ACL));
  }

  @Test
  public void testListAcls() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.listAcls(BUCKET_INFO.getName())).andReturn(ACLS);
    replay(storage);
    initializeBucket();
    assertEquals(ACLS, bucket.listAcl());
  }

  @Test
  public void testGetDefaultAcl() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.getDefaultAcl(BUCKET_INFO.getName(), AccessControlEntry.UserIdentity.allAuthenticatedUsers()))
        .andReturn(ACL);
    replay(storage);
    initializeBucket();
    assertEquals(ACL, bucket.getDefaultAcl(UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteDefaultAcl() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.deleteDefaultAcl(BUCKET_INFO.getName(), AccessControlEntry.UserIdentity.allAuthenticatedUsers()))
        .andReturn(true);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.removeDefaultAcl(UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateDefaultAcl() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    expect(storage.createDefaultAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.addDefaultAcl(ACL));
  }

  @Test
  public void testUpdateDefaultAcl() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    expect(storage.updateDefaultAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.updateDefaultAccessControl(ACL));
  }

  @Test
  public void testListDefaultAcls() throws Exception {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.listDefaultAcls(BUCKET_INFO.getName())).andReturn(ACLS);
    replay(storage);
    initializeBucket();
    assertEquals(ACLS, bucket.listDefaultAcl());
  }

  @Test
  public void testLockRetention() throws Exception {
    initializeExpectedBucket();
    StorageBucket expectedRetentionLockedBucket =
        expectedBucket
            .asBuilder()
            .setRetentionPeriod(RETENTION_PERIOD)
            .setRetentionPolicyIsLocked(true)
            .buildBucket();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(
            storage.lockRetentionPolicy(
                expectedRetentionLockedBucket,
                Storage.BucketTargetOptions.withMetagenerationMatch(),
                Storage.BucketTargetOptions.withUserProject(USER_PROJECT)))
        .andReturn(expectedRetentionLockedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket lockedRetentionPolicyBucket =
        new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(expectedRetentionLockedBucket));
    StorageBucket actualRetentionLockedBucket =
        lockedRetentionPolicyBucket.lockRetention(
            Storage.BucketTargetOptions.withMetagenerationMatch(),
            Storage.BucketTargetOptions.withUserProject(USER_PROJECT));
    assertEquals(expectedRetentionLockedBucket, actualRetentionLockedBucket);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testToBuilder() {
    expect(storage.getOptions()).andReturn(mockOptions).times(4);
    replay(storage);
    StorageBucket fullBucket = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(FULL_BUCKET_INFO));
    assertEquals(fullBucket, fullBucket.asBuilder().buildBucket());
    StorageBucket simpleBlob = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO));
    assertEquals(simpleBlob, simpleBlob.asBuilder().buildBucket());
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testBuilder() {
    initializeExpectedBucket();
    expect(storage.getOptions()).andReturn(mockOptions).times(4);
    replay(storage);
    StorageBucket.BucketInfoBuilder builder =
        new StorageBucket.BucketInfoBuilder(new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO)));
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
            .buildBucket();
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
    initializeExpectedBucket();
    StorageBucket bucket =
        new StorageBucket(serviceMockReturnsOptions, new BucketMetadata.BucketBuilderImpl(FULL_BUCKET_INFO));
    assertThat(bucket.getLifecycleRules()).hasSize(1);
    StorageBucket expectedUpdatedBucket = bucket.asBuilder().clearLifecycleRules().buildBucket();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.updateBucket();
    assertThat(actualUpdatedBucket.getLifecycleRules()).hasSize(0);
  }

  @Test
  public void testUpdateBucketLogging() {
    initializeExpectedBucket();
    BucketMetadata.LoggingConfig logging =
        BucketMetadata.LoggingConfig.builder()
            .setLogBucket("logs-bucket")
            .setLogObjectPrefix("test-logs")
            .buildConfig();
    BucketMetadata bucketInfo = BucketMetadata.newBucketBuilder("b").setLogging(logging).buildBucket();
    StorageBucket bucket = new StorageBucket(serviceMockReturnsOptions, new BucketMetadata.BucketBuilderImpl(bucketInfo));
    assertThat(bucket.getLogging().getLogBucket()).isEqualTo("logs-bucket");
    assertThat(bucket.getLogging().getLogObjectPrefix()).isEqualTo("test-logs");
    StorageBucket expectedUpdatedBucket = bucket.asBuilder().setLogging(null).buildBucket();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.updateBucket();
    assertThat(actualUpdatedBucket.getLogging().getLogBucket()).isNull();
    assertThat(actualUpdatedBucket.getLogging().getLogObjectPrefix()).isNull();
  }

  @Test
  public void testRemoveBucketCORS() {
    initializeExpectedBucket();
    List<CorsConfiguration.OriginValue> origins = ImmutableList.of(CorsConfiguration.OriginValue.from("http://cloud.google.com"));
    List<HttpRequestMethod> httpMethods = ImmutableList.of(HttpRequestMethod.GET);
    List<String> responseHeaders = ImmutableList.of("Content-Type");
    CorsConfiguration cors =
        CorsConfiguration.newCorsConfigurationBuilder()
            .setOrigins(origins)
            .setMethods(httpMethods)
            .setResponseHeaders(responseHeaders)
            .setMaxAgeSeconds(100)
            .buildCorsConfiguration();
    BucketMetadata bucketInfo = BucketMetadata.newBucketBuilder("b").setCors(ImmutableList.of(cors)).buildBucket();
    StorageBucket bucket = new StorageBucket(serviceMockReturnsOptions, new BucketMetadata.BucketBuilderImpl(bucketInfo));
    assertThat(bucket.getCors()).isNotNull();
    assertThat(bucket.getCors().get(0).getMaxAgeSeconds()).isEqualTo(100);
    assertThat(bucket.getCors().get(0).getMethods()).isEqualTo(httpMethods);
    assertThat(bucket.getCors().get(0).getOrigins()).isEqualTo(origins);
    assertThat(bucket.getCors().get(0).getResponseHeaders()).isEqualTo(responseHeaders);

    // Remove bucket CORS configuration.
    StorageBucket expectedUpdatedBucket = bucket.asBuilder().setCors(null).buildBucket();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.updateBucket();
    assertThat(actualUpdatedBucket.getCors()).isEmpty();
  }
}
