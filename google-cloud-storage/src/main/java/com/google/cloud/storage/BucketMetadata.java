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
import com.google.cloud.storage.AccessControlEntry.AbstractEntity;
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

    static final Function<BucketMetadata, com.google.api.services.storage.model.Bucket> TO_PROTOBUF_FUNCTION = new Function<BucketMetadata, com.google.api.services.storage.model.Bucket>() {

        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketMetadata bucketInfo) {
            return bucketInfo.toProto();
        }
    };

    private static final long serialVersionUID = -4712013629621638459L;

    private final String generatedIdentifier;

    private final String displayName;

    private final AccessControlEntry.AbstractEntity ownerEntity;

    private final String selfLinkUrl;

    private final Boolean requesterBillingEnabled;

    private final Boolean versioningActive;

    private final String indexDocument;

    private final String notFoundDocument;

    private final List<DeletionRule> deletionRules;

    private final List<LifecycleRuleDefinition> lifecyclePolicies;

    private final String entityTag;

    private final Long creationTime;

    private final Long metaGeneration;

    private final List<CorsConfiguration> corsConfigs;

    private final List<AccessControlEntry> accessControlList;

    private final List<AccessControlEntry> defaultAccessControlList;

    private final String region;

    private final StorageClassType storageTier;

    private final Map<String, String> labelsMap;

    private final String defaultKmsKey;

    private final Boolean eventBasedHoldDefault;

    private final Long retentionEffectiveTimestamp;

    private final Boolean retentionPolicyLocked;

    private final Long retentionDuration;

    private final BucketIamConfiguration iamConfig;

    private final String locationScope;

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

        private Long uniformBucketLevelAccessLockTime;

        /**
         * Builder for {@code IamConfiguration}
         */
        public static class UniformBucketLevelAccessBuilder {

            private Boolean uniformBucketLevelAccessEnabled;

            private Long uniformBucketLevelAccessLockTime;

            /**
             * Sets the deadline for switching {@code uniformBucketLevelAccess.enabled} back to false.
             * After this time passes, calls to do so will fail. This is package-private, since in general
             * this field should never be set by a user--it's automatically set by the backend when {@code
             * enabled} is set to true.
             */
            UniformBucketLevelAccessBuilder setUniformBucketLevelAccessLockedTime(Long uniformBucketLevelAccessLockTime) {
                this.uniformBucketLevelAccessLockTime = uniformBucketLevelAccessLockTime;
                return this;
            }

            /**
             * Builds an {@code IamConfiguration} object
             */
            public BucketIamConfiguration buildBucketIamConfiguration() {
                return new BucketIamConfiguration(this);
            }

            /**
             * Deprecated in favor of setUniformBucketLevelAccessLockedTime().
             */
            @Deprecated
            BucketMetadata.BucketIamConfiguration.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long policyLockTimestamp) {
                this.uniformBucketLevelAccessLockTime = policyLockTimestamp;
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
            public UniformBucketLevelAccessBuilder setIsUniformBucketLevelAccessEnabled(Boolean uniformBucketLevelAccessEnabled) {
                this.uniformBucketLevelAccessEnabled = uniformBucketLevelAccessEnabled;
                return this;
            }

            /**
             * Deprecated in favor of setIsUniformBucketLevelAccessEnabled().
             */
            @Deprecated
            public BucketMetadata.BucketIamConfiguration.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean bucketPolicyEnabled) {
                this.uniformBucketLevelAccessEnabled = bucketPolicyEnabled;
                return this;
            }

        }

        private BucketIamConfiguration(UniformBucketLevelAccessBuilder uniformBuilder) {
            this.uniformBucketLevelAccessEnabled = uniformBuilder.uniformBucketLevelAccessEnabled;
            this.uniformBucketLevelAccessLockTime = uniformBuilder.uniformBucketLevelAccessLockTime;
        }

        /**
         * Deprecated in favor of uniformBucketLevelAccessLockedTime().
         */
        @Deprecated
        public Long getBucketPolicyOnlyLockedTime() {
            return uniformBucketLevelAccessLockTime;
        }

        static BucketIamConfiguration fromProto(Bucket.IamConfiguration iamConfig) {
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessConfig = iamConfig.getUniformBucketLevelAccess();
            DateTime lockTimestamp = uniformAccessConfig.getLockedTime();
            return newUniformBucketLevelAccessBuilder().setIsUniformBucketLevelAccessEnabled(uniformAccessConfig.getEnabled()).setUniformBucketLevelAccessLockedTime(null == lockTimestamp ? null : lockTimestamp.getValue()).buildBucketIamConfiguration();
        }

        Bucket.IamConfiguration toProto() {
            Bucket.IamConfiguration iamConfig = new Bucket.IamConfiguration();
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessConfig = new Bucket.IamConfiguration.UniformBucketLevelAccess();
            uniformAccessConfig.setEnabled(uniformBucketLevelAccessEnabled);
            uniformAccessConfig.setLockedTime(null == uniformBucketLevelAccessLockTime ? null : new DateTime(uniformBucketLevelAccessLockTime));
            iamConfig.setUniformBucketLevelAccess(uniformAccessConfig);
            return iamConfig;
        }

        public Long getUniformBucketLevelAccessLockedTime() {
            return uniformBucketLevelAccessLockTime;
        }

        public Boolean isUniformBucketLevelAccessEnabled() {
            return uniformBucketLevelAccessEnabled;
        }

        public static UniformBucketLevelAccessBuilder newUniformBucketLevelAccessBuilder() {
            return new UniformBucketLevelAccessBuilder();
        }

        /**
         * Deprecated in favor of isUniformBucketLevelAccessEnabled().
         */
        @Deprecated
        public Boolean isBucketPolicyOnlyEnabled() {
            return uniformBucketLevelAccessEnabled;
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

        @Override
        public int hashCode() {
            return Objects.hash(uniformBucketLevelAccessEnabled, uniformBucketLevelAccessLockTime);
        }

        public UniformBucketLevelAccessBuilder toUniformBucketLevelAccessBuilder() {
            UniformBucketLevelAccessBuilder uniformBuilder = new UniformBucketLevelAccessBuilder();
            uniformBuilder.uniformBucketLevelAccessEnabled = uniformBucketLevelAccessEnabled;
            uniformBuilder.uniformBucketLevelAccessLockTime = uniformBucketLevelAccessLockTime;
            return uniformBuilder;
        }

    }

    /**
     * The bucket's logging configuration, which defines the destination bucket and optional name
     * prefix for the current bucket's logs.
     */
    public static class LoggingConfig implements Serializable {

        private static final long serialVersionUID = -708892101216778492L;

        private String loggingBucket;

        private String logPrefix;

        public static class LogConfigBuilder {

            private String loggingBucket;

            private String logPrefix;

            /**
             * A prefix for log object names.
             */
            public LogConfigBuilder setLogObjectPrefix(String logPrefix) {
                this.logPrefix = logPrefix;
                return this;
            }

            /**
             * Builds an {@code Logging} object
             */
            public LoggingConfig buildLoggingConfig() {
                return new LoggingConfig(this);
            }

            /**
             * The destination bucket where the current bucket's logs should be placed.
             */
            public LogConfigBuilder setLogBucket(String loggingBucket) {
                this.loggingBucket = loggingBucket;
                return this;
            }

        }

        private LoggingConfig(LogConfigBuilder uniformBuilder) {
            this.loggingBucket = uniformBuilder.loggingBucket;
            this.logPrefix = uniformBuilder.logPrefix;
        }

        static LoggingConfig fromProto(Bucket.Logging loggingConfig) {
            return newLogConfigBuilder().setLogBucket(loggingConfig.getLogBucket()).setLogObjectPrefix(loggingConfig.getLogObjectPrefix()).buildLoggingConfig();
        }

        @Override
        public int hashCode() {
            return Objects.hash(loggingBucket, logPrefix);
        }

        Bucket.Logging toProto() {
            Bucket.Logging loggingConfig = new Bucket.Logging();
            loggingConfig.setLogBucket(loggingBucket);
            loggingConfig.setLogObjectPrefix(logPrefix);
            return loggingConfig;
        }

        public String getLogObjectPrefix() {
            return logPrefix;
        }

        public String getLogBucket() {
            return loggingBucket;
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

        public LogConfigBuilder toBuilder() {
            LogConfigBuilder uniformBuilder = new LogConfigBuilder();
            uniformBuilder.loggingBucket = loggingBucket;
            uniformBuilder.logPrefix = logPrefix;
            return uniformBuilder;
        }

        public static LogConfigBuilder newLogConfigBuilder() {
            return new LogConfigBuilder();
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

        private final AbstractLifecycleAction ruleAction;

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

            private final DateTime creationTimestamp;

            private final Integer newerVersionsCount;

            private final Boolean liveFlag;

            private final List<StorageClassType> matchingStorageTypes;

            /**
             * Builder for {@code LifecycleCondition}.
             */
            public static class LifecycleConditionBuilder {

                private Integer daysOld;

                private DateTime creationTimestamp;

                private Integer newerVersionsCount;

                private Boolean liveFlag;

                private List<StorageClassType> matchingStorageTypes;

                /**
                 * Sets a list of Storage Classes for a objects that satisfy the condition to execute the
                 * Action. *
                 */
                public LifecycleConditionBuilder setMatchesStorageClass(List<StorageClassType> matchingStorageTypes) {
                    this.matchingStorageTypes = matchingStorageTypes;
                    return this;
                }

                /**
                 * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
                 * only live Blobs; if the value is false, it matches only archived objects. For the
                 * purposes of this condition, Blobs in non-versioned buckets are considered live.
                 */
                public LifecycleConditionBuilder setIsLive(Boolean active) {
                    this.liveFlag = active;
                    return this;
                }

                /**
                 * Builds a {@code LifecycleCondition} object. *
                 */
                public LifecycleRuleCondition buildLifecycleCondition() {
                    return new LifecycleRuleCondition(this);
                }

                private LifecycleConditionBuilder() {
                }

                /**
                 * Sets the date a Blob should be created before for an Action to be executed. Note that
                 * only the date will be considered, if the time is specified it will be truncated. This
                 * condition is satisfied when an object is created before midnight of the specified date in
                 * UTC. *
                 */
                public LifecycleConditionBuilder setCreatedBefore(DateTime creationTimestamp) {
                    this.creationTimestamp = creationTimestamp;
                    return this;
                }

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
                 * Sets the number of newer versions a Blob should have for an Action to be executed.
                 * Relevant only when versioning is enabled on a bucket. *
                 */
                public LifecycleConditionBuilder setNumberOfNewerVersions(Integer newerVersionsCount) {
                    this.newerVersionsCount = newerVersionsCount;
                    return this;
                }

            }

            public Boolean getIsLive() {
                return liveFlag;
            }

            public Integer getAge() {
                return daysOld;
            }

            public List<StorageClassType> getMatchesStorageClass() {
                return matchingStorageTypes;
            }

            public Integer getNumberOfNewerVersions() {
                return newerVersionsCount;
            }

            private LifecycleRuleCondition(LifecycleConditionBuilder uniformBuilder) {
                this.daysOld = uniformBuilder.daysOld;
                this.creationTimestamp = uniformBuilder.creationTimestamp;
                this.newerVersionsCount = uniformBuilder.newerVersionsCount;
                this.liveFlag = uniformBuilder.liveFlag;
                this.matchingStorageTypes = uniformBuilder.matchingStorageTypes;
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("age", daysOld).add("createBefore", creationTimestamp).add("numberofNewerVersions", newerVersionsCount).add("isLive", liveFlag).add("matchesStorageClass", matchingStorageTypes).toString();
            }

            public LifecycleConditionBuilder toBuilder() {
                return newLifecycleConditionBuilder().setAge(this.daysOld).setCreatedBefore(this.creationTimestamp).setNumberOfNewerVersions(this.newerVersionsCount).setIsLive(this.liveFlag).setMatchesStorageClass(this.matchingStorageTypes);
            }

            public static LifecycleConditionBuilder newLifecycleConditionBuilder() {
                return new LifecycleConditionBuilder();
            }

            public DateTime getCreatedBefore() {
                return creationTimestamp;
            }

        }

        /**
         * Base class for the Action to take when a Lifecycle Condition is met. Specific Actions are
         * expressed as subclasses of this class, accessed by static factory methods.
         */
        public abstract static class AbstractLifecycleAction implements Serializable {

            private static final long serialVersionUID = 5801228724709173284L;

            /**
             * Creates a new {@code SetStorageClassLifecycleAction}. A Blob's storage class that meets the
             * action's conditions will be changed to the specified storage class.
             *
             * @param storageTier The new storage class to use when conditions are met for this action.
             */
            public static UpdateStorageClassLifecycleAction newUpdateStorageClassAction(StorageClassType storageTier) {
                return new UpdateStorageClassLifecycleAction(storageTier);
            }

            /**
             * Creates a new {@code DeleteLifecycleAction}. Blobs that meet the Condition associated with
             * this action will be deleted.
             */
            public static RemoveLifecycleAction newRemoveAction() {
                return new RemoveLifecycleAction();
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("actionType", getActionType()).toString();
            }

            public abstract String getActionType();

        }

        public static class RemoveLifecycleAction extends AbstractLifecycleAction {

            public static final String TYPE = "Delete";

            private static final long serialVersionUID = -2050986302222644873L;

            @Override
            public String getActionType() {
                return TYPE;
            }

            private RemoveLifecycleAction() {
            }

        }

        public static class UpdateStorageClassLifecycleAction extends AbstractLifecycleAction {

            public static final String TYPE = "SetStorageClass";

            private static final long serialVersionUID = -62615467186000899L;

            private final StorageClassType storageTier;

            public StorageClassType getStorageClass() {
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

            private UpdateStorageClassLifecycleAction(StorageClassType storageTier) {
                this.storageTier = storageTier;
            }

        }

        static LifecycleRuleDefinition fromProto(Rule protoEntry) {
            AbstractLifecycleAction ruleAction;
            Rule.Action lifecycleActParam = protoEntry.getAction();
            switch(lifecycleActParam.getType()) {
                case RemoveLifecycleAction.TYPE:
                    ruleAction = AbstractLifecycleAction.newRemoveAction();
                    break;
                case UpdateStorageClassLifecycleAction.TYPE:
                    ruleAction = AbstractLifecycleAction.newUpdateStorageClassAction(StorageClassType.fromValue(lifecycleActParam.getStorageClass()));
                    break;
                default:
                    throw new UnsupportedOperationException("The specified lifecycle action " + lifecycleActParam.getType() + " is not currently supported");
            }
            Rule.Condition lifecycleCondParam = protoEntry.getCondition();
            LifecycleRuleCondition.LifecycleConditionBuilder condCreator = LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(lifecycleCondParam.getAge()).setCreatedBefore(lifecycleCondParam.getCreatedBefore()).setIsLive(lifecycleCondParam.getIsLive()).setNumberOfNewerVersions(lifecycleCondParam.getNumNewerVersions()).setMatchesStorageClass(null == lifecycleCondParam.getMatchesStorageClass() ? null : transform(lifecycleCondParam.getMatchesStorageClass(), new Function<String, StorageClassType>() {

                public StorageClassType apply(String storageClass) {
                    return StorageClassType.fromValue(storageClass);
                }
            }));
            return new LifecycleRuleDefinition(ruleAction, condCreator.buildLifecycleCondition());
        }

        public LifecycleRuleCondition getCondition() {
            return ruleCondition;
        }

        Rule toProto() {
            Rule protoEntry = new Rule();
            Rule.Action lifecycleActParam = new Rule.Action().setType(ruleAction.getActionType());
            if (ruleAction.getActionType().equals(UpdateStorageClassLifecycleAction.TYPE)) {
                lifecycleActParam.setStorageClass(((UpdateStorageClassLifecycleAction) ruleAction).getStorageClass().toString());
            }
            protoEntry.setAction(lifecycleActParam);
            Rule.Condition lifecycleCondParam = new Rule.Condition().setAge(ruleCondition.getAge()).setCreatedBefore(null == ruleCondition.getCreatedBefore() ? null : new DateTime(true, ruleCondition.getCreatedBefore().getValue(), 0)).setIsLive(ruleCondition.getIsLive()).setNumNewerVersions(ruleCondition.getNumberOfNewerVersions()).setMatchesStorageClass(null == ruleCondition.getMatchesStorageClass() ? null : transform(ruleCondition.getMatchesStorageClass(), Functions.toStringFunction()));
            protoEntry.setCondition(lifecycleCondParam);
            return protoEntry;
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleAction, ruleCondition);
        }

        public AbstractLifecycleAction getAction() {
            return ruleAction;
        }

        @Override
        public boolean equals(Object candidate) {
            if (candidate == this) {
                return true;
            }
            if (null == candidate || candidate.getClass() != getClass()) {
                return false;
            }
            final LifecycleRuleDefinition otherConfig = (LifecycleRuleDefinition) candidate;
            return Objects.equals(toProto(), otherConfig.toProto());
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("lifecycleAction", ruleAction).add("lifecycleCondition", ruleCondition).toString();
        }

        public LifecycleRuleDefinition(AbstractLifecycleAction lifecycleActParam, LifecycleRuleCondition lifecycleCondParam) {
            if (null == lifecycleCondParam.getIsLive() && null == lifecycleCondParam.getAge() && null == lifecycleCondParam.getCreatedBefore() && null == lifecycleCondParam.getMatchesStorageClass() && null == lifecycleCondParam.getNumberOfNewerVersions()) {
                throw new IllegalArgumentException("You must specify at least one condition to use object lifecycle " + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
            }
            this.ruleAction = lifecycleActParam;
            this.ruleCondition = lifecycleCondParam;
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

        private static final String ALLOWED_ACTIONS = "Delete";

        private final RetentionCriterion retentionCriterion;

        public enum RetentionCriterion {

            AGE, CREATE_BEFORE, NUM_NEWER_VERSIONS, IS_LIVE, UNKNOWN
        }

        Rule toProto() {
            Rule protoEntry = new Rule();
            protoEntry.setAction(new Rule.Action().setType(ALLOWED_ACTIONS));
            Rule.Condition lifecycleCondParam = new Rule.Condition();
            fillCondition(lifecycleCondParam);
            protoEntry.setCondition(lifecycleCondParam);
            return protoEntry;
        }

        @Override
        public boolean equals(Object candidate) {
            if (candidate == this) {
                return true;
            }
            if (null == candidate || candidate.getClass() != getClass()) {
                return false;
            }
            final DeletionRule otherConfig = (DeletionRule) candidate;
            return Objects.equals(toProto(), otherConfig.toProto());
        }

        abstract void fillCondition(Rule.Condition condition);

        static DeletionRule fromProto(Rule protoEntry) {
            if (null != protoEntry.getAction() && ALLOWED_ACTIONS.endsWith(protoEntry.getAction().getType())) {
                Rule.Condition lifecycleCondParam = protoEntry.getCondition();
                Integer daysOld = lifecycleCondParam.getAge();
                if (null != daysOld) {
                    return new AgeBasedDeletionRule(daysOld);
                }
                DateTime parsedTimestamp = lifecycleCondParam.getCreatedBefore();
                if (null != parsedTimestamp) {
                    return new CreatedBeforeDeletionRule(parsedTimestamp.getValue());
                }
                Integer newerVersionsCount = lifecycleCondParam.getNumNewerVersions();
                if (null != newerVersionsCount) {
                    return new NumNewerVersionsDeletionRule(newerVersionsCount);
                }
                Boolean liveFlag = lifecycleCondParam.getIsLive();
                if (null != liveFlag) {
                    return new LiveDeleteRule(liveFlag);
                }
            }
            return new RawDeletionRule(protoEntry);
        }

        @Override
        public int hashCode() {
            return Objects.hash(retentionCriterion);
        }

        public RetentionCriterion getType() {
            return retentionCriterion;
        }

        DeletionRule(RetentionCriterion retentionCriterion) {
            this.retentionCriterion = retentionCriterion;
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

        @Override
        void fillCondition(Rule.Condition lifecycleCondParam) {
            lifecycleCondParam.setAge(ttlDays);
        }

        /**
         * Creates an {@code AgeDeleteRule} object.
         *
         * @param ttlDays blobs' Time To Live expressed in days. The time when the age condition is
         *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
         *     following blob's creation time in UTC.
         */
        public AgeBasedDeletionRule(int ttlDays) {
            super(RetentionCriterion.AGE);
            this.ttlDays = ttlDays;
        }

        public int getDaysToLive() {
            return ttlDays;
        }

    }

    static class RawDeletionRule extends DeletionRule {

        private static final long serialVersionUID = -7166938278642301933L;

        private transient Rule protoEntry;

        private void readObject(ObjectInputStream objectReader) throws IOException, ClassNotFoundException {
            objectReader.defaultReadObject();
            protoEntry = new JacksonFactory().fromString(objectReader.readUTF(), Rule.class);
        }

        @Override
        Rule toProto() {
            return protoEntry;
        }

        @Override
        void fillCondition(Rule.Condition condition) {
            throw new UnsupportedOperationException();
        }

        RawDeletionRule(Rule protoEntry) {
            super(RetentionCriterion.UNKNOWN);
            this.protoEntry = protoEntry;
        }

        private void writeObject(ObjectOutputStream objectWriter) throws IOException {
            objectWriter.defaultWriteObject();
            objectWriter.writeUTF(protoEntry.toString());
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
    public static class CreatedBeforeDeletionRule extends DeletionRule {

        private static final long serialVersionUID = 881692650279195867L;

        private final long createdAtMs;

        @Override
        void fillCondition(Rule.Condition lifecycleCondParam) {
            lifecycleCondParam.setCreatedBefore(new DateTime(true, createdAtMs, 0));
        }

        public long getTimeMillis() {
            return createdAtMs;
        }

        /**
         * Creates an {@code CreatedBeforeDeleteRule} object.
         *
         * @param createdAtMs a date in UTC. Blobs that have been created before midnight of the provided
         *     date meet the delete condition
         */
        public CreatedBeforeDeletionRule(long createdAtMs) {
            super(RetentionCriterion.CREATE_BEFORE);
            this.createdAtMs = createdAtMs;
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
    public static class NumNewerVersionsDeletionRule extends DeletionRule {

        private static final long serialVersionUID = -1955554976528303894L;

        private final int newerVersionsCount;

        @Override
        void fillCondition(Rule.Condition lifecycleCondParam) {
            lifecycleCondParam.setNumNewerVersions(newerVersionsCount);
        }

        /**
         * Creates an {@code NumNewerVersionsDeleteRule} object.
         *
         * @param newerVersionsCount the number of newer versions. A blob's version meets the delete
         *     condition when {@code numNewerVersions} newer versions are available.
         */
        public NumNewerVersionsDeletionRule(int newerVersionsCount) {
            super(RetentionCriterion.NUM_NEWER_VERSIONS);
            this.newerVersionsCount = newerVersionsCount;
        }

        public int getNumNewerVersions() {
            return newerVersionsCount;
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

        private final boolean liveFlag;

        @Override
        void fillCondition(Rule.Condition lifecycleCondParam) {
            lifecycleCondParam.setIsLive(liveFlag);
        }

        /**
         * Creates an {@code IsLiveDeleteRule} object.
         *
         * @param liveFlag if set to {@code true} live blobs meet the delete condition. If set to {@code
         *     false} delete condition is met by archived blobs.
         */
        public LiveDeleteRule(boolean liveFlag) {
            super(RetentionCriterion.IS_LIVE);
            this.liveFlag = liveFlag;
        }

        public boolean isLive() {
            return liveFlag;
        }

    }

    /**
     * Builder for {@code BucketInfo}.
     */
    public abstract static class AbstractBuilder {

        /**
         * Sets the default access control configuration to apply to bucket's blobs when no other
         * configuration is specified.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract AbstractBuilder setDefaultAcl(Iterable<AccessControlEntry> acl);

        /**
         * Sets the label of this bucket.
         */
        public abstract AbstractBuilder setLabels(Map<String, String> labels);

        /**
         * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
         * determines the SLA and the cost of storage. A list of supported values is available <a
         * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
         */
        public abstract AbstractBuilder setStorageClass(StorageClassType storageClass);

        /**
         * Sets the bucket's lifecycle configuration as a number of lifecycle rules, consisting of an
         * action and a condition.
         *
         * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
         *     Management</a>
         */
        public abstract AbstractBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> rules);

        abstract AbstractBuilder setOwner(AbstractEntity owner);

        /**
         * Sets the bucket's name.
         */
        public abstract AbstractBuilder setName(String name);

        /**
         * Sets the bucket's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract AbstractBuilder setAcl(Iterable<AccessControlEntry> acl);

        /**
         * Creates a {@code BucketInfo} object.
         */
        public abstract BucketMetadata buildInstance();

        @BetaApi
        abstract AbstractBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyIsLocked);

        /**
         * If policy is not locked this value can be cleared, increased, and decreased. If policy is
         * locked the retention period can only be increased.
         */
        @BetaApi
        public abstract AbstractBuilder setRetentionPeriod(Long retentionPeriod);

        /**
         * Sets the bucket's lifecycle configuration as a number of delete rules.
         *
         * @deprecated Use {@code setLifecycleRules} instead, as in {@code
         *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRule(
         *     LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(5).build())));}
         */
        @Deprecated
        public abstract BucketMetadata.AbstractBuilder setDeleteRules(Iterable<? extends DeletionRule> rules);

        /**
         * Sets the IamConfiguration to specify whether IAM access should be enabled.
         *
         * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
         *     Only</a>
         */
        @BetaApi
        public abstract AbstractBuilder setIamConfiguration(BucketIamConfiguration iamConfiguration);

        /**
         * Sets the default Cloud KMS key name for this bucket.
         */
        public abstract AbstractBuilder setDefaultKmsKeyName(String defaultKmsKeyName);

        abstract AbstractBuilder setCreateTime(Long createTime);

        @BetaApi
        abstract AbstractBuilder setRetentionEffectiveTime(Long retentionEffectiveTime);

        /**
         * Sets the bucket's website index page. Behaves as the bucket's directory index where missing
         * blobs are treated as potential directories.
         */
        public abstract AbstractBuilder setIndexPage(String indexPage);

        AbstractBuilder() {
        }

        public abstract AbstractBuilder setLogging(LoggingConfig logging);

        abstract AbstractBuilder setGeneratedId(String generatedId);

        /**
         * Sets whether versioning should be enabled for this bucket. When set to true, versioning is
         * fully enabled.
         */
        public abstract AbstractBuilder setVersioningEnabled(Boolean enable);

        /**
         * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
         *
         * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
         *     Sharing (CORS)</a>
         */
        public abstract AbstractBuilder setCors(Iterable<CorsConfiguration> cors);

        /**
         * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
         * this region. A list of supported values is available <a
         * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
         */
        public abstract AbstractBuilder setLocation(String location);

        abstract AbstractBuilder setLocationType(String locationType);

        abstract AbstractBuilder setSelfLink(String selfLink);

        /**
         * Sets the custom object to return when a requested resource is not found.
         */
        public abstract AbstractBuilder setNotFoundPage(String notFoundPage);

        abstract AbstractBuilder setMetageneration(Long metageneration);

        /**
         * Sets the default event-based hold for this bucket.
         */
        @BetaApi
        public abstract AbstractBuilder setDefaultEventBasedHold(Boolean defaultEventBasedHold);

        /**
         * Sets whether a user accessing the bucket or an object it contains should assume the transit
         * costs related to the access.
         */
        public abstract AbstractBuilder setRequesterPays(Boolean requesterPays);

        abstract AbstractBuilder setEtag(String etag);

    }

    static final class BucketBuilderImpl extends AbstractBuilder {

        private String generatedIdentifier;

        private String displayName;

        private AbstractEntity ownerEntity;

        private String selfLinkUrl;

        private Boolean requesterBillingEnabled;

        private Boolean versioningActive;

        private String indexDocument;

        private String notFoundDocument;

        private List<DeletionRule> deletionRules;

        private List<LifecycleRuleDefinition> lifecyclePolicies;

        private StorageClassType storageTier;

        private String region;

        private String entityTag;

        private Long creationTime;

        private Long metaGeneration;

        private List<CorsConfiguration> corsConfigs;

        private List<AccessControlEntry> accessControlList;

        private List<AccessControlEntry> defaultAccessControlList;

        private Map<String, String> labelsMap;

        private String defaultKmsKey;

        private Boolean eventBasedHoldDefault;

        private Long retentionEffectiveTimestamp;

        private Boolean retentionPolicyLocked;

        private Long retentionDuration;

        private BucketIamConfiguration iamConfig;

        private String locationScope;

        private LoggingConfig loggingConfig;

        @Override
        public BucketMetadata.AbstractBuilder setDefaultKmsKeyName(String defaultKmsKey) {
            this.defaultKmsKey = null != defaultKmsKey ? defaultKmsKey : Data.<String>nullOf(String.class);
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setRetentionPeriod(Long retentionDuration) {
            this.retentionDuration = firstNonNull(retentionDuration, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyLocked) {
            this.retentionPolicyLocked = firstNonNull(retentionPolicyLocked, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setRetentionEffectiveTime(Long retentionEffectiveTimestamp) {
            this.retentionEffectiveTimestamp = firstNonNull(retentionEffectiveTimestamp, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setLocation(String region) {
            this.region = region;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setDefaultAcl(Iterable<AccessControlEntry> accessControlList) {
            this.defaultAccessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setDefaultEventBasedHold(Boolean eventBasedHoldDefault) {
            this.eventBasedHoldDefault = firstNonNull(eventBasedHoldDefault, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setLabels(Map<String, String> labelsMap) {
            if (null != labelsMap) {
                this.labelsMap = Maps.transformValues(labelsMap, new Function<String, String>() {

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
        public BucketMetadata.AbstractBuilder setNotFoundPage(String notFoundDocument) {
            this.notFoundDocument = notFoundDocument;
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        /**
         * @deprecated Use {@code setLifecycleRules} method instead. *
         */
        @Override
        @Deprecated
        public BucketMetadata.AbstractBuilder setDeleteRules(Iterable<? extends DeletionRule> deletionPolicies) {
            this.deletionRules = null != deletionPolicies ? ImmutableList.copyOf(deletionPolicies) : null;
            return this;
        }

        @Override
        public BucketMetadata buildInstance() {
            checkNotNull(displayName);
            return new BucketMetadata(this);
        }

        @Override
        public BucketMetadata.AbstractBuilder setName(String displayName) {
            this.displayName = checkNotNull(displayName);
            return this;
        }

        BucketBuilderImpl(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public BucketMetadata.AbstractBuilder setLogging(LoggingConfig loggingConfig) {
            this.loggingConfig = loggingConfig;
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setSelfLink(String selfLinkUrl) {
            this.selfLinkUrl = selfLinkUrl;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setIamConfiguration(BucketIamConfiguration iamConfig) {
            this.iamConfig = iamConfig;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setCors(Iterable<CorsConfiguration> corsConfigs) {
            this.corsConfigs = null != corsConfigs ? ImmutableList.copyOf(corsConfigs) : null;
            return this;
        }

        BucketBuilderImpl(BucketMetadata bucketMetadata) {
            generatedIdentifier = bucketMetadata.generatedIdentifier;
            displayName = bucketMetadata.displayName;
            entityTag = bucketMetadata.entityTag;
            creationTime = bucketMetadata.creationTime;
            metaGeneration = bucketMetadata.metaGeneration;
            region = bucketMetadata.region;
            storageTier = bucketMetadata.storageTier;
            corsConfigs = bucketMetadata.corsConfigs;
            accessControlList = bucketMetadata.accessControlList;
            defaultAccessControlList = bucketMetadata.defaultAccessControlList;
            ownerEntity = bucketMetadata.ownerEntity;
            selfLinkUrl = bucketMetadata.selfLinkUrl;
            versioningActive = bucketMetadata.versioningActive;
            indexDocument = bucketMetadata.indexDocument;
            notFoundDocument = bucketMetadata.notFoundDocument;
            deletionRules = bucketMetadata.deletionRules;
            lifecyclePolicies = bucketMetadata.lifecyclePolicies;
            labelsMap = bucketMetadata.labelsMap;
            requesterBillingEnabled = bucketMetadata.requesterBillingEnabled;
            defaultKmsKey = bucketMetadata.defaultKmsKey;
            eventBasedHoldDefault = bucketMetadata.eventBasedHoldDefault;
            retentionEffectiveTimestamp = bucketMetadata.retentionEffectiveTimestamp;
            retentionPolicyLocked = bucketMetadata.retentionPolicyLocked;
            retentionDuration = bucketMetadata.retentionDuration;
            iamConfig = bucketMetadata.iamConfig;
            locationScope = bucketMetadata.locationScope;
            loggingConfig = bucketMetadata.loggingConfig;
        }

        @Override
        public BucketMetadata.AbstractBuilder setVersioningEnabled(Boolean versioningFlag) {
            this.versioningActive = firstNonNull(versioningFlag, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setOwner(AbstractEntity ownerEntity) {
            this.ownerEntity = ownerEntity;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setAcl(Iterable<AccessControlEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setRequesterPays(Boolean versioningFlag) {
            this.requesterBillingEnabled = firstNonNull(versioningFlag, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setMetageneration(Long metaGeneration) {
            this.metaGeneration = metaGeneration;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setStorageClass(StorageClassType storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deletionPolicies) {
            this.lifecyclePolicies = null != deletionPolicies ? ImmutableList.copyOf(deletionPolicies) : null;
            return this;
        }

        @Override
        public BucketMetadata.AbstractBuilder setIndexPage(String indexDocument) {
            this.indexDocument = indexDocument;
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setCreateTime(Long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        @Override
        BucketMetadata.AbstractBuilder setLocationType(String locationScope) {
            this.locationScope = locationScope;
            return this;
        }

    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", displayName).toString();
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
     * Returns the labels for this bucket.
     */
    public Map<String, String> getLabels() {
        return labelsMap;
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
     * Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name.
     */
    public static AbstractBuilder newBucketBuilder(String displayName) {
        return new BucketBuilderImpl(displayName);
    }

    Bucket toProto() {
        Bucket bucketProto = new Bucket();
        bucketProto.setId(generatedIdentifier);
        bucketProto.setName(displayName);
        bucketProto.setEtag(entityTag);
        if (null != creationTime) {
            bucketProto.setTimeCreated(new DateTime(creationTime));
        }
        if (null != metaGeneration) {
            bucketProto.setMetageneration(metaGeneration);
        }
        if (null != region) {
            bucketProto.setLocation(region);
        }
        if (null != locationScope) {
            bucketProto.setLocationType(locationScope);
        }
        if (null != storageTier) {
            bucketProto.setStorageClass(storageTier.toString());
        }
        if (null != corsConfigs) {
            bucketProto.setCors(transform(corsConfigs, CorsConfiguration.TO_PROTO_FUNCTION));
        }
        if (null != accessControlList) {
            bucketProto.setAcl(transform(accessControlList, new Function<AccessControlEntry, BucketAccessControl>() {

                @Override
                public BucketAccessControl apply(AccessControlEntry acl) {
                    return acl.toBucketProto();
                }
            }));
        }
        if (null != defaultAccessControlList) {
            bucketProto.setDefaultObjectAcl(transform(defaultAccessControlList, new Function<AccessControlEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != ownerEntity) {
            bucketProto.setOwner(new Owner().setEntity(ownerEntity.toProto()));
        }
        bucketProto.setSelfLink(selfLinkUrl);
        if (null != versioningActive) {
            bucketProto.setVersioning(new Versioning().setEnabled(versioningActive));
        }
        if (null != requesterBillingEnabled) {
            Bucket.Billing billingInfo = new Bucket.Billing();
            billingInfo.setRequesterPays(requesterBillingEnabled);
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
            deletionPolicies.addAll(transform(lifecyclePolicies, new Function<LifecycleRuleDefinition, Rule>() {

                @Override
                public Rule apply(LifecycleRuleDefinition lifecycleRule) {
                    return lifecycleRule.toProto();
                }
            }));
        }
        if (!deletionPolicies.isEmpty()) {
            Lifecycle lifecycleProto = new Lifecycle();
            lifecycleProto.setRule(ImmutableList.copyOf(deletionPolicies));
            bucketProto.setLifecycle(lifecycleProto);
        }
        if (null != labelsMap) {
            bucketProto.setLabels(labelsMap);
        }
        if (null != defaultKmsKey) {
            bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(defaultKmsKey));
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
        if (null != loggingConfig) {
            bucketProto.setLogging(loggingConfig.toProto());
        }
        return bucketProto;
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
     * Returns the IAM configuration
     */
    @BetaApi
    public BucketIamConfiguration getIamConfiguration() {
        return iamConfig;
    }

    /**
     * Returns the metadata generation of this bucket.
     */
    public Long getMetageneration() {
        return metaGeneration;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageService.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * StorageService#get(String, StorageService.GetBucketOption...)} and default event-based hold for the bucket is
     * enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * StorageService#get(String, StorageService.GetBucketOption...)}, but default event-based hold for the bucket
     * is not enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BucketMetadataField#DEFAULT_EVENT_BASED_HOLD} is not selected in a
     * {@link StorageService#get(String, StorageService.GetBucketOption...)}, and the state for this field is
     * unknown.
     *
     * <p>Case 3: {@code false} default event-based hold is explicitly set to false using in a {@link
     * AbstractBuilder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * StorageService#update(BucketMetadata, StorageService.BucketTargetOptions...)} in which case the value of default
     * event-based hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getDefaultEventBasedHold() {
        return Data.isNull(eventBasedHoldDefault) ? null : eventBasedHoldDefault;
    }

    /**
     * Creates a {@code BucketInfo} object for the provided bucket name.
     */
    public static BucketMetadata ofName(String displayName) {
        return newBucketBuilder(displayName).buildInstance();
    }

    /**
     * Returns the retention policy retention period.
     */
    @BetaApi
    public Long getRetentionPeriod() {
        return retentionDuration;
    }

    /**
     * Returns the service-generated id for the bucket.
     */
    public String getGeneratedId() {
        return generatedIdentifier;
    }

    public List<? extends LifecycleRuleDefinition> getLifecycleRules() {
        return lifecyclePolicies;
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate || null != candidate && candidate.getClass().equals(BucketMetadata.class) && Objects.equals(toProto(), ((BucketMetadata) candidate).toProto());
    }

    /**
     * Returns the time at which the bucket was created.
     */
    public Long getCreateTime() {
        return creationTime;
    }

    /**
     * Returns the bucket's owner. This is always the project team's owner group.
     */
    public AbstractEntity getOwner() {
        return ownerEntity;
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

    @Override
    public int hashCode() {
        return Objects.hash(displayName);
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
     * Returns a builder for the current bucket.
     */
    public AbstractBuilder toBucketBuilder() {
        return new BucketBuilderImpl(this);
    }

    /**
     * Returns the custom object to return when a requested resource is not found.
     */
    public String getNotFoundPage() {
        return notFoundDocument;
    }

    /**
     * Returns the Logging
     */
    public LoggingConfig getLogging() {
        return loggingConfig;
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
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageService.BucketMetadataField#VERSIONING} is selected in a {@link
     * StorageService#get(String, StorageService.GetBucketOption...)} and versions for the bucket is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BucketMetadataField#VERSIONING} is selected in a {@link
     * StorageService#get(String, StorageService.GetBucketOption...)}, but versions for the bucket is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BucketMetadataField#VERSIONING} is not selected in a {@link
     * StorageService#get(String, StorageService.GetBucketOption...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
     * request for example {@link StorageService#update(BucketMetadata, StorageService.BucketTargetOptions...)} in which
     * case the value of versions will remain {@code false} for for the given instance.
     */
    public Boolean isVersioningEnabled() {
        return Data.isNull(versioningActive) ? null : versioningActive;
    }

    /**
     * Returns the bucket's name.
     */
    public String getName() {
        return displayName;
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
     * Returns the URI of this bucket as a string.
     */
    public String getSelfLink() {
        return selfLinkUrl;
    }

    /**
     * Returns bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public String getIndexPage() {
        return indexDocument;
    }

    BucketMetadata(BucketBuilderImpl uniformBuilder) {
        generatedIdentifier = uniformBuilder.generatedIdentifier;
        displayName = uniformBuilder.displayName;
        entityTag = uniformBuilder.entityTag;
        creationTime = uniformBuilder.creationTime;
        metaGeneration = uniformBuilder.metaGeneration;
        region = uniformBuilder.region;
        storageTier = uniformBuilder.storageTier;
        corsConfigs = uniformBuilder.corsConfigs;
        accessControlList = uniformBuilder.accessControlList;
        defaultAccessControlList = uniformBuilder.defaultAccessControlList;
        ownerEntity = uniformBuilder.ownerEntity;
        selfLinkUrl = uniformBuilder.selfLinkUrl;
        versioningActive = uniformBuilder.versioningActive;
        indexDocument = uniformBuilder.indexDocument;
        notFoundDocument = uniformBuilder.notFoundDocument;
        deletionRules = uniformBuilder.deletionRules;
        lifecyclePolicies = uniformBuilder.lifecyclePolicies;
        labelsMap = uniformBuilder.labelsMap;
        requesterBillingEnabled = uniformBuilder.requesterBillingEnabled;
        defaultKmsKey = uniformBuilder.defaultKmsKey;
        eventBasedHoldDefault = uniformBuilder.eventBasedHoldDefault;
        retentionEffectiveTimestamp = uniformBuilder.retentionEffectiveTimestamp;
        retentionPolicyLocked = uniformBuilder.retentionPolicyLocked;
        retentionDuration = uniformBuilder.retentionDuration;
        iamConfig = uniformBuilder.iamConfig;
        locationScope = uniformBuilder.locationScope;
        loggingConfig = uniformBuilder.loggingConfig;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
     * {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link StorageService.BucketMetadataField#BILLING}
     * is selected in a {@link StorageService#get(String, StorageService.GetBucketOption...)} and requester pays for
     * the bucket is enabled.
     *
     * <p>Case 2: {@code false} the field {@link StorageService.BucketMetadataField#BILLING}
     * in a {@link StorageService#get(String, StorageService.GetBucketOption...)} is selected and requester pays for
     * the bucket is disable.
     *
     * <p>Case 3: {@code null} the field {@link StorageService.BucketMetadataField#BILLING}
     * in a {@link StorageService#get(String, StorageService.GetBucketOption...)} is not selected, the value is
     * unknown.
     */
    public Boolean isRequesterPays() {
        return Data.isNull(requesterBillingEnabled) ? null : requesterBillingEnabled;
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

    static BucketMetadata fromProto(Bucket bucketProto) {
        AbstractBuilder uniformBuilder = new BucketBuilderImpl(bucketProto.getName());
        if (null != bucketProto.getId()) {
            uniformBuilder.setGeneratedId(bucketProto.getId());
        }
        if (null != bucketProto.getEtag()) {
            uniformBuilder.setEtag(bucketProto.getEtag());
        }
        if (null != bucketProto.getMetageneration()) {
            uniformBuilder.setMetageneration(bucketProto.getMetageneration());
        }
        if (null != bucketProto.getSelfLink()) {
            uniformBuilder.setSelfLink(bucketProto.getSelfLink());
        }
        if (null != bucketProto.getTimeCreated()) {
            uniformBuilder.setCreateTime(bucketProto.getTimeCreated().getValue());
        }
        if (null != bucketProto.getLocation()) {
            uniformBuilder.setLocation(bucketProto.getLocation());
        }
        if (null != bucketProto.getStorageClass()) {
            uniformBuilder.setStorageClass(StorageClassType.fromValue(bucketProto.getStorageClass()));
        }
        if (null != bucketProto.getCors()) {
            uniformBuilder.setCors(transform(bucketProto.getCors(), CorsConfiguration.FROM_PROTO_TO_CONFIG_FN));
        }
        if (null != bucketProto.getAcl()) {
            uniformBuilder.setAcl(transform(bucketProto.getAcl(), new Function<BucketAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(BucketAccessControl bucketAccessControl) {
                    return AccessControlEntry.fromProto(bucketAccessControl);
                }
            }));
        }
        if (null != bucketProto.getDefaultObjectAcl()) {
            uniformBuilder.setDefaultAcl(transform(bucketProto.getDefaultObjectAcl(), new Function<ObjectAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                    return AccessControlEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (null != bucketProto.getOwner()) {
            uniformBuilder.setOwner(AbstractEntity.fromProto(bucketProto.getOwner().getEntity()));
        }
        if (null != bucketProto.getVersioning()) {
            uniformBuilder.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
        }
        Website websiteConfig = bucketProto.getWebsite();
        if (null != websiteConfig) {
            uniformBuilder.setIndexPage(websiteConfig.getMainPageSuffix());
            uniformBuilder.setNotFoundPage(websiteConfig.getNotFoundPage());
        }
        if (null != bucketProto.getLifecycle() && null != bucketProto.getLifecycle().getRule()) {
            uniformBuilder.setLifecycleRules(transform(bucketProto.getLifecycle().getRule(), new Function<Rule, LifecycleRuleDefinition>() {

                @Override
                public BucketMetadata.LifecycleRuleDefinition apply(Rule rule) {
                    return LifecycleRuleDefinition.fromProto(rule);
                }
            }));
            uniformBuilder.setDeleteRules(transform(bucketProto.getLifecycle().getRule(), new Function<Rule, DeletionRule>() {

                @Override
                public BucketMetadata.DeletionRule apply(Rule rule) {
                    return DeletionRule.fromProto(rule);
                }
            }));
        }
        if (null != bucketProto.getLabels()) {
            uniformBuilder.setLabels(bucketProto.getLabels());
        }
        Bucket.Billing billingInfo = bucketProto.getBilling();
        if (null != billingInfo) {
            uniformBuilder.setRequesterPays(billingInfo.getRequesterPays());
        }
        Encryption cryptoConfig = bucketProto.getEncryption();
        if (null != cryptoConfig && null != cryptoConfig.getDefaultKmsKeyName() && !cryptoConfig.getDefaultKmsKeyName().isEmpty()) {
            uniformBuilder.setDefaultKmsKeyName(cryptoConfig.getDefaultKmsKeyName());
        }
        if (null != bucketProto.getDefaultEventBasedHold()) {
            uniformBuilder.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
        }
        Bucket.RetentionPolicy retentionPolicyProto = bucketProto.getRetentionPolicy();
        if (null != retentionPolicyProto) {
            if (null != retentionPolicyProto.getEffectiveTime()) {
                uniformBuilder.setRetentionEffectiveTime(retentionPolicyProto.getEffectiveTime().getValue());
            }
            if (null != retentionPolicyProto.getIsLocked()) {
                uniformBuilder.setRetentionPolicyIsLocked(retentionPolicyProto.getIsLocked());
            }
            if (null != retentionPolicyProto.getRetentionPeriod()) {
                uniformBuilder.setRetentionPeriod(retentionPolicyProto.getRetentionPeriod());
            }
        }
        Bucket.IamConfiguration iamConfig = bucketProto.getIamConfiguration();
        if (null != bucketProto.getLocationType()) {
            uniformBuilder.setLocationType(bucketProto.getLocationType());
        }
        if (null != iamConfig) {
            uniformBuilder.setIamConfiguration(BucketIamConfiguration.fromProto(iamConfig));
        }
        Bucket.Logging loggingConfig = bucketProto.getLogging();
        if (null != loggingConfig) {
            uniformBuilder.setLogging(LoggingConfig.fromProto(loggingConfig));
        }
        return uniformBuilder.buildInstance();
    }

    /**
     * Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket.
     */
    public String getDefaultKmsKeyName() {
        return defaultKmsKey;
    }

    /**
     * Returns a {@code Boolean} with either {@code true} or {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link
     * StorageService.BucketMetadataField#RETENTION_POLICY} is selected in a {@link
     * StorageService#get(String, StorageService.GetBucketOption...)} and retention policy for the bucket is locked.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * StorageService.BucketMetadataField#RETENTION_POLICY} is selected in a {@link
     * StorageService#get(String, StorageService.GetBucketOption...)}, but retention policy for the bucket is not
     * locked. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * StorageService.BucketMetadataField#RETENTION_POLICY} is not selected in a {@link
     * StorageService#get(String, StorageService.GetBucketOption...)}, and the state for this field is unknown.
     */
    @BetaApi
    public Boolean isRetentionPolicyLocked() {
        return Data.isNull(retentionPolicyLocked) ? null : retentionPolicyLocked;
    }

}
