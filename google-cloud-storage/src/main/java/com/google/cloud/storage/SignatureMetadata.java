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
public class SignatureMetadata {

    public static final char COMPONENT_SEPARATOR = '\n';

    public static final String GOOG4_RSA_SHA256 = "GOOG4-RSA-SHA256";

    public static final String SCOPE = "/auto/storage/goog4_request";

    private static final List<String> // V2:
    // V2:
    // V2:
    // V2:
    // V4:
    RESERVED_PARAM_KEYS = // V4:
    ImmutableList.<String>of("expires", "googleaccessid", "x-goog-algorithm", "x-goog-credential", "x-goog-date", "x-goog-expires", "x-goog-signedheaders");

    private final HttpRequestMethod httpMethod;

    private final String contentChecksum;

    private final String mimeType;

    private final long expiresAt;

    private final Map<String, String> canonicalExtensionHeaders;

    private final Map<String, String> queryParameters;

    private final URI canonicalResource;

    private final Storage.UrlSigningOption.SignatureSchemeVersion signatureScheme;

    private final String ownerEmail;

    private final long epochMillis;

    private final String dateYmd;

    private final String preciseDate;

    public static final class CanonicalStringBuilder {

        private final HttpRequestMethod httpMethod;

        private String contentChecksum;

        private String mimeType;

        private final long expiresAt;

        private Map<String, String> canonicalExtensionHeaders;

        private Map<String, String> queryParameters;

        private final URI canonicalResource;

        private Storage.UrlSigningOption.SignatureSchemeVersion signatureScheme;

        private String ownerEmail;

        private long epochMillis;

        public CanonicalStringBuilder setAccountEmail(String ownerEmail) {
            this.ownerEmail = ownerEmail;
            return this;
        }

        public CanonicalStringBuilder setContentType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public CanonicalStringBuilder setCanonicalizedExtensionHeaders(Map<String, String> canonicalExtensionHeaders) {
            this.canonicalExtensionHeaders = canonicalExtensionHeaders;
            return this;
        }

        public CanonicalStringBuilder(SignatureMetadata signatureMetadata) {
            this.httpMethod = signatureMetadata.httpMethod;
            this.contentChecksum = signatureMetadata.contentChecksum;
            this.mimeType = signatureMetadata.mimeType;
            this.expiresAt = signatureMetadata.expiresAt;
            this.canonicalExtensionHeaders = signatureMetadata.canonicalExtensionHeaders;
            this.queryParameters = signatureMetadata.queryParameters;
            this.canonicalResource = signatureMetadata.canonicalResource;
            this.signatureScheme = signatureMetadata.signatureScheme;
            this.ownerEmail = signatureMetadata.ownerEmail;
            this.epochMillis = signatureMetadata.epochMillis;
        }

        public CanonicalStringBuilder setSignatureVersion(Storage.UrlSigningOption.SignatureSchemeVersion signatureScheme) {
            this.signatureScheme = signatureScheme;
            return this;
        }

        /**
         * Creates an {@code SignatureInfo} object from this builder.
         */
        public SignatureMetadata buildCanonicalString() {
            checkArgument(null != httpMethod, "Required HTTP method");
            checkArgument(null != canonicalResource, "Required canonicalized resource");
            checkArgument(0 <= expiresAt, "Expiration must be greater than or equal to zero");
            if (Storage.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureScheme)) {
                checkArgument(null != ownerEmail, "Account email required to use V4 signing");
                checkArgument(0 < epochMillis, "Timestamp required to use V4 signing");
                checkArgument(604800 >= expiresAt, "Expiration can't be longer than 7 days to use V4 signing");
            }
            if (null == canonicalExtensionHeaders) {
                canonicalExtensionHeaders = new HashMap<>();
            }
            if (null == queryParameters) {
                queryParameters = new HashMap<>();
            }
            return new SignatureMetadata(this);
        }

        public CanonicalStringBuilder setContentMd5(String contentChecksum) {
            this.contentChecksum = contentChecksum;
            return this;
        }

        public CanonicalStringBuilder setCanonicalizedQueryParams(Map<String, String> queryParameters) {
            this.queryParameters = queryParameters;
            return this;
        }

        /**
         * Constructs builder.
         *
         * @param httpMethod the HTTP method
         * @param expiresAt the EPOX expiration date
         * @param canonicalResource the resource URI
         * @throws IllegalArgumentException if required field is not provided.
         */
        public CanonicalStringBuilder(HttpRequestMethod httpMethod, long expiresAt, URI canonicalResource) {
            this.httpMethod = httpMethod;
            this.expiresAt = expiresAt;
            this.canonicalResource = canonicalResource;
        }

        public CanonicalStringBuilder setTimestamp(long epochMillis) {
            this.epochMillis = epochMillis;
            return this;
        }

    }

    public HttpRequestMethod getHttpVerb() {
        return httpMethod;
    }

    public String getContentType() {
        return mimeType;
    }

    private String buildV4CanonicalRequestHash() {
        StringBuilder canonicalReqBuilder = new StringBuilder();
        CanonicalExtensionHeadersSerializer extensionHeaderSerializer = new CanonicalExtensionHeadersSerializer(Storage.UrlSigningOption.SignatureSchemeVersion.V4);
        canonicalReqBuilder.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(canonicalResource).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(buildV4QueryString()).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(extensionHeaderSerializer.serialize(canonicalExtensionHeaders)).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(extensionHeaderSerializer.serializeHeaderNames(canonicalExtensionHeaders)).append(COMPONENT_SEPARATOR);
        String externalHash = canonicalExtensionHeaders.get("X-Goog-Content-SHA256");
        canonicalReqBuilder.append(null == externalHash ? "UNSIGNED-PAYLOAD" : externalHash);
        return Hashing.sha256().hashString(canonicalReqBuilder.toString(), StandardCharsets.UTF_8).toString();
    }

    public long getExpiration() {
        return expiresAt;
    }

    public String getAccountEmail() {
        return ownerEmail;
    }

    public String getContentMd5() {
        return contentChecksum;
    }

    public Map<String, String> getQueryParams() {
        return queryParameters;
    }

    private String buildV4UnsignedPayload() {
        StringBuilder dataBuffer = new StringBuilder();
        dataBuffer.append(GOOG4_RSA_SHA256).append(COMPONENT_SEPARATOR);
        dataBuffer.append(preciseDate).append(COMPONENT_SEPARATOR);
        dataBuffer.append(dateYmd).append(SCOPE).append(COMPONENT_SEPARATOR);
        dataBuffer.append(buildV4CanonicalRequestHash());
        return dataBuffer.toString();
    }

    private String buildV2UnsignedPayload() {
        StringBuilder dataBuffer = new StringBuilder();
        dataBuffer.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
        if (null != contentChecksum) {
            dataBuffer.append(contentChecksum);
        }
        dataBuffer.append(COMPONENT_SEPARATOR);
        if (null != mimeType) {
            dataBuffer.append(mimeType);
        }
        dataBuffer.append(COMPONENT_SEPARATOR);
        dataBuffer.append(expiresAt).append(COMPONENT_SEPARATOR);
        if (0 < canonicalExtensionHeaders.size()) {
            dataBuffer.append(new CanonicalExtensionHeadersSerializer(Storage.UrlSigningOption.SignatureSchemeVersion.V2).serialize(canonicalExtensionHeaders));
        }
        dataBuffer.append(canonicalResource);
        return dataBuffer.toString();
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
        TreeMap<String, String> orderedParamTree = getNonReservedUserQueryParams();
        // The "GoogleAccessId", "Expires", and "Signature" params are not included here.
        return buildQueryStringFromParams(orderedParamTree);
    }

    public URI getCanonicalizedResource() {
        return canonicalResource;
    }

    public Storage.UrlSigningOption.SignatureSchemeVersion getSignatureVersion() {
        return signatureScheme;
    }

    private SignatureMetadata(CanonicalStringBuilder canonicalStringBuilder) {
        this.httpMethod = canonicalStringBuilder.httpMethod;
        this.contentChecksum = canonicalStringBuilder.contentChecksum;
        this.mimeType = canonicalStringBuilder.mimeType;
        this.expiresAt = canonicalStringBuilder.expiresAt;
        this.canonicalResource = canonicalStringBuilder.canonicalResource;
        this.signatureScheme = canonicalStringBuilder.signatureScheme;
        this.ownerEmail = canonicalStringBuilder.ownerEmail;
        this.epochMillis = canonicalStringBuilder.epochMillis;
        ImmutableMap.Builder<String, String> headersMapBuilder = new ImmutableMap.Builder<String, String>().putAll(canonicalStringBuilder.canonicalExtensionHeaders);
        // The "host" header only needs to be present and signed if using V4.
        if (Storage.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureScheme) && (!canonicalStringBuilder.canonicalExtensionHeaders.containsKey("host"))) {
            headersMapBuilder.put("host", "storage.googleapis.com");
        }
        canonicalExtensionHeaders = headersMapBuilder.build();
        queryParameters = ImmutableMap.<String, String>copyOf(canonicalStringBuilder.queryParameters);
        Date currentDate = new Date(epochMillis);
        SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat isoExactFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        isoExactFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        dateYmd = ymdFormatter.format(currentDate);
        preciseDate = isoExactFormatter.format(currentDate);
    }

    public long getTimestamp() {
        return epochMillis;
    }

    /**
     * Returns a TreeMap containing the user-supplied query parameters that do not have reserved keys.
     */
    private TreeMap<String, String> getNonReservedUserQueryParams() {
        TreeMap<String, String> orderedParamTree = new TreeMap<String, String>();
        // Skip any instances of well-known required headers that might have been supplied by the
        // caller.
        for (Map.Entry<String, String> mapPair : queryParameters.entrySet()) {
            // Convert to (and check for the existence of) lowercase keys to prevent cases like a user
            // supplying "x-goog-algorithm", in order to prevent the resulting query string from
            // containing "x-goog-algorithm" and "X-Goog-Algorithm".
            if (!RESERVED_PARAM_KEYS.contains(mapPair.getKey().toLowerCase())) {
                // URI encode user-supplied parameter, both the name and the value.
                orderedParamTree.put(rfc3986UriEncode(mapPair.getKey(), true), rfc3986UriEncode(mapPair.getValue(), true));
            }
        }
        return orderedParamTree;
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
        TreeMap<String, String> orderedParamTree = getNonReservedUserQueryParams();
        // Add in the reserved auth-specific query params.
        orderedParamTree.put("X-Goog-Algorithm", rfc3986UriEncode(GOOG4_RSA_SHA256, true));
        orderedParamTree.put("X-Goog-Credential", rfc3986UriEncode(ownerEmail + "/" + dateYmd + SCOPE, true));
        orderedParamTree.put("X-Goog-Date", rfc3986UriEncode(preciseDate, true));
        orderedParamTree.put("X-Goog-Expires", rfc3986UriEncode(Long.toString(expiresAt), true));
        StringBuilder signedHeadersBuf = new CanonicalExtensionHeadersSerializer(Storage.UrlSigningOption.SignatureSchemeVersion.V4).serializeHeaderNames(canonicalExtensionHeaders);
        orderedParamTree.put("X-Goog-SignedHeaders", rfc3986UriEncode(signedHeadersBuf.toString(), true));
        // The "X-Goog-Signature" param is not included here.
        return buildQueryStringFromParams(orderedParamTree);
    }

    /**
     * Constructs payload to be signed.
     *
     * @return payload to sign
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed URLs</a>
     */
    public String buildUnsignedPayload() {
        // TODO reverse order when V4 becomes default
        if (Storage.UrlSigningOption.SignatureSchemeVersion.V4.equals(signatureScheme)) {
            return buildV4UnsignedPayload();
        }
        return buildV2UnsignedPayload();
    }

    public Map<String, String> getCanonicalizedExtensionHeaders() {
        return canonicalExtensionHeaders;
    }

    private String buildQueryStringFromParams(Map<String, String> paramsMap) {
        StringBuilder qsBuilder = new StringBuilder();
        String separator = "";
        for (Map.Entry<String, String> mapPair : paramsMap.entrySet()) {
            qsBuilder.append(separator);
            separator = "&";
            qsBuilder.append(mapPair.getKey()).append('=').append(mapPair.getValue());
        }
        return qsBuilder.toString();
    }

}
