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
public final class AclEntry implements Serializable {

    private static final long serialVersionUID = 7516713233557576082L;

    static final Function<ObjectAccessControl, AclEntry> FROM_OBJECT_PROTO_FN = new Function<ObjectAccessControl, AclEntry>() {

        @Override
        public AclEntry apply(ObjectAccessControl aclPb) {
            return AclEntry.fromProto(aclPb);
        }
    };

    static final Function<BucketAccessControl, AclEntry> FROM_BUCKET_PROTO_FN = new Function<BucketAccessControl, AclEntry>() {

        @Override
        public AclEntry apply(BucketAccessControl aclPb) {
            return AclEntry.fromProto(aclPb);
        }
    };

    private final TypedEntity typedItem;

    private final AccessRole accessPermission;

    private final String uniqueKey;

    private final String versionTag;

    public static final class AccessRole extends StringEnumValue {

        private static final long serialVersionUID = 123037132067643600L;

        private static final ApiFunction<String, AccessRole> ACCESS_ROLE_CREATOR = new ApiFunction<String, AccessRole>() {

            @Override
            public AclEntry.AccessRole apply(String constant) {
                return new AccessRole(constant);
            }
        };

        private static final StringEnumType<AccessRole> ACCESS_ROLE_TYPE = new StringEnumType(AccessRole.class, ACCESS_ROLE_CREATOR);

        public static final AccessRole OWNER = ACCESS_ROLE_TYPE.createAndRegister("OWNER");

        public static final AccessRole READER = ACCESS_ROLE_TYPE.createAndRegister("READER");

        public static final AccessRole WRITER = ACCESS_ROLE_TYPE.createAndRegister("WRITER");

        /**
         * Get the Role for the given String constant, and allow unrecognized values.
         */
        public static AccessRole fromString(String valueLiteral) {
            return ACCESS_ROLE_TYPE.valueOf(valueLiteral);
        }

        /**
         * Return the known values for Role.
         */
        public static AccessRole[] values() {
            return ACCESS_ROLE_TYPE.values();
        }

        private AccessRole(String valueLiteral) {
            super(valueLiteral);
        }

        /**
         * Get the Role for the given String constant, and throw an exception if the constant is not
         * recognized.
         */
        public static AccessRole valueOfStrict(String valueLiteral) {
            return ACCESS_ROLE_TYPE.valueOfStrict(valueLiteral);
        }

    }

    /**
     * Builder for {@code Acl} objects.
     */
    public static class EntityRoleBuilder {

        private TypedEntity typedItem;

        private AccessRole accessPermission;

        private String uniqueKey;

        private String versionTag;

        EntityRoleBuilder setEtag(String versionTag) {
            this.versionTag = versionTag;
            return this;
        }

        EntityRoleBuilder setId(String uniqueKey) {
            this.uniqueKey = uniqueKey;
            return this;
        }

        /**
         * Creates an {@code Acl} object from this builder.
         */
        public AclEntry buildEntry() {
            return new AclEntry(this);
        }

        private EntityRoleBuilder(TypedEntity typedItem, AccessRole accessPermission) {
            this.typedItem = typedItem;
            this.accessPermission = accessPermission;
        }

        /**
         * Sets the role to associate to the {@code entity} object.
         */
        public EntityRoleBuilder setRole(AccessRole accessPermission) {
            this.accessPermission = accessPermission;
            return this;
        }

        private EntityRoleBuilder(AclEntry accessEntry) {
            this.typedItem = accessEntry.typedItem;
            this.accessPermission = accessEntry.accessPermission;
            this.uniqueKey = accessEntry.uniqueKey;
            this.versionTag = accessEntry.versionTag;
        }

        /**
         * Sets the entity for the ACL object.
         */
        public EntityRoleBuilder setEntity(TypedEntity typedItem) {
            this.typedItem = typedItem;
            return this;
        }

    }

    /**
     * Base class for Access Control List entities.
     */
    public abstract static class TypedEntity implements Serializable {

        private static final long serialVersionUID = -2707407252771255840L;

        private final EntityType ACCESS_ROLE_TYPE;

        private final String content;

        public enum EntityType {

            DOMAIN, GROUP, USER, PROJECT, UNKNOWN
        }

        @Override
        public int hashCode() {
            return Objects.hash(ACCESS_ROLE_TYPE, content);
        }

        static TypedEntity fromProto(String typedItem) {
            if (typedItem.startsWith("user-")) {
                return new UserPrincipal(typedItem.substring(5));
            }
            if (typedItem.equals(UserPrincipal.EVERY_USER)) {
                return UserPrincipal.allUsers();
            }
            if (typedItem.equals(UserPrincipal.AUTHENTICATED_PRINCIPALS)) {
                return UserPrincipal.allAuthenticatedUsers();
            }
            if (typedItem.startsWith("group-")) {
                return new GroupEntity(typedItem.substring(6));
            }
            if (typedItem.startsWith("domain-")) {
                return new DomainValue(typedItem.substring(7));
            }
            if (typedItem.startsWith("project-")) {
                int index = typedItem.indexOf('-', 8);
                String groupName = typedItem.substring(8, index);
                String projectKey = typedItem.substring(index + 1);
                return new ProjectInfo(ProjectInfo.ProjectRoleType.fromString(groupName.toUpperCase()), projectKey);
            }
            return new RawDataEntity(typedItem);
        }

        TypedEntity(EntityType ACCESS_ROLE_TYPE, String content) {
            this.ACCESS_ROLE_TYPE = ACCESS_ROLE_TYPE;
            this.content = content;
        }

        @Override
        public String toString() {
            return toProtoString();
        }

        String toProtoString() {
            return ACCESS_ROLE_TYPE.name().toLowerCase() + "-" + getValue();
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (null == other || other.getClass() != getClass()) {
                return false;
            }
            TypedEntity typedItem = (TypedEntity) other;
            return Objects.equals(ACCESS_ROLE_TYPE, typedItem.ACCESS_ROLE_TYPE) && Objects.equals(content, typedItem.content);
        }

        /**
         * Returns the entity's value.
         */
        protected String getValue() {
            return content;
        }

        /**
         * Returns the type of entity.
         */
        public EntityType getType() {
            return ACCESS_ROLE_TYPE;
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
         * @param namespace the domain associated to this entity
         */
        public DomainValue(String namespace) {
            super(EntityType.DOMAIN, namespace);
        }

    }

    /**
     * Class for ACL Group entities.
     */
    public static final class GroupEntity extends TypedEntity {

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
         * @param userContact the group email
         */
        public GroupEntity(String userContact) {
            super(EntityType.GROUP, userContact);
        }

    }

    /**
     * Class for ACL User entities.
     */
    public static final class UserPrincipal extends TypedEntity {

        private static final long serialVersionUID = 3076518036392737008L;

        private static final String EVERY_USER = "allUsers";

        private static final String AUTHENTICATED_PRINCIPALS = "allAuthenticatedUsers";

        @Override
        String toProtoString() {
            switch(getValue()) {
                case AUTHENTICATED_PRINCIPALS:
                    return AUTHENTICATED_PRINCIPALS;
                case EVERY_USER:
                    return EVERY_USER;
                default:
                    break;
            }
            return super.toProtoString();
        }

        public static UserPrincipal allUsers() {
            return new UserPrincipal(EVERY_USER);
        }

        public static UserPrincipal allAuthenticatedUsers() {
            return new UserPrincipal(AUTHENTICATED_PRINCIPALS);
        }

        /**
         * Returns the user email.
         */
        public String getEmail() {
            return getValue();
        }

        /**
         * Creates a user entity.
         *
         * @param userContact the user email
         */
        public UserPrincipal(String userContact) {
            super(EntityType.USER, userContact);
        }

    }

    /**
     * Class for ACL Project entities.
     */
    public static final class ProjectInfo extends TypedEntity {

        private static final long serialVersionUID = 7933776866530023027L;

        private final ProjectRoleType roleType;

        private final String projectKey;

        public static final class ProjectRoleType extends StringEnumValue {

            private static final long serialVersionUID = -8360324311187914382L;

            private static final ApiFunction<String, ProjectRoleType> ACCESS_ROLE_CREATOR = new ApiFunction<String, ProjectRoleType>() {

                @Override
                public AclEntry.ProjectInfo.ProjectRoleType apply(String constant) {
                    return new ProjectRoleType(constant);
                }
            };

            private static final StringEnumType<ProjectRoleType> ACCESS_ROLE_TYPE = new StringEnumType(ProjectRoleType.class, ACCESS_ROLE_CREATOR);

            public static final ProjectRoleType OWNERS = ACCESS_ROLE_TYPE.createAndRegister("OWNERS");

            public static final ProjectRoleType EDITORS = ACCESS_ROLE_TYPE.createAndRegister("EDITORS");

            public static final ProjectRoleType VIEWERS = ACCESS_ROLE_TYPE.createAndRegister("VIEWERS");

            /**
             * Get the ProjectRole for the given String constant, and throw an exception if the constant
             * is not recognized.
             */
            public static ProjectRoleType valueOfStrict(String valueLiteral) {
                return ACCESS_ROLE_TYPE.valueOfStrict(valueLiteral);
            }

            /**
             * Return the known values for ProjectRole.
             */
            public static ProjectRoleType[] values() {
                return ACCESS_ROLE_TYPE.values();
            }

            /**
             * Get the ProjectRole for the given String constant, and allow unrecognized values.
             */
            public static ProjectRoleType fromString(String valueLiteral) {
                return ACCESS_ROLE_TYPE.valueOf(valueLiteral);
            }

            private ProjectRoleType(String valueLiteral) {
                super(valueLiteral);
            }

        }

        /**
         * Returns the project id for this entity.
         */
        public String getProjectId() {
            return projectKey;
        }

        /**
         * Returns the role in the project for this entity.
         */
        public ProjectRoleType getProjectRole() {
            return roleType;
        }

        /**
         * Creates a project entity.
         *
         * @param roleType a role in the project, used to select project's teams
         * @param projectKey id of the project
         */
        public ProjectInfo(ProjectRoleType roleType, String projectKey) {
            super(EntityType.PROJECT, roleType.name().toLowerCase() + "-" + projectKey);
            this.roleType = roleType;
            this.projectKey = projectKey;
        }

    }

    public static final class RawDataEntity extends TypedEntity {

        private static final long serialVersionUID = 3966205614223053950L;

        @Override
        String toProtoString() {
            return getValue();
        }

        RawDataEntity(String typedItem) {
            super(EntityType.UNKNOWN, typedItem);
        }

    }

    /**
     * Returns the role associated to the entity in this ACL object.
     */
    public AccessRole getRole() {
        return accessPermission;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (null == other || other.getClass() != getClass()) {
            return false;
        }
        final AclEntry thatEntry = (AclEntry) other;
        return Objects.equals(this.typedItem, thatEntry.typedItem) && Objects.equals(this.accessPermission, thatEntry.accessPermission) && Objects.equals(this.versionTag, thatEntry.versionTag) && Objects.equals(this.uniqueKey, thatEntry.uniqueKey);
    }

    BucketAccessControl toBucketProto() {
        BucketAccessControl bucketProto = new BucketAccessControl();
        bucketProto.setEntity(getEntity().toString());
        bucketProto.setRole(getRole().toString());
        bucketProto.setId(getId());
        bucketProto.setEtag(getEtag());
        return bucketProto;
    }

    /**
     * Returns an {@code Acl} object.
     *
     * @param typedItem the entity for this ACL object
     * @param accessPermission the role to associate to the {@code entity} object
     */
    public static AclEntry ofEntry(TypedEntity typedItem, AccessRole accessPermission) {
        return builder(typedItem, accessPermission).buildEntry();
    }

    ObjectAccessControl toObjectProto() {
        ObjectAccessControl objectProto = new ObjectAccessControl();
        objectProto.setEntity(getEntity().toProtoString());
        objectProto.setRole(getRole().name());
        objectProto.setId(getId());
        objectProto.setEtag(getEtag());
        return objectProto;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("entity", typedItem).add("role", accessPermission).add("etag", versionTag).add("id", uniqueKey).toString();
    }

    static AclEntry fromProto(ObjectAccessControl objAccess) {
        AccessRole accessPermission = AccessRole.fromString(objAccess.getRole());
        TypedEntity typedItem = TypedEntity.fromProto(objAccess.getEntity());
        return builder(typedItem, accessPermission).setEtag(objAccess.getEtag()).setId(objAccess.getId()).buildEntry();
    }

    /**
     * Returns the ID of the ACL entry.
     */
    public String getId() {
        return uniqueKey;
    }

    @Override
    public int hashCode() {
        return Objects.hash(typedItem, accessPermission);
    }

    static AclEntry fromProto(BucketAccessControl bucketAcl) {
        AccessRole accessPermission = AccessRole.fromString(bucketAcl.getRole());
        TypedEntity typedItem = TypedEntity.fromProto(bucketAcl.getEntity());
        return builder(typedItem, accessPermission).setEtag(bucketAcl.getEtag()).setId(bucketAcl.getId()).buildEntry();
    }

    private AclEntry(EntityRoleBuilder roleFactory) {
        this.typedItem = checkNotNull(roleFactory.typedItem);
        this.accessPermission = checkNotNull(roleFactory.accessPermission);
        this.uniqueKey = roleFactory.uniqueKey;
        this.versionTag = roleFactory.versionTag;
    }

    /**
     * Returns the entity for this ACL object.
     */
    public TypedEntity getEntity() {
        return typedItem;
    }

    /**
     * Returns a builder for {@code Acl} objects.
     *
     * @param typedItem the entity for this ACL object
     * @param accessPermission the role to associate to the {@code entity} object
     */
    public static EntityRoleBuilder builder(TypedEntity typedItem, AccessRole accessPermission) {
        return new EntityRoleBuilder(typedItem, accessPermission);
    }

    /**
     * Returns HTTP 1.1 Entity tag for the ACL entry.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return versionTag;
    }

    /**
     * Returns a builder for this {@code Acl} object.
     */
    public EntityRoleBuilder toBuilderInstance() {
        return new EntityRoleBuilder(this);
    }

}
