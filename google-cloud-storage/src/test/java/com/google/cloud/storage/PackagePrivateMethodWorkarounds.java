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

package com.google.cloud.storage;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BucketMetadata.BucketBuilderImpl;
import java.util.Optional;
import java.util.function.Function;

/**
 * Several classes in the High Level Model for storage include package-local constructors and
 * methods. For conformance testing we don't want to exist in the com.google.cloud.storage package
 * to ensure we're interacting with the public api, however in a few select cases we need to change
 * the instance of {@link Storage} which an object holds on to. The utilities in this class allow us
 * to perform these operations.
 */
public final class PackagePrivateMethodWorkarounds {

  private PackagePrivateMethodWorkarounds() {}

  public static StorageBucket bucketCopyWithStorage(StorageBucket b, Storage s) {
    BucketMetadata.BucketBuilderImpl builder = (BucketBuilderImpl) BucketMetadata.fromProto(b.toProto()).asBuilder();
    return new StorageBucket(s, builder);
  }

  public static StorageObject blobCopyWithStorage(StorageObject b, Storage s) {
    BlobMetadata.BlobInfoBuilderImpl builder = (BlobMetadata.BlobInfoBuilderImpl) BlobMetadata.fromProto(b.toProto()).toInfoBuilder();
    return new StorageObject(s, builder);
  }

  public static Function<WriteChannel, Optional<com.google.api.services.storage.model.StorageObject>> maybeGetStorageObjectFunction() {
    return (w) -> {
      if (w instanceof BlobWriteChannel) {
        BlobWriteChannel blobWriteChannel = (BlobWriteChannel) w;
        return Optional.of(blobWriteChannel.getStorageObject());
      } else {
        return Optional.empty();
      }
    };
  }
}
