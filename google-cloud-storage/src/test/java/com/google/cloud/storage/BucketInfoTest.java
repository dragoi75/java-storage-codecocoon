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

import static com.google.cloud.storage.AccessControlEntry.ProjectInfo.ProjectRoleType.VIEWERS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule;
import com.google.cloud.storage.AccessControlEntry.ProjectInfo;
import com.google.cloud.storage.AccessControlEntry.AccessRole;
import com.google.cloud.storage.AccessControlEntry.UserPrincipal;
import com.google.cloud.storage.BucketMetadata.AgeBasedDeletionRule;
import com.google.cloud.storage.BucketMetadata.CreatedBeforeDeletionRule;
import com.google.cloud.storage.BucketMetadata.DeletionRule;
import com.google.cloud.storage.BucketMetadata.DeletionRule.RetentionCriterion;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.AbstractLifecycleAction;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition;
import com.google.cloud.storage.BucketMetadata.NumNewerVersionsDeletionRule;
import com.google.cloud.storage.BucketMetadata.RawDeletionRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BucketInfoTest {

  private static final List<AccessControlEntry> ACL =
      ImmutableList.of(
          AccessControlEntry.create(AccessControlEntry.UserPrincipal.allAuthenticatedUsers(), AccessControlEntry.AccessRole.READER),
          AccessControlEntry.create(new ProjectInfo(VIEWERS, "p1"), AccessRole.WRITER));
  private static final String ETAG = "0xFF00";
  private static final String GENERATED_ID = "B/N:1";
  private static final Long META_GENERATION = 10L;
  private static final AccessControlEntry.UserPrincipal OWNER = new AccessControlEntry.UserPrincipal("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long CREATE_TIME = System.currentTimeMillis();
  private static final List<CorsConfiguration> CORS = Collections.singletonList(CorsConfiguration.createBuilder().create());
  private static final List<AccessControlEntry> DEFAULT_ACL =
      Collections.singletonList(AccessControlEntry.create(UserPrincipal.allAuthenticatedUsers(), AccessControlEntry.AccessRole.WRITER));
  private static final List<? extends BucketMetadata.DeletionRule> DELETE_RULES =
      Collections.singletonList(new AgeBasedDeletionRule(5));
  private static final List<? extends LifecycleRuleDefinition> LIFECYCLE_RULES =
      Collections.singletonList(
          new LifecycleRuleDefinition(
              AbstractLifecycleAction.newRemoveAction(),
              LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(5).buildLifecycleCondition()));
  private static final String INDEX_PAGE = "index.html";
  private static final BucketMetadata.BucketIamConfiguration IAM_CONFIGURATION =
      BucketMetadata.BucketIamConfiguration.newUniformBucketLevelAccessBuilder()
          .setIsUniformBucketLevelAccessEnabled(true)
          .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
          .buildBucketIamConfiguration();
  private static final BucketMetadata.LoggingConfig LOGGING =
      BucketMetadata.LoggingConfig.newLogConfigBuilder()
          .setLogBucket("test-bucket")
          .setLogObjectPrefix("test-")
          .buildLoggingConfig();
  private static final String NOT_FOUND_PAGE = "error.html";
  private static final String LOCATION = "ASIA";
  private static final StorageClassType STORAGE_CLASS = StorageClassType.STANDARD;
  private static final StorageClassType ARCHIVE_STORAGE_CLASS = StorageClassType.ARCHIVE;
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
          .buildInstance();
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
          .buildInstance();

  @Test
  public void testToBuilder() {
    compareBuckets(BUCKET_INFO, BUCKET_INFO.toBucketBuilder().buildInstance());
    BucketMetadata bucketInfo = BUCKET_INFO.toBucketBuilder().setName("B").setGeneratedId("id").buildInstance();
    assertEquals("B", bucketInfo.getName());
    assertEquals("id", bucketInfo.getGeneratedId());
    bucketInfo = bucketInfo.toBucketBuilder().setName("b").setGeneratedId(GENERATED_ID).buildInstance();
    compareBuckets(BUCKET_INFO, bucketInfo);
    assertEquals(ARCHIVE_STORAGE_CLASS, BUCKET_INFO_ARCHIVE.getStorageClass());
  }

  @Test
  public void testToBuilderIncomplete() {
    BucketMetadata incompleteBucketInfo = BucketMetadata.newBucketBuilder("b").buildInstance();
    compareBuckets(incompleteBucketInfo, incompleteBucketInfo.toBucketBuilder().buildInstance());
  }

  @Test
  public void testOf() {
    BucketMetadata bucketInfo = BucketMetadata.ofName("bucket");
    assertEquals("bucket", bucketInfo.getName());
  }

  @Test
  public void testBuilder() {
    assertEquals("b", BUCKET_INFO.getName());
    assertEquals(ACL, BUCKET_INFO.getAcl());
    assertEquals(ETAG, BUCKET_INFO.getEtag());
    assertEquals(GENERATED_ID, BUCKET_INFO.getGeneratedId());
    assertEquals(META_GENERATION, BUCKET_INFO.getMetageneration());
    assertEquals(OWNER, BUCKET_INFO.getOwner());
    assertEquals(SELF_LINK, BUCKET_INFO.getSelfLink());
    assertEquals(CREATE_TIME, BUCKET_INFO.getCreateTime());
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
  public void testToPbAndFromPb() {
    compareBuckets(BUCKET_INFO, BucketMetadata.fromProto(BUCKET_INFO.toProto()));
    BucketMetadata bucketInfo = BucketMetadata.ofName("b");
    compareBuckets(bucketInfo, BucketMetadata.fromProto(bucketInfo.toProto()));
  }

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
  public void testDeleteRules() {
    BucketMetadata.AgeBasedDeletionRule ageRule = new BucketMetadata.AgeBasedDeletionRule(10);
    assertEquals(10, ageRule.getDaysToLive());
    assertEquals(10, ageRule.getDaysToLive());
    assertEquals(BucketMetadata.DeletionRule.RetentionCriterion.AGE, ageRule.getType());
    assertEquals(BucketMetadata.DeletionRule.RetentionCriterion.AGE, ageRule.getType());
    CreatedBeforeDeletionRule createBeforeRule = new BucketMetadata.CreatedBeforeDeletionRule(1);
    assertEquals(1, createBeforeRule.getTimeMillis());
    assertEquals(1, createBeforeRule.getTimeMillis());
    assertEquals(BucketMetadata.DeletionRule.RetentionCriterion.CREATE_BEFORE, createBeforeRule.getType());
    BucketMetadata.NumNewerVersionsDeletionRule versionsRule = new NumNewerVersionsDeletionRule(2);
    assertEquals(2, versionsRule.getNumNewerVersions());
    assertEquals(2, versionsRule.getNumNewerVersions());
    assertEquals(BucketMetadata.DeletionRule.RetentionCriterion.NUM_NEWER_VERSIONS, versionsRule.getType());
    BucketMetadata.LiveDeleteRule isLiveRule = new BucketMetadata.LiveDeleteRule(true);
    assertTrue(isLiveRule.isLive());
    assertEquals(BucketMetadata.DeletionRule.RetentionCriterion.IS_LIVE, isLiveRule.getType());
    assertEquals(RetentionCriterion.IS_LIVE, isLiveRule.getType());
    Rule rule = new Rule().set("a", "b");
    BucketMetadata.RawDeletionRule rawRule = new RawDeletionRule(rule);
    assertEquals(BucketMetadata.DeletionRule.RetentionCriterion.IS_LIVE, isLiveRule.getType());
    assertEquals(DeletionRule.RetentionCriterion.IS_LIVE, isLiveRule.getType());
    ImmutableList<DeletionRule> rules =
        ImmutableList.of(ageRule, createBeforeRule, versionsRule, isLiveRule, rawRule);
    for (BucketMetadata.DeletionRule delRule : rules) {
      assertEquals(delRule, BucketMetadata.DeletionRule.fromProto(delRule.toProto()));
    }
  }

  @Test
  public void testLifecycleRules() {
    Rule deleteLifecycleRule =
        new LifecycleRuleDefinition(
                AbstractLifecycleAction.newRemoveAction(),
                LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(10).buildLifecycleCondition())
            .toProto();

    assertEquals(
        BucketMetadata.LifecycleRuleDefinition.RemoveLifecycleAction.TYPE, deleteLifecycleRule.getAction().getType());
    assertEquals(10, deleteLifecycleRule.getCondition().getAge().intValue());

    Rule setStorageClassLifecycleRule =
        new BucketMetadata.LifecycleRuleDefinition(
                AbstractLifecycleAction.newUpdateStorageClassAction(StorageClassType.COLDLINE),
                LifecycleRuleCondition.newLifecycleConditionBuilder()
                    .setIsLive(true)
                    .setNumberOfNewerVersions(10)
                    .buildLifecycleCondition())
            .toProto();

    assertEquals(
        StorageClassType.COLDLINE.toString(),
        setStorageClassLifecycleRule.getAction().getStorageClass());
    assertTrue(setStorageClassLifecycleRule.getCondition().getIsLive());
    assertEquals(10, setStorageClassLifecycleRule.getCondition().getNumNewerVersions().intValue());
  }

  @Test
  public void testIamConfiguration() {
    Bucket.IamConfiguration iamConfiguration =
        BucketMetadata.BucketIamConfiguration.newUniformBucketLevelAccessBuilder()
            .setIsUniformBucketLevelAccessEnabled(true)
            .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
            .buildBucketIamConfiguration()
            .toProto();

    assertEquals(Boolean.TRUE, iamConfiguration.getUniformBucketLevelAccess().getEnabled());
    assertNotNull(iamConfiguration.getUniformBucketLevelAccess().getLockedTime());
  }

  @Test
  public void testLogging() {
    Bucket.Logging logging =
        BucketMetadata.LoggingConfig.newLogConfigBuilder()
            .setLogBucket("test-bucket")
            .setLogObjectPrefix("test-")
            .buildLoggingConfig()
            .toProto();
    assertEquals("test-bucket", logging.getLogBucket());
    assertEquals("test-", logging.getLogObjectPrefix());
  }
}
