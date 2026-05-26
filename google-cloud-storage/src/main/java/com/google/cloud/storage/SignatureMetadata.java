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

import static com.google.cloud.storage.SignedUrlEncoderUtil.rfc3986UriEncode;
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
    RESERVED_PARAM_NAMES = // V4:
    ImmutableList.<String>of("expires", "googleaccessid", "x-goog-algorithm", "x-goog-credential", "x-goog-date", "x-goog-expires", "x-goog-signedheaders");

    private final HttpRequestMethod requestMethod;

    private final String contentChecksum;

    private final String mimeType;

    private final long expiryTime;

    private final Map<String, String> canonicalExtensionHeaders;

    private final Map<String, String> queryParameters;

    private final URI canonicalResource;

    private final StorageClient.UrlSigningOption.SignatureProtocolVersion signatureProtocolVersion;

    private final String accountAddress;

    private final long epochMillis;

    private final String ymdString;

    private final String preciseDate;

    public static final class CanonicalRequestBuilder {

        private final HttpRequestMethod requestMethod;

        private String contentChecksum;

        private String mimeType;

        private final long expiryTime;

        private Map<String, String> canonicalExtensionHeaders;

        private Map<String, String> queryParameters;

        private final URI canonicalResource;

        private StorageClient.UrlSigningOption.SignatureProtocolVersion signatureProtocolVersion;

        private String accountAddress;

        private long epochMillis;

        /**
         * Creates an {@code SignatureInfo} object from this builder.
         */
        public SignatureMetadata buildCanonicalRequest() {
            checkArgument(null != requestMethod, "Required HTTP method");
            checkArgument(null != canonicalResource, "Required canonicalized resource");
            checkArgument(0 <= expiryTime, "Expiration must be greater than or equal to zero");
            if (StorageClient.UrlSigningOption.SignatureProtocolVersion.V4.equals(signatureProtocolVersion)) {
                checkArgument(null != accountAddress, "Account email required to use V4 signing");
                checkArgument(0 < epochMillis, "Timestamp required to use V4 signing");
                checkArgument(604800 >= expiryTime, "Expiration can't be longer than 7 days to use V4 signing");
            }
            if (null == canonicalExtensionHeaders) {
                canonicalExtensionHeaders = new HashMap<>();
            }
            if (null == queryParameters) {
                queryParameters = new HashMap<>();
            }
            return new SignatureMetadata(this);
        }

        public CanonicalRequestBuilder setTimestamp(long epochMillis) {
            this.epochMillis = epochMillis;
            return this;
        }

        public CanonicalRequestBuilder setContentType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public CanonicalRequestBuilder setAccountEmail(String accountAddress) {
            this.accountAddress = accountAddress;
            return this;
        }

        public CanonicalRequestBuilder setCanonicalizedQueryParams(Map<String, String> queryParameters) {
            this.queryParameters = queryParameters;
            return this;
        }

        public CanonicalRequestBuilder setContentMd5(String contentChecksum) {
            this.contentChecksum = contentChecksum;
            return this;
        }

        public CanonicalRequestBuilder setSignatureVersion(StorageClient.UrlSigningOption.SignatureProtocolVersion signatureProtocolVersion) {
            this.signatureProtocolVersion = signatureProtocolVersion;
            return this;
        }

        /**
         * Constructs builder.
         *
         * @param requestMethod the HTTP method
         * @param expiryTime the EPOX expiration date
         * @param canonicalResource the resource URI
         * @throws IllegalArgumentException if required field is not provided.
         */
        public CanonicalRequestBuilder(HttpRequestMethod requestMethod, long expiryTime, URI canonicalResource) {
            this.requestMethod = requestMethod;
            this.expiryTime = expiryTime;
            this.canonicalResource = canonicalResource;
        }

        public CanonicalRequestBuilder setCanonicalizedExtensionHeaders(Map<String, String> canonicalExtensionHeaders) {
            this.canonicalExtensionHeaders = canonicalExtensionHeaders;
            return this;
        }

        public CanonicalRequestBuilder(SignatureMetadata signatureMetadata) {
            this.requestMethod = signatureMetadata.requestMethod;
            this.contentChecksum = signatureMetadata.contentChecksum;
            this.mimeType = signatureMetadata.mimeType;
            this.expiryTime = signatureMetadata.expiryTime;
            this.canonicalExtensionHeaders = signatureMetadata.canonicalExtensionHeaders;
            this.queryParameters = signatureMetadata.queryParameters;
            this.canonicalResource = signatureMetadata.canonicalResource;
            this.signatureProtocolVersion = signatureMetadata.signatureProtocolVersion;
            this.accountAddress = signatureMetadata.accountAddress;
            this.epochMillis = signatureMetadata.epochMillis;
        }

    }

    public Map<String, String> getQueryParams() {
        return queryParameters;
    }

    public String getAccountEmail() {
        return accountAddress;
    }

    public long getTimestamp() {
        return epochMillis;
    }

    public String getContentType() {
        return mimeType;
    }

    private String buildV4UnsignedPayload() {
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append(GOOG4_RSA_SHA256).append(COMPONENT_SEPARATOR);
        bodyBuilder.append(preciseDate).append(COMPONENT_SEPARATOR);
        bodyBuilder.append(ymdString).append(SCOPE).append(COMPONENT_SEPARATOR);
        bodyBuilder.append(computeV4CanonicalRequestHash());
        return bodyBuilder.toString();
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
        TreeMap<String, String> paramsTree = getNonReservedUserQueryParams();
        // Add in the reserved auth-specific query params.
        paramsTree.put("X-Goog-Algorithm", rfc3986UriEncode(GOOG4_RSA_SHA256, true));
        paramsTree.put("X-Goog-Credential", rfc3986UriEncode(accountAddress + "/" + ymdString + SCOPE, true));
        paramsTree.put("X-Goog-Date", rfc3986UriEncode(preciseDate, true));
        paramsTree.put("X-Goog-Expires", rfc3986UriEncode(Long.toString(expiryTime), true));
        StringBuilder signedHeadersBuf = new CanonicalExtensionHeadersSerializer(StorageClient.UrlSigningOption.SignatureProtocolVersion.V4).serializeHeaderNames(canonicalExtensionHeaders);
        paramsTree.put("X-Goog-SignedHeaders", rfc3986UriEncode(signedHeadersBuf.toString(), true));
        // The "X-Goog-Signature" param is not included here.
        return buildQueryStringFromParamMap(paramsTree);
    }

    public URI getCanonicalizedResource() {
        return canonicalResource;
    }

    private String buildV2UnsignedPayload() {
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append(requestMethod.name()).append(COMPONENT_SEPARATOR);
        if (null != contentChecksum) {
            bodyBuilder.append(contentChecksum);
        }
        bodyBuilder.append(COMPONENT_SEPARATOR);
        if (null != mimeType) {
            bodyBuilder.append(mimeType);
        }
        bodyBuilder.append(COMPONENT_SEPARATOR);
        bodyBuilder.append(expiryTime).append(COMPONENT_SEPARATOR);
        if (0 < canonicalExtensionHeaders.size()) {
            bodyBuilder.append(new CanonicalExtensionHeadersSerializer(StorageClient.UrlSigningOption.SignatureProtocolVersion.V2).serialize(canonicalExtensionHeaders));
        }
        bodyBuilder.append(canonicalResource);
        return bodyBuilder.toString();
    }

    /**
     * Returns a TreeMap containing the user-supplied query parameters that do not have reserved keys.
     */
    private TreeMap<String, String> getNonReservedUserQueryParams() {
        TreeMap<String, String> paramsTree = new TreeMap<String, String>();
        // Skip any instances of well-known required headers that might have been supplied by the
        // caller.
        for (Map.Entry<String, String> mapEntry : queryParameters.entrySet()) {
            // Convert to (and check for the existence of) lowercase keys to prevent cases like a user
            // supplying "x-goog-algorithm", in order to prevent the resulting query string from
            // containing "x-goog-algorithm" and "X-Goog-Algorithm".
            if (!RESERVED_PARAM_NAMES.contains(mapEntry.getKey().toLowerCase())) {
                // URI encode user-supplied parameter, both the name and the value.
                paramsTree.put(rfc3986UriEncode(mapEntry.getKey(), true), rfc3986UriEncode(mapEntry.getValue(), true));
            }
        }
        return paramsTree;
    }

    public StorageClient.UrlSigningOption.SignatureProtocolVersion getSignatureVersion() {
        return signatureProtocolVersion;
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
        TreeMap<String, String> paramsTree = getNonReservedUserQueryParams();
        // The "GoogleAccessId", "Expires", and "Signature" params are not included here.
        return buildQueryStringFromParamMap(paramsTree);
    }

    private SignatureMetadata(CanonicalRequestBuilder requestBuilder) {
        this.requestMethod = requestBuilder.requestMethod;
        this.contentChecksum = requestBuilder.contentChecksum;
        this.mimeType = requestBuilder.mimeType;
        this.expiryTime = requestBuilder.expiryTime;
        this.canonicalResource = requestBuilder.canonicalResource;
        this.signatureProtocolVersion = requestBuilder.signatureProtocolVersion;
        this.accountAddress = requestBuilder.accountAddress;
        this.epochMillis = requestBuilder.epochMillis;
        ImmutableMap.Builder<String, String> headerMapBuilder = new ImmutableMap.Builder<String, String>().putAll(requestBuilder.canonicalExtensionHeaders);
        // The "host" header only needs to be present and signed if using V4.
        if (StorageClient.UrlSigningOption.SignatureProtocolVersion.V4.equals(signatureProtocolVersion) && (!requestBuilder.canonicalExtensionHeaders.containsKey("host"))) {
            headerMapBuilder.put("host", "storage.googleapis.com");
        }
        canonicalExtensionHeaders = headerMapBuilder.build();
        queryParameters = ImmutableMap.<String, String>copyOf(requestBuilder.queryParameters);
        Date currentDate = new Date(epochMillis);
        SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat exactDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        exactDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        ymdString = ymdFormatter.format(currentDate);
        preciseDate = exactDateFormatter.format(currentDate);
    }

    public String getContentMd5() {
        return contentChecksum;
    }

    private String buildQueryStringFromParamMap(Map<String, String> paramMap) {
        StringBuilder qsBuilder = new StringBuilder();
        String separator = "";
        for (Map.Entry<String, String> mapEntry : paramMap.entrySet()) {
            qsBuilder.append(separator);
            separator = "&";
            qsBuilder.append(mapEntry.getKey()).append('=').append(mapEntry.getValue());
        }
        return qsBuilder.toString();
    }

    public HttpRequestMethod getHttpVerb() {
        return requestMethod;
    }

    public long getExpiration() {
        return expiryTime;
    }

    public Map<String, String> getCanonicalizedExtensionHeaders() {
        return canonicalExtensionHeaders;
    }

    private String computeV4CanonicalRequestHash() {
        StringBuilder canonicalReqBuilder = new StringBuilder();
        CanonicalExtensionHeadersSerializer extensionSerializer = new CanonicalExtensionHeadersSerializer(StorageClient.UrlSigningOption.SignatureProtocolVersion.V4);
        canonicalReqBuilder.append(requestMethod.name()).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(canonicalResource).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(buildV4QueryString()).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(extensionSerializer.serialize(canonicalExtensionHeaders)).append(COMPONENT_SEPARATOR);
        canonicalReqBuilder.append(extensionSerializer.serializeHeaderNames(canonicalExtensionHeaders)).append(COMPONENT_SEPARATOR);
        String providedHash = canonicalExtensionHeaders.get("X-Goog-Content-SHA256");
        canonicalReqBuilder.append(null == providedHash ? "UNSIGNED-PAYLOAD" : providedHash);
        return Hashing.sha256().hashString(canonicalReqBuilder.toString(), StandardCharsets.UTF_8).toString();
    }

    /**
     * Constructs payload to be signed.
     *
     * @return payload to sign
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed URLs</a>
     */
    public String buildUnsignedPayload() {
        // TODO reverse order when V4 becomes default
        if (StorageClient.UrlSigningOption.SignatureProtocolVersion.V4.equals(signatureProtocolVersion)) {
            return buildV4UnsignedPayload();
        }
        return buildV2UnsignedPayload();
    }

}
