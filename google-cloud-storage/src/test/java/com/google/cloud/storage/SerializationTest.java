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
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

public class SerializationTest extends BaseSerializationTest {

  private static final StorageClient STORAGE =
      StorageSettings.newClientBuilder().setProjectId("p").build().getService();
  private static final AccessControlEntry.DomainValue ACL_DOMAIN = new AccessControlEntry.DomainValue("domain");
  private static final AccessControlEntry.MailingGroup ACL_GROUP = new AccessControlEntry.MailingGroup("group");
  private static final AccessControlEntry.ProjectEntity ACL_PROJECT_ = new AccessControlEntry.ProjectEntity(AccessControlEntry.ProjectEntity.ProjectMemberRole.VIEWERS, "pid");
  private static final AccessControlEntry.UserPrincipal ACL_USER = new AccessControlEntry.UserPrincipal("user");
  private static final AccessControlEntry.RawEntityWrapper ACL_RAW = new AccessControlEntry.RawEntityWrapper("raw");
  private static final AccessControlEntry ACL = AccessControlEntry.create(ACL_DOMAIN, AccessControlEntry.AccessRole.OWNER);
  private static final BlobMetadata BLOB_INFO = BlobMetadata.newBuilder("b", "n").buildStorageObject();
  private static final BucketMetadata BUCKET_INFO = BucketMetadata.ofName("b");
  private static final StorageBlob BLOB = new StorageBlob(STORAGE, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
  private static final StorageBucket BUCKET = new StorageBucket(STORAGE, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO));
  private static final Cors.Origin ORIGIN = Cors.Origin.any();
  private static final Cors CORS =
      Cors.newBuilder().setMaxAgeSeconds(1).setOrigins(Collections.singleton(ORIGIN)).build();
  private static final PageImpl<StorageBlob> PAGE_RESULT =
      new PageImpl<>(null, "c", Collections.singletonList(BLOB));
  private static final StorageOperationException STORAGE_EXCEPTION = new StorageOperationException(42, "message");
  private static final StorageClient.BlobListOptions BLOB_LIST_OPTIONS =
      StorageClient.BlobListOptions.maxResults(100);
  private static final StorageClient.BlobSourceOptions BLOB_SOURCE_OPTIONS =
      StorageClient.BlobSourceOptions.ifGenerationMatch(1);
  private static final StorageClient.BlobUploadOption BLOB_TARGET_OPTIONS =
      StorageClient.BlobUploadOption.ifGenerationMatch();
  private static final StorageClient.BucketListOptions BUCKET_LIST_OPTIONS =
      StorageClient.BucketListOptions.namePrefix("bla");
  private static final StorageClient.BucketSourceOptions BUCKET_SOURCE_OPTIONS =
      StorageClient.BucketSourceOptions.withMetagenerationMatch(1);
  private static final StorageClient.BucketTargetRequestOption BUCKET_TARGET_OPTIONS =
      StorageClient.BucketTargetRequestOption.ifMetagenerationNotMatch();
  private static final Map<CloudStorageRpc.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  @Override
  protected Serializable[] serializableObjects() {
    StorageSettings options =
        StorageSettings.newClientBuilder()
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
    StorageSettings options = StorageSettings.newClientBuilder().setProjectId("p2").build();
    ReadChannel reader = new BlobReadStream(options, BlobIdentifier.create("b", "n"), EMPTY_RPC_OPTIONS);
    // avoid closing when you don't want partial writes to GCS upon failure
    @SuppressWarnings("resource")
    BlobUploadChannel writer =
        new BlobUploadChannel(
            options, BlobMetadata.newBuilder(BlobIdentifier.create("b", "n")).buildStorageObject(), "upload-id");
    return new Restorable<?>[] {reader, writer};
  }
}
