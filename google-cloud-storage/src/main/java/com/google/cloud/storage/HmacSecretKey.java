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
  private final String secretValue;
  private final HmacKeyInfo keyInfo;

  private HmacSecretKey(SecretMetadataBuilder secretMetadataBuilder) {
    this.secretValue = secretMetadataBuilder.secretValue;
    this.keyInfo = secretMetadataBuilder.keyInfo;
  }

  public static SecretMetadataBuilder newSecretMetadataBuilder(String secretValue) {
    return new SecretMetadataBuilder(secretValue);
  }

  /** Builder for {@code HmacKey} objects. * */
  public static class SecretMetadataBuilder {
    private String secretValue;
    private HmacKeyInfo keyInfo;

    private SecretMetadataBuilder(String secretValue) {
      this.secretValue = secretValue;
    }

    public SecretMetadataBuilder setSecretKey(String secretValue) {
      this.secretValue = secretValue;
      return this;
    }

    public SecretMetadataBuilder setMetadata(HmacKeyInfo keyInfo) {
      this.keyInfo = keyInfo;
      return this;
    }

    /** Creates an {@code HmacKey} object from this builder. * */
    public HmacSecretKey buildHmacSecretKey() {
      return new HmacSecretKey(this);
    }
  }

  /** Returns the secret key associated with this HMAC key. * */
  public String getSecretKey() {
    return secretValue;
  }

  /** Returns the metadata associated with this HMAC key. * */
  public HmacKeyInfo getMetadata() {
    return keyInfo;
  }

  @Override
  public int hashCode() {
    return Objects.hash(secretValue, keyInfo);
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
    return Objects.equals(this.secretValue, secretValue) && Objects.equals(this.keyInfo, keyInfo);
  }

  com.google.api.services.storage.model.HmacKey toPb() {
    com.google.api.services.storage.model.HmacKey protoKey =
        new com.google.api.services.storage.model.HmacKey();
    protoKey.setSecret(this.secretValue);

    if (keyInfo != null) {
      protoKey.setMetadata(keyInfo.toProto());
    }

    return protoKey;
  }

  static HmacSecretKey fromProto(com.google.api.services.storage.model.HmacKey protoKey) {
    return HmacSecretKey.newSecretMetadataBuilder(protoKey.getSecret())
        .setMetadata(HmacKeyInfo.fromProto(protoKey.getMetadata()))
        .buildHmacSecretKey();
  }

  public enum HmacKeyStatus {
    ACTIVE("ACTIVE"),
    INACTIVE("INACTIVE"),
    DELETED("DELETED");

    private final String statusText;

    HmacKeyStatus(String statusText) {
      this.statusText = statusText;
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
    private final HmacKeyStatus statusText;
    private final Long creationTime;
    private final Long modificationTime;

    private HmacKeyInfo(AccessKeyBuilder secretMetadataBuilder) {
      this.accessIdentifier = secretMetadataBuilder.accessIdentifier;
      this.entityTag = secretMetadataBuilder.entityTag;
      this.identifier = secretMetadataBuilder.identifier;
      this.projectIdentifier = secretMetadataBuilder.projectIdentifier;
      this.svcAccount = secretMetadataBuilder.svcAccount;
      this.statusText = secretMetadataBuilder.statusText;
      this.creationTime = secretMetadataBuilder.creationTime;
      this.modificationTime = secretMetadataBuilder.modificationTime;
    }

    public static AccessKeyBuilder newAccessKeyBuilder(ServiceAccountInfo svcAccount) {
      return new AccessKeyBuilder(svcAccount);
    }

    public AccessKeyBuilder toBuilder() {
      return new AccessKeyBuilder(this);
    }

    public static HmacKeyInfo of(
            ServiceAccountInfo svcAccount, String accessIdentifier, String projectIdentifier) {
      return newAccessKeyBuilder(svcAccount).setAccessId(accessIdentifier).setProjectId(projectIdentifier).buildHmacKeyInfo();
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
      final HmacKeyInfo comparedInfo = (HmacKeyInfo) otherObject;
      return Objects.equals(this.accessIdentifier, comparedInfo.accessIdentifier)
          && Objects.equals(this.entityTag, comparedInfo.entityTag)
          && Objects.equals(this.identifier, comparedInfo.identifier)
          && Objects.equals(this.projectIdentifier, comparedInfo.projectIdentifier)
          && Objects.equals(this.svcAccount, comparedInfo.svcAccount)
          && Objects.equals(this.statusText, comparedInfo.statusText)
          && Objects.equals(this.creationTime, comparedInfo.creationTime)
          && Objects.equals(this.modificationTime, comparedInfo.modificationTime);
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
      keyInfo.setState(this.statusText == null ? null : this.statusText.toString());
      keyInfo.setTimeCreated(this.creationTime == null ? null : new DateTime(this.creationTime));
      keyInfo.setUpdated(this.modificationTime == null ? null : new DateTime(this.modificationTime));

      return keyInfo;
    }

    static HmacKeyInfo fromProto(com.google.api.services.storage.model.HmacKeyMetadata keyInfo) {
      return newAccessKeyBuilder(ServiceAccountInfo.create(keyInfo.getServiceAccountEmail()))
          .setAccessId(keyInfo.getAccessId())
          .setCreateTime(keyInfo.getTimeCreated().getValue())
          .setEtag(keyInfo.getEtag())
          .setId(keyInfo.getId())
          .setProjectId(keyInfo.getProjectId())
          .setState(HmacKeyStatus.valueOf(keyInfo.getState()))
          .setUpdateTime(keyInfo.getUpdated().getValue())
          .buildHmacKeyInfo();
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
      return statusText;
    }

    /** Returns the creation time of this HMAC key. * */
    public Long getCreateTime() {
      return creationTime;
    }

    /** Returns the last updated time of this HMAC key. * */
    public Long getUpdateTime() {
      return modificationTime;
    }

    /** Builder for {@code HmacKeyMetadata} objects. * */
    public static class AccessKeyBuilder {
      private String accessIdentifier;
      private String entityTag;
      private String identifier;
      private String projectIdentifier;
      private ServiceAccountInfo svcAccount;
      private HmacKeyStatus statusText;
      private Long creationTime;
      private Long modificationTime;

      private AccessKeyBuilder(ServiceAccountInfo svcAccount) {
        this.svcAccount = svcAccount;
      }

      private AccessKeyBuilder(HmacKeyInfo keyInfo) {
        this.accessIdentifier = keyInfo.accessIdentifier;
        this.entityTag = keyInfo.entityTag;
        this.identifier = keyInfo.identifier;
        this.projectIdentifier = keyInfo.projectIdentifier;
        this.svcAccount = keyInfo.svcAccount;
        this.statusText = keyInfo.statusText;
        this.creationTime = keyInfo.creationTime;
        this.modificationTime = keyInfo.modificationTime;
      }

      public AccessKeyBuilder setAccessId(String accessIdentifier) {
        this.accessIdentifier = accessIdentifier;
        return this;
      }

      public AccessKeyBuilder setEtag(String entityTag) {
        this.entityTag = entityTag;
        return this;
      }

      public AccessKeyBuilder setId(String identifier) {
        this.identifier = identifier;
        return this;
      }

      public AccessKeyBuilder setServiceAccount(ServiceAccountInfo svcAccount) {
        this.svcAccount = svcAccount;
        return this;
      }

      public AccessKeyBuilder setState(HmacKeyStatus statusText) {
        this.statusText = statusText;
        return this;
      }

      public AccessKeyBuilder setCreateTime(long creationTime) {
        this.creationTime = creationTime;
        return this;
      }

      public AccessKeyBuilder setProjectId(String projectIdentifier) {
        this.projectIdentifier = projectIdentifier;
        return this;
      }

      /** Creates an {@code HmacKeyMetadata} object from this builder. * */
      public HmacKeyInfo buildHmacKeyInfo() {
        return new HmacKeyInfo(this);
      }

      public AccessKeyBuilder setUpdateTime(long modificationTime) {
        this.modificationTime = modificationTime;
        return this;
      }
    }
  }
}
