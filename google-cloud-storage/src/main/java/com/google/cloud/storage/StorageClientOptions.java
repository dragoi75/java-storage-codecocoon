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
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class StorageClientOptions extends ServiceOptions<CloudStorageClient, StorageClientOptions> {

  private static final long serialVersionUID = -2907268477247502947L;
  private static final String API_ABBREV = "Storage";
  private static final String CLOUD_STORAGE_SCOPE = "https://www.googleapis.com/auth/devstorage.full_control";
  private static final Set<String> SCOPE_VALUES = ImmutableSet.of(CLOUD_STORAGE_SCOPE);
  private static final String FALLBACK_HOST = "https://storage.googleapis.com";

  public static class StandardStorageFactory implements StorageProvider {

    private static final StorageProvider STORAGE_PROVIDER_SINGLETON = new StandardStorageFactory();

    @Override
    public CloudStorageClient create(StorageClientOptions clientConfig) {
      return new StorageServiceImpl(clientConfig);
    }
  }

  public static class DefaultStorageRpcProvider implements StorageRpcProvider {

    private static final StorageRpcProvider STORAGE_PROVIDER_SINGLETON = new DefaultStorageRpcProvider();

    @Override
    public ServiceRpc create(StorageClientOptions clientConfig) {
      return new HttpStorageClient(clientConfig);
    }
  }

  public static class ServiceClientBuilder extends ServiceOptions.Builder<CloudStorageClient, StorageClientOptions, ServiceClientBuilder> {

    private ServiceClientBuilder() {}

    private ServiceClientBuilder(StorageClientOptions clientConfig) {
      super(clientConfig);
    }

    @Override
    public StorageClientOptions.ServiceClientBuilder setTransportOptions(TransportOptions transportConfig) {
      if (!(transportConfig instanceof HttpTransportOptions)) {
        throw new IllegalArgumentException(
            "Only http transport is allowed for " + API_ABBREV + ".");
      }
      return super.setTransportOptions(transportConfig);
    }

    @Override
    public StorageClientOptions build() {
      return new StorageClientOptions(this);
    }
  }

  private StorageClientOptions(ServiceClientBuilder clientCreator) {
    super(StorageProvider.class, StorageRpcProvider.class, clientCreator, new StorageDefaultsProvider());
  }

  private static class StorageDefaultsProvider implements ServiceDefaults<CloudStorageClient, StorageClientOptions> {

    @Override
    public StorageProvider getDefaultServiceFactory() {
      return StandardStorageFactory.STORAGE_PROVIDER_SINGLETON;
    }

    @Override
    public StorageRpcProvider getDefaultRpcFactory() {
      return DefaultStorageRpcProvider.STORAGE_PROVIDER_SINGLETON;
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
    return SCOPE_VALUES;
  }

  protected StorageRpcClient getStorageRpcV1() {
    return (StorageRpcClient) getRpc();
  }

  /** Returns a default {@code StorageOptions} instance. */
  public static StorageClientOptions getDefaultInstance() {
    return newServiceClientBuilder().build();
  }

  /** Returns a unauthenticated {@code StorageOptions} instance. */
  public static StorageClientOptions getUnauthenticatedInstance() {
    return newServiceClientBuilder().setCredentials(NoCredentials.getInstance()).build();
  }

  @SuppressWarnings("unchecked")
  @Override
  public StorageClientOptions.ServiceClientBuilder toBuilder() {
    return new ServiceClientBuilder(this).setHost(FALLBACK_HOST);
  }

  @Override
  public int hashCode() {
    return baseHashCode();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof StorageClientOptions && baseEquals((StorageClientOptions) other);
  }

  public static ServiceClientBuilder newServiceClientBuilder() {
    return new ServiceClientBuilder().setHost(FALLBACK_HOST);
  }
}
