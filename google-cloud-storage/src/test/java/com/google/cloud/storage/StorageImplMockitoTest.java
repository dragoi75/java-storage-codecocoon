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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.google.api.core.ApiClock;
import com.google.api.gax.paging.Page;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.ReadChannel;
import com.google.cloud.ServiceOptions;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.spi.StorageRpcFactory;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.BaseEncoding;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class StorageImplMockitoTest {

  private static final String BUCKET_NAME1 = "b1";
  private static final String BUCKET_NAME2 = "b2";
  private static final String BUCKET_NAME3 = "b3";
  private static final String BLOB_NAME1 = "n1";
  private static final String BLOB_NAME2 = "n2";
  private static final String BLOB_NAME3 = "n3";
  private static final byte[] BLOB_CONTENT = {0xD, 0xE, 0xA, 0xD};
  private static final byte[] BLOB_SUB_CONTENT = {0xE, 0xA};
  private static final String CONTENT_MD5 = "O1R4G1HJSDUISJjoIYmVhQ==";
  private static final String CONTENT_CRC32C = "9N3EPQ==";
  private static final String SUB_CONTENT_MD5 = "5e7c7CdasUiOn3BO560jPg==";
  private static final String SUB_CONTENT_CRC32C = "bljNYA==";
  private static final int DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024;
  private static final String BASE64_KEY = "JVzfVl8NLD9FjedFuStegjRfES5ll5zc59CIXw572OA=";
  private static final Key KEY =
      new SecretKeySpec(BaseEncoding.base64().decode(BASE64_KEY), "AES256");
  private static final String KMS_KEY_NAME =
      "projects/gcloud-devel/locations/us/keyRings/gcs_kms_key_ring_us/cryptoKeys/key";
  private static final Long RETENTION_PERIOD = 10L;
  private static final String USER_PROJECT = "test-project";
  private static final int DEFAULT_BUFFER_SIZE = 15 * 1024 * 1024;
  private static final int MIN_BUFFER_SIZE = 256 * 1024;
  // BucketInfo objects
  private static final BucketInfo BUCKET_INFO1 =
      BucketInfo.newBuilder(BUCKET_NAME1).setMetageneration(42L).build();
  private static final BucketInfo BUCKET_INFO2 = BucketInfo.newBuilder(BUCKET_NAME2).build();
  private static final BucketInfo BUCKET_INFO3 =
      BucketInfo.newBuilder(BUCKET_NAME3)
          .setRetentionPeriod(RETENTION_PERIOD)
          .setRetentionPolicyIsLocked(true)
          .setMetageneration(42L)
          .build();

  // BlobInfo objects
  private static final BlobInfo BLOB_INFO1 =
      BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME1, 24L)
          .setMetageneration(42L)
          .setContentType("application/json")
          .setMd5("md5string")
          .build();
  private static final BlobInfo BLOB_INFO2 = BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME2).build();
  private static final BlobInfo BLOB_INFO3 = BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME3).build();

  private static final BlobInfo BLOB_INFO_WITH_HASHES =
      BLOB_INFO1.toBuilder().setMd5(CONTENT_MD5).setCrc32c(CONTENT_CRC32C).build();
  private static final BlobInfo BLOB_INFO_WITHOUT_HASHES =
      BLOB_INFO1.toBuilder().setMd5(null).setCrc32c(null).build();

  // Empty StorageRpc options
  private static final Map<StorageRpc.RequestOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  // Bucket target options
  private static final Storage.BucketTargetOptions BUCKET_TARGET_METAGENERATION =
      Storage.BucketTargetOptions.ifMetagenerationMatch();
  private static final Storage.BucketTargetOptions BUCKET_TARGET_PREDEFINED_ACL =
      Storage.BucketTargetOptions.withPredefinedAcl(Storage.PredefinedAccessControlList.PRIVATE);
  private static final Storage.BucketTargetOptions BUCKET_TARGET_USER_PROJECT =
      Storage.BucketTargetOptions.withUserProject(USER_PROJECT);
  private static final Map<StorageRpc.RequestOption, ?> BUCKET_TARGET_OPTIONS =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH, BUCKET_INFO1.getMetageneration(),
          StorageRpc.RequestOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageRpc.RequestOption, ?> BUCKET_TARGET_OPTIONS_LOCK_RETENTION_POLICY =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH,
          BUCKET_INFO3.getMetageneration(),
          StorageRpc.RequestOption.USER_PROJECT,
          USER_PROJECT);

  // StorageObject target options (create, save, compose)
  private static final Storage.BlobTargetOptions BLOB_TARGET_GENERATION =
      Storage.BlobTargetOptions.ifGenerationMatch();
  private static final Storage.BlobTargetOptions BLOB_TARGET_METAGENERATION =
      Storage.BlobTargetOptions.ifMetagenerationMatch();
  private static final Storage.BlobTargetOptions BLOB_TARGET_DISABLE_GZIP_CONTENT =
      Storage.BlobTargetOptions.disableGzipCompression();
  private static final Storage.BlobTargetOptions BLOB_TARGET_NOT_EXIST =
      Storage.BlobTargetOptions.ifNotExists();
  private static final Storage.BlobTargetOptions BLOB_TARGET_PREDEFINED_ACL =
      Storage.BlobTargetOptions.withPredefinedAcl(Storage.PredefinedAccessControlList.PRIVATE);
  private static final Map<StorageRpc.RequestOption, ?> BLOB_TARGET_OPTIONS_CREATE =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          StorageRpc.RequestOption.IF_GENERATION_MATCH, 0L,
          StorageRpc.RequestOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageRpc.RequestOption, ?> BLOB_TARGET_OPTIONS_CREATE_DISABLE_GZIP_CONTENT =
      ImmutableMap.of(StorageRpc.RequestOption.IF_DISABLE_GZIP_CONTENT, true);
  private static final Map<StorageRpc.RequestOption, ?> BLOB_TARGET_OPTIONS_UPDATE =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          StorageRpc.RequestOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageRpc.RequestOption, ?> BLOB_TARGET_OPTIONS_COMPOSE =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_GENERATION_MATCH, BLOB_INFO1.getGeneration(),
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration());

  // StorageObject write options (create, getWriter)
  private static final Storage.BlobWriteOptions BLOB_WRITE_METAGENERATION =
      Storage.BlobWriteOptions.ifMetagenerationMatch();
  private static final Storage.BlobWriteOptions BLOB_WRITE_NOT_EXIST =
      Storage.BlobWriteOptions.ifNotExists();
  private static final Storage.BlobWriteOptions BLOB_WRITE_PREDEFINED_ACL =
      Storage.BlobWriteOptions.setPredefinedAcl(Storage.PredefinedAccessControlList.PRIVATE);
  private static final Storage.BlobWriteOptions BLOB_WRITE_MD5_HASH =
      Storage.BlobWriteOptions.ifMd5Match();
  private static final Storage.BlobWriteOptions BLOB_WRITE_CRC2C =
      Storage.BlobWriteOptions.ifCrc32cMatch();

  // Bucket get/source options
  private static final Storage.BucketReadOption BUCKET_SOURCE_METAGENERATION =
      Storage.BucketReadOption.ifMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Map<StorageRpc.RequestOption, ?> BUCKET_SOURCE_OPTIONS =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());
  private static final Storage.GetBucketOption BUCKET_GET_METAGENERATION =
      Storage.GetBucketOption.ifMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Storage.GetBucketOption BUCKET_GET_FIELDS =
      Storage.GetBucketOption.withFields(Storage.BucketFields.LOCATION, Storage.BucketFields.ACL);
  private static final Storage.GetBucketOption BUCKET_GET_EMPTY_FIELDS =
      Storage.GetBucketOption.withFields();
  private static final Map<StorageRpc.RequestOption, ?> BUCKET_GET_OPTIONS =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());

  // StorageObject get/source options
  private static final Storage.BlobFetchOption BLOB_GET_METAGENERATION =
      Storage.BlobFetchOption.matchMetageneration(BLOB_INFO1.getMetageneration());
  private static final Storage.BlobFetchOption BLOB_GET_GENERATION =
      Storage.BlobFetchOption.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final Storage.BlobFetchOption BLOB_GET_GENERATION_FROM_BLOB_ID =
      Storage.BlobFetchOption.ifGenerationMatch();
  private static final Storage.BlobFetchOption BLOB_GET_FIELDS =
      Storage.BlobFetchOption.withFields(Storage.BlobMetadataField.CONTENT_TYPE, Storage.BlobMetadataField.CRC32C);
  private static final Storage.BlobFetchOption BLOB_GET_EMPTY_FIELDS = Storage.BlobFetchOption.withFields();
  private static final Map<StorageRpc.RequestOption, ?> BLOB_GET_OPTIONS =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH, BLOB_GET_METAGENERATION.getValue(),
          StorageRpc.RequestOption.IF_GENERATION_MATCH, BLOB_GET_GENERATION.getValue());
  private static final Storage.BlobReadOption BLOB_SOURCE_METAGENERATION =
      Storage.BlobReadOption.ifMetagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final Storage.BlobReadOption BLOB_SOURCE_GENERATION =
      Storage.BlobReadOption.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final Storage.BlobReadOption BLOB_SOURCE_GENERATION_FROM_BLOB_ID =
      Storage.BlobReadOption.ifGenerationMatch();
  private static final Map<StorageRpc.RequestOption, ?> BLOB_SOURCE_OPTIONS =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_METAGENERATION_MATCH, BLOB_SOURCE_METAGENERATION.getValue(),
          StorageRpc.RequestOption.IF_GENERATION_MATCH, BLOB_SOURCE_GENERATION.getValue());
  private static final Map<StorageRpc.RequestOption, ?> BLOB_SOURCE_OPTIONS_COPY =
      ImmutableMap.of(
          StorageRpc.RequestOption.IF_SOURCE_METAGENERATION_MATCH, BLOB_SOURCE_METAGENERATION.getValue(),
          StorageRpc.RequestOption.IF_SOURCE_GENERATION_MATCH, BLOB_SOURCE_GENERATION.getValue());

  // Bucket list options
  private static final Storage.BucketListOptions BUCKET_LIST_PAGE_SIZE =
      Storage.BucketListOptions.maxResults(42L);
  private static final Storage.BucketListOptions BUCKET_LIST_PREFIX =
      Storage.BucketListOptions.withPrefix("withPrefix");
  private static final Storage.BucketListOptions BUCKET_LIST_FIELDS =
      Storage.BucketListOptions.fieldNames(Storage.BucketFields.LOCATION, Storage.BucketFields.ACL);
  private static final Storage.BucketListOptions BUCKET_LIST_EMPTY_FIELDS =
      Storage.BucketListOptions.fieldNames();
  private static final Map<StorageRpc.RequestOption, ?> BUCKET_LIST_OPTIONS =
      ImmutableMap.of(
          StorageRpc.RequestOption.MAX_RESULTS, BUCKET_LIST_PAGE_SIZE.getValue(),
          StorageRpc.RequestOption.PREFIX, BUCKET_LIST_PREFIX.getValue());

  // StorageObject list options
  private static final Storage.ListBlobsOption BLOB_LIST_PAGE_SIZE =
      Storage.ListBlobsOption.maxResults(42L);
  private static final Storage.ListBlobsOption BLOB_LIST_PREFIX =
      Storage.ListBlobsOption.withPrefix("withPrefix");
  private static final Storage.ListBlobsOption BLOB_LIST_FIELDS =
      Storage.ListBlobsOption.withFields(Storage.BlobMetadataField.CONTENT_TYPE, Storage.BlobMetadataField.MD5HASH);
  private static final Storage.ListBlobsOption BLOB_LIST_VERSIONS =
      Storage.ListBlobsOption.includeVersions(false);
  private static final Storage.ListBlobsOption BLOB_LIST_EMPTY_FIELDS =
      Storage.ListBlobsOption.withFields();
  private static final Map<StorageRpc.RequestOption, ?> BLOB_LIST_OPTIONS =
      ImmutableMap.of(
          StorageRpc.RequestOption.MAX_RESULTS, BLOB_LIST_PAGE_SIZE.getValue(),
          StorageRpc.RequestOption.PREFIX, BLOB_LIST_PREFIX.getValue(),
          StorageRpc.RequestOption.VERSIONS, BLOB_LIST_VERSIONS.getValue());

  // ACLs
  private static final Acl ACL = Acl.of(Acl.User.ofAllAuthenticatedUsers(), Acl.Role.OWNER);
  private static final Acl OTHER_ACL =
      Acl.of(new Acl.Project(Acl.Project.ProjectRole.OWNERS, "p"), Acl.Role.READER);

  // Customer supplied encryption key options
  private static final Map<StorageRpc.RequestOption, ?> ENCRYPTION_KEY_OPTIONS =
      ImmutableMap.of(StorageRpc.RequestOption.CUSTOMER_SUPPLIED_KEY, BASE64_KEY);

  // Customer managed encryption key options
  private static final Map<StorageRpc.RequestOption, ?> KMS_KEY_NAME_OPTIONS =
      ImmutableMap.of(StorageRpc.RequestOption.KMS_KEY_NAME, KMS_KEY_NAME);
  // IAM policies
  private static final String POLICY_ETAG1 = "CAE=";
  private static final String POLICY_ETAG2 = "CAI=";
  private static final Policy LIB_POLICY1 =
      Policy.newBuilder()
          .addIdentity(StorageRoles.objectViewer(), Identity.allUsers())
          .addIdentity(
              StorageRoles.objectAdmin(),
              Identity.user("test1@gmail.com"),
              Identity.user("test2@gmail.com"))
          .setEtag(POLICY_ETAG1)
          .setVersion(1)
          .build();

  private static final ServiceAccount SERVICE_ACCOUNT = ServiceAccount.of("test@google.com");

  private static final com.google.api.services.storage.model.Policy API_POLICY1 =
      new com.google.api.services.storage.model.Policy()
          .setBindings(
              ImmutableList.of(
                  new com.google.api.services.storage.model.Policy.Bindings()
                      .setMembers(ImmutableList.of("allUsers"))
                      .setRole("roles/storage.objectViewer"),
                  new com.google.api.services.storage.model.Policy.Bindings()
                      .setMembers(ImmutableList.of("user:test1@gmail.com", "user:test2@gmail.com"))
                      .setRole("roles/storage.objectAdmin")))
          .setEtag(POLICY_ETAG1)
          .setVersion(1);

  private static final String PRIVATE_KEY_STRING =
      "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoG"
          + "BAL2xolH1zrISQ8+GzOV29BNjjzq4/HIP8Psd1+cZb81vDklSF+95wB250MSE0BDc81pvIMwj5OmIfLg1NY6uB"
          + "1xavOPpVdx1z664AGc/BEJ1zInXGXaQ6s+SxGenVq40Yws57gikQGMZjttpf1Qbz4DjkxsbRoeaRHn06n9pH1e"
          + "jAgMBAAECgYEAkWcm0AJF5LMhbWKbjkxm/LG06UNApkHX6vTOOOODkonM/qDBnhvKCj8Tan+PaU2j7679Cd19q"
          + "xCm4SBQJET7eBhqLD9L2j9y0h2YUQnLbISaqUS1/EXcr2C1Lf9VCEn1y/GYuDYqs85rGoQ4ZYfM9ClROSq86fH"
          + "+cbIIssqJqukCQQD18LjfJz/ichFeli5/l1jaFid2XoCH3T6TVuuysszVx68fh60gSIxEF/0X2xB+wuPxTP4IQ"
          + "+t8tD/ktd232oWXAkEAxXPych2QBHePk9/lek4tOkKBgfnDzex7S/pI0G1vpB3VmzBbCsokn9lpOv7JV8071GD"
          + "lW/7R6jlLfpQy3hN31QJAE10osSk99m5Uv8XDU3hvHnywDrnSFOBulNs7I47AYfSe7TSZhPkxUgsxejddTR27J"
          + "LyTI8N1PxRSE4feNSOXcQJAMMKJRJT4U6IS2rmXubREhvaVdLtxFxEnAYQ1JwNfZm/XqBMw6GEy2iaeTetNXVl"
          + "ZRQEIoscyn1y2v/No/F5iYQJBAKBOGASoQcBjGTOg/H/SfcE8QVNsKEpthRrs6CkpT80aZ/AV+ksfoIf2zw2M3"
          + "mAHfrO+TBLdz4sicuFQvlN9SEc=";

  private static final String PUBLIC_KEY_STRING =
      "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC9saJR9c6y"
          + "EkPPhszldvQTY486uPxyD/D7HdfnGW/Nbw5JUhfvecAdudDEhNAQ3PNabyDMI+TpiHy4NTWOrgdcWrzj6VXcdc"
          + "+uuABnPwRCdcyJ1xl2kOrPksRnp1auNGMLOe4IpEBjGY7baX9UG8+A45MbG0aHmkR59Op/aR9XowIDAQAB";

  private static final String SIGNED_URL =
      "http://www.test.com/test-bucket/test1.txt?GoogleAccessId=testClient-test@test.com&Expires=1553839761&Signature=MJUBXAZ7";

  private static final ApiClock TIME_SOURCE =
      new ApiClock() {
        @Override
        public long nanoTime() {
          return 42_000_000_000L;
        }

        @Override
        public long millisTime() {
          return 42_000L;
        }
      };

  // List from chars under test were taken from
  // https://en.wikipedia.org/wiki/Percent-encoding#Percent-encoding_reserved_characters
  private static final Map<Character, String> RFC3986_URI_ENCODING_MAP =
      ImmutableMap.<Character, String>builder()
          .put('!', "%21")
          .put('#', "%23")
          .put('$', "%24")
          .put('&', "%26")
          .put('\'', "%27")
          .put('(', "%28")
          .put(')', "%29")
          .put('*', "%2A")
          .put('+', "%2B")
          .put(',', "%2C")
          // NOTE: Whether the forward slash character should be encoded depends on the URI segment
          // being encoded. The path segment should not encode forward slashes, but others (e.g.
          // query parameter keys and values) should encode them. Tests verifying encoding behavior
          // in path segments should make a copy from this map and replace the mapping for '/' to "/".
          .put('/', "%2F")
          .put(':', "%3A")
          .put(';', "%3B")
          .put('=', "%3D")
          .put('?', "%3F")
          .put('@', "%40")
          .put('[', "%5B")
          .put(']', "%5D")
          // In addition to [a-zA-Z0-9], these chars should not be URI-encoded:
          .put('-', "-")
          .put('_', "_")
          .put('.', ".")
          .put('~', "~")
          .build();

  private static final String ACCOUNT = "account";
  private static PrivateKey privateKey;
  private static PublicKey publicKey;

  private StorageOptions options;
  private StorageRpcFactory rpcFactoryMock;
  private StorageRpc storageRpcMock;
  private Storage storage;

  private StorageObject expectedBlob1, expectedBlob2, expectedBlob3, expectedUpdated;
  private Bucket expectedBucket1, expectedBucket2, expectedBucket3;

  @BeforeClass
  public static void beforeClass() throws NoSuchAlgorithmException, InvalidKeySpecException {
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    EncodedKeySpec privateKeySpec =
        new PKCS8EncodedKeySpec(BaseEncoding.base64().decode(PRIVATE_KEY_STRING));
    privateKey = keyFactory.generatePrivate(privateKeySpec);
    EncodedKeySpec publicKeySpec =
        new X509EncodedKeySpec(BaseEncoding.base64().decode(PUBLIC_KEY_STRING));
    publicKey = keyFactory.generatePublic(publicKeySpec);
  }

  private static final RuntimeException STORAGE_FAILURE =
      new RuntimeException("Something went wrong");

  private static final RuntimeException UNEXPECTED_CALL_EXCEPTION =
      new RuntimeException("Unexpected call");
  private static final Answer UNEXPECTED_CALL_ANSWER =
      new Answer<Object>() {
        @Override
        public Object answer(InvocationOnMock invocation) {
          throw new IllegalArgumentException(
              "Unexpected call from "
                  + invocation.getMethod()
                  + " with "
                  + Arrays.toString(invocation.getArguments()));
        };
      };

  @Before
  public void setUp() {
    rpcFactoryMock = mock(StorageRpcFactory.class, UNEXPECTED_CALL_ANSWER);
    storageRpcMock = mock(StorageRpc.class, UNEXPECTED_CALL_ANSWER);
    doReturn(storageRpcMock).when(rpcFactoryMock).create(Mockito.any(StorageOptions.class));
    options =
        StorageOptions.newBuilder()
            .setProjectId("projectId")
            .setClock(TIME_SOURCE)
            .setServiceRpcFactory(rpcFactoryMock)
            .setRetrySettings(ServiceOptions.getNoRetrySettings())
            .build();
  }

  private void initializeService() {
    storage = options.getService();
    initializeServiceDependentObjects();
  }

  private void initializeServiceDependentObjects() {
    expectedBlob1 = new StorageObject(storage, new BlobInfo.BuilderImpl(BLOB_INFO1));
    expectedBlob2 = new StorageObject(storage, new BlobInfo.BuilderImpl(BLOB_INFO2));
    expectedBlob3 = new StorageObject(storage, new BlobInfo.BuilderImpl(BLOB_INFO3));
    expectedBucket1 = new Bucket(storage, new BucketInfo.BuilderImpl(BUCKET_INFO1));
    expectedBucket2 = new Bucket(storage, new BucketInfo.BuilderImpl(BUCKET_INFO2));
    expectedBucket3 = new Bucket(storage, new BucketInfo.BuilderImpl(BUCKET_INFO3));
    expectedUpdated = null;
  }

  @Test
  public void testGetOptions() {
    initializeService();
    assertSame(options, storage.getOptions());
  }

  @Test
  public void testCreateBucket() {
    doReturn(BUCKET_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(BUCKET_INFO1.toPb(), EMPTY_RPC_OPTIONS);
    initializeService();
    Bucket bucket = storage.create(BUCKET_INFO1);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testCreateBucketWithOptions() {
    doReturn(BUCKET_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(BUCKET_INFO1.toPb(), BUCKET_TARGET_OPTIONS);
    initializeService();
    Bucket bucket =
        storage.create(BUCKET_INFO1, BUCKET_TARGET_METAGENERATION, BUCKET_TARGET_PREDEFINED_ACL);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testCreateBucketFailure() {
    doThrow(STORAGE_FAILURE).when(storageRpcMock).create(BUCKET_INFO1.toPb(), EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.create(BUCKET_INFO1);
      fail();
    } catch (StorageException e) {
      assertEquals(STORAGE_FAILURE, e.getCause());
    }
  }

  @Test
  public void testGetBucket() {
    doReturn(BUCKET_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BucketInfo.of(BUCKET_NAME1).toPb(), EMPTY_RPC_OPTIONS);
    initializeService();
    Bucket bucket = storage.get(BUCKET_NAME1);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testGetBucketWithOptions() {
    doReturn(BUCKET_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BucketInfo.of(BUCKET_NAME1).toPb(), BUCKET_GET_OPTIONS);
    initializeService();
    Bucket bucket = storage.get(BUCKET_NAME1, BUCKET_GET_METAGENERATION);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testGetBucketWithSelectedFields() {
    ArgumentCaptor<Map<StorageRpc.RequestOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    doReturn(BUCKET_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(Mockito.eq(BucketInfo.of(BUCKET_NAME1).toPb()), capturedOptions.capture());
    initializeService();
    Bucket bucket = storage.get(BUCKET_NAME1, BUCKET_GET_METAGENERATION, BUCKET_GET_FIELDS);
    assertEquals(
        BUCKET_GET_METAGENERATION.getValue(),
        capturedOptions.getValue().get(BUCKET_GET_METAGENERATION.getRpcOption()));
    String selector = (String) capturedOptions.getValue().get(BLOB_GET_FIELDS.getRpcOption());
    assertTrue(selector.contains("name"));
    assertTrue(selector.contains("location"));
    assertTrue(selector.contains("acl"));
    assertEquals(17, selector.length());
    assertEquals(BUCKET_INFO1.getName(), bucket.getName());
  }

  @Test
  public void testGetBucketWithEmptyFields() {
    ArgumentCaptor<Map<StorageRpc.RequestOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    doReturn(BUCKET_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(Mockito.eq(BucketInfo.of(BUCKET_NAME1).toPb()), capturedOptions.capture());
    initializeService();
    Bucket bucket = storage.get(BUCKET_NAME1, BUCKET_GET_METAGENERATION, BUCKET_GET_EMPTY_FIELDS);
    assertEquals(
        BUCKET_GET_METAGENERATION.getValue(),
        capturedOptions.getValue().get(BUCKET_GET_METAGENERATION.getRpcOption()));
    String selector = (String) capturedOptions.getValue().get(BLOB_GET_FIELDS.getRpcOption());
    assertTrue(selector.contains("name"));
    assertEquals(4, selector.length());
    assertEquals(BUCKET_INFO1.getName(), bucket.getName());
  }

  @Test
  public void testGetBucketFailure() {
    doThrow(STORAGE_FAILURE)
        .when(storageRpcMock)
        .get(BucketInfo.of(BUCKET_NAME1).toPb(), EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.get(BUCKET_NAME1);
      fail();
    } catch (StorageException e) {
      assertEquals(STORAGE_FAILURE, e.getCause());
    }
  }

  @Test
  public void testGetBlob() {
    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BlobId.of(BUCKET_NAME1, BLOB_NAME1).toPb(), EMPTY_RPC_OPTIONS);
    initializeService();
    StorageObject blob = storage.get(BUCKET_NAME1, BLOB_NAME1);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testGetBlobWithOptions() {
    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BlobId.of(BUCKET_NAME1, BLOB_NAME1).toPb(), BLOB_GET_OPTIONS);
    initializeService();
    StorageObject blob = storage.get(BUCKET_NAME1, BLOB_NAME1, BLOB_GET_METAGENERATION, BLOB_GET_GENERATION);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testGetBlobWithOptionsFromBlobId() {
    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BLOB_INFO1.getBlobId().toPb(), BLOB_GET_OPTIONS);
    initializeService();
    StorageObject blob =
        storage.get(
            BLOB_INFO1.getBlobId(), BLOB_GET_METAGENERATION, BLOB_GET_GENERATION_FROM_BLOB_ID);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testGetBlobWithSelectedFields() {
    ArgumentCaptor<Map<StorageRpc.RequestOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(Mockito.eq(BlobId.of(BUCKET_NAME1, BLOB_NAME1).toPb()), capturedOptions.capture());
    initializeService();
    StorageObject blob =
        storage.get(
            BUCKET_NAME1,
            BLOB_NAME1,
            BLOB_GET_METAGENERATION,
            BLOB_GET_GENERATION,
            BLOB_GET_FIELDS);
    assertEquals(
        BLOB_GET_METAGENERATION.getValue(),
        capturedOptions.getValue().get(BLOB_GET_METAGENERATION.getRpcOption()));
    assertEquals(
        BLOB_GET_GENERATION.getValue(),
        capturedOptions.getValue().get(BLOB_GET_GENERATION.getRpcOption()));
    String selector = (String) capturedOptions.getValue().get(BLOB_GET_FIELDS.getRpcOption());
    assertTrue(selector.contains("bucket"));
    assertTrue(selector.contains("name"));
    assertTrue(selector.contains("contentType"));
    assertTrue(selector.contains("crc32c"));
    assertEquals(30, selector.length());
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testGetBlobWithEmptyFields() {
    ArgumentCaptor<Map<StorageRpc.RequestOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(Mockito.eq(BlobId.of(BUCKET_NAME1, BLOB_NAME1).toPb()), capturedOptions.capture());
    initializeService();
    StorageObject blob =
        storage.get(
            BUCKET_NAME1,
            BLOB_NAME1,
            BLOB_GET_METAGENERATION,
            BLOB_GET_GENERATION,
            BLOB_GET_EMPTY_FIELDS);
    assertEquals(
        BLOB_GET_METAGENERATION.getValue(),
        capturedOptions.getValue().get(BLOB_GET_METAGENERATION.getRpcOption()));
    assertEquals(
        BLOB_GET_GENERATION.getValue(),
        capturedOptions.getValue().get(BLOB_GET_GENERATION.getRpcOption()));
    String selector = (String) capturedOptions.getValue().get(BLOB_GET_FIELDS.getRpcOption());
    assertTrue(selector.contains("bucket"));
    assertTrue(selector.contains("name"));
    assertEquals(11, selector.length());
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testGetBlobFailure() {
    doThrow(STORAGE_FAILURE)
        .when(storageRpcMock)
        .get(BlobId.of(BUCKET_NAME1, BLOB_NAME1).toPb(), EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.get(BUCKET_NAME1, BLOB_NAME1);
      fail();
    } catch (StorageException e) {
      assertEquals(STORAGE_FAILURE, e.getCause());
    }
  }

  private void verifyCreateBlobCapturedStream(ArgumentCaptor<ByteArrayInputStream> capturedStream)
      throws IOException {
    ByteArrayInputStream byteStream = capturedStream.getValue();
    byte[] streamBytes = new byte[BLOB_CONTENT.length];
    assertEquals(BLOB_CONTENT.length, byteStream.read(streamBytes));
    assertArrayEquals(BLOB_CONTENT, streamBytes);
    assertEquals(-1, byteStream.read(streamBytes));
  }

  @Test
  public void testCreateBlob() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);
    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toPb()),
            capturedStream.capture(),
            Mockito.eq(EMPTY_RPC_OPTIONS));
    initializeService();

    StorageObject blob = storage.create(BLOB_INFO1, BLOB_CONTENT);

    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  public void testCreateBlobWithSubArrayFromByteArray() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);
    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(
                BLOB_INFO1
                    .toBuilder()
                    .setMd5(SUB_CONTENT_MD5)
                    .setCrc32c(SUB_CONTENT_CRC32C)
                    .build()
                    .toPb()),
            capturedStream.capture(),
            Mockito.eq(EMPTY_RPC_OPTIONS));
    initializeService();

    StorageObject blob = storage.create(BLOB_INFO1, BLOB_CONTENT, 1, 2);

    assertEquals(expectedBlob1, blob);
    ByteArrayInputStream byteStream = capturedStream.getValue();
    byte[] streamBytes = new byte[BLOB_SUB_CONTENT.length];
    assertEquals(BLOB_SUB_CONTENT.length, byteStream.read(streamBytes));
    assertArrayEquals(BLOB_SUB_CONTENT, streamBytes);
    assertEquals(-1, byteStream.read(streamBytes));
  }

  @Test
  public void testCreateBlobRetry() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    com.google.api.services.storage.model.StorageObject storageObject = BLOB_INFO_WITH_HASHES.toPb();

    doThrow(new StorageException(500, "internalError"))
        .doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(Mockito.eq(storageObject), capturedStream.capture(), Mockito.eq(EMPTY_RPC_OPTIONS));

    storage =
        options
            .toBuilder()
            .setRetrySettings(ServiceOptions.getDefaultRetrySettings())
            .build()
            .getService();
    initializeServiceDependentObjects();

    StorageObject blob = storage.create(BLOB_INFO1, BLOB_CONTENT);

    assertEquals(expectedBlob1, blob);

    byte[] streamBytes = new byte[BLOB_CONTENT.length];
    for (ByteArrayInputStream byteStream : capturedStream.getAllValues()) {
      assertEquals(BLOB_CONTENT.length, byteStream.read(streamBytes));
      assertArrayEquals(BLOB_CONTENT, streamBytes);
      assertEquals(-1, byteStream.read(streamBytes));
    }
  }

  @Test
  public void testCreateEmptyBlob() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(
                BLOB_INFO1
                    .toBuilder()
                    .setMd5("1B2M2Y8AsgTpgAmY7PhCfg==")
                    .setCrc32c("AAAAAA==")
                    .build()
                    .toPb()),
            capturedStream.capture(),
            Mockito.eq(EMPTY_RPC_OPTIONS));
    initializeService();

    StorageObject blob = storage.create(BLOB_INFO1);
    assertEquals(expectedBlob1, blob);
    ByteArrayInputStream byteStream = capturedStream.getValue();
    byte[] streamBytes = new byte[BLOB_CONTENT.length];
    assertEquals(-1, byteStream.read(streamBytes));
  }

  @Test
  public void testCreateBlobWithOptions() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toPb()),
            capturedStream.capture(),
            Mockito.eq(BLOB_TARGET_OPTIONS_CREATE));
    initializeService();

    StorageObject blob =
        storage.create(
            BLOB_INFO1,
            BLOB_CONTENT,
            BLOB_TARGET_METAGENERATION,
            BLOB_TARGET_NOT_EXIST,
            BLOB_TARGET_PREDEFINED_ACL);
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  public void testCreateBlobWithDisabledGzipContent() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toPb()),
            capturedStream.capture(),
            Mockito.eq(BLOB_TARGET_OPTIONS_CREATE_DISABLE_GZIP_CONTENT));
    initializeService();

    StorageObject blob = storage.create(BLOB_INFO1, BLOB_CONTENT, BLOB_TARGET_DISABLE_GZIP_CONTENT);
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  public void testCreateBlobWithEncryptionKey() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    doReturn(BLOB_INFO1.toPb())
        .doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toPb()),
            capturedStream.capture(),
            Mockito.eq(ENCRYPTION_KEY_OPTIONS));
    initializeService();

    StorageObject blob =
        storage.create(BLOB_INFO1, BLOB_CONTENT, Storage.BlobTargetOptions.customerSuppliedKey(KEY));
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
    blob =
        storage.create(
            BLOB_INFO1, BLOB_CONTENT, Storage.BlobTargetOptions.customerSuppliedKey(BASE64_KEY));
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  public void testCreateBlobWithKmsKeyName() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    doReturn(BLOB_INFO1.toPb())
        .doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toPb()),
            capturedStream.capture(),
            Mockito.eq(KMS_KEY_NAME_OPTIONS));
    initializeService();

    StorageObject blob =
        storage.create(BLOB_INFO1, BLOB_CONTENT, Storage.BlobTargetOptions.kmsKey(KMS_KEY_NAME));
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
    blob =
        storage.create(BLOB_INFO1, BLOB_CONTENT, Storage.BlobTargetOptions.kmsKey(KMS_KEY_NAME));
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobFromStream() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    ByteArrayInputStream fileStream = new ByteArrayInputStream(BLOB_CONTENT);

    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITHOUT_HASHES.toPb()),
            capturedStream.capture(),
            Mockito.eq(EMPTY_RPC_OPTIONS));
    initializeService();

    StorageObject blob = storage.create(BLOB_INFO_WITH_HASHES, fileStream);

    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobFromStreamDisableGzipContent() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    ByteArrayInputStream fileStream = new ByteArrayInputStream(BLOB_CONTENT);
    doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITHOUT_HASHES.toPb()),
            capturedStream.capture(),
            Mockito.eq(BLOB_TARGET_OPTIONS_CREATE_DISABLE_GZIP_CONTENT));
    initializeService();

    StorageObject blob =
        storage.create(
            BLOB_INFO_WITH_HASHES, fileStream, Storage.BlobWriteOptions.disableGzipCompression());

    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobFromStreamWithEncryptionKey() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream(BLOB_CONTENT);

    doReturn(BLOB_INFO1.toPb())
        .doReturn(BLOB_INFO1.toPb())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(BLOB_INFO_WITHOUT_HASHES.toPb(), fileStream, ENCRYPTION_KEY_OPTIONS);
    initializeService();
    StorageObject blob =
        storage.create(
            BLOB_INFO_WITH_HASHES, fileStream, Storage.BlobWriteOptions.getEncryptionKey(BASE64_KEY));
    assertEquals(expectedBlob1, blob);
    blob =
        storage.create(
            BLOB_INFO_WITH_HASHES, fileStream, Storage.BlobWriteOptions.getEncryptionKey(BASE64_KEY));
    assertEquals(expectedBlob1, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobFromStreamRetryableException() throws IOException {

    ByteArrayInputStream fileStream = new ByteArrayInputStream(BLOB_CONTENT);

    Exception internalErrorException = new StorageException(500, "internalError");
    doThrow(internalErrorException)
        .when(storageRpcMock)
        .create(BLOB_INFO_WITHOUT_HASHES.toPb(), fileStream, EMPTY_RPC_OPTIONS);

    storage =
        options
            .toBuilder()
            .setRetrySettings(ServiceOptions.getDefaultRetrySettings())
            .build()
            .getService();

    // Even though this exception is retryable, storage.create(BlobInfo, InputStream)
    // shouldn't retry.
    try {
      storage.create(BLOB_INFO_WITH_HASHES, fileStream);
      fail();
    } catch (StorageException ex) {
      assertSame(internalErrorException, ex);
    }
  }

  @Test
  public void testCreateFromDirectory() throws IOException {
    initializeService();
    Path dir = Files.createTempDirectory("unit_");
    try {
      storage.createFrom(BLOB_INFO1, dir);
      fail();
    } catch (StorageException e) {
      assertEquals(dir + " is a directory", e.getMessage());
    }
  }

  private BlobInfo initializeUpload(byte[] bytes) {
    return initializeUpload(bytes, DEFAULT_BUFFER_SIZE, EMPTY_RPC_OPTIONS);
  }

  private BlobInfo initializeUpload(byte[] bytes, int bufferSize) {
    return initializeUpload(bytes, bufferSize, EMPTY_RPC_OPTIONS);
  }

  private BlobInfo initializeUpload(
      byte[] bytes, int bufferSize, Map<StorageRpc.RequestOption, ?> rpcOptions) {
    String uploadId = "upload-id";
    byte[] buffer = new byte[bufferSize];
    System.arraycopy(bytes, 0, buffer, 0, bytes.length);
    BlobInfo blobInfo = BLOB_INFO1.toBuilder().setMd5(null).setCrc32c(null).build();
    com.google.api.services.storage.model.StorageObject storageObject = new com.google.api.services.storage.model.StorageObject();
    storageObject.setBucket(BLOB_INFO1.getBucket());
    storageObject.setName(BLOB_INFO1.getName());
    storageObject.setSize(BigInteger.valueOf(bytes.length));
    doReturn(uploadId)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(blobInfo.toPb(), rpcOptions);

    doReturn(storageObject)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .writeWithResponse(uploadId, buffer, 0, 0L, bytes.length, true);

    initializeService();
    expectedUpdated = StorageObject.fromProto(storage, storageObject);
    return blobInfo;
  }

  @Test
  public void testCreateFromFile() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4};
    Path tempFile = Files.createTempFile("testCreateFrom", ".tmp");
    Files.write(tempFile, dataToSend);

    BlobInfo blobInfo = initializeUpload(dataToSend);
    StorageObject blob = storage.createFrom(blobInfo, tempFile);
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromStream() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4, 5};
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);

    BlobInfo blobInfo = initializeUpload(dataToSend);
    StorageObject blob = storage.createFrom(blobInfo, stream);
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithOptions() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4, 5, 6};
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);

    BlobInfo blobInfo = initializeUpload(dataToSend, DEFAULT_BUFFER_SIZE, KMS_KEY_NAME_OPTIONS);
    StorageObject blob =
        storage.createFrom(blobInfo, stream, Storage.BlobWriteOptions.withKmsKeyName(KMS_KEY_NAME));
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithBufferSize() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4, 5, 6};
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);
    int bufferSize = MIN_BUFFER_SIZE * 2;

    BlobInfo blobInfo = initializeUpload(dataToSend, bufferSize);
    StorageObject blob = storage.createFrom(blobInfo, stream, bufferSize);
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithBufferSizeAndOptions() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4, 5, 6};
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);
    int bufferSize = MIN_BUFFER_SIZE * 2;

    BlobInfo blobInfo = initializeUpload(dataToSend, bufferSize, KMS_KEY_NAME_OPTIONS);
    StorageObject blob =
        storage.createFrom(
            blobInfo, stream, bufferSize, Storage.BlobWriteOptions.withKmsKeyName(KMS_KEY_NAME));
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithSmallBufferSize() throws Exception {
    byte[] dataToSend = new byte[100_000];
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);
    int smallBufferSize = 100;

    BlobInfo blobInfo = initializeUpload(dataToSend, MIN_BUFFER_SIZE);
    StorageObject blob = storage.createFrom(blobInfo, stream, smallBufferSize);
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithException() throws Exception {
    initializeService();
    String uploadId = "id-exception";
    byte[] bytes = new byte[10];
    byte[] buffer = new byte[MIN_BUFFER_SIZE];
    System.arraycopy(bytes, 0, buffer, 0, bytes.length);
    BlobInfo info = BLOB_INFO1.toBuilder().setMd5(null).setCrc32c(null).build();
    doReturn(uploadId)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toPb(), EMPTY_RPC_OPTIONS);

    Exception runtimeException = new RuntimeException("message");
    doThrow(runtimeException)
        .when(storageRpcMock)
        .writeWithResponse(uploadId, buffer, 0, 0L, bytes.length, true);

    InputStream input = new ByteArrayInputStream(bytes);
    try {
      storage.createFrom(info, input, MIN_BUFFER_SIZE);
      fail();
    } catch (StorageException e) {
      assertSame(runtimeException, e.getCause());
    }
  }

  @Test
  public void testCreateFromMultipleParts() throws Exception {
    initializeService();
    String uploadId = "id-multiple-parts";
    int extraBytes = 10;
    int totalSize = MIN_BUFFER_SIZE + extraBytes;
    byte[] dataToSend = new byte[totalSize];
    dataToSend[0] = 42;
    dataToSend[MIN_BUFFER_SIZE + 1] = 43;

    com.google.api.services.storage.model.StorageObject storageObject = new com.google.api.services.storage.model.StorageObject();
    storageObject.setBucket(BLOB_INFO1.getBucket());
    storageObject.setName(BLOB_INFO1.getName());
    storageObject.setSize(BigInteger.valueOf(totalSize));

    BlobInfo info = BLOB_INFO1.toBuilder().setMd5(null).setCrc32c(null).build();
    doReturn(uploadId)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toPb(), EMPTY_RPC_OPTIONS);

    byte[] buffer1 = new byte[MIN_BUFFER_SIZE];
    System.arraycopy(dataToSend, 0, buffer1, 0, MIN_BUFFER_SIZE);
    doReturn(null)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .writeWithResponse(uploadId, buffer1, 0, 0L, MIN_BUFFER_SIZE, false);

    byte[] buffer2 = new byte[MIN_BUFFER_SIZE];
    System.arraycopy(dataToSend, MIN_BUFFER_SIZE, buffer2, 0, extraBytes);
    doReturn(storageObject)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .writeWithResponse(uploadId, buffer2, 0, (long) MIN_BUFFER_SIZE, extraBytes, true);

    InputStream input = new ByteArrayInputStream(dataToSend);
    StorageObject blob = storage.createFrom(info, input, MIN_BUFFER_SIZE);
    assertEquals(StorageObject.fromProto(storage, storageObject), blob);
  }

  @Test
  public void testListBuckets() {
    String cursor = "cursor";
    ImmutableList<BucketInfo> bucketInfoList = ImmutableList.of(BUCKET_INFO1, BUCKET_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> result =
        Tuple.of(cursor, Iterables.transform(bucketInfoList, BucketInfo.TO_PB_FUNCTION));

    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(EMPTY_RPC_OPTIONS);

    initializeService();
    ImmutableList<Bucket> bucketList = ImmutableList.of(expectedBucket1, expectedBucket2);
    Page<Bucket> page = storage.list();
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(bucketList.toArray(), Iterables.toArray(page.getValues(), Bucket.class));
  }

  @Test
  public void testListBucketsEmpty() {
    doReturn(Tuple.<String, Iterable<com.google.api.services.storage.model.Bucket>>of(null, null))
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(EMPTY_RPC_OPTIONS);

    initializeService();
    Page<Bucket> page = storage.list();
    assertNull(page.getNextPageToken());
    assertArrayEquals(
        ImmutableList.of().toArray(), Iterables.toArray(page.getValues(), Bucket.class));
  }

  @Test
  public void testListBucketsWithOptions() {
    String cursor = "cursor";
    ImmutableList<BucketInfo> bucketInfoList = ImmutableList.of(BUCKET_INFO1, BUCKET_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> result =
        Tuple.of(cursor, Iterables.transform(bucketInfoList, BucketInfo.TO_PB_FUNCTION));

    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(BUCKET_LIST_OPTIONS);

    initializeService();
    ImmutableList<Bucket> bucketList = ImmutableList.of(expectedBucket1, expectedBucket2);
    Page<Bucket> page = storage.list(BUCKET_LIST_PAGE_SIZE, BUCKET_LIST_PREFIX);
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(bucketList.toArray(), Iterables.toArray(page.getValues(), Bucket.class));
  }

  @Test
  public void testListBucketsWithSelectedFields() {
    String cursor = "cursor";
    ArgumentCaptor<Map<StorageRpc.RequestOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);

    ImmutableList<BucketInfo> bucketInfoList = ImmutableList.of(BUCKET_INFO1, BUCKET_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> result =
        Tuple.of(cursor, Iterables.transform(bucketInfoList, BucketInfo.TO_PB_FUNCTION));

    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(capturedOptions.capture());
    initializeService();
    ImmutableList<Bucket> bucketList = ImmutableList.of(expectedBucket1, expectedBucket2);
    Page<Bucket> page = storage.list(BUCKET_LIST_FIELDS);
    String selector = (String) capturedOptions.getValue().get(BUCKET_LIST_FIELDS.getRpcOption());
    assertTrue(selector.contains("items("));
    assertTrue(selector.contains("name"));
    assertTrue(selector.contains("acl"));
    assertTrue(selector.contains("location"));
    assertTrue(selector.contains("nextPageToken"));
    assertTrue(selector.endsWith(")"));
    assertEquals(38, selector.length());
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(bucketList.toArray(), Iterables.toArray(page.getValues(), Bucket.class));
  }

  @Test
  public void testListBucketsWithEmptyFields() {
    String cursor = "cursor";
    ArgumentCaptor<Map<StorageRpc.RequestOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    ImmutableList<BucketInfo> bucketInfoList = ImmutableList.of(BUCKET_INFO1, BUCKET_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> result =
        Tuple.of(cursor, Iterables.transform(bucketInfoList, BucketInfo.TO_PB_FUNCTION));

    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(capturedOptions.capture());
    initializeService();
    ImmutableList<Bucket> bucketList = ImmutableList.of(expectedBucket1, expectedBucket2);
    Page<Bucket> page = storage.list(BUCKET_LIST_EMPTY_FIELDS);
    String selector =
        (String) capturedOptions.getValue().get(BUCKET_LIST_EMPTY_FIELDS.getRpcOption());
    assertTrue(selector.contains("items("));
    assertTrue(selector.contains("name"));
    assertTrue(selector.contains("nextPageToken"));
    assertTrue(selector.endsWith(")"));
    assertEquals(25, selector.length());
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(bucketList.toArray(), Iterables.toArray(page.getValues(), Bucket.class));
  }

  @Test
  public void testListBucketsWithException() {
    doThrow(STORAGE_FAILURE).when(storageRpcMock).list(EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.list();
      fail();
    } catch (StorageException e) {
      assertEquals(STORAGE_FAILURE.toString(), e.getMessage());
    }
  }

  @Test
  public void testListBlobs() {
    String cursor = "cursor";
    ImmutableList<BlobInfo> blobInfoList = ImmutableList.of(BLOB_INFO1, BLOB_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> result =
        Tuple.of(cursor, Iterables.transform(blobInfoList, BlobInfo.INFO_TO_PB_FUNCTION));

    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(BUCKET_NAME1, EMPTY_RPC_OPTIONS);

    initializeService();
    ImmutableList<StorageObject> blobList = ImmutableList.of(expectedBlob1, expectedBlob2);
    Page<StorageObject> page = storage.list(BUCKET_NAME1);
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(blobList.toArray(), Iterables.toArray(page.getValues(), StorageObject.class));
  }

  @Test
  public void testListBlobsEmpty() {
    doReturn(
            Tuple.<String, Iterable<com.google.api.services.storage.model.StorageObject>>of(
                null, null))
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(BUCKET_NAME1, EMPTY_RPC_OPTIONS);

    initializeService();
    Page<StorageObject> page = storage.list(BUCKET_NAME1);
    assertNull(page.getNextPageToken());
    assertArrayEquals(
        ImmutableList.of().toArray(), Iterables.toArray(page.getValues(), StorageObject.class));
  }

  @Test
  public void testListBlobsWithOptions() {
    String cursor = "cursor";
    ImmutableList<BlobInfo> blobInfoList = ImmutableList.of(BLOB_INFO1, BLOB_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> result =
        Tuple.of(cursor, Iterables.transform(blobInfoList, BlobInfo.INFO_TO_PB_FUNCTION));
    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(BUCKET_NAME1, BLOB_LIST_OPTIONS);
    initializeService();
    ImmutableList<StorageObject> blobList = ImmutableList.of(expectedBlob1, expectedBlob2);
    Page<StorageObject> page =
        storage.list(BUCKET_NAME1, BLOB_LIST_PAGE_SIZE, BLOB_LIST_PREFIX, BLOB_LIST_VERSIONS);
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(blobList.toArray(), Iterables.toArray(page.getValues(), StorageObject.class));
  }

  @Test
  public void testListBlobsWithSelectedFields() {
    String cursor = "cursor";
    ArgumentCaptor<Map<StorageRpc.RequestOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    ImmutableList<BlobInfo> blobInfoList = ImmutableList.of(BLOB_INFO1, BLOB_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> result =
        Tuple.of(cursor, Iterables.transform(blobInfoList, BlobInfo.INFO_TO_PB_FUNCTION));
    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(Mockito.eq(BUCKET_NAME1), capturedOptions.capture());

    initializeService();
    ImmutableList<StorageObject> blobList = ImmutableList.of(expectedBlob1, expectedBlob2);
    Page<StorageObject> page =
        storage.list(BUCKET_NAME1, BLOB_LIST_PAGE_SIZE, BLOB_LIST_PREFIX, BLOB_LIST_FIELDS);
    assertEquals(
        BLOB_LIST_PAGE_SIZE.getValue(),
        capturedOptions.getValue().get(BLOB_LIST_PAGE_SIZE.getRpcOption()));
    assertEquals(
        BLOB_LIST_PREFIX.getValue(),
        capturedOptions.getValue().get(BLOB_LIST_PREFIX.getRpcOption()));
    String selector = (String) capturedOptions.getValue().get(BLOB_LIST_FIELDS.getRpcOption());
    assertTrue(selector.contains("prefixes"));
    assertTrue(selector.contains("items("));
    assertTrue(selector.contains("bucket"));
    assertTrue(selector.contains("name"));
    assertTrue(selector.contains("contentType"));
    assertTrue(selector.contains("md5Hash"));
    assertTrue(selector.contains("nextPageToken"));
    assertTrue(selector.endsWith(")"));
    assertEquals(61, selector.length());
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(blobList.toArray(), Iterables.toArray(page.getValues(), StorageObject.class));
  }

  @Test
  public void testListBlobsWithEmptyFields() {
    String cursor = "cursor";
    ArgumentCaptor<Map<StorageRpc.RequestOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    ImmutableList<BlobInfo> blobInfoList = ImmutableList.of(BLOB_INFO1, BLOB_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> result =
        Tuple.of(cursor, Iterables.transform(blobInfoList, BlobInfo.INFO_TO_PB_FUNCTION));
    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(Mockito.eq(BUCKET_NAME1), capturedOptions.capture());

    initializeService();
    ImmutableList<StorageObject> blobList = ImmutableList.of(expectedBlob1, expectedBlob2);
    Page<StorageObject> page =
        storage.list(BUCKET_NAME1, BLOB_LIST_PAGE_SIZE, BLOB_LIST_PREFIX, BLOB_LIST_EMPTY_FIELDS);
    assertEquals(
        BLOB_LIST_PAGE_SIZE.getValue(),
        capturedOptions.getValue().get(BLOB_LIST_PAGE_SIZE.getRpcOption()));
    assertEquals(
        BLOB_LIST_PREFIX.getValue(),
        capturedOptions.getValue().get(BLOB_LIST_PREFIX.getRpcOption()));
    String selector =
        (String) capturedOptions.getValue().get(BLOB_LIST_EMPTY_FIELDS.getRpcOption());
    assertTrue(selector.contains("prefixes"));
    assertTrue(selector.contains("items("));
    assertTrue(selector.contains("bucket"));
    assertTrue(selector.contains("name"));
    assertTrue(selector.contains("nextPageToken"));
    assertTrue(selector.endsWith(")"));
    assertEquals(41, selector.length());
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(blobList.toArray(), Iterables.toArray(page.getValues(), StorageObject.class));
  }

  @Test
  public void testListBlobsCurrentDirectory() {
    String cursor = "cursor";
    Map<StorageRpc.RequestOption, ?> options = ImmutableMap.of(StorageRpc.RequestOption.DELIMITER, "/");
    ImmutableList<BlobInfo> blobInfoList = ImmutableList.of(BLOB_INFO1, BLOB_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> result =
        Tuple.of(cursor, Iterables.transform(blobInfoList, BlobInfo.INFO_TO_PB_FUNCTION));
    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(BUCKET_NAME1, options);

    initializeService();
    ImmutableList<StorageObject> blobList = ImmutableList.of(expectedBlob1, expectedBlob2);
    Page<StorageObject> page = storage.list(BUCKET_NAME1, Storage.ListBlobsOption.currentDirectoryOption());
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(blobList.toArray(), Iterables.toArray(page.getValues(), StorageObject.class));
  }

  @Test
  public void testListBlobsDelimiter() {
    String cursor = "cursor";
    String delimiter = "/";
    Map<StorageRpc.RequestOption, ?> options = ImmutableMap.of(StorageRpc.RequestOption.DELIMITER, delimiter);
    ImmutableList<BlobInfo> blobInfoList = ImmutableList.of(BLOB_INFO1, BLOB_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> result =
        Tuple.of(cursor, Iterables.transform(blobInfoList, BlobInfo.INFO_TO_PB_FUNCTION));
    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(BUCKET_NAME1, options);

    initializeService();
    ImmutableList<StorageObject> blobList = ImmutableList.of(expectedBlob1, expectedBlob2);
    Page<StorageObject> page = storage.list(BUCKET_NAME1, Storage.ListBlobsOption.withDelimiter(delimiter));
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(blobList.toArray(), Iterables.toArray(page.getValues(), StorageObject.class));
  }

  @Test
  public void testListBlobsWithOffset() {
    String cursor = "cursor";
    String startOffset = "startingOffset";
    String endOffset = "withEndOffset";
    Map<StorageRpc.RequestOption, ?> options =
        ImmutableMap.of(
            StorageRpc.RequestOption.START_OFF_SET, startOffset, StorageRpc.RequestOption.END_OFF_SET, endOffset);
    ImmutableList<BlobInfo> blobInfoList = ImmutableList.of(BLOB_INFO1, BLOB_INFO2);
    Tuple<String, Iterable<com.google.api.services.storage.model.StorageObject>> result =
        Tuple.of(cursor, Iterables.transform(blobInfoList, BlobInfo.INFO_TO_PB_FUNCTION));
    doReturn(result)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .list(BUCKET_NAME1, options);

    initializeService();
    ImmutableList<StorageObject> blobList = ImmutableList.of(expectedBlob1, expectedBlob2);
    Page<StorageObject> page =
        storage.list(
            BUCKET_NAME1,
            Storage.ListBlobsOption.startingOffset(startOffset),
            Storage.ListBlobsOption.withEndOffset(endOffset));
    assertEquals(cursor, page.getNextPageToken());
    assertArrayEquals(blobList.toArray(), Iterables.toArray(page.getValues(), StorageObject.class));
  }

  @Test
  public void testListBlobsWithException() {
    doThrow(STORAGE_FAILURE).when(storageRpcMock).list(BUCKET_NAME1, EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.list(BUCKET_NAME1);
      fail();
    } catch (StorageException e) {
      assertEquals(STORAGE_FAILURE.toString(), e.getMessage());
    }
  }

  private void verifyChannelRead(ReadChannel channel, byte[] bytes) throws IOException {
    assertNotNull(channel);
    assertTrue(channel.isOpen());

    ByteBuffer buffer = ByteBuffer.allocate(42);
    byte[] expectedBytes = new byte[buffer.capacity()];
    System.arraycopy(bytes, 0, expectedBytes, 0, bytes.length);

    int size = channel.read(buffer);
    assertEquals(bytes.length, size);
    assertEquals(bytes.length, buffer.position());
    assertArrayEquals(expectedBytes, buffer.array());
  }

  @Test
  public void testReader() {
    initializeService();
    ReadChannel channel = storage.reader(BUCKET_NAME1, BLOB_NAME1);
    assertNotNull(channel);
    assertTrue(channel.isOpen());
    // Storage.getReader() does not issue any RPC, channel.read() does
    try {
      channel.read(ByteBuffer.allocate(100));
      fail();
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("java.lang.IllegalArgumentException: Unexpected call"));
    }
  }

  @Test
  public void testReaderWithOptions() throws IOException {
    doReturn(Tuple.of("etag", BLOB_CONTENT))
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .read(BLOB_INFO2.toPb(), BLOB_SOURCE_OPTIONS, 0, DEFAULT_CHUNK_SIZE);
    initializeService();
    ReadChannel channel =
        storage.reader(
            BUCKET_NAME1, BLOB_NAME2, BLOB_SOURCE_GENERATION, BLOB_SOURCE_METAGENERATION);
    verifyChannelRead(channel, BLOB_CONTENT);
  }

  @Test
  public void testReaderWithDecryptionKey() throws IOException {
    doReturn(Tuple.of("a", BLOB_CONTENT), Tuple.of("b", BLOB_SUB_CONTENT))
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .read(BLOB_INFO2.toPb(), ENCRYPTION_KEY_OPTIONS, 0, DEFAULT_CHUNK_SIZE);
    initializeService();
    ReadChannel channel =
        storage.reader(BUCKET_NAME1, BLOB_NAME2, Storage.BlobReadOption.customerSuppliedKey(KEY));

    verifyChannelRead(channel, BLOB_CONTENT);
    channel =
        storage.reader(
            BUCKET_NAME1, BLOB_NAME2, Storage.BlobReadOption.customerSuppliedKey(BASE64_KEY));
    verifyChannelRead(channel, BLOB_SUB_CONTENT);
  }

  @Test
  public void testReaderWithOptionsFromBlobId() throws IOException {
    doReturn(Tuple.of("etag", BLOB_CONTENT))
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .read(BLOB_INFO1.getBlobId().toPb(), BLOB_SOURCE_OPTIONS, 0, DEFAULT_CHUNK_SIZE);
    initializeService();
    ReadChannel channel =
        storage.reader(
            BLOB_INFO1.getBlobId(),
            BLOB_SOURCE_GENERATION_FROM_BLOB_ID,
            BLOB_SOURCE_METAGENERATION);
    verifyChannelRead(channel, BLOB_CONTENT);
  }

  @Test
  public void testReaderFailure() throws IOException {
    doThrow(STORAGE_FAILURE)
        .when(storageRpcMock)
        .read(BLOB_INFO2.getBlobId().toPb(), EMPTY_RPC_OPTIONS, 0, DEFAULT_CHUNK_SIZE);
    initializeService();
    ReadChannel channel = storage.reader(BUCKET_NAME1, BLOB_NAME2);
    assertNotNull(channel);
    assertTrue(channel.isOpen());
    try {
      channel.read(ByteBuffer.allocate(42));
      fail();
    } catch (IOException e) {
      assertTrue(e.getMessage().contains(STORAGE_FAILURE.toString()));
    }
  }

  @Test
  public void testWriter() {
    doReturn("upload-id")
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(BLOB_INFO_WITHOUT_HASHES.toPb(), EMPTY_RPC_OPTIONS);
    initializeService();
    WriteChannel channel = storage.writer(BLOB_INFO_WITH_HASHES);
    assertNotNull(channel);
    assertTrue(channel.isOpen());
  }

  @Test
  public void testWriterWithOptions() {
    BlobInfo info = BLOB_INFO1.toBuilder().setMd5(CONTENT_MD5).setCrc32c(CONTENT_CRC32C).build();
    doReturn("upload-id")
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toPb(), BLOB_TARGET_OPTIONS_CREATE);
    initializeService();
    WriteChannel channel =
        storage.writer(
            info,
            BLOB_WRITE_METAGENERATION,
            BLOB_WRITE_NOT_EXIST,
            BLOB_WRITE_PREDEFINED_ACL,
            BLOB_WRITE_CRC2C,
            BLOB_WRITE_MD5_HASH);
    assertNotNull(channel);
    assertTrue(channel.isOpen());
  }

  @Test
  public void testWriterWithEncryptionKey() {
    BlobInfo info = BLOB_INFO1.toBuilder().setMd5(null).setCrc32c(null).build();
    doReturn("upload-id-1", "upload-id-2")
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toPb(), ENCRYPTION_KEY_OPTIONS);
    initializeService();
    WriteChannel channel = storage.writer(info, Storage.BlobWriteOptions.getEncryptionKey(KEY));
    assertNotNull(channel);
    assertTrue(channel.isOpen());
    channel = storage.writer(info, Storage.BlobWriteOptions.getEncryptionKey(BASE64_KEY));
    assertNotNull(channel);
    assertTrue(channel.isOpen());
  }

  @Test
  public void testWriterWithKmsKeyName() {
    BlobInfo info = BLOB_INFO1.toBuilder().setMd5(null).setCrc32c(null).build();
    doReturn("upload-id-1", "upload-id-2")
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toPb(), KMS_KEY_NAME_OPTIONS);
    initializeService();
    WriteChannel channel = storage.writer(info, Storage.BlobWriteOptions.withKmsKeyName(KMS_KEY_NAME));
    assertNotNull(channel);
    assertTrue(channel.isOpen());
    channel = storage.writer(info, Storage.BlobWriteOptions.withKmsKeyName(KMS_KEY_NAME));
    assertNotNull(channel);
    assertTrue(channel.isOpen());
  }

  @Test
  public void testWriterFailure() {
    doThrow(STORAGE_FAILURE)
        .when(storageRpcMock)
        .open(BLOB_INFO_WITHOUT_HASHES.toPb(), EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.writer(BLOB_INFO_WITH_HASHES);
      fail();
    } catch (StorageException e) {
      assertSame(STORAGE_FAILURE, e.getCause());
    }
  }
}
