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
import com.google.cloud.storage.spi.StorageRpcFactory;
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient;
import com.google.cloud.storage.spi.v1.HttpStorageRpc;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class StorageSettings extends ServiceOptions<StorageClient, StorageSettings> {

  private static final long serialVersionUID = -2907268477247502947L;
  private static final String API_ALIAS = "Storage";
  private static final String STORAGE_SCOPE = "https://www.googleapis.com/auth/devstorage.full_control";
  private static final Set<String> SCOPE_GROUP = ImmutableSet.of(STORAGE_SCOPE);
  private static final String DEFAULT_ENDPOINT = "https://storage.googleapis.com";

  public static class DefaultStorageProvider implements StorageFactory {

    private static final StorageFactory DEFAULT_STORAGE_FACTORY = new DefaultStorageProvider();

    @Override
    public StorageClient create(StorageSettings storageSettings) {
      return new StorageImpl(storageSettings);
    }
  }

  public static class StorageRpcFactoryImpl implements StorageRpcFactory {

    private static final StorageRpcFactory DEFAULT_STORAGE_FACTORY = new StorageRpcFactoryImpl();

    @Override
    public ServiceRpc create(StorageSettings storageSettings) {
      return new HttpStorageRpc(storageSettings);
    }
  }

  public static class ClientBuilder extends ServiceOptions.Builder<StorageClient, StorageSettings, ClientBuilder> {

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

      private ClientBuilder(StorageSettings storageSettings) {
        super(storageSettings);
      }

      private ClientBuilder() {}

  }

    private static class StorageDefaultsProvider implements ServiceDefaults<StorageClient, StorageSettings> {

        @Override
        public TransportOptions getDefaultTransportOptions() {
          return getDefaultHttpTransportOptions();
        }

        @Override
        public StorageRpcFactory getDefaultRpcFactory() {
          return StorageRpcFactoryImpl.DEFAULT_STORAGE_FACTORY;
        }

        @Override
        public StorageFactory getDefaultServiceFactory() {
          return DefaultStorageProvider.DEFAULT_STORAGE_FACTORY;
        }

    }

    @Override
    protected Set<String> getScopes() {
      return SCOPE_GROUP;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof StorageSettings && baseEquals((StorageSettings) other);
    }

    @SuppressWarnings("unchecked")
    @Override
    public StorageSettings.ClientBuilder toBuilder() {
      return new ClientBuilder(this).setHost(DEFAULT_ENDPOINT);
    }

    /** Returns a default {@code StorageOptions} instance. */
    public static StorageSettings getDefaultInstance() {
      return newStorageBuilder().build();
    }

    @Override
    public int hashCode() {
      return baseHashCode();
    }

    public static HttpTransportOptions getDefaultHttpTransportOptions() {
      return HttpTransportOptions.newBuilder().build();
    }

    /** Returns a unauthenticated {@code StorageOptions} instance. */
    public static StorageSettings getUnauthenticatedInstance() {
      return newStorageBuilder().setCredentials(NoCredentials.getInstance()).build();
    }

    public static ClientBuilder newStorageBuilder() {
      return new ClientBuilder().setHost(DEFAULT_ENDPOINT);
    }

    // Project ID is only required for creating buckets, so we don't require it for creating the
    // service.
    @Override
    protected boolean projectIdRequired() {
      return false;
    }

    private StorageSettings(ClientBuilder client) {
      super(StorageFactory.class, StorageRpcFactory.class, client, new StorageDefaultsProvider());
    }

    protected CloudStorageRpcClient getStorageRpcV1() {
      return (CloudStorageRpcClient) getRpc();
    }

}
