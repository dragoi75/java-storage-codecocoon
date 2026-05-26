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

    private final String container;

    private final String objectKey;

    private final Long version;

    StorageObject toProto() {
        StorageObject proto = new StorageObject();
        proto.setBucket(container);
        proto.setName(objectKey);
        proto.setGeneration(version);
        return proto;
    }

    static BlobIdentifier fromProto(StorageObject proto) {
        return BlobIdentifier.create(proto.getBucket(), proto.getName(), proto.getGeneration());
    }

    @Override
    public boolean equals(Object candidateObject) {
        if (this == candidateObject) {
            return true;
        }
        if (null == candidateObject || !candidateObject.getClass().equals(BlobIdentifier.class)) {
            return false;
        }
        BlobIdentifier thatIdentifier = (BlobIdentifier) candidateObject;
        return Objects.equals(container, thatIdentifier.container) && Objects.equals(objectKey, thatIdentifier.objectKey) && Objects.equals(version, thatIdentifier.version);
    }

    /**
     * Creates a {@code BlobId} object.
     *
     * @param container name of the containing bucket
     * @param objectKey blob's name
     * @param version blob's data generation, used for versioning. If {@code null} the identifier
     *     refers to the latest blob's generation
     */
    public static BlobIdentifier create(String container, String objectKey, Long version) {
        return new BlobIdentifier(checkNotNull(container), checkNotNull(objectKey), version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(container, objectKey, version);
    }

    private BlobIdentifier(String container, String objectKey, Long version) {
        this.container = container;
        this.objectKey = objectKey;
        this.version = version;
    }

    /**
     * Creates a blob identifier. Generation is set to {@code null}.
     *
     * @param container the name of the bucket that contains the blob
     * @param objectKey the name of the blob
     */
    public static BlobIdentifier create(String container, String objectKey) {
        return new BlobIdentifier(checkNotNull(container), checkNotNull(objectKey), null);
    }

    /**
     * Returns the name of the blob.
     */
    public String getName() {
        return objectKey;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("bucket", getBucket()).add("name", getName()).add("generation", getGeneration()).toString();
    }

    /**
     * Returns the name of the bucket containing the blob.
     */
    public String getBucket() {
        return container;
    }

    /**
     * Returns blob's data generation. Used for versioning.
     */
    public Long getGeneration() {
        return version;
    }

}
