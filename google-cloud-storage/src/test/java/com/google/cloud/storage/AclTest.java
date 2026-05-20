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
import com.google.cloud.storage.AccessControlEntry.DomainValue;
import com.google.cloud.storage.AccessControlEntry.TypedEntity;
import com.google.cloud.storage.AccessControlEntry.TypedEntity.EntityType;
import com.google.cloud.storage.AccessControlEntry.MailingGroup;
import com.google.cloud.storage.AccessControlEntry.ProjectEntity;
import com.google.cloud.storage.AccessControlEntry.ProjectEntity.ProjectMemberRole;
import com.google.cloud.storage.AccessControlEntry.AccessRole;
import com.google.cloud.storage.AccessControlEntry.UserPrincipal;
import org.junit.Test;

public class AclTest {

  private static final AccessRole ROLE = AccessControlEntry.AccessRole.OWNER;
  private static final AccessControlEntry.TypedEntity ENTITY = UserPrincipal.allAuthenticatedUsers();
  private static final String ETAG = "etag";
  private static final String ID = "id";
  private static final AccessControlEntry ACL = AccessControlEntry.builder(ENTITY, ROLE).setEtag(ETAG).setId(ID).create();

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
    AccessControlEntry acl =
        ACL.asBuilder()
            .setEtag("otherEtag")
            .setId("otherId")
            .setRole(AccessRole.READER)
            .setEntity(UserPrincipal.allUsers())
            .create();
    assertEquals(AccessControlEntry.AccessRole.READER, acl.getRole());
    assertEquals(UserPrincipal.allUsers(), acl.getEntity());
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
    AccessControlEntry.DomainValue acl = new DomainValue("d1");
    assertEquals("d1", acl.getDomain());
    assertEquals(EntityType.DOMAIN, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AccessControlEntry.TypedEntity.fromProto(pb));
  }

  @Test
  public void testGroupEntity() {
    MailingGroup acl = new MailingGroup("g1");
    assertEquals("g1", acl.getEmail());
    assertEquals(EntityType.GROUP, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AccessControlEntry.TypedEntity.fromProto(pb));
  }

  @Test
  public void testUserEntity() {
    UserPrincipal acl = new UserPrincipal("u1");
    assertEquals("u1", acl.getEmail());
    assertEquals(TypedEntity.EntityType.USER, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AccessControlEntry.TypedEntity.fromProto(pb));
  }

  @Test
  public void testProjectEntity() {
    ProjectEntity acl = new ProjectEntity(ProjectEntity.ProjectMemberRole.VIEWERS, "p1");
    assertEquals(ProjectMemberRole.VIEWERS, acl.getProjectRole());
    assertEquals("p1", acl.getProjectId());
    assertEquals(EntityType.PROJECT, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AccessControlEntry.TypedEntity.fromProto(pb));
  }

  @Test
  public void testRawEntity() {
    AccessControlEntry.TypedEntity acl = new AccessControlEntry.RawEntityWrapper("bla");
    assertEquals("bla", acl.getValue());
    assertEquals(AccessControlEntry.TypedEntity.EntityType.UNKNOWN, acl.getType());
    String pb = acl.toProto();
    assertEquals(acl, AccessControlEntry.TypedEntity.fromProto(pb));
  }

  @Test
  public void testOf() {
    AccessControlEntry acl = AccessControlEntry.create(UserPrincipal.allUsers(), AccessControlEntry.AccessRole.READER);
    assertEquals(UserPrincipal.allUsers(), acl.getEntity());
    assertEquals(AccessRole.READER, acl.getRole());
    ObjectAccessControl objectPb = acl.toObjectProto();
    assertEquals(acl, AccessControlEntry.fromProto(objectPb));
    BucketAccessControl bucketPb = acl.toBucketProto();
    assertEquals(acl, AccessControlEntry.fromProto(bucketPb));
  }
}
