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
import com.google.cloud.storage.spi.v1.HttpStorageRpcClient;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class StorageSettings extends ServiceOptions<StorageService, StorageSettings> {

  private static final long serialVersionUID = -2907268477247502947L;
  private static final String API_ALIAS = "Storage";
  private static final String GCS_OAUTH_SCOPE = "https://www.googleapis.com/auth/devstorage.full_control";
  private static final Set<String> AUTHORIZED_SCOPES = ImmutableSet.of(GCS_OAUTH_SCOPE);
  private static final String DEFAULT_ENDPOINT = "https://storage.googleapis.com";

  public static class DefaultStorageFactoryImpl implements StorageProvider {

    private static final StorageProvider DEFAULT_PROVIDER = new DefaultStorageFactoryImpl();

    @Override
    public StorageService create(StorageSettings settings) {
      return new StorageServiceImpl(settings);
    }
  }

  public static class DefaultStorageRpcFactoryImpl implements StorageRpcProvider {

    private static final StorageRpcProvider DEFAULT_PROVIDER = new DefaultStorageRpcFactoryImpl();

    @Override
    public ServiceRpc create(StorageSettings settings) {
      return new HttpStorageRpcClient(settings);
    }
  }

  public static class ClientBuilder extends ServiceOptions.Builder<StorageService, StorageSettings, ClientBuilder> {

      @Override
      public StorageSettings.ClientBuilder setTransportOptions(TransportOptions transportConfig) {
        if (!(transportConfig instanceof HttpTransportOptions)) {
          throw new IllegalArgumentException(
              "Only http transport is allowed for " + API_ALIAS + ".");
        }
        return super.setTransportOptions(transportConfig);
      }

      @Override
      public StorageSettings build() {
        return new StorageSettings(this);
      }

      private ClientBuilder() {}

      private ClientBuilder(StorageSettings settings) {
        super(settings);
      }

  }

    private static class StorageDefaultsProvider implements ServiceDefaults<StorageService, StorageSettings> {

        @Override
        public TransportOptions getDefaultTransportOptions() {
          return getDefaultHttpTransportOptions();
        }

        @Override
        public StorageProvider getDefaultServiceFactory() {
          return DefaultStorageFactoryImpl.DEFAULT_PROVIDER;
        }

        @Override
        public StorageRpcProvider getDefaultRpcFactory() {
          return DefaultStorageRpcFactoryImpl.DEFAULT_PROVIDER;
        }

    }

    @SuppressWarnings("unchecked")
    @Override
    public StorageSettings.ClientBuilder toBuilder() {
      return new ClientBuilder(this).setHost(DEFAULT_ENDPOINT);
    }

    @Override
    protected Set<String> getScopes() {
      return AUTHORIZED_SCOPES;
    }

    protected StorageRpcClient getStorageRpcV1() {
      return (StorageRpcClient) getRpc();
    }

    /** Returns a default {@code StorageOptions} instance. */
    public static StorageSettings getDefaultInstance() {
      return newSettingsBuilder().build();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof StorageSettings && baseEquals((StorageSettings) other);
    }

    public static ClientBuilder newSettingsBuilder() {
      return new ClientBuilder().setHost(DEFAULT_ENDPOINT);
    }

    private StorageSettings(ClientBuilder clientCreator) {
      super(StorageProvider.class, StorageRpcProvider.class, clientCreator, new StorageDefaultsProvider());
    }

    public static HttpTransportOptions getDefaultHttpTransportOptions() {
      return HttpTransportOptions.newBuilder().build();
    }

    // Project ID is only required for creating buckets, so we don't require it for creating the
    // service.
    @Override
    protected boolean projectIdRequired() {
      return false;
    }

    @Override
    public int hashCode() {
      return baseHashCode();
    }

    /** Returns a unauthenticated {@code StorageOptions} instance. */
    public static StorageSettings getUnauthenticatedInstance() {
      return newSettingsBuilder().setCredentials(NoCredentials.getInstance()).build();
    }

}
