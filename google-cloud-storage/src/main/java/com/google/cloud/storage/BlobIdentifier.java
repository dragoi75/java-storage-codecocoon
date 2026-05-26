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

    private final Long versionNumber;

    @Override
    public int hashCode() {
        return Objects.hash(containerName, objectKey, versionNumber);
    }

    static BlobIdentifier fromProto(StorageObject storageEntry) {
        return BlobIdentifier.create(storageEntry.getBucket(), storageEntry.getName(), storageEntry.getGeneration());
    }

    /**
     * Creates a {@code BlobId} object.
     *
     * @param containerName name of the containing bucket
     * @param objectKey blob's name
     * @param versionNumber blob's data generation, used for versioning. If {@code null} the identifier
     *     refers to the latest blob's generation
     */
    public static BlobIdentifier create(String containerName, String objectKey, Long versionNumber) {
        return new BlobIdentifier(checkNotNull(containerName), checkNotNull(objectKey), versionNumber);
    }

    StorageObject toStorageObject() {
        StorageObject storageEntry = new StorageObject();
        storageEntry.setBucket(containerName);
        storageEntry.setName(objectKey);
        storageEntry.setGeneration(versionNumber);
        return storageEntry;
    }

    /**
     * Returns the name of the blob.
     */
    public String getName() {
        return objectKey;
    }

    private BlobIdentifier(String containerName, String objectKey, Long versionNumber) {
        this.containerName = containerName;
        this.objectKey = objectKey;
        this.versionNumber = versionNumber;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).toString();
    }

    /**
     * Returns blob's data generation. Used for versioning.
     */
    public Long getGeneration() {
        return versionNumber;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (null == other || !other.getClass().equals(BlobIdentifier.class)) {
            return false;
        }
        BlobIdentifier rhs = (BlobIdentifier) other;
        return Objects.equals(containerName, rhs.containerName) && Objects.equals(objectKey, rhs.objectKey) && Objects.equals(versionNumber, rhs.versionNumber);
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
     * Returns the name of the bucket containing the blob.
     */
    public String getBucket() {
        return containerName;
    }

}
