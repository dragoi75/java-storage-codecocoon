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

import static com.google.cloud.RetryHelper.runWithRetries;
import static java.util.concurrent.Executors.callable;
import com.google.cloud.BaseWriteChannel;
import com.google.cloud.RestorableState;
import com.google.cloud.RetryHelper;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Write channel implementation to upload Google Cloud Storage blobs.
 */
class ChunkedBlobWriter extends BaseWriteChannel<StorageSettings, BlobMetadata> {

    ChunkedBlobWriter(StorageSettings storageSettings, BlobMetadata metadata, Map<StorageRpcClient.StorageOption, ?> storageSettingsMap) {
        this(storageSettings, metadata, openWriter(storageSettings, metadata, storageSettingsMap));
    }

    ChunkedBlobWriter(StorageSettings storageSettings, URL signedUri) {
        this(storageSettings, openWriter(signedUri, storageSettings));
    }

    ChunkedBlobWriter(StorageSettings storageSettings, BlobMetadata metadata, String transferId) {
        super(storageSettings, metadata, transferId);
    }

    ChunkedBlobWriter(StorageSettings storageSettings, String transferId) {
        super(storageSettings, null, transferId);
    }

    @Override
    protected void flushBuffer(final int byteCount, final boolean isFinal) {
        try {
            runWithRetries(callable(new Runnable() {

                @Override
                public void run() {
                    getOptions().getStorageRpcV1().write(getUploadId(), getBuffer(), 0, getPosition(), byteCount, isFinal);
                }
            }), getOptions().getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelper.RetryHelperException retryException) {
            throw StorageServiceException.translateThenThrow(retryException);
        }
    }

    protected UploadState.UploadBuilder stateBuilder() {
        return UploadState.newBuilder(getOptions(), getEntity(), getUploadId());
    }

    private static String openWriter(final StorageSettings storageSettings, final BlobMetadata metadata, final Map<StorageRpcClient.StorageOption, ?> storageSettingsMap) {
        try {
            return runWithRetries(new Callable<String>() {

                @Override
                public String call() {
                    return storageSettings.getStorageRpcV1().open(metadata.toProto(), storageSettingsMap);
                }
            }, storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException retryException) {
            throw StorageServiceException.translateThenThrow(retryException);
        }
    }

    private static String openWriter(final URL signedUri, final StorageSettings storageSettings) {
        try {
            return runWithRetries(new Callable<String>() {

                @Override
                public String call() {
                    if (!isValidSignedURL(signedUri.getQuery())) {
                        throw new StorageServiceException(2, "invalid signedURL");
                    }
                    return storageSettings.getStorageRpcV1().open(signedUri.toString());
                }
            }, storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException retryException) {
            throw StorageServiceException.translateThenThrow(retryException);
        }
    }

    private static boolean isValidSignedURL(String queryString) {
        boolean validFlag = true;
        if (!queryString.startsWith("X-Goog-Algorithm=")) {
            if (!queryString.startsWith("GoogleAccessId=")) {
                validFlag = false;
            } else {
                if (!queryString.contains("&Expires=") || !queryString.contains("&Signature=")) {
                    validFlag = false;
                }
            }
        } else {
            if (!queryString.contains("&X-Goog-Credential=") || !queryString.contains("&X-Goog-Date=") || !queryString.contains("&X-Goog-Expires=") || !queryString.contains("&X-Goog-SignedHeaders=") || !queryString.contains("&X-Goog-Signature=")) {
                validFlag = false;
            }
        }
        return validFlag;
    }

    static class UploadState extends BaseWriteChannel.BaseState<StorageSettings, BlobMetadata> {

        private static final long serialVersionUID = -9028324143780151286L;

        UploadState(UploadBuilder stateCreator) {
            super(stateCreator);
        }

        static class UploadBuilder extends BaseWriteChannel.BaseState.Builder<StorageSettings, BlobMetadata> {

            private UploadBuilder(StorageSettings storageSettings, BlobMetadata metadata, String transferId) {
                super(storageSettings, metadata, transferId);
            }

            @Override
            public RestorableState<WriteChannel> build() {
                return new UploadState(this);
            }
        }

        static UploadBuilder newBuilder(StorageSettings storageSettings, BlobMetadata metadata, String transferId) {
            return new UploadBuilder(storageSettings, metadata, transferId);
        }

        @Override
        public WriteChannel restore() {
            ChunkedBlobWriter writer = new ChunkedBlobWriter(serviceOptions, entity, uploadId);
            writer.restore(this);
            return writer;
        }
    }
}
