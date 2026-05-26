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
import com.google.cloud.storage.spi.v1.CloudStorageRpc;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Write channel implementation to upload Google Cloud Storage blobs.
 */
class BlobUploadChannel extends BaseWriteChannel<StorageSettings, BlobMetadata> {

    static class UploadStateImpl extends BaseWriteChannel.BaseState<StorageSettings, BlobMetadata> {

        private static final long serialVersionUID = -9028324143780151286L;

        static class UploadBuilder extends BaseWriteChannel.BaseState.Builder<StorageSettings, BlobMetadata> {

            @Override
            public RestorableState<WriteChannel> build() {
                return new UploadStateImpl(this);
            }

            private UploadBuilder(StorageSettings storageSettings, BlobMetadata metadata, String transferId) {
                super(storageSettings, metadata, transferId);
            }

        }

        static UploadBuilder newBuilder(StorageSettings storageSettings, BlobMetadata metadata, String transferId) {
            return new UploadBuilder(storageSettings, metadata, transferId);
        }

        @Override
        public WriteChannel restore() {
            BlobUploadChannel uploadStream = new BlobUploadChannel(serviceOptions, entity, uploadId);
            uploadStream.restore(this);
            return uploadStream;
        }

        UploadStateImpl(UploadBuilder buildSource) {
            super(buildSource);
        }

    }

    @Override
    protected void flushBuffer(final int byteCount, final boolean isFinalChunk) {
        try {
            runWithRetries(callable(new Runnable() {

                @Override
                public void run() {
                    getOptions().getStorageRpcV1().write(getUploadId(), getBuffer(), 0, getPosition(), byteCount, isFinalChunk);
                }
            }), getOptions().getRetrySettings(), StorageClientImpl.EXCEPTION_HANDLER, getOptions().getClock());
        } catch (RetryHelper.RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private static String openChannel(final URL accessUri, final StorageSettings storageSettings) {
        try {
            return runWithRetries(new Callable<String>() {

                @Override
                public String call() {
                    if (!isValidSignedURL(accessUri.getQuery())) {
                        throw new StorageOperationException(2, "invalid signedURL");
                    }
                    return storageSettings.getStorageRpcV1().open(accessUri.toString());
                }
            }, storageSettings.getRetrySettings(), StorageClientImpl.EXCEPTION_HANDLER, storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
        }
    }

    private static String openChannel(final StorageSettings storageSettings, final BlobMetadata metadata, final Map<CloudStorageRpc.StorageOption, ?> storageSettingsMap) {
        try {
            return runWithRetries(new Callable<String>() {

                @Override
                public String call() {
                    return storageSettings.getStorageRpcV1().open(metadata.toProto(), storageSettingsMap);
                }
            }, storageSettings.getRetrySettings(), StorageClientImpl.EXCEPTION_HANDLER, storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException retryEx) {
            throw StorageOperationException.translateAndRethrow(retryEx);
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

    BlobUploadChannel(StorageSettings storageSettings, String transferId) {
        super(storageSettings, null, transferId);
    }

    BlobUploadChannel(StorageSettings storageSettings, BlobMetadata metadata, String transferId) {
        super(storageSettings, metadata, transferId);
    }

    BlobUploadChannel(StorageSettings storageSettings, BlobMetadata metadata, Map<CloudStorageRpc.StorageOption, ?> storageSettingsMap) {
        this(storageSettings, metadata, openChannel(storageSettings, metadata, storageSettingsMap));
    }

    BlobUploadChannel(StorageSettings storageSettings, URL accessUri) {
        this(storageSettings, openChannel(accessUri, storageSettings));
    }

    protected UploadStateImpl.UploadBuilder stateBuilder() {
        return UploadStateImpl.newBuilder(getOptions(), getEntity(), getUploadId());
    }

}
