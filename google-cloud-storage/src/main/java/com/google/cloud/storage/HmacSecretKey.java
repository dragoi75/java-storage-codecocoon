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

    private final String hmacCredential;

    private final HmacKeyInfo keyDetails;

    /**
     * Builder for {@code HmacKey} objects. *
     */
    public static class SecretMetadataBuilder {

        private String hmacCredential;

        private HmacKeyInfo keyDetails;

        /**
         * Creates an {@code HmacKey} object from this builder. *
         */
        public HmacSecretKey buildMetadata() {
            return new HmacSecretKey(this);
        }

        public SecretMetadataBuilder setMetadata(HmacKeyInfo keyDetails) {
            this.keyDetails = keyDetails;
            return this;
        }

        public SecretMetadataBuilder setSecretKey(String hmacCredential) {
            this.hmacCredential = hmacCredential;
            return this;
        }

        private SecretMetadataBuilder(String hmacCredential) {
            this.hmacCredential = hmacCredential;
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

        private final ServiceAccountInfo serviceAcctInfo;

        private final HmacKeyStatus status;

        private final Long creationTime;

        private final Long lastUpdated;

        /**
         * Builder for {@code HmacKeyMetadata} objects. *
         */
        public static class ServiceAccountBuilder {

            private String accessIdentifier;

            private String entityTag;

            private String identifier;

            private String projectIdentifier;

            private ServiceAccountInfo serviceAcctInfo;

            private HmacKeyStatus status;

            private Long creationTime;

            private Long lastUpdated;

            public ServiceAccountBuilder setProjectId(String projectIdentifier) {
                this.projectIdentifier = projectIdentifier;
                return this;
            }

            private ServiceAccountBuilder(HmacKeyInfo keyDetails) {
                this.accessIdentifier = keyDetails.accessIdentifier;
                this.entityTag = keyDetails.entityTag;
                this.identifier = keyDetails.identifier;
                this.projectIdentifier = keyDetails.projectIdentifier;
                this.serviceAcctInfo = keyDetails.serviceAcctInfo;
                this.status = keyDetails.status;
                this.creationTime = keyDetails.creationTime;
                this.lastUpdated = keyDetails.lastUpdated;
            }

            public ServiceAccountBuilder setId(String identifier) {
                this.identifier = identifier;
                return this;
            }

            public ServiceAccountBuilder setState(HmacKeyStatus status) {
                this.status = status;
                return this;
            }

            public ServiceAccountBuilder setCreateTime(long creationTime) {
                this.creationTime = creationTime;
                return this;
            }

            private ServiceAccountBuilder(ServiceAccountInfo serviceAcctInfo) {
                this.serviceAcctInfo = serviceAcctInfo;
            }

            public ServiceAccountBuilder setUpdateTime(long lastUpdated) {
                this.lastUpdated = lastUpdated;
                return this;
            }

            public ServiceAccountBuilder setAccessId(String accessIdentifier) {
                this.accessIdentifier = accessIdentifier;
                return this;
            }

            public ServiceAccountBuilder setServiceAccount(ServiceAccountInfo serviceAcctInfo) {
                this.serviceAcctInfo = serviceAcctInfo;
                return this;
            }

            public ServiceAccountBuilder setEtag(String entityTag) {
                this.entityTag = entityTag;
                return this;
            }

            /**
             * Creates an {@code HmacKeyMetadata} object from this builder. *
             */
            public HmacKeyInfo buildInfo() {
                return new HmacKeyInfo(this);
            }

        }

        /**
         * Returns the creation time of this HMAC key. *
         */
        public Long getCreateTime() {
            return creationTime;
        }

        @Override
        public boolean equals(Object otherObject) {
            if (otherObject == this) {
                return true;
            }
            if (null == otherObject || otherObject.getClass() != getClass()) {
                return false;
            }
            final HmacKeyInfo comparedInfo = (HmacKeyInfo) otherObject;
            return Objects.equals(this.accessIdentifier, comparedInfo.accessIdentifier) && Objects.equals(this.entityTag, comparedInfo.entityTag) && Objects.equals(this.identifier, comparedInfo.identifier) && Objects.equals(this.projectIdentifier, comparedInfo.projectIdentifier) && Objects.equals(this.serviceAcctInfo, comparedInfo.serviceAcctInfo) && Objects.equals(this.status, comparedInfo.status) && Objects.equals(this.creationTime, comparedInfo.creationTime) && Objects.equals(this.lastUpdated, comparedInfo.lastUpdated);
        }

        /**
         * Returns the service account associated with this HMAC key. *
         */
        public ServiceAccountInfo getServiceAccount() {
            return serviceAcctInfo;
        }

        /**
         * Returns the access id for this HMAC key. This is the id needed to get or delete the key. *
         */
        public String getAccessId() {
            return accessIdentifier;
        }

        /**
         * Returns the current state of this HMAC key. *
         */
        public HmacKeyStatus getState() {
            return status;
        }

        /**
         * Returns the project id associated with this HMAC key. *
         */
        public String getProjectId() {
            return projectIdentifier;
        }

        public com.google.api.services.storage.model.HmacKeyMetadata toProto() {
            com.google.api.services.storage.model.HmacKeyMetadata keyDetails = new com.google.api.services.storage.model.HmacKeyMetadata();
            keyDetails.setAccessId(this.accessIdentifier);
            keyDetails.setEtag(this.entityTag);
            keyDetails.setId(this.identifier);
            keyDetails.setProjectId(this.projectIdentifier);
            keyDetails.setServiceAccountEmail(null == this.serviceAcctInfo ? null : this.serviceAcctInfo.getEmail());
            keyDetails.setState(null == this.status ? null : this.status.toString());
            keyDetails.setTimeCreated(null == this.creationTime ? null : new DateTime(this.creationTime));
            keyDetails.setUpdated(null == this.lastUpdated ? null : new DateTime(this.lastUpdated));
            return keyDetails;
        }

        /**
         * Returns HTTP 1.1 Entity tag for this HMAC key.
         *
         * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
         */
        public String getEtag() {
            return entityTag;
        }

        public static ServiceAccountBuilder createBuilder(ServiceAccountInfo serviceAcctInfo) {
            return new ServiceAccountBuilder(serviceAcctInfo);
        }

        static HmacKeyInfo fromProto(com.google.api.services.storage.model.HmacKeyMetadata keyDetails) {
            return createBuilder(ServiceAccountInfo.ofEmail(keyDetails.getServiceAccountEmail())).setAccessId(keyDetails.getAccessId()).setCreateTime(keyDetails.getTimeCreated().getValue()).setEtag(keyDetails.getEtag()).setId(keyDetails.getId()).setProjectId(keyDetails.getProjectId()).setState(HmacKeyStatus.valueOf(keyDetails.getState())).setUpdateTime(keyDetails.getUpdated().getValue()).buildInfo();
        }

        /**
         * Returns the last updated time of this HMAC key. *
         */
        public Long getUpdateTime() {
            return lastUpdated;
        }

        public ServiceAccountBuilder toBuilder() {
            return new ServiceAccountBuilder(this);
        }

        /**
         * Returns the resource name of this HMAC key. *
         */
        public String getId() {
            return identifier;
        }

        public static HmacKeyInfo of(ServiceAccountInfo serviceAcctInfo, String accessIdentifier, String projectIdentifier) {
            return createBuilder(serviceAcctInfo).setAccessId(accessIdentifier).setProjectId(projectIdentifier).buildInfo();
        }

        @Override
        public int hashCode() {
            return Objects.hash(accessIdentifier, projectIdentifier);
        }

        private HmacKeyInfo(ServiceAccountBuilder metadataCreator) {
            this.accessIdentifier = metadataCreator.accessIdentifier;
            this.entityTag = metadataCreator.entityTag;
            this.identifier = metadataCreator.identifier;
            this.projectIdentifier = metadataCreator.projectIdentifier;
            this.serviceAcctInfo = metadataCreator.serviceAcctInfo;
            this.status = metadataCreator.status;
            this.creationTime = metadataCreator.creationTime;
            this.lastUpdated = metadataCreator.lastUpdated;
        }

    }

    static HmacSecretKey fromProto(com.google.api.services.storage.model.HmacKey protoKey) {
        return HmacSecretKey.createBuilder(protoKey.getSecret()).setMetadata(HmacKeyInfo.fromProto(protoKey.getMetadata())).buildMetadata();
    }

    @Override
    public boolean equals(Object otherObject) {
        if (otherObject == this) {
            return true;
        }
        if (null == otherObject || otherObject.getClass() != getClass()) {
            return false;
        }
        final HmacKeyInfo other = (HmacKeyInfo) otherObject;
        return Objects.equals(this.hmacCredential, hmacCredential) && Objects.equals(this.keyDetails, keyDetails);
    }

    com.google.api.services.storage.model.HmacKey toPb() {
        com.google.api.services.storage.model.HmacKey protoKey = new com.google.api.services.storage.model.HmacKey();
        protoKey.setSecret(this.hmacCredential);
        if (null != keyDetails) {
            protoKey.setMetadata(keyDetails.toProto());
        }
        return protoKey;
    }

    /**
     * Returns the secret key associated with this HMAC key. *
     */
    public String getSecretKey() {
        return hmacCredential;
    }

    /**
     * Returns the metadata associated with this HMAC key. *
     */
    public HmacKeyInfo getMetadata() {
        return keyDetails;
    }

    private HmacSecretKey(SecretMetadataBuilder metadataCreator) {
        this.hmacCredential = metadataCreator.hmacCredential;
        this.keyDetails = metadataCreator.keyDetails;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hmacCredential, keyDetails);
    }

    public static SecretMetadataBuilder createBuilder(String hmacCredential) {
        return new SecretMetadataBuilder(hmacCredential);
    }

}
