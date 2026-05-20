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

import static com.google.cloud.storage.AccessControlEntry.AccessRole.WRITER;
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
import com.google.cloud.storage.AccessControlEntry.AccessRole;
import com.google.cloud.storage.AccessControlEntry.UserPrincipal;
import com.google.cloud.storage.BucketMetadata.AgeBasedDeletionRule;
import com.google.cloud.storage.BucketMetadata.DeletionRule;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.AbstractLifecycleAction;
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

  private static final AccessControlEntry ACL = AccessControlEntry.create(UserPrincipal.allAuthenticatedUsers(), AccessRole.OWNER);
  private static final AccessControlEntry OTHER_ACL = AccessControlEntry.create(new AccessControlEntry.ProjectInfo(AccessControlEntry.ProjectInfo.ProjectRoleType.OWNERS, "p"), AccessControlEntry.AccessRole.READER);
  private static final List<AccessControlEntry> ACLS = ImmutableList.of(ACL, OTHER_ACL);
  private static final String ETAG = "0xFF00";
  private static final String GENERATED_ID = "B/N:1";
  private static final Long META_GENERATION = 10L;
  private static final UserPrincipal OWNER = new UserPrincipal("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long CREATE_TIME = System.currentTimeMillis();
  private static final List<CorsConfiguration> CORS = Collections.singletonList(CorsConfiguration.createBuilder().create());
  private static final List<AccessControlEntry> DEFAULT_ACL =
      Collections.singletonList(AccessControlEntry.create(UserPrincipal.allAuthenticatedUsers(), WRITER));
  private static final List<? extends DeletionRule> DELETE_RULES =
      Collections.singletonList(new AgeBasedDeletionRule(5));
  private static final List<? extends BucketMetadata.LifecycleRuleDefinition> LIFECYCLE_RULES =
      Collections.singletonList(
          new BucketMetadata.LifecycleRuleDefinition(
              AbstractLifecycleAction.newRemoveAction(),
              BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(5).buildLifecycleCondition()));
  private static final String INDEX_PAGE = "index.html";
  private static final String NOT_FOUND_PAGE = "error.html";
  private static final String LOCATION = "ASIA";
  private static final StorageClassType STORAGE_CLASS = StorageClassType.STANDARD;
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
          .buildInstance();
  private static final BucketMetadata BUCKET_INFO =
      BucketMetadata.newBucketBuilder("b").setMetageneration(42L).buildInstance();
  private static final String CONTENT_TYPE = "text/plain";
  private static final String BASE64_KEY = "JVzfVl8NLD9FjedFuStegjRfES5ll5zc59CIXw572OA=";
  private static final Key KEY =
      new SecretKeySpec(BaseEncoding.base64().decode(BASE64_KEY), "AES256");

  private StorageService storage;
  private StorageService serviceMockReturnsOptions = createMock(StorageService.class);
  private StorageSettings mockOptions = createMock(StorageSettings.class);
  private StorageBucket bucket;
  private StorageBucket expectedBucket;
  private List<StorageObject> blobResults;

  @Before
  public void setUp() {
    storage = createStrictMock(StorageService.class);
  }

  @After
  public void tearDown() throws Exception {
    verify(storage);
  }

  private void initializeExpectedBucket(int optionsCalls) {
    expect(serviceMockReturnsOptions.getOptions()).andReturn(mockOptions).times(optionsCalls);
    replay(serviceMockReturnsOptions);
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
    initializeExpectedBucket(4);
    StorageService.GetBucketOption[] expectedOptions = {StorageService.GetBucketOption.selectFields()};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName(), expectedOptions)).andReturn(expectedBucket);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.bucketExists());
  }

  @Test
  public void testExists_False() throws Exception {
    initializeExpectedBucket(4);
    StorageService.GetBucketOption[] expectedOptions = {StorageService.GetBucketOption.selectFields()};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName(), expectedOptions)).andReturn(null);
    replay(storage);
    initializeBucket();
    assertFalse(bucket.bucketExists());
  }

  @Test
  public void testReload() throws Exception {
    initializeExpectedBucket(5);
    BucketMetadata updatedInfo = BUCKET_INFO.toBucketBuilder().setNotFoundPage("p").buildInstance();
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
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BUCKET_INFO.getName())).andReturn(null);
    replay(storage);
    initializeBucket();
    assertNull(bucket.refresh());
  }

  @Test
  public void testReloadWithOptions() throws Exception {
    initializeExpectedBucket(5);
    BucketMetadata updatedInfo = BUCKET_INFO.toBucketBuilder().setNotFoundPage("p").buildInstance();
    StorageBucket expectedUpdatedBucket =
        new StorageBucket(serviceMockReturnsOptions, new BucketMetadata.BucketBuilderImpl(updatedInfo));
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(updatedInfo.getName(), StorageService.GetBucketOption.ifMetagenerationMatch(42L)))
        .andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = bucket.refresh(StorageBucket.BucketSourceOptionConfig.ifMetagenerationMatch());
    assertEquals(expectedUpdatedBucket, updatedBucket);
  }

  @Test
  public void testUpdate() throws Exception {
    initializeExpectedBucket(5);
    StorageBucket expectedUpdatedBucket = expectedBucket.toBucketBuilder().setNotFoundPage("p").buildInstance();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(storage.update(expectedUpdatedBucket)).andReturn(expectedUpdatedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket updatedBucket = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(expectedUpdatedBucket));
    StorageBucket actualUpdatedBucket = updatedBucket.modify();
    assertEquals(expectedUpdatedBucket, actualUpdatedBucket);
  }

  @Test
  public void testDelete() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.delete(BUCKET_INFO.getName())).andReturn(true);
    replay(storage);
    initializeBucket();
    assertTrue(bucket.remove());
  }

  @Test
  public void testList() throws Exception {
    initializeExpectedBucket(4);
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
    initializeExpectedBucket(5);
    StorageObject expectedBlob =
        new StorageObject(
            serviceMockReturnsOptions,
            new BlobMetadata.BlobInfoBuilderImpl(BlobMetadata.newBuilder("b", "n").buildObject()));
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.get(BlobIdentifier.create(expectedBucket.getName(), "n"), new StorageService.BlobFetchOption[0]))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob = bucket.get("n");
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testGetAllArray() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    List<BlobIdentifier> blobIds =
        Lists.transform(
            blobResults,
            new Function<StorageObject, BlobIdentifier>() {
              @Override
              public BlobIdentifier apply(StorageObject blob) {
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
            new Function<StorageObject, BlobIdentifier>() {
              @Override
              public BlobIdentifier apply(StorageObject blob) {
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
    initializeExpectedBucket(5);
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
    initializeExpectedBucket(5);
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobIdentifier.create("b", "n", 42L))
            .setContentType(CONTENT_TYPE)
            .setMetageneration(24L)
            .buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    StorageService.PredefinedAccessControlList acl = StorageService.PredefinedAccessControlList.ALL_AUTHENTICATED_USERS;
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.create(
                info,
                content,
                StorageService.BlobUploadOption.ifGenerationMatch(),
                StorageService.BlobUploadOption.ifMetagenerationMatch(),
                StorageService.BlobUploadOption.withPredefinedAcl(acl),
                StorageService.BlobUploadOption.customerSuppliedKey(BASE64_KEY),
                StorageService.BlobUploadOption.withUserProject(USER_PROJECT)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
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
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, StorageService.BlobUploadOption.customerSuppliedKey(KEY)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob("n", content, CONTENT_TYPE, StorageBucket.BlobUploadOption.withEncryptionKey(KEY));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateWithKmsKeyName() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder(BlobIdentifier.create("b", "n")).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, StorageService.BlobUploadOption.withKmsKeyName(DEFAULT_KMS_KEY_NAME)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob(
            "n", content, CONTENT_TYPE, StorageBucket.BlobUploadOption.withKmsKeyName(DEFAULT_KMS_KEY_NAME));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateNotExists() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobIdentifier.create("b", "n", 0L)).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, content, StorageService.BlobUploadOption.ifGenerationMatch()))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob = bucket.createBlob("n", content, CONTENT_TYPE, StorageBucket.BlobUploadOption.ifDoesNotExist());
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
  public void testCreateFromStream() throws Exception {
    initializeExpectedBucket(5);
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
  public void testCreateFromStreamNoContentType() throws Exception {
    initializeExpectedBucket(5);
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
  public void testCreateFromStreamWithOptions() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobIdentifier.create("b", "n", 42L))
            .setContentType(CONTENT_TYPE)
            .setMetageneration(24L)
            .setCrc32c("crc")
            .setMd5("md5")
            .buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    StorageService.PredefinedAccessControlList acl = StorageService.PredefinedAccessControlList.ALL_AUTHENTICATED_USERS;
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(
            storage.create(
                info,
                streamContent,
                StorageService.BlobWriteSetting.ifGenerationMatch(),
                StorageService.BlobWriteSetting.ifMetagenerationMatch(),
                StorageService.BlobWriteSetting.withPredefinedAcl(acl),
                StorageService.BlobWriteSetting.ifCrc32cMatch(),
                StorageService.BlobWriteSetting.ifMd5Match(),
                StorageService.BlobWriteSetting.customerSuppliedKey(BASE64_KEY),
                StorageService.BlobWriteSetting.withUserProject(USER_PROJECT)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob(
            "n",
            streamContent,
            CONTENT_TYPE,
            StorageBucket.BlobWriteSetting.withGenerationMatch(42L),
            StorageBucket.BlobWriteSetting.withMetagenerationMatch(24L),
            StorageBucket.BlobWriteSetting.withPredefinedAcl(acl),
            StorageBucket.BlobWriteSetting.withCrc32cMatch("crc"),
            StorageBucket.BlobWriteSetting.withMd5Match("md5"),
            StorageBucket.BlobWriteSetting.withEncryptionKey(BASE64_KEY),
            StorageBucket.BlobWriteSetting.withUserProject(USER_PROJECT));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateFromStreamWithEncryptionKey() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info = BlobMetadata.newBuilder(BlobIdentifier.create("b", "n")).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent, StorageService.BlobWriteSetting.customerSuppliedKey(KEY)))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob("n", streamContent, CONTENT_TYPE, StorageBucket.BlobWriteSetting.withEncryptionKey(KEY));
    assertEquals(expectedBlob, blob);
  }

  @Test
  public void testCreateFromStreamNotExists() throws Exception {
    initializeExpectedBucket(5);
    BlobMetadata info =
        BlobMetadata.newBuilder(BlobIdentifier.create("b", "n", 0L)).setContentType(CONTENT_TYPE).buildObject();
    StorageObject expectedBlob = new StorageObject(serviceMockReturnsOptions, new BlobMetadata.BlobInfoBuilderImpl(info));
    byte[] content = {0xD, 0xE, 0xA, 0xD};
    InputStream streamContent = new ByteArrayInputStream(content);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.create(info, streamContent, StorageService.BlobWriteSetting.ifGenerationMatch()))
        .andReturn(expectedBlob);
    replay(storage);
    initializeBucket();
    StorageObject blob =
        bucket.createBlob("n", streamContent, CONTENT_TYPE, StorageBucket.BlobWriteSetting.ifDoesNotExist());
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
          StorageBucket.BlobWriteSetting.withGenerationMatch(42L),
          StorageBucket.BlobWriteSetting.withGenerationNotMatch(24L));
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
          StorageBucket.BlobWriteSetting.withMetagenerationMatch(42L),
          StorageBucket.BlobWriteSetting.withMetagenerationNotMatch(24L));
      Assert.fail();
    } catch (IllegalArgumentException ex) {
      assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testGetAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    expect(storage.getAcl(BUCKET_INFO.getName(), AccessControlEntry.UserPrincipal.allAuthenticatedUsers())).andReturn(ACL);
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
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").create();
    expect(storage.createAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.addAcl(ACL));
  }

  @Test
  public void testUpdateAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").create();
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
    assertEquals(ACLS, bucket.listAclEntries());
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
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").create();
    expect(storage.createDefaultAcl(BUCKET_INFO.getName(), ACL)).andReturn(returnedAcl);
    replay(storage);
    initializeBucket();
    assertEquals(returnedAcl, bucket.addDefaultAcl(ACL));
  }

  @Test
  public void testUpdateDefaultAcl() throws Exception {
    initializeExpectedBucket(4);
    expect(storage.getOptions()).andReturn(mockOptions);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").create();
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
    assertEquals(ACLS, bucket.listDefaultAclEntries());
  }

  @Test
  public void testLockRetention() throws Exception {
    initializeExpectedBucket(5);
    StorageBucket expectedRetentionLockedBucket =
        expectedBucket
            .toBucketBuilder()
            .setRetentionPeriod(RETENTION_PERIOD)
            .setRetentionPolicyIsLocked(true)
            .buildInstance();
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    expect(
            storage.lockRetentionPolicy(
                expectedRetentionLockedBucket,
                StorageService.BucketTargetOptions.ifMetagenerationMatch(),
                StorageService.BucketTargetOptions.withUserProject(USER_PROJECT)))
        .andReturn(expectedRetentionLockedBucket);
    replay(storage);
    initializeBucket();
    StorageBucket lockedRetentionPolicyBucket =
        new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(expectedRetentionLockedBucket));
    StorageBucket actualRetentionLockedBucket =
        lockedRetentionPolicyBucket.lockRetention(
            StorageService.BucketTargetOptions.ifMetagenerationMatch(),
            StorageService.BucketTargetOptions.withUserProject(USER_PROJECT));
    assertEquals(expectedRetentionLockedBucket, actualRetentionLockedBucket);
  }

  @Test
  public void testToBuilder() {
    expect(storage.getOptions()).andReturn(mockOptions).times(4);
    replay(storage);
    StorageBucket fullBucket = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(FULL_BUCKET_INFO));
    assertEquals(fullBucket, fullBucket.toBucketBuilder().buildInstance());
    StorageBucket simpleBlob = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO));
    assertEquals(simpleBlob, simpleBlob.toBucketBuilder().buildInstance());
  }

  @Test
  public void testBuilder() {
    initializeExpectedBucket(4);
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
            .buildInstance();
    assertEquals("b", bucket.getName());
    assertEquals(ACLS, bucket.getAcl());
    assertEquals(ETAG, bucket.getEtag());
    assertEquals(GENERATED_ID, bucket.getGeneratedId());
    assertEquals(META_GENERATION, bucket.getMetageneration());
    assertEquals(OWNER, bucket.getOwner());
    assertEquals(SELF_LINK, bucket.getSelfLink());
    assertEquals(CREATE_TIME, bucket.getCreateTime());
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
}
