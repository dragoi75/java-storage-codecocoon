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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.google.api.core.ApiClock;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.ReadChannel;
import com.google.cloud.ServiceOptions;
import com.google.cloud.Tuple;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.spi.StorageRpcFactory;
import com.google.cloud.storage.spi.v1.CloudStorageRpcClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
      BucketInfo.newBucketBuilder(BUCKET_NAME1).setMetageneration(42L).buildInstance();
  private static final BucketInfo BUCKET_INFO2 = BucketInfo.newBucketBuilder(BUCKET_NAME2).buildInstance();
  private static final BucketInfo BUCKET_INFO3 =
      BucketInfo.newBucketBuilder(BUCKET_NAME3)
          .setRetentionPeriod(RETENTION_PERIOD)
          .setRetentionPolicyIsLocked(true)
          .setMetageneration(42L)
          .buildInstance();

  // BlobInfo objects
  private static final BlobAttributes BLOB_INFO1 =
      BlobAttributes.newBuilder(BUCKET_NAME1, BLOB_NAME1, 24L)
          .setMetageneration(42L)
          .setContentType("application/json")
          .setMd5("md5string")
          .buildObject();
  private static final BlobAttributes BLOB_INFO2 = BlobAttributes.newBuilder(BUCKET_NAME1, BLOB_NAME2).buildObject();
  private static final BlobAttributes BLOB_INFO3 = BlobAttributes.newBuilder(BUCKET_NAME1, BLOB_NAME3).buildObject();

  private static final BlobAttributes BLOB_INFO_WITH_HASHES =
      BLOB_INFO1.asBuilder().setMd5(CONTENT_MD5).setCrc32c(CONTENT_CRC32C).buildObject();
  private static final BlobAttributes BLOB_INFO_WITHOUT_HASHES =
      BLOB_INFO1.asBuilder().setMd5(null).setCrc32c(null).buildObject();

  // Empty StorageRpc options
  private static final Map<CloudStorageRpcClient.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  // Bucket target options
  private static final StorageClient.BucketTargetOptions BUCKET_TARGET_METAGENERATION =
      StorageClient.BucketTargetOptions.ifMetagenerationMatch();
  private static final StorageClient.BucketTargetOptions BUCKET_TARGET_PREDEFINED_ACL =
      StorageClient.BucketTargetOptions.withPredefinedAcl(StorageClient.PredefinedAccessControlList.PRIVATE);
  private static final StorageClient.BucketTargetOptions BUCKET_TARGET_USER_PROJECT =
      StorageClient.BucketTargetOptions.withUserProject(USER_PROJECT);
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BUCKET_TARGET_OPTIONS =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BUCKET_INFO1.getMetageneration(),
          CloudStorageRpcClient.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BUCKET_TARGET_OPTIONS_LOCK_RETENTION_POLICY =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH,
          BUCKET_INFO3.getMetageneration(),
          CloudStorageRpcClient.StorageOption.USER_PROJECT,
          USER_PROJECT);

  // Blob target options (create, update, compose)
  private static final StorageClient.BlobUploadOption BLOB_TARGET_GENERATION =
      StorageClient.BlobUploadOption.withGenerationMatch();
  private static final StorageClient.BlobUploadOption BLOB_TARGET_METAGENERATION =
      StorageClient.BlobUploadOption.withMetagenerationMatch();
  private static final StorageClient.BlobUploadOption BLOB_TARGET_DISABLE_GZIP_CONTENT =
      StorageClient.BlobUploadOption.disableGzip();
  private static final StorageClient.BlobUploadOption BLOB_TARGET_NOT_EXIST =
      StorageClient.BlobUploadOption.ifDoesNotExist();
  private static final StorageClient.BlobUploadOption BLOB_TARGET_PREDEFINED_ACL =
      StorageClient.BlobUploadOption.withPredefinedAcl(StorageClient.PredefinedAccessControlList.PRIVATE);
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BLOB_TARGET_OPTIONS_CREATE =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L,
          CloudStorageRpcClient.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BLOB_TARGET_OPTIONS_CREATE_DISABLE_GZIP_CONTENT =
      ImmutableMap.of(CloudStorageRpcClient.StorageOption.IF_DISABLE_GZIP_CONTENT, true);
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BLOB_TARGET_OPTIONS_UPDATE =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          CloudStorageRpcClient.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BLOB_TARGET_OPTIONS_COMPOSE =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH, BLOB_INFO1.getGeneration(),
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration());

  // Blob write options (create, writer)
  private static final StorageClient.BlobWriteOptions BLOB_WRITE_METAGENERATION =
      StorageClient.BlobWriteOptions.ifMetagenerationMatch();
  private static final StorageClient.BlobWriteOptions BLOB_WRITE_NOT_EXIST =
      StorageClient.BlobWriteOptions.ifNotExists();
  private static final StorageClient.BlobWriteOptions BLOB_WRITE_PREDEFINED_ACL =
      StorageClient.BlobWriteOptions.withPredefinedAcl(StorageClient.PredefinedAccessControlList.PRIVATE);
  private static final StorageClient.BlobWriteOptions BLOB_WRITE_MD5_HASH =
      StorageClient.BlobWriteOptions.ifMd5Match();
  private static final StorageClient.BlobWriteOptions BLOB_WRITE_CRC2C =
      StorageClient.BlobWriteOptions.ifCrc32cMatch();

  // Bucket get/source options
  private static final StorageClient.BucketSourceRequestOption BUCKET_SOURCE_METAGENERATION =
      StorageClient.BucketSourceRequestOption.withMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BUCKET_SOURCE_OPTIONS =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());
  private static final StorageClient.BucketGetOptions BUCKET_GET_METAGENERATION =
      StorageClient.BucketGetOptions.withMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final StorageClient.BucketGetOptions BUCKET_GET_FIELDS =
      StorageClient.BucketGetOptions.withFields(StorageClient.BucketAttribute.LOCATION, StorageClient.BucketAttribute.ACL);
  private static final StorageClient.BucketGetOptions BUCKET_GET_EMPTY_FIELDS =
      StorageClient.BucketGetOptions.withFields();
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BUCKET_GET_OPTIONS =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());

  // Blob get/source options
  private static final StorageClient.BlobGetOptions BLOB_GET_METAGENERATION =
      StorageClient.BlobGetOptions.ifMetagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final StorageClient.BlobGetOptions BLOB_GET_GENERATION =
      StorageClient.BlobGetOptions.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final StorageClient.BlobGetOptions BLOB_GET_GENERATION_FROM_BLOB_ID =
      StorageClient.BlobGetOptions.ifGenerationMatch();
  private static final StorageClient.BlobGetOptions BLOB_GET_FIELDS =
      StorageClient.BlobGetOptions.selectFields(StorageClient.BlobMetadataField.CONTENT_TYPE, StorageClient.BlobMetadataField.CRC32C);
  private static final StorageClient.BlobGetOptions BLOB_GET_EMPTY_FIELDS = StorageClient.BlobGetOptions.selectFields();
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BLOB_GET_OPTIONS =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_GET_METAGENERATION.getValue(),
          CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH, BLOB_GET_GENERATION.getValue());
  private static final StorageClient.BlobSourceOptions BLOB_SOURCE_METAGENERATION =
      StorageClient.BlobSourceOptions.ifMetagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final StorageClient.BlobSourceOptions BLOB_SOURCE_GENERATION =
      StorageClient.BlobSourceOptions.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final StorageClient.BlobSourceOptions BLOB_SOURCE_GENERATION_FROM_BLOB_ID =
      StorageClient.BlobSourceOptions.ifGenerationMatch();
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BLOB_SOURCE_OPTIONS =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_SOURCE_METAGENERATION.getValue(),
          CloudStorageRpcClient.StorageOption.IF_GENERATION_MATCH, BLOB_SOURCE_GENERATION.getValue());
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BLOB_SOURCE_OPTIONS_COPY =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.IF_SOURCE_METAGENERATION_MATCH, BLOB_SOURCE_METAGENERATION.getValue(),
          CloudStorageRpcClient.StorageOption.IF_SOURCE_GENERATION_MATCH, BLOB_SOURCE_GENERATION.getValue());

  // Bucket list options
  private static final StorageClient.BucketListOptions BUCKET_LIST_PAGE_SIZE =
      StorageClient.BucketListOptions.maxResults(42L);
  private static final StorageClient.BucketListOptions BUCKET_LIST_PREFIX =
      StorageClient.BucketListOptions.withPrefix("prefix");
  private static final StorageClient.BucketListOptions BUCKET_LIST_FIELDS =
      StorageClient.BucketListOptions.selectFields(StorageClient.BucketAttribute.LOCATION, StorageClient.BucketAttribute.ACL);
  private static final StorageClient.BucketListOptions BUCKET_LIST_EMPTY_FIELDS =
      StorageClient.BucketListOptions.selectFields();
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BUCKET_LIST_OPTIONS =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.MAX_RESULTS, BUCKET_LIST_PAGE_SIZE.getValue(),
          CloudStorageRpcClient.StorageOption.PREFIX, BUCKET_LIST_PREFIX.getValue());

  // Blob list options
  private static final StorageClient.BlobListOptions BLOB_LIST_PAGE_SIZE =
      StorageClient.BlobListOptions.maxResults(42L);
  private static final StorageClient.BlobListOptions BLOB_LIST_PREFIX =
      StorageClient.BlobListOptions.withPrefix("prefix");
  private static final StorageClient.BlobListOptions BLOB_LIST_FIELDS =
      StorageClient.BlobListOptions.withFields(StorageClient.BlobMetadataField.CONTENT_TYPE, StorageClient.BlobMetadataField.MD5HASH);
  private static final StorageClient.BlobListOptions BLOB_LIST_VERSIONS =
      StorageClient.BlobListOptions.includeVersions(false);
  private static final StorageClient.BlobListOptions BLOB_LIST_EMPTY_FIELDS =
      StorageClient.BlobListOptions.withFields();
  private static final Map<CloudStorageRpcClient.StorageOption, ?> BLOB_LIST_OPTIONS =
      ImmutableMap.of(
          CloudStorageRpcClient.StorageOption.MAX_RESULTS, BLOB_LIST_PAGE_SIZE.getValue(),
          CloudStorageRpcClient.StorageOption.PREFIX, BLOB_LIST_PREFIX.getValue(),
          CloudStorageRpcClient.StorageOption.VERSIONS, BLOB_LIST_VERSIONS.getValue());

  // ACLs
  private static final AclEntry ACL = AclEntry.ofEntry(AclEntry.UserPrincipal.allAuthenticatedUsers(), AclEntry.AccessRole.OWNER);
  private static final AclEntry OTHER_ACL =
      AclEntry.ofEntry(new AclEntry.ProjectInfo(AclEntry.ProjectInfo.ProjectRoleType.OWNERS, "p"), AclEntry.AccessRole.READER);

  // Customer supplied encryption key options
  private static final Map<CloudStorageRpcClient.StorageOption, ?> ENCRYPTION_KEY_OPTIONS =
      ImmutableMap.of(CloudStorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, BASE64_KEY);

  // Customer managed encryption key options
  private static final Map<CloudStorageRpcClient.StorageOption, ?> KMS_KEY_NAME_OPTIONS =
      ImmutableMap.of(CloudStorageRpcClient.StorageOption.KMS_KEY_NAME, KMS_KEY_NAME);
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

  private static final ServiceAccountInfo SERVICE_ACCOUNT = ServiceAccountInfo.create("test@google.com");

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

  // List of chars under test were taken from
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
          // in path segments should make a copy of this map and replace the mapping for '/' to "/".
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

  private StorageSettings options;
  private StorageRpcFactory rpcFactoryMock;
  private CloudStorageRpcClient storageRpcMock;
  private StorageClient storage;

  private StorageObject expectedBlob1, expectedBlob2, expectedBlob3, expectedUpdated;
  private StorageBucket expectedBucket1, expectedBucket2, expectedBucket3;

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
              "Unexpected call of "
                  + invocation.getMethod()
                  + " with "
                  + Arrays.toString(invocation.getArguments()));
        };
      };

  @Before
  public void setUp() {
    rpcFactoryMock = mock(StorageRpcFactory.class, UNEXPECTED_CALL_ANSWER);
    storageRpcMock = mock(CloudStorageRpcClient.class, UNEXPECTED_CALL_ANSWER);
    doReturn(storageRpcMock).when(rpcFactoryMock).create(Mockito.any(StorageSettings.class));
    options =
        StorageSettings.newStorageBuilder()
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
    expectedBlob1 = new StorageObject(storage, new BlobAttributes.BlobInfoBuilderImpl(BLOB_INFO1));
    expectedBlob2 = new StorageObject(storage, new BlobAttributes.BlobInfoBuilderImpl(BLOB_INFO2));
    expectedBlob3 = new StorageObject(storage, new BlobAttributes.BlobInfoBuilderImpl(BLOB_INFO3));
    expectedBucket1 = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO1));
    expectedBucket2 = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO2));
    expectedBucket3 = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO3));
    expectedUpdated = null;
  }

  @Test
  public void testGetOptions() {
    initializeService();
    assertSame(options, storage.getOptions());
  }

  @Test
  public void testCreateBucket() {
    doReturn(BUCKET_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(BUCKET_INFO1.toProto(), EMPTY_RPC_OPTIONS);
    initializeService();
    StorageBucket bucket = storage.create(BUCKET_INFO1);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testCreateBucketWithOptions() {
    doReturn(BUCKET_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(BUCKET_INFO1.toProto(), BUCKET_TARGET_OPTIONS);
    initializeService();
    StorageBucket bucket =
        storage.create(BUCKET_INFO1, BUCKET_TARGET_METAGENERATION, BUCKET_TARGET_PREDEFINED_ACL);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testCreateBucketFailure() {
    doThrow(STORAGE_FAILURE).when(storageRpcMock).create(BUCKET_INFO1.toProto(), EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.create(BUCKET_INFO1);
      fail();
    } catch (StorageOperationException e) {
      assertEquals(STORAGE_FAILURE, e.getCause());
    }
  }

  @Test
  public void testGetBucket() {
    doReturn(BUCKET_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BucketInfo.ofName(BUCKET_NAME1).toProto(), EMPTY_RPC_OPTIONS);
    initializeService();
    StorageBucket bucket = storage.get(BUCKET_NAME1);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testGetBucketWithOptions() {
    doReturn(BUCKET_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BucketInfo.ofName(BUCKET_NAME1).toProto(), BUCKET_GET_OPTIONS);
    initializeService();
    StorageBucket bucket = storage.get(BUCKET_NAME1, BUCKET_GET_METAGENERATION);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testGetBucketWithSelectedFields() {
    ArgumentCaptor<Map<CloudStorageRpcClient.StorageOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    doReturn(BUCKET_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(Mockito.eq(BucketInfo.ofName(BUCKET_NAME1).toProto()), capturedOptions.capture());
    initializeService();
    StorageBucket bucket = storage.get(BUCKET_NAME1, BUCKET_GET_METAGENERATION, BUCKET_GET_FIELDS);
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
    ArgumentCaptor<Map<CloudStorageRpcClient.StorageOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    doReturn(BUCKET_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(Mockito.eq(BucketInfo.ofName(BUCKET_NAME1).toProto()), capturedOptions.capture());
    initializeService();
    StorageBucket bucket = storage.get(BUCKET_NAME1, BUCKET_GET_METAGENERATION, BUCKET_GET_EMPTY_FIELDS);
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
        .get(BucketInfo.ofName(BUCKET_NAME1).toProto(), EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.get(BUCKET_NAME1);
      fail();
    } catch (StorageOperationException e) {
      assertEquals(STORAGE_FAILURE, e.getCause());
    }
  }

  @Test
  public void testGetBlob() {
    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toStorageObject(), EMPTY_RPC_OPTIONS);
    initializeService();
    StorageObject blob = storage.get(BUCKET_NAME1, BLOB_NAME1);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testGetBlobWithOptions() {
    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toStorageObject(), BLOB_GET_OPTIONS);
    initializeService();
    StorageObject blob = storage.get(BUCKET_NAME1, BLOB_NAME1, BLOB_GET_METAGENERATION, BLOB_GET_GENERATION);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testGetBlobWithOptionsFromBlobId() {
    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(BLOB_INFO1.getBlobId().toStorageObject(), BLOB_GET_OPTIONS);
    initializeService();
    StorageObject blob =
        storage.get(
            BLOB_INFO1.getBlobId(), BLOB_GET_METAGENERATION, BLOB_GET_GENERATION_FROM_BLOB_ID);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testGetBlobWithSelectedFields() {
    ArgumentCaptor<Map<CloudStorageRpcClient.StorageOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(Mockito.eq(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toStorageObject()), capturedOptions.capture());
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
    ArgumentCaptor<Map<CloudStorageRpcClient.StorageOption, Object>> capturedOptions =
        ArgumentCaptor.forClass(Map.class);
    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .get(Mockito.eq(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toStorageObject()), capturedOptions.capture());
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
        .get(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toStorageObject(), EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.get(BUCKET_NAME1, BLOB_NAME1);
      fail();
    } catch (StorageOperationException e) {
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
    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toProto()),
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
    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(
                BLOB_INFO1
                    .asBuilder()
                    .setMd5(SUB_CONTENT_MD5)
                    .setCrc32c(SUB_CONTENT_CRC32C)
                    .buildObject()
                    .toProto()),
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

    com.google.api.services.storage.model.StorageObject storageObject = BLOB_INFO_WITH_HASHES.toProto();

    doThrow(new StorageOperationException(500, "internalError"))
        .doReturn(BLOB_INFO1.toProto())
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

    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(
                BLOB_INFO1
                    .asBuilder()
                    .setMd5("1B2M2Y8AsgTpgAmY7PhCfg==")
                    .setCrc32c("AAAAAA==")
                    .buildObject()
                    .toProto()),
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

    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toProto()),
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

    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toProto()),
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

    doReturn(BLOB_INFO1.toProto())
        .doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toProto()),
            capturedStream.capture(),
            Mockito.eq(ENCRYPTION_KEY_OPTIONS));
    initializeService();

    StorageObject blob =
        storage.create(BLOB_INFO1, BLOB_CONTENT, StorageClient.BlobUploadOption.withEncryptionKey(KEY));
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
    blob =
        storage.create(
            BLOB_INFO1, BLOB_CONTENT, StorageClient.BlobUploadOption.withEncryptionKey(BASE64_KEY));
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  public void testCreateBlobWithKmsKeyName() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    doReturn(BLOB_INFO1.toProto())
        .doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITH_HASHES.toProto()),
            capturedStream.capture(),
            Mockito.eq(KMS_KEY_NAME_OPTIONS));
    initializeService();

    StorageObject blob =
        storage.create(BLOB_INFO1, BLOB_CONTENT, StorageClient.BlobUploadOption.withKmsKeyName(KMS_KEY_NAME));
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
    blob =
        storage.create(BLOB_INFO1, BLOB_CONTENT, StorageClient.BlobUploadOption.withKmsKeyName(KMS_KEY_NAME));
    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobFromStream() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    ByteArrayInputStream fileStream = new ByteArrayInputStream(BLOB_CONTENT);

    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITHOUT_HASHES.toProto()),
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
    doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(
            Mockito.eq(BLOB_INFO_WITHOUT_HASHES.toProto()),
            capturedStream.capture(),
            Mockito.eq(BLOB_TARGET_OPTIONS_CREATE_DISABLE_GZIP_CONTENT));
    initializeService();

    StorageObject blob =
        storage.create(
            BLOB_INFO_WITH_HASHES, fileStream, StorageClient.BlobWriteOptions.disableGzip());

    assertEquals(expectedBlob1, blob);
    verifyCreateBlobCapturedStream(capturedStream);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobFromStreamWithEncryptionKey() throws IOException {
    ByteArrayInputStream fileStream = new ByteArrayInputStream(BLOB_CONTENT);

    doReturn(BLOB_INFO1.toProto())
        .doReturn(BLOB_INFO1.toProto())
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .create(BLOB_INFO_WITHOUT_HASHES.toProto(), fileStream, ENCRYPTION_KEY_OPTIONS);
    initializeService();
    StorageObject blob =
        storage.create(
            BLOB_INFO_WITH_HASHES, fileStream, StorageClient.BlobWriteOptions.customerSuppliedKey(BASE64_KEY));
    assertEquals(expectedBlob1, blob);
    blob =
        storage.create(
            BLOB_INFO_WITH_HASHES, fileStream, StorageClient.BlobWriteOptions.customerSuppliedKey(BASE64_KEY));
    assertEquals(expectedBlob1, blob);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobFromStreamRetryableException() throws IOException {
    ArgumentCaptor<ByteArrayInputStream> capturedStream =
        ArgumentCaptor.forClass(ByteArrayInputStream.class);

    ByteArrayInputStream fileStream = new ByteArrayInputStream(BLOB_CONTENT);

    Exception internalErrorException = new StorageOperationException(500, "internalError");
    doThrow(internalErrorException)
        .when(storageRpcMock)
        .create(BLOB_INFO_WITHOUT_HASHES.toProto(), fileStream, EMPTY_RPC_OPTIONS);

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
    } catch (StorageOperationException ex) {
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
    } catch (StorageOperationException e) {
      assertEquals(dir + " is a directory", e.getMessage());
    }
  }

  private BlobAttributes initializeUpload(byte[] bytes) {
    return initializeUpload(bytes, DEFAULT_BUFFER_SIZE, EMPTY_RPC_OPTIONS);
  }

  private BlobAttributes initializeUpload(byte[] bytes, int bufferSize) {
    return initializeUpload(bytes, bufferSize, EMPTY_RPC_OPTIONS);
  }

  private BlobAttributes initializeUpload(
      byte[] bytes, int bufferSize, Map<CloudStorageRpcClient.StorageOption, ?> rpcOptions) {
    String uploadId = "upload-id";
    byte[] buffer = new byte[bufferSize];
    System.arraycopy(bytes, 0, buffer, 0, bytes.length);
    BlobAttributes blobInfo = BLOB_INFO1.asBuilder().setMd5(null).setCrc32c(null).buildObject();
    com.google.api.services.storage.model.StorageObject storageObject = new com.google.api.services.storage.model.StorageObject();
    storageObject.setBucket(BLOB_INFO1.getBucket());
    storageObject.setName(BLOB_INFO1.getName());
    storageObject.setSize(BigInteger.valueOf(bytes.length));
    doReturn(uploadId)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(blobInfo.toProto(), rpcOptions);

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

    BlobAttributes blobInfo = initializeUpload(dataToSend);
    StorageObject blob = storage.createFrom(blobInfo, tempFile);
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromStream() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4, 5};
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);

    BlobAttributes blobInfo = initializeUpload(dataToSend);
    StorageObject blob = storage.createFrom(blobInfo, stream);
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithOptions() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4, 5, 6};
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);

    BlobAttributes blobInfo = initializeUpload(dataToSend, DEFAULT_BUFFER_SIZE, KMS_KEY_NAME_OPTIONS);
    StorageObject blob =
        storage.createFrom(blobInfo, stream, StorageClient.BlobWriteOptions.kmsKey(KMS_KEY_NAME));
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithBufferSize() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4, 5, 6};
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);
    int bufferSize = MIN_BUFFER_SIZE * 2;

    BlobAttributes blobInfo = initializeUpload(dataToSend, bufferSize);
    StorageObject blob = storage.createFrom(blobInfo, stream, bufferSize);
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithBufferSizeAndOptions() throws Exception {
    byte[] dataToSend = {1, 2, 3, 4, 5, 6};
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);
    int bufferSize = MIN_BUFFER_SIZE * 2;

    BlobAttributes blobInfo = initializeUpload(dataToSend, bufferSize, KMS_KEY_NAME_OPTIONS);
    StorageObject blob =
        storage.createFrom(
            blobInfo, stream, bufferSize, StorageClient.BlobWriteOptions.kmsKey(KMS_KEY_NAME));
    assertEquals(expectedUpdated, blob);
  }

  @Test
  public void testCreateFromWithSmallBufferSize() throws Exception {
    byte[] dataToSend = new byte[100_000];
    ByteArrayInputStream stream = new ByteArrayInputStream(dataToSend);
    int smallBufferSize = 100;

    BlobAttributes blobInfo = initializeUpload(dataToSend, MIN_BUFFER_SIZE);
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
    BlobAttributes info = BLOB_INFO1.asBuilder().setMd5(null).setCrc32c(null).buildObject();
    doReturn(uploadId)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toProto(), EMPTY_RPC_OPTIONS);

    Exception runtimeException = new RuntimeException("message");
    doThrow(runtimeException)
        .when(storageRpcMock)
        .writeWithResponse(uploadId, buffer, 0, 0L, bytes.length, true);

    InputStream input = new ByteArrayInputStream(bytes);
    try {
      storage.createFrom(info, input, MIN_BUFFER_SIZE);
      fail();
    } catch (StorageOperationException e) {
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

    BlobAttributes info = BLOB_INFO1.asBuilder().setMd5(null).setCrc32c(null).buildObject();
    doReturn(uploadId)
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toProto(), EMPTY_RPC_OPTIONS);

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
    // Storage.reader() does not issue any RPC, channel.read() does
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
        .read(BLOB_INFO2.toProto(), BLOB_SOURCE_OPTIONS, 0, DEFAULT_CHUNK_SIZE);
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
        .read(BLOB_INFO2.toProto(), ENCRYPTION_KEY_OPTIONS, 0, DEFAULT_CHUNK_SIZE);
    initializeService();
    ReadChannel channel =
        storage.reader(BUCKET_NAME1, BLOB_NAME2, StorageClient.BlobSourceOptions.customerSuppliedKey(KEY));

    verifyChannelRead(channel, BLOB_CONTENT);
    channel =
        storage.reader(
            BUCKET_NAME1, BLOB_NAME2, StorageClient.BlobSourceOptions.customerSuppliedKey(BASE64_KEY));
    verifyChannelRead(channel, BLOB_SUB_CONTENT);
  }

  @Test
  public void testReaderWithOptionsFromBlobId() throws IOException {
    doReturn(Tuple.of("etag", BLOB_CONTENT))
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .read(BLOB_INFO1.getBlobId().toStorageObject(), BLOB_SOURCE_OPTIONS, 0, DEFAULT_CHUNK_SIZE);
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
        .read(BLOB_INFO2.getBlobId().toStorageObject(), EMPTY_RPC_OPTIONS, 0, DEFAULT_CHUNK_SIZE);
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
        .open(BLOB_INFO_WITHOUT_HASHES.toProto(), EMPTY_RPC_OPTIONS);
    initializeService();
    WriteChannel channel = storage.writer(BLOB_INFO_WITH_HASHES);
    assertNotNull(channel);
    assertTrue(channel.isOpen());
  }

  @Test
  public void testWriterWithOptions() {
    BlobAttributes info = BLOB_INFO1.asBuilder().setMd5(CONTENT_MD5).setCrc32c(CONTENT_CRC32C).buildObject();
    doReturn("upload-id")
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toProto(), BLOB_TARGET_OPTIONS_CREATE);
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
    BlobAttributes info = BLOB_INFO1.asBuilder().setMd5(null).setCrc32c(null).buildObject();
    doReturn("upload-id-1", "upload-id-2")
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toProto(), ENCRYPTION_KEY_OPTIONS);
    initializeService();
    WriteChannel channel = storage.writer(info, StorageClient.BlobWriteOptions.customerSuppliedKey(KEY));
    assertNotNull(channel);
    assertTrue(channel.isOpen());
    channel = storage.writer(info, StorageClient.BlobWriteOptions.customerSuppliedKey(BASE64_KEY));
    assertNotNull(channel);
    assertTrue(channel.isOpen());
  }

  @Test
  public void testWriterWithKmsKeyName() {
    BlobAttributes info = BLOB_INFO1.asBuilder().setMd5(null).setCrc32c(null).buildObject();
    doReturn("upload-id-1", "upload-id-2")
        .doThrow(UNEXPECTED_CALL_EXCEPTION)
        .when(storageRpcMock)
        .open(info.toProto(), KMS_KEY_NAME_OPTIONS);
    initializeService();
    WriteChannel channel = storage.writer(info, StorageClient.BlobWriteOptions.kmsKey(KMS_KEY_NAME));
    assertNotNull(channel);
    assertTrue(channel.isOpen());
    channel = storage.writer(info, StorageClient.BlobWriteOptions.kmsKey(KMS_KEY_NAME));
    assertNotNull(channel);
    assertTrue(channel.isOpen());
  }

  @Test
  public void testWriterFailure() {
    doThrow(STORAGE_FAILURE)
        .when(storageRpcMock)
        .open(BLOB_INFO_WITHOUT_HASHES.toProto(), EMPTY_RPC_OPTIONS);
    initializeService();
    try {
      storage.writer(BLOB_INFO_WITH_HASHES);
      fail();
    } catch (StorageOperationException e) {
      assertSame(STORAGE_FAILURE, e.getCause());
    }
  }
}
