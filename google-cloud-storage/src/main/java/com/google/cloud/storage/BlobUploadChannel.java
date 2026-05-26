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
class BlobUploadChannel extends BaseWriteChannel<StorageSettings, BlobMetadata> {

    static class ResumableUploadState extends BaseWriteChannel.BaseState<StorageSettings, BlobMetadata> {

        private static final long serialVersionUID = -9028324143780151286L;

        static class ChunkedUploadBuilder extends BaseWriteChannel.BaseState.Builder<StorageSettings, BlobMetadata> {

            @Override
            public RestorableState<WriteChannel> build() {
                return new ResumableUploadState(this);
            }

            private ChunkedUploadBuilder(StorageSettings storageSettings, BlobMetadata metadataInfo, String uploadToken) {
                super(storageSettings, metadataInfo, uploadToken);
            }

        }

        static ChunkedUploadBuilder newBuilder(StorageSettings storageSettings, BlobMetadata metadataInfo, String uploadToken) {
            return new ChunkedUploadBuilder(storageSettings, metadataInfo, uploadToken);
        }

        @Override
        public WriteChannel restore() {
            BlobUploadChannel uploadStream = new BlobUploadChannel(serviceOptions, entity, uploadId);
            uploadStream.restore(this);
            return uploadStream;
        }

        ResumableUploadState(ChunkedUploadBuilder chunkedUploadConfig) {
            super(chunkedUploadConfig);
        }

    }

    private static String openChannel(final URL signedUri, final StorageSettings storageSettings) {
        try {
            return runWithRetries(new Callable<String>() {

                @Override
                public String call() {
                    if (!isValidSignedURL(signedUri.getQuery())) {
                        throw new StorageOperationException(2, "invalid signedURL");
                    }
                    return storageSettings.getStorageRpcV1().open(signedUri.toString());
                }
            }, storageSettings.getRetrySettings(), DefaultStorageImpl.EXCEPTION_HANDLER, storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    BlobUploadChannel(StorageSettings storageSettings, URL signedUri) {
        this(storageSettings, openChannel(signedUri, storageSettings));
    }

    protected ResumableUploadState.ChunkedUploadBuilder stateBuilder() {
        return ResumableUploadState.newBuilder(getOptions(), getEntity(), getUploadId());
    }

    private static String openChannel(final StorageSettings storageSettings, final BlobMetadata metadata, final Map<StorageRpcClient.StorageOption, ?> settingsMap) {
        try {
            return runWithRetries(new Callable<String>() {

                @Override
                public String call() {
                    return storageSettings.getStorageRpcV1().open(metadata.toProto(), settingsMap);
                }
            }, storageSettings.getRetrySettings(), DefaultStorageImpl.EXCEPTION_HANDLER, storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

    private static boolean isValidSignedURL(String signedQuery) {
        boolean validFlag = true;
        if (!signedQuery.startsWith("X-Goog-Algorithm=")) {
            if (!signedQuery.startsWith("GoogleAccessId=")) {
                validFlag = false;
            } else {
                if (!signedQuery.contains("&Expires=") || !signedQuery.contains("&Signature=")) {
                    validFlag = false;
                }
            }
        } else {
            if (!signedQuery.contains("&X-Goog-Credential=") || !signedQuery.contains("&X-Goog-Date=") || !signedQuery.contains("&X-Goog-Expires=") || !signedQuery.contains("&X-Goog-SignedHeaders=") || !signedQuery.contains("&X-Goog-Signature=")) {
                validFlag = false;
            }
        }
        return validFlag;
    }

    BlobUploadChannel(StorageSettings storageSettings, BlobMetadata metadataInfo, String uploadToken) {
        super(storageSettings, metadataInfo, uploadToken);
    }

    BlobUploadChannel(StorageSettings storageSettings, BlobMetadata metadata, Map<StorageRpcClient.StorageOption, ?> settingsMap) {
        this(storageSettings, metadata, openChannel(storageSettings, metadata, settingsMap));
    }

    BlobUploadChannel(StorageSettings storageSettings, String uploadToken) {
        super(storageSettings, null, uploadToken);
    }

    @Override
    protected void flushBuffer(final int size, final boolean isFinal) {
        try {
            runWithRetries(callable(new Runnable() {

                @Override
                public void run() {
                    getOptions().getStorageRpcV1().write(getUploadId(), getBuffer(), 0, getPosition(), size, isFinal);
                }
            }), getOptions().getRetrySettings(), DefaultStorageImpl.EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelper.RetryHelperException retryException) {
            throw StorageOperationException.translateAndRethrow(retryException);
        }
    }

}
