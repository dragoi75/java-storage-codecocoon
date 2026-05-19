/*
 * Copyright 2017 Google LLC
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

import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Objects;

/**
 * A service account, with its specified scopes, authorized for this instance.
 *
 * @see <a href="https://cloud.google.com/storage/docs/authentication">Authenticating from Google
 *     Cloud Storage</a>
 */
public final class ServiceAccountIdentity implements Serializable {

  static final Function<com.google.api.services.storage.model.ServiceAccount, ServiceAccountIdentity>
      FROM_PB_FUNCTION =
          new Function<com.google.api.services.storage.model.ServiceAccount, ServiceAccountIdentity>() {
            @Override
            public ServiceAccountIdentity apply(com.google.api.services.storage.model.ServiceAccount pb) {
              return ServiceAccountIdentity.fromServiceAccountModel(pb);
            }
          };
  static final Function<ServiceAccountIdentity, com.google.api.services.storage.model.ServiceAccount>
      TO_PB_FUNCTION =
          new Function<ServiceAccountIdentity, com.google.api.services.storage.model.ServiceAccount>() {
            @Override
            public com.google.api.services.storage.model.ServiceAccount apply(
                ServiceAccountIdentity metadata) {
              return metadata.toServiceAccountModel();
            }
          };

  private static final long serialVersionUID = 4199610694227857331L;

  private final String address;

  private ServiceAccountIdentity(String address) {
    this.address = address;
  }

  /** Returns the email address of the service account. */
  public String getEmail() {
    return address;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("email", address).toString();
  }

  @Override
  public int hashCode() {
    return Objects.hash(address);
  }

  @Override
  public boolean equals(Object other) {
    return other == this
        || other instanceof ServiceAccountIdentity && Objects.equals(toServiceAccountModel(), ((ServiceAccountIdentity) other).toServiceAccountModel());
  }

  com.google.api.services.storage.model.ServiceAccount toServiceAccountModel() {
    com.google.api.services.storage.model.ServiceAccount serviceAccountModel =
        new com.google.api.services.storage.model.ServiceAccount();
    serviceAccountModel.setEmailAddress(address);
    return serviceAccountModel;
  }

  /** Returns a {@code ServiceAccount} object for the provided email. */
  public static ServiceAccountIdentity create(String address) {
    return new ServiceAccountIdentity(address);
  }

  static ServiceAccountIdentity fromServiceAccountModel(com.google.api.services.storage.model.ServiceAccount accountProto) {
    return new ServiceAccountIdentity(accountProto.getEmailAddress());
  }
}
