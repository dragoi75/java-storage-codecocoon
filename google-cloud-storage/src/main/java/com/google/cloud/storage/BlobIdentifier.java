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

    private final String objectIdentifier;

    private final Long version;

    /**
     * Creates a {@code BlobId} object.
     *
     * @param containerName name of the containing bucket
     * @param objectIdentifier blob's name
     * @param version blob's data generation, used for versioning. If {@code null} the identifier
     *     refers to the latest blob's generation
     */
    public static BlobIdentifier from(String containerName, String objectIdentifier, Long version) {
        return new BlobIdentifier(checkNotNull(containerName), checkNotNull(objectIdentifier), version);
    }

    /**
     * Creates a blob identifier. Generation is set to {@code null}.
     *
     * @param containerName the name of the bucket that contains the blob
     * @param objectIdentifier the name of the blob
     */
    public static BlobIdentifier from(String containerName, String objectIdentifier) {
        return new BlobIdentifier(checkNotNull(containerName), checkNotNull(objectIdentifier), null);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).toString();
    }

    StorageObject toProto() {
        StorageObject protoObject = new StorageObject();
        protoObject.setBucket(containerName);
        protoObject.setName(objectIdentifier);
        protoObject.setGeneration(version);
        return protoObject;
    }

    @Override
    public boolean equals(Object otherValue) {
        if (this == otherValue) {
            return true;
        }
        if (null == otherValue || !otherValue.getClass().equals(BlobIdentifier.class)) {
            return false;
        }
        BlobIdentifier thatIdentifier = (BlobIdentifier) otherValue;
        return Objects.equals(containerName, thatIdentifier.containerName) && Objects.equals(objectIdentifier, thatIdentifier.objectIdentifier) && Objects.equals(version, thatIdentifier.version);
    }

    /**
     * Returns blob's data generation. Used for versioning.
     */
    public Long getGeneration() {
        return version;
    }

    /**
     * Returns the name of the blob.
     */
    public String getName() {
        return objectIdentifier;
    }

    static BlobIdentifier fromProto(StorageObject protoObject) {
        return BlobIdentifier.from(protoObject.getBucket(), protoObject.getName(), protoObject.getGeneration());
    }

    /**
     * Returns the name of the bucket containing the blob.
     */
    public String getBucket() {
        return containerName;
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerName, objectIdentifier, version);
    }

    private BlobIdentifier(String containerName, String objectIdentifier, Long version) {
        this.containerName = containerName;
        this.objectIdentifier = objectIdentifier;
        this.version = version;
    }

}
