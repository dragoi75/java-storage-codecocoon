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
public final class CorsConfig implements Serializable {

  private static final long serialVersionUID = -8637770919343335655L;

  static final Function<Bucket.Cors, CorsConfig> FROM_PROTO_FUNCTION =
      new Function<Bucket.Cors, CorsConfig>() {
        @Override
        public CorsConfig apply(Bucket.Cors pb) {
          return CorsConfig.fromProto(pb);
        }
      };

  static final Function<CorsConfig, Bucket.Cors> TO_PROTO_FUNCTION =
      new Function<CorsConfig, Bucket.Cors>() {
        @Override
        public Bucket.Cors apply(CorsConfig cors) {
          return cors.toProto();
        }
      };

  private final Integer maxAge;
  private final ImmutableList<HttpRequestMethod> httpVerbs;
  private final ImmutableList<OriginValue> originValues;
  private final ImmutableList<String> headerNames;

  /** Class for a CORS origin. */
  public static final class OriginValue implements Serializable {

    private static final long serialVersionUID = -4447958124895577993L;
    private static final String WILDCARD_URI = "*";
    private final String originText;

    private static final OriginValue WILDCARD = new OriginValue(WILDCARD_URI);

    private OriginValue(String originText) {
      this.originText = checkNotNull(originText);
    }

    /** Returns an {@code Origin} object for all possible origins. */
    public static OriginValue anyOrigin() {
      return WILDCARD;
    }

    /** Returns an {@code Origin} object for the given scheme, host and port. */
    public static OriginValue from(String protocol, String serverName, int portNumber) {
      try {
        return from(new URI(protocol, null, serverName, portNumber, null, null, null).toString());
      } catch (URISyntaxException ex) {
        throw new IllegalArgumentException(ex);
      }
    }

    /** Creates an {@code Origin} object for the provided value. */
    public static OriginValue from(String originText) {
      if (WILDCARD_URI.equals(originText)) {
        return anyOrigin();
      }
      return new OriginValue(originText);
    }

    @Override
    public int hashCode() {
      return originText.hashCode();
    }

    @Override
    public boolean equals(Object candidate) {
      if (!(candidate instanceof OriginValue)) {
        return false;
      }
      return originText.equals(((OriginValue) candidate).originText);
    }

    @Override
    public String toString() {
      return getValue();
    }

    public String getValue() {
      return originText;
    }
  }

  /** CORS configuration builder. */
  public static final class CorsPolicyBuilder {

    private Integer maxAge;
    private ImmutableList<HttpRequestMethod> httpVerbs;
    private ImmutableList<OriginValue> originValues;
    private ImmutableList<String> headerNames;

    private CorsPolicyBuilder() {}

    /**
     * Sets the max time in seconds in which a client can issue requests before sending a new
     * preflight request.
     */
    public CorsPolicyBuilder setMaxAgeSeconds(Integer maxAge) {
      this.maxAge = maxAge;
      return this;
    }

    /** Sets the HTTP methods supported by this CORS configuration. */
    public CorsPolicyBuilder setMethods(Iterable<HttpRequestMethod> httpVerbs) {
      this.httpVerbs = httpVerbs != null ? ImmutableList.copyOf(httpVerbs) : null;
      return this;
    }

    /** Sets the origins for this CORS configuration. */
    public CorsPolicyBuilder setOrigins(Iterable<OriginValue> originValues) {
      this.originValues = originValues != null ? ImmutableList.copyOf(originValues) : null;
      return this;
    }

    /** Sets the response headers supported by this CORS configuration. */
    public CorsPolicyBuilder setResponseHeaders(Iterable<String> headerIterable) {
      this.headerNames = headerIterable != null ? ImmutableList.copyOf(headerIterable) : null;
      return this;
    }

    /** Creates a CORS configuration. */
    public CorsConfig buildConfig() {
      return new CorsConfig(this);
    }
  }

  private CorsConfig(CorsPolicyBuilder policyBuilder) {
    this.maxAge = policyBuilder.maxAge;
    this.httpVerbs = policyBuilder.httpVerbs;
    this.originValues = policyBuilder.originValues;
    this.headerNames = policyBuilder.headerNames;
  }

  /**
   * Returns the max time in seconds in which a client can issue requests before sending a new
   * preflight request.
   */
  public Integer getMaxAgeSeconds() {
    return maxAge;
  }

  /** Returns the HTTP methods supported by this CORS configuration. */
  public List<HttpRequestMethod> getMethods() {
    return httpVerbs;
  }

  /** Returns the origins in this CORS configuration. */
  public List<OriginValue> getOrigins() {
    return originValues;
  }

  /** Returns the response headers supported by this CORS configuration. */
  public List<String> getResponseHeaders() {
    return headerNames;
  }

  /** Returns a builder for this CORS configuration. */
  public CorsPolicyBuilder toBuilder() {
    return builder()
        .setMaxAgeSeconds(maxAge)
        .setMethods(httpVerbs)
        .setOrigins(originValues)
        .setResponseHeaders(headerNames);
  }

  @Override
  public int hashCode() {
    return Objects.hash(maxAge, httpVerbs, originValues, headerNames);
  }

  @Override
  public boolean equals(Object candidate) {
    if (!(candidate instanceof CorsConfig)) {
      return false;
    }
    CorsConfig otherConfig = (CorsConfig) candidate;
    return Objects.equals(maxAge, otherConfig.maxAge)
        && Objects.equals(httpVerbs, otherConfig.httpVerbs)
        && Objects.equals(originValues, otherConfig.originValues)
        && Objects.equals(headerNames, otherConfig.headerNames);
  }

  /** Returns a CORS configuration builder. */
  public static CorsPolicyBuilder builder() {
    return new CorsPolicyBuilder();
  }

  Bucket.Cors toProto() {
    Bucket.Cors corsProto = new Bucket.Cors();
    corsProto.setMaxAgeSeconds(maxAge);
    corsProto.setResponseHeader(headerNames);
    if (httpVerbs != null) {
      corsProto.setMethod(newArrayList(transform(httpVerbs, Functions.toStringFunction())));
    }
    if (originValues != null) {
      corsProto.setOrigin(newArrayList(transform(originValues, Functions.toStringFunction())));
    }
    return corsProto;
  }

  static CorsConfig fromProto(Bucket.Cors policyConfig) {
    CorsPolicyBuilder policyBuilder = builder().setMaxAgeSeconds(policyConfig.getMaxAgeSeconds());
    if (policyConfig.getMethod() != null) {
      policyBuilder.setMethods(
          transform(
              policyConfig.getMethod(),
              new Function<String, HttpRequestMethod>() {
                @Override
                public HttpRequestMethod apply(String name) {
                  return HttpRequestMethod.fromValue(name.toUpperCase());
                }
              }));
    }
    if (policyConfig.getOrigin() != null) {
      policyBuilder.setOrigins(
          transform(
              policyConfig.getOrigin(),
              new Function<String, OriginValue>() {
                @Override
                public CorsConfig.OriginValue apply(String value) {
                  return OriginValue.from(value);
                }
              }));
    }
    policyBuilder.setResponseHeaders(policyConfig.getResponseHeader());
    return policyBuilder.buildConfig();
  }
}
