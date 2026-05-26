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
import com.google.cloud.storage.AclEntry.BaseEntity;
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

    static final Function<BucketMetadata, com.google.api.services.storage.model.Bucket> METADATA_TO_BUCKET_FUNCTION = new Function<BucketMetadata, com.google.api.services.storage.model.Bucket>() {

        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketMetadata bucketInfo) {
            return bucketInfo.toProto();
        }
    };

    private static final long serialVersionUID = -4712013629621638459L;

    private final String uniqueIdentifier;

    private final String bucketName;

    private final BaseEntity ownerEntity;

    private final String selfLinkUri;

    private final Boolean requesterBilling;

    private final Boolean versioningOn;

    private final String indexDocument;

    private final String notFoundDocument;

    private final List<DeletionRule> deletionRules;

    private final List<LifecycleRuleSpec> lifecyclePolicies;

    private final String entityTag;

    private final Long creationTime;

    private final Long metadataGeneration;

    private final List<CorsConfig> corsConfigs;

    private final List<AclEntry> aclEntries;

    private final List<AclEntry> defaultAclEntries;

    private final String region;

    private final StorageTier storageTier;

    private final Map<String, String> labelMap;

    private final String kmsDefaultKey;

    private final Boolean eventBasedHoldDefault;

    private final Long retentionEffectiveTimestamp;

    private final Boolean retentionPolicyLocked;

    private final Long retentionDuration;

    private final BucketIamConfiguration iamConfig;

    private final String locationClass;

    private final LoggingConfig logConfig;

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

        /**
         * Builder for {@code IamConfiguration}
         */
        public static class UniformBucketLevelAccessBuilder {

            private Boolean uniformAccessEnabled;

            private Long uniformAccessLockedTime;

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
            public BucketIamConfiguration buildInstance() {
                return new BucketIamConfiguration(this);
            }

            /**
             * Deprecated in favor of setIsUniformBucketLevelAccessEnabled().
             */
            @Deprecated
            public BucketMetadata.BucketIamConfiguration.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean isPolicyOnlyEnabled) {
                this.uniformAccessEnabled = isPolicyOnlyEnabled;
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
             * Deprecated in favor of setUniformBucketLevelAccessLockedTime().
             */
            @Deprecated
            BucketMetadata.BucketIamConfiguration.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long policyOnlyLockTimestamp) {
                this.uniformAccessLockedTime = policyOnlyLockTimestamp;
                return this;
            }

        }

        /**
         * Deprecated in favor of isUniformBucketLevelAccessEnabled().
         */
        @Deprecated
        public Boolean isBucketPolicyOnlyEnabled() {
            return uniformAccessEnabled;
        }

        Bucket.IamConfiguration toProto() {
            Bucket.IamConfiguration iamConfig = new Bucket.IamConfiguration();
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccess = new Bucket.IamConfiguration.UniformBucketLevelAccess();
            uniformAccess.setEnabled(uniformAccessEnabled);
            uniformAccess.setLockedTime(null == uniformAccessLockedTime ? null : new DateTime(uniformAccessLockedTime));
            iamConfig.setUniformBucketLevelAccess(uniformAccess);
            return iamConfig;
        }

        public Boolean isUniformBucketLevelAccessEnabled() {
            return uniformAccessEnabled;
        }

        public static UniformBucketLevelAccessBuilder newUniformBucketLevelAccessBuilder() {
            return new UniformBucketLevelAccessBuilder();
        }

        static BucketIamConfiguration fromProto(Bucket.IamConfiguration iamConfig) {
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccess = iamConfig.getUniformBucketLevelAccess();
            DateTime lockTimestamp = uniformAccess.getLockedTime();
            return newUniformBucketLevelAccessBuilder().setIsUniformBucketLevelAccessEnabled(uniformAccess.getEnabled()).setUniformBucketLevelAccessLockedTime(null == lockTimestamp ? null : lockTimestamp.getValue()).buildInstance();
        }

        public Long getUniformBucketLevelAccessLockedTime() {
            return uniformAccessLockedTime;
        }

        @Override
        public int hashCode() {
            return Objects.hash(uniformAccessEnabled, uniformAccessLockedTime);
        }

        public UniformBucketLevelAccessBuilder toUniformBucketLevelAccessBuilder() {
            UniformBucketLevelAccessBuilder builderParam = new UniformBucketLevelAccessBuilder();
            builderParam.uniformAccessEnabled = uniformAccessEnabled;
            builderParam.uniformAccessLockedTime = uniformAccessLockedTime;
            return builderParam;
        }

        /**
         * Deprecated in favor of uniformBucketLevelAccessLockedTime().
         */
        @Deprecated
        public Long getBucketPolicyOnlyLockedTime() {
            return uniformAccessLockedTime;
        }

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

        private BucketIamConfiguration(UniformBucketLevelAccessBuilder builderParam) {
            this.uniformAccessEnabled = builderParam.uniformAccessEnabled;
            this.uniformAccessLockedTime = builderParam.uniformAccessLockedTime;
        }

    }

    /**
     * The bucket's logging configuration, which defines the destination bucket and optional name
     * prefix for the current bucket's logs.
     */
    public static class LoggingConfig implements Serializable {

        private static final long serialVersionUID = -708892101216778492L;

        private String loggingBucket;

        private String logObjectKeyPrefix;

        public static class LogConfigBuilder {

            private String loggingBucket;

            private String logObjectKeyPrefix;

            /**
             * Builds an {@code Logging} object
             */
            public LoggingConfig buildConfig() {
                return new LoggingConfig(this);
            }

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
            public LogConfigBuilder setLogObjectPrefix(String logObjectKeyPrefix) {
                this.logObjectKeyPrefix = logObjectKeyPrefix;
                return this;
            }

        }

        public String getLogObjectPrefix() {
            return logObjectKeyPrefix;
        }

        public String getLogBucket() {
            return loggingBucket;
        }

        static LoggingConfig fromProto(Bucket.Logging logConfig) {
            return newLogConfigBuilder().setLogBucket(logConfig.getLogBucket()).setLogObjectPrefix(logConfig.getLogObjectPrefix()).buildConfig();
        }

        Bucket.Logging toProto() {
            Bucket.Logging logConfig = new Bucket.Logging();
            logConfig.setLogBucket(loggingBucket);
            logConfig.setLogObjectPrefix(logObjectKeyPrefix);
            return logConfig;
        }

        public static LogConfigBuilder newLogConfigBuilder() {
            return new LogConfigBuilder();
        }

        private LoggingConfig(LogConfigBuilder builderParam) {
            this.loggingBucket = builderParam.loggingBucket;
            this.logObjectKeyPrefix = builderParam.logObjectKeyPrefix;
        }

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
            return Objects.hash(loggingBucket, logObjectKeyPrefix);
        }

        public LogConfigBuilder toBuilder() {
            LogConfigBuilder builderParam = new LogConfigBuilder();
            builderParam.loggingBucket = loggingBucket;
            builderParam.logObjectKeyPrefix = logObjectKeyPrefix;
            return builderParam;
        }

    }

    /**
     * Lifecycle rule for a bucket. Allows supported Actions, such as deleting and changing storage
     * class, to be executed when certain Conditions are met.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle#actions">Object Lifecycle
     *     Management</a>
     */
    public static class LifecycleRuleSpec implements Serializable {

        private static final long serialVersionUID = -5739807320148748613L;

        private final LifecycleOperation ruleAction;

        private final LifecycleRuleCondition ruleCondition;

        /**
         * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
         *
         * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
         *     Management</a>
         */
        public static class LifecycleRuleCondition implements Serializable {

            private static final long serialVersionUID = -6482314338394768785L;

            private final Integer daysOld;

            private final DateTime creationCutoff;

            private final Integer newerVersionCount;

            private final Boolean liveStatus;

            private final List<StorageTier> matchingStorageTiers;

            /**
             * Builder for {@code LifecycleCondition}.
             */
            public static class LifecycleRuleConditionBuilder {

                private Integer daysOld;

                private DateTime creationCutoff;

                private Integer newerVersionCount;

                private Boolean liveStatus;

                private List<StorageTier> matchingStorageTiers;

                /**
                 * Builds a {@code LifecycleCondition} object. *
                 */
                public LifecycleRuleCondition buildCondition() {
                    return new LifecycleRuleCondition(this);
                }

                /**
                 * Sets a list of Storage Classes for a objects that satisfy the condition to execute the
                 * Action. *
                 */
                public LifecycleRuleConditionBuilder setMatchesStorageClass(List<StorageTier> matchingStorageTiers) {
                    this.matchingStorageTiers = matchingStorageTiers;
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
                 * Sets the age in days. This condition is satisfied when a Blob reaches the specified age
                 * (in days). When you specify the Age condition, you are specifying a Time to Live (TTL)
                 * for objects in a bucket with lifecycle management configured. The time when the Age
                 * condition is considered to be satisfied is calculated by adding the specified value to
                 * the object creation time.
                 */
                public LifecycleRuleConditionBuilder setAge(Integer daysOld) {
                    this.daysOld = daysOld;
                    return this;
                }

                /**
                 * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
                 * only live Blobs; if the value is false, it matches only archived objects. For the
                 * purposes of this condition, Blobs in non-versioned buckets are considered live.
                 */
                public LifecycleRuleConditionBuilder setIsLive(Boolean liveFlag) {
                    this.liveStatus = liveFlag;
                    return this;
                }

                private LifecycleRuleConditionBuilder() {
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

            }

            public List<StorageTier> getMatchesStorageClass() {
                return matchingStorageTiers;
            }

            public DateTime getCreatedBefore() {
                return creationCutoff;
            }

            public Boolean getIsLive() {
                return liveStatus;
            }

            public Integer getNumberOfNewerVersions() {
                return newerVersionCount;
            }

            public Integer getAge() {
                return daysOld;
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("age", daysOld).add("createBefore", creationCutoff).add("numberofNewerVersions", newerVersionCount).add("isLive", liveStatus).add("matchesStorageClass", matchingStorageTiers).toString();
            }

            public LifecycleRuleConditionBuilder toBuilder() {
                return newConditionBuilder().setAge(this.daysOld).setCreatedBefore(this.creationCutoff).setNumberOfNewerVersions(this.newerVersionCount).setIsLive(this.liveStatus).setMatchesStorageClass(this.matchingStorageTiers);
            }

            private LifecycleRuleCondition(LifecycleRuleConditionBuilder builderParam) {
                this.daysOld = builderParam.daysOld;
                this.creationCutoff = builderParam.creationCutoff;
                this.newerVersionCount = builderParam.newerVersionCount;
                this.liveStatus = builderParam.liveStatus;
                this.matchingStorageTiers = builderParam.matchingStorageTiers;
            }

            public static LifecycleRuleConditionBuilder newConditionBuilder() {
                return new LifecycleRuleConditionBuilder();
            }

        }

        /**
         * Base class for the Action to take when a Lifecycle Condition is met. Specific Actions are
         * expressed as subclasses of this class, accessed by static factory methods.
         */
        public abstract static class LifecycleOperation implements Serializable {

            private static final long serialVersionUID = 5801228724709173284L;

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
            public static SetStorageClassLifecycleOperation createSetStorageClassAction(StorageTier storageTier) {
                return new SetStorageClassLifecycleOperation(storageTier);
            }

            public abstract String getActionType();

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("actionType", getActionType()).toString();
            }

        }

        public static class RemoveLifecycleAction extends LifecycleOperation {

            public static final String TYPE = "Delete";

            private static final long serialVersionUID = -2050986302222644873L;

            @Override
            public String getActionType() {
                return TYPE;
            }

            private RemoveLifecycleAction() {
            }

        }

        public static class SetStorageClassLifecycleOperation extends LifecycleOperation {

            public static final String TYPE = "SetStorageClass";

            private static final long serialVersionUID = -62615467186000899L;

            private final StorageTier storageTier;

            public StorageTier getStorageClass() {
                return storageTier;
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("actionType", getActionType()).add("storageClass", storageTier.name()).toString();
            }

            @Override
            public String getActionType() {
                return TYPE;
            }

            private SetStorageClassLifecycleOperation(StorageTier storageTier) {
                this.storageTier = storageTier;
            }

        }

        Rule toProto() {
            Rule protoRule = new Rule();
            Rule.Action lifecycleOp = new Rule.Action().setType(ruleAction.getActionType());
            if (ruleAction.getActionType().equals(SetStorageClassLifecycleOperation.TYPE)) {
                lifecycleOp.setStorageClass(((SetStorageClassLifecycleOperation) ruleAction).getStorageClass().toString());
            }
            protoRule.setAction(lifecycleOp);
            Rule.Condition lifecycleCond = new Rule.Condition().setAge(ruleCondition.getAge()).setCreatedBefore(null == ruleCondition.getCreatedBefore() ? null : new DateTime(true, ruleCondition.getCreatedBefore().getValue(), 0)).setIsLive(ruleCondition.getIsLive()).setNumNewerVersions(ruleCondition.getNumberOfNewerVersions()).setMatchesStorageClass(null == ruleCondition.getMatchesStorageClass() ? null : transform(ruleCondition.getMatchesStorageClass(), Functions.toStringFunction()));
            protoRule.setCondition(lifecycleCond);
            return protoRule;
        }

        @Override
        public boolean equals(Object otherObject) {
            if (otherObject == this) {
                return true;
            }
            if (null == otherObject || otherObject.getClass() != getClass()) {
                return false;
            }
            final LifecycleRuleSpec otherConfig = (LifecycleRuleSpec) otherObject;
            return Objects.equals(toProto(), otherConfig.toProto());
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleAction, ruleCondition);
        }

        static LifecycleRuleSpec fromProto(Rule protoRule) {
            LifecycleOperation ruleAction;
            Rule.Action lifecycleOp = protoRule.getAction();
            switch(lifecycleOp.getType()) {
                case RemoveLifecycleAction.TYPE:
                    ruleAction = LifecycleOperation.createDeleteAction();
                    break;
                case SetStorageClassLifecycleOperation.TYPE:
                    ruleAction = LifecycleOperation.createSetStorageClassAction(StorageTier.fromValue(lifecycleOp.getStorageClass()));
                    break;
                default:
                    throw new UnsupportedOperationException("The specified lifecycle action " + lifecycleOp.getType() + " is not currently supported");
            }
            Rule.Condition lifecycleCond = protoRule.getCondition();
            LifecycleRuleCondition.LifecycleRuleConditionBuilder conditionCreator = LifecycleRuleCondition.newConditionBuilder().setAge(lifecycleCond.getAge()).setCreatedBefore(lifecycleCond.getCreatedBefore()).setIsLive(lifecycleCond.getIsLive()).setNumberOfNewerVersions(lifecycleCond.getNumNewerVersions()).setMatchesStorageClass(null == lifecycleCond.getMatchesStorageClass() ? null : transform(lifecycleCond.getMatchesStorageClass(), new Function<String, StorageTier>() {

                public StorageTier apply(String storageClass) {
                    return StorageTier.fromValue(storageClass);
                }
            }));
            return new LifecycleRuleSpec(ruleAction, conditionCreator.buildCondition());
        }

        public LifecycleRuleCondition getCondition() {
            return ruleCondition;
        }

        public LifecycleRuleSpec(LifecycleOperation lifecycleOp, LifecycleRuleCondition lifecycleCond) {
            if (null == lifecycleCond.getIsLive() && null == lifecycleCond.getAge() && null == lifecycleCond.getCreatedBefore() && null == lifecycleCond.getMatchesStorageClass() && null == lifecycleCond.getNumberOfNewerVersions()) {
                throw new IllegalArgumentException("You must specify at least one condition to use object lifecycle " + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
            }
            this.ruleAction = lifecycleOp;
            this.ruleCondition = lifecycleCond;
        }

        public LifecycleOperation getAction() {
            return ruleAction;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("lifecycleAction", ruleAction).add("lifecycleCondition", ruleCondition).toString();
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

        private static final String DELETION_RULE_SUPPORTED_ACTION = "Delete";

        private final VersionFilterType versionFilter;

        public enum VersionFilterType {

            AGE, CREATE_BEFORE, NUM_NEWER_VERSIONS, IS_LIVE, UNKNOWN
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionFilter);
        }

        abstract void fillCondition(Rule.Condition condition);

        static DeletionRule fromProto(Rule protoRule) {
            if (null != protoRule.getAction() && DELETION_RULE_SUPPORTED_ACTION.endsWith(protoRule.getAction().getType())) {
                Rule.Condition lifecycleCond = protoRule.getCondition();
                Integer daysOld = lifecycleCond.getAge();
                if (null != daysOld) {
                    return new AgeBasedDeletionRule(daysOld);
                }
                DateTime parsedDateTime = lifecycleCond.getCreatedBefore();
                if (null != parsedDateTime) {
                    return new CreationPrecedesDeletionRule(parsedDateTime.getValue());
                }
                Integer newerVersionCount = lifecycleCond.getNumNewerVersions();
                if (null != newerVersionCount) {
                    return new NewerVersionsCountDeletionRule(newerVersionCount);
                }
                Boolean liveStatus = lifecycleCond.getIsLive();
                if (null != liveStatus) {
                    return new LiveDeleteRulePredicate(liveStatus);
                }
            }
            return new RawDeletionRule(protoRule);
        }

        Rule toProto() {
            Rule protoRule = new Rule();
            protoRule.setAction(new Rule.Action().setType(DELETION_RULE_SUPPORTED_ACTION));
            Rule.Condition lifecycleCond = new Rule.Condition();
            fillCondition(lifecycleCond);
            protoRule.setCondition(lifecycleCond);
            return protoRule;
        }

        public VersionFilterType getType() {
            return versionFilter;
        }

        DeletionRule(VersionFilterType versionFilter) {
            this.versionFilter = versionFilter;
        }

        @Override
        public boolean equals(Object otherObject) {
            if (otherObject == this) {
                return true;
            }
            if (null == otherObject || otherObject.getClass() != getClass()) {
                return false;
            }
            final DeletionRule otherConfig = (DeletionRule) otherObject;
            return Objects.equals(toProto(), otherConfig.toProto());
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

        private final int lifetimeDays;

        public int getDaysToLive() {
            return lifetimeDays;
        }

        @Override
        void fillCondition(Rule.Condition lifecycleCond) {
            lifecycleCond.setAge(lifetimeDays);
        }

        /**
         * Creates an {@code AgeDeleteRule} object.
         *
         * @param lifetimeDays blobs' Time To Live expressed in days. The time when the age condition is
         *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
         *     following blob's creation time in UTC.
         */
        public AgeBasedDeletionRule(int lifetimeDays) {
            super(VersionFilterType.AGE);
            this.lifetimeDays = lifetimeDays;
        }

    }

    static class RawDeletionRule extends DeletionRule {

        private static final long serialVersionUID = -7166938278642301933L;

        private transient Rule protoRule;

        private void writeObject(ObjectOutputStream objectOutput) throws IOException {
            objectOutput.defaultWriteObject();
            objectOutput.writeUTF(protoRule.toString());
        }

        @Override
        Rule toProto() {
            return protoRule;
        }

        private void readObject(ObjectInputStream objectInput) throws IOException, ClassNotFoundException {
            objectInput.defaultReadObject();
            protoRule = new JacksonFactory().fromString(objectInput.readUTF(), Rule.class);
        }

        @Override
        void fillCondition(Rule.Condition condition) {
            throw new UnsupportedOperationException();
        }

        RawDeletionRule(Rule protoRule) {
            super(VersionFilterType.UNKNOWN);
            this.protoRule = protoRule;
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
    public static class CreationPrecedesDeletionRule extends DeletionRule {

        private static final long serialVersionUID = 881692650279195867L;

        private final long creationEpochMillis;

        public long getTimeMillis() {
            return creationEpochMillis;
        }

        @Override
        void fillCondition(Rule.Condition lifecycleCond) {
            lifecycleCond.setCreatedBefore(new DateTime(true, creationEpochMillis, 0));
        }

        /**
         * Creates an {@code CreatedBeforeDeleteRule} object.
         *
         * @param creationEpochMillis a date in UTC. Blobs that have been created before midnight of the provided
         *     date meet the delete condition
         */
        public CreationPrecedesDeletionRule(long creationEpochMillis) {
            super(VersionFilterType.CREATE_BEFORE);
            this.creationEpochMillis = creationEpochMillis;
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
    public static class NewerVersionsCountDeletionRule extends DeletionRule {

        private static final long serialVersionUID = -1955554976528303894L;

        private final int newerVersionCount;

        public int getNumNewerVersions() {
            return newerVersionCount;
        }

        @Override
        void fillCondition(Rule.Condition lifecycleCond) {
            lifecycleCond.setNumNewerVersions(newerVersionCount);
        }

        /**
         * Creates an {@code NumNewerVersionsDeleteRule} object.
         *
         * @param newerVersionCount the number of newer versions. A blob's version meets the delete
         *     condition when {@code numNewerVersions} newer versions are available.
         */
        public NewerVersionsCountDeletionRule(int newerVersionCount) {
            super(VersionFilterType.NUM_NEWER_VERSIONS);
            this.newerVersionCount = newerVersionCount;
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
    public static class LiveDeleteRulePredicate extends DeletionRule {

        private static final long serialVersionUID = -3502994563121313364L;

        private final boolean liveStatus;

        @Override
        void fillCondition(Rule.Condition lifecycleCond) {
            lifecycleCond.setIsLive(liveStatus);
        }

        /**
         * Creates an {@code IsLiveDeleteRule} object.
         *
         * @param liveStatus if set to {@code true} live blobs meet the delete condition. If set to {@code
         *     false} delete condition is met by archived blobs.
         */
        public LiveDeleteRulePredicate(boolean liveStatus) {
            super(VersionFilterType.IS_LIVE);
            this.liveStatus = liveStatus;
        }

        public boolean isLive() {
            return liveStatus;
        }

    }

    /**
     * Builder for {@code BucketInfo}.
     */
    public abstract static class BucketBuilder {

        @BetaApi
        abstract BucketBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyIsLocked);

        /**
         * Sets the default event-based hold for this bucket.
         */
        @BetaApi
        public abstract BucketBuilder setDefaultEventBasedHold(Boolean defaultEventBasedHold);

        /**
         * Sets the bucket's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract BucketBuilder setAcl(Iterable<AclEntry> acl);

        /**
         * Creates a {@code BucketInfo} object.
         */
        public abstract BucketMetadata buildBucket();

        @BetaApi
        abstract BucketBuilder setRetentionEffectiveTime(Long retentionEffectiveTime);

        /**
         * Sets the default Cloud KMS key name for this bucket.
         */
        public abstract BucketBuilder setDefaultKmsKeyName(String defaultKmsKeyName);

        /**
         * Sets the bucket's lifecycle configuration as a number of lifecycle rules, consisting of an
         * action and a condition.
         *
         * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
         *     Management</a>
         */
        public abstract BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleSpec> rules);

        /**
         * Sets the custom object to return when a requested resource is not found.
         */
        public abstract BucketBuilder setNotFoundPage(String notFoundPage);

        abstract BucketBuilder setOwner(BaseEntity owner);

        abstract BucketBuilder setGeneratedId(String generatedId);

        /**
         * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
         * this region. A list of supported values is available <a
         * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
         */
        public abstract BucketBuilder setLocation(String location);

        /**
         * Sets the IamConfiguration to specify whether IAM access should be enabled.
         *
         * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
         *     Only</a>
         */
        @BetaApi
        public abstract BucketBuilder setIamConfiguration(BucketIamConfiguration iamConfiguration);

        abstract BucketBuilder setMetageneration(Long metageneration);

        abstract BucketBuilder setEtag(String etag);

        abstract BucketBuilder setLocationType(String locationType);

        /**
         * Sets the bucket's lifecycle configuration as a number of delete rules.
         *
         * @deprecated Use {@code setLifecycleRules} instead, as in {@code
         *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRule(
         *     LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(5).build())));}
         */
        @Deprecated
        public abstract BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends DeletionRule> rules);

        /**
         * Sets the label of this bucket.
         */
        public abstract BucketBuilder setLabels(Map<String, String> labels);

        public abstract BucketBuilder setLogging(LoggingConfig logging);

        BucketBuilder() {
        }

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
         * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
         *
         * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
         *     Sharing (CORS)</a>
         */
        public abstract BucketBuilder setCors(Iterable<CorsConfig> cors);

        /**
         * Sets the bucket's website index page. Behaves as the bucket's directory index where missing
         * blobs are treated as potential directories.
         */
        public abstract BucketBuilder setIndexPage(String indexPage);

        /**
         * Sets the bucket's name.
         */
        public abstract BucketBuilder setName(String name);

        /**
         * If policy is not locked this value can be cleared, increased, and decreased. If policy is
         * locked the retention period can only be increased.
         */
        @BetaApi
        public abstract BucketBuilder setRetentionPeriod(Long retentionPeriod);

        abstract BucketBuilder setSelfLink(String selfLink);

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
         * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
         * determines the SLA and the cost of storage. A list of supported values is available <a
         * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
         */
        public abstract BucketBuilder setStorageClass(StorageTier storageClass);

        abstract BucketBuilder setCreateTime(Long createTime);

    }

    static final class BucketBuilderImpl extends BucketBuilder {

        private String uniqueIdentifier;

        private String bucketName;

        private BaseEntity ownerEntity;

        private String selfLinkUri;

        private Boolean requesterBilling;

        private Boolean versioningOn;

        private String indexDocument;

        private String notFoundDocument;

        private List<DeletionRule> deletionRules;

        private List<LifecycleRuleSpec> lifecyclePolicies;

        private StorageTier storageTier;

        private String region;

        private String entityTag;

        private Long creationTime;

        private Long metadataGeneration;

        private List<CorsConfig> corsConfigs;

        private List<AclEntry> aclEntries;

        private List<AclEntry> defaultAclEntries;

        private Map<String, String> labelMap;

        private String kmsDefaultKey;

        private Boolean eventBasedHoldDefault;

        private Long retentionEffectiveTimestamp;

        private Boolean retentionPolicyLocked;

        private Long retentionDuration;

        private BucketIamConfiguration iamConfig;

        private String locationClass;

        private LoggingConfig logConfig;

        @Override
        public BucketMetadata.BucketBuilder setStorageClass(StorageTier storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setRetentionEffectiveTime(Long retentionEffectiveTimestamp) {
            this.retentionEffectiveTimestamp = firstNonNull(retentionEffectiveTimestamp, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setIamConfiguration(BucketIamConfiguration iamConfig) {
            this.iamConfig = iamConfig;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setRetentionPeriod(Long retentionDuration) {
            this.retentionDuration = firstNonNull(retentionDuration, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setCreateTime(Long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setLocationType(String locationClass) {
            this.locationClass = locationClass;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleSpec> deletionPolicies) {
            this.lifecyclePolicies = null != deletionPolicies ? ImmutableList.copyOf(deletionPolicies) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setIndexPage(String indexDocument) {
            this.indexDocument = indexDocument;
            return this;
        }

        @Override
        public BucketMetadata buildBucket() {
            checkNotNull(bucketName);
            return new BucketMetadata(this);
        }

        @Override
        BucketMetadata.BucketBuilder setMetageneration(Long metadataGeneration) {
            this.metadataGeneration = metadataGeneration;
            return this;
        }

        BucketBuilderImpl(String bucketName) {
            this.bucketName = bucketName;
        }

        @Override
        BucketMetadata.BucketBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setCors(Iterable<CorsConfig> corsConfigs) {
            this.corsConfigs = null != corsConfigs ? ImmutableList.copyOf(corsConfigs) : null;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setGeneratedId(String uniqueIdentifier) {
            this.uniqueIdentifier = uniqueIdentifier;
            return this;
        }

        /**
         * @deprecated Use {@code setLifecycleRules} method instead. *
         */
        @Override
        @Deprecated
        public BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends DeletionRule> deletionPolicies) {
            this.deletionRules = null != deletionPolicies ? ImmutableList.copyOf(deletionPolicies) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLogging(LoggingConfig logConfig) {
            this.logConfig = logConfig;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setVersioningEnabled(Boolean versioningEnabled) {
            this.versioningOn = firstNonNull(versioningEnabled, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setSelfLink(String selfLinkUri) {
            this.selfLinkUri = selfLinkUri;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setRequesterPays(Boolean versioningEnabled) {
            this.requesterBilling = firstNonNull(versioningEnabled, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        BucketBuilderImpl(BucketMetadata metadata) {
            uniqueIdentifier = metadata.uniqueIdentifier;
            bucketName = metadata.bucketName;
            entityTag = metadata.entityTag;
            creationTime = metadata.creationTime;
            metadataGeneration = metadata.metadataGeneration;
            region = metadata.region;
            storageTier = metadata.storageTier;
            corsConfigs = metadata.corsConfigs;
            aclEntries = metadata.aclEntries;
            defaultAclEntries = metadata.defaultAclEntries;
            ownerEntity = metadata.ownerEntity;
            selfLinkUri = metadata.selfLinkUri;
            versioningOn = metadata.versioningOn;
            indexDocument = metadata.indexDocument;
            notFoundDocument = metadata.notFoundDocument;
            deletionRules = metadata.deletionRules;
            lifecyclePolicies = metadata.lifecyclePolicies;
            labelMap = metadata.labelMap;
            requesterBilling = metadata.requesterBilling;
            kmsDefaultKey = metadata.kmsDefaultKey;
            eventBasedHoldDefault = metadata.eventBasedHoldDefault;
            retentionEffectiveTimestamp = metadata.retentionEffectiveTimestamp;
            retentionPolicyLocked = metadata.retentionPolicyLocked;
            retentionDuration = metadata.retentionDuration;
            iamConfig = metadata.iamConfig;
            locationClass = metadata.locationClass;
            logConfig = metadata.logConfig;
        }

        @Override
        BucketMetadata.BucketBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyLocked) {
            this.retentionPolicyLocked = firstNonNull(retentionPolicyLocked, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLocation(String region) {
            this.region = region;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setDefaultAcl(Iterable<AclEntry> aclEntries) {
            this.defaultAclEntries = null != aclEntries ? ImmutableList.copyOf(aclEntries) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setName(String bucketName) {
            this.bucketName = checkNotNull(bucketName);
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setOwner(BaseEntity ownerEntity) {
            this.ownerEntity = ownerEntity;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setAcl(Iterable<AclEntry> aclEntries) {
            this.aclEntries = null != aclEntries ? ImmutableList.copyOf(aclEntries) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setDefaultEventBasedHold(Boolean eventBasedHoldDefault) {
            this.eventBasedHoldDefault = firstNonNull(eventBasedHoldDefault, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setNotFoundPage(String notFoundDocument) {
            this.notFoundDocument = notFoundDocument;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLabels(Map<String, String> labelMap) {
            if (null != labelMap) {
                this.labelMap = Maps.transformValues(labelMap, new Function<String, String>() {

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
        public BucketMetadata.BucketBuilder setDefaultKmsKeyName(String kmsDefaultKey) {
            this.kmsDefaultKey = null != kmsDefaultKey ? kmsDefaultKey : Data.<String>nullOf(String.class);
            return this;
        }

    }

    Bucket toProto() {
        Bucket bucketProto = new Bucket();
        bucketProto.setId(uniqueIdentifier);
        bucketProto.setName(bucketName);
        bucketProto.setEtag(entityTag);
        if (null != creationTime) {
            bucketProto.setTimeCreated(new DateTime(creationTime));
        }
        if (null != metadataGeneration) {
            bucketProto.setMetageneration(metadataGeneration);
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
            bucketProto.setCors(transform(corsConfigs, CorsConfig.TO_PROTO_FUNCTION));
        }
        if (null != aclEntries) {
            bucketProto.setAcl(transform(aclEntries, new Function<AclEntry, BucketAccessControl>() {

                @Override
                public BucketAccessControl apply(AclEntry acl) {
                    return acl.toBucketProto();
                }
            }));
        }
        if (null != defaultAclEntries) {
            bucketProto.setDefaultObjectAcl(transform(defaultAclEntries, new Function<AclEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AclEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != ownerEntity) {
            bucketProto.setOwner(new Owner().setEntity(ownerEntity.toProto()));
        }
        bucketProto.setSelfLink(selfLinkUri);
        if (null != versioningOn) {
            bucketProto.setVersioning(new Versioning().setEnabled(versioningOn));
        }
        if (null != requesterBilling) {
            Bucket.Billing billingProto = new Bucket.Billing();
            billingProto.setRequesterPays(requesterBilling);
            bucketProto.setBilling(billingProto);
        }
        if (null != indexDocument || null != notFoundDocument) {
            Website websiteProto = new Website();
            websiteProto.setMainPageSuffix(indexDocument);
            websiteProto.setNotFoundPage(notFoundDocument);
            bucketProto.setWebsite(websiteProto);
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
            deletionPolicies.addAll(transform(lifecyclePolicies, new Function<LifecycleRuleSpec, Rule>() {

                @Override
                public Rule apply(LifecycleRuleSpec lifecycleRule) {
                    return lifecycleRule.toProto();
                }
            }));
        }
        if (!deletionPolicies.isEmpty()) {
            Lifecycle lifecycleProto = new Lifecycle();
            lifecycleProto.setRule(ImmutableList.copyOf(deletionPolicies));
            bucketProto.setLifecycle(lifecycleProto);
        }
        if (null != labelMap) {
            bucketProto.setLabels(labelMap);
        }
        if (null != kmsDefaultKey) {
            bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(kmsDefaultKey));
        }
        if (null != eventBasedHoldDefault) {
            bucketProto.setDefaultEventBasedHold(eventBasedHoldDefault);
        }
        if (null != retentionDuration) {
            if (!Data.isNull(retentionDuration)) {
                Bucket.RetentionPolicy retentionPolicyProto = new Bucket.RetentionPolicy();
                retentionPolicyProto.setRetentionPeriod(retentionDuration);
                if (null != retentionEffectiveTimestamp) {
                    retentionPolicyProto.setEffectiveTime(new DateTime(retentionEffectiveTimestamp));
                }
                if (null != retentionPolicyLocked) {
                    retentionPolicyProto.setIsLocked(retentionPolicyLocked);
                }
                bucketProto.setRetentionPolicy(retentionPolicyProto);
            } else {
                bucketProto.setRetentionPolicy(Data.<Bucket.RetentionPolicy>nullOf(Bucket.RetentionPolicy.class));
            }
        }
        if (null != iamConfig) {
            bucketProto.setIamConfiguration(iamConfig.toProto());
        }
        if (null != logConfig) {
            bucketProto.setLogging(logConfig.toProto());
        }
        return bucketProto;
    }

    /**
     * Returns the IAM configuration
     */
    @BetaApi
    public BucketIamConfiguration getIamConfiguration() {
        return iamConfig;
    }

    /**
     * Creates a {@code BucketInfo} object for the provided bucket name.
     */
    public static BucketMetadata ofName(String bucketName) {
        return newBucketBuilder(bucketName).buildBucket();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", bucketName).toString();
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
     * Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket.
     */
    public String getDefaultKmsKeyName() {
        return kmsDefaultKey;
    }

    /**
     * Returns the URI of this bucket as a string.
     */
    public String getSelfLink() {
        return selfLinkUri;
    }

    /**
     * Returns the bucket's locationType.
     *
     * @see <a href="https://cloud.google.com/storage/docs/bucket-locations">Bucket LocationType</a>
     */
    public String getLocationType() {
        return locationClass;
    }

    @Override
    public boolean equals(Object otherObject) {
        return this == otherObject || null != otherObject && otherObject.getClass().equals(BucketMetadata.class) && Objects.equals(toProto(), ((BucketMetadata) otherObject).toProto());
    }

    public List<? extends LifecycleRuleSpec> getLifecycleRules() {
        return lifecyclePolicies;
    }

    /**
     * Returns the bucket's name.
     */
    public String getName() {
        return bucketName;
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
     * Returns the retention policy retention period.
     */
    @BetaApi
    public Long getRetentionPeriod() {
        return retentionDuration;
    }

    /**
     * Returns the bucket's owner. This is always the project team's owner group.
     */
    public BaseEntity getOwner() {
        return ownerEntity;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BucketAttribute#VERSIONING} is selected in a {@link
     * Storage#get(String, Storage.BucketGetOptions...)} and versions for the bucket is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BucketAttribute#VERSIONING} is selected in a {@link
     * Storage#get(String, Storage.BucketGetOptions...)}, but versions for the bucket is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BucketAttribute#VERSIONING} is not selected in a {@link
     * Storage#get(String, Storage.BucketGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
     * request for example {@link Storage#update(BucketMetadata, Storage.BucketTargetOptions...)} in which
     * case the value of versions will remain {@code false} for for the given instance.
     */
    public Boolean isVersioningEnabled() {
        return Data.isNull(versioningOn) ? null : versioningOn;
    }

    /**
     * Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name.
     */
    public static BucketBuilder newBucketBuilder(String bucketName) {
        return new BucketBuilderImpl(bucketName);
    }

    /**
     * Returns a {@code Boolean} with either {@code true} or {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BucketAttribute#RETENTION_POLICY} is selected in a {@link
     * Storage#get(String, Storage.BucketGetOptions...)} and retention policy for the bucket is locked.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BucketAttribute#RETENTION_POLICY} is selected in a {@link
     * Storage#get(String, Storage.BucketGetOptions...)}, but retention policy for the bucket is not
     * locked. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BucketAttribute#RETENTION_POLICY} is not selected in a {@link
     * Storage#get(String, Storage.BucketGetOptions...)}, and the state for this field is unknown.
     */
    @BetaApi
    public Boolean isRetentionPolicyLocked() {
        return Data.isNull(retentionPolicyLocked) ? null : retentionPolicyLocked;
    }

    /**
     * Returns the default access control configuration for this bucket's blobs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AclEntry> getDefaultAcl() {
        return defaultAclEntries;
    }

    /**
     * Returns the labels for this bucket.
     */
    public Map<String, String> getLabels() {
        return labelMap;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
     * {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link Storage.BucketAttribute#BILLING}
     * is selected in a {@link Storage#get(String, Storage.BucketGetOptions...)} and requester pays for
     * the bucket is enabled.
     *
     * <p>Case 2: {@code false} the field {@link Storage.BucketAttribute#BILLING}
     * in a {@link Storage#get(String, Storage.BucketGetOptions...)} is selected and requester pays for
     * the bucket is disable.
     *
     * <p>Case 3: {@code null} the field {@link Storage.BucketAttribute#BILLING}
     * in a {@link Storage#get(String, Storage.BucketGetOptions...)} is not selected, the value is
     * unknown.
     */
    public Boolean isRequesterPays() {
        return Data.isNull(requesterBilling) ? null : requesterBilling;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * Storage#get(String, Storage.BucketGetOptions...)} and default event-based hold for the bucket is
     * enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * Storage#get(String, Storage.BucketGetOptions...)}, but default event-based hold for the bucket
     * is not enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is not selected in a
     * {@link Storage#get(String, Storage.BucketGetOptions...)}, and the state for this field is
     * unknown.
     *
     * <p>Case 3: {@code false} default event-based hold is explicitly set to false using in a {@link
     * BucketBuilder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * Storage#update(BucketMetadata, Storage.BucketTargetOptions...)} in which case the value of default
     * event-based hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getDefaultEventBasedHold() {
        return Data.isNull(eventBasedHoldDefault) ? null : eventBasedHoldDefault;
    }

    /**
     * Returns bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public String getIndexPage() {
        return indexDocument;
    }

    /**
     * Returns the Logging
     */
    public LoggingConfig getLogging() {
        return logConfig;
    }

    /**
     * Returns the metadata generation of this bucket.
     */
    public Long getMetageneration() {
        return metadataGeneration;
    }

    /**
     * Returns a builder for the current bucket.
     */
    public BucketBuilder asBuilder() {
        return new BucketBuilderImpl(this);
    }

    /**
     * Returns the service-generated id for the bucket.
     */
    public String getGeneratedId() {
        return uniqueIdentifier;
    }

    static BucketMetadata fromProto(Bucket bucketProto) {
        BucketBuilder builderParam = new BucketBuilderImpl(bucketProto.getName());
        if (null != bucketProto.getId()) {
            builderParam.setGeneratedId(bucketProto.getId());
        }
        if (null != bucketProto.getEtag()) {
            builderParam.setEtag(bucketProto.getEtag());
        }
        if (null != bucketProto.getMetageneration()) {
            builderParam.setMetageneration(bucketProto.getMetageneration());
        }
        if (null != bucketProto.getSelfLink()) {
            builderParam.setSelfLink(bucketProto.getSelfLink());
        }
        if (null != bucketProto.getTimeCreated()) {
            builderParam.setCreateTime(bucketProto.getTimeCreated().getValue());
        }
        if (null != bucketProto.getLocation()) {
            builderParam.setLocation(bucketProto.getLocation());
        }
        if (null != bucketProto.getStorageClass()) {
            builderParam.setStorageClass(StorageTier.fromValue(bucketProto.getStorageClass()));
        }
        if (null != bucketProto.getCors()) {
            builderParam.setCors(transform(bucketProto.getCors(), CorsConfig.FROM_PROTO_FUNCTION));
        }
        if (null != bucketProto.getAcl()) {
            builderParam.setAcl(transform(bucketProto.getAcl(), new Function<BucketAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(BucketAccessControl bucketAccessControl) {
                    return AclEntry.fromProto(bucketAccessControl);
                }
            }));
        }
        if (null != bucketProto.getDefaultObjectAcl()) {
            builderParam.setDefaultAcl(transform(bucketProto.getDefaultObjectAcl(), new Function<ObjectAccessControl, AclEntry>() {

                @Override
                public AclEntry apply(ObjectAccessControl objectAccessControl) {
                    return AclEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (null != bucketProto.getOwner()) {
            builderParam.setOwner(BaseEntity.fromProto(bucketProto.getOwner().getEntity()));
        }
        if (null != bucketProto.getVersioning()) {
            builderParam.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
        }
        Website websiteProto = bucketProto.getWebsite();
        if (null != websiteProto) {
            builderParam.setIndexPage(websiteProto.getMainPageSuffix());
            builderParam.setNotFoundPage(websiteProto.getNotFoundPage());
        }
        if (null != bucketProto.getLifecycle() && null != bucketProto.getLifecycle().getRule()) {
            builderParam.setLifecycleRules(transform(bucketProto.getLifecycle().getRule(), new Function<Rule, LifecycleRuleSpec>() {

                @Override
                public BucketMetadata.LifecycleRuleSpec apply(Rule rule) {
                    return LifecycleRuleSpec.fromProto(rule);
                }
            }));
            builderParam.setDeleteRules(transform(bucketProto.getLifecycle().getRule(), new Function<Rule, DeletionRule>() {

                @Override
                public BucketMetadata.DeletionRule apply(Rule rule) {
                    return DeletionRule.fromProto(rule);
                }
            }));
        }
        if (null != bucketProto.getLabels()) {
            builderParam.setLabels(bucketProto.getLabels());
        }
        Bucket.Billing billingProto = bucketProto.getBilling();
        if (null != billingProto) {
            builderParam.setRequesterPays(billingProto.getRequesterPays());
        }
        Encryption cryptoConfig = bucketProto.getEncryption();
        if (null != cryptoConfig && null != cryptoConfig.getDefaultKmsKeyName() && !cryptoConfig.getDefaultKmsKeyName().isEmpty()) {
            builderParam.setDefaultKmsKeyName(cryptoConfig.getDefaultKmsKeyName());
        }
        if (null != bucketProto.getDefaultEventBasedHold()) {
            builderParam.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
        }
        Bucket.RetentionPolicy retentionPolicyProto = bucketProto.getRetentionPolicy();
        if (null != retentionPolicyProto) {
            if (null != retentionPolicyProto.getEffectiveTime()) {
                builderParam.setRetentionEffectiveTime(retentionPolicyProto.getEffectiveTime().getValue());
            }
            if (null != retentionPolicyProto.getIsLocked()) {
                builderParam.setRetentionPolicyIsLocked(retentionPolicyProto.getIsLocked());
            }
            if (null != retentionPolicyProto.getRetentionPeriod()) {
                builderParam.setRetentionPeriod(retentionPolicyProto.getRetentionPeriod());
            }
        }
        Bucket.IamConfiguration iamConfig = bucketProto.getIamConfiguration();
        if (null != bucketProto.getLocationType()) {
            builderParam.setLocationType(bucketProto.getLocationType());
        }
        if (null != iamConfig) {
            builderParam.setIamConfiguration(BucketIamConfiguration.fromProto(iamConfig));
        }
        Bucket.Logging logConfig = bucketProto.getLogging();
        if (null != logConfig) {
            builderParam.setLogging(LoggingConfig.fromProto(logConfig));
        }
        return builderParam.buildBucket();
    }

    /**
     * Returns the custom object to return when a requested resource is not found.
     */
    public String getNotFoundPage() {
        return notFoundDocument;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucketName);
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
     * Returns bucket's lifecycle configuration as a number of delete rules.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
     */
    @Deprecated
    public List<? extends DeletionRule> getDeleteRules() {
        return deletionRules;
    }

    /**
     * Returns the bucket's Cross-Origin Resource Sharing (CORS) configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource Sharing
     *     (CORS)</a>
     */
    public List<CorsConfig> getCors() {
        return corsConfigs;
    }

    /**
     * Returns the time at which the bucket was created.
     */
    public Long getCreateTime() {
        return creationTime;
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
     * Returns HTTP 1.1 Entity tag for the bucket.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return entityTag;
    }

    BucketMetadata(BucketBuilderImpl builderParam) {
        uniqueIdentifier = builderParam.uniqueIdentifier;
        bucketName = builderParam.bucketName;
        entityTag = builderParam.entityTag;
        creationTime = builderParam.creationTime;
        metadataGeneration = builderParam.metadataGeneration;
        region = builderParam.region;
        storageTier = builderParam.storageTier;
        corsConfigs = builderParam.corsConfigs;
        aclEntries = builderParam.aclEntries;
        defaultAclEntries = builderParam.defaultAclEntries;
        ownerEntity = builderParam.ownerEntity;
        selfLinkUri = builderParam.selfLinkUri;
        versioningOn = builderParam.versioningOn;
        indexDocument = builderParam.indexDocument;
        notFoundDocument = builderParam.notFoundDocument;
        deletionRules = builderParam.deletionRules;
        lifecyclePolicies = builderParam.lifecyclePolicies;
        labelMap = builderParam.labelMap;
        requesterBilling = builderParam.requesterBilling;
        kmsDefaultKey = builderParam.kmsDefaultKey;
        eventBasedHoldDefault = builderParam.eventBasedHoldDefault;
        retentionEffectiveTimestamp = builderParam.retentionEffectiveTimestamp;
        retentionPolicyLocked = builderParam.retentionPolicyLocked;
        retentionDuration = builderParam.retentionDuration;
        iamConfig = builderParam.iamConfig;
        locationClass = builderParam.locationClass;
        logConfig = builderParam.logConfig;
    }

}
