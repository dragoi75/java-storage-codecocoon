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

import com.google.cloud.BaseSerializationTest;
import com.google.cloud.NoCredentials;
import com.google.cloud.PageImpl;
import com.google.cloud.ReadChannel;
import com.google.cloud.Restorable;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

public class SerializationTest extends BaseSerializationTest {

  private static final CloudStorageClient STORAGE =
      StorageClientOptions.newServiceClientBuilder().setProjectId("p").build().getService();
  private static final AccessControlEntry.DomainEntry ACL_DOMAIN = new AccessControlEntry.DomainEntry("domain");
  private static final AccessControlEntry.GroupInfo ACL_GROUP = new AccessControlEntry.GroupInfo("group");
  private static final AccessControlEntry.ProjectInfo ACL_PROJECT_ = new AccessControlEntry.ProjectInfo(AccessControlEntry.ProjectInfo.ProjectRoleType.VIEWERS, "pid");
  private static final AccessControlEntry.UserIdentity ACL_USER = new AccessControlEntry.UserIdentity("user");
  private static final AccessControlEntry.RawEntityData ACL_RAW = new AccessControlEntry.RawEntityData("raw");
  private static final AccessControlEntry ACL = AccessControlEntry.create(ACL_DOMAIN, AccessControlEntry.UserRole.OWNER);
  private static final BlobMetadata BLOB_INFO = BlobMetadata.newBuilder("b", "n").buildMetadata();
  private static final BucketInfo BUCKET_INFO = BucketInfo.ofName("b");
  private static final StorageObject BLOB = new StorageObject(STORAGE, new BlobMetadata.BlobMetadataBuilderImpl(BLOB_INFO));
  private static final StorageBucket BUCKET = new StorageBucket(STORAGE, new BucketInfo.BucketBuilderImpl(BUCKET_INFO));
  private static final Cors.Origin ORIGIN = Cors.Origin.any();
  private static final Cors CORS =
      Cors.newBuilder().setMaxAgeSeconds(1).setOrigins(Collections.singleton(ORIGIN)).build();
  private static final PageImpl<StorageObject> PAGE_RESULT =
      new PageImpl<>(null, "c", Collections.singletonList(BLOB));
  private static final StorageServiceException STORAGE_EXCEPTION = new StorageServiceException(42, "message");
  private static final CloudStorageClient.BlobListOptions BLOB_LIST_OPTIONS =
      CloudStorageClient.BlobListOptions.withPageSize(100);
  private static final CloudStorageClient.BlobReadOption BLOB_SOURCE_OPTIONS =
      CloudStorageClient.BlobReadOption.ifGenerationMatch(1);
  private static final CloudStorageClient.BlobUploadOption BLOB_TARGET_OPTIONS =
      CloudStorageClient.BlobUploadOption.ifGenerationMatch();
  private static final CloudStorageClient.BucketListOptions BUCKET_LIST_OPTIONS =
      CloudStorageClient.BucketListOptions.withPrefix("bla");
  private static final CloudStorageClient.BucketOption BUCKET_SOURCE_OPTIONS =
      CloudStorageClient.BucketOption.ifMetagenerationMatch(1);
  private static final CloudStorageClient.BucketTargetOptions BUCKET_TARGET_OPTIONS =
      CloudStorageClient.BucketTargetOptions.ifMetagenerationNotMatch();
  private static final Map<StorageRpcClient.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  @Override
  protected Serializable[] serializableObjects() {
    StorageClientOptions options =
        StorageClientOptions.newServiceClientBuilder()
            .setProjectId("p1")
            .setCredentials(NoCredentials.getInstance())
            .build();
    StorageClientOptions otherOptions = options.toBuilder().setProjectId("p2").build();
    return new Serializable[] {
      ACL_DOMAIN,
      ACL_GROUP,
      ACL_PROJECT_,
      ACL_USER,
      ACL_RAW,
      ACL,
      BLOB_INFO,
      BLOB,
      BUCKET_INFO,
      BUCKET,
      ORIGIN,
      CORS,
      PAGE_RESULT,
      BLOB_LIST_OPTIONS,
      BLOB_SOURCE_OPTIONS,
      BLOB_TARGET_OPTIONS,
      BUCKET_LIST_OPTIONS,
      BUCKET_SOURCE_OPTIONS,
      BUCKET_TARGET_OPTIONS,
      STORAGE_EXCEPTION,
      options,
      otherOptions
    };
  }

  @Override
  protected Restorable<?>[] restorableObjects() {
    StorageClientOptions options = StorageClientOptions.newServiceClientBuilder().setProjectId("p2").build();
    ReadChannel reader = new BlobReadStream(options, BlobIdentifier.from("b", "n"), EMPTY_RPC_OPTIONS);
    // avoid closing when you don't want partial writes to GCS upon failure
    @SuppressWarnings("resource")
    BlobUploadChannel writer =
        new BlobUploadChannel(
            options, BlobMetadata.newBuilder(BlobIdentifier.from("b", "n")).buildMetadata(), "upload-id");
    return new Restorable<?>[] {reader, writer};
  }
}
