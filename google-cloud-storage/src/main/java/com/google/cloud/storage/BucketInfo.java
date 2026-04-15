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

  static final Function<com.google.api.services.storage.model.Bucket, BucketInfo> BUCKET_TO_INFO_FUNCTION =
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
          return bucketInfo.toBucketPb();
        }
      };
  private static final long CLASS_VERSION_ID = -4712013629621638459L;
  private final String generatedIdentifier;
  private final String identifier;
  private final Acl.Entity principal;
  private final String resourceLink;
  private final Boolean payerResponsible;
  private final Boolean versioningActive;
  private final String indexDocument;
  private final String errorDocument;
  private final List<DeleteRule> deletionPolicies;
  private final List<ObjectLifecycleRule> lifecyclePolicies;
  private final String entityTag;
  private final Long creationTimestamp;
  private final Long metadataVersion;
  private final List<Cors> corsRules;
  private final List<Acl> accessControls;
  private final List<Acl> defaultAccessControls;
  private final String region;
  private final StorageClass storageTier;
  private final Map<String, String> tagMap;
  private final String defaultKmsKeyRef;
  private final Boolean eventHoldDefault;
  private final Long retentionStartTime;
  private final Boolean retentionPolicyLocked;
  private final Long retentionDuration;
  private final IamSettings iamSettings;
  private final String regionType;
  private final BucketLogging accessLogging;

  /**
   * The Bucket's IAM Configuration.
   *
   * @see <a href="https://cloud.google.com/storage/docs/uniform-bucket-level-access">uniform
   *     bucket-level access</a>
   */
  public static class IamSettings implements Serializable {
    private static final long CLASS_VERSION_ID = -8671736104909424616L;

    private Boolean uniformAccessEnabledFlag;
    private Long uniformAccessLockTimestamp;

    @Override
    public boolean equals(Object otherObj) {
      if (this == otherObj) return true;
      if (otherObj == null || getClass() != otherObj.getClass()) {
        return false;
      }
      IamSettings otherSettings = (IamSettings) otherObj;
      return Objects.equals(toProto(), otherSettings.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(uniformAccessEnabledFlag, uniformAccessLockTimestamp);
    }

    private IamSettings(UniformBucketLevelAccessBuilder instanceCreator) {
      this.uniformAccessEnabledFlag = instanceCreator.uniformAccessEnabledFlag;
      this.uniformAccessLockTimestamp = instanceCreator.uniformAccessLockTimestamp;
    }

    public static UniformBucketLevelAccessBuilder createBuilder() {
      return new UniformBucketLevelAccessBuilder();
    }

    public UniformBucketLevelAccessBuilder toUniformBucketLevelAccessBuilder() {
      UniformBucketLevelAccessBuilder instanceCreator = new UniformBucketLevelAccessBuilder();
      instanceCreator.uniformAccessEnabledFlag = uniformAccessEnabledFlag;
      instanceCreator.uniformAccessLockTimestamp = uniformAccessLockTimestamp;
      return instanceCreator;
    }

    /** Deprecated in favor from isUniformBucketLevelAccessEnabled(). */
    @Deprecated
    public Boolean isBucketPolicyOnlyEnabled() {
      return uniformAccessEnabledFlag;
    }

    /** Deprecated in favor from uniformBucketLevelAccessLockedTime(). */
    @Deprecated
    public Long getBucketPolicyOnlyLockedTime() {
      return uniformAccessLockTimestamp;
    }

    public Boolean isUniformBucketLevelAccessEnabled() {
      return uniformAccessEnabledFlag;
    }

    public Long getUniformBucketLevelAccessLockedTime() {
      return uniformAccessLockTimestamp;
    }

    Bucket.IamConfiguration toProto() {
      Bucket.IamConfiguration iamSettings = new Bucket.IamConfiguration();

      Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessProto =
          new Bucket.IamConfiguration.UniformBucketLevelAccess();
      uniformAccessProto.setEnabled(uniformAccessEnabledFlag);
      uniformAccessProto.setLockedTime(
          uniformAccessLockTimestamp == null
              ? null
              : new DateTime(uniformAccessLockTimestamp));

      iamSettings.setUniformBucketLevelAccess(uniformAccessProto);

      return iamSettings;
    }

    static IamSettings fromProto(Bucket.IamConfiguration iamSettings) {
      Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessProto =
          iamSettings.getUniformBucketLevelAccess();
      DateTime lockTimestamp = uniformAccessProto.getLockedTime();

      return createBuilder()
          .setIsUniformBucketLevelAccessEnabled(uniformAccessProto.getEnabled())
          .setUniformBucketLevelAccessLockedTime(lockTimestamp == null ? null : lockTimestamp.getValue())
          .buildIamSettings();
    }

    /** UniformBucketLevelAccessBuilder for {@code IamSettings} */
    public static class UniformBucketLevelAccessBuilder {
      private Boolean uniformAccessEnabledFlag;
      private Long uniformAccessLockTimestamp;

      /** Deprecated in favor from setIsUniformBucketLevelAccessEnabled(). */
      @Deprecated
      public BucketInfo.IamSettings.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean policyOnlyEnabledFlag) {
        this.uniformAccessEnabledFlag = policyOnlyEnabledFlag;
        return this;
      }

      /** Deprecated in favor from setUniformBucketLevelAccessLockedTime(). */
      @Deprecated
      BucketInfo.IamSettings.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long policyOnlyLockTimestamp) {
        this.uniformAccessLockTimestamp = policyOnlyLockTimestamp;
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
          Boolean uniformAccessEnabledFlag) {
        this.uniformAccessEnabledFlag = uniformAccessEnabledFlag;
        return this;
      }

      /**
       * Sets the deadline for switching {@code uniformBucketLevelAccess.enabled} back to false.
       * After this time passes, calls to do so will fail. This is package-private, since in general
       * this field should never be set by a user--it's automatically set by the backend when {@code
       * enabled} is set to true.
       */
      UniformBucketLevelAccessBuilder setUniformBucketLevelAccessLockedTime(Long uniformAccessLockTimestamp) {
        this.uniformAccessLockTimestamp = uniformAccessLockTimestamp;
        return this;
      }

      /** Builds an {@code IamSettings} object */
      public IamSettings buildIamSettings() {
        return new IamSettings(this);
      }
    }
  }

  /**
   * The bucket's logging configuration, which defines the destination bucket and optional name
   * prefix for the current bucket's logs.
   */
  public static class BucketLogging implements Serializable {

    private static final long CLASS_VERSION_ID = -708892101216778492L;
    private String loggingBucket;
    private String logObjectPath;

    @Override
    public boolean equals(Object otherObj) {
      if (this == otherObj) return true;
      if (otherObj == null || getClass() != otherObj.getClass()) {
        return false;
      }
      BucketLogging otherSettings = (BucketLogging) otherObj;
      return Objects.equals(toProto(), otherSettings.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(loggingBucket, logObjectPath);
    }

    public static LogConfigBuilder newLogConfigBuilder() {
      return new LogConfigBuilder();
    }

    public LogConfigBuilder toBuilder() {
      LogConfigBuilder instanceCreator = new LogConfigBuilder();
      instanceCreator.loggingBucket = loggingBucket;
      instanceCreator.logObjectPath = logObjectPath;
      return instanceCreator;
    }

    public String getLogBucket() {
      return loggingBucket;
    }

    public String getLogObjectPrefix() {
      return logObjectPath;
    }

    Bucket.Logging toProto() {
      Bucket.Logging accessLogging;
      if (loggingBucket != null || logObjectPath != null) {
        accessLogging = new Bucket.Logging();
        accessLogging.setLogBucket(loggingBucket);
        accessLogging.setLogObjectPrefix(logObjectPath);
      } else {
        accessLogging = Data.nullOf(Bucket.Logging.class);
      }
      return accessLogging;
    }

    static BucketLogging fromProto(Bucket.Logging accessLogging) {
      return newLogConfigBuilder()
          .setLogBucket(accessLogging.getLogBucket())
          .setLogObjectPrefix(accessLogging.getLogObjectPrefix())
          .buildBucketLogging();
    }

    private BucketLogging(LogConfigBuilder instanceCreator) {
      this.loggingBucket = instanceCreator.loggingBucket;
      this.logObjectPath = instanceCreator.logObjectPath;
    }

    public static class LogConfigBuilder {
      private String loggingBucket;
      private String logObjectPath;

      /** The destination bucket where the current bucket's logs should be placed. */
      public LogConfigBuilder setLogBucket(String loggingBucket) {
        this.loggingBucket = loggingBucket;
        return this;
      }

      /** A prefix for log object names. */
      public LogConfigBuilder setLogObjectPrefix(String logObjectPath) {
        this.logObjectPath = logObjectPath;
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
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle#actions">Object Lifecycle
   *     Management</a>
   */
  public static class ObjectLifecycleRule implements Serializable {

    private static final long CLASS_VERSION_ID = -5739807320148748613L;
    private final LifecycleOperation lifecycleOperation;
    private final LifecycleRuleCondition lifecycleCriteria;

    public ObjectLifecycleRule(LifecycleOperation operation, LifecycleRuleCondition criteria) {
      if (criteria.getIsLive() == null
          && criteria.getAge() == null
          && criteria.getCreatedBefore() == null
          && criteria.getMatchesStorageClass() == null
          && criteria.getNumberOfNewerVersions() == null) {
        throw new IllegalArgumentException(
            "You must specify at least one condition to use object lifecycle "
                + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
      }

      this.lifecycleOperation = operation;
      this.lifecycleCriteria = criteria;
    }

    public LifecycleOperation getAction() {
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
      final ObjectLifecycleRule otherSettings = (ObjectLifecycleRule) candidateObj;
      return Objects.equals(toProto(), otherSettings.toProto());
    }

    Rule toProto() {
      Rule protoRule = new Rule();

      Rule.Action operation = new Rule.Action().setType(lifecycleOperation.getActionType());
      if (lifecycleOperation.getActionType().equals(UpdateStorageClassLifecycleAction.TYPE)) {
        operation.setStorageClass(
            ((UpdateStorageClassLifecycleAction) lifecycleOperation).getStorageClass().toString());
      }

      protoRule.setAction(operation);

      Rule.Condition criteria =
          new Rule.Condition()
              .setAge(lifecycleCriteria.getAge())
              .setCreatedBefore(
                  lifecycleCriteria.getCreatedBefore() == null
                      ? null
                      : new DateTime(true, lifecycleCriteria.getCreatedBefore().getValue(), 0))
              .setIsLive(lifecycleCriteria.getIsLive())
              .setNumNewerVersions(lifecycleCriteria.getNumberOfNewerVersions())
              .setMatchesStorageClass(
                  lifecycleCriteria.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          lifecycleCriteria.getMatchesStorageClass(),
                          Functions.toStringFunction()));

      protoRule.setCondition(criteria);

      return protoRule;
    }

    static ObjectLifecycleRule fromProto(Rule protoRule) {
      LifecycleOperation lifecycleOperation;

      Rule.Action operation = protoRule.getAction();

      switch (operation.getType()) {
        case RemoveLifecycleAction.TYPE:
          lifecycleOperation = LifecycleOperation.createRemoveLifecycleAction();
          break;
        case UpdateStorageClassLifecycleAction.TYPE:
          lifecycleOperation =
              LifecycleOperation.createUpdateStorageClassAction(
                  StorageClass.valueOf(operation.getStorageClass()));
          break;
        default:
          throw new UnsupportedOperationException(
              "The specified lifecycle action " + operation.getType() + " is not currently supported");
      }

      Rule.Condition criteria = protoRule.getCondition();

      LifecycleRuleCondition.LifecycleConditionBuilder lifecycleConditionCreator =
          LifecycleRuleCondition.newLifecycleConditionBuilder()
              .setAge(criteria.getAge())
              .setCreatedBefore(criteria.getCreatedBefore())
              .setIsLive(criteria.getIsLive())
              .setNumberOfNewerVersions(criteria.getNumNewerVersions())
              .setMatchesStorageClass(
                  criteria.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          criteria.getMatchesStorageClass(),
                          new Function<String, StorageClass>() {
                            public StorageClass apply(String storageClass) {
                              return StorageClass.valueOf(storageClass);
                            }
                          }));

      return new ObjectLifecycleRule(lifecycleOperation, lifecycleConditionCreator.buildLifecycleRuleCondition());
    }

    /**
     * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
     *     Management</a>
     */
    public static class LifecycleRuleCondition implements Serializable {
      private static final long CLASS_VERSION_ID = -6482314338394768785L;
      private final Integer ageDays;
      private final DateTime createdPriorTo;
      private final Integer newerVersionsCount;
      private final Boolean activeFlag;
      private final List<StorageClass> matchingStorageTiers;

      private LifecycleRuleCondition(LifecycleConditionBuilder instanceCreator) {
        this.ageDays = instanceCreator.ageDays;
        this.createdPriorTo = instanceCreator.createdPriorTo;
        this.newerVersionsCount = instanceCreator.newerVersionsCount;
        this.activeFlag = instanceCreator.activeFlag;
        this.matchingStorageTiers = instanceCreator.matchingStorageTiers;
      }

      public LifecycleConditionBuilder toBuilder() {
        return newLifecycleConditionBuilder()
            .setAge(this.ageDays)
            .setCreatedBefore(this.createdPriorTo)
            .setNumberOfNewerVersions(this.newerVersionsCount)
            .setIsLive(this.activeFlag)
            .setMatchesStorageClass(this.matchingStorageTiers);
      }

      public static LifecycleConditionBuilder newLifecycleConditionBuilder() {
        return new LifecycleConditionBuilder();
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("age", ageDays)
            .add("createBefore", createdPriorTo)
            .add("numberofNewerVersions", newerVersionsCount)
            .add("isLive", activeFlag)
            .add("matchesStorageClass", matchingStorageTiers)
            .toString();
      }

      public Integer getAge() {
        return ageDays;
      }

      public DateTime getCreatedBefore() {
        return createdPriorTo;
      }

      public Integer getNumberOfNewerVersions() {
        return newerVersionsCount;
      }

      public Boolean getIsLive() {
        return activeFlag;
      }

      public List<StorageClass> getMatchesStorageClass() {
        return matchingStorageTiers;
      }

      /** UniformBucketLevelAccessBuilder for {@code LifecycleRuleCondition}. */
      public static class LifecycleConditionBuilder {
        private Integer ageDays;
        private DateTime createdPriorTo;
        private Integer newerVersionsCount;
        private Boolean activeFlag;
        private List<StorageClass> matchingStorageTiers;

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
        public LifecycleConditionBuilder setNumberOfNewerVersions(Integer newerVersionsCount) {
          this.newerVersionsCount = newerVersionsCount;
          return this;
        }

        /**
         * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
         * only live Blobs; if the value is false, it matches only archived objects. For the
         * purposes from this condition, Blobs in non-versioned buckets are considered live.
         */
        public LifecycleConditionBuilder setIsLive(Boolean activeFlag) {
          this.activeFlag = activeFlag;
          return this;
        }

        /**
         * Sets a list from Storage Classes for a objects that satisfy the condition to execute the
         * Action. *
         */
        public LifecycleConditionBuilder setMatchesStorageClass(List<StorageClass> matchingStorageTiers) {
          this.matchingStorageTiers = matchingStorageTiers;
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
    public abstract static class LifecycleOperation implements Serializable {
      private static final long CLASS_VERSION_ID = 5801228724709173284L;

      public abstract String getActionType();

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this).add("actionType", getActionType()).toString();
      }

      /**
       * Creates a new {@code RemoveLifecycleAction}. Blobs that meet the Condition associated with
       * this action will be deleted.
       */
      public static RemoveLifecycleAction createRemoveLifecycleAction() {
        return new RemoveLifecycleAction();
      }

      /**
       * Creates a new {@code UpdateStorageClassLifecycleAction}. A Blob's storage class that meets the
       * action's conditions will be changed to the specified storage class.
       *
       * @param storageTier The new storage class to use when conditions are met for this action.
       */
      public static UpdateStorageClassLifecycleAction createUpdateStorageClassAction(
          StorageClass storageTier) {
        return new UpdateStorageClassLifecycleAction(storageTier);
      }
    }

    public static class RemoveLifecycleAction extends LifecycleOperation {
      public static final String TYPE = "Delete";
      private static final long CLASS_VERSION_ID = -2050986302222644873L;

      private RemoveLifecycleAction() {}

      @Override
      public String getActionType() {
        return TYPE;
      }
    }

    public static class UpdateStorageClassLifecycleAction extends LifecycleOperation {
      public static final String TYPE = "SetStorageClass";
      private static final long CLASS_VERSION_ID = -62615467186000899L;

      private final StorageClass storageTier;

      private UpdateStorageClassLifecycleAction(StorageClass storageTier) {
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
   * Base class for bucket's delete rules. Allows to configure automatic deletion from blobs and blobs
   * versions.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code ObjectLifecycleRule} with a {@code RemoveLifecycleAction} and a {@code
   *     LifecycleRuleCondition} which is equivalent to a subclass from DeleteRule instead.
   */
  @Deprecated
  public abstract static class DeleteRule implements Serializable {

    private static final long CLASS_VERSION_ID = 3137971668395933033L;
    private static final String SUPPORTED_OPERATION = "Delete";
    private final FilterType filterKind;

    public enum FilterType {
      AGE,
      CREATE_BEFORE,
      NUM_NEWER_VERSIONS,
      IS_LIVE,
      UNKNOWN
    }

    DeleteRule(FilterType filterKind) {
      this.filterKind = filterKind;
    }

    public FilterType getType() {
      return filterKind;
    }

    @Override
    public int hashCode() {
      return Objects.hash(filterKind);
    }

    @Override
    public boolean equals(Object candidateObj) {
      if (this == candidateObj) {
        return true;
      }
      if (candidateObj == null || getClass() != candidateObj.getClass()) {
        return false;
      }
      final DeleteRule otherSettings = (DeleteRule) candidateObj;
      return Objects.equals(toProto(), otherSettings.toProto());
    }

    Rule toProto() {
      Rule protoRule = new Rule();
      protoRule.setAction(new Rule.Action().setType(SUPPORTED_OPERATION));
      Rule.Condition criteria = new Rule.Condition();
      buildCondition(criteria);
      protoRule.setCondition(criteria);
      return protoRule;
    }

    abstract void buildCondition(Rule.Condition criteria);

    static DeleteRule fromProto(Rule protoRule) {
      if (protoRule.getAction() != null && SUPPORTED_OPERATION.endsWith(protoRule.getAction().getType())) {
        Rule.Condition criteria = protoRule.getCondition();
        Integer ageDays = criteria.getAge();
        if (ageDays != null) {
          return new AgeDeleteRule(ageDays);
        }
        DateTime dateTimeValue = criteria.getCreatedBefore();
        if (dateTimeValue != null) {
          return new CreatedBeforeDeleteRule(dateTimeValue.getValue());
        }
        Integer newerVersionsCount = criteria.getNumNewerVersions();
        if (newerVersionsCount != null) {
          return new NumNewerVersionsDeleteRule(newerVersionsCount);
        }
        Boolean activeFlag = criteria.getIsLive();
        if (activeFlag != null) {
          return new IsLiveDeleteRule(activeFlag);
        }
      }
      return new RawDeletionRule(protoRule);
    }
  }

  /**
   * Delete rule class that sets a Time To Live for blobs in the bucket.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code ObjectLifecycleRule} with a {@code RemoveLifecycleAction} and use {@code
   *     LifecycleRuleCondition.UniformBucketLevelAccessBuilder.setAge} instead.
   *     <p>For example, {@code new RemoveLifecycleAction(1)} is equivalent to {@code new
   *     ObjectLifecycleRule( LifecycleOperation.createRemoveLifecycleAction(),
   *     LifecycleRuleCondition.createBuilder().setAge(1).buildIamSettings()))}
   */
  @Deprecated
  public static class AgeDeleteRule extends DeleteRule {

    private static final long CLASS_VERSION_ID = 5697166940712116380L;
    private final int daysUntilDeletion;

    /**
     * Creates an {@code AgeDeleteRule} object.
     *
     * @param daysUntilDeletion blobs' Time To Live expressed in days. The time when the age condition is
     *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
     *     following blob's creation time in UTC.
     */
    public AgeDeleteRule(int daysUntilDeletion) {
      super(FilterType.AGE);
      this.daysUntilDeletion = daysUntilDeletion;
    }

    public int getDaysToLive() {
      return daysUntilDeletion;
    }

    @Override
    void buildCondition(Rule.Condition criteria) {
      criteria.setAge(daysUntilDeletion);
    }
  }

  static class RawDeletionRule extends DeleteRule {

    private static final long CLASS_VERSION_ID = -7166938278642301933L;

    private transient Rule protoRule;

    RawDeletionRule(Rule protoRule) {
      super(FilterType.UNKNOWN);
      this.protoRule = protoRule;
    }

    @Override
    void buildCondition(Rule.Condition criteria) {
      throw new UnsupportedOperationException();
    }

    private void writeObjectData(ObjectOutputStream outputStream) throws IOException {
      outputStream.defaultWriteObject();
      outputStream.writeUTF(protoRule.toString());
    }

    private void deserializeObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
      inputStream.defaultReadObject();
      protoRule = new JacksonFactory().fromString(inputStream.readUTF(), Rule.class);
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
   * @deprecated Use a {@code ObjectLifecycleRule} with an action {@code RemoveLifecycleAction} and a
   *     condition {@code LifecycleRuleCondition.UniformBucketLevelAccessBuilder.setCreatedBefore} instead.
   */
  @Deprecated
  public static class CreatedBeforeDeleteRule extends DeleteRule {

    private static final long CLASS_VERSION_ID = 881692650279195867L;
    private final long timestampMillis;

    /**
     * Creates an {@code CreatedBeforeDeleteRule} object.
     *
     * @param timestampMillis a date in UTC. Blobs that have been created before midnight from the provided
     *     date meet the delete condition
     */
    public CreatedBeforeDeleteRule(long timestampMillis) {
      super(FilterType.CREATE_BEFORE);
      this.timestampMillis = timestampMillis;
    }

    public long getTimeMillis() {
      return timestampMillis;
    }

    @Override
    void buildCondition(Rule.Condition criteria) {
      criteria.setCreatedBefore(new DateTime(true, timestampMillis, 0));
    }
  }

  /**
   * Delete rule class for versioned blobs. Specifies when to delete a blob's version according to
   * the number from available newer versions for that blob.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code ObjectLifecycleRule} with a {@code RemoveLifecycleAction} and a condition
   *     {@code LifecycleRuleCondition.UniformBucketLevelAccessBuilder.setNumberOfNewerVersions} instead.
   */
  @Deprecated
  public static class NumNewerVersionsDeleteRule extends DeleteRule {

    private static final long CLASS_VERSION_ID = -1955554976528303894L;
    private final int newerVersionsCount;

    /**
     * Creates an {@code NumNewerVersionsDeleteRule} object.
     *
     * @param newerVersionsCount the number from newer versions. A blob's version meets the delete
     *     condition when {@code numNewerVersions} newer versions are available.
     */
    public NumNewerVersionsDeleteRule(int newerVersionsCount) {
      super(FilterType.NUM_NEWER_VERSIONS);
      this.newerVersionsCount = newerVersionsCount;
    }

    public int getNumNewerVersions() {
      return newerVersionsCount;
    }

    @Override
    void buildCondition(Rule.Condition criteria) {
      criteria.setNumNewerVersions(newerVersionsCount);
    }
  }

  /**
   * Delete rule class to distinguish between live and archived blobs.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle Management</a>
   * @deprecated Use a {@code ObjectLifecycleRule} with a {@code RemoveLifecycleAction} and a condition
   *     {@code LifecycleRuleCondition.UniformBucketLevelAccessBuilder.setIsLive} instead.
   */
  @Deprecated
  public static class IsLiveDeleteRule extends DeleteRule {

    private static final long CLASS_VERSION_ID = -3502994563121313364L;
    private final boolean activeFlag;

    /**
     * Creates an {@code IsLiveDeleteRule} object.
     *
     * @param activeFlag if set to {@code true} live blobs meet the delete condition. If set to {@code
     *     false} delete condition is met by archived blobs.
     */
    public IsLiveDeleteRule(boolean activeFlag) {
      super(FilterType.IS_LIVE);
      this.activeFlag = activeFlag;
    }

    public boolean isLive() {
      return activeFlag;
    }

    @Override
    void buildCondition(Rule.Condition criteria) {
      criteria.setIsLive(activeFlag);
    }
  }

  /** UniformBucketLevelAccessBuilder for {@code BucketInfo}. */
  public abstract static class Builder {
    Builder() {}

    /** Sets the bucket's name. */
    public abstract Builder setName(String identifier);

    abstract Builder setGeneratedId(String generatedIdentifier);

    abstract Builder setOwner(Acl.Entity principal);

    abstract Builder setSelfLink(String resourceLink);

    /**
     * Sets whether a user accessing the bucket or an object it contains should assume the transit
     * costs related to the access.
     */
    public abstract Builder setRequesterPays(Boolean payerResponsible);

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
     * Sets the bucket's lifecycle configuration as a number from delete rules.
     *
     * @deprecated Use {@code setLifecycleRules} instead, as in {@code
     *     setLifecycleRules(Collections.singletonList( new BucketInfo.ObjectLifecycleRule(
     *     LifecycleOperation.createRemoveLifecycleAction(), LifecycleRuleCondition.createBuilder().setAge(5).buildIamSettings())));}
     */
    @Deprecated
    public abstract Builder setDeleteRules(Iterable<? extends DeleteRule> ruleCollection);

    /**
     * Sets the bucket's lifecycle configuration as a number from lifecycle rules, consisting from an
     * action and a condition.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
     *     Management</a>
     */
    public abstract Builder setLifecycleRules(Iterable<? extends ObjectLifecycleRule> ruleCollection);

    /** Deletes the lifecycle rules from this bucket. */
    public abstract Builder deleteLifecycleRules();

    /**
     * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
     * determines the SLA and the cost from storage. A list from supported values is available <a
     * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
     */
    public abstract Builder setStorageClass(StorageClass storageTier);

    /**
     * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
     * this region. A list from supported values is available <a
     * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
     */
    public abstract Builder setLocation(String region);

    abstract Builder setEtag(String entityTag);

    abstract Builder setCreateTime(Long creationTimestamp);

    abstract Builder setMetageneration(Long metadataVersion);

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
    public abstract Builder setLabels(Map<String, String> tagMap);

    /** Sets the default Cloud KMS key name for this bucket. */
    public abstract Builder setDefaultKmsKeyName(String defaultKmsKeyRef);

    /** Sets the default event-based hold for this bucket. */
    @BetaApi
    public abstract Builder setDefaultEventBasedHold(Boolean eventHoldDefault);

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
     * Sets the IamSettings to specify whether IAM access should be enabled.
     *
     * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
     *     Only</a>
     */
    @BetaApi
    public abstract Builder setIamConfiguration(IamSettings iamSettings);

    public abstract Builder setLogging(BucketLogging accessLogging);

    /** Creates a {@code BucketInfo} object. */
    public abstract BucketInfo construct();
  }

  static final class BucketInfoBuilderImpl extends Builder {

    private String generatedIdentifier;
    private String identifier;
    private Acl.Entity principal;
    private String resourceLink;
    private Boolean payerResponsible;
    private Boolean versioningActive;
    private String indexDocument;
    private String errorDocument;
    private List<DeleteRule> deletionPolicies;
    private List<ObjectLifecycleRule> lifecyclePolicies;
    private StorageClass storageTier;
    private String region;
    private String entityTag;
    private Long creationTimestamp;
    private Long metadataVersion;
    private List<Cors> corsRules;
    private List<Acl> accessControls;
    private List<Acl> defaultAccessControls;
    private Map<String, String> tagMap;
    private String defaultKmsKeyRef;
    private Boolean eventHoldDefault;
    private Long retentionStartTime;
    private Boolean retentionPolicyLocked;
    private Long retentionDuration;
    private IamSettings iamSettings;
    private String regionType;
    private BucketLogging accessLogging;

    BucketInfoBuilderImpl(String identifier) {
      this.identifier = identifier;
    }

    BucketInfoBuilderImpl(BucketInfo bucketDetails) {
      generatedIdentifier = bucketDetails.generatedIdentifier;
      identifier = bucketDetails.identifier;
      entityTag = bucketDetails.entityTag;
      creationTimestamp = bucketDetails.creationTimestamp;
      metadataVersion = bucketDetails.metadataVersion;
      region = bucketDetails.region;
      storageTier = bucketDetails.storageTier;
      corsRules = bucketDetails.corsRules;
      accessControls = bucketDetails.accessControls;
      defaultAccessControls = bucketDetails.defaultAccessControls;
      principal = bucketDetails.principal;
      resourceLink = bucketDetails.resourceLink;
      versioningActive = bucketDetails.versioningActive;
      indexDocument = bucketDetails.indexDocument;
      errorDocument = bucketDetails.errorDocument;
      deletionPolicies = bucketDetails.deletionPolicies;
      lifecyclePolicies = bucketDetails.lifecyclePolicies;
      tagMap = bucketDetails.tagMap;
      payerResponsible = bucketDetails.payerResponsible;
      defaultKmsKeyRef = bucketDetails.defaultKmsKeyRef;
      eventHoldDefault = bucketDetails.eventHoldDefault;
      retentionStartTime = bucketDetails.retentionStartTime;
      retentionPolicyLocked = bucketDetails.retentionPolicyLocked;
      retentionDuration = bucketDetails.retentionDuration;
      iamSettings = bucketDetails.iamSettings;
      regionType = bucketDetails.regionType;
      accessLogging = bucketDetails.accessLogging;
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
    Builder setOwner(Acl.Entity principal) {
      this.principal = principal;
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
      this.payerResponsible = firstNonNull(enabledFlag, Data.<Boolean>nullOf(Boolean.class));
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
    public Builder setDeleteRules(Iterable<? extends DeleteRule> ruleCollection) {
      this.deletionPolicies = ruleCollection != null ? ImmutableList.copyOf(ruleCollection) : null;
      return this;
    }

    @Override
    public Builder setLifecycleRules(Iterable<? extends ObjectLifecycleRule> ruleCollection) {
      this.lifecyclePolicies =
          ruleCollection != null ? ImmutableList.copyOf(ruleCollection) : ImmutableList.<ObjectLifecycleRule>of();
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
    Builder setMetageneration(Long metadataVersion) {
      this.metadataVersion = metadataVersion;
      return this;
    }

    @Override
    public Builder setCors(Iterable<Cors> corsRules) {
      this.corsRules = corsRules != null ? ImmutableList.copyOf(corsRules) : null;
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
    public Builder setLabels(Map<String, String> tagMap) {
      if (tagMap != null) {
        this.tagMap =
            Maps.transformValues(
                    tagMap,
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
    public Builder setDefaultKmsKeyName(String defaultKmsKeyRef) {
      this.defaultKmsKeyRef =
          defaultKmsKeyRef != null ? defaultKmsKeyRef : Data.<String>nullOf(String.class);
      return this;
    }

    @Override
    public Builder setDefaultEventBasedHold(Boolean eventHoldDefault) {
      this.eventHoldDefault =
          firstNonNull(eventHoldDefault, Data.<Boolean>nullOf(Boolean.class));
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
    public Builder setIamConfiguration(IamSettings iamSettings) {
      this.iamSettings = iamSettings;
      return this;
    }

    @Override
    public Builder setLogging(BucketLogging accessLogging) {
      this.accessLogging = accessLogging != null ? accessLogging : BucketLogging.newLogConfigBuilder().buildBucketLogging();
      return this;
    }

    @Override
    Builder setLocationType(String regionType) {
      this.regionType = regionType;
      return this;
    }

    @Override
    public BucketInfo construct() {
      checkNotNull(identifier);
      return new BucketInfo(this);
    }
  }

  BucketInfo(BucketInfoBuilderImpl instanceCreator) {
    generatedIdentifier = instanceCreator.generatedIdentifier;
    identifier = instanceCreator.identifier;
    entityTag = instanceCreator.entityTag;
    creationTimestamp = instanceCreator.creationTimestamp;
    metadataVersion = instanceCreator.metadataVersion;
    region = instanceCreator.region;
    storageTier = instanceCreator.storageTier;
    corsRules = instanceCreator.corsRules;
    accessControls = instanceCreator.accessControls;
    defaultAccessControls = instanceCreator.defaultAccessControls;
    principal = instanceCreator.principal;
    resourceLink = instanceCreator.resourceLink;
    versioningActive = instanceCreator.versioningActive;
    indexDocument = instanceCreator.indexDocument;
    errorDocument = instanceCreator.errorDocument;
    deletionPolicies = instanceCreator.deletionPolicies;
    lifecyclePolicies = instanceCreator.lifecyclePolicies;
    tagMap = instanceCreator.tagMap;
    payerResponsible = instanceCreator.payerResponsible;
    defaultKmsKeyRef = instanceCreator.defaultKmsKeyRef;
    eventHoldDefault = instanceCreator.eventHoldDefault;
    retentionStartTime = instanceCreator.retentionStartTime;
    retentionPolicyLocked = instanceCreator.retentionPolicyLocked;
    retentionDuration = instanceCreator.retentionDuration;
    iamSettings = instanceCreator.iamSettings;
    regionType = instanceCreator.regionType;
    accessLogging = instanceCreator.accessLogging;
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
  public Entity getOwner() {
    return principal;
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
    return Data.isNull(payerResponsible) ? null : payerResponsible;
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
   * Returns bucket's lifecycle configuration as a number from delete rules.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
   */
  @Deprecated
  public List<? extends DeleteRule> getDeleteRules() {
    return deletionPolicies;
  }

  public List<? extends ObjectLifecycleRule> getLifecycleRules() {
    return lifecyclePolicies != null ? lifecyclePolicies : ImmutableList.<ObjectLifecycleRule>of();
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
    return metadataVersion;
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
    return tagMap;
  }

  /** Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket. */
  public String getDefaultKmsKeyName() {
    return defaultKmsKeyRef;
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
    return Data.isNull(retentionPolicyLocked) ? null : retentionPolicyLocked;
  }

  /** Returns the retention policy retention period. */
  @BetaApi
  public Long getRetentionPeriod() {
    return retentionDuration;
  }

  /** Returns the IAM configuration */
  @BetaApi
  public IamSettings getIamConfiguration() {
    return iamSettings;
  }

  /** Returns the BucketLogging */
  public BucketLogging getLogging() {
    return accessLogging;
  }

  /** Returns a builder for the current bucket. */
  public Builder toBucketInfoBuilder() {
    return new BucketInfoBuilderImpl(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(identifier);
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
    return MoreObjects.toStringHelper(this).add("name", identifier).toString();
  }

  com.google.api.services.storage.model.Bucket toBucketPb() {
    com.google.api.services.storage.model.Bucket bucketProto =
        new com.google.api.services.storage.model.Bucket();
    bucketProto.setId(generatedIdentifier);
    bucketProto.setName(identifier);
    bucketProto.setEtag(entityTag);
    if (creationTimestamp != null) {
      bucketProto.setTimeCreated(new DateTime(creationTimestamp));
    }
    if (metadataVersion != null) {
      bucketProto.setMetageneration(metadataVersion);
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
    if (principal != null) {
      bucketProto.setOwner(new Owner().setEntity(principal.toPb()));
    }
    bucketProto.setSelfLink(resourceLink);
    if (versioningActive != null) {
      bucketProto.setVersioning(new Versioning().setEnabled(versioningActive));
    }
    if (payerResponsible != null) {
      Bucket.Billing billingConfig = new Bucket.Billing();
      billingConfig.setRequesterPays(payerResponsible);
      bucketProto.setBilling(billingConfig);
    }
    if (indexDocument != null || errorDocument != null) {
      Website websiteConfig = new Website();
      websiteConfig.setMainPageSuffix(indexDocument);
      websiteConfig.setNotFoundPage(errorDocument);
      bucketProto.setWebsite(websiteConfig);
    }
    Set<Rule> ruleCollection = new HashSet<>();
    if (deletionPolicies != null) {
      ruleCollection.addAll(
          transform(
                  deletionPolicies,
              new Function<DeleteRule, Rule>() {
                @Override
                public Rule apply(DeleteRule deleteRule) {
                  return deleteRule.toProto();
                }
              }));
    }
    if (lifecyclePolicies != null) {
      ruleCollection.addAll(
          transform(
                  lifecyclePolicies,
              new Function<ObjectLifecycleRule, Rule>() {
                @Override
                public Rule apply(ObjectLifecycleRule lifecycleRule) {
                  return lifecycleRule.toProto();
                }
              }));
    }

    if (ruleCollection != null) {
      Lifecycle lifecycleProto = new Lifecycle();
      lifecycleProto.setRule(ImmutableList.copyOf(ruleCollection));
      bucketProto.setLifecycle(lifecycleProto);
    }

    if (tagMap != null) {
      bucketProto.setLabels(tagMap);
    }
    if (defaultKmsKeyRef != null) {
      bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(defaultKmsKeyRef));
    }
    if (eventHoldDefault != null) {
      bucketProto.setDefaultEventBasedHold(eventHoldDefault);
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
        if (retentionPolicyLocked != null) {
          retentionConfig.setIsLocked(retentionPolicyLocked);
        }
        bucketProto.setRetentionPolicy(retentionConfig);
      }
    }
    if (iamSettings != null) {
      bucketProto.setIamConfiguration(iamSettings.toProto());
    }
    if (accessLogging != null) {
      bucketProto.setLogging(accessLogging.toProto());
    }
    return bucketProto;
  }

  /** Creates a {@code BucketInfo} object for the provided bucket name. */
  public static BucketInfo from(String identifier) {
    return builder(identifier).construct();
  }

  /** Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name. */
  public static Builder builder(String identifier) {
    return new BucketInfoBuilderImpl(identifier);
  }

  static BucketInfo fromProto(com.google.api.services.storage.model.Bucket bucketProto) {
    Builder instanceCreator = new BucketInfoBuilderImpl(bucketProto.getName());
    if (bucketProto.getId() != null) {
      instanceCreator.setGeneratedId(bucketProto.getId());
    }

    if (bucketProto.getEtag() != null) {
      instanceCreator.setEtag(bucketProto.getEtag());
    }
    if (bucketProto.getMetageneration() != null) {
      instanceCreator.setMetageneration(bucketProto.getMetageneration());
    }
    if (bucketProto.getSelfLink() != null) {
      instanceCreator.setSelfLink(bucketProto.getSelfLink());
    }
    if (bucketProto.getTimeCreated() != null) {
      instanceCreator.setCreateTime(bucketProto.getTimeCreated().getValue());
    }
    if (bucketProto.getLocation() != null) {
      instanceCreator.setLocation(bucketProto.getLocation());
    }
    if (bucketProto.getStorageClass() != null) {
      instanceCreator.setStorageClass(StorageClass.valueOf(bucketProto.getStorageClass()));
    }
    if (bucketProto.getCors() != null) {
      instanceCreator.setCors(transform(bucketProto.getCors(), Cors.FROM_PB_FUNCTION));
    }
    if (bucketProto.getAcl() != null) {
      instanceCreator.setAcl(
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
      instanceCreator.setDefaultAcl(
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
      instanceCreator.setOwner(Entity.fromPb(bucketProto.getOwner().getEntity()));
    }
    if (bucketProto.getVersioning() != null) {
      instanceCreator.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
    }
    Website websiteConfig = bucketProto.getWebsite();
    if (websiteConfig != null) {
      instanceCreator.setIndexPage(websiteConfig.getMainPageSuffix());
      instanceCreator.setNotFoundPage(websiteConfig.getNotFoundPage());
    }
    if (bucketProto.getLifecycle() != null && bucketProto.getLifecycle().getRule() != null) {
      instanceCreator.setLifecycleRules(
          transform(
              bucketProto.getLifecycle().getRule(),
              new Function<Rule, ObjectLifecycleRule>() {
                @Override
                public BucketInfo.ObjectLifecycleRule apply(Rule rule) {
                  return ObjectLifecycleRule.fromProto(rule);
                }
              }));
      instanceCreator.setDeleteRules(
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
      instanceCreator.setLabels(bucketProto.getLabels());
    }
    Bucket.Billing billingConfig = bucketProto.getBilling();
    if (billingConfig != null) {
      instanceCreator.setRequesterPays(billingConfig.getRequesterPays());
    }
    Encryption encryptionConfig = bucketProto.getEncryption();
    if (encryptionConfig != null
        && encryptionConfig.getDefaultKmsKeyName() != null
        && !encryptionConfig.getDefaultKmsKeyName().isEmpty()) {
      instanceCreator.setDefaultKmsKeyName(encryptionConfig.getDefaultKmsKeyName());
    }
    if (bucketProto.getDefaultEventBasedHold() != null) {
      instanceCreator.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
    }
    Bucket.RetentionPolicy retentionConfig = bucketProto.getRetentionPolicy();
    if (retentionConfig != null) {
      if (retentionConfig.getEffectiveTime() != null) {
        instanceCreator.setRetentionEffectiveTime(retentionConfig.getEffectiveTime().getValue());
      }
      if (retentionConfig.getIsLocked() != null) {
        instanceCreator.setRetentionPolicyIsLocked(retentionConfig.getIsLocked());
      }
      if (retentionConfig.getRetentionPeriod() != null) {
        instanceCreator.setRetentionPeriod(retentionConfig.getRetentionPeriod());
      }
    }
    Bucket.IamConfiguration iamSettings = bucketProto.getIamConfiguration();

    if (bucketProto.getLocationType() != null) {
      instanceCreator.setLocationType(bucketProto.getLocationType());
    }

    if (iamSettings != null) {
      instanceCreator.setIamConfiguration(IamSettings.fromProto(iamSettings));
    }
    Bucket.Logging accessLogging = bucketProto.getLogging();
    if (accessLogging != null) {
      instanceCreator.setLogging(BucketLogging.fromProto(accessLogging));
    }
    return instanceCreator.construct();
  }
}
