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

import static java.util.Objects.requireNonNull;

import com.google.cloud.NoCredentials;
import com.google.cloud.ServiceDefaults;
import com.google.cloud.ServiceOptions;
import com.google.cloud.ServiceRpc;
import com.google.cloud.TransportOptions;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.spi.StorageRpcFactory;
import com.google.cloud.storage.spi.v1.HttpStorageRpc;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class StorageClientOptions extends ServiceOptions<Storage, StorageClientOptions> {

  private static final long serialVersionUID = -2907268477247502947L;
  private static final String API_SIMPLE_NAME = "Storage";
  private static final String GCS_AUTH_SCOPE = "https://www.googleapis.com/auth/devstorage.full_control";
  private static final Set<String> AUTH_SCOPE_SET = ImmutableSet.of(GCS_AUTH_SCOPE);
  private static final String DEFAULT_ENDPOINT = "https://storage.googleapis.com";
  private static final boolean INCLUDE_INVOCATION_BY_DEFAULT = true;
  private final RetryAlgorithmManager retryAlgoManager;
  private final boolean includeInvocationIdentifier;

  public static class StandardStorageFactory implements StorageFactory {

    private static final StorageFactory STANDARD_STORAGE_FACTORY_SINGLETON = new StandardStorageFactory();

    @Override
    public Storage create(StorageClientOptions clientConfig) {
      return new StorageImpl(clientConfig);
    }
  }

  public static class DefaultStorageRpcProvider implements StorageRpcFactory {

    private static final StorageRpcFactory STANDARD_STORAGE_FACTORY_SINGLETON = new DefaultStorageRpcProvider();

    @Override
    public ServiceRpc create(StorageClientOptions clientConfig) {
      return new HttpStorageRpc(clientConfig);
    }
  }

  public static class StorageClientBuilder extends ServiceOptions.Builder<Storage, StorageClientOptions, StorageClientBuilder> {

    private StorageRetryStrategy retryStrategy;
    private boolean includeInvocationIdentifier;

      /**
       * Override the default retry handling behavior with an alternate strategy.
       *
       * @param retryStrategy a non-null storageRetryStrategy to use
       * @return the builder
       * @see StorageRetryStrategy#getDefaultStorageRetryStrategy()
       */
      public StorageClientBuilder setStorageRetryStrategy(StorageRetryStrategy retryStrategy) {
        this.retryStrategy =
            requireNonNull(retryStrategy, "storageRetryStrategy must be non null");
        return this;
      }

      /**
       * Override default enablement of invocation id added to x-goog-api-client header.
       *
       * @param includeInvocationIdentifier a boolean to change enablement of invocation id
       * @return the builder
       */
      StorageClientBuilder setIncludeInvocationId(boolean includeInvocationIdentifier) {
        this.includeInvocationIdentifier = includeInvocationIdentifier;
        return this;
      }

      @Override
      public StorageClientOptions build() {
        return new StorageClientOptions(this, new StorageDefaultsProvider());
      }

      @Override
      public StorageClientOptions.StorageClientBuilder setTransportOptions(TransportOptions transportConfig) {
        if (!(transportConfig instanceof HttpTransportOptions)) {
          throw new IllegalArgumentException(
              "Only http transport is allowed for " + API_SIMPLE_NAME + ".");
        }
        return super.setTransportOptions(transportConfig);
      }

      private StorageClientBuilder() {}

      private StorageClientBuilder(StorageClientOptions clientConfig) {
        super(clientConfig);
        this.includeInvocationIdentifier = clientConfig.includeInvocationIdentifier;
      }

  }

    private static class StorageDefaultsProvider implements ServiceDefaults<Storage, StorageClientOptions> {

        @Override
        public TransportOptions getDefaultTransportOptions() {
          return getDefaultHttpTransportOptions();
        }

        boolean isIncludeInvocationId() {
          return INCLUDE_INVOCATION_BY_DEFAULT;
        }

        @Override
        public StorageFactory getDefaultServiceFactory() {
          return StandardStorageFactory.STANDARD_STORAGE_FACTORY_SINGLETON;
        }

        @Override
        public StorageRpcFactory getDefaultRpcFactory() {
          return DefaultStorageRpcProvider.STANDARD_STORAGE_FACTORY_SINGLETON;
        }

        public StorageRetryStrategy getStorageRetryStrategy() {
          return StorageRetryStrategy.getDefaultStorageRetryStrategy();
        }

    }

    /** Returns a unauthenticated {@code StorageOptions} instance. */
    public static StorageClientOptions getUnauthenticatedInstance() {
      return newStorageClientBuilder().setCredentials(NoCredentials.getInstance()).build();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof StorageClientOptions && baseEquals((StorageClientOptions) other);
    }

    public static StorageClientBuilder newStorageClientBuilder() {
      return new StorageClientBuilder()
          .setHost(DEFAULT_ENDPOINT)
          .setIncludeInvocationId(INCLUDE_INVOCATION_BY_DEFAULT);
    }

    public static HttpTransportOptions getDefaultHttpTransportOptions() {
      return HttpTransportOptions.newBuilder().build();
    }

    @Override
    protected Set<String> getScopes() {
      return AUTH_SCOPE_SET;
    }

    @SuppressWarnings("unchecked")
    @Override
    public StorageClientOptions.StorageClientBuilder toBuilder() {
      return new StorageClientBuilder(this);
    }

    /** Returns if Invocation ID is enabled and transmitted through x-goog-api-client header. */
    boolean isIncludeInvocationId() {
      return includeInvocationIdentifier;
    }

    private StorageClientOptions(StorageClientBuilder clientFactory, StorageDefaultsProvider defaultsProvider) {
      super(StorageFactory.class, StorageRpcFactory.class, clientFactory, defaultsProvider);
      this.retryAlgoManager =
          new RetryAlgorithmManager(
              MoreObjects.firstNonNull(
                  clientFactory.retryStrategy, defaultsProvider.getStorageRetryStrategy()));
      this.includeInvocationIdentifier = clientFactory.includeInvocationIdentifier;
    }

    protected StorageRpcClient getStorageRpcV1() {
      return (StorageRpcClient) getRpc();
    }

    RetryAlgorithmManager getRetryAlgorithmManager() {
      return retryAlgoManager;
    }

    @Override
    public int hashCode() {
      return baseHashCode();
    }

    // Project ID is only required for creating buckets, so we don't require it for creating the
    // service.
    @Override
    protected boolean projectIdRequired() {
      return false;
    }

    /** Returns a default {@code StorageOptions} instance. */
    public static StorageClientOptions getDefaultInstance() {
      return newStorageClientBuilder().build();
    }

}
