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

import com.google.cloud.storage.CorsConfig.OriginValue;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;

public class CorsTest {

  @Test
  public void testOrigin() {
    assertEquals("bla", CorsConfig.OriginValue.from("bla").getValue());
    assertEquals("http://host:8080", CorsConfig.OriginValue.from("http", "host", 8080).toString());
    assertEquals(CorsConfig.OriginValue.from("*"), CorsConfig.OriginValue.anyOrigin());
  }

  @Test
  public void corsTest() {
    List<CorsConfig.OriginValue> origins = ImmutableList.of(OriginValue.anyOrigin(), CorsConfig.OriginValue.from("o"));
    List<String> headers = ImmutableList.of("h1", "h2");
    List<HttpRequestMethod> methods = ImmutableList.of(HttpRequestMethod.GET);
    CorsConfig cors =
        CorsConfig.builder()
            .setMaxAgeSeconds(100)
            .setOrigins(origins)
            .setResponseHeaders(headers)
            .setMethods(methods)
            .buildConfig();

    assertEquals(Integer.valueOf(100), cors.getMaxAgeSeconds());
    assertEquals(origins, cors.getOrigins());
    assertEquals(methods, cors.getMethods());
    assertEquals(headers, cors.getResponseHeaders());
  }
}
