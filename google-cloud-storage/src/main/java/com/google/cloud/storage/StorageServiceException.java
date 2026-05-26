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
public final class StorageServiceException extends BaseHttpServiceException {

    private static final String SERVER_INTERNAL_ERROR = "internalError";

    private static final String CONNECTION_CLOSED_EARLY = "connectionClosedPrematurely";

    // see: https://cloud.google.com/storage/docs/resumable-uploads-xml#practices
    static final Set<Error> RETRYABLE_ERROR_SET = ImmutableSet.of(new Error(504, null), new Error(503, null), new Error(502, null), new Error(500, null), new Error(429, null), new Error(408, null), new Error(null, SERVER_INTERNAL_ERROR), new Error(null, CONNECTION_CLOSED_EARLY));

    private static final long serialVersionUID = -4168430271327813063L;

    /**
     * Translate RetryHelperException to the StorageException that caused the error. This method will
     * always throw an exception.
     *
     * @throws StorageServiceException when {@code ex} was caused by a {@code StorageException}
     */
    public static StorageServiceException translateAndRethrow(RetryHelperException retryFailure) {
        BaseServiceException.translate(retryFailure);
        throw getStorageException(retryFailure);
    }

    private static StorageServiceException getStorageException(Throwable throwable) {
        return new StorageServiceException(UNKNOWN_CODE, throwable.getMessage(), throwable.getCause());
    }

    /**
     * Translate IOException to a StorageException representing the cause of the error. This method
     * defaults to idempotent always being {@code true}. Additionally, this method translates
     * transient issues Connection Closed Prematurely as a retryable error.
     *
     * @returns {@code StorageException}
     */
    public static StorageServiceException translateException(IOException ioError) {
        if (!ioError.getMessage().contains("Connection closed prematurely")) {
            // default
            return new StorageServiceException(ioError);
        } else {
            return new StorageServiceException(0, ioError.getMessage(), CONNECTION_CLOSED_EARLY, ioError);
        }
    }

    public StorageServiceException(int statusValue, String detail, Throwable rootThrowable) {
        super(statusValue, detail, null, true, RETRYABLE_ERROR_SET, rootThrowable);
    }

    /**
     * Attempt to find an Exception which is a {@link BaseServiceException} If neither {@code t} or
     * {@code t.getCause()} are a {@code BaseServiceException} a {@link StorageServiceException} will be
     * created with an unknown status code.
     */
    static BaseServiceException coalesceException(Throwable throwable) {
        if (throwable instanceof BaseServiceException) {
            return (BaseServiceException) throwable;
        }
        if (throwable.getCause() instanceof BaseServiceException) {
            return (BaseServiceException) throwable.getCause();
        }
        return getStorageException(throwable);
    }

    public StorageServiceException(GoogleJsonError jsonFault) {
        super(jsonFault, true, RETRYABLE_ERROR_SET);
    }

    public StorageServiceException(int statusValue, String detail, String explanation, Throwable rootThrowable) {
        super(statusValue, detail, explanation, true, RETRYABLE_ERROR_SET, rootThrowable);
    }

    public StorageServiceException(IOException ioError) {
        super(ioError, true, RETRYABLE_ERROR_SET);
    }

    public StorageServiceException(int statusValue, String detail) {
        this(statusValue, detail, null);
    }

}
