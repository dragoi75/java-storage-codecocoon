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
  private static final long STORAGE_TIER_VERSION = -6938125060419556331L;

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

  private static final StringEnumType<StorageTier> STORAGE_TIER_ENUM =
      new StringEnumType(StorageTier.class, STORAGE_TIER_FACTORY);

  /** Regional storage class. */
  public static final StorageTier REGIONAL = STORAGE_TIER_ENUM.createAndRegister("REGIONAL");

  /** Multi-regional storage class. */
  public static final StorageTier MULTI_REGIONAL = STORAGE_TIER_ENUM.createAndRegister("MULTI_REGIONAL");

  /** Nearline storage class. */
  public static final StorageTier NEARLINE = STORAGE_TIER_ENUM.createAndRegister("NEARLINE");

  /** Coldline storage class. */
  public static final StorageTier COLDLINE = STORAGE_TIER_ENUM.createAndRegister("COLDLINE");

  /** Standard storage class. */
  public static final StorageTier STANDARD = STORAGE_TIER_ENUM.createAndRegister("STANDARD");

  /** Durable Reduced Availability (deprecated) */
  public static final StorageTier DURABLE_REDUCED_AVAILABILITY =
      STORAGE_TIER_ENUM.createAndRegister("DURABLE_REDUCED_AVAILABILITY");

  /**
   * Get the StorageTier for the given String constant, and throw an exception if the constant is
   * not recognized.
   */
  public static StorageTier valueOfStrict(String value) {
    return STORAGE_TIER_ENUM.valueOfStrict(value);
  }

  /** Get the StorageTier for the given String constant, and allow unrecognized values. */
  public static StorageTier fromValue(String value) {
    return STORAGE_TIER_ENUM.valueOf(value);
  }

  /** Return the known values for StorageTier. */
  public static StorageTier[] values() {
    return STORAGE_TIER_ENUM.values();
  }
}
