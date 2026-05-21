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

import static com.google.cloud.storage.SignedUrlEncoderHelper.rfc3986UriEncode;
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
 * Signature Info holds payload components of the string that requires signing.
 *
 * @see <a href=
 *     "https://cloud.google.com/storage/docs/access-control/signed-urls#string-components">
 *     Components</a>
 */
public class SigningContext {

    public static final char COMPONENT_SEPARATOR = '\n';

    public static final String GOOG4_RSA_SHA256 = "GOOG4-RSA-SHA256";

    public static final String SCOPE = "/auto/storage/goog4_request";

    private static final List<String> // V2:
    // V2:
    // V2:
    // V2:
    // V4:
    RESERVED_PARAM_NAMES_LOWER = // V4:
    ImmutableList.<String>of("expires", "googleaccessid", "x-goog-algorithm", "x-goog-credential", "x-goog-date", "x-goog-expires", "x-goog-signedheaders");

    private final HttpRequestMethod httpMethod;

    private final String contentHash;

    private final String mimeType;

    private final long expiresAt;

    private final Map<String, String> canonicalExtensionHeaders;

    private final Map<String, String> requestQueryParams;

    private final URI canonicalResource;

    private final CloudStorageClient.UrlSigningOption.SignatureSchemeVersion signatureSchemeVersion;

    private final String accountAddress;

    private final long timeMillis;

    private final String ymdString;

    private final String preciseDate;

    private SigningContext(RequestSignatureBuilder signatureBuilder) {
        this.httpMethod = signatureBuilder.httpMethod;
        this.contentHash = signatureBuilder.contentHash;
        this.mimeType = signatureBuilder.mimeType;
        this.expiresAt = signatureBuilder.expiresAt;
        this.canonicalResource = signatureBuilder.canonicalResource;
        this.signatureSchemeVersion = signatureBuilder.signatureSchemeVersion;
        this.accountAddress = signatureBuilder.accountAddress;
        this.timeMillis = signatureBuilder.timeMillis;
        ImmutableMap.Builder<String, String> headersBuilder = new ImmutableMap.Builder<String, String>().putAll(signatureBuilder.canonicalExtensionHeaders);
        // The "host" header only needs to be present and signed if using V4.
        if (CloudStorageClient.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureSchemeVersion) && (!signatureBuilder.canonicalExtensionHeaders.containsKey("host"))) {
            headersBuilder.put("host", "storage.googleapis.com");
        }
        canonicalExtensionHeaders = headersBuilder.build();
        requestQueryParams = ImmutableMap.<String, String>copyOf(signatureBuilder.requestQueryParams);
        Date now = new Date(timeMillis);
        SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat exactDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        exactDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        ymdString = ymdFormatter.format(now);
        preciseDate = exactDateFormatter.format(now);
    }

    /**
     * Constructs payload to be signed.
     *
     * @return payload to sign
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed URLs</a>
     */
    public String buildUnsignedPayload() {
        // TODO reverse order when V4 becomes default
        if (CloudStorageClient.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureSchemeVersion)) {
            return buildV4UnsignedPayload();
        }
        return buildV2UnsignedPayload();
    }

    private String buildV2UnsignedPayload() {
        StringBuilder contentBuilder = new StringBuilder();
        contentBuilder.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
        if (null != contentHash) {
            contentBuilder.append(contentHash);
        }
        contentBuilder.append(COMPONENT_SEPARATOR);
        if (null != mimeType) {
            contentBuilder.append(mimeType);
        }
        contentBuilder.append(COMPONENT_SEPARATOR);
        contentBuilder.append(expiresAt).append(COMPONENT_SEPARATOR);
        if (0 < canonicalExtensionHeaders.size()) {
            contentBuilder.append(new CanonicalExtensionHeadersSerializer(CloudStorageClient.UrlSigningOption.SignatureSchemeVersion.V2).serialize(canonicalExtensionHeaders));
        }
        contentBuilder.append(canonicalResource);
        return contentBuilder.toString();
    }

    private String buildV4UnsignedPayload() {
        StringBuilder contentBuilder = new StringBuilder();
        contentBuilder.append(GOOG4_RSA_SHA256).append(COMPONENT_SEPARATOR);
        contentBuilder.append(preciseDate).append(COMPONENT_SEPARATOR);
        contentBuilder.append(ymdString).append(SCOPE).append(COMPONENT_SEPARATOR);
        contentBuilder.append(computeV4CanonicalRequestHash());
        return contentBuilder.toString();
    }

    private String computeV4CanonicalRequestHash() {
        StringBuilder canonicalReqBuilder = new StringBuilder();
        CanonicalExtensionHeadersSerializer extensionHeadersSerializer = new CanonicalExtensionHeadersSerializer(CloudStorageClient.UrlSigningOption.SignatureSchemeVersion.V4);
        canonicalReqBuilder.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(canonicalResource).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(buildV4QueryString()).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(extensionHeadersSerializer.serialize(canonicalExtensionHeaders)).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(extensionHeadersSerializer.serializeHeaderNames(canonicalExtensionHeaders)).append(COMPONENT_SEPARATOR);
        String providedHash = canonicalExtensionHeaders.get("X-Goog-Content-SHA256");
        canonicalReqBuilder.append(null == providedHash ? "UNSIGNED-PAYLOAD" : providedHash);
        return Hashing.sha256().hashString(canonicalReqBuilder.toString(), StandardCharsets.UTF_8).toString();
    }

    /**
     * Returns a TreeMap containing the user-supplied query parameters that do not have reserved keys.
     */
    private TreeMap<String, String> getNonReservedUserQueryParams() {
        TreeMap<String, String> paramTreeMap = new TreeMap<String, String>();
        // Skip any instances of well-known required headers that might have been supplied by the
        // caller.
        for (Map.Entry<String, String> paramEntry : requestQueryParams.entrySet()) {
            // Convert to (and check for the existence of) lowercase keys to prevent cases like a user
            // supplying "x-goog-algorithm", in order to prevent the resulting query string from
            // containing "x-goog-algorithm" and "X-Goog-Algorithm".
            if (!RESERVED_PARAM_NAMES_LOWER.contains(paramEntry.getKey().toLowerCase())) {
                // URI encode user-supplied parameter, both the name and the value.
                paramTreeMap.put(rfc3986UriEncode(paramEntry.getKey(), true), rfc3986UriEncode(paramEntry.getValue(), true));
            }
        }
        return paramTreeMap;
    }

    private String buildQueryStringFromMap(Map<String, String> paramsMap) {
        StringBuilder qsBuilder = new StringBuilder();
        String separator = "";
        for (Map.Entry<String, String> paramEntry : paramsMap.entrySet()) {
            qsBuilder.append(separator);
            separator = "&";
            qsBuilder.append(paramEntry.getKey()).append('=').append(paramEntry.getValue());
        }
        return qsBuilder.toString();
    }

    /**
     * Returns a query string constructed from this object's stored query parameters, sorted in code
     * point order. Note that these query parameters are not used when constructing the URL's
     * signature. The returned value does not include the leading ? character, as this is not part of
     * a query string.
     *
     * @return A URI query string. Returns an empty string if the user supplied no query parameters.
     */
    public String buildV2QueryString() {
        TreeMap<String, String> paramTreeMap = getNonReservedUserQueryParams();
        // The "GoogleAccessId", "Expires", and "Signature" params are not included here.
        return buildQueryStringFromMap(paramTreeMap);
    }

    /**
     * Returns a query string constructed from this object's stored query parameters, sorted in code
     * point order so that the query string can be used in a V4 canonical request string. The returned
     * value does not include the leading ? character, as this is not part of a query string.
     *
     * @see <a href= "https://cloud.google.com/storage/docs/authentication/canonical-requests">
     *     Canonical Requests</a>
     */
    public String buildV4QueryString() {
        TreeMap<String, String> paramTreeMap = getNonReservedUserQueryParams();
        // Add in the reserved auth-specific query params.
        paramTreeMap.put("X-Goog-Algorithm", rfc3986UriEncode(GOOG4_RSA_SHA256, true));
        paramTreeMap.put("X-Goog-Credential", rfc3986UriEncode(accountAddress + "/" + ymdString + SCOPE, true));
        paramTreeMap.put("X-Goog-Date", rfc3986UriEncode(preciseDate, true));
        paramTreeMap.put("X-Goog-Expires", rfc3986UriEncode(Long.toString(expiresAt), true));
        StringBuilder headersSignedBuilder = new CanonicalExtensionHeadersSerializer(CloudStorageClient.UrlSigningOption.SignatureSchemeVersion.V4).serializeHeaderNames(canonicalExtensionHeaders);
        paramTreeMap.put("X-Goog-SignedHeaders", rfc3986UriEncode(headersSignedBuilder.toString(), true));
        // The "X-Goog-Signature" param is not included here.
        return buildQueryStringFromMap(paramTreeMap);
    }

    public HttpRequestMethod getHttpVerb() {
        return httpMethod;
    }

    public String getContentMd5() {
        return contentHash;
    }

    public String getContentType() {
        return mimeType;
    }

    public long getExpiration() {
        return expiresAt;
    }

    public Map<String, String> getCanonicalizedExtensionHeaders() {
        return canonicalExtensionHeaders;
    }

    public Map<String, String> getQueryParams() {
        return requestQueryParams;
    }

    public URI getCanonicalizedResource() {
        return canonicalResource;
    }

    public CloudStorageClient.UrlSigningOption.SignatureSchemeVersion getSignatureVersion() {
        return signatureSchemeVersion;
    }

    public long getTimestamp() {
        return timeMillis;
    }

    public String getAccountEmail() {
        return accountAddress;
    }

    public static final class RequestSignatureBuilder {

        private final HttpRequestMethod httpMethod;

        private String contentHash;

        private String mimeType;

        private final long expiresAt;

        private Map<String, String> canonicalExtensionHeaders;

        private Map<String, String> requestQueryParams;

        private final URI canonicalResource;

        private CloudStorageClient.UrlSigningOption.SignatureSchemeVersion signatureSchemeVersion;

        private String accountAddress;

        private long timeMillis;

        /**
         * Constructs builder.
         *
         * @param httpMethod the HTTP method
         * @param expiresAt the EPOX expiration date
         * @param canonicalResource the resource URI
         * @throws IllegalArgumentException if required field is not provided.
         */
        public RequestSignatureBuilder(HttpRequestMethod httpMethod, long expiresAt, URI canonicalResource) {
            this.httpMethod = httpMethod;
            this.expiresAt = expiresAt;
            this.canonicalResource = canonicalResource;
        }

        public RequestSignatureBuilder(SigningContext signingContext) {
            this.httpMethod = signingContext.httpMethod;
            this.contentHash = signingContext.contentHash;
            this.mimeType = signingContext.mimeType;
            this.expiresAt = signingContext.expiresAt;
            this.canonicalExtensionHeaders = signingContext.canonicalExtensionHeaders;
            this.requestQueryParams = signingContext.requestQueryParams;
            this.canonicalResource = signingContext.canonicalResource;
            this.signatureSchemeVersion = signingContext.signatureSchemeVersion;
            this.accountAddress = signingContext.accountAddress;
            this.timeMillis = signingContext.timeMillis;
        }

        public RequestSignatureBuilder setContentMd5(String contentHash) {
            this.contentHash = contentHash;
            return this;
        }

        public RequestSignatureBuilder setContentType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public RequestSignatureBuilder setCanonicalizedExtensionHeaders(Map<String, String> canonicalExtensionHeaders) {
            this.canonicalExtensionHeaders = canonicalExtensionHeaders;
            return this;
        }

        public RequestSignatureBuilder setCanonicalizedQueryParams(Map<String, String> requestQueryParams) {
            this.requestQueryParams = requestQueryParams;
            return this;
        }

        public RequestSignatureBuilder setSignatureVersion(CloudStorageClient.UrlSigningOption.SignatureSchemeVersion signatureSchemeVersion) {
            this.signatureSchemeVersion = signatureSchemeVersion;
            return this;
        }

        public RequestSignatureBuilder setAccountEmail(String accountAddress) {
            this.accountAddress = accountAddress;
            return this;
        }

        public RequestSignatureBuilder setTimestamp(long timeMillis) {
            this.timeMillis = timeMillis;
            return this;
        }

        /**
         * Creates an {@code SignatureInfo} object from this builder.
         */
        public SigningContext buildSignature() {
            checkArgument(null != httpMethod, "Required HTTP method");
            checkArgument(null != canonicalResource, "Required canonicalized resource");
            checkArgument(0 <= expiresAt, "Expiration must be greater than or equal to zero");
            if (CloudStorageClient.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureSchemeVersion)) {
                checkArgument(null != accountAddress, "Account email required to use V4 signing");
                checkArgument(0 < timeMillis, "Timestamp required to use V4 signing");
                checkArgument(604800 >= expiresAt, "Expiration can't be longer than 7 days to use V4 signing");
            }
            if (null == canonicalExtensionHeaders) {
                canonicalExtensionHeaders = new HashMap<>();
            }
            if (null == requestQueryParams) {
                requestQueryParams = new HashMap<>();
            }
            return new SigningContext(this);
        }
    }
}
