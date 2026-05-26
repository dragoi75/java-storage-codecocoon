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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

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

    static final Function<BucketMetadata, com.google.api.services.storage.model.Bucket> TO_BUCKET_FUNCTION = new Function<BucketMetadata, com.google.api.services.storage.model.Bucket>() {

        @Override
        public com.google.api.services.storage.model.Bucket apply(BucketMetadata bucketInfo) {
            return bucketInfo.toProto();
        }
    };

    private static final long serialVersionUID = -4712013629621638459L;

    private final String generatedIdentifier;

    private final String displayName;

    private final AbstractEntity principal;

    private final String resourceUrl;

    private final Boolean billingEnabled;

    private final Boolean versioningActive;

    private final String indexDocument;

    private final String notFoundDocument;

    private final List<DeletionRule> deletionRules;

    private final List<LifecycleRuleDefinition> lifecyclePolicies;

    private final String entityTag;

    private final Long creationTime;

    private final Long updatedAt;

    private final Long metadataVersion;

    private final List<CorsConfiguration> crossOriginConfigs;

    private final List<AccessControlEntry> accessControlList;

    private final List<AccessControlEntry> defaultAccessControls;

    private final String region;

    private final RecoveryPointObjective recoveryObjective;

    private final StorageTier storageTier;

    private final Map<String, String> tags;

    private final String primaryKmsKey;

    private final Boolean eventHoldEnabled;

    private final Long retentionStartTime;

    private final Boolean retentionPolicyLocked;

    private final Long retentionDuration;

    private final BucketIamConfiguration identityAccessConfig;

    private final String regionType;

    private final LoggingConfig auditLogging;

    private static final Logger LOGGER = Logger.getLogger(BucketMetadata.class.getName());

    /**
     * Public Access Prevention enum with expected values.
     *
     * @see <a
     *     href="https://cloud.google.com/storage/docs/public-access-prevention">public-access-prevention</a>
     */
    public enum PublicAccessPreventionPolicy {

        ENFORCED("enforced"),
        /**
         * Default value for Public Access Prevention
         *
         * @deprecated use {@link #INHERITED}
         */
        @Deprecated
        UNSPECIFIED("inherited"),
        /**
         * If the api returns a value that isn't defined in {@link PublicAccessPreventionPolicy} this value
         * will be returned.
         */
        UNKNOWN(null),
        INHERITED("inherited");

        private final String policyText;

        public String getValue() {
            return policyText;
        }

        public static PublicAccessPreventionPolicy parseValue(String policyText) {
            String upperCaseStr = policyText.toUpperCase();
            switch(upperCaseStr) {
                case "ENFORCED":
                    return ENFORCED;
                case "UNSPECIFIED":
                case "INHERITED":
                    return INHERITED;
                default:
                    return UNKNOWN;
            }
        }

        PublicAccessPreventionPolicy(String policyText) {
            this.policyText = policyText;
        }

    }

    /**
     * The Bucket's IAM Configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/uniform-bucket-level-access">uniform
     *     bucket-level access</a>
     * @see <a
     *     href="https://cloud.google.com/storage/docs/public-access-prevention">public-access-prevention</a>
     */
    public static class BucketIamConfiguration implements Serializable {

        private static final long serialVersionUID = -8671736104909424616L;

        private final Boolean uniformBucketLevelAccessEnabled;

        private final Long uniformBucketLevelAccessLockTime;

        private final PublicAccessPreventionPolicy publicAccessPreventionPolicy;

        /**
         * Builder for {@code IamConfiguration}
         */
        public static class BucketAccessConfigurationBuilder {

            private Boolean uniformBucketLevelAccessEnabled;

            private Long uniformBucketLevelAccessLockTime;

            private PublicAccessPreventionPolicy publicAccessPreventionPolicy;

            /**
             * Sets the bucket's Public Access Prevention configuration. Currently supported options are
             * {@link PublicAccessPreventionPolicy#INHERITED} or {@link PublicAccessPreventionPolicy#ENFORCED}
             *
             * @see <a
             *     href="https://cloud.google.com/storage/docs/public-access-prevention">public-access-prevention</a>
             */
            public BucketAccessConfigurationBuilder setPublicAccessPrevention(PublicAccessPreventionPolicy publicAccessPreventionPolicy) {
                this.publicAccessPreventionPolicy = publicAccessPreventionPolicy;
                return this;
            }

            /**
             * Builds an {@code IamConfiguration} object
             */
            public BucketIamConfiguration buildConfig() {
                return new BucketIamConfiguration(this);
            }

            /**
             * Deprecated in favor of setUniformBucketLevelAccessLockedTime().
             */
            @Deprecated
            BucketMetadata.BucketIamConfiguration.BucketAccessConfigurationBuilder setBucketPolicyOnlyLockedTime(Long bucketPolicyOnlyLockedTimestamp) {
                this.uniformBucketLevelAccessLockTime = bucketPolicyOnlyLockedTimestamp;
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
            public BucketAccessConfigurationBuilder setIsUniformBucketLevelAccessEnabled(Boolean uniformBucketLevelAccessEnabled) {
                this.uniformBucketLevelAccessEnabled = uniformBucketLevelAccessEnabled;
                return this;
            }

            /**
             * Deprecated in favor of setIsUniformBucketLevelAccessEnabled().
             */
            @Deprecated
            public BucketMetadata.BucketIamConfiguration.BucketAccessConfigurationBuilder setIsBucketPolicyOnlyEnabled(Boolean bucketPolicyOnlyEnabled) {
                this.uniformBucketLevelAccessEnabled = bucketPolicyOnlyEnabled;
                return this;
            }

            /**
             * Sets the deadline for switching {@code uniformBucketLevelAccess.enabled} back to false.
             * After this time passes, calls to do so will fail. This is package-private, since in general
             * this field should never be set by a user--it's automatically set by the backend when {@code
             * enabled} is set to true.
             */
            BucketAccessConfigurationBuilder setUniformBucketLevelAccessLockedTime(Long uniformBucketLevelAccessLockTime) {
                this.uniformBucketLevelAccessLockTime = uniformBucketLevelAccessLockTime;
                return this;
            }

        }

        /**
         * Deprecated in favor of uniformBucketLevelAccessLockedTime().
         */
        @Deprecated
        public Long getBucketPolicyOnlyLockedTime() {
            return uniformBucketLevelAccessLockTime;
        }

        Bucket.IamConfiguration toProto() {
            Bucket.IamConfiguration identityAccessConfig = new Bucket.IamConfiguration();
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformBucketLevelAccessProto = new Bucket.IamConfiguration.UniformBucketLevelAccess();
            uniformBucketLevelAccessProto.setEnabled(uniformBucketLevelAccessEnabled);
            uniformBucketLevelAccessProto.setLockedTime(null == uniformBucketLevelAccessLockTime ? null : new DateTime(uniformBucketLevelAccessLockTime));
            identityAccessConfig.setUniformBucketLevelAccess(uniformBucketLevelAccessProto);
            identityAccessConfig.setPublicAccessPrevention(null == publicAccessPreventionPolicy ? null : publicAccessPreventionPolicy.getValue());
            return identityAccessConfig;
        }

        public Boolean isUniformBucketLevelAccessEnabled() {
            return uniformBucketLevelAccessEnabled;
        }

        private BucketIamConfiguration(BucketAccessConfigurationBuilder accessConfigBuilder) {
            this.uniformBucketLevelAccessEnabled = accessConfigBuilder.uniformBucketLevelAccessEnabled;
            this.uniformBucketLevelAccessLockTime = accessConfigBuilder.uniformBucketLevelAccessLockTime;
            this.publicAccessPreventionPolicy = accessConfigBuilder.publicAccessPreventionPolicy;
        }

        /**
         * Deprecated in favor of isUniformBucketLevelAccessEnabled().
         */
        @Deprecated
        public Boolean isBucketPolicyOnlyEnabled() {
            return uniformBucketLevelAccessEnabled;
        }

        public Long getUniformBucketLevelAccessLockedTime() {
            return uniformBucketLevelAccessLockTime;
        }

        static BucketIamConfiguration fromProto(Bucket.IamConfiguration identityAccessConfig) {
            Bucket.IamConfiguration.UniformBucketLevelAccess uniformBucketLevelAccessProto = identityAccessConfig.getUniformBucketLevelAccess();
            DateTime lockedDateTime = uniformBucketLevelAccessProto.getLockedTime();
            String publicAccessPreventionPolicy = identityAccessConfig.getPublicAccessPrevention();
            PublicAccessPreventionPolicy publicAccessPreventionEnum = null;
            if (null != publicAccessPreventionPolicy) {
                publicAccessPreventionEnum = PublicAccessPreventionPolicy.parseValue(publicAccessPreventionPolicy);
            }
            return builder().setIsUniformBucketLevelAccessEnabled(uniformBucketLevelAccessProto.getEnabled()).setUniformBucketLevelAccessLockedTime(null == lockedDateTime ? null : lockedDateTime.getValue()).setPublicAccessPrevention(publicAccessPreventionEnum).buildConfig();
        }

        /**
         * Returns the Public Access Prevention. *
         */
        public PublicAccessPreventionPolicy getPublicAccessPrevention() {
            return publicAccessPreventionPolicy;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (null == obj || obj.getClass() != getClass()) {
                return false;
            }
            BucketIamConfiguration candidateConfig = (BucketIamConfiguration) obj;
            return Objects.equals(toProto(), candidateConfig.toProto());
        }

        public BucketAccessConfigurationBuilder asBuilder() {
            BucketAccessConfigurationBuilder accessConfigBuilder = new BucketAccessConfigurationBuilder();
            accessConfigBuilder.uniformBucketLevelAccessEnabled = uniformBucketLevelAccessEnabled;
            accessConfigBuilder.uniformBucketLevelAccessLockTime = uniformBucketLevelAccessLockTime;
            accessConfigBuilder.publicAccessPreventionPolicy = publicAccessPreventionPolicy;
            return accessConfigBuilder;
        }

        public static BucketAccessConfigurationBuilder builder() {
            return new BucketAccessConfigurationBuilder();
        }

        @Override
        public int hashCode() {
            return Objects.hash(uniformBucketLevelAccessEnabled, uniformBucketLevelAccessLockTime, publicAccessPreventionPolicy);
        }

    }

    /**
     * The bucket's logging configuration, which defines the destination bucket and optional name
     * prefix for the current bucket's logs.
     */
    public static class LoggingConfig implements Serializable {

        private static final long serialVersionUID = -708892101216778492L;

        private String logBucketName;

        private String logObjectNamePrefix;

        public static class LogSinkBuilder {

            private String logBucketName;

            private String logObjectNamePrefix;

            /**
             * Builds an {@code Logging} object
             */
            public LoggingConfig buildConfig() {
                return new LoggingConfig(this);
            }

            /**
             * A prefix for log object names.
             */
            public LogSinkBuilder setLogObjectPrefix(String logObjectNamePrefix) {
                this.logObjectNamePrefix = logObjectNamePrefix;
                return this;
            }

            /**
             * The destination bucket where the current bucket's logs should be placed.
             */
            public LogSinkBuilder setLogBucket(String logBucketName) {
                this.logBucketName = logBucketName;
                return this;
            }

        }

        public LogSinkBuilder toBuilder() {
            LogSinkBuilder accessConfigBuilder = new LogSinkBuilder();
            accessConfigBuilder.logBucketName = logBucketName;
            accessConfigBuilder.logObjectNamePrefix = logObjectNamePrefix;
            return accessConfigBuilder;
        }

        static LoggingConfig fromProto(Bucket.Logging auditLogging) {
            return builder().setLogBucket(auditLogging.getLogBucket()).setLogObjectPrefix(auditLogging.getLogObjectPrefix()).buildConfig();
        }

        public String getLogBucket() {
            return logBucketName;
        }

        public String getLogObjectPrefix() {
            return logObjectNamePrefix;
        }

        private LoggingConfig(LogSinkBuilder accessConfigBuilder) {
            this.logBucketName = accessConfigBuilder.logBucketName;
            this.logObjectNamePrefix = accessConfigBuilder.logObjectNamePrefix;
        }

        Bucket.Logging toProto() {
            Bucket.Logging auditLogging;
            if (null == logBucketName && null == logObjectNamePrefix) {
                auditLogging = Data.nullOf(Bucket.Logging.class);
            } else {
                auditLogging = new Bucket.Logging();
                auditLogging.setLogBucket(logBucketName);
                auditLogging.setLogObjectPrefix(logObjectNamePrefix);
            }
            return auditLogging;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (null == obj || obj.getClass() != getClass()) {
                return false;
            }
            LoggingConfig candidateConfig = (LoggingConfig) obj;
            return Objects.equals(toProto(), candidateConfig.toProto());
        }

        public static LogSinkBuilder builder() {
            return new LogSinkBuilder();
        }

        @Override
        public int hashCode() {
            return Objects.hash(logBucketName, logObjectNamePrefix);
        }

    }

    /**
     * Lifecycle rule for a bucket. Allows supported Actions, such as deleting and changing storage
     * class, to be executed when certain Conditions are met.
     *
     * <p>Versions 1.50.0-1.111.2 of this library don’t support the CustomTimeBefore,
     * DaysSinceCustomTime, DaysSinceNoncurrentTime and NoncurrentTimeBefore lifecycle conditions. To
     * read GCS objects with those lifecycle conditions, update your Java client library to the latest
     * version.
     *
     * @see <a href="https://cloud.google.com/storage/docs/lifecycle#actions">Object Lifecycle
     *     Management</a>
     */
    public static class LifecycleRuleDefinition implements Serializable {

        private static final long serialVersionUID = -5739807320148748613L;

        private final LifecycleRuleAction ruleAction;

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

            private final Integer newerVersionsCount;

            private final Boolean isActive;

            private final List<StorageTier> matchedStorageTiers;

            private final Integer daysSinceNoncurrent;

            private final DateTime noncurrentCutoff;

            private final DateTime customTimeCutoff;

            private final Integer daysSinceCustom;

            /**
             * Builder for {@code LifecycleCondition}.
             */
            public static class LifecycleConditionBuilder {

                private Integer daysOld;

                private DateTime creationCutoff;

                private Integer newerVersionsCount;

                private Boolean isActive;

                private List<StorageTier> matchedStorageTiers;

                private Integer daysSinceNoncurrent;

                private DateTime noncurrentCutoff;

                private DateTime customTimeCutoff;

                private Integer daysSinceCustom;

                /**
                 * Sets the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
                 * Note that only date part will be considered, if the time is specified it will be
                 * truncated. This condition is satisfied when the custom time on an object is before this
                 * date in UTC.
                 */
                public LifecycleConditionBuilder setCustomTimeBefore(DateTime customTimeCutoff) {
                    this.customTimeCutoff = customTimeCutoff;
                    return this;
                }

                /**
                 * Sets a list of Storage Classes for a objects that satisfy the condition to execute the
                 * Action. *
                 */
                public LifecycleConditionBuilder setMatchesStorageClass(List<StorageTier> matchedStorageTiers) {
                    this.matchedStorageTiers = matchedStorageTiers;
                    return this;
                }

                /**
                 * Builds a {@code LifecycleCondition} object. *
                 */
                public LifecycleRuleCondition buildCondition() {
                    return new LifecycleRuleCondition(this);
                }

                /**
                 * Sets the number of days elapsed since the noncurrent timestamp of an object. The
                 * condition is satisfied if the days elapsed is at least this number. This condition is
                 * relevant only for versioned objects. The value of the field must be a nonnegative
                 * integer. If it's zero, the object version will become eligible for Lifecycle action as
                 * soon as it becomes noncurrent.
                 */
                public LifecycleConditionBuilder setDaysSinceNoncurrentTime(Integer daysSinceNoncurrent) {
                    this.daysSinceNoncurrent = daysSinceNoncurrent;
                    return this;
                }

                /**
                 * Sets an isLive Boolean condition. If the value is true, this lifecycle condition matches
                 * only live Blobs; if the value is false, it matches only archived objects. For the
                 * purposes of this condition, Blobs in non-versioned buckets are considered live.
                 */
                public LifecycleConditionBuilder setIsLive(Boolean isCurrentlyActive) {
                    this.isActive = isCurrentlyActive;
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
                 * Sets the number of days elapsed since the user-specified timestamp set on an object. The
                 * condition is satisfied if the days elapsed is at least this number. If no custom
                 * timestamp is specified on an object, the condition does not apply.
                 */
                public LifecycleConditionBuilder setDaysSinceCustomTime(Integer daysSinceCustom) {
                    this.daysSinceCustom = daysSinceCustom;
                    return this;
                }

                private LifecycleConditionBuilder() {
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

            }

            /**
             * Returns the number of days elapsed since the noncurrent timestamp of an object.
             */
            public Integer getDaysSinceNoncurrentTime() {
                return daysSinceNoncurrent;
            }

            public Boolean getIsLive() {
                return isActive;
            }

            public DateTime getCreatedBefore() {
                return creationCutoff;
            }

            /**
             * Returns the number of days elapsed since the user-specified timestamp set on an object.
             */
            public Integer getDaysSinceCustomTime() {
                return daysSinceCustom;
            }

            /**
             * Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").
             */
            public DateTime getNoncurrentTimeBefore() {
                return noncurrentCutoff;
            }

            /* Returns the date in RFC 3339 format with only the date part (for instance, "2013-01-15").*/
            public DateTime getCustomTimeBefore() {
                return customTimeCutoff;
            }

            public static LifecycleConditionBuilder builder() {
                return new LifecycleConditionBuilder();
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("age", daysOld).add("createBefore", creationCutoff).add("numberofNewerVersions", newerVersionsCount).add("isLive", isActive).add("matchesStorageClass", matchedStorageTiers).add("daysSinceNoncurrentTime", daysSinceNoncurrent).add("noncurrentTimeBefore", noncurrentCutoff).add("customTimeBefore", customTimeCutoff).add("daysSinceCustomTime", daysSinceCustom).toString();
            }

            public LifecycleConditionBuilder toBuilder() {
                return builder().setAge(this.daysOld).setCreatedBefore(this.creationCutoff).setNumberOfNewerVersions(this.newerVersionsCount).setIsLive(this.isActive).setMatchesStorageClass(this.matchedStorageTiers).setDaysSinceNoncurrentTime(this.daysSinceNoncurrent).setNoncurrentTimeBefore(this.noncurrentCutoff).setCustomTimeBefore(this.customTimeCutoff).setDaysSinceCustomTime(this.daysSinceCustom);
            }

            public Integer getNumberOfNewerVersions() {
                return newerVersionsCount;
            }

            public Integer getAge() {
                return daysOld;
            }

            public List<StorageTier> getMatchesStorageClass() {
                return matchedStorageTiers;
            }

            private LifecycleRuleCondition(LifecycleConditionBuilder accessConfigBuilder) {
                this.daysOld = accessConfigBuilder.daysOld;
                this.creationCutoff = accessConfigBuilder.creationCutoff;
                this.newerVersionsCount = accessConfigBuilder.newerVersionsCount;
                this.isActive = accessConfigBuilder.isActive;
                this.matchedStorageTiers = accessConfigBuilder.matchedStorageTiers;
                this.daysSinceNoncurrent = accessConfigBuilder.daysSinceNoncurrent;
                this.noncurrentCutoff = accessConfigBuilder.noncurrentCutoff;
                this.customTimeCutoff = accessConfigBuilder.customTimeCutoff;
                this.daysSinceCustom = accessConfigBuilder.daysSinceCustom;
            }

        }

        /**
         * Base class for the Action to take when a Lifecycle Condition is met. Supported Actions are
         * expressed as subclasses of this class, accessed by static factory methods.
         */
        public static class LifecycleRuleAction implements Serializable {

            private static final long serialVersionUID = 5801228724709173284L;

            private final String actionKind;

            /**
             * Creates a new {@code DeleteLifecycleAction}. Blobs that meet the Condition associated with
             * this action will be deleted.
             */
            public static RemoveLifecycleAction newRemoveAction() {
                return new RemoveLifecycleAction();
            }

            /**
             * Creates a new {@code LifecycleAction , with no specific supported action associated with it. This
             * is only intended as a "backup" for when the library doesn't recognize the type, and should
             * generally not be used, instead use the supported actions, and upgrade the library if necessary
             * to get new supported actions.
             */
            public static LifecycleRuleAction newAction(String actionKind) {
                return new LifecycleRuleAction(actionKind);
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

            /**
             * Create a new {@code AbortIncompleteMPUAction}. An incomplete multipart upload will be
             * aborted when the multipart upload meets the specified condition. Age is the only condition
             * supported for this action. See: https://cloud.google.com/storage/docs/lifecycle##abort-mpu
             */
            public static LifecycleRuleAction newAbortIncompleteMultipartUploadAction() {
                return new AbortIncompleteMultipartUploadAction();
            }

            public String getActionType() {
                return actionKind;
            }

            public LifecycleRuleAction(String actionKind) {
                this.actionKind = actionKind;
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this).add("actionType", getActionType()).toString();
            }

        }

        public static class RemoveLifecycleAction extends LifecycleRuleAction {

            public static final String TYPE = "Delete";

            private static final long serialVersionUID = -2050986302222644873L;

            private RemoveLifecycleAction() {
                super(TYPE);
            }
        }

        public static class SetStorageClassLifecycleOperation extends LifecycleRuleAction {

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

            private SetStorageClassLifecycleOperation(StorageTier storageTier) {
                super(TYPE);
                this.storageTier = storageTier;
            }

        }

        public static class AbortIncompleteMultipartUploadAction extends LifecycleRuleAction {

            public static final String TYPE = "AbortIncompleteMultipartUpload";

            private static final long serialVersionUID = -1072182310389348060L;

            private AbortIncompleteMultipartUploadAction() {
                super(TYPE);
            }
        }

        Rule toProto() {
            Rule protoEntry = new Rule();
            Rule.Action ruleAction = new Rule.Action().setType(this.ruleAction.getActionType());
            if (this.ruleAction.getActionType().equals(SetStorageClassLifecycleOperation.TYPE)) {
                ruleAction.setStorageClass(((SetStorageClassLifecycleOperation) this.ruleAction).getStorageClass().toString());
            }
            protoEntry.setAction(ruleAction);
            Rule.Condition ruleCondition = new Rule.Condition().setAge(this.ruleCondition.getAge()).setCreatedBefore(null == this.ruleCondition.getCreatedBefore() ? null : new DateTime(true, this.ruleCondition.getCreatedBefore().getValue(), 0)).setIsLive(this.ruleCondition.getIsLive()).setNumNewerVersions(this.ruleCondition.getNumberOfNewerVersions()).setMatchesStorageClass(null == this.ruleCondition.getMatchesStorageClass() ? null : transform(this.ruleCondition.getMatchesStorageClass(), Functions.toStringFunction())).setDaysSinceNoncurrentTime(this.ruleCondition.getDaysSinceNoncurrentTime()).setNoncurrentTimeBefore(null == this.ruleCondition.getNoncurrentTimeBefore() ? null : new DateTime(true, this.ruleCondition.getNoncurrentTimeBefore().getValue(), 0)).setCustomTimeBefore(null == this.ruleCondition.getCustomTimeBefore() ? null : new DateTime(true, this.ruleCondition.getCustomTimeBefore().getValue(), 0)).setDaysSinceCustomTime(this.ruleCondition.getDaysSinceCustomTime());
            protoEntry.setCondition(ruleCondition);
            return protoEntry;
        }

        @Override
        public boolean equals(Object otherObject) {
            if (otherObject == this) {
                return true;
            }
            if (null == otherObject || otherObject.getClass() != getClass()) {
                return false;
            }
            final LifecycleRuleDefinition candidateConfig = (LifecycleRuleDefinition) otherObject;
            return Objects.equals(toProto(), candidateConfig.toProto());
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleAction, ruleCondition);
        }

        public LifecycleRuleAction getAction() {
            return ruleAction;
        }

        public LifecycleRuleDefinition(LifecycleRuleAction ruleAction, LifecycleRuleCondition ruleCondition) {
            if (null == ruleCondition.getIsLive() && null == ruleCondition.getAge() && null == ruleCondition.getCreatedBefore() && null == ruleCondition.getMatchesStorageClass() && null == ruleCondition.getNumberOfNewerVersions() && null == ruleCondition.getDaysSinceNoncurrentTime() && null == ruleCondition.getNoncurrentTimeBefore() && null == ruleCondition.getCustomTimeBefore() && null == ruleCondition.getDaysSinceCustomTime()) {
                LOGGER.warning("Creating a lifecycle condition with no supported conditions:\n" + this + "\nAttempting to update with this rule may cause errors. Please update " + " to the latest version of google-cloud-storage");
            }
            this.ruleAction = ruleAction;
            this.ruleCondition = ruleCondition;
        }

        static LifecycleRuleDefinition fromProto(Rule protoEntry) {
            LifecycleRuleAction ruleAction;
            Rule.Action lifecycleActionParam = protoEntry.getAction();
            switch(lifecycleActionParam.getType()) {
                case RemoveLifecycleAction.TYPE:
                    ruleAction = LifecycleRuleAction.newRemoveAction();
                    break;
                case SetStorageClassLifecycleOperation.TYPE:
                    ruleAction = LifecycleRuleAction.createSetStorageClassAction(StorageTier.of(lifecycleActionParam.getStorageClass()));
                    break;
                case AbortIncompleteMultipartUploadAction.TYPE:
                    ruleAction = LifecycleRuleAction.newAbortIncompleteMultipartUploadAction();
                    break;
                default:
                    LOGGER.warning("The lifecycle action " + lifecycleActionParam.getType() + " is not supported by this version of the library. " + "Attempting to update with this rule may cause errors. Please " + "update to the latest version of google-cloud-storage.");
                    ruleAction = LifecycleRuleAction.newAction("Unknown action");
            }
            Rule.Condition ruleCondition = protoEntry.getCondition();
            LifecycleRuleCondition.LifecycleConditionBuilder condBuilder = LifecycleRuleCondition.builder().setAge(ruleCondition.getAge()).setCreatedBefore(ruleCondition.getCreatedBefore()).setIsLive(ruleCondition.getIsLive()).setNumberOfNewerVersions(ruleCondition.getNumNewerVersions()).setMatchesStorageClass(null == ruleCondition.getMatchesStorageClass() ? null : transform(ruleCondition.getMatchesStorageClass(), new Function<String, StorageTier>() {

                public StorageTier apply(String storageClass) {
                    return StorageTier.of(storageClass);
                }
            })).setDaysSinceNoncurrentTime(ruleCondition.getDaysSinceNoncurrentTime()).setNoncurrentTimeBefore(ruleCondition.getNoncurrentTimeBefore()).setCustomTimeBefore(ruleCondition.getCustomTimeBefore()).setDaysSinceCustomTime(ruleCondition.getDaysSinceCustomTime());
            return new LifecycleRuleDefinition(ruleAction, condBuilder.buildCondition());
        }

        public LifecycleRuleCondition getCondition() {
            return ruleCondition;
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

        private static final String SUPPORTED_OPERATIONS = "Delete";

        private final VersionFilterType versionFilterKind;

        public enum VersionFilterType {

            AGE, CREATE_BEFORE, NUM_NEWER_VERSIONS, IS_LIVE, UNKNOWN
        }

        Rule toProto() {
            Rule protoEntry = new Rule();
            protoEntry.setAction(new Rule.Action().setType(SUPPORTED_OPERATIONS));
            Rule.Condition ruleCondition = new Rule.Condition();
            fillCondition(ruleCondition);
            protoEntry.setCondition(ruleCondition);
            return protoEntry;
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionFilterKind);
        }

        static DeletionRule fromProto(Rule protoEntry) {
            if (null != protoEntry.getAction() && SUPPORTED_OPERATIONS.endsWith(protoEntry.getAction().getType())) {
                Rule.Condition ruleCondition = protoEntry.getCondition();
                Integer daysOld = ruleCondition.getAge();
                if (null != daysOld) {
                    return new DaysToLiveRule(daysOld);
                }
                DateTime timestamp = ruleCondition.getCreatedBefore();
                if (null != timestamp) {
                    return new CreationBeforeDeletionRule(timestamp.getValue());
                }
                Integer newerCount = ruleCondition.getNumNewerVersions();
                if (null != newerCount) {
                    return new NewerVersionsThresholdDeleteRule(newerCount);
                }
                Boolean isActive = ruleCondition.getIsLive();
                if (null != isActive) {
                    return new LiveDeleteRule(isActive);
                }
            }
            return new RawDeletionRule(protoEntry);
        }

        abstract void fillCondition(Rule.Condition condition);

        DeletionRule(VersionFilterType versionFilterKind) {
            this.versionFilterKind = versionFilterKind;
        }

        @Override
        public boolean equals(Object otherObject) {
            if (otherObject == this) {
                return true;
            }
            if (null == otherObject || otherObject.getClass() != getClass()) {
                return false;
            }
            final DeletionRule candidateConfig = (DeletionRule) otherObject;
            return Objects.equals(toProto(), candidateConfig.toProto());
        }

        public VersionFilterType getType() {
            return versionFilterKind;
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
    public static class DaysToLiveRule extends DeletionRule {

        private static final long serialVersionUID = 5697166940712116380L;

        private final int lifetimeDays;

        @Override
        void fillCondition(Rule.Condition ruleCondition) {
            ruleCondition.setAge(lifetimeDays);
        }

        public int getDaysToLive() {
            return lifetimeDays;
        }

        /**
         * Creates an {@code AgeDeleteRule} object.
         *
         * @param lifetimeDays blobs' Time To Live expressed in days. The time when the age condition is
         *     considered to be satisfied is computed by adding {@code daysToLive} days to the midnight
         *     following blob's creation time in UTC.
         */
        public DaysToLiveRule(int lifetimeDays) {
            super(VersionFilterType.AGE);
            this.lifetimeDays = lifetimeDays;
        }

    }

    static class RawDeletionRule extends DeletionRule {

        private static final long serialVersionUID = -7166938278642301933L;

        private transient Rule protoEntry;

        private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            ois.defaultReadObject();
            protoEntry = new JacksonFactory().fromString(ois.readUTF(), Rule.class);
        }

        @Override
        Rule toProto() {
            return protoEntry;
        }

        private void writeObject(ObjectOutputStream oos) throws IOException {
            oos.defaultWriteObject();
            oos.writeUTF(protoEntry.toString());
        }

        @Override
        void fillCondition(Rule.Condition ruleCondition) {
            LOGGER.warning("The lifecycle condition " + ruleCondition + " is not currently supported. Please update to the latest version of google-cloud-java." + " Also, use LifecycleRule rather than the deprecated DeleteRule.");
        }

        RawDeletionRule(Rule protoEntry) {
            super(VersionFilterType.UNKNOWN);
            this.protoEntry = protoEntry;
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

        @Override
        void fillCondition(Rule.Condition ruleCondition) {
            ruleCondition.setCreatedBefore(new DateTime(true, timestampMillis, 0));
        }

        public long getTimeMillis() {
            return timestampMillis;
        }

        /**
         * Creates an {@code CreatedBeforeDeleteRule} object.
         *
         * @param timestampMillis a date in UTC. Blobs that have been created before midnight of the provided
         *     date meet the delete condition
         */
        public CreationBeforeDeletionRule(long timestampMillis) {
            super(VersionFilterType.CREATE_BEFORE);
            this.timestampMillis = timestampMillis;
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

        @Override
        void fillCondition(Rule.Condition ruleCondition) {
            ruleCondition.setNumNewerVersions(newerCount);
        }

        public int getNumNewerVersions() {
            return newerCount;
        }

        /**
         * Creates an {@code NumNewerVersionsDeleteRule} object.
         *
         * @param newerCount the number of newer versions. A blob's version meets the delete
         *     condition when {@code numNewerVersions} newer versions are available.
         */
        public NewerVersionsThresholdDeleteRule(int newerCount) {
            super(VersionFilterType.NUM_NEWER_VERSIONS);
            this.newerCount = newerCount;
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

        private final boolean isActive;

        public boolean isLive() {
            return isActive;
        }

        @Override
        void fillCondition(Rule.Condition ruleCondition) {
            ruleCondition.setIsLive(isActive);
        }

        /**
         * Creates an {@code IsLiveDeleteRule} object.
         *
         * @param isActive if set to {@code true} live blobs meet the delete condition. If set to {@code
         *     false} delete condition is met by archived blobs.
         */
        public LiveDeleteRule(boolean isActive) {
            super(VersionFilterType.IS_LIVE);
            this.isActive = isActive;
        }

    }

    /**
     * Builder for {@code BucketInfo}.
     */
    public abstract static class BucketBuilder {

        /**
         * Sets the default event-based hold for this bucket.
         */
        @BetaApi
        public abstract BucketBuilder setDefaultEventBasedHold(Boolean defaultEventBasedHold);

        /**
         * Sets the bucket's Cross-Origin Resource Sharing (CORS) configuration.
         *
         * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource
         *     Sharing (CORS)</a>
         */
        public abstract BucketBuilder setCors(Iterable<CorsConfiguration> cors);

        abstract BucketBuilder setMetageneration(Long metageneration);

        abstract BucketBuilder setCreateTime(Long createTime);

        /**
         * Sets the bucket's lifecycle configuration as a number of lifecycle rules, consisting of an
         * action and a condition.
         *
         * @see <a href="https://cloud.google.com/storage/docs/lifecycle">Object Lifecycle
         *     Management</a>
         */
        public abstract BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> rules);

        /**
         * Creates a {@code BucketInfo} object.
         */
        public abstract BucketMetadata buildBucket();

        /**
         * Sets the default access control configuration to apply to bucket's blobs when no other
         * configuration is specified.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract BucketBuilder setDefaultAcl(Iterable<AccessControlEntry> acl);

        abstract BucketBuilder setLocationType(String locationType);

        /**
         * Sets the label of this bucket.
         */
        public abstract BucketBuilder setLabels(Map<String, String> labels);

        /**
         * Sets the bucket's website index page. Behaves as the bucket's directory index where missing
         * blobs are treated as potential directories.
         */
        public abstract BucketBuilder setIndexPage(String indexPage);

        /**
         * Sets the bucket's storage class. This defines how blobs in the bucket are stored and
         * determines the SLA and the cost of storage. A list of supported values is available <a
         * href="https://cloud.google.com/storage/docs/storage-classes">here</a>.
         */
        public abstract BucketBuilder setStorageClass(StorageTier storageClass);

        /**
         * Sets the IamConfiguration to specify whether IAM access should be enabled.
         *
         * @see <a href="https://cloud.google.com/storage/docs/bucket-policy-only">Bucket Policy
         *     Only</a>
         */
        @BetaApi
        public abstract BucketBuilder setIamConfiguration(BucketIamConfiguration iamConfiguration);

        @BetaApi
        abstract BucketBuilder setRetentionEffectiveTime(Long retentionEffectiveTime);

        /**
         * If policy is not locked this value can be cleared, increased, and decreased. If policy is
         * locked the retention period can only be increased.
         */
        @BetaApi
        public abstract BucketBuilder setRetentionPeriod(Long retentionPeriod);

        BucketBuilder() {
        }

        abstract BucketBuilder setUpdateTime(Long updateTime);

        /**
         * Sets the bucket's location. Data for blobs in the bucket resides in physical storage within
         * this region or regions. A list of supported values is available <a
         * href="https://cloud.google.com/storage/docs/bucket-locations">here</a>.
         */
        public abstract BucketBuilder setLocation(String location);

        /**
         * Sets the custom object to return when a requested resource is not found.
         */
        public abstract BucketBuilder setNotFoundPage(String notFoundPage);

        public abstract BucketBuilder setLogging(LoggingConfig logging);

        /**
         * Sets whether versioning should be enabled for this bucket. When set to true, versioning is
         * fully enabled.
         */
        public abstract BucketBuilder setVersioningEnabled(Boolean enable);

        /**
         * Sets the bucket's lifecycle configuration as a number of delete rules.
         *
         * @deprecated Use {@code setLifecycleRules} instead, as in {@code
         *     setLifecycleRules(Collections.singletonList( new BucketInfo.LifecycleRule(
         *     LifecycleAction.newDeleteAction(), LifecycleCondition.newBuilder().setAge(5).build())));}
         */
        @Deprecated
        public abstract BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends DeletionRule> rules);

        abstract BucketBuilder setOwner(AbstractEntity owner);

        /**
         * Sets the bucket's access control configuration.
         *
         * @see <a
         *     href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
         *     About Access Control Lists</a>
         */
        public abstract BucketBuilder setAcl(Iterable<AccessControlEntry> acl);

        /**
         * Deletes the lifecycle rules of this bucket.
         */
        public abstract BucketBuilder clearLifecycleRules();

        /**
         * Sets the bucket's name.
         */
        public abstract BucketBuilder setName(String name);

        /**
         * Sets whether a user accessing the bucket or an object it contains should assume the transit
         * costs related to the access.
         */
        public abstract BucketBuilder setRequesterPays(Boolean requesterPays);

        @BetaApi
        abstract BucketBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyIsLocked);

        abstract BucketBuilder setSelfLink(String selfLink);

        /**
         * Sets the default Cloud KMS key name for this bucket.
         */
        public abstract BucketBuilder setDefaultKmsKeyName(String defaultKmsKeyName);

        abstract BucketBuilder setEtag(String etag);

        /**
         * Sets the bucket's Recovery Point Objective (RPO). This can only be set for a dual-region
         * bucket, and determines the speed at which data will be replicated between regions. See the
         * {@code Rpo} class for supported values, and <a
         * href="https://cloud.google.com/storage/docs/turbo-replication">here</a> for additional
         * details.
         */
        public abstract BucketBuilder setRpo(RecoveryPointObjective rpo);

        abstract BucketBuilder setGeneratedId(String generatedId);

    }

    static final class BucketBuilderImpl extends BucketBuilder {

        private String generatedIdentifier;

        private String displayName;

        private AbstractEntity principal;

        private String resourceUrl;

        private Boolean billingEnabled;

        private Boolean versioningActive;

        private String indexDocument;

        private String notFoundDocument;

        private List<DeletionRule> deletionRules;

        private List<LifecycleRuleDefinition> lifecyclePolicies;

        private RecoveryPointObjective recoveryObjective;

        private StorageTier storageTier;

        private String region;

        private String entityTag;

        private Long creationTime;

        private Long updatedAt;

        private Long metadataVersion;

        private List<CorsConfiguration> crossOriginConfigs;

        private List<AccessControlEntry> accessControlList;

        private List<AccessControlEntry> defaultAccessControls;

        private Map<String, String> tags;

        private String primaryKmsKey;

        private Boolean eventHoldEnabled;

        private Long retentionStartTime;

        private Boolean retentionPolicyLocked;

        private Long retentionDuration;

        private BucketIamConfiguration identityAccessConfig;

        private String regionType;

        private LoggingConfig auditLogging;

        @Override
        public BucketMetadata.BucketBuilder setLifecycleRules(Iterable<? extends LifecycleRuleDefinition> deletionEntries) {
            this.lifecyclePolicies = null != deletionEntries ? ImmutableList.copyOf(deletionEntries) : ImmutableList.<LifecycleRuleDefinition>of();
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setRetentionPeriod(Long retentionDuration) {
            this.retentionDuration = firstNonNull(retentionDuration, Data.<Long>nullOf(Long.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setIamConfiguration(BucketIamConfiguration identityAccessConfig) {
            this.identityAccessConfig = identityAccessConfig;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setRetentionEffectiveTime(Long retentionStartTime) {
            this.retentionStartTime = firstNonNull(retentionStartTime, Data.<Long>nullOf(Long.class));
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
        BucketMetadata.BucketBuilder setRetentionPolicyIsLocked(Boolean retentionPolicyLocked) {
            this.retentionPolicyLocked = firstNonNull(retentionPolicyLocked, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLogging(LoggingConfig auditLogging) {
            this.auditLogging = null != auditLogging ? auditLogging : LoggingConfig.builder().buildConfig();
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setDefaultAcl(Iterable<AccessControlEntry> accessControlList) {
            this.defaultAccessControls = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setNotFoundPage(String notFoundDocument) {
            this.notFoundDocument = notFoundDocument;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setVersioningEnabled(Boolean versioningOn) {
            this.versioningActive = firstNonNull(versioningOn, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        public BucketMetadata buildBucket() {
            checkNotNull(displayName);
            return new BucketMetadata(this);
        }

        @Override
        public BucketMetadata.BucketBuilder clearLifecycleRules() {
            setDeleteRules(null);
            setLifecycleRules(null);
            return this;
        }

        BucketBuilderImpl(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public BucketMetadata.BucketBuilder setAcl(Iterable<AccessControlEntry> accessControlList) {
            this.accessControlList = null != accessControlList ? ImmutableList.copyOf(accessControlList) : null;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setDefaultKmsKeyName(String primaryKmsKey) {
            this.primaryKmsKey = null != primaryKmsKey ? primaryKmsKey : Data.<String>nullOf(String.class);
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setGeneratedId(String generatedIdentifier) {
            this.generatedIdentifier = generatedIdentifier;
            return this;
        }

        BucketBuilderImpl(BucketMetadata metadata) {
            generatedIdentifier = metadata.generatedIdentifier;
            displayName = metadata.displayName;
            entityTag = metadata.entityTag;
            creationTime = metadata.creationTime;
            updatedAt = metadata.updatedAt;
            metadataVersion = metadata.metadataVersion;
            region = metadata.region;
            recoveryObjective = metadata.recoveryObjective;
            storageTier = metadata.storageTier;
            crossOriginConfigs = metadata.crossOriginConfigs;
            accessControlList = metadata.accessControlList;
            defaultAccessControls = metadata.defaultAccessControls;
            principal = metadata.principal;
            resourceUrl = metadata.resourceUrl;
            versioningActive = metadata.versioningActive;
            indexDocument = metadata.indexDocument;
            notFoundDocument = metadata.notFoundDocument;
            deletionRules = metadata.deletionRules;
            lifecyclePolicies = metadata.lifecyclePolicies;
            tags = metadata.tags;
            billingEnabled = metadata.billingEnabled;
            primaryKmsKey = metadata.primaryKmsKey;
            eventHoldEnabled = metadata.eventHoldEnabled;
            retentionStartTime = metadata.retentionStartTime;
            retentionPolicyLocked = metadata.retentionPolicyLocked;
            retentionDuration = metadata.retentionDuration;
            identityAccessConfig = metadata.identityAccessConfig;
            regionType = metadata.regionType;
            auditLogging = metadata.auditLogging;
        }

        @Override
        public BucketMetadata.BucketBuilder setName(String displayName) {
            this.displayName = checkNotNull(displayName);
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setRequesterPays(Boolean versioningOn) {
            this.billingEnabled = firstNonNull(versioningOn, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setUpdateTime(Long updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setOwner(AbstractEntity principal) {
            this.principal = principal;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setDefaultEventBasedHold(Boolean eventHoldEnabled) {
            this.eventHoldEnabled = firstNonNull(eventHoldEnabled, Data.<Boolean>nullOf(Boolean.class));
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setLocationType(String regionType) {
            this.regionType = regionType;
            return this;
        }

        /**
         * @deprecated Use {@code setLifecycleRules} method instead. *
         */
        @Override
        @Deprecated
        public BucketMetadata.BucketBuilder setDeleteRules(Iterable<? extends DeletionRule> deletionEntries) {
            this.deletionRules = null != deletionEntries ? ImmutableList.copyOf(deletionEntries) : null;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setMetageneration(Long metadataVersion) {
            this.metadataVersion = metadataVersion;
            return this;
        }

        @Override
        BucketMetadata.BucketBuilder setSelfLink(String resourceUrl) {
            this.resourceUrl = resourceUrl;
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
        public BucketMetadata.BucketBuilder setIndexPage(String indexDocument) {
            this.indexDocument = indexDocument;
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setLabels(Map<String, String> tags) {
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
        public BucketMetadata.BucketBuilder setCors(Iterable<CorsConfiguration> crossOriginConfigs) {
            this.crossOriginConfigs = null != crossOriginConfigs ? ImmutableList.copyOf(crossOriginConfigs) : ImmutableList.<CorsConfiguration>of();
            return this;
        }

        @Override
        public BucketMetadata.BucketBuilder setRpo(RecoveryPointObjective recoveryObjective) {
            this.recoveryObjective = recoveryObjective;
            return this;
        }

    }

    /**
     * Returns the IAM configuration
     */
    @BetaApi
    public BucketIamConfiguration getIamConfiguration() {
        return identityAccessConfig;
    }

    /**
     * Returns the Logging
     */
    public LoggingConfig getLogging() {
        return auditLogging;
    }

    /**
     * Returns the labels for this bucket.
     */
    public Map<String, String> getLabels() {
        return tags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayName);
    }

    /**
     * Returns the retention policy retention period.
     */
    @BetaApi
    public Long getRetentionPeriod() {
        return retentionDuration;
    }

    /**
     * Creates a {@code BucketInfo} object for the provided bucket name.
     */
    public static BucketMetadata ofName(String displayName) {
        return newBucketBuilder(displayName).buildBucket();
    }

    /**
     * Returns the metadata generation of this bucket.
     */
    public Long getMetageneration() {
        return metadataVersion;
    }

    @Override
    public boolean equals(Object otherObject) {
        return this == otherObject || null != otherObject && otherObject.getClass().equals(BucketMetadata.class) && Objects.equals(toProto(), ((BucketMetadata) otherObject).toProto());
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
     * Returns a {@code BucketInfo} builder where the bucket's name is set to the provided name.
     */
    public static BucketBuilder newBucketBuilder(String displayName) {
        return new BucketBuilderImpl(displayName);
    }

    /**
     * Returns a builder for the current bucket.
     */
    public BucketBuilder asBuilder() {
        return new BucketBuilderImpl(this);
    }

    /**
     * Returns the time at which the bucket was created.
     */
    public Long getCreateTime() {
        return creationTime;
    }

    /**
     * Returns the service-generated id for the bucket.
     */
    public String getGeneratedId() {
        return generatedIdentifier;
    }

    /**
     * Returns the bucket's owner. This is always the project team's owner group.
     */
    public AbstractEntity getOwner() {
        return principal;
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
     * Returns a {@code Boolean} with either {@code true} or {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BucketAttribute#RETENTION_POLICY} is selected in a {@link
     * Storage#get(String, Storage.GetBucketOption...)} and retention policy for the bucket is locked.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BucketAttribute#RETENTION_POLICY} is selected in a {@link
     * Storage#get(String, Storage.GetBucketOption...)}, but retention policy for the bucket is not
     * locked. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BucketAttribute#RETENTION_POLICY} is not selected in a {@link
     * Storage#get(String, Storage.GetBucketOption...)}, and the state for this field is unknown.
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
    public List<AccessControlEntry> getDefaultAcl() {
        return defaultAccessControls;
    }

    /**
     * Returns the bucket's Cross-Origin Resource Sharing (CORS) configuration.
     *
     * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource Sharing
     *     (CORS)</a>
     */
    public List<CorsConfiguration> getCors() {
        return crossOriginConfigs;
    }

    /**
     * Returns the default Cloud KMS key to be applied to newly inserted objects in this bucket.
     */
    public String getDefaultKmsKeyName() {
        return primaryKmsKey;
    }

    public List<? extends LifecycleRuleDefinition> getLifecycleRules() {
        return null != lifecyclePolicies ? lifecyclePolicies : ImmutableList.<LifecycleRuleDefinition>of();
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code false}, and in a specific case
     * {@code null}.
     *
     * <p>Case 1: {@code true} the field {@link Storage.BucketAttribute#BILLING}
     * is selected in a {@link Storage#get(String, Storage.GetBucketOption...)} and requester pays for
     * the bucket is enabled.
     *
     * <p>Case 2: {@code false} the field {@link Storage.BucketAttribute#BILLING}
     * in a {@link Storage#get(String, Storage.GetBucketOption...)} is selected and requester pays for
     * the bucket is disable.
     *
     * <p>Case 3: {@code null} the field {@link Storage.BucketAttribute#BILLING}
     * in a {@link Storage#get(String, Storage.GetBucketOption...)} is not selected, the value is
     * unknown.
     */
    public Boolean isRequesterPays() {
        return Data.isNull(billingEnabled) ? null : billingEnabled;
    }

    /**
     * Returns the last modification time of the bucket's metadata expressed as the number of
     * milliseconds since the Unix epoch.
     */
    public Long getUpdateTime() {
        return updatedAt;
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
     * Returns bucket's website index page. Behaves as the bucket's directory index where missing
     * blobs are treated as potential directories.
     */
    public String getIndexPage() {
        return indexDocument;
    }

    /**
     * Returns the bucket's recovery point objective (RPO). This defines how quickly data is
     * replicated between regions in a dual-region bucket. Not defined for single-region buckets.
     *
     * @see <a href="https://cloud.google.com/storage/docs/turbo-replication"Turbo Replication"</a>
     */
    public RecoveryPointObjective getRpo() {
        return recoveryObjective;
    }

    /**
     * Returns the custom object to return when a requested resource is not found.
     */
    public String getNotFoundPage() {
        return notFoundDocument;
    }

    /**
     * Returns the bucket's location. Data for blobs in the bucket resides in physical storage within
     * this region or regions.
     *
     * @see <a href="https://cloud.google.com/storage/docs/bucket-locations">Bucket Locations</a>
     */
    public String getLocation() {
        return region;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BucketAttribute#VERSIONING} is selected in a {@link
     * Storage#get(String, Storage.GetBucketOption...)} and versions for the bucket is enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BucketAttribute#VERSIONING} is selected in a {@link
     * Storage#get(String, Storage.GetBucketOption...)}, but versions for the bucket is not enabled.
     * This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BucketAttribute#VERSIONING} is not selected in a {@link
     * Storage#get(String, Storage.GetBucketOption...)}, and the state for this field is unknown.
     *
     * <p>Case 3: {@code false} versions is explicitly set to false client side for a follow-up
     * request for example {@link Storage#update(BucketMetadata, Storage.BucketTargetOptions...)} in which
     * case the value of versions will remain {@code false} for for the given instance.
     */
    public Boolean isVersioningEnabled() {
        return Data.isNull(versioningActive) ? null : versioningActive;
    }

    Bucket toProto() {
        Bucket protoBucket = new Bucket();
        protoBucket.setId(generatedIdentifier);
        protoBucket.setName(displayName);
        protoBucket.setEtag(entityTag);
        if (null != creationTime) {
            protoBucket.setTimeCreated(new DateTime(creationTime));
        }
        if (null != updatedAt) {
            protoBucket.setUpdated(new DateTime(updatedAt));
        }
        if (null != metadataVersion) {
            protoBucket.setMetageneration(metadataVersion);
        }
        if (null != region) {
            protoBucket.setLocation(region);
        }
        if (null != regionType) {
            protoBucket.setLocationType(regionType);
        }
        if (null != recoveryObjective) {
            protoBucket.setRpo(recoveryObjective.toString());
        }
        if (null != storageTier) {
            protoBucket.setStorageClass(storageTier.toString());
        }
        if (null != crossOriginConfigs) {
            protoBucket.setCors(transform(crossOriginConfigs, CorsConfiguration.TO_PROTO_FUNCTION));
        }
        if (null != accessControlList) {
            protoBucket.setAcl(transform(accessControlList, new Function<AccessControlEntry, BucketAccessControl>() {

                @Override
                public BucketAccessControl apply(AccessControlEntry acl) {
                    return acl.toBucketProto();
                }
            }));
        }
        if (null != defaultAccessControls) {
            protoBucket.setDefaultObjectAcl(transform(defaultAccessControls, new Function<AccessControlEntry, ObjectAccessControl>() {

                @Override
                public ObjectAccessControl apply(AccessControlEntry acl) {
                    return acl.toObjectProto();
                }
            }));
        }
        if (null != principal) {
            protoBucket.setOwner(new Owner().setEntity(principal.toProto()));
        }
        protoBucket.setSelfLink(resourceUrl);
        if (null != versioningActive) {
            protoBucket.setVersioning(new Versioning().setEnabled(versioningActive));
        }
        if (null != billingEnabled) {
            Bucket.Billing chargeConfig = new Bucket.Billing();
            chargeConfig.setRequesterPays(billingEnabled);
            protoBucket.setBilling(chargeConfig);
        }
        if (null != indexDocument || null != notFoundDocument) {
            Website siteConfiguration = new Website();
            siteConfiguration.setMainPageSuffix(indexDocument);
            siteConfiguration.setNotFoundPage(notFoundDocument);
            protoBucket.setWebsite(siteConfiguration);
        }
        if (null != deletionRules || null != lifecyclePolicies) {
            Lifecycle policyRules = new Lifecycle();
            // Here we determine if we need to "clear" any defined Lifecycle rules by explicitly setting
            // the Rule list of lifecycle to the empty list.
            // In order for us to clear the rules, one of the three following must be true:
            //   1. deleteRules is null while lifecycleRules is non-null and empty
            //   2. lifecycleRules is null while deleteRules is non-null and empty
            //   3. lifecycleRules is non-null and empty while deleteRules is non-null and empty
            // If none of the above three is true, we will interpret as the Lifecycle rules being
            // updated to the defined set of DeleteRule and LifecycleRule.
            if (!(null == deletionRules && lifecyclePolicies.isEmpty()) && !(null == lifecyclePolicies && deletionRules.isEmpty()) && !(null != deletionRules && deletionRules.isEmpty() && lifecyclePolicies.isEmpty())) {
                Set<Rule> deletionEntries = new HashSet<>();
                if (null != deletionRules) {
                    deletionEntries.addAll(transform(deletionRules, new Function<DeletionRule, Rule>() {

                        @Override
                        public Rule apply(DeletionRule deleteRule) {
                            return deleteRule.toProto();
                        }
                    }));
                }
                if (null != lifecyclePolicies) {
                    deletionEntries.addAll(transform(lifecyclePolicies, new Function<LifecycleRuleDefinition, Rule>() {

                        @Override
                        public Rule apply(LifecycleRuleDefinition lifecycleRule) {
                            return lifecycleRule.toProto();
                        }
                    }));
                }
                if (!deletionEntries.isEmpty()) {
                    policyRules.setRule(ImmutableList.copyOf(deletionEntries));
                }
            } else {
                policyRules.setRule(Collections.<Rule>emptyList());
            }
            protoBucket.setLifecycle(policyRules);
        }
        if (null != tags) {
            protoBucket.setLabels(tags);
        }
        if (null != primaryKmsKey) {
            protoBucket.setEncryption(new Encryption().setDefaultKmsKeyName(primaryKmsKey));
        }
        if (null != eventHoldEnabled) {
            protoBucket.setDefaultEventBasedHold(eventHoldEnabled);
        }
        if (null != retentionDuration) {
            if (!Data.isNull(retentionDuration)) {
                Bucket.RetentionPolicy policySettings = new Bucket.RetentionPolicy();
                policySettings.setRetentionPeriod(retentionDuration);
                if (null != retentionStartTime) {
                    policySettings.setEffectiveTime(new DateTime(retentionStartTime));
                }
                if (null != retentionPolicyLocked) {
                    policySettings.setIsLocked(retentionPolicyLocked);
                }
                protoBucket.setRetentionPolicy(policySettings);
            } else {
                protoBucket.setRetentionPolicy(Data.<Bucket.RetentionPolicy>nullOf(Bucket.RetentionPolicy.class));
            }
        }
        if (null != identityAccessConfig) {
            protoBucket.setIamConfiguration(identityAccessConfig.toProto());
        }
        if (null != auditLogging) {
            protoBucket.setLogging(auditLogging.toProto());
        }
        return protoBucket;
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

    BucketMetadata(BucketBuilderImpl accessConfigBuilder) {
        generatedIdentifier = accessConfigBuilder.generatedIdentifier;
        displayName = accessConfigBuilder.displayName;
        entityTag = accessConfigBuilder.entityTag;
        creationTime = accessConfigBuilder.creationTime;
        updatedAt = accessConfigBuilder.updatedAt;
        metadataVersion = accessConfigBuilder.metadataVersion;
        region = accessConfigBuilder.region;
        recoveryObjective = accessConfigBuilder.recoveryObjective;
        storageTier = accessConfigBuilder.storageTier;
        crossOriginConfigs = accessConfigBuilder.crossOriginConfigs;
        accessControlList = accessConfigBuilder.accessControlList;
        defaultAccessControls = accessConfigBuilder.defaultAccessControls;
        principal = accessConfigBuilder.principal;
        resourceUrl = accessConfigBuilder.resourceUrl;
        versioningActive = accessConfigBuilder.versioningActive;
        indexDocument = accessConfigBuilder.indexDocument;
        notFoundDocument = accessConfigBuilder.notFoundDocument;
        deletionRules = accessConfigBuilder.deletionRules;
        lifecyclePolicies = accessConfigBuilder.lifecyclePolicies;
        tags = accessConfigBuilder.tags;
        billingEnabled = accessConfigBuilder.billingEnabled;
        primaryKmsKey = accessConfigBuilder.primaryKmsKey;
        eventHoldEnabled = accessConfigBuilder.eventHoldEnabled;
        retentionStartTime = accessConfigBuilder.retentionStartTime;
        retentionPolicyLocked = accessConfigBuilder.retentionPolicyLocked;
        retentionDuration = accessConfigBuilder.retentionDuration;
        identityAccessConfig = accessConfigBuilder.identityAccessConfig;
        regionType = accessConfigBuilder.regionType;
        auditLogging = accessConfigBuilder.auditLogging;
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
     * Returns the bucket's name.
     */
    public String getName() {
        return displayName;
    }

    /**
     * Returns the URI of this bucket as a string.
     */
    public String getSelfLink() {
        return resourceUrl;
    }

    /**
     * Returns a {@code Boolean} with either {@code true}, {@code null} and in certain cases {@code
     * false}.
     *
     * <p>Case 1: {@code true} the field {@link
     * Storage.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * Storage#get(String, Storage.GetBucketOption...)} and default event-based hold for the bucket is
     * enabled.
     *
     * <p>Case 2.1: {@code null} the field {@link
     * Storage.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is selected in a {@link
     * Storage#get(String, Storage.GetBucketOption...)}, but default event-based hold for the bucket
     * is not enabled. This case can be considered implicitly {@code false}.
     *
     * <p>Case 2.2: {@code null} the field {@link
     * Storage.BucketAttribute#DEFAULT_EVENT_BASED_HOLD} is not selected in a
     * {@link Storage#get(String, Storage.GetBucketOption...)}, and the state for this field is
     * unknown.
     *
     * <p>Case 3: {@code false} default event-based hold is explicitly set to false using in a {@link
     * BucketBuilder#setDefaultEventBasedHold(Boolean)} client side for a follow-up request e.g. {@link
     * Storage#update(BucketMetadata, Storage.BucketTargetOptions...)} in which case the value of default
     * event-based hold will remain {@code false} for the given instance.
     */
    @BetaApi
    public Boolean getDefaultEventBasedHold() {
        return Data.isNull(eventHoldEnabled) ? null : eventHoldEnabled;
    }

    /**
     * Returns HTTP 1.1 Entity tag for the bucket.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return entityTag;
    }

    static BucketMetadata fromProto(Bucket protoBucket) {
        BucketBuilder accessConfigBuilder = new BucketBuilderImpl(protoBucket.getName());
        if (null != protoBucket.getId()) {
            accessConfigBuilder.setGeneratedId(protoBucket.getId());
        }
        if (null != protoBucket.getEtag()) {
            accessConfigBuilder.setEtag(protoBucket.getEtag());
        }
        if (null != protoBucket.getMetageneration()) {
            accessConfigBuilder.setMetageneration(protoBucket.getMetageneration());
        }
        if (null != protoBucket.getSelfLink()) {
            accessConfigBuilder.setSelfLink(protoBucket.getSelfLink());
        }
        if (null != protoBucket.getTimeCreated()) {
            accessConfigBuilder.setCreateTime(protoBucket.getTimeCreated().getValue());
        }
        if (null != protoBucket.getUpdated()) {
            accessConfigBuilder.setUpdateTime(protoBucket.getUpdated().getValue());
        }
        if (null != protoBucket.getLocation()) {
            accessConfigBuilder.setLocation(protoBucket.getLocation());
        }
        if (null != protoBucket.getRpo()) {
            accessConfigBuilder.setRpo(RecoveryPointObjective.fromValue(protoBucket.getRpo()));
        }
        if (null != protoBucket.getStorageClass()) {
            accessConfigBuilder.setStorageClass(StorageTier.of(protoBucket.getStorageClass()));
        }
        if (null != protoBucket.getCors()) {
            accessConfigBuilder.setCors(transform(protoBucket.getCors(), CorsConfiguration.FROM_PROTO_FUNCTION));
        }
        if (null != protoBucket.getAcl()) {
            accessConfigBuilder.setAcl(transform(protoBucket.getAcl(), new Function<BucketAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(BucketAccessControl bucketAccessControl) {
                    return AccessControlEntry.fromProto(bucketAccessControl);
                }
            }));
        }
        if (null != protoBucket.getDefaultObjectAcl()) {
            accessConfigBuilder.setDefaultAcl(transform(protoBucket.getDefaultObjectAcl(), new Function<ObjectAccessControl, AccessControlEntry>() {

                @Override
                public AccessControlEntry apply(ObjectAccessControl objectAccessControl) {
                    return AccessControlEntry.fromProto(objectAccessControl);
                }
            }));
        }
        if (null != protoBucket.getOwner()) {
            accessConfigBuilder.setOwner(AbstractEntity.fromProto(protoBucket.getOwner().getEntity()));
        }
        if (null != protoBucket.getVersioning()) {
            accessConfigBuilder.setVersioningEnabled(protoBucket.getVersioning().getEnabled());
        }
        Website siteConfiguration = protoBucket.getWebsite();
        if (null != siteConfiguration) {
            accessConfigBuilder.setIndexPage(siteConfiguration.getMainPageSuffix());
            accessConfigBuilder.setNotFoundPage(siteConfiguration.getNotFoundPage());
        }
        if (null != protoBucket.getLifecycle() && null != protoBucket.getLifecycle().getRule()) {
            accessConfigBuilder.setLifecycleRules(transform(protoBucket.getLifecycle().getRule(), new Function<Rule, LifecycleRuleDefinition>() {

                @Override
                public BucketMetadata.LifecycleRuleDefinition apply(Rule rule) {
                    return LifecycleRuleDefinition.fromProto(rule);
                }
            }));
            accessConfigBuilder.setDeleteRules(transform(protoBucket.getLifecycle().getRule(), new Function<Rule, DeletionRule>() {

                @Override
                public BucketMetadata.DeletionRule apply(Rule rule) {
                    return DeletionRule.fromProto(rule);
                }
            }));
        }
        if (null != protoBucket.getLabels()) {
            accessConfigBuilder.setLabels(protoBucket.getLabels());
        }
        Bucket.Billing chargeConfig = protoBucket.getBilling();
        if (null != chargeConfig) {
            accessConfigBuilder.setRequesterPays(chargeConfig.getRequesterPays());
        }
        Encryption cryptoConfig = protoBucket.getEncryption();
        if (null != cryptoConfig && null != cryptoConfig.getDefaultKmsKeyName() && !cryptoConfig.getDefaultKmsKeyName().isEmpty()) {
            accessConfigBuilder.setDefaultKmsKeyName(cryptoConfig.getDefaultKmsKeyName());
        }
        if (null != protoBucket.getDefaultEventBasedHold()) {
            accessConfigBuilder.setDefaultEventBasedHold(protoBucket.getDefaultEventBasedHold());
        }
        Bucket.RetentionPolicy policySettings = protoBucket.getRetentionPolicy();
        if (null != policySettings) {
            if (null != policySettings.getEffectiveTime()) {
                accessConfigBuilder.setRetentionEffectiveTime(policySettings.getEffectiveTime().getValue());
            }
            if (null != policySettings.getIsLocked()) {
                accessConfigBuilder.setRetentionPolicyIsLocked(policySettings.getIsLocked());
            }
            if (null != policySettings.getRetentionPeriod()) {
                accessConfigBuilder.setRetentionPeriod(policySettings.getRetentionPeriod());
            }
        }
        Bucket.IamConfiguration identityAccessConfig = protoBucket.getIamConfiguration();
        if (null != protoBucket.getLocationType()) {
            accessConfigBuilder.setLocationType(protoBucket.getLocationType());
        }
        if (null != identityAccessConfig) {
            accessConfigBuilder.setIamConfiguration(BucketIamConfiguration.fromProto(identityAccessConfig));
        }
        Bucket.Logging auditLogging = protoBucket.getLogging();
        if (null != auditLogging) {
            accessConfigBuilder.setLogging(LoggingConfig.fromProto(auditLogging));
        }
        return accessConfigBuilder.buildBucket();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", displayName).toString();
    }

}
