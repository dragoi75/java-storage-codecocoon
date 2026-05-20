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

import com.google.api.gax.retrying.ResultRetryAlgorithm;
import com.google.cloud.BaseSerializationTest;
import com.google.cloud.NoCredentials;
import com.google.cloud.PageImpl;
import com.google.cloud.ReadChannel;
import com.google.cloud.Restorable;
import com.google.cloud.storage.AccessControlEntry.ProjectInfo.ProjectMemberRole;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

public class SerializationTest extends BaseSerializationTest {

  private static final Storage STORAGE =
      StorageClientOptions.newStorageClientBuilder().setProjectId("p").build().getService();
  private static final AccessControlEntry.DomainValue ACL_DOMAIN = new AccessControlEntry.DomainValue("domain");
  private static final AccessControlEntry.EmailGroup ACL_GROUP = new AccessControlEntry.EmailGroup("group");
  private static final AccessControlEntry.ProjectInfo ACL_PROJECT_ = new AccessControlEntry.ProjectInfo(ProjectMemberRole.VIEWERS, "pid");
  private static final AccessControlEntry.UserIdentity ACL_USER = new AccessControlEntry.UserIdentity("user");
  private static final AccessControlEntry.RawDataEntity ACL_RAW = new AccessControlEntry.RawDataEntity("raw");
  private static final AccessControlEntry ACL = AccessControlEntry.create(ACL_DOMAIN, AccessControlEntry.RoleType.OWNER);
  private static final BlobMetadata BLOB_INFO = BlobMetadata.newBuilder("b", "n").buildObject();
  private static final BucketMetadata BUCKET_INFO = BucketMetadata.ofName("b");
  private static final StorageObject BLOB = new StorageObject(STORAGE, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO));
  private static final StorageBucket BUCKET = new StorageBucket(STORAGE, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO));
  private static final CorsConfiguration.OriginValue ORIGIN = CorsConfiguration.OriginValue.anyOrigin();
  private static final CorsConfiguration CORS =
      CorsConfiguration.newCorsConfigurationBuilder().setMaxAgeSeconds(1).setOrigins(Collections.singleton(ORIGIN)).buildCorsConfiguration();
  private static final PageImpl<StorageObject> PAGE_RESULT =
      new PageImpl<>(null, "c", Collections.singletonList(BLOB));
  private static final StorageServiceException STORAGE_EXCEPTION = new StorageServiceException(42, "message");
  private static final Storage.BlobListOptions BLOB_LIST_OPTIONS =
      Storage.BlobListOptions.withPageSize(100);
  private static final Storage.BlobSourceOptions BLOB_SOURCE_OPTIONS =
      Storage.BlobSourceOptions.ifGenerationMatch(1);
  private static final Storage.BlobUploadOption BLOB_TARGET_OPTIONS =
      Storage.BlobUploadOption.withGenerationMatch();
  private static final Storage.BucketListOptions BUCKET_LIST_OPTIONS =
      Storage.BucketListOptions.withPrefix("bla");
  private static final Storage.BucketSourceOptions BUCKET_SOURCE_OPTIONS =
      Storage.BucketSourceOptions.withMetagenerationMatch(1);
  private static final Storage.BucketTargetOptions BUCKET_TARGET_OPTIONS =
      Storage.BucketTargetOptions.withMetagenerationNotMatch();
  private static final Map<StorageRpcClient.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  @Override
  protected Serializable[] serializableObjects() {
    StorageClientOptions options =
        StorageClientOptions.newStorageClientBuilder()
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
    StorageClientOptions options = StorageClientOptions.newStorageClientBuilder().setProjectId("p2").build();
    ResultRetryAlgorithm<?> algorithm =
        options.getRetryAlgorithmManager().getForResumableUploadSessionWrite(EMPTY_RPC_OPTIONS);
    ReadChannel reader = new BlobReadChannel(options, BlobId.from("b", "n"), EMPTY_RPC_OPTIONS);
    // avoid closing when you don't want partial writes to GCS upon failure
    @SuppressWarnings("resource")
    BlobWriteChannel writer =
        new BlobWriteChannel(
            options, BlobMetadata.newBuilder(BlobId.from("b", "n")).buildObject(), "upload-id", algorithm);
    return new Restorable<?>[] {reader, writer};
  }
}
