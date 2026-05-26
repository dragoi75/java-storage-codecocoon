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

    private final ImmutableList<HttpRequestMethod> allowedHttpVerbs;

    private final ImmutableList<Source> allowedSources;

    private final ImmutableList<String> responseMeta;

    /**
     * Class for a CORS origin.
     */
    public static final class Source implements Serializable {

        private static final long serialVersionUID = -4447958124895577993L;

        private static final String WILDCARD_URI = "*";

        private final String text;

        private static final Source WILDCARD_SOURCE = new Source(WILDCARD_URI);

        @Override
        public String toString() {
            return getValue();
        }

        /**
         * Returns an {@code Origin} object for the given scheme, host and port.
         */
        public static Source from(String protocol, String domain, int socketNumber) {
            try {
                return from(new URI(protocol, null, domain, socketNumber, null, null, null).toString());
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException(ex);
            }
        }

        public String getValue() {
            return text;
        }

        private Source(String text) {
            this.text = checkNotNull(text);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Source)) {
                return false;
            }
            return text.equals(((Source) other).text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }

        /**
         * Creates an {@code Origin} object for the provided value.
         */
        public static Source from(String text) {
            if (WILDCARD_URI.equals(text)) {
                return anySource();
            }
            return new Source(text);
        }

        /**
         * Returns an {@code Origin} object for all possible origins.
         */
        public static Source anySource() {
            return WILDCARD_SOURCE;
        }

    }

    /**
     * CORS configuration builder.
     */
    public static final class CorsConfigurationBuilder {

        private Integer maxAge;

        private ImmutableList<HttpRequestMethod> allowedHttpVerbs;

        private ImmutableList<Source> allowedSources;

        private ImmutableList<String> responseMeta;

        /**
         * Creates a CORS configuration.
         */
        public CorsConfiguration create() {
            return new CorsConfiguration(this);
        }

        /**
         * Sets the response headers supported by this CORS configuration.
         */
        public CorsConfigurationBuilder setResponseHeaders(Iterable<String> headerNamesIter) {
            this.responseMeta = null != headerNamesIter ? ImmutableList.copyOf(headerNamesIter) : null;
            return this;
        }

        /**
         * Sets the origins for this CORS configuration.
         */
        public CorsConfigurationBuilder setOrigins(Iterable<Source> allowedSources) {
            this.allowedSources = null != allowedSources ? ImmutableList.copyOf(allowedSources) : null;
            return this;
        }

        /**
         * Sets the HTTP methods supported by this CORS configuration.
         */
        public CorsConfigurationBuilder setMethods(Iterable<HttpRequestMethod> allowedHttpVerbs) {
            this.allowedHttpVerbs = null != allowedHttpVerbs ? ImmutableList.copyOf(allowedHttpVerbs) : null;
            return this;
        }

        /**
         * Sets the max time in seconds in which a client can issue requests before sending a new
         * preflight request.
         */
        public CorsConfigurationBuilder setMaxAgeSeconds(Integer maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        private CorsConfigurationBuilder() {
        }

    }

    static CorsConfiguration fromProto(Bucket.Cors configuration) {
        CorsConfigurationBuilder configProducer = createBuilder().setMaxAgeSeconds(configuration.getMaxAgeSeconds());
        if (null != configuration.getMethod()) {
            configProducer.setMethods(transform(configuration.getMethod(), new Function<String, HttpRequestMethod>() {

                @Override
                public HttpRequestMethod apply(String name) {
                    return HttpRequestMethod.fromValue(name.toUpperCase());
                }
            }));
        }
        if (null != configuration.getOrigin()) {
            configProducer.setOrigins(transform(configuration.getOrigin(), new Function<String, Source>() {

                @Override
                public CorsConfiguration.Source apply(String value) {
                    return Source.from(value);
                }
            }));
        }
        configProducer.setResponseHeaders(configuration.getResponseHeader());
        return configProducer.create();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CorsConfiguration)) {
            return false;
        }
        CorsConfiguration comparisonTarget = (CorsConfiguration) other;
        return Objects.equals(maxAge, comparisonTarget.maxAge) && Objects.equals(allowedHttpVerbs, comparisonTarget.allowedHttpVerbs) && Objects.equals(allowedSources, comparisonTarget.allowedSources) && Objects.equals(responseMeta, comparisonTarget.responseMeta);
    }

    private CorsConfiguration(CorsConfigurationBuilder configProducer) {
        this.maxAge = configProducer.maxAge;
        this.allowedHttpVerbs = configProducer.allowedHttpVerbs;
        this.allowedSources = configProducer.allowedSources;
        this.responseMeta = configProducer.responseMeta;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAge, allowedHttpVerbs, allowedSources, responseMeta);
    }

    Bucket.Cors toProto() {
        Bucket.Cors proto = new Bucket.Cors();
        proto.setMaxAgeSeconds(maxAge);
        proto.setResponseHeader(responseMeta);
        if (null != allowedHttpVerbs) {
            proto.setMethod(newArrayList(transform(allowedHttpVerbs, Functions.toStringFunction())));
        }
        if (null != allowedSources) {
            proto.setOrigin(newArrayList(transform(allowedSources, Functions.toStringFunction())));
        }
        return proto;
    }

    /**
     * Returns the response headers supported by this CORS configuration.
     */
    public List<String> getResponseHeaders() {
        return responseMeta;
    }

    /**
     * Returns the HTTP methods supported by this CORS configuration.
     */
    public List<HttpRequestMethod> getMethods() {
        return allowedHttpVerbs;
    }

    /**
     * Returns a CORS configuration builder.
     */
    public static CorsConfigurationBuilder createBuilder() {
        return new CorsConfigurationBuilder();
    }

    /**
     * Returns the origins in this CORS configuration.
     */
    public List<Source> getOrigins() {
        return allowedSources;
    }

    /**
     * Returns a builder for this CORS configuration.
     */
    public CorsConfigurationBuilder toBuilder() {
        return createBuilder().setMaxAgeSeconds(maxAge).setMethods(allowedHttpVerbs).setOrigins(allowedSources).setResponseHeaders(responseMeta);
    }

    /**
     * Returns the max time in seconds in which a client can issue requests before sending a new
     * preflight request.
     */
    public Integer getMaxAgeSeconds() {
        return maxAge;
    }

}
