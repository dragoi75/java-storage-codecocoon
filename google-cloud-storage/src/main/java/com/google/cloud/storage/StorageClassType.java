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
public final class StorageClassType extends StringEnumValue {
  private static final long serialVersionUID = -6938125060419556331L;

  private StorageClassType(String value) {
    super(value);
  }

  private static final ApiFunction<String, StorageClassType> STORAGE_CLASS_FACTORY =
      new ApiFunction<String, StorageClassType>() {
        @Override
        public StorageClassType apply(String constant) {
          return new StorageClassType(constant);
        }
      };

  private static final StringEnumType<StorageClassType> STORAGE_CLASS_ENUM =
      new StringEnumType(StorageClassType.class, STORAGE_CLASS_FACTORY);

  /**
   * Standard storage class. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageClassType STANDARD = STORAGE_CLASS_ENUM.createAndRegister("STANDARD");

  /**
   * Nearline storage class. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageClassType NEARLINE = STORAGE_CLASS_ENUM.createAndRegister("NEARLINE");

  /**
   * Coldline storage class. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageClassType COLDLINE = STORAGE_CLASS_ENUM.createAndRegister("COLDLINE");

  /**
   * Archive storage class. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageClassType ARCHIVE = STORAGE_CLASS_ENUM.createAndRegister("ARCHIVE");

  /**
   * Regional storage class. This is supported as a legacy storage class and will be deprecated in
   * the future. See: https://cloud.google.com/storage/docs/storage-classes for details
   */
  public static final StorageClassType REGIONAL = STORAGE_CLASS_ENUM.createAndRegister("REGIONAL");

  /**
   * Multi-regional storage class. This is supported as a legacy storage class and will be
   * deprecated in the future. See: https://cloud.google.com/storage/docs/storage-classes for
   * details
   */
  public static final StorageClassType MULTI_REGIONAL = STORAGE_CLASS_ENUM.createAndRegister("MULTI_REGIONAL");

  /**
   * Durable Reduced Availability storage class. This is supported as a legacy storage class and
   * will be deprecated in the future. See: https://cloud.google.com/storage/docs/storage-classes
   * for details
   */
  public static final StorageClassType DURABLE_REDUCED_AVAILABILITY =
      STORAGE_CLASS_ENUM.createAndRegister("DURABLE_REDUCED_AVAILABILITY");

  /**
   * Get the StorageClass for the given String constant, and throw an exception if the constant is
   * not recognized.
   */
  public static StorageClassType valueOfStrict(String value) {
    return STORAGE_CLASS_ENUM.valueOfStrict(value);
  }

  /** Get the StorageClass for the given String constant, and allow unrecognized values. */
  public static StorageClassType fromValue(String value) {
    return STORAGE_CLASS_ENUM.valueOf(value);
  }

  /** Return the known values for StorageClass. */
  public static StorageClassType[] values() {
    return STORAGE_CLASS_ENUM.values();
  }
}
