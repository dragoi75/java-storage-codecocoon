/*
 * Copyright 2015 Google LLC
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Objects;

/** Base class for Storage operation option. */
public abstract class AbstractOption implements Serializable {

  private static final long serialVersionUID = -73199088766477208L;

  private final StorageRpcClient.StorageOption remoteCallOption;
  private final Object payload;

    @Override
    public int hashCode() {
      return Objects.hash(remoteCallOption, payload);
    }

    @Override
    public boolean equals(Object candidate) {
      if (!(candidate instanceof AbstractOption)) {
        return false;
      }
      AbstractOption thatOption = (AbstractOption) candidate;
      return Objects.equals(remoteCallOption, thatOption.remoteCallOption) && Objects.equals(payload, thatOption.payload);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("name", remoteCallOption.getValue())
          .add("value", payload)
          .toString();
    }

    StorageRpcClient.StorageOption getRpcOption() {
      return remoteCallOption;
    }

    AbstractOption(StorageRpcClient.StorageOption remoteCallOption, Object payload) {
      this.remoteCallOption = checkNotNull(remoteCallOption);
      this.payload = payload;
    }

    Object getValue() {
      return payload;
    }

}
