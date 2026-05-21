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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Presigned V4 post policy.
 *
 * @see <a href="https://cloud.google.com/storage/docs/xml-api/post-object">POST Object</a>
 */
public final class PostPolicyVersion4 {

    private final String endpoint;

    private final Map<String, String> formParams;

    private PostPolicyVersion4(String endpoint, Map<String, String> formParams) {
        this.endpoint = endpoint;
        this.formParams = formParams;
    }

    public static PostPolicyVersion4 create(String endpoint, Map<String, String> formParams) {
        return new PostPolicyVersion4(endpoint, formParams);
    }

    public String getUrl() {
        return endpoint;
    }

    public Map<String, String> getFields() {
        return formParams;
    }

    /**
     * Class representing which fields to specify in a V4 POST request.
     *
     * @see <a href="https://cloud.google.com/storage/docs/xml-api/post-object#form_fields">POST
     *     Object Form fields</a>
     */
    public static final class PostFieldsVersion4 {

        private final Map<String, String> metadataEntries;

        private PostFieldsVersion4(ObjectMetadataBuilder metadataCreator) {
            this.metadataEntries = metadataCreator.metadataEntries;
        }

        private PostFieldsVersion4(Map<String, String> formParams) {
            this.metadataEntries = formParams;
        }

        public static PostFieldsVersion4 create(Map<String, String> formParams) {
            return new PostFieldsVersion4(formParams);
        }

        public static ObjectMetadataBuilder builder() {
            return new ObjectMetadataBuilder();
        }

        public Map<String, String> getFieldsMap() {
            return metadataEntries;
        }

        public static class ObjectMetadataBuilder {

            private static final String CUSTOM_METADATA_PREFIX = "x-goog-meta-";

            private final Map<String, String> metadataEntries;

            private ObjectMetadataBuilder() {
                this.metadataEntries = new HashMap<>();
            }

            public PostFieldsVersion4 create() {
                return new PostFieldsVersion4(this);
            }

            public ObjectMetadataBuilder setAcl(String accessControl) {
                metadataEntries.put("acl", accessControl);
                return this;
            }

            public ObjectMetadataBuilder setCacheControl(String cacheDirective) {
                metadataEntries.put("cache-control", cacheDirective);
                return this;
            }

            public ObjectMetadataBuilder setContentDisposition(String dispositionHeader) {
                metadataEntries.put("content-disposition", dispositionHeader);
                return this;
            }

            public ObjectMetadataBuilder setContentEncoding(String encodingHeader) {
                metadataEntries.put("content-encoding", encodingHeader);
                return this;
            }

            public ObjectMetadataBuilder setContentLength(int lengthBytes) {
                metadataEntries.put("content-length", "" + lengthBytes);
                return this;
            }

            public ObjectMetadataBuilder setContentType(String mimeType) {
                metadataEntries.put("content-type", mimeType);
                return this;
            }

            /**
             * @deprecated use {@link #setExpires(String)}
             */
            @Deprecated
            public PostPolicyVersion4.PostFieldsVersion4.ObjectMetadataBuilder Expires(String expiryDate) {
                return setExpires(expiryDate);
            }

            public ObjectMetadataBuilder setExpires(String expiryDate) {
                metadataEntries.put("expires", expiryDate);
                return this;
            }

            public ObjectMetadataBuilder setSuccessActionRedirect(String successRedirect) {
                metadataEntries.put("success_action_redirect", successRedirect);
                return this;
            }

            public ObjectMetadataBuilder setSuccessActionStatus(int successStatusCode) {
                metadataEntries.put("success_action_status", "" + successStatusCode);
                return this;
            }

            /**
             * @deprecated use {@link #setCustomMetadataField(String, String)}
             */
            @Deprecated
            public PostPolicyVersion4.PostFieldsVersion4.ObjectMetadataBuilder AddCustomMetadataField(String metadataKey, String metadataContent) {
                return setCustomMetadataField(metadataKey, metadataContent);
            }

            public ObjectMetadataBuilder setCustomMetadataField(String metadataKey, String metadataContent) {
                if (!metadataKey.startsWith(CUSTOM_METADATA_PREFIX)) {
                    metadataKey = CUSTOM_METADATA_PREFIX + metadataContent;
                }
                metadataEntries.put(metadataKey, metadataContent);
                return this;
            }
        }
    }

    /**
     * Class for specifying conditions in a V4 POST Policy document.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
     *     Policy document</a>
     */
    public static final class PostConditionsVersion4 {

        private Set<BinaryCondition> conditionSet;

        private static SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

        public PostConditionsVersion4(ConditionsBuilder metadataCreator) {
            this.conditionSet = metadataCreator.conditionSet;
        }

        public ConditionsBuilder asBuilder() {
            return new ConditionsBuilder(conditionSet);
        }

        public static ConditionsBuilder builder() {
            return new ConditionsBuilder();
        }

        public Set<BinaryCondition> getConditions() {
            return conditionSet;
        }

        public static class ConditionsBuilder {

            Set<BinaryCondition> conditionSet;

            private ConditionsBuilder() {
                this.conditionSet = new LinkedHashSet<>();
            }

            private ConditionsBuilder(Set<BinaryCondition> conditionSet) {
                this.conditionSet = conditionSet;
            }

            public static ConditionsBuilder newBuilder() {
                return new ConditionsBuilder();
            }

            public PostConditionsVersion4 create() {
                return new PostConditionsVersion4(this);
            }

            public ConditionsBuilder addAclCondition(ConditionTypeV4 conditionKind, String accessControl) {
                conditionSet.add(new BinaryCondition(conditionKind, "acl", accessControl));
                return this;
            }

            public ConditionsBuilder addBucket(ConditionTypeV4 conditionKind, String storageContainer) {
                conditionSet.add(new BinaryCondition(conditionKind, "bucket", storageContainer));
                return this;
            }

            public ConditionsBuilder addCacheControlCondition(ConditionTypeV4 conditionKind, String cacheDirective) {
                conditionSet.add(new BinaryCondition(conditionKind, "cache-control", cacheDirective));
                return this;
            }

            public ConditionsBuilder addContentDispositionCondition(ConditionTypeV4 conditionKind, String dispositionHeader) {
                conditionSet.add(new BinaryCondition(conditionKind, "content-disposition", dispositionHeader));
                return this;
            }

            public ConditionsBuilder addContentEncodingCondition(ConditionTypeV4 conditionKind, String encodingHeader) {
                conditionSet.add(new BinaryCondition(conditionKind, "content-encoding", encodingHeader));
                return this;
            }

            public ConditionsBuilder addContentLengthCondition(ConditionTypeV4 conditionKind, int lengthBytes) {
                conditionSet.add(new BinaryCondition(conditionKind, "content-length", "" + lengthBytes));
                return this;
            }

            public ConditionsBuilder addContentType(ConditionTypeV4 conditionKind, String mimeType) {
                conditionSet.add(new BinaryCondition(conditionKind, "content-type", mimeType));
                return this;
            }

            public ConditionsBuilder addExpiresCondition(ConditionTypeV4 conditionKind, long expiryDate) {
                conditionSet.add(new BinaryCondition(conditionKind, "expires", timestampFormat.format(expiryDate)));
                return this;
            }

            public ConditionsBuilder addExpiresCondition(ConditionTypeV4 conditionKind, String expiryDate) {
                conditionSet.add(new BinaryCondition(conditionKind, "expires", expiryDate));
                return this;
            }

            public ConditionsBuilder addKey(ConditionTypeV4 conditionKind, String objectKey) {
                conditionSet.add(new BinaryCondition(conditionKind, "key", objectKey));
                return this;
            }

            public ConditionsBuilder addSuccessActionRedirect(ConditionTypeV4 conditionKind, String redirectUrl) {
                conditionSet.add(new BinaryCondition(conditionKind, "success_action_redirect", redirectUrl));
                return this;
            }

            public ConditionsBuilder addSuccessStatus(ConditionTypeV4 conditionKind, int httpStatus) {
                conditionSet.add(new BinaryCondition(conditionKind, "success_action_status", "" + httpStatus));
                return this;
            }

            public ConditionsBuilder addContentLengthRange(int minLength, int maxLength) {
                conditionSet.add(new BinaryCondition(ConditionTypeV4.CONTENT_LENGTH_RANGE, "" + minLength, "" + maxLength));
                return this;
            }

            ConditionsBuilder addCondition(ConditionTypeV4 conditionKind, String metadataKey, String metadataContent) {
                conditionSet.add(new BinaryCondition(conditionKind, metadataKey, metadataContent));
                return this;
            }
        }
    }

    /**
     * Class for a V4 POST Policy document.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
     *     Policy document</a>
     */
    public static final class PostPolicyV4Payload {

        private final String expirationDate;

        private final PostConditionsVersion4 conditionSet;

        private PostPolicyV4Payload(String expirationDate, PostConditionsVersion4 conditionSet) {
            this.expirationDate = expirationDate;
            this.conditionSet = conditionSet;
        }

        public static PostPolicyV4Payload create(String expirationDate, PostConditionsVersion4 conditionSet) {
            return new PostPolicyV4Payload(expirationDate, conditionSet);
        }

        public String toJsonString() {
            JsonObject jsonObject = new JsonObject();
            JsonArray conditionSet = new JsonArray();
            for (BinaryCondition binaryCondition : this.conditionSet.conditionSet) {
                switch(binaryCondition.conditionKind) {
                    case MATCHES:
                        JsonObject matchObject = new JsonObject();
                        matchObject.addProperty(binaryCondition.leftOperand, binaryCondition.rightOperand);
                        conditionSet.add(matchObject);
                        break;
                    case STARTS_WITH:
                        JsonArray startsWithArray = new JsonArray();
                        startsWithArray.add("starts-with");
                        startsWithArray.add("$" + binaryCondition.leftOperand);
                        startsWithArray.add(binaryCondition.rightOperand);
                        conditionSet.add(startsWithArray);
                        break;
                    case CONTENT_LENGTH_RANGE:
                        JsonArray lengthRangeArray = new JsonArray();
                        lengthRangeArray.add("content-length-range");
                        lengthRangeArray.add(Integer.parseInt(binaryCondition.leftOperand));
                        lengthRangeArray.add(Integer.parseInt(binaryCondition.rightOperand));
                        conditionSet.add(lengthRangeArray);
                        break;
                }
            }
            jsonObject.add("conditions", conditionSet);
            jsonObject.addProperty("expiration", expirationDate);
            String jsonString = jsonObject.toString();
            StringBuilder escapedJsonBuilder = new StringBuilder();
            // Certain characters in a policy must be escaped
            for (char ch : jsonString.toCharArray()) {
                if (128 > ch) {
                    switch(ch) {
                        case '\\':
                            escapedJsonBuilder.append("\\\\");
                            break;
                        case '\b':
                            escapedJsonBuilder.append("\\b");
                            break;
                        case '\f':
                            escapedJsonBuilder.append("\\f");
                            break;
                        case '\n':
                            escapedJsonBuilder.append("\\n");
                            break;
                        case '\r':
                            escapedJsonBuilder.append("\\r");
                            break;
                        case '\t':
                            escapedJsonBuilder.append("\\t");
                            break;
                        case '\u000b':
                            escapedJsonBuilder.append("\\v");
                            break;
                        default:
                            escapedJsonBuilder.append(ch);
                    }
                } else {
                    // is a unicode character
                    escapedJsonBuilder.append(String.format("\\u%04x", (int) ch));
                }
            }
            return escapedJsonBuilder.toString();
        }
    }

    public enum ConditionTypeV4 {

        MATCHES, STARTS_WITH, CONTENT_LENGTH_RANGE
    }

    /**
     * Class for a specific POST policy document condition.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
     *     Policy document</a>
     */
    static final class BinaryCondition {

        final ConditionTypeV4 conditionKind;

        final String leftOperand;

        final String rightOperand;

        private BinaryCondition(ConditionTypeV4 conditionKind, String leftOperand, String rightOperand) {
            this.conditionKind = conditionKind;
            this.leftOperand = leftOperand;
            this.rightOperand = rightOperand;
        }

        @Override
        public boolean equals(Object obj) {
            BinaryCondition binaryCondition = (BinaryCondition) obj;
            return binaryCondition.conditionKind == this.conditionKind && this.leftOperand.equals(binaryCondition.leftOperand) && this.rightOperand.equals(binaryCondition.rightOperand);
        }

        @Override
        public int hashCode() {
            return Objects.hash(conditionKind, leftOperand, rightOperand);
        }
    }
}
