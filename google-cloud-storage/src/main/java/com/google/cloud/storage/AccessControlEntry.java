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
  static final Function<ObjectAccessControl, AccessControlEntry> OBJECT_PB_TO_ACE_FN =
      new Function<ObjectAccessControl, AccessControlEntry>() {
        @Override
        public AccessControlEntry apply(ObjectAccessControl aclPb) {
          return AccessControlEntry.fromProto(aclPb);
        }
      };
  static final Function<BucketAccessControl, AccessControlEntry> BUCKET_PB_TO_ACE_FN =
      new Function<BucketAccessControl, AccessControlEntry>() {
        @Override
        public AccessControlEntry apply(BucketAccessControl aclPb) {
          return AccessControlEntry.fromProto(aclPb);
        }
      };

  private final TypedEntity typedEntity;
  private final AccessRole accessRole;
  private final String identifier;
  private final String entityTag;

  public static final class AccessRole extends StringEnumValue {
    private static final long serialVersionUID = 123037132067643600L;

    private AccessRole(String value) {
      super(value);
    }

    private static final ApiFunction<String, AccessRole> ROLE_CONSTRUCTOR_FN =
        new ApiFunction<String, AccessRole>() {
          @Override
          public AccessControlEntry.AccessRole apply(String constant) {
            return new AccessRole(constant);
          }
        };

    private static final StringEnumType<AccessRole> ROLE_TYPE = new StringEnumType(AccessRole.class, ROLE_CONSTRUCTOR_FN);

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
    public static AccessRole fromValue(String value) {
      return ROLE_TYPE.valueOf(value);
    }

    /** Return the known values for Role. */
    public static AccessRole[] values() {
      return ROLE_TYPE.values();
    }
  }

  /** Builder for {@code Acl} objects. */
  public static class EntityDescriptorBuilder {

    private TypedEntity typedEntity;
    private AccessRole accessRole;
    private String identifier;
    private String entityTag;

    private EntityDescriptorBuilder(TypedEntity typedEntity, AccessRole accessRole) {
      this.typedEntity = typedEntity;
      this.accessRole = accessRole;
    }

    private EntityDescriptorBuilder(AccessControlEntry accessControlEntry) {
      this.typedEntity = accessControlEntry.typedEntity;
      this.accessRole = accessControlEntry.accessRole;
      this.identifier = accessControlEntry.identifier;
      this.entityTag = accessControlEntry.entityTag;
    }

    /** Sets the entity for the ACL object. */
    public EntityDescriptorBuilder setEntity(TypedEntity typedEntity) {
      this.typedEntity = typedEntity;
      return this;
    }

    /** Sets the role to associate to the {@code entity} object. */
    public EntityDescriptorBuilder setRole(AccessRole accessRole) {
      this.accessRole = accessRole;
      return this;
    }

    EntityDescriptorBuilder setId(String identifier) {
      this.identifier = identifier;
      return this;
    }

    EntityDescriptorBuilder setEtag(String entityTag) {
      this.entityTag = entityTag;
      return this;
    }

    /** Creates an {@code Acl} object from this builder. */
    public AccessControlEntry create() {
      return new AccessControlEntry(this);
    }
  }

  /** Base class for Access Control List entities. */
  public abstract static class TypedEntity implements Serializable {

    private static final long serialVersionUID = -2707407252771255840L;

    private final EntityType ROLE_TYPE;
    private final String valueText;

    public enum EntityType {
      DOMAIN,
      GROUP,
      USER,
      PROJECT,
      UNKNOWN
    }

    TypedEntity(EntityType ROLE_TYPE, String valueText) {
      this.ROLE_TYPE = ROLE_TYPE;
      this.valueText = valueText;
    }

    /** Returns the type of entity. */
    public EntityType getType() {
      return ROLE_TYPE;
    }

    /** Returns the entity's value. */
    protected String getValue() {
      return valueText;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }
      TypedEntity typedEntity = (TypedEntity) other;
      return Objects.equals(ROLE_TYPE, typedEntity.ROLE_TYPE) && Objects.equals(valueText, typedEntity.valueText);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ROLE_TYPE, valueText);
    }

    @Override
    public String toString() {
      return toProto();
    }

    String toProto() {
      return ROLE_TYPE.name().toLowerCase() + "-" + getValue();
    }

    static TypedEntity fromProto(String typedEntity) {
      if (typedEntity.startsWith("user-")) {
        return new UserPrincipal(typedEntity.substring(5));
      }
      if (typedEntity.equals(UserPrincipal.EVERY_USER)) {
        return UserPrincipal.allUsers();
      }
      if (typedEntity.equals(UserPrincipal.AUTHENTICATED_PRINCIPALS)) {
        return UserPrincipal.allAuthenticatedUsers();
      }
      if (typedEntity.startsWith("group-")) {
        return new MailingGroup(typedEntity.substring(6));
      }
      if (typedEntity.startsWith("domain-")) {
        return new DomainValue(typedEntity.substring(7));
      }
      if (typedEntity.startsWith("project-")) {
        int index = typedEntity.indexOf('-', 8);
        String teamName = typedEntity.substring(8, index);
        String projectIdentifier = typedEntity.substring(index + 1);
        return new ProjectEntity(ProjectEntity.ProjectMemberRole.fromValue(teamName.toUpperCase()), projectIdentifier);
      }
      return new RawEntityWrapper(typedEntity);
    }
  }

  /** Class for ACL Domain entities. */
  public static final class DomainValue extends TypedEntity {

    private static final long serialVersionUID = -3033025857280447253L;

    /**
     * Creates a domain entity.
     *
     * @param domainName the domain associated to this entity
     */
    public DomainValue(String domainName) {
      super(EntityType.DOMAIN, domainName);
    }

    /** Returns the domain associated to this entity. */
    public String getDomain() {
      return getValue();
    }
  }

  /** Class for ACL Group entities. */
  public static final class MailingGroup extends TypedEntity {

    private static final long serialVersionUID = -1660987136294408826L;

    /**
     * Creates a group entity.
     *
     * @param address the group email
     */
    public MailingGroup(String address) {
      super(EntityType.GROUP, address);
    }

    /** Returns the group email. */
    public String getEmail() {
      return getValue();
    }
  }

  /** Class for ACL User entities. */
  public static final class UserPrincipal extends TypedEntity {

    private static final long serialVersionUID = 3076518036392737008L;
    private static final String EVERY_USER = "allUsers";
    private static final String AUTHENTICATED_PRINCIPALS = "allAuthenticatedUsers";

    /**
     * Creates a user entity.
     *
     * @param address the user email
     */
    public UserPrincipal(String address) {
      super(EntityType.USER, address);
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
        case EVERY_USER:
          return EVERY_USER;
        default:
          break;
      }
      return super.toProto();
    }

    public static UserPrincipal allUsers() {
      return new UserPrincipal(EVERY_USER);
    }

    public static UserPrincipal allAuthenticatedUsers() {
      return new UserPrincipal(AUTHENTICATED_PRINCIPALS);
    }
  }

  /** Class for ACL Project entities. */
  public static final class ProjectEntity extends TypedEntity {

    private static final long serialVersionUID = 7933776866530023027L;

    private final ProjectMemberRole memberRole;
    private final String projectIdentifier;

    public static final class ProjectMemberRole extends StringEnumValue {
      private static final long serialVersionUID = -8360324311187914382L;

      private ProjectMemberRole(String value) {
        super(value);
      }

      private static final ApiFunction<String, ProjectMemberRole> ROLE_CONSTRUCTOR_FN =
          new ApiFunction<String, ProjectMemberRole>() {
            @Override
            public AccessControlEntry.ProjectEntity.ProjectMemberRole apply(String constant) {
              return new ProjectMemberRole(constant);
            }
          };

      private static final StringEnumType<ProjectMemberRole> ROLE_TYPE =
          new StringEnumType(ProjectMemberRole.class, ROLE_CONSTRUCTOR_FN);

      public static final ProjectMemberRole OWNERS = ROLE_TYPE.createAndRegister("OWNERS");
      public static final ProjectMemberRole EDITORS = ROLE_TYPE.createAndRegister("EDITORS");
      public static final ProjectMemberRole VIEWERS = ROLE_TYPE.createAndRegister("VIEWERS");

      /**
       * Get the ProjectRole for the given String constant, and throw an exception if the constant
       * is not recognized.
       */
      public static ProjectMemberRole valueOfStrict(String value) {
        return ROLE_TYPE.valueOfStrict(value);
      }

      /** Get the ProjectRole for the given String constant, and allow unrecognized values. */
      public static ProjectMemberRole fromValue(String value) {
        return ROLE_TYPE.valueOf(value);
      }

      /** Return the known values for ProjectRole. */
      public static ProjectMemberRole[] values() {
        return ROLE_TYPE.values();
      }
    }

    /**
     * Creates a project entity.
     *
     * @param memberRole a role in the project, used to select project's teams
     * @param projectIdentifier id of the project
     */
    public ProjectEntity(ProjectMemberRole memberRole, String projectIdentifier) {
      super(EntityType.PROJECT, memberRole.name().toLowerCase() + "-" + projectIdentifier);
      this.memberRole = memberRole;
      this.projectIdentifier = projectIdentifier;
    }

    /** Returns the role in the project for this entity. */
    public ProjectMemberRole getProjectRole() {
      return memberRole;
    }

    /** Returns the project id for this entity. */
    public String getProjectId() {
      return projectIdentifier;
    }
  }

  public static final class RawEntityWrapper extends TypedEntity {

    private static final long serialVersionUID = 3966205614223053950L;

    RawEntityWrapper(String typedEntity) {
      super(EntityType.UNKNOWN, typedEntity);
    }

    @Override
    String toProto() {
      return getValue();
    }
  }

  private AccessControlEntry(EntityDescriptorBuilder descriptor) {
    this.typedEntity = checkNotNull(descriptor.typedEntity);
    this.accessRole = checkNotNull(descriptor.accessRole);
    this.identifier = descriptor.identifier;
    this.entityTag = descriptor.entityTag;
  }

  /** Returns the entity for this ACL object. */
  public TypedEntity getEntity() {
    return typedEntity;
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
    return entityTag;
  }

  /** Returns a builder for this {@code Acl} object. */
  public EntityDescriptorBuilder asBuilder() {
    return new EntityDescriptorBuilder(this);
  }

  /**
   * Returns an {@code Acl} object.
   *
   * @param typedEntity the entity for this ACL object
   * @param accessRole the role to associate to the {@code entity} object
   */
  public static AccessControlEntry create(TypedEntity typedEntity, AccessRole accessRole) {
    return builder(typedEntity, accessRole).create();
  }

  /**
   * Returns a builder for {@code Acl} objects.
   *
   * @param typedEntity the entity for this ACL object
   * @param accessRole the role to associate to the {@code entity} object
   */
  public static EntityDescriptorBuilder builder(TypedEntity typedEntity, AccessRole accessRole) {
    return new EntityDescriptorBuilder(typedEntity, accessRole);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("entity", typedEntity)
        .add("role", accessRole)
        .add("etag", entityTag)
        .add("id", identifier)
        .toString();
  }

  @Override
  public int hashCode() {
    return Objects.hash(typedEntity, accessRole);
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
    return Objects.equals(this.typedEntity, thatEntry.typedEntity)
        && Objects.equals(this.accessRole, thatEntry.accessRole)
        && Objects.equals(this.entityTag, thatEntry.entityTag)
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
    AccessRole accessRole = AccessRole.fromValue(objectAcl.getRole());
    TypedEntity typedEntity = TypedEntity.fromProto(objectAcl.getEntity());
    return builder(typedEntity, accessRole)
        .setEtag(objectAcl.getEtag())
        .setId(objectAcl.getId())
        .create();
  }

  static AccessControlEntry fromProto(BucketAccessControl bucketAcl) {
    AccessRole accessRole = AccessRole.fromValue(bucketAcl.getRole());
    TypedEntity typedEntity = TypedEntity.fromProto(bucketAcl.getEntity());
    return builder(typedEntity, accessRole)
        .setEtag(bucketAcl.getEtag())
        .setId(bucketAcl.getId())
        .create();
  }
}
