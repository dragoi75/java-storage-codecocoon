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
import com.google.cloud.storage.AclEntry.AbstractEntity;
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

    static final Function<com.google.api.services.storage.model.Bucket, BucketMetadata> FROM_PB_FUNCTION = new Function<com.google.api.services.storage.model.Bucket, BucketMetadata>() {

        @Override
        public BucketMetadata apply(com.google.api.services.storage.model.Bucket pb) {
            return BucketMetadata.fromProto(pb);
        }
    };

    static final Function<BucketMetadata, com.google.api.services.storage.model.Bucket> TO_METADATA_PROTOBUF = new Function<BucketMetadata, com.google.api.services.storage.model.Bucket>() {

        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketMetadata bucketInfo) {
            return bucketInfo.toProto();
        }
    };

    private static final long serialVersionUID = -4712013629621638459L;

    private final String generatedIdentifier;

    private final String bucketLabel;

    private final AbstractEntity principalEntity;

    private final String resourceLink;

    private final Boolean billingRequired;

    private final Boolean versioningActive;

    private final String indexDocument;

    private final String missingPage;

    private final List<DeleteActionRule> deletionRules;

    private final List<LifecycleRuleDefinition> lifecyclePolicies;

    private final String entityTag;

    private final Long creationTime;

    private final Long metaGenerationNumber;

    private final List<Cors> corsRules;

    private final List<AclEntry> accessControlList;

    private final List<AclEntry> defaultAccessControl;

    private final String region;

    private final StorageClass storageTier;

    private final Map<String, String> tagsMap;

    private final String defaultKmsKey;

    private final Boolean eventHoldDefault;

    private final Long retentionEffectiveTimestamp;

    private final Boolean retentionPolicyLocked;

    private final Long retentionDuration;

    private final IamSettings iamSettings;

    private final String locationScope;

    private final BucketLogging accessLogging;

    /**
     * The Bucket's IAM Configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/uniform-bucket-level-access">uniform
     *     bucket-level access</a>
     */
    public static class IamSettings implements Serializable {

        private static final long serialVersionUID = -8671736104909424616L;

        private Boolean uniformAccessEnabled;

        private Long uniformAccessLockedTime;

        @Override
        public boolean equals(Object objForComparison) {
            if (objForComparison == this)
                return true;
            if (null == objForComparison || objForComparison.getClass() != getClass()) {
                return false;
            }
            IamSettings thatSettings = (IamSettings) objForComparison;
            return Objects.equals(toProto(), thatSettings.toProto());
        }

        @Override
        public int hashCode() {
            return Objects.hash(uniformAccessEnabled, uniformAccessLockedTime);
        }

        private IamSettings(UniformBucketLevelAccessBuilder uniformAccessConfig) {
            this.uniformAccessEnabled = uniformAccessConfig.uniformAccessEnabled;
            this.uniformAccessLockedTime = uniformAccessConfig.uniformAccessLockedTime;
        }

        public static UniformBucketLevelAccessBuilder newIamSettingsBuilder() {
            return new UniformBucketLevelAccessBuilder();
        }

        public UniformBucketLevelAccessBuilder toUniformBucketLevelAccessBuilder() {
            UniformBucketLevelAccessBuilder uniformAccessConfig = new UniformBucketLevelAccessBuilder();
            uniformAccessConfig.uniformAccessEnabled = uniformAccessEnabled;
            uniformAccessConfig.uniformAccessLockedTime = uniformAccessLockedTime;
            return uniformAccessConfig;
        }

        /**
         * Deprecated in favor of isUniformBucketLevelAccessEnabled().
         */
        @Deprecated
        public Boolean isBucketPolicyOnlyEnabled() {
            return uniformAccessEnabled;
        }

        /**
         * Deprecated in favor of uniformBucketLevelAccessLockedTime().
         */
        @Deprecated
        public Long getBucketPolicyOnlyLockedTime() {
            return uniformAccessLockedTime;
        }

        public Boolean isUniformBucketLevelAccessEnabled() {
            return uniformAccessEnabled;
        }

        public Long getUniformBucketLevelAccessLockedTime() {
            return uniformAccessLockedTime;
        }

        Bucket.IamConfiguration toProto() {
            Bucket.IamConfiguration iamSettings = new Bucket.IamConfiguration();
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccess = new Bucket.IamConfiguration.UniformBucketLevelAccess();
            uniformAccess.setEnabled(uniformAccessEnabled);
            uniformAccess.setLockedTime(null == uniformAccessLockedTime ? null : new DateTime(uniformAccessLockedTime));
            iamSettings.setUniformBucketLevelAccess(uniformAccess);
            return iamSettings;
        }

        static IamSettings fromProto(Bucket.IamConfiguration iamSettings) {
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccess = iamSettings.getUniformBucketLevelAccess();
            DateTime lockTimestamp = uniformAccess.getLockedTime();
            return newIamSettingsBuilder().setIsUniformBucketLevelAccessEnabled(uniformAccess.getEnabled()).setUniformBucketLevelAccessLockedTime(null == lockTimestamp ? null : lockTimestamp.getValue()).buildIamSettings();
        }

        /**
         * Builder for {@code IamConfiguration}
         */
        public static class UniformBucketLevelAccessBuilder {

            private Boolean uniformAccessEnabled;

            private Long uniformAccessLockedTime;

            /**
             * Deprecated in favor of setIsUniformBucketLevelAccessEnabled().
             */
            @Deprecated
            public BucketMetadata.IamSettings.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean bucketPolicyOnlyActive) {
                this.uniformAccessEnabled = bucketPolicyOnlyActive;
                return this;
            }

            /**
             * Deprecated in favor of setUniformBucketLevelAccessLockedTime().
             */
            @Deprecated
            BucketMetadata.IamSettings.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long policyOnlyLockTime) {
                this.uniformAccessLockedTime = policyOnlyLockTime;
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
            public UniformBucketLevelAccessBuilder setIsUniformBucketLevelAccessEnabled(Boolean uniformAccessEnabled) {
                this.uniformAccessEnabled = uniformAccessEnabled;
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

            /**
             * Builds an {@code IamConfiguration} object
             */
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

        private static final long serialVersionUID = -708892101216778492L;

        private String loggingBucket;

        private String loggingObjectPrefix;

        @Override
        public boolean equals(Object objForComparison) {
            if (objForComparison == this)
                return true;
            if (null == objForComparison || objForComparison.getClass() != getClass()) {
                return false;
            }
            BucketLogging thatSettings = (BucketLogging) objForComparison;
            return Objects.equals(toProto(), thatSettings.toProto());
        }

        @Override
        public int hashCode() {
            return Objects.hash(loggingBucket, loggingObjectPrefix);
        }

        public static LogBucketBuilder newLogBucketBuilder() {
            return new LogBucketBuilder();
        }

        public LogBucketBuilder toBuilder() {
            LogBucketBuilder uniformAccessConfig = new LogBucketBuilder();
            uniformAccessConfig.loggingBucket = loggingBucket;
            uniformAccessConfig.loggingObjectPrefix = loggingObjectPrefix;
            return uniformAccessConfig;
        }

        public String getLogBucket() {
            return loggingBucket;
        }

        public String getLogObjectPrefix() {
            return loggingObjectPrefix;
        }

        Bucket.Logging toProto() {
            Bucket.Logging accessLogging = new Bucket.Logging();
            accessLogging.setLogBucket(loggingBucket);
            accessLogging.setLogObjectPrefix(loggingObjectPrefix);
            return accessLogging;
        }

        static BucketLogging fromProto(Bucket.Logging accessLogging) {
            return newLogBucketBuilder().setLogBucket(accessLogging.getLogBucket()).setLogObjectPrefix(accessLogging.getLogObjectPrefix()).buildBucketLogging();
        }

        private BucketLogging(LogBucketBuilder uniformAccessConfig) {
            this.loggingBucket = uniformAccessConfig.loggingBucket;
            this.loggingObjectPrefix = uniformAccessConfig.loggingObjectPrefix;
        }

        public static class LogBucketBuilder {

            private String loggingBucket;

            private String loggingObjectPrefix;

            /**
             * The destination bucket where the current bucket's logs should be placed.
             */
            public LogBucketBuilder setLogBucket(String loggingBucket) {
                this.loggingBucket = loggingBucket;
                return this;
            }

            /**
             * A prefix for log object names.
             */
            public LogBucketBuilder setLogObjectPrefix(String loggingObjectPrefix) {
                this.loggingObjectPrefix = loggingObjectPrefix;
                return this;
            }

            /**
             * Builds an {@code Logging} object
             */
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
    public static class LifecycleRuleDefinition implements Serializable {

        private static final long serialVersionUID = -5739807320148748613L;

        private final LifecycleOperation ruleAction;

        private final LifecycleRuleCondition ruleCondition;

        public LifecycleRuleDefinition(LifecycleOperation ruleAction, LifecycleRuleCondition ruleCondition) {
            if (null == ruleCondition.getIsLive() && null == ruleCondition.getAge() && null == ruleCondition.getCreatedBefore() && null == ruleCondition.getMatchesStorageClass() && null == ruleCondition.getNumberOfNewerVersions()) {
                throw new IllegalArgumentException("You must specify at least one condition to use object lifecycle " + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
            }
            this.ruleAction = ruleAction;
            this.ruleCondition = ruleCondition;
        }

        public LifecycleOperation getAction() {
            return ruleAction;
        }

        public LifecycleRuleCondition getCondition() {
            return ruleCondition;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("lifecycleAction", ruleAction).add("lifecycleCondition", ruleCondition).toString();
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleAction, ruleCondition);
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj == this) {
                return true;
            }
            if (null == otherObj || otherObj.getClass() != getClass()) {
                return false;
            }
            final LifecycleRuleDefinition thatSettings = (LifecycleRuleDefinition) otherObj;
            return Objects.equals(toProto(), thatSettings.toProto());
        }

        Rule toProto() {
            Rule protoRule = new Rule();
            Rule.Action ruleAction = new Rule.Action().setType(this.ruleAction.getActionType());
            if (this.ruleAction.getActionType().equals(UpdateStorageClassLifecycleAction.TYPE)) {
                ruleAction.setStorageClass(((UpdateStorageClassLifecycleAction) this.ruleAction).getStorageClass().toString());
            }
            protoRule.setAction(ruleAction);
            Rule.Condition ruleCondition = new Rule.Condition().setAge(this.ruleCondition.getAge()).setCreatedBefore(null == this.ruleCondition.getCreatedBefore() ? null : new DateTime(true, this.ruleCondition.getCreatedBefore().getValue(), 0)).setIsLive(this.ruleCondition.getIsLive()).setNumNewerVersions(this.ruleCondition.getNumberOfNewerVersions()).setMatchesStorageClass(null == this.ruleCondition.getMatchesStorageClass() ? null : transform(this.ruleCondition.getMatchesStorageClass(), Functions.toStringFunction()));
            protoRule.setCondition(ruleCondition);
            return protoRule;
        }

        static LifecycleRuleDefinition fromProto(Rule protoRule) {
            LifecycleOperation ruleAction;
            Rule.Action lifecycleActionParam = protoRule.getAction();
            switch(lifecycleActionParam.getType()) {
                case RemoveLifecycleAction.TYPE:
                    ruleAction = LifecycleOperation.createDeleteAction();
                    break;
                case UpdateStorageClassLifecycleAction.TYPE:
                    ruleAction = LifecycleOperation.createSetStorageClassAction(StorageClass.valueOf(lifecycleActionParam.getStorageClass()));
                    break;
                default:
                    throw new UnsupportedOperationException("The specified lifecycle action " + lifecycleActionParam.getType() + " is not currently supported");
            }
            Rule.Condition ruleCondition = protoRule.getCondition();
            LifecycleRuleCondition.LifecycleRuleConditionBuilder condBuilder = LifecycleRuleCondition.newLifecycleRuleConditionBuilder().setAge(ruleCondition.getAge()).setCreatedBefore(ruleCondition.getCreatedBefore()).setIsLive(ruleCondition.getIsLive()).setNumberOfNewerVersions(ruleCondition.getNumNewerVersions()).setMatchesStorageClass(null == ruleCondition.getMatchesStorageClass() ? null : transform(ruleCondition.getMatchesStorageClass(), new Function<String, StorageClass>() {

                public StorageClass apply(String storageClass) {
                    return StorageClass.valueOf(storageClass);
                }
            }));
            return new LifecycleRuleDefinition(ruleAction, condBuilder.buildCondition());
        }

        /**
         * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
         *
         * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
         *     Management</a>
         */
        public static class LifecycleRuleCondition implements Serializable {

            private static final long serialVersionUID = -6482314338394768785L;

            private final Integer lifespanDays;

            private final DateTime creationCutoff;

            private final Integer newerVersionCount;

            private final Boolean activeFlag;

            private final List<StorageClass> matchedStorageClasses;

            private LifecycleRuleCondition(LifecycleRuleConditionBuilder uniformAccessConfig) {
                this.lifespanDays = uniformAccessConfig.lifespanDays;
                this.creationCutoff = uniformAccessConfig.creationCutoff;
                this.newerVersionCount = uniformAccessConfig.newerVersionCount;
                this.activeFlag = uniformAccessConfig.activeFlag;
                this.matchedStorageClasses = uniformAccessConfig.matchedStorageClasses;
            }

            public LifecycleRuleConditionBuilder toBuilder() {
                return newLifecycleRuleConditionBuilder().setAge(this.lifespanDays).setCreatedBefore(this.creationCutoff).setNumberOfNewerVersions(this.newerVersionCount).setIsLive(this.activeFlag).setMatchesStorageClass(this.matchedStorageClasses);
            }

            public static LifecycleRuleConditionBuilder newLifecycleRuleConditionBuilder() {
                return new LifecycleRuleConditionBuilder();
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("age", lifespanDays).add("createBefore", creationCutoff).add("numberofNewerVersions", newerVersionCount).add("isLive", activeFlag).add("matchesStorageClass", matchedStorageClasses).toString();
            }

            public Integer getAge() {
                return lifespanDays;
            }

            public DateTime getCreatedBefore() {
                return creationCutoff;
            }

            public Integer getNumberOfNewerVersions() {
                return newerVersionCount;
            }

            public Boolean getIsLive() {
                return activeFlag;
            }

            public List<StorageClass> getMatchesStorageClass() {
                return matchedStorageClasses;
            }

            /**
             * Builder for {@code LifecycleCondition}.
             */
            public static class LifecycleRuleConditionBuilder {

                private Integer lifespanDays;

                private DateTime creationCutoff;

                private Integer newerVersionCount;

                private Boolean activeFlag;

                private List<StorageClass> matchedStorageClasses;

                private LifecycleRuleConditionBuilder() {
                }

                /**
                 * Sets the age in days. This condition is satisfied when a Blob reaches the specified age
                 * (in days). When you specify the Age condition, you are specifying a Time to Live (TTL)
                 * for objects in a bucket with lifecycle management configured. The time when the Age
                 * condition is considered to be satisfied is calculated by adding the specified value to
                 * the object creation time.
                 */
                public LifecycleRuleConditionBuilder setAge(Integer lifespanDays) {
                    this.lifespanDays = lifespanDays;
                    return this;
                }

                /**
                 * Sets the date a Blob should be created before for an Action to be executed. Note that
                 * only the date will be considered, if the time is specified it will be truncated. This
                 * condition is satisfied when an object is created before midnight of the specified date in
                 * UTC. *
                 */
                public LifecycleRuleConditionBuilder setCreatedBefore(DateTime creationCutoff) {
                    this.creationCutoff = creationCutoff;
                    return this;
                }

                /**
                 * Sets the number of newer versions a Blob should have for an Action to be executed.
                 * Relevant only when versioning is enabled on a bucket. *
                 */
                public LifecycleRuleConditionBuilder setNumberOfNewerVersions(Integer newerVersionCount) {
                    this.newerVersionCount = newerVersionCount;
                    return this;
                }

                /**
                 * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
                 * only live Blobs; if the value is false, it matches only archived objects. For the
                 * purposes of this condition, Blobs in non-versioned buckets are considered live.
                 */
                public LifecycleRuleConditionBuilder setIsLive(Boolean active) {
                    this.activeFlag = active;
                    return this;
                }

                /**
                 * Sets a list of Storage Classes for a objects that satisfy the condition to execute the
                 * Action. *
                 */
                public LifecycleRuleConditionBuilder setMatchesStorageClass(List<StorageClass> matchedStorageClasses) {
                    this.matchedStorageClasses = matchedStorageClasses;
                    return this;
                }

                /**
                 * Builds a {@code LifecycleCondition} object. *
                 */
                public LifecycleRuleCondition buildCondition() {
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
            public static RemoveLifecycleAction createDeleteAction() {
                return new RemoveLifecycleAction();
            }

            /**
             * Creates a new {@code SetStorageClassLifecycleAction}. A Blob's storage class that meets the
             * action's conditions will be changed to the specified storage class.
             *
             * @param storageTier The new storage class to use when conditions are met for this action.
             */
            public static UpdateStorageClassLifecycleAction createSetStorageClassAction(StorageClass storageTier) {
                return new UpdateStorageClassLifecycleAction(storageTier);
            }
        }

        public static class RemoveLifecycleAction extends LifecycleOperation {

            public static final String TYPE = "Delete";

            private static final long serialVersionUID = -2050986302222644873L;

            private RemoveLifecycleAction() {
            }

            @Override
            public String getActionType() {
                return TYPE;
            }
        }

        public static class UpdateStorageClassLifecycleAction extends LifecycleOperation {

            public static final String TYPE = "SetStorageClass";

            private static final long serialVersionUID = -62615467186000899L;

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
                return MoreObjects.toStringHelper(this).add("actionType", getActionType()).add("storageClass", storageTier.name()).toString();
            }

            public StorageClass getStorageClass() {
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
    public abstract static class DeleteActionRule implements Serializable {

        private static final long serialVersionUID = 3137971668395933033L;

        private static final String SUPPORTED_ACTIONS = "Delete";

        private final VersionFilterType filterType;

        public enum VersionFilterType {

            AGE, CREATE_BEFORE, NUM_NEWER_VERSIONS, IS_LIVE, UNKNOWN
        }

        DeleteActionRule(VersionFilterType filterType) {
            this.filterType = filterType;
        }

        public VersionFilterType getType() {
            return filterType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(filterType);
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj == this) {
                return true;
            }
            if (null == otherObj || otherObj.getClass() != getClass()) {
                return false;
            }
            final DeleteActionRule thatSettings = (DeleteActionRule) otherObj;
            return Objects.equals(toProto(), thatSettings.toProto());
        }

        Rule toProto() {
            Rule protoRule = new Rule();
            protoRule.setAction(new Rule.Action().setType(SUPPORTED_ACTIONS));
            Rule.Condition ruleCondition = new Rule.Condition();
            populateRuleCondition(ruleCondition);
            protoRule.setCondition(ruleCondition);
            return protoRule;
        }

        abstract void populateRuleCondition(Rule.Condition condition);

        static DeleteActionRule fromProto(Rule protoRule) {
            if (null != protoRule.getAction() && SUPPORTED_ACTIONS.endsWith(protoRule.getAction().getType())) {
                Rule.Condition ruleCondition = protoRule.getCondition();
                Integer lifespanDays = ruleCondition.getAge();
                if (null != lifespanDays) {
                    return new AgeBasedDeleteRule(lifespanDays);
                }
                DateTime creationTime = ruleCondition.getCreatedBefore();
                if (null != creationTime) {
                    return new CreatedBeforeDeletionRule(creationTime.getValue());
                }
                Integer newerVersionCount = ruleCondition.getNumNewerVersions();
                if (null != newerVersionCount) {
                    return new NumNewerVersionsDeletionRule(newerVersionCount);
                }
                Boolean activeFlag = ruleCondition.getIsLive();
                if (null != activeFlag) {
                    return new LiveDeleteRule(activeFlag);
                }
            }
            return new UnprocessedDeleteRule(protoRule);
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
    public static class AgeBasedDeleteRule extends DeleteActionRule {

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
            super(VersionFilterType.AGE);
            this.ttlDays = ttlDays;
        }

        public int getDaysToLive() {
            return ttlDays;
        }

        @Override
        void populateRuleCondition(Rule.Condition ruleCondition) {
            ruleCondition.setAge(ttlDays);
        }
    }

    static class UnprocessedDeleteRule extends DeleteActionRule {

        private static final long serialVersionUID = -7166938278642301933L;

        private transient Rule protoRule;

        UnprocessedDeleteRule(Rule protoRule) {
            super(VersionFilterType.UNKNOWN);
            this.protoRule = protoRule;
        }

        @Override
        void populateRuleCondition(Rule.Condition condition) {
            throw new UnsupportedOperationException();
        }

        private void writeObject(ObjectOutputStream objectStream) throws IOException {
            objectStream.defaultWriteObject();
            objectStream.writeUTF(protoRule.toString());
        }

        private void readObject(ObjectInputStream objectReader) throws IOException, ClassNotFoundException {
            objectReader.defaultReadObject();
            protoRule = new JacksonFactory().fromString(objectReader.readUTF(), Rule.class);
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
     * @deprecated Use a {@code LifecycleRule} with an action {@code DeleteLifecycleAction} and a
     *     condition {@code LifecycleCondition.Builder.setCreatedBefore} instead.
     */
    @Deprecated
    public static class CreatedBeforeDeletionRule extends DeleteActionRule {

        private static final long serialVersionUID = 881692650279195867L;

        private final long timestampMillis;

        /**
         * Creates an {@code CreatedBeforeDeleteRule} object.
         *
         * @param timestampMillis a date in UTC. Blobs that have been created before midnight of the provided
         *     date meet the delete condition
         */
        public CreatedBeforeDeletionRule(long timestampMillis) {
            super(VersionFilterType.CREATE_BEFORE);
            this.timestampMillis = timestampMillis;
        }

        public long getTimeMillis() {
            return timestampMillis;
        }

        @Override
        void populateRuleCondition(Rule.Condition ruleCondition) {
            ruleCondition.setCreatedBefore(new DateTime(true, timestampMillis, 0));
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
    public static class NumNewerVersionsDeletionRule extends DeleteActionRule {

        private static final long serialVersionUID = -1955554976528303894L;

        private final int newerVersionCount;

        /**
         * Creates an {@code NumNewerVersionsDeleteRule} object.
         *
         * @param newerVersionCount the number of newer versions. A blob's version meets the delete
         *     condition when {@code numNewerVersions} newer versions are available.
         */
        public NumNewerVersionsDeletionRule(int newerVersionCount) {
            super(VersionFilterType.NUM_NEWER_VERSIONS);
            this.newerVersionCount = newerVersionCount;
        }

        public int getNumNewerVersions() {
            return newerVersionCount;
        }

        @Override
        void populateRuleCondition(Rule.Condition ruleCondition) {
            ruleCondition.setNumNewerVersions(newerVersionCount);
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
    public static class LiveDeleteRule extends DeleteActionRule {

        private static final long serialVersionUID = -3502994563121313364L;

        private final boolean activeFlag;

        /**
         * Creates an {@code IsLiveDeleteRule} object.
         *
         * @param activeFlag if set to {@code true} live blobs meet the delete condition. If set to {@code
         *     false} delete condition is met by archived blobs.
         */
        public LiveDeleteRule(boolean activeFlag) {
            super(VersionFilterType.IS_LIVE);
            this.activeFlag = activeFlag;
        }

        public boolean isLive() {
            return activeFlag;
        }

        @Override
        void populateRuleCondition(Rule.Condition ruleCondition) {
            ruleCondition.setIsLive(activeFlag);
        }
    }

    /**
     * Builder for {@code BucketInfo}.
     */
    public abstract static class BucketBuilder {

        BucketBuilder() {
        }

        /**
         * Sets the bucket's name.
         */
        public abstract BucketBuilder setName(String name);

        abstract BucketBuilder setGeneratedId(String generatedId);

        abstract BucketBuilder setOwner(AclEntry.AbstractEntity owner);

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

        /**
         * Sets the custom object to return when a requested resource is not found.
         */
        public abstract BucketBuilder setNotFoundPage(String notFoundPage);

        /**
         * Sets the bucket's lifecycle configuration as a number of delete rules.
         *
         * @deprecated Use {@code setLifecycleRules} instead, as in {@code
         *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRule(
         *     LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(5).build())));}
         */
        @Deprecated
        public abstract BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends DeleteActionRule> rules);

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
        public abstract BucketBuilder setStorageClass(StorageClass storageClass);

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
        public abstract BucketBuilder setAcl(Iterable<AclEntry> acl);

        /**
         * Sets the default access control configuration to apply to bucket's blobs when no other
         * configuration is specified.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract BucketBuilder setDefaultAcl(Iterable<AclEntry> acl);

        /**
         * Sets the label of this bucket.
         */
        public abstract BucketBuilder setLabels(Map<String, String> labels);

        /**
         * Sets the default Cloud KMS key name for this bucket.
         */
        public abstract BucketBuilder setDefaultKmsKeyName(String defaultKmsKeyName);

        /**
         * Sets the default event-based hold for this bucket.
         */
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
        public abstract BucketBuilder setIamConfiguration(IamSettings iamConfiguration);

        public abstract BucketBuilder setLogging(BucketLogging logging);

        /**
         * Creates a {@code BucketInfo} object.
         */
        public abstract BucketMetadata buildBucket();
    }

    static final class BucketBuilderImpl extends BucketBuilder {

        private String generatedIdentifier;

        private String bucketLabel;

        private AclEntry.AbstractEntity principalEntity;

        private String resourceLink;

        private Boolean billingRequired;

        private Boolean versioningActive;

        private String indexDocument;

        private String missingPage;

        private List<DeleteActionRule> deletionRules;

        private List<LifecycleRuleDefinition> lifecyclePolicies;

        private StorageClass storageTier;

        private String region;

        private String entityTag;

        private Long creationTime;

        private Long metaGenerationNumber;

        private List<Cors> corsRules;

        private List<AclEntry> accessControlList;

        private List<AclEntry> defaultAccessControl;

        private Map<String, String> tagsMap;

        private String defaultKmsKey;

        private Boolean eventHoldDefault;

        private Long retentionEffectiveTimestamp;

        private Boolean retentionPolicyLocked;

        private Long retentionDuration;

        private IamSettings iamSettings;

        private String locationScope;

        private BucketLogging accessLogging;

        BucketBuilderImpl(String bucketLabel) {
            this.bucketLabel = bucketLabel;
        }

        BucketBuilderImpl(BucketMetadata bucketMetadata) {
            generatedIdentifier = bucketMetadata.generatedIdentifier;
            bucketLabel = bucketMetadata.bucketLabel;
            entityTag = bucketMetadata.entityTag;
            creationTime = bucketMetadata.creationTime;
            metaGenerationNumber = bucketMetadata.metaGenerationNumber;
            region = bucketMetadata.region;
            storageTier = bucketMetadata.storageTier;
            corsRules = bucketMetadata.corsRules;
            accessControlList = bucketMetadata.accessControlList;
            defaultAccessControl = bucketMetadata.defaultAccessControl;
            principalEntity = bucketMetadata.principalEntity;
            resourceLink = bucketMetadata.resourceLink;
            versioningActive = bucketMetadata.versioningActive;
            indexDocument = bucketMetadata.indexDocument;
            missingPage = bucketMetadata.missingPage;
            deletionRules = bucketMetadata.deletionRules;
            lifecyclePolicies = bucketMetadata.lifecyclePolicies;
            tagsMap = bucketMetadata.tagsMap;
            billingRequired = bucketMetadata.billingRequired;
            defaultKmsKey = bucketMetadata.defaultKmsKey;
            eventHoldDefault = bucketMetadata.eventHoldDefault;
            retentionEffectiveTimestamp = bucketMetadata.retentionEffectiveTimestamp;
            retentionPolicyLocked = bucketMetadata.retentionPolicyLocked;
            retentionDuration = bucketMetadata.retentionDuration;
            iamSettings = bucketMetadata.iamSettings;
            locationScope = bucketMetadata.locationScope;
            accessLogging = bucketMetadata.accessLogging;
        }

        @Override
        public BucketMetadata.BucketBuilder setName(String bucketLabel) {
            this.bucketLabel = checkNotNull(bucketLabel);
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setOwner(AbstractEntity principalEntity) {
            this.principalEntity = principalEntity;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setSelfLink(String resourceLink) {
            this.resourceLink = resourceLink;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setVersioningEnabled(Boolean versioningActive) {
            this.versioningActive = firstNonNull(versioningActive, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setRequesterPays(Boolean versioningActive) {
            this.billingRequired = firstNonNull(versioningActive, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setIndexPage(String indexDocument) {
            this.indexDocument = indexDocument;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setNotFoundPage(String missingPage) {
            this.missingPage = missingPage;
            return this;
        }

        /**
         * @deprecated Use {@code setLifecycleRules} method instead. *
         */
        @Override
        @Deprecated
        public BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends DeleteActionRule> deletePolicies) {
            this.deletionRules = null != deletePolicies ? ImmutableList.copyOf(deletePolicies) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deletePolicies) {
            this.lifecyclePolicies = null != deletePolicies ? ImmutableList.copyOf(deletePolicies) : null;
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
        BucketMetadata.BucketBuilder setCreateTime(Long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setMetageneration(Long metaGenerationNumber) {
            this.metaGenerationNumber = metaGenerationNumber;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setCors(Iterable<Cors> corsRules) {
            this.corsRules = null != corsRules ? ImmutableList.copyOf(corsRules) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setAcl(Iterable<AclEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setDefaultAcl(Iterable<AclEntry> accessControlList) {
            this.defaultAccessControl = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLabels(Map<String, String> tagsMap) {
            if (null != tagsMap) {
                this.tagsMap = Maps.transformValues(tagsMap, new Function<String, String>() {

                    @Override
                    public String apply(String input) {
                        // replace null values with empty strings
                        return null == input ? Data.<String>nullOf(String.class) : input;
                    }
                });
            }
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setDefaultKmsKeyName(String defaultKmsKey) {
            this.defaultKmsKey = null != defaultKmsKey ? defaultKmsKey : Data.<String>nullOf(String.class);
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setDefaultEventBasedHold(Boolean eventHoldDefault) {
            this.eventHoldDefault = firstNonNull(eventHoldDefault, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setRetentionEffectiveTime(Long retentionEffectiveTimestamp) {
            this.retentionEffectiveTimestamp = firstNonNull(retentionEffectiveTimestamp, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyLocked) {
            this.retentionPolicyLocked = firstNonNull(retentionPolicyLocked, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setRetentionPeriod(Long retentionDuration) {
            this.retentionDuration = firstNonNull(retentionDuration, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setIamConfiguration(IamSettings iamSettings) {
            this.iamSettings = iamSettings;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLogging(BucketLogging accessLogging) {
            this.accessLogging = accessLogging;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setLocationType(String locationScope) {
            this.locationScope = locationScope;
            return this;
        }

        @Override
        public BucketMetadata buildBucket() {
            checkNotNull(bucketLabel);
            return new BucketMetadata(this);
        }
    }

    BucketMetadata(BucketBuilderImpl uniformAccessConfig) {
        generatedIdentifier = uniformAccessConfig.generatedIdentifier;
        bucketLabel = uniformAccessConfig.bucketLabel;
        entityTag = uniformAccessConfig.entityTag;
        creationTime = uniformAccessConfig.creationTime;
        metaGenerationNumber = uniformAccessConfig.metaGenerationNumber;
        region = uniformAccessConfig.region;
        storageTier = uniformAccessConfig.storageTier;
        corsRules = uniformAccessConfig.corsRules;
        accessControlList = uniformAccessConfig.accessControlList;
        defaultAccessControl = uniformAccessConfig.defaultAccessControl;
        principalEntity = uniformAccessConfig.principalEntity;
        resourceLink = uniformAccessConfig.resourceLink;
        versioningActive = uniformAccessConfig.versioningActive;
        indexDocument = uniformAccessConfig.indexDocument;
        missingPage = uniformAccessConfig.missingPage;
        deletionRules = uniformAccessConfig.deletionRules;
        lifecyclePolicies = uniformAccessConfig.lifecyclePolicies;
        tagsMap = uniformAccessConfig.tagsMap;
        billingRequired = uniformAccessConfig.billingRequired;
        defaultKmsKey = uniformAccessConfig.defaultKmsKey;
        eventHoldDefault = uniformAccessConfig.eventHoldDefault;
        retentionEffectiveTimestamp = uniformAccessConfig.retentionEffectiveTimestamp;
        retentionPolicyLocked = uniformAccessConfig.retentionPolicyLocked;
        retentionDuration = uniformAccessConfig.retentionDuration;
        iamSettings = uniformAccessConfig.iamSettings;
        locationScope = uniformAccessConfig.locationScope;
        accessLogging = uniformAccessConfig.accessLogging;
    }

    /**
     * Returns the service-generated id for the bucket.
     */
    public String getGeneratedId() {
        return generatedIdentifier;
    }

    /**
     * Returns the bucket's name.
     */
    public String getName() {
        return bucketLabel;
    }

    /**
     * Returns the bucket's owner. This is always the project team's owner group.
     */
    public AbstractEntity getOwner() {
        return principalEntity;
    }

    /**
     * Returns the URI of this bucket as a string.
     */
    public String getSelfLink() {
        return resourceLink;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageService.BucketMetadataField#VERSIONING} is selected in a {@link
     * StorageService#get(String, StorageService.BucketGetOptions...)} and versions for the bucket is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BucketMetadataField#VERSIONING} is selected in a {@link
     * StorageService#get(String, StorageService.BucketGetOptions...)}, but versions for the bucket is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BucketMetadataField#VERSIONING} is not selected in a {@link
     * StorageService#get(String, StorageService.BucketGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
     * request for example {@link StorageService#update(BucketMetadata, StorageService.BucketTargetOptions...)} in which
     * case the value of versions will remain {@code false} for for the given instance.
     */
    public Boolean isVersioningEnabled() {
        return Data.isNull(versioningActive) ? null : versioningActive;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
     * {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link StorageService.BucketMetadataField#BILLING}
     * is selected in a {@link StorageService#get(String, StorageService.BucketGetOptions...)} and requester pays for
     * the bucket is enabled.
     *
     * <p>Case 2: {@code false} the field {@link StorageService.BucketMetadataField#BILLING}
     * in a {@link StorageService#get(String, StorageService.BucketGetOptions...)} is selected and requester pays for
     * the bucket is disable.
     *
     * <p>Case 3: {@code null} the field {@link StorageService.BucketMetadataField#BILLING}
     * in a {@link StorageService#get(String, StorageService.BucketGetOptions...)} is not selected, the value is
     * unknown.
     */
    public Boolean getRequesterPays() {
        return Data.isNull(billingRequired) ? null : billingRequired;
    }

    /**
     * Returns bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public String getIndexPage() {
        return indexDocument;
    }

    /**
     * Returns the custom object to return when a requested resource is not found.
     */
    public String getNotFoundPage() {
        return missingPage;
    }

    /**
     * Returns bucket's lifecycle configuration as a number of delete rules.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
     */
    @Deprecated
    public List<? extends DeleteActionRule> getDeleteRules() {
        return deletionRules;
    }

    public List<? extends LifecycleRuleDefinition> getLifecycleRules() {
        return lifecyclePolicies;
    }

    /**
     * Returns HTTP 1.1 Entity tag for the bucket.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return entityTag;
    }

    /**
     * Returns the time at which the bucket was created.
     */
    public Long getCreateTime() {
        return creationTime;
    }

    /**
     * Returns the metadata generation of this bucket.
     */
    public Long getMetageneration() {
        return metaGenerationNumber;
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
        return locationScope;
    }

    /**
     * Returns the bucket's storage class. This defines how blobs in the bucket are stored and
     * determines the SLA and the cost of storage.
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
    public List<AclEntry> getAcl() {
        return accessControlList;
    }

    /**
     * Returns the default access control configuration for this bucket's blobs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AclEntry> getDefaultAcl() {
        return defaultAccessControl;
    }

    /**
     * Returns the labels for this bucket.
     */
    public Map<String, String> getLabels() {
        return tagsMap;
    }

    /**
     * Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket.
     */
    public String getDefaultKmsKeyName() {
        return defaultKmsKey;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageService.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * StorageService#get(String, StorageService.BucketGetOptions...)} and default event-based hold for the bucket is
     * enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * StorageService#get(String, StorageService.BucketGetOptions...)}, but default event-based hold for the bucket
     * is not enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is not selected in a
     * {@link StorageService#get(String, StorageService.BucketGetOptions...)}, and the state for this field is
     * unknown.
     *
     * <p>Case 3: {@code false} default event-based hold is explicitly set to false using in a {@link
     * BucketBuilder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * StorageService#update(BucketMetadata, StorageService.BucketTargetOptions...)} in which case the value of default
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
        return retentionEffectiveTimestamp;
    }

    /**
     * Returns a {@code Boolean} with either {@code true} or {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageService.BucketMetadataField#RETENTION_POLICY} is selected in a {@link
     * StorageService#get(String, StorageService.BucketGetOptions...)} and retention policy for the bucket is locked.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BucketMetadataField#RETENTION_POLICY} is selected in a {@link
     * StorageService#get(String, StorageService.BucketGetOptions...)}, but retention policy for the bucket is not
     * locked. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BucketMetadataField#RETENTION_POLICY} is not selected in a {@link
     * StorageService#get(String, StorageService.BucketGetOptions...)}, and the state for this field is unknown.
     */
    @BetaApi
    public Boolean isRetentionPolicyLocked() {
        return Data.isNull(retentionPolicyLocked) ? null : retentionPolicyLocked;
    }

    /**
     * Returns the retention policy retention period.
     */
    @BetaApi
    public Long getRetentionPeriod() {
        return retentionDuration;
    }

    /**
     * Returns the IAM configuration
     */
    @BetaApi
    public IamSettings getIamConfiguration() {
        return iamSettings;
    }

    /**
     * Returns the Logging
     */
    public BucketLogging getLogging() {
        return accessLogging;
    }

    /**
     * Returns a builder for the current bucket.
     */
    public BucketBuilder toBucketBuilder() {
        return new BucketBuilderImpl(this);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucketLabel);
    }

    @Override
    public boolean equals(Object otherObj) {
        return this == otherObj || null != otherObj && otherObj.getClass().equals(BucketMetadata.class) && Objects.equals(toProto(), ((BucketMetadata) otherObj).toProto());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", bucketLabel).toString();
    }

    com.google.api.services.storage.model.Bucket toProto() {
        com.google.api.services.storage.model.Bucket protoBucket = new com.google.api.services.storage.model.Bucket();
        protoBucket.setId(generatedIdentifier);
        protoBucket.setName(bucketLabel);
        protoBucket.setEtag(entityTag);
        if (null != creationTime) {
            protoBucket.setTimeCreated(new DateTime(creationTime));
        }
        if (null != metaGenerationNumber) {
            protoBucket.setMetageneration(metaGenerationNumber);
        }
        if (null != region) {
            protoBucket.setLocation(region);
        }
        if (null != locationScope) {
            protoBucket.setLocationType(locationScope);
        }
        if (null != storageTier) {
            protoBucket.setStorageClass(storageTier.toString());
        }
        if (null != corsRules) {
            protoBucket.setCors(transform(corsRules, Cors.TO_PB_FUNCTION));
        }
        if (null != accessControlList) {
            protoBucket.setAcl(transform(accessControlList, new Function<AclEntry, BucketAccessControl>() {

                @Override
                public BucketAccessControl apply(AclEntry acl) {
                    return acl.toBucketProto();
                }
            }));
        }
        if (null != defaultAccessControl) {
            protoBucket.setDefaultObjectAcl(transform(defaultAccessControl, new Function<AclEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AclEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != principalEntity) {
            protoBucket.setOwner(new Owner().setEntity(principalEntity.toProto()));
        }
        protoBucket.setSelfLink(resourceLink);
        if (null != versioningActive) {
            protoBucket.setVersioning(new Versioning().setEnabled(versioningActive));
        }
        if (null != billingRequired) {
            Bucket.Billing billingInfo = new Bucket.Billing();
            billingInfo.setRequesterPays(billingRequired);
            protoBucket.setBilling(billingInfo);
        }
        if (null != indexDocument || null != missingPage) {
            Website websiteConfig = new Website();
            websiteConfig.setMainPageSuffix(indexDocument);
            websiteConfig.setNotFoundPage(missingPage);
            protoBucket.setWebsite(websiteConfig);
        }
        Set<Rule> deletePolicies = new HashSet<>();
        if (null != deletionRules) {
            deletePolicies.addAll(transform(deletionRules, new Function<DeleteActionRule, Rule>() {

                @Override
                public Rule apply(DeleteActionRule deleteRule) {
                    return deleteRule.toProto();
                }
            }));
        }
        if (null != lifecyclePolicies) {
            deletePolicies.addAll(transform(lifecyclePolicies, new Function<LifecycleRuleDefinition, Rule>() {

                @Override
                public Rule apply(LifecycleRuleDefinition lifecycleRule) {
                    return lifecycleRule.toProto();
                }
            }));
        }
        if (!deletePolicies.isEmpty()) {
            Lifecycle lifecycleConfig = new Lifecycle();
            lifecycleConfig.setRule(ImmutableList.copyOf(deletePolicies));
            protoBucket.setLifecycle(lifecycleConfig);
        }
        if (null != tagsMap) {
            protoBucket.setLabels(tagsMap);
        }
        if (null != defaultKmsKey) {
            protoBucket.setEncryption(new Encryption().setDefaultKmsKeyName(defaultKmsKey));
        }
        if (null != eventHoldDefault) {
            protoBucket.setDefaultEventBasedHold(eventHoldDefault);
        }
        if (null != retentionDuration) {
            if (!Data.isNull(retentionDuration)) {
                Bucket.RetentionPolicy retentionConfig = new Bucket.RetentionPolicy();
                retentionConfig.setRetentionPeriod(retentionDuration);
                if (null != retentionEffectiveTimestamp) {
                    retentionConfig.setEffectiveTime(new DateTime(retentionEffectiveTimestamp));
                }
                if (null != retentionPolicyLocked) {
                    retentionConfig.setIsLocked(retentionPolicyLocked);
                }
                protoBucket.setRetentionPolicy(retentionConfig);
            } else {
                protoBucket.setRetentionPolicy(Data.<Bucket.RetentionPolicy>nullOf(Bucket.RetentionPolicy.class));
            }
        }
        if (null != iamSettings) {
            protoBucket.setIamConfiguration(iamSettings.toProto());
        }
        if (null != accessLogging) {
            protoBucket.setLogging(accessLogging.toProto());
        }
        return protoBucket;
    }

    /**
     * Creates a {@code BucketInfo} object for the provided bucket name.
     */
    public static BucketMetadata create(String bucketLabel) {
        return newBucketBuilder(bucketLabel).buildBucket();
    }

    /**
     * Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name.
     */
    public static BucketBuilder newBucketBuilder(String bucketLabel) {
        return new BucketBuilderImpl(bucketLabel);
    }

    static BucketMetadata fromProto(com.google.api.services.storage.model.Bucket protoBucket) {
        BucketBuilder uniformAccessConfig = new BucketBuilderImpl(protoBucket.getName());
        if (null != protoBucket.getId()) {
            uniformAccessConfig.setGeneratedId(protoBucket.getId());
        }
        if (null != protoBucket.getEtag()) {
            uniformAccessConfig.setEtag(protoBucket.getEtag());
        }
        if (null != protoBucket.getMetageneration()) {
            uniformAccessConfig.setMetageneration(protoBucket.getMetageneration());
        }
        if (null != protoBucket.getSelfLink()) {
            uniformAccessConfig.setSelfLink(protoBucket.getSelfLink());
        }
        if (null != protoBucket.getTimeCreated()) {
            uniformAccessConfig.setCreateTime(protoBucket.getTimeCreated().getValue());
        }
        if (null != protoBucket.getLocation()) {
            uniformAccessConfig.setLocation(protoBucket.getLocation());
        }
        if (null != protoBucket.getStorageClass()) {
            uniformAccessConfig.setStorageClass(StorageClass.valueOf(protoBucket.getStorageClass()));
        }
        if (null != protoBucket.getCors()) {
            uniformAccessConfig.setCors(transform(protoBucket.getCors(), Cors.FROM_PB_FUNCTION));
        }
        if (null != protoBucket.getAcl()) {
            uniformAccessConfig.setAcl(transform(protoBucket.getAcl(), new Function<BucketAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(BucketAccessControl bucketAccessControl) {
                    return AclEntry.fromObjectPb(bucketAccessControl);
                }
            }));
        }
        if (null != protoBucket.getDefaultObjectAcl()) {
            uniformAccessConfig.setDefaultAcl(transform(protoBucket.getDefaultObjectAcl(), new Function<ObjectAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(ObjectAccessControl objectAccessControl) {
                    return AclEntry.fromObjectPb(objectAccessControl);
                }
            }));
        }
        if (null != protoBucket.getOwner()) {
            uniformAccessConfig.setOwner(AbstractEntity.fromProto(protoBucket.getOwner().getEntity()));
        }
        if (null != protoBucket.getVersioning()) {
            uniformAccessConfig.setVersioningEnabled(protoBucket.getVersioning().getEnabled());
        }
        Website websiteConfig = protoBucket.getWebsite();
        if (null != websiteConfig) {
            uniformAccessConfig.setIndexPage(websiteConfig.getMainPageSuffix());
            uniformAccessConfig.setNotFoundPage(websiteConfig.getNotFoundPage());
        }
        if (null != protoBucket.getLifecycle() && null != protoBucket.getLifecycle().getRule()) {
            uniformAccessConfig.setLifecycleRules(transform(protoBucket.getLifecycle().getRule(), new Function<Rule, LifecycleRuleDefinition>() {

                @Override
                public BucketMetadata.LifecycleRuleDefinition apply(Rule rule) {
                    return LifecycleRuleDefinition.fromProto(rule);
                }
            }));
            uniformAccessConfig.setDeleteRules(transform(protoBucket.getLifecycle().getRule(), new Function<Rule, DeleteActionRule>() {

                @Override
                public BucketMetadata.DeleteActionRule apply(Rule rule) {
                    return DeleteActionRule.fromProto(rule);
                }
            }));
        }
        if (null != protoBucket.getLabels()) {
            uniformAccessConfig.setLabels(protoBucket.getLabels());
        }
        Bucket.Billing billingInfo = protoBucket.getBilling();
        if (null != billingInfo) {
            uniformAccessConfig.setRequesterPays(billingInfo.getRequesterPays());
        }
        Encryption cryptoConfig = protoBucket.getEncryption();
        if (null != cryptoConfig && null != cryptoConfig.getDefaultKmsKeyName() && !cryptoConfig.getDefaultKmsKeyName().isEmpty()) {
            uniformAccessConfig.setDefaultKmsKeyName(cryptoConfig.getDefaultKmsKeyName());
        }
        if (null != protoBucket.getDefaultEventBasedHold()) {
            uniformAccessConfig.setDefaultEventBasedHold(protoBucket.getDefaultEventBasedHold());
        }
        Bucket.RetentionPolicy retentionConfig = protoBucket.getRetentionPolicy();
        if (null != retentionConfig) {
            if (null != retentionConfig.getEffectiveTime()) {
                uniformAccessConfig.setRetentionEffectiveTime(retentionConfig.getEffectiveTime().getValue());
            }
            if (null != retentionConfig.getIsLocked()) {
                uniformAccessConfig.setRetentionPolicyIsLocked(retentionConfig.getIsLocked());
            }
            if (null != retentionConfig.getRetentionPeriod()) {
                uniformAccessConfig.setRetentionPeriod(retentionConfig.getRetentionPeriod());
            }
        }
        Bucket.IamConfiguration iamSettings = protoBucket.getIamConfiguration();
        if (null != protoBucket.getLocationType()) {
            uniformAccessConfig.setLocationType(protoBucket.getLocationType());
        }
        if (null != iamSettings) {
            uniformAccessConfig.setIamConfiguration(IamSettings.fromProto(iamSettings));
        }
        Bucket.Logging accessLogging = protoBucket.getLogging();
        if (null != accessLogging) {
            uniformAccessConfig.setLogging(BucketLogging.fromProto(accessLogging));
        }
        return uniformAccessConfig.buildBucket();
    }
}
