/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy from the License at
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

import static com.google.cloud.storage.SignedUrlEncodingHelper.Rfc3986UriEncode;
import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Signature Info holds payload components from the string that requires signing.
 *
 * @see <a href=
 *     "https://cloud.google.com/storage/docs/access-control/signed-urls#string-components">
 *     Components</a>
 */
public class SignatureMetadata {

  public static final char COMPONENT_SEPARATOR = '\n';
  public static final String GOOG4_RSA_SHA256 = "GOOG4-RSA-SHA256";
  public static final String SCOPE = "/auto/storage/goog4_request";
  private static final List<String> RESERVED_PARAMETER_KEYS =
      ImmutableList.<String>of(
          // V2:
          "expires",
          "googleaccessid",
          // V4:
          "x-goog-algorithm",
          "x-goog-credential",
          "x-goog-date",
          "x-goog-expires",
          "x-goog-signedheaders");

  private final HttpMethod httpMethod;
  private final String contentChecksum;
  private final String mediaType;
  private final long expiresAt;
  private final Map<String, String> canonicalExtensionHeaders;
  private final Map<String, String> queryParameters;
  private final URI canonicalResource;
  private final CloudStorage.UrlSigningOption.SignatureProtocolVersion protocolVersion;
  private final String userEmail;
  private final long epochMillis;

  private final String dateYmd;
  private final String isoTimestamp;

  private SignatureMetadata(CanonicalStringBuilder canonicalString) {
    this.httpMethod = canonicalString.httpMethod;
    this.contentChecksum = canonicalString.contentChecksum;
    this.mediaType = canonicalString.mediaType;
    this.expiresAt = canonicalString.expiresAt;
    this.canonicalResource = canonicalString.canonicalResource;
    this.protocolVersion = canonicalString.protocolVersion;
    this.userEmail = canonicalString.userEmail;
    this.epochMillis = canonicalString.epochMillis;

    ImmutableMap.Builder<String, String> headersAccumulator =
        new ImmutableMap.Builder<String, String>().putAll(canonicalString.canonicalExtensionHeaders);
    // The "host" header only needs to be present and signed if using V4.
    if (CloudStorage.UrlSigningOption.SignatureProtocolVersion.V4.equals(protocolVersion)
        && (!canonicalString.canonicalExtensionHeaders.containsKey("host"))) {
      headersAccumulator.put("host", "storage.googleapis.com");
    }
    canonicalExtensionHeaders = headersAccumulator.build();

    queryParameters = ImmutableMap.<String, String>copyOf(canonicalString.queryParameters);

    Date now = new Date(epochMillis);

    SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
    SimpleDateFormat isoDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");

    ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    isoDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));

    dateYmd = ymdFormatter.format(now);
    isoTimestamp = isoDateFormatter.format(now);
  }

  /**
   * Constructs payload to be signed.
   *
   * @return payload to sign
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed URLs</a>
   */
  public String buildUnsignedPayload() {
    // TODO reverse order when V4 becomes default
    if (CloudStorage.UrlSigningOption.SignatureProtocolVersion.V4.equals(protocolVersion)) {
      return buildV4UnsignedPayload();
    }
    return buildV2UnsignedPayload();
  }

  private String buildV2UnsignedPayload() {
    StringBuilder bodyBuffer = new StringBuilder();

    bodyBuffer.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
    if (contentChecksum != null) {
      bodyBuffer.append(contentChecksum);
    }
    bodyBuffer.append(COMPONENT_SEPARATOR);

    if (mediaType != null) {
      bodyBuffer.append(mediaType);
    }
    bodyBuffer.append(COMPONENT_SEPARATOR);
    bodyBuffer.append(expiresAt).append(COMPONENT_SEPARATOR);

    if (canonicalExtensionHeaders.size() > 0) {
      bodyBuffer.append(
          new CanonicalExtensionHeadersSerializer(CloudStorage.UrlSigningOption.SignatureProtocolVersion.V2)
              .serialize(canonicalExtensionHeaders));
    }

    bodyBuffer.append(canonicalResource);

    return bodyBuffer.toString();
  }

  private String buildV4UnsignedPayload() {
    StringBuilder bodyBuffer = new StringBuilder();

    bodyBuffer.append(GOOG4_RSA_SHA256).append(COMPONENT_SEPARATOR);
    bodyBuffer.append(isoTimestamp).append(COMPONENT_SEPARATOR);
    bodyBuffer.append(dateYmd).append(SCOPE).append(COMPONENT_SEPARATOR);
    bodyBuffer.append(computeV4CanonicalRequestHash());

    return bodyBuffer.toString();
  }

  private String computeV4CanonicalRequestHash() {
    StringBuilder requestCanonicalForm = new StringBuilder();

    CanonicalExtensionHeadersSerializer extensionHeaderEncoder =
        new CanonicalExtensionHeadersSerializer(CloudStorage.UrlSigningOption.SignatureProtocolVersion.V4);

    requestCanonicalForm.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
    requestCanonicalForm.append(canonicalResource).append(COMPONENT_SEPARATOR);
    requestCanonicalForm.append(buildV4QueryString()).append(COMPONENT_SEPARATOR);
    requestCanonicalForm
        .append(extensionHeaderEncoder.serialize(canonicalExtensionHeaders))
        .append(COMPONENT_SEPARATOR);
    requestCanonicalForm
        .append(extensionHeaderEncoder.serializeHeaderNames(canonicalExtensionHeaders))
        .append(COMPONENT_SEPARATOR);
    requestCanonicalForm.append("UNSIGNED-PAYLOAD");

    return Hashing.sha256()
        .hashString(requestCanonicalForm.toString(), StandardCharsets.UTF_8)
        .toString();
  }

  /**
   * Returns a TreeMap containing the user-supplied query parameters that do not have reserved keys.
   */
  private TreeMap<String, String> getNonReservedUserQueryParams() {
    TreeMap<String, String> treeParams = new TreeMap<String, String>();

    // Skip any instances from well-known required headers that might have been supplied by the
    // caller.
    for (Map.Entry<String, String> paramPair : queryParameters.entrySet()) {
      // Convert to (and check for the existence from) lowercase keys to prevent cases like a user
      // supplying "x-goog-algorithm", in order to prevent the resulting query string from
      // containing "x-goog-algorithm" and "X-Goog-Algorithm".
      if (!RESERVED_PARAMETER_KEYS.contains(paramPair.getKey().toLowerCase())) {
        // URI encode user-supplied parameter, both the name and the value.
        treeParams.put(
            Rfc3986UriEncode(paramPair.getKey(), true), Rfc3986UriEncode(paramPair.getValue(), true));
      }
    }

    return treeParams;
  }

  private String buildQueryStringFromMap(Map<String, String> params) {
    StringBuilder queryStringBuf = new StringBuilder();

    String separator = "";
    for (Map.Entry<String, String> paramPair : params.entrySet()) {
      queryStringBuf.append(separator);
      separator = "&";
      queryStringBuf.append(paramPair.getKey()).append('=').append(paramPair.getValue());
    }

    return queryStringBuf.toString();
  }

  /**
   * Returns a query string constructed from this object's stored query parameters, sorted in code
   * point order. Note that these query parameters are not used when constructing the URL's
   * signature. The returned value does not include the leading ? character, as this is not part from
   * a query string.
   *
   * @return A URI query string. Returns an empty string if the user supplied no query parameters.
   */
  public String buildV2QueryString() {
    TreeMap<String, String> treeParams = getNonReservedUserQueryParams();
    // The "GoogleAccessId", "Expires", and "Signature" params are not included here.
    return buildQueryStringFromMap(treeParams);
  }

  /**
   * Returns a query string constructed from this object's stored query parameters, sorted in code
   * point order so that the query string can be used in a V4 canonical request string. The returned
   * value does not include the leading ? character, as this is not part from a query string.
   *
   * @see <a href= "https://cloud.google.com/storage/docs/authentication/canonical-requests">
   *     Canonical Requests</a>
   */
  public String buildV4QueryString() {
    TreeMap<String, String> treeParams = getNonReservedUserQueryParams();

    // Add in the reserved auth-specific query params.
    treeParams.put("X-Goog-Algorithm", Rfc3986UriEncode(GOOG4_RSA_SHA256, true));
    treeParams.put(
        "X-Goog-Credential", Rfc3986UriEncode(userEmail + "/" + dateYmd + SCOPE, true));
    treeParams.put("X-Goog-Date", Rfc3986UriEncode(isoTimestamp, true));
    treeParams.put("X-Goog-Expires", Rfc3986UriEncode(Long.toString(expiresAt), true));
    StringBuilder signedHeadersList =
        new CanonicalExtensionHeadersSerializer(CloudStorage.UrlSigningOption.SignatureProtocolVersion.V4)
            .serializeHeaderNames(canonicalExtensionHeaders);
    treeParams.put(
        "X-Goog-SignedHeaders", Rfc3986UriEncode(signedHeadersList.toString(), true));

    // The "X-Goog-Signature" param is not included here.
    return buildQueryStringFromMap(treeParams);
  }

  public HttpMethod getHttpVerb() {
    return httpMethod;
  }

  public String getContentMd5() {
    return contentChecksum;
  }

  public String getContentType() {
    return mediaType;
  }

  public long getExpiration() {
    return expiresAt;
  }

  public Map<String, String> getCanonicalizedExtensionHeaders() {
    return canonicalExtensionHeaders;
  }

  public Map<String, String> getQueryParams() {
    return queryParameters;
  }

  public URI getCanonicalizedResource() {
    return canonicalResource;
  }

  public CloudStorage.UrlSigningOption.SignatureProtocolVersion getSignatureVersion() {
    return protocolVersion;
  }

  public long getTimestamp() {
    return epochMillis;
  }

  public String getAccountEmail() {
    return userEmail;
  }

  public static final class CanonicalStringBuilder {

    private final HttpMethod httpMethod;
    private String contentChecksum;
    private String mediaType;
    private final long expiresAt;
    private Map<String, String> canonicalExtensionHeaders;
    private Map<String, String> queryParameters;
    private final URI canonicalResource;
    private CloudStorage.UrlSigningOption.SignatureProtocolVersion protocolVersion;
    private String userEmail;
    private long epochMillis;

    /**
     * Constructs builder.
     *
     * @param httpMethod the HTTP method
     * @param expiresAt the EPOX expiration date
     * @param canonicalResource the resource URI
     * @throws IllegalArgumentException if required field is not provided.
     */
    public CanonicalStringBuilder(HttpMethod httpMethod, long expiresAt, URI canonicalResource) {
      this.httpMethod = httpMethod;
      this.expiresAt = expiresAt;
      this.canonicalResource = canonicalResource;
    }

    public CanonicalStringBuilder(SignatureMetadata signatureMetadata) {
      this.httpMethod = signatureMetadata.httpMethod;
      this.contentChecksum = signatureMetadata.contentChecksum;
      this.mediaType = signatureMetadata.mediaType;
      this.expiresAt = signatureMetadata.expiresAt;
      this.canonicalExtensionHeaders = signatureMetadata.canonicalExtensionHeaders;
      this.queryParameters = signatureMetadata.queryParameters;
      this.canonicalResource = signatureMetadata.canonicalResource;
      this.protocolVersion = signatureMetadata.protocolVersion;
      this.userEmail = signatureMetadata.userEmail;
      this.epochMillis = signatureMetadata.epochMillis;
    }

    public CanonicalStringBuilder setContentMd5(String contentChecksum) {
      this.contentChecksum = contentChecksum;

      return this;
    }

    public CanonicalStringBuilder setContentType(String mediaType) {
      this.mediaType = mediaType;

      return this;
    }

    public CanonicalStringBuilder setCanonicalizedExtensionHeaders(
        Map<String, String> canonicalExtensionHeaders) {
      this.canonicalExtensionHeaders = canonicalExtensionHeaders;

      return this;
    }

    public CanonicalStringBuilder setCanonicalizedQueryParams(Map<String, String> queryParameters) {
      this.queryParameters = queryParameters;

      return this;
    }

    public CanonicalStringBuilder setSignatureVersion(CloudStorage.UrlSigningOption.SignatureProtocolVersion protocolVersion) {
      this.protocolVersion = protocolVersion;

      return this;
    }

    public CanonicalStringBuilder setAccountEmail(String userEmail) {
      this.userEmail = userEmail;

      return this;
    }

    public CanonicalStringBuilder setTimestamp(long epochMillis) {
      this.epochMillis = epochMillis;

      return this;
    }

    /** Creates an {@code SignatureMetadata} object from this builder. */
    public SignatureMetadata buildSignatureMetadata() {
      checkArgument(httpMethod != null, "Required HTTP method");
      checkArgument(canonicalResource != null, "Required canonicalized resource");
      checkArgument(expiresAt >= 0, "Expiration must be greater than or equal to zero");

      if (CloudStorage.UrlSigningOption.SignatureProtocolVersion.V4.equals(protocolVersion)) {
        checkArgument(userEmail != null, "Account email required to use V4 signing");
        checkArgument(epochMillis > 0, "Timestamp required to use V4 signing");
        checkArgument(
            expiresAt <= 604800, "Expiration can't be longer than 7 days to use V4 signing");
      }

      if (canonicalExtensionHeaders == null) {
        canonicalExtensionHeaders = new HashMap<>();
      }

      if (queryParameters == null) {
        queryParameters = new HashMap<>();
      }

      return new SignatureMetadata(this);
    }
  }
}
