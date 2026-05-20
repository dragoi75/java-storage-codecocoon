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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ServiceAccountTest {

  private static final ServiceAccountInfo SERVICE_ACCOUNT = ServiceAccountInfo.create("email");

  @Test
  public void testOf() {
    compareServiceAccount(SERVICE_ACCOUNT, ServiceAccountInfo.create("email"));
  }

  @Test
  public void testToAndFromPb() {
    compareServiceAccount(SERVICE_ACCOUNT, ServiceAccountInfo.fromProto(SERVICE_ACCOUNT.toProto()));
  }

  public void compareServiceAccount(ServiceAccountInfo expected, ServiceAccountInfo value) {
    assertEquals(expected, value);
    assertEquals(expected.getEmail(), value.getEmail());
    assertEquals(expected.hashCode(), value.hashCode());
  }
}
