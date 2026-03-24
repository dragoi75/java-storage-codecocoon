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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.transform;

import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Data;
import com.google.api.client.util.DateTime;
import com.google.api.core.BetaApi;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Bucket.Encryption;
import com.google.api.services.storage.model.Bucket.Lifecycle;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule;
import com.google.api.services.storage.model.Bucket.Owner;
import com.google.api.services.storage.model.Bucket.Versioning;
import com.google.api.services.storage.model.Bucket.Website;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.cloud.storage.Acl.Entity;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Google Storage bucket metadata;
 *
 * @see <a href="https://cloud.google.com/storage/docs/concepts-techniques#concepts">Concepts and
 *     Terminology</a>
 */
public class BucketMetadata implements Serializable {

  static final Function<com.google.api.services.storage.model.Bucket, BucketMetadata> BUCKET_TO_METADATA_FN =
      new Function<com.google.api.services.storage.model.Bucket, BucketMetadata>() {
        @Override
        public BucketMetadata apply(com.google.api.services.storage.model.Bucket pb) {
          return BucketMetadata.fromProto(pb);
        }
      };
  static final Function<BucketMetadata, com.google.api.services.storage.model.Bucket> METADATA_TO_BUCKET_FN =
      new Function<BucketMetadata, com.google.api.services.storage.model.Bucket>() {
        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketMetadata bucketInfo) {
          return bucketInfo.toBucketPb();
        }
      };
  private static final long CLASS_SERIAL_UID = -4712013629621638459L;
  private final String generatedIdentifier;
  private final String label;
  private final Acl.Entity ownerEntity;
  private final String resourceLink;
  private final Boolean billingEnabled;
  private final Boolean versioningActive;
  private final String mainPage;
  private final String errorPage;
  private final List<DeleteRule> removalRules;
  private final List<LifecycleRuleEntry> retentionPolicies;
  private final String entityTag;
  private final Long creationTimestamp;
  private final Long updatedTimestamp;
  private final Long metaGenerationNumber;
  private final List<Cors> crossOriginRules;
  private final List<Acl> accessControlList;
  private final List<Acl> defaultAccessControls;
  private final String region;
  private final Rpo recoveryObjective;
  private final StorageClass storageTier;
  private final Map<String, String> tags;
  private final String kmsKeyDefaultName;
  private final Boolean eventHoldDefault;
  private final Long retentionStartTime;
  private final Boolean retentionLocked;
  private final Long retentionDuration;
  private final BucketIamConfiguration identityConfig;
  private final String regionType;
  private final BucketLogging accessLogging;

  private static final Logger LOGGER = Logger.getLogger(BucketMetadata.class.getName());

  /**
   * Public Access Prevention enum with expected values.
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/public-access-prevention">public-access-prevention</a>
   */
  public enum PublicAccessPreventionMode {
    ENFORCED("enforced"),
    /**
     * Default value for Public Access Prevention
     *
     * @deprecated use {@link #INHERITED}
     */
    @Deprecated
    UNSPECIFIED("inherited"),
    /**
     * If the api returns a value that isn't defined in {@link PublicAccessPreventionMode} this value
     * will be returned.
     */
    UNKNOWN(null),
    INHERITED("inherited");

    private final String modeValue;

    PublicAccessPreventionMode(String modeValue) {
      this.modeValue = modeValue;
    }

    public String getValue() {
      return modeValue;
    }

    public static PublicAccessPreventionMode fromString(String modeValue) {
      String upperCase = modeValue.toUpperCase();
      switch (upperCase) {
        case "ENFORCED":
          return ENFORCED;
        case "UNSPECIFIED":
        case "INHERITED":
          return INHERITED;
        default:
          return UNKNOWN;
      }
    }
  }

  /**
   * The Bucket's IAM Configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/uniform-bucket-level-access">uniform
   *     bucket-level access</a>
   * @see <a
   *     href="https://cloud.google.com/storage/docs/public-access-prevention">public-access-prevention</a>
   */
  public static class BucketIamConfiguration implements Serializable {
    private static final long CLASS_SERIAL_UID = -8671736104909424616L;

    private final Boolean uniformAccessEnabled;
    private final Long uniformAccessLockTime;
    private final PublicAccessPreventionMode publicAccessMode;

    @Override
    public boolean equals(Object otherObj) {
      if (this == otherObj) return true;
      if (otherObj == null || getClass() != otherObj.getClass()) {
        return false;
      }
      BucketIamConfiguration otherConfig = (BucketIamConfiguration) otherObj;
      return Objects.equals(toProto(), otherConfig.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          uniformAccessEnabled,
          uniformAccessLockTime,
          publicAccessMode);
    }

    private BucketIamConfiguration(BucketAccessControlBuilder accessControlBuilder) {
      this.uniformAccessEnabled = accessControlBuilder.uniformAccessEnabled;
      this.uniformAccessLockTime = accessControlBuilder.uniformAccessLockTime;
      this.publicAccessMode = accessControlBuilder.publicAccessMode;
    }

    public static BucketAccessControlBuilder createBuilder() {
      return new BucketAccessControlBuilder();
    }

    public BucketAccessControlBuilder toBucketAccessControlBuilder() {
      BucketAccessControlBuilder accessControlBuilder = new BucketAccessControlBuilder();
      accessControlBuilder.uniformAccessEnabled = uniformAccessEnabled;
      accessControlBuilder.uniformAccessLockTime = uniformAccessLockTime;
      accessControlBuilder.publicAccessMode = publicAccessMode;
      return accessControlBuilder;
    }

    /** Deprecated in favor from isUniformBucketLevelAccessEnabled(). */
    @Deprecated
    public Boolean isBucketPolicyOnlyEnabled() {
      return uniformAccessEnabled;
    }

    /** Deprecated in favor from uniformBucketLevelAccessLockedTime(). */
    @Deprecated
    public Long getBucketPolicyOnlyLockedTime() {
      return uniformAccessLockTime;
    }

    public Boolean isUniformBucketLevelAccessEnabled() {
      return uniformAccessEnabled;
    }

    public Long getUniformBucketLevelAccessLockedTime() {
      return uniformAccessLockTime;
    }

    /** Returns the Public Access Prevention. * */
    public PublicAccessPreventionMode getPublicAccessPrevention() {
      return publicAccessMode;
    }

    Bucket.IamConfiguration toProto() {
      Bucket.IamConfiguration identityConfig = new Bucket.IamConfiguration();

      Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessProto =
          new Bucket.IamConfiguration.UniformBucketLevelAccess();
      uniformAccessProto.setEnabled(uniformAccessEnabled);
      uniformAccessProto.setLockedTime(
          uniformAccessLockTime == null
              ? null
              : new DateTime(uniformAccessLockTime));

      identityConfig.setUniformBucketLevelAccess(uniformAccessProto);
      identityConfig.setPublicAccessPrevention(
          publicAccessMode == null ? null : publicAccessMode.getValue());

      return identityConfig;
    }

    static BucketIamConfiguration fromProto(Bucket.IamConfiguration identityConfig) {
      Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessProto =
          identityConfig.getUniformBucketLevelAccess();
      DateTime lockedTimestamp = uniformAccessProto.getLockedTime();
      String publicAccessMode = identityConfig.getPublicAccessPrevention();

      PublicAccessPreventionMode papMode = null;
      if (publicAccessMode != null) {
        papMode = PublicAccessPreventionMode.fromString(publicAccessMode);
      }

      return createBuilder()
          .setIsUniformBucketLevelAccessEnabled(uniformAccessProto.getEnabled())
          .setUniformBucketLevelAccessLockedTime(lockedTimestamp == null ? null : lockedTimestamp.getValue())
          .setPublicAccessPrevention(papMode)
          .buildBucketIamConfiguration();
    }

    /** BucketAccessControlBuilder for {@code BucketIamConfiguration} */
    public static class BucketAccessControlBuilder {
      private Boolean uniformAccessEnabled;
      private Long uniformAccessLockTime;
      private PublicAccessPreventionMode publicAccessMode;

      /** Deprecated in favor from setIsUniformBucketLevelAccessEnabled(). */
      @Deprecated
      public BucketMetadata.BucketIamConfiguration.BucketAccessControlBuilder setIsBucketPolicyOnlyEnabled(Boolean policyOnlyEnabled) {
        this.uniformAccessEnabled = policyOnlyEnabled;
        return this;
      }

      /** Deprecated in favor from setUniformBucketLevelAccessLockedTime(). */
      @Deprecated
      BucketMetadata.BucketIamConfiguration.BucketAccessControlBuilder setBucketPolicyOnlyLockedTime(Long policyOnlyLockedAt) {
        this.uniformAccessLockTime = policyOnlyLockedAt;
        return this;
      }

      /**
       * Sets whether uniform bucket-level access is enabled for this bucket. When this is enabled,
       * access to the bucket will be configured through IAM, and legacy ACL policies will not work.
       * When this is first enabled, {@code uniformBucketLevelAccess.lockedTime} will be set by the
       * API automatically. This field can then be disabled until the time specified, after which it
       * will become immutable and calls to change it will fail. If this is enabled, calls to access
       * legacy ACL information will fail.
       */
      public BucketAccessControlBuilder setIsUniformBucketLevelAccessEnabled(
          Boolean uniformAccessEnabled) {
        this.uniformAccessEnabled = uniformAccessEnabled;
        return this;
      }

      /**
       * Sets the deadline for switching {@code uniformBucketLevelAccess.enabled} back to false.
       * After this time passes, calls to do so will fail. This is package-private, since in general
       * this field should never be set by a user--it's automatically set by the backend when {@code
       * enabled} is set to true.
       */
      BucketAccessControlBuilder setUniformBucketLevelAccessLockedTime(Long uniformAccessLockTime) {
        this.uniformAccessLockTime = uniformAccessLockTime;
        return this;
      }

      /**
       * Sets the bucket's Public Access Prevention configuration. Currently supported options are
       * {@link PublicAccessPreventionMode#INHERITED} or {@link PublicAccessPreventionMode#ENFORCED}
       *
       * @see <a
       *     href="https://cloud.google.com/storage/docs/public-access-prevention">public-access-prevention</a>
       */
      public BucketAccessControlBuilder setPublicAccessPrevention(PublicAccessPreventionMode publicAccessMode) {
        this.publicAccessMode = publicAccessMode;
        return this;
      }

      /** Builds an {@code BucketIamConfiguration} object */
      public BucketIamConfiguration buildBucketIamConfiguration() {
        return new BucketIamConfiguration(this);
      }
    }
  }

  /**
   * The bucket's logging configuration, which defines the destination bucket and optional name
   * prefix for the current bucket's logs.
   */
  public static class BucketLogging implements Serializable {

    private static final long CLASS_SERIAL_UID = -708892101216778492L;
    private String loggingBucket;
    private String logPrefix;

    @Override
    public boolean equals(Object otherObj) {
      if (this == otherObj) return true;
      if (otherObj == null || getClass() != otherObj.getClass()) {
        return false;
      }
      BucketLogging otherConfig = (BucketLogging) otherObj;
      return Objects.equals(toProto(), otherConfig.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(loggingBucket, logPrefix);
    }

    public static LogLocationBuilder newLogLocationBuilder() {
      return new LogLocationBuilder();
    }

    public LogLocationBuilder toBuilder() {
      LogLocationBuilder accessControlBuilder = new LogLocationBuilder();
      accessControlBuilder.loggingBucket = loggingBucket;
      accessControlBuilder.logPrefix = logPrefix;
      return accessControlBuilder;
    }

    public String getLogBucket() {
      return loggingBucket;
    }

    public String getLogObjectPrefix() {
      return logPrefix;
    }

    Bucket.Logging toProto() {
      Bucket.Logging accessLogging;
      if (loggingBucket != null || logPrefix != null) {
        accessLogging = new Bucket.Logging();
        accessLogging.setLogBucket(loggingBucket);
        accessLogging.setLogObjectPrefix(logPrefix);
      } else {
        accessLogging = Data.nullOf(Bucket.Logging.class);
      }
      return accessLogging;
    }

    static BucketLogging fromProto(Bucket.Logging accessLogging) {
      return newLogLocationBuilder()
          .setLogBucket(accessLogging.getLogBucket())
          .setLogObjectPrefix(accessLogging.getLogObjectPrefix())
          .buildBucketLogging();
    }

    private BucketLogging(LogLocationBuilder accessControlBuilder) {
      this.loggingBucket = accessControlBuilder.loggingBucket;
      this.logPrefix = accessControlBuilder.logPrefix;
    }

    public static class LogLocationBuilder {
      private String loggingBucket;
      private String logPrefix;

      /** The destination bucket where the current bucket's logs should be placed. */
      public LogLocationBuilder setLogBucket(String loggingBucket) {
        this.loggingBucket = loggingBucket;
        return this;
      }

      /** A prefix for log object names. */
      public LogLocationBuilder setLogObjectPrefix(String logPrefix) {
        this.logPrefix = logPrefix;
        return this;
      }

      /** Builds an {@code BucketLogging} object */
      public BucketLogging buildBucketLogging() {
        return new BucketLogging(this);
      }
    }
  }

  /**
   * Lifecycle rule for a bucket. Allows supported Actions, such as deleting and changing storage
   * class, to be executed when certain Conditions are met.
   *
   * <p>Versions 1.50.0-1.111.2 from this library don’t support the CustomTimeBefore,
   * DaysSinceCustomTime, DaysSinceNoncurrentTime and NoncurrentTimeBefore lifecycle conditions. To
   * read GCS objects with those lifecycle conditions, update your Java client library to the latest
   * version.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle#actions">Object Lifecycle
   *     Management</a>
   */
  public static class LifecycleRuleEntry implements Serializable {

    private static final long CLASS_SERIAL_UID = -5739807320148748613L;
    private final LifecycleRuleAction actionEntry;
    private final LifecycleRuleCondition conditionEntry;

    public LifecycleRuleEntry(LifecycleRuleAction lifecycleActionParam, LifecycleRuleCondition lifecycleConditionParam) {
      if (lifecycleConditionParam.getIsLive() == null
          && lifecycleConditionParam.getAge() == null
          && lifecycleConditionParam.getCreatedBefore() == null
          && lifecycleConditionParam.getMatchesStorageClass() == null
          && lifecycleConditionParam.getNumberOfNewerVersions() == null
          && lifecycleConditionParam.getDaysSinceNoncurrentTime() == null
          && lifecycleConditionParam.getNoncurrentTimeBefore() == null
          && lifecycleConditionParam.getCustomTimeBefore() == null
          && lifecycleConditionParam.getDaysSinceCustomTime() == null) {
        LOGGER.warning(
            "Creating a lifecycle condition with no supported conditions:\n"
                + this
                + "\nAttempting to update with this rule may cause errors. Please update "
                + " to the latest version from google-cloud-storage");
      }

      this.actionEntry = lifecycleActionParam;
      this.conditionEntry = lifecycleConditionParam;
    }

    public LifecycleRuleAction getAction() {
      return actionEntry;
    }

    public LifecycleRuleCondition getCondition() {
      return conditionEntry;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("lifecycleAction", actionEntry)
          .add("lifecycleCondition", conditionEntry)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(actionEntry, conditionEntry);
    }

    @Override
    public boolean equals(Object otherObj) {
      if (this == otherObj) {
        return true;
      }
      if (otherObj == null || getClass() != otherObj.getClass()) {
        return false;
      }
      final LifecycleRuleEntry otherConfig = (LifecycleRuleEntry) otherObj;
      return Objects.equals(toProto(), otherConfig.toProto());
    }

    Rule toProto() {
      Rule protoRule = new Rule();

      Rule.Action lifecycleActionParam = new Rule.Action().setType(actionEntry.getActionType());
      if (actionEntry.getActionType().equals(SetStorageClassLifecycleOperation.TYPE)) {
        lifecycleActionParam.setStorageClass(
            ((SetStorageClassLifecycleOperation) actionEntry).getStorageClass().toString());
      }

      protoRule.setAction(lifecycleActionParam);

      Rule.Condition lifecycleConditionParam =
          new Rule.Condition()
              .setAge(conditionEntry.getAge())
              .setCreatedBefore(
                  conditionEntry.getCreatedBefore() == null
                      ? null
                      : new DateTime(true, conditionEntry.getCreatedBefore().getValue(), 0))
              .setIsLive(conditionEntry.getIsLive())
              .setNumNewerVersions(conditionEntry.getNumberOfNewerVersions())
              .setMatchesStorageClass(
                  conditionEntry.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          conditionEntry.getMatchesStorageClass(),
                          Functions.toStringFunction()))
              .setDaysSinceNoncurrentTime(conditionEntry.getDaysSinceNoncurrentTime())
              .setNoncurrentTimeBefore(
                  conditionEntry.getNoncurrentTimeBefore() == null
                      ? null
                      : new DateTime(
                          true, conditionEntry.getNoncurrentTimeBefore().getValue(), 0))
              .setCustomTimeBefore(
                  conditionEntry.getCustomTimeBefore() == null
                      ? null
                      : new DateTime(true, conditionEntry.getCustomTimeBefore().getValue(), 0))
              .setDaysSinceCustomTime(conditionEntry.getDaysSinceCustomTime());

      protoRule.setCondition(lifecycleConditionParam);

      return protoRule;
    }

    static LifecycleRuleEntry fromProto(Rule protoRule) {
      LifecycleRuleAction actionEntry;

      Rule.Action lifecycleActionParam = protoRule.getAction();

      switch (lifecycleActionParam.getType()) {
        case RemoveLifecycleAction.TYPE:
          actionEntry = LifecycleRuleAction.newRemoveAction();
          break;
        case SetStorageClassLifecycleOperation.TYPE:
          actionEntry =
              LifecycleRuleAction.createSetStorageClassAction(
                  StorageClass.valueOf(lifecycleActionParam.getStorageClass()));
          break;
        case AbortIncompleteMultipartUploadAction.TYPE:
          actionEntry = LifecycleRuleAction.newAbortIncompleteMultipartUploadAction();
          break;
        default:
          LOGGER.warning(
              "The lifecycle action "
                  + lifecycleActionParam.getType()
                  + " is not supported by this version from the library. "
                  + "Attempting to update with this rule may cause errors. Please "
                  + "update to the latest version from google-cloud-storage.");
          actionEntry = LifecycleRuleAction.createLifecycleAction("Unknown action");
      }

      Rule.Condition lifecycleConditionParam = protoRule.getCondition();

      LifecycleRuleCondition.LifecycleConditionBuilder lifecycleConditionBuilder =
          LifecycleRuleCondition.newLifecycleConditionBuilder()
              .setAge(lifecycleConditionParam.getAge())
              .setCreatedBefore(lifecycleConditionParam.getCreatedBefore())
              .setIsLive(lifecycleConditionParam.getIsLive())
              .setNumberOfNewerVersions(lifecycleConditionParam.getNumNewerVersions())
              .setMatchesStorageClass(
                  lifecycleConditionParam.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          lifecycleConditionParam.getMatchesStorageClass(),
                          new Function<String, StorageClass>() {
                            public StorageClass apply(String storageClass) {
                              return StorageClass.valueOf(storageClass);
                            }
                          }))
              .setDaysSinceNoncurrentTime(lifecycleConditionParam.getDaysSinceNoncurrentTime())
              .setNoncurrentTimeBefore(lifecycleConditionParam.getNoncurrentTimeBefore())
              .setCustomTimeBefore(lifecycleConditionParam.getCustomTimeBefore())
              .setDaysSinceCustomTime(lifecycleConditionParam.getDaysSinceCustomTime());

      return new LifecycleRuleEntry(actionEntry, lifecycleConditionBuilder.buildLifecycleRuleCondition());
    }

    /**
     * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
     *     Management</a>
     */
    public static class LifecycleRuleCondition implements Serializable {
      private static final long CLASS_SERIAL_UID = -6482314338394768785L;
      private final Integer ageDays;
      private final DateTime createdPriorTo;
      private final Integer newerVersionCount;
      private final Boolean liveFlag;
      private final List<StorageClass> storageClassMatches;
      private final Integer daysSinceNoncurrent;
      private final DateTime noncurrentBefore;
      private final DateTime customBefore;
      private final Integer daysSinceCustom;

      private LifecycleRuleCondition(LifecycleConditionBuilder accessControlBuilder) {
        this.ageDays = accessControlBuilder.ageDays;
        this.createdPriorTo = accessControlBuilder.createdPriorTo;
        this.newerVersionCount = accessControlBuilder.newerVersionCount;
        this.liveFlag = accessControlBuilder.liveFlag;
        this.storageClassMatches = accessControlBuilder.storageClassMatches;
        this.daysSinceNoncurrent = accessControlBuilder.daysSinceNoncurrent;
        this.noncurrentBefore = accessControlBuilder.noncurrentBefore;
        this.customBefore = accessControlBuilder.customBefore;
        this.daysSinceCustom = accessControlBuilder.daysSinceCustom;
      }

      public LifecycleConditionBuilder toBuilder() {
        return newLifecycleConditionBuilder()
            .setAge(this.ageDays)
            .setCreatedBefore(this.createdPriorTo)
            .setNumberOfNewerVersions(this.newerVersionCount)
            .setIsLive(this.liveFlag)
            .setMatchesStorageClass(this.storageClassMatches)
            .setDaysSinceNoncurrentTime(this.daysSinceNoncurrent)
            .setNoncurrentTimeBefore(this.noncurrentBefore)
            .setCustomTimeBefore(this.customBefore)
            .setDaysSinceCustomTime(this.daysSinceCustom);
      }

      public static LifecycleConditionBuilder newLifecycleConditionBuilder() {
        return new LifecycleConditionBuilder();
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("age", ageDays)
            .add("createBefore", createdPriorTo)
            .add("numberofNewerVersions", newerVersionCount)
            .add("isLive", liveFlag)
            .add("matchesStorageClass", storageClassMatches)
            .add("daysSinceNoncurrentTime", daysSinceNoncurrent)
            .add("noncurrentTimeBefore", noncurrentBefore)
            .add("customTimeBefore", customBefore)
            .add("daysSinceCustomTime", daysSinceCustom)
            .toString();
      }

      public Integer getAge() {
        return ageDays;
      }

      public DateTime getCreatedBefore() {
        return createdPriorTo;
      }

      public Integer getNumberOfNewerVersions() {
        return newerVersionCount;
      }

      public Boolean getIsLive() {
        return liveFlag;
      }

      public List<StorageClass> getMatchesStorageClass() {
        return storageClassMatches;
      }

      /** Returns the number from days elapsed since the noncurrent timestamp from an object. */
      public Integer getDaysSinceNoncurrentTime() {
        return daysSinceNoncurrent;
      }

      /**
       * Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
       */
      public DateTime getNoncurrentTimeBefore() {
        return noncurrentBefore;
      }

      /* Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").*/
      public DateTime getCustomTimeBefore() {
        return customBefore;
      }

      /** Returns the number from days elapsed since the user-specified timestamp set on an object. */
      public Integer getDaysSinceCustomTime() {
        return daysSinceCustom;
      }

      /** BucketAccessControlBuilder for {@code LifecycleRuleCondition}. */
      public static class LifecycleConditionBuilder {
        private Integer ageDays;
        private DateTime createdPriorTo;
        private Integer newerVersionCount;
        private Boolean liveFlag;
        private List<StorageClass> storageClassMatches;
        private Integer daysSinceNoncurrent;
        private DateTime noncurrentBefore;
        private DateTime customBefore;
        private Integer daysSinceCustom;

        private LifecycleConditionBuilder() {}

        /**
         * Sets the age in days. This condition is satisfied when a Blob reaches the specified age
         * (in days). When you specify the Age condition, you are specifying a Time to Live (TTL)
         * for objects in a bucket with lifecycle management configured. The time when the Age
         * condition is considered to be satisfied is calculated by adding the specified value to
         * the object creation time.
         */
        public LifecycleConditionBuilder setAge(Integer ageDays) {
          this.ageDays = ageDays;
          return this;
        }

        /**
         * Sets the date a Blob should be created before for an Action to be executed. Note that
         * only the date will be considered, if the time is specified it will be truncated. This
         * condition is satisfied when an object is created before midnight from the specified date in
         * UTC. *
         */
        public LifecycleConditionBuilder setCreatedBefore(DateTime createdPriorTo) {
          this.createdPriorTo = createdPriorTo;
          return this;
        }

        /**
         * Sets the number from newer versions a Blob should have for an Action to be executed.
         * Relevant only when versioning is enabled on a bucket. *
         */
        public LifecycleConditionBuilder setNumberOfNewerVersions(Integer newerVersionCount) {
          this.newerVersionCount = newerVersionCount;
          return this;
        }

        /**
         * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
         * only live Blobs; if the value is false, it matches only archived objects. For the
         * purposes from this condition, Blobs in non-versioned buckets are considered live.
         */
        public LifecycleConditionBuilder setIsLive(Boolean isActive) {
          this.liveFlag = isActive;
          return this;
        }

        /**
         * Sets a list from Storage Classes for a objects that satisfy the condition to execute the
         * Action. *
         */
        public LifecycleConditionBuilder setMatchesStorageClass(List<StorageClass> storageClassMatches) {
          this.storageClassMatches = storageClassMatches;
          return this;
        }

        /**
         * Sets the number from days elapsed since the noncurrent timestamp from an object. The
         * condition is satisfied if the days elapsed is at least this number. This condition is
         * relevant only for versioned objects. The value from the field must be a nonnegative
         * integer. If it's zero, the object version will become eligible for Lifecycle action as
         * soon as it becomes noncurrent.
         */
        public LifecycleConditionBuilder setDaysSinceNoncurrentTime(Integer daysSinceNoncurrent) {
          this.daysSinceNoncurrent = daysSinceNoncurrent;
          return this;
        }

        /**
         * Sets the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
         * Note that only date part will be considered, if the time is specified it will be
         * truncated. This condition is satisfied when the noncurrent time on an object is before
         * this date. This condition is relevant only for versioned objects.
         */
        public LifecycleConditionBuilder setNoncurrentTimeBefore(DateTime noncurrentBefore) {
          this.noncurrentBefore = noncurrentBefore;
          return this;
        }

        /**
         * Sets the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
         * Note that only date part will be considered, if the time is specified it will be
         * truncated. This condition is satisfied when the custom time on an object is before this
         * date in UTC.
         */
        public LifecycleConditionBuilder setCustomTimeBefore(DateTime customBefore) {
          this.customBefore = customBefore;
          return this;
        }

        /**
         * Sets the number from days elapsed since the user-specified timestamp set on an object. The
         * condition is satisfied if the days elapsed is at least this number. If no custom
         * timestamp is specified on an object, the condition does not apply.
         */
        public LifecycleConditionBuilder setDaysSinceCustomTime(Integer daysSinceCustom) {
          this.daysSinceCustom = daysSinceCustom;
          return this;
        }

        /** Builds a {@code LifecycleRuleCondition} object. * */
        public LifecycleRuleCondition buildLifecycleRuleCondition() {
          return new LifecycleRuleCondition(this);
        }
      }
    }

    /**
     * Base class for the Action to take when a Lifecycle Condition is met. Supported Actions are
     * expressed as subclasses from this class, accessed by static factory methods.
     */
    public static class LifecycleRuleAction implements Serializable {
      private static final long CLASS_SERIAL_UID = 5801228724709173284L;

      private final String operationType;

      public LifecycleRuleAction(String operationType) {
        this.operationType = operationType;
      }

      public String getActionType() {
        return operationType;
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this).add("actionType", getActionType()).toString();
      }

      /**
       * Creates a new {@code RemoveLifecycleAction}. Blobs that meet the Condition associated with
       * this action will be deleted.
       */
      public static RemoveLifecycleAction newRemoveAction() {
        return new RemoveLifecycleAction();
      }

      /**
       * Creates a new {@code SetStorageClassLifecycleOperation}. A Blob's storage class that meets the
       * action's conditions will be changed to the specified storage class.
       *
       * @param storageTier The new storage class to use when conditions are met for this action.
       */
      public static SetStorageClassLifecycleOperation createSetStorageClassAction(
          StorageClass storageTier) {
        return new SetStorageClassLifecycleOperation(storageTier);
      }

      /**
       * Create a new {@code AbortIncompleteMultipartUploadAction}. An incomplete multipart upload will be
       * aborted when the multipart upload meets the specified condition. Age is the only condition
       * supported for this action. See: https://cloud.google.com/storage/docs/lifecycle##abort-mpu
       */
      public static LifecycleRuleAction newAbortIncompleteMultipartUploadAction() {
        return new AbortIncompleteMultipartUploadAction();
      }

      /**
       * Creates a new {@code LifecycleRuleAction , with no specific supported action associated with it. This
       * is only intended as a "backup" for when the library doesn't recognize the type, and should
       * generally not be used, instead use the supported actions, and upgrade the library if necessary
       * to get new supported actions.
       */
      public static LifecycleRuleAction createLifecycleAction(String operationType) {
        return new LifecycleRuleAction(operationType);
      }
    }

    public static class RemoveLifecycleAction extends LifecycleRuleAction {
      public static final String TYPE = "Delete";
      private static final long CLASS_SERIAL_UID = -2050986302222644873L;

      private RemoveLifecycleAction() {
        super(TYPE);
      }
    }

    public static class SetStorageClassLifecycleOperation extends LifecycleRuleAction {
      public static final String TYPE = "SetStorageClass";
      private static final long CLASS_SERIAL_UID = -62615467186000899L;

      private final StorageClass storageTier;

      private SetStorageClassLifecycleOperation(StorageClass storageTier) {
        super(TYPE);
        this.storageTier = storageTier;
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("actionType", getActionType())
            .add("storageClass", storageTier.name())
            .toString();
      }

      public StorageClass getStorageClass() {
        return storageTier;
      }
    }

    public static class AbortIncompleteMultipartUploadAction extends LifecycleRuleAction {
      public static final String TYPE = "AbortIncompleteMultipartUpload";
      private static final long CLASS_SERIAL_UID = -1072182310389348060L;

      private AbortIncompleteMultipartUploadAction() {
        super(TYPE);
      }
    }
  }

  /**
   * Base class for bucket's delete rules. Allows to configure automatic deletion from blobs and blobs
   * versions.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleEntry} with a {@code RemoveLifecycleAction} and a {@code
   *     LifecycleRuleCondition} which is equivalent to a subclass from DeleteRule instead.
   */
  @Deprecated
  public abstract static class DeleteRule implements Serializable {

    private static final long CLASS_SERIAL_UID = 3137971668395933033L;
    private static final String SUPPORTED_OPERATION = "Delete";
    private final VersionFilterType versionFilter;

    public enum VersionFilterType {
      AGE,
      CREATE_BEFORE,
      NUM_NEWER_VERSIONS,
      IS_LIVE,
      UNKNOWN
    }

    DeleteRule(VersionFilterType versionFilter) {
      this.versionFilter = versionFilter;
    }

    public VersionFilterType getType() {
      return versionFilter;
    }

    @Override
    public int hashCode() {
      return Objects.hash(versionFilter);
    }

    @Override
    public boolean equals(Object otherObj) {
      if (this == otherObj) {
        return true;
      }
      if (otherObj == null || getClass() != otherObj.getClass()) {
        return false;
      }
      final DeleteRule otherConfig = (DeleteRule) otherObj;
      return Objects.equals(toProto(), otherConfig.toProto());
    }

    Rule toProto() {
      Rule protoRule = new Rule();
      protoRule.setAction(new Rule.Action().setType(SUPPORTED_OPERATION));
      Rule.Condition lifecycleConditionParam = new Rule.Condition();
      populateDeleteCondition(lifecycleConditionParam);
      protoRule.setCondition(lifecycleConditionParam);
      return protoRule;
    }

    abstract void populateDeleteCondition(Rule.Condition lifecycleConditionParam);

    static DeleteRule fromProto(Rule protoRule) {
      if (protoRule.getAction() != null && SUPPORTED_OPERATION.endsWith(protoRule.getAction().getType())) {
        Rule.Condition lifecycleConditionParam = protoRule.getCondition();
        Integer ageDays = lifecycleConditionParam.getAge();
        if (ageDays != null) {
          return new AgeDeleteRule(ageDays);
        }
        DateTime timestamp = lifecycleConditionParam.getCreatedBefore();
        if (timestamp != null) {
          return new CreatedBeforeDeleteRule(timestamp.getValue());
        }
        Integer newerVersionCount = lifecycleConditionParam.getNumNewerVersions();
        if (newerVersionCount != null) {
          return new NumNewerVersionsDeleteRule(newerVersionCount);
        }
        Boolean liveFlag = lifecycleConditionParam.getIsLive();
        if (liveFlag != null) {
          return new IsLiveDeleteRule(liveFlag);
        }
      }
      return new RawDeletionRule(protoRule);
    }
  }

  /**
   * Delete rule class that sets a Time To Live for blobs in the bucket.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleEntry} with a {@code RemoveLifecycleAction} and use {@code
   *     LifecycleRuleCondition.BucketAccessControlBuilder.setAge} instead.
   *     <p>For example, {@code new RemoveLifecycleAction(1)} is equivalent to {@code new
   *     LifecycleRuleEntry( LifecycleRuleAction.newRemoveAction(),
   *     LifecycleRuleCondition.createBuilder().setAge(1).buildBucketIamConfiguration()))}
   */
  @Deprecated
  public static class AgeDeleteRule extends DeleteRule {

    private static final long CLASS_SERIAL_UID = 5697166940712116380L;
    private final int lifetimeDays;

    /**
     * Creates an {@code AgeDeleteRule} object.
     *
     * @param lifetimeDays blobs' Time To Live expressed in days. The time when the age condition is
     *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
     *     following blob's creation time in UTC.
     */
    public AgeDeleteRule(int lifetimeDays) {
      super(VersionFilterType.AGE);
      this.lifetimeDays = lifetimeDays;
    }

    public int getDaysToLive() {
      return lifetimeDays;
    }

    @Override
    void populateDeleteCondition(Rule.Condition lifecycleConditionParam) {
      lifecycleConditionParam.setAge(lifetimeDays);
    }
  }

  static class RawDeletionRule extends DeleteRule {

    private static final long CLASS_SERIAL_UID = -7166938278642301933L;

    private transient Rule protoRule;

    RawDeletionRule(Rule protoRule) {
      super(VersionFilterType.UNKNOWN);
      this.protoRule = protoRule;
    }

    @Override
    void populateDeleteCondition(Rule.Condition lifecycleConditionParam) {
      LOGGER.warning(
          "The lifecycle condition "
              + lifecycleConditionParam
              + " is not currently supported. Please update to the latest version from google-cloud-java."
              + " Also, use LifecycleRuleEntry rather than the deprecated DeleteRule.");
    }

    private void writeObjectData(ObjectOutputStream objectOut) throws IOException {
      objectOut.defaultWriteObject();
      objectOut.writeUTF(protoRule.toString());
    }

    private void readObjectData(ObjectInputStream objectIn) throws IOException, ClassNotFoundException {
      objectIn.defaultReadObject();
      protoRule = new JacksonFactory().fromString(objectIn.readUTF(), Rule.class);
    }

    @Override
    Rule toProto() {
      return protoRule;
    }
  }

  /**
   * Delete rule class for blobs in the bucket that have been created before a certain date.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleEntry} with an action {@code RemoveLifecycleAction} and a
   *     condition {@code LifecycleRuleCondition.BucketAccessControlBuilder.setCreatedBefore} instead.
   */
  @Deprecated
  public static class CreatedBeforeDeleteRule extends DeleteRule {

    private static final long CLASS_SERIAL_UID = 881692650279195867L;
    private final long timestampMillis;

    /**
     * Creates an {@code CreatedBeforeDeleteRule} object.
     *
     * @param timestampMillis a date in UTC. Blobs that have been created before midnight from the provided
     *     date meet the delete condition
     */
    public CreatedBeforeDeleteRule(long timestampMillis) {
      super(VersionFilterType.CREATE_BEFORE);
      this.timestampMillis = timestampMillis;
    }

    public long getTimeMillis() {
      return timestampMillis;
    }

    @Override
    void populateDeleteCondition(Rule.Condition lifecycleConditionParam) {
      lifecycleConditionParam.setCreatedBefore(new DateTime(true, timestampMillis, 0));
    }
  }

  /**
   * Delete rule class for versioned blobs. Specifies when to delete a blob's version according to
   * the number from available newer versions for that blob.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleEntry} with a {@code RemoveLifecycleAction} and a condition
   *     {@code LifecycleRuleCondition.BucketAccessControlBuilder.setNumberOfNewerVersions} instead.
   */
  @Deprecated
  public static class NumNewerVersionsDeleteRule extends DeleteRule {

    private static final long CLASS_SERIAL_UID = -1955554976528303894L;
    private final int newerVersionCount;

    /**
     * Creates an {@code NumNewerVersionsDeleteRule} object.
     *
     * @param newerVersionCount the number from newer versions. A blob's version meets the delete
     *     condition when {@code numNewerVersions} newer versions are available.
     */
    public NumNewerVersionsDeleteRule(int newerVersionCount) {
      super(VersionFilterType.NUM_NEWER_VERSIONS);
      this.newerVersionCount = newerVersionCount;
    }

    public int getNumNewerVersions() {
      return newerVersionCount;
    }

    @Override
    void populateDeleteCondition(Rule.Condition lifecycleConditionParam) {
      lifecycleConditionParam.setNumNewerVersions(newerVersionCount);
    }
  }

  /**
   * Delete rule class to distinguish between live and archived blobs.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleEntry} with a {@code RemoveLifecycleAction} and a condition
   *     {@code LifecycleRuleCondition.BucketAccessControlBuilder.setIsLive} instead.
   */
  @Deprecated
  public static class IsLiveDeleteRule extends DeleteRule {

    private static final long CLASS_SERIAL_UID = -3502994563121313364L;
    private final boolean liveFlag;

    /**
     * Creates an {@code IsLiveDeleteRule} object.
     *
     * @param liveFlag if set to {@code true} live blobs meet the delete condition. If set to {@code
     *     false} delete condition is met by archived blobs.
     */
    public IsLiveDeleteRule(boolean liveFlag) {
      super(VersionFilterType.IS_LIVE);
      this.liveFlag = liveFlag;
    }

    public boolean isLive() {
      return liveFlag;
    }

    @Override
    void populateDeleteCondition(Rule.Condition lifecycleConditionParam) {
      lifecycleConditionParam.setIsLive(liveFlag);
    }
  }

  /** BucketAccessControlBuilder for {@code BucketMetadata}. */
  public abstract static class BucketBuilder {
    BucketBuilder() {}

    /** Sets the bucket's name. */
    public abstract BucketBuilder setName(String label);

    abstract BucketBuilder setGeneratedId(String generatedIdentifier);

    abstract BucketBuilder setOwner(Acl.Entity ownerEntity);

    abstract BucketBuilder setSelfLink(String resourceLink);

    /**
     * Sets whether a user accessing the bucket or an object it contains should assume the transit
     * costs related to the access.
     */
    public abstract BucketBuilder setRequesterPays(Boolean billingEnabled);

    /**
     * Sets whether versioning should be enabled for this bucket. When set to true, versioning is
     * fully enabled.
     */
    public abstract BucketBuilder setVersioningEnabled(Boolean versioningFlag);

    /**
     * Sets the bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public abstract BucketBuilder setIndexPage(String mainPage);

    /** Sets the custom object to return when a requested resource is not found. */
    public abstract BucketBuilder setNotFoundPage(String errorPage);

    /**
     * Sets the bucket's lifecycle configuration as a number from delete rules.
     *
     * @deprecated Use {@code setLifecycleRules} instead, as in {@code
     *     setLifecycleRules(Collections.singletonList( new BucketMetadata.LifecycleRuleEntry(
     *     LifecycleRuleAction.newRemoveAction(), LifecycleRuleCondition.createBuilder().setAge(5).buildBucketIamConfiguration())));}
     */
    @Deprecated
    public abstract BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends DeleteRule> deletionRules);

    /**
     * Sets the bucket's lifecycle configuration as a number from lifecycle rules, consisting from an
     * action and a condition.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
     *     Management</a>
     */
    public abstract BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleEntry> deletionRules);

    /** Deletes the lifecycle rules from this bucket. */
    public abstract BucketBuilder removeLifecycleRules();

    /**
     * Sets the bucket's Recovery Point Objective (RPO). This can only be set for a dual-region
     * bucket, and determines the speed at which data will be replicated between regions. See the
     * {@code Rpo} class for supported values, and <a
     * href="https://cloud.google.com/storage/docs/turbo-replication">here</a> for additional
     * details.
     */
    public abstract BucketBuilder setRpo(Rpo recoveryObjective);

    /**
     * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
     * determines the SLA and the cost from storage. A list from supported values is available <a
     * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
     */
    public abstract BucketBuilder setStorageClass(StorageClass storageTier);

    /**
     * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
     * this region or regions. A list from supported values is available <a
     * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
     */
    public abstract BucketBuilder setLocation(String region);

    abstract BucketBuilder setEtag(String entityTag);

    abstract BucketBuilder setCreateTime(Long creationTimestamp);

    abstract BucketBuilder setUpdateTime(Long updatedTimestamp);

    abstract BucketBuilder setMetageneration(Long metaGenerationNumber);

    abstract BucketBuilder setLocationType(String regionType);

    /**
     * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
     *     Sharing (CORS)</a>
     */
    public abstract BucketBuilder setCors(Iterable<Cors> crossOriginRules);

    /**
     * Sets the bucket's access control configuration.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract BucketBuilder setAcl(Iterable<Acl> accessControlList);

    /**
     * Sets the default access control configuration to apply to bucket's blobs when no other
     * configuration is specified.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract BucketBuilder setDefaultAcl(Iterable<Acl> accessControlList);

    /** Sets the label from this bucket. */
    public abstract BucketBuilder setLabels(Map<String, String> tags);

    /** Sets the default Cloud KMS key name for this bucket. */
    public abstract BucketBuilder setDefaultKmsKeyName(String kmsKeyDefaultName);

    /** Sets the default event-based hold for this bucket. */
    @BetaApi
    public abstract BucketBuilder setDefaultEventBasedHold(Boolean eventHoldDefault);

    @BetaApi
    abstract BucketBuilder setRetentionEffectiveTime(Long retentionStartTime);

    @BetaApi
    abstract BucketBuilder setRetentionPolicyIsLocked(Boolean retentionLocked);

    /**
     * If policy is not locked this value can be cleared, increased, and decreased. If policy is
     * locked the retention period can only be increased.
     */
    @BetaApi
    public abstract BucketBuilder setRetentionPeriod(Long retentionDuration);

    /**
     * Sets the BucketIamConfiguration to specify whether IAM access should be enabled.
     *
     * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
     *     Only</a>
     */
    @BetaApi
    public abstract BucketBuilder setIamConfiguration(BucketIamConfiguration identityConfig);

    public abstract BucketBuilder setLogging(BucketLogging accessLogging);

    /** Creates a {@code BucketMetadata} object. */
    public abstract BucketMetadata buildBucket();
  }

  static final class BucketBuilderImpl extends BucketBuilder {

    private String generatedIdentifier;
    private String label;
    private Acl.Entity ownerEntity;
    private String resourceLink;
    private Boolean billingEnabled;
    private Boolean versioningActive;
    private String mainPage;
    private String errorPage;
    private List<DeleteRule> removalRules;
    private List<LifecycleRuleEntry> retentionPolicies;
    private Rpo recoveryObjective;
    private StorageClass storageTier;
    private String region;
    private String entityTag;
    private Long creationTimestamp;
    private Long updatedTimestamp;
    private Long metaGenerationNumber;
    private List<Cors> crossOriginRules;
    private List<Acl> accessControlList;
    private List<Acl> defaultAccessControls;
    private Map<String, String> tags;
    private String kmsKeyDefaultName;
    private Boolean eventHoldDefault;
    private Long retentionStartTime;
    private Boolean retentionLocked;
    private Long retentionDuration;
    private BucketIamConfiguration identityConfig;
    private String regionType;
    private BucketLogging accessLogging;

    BucketBuilderImpl(String label) {
      this.label = label;
    }

    BucketBuilderImpl(BucketMetadata metadata) {
      generatedIdentifier = metadata.generatedIdentifier;
      label = metadata.label;
      entityTag = metadata.entityTag;
      creationTimestamp = metadata.creationTimestamp;
      updatedTimestamp = metadata.updatedTimestamp;
      metaGenerationNumber = metadata.metaGenerationNumber;
      region = metadata.region;
      recoveryObjective = metadata.recoveryObjective;
      storageTier = metadata.storageTier;
      crossOriginRules = metadata.crossOriginRules;
      accessControlList = metadata.accessControlList;
      defaultAccessControls = metadata.defaultAccessControls;
      ownerEntity = metadata.ownerEntity;
      resourceLink = metadata.resourceLink;
      versioningActive = metadata.versioningActive;
      mainPage = metadata.mainPage;
      errorPage = metadata.errorPage;
      removalRules = metadata.removalRules;
      retentionPolicies = metadata.retentionPolicies;
      tags = metadata.tags;
      billingEnabled = metadata.billingEnabled;
      kmsKeyDefaultName = metadata.kmsKeyDefaultName;
      eventHoldDefault = metadata.eventHoldDefault;
      retentionStartTime = metadata.retentionStartTime;
      retentionLocked = metadata.retentionLocked;
      retentionDuration = metadata.retentionDuration;
      identityConfig = metadata.identityConfig;
      regionType = metadata.regionType;
      accessLogging = metadata.accessLogging;
    }

    @Override
    public BucketMetadata.BucketBuilder setName(String label) {
      this.label = checkNotNull(label);
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setGeneratedId(String generatedIdentifier) {
      this.generatedIdentifier = generatedIdentifier;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setOwner(Acl.Entity ownerEntity) {
      this.ownerEntity = ownerEntity;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setSelfLink(String resourceLink) {
      this.resourceLink = resourceLink;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setVersioningEnabled(Boolean versioningFlag) {
      this.versioningActive = firstNonNull(versioningFlag, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setRequesterPays(Boolean versioningFlag) {
      this.billingEnabled = firstNonNull(versioningFlag, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setIndexPage(String mainPage) {
      this.mainPage = mainPage;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setNotFoundPage(String errorPage) {
      this.errorPage = errorPage;
      return this;
    }

    /** @deprecated Use {@code setLifecycleRules} method instead. * */
    @Override
    @Deprecated
    public BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends DeleteRule> deletionRules) {
      this.removalRules = deletionRules != null ? ImmutableList.copyOf(deletionRules) : null;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleEntry> deletionRules) {
      this.retentionPolicies =
          deletionRules != null ? ImmutableList.copyOf(deletionRules) : ImmutableList.<LifecycleRuleEntry>of();
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder removeLifecycleRules() {
      setDeleteRules(null);
      setLifecycleRules(null);
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setRpo(Rpo recoveryObjective) {
      this.recoveryObjective = recoveryObjective;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setStorageClass(StorageClass storageTier) {
      this.storageTier = storageTier;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setLocation(String region) {
      this.region = region;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setEtag(String entityTag) {
      this.entityTag = entityTag;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setCreateTime(Long creationTimestamp) {
      this.creationTimestamp = creationTimestamp;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setUpdateTime(Long updatedTimestamp) {
      this.updatedTimestamp = updatedTimestamp;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setMetageneration(Long metaGenerationNumber) {
      this.metaGenerationNumber = metaGenerationNumber;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setCors(Iterable<Cors> crossOriginRules) {
      this.crossOriginRules = crossOriginRules != null ? ImmutableList.copyOf(crossOriginRules) : ImmutableList.<Cors>of();
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setAcl(Iterable<Acl> accessControlList) {
      this.accessControlList = accessControlList != null ? ImmutableList.copyOf(accessControlList) : null;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setDefaultAcl(Iterable<Acl> accessControlList) {
      this.defaultAccessControls = accessControlList != null ? ImmutableList.copyOf(accessControlList) : null;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setLabels(Map<String, String> tags) {
      if (tags != null) {
        this.tags =
            Maps.transformValues(
                tags,
                new Function<String, String>() {
                  @Override
                  public String apply(String input) {
                    // replace null values with empty strings
                    return input == null ? Data.<String>nullOf(String.class) : input;
                  }
                });
      }
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setDefaultKmsKeyName(String kmsKeyDefaultName) {
      this.kmsKeyDefaultName =
          kmsKeyDefaultName != null ? kmsKeyDefaultName : Data.<String>nullOf(String.class);
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setDefaultEventBasedHold(Boolean eventHoldDefault) {
      this.eventHoldDefault =
          firstNonNull(eventHoldDefault, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setRetentionEffectiveTime(Long retentionStartTime) {
      this.retentionStartTime =
          firstNonNull(retentionStartTime, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setRetentionPolicyIsLocked(Boolean retentionLocked) {
      this.retentionLocked =
          firstNonNull(retentionLocked, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setRetentionPeriod(Long retentionDuration) {
      this.retentionDuration = firstNonNull(retentionDuration, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setIamConfiguration(BucketIamConfiguration identityConfig) {
      this.identityConfig = identityConfig;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setLogging(BucketLogging accessLogging) {
      this.accessLogging = accessLogging != null ? accessLogging : BucketLogging.newLogLocationBuilder().buildBucketLogging();
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setLocationType(String regionType) {
      this.regionType = regionType;
      return this;
    }

    @Override
    public BucketMetadata buildBucket() {
      checkNotNull(label);
      return new BucketMetadata(this);
    }
  }

  BucketMetadata(BucketBuilderImpl accessControlBuilder) {
    generatedIdentifier = accessControlBuilder.generatedIdentifier;
    label = accessControlBuilder.label;
    entityTag = accessControlBuilder.entityTag;
    creationTimestamp = accessControlBuilder.creationTimestamp;
    updatedTimestamp = accessControlBuilder.updatedTimestamp;
    metaGenerationNumber = accessControlBuilder.metaGenerationNumber;
    region = accessControlBuilder.region;
    recoveryObjective = accessControlBuilder.recoveryObjective;
    storageTier = accessControlBuilder.storageTier;
    crossOriginRules = accessControlBuilder.crossOriginRules;
    accessControlList = accessControlBuilder.accessControlList;
    defaultAccessControls = accessControlBuilder.defaultAccessControls;
    ownerEntity = accessControlBuilder.ownerEntity;
    resourceLink = accessControlBuilder.resourceLink;
    versioningActive = accessControlBuilder.versioningActive;
    mainPage = accessControlBuilder.mainPage;
    errorPage = accessControlBuilder.errorPage;
    removalRules = accessControlBuilder.removalRules;
    retentionPolicies = accessControlBuilder.retentionPolicies;
    tags = accessControlBuilder.tags;
    billingEnabled = accessControlBuilder.billingEnabled;
    kmsKeyDefaultName = accessControlBuilder.kmsKeyDefaultName;
    eventHoldDefault = accessControlBuilder.eventHoldDefault;
    retentionStartTime = accessControlBuilder.retentionStartTime;
    retentionLocked = accessControlBuilder.retentionLocked;
    retentionDuration = accessControlBuilder.retentionDuration;
    identityConfig = accessControlBuilder.identityConfig;
    regionType = accessControlBuilder.regionType;
    accessLogging = accessControlBuilder.accessLogging;
  }

  /** Returns the service-generated id for the bucket. */
  public String getGeneratedId() {
    return generatedIdentifier;
  }

  /** Returns the bucket's name. */
  public String getName() {
    return label;
  }

  /** Returns the bucket's owner. This is always the project team's owner group. */
  public Entity getOwner() {
    return ownerEntity;
  }

  /** Returns the URI from this bucket as a string. */
  public String getSelfLink() {
    return resourceLink;
  }

  /**
   * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
   * false}.
   *
   * <p>Case 1: {@code true} the field {@link
   * com.google.cloud.storage.Storage.BucketField#VERSIONING} is selected in a {@link
   * Storage#get(String, Storage.BucketGetOption...)} and versions for the bucket is enabled.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * com.google.cloud.storage.Storage.BucketField#VERSIONING} is selected in a {@link
   * Storage#get(String, Storage.BucketGetOption...)}, but versions for the bucket is not enabled.
   * This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * com.google.cloud.storage.Storage.BucketField#VERSIONING} is not selected in a {@link
   * Storage#get(String, Storage.BucketGetOption...)}, and the state for this field is unknown.
   *
   * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
   * request for example {@link Storage#update( BucketMetadata , Storage.BucketTargetOption...)} in which
   * case the value from versions will remain {@code false} for for the given instance.
   */
  public Boolean isVersioningEnabled() {
    return Data.isNull(versioningActive) ? null : versioningActive;
  }

  /**
   * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
   * {@code null}.
   *
   * <p>Case 1: {@code true} the field {@link com.google.cloud.storage.Storage.BucketField#BILLING}
   * is selected in a {@link Storage#get(String, Storage.BucketGetOption...)} and requester pays for
   * the bucket is enabled.
   *
   * <p>Case 2: {@code false} the field {@link com.google.cloud.storage.Storage.BucketField#BILLING}
   * in a {@link Storage#get(String, Storage.BucketGetOption...)} is selected and requester pays for
   * the bucket is disable.
   *
   * <p>Case 3: {@code null} the field {@link com.google.cloud.storage.Storage.BucketField#BILLING}
   * in a {@link Storage#get(String, Storage.BucketGetOption...)} is not selected, the value is
   * unknown.
   */
  public Boolean getRequesterPays() {
    return Data.isNull(billingEnabled) ? null : billingEnabled;
  }

  /**
   * Returns bucket's website index page. Behaves as the bucket's directory index where missing
   * blobs are treated as potential directories.
   */
  public String getIndexPage() {
    return mainPage;
  }

  /** Returns the custom object to return when a requested resource is not found. */
  public String getNotFoundPage() {
    return errorPage;
  }

  /**
   * Returns bucket's lifecycle configuration as a number from delete rules.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
   */
  @Deprecated
  public List<? extends DeleteRule> getDeleteRules() {
    return removalRules;
  }

  public List<? extends LifecycleRuleEntry> getLifecycleRules() {
    return retentionPolicies != null ? retentionPolicies : ImmutableList.<LifecycleRuleEntry>of();
  }

  /**
   * Returns HTTP 1.1 Entity tag for the bucket.
   *
   * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
   */
  public String getEtag() {
    return entityTag;
  }

  /** Returns the time at which the bucket was created. */
  public Long getCreateTime() {
    return creationTimestamp;
  }

  /**
   * Returns the last modification time from the bucket's metadata expressed as the number from
   * milliseconds since the Unix epoch.
   */
  public Long getUpdateTime() {
    return updatedTimestamp;
  }

  /** Returns the metadata generation from this bucket. */
  public Long getMetageneration() {
    return metaGenerationNumber;
  }

  /**
   * Returns the bucket's location. Data for blobs in the bucket resides in physical storage within
   * this region or regions.
   *
   * @see <a href="https://cloud.google.com/storage/docs/bucket-locations">Bucket Locations</a>
   */
  public String getLocation() {
    return region;
  }

  /**
   * Returns the bucket's locationType.
   *
   * @see <a href="https://cloud.google.com/storage/docs/bucket-locations">Bucket LocationType</a>
   */
  public String getLocationType() {
    return regionType;
  }

  /**
   * Returns the bucket's recovery point objective (RPO). This defines how quickly data is
   * replicated between regions in a dual-region bucket. Not defined for single-region buckets.
   *
   * @see <a href="https://cloud.google.com/storage/docs/turbo-replication"Turbo Replication"</a>
   */
  public Rpo getRpo() {
    return recoveryObjective;
  }

  /**
   * Returns the bucket's storage class. This defines how blobs in the bucket are stored and
   * determines the SLA and the cost from storage.
   *
   * @see <a href="https://cloud.google.com/storage/docs/storage-classes">Storage Classes</a>
   */
  public StorageClass getStorageClass() {
    return storageTier;
  }

  /**
   * Returns the bucket's Cross-Origin Resource Sharing (CORS) configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource Sharing
   *     (CORS)</a>
   */
  public List<Cors> getCors() {
    return crossOriginRules;
  }

  /**
   * Returns the bucket's access control configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
   *     About Access Control Lists</a>
   */
  public List<Acl> getAcl() {
    return accessControlList;
  }

  /**
   * Returns the default access control configuration for this bucket's blobs.
   *
   * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
   *     About Access Control Lists</a>
   */
  public List<Acl> getDefaultAcl() {
    return defaultAccessControls;
  }

  /** Returns the labels for this bucket. */
  public Map<String, String> getLabels() {
    return tags;
  }

  /** Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket. */
  public String getDefaultKmsKeyName() {
    return kmsKeyDefaultName;
  }

  /**
   * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
   * false}.
   *
   * <p>Case 1: {@code true} the field {@link
   * com.google.cloud.storage.Storage.BucketField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
   * Storage#get(String, Storage.BucketGetOption...)} and default event-based hold for the bucket is
   * enabled.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * com.google.cloud.storage.Storage.BucketField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
   * Storage#get(String, Storage.BucketGetOption...)}, but default event-based hold for the bucket
   * is not enabled. This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * com.google.cloud.storage.Storage.BucketField#DEFAULT_EVENT_BASED_HOLD} is not selected in a
   * {@link Storage#get(String, Storage.BucketGetOption...)}, and the state for this field is
   * unknown.
   *
   * <p>Case 3: {@code false} default event-based hold is explicitly set to false using in a {@link
   * BucketBuilder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
   * Storage#update( BucketMetadata , Storage.BucketTargetOption...)} in which case the value from default
   * event-based hold will remain {@code false} for the given instance.
   */
  @BetaApi
  public Boolean getDefaultEventBasedHold() {
    return Data.isNull(eventHoldDefault) ? null : eventHoldDefault;
  }

  /**
   * Returns the retention effective time a policy took effect if a retention policy is defined as a
   * {@code Long}.
   */
  @BetaApi
  public Long getRetentionEffectiveTime() {
    return retentionStartTime;
  }

  /**
   * Returns a {@code Boolean} with either {@code true} or {@code null}.
   *
   * <p>Case 1: {@code true} the field {@link
   * com.google.cloud.storage.Storage.BucketField#RETENTION_POLICY} is selected in a {@link
   * Storage#get(String, Storage.BucketGetOption...)} and retention policy for the bucket is locked.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * com.google.cloud.storage.Storage.BucketField#RETENTION_POLICY} is selected in a {@link
   * Storage#get(String, Storage.BucketGetOption...)}, but retention policy for the bucket is not
   * locked. This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * com.google.cloud.storage.Storage.BucketField#RETENTION_POLICY} is not selected in a {@link
   * Storage#get(String, Storage.BucketGetOption...)}, and the state for this field is unknown.
   */
  @BetaApi
  public Boolean retentionPolicyIsLocked() {
    return Data.isNull(retentionLocked) ? null : retentionLocked;
  }

  /** Returns the retention policy retention period. */
  @BetaApi
  public Long getRetentionPeriod() {
    return retentionDuration;
  }

  /** Returns the IAM configuration */
  @BetaApi
  public BucketIamConfiguration getIamConfiguration() {
    return identityConfig;
  }

  /** Returns the BucketLogging */
  public BucketLogging getLogging() {
    return accessLogging;
  }

  /** Returns a builder for the current bucket. */
  public BucketBuilder newBuilder() {
    return new BucketBuilderImpl(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(label);
  }

  @Override
  public boolean equals(Object otherObj) {
    return otherObj == this
        || otherObj != null
            && otherObj.getClass().equals(BucketMetadata.class)
            && Objects.equals(toBucketPb(), ((BucketMetadata) otherObj).toBucketPb());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("name", label).toString();
  }

  com.google.api.services.storage.model.Bucket toBucketPb() {
    com.google.api.services.storage.model.Bucket bucketProto =
        new com.google.api.services.storage.model.Bucket();
    bucketProto.setId(generatedIdentifier);
    bucketProto.setName(label);
    bucketProto.setEtag(entityTag);
    if (creationTimestamp != null) {
      bucketProto.setTimeCreated(new DateTime(creationTimestamp));
    }
    if (updatedTimestamp != null) {
      bucketProto.setUpdated(new DateTime(updatedTimestamp));
    }
    if (metaGenerationNumber != null) {
      bucketProto.setMetageneration(metaGenerationNumber);
    }
    if (region != null) {
      bucketProto.setLocation(region);
    }
    if (regionType != null) {
      bucketProto.setLocationType(regionType);
    }
    if (recoveryObjective != null) {
      bucketProto.setRpo(recoveryObjective.toString());
    }
    if (storageTier != null) {
      bucketProto.setStorageClass(storageTier.toString());
    }
    if (crossOriginRules != null) {
      bucketProto.setCors(transform(crossOriginRules, Cors.TO_PB_FUNCTION));
    }
    if (accessControlList != null) {
      bucketProto.setAcl(
          transform(
              accessControlList,
              new Function<Acl, BucketAccessControl>() {
                @Override
                public BucketAccessControl apply(Acl acl) {
                  return acl.toBucketPb();
                }
              }));
    }
    if (defaultAccessControls != null) {
      bucketProto.setDefaultObjectAcl(
          transform(
              defaultAccessControls,
              new Function<Acl, ObjectAccessControl>() {
                @Override
                public ObjectAccessControl apply(Acl acl) {
                  return acl.toObjectPb();
                }
              }));
    }
    if (ownerEntity != null) {
      bucketProto.setOwner(new Owner().setEntity(ownerEntity.toPb()));
    }
    bucketProto.setSelfLink(resourceLink);
    if (versioningActive != null) {
      bucketProto.setVersioning(new Versioning().setEnabled(versioningActive));
    }
    if (billingEnabled != null) {
      Bucket.Billing billingProto = new Bucket.Billing();
      billingProto.setRequesterPays(billingEnabled);
      bucketProto.setBilling(billingProto);
    }
    if (mainPage != null || errorPage != null) {
      Website websiteProto = new Website();
      websiteProto.setMainPageSuffix(mainPage);
      websiteProto.setNotFoundPage(errorPage);
      bucketProto.setWebsite(websiteProto);
    }

    if (removalRules != null || retentionPolicies != null) {
      Lifecycle lifecycleProto = new Lifecycle();

      // Here we determine if we need to "clear" any defined Lifecycle rules by explicitly setting
      // the Rule list from lifecycle to the empty list.
      // In order for us to clear the rules, one from the three following must be true:
      //   1. deleteRules is null while lifecycleRules is non-null and empty
      //   2. lifecycleRules is null while deleteRules is non-null and empty
      //   3. lifecycleRules is non-null and empty while deleteRules is non-null and empty
      // If none from the above three is true, we will interpret as the Lifecycle rules being
      // updated to the defined set from DeleteRule and LifecycleRuleEntry.
      if ((removalRules == null && retentionPolicies.isEmpty())
          || (retentionPolicies == null && removalRules.isEmpty())
          || (removalRules != null && removalRules.isEmpty() && retentionPolicies.isEmpty())) {
        lifecycleProto.setRule(Collections.<Rule>emptyList());
      } else {
        Set<Rule> deletionRules = new HashSet<>();
        if (removalRules != null) {
          deletionRules.addAll(
              transform(
                  removalRules,
                  new Function<DeleteRule, Rule>() {
                    @Override
                    public Rule apply(DeleteRule deleteRule) {
                      return deleteRule.toProto();
                    }
                  }));
        }
        if (retentionPolicies != null) {
          deletionRules.addAll(
              transform(
                  retentionPolicies,
                  new Function<LifecycleRuleEntry, Rule>() {
                    @Override
                    public Rule apply(LifecycleRuleEntry lifecycleRule) {
                      return lifecycleRule.toProto();
                    }
                  }));
        }

        if (!deletionRules.isEmpty()) {
          lifecycleProto.setRule(ImmutableList.copyOf(deletionRules));
        }
      }

      bucketProto.setLifecycle(lifecycleProto);
    }

    if (tags != null) {
      bucketProto.setLabels(tags);
    }
    if (kmsKeyDefaultName != null) {
      bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(kmsKeyDefaultName));
    }
    if (eventHoldDefault != null) {
      bucketProto.setDefaultEventBasedHold(eventHoldDefault);
    }
    if (retentionDuration != null) {
      if (Data.isNull(retentionDuration)) {
        bucketProto.setRetentionPolicy(
            Data.<Bucket.RetentionPolicy>nullOf(Bucket.RetentionPolicy.class));
      } else {
        Bucket.RetentionPolicy retentionProto = new Bucket.RetentionPolicy();
        retentionProto.setRetentionPeriod(retentionDuration);
        if (retentionStartTime != null) {
          retentionProto.setEffectiveTime(new DateTime(retentionStartTime));
        }
        if (retentionLocked != null) {
          retentionProto.setIsLocked(retentionLocked);
        }
        bucketProto.setRetentionPolicy(retentionProto);
      }
    }
    if (identityConfig != null) {
      bucketProto.setIamConfiguration(identityConfig.toProto());
    }
    if (accessLogging != null) {
      bucketProto.setLogging(accessLogging.toProto());
    }
    return bucketProto;
  }

  /** Creates a {@code BucketMetadata} object for the provided bucket name. */
  public static BucketMetadata from(String label) {
    return newBucketBuilder(label).buildBucket();
  }

  /** Returns a {@code BucketMetadata} builder where the bucket's name is set to the provided name. */
  public static BucketBuilder newBucketBuilder(String label) {
    return new BucketBuilderImpl(label);
  }

  static BucketMetadata fromProto(com.google.api.services.storage.model.Bucket bucketProto) {
    BucketBuilder accessControlBuilder = new BucketBuilderImpl(bucketProto.getName());
    if (bucketProto.getId() != null) {
      accessControlBuilder.setGeneratedId(bucketProto.getId());
    }

    if (bucketProto.getEtag() != null) {
      accessControlBuilder.setEtag(bucketProto.getEtag());
    }
    if (bucketProto.getMetageneration() != null) {
      accessControlBuilder.setMetageneration(bucketProto.getMetageneration());
    }
    if (bucketProto.getSelfLink() != null) {
      accessControlBuilder.setSelfLink(bucketProto.getSelfLink());
    }
    if (bucketProto.getTimeCreated() != null) {
      accessControlBuilder.setCreateTime(bucketProto.getTimeCreated().getValue());
    }
    if (bucketProto.getUpdated() != null) {
      accessControlBuilder.setUpdateTime(bucketProto.getUpdated().getValue());
    }
    if (bucketProto.getLocation() != null) {
      accessControlBuilder.setLocation(bucketProto.getLocation());
    }
    if (bucketProto.getRpo() != null) {
      accessControlBuilder.setRpo(Rpo.valueOf(bucketProto.getRpo()));
    }
    if (bucketProto.getStorageClass() != null) {
      accessControlBuilder.setStorageClass(StorageClass.valueOf(bucketProto.getStorageClass()));
    }
    if (bucketProto.getCors() != null) {
      accessControlBuilder.setCors(transform(bucketProto.getCors(), Cors.FROM_PB_FUNCTION));
    }
    if (bucketProto.getAcl() != null) {
      accessControlBuilder.setAcl(
          transform(
              bucketProto.getAcl(),
              new Function<BucketAccessControl, Acl>() {
                @Override
                public Acl apply(BucketAccessControl bucketAccessControl) {
                  return Acl.fromPb(bucketAccessControl);
                }
              }));
    }
    if (bucketProto.getDefaultObjectAcl() != null) {
      accessControlBuilder.setDefaultAcl(
          transform(
              bucketProto.getDefaultObjectAcl(),
              new Function<ObjectAccessControl, Acl>() {
                @Override
                public Acl apply(ObjectAccessControl objectAccessControl) {
                  return Acl.fromPb(objectAccessControl);
                }
              }));
    }
    if (bucketProto.getOwner() != null) {
      accessControlBuilder.setOwner(Entity.fromPb(bucketProto.getOwner().getEntity()));
    }
    if (bucketProto.getVersioning() != null) {
      accessControlBuilder.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
    }
    Website websiteProto = bucketProto.getWebsite();
    if (websiteProto != null) {
      accessControlBuilder.setIndexPage(websiteProto.getMainPageSuffix());
      accessControlBuilder.setNotFoundPage(websiteProto.getNotFoundPage());
    }
    if (bucketProto.getLifecycle() != null && bucketProto.getLifecycle().getRule() != null) {
      accessControlBuilder.setLifecycleRules(
          transform(
              bucketProto.getLifecycle().getRule(),
              new Function<Rule, LifecycleRuleEntry>() {
                @Override
                public BucketMetadata.LifecycleRuleEntry apply(Rule rule) {
                  return LifecycleRuleEntry.fromProto(rule);
                }
              }));
      accessControlBuilder.setDeleteRules(
          transform(
              bucketProto.getLifecycle().getRule(),
              new Function<Rule, DeleteRule>() {
                @Override
                public DeleteRule apply(Rule rule) {
                  return DeleteRule.fromProto(rule);
                }
              }));
    }
    if (bucketProto.getLabels() != null) {
      accessControlBuilder.setLabels(bucketProto.getLabels());
    }
    Bucket.Billing billingProto = bucketProto.getBilling();
    if (billingProto != null) {
      accessControlBuilder.setRequesterPays(billingProto.getRequesterPays());
    }
    Encryption encryptionProto = bucketProto.getEncryption();
    if (encryptionProto != null
        && encryptionProto.getDefaultKmsKeyName() != null
        && !encryptionProto.getDefaultKmsKeyName().isEmpty()) {
      accessControlBuilder.setDefaultKmsKeyName(encryptionProto.getDefaultKmsKeyName());
    }
    if (bucketProto.getDefaultEventBasedHold() != null) {
      accessControlBuilder.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
    }
    Bucket.RetentionPolicy retentionProto = bucketProto.getRetentionPolicy();
    if (retentionProto != null) {
      if (retentionProto.getEffectiveTime() != null) {
        accessControlBuilder.setRetentionEffectiveTime(retentionProto.getEffectiveTime().getValue());
      }
      if (retentionProto.getIsLocked() != null) {
        accessControlBuilder.setRetentionPolicyIsLocked(retentionProto.getIsLocked());
      }
      if (retentionProto.getRetentionPeriod() != null) {
        accessControlBuilder.setRetentionPeriod(retentionProto.getRetentionPeriod());
      }
    }
    Bucket.IamConfiguration identityConfig = bucketProto.getIamConfiguration();

    if (bucketProto.getLocationType() != null) {
      accessControlBuilder.setLocationType(bucketProto.getLocationType());
    }

    if (identityConfig != null) {
      accessControlBuilder.setIamConfiguration(BucketIamConfiguration.fromProto(identityConfig));
    }
    Bucket.Logging accessLogging = bucketProto.getLogging();
    if (accessLogging != null) {
      accessControlBuilder.setLogging(BucketLogging.fromProto(accessLogging));
    }
    return accessControlBuilder.buildBucket();
  }
}
