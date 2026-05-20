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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical extension header serializer.
 *
 * @see <a href=
 *     "https://cloud.google.com/storage/docs/access-control/signed-urls#about-canonical-extension-headers">
 *     Canonical Extension Headers</a>
 */
public class CanonicalExtensionHeadersFormatter {

  private static final char EXTENSION_HEADER_DELIM = ':';
  private static final char HEADER_VALUE_SEPARATOR = ';';

  private final StorageService.UrlSigningOption.SigningVersion signingVersion;

  public CanonicalExtensionHeadersFormatter(
      StorageService.UrlSigningOption.SigningVersion signingVersion) {
    this.signingVersion = signingVersion;
  }

  public CanonicalExtensionHeadersFormatter() {
    // TODO switch this when V4 becomes default
    this.signingVersion = StorageService.UrlSigningOption.SigningVersion.V2;
  }

  public StringBuilder serializeExtensionHeaders(Map<String, String> normalizedExtensionHeaders) {

    StringBuilder headersBuilder = new StringBuilder();

    if (normalizedExtensionHeaders == null || normalizedExtensionHeaders.isEmpty()) {
      return headersBuilder;
    }

    Map<String, String> lowercasedHeaders = getLowercaseHeaders(normalizedExtensionHeaders);

    // Sort all custom headers by header name using a lexicographical sort by code point value.
    List<String> sortedHeaderList = new ArrayList<>(lowercasedHeaders.keySet());
    Collections.sort(sortedHeaderList);

    for (String headerKey : sortedHeaderList) {
      headersBuilder
          .append(headerKey)
          .append(EXTENSION_HEADER_DELIM)
          .append(
              lowercasedHeaders
                  .get(headerKey)
                  // Remove any whitespace around the colon that appears after the header name.
                  .trim()
                  // Replace any sequence of whitespace with a single space.
                  .replaceAll("\\s+", " "))
          // Append a newline (U+000A) to each custom header.
          .append(SigningInfo.COMPONENT_SEPARATOR);
    }

    // Concatenate all custom headers
    return headersBuilder;
  }

  public StringBuilder joinHeaderNames(Map<String, String> normalizedExtensionHeaders) {
    StringBuilder headersBuilder = new StringBuilder();

    if (normalizedExtensionHeaders == null || normalizedExtensionHeaders.isEmpty()) {
      return headersBuilder;
    }
    Map<String, String> lowercasedHeaders = getLowercaseHeaders(normalizedExtensionHeaders);

    List<String> sortedHeaderList = new ArrayList<>(lowercasedHeaders.keySet());
    Collections.sort(sortedHeaderList);

    for (String headerKey : sortedHeaderList) {
      headersBuilder.append(headerKey).append(HEADER_VALUE_SEPARATOR);
    }

    headersBuilder.setLength(headersBuilder.length() - 1); // remove trailing semicolon

    return headersBuilder;
  }

  private Map<String, String> getLowercaseHeaders(
      Map<String, String> normalizedExtensionHeaders) {
    // Make all custom header names lowercase.
    Map<String, String> lowercasedHeaders = new HashMap<>();
    for (String headerKey : new ArrayList<>(normalizedExtensionHeaders.keySet())) {

      String lowercasedName = headerKey.toLowerCase();

      // If present and we're V2, remove the x-goog-encryption-key and x-goog-encryption-key-sha256
      // headers. (CSEK headers are allowed for V4)
      if (StorageService.UrlSigningOption.SigningVersion.V2.equals(signingVersion)
          && ("x-goog-encryption-key".equals(lowercasedName)
              || "x-goog-encryption-key-sha256".equals(lowercasedName))) {

        continue;
      }

      lowercasedHeaders.put(lowercasedName, normalizedExtensionHeaders.get(headerKey));
    }

    return lowercasedHeaders;
  }
}
