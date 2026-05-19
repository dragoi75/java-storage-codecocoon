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
 * PostFieldsMapV4, PostConditionsVersion4, CloudStorageClient.PostPolicyV4Parameter...)} for
 * example of usage.
 */
public final class FormPostPolicyV4 {
  private final String endpoint;
  private final Map<String, String> parameters;

  private FormPostPolicyV4(String endpoint, Map<String, String> parameters) {
    try {
      if (!new URI(endpoint).isAbsolute()) {
        throw new IllegalArgumentException(endpoint + " is not an absolute URL");
      }
    } catch (URISyntaxException uriException) {
      throw new IllegalArgumentException(uriException);
    }
    PostFieldsMapV4.validate(parameters);

    this.endpoint = endpoint;
    this.parameters = Collections.unmodifiableMap(parameters);
  }

  /**
   * Constructs {@code PostPolicyV4} instance of the given URL and fields map.
   *
   * @param endpoint URL for the HTTP POST request
   * @param parameters HTML form fields
   * @return constructed object
   * @throws IllegalArgumentException if URL is malformed or fields are not valid
   */
  public static FormPostPolicyV4 create(String endpoint, Map<String, String> parameters) {
    return new FormPostPolicyV4(endpoint, parameters);
  }

  /** Returns the URL for the HTTP POST request */
  public String getUrl() {
    return endpoint;
  }

  /** Returns the HTML form fields */
  public Map<String, String> getFields() {
    return parameters;
  }

  /**
   * A helper class to define fields to be specified in a V4 POST request. Instance of this class
   * helps to construct {@code PostPolicyV4} objects. Used in: {@link
   * CloudStorageClient#generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit, PostFieldsMapV4,
   * PostConditionsVersion4, CloudStorageClient.PostPolicyV4Parameter...)}.
   *
   * @see <a href="https://cloud.google.com/storage/docs/xml-api/post-object#form_fields">POST
   *     Object Form fields</a>
   */
  public static final class PostFieldsMapV4 {
    private final Map<String, String> parametersMap;
    private static final List<String> ALLOWED_KEYS =
        Arrays.asList(
            "acl",
            "bucket",
            "cache-control",
            "content-disposition",
            "content-encoding",
            "content-type",
            "expires",
            "file",
            "key",
            "policy",
            "success_action_redirect",
            "success_action_status",
            "x-goog-algorithm",
            "x-goog-credential",
            "x-goog-date",
            "x-goog-signature");

    private static void validate(Map<String, String> parameters) {
      for (String fieldIdentifier : parameters.keySet()) {
        if (!ALLOWED_KEYS.contains(fieldIdentifier.toLowerCase())
            && !fieldIdentifier.startsWith(ObjectMetadataBuilder.META_PREFIX)) {
          throw new IllegalArgumentException("Invalid key: " + fieldIdentifier);
        }
      }
    }

    private PostFieldsMapV4(ObjectMetadataBuilder objectMetadata) {
      this(objectMetadata.parametersMap);
    }

    private PostFieldsMapV4(Map<String, String> parameters) {
      validate(parameters);
      this.parametersMap = Collections.unmodifiableMap(parameters);
    }

    /**
     * Constructs {@code PostPolicyV4.PostFieldsV4} object of the given field map.
     *
     * @param parameters a map of the HTML form fields
     * @return constructed object
     * @throws IllegalArgumentException if an unsupported field is specified
     */
    public static PostFieldsMapV4 create(Map<String, String> parameters) {
      return new PostFieldsMapV4(parameters);
    }

    public static ObjectMetadataBuilder builder() {
      return new ObjectMetadataBuilder();
    }

    public Map<String, String> getFieldsMap() {
      return parametersMap;
    }

    public static class ObjectMetadataBuilder {
      private static final String META_PREFIX = "x-goog-meta-";
      private final Map<String, String> parametersMap;

      private ObjectMetadataBuilder() {
        this.parametersMap = new HashMap<>();
      }

      public PostFieldsMapV4 create() {
        return new PostFieldsMapV4(this);
      }

      public ObjectMetadataBuilder setAcl(String accessControlList) {
        parametersMap.put("acl", accessControlList);
        return this;
      }

      public ObjectMetadataBuilder setCacheControl(String cacheDirective) {
        parametersMap.put("cache-control", cacheDirective);
        return this;
      }

      public ObjectMetadataBuilder setContentDisposition(String dispositionValue) {
        parametersMap.put("content-disposition", dispositionValue);
        return this;
      }

      public ObjectMetadataBuilder setContentEncoding(String encodingType) {
        parametersMap.put("content-encoding", encodingType);
        return this;
      }

      /**
       * @deprecated Invocation of this method has no effect, because all valid HTML form fields
       *     except Content-Length can use exact matching. Use {@link
       *     PostConditionsVersion4.ConditionsBuilder#addContentLengthRange(int, int)} to
       *     specify a range for the content-length.
       */
      @Deprecated
      public FormPostPolicyV4.PostFieldsMapV4.ObjectMetadataBuilder setContentLength(int contentLength) {
        return this;
      }

      public ObjectMetadataBuilder setContentType(String mimeType) {
        parametersMap.put("content-type", mimeType);
        return this;
      }

      /** @deprecated Use {@link #setExpires(String)}. */
      @Deprecated
      public FormPostPolicyV4.PostFieldsMapV4.ObjectMetadataBuilder Expires(String expiration) {
        return setExpires(expiration);
      }

      public ObjectMetadataBuilder setExpires(String expiration) {
        parametersMap.put("expires", expiration);
        return this;
      }

      public ObjectMetadataBuilder setSuccessActionRedirect(String successRedirect) {
        parametersMap.put("success_action_redirect", successRedirect);
        return this;
      }

      public ObjectMetadataBuilder setSuccessActionStatus(int successStatusCode) {
        parametersMap.put("success_action_status", "" + successStatusCode);
        return this;
      }

      /** @deprecated Use {@link #setCustomMetadataField(String, String)}. */
      @Deprecated
      public FormPostPolicyV4.PostFieldsMapV4.ObjectMetadataBuilder AddCustomMetadataField(String metadataName, String metadataValue) {
        return setCustomMetadataField(metadataName, metadataValue);
      }

      public ObjectMetadataBuilder setCustomMetadataField(String metadataName, String metadataValue) {
        if (!metadataName.startsWith(META_PREFIX)) {
          metadataName = META_PREFIX + metadataName;
        }
        parametersMap.put(metadataName, metadataValue);
        return this;
      }
    }
  }

  /**
   * A helper class for specifying conditions in a V4 POST Policy document. Used in: {@link
   * CloudStorageClient#generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit, PostFieldsMapV4,
   * PostConditionsVersion4, CloudStorageClient.PostPolicyV4Parameter...)}.
   *
   * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
   *     Policy document</a>
   */
  public static final class PostConditionsVersion4 {
    private Set<BinaryConditionV4> conditionSet;

    private static SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public PostConditionsVersion4(ConditionsBuilder objectMetadata) {
      this.conditionSet = objectMetadata.conditionSet;
    }

    public ConditionsBuilder asBuilder() {
      return new ConditionsBuilder(conditionSet);
    }

    public static ConditionsBuilder builder() {
      return new ConditionsBuilder();
    }

    public Set<BinaryConditionV4> getConditions() {
      return Collections.unmodifiableSet(conditionSet);
    }

    public static class ConditionsBuilder {
      private final Set<BinaryConditionV4> conditionSet;

      private ConditionsBuilder() {
        this(new LinkedHashSet<BinaryConditionV4>());
      }

      private ConditionsBuilder(Set<BinaryConditionV4> conditionSet) {
        this.conditionSet = conditionSet;
      }

      public static ConditionsBuilder newBuilder() {
        return new ConditionsBuilder();
      }

      public PostConditionsVersion4 create() {
        return new PostConditionsVersion4(this);
      }

      public ConditionsBuilder addAcl(ConditionV4Operator operator, String accessControlList) {
        validateType(operator, "acl");
        conditionSet.add(new BinaryConditionV4(operator, "acl", accessControlList));
        return this;
      }

      public ConditionsBuilder addBucket(ConditionV4Operator operator, String containerName) {
        validateType(operator, "bucket");
        conditionSet.add(new BinaryConditionV4(operator, "bucket", containerName));
        return this;
      }

      public ConditionsBuilder addCacheControl(ConditionV4Operator operator, String cacheDirective) {
        validateType(operator, "cache-control");
        conditionSet.add(new BinaryConditionV4(operator, "cache-control", cacheDirective));
        return this;
      }

      public ConditionsBuilder addContentDisposition(
              ConditionV4Operator operator, String dispositionValue) {
        validateType(operator, "content-disposition");
        conditionSet.add(new BinaryConditionV4(operator, "content-disposition", dispositionValue));
        return this;
      }

      public ConditionsBuilder addContentEncoding(ConditionV4Operator operator, String encodingType) {
        validateType(operator, "content-encoding");
        conditionSet.add(new BinaryConditionV4(operator, "content-encoding", encodingType));
        return this;
      }

      /**
       * @deprecated Invocation of this method has no effect. Use {@link
       *     #addContentLengthRange(int, int)} to specify a range for the content-length.
       */
      public ConditionsBuilder addContentLengthCondition(ConditionV4Operator type, int contentLength) {
        return this;
      }

      public ConditionsBuilder addContentType(ConditionV4Operator operator, String mimeType) {
        validateType(operator, "content-type");
        conditionSet.add(new BinaryConditionV4(operator, "content-type", mimeType));
        return this;
      }

      /** @deprecated Use {@link #addExpires(long)} */
      @Deprecated
      public FormPostPolicyV4.PostConditionsVersion4.ConditionsBuilder addExpires(ConditionV4Operator type, long expiration) {
        return addExpires(expiration);
      }

      /** @deprecated Use {@link #addExpires(String)} */
      @Deprecated
      public FormPostPolicyV4.PostConditionsVersion4.ConditionsBuilder addExpires(ConditionV4Operator type, String expiration) {
        return addExpires(expiration);
      }

      public ConditionsBuilder addExpires(long expiration) {
        return addExpiresCondition(dateFormatter.format(expiration));
      }

      public ConditionsBuilder addExpires(String expiration) {
        conditionSet.add(new BinaryConditionV4(ConditionV4Operator.MATCHES, "expires", expiration));
        return this;
      }

      public ConditionsBuilder addKey(ConditionV4Operator operator, String fieldIdentifier) {
        validateType(operator, "key");
        conditionSet.add(new BinaryConditionV4(operator, "key", fieldIdentifier));
        return this;
      }

      public ConditionsBuilder addSuccessActionRedirect(
              ConditionV4Operator operator, String redirectUrl) {
        validateType(operator, "success_action_redirect");
        conditionSet.add(new BinaryConditionV4(operator, "success_action_redirect", redirectUrl));
        return this;
      }

      /** @deprecated Use {@link #addSuccessActionStatus(int)} */
      @Deprecated
      public FormPostPolicyV4.PostConditionsVersion4.ConditionsBuilder addSuccessActionStatus(ConditionV4Operator type, int responseCode) {
        return addSuccessActionStatus(responseCode);
      }

      public ConditionsBuilder addSuccessActionStatus(int responseCode) {
        conditionSet.add(
            new BinaryConditionV4(ConditionV4Operator.MATCHES, "success_action_status", "" + responseCode));
        return this;
      }

      public ConditionsBuilder addContentLengthRange(int lowerBound, int upperBound) {
        conditionSet.add(new BinaryConditionV4(ConditionV4Operator.CONTENT_LENGTH_RANGE, "" + lowerBound, "" + upperBound));
        return this;
      }

      ConditionsBuilder addCustom(ConditionV4Operator operator, String metadataName, String metadataValue) {
        conditionSet.add(new BinaryConditionV4(operator, metadataName, metadataValue));
        return this;
      }

      private void validateType(ConditionV4Operator operator, String metadataName) {
        if (operator != ConditionV4Operator.MATCHES && operator != ConditionV4Operator.STARTS_WITH) {
          throw new IllegalArgumentException("Field " + metadataName + " can't use " + operator);
        }
      }
    }
  }

  /**
   * Class for a V4 POST Policy document. Used by Storage to construct {@code PostPolicyV4} objects.
   *
   * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
   *     Policy document</a>
   */
  public static final class PostPolicyV4DocumentModel {
    private final String expiryDate;
    private final PostConditionsVersion4 conditionSet;

    private PostPolicyV4DocumentModel(String expiryDate, PostConditionsVersion4 conditionSet) {
      this.expiryDate = expiryDate;
      this.conditionSet = conditionSet;
    }

    public static PostPolicyV4DocumentModel create(String expiryDate, PostConditionsVersion4 conditionSet) {
      return new PostPolicyV4DocumentModel(expiryDate, conditionSet);
    }

    public String toJsonString() {
      JsonObject jsonRoot = new JsonObject();
      JsonArray conditionSet = new JsonArray();
      for (BinaryConditionV4 binaryClause : this.conditionSet.conditionSet) {
        switch (binaryClause.type) {
          case MATCHES:
            JsonObject matchingPair = new JsonObject();
            matchingPair.addProperty(binaryClause.operand1, binaryClause.operand2);
            conditionSet.add(matchingPair);
            break;
          case STARTS_WITH:
            JsonArray prefixArray = new JsonArray();
            prefixArray.add("starts-with");
            prefixArray.add("$" + binaryClause.operand1);
            prefixArray.add(binaryClause.operand2);
            conditionSet.add(prefixArray);
            break;
          case CONTENT_LENGTH_RANGE:
            JsonArray lengthBoundsArray = new JsonArray();
            lengthBoundsArray.add("content-length-range");
            lengthBoundsArray.add(Integer.parseInt(binaryClause.operand1));
            lengthBoundsArray.add(Integer.parseInt(binaryClause.operand2));
            conditionSet.add(lengthBoundsArray);
            break;
        }
      }
      jsonRoot.add("conditions", conditionSet);
      jsonRoot.addProperty("expiration", expiryDate);

      String serializedString = jsonRoot.toString();
      StringBuilder escapedBuffer = new StringBuilder();

      // Certain characters in a policy must be escaped
      for (char ch : serializedString.toCharArray()) {
        if (ch >= 128) { // is a unicode character
          escapedBuffer.append(String.format("\\u%04x", (int) ch));
        } else {
          switch (ch) {
            case '\\':
              escapedBuffer.append("\\\\");
              break;
            case '\b':
              escapedBuffer.append("\\b");
              break;
            case '\f':
              escapedBuffer.append("\\f");
              break;
            case '\n':
              escapedBuffer.append("\\n");
              break;
            case '\r':
              escapedBuffer.append("\\r");
              break;
            case '\t':
              escapedBuffer.append("\\t");
              break;
            case '\u000b':
              escapedBuffer.append("\\v");
              break;
            default:
              escapedBuffer.append(ch);
          }
        }
      }
      return escapedBuffer.toString();
    }
  }

  public enum ConditionV4Operator {
    MATCHES("eq"),
    STARTS_WITH("starts-with"),
    CONTENT_LENGTH_RANGE("content-length-range");

    private final String identifier;

    ConditionV4Operator(String identifier) {
      this.identifier = identifier;
    }

    @Override
    public String toString() {
      return identifier;
    }
  }

  /**
   * Class for a specific POST policy document condition.
   *
   * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
   *     Policy document</a>
   */
  public static final class BinaryConditionV4 {
    public final ConditionV4Operator type;
    public final String operand1;
    public final String operand2;

    BinaryConditionV4(ConditionV4Operator operator, String leftOperand, String rightOperand) {
      this.type = operator;
      this.operand1 = leftOperand;
      this.operand2 = rightOperand;
    }

    @Override
    public boolean equals(Object obj) {
      BinaryConditionV4 binaryClause = (BinaryConditionV4) obj;
      return this.type == binaryClause.type
          && this.operand1.equals(binaryClause.operand1)
          && this.operand2.equals(binaryClause.operand2);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, operand1, operand2);
    }

    /**
     * Examples of returned strings: {@code ["eq", "$key", "test-object"]}, {@code ["starts-with",
     * "$acl", "public"]}, {@code ["content-length-range", 246, 266]}.
     */
    @Override
    public String toString() {
      String content =
          type == ConditionV4Operator.CONTENT_LENGTH_RANGE
              ? operand1 + ", " + operand2
              : "\"$" + operand1 + "\", \"" + operand2 + "\"";
      return "[\"" + type + "\", " + content + "]";
    }
  }
}
