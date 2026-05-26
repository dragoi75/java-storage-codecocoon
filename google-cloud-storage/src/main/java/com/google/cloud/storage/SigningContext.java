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

import static com.google.cloud.storage.SignedUrlEncoder.rfc3986UriEncode;
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
    RESERVED_PARAMETER_NAMES_LOWERCASE = // V4:
    ImmutableList.<String>of("expires", "googleaccessid", "x-goog-algorithm", "x-goog-credential", "x-goog-date", "x-goog-expires", "x-goog-signedheaders");

    private final HttpRequestMethod requestMethod;

    private final String contentHash;

    private final String mimeType;

    private final long expiryMillis;

    private final Map<String, String> canonicalExtensionHeaders;

    private final Map<String, String> queryParameters;

    private final URI canonicalResource;

    private final StorageService.UrlSigningOption.SigningVersion signingVersion;

    private final String userEmail;

    private final long epochMillis;

    private final String ymdString;

    private final String isoDateString;

    public static final class SignedUrlBuilder {

        private final HttpRequestMethod requestMethod;

        private String contentHash;

        private String mimeType;

        private final long expiryMillis;

        private Map<String, String> canonicalExtensionHeaders;

        private Map<String, String> queryParameters;

        private final URI canonicalResource;

        private StorageService.UrlSigningOption.SigningVersion signingVersion;

        private String userEmail;

        private long epochMillis;

        public SignedUrlBuilder setCanonicalizedQueryParams(Map<String, String> queryParameters) {
            this.queryParameters = queryParameters;
            return this;
        }

        public SignedUrlBuilder setCanonicalizedExtensionHeaders(Map<String, String> canonicalExtensionHeaders) {
            this.canonicalExtensionHeaders = canonicalExtensionHeaders;
            return this;
        }

        public SignedUrlBuilder setSignatureVersion(StorageService.UrlSigningOption.SigningVersion signingVersion) {
            this.signingVersion = signingVersion;
            return this;
        }

        public SignedUrlBuilder setContentMd5(String contentHash) {
            this.contentHash = contentHash;
            return this;
        }

        public SignedUrlBuilder setTimestamp(long epochMillis) {
            this.epochMillis = epochMillis;
            return this;
        }

        public SignedUrlBuilder setAccountEmail(String userEmail) {
            this.userEmail = userEmail;
            return this;
        }

        /**
         * Creates an {@code SignatureInfo} object from this builder.
         */
        public SigningContext buildSignedUrl() {
            checkArgument(null != requestMethod, "Required HTTP method");
            checkArgument(null != canonicalResource, "Required canonicalized resource");
            checkArgument(0 <= expiryMillis, "Expiration must be greater than or equal to zero");
            if (StorageService.UrlSigningOption.SigningVersion.V4.equals(signingVersion)) {
                checkArgument(null != userEmail, "Account email required to use V4 signing");
                checkArgument(0 < epochMillis, "Timestamp required to use V4 signing");
                checkArgument(604800 >= expiryMillis, "Expiration can't be longer than 7 days to use V4 signing");
            }
            if (null == canonicalExtensionHeaders) {
                canonicalExtensionHeaders = new HashMap<>();
            }
            if (null == queryParameters) {
                queryParameters = new HashMap<>();
            }
            return new SigningContext(this);
        }

        public SignedUrlBuilder(SigningContext signingContext) {
            this.requestMethod = signingContext.requestMethod;
            this.contentHash = signingContext.contentHash;
            this.mimeType = signingContext.mimeType;
            this.expiryMillis = signingContext.expiryMillis;
            this.canonicalExtensionHeaders = signingContext.canonicalExtensionHeaders;
            this.queryParameters = signingContext.queryParameters;
            this.canonicalResource = signingContext.canonicalResource;
            this.signingVersion = signingContext.signingVersion;
            this.userEmail = signingContext.userEmail;
            this.epochMillis = signingContext.epochMillis;
        }

        /**
         * Constructs builder.
         *
         * @param requestMethod the HTTP method
         * @param expiryMillis the EPOX expiration date
         * @param canonicalResource the resource URI
         * @throws IllegalArgumentException if required field is not provided.
         */
        public SignedUrlBuilder(HttpRequestMethod requestMethod, long expiryMillis, URI canonicalResource) {
            this.requestMethod = requestMethod;
            this.expiryMillis = expiryMillis;
            this.canonicalResource = canonicalResource;
        }

        public SignedUrlBuilder setContentType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

    }

    public Map<String, String> getCanonicalizedExtensionHeaders() {
        return canonicalExtensionHeaders;
    }

    public long getExpiration() {
        return expiryMillis;
    }

    public StorageService.UrlSigningOption.SigningVersion getSignatureVersion() {
        return signingVersion;
    }

    private String buildV4UnsignedPayload() {
        StringBuilder contentBuffer = new StringBuilder();
        contentBuffer.append(GOOG4_RSA_SHA256).append(COMPONENT_SEPARATOR);
        contentBuffer.append(isoDateString).append(COMPONENT_SEPARATOR);
        contentBuffer.append(ymdString).append(SCOPE).append(COMPONENT_SEPARATOR);
        contentBuffer.append(computeV4CanonicalRequestHash());
        return contentBuffer.toString();
    }

    public URI getCanonicalizedResource() {
        return canonicalResource;
    }

    private String buildQueryStringFromParamMap(Map<String, String> params) {
        StringBuilder queryStringBuf = new StringBuilder();
        String separator = "";
        for (Map.Entry<String, String> paramEntry : params.entrySet()) {
            queryStringBuf.append(separator);
            separator = "&";
            queryStringBuf.append(paramEntry.getKey()).append('=').append(paramEntry.getValue());
        }
        return queryStringBuf.toString();
    }

    public Map<String, String> getQueryParams() {
        return queryParameters;
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
        TreeMap<String, String> orderedParamMap = getNonReservedUserQueryParams();
        // Add in the reserved auth-specific query params.
        orderedParamMap.put("X-Goog-Algorithm", rfc3986UriEncode(GOOG4_RSA_SHA256, true));
        orderedParamMap.put("X-Goog-Credential", rfc3986UriEncode(userEmail + "/" + ymdString + SCOPE, true));
        orderedParamMap.put("X-Goog-Date", rfc3986UriEncode(isoDateString, true));
        orderedParamMap.put("X-Goog-Expires", rfc3986UriEncode(Long.toString(expiryMillis), true));
        StringBuilder signedHeadersBuf = new CanonicalExtensionHeadersSerializer(StorageService.UrlSigningOption.SigningVersion.V4).serializeHeaderNames(canonicalExtensionHeaders);
        orderedParamMap.put("X-Goog-SignedHeaders", rfc3986UriEncode(signedHeadersBuf.toString(), true));
        // The "X-Goog-Signature" param is not included here.
        return buildQueryStringFromParamMap(orderedParamMap);
    }

    private String buildV2UnsignedPayload() {
        StringBuilder contentBuffer = new StringBuilder();
        contentBuffer.append(requestMethod.name()).append(COMPONENT_SEPARATOR);
        if (null != contentHash) {
            contentBuffer.append(contentHash);
        }
        contentBuffer.append(COMPONENT_SEPARATOR);
        if (null != mimeType) {
            contentBuffer.append(mimeType);
        }
        contentBuffer.append(COMPONENT_SEPARATOR);
        contentBuffer.append(expiryMillis).append(COMPONENT_SEPARATOR);
        if (0 < canonicalExtensionHeaders.size()) {
            contentBuffer.append(new CanonicalExtensionHeadersSerializer(StorageService.UrlSigningOption.SigningVersion.V2).serialize(canonicalExtensionHeaders));
        }
        contentBuffer.append(canonicalResource);
        return contentBuffer.toString();
    }

    /**
     * Returns a TreeMap containing the user-supplied query parameters that do not have reserved keys.
     */
    private TreeMap<String, String> getNonReservedUserQueryParams() {
        TreeMap<String, String> orderedParamMap = new TreeMap<String, String>();
        // Skip any instances of well-known required headers that might have been supplied by the
        // caller.
        for (Map.Entry<String, String> paramEntry : queryParameters.entrySet()) {
            // Convert to (and check for the existence of) lowercase keys to prevent cases like a user
            // supplying "x-goog-algorithm", in order to prevent the resulting query string from
            // containing "x-goog-algorithm" and "X-Goog-Algorithm".
            if (!RESERVED_PARAMETER_NAMES_LOWERCASE.contains(paramEntry.getKey().toLowerCase())) {
                // URI encode user-supplied parameter, both the name and the value.
                orderedParamMap.put(rfc3986UriEncode(paramEntry.getKey(), true), rfc3986UriEncode(paramEntry.getValue(), true));
            }
        }
        return orderedParamMap;
    }

    /**
     * Constructs payload to be signed.
     *
     * @return payload to sign
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed URLs</a>
     */
    public String buildUnsignedPayload() {
        // TODO reverse order when V4 becomes default
        if (StorageService.UrlSigningOption.SigningVersion.V4.equals(signingVersion)) {
            return buildV4UnsignedPayload();
        }
        return buildV2UnsignedPayload();
    }

    private String computeV4CanonicalRequestHash() {
        StringBuilder canonicalReq = new StringBuilder();
        CanonicalExtensionHeadersSerializer headerSerializer = new CanonicalExtensionHeadersSerializer(StorageService.UrlSigningOption.SigningVersion.V4);
        canonicalReq.append(requestMethod.name()).append(COMPONENT_SEPARATOR);
        canonicalReq.append(canonicalResource).append(COMPONENT_SEPARATOR);
        canonicalReq.append(buildV4QueryString()).append(COMPONENT_SEPARATOR);
        canonicalReq.append(headerSerializer.serialize(canonicalExtensionHeaders)).append(COMPONENT_SEPARATOR);
        canonicalReq.append(headerSerializer.serializeHeaderNames(canonicalExtensionHeaders)).append(COMPONENT_SEPARATOR);
        String providedHash = canonicalExtensionHeaders.get("X-Goog-Content-SHA256");
        canonicalReq.append(null == providedHash ? "UNSIGNED-PAYLOAD" : providedHash);
        return Hashing.sha256().hashString(canonicalReq.toString(), StandardCharsets.UTF_8).toString();
    }

    public long getTimestamp() {
        return epochMillis;
    }

    public String getAccountEmail() {
        return userEmail;
    }

    public String getContentMd5() {
        return contentHash;
    }

    public HttpRequestMethod getHttpVerb() {
        return requestMethod;
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
        TreeMap<String, String> orderedParamMap = getNonReservedUserQueryParams();
        // The "GoogleAccessId", "Expires", and "Signature" params are not included here.
        return buildQueryStringFromParamMap(orderedParamMap);
    }

    public String getContentType() {
        return mimeType;
    }

    private SigningContext(SignedUrlBuilder signedUrlCreator) {
        this.requestMethod = signedUrlCreator.requestMethod;
        this.contentHash = signedUrlCreator.contentHash;
        this.mimeType = signedUrlCreator.mimeType;
        this.expiryMillis = signedUrlCreator.expiryMillis;
        this.canonicalResource = signedUrlCreator.canonicalResource;
        this.signingVersion = signedUrlCreator.signingVersion;
        this.userEmail = signedUrlCreator.userEmail;
        this.epochMillis = signedUrlCreator.epochMillis;
        ImmutableMap.Builder<String, String> headerCollector = new ImmutableMap.Builder<String, String>().putAll(signedUrlCreator.canonicalExtensionHeaders);
        // The "host" header only needs to be present and signed if using V4.
        if (StorageService.UrlSigningOption.SigningVersion.V4.equals(signingVersion) && (!signedUrlCreator.canonicalExtensionHeaders.containsKey("host"))) {
            headerCollector.put("host", "storage.googleapis.com");
        }
        canonicalExtensionHeaders = headerCollector.build();
        queryParameters = ImmutableMap.<String, String>copyOf(signedUrlCreator.queryParameters);
        Date now = new Date(epochMillis);
        SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat isoDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        isoDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        ymdString = ymdFormatter.format(now);
        isoDateString = isoDateFormatter.format(now);
    }

}
