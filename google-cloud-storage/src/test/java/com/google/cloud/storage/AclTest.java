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
import com.google.cloud.storage.AclEntry.DomainValue;
import com.google.cloud.storage.AclEntry.AbstractEntity;
import com.google.cloud.storage.AclEntry.AbstractEntity.EntityType;
import com.google.cloud.storage.AclEntry.ProjectInfo;
import com.google.cloud.storage.AclEntry.ProjectInfo.ProjectMemberRole;
import com.google.cloud.storage.AclEntry.RawProtoEntity;
import com.google.cloud.storage.AclEntry.AccessRole;
import com.google.cloud.storage.AclEntry.UserIdentity;
import org.junit.Test;

public class AclTest {

  private static final AccessRole ROLE = AccessRole.OWNER;
  private static final AbstractEntity ENTITY = AclEntry.UserIdentity.allAuthenticatedUsers();
  private static final String ETAG = "etag";
  private static final String ID = "id";
  private static final AclEntry ACL = AclEntry.createBuilder(ENTITY, ROLE).setEtag(ETAG).setId(ID).buildEntry();

  @Test
  public void testBuilder() {
    assertEquals(ROLE, ACL.getRole());
    assertEquals(ENTITY, ACL.getEntity());
    assertEquals(ETAG, ACL.getEtag());
    assertEquals(ID, ACL.getId());
  }

  @Test
  public void testToBuilder() {
    assertEquals(ACL, ACL.toEntityBuilder().buildEntry());
    AclEntry acl =
        ACL.toEntityBuilder()
            .setEtag("otherEtag")
            .setId("otherId")
            .setRole(AccessRole.READER)
            .setEntity(AclEntry.UserIdentity.allUsers())
            .buildEntry();
    assertEquals(AccessRole.READER, acl.getRole());
    assertEquals(AclEntry.UserIdentity.allUsers(), acl.getEntity());
    assertEquals("otherEtag", acl.getEtag());
    assertEquals("otherId", acl.getId());
  }

  @Test
  public void testToAndFromPb() {
    assertEquals(ACL, AclEntry.fromObjectPb(ACL.toBucketProto()));
    assertEquals(ACL, AclEntry.fromObjectPb(ACL.toObjectProto()));
  }

  @Test
  public void testDomainEntity() {
    AclEntry.DomainValue acl = new DomainValue("d1");
    assertEquals("d1", acl.getDomain());
    assertEquals(EntityType.DOMAIN, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AclEntry.AbstractEntity.fromProto(pb));
  }

  @Test
  public void testGroupEntity() {
    AclEntry.EmailGroup acl = new AclEntry.EmailGroup("g1");
    assertEquals("g1", acl.getEmail());
    assertEquals(EntityType.GROUP, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testUserEntity() {
    AclEntry.UserIdentity acl = new UserIdentity("u1");
    assertEquals("u1", acl.getEmail());
    assertEquals(EntityType.USER, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testProjectEntity() {
    ProjectInfo acl = new ProjectInfo(AclEntry.ProjectInfo.ProjectMemberRole.VIEWERS, "p1");
    assertEquals(ProjectMemberRole.VIEWERS, acl.getProjectRole());
    assertEquals("p1", acl.getProjectId());
    assertEquals(AbstractEntity.EntityType.PROJECT, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testRawEntity() {
    AbstractEntity acl = new RawProtoEntity("bla");
    assertEquals("bla", acl.getValue());
    assertEquals(EntityType.UNKNOWN, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AbstractEntity.fromProto(pb));
  }

  @Test
  public void testOf() {
    AclEntry acl = AclEntry.ofEntry(AclEntry.UserIdentity.allUsers(), AccessRole.READER);
    assertEquals(UserIdentity.allUsers(), acl.getEntity());
    assertEquals(AccessRole.READER, acl.getRole());
    ObjectAccessControl objectPb = acl.toObjectProto();
    assertEquals(acl, AclEntry.fromObjectPb(objectPb));
    BucketAccessControl bucketPb = acl.toBucketProto();
    assertEquals(acl, AclEntry.fromObjectPb(bucketPb));
  }
}
