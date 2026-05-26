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
public final class Cors implements Serializable {

    private static final long serialVersionUID = -8637770919343335655L;

    static final Function<Bucket.Cors, Cors> FROM_PB_FUNCTION = new Function<Bucket.Cors, Cors>() {

        @Override
        public Cors apply(Bucket.Cors pb) {
            return Cors.fromPb(pb);
        }
    };

    static final Function<Cors, Bucket.Cors> TO_PB_FUNCTION = new Function<Cors, Bucket.Cors>() {

        @Override
        public Bucket.Cors apply(Cors cors) {
            return cors.toPb();
        }
    };

    private final Integer maxAgeSeconds;

    private final ImmutableList<HttpRequestMethod> methods;

    private final ImmutableList<Origin> origins;

    private final ImmutableList<String> responseHeaders;

    /**
     * Class for a CORS origin.
     */
    public static final class Origin implements Serializable {

        private static final long serialVersionUID = -4447958124895577993L;

        private static final String ANY_URI = "*";

        private final String value;

        private static final Origin ANY = new Origin(ANY_URI);

        /**
         * Creates an {@code Origin} object for the provided value.
         */
        public static Origin of(String value) {
            if (ANY_URI.equals(value)) {
                return any();
            }
            return new Origin(value);
        }

        @Override
        public String toString() {
            return getValue();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Origin)) {
                return false;
            }
            return value.equals(((Origin) obj).value);
        }

        public String getValue() {
            return value;
        }

        /**
         * Returns an {@code Origin} object for the given scheme, host and port.
         */
        public static Origin of(String scheme, String host, int port) {
            try {
                return of(new URI(scheme, null, host, port, null, null, null).toString());
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }

        /**
         * Returns an {@code Origin} object for all possible origins.
         */
        public static Origin any() {
            return ANY;
        }

        private Origin(String value) {
            this.value = checkNotNull(value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

    }

    /**
     * CORS configuration builder.
     */
    public static final class Builder {

        private Integer maxAgeSeconds;

        private ImmutableList<HttpRequestMethod> methods;

        private ImmutableList<Origin> origins;

        private ImmutableList<String> responseHeaders;

        /**
         * Sets the HTTP methods supported by this CORS configuration.
         */
        public Builder setMethods(Iterable<HttpRequestMethod> methods) {
            this.methods = null != methods ? ImmutableList.copyOf(methods) : null;
            return this;
        }

        /**
         * Sets the response headers supported by this CORS configuration.
         */
        public Builder setResponseHeaders(Iterable<String> headers) {
            this.responseHeaders = null != headers ? ImmutableList.copyOf(headers) : null;
            return this;
        }

        /**
         * Sets the origins for this CORS configuration.
         */
        public Builder setOrigins(Iterable<Origin> origins) {
            this.origins = null != origins ? ImmutableList.copyOf(origins) : null;
            return this;
        }

        /**
         * Creates a CORS configuration.
         */
        public Cors build() {
            return new Cors(this);
        }

        private Builder() {
        }

        /**
         * Sets the max time in seconds in which a client can issue requests before sending a new
         * preflight request.
         */
        public Builder setMaxAgeSeconds(Integer maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
            return this;
        }

    }

    /**
     * Returns a builder for this CORS configuration.
     */
    public Builder toBuilder() {
        return newBuilder().setMaxAgeSeconds(maxAgeSeconds).setMethods(methods).setOrigins(origins).setResponseHeaders(responseHeaders);
    }

    static Cors fromPb(Bucket.Cors cors) {
        Builder builder = newBuilder().setMaxAgeSeconds(cors.getMaxAgeSeconds());
        if (null != cors.getMethod()) {
            builder.setMethods(transform(cors.getMethod(), new Function<String, HttpRequestMethod>() {

                @Override
                public HttpRequestMethod apply(String name) {
                    return HttpRequestMethod.fromValue(name.toUpperCase());
                }
            }));
        }
        if (null != cors.getOrigin()) {
            builder.setOrigins(transform(cors.getOrigin(), new Function<String, Origin>() {

                @Override
                public Origin apply(String value) {
                    return Origin.of(value);
                }
            }));
        }
        builder.setResponseHeaders(cors.getResponseHeader());
        return builder.build();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Cors)) {
            return false;
        }
        Cors other = (Cors) obj;
        return Objects.equals(maxAgeSeconds, other.maxAgeSeconds) && Objects.equals(methods, other.methods) && Objects.equals(origins, other.origins) && Objects.equals(responseHeaders, other.responseHeaders);
    }

    private Cors(Builder builder) {
        this.maxAgeSeconds = builder.maxAgeSeconds;
        this.methods = builder.methods;
        this.origins = builder.origins;
        this.responseHeaders = builder.responseHeaders;
    }

    /**
     * Returns a CORS configuration builder.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Returns the origins in this CORS configuration.
     */
    public List<Origin> getOrigins() {
        return origins;
    }

    Bucket.Cors toPb() {
        Bucket.Cors pb = new Bucket.Cors();
        pb.setMaxAgeSeconds(maxAgeSeconds);
        pb.setResponseHeader(responseHeaders);
        if (null != methods) {
            pb.setMethod(newArrayList(transform(methods, Functions.toStringFunction())));
        }
        if (null != origins) {
            pb.setOrigin(newArrayList(transform(origins, Functions.toStringFunction())));
        }
        return pb;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxAgeSeconds, methods, origins, responseHeaders);
    }

    /**
     * Returns the max time in seconds in which a client can issue requests before sending a new
     * preflight request.
     */
    public Integer getMaxAgeSeconds() {
        return maxAgeSeconds;
    }

    /**
     * Returns the response headers supported by this CORS configuration.
     */
    public List<String> getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * Returns the HTTP methods supported by this CORS configuration.
     */
    public List<HttpRequestMethod> getMethods() {
        return methods;
    }

}
