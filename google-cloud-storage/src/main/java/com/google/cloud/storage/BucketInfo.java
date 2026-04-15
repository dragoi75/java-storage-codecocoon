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
  static final Function<BucketInfo, com.google.api.services.storage.model.Bucket> TO_PB_FUNCTION =
      new Function<BucketInfo, com.google.api.services.storage.model.Bucket>() {
        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketInfo bucketInfo) {
          return bucketInfo.toBucketPb();
        }
      };
  private static final long serialVersionUID = -4712013629621638459L;
  private final String generatedId;
  private final String name;
  private final Acl.Entity owner;
  private final String selfLink;
  private final Boolean requesterPays;
  private final Boolean versioningEnabled;
  private final String indexPage;
  private final String notFoundPage;
  private final List<DeleteRule> deleteRules;
  private final List<LifecycleRuleDefinition> lifecycleRules;
  private final String etag;
  private final Long createTime;
  private final Long updateTime;
  private final Long metageneration;
  private final List<Cors> cors;
  private final List<Acl> acl;
  private final List<Acl> defaultAcl;
  private final String location;
  private final StorageClass storageClass;
  private final Map<String, String> labels;
  private final String defaultKmsKeyName;
  private final Boolean defaultEventBasedHold;
  private final Long retentionEffectiveTime;
  private final Boolean retentionPolicyIsLocked;
  private final Long retentionPeriod;
  private final BucketIamConfiguration iamConfiguration;
  private final String locationType;
  private final LoggingConfig logging;

  /**
   * The Bucket's IAM Configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/uniform-bucket-level-access">uniform
   *     bucket-level access</a>
   */
  public static class BucketIamConfiguration implements Serializable {
    private static final long serialVersionUID = -8671736104909424616L;

    private Boolean isUniformBucketLevelAccessEnabled;
    private Long uniformBucketLevelAccessLockedTime;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      BucketIamConfiguration other = (BucketIamConfiguration) o;
      return Objects.equals(toProto(), other.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(isUniformBucketLevelAccessEnabled, uniformBucketLevelAccessLockedTime);
    }

    private BucketIamConfiguration(UniformBucketLevelAccessBuilder builder) {
      this.isUniformBucketLevelAccessEnabled = builder.isUniformBucketLevelAccessEnabled;
      this.uniformBucketLevelAccessLockedTime = builder.uniformBucketLevelAccessLockedTime;
    }

    public static UniformBucketLevelAccessBuilder createBuilder() {
      return new UniformBucketLevelAccessBuilder();
    }

    public UniformBucketLevelAccessBuilder toUniformBucketLevelAccessBuilder() {
      UniformBucketLevelAccessBuilder builder = new UniformBucketLevelAccessBuilder();
      builder.isUniformBucketLevelAccessEnabled = isUniformBucketLevelAccessEnabled;
      builder.uniformBucketLevelAccessLockedTime = uniformBucketLevelAccessLockedTime;
      return builder;
    }

    /** Deprecated in favor from isUniformBucketLevelAccessEnabled(). */
    @Deprecated
    public Boolean isBucketPolicyOnlyEnabled() {
      return isUniformBucketLevelAccessEnabled;
    }

    /** Deprecated in favor from uniformBucketLevelAccessLockedTime(). */
    @Deprecated
    public Long getBucketPolicyOnlyLockedTime() {
      return uniformBucketLevelAccessLockedTime;
    }

    public Boolean isUniformBucketLevelAccessEnabled() {
      return isUniformBucketLevelAccessEnabled;
    }

    public Long getUniformBucketLevelAccessLockedTime() {
      return uniformBucketLevelAccessLockedTime;
    }

    Bucket.IamConfiguration toProto() {
      Bucket.IamConfiguration iamConfiguration = new Bucket.IamConfiguration();

      Bucket.IamConfiguration.UniformBucketLevelAccess uniformBucketLevelAccess =
          new Bucket.IamConfiguration.UniformBucketLevelAccess();
      uniformBucketLevelAccess.setEnabled(isUniformBucketLevelAccessEnabled);
      uniformBucketLevelAccess.setLockedTime(
          uniformBucketLevelAccessLockedTime == null
              ? null
              : new DateTime(uniformBucketLevelAccessLockedTime));

      iamConfiguration.setUniformBucketLevelAccess(uniformBucketLevelAccess);

      return iamConfiguration;
    }

    static BucketIamConfiguration fromProto(Bucket.IamConfiguration iamConfiguration) {
      Bucket.IamConfiguration.UniformBucketLevelAccess uniformBucketLevelAccess =
          iamConfiguration.getUniformBucketLevelAccess();
      DateTime lockedTime = uniformBucketLevelAccess.getLockedTime();

      return createBuilder()
          .setIsUniformBucketLevelAccessEnabled(uniformBucketLevelAccess.getEnabled())
          .setUniformBucketLevelAccessLockedTime(lockedTime == null ? null : lockedTime.getValue())
          .buildBucketIamConfiguration();
    }

    /** UniformBucketLevelAccessBuilder for {@code BucketIamConfiguration} */
    public static class UniformBucketLevelAccessBuilder {
      private Boolean isUniformBucketLevelAccessEnabled;
      private Long uniformBucketLevelAccessLockedTime;

      /** Deprecated in favor from setIsUniformBucketLevelAccessEnabled(). */
      @Deprecated
      public BucketInfo.BucketIamConfiguration.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean isBucketPolicyOnlyEnabled) {
        this.isUniformBucketLevelAccessEnabled = isBucketPolicyOnlyEnabled;
        return this;
      }

      /** Deprecated in favor from setUniformBucketLevelAccessLockedTime(). */
      @Deprecated
      BucketInfo.BucketIamConfiguration.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long bucketPolicyOnlyLockedTime) {
        this.uniformBucketLevelAccessLockedTime = bucketPolicyOnlyLockedTime;
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
          Boolean isUniformBucketLevelAccessEnabled) {
        this.isUniformBucketLevelAccessEnabled = isUniformBucketLevelAccessEnabled;
        return this;
      }

      /**
       * Sets the deadline for switching {@code uniformBucketLevelAccess.enabled} back to false.
       * After this time passes, calls to do so will fail. This is package-private, since in general
       * this field should never be set by a user--it's automatically set by the backend when {@code
       * enabled} is set to true.
       */
      UniformBucketLevelAccessBuilder setUniformBucketLevelAccessLockedTime(Long uniformBucketLevelAccessLockedTime) {
        this.uniformBucketLevelAccessLockedTime = uniformBucketLevelAccessLockedTime;
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
  public static class LoggingConfig implements Serializable {

    private static final long serialVersionUID = -708892101216778492L;
    private String logBucket;
    private String logObjectPrefix;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LoggingConfig other = (LoggingConfig) o;
      return Objects.equals(toProto(), other.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(logBucket, logObjectPrefix);
    }

    public static LogLocationBuilder newLogLocationBuilder() {
      return new LogLocationBuilder();
    }

    public LogLocationBuilder toBuilder() {
      LogLocationBuilder builder = new LogLocationBuilder();
      builder.logBucket = logBucket;
      builder.logObjectPrefix = logObjectPrefix;
      return builder;
    }

    public String getLogBucket() {
      return logBucket;
    }

    public String getLogObjectPrefix() {
      return logObjectPrefix;
    }

    Bucket.Logging toProto() {
      Bucket.Logging logging;
      if (logBucket != null || logObjectPrefix != null) {
        logging = new Bucket.Logging();
        logging.setLogBucket(logBucket);
        logging.setLogObjectPrefix(logObjectPrefix);
      } else {
        logging = Data.nullOf(Bucket.Logging.class);
      }
      return logging;
    }

    static LoggingConfig fromProto(Bucket.Logging logging) {
      return newLogLocationBuilder()
          .setLogBucket(logging.getLogBucket())
          .setLogObjectPrefix(logging.getLogObjectPrefix())
          .buildLoggingConfig();
    }

    private LoggingConfig(LogLocationBuilder builder) {
      this.logBucket = builder.logBucket;
      this.logObjectPrefix = builder.logObjectPrefix;
    }

    public static class LogLocationBuilder {
      private String logBucket;
      private String logObjectPrefix;

      /** The destination bucket where the current bucket's logs should be placed. */
      public LogLocationBuilder setLogBucket(String logBucket) {
        this.logBucket = logBucket;
        return this;
      }

      /** A prefix for log object names. */
      public LogLocationBuilder setLogObjectPrefix(String logObjectPrefix) {
        this.logObjectPrefix = logObjectPrefix;
        return this;
      }

      /** Builds an {@code LoggingConfig} object */
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
    private final LifecycleRuleAction lifecycleAction;
    private final LifecycleRuleCondition lifecycleCondition;

    public LifecycleRuleDefinition(LifecycleRuleAction action, LifecycleRuleCondition condition) {
      if (condition.getIsLive() == null
          && condition.getAge() == null
          && condition.getCreatedBefore() == null
          && condition.getMatchesStorageClass() == null
          && condition.getNumberOfNewerVersions() == null
          && condition.getDaysSinceNoncurrentTime() == null
          && condition.getNoncurrentTimeBefore() == null) {
        throw new IllegalArgumentException(
            "You must specify at least one condition to use object lifecycle "
                + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
      }

      this.lifecycleAction = action;
      this.lifecycleCondition = condition;
    }

    public LifecycleRuleAction getAction() {
      return lifecycleAction;
    }

    public LifecycleRuleCondition getCondition() {
      return lifecycleCondition;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("lifecycleAction", lifecycleAction)
          .add("lifecycleCondition", lifecycleCondition)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(lifecycleAction, lifecycleCondition);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      final LifecycleRuleDefinition other = (LifecycleRuleDefinition) obj;
      return Objects.equals(toProto(), other.toProto());
    }

    Rule toProto() {
      Rule rule = new Rule();

      Rule.Action action = new Rule.Action().setType(lifecycleAction.getActionType());
      if (lifecycleAction.getActionType().equals(StorageClassLifecycleSetAction.TYPE)) {
        action.setStorageClass(
            ((StorageClassLifecycleSetAction) lifecycleAction).getStorageClass().toString());
      }

      rule.setAction(action);

      Rule.Condition condition =
          new Rule.Condition()
              .setAge(lifecycleCondition.getAge())
              .setCreatedBefore(
                  lifecycleCondition.getCreatedBefore() == null
                      ? null
                      : new DateTime(true, lifecycleCondition.getCreatedBefore().getValue(), 0))
              .setIsLive(lifecycleCondition.getIsLive())
              .setNumNewerVersions(lifecycleCondition.getNumberOfNewerVersions())
              .setMatchesStorageClass(
                  lifecycleCondition.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          lifecycleCondition.getMatchesStorageClass(),
                          Functions.toStringFunction()))
              .setDaysSinceNoncurrentTime(lifecycleCondition.getDaysSinceNoncurrentTime())
              .setNoncurrentTimeBefore(
                  lifecycleCondition.getNoncurrentTimeBefore() == null
                      ? null
                      : new DateTime(
                          true, lifecycleCondition.getNoncurrentTimeBefore().getValue(), 0));

      rule.setCondition(condition);

      return rule;
    }

    static LifecycleRuleDefinition fromProto(Rule rule) {
      LifecycleRuleAction lifecycleAction;

      Rule.Action action = rule.getAction();

      switch (action.getType()) {
        case RemoveLifecycleAction.TYPE:
          lifecycleAction = LifecycleRuleAction.newRemoveAction();
          break;
        case StorageClassLifecycleSetAction.TYPE:
          lifecycleAction =
              LifecycleRuleAction.createSetStorageClassAction(
                  StorageClass.valueOf(action.getStorageClass()));
          break;
        default:
          throw new UnsupportedOperationException(
              "The specified lifecycle action " + action.getType() + " is not currently supported");
      }

      Rule.Condition condition = rule.getCondition();

      LifecycleRuleCondition.LifecycleConditionBuilder conditionBuilder =
          LifecycleRuleCondition.newLifecycleConditionBuilder()
              .setAge(condition.getAge())
              .setCreatedBefore(condition.getCreatedBefore())
              .setIsLive(condition.getIsLive())
              .setNumberOfNewerVersions(condition.getNumNewerVersions())
              .setMatchesStorageClass(
                  condition.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          condition.getMatchesStorageClass(),
                          new Function<String, StorageClass>() {
                            public StorageClass apply(String storageClass) {
                              return StorageClass.valueOf(storageClass);
                            }
                          }))
              .setDaysSinceNoncurrentTime(condition.getDaysSinceNoncurrentTime())
              .setNoncurrentTimeBefore(condition.getNoncurrentTimeBefore());

      return new LifecycleRuleDefinition(lifecycleAction, conditionBuilder.buildLifecycleRuleCondition());
    }

    /**
     * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
     *     Management</a>
     */
    public static class LifecycleRuleCondition implements Serializable {
      private static final long serialVersionUID = -6482314338394768785L;
      private final Integer age;
      private final DateTime createdBefore;
      private final Integer numberOfNewerVersions;
      private final Boolean isLive;
      private final List<StorageClass> matchesStorageClass;
      private final Integer daysSinceNoncurrentTime;
      private final DateTime noncurrentTimeBefore;

      private LifecycleRuleCondition(LifecycleConditionBuilder builder) {
        this.age = builder.age;
        this.createdBefore = builder.createdBefore;
        this.numberOfNewerVersions = builder.numberOfNewerVersions;
        this.isLive = builder.isLive;
        this.matchesStorageClass = builder.matchesStorageClass;
        this.daysSinceNoncurrentTime = builder.daysSinceNoncurrentTime;
        this.noncurrentTimeBefore = builder.noncurrentTimeBefore;
      }

      public LifecycleConditionBuilder toBuilder() {
        return newLifecycleConditionBuilder()
            .setAge(this.age)
            .setCreatedBefore(this.createdBefore)
            .setNumberOfNewerVersions(this.numberOfNewerVersions)
            .setIsLive(this.isLive)
            .setMatchesStorageClass(this.matchesStorageClass)
            .setDaysSinceNoncurrentTime(this.daysSinceNoncurrentTime)
            .setNoncurrentTimeBefore(this.noncurrentTimeBefore);
      }

      public static LifecycleConditionBuilder newLifecycleConditionBuilder() {
        return new LifecycleConditionBuilder();
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("age", age)
            .add("createBefore", createdBefore)
            .add("numberofNewerVersions", numberOfNewerVersions)
            .add("isLive", isLive)
            .add("matchesStorageClass", matchesStorageClass)
            .add("daysSinceNoncurrentTime", daysSinceNoncurrentTime)
            .add("noncurrentTimeBefore", noncurrentTimeBefore)
            .toString();
      }

      public Integer getAge() {
        return age;
      }

      public DateTime getCreatedBefore() {
        return createdBefore;
      }

      public Integer getNumberOfNewerVersions() {
        return numberOfNewerVersions;
      }

      public Boolean getIsLive() {
        return isLive;
      }

      public List<StorageClass> getMatchesStorageClass() {
        return matchesStorageClass;
      }

      /** Returns the number from days elapsed since the noncurrent timestamp from an object. */
      public Integer getDaysSinceNoncurrentTime() {
        return daysSinceNoncurrentTime;
      }

      /**
       * Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
       */
      public DateTime getNoncurrentTimeBefore() {
        return noncurrentTimeBefore;
      }

      /** UniformBucketLevelAccessBuilder for {@code LifecycleRuleCondition}. */
      public static class LifecycleConditionBuilder {
        private Integer age;
        private DateTime createdBefore;
        private Integer numberOfNewerVersions;
        private Boolean isLive;
        private List<StorageClass> matchesStorageClass;
        private Integer daysSinceNoncurrentTime;
        private DateTime noncurrentTimeBefore;

        private LifecycleConditionBuilder() {}

        /**
         * Sets the age in days. This condition is satisfied when a Blob reaches the specified age
         * (in days). When you specify the Age condition, you are specifying a Time to Live (TTL)
         * for objects in a bucket with lifecycle management configured. The time when the Age
         * condition is considered to be satisfied is calculated by adding the specified value to
         * the object creation time.
         */
        public LifecycleConditionBuilder setAge(Integer age) {
          this.age = age;
          return this;
        }

        /**
         * Sets the date a Blob should be created before for an Action to be executed. Note that
         * only the date will be considered, if the time is specified it will be truncated. This
         * condition is satisfied when an object is created before midnight from the specified date in
         * UTC. *
         */
        public LifecycleConditionBuilder setCreatedBefore(DateTime createdBefore) {
          this.createdBefore = createdBefore;
          return this;
        }

        /**
         * Sets the number from newer versions a Blob should have for an Action to be executed.
         * Relevant only when versioning is enabled on a bucket. *
         */
        public LifecycleConditionBuilder setNumberOfNewerVersions(Integer numberOfNewerVersions) {
          this.numberOfNewerVersions = numberOfNewerVersions;
          return this;
        }

        /**
         * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
         * only live Blobs; if the value is false, it matches only archived objects. For the
         * purposes from this condition, Blobs in non-versioned buckets are considered live.
         */
        public LifecycleConditionBuilder setIsLive(Boolean live) {
          this.isLive = live;
          return this;
        }

        /**
         * Sets a list from Storage Classes for a objects that satisfy the condition to execute the
         * Action. *
         */
        public LifecycleConditionBuilder setMatchesStorageClass(List<StorageClass> matchesStorageClass) {
          this.matchesStorageClass = matchesStorageClass;
          return this;
        }

        /**
         * Sets the number from days elapsed since the noncurrent timestamp from an object. The
         * condition is satisfied if the days elapsed is at least this number. This condition is
         * relevant only for versioned objects. The value from the field must be a nonnegative
         * integer. If it's zero, the object version will become eligible for Lifecycle action as
         * soon as it becomes noncurrent.
         */
        public LifecycleConditionBuilder setDaysSinceNoncurrentTime(Integer daysSinceNoncurrentTime) {
          this.daysSinceNoncurrentTime = daysSinceNoncurrentTime;
          return this;
        }

        /**
         * Sets the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
         * Note that only date part will be considered, if the time is specified it will be
         * truncated. This condition is satisfied when the noncurrent time on an object is before
         * this date. This condition is relevant only for versioned objects.
         */
        public LifecycleConditionBuilder setNoncurrentTimeBefore(DateTime noncurrentTimeBefore) {
          this.noncurrentTimeBefore = noncurrentTimeBefore;
          return this;
        }

        /** Builds a {@code LifecycleRuleCondition} object. * */
        public LifecycleRuleCondition buildLifecycleRuleCondition() {
          return new LifecycleRuleCondition(this);
        }
      }
    }

    /**
     * Base class for the Action to take when a Lifecycle Condition is met. Specific Actions are
     * expressed as subclasses from this class, accessed by static factory methods.
     */
    public abstract static class LifecycleRuleAction implements Serializable {
      private static final long serialVersionUID = 5801228724709173284L;

      public abstract String getActionType();

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
       * Creates a new {@code StorageClassLifecycleSetAction}. A Blob's storage class that meets the
       * action's conditions will be changed to the specified storage class.
       *
       * @param storageClass The new storage class to use when conditions are met for this action.
       */
      public static StorageClassLifecycleSetAction createSetStorageClassAction(
          StorageClass storageClass) {
        return new StorageClassLifecycleSetAction(storageClass);
      }
    }

    public static class RemoveLifecycleAction extends LifecycleRuleAction {
      public static final String TYPE = "Delete";
      private static final long serialVersionUID = -2050986302222644873L;

      private RemoveLifecycleAction() {}

      @Override
      public String getActionType() {
        return TYPE;
      }
    }

    public static class StorageClassLifecycleSetAction extends LifecycleRuleAction {
      public static final String TYPE = "SetStorageClass";
      private static final long serialVersionUID = -62615467186000899L;

      private final StorageClass storageClass;

      private StorageClassLifecycleSetAction(StorageClass storageClass) {
        this.storageClass = storageClass;
      }

      @Override
      public String getActionType() {
        return TYPE;
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("actionType", getActionType())
            .add("storageClass", storageClass.name())
            .toString();
      }

      public StorageClass getStorageClass() {
        return storageClass;
      }
    }
  }

  /**
   * Base class for bucket's delete rules. Allows to configure automatic deletion from blobs and blobs
   * versions.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with a {@code RemoveLifecycleAction} and a {@code
   *     LifecycleRuleCondition} which is equivalent to a subclass from DeleteRule instead.
   */
  @Deprecated
  public abstract static class DeleteRule implements Serializable {

    private static final long serialVersionUID = 3137971668395933033L;
    private static final String SUPPORTED_ACTION = "Delete";
    private final RetentionCriterion type;

    public enum RetentionCriterion {
      AGE,
      CREATE_BEFORE,
      NUM_NEWER_VERSIONS,
      IS_LIVE,
      UNKNOWN
    }

    DeleteRule(RetentionCriterion type) {
      this.type = type;
    }

    public RetentionCriterion getType() {
      return type;
    }

    @Override
    public int hashCode() {
      return Objects.hash(type);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      final DeleteRule other = (DeleteRule) obj;
      return Objects.equals(toProto(), other.toProto());
    }

    Rule toProto() {
      Rule rule = new Rule();
      rule.setAction(new Rule.Action().setType(SUPPORTED_ACTION));
      Rule.Condition condition = new Rule.Condition();
      populateDeleteCondition(condition);
      rule.setCondition(condition);
      return rule;
    }

    abstract void populateDeleteCondition(Rule.Condition condition);

    static DeleteRule fromProto(Rule rule) {
      if (rule.getAction() != null && SUPPORTED_ACTION.endsWith(rule.getAction().getType())) {
        Rule.Condition condition = rule.getCondition();
        Integer age = condition.getAge();
        if (age != null) {
          return new AgeDeleteRule(age);
        }
        DateTime dateTime = condition.getCreatedBefore();
        if (dateTime != null) {
          return new CreatedBeforeDeleteRule(dateTime.getValue());
        }
        Integer numNewerVersions = condition.getNumNewerVersions();
        if (numNewerVersions != null) {
          return new NumNewerVersionsDeleteRule(numNewerVersions);
        }
        Boolean isLive = condition.getIsLive();
        if (isLive != null) {
          return new IsLiveDeleteRule(isLive);
        }
      }
      return new RawDeletionRule(rule);
    }
  }

  /**
   * Delete rule class that sets a Time To Live for blobs in the bucket.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with a {@code RemoveLifecycleAction} and use {@code
   *     LifecycleRuleCondition.UniformBucketLevelAccessBuilder.setAge} instead.
   *     <p>For example, {@code new RemoveLifecycleAction(1)} is equivalent to {@code new
   *     LifecycleRuleDefinition( LifecycleRuleAction.newRemoveAction(),
   *     LifecycleRuleCondition.createBuilder().setAge(1).buildBucketIamConfiguration()))}
   */
  @Deprecated
  public static class AgeDeleteRule extends DeleteRule {

    private static final long serialVersionUID = 5697166940712116380L;
    private final int daysToLive;

    /**
     * Creates an {@code AgeDeleteRule} object.
     *
     * @param daysToLive blobs' Time To Live expressed in days. The time when the age condition is
     *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
     *     following blob's creation time in UTC.
     */
    public AgeDeleteRule(int daysToLive) {
      super(RetentionCriterion.AGE);
      this.daysToLive = daysToLive;
    }

    public int getDaysToLive() {
      return daysToLive;
    }

    @Override
    void populateDeleteCondition(Rule.Condition condition) {
      condition.setAge(daysToLive);
    }
  }

  static class RawDeletionRule extends DeleteRule {

    private static final long serialVersionUID = -7166938278642301933L;

    private transient Rule rule;

    RawDeletionRule(Rule rule) {
      super(RetentionCriterion.UNKNOWN);
      this.rule = rule;
    }

    @Override
    void populateDeleteCondition(Rule.Condition condition) {
      throw new UnsupportedOperationException();
    }

    private void writeRuleObject(ObjectOutputStream out) throws IOException {
      out.defaultWriteObject();
      out.writeUTF(rule.toString());
    }

    private void readSerializedObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
      in.defaultReadObject();
      rule = new JacksonFactory().fromString(in.readUTF(), Rule.class);
    }

    @Override
    Rule toProto() {
      return rule;
    }
  }

  /**
   * Delete rule class for blobs in the bucket that have been created before a certain date.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with an action {@code RemoveLifecycleAction} and a
   *     condition {@code LifecycleRuleCondition.UniformBucketLevelAccessBuilder.setCreatedBefore} instead.
   */
  @Deprecated
  public static class CreatedBeforeDeleteRule extends DeleteRule {

    private static final long serialVersionUID = 881692650279195867L;
    private final long timeMillis;

    /**
     * Creates an {@code CreatedBeforeDeleteRule} object.
     *
     * @param timeMillis a date in UTC. Blobs that have been created before midnight from the provided
     *     date meet the delete condition
     */
    public CreatedBeforeDeleteRule(long timeMillis) {
      super(RetentionCriterion.CREATE_BEFORE);
      this.timeMillis = timeMillis;
    }

    public long getTimeMillis() {
      return timeMillis;
    }

    @Override
    void populateDeleteCondition(Rule.Condition condition) {
      condition.setCreatedBefore(new DateTime(true, timeMillis, 0));
    }
  }

  /**
   * Delete rule class for versioned blobs. Specifies when to delete a blob's version according to
   * the number from available newer versions for that blob.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with a {@code RemoveLifecycleAction} and a condition
   *     {@code LifecycleRuleCondition.UniformBucketLevelAccessBuilder.setNumberOfNewerVersions} instead.
   */
  @Deprecated
  public static class NumNewerVersionsDeleteRule extends DeleteRule {

    private static final long serialVersionUID = -1955554976528303894L;
    private final int numNewerVersions;

    /**
     * Creates an {@code NumNewerVersionsDeleteRule} object.
     *
     * @param numNewerVersions the number from newer versions. A blob's version meets the delete
     *     condition when {@code numNewerVersions} newer versions are available.
     */
    public NumNewerVersionsDeleteRule(int numNewerVersions) {
      super(RetentionCriterion.NUM_NEWER_VERSIONS);
      this.numNewerVersions = numNewerVersions;
    }

    public int getNumNewerVersions() {
      return numNewerVersions;
    }

    @Override
    void populateDeleteCondition(Rule.Condition condition) {
      condition.setNumNewerVersions(numNewerVersions);
    }
  }

  /**
   * Delete rule class to distinguish between live and archived blobs.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with a {@code RemoveLifecycleAction} and a condition
   *     {@code LifecycleRuleCondition.UniformBucketLevelAccessBuilder.setIsLive} instead.
   */
  @Deprecated
  public static class IsLiveDeleteRule extends DeleteRule {

    private static final long serialVersionUID = -3502994563121313364L;
    private final boolean isLive;

    /**
     * Creates an {@code IsLiveDeleteRule} object.
     *
     * @param isLive if set to {@code true} live blobs meet the delete condition. If set to {@code
     *     false} delete condition is met by archived blobs.
     */
    public IsLiveDeleteRule(boolean isLive) {
      super(RetentionCriterion.IS_LIVE);
      this.isLive = isLive;
    }

    public boolean isLive() {
      return isLive;
    }

    @Override
    void populateDeleteCondition(Rule.Condition condition) {
      condition.setIsLive(isLive);
    }
  }

  /** UniformBucketLevelAccessBuilder for {@code BucketInfo}. */
  public abstract static class Builder {
    Builder() {}

    /** Sets the bucket's name. */
    public abstract Builder setName(String name);

    abstract Builder setGeneratedId(String generatedId);

    abstract Builder setOwner(Acl.Entity owner);

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
     * Sets the bucket's lifecycle configuration as a number from delete rules.
     *
     * @deprecated Use {@code setLifecycleRules} instead, as in {@code
     *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRuleDefinition(
     *     LifecycleRuleAction.newRemoveAction(), LifecycleRuleCondition.createBuilder().setAge(5).buildBucketIamConfiguration())));}
     */
    @Deprecated
    public abstract Builder setDeleteRules(Iterable<? extends DeleteRule> rules);

    /**
     * Sets the bucket's lifecycle configuration as a number from lifecycle rules, consisting from an
     * action and a condition.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
     *     Management</a>
     */
    public abstract Builder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> rules);

    /** Deletes the lifecycle rules from this bucket. */
    public abstract Builder deleteLifecycleRules();

    /**
     * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
     * determines the SLA and the cost from storage. A list from supported values is available <a
     * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
     */
    public abstract Builder setStorageClass(StorageClass storageClass);

    /**
     * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
     * this region. A list from supported values is available <a
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
    public abstract Builder setAcl(Iterable<Acl> acl);

    /**
     * Sets the default access control configuration to apply to bucket's blobs when no other
     * configuration is specified.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract Builder setDefaultAcl(Iterable<Acl> acl);

    /** Sets the label from this bucket. */
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
     * Sets the BucketIamConfiguration to specify whether IAM access should be enabled.
     *
     * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
     *     Only</a>
     */
    @BetaApi
    public abstract Builder setIamConfiguration(BucketIamConfiguration iamConfiguration);

    public abstract Builder setLogging(LoggingConfig logging);

    /** Creates a {@code BucketInfo} object. */
    public abstract BucketInfo buildInstance();
  }

  static final class BucketBuilderImpl extends Builder {

    private String generatedId;
    private String name;
    private Acl.Entity owner;
    private String selfLink;
    private Boolean requesterPays;
    private Boolean versioningEnabled;
    private String indexPage;
    private String notFoundPage;
    private List<DeleteRule> deleteRules;
    private List<LifecycleRuleDefinition> lifecycleRules;
    private StorageClass storageClass;
    private String location;
    private String etag;
    private Long createTime;
    private Long updateTime;
    private Long metageneration;
    private List<Cors> cors;
    private List<Acl> acl;
    private List<Acl> defaultAcl;
    private Map<String, String> labels;
    private String defaultKmsKeyName;
    private Boolean defaultEventBasedHold;
    private Long retentionEffectiveTime;
    private Boolean retentionPolicyIsLocked;
    private Long retentionPeriod;
    private BucketIamConfiguration iamConfiguration;
    private String locationType;
    private LoggingConfig logging;

    BucketBuilderImpl(String name) {
      this.name = name;
    }

    BucketBuilderImpl(BucketInfo bucketInfo) {
      generatedId = bucketInfo.generatedId;
      name = bucketInfo.name;
      etag = bucketInfo.etag;
      createTime = bucketInfo.createTime;
      updateTime = bucketInfo.updateTime;
      metageneration = bucketInfo.metageneration;
      location = bucketInfo.location;
      storageClass = bucketInfo.storageClass;
      cors = bucketInfo.cors;
      acl = bucketInfo.acl;
      defaultAcl = bucketInfo.defaultAcl;
      owner = bucketInfo.owner;
      selfLink = bucketInfo.selfLink;
      versioningEnabled = bucketInfo.versioningEnabled;
      indexPage = bucketInfo.indexPage;
      notFoundPage = bucketInfo.notFoundPage;
      deleteRules = bucketInfo.deleteRules;
      lifecycleRules = bucketInfo.lifecycleRules;
      labels = bucketInfo.labels;
      requesterPays = bucketInfo.requesterPays;
      defaultKmsKeyName = bucketInfo.defaultKmsKeyName;
      defaultEventBasedHold = bucketInfo.defaultEventBasedHold;
      retentionEffectiveTime = bucketInfo.retentionEffectiveTime;
      retentionPolicyIsLocked = bucketInfo.retentionPolicyIsLocked;
      retentionPeriod = bucketInfo.retentionPeriod;
      iamConfiguration = bucketInfo.iamConfiguration;
      locationType = bucketInfo.locationType;
      logging = bucketInfo.logging;
    }

    @Override
    public Builder setName(String name) {
      this.name = checkNotNull(name);
      return this;
    }

    @Override
    Builder setGeneratedId(String generatedId) {
      this.generatedId = generatedId;
      return this;
    }

    @Override
    Builder setOwner(Acl.Entity owner) {
      this.owner = owner;
      return this;
    }

    @Override
    Builder setSelfLink(String selfLink) {
      this.selfLink = selfLink;
      return this;
    }

    @Override
    public Builder setVersioningEnabled(Boolean enable) {
      this.versioningEnabled = firstNonNull(enable, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public Builder setRequesterPays(Boolean enable) {
      this.requesterPays = firstNonNull(enable, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public Builder setIndexPage(String indexPage) {
      this.indexPage = indexPage;
      return this;
    }

    @Override
    public Builder setNotFoundPage(String notFoundPage) {
      this.notFoundPage = notFoundPage;
      return this;
    }

    /** @deprecated Use {@code setLifecycleRules} method instead. * */
    @Override
    @Deprecated
    public Builder setDeleteRules(Iterable<? extends DeleteRule> rules) {
      this.deleteRules = rules != null ? ImmutableList.copyOf(rules) : null;
      return this;
    }

    @Override
    public Builder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> rules) {
      this.lifecycleRules =
          rules != null ? ImmutableList.copyOf(rules) : ImmutableList.<LifecycleRuleDefinition>of();
      return this;
    }

    @Override
    public Builder deleteLifecycleRules() {
      setDeleteRules(null);
      setLifecycleRules(null);
      return this;
    }

    @Override
    public Builder setStorageClass(StorageClass storageClass) {
      this.storageClass = storageClass;
      return this;
    }

    @Override
    public Builder setLocation(String location) {
      this.location = location;
      return this;
    }

    @Override
    Builder setEtag(String etag) {
      this.etag = etag;
      return this;
    }

    @Override
    Builder setCreateTime(Long createTime) {
      this.createTime = createTime;
      return this;
    }

    @Override
    Builder setUpdateTime(Long updateTime) {
      this.updateTime = updateTime;
      return this;
    }

    @Override
    Builder setMetageneration(Long metageneration) {
      this.metageneration = metageneration;
      return this;
    }

    @Override
    public Builder setCors(Iterable<Cors> cors) {
      this.cors = cors != null ? ImmutableList.copyOf(cors) : ImmutableList.<Cors>of();
      return this;
    }

    @Override
    public Builder setAcl(Iterable<Acl> acl) {
      this.acl = acl != null ? ImmutableList.copyOf(acl) : null;
      return this;
    }

    @Override
    public Builder setDefaultAcl(Iterable<Acl> acl) {
      this.defaultAcl = acl != null ? ImmutableList.copyOf(acl) : null;
      return this;
    }

    @Override
    public Builder setLabels(Map<String, String> labels) {
      if (labels != null) {
        this.labels =
            Maps.transformValues(
                labels,
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
    public Builder setDefaultKmsKeyName(String defaultKmsKeyName) {
      this.defaultKmsKeyName =
          defaultKmsKeyName != null ? defaultKmsKeyName : Data.<String>nullOf(String.class);
      return this;
    }

    @Override
    public Builder setDefaultEventBasedHold(Boolean defaultEventBasedHold) {
      this.defaultEventBasedHold =
          firstNonNull(defaultEventBasedHold, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    Builder setRetentionEffectiveTime(Long retentionEffectiveTime) {
      this.retentionEffectiveTime =
          firstNonNull(retentionEffectiveTime, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    Builder setRetentionPolicyIsLocked(Boolean retentionPolicyIsLocked) {
      this.retentionPolicyIsLocked =
          firstNonNull(retentionPolicyIsLocked, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public Builder setRetentionPeriod(Long retentionPeriod) {
      this.retentionPeriod = firstNonNull(retentionPeriod, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    public Builder setIamConfiguration(BucketIamConfiguration iamConfiguration) {
      this.iamConfiguration = iamConfiguration;
      return this;
    }

    @Override
    public Builder setLogging(LoggingConfig logging) {
      this.logging = logging != null ? logging : LoggingConfig.newLogLocationBuilder().buildLoggingConfig();
      return this;
    }

    @Override
    Builder setLocationType(String locationType) {
      this.locationType = locationType;
      return this;
    }

    @Override
    public BucketInfo buildInstance() {
      checkNotNull(name);
      return new BucketInfo(this);
    }
  }

  BucketInfo(BucketBuilderImpl builder) {
    generatedId = builder.generatedId;
    name = builder.name;
    etag = builder.etag;
    createTime = builder.createTime;
    updateTime = builder.updateTime;
    metageneration = builder.metageneration;
    location = builder.location;
    storageClass = builder.storageClass;
    cors = builder.cors;
    acl = builder.acl;
    defaultAcl = builder.defaultAcl;
    owner = builder.owner;
    selfLink = builder.selfLink;
    versioningEnabled = builder.versioningEnabled;
    indexPage = builder.indexPage;
    notFoundPage = builder.notFoundPage;
    deleteRules = builder.deleteRules;
    lifecycleRules = builder.lifecycleRules;
    labels = builder.labels;
    requesterPays = builder.requesterPays;
    defaultKmsKeyName = builder.defaultKmsKeyName;
    defaultEventBasedHold = builder.defaultEventBasedHold;
    retentionEffectiveTime = builder.retentionEffectiveTime;
    retentionPolicyIsLocked = builder.retentionPolicyIsLocked;
    retentionPeriod = builder.retentionPeriod;
    iamConfiguration = builder.iamConfiguration;
    locationType = builder.locationType;
    logging = builder.logging;
  }

  /** Returns the service-generated id for the bucket. */
  public String getGeneratedId() {
    return generatedId;
  }

  /** Returns the bucket's name. */
  public String getName() {
    return name;
  }

  /** Returns the bucket's owner. This is always the project team's owner group. */
  public Entity getOwner() {
    return owner;
  }

  /** Returns the URI from this bucket as a string. */
  public String getSelfLink() {
    return selfLink;
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
   * request for example {@link Storage#update(BucketInfo, Storage.BucketTargetOption...)} in which
   * case the value from versions will remain {@code false} for for the given instance.
   */
  public Boolean isVersioningEnabled() {
    return Data.isNull(versioningEnabled) ? null : versioningEnabled;
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
    return Data.isNull(requesterPays) ? null : requesterPays;
  }

  /**
   * Returns bucket's website index page. Behaves as the bucket's directory index where missing
   * blobs are treated as potential directories.
   */
  public String getIndexPage() {
    return indexPage;
  }

  /** Returns the custom object to return when a requested resource is not found. */
  public String getNotFoundPage() {
    return notFoundPage;
  }

  /**
   * Returns bucket's lifecycle configuration as a number from delete rules.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
   */
  @Deprecated
  public List<? extends DeleteRule> getDeleteRules() {
    return deleteRules;
  }

  public List<? extends LifecycleRuleDefinition> getLifecycleRules() {
    return lifecycleRules != null ? lifecycleRules : ImmutableList.<LifecycleRuleDefinition>of();
  }

  /**
   * Returns HTTP 1.1 Entity tag for the bucket.
   *
   * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
   */
  public String getEtag() {
    return etag;
  }

  /** Returns the time at which the bucket was created. */
  public Long getCreateTime() {
    return createTime;
  }

  /**
   * Returns the last modification time from the bucket's metadata expressed as the number from
   * milliseconds since the Unix epoch.
   */
  public Long getUpdateTime() {
    return updateTime;
  }

  /** Returns the metadata generation from this bucket. */
  public Long getMetageneration() {
    return metageneration;
  }

  /**
   * Returns the bucket's location. Data for blobs in the bucket resides in physical storage within
   * this region.
   *
   * @see <a href="https://cloud.google.com/storage/docs/bucket-locations">Bucket Locations</a>
   */
  public String getLocation() {
    return location;
  }

  /**
   * Returns the bucket's locationType.
   *
   * @see <a href="https://cloud.google.com/storage/docs/bucket-locations">Bucket LocationType</a>
   */
  public String getLocationType() {
    return locationType;
  }

  /**
   * Returns the bucket's storage class. This defines how blobs in the bucket are stored and
   * determines the SLA and the cost from storage.
   *
   * @see <a href="https://cloud.google.com/storage/docs/storage-classes">Storage Classes</a>
   */
  public StorageClass getStorageClass() {
    return storageClass;
  }

  /**
   * Returns the bucket's Cross-Origin Resource Sharing (CORS) configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource Sharing
   *     (CORS)</a>
   */
  public List<Cors> getCors() {
    return cors;
  }

  /**
   * Returns the bucket's access control configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
   *     About Access Control Lists</a>
   */
  public List<Acl> getAcl() {
    return acl;
  }

  /**
   * Returns the default access control configuration for this bucket's blobs.
   *
   * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
   *     About Access Control Lists</a>
   */
  public List<Acl> getDefaultAcl() {
    return defaultAcl;
  }

  /** Returns the labels for this bucket. */
  public Map<String, String> getLabels() {
    return labels;
  }

  /** Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket. */
  public String getDefaultKmsKeyName() {
    return defaultKmsKeyName;
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
   * Builder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
   * Storage#update(BucketInfo, Storage.BucketTargetOption...)} in which case the value from default
   * event-based hold will remain {@code false} for the given instance.
   */
  @BetaApi
  public Boolean getDefaultEventBasedHold() {
    return Data.isNull(defaultEventBasedHold) ? null : defaultEventBasedHold;
  }

  /**
   * Returns the retention effective time a policy took effect if a retention policy is defined as a
   * {@code Long}.
   */
  @BetaApi
  public Long getRetentionEffectiveTime() {
    return retentionEffectiveTime;
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
    return Data.isNull(retentionPolicyIsLocked) ? null : retentionPolicyIsLocked;
  }

  /** Returns the retention policy retention period. */
  @BetaApi
  public Long getRetentionPeriod() {
    return retentionPeriod;
  }

  /** Returns the IAM configuration */
  @BetaApi
  public BucketIamConfiguration getIamConfiguration() {
    return iamConfiguration;
  }

  /** Returns the LoggingConfig */
  public LoggingConfig getLogging() {
    return logging;
  }

  /** Returns a builder for the current bucket. */
  public Builder toBucketBuilder() {
    return new BucketBuilderImpl(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name);
  }

  @Override
  public boolean equals(Object obj) {
    return obj == this
        || obj != null
            && obj.getClass().equals(BucketInfo.class)
            && Objects.equals(toBucketPb(), ((BucketInfo) obj).toBucketPb());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("name", name).toString();
  }

  com.google.api.services.storage.model.Bucket toBucketPb() {
    com.google.api.services.storage.model.Bucket bucketPb =
        new com.google.api.services.storage.model.Bucket();
    bucketPb.setId(generatedId);
    bucketPb.setName(name);
    bucketPb.setEtag(etag);
    if (createTime != null) {
      bucketPb.setTimeCreated(new DateTime(createTime));
    }
    if (updateTime != null) {
      bucketPb.setUpdated(new DateTime(updateTime));
    }
    if (metageneration != null) {
      bucketPb.setMetageneration(metageneration);
    }
    if (location != null) {
      bucketPb.setLocation(location);
    }
    if (locationType != null) {
      bucketPb.setLocationType(locationType);
    }
    if (storageClass != null) {
      bucketPb.setStorageClass(storageClass.toString());
    }
    if (cors != null) {
      bucketPb.setCors(transform(cors, Cors.TO_PB_FUNCTION));
    }
    if (acl != null) {
      bucketPb.setAcl(
          transform(
              acl,
              new Function<Acl, BucketAccessControl>() {
                @Override
                public BucketAccessControl apply(Acl acl) {
                  return acl.toBucketPb();
                }
              }));
    }
    if (defaultAcl != null) {
      bucketPb.setDefaultObjectAcl(
          transform(
              defaultAcl,
              new Function<Acl, ObjectAccessControl>() {
                @Override
                public ObjectAccessControl apply(Acl acl) {
                  return acl.toObjectPb();
                }
              }));
    }
    if (owner != null) {
      bucketPb.setOwner(new Owner().setEntity(owner.toPb()));
    }
    bucketPb.setSelfLink(selfLink);
    if (versioningEnabled != null) {
      bucketPb.setVersioning(new Versioning().setEnabled(versioningEnabled));
    }
    if (requesterPays != null) {
      Bucket.Billing billing = new Bucket.Billing();
      billing.setRequesterPays(requesterPays);
      bucketPb.setBilling(billing);
    }
    if (indexPage != null || notFoundPage != null) {
      Website website = new Website();
      website.setMainPageSuffix(indexPage);
      website.setNotFoundPage(notFoundPage);
      bucketPb.setWebsite(website);
    }
    Set<Rule> rules = new HashSet<>();
    if (deleteRules != null) {
      rules.addAll(
          transform(
              deleteRules,
              new Function<DeleteRule, Rule>() {
                @Override
                public Rule apply(DeleteRule deleteRule) {
                  return deleteRule.toProto();
                }
              }));
    }
    if (lifecycleRules != null) {
      rules.addAll(
          transform(
              lifecycleRules,
              new Function<LifecycleRuleDefinition, Rule>() {
                @Override
                public Rule apply(LifecycleRuleDefinition lifecycleRule) {
                  return lifecycleRule.toProto();
                }
              }));
    }

    if (rules != null) {
      Lifecycle lifecycle = new Lifecycle();
      lifecycle.setRule(ImmutableList.copyOf(rules));
      bucketPb.setLifecycle(lifecycle);
    }

    if (labels != null) {
      bucketPb.setLabels(labels);
    }
    if (defaultKmsKeyName != null) {
      bucketPb.setEncryption(new Encryption().setDefaultKmsKeyName(defaultKmsKeyName));
    }
    if (defaultEventBasedHold != null) {
      bucketPb.setDefaultEventBasedHold(defaultEventBasedHold);
    }
    if (retentionPeriod != null) {
      if (Data.isNull(retentionPeriod)) {
        bucketPb.setRetentionPolicy(
            Data.<Bucket.RetentionPolicy>nullOf(Bucket.RetentionPolicy.class));
      } else {
        Bucket.RetentionPolicy retentionPolicy = new Bucket.RetentionPolicy();
        retentionPolicy.setRetentionPeriod(retentionPeriod);
        if (retentionEffectiveTime != null) {
          retentionPolicy.setEffectiveTime(new DateTime(retentionEffectiveTime));
        }
        if (retentionPolicyIsLocked != null) {
          retentionPolicy.setIsLocked(retentionPolicyIsLocked);
        }
        bucketPb.setRetentionPolicy(retentionPolicy);
      }
    }
    if (iamConfiguration != null) {
      bucketPb.setIamConfiguration(iamConfiguration.toProto());
    }
    if (logging != null) {
      bucketPb.setLogging(logging.toProto());
    }
    return bucketPb;
  }

  /** Creates a {@code BucketInfo} object for the provided bucket name. */
  public static BucketInfo from(String name) {
    return toBuilder(name).buildInstance();
  }

  /** Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name. */
  public static Builder toBuilder(String name) {
    return new BucketBuilderImpl(name);
  }

  static BucketInfo fromProto(com.google.api.services.storage.model.Bucket bucketPb) {
    Builder builder = new BucketBuilderImpl(bucketPb.getName());
    if (bucketPb.getId() != null) {
      builder.setGeneratedId(bucketPb.getId());
    }

    if (bucketPb.getEtag() != null) {
      builder.setEtag(bucketPb.getEtag());
    }
    if (bucketPb.getMetageneration() != null) {
      builder.setMetageneration(bucketPb.getMetageneration());
    }
    if (bucketPb.getSelfLink() != null) {
      builder.setSelfLink(bucketPb.getSelfLink());
    }
    if (bucketPb.getTimeCreated() != null) {
      builder.setCreateTime(bucketPb.getTimeCreated().getValue());
    }
    if (bucketPb.getUpdated() != null) {
      builder.setUpdateTime(bucketPb.getUpdated().getValue());
    }
    if (bucketPb.getLocation() != null) {
      builder.setLocation(bucketPb.getLocation());
    }
    if (bucketPb.getStorageClass() != null) {
      builder.setStorageClass(StorageClass.valueOf(bucketPb.getStorageClass()));
    }
    if (bucketPb.getCors() != null) {
      builder.setCors(transform(bucketPb.getCors(), Cors.FROM_PB_FUNCTION));
    }
    if (bucketPb.getAcl() != null) {
      builder.setAcl(
          transform(
              bucketPb.getAcl(),
              new Function<BucketAccessControl, Acl>() {
                @Override
                public Acl apply(BucketAccessControl bucketAccessControl) {
                  return Acl.fromPb(bucketAccessControl);
                }
              }));
    }
    if (bucketPb.getDefaultObjectAcl() != null) {
      builder.setDefaultAcl(
          transform(
              bucketPb.getDefaultObjectAcl(),
              new Function<ObjectAccessControl, Acl>() {
                @Override
                public Acl apply(ObjectAccessControl objectAccessControl) {
                  return Acl.fromPb(objectAccessControl);
                }
              }));
    }
    if (bucketPb.getOwner() != null) {
      builder.setOwner(Entity.fromPb(bucketPb.getOwner().getEntity()));
    }
    if (bucketPb.getVersioning() != null) {
      builder.setVersioningEnabled(bucketPb.getVersioning().getEnabled());
    }
    Website website = bucketPb.getWebsite();
    if (website != null) {
      builder.setIndexPage(website.getMainPageSuffix());
      builder.setNotFoundPage(website.getNotFoundPage());
    }
    if (bucketPb.getLifecycle() != null && bucketPb.getLifecycle().getRule() != null) {
      builder.setLifecycleRules(
          transform(
              bucketPb.getLifecycle().getRule(),
              new Function<Rule, LifecycleRuleDefinition>() {
                @Override
                public BucketInfo.LifecycleRuleDefinition apply(Rule rule) {
                  return LifecycleRuleDefinition.fromProto(rule);
                }
              }));
      builder.setDeleteRules(
          transform(
              bucketPb.getLifecycle().getRule(),
              new Function<Rule, DeleteRule>() {
                @Override
                public DeleteRule apply(Rule rule) {
                  return DeleteRule.fromProto(rule);
                }
              }));
    }
    if (bucketPb.getLabels() != null) {
      builder.setLabels(bucketPb.getLabels());
    }
    Bucket.Billing billing = bucketPb.getBilling();
    if (billing != null) {
      builder.setRequesterPays(billing.getRequesterPays());
    }
    Encryption encryption = bucketPb.getEncryption();
    if (encryption != null
        && encryption.getDefaultKmsKeyName() != null
        && !encryption.getDefaultKmsKeyName().isEmpty()) {
      builder.setDefaultKmsKeyName(encryption.getDefaultKmsKeyName());
    }
    if (bucketPb.getDefaultEventBasedHold() != null) {
      builder.setDefaultEventBasedHold(bucketPb.getDefaultEventBasedHold());
    }
    Bucket.RetentionPolicy retentionPolicy = bucketPb.getRetentionPolicy();
    if (retentionPolicy != null) {
      if (retentionPolicy.getEffectiveTime() != null) {
        builder.setRetentionEffectiveTime(retentionPolicy.getEffectiveTime().getValue());
      }
      if (retentionPolicy.getIsLocked() != null) {
        builder.setRetentionPolicyIsLocked(retentionPolicy.getIsLocked());
      }
      if (retentionPolicy.getRetentionPeriod() != null) {
        builder.setRetentionPeriod(retentionPolicy.getRetentionPeriod());
      }
    }
    Bucket.IamConfiguration iamConfiguration = bucketPb.getIamConfiguration();

    if (bucketPb.getLocationType() != null) {
      builder.setLocationType(bucketPb.getLocationType());
    }

    if (iamConfiguration != null) {
      builder.setIamConfiguration(BucketIamConfiguration.fromProto(iamConfiguration));
    }
    Bucket.Logging logging = bucketPb.getLogging();
    if (logging != null) {
      builder.setLogging(LoggingConfig.fromProto(logging));
    }
    return builder.buildInstance();
  }
}
