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

import static com.google.cloud.storage.AclEntry.ProjectInfo.ProjectMemberRole.VIEWERS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule;
import com.google.cloud.storage.AclEntry.ProjectInfo;
import com.google.cloud.storage.AclEntry.UserIdentity;
import com.google.cloud.storage.BucketMetadata.CreatedBeforeDeletionRule;
import com.google.cloud.storage.BucketMetadata.DeleteActionRule;
import com.google.cloud.storage.BucketMetadata.DeleteActionRule.VersionFilterType;
import com.google.cloud.storage.BucketMetadata.LiveDeleteRule;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.LifecycleOperation;
import com.google.cloud.storage.BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition;
import com.google.cloud.storage.BucketMetadata.NumNewerVersionsDeletionRule;
import com.google.cloud.storage.BucketMetadata.UnprocessedDeleteRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BucketInfoTest {

  private static final List<AclEntry> ACL =
      ImmutableList.of(
          AclEntry.ofEntry(AclEntry.UserIdentity.allAuthenticatedUsers(), AclEntry.AccessRole.READER),
          AclEntry.ofEntry(new ProjectInfo(VIEWERS, "p1"), AclEntry.AccessRole.WRITER));
  private static final String ETAG = "0xFF00";
  private static final String GENERATED_ID = "B/N:1";
  private static final Long META_GENERATION = 10L;
  private static final AclEntry.UserIdentity OWNER = new AclEntry.UserIdentity("user@gmail.com");
  private static final String SELF_LINK = "http://storage/b/n";
  private static final Long CREATE_TIME = System.currentTimeMillis();
  private static final List<Cors> CORS = Collections.singletonList(Cors.newBuilder().build());
  private static final List<AclEntry> DEFAULT_ACL =
      Collections.singletonList(AclEntry.ofEntry(UserIdentity.allAuthenticatedUsers(), AclEntry.AccessRole.WRITER));
  private static final List<? extends DeleteActionRule> DELETE_RULES =
      Collections.singletonList(new BucketMetadata.AgeBasedDeleteRule(5));
  private static final List<? extends LifecycleRuleDefinition> LIFECYCLE_RULES =
      Collections.singletonList(
          new LifecycleRuleDefinition(
              LifecycleOperation.createDeleteAction(),
              LifecycleRuleCondition.newLifecycleRuleConditionBuilder().setAge(5).buildCondition()));
  private static final String INDEX_PAGE = "index.html";
  private static final BucketMetadata.IamSettings IAM_CONFIGURATION =
      BucketMetadata.IamSettings.newIamSettingsBuilder()
          .setIsUniformBucketLevelAccessEnabled(true)
          .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
          .buildIamSettings();
  private static final BucketMetadata.BucketLogging LOGGING =
      BucketMetadata.BucketLogging.newLogBucketBuilder()
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
          .buildBucket();
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
          .buildBucket();

  @Test
  public void testToBuilder() {
    compareBuckets(BUCKET_INFO, BUCKET_INFO.toBucketBuilder().buildBucket());
    BucketMetadata bucketInfo = BUCKET_INFO.toBucketBuilder().setName("B").setGeneratedId("id").buildBucket();
    assertEquals("B", bucketInfo.getName());
    assertEquals("id", bucketInfo.getGeneratedId());
    bucketInfo = bucketInfo.toBucketBuilder().setName("b").setGeneratedId(GENERATED_ID).buildBucket();
    compareBuckets(BUCKET_INFO, bucketInfo);
    assertEquals(ARCHIVE_STORAGE_CLASS, BUCKET_INFO_ARCHIVE.getStorageClass());
  }

  @Test
  public void testToBuilderIncomplete() {
    BucketMetadata incompleteBucketInfo = BucketMetadata.newBucketBuilder("b").buildBucket();
    compareBuckets(incompleteBucketInfo, incompleteBucketInfo.toBucketBuilder().buildBucket());
  }

  @Test
  public void testOf() {
    BucketMetadata bucketInfo = BucketMetadata.create("bucket");
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
    assertEquals(REQUESTER_PAYS, BUCKET_INFO.getRequesterPays());
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
    BucketMetadata bucketInfo = BucketMetadata.create("b");
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
    assertEquals(expected.getRequesterPays(), value.getRequesterPays());
    assertEquals(expected.getDefaultEventBasedHold(), value.getDefaultEventBasedHold());
    assertEquals(expected.getRetentionEffectiveTime(), value.getRetentionEffectiveTime());
    assertEquals(expected.getRetentionPeriod(), value.getRetentionPeriod());
    assertEquals(expected.isRetentionPolicyLocked(), value.isRetentionPolicyLocked());
    assertEquals(expected.getLogging(), value.getLogging());
  }

  @Test
  public void testDeleteRules() {
    BucketMetadata.AgeBasedDeleteRule ageRule = new BucketMetadata.AgeBasedDeleteRule(10);
    assertEquals(10, ageRule.getDaysToLive());
    assertEquals(10, ageRule.getDaysToLive());
    assertEquals(VersionFilterType.AGE, ageRule.getType());
    assertEquals(DeleteActionRule.VersionFilterType.AGE, ageRule.getType());
    CreatedBeforeDeletionRule createBeforeRule = new BucketMetadata.CreatedBeforeDeletionRule(1);
    assertEquals(1, createBeforeRule.getTimeMillis());
    assertEquals(1, createBeforeRule.getTimeMillis());
    assertEquals(VersionFilterType.CREATE_BEFORE, createBeforeRule.getType());
    NumNewerVersionsDeletionRule versionsRule = new BucketMetadata.NumNewerVersionsDeletionRule(2);
    assertEquals(2, versionsRule.getNumNewerVersions());
    assertEquals(2, versionsRule.getNumNewerVersions());
    assertEquals(VersionFilterType.NUM_NEWER_VERSIONS, versionsRule.getType());
    BucketMetadata.LiveDeleteRule isLiveRule = new LiveDeleteRule(true);
    assertTrue(isLiveRule.isLive());
    assertEquals(DeleteActionRule.VersionFilterType.IS_LIVE, isLiveRule.getType());
    assertEquals(BucketMetadata.DeleteActionRule.VersionFilterType.IS_LIVE, isLiveRule.getType());
    Rule rule = new Rule().set("a", "b");
    BucketMetadata.UnprocessedDeleteRule rawRule = new UnprocessedDeleteRule(rule);
    assertEquals(DeleteActionRule.VersionFilterType.IS_LIVE, isLiveRule.getType());
    assertEquals(DeleteActionRule.VersionFilterType.IS_LIVE, isLiveRule.getType());
    ImmutableList<DeleteActionRule> rules =
        ImmutableList.of(ageRule, createBeforeRule, versionsRule, isLiveRule, rawRule);
    for (DeleteActionRule delRule : rules) {
      assertEquals(delRule, DeleteActionRule.fromProto(delRule.toProto()));
    }
  }

  @Test
  public void testLifecycleRules() {
    Rule deleteLifecycleRule =
        new LifecycleRuleDefinition(
                LifecycleOperation.createDeleteAction(),
                LifecycleRuleDefinition.LifecycleRuleCondition.newLifecycleRuleConditionBuilder().setAge(10).buildCondition())
            .toProto();

    assertEquals(
        LifecycleRuleDefinition.RemoveLifecycleAction.TYPE, deleteLifecycleRule.getAction().getType());
    assertEquals(10, deleteLifecycleRule.getCondition().getAge().intValue());

    Rule setStorageClassLifecycleRule =
        new LifecycleRuleDefinition(
                LifecycleOperation.createSetStorageClassAction(StorageClass.COLDLINE),
                LifecycleRuleDefinition.LifecycleRuleCondition.newLifecycleRuleConditionBuilder()
                    .setIsLive(true)
                    .setNumberOfNewerVersions(10)
                    .buildCondition())
            .toProto();

    assertEquals(
        StorageClass.COLDLINE.toString(),
        setStorageClassLifecycleRule.getAction().getStorageClass());
    assertTrue(setStorageClassLifecycleRule.getCondition().getIsLive());
    assertEquals(10, setStorageClassLifecycleRule.getCondition().getNumNewerVersions().intValue());
  }

  @Test
  public void testIamConfiguration() {
    Bucket.IamConfiguration iamConfiguration =
        BucketMetadata.IamSettings.newIamSettingsBuilder()
            .setIsUniformBucketLevelAccessEnabled(true)
            .setUniformBucketLevelAccessLockedTime(System.currentTimeMillis())
            .buildIamSettings()
            .toProto();

    assertEquals(Boolean.TRUE, iamConfiguration.getUniformBucketLevelAccess().getEnabled());
    assertNotNull(iamConfiguration.getUniformBucketLevelAccess().getLockedTime());
  }

  @Test
  public void testLogging() {
    Bucket.Logging logging =
        BucketMetadata.BucketLogging.newLogBucketBuilder()
            .setLogBucket("test-bucket")
            .setLogObjectPrefix("test-")
            .buildBucketLogging()
            .toProto();
    assertEquals("test-bucket", logging.getLogBucket());
    assertEquals("test-", logging.getLogObjectPrefix());
  }
}
