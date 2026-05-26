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
public class BucketInfo implements Serializable {

    static final Function<com.google.api.services.storage.model.Bucket, BucketInfo> FROM_PB_FUNCTION = new Function<com.google.api.services.storage.model.Bucket, BucketInfo>() {

        @Override
        public BucketInfo apply(com.google.api.services.storage.model.Bucket pb) {
            return BucketInfo.fromProto(pb);
        }
    };

    static final Function<BucketInfo, com.google.api.services.storage.model.Bucket> INFO_TO_BUCKET_FUNCTION = new Function<BucketInfo, com.google.api.services.storage.model.Bucket>() {

        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketInfo bucketInfo) {
            return bucketInfo.toProto();
        }
    };

    private static final long serialVersionUID = -4712013629621638459L;

    private final String generatedIdentifier;

    private final String label;

    private final TypedEntity principalEntity;

    private final String resourceLink;

    private final Boolean isUserCharged;

    private final Boolean isVersioningActive;

    private final String indexDocument;

    private final String errorDocument;

    private final List<DeletionRule> removalRules;

    private final List<LifecycleRuleDefinition> lifecyclePolicies;

    private final String entityTag;

    private final Long creationTimestamp;

    private final Long lastModifiedTimestamp;

    private final Long metadataGeneration;

    private final List<Cors> crossOriginSettings;

    private final List<AccessControlEntry> accessControlEntries;

    private final List<AccessControlEntry> defaultAccessEntries;

    private final String region;

    private final StorageTier storageTier;

    private final Map<String, String> tags;

    private final String defaultEncryptionKey;

    private final Boolean eventHoldEnabled;

    private final Long retentionEffectiveTimestamp;

    private final Boolean isRetentionLocked;

    private final Long retentionDuration;

    private final IamSettings iamSettings;

    private final String regionType;

    private final LoggingConfig logSettings;

    /**
     * The Bucket's IAM Configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/uniform-bucket-level-access">uniform
     *     bucket-level access</a>
     */
    public static class IamSettings implements Serializable {

        private static final long serialVersionUID = -8671736104909424616L;

        private Boolean isUniformAccessEnabled;

        private Long uniformAccessLockedAt;

        /**
         * Builder for {@code IamConfiguration}
         */
        public static class UniformBucketLevelAccessBuilder {

            private Boolean isUniformAccessEnabled;

            private Long uniformAccessLockedAt;

            /**
             * Sets whether uniform bucket-level access is enabled for this bucket. When this is enabled,
             * access to the bucket will be configured through IAM, and legacy ACL policies will not work.
             * When this is first enabled, {@code uniformBucketLevelAccess.lockedTime} will be set by the
             * API automatically. This field can then be disabled until the time specified, after which it
             * will become immutable and calls to change it will fail. If this is enabled, calls to access
             * legacy ACL information will fail.
             */
            public UniformBucketLevelAccessBuilder setIsUniformBucketLevelAccessEnabled(Boolean isUniformAccessEnabled) {
                this.isUniformAccessEnabled = isUniformAccessEnabled;
                return this;
            }

            /**
             * Builds an {@code IamConfiguration} object
             */
            public IamSettings buildIamSettings() {
                return new IamSettings(this);
            }

            /**
             * Sets the deadline for switching {@code uniformBucketLevelAccess.enabled} back to false.
             * After this time passes, calls to do so will fail. This is package-private, since in general
             * this field should never be set by a user--it's automatically set by the backend when {@code
             * enabled} is set to true.
             */
            UniformBucketLevelAccessBuilder setUniformBucketLevelAccessLockedTime(Long uniformAccessLockedAt) {
                this.uniformAccessLockedAt = uniformAccessLockedAt;
                return this;
            }

            /**
             * Deprecated in favor of setIsUniformBucketLevelAccessEnabled().
             */
            @Deprecated
            public BucketInfo.IamSettings.UniformBucketLevelAccessBuilder setIsBucketPolicyOnlyEnabled(Boolean bucketPolicyOnlyEnabledFlag) {
                this.isUniformAccessEnabled = bucketPolicyOnlyEnabledFlag;
                return this;
            }

            /**
             * Deprecated in favor of setUniformBucketLevelAccessLockedTime().
             */
            @Deprecated
            BucketInfo.IamSettings.UniformBucketLevelAccessBuilder setBucketPolicyOnlyLockedTime(Long bucketPolicyOnlyLockTime) {
                this.uniformAccessLockedAt = bucketPolicyOnlyLockTime;
                return this;
            }

        }

        public UniformBucketLevelAccessBuilder toIamSettingsBuilder() {
            UniformBucketLevelAccessBuilder accessConfigurator = new UniformBucketLevelAccessBuilder();
            accessConfigurator.isUniformAccessEnabled = isUniformAccessEnabled;
            accessConfigurator.uniformAccessLockedAt = uniformAccessLockedAt;
            return accessConfigurator;
        }

        static IamSettings fromProto(Bucket.IamConfiguration iamSettings) {
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessSettings = iamSettings.getUniformBucketLevelAccess();
            DateTime lockedAt = uniformAccessSettings.getLockedTime();
            return newIamSettingsBuilder().setIsUniformBucketLevelAccessEnabled(uniformAccessSettings.getEnabled()).setUniformBucketLevelAccessLockedTime(null == lockedAt ? null : lockedAt.getValue()).buildIamSettings();
        }

        public Long getUniformBucketLevelAccessLockedTime() {
            return uniformAccessLockedAt;
        }

        Bucket.IamConfiguration toProto() {
            Bucket.IamConfiguration iamSettings = new Bucket.IamConfiguration();
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformAccessSettings = new Bucket.IamConfiguration.UniformBucketLevelAccess();
            uniformAccessSettings.setEnabled(isUniformAccessEnabled);
            uniformAccessSettings.setLockedTime(null == uniformAccessLockedAt ? null : new DateTime(uniformAccessLockedAt));
            iamSettings.setUniformBucketLevelAccess(uniformAccessSettings);
            return iamSettings;
        }

        /**
         * Deprecated in favor of isUniformBucketLevelAccessEnabled().
         */
        @Deprecated
        public Boolean isBucketPolicyOnlyEnabled() {
            return isUniformAccessEnabled;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (null == obj || obj.getClass() != getClass()) {
                return false;
            }
            IamSettings thatSettings = (IamSettings) obj;
            return Objects.equals(toProto(), thatSettings.toProto());
        }

        /**
         * Deprecated in favor of uniformBucketLevelAccessLockedTime().
         */
        @Deprecated
        public Long getBucketPolicyOnlyLockedTime() {
            return uniformAccessLockedAt;
        }

        public static UniformBucketLevelAccessBuilder newIamSettingsBuilder() {
            return new UniformBucketLevelAccessBuilder();
        }

        @Override
        public int hashCode() {
            return Objects.hash(isUniformAccessEnabled, uniformAccessLockedAt);
        }

        private IamSettings(UniformBucketLevelAccessBuilder accessConfigurator) {
            this.isUniformAccessEnabled = accessConfigurator.isUniformAccessEnabled;
            this.uniformAccessLockedAt = accessConfigurator.uniformAccessLockedAt;
        }

        public Boolean isUniformBucketLevelAccessEnabled() {
            return isUniformAccessEnabled;
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

        public static class LogConfigBuilder {

            private String loggingBucket;

            private String loggingObjectPrefix;

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

            /**
             * A prefix for log object names.
             */
            public LogConfigBuilder setLogObjectPrefix(String loggingObjectPrefix) {
                this.loggingObjectPrefix = loggingObjectPrefix;
                return this;
            }

        }

        static LoggingConfig fromProto(Bucket.Logging logSettings) {
            return newLogConfigBuilder().setLogBucket(logSettings.getLogBucket()).setLogObjectPrefix(logSettings.getLogObjectPrefix()).buildLoggingConfig();
        }

        private LoggingConfig(LogConfigBuilder accessConfigurator) {
            this.loggingBucket = accessConfigurator.loggingBucket;
            this.loggingObjectPrefix = accessConfigurator.loggingObjectPrefix;
        }

        public String getLogBucket() {
            return loggingBucket;
        }

        @Override
        public int hashCode() {
            return Objects.hash(loggingBucket, loggingObjectPrefix);
        }

        Bucket.Logging toProto() {
            Bucket.Logging logSettings;
            if (null == loggingBucket && null == loggingObjectPrefix) {
                logSettings = Data.nullOf(Bucket.Logging.class);
            } else {
                logSettings = new Bucket.Logging();
                logSettings.setLogBucket(loggingBucket);
                logSettings.setLogObjectPrefix(loggingObjectPrefix);
            }
            return logSettings;
        }

        public static LogConfigBuilder newLogConfigBuilder() {
            return new LogConfigBuilder();
        }

        public String getLogObjectPrefix() {
            return loggingObjectPrefix;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (null == obj || obj.getClass() != getClass()) {
                return false;
            }
            LoggingConfig thatSettings = (LoggingConfig) obj;
            return Objects.equals(toProto(), thatSettings.toProto());
        }

        public LogConfigBuilder toBuilder() {
            LogConfigBuilder accessConfigurator = new LogConfigBuilder();
            accessConfigurator.loggingBucket = loggingBucket;
            accessConfigurator.loggingObjectPrefix = loggingObjectPrefix;
            return accessConfigurator;
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

        private final AbstractLifecycleAction lifecycleOperation;

        private final LifecycleRuleCondition lifecycleCriteria;

        /**
         * Condition for a Lifecycle rule, specifies under what criteria an Action should be executed.
         *
         * @see <a href="https://cloud.google.com/storage/docs/lifecycle#conditions">Object Lifecycle
         *     Management</a>
         */
        public static class LifecycleRuleCondition implements Serializable {

            private static final long serialVersionUID = -6482314338394768785L;

            private final Integer daysOld;

            private final DateTime createdPriorTo;

            private final Integer newerVersionCount;

            private final Boolean liveFlag;

            private final List<StorageTier> storageClassFilters;

            private final Integer noncurrentDaysElapsed;

            private final DateTime noncurrentPriorTo;

            private final DateTime customPriorTo;

            private final Integer customDaysElapsed;

            /**
             * Builder for {@code LifecycleCondition}.
             */
            public static class LifecycleRuleBuilder {

                private Integer daysOld;

                private DateTime createdPriorTo;

                private Integer newerVersionCount;

                private Boolean liveFlag;

                private List<StorageTier> storageClassFilters;

                private Integer noncurrentDaysElapsed;

                private DateTime noncurrentPriorTo;

                private DateTime customPriorTo;

                private Integer customDaysElapsed;

                private LifecycleRuleBuilder() {
                }

                /**
                 * Sets the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
                 * Note that only date part will be considered, if the time is specified it will be
                 * truncated. This condition is satisfied when the custom time on an object is before this
                 * date in UTC.
                 */
                public LifecycleRuleBuilder setCustomTimeBefore(DateTime customPriorTo) {
                    this.customPriorTo = customPriorTo;
                    return this;
                }

                /**
                 * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
                 * only live Blobs; if the value is false, it matches only archived objects. For the
                 * purposes of this condition, Blobs in non-versioned buckets are considered live.
                 */
                public LifecycleRuleBuilder setIsLive(Boolean activeFlag) {
                    this.liveFlag = activeFlag;
                    return this;
                }

                /**
                 * Sets the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
                 * Note that only date part will be considered, if the time is specified it will be
                 * truncated. This condition is satisfied when the noncurrent time on an object is before
                 * this date. This condition is relevant only for versioned objects.
                 */
                public LifecycleRuleBuilder setNoncurrentTimeBefore(DateTime noncurrentPriorTo) {
                    this.noncurrentPriorTo = noncurrentPriorTo;
                    return this;
                }

                /**
                 * Sets the number of days elapsed since the user-specified timestamp set on an object. The
                 * condition is satisfied if the days elapsed is at least this number. If no custom
                 * timestamp is specified on an object, the condition does not apply.
                 */
                public LifecycleRuleBuilder setDaysSinceCustomTime(Integer customDaysElapsed) {
                    this.customDaysElapsed = customDaysElapsed;
                    return this;
                }

                /**
                 * Sets a list of Storage Classes for a objects that satisfy the condition to execute the
                 * Action. *
                 */
                public LifecycleRuleBuilder setMatchesStorageClass(List<StorageTier> storageClassFilters) {
                    this.storageClassFilters = storageClassFilters;
                    return this;
                }

                /**
                 * Builds a {@code LifecycleCondition} object. *
                 */
                public LifecycleRuleCondition buildLifecycleCondition() {
                    return new LifecycleRuleCondition(this);
                }

                /**
                 * Sets the date a Blob should be created before for an Action to be executed. Note that
                 * only the date will be considered, if the time is specified it will be truncated. This
                 * condition is satisfied when an object is created before midnight of the specified date in
                 * UTC. *
                 */
                public LifecycleRuleBuilder setCreatedBefore(DateTime createdPriorTo) {
                    this.createdPriorTo = createdPriorTo;
                    return this;
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
                 * Sets the number of newer versions a Blob should have for an Action to be executed.
                 * Relevant only when versioning is enabled on a bucket. *
                 */
                public LifecycleRuleBuilder setNumberOfNewerVersions(Integer newerVersionCount) {
                    this.newerVersionCount = newerVersionCount;
                    return this;
                }

                /**
                 * Sets the number of days elapsed since the noncurrent timestamp of an object. The
                 * condition is satisfied if the days elapsed is at least this number. This condition is
                 * relevant only for versioned objects. The value of the field must be a nonnegative
                 * integer. If it's zero, the object version will become eligible for Lifecycle action as
                 * soon as it becomes noncurrent.
                 */
                public LifecycleRuleBuilder setDaysSinceNoncurrentTime(Integer noncurrentDaysElapsed) {
                    this.noncurrentDaysElapsed = noncurrentDaysElapsed;
                    return this;
                }

            }

            /* Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").*/
            public DateTime getCustomTimeBefore() {
                return customPriorTo;
            }

            /**
             * Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
             */
            public DateTime getNoncurrentTimeBefore() {
                return noncurrentPriorTo;
            }

            /**
             * Returns the number of days elapsed since the user-specified timestamp set on an object.
             */
            public Integer getDaysSinceCustomTime() {
                return customDaysElapsed;
            }

            public Boolean getIsLive() {
                return liveFlag;
            }

            public Integer getNumberOfNewerVersions() {
                return newerVersionCount;
            }

            public DateTime getCreatedBefore() {
                return createdPriorTo;
            }

            public LifecycleRuleBuilder toBuilder() {
                return newLifecycleRuleBuilder().setAge(this.daysOld).setCreatedBefore(this.createdPriorTo).setNumberOfNewerVersions(this.newerVersionCount).setIsLive(this.liveFlag).setMatchesStorageClass(this.storageClassFilters).setDaysSinceNoncurrentTime(this.noncurrentDaysElapsed).setNoncurrentTimeBefore(this.noncurrentPriorTo).setCustomTimeBefore(this.customPriorTo).setDaysSinceCustomTime(this.customDaysElapsed);
            }

            public static LifecycleRuleBuilder newLifecycleRuleBuilder() {
                return new LifecycleRuleBuilder();
            }

            /**
             * Returns the number of days elapsed since the noncurrent timestamp of an object.
             */
            public Integer getDaysSinceNoncurrentTime() {
                return noncurrentDaysElapsed;
            }

            public List<StorageTier> getMatchesStorageClass() {
                return storageClassFilters;
            }

            public Integer getAge() {
                return daysOld;
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("age", daysOld).add("createBefore", createdPriorTo).add("numberofNewerVersions", newerVersionCount).add("isLive", liveFlag).add("matchesStorageClass", storageClassFilters).add("daysSinceNoncurrentTime", noncurrentDaysElapsed).add("noncurrentTimeBefore", noncurrentPriorTo).add("customTimeBefore", customPriorTo).add("daysSinceCustomTime", customDaysElapsed).toString();
            }

            private LifecycleRuleCondition(LifecycleRuleBuilder accessConfigurator) {
                this.daysOld = accessConfigurator.daysOld;
                this.createdPriorTo = accessConfigurator.createdPriorTo;
                this.newerVersionCount = accessConfigurator.newerVersionCount;
                this.liveFlag = accessConfigurator.liveFlag;
                this.storageClassFilters = accessConfigurator.storageClassFilters;
                this.noncurrentDaysElapsed = accessConfigurator.noncurrentDaysElapsed;
                this.noncurrentPriorTo = accessConfigurator.noncurrentPriorTo;
                this.customPriorTo = accessConfigurator.customPriorTo;
                this.customDaysElapsed = accessConfigurator.customDaysElapsed;
            }

        }

        /**
         * Base class for the Action to take when a Lifecycle Condition is met. Specific Actions are
         * expressed as subclasses of this class, accessed by static factory methods.
         */
        public abstract static class AbstractLifecycleAction implements Serializable {

            private static final long serialVersionUID = 5801228724709173284L;

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("actionType", getActionType()).toString();
            }

            /**
             * Creates a new {@code SetStorageClassLifecycleAction}. A Blob's storage class that meets the
             * action's conditions will be changed to the specified storage class.
             *
             * @param storageTier The new storage class to use when conditions are met for this action.
             */
            public static SetStorageClassLifecycleOperation newSetStorageClassLifecycleAction(StorageTier storageTier) {
                return new SetStorageClassLifecycleOperation(storageTier);
            }

            public abstract String getActionType();

            /**
             * Creates a new {@code DeleteLifecycleAction}. Blobs that meet the Condition associated with
             * this action will be deleted.
             */
            public static RemoveLifecycleAction newRemoveAction() {
                return new RemoveLifecycleAction();
            }

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

        public static class SetStorageClassLifecycleOperation extends AbstractLifecycleAction {

            public static final String TYPE = "SetStorageClass";

            private static final long serialVersionUID = -62615467186000899L;

            private final StorageTier storageTier;

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("actionType", getActionType()).add("storageClass", storageTier.name()).toString();
            }

            public StorageTier getStorageClass() {
                return storageTier;
            }

            private SetStorageClassLifecycleOperation(StorageTier storageTier) {
                this.storageTier = storageTier;
            }

            @Override
            public String getActionType() {
                return TYPE;
            }

        }

        @Override
        public boolean equals(Object objToCompare) {
            if (objToCompare == this) {
                return true;
            }
            if (null == objToCompare || objToCompare.getClass() != getClass()) {
                return false;
            }
            final LifecycleRuleDefinition thatSettings = (LifecycleRuleDefinition) objToCompare;
            return Objects.equals(toProto(), thatSettings.toProto());
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("lifecycleAction", lifecycleOperation).add("lifecycleCondition", lifecycleCriteria).toString();
        }

        static LifecycleRuleDefinition fromProto(Rule protoRule) {
            AbstractLifecycleAction lifecycleOperation;
            Rule.Action ruleActionParam = protoRule.getAction();
            switch(ruleActionParam.getType()) {
                case RemoveLifecycleAction.TYPE:
                    lifecycleOperation = AbstractLifecycleAction.newRemoveAction();
                    break;
                case SetStorageClassLifecycleOperation.TYPE:
                    lifecycleOperation = AbstractLifecycleAction.newSetStorageClassLifecycleAction(StorageTier.fromValue(ruleActionParam.getStorageClass()));
                    break;
                default:
                    throw new UnsupportedOperationException("The specified lifecycle action " + ruleActionParam.getType() + " is not currently supported");
            }
            Rule.Condition ruleConditionParam = protoRule.getCondition();
            LifecycleRuleCondition.LifecycleRuleBuilder lifecycleRuleBuilder = LifecycleRuleCondition.newLifecycleRuleBuilder().setAge(ruleConditionParam.getAge()).setCreatedBefore(ruleConditionParam.getCreatedBefore()).setIsLive(ruleConditionParam.getIsLive()).setNumberOfNewerVersions(ruleConditionParam.getNumNewerVersions()).setMatchesStorageClass(null == ruleConditionParam.getMatchesStorageClass() ? null : transform(ruleConditionParam.getMatchesStorageClass(), new Function<String, StorageTier>() {

                public StorageTier apply(String storageClass) {
                    return StorageTier.fromValue(storageClass);
                }
            })).setDaysSinceNoncurrentTime(ruleConditionParam.getDaysSinceNoncurrentTime()).setNoncurrentTimeBefore(ruleConditionParam.getNoncurrentTimeBefore()).setCustomTimeBefore(ruleConditionParam.getCustomTimeBefore()).setDaysSinceCustomTime(ruleConditionParam.getDaysSinceCustomTime());
            return new LifecycleRuleDefinition(lifecycleOperation, lifecycleRuleBuilder.buildLifecycleCondition());
        }

        Rule toProto() {
            Rule protoRule = new Rule();
            Rule.Action ruleActionParam = new Rule.Action().setType(lifecycleOperation.getActionType());
            if (lifecycleOperation.getActionType().equals(SetStorageClassLifecycleOperation.TYPE)) {
                ruleActionParam.setStorageClass(((SetStorageClassLifecycleOperation) lifecycleOperation).getStorageClass().toString());
            }
            protoRule.setAction(ruleActionParam);
            Rule.Condition ruleConditionParam = new Rule.Condition().setAge(lifecycleCriteria.getAge()).setCreatedBefore(null == lifecycleCriteria.getCreatedBefore() ? null : new DateTime(true, lifecycleCriteria.getCreatedBefore().getValue(), 0)).setIsLive(lifecycleCriteria.getIsLive()).setNumNewerVersions(lifecycleCriteria.getNumberOfNewerVersions()).setMatchesStorageClass(null == lifecycleCriteria.getMatchesStorageClass() ? null : transform(lifecycleCriteria.getMatchesStorageClass(), Functions.toStringFunction())).setDaysSinceNoncurrentTime(lifecycleCriteria.getDaysSinceNoncurrentTime()).setNoncurrentTimeBefore(null == lifecycleCriteria.getNoncurrentTimeBefore() ? null : new DateTime(true, lifecycleCriteria.getNoncurrentTimeBefore().getValue(), 0)).setCustomTimeBefore(null == lifecycleCriteria.getCustomTimeBefore() ? null : new DateTime(true, lifecycleCriteria.getCustomTimeBefore().getValue(), 0)).setDaysSinceCustomTime(lifecycleCriteria.getDaysSinceCustomTime());
            protoRule.setCondition(ruleConditionParam);
            return protoRule;
        }

        public LifecycleRuleDefinition(AbstractLifecycleAction ruleActionParam, LifecycleRuleCondition ruleConditionParam) {
            if (null == ruleConditionParam.getIsLive() && null == ruleConditionParam.getAge() && null == ruleConditionParam.getCreatedBefore() && null == ruleConditionParam.getMatchesStorageClass() && null == ruleConditionParam.getNumberOfNewerVersions() && null == ruleConditionParam.getDaysSinceNoncurrentTime() && null == ruleConditionParam.getNoncurrentTimeBefore() && null == ruleConditionParam.getCustomTimeBefore() && null == ruleConditionParam.getDaysSinceCustomTime()) {
                throw new IllegalArgumentException("You must specify at least one condition to use object lifecycle " + "management. Please see https://cloud.google.com/storage/docs/lifecycle for details.");
            }
            this.lifecycleOperation = ruleActionParam;
            this.lifecycleCriteria = ruleConditionParam;
        }

        public LifecycleRuleCondition getCondition() {
            return lifecycleCriteria;
        }

        @Override
        public int hashCode() {
            return Objects.hash(lifecycleOperation, lifecycleCriteria);
        }

        public AbstractLifecycleAction getAction() {
            return lifecycleOperation;
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

        private final VersionSelectionType versionSelection;

        public enum VersionSelectionType {

            AGE, CREATE_BEFORE, NUM_NEWER_VERSIONS, IS_LIVE, UNKNOWN
        }

        abstract void populateRuleCondition(Rule.Condition condition);

        Rule toProto() {
            Rule protoRule = new Rule();
            protoRule.setAction(new Rule.Action().setType(ALLOWED_ACTIONS));
            Rule.Condition ruleConditionParam = new Rule.Condition();
            populateRuleCondition(ruleConditionParam);
            protoRule.setCondition(ruleConditionParam);
            return protoRule;
        }

        static DeletionRule fromProto(Rule protoRule) {
            if (null != protoRule.getAction() && ALLOWED_ACTIONS.endsWith(protoRule.getAction().getType())) {
                Rule.Condition ruleConditionParam = protoRule.getCondition();
                Integer daysOld = ruleConditionParam.getAge();
                if (null != daysOld) {
                    return new AgeBasedDeletionRule(daysOld);
                }
                DateTime timestamp = ruleConditionParam.getCreatedBefore();
                if (null != timestamp) {
                    return new CreatedBeforeDeletionRule(timestamp.getValue());
                }
                Integer newerVersionCount = ruleConditionParam.getNumNewerVersions();
                if (null != newerVersionCount) {
                    return new NumNewerVersionsDeletionRule(newerVersionCount);
                }
                Boolean liveFlag = ruleConditionParam.getIsLive();
                if (null != liveFlag) {
                    return new LiveDeleteRule(liveFlag);
                }
            }
            return new RawDeletionRule(protoRule);
        }

        @Override
        public boolean equals(Object objToCompare) {
            if (objToCompare == this) {
                return true;
            }
            if (null == objToCompare || objToCompare.getClass() != getClass()) {
                return false;
            }
            final DeletionRule thatSettings = (DeletionRule) objToCompare;
            return Objects.equals(toProto(), thatSettings.toProto());
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionSelection);
        }

        public VersionSelectionType getType() {
            return versionSelection;
        }

        DeletionRule(VersionSelectionType versionSelection) {
            this.versionSelection = versionSelection;
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

        private final int retentionDays;

        @Override
        void populateRuleCondition(Rule.Condition ruleConditionParam) {
            ruleConditionParam.setAge(retentionDays);
        }

        public int getDaysToLive() {
            return retentionDays;
        }

        /**
         * Creates an {@code AgeDeleteRule} object.
         *
         * @param retentionDays blobs' Time To Live expressed in days. The time when the age condition is
         *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
         *     following blob's creation time in UTC.
         */
        public AgeBasedDeletionRule(int retentionDays) {
            super(VersionSelectionType.AGE);
            this.retentionDays = retentionDays;
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
        void populateRuleCondition(Rule.Condition condition) {
            throw new UnsupportedOperationException();
        }

        RawDeletionRule(Rule protoRule) {
            super(VersionSelectionType.UNKNOWN);
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
    public static class CreatedBeforeDeletionRule extends DeletionRule {

        private static final long serialVersionUID = 881692650279195867L;

        private final long createdAtMillis;

        @Override
        void populateRuleCondition(Rule.Condition ruleConditionParam) {
            ruleConditionParam.setCreatedBefore(new DateTime(true, createdAtMillis, 0));
        }

        /**
         * Creates an {@code CreatedBeforeDeleteRule} object.
         *
         * @param createdAtMillis a date in UTC. Blobs that have been created before midnight of the provided
         *     date meet the delete condition
         */
        public CreatedBeforeDeletionRule(long createdAtMillis) {
            super(VersionSelectionType.CREATE_BEFORE);
            this.createdAtMillis = createdAtMillis;
        }

        public long getTimeMillis() {
            return createdAtMillis;
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

        private final int newerVersionCount;

        @Override
        void populateRuleCondition(Rule.Condition ruleConditionParam) {
            ruleConditionParam.setNumNewerVersions(newerVersionCount);
        }

        public int getNumNewerVersions() {
            return newerVersionCount;
        }

        /**
         * Creates an {@code NumNewerVersionsDeleteRule} object.
         *
         * @param newerVersionCount the number of newer versions. A blob's version meets the delete
         *     condition when {@code numNewerVersions} newer versions are available.
         */
        public NumNewerVersionsDeletionRule(int newerVersionCount) {
            super(VersionSelectionType.NUM_NEWER_VERSIONS);
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
    public static class LiveDeleteRule extends DeletionRule {

        private static final long serialVersionUID = -3502994563121313364L;

        private final boolean liveFlag;

        @Override
        void populateRuleCondition(Rule.Condition ruleConditionParam) {
            ruleConditionParam.setIsLive(liveFlag);
        }

        public boolean isLive() {
            return liveFlag;
        }

        /**
         * Creates an {@code IsLiveDeleteRule} object.
         *
         * @param liveFlag if set to {@code true} live blobs meet the delete condition. If set to {@code
         *     false} delete condition is met by archived blobs.
         */
        public LiveDeleteRule(boolean liveFlag) {
            super(VersionSelectionType.IS_LIVE);
            this.liveFlag = liveFlag;
        }

    }

    /**
     * Builder for {@code BucketInfo}.
     */
    public abstract static class Builder {

        /**
         * Sets the bucket's lifecycle configuration as a number of lifecycle rules, consisting of an
         * action and a condition.
         *
         * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
         *     Management</a>
         */
        public abstract Builder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> rules);

        abstract Builder setLocationType(String locationType);

        /**
         * Deletes the lifecycle rules of this bucket.
         */
        public abstract Builder deleteLifecycleRules();

        abstract Builder setEtag(String etag);

        abstract Builder setCreateTime(Long createTime);

        @BetaApi
        abstract Builder setRetentionEffectiveTime(Long retentionEffectiveTime);

        /**
         * Sets the bucket's lifecycle configuration as a number of delete rules.
         *
         * @deprecated Use {@code setLifecycleRules} instead, as in {@code
         *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRule(
         *     LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(5).build())));}
         */
        @Deprecated
        public abstract Builder setDeleteRules(Iterable<? extends DeletionRule> rules);

        public abstract Builder setLogging(LoggingConfig logging);

        /**
         * Sets the default event-based hold for this bucket.
         */
        @BetaApi
        public abstract Builder setDefaultEventBasedHold(Boolean defaultEventBasedHold);

        /**
         * Creates a {@code BucketInfo} object.
         */
        public abstract BucketInfo buildBucketInfo();

        /**
         * Sets the bucket's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract Builder setAcl(Iterable<AccessControlEntry> acl);

        /**
         * Sets the bucket's name.
         */
        public abstract Builder setName(String name);

        /**
         * Sets whether a user accessing the bucket or an object it contains should assume the transit
         * costs related to the access.
         */
        public abstract Builder setRequesterPays(Boolean requesterPays);

        /**
         * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
         * this region. A list of supported values is available <a
         * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
         */
        public abstract Builder setLocation(String location);

        /**
         * Sets whether versioning should be enabled for this bucket. When set to true, versioning is
         * fully enabled.
         */
        public abstract Builder setVersioningEnabled(Boolean enable);

        @BetaApi
        abstract Builder setRetentionPolicyIsLocked(Boolean retentionPolicyIsLocked);

        abstract Builder setUpdateTime(Long updateTime);

        /**
         * Sets the default Cloud KMS key name for this bucket.
         */
        public abstract Builder setDefaultKmsKeyName(String defaultKmsKeyName);

        abstract Builder setGeneratedId(String generatedId);

        /**
         * If policy is not locked this value can be cleared, increased, and decreased. If policy is
         * locked the retention period can only be increased.
         */
        @BetaApi
        public abstract Builder setRetentionPeriod(Long retentionPeriod);

        /**
         * Sets the default access control configuration to apply to bucket's blobs when no other
         * configuration is specified.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract Builder setDefaultAcl(Iterable<AccessControlEntry> acl);

        /**
         * Sets the custom object to return when a requested resource is not found.
         */
        public abstract Builder setNotFoundPage(String notFoundPage);

        abstract Builder setOwner(TypedEntity owner);

        /**
         * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
         * determines the SLA and the cost of storage. A list of supported values is available <a
         * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
         */
        public abstract Builder setStorageClass(StorageTier storageClass);

        abstract Builder setMetageneration(Long metageneration);

        abstract Builder setSelfLink(String selfLink);

        /**
         * Sets the bucket's website index page. Behaves as the bucket's directory index where missing
         * blobs are treated as potential directories.
         */
        public abstract Builder setIndexPage(String indexPage);

        /**
         * Sets the label of this bucket.
         */
        public abstract Builder setLabels(Map<String, String> labels);

        /**
         * Sets the IamConfiguration to specify whether IAM access should be enabled.
         *
         * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
         *     Only</a>
         */
        @BetaApi
        public abstract Builder setIamConfiguration(IamSettings iamConfiguration);

        /**
         * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
         *
         * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
         *     Sharing (CORS)</a>
         */
        public abstract Builder setCors(Iterable<Cors> cors);

        Builder() {
        }

    }

    static final class BucketBuilderImpl extends Builder {

        private String generatedIdentifier;

        private String label;

        private TypedEntity principalEntity;

        private String resourceLink;

        private Boolean isUserCharged;

        private Boolean isVersioningActive;

        private String indexDocument;

        private String errorDocument;

        private List<DeletionRule> removalRules;

        private List<LifecycleRuleDefinition> lifecyclePolicies;

        private StorageTier storageTier;

        private String region;

        private String entityTag;

        private Long creationTimestamp;

        private Long lastModifiedTimestamp;

        private Long metadataGeneration;

        private List<Cors> crossOriginSettings;

        private List<AccessControlEntry> accessControlEntries;

        private List<AccessControlEntry> defaultAccessEntries;

        private Map<String, String> tags;

        private String defaultEncryptionKey;

        private Boolean eventHoldEnabled;

        private Long retentionEffectiveTimestamp;

        private Boolean isRetentionLocked;

        private Long retentionDuration;

        private IamSettings iamSettings;

        private String regionType;

        private LoggingConfig logSettings;

        @Override
        public Builder setCors(Iterable<Cors> crossOriginSettings) {
            this.crossOriginSettings = null != crossOriginSettings ? ImmutableList.copyOf(crossOriginSettings) : ImmutableList.<Cors>of();
            return this;
        }

        @Override
        Builder setRetentionPolicyIsLocked(Boolean isRetentionLocked) {
            this.isRetentionLocked = firstNonNull(isRetentionLocked, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public Builder setVersioningEnabled(Boolean versioningOn) {
            this.isVersioningActive = firstNonNull(versioningOn, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        Builder setMetageneration(Long metadataGeneration) {
            this.metadataGeneration = metadataGeneration;
            return this;
        }

        @Override
        public Builder deleteLifecycleRules() {
            setDeleteRules(null);
            setLifecycleRules(null);
            return this;
        }

        @Override
        Builder setSelfLink(String resourceLink) {
            this.resourceLink = resourceLink;
            return this;
        }

        @Override
        Builder setLocationType(String regionType) {
            this.regionType = regionType;
            return this;
        }

        @Override
        public Builder setIamConfiguration(IamSettings iamSettings) {
            this.iamSettings = iamSettings;
            return this;
        }

        @Override
        Builder setRetentionEffectiveTime(Long retentionEffectiveTimestamp) {
            this.retentionEffectiveTimestamp = firstNonNull(retentionEffectiveTimestamp, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        public Builder setLogging(LoggingConfig logSettings) {
            this.logSettings = null != logSettings ? logSettings : LoggingConfig.newLogConfigBuilder().buildLoggingConfig();
            return this;
        }

        @Override
        public Builder setIndexPage(String indexDocument) {
            this.indexDocument = indexDocument;
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
        public BucketInfo buildBucketInfo() {
            checkNotNull(label);
            return new BucketInfo(this);
        }

        @Override
        Builder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        Builder setUpdateTime(Long lastModifiedTimestamp) {
            this.lastModifiedTimestamp = lastModifiedTimestamp;
            return this;
        }

        @Override
        public Builder setDefaultAcl(Iterable<AccessControlEntry> accessControlEntries) {
            this.defaultAccessEntries = null != accessControlEntries ? ImmutableList.copyOf(accessControlEntries) : null;
            return this;
        }

        /**
         * @deprecated Use {@code setLifecycleRules} method instead. *
         */
        @Override
        @Deprecated
        public Builder setDeleteRules(Iterable<? extends DeletionRule> ruleCollection) {
            this.removalRules = null != ruleCollection ? ImmutableList.copyOf(ruleCollection) : null;
            return this;
        }

        @Override
        public Builder setRetentionPeriod(Long retentionDuration) {
            this.retentionDuration = firstNonNull(retentionDuration, Data.<Long>nullOf(Long.class));
            return this;
        }

        BucketBuilderImpl(String label) {
            this.label = label;
        }

        @Override
        Builder setCreateTime(Long creationTimestamp) {
            this.creationTimestamp = creationTimestamp;
            return this;
        }

        @Override
        public Builder setStorageClass(StorageTier storageTier) {
            this.storageTier = storageTier;
            return this;
        }

        @Override
        public Builder setRequesterPays(Boolean versioningOn) {
            this.isUserCharged = firstNonNull(versioningOn, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public Builder setDefaultKmsKeyName(String defaultEncryptionKey) {
            this.defaultEncryptionKey = null != defaultEncryptionKey ? defaultEncryptionKey : Data.<String>nullOf(String.class);
            return this;
        }

        BucketBuilderImpl(BucketInfo bucketMetadata) {
            generatedIdentifier = bucketMetadata.generatedIdentifier;
            label = bucketMetadata.label;
            entityTag = bucketMetadata.entityTag;
            creationTimestamp = bucketMetadata.creationTimestamp;
            lastModifiedTimestamp = bucketMetadata.lastModifiedTimestamp;
            metadataGeneration = bucketMetadata.metadataGeneration;
            region = bucketMetadata.region;
            storageTier = bucketMetadata.storageTier;
            crossOriginSettings = bucketMetadata.crossOriginSettings;
            accessControlEntries = bucketMetadata.accessControlEntries;
            defaultAccessEntries = bucketMetadata.defaultAccessEntries;
            principalEntity = bucketMetadata.principalEntity;
            resourceLink = bucketMetadata.resourceLink;
            isVersioningActive = bucketMetadata.isVersioningActive;
            indexDocument = bucketMetadata.indexDocument;
            errorDocument = bucketMetadata.errorDocument;
            removalRules = bucketMetadata.removalRules;
            lifecyclePolicies = bucketMetadata.lifecyclePolicies;
            tags = bucketMetadata.tags;
            isUserCharged = bucketMetadata.isUserCharged;
            defaultEncryptionKey = bucketMetadata.defaultEncryptionKey;
            eventHoldEnabled = bucketMetadata.eventHoldEnabled;
            retentionEffectiveTimestamp = bucketMetadata.retentionEffectiveTimestamp;
            isRetentionLocked = bucketMetadata.isRetentionLocked;
            retentionDuration = bucketMetadata.retentionDuration;
            iamSettings = bucketMetadata.iamSettings;
            regionType = bucketMetadata.regionType;
            logSettings = bucketMetadata.logSettings;
        }

        @Override
        Builder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        @Override
        public Builder setLocation(String region) {
            this.region = region;
            return this;
        }

        @Override
        public Builder setAcl(Iterable<AccessControlEntry> accessControlEntries) {
            this.accessControlEntries = null != accessControlEntries ? ImmutableList.copyOf(accessControlEntries) : null;
            return this;
        }

        @Override
        public Builder setNotFoundPage(String errorDocument) {
            this.errorDocument = errorDocument;
            return this;
        }

        @Override
        public Builder setDefaultEventBasedHold(Boolean eventHoldEnabled) {
            this.eventHoldEnabled = firstNonNull(eventHoldEnabled, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public Builder setName(String label) {
            this.label = checkNotNull(label);
            return this;
        }

        @Override
        Builder setOwner(TypedEntity principalEntity) {
            this.principalEntity = principalEntity;
            return this;
        }

        @Override
        public Builder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> ruleCollection) {
            this.lifecyclePolicies = null != ruleCollection ? ImmutableList.copyOf(ruleCollection) : ImmutableList.<LifecycleRuleDefinition>of();
            return this;
        }

    }

    /**
     * Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket.
     */
    public String getDefaultKmsKeyName() {
        return defaultEncryptionKey;
    }

    public List<? extends LifecycleRuleDefinition> getLifecycleRules() {
        return null != lifecyclePolicies ? lifecyclePolicies : ImmutableList.<LifecycleRuleDefinition>of();
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
     * {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link CloudStorageClient.BucketProperty#BILLING}
     * is selected in a {@link CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} and requester pays for
     * the bucket is enabled.
     *
     * <p>Case 2: {@code false} the field {@link CloudStorageClient.BucketProperty#BILLING}
     * in a {@link CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} is selected and requester pays for
     * the bucket is disable.
     *
     * <p>Case 3: {@code null} the field {@link CloudStorageClient.BucketProperty#BILLING}
     * in a {@link CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} is not selected, the value is
     * unknown.
     */
    public Boolean isRequesterPays() {
        return Data.isNull(isUserCharged) ? null : isUserCharged;
    }

    Bucket toProto() {
        Bucket bucketProto = new Bucket();
        bucketProto.setId(generatedIdentifier);
        bucketProto.setName(label);
        bucketProto.setEtag(entityTag);
        if (null != creationTimestamp) {
            bucketProto.setTimeCreated(new DateTime(creationTimestamp));
        }
        if (null != lastModifiedTimestamp) {
            bucketProto.setUpdated(new DateTime(lastModifiedTimestamp));
        }
        if (null != metadataGeneration) {
            bucketProto.setMetageneration(metadataGeneration);
        }
        if (null != region) {
            bucketProto.setLocation(region);
        }
        if (null != regionType) {
            bucketProto.setLocationType(regionType);
        }
        if (null != storageTier) {
            bucketProto.setStorageClass(storageTier.toString());
        }
        if (null != crossOriginSettings) {
            bucketProto.setCors(transform(crossOriginSettings, Cors.TO_PB_FUNCTION));
        }
        if (null != accessControlEntries) {
            bucketProto.setAcl(transform(accessControlEntries, new Function<AccessControlEntry, BucketAccessControl>() {

                @Override
                public BucketAccessControl apply(AccessControlEntry acl) {
                    return acl.toBucketProto();
                }
            }));
        }
        if (null != defaultAccessEntries) {
            bucketProto.setDefaultObjectAcl(transform(defaultAccessEntries, new Function<AccessControlEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != principalEntity) {
            bucketProto.setOwner(new Owner().setEntity(principalEntity.toProto()));
        }
        bucketProto.setSelfLink(resourceLink);
        if (null != isVersioningActive) {
            bucketProto.setVersioning(new Versioning().setEnabled(isVersioningActive));
        }
        if (null != isUserCharged) {
            Bucket.Billing chargeConfig = new Bucket.Billing();
            chargeConfig.setRequesterPays(isUserCharged);
            bucketProto.setBilling(chargeConfig);
        }
        if (null != indexDocument || null != errorDocument) {
            Website siteConfig = new Website();
            siteConfig.setMainPageSuffix(indexDocument);
            siteConfig.setNotFoundPage(errorDocument);
            bucketProto.setWebsite(siteConfig);
        }
        Set<Rule> ruleCollection = new HashSet<>();
        if (null != removalRules) {
            ruleCollection.addAll(transform(removalRules, new Function<DeletionRule, Rule>() {

                @Override
                public Rule apply(DeletionRule deleteRule) {
                    return deleteRule.toProto();
                }
            }));
        }
        if (null != lifecyclePolicies) {
            ruleCollection.addAll(transform(lifecyclePolicies, new Function<LifecycleRuleDefinition, Rule>() {

                @Override
                public Rule apply(LifecycleRuleDefinition lifecycleRule) {
                    return lifecycleRule.toProto();
                }
            }));
        }
        if (null != ruleCollection) {
            Lifecycle lifePolicy = new Lifecycle();
            lifePolicy.setRule(ImmutableList.copyOf(ruleCollection));
            bucketProto.setLifecycle(lifePolicy);
        }
        if (null != tags) {
            bucketProto.setLabels(tags);
        }
        if (null != defaultEncryptionKey) {
            bucketProto.setEncryption(new Encryption().setDefaultKmsKeyName(defaultEncryptionKey));
        }
        if (null != eventHoldEnabled) {
            bucketProto.setDefaultEventBasedHold(eventHoldEnabled);
        }
        if (null != retentionDuration) {
            if (!Data.isNull(retentionDuration)) {
                Bucket.RetentionPolicy retentionConfig = new Bucket.RetentionPolicy();
                retentionConfig.setRetentionPeriod(retentionDuration);
                if (null != retentionEffectiveTimestamp) {
                    retentionConfig.setEffectiveTime(new DateTime(retentionEffectiveTimestamp));
                }
                if (null != isRetentionLocked) {
                    retentionConfig.setIsLocked(isRetentionLocked);
                }
                bucketProto.setRetentionPolicy(retentionConfig);
            } else {
                bucketProto.setRetentionPolicy(Data.<Bucket.RetentionPolicy>nullOf(Bucket.RetentionPolicy.class));
            }
        }
        if (null != iamSettings) {
            bucketProto.setIamConfiguration(iamSettings.toProto());
        }
        if (null != logSettings) {
            bucketProto.setLogging(logSettings.toProto());
        }
        return bucketProto;
    }

    /**
     * Returns the retention effective time a policy took effect if a retention policy is defined as a
     * {@code Long}.
     */
    @BetaApi
    public Long getRetentionEffectiveTime() {
        return retentionEffectiveTimestamp;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", label).toString();
    }

    /**
     * Returns the bucket's Cross-Origin Resource Sharing (CORS) configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource Sharing
     *     (CORS)</a>
     */
    public List<Cors> getCors() {
        return crossOriginSettings;
    }

    /**
     * Returns the retention policy retention period.
     */
    @BetaApi
    public Long getRetentionPeriod() {
        return retentionDuration;
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
     * Returns bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public String getIndexPage() {
        return indexDocument;
    }

    /**
     * Returns a builder for the current bucket.
     */
    public Builder toBucketBuilder() {
        return new BucketBuilderImpl(this);
    }

    static BucketInfo fromProto(Bucket bucketProto) {
        Builder accessConfigurator = new BucketBuilderImpl(bucketProto.getName());
        if (null != bucketProto.getId()) {
            accessConfigurator.setGeneratedId(bucketProto.getId());
        }
        if (null != bucketProto.getEtag()) {
            accessConfigurator.setEtag(bucketProto.getEtag());
        }
        if (null != bucketProto.getMetageneration()) {
            accessConfigurator.setMetageneration(bucketProto.getMetageneration());
        }
        if (null != bucketProto.getSelfLink()) {
            accessConfigurator.setSelfLink(bucketProto.getSelfLink());
        }
        if (null != bucketProto.getTimeCreated()) {
            accessConfigurator.setCreateTime(bucketProto.getTimeCreated().getValue());
        }
        if (null != bucketProto.getUpdated()) {
            accessConfigurator.setUpdateTime(bucketProto.getUpdated().getValue());
        }
        if (null != bucketProto.getLocation()) {
            accessConfigurator.setLocation(bucketProto.getLocation());
        }
        if (null != bucketProto.getStorageClass()) {
            accessConfigurator.setStorageClass(StorageTier.fromValue(bucketProto.getStorageClass()));
        }
        if (null != bucketProto.getCors()) {
            accessConfigurator.setCors(transform(bucketProto.getCors(), Cors.FROM_PB_FUNCTION));
        }
        if (null != bucketProto.getAcl()) {
            accessConfigurator.setAcl(transform(bucketProto.getAcl(), new Function<BucketAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(BucketAccessControl bucketAccessControl) {
                    return AccessControlEntry.fromProto(bucketAccessControl);
                }
            }));
        }
        if (null != bucketProto.getDefaultObjectAcl()) {
            accessConfigurator.setDefaultAcl(transform(bucketProto.getDefaultObjectAcl(), new Function<ObjectAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                    return AccessControlEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (null != bucketProto.getOwner()) {
            accessConfigurator.setOwner(TypedEntity.fromProto(bucketProto.getOwner().getEntity()));
        }
        if (null != bucketProto.getVersioning()) {
            accessConfigurator.setVersioningEnabled(bucketProto.getVersioning().getEnabled());
        }
        Website siteConfig = bucketProto.getWebsite();
        if (null != siteConfig) {
            accessConfigurator.setIndexPage(siteConfig.getMainPageSuffix());
            accessConfigurator.setNotFoundPage(siteConfig.getNotFoundPage());
        }
        if (null != bucketProto.getLifecycle() && null != bucketProto.getLifecycle().getRule()) {
            accessConfigurator.setLifecycleRules(transform(bucketProto.getLifecycle().getRule(), new Function<Rule, LifecycleRuleDefinition>() {

                @Override
                public BucketInfo.LifecycleRuleDefinition apply(Rule rule) {
                    return LifecycleRuleDefinition.fromProto(rule);
                }
            }));
            accessConfigurator.setDeleteRules(transform(bucketProto.getLifecycle().getRule(), new Function<Rule, DeletionRule>() {

                @Override
                public BucketInfo.DeletionRule apply(Rule rule) {
                    return DeletionRule.fromProto(rule);
                }
            }));
        }
        if (null != bucketProto.getLabels()) {
            accessConfigurator.setLabels(bucketProto.getLabels());
        }
        Bucket.Billing chargeConfig = bucketProto.getBilling();
        if (null != chargeConfig) {
            accessConfigurator.setRequesterPays(chargeConfig.getRequesterPays());
        }
        Encryption cryptoConfig = bucketProto.getEncryption();
        if (null != cryptoConfig && null != cryptoConfig.getDefaultKmsKeyName() && !cryptoConfig.getDefaultKmsKeyName().isEmpty()) {
            accessConfigurator.setDefaultKmsKeyName(cryptoConfig.getDefaultKmsKeyName());
        }
        if (null != bucketProto.getDefaultEventBasedHold()) {
            accessConfigurator.setDefaultEventBasedHold(bucketProto.getDefaultEventBasedHold());
        }
        Bucket.RetentionPolicy retentionConfig = bucketProto.getRetentionPolicy();
        if (null != retentionConfig) {
            if (null != retentionConfig.getEffectiveTime()) {
                accessConfigurator.setRetentionEffectiveTime(retentionConfig.getEffectiveTime().getValue());
            }
            if (null != retentionConfig.getIsLocked()) {
                accessConfigurator.setRetentionPolicyIsLocked(retentionConfig.getIsLocked());
            }
            if (null != retentionConfig.getRetentionPeriod()) {
                accessConfigurator.setRetentionPeriod(retentionConfig.getRetentionPeriod());
            }
        }
        Bucket.IamConfiguration iamSettings = bucketProto.getIamConfiguration();
        if (null != bucketProto.getLocationType()) {
            accessConfigurator.setLocationType(bucketProto.getLocationType());
        }
        if (null != iamSettings) {
            accessConfigurator.setIamConfiguration(IamSettings.fromProto(iamSettings));
        }
        Bucket.Logging logSettings = bucketProto.getLogging();
        if (null != logSettings) {
            accessConfigurator.setLogging(LoggingConfig.fromProto(logSettings));
        }
        return accessConfigurator.buildBucketInfo();
    }

    /**
     * Returns the service-generated id for the bucket.
     */
    public String getGeneratedId() {
        return generatedIdentifier;
    }

    /**
     * Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name.
     */
    public static Builder newBucketBuilder(String label) {
        return new BucketBuilderImpl(label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label);
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * CloudStorageClient.BucketProperty#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} and default event-based hold for the bucket is
     * enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * CloudStorageClient.BucketProperty#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, but default event-based hold for the bucket
     * is not enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * CloudStorageClient.BucketProperty#DEFAULT_EVENT_BASED_HOLD} is not selected in a
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
        return Data.isNull(eventHoldEnabled) ? null : eventHoldEnabled;
    }

    /**
     * Returns a {@code Boolean} with either {@code true} or {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link
     * CloudStorageClient.BucketProperty#RETENTION_POLICY} is selected in a {@link
     * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} and retention policy for the bucket is locked.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * CloudStorageClient.BucketProperty#RETENTION_POLICY} is selected in a {@link
     * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, but retention policy for the bucket is not
     * locked. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * CloudStorageClient.BucketProperty#RETENTION_POLICY} is not selected in a {@link
     * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, and the state for this field is unknown.
     */
    @BetaApi
    public Boolean isRetentionPolicyLocked() {
        return Data.isNull(isRetentionLocked) ? null : isRetentionLocked;
    }

    /**
     * Returns the URI of this bucket as a string.
     */
    public String getSelfLink() {
        return resourceLink;
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
     * Returns the IAM configuration
     */
    @BetaApi
    public IamSettings getIamConfiguration() {
        return iamSettings;
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
     * Returns the bucket's owner. This is always the project team's owner group.
     */
    public TypedEntity getOwner() {
        return principalEntity;
    }

    /**
     * Creates a {@code BucketInfo} object for the provided bucket name.
     */
    public static BucketInfo ofName(String label) {
        return newBucketBuilder(label).buildBucketInfo();
    }

    /**
     * Returns the metadata generation of this bucket.
     */
    public Long getMetageneration() {
        return metadataGeneration;
    }

    /**
     * Returns the bucket's access control configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AccessControlEntry> getAcl() {
        return accessControlEntries;
    }

    /**
     * Returns the time at which the bucket was created.
     */
    public Long getCreateTime() {
        return creationTimestamp;
    }

    /**
     * Returns the custom object to return when a requested resource is not found.
     */
    public String getNotFoundPage() {
        return errorDocument;
    }

    /**
     * Returns the default access control configuration for this bucket's blobs.
     *
     * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
     *     About Access Control Lists</a>
     */
    public List<AccessControlEntry> getDefaultAcl() {
        return defaultAccessEntries;
    }

    /**
     * Returns bucket's lifecycle configuration as a number of delete rules.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Lifecycle Management</a>
     */
    @Deprecated
    public List<? extends DeletionRule> getDeleteRules() {
        return removalRules;
    }

    @Override
    public boolean equals(Object objToCompare) {
        return this == objToCompare || null != objToCompare && objToCompare.getClass().equals(BucketInfo.class) && Objects.equals(toProto(), ((BucketInfo) objToCompare).toProto());
    }

    /**
     * Returns the Logging
     */
    public LoggingConfig getLogging() {
        return logSettings;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * CloudStorageClient.BucketProperty#VERSIONING} is selected in a {@link
     * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)} and versions for the bucket is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * CloudStorageClient.BucketProperty#VERSIONING} is selected in a {@link
     * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, but versions for the bucket is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * CloudStorageClient.BucketProperty#VERSIONING} is not selected in a {@link
     * CloudStorageClient#get(String, CloudStorageClient.BucketGetOptions...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
     * request for example {@link CloudStorageClient#update(BucketInfo, CloudStorageClient.BucketTargetOptions...)} in which
     * case the value of versions will remain {@code false} for for the given instance.
     */
    public Boolean isVersioningEnabled() {
        return Data.isNull(isVersioningActive) ? null : isVersioningActive;
    }

    /**
     * Returns the last modification time of the bucket's metadata expressed as the number of
     * milliseconds since the Unix epoch.
     */
    public Long getUpdateTime() {
        return lastModifiedTimestamp;
    }

    /**
     * Returns the labels for this bucket.
     */
    public Map<String, String> getLabels() {
        return tags;
    }

    /**
     * Returns the bucket's name.
     */
    public String getName() {
        return label;
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

    BucketInfo(BucketBuilderImpl accessConfigurator) {
        generatedIdentifier = accessConfigurator.generatedIdentifier;
        label = accessConfigurator.label;
        entityTag = accessConfigurator.entityTag;
        creationTimestamp = accessConfigurator.creationTimestamp;
        lastModifiedTimestamp = accessConfigurator.lastModifiedTimestamp;
        metadataGeneration = accessConfigurator.metadataGeneration;
        region = accessConfigurator.region;
        storageTier = accessConfigurator.storageTier;
        crossOriginSettings = accessConfigurator.crossOriginSettings;
        accessControlEntries = accessConfigurator.accessControlEntries;
        defaultAccessEntries = accessConfigurator.defaultAccessEntries;
        principalEntity = accessConfigurator.principalEntity;
        resourceLink = accessConfigurator.resourceLink;
        isVersioningActive = accessConfigurator.isVersioningActive;
        indexDocument = accessConfigurator.indexDocument;
        errorDocument = accessConfigurator.errorDocument;
        removalRules = accessConfigurator.removalRules;
        lifecyclePolicies = accessConfigurator.lifecyclePolicies;
        tags = accessConfigurator.tags;
        isUserCharged = accessConfigurator.isUserCharged;
        defaultEncryptionKey = accessConfigurator.defaultEncryptionKey;
        eventHoldEnabled = accessConfigurator.eventHoldEnabled;
        retentionEffectiveTimestamp = accessConfigurator.retentionEffectiveTimestamp;
        isRetentionLocked = accessConfigurator.isRetentionLocked;
        retentionDuration = accessConfigurator.retentionDuration;
        iamSettings = accessConfigurator.iamSettings;
        regionType = accessConfigurator.regionType;
        logSettings = accessConfigurator.logSettings;
    }

}
