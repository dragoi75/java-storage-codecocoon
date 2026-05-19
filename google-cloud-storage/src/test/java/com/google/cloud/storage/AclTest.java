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

import static org.junit.Assert.assertEquals;

import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.cloud.storage.AclEntry.DomainInfo;
import com.google.cloud.storage.AclEntry.BaseEntity;
import com.google.cloud.storage.AclEntry.BaseEntity.EntityType;
import com.google.cloud.storage.AclEntry.ProjectInfo;
import com.google.cloud.storage.AclEntry.RawDataEntity;
import com.google.cloud.storage.AclEntry.UserPrincipal;
import org.junit.Test;

public class AclTest {

  private static final AclEntry.AccessRole ROLE = AclEntry.AccessRole.OWNER;
  private static final BaseEntity ENTITY = UserPrincipal.allAuthenticatedUsers();
  private static final String ETAG = "etag";
  private static final String ID = "id";
  private static final AclEntry ACL = AclEntry.builder(ENTITY, ROLE).setEtag(ETAG).setId(ID).create();

  @Test
  public void testBuilder() {
    assertEquals(ROLE, ACL.getRole());
    assertEquals(ENTITY, ACL.getEntity());
    assertEquals(ETAG, ACL.getEtag());
    assertEquals(ID, ACL.getId());
  }

  @Test
  public void testToBuilder() {
    assertEquals(ACL, ACL.asBuilder().create());
    AclEntry acl =
        ACL.asBuilder()
            .setEtag("otherEtag")
            .setId("otherId")
            .setRole(AclEntry.AccessRole.READER)
            .setEntity(AclEntry.UserPrincipal.allUsers())
            .create();
    assertEquals(AclEntry.AccessRole.READER, acl.getRole());
    assertEquals(AclEntry.UserPrincipal.allUsers(), acl.getEntity());
    assertEquals("otherEtag", acl.getEtag());
    assertEquals("otherId", acl.getId());
  }

  @Test
  public void testToAndFromPb() {
    assertEquals(ACL, AclEntry.fromProto(ACL.toBucketProto()));
    assertEquals(ACL, AclEntry.fromProto(ACL.toObjectProto()));
  }

  @Test
  public void testDomainEntity() {
    DomainInfo acl = new AclEntry.DomainInfo("d1");
    assertEquals("d1", acl.getDomain());
    assertEquals(BaseEntity.EntityType.DOMAIN, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, BaseEntity.fromProto(pb));
  }

  @Test
  public void testGroupEntity() {
    AclEntry.EmailGroup acl = new AclEntry.EmailGroup("g1");
    assertEquals("g1", acl.getEmail());
    assertEquals(BaseEntity.EntityType.GROUP, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, BaseEntity.fromProto(pb));
  }

  @Test
  public void testUserEntity() {
    AclEntry.UserPrincipal acl = new UserPrincipal("u1");
    assertEquals("u1", acl.getEmail());
    assertEquals(AclEntry.BaseEntity.EntityType.USER, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, BaseEntity.fromProto(pb));
  }

  @Test
  public void testProjectEntity() {
    ProjectInfo acl = new AclEntry.ProjectInfo(AclEntry.ProjectInfo.ProjectAccessLevel.VIEWERS, "p1");
    assertEquals(AclEntry.ProjectInfo.ProjectAccessLevel.VIEWERS, acl.getProjectRole());
    assertEquals("p1", acl.getProjectId());
    assertEquals(BaseEntity.EntityType.PROJECT, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AclEntry.BaseEntity.fromProto(pb));
  }

  @Test
  public void testRawEntity() {
    BaseEntity acl = new RawDataEntity("bla");
    assertEquals("bla", acl.getValue());
    assertEquals(EntityType.UNKNOWN, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AclEntry.BaseEntity.fromProto(pb));
  }

  @Test
  public void testOf() {
    AclEntry acl = AclEntry.ofEntry(UserPrincipal.allUsers(), AclEntry.AccessRole.READER);
    assertEquals(UserPrincipal.allUsers(), acl.getEntity());
    assertEquals(AclEntry.AccessRole.READER, acl.getRole());
    ObjectAccessControl objectPb = acl.toObjectProto();
    assertEquals(acl, AclEntry.fromProto(objectPb));
    BucketAccessControl bucketPb = acl.toBucketProto();
    assertEquals(acl, AclEntry.fromProto(bucketPb));
  }
}
