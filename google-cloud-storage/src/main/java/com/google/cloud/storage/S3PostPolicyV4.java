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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * <p>See {@link Storage#generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit,
 * PostFieldsMapV4, PostConditionsV4Model, Storage.PostPolicyV4Parameter...)} for
 * example of usage.
 */
public final class S3PostPolicyV4 {
  private final String endpoint;
  private final Map<String, String> metadataMap;

  private S3PostPolicyV4(String endpoint, Map<String, String> metadataMap) {
    try {
      if (!new URI(endpoint).isAbsolute()) {
        throw new IllegalArgumentException(endpoint + " is not an absolute URL");
      }
    } catch (URISyntaxException uriException) {
      throw new IllegalArgumentException(uriException);
    }

    this.endpoint = endpoint;
    this.metadataMap = Collections.unmodifiableMap(metadataMap);
  }

  /**
   * Constructs {@code PostPolicyV4} instance of the given URL and fields map.
   *
   * @param endpoint URL for the HTTP POST request
   * @param metadataMap HTML form fields
   * @return constructed object
   * @throws IllegalArgumentException if URL is malformed or fields are not valid
   */
  public static S3PostPolicyV4 create(String endpoint, Map<String, String> metadataMap) {
    return new S3PostPolicyV4(endpoint, metadataMap);
  }

  /** Returns the URL for the HTTP POST request */
  public String getUrl() {
    return endpoint;
  }

  /** Returns the HTML form fields */
  public Map<String, String> getFields() {
    return metadataMap;
  }

  /**
   * A helper class to define fields to be specified in a V4 POST request. Instance of this class
   * helps to construct {@code PostPolicyV4} objects. Used in: {@link
   * Storage#generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit, PostFieldsMapV4,
   * PostConditionsV4Model, Storage.PostPolicyV4Parameter...)}.
   *
   * @see <a href="https://cloud.google.com/storage/docs/xml-api/post-object#form_fields">POST
   *     Object Form fields</a>
   */
  public static final class PostFieldsMapV4 {
    private final Map<String, String> postMetadataMap;

    private PostFieldsMapV4(ObjectMetadataBuilder metadataCreator) {
      this(metadataCreator.postMetadataMap);
    }

    private PostFieldsMapV4(Map<String, String> metadataMap) {
      this.postMetadataMap = Collections.unmodifiableMap(metadataMap);
    }

    /**
     * Constructs {@code PostPolicyV4.PostFieldsV4} object of the given field map.
     *
     * @param metadataMap a map of the HTML form fields
     * @return constructed object
     * @throws IllegalArgumentException if an unsupported field is specified
     */
    public static PostFieldsMapV4 create(Map<String, String> metadataMap) {
      return new PostFieldsMapV4(metadataMap);
    }

    public static ObjectMetadataBuilder newObjectMetadataBuilder() {
      return new ObjectMetadataBuilder();
    }

    public Map<String, String> getFieldsMap() {
      return postMetadataMap;
    }

    public static class ObjectMetadataBuilder {
      private static final String METADATA_PREFIX = "x-goog-meta-";
      private final Map<String, String> postMetadataMap;

      private ObjectMetadataBuilder() {
        this.postMetadataMap = new HashMap<>();
      }

      public PostFieldsMapV4 buildMap() {
        return new PostFieldsMapV4(this);
      }

      public ObjectMetadataBuilder setAcl(String accessControl) {
        postMetadataMap.put("acl", accessControl);
        return this;
      }

      public ObjectMetadataBuilder setCacheControl(String cacheDirective) {
        postMetadataMap.put("cache-control", cacheDirective);
        return this;
      }

      public ObjectMetadataBuilder setContentDisposition(String dispositionHeader) {
        postMetadataMap.put("content-disposition", dispositionHeader);
        return this;
      }

      public ObjectMetadataBuilder setContentEncoding(String transferEncoding) {
        postMetadataMap.put("content-encoding", transferEncoding);
        return this;
      }

      /**
       * @deprecated Invocation of this method has no effect, because all valid HTML form fields
       *     except Content-Length can use exact matching. Use {@link
       *     PostConditionsV4Model.PostPolicyBuilder#addContentLengthRange(int, int)} to
       *     specify a range for the content-length.
       */
      @Deprecated
      public S3PostPolicyV4.PostFieldsMapV4.ObjectMetadataBuilder setContentLength(int contentLength) {
        return this;
      }

      public ObjectMetadataBuilder setContentType(String mimeType) {
        postMetadataMap.put("content-type", mimeType);
        return this;
      }

      /** @deprecated Use {@link #setExpires(String)}. */
      @Deprecated
      public S3PostPolicyV4.PostFieldsMapV4.ObjectMetadataBuilder Expires(String expiryDate) {
        return setExpires(expiryDate);
      }

      public ObjectMetadataBuilder setExpires(String expiryDate) {
        postMetadataMap.put("expires", expiryDate);
        return this;
      }

      public ObjectMetadataBuilder setSuccessActionRedirect(String successRedirect) {
        postMetadataMap.put("success_action_redirect", successRedirect);
        return this;
      }

      public ObjectMetadataBuilder setSuccessActionStatus(int successStatusCode) {
        postMetadataMap.put("success_action_status", "" + successStatusCode);
        return this;
      }

      /** @deprecated Use {@link #setCustomMetadataField(String, String)}. */
      @Deprecated
      public S3PostPolicyV4.PostFieldsMapV4.ObjectMetadataBuilder AddCustomMetadataField(String metaKey, String metadataContent) {
        return setCustomMetadataField(metaKey, metadataContent);
      }

      public ObjectMetadataBuilder setCustomMetadataField(String metaKey, String metadataContent) {
        if (!metaKey.startsWith(METADATA_PREFIX)) {
          metaKey = METADATA_PREFIX + metaKey;
        }
        postMetadataMap.put(metaKey, metadataContent);
        return this;
      }
    }
  }

  /**
   * A helper class for specifying conditions in a V4 POST Policy document. Used in: {@link
   * Storage#generateSignedPostPolicyV4(BlobMetadata, long, TimeUnit, PostFieldsMapV4,
   * PostConditionsV4Model, Storage.PostPolicyV4Parameter...)}.
   *
   * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
   *     Policy document</a>
   */
  public static final class PostConditionsV4Model {
    private Set<BinaryConditionV4> binaryConstraints;

    private static SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public PostConditionsV4Model(PostPolicyBuilder metadataCreator) {
      this.binaryConstraints = metadataCreator.binaryConstraints;
    }

    public PostPolicyBuilder toPolicyBuilder() {
      return new PostPolicyBuilder(binaryConstraints);
    }

    public static PostPolicyBuilder newPolicyBuilder() {
      return new PostPolicyBuilder();
    }

    public Set<BinaryConditionV4> getConditions() {
      return Collections.unmodifiableSet(binaryConstraints);
    }

    public static class PostPolicyBuilder {
      private final Set<BinaryConditionV4> binaryConstraints;

      private PostPolicyBuilder() {
        this(new LinkedHashSet<BinaryConditionV4>());
      }

      private PostPolicyBuilder(Set<BinaryConditionV4> binaryConstraints) {
        this.binaryConstraints = binaryConstraints;
      }

      public static PostPolicyBuilder newBuilder() {
        return new PostPolicyBuilder();
      }

      public PostConditionsV4Model buildModel() {
        return new PostConditionsV4Model(this);
      }

      public PostPolicyBuilder addAcl(ConditionTypeV4 conditionKind, String accessControl) {
        validateType(conditionKind, "acl");
        binaryConstraints.add(new BinaryConditionV4(conditionKind, "acl", accessControl));
        return this;
      }

      public PostPolicyBuilder addBucket(ConditionTypeV4 conditionKind, String containerName) {
        validateType(conditionKind, "bucket");
        binaryConstraints.add(new BinaryConditionV4(conditionKind, "bucket", containerName));
        return this;
      }

      public PostPolicyBuilder addCacheControl(ConditionTypeV4 conditionKind, String cacheDirective) {
        validateType(conditionKind, "cache-control");
        binaryConstraints.add(new BinaryConditionV4(conditionKind, "cache-control", cacheDirective));
        return this;
      }

      public PostPolicyBuilder addContentDisposition(
              ConditionTypeV4 conditionKind, String dispositionHeader) {
        validateType(conditionKind, "content-disposition");
        binaryConstraints.add(new BinaryConditionV4(conditionKind, "content-disposition", dispositionHeader));
        return this;
      }

      public PostPolicyBuilder addContentEncoding(ConditionTypeV4 conditionKind, String transferEncoding) {
        validateType(conditionKind, "content-encoding");
        binaryConstraints.add(new BinaryConditionV4(conditionKind, "content-encoding", transferEncoding));
        return this;
      }

      /**
       * @deprecated Invocation of this method has no effect. Use {@link
       *     #addContentLengthRange(int, int)} to specify a range for the content-length.
       */
      public PostPolicyBuilder addContentLengthCondition(ConditionTypeV4 type, int contentLength) {
        return this;
      }

      public PostPolicyBuilder addContentType(ConditionTypeV4 conditionKind, String mimeType) {
        validateType(conditionKind, "content-type");
        binaryConstraints.add(new BinaryConditionV4(conditionKind, "content-type", mimeType));
        return this;
      }

      /** @deprecated Use {@link #addExpires(long)} */
      @Deprecated
      public S3PostPolicyV4.PostConditionsV4Model.PostPolicyBuilder addExpires(ConditionTypeV4 type, long expiryDate) {
        return addExpires(expiryDate);
      }

      /** @deprecated Use {@link #addExpires(String)} */
      @Deprecated
      public S3PostPolicyV4.PostConditionsV4Model.PostPolicyBuilder addExpires(ConditionTypeV4 type, String expiryDate) {
        return addExpires(expiryDate);
      }

      public PostPolicyBuilder addExpires(long expiryDate) {
        return addExpiresCondition(timestampFormat.format(expiryDate));
      }

      public PostPolicyBuilder addExpires(String expiryDate) {
        binaryConstraints.add(new BinaryConditionV4(ConditionTypeV4.MATCHES, "expires", expiryDate));
        return this;
      }

      public PostPolicyBuilder addKey(ConditionTypeV4 conditionKind, String objectName) {
        validateType(conditionKind, "key");
        binaryConstraints.add(new BinaryConditionV4(conditionKind, "key", objectName));
        return this;
      }

      public PostPolicyBuilder addSuccessActionRedirect(
              ConditionTypeV4 conditionKind, String redirectLocation) {
        validateType(conditionKind, "success_action_redirect");
        binaryConstraints.add(new BinaryConditionV4(conditionKind, "success_action_redirect", redirectLocation));
        return this;
      }

      /** @deprecated Use {@link #addSuccessActionStatus(int)} */
      @Deprecated
      public S3PostPolicyV4.PostConditionsV4Model.PostPolicyBuilder addSuccessActionStatus(ConditionTypeV4 type, int responseCode) {
        return addSuccessActionStatus(responseCode);
      }

      public PostPolicyBuilder addSuccessActionStatus(int responseCode) {
        binaryConstraints.add(
            new BinaryConditionV4(ConditionTypeV4.MATCHES, "success_action_status", "" + responseCode));
        return this;
      }

      public PostPolicyBuilder addContentLengthRange(int minimumLength, int maximumLength) {
        binaryConstraints.add(new BinaryConditionV4(ConditionTypeV4.CONTENT_LENGTH_RANGE, "" + minimumLength, "" + maximumLength));
        return this;
      }

      PostPolicyBuilder addCustom(ConditionTypeV4 conditionKind, String metaKey, String metadataContent) {
        binaryConstraints.add(new BinaryConditionV4(conditionKind, metaKey, metadataContent));
        return this;
      }

      private void validateType(ConditionTypeV4 conditionKind, String metaKey) {
        if (conditionKind != ConditionTypeV4.MATCHES && conditionKind != ConditionTypeV4.STARTS_WITH) {
          throw new IllegalArgumentException("Field " + metaKey + " can't use " + conditionKind);
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
  public static final class PostPolicyV4Payload {
    private final String expiry;
    private final PostConditionsV4Model binaryConstraints;

    private PostPolicyV4Payload(String expiry, PostConditionsV4Model binaryConstraints) {
      this.expiry = expiry;
      this.binaryConstraints = binaryConstraints;
    }

    public static PostPolicyV4Payload create(String expiry, PostConditionsV4Model binaryConstraints) {
      return new PostPolicyV4Payload(expiry, binaryConstraints);
    }

    public String toJsonString() {
      JsonObject jsonRoot = new JsonObject();
      JsonArray binaryConstraints = new JsonArray();
      for (BinaryConditionV4 condEntry : this.binaryConstraints.binaryConstraints) {
        switch (condEntry.type) {
          case MATCHES:
            JsonObject matcher = new JsonObject();
            matcher.addProperty(condEntry.operand1, condEntry.operand2);
            binaryConstraints.add(matcher);
            break;
          case STARTS_WITH:
            JsonArray prefixList = new JsonArray();
            prefixList.add("starts-with");
            prefixList.add("$" + condEntry.operand1);
            prefixList.add(condEntry.operand2);
            binaryConstraints.add(prefixList);
            break;
          case CONTENT_LENGTH_RANGE:
            JsonArray lengthRange = new JsonArray();
            lengthRange.add("content-length-range");
            lengthRange.add(Integer.parseInt(condEntry.operand1));
            lengthRange.add(Integer.parseInt(condEntry.operand2));
            binaryConstraints.add(lengthRange);
            break;
        }
      }
      jsonRoot.add("conditions", binaryConstraints);
      jsonRoot.addProperty("expiration", expiry);

      String serializedPayload = jsonRoot.toString();
      StringBuilder escapedBuilder = new StringBuilder();

      // Certain characters in a policy must be escaped
      char[] jsonChars = serializedPayload.toCharArray();
      for (int index = 0; index < jsonChars.length; index++) {
        char ch = jsonChars[index];
        if (ch >= 128) { // is a unicode character
          escapedBuilder.append(String.format("\\u%04x", (int) ch));
        } else {
          switch (ch) {
            case '\\':
              // The JsonObject/JsonArray operations above handle quote escapes, so leave any "\""
              // found alone
              if (jsonChars[index + 1] == '"') {
                escapedBuilder.append("\\");
              } else {
                escapedBuilder.append("\\\\");
              }
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
        }
      }
      return escapedBuilder.toString();
    }
  }

  public enum ConditionTypeV4 {
    MATCHES("eq"),
    STARTS_WITH("starts-with"),
    CONTENT_LENGTH_RANGE("content-length-range");

    private final String typeLabel;

    ConditionTypeV4(String typeLabel) {
      this.typeLabel = typeLabel;
    }

    @Override
    public String toString() {
      return typeLabel;
    }
  }

  /**
   * Class for a specific POST policy document condition.
   *
   * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
   *     Policy document</a>
   */
  public static final class BinaryConditionV4 {
    public final ConditionTypeV4 type;
    public final String operand1;
    public final String operand2;

    BinaryConditionV4(ConditionTypeV4 conditionKind, String leftValue, String rightValue) {
      this.type = conditionKind;
      this.operand1 = leftValue;
      this.operand2 = rightValue;
    }

    @Override
    public boolean equals(Object obj) {
      BinaryConditionV4 condEntry = (BinaryConditionV4) obj;
      return this.type == condEntry.type
          && this.operand1.equals(condEntry.operand1)
          && this.operand2.equals(condEntry.operand2);
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
          type == ConditionTypeV4.CONTENT_LENGTH_RANGE
              ? operand1 + ", " + operand2
              : "\"$" + operand1 + "\", \"" + operand2 + "\"";
      return "[\"" + type + "\", " + content + "]";
    }
  }
}
