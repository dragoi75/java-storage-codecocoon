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
import com.google.cloud.storage.spi.v1.HttpStorageServiceRpc;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class StorageSettings extends ServiceOptions<StorageService, StorageSettings> {

  private static final long serialVersionUID = -2907268477247502947L;
  private static final String API_DISPLAY_NAME = "Storage";
  private static final String GCS_OAUTH_SCOPE = "https://www.googleapis.com/auth/devstorage.full_control";
  private static final Set<String> ALL_SCOPES = ImmutableSet.of(GCS_OAUTH_SCOPE);
  private static final String DEFAULT_ENDPOINT = "https://storage.googleapis.com";

  public static class DefaultStorageProvider implements StorageProviderFactory {

    private static final StorageProviderFactory FACTORY_INSTANCE = new DefaultStorageProvider();

    @Override
    public StorageService create(StorageSettings storageSettings) {
      return new DefaultStorageImpl(storageSettings);
    }
  }

  public static class DefaultStorageRpcFactoryImpl implements StorageRpcProvider {

    private static final StorageRpcProvider FACTORY_INSTANCE = new DefaultStorageRpcFactoryImpl();

    @Override
    public ServiceRpc create(StorageSettings storageSettings) {
      return new HttpStorageServiceRpc(storageSettings);
    }
  }

  public static class ClientBuilder extends ServiceOptions.Builder<StorageService, StorageSettings, ClientBuilder> {

    private ClientBuilder() {}

    private ClientBuilder(StorageSettings storageSettings) {
      super(storageSettings);
    }

    @Override
    public StorageSettings.ClientBuilder setTransportOptions(TransportOptions transportConfig) {
      if (!(transportConfig instanceof HttpTransportOptions)) {
        throw new IllegalArgumentException(
            "Only http transport is allowed for " + API_DISPLAY_NAME + ".");
      }
      return super.setTransportOptions(transportConfig);
    }

    @Override
    public StorageSettings build() {
      return new StorageSettings(this);
    }
  }

  private StorageSettings(ClientBuilder clientBuilder) {
    super(StorageProviderFactory.class, StorageRpcProvider.class, clientBuilder, new StorageDefaultsProvider());
  }

  private static class StorageDefaultsProvider implements ServiceDefaults<StorageService, StorageSettings> {

    @Override
    public StorageProviderFactory getDefaultServiceFactory() {
      return DefaultStorageProvider.FACTORY_INSTANCE;
    }

    @Override
    public StorageRpcProvider getDefaultRpcFactory() {
      return DefaultStorageRpcFactoryImpl.FACTORY_INSTANCE;
    }

    @Override
    public TransportOptions getDefaultTransportOptions() {
      return getDefaultHttpTransportOptions();
    }
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
  protected Set<String> getScopes() {
    return ALL_SCOPES;
  }

  protected StorageRpcClient getStorageRpcV1() {
    return (StorageRpcClient) getRpc();
  }

  /** Returns a default {@code StorageOptions} instance. */
  public static StorageSettings getDefaultInstance() {
    return newClientBuilder().build();
  }

  /** Returns a unauthenticated {@code StorageOptions} instance. */
  public static StorageSettings getUnauthenticatedInstance() {
    return newClientBuilder().setCredentials(NoCredentials.getInstance()).build();
  }

  @SuppressWarnings("unchecked")
  @Override
  public StorageSettings.ClientBuilder toBuilder() {
    return new ClientBuilder(this).setHost(DEFAULT_ENDPOINT);
  }

  @Override
  public int hashCode() {
    return baseHashCode();
  }

  @Override
  public boolean equals(Object otherObject) {
    return otherObject instanceof StorageSettings && baseEquals((StorageSettings) otherObject);
  }

  public static ClientBuilder newClientBuilder() {
    return new ClientBuilder().setHost(DEFAULT_ENDPOINT);
  }
}
