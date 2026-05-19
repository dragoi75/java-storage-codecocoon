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

  private static final StorageService STORAGE =
      StorageSettings.newSettingsBuilder().setProjectId("p").build().getService();
  private static final AclEntry.DomainValue ACL_DOMAIN = new AclEntry.DomainValue("domain");
  private static final AclEntry.EmailGroup ACL_GROUP = new AclEntry.EmailGroup("group");
  private static final AclEntry.ProjectInfo ACL_PROJECT_ = new AclEntry.ProjectInfo(AclEntry.ProjectInfo.ProjectMemberRole.VIEWERS, "pid");
  private static final AclEntry.UserIdentity ACL_USER = new AclEntry.UserIdentity("user");
  private static final AclEntry.RawProtoEntity ACL_RAW = new AclEntry.RawProtoEntity("raw");
  private static final AclEntry ACL = AclEntry.ofEntry(ACL_DOMAIN, AclEntry.AccessRole.OWNER);
  private static final BlobMetadata BLOB_INFO = BlobMetadata.newBuilder("b", "n").buildObject();
  private static final BucketMetadata BUCKET_INFO = BucketMetadata.create("b");
  private static final CloudStorageObject BLOB = new CloudStorageObject(STORAGE, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
  private static final StorageBucket BUCKET = new StorageBucket(STORAGE, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO));
  private static final Cors.Origin ORIGIN = Cors.Origin.any();
  private static final Cors CORS =
      Cors.newBuilder().setMaxAgeSeconds(1).setOrigins(Collections.singleton(ORIGIN)).build();
  private static final PageImpl<CloudStorageObject> PAGE_RESULT =
      new PageImpl<>(null, "c", Collections.singletonList(BLOB));
  private static final StorageServiceException STORAGE_EXCEPTION = new StorageServiceException(42, "message");
  private static final StorageService.BlobListOptions BLOB_LIST_OPTIONS =
      StorageService.BlobListOptions.withPageSize(100);
  private static final StorageService.BlobReadOption BLOB_SOURCE_OPTIONS =
      StorageService.BlobReadOption.ifGenerationMatch(1);
  private static final StorageService.BlobUploadOption BLOB_TARGET_OPTIONS =
      StorageService.BlobUploadOption.ifGenerationMatch();
  private static final StorageService.BucketListOptions BUCKET_LIST_OPTIONS =
      StorageService.BucketListOptions.withPrefix("bla");
  private static final StorageService.BucketRequestOption BUCKET_SOURCE_OPTIONS =
      StorageService.BucketRequestOption.ifMetagenerationMatch(1);
  private static final StorageService.BucketTargetOptions BUCKET_TARGET_OPTIONS =
      StorageService.BucketTargetOptions.ifMetagenerationNotMatch();
  private static final Map<StorageRpcClient.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  @Override
  protected Serializable[] serializableObjects() {
    StorageSettings options =
        StorageSettings.newSettingsBuilder()
            .setProjectId("p1")
            .setCredentials(NoCredentials.getInstance())
            .build();
    StorageSettings otherOptions = options.toBuilder().setProjectId("p2").build();
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
    StorageSettings options = StorageSettings.newSettingsBuilder().setProjectId("p2").build();
    ReadChannel reader = new BlobInputChannel(options, BlobIdentifier.create("b", "n"), EMPTY_RPC_OPTIONS);
    // avoid closing when you don't want partial writes to GCS upon failure
    @SuppressWarnings("resource")
    ChunkedBlobWriter writer =
        new ChunkedBlobWriter(
            options, BlobMetadata.newBuilder(BlobIdentifier.create("b", "n")).buildObject(), "upload-id");
    return new Restorable<?>[] {reader, writer};
  }
}
