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
public class SignatureDetails {

    public static final char COMPONENT_SEPARATOR = '\n';

    public static final String GOOG4_RSA_SHA256 = "GOOG4-RSA-SHA256";

    public static final String SCOPE = "/auto/storage/goog4_request";

    private static final List<String> // V2:
    // V2:
    // V2:
    // V2:
    // V4:
    SIGNATURE_RESERVED_KEYS = // V4:
    ImmutableList.<String>of("expires", "googleaccessid", "x-goog-algorithm", "x-goog-credential", "x-goog-date", "x-goog-expires", "x-goog-signedheaders");

    private final HttpRequestMethod httpMethod;

    private final String contentChecksum;

    private final String mediaType;

    private final long expiryTimestamp;

    private final Map<String, String> canonicalExtHeaders;

    private final Map<String, String> queryParameters;

    private final URI canonicalResource;

    private final CloudStorageClient.UrlSigningOption.SignatureProtocolVersion protocolVersion;

    private final String accountAddress;

    private final long timeMillis;

    private final String yyyymmddString;

    private final String preciseDate;

    private SignatureDetails(CanonicalStringBuilder canonicalString) {
        this.httpMethod = canonicalString.httpMethod;
        this.contentChecksum = canonicalString.contentChecksum;
        this.mediaType = canonicalString.mediaType;
        this.expiryTimestamp = canonicalString.expiryTimestamp;
        this.canonicalResource = canonicalString.canonicalResource;
        this.protocolVersion = canonicalString.protocolVersion;
        this.accountAddress = canonicalString.accountAddress;
        this.timeMillis = canonicalString.timeMillis;
        ImmutableMap.Builder<String, String> headerFactory = new ImmutableMap.Builder<String, String>().putAll(canonicalString.canonicalExtHeaders);
        // The "host" header only needs to be present and signed if using V4.
        if (CloudStorageClient.UrlSigningOption.SignatureProtocolVersion.V4.equals(protocolVersion) && (!canonicalString.canonicalExtHeaders.containsKey("host"))) {
            headerFactory.put("host", "storage.googleapis.com");
        }
        canonicalExtHeaders = headerFactory.build();
        queryParameters = ImmutableMap.<String, String>copyOf(canonicalString.queryParameters);
        Date now = new Date(timeMillis);
        SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat preciseDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        preciseDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        yyyymmddString = ymdFormatter.format(now);
        preciseDate = preciseDateFormatter.format(now);
    }

    /**
     * Constructs payload to be signed.
     *
     * @return payload to sign
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed URLs</a>
     */
    public String buildUnsignedPayload() {
        // TODO reverse order when V4 becomes default
        if (CloudStorageClient.UrlSigningOption.SignatureProtocolVersion.V4.equals(protocolVersion)) {
            return buildV4UnsignedPayload();
        }
        return buildV2UnsignedPayload();
    }

    private String buildV2UnsignedPayload() {
        StringBuilder dataBuilder = new StringBuilder();
        dataBuilder.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
        if (null != contentChecksum) {
            dataBuilder.append(contentChecksum);
        }
        dataBuilder.append(COMPONENT_SEPARATOR);
        if (null != mediaType) {
            dataBuilder.append(mediaType);
        }
        dataBuilder.append(COMPONENT_SEPARATOR);
        dataBuilder.append(expiryTimestamp).append(COMPONENT_SEPARATOR);
        if (0 < canonicalExtHeaders.size()) {
            dataBuilder.append(new CanonicalExtensionHeadersSerializer(CloudStorageClient.UrlSigningOption.SignatureProtocolVersion.V2).serialize(canonicalExtHeaders));
        }
        dataBuilder.append(canonicalResource);
        return dataBuilder.toString();
    }

    private String buildV4UnsignedPayload() {
        StringBuilder dataBuilder = new StringBuilder();
        dataBuilder.append(GOOG4_RSA_SHA256).append(COMPONENT_SEPARATOR);
        dataBuilder.append(preciseDate).append(COMPONENT_SEPARATOR);
        dataBuilder.append(yyyymmddString).append(SCOPE).append(COMPONENT_SEPARATOR);
        dataBuilder.append(computeV4CanonicalRequestHash());
        return dataBuilder.toString();
    }

    private String computeV4CanonicalRequestHash() {
        StringBuilder canonicalReqBuilder = new StringBuilder();
        CanonicalExtensionHeadersSerializer extHeadersSerializer = new CanonicalExtensionHeadersSerializer(CloudStorageClient.UrlSigningOption.SignatureProtocolVersion.V4);
        canonicalReqBuilder.append(httpMethod.name()).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(canonicalResource).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(buildV4QueryString()).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(extHeadersSerializer.serialize(canonicalExtHeaders)).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(extHeadersSerializer.serializeHeaderNames(canonicalExtHeaders)).append(COMPONENT_SEPARATOR);
        String providedHash = canonicalExtHeaders.get("X-Goog-Content-SHA256");
        canonicalReqBuilder.append(null == providedHash ? "UNSIGNED-PAYLOAD" : providedHash);
        return Hashing.sha256().hashString(canonicalReqBuilder.toString(), StandardCharsets.UTF_8).toString();
    }

    /**
     * Returns a TreeMap containing the user-supplied query parameters that do not have reserved keys.
     */
    private TreeMap<String, String> getNonReservedUserQueryParams() {
        TreeMap<String, String> paramsTreeMap = new TreeMap<String, String>();
        // Skip any instances of well-known required headers that might have been supplied by the
        // caller.
        for (Map.Entry<String, String> paramEntry : queryParameters.entrySet()) {
            // Convert to (and check for the existence of) lowercase keys to prevent cases like a user
            // supplying "x-goog-algorithm", in order to prevent the resulting query string from
            // containing "x-goog-algorithm" and "X-Goog-Algorithm".
            if (!SIGNATURE_RESERVED_KEYS.contains(paramEntry.getKey().toLowerCase())) {
                // URI encode user-supplied parameter, both the name and the value.
                paramsTreeMap.put(rfc3986UriEncode(paramEntry.getKey(), true), rfc3986UriEncode(paramEntry.getValue(), true));
            }
        }
        return paramsTreeMap;
    }

    private String buildQueryStringFromParamMap(Map<String, String> params) {
        StringBuilder qsBuilder = new StringBuilder();
        String separator = "";
        for (Map.Entry<String, String> paramEntry : params.entrySet()) {
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
        TreeMap<String, String> paramsTreeMap = getNonReservedUserQueryParams();
        // The "GoogleAccessId", "Expires", and "Signature" params are not included here.
        return buildQueryStringFromParamMap(paramsTreeMap);
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
        TreeMap<String, String> paramsTreeMap = getNonReservedUserQueryParams();
        // Add in the reserved auth-specific query params.
        paramsTreeMap.put("X-Goog-Algorithm", rfc3986UriEncode(GOOG4_RSA_SHA256, true));
        paramsTreeMap.put("X-Goog-Credential", rfc3986UriEncode(accountAddress + "/" + yyyymmddString + SCOPE, true));
        paramsTreeMap.put("X-Goog-Date", rfc3986UriEncode(preciseDate, true));
        paramsTreeMap.put("X-Goog-Expires", rfc3986UriEncode(Long.toString(expiryTimestamp), true));
        StringBuilder signedHeadersSb = new CanonicalExtensionHeadersSerializer(CloudStorageClient.UrlSigningOption.SignatureProtocolVersion.V4).serializeHeaderNames(canonicalExtHeaders);
        paramsTreeMap.put("X-Goog-SignedHeaders", rfc3986UriEncode(signedHeadersSb.toString(), true));
        // The "X-Goog-Signature" param is not included here.
        return buildQueryStringFromParamMap(paramsTreeMap);
    }

    public HttpRequestMethod getHttpVerb() {
        return httpMethod;
    }

    public String getContentMd5() {
        return contentChecksum;
    }

    public String getContentType() {
        return mediaType;
    }

    public long getExpiration() {
        return expiryTimestamp;
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

    public CloudStorageClient.UrlSigningOption.SignatureProtocolVersion getSignatureVersion() {
        return protocolVersion;
    }

    public long getTimestamp() {
        return timeMillis;
    }

    public String getAccountEmail() {
        return accountAddress;
    }

    public static final class CanonicalStringBuilder {

        private final HttpRequestMethod httpMethod;

        private String contentChecksum;

        private String mediaType;

        private final long expiryTimestamp;

        private Map<String, String> canonicalExtHeaders;

        private Map<String, String> queryParameters;

        private final URI canonicalResource;

        private CloudStorageClient.UrlSigningOption.SignatureProtocolVersion protocolVersion;

        private String accountAddress;

        private long timeMillis;

        /**
         * Constructs builder.
         *
         * @param httpMethod the HTTP method
         * @param expiryTimestamp the EPOX expiration date
         * @param canonicalResource the resource URI
         * @throws IllegalArgumentException if required field is not provided.
         */
        public CanonicalStringBuilder(HttpRequestMethod httpMethod, long expiryTimestamp, URI canonicalResource) {
            this.httpMethod = httpMethod;
            this.expiryTimestamp = expiryTimestamp;
            this.canonicalResource = canonicalResource;
        }

        public CanonicalStringBuilder(SignatureDetails signingDetails) {
            this.httpMethod = signingDetails.httpMethod;
            this.contentChecksum = signingDetails.contentChecksum;
            this.mediaType = signingDetails.mediaType;
            this.expiryTimestamp = signingDetails.expiryTimestamp;
            this.canonicalExtHeaders = signingDetails.canonicalExtHeaders;
            this.queryParameters = signingDetails.queryParameters;
            this.canonicalResource = signingDetails.canonicalResource;
            this.protocolVersion = signingDetails.protocolVersion;
            this.accountAddress = signingDetails.accountAddress;
            this.timeMillis = signingDetails.timeMillis;
        }

        public CanonicalStringBuilder setContentMd5(String contentChecksum) {
            this.contentChecksum = contentChecksum;
            return this;
        }

        public CanonicalStringBuilder setContentType(String mediaType) {
            this.mediaType = mediaType;
            return this;
        }

        public CanonicalStringBuilder setCanonicalizedExtensionHeaders(Map<String, String> canonicalExtHeaders) {
            this.canonicalExtHeaders = canonicalExtHeaders;
            return this;
        }

        public CanonicalStringBuilder setCanonicalizedQueryParams(Map<String, String> queryParameters) {
            this.queryParameters = queryParameters;
            return this;
        }

        public CanonicalStringBuilder setSignatureVersion(CloudStorageClient.UrlSigningOption.SignatureProtocolVersion protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        public CanonicalStringBuilder setAccountEmail(String accountAddress) {
            this.accountAddress = accountAddress;
            return this;
        }

        public CanonicalStringBuilder setTimestamp(long timeMillis) {
            this.timeMillis = timeMillis;
            return this;
        }

        /**
         * Creates an {@code SignatureInfo} object from this builder.
         */
        public SignatureDetails buildCanonicalString() {
            checkArgument(null != httpMethod, "Required HTTP method");
            checkArgument(null != canonicalResource, "Required canonicalized resource");
            checkArgument(0 <= expiryTimestamp, "Expiration must be greater than or equal to zero");
            if (CloudStorageClient.UrlSigningOption.SignatureProtocolVersion.V4.equals(protocolVersion)) {
                checkArgument(null != accountAddress, "Account email required to use V4 signing");
                checkArgument(0 < timeMillis, "Timestamp required to use V4 signing");
                checkArgument(604800 >= expiryTimestamp, "Expiration can't be longer than 7 days to use V4 signing");
            }
            if (null == canonicalExtHeaders) {
                canonicalExtHeaders = new HashMap<>();
            }
            if (null == queryParameters) {
                queryParameters = new HashMap<>();
            }
            return new SignatureDetails(this);
        }
    }
}
