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

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.core.InternalApi;
import com.google.cloud.BaseServiceException;
import com.google.cloud.RetryHelper.RetryHelperException;
import com.google.cloud.http.BaseHttpServiceException;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Set;

/**
 * Storage service exception.
 *
 * @see <a href="https://cloud.google.com/storage/docs/json_api/v1/status-codes">Google Cloud
 *     Storage error codes</a>
 */
@InternalApi
public final class StorageOperationException extends BaseHttpServiceException {
  private static final String SYSTEM_FAILURE = "internalError";
  private static final String STREAM_CLOSED_EARLY = "connectionClosedPrematurely";

  // see: https://cloud.google.com/storage/docs/resumable-uploads-xml#practices
  private static final Set<Error> TRANSIENT_ERRORS =
      ImmutableSet.of(
          new Error(504, null),
          new Error(503, null),
          new Error(502, null),
          new Error(500, null),
          new Error(429, null),
          new Error(408, null),
          new Error(null, SYSTEM_FAILURE),
          new Error(null, STREAM_CLOSED_EARLY));

  private static final long serialVersionUID = -4168430271327813063L;

  public StorageOperationException(int status, String detail) {
    this(status, detail, null);
  }

  public StorageOperationException(int status, String detail, Throwable rootThrowable) {
    super(status, detail, null, true, TRANSIENT_ERRORS, rootThrowable);
  }

  public StorageOperationException(int status, String detail, String explanation, Throwable rootThrowable) {
    super(status, detail, explanation, true, TRANSIENT_ERRORS, rootThrowable);
  }

  public StorageOperationException(IOException ioFailure) {
    super(ioFailure, true, TRANSIENT_ERRORS);
  }

  public StorageOperationException(GoogleJsonError apiFault) {
    super(apiFault, true, TRANSIENT_ERRORS);
  }

  /**
   * Translate RetryHelperException to the StorageException that caused the error. This method will
   * always throw an exception.
   *
   * @throws StorageOperationException when {@code ex} was caused by a {@code StorageException}
   */
  public static StorageOperationException translateAndRethrow(RetryHelperException retryException) {
    BaseServiceException.translate(retryException);
    throw new StorageOperationException(UNKNOWN_CODE, retryException.getMessage(), retryException.getCause());
  }

  /**
   * Translate IOException to a StorageException representing the cause of the error. This method
   * defaults to idempotent always being {@code true}. Additionally, this method translates
   * transient issues Connection Closed Prematurely as a retryable error.
   *
   * @returns {@code StorageException}
   */
  public static StorageOperationException translateException(IOException ioFailure) {
    if (ioFailure.getMessage().contains("Connection closed prematurely")) {
      return new StorageOperationException(
          0, ioFailure.getMessage(), STREAM_CLOSED_EARLY, ioFailure);
    } else {
      // default
      return new StorageOperationException(ioFailure);
    }
  }
}
