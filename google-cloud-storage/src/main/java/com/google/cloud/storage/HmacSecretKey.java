/*
 * Copyright 2019 Google LLC
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

import com.google.api.client.util.DateTime;
import java.io.Serializable;
import java.util.Objects;

/**
 * HMAC key for a service account.
 */
public class HmacSecretKey implements Serializable {

    private static final long serialVersionUID = -1809610424373783062L;

    private final String secretValue;

    private final HmacKeyDetails keyDetails;

    private HmacSecretKey(SecretMetadataBuilder secretMetadata) {
        this.secretValue = secretMetadata.secretValue;
        this.keyDetails = secretMetadata.keyDetails;
    }

    public static SecretMetadataBuilder newSecretBuilder(String secretValue) {
        return new SecretMetadataBuilder(secretValue);
    }

    /**
     * Builder for {@code HmacKey} objects. *
     */
    public static class SecretMetadataBuilder {

        private String secretValue;

        private HmacKeyDetails keyDetails;

        private SecretMetadataBuilder(String secretValue) {
            this.secretValue = secretValue;
        }

        public SecretMetadataBuilder setSecretKey(String secretValue) {
            this.secretValue = secretValue;
            return this;
        }

        public SecretMetadataBuilder setMetadata(HmacKeyDetails keyDetails) {
            this.keyDetails = keyDetails;
            return this;
        }

        /**
         * Creates an {@code HmacKey} object from this builder. *
         */
        public HmacSecretKey create() {
            return new HmacSecretKey(this);
        }
    }

    /**
     * Returns the secret key associated with this HMAC key. *
     */
    public String getSecretKey() {
        return secretValue;
    }

    /**
     * Returns the metadata associated with this HMAC key. *
     */
    public HmacKeyDetails getMetadata() {
        return keyDetails;
    }

    @Override
    public int hashCode() {
        return Objects.hash(secretValue, keyDetails);
    }

    @Override
    public boolean equals(Object otherObject) {
        if (otherObject == this) {
            return true;
        }
        if (null == otherObject || otherObject.getClass() != getClass()) {
            return false;
        }
        final HmacKeyDetails other = (HmacKeyDetails) otherObject;
        return Objects.equals(this.secretValue, secretValue) && Objects.equals(this.keyDetails, keyDetails);
    }

    com.google.api.services.storage.model.HmacKey toPb() {
        com.google.api.services.storage.model.HmacKey protoKey = new com.google.api.services.storage.model.HmacKey();
        protoKey.setSecret(this.secretValue);
        if (null != keyDetails) {
            protoKey.setMetadata(keyDetails.toProto());
        }
        return protoKey;
    }

    static HmacSecretKey fromProto(com.google.api.services.storage.model.HmacKey protoKey) {
        return HmacSecretKey.newSecretBuilder(protoKey.getSecret()).setMetadata(HmacKeyDetails.fromProto(protoKey.getMetadata())).create();
    }

    public enum HmacKeyStatus {

        ACTIVE("ACTIVE"), INACTIVE("INACTIVE"), DELETED("DELETED");

        private final String status;

        HmacKeyStatus(String status) {
            this.status = status;
        }
    }

    /**
     * The metadata for a service account HMAC key. This class holds all data associated with an HMAC
     * key other than the secret key.
     */
    public static class HmacKeyDetails implements Serializable {

        private static final long serialVersionUID = 4571684785352640737L;

        private final String accessIdentifier;

        private final String entityTag;

        private final String resourceIdentifier;

        private final String projectIdentifier;

        private final ServiceAccountInfo accountInfo;

        private final HmacKeyStatus status;

        private final Long createdAt;

        private final Long updatedAt;

        private HmacKeyDetails(ServiceAccountKeyBuilder secretMetadata) {
            this.accessIdentifier = secretMetadata.accessIdentifier;
            this.entityTag = secretMetadata.entityTag;
            this.resourceIdentifier = secretMetadata.resourceIdentifier;
            this.projectIdentifier = secretMetadata.projectIdentifier;
            this.accountInfo = secretMetadata.accountInfo;
            this.status = secretMetadata.status;
            this.createdAt = secretMetadata.createdAt;
            this.updatedAt = secretMetadata.updatedAt;
        }

        public static ServiceAccountKeyBuilder newServiceAccountBuilder(ServiceAccountInfo accountInfo) {
            return new ServiceAccountKeyBuilder(accountInfo);
        }

        public ServiceAccountKeyBuilder toBuilder() {
            return new ServiceAccountKeyBuilder(this);
        }

        public static HmacKeyDetails of(ServiceAccountInfo accountInfo, String accessIdentifier, String projectIdentifier) {
            return newServiceAccountBuilder(accountInfo).setAccessId(accessIdentifier).setProjectId(projectIdentifier).buildHmacKeyDetails();
        }

        @Override
        public int hashCode() {
            return Objects.hash(accessIdentifier, projectIdentifier);
        }

        @Override
        public boolean equals(Object otherObject) {
            if (otherObject == this) {
                return true;
            }
            if (null == otherObject || otherObject.getClass() != getClass()) {
                return false;
            }
            final HmacKeyDetails thatDetails = (HmacKeyDetails) otherObject;
            return Objects.equals(this.accessIdentifier, thatDetails.accessIdentifier) && Objects.equals(this.entityTag, thatDetails.entityTag) && Objects.equals(this.resourceIdentifier, thatDetails.resourceIdentifier) && Objects.equals(this.projectIdentifier, thatDetails.projectIdentifier) && Objects.equals(this.accountInfo, thatDetails.accountInfo) && Objects.equals(this.status, thatDetails.status) && Objects.equals(this.createdAt, thatDetails.createdAt) && Objects.equals(this.updatedAt, thatDetails.updatedAt);
        }

        public com.google.api.services.storage.model.HmacKeyMetadata toProto() {
            com.google.api.services.storage.model.HmacKeyMetadata keyDetails = new com.google.api.services.storage.model.HmacKeyMetadata();
            keyDetails.setAccessId(this.accessIdentifier);
            keyDetails.setEtag(this.entityTag);
            keyDetails.setId(this.resourceIdentifier);
            keyDetails.setProjectId(this.projectIdentifier);
            keyDetails.setServiceAccountEmail(null == this.accountInfo ? null : this.accountInfo.getEmail());
            keyDetails.setState(null == this.status ? null : this.status.toString());
            keyDetails.setTimeCreated(null == this.createdAt ? null : new DateTime(this.createdAt));
            keyDetails.setUpdated(null == this.updatedAt ? null : new DateTime(this.updatedAt));
            return keyDetails;
        }

        static HmacKeyDetails fromProto(com.google.api.services.storage.model.HmacKeyMetadata keyDetails) {
            return newServiceAccountBuilder(ServiceAccountInfo.from(keyDetails.getServiceAccountEmail())).setAccessId(keyDetails.getAccessId()).setCreateTime(keyDetails.getTimeCreated().getValue()).setEtag(keyDetails.getEtag()).setId(keyDetails.getId()).setProjectId(keyDetails.getProjectId()).setState(HmacKeyStatus.valueOf(keyDetails.getState())).setUpdateTime(keyDetails.getUpdated().getValue()).buildHmacKeyDetails();
        }

        /**
         * Returns the access id for this HMAC key. This is the id needed to get or delete the key. *
         */
        public String getAccessId() {
            return accessIdentifier;
        }

        /**
         * Returns HTTP 1.1 Entity tag for this HMAC key.
         *
         * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
         */
        public String getEtag() {
            return entityTag;
        }

        /**
         * Returns the resource name of this HMAC key. *
         */
        public String getId() {
            return resourceIdentifier;
        }

        /**
         * Returns the project id associated with this HMAC key. *
         */
        public String getProjectId() {
            return projectIdentifier;
        }

        /**
         * Returns the service account associated with this HMAC key. *
         */
        public ServiceAccountInfo getServiceAccount() {
            return accountInfo;
        }

        /**
         * Returns the current state of this HMAC key. *
         */
        public HmacKeyStatus getState() {
            return status;
        }

        /**
         * Returns the creation time of this HMAC key. *
         */
        public Long getCreateTime() {
            return createdAt;
        }

        /**
         * Returns the last updated time of this HMAC key. *
         */
        public Long getUpdateTime() {
            return updatedAt;
        }

        /**
         * Builder for {@code HmacKeyMetadata} objects. *
         */
        public static class ServiceAccountKeyBuilder {

            private String accessIdentifier;

            private String entityTag;

            private String resourceIdentifier;

            private String projectIdentifier;

            private ServiceAccountInfo accountInfo;

            private HmacKeyStatus status;

            private Long createdAt;

            private Long updatedAt;

            private ServiceAccountKeyBuilder(ServiceAccountInfo accountInfo) {
                this.accountInfo = accountInfo;
            }

            private ServiceAccountKeyBuilder(HmacKeyDetails keyDetails) {
                this.accessIdentifier = keyDetails.accessIdentifier;
                this.entityTag = keyDetails.entityTag;
                this.resourceIdentifier = keyDetails.resourceIdentifier;
                this.projectIdentifier = keyDetails.projectIdentifier;
                this.accountInfo = keyDetails.accountInfo;
                this.status = keyDetails.status;
                this.createdAt = keyDetails.createdAt;
                this.updatedAt = keyDetails.updatedAt;
            }

            public ServiceAccountKeyBuilder setAccessId(String accessIdentifier) {
                this.accessIdentifier = accessIdentifier;
                return this;
            }

            public ServiceAccountKeyBuilder setEtag(String entityTag) {
                this.entityTag = entityTag;
                return this;
            }

            public ServiceAccountKeyBuilder setId(String resourceIdentifier) {
                this.resourceIdentifier = resourceIdentifier;
                return this;
            }

            public ServiceAccountKeyBuilder setServiceAccount(ServiceAccountInfo accountInfo) {
                this.accountInfo = accountInfo;
                return this;
            }

            public ServiceAccountKeyBuilder setState(HmacKeyStatus status) {
                this.status = status;
                return this;
            }

            public ServiceAccountKeyBuilder setCreateTime(long createdAt) {
                this.createdAt = createdAt;
                return this;
            }

            public ServiceAccountKeyBuilder setProjectId(String projectIdentifier) {
                this.projectIdentifier = projectIdentifier;
                return this;
            }

            /**
             * Creates an {@code HmacKeyMetadata} object from this builder. *
             */
            public HmacKeyDetails buildHmacKeyDetails() {
                return new HmacKeyDetails(this);
            }

            public ServiceAccountKeyBuilder setUpdateTime(long updatedAt) {
                this.updatedAt = updatedAt;
                return this;
            }
        }
    }
}
