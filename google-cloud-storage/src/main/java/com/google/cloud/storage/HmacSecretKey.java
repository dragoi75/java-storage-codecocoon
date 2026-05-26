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

    private final HmacKeyInfo keyInfo;

    /**
     * Builder for {@code HmacKey} objects. *
     */
    public static class SecureDataBuilder {

        private String secretValue;

        private HmacKeyInfo keyInfo;

        public SecureDataBuilder setMetadata(HmacKeyInfo keyInfo) {
            this.keyInfo = keyInfo;
            return this;
        }

        /**
         * Creates an {@code HmacKey} object from this builder. *
         */
        public HmacSecretKey buildHmacSecretKey() {
            return new HmacSecretKey(this);
        }

        private SecureDataBuilder(String secretValue) {
            this.secretValue = secretValue;
        }

        public SecureDataBuilder setSecretKey(String secretValue) {
            this.secretValue = secretValue;
            return this;
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

        private final ServiceAccountInfo accountInfo;

        private final HmacKeyStatus status;

        private final Long creationTimestamp;

        private final Long modifiedTimestamp;

        /**
         * Builder for {@code HmacKeyMetadata} objects. *
         */
        public static class HmacKeyBuilder {

            private String accessIdentifier;

            private String entityTag;

            private String identifier;

            private String projectIdentifier;

            private ServiceAccountInfo accountInfo;

            private HmacKeyStatus status;

            private Long creationTimestamp;

            private Long modifiedTimestamp;

            /**
             * Creates an {@code HmacKeyMetadata} object from this builder. *
             */
            public HmacKeyInfo buildHmacKeyInfo() {
                return new HmacKeyInfo(this);
            }

            public HmacKeyBuilder setProjectId(String projectIdentifier) {
                this.projectIdentifier = projectIdentifier;
                return this;
            }

            public HmacKeyBuilder setServiceAccount(ServiceAccountInfo accountInfo) {
                this.accountInfo = accountInfo;
                return this;
            }

            public HmacKeyBuilder setCreateTime(long creationTimestamp) {
                this.creationTimestamp = creationTimestamp;
                return this;
            }

            public HmacKeyBuilder setEtag(String entityTag) {
                this.entityTag = entityTag;
                return this;
            }

            private HmacKeyBuilder(ServiceAccountInfo accountInfo) {
                this.accountInfo = accountInfo;
            }

            public HmacKeyBuilder setState(HmacKeyStatus status) {
                this.status = status;
                return this;
            }

            public HmacKeyBuilder setId(String identifier) {
                this.identifier = identifier;
                return this;
            }

            public HmacKeyBuilder setUpdateTime(long modifiedTimestamp) {
                this.modifiedTimestamp = modifiedTimestamp;
                return this;
            }

            private HmacKeyBuilder(HmacKeyInfo keyInfo) {
                this.accessIdentifier = keyInfo.accessIdentifier;
                this.entityTag = keyInfo.entityTag;
                this.identifier = keyInfo.identifier;
                this.projectIdentifier = keyInfo.projectIdentifier;
                this.accountInfo = keyInfo.accountInfo;
                this.status = keyInfo.status;
                this.creationTimestamp = keyInfo.creationTimestamp;
                this.modifiedTimestamp = keyInfo.modifiedTimestamp;
            }

            public HmacKeyBuilder setAccessId(String accessIdentifier) {
                this.accessIdentifier = accessIdentifier;
                return this;
            }

        }

        /**
         * Returns HTTP 1.1 Entity tag for this HMAC key.
         *
         * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
         */
        public String getEtag() {
            return entityTag;
        }

        public com.google.api.services.storage.model.HmacKeyMetadata toProto() {
            com.google.api.services.storage.model.HmacKeyMetadata keyInfo = new com.google.api.services.storage.model.HmacKeyMetadata();
            keyInfo.setAccessId(this.accessIdentifier);
            keyInfo.setEtag(this.entityTag);
            keyInfo.setId(this.identifier);
            keyInfo.setProjectId(this.projectIdentifier);
            keyInfo.setServiceAccountEmail(null == this.accountInfo ? null : this.accountInfo.getEmail());
            keyInfo.setState(null == this.status ? null : this.status.toString());
            keyInfo.setTimeCreated(null == this.creationTimestamp ? null : new DateTime(this.creationTimestamp));
            keyInfo.setUpdated(null == this.modifiedTimestamp ? null : new DateTime(this.modifiedTimestamp));
            return keyInfo;
        }

        /**
         * Returns the service account associated with this HMAC key. *
         */
        public ServiceAccountInfo getServiceAccount() {
            return accountInfo;
        }

        /**
         * Returns the creation time of this HMAC key. *
         */
        public Long getCreateTime() {
            return creationTimestamp;
        }

        /**
         * Returns the resource name of this HMAC key. *
         */
        public String getId() {
            return identifier;
        }

        /**
         * Returns the access id for this HMAC key. This is the id needed to get or delete the key. *
         */
        public String getAccessId() {
            return accessIdentifier;
        }

        /**
         * Returns the project id associated with this HMAC key. *
         */
        public String getProjectId() {
            return projectIdentifier;
        }

        /**
         * Returns the current state of this HMAC key. *
         */
        public HmacKeyStatus getState() {
            return status;
        }

        @Override
        public boolean equals(Object otherObject) {
            if (otherObject == this) {
                return true;
            }
            if (null == otherObject || otherObject.getClass() != getClass()) {
                return false;
            }
            final HmacKeyInfo thatKeyInfo = (HmacKeyInfo) otherObject;
            return Objects.equals(this.accessIdentifier, thatKeyInfo.accessIdentifier) && Objects.equals(this.entityTag, thatKeyInfo.entityTag) && Objects.equals(this.identifier, thatKeyInfo.identifier) && Objects.equals(this.projectIdentifier, thatKeyInfo.projectIdentifier) && Objects.equals(this.accountInfo, thatKeyInfo.accountInfo) && Objects.equals(this.status, thatKeyInfo.status) && Objects.equals(this.creationTimestamp, thatKeyInfo.creationTimestamp) && Objects.equals(this.modifiedTimestamp, thatKeyInfo.modifiedTimestamp);
        }

        /**
         * Returns the last updated time of this HMAC key. *
         */
        public Long getUpdateTime() {
            return modifiedTimestamp;
        }

        public static HmacKeyInfo of(ServiceAccountInfo accountInfo, String accessIdentifier, String projectIdentifier) {
            return newHmacKeyBuilder(accountInfo).setAccessId(accessIdentifier).setProjectId(projectIdentifier).buildHmacKeyInfo();
        }

        private HmacKeyInfo(HmacKeyBuilder secureFactory) {
            this.accessIdentifier = secureFactory.accessIdentifier;
            this.entityTag = secureFactory.entityTag;
            this.identifier = secureFactory.identifier;
            this.projectIdentifier = secureFactory.projectIdentifier;
            this.accountInfo = secureFactory.accountInfo;
            this.status = secureFactory.status;
            this.creationTimestamp = secureFactory.creationTimestamp;
            this.modifiedTimestamp = secureFactory.modifiedTimestamp;
        }

        public static HmacKeyBuilder newHmacKeyBuilder(ServiceAccountInfo accountInfo) {
            return new HmacKeyBuilder(accountInfo);
        }

        public HmacKeyBuilder toBuilder() {
            return new HmacKeyBuilder(this);
        }

        static HmacKeyInfo fromProto(com.google.api.services.storage.model.HmacKeyMetadata keyInfo) {
            return newHmacKeyBuilder(ServiceAccountInfo.ofEmail(keyInfo.getServiceAccountEmail())).setAccessId(keyInfo.getAccessId()).setCreateTime(keyInfo.getTimeCreated().getValue()).setEtag(keyInfo.getEtag()).setId(keyInfo.getId()).setProjectId(keyInfo.getProjectId()).setState(HmacKeyStatus.valueOf(keyInfo.getState())).setUpdateTime(keyInfo.getUpdated().getValue()).buildHmacKeyInfo();
        }

        @Override
        public int hashCode() {
            return Objects.hash(accessIdentifier, projectIdentifier);
        }

    }

    static HmacSecretKey fromProto(com.google.api.services.storage.model.HmacKey keyProto) {
        return HmacSecretKey.newHmacSecretKeyBuilder(keyProto.getSecret()).setMetadata(HmacKeyInfo.fromProto(keyProto.getMetadata())).buildHmacSecretKey();
    }

    com.google.api.services.storage.model.HmacKey toPb() {
        com.google.api.services.storage.model.HmacKey keyProto = new com.google.api.services.storage.model.HmacKey();
        keyProto.setSecret(this.secretValue);
        if (null != keyInfo) {
            keyProto.setMetadata(keyInfo.toProto());
        }
        return keyProto;
    }

    public static SecureDataBuilder newHmacSecretKeyBuilder(String secretValue) {
        return new SecureDataBuilder(secretValue);
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
        return Objects.equals(this.secretValue, secretValue) && Objects.equals(this.keyInfo, keyInfo);
    }

    /**
     * Returns the metadata associated with this HMAC key. *
     */
    public HmacKeyInfo getMetadata() {
        return keyInfo;
    }

    @Override
    public int hashCode() {
        return Objects.hash(secretValue, keyInfo);
    }

    /**
     * Returns the secret key associated with this HMAC key. *
     */
    public String getSecretKey() {
        return secretValue;
    }

    private HmacSecretKey(SecureDataBuilder secureFactory) {
        this.secretValue = secureFactory.secretValue;
        this.keyInfo = secureFactory.keyInfo;
    }

}
