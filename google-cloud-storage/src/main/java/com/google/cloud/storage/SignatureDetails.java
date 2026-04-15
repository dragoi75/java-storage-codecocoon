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
public class SignatureDetails {

  public static final char COMPONENT_SEPARATOR = '\n';
  public static final String GOOG4_RSA_SHA256 = "GOOG4-RSA-SHA256";
  public static final String SCOPE = "/auto/storage/goog4_request";
  private static final List<String> RESERVED_PARAM_KEYS =
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
  private final String contentDigest;
  private final String contentMimeType;
  private final long expirationTime;
  private final Map<String, String> canonicalExtHeaders;
  private final Map<String, String> queryParameters;
  private final URI canonicalResource;
  private final StorageClient.UrlSigningOption.SignatureSchemeVersion signatureScheme;
  private final String userEmail;
  private final long epochTimestamp;

  private final String yearMonthDayStr;
  private final String exactDateStr;

  private SignatureDetails(SignatureBuilder signatureBuilder) {
    this.httpMethod = signatureBuilder.httpMethod;
    this.contentDigest = signatureBuilder.contentDigest;
    this.contentMimeType = signatureBuilder.contentMimeType;
    this.expirationTime = signatureBuilder.expirationTime;
    this.canonicalResource = signatureBuilder.canonicalResource;
    this.signatureScheme = signatureBuilder.signatureScheme;
    this.userEmail = signatureBuilder.userEmail;
    this.epochTimestamp = signatureBuilder.epochTimestamp;

    ImmutableMap.Builder<String, String> headersBuilder =
        new ImmutableMap.Builder<String, String>().putAll(signatureBuilder.canonicalExtHeaders);
    // The "host" header only needs to be present and signed if using V4.
    if (StorageClient.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureScheme)
        && (!signatureBuilder.canonicalExtHeaders.containsKey("host"))) {
      headersBuilder.put("host", "storage.googleapis.com");
    }
    canonicalExtHeaders = headersBuilder.build();

    queryParameters = ImmutableMap.<String, String>copyOf(signatureBuilder.queryParameters);

    Date currentDate = new Date(epochTimestamp);

    SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
    SimpleDateFormat exactDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");

    ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    exactDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));

    yearMonthDayStr = ymdFormatter.format(currentDate);
    exactDateStr = exactDateFormatter.format(currentDate);
  }

  /**
   * Constructs payload to be signed.
   *
   * @return payload to sign
   * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed URLs</a>
   */
  public String buildUnsignedPayload() {
    // TODO reverse order when V4 becomes default
    if (StorageClient.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureScheme)) {
      return buildV4UnsignedPayload();
    }
    return buildV2UnsignedPayload();
  }

  private String buildV2UnsignedPayload() {
    StringBuilder unsignedPayloadBuilder = new StringBuilder();

    unsignedPayloadBuilder.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
    if (contentDigest != null) {
      unsignedPayloadBuilder.append(contentDigest);
    }
    unsignedPayloadBuilder.append(COMPONENT_SEPARATOR);

    if (contentMimeType != null) {
      unsignedPayloadBuilder.append(contentMimeType);
    }
    unsignedPayloadBuilder.append(COMPONENT_SEPARATOR);
    unsignedPayloadBuilder.append(expirationTime).append(COMPONENT_SEPARATOR);

    if (canonicalExtHeaders.size() > 0) {
      unsignedPayloadBuilder.append(
          new CanonicalExtensionHeadersSerializer(StorageClient.UrlSigningOption.SignatureSchemeVersion.V2)
              .serialize(canonicalExtHeaders));
    }

    unsignedPayloadBuilder.append(canonicalResource);

    return unsignedPayloadBuilder.toString();
  }

  private String buildV4UnsignedPayload() {
    StringBuilder unsignedPayloadBuilder = new StringBuilder();

    unsignedPayloadBuilder.append(GOOG4_RSA_SHA256).append(COMPONENT_SEPARATOR);
    unsignedPayloadBuilder.append(exactDateStr).append(COMPONENT_SEPARATOR);
    unsignedPayloadBuilder.append(yearMonthDayStr).append(SCOPE).append(COMPONENT_SEPARATOR);
    unsignedPayloadBuilder.append(computeV4CanonicalRequestHash());

    return unsignedPayloadBuilder.toString();
  }

  private String computeV4CanonicalRequestHash() {
    StringBuilder canonicalRequestBuilder = new StringBuilder();

    CanonicalExtensionHeadersSerializer extensionHeadersSerializer =
        new CanonicalExtensionHeadersSerializer(StorageClient.UrlSigningOption.SignatureSchemeVersion.V4);

    canonicalRequestBuilder.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
    canonicalRequestBuilder.append(canonicalResource).append(COMPONENT_SEPARATOR);
    canonicalRequestBuilder.append(buildV4QueryString()).append(COMPONENT_SEPARATOR);
    canonicalRequestBuilder
        .append(extensionHeadersSerializer.serialize(canonicalExtHeaders))
        .append(COMPONENT_SEPARATOR);
    canonicalRequestBuilder
        .append(extensionHeadersSerializer.serializeHeaderNames(canonicalExtHeaders))
        .append(COMPONENT_SEPARATOR);
    canonicalRequestBuilder.append("UNSIGNED-PAYLOAD");

    return Hashing.sha256()
        .hashString(canonicalRequestBuilder.toString(), StandardCharsets.UTF_8)
        .toString();
  }

  /**
   * Returns a TreeMap containing the user-supplied query parameters that do not have reserved keys.
   */
  private TreeMap<String, String> getNonReservedUserQueryParams() {
    TreeMap<String, String> sortedParamsMap = new TreeMap<String, String>();

    // Skip any instances from well-known required headers that might have been supplied by the
    // caller.
    for (Map.Entry<String, String> paramEntry : queryParameters.entrySet()) {
      // Convert to (and check for the existence from) lowercase keys to prevent cases like a user
      // supplying "x-goog-algorithm", in order to prevent the resulting query string from
      // containing "x-goog-algorithm" and "X-Goog-Algorithm".
      if (!RESERVED_PARAM_KEYS.contains(paramEntry.getKey().toLowerCase())) {
        // URI encode user-supplied parameter, both the name and the value.
        sortedParamsMap.put(
            Rfc3986UriEncode(paramEntry.getKey(), true), Rfc3986UriEncode(paramEntry.getValue(), true));
      }
    }

    return sortedParamsMap;
  }

  private String buildQueryStringFromParamMap(Map<String, String> paramMap) {
    StringBuilder queryBuilder = new StringBuilder();

    String separator = "";
    for (Map.Entry<String, String> paramEntry : paramMap.entrySet()) {
      queryBuilder.append(separator);
      separator = "&";
      queryBuilder.append(paramEntry.getKey()).append('=').append(paramEntry.getValue());
    }

    return queryBuilder.toString();
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
    TreeMap<String, String> sortedParamsMap = getNonReservedUserQueryParams();
    // The "GoogleAccessId", "Expires", and "Signature" params are not included here.
    return buildQueryStringFromParamMap(sortedParamsMap);
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
    TreeMap<String, String> sortedParamsMap = getNonReservedUserQueryParams();

    // Add in the reserved auth-specific query params.
    sortedParamsMap.put("X-Goog-Algorithm", Rfc3986UriEncode(GOOG4_RSA_SHA256, true));
    sortedParamsMap.put(
        "X-Goog-Credential", Rfc3986UriEncode(userEmail + "/" + yearMonthDayStr + SCOPE, true));
    sortedParamsMap.put("X-Goog-Date", Rfc3986UriEncode(exactDateStr, true));
    sortedParamsMap.put("X-Goog-Expires", Rfc3986UriEncode(Long.toString(expirationTime), true));
    StringBuilder signedHeadersSb =
        new CanonicalExtensionHeadersSerializer(StorageClient.UrlSigningOption.SignatureSchemeVersion.V4)
            .serializeHeaderNames(canonicalExtHeaders);
    sortedParamsMap.put(
        "X-Goog-SignedHeaders", Rfc3986UriEncode(signedHeadersSb.toString(), true));

    // The "X-Goog-Signature" param is not included here.
    return buildQueryStringFromParamMap(sortedParamsMap);
  }

  public HttpMethod getHttpVerb() {
    return httpMethod;
  }

  public String getContentMd5() {
    return contentDigest;
  }

  public String getContentType() {
    return contentMimeType;
  }

  public long getExpiration() {
    return expirationTime;
  }

  public Map<String, String> getCanonicalizedExtensionHeaders() {
    return canonicalExtHeaders;
  }

  public Map<String, String> getQueryParams() {
    return queryParameters;
  }

  public URI getCanonicalizedResource() {
    return canonicalResource;
  }

  public StorageClient.UrlSigningOption.SignatureSchemeVersion getSignatureVersion() {
    return signatureScheme;
  }

  public long getTimestamp() {
    return epochTimestamp;
  }

  public String getAccountEmail() {
    return userEmail;
  }

  public static final class SignatureBuilder {

    private final HttpMethod httpMethod;
    private String contentDigest;
    private String contentMimeType;
    private final long expirationTime;
    private Map<String, String> canonicalExtHeaders;
    private Map<String, String> queryParameters;
    private final URI canonicalResource;
    private StorageClient.UrlSigningOption.SignatureSchemeVersion signatureScheme;
    private String userEmail;
    private long epochTimestamp;

    /**
     * Constructs builder.
     *
     * @param httpMethod the HTTP method
     * @param expirationTime the EPOX expiration date
     * @param canonicalResource the resource URI
     * @throws IllegalArgumentException if required field is not provided.
     */
    public SignatureBuilder(HttpMethod httpMethod, long expirationTime, URI canonicalResource) {
      this.httpMethod = httpMethod;
      this.expirationTime = expirationTime;
      this.canonicalResource = canonicalResource;
    }

    public SignatureBuilder(SignatureDetails signatureDetailsParam) {
      this.httpMethod = signatureDetailsParam.httpMethod;
      this.contentDigest = signatureDetailsParam.contentDigest;
      this.contentMimeType = signatureDetailsParam.contentMimeType;
      this.expirationTime = signatureDetailsParam.expirationTime;
      this.canonicalExtHeaders = signatureDetailsParam.canonicalExtHeaders;
      this.queryParameters = signatureDetailsParam.queryParameters;
      this.canonicalResource = signatureDetailsParam.canonicalResource;
      this.signatureScheme = signatureDetailsParam.signatureScheme;
      this.userEmail = signatureDetailsParam.userEmail;
      this.epochTimestamp = signatureDetailsParam.epochTimestamp;
    }

    public SignatureBuilder setContentMd5(String contentDigest) {
      this.contentDigest = contentDigest;

      return this;
    }

    public SignatureBuilder setContentType(String contentMimeType) {
      this.contentMimeType = contentMimeType;

      return this;
    }

    public SignatureBuilder setCanonicalizedExtensionHeaders(
        Map<String, String> canonicalExtHeaders) {
      this.canonicalExtHeaders = canonicalExtHeaders;

      return this;
    }

    public SignatureBuilder setCanonicalizedQueryParams(Map<String, String> queryParameters) {
      this.queryParameters = queryParameters;

      return this;
    }

    public SignatureBuilder setSignatureVersion(StorageClient.UrlSigningOption.SignatureSchemeVersion signatureScheme) {
      this.signatureScheme = signatureScheme;

      return this;
    }

    public SignatureBuilder setAccountEmail(String userEmail) {
      this.userEmail = userEmail;

      return this;
    }

    public SignatureBuilder setTimestamp(long epochTimestamp) {
      this.epochTimestamp = epochTimestamp;

      return this;
    }

    /** Creates an {@code SignatureDetails} object from this builder. */
    public SignatureDetails buildSignature() {
      checkArgument(httpMethod != null, "Required HTTP method");
      checkArgument(canonicalResource != null, "Required canonicalized resource");
      checkArgument(expirationTime >= 0, "Expiration must be greater than or equal to zero");

      if (StorageClient.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureScheme)) {
        checkArgument(userEmail != null, "Account email required to use V4 signing");
        checkArgument(epochTimestamp > 0, "Timestamp required to use V4 signing");
        checkArgument(
            expirationTime <= 604800, "Expiration can't be longer than 7 days to use V4 signing");
      }

      if (canonicalExtHeaders == null) {
        canonicalExtHeaders = new HashMap<>();
      }

      if (queryParameters == null) {
        queryParameters = new HashMap<>();
      }

      return new SignatureDetails(this);
    }
  }
}
