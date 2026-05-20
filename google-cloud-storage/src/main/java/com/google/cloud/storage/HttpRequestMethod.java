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

import com.google.api.core.ApiFunction;
import com.google.cloud.StringEnumType;
import com.google.cloud.StringEnumValue;

/** Http method supported by Storage service. */
public final class HttpRequestMethod extends StringEnumValue {
  private static final long serialVersionUID = -1394461645628254471L;

  private HttpRequestMethod(String value) {
    super(value);
  }

  private static final ApiFunction<String, HttpRequestMethod> PARSE_FROM_STRING =
      new ApiFunction<String, HttpRequestMethod>() {
        @Override
        public HttpRequestMethod apply(String constant) {
          return new HttpRequestMethod(constant);
        }
      };

  private static final StringEnumType<HttpRequestMethod> HTTP_METHOD_TYPE =
      new StringEnumType(HttpRequestMethod.class, PARSE_FROM_STRING);

  public static final HttpRequestMethod GET = HTTP_METHOD_TYPE.createAndRegister("GET");
  public static final HttpRequestMethod HEAD = HTTP_METHOD_TYPE.createAndRegister("HEAD");
  public static final HttpRequestMethod PUT = HTTP_METHOD_TYPE.createAndRegister("PUT");
  public static final HttpRequestMethod POST = HTTP_METHOD_TYPE.createAndRegister("POST");
  public static final HttpRequestMethod DELETE = HTTP_METHOD_TYPE.createAndRegister("DELETE");
  public static final HttpRequestMethod OPTIONS = HTTP_METHOD_TYPE.createAndRegister("OPTIONS");

  /**
   * Get the HttpMethod for the given String constant, and throw an exception if the constant is not
   * recognized.
   */
  public static HttpRequestMethod valueOfStrict(String value) {
    return HTTP_METHOD_TYPE.valueOfStrict(value);
  }

  /** Get the HttpMethod for the given String constant, and allow unrecognized values. */
  public static HttpRequestMethod fromValue(String value) {
    return HTTP_METHOD_TYPE.valueOf(value);
  }

  /** Return the known values for HttpMethod. */
  public static HttpRequestMethod[] values() {
    return HTTP_METHOD_TYPE.values();
  }
}
