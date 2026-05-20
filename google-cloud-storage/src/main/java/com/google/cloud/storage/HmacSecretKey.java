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

  private HmacSecretKey(SecretEntryBuilder entryCreator) {
    this.keyMaterial = entryCreator.keyMaterial;
    this.keyInfo = entryCreator.keyInfo;
  }

  public static SecretEntryBuilder createBuilder(String keyMaterial) {
    return new SecretEntryBuilder(keyMaterial);
  }

  /** Builder for {@code HmacKey} objects. * */
  public static class SecretEntryBuilder {
    private String keyMaterial;
    private HmacKeyInfo keyInfo;

    private SecretEntryBuilder(String keyMaterial) {
      this.keyMaterial = keyMaterial;
    }

    public SecretEntryBuilder setSecretKey(String keyMaterial) {
      this.keyMaterial = keyMaterial;
      return this;
    }

    public SecretEntryBuilder setMetadata(HmacKeyInfo keyInfo) {
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
  public boolean equals(Object candidate) {
    if (this == candidate) {
      return true;
    }
    if (candidate == null || getClass() != candidate.getClass()) {
      return false;
    }
    final HmacKeyInfo other = (HmacKeyInfo) candidate;
    return Objects.equals(this.keyMaterial, keyMaterial) && Objects.equals(this.keyInfo, keyInfo);
  }

  com.google.api.services.storage.model.HmacKey toPb() {
    com.google.api.services.storage.model.HmacKey protoHmac =
        new com.google.api.services.storage.model.HmacKey();
    protoHmac.setSecret(this.keyMaterial);

    if (keyInfo != null) {
      protoHmac.setMetadata(keyInfo.toProto());
    }

    return protoHmac;
  }

  static HmacSecretKey fromProto(com.google.api.services.storage.model.HmacKey protoHmac) {
    return HmacSecretKey.createBuilder(protoHmac.getSecret())
        .setMetadata(HmacKeyInfo.fromProto(protoHmac.getMetadata()))
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
    private final Long creationTimestamp;
    private final Long updateTimestamp;

    private HmacKeyInfo(ServiceAccountKeyBuilder entryCreator) {
      this.accessIdentifier = entryCreator.accessIdentifier;
      this.entityTag = entryCreator.entityTag;
      this.identifier = entryCreator.identifier;
      this.projectIdentifier = entryCreator.projectIdentifier;
      this.accountInfo = entryCreator.accountInfo;
      this.status = entryCreator.status;
      this.creationTimestamp = entryCreator.creationTimestamp;
      this.updateTimestamp = entryCreator.updateTimestamp;
    }

    public static ServiceAccountKeyBuilder createBuilder(ServiceAccountInfo accountInfo) {
      return new ServiceAccountKeyBuilder(accountInfo);
    }

    public ServiceAccountKeyBuilder toBuilder() {
      return new ServiceAccountKeyBuilder(this);
    }

    public static HmacKeyInfo of(
            ServiceAccountInfo accountInfo, String accessIdentifier, String projectIdentifier) {
      return createBuilder(accountInfo).setAccessId(accessIdentifier).setProjectId(projectIdentifier).create();
    }

    @Override
    public int hashCode() {
      return Objects.hash(accessIdentifier, projectIdentifier);
    }

    @Override
    public boolean equals(Object candidate) {
      if (this == candidate) {
        return true;
      }
      if (candidate == null || getClass() != candidate.getClass()) {
        return false;
      }
      final HmacKeyInfo comparedInfo = (HmacKeyInfo) candidate;
      return Objects.equals(this.accessIdentifier, comparedInfo.accessIdentifier)
          && Objects.equals(this.entityTag, comparedInfo.entityTag)
          && Objects.equals(this.identifier, comparedInfo.identifier)
          && Objects.equals(this.projectIdentifier, comparedInfo.projectIdentifier)
          && Objects.equals(this.accountInfo, comparedInfo.accountInfo)
          && Objects.equals(this.status, comparedInfo.status)
          && Objects.equals(this.creationTimestamp, comparedInfo.creationTimestamp)
          && Objects.equals(this.updateTimestamp, comparedInfo.updateTimestamp);
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
      keyInfo.setTimeCreated(this.creationTimestamp == null ? null : new DateTime(this.creationTimestamp));
      keyInfo.setUpdated(this.updateTimestamp == null ? null : new DateTime(this.updateTimestamp));

      return keyInfo;
    }

    static HmacKeyInfo fromProto(com.google.api.services.storage.model.HmacKeyMetadata keyInfo) {
      return createBuilder(ServiceAccountInfo.create(keyInfo.getServiceAccountEmail()))
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
      return creationTimestamp;
    }

    /** Returns the last updated time of this HMAC key. * */
    public Long getUpdateTime() {
      return updateTimestamp;
    }

    /** Builder for {@code HmacKeyMetadata} objects. * */
    public static class ServiceAccountKeyBuilder {
      private String accessIdentifier;
      private String entityTag;
      private String identifier;
      private String projectIdentifier;
      private ServiceAccountInfo accountInfo;
      private HmacKeyStatus status;
      private Long creationTimestamp;
      private Long updateTimestamp;

      private ServiceAccountKeyBuilder(ServiceAccountInfo accountInfo) {
        this.accountInfo = accountInfo;
      }

      private ServiceAccountKeyBuilder(HmacKeyInfo keyInfo) {
        this.accessIdentifier = keyInfo.accessIdentifier;
        this.entityTag = keyInfo.entityTag;
        this.identifier = keyInfo.identifier;
        this.projectIdentifier = keyInfo.projectIdentifier;
        this.accountInfo = keyInfo.accountInfo;
        this.status = keyInfo.status;
        this.creationTimestamp = keyInfo.creationTimestamp;
        this.updateTimestamp = keyInfo.updateTimestamp;
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

      public ServiceAccountKeyBuilder setServiceAccount(ServiceAccountInfo accountInfo) {
        this.accountInfo = accountInfo;
        return this;
      }

      public ServiceAccountKeyBuilder setState(HmacKeyStatus status) {
        this.status = status;
        return this;
      }

      public ServiceAccountKeyBuilder setCreateTime(long creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
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

      public ServiceAccountKeyBuilder setUpdateTime(long updateTimestamp) {
        this.updateTimestamp = updateTimestamp;
        return this;
      }
    }
  }
}
