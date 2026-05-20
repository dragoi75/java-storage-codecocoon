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
 * Enums for the storage classes. See <a
 * href="https://cloud.google.com/storage/docs/storage-classes">https://cloud.google.com/storage/docs/storage-classes</a>
 * for details.
 */
public final class StorageClassType extends StringEnumValue {
  private static final long serialVersionUID = -6938125060419556331L;

  private StorageClassType(String value) {
    super(value);
  }

  private static final ApiFunction<String, StorageClassType> STORAGE_CLASS_CREATOR_FN =
      new ApiFunction<String, StorageClassType>() {
        @Override
        public StorageClassType apply(String constant) {
          return new StorageClassType(constant);
        }
      };

  private static final StringEnumType<StorageClassType> STORAGE_CLASS_KIND =
      new StringEnumType(StorageClassType.class, STORAGE_CLASS_CREATOR_FN);

  /**
   * Standard storage class.
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/storage-classes#standard">https://cloud.google.com/storage/docs/storage-classes#standard</a>
   */
  public static final StorageClassType STANDARD = STORAGE_CLASS_KIND.createAndRegister("STANDARD");

  /**
   * Nearline storage class.
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/storage-classes#nearline">https://cloud.google.com/storage/docs/storage-classes#nearline</a>
   */
  public static final StorageClassType NEARLINE = STORAGE_CLASS_KIND.createAndRegister("NEARLINE");

  /**
   * Coldline storage class.
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/storage-classes#coldline">https://cloud.google.com/storage/docs/storage-classes#coldline</a>
   */
  public static final StorageClassType COLDLINE = STORAGE_CLASS_KIND.createAndRegister("COLDLINE");

  /**
   * Archive storage class.
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/storage-classes#archive">https://cloud.google.com/storage/docs/storage-classes#archive</a>
   */
  public static final StorageClassType ARCHIVE = STORAGE_CLASS_KIND.createAndRegister("ARCHIVE");

  /**
   * Legacy Regional storage class, use {@link #STANDARD} instead. This class will be deprecated in
   * the future.
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/storage-classes#legacy">https://cloud.google.com/storage/docs/storage-classes#legacy</a>
   */
  public static final StorageClassType REGIONAL = STORAGE_CLASS_KIND.createAndRegister("REGIONAL");

  /**
   * Legacy Multi-regional storage class, use {@link #STANDARD} instead. This class will be
   * deprecated in the future.
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/storage-classes#legacy">https://cloud.google.com/storage/docs/storage-classes#legacy</a>
   */
  public static final StorageClassType MULTI_REGIONAL = STORAGE_CLASS_KIND.createAndRegister("MULTI_REGIONAL");

  /**
   * Legacy Durable Reduced Availability storage class, use {@link #STANDARD} instead. This class
   * will be deprecated in the future.
   *
   * @see <a
   *     href="https://cloud.google.com/storage/docs/storage-classes#legacy">https://cloud.google.com/storage/docs/storage-classes#legacy</a>
   */
  public static final StorageClassType DURABLE_REDUCED_AVAILABILITY =
      STORAGE_CLASS_KIND.createAndRegister("DURABLE_REDUCED_AVAILABILITY");

  /**
   * Get the StorageClass for the given String constant, and throw an exception if the constant is
   * not recognized.
   */
  public static StorageClassType valueOfStrict(String value) {
    return STORAGE_CLASS_KIND.valueOfStrict(value);
  }

  /** Get the StorageClass for the given String constant, and allow unrecognized values. */
  public static StorageClassType fromValue(String value) {
    return STORAGE_CLASS_KIND.valueOf(value);
  }

  /** Return the known values for StorageClass. */
  public static StorageClassType[] values() {
    return STORAGE_CLASS_KIND.values();
  }
}
