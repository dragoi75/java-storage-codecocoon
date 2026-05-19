/*
 * Copyright 2019 Google LLC
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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** Helper for encoding URI segments appropriately when creating a Signed URL. */
class SignedUrlEncoder {

  static String rfc3986UriEncode(final String pathPart, final boolean escapeSlash) {
    String encodedPart;
    try {
      encodedPart = URLEncoder.encode(pathPart, "UTF-8");
    } catch (UnsupportedEncodingException encodingError) {
      throw new RuntimeException(encodingError);
    }
    // URLEncoder.encode() does mostly what we want, with the exception of a few characters that
    // we fix in a second phase:
    encodedPart =
        encodedPart
            .replace("*", "%2A") // Asterisks should be encoded.
            .replace("+", "%20") // Spaces should be encoded as %20 instead of a plus sign.
            .replace("%7E", "~"); // Tildes should not be encoded.
    // Forward slashes should NOT be encoded in the segment of the URI that represents the
    // object's name, but should be encoded for all other segments.
    if (!escapeSlash) {
      encodedPart = encodedPart.replace("%2F", "/");
    }
    return encodedPart;
  }
}
