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
import com.google.cloud.storage.AclEntry.TypedEntity;
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

    static final Function<com.google.api.services.storage.model.Bucket, BucketInfo> FROM_PB_FUNCTION = new Function<com.google.api.services.storage.model.Bucket, BucketInfo>() {

        @Override
        public BucketInfo apply(com.google.api.services.storage.model.Bucket pb) {
            return BucketInfo.fromProto(pb);
        }
    };

    static final Function<BucketInfo, com.google.api.services.storage.model.Bucket> INFO_TO_PROTO_FUNCTION = new Function<BucketInfo, com.google.api.services.storage.model.Bucket>() {

        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketInfo bucketInfo) {
            return bucketInfo.toProto();
        }
    };

    private static final long serialVersionUID = -4712013629621638459L;

    private final String generatedIdentifier;

    private final String bucketName;

    private final TypedEntity ownerEntity;

    private final String selfUrl;

    private final Boolean billingEnabled;

    private final Boolean versioningActive;

    private final String indexDocument;

    private final String notFoundDocument;

    private final List<DeletionRule> deletionRules;

    private final List<LifecyclePolicy> lifecyclePolicies;

    private final String entityTag;

    private final Long creationTime;

    private final Long generationNumber;

    private final List<CorsConfiguration> corsConfigs;

    private final List<AclEntry> aclEntries;

    private final List<AclEntry> defaultAccessList;

    private final String region;

    private final StorageClassType storageTier;

    private final Map<String, String> tags;

    private final String kmsDefaultKey;

    private final Boolean eventBasedHoldDefault;

    private final Long retentionEffectiveTimestamp;

    private final Boolean retentionPolicyLocked;

    private final Long retentionDuration;

    private final BucketIamConfiguration iamConfig;

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

        private Boolean uniformAccessEnabled;

        private Long uniformAccessLockedTime;

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (null == obj || obj.getClass() != getClass()) {
                return false;
            }
            BucketIamConfiguration otherConfig = (BucketIamConfiguration) obj;
            return Objects.equals(toProto(), otherConfig.toProto());
        }

        @Override
        public int hashCode() {
            return Objects.hash(uniformAccessEnabled, uniformAccessLockedTime);
        }

        private BucketIamConfiguration(UniformBucketLevelAccessBuilder accessBuilder) {
            this.uniformAccessEnabled = accessBuilder.uniformAccessEnabled;
            this.uniformAccessLockedTime = accessBuilder.uniformAccessLockedTime;
        }

        public static UniformBucketLevelAccessBuilder newIamBuilder() {
            return new UniformBucketLevelAccessBuilder();
        }

        public UniformBucketLevelAccessBuilder toBuilderCopy() {
            UniformBucketLevelAccessBuilder accessBuilder = new UniformBucketLevelAccessBuilder();
            accessBuilder.uniformAccessEnabled = uniformAccessEnabled;
            accessBuilder.uniformAccessLockedTime = uniformAccessLockedTime;
            return accessBuilder;
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
            Bucket.IamConfiguration iamConfig = new Bucket.IamConfiguration();
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessConfig = new Bucket.IamConfiguration.UniformBucketLevelAccess();
            uniformAccessConfig.setEnabled(uniformAccessEnabled);
            uniformAccessConfig.setLockedTime(null == uniformAccessLockedTime ? null : new DateTime(uniformAccessLockedTime));
            iamConfig.setUniformBucketLevelAccess(uniformAccessConfig);
            return iamConfig;
        }

        static BucketIamConfiguration fromProto(Bucket.IamConfiguration iamConfig) {
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessConfig = iamConfig.getUniformBucketLevelAccess();
            DateTime lockTimestamp = uniformAccessConfig.getLockedTime();
            return newIamBuilder().setIsUniformBucketLevelAccessEnabled(uniformAccessConfig.getEnabled()).setUniformBucketLevelAccessLockedTime(null == lockTimestamp ? null : lockTimestamp.getValue()).buildConfiguration();
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
            public BucketInfo.BucketIamConfiguration.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean bucketPolicyEnabled) {
                this.uniformAccessEnabled = bucketPolicyEnabled;
                return this;
            }

            /**
             * Deprecated in favor of setUniformBucketLevelAccessLockedTime().
             */
            @Deprecated
            BucketInfo.BucketIamConfiguration.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long policyOnlyLockMillis) {
                this.uniformAccessLockedTime = policyOnlyLockMillis;
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
            public BucketIamConfiguration buildConfiguration() {
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

        private String objectLogPrefix;

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (null == obj || obj.getClass() != getClass()) {
                return false;
            }
            LoggingConfig otherConfig = (LoggingConfig) obj;
            return Objects.equals(toProto(), otherConfig.toProto());
        }

        @Override
        public int hashCode() {
            return Objects.hash(loggingBucket, objectLogPrefix);
        }

        public static LogConfigBuilder newLogConfigBuilder() {
            return new LogConfigBuilder();
        }

        public LogConfigBuilder toBuilder() {
            LogConfigBuilder accessBuilder = new LogConfigBuilder();
            accessBuilder.loggingBucket = loggingBucket;
            accessBuilder.objectLogPrefix = objectLogPrefix;
            return accessBuilder;
        }

        public String getLogBucket() {
            return loggingBucket;
        }

        public String getLogObjectPrefix() {
            return objectLogPrefix;
        }

        Bucket.Logging toProto() {
            Bucket.Logging loggingConfig;
            if (null == loggingBucket && null == objectLogPrefix) {
                loggingConfig = Data.nullOf(Bucket.Logging.class);
            } else {
                loggingConfig = new Bucket.Logging();
                loggingConfig.setLogBucket(loggingBucket);
                loggingConfig.setLogObjectPrefix(objectLogPrefix);
            }
            return loggingConfig;
        }

        static LoggingConfig fromProto(Bucket.Logging loggingConfig) {
            return newLogConfigBuilder().setLogBucket(loggingConfig.getLogBucket()).setLogObjectPrefix(loggingConfig.getLogObjectPrefix()).buildConfig();
        }

        private LoggingConfig(LogConfigBuilder accessBuilder) {
            this.loggingBucket = accessBuilder.loggingBucket;
            this.objectLogPrefix = accessBuilder.objectLogPrefix;
        }

        public static class LogConfigBuilder {

            private String loggingBucket;

            private String objectLogPrefix;

            /**
             * The destination bucket where the current bucket's logs should be placed.
             */
            public LogConfigBuilder setLogBucket(String loggingBucket) {
                this.loggingBucket = loggingBucket;
                return this;
            }

            /**
             * A prefix for log object names.
             */
            public LogConfigBuilder setLogObjectPrefix(String objectLogPrefix) {
                this.objectLogPrefix = objectLogPrefix;
                return this;
            }

            /**
             * Builds an {@code Logging} object
             */
            public LoggingConfig buildConfig() {
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
    public static class LifecyclePolicy implements Serializable {

        private static final long serialVersionUID = -5739807320148748613L;

        private final LifecycleOperation lifecycleOperation;

        private final LifecycleRuleCondition ruleCondition;

        public LifecyclePolicy(LifecycleOperation operation, LifecycleRuleCondition ruleConditionParam) {
            if (null == ruleConditionParam.getIsLive() && null == ruleConditionParam.getAge() && null == ruleConditionParam.getCreatedBefore() && null == ruleConditionParam.getMatchesStorageClass() && null == ruleConditionParam.getNumberOfNewerVersions()) {
                throw new IllegalArgumentException("You must specify at least one condition to use object lifecycle " + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
            }
            this.lifecycleOperation = operation;
            this.ruleCondition = ruleConditionParam;
        }

        public LifecycleOperation getAction() {
            return lifecycleOperation;
        }

        public LifecycleRuleCondition getCondition() {
            return ruleCondition;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("lifecycleAction", lifecycleOperation).add("lifecycleCondition", ruleCondition).toString();
        }

        @Override
        public int hashCode() {
            return Objects.hash(lifecycleOperation, ruleCondition);
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj == this) {
                return true;
            }
            if (null == otherObj || otherObj.getClass() != getClass()) {
                return false;
            }
            final LifecyclePolicy otherConfig = (LifecyclePolicy) otherObj;
            return Objects.equals(toProto(), otherConfig.toProto());
        }

        Rule toProto() {
            Rule protoRule = new Rule();
            Rule.Action operation = new Rule.Action().setType(lifecycleOperation.getActionType());
            if (lifecycleOperation.getActionType().equals(SetStorageClassLifecycleOperation.TYPE)) {
                operation.setStorageClass(((SetStorageClassLifecycleOperation) lifecycleOperation).getStorageClass().toString());
            }
            protoRule.setAction(operation);
            Rule.Condition ruleConditionParam = new Rule.Condition().setAge(ruleCondition.getAge()).setCreatedBefore(null == ruleCondition.getCreatedBefore() ? null : new DateTime(true, ruleCondition.getCreatedBefore().getValue(), 0)).setIsLive(ruleCondition.getIsLive()).setNumNewerVersions(ruleCondition.getNumberOfNewerVersions()).setMatchesStorageClass(null == ruleCondition.getMatchesStorageClass() ? null : transform(ruleCondition.getMatchesStorageClass(), Functions.toStringFunction()));
            protoRule.setCondition(ruleConditionParam);
            return protoRule;
        }

        static LifecyclePolicy fromProto(Rule protoRule) {
            LifecycleOperation lifecycleOperation;
            Rule.Action operation = protoRule.getAction();
            switch(operation.getType()) {
                case RemoveLifecycleAction.TYPE:
                    lifecycleOperation = LifecycleOperation.createDeleteAction();
                    break;
                case SetStorageClassLifecycleOperation.TYPE:
                    lifecycleOperation = LifecycleOperation.createSetStorageClassAction(StorageClassType.fromValue(operation.getStorageClass()));
                    break;
                default:
                    throw new UnsupportedOperationException("The specified lifecycle action " + operation.getType() + " is not currently supported");
            }
            Rule.Condition ruleConditionParam = protoRule.getCondition();
            LifecycleRuleCondition.LifecycleRuleBuilder ruleBuilder = LifecycleRuleCondition.newLifecycleRuleBuilder().setAge(ruleConditionParam.getAge()).setCreatedBefore(ruleConditionParam.getCreatedBefore()).setIsLive(ruleConditionParam.getIsLive()).setNumberOfNewerVersions(ruleConditionParam.getNumNewerVersions()).setMatchesStorageClass(null == ruleConditionParam.getMatchesStorageClass() ? null : transform(ruleConditionParam.getMatchesStorageClass(), new Function<String, StorageClassType>() {

                public StorageClassType apply(String storageClass) {
                    return StorageClassType.fromValue(storageClass);
                }
            }));
            return new LifecyclePolicy(lifecycleOperation, ruleBuilder.buildCondition());
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

            private final DateTime priorToDate;

            private final Integer newerVersionCount;

            private final Boolean active;

            private final List<StorageClassType> storageClassFilters;

            private LifecycleRuleCondition(LifecycleRuleBuilder accessBuilder) {
                this.daysOld = accessBuilder.daysOld;
                this.priorToDate = accessBuilder.priorToDate;
                this.newerVersionCount = accessBuilder.newerVersionCount;
                this.active = accessBuilder.active;
                this.storageClassFilters = accessBuilder.storageClassFilters;
            }

            public LifecycleRuleBuilder toBuilder() {
                return newLifecycleRuleBuilder().setAge(this.daysOld).setCreatedBefore(this.priorToDate).setNumberOfNewerVersions(this.newerVersionCount).setIsLive(this.active).setMatchesStorageClass(this.storageClassFilters);
            }

            public static LifecycleRuleBuilder newLifecycleRuleBuilder() {
                return new LifecycleRuleBuilder();
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("age", daysOld).add("createBefore", priorToDate).add("numberofNewerVersions", newerVersionCount).add("isLive", active).add("matchesStorageClass", storageClassFilters).toString();
            }

            public Integer getAge() {
                return daysOld;
            }

            public DateTime getCreatedBefore() {
                return priorToDate;
            }

            public Integer getNumberOfNewerVersions() {
                return newerVersionCount;
            }

            public Boolean getIsLive() {
                return active;
            }

            public List<StorageClassType> getMatchesStorageClass() {
                return storageClassFilters;
            }

            /**
             * Builder for {@code LifecycleCondition}.
             */
            public static class LifecycleRuleBuilder {

                private Integer daysOld;

                private DateTime priorToDate;

                private Integer newerVersionCount;

                private Boolean active;

                private List<StorageClassType> storageClassFilters;

                private LifecycleRuleBuilder() {
                }

                /**
                 * Sets the age in days. This condition is satisfied when a Blob reaches the specified age
                 * (in days). When you specify the Age condition, you are specifying a Time to Live (TTL)
                 * for objects in a bucket with lifecycle management configured. The time when the Age
                 * condition is considered to be satisfied is calculated by adding the specified value to
                 * the object creation time.
                 */
                public LifecycleRuleBuilder setAge(Integer daysOld) {
                    this.daysOld = daysOld;
                    return this;
                }

                /**
                 * Sets the date a Blob should be created before for an Action to be executed. Note that
                 * only the date will be considered, if the time is specified it will be truncated. This
                 * condition is satisfied when an object is created before midnight of the specified date in
                 * UTC. *
                 */
                public LifecycleRuleBuilder setCreatedBefore(DateTime priorToDate) {
                    this.priorToDate = priorToDate;
                    return this;
                }

                /**
                 * Sets the number of newer versions a Blob should have for an Action to be executed.
                 * Relevant only when versioning is enabled on a bucket. *
                 */
                public LifecycleRuleBuilder setNumberOfNewerVersions(Integer newerVersionCount) {
                    this.newerVersionCount = newerVersionCount;
                    return this;
                }

                /**
                 * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
                 * only live Blobs; if the value is false, it matches only archived objects. For the
                 * purposes of this condition, Blobs in non-versioned buckets are considered live.
                 */
                public LifecycleRuleBuilder setIsLive(Boolean activeFlag) {
                    this.active = activeFlag;
                    return this;
                }

                /**
                 * Sets a list of Storage Classes for a objects that satisfy the condition to execute the
                 * Action. *
                 */
                public LifecycleRuleBuilder setMatchesStorageClass(List<StorageClassType> storageClassFilters) {
                    this.storageClassFilters = storageClassFilters;
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
            public static SetStorageClassLifecycleOperation createSetStorageClassAction(StorageClassType storageTier) {
                return new SetStorageClassLifecycleOperation(storageTier);
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

        public static class SetStorageClassLifecycleOperation extends LifecycleOperation {

            public static final String TYPE = "SetStorageClass";

            private static final long serialVersionUID = -62615467186000899L;

            private final StorageClassType storageTier;

            private SetStorageClassLifecycleOperation(StorageClassType storageTier) {
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

        private static final String ALLOWED_ACTION = "Delete";

        private final CriterionType criterionKind;

        public enum CriterionType {

            AGE, CREATE_BEFORE, NUM_NEWER_VERSIONS, IS_LIVE, UNKNOWN
        }

        DeletionRule(CriterionType criterionKind) {
            this.criterionKind = criterionKind;
        }

        public CriterionType getType() {
            return criterionKind;
        }

        @Override
        public int hashCode() {
            return Objects.hash(criterionKind);
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj == this) {
                return true;
            }
            if (null == otherObj || otherObj.getClass() != getClass()) {
                return false;
            }
            final DeletionRule otherConfig = (DeletionRule) otherObj;
            return Objects.equals(toProto(), otherConfig.toProto());
        }

        Rule toProto() {
            Rule protoRule = new Rule();
            protoRule.setAction(new Rule.Action().setType(ALLOWED_ACTION));
            Rule.Condition ruleConditionParam = new Rule.Condition();
            fillCondition(ruleConditionParam);
            protoRule.setCondition(ruleConditionParam);
            return protoRule;
        }

        abstract void fillCondition(Rule.Condition condition);

        static DeletionRule fromProto(Rule protoRule) {
            if (null != protoRule.getAction() && ALLOWED_ACTION.endsWith(protoRule.getAction().getType())) {
                Rule.Condition ruleConditionParam = protoRule.getCondition();
                Integer daysOld = ruleConditionParam.getAge();
                if (null != daysOld) {
                    return new AgeBasedDeletionRule(daysOld);
                }
                DateTime timestamp = ruleConditionParam.getCreatedBefore();
                if (null != timestamp) {
                    return new CreationBeforeDeletionRule(timestamp.getValue());
                }
                Integer newerVersionCount = ruleConditionParam.getNumNewerVersions();
                if (null != newerVersionCount) {
                    return new NewerVersionsCountDeleteRule(newerVersionCount);
                }
                Boolean active = ruleConditionParam.getIsLive();
                if (null != active) {
                    return new LiveDeleteRule(active);
                }
            }
            return new RawDeletionRule(protoRule);
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
            super(CriterionType.AGE);
            this.ttlDays = ttlDays;
        }

        public int getDaysToLive() {
            return ttlDays;
        }

        @Override
        void fillCondition(Rule.Condition ruleConditionParam) {
            ruleConditionParam.setAge(ttlDays);
        }
    }

    static class RawDeletionRule extends DeletionRule {

        private static final long serialVersionUID = -7166938278642301933L;

        private transient Rule protoRule;

        RawDeletionRule(Rule protoRule) {
            super(CriterionType.UNKNOWN);
            this.protoRule = protoRule;
        }

        @Override
        void fillCondition(Rule.Condition condition) {
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
    public static class CreationBeforeDeletionRule extends DeletionRule {

        private static final long serialVersionUID = 881692650279195867L;

        private final long creationMillis;

        /**
         * Creates an {@code CreatedBeforeDeleteRule} object.
         *
         * @param creationMillis a date in UTC. Blobs that have been created before midnight of the provided
         *     date meet the delete condition
         */
        public CreationBeforeDeletionRule(long creationMillis) {
            super(CriterionType.CREATE_BEFORE);
            this.creationMillis = creationMillis;
        }

        public long getTimeMillis() {
            return creationMillis;
        }

        @Override
        void fillCondition(Rule.Condition ruleConditionParam) {
            ruleConditionParam.setCreatedBefore(new DateTime(true, creationMillis, 0));
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
    public static class NewerVersionsCountDeleteRule extends DeletionRule {

        private static final long serialVersionUID = -1955554976528303894L;

        private final int newerVersionCount;

        /**
         * Creates an {@code NumNewerVersionsDeleteRule} object.
         *
         * @param newerVersionCount the number of newer versions. A blob's version meets the delete
         *     condition when {@code numNewerVersions} newer versions are available.
         */
        public NewerVersionsCountDeleteRule(int newerVersionCount) {
            super(CriterionType.NUM_NEWER_VERSIONS);
            this.newerVersionCount = newerVersionCount;
        }

        public int getNumNewerVersions() {
            return newerVersionCount;
        }

        @Override
        void fillCondition(Rule.Condition ruleConditionParam) {
            ruleConditionParam.setNumNewerVersions(newerVersionCount);
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

        private final boolean active;

        /**
         * Creates an {@code IsLiveDeleteRule} object.
         *
         * @param active if set to {@code true} live blobs meet the delete condition. If set to {@code
         *     false} delete condition is met by archived blobs.
         */
        public LiveDeleteRule(boolean active) {
            super(CriterionType.IS_LIVE);
            this.active = active;
        }

        public boolean isLive() {
            return active;
        }

        @Override
        void fillCondition(Rule.Condition ruleConditionParam) {
            ruleConditionParam.setIsLive(active);
        }
    }

    /**
     * Builder for {@code BucketInfo}.
     */
    public abstract static class Builder {

        Builder() {
        }

        /**
         * Sets the bucket's name.
         */
        public abstract Builder setName(String name);

        abstract Builder setGeneratedId(String generatedId);

        abstract Builder setOwner(TypedEntity owner);

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

        /**
         * Sets the custom object to return when a requested resource is not found.
         */
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
        public abstract Builder setLifecycleRules(Iterable<? extends LifecyclePolicy> rules);

        /**
         * Deletes the lifecycle rules of this bucket.
         */
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

        abstract Builder setMetageneration(Long metageneration);

        abstract Builder setLocationType(String locationType);

        /**
         * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
         *
         * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
         *     Sharing (CORS)</a>
         */
        public abstract Builder setCors(Iterable<CorsConfiguration> cors);

        /**
         * Sets the bucket's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract Builder setAcl(Iterable<AclEntry> acl);

        /**
         * Sets the default access control configuration to apply to bucket's blobs when no other
         * configuration is specified.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract Builder setDefaultAcl(Iterable<AclEntry> acl);

        /**
         * Sets the label of this bucket.
         */
        public abstract Builder setLabels(Map<String, String> labels);

        /**
         * Sets the default Cloud KMS key name for this bucket.
         */
        public abstract Builder setDefaultKmsKeyName(String defaultKmsKeyName);

        /**
         * Sets the default event-based hold for this bucket.
         */
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

        /**
         * Creates a {@code BucketInfo} object.
         */
        public abstract BucketInfo buildInstance();
    }

    static final class BucketBuilderImpl extends Builder {

        private String generatedIdentifier;

        private String bucketName;

        private TypedEntity ownerEntity;

        private String selfUrl;

        private Boolean billingEnabled;

        private Boolean versioningActive;

        private String indexDocument;

        private String notFoundDocument;

        private List<DeletionRule> deletionRules;

        private List<LifecyclePolicy> lifecyclePolicies;

        private StorageClassType storageTier;

        private String region;

        private String entityTag;

        private Long creationTime;

        private Long generationNumber;

        private List<CorsConfiguration> corsConfigs;

        private List<AclEntry> aclEntries;

        private List<AclEntry> defaultAccessList;

        private Map<String, String> tags;

        private String kmsDefaultKey;

        private Boolean eventBasedHoldDefault;

        private Long retentionEffectiveTimestamp;

        private Boolean retentionPolicyLocked;

        private Long retentionDuration;

        private BucketIamConfiguration iamConfig;

        private String locationClass;

        private LoggingConfig loggingConfig;

        BucketBuilderImpl(String bucketName) {
            this.bucketName = bucketName;
        }

        BucketBuilderImpl(BucketInfo info) {
            generatedIdentifier = info.generatedIdentifier;
            bucketName = info.bucketName;
            entityTag = info.entityTag;
            creationTime = info.creationTime;
            generationNumber = info.generationNumber;
            region = info.region;
            storageTier = info.storageTier;
            corsConfigs = info.corsConfigs;
            aclEntries = info.aclEntries;
            defaultAccessList = info.defaultAccessList;
            ownerEntity = info.ownerEntity;
            selfUrl = info.selfUrl;
            versioningActive = info.versioningActive;
            indexDocument = info.indexDocument;
            notFoundDocument = info.notFoundDocument;
            deletionRules = info.deletionRules;
            lifecyclePolicies = info.lifecyclePolicies;
            tags = info.tags;
            billingEnabled = info.billingEnabled;
            kmsDefaultKey = info.kmsDefaultKey;
            eventBasedHoldDefault = info.eventBasedHoldDefault;
            retentionEffectiveTimestamp = info.retentionEffectiveTimestamp;
            retentionPolicyLocked = info.retentionPolicyLocked;
            retentionDuration = info.retentionDuration;
            iamConfig = info.iamConfig;
            locationClass = info.locationClass;
            loggingConfig = info.loggingConfig;
        }

        @Override
        public Builder setName(String bucketName) {
            this.bucketName = checkNotNull(bucketName);
            return this;
        }

        @Override
        Builder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        Builder setOwner(TypedEntity ownerEntity) {
            this.ownerEntity = ownerEntity;
            return this;
        }

        @Override
        Builder setSelfLink(String selfUrl) {
            this.selfUrl = selfUrl;
            return this;
        }

        @Override
        public Builder setVersioningEnabled(Boolean versioningFlag) {
            this.versioningActive = firstNonNull(versioningFlag, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public Builder setRequesterPays(Boolean versioningFlag) {
            this.billingEnabled = firstNonNull(versioningFlag, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public Builder setIndexPage(String indexDocument) {
            this.indexDocument = indexDocument;
            return this;
        }

        @Override
        public Builder setNotFoundPage(String notFoundDocument) {
            this.notFoundDocument = notFoundDocument;
            return this;
        }

        /**
         * @deprecated Use {@code setLifecycleRules} method instead. *
         */
        @Override
        @Deprecated
        public Builder setDeleteRules(Iterable<? extends DeletionRule> deletionPolicies) {
            this.deletionRules = null != deletionPolicies ? ImmutableList.copyOf(deletionPolicies) : null;
            return this;
        }

        @Override
        public Builder setLifecycleRules(Iterable<? extends LifecyclePolicy> deletionPolicies) {
            this.lifecyclePolicies = null != deletionPolicies ? ImmutableList.copyOf(deletionPolicies) : ImmutableList.<LifecyclePolicy>of();
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
        Builder setMetageneration(Long generationNumber) {
            this.generationNumber = generationNumber;
            return this;
        }

        @Override
        public Builder setCors(Iterable<CorsConfiguration> corsConfigs) {
            this.corsConfigs = null != corsConfigs ? ImmutableList.copyOf(corsConfigs) : null;
            return this;
        }

        @Override
        public Builder setAcl(Iterable<AclEntry> aclEntries) {
            this.aclEntries = null != aclEntries ? ImmutableList.copyOf(aclEntries) : null;
            return this;
        }

        @Override
        public Builder setDefaultAcl(Iterable<AclEntry> aclEntries) {
            this.defaultAccessList = null != aclEntries ? ImmutableList.copyOf(aclEntries) : null;
            return this;
        }

        @Override
        public Builder setLabels(Map<String, String> tags) {
            if (null != tags) {
                this.tags = Maps.transformValues(tags, new Function<String, String>() {

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
        public Builder setDefaultKmsKeyName(String kmsDefaultKey) {
            this.kmsDefaultKey = null != kmsDefaultKey ? kmsDefaultKey : Data.<String>nullOf(String.class);
            return this;
        }

        @Override
        public Builder setDefaultEventBasedHold(Boolean eventBasedHoldDefault) {
            this.eventBasedHoldDefault = firstNonNull(eventBasedHoldDefault, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        Builder setRetentionEffectiveTime(Long retentionEffectiveTimestamp) {
            this.retentionEffectiveTimestamp = firstNonNull(retentionEffectiveTimestamp, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        Builder setRetentionPolicyIsLocked(Boolean retentionPolicyLocked) {
            this.retentionPolicyLocked = firstNonNull(retentionPolicyLocked, Data.<Boolean>nullOf(Boolean.class));
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
            this.loggingConfig = null != loggingConfig ? loggingConfig : LoggingConfig.newLogConfigBuilder().buildConfig();
            return this;
        }

        @Override
        Builder setLocationType(String locationClass) {
            this.locationClass = locationClass;
            return this;
        }

        @Override
        public BucketInfo buildInstance() {
            checkNotNull(bucketName);
            return new BucketInfo(this);
        }
    }

    BucketInfo(BucketBuilderImpl accessBuilder) {
        generatedIdentifier = accessBuilder.generatedIdentifier;
        bucketName = accessBuilder.bucketName;
        entityTag = accessBuilder.entityTag;
        creationTime = accessBuilder.creationTime;
        generationNumber = accessBuilder.generationNumber;
        region = accessBuilder.region;
        storageTier = accessBuilder.storageTier;
        corsConfigs = accessBuilder.corsConfigs;
        aclEntries = accessBuilder.aclEntries;
        defaultAccessList = accessBuilder.defaultAccessList;
        ownerEntity = accessBuilder.ownerEntity;
        selfUrl = accessBuilder.selfUrl;
        versioningActive = accessBuilder.versioningActive;
        indexDocument = accessBuilder.indexDocument;
        notFoundDocument = accessBuilder.notFoundDocument;
        deletionRules = accessBuilder.deletionRules;
        lifecyclePolicies = accessBuilder.lifecyclePolicies;
        tags = accessBuilder.tags;
        billingEnabled = accessBuilder.billingEnabled;
        kmsDefaultKey = accessBuilder.kmsDefaultKey;
        eventBasedHoldDefault = accessBuilder.eventBasedHoldDefault;
        retentionEffectiveTimestamp = accessBuilder.retentionEffectiveTimestamp;
        retentionPolicyLocked = accessBuilder.retentionPolicyLocked;
        retentionDuration = accessBuilder.retentionDuration;
        iamConfig = accessBuilder.iamConfig;
        locationClass = accessBuilder.locationClass;
        loggingConfig = accessBuilder.loggingConfig;
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
        return bucketName;
    }

    /**
     * Returns the bucket's owner. This is always the project team's owner group.
     */
    public TypedEntity getOwner() {
        return ownerEntity;
    }

    /**
     * Returns the URI of this bucket as a string.
     */
    public String getSelfLink() {
        return selfUrl;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageClient.BucketAttribute#VERSIONING} is selected in a {@link
     * StorageClient#get(String, StorageClient.BucketGetOptions...)} and versions for the bucket is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageClient.BucketAttribute#VERSIONING} is selected in a {@link
     * StorageClient#get(String, StorageClient.BucketGetOptions...)}, but versions for the bucket is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageClient.BucketAttribute#VERSIONING} is not selected in a {@link
     * StorageClient#get(String, StorageClient.BucketGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
     * request for example {@link StorageClient#update(BucketInfo, StorageClient.BucketTargetOptions...)} in which
     * case the value of versions will remain {@code false} for for the given instance.
     */
    public Boolean isVersioningEnabled() {
        return Data.isNull(versioningActive) ? null : versioningActive;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
     * {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link StorageClient.BucketAttribute#BILLING}
     * is selected in a {@link StorageClient#get(String, StorageClient.BucketGetOptions...)} and requester pays for
     * the bucket is enabled.
     *
     * <p>Case 2: {@code false} the field {@link StorageClient.BucketAttribute#BILLING}
     * in a {@link StorageClient#get(String, StorageClient.BucketGetOptions...)} is selected and requester pays for
     * the bucket is disable.
     *
     * <p>Case 3: {@code null} the field {@link StorageClient.BucketAttribute#BILLING}
     * in a {@link StorageClient#get(String, StorageClient.BucketGetOptions...)} is not selected, the value is
     * unknown.
     */
    public Boolean isRequesterPays() {
        return Data.isNull(billingEnabled) ? null : billingEnabled;
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
        return notFoundDocument;
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

    public List<? extends LifecyclePolicy> getLifecycleRules() {
        return null != lifecyclePolicies ? lifecyclePolicies : ImmutableList.<LifecyclePolicy>of();
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
        return generationNumber;
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
    public StorageClassType getStorageClass() {
        return storageTier;
    }

    /**
     * Returns the bucket's Cross-Origin Resource Sharing (CORS) configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource Sharing
     *     (CORS)</a>
     */
    public List<CorsConfiguration> getCors() {
        return corsConfigs;
    }

    /**
     * Returns the bucket's access control configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AclEntry> getAcl() {
        return aclEntries;
    }

    /**
     * Returns the default access control configuration for this bucket's blobs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AclEntry> getDefaultAcl() {
        return defaultAccessList;
    }

    /**
     * Returns the labels for this bucket.
     */
    public Map<String, String> getLabels() {
        return tags;
    }

    /**
     * Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket.
     */
    public String getDefaultKmsKeyName() {
        return kmsDefaultKey;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageClient.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * StorageClient#get(String, StorageClient.BucketGetOptions...)} and default event-based hold for the bucket is
     * enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageClient.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * StorageClient#get(String, StorageClient.BucketGetOptions...)}, but default event-based hold for the bucket
     * is not enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageClient.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is not selected in a
     * {@link StorageClient#get(String, StorageClient.BucketGetOptions...)}, and the state for this field is
     * unknown.
     *
     * <p>Case 3: {@code false} default event-based hold is explicitly set to false using in a {@link
     * Builder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * StorageClient#update(BucketInfo, StorageClient.BucketTargetOptions...)} in which case the value of default
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
        return retentionEffectiveTimestamp;
    }

    /**
     * Returns a {@code Boolean} with either {@code true} or {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageClient.BucketAttribute#RETENTION_POLICY} is selected in a {@link
     * StorageClient#get(String, StorageClient.BucketGetOptions...)} and retention policy for the bucket is locked.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageClient.BucketAttribute#RETENTION_POLICY} is selected in a {@link
     * StorageClient#get(String, StorageClient.BucketGetOptions...)}, but retention policy for the bucket is not
     * locked. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageClient.BucketAttribute#RETENTION_POLICY} is not selected in a {@link
     * StorageClient#get(String, StorageClient.BucketGetOptions...)}, and the state for this field is unknown.
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
    public BucketIamConfiguration getIamConfiguration() {
        return iamConfig;
    }

    /**
     * Returns the Logging
     */
    public LoggingConfig getLogging() {
        return loggingConfig;
    }

    /**
     * Returns a builder for the current bucket.
     */
    public Builder toBuilderCopy() {
        return new BucketBuilderImpl(this);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucketName);
    }

    @Override
    public boolean equals(Object otherObj) {
        return this == otherObj || null != otherObj && otherObj.getClass().equals(BucketInfo.class) && Objects.equals(toProto(), ((BucketInfo) otherObj).toProto());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", bucketName).toString();
    }

    com.google.api.services.storage.model.Bucket toProto() {
        com.google.api.services.storage.model.Bucket bucketProto = new com.google.api.services.storage.model.Bucket();
        bucketProto.setId(generatedIdentifier);
        bucketProto.setName(bucketName);
        bucketProto.setEtag(entityTag);
        if (null != creationTime) {
            bucketProto.setTimeCreated(new DateTime(creationTime));
        }
        if (null != generationNumber) {
            bucketProto.setMetageneration(generationNumber);
        }
        if (null != region) {
            bucketProto.setLocation(region);
        }
        if (null != locationClass) {
            bucketProto.setLocationType(locationClass);
        }
        if (null != storageTier) {
            bucketProto.setStorageClass(storageTier.toString());
        }
        if (null != corsConfigs) {
            bucketProto.setCors(transform(corsConfigs, CorsConfiguration.TO_PROTO_FUNCTION));
        }
        if (null != aclEntries) {
            bucketProto.setAcl(transform(aclEntries, new Function<AclEntry, BucketAccessControl>() {

                @Override
                public BucketAccessControl apply(AclEntry acl) {
                    return acl.toBucketProto();
                }
            }));
        }
        if (null != defaultAccessList) {
            bucketProto.setDefaultObjectAcl(transform(defaultAccessList, new Function<AclEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AclEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != ownerEntity) {
            bucketProto.setOwner(new Owner().setEntity(ownerEntity.toProtoString()));
        }
        bucketProto.setSelfLink(selfUrl);
        if (null != versioningActive) {
            bucketProto.setVersioning(new Versioning().setEnabled(versioningActive));
        }
        if (null != billingEnabled) {
            Bucket.Billing billingInfo = new Bucket.Billing();
            billingInfo.setRequesterPays(billingEnabled);
            bucketProto.setBilling(billingInfo);
        }
        if (null != indexDocument || null != notFoundDocument) {
            Website websiteConfig = new Website();
            websiteConfig.setMainPageSuffix(indexDocument);
            websiteConfig.setNotFoundPage(notFoundDocument);
            bucketProto.setWebsite(websiteConfig);
        }
        Set<Rule> deletionPolicies = new HashSet<>();
        if (null != deletionRules) {
            deletionPolicies.addAll(transform(deletionRules, new Function<DeletionRule, Rule>() {

                @Override
                public Rule apply(DeletionRule deleteRule) {
                    return deleteRule.toProto();
                }
            }));
        }
        if (null != lifecyclePolicies) {
            deletionPolicies.addAll(transform(lifecyclePolicies, new Function<LifecyclePolicy, Rule>() {

                @Override
                public Rule apply(LifecyclePolicy lifecycleRule) {
                    return lifecycleRule.toProto();
                }
            }));
        }
        if (null != deletionPolicies) {
            Lifecycle lifecycleProto = new Lifecycle();
            lifecycleProto.setRule(ImmutableList.copyOf(deletionPolicies));
            bucketProto.setLifecycle(lifecycleProto);
        }
        if (null != tags) {
            bucketProto.setLabels(tags);
        }
        if (null != kmsDefaultKey) {
            bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(kmsDefaultKey));
        }
        if (null != eventBasedHoldDefault) {
            bucketProto.setDefaultEventBasedHold(eventBasedHoldDefault);
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
                bucketProto.setRetentionPolicy(retentionConfig);
            } else {
                bucketProto.setRetentionPolicy(Data.<Bucket.RetentionPolicy>nullOf(Bucket.RetentionPolicy.class));
            }
        }
        if (null != iamConfig) {
            bucketProto.setIamConfiguration(iamConfig.toProto());
        }
        if (null != loggingConfig) {
            bucketProto.setLogging(loggingConfig.toProto());
        }
        return bucketProto;
    }

    /**
     * Creates a {@code BucketInfo} object for the provided bucket name.
     */
    public static BucketInfo ofName(String bucketName) {
        return newBucketBuilder(bucketName).buildInstance();
    }

    /**
     * Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name.
     */
    public static Builder newBucketBuilder(String bucketName) {
        return new BucketBuilderImpl(bucketName);
    }

    static BucketInfo fromProto(com.google.api.services.storage.model.Bucket bucketProto) {
        Builder accessBuilder = new BucketBuilderImpl(bucketProto.getName());
        if (null != bucketProto.getId()) {
            accessBuilder.setGeneratedId(bucketProto.getId());
        }
        if (null != bucketProto.getEtag()) {
            accessBuilder.setEtag(bucketProto.getEtag());
        }
        if (null != bucketProto.getMetageneration()) {
            accessBuilder.setMetageneration(bucketProto.getMetageneration());
        }
        if (null != bucketProto.getSelfLink()) {
            accessBuilder.setSelfLink(bucketProto.getSelfLink());
        }
        if (null != bucketProto.getTimeCreated()) {
            accessBuilder.setCreateTime(bucketProto.getTimeCreated().getValue());
        }
        if (null != bucketProto.getLocation()) {
            accessBuilder.setLocation(bucketProto.getLocation());
        }
        if (null != bucketProto.getStorageClass()) {
            accessBuilder.setStorageClass(StorageClassType.fromValue(bucketProto.getStorageClass()));
        }
        if (null != bucketProto.getCors()) {
            accessBuilder.setCors(transform(bucketProto.getCors(), CorsConfiguration.FROM_PROTO_FUNCTION));
        }
        if (null != bucketProto.getAcl()) {
            accessBuilder.setAcl(transform(bucketProto.getAcl(), new Function<BucketAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(BucketAccessControl bucketAccessControl) {
                    return AclEntry.fromProto(bucketAccessControl);
                }
            }));
        }
        if (null != bucketProto.getDefaultObjectAcl()) {
            accessBuilder.setDefaultAcl(transform(bucketProto.getDefaultObjectAcl(), new Function<ObjectAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(ObjectAccessControl objectAccessControl) {
                    return AclEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (null != bucketProto.getOwner()) {
            accessBuilder.setOwner(TypedEntity.fromProto(bucketProto.getOwner().getEntity()));
        }
        if (null != bucketProto.getVersioning()) {
            accessBuilder.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
        }
        Website websiteConfig = bucketProto.getWebsite();
        if (null != websiteConfig) {
            accessBuilder.setIndexPage(websiteConfig.getMainPageSuffix());
            accessBuilder.setNotFoundPage(websiteConfig.getNotFoundPage());
        }
        if (null != bucketProto.getLifecycle() && null != bucketProto.getLifecycle().getRule()) {
            accessBuilder.setLifecycleRules(transform(bucketProto.getLifecycle().getRule(), new Function<Rule, LifecyclePolicy>() {

                @Override
                public BucketInfo.LifecyclePolicy apply(Rule rule) {
                    return LifecyclePolicy.fromProto(rule);
                }
            }));
            accessBuilder.setDeleteRules(transform(bucketProto.getLifecycle().getRule(), new Function<Rule, DeletionRule>() {

                @Override
                public BucketInfo.DeletionRule apply(Rule rule) {
                    return DeletionRule.fromProto(rule);
                }
            }));
        }
        if (null != bucketProto.getLabels()) {
            accessBuilder.setLabels(bucketProto.getLabels());
        }
        Bucket.Billing billingInfo = bucketProto.getBilling();
        if (null != billingInfo) {
            accessBuilder.setRequesterPays(billingInfo.getRequesterPays());
        }
        Encryption cryptoSettings = bucketProto.getEncryption();
        if (null != cryptoSettings && null != cryptoSettings.getDefaultKmsKeyName() && !cryptoSettings.getDefaultKmsKeyName().isEmpty()) {
            accessBuilder.setDefaultKmsKeyName(cryptoSettings.getDefaultKmsKeyName());
        }
        if (null != bucketProto.getDefaultEventBasedHold()) {
            accessBuilder.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
        }
        Bucket.RetentionPolicy retentionConfig = bucketProto.getRetentionPolicy();
        if (null != retentionConfig) {
            if (null != retentionConfig.getEffectiveTime()) {
                accessBuilder.setRetentionEffectiveTime(retentionConfig.getEffectiveTime().getValue());
            }
            if (null != retentionConfig.getIsLocked()) {
                accessBuilder.setRetentionPolicyIsLocked(retentionConfig.getIsLocked());
            }
            if (null != retentionConfig.getRetentionPeriod()) {
                accessBuilder.setRetentionPeriod(retentionConfig.getRetentionPeriod());
            }
        }
        Bucket.IamConfiguration iamConfig = bucketProto.getIamConfiguration();
        if (null != bucketProto.getLocationType()) {
            accessBuilder.setLocationType(bucketProto.getLocationType());
        }
        if (null != iamConfig) {
            accessBuilder.setIamConfiguration(BucketIamConfiguration.fromProto(iamConfig));
        }
        Bucket.Logging loggingConfig = bucketProto.getLogging();
        if (null != loggingConfig) {
            accessBuilder.setLogging(LoggingConfig.fromProto(loggingConfig));
        }
        return accessBuilder.buildInstance();
    }
}
