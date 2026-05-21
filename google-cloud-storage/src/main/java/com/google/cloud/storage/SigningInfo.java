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

import static com.google.cloud.storage.SignedUrlEncoder.encodeRfc3986Uri;
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
public class SigningInfo {

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

    private final String contentDigest;

    private final String mimeType;

    private final long expiry;

    private final Map<String, String> canonicalExtensionHeadersMap;

    private final Map<String, String> queryParameters;

    private final URI canonicalResource;

    private final StorageService.UrlSigningOption.SigningVersion signingVersion;

    private final String accountAddress;

    private final long epochSeconds;

    private final String dateYmd;

    private final String preciseDate;

    private SigningInfo(RequestSignatureBuilder signatureCreator) {
        this.requestMethod = signatureCreator.requestMethod;
        this.contentDigest = signatureCreator.contentDigest;
        this.mimeType = signatureCreator.mimeType;
        this.expiry = signatureCreator.expiry;
        this.canonicalResource = signatureCreator.canonicalResource;
        this.signingVersion = signatureCreator.signingVersion;
        this.accountAddress = signatureCreator.accountAddress;
        this.epochSeconds = signatureCreator.epochSeconds;
        ImmutableMap.Builder<String, String> headerAssembler = new ImmutableMap.Builder<String, String>().putAll(signatureCreator.canonicalExtensionHeadersMap);
        // The "host" header only needs to be present and signed if using V4.
        if (StorageService.UrlSigningOption.SigningVersion.V4.equals(signingVersion) && (!signatureCreator.canonicalExtensionHeadersMap.containsKey("host"))) {
            headerAssembler.put("host", "storage.googleapis.com");
        }
        canonicalExtensionHeadersMap = headerAssembler.build();
        queryParameters = ImmutableMap.<String, String>copyOf(signatureCreator.queryParameters);
        Date nowInstant = new Date(epochSeconds);
        SimpleDateFormat ymdFormatter = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat fullDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        ymdFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        fullDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        dateYmd = ymdFormatter.format(nowInstant);
        preciseDate = fullDateFormatter.format(nowInstant);
    }

    /**
     * Constructs payload to be signed.
     *
     * @return payload to sign
     * @see <a href="https://cloud.google.com/storage/docs/access-control#Signed-URLs">Signed URLs</a>
     */
    public String createUnsignedPayload() {
        // TODO reverse order when V4 becomes default
        if (StorageService.UrlSigningOption.SigningVersion.V4.equals(signingVersion)) {
            return createV4UnsignedPayload();
        }
        return createV2UnsignedPayload();
    }

    private String createV2UnsignedPayload() {
        StringBuilder bodyBuilderV2 = new StringBuilder();
        bodyBuilderV2.append(requestMethod.name()).append(COMPONENT_SEPARATOR);
        if (null != contentDigest) {
            bodyBuilderV2.append(contentDigest);
        }
        bodyBuilderV2.append(COMPONENT_SEPARATOR);
        if (null != mimeType) {
            bodyBuilderV2.append(mimeType);
        }
        bodyBuilderV2.append(COMPONENT_SEPARATOR);
        bodyBuilderV2.append(expiry).append(COMPONENT_SEPARATOR);
        if (0 < canonicalExtensionHeadersMap.size()) {
            bodyBuilderV2.append(new CanonicalExtensionHeadersFormatter(StorageService.UrlSigningOption.SigningVersion.V2).serializeExtensionHeaders(canonicalExtensionHeadersMap));
        }
        bodyBuilderV2.append(canonicalResource);
        return bodyBuilderV2.toString();
    }

    private String createV4UnsignedPayload() {
        StringBuilder bodyBuilderV2 = new StringBuilder();
        bodyBuilderV2.append(GOOG4_RSA_SHA256).append(COMPONENT_SEPARATOR);
        bodyBuilderV2.append(preciseDate).append(COMPONENT_SEPARATOR);
        bodyBuilderV2.append(dateYmd).append(SCOPE).append(COMPONENT_SEPARATOR);
        bodyBuilderV2.append(computeV4CanonicalRequestHash());
        return bodyBuilderV2.toString();
    }

    private String computeV4CanonicalRequestHash() {
        StringBuilder canonicalReq = new StringBuilder();
        CanonicalExtensionHeadersFormatter extHeaderFormatter = new CanonicalExtensionHeadersFormatter(StorageService.UrlSigningOption.SigningVersion.V4);
        canonicalReq.append(requestMethod.name()).append(COMPONENT_SEPARATOR);
        canonicalReq.append(canonicalResource).append(COMPONENT_SEPARATOR);
        canonicalReq.append(buildV4QueryString()).append(COMPONENT_SEPARATOR);
        canonicalReq.append(extHeaderFormatter.serializeExtensionHeaders(canonicalExtensionHeadersMap)).append(COMPONENT_SEPARATOR);
        canonicalReq.append(extHeaderFormatter.joinHeaderNames(canonicalExtensionHeadersMap)).append(COMPONENT_SEPARATOR);
        canonicalReq.append("UNSIGNED-PAYLOAD");
        return Hashing.sha256().hashString(canonicalReq.toString(), StandardCharsets.UTF_8).toString();
    }

    /**
     * Returns a TreeMap containing the user-supplied query parameters that do not have reserved keys.
     */
    private TreeMap<String, String> getNonReservedUserQueryParams() {
        TreeMap<String, String> paramTreeMap = new TreeMap<String, String>();
        // Skip any instances of well-known required headers that might have been supplied by the
        // caller.
        for (Map.Entry<String, String> paramEntry : queryParameters.entrySet()) {
            // Convert to (and check for the existence of) lowercase keys to prevent cases like a user
            // supplying "x-goog-algorithm", in order to prevent the resulting query string from
            // containing "x-goog-algorithm" and "X-Goog-Algorithm".
            if (!RESERVED_PARAM_NAMES.contains(paramEntry.getKey().toLowerCase())) {
                // URI encode user-supplied parameter, both the name and the value.
                paramTreeMap.put(encodeRfc3986Uri(paramEntry.getKey(), true), encodeRfc3986Uri(paramEntry.getValue(), true));
            }
        }
        return paramTreeMap;
    }

    private String buildQueryStringFromParamMap(Map<String, String> params) {
        StringBuilder queryBuilder = new StringBuilder();
        String separator = "";
        for (Map.Entry<String, String> paramEntry : params.entrySet()) {
            queryBuilder.append(separator);
            separator = "&";
            queryBuilder.append(paramEntry.getKey()).append('=').append(paramEntry.getValue());
        }
        return queryBuilder.toString();
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
        return buildQueryStringFromParamMap(paramTreeMap);
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
        paramTreeMap.put("X-Goog-Algorithm", encodeRfc3986Uri(GOOG4_RSA_SHA256, true));
        paramTreeMap.put("X-Goog-Credential", encodeRfc3986Uri(accountAddress + "/" + dateYmd + SCOPE, true));
        paramTreeMap.put("X-Goog-Date", encodeRfc3986Uri(preciseDate, true));
        paramTreeMap.put("X-Goog-Expires", encodeRfc3986Uri(Long.toString(expiry), true));
        StringBuilder signedHeadersBuf = new CanonicalExtensionHeadersFormatter(StorageService.UrlSigningOption.SigningVersion.V4).joinHeaderNames(canonicalExtensionHeadersMap);
        paramTreeMap.put("X-Goog-SignedHeaders", encodeRfc3986Uri(signedHeadersBuf.toString(), true));
        // The "X-Goog-Signature" param is not included here.
        return buildQueryStringFromParamMap(paramTreeMap);
    }

    public HttpRequestMethod getHttpVerb() {
        return requestMethod;
    }

    public String getContentMd5() {
        return contentDigest;
    }

    public String getContentType() {
        return mimeType;
    }

    public long getExpiration() {
        return expiry;
    }

    public Map<String, String> getCanonicalizedExtensionHeaders() {
        return canonicalExtensionHeadersMap;
    }

    public Map<String, String> getQueryParams() {
        return queryParameters;
    }

    public URI getCanonicalizedResource() {
        return canonicalResource;
    }

    public StorageService.UrlSigningOption.SigningVersion getSignatureVersion() {
        return signingVersion;
    }

    public long getTimestamp() {
        return epochSeconds;
    }

    public String getAccountEmail() {
        return accountAddress;
    }

    public static final class RequestSignatureBuilder {

        private final HttpRequestMethod requestMethod;

        private String contentDigest;

        private String mimeType;

        private final long expiry;

        private Map<String, String> canonicalExtensionHeadersMap;

        private Map<String, String> queryParameters;

        private final URI canonicalResource;

        private StorageService.UrlSigningOption.SigningVersion signingVersion;

        private String accountAddress;

        private long epochSeconds;

        /**
         * Constructs builder.
         *
         * @param requestMethod the HTTP method
         * @param expiry the EPOX expiration date
         * @param canonicalResource the resource URI
         * @throws IllegalArgumentException if required field is not provided.
         */
        public RequestSignatureBuilder(HttpRequestMethod requestMethod, long expiry, URI canonicalResource) {
            this.requestMethod = requestMethod;
            this.expiry = expiry;
            this.canonicalResource = canonicalResource;
        }

        public RequestSignatureBuilder(SigningInfo signingDetails) {
            this.requestMethod = signingDetails.requestMethod;
            this.contentDigest = signingDetails.contentDigest;
            this.mimeType = signingDetails.mimeType;
            this.expiry = signingDetails.expiry;
            this.canonicalExtensionHeadersMap = signingDetails.canonicalExtensionHeadersMap;
            this.queryParameters = signingDetails.queryParameters;
            this.canonicalResource = signingDetails.canonicalResource;
            this.signingVersion = signingDetails.signingVersion;
            this.accountAddress = signingDetails.accountAddress;
            this.epochSeconds = signingDetails.epochSeconds;
        }

        public RequestSignatureBuilder setContentMd5(String contentDigest) {
            this.contentDigest = contentDigest;
            return this;
        }

        public RequestSignatureBuilder setContentType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public RequestSignatureBuilder setCanonicalizedExtensionHeaders(Map<String, String> canonicalExtensionHeadersMap) {
            this.canonicalExtensionHeadersMap = canonicalExtensionHeadersMap;
            return this;
        }

        public RequestSignatureBuilder setCanonicalizedQueryParams(Map<String, String> queryParameters) {
            this.queryParameters = queryParameters;
            return this;
        }

        public RequestSignatureBuilder setSignatureVersion(StorageService.UrlSigningOption.SigningVersion signingVersion) {
            this.signingVersion = signingVersion;
            return this;
        }

        public RequestSignatureBuilder setAccountEmail(String accountAddress) {
            this.accountAddress = accountAddress;
            return this;
        }

        public RequestSignatureBuilder setTimestamp(long epochSeconds) {
            this.epochSeconds = epochSeconds;
            return this;
        }

        /**
         * Creates an {@code SignatureInfo} object from this builder.
         */
        public SigningInfo buildSigningInfo() {
            checkArgument(null != requestMethod, "Required HTTP method");
            checkArgument(null != canonicalResource, "Required canonicalized resource");
            checkArgument(0 <= expiry, "Expiration must be greater than or equal to zero");
            if (StorageService.UrlSigningOption.SigningVersion.V4.equals(signingVersion)) {
                checkArgument(null != accountAddress, "Account email required to use V4 signing");
                checkArgument(0 < epochSeconds, "Timestamp required to use V4 signing");
                checkArgument(604800 >= expiry, "Expiration can't be longer than 7 days to use V4 signing");
            }
            if (null == canonicalExtensionHeadersMap) {
                canonicalExtensionHeadersMap = new HashMap<>();
            }
            if (null == queryParameters) {
                queryParameters = new HashMap<>();
            }
            return new SigningInfo(this);
        }
    }
}
