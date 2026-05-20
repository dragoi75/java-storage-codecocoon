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

import static com.google.cloud.storage.AccessControlEntry.ProjectInfo.ProjectMemberRole.VIEWERS;
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
import com.google.cloud.storage.AccessControlEntry.ProjectInfo;
import com.google.cloud.storage.AccessControlEntry.RoleType;
import com.google.cloud.storage.AccessControlEntry.UserIdentity;
import com.google.cloud.storage.BucketMetadata.DaysToLiveRule;
import com.google.cloud.storage.BucketMetadata.CreationBeforeDeletionRule;
import com.google.cloud.storage.BucketMetadata.DeletionRule;
import com.google.cloud.storage.BucketMetadata.DeletionRule.VersionFilterType;
import com.google.cloud.storage.BucketMetadata.BucketIamConfiguration;
import com.google.cloud.storage.BucketMetadata.LiveDeleteRule;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.AbortIncompleteMultipartUploadAction;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.LifecycleRuleAction;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.SetStorageClassLifecycleOperation;
import com.google.cloud.storage.BucketMetadata.PublicAccessPreventionPolicy;
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

  private static final List<AccessControlEntry> ACL =
      ImmutableList.of(
          AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), AccessControlEntry.RoleType.READER),
          AccessControlEntry.create(new ProjectInfo(VIEWERS, "p1"), RoleType.WRITER));
  private static final String ETAG = "0xFF00";
  private static final String GENERATED_ID = "B/N:1";
  private static final Long META_GENERATION = 10L;
  private static final AccessControlEntry.UserIdentity OWNER = new AccessControlEntry.UserIdentity("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long CREATE_TIME = System.currentTimeMillis();
  private static final Long UPDATE_TIME = CREATE_TIME;
  private static final List<CorsConfiguration> CORS = Collections.singletonList(CorsConfiguration.newCorsConfigurationBuilder().buildCorsConfiguration());
  private static final List<AccessControlEntry> DEFAULT_ACL =
      Collections.singletonList(AccessControlEntry.create(AccessControlEntry.UserIdentity.allAuthenticatedUsers(), RoleType.WRITER));

  @SuppressWarnings({"unchecked", "deprecation"})
  private static final List<? extends BucketMetadata.DeletionRule> DELETE_RULES =
      Collections.singletonList(new DaysToLiveRule(5));

  private static final List<? extends BucketMetadata.LifecycleRuleDefinition> LIFECYCLE_RULES =
      Collections.singletonList(
          new BucketMetadata.LifecycleRuleDefinition(
              LifecycleRuleDefinition.LifecycleRuleAction.newRemoveAction(),
              BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition.builder().setAge(5).buildCondition()));
  private static final String INDEX_PAGE = "index.html";
  private static final BucketIamConfiguration IAM_CONFIGURATION =
      BucketIamConfiguration.builder()
          .setIsUniformBucketLevelAccessEnabled(true)
          .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
          .setPublicAccessPrevention(PublicAccessPreventionPolicy.ENFORCED)
          .buildConfig();
  private static final BucketMetadata.LoggingConfig LOGGING =
      BucketMetadata.LoggingConfig.builder()
          .setLogBucket("test-bucket")
          .setLogObjectPrefix("test-")
          .buildConfig();
  private static final String NOT_FOUND_PAGE = "error.html";
  private static final String LOCATION = "ASIA";
  private static final StorageTier STORAGE_CLASS = StorageTier.STANDARD;
  private static final StorageTier ARCHIVE_STORAGE_CLASS = StorageTier.ARCHIVE;
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
    compareBuckets(BUCKET_INFO, BUCKET_INFO.asBuilder().buildBucket());
    BucketMetadata bucketInfo = BUCKET_INFO.asBuilder().setName("B").setGeneratedId("id").buildBucket();
    assertEquals("B", bucketInfo.getName());
    assertEquals("id", bucketInfo.getGeneratedId());
    bucketInfo = bucketInfo.asBuilder().setName("b").setGeneratedId(GENERATED_ID).buildBucket();
    compareBuckets(BUCKET_INFO, bucketInfo);
    assertEquals(ARCHIVE_STORAGE_CLASS, BUCKET_INFO_ARCHIVE.getStorageClass());
  }

  @Test
  public void testToBuilderIncomplete() {
    BucketMetadata incompleteBucketInfo = BucketMetadata.newBucketBuilder("b").buildBucket();
    compareBuckets(incompleteBucketInfo, incompleteBucketInfo.asBuilder().buildBucket());
  }

  @Test
  public void testOf() {
    BucketMetadata bucketInfo = BucketMetadata.ofName("bucket");
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
    assertEquals(REQUESTER_PAYS, BUCKET_INFO.isRequesterPays());
    assertEquals(DEFAULT_EVENT_BASED_HOLD, BUCKET_INFO.getDefaultEventBasedHold());
    assertEquals(RETENTION_EFFECTIVE_TIME, BUCKET_INFO.getRetentionEffectiveTime());
    assertEquals(RETENTION_PERIOD, BUCKET_INFO.getRetentionPeriod());
    assertEquals(RETENTION_POLICY_IS_LOCKED, BUCKET_INFO.isRetentionPolicyLocked());
    assertTrue(LOCATION_TYPES.contains(BUCKET_INFO.getLocationType()));
    assertEquals(LOGGING, BUCKET_INFO.getLogging());
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testToPbAndFromPb() {
    compareBuckets(BUCKET_INFO, BucketMetadata.fromProto(BUCKET_INFO.toProto()));
    BucketMetadata bucketInfo =
        BucketMetadata.newBucketBuilder("b")
            .setDeleteRules(DELETE_RULES)
            .setLifecycleRules(LIFECYCLE_RULES)
            .setLogging(LOGGING)
            .buildBucket();
    compareBuckets(bucketInfo, BucketMetadata.fromProto(bucketInfo.toProto()));
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
    assertEquals(expected.isRequesterPays(), value.isRequesterPays());
    assertEquals(expected.getDefaultEventBasedHold(), value.getDefaultEventBasedHold());
    assertEquals(expected.getRetentionEffectiveTime(), value.getRetentionEffectiveTime());
    assertEquals(expected.getRetentionPeriod(), value.getRetentionPeriod());
    assertEquals(expected.isRetentionPolicyLocked(), value.isRetentionPolicyLocked());
    assertEquals(expected.getLogging(), value.getLogging());
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testDeleteRules() {
    BucketMetadata.DaysToLiveRule ageRule = new BucketMetadata.DaysToLiveRule(10);
    assertEquals(10, ageRule.getDaysToLive());
    assertEquals(10, ageRule.getDaysToLive());
    assertEquals(VersionFilterType.AGE, ageRule.getType());
    assertEquals(VersionFilterType.AGE, ageRule.getType());
    BucketMetadata.CreationBeforeDeletionRule createBeforeRule = new CreationBeforeDeletionRule(1);
    assertEquals(1, createBeforeRule.getTimeMillis());
    assertEquals(1, createBeforeRule.getTimeMillis());
    assertEquals(BucketMetadata.DeletionRule.VersionFilterType.CREATE_BEFORE, createBeforeRule.getType());
    BucketMetadata.NewerVersionsThresholdDeleteRule versionsRule = new BucketMetadata.NewerVersionsThresholdDeleteRule(2);
    assertEquals(2, versionsRule.getNumNewerVersions());
    assertEquals(2, versionsRule.getNumNewerVersions());
    assertEquals(VersionFilterType.NUM_NEWER_VERSIONS, versionsRule.getType());
    BucketMetadata.LiveDeleteRule isLiveRule = new LiveDeleteRule(true);
    assertTrue(isLiveRule.isLive());
    assertEquals(VersionFilterType.IS_LIVE, isLiveRule.getType());
    assertEquals(VersionFilterType.IS_LIVE, isLiveRule.getType());
    Rule rule = new Rule().set("a", "b");
    BucketMetadata.RawDeletionRule rawRule = new BucketMetadata.RawDeletionRule(rule);
    assertEquals(BucketMetadata.DeletionRule.VersionFilterType.IS_LIVE, isLiveRule.getType());
    assertEquals(DeletionRule.VersionFilterType.IS_LIVE, isLiveRule.getType());
    ImmutableList<BucketMetadata.DeletionRule> rules =
        ImmutableList.of(ageRule, createBeforeRule, versionsRule, isLiveRule, rawRule);
    for (BucketMetadata.DeletionRule delRule : rules) {
      assertEquals(delRule, BucketMetadata.DeletionRule.fromProto(delRule.toProto()));
    }
    Rule unsupportedRule =
        new Rule().setAction(new Rule.Action().setType("This action doesn't exist"));
    DeletionRule.fromProto(
        unsupportedRule); // if this doesn't throw an exception, unsupported rules work
  }

  @Test
  public void testLifecycleRules() {
    Rule deleteLifecycleRule =
        new LifecycleRuleDefinition(
                LifecycleRuleAction.newRemoveAction(),
                BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition.builder().setAge(10).buildCondition())
            .toProto();

    assertEquals(
        BucketMetadata.LifecycleRuleDefinition.RemoveLifecycleAction.TYPE, deleteLifecycleRule.getAction().getType());
    assertEquals(10, deleteLifecycleRule.getCondition().getAge().intValue());
    assertTrue(
        BucketMetadata.LifecycleRuleDefinition.fromProto(deleteLifecycleRule).getAction() instanceof LifecycleRuleDefinition.RemoveLifecycleAction);

    Rule setStorageClassLifecycleRule =
        new LifecycleRuleDefinition(
                LifecycleRuleAction.createSetStorageClassAction(StorageTier.COLDLINE),
                LifecycleRuleCondition.builder()
                    .setIsLive(true)
                    .setNumberOfNewerVersions(10)
                    .buildCondition())
            .toProto();

    assertEquals(
        StorageTier.COLDLINE.toString(),
        setStorageClassLifecycleRule.getAction().getStorageClass());
    assertTrue(setStorageClassLifecycleRule.getCondition().getIsLive());
    assertEquals(10, setStorageClassLifecycleRule.getCondition().getNumNewerVersions().intValue());
    assertTrue(
        LifecycleRuleDefinition.fromProto(setStorageClassLifecycleRule).getAction()
            instanceof LifecycleRuleDefinition.SetStorageClassLifecycleOperation);

    Rule lifecycleRule =
        new BucketMetadata.LifecycleRuleDefinition(
                LifecycleRuleDefinition.LifecycleRuleAction.createSetStorageClassAction(StorageTier.COLDLINE),
                BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition.builder()
                    .setIsLive(true)
                    .setNumberOfNewerVersions(10)
                    .setDaysSinceNoncurrentTime(30)
                    .setNoncurrentTimeBefore(new DateTime(System.currentTimeMillis()))
                    .setCustomTimeBefore(new DateTime(System.currentTimeMillis()))
                    .setDaysSinceCustomTime(30)
                    .buildCondition())
            .toProto();
    assertEquals(StorageTier.COLDLINE.toString(), lifecycleRule.getAction().getStorageClass());
    assertTrue(lifecycleRule.getCondition().getIsLive());
    assertEquals(10, lifecycleRule.getCondition().getNumNewerVersions().intValue());
    assertEquals(30, lifecycleRule.getCondition().getDaysSinceNoncurrentTime().intValue());
    assertNotNull(lifecycleRule.getCondition().getNoncurrentTimeBefore());
    assertEquals(StorageTier.COLDLINE.toString(), lifecycleRule.getAction().getStorageClass());
    assertEquals(30, lifecycleRule.getCondition().getDaysSinceCustomTime().intValue());
    assertNotNull(lifecycleRule.getCondition().getCustomTimeBefore());
    assertTrue(
        LifecycleRuleDefinition.fromProto(lifecycleRule).getAction() instanceof SetStorageClassLifecycleOperation);

    Rule abortMpuLifecycleRule =
        new LifecycleRuleDefinition(
                LifecycleRuleAction.newAbortIncompleteMultipartUploadAction(),
                LifecycleRuleDefinition.LifecycleRuleCondition.builder().setAge(10).buildCondition())
            .toProto();
    assertEquals(AbortIncompleteMultipartUploadAction.TYPE, abortMpuLifecycleRule.getAction().getType());
    assertEquals(10, abortMpuLifecycleRule.getCondition().getAge().intValue());
    assertTrue(
        LifecycleRuleDefinition.fromProto(abortMpuLifecycleRule).getAction()
            instanceof LifecycleRuleDefinition.AbortIncompleteMultipartUploadAction);

    Rule unsupportedRule =
        new BucketMetadata.LifecycleRuleDefinition(
                LifecycleRuleAction.newAction("This action type doesn't exist"),
                LifecycleRuleDefinition.LifecycleRuleCondition.builder().setAge(10).buildCondition())
            .toProto();
    unsupportedRule.setAction(
        unsupportedRule.getAction().setType("This action type also doesn't exist"));

    BucketMetadata.LifecycleRuleDefinition.fromProto(
        unsupportedRule); // If this doesn't throw an exception, unsupported rules are working
  }

  @Test
  public void testIamConfiguration() {
    Bucket.IamConfiguration iamConfiguration =
        BucketIamConfiguration.builder()
            .setIsUniformBucketLevelAccessEnabled(true)
            .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
            .setPublicAccessPrevention(PublicAccessPreventionPolicy.ENFORCED)
            .buildConfig()
            .toProto();

    assertEquals(Boolean.TRUE, iamConfiguration.getUniformBucketLevelAccess().getEnabled());
    assertNotNull(iamConfiguration.getUniformBucketLevelAccess().getLockedTime());
    assertEquals(
        PublicAccessPreventionPolicy.ENFORCED.getValue(),
        iamConfiguration.getPublicAccessPrevention());
  }

  @Test
  public void testPublicAccessPrevention_ensureAbsentWhenUnknown() throws IOException {
    StringWriter stringWriter = new StringWriter();
    JsonGenerator jsonGenerator =
        JacksonFactory.getDefaultInstance().createJsonGenerator(stringWriter);

    jsonGenerator.serialize(
        BucketMetadata.BucketIamConfiguration.builder()
            .setIsUniformBucketLevelAccessEnabled(true)
            .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
            .setPublicAccessPrevention(PublicAccessPreventionPolicy.UNKNOWN)
            .buildConfig()
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

    assertEquals(BucketMetadata.PublicAccessPreventionPolicy.UNKNOWN, fromPb.getPublicAccessPrevention());
  }

  @Test
  public void testLogging() {
    Bucket.Logging logging =
        BucketMetadata.LoggingConfig.builder()
            .setLogBucket("test-bucket")
            .setLogObjectPrefix("test-")
            .buildConfig()
            .toProto();
    assertEquals("test-bucket", logging.getLogBucket());
    assertEquals("test-", logging.getLogObjectPrefix());
  }

  @Test
  public void testRuleMappingIsCorrect_noMutations() {
    Bucket bucket = bi().buildBucket().toProto();
    assertNull(bucket.getLifecycle());
  }

  @Test
  public void testRuleMappingIsCorrect_deleteLifecycleRules() {
    Bucket bucket = bi().clearLifecycleRules().buildBucket().toProto();
    assertEquals(EMPTY_LIFECYCLE, bucket.getLifecycle());
  }

  @Test
  @SuppressWarnings({"deprecation"})
  public void testRuleMappingIsCorrect_setDeleteRules_null() {
    Bucket bucket = bi().setDeleteRules(null).buildBucket().toProto();
    assertNull(bucket.getLifecycle());
  }

  @Test
  @SuppressWarnings({"deprecation"})
  public void testRuleMappingIsCorrect_setDeleteRules_empty() {
    Bucket bucket = bi().setDeleteRules(Collections.<BucketMetadata.DeletionRule>emptyList()).buildBucket().toProto();
    assertEquals(EMPTY_LIFECYCLE, bucket.getLifecycle());
  }

  @Test
  public void testRuleMappingIsCorrect_setLifecycleRules_empty() {
    Bucket bucket = bi().setLifecycleRules(Collections.<LifecycleRuleDefinition>emptyList()).buildBucket().toProto();
    assertEquals(EMPTY_LIFECYCLE, bucket.getLifecycle());
  }

  @Test
  public void testRuleMappingIsCorrect_setLifeCycleRules_nonEmpty() {
    LifecycleRuleDefinition lifecycleRule =
        new LifecycleRuleDefinition(
            LifecycleRuleAction.newRemoveAction(), LifecycleRuleDefinition.LifecycleRuleCondition.builder().setAge(10).buildCondition());
    Rule lifecycleDeleteAfter10 = lifecycleRule.toProto();
    Bucket bucket = bi().setLifecycleRules(ImmutableList.of(lifecycleRule)).buildBucket().toProto();
    assertEquals(lifecycle(lifecycleDeleteAfter10), bucket.getLifecycle());
  }

  @Test
  @SuppressWarnings({"deprecation"})
  public void testRuleMappingIsCorrect_setDeleteRules_nonEmpty() {
    DeletionRule deleteRule = DELETE_RULES.get(0);
    Rule deleteRuleAge5 = deleteRule.toProto();
    Bucket bucket = bi().setDeleteRules(ImmutableList.of(deleteRule)).buildBucket().toProto();
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
