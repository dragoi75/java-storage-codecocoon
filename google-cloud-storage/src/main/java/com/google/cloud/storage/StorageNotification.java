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
import java.util.Map;
import java.util.Objects;

/**
 * The class representing Pub/Sub notifications for the Storage. See <a
 * href="https://cloud.google.com/storage/docs/pubsub-notifications">pubsub-notifications</a> for
 * details.
 */
public class StorageNotification extends NotificationMetadata {

    private final StorageClientOptions clientConfig;

    private transient Storage client;

    /**
     * Builder for {@code Notification}.
     */
    public static class NotificationInfoBuilder extends NotificationBuilder {

        private final Storage client;

        private final NotificationBuilderImpl builder;

        @Override
        public StorageNotification.NotificationInfoBuilder setEventTypes(ObjectEventType... events) {
            builder.setEventTypes(events);
            return this;
        }

        @Override
        public StorageNotification create() {
            return new StorageNotification(client, builder);
        }

        @Override
        public StorageNotification.NotificationInfoBuilder setCustomAttributes(Map<String, String> attributes) {
            builder.setCustomAttributes(attributes);
            return this;
        }

        @Override
        public StorageNotification.NotificationInfoBuilder setPayloadFormat(PayloadFormatType formatType) {
            builder.setPayloadFormat(formatType);
            return this;
        }

        @Override
        public StorageNotification.NotificationInfoBuilder setSelfLink(String resourceUri) {
            builder.setSelfLink(resourceUri);
            return this;
        }

        @Override
        public StorageNotification.NotificationInfoBuilder setObjectNamePrefix(String namePrefix) {
            builder.setObjectNamePrefix(namePrefix);
            return this;
        }

        @Override
        StorageNotification.NotificationInfoBuilder setNotificationId(String id) {
            builder.setNotificationId(id);
            return this;
        }

        @Override
        public StorageNotification.NotificationInfoBuilder setEtag(String entityTag) {
            builder.setEtag(entityTag);
            return this;
        }

        NotificationInfoBuilder(StorageNotification notif) {
            this.client = notif.client;
            this.builder = new NotificationBuilderImpl(notif);
        }

        @Override
        public StorageNotification.NotificationInfoBuilder setTopic(String subject) {
            builder.setTopic(subject);
            return this;
        }

    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (null == obj || obj.getClass() != getClass()) {
            return false;
        }
        if (!super.equals(obj)) {
            return false;
        }
        StorageNotification notif = (StorageNotification) obj;
        return Objects.equals(toProto(), notif.toProto()) && Objects.equals(clientConfig, notif.clientConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), clientConfig, client);
    }

    @Override
    public StorageNotification.NotificationInfoBuilder toNotificationBuilder() {
        return new NotificationInfoBuilder(this);
    }

    static StorageNotification fromProto(Storage client, com.google.api.services.storage.model.Notification protoMsg) {
        return new StorageNotification(client, new NotificationBuilderImpl(NotificationMetadata.fromProto(protoMsg)));
    }

    StorageNotification(Storage client, NotificationBuilderImpl builder) {
        super(builder);
        this.client = checkNotNull(client);
        this.clientConfig = client.getOptions();
    }

    /**
     * Returns the notification's {@code Storage} object used to issue requests.
     */
    public Storage getStorage() {
        return client;
    }

}
