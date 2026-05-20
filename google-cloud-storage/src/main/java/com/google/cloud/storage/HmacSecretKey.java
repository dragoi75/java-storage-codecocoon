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

/** HMAC key for a service account. */
public class HmacSecretKey implements Serializable {

  private static final long serialVersionUID = -1809610424373783062L;
  private final String keyMaterial;
  private final HmacKeyInfo keyInfo;

  private HmacSecretKey(SecretBuilder secretFactory) {
    this.keyMaterial = secretFactory.keyMaterial;
    this.keyInfo = secretFactory.keyInfo;
  }

  public static SecretBuilder newSecretBuilder(String keyMaterial) {
    return new SecretBuilder(keyMaterial);
  }

  /** Builder for {@code HmacKey} objects. * */
  public static class SecretBuilder {
    private String keyMaterial;
    private HmacKeyInfo keyInfo;

    private SecretBuilder(String keyMaterial) {
      this.keyMaterial = keyMaterial;
    }

    public SecretBuilder setSecretKey(String keyMaterial) {
      this.keyMaterial = keyMaterial;
      return this;
    }

    public SecretBuilder setMetadata(HmacKeyInfo keyInfo) {
      this.keyInfo = keyInfo;
      return this;
    }

    /** Creates an {@code HmacKey} object from this builder. * */
    public HmacSecretKey create() {
      return new HmacSecretKey(this);
    }
  }

  /** Returns the secret key associated with this HMAC key. * */
  public String getSecretKey() {
    return keyMaterial;
  }

  /** Returns the metadata associated with this HMAC key. * */
  public HmacKeyInfo getMetadata() {
    return keyInfo;
  }

  @Override
  public int hashCode() {
    return Objects.hash(keyMaterial, keyInfo);
  }

  @Override
  public boolean equals(Object otherObject) {
    if (this == otherObject) {
      return true;
    }
    if (otherObject == null || getClass() != otherObject.getClass()) {
      return false;
    }
    final HmacKeyInfo other = (HmacKeyInfo) otherObject;
    return Objects.equals(this.keyMaterial, keyMaterial) && Objects.equals(this.keyInfo, keyInfo);
  }

  com.google.api.services.storage.model.HmacKey toPb() {
    com.google.api.services.storage.model.HmacKey protoKey =
        new com.google.api.services.storage.model.HmacKey();
    protoKey.setSecret(this.keyMaterial);

    if (keyInfo != null) {
      protoKey.setMetadata(keyInfo.toProto());
    }

    return protoKey;
  }

  static HmacSecretKey fromProto(com.google.api.services.storage.model.HmacKey protoKey) {
    return HmacSecretKey.newSecretBuilder(protoKey.getSecret())
        .setMetadata(HmacKeyInfo.fromProto(protoKey.getMetadata()))
        .create();
  }

  public enum HmacKeyStatus {
    ACTIVE("ACTIVE"),
    INACTIVE("INACTIVE"),
    DELETED("DELETED");

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
    private final ServiceAccountInfo svcAccount;
    private final HmacKeyStatus status;
    private final Long creationTime;
    private final Long lastUpdatedTime;

    private HmacKeyInfo(ServiceAccountKeyBuilder secretFactory) {
      this.accessIdentifier = secretFactory.accessIdentifier;
      this.entityTag = secretFactory.entityTag;
      this.identifier = secretFactory.identifier;
      this.projectIdentifier = secretFactory.projectIdentifier;
      this.svcAccount = secretFactory.svcAccount;
      this.status = secretFactory.status;
      this.creationTime = secretFactory.creationTime;
      this.lastUpdatedTime = secretFactory.lastUpdatedTime;
    }

    public static ServiceAccountKeyBuilder newServiceAccountBuilder(ServiceAccountInfo svcAccount) {
      return new ServiceAccountKeyBuilder(svcAccount);
    }

    public ServiceAccountKeyBuilder toBuilder() {
      return new ServiceAccountKeyBuilder(this);
    }

    public static HmacKeyInfo of(
            ServiceAccountInfo svcAccount, String accessIdentifier, String projectIdentifier) {
      return newServiceAccountBuilder(svcAccount).setAccessId(accessIdentifier).setProjectId(projectIdentifier).create();
    }

    @Override
    public int hashCode() {
      return Objects.hash(accessIdentifier, projectIdentifier);
    }

    @Override
    public boolean equals(Object otherObject) {
      if (this == otherObject) {
        return true;
      }
      if (otherObject == null || getClass() != otherObject.getClass()) {
        return false;
      }
      final HmacKeyInfo thatInfo = (HmacKeyInfo) otherObject;
      return Objects.equals(this.accessIdentifier, thatInfo.accessIdentifier)
          && Objects.equals(this.entityTag, thatInfo.entityTag)
          && Objects.equals(this.identifier, thatInfo.identifier)
          && Objects.equals(this.projectIdentifier, thatInfo.projectIdentifier)
          && Objects.equals(this.svcAccount, thatInfo.svcAccount)
          && Objects.equals(this.status, thatInfo.status)
          && Objects.equals(this.creationTime, thatInfo.creationTime)
          && Objects.equals(this.lastUpdatedTime, thatInfo.lastUpdatedTime);
    }

    public com.google.api.services.storage.model.HmacKeyMetadata toProto() {
      com.google.api.services.storage.model.HmacKeyMetadata keyInfo =
          new com.google.api.services.storage.model.HmacKeyMetadata();
      keyInfo.setAccessId(this.accessIdentifier);
      keyInfo.setEtag(this.entityTag);
      keyInfo.setId(this.identifier);
      keyInfo.setProjectId(this.projectIdentifier);
      keyInfo.setServiceAccountEmail(
          this.svcAccount == null ? null : this.svcAccount.getEmail());
      keyInfo.setState(this.status == null ? null : this.status.toString());
      keyInfo.setTimeCreated(this.creationTime == null ? null : new DateTime(this.creationTime));
      keyInfo.setUpdated(this.lastUpdatedTime == null ? null : new DateTime(this.lastUpdatedTime));

      return keyInfo;
    }

    static HmacKeyInfo fromProto(com.google.api.services.storage.model.HmacKeyMetadata keyInfo) {
      return newServiceAccountBuilder(ServiceAccountInfo.from(keyInfo.getServiceAccountEmail()))
          .setAccessId(keyInfo.getAccessId())
          .setCreateTime(keyInfo.getTimeCreated().getValue())
          .setEtag(keyInfo.getEtag())
          .setId(keyInfo.getId())
          .setProjectId(keyInfo.getProjectId())
          .setState(HmacKeyStatus.valueOf(keyInfo.getState()))
          .setUpdateTime(keyInfo.getUpdated().getValue())
          .create();
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

    /** Returns the resource name of this HMAC key. * */
    public String getId() {
      return identifier;
    }

    /** Returns the project id associated with this HMAC key. * */
    public String getProjectId() {
      return projectIdentifier;
    }

    /** Returns the service account associated with this HMAC key. * */
    public ServiceAccountInfo getServiceAccount() {
      return svcAccount;
    }

    /** Returns the current state of this HMAC key. * */
    public HmacKeyStatus getState() {
      return status;
    }

    /** Returns the creation time of this HMAC key. * */
    public Long getCreateTime() {
      return creationTime;
    }

    /** Returns the last updated time of this HMAC key. * */
    public Long getUpdateTime() {
      return lastUpdatedTime;
    }

    /** Builder for {@code HmacKeyMetadata} objects. * */
    public static class ServiceAccountKeyBuilder {
      private String accessIdentifier;
      private String entityTag;
      private String identifier;
      private String projectIdentifier;
      private ServiceAccountInfo svcAccount;
      private HmacKeyStatus status;
      private Long creationTime;
      private Long lastUpdatedTime;

      private ServiceAccountKeyBuilder(ServiceAccountInfo svcAccount) {
        this.svcAccount = svcAccount;
      }

      private ServiceAccountKeyBuilder(HmacKeyInfo keyInfo) {
        this.accessIdentifier = keyInfo.accessIdentifier;
        this.entityTag = keyInfo.entityTag;
        this.identifier = keyInfo.identifier;
        this.projectIdentifier = keyInfo.projectIdentifier;
        this.svcAccount = keyInfo.svcAccount;
        this.status = keyInfo.status;
        this.creationTime = keyInfo.creationTime;
        this.lastUpdatedTime = keyInfo.lastUpdatedTime;
      }

      public ServiceAccountKeyBuilder setAccessId(String accessIdentifier) {
        this.accessIdentifier = accessIdentifier;
        return this;
      }

      public ServiceAccountKeyBuilder setEtag(String entityTag) {
        this.entityTag = entityTag;
        return this;
      }

      public ServiceAccountKeyBuilder setId(String identifier) {
        this.identifier = identifier;
        return this;
      }

      public ServiceAccountKeyBuilder setServiceAccount(ServiceAccountInfo svcAccount) {
        this.svcAccount = svcAccount;
        return this;
      }

      public ServiceAccountKeyBuilder setState(HmacKeyStatus status) {
        this.status = status;
        return this;
      }

      public ServiceAccountKeyBuilder setCreateTime(long creationTime) {
        this.creationTime = creationTime;
        return this;
      }

      public ServiceAccountKeyBuilder setProjectId(String projectIdentifier) {
        this.projectIdentifier = projectIdentifier;
        return this;
      }

      /** Creates an {@code HmacKeyMetadata} object from this builder. * */
      public HmacKeyInfo create() {
        return new HmacKeyInfo(this);
      }

      public ServiceAccountKeyBuilder setUpdateTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
        return this;
      }
    }
  }
}
