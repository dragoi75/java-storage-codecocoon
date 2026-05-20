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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.Test;

public class PostPolicyV4Test {
  private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

  private void assertNotSameButEqual(Map<String, String> expected, Map<String, String> returned) {
    assertNotSame(expected, returned);
    assertEquals("map sizes", expected.size(), returned.size());
    for (String key : expected.keySet()) {
      assertEquals("value of $" + key, expected.get(key), returned.get(key));
    }
  }

  private static final String[] VALID_FIELDS = {
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
    "x-goog-signature",
  };

  private static final String CUSTOM_PREFIX = "x-goog-meta-";

  private static Map<String, String> initAllFields() {
    Map<String, String> fields = new HashMap<>();
    for (String key : VALID_FIELDS) {
      fields.put(key, "value of " + key);
    }
    fields.put(CUSTOM_PREFIX + "custom", "value of custom field");
    return Collections.unmodifiableMap(fields);
  }

  private static final Map<String, String> ALL_FIELDS = initAllFields();

  @Test
  public void testPostPolicyV4_of() {
    String url = "http://example.com";
    S3PostPolicyV4 policy = S3PostPolicyV4.from(url, ALL_FIELDS);
    assertEquals(url, policy.getUrl());
    assertNotSameButEqual(ALL_FIELDS, policy.getFields());
  }

  @Test
  public void testPostPolicyV4_ofMalformedURL() {
    try {
      S3PostPolicyV4.from("example.com", new HashMap<String, String>());
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("example.com is not an absolute URL", e.getMessage());
    }

    try {
      S3PostPolicyV4.from("Scio nescio", new HashMap<String, String>());
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals(
          "java.net.URISyntaxException: Illegal character in path at index 4: Scio nescio",
          e.getMessage());
    }
  }

  @Test
  public void testPostPolicyV4_ofInvalidField() {
    Map<String, String> fields = new HashMap<>(ALL_FIELDS);
    fields.put("$file", "file.txt");
    try {
      S3PostPolicyV4.from("http://google.com", fields);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Invalid key: $file", e.getMessage());
    }
  }

  @Test
  public void testPostFieldsV4_of() {
    S3PostPolicyV4.PostFieldsMapV4 fields = S3PostPolicyV4.PostFieldsMapV4.from(ALL_FIELDS);
    assertNotSameButEqual(ALL_FIELDS, fields.getFieldsMap());
  }

  @Test
  public void testPostFieldsV4_ofInvalidField() {
    Map<String, String> map = new HashMap<>();
    map.put("$file", "file.txt");
    try {
      S3PostPolicyV4.PostFieldsMapV4.from(map);
      fail();
    } catch (IllegalArgumentException e) {
      assertEquals("Invalid key: $file", e.getMessage());
    }
  }

  @Test
  public void testPostPolicyV4_builder() {
    S3PostPolicyV4.PostFieldsMapV4.UploadFormBuilder builder = S3PostPolicyV4.PostFieldsMapV4.createBuilder();
    builder.setAcl("acl");
    builder.setCacheControl("cache-control");
    builder.setContentDisposition("content-disposition");
    builder.setContentType("content-type");
    builder.setExpires("expires");
    builder.setSuccessActionRedirect("success_action_redirect");
    Map<String, String> map = builder.buildMap().getFieldsMap();
    assertEquals("map size", 6, map.size());
    for (String key : map.keySet()) {
      assertEquals("value of $" + key, key, map.get(key));
    }

    Map<String, String> expectedUpdated = new HashMap<>(map);
    builder.setCustomMetadataField("xxx", "XXX");
    builder.setCustomMetadataField(CUSTOM_PREFIX + "yyy", "YYY");
    builder.setAcl(null);
    builder.setContentType("new-content-type");
    builder.setSuccessActionStatus(42);
    expectedUpdated.put(CUSTOM_PREFIX + "xxx", "XXX");
    expectedUpdated.put(CUSTOM_PREFIX + "yyy", "YYY");
    expectedUpdated.put("acl", null);
    expectedUpdated.put("content-type", "new-content-type");
    expectedUpdated.put("success_action_status", "42");
    Map<String, String> updated = builder.buildMap().getFieldsMap();
    assertNotSameButEqual(expectedUpdated, updated);
  }

  @Test
  public void testPostPolicyV4_setContentLength() {
    S3PostPolicyV4.PostFieldsMapV4.UploadFormBuilder builder = S3PostPolicyV4.PostFieldsMapV4.createBuilder();
    builder.setContentLength(12345);
    assertTrue(builder.buildMap().getFieldsMap().isEmpty());
  }

  @Test
  public void testPostConditionsV4_builder() {
    S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder = S3PostPolicyV4.PostConditionsVersion4.createBuilder();
    assertTrue(builder.buildConditions().getConditions().isEmpty());

    builder.addAcl(S3PostPolicyV4.ConditionV4Kind.STARTS_WITH, "public");
    builder.addBucket(S3PostPolicyV4.ConditionV4Kind.MATCHES, "travel-maps");
    builder.addContentLengthRange(0, 100000);

    S3PostPolicyV4.PostConditionsVersion4 postConditionsV4 = builder.buildConditions();
    Set<S3PostPolicyV4.ConditionalExpressionV4> conditions = postConditionsV4.getConditions();
    assertEquals(3, conditions.size());

    try {
      conditions.clear();
      fail();
    } catch (UnsupportedOperationException e) {
      // expected
    }

    S3PostPolicyV4.PostConditionsVersion4 postConditionsV4Extended =
        postConditionsV4
            .asBuilder()
            .addCustom(S3PostPolicyV4.ConditionV4Kind.STARTS_WITH, "key", "")
            .buildConditions();
    assertEquals(4, postConditionsV4Extended.getConditions().size());
  }

  interface ConditionTest {
    /**
     * Calls one of addCondition method on the given builder and returns expected ConditionV4
     * object.
     */
    S3PostPolicyV4.ConditionalExpressionV4 addCondition(S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder);
  }

  @Test
  public void testPostConditionsV4_addCondition() {
    // shortcuts
    final S3PostPolicyV4.ConditionV4Kind eq = S3PostPolicyV4.ConditionV4Kind.MATCHES;
    final S3PostPolicyV4.ConditionV4Kind startsWith = S3PostPolicyV4.ConditionV4Kind.STARTS_WITH;
    final S3PostPolicyV4.ConditionV4Kind range = S3PostPolicyV4.ConditionV4Kind.CONTENT_LENGTH_RANGE;

    ConditionTest[] cases = {
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addContentLengthRange(123, 456);
          return new S3PostPolicyV4.ConditionalExpressionV4(range, "123", "456");
        }

        @Override
        public String toString() {
          return "addContentLengthRangeCondition()";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          long date = 2000000000000L;
          builder.addExpires(date);
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "expires", dateFormat.format(date));
        }

        @Override
        public String toString() {
          return "addExpiresCondition(long)";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addExpires("2030-Dec-31");
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "expires", "2030-Dec-31");
        }

        @Override
        public String toString() {
          return "addExpiresCondition(String)";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addExpires(range, 0);
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "expires", dateFormat.format(0));
        }

        @Override
        public String toString() {
          return "@deprecated addExpiresCondition(type,long)";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addExpires(startsWith, "2030-Dec-31");
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "expires", "2030-Dec-31");
        }

        @Override
        public String toString() {
          return "@deprecated addExpiresCondition(type,String)";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addSuccessActionStatus(202);
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "success_action_status", "202");
        }

        @Override
        public String toString() {
          return "addSuccessActionStatusCondition(int)";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addSuccessActionStatus(startsWith, 202);
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "success_action_status", "202");
        }

        @Override
        public String toString() {
          return "@deprecated addSuccessActionStatusCondition(type,int)";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addAcl(startsWith, "read");
          return new S3PostPolicyV4.ConditionalExpressionV4(startsWith, "acl", "read");
        }

        @Override
        public String toString() {
          return "addAclCondition()";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addBucket(eq, "my-bucket");
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "bucket", "my-bucket");
        }

        @Override
        public String toString() {
          return "addBucketCondition()";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addCacheControl(eq, "false");
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "cache-control", "false");
        }

        @Override
        public String toString() {
          return "addCacheControlCondition()";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addContentDisposition(startsWith, "gzip");
          return new S3PostPolicyV4.ConditionalExpressionV4(startsWith, "content-disposition", "gzip");
        }

        @Override
        public String toString() {
          return "addContentDispositionCondition()";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addContentEncoding(eq, "koi8");
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "content-encoding", "koi8");
        }

        @Override
        public String toString() {
          return "addContentEncodingCondition()";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addContentType(startsWith, "application/");
          return new S3PostPolicyV4.ConditionalExpressionV4(startsWith, "content-type", "application/");
        }

        @Override
        public String toString() {
          return "addContentTypeCondition()";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addKey(startsWith, "");
          return new S3PostPolicyV4.ConditionalExpressionV4(startsWith, "key", "");
        }

        @Override
        public String toString() {
          return "addKeyCondition()";
        }
      },
      new ConditionTest() {
        @Override
        public S3PostPolicyV4.ConditionalExpressionV4 addCondition(
            S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder) {
          builder.addSuccessActionRedirect(eq, "fail");
          return new S3PostPolicyV4.ConditionalExpressionV4(eq, "success_action_redirect", "fail");
        }

        @Override
        public String toString() {
          return "addSuccessActionRedirectUrlCondition()";
        }
      },
    };

    for (ConditionTest testCase : cases) {
      S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder = S3PostPolicyV4.PostConditionsVersion4.createBuilder();
      S3PostPolicyV4.ConditionalExpressionV4 expected = testCase.addCondition(builder);
      Set<S3PostPolicyV4.ConditionalExpressionV4> conditions = builder.buildConditions().getConditions();
      assertEquals("size", 1, conditions.size());
      S3PostPolicyV4.ConditionalExpressionV4 actual = conditions.toArray(new S3PostPolicyV4.ConditionalExpressionV4[1])[0];
      assertEquals(testCase.toString(), expected, actual);
    }
  }

  @Test
  public void testPostConditionsV4_addConditionFail() {
    final S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder =
        S3PostPolicyV4.PostConditionsVersion4.createBuilder();
    final S3PostPolicyV4.ConditionV4Kind range = S3PostPolicyV4.ConditionV4Kind.CONTENT_LENGTH_RANGE;

    Callable[] cases = {
      new Callable<Void>() {
        @Override
        public Void call() {
          builder.addAcl(range, "");
          return null;
        }

        @Override
        public String toString() {
          return "acl";
        }
      },
      new Callable<Void>() {
        @Override
        public Void call() {
          builder.addBucket(range, "");
          return null;
        }

        @Override
        public String toString() {
          return "bucket";
        }
      },
      new Callable<Void>() {
        @Override
        public Void call() {
          builder.addCacheControl(range, "");
          return null;
        }

        @Override
        public String toString() {
          return "cache-control";
        }
      },
      new Callable<Void>() {
        @Override
        public Void call() {
          builder.addContentDisposition(range, "");
          return null;
        }

        @Override
        public String toString() {
          return "content-disposition";
        }
      },
      new Callable<Void>() {
        @Override
        public Void call() {
          builder.addContentEncoding(range, "");
          return null;
        }

        @Override
        public String toString() {
          return "content-encoding";
        }
      },
      new Callable<Void>() {
        @Override
        public Void call() {
          builder.addContentType(range, "");
          return null;
        }

        @Override
        public String toString() {
          return "content-type";
        }
      },
      new Callable<Void>() {
        @Override
        public Void call() {
          builder.addKey(range, "");
          return null;
        }

        @Override
        public String toString() {
          return "key";
        }
      },
      new Callable<Void>() {
        @Override
        public Void call() {
          builder.addSuccessActionRedirect(range, "");
          return null;
        }

        @Override
        public String toString() {
          return "success_action_redirect";
        }
      },
    };

    for (Callable testCase : cases) {
      try {
        testCase.call();
        fail();
      } catch (Exception e) {
        String expected =
            "java.lang.IllegalArgumentException: Field "
                + testCase
                + " can't use content-length-range";
        assertEquals(expected, e.toString());
      }
    }
    assertTrue(builder.buildConditions().getConditions().isEmpty());
  }

  @Test
  public void testPostConditionsV4_toString() {
    S3PostPolicyV4.PostConditionsVersion4.PolicyBuilder builder = S3PostPolicyV4.PostConditionsVersion4.createBuilder();
    builder.addKey(S3PostPolicyV4.ConditionV4Kind.MATCHES, "test-object");
    builder.addAcl(S3PostPolicyV4.ConditionV4Kind.STARTS_WITH, "public");
    builder.addContentLengthRange(246, 266);

    Set<String> toStringSet = new HashSet<>();
    for (S3PostPolicyV4.ConditionalExpressionV4 conditionV4 : builder.buildConditions().getConditions()) {
      toStringSet.add(conditionV4.toString());
    }
    assertEquals(3, toStringSet.size());

    String[] expectedStrings = {
      "[\"eq\", \"$key\", \"test-object\"]",
      "[\"starts-with\", \"$acl\", \"public\"]",
      "[\"content-length-range\", 246, 266]"
    };

    for (String expected : expectedStrings) {
      assertTrue(expected + "/" + toStringSet, toStringSet.contains(expected));
    }
  }

  @Test
  public void testPostPolicyV4Document_of_toJson() {
    S3PostPolicyV4.PostConditionsVersion4 emptyConditions =
        S3PostPolicyV4.PostConditionsVersion4.createBuilder().buildConditions();
    S3PostPolicyV4.PostPolicyV4JsonDocument emptyDocument =
        S3PostPolicyV4.PostPolicyV4JsonDocument.create("", emptyConditions);
    String emptyJson = emptyDocument.toJsonString();
    assertEquals(emptyJson, "{\"conditions\":[],\"expiration\":\"\"}");

    S3PostPolicyV4.PostConditionsVersion4 postConditionsV4 =
        S3PostPolicyV4.PostConditionsVersion4.createBuilder()
            .addBucket(S3PostPolicyV4.ConditionV4Kind.MATCHES, "my-bucket")
            .addKey(S3PostPolicyV4.ConditionV4Kind.STARTS_WITH, "")
            .addContentLengthRange(1, 1000)
            .buildConditions();

    String expiration = dateFormat.format(System.currentTimeMillis());
    S3PostPolicyV4.PostPolicyV4JsonDocument document =
        S3PostPolicyV4.PostPolicyV4JsonDocument.create(expiration, postConditionsV4);
    String json = document.toJsonString();
    assertEquals(
        json,
        "{\"conditions\":[{\"bucket\":\"my-bucket\"},[\"starts-with\",\"$key\",\"\"],[\"content-length-range\",1,1000]],\"expiration\":\""
            + expiration
            + "\"}");
  }
}
