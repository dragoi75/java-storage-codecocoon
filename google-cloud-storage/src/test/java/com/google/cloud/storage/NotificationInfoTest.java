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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.cloud.storage.NotificationMetadata.PayloadFormatType;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;

public class NotificationInfoTest {
  private static final String ETAG = "0xFF00";
  private static final String SELF_LINK = "http://storage/b/n";
  private static final String OBJECT_NAME_PREFIX = "index.html";
  private static final String TOPIC = "projects/myProject/topics/topic1";
  private static final Map<String, String> CUSTOM_ATTRIBUTES = ImmutableMap.of("label1", "value1");
  private static final PayloadFormatType PAYLOAD_FORMAT = PayloadFormatType.JSON_API_V1.JSON_API_V1;
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

  @Test
  public void testToBuilder() {
    compareBucketsNotification(NOTIFICATION_INFO, NOTIFICATION_INFO.toNotificationBuilder().create());
    NotificationMetadata notificationInfo = NOTIFICATION_INFO.toNotificationBuilder().setTopic(TOPIC).create();
    assertEquals(TOPIC, notificationInfo.getTopic());
    notificationInfo = notificationInfo.toNotificationBuilder().setTopic(TOPIC).create();
    compareBucketsNotification(NOTIFICATION_INFO, notificationInfo);
  }

  @Test
  public void testToBuilderIncomplete() {
    NotificationMetadata incompleteNotificationInfo = StorageNotification.newNotificationBuilder(TOPIC).create();
    compareBucketsNotification(
        incompleteNotificationInfo, incompleteNotificationInfo.toNotificationBuilder().create());
  }

  @Test
  public void testOf() {
    NotificationMetadata notificationInfo = NotificationMetadata.fromTopic(TOPIC);
    assertEquals(TOPIC, notificationInfo.getTopic());
    assertNull(notificationInfo.getNotificationId());
    assertNull(notificationInfo.getCustomAttributes());
    assertNull(notificationInfo.getEtag());
    assertNull(notificationInfo.getSelfLink());
    assertNull(notificationInfo.getEventTypes());
    assertNull(notificationInfo.getObjectNamePrefix());
    assertNull(notificationInfo.getPayloadFormat());
  }

  @Test
  public void testBuilder() {
    assertEquals(ETAG, NOTIFICATION_INFO.getEtag());
    assertNull(NOTIFICATION_INFO.getNotificationId());
    assertEquals(SELF_LINK, NOTIFICATION_INFO.getSelfLink());
    assertEquals(OBJECT_NAME_PREFIX, NOTIFICATION_INFO.getObjectNamePrefix());
    assertEquals(PAYLOAD_FORMAT, NOTIFICATION_INFO.getPayloadFormat());
    assertEquals(TOPIC, NOTIFICATION_INFO.getTopic());
    assertEquals(CUSTOM_ATTRIBUTES, NOTIFICATION_INFO.getCustomAttributes());
    assertEquals(Arrays.asList(EVENT_TYPES), NOTIFICATION_INFO.getEventTypes());
  }

  @Test
  public void testToPbAndFromPb() {
    compareBucketsNotification(
        NOTIFICATION_INFO, NotificationMetadata.fromProto(NOTIFICATION_INFO.toProto()));
    NotificationMetadata notificationInfo =
        NotificationMetadata.fromTopic(TOPIC).toNotificationBuilder().setPayloadFormat(PayloadFormatType.NONE).create();
    compareBucketsNotification(notificationInfo, StorageNotification.fromProto(notificationInfo.toProto()));
  }

  private void compareBucketsNotification(NotificationMetadata expected, NotificationMetadata actual) {
    assertEquals(expected, actual);
    assertEquals(expected.getNotificationId(), actual.getNotificationId());
    assertEquals(expected.getCustomAttributes(), actual.getCustomAttributes());
    assertEquals(expected.getEtag(), actual.getEtag());
    assertEquals(expected.getSelfLink(), actual.getSelfLink());
    assertEquals(expected.getEventTypes(), actual.getEventTypes());
    assertEquals(expected.getObjectNamePrefix(), actual.getObjectNamePrefix());
    assertEquals(expected.getPayloadFormat(), actual.getPayloadFormat());
    assertEquals(expected.getTopic(), actual.getTopic());
  }
}
