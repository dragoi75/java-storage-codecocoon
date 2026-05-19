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

import static com.google.cloud.storage.SignedUrlEncoderHelper.rfc3986UriEncode;
import static com.google.cloud.storage.testing.ApiPolicyMatcher.eqApiPolicy;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.util.DateTime;
import com.google.api.core.ApiClock;
import com.google.api.services.storage.model.Policy.Bindings;
import com.google.api.services.storage.model.StorageObject;
import com.google.api.services.storage.model.TestIamPermissionsResponse;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.ServiceOptions;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.AccessControlEntry.UserRole;
import com.google.cloud.storage.AccessControlEntry.UserPrincipal;
import com.google.cloud.storage.CloudStorageClient.BlobReadOption;
import com.google.cloud.storage.CloudStorageClient.BlobUploadOptions;
import com.google.cloud.storage.CloudStorageClient.BlobWriteOptions;
import com.google.cloud.storage.CloudStorageClient.CopyOperationRequest;
import com.google.cloud.storage.spi.StorageRpcProvider;
import com.google.cloud.storage.spi.v1.RpcRequestBatch;
import com.google.cloud.storage.spi.v1.StorageServiceRpc;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
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
  private static final BucketInfo BUCKET_INFO1 =
      BucketInfo.newBucketBuilder(BUCKET_NAME1).setMetageneration(42L).buildBucketInfo();
  private static final BucketInfo BUCKET_INFO2 = BucketInfo.newBucketBuilder(BUCKET_NAME2).buildBucketInfo();
  private static final BucketInfo BUCKET_INFO3 =
      BucketInfo.newBucketBuilder(BUCKET_NAME3)
          .setRetentionPeriod(RETENTION_PERIOD)
          .setRetentionPolicyIsLocked(true)
          .setMetageneration(42L)
          .buildBucketInfo();

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
  private static final Map<StorageServiceRpc.StorageOption, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  // Bucket target options
  private static final CloudStorageClient.BucketTargetOptions BUCKET_TARGET_METAGENERATION =
      CloudStorageClient.BucketTargetOptions.ifMetagenerationMatch();
  private static final CloudStorageClient.BucketTargetOptions BUCKET_TARGET_PREDEFINED_ACL =
      CloudStorageClient.BucketTargetOptions.withPredefinedAcl(CloudStorageClient.PredefinedAccessControlList.PRIVATE);
  private static final CloudStorageClient.BucketTargetOptions BUCKET_TARGET_USER_PROJECT =
      CloudStorageClient.BucketTargetOptions.withUserProject(USER_PROJECT);
  private static final Map<StorageServiceRpc.StorageOption, ?> BUCKET_TARGET_OPTIONS =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, BUCKET_INFO1.getMetageneration(),
          StorageServiceRpc.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageServiceRpc.StorageOption, ?> BUCKET_TARGET_OPTIONS_LOCK_RETENTION_POLICY =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH,
          BUCKET_INFO3.getMetageneration(),
          StorageServiceRpc.StorageOption.USER_PROJECT,
          USER_PROJECT);

  // Blob target options (create, update, compose)
  private static final CloudStorageClient.BlobUploadOptions BLOB_TARGET_GENERATION = BlobUploadOptions.ifGenerationMatch();
  private static final BlobUploadOptions BLOB_TARGET_METAGENERATION =
      CloudStorageClient.BlobUploadOptions.ifMetagenerationMatch();
  private static final BlobUploadOptions BLOB_TARGET_DISABLE_GZIP_CONTENT =
      BlobUploadOptions.disableGzip();
  private static final BlobUploadOptions BLOB_TARGET_NOT_EXIST = CloudStorageClient.BlobUploadOptions.ifDoesNotExist();
  private static final BlobUploadOptions BLOB_TARGET_PREDEFINED_ACL =
      BlobUploadOptions.withPredefinedAcl(CloudStorageClient.PredefinedAccessControlList.PRIVATE);
  private static final Map<StorageServiceRpc.StorageOption, ?> BLOB_TARGET_OPTIONS_CREATE =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          StorageServiceRpc.StorageOption.IF_GENERATION_MATCH, 0L,
          StorageServiceRpc.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageServiceRpc.StorageOption, ?> BLOB_TARGET_OPTIONS_CREATE_DISABLE_GZIP_CONTENT =
      ImmutableMap.of(StorageServiceRpc.StorageOption.IF_DISABLE_GZIP_CONTENT, true);
  private static final Map<StorageServiceRpc.StorageOption, ?> BLOB_TARGET_OPTIONS_UPDATE =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          StorageServiceRpc.StorageOption.PREDEFINED_ACL, BUCKET_TARGET_PREDEFINED_ACL.getValue());
  private static final Map<StorageServiceRpc.StorageOption, ?> BLOB_TARGET_OPTIONS_COMPOSE =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_GENERATION_MATCH, BLOB_INFO1.getGeneration(),
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration());

  // Blob write options (create, writer)
  private static final CloudStorageClient.BlobWriteOptions BLOB_WRITE_METAGENERATION =
      BlobWriteOptions.ifMetagenerationMatch();
  private static final BlobWriteOptions BLOB_WRITE_NOT_EXIST = BlobWriteOptions.ifNotExists();
  private static final CloudStorageClient.BlobWriteOptions BLOB_WRITE_PREDEFINED_ACL =
      CloudStorageClient.BlobWriteOptions.withPredefinedAcl(CloudStorageClient.PredefinedAccessControlList.PRIVATE);
  private static final BlobWriteOptions BLOB_WRITE_MD5_HASH = CloudStorageClient.BlobWriteOptions.ifMd5Match();
  private static final BlobWriteOptions BLOB_WRITE_CRC2C = CloudStorageClient.BlobWriteOptions.ifCrc32cMatch();

  // Bucket get/source options
  private static final CloudStorageClient.BucketSourceRequestOption BUCKET_SOURCE_METAGENERATION =
      CloudStorageClient.BucketSourceRequestOption.ifMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Map<StorageServiceRpc.StorageOption, ?> BUCKET_SOURCE_OPTIONS =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());
  private static final CloudStorageClient.BucketGetOptions BUCKET_GET_METAGENERATION =
      CloudStorageClient.BucketGetOptions.ifMetagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final CloudStorageClient.BucketGetOptions BUCKET_GET_FIELDS =
      CloudStorageClient.BucketGetOptions.selectFields(CloudStorageClient.BucketProperty.LOCATION, CloudStorageClient.BucketProperty.ACL);
  private static final CloudStorageClient.BucketGetOptions BUCKET_GET_EMPTY_FIELDS =
      CloudStorageClient.BucketGetOptions.selectFields();
  private static final Map<StorageServiceRpc.StorageOption, ?> BUCKET_GET_OPTIONS =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, BUCKET_SOURCE_METAGENERATION.getValue());

  // Blob get/source options
  private static final CloudStorageClient.BlobGetOptions BLOB_GET_METAGENERATION =
      CloudStorageClient.BlobGetOptions.ifMetagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final CloudStorageClient.BlobGetOptions BLOB_GET_GENERATION =
      CloudStorageClient.BlobGetOptions.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final CloudStorageClient.BlobGetOptions BLOB_GET_GENERATION_FROM_BLOB_ID =
      CloudStorageClient.BlobGetOptions.ifGenerationMatch();
  private static final CloudStorageClient.BlobGetOptions BLOB_GET_FIELDS =
      CloudStorageClient.BlobGetOptions.selectFields(CloudStorageClient.BlobMetadataField.CONTENT_TYPE, CloudStorageClient.BlobMetadataField.CRC32C);
  private static final CloudStorageClient.BlobGetOptions BLOB_GET_EMPTY_FIELDS = CloudStorageClient.BlobGetOptions.selectFields();
  private static final Map<StorageServiceRpc.StorageOption, ?> BLOB_GET_OPTIONS =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, BLOB_GET_METAGENERATION.getValue(),
          StorageServiceRpc.StorageOption.IF_GENERATION_MATCH, BLOB_GET_GENERATION.getValue());
  private static final CloudStorageClient.BlobReadOption BLOB_SOURCE_METAGENERATION =
      BlobReadOption.ifMetagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final CloudStorageClient.BlobReadOption BLOB_SOURCE_GENERATION =
      BlobReadOption.ifGenerationMatch(BLOB_INFO1.getGeneration());
  private static final BlobReadOption BLOB_SOURCE_GENERATION_FROM_BLOB_ID =
      BlobReadOption.ifGenerationMatch();
  private static final Map<StorageServiceRpc.StorageOption, ?> BLOB_SOURCE_OPTIONS =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_METAGENERATION_MATCH, BLOB_SOURCE_METAGENERATION.getValue(),
          StorageServiceRpc.StorageOption.IF_GENERATION_MATCH, BLOB_SOURCE_GENERATION.getValue());
  private static final Map<StorageServiceRpc.StorageOption, ?> BLOB_SOURCE_OPTIONS_COPY =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.IF_SOURCE_METAGENERATION_MATCH, BLOB_SOURCE_METAGENERATION.getValue(),
          StorageServiceRpc.StorageOption.IF_SOURCE_GENERATION_MATCH, BLOB_SOURCE_GENERATION.getValue());

  // Bucket list options
  private static final CloudStorageClient.BucketListOptions BUCKET_LIST_PAGE_SIZE =
      CloudStorageClient.BucketListOptions.withPageSize(42L);
  private static final CloudStorageClient.BucketListOptions BUCKET_LIST_PREFIX =
      CloudStorageClient.BucketListOptions.withPrefix("prefix");
  private static final CloudStorageClient.BucketListOptions BUCKET_LIST_FIELDS =
      CloudStorageClient.BucketListOptions.selectFields(CloudStorageClient.BucketProperty.LOCATION, CloudStorageClient.BucketProperty.ACL);
  private static final CloudStorageClient.BucketListOptions BUCKET_LIST_EMPTY_FIELDS =
      CloudStorageClient.BucketListOptions.selectFields();
  private static final Map<StorageServiceRpc.StorageOption, ?> BUCKET_LIST_OPTIONS =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.MAX_RESULTS, BUCKET_LIST_PAGE_SIZE.getValue(),
          StorageServiceRpc.StorageOption.PREFIX, BUCKET_LIST_PREFIX.getValue());

  // Blob list options
  private static final CloudStorageClient.BlobListOptions BLOB_LIST_PAGE_SIZE =
      CloudStorageClient.BlobListOptions.withPageSize(42L);
  private static final CloudStorageClient.BlobListOptions BLOB_LIST_PREFIX =
      CloudStorageClient.BlobListOptions.withPrefix("prefix");
  private static final CloudStorageClient.BlobListOptions BLOB_LIST_FIELDS =
      CloudStorageClient.BlobListOptions.withFields(CloudStorageClient.BlobMetadataField.CONTENT_TYPE, CloudStorageClient.BlobMetadataField.MD5HASH);
  private static final CloudStorageClient.BlobListOptions BLOB_LIST_VERSIONS =
      CloudStorageClient.BlobListOptions.withVersions(false);
  private static final CloudStorageClient.BlobListOptions BLOB_LIST_EMPTY_FIELDS =
      CloudStorageClient.BlobListOptions.withFields();
  private static final Map<StorageServiceRpc.StorageOption, ?> BLOB_LIST_OPTIONS =
      ImmutableMap.of(
          StorageServiceRpc.StorageOption.MAX_RESULTS, BLOB_LIST_PAGE_SIZE.getValue(),
          StorageServiceRpc.StorageOption.PREFIX, BLOB_LIST_PREFIX.getValue(),
          StorageServiceRpc.StorageOption.VERSIONS, BLOB_LIST_VERSIONS.getValue());

  // ACLs
  private static final AccessControlEntry ACL = AccessControlEntry.create(AccessControlEntry.UserPrincipal.allAuthenticatedUsers(), UserRole.OWNER);
  private static final AccessControlEntry OTHER_ACL = AccessControlEntry.create(new AccessControlEntry.ProjectInfo(AccessControlEntry.ProjectInfo.ProjectUserRole.OWNERS, "p"), UserRole.READER);

  // Customer supplied encryption key options
  private static final Map<StorageServiceRpc.StorageOption, ?> ENCRYPTION_KEY_OPTIONS =
      ImmutableMap.of(StorageServiceRpc.StorageOption.CUSTOMER_SUPPLIED_KEY, BASE64_KEY);

  // Customer managed encryption key options
  private static final Map<StorageServiceRpc.StorageOption, ?> KMS_KEY_NAME_OPTIONS =
      ImmutableMap.of(StorageServiceRpc.StorageOption.KMS_KEY_NAME, KMS_KEY_NAME);
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

  private static final ServiceAccountIdentity SERVICE_ACCOUNT = ServiceAccountIdentity.create("test@google.com");

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
  private StorageRpcProvider rpcFactoryMock;
  private StorageServiceRpc storageRpcMock;
  private CloudStorageClient storage;

  private StorageBlob expectedBlob1, expectedBlob2, expectedBlob3;
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
    rpcFactoryMock = EasyMock.createMock(StorageRpcProvider.class);
    storageRpcMock = EasyMock.createMock(StorageServiceRpc.class);
    EasyMock.expect(rpcFactoryMock.create(EasyMock.anyObject(StorageClientOptions.class)))
        .andReturn(storageRpcMock);
    EasyMock.replay(rpcFactoryMock);
    options =
        StorageClientOptions.createBuilder()
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
    expectedBlob1 = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1));
    expectedBlob2 = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO2));
    expectedBlob3 = new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO3));
    expectedBucket1 = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO1));
    expectedBucket2 = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO2));
    expectedBucket3 = new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(BUCKET_INFO3));
  }

  @Test
  public void testUpdateBucket() {
    BucketInfo updatedBucketInfo = BUCKET_INFO1.toBucketBuilder().setIndexPage("some-page").buildBucketInfo();
    EasyMock.expect(storageRpcMock.patch(updatedBucketInfo.toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(updatedBucketInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBucket bucket = storage.update(updatedBucketInfo);
    assertEquals(new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(updatedBucketInfo)), bucket);
  }

  @Test
  public void testUpdateBucketWithOptions() {
    BucketInfo updatedBucketInfo = BUCKET_INFO1.toBucketBuilder().setIndexPage("some-page").buildBucketInfo();
    EasyMock.expect(storageRpcMock.patch(updatedBucketInfo.toProto(), BUCKET_TARGET_OPTIONS))
        .andReturn(updatedBucketInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBucket bucket =
        storage.update(
            updatedBucketInfo, BUCKET_TARGET_METAGENERATION, BUCKET_TARGET_PREDEFINED_ACL);
    assertEquals(new StorageBucket(storage, new BucketInfo.BucketBuilderImpl(updatedBucketInfo)), bucket);
  }

  @Test
  public void testUpdateBlob() {
    BlobMetadata updatedBlobInfo = BLOB_INFO1.asBuilder().setContentType("some-content-type").buildObject();
    EasyMock.expect(storageRpcMock.patch(updatedBlobInfo.toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(updatedBlobInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBlob blob = storage.update(updatedBlobInfo);
    assertEquals(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(updatedBlobInfo)), blob);
  }

  @Test
  public void testUpdateBlobWithOptions() {
    BlobMetadata updatedBlobInfo = BLOB_INFO1.asBuilder().setContentType("some-content-type").buildObject();
    EasyMock.expect(storageRpcMock.patch(updatedBlobInfo.toProto(), BLOB_TARGET_OPTIONS_UPDATE))
        .andReturn(updatedBlobInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBlob blob =
        storage.update(updatedBlobInfo, BLOB_TARGET_METAGENERATION, BLOB_TARGET_PREDEFINED_ACL);
    assertEquals(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(updatedBlobInfo)), blob);
  }

  @Test
  public void testDeleteBucket() {
    EasyMock.expect(storageRpcMock.delete(BucketInfo.ofName(BUCKET_NAME1).toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1));
  }

  @Test
  public void testDeleteBucketWithOptions() {
    EasyMock.expect(
            storageRpcMock.delete(BucketInfo.ofName(BUCKET_NAME1).toProto(), BUCKET_SOURCE_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1, BUCKET_SOURCE_METAGENERATION));
  }

  @Test
  public void testDeleteBlob() {
    EasyMock.expect(
            storageRpcMock.delete(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1, BLOB_NAME1));
  }

  @Test
  public void testDeleteBlobWithOptions() {
    EasyMock.expect(
            storageRpcMock.delete(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toProto(), BLOB_SOURCE_OPTIONS))
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
    CloudStorageClient.ComposeBlobsRequest req =
        CloudStorageClient.ComposeBlobsRequest.builder()
            .addSources(BLOB_NAME2, BLOB_NAME3)
            .setTarget(BLOB_INFO1)
            .create();
    EasyMock.expect(
            storageRpcMock.compose(
                ImmutableList.of(BLOB_INFO2.toProto(), BLOB_INFO3.toProto()),
                BLOB_INFO1.toProto(),
                EMPTY_RPC_OPTIONS))
        .andReturn(BLOB_INFO1.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBlob blob = storage.compose(req);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testComposeWithOptions() {
    CloudStorageClient.ComposeBlobsRequest req =
        CloudStorageClient.ComposeBlobsRequest.builder()
            .addSources(BLOB_NAME2, BLOB_NAME3)
            .setTarget(BLOB_INFO1)
            .setTargetOptions(BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .create();
    EasyMock.expect(
            storageRpcMock.compose(
                ImmutableList.of(BLOB_INFO2.toProto(), BLOB_INFO3.toProto()),
                BLOB_INFO1.toProto(),
                BLOB_TARGET_OPTIONS_COMPOSE))
        .andReturn(BLOB_INFO1.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBlob blob = storage.compose(req);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testCopy() {
    CloudStorageClient.CopyOperationRequest request = CloudStorageClient.CopyOperationRequest.create(BLOB_INFO1.getBlobId(), BLOB_INFO2.getBlobId());
    StorageServiceRpc.ObjectRewriteRequest rpcRequest =
        new StorageServiceRpc.ObjectRewriteRequest(
            request.getSource().toProto(),
            EMPTY_RPC_OPTIONS,
            false,
            BLOB_INFO2.toProto(),
            EMPTY_RPC_OPTIONS,
            null);
    StorageServiceRpc.RewriteResult rpcResponse =
        new StorageServiceRpc.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    BlobCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithOptions() {
    CloudStorageClient.CopyOperationRequest request =
        CopyOperationRequest.builder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BLOB_SOURCE_GENERATION, BLOB_SOURCE_METAGENERATION)
            .setTarget(BLOB_INFO1, BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .create();
    StorageServiceRpc.ObjectRewriteRequest rpcRequest =
        new StorageServiceRpc.ObjectRewriteRequest(
            request.getSource().toProto(),
            BLOB_SOURCE_OPTIONS_COPY,
            true,
            request.getTarget().toProto(),
            BLOB_TARGET_OPTIONS_COMPOSE,
            null);
    StorageServiceRpc.RewriteResult rpcResponse =
        new StorageServiceRpc.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    BlobCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithEncryptionKey() {
    CopyOperationRequest request =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BlobReadOption.customerSuppliedKey(KEY))
            .setTarget(BLOB_INFO1, BlobUploadOptions.customerSuppliedKey(BASE64_KEY))
            .create();
    StorageServiceRpc.ObjectRewriteRequest rpcRequest =
        new StorageServiceRpc.ObjectRewriteRequest(
            request.getSource().toProto(),
            ENCRYPTION_KEY_OPTIONS,
            true,
            request.getTarget().toProto(),
            ENCRYPTION_KEY_OPTIONS,
            null);
    StorageServiceRpc.RewriteResult rpcResponse =
        new StorageServiceRpc.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse).times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    BlobCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
    request =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(CloudStorageClient.BlobReadOption.customerSuppliedKey(BASE64_KEY))
            .setTarget(BLOB_INFO1, BlobUploadOptions.customerSuppliedKey(KEY))
            .create();
    writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyFromEncryptionKeyToKmsKeyName() {
    CloudStorageClient.CopyOperationRequest request =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BlobReadOption.customerSuppliedKey(KEY))
            .setTarget(BLOB_INFO1, BlobUploadOptions.withKmsKeyName(KMS_KEY_NAME))
            .create();
    StorageServiceRpc.ObjectRewriteRequest rpcRequest =
        new StorageServiceRpc.ObjectRewriteRequest(
            request.getSource().toProto(),
            ENCRYPTION_KEY_OPTIONS,
            true,
            request.getTarget().toProto(),
            KMS_KEY_NAME_OPTIONS,
            null);
    StorageServiceRpc.RewriteResult rpcResponse =
        new StorageServiceRpc.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse).times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    BlobCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
    request =
        CopyOperationRequest.builder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BlobReadOption.customerSuppliedKey(BASE64_KEY))
            .setTarget(BLOB_INFO1, CloudStorageClient.BlobUploadOptions.withKmsKeyName(KMS_KEY_NAME))
            .create();
    writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithOptionsFromBlobId() {
    CopyOperationRequest request =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(BLOB_INFO1.getBlobId())
            .setSourceOptions(BLOB_SOURCE_GENERATION_FROM_BLOB_ID, BLOB_SOURCE_METAGENERATION)
            .setTarget(BLOB_INFO1, BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .create();
    StorageServiceRpc.ObjectRewriteRequest rpcRequest =
        new StorageServiceRpc.ObjectRewriteRequest(
            request.getSource().toProto(),
            BLOB_SOURCE_OPTIONS_COPY,
            true,
            request.getTarget().toProto(),
            BLOB_TARGET_OPTIONS_COMPOSE,
            null);
    StorageServiceRpc.RewriteResult rpcResponse =
        new StorageServiceRpc.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    BlobCopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyMultipleRequests() {
    CloudStorageClient.CopyOperationRequest request = CloudStorageClient.CopyOperationRequest.create(BLOB_INFO1.getBlobId(), BLOB_INFO2.getBlobId());
    StorageServiceRpc.ObjectRewriteRequest rpcRequest =
        new StorageServiceRpc.ObjectRewriteRequest(
            request.getSource().toProto(),
            EMPTY_RPC_OPTIONS,
            false,
            BLOB_INFO2.toProto(),
            EMPTY_RPC_OPTIONS,
            null);
    StorageServiceRpc.RewriteResult rpcResponse1 =
        new StorageServiceRpc.RewriteResult(rpcRequest, null, 42L, false, "token", 21L);
    StorageServiceRpc.RewriteResult rpcResponse2 =
        new StorageServiceRpc.RewriteResult(rpcRequest, BLOB_INFO1.toProto(), 42L, true, "token", 42L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse1);
    EasyMock.expect(storageRpcMock.continueRewrite(rpcResponse1)).andReturn(rpcResponse2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    BlobCopyWriter writer = storage.copy(request);
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
            storageRpcMock.load(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toProto(), EMPTY_RPC_OPTIONS))
        .andReturn(BLOB_CONTENT);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes = storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1);
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesWithOptions() {
    EasyMock.expect(
            storageRpcMock.load(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toProto(), BLOB_SOURCE_OPTIONS))
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
            storageRpcMock.load(BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1).toProto(), ENCRYPTION_KEY_OPTIONS))
        .andReturn(BLOB_CONTENT)
        .times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1, BlobReadOption.customerSuppliedKey(KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
    readBytes =
        storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1, BlobReadOption.customerSuppliedKey(BASE64_KEY));
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
        storage.readAllBytes(BLOB_INFO1.getBlobId(), BlobReadOption.customerSuppliedKey(KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
    readBytes =
        storage.readAllBytes(BLOB_INFO1.getBlobId(), BlobReadOption.customerSuppliedKey(BASE64_KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testBatch() {
    RpcRequestBatch batchMock = EasyMock.mock(RpcRequestBatch.class);
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
            CloudStorageClient.UrlSigningOption.setHostName("https://example.com"));
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
            CloudStorageClient.UrlSigningOption.setHostName("https://example.com"));
    String escapedBlobName = rfc3986UriEncode(blobName, false);
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
            CloudStorageClient.UrlSigningOption.withHttpMethod(HttpRequestMethod.POST),
            CloudStorageClient.UrlSigningOption.setContentType(),
            CloudStorageClient.UrlSigningOption.setMd5());
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
            CloudStorageClient.UrlSigningOption.withHttpMethod(HttpRequestMethod.POST),
            CloudStorageClient.UrlSigningOption.setContentType(),
            CloudStorageClient.UrlSigningOption.setMd5(),
            CloudStorageClient.UrlSigningOption.setHostName("https://example.com"));
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
              CloudStorageClient.UrlSigningOption.setHostName("https://example.com"));
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
            CloudStorageClient.UrlSigningOption.withHttpMethod(HttpRequestMethod.PUT),
            CloudStorageClient.UrlSigningOption.setContentType(),
            CloudStorageClient.UrlSigningOption.withExtraHeaders(extHeaders));
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
            CloudStorageClient.UrlSigningOption.withHttpMethod(HttpRequestMethod.PUT),
            CloudStorageClient.UrlSigningOption.setContentType(),
            CloudStorageClient.UrlSigningOption.withExtraHeaders(extHeaders),
            CloudStorageClient.UrlSigningOption.setHostName("https://example.com"));
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
    String escapedBlobName = rfc3986UriEncode(blobName, false);
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
            CloudStorageClient.UrlSigningOption.setHostName("https://example.com"));
    String escapedBlobName = rfc3986UriEncode(blobName, false);
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
            CloudStorageClient.UrlSigningOption.usePathStyle(),
            CloudStorageClient.UrlSigningOption.useV2Signature(),
            CloudStorageClient.UrlSigningOption.setQueryParams(
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
            CloudStorageClient.UrlSigningOption.usePathStyle(),
            CloudStorageClient.UrlSigningOption.useV4Signature(),
            CloudStorageClient.UrlSigningOption.setQueryParams(
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
    BlobIdentifier blobId1 = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1);
    BlobIdentifier blobId2 = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME2);
    RpcRequestBatch batchMock = EasyMock.createMock(RpcRequestBatch.class);
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback2 = Capture.newInstance();
    batchMock.addGetRequest(
        EasyMock.eq(blobId1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    batchMock.addGetRequest(
        EasyMock.eq(blobId2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submitBatch();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<StorageBlob> resultBlobs = storage.get(blobId1, blobId2);
    callback1.getValue().handleSuccess(BLOB_INFO1.toProto());
    callback2.getValue().handleFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testGetAllArrayIterable() {
    BlobIdentifier blobId1 = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1);
    BlobIdentifier blobId2 = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME2);
    RpcRequestBatch batchMock = EasyMock.createMock(RpcRequestBatch.class);
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback2 = Capture.newInstance();
    batchMock.addGetRequest(
        EasyMock.eq(blobId1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    batchMock.addGetRequest(
        EasyMock.eq(blobId2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submitBatch();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<StorageBlob> resultBlobs = storage.get(ImmutableList.of(blobId1, blobId2));
    callback1.getValue().handleSuccess(BLOB_INFO1.toProto());
    callback2.getValue().handleFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testDeleteAllArray() {
    BlobIdentifier blobId1 = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1);
    BlobIdentifier blobId2 = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME2);
    RpcRequestBatch batchMock = EasyMock.createMock(RpcRequestBatch.class);
    Capture<RpcRequestBatch.CompletionHandler<Void>> callback1 = Capture.newInstance();
    Capture<RpcRequestBatch.CompletionHandler<Void>> callback2 = Capture.newInstance();
    batchMock.addDeleteRequest(
        EasyMock.eq(blobId1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    batchMock.addDeleteRequest(
        EasyMock.eq(blobId2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submitBatch();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Boolean> result = storage.delete(blobId1, blobId2);
    callback1.getValue().handleSuccess(null);
    callback2.getValue().handleFailure(new GoogleJsonError());
    assertEquals(2, result.size());
    assertTrue(result.get(0));
    assertFalse(result.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testDeleteAllIterable() {
    BlobIdentifier blobId1 = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1);
    BlobIdentifier blobId2 = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME2);
    RpcRequestBatch batchMock = EasyMock.createMock(RpcRequestBatch.class);
    Capture<RpcRequestBatch.CompletionHandler<Void>> callback1 = Capture.newInstance();
    Capture<RpcRequestBatch.CompletionHandler<Void>> callback2 = Capture.newInstance();
    batchMock.addDeleteRequest(
        EasyMock.eq(blobId1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    batchMock.addDeleteRequest(
        EasyMock.eq(blobId2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submitBatch();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Boolean> result = storage.delete(blobId1, blobId2);
    callback1.getValue().handleSuccess(null);
    callback2.getValue().handleFailure(new GoogleJsonError());
    assertEquals(2, result.size());
    assertTrue(result.get(0));
    assertFalse(result.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testUpdateAllArray() {
    RpcRequestBatch batchMock = EasyMock.createMock(RpcRequestBatch.class);
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback2 = Capture.newInstance();
    batchMock.addPatchRequest(
        EasyMock.eq(BLOB_INFO1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    batchMock.addPatchRequest(
        EasyMock.eq(BLOB_INFO2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submitBatch();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<StorageBlob> resultBlobs = storage.update(BLOB_INFO1, BLOB_INFO2);
    callback1.getValue().handleSuccess(BLOB_INFO1.toProto());
    callback2.getValue().handleFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testUpdateAllIterable() {
    RpcRequestBatch batchMock = EasyMock.createMock(RpcRequestBatch.class);
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcRequestBatch.CompletionHandler<StorageObject>> callback2 = Capture.newInstance();
    batchMock.addPatchRequest(
        EasyMock.eq(BLOB_INFO1.toProto()),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    batchMock.addPatchRequest(
        EasyMock.eq(BLOB_INFO2.toProto()),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageServiceRpc.StorageOption, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submitBatch();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<StorageBlob> resultBlobs = storage.update(ImmutableList.of(BLOB_INFO1, BLOB_INFO2));
    callback1.getValue().handleSuccess(BLOB_INFO1.toProto());
    callback2.getValue().handleFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new StorageBlob(storage, new BlobMetadata.BlobInfoBuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testGetBucketAcl() {
    EasyMock.expect(
            storageRpcMock.getAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageServiceRpc.StorageOption, Object>()))
        .andReturn(ACL.toBucketProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.getAcl(BUCKET_NAME1, UserPrincipal.allAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetBucketAclNull() {
    EasyMock.expect(
            storageRpcMock.getAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageServiceRpc.StorageOption, Object>()))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getAcl(BUCKET_NAME1, UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteBucketAcl() {
    EasyMock.expect(
            storageRpcMock.deleteAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageServiceRpc.StorageOption, Object>()))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteAcl(BUCKET_NAME1, UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateBucketAcl() {
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
    EasyMock.expect(
            storageRpcMock.createAcl(
                ACL.toBucketProto().setBucket(BUCKET_NAME1), new HashMap<StorageServiceRpc.StorageOption, Object>()))
        .andReturn(returnedAcl.toBucketProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.createAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testUpdateBucketAcl() {
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
    EasyMock.expect(
            storageRpcMock.patchAcl(
                ACL.toBucketProto().setBucket(BUCKET_NAME1), new HashMap<StorageServiceRpc.StorageOption, Object>()))
        .andReturn(returnedAcl.toBucketProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.updateAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testListBucketAcl() {
    EasyMock.expect(storageRpcMock.listAcls(BUCKET_NAME1, new HashMap<StorageServiceRpc.StorageOption, Object>()))
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
    AccessControlEntry acl = storage.getDefaultAcl(BUCKET_NAME1, UserPrincipal.allAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetDefaultBucketAclNull() {
    EasyMock.expect(storageRpcMock.getDefaultAcl(BUCKET_NAME1, "allAuthenticatedUsers"))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getDefaultAcl(BUCKET_NAME1, UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteDefaultBucketAcl() {
    EasyMock.expect(storageRpcMock.deleteDefaultAcl(BUCKET_NAME1, "allAuthenticatedUsers"))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteDefaultAcl(BUCKET_NAME1, UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateDefaultBucketAcl() {
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
    EasyMock.expect(storageRpcMock.createDefaultAcl(ACL.toObjectProto().setBucket(BUCKET_NAME1)))
        .andReturn(returnedAcl.toObjectProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.createDefaultAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testUpdateDefaultBucketAcl() {
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
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
    BlobIdentifier blobId = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(storageRpcMock.getAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(ACL.toObjectProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    AccessControlEntry acl = storage.getAcl(blobId, UserPrincipal.allAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetBlobAclNull() {
    BlobIdentifier blobId = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(storageRpcMock.getAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getAcl(blobId, AccessControlEntry.UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testDeleteBlobAcl() {
    BlobIdentifier blobId = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(
            storageRpcMock.deleteAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteAcl(blobId, UserPrincipal.allAuthenticatedUsers()));
  }

  @Test
  public void testCreateBlobAcl() {
    BlobIdentifier blobId = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1, 42L);
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
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
    BlobIdentifier blobId = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1, 42L);
    AccessControlEntry returnedAcl = ACL.toEntityBuilder().setEtag("ETAG").setId("ID").buildEntry();
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
    BlobIdentifier blobId = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1, 42L);
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
        .andReturn(SERVICE_ACCOUNT.toServiceAccountModel());
    EasyMock.replay(storageRpcMock);
    initializeService();
    ServiceAccountIdentity serviceAccount = storage.getServiceAccount("projectId");
    assertEquals(SERVICE_ACCOUNT, serviceAccount);
  }

  @Test
  public void testRetryableException() {
    BlobIdentifier blob = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1);
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
    StorageBlob readBlob = storage.get(blob);
    assertEquals(expectedBlob1, readBlob);
  }

  @Test
  public void testNonRetryableException() {
    BlobIdentifier blob = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1);
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
    BlobIdentifier blob = BlobIdentifier.create(BUCKET_NAME1, BLOB_NAME1);
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
  public void testWriterWithSignedURL() throws MalformedURLException {
    EasyMock.expect(storageRpcMock.open(SIGNED_URL)).andReturn("upload-id");
    EasyMock.replay(storageRpcMock);
    initializeService();
    WriteChannel writer = new BlobUploadChannel(options, new URL(SIGNED_URL));
    assertNotNull(writer);
    assertTrue(writer.isOpen());
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

    FormPostPolicyV4.PostFieldsMapV4 fields =
        FormPostPolicyV4.PostFieldsMapV4.builder().setAcl("public-read").create();
    FormPostPolicyV4.PostConditionsVersion4 conditions =
        FormPostPolicyV4.PostConditionsVersion4.builder()
            .addContentType(FormPostPolicyV4.ConditionV4Operator.MATCHES, "image/jpeg")
            .create();

    // test fields and conditions
    FormPostPolicyV4 policy =
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
    BucketInfo bucketInfo =
        BucketInfo.newBucketBuilder("b")
            .setLocation("us")
            .setLifecycleRules(
                ImmutableList.of(
                    new BucketInfo.LifecycleRuleDefinition(
                        BucketInfo.LifecycleRuleDefinition.AbstractLifecycleAction.newSetStorageClassLifecycleAction(
                            StorageTier.COLDLINE),
                        BucketInfo.LifecycleRuleDefinition.LifecycleRuleCondition.newLifecycleRuleBuilder()
                            .setAge(1)
                            .setNumberOfNewerVersions(3)
                            .setIsLive(false)
                            .setCreatedBefore(new DateTime(System.currentTimeMillis()))
                            .setMatchesStorageClass(ImmutableList.of(StorageTier.COLDLINE))
                            .setDaysSinceNoncurrentTime(30)
                            .setNoncurrentTimeBefore(new DateTime(System.currentTimeMillis()))
                            .setCustomTimeBefore(new DateTime(System.currentTimeMillis()))
                            .setDaysSinceCustomTime(30)
                            .buildLifecycleCondition())))
            .buildBucketInfo();
    EasyMock.expect(
            storageRpcMock.create(bucketInfo.toProto(), new HashMap<StorageServiceRpc.StorageOption, Object>()))
        .andReturn(bucketInfo.toProto());
    EasyMock.replay(storageRpcMock);
    initializeService();
    StorageBucket bucket = storage.create(bucketInfo);
    BucketInfo.LifecycleRuleDefinition lifecycleRule = bucket.getLifecycleRules().get(0);
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
}
