/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy from the License at
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
public final class FormPostPolicyV4 {
  private String endpoint;
  private Map<String, String> formParams;

  private FormPostPolicyV4(String endpoint, Map<String, String> formParams) {
    this.endpoint = endpoint;
    this.formParams = formParams;
  }

  public static FormPostPolicyV4 from(String endpoint, Map<String, String> formParams) {
    return new FormPostPolicyV4(endpoint, formParams);
  }

  public String getUrl() {
    return endpoint;
  }

  public Map<String, String> getFields() {
    return formParams;
  }

  /**
   * Class representing which setFields to specify in a V4 POST request.
   *
   * @see <a href="https://cloud.google.com/storage/docs/xml-api/post-object#form_fields">POST
   *     Object Form setFields</a>
   */
  public static final class PostFieldsMapV4 {
    private Map<String, String> entriesMap;

    private PostFieldsMapV4(UploadFormBuilder formCreator) {
      this.entriesMap = formCreator.entriesMap;
    }

    private PostFieldsMapV4(Map<String, String> formParams) {
      this.entriesMap = formParams;
    }

    public static PostFieldsMapV4 from(Map<String, String> formParams) {
      return new PostFieldsMapV4(formParams);
    }

    public static UploadFormBuilder newUploadFormBuilder() {
      return new UploadFormBuilder();
    }

    public Map<String, String> getFieldsMap() {
      return entriesMap;
    }

    public static class UploadFormBuilder {
      private Map<String, String> entriesMap;

      private UploadFormBuilder() {
        entriesMap = new HashMap<>();
      }

      public PostFieldsMapV4 buildPostFieldsMap() {
        return new PostFieldsMapV4(this);
      }

      public UploadFormBuilder setAcl(String accessLevel) {
        entriesMap.put("acl", accessLevel);
        return this;
      }

      public UploadFormBuilder setCacheControl(String cacheDirective) {
        entriesMap.put("cache-control", cacheDirective);
        return this;
      }

      public UploadFormBuilder setContentDisposition(String dispositionHeader) {
        entriesMap.put("content-disposition", dispositionHeader);
        return this;
      }

      public UploadFormBuilder setContentEncoding(String encodingHeader) {
        entriesMap.put("content-encoding", encodingHeader);
        return this;
      }

      public UploadFormBuilder setContentLength(int lengthBytes) {
        entriesMap.put("content-length", "" + lengthBytes);
        return this;
      }

      public UploadFormBuilder setContentType(String mediaType) {
        entriesMap.put("content-type", mediaType);
        return this;
      }

      public UploadFormBuilder Expires(String expiryDate) {
        entriesMap.put("expires", expiryDate);
        return this;
      }

      public UploadFormBuilder setSuccessActionRedirect(String redirectTarget) {
        entriesMap.put("success_action_redirect", redirectTarget);
        return this;
      }

      public UploadFormBuilder setSuccessActionStatus(int redirectStatus) {
        entriesMap.put("success_action_status", "" + redirectStatus);
        return this;
      }

      public UploadFormBuilder AddCustomMetadataField(String metadataKey, String entryData) {
        entriesMap.put("x-goog-meta-" + metadataKey, entryData);
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
  public static final class PostConditionsV4Dto {
    private Set<BinaryConditionV4> conditionSet;

    private static SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public PostConditionsV4Dto(ConditionBuilder formCreator) {
      this.conditionSet = formCreator.conditionSet;
    }

    public ConditionBuilder toConditionBuilder() {
      return new ConditionBuilder(conditionSet);
    }

    public static ConditionBuilder newConditionBuilder() {
      return new ConditionBuilder();
    }

    public Set<BinaryConditionV4> getConditions() {
      return conditionSet;
    }

    public static class ConditionBuilder {
      Set<BinaryConditionV4> conditionSet;

      private ConditionBuilder() {
        this.conditionSet = new LinkedHashSet<>();
      }

      private ConditionBuilder(Set<BinaryConditionV4> conditionSet) {
        this.conditionSet = conditionSet;
      }

      public static ConditionBuilder newBuilder() {
        return new ConditionBuilder();
      }

      public PostConditionsV4Dto buildPostConditionsV4Dto() {
        return new PostConditionsV4Dto(this);
      }

      public ConditionBuilder addAclCondition(ConditionTypeV4 conditionKind, String accessLevel) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "acl", accessLevel));
        return this;
      }

      public ConditionBuilder addBucketConstraint(ConditionTypeV4 conditionKind, String containerName) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "bucket", containerName));
        return this;
      }

      public ConditionBuilder addCacheControlCondition(ConditionTypeV4 conditionKind, String cacheDirective) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "cache-control", cacheDirective));
        return this;
      }

      public ConditionBuilder addContentDispositionCondition(
          ConditionTypeV4 conditionKind, String dispositionHeader) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "content-disposition", dispositionHeader));
        return this;
      }

      public ConditionBuilder addContentEncodingCondition(ConditionTypeV4 conditionKind, String encodingHeader) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "content-encoding", encodingHeader));
        return this;
      }

      public ConditionBuilder addContentLengthCondition(ConditionTypeV4 conditionKind, int lengthBytes) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "content-length", "" + lengthBytes));
        return this;
      }

      public ConditionBuilder addContentType(ConditionTypeV4 conditionKind, String mediaType) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "content-type", mediaType));
        return this;
      }

      public ConditionBuilder addExpiresCondition(ConditionTypeV4 conditionKind, long expiryDate) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "expires", formatter.format(expiryDate)));
        return this;
      }

      public ConditionBuilder addExpiresCondition(ConditionTypeV4 conditionKind, String expiryDate) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "expires", expiryDate));
        return this;
      }

      public ConditionBuilder withKeyCondition(ConditionTypeV4 conditionKind, String objectName) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "key", objectName));
        return this;
      }

      public ConditionBuilder addSuccessActionRedirectCondition(
          ConditionTypeV4 conditionKind, String redirectTarget) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "success_action_redirect", redirectTarget));
        return this;
      }

      public ConditionBuilder addSuccessActionStatus(ConditionTypeV4 conditionKind, int responseCode) {
        conditionSet.add(new BinaryConditionV4(conditionKind, "success_action_status", "" + responseCode));
        return this;
      }

      public ConditionBuilder addContentLengthRange(int lowerBound, int upperBound) {
        conditionSet.add(new BinaryConditionV4(ConditionTypeV4.CONTENT_LENGTH_RANGE, "" + lowerBound, "" + upperBound));
        return this;
      }

      ConditionBuilder addBinaryCondition(ConditionTypeV4 conditionKind, String metadataKey, String entryData) {
        conditionSet.add(new BinaryConditionV4(conditionKind, metadataKey, entryData));
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
  public static final class PostPolicyV4PolicyDocument {
    private String expirationDate;
    private PostConditionsV4Dto conditionSet;

    private PostPolicyV4PolicyDocument(String expirationDate, PostConditionsV4Dto conditionSet) {
      this.expirationDate = expirationDate;
      this.conditionSet = conditionSet;
    }

    public static PostPolicyV4PolicyDocument from(String expirationDate, PostConditionsV4Dto conditionSet) {
      return new PostPolicyV4PolicyDocument(expirationDate, conditionSet);
    }

    public String toJsonString() {
      JsonObject rootJson = new JsonObject();
      JsonArray conditionSet = new JsonArray();
      for (BinaryConditionV4 binaryCond : this.conditionSet.conditionSet) {
        switch (binaryCond.conditionKind) {
          case MATCHES:
            JsonObject comparisonJson = new JsonObject();
            comparisonJson.addProperty(binaryCond.leftOperand, binaryCond.rightOperand);
            conditionSet.add(comparisonJson);
            break;
          case STARTS_WITH:
            JsonArray prefixArray = new JsonArray();
            prefixArray.add("starts-with");
            prefixArray.add("$" + binaryCond.leftOperand);
            prefixArray.add(binaryCond.rightOperand);
            conditionSet.add(prefixArray);
            break;
          case CONTENT_LENGTH_RANGE:
            JsonArray lengthRangeArray = new JsonArray();
            lengthRangeArray.add("content-length-range");
            lengthRangeArray.add(Integer.parseInt(binaryCond.leftOperand));
            lengthRangeArray.add(Integer.parseInt(binaryCond.rightOperand));
            conditionSet.add(lengthRangeArray);
            break;
        }
      }
      rootJson.add("conditions", conditionSet);
      rootJson.addProperty("expiration", expirationDate);

      String serializedString = rootJson.toString();
      StringBuilder escapedBuilder = new StringBuilder();

      // Certain characters in a policy must be escaped
      for (char ch : serializedString.toCharArray()) {
        if (ch >= 128) { // is a unicode character
          escapedBuilder.append(String.format("\\u%04x", (int) ch));
        } else {
          switch (ch) {
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
        }
      }
      return escapedBuilder.toString();
    }
  }

  public enum ConditionTypeV4 {
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
  static final class BinaryConditionV4 {
    ConditionTypeV4 conditionKind;
    String leftOperand;
    String rightOperand;

    private BinaryConditionV4(ConditionTypeV4 conditionKind, String leftOperand, String rightOperand) {
      this.conditionKind = conditionKind;
      this.leftOperand = leftOperand;
      this.rightOperand = rightOperand;
    }

    @Override
    public boolean equals(Object candidate) {
      BinaryConditionV4 binaryCond = (BinaryConditionV4) candidate;
      return this.conditionKind == binaryCond.conditionKind
          && this.leftOperand.equals(binaryCond.leftOperand)
          && this.rightOperand.equals(binaryCond.rightOperand);
    }

    @Override
    public int hashCode() {
      return Objects.hash(conditionKind, leftOperand, rightOperand);
    }
  }
}
