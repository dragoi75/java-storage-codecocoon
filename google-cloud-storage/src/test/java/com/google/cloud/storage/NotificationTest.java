/*
 * Copyright 2020 Google LLC
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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.google.cloud.storage.NotificationMetadata.PayloadFormatType;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NotificationTest {

  private static final String ETAG = "0xFF00";
  private static final String SELF_LINK = "http://storage/b/n";
  private static final String OBJECT_NAME_PREFIX = "index.html";
  private static final String TOPIC = "projects/myProject/topics/topic1";
  private static final Map<String, String> CUSTOM_ATTRIBUTES = ImmutableMap.of("label1", "value1");
  private static final NotificationMetadata.PayloadFormatType PAYLOAD_FORMAT = PayloadFormatType.JSON_API_V1.JSON_API_V1;
  private static final NotificationMetadata.ObjectEventType[] EVENT_TYPES = {
    NotificationMetadata.ObjectEventType.OBJECT_FINALIZE, NotificationMetadata.ObjectEventType.OBJECT_METADATA_UPDATE
  };
  private static final NotificationMetadata NOTIFICATION_INFO =
      NotificationMetadata.newNotificationBuilder(TOPIC)
          .setEtag(ETAG)
          .setCustomAttributes(CUSTOM_ATTRIBUTES)
          .setSelfLink(SELF_LINK)
          .setEventTypes(EVENT_TYPES)
          .setObjectNamePrefix(OBJECT_NAME_PREFIX)
          .setPayloadFormat(PAYLOAD_FORMAT)
          .create();

  private Storage storage;
  private StorageClientOptions mockOptions = createMock(StorageClientOptions.class);

  @Before
  public void setUp() {
    storage = createStrictMock(Storage.class);
  }

  @After
  public void tearDown() {
    verify(storage);
  }

  @Test
  public void testBuilder() {
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    replay(storage);
    StorageNotification.NotificationInfoBuilder builder =
        new StorageNotification.NotificationInfoBuilder(
            new StorageNotification(storage, new NotificationMetadata.NotificationBuilderImpl(NOTIFICATION_INFO)));
    StorageNotification notification =
        builder
            .setEtag(ETAG)
            .setCustomAttributes(CUSTOM_ATTRIBUTES)
            .setSelfLink(SELF_LINK)
            .setEventTypes(EVENT_TYPES)
            .setObjectNamePrefix(OBJECT_NAME_PREFIX)
            .setPayloadFormat(PAYLOAD_FORMAT)
            .create();
    assertEquals(ETAG, notification.getEtag());
    assertEquals(SELF_LINK, notification.getSelfLink());
    assertEquals(OBJECT_NAME_PREFIX, notification.getObjectNamePrefix());
    assertEquals(PAYLOAD_FORMAT, notification.getPayloadFormat());
    assertEquals(TOPIC, notification.getTopic());
    assertEquals(CUSTOM_ATTRIBUTES, notification.getCustomAttributes());
    assertEquals(Arrays.asList(EVENT_TYPES), notification.getEventTypes());
  }

  @Test
  public void testToBuilder() {
    expect(storage.getOptions()).andReturn(mockOptions).times(2);
    replay(storage);
    StorageNotification notification =
        new StorageNotification(storage, new NotificationMetadata.NotificationBuilderImpl(NOTIFICATION_INFO));
    compareBucketNotification(notification, notification.toNotificationBuilder().create());
  }

  @Test
  public void testFromPb() {
    expect(storage.getOptions()).andReturn(mockOptions).times(1);
    replay(storage);
    compareBucketNotification(
        NOTIFICATION_INFO, StorageNotification.fromProto(storage, NOTIFICATION_INFO.toProto()));
  }

  private void compareBucketNotification(NotificationMetadata expected, NotificationMetadata actual) {
    assertEquals(expected.getNotificationId(), actual.getNotificationId());
    assertEquals(expected.getCustomAttributes(), actual.getCustomAttributes());
    assertEquals(expected.getEtag(), actual.getEtag());
    assertEquals(expected.getSelfLink(), actual.getSelfLink());
    assertEquals(expected.getEventTypes(), actual.getEventTypes());
    assertEquals(expected.getObjectNamePrefix(), actual.getObjectNamePrefix());
    assertEquals(expected.getPayloadFormat(), actual.getPayloadFormat());
    assertEquals(expected.getTopic().trim(), actual.getTopic().trim());
  }
}
