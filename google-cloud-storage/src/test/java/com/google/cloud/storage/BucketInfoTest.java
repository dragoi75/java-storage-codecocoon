/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy from the License at
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

import static com.google.cloud.storage.Acl.Project.ProjectRole.VIEWERS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.api.client.json.JsonGenerator;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Bucket.Lifecycle;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule;
import com.google.cloud.storage.Acl.Project;
import com.google.cloud.storage.Acl.Role;
import com.google.cloud.storage.Acl.User;
import com.google.cloud.storage.BucketMetadata.AgeDeleteRule;
import com.google.cloud.storage.BucketMetadata.CreatedBeforeDeleteRule;
import com.google.cloud.storage.BucketMetadata.DeleteRule;
import com.google.cloud.storage.BucketMetadata.DeleteRule.VersionFilterType;
import com.google.cloud.storage.BucketMetadata.IsLiveDeleteRule;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleEntry;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleEntry.AbortIncompleteMultipartUploadAction;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleEntry.RemoveLifecycleAction;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleEntry.LifecycleRuleCondition;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleEntry.SetStorageClassLifecycleOperation;
import com.google.cloud.storage.BucketMetadata.NumNewerVersionsDeleteRule;
import com.google.cloud.storage.BucketMetadata.PublicAccessPreventionMode;
import com.google.cloud.storage.BucketMetadata.RawDeletionRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BucketInfoTest {

  private static final List<Acl> ACL =
      ImmutableList.of(
          Acl.of(User.ofAllAuthenticatedUsers(), Role.READER),
          Acl.of(new Project(VIEWERS, "p1"), Role.WRITER));
  private static final String ETAG = "0xFF00";
  private static final String GENERATED_ID = "B/N:1";
  private static final Long META_GENERATION = 10L;
  private static final User OWNER = new User("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long CREATE_TIME = System.currentTimeMillis();
  private static final Long UPDATE_TIME = CREATE_TIME;
  private static final List<Cors> CORS = Collections.singletonList(Cors.newBuilder().build());
  private static final List<Acl> DEFAULT_ACL =
      Collections.singletonList(Acl.of(User.ofAllAuthenticatedUsers(), Role.WRITER));

  @SuppressWarnings({"unchecked", "deprecation"})
  private static final List<? extends DeleteRule> DELETE_RULES =
      Collections.singletonList(new AgeDeleteRule(5));

  private static final List<? extends BucketMetadata.LifecycleRuleEntry> LIFECYCLE_RULES =
      Collections.singletonList(
          new BucketMetadata.LifecycleRuleEntry(
              LifecycleRuleEntry.LifecycleRuleAction.newRemoveAction(),
              LifecycleRuleEntry.LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(5).buildLifecycleRuleCondition()));
  private static final String INDEX_PAGE = "index.html";
  private static final BucketMetadata.BucketIamConfiguration IAM_CONFIGURATION =
      BucketMetadata.BucketIamConfiguration.createBuilder()
          .setIsUniformBucketLevelAccessEnabled(true)
          .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
          .setPublicAccessPrevention(BucketMetadata.PublicAccessPreventionMode.ENFORCED)
          .buildBucketIamConfiguration();
  private static final BucketMetadata.BucketLogging LOGGING =
      BucketMetadata.BucketLogging.newLogLocationBuilder()
          .setLogBucket("test-bucket")
          .setLogObjectPrefix("test-")
          .buildBucketLogging();
  private static final String NOT_FOUND_PAGE = "error.html";
  private static final String LOCATION = "ASIA";
  private static final StorageClass STORAGE_CLASS = StorageClass.STANDARD;
  private static final StorageClass ARCHIVE_STORAGE_CLASS = StorageClass.ARCHIVE;
  private static final String DEFAULT_KMS_KEY_NAME =
      "projects/p/locations/kr-loc/keyRings/kr/cryptoKeys/key";
  private static final Boolean VERSIONING_ENABLED = true;
  private static final Map<String, String> BUCKET_LABELS;

  static {
    BUCKET_LABELS = new HashMap<>();
    BUCKET_LABELS.put("label1", "value1");
    BUCKET_LABELS.put("label2", null);
  }

  private static final Map<String, String> BUCKET_LABELS_TARGET =
      ImmutableMap.of("label1", "value1", "label2", "");
  private static final Boolean REQUESTER_PAYS = true;
  private static final Boolean DEFAULT_EVENT_BASED_HOLD = true;
  private static final Long RETENTION_EFFECTIVE_TIME = 10L;
  private static final Long RETENTION_PERIOD = 10L;
  private static final Boolean RETENTION_POLICY_IS_LOCKED = false;
  private static final List<String> LOCATION_TYPES =
      ImmutableList.of("multi-region", "region", "dual-region");
  private static final String LOCATION_TYPE = "multi-region";

  @SuppressWarnings({"unchecked", "deprecation"})
  private static final BucketMetadata BUCKET_INFO =
      BucketMetadata.newBucketBuilder("b")
          .setAcl(ACL)
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
          .setIamConfiguration(IAM_CONFIGURATION)
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
          .setLogging(LOGGING)
          .buildBucket();

  @SuppressWarnings({"unchecked", "deprecation"})
  private static final BucketMetadata BUCKET_INFO_ARCHIVE =
      BucketMetadata.newBucketBuilder("b")
          .setAcl(ACL)
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
          .setIamConfiguration(IAM_CONFIGURATION)
          .setNotFoundPage(NOT_FOUND_PAGE)
          .setLocation(LOCATION)
          .setLocationType(LOCATION_TYPE)
          .setStorageClass(ARCHIVE_STORAGE_CLASS)
          .setVersioningEnabled(VERSIONING_ENABLED)
          .setLabels(BUCKET_LABELS)
          .setRequesterPays(REQUESTER_PAYS)
          .setDefaultKmsKeyName(DEFAULT_KMS_KEY_NAME)
          .setDefaultEventBasedHold(DEFAULT_EVENT_BASED_HOLD)
          .setRetentionEffectiveTime(RETENTION_EFFECTIVE_TIME)
          .setRetentionPeriod(RETENTION_PERIOD)
          .setRetentionPolicyIsLocked(RETENTION_POLICY_IS_LOCKED)
          .setLogging(LOGGING)
          .buildBucket();

  private static final Lifecycle EMPTY_LIFECYCLE = lifecycle(Collections.<Rule>emptyList());

  @Test
  public void testToBuilder() {
    compareBuckets(BUCKET_INFO, BUCKET_INFO.newBuilder().buildBucket());
    BucketMetadata bucketInfo = BUCKET_INFO.newBuilder().setName("B").setGeneratedId("id").buildBucket();
    assertEquals("B", bucketInfo.getName());
    assertEquals("id", bucketInfo.getGeneratedId());
    bucketInfo = bucketInfo.newBuilder().setName("b").setGeneratedId(GENERATED_ID).buildBucket();
    compareBuckets(BUCKET_INFO, bucketInfo);
    assertEquals(ARCHIVE_STORAGE_CLASS, BUCKET_INFO_ARCHIVE.getStorageClass());
  }

  @Test
  public void testToBuilderIncomplete() {
    BucketMetadata incompleteBucketInfo = BucketMetadata.newBucketBuilder("b").buildBucket();
    compareBuckets(incompleteBucketInfo, incompleteBucketInfo.newBuilder().buildBucket());
  }

  @Test
  public void testOf() {
    BucketMetadata bucketInfo = BucketMetadata.from("bucket");
    assertEquals("bucket", bucketInfo.getName());
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testBuilder() {
    assertEquals("b", BUCKET_INFO.getName());
    assertEquals(ACL, BUCKET_INFO.getAcl());
    assertEquals(ETAG, BUCKET_INFO.getEtag());
    assertEquals(GENERATED_ID, BUCKET_INFO.getGeneratedId());
    assertEquals(META_GENERATION, BUCKET_INFO.getMetageneration());
    assertEquals(OWNER, BUCKET_INFO.getOwner());
    assertEquals(SELF_LINK, BUCKET_INFO.getSelfLink());
    assertEquals(CREATE_TIME, BUCKET_INFO.getCreateTime());
    assertEquals(UPDATE_TIME, BUCKET_INFO.getUpdateTime());
    assertEquals(CORS, BUCKET_INFO.getCors());
    assertEquals(DEFAULT_ACL, BUCKET_INFO.getDefaultAcl());
    assertEquals(DELETE_RULES, BUCKET_INFO.getDeleteRules());
    assertEquals(INDEX_PAGE, BUCKET_INFO.getIndexPage());
    assertEquals(IAM_CONFIGURATION, BUCKET_INFO.getIamConfiguration());
    assertEquals(NOT_FOUND_PAGE, BUCKET_INFO.getNotFoundPage());
    assertEquals(LOCATION, BUCKET_INFO.getLocation());
    assertEquals(STORAGE_CLASS, BUCKET_INFO.getStorageClass());
    assertEquals(DEFAULT_KMS_KEY_NAME, BUCKET_INFO.getDefaultKmsKeyName());
    assertEquals(VERSIONING_ENABLED, BUCKET_INFO.isVersioningEnabled());
    assertEquals(BUCKET_LABELS_TARGET, BUCKET_INFO.getLabels());
    assertEquals(REQUESTER_PAYS, BUCKET_INFO.getRequesterPays());
    assertEquals(DEFAULT_EVENT_BASED_HOLD, BUCKET_INFO.getDefaultEventBasedHold());
    assertEquals(RETENTION_EFFECTIVE_TIME, BUCKET_INFO.getRetentionEffectiveTime());
    assertEquals(RETENTION_PERIOD, BUCKET_INFO.getRetentionPeriod());
    assertEquals(RETENTION_POLICY_IS_LOCKED, BUCKET_INFO.retentionPolicyIsLocked());
    assertTrue(LOCATION_TYPES.contains(BUCKET_INFO.getLocationType()));
    assertEquals(LOGGING, BUCKET_INFO.getLogging());
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testToPbAndFromPb() {
    compareBuckets(BUCKET_INFO, BucketMetadata.fromProto(BUCKET_INFO.toBucketPb()));
    BucketMetadata bucketInfo =
        BucketMetadata.newBucketBuilder("b")
            .setDeleteRules(DELETE_RULES)
            .setLifecycleRules(LIFECYCLE_RULES)
            .setLogging(LOGGING)
            .buildBucket();
    compareBuckets(bucketInfo, BucketMetadata.fromProto(bucketInfo.toBucketPb()));
  }

  @SuppressWarnings({"unchecked", "deprecation"})
  private void compareBuckets(BucketMetadata expected, BucketMetadata value) {
    assertEquals(expected, value);
    assertEquals(expected.getName(), value.getName());
    assertEquals(expected.getAcl(), value.getAcl());
    assertEquals(expected.getEtag(), value.getEtag());
    assertEquals(expected.getGeneratedId(), value.getGeneratedId());
    assertEquals(expected.getMetageneration(), value.getMetageneration());
    assertEquals(expected.getOwner(), value.getOwner());
    assertEquals(expected.getSelfLink(), value.getSelfLink());
    assertEquals(expected.getCreateTime(), value.getCreateTime());
    assertEquals(expected.getUpdateTime(), value.getUpdateTime());
    assertEquals(expected.getCors(), value.getCors());
    assertEquals(expected.getDefaultAcl(), value.getDefaultAcl());
    assertEquals(expected.getDeleteRules(), value.getDeleteRules());
    assertEquals(expected.getLifecycleRules(), value.getLifecycleRules());
    assertEquals(expected.getIndexPage(), value.getIndexPage());
    assertEquals(expected.getIamConfiguration(), value.getIamConfiguration());
    assertEquals(expected.getNotFoundPage(), value.getNotFoundPage());
    assertEquals(expected.getLocation(), value.getLocation());
    assertEquals(expected.getStorageClass(), value.getStorageClass());
    assertEquals(expected.getDefaultKmsKeyName(), value.getDefaultKmsKeyName());
    assertEquals(expected.isVersioningEnabled(), value.isVersioningEnabled());
    assertEquals(expected.getLabels(), value.getLabels());
    assertEquals(expected.getRequesterPays(), value.getRequesterPays());
    assertEquals(expected.getDefaultEventBasedHold(), value.getDefaultEventBasedHold());
    assertEquals(expected.getRetentionEffectiveTime(), value.getRetentionEffectiveTime());
    assertEquals(expected.getRetentionPeriod(), value.getRetentionPeriod());
    assertEquals(expected.retentionPolicyIsLocked(), value.retentionPolicyIsLocked());
    assertEquals(expected.getLogging(), value.getLogging());
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testDeleteRules() {
    AgeDeleteRule ageRule = new AgeDeleteRule(10);
    assertEquals(10, ageRule.getDaysToLive());
    assertEquals(10, ageRule.getDaysToLive());
    assertEquals(DeleteRule.VersionFilterType.AGE, ageRule.getType());
    assertEquals(VersionFilterType.AGE, ageRule.getType());
    CreatedBeforeDeleteRule createBeforeRule = new CreatedBeforeDeleteRule(1);
    assertEquals(1, createBeforeRule.getTimeMillis());
    assertEquals(1, createBeforeRule.getTimeMillis());
    assertEquals(VersionFilterType.CREATE_BEFORE, createBeforeRule.getType());
    NumNewerVersionsDeleteRule versionsRule = new NumNewerVersionsDeleteRule(2);
    assertEquals(2, versionsRule.getNumNewerVersions());
    assertEquals(2, versionsRule.getNumNewerVersions());
    assertEquals(VersionFilterType.NUM_NEWER_VERSIONS, versionsRule.getType());
    IsLiveDeleteRule isLiveRule = new IsLiveDeleteRule(true);
    assertTrue(isLiveRule.isLive());
    assertEquals(VersionFilterType.IS_LIVE, isLiveRule.getType());
    assertEquals(VersionFilterType.IS_LIVE, isLiveRule.getType());
    Rule rule = new Rule().set("a", "b");
    BucketMetadata.RawDeletionRule rawRule = new RawDeletionRule(rule);
    assertEquals(VersionFilterType.IS_LIVE, isLiveRule.getType());
    assertEquals(DeleteRule.VersionFilterType.IS_LIVE, isLiveRule.getType());
    ImmutableList<DeleteRule> rules =
        ImmutableList.of(ageRule, createBeforeRule, versionsRule, isLiveRule, rawRule);
    for (DeleteRule delRule : rules) {
      assertEquals(delRule, DeleteRule.fromProto(delRule.toProto()));
    }
    Rule unsupportedRule =
        new Rule().setAction(new Rule.Action().setType("This action doesn't exist"));
    DeleteRule.fromProto(
        unsupportedRule); // if this doesn't throw an exception, unsupported rules work
  }

  @Test
  public void testLifecycleRules() {
    Rule deleteLifecycleRule =
        new BucketMetadata.LifecycleRuleEntry(
                BucketMetadata.LifecycleRuleEntry.LifecycleRuleAction.newRemoveAction(),
                LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(10).buildLifecycleRuleCondition())
            .toProto();

    assertEquals(
        RemoveLifecycleAction.TYPE, deleteLifecycleRule.getAction().getType());
    assertEquals(10, deleteLifecycleRule.getCondition().getAge().intValue());
    assertTrue(
        LifecycleRuleEntry.fromProto(deleteLifecycleRule).getAction() instanceof RemoveLifecycleAction);

    Rule setStorageClassLifecycleRule =
        new BucketMetadata.LifecycleRuleEntry(
                BucketMetadata.LifecycleRuleEntry.LifecycleRuleAction.createSetStorageClassAction(StorageClass.COLDLINE),
                LifecycleRuleCondition.newLifecycleConditionBuilder()
                    .setIsLive(true)
                    .setNumberOfNewerVersions(10)
                    .buildLifecycleRuleCondition())
            .toProto();

    assertEquals(
        StorageClass.COLDLINE.toString(),
        setStorageClassLifecycleRule.getAction().getStorageClass());
    assertTrue(setStorageClassLifecycleRule.getCondition().getIsLive());
    assertEquals(10, setStorageClassLifecycleRule.getCondition().getNumNewerVersions().intValue());
    assertTrue(
        BucketMetadata.LifecycleRuleEntry.fromProto(setStorageClassLifecycleRule).getAction()
            instanceof SetStorageClassLifecycleOperation);

    Rule lifecycleRule =
        new BucketMetadata.LifecycleRuleEntry(
                BucketMetadata.LifecycleRuleEntry.LifecycleRuleAction.createSetStorageClassAction(StorageClass.COLDLINE),
                LifecycleRuleCondition.newLifecycleConditionBuilder()
                    .setIsLive(true)
                    .setNumberOfNewerVersions(10)
                    .setDaysSinceNoncurrentTime(30)
                    .setNoncurrentTimeBefore(new DateTime(System.currentTimeMillis()))
                    .setCustomTimeBefore(new DateTime(System.currentTimeMillis()))
                    .setDaysSinceCustomTime(30)
                    .buildLifecycleRuleCondition())
            .toProto();
    assertEquals(StorageClass.COLDLINE.toString(), lifecycleRule.getAction().getStorageClass());
    assertTrue(lifecycleRule.getCondition().getIsLive());
    assertEquals(10, lifecycleRule.getCondition().getNumNewerVersions().intValue());
    assertEquals(30, lifecycleRule.getCondition().getDaysSinceNoncurrentTime().intValue());
    assertNotNull(lifecycleRule.getCondition().getNoncurrentTimeBefore());
    assertEquals(StorageClass.COLDLINE.toString(), lifecycleRule.getAction().getStorageClass());
    assertEquals(30, lifecycleRule.getCondition().getDaysSinceCustomTime().intValue());
    assertNotNull(lifecycleRule.getCondition().getCustomTimeBefore());
    assertTrue(
        BucketMetadata.LifecycleRuleEntry.fromProto(lifecycleRule).getAction() instanceof BucketMetadata.LifecycleRuleEntry.SetStorageClassLifecycleOperation);

    Rule abortMpuLifecycleRule =
        new BucketMetadata.LifecycleRuleEntry(
                BucketMetadata.LifecycleRuleEntry.LifecycleRuleAction.newAbortIncompleteMultipartUploadAction(),
                LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(10).buildLifecycleRuleCondition())
            .toProto();
    assertEquals(AbortIncompleteMultipartUploadAction.TYPE, abortMpuLifecycleRule.getAction().getType());
    assertEquals(10, abortMpuLifecycleRule.getCondition().getAge().intValue());
    assertTrue(
        LifecycleRuleEntry.fromProto(abortMpuLifecycleRule).getAction()
            instanceof BucketMetadata.LifecycleRuleEntry.AbortIncompleteMultipartUploadAction);

    Rule unsupportedRule =
        new BucketMetadata.LifecycleRuleEntry(
                BucketMetadata.LifecycleRuleEntry.LifecycleRuleAction.createLifecycleAction("This action type doesn't exist"),
                LifecycleRuleEntry.LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(10).buildLifecycleRuleCondition())
            .toProto();
    unsupportedRule.setAction(
        unsupportedRule.getAction().setType("This action type also doesn't exist"));

    BucketMetadata.LifecycleRuleEntry.fromProto(
        unsupportedRule); // If this doesn't throw an exception, unsupported rules are working
  }

  @Test
  public void testIamConfiguration() {
    Bucket.IamConfiguration iamConfiguration =
        BucketMetadata.BucketIamConfiguration.createBuilder()
            .setIsUniformBucketLevelAccessEnabled(true)
            .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
            .setPublicAccessPrevention(PublicAccessPreventionMode.ENFORCED)
            .buildBucketIamConfiguration()
            .toProto();

    assertEquals(Boolean.TRUE, iamConfiguration.getUniformBucketLevelAccess().getEnabled());
    assertNotNull(iamConfiguration.getUniformBucketLevelAccess().getLockedTime());
    assertEquals(
        PublicAccessPreventionMode.ENFORCED.getValue(),
        iamConfiguration.getPublicAccessPrevention());
  }

  @Test
  public void testPublicAccessPrevention_ensureAbsentWhenUnknown() throws IOException {
    StringWriter stringWriter = new StringWriter();
    JsonGenerator jsonGenerator =
        JacksonFactory.getDefaultInstance().createJsonGenerator(stringWriter);

    jsonGenerator.serialize(
        BucketMetadata.BucketIamConfiguration.createBuilder()
            .setIsUniformBucketLevelAccessEnabled(true)
            .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
            .setPublicAccessPrevention(PublicAccessPreventionMode.UNKNOWN)
            .buildBucketIamConfiguration()
            .toProto());
    jsonGenerator.flush();

    assertFalse(stringWriter.getBuffer().toString().contains("publicAccessPrevention"));
  }

  @Test
  public void testPapValueOfIamConfiguration() {
    Bucket.IamConfiguration iamConfiguration = new Bucket.IamConfiguration();
    Bucket.IamConfiguration.UniformBucketLevelAccess uniformBucketLevelAccess =
        new Bucket.IamConfiguration.UniformBucketLevelAccess();
    iamConfiguration.setUniformBucketLevelAccess(uniformBucketLevelAccess);
    iamConfiguration.setPublicAccessPrevention("random-string");
    BucketMetadata.BucketIamConfiguration fromPb = BucketMetadata.BucketIamConfiguration.fromProto(iamConfiguration);

    assertEquals(BucketMetadata.PublicAccessPreventionMode.UNKNOWN, fromPb.getPublicAccessPrevention());
  }

  @Test
  public void testLogging() {
    Bucket.Logging logging =
        BucketMetadata.BucketLogging.newLogLocationBuilder()
            .setLogBucket("test-bucket")
            .setLogObjectPrefix("test-")
            .buildBucketLogging()
            .toProto();
    assertEquals("test-bucket", logging.getLogBucket());
    assertEquals("test-", logging.getLogObjectPrefix());
  }

  @Test
  public void testRuleMappingIsCorrect_noMutations() {
    Bucket bucket = bi().buildBucket().toBucketPb();
    assertNull(bucket.getLifecycle());
  }

  @Test
  public void testRuleMappingIsCorrect_deleteLifecycleRules() {
    Bucket bucket = bi().removeLifecycleRules().buildBucket().toBucketPb();
    assertEquals(EMPTY_LIFECYCLE, bucket.getLifecycle());
  }

  @Test
  @SuppressWarnings({"deprecation"})
  public void testRuleMappingIsCorrect_setDeleteRules_null() {
    Bucket bucket = bi().setDeleteRules(null).buildBucket().toBucketPb();
    assertNull(bucket.getLifecycle());
  }

  @Test
  @SuppressWarnings({"deprecation"})
  public void testRuleMappingIsCorrect_setDeleteRules_empty() {
    Bucket bucket = bi().setDeleteRules(Collections.<DeleteRule>emptyList()).buildBucket().toBucketPb();
    assertEquals(EMPTY_LIFECYCLE, bucket.getLifecycle());
  }

  @Test
  public void testRuleMappingIsCorrect_setLifecycleRules_empty() {
    Bucket bucket = bi().setLifecycleRules(Collections.<BucketMetadata.LifecycleRuleEntry>emptyList()).buildBucket().toBucketPb();
    assertEquals(EMPTY_LIFECYCLE, bucket.getLifecycle());
  }

  @Test
  public void testRuleMappingIsCorrect_setLifeCycleRules_nonEmpty() {
    BucketMetadata.LifecycleRuleEntry lifecycleRule =
        new BucketMetadata.LifecycleRuleEntry(
            BucketMetadata.LifecycleRuleEntry.LifecycleRuleAction.newRemoveAction(), LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(10).buildLifecycleRuleCondition());
    Rule lifecycleDeleteAfter10 = lifecycleRule.toProto();
    Bucket bucket = bi().setLifecycleRules(ImmutableList.of(lifecycleRule)).buildBucket().toBucketPb();
    assertEquals(lifecycle(lifecycleDeleteAfter10), bucket.getLifecycle());
  }

  @Test
  @SuppressWarnings({"deprecation"})
  public void testRuleMappingIsCorrect_setDeleteRules_nonEmpty() {
    DeleteRule deleteRule = DELETE_RULES.get(0);
    Rule deleteRuleAge5 = deleteRule.toProto();
    Bucket bucket = bi().setDeleteRules(ImmutableList.of(deleteRule)).buildBucket().toBucketPb();
    assertEquals(lifecycle(deleteRuleAge5), bucket.getLifecycle());
  }

  private static Lifecycle lifecycle(Rule... rules) {
    return lifecycle(Arrays.asList(rules));
  }

  private static Lifecycle lifecycle(List<Rule> rules) {
    Lifecycle emptyLifecycle = new Lifecycle();
    emptyLifecycle.setRule(rules);
    return emptyLifecycle;
  }

  private static BucketMetadata.BucketBuilder bi() {
    String bucketId = "bucketId";
    return BucketMetadata.newBucketBuilder(bucketId);
  }
}
