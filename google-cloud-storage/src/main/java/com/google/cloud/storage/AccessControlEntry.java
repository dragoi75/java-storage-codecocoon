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
  static final Function<ObjectAccessControl, AccessControlEntry> OBJECT_PB_TO_ACCESS_CONTROL_ENTRY =
      new Function<ObjectAccessControl, AccessControlEntry>() {
        @Override
        public AccessControlEntry apply(ObjectAccessControl aclPb) {
          return AccessControlEntry.fromProto(aclPb);
        }
      };
  static final Function<BucketAccessControl, AccessControlEntry> BUCKET_PB_TO_ACCESS_CONTROL_ENTRY =
      new Function<BucketAccessControl, AccessControlEntry>() {
        @Override
        public AccessControlEntry apply(BucketAccessControl aclPb) {
          return AccessControlEntry.fromProto(aclPb);
        }
      };

  private final AbstractEntity entityRef;
  private final AccessRole accessRole;
  private final String identifier;
  private final String versionTag;

  public static final class AccessRole extends StringEnumValue {
    private static final long serialVersionUID = 123037132067643600L;

    private AccessRole(String value) {
      super(value);
    }

    private static final ApiFunction<String, AccessRole> STRING_TO_ACCESS_ROLE =
        new ApiFunction<String, AccessRole>() {
          @Override
          public AccessControlEntry.AccessRole apply(String constant) {
            return new AccessRole(constant);
          }
        };

    private static final StringEnumType<AccessRole> ROLE_TYPE = new StringEnumType(AccessRole.class, STRING_TO_ACCESS_ROLE);

    public static final AccessRole OWNER = ROLE_TYPE.createAndRegister("OWNER");
    public static final AccessRole READER = ROLE_TYPE.createAndRegister("READER");
    public static final AccessRole WRITER = ROLE_TYPE.createAndRegister("WRITER");

    /**
     * Get the Role for the given String constant, and throw an exception if the constant is not
     * recognized.
     */
    public static AccessRole valueOfStrict(String value) {
      return ROLE_TYPE.valueOfStrict(value);
    }

    /** Get the Role for the given String constant, and allow unrecognized values. */
    public static AccessRole fromString(String value) {
      return ROLE_TYPE.valueOf(value);
    }

    /** Return the known values for Role. */
    public static AccessRole[] values() {
      return ROLE_TYPE.values();
    }
  }

  /** Builder for {@code Acl} objects. */
  public static class EntityBuilder {

    private AbstractEntity entityRef;
    private AccessRole accessRole;
    private String identifier;
    private String versionTag;

    private EntityBuilder(AbstractEntity entityRef, AccessRole accessRole) {
      this.entityRef = entityRef;
      this.accessRole = accessRole;
    }

    private EntityBuilder(AccessControlEntry accessControlEntry) {
      this.entityRef = accessControlEntry.entityRef;
      this.accessRole = accessControlEntry.accessRole;
      this.identifier = accessControlEntry.identifier;
      this.versionTag = accessControlEntry.versionTag;
    }

    /** Sets the entity for the ACL object. */
    public EntityBuilder setEntity(AbstractEntity entityRef) {
      this.entityRef = entityRef;
      return this;
    }

    /** Sets the role to associate to the {@code entity} object. */
    public EntityBuilder setRole(AccessRole accessRole) {
      this.accessRole = accessRole;
      return this;
    }

    EntityBuilder setId(String identifier) {
      this.identifier = identifier;
      return this;
    }

    EntityBuilder setEtag(String versionTag) {
      this.versionTag = versionTag;
      return this;
    }

    /** Creates an {@code Acl} object from this builder. */
    public AccessControlEntry create() {
      return new AccessControlEntry(this);
    }
  }

  /** Base class for Access Control List entities. */
  public abstract static class AbstractEntity implements Serializable {

    private static final long serialVersionUID = -2707407252771255840L;

    private final ResourceType ROLE_TYPE;
    private final String valueStr;

    public enum ResourceType {
      DOMAIN,
      GROUP,
      USER,
      PROJECT,
      UNKNOWN
    }

    AbstractEntity(ResourceType ROLE_TYPE, String valueStr) {
      this.ROLE_TYPE = ROLE_TYPE;
      this.valueStr = valueStr;
    }

    /** Returns the type of entity. */
    public ResourceType getType() {
      return ROLE_TYPE;
    }

    /** Returns the entity's value. */
    protected String getValue() {
      return valueStr;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      AbstractEntity entityRef = (AbstractEntity) other;
      return Objects.equals(ROLE_TYPE, entityRef.ROLE_TYPE) && Objects.equals(valueStr, entityRef.valueStr);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ROLE_TYPE, valueStr);
    }

    @Override
    public String toString() {
      return toProto();
    }

    String toProto() {
      return ROLE_TYPE.name().toLowerCase() + "-" + getValue();
    }

    static AbstractEntity fromProto(String entityRef) {
      if (entityRef.startsWith("user-")) {
        return new UserPrincipal(entityRef.substring(5));
      }
      if (entityRef.equals(UserPrincipal.EVERYONE)) {
        return UserPrincipal.allUsers();
      }
      if (entityRef.equals(UserPrincipal.AUTHENTICATED_PRINCIPALS)) {
        return UserPrincipal.allAuthenticatedUsers();
      }
      if (entityRef.startsWith("group-")) {
        return new EmailGroup(entityRef.substring(6));
      }
      if (entityRef.startsWith("domain-")) {
        return new DomainValue(entityRef.substring(7));
      }
      if (entityRef.startsWith("project-")) {
        int index = entityRef.indexOf('-', 8);
        String teamStr = entityRef.substring(8, index);
        String projectIdentifier = entityRef.substring(index + 1);
        return new ProjectInfo(ProjectInfo.ProjectRoleType.fromString(teamStr.toUpperCase()), projectIdentifier);
      }
      return new RawEntityData(entityRef);
    }
  }

  /** Class for ACL Domain entities. */
  public static final class DomainValue extends AbstractEntity {

    private static final long serialVersionUID = -3033025857280447253L;

    /**
     * Creates a domain entity.
     *
     * @param domainStr the domain associated to this entity
     */
    public DomainValue(String domainStr) {
      super(ResourceType.DOMAIN, domainStr);
    }

    /** Returns the domain associated to this entity. */
    public String getDomain() {
      return getValue();
    }
  }

  /** Class for ACL Group entities. */
  public static final class EmailGroup extends AbstractEntity {

    private static final long serialVersionUID = -1660987136294408826L;

    /**
     * Creates a group entity.
     *
     * @param mailAddress the group email
     */
    public EmailGroup(String mailAddress) {
      super(ResourceType.GROUP, mailAddress);
    }

    /** Returns the group email. */
    public String getEmail() {
      return getValue();
    }
  }

  /** Class for ACL User entities. */
  public static final class UserPrincipal extends AbstractEntity {

    private static final long serialVersionUID = 3076518036392737008L;
    private static final String EVERYONE = "allUsers";
    private static final String AUTHENTICATED_PRINCIPALS = "allAuthenticatedUsers";

    /**
     * Creates a user entity.
     *
     * @param mailAddress the user email
     */
    public UserPrincipal(String mailAddress) {
      super(ResourceType.USER, mailAddress);
    }

    /** Returns the user email. */
    public String getEmail() {
      return getValue();
    }

    @Override
    String toProto() {
      switch (getValue()) {
        case AUTHENTICATED_PRINCIPALS:
          return AUTHENTICATED_PRINCIPALS;
        case EVERYONE:
          return EVERYONE;
        default:
          break;
      }
      return super.toProto();
    }

    public static UserPrincipal allUsers() {
      return new UserPrincipal(EVERYONE);
    }

    public static UserPrincipal allAuthenticatedUsers() {
      return new UserPrincipal(AUTHENTICATED_PRINCIPALS);
    }
  }

  /** Class for ACL Project entities. */
  public static final class ProjectInfo extends AbstractEntity {

    private static final long serialVersionUID = 7933776866530023027L;

    private final ProjectRoleType roleInProject;
    private final String projectIdentifier;

    public static final class ProjectRoleType extends StringEnumValue {
      private static final long serialVersionUID = -8360324311187914382L;

      private ProjectRoleType(String value) {
        super(value);
      }

      private static final ApiFunction<String, ProjectRoleType> STRING_TO_ACCESS_ROLE =
          new ApiFunction<String, ProjectRoleType>() {
            @Override
            public AccessControlEntry.ProjectInfo.ProjectRoleType apply(String constant) {
              return new ProjectRoleType(constant);
            }
          };

      private static final StringEnumType<ProjectRoleType> ROLE_TYPE =
          new StringEnumType(ProjectRoleType.class, STRING_TO_ACCESS_ROLE);

      public static final ProjectRoleType OWNERS = ROLE_TYPE.createAndRegister("OWNERS");
      public static final ProjectRoleType EDITORS = ROLE_TYPE.createAndRegister("EDITORS");
      public static final ProjectRoleType VIEWERS = ROLE_TYPE.createAndRegister("VIEWERS");

      /**
       * Get the ProjectRole for the given String constant, and throw an exception if the constant
       * is not recognized.
       */
      public static ProjectRoleType valueOfStrict(String value) {
        return ROLE_TYPE.valueOfStrict(value);
      }

      /** Get the ProjectRole for the given String constant, and allow unrecognized values. */
      public static ProjectRoleType fromString(String value) {
        return ROLE_TYPE.valueOf(value);
      }

      /** Return the known values for ProjectRole. */
      public static ProjectRoleType[] values() {
        return ROLE_TYPE.values();
      }
    }

    /**
     * Creates a project entity.
     *
     * @param roleInProject a role in the project, used to select project's teams
     * @param projectIdentifier id of the project
     */
    public ProjectInfo(ProjectRoleType roleInProject, String projectIdentifier) {
      super(ResourceType.PROJECT, roleInProject.name().toLowerCase() + "-" + projectIdentifier);
      this.roleInProject = roleInProject;
      this.projectIdentifier = projectIdentifier;
    }

    /** Returns the role in the project for this entity. */
    public ProjectRoleType getProjectRole() {
      return roleInProject;
    }

    /** Returns the project id for this entity. */
    public String getProjectId() {
      return projectIdentifier;
    }
  }

  public static final class RawEntityData extends AbstractEntity {

    private static final long serialVersionUID = 3966205614223053950L;

    RawEntityData(String entityRef) {
      super(ResourceType.UNKNOWN, entityRef);
    }

    @Override
    String toProto() {
      return getValue();
    }
  }

  private AccessControlEntry(EntityBuilder entityFactory) {
    this.entityRef = checkNotNull(entityFactory.entityRef);
    this.accessRole = checkNotNull(entityFactory.accessRole);
    this.identifier = entityFactory.identifier;
    this.versionTag = entityFactory.versionTag;
  }

  /** Returns the entity for this ACL object. */
  public AbstractEntity getEntity() {
    return entityRef;
  }

  /** Returns the role associated to the entity in this ACL object. */
  public AccessRole getRole() {
    return accessRole;
  }

  /** Returns the ID of the ACL entry. */
  public String getId() {
    return identifier;
  }

  /**
   * Returns HTTP 1.1 Entity tag for the ACL entry.
   *
   * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
   */
  public String getEtag() {
    return versionTag;
  }

  /** Returns a builder for this {@code Acl} object. */
  public EntityBuilder asBuilder() {
    return new EntityBuilder(this);
  }

  /**
   * Returns an {@code Acl} object.
   *
   * @param entityRef the entity for this ACL object
   * @param accessRole the role to associate to the {@code entity} object
   */
  public static AccessControlEntry create(AbstractEntity entityRef, AccessRole accessRole) {
    return builder(entityRef, accessRole).create();
  }

  /**
   * Returns a builder for {@code Acl} objects.
   *
   * @param entityRef the entity for this ACL object
   * @param accessRole the role to associate to the {@code entity} object
   */
  public static EntityBuilder builder(AbstractEntity entityRef, AccessRole accessRole) {
    return new EntityBuilder(entityRef, accessRole);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("entity", entityRef)
        .add("role", accessRole)
        .add("etag", versionTag)
        .add("id", identifier)
        .toString();
  }

  @Override
  public int hashCode() {
    return Objects.hash(entityRef, accessRole);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    final AccessControlEntry thatEntry = (AccessControlEntry) other;
    return Objects.equals(this.entityRef, thatEntry.entityRef)
        && Objects.equals(this.accessRole, thatEntry.accessRole)
        && Objects.equals(this.versionTag, thatEntry.versionTag)
        && Objects.equals(this.identifier, thatEntry.identifier);
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
    AccessRole accessRole = AccessRole.fromString(objectAcl.getRole());
    AbstractEntity entityRef = AbstractEntity.fromProto(objectAcl.getEntity());
    return builder(entityRef, accessRole)
        .setEtag(objectAcl.getEtag())
        .setId(objectAcl.getId())
        .create();
  }

  static AccessControlEntry fromProto(BucketAccessControl bucketAcl) {
    AccessRole accessRole = AccessRole.fromString(bucketAcl.getRole());
    AbstractEntity entityRef = AbstractEntity.fromProto(bucketAcl.getEntity());
    return builder(entityRef, accessRole)
        .setEtag(bucketAcl.getEtag())
        .setId(bucketAcl.getId())
        .create();
  }
}
