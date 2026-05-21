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

    static final Function<Bucket.Cors, CorsConfiguration> FROM_PROTO_TO_CONFIG_FN = new Function<Bucket.Cors, CorsConfiguration>() {

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

    private final Integer maxAgeSecs;

    private final ImmutableList<HttpRequestMethod> allowedMethods;

    private final ImmutableList<ResourceOrigin> allowedOrigins;

    private final ImmutableList<String> allowedResponseHeaders;

    /**
     * Class for a CORS origin.
     */
    public static final class ResourceOrigin implements Serializable {

        private static final long serialVersionUID = -4447958124895577993L;

        private static final String ANY_URI_PATTERN = "*";

        private final String originValue;

        private static final ResourceOrigin ANY_ORIGIN = new ResourceOrigin(ANY_URI_PATTERN);

        private ResourceOrigin(String originValue) {
            this.originValue = checkNotNull(originValue);
        }

        /**
         * Returns an {@code Origin} object for all possible origins.
         */
        public static ResourceOrigin anyOrigin() {
            return ANY_ORIGIN;
        }

        /**
         * Returns an {@code Origin} object for the given scheme, host and port.
         */
        public static ResourceOrigin from(String uriScheme, String hostName, int portNumber) {
            try {
                return from(new URI(uriScheme, null, hostName, portNumber, null, null, null).toString());
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException(ex);
            }
        }

        /**
         * Creates an {@code Origin} object for the provided value.
         */
        public static ResourceOrigin from(String originValue) {
            if (ANY_URI_PATTERN.equals(originValue)) {
                return anyOrigin();
            }
            return new ResourceOrigin(originValue);
        }

        @Override
        public int hashCode() {
            return originValue.hashCode();
        }

        @Override
        public boolean equals(Object otherObject) {
            if (!(otherObject instanceof ResourceOrigin)) {
                return false;
            }
            return originValue.equals(((ResourceOrigin) otherObject).originValue);
        }

        @Override
        public String toString() {
            return getValue();
        }

        public String getValue() {
            return originValue;
        }
    }

    /**
     * CORS configuration builder.
     */
    public static final class CorsPolicyBuilder {

        private Integer maxAgeSecs;

        private ImmutableList<HttpRequestMethod> allowedMethods;

        private ImmutableList<ResourceOrigin> allowedOrigins;

        private ImmutableList<String> allowedResponseHeaders;

        private CorsPolicyBuilder() {
        }

        /**
         * Sets the max time in seconds in which a client can issue requests before sending a new
         * preflight request.
         */
        public CorsPolicyBuilder setMaxAgeSeconds(Integer maxAgeSecs) {
            this.maxAgeSecs = maxAgeSecs;
            return this;
        }

        /**
         * Sets the HTTP methods supported by this CORS configuration.
         */
        public CorsPolicyBuilder setMethods(Iterable<HttpRequestMethod> allowedMethods) {
            this.allowedMethods = null != allowedMethods ? ImmutableList.copyOf(allowedMethods) : null;
            return this;
        }

        /**
         * Sets the origins for this CORS configuration.
         */
        public CorsPolicyBuilder setOrigins(Iterable<ResourceOrigin> allowedOrigins) {
            this.allowedOrigins = null != allowedOrigins ? ImmutableList.copyOf(allowedOrigins) : null;
            return this;
        }

        /**
         * Sets the response headers supported by this CORS configuration.
         */
        public CorsPolicyBuilder setResponseHeaders(Iterable<String> headerIterable) {
            this.allowedResponseHeaders = null != headerIterable ? ImmutableList.copyOf(headerIterable) : null;
            return this;
        }

        /**
         * Creates a CORS configuration.
         */
        public CorsConfiguration create() {
            return new CorsConfiguration(this);
        }
    }

    private CorsConfiguration(CorsPolicyBuilder policyBuilder) {
        this.maxAgeSecs = policyBuilder.maxAgeSecs;
        this.allowedMethods = policyBuilder.allowedMethods;
        this.allowedOrigins = policyBuilder.allowedOrigins;
        this.allowedResponseHeaders = policyBuilder.allowedResponseHeaders;
    }

    /**
     * Returns the max time in seconds in which a client can issue requests before sending a new
     * preflight request.
     */
    public Integer getMaxAgeSeconds() {
        return maxAgeSecs;
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
    public List<ResourceOrigin> getOrigins() {
        return allowedOrigins;
    }

    /**
     * Returns the response headers supported by this CORS configuration.
     */
    public List<String> getResponseHeaders() {
        return allowedResponseHeaders;
    }

    /**
     * Returns a builder for this CORS configuration.
     */
    public CorsPolicyBuilder toBuilder() {
        return createBuilder().setMaxAgeSeconds(maxAgeSecs).setMethods(allowedMethods).setOrigins(allowedOrigins).setResponseHeaders(allowedResponseHeaders);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAgeSecs, allowedMethods, allowedOrigins, allowedResponseHeaders);
    }

    @Override
    public boolean equals(Object otherObject) {
        if (!(otherObject instanceof CorsConfiguration)) {
            return false;
        }
        CorsConfiguration comparedConfig = (CorsConfiguration) otherObject;
        return Objects.equals(maxAgeSecs, comparedConfig.maxAgeSecs) && Objects.equals(allowedMethods, comparedConfig.allowedMethods) && Objects.equals(allowedOrigins, comparedConfig.allowedOrigins) && Objects.equals(allowedResponseHeaders, comparedConfig.allowedResponseHeaders);
    }

    /**
     * Returns a CORS configuration builder.
     */
    public static CorsPolicyBuilder createBuilder() {
        return new CorsPolicyBuilder();
    }

    Bucket.Cors toProto() {
        Bucket.Cors protoCors = new Bucket.Cors();
        protoCors.setMaxAgeSeconds(maxAgeSecs);
        protoCors.setResponseHeader(allowedResponseHeaders);
        if (null != allowedMethods) {
            protoCors.setMethod(newArrayList(transform(allowedMethods, Functions.toStringFunction())));
        }
        if (null != allowedOrigins) {
            protoCors.setOrigin(newArrayList(transform(allowedOrigins, Functions.toStringFunction())));
        }
        return protoCors;
    }

    static CorsConfiguration fromProto(Bucket.Cors configuration) {
        CorsPolicyBuilder policyBuilder = createBuilder().setMaxAgeSeconds(configuration.getMaxAgeSeconds());
        if (null != configuration.getMethod()) {
            policyBuilder.setMethods(transform(configuration.getMethod(), new Function<String, HttpRequestMethod>() {

                @Override
                public HttpRequestMethod apply(String name) {
                    return HttpRequestMethod.fromValue(name.toUpperCase());
                }
            }));
        }
        if (null != configuration.getOrigin()) {
            policyBuilder.setOrigins(transform(configuration.getOrigin(), new Function<String, ResourceOrigin>() {

                @Override
                public CorsConfiguration.ResourceOrigin apply(String value) {
                    return ResourceOrigin.from(value);
                }
            }));
        }
        policyBuilder.setResponseHeaders(configuration.getResponseHeader());
        return policyBuilder.create();
    }
}
