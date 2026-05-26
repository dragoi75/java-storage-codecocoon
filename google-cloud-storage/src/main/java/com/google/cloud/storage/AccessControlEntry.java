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

    static final Function<ObjectAccessControl, AccessControlEntry> OBJECT_PB_TO_ENTRY_FN = new Function<ObjectAccessControl, AccessControlEntry>() {

        @Override
        public AccessControlEntry apply(ObjectAccessControl aclPb) {
            return AccessControlEntry.fromProto(aclPb);
        }
    };

    static final Function<BucketAccessControl, AccessControlEntry> BUCKET_PB_TO_ENTRY_FN = new Function<BucketAccessControl, AccessControlEntry>() {

        @Override
        public AccessControlEntry apply(BucketAccessControl aclPb) {
            return AccessControlEntry.fromProto(aclPb);
        }
    };

    private final TypedEntity typedEntity;

    private final UserRole userRole;

    private final String identifier;

    private final String entityTag;

    public static final class UserRole extends StringEnumValue {

        private static final long serialVersionUID = 123037132067643600L;

        private static final ApiFunction<String, UserRole> USER_ROLE_CONSTRUCTOR_FN = new ApiFunction<String, UserRole>() {

            @Override
            public AccessControlEntry.UserRole apply(String constant) {
                return new UserRole(constant);
            }
        };

        private static final StringEnumType<UserRole> ROLE_TYPE = new StringEnumType(UserRole.class, USER_ROLE_CONSTRUCTOR_FN);

        public static final UserRole OWNER = ROLE_TYPE.createAndRegister("OWNER");

        public static final UserRole READER = ROLE_TYPE.createAndRegister("READER");

        public static final UserRole WRITER = ROLE_TYPE.createAndRegister("WRITER");

        /**
         * Return the known values for Role.
         */
        public static UserRole[] values() {
            return ROLE_TYPE.values();
        }

        /**
         * Get the Role for the given String constant, and allow unrecognized values.
         */
        public static UserRole fromString(String constantValue) {
            return ROLE_TYPE.valueOf(constantValue);
        }

        /**
         * Get the Role for the given String constant, and throw an exception if the constant is not
         * recognized.
         */
        public static UserRole valueOfStrict(String constantValue) {
            return ROLE_TYPE.valueOfStrict(constantValue);
        }

        private UserRole(String constantValue) {
            super(constantValue);
        }

    }

    /**
     * Builder for {@code Acl} objects.
     */
    public static class EntityBuilder {

        private TypedEntity typedEntity;

        private UserRole userRole;

        private String identifier;

        private String entityTag;

        EntityBuilder setEtag(String entityTag) {
            this.entityTag = entityTag;
            return this;
        }

        /**
         * Sets the role to associate to the {@code entity} object.
         */
        public EntityBuilder setRole(UserRole userRole) {
            this.userRole = userRole;
            return this;
        }

        EntityBuilder setId(String identifier) {
            this.identifier = identifier;
            return this;
        }

        /**
         * Creates an {@code Acl} object from this builder.
         */
        public AccessControlEntry buildEntry() {
            return new AccessControlEntry(this);
        }

        /**
         * Sets the entity for the ACL object.
         */
        public EntityBuilder setEntity(TypedEntity typedEntity) {
            this.typedEntity = typedEntity;
            return this;
        }

        private EntityBuilder(AccessControlEntry accessControlEntry) {
            this.typedEntity = accessControlEntry.typedEntity;
            this.userRole = accessControlEntry.userRole;
            this.identifier = accessControlEntry.identifier;
            this.entityTag = accessControlEntry.entityTag;
        }

        private EntityBuilder(TypedEntity typedEntity, UserRole userRole) {
            this.typedEntity = typedEntity;
            this.userRole = userRole;
        }

    }

    /**
     * Base class for Access Control List entities.
     */
    public abstract static class TypedEntity implements Serializable {

        private static final long serialVersionUID = -2707407252771255840L;

        private final EntityType ROLE_TYPE;

        private final String valueStr;

        public enum EntityType {

            DOMAIN, GROUP, USER, PROJECT, UNKNOWN
        }

        String toProto() {
            return ROLE_TYPE.name().toLowerCase() + "-" + getValue();
        }

        static TypedEntity fromProto(String typedEntity) {
            if (typedEntity.startsWith("user-")) {
                return new UserPrincipal(typedEntity.substring(5));
            }
            if (typedEntity.equals(UserPrincipal.ALL_ACCOUNTS)) {
                return UserPrincipal.allUsers();
            }
            if (typedEntity.equals(UserPrincipal.ALL_AUTHENTICATED_PRINCIPALS)) {
                return UserPrincipal.allAuthenticatedUsers();
            }
            if (typedEntity.startsWith("group-")) {
                return new GroupInfo(typedEntity.substring(6));
            }
            if (typedEntity.startsWith("domain-")) {
                return new DomainValue(typedEntity.substring(7));
            }
            if (typedEntity.startsWith("project-")) {
                int index = typedEntity.indexOf('-', 8);
                String teamName = typedEntity.substring(8, index);
                String projectIdentifier = typedEntity.substring(index + 1);
                return new ProjectInfo(ProjectInfo.ProjectUserRole.fromString(teamName.toUpperCase()), projectIdentifier);
            }
            return new RawDataEntity(typedEntity);
        }

        @Override
        public String toString() {
            return toProto();
        }

        TypedEntity(EntityType ROLE_TYPE, String valueStr) {
            this.ROLE_TYPE = ROLE_TYPE;
            this.valueStr = valueStr;
        }

        /**
         * Returns the entity's value.
         */
        protected String getValue() {
            return valueStr;
        }

        /**
         * Returns the type of entity.
         */
        public EntityType getType() {
            return ROLE_TYPE;
        }

        @Override
        public int hashCode() {
            return Objects.hash(ROLE_TYPE, valueStr);
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (null == other || other.getClass() != getClass()) {
                return false;
            }
            TypedEntity typedEntity = (TypedEntity) other;
            return Objects.equals(ROLE_TYPE, typedEntity.ROLE_TYPE) && Objects.equals(valueStr, typedEntity.valueStr);
        }

    }

    /**
     * Class for ACL Domain entities.
     */
    public static final class DomainValue extends TypedEntity {

        private static final long serialVersionUID = -3033025857280447253L;

        /**
         * Returns the domain associated to this entity.
         */
        public String getDomain() {
            return getValue();
        }

        /**
         * Creates a domain entity.
         *
         * @param domainValue the domain associated to this entity
         */
        public DomainValue(String domainValue) {
            super(EntityType.DOMAIN, domainValue);
        }

    }

    /**
     * Class for ACL Group entities.
     */
    public static final class GroupInfo extends TypedEntity {

        private static final long serialVersionUID = -1660987136294408826L;

        /**
         * Returns the group email.
         */
        public String getEmail() {
            return getValue();
        }

        /**
         * Creates a group entity.
         *
         * @param contactAddress the group email
         */
        public GroupInfo(String contactAddress) {
            super(EntityType.GROUP, contactAddress);
        }

    }

    /**
     * Class for ACL User entities.
     */
    public static final class UserPrincipal extends TypedEntity {

        private static final long serialVersionUID = 3076518036392737008L;

        private static final String ALL_ACCOUNTS = "allUsers";

        private static final String ALL_AUTHENTICATED_PRINCIPALS = "allAuthenticatedUsers";

        @Override
        String toProto() {
            switch(getValue()) {
                case ALL_AUTHENTICATED_PRINCIPALS:
                    return ALL_AUTHENTICATED_PRINCIPALS;
                case ALL_ACCOUNTS:
                    return ALL_ACCOUNTS;
                default:
                    break;
            }
            return super.toProto();
        }

        public static UserPrincipal allAuthenticatedUsers() {
            return new UserPrincipal(ALL_AUTHENTICATED_PRINCIPALS);
        }

        /**
         * Creates a user entity.
         *
         * @param contactAddress the user email
         */
        public UserPrincipal(String contactAddress) {
            super(EntityType.USER, contactAddress);
        }

        public static UserPrincipal allUsers() {
            return new UserPrincipal(ALL_ACCOUNTS);
        }

        /**
         * Returns the user email.
         */
        public String getEmail() {
            return getValue();
        }

    }

    /**
     * Class for ACL Project entities.
     */
    public static final class ProjectInfo extends TypedEntity {

        private static final long serialVersionUID = 7933776866530023027L;

        private final ProjectUserRole roleInProject;

        private final String projectIdentifier;

        public static final class ProjectUserRole extends StringEnumValue {

            private static final long serialVersionUID = -8360324311187914382L;

            private static final ApiFunction<String, ProjectUserRole> USER_ROLE_CONSTRUCTOR_FN = new ApiFunction<String, ProjectUserRole>() {

                @Override
                public AccessControlEntry.ProjectInfo.ProjectUserRole apply(String constant) {
                    return new ProjectUserRole(constant);
                }
            };

            private static final StringEnumType<ProjectUserRole> ROLE_TYPE = new StringEnumType(ProjectUserRole.class, USER_ROLE_CONSTRUCTOR_FN);

            public static final ProjectUserRole OWNERS = ROLE_TYPE.createAndRegister("OWNERS");

            public static final ProjectUserRole EDITORS = ROLE_TYPE.createAndRegister("EDITORS");

            public static final ProjectUserRole VIEWERS = ROLE_TYPE.createAndRegister("VIEWERS");

            /**
             * Return the known values for ProjectRole.
             */
            public static ProjectUserRole[] values() {
                return ROLE_TYPE.values();
            }

            /**
             * Get the ProjectRole for the given String constant, and allow unrecognized values.
             */
            public static ProjectUserRole fromString(String constantValue) {
                return ROLE_TYPE.valueOf(constantValue);
            }

            /**
             * Get the ProjectRole for the given String constant, and throw an exception if the constant
             * is not recognized.
             */
            public static ProjectUserRole valueOfStrict(String constantValue) {
                return ROLE_TYPE.valueOfStrict(constantValue);
            }

            private ProjectUserRole(String constantValue) {
                super(constantValue);
            }

        }

        /**
         * Returns the project id for this entity.
         */
        public String getProjectId() {
            return projectIdentifier;
        }

        /**
         * Returns the role in the project for this entity.
         */
        public ProjectUserRole getProjectRole() {
            return roleInProject;
        }

        /**
         * Creates a project entity.
         *
         * @param roleInProject a role in the project, used to select project's teams
         * @param projectIdentifier id of the project
         */
        public ProjectInfo(ProjectUserRole roleInProject, String projectIdentifier) {
            super(EntityType.PROJECT, roleInProject.name().toLowerCase() + "-" + projectIdentifier);
            this.roleInProject = roleInProject;
            this.projectIdentifier = projectIdentifier;
        }

    }

    public static final class RawDataEntity extends TypedEntity {

        private static final long serialVersionUID = 3966205614223053950L;

        @Override
        String toProto() {
            return getValue();
        }

        RawDataEntity(String typedEntity) {
            super(EntityType.UNKNOWN, typedEntity);
        }

    }

    BucketAccessControl toBucketProto() {
        BucketAccessControl bucketProto = new BucketAccessControl();
        bucketProto.setEntity(getEntity().toString());
        bucketProto.setRole(getRole().toString());
        bucketProto.setId(getId());
        bucketProto.setEtag(getEtag());
        return bucketProto;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (null == other || other.getClass() != getClass()) {
            return false;
        }
        final AccessControlEntry otherEntry = (AccessControlEntry) other;
        return Objects.equals(this.typedEntity, otherEntry.typedEntity) && Objects.equals(this.userRole, otherEntry.userRole) && Objects.equals(this.entityTag, otherEntry.entityTag) && Objects.equals(this.identifier, otherEntry.identifier);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("entity", typedEntity).add("role", userRole).add("etag", entityTag).add("id", identifier).toString();
    }

    private AccessControlEntry(EntityBuilder entryBuilder) {
        this.typedEntity = checkNotNull(entryBuilder.typedEntity);
        this.userRole = checkNotNull(entryBuilder.userRole);
        this.identifier = entryBuilder.identifier;
        this.entityTag = entryBuilder.entityTag;
    }

    static AccessControlEntry fromProto(BucketAccessControl bucketAcl) {
        UserRole userRole = UserRole.fromString(bucketAcl.getRole());
        TypedEntity typedEntity = TypedEntity.fromProto(bucketAcl.getEntity());
        return builder(typedEntity, userRole).setEtag(bucketAcl.getEtag()).setId(bucketAcl.getId()).buildEntry();
    }

    static AccessControlEntry fromProto(ObjectAccessControl objectAcl) {
        UserRole userRole = UserRole.fromString(objectAcl.getRole());
        TypedEntity typedEntity = TypedEntity.fromProto(objectAcl.getEntity());
        return builder(typedEntity, userRole).setEtag(objectAcl.getEtag()).setId(objectAcl.getId()).buildEntry();
    }

    ObjectAccessControl toObjectProto() {
        ObjectAccessControl objectProto = new ObjectAccessControl();
        objectProto.setEntity(getEntity().toProto());
        objectProto.setRole(getRole().name());
        objectProto.setId(getId());
        objectProto.setEtag(getEtag());
        return objectProto;
    }

    /**
     * Returns a builder for {@code Acl} objects.
     *
     * @param typedEntity the entity for this ACL object
     * @param userRole the role to associate to the {@code entity} object
     */
    public static EntityBuilder builder(TypedEntity typedEntity, UserRole userRole) {
        return new EntityBuilder(typedEntity, userRole);
    }

    /**
     * Returns HTTP 1.1 Entity tag for the ACL entry.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return entityTag;
    }

    @Override
    public int hashCode() {
        return Objects.hash(typedEntity, userRole);
    }

    /**
     * Returns an {@code Acl} object.
     *
     * @param typedEntity the entity for this ACL object
     * @param userRole the role to associate to the {@code entity} object
     */
    public static AccessControlEntry create(TypedEntity typedEntity, UserRole userRole) {
        return builder(typedEntity, userRole).buildEntry();
    }

    /**
     * Returns the entity for this ACL object.
     */
    public TypedEntity getEntity() {
        return typedEntity;
    }

    /**
     * Returns the role associated to the entity in this ACL object.
     */
    public UserRole getRole() {
        return userRole;
    }

    /**
     * Returns the ID of the ACL entry.
     */
    public String getId() {
        return identifier;
    }

    /**
     * Returns a builder for this {@code Acl} object.
     */
    public EntityBuilder toEntityBuilder() {
        return new EntityBuilder(this);
    }

}
