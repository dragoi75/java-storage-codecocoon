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
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Presigned V4 post policy. Instances of {@code PostPolicyV4} include a URL and a map of fields
 * that can be specified in an HTML form to submit a POST request to upload an object.
 *
 * <p>See <a href="https://cloud.google.com/storage/docs/xml-api/post-object">POST Object</a> for
 * details of upload by using HTML forms.
 *
 * <p>See {@link CloudStorageClient#generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit,
 * PostFieldsMapV4, PostConditionsVersion4, CloudStorageClient.PostPolicyV4FormField...)} for
 * example of usage.
 */
public final class S3PostPolicyV4 {

    private final String endpoint;

    private final Map<String, String> formParams;

    /**
     * A helper class to define fields to be specified in a V4 POST request. Instance of this class
     * helps to construct {@code PostPolicyV4} objects. Used in: {@link
     * CloudStorageClient#generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit, PostFieldsMapV4,
     * PostConditionsVersion4, CloudStorageClient.PostPolicyV4FormField...)}.
     *
     * @see <a href="https://cloud.google.com/storage/docs/xml-api/post-object#form_fields">POST
     *     Object Form fields</a>
     */
    public static final class PostFieldsMapV4 {

        private final Map<String, String> formDataMap;

        private static final List<String> ALLOWED_FORM_KEYS = Arrays.asList("acl", "bucket", "cache-control", "content-disposition", "content-encoding", "content-type", "expires", "file", "key", "policy", "success_action_redirect", "success_action_status", "x-goog-algorithm", "x-goog-credential", "x-goog-date", "x-goog-signature");

        public static class UploadFormBuilder {

            private static final String METADATA_PREFIX = "x-goog-meta-";

            private final Map<String, String> formDataMap;

            public UploadFormBuilder setExpires(String expiry) {
                formDataMap.put("expires", expiry);
                return this;
            }

            /**
             * @deprecated Invocation of this method has no effect, because all valid HTML form fields
             *     except Content-Length can use exact matching. Use {@link
             *     PostConditionsVersion4.PolicyBuilder#addContentLengthRange(int, int)} to
             *     specify a range for the content-length.
             */
            @Deprecated
            public S3PostPolicyV4.PostFieldsMapV4.UploadFormBuilder setContentLength(int contentLength) {
                return this;
            }

            /**
             * @deprecated Use {@link #setCustomMetadataField(String, String)}.
             */
            @Deprecated
            public S3PostPolicyV4.PostFieldsMapV4.UploadFormBuilder AddCustomMetadataField(String metadataName, String metadataValue) {
                return setCustomMetadataField(metadataName, metadataValue);
            }

            public UploadFormBuilder setContentType(String mimeType) {
                formDataMap.put("content-type", mimeType);
                return this;
            }

            /**
             * @deprecated Use {@link #setExpires(String)}.
             */
            @Deprecated
            public S3PostPolicyV4.PostFieldsMapV4.UploadFormBuilder Expires(String expiry) {
                return setExpires(expiry);
            }

            public UploadFormBuilder setSuccessActionStatus(int successStatusCode) {
                formDataMap.put("success_action_status", "" + successStatusCode);
                return this;
            }

            public UploadFormBuilder setCustomMetadataField(String metadataName, String metadataValue) {
                if (!metadataName.startsWith(METADATA_PREFIX)) {
                    metadataName = METADATA_PREFIX + metadataName;
                }
                formDataMap.put(metadataName, metadataValue);
                return this;
            }

            private UploadFormBuilder() {
                this.formDataMap = new HashMap<>();
            }

            public PostFieldsMapV4 buildMap() {
                return new PostFieldsMapV4(this);
            }

            public UploadFormBuilder setAcl(String accessControl) {
                formDataMap.put("acl", accessControl);
                return this;
            }

            public UploadFormBuilder setSuccessActionRedirect(String redirectOnSuccess) {
                formDataMap.put("success_action_redirect", redirectOnSuccess);
                return this;
            }

            public UploadFormBuilder setContentEncoding(String encoding) {
                formDataMap.put("content-encoding", encoding);
                return this;
            }

            public UploadFormBuilder setCacheControl(String cacheDirective) {
                formDataMap.put("cache-control", cacheDirective);
                return this;
            }

            public UploadFormBuilder setContentDisposition(String disposition) {
                formDataMap.put("content-disposition", disposition);
                return this;
            }

        }

        public static UploadFormBuilder createBuilder() {
            return new UploadFormBuilder();
        }

        /**
         * Constructs {@code PostPolicyV4.PostFieldsV4} object of the given field map.
         *
         * @param formParams a map of the HTML form fields
         * @return constructed object
         * @throws IllegalArgumentException if an unsupported field is specified
         */
        public static PostFieldsMapV4 from(Map<String, String> formParams) {
            return new PostFieldsMapV4(formParams);
        }

        private PostFieldsMapV4(UploadFormBuilder formBuilder) {
            this(formBuilder.formDataMap);
        }

        public Map<String, String> getFieldsMap() {
            return formDataMap;
        }

        private static void validate(Map<String, String> formParams) {
            for (String fieldName : formParams.keySet()) {
                if (!ALLOWED_FORM_KEYS.contains(fieldName.toLowerCase()) && !fieldName.startsWith(UploadFormBuilder.METADATA_PREFIX)) {
                    throw new IllegalArgumentException("Invalid key: " + fieldName);
                }
            }
        }

        private PostFieldsMapV4(Map<String, String> formParams) {
            validate(formParams);
            this.formDataMap = Collections.unmodifiableMap(formParams);
        }

    }

    /**
     * A helper class for specifying conditions in a V4 POST Policy document. Used in: {@link
     * CloudStorageClient#generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit, PostFieldsMapV4,
     * PostConditionsVersion4, CloudStorageClient.PostPolicyV4FormField...)}.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
     *     Policy document</a>
     */
    public static final class PostConditionsVersion4 {

        private Set<ConditionalExpressionV4> conditionSet;

        private static SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

        public static class PolicyBuilder {

            private final Set<ConditionalExpressionV4> conditionSet;

            public PolicyBuilder addContentEncoding(ConditionV4Kind conditionType, String encoding) {
                validateType(conditionType, "content-encoding");
                conditionSet.add(new ConditionalExpressionV4(conditionType, "content-encoding", encoding));
                return this;
            }

            public PolicyBuilder addExpires(long expiry) {
                return addExpires(dateFormatter.format(expiry));
            }

            public PolicyBuilder addSuccessActionStatus(int responseCode) {
                conditionSet.add(new ConditionalExpressionV4(ConditionV4Kind.MATCHES, "success_action_status", "" + responseCode));
                return this;
            }

            public static PolicyBuilder newBuilder() {
                return new PolicyBuilder();
            }

            /**
             * @deprecated Use {@link #addSuccessActionStatus(int)}
             */
            @Deprecated
            public S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder addSuccessActionStatus(ConditionV4Kind type, int responseCode) {
                return addSuccessActionStatus(responseCode);
            }

            public PolicyBuilder addExpires(String expiry) {
                conditionSet.add(new ConditionalExpressionV4(ConditionV4Kind.MATCHES, "expires", expiry));
                return this;
            }

            /**
             * @deprecated Use {@link #addExpires(long)}
             */
            @Deprecated
            public S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder addExpires(ConditionV4Kind type, long expiry) {
                return addExpires(expiry);
            }

            public PolicyBuilder addSuccessActionRedirect(ConditionV4Kind conditionType, String redirectUrl) {
                validateType(conditionType, "success_action_redirect");
                conditionSet.add(new ConditionalExpressionV4(conditionType, "success_action_redirect", redirectUrl));
                return this;
            }

            private void validateType(ConditionV4Kind conditionType, String metadataName) {
                if (ConditionV4Kind.MATCHES != conditionType && ConditionV4Kind.STARTS_WITH != conditionType) {
                    throw new IllegalArgumentException("Field " + metadataName + " can't use " + conditionType);
                }
            }

            public PolicyBuilder addContentDisposition(ConditionV4Kind conditionType, String disposition) {
                validateType(conditionType, "content-disposition");
                conditionSet.add(new ConditionalExpressionV4(conditionType, "content-disposition", disposition));
                return this;
            }

            public PolicyBuilder addKey(ConditionV4Kind conditionType, String fieldName) {
                validateType(conditionType, "key");
                conditionSet.add(new ConditionalExpressionV4(conditionType, "key", fieldName));
                return this;
            }

            public PolicyBuilder addCacheControl(ConditionV4Kind conditionType, String cacheDirective) {
                validateType(conditionType, "cache-control");
                conditionSet.add(new ConditionalExpressionV4(conditionType, "cache-control", cacheDirective));
                return this;
            }

            private PolicyBuilder() {
                this(new LinkedHashSet<ConditionalExpressionV4>());
            }

            private PolicyBuilder(Set<ConditionalExpressionV4> conditionSet) {
                this.conditionSet = conditionSet;
            }

            public PolicyBuilder addContentType(ConditionV4Kind conditionType, String mimeType) {
                validateType(conditionType, "content-type");
                conditionSet.add(new ConditionalExpressionV4(conditionType, "content-type", mimeType));
                return this;
            }

            public PolicyBuilder addContentLengthRange(int minimumLength, int maximumLength) {
                conditionSet.add(new ConditionalExpressionV4(ConditionV4Kind.CONTENT_LENGTH_RANGE, "" + minimumLength, "" + maximumLength));
                return this;
            }

            /**
             * @deprecated Use {@link #addExpires(String)}
             */
            @Deprecated
            public S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder addExpires(ConditionV4Kind type, String expiry) {
                return addExpires(expiry);
            }

            public PostConditionsVersion4 buildConditions() {
                return new PostConditionsVersion4(this);
            }

            public PolicyBuilder addAcl(ConditionV4Kind conditionType, String accessControl) {
                validateType(conditionType, "acl");
                conditionSet.add(new ConditionalExpressionV4(conditionType, "acl", accessControl));
                return this;
            }

            /**
             * @deprecated Invocation of this method has no effect. Use {@link
             *     #addContentLengthRange(int, int)} to specify a range for the content-length.
             */
            public PolicyBuilder addContentLengthCondition(ConditionV4Kind type, int contentLength) {
                return this;
            }

            public PolicyBuilder addBucket(ConditionV4Kind conditionType, String bucketName) {
                validateType(conditionType, "bucket");
                conditionSet.add(new ConditionalExpressionV4(conditionType, "bucket", bucketName));
                return this;
            }

            PolicyBuilder addCustom(ConditionV4Kind conditionType, String metadataName, String metadataValue) {
                conditionSet.add(new ConditionalExpressionV4(conditionType, metadataName, metadataValue));
                return this;
            }

        }

        public static PolicyBuilder createBuilder() {
            return new PolicyBuilder();
        }

        public Set<ConditionalExpressionV4> getConditions() {
            return Collections.unmodifiableSet(conditionSet);
        }

        public PolicyBuilder asBuilder() {
            return new PolicyBuilder(conditionSet);
        }

        public PostConditionsVersion4(PolicyBuilder formBuilder) {
            this.conditionSet = formBuilder.conditionSet;
        }

    }

    /**
     * Class for a V4 POST Policy document. Used by Storage to construct {@code PostPolicyV4} objects.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
     *     Policy document</a>
     */
    public static final class PostPolicyV4JsonDocument {

        private final String expiry;

        private final PostConditionsVersion4 conditionSet;

        public static PostPolicyV4JsonDocument create(String expiry, PostConditionsVersion4 conditionSet) {
            return new PostPolicyV4JsonDocument(expiry, conditionSet);
        }

        public String toJsonString() {
            JsonObject jsonObj = new JsonObject();
            JsonArray conditionSet = new JsonArray();
            for (ConditionalExpressionV4 condExpr : this.conditionSet.conditionSet) {
                switch(condExpr.type) {
                    case MATCHES:
                        JsonObject pairObject = new JsonObject();
                        pairObject.addProperty(condExpr.operand1, condExpr.operand2);
                        conditionSet.add(pairObject);
                        break;
                    case STARTS_WITH:
                        JsonArray prefixArray = new JsonArray();
                        prefixArray.add("starts-with");
                        prefixArray.add("$" + condExpr.operand1);
                        prefixArray.add(condExpr.operand2);
                        conditionSet.add(prefixArray);
                        break;
                    case CONTENT_LENGTH_RANGE:
                        JsonArray lengthBounds = new JsonArray();
                        lengthBounds.add("content-length-range");
                        lengthBounds.add(Integer.parseInt(condExpr.operand1));
                        lengthBounds.add(Integer.parseInt(condExpr.operand2));
                        conditionSet.add(lengthBounds);
                        break;
                }
            }
            jsonObj.add("conditions", conditionSet);
            jsonObj.addProperty("expiration", expiry);
            String serializedData = jsonObj.toString();
            StringBuilder escapedBuilder = new StringBuilder();
            // Certain characters in a policy must be escaped
            for (char ch : serializedData.toCharArray()) {
                if (128 > ch) {
                    switch(ch) {
                        case '\\':
                            escapedBuilder.append("\\\\");
                            break;
                        case '\b':
                            escapedBuilder.append("\\b");
                            break;
                        case '\f':
                            escapedBuilder.append("\\f");
                            break;
                        case '\n':
                            escapedBuilder.append("\\n");
                            break;
                        case '\r':
                            escapedBuilder.append("\\r");
                            break;
                        case '\t':
                            escapedBuilder.append("\\t");
                            break;
                        case '\u000b':
                            escapedBuilder.append("\\v");
                            break;
                        default:
                            escapedBuilder.append(ch);
                    }
                } else {
                    // is a unicode character
                    escapedBuilder.append(String.format("\\u%04x", (int) ch));
                }
            }
            return escapedBuilder.toString();
        }

        private PostPolicyV4JsonDocument(String expiry, PostConditionsVersion4 conditionSet) {
            this.expiry = expiry;
            this.conditionSet = conditionSet;
        }

    }

    public enum ConditionV4Kind {

        MATCHES("eq"), STARTS_WITH("starts-with"), CONTENT_LENGTH_RANGE("content-length-range");

        private final String identifier;

        @Override
        public String toString() {
            return identifier;
        }

        ConditionV4Kind(String identifier) {
            this.identifier = identifier;
        }

    }

    /**
     * Class for a specific POST policy document condition.
     *
     * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
     *     Policy document</a>
     */
    public static final class ConditionalExpressionV4 {

        public final ConditionV4Kind type;

        public final String operand1;

        public final String operand2;

        /**
         * Examples of returned strings: {@code ["eq", "$key", "test-object"]}, {@code ["starts-with",
         * "$acl", "public"]}, {@code ["content-length-range", 246, 266]}.
         */
        @Override
        public String toString() {
            String content = ConditionV4Kind.CONTENT_LENGTH_RANGE == type ? operand1 + ", " + operand2 : "\"$" + operand1 + "\", \"" + operand2 + "\"";
            return "[\"" + type + "\", " + content + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, operand1, operand2);
        }

        @Override
        public boolean equals(Object obj) {
            ConditionalExpressionV4 condExpr = (ConditionalExpressionV4) obj;
            return condExpr.type == this.type && this.operand1.equals(condExpr.operand1) && this.operand2.equals(condExpr.operand2);
        }

        ConditionalExpressionV4(ConditionV4Kind conditionType, String firstOperand, String secondOperand) {
            this.type = conditionType;
            this.operand1 = firstOperand;
            this.operand2 = secondOperand;
        }

    }

    /**
     * Returns the URL for the HTTP POST request
     */
    public String getUrl() {
        return endpoint;
    }

    /**
     * Returns the HTML form fields
     */
    public Map<String, String> getFields() {
        return formParams;
    }

    private S3PostPolicyV4(String endpoint, Map<String, String> formParams) {
        try {
            if (!new URI(endpoint).isAbsolute()) {
                throw new IllegalArgumentException(endpoint + " is not an absolute URL");
            }
        } catch (URISyntaxException uriSyntaxException) {
            throw new IllegalArgumentException(uriSyntaxException);
        }
        PostFieldsMapV4.validate(formParams);
        this.endpoint = endpoint;
        this.formParams = Collections.unmodifiableMap(formParams);
    }

    /**
     * Constructs {@code PostPolicyV4} instance of the given URL and fields map.
     *
     * @param endpoint URL for the HTTP POST request
     * @param formParams HTML form fields
     * @return constructed object
     * @throws IllegalArgumentException if URL is malformed or fields are not valid
     */
    public static S3PostPolicyV4 from(String endpoint, Map<String, String> formParams) {
        return new S3PostPolicyV4(endpoint, formParams);
    }

}
