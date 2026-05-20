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
import com.google.cloud.storage.AccessControlEntry.AbstractEntity;
import com.google.cloud.storage.AccessControlEntry.AbstractEntity.EntityType;
import com.google.cloud.storage.AccessControlEntry.EmailGroup;
import com.google.cloud.storage.AccessControlEntry.ProjectInfo.ProjectMemberRole;
import com.google.cloud.storage.AccessControlEntry.RawDataEntity;
import com.google.cloud.storage.AccessControlEntry.UserIdentity;
import org.junit.Test;

public class AclTest {

  private static final AccessControlEntry.RoleType ROLE = AccessControlEntry.RoleType.OWNER;
  private static final AbstractEntity ENTITY = AccessControlEntry.UserIdentity.allAuthenticatedUsers();
  private static final String ETAG = "etag";
  private static final String ID = "id";
  private static final AccessControlEntry ACL = AccessControlEntry.builder(ENTITY, ROLE).setEtag(ETAG).setId(ID).buildInstance();

  @Test
  public void testBuilder() {
    assertEquals(ROLE, ACL.getRole());
    assertEquals(ENTITY, ACL.getEntity());
    assertEquals(ETAG, ACL.getEtag());
    assertEquals(ID, ACL.getId());
  }

  @Test
  public void testToBuilder() {
    assertEquals(ACL, ACL.asBuilder().buildInstance());
    AccessControlEntry acl =
        ACL.asBuilder()
            .setEtag("otherEtag")
            .setId("otherId")
            .setRole(AccessControlEntry.RoleType.READER)
            .setEntity(UserIdentity.allUsers())
            .buildInstance();
    assertEquals(AccessControlEntry.RoleType.READER, acl.getRole());
    assertEquals(AccessControlEntry.UserIdentity.allUsers(), acl.getEntity());
    assertEquals("otherEtag", acl.getEtag());
    assertEquals("otherId", acl.getId());
  }

  @Test
  public void testToAndFromPb() {
    assertEquals(ACL, AccessControlEntry.fromProto(ACL.toBucketProto()));
    assertEquals(ACL, AccessControlEntry.fromProto(ACL.toObjectProto()));
  }

  @Test
  public void testDomainEntity() {
    AccessControlEntry.DomainValue acl = new AccessControlEntry.DomainValue("d1");
    assertEquals("d1", acl.getDomain());
    assertEquals(AbstractEntity.EntityType.DOMAIN, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testGroupEntity() {
    EmailGroup acl = new AccessControlEntry.EmailGroup("g1");
    assertEquals("g1", acl.getEmail());
    assertEquals(EntityType.GROUP, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testUserEntity() {
    AccessControlEntry.UserIdentity acl = new AccessControlEntry.UserIdentity("u1");
    assertEquals("u1", acl.getEmail());
    assertEquals(AbstractEntity.EntityType.USER, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testProjectEntity() {
    AccessControlEntry.ProjectInfo acl = new AccessControlEntry.ProjectInfo(ProjectMemberRole.VIEWERS, "p1");
    assertEquals(ProjectMemberRole.VIEWERS, acl.getProjectRole());
    assertEquals("p1", acl.getProjectId());
    assertEquals(AbstractEntity.EntityType.PROJECT, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testRawEntity() {
    AbstractEntity acl = new RawDataEntity("bla");
    assertEquals("bla", acl.getValue());
    assertEquals(EntityType.UNKNOWN, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testOf() {
    AccessControlEntry acl = AccessControlEntry.create(UserIdentity.allUsers(), AccessControlEntry.RoleType.READER);
    assertEquals(AccessControlEntry.UserIdentity.allUsers(), acl.getEntity());
    assertEquals(AccessControlEntry.RoleType.READER, acl.getRole());
    ObjectAccessControl objectPb = acl.toObjectProto();
    assertEquals(acl, AccessControlEntry.fromProto(objectPb));
    BucketAccessControl bucketPb = acl.toBucketProto();
    assertEquals(acl, AccessControlEntry.fromProto(bucketPb));
  }
}
