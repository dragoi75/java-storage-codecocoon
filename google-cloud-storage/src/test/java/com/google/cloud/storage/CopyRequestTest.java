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
  private static final BlobAttributes TARGET_BLOB_INFO =
      BlobAttributes.newBuilder(TARGET_BLOB_ID).setContentType(TARGET_BLOB_CONTENT_TYPE).buildObject();

  @Test
  public void testCopyRequest() {
    StorageClient.DataCopyRequest copyRequest1 =
        StorageClient.DataCopyRequest.builder()
            .setSource(SOURCE_BLOB_ID)
            .setSourceOptions(StorageClient.BlobSourceOptions.ifGenerationMatch(1))
            .setTarget(TARGET_BLOB_INFO, BlobUploadOption.withPredefinedAcl(PUBLIC_READ))
            .buildCopyRequest();
    assertEquals(SOURCE_BLOB_ID, copyRequest1.getSource());
    assertEquals(1, copyRequest1.getSourceOptions().size());
    assertEquals(StorageClient.BlobSourceOptions.ifGenerationMatch(1), copyRequest1.getSourceOptions().get(0));
    assertEquals(TARGET_BLOB_INFO, copyRequest1.getTarget());
    assertTrue(copyRequest1.getOverrideInfo());
    assertEquals(1, copyRequest1.getTargetOptions().size());
    assertEquals(
        StorageClient.BlobUploadOption.withPredefinedAcl(PUBLIC_READ), copyRequest1.getTargetOptions().get(0));

    StorageClient.DataCopyRequest copyRequest2 =
        StorageClient.DataCopyRequest.builder()
            .setSource(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME)
            .setTarget(TARGET_BLOB_ID)
            .buildCopyRequest();
    assertEquals(SOURCE_BLOB_ID, copyRequest2.getSource());
    assertEquals(BlobAttributes.newBuilder(TARGET_BLOB_ID).buildObject(), copyRequest2.getTarget());
    assertFalse(copyRequest2.getOverrideInfo());

    StorageClient.DataCopyRequest copyRequest3 =
        StorageClient.DataCopyRequest.builder()
            .setSource(SOURCE_BLOB_ID)
            .setTarget(
                TARGET_BLOB_INFO, ImmutableList.of(StorageClient.BlobUploadOption.withPredefinedAcl(PUBLIC_READ)))
            .buildCopyRequest();
    assertEquals(SOURCE_BLOB_ID, copyRequest3.getSource());
    assertEquals(TARGET_BLOB_INFO, copyRequest3.getTarget());
    assertTrue(copyRequest3.getOverrideInfo());
    assertEquals(
        ImmutableList.of(StorageClient.BlobUploadOption.withPredefinedAcl(PUBLIC_READ)),
        copyRequest3.getTargetOptions());
  }

  @Test
  public void testCopyRequestOf() {
    StorageClient.DataCopyRequest copyRequest1 = StorageClient.DataCopyRequest.from(SOURCE_BLOB_ID, TARGET_BLOB_INFO);
    assertEquals(SOURCE_BLOB_ID, copyRequest1.getSource());
    assertEquals(TARGET_BLOB_INFO, copyRequest1.getTarget());
    assertTrue(copyRequest1.getOverrideInfo());

    StorageClient.DataCopyRequest copyRequest2 = StorageClient.DataCopyRequest.from(SOURCE_BLOB_ID, TARGET_BLOB_NAME);
    assertEquals(SOURCE_BLOB_ID, copyRequest2.getSource());
    assertEquals(
        BlobAttributes.newBuilder(BlobIdentifier.create(SOURCE_BUCKET_NAME, TARGET_BLOB_NAME)).buildObject(),
        copyRequest2.getTarget());
    assertFalse(copyRequest2.getOverrideInfo());

    StorageClient.DataCopyRequest copyRequest3 =
        StorageClient.DataCopyRequest.from(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME, TARGET_BLOB_INFO);
    assertEquals(SOURCE_BLOB_ID, copyRequest3.getSource());
    assertEquals(TARGET_BLOB_INFO, copyRequest3.getTarget());
    assertTrue(copyRequest3.getOverrideInfo());

    StorageClient.DataCopyRequest copyRequest4 =
        StorageClient.DataCopyRequest.from(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME, TARGET_BLOB_NAME);
    assertEquals(SOURCE_BLOB_ID, copyRequest4.getSource());
    assertEquals(
        BlobAttributes.newBuilder(BlobIdentifier.create(SOURCE_BUCKET_NAME, TARGET_BLOB_NAME)).buildObject(),
        copyRequest4.getTarget());
    assertFalse(copyRequest4.getOverrideInfo());

    StorageClient.DataCopyRequest copyRequest5 = StorageClient.DataCopyRequest.from(SOURCE_BLOB_ID, TARGET_BLOB_ID);
    assertEquals(SOURCE_BLOB_ID, copyRequest5.getSource());
    assertEquals(BlobAttributes.newBuilder(TARGET_BLOB_ID).buildObject(), copyRequest5.getTarget());
    assertFalse(copyRequest5.getOverrideInfo());

    StorageClient.DataCopyRequest copyRequest6 =
        StorageClient.DataCopyRequest.from(SOURCE_BUCKET_NAME, SOURCE_BLOB_NAME, TARGET_BLOB_ID);
    assertEquals(SOURCE_BLOB_ID, copyRequest6.getSource());
    assertEquals(BlobAttributes.newBuilder(TARGET_BLOB_ID).buildObject(), copyRequest6.getTarget());
    assertFalse(copyRequest6.getOverrideInfo());
  }
}
