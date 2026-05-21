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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Lists.newArrayList;
import com.google.api.services.storage.model.Bucket;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/**
 * Cross-Origin Resource Sharing (CORS) configuration for a bucket.
 *
 * @see <a href="https://cloud.google.com/storage/docs/cross-origin">Cross-Origin Resource Sharing
 *     (CORS)</a>
 */
public final class CorsConfiguration implements Serializable {

    private static final long serialVersionUID = -8637770919343335655L;

    static final Function<Bucket.Cors, CorsConfiguration> FROM_PROTO_FUNCTION = new Function<Bucket.Cors, CorsConfiguration>() {

        @Override
        public CorsConfiguration apply(Bucket.Cors pb) {
            return CorsConfiguration.fromProto(pb);
        }
    };

    static final Function<CorsConfiguration, Bucket.Cors> TO_PROTO_FUNCTION = new Function<CorsConfiguration, Bucket.Cors>() {

        @Override
        public Bucket.Cors apply(CorsConfiguration cors) {
            return cors.toProto();
        }
    };

    private final Integer maxAge;

    private final ImmutableList<HttpRequestMethod> allowedMethods;

    private final ImmutableList<OriginValue> allowedOrigins;

    private final ImmutableList<String> exposedHeaders;

    /**
     * Class for a CORS origin.
     */
    public static final class OriginValue implements Serializable {

        private static final long serialVersionUID = -4447958124895577993L;

        private static final String WILDCARD_URI = "*";

        private final String originUri;

        private static final OriginValue WILDCARD_ORIGIN = new OriginValue(WILDCARD_URI);

        private OriginValue(String originUri) {
            this.originUri = checkNotNull(originUri);
        }

        /**
         * Returns an {@code Origin} object for all possible origins.
         */
        public static OriginValue anyOrigin() {
            return WILDCARD_ORIGIN;
        }

        /**
         * Returns an {@code Origin} object for the given scheme, host and port.
         */
        public static OriginValue from(String uriScheme, String hostname, int portNumber) {
            try {
                return from(new URI(uriScheme, null, hostname, portNumber, null, null, null).toString());
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException(ex);
            }
        }

        /**
         * Creates an {@code Origin} object for the provided value.
         */
        public static OriginValue from(String originUri) {
            if (WILDCARD_URI.equals(originUri)) {
                return anyOrigin();
            }
            return new OriginValue(originUri);
        }

        @Override
        public int hashCode() {
            return originUri.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof OriginValue)) {
                return false;
            }
            return originUri.equals(((OriginValue) other).originUri);
        }

        @Override
        public String toString() {
            return getValue();
        }

        public String getValue() {
            return originUri;
        }
    }

    /**
     * CORS configuration builder.
     */
    public static final class CorsConfigurationBuilder {

        private Integer maxAge;

        private ImmutableList<HttpRequestMethod> allowedMethods;

        private ImmutableList<OriginValue> allowedOrigins;

        private ImmutableList<String> exposedHeaders;

        private CorsConfigurationBuilder() {
        }

        /**
         * Sets the max time in seconds in which a client can issue requests before sending a new
         * preflight request.
         */
        public CorsConfigurationBuilder setMaxAgeSeconds(Integer maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        /**
         * Sets the HTTP methods supported by this CORS configuration.
         */
        public CorsConfigurationBuilder setMethods(Iterable<HttpRequestMethod> allowedMethods) {
            this.allowedMethods = null != allowedMethods ? ImmutableList.copyOf(allowedMethods) : null;
            return this;
        }

        /**
         * Sets the origins for this CORS configuration.
         */
        public CorsConfigurationBuilder setOrigins(Iterable<OriginValue> allowedOrigins) {
            this.allowedOrigins = null != allowedOrigins ? ImmutableList.copyOf(allowedOrigins) : null;
            return this;
        }

        /**
         * Sets the response headers supported by this CORS configuration.
         */
        public CorsConfigurationBuilder setResponseHeaders(Iterable<String> headerNames) {
            this.exposedHeaders = null != headerNames ? ImmutableList.copyOf(headerNames) : null;
            return this;
        }

        /**
         * Creates a CORS configuration.
         */
        public CorsConfiguration buildCorsConfiguration() {
            return new CorsConfiguration(this);
        }
    }

    private CorsConfiguration(CorsConfigurationBuilder configBuilder) {
        this.maxAge = configBuilder.maxAge;
        this.allowedMethods = configBuilder.allowedMethods;
        this.allowedOrigins = configBuilder.allowedOrigins;
        this.exposedHeaders = configBuilder.exposedHeaders;
    }

    /**
     * Returns the max time in seconds in which a client can issue requests before sending a new
     * preflight request.
     */
    public Integer getMaxAgeSeconds() {
        return maxAge;
    }

    /**
     * Returns the HTTP methods supported by this CORS configuration.
     */
    public List<HttpRequestMethod> getMethods() {
        return allowedMethods;
    }

    /**
     * Returns the origins in this CORS configuration.
     */
    public List<OriginValue> getOrigins() {
        return allowedOrigins;
    }

    /**
     * Returns the response headers supported by this CORS configuration.
     */
    public List<String> getResponseHeaders() {
        return exposedHeaders;
    }

    /**
     * Returns a builder for this CORS configuration.
     */
    public CorsConfigurationBuilder toBuilder() {
        return newCorsConfigurationBuilder().setMaxAgeSeconds(maxAge).setMethods(allowedMethods).setOrigins(allowedOrigins).setResponseHeaders(exposedHeaders);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAge, allowedMethods, allowedOrigins, exposedHeaders);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CorsConfiguration)) {
            return false;
        }
        CorsConfiguration comparedConfig = (CorsConfiguration) other;
        return Objects.equals(maxAge, comparedConfig.maxAge) && Objects.equals(allowedMethods, comparedConfig.allowedMethods) && Objects.equals(allowedOrigins, comparedConfig.allowedOrigins) && Objects.equals(exposedHeaders, comparedConfig.exposedHeaders);
    }

    /**
     * Returns a CORS configuration builder.
     */
    public static CorsConfigurationBuilder newCorsConfigurationBuilder() {
        return new CorsConfigurationBuilder();
    }

    Bucket.Cors toProto() {
        Bucket.Cors corsProto = new Bucket.Cors();
        corsProto.setMaxAgeSeconds(maxAge);
        corsProto.setResponseHeader(exposedHeaders);
        if (null != allowedMethods) {
            corsProto.setMethod(newArrayList(transform(allowedMethods, Functions.toStringFunction())));
        }
        if (null != allowedOrigins) {
            corsProto.setOrigin(newArrayList(transform(allowedOrigins, Functions.toStringFunction())));
        }
        return corsProto;
    }

    static CorsConfiguration fromProto(Bucket.Cors configuration) {
        CorsConfigurationBuilder configBuilder = newCorsConfigurationBuilder().setMaxAgeSeconds(configuration.getMaxAgeSeconds());
        if (null != configuration.getMethod()) {
            configBuilder.setMethods(transform(configuration.getMethod(), new Function<String, HttpRequestMethod>() {

                @Override
                public HttpRequestMethod apply(String name) {
                    return HttpRequestMethod.fromValue(name.toUpperCase());
                }
            }));
        }
        if (null != configuration.getOrigin()) {
            configBuilder.setOrigins(transform(configuration.getOrigin(), new Function<String, OriginValue>() {

                @Override
                public CorsConfiguration.OriginValue apply(String value) {
                    return OriginValue.from(value);
                }
            }));
        }
        configBuilder.setResponseHeaders(configuration.getResponseHeader());
        return configBuilder.buildCorsConfiguration();
    }
}
