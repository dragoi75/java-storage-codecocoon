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
import com.google.cloud.storage.AccessControlEntry.ProtoEntity;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Google Storage bucket metadata;
 *
 * @see <a href="https://cloud.google.com/storage/docs/concepts-techniques#concepts">Concepts and
 *     Terminology</a>
 */
public class BucketInfo implements Serializable {

  static final Function<com.google.api.services.storage.model.Bucket, BucketInfo> FROM_PB_FUNCTION =
      new Function<com.google.api.services.storage.model.Bucket, BucketInfo>() {
        @Override
        public BucketInfo apply(com.google.api.services.storage.model.Bucket pb) {
          return BucketInfo.fromProto(pb);
        }
      };
  static final Function<BucketInfo, com.google.api.services.storage.model.Bucket> INFO_TO_BUCKET_FUNCTION =
      new Function<BucketInfo, com.google.api.services.storage.model.Bucket>() {
        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketInfo bucketInfo) {
          return bucketInfo.toProto();
        }
      };
  private static final long serialVersionUID = -4712013629621638459L;
  private final String generatedIdentifier;
  private final String identifier;
  private final AccessControlEntry.ProtoEntity principal;
  private final String resourceLink;
  private final Boolean requesterBilling;
  private final Boolean isVersioningEnabled;
  private final String indexDocument;
  private final String errorDocument;
  private final List<DeletionRule> deletionRules;
  private final List<LifecycleRuleDefinition> lifecycleDefinitions;
  private final String entityTag;
  private final Long creationTime;
  private final Long modificationTime;
  private final Long metadataGeneration;
  private final List<Cors> corsRules;
  private final List<AccessControlEntry> accessControlList;
  private final List<AccessControlEntry> defaultAccessControlList;
  private final String region;
  private final StorageClassType storageTier;
  private final Map<String, String> metadataLabels;
  private final String defaultKmsKey;
  private final Boolean eventBasedHoldByDefault;
  private final Long retentionStartTime;
  private final Boolean isRetentionPolicyLocked;
  private final Long retentionDuration;
  private final BucketIamConfiguration iamConfig;
  private final String regionType;
  private final LoggingConfig loggingConfig;

  /**
   * The Bucket's IAM Configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/uniform-bucket-level-access">uniform
   *     bucket-level access</a>
   */
  public static class BucketIamConfiguration implements Serializable {
    private static final long serialVersionUID = -8671736104909424616L;

    private Boolean uniformBucketLevelAccessEnabled;
    private Long uniformAccessLockedTime;

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      BucketIamConfiguration thatConfig = (BucketIamConfiguration) obj;
      return Objects.equals(toProto(), thatConfig.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(uniformBucketLevelAccessEnabled, uniformAccessLockedTime);
    }

    private BucketIamConfiguration(UniformBucketLevelAccessBuilder accessBuilder) {
      this.uniformBucketLevelAccessEnabled = accessBuilder.uniformBucketLevelAccessEnabled;
      this.uniformAccessLockedTime = accessBuilder.uniformAccessLockedTime;
    }

    public static UniformBucketLevelAccessBuilder newUniformBucketLevelAccessBuilder() {
      return new UniformBucketLevelAccessBuilder();
    }

    public UniformBucketLevelAccessBuilder toUniformBucketLevelAccessBuilder() {
      UniformBucketLevelAccessBuilder accessBuilder = new UniformBucketLevelAccessBuilder();
      accessBuilder.uniformBucketLevelAccessEnabled = uniformBucketLevelAccessEnabled;
      accessBuilder.uniformAccessLockedTime = uniformAccessLockedTime;
      return accessBuilder;
    }

    /** Deprecated in favor of isUniformBucketLevelAccessEnabled(). */
    @Deprecated
    public Boolean isBucketPolicyOnlyEnabled() {
      return uniformBucketLevelAccessEnabled;
    }

    /** Deprecated in favor of uniformBucketLevelAccessLockedTime(). */
    @Deprecated
    public Long getBucketPolicyOnlyLockedTime() {
      return uniformAccessLockedTime;
    }

    public Boolean isUniformBucketLevelAccessEnabled() {
      return uniformBucketLevelAccessEnabled;
    }

    public Long getUniformBucketLevelAccessLockedTime() {
      return uniformAccessLockedTime;
    }

    Bucket.IamConfiguration toProto() {
      Bucket.IamConfiguration iamConfig = new Bucket.IamConfiguration();

      Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccess =
          new Bucket.IamConfiguration.UniformBucketLevelAccess();
      uniformAccess.setEnabled(uniformBucketLevelAccessEnabled);
      uniformAccess.setLockedTime(
          uniformAccessLockedTime == null
              ? null
              : new DateTime(uniformAccessLockedTime));

      iamConfig.setUniformBucketLevelAccess(uniformAccess);

      return iamConfig;
    }

    static BucketIamConfiguration fromProto(Bucket.IamConfiguration iamConfig) {
      Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccess =
          iamConfig.getUniformBucketLevelAccess();
      DateTime lockTimestamp = uniformAccess.getLockedTime();

      return newUniformBucketLevelAccessBuilder()
          .setIsUniformBucketLevelAccessEnabled(uniformAccess.getEnabled())
          .setUniformBucketLevelAccessLockedTime(lockTimestamp == null ? null : lockTimestamp.getValue())
          .buildBucketIamConfiguration();
    }

    /** Builder for {@code IamConfiguration} */
    public static class UniformBucketLevelAccessBuilder {
      private Boolean uniformBucketLevelAccessEnabled;
      private Long uniformAccessLockedTime;

      /** Deprecated in favor of setIsUniformBucketLevelAccessEnabled(). */
      @Deprecated
      public BucketInfo.BucketIamConfiguration.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean bucketPolicyOnlyEnabled) {
        this.uniformBucketLevelAccessEnabled = bucketPolicyOnlyEnabled;
        return this;
      }

      /** Deprecated in favor of setUniformBucketLevelAccessLockedTime(). */
      @Deprecated
      BucketInfo.BucketIamConfiguration.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long policyOnlyLockedTime) {
        this.uniformAccessLockedTime = policyOnlyLockedTime;
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
      public UniformBucketLevelAccessBuilder setIsUniformBucketLevelAccessEnabled(
          Boolean uniformBucketLevelAccessEnabled) {
        this.uniformBucketLevelAccessEnabled = uniformBucketLevelAccessEnabled;
        return this;
      }

      /**
       * Sets the deadline for switching {@code uniformBucketLevelAccess.enabled} back to false.
       * After this time passes, calls to do so will fail. This is package-private, since in general
       * this field should never be set by a user--it's automatically set by the backend when {@code
       * enabled} is set to true.
       */
      UniformBucketLevelAccessBuilder setUniformBucketLevelAccessLockedTime(Long uniformAccessLockedTime) {
        this.uniformAccessLockedTime = uniformAccessLockedTime;
        return this;
      }

      /** Builds an {@code IamConfiguration} object */
      public BucketIamConfiguration buildBucketIamConfiguration() {
        return new BucketIamConfiguration(this);
      }
    }
  }

  /**
   * The bucket's logging configuration, which defines the destination bucket and optional name
   * prefix for the current bucket's logs.
   */
  public static class LoggingConfig implements Serializable {

    private static final long serialVersionUID = -708892101216778492L;
    private String loggingBucket;
    private String loggingObjectPrefix;

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      LoggingConfig thatConfig = (LoggingConfig) obj;
      return Objects.equals(toProto(), thatConfig.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(loggingBucket, loggingObjectPrefix);
    }

    public static LogConfigBuilder newLogConfigBuilder() {
      return new LogConfigBuilder();
    }

    public LogConfigBuilder toBuilder() {
      LogConfigBuilder accessBuilder = new LogConfigBuilder();
      accessBuilder.loggingBucket = loggingBucket;
      accessBuilder.loggingObjectPrefix = loggingObjectPrefix;
      return accessBuilder;
    }

    public String getLogBucket() {
      return loggingBucket;
    }

    public String getLogObjectPrefix() {
      return loggingObjectPrefix;
    }

    Bucket.Logging toProto() {
      Bucket.Logging loggingConfig;
      if (loggingBucket != null || loggingObjectPrefix != null) {
        loggingConfig = new Bucket.Logging();
        loggingConfig.setLogBucket(loggingBucket);
        loggingConfig.setLogObjectPrefix(loggingObjectPrefix);
      } else {
        loggingConfig = Data.nullOf(Bucket.Logging.class);
      }
      return loggingConfig;
    }

    static LoggingConfig fromProto(Bucket.Logging loggingConfig) {
      return newLogConfigBuilder()
          .setLogBucket(loggingConfig.getLogBucket())
          .setLogObjectPrefix(loggingConfig.getLogObjectPrefix())
          .buildLoggingConfig();
    }

    private LoggingConfig(LogConfigBuilder accessBuilder) {
      this.loggingBucket = accessBuilder.loggingBucket;
      this.loggingObjectPrefix = accessBuilder.loggingObjectPrefix;
    }

    public static class LogConfigBuilder {
      private String loggingBucket;
      private String loggingObjectPrefix;

      /** The destination bucket where the current bucket's logs should be placed. */
      public LogConfigBuilder setLogBucket(String loggingBucket) {
        this.loggingBucket = loggingBucket;
        return this;
      }

      /** A prefix for log object names. */
      public LogConfigBuilder setLogObjectPrefix(String loggingObjectPrefix) {
        this.loggingObjectPrefix = loggingObjectPrefix;
        return this;
      }

      /** Builds an {@code Logging} object */
      public LoggingConfig buildLoggingConfig() {
        return new LoggingConfig(this);
      }
    }
  }

  /**
   * Lifecycle rule for a bucket. Allows supported Actions, such as deleting and changing storage
   * class, to be executed when certain Conditions are met.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle#actions">Object Lifecycle
   *     Management</a>
   */
  public static class LifecycleRuleDefinition implements Serializable {

    private static final long serialVersionUID = -5739807320148748613L;
    private final AbstractLifecycleAction actionDefinition;
    private final LifecycleRuleCondition conditionDefinition;

    public LifecycleRuleDefinition(AbstractLifecycleAction act, LifecycleRuleCondition cond) {
      if (cond.getIsLive() == null
          && cond.getAge() == null
          && cond.getCreatedBefore() == null
          && cond.getMatchesStorageClass() == null
          && cond.getNumberOfNewerVersions() == null
          && cond.getDaysSinceNoncurrentTime() == null
          && cond.getNoncurrentTimeBefore() == null
          && cond.getCustomTimeBefore() == null
          && cond.getDaysSinceCustomTime() == null) {
        throw new IllegalArgumentException(
            "You must specify at least one condition to use object lifecycle "
                + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
      }

      this.actionDefinition = act;
      this.conditionDefinition = cond;
    }

    public AbstractLifecycleAction getAction() {
      return actionDefinition;
    }

    public LifecycleRuleCondition getCondition() {
      return conditionDefinition;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("lifecycleAction", actionDefinition)
          .add("lifecycleCondition", conditionDefinition)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(actionDefinition, conditionDefinition);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      final LifecycleRuleDefinition thatConfig = (LifecycleRuleDefinition) other;
      return Objects.equals(toProto(), thatConfig.toProto());
    }

    Rule toProto() {
      Rule protoEntry = new Rule();

      Rule.Action act = new Rule.Action().setType(actionDefinition.getActionType());
      if (actionDefinition.getActionType().equals(UpdateStorageClassLifecycleAction.TYPE)) {
        act.setStorageClass(
            ((UpdateStorageClassLifecycleAction) actionDefinition).getStorageClass().toString());
      }

      protoEntry.setAction(act);

      Rule.Condition cond =
          new Rule.Condition()
              .setAge(conditionDefinition.getAge())
              .setCreatedBefore(
                  conditionDefinition.getCreatedBefore() == null
                      ? null
                      : new DateTime(true, conditionDefinition.getCreatedBefore().getValue(), 0))
              .setIsLive(conditionDefinition.getIsLive())
              .setNumNewerVersions(conditionDefinition.getNumberOfNewerVersions())
              .setMatchesStorageClass(
                  conditionDefinition.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          conditionDefinition.getMatchesStorageClass(),
                          Functions.toStringFunction()))
              .setDaysSinceNoncurrentTime(conditionDefinition.getDaysSinceNoncurrentTime())
              .setNoncurrentTimeBefore(
                  conditionDefinition.getNoncurrentTimeBefore() == null
                      ? null
                      : new DateTime(
                          true, conditionDefinition.getNoncurrentTimeBefore().getValue(), 0))
              .setCustomTimeBefore(
                  conditionDefinition.getCustomTimeBefore() == null
                      ? null
                      : new DateTime(true, conditionDefinition.getCustomTimeBefore().getValue(), 0))
              .setDaysSinceCustomTime(conditionDefinition.getDaysSinceCustomTime());

      protoEntry.setCondition(cond);

      return protoEntry;
    }

    static LifecycleRuleDefinition fromProto(Rule protoEntry) {
      AbstractLifecycleAction actionDefinition;

      Rule.Action act = protoEntry.getAction();

      switch (act.getType()) {
        case RemoveLifecycleAction.TYPE:
          actionDefinition = AbstractLifecycleAction.newRemoveAction();
          break;
        case UpdateStorageClassLifecycleAction.TYPE:
          actionDefinition =
              AbstractLifecycleAction.newUpdateStorageClassAction(
                  StorageClassType.fromValue(act.getStorageClass()));
          break;
        default:
          throw new UnsupportedOperationException(
              "The specified lifecycle action " + act.getType() + " is not currently supported");
      }

      Rule.Condition cond = protoEntry.getCondition();

      LifecycleRuleCondition.LifecycleConditionBuilder condBuilder =
          LifecycleRuleCondition.newLifecycleConditionBuilder()
              .setAge(cond.getAge())
              .setCreatedBefore(cond.getCreatedBefore())
              .setIsLive(cond.getIsLive())
              .setNumberOfNewerVersions(cond.getNumNewerVersions())
              .setMatchesStorageClass(
                  cond.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          cond.getMatchesStorageClass(),
                          new Function<String, StorageClassType>() {
                            public StorageClassType apply(String storageClass) {
                              return StorageClassType.fromValue(storageClass);
                            }
                          }))
              .setDaysSinceNoncurrentTime(cond.getDaysSinceNoncurrentTime())
              .setNoncurrentTimeBefore(cond.getNoncurrentTimeBefore())
              .setCustomTimeBefore(cond.getCustomTimeBefore())
              .setDaysSinceCustomTime(cond.getDaysSinceCustomTime());

      return new LifecycleRuleDefinition(actionDefinition, condBuilder.buildLifecycleCondition());
    }

    /**
     * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
     *     Management</a>
     */
    public static class LifecycleRuleCondition implements Serializable {
      private static final long serialVersionUID = -6482314338394768785L;
      private final Integer lifecycleDays;
      private final DateTime creationCutoff;
      private final Integer newerVersionsCount;
      private final Boolean liveStatus;
      private final List<StorageClassType> storageClasses;
      private final Integer noncurrentDays;
      private final DateTime noncurrentCutoff;
      private final DateTime customCutoff;
      private final Integer customElapsedDays;

      private LifecycleRuleCondition(LifecycleConditionBuilder accessBuilder) {
        this.lifecycleDays = accessBuilder.lifecycleDays;
        this.creationCutoff = accessBuilder.creationCutoff;
        this.newerVersionsCount = accessBuilder.newerVersionsCount;
        this.liveStatus = accessBuilder.liveStatus;
        this.storageClasses = accessBuilder.storageClasses;
        this.noncurrentDays = accessBuilder.noncurrentDays;
        this.noncurrentCutoff = accessBuilder.noncurrentCutoff;
        this.customCutoff = accessBuilder.customCutoff;
        this.customElapsedDays = accessBuilder.customElapsedDays;
      }

      public LifecycleConditionBuilder toBuilder() {
        return newLifecycleConditionBuilder()
            .setAge(this.lifecycleDays)
            .setCreatedBefore(this.creationCutoff)
            .setNumberOfNewerVersions(this.newerVersionsCount)
            .setIsLive(this.liveStatus)
            .setMatchesStorageClass(this.storageClasses)
            .setDaysSinceNoncurrentTime(this.noncurrentDays)
            .setNoncurrentTimeBefore(this.noncurrentCutoff)
            .setCustomTimeBefore(this.customCutoff)
            .setDaysSinceCustomTime(this.customElapsedDays);
      }

      public static LifecycleConditionBuilder newLifecycleConditionBuilder() {
        return new LifecycleConditionBuilder();
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("age", lifecycleDays)
            .add("createBefore", creationCutoff)
            .add("numberofNewerVersions", newerVersionsCount)
            .add("isLive", liveStatus)
            .add("matchesStorageClass", storageClasses)
            .add("daysSinceNoncurrentTime", noncurrentDays)
            .add("noncurrentTimeBefore", noncurrentCutoff)
            .add("customTimeBefore", customCutoff)
            .add("daysSinceCustomTime", customElapsedDays)
            .toString();
      }

      public Integer getAge() {
        return lifecycleDays;
      }

      public DateTime getCreatedBefore() {
        return creationCutoff;
      }

      public Integer getNumberOfNewerVersions() {
        return newerVersionsCount;
      }

      public Boolean getIsLive() {
        return liveStatus;
      }

      public List<StorageClassType> getMatchesStorageClass() {
        return storageClasses;
      }

      /** Returns the number of days elapsed since the noncurrent timestamp of an object. */
      public Integer getDaysSinceNoncurrentTime() {
        return noncurrentDays;
      }

      /**
       * Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
       */
      public DateTime getNoncurrentTimeBefore() {
        return noncurrentCutoff;
      }

      /* Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").*/
      public DateTime getCustomTimeBefore() {
        return customCutoff;
      }

      /** Returns the number of days elapsed since the user-specified timestamp set on an object. */
      public Integer getDaysSinceCustomTime() {
        return customElapsedDays;
      }

      /** Builder for {@code LifecycleCondition}. */
      public static class LifecycleConditionBuilder {
        private Integer lifecycleDays;
        private DateTime creationCutoff;
        private Integer newerVersionsCount;
        private Boolean liveStatus;
        private List<StorageClassType> storageClasses;
        private Integer noncurrentDays;
        private DateTime noncurrentCutoff;
        private DateTime customCutoff;
        private Integer customElapsedDays;

        private LifecycleConditionBuilder() {}

        /**
         * Sets the age in days. This condition is satisfied when a Blob reaches the specified age
         * (in days). When you specify the Age condition, you are specifying a Time to Live (TTL)
         * for objects in a bucket with lifecycle management configured. The time when the Age
         * condition is considered to be satisfied is calculated by adding the specified value to
         * the object creation time.
         */
        public LifecycleConditionBuilder setAge(Integer lifecycleDays) {
          this.lifecycleDays = lifecycleDays;
          return this;
        }

        /**
         * Sets the date a Blob should be created before for an Action to be executed. Note that
         * only the date will be considered, if the time is specified it will be truncated. This
         * condition is satisfied when an object is created before midnight of the specified date in
         * UTC. *
         */
        public LifecycleConditionBuilder setCreatedBefore(DateTime creationCutoff) {
          this.creationCutoff = creationCutoff;
          return this;
        }

        /**
         * Sets the number of newer versions a Blob should have for an Action to be executed.
         * Relevant only when versioning is enabled on a bucket. *
         */
        public LifecycleConditionBuilder setNumberOfNewerVersions(Integer newerVersionsCount) {
          this.newerVersionsCount = newerVersionsCount;
          return this;
        }

        /**
         * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
         * only live Blobs; if the value is false, it matches only archived objects. For the
         * purposes of this condition, Blobs in non-versioned buckets are considered live.
         */
        public LifecycleConditionBuilder setIsLive(Boolean active) {
          this.liveStatus = active;
          return this;
        }

        /**
         * Sets a list of Storage Classes for a objects that satisfy the condition to execute the
         * Action. *
         */
        public LifecycleConditionBuilder setMatchesStorageClass(List<StorageClassType> storageClasses) {
          this.storageClasses = storageClasses;
          return this;
        }

        /**
         * Sets the number of days elapsed since the noncurrent timestamp of an object. The
         * condition is satisfied if the days elapsed is at least this number. This condition is
         * relevant only for versioned objects. The value of the field must be a nonnegative
         * integer. If it's zero, the object version will become eligible for Lifecycle action as
         * soon as it becomes noncurrent.
         */
        public LifecycleConditionBuilder setDaysSinceNoncurrentTime(Integer noncurrentDays) {
          this.noncurrentDays = noncurrentDays;
          return this;
        }

        /**
         * Sets the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
         * Note that only date part will be considered, if the time is specified it will be
         * truncated. This condition is satisfied when the noncurrent time on an object is before
         * this date. This condition is relevant only for versioned objects.
         */
        public LifecycleConditionBuilder setNoncurrentTimeBefore(DateTime noncurrentCutoff) {
          this.noncurrentCutoff = noncurrentCutoff;
          return this;
        }

        /**
         * Sets the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
         * Note that only date part will be considered, if the time is specified it will be
         * truncated. This condition is satisfied when the custom time on an object is before this
         * date in UTC.
         */
        public LifecycleConditionBuilder setCustomTimeBefore(DateTime customCutoff) {
          this.customCutoff = customCutoff;
          return this;
        }

        /**
         * Sets the number of days elapsed since the user-specified timestamp set on an object. The
         * condition is satisfied if the days elapsed is at least this number. If no custom
         * timestamp is specified on an object, the condition does not apply.
         */
        public LifecycleConditionBuilder setDaysSinceCustomTime(Integer customElapsedDays) {
          this.customElapsedDays = customElapsedDays;
          return this;
        }

        /** Builds a {@code LifecycleCondition} object. * */
        public LifecycleRuleCondition buildLifecycleCondition() {
          return new LifecycleRuleCondition(this);
        }
      }
    }

    /**
     * Base class for the Action to take when a Lifecycle Condition is met. Specific Actions are
     * expressed as subclasses of this class, accessed by static factory methods.
     */
    public abstract static class AbstractLifecycleAction implements Serializable {
      private static final long serialVersionUID = 5801228724709173284L;

      public abstract String getActionType();

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this).add("actionType", getActionType()).toString();
      }

      /**
       * Creates a new {@code DeleteLifecycleAction}. Blobs that meet the Condition associated with
       * this action will be deleted.
       */
      public static RemoveLifecycleAction newRemoveAction() {
        return new RemoveLifecycleAction();
      }

      /**
       * Creates a new {@code SetStorageClassLifecycleAction}. A Blob's storage class that meets the
       * action's conditions will be changed to the specified storage class.
       *
       * @param storageTier The new storage class to use when conditions are met for this action.
       */
      public static UpdateStorageClassLifecycleAction newUpdateStorageClassAction(
          StorageClassType storageTier) {
        return new UpdateStorageClassLifecycleAction(storageTier);
      }
    }

    public static class RemoveLifecycleAction extends AbstractLifecycleAction {
      public static final String TYPE = "Delete";
      private static final long serialVersionUID = -2050986302222644873L;

      private RemoveLifecycleAction() {}

      @Override
      public String getActionType() {
        return TYPE;
      }
    }

    public static class UpdateStorageClassLifecycleAction extends AbstractLifecycleAction {
      public static final String TYPE = "SetStorageClass";
      private static final long serialVersionUID = -62615467186000899L;

      private final StorageClassType storageTier;

      private UpdateStorageClassLifecycleAction(StorageClassType storageTier) {
        this.storageTier = storageTier;
      }

      @Override
      public String getActionType() {
        return TYPE;
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("actionType", getActionType())
            .add("storageClass", storageTier.name())
            .toString();
      }

      public StorageClassType getStorageClass() {
        return storageTier;
      }
    }
  }

  /**
   * Base class for bucket's delete rules. Allows to configure automatic deletion of blobs and blobs
   * versions.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRule} with a {@code DeleteLifecycleAction} and a {@code
   *     LifecycleCondition} which is equivalent to a subclass of DeleteRule instead.
   */
  @Deprecated
  public abstract static class DeletionRule implements Serializable {

    private static final long serialVersionUID = 3137971668395933033L;
    private static final String DELETION_ACTION_NAME = "Delete";
    private final ConditionType conditionKind;

    public enum ConditionType {
      AGE,
      CREATE_BEFORE,
      NUM_NEWER_VERSIONS,
      IS_LIVE,
      UNKNOWN
    }

    DeletionRule(ConditionType conditionKind) {
      this.conditionKind = conditionKind;
    }

    public ConditionType getType() {
      return conditionKind;
    }

    @Override
    public int hashCode() {
      return Objects.hash(conditionKind);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      final DeletionRule thatConfig = (DeletionRule) other;
      return Objects.equals(toProto(), thatConfig.toProto());
    }

    Rule toProto() {
      Rule protoEntry = new Rule();
      protoEntry.setAction(new Rule.Action().setType(DELETION_ACTION_NAME));
      Rule.Condition cond = new Rule.Condition();
      fillCondition(cond);
      protoEntry.setCondition(cond);
      return protoEntry;
    }

    abstract void fillCondition(Rule.Condition condition);

    static DeletionRule fromProto(Rule protoEntry) {
      if (protoEntry.getAction() != null && DELETION_ACTION_NAME.endsWith(protoEntry.getAction().getType())) {
        Rule.Condition cond = protoEntry.getCondition();
        Integer lifecycleDays = cond.getAge();
        if (lifecycleDays != null) {
          return new AgeBasedDeletionRule(lifecycleDays);
        }
        DateTime timestamp = cond.getCreatedBefore();
        if (timestamp != null) {
          return new CreationBeforeDeletionRule(timestamp.getValue());
        }
        Integer newerCount = cond.getNumNewerVersions();
        if (newerCount != null) {
          return new NewerVersionsThresholdDeleteRule(newerCount);
        }
        Boolean liveStatus = cond.getIsLive();
        if (liveStatus != null) {
          return new LiveDeleteRule(liveStatus);
        }
      }
      return new RawDeletionRule(protoEntry);
    }
  }

  /**
   * Delete rule class that sets a Time To Live for blobs in the bucket.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRule} with a {@code DeleteLifecycleAction} and use {@code
   *     LifecycleCondition.Builder.setAge} instead.
   *     <p>For example, {@code new DeleteLifecycleAction(1)} is equivalent to {@code new
   *     LifecycleRule( LifecycleAction.newDeleteAction(),
   *     LifecycleCondition.newBuilder().setAge(1).build()))}
   */
  @Deprecated
  public static class AgeBasedDeletionRule extends DeletionRule {

    private static final long serialVersionUID = 5697166940712116380L;
    private final int ttlDays;

    /**
     * Creates an {@code AgeDeleteRule} object.
     *
     * @param ttlDays blobs' Time To Live expressed in days. The time when the age condition is
     *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
     *     following blob's creation time in UTC.
     */
    public AgeBasedDeletionRule(int ttlDays) {
      super(ConditionType.AGE);
      this.ttlDays = ttlDays;
    }

    public int getDaysToLive() {
      return ttlDays;
    }

    @Override
    void fillCondition(Rule.Condition cond) {
      cond.setAge(ttlDays);
    }
  }

  static class RawDeletionRule extends DeletionRule {

    private static final long serialVersionUID = -7166938278642301933L;

    private transient Rule protoEntry;

    RawDeletionRule(Rule protoEntry) {
      super(ConditionType.UNKNOWN);
      this.protoEntry = protoEntry;
    }

    @Override
    void fillCondition(Rule.Condition condition) {
      throw new UnsupportedOperationException();
    }

    private void writeObject(ObjectOutputStream objectWriter) throws IOException {
      objectWriter.defaultWriteObject();
      objectWriter.writeUTF(protoEntry.toString());
    }

    private void readObject(ObjectInputStream objectReader) throws IOException, ClassNotFoundException {
      objectReader.defaultReadObject();
      protoEntry = new JacksonFactory().fromString(objectReader.readUTF(), Rule.class);
    }

    @Override
    Rule toProto() {
      return protoEntry;
    }
  }

  /**
   * Delete rule class for blobs in the bucket that have been created before a certain date.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRule} with an action {@code DeleteLifecycleAction} and a
   *     condition {@code LifecycleCondition.Builder.setCreatedBefore} instead.
   */
  @Deprecated
  public static class CreationBeforeDeletionRule extends DeletionRule {

    private static final long serialVersionUID = 881692650279195867L;
    private final long timestampMillis;

    /**
     * Creates an {@code CreatedBeforeDeleteRule} object.
     *
     * @param timestampMillis a date in UTC. Blobs that have been created before midnight of the provided
     *     date meet the delete condition
     */
    public CreationBeforeDeletionRule(long timestampMillis) {
      super(ConditionType.CREATE_BEFORE);
      this.timestampMillis = timestampMillis;
    }

    public long getTimeMillis() {
      return timestampMillis;
    }

    @Override
    void fillCondition(Rule.Condition cond) {
      cond.setCreatedBefore(new DateTime(true, timestampMillis, 0));
    }
  }

  /**
   * Delete rule class for versioned blobs. Specifies when to delete a blob's version according to
   * the number of available newer versions for that blob.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRule} with a {@code DeleteLifecycleAction} and a condition
   *     {@code LifecycleCondition.Builder.setNumberOfNewerVersions} instead.
   */
  @Deprecated
  public static class NewerVersionsThresholdDeleteRule extends DeletionRule {

    private static final long serialVersionUID = -1955554976528303894L;
    private final int newerCount;

    /**
     * Creates an {@code NumNewerVersionsDeleteRule} object.
     *
     * @param newerCount the number of newer versions. A blob's version meets the delete
     *     condition when {@code numNewerVersions} newer versions are available.
     */
    public NewerVersionsThresholdDeleteRule(int newerCount) {
      super(ConditionType.NUM_NEWER_VERSIONS);
      this.newerCount = newerCount;
    }

    public int getNumNewerVersions() {
      return newerCount;
    }

    @Override
    void fillCondition(Rule.Condition cond) {
      cond.setNumNewerVersions(newerCount);
    }
  }

  /**
   * Delete rule class to distinguish between live and archived blobs.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRule} with a {@code DeleteLifecycleAction} and a condition
   *     {@code LifecycleCondition.Builder.setIsLive} instead.
   */
  @Deprecated
  public static class LiveDeleteRule extends DeletionRule {

    private static final long serialVersionUID = -3502994563121313364L;
    private final boolean liveStatus;

    /**
     * Creates an {@code IsLiveDeleteRule} object.
     *
     * @param liveStatus if set to {@code true} live blobs meet the delete condition. If set to {@code
     *     false} delete condition is met by archived blobs.
     */
    public LiveDeleteRule(boolean liveStatus) {
      super(ConditionType.IS_LIVE);
      this.liveStatus = liveStatus;
    }

    public boolean isLive() {
      return liveStatus;
    }

    @Override
    void fillCondition(Rule.Condition cond) {
      cond.setIsLive(liveStatus);
    }
  }

  /** Builder for {@code BucketInfo}. */
  public abstract static class Builder {
    Builder() {}

    /** Sets the bucket's name. */
    public abstract Builder setName(String name);

    abstract Builder setGeneratedId(String generatedId);

    abstract Builder setOwner(ProtoEntity owner);

    abstract Builder setSelfLink(String selfLink);

    /**
     * Sets whether a user accessing the bucket or an object it contains should assume the transit
     * costs related to the access.
     */
    public abstract Builder setRequesterPays(Boolean requesterPays);

    /**
     * Sets whether versioning should be enabled for this bucket. When set to true, versioning is
     * fully enabled.
     */
    public abstract Builder setVersioningEnabled(Boolean enable);

    /**
     * Sets the bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public abstract Builder setIndexPage(String indexPage);

    /** Sets the custom object to return when a requested resource is not found. */
    public abstract Builder setNotFoundPage(String notFoundPage);

    /**
     * Sets the bucket's lifecycle configuration as a number of delete rules.
     *
     * @deprecated Use {@code setLifecycleRules} instead, as in {@code
     *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRule(
     *     LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(5).build())));}
     */
    @Deprecated
    public abstract Builder setDeleteRules(Iterable<? extends DeletionRule> rules);

    /**
     * Sets the bucket's lifecycle configuration as a number of lifecycle rules, consisting of an
     * action and a condition.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
     *     Management</a>
     */
    public abstract Builder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> rules);

    /** Deletes the lifecycle rules of this bucket. */
    public abstract Builder deleteLifecycleRules();

    /**
     * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
     * determines the SLA and the cost of storage. A list of supported values is available <a
     * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
     */
    public abstract Builder setStorageClass(StorageClassType storageClass);

    /**
     * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
     * this region. A list of supported values is available <a
     * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
     */
    public abstract Builder setLocation(String location);

    abstract Builder setEtag(String etag);

    abstract Builder setCreateTime(Long createTime);

    abstract Builder setUpdateTime(Long updateTime);

    abstract Builder setMetageneration(Long metageneration);

    abstract Builder setLocationType(String locationType);

    /**
     * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
     *     Sharing (CORS)</a>
     */
    public abstract Builder setCors(Iterable<Cors> cors);

    /**
     * Sets the bucket's access control configuration.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract Builder setAcl(Iterable<AccessControlEntry> acl);

    /**
     * Sets the default access control configuration to apply to bucket's blobs when no other
     * configuration is specified.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract Builder setDefaultAcl(Iterable<AccessControlEntry> acl);

    /** Sets the label of this bucket. */
    public abstract Builder setLabels(Map<String, String> labels);

    /** Sets the default Cloud KMS key name for this bucket. */
    public abstract Builder setDefaultKmsKeyName(String defaultKmsKeyName);

    /** Sets the default event-based hold for this bucket. */
    @BetaApi
    public abstract Builder setDefaultEventBasedHold(Boolean defaultEventBasedHold);

    @BetaApi
    abstract Builder setRetentionEffectiveTime(Long retentionEffectiveTime);

    @BetaApi
    abstract Builder setRetentionPolicyIsLocked(Boolean retentionPolicyIsLocked);

    /**
     * If policy is not locked this value can be cleared, increased, and decreased. If policy is
     * locked the retention period can only be increased.
     */
    @BetaApi
    public abstract Builder setRetentionPeriod(Long retentionPeriod);

    /**
     * Sets the IamConfiguration to specify whether IAM access should be enabled.
     *
     * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
     *     Only</a>
     */
    @BetaApi
    public abstract Builder setIamConfiguration(BucketIamConfiguration iamConfiguration);

    public abstract Builder setLogging(LoggingConfig logging);

    /** Creates a {@code BucketInfo} object. */
    public abstract BucketInfo buildBucketInfo();
  }

  static final class BucketBuilderImpl extends Builder {

    private String generatedIdentifier;
    private String identifier;
    private ProtoEntity principal;
    private String resourceLink;
    private Boolean requesterBilling;
    private Boolean isVersioningEnabled;
    private String indexDocument;
    private String errorDocument;
    private List<DeletionRule> deletionRules;
    private List<LifecycleRuleDefinition> lifecycleDefinitions;
    private StorageClassType storageTier;
    private String region;
    private String entityTag;
    private Long creationTime;
    private Long modificationTime;
    private Long metadataGeneration;
    private List<Cors> corsRules;
    private List<AccessControlEntry> accessControlList;
    private List<AccessControlEntry> defaultAccessControlList;
    private Map<String, String> metadataLabels;
    private String defaultKmsKey;
    private Boolean eventBasedHoldByDefault;
    private Long retentionStartTime;
    private Boolean isRetentionPolicyLocked;
    private Long retentionDuration;
    private BucketIamConfiguration iamConfig;
    private String regionType;
    private LoggingConfig loggingConfig;

    BucketBuilderImpl(String identifier) {
      this.identifier = identifier;
    }

    BucketBuilderImpl(BucketInfo bucketDetails) {
      generatedIdentifier = bucketDetails.generatedIdentifier;
      identifier = bucketDetails.identifier;
      entityTag = bucketDetails.entityTag;
      creationTime = bucketDetails.creationTime;
      modificationTime = bucketDetails.modificationTime;
      metadataGeneration = bucketDetails.metadataGeneration;
      region = bucketDetails.region;
      storageTier = bucketDetails.storageTier;
      corsRules = bucketDetails.corsRules;
      accessControlList = bucketDetails.accessControlList;
      defaultAccessControlList = bucketDetails.defaultAccessControlList;
      principal = bucketDetails.principal;
      resourceLink = bucketDetails.resourceLink;
      isVersioningEnabled = bucketDetails.isVersioningEnabled;
      indexDocument = bucketDetails.indexDocument;
      errorDocument = bucketDetails.errorDocument;
      deletionRules = bucketDetails.deletionRules;
      lifecycleDefinitions = bucketDetails.lifecycleDefinitions;
      metadataLabels = bucketDetails.metadataLabels;
      requesterBilling = bucketDetails.requesterBilling;
      defaultKmsKey = bucketDetails.defaultKmsKey;
      eventBasedHoldByDefault = bucketDetails.eventBasedHoldByDefault;
      retentionStartTime = bucketDetails.retentionStartTime;
      isRetentionPolicyLocked = bucketDetails.isRetentionPolicyLocked;
      retentionDuration = bucketDetails.retentionDuration;
      iamConfig = bucketDetails.iamConfig;
      regionType = bucketDetails.regionType;
      loggingConfig = bucketDetails.loggingConfig;
    }

    @Override
    public Builder setName(String identifier) {
      this.identifier = checkNotNull(identifier);
      return this;
    }

    @Override
    Builder setGeneratedId(String generatedIdentifier) {
      this.generatedIdentifier = generatedIdentifier;
      return this;
    }

    @Override
    Builder setOwner(AccessControlEntry.ProtoEntity principal) {
      this.principal = principal;
      return this;
    }

    @Override
    Builder setSelfLink(String resourceLink) {
      this.resourceLink = resourceLink;
      return this;
    }

    @Override
    public Builder setVersioningEnabled(Boolean enabled) {
      this.isVersioningEnabled = firstNonNull(enabled, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public Builder setRequesterPays(Boolean enabled) {
      this.requesterBilling = firstNonNull(enabled, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public Builder setIndexPage(String indexDocument) {
      this.indexDocument = indexDocument;
      return this;
    }

    @Override
    public Builder setNotFoundPage(String errorDocument) {
      this.errorDocument = errorDocument;
      return this;
    }

    /** @deprecated Use {@code setLifecycleRules} method instead. * */
    @Override
    @Deprecated
    public Builder setDeleteRules(Iterable<? extends DeletionRule> deletionCriteria) {
      this.deletionRules = deletionCriteria != null ? ImmutableList.copyOf(deletionCriteria) : null;
      return this;
    }

    @Override
    public Builder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deletionCriteria) {
      this.lifecycleDefinitions =
          deletionCriteria != null ? ImmutableList.copyOf(deletionCriteria) : ImmutableList.<LifecycleRuleDefinition>of();
      return this;
    }

    @Override
    public Builder deleteLifecycleRules() {
      setDeleteRules(null);
      setLifecycleRules(null);
      return this;
    }

    @Override
    public Builder setStorageClass(StorageClassType storageTier) {
      this.storageTier = storageTier;
      return this;
    }

    @Override
    public Builder setLocation(String region) {
      this.region = region;
      return this;
    }

    @Override
    Builder setEtag(String entityTag) {
      this.entityTag = entityTag;
      return this;
    }

    @Override
    Builder setCreateTime(Long creationTime) {
      this.creationTime = creationTime;
      return this;
    }

    @Override
    Builder setUpdateTime(Long modificationTime) {
      this.modificationTime = modificationTime;
      return this;
    }

    @Override
    Builder setMetageneration(Long metadataGeneration) {
      this.metadataGeneration = metadataGeneration;
      return this;
    }

    @Override
    public Builder setCors(Iterable<Cors> corsRules) {
      this.corsRules = corsRules != null ? ImmutableList.copyOf(corsRules) : ImmutableList.<Cors>of();
      return this;
    }

    @Override
    public Builder setAcl(Iterable<AccessControlEntry> accessControlList) {
      this.accessControlList = accessControlList != null ? ImmutableList.copyOf(accessControlList) : null;
      return this;
    }

    @Override
    public Builder setDefaultAcl(Iterable<AccessControlEntry> accessControlList) {
      this.defaultAccessControlList = accessControlList != null ? ImmutableList.copyOf(accessControlList) : null;
      return this;
    }

    @Override
    public Builder setLabels(Map<String, String> metadataLabels) {
      if (metadataLabels != null) {
        this.metadataLabels =
            Maps.transformValues(
                    metadataLabels,
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
    public Builder setDefaultKmsKeyName(String defaultKmsKey) {
      this.defaultKmsKey =
          defaultKmsKey != null ? defaultKmsKey : Data.<String>nullOf(String.class);
      return this;
    }

    @Override
    public Builder setDefaultEventBasedHold(Boolean eventBasedHoldByDefault) {
      this.eventBasedHoldByDefault =
          firstNonNull(eventBasedHoldByDefault, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    Builder setRetentionEffectiveTime(Long retentionStartTime) {
      this.retentionStartTime =
          firstNonNull(retentionStartTime, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    Builder setRetentionPolicyIsLocked(Boolean isRetentionPolicyLocked) {
      this.isRetentionPolicyLocked =
          firstNonNull(isRetentionPolicyLocked, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public Builder setRetentionPeriod(Long retentionDuration) {
      this.retentionDuration = firstNonNull(retentionDuration, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    public Builder setIamConfiguration(BucketIamConfiguration iamConfig) {
      this.iamConfig = iamConfig;
      return this;
    }

    @Override
    public Builder setLogging(LoggingConfig loggingConfig) {
      this.loggingConfig = loggingConfig != null ? loggingConfig : LoggingConfig.newLogConfigBuilder().buildLoggingConfig();
      return this;
    }

    @Override
    Builder setLocationType(String regionType) {
      this.regionType = regionType;
      return this;
    }

    @Override
    public BucketInfo buildBucketInfo() {
      checkNotNull(identifier);
      return new BucketInfo(this);
    }
  }

  BucketInfo(BucketBuilderImpl accessBuilder) {
    generatedIdentifier = accessBuilder.generatedIdentifier;
    identifier = accessBuilder.identifier;
    entityTag = accessBuilder.entityTag;
    creationTime = accessBuilder.creationTime;
    modificationTime = accessBuilder.modificationTime;
    metadataGeneration = accessBuilder.metadataGeneration;
    region = accessBuilder.region;
    storageTier = accessBuilder.storageTier;
    corsRules = accessBuilder.corsRules;
    accessControlList = accessBuilder.accessControlList;
    defaultAccessControlList = accessBuilder.defaultAccessControlList;
    principal = accessBuilder.principal;
    resourceLink = accessBuilder.resourceLink;
    isVersioningEnabled = accessBuilder.isVersioningEnabled;
    indexDocument = accessBuilder.indexDocument;
    errorDocument = accessBuilder.errorDocument;
    deletionRules = accessBuilder.deletionRules;
    lifecycleDefinitions = accessBuilder.lifecycleDefinitions;
    metadataLabels = accessBuilder.metadataLabels;
    requesterBilling = accessBuilder.requesterBilling;
    defaultKmsKey = accessBuilder.defaultKmsKey;
    eventBasedHoldByDefault = accessBuilder.eventBasedHoldByDefault;
    retentionStartTime = accessBuilder.retentionStartTime;
    isRetentionPolicyLocked = accessBuilder.isRetentionPolicyLocked;
    retentionDuration = accessBuilder.retentionDuration;
    iamConfig = accessBuilder.iamConfig;
    regionType = accessBuilder.regionType;
    loggingConfig = accessBuilder.loggingConfig;
  }

  /** Returns the service-generated id for the bucket. */
  public String getGeneratedId() {
    return generatedIdentifier;
  }

  /** Returns the bucket's name. */
  public String getName() {
    return identifier;
  }

  /** Returns the bucket's owner. This is always the project team's owner group. */
  public ProtoEntity getOwner() {
    return principal;
  }

  /** Returns the URI of this bucket as a string. */
  public String getSelfLink() {
    return resourceLink;
  }

  /**
   * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
   * false}.
   *
   * <p>Case 1: {@code true} the field {@link
   * CloudStorageClient.BucketMetadataField#VERSIONING} is selected in a {@link
   * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} and versions for the bucket is enabled.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * CloudStorageClient.BucketMetadataField#VERSIONING} is selected in a {@link
   * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, but versions for the bucket is not enabled.
   * This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * CloudStorageClient.BucketMetadataField#VERSIONING} is not selected in a {@link
   * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, and the state for this field is unknown.
   *
   * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
   * request for example {@link CloudStorageClient#update(BucketInfo, CloudStorageClient.BucketTargetOptions...)} in which
   * case the value of versions will remain {@code false} for for the given instance.
   */
  public Boolean isVersioningEnabled() {
    return Data.isNull(isVersioningEnabled) ? null : isVersioningEnabled;
  }

  /**
   * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
   * {@code null}.
   *
   * <p>Case 1: {@code true} the field {@link CloudStorageClient.BucketMetadataField#BILLING}
   * is selected in a {@link CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} and requester pays for
   * the bucket is enabled.
   *
   * <p>Case 2: {@code false} the field {@link CloudStorageClient.BucketMetadataField#BILLING}
   * in a {@link CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} is selected and requester pays for
   * the bucket is disable.
   *
   * <p>Case 3: {@code null} the field {@link CloudStorageClient.BucketMetadataField#BILLING}
   * in a {@link CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} is not selected, the value is
   * unknown.
   */
  public Boolean isRequesterPays() {
    return Data.isNull(requesterBilling) ? null : requesterBilling;
  }

  /**
   * Returns bucket's website index page. Behaves as the bucket's directory index where missing
   * blobs are treated as potential directories.
   */
  public String getIndexPage() {
    return indexDocument;
  }

  /** Returns the custom object to return when a requested resource is not found. */
  public String getNotFoundPage() {
    return errorDocument;
  }

  /**
   * Returns bucket's lifecycle configuration as a number of delete rules.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
   */
  @Deprecated
  public List<? extends DeletionRule> getDeleteRules() {
    return deletionRules;
  }

  public List<? extends LifecycleRuleDefinition> getLifecycleRules() {
    return lifecycleDefinitions != null ? lifecycleDefinitions : ImmutableList.<LifecycleRuleDefinition>of();
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
    return creationTime;
  }

  /**
   * Returns the last modification time of the bucket's metadata expressed as the number of
   * milliseconds since the Unix epoch.
   */
  public Long getUpdateTime() {
    return modificationTime;
  }

  /** Returns the metadata generation of this bucket. */
  public Long getMetageneration() {
    return metadataGeneration;
  }

  /**
   * Returns the bucket's location. Data for blobs in the bucket resides in physical storage within
   * this region.
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
   * Returns the bucket's storage class. This defines how blobs in the bucket are stored and
   * determines the SLA and the cost of storage.
   *
   * @see <a href="https://cloud.google.com/storage/docs/storage-classes">Storage Classes</a>
   */
  public StorageClassType getStorageClass() {
    return storageTier;
  }

  /**
   * Returns the bucket's Cross-Origin Resource Sharing (CORS) configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource Sharing
   *     (CORS)</a>
   */
  public List<Cors> getCors() {
    return corsRules;
  }

  /**
   * Returns the bucket's access control configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
   *     About Access Control Lists</a>
   */
  public List<AccessControlEntry> getAcl() {
    return accessControlList;
  }

  /**
   * Returns the default access control configuration for this bucket's blobs.
   *
   * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
   *     About Access Control Lists</a>
   */
  public List<AccessControlEntry> getDefaultAcl() {
    return defaultAccessControlList;
  }

  /** Returns the labels for this bucket. */
  public Map<String, String> getLabels() {
    return metadataLabels;
  }

  /** Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket. */
  public String getDefaultKmsKeyName() {
    return defaultKmsKey;
  }

  /**
   * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
   * false}.
   *
   * <p>Case 1: {@code true} the field {@link
   * CloudStorageClient.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
   * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} and default event-based hold for the bucket is
   * enabled.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * CloudStorageClient.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
   * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, but default event-based hold for the bucket
   * is not enabled. This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * CloudStorageClient.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is not selected in a
   * {@link CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, and the state for this field is
   * unknown.
   *
   * <p>Case 3: {@code false} default event-based hold is explicitly set to false using in a {@link
   * Builder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
   * CloudStorageClient#update(BucketInfo, CloudStorageClient.BucketTargetOptions...)} in which case the value of default
   * event-based hold will remain {@code false} for the given instance.
   */
  @BetaApi
  public Boolean getDefaultEventBasedHold() {
    return Data.isNull(eventBasedHoldByDefault) ? null : eventBasedHoldByDefault;
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
   * CloudStorageClient.BucketMetadataField#RETENTION_POLICY} is selected in a {@link
   * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} and retention policy for the bucket is locked.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * CloudStorageClient.BucketMetadataField#RETENTION_POLICY} is selected in a {@link
   * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, but retention policy for the bucket is not
   * locked. This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * CloudStorageClient.BucketMetadataField#RETENTION_POLICY} is not selected in a {@link
   * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, and the state for this field is unknown.
   */
  @BetaApi
  public Boolean isRetentionPolicyLocked() {
    return Data.isNull(isRetentionPolicyLocked) ? null : isRetentionPolicyLocked;
  }

  /** Returns the retention policy retention period. */
  @BetaApi
  public Long getRetentionPeriod() {
    return retentionDuration;
  }

  /** Returns the IAM configuration */
  @BetaApi
  public BucketIamConfiguration getIamConfiguration() {
    return iamConfig;
  }

  /** Returns the Logging */
  public LoggingConfig getLogging() {
    return loggingConfig;
  }

  /** Returns a builder for the current bucket. */
  public Builder toBucketBuilder() {
    return new BucketBuilderImpl(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(identifier);
  }

  @Override
  public boolean equals(Object other) {
    return other == this
        || other != null
            && other.getClass().equals(BucketInfo.class)
            && Objects.equals(toProto(), ((BucketInfo) other).toProto());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("name", identifier).toString();
  }

  com.google.api.services.storage.model.Bucket toProto() {
    com.google.api.services.storage.model.Bucket bucketProto =
        new com.google.api.services.storage.model.Bucket();
    bucketProto.setId(generatedIdentifier);
    bucketProto.setName(identifier);
    bucketProto.setEtag(entityTag);
    if (creationTime != null) {
      bucketProto.setTimeCreated(new DateTime(creationTime));
    }
    if (modificationTime != null) {
      bucketProto.setUpdated(new DateTime(modificationTime));
    }
    if (metadataGeneration != null) {
      bucketProto.setMetageneration(metadataGeneration);
    }
    if (region != null) {
      bucketProto.setLocation(region);
    }
    if (regionType != null) {
      bucketProto.setLocationType(regionType);
    }
    if (storageTier != null) {
      bucketProto.setStorageClass(storageTier.toString());
    }
    if (corsRules != null) {
      bucketProto.setCors(transform(corsRules, Cors.TO_PB_FUNCTION));
    }
    if (accessControlList != null) {
      bucketProto.setAcl(
          transform(
                  accessControlList,
              new Function<AccessControlEntry, BucketAccessControl>() {
                @Override
                public BucketAccessControl apply(AccessControlEntry acl) {
                  return acl.toBucketProto();
                }
              }));
    }
    if (defaultAccessControlList != null) {
      bucketProto.setDefaultObjectAcl(
          transform(
                  defaultAccessControlList,
              new Function<AccessControlEntry, ObjectAccessControl>() {
                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                  return acl.toObjectProto();
                }
              }));
    }
    if (principal != null) {
      bucketProto.setOwner(new Owner().setEntity(principal.toProto()));
    }
    bucketProto.setSelfLink(resourceLink);
    if (isVersioningEnabled != null) {
      bucketProto.setVersioning(new Versioning().setEnabled(isVersioningEnabled));
    }
    if (requesterBilling != null) {
      Bucket.Billing paymentConfig = new Bucket.Billing();
      paymentConfig.setRequesterPays(requesterBilling);
      bucketProto.setBilling(paymentConfig);
    }
    if (indexDocument != null || errorDocument != null) {
      Website siteConfig = new Website();
      siteConfig.setMainPageSuffix(indexDocument);
      siteConfig.setNotFoundPage(errorDocument);
      bucketProto.setWebsite(siteConfig);
    }
    Set<Rule> deletionCriteria = new HashSet<>();
    if (deletionRules != null) {
      deletionCriteria.addAll(
          transform(
                  deletionRules,
              new Function<DeletionRule, Rule>() {
                @Override
                public Rule apply(DeletionRule deleteRule) {
                  return deleteRule.toProto();
                }
              }));
    }
    if (lifecycleDefinitions != null) {
      deletionCriteria.addAll(
          transform(
                  lifecycleDefinitions,
              new Function<LifecycleRuleDefinition, Rule>() {
                @Override
                public Rule apply(LifecycleRuleDefinition lifecycleRule) {
                  return lifecycleRule.toProto();
                }
              }));
    }

    if (deletionCriteria != null) {
      Lifecycle policySet = new Lifecycle();
      policySet.setRule(ImmutableList.copyOf(deletionCriteria));
      bucketProto.setLifecycle(policySet);
    }

    if (metadataLabels != null) {
      bucketProto.setLabels(metadataLabels);
    }
    if (defaultKmsKey != null) {
      bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(defaultKmsKey));
    }
    if (eventBasedHoldByDefault != null) {
      bucketProto.setDefaultEventBasedHold(eventBasedHoldByDefault);
    }
    if (retentionDuration != null) {
      if (Data.isNull(retentionDuration)) {
        bucketProto.setRetentionPolicy(
            Data.<Bucket.RetentionPolicy>nullOf(Bucket.RetentionPolicy.class));
      } else {
        Bucket.RetentionPolicy retentionConfig = new Bucket.RetentionPolicy();
        retentionConfig.setRetentionPeriod(retentionDuration);
        if (retentionStartTime != null) {
          retentionConfig.setEffectiveTime(new DateTime(retentionStartTime));
        }
        if (isRetentionPolicyLocked != null) {
          retentionConfig.setIsLocked(isRetentionPolicyLocked);
        }
        bucketProto.setRetentionPolicy(retentionConfig);
      }
    }
    if (iamConfig != null) {
      bucketProto.setIamConfiguration(iamConfig.toProto());
    }
    if (loggingConfig != null) {
      bucketProto.setLogging(loggingConfig.toProto());
    }
    return bucketProto;
  }

  /** Creates a {@code BucketInfo} object for the provided bucket name. */
  public static BucketInfo ofName(String identifier) {
    return newBucketBuilder(identifier).buildBucketInfo();
  }

  /** Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name. */
  public static Builder newBucketBuilder(String identifier) {
    return new BucketBuilderImpl(identifier);
  }

  static BucketInfo fromProto(com.google.api.services.storage.model.Bucket bucketProto) {
    Builder accessBuilder = new BucketBuilderImpl(bucketProto.getName());
    if (bucketProto.getId() != null) {
      accessBuilder.setGeneratedId(bucketProto.getId());
    }

    if (bucketProto.getEtag() != null) {
      accessBuilder.setEtag(bucketProto.getEtag());
    }
    if (bucketProto.getMetageneration() != null) {
      accessBuilder.setMetageneration(bucketProto.getMetageneration());
    }
    if (bucketProto.getSelfLink() != null) {
      accessBuilder.setSelfLink(bucketProto.getSelfLink());
    }
    if (bucketProto.getTimeCreated() != null) {
      accessBuilder.setCreateTime(bucketProto.getTimeCreated().getValue());
    }
    if (bucketProto.getUpdated() != null) {
      accessBuilder.setUpdateTime(bucketProto.getUpdated().getValue());
    }
    if (bucketProto.getLocation() != null) {
      accessBuilder.setLocation(bucketProto.getLocation());
    }
    if (bucketProto.getStorageClass() != null) {
      accessBuilder.setStorageClass(StorageClassType.fromValue(bucketProto.getStorageClass()));
    }
    if (bucketProto.getCors() != null) {
      accessBuilder.setCors(transform(bucketProto.getCors(), Cors.FROM_PB_FUNCTION));
    }
    if (bucketProto.getAcl() != null) {
      accessBuilder.setAcl(
          transform(
              bucketProto.getAcl(),
              new Function<BucketAccessControl, AccessControlEntry>() {
                @Override
                public AccessControlEntry apply(BucketAccessControl bucketAccessControl) {
                  return AccessControlEntry.fromProto(bucketAccessControl);
                }
              }));
    }
    if (bucketProto.getDefaultObjectAcl() != null) {
      accessBuilder.setDefaultAcl(
          transform(
              bucketProto.getDefaultObjectAcl(),
              new Function<ObjectAccessControl, AccessControlEntry>() {
                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                  return AccessControlEntry.fromProto(objectAccessControl);
                }
              }));
    }
    if (bucketProto.getOwner() != null) {
      accessBuilder.setOwner(ProtoEntity.fromProto(bucketProto.getOwner().getEntity()));
    }
    if (bucketProto.getVersioning() != null) {
      accessBuilder.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
    }
    Website siteConfig = bucketProto.getWebsite();
    if (siteConfig != null) {
      accessBuilder.setIndexPage(siteConfig.getMainPageSuffix());
      accessBuilder.setNotFoundPage(siteConfig.getNotFoundPage());
    }
    if (bucketProto.getLifecycle() != null && bucketProto.getLifecycle().getRule() != null) {
      accessBuilder.setLifecycleRules(
          transform(
              bucketProto.getLifecycle().getRule(),
              new Function<Rule, LifecycleRuleDefinition>() {
                @Override
                public BucketInfo.LifecycleRuleDefinition apply(Rule rule) {
                  return LifecycleRuleDefinition.fromProto(rule);
                }
              }));
      accessBuilder.setDeleteRules(
          transform(
              bucketProto.getLifecycle().getRule(),
              new Function<Rule, DeletionRule>() {
                @Override
                public BucketInfo.DeletionRule apply(Rule rule) {
                  return DeletionRule.fromProto(rule);
                }
              }));
    }
    if (bucketProto.getLabels() != null) {
      accessBuilder.setLabels(bucketProto.getLabels());
    }
    Bucket.Billing paymentConfig = bucketProto.getBilling();
    if (paymentConfig != null) {
      accessBuilder.setRequesterPays(paymentConfig.getRequesterPays());
    }
    Encryption cryptoSettings = bucketProto.getEncryption();
    if (cryptoSettings != null
        && cryptoSettings.getDefaultKmsKeyName() != null
        && !cryptoSettings.getDefaultKmsKeyName().isEmpty()) {
      accessBuilder.setDefaultKmsKeyName(cryptoSettings.getDefaultKmsKeyName());
    }
    if (bucketProto.getDefaultEventBasedHold() != null) {
      accessBuilder.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
    }
    Bucket.RetentionPolicy retentionConfig = bucketProto.getRetentionPolicy();
    if (retentionConfig != null) {
      if (retentionConfig.getEffectiveTime() != null) {
        accessBuilder.setRetentionEffectiveTime(retentionConfig.getEffectiveTime().getValue());
      }
      if (retentionConfig.getIsLocked() != null) {
        accessBuilder.setRetentionPolicyIsLocked(retentionConfig.getIsLocked());
      }
      if (retentionConfig.getRetentionPeriod() != null) {
        accessBuilder.setRetentionPeriod(retentionConfig.getRetentionPeriod());
      }
    }
    Bucket.IamConfiguration iamConfig = bucketProto.getIamConfiguration();

    if (bucketProto.getLocationType() != null) {
      accessBuilder.setLocationType(bucketProto.getLocationType());
    }

    if (iamConfig != null) {
      accessBuilder.setIamConfiguration(BucketIamConfiguration.fromProto(iamConfig));
    }
    Bucket.Logging loggingConfig = bucketProto.getLogging();
    if (loggingConfig != null) {
      accessBuilder.setLogging(LoggingConfig.fromProto(loggingConfig));
    }
    return accessBuilder.buildBucketInfo();
  }
}
