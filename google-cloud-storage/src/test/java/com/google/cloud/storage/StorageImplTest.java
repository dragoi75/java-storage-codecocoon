/*
 * Copyright 2015 Google LLC
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

import static com.google.cloud.storage.SignedUrlEncodingHelper.Rfc3986UriEncode;
import static com.google.cloud.storage.testing.ApiPolicyMatcher.eqApiPolicy;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.util.DateTime;
import com.google.api.core.ApiClock;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.services.storage.model.Policy.Bindings;
import com.google.api.services.storage.model.TestIamPermissionsResponse;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.ServiceOptions;
import com.google.cloud.storage.AccessControlEntry.ProjectInfo;
import com.google.cloud.storage.AccessControlEntry.RoleType;
import com.google.cloud.storage.AccessControlEntry.UserIdentity;
import com.google.cloud.storage.Storage.BlobSourceOptions;
import com.google.cloud.storage.Storage.BlobUploadOption;
import com.google.cloud.storage.Storage.BlobWriteOptions;
import com.google.cloud.storage.Storage.BucketSourceOptions;
import com.google.cloud.storage.Storage.ChunkedCopyRequest;
import com.google.cloud.storage.spi.StorageRpcFactory;
import com.google.cloud.storage.spi.v1.RpcBatch;
import com.google.cloud.storage.spi.v1.StorageRpcClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import java.io.File;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class StorageImplTest {

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
  private static final BlobMetadata BLOB_INFO1 =
      BlobMetadata.newBuilder(BUCKET_NAME1, BLOB_NAME1, 24L)
          .setMetageneration(42L)
          .setContentType("application/json")
          .setMd5("md5string")
          .buildObject();
  private static final BlobMetadata BLOB_INFO2 = BlobMetadata.newBuilder(BUCKET_NAME1, BLOB_NAME2).buildObject();
  private static final BlobMetadata BLOB_INFO3 = BlobMetadata.newBuilder(BUCKET_NAME1, BLOB_NAME3).buildObject();

  // Empty StorageRpc options
  private static final Map<StorageRpcClient.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  // Bucket target options
  private static final Storage.BucketTargetOptions BUCKET_TARGET_METAGENERATION =
      Storage.BucketTargetOptions.withMetagenerationMatch();
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
  private static final BlobUploadOption BLOB_TARGET_GENERATION = Storage.BlobUploadOption.withGenerationMatch();
  private static final Storage.BlobUploadOption BLOB_TARGET_METAGENERATION =
      BlobUploadOption.withMetagenerationMatch();
  private static final BlobUploadOption BLOB_TARGET_DISABLE_GZIP_CONTENT =
      Storage.BlobUploadOption.disableGzip();
  private static final BlobUploadOption BLOB_TARGET_NOT_EXIST = Storage.BlobUploadOption.ifDoesNotExist();
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
  private static final BlobWriteOptions BLOB_WRITE_NOT_EXIST = Storage.BlobWriteOptions.ifDoesNotExist();
  private static final BlobWriteOptions BLOB_WRITE_PREDEFINED_ACL =
      BlobWriteOptions.withPredefinedAcl(Storage.PredefinedAccessControlList.PRIVATE);
  private static final Storage.BlobWriteOptions BLOB_WRITE_MD5_HASH = BlobWriteOptions.ifMd5Match();
  private static final Storage.BlobWriteOptions BLOB_WRITE_CRC2C = Storage.BlobWriteOptions.ifCrc32cMatch();

  // Bucket get/source options
  private static final Storage.BucketSourceOptions BUCKET_SOURCE_METAGENERATION =
      BucketSourceOptions.withMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Map<StorageRpcClient.StorageOption, ?> BUCKET_SOURCE_OPTIONS =
      ImmutableMap.of(
          StorageRpcClient.StorageOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());
  private static final Storage.GetBucketOption BUCKET_GET_METAGENERATION =
      Storage.GetBucketOption.withMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Storage.GetBucketOption BUCKET_GET_FIELDS =
      Storage.GetBucketOption.withFields(Storage.BucketAttribute.LOCATION, Storage.BucketAttribute.ACL);
  private static final Storage.GetBucketOption BUCKET_GET_EMPTY_FIELDS =
      Storage.GetBucketOption.withFields();
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
  private static final BlobSourceOptions BLOB_SOURCE_METAGENERATION =
      Storage.BlobSourceOptions.ifMetagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final Storage.BlobSourceOptions BLOB_SOURCE_GENERATION =
      BlobSourceOptions.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final Storage.BlobSourceOptions BLOB_SOURCE_GENERATION_FROM_BLOB_ID =
      Storage.BlobSourceOptions.ifGenerationMatch();
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
  private static final AccessControlEntry ACL = AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), RoleType.OWNER);
  private static final AccessControlEntry OTHER_ACL = AccessControlEntry.create(new AccessControlEntry.ProjectInfo(ProjectInfo.ProjectMemberRole.OWNERS, "p"), AccessControlEntry.RoleType.READER);

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

  private static final ServiceAccountInfo SERVICE_ACCOUNT = ServiceAccountInfo.from("test@google.com");

  private static final com.google.api.services.storage.model.Policy API_POLICY1 =
      new com.google.api.services.storage.model.Policy()
          .setBindings(
              ImmutableList.of(
                  new Bindings()
                      .setMembers(ImmutableList.of("allUsers"))
                      .setRole("roles/storage.objectViewer"),
                  new Bindings()
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

  private StorageClientOptions options;
  private StorageRpcFactory rpcFactoryMock;
  private StorageRpcClient storageRpcMock;
  private Storage storage;

  private StorageObject expectedBlob1, expectedBlob2, expectedBlob3;
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
    rpcFactoryMock = EasyMock.createMock(StorageRpcFactory.class);
    storageRpcMock = EasyMock.createMock(StorageRpcClient.class);
    EasyMock.expect(rpcFactoryMock.create(EasyMock.anyObject(StorageClientOptions.class)))
        .andReturn(storageRpcMock);
    EasyMock.replay(rpcFactoryMock);
    options =
        StorageClientOptions.newStorageClientBuilder()
            .setProjectId("projectId")
            .setClock(TIME_SOURCE)
            .setServiceRpcFactory(rpcFactoryMock)
            .setRetrySettings(ServiceOptions.getNoRetrySettings())
            .build();
  }

  @After
  public void tearDown() throws Exception {
    EasyMock.verify(rpcFactoryMock, storageRpcMock);
  }

  private void initializeService() {
    storage = options.getService();
    initializeServiceDependentObjects();
  }

  private void initializeServiceDependentObjects() {
    expectedBlob1 = new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1));
    expectedBlob2 = new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO2));
    expectedBlob3 = new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO3));
    expectedBucket1 = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO1));
    expectedBucket2 = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO2));
    expectedBucket3 = new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(BUCKET_INFO3));
  }

  @Test
  public void testUpdateBucket() {
    BucketMetadata updatedBucketInfo = BUCKET_INFO1.asBuilder().setIndexPage("some-page").buildBucket();
    EasyMock.expect(storageRpcMock.patch(updatedBucketInfo.toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(updatedBucketInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBucket bucket = storage.update(updatedBucketInfo);
    assertEquals(new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(updatedBucketInfo)), bucket);
  }

  @Test
  public void testUpdateBucketWithOptions() {
    BucketMetadata updatedBucketInfo = BUCKET_INFO1.asBuilder().setIndexPage("some-page").buildBucket();
    EasyMock.expect(storageRpcMock.patch(updatedBucketInfo.toProto(), BUCKET_TARGET_OPTIONS))
        .andReturn(updatedBucketInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBucket bucket =
        storage.update(
            updatedBucketInfo, BUCKET_TARGET_METAGENERATION, BUCKET_TARGET_PREDEFINED_ACL);
    assertEquals(new StorageBucket(storage, new BucketMetadata.BucketBuilderImpl(updatedBucketInfo)), bucket);
  }

  @Test
  public void testUpdateBlob() {
    BlobMetadata updatedBlobInfo = BLOB_INFO1.toInfoBuilder().setContentType("some-content-type").buildObject();
    EasyMock.expect(storageRpcMock.patch(updatedBlobInfo.toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(updatedBlobInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageObject blob = storage.update(updatedBlobInfo);
    assertEquals(new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(updatedBlobInfo)), blob);
  }

  @Test
  public void testUpdateBlobWithOptions() {
    BlobMetadata updatedBlobInfo = BLOB_INFO1.toInfoBuilder().setContentType("some-content-type").buildObject();
    EasyMock.expect(storageRpcMock.patch(updatedBlobInfo.toProto(), BLOB_TARGET_OPTIONS_UPDATE))
        .andReturn(updatedBlobInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageObject blob =
        storage.update(updatedBlobInfo, BLOB_TARGET_METAGENERATION, BLOB_TARGET_PREDEFINED_ACL);
    assertEquals(new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(updatedBlobInfo)), blob);
  }

  @Test
  public void testDeleteBucket() {
    EasyMock.expect(storageRpcMock.delete(BucketMetadata.ofName(BUCKET_NAME1).toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1));
  }

  @Test
  public void testDeleteBucketWithOptions() {
    EasyMock.expect(
            storageRpcMock.delete(BucketMetadata.ofName(BUCKET_NAME1).toProto(), BUCKET_SOURCE_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1, BUCKET_SOURCE_METAGENERATION));
  }

  @Test
  public void testDeleteBlob() {
    EasyMock.expect(
            storageRpcMock.delete(BlobId.from(BUCKET_NAME1, BLOB_NAME1).toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1, BLOB_NAME1));
  }

  @Test
  public void testDeleteBlobWithOptions() {
    EasyMock.expect(
            storageRpcMock.delete(BlobId.from(BUCKET_NAME1, BLOB_NAME1).toProto(), BLOB_SOURCE_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(
        storage.delete(
            BUCKET_NAME1, BLOB_NAME1, BLOB_SOURCE_GENERATION, BLOB_SOURCE_METAGENERATION));
  }

  @Test
  public void testDeleteBlobWithOptionsFromBlobId() {
    EasyMock.expect(storageRpcMock.delete(BLOB_INFO1.getBlobId().toProto(), BLOB_SOURCE_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(
        storage.delete(
            BLOB_INFO1.getBlobId(),
            BLOB_SOURCE_GENERATION_FROM_BLOB_ID,
            BLOB_SOURCE_METAGENERATION));
  }

  @Test
  public void testCompose() {
    Storage.ComposeBlobsRequest req =
        Storage.ComposeBlobsRequest.newSourceTargetBuilder()
            .addSources(BLOB_NAME2, BLOB_NAME3)
            .setTarget(BLOB_INFO1)
            .buildRequest();
    EasyMock.expect(
            storageRpcMock.compose(
                ImmutableList.of(BLOB_INFO2.toProto(), BLOB_INFO3.toProto()),
                BLOB_INFO1.toProto(),
                EMPTY_RPC_OPTIONS))
        .andReturn(BLOB_INFO1.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageObject blob = storage.compose(req);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testComposeWithOptions() {
    Storage.ComposeBlobsRequest req =
        Storage.ComposeBlobsRequest.newSourceTargetBuilder()
            .addSources(BLOB_NAME2, BLOB_NAME3)
            .setTarget(BLOB_INFO1)
            .setTargetOptions(BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .buildRequest();
    EasyMock.expect(
            storageRpcMock.compose(
                ImmutableList.of(BLOB_INFO2.toProto(), BLOB_INFO3.toProto()),
                BLOB_INFO1.toProto(),
                BLOB_TARGET_OPTIONS_COMPOSE))
        .andReturn(BLOB_INFO1.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageObject blob = storage.compose(req);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testCopy() {
    ChunkedCopyRequest request = Storage.ChunkedCopyRequest.create(BLOB_INFO1.getBlobId(), BLOB_INFO2.getBlobId());
    StorageRpcClient.RewriteOperationRequest rpcRequest =
        new StorageRpcClient.RewriteOperationRequest(
            request.getSource().toProto(),
            EMPTY_RPC_OPTIONS,
            false,
            BLOB_INFO2.toProto(),
            EMPTY_RPC_OPTIONS,
            null);
    StorageRpcClient.RewriteResult rpcResponse =
        new StorageRpcClient.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    ObjectCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithOptions() {
    Storage.ChunkedCopyRequest request =
        ChunkedCopyRequest.newCopyOperationBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BLOB_SOURCE_GENERATION, BLOB_SOURCE_METAGENERATION)
            .setTarget(BLOB_INFO1, BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .buildCopyRequest();
    StorageRpcClient.RewriteOperationRequest rpcRequest =
        new StorageRpcClient.RewriteOperationRequest(
            request.getSource().toProto(),
            BLOB_SOURCE_OPTIONS_COPY,
            true,
            request.getTarget().toProto(),
            BLOB_TARGET_OPTIONS_COMPOSE,
            null);
    StorageRpcClient.RewriteResult rpcResponse =
        new StorageRpcClient.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    ObjectCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithEncryptionKey() {
    ChunkedCopyRequest request =
        ChunkedCopyRequest.newCopyOperationBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(Storage.BlobSourceOptions.customerDecryptionKey(KEY))
            .setTarget(BLOB_INFO1, Storage.BlobUploadOption.withEncryptionKey(BASE64_KEY))
            .buildCopyRequest();
    StorageRpcClient.RewriteOperationRequest rpcRequest =
        new StorageRpcClient.RewriteOperationRequest(
            request.getSource().toProto(),
            ENCRYPTION_KEY_OPTIONS,
            true,
            request.getTarget().toProto(),
            ENCRYPTION_KEY_OPTIONS,
            null);
    StorageRpcClient.RewriteResult rpcResponse =
        new StorageRpcClient.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse).times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    ObjectCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
    request =
        ChunkedCopyRequest.newCopyOperationBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(Storage.BlobSourceOptions.customerDecryptionKey(BASE64_KEY))
            .setTarget(BLOB_INFO1, Storage.BlobUploadOption.withEncryptionKey(KEY))
            .buildCopyRequest();
    writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyFromEncryptionKeyToKmsKeyName() {
    Storage.ChunkedCopyRequest request =
        Storage.ChunkedCopyRequest.newCopyOperationBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(Storage.BlobSourceOptions.customerDecryptionKey(KEY))
            .setTarget(BLOB_INFO1, Storage.BlobUploadOption.withKmsKeyName(KMS_KEY_NAME))
            .buildCopyRequest();
    StorageRpcClient.RewriteOperationRequest rpcRequest =
        new StorageRpcClient.RewriteOperationRequest(
            request.getSource().toProto(),
            ENCRYPTION_KEY_OPTIONS,
            true,
            request.getTarget().toProto(),
            KMS_KEY_NAME_OPTIONS,
            null);
    StorageRpcClient.RewriteResult rpcResponse =
        new StorageRpcClient.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse).times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    ObjectCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
    request =
        ChunkedCopyRequest.newCopyOperationBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(Storage.BlobSourceOptions.customerDecryptionKey(BASE64_KEY))
            .setTarget(BLOB_INFO1, Storage.BlobUploadOption.withKmsKeyName(KMS_KEY_NAME))
            .buildCopyRequest();
    writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithOptionsFromBlobId() {
    ChunkedCopyRequest request =
        ChunkedCopyRequest.newCopyOperationBuilder()
            .setSource(BLOB_INFO1.getBlobId())
            .setSourceOptions(BLOB_SOURCE_GENERATION_FROM_BLOB_ID, BLOB_SOURCE_METAGENERATION)
            .setTarget(BLOB_INFO1, BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .buildCopyRequest();
    StorageRpcClient.RewriteOperationRequest rpcRequest =
        new StorageRpcClient.RewriteOperationRequest(
            request.getSource().toProto(),
            BLOB_SOURCE_OPTIONS_COPY,
            true,
            request.getTarget().toProto(),
            BLOB_TARGET_OPTIONS_COMPOSE,
            null);
    StorageRpcClient.RewriteResult rpcResponse =
        new StorageRpcClient.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    ObjectCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyMultipleRequests() {
    ChunkedCopyRequest request = ChunkedCopyRequest.create(BLOB_INFO1.getBlobId(), BLOB_INFO2.getBlobId());
    StorageRpcClient.RewriteOperationRequest rpcRequest =
        new StorageRpcClient.RewriteOperationRequest(
            request.getSource().toProto(),
            EMPTY_RPC_OPTIONS,
            false,
            BLOB_INFO2.toProto(),
            EMPTY_RPC_OPTIONS,
            null);
    StorageRpcClient.RewriteResult rpcResponse1 =
        new StorageRpcClient.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    StorageRpcClient.RewriteResult rpcResponse2 =
        new StorageRpcClient.RewriteResult(rpcRequest, BLOB_INFO1.toProto(), 42L, true, "token", 42L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse1);
    EasyMock.expect(storageRpcMock.continueRewrite(rpcResponse1)).andReturn(rpcResponse2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    ObjectCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
    assertEquals(expectedBlob1, writer.getResult());
    assertTrue(writer.isDone());
    assertEquals(42L, writer.getTotalBytesCopied());
    assertEquals(42L, writer.getBlobSize());
  }

  @Test
  public void testReadAllBytes() {
    EasyMock.expect(
            storageRpcMock.load(BlobId.from(BUCKET_NAME1, BLOB_NAME1).toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(BLOB_CONTENT);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes = storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1);
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesWithOptions() {
    EasyMock.expect(
            storageRpcMock.load(BlobId.from(BUCKET_NAME1, BLOB_NAME1).toProto(), BLOB_SOURCE_OPTIONS))
        .andReturn(BLOB_CONTENT);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(
            BUCKET_NAME1, BLOB_NAME1, BLOB_SOURCE_GENERATION, BLOB_SOURCE_METAGENERATION);
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesWithDecriptionKey() {
    EasyMock.expect(
            storageRpcMock.load(BlobId.from(BUCKET_NAME1, BLOB_NAME1).toProto(), ENCRYPTION_KEY_OPTIONS))
        .andReturn(BLOB_CONTENT)
        .times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1, Storage.BlobSourceOptions.customerDecryptionKey(KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
    readBytes =
        storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1, Storage.BlobSourceOptions.customerDecryptionKey(BASE64_KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesFromBlobIdWithOptions() {
    EasyMock.expect(storageRpcMock.load(BLOB_INFO1.getBlobId().toProto(), BLOB_SOURCE_OPTIONS))
        .andReturn(BLOB_CONTENT);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(
            BLOB_INFO1.getBlobId(),
            BLOB_SOURCE_GENERATION_FROM_BLOB_ID,
            BLOB_SOURCE_METAGENERATION);
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesFromBlobIdWithDecriptionKey() {
    EasyMock.expect(storageRpcMock.load(BLOB_INFO1.getBlobId().toProto(), ENCRYPTION_KEY_OPTIONS))
        .andReturn(BLOB_CONTENT)
        .times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(BLOB_INFO1.getBlobId(), BlobSourceOptions.customerDecryptionKey(KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
    readBytes =
        storage.readAllBytes(BLOB_INFO1.getBlobId(), Storage.BlobSourceOptions.customerDecryptionKey(BASE64_KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testBatch() {
    RpcBatch batchMock = EasyMock.mock(RpcBatch.class);
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    EasyMock.replay(batchMock, storageRpcMock);
    initializeService();
    StorageOperationBatch batch = storage.batch();
    assertSame(options, batch.getOptions());
    assertSame(storageRpcMock, batch.getStorageRpc());
    assertSame(batchMock, batch.getBatch());
    EasyMock.verify(batchMock);
  }

  @Test
  public void testSignUrl()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url = storage.signUrl(BLOB_INFO1, 14, TimeUnit.DAYS);
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlWithHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.setHostName("https://example.com"));
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlLeadingSlash()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    String blobName = "/b1";
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(BlobMetadata.newBuilder(BUCKET_NAME1, blobName).buildObject(), 14, TimeUnit.DAYS);
    String expectedResourcePath = "/b1";
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append("/")
            .append(expectedResourcePath)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append("/")
        .append(expectedResourcePath);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlLeadingSlashWithHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    String blobName = "/b1";
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(
            BlobMetadata.newBuilder(BUCKET_NAME1, blobName).buildObject(),
            14,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.setHostName("https://example.com"));
    String escapedBlobName = Rfc3986UriEncode(blobName, false);
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append("/")
            .append(escapedBlobName)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append("/")
        .append(escapedBlobName);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlWithOptions()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.withHttpMethod(HttpRequestMethod.POST),
            Storage.UrlSigningOption.includeContentType(),
            Storage.UrlSigningOption.includeMd5());
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.POST)
        .append('\n')
        .append(BLOB_INFO1.getMd5())
        .append('\n')
        .append(BLOB_INFO1.getContentType())
        .append('\n')
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlWithOptionsAndHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.withHttpMethod(HttpRequestMethod.POST),
            Storage.UrlSigningOption.includeContentType(),
            Storage.UrlSigningOption.includeMd5(),
            Storage.UrlSigningOption.setHostName("https://example.com"));
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.POST)
        .append('\n')
        .append(BLOB_INFO1.getMd5())
        .append('\n')
        .append(BLOB_INFO1.getContentType())
        .append('\n')
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlForBlobWithSpecialChars()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    Map<Character, String> encodingCharsToTest =
        new HashMap<Character, String>(RFC3986_URI_ENCODING_MAP);
    // Signed URL specs say that '/' is not encoded in the resource name (path segment of the URI).
    encodingCharsToTest.put('/', "/");
    for (Map.Entry<Character, String> entry : encodingCharsToTest.entrySet()) {
      String blobName = "/a" + entry.getKey() + "b";
      URL url =
          storage.signUrl(BlobMetadata.newBuilder(BUCKET_NAME1, blobName).buildObject(), 14, TimeUnit.DAYS);
      String expectedBlobName = "/a" + entry.getValue() + "b";
      String stringUrl = url.toString();
      String expectedUrl =
          new StringBuilder("https://storage.googleapis.com/")
              .append(BUCKET_NAME1)
              .append("/")
              .append(expectedBlobName)
              .append("?GoogleAccessId=")
              .append(ACCOUNT)
              .append("&Expires=")
              .append(42L + 1209600)
              .append("&Signature=")
              .toString();
      assertTrue(stringUrl.startsWith(expectedUrl));
      String signature = stringUrl.substring(expectedUrl.length());

      StringBuilder signedMessageBuilder = new StringBuilder();
      signedMessageBuilder
          .append(HttpRequestMethod.GET)
          .append("\n\n\n")
          .append(42L + 1209600)
          .append("\n/")
          .append(BUCKET_NAME1)
          .append("/")
          .append(expectedBlobName);

      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initVerify(publicKey);
      signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
      assertTrue(
          signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
    }
  }

  @Test
  public void testSignUrlForBlobWithSpecialCharsAndHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    Map<Character, String> encodingCharsToTest =
        new HashMap<Character, String>(RFC3986_URI_ENCODING_MAP);
    // Signed URL specs say that '/' is not encoded in the resource name (path segment of the URI).
    encodingCharsToTest.put('/', "/");
    for (Map.Entry<Character, String> entry : encodingCharsToTest.entrySet()) {
      String blobName = "/a" + entry.getKey() + "b";
      URL url =
          storage.signUrl(
              BlobMetadata.newBuilder(BUCKET_NAME1, blobName).buildObject(),
              14,
              TimeUnit.DAYS,
              Storage.UrlSigningOption.setHostName("https://example.com"));
      String expectedBlobName = "/a" + entry.getValue() + "b";
      String stringUrl = url.toString();
      String expectedUrl =
          new StringBuilder("https://example.com/")
              .append(BUCKET_NAME1)
              .append("/")
              .append(expectedBlobName)
              .append("?GoogleAccessId=")
              .append(ACCOUNT)
              .append("&Expires=")
              .append(42L + 1209600)
              .append("&Signature=")
              .toString();
      assertTrue(stringUrl.startsWith(expectedUrl));
      String signature = stringUrl.substring(expectedUrl.length());

      StringBuilder signedMessageBuilder = new StringBuilder();
      signedMessageBuilder
          .append(HttpRequestMethod.GET)
          .append("\n\n\n")
          .append(42L + 1209600)
          .append("\n/")
          .append(BUCKET_NAME1)
          .append("/")
          .append(expectedBlobName);

      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initVerify(publicKey);
      signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
      assertTrue(
          signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
    }
  }

  @Test
  public void testSignUrlWithExtHeaders()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    Map<String, String> extHeaders = new HashMap<String, String>();
    extHeaders.put("x-goog-acl", "public-read");
    extHeaders.put("x-goog-meta-owner", "myself");
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.withHttpMethod(HttpRequestMethod.PUT),
            Storage.UrlSigningOption.includeContentType(),
            Storage.UrlSigningOption.includeExtHeaders(extHeaders));
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.PUT)
        .append('\n')
        .append('\n')
        .append(BLOB_INFO1.getContentType())
        .append('\n')
        .append(42L + 1209600)
        .append('\n')
        .append("x-goog-acl:public-read\n")
        .append("x-goog-meta-owner:myself\n")
        .append('/')
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlWithExtHeadersAndHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    Map<String, String> extHeaders = new HashMap<String, String>();
    extHeaders.put("x-goog-acl", "public-read");
    extHeaders.put("x-goog-meta-owner", "myself");
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.withHttpMethod(HttpRequestMethod.PUT),
            Storage.UrlSigningOption.includeContentType(),
            Storage.UrlSigningOption.includeExtHeaders(extHeaders),
            Storage.UrlSigningOption.setHostName("https://example.com"));
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.PUT)
        .append('\n')
        .append('\n')
        .append(BLOB_INFO1.getContentType())
        .append('\n')
        .append(42L + 1209600)
        .append('\n')
        .append("x-goog-acl:public-read\n")
        .append("x-goog-meta-owner:myself\n")
        .append('/')
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlForBlobWithSlashes()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    String blobName = "/foo/bar/baz #%20other cool stuff.txt";
    URL url =
        storage.signUrl(BlobMetadata.newBuilder(BUCKET_NAME1, blobName).buildObject(), 14, TimeUnit.DAYS);
    String escapedBlobName = Rfc3986UriEncode(blobName, false);
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append("/")
            .append(escapedBlobName)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append("/")
        .append(escapedBlobName);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlForBlobWithSlashesAndHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    String blobName = "/foo/bar/baz #%20other cool stuff.txt";
    URL url =
        storage.signUrl(
            BlobMetadata.newBuilder(BUCKET_NAME1, blobName).buildObject(),
            14,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.setHostName("https://example.com"));
    String escapedBlobName = Rfc3986UriEncode(blobName, false);
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append("/")
            .append(escapedBlobName)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append("/")
        .append(escapedBlobName);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testV2SignUrlWithQueryParams()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    String dispositionNotEncoded = "attachment; filename=\"" + BLOB_NAME1 + "\"";
    String dispositionEncoded = "attachment%3B%20filename%3D%22" + BLOB_NAME1 + "%22";
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.enablePathStyle(),
            Storage.UrlSigningOption.useV2Signature(),
            Storage.UrlSigningOption.setQueryParams(
                ImmutableMap.<String, String>of(
                    "response-content-disposition", dispositionNotEncoded)));

    String stringUrl = url.toString();

    String expectedPrefix =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            // Query params aren't sorted for V2 signatures; user-supplied params are inserted at
            // the start of the query string, before the required auth params.
            .append("?response-content-disposition=")
            .append(dispositionEncoded)
            .append("&GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedPrefix));
    String signature = stringUrl.substring(expectedPrefix.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpRequestMethod.GET)
        .append('\n')
        // No value for Content-MD5, blank
        .append('\n')
        // No value for Content-Type, blank
        .append('\n')
        // Expiration line:
        .append(42L + 1209600)
        .append('\n')
        // Resource line:
        .append('/')
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  // TODO(b/144304815): Remove this test once all conformance tests contain query param test cases.
  @Test
  public void testV4SignUrlWithQueryParams() {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    String dispositionNotEncoded = "attachment; filename=\"" + BLOB_NAME1 + "\"";
    String dispositionEncoded = "attachment%3B%20filename%3D%22" + BLOB_NAME1 + "%22";
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            6,
            TimeUnit.DAYS,
            Storage.UrlSigningOption.enablePathStyle(),
            Storage.UrlSigningOption.useV4Signature(),
            Storage.UrlSigningOption.setQueryParams(
                ImmutableMap.<String, String>of(
                    "response-content-disposition", dispositionNotEncoded)));
    String stringUrl = url.toString();
    String expectedPrefix =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append('?')
            .toString();
    assertTrue(stringUrl.startsWith(expectedPrefix));
    String restOfUrl = stringUrl.substring(expectedPrefix.length());

    Pattern pattern =
        Pattern.compile(
            // We use the same code to construct the canonical request query string as we do to
            // construct the query string used in the final URL, so this query string should also be
            // sorted correctly, except for the trailing x-goog-signature param.
            new StringBuilder("X-Goog-Algorithm=GOOG4-RSA-SHA256")
                .append("&X-Goog-Credential=[^&]+")
                .append("&X-Goog-Date=[^&]+")
                .append("&X-Goog-Expires=[^&]+")
                .append("&X-Goog-SignedHeaders=[^&]+")
                .append("&response-content-disposition=[^&]+")
                // Signature is always tacked onto the end of the final URL; it's not sorted w/ the
                // other params above, since the signature is not known when you're constructing the
                // query string line of the canonical request string.
                .append("&X-Goog-Signature=.*")
                .toString());
    Matcher matcher = pattern.matcher(restOfUrl);
    assertTrue(restOfUrl, matcher.matches());

    // Make sure query param was encoded properly.
    assertNotEquals(-1, restOfUrl.indexOf("&response-content-disposition=" + dispositionEncoded));
  }

  @Test
  public void testGetAllArray() {
    BlobId blobId1 = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    BlobId blobId2 = BlobId.from(BUCKET_NAME1, BLOB_NAME2);
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>> callback2 = Capture.newInstance();
    batchMock.addGet(
        EasyMock.eq(blobId1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    batchMock.addGet(
        EasyMock.eq(blobId2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<StorageObject> resultBlobs = storage.get(blobId1, blobId2);
    callback1.getValue().onSuccess(BLOB_INFO1.toProto());
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testGetAllArrayIterable() {
    BlobId blobId1 = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    BlobId blobId2 = BlobId.from(BUCKET_NAME1, BLOB_NAME2);
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>> callback2 = Capture.newInstance();
    batchMock.addGet(
        EasyMock.eq(blobId1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    batchMock.addGet(
        EasyMock.eq(blobId2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<StorageObject> resultBlobs = storage.get(ImmutableList.of(blobId1, blobId2));
    callback1.getValue().onSuccess(BLOB_INFO1.toProto());
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testDeleteAllArray() {
    BlobId blobId1 = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    BlobId blobId2 = BlobId.from(BUCKET_NAME1, BLOB_NAME2);
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<Void>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<Void>> callback2 = Capture.newInstance();
    batchMock.addDelete(
        EasyMock.eq(blobId1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    batchMock.addDelete(
        EasyMock.eq(blobId2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Boolean> result = storage.delete(blobId1, blobId2);
    callback1.getValue().onSuccess(null);
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, result.size());
    assertTrue(result.get(0));
    assertFalse(result.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testDeleteAllIterable() {
    BlobId blobId1 = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    BlobId blobId2 = BlobId.from(BUCKET_NAME1, BLOB_NAME2);
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<Void>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<Void>> callback2 = Capture.newInstance();
    batchMock.addDelete(
        EasyMock.eq(blobId1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    batchMock.addDelete(
        EasyMock.eq(blobId2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Boolean> result = storage.delete(blobId1, blobId2);
    callback1.getValue().onSuccess(null);
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, result.size());
    assertTrue(result.get(0));
    assertFalse(result.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testUpdateAllArray() {
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>> callback2 = Capture.newInstance();
    batchMock.addPatch(
        EasyMock.eq(BLOB_INFO1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    batchMock.addPatch(
        EasyMock.eq(BLOB_INFO2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<StorageObject> resultBlobs = storage.update(BLOB_INFO1, BLOB_INFO2);
    callback1.getValue().onSuccess(BLOB_INFO1.toProto());
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testUpdateAllIterable() {
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<com.google.api.services.storage.model.StorageObject>> callback2 = Capture.newInstance();
    batchMock.addPatch(
        EasyMock.eq(BLOB_INFO1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    batchMock.addPatch(
        EasyMock.eq(BLOB_INFO2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpcClient.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<StorageObject> resultBlobs = storage.update(ImmutableList.of(BLOB_INFO1, BLOB_INFO2));
    callback1.getValue().onSuccess(BLOB_INFO1.toProto());
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new StorageObject(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testGetBucketAcl() {
    EasyMock.expect(
            storageRpcMock.getAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageRpcClient.StorageOption, Object>()))
        .andReturn(ACL.toBucketProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.getAcl(BUCKET_NAME1, AccessControlEntry.UserIdentity.allAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetBucketAclNull() {
    EasyMock.expect(
            storageRpcMock.getAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageRpcClient.StorageOption, Object>()))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getAcl(BUCKET_NAME1, AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteBucketAcl() {
    EasyMock.expect(
            storageRpcMock.deleteAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageRpcClient.StorageOption, Object>()))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteAcl(BUCKET_NAME1, AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateBucketAcl() {
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    EasyMock.expect(
            storageRpcMock.createAcl(
                ACL.toBucketProto().setBucket(BUCKET_NAME1), new HashMap<StorageRpcClient.StorageOption, Object>()))
        .andReturn(returnedAcl.toBucketProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.createAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testUpdateBucketAcl() {
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    EasyMock.expect(
            storageRpcMock.patchAcl(
                ACL.toBucketProto().setBucket(BUCKET_NAME1), new HashMap<StorageRpcClient.StorageOption, Object>()))
        .andReturn(returnedAcl.toBucketProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.updateAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testListBucketAcl() {
    EasyMock.expect(storageRpcMock.listAcls(BUCKET_NAME1, new HashMap<StorageRpcClient.StorageOption, Object>()))
        .andReturn(ImmutableList.of(ACL.toBucketProto(), OTHER_ACL.toBucketProto()));
    EasyMock.replay(storageRpcMock);
    initializeService();
    List<AccessControlEntry> acls = storage.listAcls(BUCKET_NAME1);
    assertEquals(ImmutableList.of(ACL, OTHER_ACL), acls);
  }

  @Test
  public void testGetDefaultBucketAcl() {
    EasyMock.expect(storageRpcMock.getDefaultAcl(BUCKET_NAME1, "allAuthenticatedUsers"))
        .andReturn(ACL.toObjectProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.getDefaultAcl(BUCKET_NAME1, AccessControlEntry.UserIdentity.allAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetDefaultBucketAclNull() {
    EasyMock.expect(storageRpcMock.getDefaultAcl(BUCKET_NAME1, "allAuthenticatedUsers"))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getDefaultAcl(BUCKET_NAME1, AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteDefaultBucketAcl() {
    EasyMock.expect(storageRpcMock.deleteDefaultAcl(BUCKET_NAME1, "allAuthenticatedUsers"))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteDefaultAcl(BUCKET_NAME1, AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateDefaultBucketAcl() {
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    EasyMock.expect(storageRpcMock.createDefaultAcl(ACL.toObjectProto().setBucket(BUCKET_NAME1)))
        .andReturn(returnedAcl.toObjectProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.createDefaultAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testUpdateDefaultBucketAcl() {
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    EasyMock.expect(storageRpcMock.patchDefaultAcl(ACL.toObjectProto().setBucket(BUCKET_NAME1)))
        .andReturn(returnedAcl.toObjectProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.updateDefaultAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testListDefaultBucketAcl() {
    EasyMock.expect(storageRpcMock.listDefaultAcls(BUCKET_NAME1))
        .andReturn(ImmutableList.of(ACL.toObjectProto(), OTHER_ACL.toObjectProto()));
    EasyMock.replay(storageRpcMock);
    initializeService();
    List<AccessControlEntry> acls = storage.listDefaultAcls(BUCKET_NAME1);
    assertEquals(ImmutableList.of(ACL, OTHER_ACL), acls);
  }

  @Test
  public void testGetBlobAcl() {
    BlobId blobId = BlobId.from(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(storageRpcMock.getAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(ACL.toObjectProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.getAcl(blobId, AccessControlEntry.UserIdentity.allAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetBlobAclNull() {
    BlobId blobId = BlobId.from(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(storageRpcMock.getAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getAcl(blobId, AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteBlobAcl() {
    BlobId blobId = BlobId.from(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(
            storageRpcMock.deleteAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteAcl(blobId, AccessControlEntry.UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateBlobAcl() {
    BlobId blobId = BlobId.from(BUCKET_NAME1, BLOB_NAME1, 42L);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    EasyMock.expect(
            storageRpcMock.createAcl(
                ACL.toObjectProto().setBucket(BUCKET_NAME1).setObject(BLOB_NAME1).setGeneration(42L)))
        .andReturn(returnedAcl.toObjectProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.createAcl(blobId, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testUpdateBlobAcl() {
    BlobId blobId = BlobId.from(BUCKET_NAME1, BLOB_NAME1, 42L);
    AccessControlEntry returnedAcl = ACL.asBuilder().setEtag("ETAG").setId("ID").buildInstance();
    EasyMock.expect(
            storageRpcMock.patchAcl(
                ACL.toObjectProto().setBucket(BUCKET_NAME1).setObject(BLOB_NAME1).setGeneration(42L)))
        .andReturn(returnedAcl.toObjectProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.updateAcl(blobId, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testListBlobAcl() {
    BlobId blobId = BlobId.from(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(storageRpcMock.listAcls(BUCKET_NAME1, BLOB_NAME1, 42L))
        .andReturn(ImmutableList.of(ACL.toObjectProto(), OTHER_ACL.toObjectProto()));
    EasyMock.replay(storageRpcMock);
    initializeService();
    List<AccessControlEntry> acls = storage.listAcls(blobId);
    assertEquals(ImmutableList.of(ACL, OTHER_ACL), acls);
  }

  @Test
  public void testGetIamPolicy() {
    EasyMock.expect(storageRpcMock.getIamPolicy(BUCKET_NAME1, EMPTY_RPC_OPTIONS))
        .andReturn(API_POLICY1);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertEquals(LIB_POLICY1, storage.getIamPolicy(BUCKET_NAME1));
  }

  @Test
  public void testSetIamPolicy() {
    com.google.api.services.storage.model.Policy preCommitApiPolicy =
        new com.google.api.services.storage.model.Policy()
            .setBindings(
                ImmutableList.of(
                    new Bindings()
                        .setMembers(ImmutableList.of("allUsers"))
                        .setRole("roles/storage.objectViewer"),
                    new Bindings()
                        .setMembers(
                            ImmutableList.of("user:test1@gmail.com", "user:test2@gmail.com"))
                        .setRole("roles/storage.objectAdmin"),
                    new Bindings()
                        .setMembers(ImmutableList.of("group:test-group@gmail.com"))
                        .setRole("roles/storage.admin")))
            .setEtag(POLICY_ETAG1)
            .setVersion(1);
    // postCommitApiPolicy is identical but for the etag, which has been updated.
    com.google.api.services.storage.model.Policy postCommitApiPolicy =
        new com.google.api.services.storage.model.Policy()
            .setBindings(
                ImmutableList.of(
                    new Bindings()
                        .setMembers(ImmutableList.of("allUsers"))
                        .setRole("roles/storage.objectViewer"),
                    new Bindings()
                        .setMembers(
                            ImmutableList.of("user:test1@gmail.com", "user:test2@gmail.com"))
                        .setRole("roles/storage.objectAdmin"),
                    new Bindings()
                        .setMembers(ImmutableList.of("group:test-group@gmail.com"))
                        .setRole("roles/storage.admin")))
            .setEtag(POLICY_ETAG2)
            .setVersion(1);
    Policy postCommitLibPolicy =
        Policy.newBuilder()
            .addIdentity(StorageRoles.objectViewer(), Identity.allUsers())
            .addIdentity(
                StorageRoles.objectAdmin(),
                Identity.user("test1@gmail.com"),
                Identity.user("test2@gmail.com"))
            .addIdentity(StorageRoles.admin(), Identity.group("test-group@gmail.com"))
            .setEtag(POLICY_ETAG2)
            .setVersion(1)
            .build();

    EasyMock.expect(storageRpcMock.getIamPolicy(BUCKET_NAME1, EMPTY_RPC_OPTIONS))
        .andReturn(API_POLICY1);
    EasyMock.expect(
            storageRpcMock.setIamPolicy(
                EasyMock.eq(BUCKET_NAME1),
                eqApiPolicy(preCommitApiPolicy),
                EasyMock.eq(EMPTY_RPC_OPTIONS)))
        .andReturn(postCommitApiPolicy);
    EasyMock.replay(storageRpcMock);
    initializeService();

    Policy currentPolicy = storage.getIamPolicy(BUCKET_NAME1);
    Policy updatedPolicy =
        storage.setIamPolicy(
            BUCKET_NAME1,
            currentPolicy
                .toBuilder()
                .addIdentity(StorageRoles.admin(), Identity.group("test-group@gmail.com"))
                .build());
    assertEquals(updatedPolicy, postCommitLibPolicy);
  }

  @Test
  public void testTestIamPermissionsNull() {
    ImmutableList<Boolean> expectedPermissions = ImmutableList.of(false, false, false);
    ImmutableList<String> checkedPermissions =
        ImmutableList.of(
            "storage.buckets.get", "storage.buckets.getIamPolicy", "storage.objects.list");

    EasyMock.expect(
            storageRpcMock.testIamPermissions(BUCKET_NAME1, checkedPermissions, EMPTY_RPC_OPTIONS))
        .andReturn(new TestIamPermissionsResponse());
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertEquals(expectedPermissions, storage.testIamPermissions(BUCKET_NAME1, checkedPermissions));
  }

  @Test
  public void testTestIamPermissionsNonNull() {
    ImmutableList<Boolean> expectedPermissions = ImmutableList.of(true, false, true);
    ImmutableList<String> checkedPermissions =
        ImmutableList.of(
            "storage.buckets.get", "storage.buckets.getIamPolicy", "storage.objects.list");

    EasyMock.expect(
            storageRpcMock.testIamPermissions(BUCKET_NAME1, checkedPermissions, EMPTY_RPC_OPTIONS))
        .andReturn(
            new TestIamPermissionsResponse()
                .setPermissions(ImmutableList.of("storage.objects.list", "storage.buckets.get")));
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertEquals(expectedPermissions, storage.testIamPermissions(BUCKET_NAME1, checkedPermissions));
  }

  @Test
  public void testLockRetentionPolicy() {
    EasyMock.expect(
            storageRpcMock.lockRetentionPolicy(
                BUCKET_INFO3.toProto(), BUCKET_TARGET_OPTIONS_LOCK_RETENTION_POLICY))
        .andReturn(BUCKET_INFO3.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBucket bucket =
        storage.lockRetentionPolicy(
            BUCKET_INFO3, BUCKET_TARGET_METAGENERATION, BUCKET_TARGET_USER_PROJECT);
    assertEquals(expectedBucket3, bucket);
  }

  @Test
  public void testGetServiceAccount() {
    EasyMock.expect(storageRpcMock.getServiceAccount("projectId"))
        .andReturn(SERVICE_ACCOUNT.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    ServiceAccountInfo serviceAccount = storage.getServiceAccount("projectId");
    assertEquals(SERVICE_ACCOUNT, serviceAccount);
  }

  @Test
  public void testRetryableException() {
    BlobId blob = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    EasyMock.expect(storageRpcMock.get(blob.toProto(), EMPTY_RPC_OPTIONS))
        .andThrow(new StorageServiceException(500, "internalError"))
        .andReturn(BLOB_INFO1.toProto());
    EasyMock.replay(storageRpcMock);
    storage =
        options
            .toBuilder()
            .setRetrySettings(ServiceOptions.getDefaultRetrySettings())
            .build()
            .getService();
    initializeServiceDependentObjects();
    StorageObject readBlob = storage.get(blob);
    assertEquals(expectedBlob1, readBlob);
  }

  @Test
  public void testNonRetryableException() {
    BlobId blob = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    String exceptionMessage = "Not Implemented";
    EasyMock.expect(storageRpcMock.get(blob.toProto(), EMPTY_RPC_OPTIONS))
        .andThrow(new StorageServiceException(501, exceptionMessage));
    EasyMock.replay(storageRpcMock);
    storage =
        options
            .toBuilder()
            .setRetrySettings(ServiceOptions.getDefaultRetrySettings())
            .build()
            .getService();
    initializeServiceDependentObjects();
    try {
      storage.get(blob);
      Assert.fail();
    } catch (StorageServiceException ex) {
      Assert.assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testRuntimeException() {
    BlobId blob = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    String exceptionMessage = "Artificial runtime exception";
    EasyMock.expect(storageRpcMock.get(blob.toProto(), EMPTY_RPC_OPTIONS))
        .andThrow(new RuntimeException(exceptionMessage));
    EasyMock.replay(storageRpcMock);
    storage =
        options
            .toBuilder()
            .setRetrySettings(ServiceOptions.getDefaultRetrySettings())
            .build()
            .getService();
    try {
      storage.get(blob);
      Assert.fail();
    } catch (StorageServiceException ex) {
      Assert.assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testV4PostPolicy() {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    S3PostPolicyV4.PostFieldsMapV4 fields =
        S3PostPolicyV4.PostFieldsMapV4.newObjectMetadataBuilder().setAcl("public-read").buildMap();
    S3PostPolicyV4.PostConditionsV4Model conditions =
        S3PostPolicyV4.PostConditionsV4Model.newPolicyBuilder()
            .addContentType(S3PostPolicyV4.ConditionTypeV4.MATCHES, "image/jpeg")
            .buildModel();

    // test fields and conditions
    S3PostPolicyV4 policy =
        storage.generateSignedPostPolicyV4(
            BlobMetadata.newBuilder("my-bucket", "my-object").buildObject(),
            7,
            TimeUnit.DAYS,
            fields,
            conditions);

    Map<String, String> outputFields = policy.getFields();

    assertTrue(outputFields.containsKey("x-goog-date"));
    assertTrue(outputFields.containsKey("x-goog-credential"));
    assertTrue(outputFields.containsKey("x-goog-signature"));
    assertEquals(outputFields.get("x-goog-algorithm"), "GOOG4-RSA-SHA256");
    assertEquals(outputFields.get("content-type"), "image/jpeg");
    assertEquals(outputFields.get("acl"), "public-read");
    assertEquals(outputFields.get("key"), "my-object");
    assertEquals("https://storage.googleapis.com/my-bucket/", policy.getUrl());

    // test fields, no conditions
    policy =
        storage.generateSignedPostPolicyV4(
            BlobMetadata.newBuilder("my-bucket", "my-object").buildObject(), 7, TimeUnit.DAYS, conditions);
    outputFields = policy.getFields();

    assertTrue(outputFields.containsKey("x-goog-date"));
    assertTrue(outputFields.containsKey("x-goog-credential"));
    assertTrue(outputFields.containsKey("x-goog-signature"));
    assertEquals(outputFields.get("x-goog-algorithm"), "GOOG4-RSA-SHA256");
    assertEquals(outputFields.get("content-type"), "image/jpeg");
    assertEquals(outputFields.get("key"), "my-object");
    assertEquals("https://storage.googleapis.com/my-bucket/", policy.getUrl());

    // test conditions, no fields
    policy =
        storage.generateSignedPostPolicyV4(
            BlobMetadata.newBuilder("my-bucket", "my-object").buildObject(), 7, TimeUnit.DAYS, fields);
    outputFields = policy.getFields();
    assertTrue(outputFields.containsKey("x-goog-date"));
    assertTrue(outputFields.containsKey("x-goog-credential"));
    assertTrue(outputFields.containsKey("x-goog-signature"));
    assertEquals(outputFields.get("x-goog-algorithm"), "GOOG4-RSA-SHA256");
    assertEquals(outputFields.get("acl"), "public-read");
    assertEquals(outputFields.get("key"), "my-object");

    // test no conditions no fields
    policy =
        storage.generateSignedPostPolicyV4(
            BlobMetadata.newBuilder("my-bucket", "my-object").buildObject(), 7, TimeUnit.DAYS);
    outputFields = policy.getFields();
    assertTrue(outputFields.containsKey("x-goog-date"));
    assertTrue(outputFields.containsKey("x-goog-credential"));
    assertTrue(outputFields.containsKey("x-goog-signature"));
    assertEquals(outputFields.get("x-goog-algorithm"), "GOOG4-RSA-SHA256");
    assertEquals(outputFields.get("key"), "my-object");
    assertEquals("https://storage.googleapis.com/my-bucket/", policy.getUrl());
  }

  @Test
  public void testBucketLifecycleRules() {
    BucketMetadata bucketInfo =
        BucketMetadata.newBucketBuilder("b")
            .setLocation("us")
            .setLifecycleRules(
                ImmutableList.of(
                    new BucketMetadata.LifecycleRuleDefinition(
                        BucketMetadata.LifecycleRuleDefinition.LifecycleRuleAction.createSetStorageClassAction(
                            StorageTier.COLDLINE),
                        BucketMetadata.LifecycleRuleDefinition.LifecycleRuleCondition.builder()
                            .setAge(1)
                            .setNumberOfNewerVersions(3)
                            .setIsLive(false)
                            .setCreatedBefore(new DateTime(System.currentTimeMillis()))
                            .setMatchesStorageClass(ImmutableList.of(StorageTier.COLDLINE))
                            .setDaysSinceNoncurrentTime(30)
                            .setNoncurrentTimeBefore(new DateTime(System.currentTimeMillis()))
                            .setCustomTimeBefore(new DateTime(System.currentTimeMillis()))
                            .setDaysSinceCustomTime(30)
                            .buildCondition())))
            .buildBucket();
    EasyMock.expect(
            storageRpcMock.create(bucketInfo.toProto(), new HashMap<StorageRpcClient.StorageOption, Object>()))
        .andReturn(bucketInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBucket bucket = storage.create(bucketInfo);
    BucketMetadata.LifecycleRuleDefinition lifecycleRule = bucket.getLifecycleRules().get(0);
    assertEquals(3, lifecycleRule.getCondition().getNumberOfNewerVersions().intValue());
    assertNotNull(lifecycleRule.getCondition().getCreatedBefore());
    assertFalse(lifecycleRule.getCondition().getIsLive());
    assertEquals(1, lifecycleRule.getCondition().getAge().intValue());
    assertEquals(1, lifecycleRule.getCondition().getMatchesStorageClass().size());
    assertEquals(30, lifecycleRule.getCondition().getDaysSinceNoncurrentTime().intValue());
    assertNotNull(lifecycleRule.getCondition().getNoncurrentTimeBefore());
    assertEquals(30, lifecycleRule.getCondition().getDaysSinceCustomTime().intValue());
    assertNotNull(lifecycleRule.getCondition().getCustomTimeBefore());
  }

  @Test
  public void testDownloadTo() throws Exception {
    BlobId blob = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    storage = options.toBuilder().build().getService();
    final byte[] expected = {1, 2};
    EasyMock.expect(
            storageRpcMock.read(
                anyObject(com.google.api.services.storage.model.StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected);
                return 2l;
              }
            });
    EasyMock.replay(storageRpcMock);
    File file = File.createTempFile("blob", ".tmp");
    storage.downloadTo(blob, file.toPath());
    byte actual[] = Files.readAllBytes(file.toPath());
    assertArrayEquals(expected, actual);
  }

  @Test
  public void testDownloadToWithRetries() throws Exception {
    BlobId blob = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    storage =
        options
            .toBuilder()
            .setRetrySettings(RetrySettings.newBuilder().setMaxAttempts(2).build())
            .build()
            .getService();
    final byte[] expected = {1, 2};
    expect(
            storageRpcMock.read(
                anyObject(com.google.api.services.storage.model.StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected[0]);
                throw new StorageServiceException(504, "error");
              }
            });
    expect(
            storageRpcMock.read(
                anyObject(com.google.api.services.storage.model.StorageObject.class),
                anyObject(Map.class),
                eq(1l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected[1]);
                return 1l;
              }
            });
    replay(storageRpcMock);
    File file = File.createTempFile("blob", ".tmp");
    storage.downloadTo(blob, file.toPath());
    byte actual[] = Files.readAllBytes(file.toPath());
    assertArrayEquals(expected, actual);
  }

  @Test
  public void testDownloadToWithException() throws Exception {
    BlobId blob = BlobId.from(BUCKET_NAME1, BLOB_NAME1);
    storage = options.toBuilder().build().getService();
    Exception exception = new IllegalStateException("test");
    expect(
            storageRpcMock.read(
                anyObject(com.google.api.services.storage.model.StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andThrow(exception);
    replay(storageRpcMock);
    File file = File.createTempFile("blob", ".tmp");
    try {
      storage.downloadTo(blob, file.toPath());
      fail();
    } catch (StorageServiceException e) {
      assertSame(exception, e.getCause());
    }
  }
}
