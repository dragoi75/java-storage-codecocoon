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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.services.storage.model.StorageObject;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Google Storage Object identifier. A {@code BlobId} object includes the name of the containing
 * bucket, the blob's name and possibly the blob's generation. If {@link #getGeneration()} is {@code
 * null} the identifier refers to the latest blob's generation.
 */
public final class BlobId implements Serializable {

  private static final long serialVersionUID = -6156002883225601925L;
  private final String containerName;
  private final String objectKey;
  private final Long version;

  private BlobId(String containerName, String objectKey, Long version) {
    this.containerName = containerName;
    this.objectKey = objectKey;
    this.version = version;
  }

  /** Returns the name of the bucket containing the blob. */
  public String getBucket() {
    return containerName;
  }

  /** Returns the name of the blob. */
  public String getName() {
    return objectKey;
  }

  /** Returns blob's data generation. Used for versioning. */
  public Long getGeneration() {
    return version;
  }

  /** Returns this blob's Storage url which can be used with gsutil */
  public String toGsUri() {
    return "gs://" + containerName + "/" + objectKey;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("bucket", getBucket())
        .add("name", getName())
        .add("generation", getGeneration())
        .toString();
  }

  @Override
  public int hashCode() {
    return Objects.hash(containerName, objectKey, version);
  }

  @Override
  public boolean equals(Object candidate) {
    if (candidate == this) {
      return true;
    }
    if (candidate == null || !candidate.getClass().equals(BlobId.class)) {
      return false;
    }
    BlobId rhsBlobId = (BlobId) candidate;
    return Objects.equals(containerName, rhsBlobId.containerName)
        && Objects.equals(objectKey, rhsBlobId.objectKey)
        && Objects.equals(version, rhsBlobId.version);
  }

  StorageObject toProto() {
    StorageObject protoObject = new StorageObject();
    protoObject.setBucket(containerName);
    protoObject.setName(objectKey);
    protoObject.setGeneration(version);
    return protoObject;
  }

  /**
   * Creates a blob identifier. Generation is set to {@code null}.
   *
   * @param containerName the name of the bucket that contains the blob
   * @param objectKey the name of the blob
   */
  public static BlobId from(String containerName, String objectKey) {
    return new BlobId(checkNotNull(containerName), checkNotNull(objectKey), null);
  }

  /**
   * Creates a {@code BlobId} object.
   *
   * @param containerName name of the containing bucket
   * @param objectKey blob's name
   * @param version blob's data generation, used for versioning. If {@code null} the identifier
   *     refers to the latest blob's generation
   */
  public static BlobId from(String containerName, String objectKey, Long version) {
    return new BlobId(checkNotNull(containerName), checkNotNull(objectKey), version);
  }

  /**
   * Creates a {@code BlobId} object.
   *
   * @param gsUri the Storage url to create the blob from
   */
  public static BlobId fromGsUri(String gsUri) {
    if (!Pattern.matches("gs://.*/.*", gsUri)) {
      throw new IllegalArgumentException(
          gsUri + " is not a valid gsutil URI (i.e. \"gs://bucket/blob\")");
    }
    int nameStartIndex = gsUri.indexOf('/', 5);
    String containerName = gsUri.substring(5, nameStartIndex);
    String objectKey = gsUri.substring(nameStartIndex + 1);

    return BlobId.from(containerName, objectKey);
  }

  static BlobId fromProto(StorageObject protoObject) {
    return BlobId.from(
        protoObject.getBucket(), protoObject.getName(), protoObject.getGeneration());
  }
}
