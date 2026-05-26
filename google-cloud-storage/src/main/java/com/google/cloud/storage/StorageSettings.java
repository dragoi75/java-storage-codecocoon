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

import com.google.cloud.NoCredentials;
import com.google.cloud.ServiceDefaults;
import com.google.cloud.ServiceOptions;
import com.google.cloud.ServiceRpc;
import com.google.cloud.TransportOptions;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.spi.StorageRpcProvider;
import com.google.cloud.storage.spi.v1.HttpStorageClient;
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class StorageSettings extends ServiceOptions<StorageClient, StorageSettings> {

  private static final long serialVersionUID = -2907268477247502947L;
  private static final String API_DISPLAY_NAME = "Storage";
  private static final String GCS_OAUTH_SCOPE = "https://www.googleapis.com/auth/devstorage.full_control";
  private static final Set<String> AUTH_SCOPES = ImmutableSet.of(GCS_OAUTH_SCOPE);
  private static final String HOST_DEFAULT = "https://storage.googleapis.com";

  public static class StandardStorageFactory implements StorageProvider {

    private static final StorageProvider STORAGE_PROVIDER = new StandardStorageFactory();

    @Override
    public StorageClient create(StorageSettings settings) {
      return new StorageClientImpl(settings);
    }
  }

  public static class DefaultStorageRpcFactoryImpl implements StorageRpcProvider {

    private static final StorageRpcProvider STORAGE_PROVIDER = new DefaultStorageRpcFactoryImpl();

    @Override
    public ServiceRpc create(StorageSettings settings) {
      return new HttpStorageClient(settings);
    }
  }

  public static class ClientBuilder extends ServiceOptions.Builder<StorageClient, StorageSettings, ClientBuilder> {

      @Override
      public StorageSettings build() {
        return new StorageSettings(this);
      }

      private ClientBuilder() {}

      @Override
      public StorageSettings.ClientBuilder setTransportOptions(TransportOptions transportConfig) {
        if (!(transportConfig instanceof HttpTransportOptions)) {
          throw new IllegalArgumentException(
              "Only http transport is allowed for " + API_DISPLAY_NAME + ".");
        }
        return super.setTransportOptions(transportConfig);
      }

      private ClientBuilder(StorageSettings settings) {
        super(settings);
      }

  }

    private static class StorageDefaultsProvider implements ServiceDefaults<StorageClient, StorageSettings> {

        @Override
        public TransportOptions getDefaultTransportOptions() {
          return getDefaultHttpTransportOptions();
        }

        @Override
        public StorageProvider getDefaultServiceFactory() {
          return StandardStorageFactory.STORAGE_PROVIDER;
        }

        @Override
        public StorageRpcProvider getDefaultRpcFactory() {
          return DefaultStorageRpcFactoryImpl.STORAGE_PROVIDER;
        }

    }

    @Override
    public boolean equals(Object other) {
      return other instanceof StorageSettings && baseEquals((StorageSettings) other);
    }

    /** Returns a default {@code StorageOptions} instance. */
    public static StorageSettings getDefaultInstance() {
      return newClientBuilder().build();
    }

    public static ClientBuilder newClientBuilder() {
      return new ClientBuilder().setHost(HOST_DEFAULT);
    }

    protected CloudStorageRpc getStorageRpcV1() {
      return (CloudStorageRpc) getRpc();
    }

    @Override
    protected Set<String> getScopes() {
      return AUTH_SCOPES;
    }

    @Override
    public int hashCode() {
      return baseHashCode();
    }

    @SuppressWarnings("unchecked")
    @Override
    public StorageSettings.ClientBuilder toBuilder() {
      return new ClientBuilder(this).setHost(HOST_DEFAULT);
    }

    private StorageSettings(ClientBuilder clientBuilder) {
      super(StorageProvider.class, StorageRpcProvider.class, clientBuilder, new StorageDefaultsProvider());
    }

    /** Returns a unauthenticated {@code StorageOptions} instance. */
    public static StorageSettings getUnauthenticatedInstance() {
      return newClientBuilder().setCredentials(NoCredentials.getInstance()).build();
    }

    // Project ID is only required for creating buckets, so we don't require it for creating the
    // service.
    @Override
    protected boolean projectIdRequired() {
      return false;
    }

    public static HttpTransportOptions getDefaultHttpTransportOptions() {
      return HttpTransportOptions.newBuilder().build();
    }

}
