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
public final class PostPolicyV4 {
  private String endpointAddress;
  private Map<String, String> attributes;

  private PostPolicyV4(String endpointAddress, Map<String, String> attributes) {
    this.endpointAddress = endpointAddress;
    this.attributes = attributes;
  }

  public static PostPolicyV4 create(String endpointAddress, Map<String, String> attributes) {
    return new PostPolicyV4(endpointAddress, attributes);
  }

  public String getUrl() {
    return endpointAddress;
  }

  public Map<String, String> getFields() {
    return attributes;
  }

  /**
   * Class representing which fields to specify in a V4 POST request.
   *
   * @see <a href="https://cloud.google.com/storage/docs/xml-api/post-object#form_fields">POST
   *     Object Form fields</a>
   */
  public static final class PostFieldsV4 {
    private Map<String, String> entriesMap;

    private PostFieldsV4(Builder creator) {
      this.entriesMap = creator.entriesMap;
    }

    private PostFieldsV4(Map<String, String> attributes) {
      this.entriesMap = attributes;
    }

    public static PostFieldsV4 create(Map<String, String> attributes) {
      return new PostFieldsV4(attributes);
    }

    public static Builder builder() {
      return new Builder();
    }

    public Map<String, String> getFieldsMap() {
      return entriesMap;
    }

    public static class Builder {
      private Map<String, String> entriesMap;

      private Builder() {
        entriesMap = new HashMap<>();
      }

      public PostFieldsV4 buildInstance() {
        return new PostFieldsV4(this);
      }

      public Builder setAcl(String accessControl) {
        entriesMap.put("acl", accessControl);
        return this;
      }

      public Builder setCacheControl(String cachingPolicy) {
        entriesMap.put("cache-control", cachingPolicy);
        return this;
      }

      public Builder setContentDisposition(String dispositionHeader) {
        entriesMap.put("content-disposition", dispositionHeader);
        return this;
      }

      public Builder setContentEncoding(String encodingScheme) {
        entriesMap.put("content-encoding", encodingScheme);
        return this;
      }

      public Builder setContentLength(int lengthBytes) {
        entriesMap.put("content-length", "" + lengthBytes);
        return this;
      }

      public Builder setContentType(String mimeType) {
        entriesMap.put("content-type", mimeType);
        return this;
      }

      public Builder Expires(String expiryTimestamp) {
        entriesMap.put("expires", expiryTimestamp);
        return this;
      }

      public Builder setSuccessActionRedirect(String redirectLocation) {
        entriesMap.put("success_action_redirect", redirectLocation);
        return this;
      }

      public Builder setSuccessActionStatus(int successStatusCode) {
        entriesMap.put("success_action_status", "" + successStatusCode);
        return this;
      }

      public Builder AddCustomMetadataField(String metadataKey, String metadataContent) {
        entriesMap.put("x-goog-meta-" + metadataKey, metadataContent);
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
  public static final class PostConditionsV4 {
    private Set<ConditionV4> constraints;

    private static SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public PostConditionsV4(Builder creator) {
      this.constraints = creator.constraints;
    }

    public Builder asBuilder() {
      return new Builder(constraints);
    }

    public static Builder builder() {
      return new Builder();
    }

    public Set<ConditionV4> getConditions() {
      return constraints;
    }

    public static class Builder {
      Set<ConditionV4> constraints;

      private Builder() {
        this.constraints = new LinkedHashSet<>();
      }

      private Builder(Set<ConditionV4> constraints) {
        this.constraints = constraints;
      }

      public static Builder newBuilder() {
        return new Builder();
      }

      public PostConditionsV4 buildInstance() {
        return new PostConditionsV4(this);
      }

      public Builder addAclCondition(ConditionV4Type conditionCategory, String accessControl) {
        constraints.add(new ConditionV4(conditionCategory, "acl", accessControl));
        return this;
      }

      public Builder withBucketCondition(ConditionV4Type conditionCategory, String containerName) {
        constraints.add(new ConditionV4(conditionCategory, "bucket", containerName));
        return this;
      }

      public Builder addCacheControlCondition(ConditionV4Type conditionCategory, String cachingPolicy) {
        constraints.add(new ConditionV4(conditionCategory, "cache-control", cachingPolicy));
        return this;
      }

      public Builder addContentDispositionCondition(
              ConditionV4Type conditionCategory, String dispositionHeader) {
        constraints.add(new ConditionV4(conditionCategory, "content-disposition", dispositionHeader));
        return this;
      }

      public Builder addContentEncodingCondition(ConditionV4Type conditionCategory, String encodingScheme) {
        constraints.add(new ConditionV4(conditionCategory, "content-encoding", encodingScheme));
        return this;
      }

      public Builder addContentLengthCondition(ConditionV4Type conditionCategory, int lengthBytes) {
        constraints.add(new ConditionV4(conditionCategory, "content-length", "" + lengthBytes));
        return this;
      }

      public Builder withContentTypeCondition(ConditionV4Type conditionCategory, String mimeType) {
        constraints.add(new ConditionV4(conditionCategory, "content-type", mimeType));
        return this;
      }

      public Builder addExpiresCondition(ConditionV4Type conditionCategory, long expiryTimestamp) {
        constraints.add(new ConditionV4(conditionCategory, "expires", dateFormatter.format(expiryTimestamp)));
        return this;
      }

      public Builder addExpiresCondition(ConditionV4Type conditionCategory, String expiryTimestamp) {
        constraints.add(new ConditionV4(conditionCategory, "expires", expiryTimestamp));
        return this;
      }

      public Builder withKeyCondition(ConditionV4Type conditionCategory, String objectName) {
        constraints.add(new ConditionV4(conditionCategory, "key", objectName));
        return this;
      }

      public Builder addSuccessRedirect(
              ConditionV4Type conditionCategory, String redirectDestinationUrl) {
        constraints.add(new ConditionV4(conditionCategory, "success_action_redirect", redirectDestinationUrl));
        return this;
      }

      public Builder addSuccessStatus(ConditionV4Type conditionCategory, int responseCode) {
        constraints.add(new ConditionV4(conditionCategory, "success_action_status", "" + responseCode));
        return this;
      }

      public Builder addContentLengthRange(int lowerLimit, int upperLimit) {
        constraints.add(new ConditionV4(ConditionV4Type.CONTENT_LENGTH_RANGE, "" + lowerLimit, "" + upperLimit));
        return this;
      }

      Builder addCondition(ConditionV4Type conditionCategory, String metadataKey, String metadataContent) {
        constraints.add(new ConditionV4(conditionCategory, metadataKey, metadataContent));
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
  public static final class PostPolicyV4Document {
    private String expiryDate;
    private PostConditionsV4 constraints;

    private PostPolicyV4Document(String expiryDate, PostConditionsV4 constraints) {
      this.expiryDate = expiryDate;
      this.constraints = constraints;
    }

    public static PostPolicyV4Document create(String expiryDate, PostConditionsV4 constraints) {
      return new PostPolicyV4Document(expiryDate, constraints);
    }

    public String toJsonString() {
      JsonObject jsonObj = new JsonObject();
      JsonArray constraints = new JsonArray();
      for (ConditionV4 condElement : this.constraints.constraints) {
        switch (condElement.conditionCategory) {
          case MATCHES:
            JsonObject comparisonObject = new JsonObject();
            comparisonObject.addProperty(condElement.leftOperand, condElement.rightOperand);
            constraints.add(comparisonObject);
            break;
          case STARTS_WITH:
            JsonArray prefixArray = new JsonArray();
            prefixArray.add("starts-with");
            prefixArray.add("$" + condElement.leftOperand);
            prefixArray.add(condElement.rightOperand);
            constraints.add(prefixArray);
            break;
          case CONTENT_LENGTH_RANGE:
            JsonArray lengthRangeArray = new JsonArray();
            lengthRangeArray.add("content-length-range");
            lengthRangeArray.add(Integer.parseInt(condElement.leftOperand));
            lengthRangeArray.add(Integer.parseInt(condElement.rightOperand));
            constraints.add(lengthRangeArray);
            break;
        }
      }
      jsonObj.add("conditions", constraints);
      jsonObj.addProperty("expiration", expiryDate);

      String serialized = jsonObj.toString();
      StringBuilder escapedString = new StringBuilder();

      // Certain characters in a policy must be escaped
      for (char ch : serialized.toCharArray()) {
        if (ch >= 128) { // is a unicode character
          escapedString.append(String.format("\\u%04x", (int) ch));
        } else {
          switch (ch) {
            case '\\':
              escapedString.append("\\\\");
              break;
            case '\b':
              escapedString.append("\\b");
              break;
            case '\f':
              escapedString.append("\\f");
              break;
            case '\n':
              escapedString.append("\\n");
              break;
            case '\r':
              escapedString.append("\\r");
              break;
            case '\t':
              escapedString.append("\\t");
              break;
            case '\u000b':
              escapedString.append("\\v");
              break;
            default:
              escapedString.append(ch);
          }
        }
      }
      return escapedString.toString();
    }
  }

  public enum ConditionV4Type {
    MATCHES,
    STARTS_WITH,
    CONTENT_LENGTH_RANGE
  }

  /**
   * Class for a specific POST policy document condition.
   *
   * @see <a href="https://cloud.google.com/storage/docs/authentication/signatures#policy-document">
   *     Policy document</a>
   */
  static final class ConditionV4 {
    ConditionV4Type conditionCategory;
    String leftOperand;
    String rightOperand;

    private ConditionV4(ConditionV4Type conditionCategory, String leftOperand, String rightOperand) {
      this.conditionCategory = conditionCategory;
      this.leftOperand = leftOperand;
      this.rightOperand = rightOperand;
    }

    @Override
    public boolean equals(Object obj) {
      ConditionV4 condElement = (ConditionV4) obj;
      return this.conditionCategory == condElement.conditionCategory
          && this.leftOperand.equals(condElement.leftOperand)
          && this.rightOperand.equals(condElement.rightOperand);
    }

    @Override
    public int hashCode() {
      return Objects.hash(conditionCategory, leftOperand, rightOperand);
    }
  }
}
