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

/**
 * Google Storage Object identifier. A {@code BlobId} object includes the name of the containing
 * bucket, the blob's name and possibly the blob's generation. If {@link #getGeneration()} is {@code
 * null} the identifier refers to the latest blob's generation.
 */
public final class BlobIdentifier implements Serializable {

    private static final long serialVersionUID = -6156002883225601925L;

    private final String containerName;

    private final String objectKey;

    private final Long versionId;

    private BlobIdentifier(String containerName, String objectKey, Long versionId) {
        this.containerName = containerName;
        this.objectKey = objectKey;
        this.versionId = versionId;
    }

    /**
     * Returns the name of the bucket containing the blob.
     */
    public String getBucket() {
        return containerName;
    }

    /**
     * Returns the name of the blob.
     */
    public String getName() {
        return objectKey;
    }

    /**
     * Returns blob's data generation. Used for versioning.
     */
    public Long getGeneration() {
        return versionId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerName, objectKey, versionId);
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (null == candidate || !candidate.getClass().equals(BlobIdentifier.class)) {
            return false;
        }
        BlobIdentifier that = (BlobIdentifier) candidate;
        return Objects.equals(containerName, that.containerName) && Objects.equals(objectKey, that.objectKey) && Objects.equals(versionId, that.versionId);
    }

    StorageObject toStorageObject() {
        StorageObject result = new StorageObject();
        result.setBucket(containerName);
        result.setName(objectKey);
        result.setGeneration(versionId);
        return result;
    }

    /**
     * Creates a blob identifier. Generation is set to {@code null}.
     *
     * @param containerName the name of the bucket that contains the blob
     * @param objectKey the name of the blob
     */
    public static BlobIdentifier create(String containerName, String objectKey) {
        return new BlobIdentifier(checkNotNull(containerName), checkNotNull(objectKey), null);
    }

    /**
     * Creates a {@code BlobId} object.
     *
     * @param containerName name of the containing bucket
     * @param objectKey blob's name
     * @param versionId blob's data generation, used for versioning. If {@code null} the identifier
     *     refers to the latest blob's generation
     */
    public static BlobIdentifier create(String containerName, String objectKey, Long versionId) {
        return new BlobIdentifier(checkNotNull(containerName), checkNotNull(objectKey), versionId);
    }

    static BlobIdentifier fromStorageObject(StorageObject result) {
        return BlobIdentifier.create(result.getBucket(), result.getName(), result.getGeneration());
    }
}
