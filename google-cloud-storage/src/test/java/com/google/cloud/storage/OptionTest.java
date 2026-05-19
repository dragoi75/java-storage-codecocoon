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

import com.google.cloud.storage.spi.v1.StorageServiceRpc;
import org.junit.Assert;
import org.junit.Test;

public class OptionTest {

  private static final StorageServiceRpc.StorageOption RPC_OPTION = StorageServiceRpc.StorageOption.DELIMITER;
  private static final StorageServiceRpc.StorageOption ANOTHER_RPC_OPTION = StorageServiceRpc.StorageOption.FIELDS;
  private static final String VALUE = "some value";
  private static final String OTHER_VALUE = "another value";
  private static final OptionDescriptor OPTION = new OptionDescriptor(RPC_OPTION, VALUE) {};
  private static final OptionDescriptor OPTION_EQUALS = new OptionDescriptor(RPC_OPTION, VALUE) {};
  private static final OptionDescriptor OPTION_NOT_EQUALS1 = new OptionDescriptor(RPC_OPTION, OTHER_VALUE) {};
  private static final OptionDescriptor OPTION_NOT_EQUALS2 = new OptionDescriptor(ANOTHER_RPC_OPTION, VALUE) {};

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
    OptionDescriptor option = new OptionDescriptor(RPC_OPTION, null) {};
    assertEquals(RPC_OPTION, option.getRpcOption());
    assertNull(option.getValue());
    try {
      new OptionDescriptor(null, VALUE) {};
      Assert.fail();
    } catch (NullPointerException expected) {
    }
  }
}
