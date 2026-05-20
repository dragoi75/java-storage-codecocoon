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

import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Objects;

/** Base class for Storage operation option. */
public abstract class RpcOptionWrapper implements Serializable {

  private static final long serialVersionUID = -73199088766477208L;

  private final CloudStorageRpc.StorageOption storageOption;
  private final Object data;

  RpcOptionWrapper(CloudStorageRpc.StorageOption storageOption, Object data) {
    this.storageOption = checkNotNull(storageOption);
    this.data = data;
  }

  CloudStorageRpc.StorageOption getRpcOption() {
    return storageOption;
  }

  Object getValue() {
    return data;
  }

  @Override
  public boolean equals(Object otherObject) {
    if (!(otherObject instanceof RpcOptionWrapper)) {
      return false;
    }
    RpcOptionWrapper thatWrapper = (RpcOptionWrapper) otherObject;
    return Objects.equals(storageOption, thatWrapper.storageOption) && Objects.equals(data, thatWrapper.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(storageOption, data);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("name", storageOption.getValue())
        .add("value", data)
        .toString();
  }
}
