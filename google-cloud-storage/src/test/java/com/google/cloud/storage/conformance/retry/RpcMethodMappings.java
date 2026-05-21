/*
 * Copyright 2021 Google LLC
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

package com.google.cloud.storage.conformance.retry;

import static com.google.cloud.storage.conformance.retry.CtxFunctions.ResourceSetup.defaultSetup;
import static com.google.cloud.storage.conformance.retry.CtxFunctions.ResourceSetup.serviceAccount;
import static com.google.common.base.Predicates.not;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.cloud.BaseServiceException;
import com.google.cloud.Binding;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.ReadChannel;
import com.google.cloud.RetryHelper.RetryHelperException;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AccessControlEntry;
import com.google.cloud.storage.AccessControlEntry.UserIdentity;
import com.google.cloud.storage.HmacSecretKey;
import com.google.cloud.storage.StorageBucket;
import com.google.cloud.storage.StorageObject;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.HttpRequestMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobSourceOptions;
import com.google.cloud.storage.Storage.BlobUploadOption;
import com.google.cloud.storage.Storage.BlobWriteOptions;
import com.google.cloud.storage.Storage.BucketSourceOptions;
import com.google.cloud.storage.StorageRoles;
import com.google.cloud.storage.conformance.retry.CtxFunctions.Local;
import com.google.cloud.storage.conformance.retry.CtxFunctions.ResourceSetup;
import com.google.cloud.storage.conformance.retry.CtxFunctions.Rpc;
import com.google.cloud.storage.conformance.retry.Functions.EConsumer;
import com.google.cloud.storage.conformance.retry.RpcMethod.storage.bucket_acl;
import com.google.cloud.storage.conformance.retry.RpcMethod.storage.buckets;
import com.google.cloud.storage.conformance.retry.RpcMethod.storage.default_object_acl;
import com.google.cloud.storage.conformance.retry.RpcMethod.storage.hmacKey;
import com.google.cloud.storage.conformance.retry.RpcMethod.storage.object_acl;
import com.google.cloud.storage.conformance.retry.RpcMethod.storage.objects;
import com.google.cloud.storage.conformance.retry.RpcMethod.storage.serviceaccount;
import com.google.cloud.storage.conformance.retry.RpcMethodMappings.Mappings.BucketAcl;
import com.google.cloud.storage.conformance.retry.RpcMethodMappings.Mappings.Buckets;
import com.google.cloud.storage.conformance.retry.RpcMethodMappings.Mappings.DefaultObjectAcl;
import com.google.cloud.storage.conformance.retry.RpcMethodMappings.Mappings.HmacKey;
import com.google.cloud.storage.conformance.retry.RpcMethodMappings.Mappings.Notification;
import com.google.cloud.storage.conformance.retry.RpcMethodMappings.Mappings.ObjectAcl;
import com.google.cloud.storage.conformance.retry.RpcMethodMappings.Mappings.Objects;
import com.google.cloud.storage.conformance.retry.RpcMethodMappings.Mappings.ServiceAccount;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.errorprone.annotations.Immutable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A class which serves to try and organize all of the {@link RpcMethodMapping} for the retry
 * conformance tests.
 *
 * <p>Individual mappings are grouped via inner classes corresponding to the {@link RpcMethod} for
 * which they are defined.
 *
 * <p>As part of construction mappingIds are enforced to be unique, throwing an error if not.
 */
@Immutable
@SuppressWarnings("Guava")
final class RpcMethodMappings {
  private static final Logger LOGGER = Logger.getLogger(RpcMethodMappings.class.getName());

  static final int _2MiB = 2 * 1024 * 1024;
  final Multimap<RpcMethod, RpcMethodMapping> funcMap;

  RpcMethodMappings() {
    ArrayList<RpcMethodMapping> a = new ArrayList<>();

    BucketAcl.delete(a);
    BucketAcl.get(a);
    BucketAcl.insert(a);
    BucketAcl.list(a);
    BucketAcl.patch(a);

    Buckets.delete(a);
    Buckets.get(a);
    Buckets.insert(a);
    Buckets.list(a);
    Buckets.patch(a);
    Buckets.update(a);
    Buckets.getIamPolicy(a);
    Buckets.lockRetentionPolicy(a);
    Buckets.setIamPolicy(a);
    Buckets.testIamPermission(a);

    DefaultObjectAcl.delete(a);
    DefaultObjectAcl.get(a);
    DefaultObjectAcl.insert(a);
    DefaultObjectAcl.list(a);
    DefaultObjectAcl.patch(a);
    DefaultObjectAcl.update(a);

    HmacKey.delete(a);
    HmacKey.get(a);
    HmacKey.list(a);
    HmacKey.update(a);
    HmacKey.create(a);

    Notification.delete(a);
    Notification.get(a);
    Notification.insert(a);
    Notification.list(a);

    ObjectAcl.delete(a);
    ObjectAcl.get(a);
    ObjectAcl.insert(a);
    ObjectAcl.list(a);
    ObjectAcl.patch(a);
    ObjectAcl.update(a);

    Objects.delete(a);
    Objects.get(a);
    Objects.insert(a);
    Objects.list(a);
    Objects.patch(a);
    Objects.update(a);
    Objects.compose(a);
    Objects.rewrite(a);
    Objects.copy(a);

    ServiceAccount.get(a);
    ServiceAccount.put(a);

    validateMappingDefinitions(a);

    funcMap = Multimaps.index(a, RpcMethodMapping::getMethod);
    reportMappingSummary();
  }

  public Collection<RpcMethodMapping> get(RpcMethod key) {
    return funcMap.get(key);
  }

  public Set<Integer> differenceMappingIds(Set<Integer> usedMappingIds) {
    return Sets.difference(
        funcMap.values().stream().map(RpcMethodMapping::getMappingId).collect(Collectors.toSet()),
        usedMappingIds);
  }

  private void validateMappingDefinitions(ArrayList<RpcMethodMapping> a) {
    ListMultimap<Integer, RpcMethodMapping> idMappings =
        MultimapBuilder.hashKeys()
            .arrayListValues()
            .build(Multimaps.index(a, RpcMethodMapping::getMappingId));
    String duplicateIds =
        idMappings.asMap().entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(Entry::getKey)
            .map(i -> Integer.toString(i))
            .collect(Collectors.joining(", "));
    if (!duplicateIds.isEmpty()) {
      String message = "duplicate mapping ids present: [" + duplicateIds + "]";
      throw new IllegalStateException(message);
    }
  }

  private void reportMappingSummary() {
    int mappingCount = funcMap.values().stream().mapToInt(m -> 1).sum();
    LOGGER.info("Current total number of mappings defined: " + mappingCount);
    String counts =
        funcMap.asMap().entrySet().stream()
            .map(
                e -> {
                  RpcMethod rpcMethod = e.getKey();
                  Collection<RpcMethodMapping> mappings = e.getValue();
                  return String.format(
                      "\t%s.%s: %d",
                      rpcMethod
                          .getClass()
                          .getName()
                          .replace("com.google.cloud.storage.conformance.retry.RpcMethod$", "")
                          .replace("$", "."),
                      rpcMethod,
                      mappings.size());
                })
            .sorted()
            .collect(Collectors.joining("\n", "\n", ""));
    LOGGER.info("Current number of mappings per rpc method: " + counts);
    OptionalInt max =
        funcMap.values().stream().map(RpcMethodMapping::getMappingId).mapToInt(i -> i).max();
    if (max.isPresent()) {
      LOGGER.info(String.format("Current max mapping index is: %d%n", max.getAsInt()));
    } else {
      throw new IllegalStateException("No mappings defined");
    }
  }

  private static void withTempFile(String prefix, String suffix, EConsumer<Path> f)
      throws Throwable {
    Path tmpOutFile = Files.createTempFile(prefix, suffix);
    try {
      f.consume(tmpOutFile);
    } finally {
      Files.delete(tmpOutFile);
    }
  }

  static final class Mappings {

    static final class BucketAcl {

      private static void delete(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(1, bucket_acl.delete)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean deleteSuccess =
                                  ctx.getStorage().deleteAcl(c.getBucketName(), UserIdentity.allUsers());
                              assertTrue(deleteSuccess);
                              return state.with(deleteSuccess);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(2, bucket_acl.delete)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  ctx.getStorage()
                                      .deleteAcl(
                                          c.getBucketName(),
                                          AccessControlEntry.UserIdentity.allUsers(),
                                          BucketSourceOptions.withUserProject(c.getUserProject()));
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(87, bucket_acl.delete)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  state.getBucket().removeAcl(state.getAcl().getEntity());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
      }

      private static void get(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(3, bucket_acl.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage().getAcl(c.getBucketName(), UserIdentity.allUsers()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(4, bucket_acl.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .getAcl(
                                            c.getBucketName(),
                                            AccessControlEntry.UserIdentity.allUsers(),
                                            BucketSourceOptions.withUserProject(c.getUserProject())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(88, bucket_acl.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(state.getBucket().getAcl(state.getAcl().getEntity()))))
                .build());
      }

      private static void insert(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(5, bucket_acl.insert)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage().createAcl(c.getBucketName(), state.getAcl()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(6, bucket_acl.insert)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createAcl(
                                            c.getBucketName(),
                                            state.getAcl(),
                                            BucketSourceOptions.withUserProject(c.getUserProject())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(89, bucket_acl.insert)
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.with(state.getBucket().addAcl(state.getAcl()))))
                .build());
      }

      private static void list(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(7, bucket_acl.list)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> state.withAcls(ctx.getStorage().listAcls(c.getBucketName()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(8, bucket_acl.list)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.withAcls(
                                    ctx.getStorage()
                                        .listAcls(
                                            c.getBucketName(),
                                            BucketSourceOptions.withUserProject(c.getUserProject())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(90, bucket_acl.list)
                .withTest(
                    (ctx, c) -> ctx.map(state -> state.withAcls(state.getBucket().listAcl())))
                .build());
      }

      private static void patch(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(9, bucket_acl.patch)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage().updateAcl(c.getBucketName(), state.getAcl()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(10, bucket_acl.patch)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .updateAcl(
                                            c.getBucketName(),
                                            state.getAcl(),
                                            BucketSourceOptions.withUserProject(c.getUserProject())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(91, bucket_acl.patch)
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.with(state.getBucket().updateAccessControl(state.getAcl()))))
                .build());
      }
    }

    static final class Buckets {
      private static void delete(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(11, buckets.delete)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  ctx.getStorage()
                                      .delete(
                                          c.getBucketName(),
                                          BucketSourceOptions.withUserProject(c.getUserProject()));
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(92, buckets.delete)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success = state.getBucket().deleteBucket();
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(93, buckets.delete)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  state
                                      .getBucket()
                                      .deleteBucket(StorageBucket.BucketSourceOptions.ifMetagenerationMatch());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
      }

      private static void get(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(12, buckets.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.with(ctx.getStorage().get(c.getBucketName()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(94, buckets.get)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success = state.getBucket().bucketExists();
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(95, buckets.get)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  state
                                      .getBucket()
                                      .bucketExists(StorageBucket.BucketSourceOptions.ifMetagenerationMatch());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(96, buckets.get)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest((ctx, c) -> ctx.map(state -> state.with(state.getBucket().refresh())))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(97, buckets.get)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .refresh(StorageBucket.BucketSourceOptions.ifMetagenerationMatch()))))
                .build());
      }

      private static void insert(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(14, buckets.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.bucketInfo))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> state.with(ctx.getStorage().create(state.getBucketInfo()))))
                .build());
      }

      private static void list(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(15, buckets.list)
                .withTest((ctx, c) -> ctx.map(state -> state.consume(ctx.getStorage().list())))
                .build());
      }

      private static void patch(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(17, buckets.patch)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.with(ctx.getStorage().update(state.getBucket()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(122, buckets.patch)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .update(
                                            state.getBucket(),
                                            Storage.BucketTargetOptions.withMetagenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(101, buckets.patch)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .updateBucket(Storage.BucketTargetOptions.withMetagenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(243, buckets.patch)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest((ctx, c) -> ctx.map(state -> state.with(state.getBucket().updateBucket())))
                .build());
      }

      private static void update(ArrayList<RpcMethodMapping> a) {}

      private static void getIamPolicy(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(13, buckets.getIamPolicy)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> state.with(ctx.getStorage().getIamPolicy(c.getBucketName()))))
                .build());
      }

      private static void lockRetentionPolicy(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(16, buckets.lockRetentionPolicy)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .lockRetentionPolicy(
                                            state.getBucket(),
                                            Storage.BucketTargetOptions.withMetagenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(100, buckets.lockRetentionPolicy)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .lockRetention(
                                            Storage.BucketTargetOptions.withMetagenerationMatch()))))
                .build());
      }

      private static void setIamPolicy(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(18, buckets.setIamPolicy)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .setIamPolicy(
                                            state.getBucket().getName(),
                                            Policy.newBuilder()
                                                .setVersion(3)
                                                .setBindings(
                                                    ImmutableList.of(
                                                        Binding.newBuilder()
                                                            .setRole(
                                                                StorageRoles.legacyBucketOwner()
                                                                    .toString())
                                                            .setMembers(
                                                                ImmutableList.of(
                                                                    Identity.projectOwner(
                                                                            c.getProjectId())
                                                                        .getValue()))
                                                            .build()))
                                                .build()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(240, buckets.setIamPolicy)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .setIamPolicy(
                                            state.getBucket().getName(),
                                            Policy.newBuilder()
                                                .setEtag("h??")
                                                .setVersion(3)
                                                .setBindings(
                                                    ImmutableList.of(
                                                        Binding.newBuilder()
                                                            .setRole(
                                                                StorageRoles.legacyBucketOwner()
                                                                    .toString())
                                                            .setMembers(
                                                                ImmutableList.of(
                                                                    Identity.projectOwner(
                                                                            c.getProjectId())
                                                                        .getValue()))
                                                            .build()))
                                                .build(),
                                            BucketSourceOptions.withUserProject(c.getProjectId())))))
                .build());
      }

      private static void testIamPermission(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(19, buckets.testIamPermissions)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.withTestIamPermissionsResults(
                                    ctx.getStorage()
                                        .testIamPermissions(
                                            c.getBucketName(),
                                            Collections.singletonList("todo: permissions")))))
                .build());
      }
    }

    static final class DefaultObjectAcl {

      private static void delete(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(20, default_object_acl.delete)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  ctx.getStorage()
                                      .deleteDefaultAcl(
                                          c.getBucketName(), state.getAcl().getEntity());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(102, default_object_acl.delete)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  state.getBucket().removeDefaultAcl(state.getAcl().getEntity());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
      }

      private static void get(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(21, default_object_acl.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .getDefaultAcl(
                                            c.getBucketName(), state.getAcl().getEntity()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(103, default_object_acl.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state.getBucket().getDefaultAcl(state.getAcl().getEntity()))))
                .build());
      }

      private static void insert(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(22, default_object_acl.insert)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createDefaultAcl(c.getBucketName(), state.getAcl()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(104, default_object_acl.insert)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(state.getBucket().addDefaultAcl(state.getAcl()))))
                .build());
      }

      private static void list(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(23, default_object_acl.list)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.withAcls(
                                    ctx.getStorage().listDefaultAcls(c.getBucketName()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(105, default_object_acl.list)
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.withAcls(state.getBucket().listDefaultAcl())))
                .build());
      }

      private static void patch(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(24, default_object_acl.patch)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .updateDefaultAcl(c.getBucketName(), state.getAcl()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(106, default_object_acl.patch)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(state.getBucket().updateDefaultAccessControl(state.getAcl()))))
                .build());
      }

      private static void update(ArrayList<RpcMethodMapping> a) {}
    }

    static final class HmacKey {

      private static void delete(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(26, hmacKey.delete)
                .withSetup(
                    defaultSetup.andThen(
                        (ctx, c) ->
                            ctx.map(
                                state -> {
                                  Storage storage = ctx.getStorage();
                                  HmacSecretKey.HmacKeyDetails metadata = state.getHmacKey().getMetadata();
                                  // for delete we're only using the metadata, clear the key that
                                  // was populated in defaultSetup and specify the updated metadata
                                  return state
                                      .withHmacKey(null)
                                      .with(
                                          storage.updateHmacKeyState(
                                              metadata, HmacSecretKey.HmacKeyStatus.INACTIVE));
                                })))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              ctx.getStorage().deleteHmacKey(state.getHmacKeyMetadata());
                              // clear the metadata from the state now that we've deleted it
                              return state.with((HmacSecretKey.HmacKeyDetails) null);
                            }))
                .build());
      }

      private static void get(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(27, hmacKey.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .getHmacKey(
                                            state.getHmacKey().getMetadata().getAccessId()))))
                .build());
      }

      private static void list(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(28, hmacKey.list)
                .withTest(
                    (ctx, c) -> ctx.map(state -> state.consume(ctx.getStorage().listHmacKeys())))
                .build());
      }

      private static void update(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(29, hmacKey.update)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .updateHmacKeyState(
                                            state.getHmacKey().getMetadata(),
                                            HmacSecretKey.HmacKeyStatus.ACTIVE))))
                .build());
      }

      private static void create(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(25, hmacKey.create)
                .withSetup(defaultSetup.andThen(serviceAccount))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.withHmacKey(
                                    ctx.getStorage().createHmacKey(state.getServiceAccount()))))
                .build());
      }
    }

    static final class Notification {

      private static void delete(ArrayList<RpcMethodMapping> a) {}

      private static void get(ArrayList<RpcMethodMapping> a) {}

      private static void insert(ArrayList<RpcMethodMapping> a) {}

      private static void list(ArrayList<RpcMethodMapping> a) {}
    }

    static final class ObjectAcl {

      private static void delete(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(30, object_acl.delete)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  ctx.getStorage()
                                      .deleteAcl(
                                          state.getBlob().getBlobId(), state.getAcl().getEntity());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(62, object_acl.delete)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  state.getBlob().deleteAclEntry(state.getAcl().getEntity());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
      }

      private static void get(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(31, object_acl.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .getAcl(
                                            state.getBlob().getBlobId(),
                                            state.getAcl().getEntity()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(63, object_acl.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(state.getBlob().getAcl(state.getAcl().getEntity()))))
                .build());
      }

      private static void insert(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(32, object_acl.insert)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createAcl(state.getBlob().getBlobId(), state.getAcl()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(64, object_acl.insert)
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.with(state.getBlob().createAclEntry(state.getAcl()))))
                .build());
      }

      private static void list(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(33, object_acl.list)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.withAcls(
                                    ctx.getStorage().listAcls(state.getBlob().getBlobId()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(65, object_acl.list)
                .withTest((ctx, c) -> ctx.map(state -> state.withAcls(state.getBlob().listAclEntries())))
                .build());
      }

      private static void patch(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(34, object_acl.patch)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .updateAcl(state.getBlob().getBlobId(), state.getAcl()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(66, object_acl.patch)
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.with(state.getBlob().updateAclEntry(state.getAcl()))))
                .build());
      }

      private static void update(ArrayList<RpcMethodMapping> a) {}
    }

    static final class Objects {

      private static void delete(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(36, objects.delete)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  ctx.getStorage().delete(state.getBlob().getBlobId());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(37, objects.delete)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  ctx.getStorage()
                                      .delete(
                                          state.getBlob().getBlobId(),
                                          Storage.BlobSourceOptions.ifGenerationMatch());
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(38, objects.delete)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              boolean success =
                                  ctx.getStorage()
                                      .delete(
                                          state.getBlob().getBlobId().getBucket(),
                                          state.getBlob().getBlobId().getName(),
                                          BlobSourceOptions.ifGenerationMatch(
                                              state.getBlob().getGeneration()));
                              assertTrue(success);
                              return state.with(success);
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(67, objects.delete)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest((ctx, c) -> ctx.peek(state -> state.getBlob().deleteFromStorage()))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(68, objects.delete)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state ->
                                state.getBlob().deleteFromStorage(StorageObject.BlobReadOption.ifGenerationMatch())))
                .build());
      }

      private static void get(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(39, objects.get)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> state.with(ctx.getStorage().get(state.getBlob().getBlobId()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(239, objects.get)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(state -> ctx.getStorage().get(state.getBlob().getBlobId())))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(40, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .get(
                                            state.getBlob().getBlobId(),
                                            Storage.BlobGetOptions.ifMetagenerationMatch(
                                                state.getBlob().getMetageneration())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(41, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .get(
                                            state.getBlob().getBlobId().getBucket(),
                                            state.getBlob().getBlobId().getName(),
                                            Storage.BlobGetOptions.ifMetagenerationMatch(
                                                state.getBlob().getMetageneration())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(42, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .readAllBytes(
                                            state.getBlob().getBlobId(),
                                            BlobSourceOptions.ifMetagenerationMatch(
                                                state.getBlob().getMetageneration())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(43, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .readAllBytes(
                                            state.getBlob().getBlobId().getBucket(),
                                            state.getBlob().getBlobId().getName(),
                                            BlobSourceOptions.ifMetagenerationMatch(
                                                state.getBlob().getMetageneration())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(44, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              try {
                                ReadChannel reader =
                                    ctx.getStorage().reader(ctx.getState().getBlob().getBlobId());
                                WritableByteChannel write =
                                    Channels.newChannel(ByteStreams.nullOutputStream());
                                ByteStreams.copy(reader, write);
                              } catch (IOException e) {
                                if (e.getCause() instanceof RetryHelperException) {
                                  RetryHelperException cause = (RetryHelperException) e.getCause();
                                  if (cause.getCause() instanceof BaseServiceException) {
                                    throw cause.getCause();
                                  }
                                }
                              }
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(45, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              try {
                                ReadChannel reader =
                                    ctx.getStorage()
                                        .reader(
                                            ctx.getState().getBlob().getBlobId().getBucket(),
                                            ctx.getState().getBlob().getBlobId().getName());
                                WritableByteChannel write =
                                    Channels.newChannel(ByteStreams.nullOutputStream());
                                ByteStreams.copy(reader, write);
                              } catch (IOException e) {
                                if (e.getCause() instanceof RetryHelperException) {
                                  RetryHelperException cause = (RetryHelperException) e.getCause();
                                  if (cause.getCause() instanceof BaseServiceException) {
                                    throw cause.getCause();
                                  }
                                }
                              }
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(60, objects.get)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest((ctx, c) -> ctx.peek(state -> assertTrue(state.getBlob().blobExists())))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(61, objects.get)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state ->
                                assertTrue(
                                    state
                                        .getBlob()
                                        .blobExists(StorageObject.BlobReadOption.ifGenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(69, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state ->
                                withTempFile(
                                    c.getMethod().getName(),
                                    ".txt",
                                    (tmpOutFile) -> {
                                      state.getBlob().downloadToPath(tmpOutFile);
                                      byte[] downloadedBytes = Files.readAllBytes(tmpOutFile);
                                      assertThat(downloadedBytes)
                                          .isEqualTo(c.getHelloWorldUtf8Bytes());
                                    })))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(70, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state ->
                                withTempFile(
                                    c.getMethod().getName(),
                                    ".txt",
                                    (tmpOutFile) -> {
                                      state
                                          .getBlob()
                                          .downloadToPath(
                                              tmpOutFile, StorageObject.BlobReadOption.ifGenerationMatch());
                                      byte[] downloadedBytes = Files.readAllBytes(tmpOutFile);
                                      assertThat(downloadedBytes)
                                          .isEqualTo(c.getHelloWorldUtf8Bytes());
                                    })))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(71, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              ByteArrayOutputStream baos = new ByteArrayOutputStream();
                              state.getBlob().downloadToPath(baos);
                              byte[] downloadedBytes = baos.toByteArray();
                              assertThat(downloadedBytes).isEqualTo(c.getHelloWorldUtf8Bytes());
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(72, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              ByteArrayOutputStream baos = new ByteArrayOutputStream();
                              state
                                  .getBlob()
                                  .downloadToPath(baos, StorageObject.BlobReadOption.ifGenerationMatch());
                              byte[] downloadedBytes = baos.toByteArray();
                              assertThat(downloadedBytes).isEqualTo(c.getHelloWorldUtf8Bytes());
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(73, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              byte[] downloadedBytes = state.getBlob().getContent();
                              assertThat(downloadedBytes).isEqualTo(c.getHelloWorldUtf8Bytes());
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(74, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              byte[] downloadedBytes =
                                  state
                                      .getBlob()
                                      .getContent(StorageObject.BlobReadOption.ifMetagenerationMatch());
                              assertThat(downloadedBytes).isEqualTo(c.getHelloWorldUtf8Bytes());
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(75, objects.get)
                .withTest((ctx, c) -> ctx.peek(state -> state.getBlob().reloadFromStorage()))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(76, objects.get)
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state ->
                                state
                                    .getBlob()
                                    .reloadFromStorage(StorageObject.BlobReadOption.ifMetagenerationMatch())))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(107, objects.get)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.with(state.getBucket().get(c.getObjectName()))))
                .build());
      }

      private static void insert(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(46, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withSetup(defaultSetup.andThen(Local.blobInfoWithGenerationZero))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .create(
                                            ctx.getState().getBlobInfo(),
                                            c.getHelloWorldUtf8Bytes(),
                                            BlobUploadOption.withGenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(47, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withSetup(defaultSetup.andThen(Local.blobInfoWithGenerationZero))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .create(
                                            ctx.getState().getBlobInfo(),
                                            c.getHelloWorldUtf8Bytes(),
                                            0,
                                            c.getHelloWorldUtf8Bytes().length / 2,
                                            BlobUploadOption.withGenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(48, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithGenerationZero))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .create(
                                            ctx.getState().getBlobInfo(),
                                            new ByteArrayInputStream(c.getHelloWorldUtf8Bytes()),
                                            BlobWriteOptions.withGenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(49, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withSetup(defaultSetup.andThen(Local.blobInfoWithGenerationZero))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createFrom(
                                            ctx.getState().getBlobInfo(),
                                            new ByteArrayInputStream(c.getHelloWorldUtf8Bytes()),
                                            BlobWriteOptions.withGenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(50, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withSetup(defaultSetup.andThen(Local.blobInfoWithGenerationZero))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createFrom(
                                            ctx.getState().getBlobInfo(),
                                            c.getHelloWorldFilePath(),
                                            BlobWriteOptions.withGenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(51, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withSetup(defaultSetup.andThen(Local.blobInfoWithGenerationZero))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createFrom(
                                            ctx.getState().getBlobInfo(),
                                            c.getHelloWorldFilePath(),
                                            _2MiB,
                                            BlobWriteOptions.withGenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(52, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              try (WriteChannel writer =
                                  ctx.getStorage().writer(ctx.getState().getBlobInfo())) {
                                writer.write(ByteBuffer.wrap(c.getHelloWorldUtf8Bytes()));
                              }
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(53, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withSetup(defaultSetup.andThen(Local.blobInfoWithGenerationZero))
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              try (WriteChannel writer =
                                  ctx.getStorage()
                                      .writer(
                                          ctx.getState().getBlobInfo(),
                                          BlobWriteOptions.withGenerationMatch())) {
                                writer.write(ByteBuffer.wrap(c.getHelloWorldUtf8Bytes()));
                              }
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(54, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              Storage storage = ctx.getStorage();
                              URL signedUrl =
                                  storage.signUrl(
                                      state.getBlobInfo(),
                                      1,
                                      TimeUnit.HOURS,
                                      Storage.UrlSigningOption.withHttpMethod(HttpRequestMethod.POST),
                                      // TODO(#1094): Instead of using bucketBoundHostname fix
                                      //   Signer to get BaseUri from StorageOptions
                                      // NOTE(frankyn/benwhitehead): testbench expects HTTP scheme
                                      // and we are using a hack to get around the lack of scheme
                                      // manipulation by using bucketBoundHostname to select HTTP
                                      // scheme instead. Bucket name is not present explicitly in
                                      // bucketBoundHostname because it's expected to be referred to
                                      // by the Bucket Bound Hostname so we must append it, being
                                      // the hack, to get around the limitation.
                                      Storage.UrlSigningOption.withBucketBoundHostname(
                                          c.getHost()
                                              + "/"
                                              + c.getBucketName()
                                              + "/"
                                              + c.getObjectName(),
                                          Storage.UriSchemeType.HTTP),
                                      Storage.UrlSigningOption.includeExtHeaders(
                                          ImmutableMap.of("x-goog-resumable", "start")),
                                      Storage.UrlSigningOption.withSigner(c.getServiceAccountSigner()),
                                      Storage.UrlSigningOption.useV4Signature());
                              try (WriteChannel writer = storage.writer(signedUrl)) {
                                writer.write(ByteBuffer.wrap(c.getHelloWorldUtf8Bytes()));
                              }
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(77, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(
                    defaultSetup
                        .andThen(Local.blobInfoWithoutGeneration)
                        .andThen(Rpc.createEmptyBlob))
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              try (WriteChannel writer = state.getBlob().newWriter()) {
                                writer.write(ByteBuffer.wrap(c.getHelloWorldUtf8Bytes()));
                              }
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(78, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withSetup(
                    defaultSetup
                        .andThen(Local.blobInfoWithoutGeneration)
                        .andThen(ResourceSetup.object))
                .withTest(
                    (ctx, c) ->
                        ctx.peek(
                            state -> {
                              try (WriteChannel writer =
                                  state.getBlob().newWriter(Storage.BlobWriteOptions.withGenerationMatch())) {
                                writer.write(ByteBuffer.wrap(c.getHelloWorldUtf8Bytes()));
                              }
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(108, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .createBlob(c.getObjectName(), c.getHelloWorldUtf8Bytes()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(109, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .createBlob(
                                            c.getObjectName(),
                                            c.getHelloWorldUtf8Bytes(),
                                            "text/plain);charset=utf-8"))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(110, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .createBlob(
                                            c.getObjectName(),
                                            new ByteArrayInputStream(c.getHelloWorldUtf8Bytes())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(111, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .createBlob(
                                            c.getObjectName(),
                                            new ByteArrayInputStream(c.getHelloWorldUtf8Bytes()),
                                            "text/plain);charset=utf-8"))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(112, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .create(
                                            ctx.getState().getBlobInfo(),
                                            c.getHelloWorldUtf8Bytes()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(113, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .create(
                                            ctx.getState().getBlobInfo(),
                                            c.getHelloWorldUtf8Bytes(),
                                            0,
                                            c.getHelloWorldUtf8Bytes().length / 2))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(114, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .create(
                                            ctx.getState().getBlobInfo(),
                                            new ByteArrayInputStream(c.getHelloWorldUtf8Bytes())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(115, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createFrom(
                                            ctx.getState().getBlobInfo(),
                                            new ByteArrayInputStream(c.getHelloWorldUtf8Bytes())))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(116, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createFrom(
                                            ctx.getState().getBlobInfo(),
                                            c.getHelloWorldFilePath()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(117, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobInfoWithoutGeneration))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .createFrom(
                                            ctx.getState().getBlobInfo(),
                                            c.getHelloWorldFilePath(),
                                            _2MiB))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(118, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .createBlob(
                                            c.getObjectName(),
                                            c.getHelloWorldUtf8Bytes(),
                                            StorageBucket.BlobTargetOptions.ifDoesNotExist()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(119, objects.insert)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .createBlob(
                                            c.getObjectName(),
                                            c.getHelloWorldUtf8Bytes(),
                                            "text/plain);charset=utf-8",
                                            StorageBucket.BlobTargetOptions.ifDoesNotExist()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(120, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .createBlob(
                                            c.getObjectName(),
                                            new ByteArrayInputStream(c.getHelloWorldUtf8Bytes()),
                                            StorageBucket.BlobWriteOptions.ifDoesNotExist()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(121, objects.insert)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBucket()
                                        .createBlob(
                                            c.getObjectName(),
                                            new ByteArrayInputStream(c.getHelloWorldUtf8Bytes()),
                                            "text/plain);charset=utf-8",
                                            StorageBucket.BlobWriteOptions.ifDoesNotExist()))))
                .build());
      }

      private static void list(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(55, objects.list)
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.consume(ctx.getStorage().list(c.getBucketName()))))
                .build());
      }

      private static void patch(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(56, objects.patch)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> state.with(ctx.getStorage().update(ctx.getState().getBlob()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(57, objects.patch)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    ctx.getStorage()
                                        .update(
                                            ctx.getState().getBlob(),
                                            BlobUploadOption.withMetagenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(79, objects.patch)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest((ctx, c) -> ctx.peek(state -> state.getBlob().updateInStorage()))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(80, objects.patch)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBlob()
                                        .updateInStorage(BlobUploadOption.withMetagenerationMatch()))))
                .build());
      }

      private static void update(ArrayList<RpcMethodMapping> a) {}

      private static void compose(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(35, objects.compose)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withSetup(defaultSetup.andThen(Local.composeRequest))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(ctx.getStorage().compose(state.getComposeRequest()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(241, objects.compose)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.composeRequest))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(ctx.getStorage().compose(state.getComposeRequest()))))
                .build());
      }

      private static void rewrite(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(58, objects.rewrite)
                .withApplicable(TestRetryConformance::isPreconditionsProvided)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              Storage.ChunkedCopyRequest copyRequest =
                                  Storage.ChunkedCopyRequest.newCopyOperationBuilder()
                                      .setSource(c.getBucketName(), c.getObjectName())
                                      .setTarget(
                                          BlobId.from(c.getBucketName(), "destination-blob"),
                                          Storage.BlobUploadOption.ifDoesNotExist())
                                      .buildCopyRequest();
                              return state.with(ctx.getStorage().copy(copyRequest));
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(242, objects.rewrite)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state -> {
                              Storage.ChunkedCopyRequest copyRequest =
                                  Storage.ChunkedCopyRequest.newCopyOperationBuilder()
                                      .setSource(c.getBucketName(), c.getObjectName())
                                      .setTarget(BlobId.from(c.getBucketName(), "destination-blob"))
                                      .buildCopyRequest();
                              return state.with(ctx.getStorage().copy(copyRequest));
                            }))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(81, objects.rewrite)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobCopy))
                .withTest(
                    (ctx, c) ->
                        ctx.map(state -> state.with(state.getBlob().copyToTarget(state.getCopyDest()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(82, objects.rewrite)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobCopy))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBlob()
                                        .copyToTarget(
                                            state.getCopyDest(),
                                            StorageObject.BlobReadOption.ifMetagenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(83, objects.rewrite)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobCopy))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state.getBlob().copyToTarget(state.getCopyDest().getBucket()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(84, objects.rewrite)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobCopy))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBlob()
                                        .copyToTarget(
                                            state.getCopyDest().getBucket(),
                                            StorageObject.BlobReadOption.ifMetagenerationMatch()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(85, objects.rewrite)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobCopy))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBlob()
                                        .copyToTarget(
                                            state.getCopyDest().getBucket(),
                                            state.getCopyDest().getName()))))
                .build());
        a.add(
            RpcMethodMapping.newBuilder(86, objects.rewrite)
                .withApplicable(not(TestRetryConformance::isPreconditionsProvided))
                .withSetup(defaultSetup.andThen(Local.blobCopy))
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(
                                    state
                                        .getBlob()
                                        .copyToTarget(
                                            state.getCopyDest().getBucket(),
                                            state.getCopyDest().getName(),
                                            StorageObject.BlobReadOption.ifMetagenerationMatch()))))
                .build());
      }

      private static void copy(ArrayList<RpcMethodMapping> a) {}
    }

    static final class ServiceAccount {

      private static void get(ArrayList<RpcMethodMapping> a) {
        a.add(
            RpcMethodMapping.newBuilder(59, serviceaccount.get)
                .withTest(
                    (ctx, c) ->
                        ctx.map(
                            state ->
                                state.with(ctx.getStorage().getServiceAccount(c.getUserProject()))))
                .build());
      }

      private static void put(ArrayList<RpcMethodMapping> a) {}
    }
  }
}
