/*
 * Copyright 2018 Google LLC
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

package com.google.cloud.storage.spi.v1;

import io.opencensus.trace.EndSpanOptions;

/** Helper class for instrumenting {@link HttpStorageRpcClient} with Open Census APIs. */
class HttpStorageRpcSpanNames {
  // OpenCensus span name prefix, 'Sent' for client and 'RECV' for server.
  static final String RPC_CLIENT_PREFIX = "Sent";

  static final String CREATE_BUCKET_SPAN = getTraceSpanName("create(Bucket,Map)");
  static final String CREATE_OBJECT_SPAN =
      getTraceSpanName("create(StorageObject,InputStream,Map)");
  static final String LIST_BUCKETS_SPAN = getTraceSpanName("list(Map)");
  static final String LIST_OBJECTS_SPAN = getTraceSpanName("create(String,Map)");
  static final String GET_BUCKET_SPAN = getTraceSpanName("get(Bucket,Map)");
  static final String GET_OBJECT_SPAN = getTraceSpanName("get(StorageObject,Map)");
  static final String PATCH_BUCKET_SPAN = getTraceSpanName("patch(Bucket,Map)");
  static final String PATCH_OBJECT_SPAN = getTraceSpanName("patch(StorageObject,Map)");
  static final String DELETE_BUCKET_SPAN = getTraceSpanName("delete(Bucket,Map)");
  static final String DELETE_OBJECT_SPAN = getTraceSpanName("delete(StorageObject,Map)");
  static final String CREATE_BATCH_SPAN = getTraceSpanName("createBatch()");
  static final String COMPOSE_SPAN = getTraceSpanName("compose(Iterable,StorageObject,Map)");
  static final String LOAD_SPAN = getTraceSpanName("load(StorageObject,Map");
  static final String READ_SPAN = getTraceSpanName("read(StorageObject,Map,long,int)");
  static final String OPEN_SPAN = getTraceSpanName("open(StorageObject,Map)");
  static final String WRITE_SPAN =
      getTraceSpanName("write(String,byte[],int,long,int,boolean)");
  static final String OPEN_REWRITE_SPAN = getTraceSpanName("openRewrite(RewriteRequest)");
  static final String CONTINUE_REWRITE_SPAN =
      getTraceSpanName("continueRewrite(RewriteResponse)");
  static final String GET_BUCKET_ACL_SPAN = getTraceSpanName("getAcl(String,String,Map)");
  static final String DELETE_BUCKET_ACL_SPAN =
      getTraceSpanName("deleteAcl(String,String,Map)");
  static final String CREATE_BUCKET_ACL_SPAN =
      getTraceSpanName("createAcl(BucketAccessControl,Map)");
  static final String PATCH_BUCKET_ACL_SPAN =
      getTraceSpanName("patchAcl(BucketAccessControl,Map)");
  static final String LIST_BUCKET_ACLS_SPAN = getTraceSpanName("listAcls(String,Map)");
  static final String GET_OBJECT_DEFAULT_ACL_SPAN =
      getTraceSpanName("getDefaultAcl(String,String)");
  static final String DELETE_OBJECT_DEFAULT_ACL_SPAN =
      getTraceSpanName("deleteDefaultAcl(String,String)");
  static final String CREATE_OBJECT_DEFAULT_ACL_SPAN =
      getTraceSpanName("createDefaultAcl(ObjectAccessControl)");
  static final String PATCH_OBJECT_DEFAULT_ACL_SPAN =
      getTraceSpanName("patchDefaultAcl(ObjectAccessControl)");
  static final String LIST_OBJECT_DEFAULT_ACLS_SPAN =
      getTraceSpanName("listDefaultAcls(String)");
  static final String GET_OBJECT_ACL_SPAN =
      getTraceSpanName("getAcl(String,String,Long,String)");
  static final String DELETE_OBJECT_ACL_SPAN =
      getTraceSpanName("deleteAcl(String,String,Long,String)");
  static final String CREATE_OBJECT_ACL_SPAN =
      getTraceSpanName("createAcl(ObjectAccessControl)");
  static final String PATCH_OBJECT_ACL_SPAN =
      getTraceSpanName("patchAcl(ObjectAccessControl)");
  static final String LIST_OBJECT_ACLS_SPAN = getTraceSpanName("listAcls(String,String,Long)");
  static final String CREATE_HMAC_KEY_SPAN = getTraceSpanName("createHmacKey(String)");
  static final String GET_HMAC_KEY_SPAN = getTraceSpanName("getHmacKey(String)");
  static final String DELETE_HMAC_KEY_SPAN = getTraceSpanName("deleteHmacKey(String)");
  static final String LIST_HMAC_KEYS_SPAN =
      getTraceSpanName("listHmacKeys(String,String,Long)");
  static final String UPDATE_HMAC_KEY_SPAN =
      getTraceSpanName("updateHmacKey(HmacKeyMetadata)");
  static final String GET_BUCKET_IAM_POLICY_SPAN =
      getTraceSpanName("getIamPolicy(String,Map)");
  static final String SPAN_SET_BUCKET_POLICY =
      getTraceSpanName("setIamPolicy(String,Policy,Map)");
  static final String SPAN_TEST_BUCKET_PERMISSIONS =
      getTraceSpanName("testIamPermissions(String,List,Map)");
  static final String SPAN_REMOVE_NOTIFICATION =
      getTraceSpanName("deleteNotification(String,String)");
  static final String SPAN_LIST_ALL_NOTIFICATIONS = getTraceSpanName("listNotifications(String)");
  static final String SPAN_CREATE_NEW_NOTIFICATION =
      getTraceSpanName("createNotification(String,Notification)");
  static final String SPAN_RETENTION_LOCK =
      getTraceSpanName("lockRetentionPolicy(String,Long)");
  static final String SPAN_FETCH_SERVICE_ACCOUNT = getTraceSpanName("getServiceAccount(String)");
  static final String SPAN_BATCH_SUBMISSION =
      getTraceSpanName(RpcRequestBatch.class.getName() + ".submit()");
  static final EndSpanOptions DEFAULT_END_SPAN_OPTIONS =
      EndSpanOptions.builder().setSampleToLocalSpanStore(true).build();

  static String getTraceSpanName(String rpcMethod) {
    return String.format(
        "%s.%s.%s", RPC_CLIENT_PREFIX, HttpStorageRpcClient.class.getName(), rpcMethod);
  }

  private HttpStorageRpcSpanNames() {}
}
