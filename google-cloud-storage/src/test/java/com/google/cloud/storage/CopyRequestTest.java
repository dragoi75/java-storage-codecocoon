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

import static com.google.cloud.storage.StorageClient.PredefinedAccessControlList.PUBLIC_READ;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.cloud.storage.StorageClient.BlobUploadOption;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class CopyRequestTest {

  private static final String SOURCE_BUCKET_NAME = "b0";
  private static final String SOURCE_BLOB_NAME = "o0";
  private static final String TARGET_BUCKET_NAME = "b1";
  private static final String TARGET_BLOB_NAME = "o1";
  private static final String TARGET_BLOB_CONTENT_TYPE = "contentType";
  private static final BlobIdentifier SOURCE_BLOB_ID = BlobIdentifier.create(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME);
  private static final BlobIdentifier TARGET_BLOB_ID = BlobIdentifier.create(TARGET_BUCKET_NAME, TARGET_BLOB_NAME);
  private static final BlobMetadata TARGET_BLOB_INFO =
      BlobMetadata.newBuilder(TARGET_BLOB_ID).setContentType(TARGET_BLOB_CONTENT_TYPE).buildStorageObject();

  @Test
  public void testCopyRequest() {
    StorageClient.CopyOperationRequest copyRequest1 =
        StorageClient.CopyOperationRequest.newCopyOperationBuilder()
            .setSource(SOURCE_BLOB_ID)
            .setSourceOptions(StorageClient.BlobSourceOptions.ifGenerationMatch(1))
            .setTarget(TARGET_BLOB_INFO, StorageClient.BlobUploadOption.withPredefinedAcl(PUBLIC_READ))
            .buildCopyOperation();
    assertEquals(SOURCE_BLOB_ID, copyRequest1.getSource());
    assertEquals(1, copyRequest1.getSourceOptions().size());
    assertEquals(StorageClient.BlobSourceOptions.ifGenerationMatch(1), copyRequest1.getSourceOptions().get(0));
    assertEquals(TARGET_BLOB_INFO, copyRequest1.getTarget());
    assertTrue(copyRequest1.getOverrideInfo());
    assertEquals(1, copyRequest1.getTargetOptions().size());
    assertEquals(
        StorageClient.BlobUploadOption.withPredefinedAcl(PUBLIC_READ), copyRequest1.getTargetOptions().get(0));

    StorageClient.CopyOperationRequest copyRequest2 =
        StorageClient.CopyOperationRequest.newCopyOperationBuilder()
            .setSource(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME)
            .setTarget(TARGET_BLOB_ID)
            .buildCopyOperation();
    assertEquals(SOURCE_BLOB_ID, copyRequest2.getSource());
    assertEquals(BlobMetadata.newBuilder(TARGET_BLOB_ID).buildStorageObject(), copyRequest2.getTarget());
    assertFalse(copyRequest2.getOverrideInfo());

    StorageClient.CopyOperationRequest copyRequest3 =
        StorageClient.CopyOperationRequest.newCopyOperationBuilder()
            .setSource(SOURCE_BLOB_ID)
            .setTarget(
                TARGET_BLOB_INFO, ImmutableList.of(BlobUploadOption.withPredefinedAcl(PUBLIC_READ)))
            .buildCopyOperation();
    assertEquals(SOURCE_BLOB_ID, copyRequest3.getSource());
    assertEquals(TARGET_BLOB_INFO, copyRequest3.getTarget());
    assertTrue(copyRequest3.getOverrideInfo());
    assertEquals(
        ImmutableList.of(StorageClient.BlobUploadOption.withPredefinedAcl(PUBLIC_READ)),
        copyRequest3.getTargetOptions());
  }

  @Test
  public void testCopyRequestOf() {
    StorageClient.CopyOperationRequest copyRequest1 = StorageClient.CopyOperationRequest.create(SOURCE_BLOB_ID, TARGET_BLOB_INFO);
    assertEquals(SOURCE_BLOB_ID, copyRequest1.getSource());
    assertEquals(TARGET_BLOB_INFO, copyRequest1.getTarget());
    assertTrue(copyRequest1.getOverrideInfo());

    StorageClient.CopyOperationRequest copyRequest2 = StorageClient.CopyOperationRequest.create(SOURCE_BLOB_ID, TARGET_BLOB_NAME);
    assertEquals(SOURCE_BLOB_ID, copyRequest2.getSource());
    assertEquals(
        BlobMetadata.newBuilder(BlobIdentifier.create(SOURCE_BUCKET_NAME, TARGET_BLOB_NAME)).buildStorageObject(),
        copyRequest2.getTarget());
    assertFalse(copyRequest2.getOverrideInfo());

    StorageClient.CopyOperationRequest copyRequest3 =
        StorageClient.CopyOperationRequest.create(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME, TARGET_BLOB_INFO);
    assertEquals(SOURCE_BLOB_ID, copyRequest3.getSource());
    assertEquals(TARGET_BLOB_INFO, copyRequest3.getTarget());
    assertTrue(copyRequest3.getOverrideInfo());

    StorageClient.CopyOperationRequest copyRequest4 =
        StorageClient.CopyOperationRequest.create(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME, TARGET_BLOB_NAME);
    assertEquals(SOURCE_BLOB_ID, copyRequest4.getSource());
    assertEquals(
        BlobMetadata.newBuilder(BlobIdentifier.create(SOURCE_BUCKET_NAME, TARGET_BLOB_NAME)).buildStorageObject(),
        copyRequest4.getTarget());
    assertFalse(copyRequest4.getOverrideInfo());

    StorageClient.CopyOperationRequest copyRequest5 = StorageClient.CopyOperationRequest.create(SOURCE_BLOB_ID, TARGET_BLOB_ID);
    assertEquals(SOURCE_BLOB_ID, copyRequest5.getSource());
    assertEquals(BlobMetadata.newBuilder(TARGET_BLOB_ID).buildStorageObject(), copyRequest5.getTarget());
    assertFalse(copyRequest5.getOverrideInfo());

    StorageClient.CopyOperationRequest copyRequest6 =
        StorageClient.CopyOperationRequest.create(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME, TARGET_BLOB_ID);
    assertEquals(SOURCE_BLOB_ID, copyRequest6.getSource());
    assertEquals(BlobMetadata.newBuilder(TARGET_BLOB_ID).buildStorageObject(), copyRequest6.getTarget());
    assertFalse(copyRequest6.getOverrideInfo());
  }
}
