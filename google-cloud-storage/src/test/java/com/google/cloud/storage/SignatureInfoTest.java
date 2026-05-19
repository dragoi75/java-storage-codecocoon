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
import static org.junit.Assert.assertTrue;

import com.google.cloud.storage.SignatureMetadata.CanonicalStringBuilder;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class SignatureInfoTest {

  private static final String RESOURCE = "/bucketName/blobName";

  @Test(expected = IllegalArgumentException.class)
  public void requireHttpVerb() {

    new SignatureMetadata.CanonicalStringBuilder(null, 0L, URI.create(RESOURCE)).buildCanonicalString();
  }

  @Test(expected = IllegalArgumentException.class)
  public void requireResource() {

    new SignatureMetadata.CanonicalStringBuilder(HttpRequestMethod.GET, 0L, null).buildCanonicalString();
  }

  @Test
  public void constructUnsignedPayload() {

    SignatureMetadata.CanonicalStringBuilder builder = new SignatureMetadata.CanonicalStringBuilder(HttpRequestMethod.PUT, 0L, URI.create(RESOURCE));

    String unsignedPayload = builder.buildCanonicalString().buildUnsignedPayload();

    assertEquals("PUT\n\n\n0\n" + RESOURCE, unsignedPayload);
  }

  @Test
  public void constructUnsignedPayloadWithExtensionHeaders() {

    CanonicalStringBuilder builder = new CanonicalStringBuilder(HttpRequestMethod.PUT, 0L, URI.create(RESOURCE));

    Map<String, String> extensionHeaders = new HashMap<>();
    extensionHeaders.put("x-goog-acl", "public-read");
    extensionHeaders.put("x-goog-meta-owner", "myself");

    builder.setCanonicalizedExtensionHeaders(extensionHeaders);

    String unsignedPayload = builder.buildCanonicalString().buildUnsignedPayload();

    String rawPayload = "PUT\n\n\n0\nx-goog-acl:public-read\nx-goog-meta-owner:myself\n" + RESOURCE;

    assertEquals(rawPayload, unsignedPayload);
  }

  @Test
  public void constructV4UnsignedPayload() {
    SignatureMetadata.CanonicalStringBuilder builder = new CanonicalStringBuilder(HttpRequestMethod.PUT, 10L, URI.create(RESOURCE));

    builder.setSignatureVersion(Storage.UrlSigningOption.SignatureSchemeVersion.V4);
    builder.setAccountEmail("me@google.com");
    builder.setTimestamp(1000000000000L);

    String unsignedPayload = builder.buildCanonicalString().buildUnsignedPayload();

    assertTrue(
        unsignedPayload.startsWith(
            "GOOG4-RSA-SHA256\n" + "20010909T014640Z\n" + "20010909/auto/storage/goog4_request\n"));
  }

  @Test
  public void constructV4QueryString() {
    SignatureMetadata.CanonicalStringBuilder builder = new SignatureMetadata.CanonicalStringBuilder(HttpRequestMethod.PUT, 10L, URI.create(RESOURCE));

    builder.setSignatureVersion(Storage.UrlSigningOption.SignatureSchemeVersion.V4);
    builder.setAccountEmail("me@google.com");
    builder.setTimestamp(1000000000000L);

    String queryString = builder.buildCanonicalString().buildV4QueryString();
    assertEquals(
        "X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=me%40google.com%2F20010909%2F"
            + "auto%2Fstorage%2Fgoog4_request&X-Goog-Date=20010909T014640Z&X-Goog-Expires=10&X-Goog-SignedHeaders=host",
        queryString);
  }
}
