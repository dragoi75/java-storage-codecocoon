/*
 * Copyright 2017 Google LLC
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

import com.google.api.core.ApiFunction;
import com.google.cloud.StringEnumType;
import com.google.cloud.StringEnumValue;

/**
 * Enums for the storage classes. See https://cloud.google.com/storage/docs/storage-classes for
 * details.
 */
public final class StorageTier extends StringEnumValue {
  private static final long serialVersionUID = -6938125060419556331L;

  private StorageTier(String value) {
    super(value);
  }

  private static final ApiFunction<String, StorageTier> STORAGE_TIER_FACTORY =
      new ApiFunction<String, StorageTier>() {
        @Override
        public StorageTier apply(String constant) {
          return new StorageTier(constant);
        }
      };

  private static final StringEnumType<StorageTier> STORAGE_TIER_KIND =
      new StringEnumType(StorageTier.class, STORAGE_TIER_FACTORY);

  /**
   * Standard storage class. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageTier STANDARD = STORAGE_TIER_KIND.createAndRegister("STANDARD");

  /**
   * Nearline storage class. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageTier NEARLINE = STORAGE_TIER_KIND.createAndRegister("NEARLINE");

  /**
   * Coldline storage class. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageTier COLDLINE = STORAGE_TIER_KIND.createAndRegister("COLDLINE");

  /**
   * Archive storage class. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageTier ARCHIVE = STORAGE_TIER_KIND.createAndRegister("ARCHIVE");

  /**
   * Regional storage class. This is supported as a legacy storage class and will be deprecated in
   * the future. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageTier REGIONAL = STORAGE_TIER_KIND.createAndRegister("REGIONAL");

  /**
   * Multi-regional storage class. This is supported as a legacy storage class and will be
   * deprecated in the future. See: https://cloud.google.com/storage/docs/storage-classes for
   * details
   */
  public static final StorageTier MULTI_REGIONAL = STORAGE_TIER_KIND.createAndRegister("MULTI_REGIONAL");

  /**
   * Durable Reduced Availability storage class. This is supported as a legacy storage class and
   * will be deprecated in the future. See: https://cloud.google.com/storage/docs/storage-classes
   * for details
   */
  public static final StorageTier DURABLE_REDUCED_AVAILABILITY =
      STORAGE_TIER_KIND.createAndRegister("DURABLE_REDUCED_AVAILABILITY");

  /**
   * Get the StorageClass for the given String constant, and throw an exception if the constant is
   * not recognized.
   */
  public static StorageTier valueOfStrict(String value) {
    return STORAGE_TIER_KIND.valueOfStrict(value);
  }

  /** Get the StorageClass for the given String constant, and allow unrecognized values. */
  public static StorageTier fromValue(String value) {
    return STORAGE_TIER_KIND.valueOf(value);
  }

  /** Return the known values for StorageClass. */
  public static StorageTier[] values() {
    return STORAGE_TIER_KIND.values();
  }
}
