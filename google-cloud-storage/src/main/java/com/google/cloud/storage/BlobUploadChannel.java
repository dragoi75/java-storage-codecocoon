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
class BlobUploadChannel extends BaseWriteChannel<StorageSettings, BlobInfo> {

    BlobUploadChannel(StorageSettings storageSettings, BlobInfo objectInfo, Map<StorageRpcClient.StorageOption, ?> settingsByKey) {
        this(storageSettings, objectInfo, openChannel(storageSettings, objectInfo, settingsByKey));
    }

    BlobUploadChannel(StorageSettings storageSettings, URL accessLink) {
        this(storageSettings, openChannel(accessLink, storageSettings));
    }

    BlobUploadChannel(StorageSettings storageSettings, BlobInfo objectMetadata, String transferId) {
        super(storageSettings, objectMetadata, transferId);
    }

    BlobUploadChannel(StorageSettings storageSettings, String transferId) {
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
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    protected UploadStateImpl.UploadBuilder stateBuilder() {
        return UploadStateImpl.newBuilder(getOptions(), getEntity(), getUploadId());
    }

    private static String openChannel(final StorageSettings storageSettings, final BlobInfo objectInfo, final Map<StorageRpcClient.StorageOption, ?> settingsByKey) {
        try {
            return runWithRetries(new Callable<String>() {

                @Override
                public String call() {
                    return storageSettings.getStorageRpcV1().open(objectInfo.toProto(), settingsByKey);
                }
            }, storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    private static String openChannel(final URL accessLink, final StorageSettings storageSettings) {
        try {
            return runWithRetries(new Callable<String>() {

                @Override
                public String call() {
                    if (!isValidSignedURL(accessLink.getQuery())) {
                        throw new StorageServiceException(2, "invalid signedURL");
                    }
                    return storageSettings.getStorageRpcV1().open(accessLink.toString());
                }
            }, storageSettings.getRetrySettings(), StorageServiceImpl.EXCEPTION_HANDLER, storageSettings.getClock());
        } catch (RetryHelper.RetryHelperException retryException) {
            throw StorageServiceException.translateAndRethrow(retryException);
        }
    }

    private static boolean isValidSignedURL(String queryString) {
        boolean validMatch = true;
        if (!queryString.startsWith("X-Goog-Algorithm=")) {
            if (!queryString.startsWith("GoogleAccessId=")) {
                validMatch = false;
            } else {
                if (!queryString.contains("&Expires=") || !queryString.contains("&Signature=")) {
                    validMatch = false;
                }
            }
        } else {
            if (!queryString.contains("&X-Goog-Credential=") || !queryString.contains("&X-Goog-Date=") || !queryString.contains("&X-Goog-Expires=") || !queryString.contains("&X-Goog-SignedHeaders=") || !queryString.contains("&X-Goog-Signature=")) {
                validMatch = false;
            }
        }
        return validMatch;
    }

    static class UploadStateImpl extends BaseWriteChannel.BaseState<StorageSettings, BlobInfo> {

        private static final long serialVersionUID = -9028324143780151286L;

        UploadStateImpl(UploadBuilder uploaderFactory) {
            super(uploaderFactory);
        }

        static class UploadBuilder extends BaseWriteChannel.BaseState.Builder<StorageSettings, BlobInfo> {

            private UploadBuilder(StorageSettings storageSettings, BlobInfo objectMetadata, String transferId) {
                super(storageSettings, objectMetadata, transferId);
            }

            @Override
            public RestorableState<WriteChannel> build() {
                return new UploadStateImpl(this);
            }
        }

        static UploadBuilder newBuilder(StorageSettings storageSettings, BlobInfo objectMetadata, String transferId) {
            return new UploadBuilder(storageSettings, objectMetadata, transferId);
        }

        @Override
        public WriteChannel restore() {
            BlobUploadChannel uploadStream = new BlobUploadChannel(serviceOptions, entity, uploadId);
            uploadStream.restore(this);
            return uploadStream;
        }
    }
}
