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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import org.junit.Assert;
import org.junit.Test;

public class OptionTest {

  private static final CloudStorageRpc.StorageOption RPC_OPTION = CloudStorageRpc.StorageOption.DELIMITER;
  private static final CloudStorageRpc.StorageOption ANOTHER_RPC_OPTION = CloudStorageRpc.StorageOption.FIELDS;
  private static final String VALUE = "some value";
  private static final String OTHER_VALUE = "another value";
  private static final RpcOptionWrapper OPTION = new RpcOptionWrapper(RPC_OPTION, VALUE) {};
  private static final RpcOptionWrapper OPTION_EQUALS = new RpcOptionWrapper(RPC_OPTION, VALUE) {};
  private static final RpcOptionWrapper OPTION_NOT_EQUALS1 = new RpcOptionWrapper(RPC_OPTION, OTHER_VALUE) {};
  private static final RpcOptionWrapper OPTION_NOT_EQUALS2 = new RpcOptionWrapper(ANOTHER_RPC_OPTION, VALUE) {};

  @Test
  public void testEquals() {
    assertEquals(OPTION, OPTION_EQUALS);
    assertNotEquals(OPTION, OPTION_NOT_EQUALS1);
    assertNotEquals(OPTION, OPTION_NOT_EQUALS2);
  }

  @Test
  public void testHashCode() {
    assertEquals(OPTION.hashCode(), OPTION_EQUALS.hashCode());
  }

  @Test
  public void testConstructor() {
    assertEquals(RPC_OPTION, OPTION.getRpcOption());
    assertEquals(VALUE, OPTION.getValue());
    RpcOptionWrapper option = new RpcOptionWrapper(RPC_OPTION, null) {};
    assertEquals(RPC_OPTION, option.getRpcOption());
    assertNull(option.getValue());
    try {
      new RpcOptionWrapper(null, VALUE) {};
      Assert.fail();
    } catch (NullPointerException expected) {
    }
  }
}
