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

package com.google.cloud.storage.it;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.apache.ApacheHttpTransport;
import com.google.api.client.util.DateTime;
import com.google.api.gax.paging.Page;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.Condition;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.ReadChannel;
import com.google.cloud.RestorableState;
import com.google.cloud.TransportOptions;
import com.google.cloud.WriteChannel;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.kms.v1.CreateCryptoKeyRequest;
import com.google.cloud.kms.v1.CreateKeyRingRequest;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.cloud.kms.v1.GetCryptoKeyRequest;
import com.google.cloud.kms.v1.GetKeyRingRequest;
import com.google.cloud.kms.v1.KeyManagementServiceGrpc;
import com.google.cloud.kms.v1.KeyManagementServiceGrpc.KeyManagementServiceBlockingStub;
import com.google.cloud.kms.v1.KeyRingName;
import com.google.cloud.kms.v1.LocationName;
import com.google.cloud.storage.AccessControlEntry;
import com.google.cloud.storage.AccessControlEntry.UserRole;
import com.google.cloud.storage.AccessControlEntry.UserIdentity;
import com.google.cloud.storage.BlobIdentifier;
import com.google.cloud.storage.BlobMetadata;
import com.google.cloud.storage.BlobRewriteWriter;
import com.google.cloud.storage.CloudStorageClient;
import com.google.cloud.storage.HmacSecretKey;
import com.google.cloud.storage.ServiceAccountInfo;
import com.google.cloud.storage.StorageBucket;
import com.google.cloud.storage.StorageClassType;
import com.google.cloud.storage.StorageClientOptions;
import com.google.cloud.storage.StorageObject;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.BucketInfo.LifecycleRuleDefinition;
import com.google.cloud.storage.BucketInfo.LifecycleRuleDefinition.LifecycleRuleCondition;
import com.google.cloud.storage.Cors;
import com.google.cloud.storage.HttpRequestMethod;
import com.google.cloud.storage.S3PostPolicyV4;
import com.google.cloud.storage.CloudStorageClient.BlobMetadataField;
import com.google.cloud.storage.CloudStorageClient.BlobWriteOptions;
import com.google.cloud.storage.CloudStorageClient.BucketMetadataField;
import com.google.cloud.storage.StorageRpcBatch;
import com.google.cloud.storage.StorageBatchResult;
import com.google.cloud.storage.StorageServiceException;
import com.google.cloud.storage.StorageRoles;
import com.google.cloud.storage.testing.RemoteStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.iam.v1.Binding;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.SetIamPolicyRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.MetadataUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import javax.crypto.spec.SecretKeySpec;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ITStorageTest {

  private static RemoteStorageHelper remoteStorageHelper;
  private static CloudStorageClient storage;
  private static String kmsKeyOneResourcePath;
  private static String kmsKeyTwoResourcePath;
  private static Metadata requestParamsHeader = new Metadata();
  private static Metadata.Key<String> requestParamsKey =
      Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER);
  private static final Logger log = Logger.getLogger(ITStorageTest.class.getName());
  private static final String BUCKET = RemoteStorageHelper.generateBucketName();
  private static final String BUCKET_REQUESTER_PAYS = RemoteStorageHelper.generateBucketName();
  private static final String CONTENT_TYPE = "text/plain";
  private static final byte[] BLOB_BYTE_CONTENT = {0xD, 0xE, 0xA, 0xD};
  private static final String BLOB_STRING_CONTENT = "Hello Google Cloud Storage!";
  private static final int MAX_BATCH_SIZE = 100;
  private static final String BASE64_KEY = "JVzfVl8NLD9FjedFuStegjRfES5ll5zc59CIXw572OA=";
  private static final String OTHER_BASE64_KEY = "IcOIQGlliNr5pr3vJb63l+XMqc7NjXqjfw/deBoNxPA=";
  private static final Key KEY =
      new SecretKeySpec(BaseEncoding.base64().decode(BASE64_KEY), "AES256");
  private static final byte[] COMPRESSED_CONTENT =
      BaseEncoding.base64()
          .decode("H4sIAAAAAAAAAPNIzcnJV3DPz0/PSVVwzskvTVEILskvSkxPVQQA/LySchsAAAA=");
  private static final Map<String, String> BUCKET_LABELS = ImmutableMap.of("label1", "value1");
  private static final Map<String, String> REMOVE_BUCKET_LABELS;

  static {
    REMOVE_BUCKET_LABELS = new HashMap<>();
    REMOVE_BUCKET_LABELS.put("label1", null);
  }

  private static final Long RETENTION_PERIOD = 5L;
  private static final Long RETENTION_PERIOD_IN_MILLISECONDS = RETENTION_PERIOD * 1000;
  private static final String SERVICE_ACCOUNT_EMAIL_SUFFIX =
      "@gs-project-accounts.iam.gserviceaccount.com";
  private static final String KMS_KEY_RING_NAME = "gcs_test_kms_key_ring";
  private static final String KMS_KEY_RING_LOCATION = "us";
  private static final String KMS_KEY_ONE_NAME = "gcs_kms_key_one";
  private static final String KMS_KEY_TWO_NAME = "gcs_kms_key_two";
  private static final boolean IS_VPC_TEST =
      System.getenv("GOOGLE_CLOUD_TESTS_IN_VPCSC") != null
          && System.getenv("GOOGLE_CLOUD_TESTS_IN_VPCSC").equalsIgnoreCase("true");
  private static final List<String> LOCATION_TYPES =
      ImmutableList.of("multi-region", "region", "dual-region");
  private static final BucketInfo.LifecycleRuleDefinition LIFECYCLE_RULE_1 =
      new BucketInfo.LifecycleRuleDefinition(
          LifecycleRuleDefinition.AbstractLifecycleAction.newUpdateStorageClassAction(StorageClassType.COLDLINE),
          LifecycleRuleCondition.newLifecycleConditionBuilder()
              .setAge(1)
              .setNumberOfNewerVersions(3)
              .setIsLive(false)
              .setMatchesStorageClass(ImmutableList.of(StorageClassType.COLDLINE))
              .buildLifecycleCondition());
  private static final LifecycleRuleDefinition LIFECYCLE_RULE_2 =
      new LifecycleRuleDefinition(
          LifecycleRuleDefinition.AbstractLifecycleAction.newRemoveAction(), LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(1).buildLifecycleCondition());
  private static final ImmutableList<LifecycleRuleDefinition> LIFECYCLE_RULES =
      ImmutableList.of(LIFECYCLE_RULE_1, LIFECYCLE_RULE_2);

  @BeforeClass
  public static void beforeClass() throws IOException {
    remoteStorageHelper = RemoteStorageHelper.create();
    storage = remoteStorageHelper.getOptions().getService();

    storage.create(
        BucketInfo.newBucketBuilder(BUCKET)
            .setLocation("us")
            .setLifecycleRules(
                ImmutableList.of(
                    new LifecycleRuleDefinition(
                        LifecycleRuleDefinition.AbstractLifecycleAction.newRemoveAction(),
                        LifecycleRuleDefinition.LifecycleRuleCondition.newLifecycleConditionBuilder().setAge(1).buildLifecycleCondition())))
            .buildBucketInfo());

    storage.create(BucketInfo.newBucketBuilder(BUCKET_REQUESTER_PAYS).buildBucketInfo());

    // Prepare KMS KeyRing for CMEK tests
    prepareKmsKeys();
  }

  private static void unsetRequesterPays() {
    StorageBucket remoteBucket =
        storage.get(
            BUCKET_REQUESTER_PAYS,
            CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.ID, BucketMetadataField.BILLING),
            CloudStorageClient.BucketGetOptions.userProjectId(storage.getOptions().getProjectId()));
    // Disable requester pays in case a test fails to clean up.
    if (remoteBucket.isRequesterPays() != null && remoteBucket.isRequesterPays() == true) {
      remoteBucket
          .toBucketBuilder()
          .setRequesterPays(false)
          .buildBucketInfo()
          .modify(CloudStorageClient.BucketTargetOptions.userProjectId(storage.getOptions().getProjectId()));
    }
  }

  @AfterClass
  public static void afterClass() throws ExecutionException, InterruptedException {
    if (storage != null) {
      // In beforeClass, we make buckets auto-delete blobs older than a day old.
      // Here, delete all buckets older than 2 days. They should already be empty and easy.
      long cleanTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
      long cleanTimeout = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1);
      RemoteStorageHelper.cleanBuckets(storage, cleanTime, cleanTimeout);

      boolean wasDeleted = RemoteStorageHelper.forceDelete(storage, BUCKET, 1, TimeUnit.MINUTES);
      if (!wasDeleted && log.isLoggable(Level.WARNING)) {
        log.log(Level.WARNING, "Deletion of bucket {0} timed out, bucket is not empty", BUCKET);
      }
      unsetRequesterPays();
      RemoteStorageHelper.forceDelete(storage, BUCKET_REQUESTER_PAYS, 5, TimeUnit.SECONDS);
    }
  }

  private static class CustomHttpTransportFactory implements HttpTransportFactory {
    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public HttpTransport create() {
      PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
      manager.setMaxTotal(1);
      return new ApacheHttpTransport(HttpClients.createMinimal(manager));
    }
  }

  private static void prepareKmsKeys() throws IOException {
    // https://cloud.google.com/storage/docs/encryption/using-customer-managed-keys
    String projectId = remoteStorageHelper.getOptions().getProjectId();
    GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
    ManagedChannel kmsChannel =
        ManagedChannelBuilder.forTarget("cloudkms.googleapis.com:443").build();
    KeyManagementServiceBlockingStub kmsStub =
        KeyManagementServiceGrpc.newBlockingStub(kmsChannel)
            .withCallCredentials(MoreCallCredentials.from(credentials));
    IAMPolicyGrpc.IAMPolicyBlockingStub iamStub =
        IAMPolicyGrpc.newBlockingStub(kmsChannel)
            .withCallCredentials(MoreCallCredentials.from(credentials));
    ensureKmsKeyRingExistsForTests(kmsStub, projectId, KMS_KEY_RING_LOCATION, KMS_KEY_RING_NAME);
    ensureKmsKeyRingIamPermissionsForTests(
        iamStub, projectId, KMS_KEY_RING_LOCATION, KMS_KEY_RING_NAME);
    kmsKeyOneResourcePath =
        ensureKmsKeyExistsForTests(
            kmsStub, projectId, KMS_KEY_RING_LOCATION, KMS_KEY_RING_NAME, KMS_KEY_ONE_NAME);
    kmsKeyTwoResourcePath =
        ensureKmsKeyExistsForTests(
            kmsStub, projectId, KMS_KEY_RING_LOCATION, KMS_KEY_RING_NAME, KMS_KEY_TWO_NAME);
  }

  private static String ensureKmsKeyRingExistsForTests(
      KeyManagementServiceBlockingStub kmsStub,
      String projectId,
      String location,
      String keyRingName)
      throws StatusRuntimeException {
    String kmsKeyRingResourcePath = KeyRingName.of(projectId, location, keyRingName).toString();
    try {
      // Attempt to Get KeyRing
      GetKeyRingRequest getKeyRingRequest =
          GetKeyRingRequest.newBuilder().setName(kmsKeyRingResourcePath).build();
      requestParamsHeader.put(requestParamsKey, "name=" + kmsKeyRingResourcePath);
      KeyManagementServiceBlockingStub stubForGetKeyRing =
          MetadataUtils.attachHeaders(kmsStub, requestParamsHeader);
      stubForGetKeyRing.getKeyRing(getKeyRingRequest);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
        // Create KmsKeyRing
        String keyRingParent = LocationName.of(projectId, location).toString();
        CreateKeyRingRequest createKeyRingRequest =
            CreateKeyRingRequest.newBuilder()
                .setParent(keyRingParent)
                .setKeyRingId(keyRingName)
                .build();
        requestParamsHeader.put(requestParamsKey, "parent=" + keyRingParent);
        KeyManagementServiceBlockingStub stubForCreateKeyRing =
            MetadataUtils.attachHeaders(kmsStub, requestParamsHeader);
        stubForCreateKeyRing.createKeyRing(createKeyRingRequest);
      } else {
        throw ex;
      }
    }

    return kmsKeyRingResourcePath;
  }

  private static void ensureKmsKeyRingIamPermissionsForTests(
      IAMPolicyGrpc.IAMPolicyBlockingStub iamStub,
      String projectId,
      String location,
      String keyRingName)
      throws StatusRuntimeException {
    ServiceAccountInfo serviceAccount = storage.getServiceAccount(projectId);
    String kmsKeyRingResourcePath = KeyRingName.of(projectId, location, keyRingName).toString();
    Binding binding =
        Binding.newBuilder()
            .setRole("roles/cloudkms.cryptoKeyEncrypterDecrypter")
            .addMembers("serviceAccount:" + serviceAccount.getEmail())
            .build();
    com.google.iam.v1.Policy policy =
        com.google.iam.v1.Policy.newBuilder().addBindings(binding).build();
    SetIamPolicyRequest setIamPolicyRequest =
        SetIamPolicyRequest.newBuilder()
            .setResource(kmsKeyRingResourcePath)
            .setPolicy(policy)
            .build();
    requestParamsHeader.put(requestParamsKey, "parent=" + kmsKeyRingResourcePath);
    iamStub = MetadataUtils.attachHeaders(iamStub, requestParamsHeader);
    try {
      iamStub.setIamPolicy(setIamPolicyRequest);
    } catch (StatusRuntimeException e) {
      if (log.isLoggable(Level.WARNING)) {
        log.log(Level.WARNING, "Unable to set IAM policy: {0}", e.getMessage());
      }
    }
  }

  private static String ensureKmsKeyExistsForTests(
      KeyManagementServiceBlockingStub kmsStub,
      String projectId,
      String location,
      String keyRingName,
      String keyName)
      throws StatusRuntimeException {
    String kmsKeyResourcePath =
        CryptoKeyName.of(projectId, location, keyRingName, keyName).toString();
    try {
      // Attempt to Get CryptoKey
      requestParamsHeader.put(requestParamsKey, "name=" + kmsKeyResourcePath);
      GetCryptoKeyRequest getCryptoKeyRequest =
          GetCryptoKeyRequest.newBuilder().setName(kmsKeyResourcePath).build();
      KeyManagementServiceGrpc.KeyManagementServiceBlockingStub stubForGetCryptoKey =
          MetadataUtils.attachHeaders(kmsStub, requestParamsHeader);
      stubForGetCryptoKey.getCryptoKey(getCryptoKeyRequest);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
        String kmsKeyRingResourcePath = KeyRingName.of(projectId, location, keyRingName).toString();
        CryptoKey cryptoKey =
            CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT).build();
        CreateCryptoKeyRequest createCryptoKeyRequest =
            CreateCryptoKeyRequest.newBuilder()
                .setCryptoKeyId(keyName)
                .setParent(kmsKeyRingResourcePath)
                .setCryptoKey(cryptoKey)
                .build();

        requestParamsHeader.put(requestParamsKey, "parent=" + kmsKeyRingResourcePath);
        KeyManagementServiceGrpc.KeyManagementServiceBlockingStub stubForCreateCryptoKey =
            MetadataUtils.attachHeaders(kmsStub, requestParamsHeader);
        stubForCreateCryptoKey.createCryptoKey(createCryptoKeyRequest);
      } else {
        throw ex;
      }
    }
    return kmsKeyResourcePath;
  }

  @Test(timeout = 5000)
  public void testListBuckets() throws InterruptedException {
    Iterator<StorageBucket> bucketIterator =
        storage
            .list(CloudStorageClient.BucketListOptions.withPrefix(BUCKET), CloudStorageClient.BucketListOptions.selectFields())
            .iterateAll()
            .iterator();
    while (!bucketIterator.hasNext()) {
      Thread.sleep(500);
      bucketIterator =
          storage
              .list(CloudStorageClient.BucketListOptions.withPrefix(BUCKET), CloudStorageClient.BucketListOptions.selectFields())
              .iterateAll()
              .iterator();
    }
    while (bucketIterator.hasNext()) {
      StorageBucket remoteBucket = bucketIterator.next();
      assertTrue(remoteBucket.getName().startsWith(BUCKET));
      assertNull(remoteBucket.getCreateTime());
      assertNull(remoteBucket.getSelfLink());
    }
  }

  @Test
  public void testGetBucketSelectedFields() {
    StorageBucket remoteBucket = storage.get(BUCKET, CloudStorageClient.BucketGetOptions.selectedFields(CloudStorageClient.BucketMetadataField.ID));
    assertEquals(BUCKET, remoteBucket.getName());
    assertNull(remoteBucket.getCreateTime());
    assertNotNull(remoteBucket.getGeneratedId());
  }

  @Test
  public void testGetBucketAllSelectedFields() {
    StorageBucket remoteBucket = storage.get(BUCKET, CloudStorageClient.BucketGetOptions.selectedFields(CloudStorageClient.BucketMetadataField.values()));
    assertEquals(BUCKET, remoteBucket.getName());
    assertNotNull(remoteBucket.getCreateTime());
    assertNotNull(remoteBucket.getSelfLink());
  }

  @Test
  public void testGetBucketEmptyFields() {
    StorageBucket remoteBucket = storage.get(BUCKET, CloudStorageClient.BucketGetOptions.selectedFields());
    assertEquals(BUCKET, remoteBucket.getName());
    assertNull(remoteBucket.getCreateTime());
    assertNull(remoteBucket.getSelfLink());
  }

  @Test
  public void testGetBucketLifecycleRules() {
    String lifecycleTestBucketName = RemoteStorageHelper.generateBucketName();
    storage.create(
        BucketInfo.newBucketBuilder(lifecycleTestBucketName)
            .setLocation("us")
            .setLifecycleRules(
                ImmutableList.of(
                    new LifecycleRuleDefinition(
                        LifecycleRuleDefinition.AbstractLifecycleAction.newUpdateStorageClassAction(StorageClassType.COLDLINE),
                        LifecycleRuleDefinition.LifecycleRuleCondition.newLifecycleConditionBuilder()
                            .setAge(1)
                            .setNumberOfNewerVersions(3)
                            .setIsLive(false)
                            .setCreatedBefore(new DateTime(System.currentTimeMillis()))
                            .setMatchesStorageClass(ImmutableList.of(StorageClassType.COLDLINE))
                            .setDaysSinceNoncurrentTime(30)
                            .setNoncurrentTimeBefore(new DateTime(System.currentTimeMillis()))
                            .setCustomTimeBefore(new DateTime(System.currentTimeMillis()))
                            .setDaysSinceCustomTime(30)
                            .buildLifecycleCondition())))
            .buildBucketInfo());
    StorageBucket remoteBucket =
        storage.get(lifecycleTestBucketName, CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.LIFECYCLE));
    LifecycleRuleDefinition lifecycleRule = remoteBucket.getLifecycleRules().get(0);
    try {
      assertTrue(
          lifecycleRule
              .getAction()
              .getActionType()
              .equals(LifecycleRuleDefinition.UpdateStorageClassLifecycleAction.TYPE));
      assertEquals(3, lifecycleRule.getCondition().getNumberOfNewerVersions().intValue());
      assertNotNull(lifecycleRule.getCondition().getCreatedBefore());
      assertFalse(lifecycleRule.getCondition().getIsLive());
      assertEquals(1, lifecycleRule.getCondition().getAge().intValue());
      assertEquals(1, lifecycleRule.getCondition().getMatchesStorageClass().size());
      assertEquals(30, lifecycleRule.getCondition().getDaysSinceNoncurrentTime().intValue());
      assertNotNull(lifecycleRule.getCondition().getNoncurrentTimeBefore());
      assertEquals(30, lifecycleRule.getCondition().getDaysSinceCustomTime().intValue());
      assertNotNull(lifecycleRule.getCondition().getCustomTimeBefore());
    } finally {
      storage.delete(lifecycleTestBucketName);
    }
  }

  @Test
  public void testClearBucketDefaultKmsKeyName() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket remoteBucket =
        storage.create(
            BucketInfo.newBucketBuilder(bucketName)
                .setDefaultKmsKeyName(kmsKeyOneResourcePath)
                .setLocation(KMS_KEY_RING_LOCATION)
                .buildBucketInfo());

    try {
      assertEquals(kmsKeyOneResourcePath, remoteBucket.getDefaultKmsKeyName());
      StorageBucket updatedBucket = remoteBucket.toBucketBuilder().setDefaultKmsKeyName(null).buildBucketInfo().modify();
      assertNull(updatedBucket.getDefaultKmsKeyName());
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testUpdateBucketDefaultKmsKeyName() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket remoteBucket =
        storage.create(
            BucketInfo.newBucketBuilder(bucketName)
                .setDefaultKmsKeyName(kmsKeyOneResourcePath)
                .setLocation(KMS_KEY_RING_LOCATION)
                .buildBucketInfo());

    try {
      assertEquals(kmsKeyOneResourcePath, remoteBucket.getDefaultKmsKeyName());
      StorageBucket updatedBucket =
          remoteBucket.toBucketBuilder().setDefaultKmsKeyName(kmsKeyTwoResourcePath).buildBucketInfo().modify();
      assertEquals(kmsKeyTwoResourcePath, updatedBucket.getDefaultKmsKeyName());
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testCreateBlob() {
    String blobName = "test-create-blob";
    BlobMetadata blob =
        BlobMetadata.newBuilder(BUCKET, blobName).setCustomTime(System.currentTimeMillis()).buildMetadata();
    StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob);
    assertNotNull(remoteBlob.getCustomTime());
    assertEquals(blob.getBucket(), remoteBlob.getBucket());
    assertEquals(blob.getName(), remoteBlob.getName());
    byte[] readBytes = storage.readAllBytes(BUCKET, blobName);
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
    assertTrue(remoteBlob.deleteFromStorage());
  }

  @Test
  public void testCreateBlobMd5Crc32cFromHexString() {
    String blobName = "test-create-blob-md5-crc32c-from-hex-string";
    BlobMetadata blob =
        BlobMetadata.newBuilder(BUCKET, blobName)
            .setContentType(CONTENT_TYPE)
            .setMd5FromHexString("3b54781b51c94835084898e821899585")
            .setCrc32cFromHexString("f4ddc43d")
            .buildMetadata();
    StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob);
    assertEquals(blob.getBucket(), remoteBlob.getBucket());
    assertEquals(blob.getName(), remoteBlob.getName());
    assertEquals(blob.getMd5ToHexString(), remoteBlob.getMd5ToHexString());
    assertEquals(blob.getCrc32cToHexString(), remoteBlob.getCrc32cToHexString());
    byte[] readBytes = storage.readAllBytes(BUCKET, blobName);
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
    assertTrue(remoteBlob.deleteFromStorage());
  }

  @Test
  public void testCreateGetBlobWithEncryptionKey() {
    String blobName = "test-create-with-customer-key-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob =
        storage.create(blob, BLOB_BYTE_CONTENT, CloudStorageClient.BlobUploadOption.customerSuppliedKey(KEY));
    assertNotNull(remoteBlob);
    assertEquals(blob.getBucket(), remoteBlob.getBucket());
    assertEquals(blob.getName(), remoteBlob.getName());
    byte[] readBytes =
        storage.readAllBytes(BUCKET, blobName, CloudStorageClient.BlobReadOption.withDecryptionKey(BASE64_KEY));
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
    remoteBlob =
        storage.get(
            blob.getBlobId(),
            CloudStorageClient.BlobGetOptions.withDecryptionKey(BASE64_KEY),
            CloudStorageClient.BlobGetOptions.selectFields(BlobMetadataField.CRC32C, BlobMetadataField.MD5HASH));
    assertNotNull(remoteBlob.getCrc32c());
    assertNotNull(remoteBlob.getMd5());
  }

  @Test
  public void testCreateBlobWithKmsKeyName() {
    String blobName = "test-create-with-kms-key-name-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob =
        storage.create(
            blob, BLOB_BYTE_CONTENT, CloudStorageClient.BlobUploadOption.kmsKey(kmsKeyOneResourcePath));
    assertNotNull(remoteBlob);
    assertEquals(blob.getBucket(), remoteBlob.getBucket());
    assertEquals(blob.getName(), remoteBlob.getName());
    assertNotNull(remoteBlob.getKmsKeyName());
    assertTrue(remoteBlob.getKmsKeyName().startsWith(kmsKeyOneResourcePath));
    byte[] readBytes = storage.readAllBytes(BUCKET, blobName);
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
  }

  @Test
  public void testCreateBlobWithKmsKeyNameAndCustomerSuppliedKey() {
    try {
      String blobName = "test-create-with-kms-key-name-blob";
      BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
      storage.create(
          blob,
          BLOB_BYTE_CONTENT,
          CloudStorageClient.BlobUploadOption.customerSuppliedKey(KEY),
          CloudStorageClient.BlobUploadOption.kmsKey(kmsKeyOneResourcePath));
      fail("StorageException was expected"); // can't supply both.
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testCreateBlobWithDefaultKmsKeyName()
      throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket bucket =
        storage.create(
            BucketInfo.newBucketBuilder(bucketName)
                .setDefaultKmsKeyName(kmsKeyOneResourcePath)
                .setLocation(KMS_KEY_RING_LOCATION)
                .buildBucketInfo());
    assertEquals(bucket.getDefaultKmsKeyName(), kmsKeyOneResourcePath);

    try {
      String blobName = "test-create-with-default-kms-key-name-blob";
      BlobMetadata blob = BlobMetadata.newBuilder(bucket, blobName).buildMetadata();
      StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
      assertNotNull(remoteBlob);
      assertEquals(blob.getBucket(), remoteBlob.getBucket());
      assertEquals(blob.getName(), remoteBlob.getName());
      assertNotNull(remoteBlob.getKmsKeyName());
      assertTrue(remoteBlob.getKmsKeyName().startsWith(kmsKeyOneResourcePath));
      byte[] readBytes = storage.readAllBytes(bucketName, blobName);
      assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testCreateEmptyBlob() {
    String blobName = "test-create-empty-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    assertEquals(blob.getBucket(), remoteBlob.getBucket());
    assertEquals(blob.getName(), remoteBlob.getName());
    byte[] readBytes = storage.readAllBytes(BUCKET, blobName);
    assertArrayEquals(new byte[0], readBytes);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobStream() {
    String blobName = "test-create-blob-stream";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).setContentType(CONTENT_TYPE).buildMetadata();
    ByteArrayInputStream stream = new ByteArrayInputStream(BLOB_STRING_CONTENT.getBytes(UTF_8));
    StorageObject remoteBlob = storage.create(blob, stream);
    assertNotNull(remoteBlob);
    assertEquals(blob.getBucket(), remoteBlob.getBucket());
    assertEquals(blob.getName(), remoteBlob.getName());
    assertEquals(blob.getContentType(), remoteBlob.getContentType());
    byte[] readBytes = storage.readAllBytes(BUCKET, blobName);
    assertEquals(BLOB_STRING_CONTENT, new String(readBytes, UTF_8));
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobStreamDisableGzipContent() {
    String blobName = "test-create-blob-stream-disable-gzip-compression";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).setContentType(CONTENT_TYPE).buildMetadata();
    ByteArrayInputStream stream = new ByteArrayInputStream(BLOB_STRING_CONTENT.getBytes(UTF_8));
    StorageObject remoteBlob = storage.create(blob, stream, BlobWriteOptions.disableGzip());
    assertNotNull(remoteBlob);
    assertEquals(blob.getBucket(), remoteBlob.getBucket());
    assertEquals(blob.getName(), remoteBlob.getName());
    assertEquals(blob.getContentType(), remoteBlob.getContentType());
    byte[] readBytes = storage.readAllBytes(BUCKET, blobName);
    assertEquals(BLOB_STRING_CONTENT, new String(readBytes, UTF_8));
  }

  @Test
  public void testCreateBlobFail() {
    String blobName = "test-create-blob-fail";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    BlobMetadata wrongGenerationBlob = BlobMetadata.newBuilder(BUCKET, blobName, -1L).buildMetadata();
    try {
      storage.create(
          wrongGenerationBlob, BLOB_BYTE_CONTENT, CloudStorageClient.BlobUploadOption.ifGenerationMatch());
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testCreateBlobMd5Fail() {
    String blobName = "test-create-blob-md5-fail";
    BlobMetadata blob =
        BlobMetadata.newBuilder(BUCKET, blobName)
            .setContentType(CONTENT_TYPE)
            .setMd5("O1R4G1HJSDUISJjoIYmVhQ==")
            .buildMetadata();
    ByteArrayInputStream stream = new ByteArrayInputStream(BLOB_STRING_CONTENT.getBytes(UTF_8));
    try {
      storage.create(blob, stream, CloudStorageClient.BlobWriteOptions.ifMd5Match());
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testGetBlobEmptySelectedFields() {
    String blobName = "test-get-empty-selected-fields-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).setContentType(CONTENT_TYPE).buildMetadata();
    assertNotNull(storage.create(blob));
    StorageObject remoteBlob = storage.get(blob.getBlobId(), CloudStorageClient.BlobGetOptions.selectFields());
    assertEquals(blob.getBlobId(), remoteBlob.getBlobId());
    assertNull(remoteBlob.getContentType());
  }

  @Test
  public void testGetBlobSelectedFields() {
    String blobName = "test-get-selected-fields-blob";
    BlobMetadata blob =
        BlobMetadata.newBuilder(BUCKET, blobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(ImmutableMap.of("k", "v"))
            .buildMetadata();
    assertNotNull(storage.create(blob));
    StorageObject remoteBlob =
        storage.get(blob.getBlobId(), CloudStorageClient.BlobGetOptions.selectFields(BlobMetadataField.METADATA));
    assertEquals(blob.getBlobId(), remoteBlob.getBlobId());
    assertEquals(ImmutableMap.of("k", "v"), remoteBlob.getMetadata());
    assertNull(remoteBlob.getContentType());
  }

  @Test
  public void testGetBlobKmsKeyNameField() {
    String blobName = "test-get-selected-kms-key-name-field-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).setContentType(CONTENT_TYPE).buildMetadata();
    assertNotNull(storage.create(blob, CloudStorageClient.BlobUploadOption.kmsKey(kmsKeyOneResourcePath)));
    StorageObject remoteBlob =
        storage.get(blob.getBlobId(), CloudStorageClient.BlobGetOptions.selectFields(BlobMetadataField.KMS_KEY_NAME));
    assertEquals(blob.getBlobId(), remoteBlob.getBlobId());
    assertTrue(remoteBlob.getKmsKeyName().startsWith(kmsKeyOneResourcePath));
    assertNull(remoteBlob.getContentType());
  }

  @Test
  public void testGetBlobAllSelectedFields() {
    String blobName = "test-get-all-selected-fields-blob";
    BlobMetadata blob =
        BlobMetadata.newBuilder(BUCKET, blobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(ImmutableMap.of("k", "v"))
            .buildMetadata();
    assertNotNull(storage.create(blob));
    StorageObject remoteBlob =
        storage.get(blob.getBlobId(), CloudStorageClient.BlobGetOptions.selectFields(BlobMetadataField.values()));
    assertEquals(blob.getBucket(), remoteBlob.getBucket());
    assertEquals(blob.getName(), remoteBlob.getName());
    assertEquals(ImmutableMap.of("k", "v"), remoteBlob.getMetadata());
    assertNotNull(remoteBlob.getGeneratedId());
    assertNotNull(remoteBlob.getSelfLink());
  }

  @Test
  public void testGetBlobFail() {
    String blobName = "test-get-blob-fail";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    BlobIdentifier wrongGenerationBlob = BlobIdentifier.from(BUCKET, blobName);
    try {
      storage.get(wrongGenerationBlob, CloudStorageClient.BlobGetOptions.ifGenerationMatch(-1));
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testGetBlobFailNonExistingGeneration() {
    String blobName = "test-get-blob-fail-non-existing-generation";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    BlobIdentifier wrongGenerationBlob = BlobIdentifier.from(BUCKET, blobName, -1L);
    try {
      assertNull(storage.get(wrongGenerationBlob));
      fail("Expected an 'Invalid argument' exception");
    } catch (StorageServiceException e) {
      assertThat(e.getMessage()).contains("Invalid argument");
    }
  }

  @Test(timeout = 5000)
  public void testListBlobsSelectedFields() throws InterruptedException {
    String[] blobNames = {
      "test-list-blobs-selected-fields-blob1", "test-list-blobs-selected-fields-blob2"
    };
    ImmutableMap<String, String> metadata = ImmutableMap.of("k", "v");
    BlobMetadata blob1 =
        BlobMetadata.newBuilder(BUCKET, blobNames[0])
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    BlobMetadata blob2 =
        BlobMetadata.newBuilder(BUCKET, blobNames[1])
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    StorageObject remoteBlob1 = storage.create(blob1);
    StorageObject remoteBlob2 = storage.create(blob2);
    assertNotNull(remoteBlob1);
    assertNotNull(remoteBlob2);
    Page<StorageObject> page =
        storage.list(
            BUCKET,
            CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-selected-fields-blob"),
            CloudStorageClient.BlobListOptions.withFields(BlobMetadataField.METADATA));
    // Listing blobs is eventually consistent, we loop until the list is of the expected size. The
    // test fails if timeout is reached.
    while (Iterators.size(page.iterateAll().iterator()) != 2) {
      Thread.sleep(500);
      page =
          storage.list(
              BUCKET,
              CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-selected-fields-blob"),
              CloudStorageClient.BlobListOptions.withFields(BlobMetadataField.METADATA));
    }
    Set<String> blobSet = ImmutableSet.of(blobNames[0], blobNames[1]);
    Iterator<StorageObject> iterator = page.iterateAll().iterator();
    while (iterator.hasNext()) {
      StorageObject remoteBlob = iterator.next();
      assertEquals(BUCKET, remoteBlob.getBucket());
      assertTrue(blobSet.contains(remoteBlob.getName()));
      assertEquals(metadata, remoteBlob.getMetadata());
      assertNull(remoteBlob.getContentType());
    }
  }

  @Test(timeout = 5000)
  public void testListBlobsKmsKeySelectedFields() throws InterruptedException {
    String[] blobNames = {
      "test-list-blobs-selected-field-kms-key-name-blob1",
      "test-list-blobs-selected-field-kms-key-name-blob2"
    };
    BlobMetadata blob1 = BlobMetadata.newBuilder(BUCKET, blobNames[0]).setContentType(CONTENT_TYPE).buildMetadata();
    BlobMetadata blob2 = BlobMetadata.newBuilder(BUCKET, blobNames[1]).setContentType(CONTENT_TYPE).buildMetadata();
    StorageObject remoteBlob1 =
        storage.create(blob1, CloudStorageClient.BlobUploadOption.kmsKey(kmsKeyOneResourcePath));
    StorageObject remoteBlob2 =
        storage.create(blob2, CloudStorageClient.BlobUploadOption.kmsKey(kmsKeyOneResourcePath));
    assertNotNull(remoteBlob1);
    assertNotNull(remoteBlob2);
    Page<StorageObject> page =
        storage.list(
            BUCKET,
            CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-selected-field-kms-key-name-blob"),
            CloudStorageClient.BlobListOptions.withFields(BlobMetadataField.KMS_KEY_NAME));
    // Listing blobs is eventually consistent, we loop until the list is of the expected size. The
    // test fails if timeout is reached.
    while (Iterators.size(page.iterateAll().iterator()) != 2) {
      Thread.sleep(500);
      page =
          storage.list(
              BUCKET,
              CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-selected-field-kms-key-name-blob"),
              CloudStorageClient.BlobListOptions.withFields(BlobMetadataField.KMS_KEY_NAME));
    }
    Set<String> blobSet = ImmutableSet.of(blobNames[0], blobNames[1]);
    Iterator<StorageObject> iterator = page.iterateAll().iterator();
    while (iterator.hasNext()) {
      StorageObject remoteBlob = iterator.next();
      assertEquals(BUCKET, remoteBlob.getBucket());
      assertTrue(blobSet.contains(remoteBlob.getName()));
      assertTrue(remoteBlob.getKmsKeyName().startsWith(kmsKeyOneResourcePath));
      assertNull(remoteBlob.getContentType());
    }
  }

  @Test(timeout = 5000)
  public void testListBlobsEmptySelectedFields() throws InterruptedException {
    String[] blobNames = {
      "test-list-blobs-empty-selected-fields-blob1", "test-list-blobs-empty-selected-fields-blob2"
    };
    BlobMetadata blob1 = BlobMetadata.newBuilder(BUCKET, blobNames[0]).setContentType(CONTENT_TYPE).buildMetadata();
    BlobMetadata blob2 = BlobMetadata.newBuilder(BUCKET, blobNames[1]).setContentType(CONTENT_TYPE).buildMetadata();
    StorageObject remoteBlob1 = storage.create(blob1);
    StorageObject remoteBlob2 = storage.create(blob2);
    assertNotNull(remoteBlob1);
    assertNotNull(remoteBlob2);
    Page<StorageObject> page =
        storage.list(
            BUCKET,
            CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-empty-selected-fields-blob"),
            CloudStorageClient.BlobListOptions.withFields());
    // Listing blobs is eventually consistent, we loop until the list is of the expected size. The
    // test fails if timeout is reached.
    while (Iterators.size(page.iterateAll().iterator()) != 2) {
      Thread.sleep(500);
      page =
          storage.list(
              BUCKET,
              CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-empty-selected-fields-blob"),
              CloudStorageClient.BlobListOptions.withFields());
    }
    Set<String> blobSet = ImmutableSet.of(blobNames[0], blobNames[1]);
    Iterator<StorageObject> iterator = page.iterateAll().iterator();
    while (iterator.hasNext()) {
      StorageObject remoteBlob = iterator.next();
      assertEquals(BUCKET, remoteBlob.getBucket());
      assertTrue(blobSet.contains(remoteBlob.getName()));
      assertNull(remoteBlob.getContentType());
    }
  }

  @Test(timeout = 7500)
  public void testListBlobRequesterPays() throws InterruptedException {
    unsetRequesterPays();
    BlobMetadata blob1 =
        BlobMetadata.newBuilder(BUCKET_REQUESTER_PAYS, "test-list-blobs-empty-selected-fields-blob1")
            .setContentType(CONTENT_TYPE)
            .buildMetadata();
    assertNotNull(storage.create(blob1));

    // Test listing a Requester Pays bucket.
    StorageBucket remoteBucket =
        storage.get(
            BUCKET_REQUESTER_PAYS,
            CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.ID, BucketMetadataField.BILLING));

    assertTrue(remoteBucket.isRequesterPays() == null || !remoteBucket.isRequesterPays());
    remoteBucket = remoteBucket.toBucketBuilder().setRequesterPays(true).buildBucketInfo();
    StorageBucket updatedBucket = storage.update(remoteBucket);
    assertTrue(updatedBucket.isRequesterPays());
    try {
      storage.list(
          BUCKET_REQUESTER_PAYS,
          CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-empty-selected-fields-blob"),
          CloudStorageClient.BlobListOptions.withFields(),
          CloudStorageClient.BlobListOptions.withUserProject("fakeBillingProjectId"));
      fail("Expected bad user project error.");
    } catch (StorageServiceException e) {
      assertTrue(e.getMessage().contains("User project specified in the request is invalid"));
    }

    String projectId = remoteStorageHelper.getOptions().getProjectId();
    while (true) {
      Page<StorageObject> page =
          storage.list(
              BUCKET_REQUESTER_PAYS,
              CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-empty-selected-fields-blob"),
              CloudStorageClient.BlobListOptions.withFields(),
              CloudStorageClient.BlobListOptions.withUserProject(projectId));
      List<StorageObject> blobs = Lists.newArrayList(page.iterateAll());
      // If the list is empty, maybe the blob isn't visible yet; wait and try again.
      // Otherwise, expect one blob, since we only put in one above.
      if (!blobs.isEmpty()) {
        assertThat(blobs).hasSize(1);
        break;
      }
      Thread.sleep(500);
    }
  }

  @Test(timeout = 15000)
  public void testListBlobsVersioned() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket bucket =
        storage.create(BucketInfo.newBucketBuilder(bucketName).setVersioningEnabled(true).buildBucketInfo());
    try {
      String[] blobNames = {"test-list-blobs-versioned-blob1", "test-list-blobs-versioned-blob2"};
      BlobMetadata blob1 =
          BlobMetadata.newBuilder(bucket, blobNames[0]).setContentType(CONTENT_TYPE).buildMetadata();
      BlobMetadata blob2 =
          BlobMetadata.newBuilder(bucket, blobNames[1]).setContentType(CONTENT_TYPE).buildMetadata();
      StorageObject remoteBlob1 = storage.create(blob1);
      StorageObject remoteBlob2 = storage.create(blob2);
      StorageObject remoteBlob3 = storage.create(blob2);
      assertNotNull(remoteBlob1);
      assertNotNull(remoteBlob2);
      assertNotNull(remoteBlob3);
      Page<StorageObject> page =
          storage.list(
              bucketName,
              CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-versioned-blob"),
              CloudStorageClient.BlobListOptions.withVersions(true));
      // Listing blobs is eventually consistent, we loop until the list is of the expected size. The
      // test fails if timeout is reached.
      while (Iterators.size(page.iterateAll().iterator()) != 3) {
        Thread.sleep(500);
        page =
            storage.list(
                bucketName,
                CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-versioned-blob"),
                CloudStorageClient.BlobListOptions.withVersions(true));
      }
      Set<String> blobSet = ImmutableSet.of(blobNames[0], blobNames[1]);
      Iterator<StorageObject> iterator = page.iterateAll().iterator();
      while (iterator.hasNext()) {
        StorageObject remoteBlob = iterator.next();
        assertEquals(bucketName, remoteBlob.getBucket());
        assertTrue(blobSet.contains(remoteBlob.getName()));
        assertNotNull(remoteBlob.getGeneration());
      }
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testListBlobsWithOffset() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket bucket =
        storage.create(BucketInfo.newBucketBuilder(bucketName).setVersioningEnabled(true).buildBucketInfo());
    try {
      List<String> blobNames =
          ImmutableList.of("startOffset_blob1", "startOffset_blob2", "blob3_endOffset");
      BlobMetadata blob1 =
          BlobMetadata.newBuilder(bucket, blobNames.get(0)).setContentType(CONTENT_TYPE).buildMetadata();
      BlobMetadata blob2 =
          BlobMetadata.newBuilder(bucket, blobNames.get(1)).setContentType(CONTENT_TYPE).buildMetadata();
      BlobMetadata blob3 =
          BlobMetadata.newBuilder(bucket, blobNames.get(2)).setContentType(CONTENT_TYPE).buildMetadata();

      StorageObject remoteBlob1 = storage.create(blob1);
      StorageObject remoteBlob2 = storage.create(blob2);
      StorageObject remoteBlob3 = storage.create(blob3);
      assertNotNull(remoteBlob1);
      assertNotNull(remoteBlob2);
      assertNotNull(remoteBlob3);

      // Listing blobs without BlobListOptions.
      Page<StorageObject> page1 = storage.list(bucketName);
      assertEquals(3, Iterators.size(page1.iterateAll().iterator()));

      // Listing blobs with startOffset.
      Page<StorageObject> page2 =
          storage.list(bucketName, CloudStorageClient.BlobListOptions.withStartOffset("startOffset"));
      assertEquals(2, Iterators.size(page2.iterateAll().iterator()));

      // Listing blobs with endOffset.
      Page<StorageObject> page3 = storage.list(bucketName, CloudStorageClient.BlobListOptions.withEndOffset("endOffset"));
      assertEquals(1, Iterators.size(page3.iterateAll().iterator()));

      // Listing blobs with startOffset and endOffset.
      Page<StorageObject> page4 =
          storage.list(
              bucketName,
              CloudStorageClient.BlobListOptions.withStartOffset("startOffset"),
              CloudStorageClient.BlobListOptions.withEndOffset("endOffset"));
      assertEquals(0, Iterators.size(page4.iterateAll().iterator()));
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test(timeout = 5000)
  public void testListBlobsCurrentDirectory() throws InterruptedException {
    String directoryName = "test-list-blobs-current-directory/";
    String subdirectoryName = "subdirectory/";
    String[] blobNames = {directoryName + subdirectoryName + "blob1", directoryName + "blob2"};
    BlobMetadata blob1 = BlobMetadata.newBuilder(BUCKET, blobNames[0]).setContentType(CONTENT_TYPE).buildMetadata();
    BlobMetadata blob2 = BlobMetadata.newBuilder(BUCKET, blobNames[1]).setContentType(CONTENT_TYPE).buildMetadata();
    StorageObject remoteBlob1 = storage.create(blob1, BLOB_BYTE_CONTENT);
    StorageObject remoteBlob2 = storage.create(blob2, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob1);
    assertNotNull(remoteBlob2);
    Page<StorageObject> page =
        storage.list(
            BUCKET,
            CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-current-directory/"),
            CloudStorageClient.BlobListOptions.onlyCurrentDirectory());
    // Listing blobs is eventually consistent, we loop until the list is of the expected size. The
    // test fails if timeout is reached.
    while (Iterators.size(page.iterateAll().iterator()) != 2) {
      Thread.sleep(500);
      page =
          storage.list(
              BUCKET,
              CloudStorageClient.BlobListOptions.withPrefix("test-list-blobs-current-directory/"),
              CloudStorageClient.BlobListOptions.onlyCurrentDirectory());
    }
    Iterator<StorageObject> iterator = page.iterateAll().iterator();
    while (iterator.hasNext()) {
      StorageObject remoteBlob = iterator.next();
      assertEquals(BUCKET, remoteBlob.getBucket());
      if (remoteBlob.getName().equals(blobNames[1])) {
        assertEquals(CONTENT_TYPE, remoteBlob.getContentType());
        assertEquals(BLOB_BYTE_CONTENT.length, (long) remoteBlob.getSize());
        assertFalse(remoteBlob.isDirectory());
      } else if (remoteBlob.getName().equals(directoryName + subdirectoryName)) {
        assertEquals(0L, (long) remoteBlob.getSize());
        assertTrue(remoteBlob.isDirectory());
      } else {
        fail("Unexpected blob with name " + remoteBlob.getName());
      }
    }
  }

  @Test
  public void testUpdateBlob() {
    String blobName = "test-update-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    StorageObject updatedBlob = remoteBlob.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata().updateInStorage();
    assertNotNull(updatedBlob);
    assertEquals(blob.getName(), updatedBlob.getName());
    assertEquals(blob.getBucket(), updatedBlob.getBucket());
    assertEquals(CONTENT_TYPE, updatedBlob.getContentType());
  }

  @Test
  public void testUpdateBlobReplaceMetadata() {
    String blobName = "test-update-blob-replace-metadata";
    ImmutableMap<String, String> metadata = ImmutableMap.of("k1", "a");
    ImmutableMap<String, String> newMetadata = ImmutableMap.of("k2", "b");
    BlobMetadata blob =
        BlobMetadata.newBuilder(BUCKET, blobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    StorageObject updatedBlob = remoteBlob.toBuilderCopy().setMetadata(null).buildMetadata().updateInStorage();
    assertNotNull(updatedBlob);
    assertNull(updatedBlob.getMetadata());
    updatedBlob = remoteBlob.toBuilderCopy().setMetadata(newMetadata).buildMetadata().updateInStorage();
    assertEquals(blob.getName(), updatedBlob.getName());
    assertEquals(blob.getBucket(), updatedBlob.getBucket());
    assertEquals(newMetadata, updatedBlob.getMetadata());
  }

  @Test
  public void testUpdateBlobMergeMetadata() {
    String blobName = "test-update-blob-merge-metadata";
    ImmutableMap<String, String> metadata = ImmutableMap.of("k1", "a");
    ImmutableMap<String, String> newMetadata = ImmutableMap.of("k2", "b");
    ImmutableMap<String, String> expectedMetadata = ImmutableMap.of("k1", "a", "k2", "b");
    BlobMetadata blob =
        BlobMetadata.newBuilder(BUCKET, blobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    StorageObject updatedBlob = remoteBlob.toBuilderCopy().setMetadata(newMetadata).buildMetadata().updateInStorage();
    assertNotNull(updatedBlob);
    assertEquals(blob.getName(), updatedBlob.getName());
    assertEquals(blob.getBucket(), updatedBlob.getBucket());
    assertEquals(expectedMetadata, updatedBlob.getMetadata());
  }

  @Test
  public void testUpdateBlobUnsetMetadata() {
    String blobName = "test-update-blob-unset-metadata";
    ImmutableMap<String, String> metadata = ImmutableMap.of("k1", "a", "k2", "b");
    Map<String, String> newMetadata = new HashMap<>();
    newMetadata.put("k1", "a");
    newMetadata.put("k2", null);
    ImmutableMap<String, String> expectedMetadata = ImmutableMap.of("k1", "a");
    BlobMetadata blob =
        BlobMetadata.newBuilder(BUCKET, blobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    StorageObject updatedBlob = remoteBlob.toBuilderCopy().setMetadata(newMetadata).buildMetadata().updateInStorage();
    assertNotNull(updatedBlob);
    assertEquals(blob.getName(), updatedBlob.getName());
    assertEquals(blob.getBucket(), updatedBlob.getBucket());
    assertEquals(expectedMetadata, updatedBlob.getMetadata());
  }

  @Test
  public void testUpdateBlobFail() {
    String blobName = "test-update-blob-fail";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    BlobMetadata wrongGenerationBlob =
        BlobMetadata.newBuilder(BUCKET, blobName, -1L).setContentType(CONTENT_TYPE).buildMetadata();
    try {
      storage.update(wrongGenerationBlob, CloudStorageClient.BlobUploadOption.ifGenerationMatch());
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testDeleteNonExistingBlob() {
    String blobName = "test-delete-non-existing-blob";
    assertFalse(storage.delete(BUCKET, blobName));
  }

  @Test
  public void testDeleteBlobNonExistingGeneration() {
    String blobName = "test-delete-blob-non-existing-generation";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    assertNotNull(storage.create(blob));
    try {
      assertFalse(storage.delete(BlobIdentifier.from(BUCKET, blobName, -1L)));
      fail("Expected an 'Invalid argument' exception");
    } catch (StorageServiceException e) {
      assertThat(e.getMessage()).contains("Invalid argument");
    }
  }

  @Test
  public void testDeleteBlobFail() {
    String blobName = "test-delete-blob-fail";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    try {
      storage.delete(BUCKET, blob.getName(), CloudStorageClient.BlobReadOption.ifGenerationMatch(-1L));
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
    assertTrue(remoteBlob.deleteFromStorage());
  }

  @Test
  public void testComposeBlob() {
    String sourceBlobName1 = "test-compose-blob-source-1";
    String sourceBlobName2 = "test-compose-blob-source-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    StorageObject remoteSourceBlob1 = storage.create(sourceBlob1, BLOB_BYTE_CONTENT);
    StorageObject remoteSourceBlob2 = storage.create(sourceBlob2, BLOB_BYTE_CONTENT);
    assertNotNull(remoteSourceBlob1);
    assertNotNull(remoteSourceBlob2);
    String targetBlobName = "test-compose-blob-target";
    BlobMetadata targetBlob = BlobMetadata.newBuilder(BUCKET, targetBlobName).buildMetadata();
    CloudStorageClient.ComposeBlobsRequest req =
        CloudStorageClient.ComposeBlobsRequest.of(ImmutableList.of(sourceBlobName1, sourceBlobName2), targetBlob);
    StorageObject remoteTargetBlob = storage.compose(req);
    assertNotNull(remoteTargetBlob);
    assertEquals(targetBlob.getName(), remoteTargetBlob.getName());
    assertEquals(targetBlob.getBucket(), remoteTargetBlob.getBucket());
    assertNull(remoteTargetBlob.getContentType());
    byte[] readBytes = storage.readAllBytes(BUCKET, targetBlobName);
    byte[] composedBytes = Arrays.copyOf(BLOB_BYTE_CONTENT, BLOB_BYTE_CONTENT.length * 2);
    System.arraycopy(
        BLOB_BYTE_CONTENT, 0, composedBytes, BLOB_BYTE_CONTENT.length, BLOB_BYTE_CONTENT.length);
    assertArrayEquals(composedBytes, readBytes);
  }

  @Test
  public void testComposeBlobWithContentType() {
    String sourceBlobName1 = "test-compose-blob-with-content-type-source-1";
    String sourceBlobName2 = "test-compose-blob-with-content-type-source-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    StorageObject remoteSourceBlob1 = storage.create(sourceBlob1, BLOB_BYTE_CONTENT);
    StorageObject remoteSourceBlob2 = storage.create(sourceBlob2, BLOB_BYTE_CONTENT);
    assertNotNull(remoteSourceBlob1);
    assertNotNull(remoteSourceBlob2);
    String targetBlobName = "test-compose-blob-with-content-type-target";
    BlobMetadata targetBlob =
        BlobMetadata.newBuilder(BUCKET, targetBlobName).setContentType(CONTENT_TYPE).buildMetadata();
    CloudStorageClient.ComposeBlobsRequest req =
        CloudStorageClient.ComposeBlobsRequest.of(ImmutableList.of(sourceBlobName1, sourceBlobName2), targetBlob);
    StorageObject remoteTargetBlob = storage.compose(req);
    assertNotNull(remoteTargetBlob);
    assertEquals(targetBlob.getName(), remoteTargetBlob.getName());
    assertEquals(targetBlob.getBucket(), remoteTargetBlob.getBucket());
    assertEquals(CONTENT_TYPE, remoteTargetBlob.getContentType());
    byte[] readBytes = storage.readAllBytes(BUCKET, targetBlobName);
    byte[] composedBytes = Arrays.copyOf(BLOB_BYTE_CONTENT, BLOB_BYTE_CONTENT.length * 2);
    System.arraycopy(
        BLOB_BYTE_CONTENT, 0, composedBytes, BLOB_BYTE_CONTENT.length, BLOB_BYTE_CONTENT.length);
    assertArrayEquals(composedBytes, readBytes);
  }

  @Test
  public void testComposeBlobFail() {
    String sourceBlobName1 = "test-compose-blob-fail-source-1";
    String sourceBlobName2 = "test-compose-blob-fail-source-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    StorageObject remoteSourceBlob1 = storage.create(sourceBlob1);
    StorageObject remoteSourceBlob2 = storage.create(sourceBlob2);
    assertNotNull(remoteSourceBlob1);
    assertNotNull(remoteSourceBlob2);
    String targetBlobName = "test-compose-blob-fail-target";
    BlobMetadata targetBlob = BlobMetadata.newBuilder(BUCKET, targetBlobName).buildMetadata();
    CloudStorageClient.ComposeBlobsRequest req =
        CloudStorageClient.ComposeBlobsRequest.builder()
            .addSources(sourceBlobName1, -1L)
            .addSources(sourceBlobName2, -1L)
            .setTarget(targetBlob)
            .buildRequest();
    try {
      storage.compose(req);
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testCopyBlob() {
    String sourceBlobName = "test-copy-blob-source";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName);
    ImmutableMap<String, String> metadata = ImmutableMap.of("k", "v");
    BlobMetadata blob =
        BlobMetadata.newBuilder(source).setContentType(CONTENT_TYPE).setMetadata(metadata).buildMetadata();
    StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob);
    String targetBlobName = "test-copy-blob-target";
    CloudStorageClient.CopyOperationRequest req = CloudStorageClient.CopyOperationRequest.create(source, BlobIdentifier.from(BUCKET, targetBlobName));
    BlobRewriteWriter copyWriter = storage.copy(req);
    assertEquals(BUCKET, copyWriter.getResult().getBucket());
    assertEquals(targetBlobName, copyWriter.getResult().getName());
    assertEquals(CONTENT_TYPE, copyWriter.getResult().getContentType());
    assertEquals(metadata, copyWriter.getResult().getMetadata());
    assertTrue(copyWriter.isDone());
    assertTrue(remoteBlob.deleteFromStorage());
    assertTrue(storage.delete(BUCKET, targetBlobName));
  }

  @Test
  public void testCopyBlobWithPredefinedAcl() {
    String sourceBlobName = "test-copy-blob-source";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName);
    ImmutableMap<String, String> metadata = ImmutableMap.of("k", "v");
    BlobMetadata blob =
        BlobMetadata.newBuilder(source).setContentType(CONTENT_TYPE).setMetadata(metadata).buildMetadata();
    StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob);
    String targetBlobName = "test-copy-blob-target";
    CloudStorageClient.CopyOperationRequest req =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(source)
            .setTarget(
                BlobIdentifier.from(BUCKET, targetBlobName),
                CloudStorageClient.BlobUploadOption.presetAcl(CloudStorageClient.PredefinedAccessControl.PUBLIC_READ))
            .buildRequest();
    BlobRewriteWriter copyWriter = storage.copy(req);
    assertEquals(BUCKET, copyWriter.getResult().getBucket());
    assertEquals(targetBlobName, copyWriter.getResult().getName());
    assertEquals(CONTENT_TYPE, copyWriter.getResult().getContentType());
    assertEquals(metadata, copyWriter.getResult().getMetadata());
    assertNotNull(copyWriter.getResult().getAcl(UserIdentity.allUsers()));
    assertTrue(copyWriter.isDone());
    assertTrue(remoteBlob.deleteFromStorage());
    assertTrue(storage.delete(BUCKET, targetBlobName));
  }

  @Test
  public void testCopyBlobWithEncryptionKeys() {
    String sourceBlobName = "test-copy-blob-encryption-key-source";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName);
    ImmutableMap<String, String> metadata = ImmutableMap.of("k", "v");
    StorageObject remoteBlob =
        storage.create(
            BlobMetadata.newBuilder(source).buildMetadata(),
            BLOB_BYTE_CONTENT,
            CloudStorageClient.BlobUploadOption.customerSuppliedKey(KEY));
    assertNotNull(remoteBlob);
    String targetBlobName = "test-copy-blob-encryption-key-target";
    BlobMetadata target =
        BlobMetadata.newBuilder(BUCKET, targetBlobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    CloudStorageClient.CopyOperationRequest req =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(source)
            .setTarget(target, CloudStorageClient.BlobUploadOption.customerSuppliedKey(OTHER_BASE64_KEY))
            .setSourceOptions(CloudStorageClient.BlobReadOption.withDecryptionKey(BASE64_KEY))
            .buildRequest();
    BlobRewriteWriter copyWriter = storage.copy(req);
    assertEquals(BUCKET, copyWriter.getResult().getBucket());
    assertEquals(targetBlobName, copyWriter.getResult().getName());
    assertEquals(CONTENT_TYPE, copyWriter.getResult().getContentType());
    assertArrayEquals(
        BLOB_BYTE_CONTENT,
        copyWriter.getResult().getContent(StorageObject.BlobSourceOptions.withDecryptionKey(OTHER_BASE64_KEY)));
    assertEquals(metadata, copyWriter.getResult().getMetadata());
    assertTrue(copyWriter.isDone());
    req =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(source)
            .setTarget(target)
            .setSourceOptions(CloudStorageClient.BlobReadOption.withDecryptionKey(BASE64_KEY))
            .buildRequest();
    copyWriter = storage.copy(req);
    assertEquals(BUCKET, copyWriter.getResult().getBucket());
    assertEquals(targetBlobName, copyWriter.getResult().getName());
    assertEquals(CONTENT_TYPE, copyWriter.getResult().getContentType());
    assertArrayEquals(BLOB_BYTE_CONTENT, copyWriter.getResult().getContent());
    assertEquals(metadata, copyWriter.getResult().getMetadata());
    assertTrue(copyWriter.isDone());
    assertTrue(remoteBlob.deleteFromStorage());
    assertTrue(storage.delete(BUCKET, targetBlobName));
  }

  @Test
  public void testRotateFromCustomerEncryptionToKmsKey() {
    String sourceBlobName = "test-copy-blob-encryption-key-source";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName);
    ImmutableMap<String, String> metadata = ImmutableMap.of("k", "v");
    StorageObject remoteBlob =
        storage.create(
            BlobMetadata.newBuilder(source).buildMetadata(),
            BLOB_BYTE_CONTENT,
            CloudStorageClient.BlobUploadOption.customerSuppliedKey(KEY));
    assertNotNull(remoteBlob);
    String targetBlobName = "test-copy-blob-kms-key-target";
    BlobMetadata target =
        BlobMetadata.newBuilder(BUCKET, targetBlobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    CloudStorageClient.CopyOperationRequest req =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(source)
            .setSourceOptions(CloudStorageClient.BlobReadOption.withDecryptionKey(BASE64_KEY))
            .setTarget(target, CloudStorageClient.BlobUploadOption.kmsKey(kmsKeyOneResourcePath))
            .buildRequest();
    BlobRewriteWriter copyWriter = storage.copy(req);
    assertEquals(BUCKET, copyWriter.getResult().getBucket());
    assertEquals(targetBlobName, copyWriter.getResult().getName());
    assertEquals(CONTENT_TYPE, copyWriter.getResult().getContentType());
    assertNotNull(copyWriter.getResult().getKmsKeyName());
    assertTrue(copyWriter.getResult().getKmsKeyName().startsWith(kmsKeyOneResourcePath));
    assertArrayEquals(BLOB_BYTE_CONTENT, copyWriter.getResult().getContent());
    assertEquals(metadata, copyWriter.getResult().getMetadata());
    assertTrue(copyWriter.isDone());
    assertTrue(storage.delete(BUCKET, targetBlobName));
  }

  @Test
  public void testRotateFromCustomerEncryptionToKmsKeyWithCustomerEncryption() {
    String sourceBlobName = "test-copy-blob-encryption-key-source";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName);
    ImmutableMap<String, String> metadata = ImmutableMap.of("k", "v");
    StorageObject remoteBlob =
        storage.create(
            BlobMetadata.newBuilder(source).buildMetadata(),
            BLOB_BYTE_CONTENT,
            CloudStorageClient.BlobUploadOption.customerSuppliedKey(KEY));
    assertNotNull(remoteBlob);
    String targetBlobName = "test-copy-blob-kms-key-target";
    BlobMetadata target =
        BlobMetadata.newBuilder(BUCKET, targetBlobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    try {
      CloudStorageClient.CopyOperationRequest req =
          CloudStorageClient.CopyOperationRequest.builder()
              .setSource(source)
              .setSourceOptions(CloudStorageClient.BlobReadOption.withDecryptionKey(BASE64_KEY))
              .setTarget(
                  target,
                  CloudStorageClient.BlobUploadOption.customerSuppliedKey(KEY),
                  CloudStorageClient.BlobUploadOption.kmsKey(kmsKeyOneResourcePath))
              .buildRequest();
      storage.copy(req);
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testCopyBlobUpdateMetadata() {
    String sourceBlobName = "test-copy-blob-update-metadata-source";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName);
    StorageObject remoteSourceBlob = storage.create(BlobMetadata.newBuilder(source).buildMetadata(), BLOB_BYTE_CONTENT);
    assertNotNull(remoteSourceBlob);
    String targetBlobName = "test-copy-blob-update-metadata-target";
    ImmutableMap<String, String> metadata = ImmutableMap.of("k", "v");
    BlobMetadata target =
        BlobMetadata.newBuilder(BUCKET, targetBlobName)
            .setContentType(CONTENT_TYPE)
            .setMetadata(metadata)
            .buildMetadata();
    CloudStorageClient.CopyOperationRequest req = CloudStorageClient.CopyOperationRequest.create(source, target);
    BlobRewriteWriter copyWriter = storage.copy(req);
    assertEquals(BUCKET, copyWriter.getResult().getBucket());
    assertEquals(targetBlobName, copyWriter.getResult().getName());
    assertEquals(CONTENT_TYPE, copyWriter.getResult().getContentType());
    assertEquals(metadata, copyWriter.getResult().getMetadata());
    assertTrue(copyWriter.isDone());
    assertTrue(remoteSourceBlob.deleteFromStorage());
    assertTrue(storage.delete(BUCKET, targetBlobName));
  }

  // Re-enable this test when it stops failing
  // @Test
  public void testCopyBlobUpdateStorageClass() {
    String sourceBlobName = "test-copy-blob-update-storage-class-source";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName);
    BlobMetadata sourceInfo =
        BlobMetadata.newBuilder(source).setStorageClass(StorageClassType.STANDARD).buildMetadata();
    StorageObject remoteSourceBlob = storage.create(sourceInfo, BLOB_BYTE_CONTENT);
    assertNotNull(remoteSourceBlob);
    assertEquals(StorageClassType.STANDARD, remoteSourceBlob.getStorageClass());

    String targetBlobName = "test-copy-blob-update-storage-class-target";
    BlobMetadata targetInfo =
        BlobMetadata.newBuilder(BUCKET, targetBlobName).setStorageClass(StorageClassType.COLDLINE).buildMetadata();
    CloudStorageClient.CopyOperationRequest req = CloudStorageClient.CopyOperationRequest.create(source, targetInfo);
    BlobRewriteWriter copyWriter = storage.copy(req);
    assertEquals(BUCKET, copyWriter.getResult().getBucket());
    assertEquals(targetBlobName, copyWriter.getResult().getName());
    assertEquals(StorageClassType.COLDLINE, copyWriter.getResult().getStorageClass());
    assertTrue(copyWriter.isDone());
    assertTrue(remoteSourceBlob.deleteFromStorage());
    assertTrue(storage.delete(BUCKET, targetBlobName));
  }

  @Test
  public void testCopyBlobNoContentType() {
    String sourceBlobName = "test-copy-blob-no-content-type-source";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName);
    StorageObject remoteSourceBlob = storage.create(BlobMetadata.newBuilder(source).buildMetadata(), BLOB_BYTE_CONTENT);
    assertNotNull(remoteSourceBlob);
    String targetBlobName = "test-copy-blob-no-content-type-target";
    ImmutableMap<String, String> metadata = ImmutableMap.of("k", "v");
    BlobMetadata target = BlobMetadata.newBuilder(BUCKET, targetBlobName).setMetadata(metadata).buildMetadata();
    CloudStorageClient.CopyOperationRequest req = CloudStorageClient.CopyOperationRequest.create(source, target);
    BlobRewriteWriter copyWriter = storage.copy(req);
    assertEquals(BUCKET, copyWriter.getResult().getBucket());
    assertEquals(targetBlobName, copyWriter.getResult().getName());
    assertNull(copyWriter.getResult().getContentType());
    assertEquals(metadata, copyWriter.getResult().getMetadata());
    assertTrue(copyWriter.isDone());
    assertTrue(remoteSourceBlob.deleteFromStorage());
    assertTrue(storage.delete(BUCKET, targetBlobName));
  }

  @Test
  public void testCopyBlobFail() {
    String sourceBlobName = "test-copy-blob-source-fail";
    BlobIdentifier source = BlobIdentifier.from(BUCKET, sourceBlobName, -1L);
    StorageObject remoteSourceBlob = storage.create(BlobMetadata.newBuilder(source).buildMetadata(), BLOB_BYTE_CONTENT);
    assertNotNull(remoteSourceBlob);
    String targetBlobName = "test-copy-blob-target-fail";
    BlobMetadata target =
        BlobMetadata.newBuilder(BUCKET, targetBlobName).setContentType(CONTENT_TYPE).buildMetadata();
    CloudStorageClient.CopyOperationRequest req =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(BUCKET, sourceBlobName)
            .setSourceOptions(CloudStorageClient.BlobReadOption.ifGenerationMatch(-1L))
            .setTarget(target)
            .buildRequest();
    try {
      storage.copy(req);
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
    CloudStorageClient.CopyOperationRequest req2 =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(source)
            .setSourceOptions(CloudStorageClient.BlobReadOption.ifGenerationMatch())
            .setTarget(target)
            .buildRequest();
    try {
      storage.copy(req2);
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testBatchRequest() {
    String sourceBlobName1 = "test-batch-request-blob-1";
    String sourceBlobName2 = "test-batch-request-blob-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    assertNotNull(storage.create(sourceBlob1));
    assertNotNull(storage.create(sourceBlob2));

    // Batch update request
    BlobMetadata updatedBlob1 = sourceBlob1.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata();
    BlobMetadata updatedBlob2 = sourceBlob2.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata();
    StorageRpcBatch updateBatch = storage.batch();
    StorageBatchResult<StorageObject> updateResult1 = updateBatch.modify(updatedBlob1);
    StorageBatchResult<StorageObject> updateResult2 = updateBatch.modify(updatedBlob2);
    updateBatch.submitBatch();
    StorageObject remoteUpdatedBlob1 = updateResult1.get();
    StorageObject remoteUpdatedBlob2 = updateResult2.get();
    assertEquals(sourceBlob1.getBucket(), remoteUpdatedBlob1.getBucket());
    assertEquals(sourceBlob1.getName(), remoteUpdatedBlob1.getName());
    assertEquals(sourceBlob2.getBucket(), remoteUpdatedBlob2.getBucket());
    assertEquals(sourceBlob2.getName(), remoteUpdatedBlob2.getName());
    assertEquals(updatedBlob1.getContentType(), remoteUpdatedBlob1.getContentType());
    assertEquals(updatedBlob2.getContentType(), remoteUpdatedBlob2.getContentType());

    // Batch get request
    StorageRpcBatch getBatch = storage.batch();
    StorageBatchResult<StorageObject> getResult1 = getBatch.get(BUCKET, sourceBlobName1);
    StorageBatchResult<StorageObject> getResult2 = getBatch.get(BUCKET, sourceBlobName2);
    getBatch.submitBatch();
    StorageObject remoteBlob1 = getResult1.get();
    StorageObject remoteBlob2 = getResult2.get();
    assertEquals(remoteUpdatedBlob1, remoteBlob1);
    assertEquals(remoteUpdatedBlob2, remoteBlob2);

    // Batch delete request
    StorageRpcBatch deleteBatch = storage.batch();
    StorageBatchResult<Boolean> deleteResult1 = deleteBatch.remove(BUCKET, sourceBlobName1);
    StorageBatchResult<Boolean> deleteResult2 = deleteBatch.remove(BUCKET, sourceBlobName2);
    deleteBatch.submitBatch();
    assertTrue(deleteResult1.get());
    assertTrue(deleteResult2.get());
  }

  @Test
  public void testBatchRequestManyOperations() {
    List<StorageBatchResult<Boolean>> deleteResults =
        Lists.newArrayListWithCapacity(MAX_BATCH_SIZE);
    List<StorageBatchResult<StorageObject>> getResults = Lists.newArrayListWithCapacity(MAX_BATCH_SIZE / 2);
    List<StorageBatchResult<StorageObject>> updateResults =
        Lists.newArrayListWithCapacity(MAX_BATCH_SIZE / 2);
    StorageRpcBatch batch = storage.batch();
    for (int i = 0; i < MAX_BATCH_SIZE; i++) {
      BlobIdentifier blobId = BlobIdentifier.from(BUCKET, "test-batch-request-many-operations-blob-" + i);
      deleteResults.add(batch.remove(blobId));
    }
    for (int i = 0; i < MAX_BATCH_SIZE / 2; i++) {
      BlobIdentifier blobId = BlobIdentifier.from(BUCKET, "test-batch-request-many-operations-blob-" + i);
      getResults.add(batch.get(blobId));
    }
    for (int i = 0; i < MAX_BATCH_SIZE / 2; i++) {
      BlobMetadata blob =
          BlobMetadata.newBuilder(BlobIdentifier.from(BUCKET, "test-batch-request-many-operations-blob-" + i))
              .buildMetadata();
      updateResults.add(batch.modify(blob));
    }

    String sourceBlobName1 = "test-batch-request-many-operations-source-blob-1";
    String sourceBlobName2 = "test-batch-request-many-operations-source-blob-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    assertNotNull(storage.create(sourceBlob1));
    assertNotNull(storage.create(sourceBlob2));
    BlobMetadata updatedBlob2 = sourceBlob2.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata();

    StorageBatchResult<StorageObject> getResult = batch.get(BUCKET, sourceBlobName1);
    StorageBatchResult<StorageObject> updateResult = batch.modify(updatedBlob2);

    batch.submitBatch();

    // Check deletes
    for (StorageBatchResult<Boolean> failedDeleteResult : deleteResults) {
      assertFalse(failedDeleteResult.get());
    }

    // Check gets
    for (StorageBatchResult<StorageObject> failedGetResult : getResults) {
      assertNull(failedGetResult.get());
    }
    StorageObject remoteBlob1 = getResult.get();
    assertEquals(sourceBlob1.getBucket(), remoteBlob1.getBucket());
    assertEquals(sourceBlob1.getName(), remoteBlob1.getName());

    // Check updates
    for (StorageBatchResult<StorageObject> failedUpdateResult : updateResults) {
      try {
        failedUpdateResult.get();
        fail("Expected StorageException");
      } catch (StorageServiceException ex) {
        // expected
      }
    }
    StorageObject remoteUpdatedBlob2 = updateResult.get();
    assertEquals(sourceBlob2.getBucket(), remoteUpdatedBlob2.getBucket());
    assertEquals(sourceBlob2.getName(), remoteUpdatedBlob2.getName());
    assertEquals(updatedBlob2.getContentType(), remoteUpdatedBlob2.getContentType());
  }

  @Test
  public void testBatchRequestFail() {
    String blobName = "test-batch-request-blob-fail";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    BlobMetadata updatedBlob = BlobMetadata.newBuilder(BUCKET, blobName, -1L).buildMetadata();
    StorageRpcBatch batch = storage.batch();
    StorageBatchResult<StorageObject> updateResult =
        batch.modify(updatedBlob, CloudStorageClient.BlobUploadOption.ifGenerationMatch());
    StorageBatchResult<Boolean> deleteResult1 =
        batch.remove(BUCKET, blobName, CloudStorageClient.BlobReadOption.ifGenerationMatch(-1L));
    StorageBatchResult<Boolean> deleteResult2 = batch.remove(BlobIdentifier.from(BUCKET, blobName, -1L));
    StorageBatchResult<StorageObject> getResult1 =
        batch.get(BUCKET, blobName, CloudStorageClient.BlobGetOptions.ifGenerationMatch(-1L));
    StorageBatchResult<StorageObject> getResult2 = batch.get(BlobIdentifier.from(BUCKET, blobName, -1L));
    batch.submitBatch();
    try {
      updateResult.get();
      fail("Expected StorageException");
    } catch (StorageServiceException ex) {
      // expected
    }
    try {
      deleteResult1.get();
      fail("Expected StorageException");
    } catch (StorageServiceException ex) {
      // expected
    }
    try {
      deleteResult2.get();
      fail("Expected an 'Invalid argument' exception");
    } catch (StorageServiceException e) {
      assertThat(e.getMessage()).contains("Invalid argument");
    }
    try {
      getResult1.get();
      fail("Expected StorageException");
    } catch (StorageServiceException ex) {
      // expected
    }
    try {
      getResult2.get();
      fail("Expected an 'Invalid argument' exception");
    } catch (StorageServiceException e) {
      assertThat(e.getMessage()).contains("Invalid argument");
    }
  }

  @Test
  public void testReadAndWriteChannels() throws IOException {
    String blobName = "test-read-and-write-channels-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    byte[] stringBytes;
    try (WriteChannel writer = storage.writer(blob)) {
      stringBytes = BLOB_STRING_CONTENT.getBytes(UTF_8);
      writer.write(ByteBuffer.wrap(BLOB_BYTE_CONTENT));
      writer.write(ByteBuffer.wrap(stringBytes));
    }
    ByteBuffer readBytes;
    ByteBuffer readStringBytes;
    try (ReadChannel reader = storage.reader(blob.getBlobId())) {
      readBytes = ByteBuffer.allocate(BLOB_BYTE_CONTENT.length);
      readStringBytes = ByteBuffer.allocate(stringBytes.length);
      reader.read(readBytes);
      reader.read(readStringBytes);
    }
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes.array());
    assertEquals(BLOB_STRING_CONTENT, new String(readStringBytes.array(), UTF_8));
  }

  @Test
  public void testReadAndWriteChannelWithEncryptionKey() throws IOException {
    String blobName = "test-read-write-channel-with-customer-key-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    byte[] stringBytes;
    try (WriteChannel writer =
        storage.writer(blob, CloudStorageClient.BlobWriteOptions.withEncryptionKey(BASE64_KEY))) {
      stringBytes = BLOB_STRING_CONTENT.getBytes(UTF_8);
      writer.write(ByteBuffer.wrap(BLOB_BYTE_CONTENT));
      writer.write(ByteBuffer.wrap(stringBytes));
    }
    ByteBuffer readBytes;
    ByteBuffer readStringBytes;
    try (ReadChannel reader =
        storage.reader(blob.getBlobId(), CloudStorageClient.BlobReadOption.withDecryptionKey(KEY))) {
      readBytes = ByteBuffer.allocate(BLOB_BYTE_CONTENT.length);
      readStringBytes = ByteBuffer.allocate(stringBytes.length);
      reader.read(readBytes);
      reader.read(readStringBytes);
    }
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes.array());
    assertEquals(BLOB_STRING_CONTENT, new String(readStringBytes.array(), UTF_8));
    assertTrue(storage.delete(BUCKET, blobName));
  }

  @Test
  public void testReadAndWriteChannelsWithDifferentFileSize() throws IOException {
    String blobNamePrefix = "test-read-and-write-channels-blob-";
    int[] blobSizes = {0, 700, 1024 * 256, 2 * 1024 * 1024, 4 * 1024 * 1024, 4 * 1024 * 1024 + 1};
    Random rnd = new Random();
    for (int blobSize : blobSizes) {
      String blobName = blobNamePrefix + blobSize;
      BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
      byte[] bytes = new byte[blobSize];
      rnd.nextBytes(bytes);
      try (WriteChannel writer = storage.writer(blob)) {
        writer.write(ByteBuffer.wrap(bytes));
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (ReadChannel reader = storage.reader(blob.getBlobId())) {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        while (reader.read(buffer) > 0) {
          buffer.flip();
          output.write(buffer.array(), 0, buffer.limit());
          buffer.clear();
        }
      }
      assertArrayEquals(bytes, output.toByteArray());
      assertTrue(storage.delete(BUCKET, blobName));
    }
  }

  @Test
  public void testReadAndWriteCaptureChannels() throws IOException {
    String blobName = "test-read-and-write-capture-channels-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    byte[] stringBytes;
    WriteChannel writer = storage.writer(blob);
    stringBytes = BLOB_STRING_CONTENT.getBytes(UTF_8);
    writer.write(ByteBuffer.wrap(BLOB_BYTE_CONTENT));
    RestorableState<WriteChannel> writerState = writer.capture();
    WriteChannel secondWriter = writerState.restore();
    secondWriter.write(ByteBuffer.wrap(stringBytes));
    secondWriter.close();
    ByteBuffer readBytes;
    ByteBuffer readStringBytes;
    ReadChannel reader = storage.reader(blob.getBlobId());
    reader.setChunkSize(BLOB_BYTE_CONTENT.length);
    readBytes = ByteBuffer.allocate(BLOB_BYTE_CONTENT.length);
    reader.read(readBytes);
    RestorableState<ReadChannel> readerState = reader.capture();
    ReadChannel secondReader = readerState.restore();
    readStringBytes = ByteBuffer.allocate(stringBytes.length);
    secondReader.read(readStringBytes);
    reader.close();
    secondReader.close();
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes.array());
    assertEquals(BLOB_STRING_CONTENT, new String(readStringBytes.array(), UTF_8));
    assertTrue(storage.delete(BUCKET, blobName));
  }

  @Test
  public void testReadChannelFail() throws IOException {
    String blobName = "test-read-channel-blob-fail";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertNotNull(remoteBlob);
    try (ReadChannel reader =
        storage.reader(blob.getBlobId(), CloudStorageClient.BlobReadOption.ifMetagenerationMatch(-1L))) {
      reader.read(ByteBuffer.allocate(42));
      fail("StorageException was expected");
    } catch (IOException ex) {
      // expected
    }
    try (ReadChannel reader =
        storage.reader(blob.getBlobId(), CloudStorageClient.BlobReadOption.ifGenerationMatch(-1L))) {
      reader.read(ByteBuffer.allocate(42));
      fail("StorageException was expected");
    } catch (IOException ex) {
      // expected
    }
    BlobIdentifier blobIdWrongGeneration = BlobIdentifier.from(BUCKET, blobName, -1L);
    try (ReadChannel reader =
        storage.reader(blobIdWrongGeneration, CloudStorageClient.BlobReadOption.ifGenerationMatch())) {
      reader.read(ByteBuffer.allocate(42));
      fail("StorageException was expected");
    } catch (IOException ex) {
      // expected
    }
  }

  @Test
  public void testReadChannelFailUpdatedGeneration() throws IOException {
    String blobName = "test-read-blob-fail-updated-generation";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    Random random = new Random();
    int chunkSize = 1024;
    int blobSize = 2 * chunkSize;
    byte[] content = new byte[blobSize];
    random.nextBytes(content);
    StorageObject remoteBlob = storage.create(blob, content);
    assertNotNull(remoteBlob);
    assertEquals(blobSize, (long) remoteBlob.getSize());
    try (ReadChannel reader = storage.reader(blob.getBlobId())) {
      reader.setChunkSize(chunkSize);
      ByteBuffer readBytes = ByteBuffer.allocate(chunkSize);
      int numReadBytes = reader.read(readBytes);
      assertEquals(chunkSize, numReadBytes);
      assertArrayEquals(Arrays.copyOf(content, chunkSize), readBytes.array());
      try (WriteChannel writer = storage.writer(blob)) {
        byte[] newContent = new byte[blobSize];
        random.nextBytes(newContent);
        int numWrittenBytes = writer.write(ByteBuffer.wrap(newContent));
        assertEquals(blobSize, numWrittenBytes);
      }
      readBytes = ByteBuffer.allocate(chunkSize);
      reader.read(readBytes);
      fail("StorageException was expected");
    } catch (IOException ex) {
      StringBuilder messageBuilder = new StringBuilder();
      messageBuilder.append("Blob ").append(blob.getBlobId()).append(" was updated while reading");
      assertEquals(messageBuilder.toString(), ex.getMessage());
    }
    assertTrue(storage.delete(BUCKET, blobName));
  }

  @Test
  public void testWriteChannelFail() throws IOException {
    String blobName = "test-write-channel-blob-fail";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName, -1L).buildMetadata();
    try {
      try (WriteChannel writer = storage.writer(blob, BlobWriteOptions.ifGenerationMatch())) {
        writer.write(ByteBuffer.allocate(42));
      }
      fail("StorageException was expected");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testWriteChannelExistingBlob() throws IOException {
    String blobName = "test-write-channel-existing-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    storage.create(blob);
    byte[] stringBytes;
    try (WriteChannel writer = storage.writer(blob)) {
      stringBytes = BLOB_STRING_CONTENT.getBytes(UTF_8);
      writer.write(ByteBuffer.wrap(stringBytes));
    }
    assertArrayEquals(stringBytes, storage.readAllBytes(blob.getBlobId()));
    assertTrue(storage.delete(BUCKET, blobName));
  }

  @Test(timeout = 5000)
  public void testWriteChannelWithConnectionPool() throws IOException {
    TransportOptions transportOptions =
        HttpTransportOptions.newBuilder()
            .setHttpTransportFactory(new CustomHttpTransportFactory())
            .build();
    CloudStorageClient storageWithPool =
        StorageClientOptions.newServiceClientBuilder().setTransportOptions(transportOptions).build().getService();
    String blobName = "test-custom-pool-management";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    byte[] stringBytes;
    try (WriteChannel writer = storageWithPool.writer(blob)) {
      stringBytes = BLOB_STRING_CONTENT.getBytes(UTF_8);
      writer.write(ByteBuffer.wrap(BLOB_BYTE_CONTENT));
      writer.write(ByteBuffer.wrap(stringBytes));
    }
    try (WriteChannel writer = storageWithPool.writer(blob)) {
      stringBytes = BLOB_STRING_CONTENT.getBytes(UTF_8);
      writer.write(ByteBuffer.wrap(BLOB_BYTE_CONTENT));
      writer.write(ByteBuffer.wrap(stringBytes));
    }
  }

  @Test
  public void testGetSignedUrl() throws IOException {
    if (storage.getOptions().getCredentials() != null) {
      assumeTrue(storage.getOptions().getCredentials() instanceof ServiceAccountSigner);
    }
    String blobName = "test-get-signed-url-blob/with/slashes/and?special=!#$&'()*+,:;=?@[]";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob);
    for (CloudStorageClient.UrlSigningOption urlStyle :
        Arrays.asList(
            CloudStorageClient.UrlSigningOption.usePathStyle(),
            CloudStorageClient.UrlSigningOption.useVirtualHostedStyle())) {
      URL url = storage.signUrl(blob, 1, TimeUnit.HOURS, urlStyle);
      URLConnection connection = url.openConnection();
      byte[] readBytes = new byte[BLOB_BYTE_CONTENT.length];
      try (InputStream responseStream = connection.getInputStream()) {
        assertEquals(BLOB_BYTE_CONTENT.length, responseStream.read(readBytes));
        assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
      }
    }
  }

  @Test
  public void testGetV2SignedUrlWithAddlQueryParam() throws IOException {
    if (storage.getOptions().getCredentials() != null) {
      assumeTrue(storage.getOptions().getCredentials() instanceof ServiceAccountSigner);
    }
    String blobName = "test-get-v2-with-generation-param";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob);
    for (CloudStorageClient.UrlSigningOption urlStyle :
        Arrays.asList(
            CloudStorageClient.UrlSigningOption.usePathStyle(),
            CloudStorageClient.UrlSigningOption.useVirtualHostedStyle())) {
      String generationStr = remoteBlob.getGeneration().toString();
      URL url =
          storage.signUrl(
              blob,
              1,
              TimeUnit.HOURS,
              urlStyle,
              CloudStorageClient.UrlSigningOption.useV2Signature(),
              CloudStorageClient.UrlSigningOption.includeQueryParams(
                  ImmutableMap.<String, String>of("generation", generationStr)));
      // Finally, verify that the URL works and we can get the object as expected:
      URLConnection connection = url.openConnection();
      byte[] readBytes = new byte[BLOB_BYTE_CONTENT.length];
      try (InputStream responseStream = connection.getInputStream()) {
        assertEquals(BLOB_BYTE_CONTENT.length, responseStream.read(readBytes));
        assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
      }
    }
  }

  // TODO(b/144304815): Remove this test once all conformance tests contain query param test cases.
  @Test
  public void testGetV4SignedUrlWithAddlQueryParam() throws IOException {
    if (storage.getOptions().getCredentials() != null) {
      assumeTrue(storage.getOptions().getCredentials() instanceof ServiceAccountSigner);
    }
    String blobName = "test-get-v4-with-generation-param";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob);
    for (CloudStorageClient.UrlSigningOption urlStyle :
        Arrays.asList(
            CloudStorageClient.UrlSigningOption.usePathStyle(),
            CloudStorageClient.UrlSigningOption.useVirtualHostedStyle())) {
      String generationStr = remoteBlob.getGeneration().toString();
      URL url =
          storage.signUrl(
              blob,
              1,
              TimeUnit.HOURS,
              urlStyle,
              CloudStorageClient.UrlSigningOption.useV4Signature(),
              CloudStorageClient.UrlSigningOption.includeQueryParams(
                  ImmutableMap.<String, String>of("generation", generationStr)));
      // Finally, verify that the URL works and we can get the object as expected:
      URLConnection connection = url.openConnection();
      byte[] readBytes = new byte[BLOB_BYTE_CONTENT.length];
      try (InputStream responseStream = connection.getInputStream()) {
        assertEquals(BLOB_BYTE_CONTENT.length, responseStream.read(readBytes));
        assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
      }
    }
  }

  @Test
  public void testPostSignedUrl() throws IOException {
    if (storage.getOptions().getCredentials() != null) {
      assumeTrue(storage.getOptions().getCredentials() instanceof ServiceAccountSigner);
    }
    String blobName = "test-post-signed-url-blob";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    assertNotNull(storage.create(blob));
    for (CloudStorageClient.UrlSigningOption urlStyle :
        Arrays.asList(
            CloudStorageClient.UrlSigningOption.usePathStyle(),
            CloudStorageClient.UrlSigningOption.useVirtualHostedStyle())) {

      URL url =
          storage.signUrl(
              blob, 1, TimeUnit.HOURS, CloudStorageClient.UrlSigningOption.withHttpMethod(HttpRequestMethod.POST), urlStyle);
      URLConnection connection = url.openConnection();
      connection.setDoOutput(true);
      connection.connect();
      StorageObject remoteBlob = storage.get(BUCKET, blobName);
      assertNotNull(remoteBlob);
      assertEquals(blob.getBucket(), remoteBlob.getBucket());
      assertEquals(blob.getName(), remoteBlob.getName());
    }
  }

  @Test
  public void testV4SignedUrl() throws IOException {
    if (storage.getOptions().getCredentials() != null) {
      assumeTrue(storage.getOptions().getCredentials() instanceof ServiceAccountSigner);
    }

    String blobName = "test-get-signed-url-blob/with/slashes/and?special=!#$&'()*+,:;=?@[]";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blob, BLOB_BYTE_CONTENT);
    assertNotNull(remoteBlob);
    for (CloudStorageClient.UrlSigningOption urlStyle :
        Arrays.asList(
            CloudStorageClient.UrlSigningOption.usePathStyle(),
            CloudStorageClient.UrlSigningOption.useVirtualHostedStyle())) {

      URL url =
          storage.signUrl(
              blob, 1, TimeUnit.HOURS, CloudStorageClient.UrlSigningOption.useV4Signature(), urlStyle);
      URLConnection connection = url.openConnection();
      byte[] readBytes = new byte[BLOB_BYTE_CONTENT.length];
      try (InputStream responseStream = connection.getInputStream()) {
        assertEquals(BLOB_BYTE_CONTENT.length, responseStream.read(readBytes));
        assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
      }
    }
  }

  @Test
  public void testGetBlobs() {
    String sourceBlobName1 = "test-get-blobs-1";
    String sourceBlobName2 = "test-get-blobs-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    assertNotNull(storage.create(sourceBlob1));
    assertNotNull(storage.create(sourceBlob2));
    List<StorageObject> remoteBlobs = storage.get(sourceBlob1.getBlobId(), sourceBlob2.getBlobId());
    assertEquals(sourceBlob1.getBucket(), remoteBlobs.get(0).getBucket());
    assertEquals(sourceBlob1.getName(), remoteBlobs.get(0).getName());
    assertEquals(sourceBlob2.getBucket(), remoteBlobs.get(1).getBucket());
    assertEquals(sourceBlob2.getName(), remoteBlobs.get(1).getName());
  }

  @Test
  public void testDownloadPublicBlobWithoutAuthentication() {
    assumeFalse(IS_VPC_TEST);
    // create an unauthorized user
    CloudStorageClient unauthorizedStorage = StorageClientOptions.getUnauthenticatedInstance().getService();

    // try to download blobs from a public bucket
    String landsatBucket = "gcp-public-data-landsat";
    String landsatPrefix = "LC08/PRE/044/034/LC80440342016259LGN00/";
    String landsatBlob = landsatPrefix + "LC80440342016259LGN00_MTL.txt";
    byte[] bytes = unauthorizedStorage.readAllBytes(landsatBucket, landsatBlob);

    assertThat(bytes.length).isEqualTo(7903);
    int numBlobs = 0;
    Iterator<StorageObject> blobIterator =
        unauthorizedStorage
            .list(landsatBucket, CloudStorageClient.BlobListOptions.withPrefix(landsatPrefix))
            .iterateAll()
            .iterator();
    while (blobIterator.hasNext()) {
      numBlobs++;
      blobIterator.next();
    }
    assertThat(numBlobs).isEqualTo(13);

    // try to download blobs from a bucket that requires authentication
    // authenticated client will succeed
    // unauthenticated client will receive an exception
    String sourceBlobName = "source-blob-name";
    BlobMetadata sourceBlob = BlobMetadata.newBuilder(BUCKET, sourceBlobName).buildMetadata();
    assertThat(storage.create(sourceBlob)).isNotNull();
    assertThat(storage.readAllBytes(BUCKET, sourceBlobName)).isNotNull();
    try {
      unauthorizedStorage.readAllBytes(BUCKET, sourceBlobName);
      fail("Expected StorageException");
    } catch (StorageServiceException ex) {
      // expected
    }
    assertThat(storage.get(sourceBlob.getBlobId()).deleteFromStorage()).isTrue();

    // try to upload blobs to a bucket that requires authentication
    // authenticated client will succeed
    // unauthenticated client will receive an exception
    assertThat(storage.create(sourceBlob)).isNotNull();
    try {
      unauthorizedStorage.create(sourceBlob);
      fail("Expected StorageException");
    } catch (StorageServiceException ex) {
      // expected
    }
    assertThat(storage.get(sourceBlob.getBlobId()).deleteFromStorage()).isTrue();
  }

  @Test
  public void testGetBlobsFail() {
    String sourceBlobName1 = "test-get-blobs-fail-1";
    String sourceBlobName2 = "test-get-blobs-fail-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    assertNotNull(storage.create(sourceBlob1));
    List<StorageObject> remoteBlobs = storage.get(sourceBlob1.getBlobId(), sourceBlob2.getBlobId());
    assertEquals(sourceBlob1.getBucket(), remoteBlobs.get(0).getBucket());
    assertEquals(sourceBlob1.getName(), remoteBlobs.get(0).getName());
    assertNull(remoteBlobs.get(1));
  }

  @Test
  public void testDeleteBlobs() {
    String sourceBlobName1 = "test-delete-blobs-1";
    String sourceBlobName2 = "test-delete-blobs-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    assertNotNull(storage.create(sourceBlob1));
    assertNotNull(storage.create(sourceBlob2));
    List<Boolean> deleteStatus = storage.delete(sourceBlob1.getBlobId(), sourceBlob2.getBlobId());
    assertTrue(deleteStatus.get(0));
    assertTrue(deleteStatus.get(1));
  }

  @Test
  public void testDeleteBlobsFail() {
    String sourceBlobName1 = "test-delete-blobs-fail-1";
    String sourceBlobName2 = "test-delete-blobs-fail-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    assertNotNull(storage.create(sourceBlob1));
    List<Boolean> deleteStatus = storage.delete(sourceBlob1.getBlobId(), sourceBlob2.getBlobId());
    assertTrue(deleteStatus.get(0));
    assertFalse(deleteStatus.get(1));
  }

  @Test
  public void testUpdateBlobs() {
    String sourceBlobName1 = "test-update-blobs-1";
    String sourceBlobName2 = "test-update-blobs-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    StorageObject remoteBlob1 = storage.create(sourceBlob1);
    StorageObject remoteBlob2 = storage.create(sourceBlob2);
    assertNotNull(remoteBlob1);
    assertNotNull(remoteBlob2);
    List<StorageObject> updatedBlobs =
        storage.update(
            remoteBlob1.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata(),
            remoteBlob2.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata());
    assertEquals(sourceBlob1.getBucket(), updatedBlobs.get(0).getBucket());
    assertEquals(sourceBlob1.getName(), updatedBlobs.get(0).getName());
    assertEquals(CONTENT_TYPE, updatedBlobs.get(0).getContentType());
    assertEquals(sourceBlob2.getBucket(), updatedBlobs.get(1).getBucket());
    assertEquals(sourceBlob2.getName(), updatedBlobs.get(1).getName());
    assertEquals(CONTENT_TYPE, updatedBlobs.get(1).getContentType());
  }

  @Test
  public void testUpdateBlobsFail() {
    String sourceBlobName1 = "test-update-blobs-fail-1";
    String sourceBlobName2 = "test-update-blobs-fail-2";
    BlobMetadata sourceBlob1 = BlobMetadata.newBuilder(BUCKET, sourceBlobName1).buildMetadata();
    BlobMetadata sourceBlob2 = BlobMetadata.newBuilder(BUCKET, sourceBlobName2).buildMetadata();
    BlobMetadata remoteBlob1 = storage.create(sourceBlob1);
    assertNotNull(remoteBlob1);
    List<StorageObject> updatedBlobs =
        storage.update(
            remoteBlob1.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata(),
            sourceBlob2.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata());
    assertEquals(sourceBlob1.getBucket(), updatedBlobs.get(0).getBucket());
    assertEquals(sourceBlob1.getName(), updatedBlobs.get(0).getName());
    assertEquals(CONTENT_TYPE, updatedBlobs.get(0).getContentType());
    assertNull(updatedBlobs.get(1));
  }

  @Test
  public void testBucketAcl() {
    unsetRequesterPays();
    testBucketAclRequesterPays(true);
    testBucketAclRequesterPays(false);
  }

  private void testBucketAclRequesterPays(boolean requesterPays) {
    if (requesterPays) {
      StorageBucket remoteBucket =
          storage.get(
              BUCKET_REQUESTER_PAYS,
              CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.ID, BucketMetadataField.BILLING));
      assertTrue(remoteBucket.isRequesterPays() == null || !remoteBucket.isRequesterPays());
      remoteBucket = remoteBucket.toBucketBuilder().setRequesterPays(true).buildBucketInfo();
      StorageBucket updatedBucket = storage.update(remoteBucket);
      assertTrue(updatedBucket.isRequesterPays());
    }

    String projectId = remoteStorageHelper.getOptions().getProjectId();

    CloudStorageClient.BucketOption[] bucketOptions =
        requesterPays
            ? new CloudStorageClient.BucketOption[] {CloudStorageClient.BucketOption.userProjectId(projectId)}
            : new CloudStorageClient.BucketOption[] {};

    assertNull(
        storage.getAcl(BUCKET_REQUESTER_PAYS, UserIdentity.allAuthenticatedUsers(), bucketOptions));
    assertFalse(
        storage.deleteAcl(BUCKET_REQUESTER_PAYS, UserIdentity.allAuthenticatedUsers(), bucketOptions));
    AccessControlEntry acl = AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), UserRole.READER);
    assertNotNull(storage.createAcl(BUCKET_REQUESTER_PAYS, acl, bucketOptions));
    AccessControlEntry updatedAcl =
        storage.updateAcl(
            BUCKET_REQUESTER_PAYS, acl.toEntityBuilder().setRole(UserRole.WRITER).buildEntry(), bucketOptions);
    assertEquals(UserRole.WRITER, updatedAcl.getRole());
    Set<AccessControlEntry> acls = new HashSet<>();
    acls.addAll(storage.listAcls(BUCKET_REQUESTER_PAYS, bucketOptions));
    assertTrue(acls.contains(updatedAcl));
    assertTrue(
        storage.deleteAcl(BUCKET_REQUESTER_PAYS, AccessControlEntry.UserIdentity.allAuthenticatedUsers(), bucketOptions));
    assertNull(
        storage.getAcl(BUCKET_REQUESTER_PAYS, UserIdentity.allAuthenticatedUsers(), bucketOptions));
    if (requesterPays) {
      StorageBucket remoteBucket =
          storage.get(
              BUCKET_REQUESTER_PAYS,
              CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.ID, BucketMetadataField.BILLING),
              CloudStorageClient.BucketGetOptions.userProjectId(projectId));
      assertTrue(remoteBucket.isRequesterPays());
      remoteBucket = remoteBucket.toBucketBuilder().setRequesterPays(false).buildBucketInfo();
      StorageBucket updatedBucket =
          storage.update(remoteBucket, CloudStorageClient.BucketTargetOptions.userProjectId(projectId));
      assertFalse(updatedBucket.isRequesterPays());
    }
  }

  @Test
  public void testBucketDefaultAcl() {
    assertNull(storage.getDefaultAcl(BUCKET, UserIdentity.allAuthenticatedUsers()));
    assertFalse(storage.deleteDefaultAcl(BUCKET, UserIdentity.allAuthenticatedUsers()));
    AccessControlEntry acl = AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), UserRole.READER);
    assertNotNull(storage.createDefaultAcl(BUCKET, acl));
    AccessControlEntry updatedAcl = storage.updateDefaultAcl(BUCKET, acl.toEntityBuilder().setRole(UserRole.OWNER).buildEntry());
    assertEquals(UserRole.OWNER, updatedAcl.getRole());
    Set<AccessControlEntry> acls = new HashSet<>();
    acls.addAll(storage.listDefaultAcls(BUCKET));
    assertTrue(acls.contains(updatedAcl));
    assertTrue(storage.deleteDefaultAcl(BUCKET, UserIdentity.allAuthenticatedUsers()));
    assertNull(storage.getDefaultAcl(BUCKET, UserIdentity.allAuthenticatedUsers()));
  }

  @Test
  public void testBlobAcl() {
    BlobIdentifier blobId = BlobIdentifier.from(BUCKET, "test-blob-acl");
    BlobMetadata blob = BlobMetadata.newBuilder(blobId).buildMetadata();
    storage.create(blob);
    assertNull(storage.getAcl(blobId, UserIdentity.allAuthenticatedUsers()));
    AccessControlEntry acl = AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), UserRole.READER);
    assertNotNull(storage.createAcl(blobId, acl));
    AccessControlEntry updatedAcl = storage.updateAcl(blobId, acl.toEntityBuilder().setRole(AccessControlEntry.UserRole.OWNER).buildEntry());
    assertEquals(UserRole.OWNER, updatedAcl.getRole());
    Set<AccessControlEntry> acls = new HashSet<>(storage.listAcls(blobId));
    assertTrue(acls.contains(updatedAcl));
    assertTrue(storage.deleteAcl(blobId, UserIdentity.allAuthenticatedUsers()));
    assertNull(storage.getAcl(blobId, UserIdentity.allAuthenticatedUsers()));
    // test non-existing blob
    BlobIdentifier otherBlobId = BlobIdentifier.from(BUCKET, "test-blob-acl", -1L);
    try {
      assertNull(storage.getAcl(otherBlobId, UserIdentity.allAuthenticatedUsers()));
      fail("Expected an 'Invalid argument' exception");
    } catch (StorageServiceException e) {
      assertThat(e.getMessage()).contains("Invalid argument");
    }

    try {
      assertFalse(storage.deleteAcl(otherBlobId, UserIdentity.allAuthenticatedUsers()));
      fail("Expected an 'Invalid argument' exception");
    } catch (StorageServiceException e) {
      assertThat(e.getMessage()).contains("Invalid argument");
    }

    try {
      storage.createAcl(otherBlobId, acl);
      fail("Expected StorageException");
    } catch (StorageServiceException ex) {
      // expected
    }
    try {
      storage.updateAcl(otherBlobId, acl);
      fail("Expected StorageException");
    } catch (StorageServiceException ex) {
      // expected
    }
    try {
      storage.listAcls(otherBlobId);
      fail("Expected StorageException");
    } catch (StorageServiceException ex) {
      // expected
    }
  }

  @Test
  public void testHmacKey() {
    ServiceAccountInfo serviceAccount = ServiceAccountInfo.from(System.getenv("IT_SERVICE_ACCOUNT_EMAIL"));
    try {

      HmacSecretKey hmacKey = storage.createHmacKey(serviceAccount);
      String secretKey = hmacKey.getSecretKey();
      assertNotNull(secretKey);
      HmacSecretKey.HmacKeyInfo metadata = hmacKey.getMetadata();
      String accessId = metadata.getAccessId();

      assertNotNull(accessId);
      assertNotNull(metadata.getEtag());
      assertNotNull(metadata.getId());
      assertEquals(remoteStorageHelper.getOptions().getProjectId(), metadata.getProjectId());
      assertEquals(serviceAccount.getEmail(), metadata.getServiceAccount().getEmail());
      assertEquals(HmacSecretKey.HmacKeyStatus.ACTIVE, metadata.getState());
      assertNotNull(metadata.getCreateTime());
      assertNotNull(metadata.getUpdateTime());

      Page<HmacSecretKey.HmacKeyInfo> metadatas =
          storage.listHmacKeys(CloudStorageClient.ListHmacKeysOptions.serviceAccountEmail(serviceAccount));
      boolean createdHmacKeyIsInList = false;
      for (HmacSecretKey.HmacKeyInfo hmacKeyMetadata : metadatas.iterateAll()) {
        if (accessId.equals(hmacKeyMetadata.getAccessId())) {
          createdHmacKeyIsInList = true;
          break;
        }
      }

      if (!createdHmacKeyIsInList) {
        fail("Created an HMAC key but it didn't show up in list()");
      }

      HmacSecretKey.HmacKeyInfo getResult = storage.getHmacKey(accessId);
      assertEquals(metadata, getResult);

      storage.updateHmacKeyState(metadata, HmacSecretKey.HmacKeyStatus.INACTIVE);

      storage.deleteHmacKey(metadata);

      metadatas = storage.listHmacKeys(CloudStorageClient.ListHmacKeysOptions.serviceAccountEmail(serviceAccount));
      createdHmacKeyIsInList = false;
      for (HmacSecretKey.HmacKeyInfo hmacKeyMetadata : metadatas.iterateAll()) {
        if (accessId.equals(hmacKeyMetadata.getAccessId())) {
          createdHmacKeyIsInList = true;
          break;
        }
      }

      if (createdHmacKeyIsInList) {
        fail("Deleted an HMAC key but it showed up in list()");
      }

      storage.createHmacKey(serviceAccount);
      storage.createHmacKey(serviceAccount);
      storage.createHmacKey(serviceAccount);
      storage.createHmacKey(serviceAccount);

      metadatas =
          storage.listHmacKeys(
              CloudStorageClient.ListHmacKeysOptions.serviceAccountEmail(serviceAccount),
              CloudStorageClient.ListHmacKeysOptions.maxItems(2L));

      String nextPageToken = metadatas.getNextPageToken();

      assertEquals(2, Iterators.size(metadatas.getValues().iterator()));

      metadatas =
          storage.listHmacKeys(
              CloudStorageClient.ListHmacKeysOptions.serviceAccountEmail(serviceAccount),
              CloudStorageClient.ListHmacKeysOptions.maxItems(2L),
              CloudStorageClient.ListHmacKeysOptions.pageCursor(nextPageToken));

      assertEquals(2, Iterators.size(metadatas.getValues().iterator()));
    } finally {
      Page<HmacSecretKey.HmacKeyInfo> metadatas =
          storage.listHmacKeys(CloudStorageClient.ListHmacKeysOptions.serviceAccountEmail(serviceAccount));
      for (HmacSecretKey.HmacKeyInfo hmacKeyMetadata : metadatas.iterateAll()) {
        storage.updateHmacKeyState(hmacKeyMetadata, HmacSecretKey.HmacKeyStatus.INACTIVE);
        storage.deleteHmacKey(hmacKeyMetadata);
      }
    }
  }

  @Test
  public void testReadCompressedBlob() throws IOException {
    String blobName = "test-read-compressed-blob";
    BlobMetadata blobInfo =
        BlobMetadata.newBuilder(BlobIdentifier.from(BUCKET, blobName))
            .setContentType("text/plain")
            .setContentEncoding("gzip")
            .buildMetadata();
    StorageObject blob = storage.create(blobInfo, COMPRESSED_CONTENT);
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      try (ReadChannel reader = storage.reader(BlobIdentifier.from(BUCKET, blobName))) {
        reader.setChunkSize(8);
        ByteBuffer buffer = ByteBuffer.allocate(8);
        while (reader.read(buffer) != -1) {
          buffer.flip();
          output.write(buffer.array(), 0, buffer.limit());
          buffer.clear();
        }
      }
      assertArrayEquals(
          BLOB_STRING_CONTENT.getBytes(UTF_8), storage.readAllBytes(BUCKET, blobName));
      assertArrayEquals(COMPRESSED_CONTENT, output.toByteArray());
      try (GZIPInputStream zipInput =
          new GZIPInputStream(new ByteArrayInputStream(output.toByteArray()))) {
        assertArrayEquals(BLOB_STRING_CONTENT.getBytes(UTF_8), ByteStreams.toByteArray(zipInput));
      }
    }
  }

  @Test
  public void testBucketPolicyV1RequesterPays() throws ExecutionException, InterruptedException {
    unsetRequesterPays();
    StorageBucket bucketDefault =
        storage.get(
            BUCKET_REQUESTER_PAYS,
            CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.ID, BucketMetadataField.BILLING));
    assertTrue(bucketDefault.isRequesterPays() == null || !bucketDefault.isRequesterPays());

    StorageBucket bucketTrue = storage.update(bucketDefault.toBucketBuilder().setRequesterPays(true).buildBucketInfo());
    assertTrue(bucketTrue.isRequesterPays());

    String projectId = remoteStorageHelper.getOptions().getProjectId();

    CloudStorageClient.BucketOption[] bucketOptions =
        new CloudStorageClient.BucketOption[] {CloudStorageClient.BucketOption.userProjectId(projectId)};
    Identity projectOwner = Identity.projectOwner(projectId);
    Identity projectEditor = Identity.projectEditor(projectId);
    Identity projectViewer = Identity.projectViewer(projectId);
    Map<com.google.cloud.Role, Set<Identity>> bindingsWithoutPublicRead =
        ImmutableMap.of(
            StorageRoles.legacyBucketOwner(),
            new HashSet<>(Arrays.asList(projectOwner, projectEditor)),
            StorageRoles.legacyBucketReader(),
            (Set<Identity>) new HashSet<>(Collections.singleton(projectViewer)));
    Map<com.google.cloud.Role, Set<Identity>> bindingsWithPublicRead =
        ImmutableMap.of(
            StorageRoles.legacyBucketOwner(),
            new HashSet<>(Arrays.asList(projectOwner, projectEditor)),
            StorageRoles.legacyBucketReader(),
            new HashSet<>(Collections.singleton(projectViewer)),
            StorageRoles.legacyObjectReader(),
            (Set<Identity>) new HashSet<>(Collections.singleton(Identity.allUsers())));

    // Validate getting policy.
    Policy currentPolicy = storage.getIamPolicy(BUCKET_REQUESTER_PAYS, bucketOptions);
    assertEquals(bindingsWithoutPublicRead, currentPolicy.getBindings());

    // Validate updating policy.
    Policy updatedPolicy =
        storage.setIamPolicy(
            BUCKET_REQUESTER_PAYS,
            currentPolicy
                .toBuilder()
                .addIdentity(StorageRoles.legacyObjectReader(), Identity.allUsers())
                .build(),
            bucketOptions);
    assertEquals(bindingsWithPublicRead, updatedPolicy.getBindings());
    Policy revertedPolicy =
        storage.setIamPolicy(
            BUCKET_REQUESTER_PAYS,
            updatedPolicy
                .toBuilder()
                .removeIdentity(StorageRoles.legacyObjectReader(), Identity.allUsers())
                .build(),
            bucketOptions);
    assertEquals(bindingsWithoutPublicRead, revertedPolicy.getBindings());

    // Validate testing permissions.
    List<Boolean> expectedPermissions = ImmutableList.of(true, true);
    assertEquals(
        expectedPermissions,
        storage.testIamPermissions(
            BUCKET_REQUESTER_PAYS,
            ImmutableList.of("storage.buckets.getIamPolicy", "storage.buckets.setIamPolicy"),
            bucketOptions));
    StorageBucket bucketFalse =
        storage.update(
            bucketTrue.toBucketBuilder().setRequesterPays(false).buildBucketInfo(),
            CloudStorageClient.BucketTargetOptions.userProjectId(projectId));
    assertFalse(bucketFalse.isRequesterPays());
  }

  @Test
  public void testBucketPolicyV1() {
    String projectId = remoteStorageHelper.getOptions().getProjectId();

    CloudStorageClient.BucketOption[] bucketOptions = new CloudStorageClient.BucketOption[] {};
    Identity projectOwner = Identity.projectOwner(projectId);
    Identity projectEditor = Identity.projectEditor(projectId);
    Identity projectViewer = Identity.projectViewer(projectId);
    Map<com.google.cloud.Role, Set<Identity>> bindingsWithoutPublicRead =
        ImmutableMap.of(
            StorageRoles.legacyBucketOwner(),
            new HashSet<>(Arrays.asList(projectOwner, projectEditor)),
            StorageRoles.legacyBucketReader(),
            (Set<Identity>) new HashSet<>(Collections.singleton(projectViewer)));
    Map<com.google.cloud.Role, Set<Identity>> bindingsWithPublicRead =
        ImmutableMap.of(
            StorageRoles.legacyBucketOwner(),
            new HashSet<>(Arrays.asList(projectOwner, projectEditor)),
            StorageRoles.legacyBucketReader(),
            new HashSet<>(Collections.singleton(projectViewer)),
            StorageRoles.legacyObjectReader(),
            (Set<Identity>) new HashSet<>(Collections.singleton(Identity.allUsers())));

    // Validate getting policy.
    Policy currentPolicy = storage.getIamPolicy(BUCKET, bucketOptions);
    assertEquals(bindingsWithoutPublicRead, currentPolicy.getBindings());

    // Validate updating policy.
    Policy updatedPolicy =
        storage.setIamPolicy(
            BUCKET,
            currentPolicy
                .toBuilder()
                .addIdentity(StorageRoles.legacyObjectReader(), Identity.allUsers())
                .build(),
            bucketOptions);
    assertEquals(bindingsWithPublicRead, updatedPolicy.getBindings());
    Policy revertedPolicy =
        storage.setIamPolicy(
            BUCKET,
            updatedPolicy
                .toBuilder()
                .removeIdentity(StorageRoles.legacyObjectReader(), Identity.allUsers())
                .build(),
            bucketOptions);
    assertEquals(bindingsWithoutPublicRead, revertedPolicy.getBindings());

    // Validate testing permissions.
    List<Boolean> expectedPermissions = ImmutableList.of(true, true);
    assertEquals(
        expectedPermissions,
        storage.testIamPermissions(
            BUCKET,
            ImmutableList.of("storage.buckets.getIamPolicy", "storage.buckets.setIamPolicy"),
            bucketOptions));
  }

  @Test
  public void testBucketPolicyV3() {
    // Enable Uniform Bucket-Level Access
    storage.update(
        BucketInfo.newBucketBuilder(BUCKET)
            .setIamConfiguration(
                BucketInfo.BucketIamConfiguration.newUniformBucketLevelAccessBuilder()
                    .setIsUniformBucketLevelAccessEnabled(true)
                    .buildBucketIamConfiguration())
            .buildBucketInfo());
    String projectId = remoteStorageHelper.getOptions().getProjectId();

    CloudStorageClient.BucketOption[] bucketOptions =
        new CloudStorageClient.BucketOption[] {CloudStorageClient.BucketOption.policyVersionRequested(3)};
    Identity projectOwner = Identity.projectOwner(projectId);
    Identity projectEditor = Identity.projectEditor(projectId);
    Identity projectViewer = Identity.projectViewer(projectId);
    List<com.google.cloud.Binding> bindingsWithoutPublicRead =
        ImmutableList.of(
            com.google.cloud.Binding.newBuilder()
                .setRole(StorageRoles.legacyBucketOwner().toString())
                .setMembers(ImmutableList.of(projectEditor.strValue(), projectOwner.strValue()))
                .build(),
            com.google.cloud.Binding.newBuilder()
                .setRole(StorageRoles.legacyBucketReader().toString())
                .setMembers(ImmutableList.of(projectViewer.strValue()))
                .build());
    List<com.google.cloud.Binding> bindingsWithPublicRead =
        ImmutableList.of(
            com.google.cloud.Binding.newBuilder()
                .setRole(StorageRoles.legacyBucketReader().toString())
                .setMembers(ImmutableList.of(projectViewer.strValue()))
                .build(),
            com.google.cloud.Binding.newBuilder()
                .setRole(StorageRoles.legacyBucketOwner().toString())
                .setMembers(ImmutableList.of(projectEditor.strValue(), projectOwner.strValue()))
                .build(),
            com.google.cloud.Binding.newBuilder()
                .setRole(StorageRoles.legacyObjectReader().toString())
                .setMembers(ImmutableList.of("allUsers"))
                .build());

    List<com.google.cloud.Binding> bindingsWithConditionalPolicy =
        ImmutableList.of(
            com.google.cloud.Binding.newBuilder()
                .setRole(StorageRoles.legacyBucketReader().toString())
                .setMembers(ImmutableList.of(projectViewer.strValue()))
                .build(),
            com.google.cloud.Binding.newBuilder()
                .setRole(StorageRoles.legacyBucketOwner().toString())
                .setMembers(ImmutableList.of(projectEditor.strValue(), projectOwner.strValue()))
                .build(),
            com.google.cloud.Binding.newBuilder()
                .setRole(StorageRoles.legacyObjectReader().toString())
                .setMembers(
                    ImmutableList.of(
                        "serviceAccount:storage-python@spec-test-ruby-samples.iam.gserviceaccount.com"))
                .setCondition(
                    Condition.newBuilder()
                        .setTitle("Title")
                        .setDescription("Description")
                        .setExpression(
                            "resource.name.startsWith(\"projects/_/buckets/bucket-name/objects/prefix-a-\")")
                        .build())
                .build());

    // Validate getting policy.
    Policy currentPolicy = storage.getIamPolicy(BUCKET, bucketOptions);
    assertEquals(bindingsWithoutPublicRead, currentPolicy.getBindingsList());

    // Validate updating policy.
    List<com.google.cloud.Binding> currentBindings = new ArrayList(currentPolicy.getBindingsList());
    currentBindings.add(
        com.google.cloud.Binding.newBuilder()
            .setRole(StorageRoles.legacyObjectReader().getValue())
            .addMembers(Identity.allUsers().strValue())
            .build());
    Policy updatedPolicy =
        storage.setIamPolicy(
            BUCKET, currentPolicy.toBuilder().setBindings(currentBindings).build(), bucketOptions);
    assertTrue(
        bindingsWithPublicRead.size() == updatedPolicy.getBindingsList().size()
            && bindingsWithPublicRead.containsAll(updatedPolicy.getBindingsList()));

    // Remove a member
    List<com.google.cloud.Binding> updatedBindings = new ArrayList(updatedPolicy.getBindingsList());
    for (int i = 0; i < updatedBindings.size(); i++) {
      com.google.cloud.Binding binding = updatedBindings.get(i);
      if (binding.getRole().equals(StorageRoles.legacyObjectReader().toString())) {
        List<String> members = new ArrayList(binding.getMembers());
        members.remove(Identity.allUsers().strValue());
        updatedBindings.set(i, binding.toBuilder().setMembers(members).build());
        break;
      }
    }

    Policy revertedPolicy =
        storage.setIamPolicy(
            BUCKET, updatedPolicy.toBuilder().setBindings(updatedBindings).build(), bucketOptions);

    assertEquals(bindingsWithoutPublicRead, revertedPolicy.getBindingsList());
    assertTrue(
        bindingsWithoutPublicRead.size() == revertedPolicy.getBindingsList().size()
            && bindingsWithoutPublicRead.containsAll(revertedPolicy.getBindingsList()));

    // Add Conditional Policy
    List<com.google.cloud.Binding> conditionalBindings =
        new ArrayList(revertedPolicy.getBindingsList());
    conditionalBindings.add(
        com.google.cloud.Binding.newBuilder()
            .setRole(StorageRoles.legacyObjectReader().toString())
            .addMembers(
                "serviceAccount:storage-python@spec-test-ruby-samples.iam.gserviceaccount.com")
            .setCondition(
                Condition.newBuilder()
                    .setTitle("Title")
                    .setDescription("Description")
                    .setExpression(
                        "resource.name.startsWith(\"projects/_/buckets/bucket-name/objects/prefix-a-\")")
                    .build())
            .build());
    Policy conditionalPolicy =
        storage.setIamPolicy(
            BUCKET,
            revertedPolicy.toBuilder().setBindings(conditionalBindings).setVersion(3).build(),
            bucketOptions);
    assertTrue(
        bindingsWithConditionalPolicy.size() == conditionalPolicy.getBindingsList().size()
            && bindingsWithConditionalPolicy.containsAll(conditionalPolicy.getBindingsList()));

    // Remove Conditional Policy
    conditionalPolicy =
        storage.setIamPolicy(
            BUCKET,
            conditionalPolicy.toBuilder().setBindings(updatedBindings).setVersion(3).build(),
            bucketOptions);

    // Validate testing permissions.
    List<Boolean> expectedPermissions = ImmutableList.of(true, true);
    assertEquals(
        expectedPermissions,
        storage.testIamPermissions(
            BUCKET,
            ImmutableList.of("storage.buckets.getIamPolicy", "storage.buckets.setIamPolicy"),
            bucketOptions));

    // Disable Uniform Bucket-Level Access
    storage.update(
        BucketInfo.newBucketBuilder(BUCKET)
            .setIamConfiguration(
                BucketInfo.BucketIamConfiguration.newUniformBucketLevelAccessBuilder()
                    .setIsUniformBucketLevelAccessEnabled(false)
                    .buildBucketIamConfiguration())
            .buildBucketInfo());
  }

  @Test
  public void testUpdateBucketLabel() {
    StorageBucket remoteBucket =
        storage.get(BUCKET, CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.ID, BucketMetadataField.BILLING));
    assertNull(remoteBucket.getLabels());
    remoteBucket = remoteBucket.toBucketBuilder().setLabels(BUCKET_LABELS).buildBucketInfo();
    StorageBucket updatedBucket = storage.update(remoteBucket);
    assertEquals(BUCKET_LABELS, updatedBucket.getLabels());
    remoteBucket.toBucketBuilder().setLabels(REMOVE_BUCKET_LABELS).buildBucketInfo().modify();
    assertNull(storage.get(BUCKET).getLabels());
  }

  @Test
  public void testUpdateBucketRequesterPays() {
    unsetRequesterPays();
    StorageBucket remoteBucket =
        storage.get(
            BUCKET_REQUESTER_PAYS,
            CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.ID, BucketMetadataField.BILLING));
    assertTrue(remoteBucket.isRequesterPays() == null || !remoteBucket.isRequesterPays());
    remoteBucket = remoteBucket.toBucketBuilder().setRequesterPays(true).buildBucketInfo();
    StorageBucket updatedBucket = storage.update(remoteBucket);
    assertTrue(updatedBucket.isRequesterPays());

    String projectId = remoteStorageHelper.getOptions().getProjectId();
    StorageBucket.BlobUploadOption option = StorageBucket.BlobUploadOption.withUserProject(projectId);
    String blobName = "test-create-empty-blob-requester-pays";
    StorageObject remoteBlob = updatedBucket.createBlob(blobName, BLOB_BYTE_CONTENT, option);
    assertNotNull(remoteBlob);
    byte[] readBytes =
        storage.readAllBytes(
            BUCKET_REQUESTER_PAYS, blobName, CloudStorageClient.BlobReadOption.withUserProject(projectId));
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
    remoteBucket = remoteBucket.toBucketBuilder().setRequesterPays(false).buildBucketInfo();
    updatedBucket = storage.update(remoteBucket, CloudStorageClient.BucketTargetOptions.userProjectId(projectId));
    assertFalse(updatedBucket.isRequesterPays());
  }

  @Test
  public void testListBucketRequesterPaysFails() throws InterruptedException {
    String projectId = remoteStorageHelper.getOptions().getProjectId();
    Iterator<StorageBucket> bucketIterator =
        storage
            .list(
                CloudStorageClient.BucketListOptions.withPrefix(BUCKET),
                CloudStorageClient.BucketListOptions.selectFields(),
                CloudStorageClient.BucketListOptions.withUserProject(projectId))
            .iterateAll()
            .iterator();
    while (!bucketIterator.hasNext()) {
      Thread.sleep(500);
      bucketIterator =
          storage
              .list(CloudStorageClient.BucketListOptions.withPrefix(BUCKET), CloudStorageClient.BucketListOptions.selectFields())
              .iterateAll()
              .iterator();
    }
    while (bucketIterator.hasNext()) {
      StorageBucket remoteBucket = bucketIterator.next();
      assertTrue(remoteBucket.getName().startsWith(BUCKET));
      assertNull(remoteBucket.getCreateTime());
      assertNull(remoteBucket.getSelfLink());
    }
  }

  @Test
  public void testListBucketDefaultKmsKeyName() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket remoteBucket =
        storage.create(
            BucketInfo.newBucketBuilder(bucketName)
                .setDefaultKmsKeyName(kmsKeyOneResourcePath)
                .setLocation(KMS_KEY_RING_LOCATION)
                .buildBucketInfo());
    assertNotNull(remoteBucket);
    assertTrue(remoteBucket.getDefaultKmsKeyName().startsWith(kmsKeyOneResourcePath));
    try {
      Iterator<StorageBucket> bucketIterator =
          storage
              .list(
                  CloudStorageClient.BucketListOptions.withPrefix(bucketName),
                  CloudStorageClient.BucketListOptions.selectFields(BucketMetadataField.ENCRYPTION))
              .iterateAll()
              .iterator();
      while (!bucketIterator.hasNext()) {
        Thread.sleep(500);
        bucketIterator =
            storage
                .list(
                    CloudStorageClient.BucketListOptions.withPrefix(bucketName),
                    CloudStorageClient.BucketListOptions.selectFields(CloudStorageClient.BucketMetadataField.ENCRYPTION))
                .iterateAll()
                .iterator();
      }
      while (bucketIterator.hasNext()) {
        StorageBucket bucket = bucketIterator.next();
        assertTrue(bucket.getName().startsWith(bucketName));
        assertNotNull(bucket.getDefaultKmsKeyName());
        assertTrue(bucket.getDefaultKmsKeyName().startsWith(kmsKeyOneResourcePath));
        assertNull(bucket.getCreateTime());
        assertNull(bucket.getSelfLink());
      }
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testRetentionPolicyNoLock() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket remoteBucket =
        storage.create(
            BucketInfo.newBucketBuilder(bucketName).setRetentionPeriod(RETENTION_PERIOD).buildBucketInfo());
    try {
      assertEquals(RETENTION_PERIOD, remoteBucket.getRetentionPeriod());
      assertNotNull(remoteBucket.getRetentionEffectiveTime());
      assertNull(remoteBucket.isRetentionPolicyLocked());
      remoteBucket =
          storage.get(bucketName, CloudStorageClient.BucketGetOptions.selectedFields(CloudStorageClient.BucketMetadataField.RETENTION_POLICY));
      assertEquals(RETENTION_PERIOD, remoteBucket.getRetentionPeriod());
      assertNotNull(remoteBucket.getRetentionEffectiveTime());
      assertNull(remoteBucket.isRetentionPolicyLocked());
      String blobName = "test-create-with-retention-policy-hold";
      BlobMetadata blobInfo = BlobMetadata.newBuilder(bucketName, blobName).buildMetadata();
      StorageObject remoteBlob = storage.create(blobInfo);
      assertNotNull(remoteBlob.getRetentionExpirationTime());
      remoteBucket = remoteBucket.toBucketBuilder().setRetentionPeriod(null).buildBucketInfo().modify();
      assertNull(remoteBucket.getRetentionPeriod());
      remoteBucket = remoteBucket.toBucketBuilder().setRetentionPeriod(null).buildBucketInfo().modify();
      assertNull(remoteBucket.getRetentionPeriod());
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testRetentionPolicyLock() throws ExecutionException, InterruptedException {
    retentionPolicyLockRequesterPays(true);
    retentionPolicyLockRequesterPays(false);
  }

  private void retentionPolicyLockRequesterPays(boolean requesterPays)
      throws ExecutionException, InterruptedException {
    String projectId = remoteStorageHelper.getOptions().getProjectId();
    String bucketName = RemoteStorageHelper.generateBucketName();
    BucketInfo bucketInfo;
    if (requesterPays) {
      bucketInfo =
          BucketInfo.newBucketBuilder(bucketName)
              .setRetentionPeriod(RETENTION_PERIOD)
              .setRequesterPays(true)
              .buildBucketInfo();
    } else {
      bucketInfo = BucketInfo.newBucketBuilder(bucketName).setRetentionPeriod(RETENTION_PERIOD).buildBucketInfo();
    }
    StorageBucket remoteBucket = storage.create(bucketInfo);
    try {
      assertNull(remoteBucket.isRetentionPolicyLocked());
      assertNotNull(remoteBucket.getRetentionEffectiveTime());
      assertNotNull(remoteBucket.getMetageneration());
      if (requesterPays) {
        remoteBucket =
            storage.lockRetentionPolicy(
                remoteBucket,
                CloudStorageClient.BucketTargetOptions.ifMetagenerationMatch(),
                CloudStorageClient.BucketTargetOptions.userProjectId(projectId));
      } else {
        remoteBucket =
            storage.lockRetentionPolicy(
                remoteBucket, CloudStorageClient.BucketTargetOptions.ifMetagenerationMatch());
      }
      assertTrue(remoteBucket.isRetentionPolicyLocked());
      assertNotNull(remoteBucket.getRetentionEffectiveTime());
    } finally {
      if (requesterPays) {
        bucketInfo = bucketInfo.toBucketBuilder().setRequesterPays(false).buildBucketInfo();
        StorageBucket updateBucket =
            storage.update(bucketInfo, CloudStorageClient.BucketTargetOptions.userProjectId(projectId));
        assertFalse(updateBucket.isRequesterPays());
      }
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testAttemptObjectDeleteWithRetentionPolicy()
      throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket remoteBucket =
        storage.create(
            BucketInfo.newBucketBuilder(bucketName).setRetentionPeriod(RETENTION_PERIOD).buildBucketInfo());
    assertEquals(RETENTION_PERIOD, remoteBucket.getRetentionPeriod());
    String blobName = "test-create-with-retention-policy";
    BlobMetadata blobInfo = BlobMetadata.newBuilder(bucketName, blobName).buildMetadata();
    StorageObject remoteBlob = storage.create(blobInfo);
    assertNotNull(remoteBlob.getRetentionExpirationTime());
    try {
      remoteBlob.deleteFromStorage();
      fail("Expected failure on delete from retentionPolicy");
    } catch (StorageServiceException ex) {
      // expected
    } finally {
      Thread.sleep(RETENTION_PERIOD_IN_MILLISECONDS);
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testEnableDisableBucketDefaultEventBasedHold()
      throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket remoteBucket =
        storage.create(BucketInfo.newBucketBuilder(bucketName).setDefaultEventBasedHold(true).buildBucketInfo());
    try {
      assertTrue(remoteBucket.getDefaultEventBasedHold());
      remoteBucket =
          storage.get(
              bucketName, CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.DEFAULT_EVENT_BASED_HOLD));
      assertTrue(remoteBucket.getDefaultEventBasedHold());
      String blobName = "test-create-with-event-based-hold";
      BlobMetadata blobInfo = BlobMetadata.newBuilder(bucketName, blobName).buildMetadata();
      StorageObject remoteBlob = storage.create(blobInfo);
      assertTrue(remoteBlob.getEventBasedHold());
      remoteBlob =
          storage.get(
              blobInfo.getBlobId(), CloudStorageClient.BlobGetOptions.selectFields(BlobMetadataField.EVENT_BASED_HOLD));
      assertTrue(remoteBlob.getEventBasedHold());
      remoteBlob = remoteBlob.toBuilderCopy().setEventBasedHold(false).buildMetadata().updateInStorage();
      assertFalse(remoteBlob.getEventBasedHold());
      remoteBucket = remoteBucket.toBucketBuilder().setDefaultEventBasedHold(false).buildBucketInfo().modify();
      assertFalse(remoteBucket.getDefaultEventBasedHold());
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testEnableDisableTemporaryHold() {
    String blobName = "test-create-with-temporary-hold";
    BlobMetadata blobInfo = BlobMetadata.newBuilder(BUCKET, blobName).setTemporaryHold(true).buildMetadata();
    StorageObject remoteBlob = storage.create(blobInfo);
    assertTrue(remoteBlob.getTemporaryHold());
    remoteBlob =
        storage.get(remoteBlob.getBlobId(), CloudStorageClient.BlobGetOptions.selectFields(BlobMetadataField.TEMPORARY_HOLD));
    assertTrue(remoteBlob.getTemporaryHold());
    remoteBlob = remoteBlob.toBuilderCopy().setTemporaryHold(false).buildMetadata().updateInStorage();
    assertFalse(remoteBlob.getTemporaryHold());
  }

  @Test
  public void testAttemptObjectDeleteWithEventBasedHold() {
    String blobName = "test-create-with-event-based-hold";
    BlobMetadata blobInfo = BlobMetadata.newBuilder(BUCKET, blobName).setEventBasedHold(true).buildMetadata();
    StorageObject remoteBlob = storage.create(blobInfo);
    assertTrue(remoteBlob.getEventBasedHold());
    try {
      remoteBlob.deleteFromStorage();
      fail("Expected failure on delete from eventBasedHold");
    } catch (StorageServiceException ex) {
      // expected
    } finally {
      remoteBlob.toBuilderCopy().setEventBasedHold(false).buildMetadata().updateInStorage();
    }
  }

  @Test
  public void testAttemptDeletionObjectTemporaryHold() {
    String blobName = "test-create-with-temporary-hold";
    BlobMetadata blobInfo = BlobMetadata.newBuilder(BUCKET, blobName).setTemporaryHold(true).buildMetadata();
    StorageObject remoteBlob = storage.create(blobInfo);
    assertTrue(remoteBlob.getTemporaryHold());
    try {
      remoteBlob.deleteFromStorage();
      fail("Expected failure on delete from temporaryHold");
    } catch (StorageServiceException ex) {
      // expected
    } finally {
      remoteBlob.toBuilderCopy().setTemporaryHold(false).buildMetadata().updateInStorage();
    }
  }

  @Test
  public void testGetServiceAccount() {
    String projectId = remoteStorageHelper.getOptions().getProjectId();
    ServiceAccountInfo serviceAccount = storage.getServiceAccount(projectId);
    assertNotNull(serviceAccount);
    assertTrue(serviceAccount.getEmail().endsWith(SERVICE_ACCOUNT_EMAIL_SUFFIX));
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testBucketWithBucketPolicyOnlyEnabled() throws Exception {
    String bucket = RemoteStorageHelper.generateBucketName();
    try {
      storage.create(
          StorageBucket.newBucketBuilder(bucket)
              .setIamConfiguration(
                  BucketInfo.BucketIamConfiguration.newUniformBucketLevelAccessBuilder()
                      .setIsBucketPolicyOnlyEnabled(true)
                      .buildBucketIamConfiguration())
              .buildBucketInfo());

      StorageBucket remoteBucket =
          storage.get(bucket, CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.IAMCONFIGURATION));

      assertTrue(remoteBucket.getIamConfiguration().isBucketPolicyOnlyEnabled());
      assertNotNull(remoteBucket.getIamConfiguration().getBucketPolicyOnlyLockedTime());

      try {
        remoteBucket.listAllAcls();
        fail("StorageException was expected.");
      } catch (StorageServiceException e) {
        // Expected: Listing legacy ACLs should fail on a BPO enabled bucket
      }
      try {
        remoteBucket.listAllDefaultAcls();
        fail("StorageException was expected");
      } catch (StorageServiceException e) {
        // Expected: Listing legacy ACLs should fail on a BPO enabled bucket
      }
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucket, 1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testBucketWithUniformBucketLevelAccessEnabled() throws Exception {
    String bucket = RemoteStorageHelper.generateBucketName();
    try {
      storage.create(
          StorageBucket.newBucketBuilder(bucket)
              .setIamConfiguration(
                  BucketInfo.BucketIamConfiguration.newUniformBucketLevelAccessBuilder()
                      .setIsUniformBucketLevelAccessEnabled(true)
                      .buildBucketIamConfiguration())
              .buildBucketInfo());

      StorageBucket remoteBucket =
          storage.get(bucket, CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.IAMCONFIGURATION));

      assertTrue(remoteBucket.getIamConfiguration().isUniformBucketLevelAccessEnabled());
      assertNotNull(remoteBucket.getIamConfiguration().getUniformBucketLevelAccessLockedTime());
      try {
        remoteBucket.listAllAcls();
        fail("StorageException was expected.");
      } catch (StorageServiceException e) {
        // Expected: Listing legacy ACLs should fail on a BPO enabled bucket
      }
      try {
        remoteBucket.listAllDefaultAcls();
        fail("StorageException was expected");
      } catch (StorageServiceException e) {
        // Expected: Listing legacy ACLs should fail on a BPO enabled bucket
      }
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucket, 1, TimeUnit.MINUTES);
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void testEnableAndDisableBucketPolicyOnlyOnExistingBucket() throws Exception {
    String bpoBucket = RemoteStorageHelper.generateBucketName();
    try {
      // BPO is disabled by default.
      StorageBucket bucket =
          storage.create(
              StorageBucket.newBucketBuilder(bpoBucket)
                  .setAcl(ImmutableList.of(AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), UserRole.READER)))
                  .setDefaultAcl(
                      ImmutableList.of(AccessControlEntry.create(AccessControlEntry.UserIdentity.allAuthenticatedUsers(), UserRole.READER)))
                  .buildBucketInfo());

      BucketInfo.BucketIamConfiguration bpoEnabledIamConfiguration =
          BucketInfo.BucketIamConfiguration.newUniformBucketLevelAccessBuilder().setIsBucketPolicyOnlyEnabled(true).buildBucketIamConfiguration();
      bucket
          .toBucketBuilder()
          .setAcl(null)
          .setDefaultAcl(null)
          .setIamConfiguration(bpoEnabledIamConfiguration)
          .buildBucketInfo()
          .modify();

      StorageBucket remoteBucket =
          storage.get(bpoBucket, CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.IAMCONFIGURATION));

      assertTrue(remoteBucket.getIamConfiguration().isBucketPolicyOnlyEnabled());
      assertNotNull(remoteBucket.getIamConfiguration().getBucketPolicyOnlyLockedTime());

      remoteBucket
          .toBucketBuilder()
          .setIamConfiguration(
              bpoEnabledIamConfiguration.toUniformBucketLevelAccessBuilder().setIsBucketPolicyOnlyEnabled(false).buildBucketIamConfiguration())
          .buildBucketInfo()
          .modify();

      remoteBucket =
          storage.get(
              bpoBucket,
              CloudStorageClient.BucketGetOptions.selectedFields(
                  BucketMetadataField.IAMCONFIGURATION, BucketMetadataField.ACL, BucketMetadataField.DEFAULT_OBJECT_ACL));

      assertFalse(remoteBucket.getIamConfiguration().isBucketPolicyOnlyEnabled());
      assertEquals(AccessControlEntry.UserIdentity.allAuthenticatedUsers(), remoteBucket.getDefaultAcl().get(0).getEntity());
      assertEquals(UserRole.READER, remoteBucket.getDefaultAcl().get(0).getRole());
      assertEquals(UserIdentity.allAuthenticatedUsers(), remoteBucket.getAcl().get(0).getEntity());
      assertEquals(UserRole.READER, remoteBucket.getAcl().get(0).getRole());
    } finally {
      RemoteStorageHelper.forceDelete(storage, bpoBucket, 1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testEnableAndDisableUniformBucketLevelAccessOnExistingBucket() throws Exception {
    String bpoBucket = RemoteStorageHelper.generateBucketName();
    try {
      BucketInfo.BucketIamConfiguration ublaDisabledIamConfiguration =
          BucketInfo.BucketIamConfiguration.newUniformBucketLevelAccessBuilder()
              .setIsUniformBucketLevelAccessEnabled(false)
              .buildBucketIamConfiguration();
      StorageBucket bucket =
          storage.create(
              StorageBucket.newBucketBuilder(bpoBucket)
                  .setIamConfiguration(ublaDisabledIamConfiguration)
                  .setAcl(ImmutableList.of(AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), UserRole.READER)))
                  .setDefaultAcl(
                      ImmutableList.of(AccessControlEntry.create(UserIdentity.allAuthenticatedUsers(), UserRole.READER)))
                  .buildBucketInfo());

      bucket
          .toBucketBuilder()
          .setAcl(null)
          .setDefaultAcl(null)
          .setIamConfiguration(
              ublaDisabledIamConfiguration
                  .toUniformBucketLevelAccessBuilder()
                  .setIsUniformBucketLevelAccessEnabled(true)
                  .buildBucketIamConfiguration())
          .buildBucketInfo()
          .modify();

      StorageBucket remoteBucket =
          storage.get(bpoBucket, CloudStorageClient.BucketGetOptions.selectedFields(CloudStorageClient.BucketMetadataField.IAMCONFIGURATION));

      assertTrue(remoteBucket.getIamConfiguration().isUniformBucketLevelAccessEnabled());
      assertNotNull(remoteBucket.getIamConfiguration().getUniformBucketLevelAccessLockedTime());

      remoteBucket.toBucketBuilder().setIamConfiguration(ublaDisabledIamConfiguration).buildBucketInfo().modify();

      remoteBucket =
          storage.get(
              bpoBucket,
              CloudStorageClient.BucketGetOptions.selectedFields(
                  BucketMetadataField.IAMCONFIGURATION, BucketMetadataField.ACL, BucketMetadataField.DEFAULT_OBJECT_ACL));

      assertFalse(remoteBucket.getIamConfiguration().isUniformBucketLevelAccessEnabled());
      assertEquals(UserIdentity.allAuthenticatedUsers(), remoteBucket.getDefaultAcl().get(0).getEntity());
      assertEquals(UserRole.READER, remoteBucket.getDefaultAcl().get(0).getRole());
      assertEquals(UserIdentity.allAuthenticatedUsers(), remoteBucket.getAcl().get(0).getEntity());
      assertEquals(UserRole.READER, remoteBucket.getAcl().get(0).getRole());
    } finally {
      RemoteStorageHelper.forceDelete(storage, bpoBucket, 1, TimeUnit.MINUTES);
    }
  }

  @Test
  public void testUploadUsingSignedURL() throws Exception {
    String blobName = "test-signed-url-upload";
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    assertNotNull(storage.create(blob));
    for (CloudStorageClient.UrlSigningOption urlStyle :
        Arrays.asList(
            CloudStorageClient.UrlSigningOption.usePathStyle(),
            CloudStorageClient.UrlSigningOption.useVirtualHostedStyle())) {
      URL signUrl =
          storage.signUrl(
              blob, 1, TimeUnit.HOURS, CloudStorageClient.UrlSigningOption.withHttpMethod(HttpRequestMethod.POST), urlStyle);
      byte[] bytesArrayToUpload = BLOB_STRING_CONTENT.getBytes();
      try (WriteChannel writer = storage.writer(signUrl)) {
        writer.write(ByteBuffer.wrap(bytesArrayToUpload, 0, bytesArrayToUpload.length));
      }

      int lengthOfDownLoadBytes = -1;
      BlobIdentifier blobId = BlobIdentifier.from(BUCKET, blobName);
      StorageObject blobToRead = storage.get(blobId);
      try (ReadChannel reader = blobToRead.newReader()) {
        ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
        lengthOfDownLoadBytes = reader.read(bytes);
      }

      assertEquals(bytesArrayToUpload.length, lengthOfDownLoadBytes);
      assertTrue(storage.delete(BUCKET, blobName));
    }
  }

  @Test
  public void testBucketLocationType() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    long bucketMetageneration = 42;
    storage.create(
        BucketInfo.newBucketBuilder(bucketName)
            .setLocation("us")
            .setRetentionPeriod(RETENTION_PERIOD)
            .buildBucketInfo());
    StorageBucket bucket =
        storage.get(
            bucketName, CloudStorageClient.BucketGetOptions.ifMetagenerationNotMatch(bucketMetageneration));
    assertTrue(LOCATION_TYPES.contains(bucket.getLocationType()));

    StorageBucket bucket1 =
        storage.lockRetentionPolicy(bucket, CloudStorageClient.BucketTargetOptions.ifMetagenerationMatch());
    assertTrue(LOCATION_TYPES.contains(bucket1.getLocationType()));

    StorageBucket updatedBucket =
        storage.update(
            BucketInfo.newBucketBuilder(bucketName)
                .setLocation("asia")
                .setRetentionPeriod(RETENTION_PERIOD)
                .buildBucketInfo());
    assertTrue(LOCATION_TYPES.contains(updatedBucket.getLocationType()));

    Iterator<StorageBucket> bucketIterator =
        storage.list(CloudStorageClient.BucketListOptions.withPrefix(bucketName)).iterateAll().iterator();
    while (bucketIterator.hasNext()) {
      StorageBucket remoteBucket = bucketIterator.next();
      assertTrue(LOCATION_TYPES.contains(remoteBucket.getLocationType()));
    }
    RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
  }

  @Test
  public void testBucketLogging() throws ExecutionException, InterruptedException {
    String logsBucket = RemoteStorageHelper.generateBucketName();
    String loggingBucket = RemoteStorageHelper.generateBucketName();
    try {
      assertNotNull(storage.create(BucketInfo.newBucketBuilder(logsBucket).setLocation("us").buildBucketInfo()));
      Policy policy = storage.getIamPolicy(logsBucket);
      assertNotNull(policy);
      BucketInfo.LoggingConfig logging =
          BucketInfo.LoggingConfig.newLogConfigBuilder()
              .setLogBucket(logsBucket)
              .setLogObjectPrefix("test-logs")
              .buildLoggingConfig();
      StorageBucket bucket =
          storage.create(
              BucketInfo.newBucketBuilder(loggingBucket).setLocation("us").setLogging(logging).buildBucketInfo());
      assertEquals(logsBucket, bucket.getLogging().getLogBucket());
      assertEquals("test-logs", bucket.getLogging().getLogObjectPrefix());

      // Disable bucket logging.
      StorageBucket updatedBucket = bucket.toBucketBuilder().setLogging(null).buildBucketInfo().modify();
      assertNull(updatedBucket.getLogging());

    } finally {
      RemoteStorageHelper.forceDelete(storage, logsBucket, 5, TimeUnit.SECONDS);
      RemoteStorageHelper.forceDelete(storage, loggingBucket, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testSignedPostPolicyV4() throws Exception {
    S3PostPolicyV4.PostFieldsMapV4 fields = S3PostPolicyV4.PostFieldsMapV4.createBuilder().setAcl("public-read").buildMap();

    S3PostPolicyV4 policy =
        storage.generateSignedPostPolicyV4(
            BlobMetadata.newBuilder(BUCKET, "my-object").buildMetadata(), 7, TimeUnit.DAYS, fields);

    HttpClient client = HttpClientBuilder.create().build();
    HttpPost request = new HttpPost(policy.getUrl());
    MultipartEntityBuilder builder = MultipartEntityBuilder.create();

    for (Map.Entry<String, String> entry : policy.getFields().entrySet()) {
      builder.addTextBody(entry.getKey(), entry.getValue());
    }
    File file = File.createTempFile("temp", "file");
    Files.write(file.toPath(), "hello world".getBytes());
    builder.addBinaryBody(
        "file", new FileInputStream(file), ContentType.APPLICATION_OCTET_STREAM, file.getName());
    request.setEntity(builder.build());
    client.execute(request);

    assertEquals("hello world", new String(storage.get(BUCKET, "my-object").getContent()));
  }

  @Test
  public void testBlobReload() throws Exception {
    String blobName = "test-blob-reload";
    BlobIdentifier blobId = BlobIdentifier.from(BUCKET, blobName);
    BlobMetadata blobInfo = BlobMetadata.newBuilder(blobId).buildMetadata();
    StorageObject blob = storage.create(blobInfo, new byte[] {0, 1, 2});

    StorageObject blobUnchanged = blob.reloadFromStorage();
    assertEquals(blob, blobUnchanged);

    blob.newWriter().close();
    try {
      blob.reloadFromStorage(StorageObject.BlobSourceOptions.ifGenerationMatch());
      fail("StorageException was expected");
    } catch (StorageServiceException e) {
      assertEquals(412, e.getCode());
      assertEquals("Precondition Failed", e.getMessage());
    }

    StorageObject updated = blob.reloadFromStorage();
    assertEquals(blob.getBucket(), updated.getBucket());
    assertEquals(blob.getName(), updated.getName());
    assertNotEquals(blob.getGeneration(), updated.getGeneration());
    assertEquals(new Long(0), updated.getSize());

    updated.deleteFromStorage();
    assertNull(updated.reloadFromStorage());
  }

  @Test
  public void testDeleteLifecycleRules() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    StorageBucket bucket =
        storage.create(
            BucketInfo.newBucketBuilder(bucketName)
                .setLocation("us")
                .setLifecycleRules(LIFECYCLE_RULES)
                .buildBucketInfo());
    assertThat(bucket.getLifecycleRules()).isNotNull();
    assertThat(bucket.getLifecycleRules()).hasSize(2);
    try {
      StorageBucket updatedBucket = bucket.toBucketBuilder().deleteLifecycleRules().buildBucketInfo().modify();
      assertThat(updatedBucket.getLifecycleRules()).hasSize(0);
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testUploadFromDownloadTo() throws Exception {
    String blobName = "test-uploadFrom-downloadTo-blob";
    BlobIdentifier blobId = BlobIdentifier.from(BUCKET, blobName);
    BlobMetadata blobInfo = BlobMetadata.newBuilder(blobId).buildMetadata();

    Path tempFileFrom = Files.createTempFile("ITStorageTest_", ".tmp");
    Files.write(tempFileFrom, BLOB_BYTE_CONTENT);
    StorageObject blob = storage.createFrom(blobInfo, tempFileFrom);
    assertEquals(BUCKET, blob.getBucket());
    assertEquals(blobName, blob.getName());
    assertEquals(BLOB_BYTE_CONTENT.length, (long) blob.getSize());

    Path tempFileTo = Files.createTempFile("ITStorageTest_", ".tmp");
    storage.get(blobId).downloadToPath(tempFileTo);
    byte[] readBytes = Files.readAllBytes(tempFileTo);
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
  }

  @Test
  public void testUploadWithEncryption() throws Exception {
    String blobName = "test-upload-withEncryption";
    BlobIdentifier blobId = BlobIdentifier.from(BUCKET, blobName);
    BlobMetadata blobInfo = BlobMetadata.newBuilder(blobId).buildMetadata();

    ByteArrayInputStream content = new ByteArrayInputStream(BLOB_BYTE_CONTENT);
    StorageObject blob = storage.createFrom(blobInfo, content, CloudStorageClient.BlobWriteOptions.withEncryptionKey(KEY));

    try {
      blob.getContent();
      fail("StorageException was expected");
    } catch (StorageServiceException e) {
      String expectedMessage =
          "The target object is encrypted by a customer-supplied encryption key.";
      assertTrue(e.getMessage().contains(expectedMessage));
      assertEquals(400, e.getCode());
    }
    byte[] readBytes = blob.getContent(StorageObject.BlobSourceOptions.withDecryptionKey(KEY));
    assertArrayEquals(BLOB_BYTE_CONTENT, readBytes);
  }

  private StorageObject createBlob(String method, BlobMetadata blobInfo, boolean detectType) throws IOException {
    switch (method) {
      case "create":
        return detectType
            ? storage.create(blobInfo, CloudStorageClient.BlobUploadOption.autoDetectContentType())
            : storage.create(blobInfo);
      case "createFrom":
        InputStream inputStream = new ByteArrayInputStream(BLOB_BYTE_CONTENT);
        return detectType
            ? storage.createFrom(blobInfo, inputStream, BlobWriteOptions.autoDetectContentType())
            : storage.createFrom(blobInfo, inputStream);
      case "writer":
        if (detectType) {
          storage.writer(blobInfo, BlobWriteOptions.autoDetectContentType()).close();
        } else {
          storage.writer(blobInfo).close();
        }
        return storage.get(BlobIdentifier.from(blobInfo.getBucket(), blobInfo.getName()));
      default:
        throw new IllegalArgumentException("Unknown method " + method);
    }
  }

  private void testAutoContentType(String method) throws IOException {
    String[] names = {"file1.txt", "dir with spaces/Pic.Jpg", "no_extension"};
    String[] types = {"text/plain", "image/jpeg", "application/octet-stream"};
    for (int i = 0; i < names.length; i++) {
      BlobIdentifier blobId = BlobIdentifier.from(BUCKET, names[i]);
      BlobMetadata blobInfo = BlobMetadata.newBuilder(blobId).buildMetadata();
      StorageObject blob_true = createBlob(method, blobInfo, true);
      assertEquals(types[i], blob_true.getContentType());

      StorageObject blob_false = createBlob(method, blobInfo, false);
      assertEquals("application/octet-stream", blob_false.getContentType());
    }
    String customType = "custom/type";
    BlobIdentifier blobId = BlobIdentifier.from(BUCKET, names[0]);
    BlobMetadata blobInfo = BlobMetadata.newBuilder(blobId).setContentType(customType).buildMetadata();
    StorageObject blob = createBlob(method, blobInfo, true);
    assertEquals(customType, blob.getContentType());
  }

  @Test
  public void testAutoContentTypeCreate() throws IOException {
    testAutoContentType("create");
  }

  @Test
  public void testAutoContentTypeCreateFrom() throws IOException {
    testAutoContentType("createFrom");
  }

  @Test
  public void testAutoContentTypeWriter() throws IOException {
    testAutoContentType("writer");
  }

  @Test
  public void testRemoveBucketCORS() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    List<Cors.Origin> origins = ImmutableList.of(Cors.Origin.of("http://cloud.google.com"));
    List<HttpRequestMethod> httpMethods = ImmutableList.of(HttpRequestMethod.GET);
    List<String> responseHeaders = ImmutableList.of("Content-Type");
    try {
      Cors cors =
          Cors.newBuilder()
              .setOrigins(origins)
              .setMethods(httpMethods)
              .setResponseHeaders(responseHeaders)
              .setMaxAgeSeconds(100)
              .build();
      storage.create(BucketInfo.newBucketBuilder(bucketName).setCors(ImmutableList.of(cors)).buildBucketInfo());

      // case-1 : Cors are set and field selector is selected then returns not-null.
      StorageBucket remoteBucket =
          storage.get(bucketName, CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.CORS));
      assertThat(remoteBucket.getCors()).isNotNull();
      assertThat(remoteBucket.getCors().get(0).getMaxAgeSeconds()).isEqualTo(100);
      assertThat(remoteBucket.getCors().get(0).getMethods()).isEqualTo(httpMethods);
      assertThat(remoteBucket.getCors().get(0).getOrigins()).isEqualTo(origins);
      assertThat(remoteBucket.getCors().get(0).getResponseHeaders()).isEqualTo(responseHeaders);

      // case-2 : Cors are set but field selector isn't selected then returns not-null.
      remoteBucket = storage.get(bucketName);
      assertThat(remoteBucket.getCors()).isNotNull();

      // Remove CORS configuration from the bucket.
      StorageBucket updatedBucket = remoteBucket.toBucketBuilder().setCors(null).buildBucketInfo().modify();
      assertThat(updatedBucket.getCors()).isNull();

      // case-3 : Cors are not set and field selector is selected then returns null.
      updatedBucket = storage.get(bucketName, CloudStorageClient.BucketGetOptions.selectedFields(BucketMetadataField.CORS));
      assertThat(updatedBucket.getCors()).isNull();

      // case-4 : Cors are not set and field selector isn't selected then returns null.
      updatedBucket = storage.get(bucketName);
      assertThat(updatedBucket.getCors()).isNull();

    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testBucketUpdateTime() throws ExecutionException, InterruptedException {
    String bucketName = RemoteStorageHelper.generateBucketName();
    BucketInfo bucketInfo =
        BucketInfo.newBucketBuilder(bucketName).setLocation("us").setVersioningEnabled(true).buildBucketInfo();
    try {
      StorageBucket bucket = storage.create(bucketInfo);
      assertThat(bucket).isNotNull();
      assertThat(bucket.isVersioningEnabled()).isTrue();
      assertThat(bucket.getCreateTime()).isNotNull();
      assertThat(bucket.getUpdateTime()).isEqualTo(bucket.getCreateTime());

      StorageBucket updatedBucket = bucket.toBucketBuilder().setVersioningEnabled(false).buildBucketInfo().modify();
      assertThat(updatedBucket.isVersioningEnabled()).isFalse();
      assertThat(updatedBucket.getUpdateTime()).isNotNull();
      assertThat(updatedBucket.getCreateTime()).isEqualTo(bucket.getCreateTime());
      assertThat(updatedBucket.getUpdateTime()).isGreaterThan(bucket.getCreateTime());
    } finally {
      RemoteStorageHelper.forceDelete(storage, bucketName, 5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testBlobTimeStorageClassUpdated() {
    String blobName = "test-blob-with-storage-class";
    StorageClassType storageClass = StorageClassType.COLDLINE;
    BlobMetadata blob = BlobMetadata.newBuilder(BUCKET, blobName).setStorageClass(storageClass).buildMetadata();
    StorageObject remoteBlob = storage.create(blob);
    assertThat(remoteBlob).isNotNull();
    assertThat(remoteBlob.getBucket()).isEqualTo(blob.getBucket());
    assertThat(remoteBlob.getName()).isEqualTo(blob.getName());
    assertThat(remoteBlob.getCreateTime()).isNotNull();
    assertThat(remoteBlob.getUpdateTime()).isEqualTo(remoteBlob.getCreateTime());
    assertThat(remoteBlob.getTimeStorageClassUpdated()).isEqualTo(remoteBlob.getCreateTime());

    // We can't change an object's storage class directly, the only way is to rewrite the object
    // with the desired storage class.
    BlobIdentifier blobId = BlobIdentifier.from(BUCKET, blobName);
    CloudStorageClient.CopyOperationRequest request =
        CloudStorageClient.CopyOperationRequest.builder()
            .setSource(blobId)
            .setTarget(BlobMetadata.newBuilder(blobId).setStorageClass(StorageClassType.STANDARD).buildMetadata())
            .buildRequest();
    StorageObject updatedBlob1 = storage.copy(request).getResult();
    assertThat(updatedBlob1.getTimeStorageClassUpdated()).isNotNull();
    assertThat(updatedBlob1.getCreateTime()).isGreaterThan(remoteBlob.getCreateTime());
    assertThat(updatedBlob1.getUpdateTime()).isGreaterThan(remoteBlob.getCreateTime());
    assertThat(updatedBlob1.getTimeStorageClassUpdated())
        .isGreaterThan(remoteBlob.getTimeStorageClassUpdated());

    // Updates the other properties of the blob's to check the difference between blob updateTime
    // and timeStorageClassUpdated.
    StorageObject updatedBlob2 = updatedBlob1.toBuilderCopy().setContentType(CONTENT_TYPE).buildMetadata().updateInStorage();
    assertThat(updatedBlob2.getUpdateTime())
        .isGreaterThan(updatedBlob2.getTimeStorageClassUpdated());
    assertThat(updatedBlob2.getTimeStorageClassUpdated())
        .isEqualTo(updatedBlob1.getTimeStorageClassUpdated());
    assertThat(updatedBlob2.deleteFromStorage()).isTrue();
  }

  @Test
  public void testWriterWithKmsKeyName() throws IOException {
    // Write an empty object with a kmsKeyName.
    String blobName = "test-empty-blob";
    BlobMetadata blobInfo = BlobMetadata.newBuilder(BUCKET, blobName).buildMetadata();
    StorageObject blob =
        storage.create(blobInfo, CloudStorageClient.BlobUploadOption.kmsKey(kmsKeyOneResourcePath));

    // Create a writer using blob that already has metadata received from Storage API.
    int numberOfBytes;
    try (WriteChannel writer = blob.newWriter()) {
      byte[] content = BLOB_STRING_CONTENT.getBytes(UTF_8);
      numberOfBytes = writer.write(ByteBuffer.wrap(content, 0, content.length));
    }
    assertThat(numberOfBytes).isEqualTo(27);
    assertThat(blob.getKmsKeyName()).isNotNull();
    assertThat(storage.delete(BUCKET, blobName)).isTrue();
  }
}
