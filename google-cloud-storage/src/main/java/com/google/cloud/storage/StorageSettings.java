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

public class StorageSettings extends ServiceOptions<Storage, StorageSettings> {

  private static final long serialVersionUID = -2907268477247502947L;
  private static final String API_ALIAS = "Storage";
  private static final String CLOUD_STORAGE_SCOPE = "https://www.googleapis.com/auth/devstorage.full_control";
  private static final Set<String> AUTH_SCOPES = ImmutableSet.of(CLOUD_STORAGE_SCOPE);
  private static final String DEFAULT_ENDPOINT = "https://storage.googleapis.com";

  public static class DefaultStorageProvider implements StorageCreator {

    private static final StorageCreator STORAGE_CREATOR = new DefaultStorageProvider();

    @Override
    public Storage create(StorageSettings storageSettings) {
      return new StorageServiceImpl(storageSettings);
    }
  }

  public static class DefaultStorageRpcFactoryImpl implements StorageRpcProvider {

    private static final StorageRpcProvider STORAGE_CREATOR = new DefaultStorageRpcFactoryImpl();

    @Override
    public ServiceRpc create(StorageSettings storageSettings) {
      return new HttpStorageRpcClient(storageSettings);
    }
  }

  public static class ServiceClientBuilder extends ServiceOptions.Builder<Storage, StorageSettings, ServiceClientBuilder> {

      @Override
      public StorageSettings.ServiceClientBuilder setTransportOptions(TransportOptions transportConfig) {
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

      private ServiceClientBuilder(StorageSettings storageSettings) {
        super(storageSettings);
      }

      private ServiceClientBuilder() {}

  }

    private static class StorageDefaultsProvider implements ServiceDefaults<Storage, StorageSettings> {

        @Override
        public StorageRpcProvider getDefaultRpcFactory() {
          return DefaultStorageRpcFactoryImpl.STORAGE_CREATOR;
        }

        @Override
        public TransportOptions getDefaultTransportOptions() {
          return getDefaultHttpTransportOptions();
        }

        @Override
        public StorageCreator getDefaultServiceFactory() {
          return DefaultStorageProvider.STORAGE_CREATOR;
        }

    }

    @SuppressWarnings("unchecked")
    @Override
    public StorageSettings.ServiceClientBuilder toBuilder() {
      return new ServiceClientBuilder(this).setHost(DEFAULT_ENDPOINT);
    }

    @Override
    protected Set<String> getScopes() {
      return AUTH_SCOPES;
    }

    public static ServiceClientBuilder createBuilder() {
      return new ServiceClientBuilder().setHost(DEFAULT_ENDPOINT);
    }

    @Override
    public int hashCode() {
      return baseHashCode();
    }

    /** Returns a default {@code StorageOptions} instance. */
    public static StorageSettings getDefaultInstance() {
      return createBuilder().build();
    }

    @Override
    public boolean equals(Object otherObject) {
      return otherObject instanceof StorageSettings && baseEquals((StorageSettings) otherObject);
    }

    private StorageSettings(ServiceClientBuilder source) {
      super(StorageCreator.class, StorageRpcProvider.class, source, new StorageDefaultsProvider());
    }

    /** Returns a unauthenticated {@code StorageOptions} instance. */
    public static StorageSettings getUnauthenticatedInstance() {
      return createBuilder().setCredentials(NoCredentials.getInstance()).build();
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

    protected StorageRpcClient getStorageRpcV1() {
      return (StorageRpcClient) getRpc();
    }

}
