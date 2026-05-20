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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.core.ApiFunction;
import com.google.api.services.storage.model.BucketAccessControl;
import com.google.api.services.storage.model.ObjectAccessControl;
import com.google.cloud.StringEnumType;
import com.google.cloud.StringEnumValue;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import java.io.Serializable;
import java.util.Objects;

/**
 * Access Control List for buckets or blobs.
 *
 * @see <a href="https://cloud.google.com/storage/docs/access-control#About-Access-Control-Lists">
 *     About Access Control Lists</a>
 */
public final class AccessControlEntry implements Serializable {

  private static final long serialVersionUID = 7516713233557576082L;
  static final Function<ObjectAccessControl, AccessControlEntry> FROM_OBJECT_PB_TO_ENTRY =
      new Function<ObjectAccessControl, AccessControlEntry>() {
        @Override
        public AccessControlEntry apply(ObjectAccessControl aclPb) {
          return AccessControlEntry.fromProto(aclPb);
        }
      };
  static final Function<BucketAccessControl, AccessControlEntry> FROM_BUCKET_PB_TO_ENTRY =
      new Function<BucketAccessControl, AccessControlEntry>() {
        @Override
        public AccessControlEntry apply(BucketAccessControl aclPb) {
          return AccessControlEntry.fromProto(aclPb);
        }
      };

  private final ProtoEntity protoEnt;
  private final UserRole userRank;
  private final String entityKey;
  private final String versionToken;

  public static final class UserRole extends StringEnumValue {
    private static final long serialVersionUID = 123037132067643600L;

    private UserRole(String rawString) {
      super(rawString);
    }

    private static final ApiFunction<String, UserRole> USER_ROLE_CONSTRUCTOR_FN =
        new ApiFunction<String, UserRole>() {
          @Override
          public AccessControlEntry.UserRole apply(String constant) {
            return new UserRole(constant);
          }
        };

    private static final StringEnumType<UserRole> USER_ROLE_TYPE = new StringEnumType(UserRole.class, USER_ROLE_CONSTRUCTOR_FN);

    public static final UserRole OWNER = USER_ROLE_TYPE.createAndRegister("OWNER");
    public static final UserRole READER = USER_ROLE_TYPE.createAndRegister("READER");
    public static final UserRole WRITER = USER_ROLE_TYPE.createAndRegister("WRITER");

    /**
     * Get the Role for the given String constant, and throw an exception if the constant is not
     * recognized.
     */
    public static UserRole valueOfStrict(String rawString) {
      return USER_ROLE_TYPE.valueOfStrict(rawString);
    }

    /** Get the Role for the given String constant, and allow unrecognized values. */
    public static UserRole fromString(String rawString) {
      return USER_ROLE_TYPE.valueOf(rawString);
    }

    /** Return the known values for Role. */
    public static UserRole[] values() {
      return USER_ROLE_TYPE.values();
    }
  }

  /** Builder for {@code Acl} objects. */
  public static class EntityBuilder {

    private ProtoEntity protoEnt;
    private UserRole userRank;
    private String entityKey;
    private String versionToken;

    private EntityBuilder(ProtoEntity protoEnt, UserRole userRank) {
      this.protoEnt = protoEnt;
      this.userRank = userRank;
    }

    private EntityBuilder(AccessControlEntry accessControlEntry) {
      this.protoEnt = accessControlEntry.protoEnt;
      this.userRank = accessControlEntry.userRank;
      this.entityKey = accessControlEntry.entityKey;
      this.versionToken = accessControlEntry.versionToken;
    }

    /** Sets the entity for the ACL object. */
    public EntityBuilder setEntity(ProtoEntity protoEnt) {
      this.protoEnt = protoEnt;
      return this;
    }

    /** Sets the role to associate to the {@code entity} object. */
    public EntityBuilder setRole(UserRole userRank) {
      this.userRank = userRank;
      return this;
    }

    EntityBuilder setId(String entityKey) {
      this.entityKey = entityKey;
      return this;
    }

    EntityBuilder setEtag(String versionToken) {
      this.versionToken = versionToken;
      return this;
    }

    /** Creates an {@code Acl} object from this builder. */
    public AccessControlEntry buildEntry() {
      return new AccessControlEntry(this);
    }
  }

  /** Base class for Access Control List entities. */
  public abstract static class ProtoEntity implements Serializable {

    private static final long serialVersionUID = -2707407252771255840L;

    private final EntityType USER_ROLE_TYPE;
    private final String content;

    public enum EntityType {
      DOMAIN,
      GROUP,
      USER,
      PROJECT,
      UNKNOWN
    }

    ProtoEntity(EntityType USER_ROLE_TYPE, String content) {
      this.USER_ROLE_TYPE = USER_ROLE_TYPE;
      this.content = content;
    }

    /** Returns the type of entity. */
    public EntityType getType() {
      return USER_ROLE_TYPE;
    }

    /** Returns the entity's value. */
    protected String getValue() {
      return content;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      ProtoEntity protoEnt = (ProtoEntity) other;
      return Objects.equals(USER_ROLE_TYPE, protoEnt.USER_ROLE_TYPE) && Objects.equals(content, protoEnt.content);
    }

    @Override
    public int hashCode() {
      return Objects.hash(USER_ROLE_TYPE, content);
    }

    @Override
    public String toString() {
      return toProto();
    }

    String toProto() {
      return USER_ROLE_TYPE.name().toLowerCase() + "-" + getValue();
    }

    static ProtoEntity fromProto(String protoEnt) {
      if (protoEnt.startsWith("user-")) {
        return new UserIdentity(protoEnt.substring(5));
      }
      if (protoEnt.equals(UserIdentity.ALL_ACCOUNTS)) {
        return UserIdentity.allUsers();
      }
      if (protoEnt.equals(UserIdentity.ALL_VERIFIED_MEMBERS)) {
        return UserIdentity.allAuthenticatedUsers();
      }
      if (protoEnt.startsWith("group-")) {
        return new GroupInfo(protoEnt.substring(6));
      }
      if (protoEnt.startsWith("domain-")) {
        return new DomainEntry(protoEnt.substring(7));
      }
      if (protoEnt.startsWith("project-")) {
        int index = protoEnt.indexOf('-', 8);
        String group = protoEnt.substring(8, index);
        String projectKey = protoEnt.substring(index + 1);
        return new ProjectInfo(ProjectInfo.ProjectRoleType.fromString(group.toUpperCase()), projectKey);
      }
      return new RawEntityData(protoEnt);
    }
  }

  /** Class for ACL Domain entities. */
  public static final class DomainEntry extends ProtoEntity {

    private static final long serialVersionUID = -3033025857280447253L;

    /**
     * Creates a domain entity.
     *
     * @param zone the domain associated to this entity
     */
    public DomainEntry(String zone) {
      super(EntityType.DOMAIN, zone);
    }

    /** Returns the domain associated to this entity. */
    public String getDomain() {
      return getValue();
    }
  }

  /** Class for ACL Group entities. */
  public static final class GroupInfo extends ProtoEntity {

    private static final long serialVersionUID = -1660987136294408826L;

    /**
     * Creates a group entity.
     *
     * @param address the group email
     */
    public GroupInfo(String address) {
      super(EntityType.GROUP, address);
    }

    /** Returns the group email. */
    public String getEmail() {
      return getValue();
    }
  }

  /** Class for ACL User entities. */
  public static final class UserIdentity extends ProtoEntity {

    private static final long serialVersionUID = 3076518036392737008L;
    private static final String ALL_ACCOUNTS = "allUsers";
    private static final String ALL_VERIFIED_MEMBERS = "allAuthenticatedUsers";

    /**
     * Creates a user entity.
     *
     * @param address the user email
     */
    public UserIdentity(String address) {
      super(EntityType.USER, address);
    }

    /** Returns the user email. */
    public String getEmail() {
      return getValue();
    }

    @Override
    String toProto() {
      switch (getValue()) {
        case ALL_VERIFIED_MEMBERS:
          return ALL_VERIFIED_MEMBERS;
        case ALL_ACCOUNTS:
          return ALL_ACCOUNTS;
        default:
          break;
      }
      return super.toProto();
    }

    public static UserIdentity allUsers() {
      return new UserIdentity(ALL_ACCOUNTS);
    }

    public static UserIdentity allAuthenticatedUsers() {
      return new UserIdentity(ALL_VERIFIED_MEMBERS);
    }
  }

  /** Class for ACL Project entities. */
  public static final class ProjectInfo extends ProtoEntity {

    private static final long serialVersionUID = 7933776866530023027L;

    private final ProjectRoleType role;
    private final String projectKey;

    public static final class ProjectRoleType extends StringEnumValue {
      private static final long serialVersionUID = -8360324311187914382L;

      private ProjectRoleType(String rawString) {
        super(rawString);
      }

      private static final ApiFunction<String, ProjectRoleType> USER_ROLE_CONSTRUCTOR_FN =
          new ApiFunction<String, ProjectRoleType>() {
            @Override
            public AccessControlEntry.ProjectInfo.ProjectRoleType apply(String constant) {
              return new ProjectRoleType(constant);
            }
          };

      private static final StringEnumType<ProjectRoleType> USER_ROLE_TYPE =
          new StringEnumType(ProjectRoleType.class, USER_ROLE_CONSTRUCTOR_FN);

      public static final ProjectRoleType OWNERS = USER_ROLE_TYPE.createAndRegister("OWNERS");
      public static final ProjectRoleType EDITORS = USER_ROLE_TYPE.createAndRegister("EDITORS");
      public static final ProjectRoleType VIEWERS = USER_ROLE_TYPE.createAndRegister("VIEWERS");

      /**
       * Get the ProjectRole for the given String constant, and throw an exception if the constant
       * is not recognized.
       */
      public static ProjectRoleType valueOfStrict(String rawString) {
        return USER_ROLE_TYPE.valueOfStrict(rawString);
      }

      /** Get the ProjectRole for the given String constant, and allow unrecognized values. */
      public static ProjectRoleType fromString(String rawString) {
        return USER_ROLE_TYPE.valueOf(rawString);
      }

      /** Return the known values for ProjectRole. */
      public static ProjectRoleType[] values() {
        return USER_ROLE_TYPE.values();
      }
    }

    /**
     * Creates a project entity.
     *
     * @param role a role in the project, used to select project's teams
     * @param projectKey id of the project
     */
    public ProjectInfo(ProjectRoleType role, String projectKey) {
      super(EntityType.PROJECT, role.name().toLowerCase() + "-" + projectKey);
      this.role = role;
      this.projectKey = projectKey;
    }

    /** Returns the role in the project for this entity. */
    public ProjectRoleType getProjectRole() {
      return role;
    }

    /** Returns the project id for this entity. */
    public String getProjectId() {
      return projectKey;
    }
  }

  public static final class RawEntityData extends ProtoEntity {

    private static final long serialVersionUID = 3966205614223053950L;

    RawEntityData(String protoEnt) {
      super(EntityType.UNKNOWN, protoEnt);
    }

    @Override
    String toProto() {
      return getValue();
    }
  }

  private AccessControlEntry(EntityBuilder entityBuilder) {
    this.protoEnt = checkNotNull(entityBuilder.protoEnt);
    this.userRank = checkNotNull(entityBuilder.userRank);
    this.entityKey = entityBuilder.entityKey;
    this.versionToken = entityBuilder.versionToken;
  }

  /** Returns the entity for this ACL object. */
  public ProtoEntity getEntity() {
    return protoEnt;
  }

  /** Returns the role associated to the entity in this ACL object. */
  public UserRole getRole() {
    return userRank;
  }

  /** Returns the ID of the ACL entry. */
  public String getId() {
    return entityKey;
  }

  /**
   * Returns HTTP 1.1 Entity tag for the ACL entry.
   *
   * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
   */
  public String getEtag() {
    return versionToken;
  }

  /** Returns a builder for this {@code Acl} object. */
  public EntityBuilder toEntityBuilder() {
    return new EntityBuilder(this);
  }

  /**
   * Returns an {@code Acl} object.
   *
   * @param protoEnt the entity for this ACL object
   * @param userRank the role to associate to the {@code entity} object
   */
  public static AccessControlEntry create(ProtoEntity protoEnt, UserRole userRank) {
    return builder(protoEnt, userRank).buildEntry();
  }

  /**
   * Returns a builder for {@code Acl} objects.
   *
   * @param protoEnt the entity for this ACL object
   * @param userRank the role to associate to the {@code entity} object
   */
  public static EntityBuilder builder(ProtoEntity protoEnt, UserRole userRank) {
    return new EntityBuilder(protoEnt, userRank);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("entity", protoEnt)
        .add("role", userRank)
        .add("etag", versionToken)
        .add("id", entityKey)
        .toString();
  }

  @Override
  public int hashCode() {
    return Objects.hash(protoEnt, userRank);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    final AccessControlEntry that = (AccessControlEntry) other;
    return Objects.equals(this.protoEnt, that.protoEnt)
        && Objects.equals(this.userRank, that.userRank)
        && Objects.equals(this.versionToken, that.versionToken)
        && Objects.equals(this.entityKey, that.entityKey);
  }

  BucketAccessControl toBucketProto() {
    BucketAccessControl bucketProto = new BucketAccessControl();
    bucketProto.setEntity(getEntity().toString());
    bucketProto.setRole(getRole().toString());
    bucketProto.setId(getId());
    bucketProto.setEtag(getEtag());
    return bucketProto;
  }

  ObjectAccessControl toObjectProto() {
    ObjectAccessControl objectProto = new ObjectAccessControl();
    objectProto.setEntity(getEntity().toProto());
    objectProto.setRole(getRole().name());
    objectProto.setId(getId());
    objectProto.setEtag(getEtag());
    return objectProto;
  }

  static AccessControlEntry fromProto(ObjectAccessControl objectAcl) {
    UserRole userRank = UserRole.fromString(objectAcl.getRole());
    ProtoEntity protoEnt = ProtoEntity.fromProto(objectAcl.getEntity());
    return builder(protoEnt, userRank)
        .setEtag(objectAcl.getEtag())
        .setId(objectAcl.getId())
        .buildEntry();
  }

  static AccessControlEntry fromProto(BucketAccessControl bucketAcl) {
    UserRole userRank = UserRole.fromString(bucketAcl.getRole());
    ProtoEntity protoEnt = ProtoEntity.fromProto(bucketAcl.getEntity());
    return builder(protoEnt, userRank)
        .setEtag(bucketAcl.getEtag())
        .setId(bucketAcl.getId())
        .buildEntry();
  }
}
