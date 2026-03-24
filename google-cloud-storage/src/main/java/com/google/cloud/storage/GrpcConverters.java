/*
 * Copyright 2022 Google LLC
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

import static com.google.cloud.storage.Utils.bucketNameCodec;
import static com.google.cloud.storage.Utils.durationMillisCodec;
import static com.google.cloud.storage.Utils.ifNonNull;
import static com.google.cloud.storage.Utils.lift;
import static com.google.cloud.storage.Utils.projectNameCodec;
import static com.google.cloud.storage.Utils.toImmutableListOf;
import static com.google.cloud.storage.Utils.todo;

import com.google.cloud.storage.Acl.Entity;
import com.google.cloud.storage.Acl.Role;
import com.google.cloud.storage.BlobInfo.CustomerEncryption;
import com.google.cloud.storage.BucketInfo.CustomPlacementConfig;
import com.google.cloud.storage.BucketInfo.LifecycleRule;
import com.google.cloud.storage.BucketInfo.PublicAccessPrevention;
import com.google.cloud.storage.Conversions.Codec;
import com.google.cloud.storage.HmacKey.HmacKeyState;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.Bucket.Billing;
import com.google.storage.v2.BucketAccessControl;
import com.google.storage.v2.HmacKeyMetadata;
import com.google.storage.v2.Object;
import com.google.storage.v2.ObjectAccessControl;
import com.google.storage.v2.ObjectChecksums;
import com.google.storage.v2.Owner;
import com.google.type.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

final class GrpcConverters {
  static final GrpcConverters GRPC_CONVERTERS = new GrpcConverters();

  private final Codec<Acl.Entity, String> recordCodec =
      Codec.of(this::encodeEntity, this::decodeEntity);
  private final Codec<Acl, ObjectAccessControl> accessControlCodec =
      Codec.of(this::objectAclToProto, this::decodeObjectAcl);
  private final Codec<Acl, BucketAccessControl> storagePermissionCodec =
      Codec.of(this::bucketAclToProto, this::decodeBucketAcl);
  private final Codec<HmacKey.HmacKeyMetadata, HmacKeyMetadata> hmacMetadataCodec =
      Codec.of(this::encodeHmacKeyMetadata, this::decodeHmacKeyMetadata);
  private final Codec<?, ?> hmacKeyConverter = Codec.of(Utils::todo, Utils::todo);
  private final Codec<ServiceAccount, com.google.storage.v2.ServiceAccount> serviceAccountMapper =
      Codec.of(this::toGrpcServiceAccount, this::toServiceAccount);
  private final Codec<Cors, Bucket.Cors> corsMapper = Codec.of(this::encodeCors, this::decodeCors);
  private final Codec<BucketInfo.Logging, Bucket.Logging> loggingMapper =
      Codec.of(this::encodeLogging, this::decodeLogging);
  private final Codec<BucketInfo.IamConfiguration, Bucket.IamConfig> iamConfigMapper =
      Codec.of(this::encodeIamConfig, this::decodeIamConfig);
  private final Codec<BucketInfo.LifecycleRule, Bucket.Lifecycle.Rule> lifecycleRuleMapper =
      Codec.of(this::encodeLifecycleRule, this::decodeLifecycleRule);
  private final Codec<BucketInfo, Bucket> bucketInfoMapper =
      Codec.of(this::encodeBucketInfo, this::decodeBucketInfo);
  private final Codec<CustomerEncryption, com.google.storage.v2.CustomerEncryption>
      customerEncryptionMapper =
          Codec.of(this::toGrpcCustomerEncryption, this::decodeCustomerEncryption);
  private final Codec<BlobId, Object> blobIdMapper =
      Codec.of(this::encodeBlobId, this::toBlobId);
  private final Codec<BlobInfo, Object> blobInfoMapper =
      Codec.of(this::encodeBlobInfo, this::decodeBlobInfo);
  private final Codec<?, ?> notificationMapper = Codec.of(Utils::todo, Utils::todo);
  private final Codec<Integer, String> crc32cMapper =
      Codec.of(this::encodeCrc32c, this::decodeCrc32c);

  @VisibleForTesting
  final Codec<OffsetDateTime, Timestamp> timestampMapper =
      Codec.of(
          odt ->
              Timestamp.newBuilder()
                  .setSeconds(odt.toEpochSecond())
                  .setNanos(odt.getNano())
                  .build(),
          t ->
              Instant.ofEpochSecond(t.getSeconds())
                  .plusNanos(t.getNanos())
                  .atOffset(ZoneOffset.UTC));

  @VisibleForTesting
  final Codec<OffsetDateTime, Date> offsetToDateCodec =
      Codec.of(
          odt -> {
            OffsetDateTime utc = odt.withOffsetSameInstant(ZoneOffset.UTC);
            return Date.newBuilder()
                .setYear(utc.getYear())
                .setMonth(utc.getMonthValue())
                .setDay(utc.getDayOfMonth())
                .build();
          },
          d ->
              LocalDate.of(d.getYear(), d.getMonth(), d.getDay())
                  .atStartOfDay()
                  .atOffset(ZoneOffset.UTC));

  private GrpcConverters() {}

  Codec<Acl.Entity, String> entityCodec() {
    return recordCodec;
  }

  Codec<Acl, ObjectAccessControl> objectAclCodec() {
    return accessControlCodec;
  }

  Codec<Acl, BucketAccessControl> bucketAclCodec() {
    return storagePermissionCodec;
  }

  Codec<HmacKey.HmacKeyMetadata, HmacKeyMetadata> hmacKeyMetadataCodec() {
    return hmacMetadataCodec;
  }

  Codec<?, ?> getHmacKey() {
    return todo();
  }

  Codec<ServiceAccount, com.google.storage.v2.ServiceAccount> serviceAccountCodec() {
    return serviceAccountMapper;
  }

  Codec<Cors, Bucket.Cors> getCors() {
    return corsMapper;
  }

  Codec<BucketInfo.Logging, Bucket.Logging> loggingCodec() {
    return loggingMapper;
  }

  Codec<BucketInfo.IamConfiguration, Bucket.IamConfig> getIamConfiguration() {
    return iamConfigMapper;
  }

  Codec<BucketInfo.LifecycleRule, Bucket.Lifecycle.Rule> lifecycleRuleCodec() {
    return lifecycleRuleMapper;
  }

  Codec<BucketInfo, Bucket> bucketInfoCodec() {
    return bucketInfoMapper;
  }

  Codec<CustomerEncryption, com.google.storage.v2.CustomerEncryption> customerEncryptionCodec() {
    return customerEncryptionMapper;
  }

  Codec<BlobId, Object> blobIdCodec() {
    return blobIdMapper;
  }

  Codec<BlobInfo, Object> blobInfoCodec() {
    return blobInfoMapper;
  }

  Codec<?, ?> notificationDetails() {
    return todo();
  }

  private BucketInfo decodeBucketInfo(Bucket sourceBucket) {
    BucketInfo.Builder builder = new BucketInfo.BuilderImpl(bucketNameCodec.decode(sourceBucket.getName()));
    builder.setProject(sourceBucket.getProject());
    builder.setGeneratedId(sourceBucket.getBucketId());
    if (sourceBucket.hasRetentionPolicy()) {
      Bucket.RetentionPolicy retention = sourceBucket.getRetentionPolicy();
      ifNonNull(retention.getIsLocked(), builder::setRetentionPolicyIsLocked);
      ifNonNull(
          retention.getRetentionPeriod(),
          Utils.durationMillisCodec::decode,
          builder::setRetentionPeriodDuration);
      ifNonNull(
          retention.getEffectiveTime(),
          timestampMapper::decode,
          builder::setRetentionEffectiveTimeOffsetDateTime);
    }
    ifNonNull(sourceBucket.getLocation(), builder::setLocation);
    ifNonNull(sourceBucket.getLocationType(), builder::setLocationType);
    ifNonNull(sourceBucket.getMetageneration(), builder::setMetageneration);
    if (sourceBucket.hasBilling()) {
      Billing billingInfo = sourceBucket.getBilling();
      builder.setRequesterPays(billingInfo.getRequesterPays());
    }
    if (sourceBucket.hasCreateTime()) {
      builder.setCreateTimeOffsetDateTime(timestampMapper.decode(sourceBucket.getCreateTime()));
    }
    if (sourceBucket.hasUpdateTime()) {
      builder.setUpdateTimeOffsetDateTime(timestampMapper.decode(sourceBucket.getUpdateTime()));
    }
    if (sourceBucket.hasEncryption()) {
      builder.setDefaultKmsKeyName(sourceBucket.getEncryption().getDefaultKmsKey());
    }
    if (!sourceBucket.getRpo().isEmpty()) {
      builder.setRpo(Rpo.valueOf(sourceBucket.getRpo()));
    }
    if (!sourceBucket.getStorageClass().isEmpty()) {
      builder.setStorageClass(StorageClass.valueOf(sourceBucket.getStorageClass()));
    }
    if (sourceBucket.hasVersioning()) {
      builder.setVersioningEnabled(sourceBucket.getVersioning().getEnabled());
    }
    ifNonNull(sourceBucket.getDefaultEventBasedHold(), builder::setDefaultEventBasedHold);
    Map<String, String> labels = sourceBucket.getLabelsMap();
    if (!labels.isEmpty()) {
      builder.setLabels(labels);
    }
    if (sourceBucket.hasWebsite()) {
      builder.setIndexPage(sourceBucket.getWebsite().getMainPageSuffix());
      builder.setNotFoundPage(sourceBucket.getWebsite().getNotFoundPage());
    }
    if (sourceBucket.hasLifecycle()) {
      builder.setLifecycleRules(
          toImmutableListOf(lifecycleRuleMapper::decode).apply(sourceBucket.getLifecycle().getRuleList()));
    }
    List<Bucket.Cors> corsEntries = sourceBucket.getCorsList();
    if (!corsEntries.isEmpty()) {
      builder.setCors(toImmutableListOf(corsMapper::decode).apply(corsEntries));
    }
    if (sourceBucket.hasLogging()) {
      builder.setLogging(loggingMapper.decode(sourceBucket.getLogging()));
    }
    if (sourceBucket.hasOwner()) {
      builder.setOwner(recordCodec.decode(sourceBucket.getOwner().getEntity()));
    }

    List<ObjectAccessControl> defaultAclList = sourceBucket.getDefaultObjectAclList();
    if (!defaultAclList.isEmpty()) {
      builder.setDefaultAcl(toImmutableListOf(accessControlCodec::decode).apply(defaultAclList));
    }
    List<BucketAccessControl> bucketAclEntries = sourceBucket.getAclList();
    if (!bucketAclEntries.isEmpty()) {
      builder.setAcl(toImmutableListOf(storagePermissionCodec::decode).apply(bucketAclEntries));
    }
    if (sourceBucket.hasIamConfig()) {
      builder.setIamConfiguration(iamConfigMapper.decode(sourceBucket.getIamConfig()));
    }
    if (sourceBucket.hasCustomPlacementConfig()) {
      Bucket.CustomPlacementConfig customPlacement = sourceBucket.getCustomPlacementConfig();
      builder.setCustomPlacementConfig(
          CustomPlacementConfig.newBuilder()
              .setDataLocations(customPlacement.getDataLocationsList())
              .build());
    }
    // TODO(frankyn): Add SelfLink when the field is available
    if (!sourceBucket.getEtag().isEmpty()) {
      builder.setEtag(sourceBucket.getEtag());
    }
    return builder.build();
  }

  private Bucket encodeBucketInfo(BucketInfo sourceBucket) {
    Bucket.Builder builder = Bucket.newBuilder();
    builder.setName(bucketNameCodec.encode(sourceBucket.getName()));
    ifNonNull(sourceBucket.getGeneratedId(), builder::setBucketId);
    if (sourceBucket.getRetentionPeriodDuration() != null) {
      Bucket.RetentionPolicy.Builder retentionBuilder = builder.getRetentionPolicyBuilder();
      ifNonNull(
          sourceBucket.getRetentionPeriodDuration(),
          durationMillisCodec::encode,
          retentionBuilder::setRetentionPeriod);
      ifNonNull(sourceBucket.retentionPolicyIsLocked(), retentionBuilder::setIsLocked);
      if (sourceBucket.retentionPolicyIsLocked() == Boolean.TRUE) {
        ifNonNull(
            sourceBucket.getRetentionEffectiveTimeOffsetDateTime(),
            timestampMapper::encode,
            retentionBuilder::setEffectiveTime);
      }
      builder.setRetentionPolicy(retentionBuilder.build());
    }
    ifNonNull(sourceBucket.getLocation(), builder::setLocation);
    ifNonNull(sourceBucket.getLocationType(), builder::setLocationType);
    ifNonNull(sourceBucket.getMetageneration(), builder::setMetageneration);
    if (sourceBucket.requesterPays() != null) {
      Bucket.Billing.Builder billingCfgBuilder = Billing.newBuilder();
      ifNonNull(sourceBucket.requesterPays(), billingCfgBuilder::setRequesterPays);
      builder.setBilling(billingCfgBuilder.build());
    }
    ifNonNull(sourceBucket.getCreateTimeOffsetDateTime(), timestampMapper::encode, builder::setCreateTime);
    ifNonNull(sourceBucket.getUpdateTimeOffsetDateTime(), timestampMapper::encode, builder::setUpdateTime);
    if (sourceBucket.getDefaultKmsKeyName() != null) {
      Bucket.Encryption.Builder encryptionCfgBuilder = Bucket.Encryption.newBuilder();
      ifNonNull(sourceBucket.getDefaultKmsKeyName(), encryptionCfgBuilder::setDefaultKmsKey);
      builder.setEncryption(encryptionCfgBuilder.build());
    }
    if (sourceBucket.getIndexPage() != null || sourceBucket.getNotFoundPage() != null) {
      Bucket.Website.Builder websiteCfgBuilder = Bucket.Website.newBuilder();
      ifNonNull(sourceBucket.getIndexPage(), websiteCfgBuilder::setMainPageSuffix);
      ifNonNull(sourceBucket.getNotFoundPage(), websiteCfgBuilder::setNotFoundPage);
      builder.setWebsite(websiteCfgBuilder.build());
    }
    ifNonNull(sourceBucket.getRpo(), Rpo::toString, builder::setRpo);
    ifNonNull(sourceBucket.getStorageClass(), StorageClass::toString, builder::setStorageClass);
    if (sourceBucket.versioningEnabled() != null) {
      Bucket.Versioning.Builder versionBuilder = Bucket.Versioning.newBuilder();
      ifNonNull(sourceBucket.versioningEnabled(), versionBuilder::setEnabled);
      builder.setVersioning(versionBuilder.build());
    }
    ifNonNull(sourceBucket.getDefaultEventBasedHold(), builder::setDefaultEventBasedHold);
    ifNonNull(sourceBucket.getLabels(), builder::putAllLabels);
    // Do not use, #getLifecycleRules, it can not return null, which is important to our logic here
    List<? extends LifecycleRule> lifecycleRuleList = sourceBucket.lifecycleRules;
    if (lifecycleRuleList != null) {
      Bucket.Lifecycle.Builder lifecycleCfgBuilder = Bucket.Lifecycle.newBuilder();
      if (!lifecycleRuleList.isEmpty()) {
        ImmutableSet<Bucket.Lifecycle.Rule> ruleSet =
            sourceBucket.getLifecycleRules().stream()
                .map(lifecycleRuleMapper::encode)
                .collect(ImmutableSet.toImmutableSet());
        lifecycleCfgBuilder.addAllRule(ImmutableList.copyOf(ruleSet));
      }
      builder.setLifecycle(lifecycleCfgBuilder.build());
    }
    ifNonNull(sourceBucket.getLogging(), loggingMapper::encode, builder::setLogging);
    ifNonNull(sourceBucket.getCors(), toImmutableListOf(corsMapper::encode), builder::addAllCors);
    ifNonNull(
        sourceBucket.getOwner(),
        lift(entityCodec()::encode).andThen(item -> Owner.newBuilder().setEntity(item).build()),
        builder::setOwner);
    ifNonNull(
        sourceBucket.getDefaultAcl(),
        toImmutableListOf(accessControlCodec::encode),
        builder::addAllDefaultObjectAcl);
    ifNonNull(sourceBucket.getAcl(), toImmutableListOf(storagePermissionCodec::encode), builder::addAllAcl);
    ifNonNull(sourceBucket.getIamConfiguration(), iamConfigMapper::encode, builder::setIamConfig);
    CustomPlacementConfig customPlacement = sourceBucket.getCustomPlacementConfig();
    if (customPlacement != null && customPlacement.getDataLocations() != null) {
      builder.setCustomPlacementConfig(
          Bucket.CustomPlacementConfig.newBuilder()
              .addAllDataLocations(customPlacement.getDataLocations())
              .build());
    }
    // TODO(frankyn): Add SelfLink when the field is available
    ifNonNull(sourceBucket.getEtag(), builder::setEtag);
    return builder.build();
  }

  private Bucket.Logging encodeLogging(BucketInfo.Logging sourceBucket) {
    Bucket.Logging.Builder builder = Bucket.Logging.newBuilder();
    if (!sourceBucket.getLogObjectPrefix().isEmpty()) {
      builder.setLogObjectPrefix(sourceBucket.getLogObjectPrefix());
    }
    ifNonNull(sourceBucket.getLogBucket(), bucketNameCodec::encode, builder::setLogBucket);
    return builder.build();
  }

  private BucketInfo.Logging decodeLogging(Bucket.Logging sourceBucket) {
    BucketInfo.Logging.Builder builder = BucketInfo.Logging.newBuilder();
    String objectNamePrefix = sourceBucket.getLogObjectPrefix();
    if (!objectNamePrefix.isEmpty()) {
      builder.setLogObjectPrefix(objectNamePrefix);
    }
    String loggingBucket = sourceBucket.getLogBucket();
    if (!loggingBucket.isEmpty()) {
      builder.setLogBucket(bucketNameCodec.decode(loggingBucket));
    }
    return builder.build();
  }

  private Bucket.Cors encodeCors(Cors sourceBucket) {
    Bucket.Cors.Builder builder = Bucket.Cors.newBuilder();
    builder.setMaxAgeSeconds(sourceBucket.getMaxAgeSeconds());
    builder.addAllResponseHeader(sourceBucket.getResponseHeaders());
    ifNonNull(sourceBucket.getMethods(), toImmutableListOf(java.lang.Object::toString), builder::addAllMethod);
    ifNonNull(sourceBucket.getOrigins(), toImmutableListOf(java.lang.Object::toString), builder::addAllOrigin);
    return builder.build();
  }

  private Cors decodeCors(Bucket.Cors sourceBucket) {
    Cors.Builder builder = Cors.newBuilder().setMaxAgeSeconds(sourceBucket.getMaxAgeSeconds());
    ifNonNull(
        sourceBucket.getMethodList(),
        member ->
            member.stream()
                .map(String::toUpperCase)
                .map(HttpMethod::valueOf)
                .collect(ImmutableList.toImmutableList()),
        builder::setMethods);
    ifNonNull(sourceBucket.getOriginList(), toImmutableListOf(Cors.Origin::of), builder::setOrigins);
    builder.setResponseHeaders(sourceBucket.getResponseHeaderList());
    return builder.build();
  }

  private String encodeEntity(Acl.Entity sourceBucket) {
    if (sourceBucket instanceof Acl.RawEntity) {
      return sourceBucket.getValue();
    } else if (sourceBucket instanceof Acl.User) {
      switch (sourceBucket.getValue()) {
        case Acl.User.ALL_AUTHENTICATED_USERS:
          return Acl.User.ALL_AUTHENTICATED_USERS;
        case Acl.User.ALL_USERS:
          return Acl.User.ALL_USERS;
        default:
          break;
      }
    }
    // intentionally not an else so that if the default is hit above it will fall through to here
    return sourceBucket.getType().name().toLowerCase() + "-" + sourceBucket.getValue();
  }

  private Acl.Entity decodeEntity(String sourceBucket) {
    if (sourceBucket.startsWith("user-")) {
      return new Acl.User(sourceBucket.substring(5));
    }
    if (sourceBucket.equals(Acl.User.ALL_USERS)) {
      return Acl.User.ofAllUsers();
    }
    if (sourceBucket.equals(Acl.User.ALL_AUTHENTICATED_USERS)) {
      return Acl.User.ofAllAuthenticatedUsers();
    }
    if (sourceBucket.startsWith("group-")) {
      return new Acl.Group(sourceBucket.substring(6));
    }
    if (sourceBucket.startsWith("domain-")) {
      return new Acl.Domain(sourceBucket.substring(7));
    }
    if (sourceBucket.startsWith("project-")) {
      int index = sourceBucket.indexOf('-', 8);
      String teamName = sourceBucket.substring(8, index);
      String projectIdentifier = sourceBucket.substring(index + 1);
      return new Acl.Project(Acl.Project.ProjectRole.valueOf(teamName), projectIdentifier);
    }
    return new Acl.RawEntity(sourceBucket);
  }

  private Acl decodeObjectAcl(ObjectAccessControl sourceBucket) {
    Acl.Role aclRole = Acl.Role.valueOf(sourceBucket.getRole());
    Acl.Entity aclEntity = decodeEntity(sourceBucket.getEntity());
    Acl.Builder builder = Acl.newBuilder(aclEntity, aclRole).setId(sourceBucket.getId());
    if (!sourceBucket.getEtag().isEmpty()) {
      builder.setEtag(sourceBucket.getEtag());
    }
    return builder.build();
  }

  private ObjectAccessControl objectAclToProto(Acl sourceBucket) {
    ObjectAccessControl.Builder builder =
        ObjectAccessControl.newBuilder()
            .setEntity(encodeEntity(sourceBucket.getEntity()))
            .setRole(sourceBucket.getRole().name())
            .setId(sourceBucket.getId());
    ifNonNull(sourceBucket.getEtag(), builder::setEtag);
    return builder.build();
  }

  private Acl decodeBucketAcl(com.google.storage.v2.BucketAccessControl sourceBucket) {
    Role aclRole = Role.valueOf(sourceBucket.getRole());
    Entity aclEntity = decodeEntity(sourceBucket.getEntity());
    Acl.Builder builder = Acl.newBuilder(aclEntity, aclRole).setId(sourceBucket.getId());
    if (!sourceBucket.getEtag().isEmpty()) {
      builder.setEtag(sourceBucket.getEtag());
    }
    return builder.build();
  }

  private com.google.storage.v2.BucketAccessControl bucketAclToProto(Acl sourceBucket) {
    BucketAccessControl.Builder builder =
        BucketAccessControl.newBuilder()
            .setEntity(sourceBucket.getEntity().toString())
            .setRole(sourceBucket.getRole().toString())
            .setId(sourceBucket.getId());
    ifNonNull(sourceBucket.getEtag(), builder::setEtag);
    return builder.build();
  }

  private Bucket.IamConfig.UniformBucketLevelAccess encodeUbla(BucketInfo.IamConfiguration sourceBucket) {
    Bucket.IamConfig.UniformBucketLevelAccess.Builder builder =
        Bucket.IamConfig.UniformBucketLevelAccess.newBuilder();
    builder.setEnabled(sourceBucket.isUniformBucketLevelAccessEnabled());
    if (sourceBucket.isUniformBucketLevelAccessEnabled() == Boolean.TRUE) {
      ifNonNull(
          sourceBucket.getUniformBucketLevelAccessLockedTimeOffsetDateTime(),
          timestampMapper::encode,
          builder::setLockTime);
    }
    return builder.build();
  }

  private Bucket.IamConfig encodeIamConfig(BucketInfo.IamConfiguration sourceBucket) {
    Bucket.IamConfig.Builder builder = Bucket.IamConfig.newBuilder();
    builder.setUniformBucketLevelAccess(encodeUbla(sourceBucket));
    if (sourceBucket.getPublicAccessPrevention() != null) {
      ifNonNull(sourceBucket.getPublicAccessPrevention().getValue(), builder::setPublicAccessPrevention);
    }
    return builder.build();
  }

  private BucketInfo.IamConfiguration decodeIamConfig(Bucket.IamConfig sourceBucket) {
    Bucket.IamConfig.UniformBucketLevelAccess uniformAccess = sourceBucket.getUniformBucketLevelAccess();

    BucketInfo.IamConfiguration.Builder builder = BucketInfo.IamConfiguration.newBuilder();
    ifNonNull(uniformAccess.getEnabled(), builder::setIsUniformBucketLevelAccessEnabled);
    ifNonNull(
        uniformAccess.getLockTime(),
        timestampMapper::decode,
        builder::setUniformBucketLevelAccessLockedTimeOffsetDateTime);
    if (!sourceBucket.getPublicAccessPrevention().isEmpty()) {
      builder.setPublicAccessPrevention(PublicAccessPrevention.parse(sourceBucket.getPublicAccessPrevention()));
    }
    return builder.build();
  }

  private Bucket.Lifecycle.Rule encodeLifecycleRule(BucketInfo.LifecycleRule sourceBucket) {
    Bucket.Lifecycle.Rule.Builder builder = Bucket.Lifecycle.Rule.newBuilder();
    builder.setAction(encodeRuleAction(sourceBucket.getAction()));
    builder.setCondition(encodeRuleCondition(sourceBucket.getCondition()));
    return builder.build();
  }

  private Bucket.Lifecycle.Rule.Condition encodeRuleCondition(
      BucketInfo.LifecycleRule.LifecycleCondition sourceBucket) {
    Bucket.Lifecycle.Rule.Condition.Builder builder = Bucket.Lifecycle.Rule.Condition.newBuilder();
    if (sourceBucket.getAge() != null) {
      builder.setAgeDays(sourceBucket.getAge());
    }
    if (sourceBucket.getIsLive() != null) {
      builder.setIsLive(sourceBucket.getIsLive());
    }
    if (sourceBucket.getNumberOfNewerVersions() != null) {
      builder.setNumNewerVersions(sourceBucket.getNumberOfNewerVersions());
    }
    if (sourceBucket.getDaysSinceNoncurrentTime() != null) {
      builder.setDaysSinceNoncurrentTime(sourceBucket.getDaysSinceNoncurrentTime());
    }
    if (sourceBucket.getDaysSinceCustomTime() != null) {
      builder.setDaysSinceCustomTime(sourceBucket.getDaysSinceCustomTime());
    }
    ifNonNull(sourceBucket.getCreatedBeforeOffsetDateTime(), offsetToDateCodec::encode, builder::setCreatedBefore);
    ifNonNull(
        sourceBucket.getNoncurrentTimeBeforeOffsetDateTime(),
        offsetToDateCodec::encode,
        builder::setNoncurrentTimeBefore);
    ifNonNull(
        sourceBucket.getCustomTimeBeforeOffsetDateTime(), offsetToDateCodec::encode, builder::setCustomTimeBefore);
    ifNonNull(
        sourceBucket.getMatchesStorageClass(),
        toImmutableListOf(StorageClass::toString),
        builder::addAllMatchesStorageClass);
    ifNonNull(sourceBucket.getMatchesPrefix(), builder::addAllMatchesPrefix);
    ifNonNull(sourceBucket.getMatchesSuffix(), builder::addAllMatchesSuffix);
    return builder.build();
  }

  private Bucket.Lifecycle.Rule.Action encodeRuleAction(
      BucketInfo.LifecycleRule.LifecycleAction sourceBucket) {
    Bucket.Lifecycle.Rule.Action.Builder builder =
        Bucket.Lifecycle.Rule.Action.newBuilder().setType(sourceBucket.getActionType());
    if (sourceBucket.getActionType().equals(BucketInfo.LifecycleRule.SetStorageClassLifecycleAction.TYPE)) {
      builder.setStorageClass(
          ((BucketInfo.LifecycleRule.SetStorageClassLifecycleAction) sourceBucket)
              .getStorageClass()
              .toString());
    }
    return builder.build();
  }

  private BucketInfo.LifecycleRule decodeLifecycleRule(Bucket.Lifecycle.Rule sourceBucket) {
    BucketInfo.LifecycleRule.LifecycleAction actionProto;

    Bucket.Lifecycle.Rule.Action act = sourceBucket.getAction();

    switch (act.getType()) {
      case BucketInfo.LifecycleRule.DeleteLifecycleAction.TYPE:
        actionProto = BucketInfo.LifecycleRule.LifecycleAction.newDeleteAction();
        break;
      case BucketInfo.LifecycleRule.SetStorageClassLifecycleAction.TYPE:
        actionProto =
            BucketInfo.LifecycleRule.LifecycleAction.newSetStorageClassAction(
                StorageClass.valueOf(act.getStorageClass()));
        break;
      default:
        BucketInfo.log.warning(
            "The lifecycle action "
                + act.getType()
                + " is not supported by this version of the library. "
                + "Attempting to update with this rule may cause errors. Please "
                + "update to the latest version of google-cloud-storage.");
        actionProto =
            BucketInfo.LifecycleRule.LifecycleAction.newLifecycleAction("Unknown action");
    }

    Bucket.Lifecycle.Rule.Condition cond = sourceBucket.getCondition();

    BucketInfo.LifecycleRule.LifecycleCondition.Builder condBuilder =
        BucketInfo.LifecycleRule.LifecycleCondition.newBuilder();
    if (cond.hasAgeDays()) {
      condBuilder.setAge(cond.getAgeDays());
    }
    if (cond.hasCreatedBefore()) {
      condBuilder.setCreatedBeforeOffsetDateTime(
          offsetToDateCodec.nullable().decode(cond.getCreatedBefore()));
    }
    if (cond.hasIsLive()) {
      condBuilder.setIsLive(cond.getIsLive());
    }
    if (cond.hasNumNewerVersions()) {
      condBuilder.setNumberOfNewerVersions(cond.getNumNewerVersions());
    }
    if (cond.hasDaysSinceNoncurrentTime()) {
      condBuilder.setDaysSinceNoncurrentTime(cond.getDaysSinceNoncurrentTime());
    }
    if (cond.hasNoncurrentTimeBefore()) {
      condBuilder.setNoncurrentTimeBeforeOffsetDateTime(
          offsetToDateCodec.decode(cond.getNoncurrentTimeBefore()));
    }
    if (cond.hasCustomTimeBefore()) {
      condBuilder.setCustomTimeBeforeOffsetDateTime(
          offsetToDateCodec.decode(cond.getCustomTimeBefore()));
    }
    if (cond.hasDaysSinceCustomTime()) {
      condBuilder.setDaysSinceCustomTime(cond.getDaysSinceCustomTime());
    }
    ifNonNull(
        cond.getMatchesStorageClassList(),
        toImmutableListOf(StorageClass::valueOf),
        condBuilder::setMatchesStorageClass);
    condBuilder.setMatchesPrefix(cond.getMatchesPrefixList());
    condBuilder.setMatchesSuffix(cond.getMatchesSuffixList());
    return new BucketInfo.LifecycleRule(actionProto, condBuilder.build());
  }

  private HmacKeyMetadata encodeHmacKeyMetadata(HmacKey.HmacKeyMetadata sourceBucket) {
    HmacKeyMetadata.Builder builder = HmacKeyMetadata.newBuilder();
    ifNonNull(sourceBucket.getEtag(), builder::setEtag);
    ifNonNull(sourceBucket.getId(), builder::setId);
    ifNonNull(sourceBucket.getAccessId(), builder::setAccessId);
    ifNonNull(sourceBucket.getProjectId(), projectNameCodec::encode, builder::setProject);
    ifNonNull(sourceBucket.getServiceAccount(), ServiceAccount::getEmail, builder::setServiceAccountEmail);
    ifNonNull(sourceBucket.getState(), Enum::name, builder::setState);
    ifNonNull(sourceBucket.getCreateTimeOffsetDateTime(), timestampMapper::encode, builder::setCreateTime);
    ifNonNull(sourceBucket.getUpdateTimeOffsetDateTime(), timestampMapper::encode, builder::setUpdateTime);
    return builder.build();
  }

  private HmacKey.HmacKeyMetadata decodeHmacKeyMetadata(HmacKeyMetadata sourceBucket) {
    HmacKey.HmacKeyMetadata.Builder builder =
        HmacKey.HmacKeyMetadata.newBuilder(ServiceAccount.of(sourceBucket.getServiceAccountEmail()))
            .setAccessId(sourceBucket.getAccessId())
            .setCreateTimeOffsetDateTime(timestampMapper.decode(sourceBucket.getCreateTime()))
            .setId(sourceBucket.getId())
            .setProjectId(projectNameCodec.decode(sourceBucket.getProject()))
            .setState(HmacKeyState.valueOf(sourceBucket.getState()))
            .setUpdateTimeOffsetDateTime(timestampMapper.decode(sourceBucket.getUpdateTime()));
    if (!sourceBucket.getEtag().isEmpty()) {
      builder.setEtag(sourceBucket.getEtag());
    }
    return builder.build();
  }

  private com.google.storage.v2.ServiceAccount toGrpcServiceAccount(ServiceAccount sourceBucket) {
    return com.google.storage.v2.ServiceAccount.newBuilder()
        .setEmailAddress(sourceBucket.getEmail())
        .build();
  }

  private ServiceAccount toServiceAccount(com.google.storage.v2.ServiceAccount sourceBucket) {
    return ServiceAccount.of(sourceBucket.getEmailAddress());
  }

  private com.google.storage.v2.CustomerEncryption toGrpcCustomerEncryption(
      CustomerEncryption sourceBucket) {
    return com.google.storage.v2.CustomerEncryption.newBuilder()
        .setEncryptionAlgorithm(sourceBucket.getEncryptionAlgorithm())
        .setKeySha256Bytes(ByteString.copyFrom(BaseEncoding.base64().decode(sourceBucket.getKeySha256())))
        .build();
  }

  private CustomerEncryption decodeCustomerEncryption(
      com.google.storage.v2.CustomerEncryption sourceBucket) {
    return new CustomerEncryption(
        sourceBucket.getEncryptionAlgorithm(),
        BaseEncoding.base64().encode(sourceBucket.getKeySha256Bytes().toByteArray()));
  }

  private Object encodeBlobId(BlobId sourceBucket) {
    Object.Builder builder = Object.newBuilder();
    ifNonNull(sourceBucket.getBucket(), bucketNameCodec::encode, builder::setBucket);
    ifNonNull(sourceBucket.getName(), builder::setName);
    ifNonNull(sourceBucket.getGeneration(), builder::setGeneration);
    return builder.build();
  }

  private BlobId toBlobId(Object sourceBucket) {
    return BlobId.of(sourceBucket.getBucket(), sourceBucket.getName(), sourceBucket.getGeneration());
  }

  private Object encodeBlobInfo(BlobInfo sourceBucket) {
    Object.Builder protoBuilder = Object.newBuilder();
    ifNonNull(sourceBucket.getBucket(), bucketNameCodec::encode, protoBuilder::setBucket);
    ifNonNull(sourceBucket.getName(), protoBuilder::setName);
    ifNonNull(sourceBucket.getGeneration(), protoBuilder::setGeneration);
    ifNonNull(sourceBucket.getCacheControl(), protoBuilder::setCacheControl);
    ifNonNull(sourceBucket.getSize(), protoBuilder::setSize);
    ifNonNull(sourceBucket.getContentType(), protoBuilder::setContentType);
    ifNonNull(sourceBucket.getContentEncoding(), protoBuilder::setContentEncoding);
    ifNonNull(sourceBucket.getContentDisposition(), protoBuilder::setContentDisposition);
    ifNonNull(sourceBucket.getContentLanguage(), protoBuilder::setContentLanguage);
    ifNonNull(sourceBucket.getComponentCount(), protoBuilder::setComponentCount);
    if (sourceBucket.getMd5() != null || sourceBucket.getCrc32c() != null) {
      ObjectChecksums.Builder checksumsBuilder = ObjectChecksums.newBuilder();
      if (sourceBucket.getMd5() != null) {
        checksumsBuilder.setMd5Hash(
            ByteString.copyFrom(BaseEncoding.base64().decode(sourceBucket.getMd5())));
      }
      if (sourceBucket.getCrc32c() != null) {
        checksumsBuilder.setCrc32C(crc32cMapper.decode(sourceBucket.getCrc32c()));
      }
      protoBuilder.setChecksums(checksumsBuilder.build());
    }
    ifNonNull(sourceBucket.getMetageneration(), protoBuilder::setMetageneration);
    ifNonNull(sourceBucket.getDeleteTimeOffsetDateTime(), timestampMapper::encode, protoBuilder::setDeleteTime);
    ifNonNull(sourceBucket.getUpdateTimeOffsetDateTime(), timestampMapper::encode, protoBuilder::setUpdateTime);
    ifNonNull(sourceBucket.getCreateTimeOffsetDateTime(), timestampMapper::encode, protoBuilder::setCreateTime);
    ifNonNull(sourceBucket.getCustomTimeOffsetDateTime(), timestampMapper::encode, protoBuilder::setCustomTime);
    ifNonNull(
        sourceBucket.getCustomerEncryption(),
        customerEncryptionMapper::encode,
        protoBuilder::setCustomerEncryption);
    ifNonNull(sourceBucket.getStorageClass(), StorageClass::toString, protoBuilder::setStorageClass);
    ifNonNull(
        sourceBucket.getTimeStorageClassUpdatedOffsetDateTime(),
        timestampMapper::encode,
        protoBuilder::setUpdateStorageClassTime);
    ifNonNull(sourceBucket.getKmsKeyName(), protoBuilder::setKmsKey);
    ifNonNull(sourceBucket.getEventBasedHold(), protoBuilder::setEventBasedHold);
    ifNonNull(sourceBucket.getTemporaryHold(), protoBuilder::setTemporaryHold);
    ifNonNull(
        sourceBucket.getRetentionExpirationTimeOffsetDateTime(),
        timestampMapper::encode,
        protoBuilder::setRetentionExpireTime);
    // TODO(sydmunro): Add Selflink when available
    ifNonNull(sourceBucket.getEtag(), protoBuilder::setEtag);
    Entity aclEntity = sourceBucket.getOwner();
    if (aclEntity != null) {
      protoBuilder.setOwner(Owner.newBuilder().setEntity(encodeEntity(aclEntity)).build());
    }
    ifNonNull(sourceBucket.getMetadata(), protoBuilder::putAllMetadata);
    ifNonNull(sourceBucket.getAcl(), toImmutableListOf(objectAclCodec()::encode), protoBuilder::addAllAcl);
    return protoBuilder.build();
  }

  private BlobInfo decodeBlobInfo(Object sourceBucket) {
    BlobInfo.Builder protoBuilder =
        BlobInfo.newBuilder(BlobId.of(sourceBucket.getBucket(), sourceBucket.getName(), sourceBucket.getGeneration()));
    ifNonNull(sourceBucket.getCacheControl(), protoBuilder::setCacheControl);
    ifNonNull(sourceBucket.getSize(), protoBuilder::setSize);
    ifNonNull(sourceBucket.getContentType(), protoBuilder::setContentType);
    ifNonNull(sourceBucket.getContentEncoding(), protoBuilder::setContentEncoding);
    ifNonNull(sourceBucket.getContentDisposition(), protoBuilder::setContentDisposition);
    ifNonNull(sourceBucket.getContentLanguage(), protoBuilder::setContentLanguage);
    ifNonNull(sourceBucket.getComponentCount(), protoBuilder::setComponentCount);
    if (sourceBucket.hasChecksums()) {
      ObjectChecksums objectChecksums = sourceBucket.getChecksums();
      if (objectChecksums.hasCrc32C()) {
        protoBuilder.setCrc32c(crc32cMapper.encode(objectChecksums.getCrc32C()));
      }
      if (!objectChecksums.getMd5Hash().equals(ByteString.empty())) {
        protoBuilder.setMd5(BaseEncoding.base64().encode(objectChecksums.getMd5Hash().toByteArray()));
      }
    }
    ifNonNull(sourceBucket.getMetageneration(), protoBuilder::setMetageneration);
    if (sourceBucket.hasDeleteTime()) {
      protoBuilder.setDeleteTimeOffsetDateTime(timestampMapper.decode(sourceBucket.getDeleteTime()));
    }
    if (sourceBucket.hasUpdateTime()) {
      protoBuilder.setUpdateTimeOffsetDateTime(timestampMapper.decode(sourceBucket.getUpdateTime()));
    }
    if (sourceBucket.hasCreateTime()) {
      protoBuilder.setCreateTimeOffsetDateTime(timestampMapper.decode(sourceBucket.getCreateTime()));
    }
    if (sourceBucket.hasCustomTime()) {
      protoBuilder.setCustomTimeOffsetDateTime(timestampMapper.decode(sourceBucket.getCustomTime()));
    }
    if (sourceBucket.hasCustomerEncryption()) {
      protoBuilder.setCustomerEncryption(customerEncryptionMapper.decode(sourceBucket.getCustomerEncryption()));
    }
    String className = sourceBucket.getStorageClass();
    if (!className.isEmpty()) {
      protoBuilder.setStorageClass(StorageClass.valueOf(className));
    }
    if (sourceBucket.hasUpdateStorageClassTime()) {
      protoBuilder.setTimeStorageClassUpdatedOffsetDateTime(
          timestampMapper.decode(sourceBucket.getUpdateStorageClassTime()));
    }
    if (!sourceBucket.getKmsKey().isEmpty()) {
      protoBuilder.setKmsKeyName(sourceBucket.getKmsKey());
    }
    if (sourceBucket.hasEventBasedHold()) {
      protoBuilder.setEventBasedHold(sourceBucket.getEventBasedHold());
    }
    protoBuilder.setTemporaryHold(sourceBucket.getTemporaryHold());
    if (sourceBucket.hasRetentionExpireTime()) {
      protoBuilder.setRetentionExpirationTimeOffsetDateTime(
          timestampMapper.decode(sourceBucket.getRetentionExpireTime()));
    }
    if (!sourceBucket.getMetadataMap().isEmpty()) {
      protoBuilder.setMetadata(sourceBucket.getMetadataMap());
    }
    if (sourceBucket.hasOwner()) {
      Owner blobOwner = sourceBucket.getOwner();
      if (!blobOwner.getEntity().isEmpty()) {
        protoBuilder.setOwner(decodeEntity(blobOwner.getEntity()));
      }
    }
    if (!sourceBucket.getEtag().isEmpty()) {
      protoBuilder.setEtag(sourceBucket.getEtag());
    }
    ifNonNull(sourceBucket.getAclList(), toImmutableListOf(objectAclCodec()::decode), protoBuilder::setAcl);
    return protoBuilder.build();
  }

  private int decodeCrc32c(String sourceBucket) {
    byte[] decodedCrc = BaseEncoding.base64().decode(sourceBucket);
    return Ints.fromByteArray(decodedCrc);
  }

  private String encodeCrc32c(int sourceBucket) {
    return BaseEncoding.base64().encode(Ints.toByteArray(sourceBucket));
  }
}
