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

  static final Function<com.google.api.services.storage.model.Bucket, BucketInfo> BUCKET_INFO_FROM_PB_FN =
      new Function<com.google.api.services.storage.model.Bucket, BucketInfo>() {
        @Override
        public BucketInfo apply(com.google.api.services.storage.model.Bucket pb) {
          return BucketInfo.fromProto(pb);
        }
      };
  static final Function<BucketInfo, com.google.api.services.storage.model.Bucket> BUCKET_INFO_TO_PB_FN =
      new Function<BucketInfo, com.google.api.services.storage.model.Bucket>() {
        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketInfo bucketInfo) {
          return bucketInfo.toBucketPb();
        }
      };
  private static final long CLASS_SERIAL_ID = -4712013629621638459L;
  private final String generatedIdentifier;
  private final String label;
  private final Acl.Entity ownerEntityRef;
  private final String resourceLink;
  private final Boolean requesterPaysFlag;
  private final Boolean versioningActive;
  private final String indexDocument;
  private final String errorDocument;
  private final List<DeleteRule> deletionRules;
  private final List<LifecycleRuleDefinition> lifecycleDefinitions;
  private final String entityTag;
  private final Long creationTimestamp;
  private final Long metaVersion;
  private final List<Cors> corsRules;
  private final List<Acl> accessControls;
  private final List<Acl> defaultAccessControls;
  private final String region;
  private final StorageClass storageTier;
  private final Map<String, String> metadataLabels;
  private final String defaultKmsKeyReference;
  private final Boolean eventBasedHoldDefault;
  private final Long retentionStartTime;
  private final Boolean retentionPolicyLocked;
  private final Long retentionDuration;
  private final BucketIamConfiguration iamConfig;
  private final String regionType;
  private final LogConfig logConfig;

  /**
   * The StorageBucket's IAM Configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/uniform-bucket-level-access">uniform
   *     bucket-level access</a>
   */
  public static class BucketIamConfiguration implements Serializable {
    private static final long CLASS_SERIAL_ID = -8671736104909424616L;

    private Boolean uniformBucketAccessEnabled;
    private Long uniformAccessLockTime;

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
      return Objects.hash(uniformBucketAccessEnabled, uniformAccessLockTime);
    }

    private BucketIamConfiguration(UniformBucketLevelAccessBuilder accessBuilder) {
      this.uniformBucketAccessEnabled = accessBuilder.uniformBucketAccessEnabled;
      this.uniformAccessLockTime = accessBuilder.uniformAccessLockTime;
    }

    public static UniformBucketLevelAccessBuilder newUniformBucketLevelAccessBuilder() {
      return new UniformBucketLevelAccessBuilder();
    }

    public UniformBucketLevelAccessBuilder toUniformBucketLevelAccessBuilder() {
      UniformBucketLevelAccessBuilder accessBuilder = new UniformBucketLevelAccessBuilder();
      accessBuilder.uniformBucketAccessEnabled = uniformBucketAccessEnabled;
      accessBuilder.uniformAccessLockTime = uniformAccessLockTime;
      return accessBuilder;
    }

    /** Deprecated in favor from isUniformBucketLevelAccessEnabled(). */
    @Deprecated
    public Boolean isBucketPolicyOnlyEnabled() {
      return uniformBucketAccessEnabled;
    }

    /** Deprecated in favor from uniformBucketLevelAccessLockedTime(). */
    @Deprecated
    public Long getBucketPolicyOnlyLockedTime() {
      return uniformAccessLockTime;
    }

    public Boolean isUniformBucketLevelAccessEnabled() {
      return uniformBucketAccessEnabled;
    }

    public Long getUniformBucketLevelAccessLockedTime() {
      return uniformAccessLockTime;
    }

    Bucket.IamConfiguration toProto() {
      Bucket.IamConfiguration iamConfig = new Bucket.IamConfiguration();

      Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessProto =
          new Bucket.IamConfiguration.UniformBucketLevelAccess();
      uniformAccessProto.setEnabled(uniformBucketAccessEnabled);
      uniformAccessProto.setLockedTime(
          uniformAccessLockTime == null
              ? null
              : new DateTime(uniformAccessLockTime));

      iamConfig.setUniformBucketLevelAccess(uniformAccessProto);

      return iamConfig;
    }

    static BucketIamConfiguration fromProto(Bucket.IamConfiguration iamConfig) {
      Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessProto =
          iamConfig.getUniformBucketLevelAccess();
      DateTime lockedTimestamp = uniformAccessProto.getLockedTime();

      return newUniformBucketLevelAccessBuilder()
          .setIsUniformBucketLevelAccessEnabled(uniformAccessProto.getEnabled())
          .setUniformBucketLevelAccessLockedTime(lockedTimestamp == null ? null : lockedTimestamp.getValue())
          .buildBucketIamConfiguration();
    }

    /** BucketInfoBuilder for {@code BucketIamConfiguration} */
    public static class UniformBucketLevelAccessBuilder {
      private Boolean uniformBucketAccessEnabled;
      private Long uniformAccessLockTime;

      /** Deprecated in favor from setIsUniformBucketLevelAccessEnabled(). */
      @Deprecated
      public BucketInfo.BucketIamConfiguration.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean bucketPolicyOnlyEnabled) {
        this.uniformBucketAccessEnabled = bucketPolicyOnlyEnabled;
        return this;
      }

      /** Deprecated in favor from setUniformBucketLevelAccessLockedTime(). */
      @Deprecated
      BucketInfo.BucketIamConfiguration.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long policyOnlyLockTime) {
        this.uniformAccessLockTime = policyOnlyLockTime;
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
          Boolean uniformBucketAccessEnabled) {
        this.uniformBucketAccessEnabled = uniformBucketAccessEnabled;
        return this;
      }

      /**
       * Sets the deadline for switching {@code uniformBucketLevelAccess.enabled} back to false.
       * After this time passes, calls to do so will fail. This is package-private, since in general
       * this field should never be set by a user--it's automatically set by the backend when {@code
       * enabled} is set to true.
       */
      UniformBucketLevelAccessBuilder setUniformBucketLevelAccessLockedTime(Long uniformAccessLockTime) {
        this.uniformAccessLockTime = uniformAccessLockTime;
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
  public static class LogConfig implements Serializable {

    private static final long CLASS_SERIAL_ID = -708892101216778492L;
    private String loggingBucket;
    private String logObjectPathPrefix;

    @Override
    public boolean equals(Object otherObj) {
      if (this == otherObj) return true;
      if (otherObj == null || getClass() != otherObj.getClass()) {
        return false;
      }
      LogConfig otherConfig = (LogConfig) otherObj;
      return Objects.equals(toProto(), otherConfig.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(loggingBucket, logObjectPathPrefix);
    }

    public static LogConfigBuilder newLogConfigBuilder() {
      return new LogConfigBuilder();
    }

    public LogConfigBuilder toBuilder() {
      LogConfigBuilder accessBuilder = new LogConfigBuilder();
      accessBuilder.loggingBucket = loggingBucket;
      accessBuilder.logObjectPathPrefix = logObjectPathPrefix;
      return accessBuilder;
    }

    public String getLogBucket() {
      return loggingBucket;
    }

    public String getLogObjectPrefix() {
      return logObjectPathPrefix;
    }

    Bucket.Logging toProto() {
      Bucket.Logging logConfig;
      if (loggingBucket != null || logObjectPathPrefix != null) {
        logConfig = new Bucket.Logging();
        logConfig.setLogBucket(loggingBucket);
        logConfig.setLogObjectPrefix(logObjectPathPrefix);
      } else {
        logConfig = Data.nullOf(Bucket.Logging.class);
      }
      return logConfig;
    }

    static LogConfig fromProto(Bucket.Logging logConfig) {
      return newLogConfigBuilder()
          .setLogBucket(logConfig.getLogBucket())
          .setLogObjectPrefix(logConfig.getLogObjectPrefix())
          .buildLogConfig();
    }

    private LogConfig(LogConfigBuilder accessBuilder) {
      this.loggingBucket = accessBuilder.loggingBucket;
      this.logObjectPathPrefix = accessBuilder.logObjectPathPrefix;
    }

    public static class LogConfigBuilder {
      private String loggingBucket;
      private String logObjectPathPrefix;

      /** The destination bucket where the current bucket's logs should be placed. */
      public LogConfigBuilder setLogBucket(String loggingBucket) {
        this.loggingBucket = loggingBucket;
        return this;
      }

      /** A prefix for log object names. */
      public LogConfigBuilder setLogObjectPrefix(String logObjectPathPrefix) {
        this.logObjectPathPrefix = logObjectPathPrefix;
        return this;
      }

      /** Builds an {@code LogConfig} object */
      public LogConfig buildLogConfig() {
        return new LogConfig(this);
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

    private static final long CLASS_SERIAL_ID = -5739807320148748613L;
    private final AbstractLifecycleAction lifecycleOperation;
    private final LifecycleRuleCondition lifecycleCriteria;

    public LifecycleRuleDefinition(AbstractLifecycleAction lifecycleOperation, LifecycleRuleCondition lifecycleCriteria) {
      if (lifecycleCriteria.getIsLive() == null
          && lifecycleCriteria.getAge() == null
          && lifecycleCriteria.getCreatedBefore() == null
          && lifecycleCriteria.getMatchesStorageClass() == null
          && lifecycleCriteria.getNumberOfNewerVersions() == null) {
        throw new IllegalArgumentException(
            "You must specify at least one condition to use object lifecycle "
                + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
      }

      this.lifecycleOperation = lifecycleOperation;
      this.lifecycleCriteria = lifecycleCriteria;
    }

    public AbstractLifecycleAction getAction() {
      return lifecycleOperation;
    }

    public LifecycleRuleCondition getCondition() {
      return lifecycleCriteria;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("lifecycleAction", lifecycleOperation)
          .add("lifecycleCondition", lifecycleCriteria)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(lifecycleOperation, lifecycleCriteria);
    }

    @Override
    public boolean equals(Object candidateObj) {
      if (this == candidateObj) {
        return true;
      }
      if (candidateObj == null || getClass() != candidateObj.getClass()) {
        return false;
      }
      final LifecycleRuleDefinition otherConfig = (LifecycleRuleDefinition) candidateObj;
      return Objects.equals(toProto(), otherConfig.toProto());
    }

    Rule toProto() {
      Rule lifecycleRuleProto = new Rule();

      Rule.Action lifecycleOperation = new Rule.Action().setType(this.lifecycleOperation.getActionType());
      if (this.lifecycleOperation.getActionType().equals(SetStorageClassLifecycleOperation.TYPE)) {
        lifecycleOperation.setStorageClass(
            ((SetStorageClassLifecycleOperation) this.lifecycleOperation).getStorageClass().toString());
      }

      lifecycleRuleProto.setAction(lifecycleOperation);

      Rule.Condition lifecycleCriteria =
          new Rule.Condition()
              .setAge(this.lifecycleCriteria.getAge())
              .setCreatedBefore(
                  this.lifecycleCriteria.getCreatedBefore() == null
                      ? null
                      : new DateTime(true, this.lifecycleCriteria.getCreatedBefore().getValue(), 0))
              .setIsLive(this.lifecycleCriteria.getIsLive())
              .setNumNewerVersions(this.lifecycleCriteria.getNumberOfNewerVersions())
              .setMatchesStorageClass(
                  this.lifecycleCriteria.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          this.lifecycleCriteria.getMatchesStorageClass(),
                          Functions.toStringFunction()));

      lifecycleRuleProto.setCondition(lifecycleCriteria);

      return lifecycleRuleProto;
    }

    static LifecycleRuleDefinition fromProto(Rule lifecycleRuleProto) {
      AbstractLifecycleAction lifecycleOperation;

      Rule.Action operationAction = lifecycleRuleProto.getAction();

      switch (operationAction.getType()) {
        case RemoveLifecycleAction.TYPE:
          lifecycleOperation = AbstractLifecycleAction.createRemoveAction();
          break;
        case SetStorageClassLifecycleOperation.TYPE:
          lifecycleOperation =
              AbstractLifecycleAction.createSetStorageClassAction(
                  StorageClass.valueOf(operationAction.getStorageClass()));
          break;
        default:
          throw new UnsupportedOperationException(
              "The specified lifecycle action " + operationAction.getType() + " is not currently supported");
      }

      Rule.Condition lifecycleCriteria = lifecycleRuleProto.getCondition();

      LifecycleRuleCondition.LifecycleConditionBuilder lifecycleConditionBuilder =
          LifecycleRuleCondition.newLifecycleConditionBuilder()
              .setAge(lifecycleCriteria.getAge())
              .setCreatedBefore(lifecycleCriteria.getCreatedBefore())
              .setIsLive(lifecycleCriteria.getIsLive())
              .setNumberOfNewerVersions(lifecycleCriteria.getNumNewerVersions())
              .setMatchesStorageClass(
                  lifecycleCriteria.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          lifecycleCriteria.getMatchesStorageClass(),
                          new Function<String, StorageClass>() {
                            public StorageClass apply(String storageClass) {
                              return StorageClass.valueOf(storageClass);
                            }
                          }));

      return new LifecycleRuleDefinition(lifecycleOperation, lifecycleConditionBuilder.buildLifecycleRuleCondition());
    }

    /**
     * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
     *     Management</a>
     */
    public static class LifecycleRuleCondition implements Serializable {
      private static final long CLASS_SERIAL_ID = -6482314338394768785L;
      private final Integer minAge;
      private final DateTime createdPriorTo;
      private final Integer newerVersionsCount;
      private final Boolean liveFlag;
      private final List<StorageClass> matchingStorageClasses;

      private LifecycleRuleCondition(LifecycleConditionBuilder accessBuilder) {
        this.minAge = accessBuilder.minAge;
        this.createdPriorTo = accessBuilder.createdPriorTo;
        this.newerVersionsCount = accessBuilder.newerVersionsCount;
        this.liveFlag = accessBuilder.liveFlag;
        this.matchingStorageClasses = accessBuilder.matchingStorageClasses;
      }

      public LifecycleConditionBuilder toBuilder() {
        return newLifecycleConditionBuilder()
            .setAge(this.minAge)
            .setCreatedBefore(this.createdPriorTo)
            .setNumberOfNewerVersions(this.newerVersionsCount)
            .setIsLive(this.liveFlag)
            .setMatchesStorageClass(this.matchingStorageClasses);
      }

      public static LifecycleConditionBuilder newLifecycleConditionBuilder() {
        return new LifecycleConditionBuilder();
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("age", minAge)
            .add("createBefore", createdPriorTo)
            .add("numberofNewerVersions", newerVersionsCount)
            .add("isLive", liveFlag)
            .add("matchesStorageClass", matchingStorageClasses)
            .toString();
      }

      public Integer getAge() {
        return minAge;
      }

      public DateTime getCreatedBefore() {
        return createdPriorTo;
      }

      public Integer getNumberOfNewerVersions() {
        return newerVersionsCount;
      }

      public Boolean getIsLive() {
        return liveFlag;
      }

      public List<StorageClass> getMatchesStorageClass() {
        return matchingStorageClasses;
      }

      /** BucketInfoBuilder for {@code LifecycleRuleCondition}. */
      public static class LifecycleConditionBuilder {
        private Integer minAge;
        private DateTime createdPriorTo;
        private Integer newerVersionsCount;
        private Boolean liveFlag;
        private List<StorageClass> matchingStorageClasses;

        private LifecycleConditionBuilder() {}

        /**
         * Sets the age in days. This condition is satisfied when a Blob reaches the specified age
         * (in days). When you specify the Age condition, you are specifying a Time to Live (TTL)
         * for objects in a bucket with lifecycle management configured. The time when the Age
         * condition is considered to be satisfied is calculated by adding the specified value to
         * the object creation time.
         */
        public LifecycleConditionBuilder setAge(Integer minAge) {
          this.minAge = minAge;
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
        public LifecycleConditionBuilder setNumberOfNewerVersions(Integer newerVersionsCount) {
          this.newerVersionsCount = newerVersionsCount;
          return this;
        }

        /**
         * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
         * only live Blobs; if the value is false, it matches only archived objects. For the
         * purposes from this condition, Blobs in non-versioned buckets are considered live.
         */
        public LifecycleConditionBuilder setIsLive(Boolean isActiveFlag) {
          this.liveFlag = isActiveFlag;
          return this;
        }

        /**
         * Sets a listObjects from Storage Classes for a objects that satisfy the condition to execute the
         * Action. *
         */
        public LifecycleConditionBuilder setMatchesStorageClass(List<StorageClass> matchingStorageClasses) {
          this.matchingStorageClasses = matchingStorageClasses;
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
    public abstract static class AbstractLifecycleAction implements Serializable {
      private static final long CLASS_SERIAL_ID = 5801228724709173284L;

      public abstract String getActionType();

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this).add("actionType", getActionType()).toString();
      }

      /**
       * Creates a new {@code RemoveLifecycleAction}. Blobs that meet the Condition associated with
       * this action will be deleted.
       */
      public static RemoveLifecycleAction createRemoveAction() {
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
    }

    public static class RemoveLifecycleAction extends AbstractLifecycleAction {
      public static final String TYPE = "Delete";
      private static final long CLASS_SERIAL_ID = -2050986302222644873L;

      private RemoveLifecycleAction() {}

      @Override
      public String getActionType() {
        return TYPE;
      }
    }

    public static class SetStorageClassLifecycleOperation extends AbstractLifecycleAction {
      public static final String TYPE = "SetStorageClass";
      private static final long CLASS_SERIAL_ID = -62615467186000899L;

      private final StorageClass storageTier;

      private SetStorageClassLifecycleOperation(StorageClass storageTier) {
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

      public StorageClass getStorageClass() {
        return storageTier;
      }
    }
  }

  /**
   * Base class for bucket's deleteBucket rules. Allows to configure automatic deletion from blobs and blobs
   * versions.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with a {@code RemoveLifecycleAction} and a {@code
   *     LifecycleRuleCondition} which is equivalent to a subclass from DeleteRule instead.
   */
  @Deprecated
  public abstract static class DeleteRule implements Serializable {

    private static final long CLASS_SERIAL_ID = 3137971668395933033L;
    private static final String DELETE_RULE_SUPPORTED_ACTION = "Delete";
    private final VersionType versionCategory;

    public enum VersionType {
      AGE,
      CREATE_BEFORE,
      NUM_NEWER_VERSIONS,
      IS_LIVE,
      UNKNOWN
    }

    DeleteRule(VersionType versionCategory) {
      this.versionCategory = versionCategory;
    }

    public VersionType getType() {
      return versionCategory;
    }

    @Override
    public int hashCode() {
      return Objects.hash(versionCategory);
    }

    @Override
    public boolean equals(Object candidateObj) {
      if (this == candidateObj) {
        return true;
      }
      if (candidateObj == null || getClass() != candidateObj.getClass()) {
        return false;
      }
      final DeleteRule otherConfig = (DeleteRule) candidateObj;
      return Objects.equals(toProto(), otherConfig.toProto());
    }

    Rule toProto() {
      Rule lifecycleRuleProto = new Rule();
      lifecycleRuleProto.setAction(new Rule.Action().setType(DELETE_RULE_SUPPORTED_ACTION));
      Rule.Condition lifecycleCriteria = new Rule.Condition();
      fillCondition(lifecycleCriteria);
      lifecycleRuleProto.setCondition(lifecycleCriteria);
      return lifecycleRuleProto;
    }

    abstract void fillCondition(Rule.Condition lifecycleCriteria);

    static DeleteRule fromProto(Rule lifecycleRuleProto) {
      if (lifecycleRuleProto.getAction() != null && DELETE_RULE_SUPPORTED_ACTION.endsWith(lifecycleRuleProto.getAction().getType())) {
        Rule.Condition lifecycleCriteria = lifecycleRuleProto.getCondition();
        Integer minAge = lifecycleCriteria.getAge();
        if (minAge != null) {
          return new AgeDeleteRule(minAge);
        }
        DateTime dateTimeObj = lifecycleCriteria.getCreatedBefore();
        if (dateTimeObj != null) {
          return new CreatedBeforeDeleteRule(dateTimeObj.getValue());
        }
        Integer newerVersionsCount = lifecycleCriteria.getNumNewerVersions();
        if (newerVersionsCount != null) {
          return new NumNewerVersionsDeleteRule(newerVersionsCount);
        }
        Boolean liveFlag = lifecycleCriteria.getIsLive();
        if (liveFlag != null) {
          return new IsLiveDeleteRule(liveFlag);
        }
      }
      return new RawDeletionRule(lifecycleRuleProto);
    }
  }

  /**
   * Delete rule class that sets a Time To Live for blobs in the bucket.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with a {@code RemoveLifecycleAction} and use {@code
   *     LifecycleRuleCondition.BucketInfoBuilder.setAge} instead.
   *     <p>For example, {@code new RemoveLifecycleAction(1)} is equivalent to {@code new
   *     LifecycleRuleDefinition( AbstractLifecycleAction.createRemoveAction(),
   *     LifecycleRuleCondition.newUniformBucketLevelAccessBuilder().setAge(1).buildBucketIamConfiguration()))}
   */
  @Deprecated
  public static class AgeDeleteRule extends DeleteRule {

    private static final long CLASS_SERIAL_ID = 5697166940712116380L;
    private final int ttlDays;

    /**
     * Creates an {@code AgeDeleteRule} object.
     *
     * @param ttlDays blobs' Time To Live expressed in days. The time when the age condition is
     *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
     *     following blob's creation time in UTC.
     */
    public AgeDeleteRule(int ttlDays) {
      super(VersionType.AGE);
      this.ttlDays = ttlDays;
    }

    public int getDaysToLive() {
      return ttlDays;
    }

    @Override
    void fillCondition(Rule.Condition lifecycleCriteria) {
      lifecycleCriteria.setAge(ttlDays);
    }
  }

  static class RawDeletionRule extends DeleteRule {

    private static final long CLASS_SERIAL_ID = -7166938278642301933L;

    private transient Rule lifecycleRuleProto;

    RawDeletionRule(Rule lifecycleRuleProto) {
      super(VersionType.UNKNOWN);
      this.lifecycleRuleProto = lifecycleRuleProto;
    }

    @Override
    void fillCondition(Rule.Condition lifecycleCriteria) {
      throw new UnsupportedOperationException();
    }

    private void writeObjectData(ObjectOutputStream objectOutStream) throws IOException {
      objectOutStream.defaultWriteObject();
      objectOutStream.writeUTF(lifecycleRuleProto.toString());
    }

    private void readObjectFromStream(ObjectInputStream objectInStream) throws IOException, ClassNotFoundException {
      objectInStream.defaultReadObject();
      lifecycleRuleProto = new JacksonFactory().fromString(objectInStream.readUTF(), Rule.class);
    }

    @Override
    Rule toProto() {
      return lifecycleRuleProto;
    }
  }

  /**
   * Delete rule class for blobs in the bucket that have been created before a certain date.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with an action {@code RemoveLifecycleAction} and a
   *     condition {@code LifecycleRuleCondition.BucketInfoBuilder.setCreatedBefore} instead.
   */
  @Deprecated
  public static class CreatedBeforeDeleteRule extends DeleteRule {

    private static final long CLASS_SERIAL_ID = 881692650279195867L;
    private final long timeMillisValue;

    /**
     * Creates an {@code CreatedBeforeDeleteRule} object.
     *
     * @param timeMillisValue a date in UTC. Blobs that have been created before midnight from the provided
     *     date meet the deleteBucket condition
     */
    public CreatedBeforeDeleteRule(long timeMillisValue) {
      super(VersionType.CREATE_BEFORE);
      this.timeMillisValue = timeMillisValue;
    }

    public long getTimeMillis() {
      return timeMillisValue;
    }

    @Override
    void fillCondition(Rule.Condition lifecycleCriteria) {
      lifecycleCriteria.setCreatedBefore(new DateTime(true, timeMillisValue, 0));
    }
  }

  /**
   * Delete rule class for versioned blobs. Specifies when to deleteBucket a blob's version according to
   * the number from available newer versions for that blob.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with a {@code RemoveLifecycleAction} and a condition
   *     {@code LifecycleRuleCondition.BucketInfoBuilder.setNumberOfNewerVersions} instead.
   */
  @Deprecated
  public static class NumNewerVersionsDeleteRule extends DeleteRule {

    private static final long CLASS_SERIAL_ID = -1955554976528303894L;
    private final int newerVersionsCount;

    /**
     * Creates an {@code NumNewerVersionsDeleteRule} object.
     *
     * @param newerVersionsCount the number from newer versions. A blob's version meets the deleteBucket
     *     condition when {@code numNewerVersions} newer versions are available.
     */
    public NumNewerVersionsDeleteRule(int newerVersionsCount) {
      super(VersionType.NUM_NEWER_VERSIONS);
      this.newerVersionsCount = newerVersionsCount;
    }

    public int getNumNewerVersions() {
      return newerVersionsCount;
    }

    @Override
    void fillCondition(Rule.Condition lifecycleCriteria) {
      lifecycleCriteria.setNumNewerVersions(newerVersionsCount);
    }
  }

  /**
   * Delete rule class to distinguish between live and archived blobs.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code LifecycleRuleDefinition} with a {@code RemoveLifecycleAction} and a condition
   *     {@code LifecycleRuleCondition.BucketInfoBuilder.setIsLive} instead.
   */
  @Deprecated
  public static class IsLiveDeleteRule extends DeleteRule {

    private static final long CLASS_SERIAL_ID = -3502994563121313364L;
    private final boolean liveFlag;

    /**
     * Creates an {@code IsLiveDeleteRule} object.
     *
     * @param liveFlag if set to {@code true} live blobs meet the deleteBucket condition. If set to {@code
     *     false} deleteBucket condition is met by archived blobs.
     */
    public IsLiveDeleteRule(boolean liveFlag) {
      super(VersionType.IS_LIVE);
      this.liveFlag = liveFlag;
    }

    public boolean isLive() {
      return liveFlag;
    }

    @Override
    void fillCondition(Rule.Condition lifecycleCriteria) {
      lifecycleCriteria.setIsLive(liveFlag);
    }
  }

  /** BucketInfoBuilder for {@code BucketInfo}. */
  public abstract static class Builder {
    Builder() {}

    /** Sets the bucket's name. */
    public abstract Builder setName(String label);

    abstract Builder setGeneratedId(String generatedIdentifier);

    abstract Builder setOwner(Acl.Entity ownerEntityRef);

    abstract Builder setSelfLink(String resourceLink);

    /**
     * Sets whether a user accessing the bucket or an object it contains should assume the transit
     * costs related to the access.
     */
    public abstract Builder setRequesterPays(Boolean requesterPaysFlag);

    /**
     * Sets whether versioning should be enabled for this bucket. When set to true, versioning is
     * fully enabled.
     */
    public abstract Builder setVersioningEnabled(Boolean enabledFlag);

    /**
     * Sets the bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public abstract Builder setIndexPage(String indexDocument);

    /** Sets the custom object to return when a requested resource is not found. */
    public abstract Builder setNotFoundPage(String errorDocument);

    /**
     * Sets the bucket's lifecycle configuration as a number from deleteBucket rules.
     *
     * @deprecated Use {@code setLifecycleRules} instead, as in {@code
     *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRuleDefinition(
     *     AbstractLifecycleAction.createRemoveAction(), LifecycleRuleCondition.newUniformBucketLevelAccessBuilder().setAge(5).buildBucketIamConfiguration())));}
     */
    @Deprecated
    public abstract Builder setDeleteRules(Iterable<? extends DeleteRule> ruleSet);

    /**
     * Sets the bucket's lifecycle configuration as a number from lifecycle rules, consisting from an
     * action and a condition.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
     *     Management</a>
     */
    public abstract Builder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> ruleSet);

    /** Deletes the lifecycle rules from this bucket. */
    public abstract Builder deleteLifecycleRules();

    /**
     * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
     * determines the SLA and the cost from storage. A listObjects from supported values is available <a
     * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
     */
    public abstract Builder setStorageClass(StorageClass storageTier);

    /**
     * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
     * this region. A listObjects from supported values is available <a
     * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
     */
    public abstract Builder setLocation(String region);

    abstract Builder setEtag(String entityTag);

    abstract Builder setCreateTime(Long creationTimestamp);

    abstract Builder setMetageneration(Long metaVersion);

    abstract Builder setLocationType(String regionType);

    /**
     * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
     *     Sharing (CORS)</a>
     */
    public abstract Builder setCors(Iterable<Cors> corsRules);

    /**
     * Sets the bucket's access control configuration.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract Builder setAcl(Iterable<Acl> accessControls);

    /**
     * Sets the default access control configuration to apply to bucket's blobs when no other
     * configuration is specified.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract Builder setDefaultAcl(Iterable<Acl> accessControls);

    /** Sets the label from this bucket. */
    public abstract Builder setLabels(Map<String, String> metadataLabels);

    /** Sets the default Cloud KMS key name for this bucket. */
    public abstract Builder setDefaultKmsKeyName(String defaultKmsKeyReference);

    /** Sets the default event-based hold for this bucket. */
    @BetaApi
    public abstract Builder setDefaultEventBasedHold(Boolean eventBasedHoldDefault);

    @BetaApi
    abstract Builder setRetentionEffectiveTime(Long retentionStartTime);

    @BetaApi
    abstract Builder setRetentionPolicyIsLocked(Boolean retentionPolicyLocked);

    /**
     * If policy is not locked this value can be cleared, increased, and decreased. If policy is
     * locked the retention period can only be increased.
     */
    @BetaApi
    public abstract Builder setRetentionPeriod(Long retentionDuration);

    /**
     * Sets the BucketIamConfiguration to specify whether IAM access should be enabled.
     *
     * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">StorageBucket Policy
     *     Only</a>
     */
    @BetaApi
    public abstract Builder setIamConfiguration(BucketIamConfiguration iamConfig);

    public abstract Builder setLogging(LogConfig logConfig);

    /** Creates a {@code BucketInfo} object. */
    public abstract BucketInfo buildInstance();
  }

  static final class BucketBuilderImpl extends Builder {

    private String generatedIdentifier;
    private String label;
    private Acl.Entity ownerEntityRef;
    private String resourceLink;
    private Boolean requesterPaysFlag;
    private Boolean versioningActive;
    private String indexDocument;
    private String errorDocument;
    private List<DeleteRule> deletionRules;
    private List<LifecycleRuleDefinition> lifecycleDefinitions;
    private StorageClass storageTier;
    private String region;
    private String entityTag;
    private Long creationTimestamp;
    private Long metaVersion;
    private List<Cors> corsRules;
    private List<Acl> accessControls;
    private List<Acl> defaultAccessControls;
    private Map<String, String> metadataLabels;
    private String defaultKmsKeyReference;
    private Boolean eventBasedHoldDefault;
    private Long retentionStartTime;
    private Boolean retentionPolicyLocked;
    private Long retentionDuration;
    private BucketIamConfiguration iamConfig;
    private String regionType;
    private LogConfig logConfig;

    BucketBuilderImpl(String label) {
      this.label = label;
    }

    BucketBuilderImpl(BucketInfo infoDto) {
      generatedIdentifier = infoDto.generatedIdentifier;
      label = infoDto.label;
      entityTag = infoDto.entityTag;
      creationTimestamp = infoDto.creationTimestamp;
      metaVersion = infoDto.metaVersion;
      region = infoDto.region;
      storageTier = infoDto.storageTier;
      corsRules = infoDto.corsRules;
      accessControls = infoDto.accessControls;
      defaultAccessControls = infoDto.defaultAccessControls;
      ownerEntityRef = infoDto.ownerEntityRef;
      resourceLink = infoDto.resourceLink;
      versioningActive = infoDto.versioningActive;
      indexDocument = infoDto.indexDocument;
      errorDocument = infoDto.errorDocument;
      deletionRules = infoDto.deletionRules;
      lifecycleDefinitions = infoDto.lifecycleDefinitions;
      metadataLabels = infoDto.metadataLabels;
      requesterPaysFlag = infoDto.requesterPaysFlag;
      defaultKmsKeyReference = infoDto.defaultKmsKeyReference;
      eventBasedHoldDefault = infoDto.eventBasedHoldDefault;
      retentionStartTime = infoDto.retentionStartTime;
      retentionPolicyLocked = infoDto.retentionPolicyLocked;
      retentionDuration = infoDto.retentionDuration;
      iamConfig = infoDto.iamConfig;
      regionType = infoDto.regionType;
      logConfig = infoDto.logConfig;
    }

    @Override
    public Builder setName(String label) {
      this.label = checkNotNull(label);
      return this;
    }

    @Override
    Builder setGeneratedId(String generatedIdentifier) {
      this.generatedIdentifier = generatedIdentifier;
      return this;
    }

    @Override
    Builder setOwner(Acl.Entity ownerEntityRef) {
      this.ownerEntityRef = ownerEntityRef;
      return this;
    }

    @Override
    Builder setSelfLink(String resourceLink) {
      this.resourceLink = resourceLink;
      return this;
    }

    @Override
    public Builder setVersioningEnabled(Boolean enabledFlag) {
      this.versioningActive = firstNonNull(enabledFlag, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public Builder setRequesterPays(Boolean enabledFlag) {
      this.requesterPaysFlag = firstNonNull(enabledFlag, Data.<Boolean>nullOf(Boolean.class));
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
    public Builder setDeleteRules(Iterable<? extends DeleteRule> ruleSet) {
      this.deletionRules = ruleSet != null ? ImmutableList.copyOf(ruleSet) : null;
      return this;
    }

    @Override
    public Builder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> ruleSet) {
      this.lifecycleDefinitions =
          ruleSet != null ? ImmutableList.copyOf(ruleSet) : ImmutableList.<LifecycleRuleDefinition>of();
      return this;
    }

    @Override
    public Builder deleteLifecycleRules() {
      setDeleteRules(null);
      setLifecycleRules(null);
      return this;
    }

    @Override
    public Builder setStorageClass(StorageClass storageTier) {
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
    Builder setCreateTime(Long creationTimestamp) {
      this.creationTimestamp = creationTimestamp;
      return this;
    }

    @Override
    Builder setMetageneration(Long metaVersion) {
      this.metaVersion = metaVersion;
      return this;
    }

    @Override
    public Builder setCors(Iterable<Cors> corsRules) {
      this.corsRules = corsRules != null ? ImmutableList.copyOf(corsRules) : ImmutableList.<Cors>of();
      return this;
    }

    @Override
    public Builder setAcl(Iterable<Acl> accessControls) {
      this.accessControls = accessControls != null ? ImmutableList.copyOf(accessControls) : null;
      return this;
    }

    @Override
    public Builder setDefaultAcl(Iterable<Acl> accessControls) {
      this.defaultAccessControls = accessControls != null ? ImmutableList.copyOf(accessControls) : null;
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
    public Builder setDefaultKmsKeyName(String defaultKmsKeyReference) {
      this.defaultKmsKeyReference =
          defaultKmsKeyReference != null ? defaultKmsKeyReference : Data.<String>nullOf(String.class);
      return this;
    }

    @Override
    public Builder setDefaultEventBasedHold(Boolean eventBasedHoldDefault) {
      this.eventBasedHoldDefault =
          firstNonNull(eventBasedHoldDefault, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    Builder setRetentionEffectiveTime(Long retentionStartTime) {
      this.retentionStartTime =
          firstNonNull(retentionStartTime, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    Builder setRetentionPolicyIsLocked(Boolean retentionPolicyLocked) {
      this.retentionPolicyLocked =
          firstNonNull(retentionPolicyLocked, Data.<Boolean>nullOf(Boolean.class));
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
    public Builder setLogging(LogConfig logConfig) {
      this.logConfig = logConfig != null ? logConfig : LogConfig.newLogConfigBuilder().buildLogConfig();
      return this;
    }

    @Override
    Builder setLocationType(String regionType) {
      this.regionType = regionType;
      return this;
    }

    @Override
    public BucketInfo buildInstance() {
      checkNotNull(label);
      return new BucketInfo(this);
    }
  }

  BucketInfo(BucketBuilderImpl accessBuilder) {
    generatedIdentifier = accessBuilder.generatedIdentifier;
    label = accessBuilder.label;
    entityTag = accessBuilder.entityTag;
    creationTimestamp = accessBuilder.creationTimestamp;
    metaVersion = accessBuilder.metaVersion;
    region = accessBuilder.region;
    storageTier = accessBuilder.storageTier;
    corsRules = accessBuilder.corsRules;
    accessControls = accessBuilder.accessControls;
    defaultAccessControls = accessBuilder.defaultAccessControls;
    ownerEntityRef = accessBuilder.ownerEntityRef;
    resourceLink = accessBuilder.resourceLink;
    versioningActive = accessBuilder.versioningActive;
    indexDocument = accessBuilder.indexDocument;
    errorDocument = accessBuilder.errorDocument;
    deletionRules = accessBuilder.deletionRules;
    lifecycleDefinitions = accessBuilder.lifecycleDefinitions;
    metadataLabels = accessBuilder.metadataLabels;
    requesterPaysFlag = accessBuilder.requesterPaysFlag;
    defaultKmsKeyReference = accessBuilder.defaultKmsKeyReference;
    eventBasedHoldDefault = accessBuilder.eventBasedHoldDefault;
    retentionStartTime = accessBuilder.retentionStartTime;
    retentionPolicyLocked = accessBuilder.retentionPolicyLocked;
    retentionDuration = accessBuilder.retentionDuration;
    iamConfig = accessBuilder.iamConfig;
    regionType = accessBuilder.regionType;
    logConfig = accessBuilder.logConfig;
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
    return ownerEntityRef;
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
   * request for example {@link Storage#update(BucketInfo, Storage.BucketTargetOption...)} in which
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
    return Data.isNull(requesterPaysFlag) ? null : requesterPaysFlag;
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
   * Returns bucket's lifecycle configuration as a number from deleteBucket rules.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
   */
  @Deprecated
  public List<? extends DeleteRule> getDeleteRules() {
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
    return creationTimestamp;
  }

  /** Returns the metadata generation from this bucket. */
  public Long getMetageneration() {
    return metaVersion;
  }

  /**
   * Returns the bucket's location. Data for blobs in the bucket resides in physical storage within
   * this region.
   *
   * @see <a href="https://cloud.google.com/storage/docs/bucket-locations">StorageBucket Locations</a>
   */
  public String getLocation() {
    return region;
  }

  /**
   * Returns the bucket's locationType.
   *
   * @see <a href="https://cloud.google.com/storage/docs/bucket-locations">StorageBucket LocationType</a>
   */
  public String getLocationType() {
    return regionType;
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
    return corsRules;
  }

  /**
   * Returns the bucket's access control configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
   *     About Access Control Lists</a>
   */
  public List<Acl> getAcl() {
    return accessControls;
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
    return metadataLabels;
  }

  /** Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket. */
  public String getDefaultKmsKeyName() {
    return defaultKmsKeyReference;
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
    return Data.isNull(eventBasedHoldDefault) ? null : eventBasedHoldDefault;
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
    return Data.isNull(retentionPolicyLocked) ? null : retentionPolicyLocked;
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

  /** Returns the LogConfig */
  public LogConfig getLogging() {
    return logConfig;
  }

  /** Returns a builder for the current bucket. */
  public Builder toBucketBuilder() {
    return new BucketBuilderImpl(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(label);
  }

  @Override
  public boolean equals(Object candidateObj) {
    return candidateObj == this
        || candidateObj != null
            && candidateObj.getClass().equals(BucketInfo.class)
            && Objects.equals(toBucketPb(), ((BucketInfo) candidateObj).toBucketPb());
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
    if (metaVersion != null) {
      bucketProto.setMetageneration(metaVersion);
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
    if (accessControls != null) {
      bucketProto.setAcl(
          transform(
                  accessControls,
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
    if (ownerEntityRef != null) {
      bucketProto.setOwner(new Owner().setEntity(ownerEntityRef.toPb()));
    }
    bucketProto.setSelfLink(resourceLink);
    if (versioningActive != null) {
      bucketProto.setVersioning(new Versioning().setEnabled(versioningActive));
    }
    if (requesterPaysFlag != null) {
      Bucket.Billing billingConfig = new Bucket.Billing();
      billingConfig.setRequesterPays(requesterPaysFlag);
      bucketProto.setBilling(billingConfig);
    }
    if (indexDocument != null || errorDocument != null) {
      Website websiteConfig = new Website();
      websiteConfig.setMainPageSuffix(indexDocument);
      websiteConfig.setNotFoundPage(errorDocument);
      bucketProto.setWebsite(websiteConfig);
    }
    Set<Rule> ruleSet = new HashSet<>();
    if (deletionRules != null) {
      ruleSet.addAll(
          transform(
                  deletionRules,
              new Function<DeleteRule, Rule>() {
                @Override
                public Rule apply(DeleteRule deleteRule) {
                  return deleteRule.toProto();
                }
              }));
    }
    if (lifecycleDefinitions != null) {
      ruleSet.addAll(
          transform(
                  lifecycleDefinitions,
              new Function<LifecycleRuleDefinition, Rule>() {
                @Override
                public Rule apply(LifecycleRuleDefinition lifecycleRule) {
                  return lifecycleRule.toProto();
                }
              }));
    }

    if (ruleSet != null) {
      Lifecycle lifecycleProto = new Lifecycle();
      lifecycleProto.setRule(ImmutableList.copyOf(ruleSet));
      bucketProto.setLifecycle(lifecycleProto);
    }

    if (metadataLabels != null) {
      bucketProto.setLabels(metadataLabels);
    }
    if (defaultKmsKeyReference != null) {
      bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(defaultKmsKeyReference));
    }
    if (eventBasedHoldDefault != null) {
      bucketProto.setDefaultEventBasedHold(eventBasedHoldDefault);
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
        if (retentionPolicyLocked != null) {
          retentionProto.setIsLocked(retentionPolicyLocked);
        }
        bucketProto.setRetentionPolicy(retentionProto);
      }
    }
    if (iamConfig != null) {
      bucketProto.setIamConfiguration(iamConfig.toProto());
    }
    if (logConfig != null) {
      bucketProto.setLogging(logConfig.toProto());
    }
    return bucketProto;
  }

  /** Creates a {@code BucketInfo} object for the provided bucket name. */
  public static BucketInfo from(String label) {
    return newBucketBuilder(label).buildInstance();
  }

  /** Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name. */
  public static Builder newBucketBuilder(String label) {
    return new BucketBuilderImpl(label);
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
    if (bucketProto.getLocation() != null) {
      accessBuilder.setLocation(bucketProto.getLocation());
    }
    if (bucketProto.getStorageClass() != null) {
      accessBuilder.setStorageClass(StorageClass.valueOf(bucketProto.getStorageClass()));
    }
    if (bucketProto.getCors() != null) {
      accessBuilder.setCors(transform(bucketProto.getCors(), Cors.FROM_PB_FUNCTION));
    }
    if (bucketProto.getAcl() != null) {
      accessBuilder.setAcl(
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
      accessBuilder.setDefaultAcl(
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
      accessBuilder.setOwner(Entity.fromPb(bucketProto.getOwner().getEntity()));
    }
    if (bucketProto.getVersioning() != null) {
      accessBuilder.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
    }
    Website websiteConfig = bucketProto.getWebsite();
    if (websiteConfig != null) {
      accessBuilder.setIndexPage(websiteConfig.getMainPageSuffix());
      accessBuilder.setNotFoundPage(websiteConfig.getNotFoundPage());
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
              new Function<Rule, DeleteRule>() {
                @Override
                public DeleteRule apply(Rule rule) {
                  return DeleteRule.fromProto(rule);
                }
              }));
    }
    if (bucketProto.getLabels() != null) {
      accessBuilder.setLabels(bucketProto.getLabels());
    }
    Bucket.Billing billingConfig = bucketProto.getBilling();
    if (billingConfig != null) {
      accessBuilder.setRequesterPays(billingConfig.getRequesterPays());
    }
    Encryption encryptionProto = bucketProto.getEncryption();
    if (encryptionProto != null
        && encryptionProto.getDefaultKmsKeyName() != null
        && !encryptionProto.getDefaultKmsKeyName().isEmpty()) {
      accessBuilder.setDefaultKmsKeyName(encryptionProto.getDefaultKmsKeyName());
    }
    if (bucketProto.getDefaultEventBasedHold() != null) {
      accessBuilder.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
    }
    Bucket.RetentionPolicy retentionProto = bucketProto.getRetentionPolicy();
    if (retentionProto != null) {
      if (retentionProto.getEffectiveTime() != null) {
        accessBuilder.setRetentionEffectiveTime(retentionProto.getEffectiveTime().getValue());
      }
      if (retentionProto.getIsLocked() != null) {
        accessBuilder.setRetentionPolicyIsLocked(retentionProto.getIsLocked());
      }
      if (retentionProto.getRetentionPeriod() != null) {
        accessBuilder.setRetentionPeriod(retentionProto.getRetentionPeriod());
      }
    }
    Bucket.IamConfiguration iamConfig = bucketProto.getIamConfiguration();

    if (bucketProto.getLocationType() != null) {
      accessBuilder.setLocationType(bucketProto.getLocationType());
    }

    if (iamConfig != null) {
      accessBuilder.setIamConfiguration(BucketIamConfiguration.fromProto(iamConfig));
    }
    Bucket.Logging logConfig = bucketProto.getLogging();
    if (logConfig != null) {
      accessBuilder.setLogging(LogConfig.fromProto(logConfig));
    }
    return accessBuilder.buildInstance();
  }
}
