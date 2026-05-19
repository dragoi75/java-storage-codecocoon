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

  private HmacSecretKey(SecretKeyMetadataBuilder metadataFactory) {
    this.keyMaterial = metadataFactory.keyMaterial;
    this.keyInfo = metadataFactory.keyInfo;
  }

  public static SecretKeyMetadataBuilder builder(String keyMaterial) {
    return new SecretKeyMetadataBuilder(keyMaterial);
  }

  /** Builder for {@code HmacKey} objects. * */
  public static class SecretKeyMetadataBuilder {
    private String keyMaterial;
    private HmacKeyInfo keyInfo;

    private SecretKeyMetadataBuilder(String keyMaterial) {
      this.keyMaterial = keyMaterial;
    }

    public SecretKeyMetadataBuilder setSecretKey(String keyMaterial) {
      this.keyMaterial = keyMaterial;
      return this;
    }

    public SecretKeyMetadataBuilder setMetadata(HmacKeyInfo keyInfo) {
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
    return HmacSecretKey.builder(protoKey.getSecret())
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
    private final ServiceAccountInfo accountInfo;
    private final HmacKeyStatus status;
    private final Long creationTime;
    private final Long lastUpdated;

    private HmacKeyInfo(ResourceBuilder metadataFactory) {
      this.accessIdentifier = metadataFactory.accessIdentifier;
      this.entityTag = metadataFactory.entityTag;
      this.identifier = metadataFactory.identifier;
      this.projectIdentifier = metadataFactory.projectIdentifier;
      this.accountInfo = metadataFactory.accountInfo;
      this.status = metadataFactory.status;
      this.creationTime = metadataFactory.creationTime;
      this.lastUpdated = metadataFactory.lastUpdated;
    }

    public static ResourceBuilder builder(ServiceAccountInfo accountInfo) {
      return new ResourceBuilder(accountInfo);
    }

    public ResourceBuilder toBuilder() {
      return new ResourceBuilder(this);
    }

    public static HmacKeyInfo of(
            ServiceAccountInfo accountInfo, String accessIdentifier, String projectIdentifier) {
      return builder(accountInfo).setAccessId(accessIdentifier).setProjectId(projectIdentifier).create();
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
      final HmacKeyInfo candidateInfo = (HmacKeyInfo) otherObject;
      return Objects.equals(this.accessIdentifier, candidateInfo.accessIdentifier)
          && Objects.equals(this.entityTag, candidateInfo.entityTag)
          && Objects.equals(this.identifier, candidateInfo.identifier)
          && Objects.equals(this.projectIdentifier, candidateInfo.projectIdentifier)
          && Objects.equals(this.accountInfo, candidateInfo.accountInfo)
          && Objects.equals(this.status, candidateInfo.status)
          && Objects.equals(this.creationTime, candidateInfo.creationTime)
          && Objects.equals(this.lastUpdated, candidateInfo.lastUpdated);
    }

    public com.google.api.services.storage.model.HmacKeyMetadata toProto() {
      com.google.api.services.storage.model.HmacKeyMetadata keyInfo =
          new com.google.api.services.storage.model.HmacKeyMetadata();
      keyInfo.setAccessId(this.accessIdentifier);
      keyInfo.setEtag(this.entityTag);
      keyInfo.setId(this.identifier);
      keyInfo.setProjectId(this.projectIdentifier);
      keyInfo.setServiceAccountEmail(
          this.accountInfo == null ? null : this.accountInfo.getEmail());
      keyInfo.setState(this.status == null ? null : this.status.toString());
      keyInfo.setTimeCreated(this.creationTime == null ? null : new DateTime(this.creationTime));
      keyInfo.setUpdated(this.lastUpdated == null ? null : new DateTime(this.lastUpdated));

      return keyInfo;
    }

    static HmacKeyInfo fromProto(com.google.api.services.storage.model.HmacKeyMetadata keyInfo) {
      return builder(ServiceAccountInfo.from(keyInfo.getServiceAccountEmail()))
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
      return accountInfo;
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
      return lastUpdated;
    }

    /** Builder for {@code HmacKeyMetadata} objects. * */
    public static class ResourceBuilder {
      private String accessIdentifier;
      private String entityTag;
      private String identifier;
      private String projectIdentifier;
      private ServiceAccountInfo accountInfo;
      private HmacKeyStatus status;
      private Long creationTime;
      private Long lastUpdated;

      private ResourceBuilder(ServiceAccountInfo accountInfo) {
        this.accountInfo = accountInfo;
      }

      private ResourceBuilder(HmacKeyInfo keyInfo) {
        this.accessIdentifier = keyInfo.accessIdentifier;
        this.entityTag = keyInfo.entityTag;
        this.identifier = keyInfo.identifier;
        this.projectIdentifier = keyInfo.projectIdentifier;
        this.accountInfo = keyInfo.accountInfo;
        this.status = keyInfo.status;
        this.creationTime = keyInfo.creationTime;
        this.lastUpdated = keyInfo.lastUpdated;
      }

      public ResourceBuilder setAccessId(String accessIdentifier) {
        this.accessIdentifier = accessIdentifier;
        return this;
      }

      public ResourceBuilder setEtag(String entityTag) {
        this.entityTag = entityTag;
        return this;
      }

      public ResourceBuilder setId(String identifier) {
        this.identifier = identifier;
        return this;
      }

      public ResourceBuilder setServiceAccount(ServiceAccountInfo accountInfo) {
        this.accountInfo = accountInfo;
        return this;
      }

      public ResourceBuilder setState(HmacKeyStatus status) {
        this.status = status;
        return this;
      }

      public ResourceBuilder setCreateTime(long creationTime) {
        this.creationTime = creationTime;
        return this;
      }

      public ResourceBuilder setProjectId(String projectIdentifier) {
        this.projectIdentifier = projectIdentifier;
        return this;
      }

      /** Creates an {@code HmacKeyMetadata} object from this builder. * */
      public HmacKeyInfo create() {
        return new HmacKeyInfo(this);
      }

      public ResourceBuilder setUpdateTime(long lastUpdated) {
        this.lastUpdated = lastUpdated;
        return this;
      }
    }
  }
}
