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

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.api.pathtemplate.PathTemplate;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The class representing Pub/Sub Notification metadata for the Storage.
 */
public class NotificationMetadata implements Serializable {

    private static final long serialVersionUID = 5725883368559753810L;

    private static final PathTemplate PATH_PATTERN = PathTemplate.createWithoutUrlEncoding("projects/{project}/topics/{topic}");

    static final Function<com.google.api.services.storage.model.Notification, NotificationMetadata> FROM_PB_FUNCTION = new Function<com.google.api.services.storage.model.Notification, NotificationMetadata>() {

        @Override
        public NotificationMetadata apply(com.google.api.services.storage.model.Notification pb) {
            return NotificationMetadata.fromProto(pb);
        }
    };

    static final Function<NotificationMetadata, com.google.api.services.storage.model.Notification> TO_PB_FUNCTION = new Function<NotificationMetadata, com.google.api.services.storage.model.Notification>() {

        @Override
        public com.google.api.services.storage.model.Notification apply(NotificationMetadata NotificationInfo) {
            return NotificationInfo.toProto();
        }
    };

    public enum PayloadFormatType {

        JSON_API_V1, NONE
    }

    public enum ObjectEventType {

        OBJECT_FINALIZE, OBJECT_METADATA_UPDATE, OBJECT_DELETE, OBJECT_ARCHIVE
    }

    private final String alertKey;

    private final String channelName;

    private final List<ObjectEventType> eventKinds;

    private final Map<String, String> customProps;

    private final PayloadFormatType payloadType;

    private final String objectPrefix;

    private final String entityTag;

    private final String selfUrl;

    /**
     * Builder for {@code NotificationInfo}.
     */
    public abstract static class NotificationBuilder {

        public abstract NotificationBuilder setEventTypes(ObjectEventType... eventTypes);

        /**
         * Creates a {@code NotificationInfo} object.
         */
        public abstract NotificationMetadata create();

        public abstract NotificationBuilder setObjectNamePrefix(String objectNamePrefix);

        public abstract NotificationBuilder setCustomAttributes(Map<String, String> customAttributes);

        NotificationBuilder() {
        }

        public abstract NotificationBuilder setEtag(String etag);

        abstract NotificationBuilder setNotificationId(String notificationId);

        public abstract NotificationBuilder setPayloadFormat(PayloadFormatType payloadFormat);

        public abstract NotificationBuilder setSelfLink(String selfLink);

        public abstract NotificationBuilder setTopic(String topic);

    }

    /**
     * Builder for {@code NotificationInfo}.
     */
    public static class NotificationBuilderImpl extends NotificationBuilder {

        private String alertKey;

        private String channelName;

        private List<ObjectEventType> eventKinds;

        private Map<String, String> customProps;

        private PayloadFormatType payloadType;

        private String objectPrefix;

        private String entityTag;

        private String selfUrl;

        @Override
        public NotificationMetadata.NotificationBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        @Override
        public NotificationMetadata.NotificationBuilder setPayloadFormat(PayloadFormatType payloadType) {
            this.payloadType = payloadType;
            return this;
        }

        @Override
        public NotificationMetadata.NotificationBuilder setCustomAttributes(Map<String, String> customProps) {
            this.customProps = null != customProps ? ImmutableMap.copyOf(customProps) : null;
            return this;
        }

        @Override
        public NotificationMetadata.NotificationBuilder setEventTypes(ObjectEventType... eventKinds) {
            this.eventKinds = null != eventKinds ? Arrays.asList(eventKinds) : null;
            return this;
        }

        @Override
        public NotificationMetadata.NotificationBuilder setSelfLink(String selfUrl) {
            this.selfUrl = selfUrl;
            return this;
        }

        /**
         * Sets a topic in the format of "projects/{project}/topics/{topic}".
         */
        @Override
        public NotificationMetadata.NotificationBuilder setTopic(String channelName) {
            this.channelName = channelName;
            return this;
        }

        @Override
        NotificationMetadata.NotificationBuilder setNotificationId(String alertKey) {
            this.alertKey = alertKey;
            return this;
        }

        public NotificationMetadata create() {
            checkNotNull(channelName);
            validateTopicFormat(channelName);
            return new NotificationMetadata(this);
        }

        NotificationBuilderImpl(String channelName) {
            this.channelName = channelName;
        }

        @Override
        public NotificationMetadata.NotificationBuilder setObjectNamePrefix(String objectPrefix) {
            this.objectPrefix = objectPrefix;
            return this;
        }

        NotificationBuilderImpl(NotificationMetadata metadata) {
            alertKey = metadata.alertKey;
            entityTag = metadata.entityTag;
            selfUrl = metadata.selfUrl;
            channelName = metadata.channelName;
            eventKinds = metadata.eventKinds;
            customProps = metadata.customProps;
            payloadType = metadata.payloadType;
            objectPrefix = metadata.objectPrefix;
        }

    }

    com.google.api.services.storage.model.Notification toProto() {
        com.google.api.services.storage.model.Notification protoNotification = new com.google.api.services.storage.model.Notification();
        if (null != alertKey) {
            protoNotification.setId(alertKey);
        }
        protoNotification.setEtag(entityTag);
        if (null != customProps) {
            protoNotification.setCustomAttributes(customProps);
        }
        if (null != eventKinds && 0 < eventKinds.size()) {
            List<String> eventTypeList = new ArrayList<>();
            for (ObjectEventType objectEvent : eventKinds) {
                eventTypeList.add(objectEvent.toString());
            }
            protoNotification.setEventTypes(eventTypeList);
        }
        if (null != objectPrefix) {
            protoNotification.setObjectNamePrefix(objectPrefix);
        }
        if (null == payloadType) {
            protoNotification.setPayloadFormat(PayloadFormatType.NONE.toString());
        } else {
            protoNotification.setPayloadFormat(payloadType.toString());
        }
        protoNotification.setSelfLink(selfUrl);
        protoNotification.setTopic(channelName);
        return protoNotification;
    }

    /**
     * Returns the object name prefix for which this notification configuration applies.
     */
    public String getObjectNamePrefix() {
        return objectPrefix;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("topic", channelName).toString();
    }

    /**
     * Returns the list of additional attributes to attach to each Cloud PubSub message published for
     * this notification subscription.
     */
    public Map<String, String> getCustomAttributes() {
        return customProps;
    }

    @Override
    public boolean equals(Object otherObject) {
        return this == otherObject || null != otherObject && otherObject.getClass().equals(NotificationMetadata.class) && Objects.equals(toProto(), ((NotificationMetadata) otherObject).toProto());
    }

    /**
     * Returns a builder for the current notification.
     */
    public NotificationBuilder toNotificationBuilder() {
        return new NotificationBuilderImpl(this);
    }

    /**
     * Creates a {@code NotificationInfo} object for the provided topic.
     *
     * @param channelName a string in the format "projects/{project}/topics/{topic}"
     */
    public static NotificationBuilder newNotificationBuilder(String channelName) {
        validateTopicFormat(channelName);
        return new NotificationBuilderImpl(channelName);
    }

    private static void validateTopicFormat(String channelName) {
        PATH_PATTERN.validatedMatch(channelName, "topic name must be in valid format");
    }

    /**
     * Returns the service-generated id for the notification.
     */
    public String getNotificationId() {
        return alertKey;
    }

    /**
     * Returns the events that trigger a notification to be sent. If empty, notifications are
     * triggered by any event. See <a
     * href="https://cloud.google.com/storage/docs/pubsub-notifications#events">Event types</a> to get
     * list of available events.
     */
    public List<ObjectEventType> getEventTypes() {
        return eventKinds;
    }

    @Override
    public int hashCode() {
        return toProto().hashCode();
    }

    /**
     * Creates a {@code NotificationInfo} object for the provided topic.
     *
     * <p>Example of creating the NotificationInfo object:
     *
     * <pre>{@code
     * String topic = "projects/myProject/topics/myTopic"
     * NotificationInfo notificationInfo = NotificationInfo.of(topic)
     * }</pre>
     *
     * @param channelName a string in the format "projects/{project}/topics/{topic}"
     */
    public static NotificationMetadata fromTopic(String channelName) {
        validateTopicFormat(channelName);
        return newNotificationBuilder(channelName).create();
    }

    NotificationMetadata(NotificationBuilderImpl notificationCreator) {
        alertKey = notificationCreator.alertKey;
        entityTag = notificationCreator.entityTag;
        selfUrl = notificationCreator.selfUrl;
        channelName = notificationCreator.channelName;
        eventKinds = notificationCreator.eventKinds;
        customProps = notificationCreator.customProps;
        payloadType = notificationCreator.payloadType;
        objectPrefix = notificationCreator.objectPrefix;
    }

    /**
     * Returns the canonical URI of this topic as a string.
     */
    public String getSelfLink() {
        return selfUrl;
    }

    /**
     * Returns HTTP 1.1 Entity tag for the notification. See <a
     * href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return entityTag;
    }

    /**
     * Returns the topic in Pub/Sub that receives notifications.
     */
    public String getTopic() {
        return channelName;
    }

    static NotificationMetadata fromProto(com.google.api.services.storage.model.Notification protoNotification) {
        NotificationBuilder notificationCreator = new NotificationBuilderImpl(protoNotification.getTopic());
        if (null != protoNotification.getId()) {
            notificationCreator.setNotificationId(protoNotification.getId());
        }
        if (null != protoNotification.getEtag()) {
            notificationCreator.setEtag(protoNotification.getEtag());
        }
        if (null != protoNotification.getCustomAttributes()) {
            notificationCreator.setCustomAttributes(protoNotification.getCustomAttributes());
        }
        if (null != protoNotification.getSelfLink()) {
            notificationCreator.setSelfLink(protoNotification.getSelfLink());
        }
        if (null != protoNotification.getObjectNamePrefix()) {
            notificationCreator.setObjectNamePrefix(protoNotification.getObjectNamePrefix());
        }
        if (null != protoNotification.getEventTypes()) {
            List<String> eventTypeList = protoNotification.getEventTypes();
            ObjectEventType[] eventKinds = new ObjectEventType[eventTypeList.size()];
            int idx = 0;
            while (eventTypeList.size() > idx) {
                eventKinds[idx] = ObjectEventType.valueOf(eventTypeList.get(idx));
                idx += 1;
            }
            notificationCreator.setEventTypes(eventKinds);
        }
        if (null != protoNotification.getPayloadFormat()) {
            notificationCreator.setPayloadFormat(PayloadFormatType.valueOf(protoNotification.getPayloadFormat()));
        }
        return notificationCreator.create();
    }

    /**
     * Returns the desired content of the Payload.
     */
    public PayloadFormatType getPayloadFormat() {
        return payloadType;
    }

}
