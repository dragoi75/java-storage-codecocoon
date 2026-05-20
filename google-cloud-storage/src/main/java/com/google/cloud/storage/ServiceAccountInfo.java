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
public final class ServiceAccountInfo implements Serializable {

  static final Function<com.google.api.services.storage.model.ServiceAccount, ServiceAccountInfo>
      FROM_PB_FUNCTION =
          new Function<com.google.api.services.storage.model.ServiceAccount, ServiceAccountInfo>() {
            @Override
            public ServiceAccountInfo apply(com.google.api.services.storage.model.ServiceAccount pb) {
              return ServiceAccountInfo.fromProto(pb);
            }
          };
  static final Function<ServiceAccountInfo, com.google.api.services.storage.model.ServiceAccount>
      TO_PB_FUNCTION =
          new Function<ServiceAccountInfo, com.google.api.services.storage.model.ServiceAccount>() {
            @Override
            public com.google.api.services.storage.model.ServiceAccount apply(
                ServiceAccountInfo metadata) {
              return metadata.toProto();
            }
          };

  private static final long serialVersionUID = 4199610694227857331L;

  private final String mailAddress;

  private ServiceAccountInfo(String mailAddress) {
    this.mailAddress = mailAddress;
  }

  /** Returns the email address of the service account. */
  public String getEmail() {
    return mailAddress;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("email", mailAddress).toString();
  }

  @Override
  public int hashCode() {
    return Objects.hash(mailAddress);
  }

  @Override
  public boolean equals(Object other) {
    return other == this
        || other instanceof ServiceAccountInfo && Objects.equals(toProto(), ((ServiceAccountInfo) other).toProto());
  }

  com.google.api.services.storage.model.ServiceAccount toProto() {
    com.google.api.services.storage.model.ServiceAccount serviceAccountProto =
        new com.google.api.services.storage.model.ServiceAccount();
    serviceAccountProto.setEmailAddress(mailAddress);
    return serviceAccountProto;
  }

  /** Returns a {@code ServiceAccount} object for the provided email. */
  public static ServiceAccountInfo from(String mailAddress) {
    return new ServiceAccountInfo(mailAddress);
  }

  static ServiceAccountInfo fromProto(com.google.api.services.storage.model.ServiceAccount accountProto) {
    return new ServiceAccountInfo(accountProto.getEmailAddress());
  }
}
