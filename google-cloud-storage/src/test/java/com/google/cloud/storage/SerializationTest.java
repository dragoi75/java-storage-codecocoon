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
import com.google.cloud.storage.AccessControlEntry.ProjectInfo.ProjectUserRole;
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

public class SerializationTest extends BaseSerializationTest {

  private static final CloudStorageClient STORAGE =
      StorageClientOptions.createBuilder().setProjectId("p").build().getService();
  private static final AccessControlEntry.DomainValue ACL_DOMAIN = new AccessControlEntry.DomainValue("domain");
  private static final AccessControlEntry.GroupInfo ACL_GROUP = new AccessControlEntry.GroupInfo("group");
  private static final AccessControlEntry.ProjectInfo ACL_PROJECT_ = new AccessControlEntry.ProjectInfo(ProjectUserRole.VIEWERS, "pid");
  private static final AccessControlEntry.UserPrincipal ACL_USER = new AccessControlEntry.UserPrincipal("user");
  private static final AccessControlEntry.RawDataEntity ACL_RAW = new AccessControlEntry.RawDataEntity("raw");
  private static final AccessControlEntry ACL = AccessControlEntry.create(ACL_DOMAIN, AccessControlEntry.UserRole.OWNER);
  private static final BlobMetadata BLOB_INFO = BlobMetadata.newBuilder("b", "n").buildObject();
  private static final BucketInfo BUCKET_INFO = BucketInfo.ofName("b");
  private static final StorageBlob BLOB = new StorageBlob(STORAGE, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
  private static final StorageBucket BUCKET = new StorageBucket(STORAGE, new BucketInfo.BucketBuilderImpl(BUCKET_INFO));
  private static final Cors.Origin ORIGIN = Cors.Origin.any();
  private static final Cors CORS =
      Cors.newBuilder().setMaxAgeSeconds(1).setOrigins(Collections.singleton(ORIGIN)).build();
  private static final PageImpl<StorageBlob> PAGE_RESULT =
      new PageImpl<>(null, "c", Collections.singletonList(BLOB));
  private static final StorageServiceException STORAGE_EXCEPTION = new StorageServiceException(42, "message");
  private static final CloudStorageClient.BlobListOptions BLOB_LIST_OPTIONS =
      CloudStorageClient.BlobListOptions.withPageSize(100);
  private static final CloudStorageClient.BlobReadOption BLOB_SOURCE_OPTIONS =
      CloudStorageClient.BlobReadOption.ifGenerationMatch(1);
  private static final CloudStorageClient.BlobUploadOptions BLOB_TARGET_OPTIONS =
      CloudStorageClient.BlobUploadOptions.ifGenerationMatch();
  private static final CloudStorageClient.BucketListOptions BUCKET_LIST_OPTIONS =
      CloudStorageClient.BucketListOptions.withPrefix("bla");
  private static final CloudStorageClient.BucketSourceRequestOption BUCKET_SOURCE_OPTIONS =
      CloudStorageClient.BucketSourceRequestOption.ifMetagenerationMatch(1);
  private static final CloudStorageClient.BucketTargetOptions BUCKET_TARGET_OPTIONS =
      CloudStorageClient.BucketTargetOptions.ifMetagenerationNotMatch();
  private static final Map<StorageServiceRpc.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  @Override
  protected Serializable[] serializableObjects() {
    StorageClientOptions options =
        StorageClientOptions.createBuilder()
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
    StorageClientOptions options = StorageClientOptions.createBuilder().setProjectId("p2").build();
    ReadChannel reader = new BlobInputChannel(options, BlobIdentifier.create("b", "n"), EMPTY_RPC_OPTIONS);
    // avoid closing when you don't want partial writes to GCS upon failure
    @SuppressWarnings("resource")
    BlobUploadChannel writer =
        new BlobUploadChannel(
            options, BlobMetadata.newBuilder(BlobIdentifier.create("b", "n")).buildObject(), "upload-id");
    return new Restorable<?>[] {reader, writer};
  }
}
