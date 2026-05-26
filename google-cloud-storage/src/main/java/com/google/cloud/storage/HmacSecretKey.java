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

    private final String keyMaterial;

    private final HmacKeyInfo keyInfo;

    /**
     * Builder for {@code HmacKey} objects. *
     */
    public static class SecretMetadataBuilder {

        private String keyMaterial;

        private HmacKeyInfo keyInfo;

        public SecretMetadataBuilder setMetadata(HmacKeyInfo keyInfo) {
            this.keyInfo = keyInfo;
            return this;
        }

        /**
         * Creates an {@code HmacKey} object from this builder. *
         */
        public HmacSecretKey buildHmacSecretKey() {
            return new HmacSecretKey(this);
        }

        public SecretMetadataBuilder setSecretKey(String keyMaterial) {
            this.keyMaterial = keyMaterial;
            return this;
        }

        private SecretMetadataBuilder(String keyMaterial) {
            this.keyMaterial = keyMaterial;
        }

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
    public static class HmacKeyInfo implements Serializable {

        private static final long serialVersionUID = 4571684785352640737L;

        private final String accessIdentifier;

        private final String entityTag;

        private final String identifier;

        private final String projectIdentifier;

        private final ServiceAccountIdentity serviceAcct;

        private final HmacKeyStatus status;

        private final Long createdAt;

        private final Long updatedAt;

        /**
         * Builder for {@code HmacKeyMetadata} objects. *
         */
        public static class ServiceAccountKeyBuilder {

            private String accessIdentifier;

            private String entityTag;

            private String identifier;

            private String projectIdentifier;

            private ServiceAccountIdentity serviceAcct;

            private HmacKeyStatus status;

            private Long createdAt;

            private Long updatedAt;

            public ServiceAccountKeyBuilder setEtag(String entityTag) {
                this.entityTag = entityTag;
                return this;
            }

            public ServiceAccountKeyBuilder setState(HmacKeyStatus status) {
                this.status = status;
                return this;
            }

            public ServiceAccountKeyBuilder setServiceAccount(ServiceAccountIdentity serviceAcct) {
                this.serviceAcct = serviceAcct;
                return this;
            }

            /**
             * Creates an {@code HmacKeyMetadata} object from this builder. *
             */
            public HmacKeyInfo buildHmacKeyInfo() {
                return new HmacKeyInfo(this);
            }

            public ServiceAccountKeyBuilder setProjectId(String projectIdentifier) {
                this.projectIdentifier = projectIdentifier;
                return this;
            }

            public ServiceAccountKeyBuilder setUpdateTime(long updatedAt) {
                this.updatedAt = updatedAt;
                return this;
            }

            public ServiceAccountKeyBuilder setAccessId(String accessIdentifier) {
                this.accessIdentifier = accessIdentifier;
                return this;
            }

            private ServiceAccountKeyBuilder(HmacKeyInfo keyInfo) {
                this.accessIdentifier = keyInfo.accessIdentifier;
                this.entityTag = keyInfo.entityTag;
                this.identifier = keyInfo.identifier;
                this.projectIdentifier = keyInfo.projectIdentifier;
                this.serviceAcct = keyInfo.serviceAcct;
                this.status = keyInfo.status;
                this.createdAt = keyInfo.createdAt;
                this.updatedAt = keyInfo.updatedAt;
            }

            private ServiceAccountKeyBuilder(ServiceAccountIdentity serviceAcct) {
                this.serviceAcct = serviceAcct;
            }

            public ServiceAccountKeyBuilder setId(String identifier) {
                this.identifier = identifier;
                return this;
            }

            public ServiceAccountKeyBuilder setCreateTime(long createdAt) {
                this.createdAt = createdAt;
                return this;
            }

        }

        /**
         * Returns the last updated time of this HMAC key. *
         */
        public Long getUpdateTime() {
            return updatedAt;
        }

        /**
         * Returns the creation time of this HMAC key. *
         */
        public Long getCreateTime() {
            return createdAt;
        }

        @Override
        public boolean equals(Object candidate) {
            if (candidate == this) {
                return true;
            }
            if (null == candidate || candidate.getClass() != getClass()) {
                return false;
            }
            final HmacKeyInfo rhsInfo = (HmacKeyInfo) candidate;
            return Objects.equals(this.accessIdentifier, rhsInfo.accessIdentifier) && Objects.equals(this.entityTag, rhsInfo.entityTag) && Objects.equals(this.identifier, rhsInfo.identifier) && Objects.equals(this.projectIdentifier, rhsInfo.projectIdentifier) && Objects.equals(this.serviceAcct, rhsInfo.serviceAcct) && Objects.equals(this.status, rhsInfo.status) && Objects.equals(this.createdAt, rhsInfo.createdAt) && Objects.equals(this.updatedAt, rhsInfo.updatedAt);
        }

        public ServiceAccountKeyBuilder toBuilder() {
            return new ServiceAccountKeyBuilder(this);
        }

        /**
         * Returns the service account associated with this HMAC key. *
         */
        public ServiceAccountIdentity getServiceAccount() {
            return serviceAcct;
        }

        /**
         * Returns the current state of this HMAC key. *
         */
        public HmacKeyStatus getState() {
            return status;
        }

        @Override
        public int hashCode() {
            return Objects.hash(accessIdentifier, projectIdentifier);
        }

        /**
         * Returns the project id associated with this HMAC key. *
         */
        public String getProjectId() {
            return projectIdentifier;
        }

        /**
         * Returns the resource name of this HMAC key. *
         */
        public String getId() {
            return identifier;
        }

        public static HmacKeyInfo of(ServiceAccountIdentity serviceAcct, String accessIdentifier, String projectIdentifier) {
            return newServiceAccountKeyBuilder(serviceAcct).setAccessId(accessIdentifier).setProjectId(projectIdentifier).buildHmacKeyInfo();
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

        private HmacKeyInfo(ServiceAccountKeyBuilder metadataFactory) {
            this.accessIdentifier = metadataFactory.accessIdentifier;
            this.entityTag = metadataFactory.entityTag;
            this.identifier = metadataFactory.identifier;
            this.projectIdentifier = metadataFactory.projectIdentifier;
            this.serviceAcct = metadataFactory.serviceAcct;
            this.status = metadataFactory.status;
            this.createdAt = metadataFactory.createdAt;
            this.updatedAt = metadataFactory.updatedAt;
        }

        public com.google.api.services.storage.model.HmacKeyMetadata toProto() {
            com.google.api.services.storage.model.HmacKeyMetadata keyInfo = new com.google.api.services.storage.model.HmacKeyMetadata();
            keyInfo.setAccessId(this.accessIdentifier);
            keyInfo.setEtag(this.entityTag);
            keyInfo.setId(this.identifier);
            keyInfo.setProjectId(this.projectIdentifier);
            keyInfo.setServiceAccountEmail(null == this.serviceAcct ? null : this.serviceAcct.getEmail());
            keyInfo.setState(null == this.status ? null : this.status.toString());
            keyInfo.setTimeCreated(null == this.createdAt ? null : new DateTime(this.createdAt));
            keyInfo.setUpdated(null == this.updatedAt ? null : new DateTime(this.updatedAt));
            return keyInfo;
        }

        static HmacKeyInfo fromProto(com.google.api.services.storage.model.HmacKeyMetadata keyInfo) {
            return newServiceAccountKeyBuilder(ServiceAccountIdentity.create(keyInfo.getServiceAccountEmail())).setAccessId(keyInfo.getAccessId()).setCreateTime(keyInfo.getTimeCreated().getValue()).setEtag(keyInfo.getEtag()).setId(keyInfo.getId()).setProjectId(keyInfo.getProjectId()).setState(HmacKeyStatus.valueOf(keyInfo.getState())).setUpdateTime(keyInfo.getUpdated().getValue()).buildHmacKeyInfo();
        }

        public static ServiceAccountKeyBuilder newServiceAccountKeyBuilder(ServiceAccountIdentity serviceAcct) {
            return new ServiceAccountKeyBuilder(serviceAcct);
        }

    }

    @Override
    public boolean equals(Object candidate) {
        if (candidate == this) {
            return true;
        }
        if (null == candidate || candidate.getClass() != getClass()) {
            return false;
        }
        final HmacKeyInfo other = (HmacKeyInfo) candidate;
        return Objects.equals(this.keyMaterial, keyMaterial) && Objects.equals(this.keyInfo, keyInfo);
    }

    /**
     * Returns the secret key associated with this HMAC key. *
     */
    public String getSecretKey() {
        return keyMaterial;
    }

    static HmacSecretKey fromProto(com.google.api.services.storage.model.HmacKey protoKey) {
        return HmacSecretKey.newSecretMetadataBuilder(protoKey.getSecret()).setMetadata(HmacKeyInfo.fromProto(protoKey.getMetadata())).buildHmacSecretKey();
    }

    com.google.api.services.storage.model.HmacKey toPb() {
        com.google.api.services.storage.model.HmacKey protoKey = new com.google.api.services.storage.model.HmacKey();
        protoKey.setSecret(this.keyMaterial);
        if (null != keyInfo) {
            protoKey.setMetadata(keyInfo.toProto());
        }
        return protoKey;
    }

    public static SecretMetadataBuilder newSecretMetadataBuilder(String keyMaterial) {
        return new SecretMetadataBuilder(keyMaterial);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyMaterial, keyInfo);
    }

    private HmacSecretKey(SecretMetadataBuilder metadataFactory) {
        this.keyMaterial = metadataFactory.keyMaterial;
        this.keyInfo = metadataFactory.keyInfo;
    }

    /**
     * Returns the metadata associated with this HMAC key. *
     */
    public HmacKeyInfo getMetadata() {
        return keyInfo;
    }

}
