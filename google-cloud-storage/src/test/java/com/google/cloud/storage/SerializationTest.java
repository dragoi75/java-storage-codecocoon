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
import com.google.cloud.storage.AclEntry.ProjectInfo.ProjectRoleType;
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

public class SerializationTest extends BaseSerializationTest {

  private static final StorageClient STORAGE =
      StorageSettings.newStorageBuilder().setProjectId("p").build().getService();
  private static final AclEntry.DomainValue ACL_DOMAIN = new AclEntry.DomainValue("domain");
  private static final AclEntry.GroupEntity ACL_GROUP = new AclEntry.GroupEntity("group");
  private static final AclEntry.ProjectInfo ACL_PROJECT_ = new AclEntry.ProjectInfo(ProjectRoleType.VIEWERS, "pid");
  private static final AclEntry.UserPrincipal ACL_USER = new AclEntry.UserPrincipal("user");
  private static final AclEntry.RawDataEntity ACL_RAW = new AclEntry.RawDataEntity("raw");
  private static final AclEntry ACL = AclEntry.ofEntry(ACL_DOMAIN, AclEntry.AccessRole.OWNER);
  private static final BlobAttributes BLOB_INFO = BlobAttributes.newBuilder("b", "n").buildObject();
  private static final BucketInfo BUCKET_INFO = BucketInfo.ofName("b");
  private static final StorageObject BLOB = new StorageObject(STORAGE, new BlobAttributes.BlobInfoBuilderImpl(BLOB_INFO));
  private static final StorageBucket BUCKET = new StorageBucket(STORAGE, new BucketInfo.BucketBuilderImpl(BUCKET_INFO));
  private static final CorsConfiguration.Source ORIGIN = CorsConfiguration.Source.anySource();
  private static final CorsConfiguration CORS =
      CorsConfiguration.createBuilder().setMaxAgeSeconds(1).setOrigins(Collections.singleton(ORIGIN)).create();
  private static final PageImpl<StorageObject> PAGE_RESULT =
      new PageImpl<>(null, "c", Collections.singletonList(BLOB));
  private static final StorageOperationException STORAGE_EXCEPTION = new StorageOperationException(42, "message");
  private static final StorageClient.BlobListOptions BLOB_LIST_OPTIONS =
      StorageClient.BlobListOptions.maxResults(100);
  private static final StorageClient.BlobSourceOptions BLOB_SOURCE_OPTIONS =
      StorageClient.BlobSourceOptions.ifGenerationMatch(1);
  private static final StorageClient.BlobUploadOption BLOB_TARGET_OPTIONS =
      StorageClient.BlobUploadOption.withGenerationMatch();
  private static final StorageClient.BucketListOptions BUCKET_LIST_OPTIONS =
      StorageClient.BucketListOptions.withPrefix("bla");
  private static final StorageClient.BucketSourceRequestOption BUCKET_SOURCE_OPTIONS =
      StorageClient.BucketSourceRequestOption.withMetagenerationMatch(1);
  private static final StorageClient.BucketTargetOptions BUCKET_TARGET_OPTIONS =
      StorageClient.BucketTargetOptions.ifMetagenerationNotMatch();
  private static final Map<CloudStorageRpcClient.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  @Override
  protected Serializable[] serializableObjects() {
    StorageSettings options =
        StorageSettings.newStorageBuilder()
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
    StorageSettings options = StorageSettings.newStorageBuilder().setProjectId("p2").build();
    ReadChannel reader = new BlobReadChannel(options, BlobIdentifier.create("b", "n"), EMPTY_RPC_OPTIONS);
    // avoid closing when you don't want partial writes to GCS upon failure
    @SuppressWarnings("resource")
    BlobWriteChannel writer =
        new BlobWriteChannel(
            options, BlobAttributes.newBuilder(BlobIdentifier.create("b", "n")).buildObject(), "upload-id");
    return new Restorable<?>[] {reader, writer};
  }
}
