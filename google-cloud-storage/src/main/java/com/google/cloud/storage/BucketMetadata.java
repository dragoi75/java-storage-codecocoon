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
import com.google.api.services.storage.model.*;
import com.google.api.services.storage.model.Bucket;
import com.google.api.services.storage.model.Bucket.Encryption;
import com.google.api.services.storage.model.Bucket.Lifecycle;
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule;
import com.google.api.services.storage.model.Bucket.Owner;
import com.google.api.services.storage.model.Bucket.Versioning;
import com.google.api.services.storage.model.Bucket.Website;
import com.google.cloud.storage.AccessControlEntry.TypedEntity;
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
public class BucketMetadata implements Serializable {

  static final Function<com.google.api.services.storage.model.Bucket, BucketMetadata> FROM_PB_FUNCTION =
      new Function<com.google.api.services.storage.model.Bucket, BucketMetadata>() {
        @Override
        public BucketMetadata apply(com.google.api.services.storage.model.Bucket pb) {
          return BucketMetadata.fromProto(pb);
        }
      };
  static final Function<BucketMetadata, com.google.api.services.storage.model.Bucket> TO_PROTO_FUNCTION =
      new Function<BucketMetadata, com.google.api.services.storage.model.Bucket>() {
        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketMetadata bucketInfo) {
          return bucketInfo.toProto();
        }
      };
  private static final long serialVersionUID = -4712013629621638459L;
  private final String generatedIdentifier;
  private final String bucketName;
  private final AccessControlEntry.TypedEntity ownerEntity;
  private final String selfUrl;
  private final Boolean requesterPaysEnabled;
  private final Boolean versioningActive;
  private final String mainIndexPage;
  private final String notFoundHtml;
  private final List<AbstractDeleteRule> deletionRules;
  private final List<LifecycleRuleDefinition> lifecycleDefinitions;
  private final String entityTag;
  private final Long creationTime;
  private final Long metaGenerationId;
  private final List<Cors> corsRules;
  private final List<AccessControlEntry> accessControlList;
  private final List<AccessControlEntry> defaultAccessControlList;
  private final String region;
  private final StorageTier storageTier;
  private final Map<String, String> labelMap;
  private final String defaultKmsKey;
  private final Boolean defaultEventHold;
  private final Long retentionStartTime;
  private final Boolean retentionPolicyLocked;
  private final Long retentionDuration;
  private final BucketIamConfiguration bucketIamConfig;
  private final String locationClass;
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
      BucketIamConfiguration compared = (BucketIamConfiguration) obj;
      return Objects.equals(toProto(), compared.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(uniformBucketLevelAccessEnabled, uniformAccessLockedTime);
    }

    private BucketIamConfiguration(UniformBucketLevelAccessBuilder uniformAccessBuilder) {
      this.uniformBucketLevelAccessEnabled = uniformAccessBuilder.uniformBucketLevelAccessEnabled;
      this.uniformAccessLockedTime = uniformAccessBuilder.uniformAccessLockedTime;
    }

    public static UniformBucketLevelAccessBuilder newUniformBucketLevelAccessBuilder() {
      return new UniformBucketLevelAccessBuilder();
    }

    public UniformBucketLevelAccessBuilder toUniformBucketLevelAccessBuilder() {
      UniformBucketLevelAccessBuilder uniformAccessBuilder = new UniformBucketLevelAccessBuilder();
      uniformAccessBuilder.uniformBucketLevelAccessEnabled = uniformBucketLevelAccessEnabled;
      uniformAccessBuilder.uniformAccessLockedTime = uniformAccessLockedTime;
      return uniformAccessBuilder;
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
      Bucket.IamConfiguration bucketIamConfig = new Bucket.IamConfiguration();

      Bucket.IamConfiguration.UniformBucketLevelAccess bucketLevelAccessConfig =
          new Bucket.IamConfiguration.UniformBucketLevelAccess();
      bucketLevelAccessConfig.setEnabled(uniformBucketLevelAccessEnabled);
      bucketLevelAccessConfig.setLockedTime(
          uniformAccessLockedTime == null
              ? null
              : new DateTime(uniformAccessLockedTime));

      bucketIamConfig.setUniformBucketLevelAccess(bucketLevelAccessConfig);

      return bucketIamConfig;
    }

    static BucketIamConfiguration fromProto(Bucket.IamConfiguration bucketIamConfig) {
      Bucket.IamConfiguration.UniformBucketLevelAccess bucketLevelAccessConfig =
          bucketIamConfig.getUniformBucketLevelAccess();
      DateTime lockTimestamp = bucketLevelAccessConfig.getLockedTime();

      return newUniformBucketLevelAccessBuilder()
          .setIsUniformBucketLevelAccessEnabled(bucketLevelAccessConfig.getEnabled())
          .setUniformBucketLevelAccessLockedTime(lockTimestamp == null ? null : lockTimestamp.getValue())
          .buildBucketIamConfiguration();
    }

    /** Builder for {@code IamConfiguration} */
    public static class UniformBucketLevelAccessBuilder {
      private Boolean uniformBucketLevelAccessEnabled;
      private Long uniformAccessLockedTime;

      /** Deprecated in favor of setIsUniformBucketLevelAccessEnabled(). */
      @Deprecated
      public BucketMetadata.BucketIamConfiguration.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean bucketPolicyEnabled) {
        this.uniformBucketLevelAccessEnabled = bucketPolicyEnabled;
        return this;
      }

      /** Deprecated in favor of setUniformBucketLevelAccessLockedTime(). */
      @Deprecated
      BucketMetadata.BucketIamConfiguration.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long policyOnlyLockedAt) {
        this.uniformAccessLockedTime = policyOnlyLockedAt;
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
    private String loggingBucketName;
    private String objectNamePrefix;

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      LoggingConfig compared = (LoggingConfig) obj;
      return Objects.equals(toProto(), compared.toProto());
    }

    @Override
    public int hashCode() {
      return Objects.hash(loggingBucketName, objectNamePrefix);
    }

    public static LogDestinationBuilder newLogDestinationBuilder() {
      return new LogDestinationBuilder();
    }

    public LogDestinationBuilder toBuilder() {
      LogDestinationBuilder uniformAccessBuilder = new LogDestinationBuilder();
      uniformAccessBuilder.loggingBucketName = loggingBucketName;
      uniformAccessBuilder.objectNamePrefix = objectNamePrefix;
      return uniformAccessBuilder;
    }

    public String getLogBucket() {
      return loggingBucketName;
    }

    public String getLogObjectPrefix() {
      return objectNamePrefix;
    }

    Bucket.Logging toProto() {
      Bucket.Logging loggingConfig = new Bucket.Logging();
      loggingConfig.setLogBucket(loggingBucketName);
      loggingConfig.setLogObjectPrefix(objectNamePrefix);
      return loggingConfig;
    }

    static LoggingConfig fromProto(Bucket.Logging loggingConfig) {
      return newLogDestinationBuilder()
          .setLogBucket(loggingConfig.getLogBucket())
          .setLogObjectPrefix(loggingConfig.getLogObjectPrefix())
          .buildLoggingConfig();
    }

    private LoggingConfig(LogDestinationBuilder uniformAccessBuilder) {
      this.loggingBucketName = uniformAccessBuilder.loggingBucketName;
      this.objectNamePrefix = uniformAccessBuilder.objectNamePrefix;
    }

    public static class LogDestinationBuilder {
      private String loggingBucketName;
      private String objectNamePrefix;

      /** The destination bucket where the current bucket's logs should be placed. */
      public LogDestinationBuilder setLogBucket(String loggingBucketName) {
        this.loggingBucketName = loggingBucketName;
        return this;
      }

      /** A prefix for log object names. */
      public LogDestinationBuilder setLogObjectPrefix(String objectNamePrefix) {
        this.objectNamePrefix = objectNamePrefix;
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
    private final LifecycleOperation operationType;
    private final LifecycleRuleCondition ruleCriteria;

    public LifecycleRuleDefinition(LifecycleOperation operationParam, LifecycleRuleCondition criteriaParam) {
      if (criteriaParam.getIsLive() == null
          && criteriaParam.getAge() == null
          && criteriaParam.getCreatedBefore() == null
          && criteriaParam.getMatchesStorageClass() == null
          && criteriaParam.getNumberOfNewerVersions() == null) {
        throw new IllegalArgumentException(
            "You must specify at least one condition to use object lifecycle "
                + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
      }

      this.operationType = operationParam;
      this.ruleCriteria = criteriaParam;
    }

    public LifecycleOperation getAction() {
      return operationType;
    }

    public LifecycleRuleCondition getCondition() {
      return ruleCriteria;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("lifecycleAction", operationType)
          .add("lifecycleCondition", ruleCriteria)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(operationType, ruleCriteria);
    }

    @Override
    public boolean equals(Object candidate) {
      if (this == candidate) {
        return true;
      }
      if (candidate == null || getClass() != candidate.getClass()) {
        return false;
      }
      final LifecycleRuleDefinition compared = (LifecycleRuleDefinition) candidate;
      return Objects.equals(toProto(), compared.toProto());
    }

    Rule toProto() {
      Rule entryProto = new Rule();

      Rule.Action operationParam = new Rule.Action().setType(operationType.getActionType());
      if (operationType.getActionType().equals(UpdateStorageClassLifecycleAction.TYPE)) {
        operationParam.setStorageClass(
            ((UpdateStorageClassLifecycleAction) operationType).getStorageClass().toString());
      }

      entryProto.setAction(operationParam);

      Rule.Condition criteriaParam =
          new Rule.Condition()
              .setAge(ruleCriteria.getAge())
              .setCreatedBefore(
                  ruleCriteria.getCreatedBefore() == null
                      ? null
                      : new DateTime(true, ruleCriteria.getCreatedBefore().getValue(), 0))
              .setIsLive(ruleCriteria.getIsLive())
              .setNumNewerVersions(ruleCriteria.getNumberOfNewerVersions())
              .setMatchesStorageClass(
                  ruleCriteria.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          ruleCriteria.getMatchesStorageClass(),
                          Functions.toStringFunction()));

      entryProto.setCondition(criteriaParam);

      return entryProto;
    }

    static LifecycleRuleDefinition fromProto(Rule entryProto) {
      LifecycleOperation operationType;

      Rule.Action operationParam = entryProto.getAction();

      switch (operationParam.getType()) {
        case RemoveLifecycleAction.TYPE:
          operationType = LifecycleOperation.newRemoveAction();
          break;
        case UpdateStorageClassLifecycleAction.TYPE:
          operationType =
              LifecycleOperation.newUpdateStorageClassAction(
                  StorageTier.fromValue(operationParam.getStorageClass()));
          break;
        default:
          throw new UnsupportedOperationException(
              "The specified lifecycle action " + operationParam.getType() + " is not currently supported");
      }

      Rule.Condition criteriaParam = entryProto.getCondition();

      LifecycleRuleCondition.LifecycleConditionBuilder criteriaFactory =
          LifecycleRuleCondition.newLifecycleConditionBuilder()
              .setAge(criteriaParam.getAge())
              .setCreatedBefore(criteriaParam.getCreatedBefore())
              .setIsLive(criteriaParam.getIsLive())
              .setNumberOfNewerVersions(criteriaParam.getNumNewerVersions())
              .setMatchesStorageClass(
                  criteriaParam.getMatchesStorageClass() == null
                      ? null
                      : transform(
                          criteriaParam.getMatchesStorageClass(),
                          new Function<String, StorageTier>() {
                            public StorageTier apply(String storageClass) {
                              return StorageTier.fromValue(storageClass);
                            }
                          }));

      return new LifecycleRuleDefinition(operationType, criteriaFactory.buildLifecycleCondition());
    }

    /**
     * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
     *     Management</a>
     */
    public static class LifecycleRuleCondition implements Serializable {
      private static final long serialVersionUID = -6482314338394768785L;
      private final Integer daysOld;
      private final DateTime cutoffDate;
      private final Integer newerVersionCount;
      private final Boolean liveFlag;
      private final List<StorageTier> matchingStorageTiers;

      private LifecycleRuleCondition(LifecycleConditionBuilder uniformAccessBuilder) {
        this.daysOld = uniformAccessBuilder.daysOld;
        this.cutoffDate = uniformAccessBuilder.cutoffDate;
        this.newerVersionCount = uniformAccessBuilder.newerVersionCount;
        this.liveFlag = uniformAccessBuilder.liveFlag;
        this.matchingStorageTiers = uniformAccessBuilder.matchingStorageTiers;
      }

      public LifecycleConditionBuilder toBuilder() {
        return newLifecycleConditionBuilder()
            .setAge(this.daysOld)
            .setCreatedBefore(this.cutoffDate)
            .setNumberOfNewerVersions(this.newerVersionCount)
            .setIsLive(this.liveFlag)
            .setMatchesStorageClass(this.matchingStorageTiers);
      }

      public static LifecycleConditionBuilder newLifecycleConditionBuilder() {
        return new LifecycleConditionBuilder();
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("age", daysOld)
            .add("createBefore", cutoffDate)
            .add("numberofNewerVersions", newerVersionCount)
            .add("isLive", liveFlag)
            .add("matchesStorageClass", matchingStorageTiers)
            .toString();
      }

      public Integer getAge() {
        return daysOld;
      }

      public DateTime getCreatedBefore() {
        return cutoffDate;
      }

      public Integer getNumberOfNewerVersions() {
        return newerVersionCount;
      }

      public Boolean getIsLive() {
        return liveFlag;
      }

      public List<StorageTier> getMatchesStorageClass() {
        return matchingStorageTiers;
      }

      /** Builder for {@code LifecycleCondition}. */
      public static class LifecycleConditionBuilder {
        private Integer daysOld;
        private DateTime cutoffDate;
        private Integer newerVersionCount;
        private Boolean liveFlag;
        private List<StorageTier> matchingStorageTiers;

        private LifecycleConditionBuilder() {}

        /**
         * Sets the age in days. This condition is satisfied when a Blob reaches the specified age
         * (in days). When you specify the Age condition, you are specifying a Time to Live (TTL)
         * for objects in a bucket with lifecycle management configured. The time when the Age
         * condition is considered to be satisfied is calculated by adding the specified value to
         * the object creation time.
         */
        public LifecycleConditionBuilder setAge(Integer daysOld) {
          this.daysOld = daysOld;
          return this;
        }

        /**
         * Sets the date a Blob should be created before for an Action to be executed. Note that
         * only the date will be considered, if the time is specified it will be truncated. This
         * condition is satisfied when an object is created before midnight of the specified date in
         * UTC. *
         */
        public LifecycleConditionBuilder setCreatedBefore(DateTime cutoffDate) {
          this.cutoffDate = cutoffDate;
          return this;
        }

        /**
         * Sets the number of newer versions a Blob should have for an Action to be executed.
         * Relevant only when versioning is enabled on a bucket. *
         */
        public LifecycleConditionBuilder setNumberOfNewerVersions(Integer newerVersionCount) {
          this.newerVersionCount = newerVersionCount;
          return this;
        }

        /**
         * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
         * only live Blobs; if the value is false, it matches only archived objects. For the
         * purposes of this condition, Blobs in non-versioned buckets are considered live.
         */
        public LifecycleConditionBuilder setIsLive(Boolean liveFlag) {
          this.liveFlag = liveFlag;
          return this;
        }

        /**
         * Sets a list of Storage Classes for a objects that satisfy the condition to execute the
         * Action. *
         */
        public LifecycleConditionBuilder setMatchesStorageClass(List<StorageTier> matchingStorageTiers) {
          this.matchingStorageTiers = matchingStorageTiers;
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
    public abstract static class LifecycleOperation implements Serializable {
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
          StorageTier storageTier) {
        return new UpdateStorageClassLifecycleAction(storageTier);
      }
    }

    public static class RemoveLifecycleAction extends LifecycleOperation {
      public static final String TYPE = "Delete";
      private static final long serialVersionUID = -2050986302222644873L;

      private RemoveLifecycleAction() {}

      @Override
      public String getActionType() {
        return TYPE;
      }
    }

    public static class UpdateStorageClassLifecycleAction extends LifecycleOperation {
      public static final String TYPE = "SetStorageClass";
      private static final long serialVersionUID = -62615467186000899L;

      private final StorageTier storageTier;

      private UpdateStorageClassLifecycleAction(StorageTier storageTier) {
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

      public StorageTier getStorageClass() {
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
  public abstract static class AbstractDeleteRule implements Serializable {

    private static final long serialVersionUID = 3137971668395933033L;
    private static final String SUPPORTED_ACTIONS = "Delete";
    private final FilterType filterType;

    public enum FilterType {
      AGE,
      CREATE_BEFORE,
      NUM_NEWER_VERSIONS,
      IS_LIVE,
      UNKNOWN
    }

    AbstractDeleteRule(FilterType filterType) {
      this.filterType = filterType;
    }

    public FilterType getType() {
      return filterType;
    }

    @Override
    public int hashCode() {
      return Objects.hash(filterType);
    }

    @Override
    public boolean equals(Object candidate) {
      if (this == candidate) {
        return true;
      }
      if (candidate == null || getClass() != candidate.getClass()) {
        return false;
      }
      final AbstractDeleteRule compared = (AbstractDeleteRule) candidate;
      return Objects.equals(toProto(), compared.toProto());
    }

    Rule toProto() {
      Rule entryProto = new Rule();
      entryProto.setAction(new Rule.Action().setType(SUPPORTED_ACTIONS));
      Rule.Condition criteriaParam = new Rule.Condition();
      fillCondition(criteriaParam);
      entryProto.setCondition(criteriaParam);
      return entryProto;
    }

    abstract void fillCondition(Rule.Condition condition);

    static AbstractDeleteRule fromProto(Rule entryProto) {
      if (entryProto.getAction() != null && SUPPORTED_ACTIONS.endsWith(entryProto.getAction().getType())) {
        Rule.Condition criteriaParam = entryProto.getCondition();
        Integer daysOld = criteriaParam.getAge();
        if (daysOld != null) {
          return new AgeBasedDeleteRule(daysOld);
        }
        DateTime timestampValue = criteriaParam.getCreatedBefore();
        if (timestampValue != null) {
          return new CreatedBeforeDeletionRule(timestampValue.getValue());
        }
        Integer newerVersionCount = criteriaParam.getNumNewerVersions();
        if (newerVersionCount != null) {
          return new NumNewerVersionsRemovalRule(newerVersionCount);
        }
        Boolean liveFlag = criteriaParam.getIsLive();
        if (liveFlag != null) {
          return new LiveDeleteRule(liveFlag);
        }
      }
      return new RawDeleteRuleData(entryProto);
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
  public static class AgeBasedDeleteRule extends AbstractDeleteRule {

    private static final long serialVersionUID = 5697166940712116380L;
    private final int ttlDays;

    /**
     * Creates an {@code AgeDeleteRule} object.
     *
     * @param ttlDays blobs' Time To Live expressed in days. The time when the age condition is
     *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
     *     following blob's creation time in UTC.
     */
    public AgeBasedDeleteRule(int ttlDays) {
      super(FilterType.AGE);
      this.ttlDays = ttlDays;
    }

    public int getDaysToLive() {
      return ttlDays;
    }

    @Override
    void fillCondition(Rule.Condition criteriaParam) {
      criteriaParam.setAge(ttlDays);
    }
  }

  static class RawDeleteRuleData extends AbstractDeleteRule {

    private static final long serialVersionUID = -7166938278642301933L;

    private transient Rule entryProto;

    RawDeleteRuleData(Rule entryProto) {
      super(FilterType.UNKNOWN);
      this.entryProto = entryProto;
    }

    @Override
    void fillCondition(Rule.Condition condition) {
      throw new UnsupportedOperationException();
    }

    private void writeObject(ObjectOutputStream objectStream) throws IOException {
      objectStream.defaultWriteObject();
      objectStream.writeUTF(entryProto.toString());
    }

    private void readObject(ObjectInputStream objectReader) throws IOException, ClassNotFoundException {
      objectReader.defaultReadObject();
      entryProto = new JacksonFactory().fromString(objectReader.readUTF(), Rule.class);
    }

    @Override
    Rule toProto() {
      return entryProto;
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
  public static class CreatedBeforeDeletionRule extends AbstractDeleteRule {

    private static final long serialVersionUID = 881692650279195867L;
    private final long timestampMillis;

    /**
     * Creates an {@code CreatedBeforeDeleteRule} object.
     *
     * @param timestampMillis a date in UTC. Blobs that have been created before midnight of the provided
     *     date meet the delete condition
     */
    public CreatedBeforeDeletionRule(long timestampMillis) {
      super(FilterType.CREATE_BEFORE);
      this.timestampMillis = timestampMillis;
    }

    public long getTimeMillis() {
      return timestampMillis;
    }

    @Override
    void fillCondition(Rule.Condition criteriaParam) {
      criteriaParam.setCreatedBefore(new DateTime(true, timestampMillis, 0));
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
  public static class NumNewerVersionsRemovalRule extends AbstractDeleteRule {

    private static final long serialVersionUID = -1955554976528303894L;
    private final int newerVersionCount;

    /**
     * Creates an {@code NumNewerVersionsDeleteRule} object.
     *
     * @param newerVersionCount the number of newer versions. A blob's version meets the delete
     *     condition when {@code numNewerVersions} newer versions are available.
     */
    public NumNewerVersionsRemovalRule(int newerVersionCount) {
      super(FilterType.NUM_NEWER_VERSIONS);
      this.newerVersionCount = newerVersionCount;
    }

    public int getNumNewerVersions() {
      return newerVersionCount;
    }

    @Override
    void fillCondition(Rule.Condition criteriaParam) {
      criteriaParam.setNumNewerVersions(newerVersionCount);
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
  public static class LiveDeleteRule extends AbstractDeleteRule {

    private static final long serialVersionUID = -3502994563121313364L;
    private final boolean liveFlag;

    /**
     * Creates an {@code IsLiveDeleteRule} object.
     *
     * @param liveFlag if set to {@code true} live blobs meet the delete condition. If set to {@code
     *     false} delete condition is met by archived blobs.
     */
    public LiveDeleteRule(boolean liveFlag) {
      super(FilterType.IS_LIVE);
      this.liveFlag = liveFlag;
    }

    public boolean isLive() {
      return liveFlag;
    }

    @Override
    void fillCondition(Rule.Condition criteriaParam) {
      criteriaParam.setIsLive(liveFlag);
    }
  }

  /** Builder for {@code BucketInfo}. */
  public abstract static class BucketBuilder {
    BucketBuilder() {}

    /** Sets the bucket's name. */
    public abstract BucketBuilder setName(String name);

    abstract BucketBuilder setGeneratedId(String generatedId);

    abstract BucketBuilder setOwner(TypedEntity owner);

    abstract BucketBuilder setSelfLink(String selfLink);

    /**
     * Sets whether a user accessing the bucket or an object it contains should assume the transit
     * costs related to the access.
     */
    public abstract BucketBuilder setRequesterPays(Boolean requesterPays);

    /**
     * Sets whether versioning should be enabled for this bucket. When set to true, versioning is
     * fully enabled.
     */
    public abstract BucketBuilder setVersioningEnabled(Boolean enable);

    /**
     * Sets the bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public abstract BucketBuilder setIndexPage(String indexPage);

    /** Sets the custom object to return when a requested resource is not found. */
    public abstract BucketBuilder setNotFoundPage(String notFoundPage);

    /**
     * Sets the bucket's lifecycle configuration as a number of delete rules.
     *
     * @deprecated Use {@code setLifecycleRules} instead, as in {@code
     *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRule(
     *     LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(5).build())));}
     */
    @Deprecated
    public abstract BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends AbstractDeleteRule> rules);

    /**
     * Sets the bucket's lifecycle configuration as a number of lifecycle rules, consisting of an
     * action and a condition.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
     *     Management</a>
     */
    public abstract BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> rules);

    /**
     * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
     * determines the SLA and the cost of storage. A list of supported values is available <a
     * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
     */
    public abstract BucketBuilder setStorageClass(StorageTier storageClass);

    /**
     * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
     * this region. A list of supported values is available <a
     * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
     */
    public abstract BucketBuilder setLocation(String location);

    abstract BucketBuilder setEtag(String etag);

    abstract BucketBuilder setCreateTime(Long createTime);

    abstract BucketBuilder setMetageneration(Long metageneration);

    abstract BucketBuilder setLocationType(String locationType);

    /**
     * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
     *     Sharing (CORS)</a>
     */
    public abstract BucketBuilder setCors(Iterable<Cors> cors);

    /**
     * Sets the bucket's access control configuration.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract BucketBuilder setAcl(Iterable<AccessControlEntry> acl);

    /**
     * Sets the default access control configuration to apply to bucket's blobs when no other
     * configuration is specified.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public abstract BucketBuilder setDefaultAcl(Iterable<AccessControlEntry> acl);

    /** Sets the label of this bucket. */
    public abstract BucketBuilder setLabels(Map<String, String> labels);

    /** Sets the default Cloud KMS key name for this bucket. */
    public abstract BucketBuilder setDefaultKmsKeyName(String defaultKmsKeyName);

    /** Sets the default event-based hold for this bucket. */
    @BetaApi
    public abstract BucketBuilder setDefaultEventBasedHold(Boolean defaultEventBasedHold);

    @BetaApi
    abstract BucketBuilder setRetentionEffectiveTime(Long retentionEffectiveTime);

    @BetaApi
    abstract BucketBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyIsLocked);

    /**
     * If policy is not locked this value can be cleared, increased, and decreased. If policy is
     * locked the retention period can only be increased.
     */
    @BetaApi
    public abstract BucketBuilder setRetentionPeriod(Long retentionPeriod);

    /**
     * Sets the IamConfiguration to specify whether IAM access should be enabled.
     *
     * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
     *     Only</a>
     */
    @BetaApi
    public abstract BucketBuilder setIamConfiguration(BucketIamConfiguration iamConfiguration);

    public abstract BucketBuilder setLogging(LoggingConfig logging);

    /** Creates a {@code BucketInfo} object. */
    public abstract BucketMetadata buildBucket();
  }

  static final class BucketBuilderImpl extends BucketBuilder {

    private String generatedIdentifier;
    private String bucketName;
    private TypedEntity ownerEntity;
    private String selfUrl;
    private Boolean requesterPaysEnabled;
    private Boolean versioningActive;
    private String mainIndexPage;
    private String notFoundHtml;
    private List<AbstractDeleteRule> deletionRules;
    private List<LifecycleRuleDefinition> lifecycleDefinitions;
    private StorageTier storageTier;
    private String region;
    private String entityTag;
    private Long creationTime;
    private Long metaGenerationId;
    private List<Cors> corsRules;
    private List<AccessControlEntry> accessControlList;
    private List<AccessControlEntry> defaultAccessControlList;
    private Map<String, String> labelMap;
    private String defaultKmsKey;
    private Boolean defaultEventHold;
    private Long retentionStartTime;
    private Boolean retentionPolicyLocked;
    private Long retentionDuration;
    private BucketIamConfiguration bucketIamConfig;
    private String locationClass;
    private LoggingConfig loggingConfig;

    BucketBuilderImpl(String bucketName) {
      this.bucketName = bucketName;
    }

    BucketBuilderImpl(BucketMetadata bucketMetadata) {
      generatedIdentifier = bucketMetadata.generatedIdentifier;
      bucketName = bucketMetadata.bucketName;
      entityTag = bucketMetadata.entityTag;
      creationTime = bucketMetadata.creationTime;
      metaGenerationId = bucketMetadata.metaGenerationId;
      region = bucketMetadata.region;
      storageTier = bucketMetadata.storageTier;
      corsRules = bucketMetadata.corsRules;
      accessControlList = bucketMetadata.accessControlList;
      defaultAccessControlList = bucketMetadata.defaultAccessControlList;
      ownerEntity = bucketMetadata.ownerEntity;
      selfUrl = bucketMetadata.selfUrl;
      versioningActive = bucketMetadata.versioningActive;
      mainIndexPage = bucketMetadata.mainIndexPage;
      notFoundHtml = bucketMetadata.notFoundHtml;
      deletionRules = bucketMetadata.deletionRules;
      lifecycleDefinitions = bucketMetadata.lifecycleDefinitions;
      labelMap = bucketMetadata.labelMap;
      requesterPaysEnabled = bucketMetadata.requesterPaysEnabled;
      defaultKmsKey = bucketMetadata.defaultKmsKey;
      defaultEventHold = bucketMetadata.defaultEventHold;
      retentionStartTime = bucketMetadata.retentionStartTime;
      retentionPolicyLocked = bucketMetadata.retentionPolicyLocked;
      retentionDuration = bucketMetadata.retentionDuration;
      bucketIamConfig = bucketMetadata.bucketIamConfig;
      locationClass = bucketMetadata.locationClass;
      loggingConfig = bucketMetadata.loggingConfig;
    }

    @Override
    public BucketMetadata.BucketBuilder setName(String bucketName) {
      this.bucketName = checkNotNull(bucketName);
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setGeneratedId(String generatedIdentifier) {
      this.generatedIdentifier = generatedIdentifier;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setOwner(TypedEntity ownerEntity) {
      this.ownerEntity = ownerEntity;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setSelfLink(String selfUrl) {
      this.selfUrl = selfUrl;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setVersioningEnabled(Boolean versioningEnabled) {
      this.versioningActive = firstNonNull(versioningEnabled, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setRequesterPays(Boolean versioningEnabled) {
      this.requesterPaysEnabled = firstNonNull(versioningEnabled, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setIndexPage(String mainIndexPage) {
      this.mainIndexPage = mainIndexPage;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setNotFoundPage(String notFoundHtml) {
      this.notFoundHtml = notFoundHtml;
      return this;
    }

    /** @deprecated Use {@code setLifecycleRules} method instead. * */
    @Override
    @Deprecated
    public BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends AbstractDeleteRule> deleteDefinitions) {
      this.deletionRules = deleteDefinitions != null ? ImmutableList.copyOf(deleteDefinitions) : null;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deleteDefinitions) {
      this.lifecycleDefinitions = deleteDefinitions != null ? ImmutableList.copyOf(deleteDefinitions) : null;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setStorageClass(StorageTier storageTier) {
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
    BucketMetadata.BucketBuilder setCreateTime(Long creationTime) {
      this.creationTime = creationTime;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setMetageneration(Long metaGenerationId) {
      this.metaGenerationId = metaGenerationId;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setCors(Iterable<Cors> corsRules) {
      this.corsRules = corsRules != null ? ImmutableList.copyOf(corsRules) : null;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setAcl(Iterable<AccessControlEntry> accessControlList) {
      this.accessControlList = accessControlList != null ? ImmutableList.copyOf(accessControlList) : null;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setDefaultAcl(Iterable<AccessControlEntry> accessControlList) {
      this.defaultAccessControlList = accessControlList != null ? ImmutableList.copyOf(accessControlList) : null;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setLabels(Map<String, String> labelMap) {
      if (labelMap != null) {
        this.labelMap =
            Maps.transformValues(
                    labelMap,
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
    public BucketMetadata.BucketBuilder setDefaultKmsKeyName(String defaultKmsKey) {
      this.defaultKmsKey =
          defaultKmsKey != null ? defaultKmsKey : Data.<String>nullOf(String.class);
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setDefaultEventBasedHold(Boolean defaultEventHold) {
      this.defaultEventHold =
          firstNonNull(defaultEventHold, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setRetentionEffectiveTime(Long retentionStartTime) {
      this.retentionStartTime =
          firstNonNull(retentionStartTime, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyLocked) {
      this.retentionPolicyLocked =
          firstNonNull(retentionPolicyLocked, Data.<Boolean>nullOf(Boolean.class));
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setRetentionPeriod(Long retentionDuration) {
      this.retentionDuration = firstNonNull(retentionDuration, Data.<Long>nullOf(Long.class));
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setIamConfiguration(BucketIamConfiguration bucketIamConfig) {
      this.bucketIamConfig = bucketIamConfig;
      return this;
    }

    @Override
    public BucketMetadata.BucketBuilder setLogging(LoggingConfig loggingConfig) {
      this.loggingConfig = loggingConfig;
      return this;
    }

    @Override
    BucketMetadata.BucketBuilder setLocationType(String locationClass) {
      this.locationClass = locationClass;
      return this;
    }

    @Override
    public BucketMetadata buildBucket() {
      checkNotNull(bucketName);
      return new BucketMetadata(this);
    }
  }

  BucketMetadata(BucketBuilderImpl uniformAccessBuilder) {
    generatedIdentifier = uniformAccessBuilder.generatedIdentifier;
    bucketName = uniformAccessBuilder.bucketName;
    entityTag = uniformAccessBuilder.entityTag;
    creationTime = uniformAccessBuilder.creationTime;
    metaGenerationId = uniformAccessBuilder.metaGenerationId;
    region = uniformAccessBuilder.region;
    storageTier = uniformAccessBuilder.storageTier;
    corsRules = uniformAccessBuilder.corsRules;
    accessControlList = uniformAccessBuilder.accessControlList;
    defaultAccessControlList = uniformAccessBuilder.defaultAccessControlList;
    ownerEntity = uniformAccessBuilder.ownerEntity;
    selfUrl = uniformAccessBuilder.selfUrl;
    versioningActive = uniformAccessBuilder.versioningActive;
    mainIndexPage = uniformAccessBuilder.mainIndexPage;
    notFoundHtml = uniformAccessBuilder.notFoundHtml;
    deletionRules = uniformAccessBuilder.deletionRules;
    lifecycleDefinitions = uniformAccessBuilder.lifecycleDefinitions;
    labelMap = uniformAccessBuilder.labelMap;
    requesterPaysEnabled = uniformAccessBuilder.requesterPaysEnabled;
    defaultKmsKey = uniformAccessBuilder.defaultKmsKey;
    defaultEventHold = uniformAccessBuilder.defaultEventHold;
    retentionStartTime = uniformAccessBuilder.retentionStartTime;
    retentionPolicyLocked = uniformAccessBuilder.retentionPolicyLocked;
    retentionDuration = uniformAccessBuilder.retentionDuration;
    bucketIamConfig = uniformAccessBuilder.bucketIamConfig;
    locationClass = uniformAccessBuilder.locationClass;
    loggingConfig = uniformAccessBuilder.loggingConfig;
  }

  /** Returns the service-generated id for the bucket. */
  public String getGeneratedId() {
    return generatedIdentifier;
  }

  /** Returns the bucket's name. */
  public String getName() {
    return bucketName;
  }

  /** Returns the bucket's owner. This is always the project team's owner group. */
  public AccessControlEntry.TypedEntity getOwner() {
    return ownerEntity;
  }

  /** Returns the URI of this bucket as a string. */
  public String getSelfLink() {
    return selfUrl;
  }

  /**
   * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
   * false}.
   *
   * <p>Case 1: {@code true} the field {@link
   * StorageClient.BucketMetadataField#VERSIONING} is selected in a {@link
   * StorageClient#get(String, StorageClient.BucketGetOptions...)} and versions for the bucket is enabled.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * StorageClient.BucketMetadataField#VERSIONING} is selected in a {@link
   * StorageClient#get(String, StorageClient.BucketGetOptions...)}, but versions for the bucket is not enabled.
   * This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * StorageClient.BucketMetadataField#VERSIONING} is not selected in a {@link
   * StorageClient#get(String, StorageClient.BucketGetOptions...)}, and the state for this field is unknown.
   *
   * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
   * request for example {@link StorageClient#update(BucketMetadata, StorageClient.BucketTargetRequestOption...)} in which
   * case the value of versions will remain {@code false} for for the given instance.
   */
  public Boolean isVersioningEnabled() {
    return Data.isNull(versioningActive) ? null : versioningActive;
  }

  /**
   * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
   * {@code null}.
   *
   * <p>Case 1: {@code true} the field {@link StorageClient.BucketMetadataField#BILLING}
   * is selected in a {@link StorageClient#get(String, StorageClient.BucketGetOptions...)} and requester pays for
   * the bucket is enabled.
   *
   * <p>Case 2: {@code false} the field {@link StorageClient.BucketMetadataField#BILLING}
   * in a {@link StorageClient#get(String, StorageClient.BucketGetOptions...)} is selected and requester pays for
   * the bucket is disable.
   *
   * <p>Case 3: {@code null} the field {@link StorageClient.BucketMetadataField#BILLING}
   * in a {@link StorageClient#get(String, StorageClient.BucketGetOptions...)} is not selected, the value is
   * unknown.
   */
  public Boolean isRequesterPays() {
    return Data.isNull(requesterPaysEnabled) ? null : requesterPaysEnabled;
  }

  /**
   * Returns bucket's website index page. Behaves as the bucket's directory index where missing
   * blobs are treated as potential directories.
   */
  public String getIndexPage() {
    return mainIndexPage;
  }

  /** Returns the custom object to return when a requested resource is not found. */
  public String getNotFoundPage() {
    return notFoundHtml;
  }

  /**
   * Returns bucket's lifecycle configuration as a number of delete rules.
   *
   * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
   */
  @Deprecated
  public List<? extends AbstractDeleteRule> getDeleteRules() {
    return deletionRules;
  }

  public List<? extends LifecycleRuleDefinition> getLifecycleRules() {
    return lifecycleDefinitions;
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

  /** Returns the metadata generation of this bucket. */
  public Long getMetageneration() {
    return metaGenerationId;
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
    return locationClass;
  }

  /**
   * Returns the bucket's storage class. This defines how blobs in the bucket are stored and
   * determines the SLA and the cost of storage.
   *
   * @see <a href="https://cloud.google.com/storage/docs/storage-classes">Storage Classes</a>
   */
  public StorageTier getStorageClass() {
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
    return labelMap;
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
   * StorageClient.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
   * StorageClient#get(String, StorageClient.BucketGetOptions...)} and default event-based hold for the bucket is
   * enabled.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * StorageClient.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
   * StorageClient#get(String, StorageClient.BucketGetOptions...)}, but default event-based hold for the bucket
   * is not enabled. This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * StorageClient.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is not selected in a
   * {@link StorageClient#get(String, StorageClient.BucketGetOptions...)}, and the state for this field is
   * unknown.
   *
   * <p>Case 3: {@code false} default event-based hold is explicitly set to false using in a {@link
   * BucketBuilder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
   * StorageClient#update(BucketMetadata, StorageClient.BucketTargetRequestOption...)} in which case the value of default
   * event-based hold will remain {@code false} for the given instance.
   */
  @BetaApi
  public Boolean getDefaultEventBasedHold() {
    return Data.isNull(defaultEventHold) ? null : defaultEventHold;
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
   * StorageClient.BucketMetadataField#RETENTION_POLICY} is selected in a {@link
   * StorageClient#get(String, StorageClient.BucketGetOptions...)} and retention policy for the bucket is locked.
   *
   * <p>Case 2.1: {@code null} the field {@link
   * StorageClient.BucketMetadataField#RETENTION_POLICY} is selected in a {@link
   * StorageClient#get(String, StorageClient.BucketGetOptions...)}, but retention policy for the bucket is not
   * locked. This case can be considered implicitly {@code false}.
   *
   * <p>Case 2.2: {@code null} the field {@link
   * StorageClient.BucketMetadataField#RETENTION_POLICY} is not selected in a {@link
   * StorageClient#get(String, StorageClient.BucketGetOptions...)}, and the state for this field is unknown.
   */
  @BetaApi
  public Boolean isRetentionPolicyLocked() {
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
    return bucketIamConfig;
  }

  /** Returns the Logging */
  public LoggingConfig getLogging() {
    return loggingConfig;
  }

  /** Returns a builder for the current bucket. */
  public BucketBuilder toBucketBuilder() {
    return new BucketBuilderImpl(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(bucketName);
  }

  @Override
  public boolean equals(Object candidate) {
    return candidate == this
        || candidate != null
            && candidate.getClass().equals(BucketMetadata.class)
            && Objects.equals(toProto(), ((BucketMetadata) candidate).toProto());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("name", bucketName).toString();
  }

  com.google.api.services.storage.model.Bucket toProto() {
    com.google.api.services.storage.model.Bucket bucketProto =
        new com.google.api.services.storage.model.Bucket();
    bucketProto.setId(generatedIdentifier);
    bucketProto.setName(bucketName);
    bucketProto.setEtag(entityTag);
    if (creationTime != null) {
      bucketProto.setTimeCreated(new DateTime(creationTime));
    }
    if (metaGenerationId != null) {
      bucketProto.setMetageneration(metaGenerationId);
    }
    if (region != null) {
      bucketProto.setLocation(region);
    }
    if (locationClass != null) {
      bucketProto.setLocationType(locationClass);
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
    if (ownerEntity != null) {
      bucketProto.setOwner(new Owner().setEntity(ownerEntity.toProto()));
    }
    bucketProto.setSelfLink(selfUrl);
    if (versioningActive != null) {
      bucketProto.setVersioning(new Versioning().setEnabled(versioningActive));
    }
    if (requesterPaysEnabled != null) {
      Bucket.Billing billingProto = new Bucket.Billing();
      billingProto.setRequesterPays(requesterPaysEnabled);
      bucketProto.setBilling(billingProto);
    }
    if (mainIndexPage != null || notFoundHtml != null) {
      Website siteConfig = new Website();
      siteConfig.setMainPageSuffix(mainIndexPage);
      siteConfig.setNotFoundPage(notFoundHtml);
      bucketProto.setWebsite(siteConfig);
    }
    Set<Rule> deleteDefinitions = new HashSet<>();
    if (deletionRules != null) {
      deleteDefinitions.addAll(
          transform(
                  deletionRules,
              new Function<AbstractDeleteRule, Rule>() {
                @Override
                public Rule apply(AbstractDeleteRule deleteRule) {
                  return deleteRule.toProto();
                }
              }));
    }
    if (lifecycleDefinitions != null) {
      deleteDefinitions.addAll(
          transform(
                  lifecycleDefinitions,
              new Function<LifecycleRuleDefinition, Rule>() {
                @Override
                public Rule apply(LifecycleRuleDefinition lifecycleRule) {
                  return lifecycleRule.toProto();
                }
              }));
    }
    if (!deleteDefinitions.isEmpty()) {
      Lifecycle lifecycleProto = new Lifecycle();
      lifecycleProto.setRule(ImmutableList.copyOf(deleteDefinitions));
      bucketProto.setLifecycle(lifecycleProto);
    }
    if (labelMap != null) {
      bucketProto.setLabels(labelMap);
    }
    if (defaultKmsKey != null) {
      bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(defaultKmsKey));
    }
    if (defaultEventHold != null) {
      bucketProto.setDefaultEventBasedHold(defaultEventHold);
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
    if (bucketIamConfig != null) {
      bucketProto.setIamConfiguration(bucketIamConfig.toProto());
    }
    if (loggingConfig != null) {
      bucketProto.setLogging(loggingConfig.toProto());
    }
    return bucketProto;
  }

  /** Creates a {@code BucketInfo} object for the provided bucket name. */
  public static BucketMetadata ofName(String bucketName) {
    return newBucketBuilder(bucketName).buildBucket();
  }

  /** Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name. */
  public static BucketBuilder newBucketBuilder(String bucketName) {
    return new BucketBuilderImpl(bucketName);
  }

  static BucketMetadata fromProto(com.google.api.services.storage.model.Bucket bucketProto) {
    BucketBuilder uniformAccessBuilder = new BucketBuilderImpl(bucketProto.getName());
    if (bucketProto.getId() != null) {
      uniformAccessBuilder.setGeneratedId(bucketProto.getId());
    }
    if (bucketProto.getEtag() != null) {
      uniformAccessBuilder.setEtag(bucketProto.getEtag());
    }
    if (bucketProto.getMetageneration() != null) {
      uniformAccessBuilder.setMetageneration(bucketProto.getMetageneration());
    }
    if (bucketProto.getSelfLink() != null) {
      uniformAccessBuilder.setSelfLink(bucketProto.getSelfLink());
    }
    if (bucketProto.getTimeCreated() != null) {
      uniformAccessBuilder.setCreateTime(bucketProto.getTimeCreated().getValue());
    }
    if (bucketProto.getLocation() != null) {
      uniformAccessBuilder.setLocation(bucketProto.getLocation());
    }
    if (bucketProto.getStorageClass() != null) {
      uniformAccessBuilder.setStorageClass(StorageTier.fromValue(bucketProto.getStorageClass()));
    }
    if (bucketProto.getCors() != null) {
      uniformAccessBuilder.setCors(transform(bucketProto.getCors(), Cors.FROM_PB_FUNCTION));
    }
    if (bucketProto.getAcl() != null) {
      uniformAccessBuilder.setAcl(
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
      uniformAccessBuilder.setDefaultAcl(
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
      uniformAccessBuilder.setOwner(TypedEntity.fromProto(bucketProto.getOwner().getEntity()));
    }
    if (bucketProto.getVersioning() != null) {
      uniformAccessBuilder.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
    }
    Website siteConfig = bucketProto.getWebsite();
    if (siteConfig != null) {
      uniformAccessBuilder.setIndexPage(siteConfig.getMainPageSuffix());
      uniformAccessBuilder.setNotFoundPage(siteConfig.getNotFoundPage());
    }
    if (bucketProto.getLifecycle() != null && bucketProto.getLifecycle().getRule() != null) {
      uniformAccessBuilder.setLifecycleRules(
          transform(
              bucketProto.getLifecycle().getRule(),
              new Function<Rule, LifecycleRuleDefinition>() {
                @Override
                public BucketMetadata.LifecycleRuleDefinition apply(Rule rule) {
                  return LifecycleRuleDefinition.fromProto(rule);
                }
              }));
      uniformAccessBuilder.setDeleteRules(
          transform(
              bucketProto.getLifecycle().getRule(),
              new Function<Rule, AbstractDeleteRule>() {
                @Override
                public BucketMetadata.AbstractDeleteRule apply(Rule rule) {
                  return AbstractDeleteRule.fromProto(rule);
                }
              }));
    }
    if (bucketProto.getLabels() != null) {
      uniformAccessBuilder.setLabels(bucketProto.getLabels());
    }
    Bucket.Billing billingProto = bucketProto.getBilling();
    if (billingProto != null) {
      uniformAccessBuilder.setRequesterPays(billingProto.getRequesterPays());
    }
    Encryption cryptoConfig = bucketProto.getEncryption();
    if (cryptoConfig != null
        && cryptoConfig.getDefaultKmsKeyName() != null
        && !cryptoConfig.getDefaultKmsKeyName().isEmpty()) {
      uniformAccessBuilder.setDefaultKmsKeyName(cryptoConfig.getDefaultKmsKeyName());
    }
    if (bucketProto.getDefaultEventBasedHold() != null) {
      uniformAccessBuilder.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
    }
    Bucket.RetentionPolicy retentionConfig = bucketProto.getRetentionPolicy();
    if (retentionConfig != null) {
      if (retentionConfig.getEffectiveTime() != null) {
        uniformAccessBuilder.setRetentionEffectiveTime(retentionConfig.getEffectiveTime().getValue());
      }
      if (retentionConfig.getIsLocked() != null) {
        uniformAccessBuilder.setRetentionPolicyIsLocked(retentionConfig.getIsLocked());
      }
      if (retentionConfig.getRetentionPeriod() != null) {
        uniformAccessBuilder.setRetentionPeriod(retentionConfig.getRetentionPeriod());
      }
    }
    Bucket.IamConfiguration bucketIamConfig = bucketProto.getIamConfiguration();

    if (bucketProto.getLocationType() != null) {
      uniformAccessBuilder.setLocationType(bucketProto.getLocationType());
    }

    if (bucketIamConfig != null) {
      uniformAccessBuilder.setIamConfiguration(BucketIamConfiguration.fromProto(bucketIamConfig));
    }
    Bucket.Logging loggingConfig = bucketProto.getLogging();
    if (loggingConfig != null) {
      uniformAccessBuilder.setLogging(LoggingConfig.fromProto(loggingConfig));
    }
    return uniformAccessBuilder.buildBucket();
  }
}
