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

    private static final String SERVER_ERROR = "internalError";

    private static final String CONNECTION_TERMINATED_EARLY = "connectionClosedPrematurely";

    // see: https://cloud.google.com/storage/docs/resumable-uploads-xml#practices
    private static final Set<Error> TRANSIENT_ERRORS = ImmutableSet.of(new Error(504, null), new Error(503, null), new Error(502, null), new Error(500, null), new Error(429, null), new Error(408, null), new Error(null, SERVER_ERROR), new Error(null, CONNECTION_TERMINATED_EARLY));

    private static final long serialVersionUID = -4168430271327813063L;

    /**
     * Translate RetryHelperException to the StorageException that caused the error. This method will
     * always throw an exception.
     *
     * @throws StorageServiceException when {@code ex} was caused by a {@code StorageException}
     */
    public static StorageServiceException translateThenThrow(RetryHelperException retryFailure) {
        BaseServiceException.translate(retryFailure);
        throw new StorageServiceException(UNKNOWN_CODE, retryFailure.getMessage(), retryFailure.getCause());
    }

    /**
     * Translate IOException to a StorageException representing the cause of the error. This method
     * defaults to idempotent always being {@code true}. Additionally, this method translates
     * transient issues Connection Closed Prematurely as a retryable error.
     *
     * @returns {@code StorageException}
     */
    public static StorageServiceException translateException(IOException ioFailure) {
        if (!ioFailure.getMessage().contains("Connection closed prematurely")) {
            // default
            return new StorageServiceException(ioFailure);
        } else {
            return new StorageServiceException(0, ioFailure.getMessage(), CONNECTION_TERMINATED_EARLY, ioFailure);
        }
    }

    public StorageServiceException(IOException ioFailure) {
        super(ioFailure, true, TRANSIENT_ERRORS);
    }

    public StorageServiceException(int status, String description, String explanation, Throwable underlyingThrowable) {
        super(status, description, explanation, true, TRANSIENT_ERRORS, underlyingThrowable);
    }

    public StorageServiceException(int status, String description, Throwable underlyingThrowable) {
        super(status, description, null, true, TRANSIENT_ERRORS, underlyingThrowable);
    }

    public StorageServiceException(GoogleJsonError jsonProblem) {
        super(jsonProblem, true, TRANSIENT_ERRORS);
    }

    public StorageServiceException(int status, String description) {
        this(status, description, null);
    }

}
