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
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiClock;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.ServiceOptions;
import com.google.cloud.storage.spi.StorageRpcProvider;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

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
  private static final BucketMetadata BUCKET_INFO1 =
      BucketMetadata.newBucketBuilder(BUCKET_NAME1).setMetageneration(42L).buildBucket();
  private static final BucketMetadata BUCKET_INFO2 = BucketMetadata.newBucketBuilder(BUCKET_NAME2).buildBucket();
  private static final BucketMetadata BUCKET_INFO3 =
      BucketMetadata.newBucketBuilder(BUCKET_NAME3)
          .setRetentionPeriod(RETENTION_PERIOD)
          .setRetentionPolicyIsLocked(true)
          .setMetageneration(42L)
          .buildBucket();

  // BlobInfo objects
  private static final BlobInfo BLOB_INFO1 =
      BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME1, 24L)
          .setMetageneration(42L)
          .setContentType("application/json")
          .setMd5("md5string")
          .buildMetadata();
  private static final BlobInfo BLOB_INFO2 = BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME2).buildMetadata();
  private static final BlobInfo BLOB_INFO3 = BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME3).buildMetadata();

  // Empty StorageRpc options
  private static final Map<StorageRpcClient.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  // Bucket target options
  private static final Storage.BucketTargetOptions BUCKET_TARGET_METAGENERATION =
      Storage.BucketTargetOptions.ifMetagenerationMatch();
  private static final Storage.BucketTargetOptions BUCKET_TARGET_PREDEFINED_ACL =
      Storage.BucketTargetOptions.withPredefinedAcl(Storage.PredefinedAccessControlList.PRIVATE);
  private static final Storage.BucketTargetOptions BUCKET_TARGET_USER_PROJECT =
      Storage.BucketTargetOptions.withUserProject(USER_PROJECT);
  private static final Map<StorageRpcClient.StorageOption, ?> BUCKET_TARGET_OPTIONS =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BUCKET_INFO1.getMetageneration(),
          StorageRpcClient.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageRpcClient.StorageOption, ?> BUCKET_TARGET_OPTIONS_LOCK_RETENTION_POLICY =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH,
          BUCKET_INFO3.getMetageneration(),
          StorageRpcClient.StorageOption.USER_PROJECT,
          USER_PROJECT);

  // Blob target options (create, update, compose)
  private static final Storage.BlobUploadOption BLOB_TARGET_GENERATION =
      Storage.BlobUploadOption.ifGenerationMatch();
  private static final Storage.BlobUploadOption BLOB_TARGET_METAGENERATION =
      Storage.BlobUploadOption.ifMetagenerationMatch();
  private static final Storage.BlobUploadOption BLOB_TARGET_DISABLE_GZIP_CONTENT =
      Storage.BlobUploadOption.disableGzip();
  private static final Storage.BlobUploadOption BLOB_TARGET_NOT_EXIST =
      Storage.BlobUploadOption.ifDoesNotExist();
  private static final Storage.BlobUploadOption BLOB_TARGET_PREDEFINED_ACL =
      Storage.BlobUploadOption.withPredefinedAcl(Storage.PredefinedAccessControlList.PRIVATE);
  private static final Map<StorageRpcClient.StorageOption, ?> BLOB_TARGET_OPTIONS_CREATE =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          StorageRpcClient.StorageOption.IF_GENERATION_MATCH, 0L,
          StorageRpcClient.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageRpcClient.StorageOption, ?> BLOB_TARGET_OPTIONS_CREATE_DISABLE_GZIP_CONTENT =
      ImmutableMap.of(StorageRpcClient.StorageOption.IF_DISABLE_GZIP_CONTENT, true);
  private static final Map<StorageRpcClient.StorageOption, ?> BLOB_TARGET_OPTIONS_UPDATE =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          StorageRpcClient.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageRpcClient.StorageOption, ?> BLOB_TARGET_OPTIONS_COMPOSE =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_GENERATION_MATCH, BLOB_INFO1.getGeneration(),
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration());

  // Blob write options (create, writer)
  private static final Storage.BlobWriteOptions BLOB_WRITE_METAGENERATION =
      Storage.BlobWriteOptions.ifMetagenerationMatch();
  private static final Storage.BlobWriteOptions BLOB_WRITE_NOT_EXIST =
      Storage.BlobWriteOptions.ifDoesNotExist();
  private static final Storage.BlobWriteOptions BLOB_WRITE_PREDEFINED_ACL =
      Storage.BlobWriteOptions.withPredefinedAcl(Storage.PredefinedAccessControlList.PRIVATE);
  private static final Storage.BlobWriteOptions BLOB_WRITE_MD5_HASH =
      Storage.BlobWriteOptions.ifMd5Match();
  private static final Storage.BlobWriteOptions BLOB_WRITE_CRC2C =
      Storage.BlobWriteOptions.ifCrc32cMatch();

  // Bucket get/source options
  private static final Storage.BucketSourceRequestOption BUCKET_SOURCE_METAGENERATION =
      Storage.BucketSourceRequestOption.ifMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Map<StorageRpcClient.StorageOption, ?> BUCKET_SOURCE_OPTIONS =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());
  private static final Storage.BucketGetOptions BUCKET_GET_METAGENERATION =
      Storage.BucketGetOptions.ifMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Storage.BucketGetOptions BUCKET_GET_FIELDS =
      Storage.BucketGetOptions.withFields(Storage.BucketAttribute.LOCATION, Storage.BucketAttribute.ACL);
  private static final Storage.BucketGetOptions BUCKET_GET_EMPTY_FIELDS =
      Storage.BucketGetOptions.withFields();
  private static final Map<StorageRpcClient.StorageOption, ?> BUCKET_GET_OPTIONS =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());

  // Blob get/source options
  private static final Storage.BlobGetOptions BLOB_GET_METAGENERATION =
      Storage.BlobGetOptions.ifMetagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final Storage.BlobGetOptions BLOB_GET_GENERATION =
      Storage.BlobGetOptions.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final Storage.BlobGetOptions BLOB_GET_GENERATION_FROM_BLOB_ID =
      Storage.BlobGetOptions.ifGenerationMatch();
  private static final Storage.BlobGetOptions BLOB_GET_FIELDS =
      Storage.BlobGetOptions.selectFields(Storage.BlobMetadataField.CONTENT_TYPE, Storage.BlobMetadataField.CRC32C);
  private static final Storage.BlobGetOptions BLOB_GET_EMPTY_FIELDS = Storage.BlobGetOptions.selectFields();
  private static final Map<StorageRpcClient.StorageOption, ?> BLOB_GET_OPTIONS =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_GET_METAGENERATION.getValue(),
          StorageRpcClient.StorageOption.IF_GENERATION_MATCH, BLOB_GET_GENERATION.getValue());
  private static final Storage.BlobSourceSettings BLOB_SOURCE_METAGENERATION =
      Storage.BlobSourceSettings.ifMetagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final Storage.BlobSourceSettings BLOB_SOURCE_GENERATION =
      Storage.BlobSourceSettings.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final Storage.BlobSourceSettings BLOB_SOURCE_GENERATION_FROM_BLOB_ID =
      Storage.BlobSourceSettings.ifGenerationMatch();
  private static final Map<StorageRpcClient.StorageOption, ?> BLOB_SOURCE_OPTIONS =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BLOB_SOURCE_METAGENERATION.getValue(),
          StorageRpcClient.StorageOption.IF_GENERATION_MATCH, BLOB_SOURCE_GENERATION.getValue());
  private static final Map<StorageRpcClient.StorageOption, ?> BLOB_SOURCE_OPTIONS_COPY =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_SOURCE_METAGENERATION_MATCH, BLOB_SOURCE_METAGENERATION.getValue(),
          StorageRpcClient.StorageOption.IF_SOURCE_GENERATION_MATCH, BLOB_SOURCE_GENERATION.getValue());

  // Bucket list options
  private static final Storage.BucketListOptions BUCKET_LIST_PAGE_SIZE =
      Storage.BucketListOptions.withPageSize(42L);
  private static final Storage.BucketListOptions BUCKET_LIST_PREFIX =
      Storage.BucketListOptions.withPrefix("prefix");
  private static final Storage.BucketListOptions BUCKET_LIST_FIELDS =
      Storage.BucketListOptions.selectFields(Storage.BucketAttribute.LOCATION, Storage.BucketAttribute.ACL);
  private static final Storage.BucketListOptions BUCKET_LIST_EMPTY_FIELDS =
      Storage.BucketListOptions.selectFields();
  private static final Map<StorageRpcClient.StorageOption, ?> BUCKET_LIST_OPTIONS =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.MAX_RESULTS, BUCKET_LIST_PAGE_SIZE.getValue(),
          StorageRpcClient.StorageOption.PREFIX, BUCKET_LIST_PREFIX.getValue());

  // Blob list options
  private static final Storage.BlobListOptions BLOB_LIST_PAGE_SIZE =
      Storage.BlobListOptions.withPageSize(42L);
  private static final Storage.BlobListOptions BLOB_LIST_PREFIX =
      Storage.BlobListOptions.withPrefix("prefix");
  private static final Storage.BlobListOptions BLOB_LIST_FIELDS =
      Storage.BlobListOptions.withFields(Storage.BlobMetadataField.CONTENT_TYPE, Storage.BlobMetadataField.MD5HASH);
  private static final Storage.BlobListOptions BLOB_LIST_VERSIONS =
      Storage.BlobListOptions.withVersions(false);
  private static final Storage.BlobListOptions BLOB_LIST_EMPTY_FIELDS =
      Storage.BlobListOptions.withFields();
  private static final Map<StorageRpcClient.StorageOption, ?> BLOB_LIST_OPTIONS =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.MAX_RESULTS, BLOB_LIST_PAGE_SIZE.getValue(),
          StorageRpcClient.StorageOption.PREFIX, BLOB_LIST_PREFIX.getValue(),
          StorageRpcClient.StorageOption.VERSIONS, BLOB_LIST_VERSIONS.getValue());

  // ACLs
  private static final AclEntry ACL = AclEntry.ofEntry(AclEntry.UserPrincipal.allAuthenticatedUsers(), AclEntry.AccessRole.OWNER);
  private static final AclEntry OTHER_ACL =
      AclEntry.ofEntry(new AclEntry.ProjectInfo(AclEntry.ProjectInfo.ProjectAccessLevel.OWNERS, "p"), AclEntry.AccessRole.READER);

  // Customer supplied encryption key options
  private static final Map<StorageRpcClient.StorageOption, ?> ENCRYPTION_KEY_OPTIONS =
      ImmutableMap.of(StorageRpcClient.StorageOption.CUSTOMER_SUPPLIED_KEY, BASE64_KEY);

  // Customer managed encryption key options
  private static final Map<StorageRpcClient.StorageOption, ?> KMS_KEY_NAME_OPTIONS =
      ImmutableMap.of(StorageRpcClient.StorageOption.KMS_KEY_NAME, KMS_KEY_NAME);
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

  private static final ServiceAccountInfo SERVICE_ACCOUNT = ServiceAccountInfo.ofEmail("test@google.com");

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
  private StorageRpcProvider rpcFactoryMock;
  private StorageRpcClient storageRpcMock;
  private Storage storage;

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

  @Before
  public void setUp() {
    rpcFactoryMock = mock(StorageRpcProvider.class);
    storageRpcMock = mock(StorageRpcClient.class);
    when(rpcFactoryMock.create(any(StorageSettings.class))).thenReturn(storageRpcMock);
    options =
        StorageSettings.createBuilder()
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
    expectedBlob1 = new StorageObject(storage, new BlobInfo.StorageObjectBuilder(BLOB_INFO1));
    expectedBlob2 = new StorageObject(storage, new BlobInfo.StorageObjectBuilder(BLOB_INFO2));
    expectedBlob3 = new StorageObject(storage, new BlobInfo.StorageObjectBuilder(BLOB_INFO3));
    expectedBucket1 = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO1));
    expectedBucket2 = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO2));
    expectedBucket3 = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO3));
    expectedUpdated = null;
  }

  @Test
  public void testGetOptions() {
    initializeService();
    assertSame(options, storage.getOptions());
  }

  @Test
  public void testCreateBucket() {
    when(storageRpcMock.create(BUCKET_INFO1.toProto(), EMPTY_RPC_OPTIONS))
        .thenReturn(BUCKET_INFO1.toProto())
        .thenThrow(new RuntimeException("Fail"));
    initializeService();
    StorageBucket bucket = storage.create(BUCKET_INFO1);
    assertEquals(expectedBucket1, bucket);
  }

  @Test
  public void testCreateBucketWithOptions() {
    when(storageRpcMock.create(BUCKET_INFO1.toProto(), BUCKET_TARGET_OPTIONS))
        .thenReturn(BUCKET_INFO1.toProto())
        .thenThrow(new RuntimeException("Fail"));
    initializeService();
    StorageBucket bucket =
        storage.create(BUCKET_INFO1, BUCKET_TARGET_METAGENERATION, BUCKET_TARGET_PREDEFINED_ACL);
    assertEquals(expectedBucket1, bucket);
  }
}
