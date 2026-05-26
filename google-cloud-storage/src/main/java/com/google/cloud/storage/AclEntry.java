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

    static final Function<ObjectAccessControl, AclEntry> FROM_OBJECT_PROTO_FUNCTION = new Function<ObjectAccessControl, AclEntry>() {

        @Override
        public AclEntry apply(ObjectAccessControl aclPb) {
            return AclEntry.fromObjectPb(aclPb);
        }
    };

    static final Function<BucketAccessControl, AclEntry> FROM_BUCKET_PROTO_FUNCTION = new Function<BucketAccessControl, AclEntry>() {

        @Override
        public AclEntry apply(BucketAccessControl aclPb) {
            return AclEntry.fromObjectPb(aclPb);
        }
    };

    private final AbstractEntity principal;

    private final AccessRole permission;

    private final String key;

    private final String versionTag;

    public static final class AccessRole extends StringEnumValue {

        private static final long serialVersionUID = 123037132067643600L;

        private static final ApiFunction<String, AccessRole> ACCESS_ROLE_FACTORY = new ApiFunction<String, AccessRole>() {

            @Override
            public AclEntry.AccessRole apply(String constant) {
                return new AccessRole(constant);
            }
        };

        private static final StringEnumType<AccessRole> ACCESS_ROLE_ENUM = new StringEnumType(AccessRole.class, ACCESS_ROLE_FACTORY);

        public static final AccessRole OWNER = ACCESS_ROLE_ENUM.createAndRegister("OWNER");

        public static final AccessRole READER = ACCESS_ROLE_ENUM.createAndRegister("READER");

        public static final AccessRole WRITER = ACCESS_ROLE_ENUM.createAndRegister("WRITER");

        /**
         * Get the Role for the given String constant, and allow unrecognized values.
         */
        public static AccessRole fromValue(String value) {
            return ACCESS_ROLE_ENUM.valueOf(value);
        }

        /**
         * Return the known values for Role.
         */
        public static AccessRole[] values() {
            return ACCESS_ROLE_ENUM.values();
        }

        private AccessRole(String value) {
            super(value);
        }

        /**
         * Get the Role for the given String constant, and throw an exception if the constant is not
         * recognized.
         */
        public static AccessRole valueOfStrict(String value) {
            return ACCESS_ROLE_ENUM.valueOfStrict(value);
        }

    }

    /**
     * Builder for {@code Acl} objects.
     */
    public static class EntityBuilder {

        private AbstractEntity principal;

        private AccessRole permission;

        private String key;

        private String versionTag;

        EntityBuilder setEtag(String versionTag) {
            this.versionTag = versionTag;
            return this;
        }

        EntityBuilder setId(String key) {
            this.key = key;
            return this;
        }

        /**
         * Sets the entity for the ACL object.
         */
        public EntityBuilder setEntity(AbstractEntity principal) {
            this.principal = principal;
            return this;
        }

        /**
         * Sets the role to associate to the {@code entity} object.
         */
        public EntityBuilder setRole(AccessRole permission) {
            this.permission = permission;
            return this;
        }

        /**
         * Creates an {@code Acl} object from this builder.
         */
        public AclEntry buildEntry() {
            return new AclEntry(this);
        }

        private EntityBuilder(AbstractEntity principal, AccessRole permission) {
            this.principal = principal;
            this.permission = permission;
        }

        private EntityBuilder(AclEntry accessControlEntry) {
            this.principal = accessControlEntry.principal;
            this.permission = accessControlEntry.permission;
            this.key = accessControlEntry.key;
            this.versionTag = accessControlEntry.versionTag;
        }

    }

    /**
     * Base class for Access Control List entities.
     */
    public abstract static class AbstractEntity implements Serializable {

        private static final long serialVersionUID = -2707407252771255840L;

        private final EntityType ACCESS_ROLE_ENUM;

        private final String content;

        public enum EntityType {

            DOMAIN, GROUP, USER, PROJECT, UNKNOWN
        }

        static AbstractEntity fromProto(String principal) {
            if (principal.startsWith("user-")) {
                return new UserIdentity(principal.substring(5));
            }
            if (principal.equals(UserIdentity.EVERYONE)) {
                return UserIdentity.allUsers();
            }
            if (principal.equals(UserIdentity.AUTHENTICATED_PRINCIPALS)) {
                return UserIdentity.allAuthenticatedUsers();
            }
            if (principal.startsWith("group-")) {
                return new EmailGroup(principal.substring(6));
            }
            if (principal.startsWith("domain-")) {
                return new DomainValue(principal.substring(7));
            }
            if (principal.startsWith("project-")) {
                int index = principal.indexOf('-', 8);
                String group = principal.substring(8, index);
                String projectKey = principal.substring(index + 1);
                return new ProjectInfo(ProjectInfo.ProjectMemberRole.fromValue(group.toUpperCase()), projectKey);
            }
            return new RawProtoEntity(principal);
        }

        String toProto() {
            return ACCESS_ROLE_ENUM.name().toLowerCase() + "-" + getValue();
        }

        @Override
        public int hashCode() {
            return Objects.hash(ACCESS_ROLE_ENUM, content);
        }

        /**
         * Returns the type of entity.
         */
        public EntityType getType() {
            return ACCESS_ROLE_ENUM;
        }

        @Override
        public String toString() {
            return toProto();
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (null == other || other.getClass() != getClass()) {
                return false;
            }
            AbstractEntity principal = (AbstractEntity) other;
            return Objects.equals(ACCESS_ROLE_ENUM, principal.ACCESS_ROLE_ENUM) && Objects.equals(content, principal.content);
        }

        AbstractEntity(EntityType ACCESS_ROLE_ENUM, String content) {
            this.ACCESS_ROLE_ENUM = ACCESS_ROLE_ENUM;
            this.content = content;
        }

        /**
         * Returns the entity's value.
         */
        protected String getValue() {
            return content;
        }

    }

    /**
     * Class for ACL Domain entities.
     */
    public static final class DomainValue extends AbstractEntity {

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
         * @param realm the domain associated to this entity
         */
        public DomainValue(String realm) {
            super(EntityType.DOMAIN, realm);
        }

    }

    /**
     * Class for ACL Group entities.
     */
    public static final class EmailGroup extends AbstractEntity {

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
         * @param address the group email
         */
        public EmailGroup(String address) {
            super(EntityType.GROUP, address);
        }

    }

    /**
     * Class for ACL User entities.
     */
    public static final class UserIdentity extends AbstractEntity {

        private static final long serialVersionUID = 3076518036392737008L;

        private static final String EVERYONE = "allUsers";

        private static final String AUTHENTICATED_PRINCIPALS = "allAuthenticatedUsers";

        @Override
        String toProto() {
            switch(getValue()) {
                case AUTHENTICATED_PRINCIPALS:
                    return AUTHENTICATED_PRINCIPALS;
                case EVERYONE:
                    return EVERYONE;
                default:
                    break;
            }
            return super.toProto();
        }

        public static UserIdentity allUsers() {
            return new UserIdentity(EVERYONE);
        }

        public static UserIdentity allAuthenticatedUsers() {
            return new UserIdentity(AUTHENTICATED_PRINCIPALS);
        }

        /**
         * Creates a user entity.
         *
         * @param address the user email
         */
        public UserIdentity(String address) {
            super(EntityType.USER, address);
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
    public static final class ProjectInfo extends AbstractEntity {

        private static final long serialVersionUID = 7933776866530023027L;

        private final ProjectMemberRole assignedRole;

        private final String projectKey;

        public static final class ProjectMemberRole extends StringEnumValue {

            private static final long serialVersionUID = -8360324311187914382L;

            private static final ApiFunction<String, ProjectMemberRole> ACCESS_ROLE_FACTORY = new ApiFunction<String, ProjectMemberRole>() {

                @Override
                public AclEntry.ProjectInfo.ProjectMemberRole apply(String constant) {
                    return new ProjectMemberRole(constant);
                }
            };

            private static final StringEnumType<ProjectMemberRole> ACCESS_ROLE_ENUM = new StringEnumType(ProjectMemberRole.class, ACCESS_ROLE_FACTORY);

            public static final ProjectMemberRole OWNERS = ACCESS_ROLE_ENUM.createAndRegister("OWNERS");

            public static final ProjectMemberRole EDITORS = ACCESS_ROLE_ENUM.createAndRegister("EDITORS");

            public static final ProjectMemberRole VIEWERS = ACCESS_ROLE_ENUM.createAndRegister("VIEWERS");

            /**
             * Get the ProjectRole for the given String constant, and allow unrecognized values.
             */
            public static ProjectMemberRole fromValue(String value) {
                return ACCESS_ROLE_ENUM.valueOf(value);
            }

            /**
             * Return the known values for ProjectRole.
             */
            public static ProjectMemberRole[] values() {
                return ACCESS_ROLE_ENUM.values();
            }

            private ProjectMemberRole(String value) {
                super(value);
            }

            /**
             * Get the ProjectRole for the given String constant, and throw an exception if the constant
             * is not recognized.
             */
            public static ProjectMemberRole valueOfStrict(String value) {
                return ACCESS_ROLE_ENUM.valueOfStrict(value);
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
        public ProjectMemberRole getProjectRole() {
            return assignedRole;
        }

        /**
         * Creates a project entity.
         *
         * @param assignedRole a role in the project, used to select project's teams
         * @param projectKey id of the project
         */
        public ProjectInfo(ProjectMemberRole assignedRole, String projectKey) {
            super(EntityType.PROJECT, assignedRole.name().toLowerCase() + "-" + projectKey);
            this.assignedRole = assignedRole;
            this.projectKey = projectKey;
        }

    }

    public static final class RawProtoEntity extends AbstractEntity {

        private static final long serialVersionUID = 3966205614223053950L;

        @Override
        String toProto() {
            return getValue();
        }

        RawProtoEntity(String principal) {
            super(EntityType.UNKNOWN, principal);
        }

    }

    /**
     * Returns an {@code Acl} object.
     *
     * @param principal the entity for this ACL object
     * @param permission the role to associate to the {@code entity} object
     */
    public static AclEntry ofEntry(AbstractEntity principal, AccessRole permission) {
        return createBuilder(principal, permission).buildEntry();
    }

    static AclEntry fromObjectPb(ObjectAccessControl accessControl) {
        AccessRole permission = AccessRole.fromValue(accessControl.getRole());
        AbstractEntity principal = AbstractEntity.fromProto(accessControl.getEntity());
        return createBuilder(principal, permission).setEtag(accessControl.getEtag()).setId(accessControl.getId()).buildEntry();
    }

    /**
     * Returns a builder for {@code Acl} objects.
     *
     * @param principal the entity for this ACL object
     * @param permission the role to associate to the {@code entity} object
     */
    public static EntityBuilder createBuilder(AbstractEntity principal, AccessRole permission) {
        return new EntityBuilder(principal, permission);
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
     * Returns a builder for this {@code Acl} object.
     */
    public EntityBuilder toEntityBuilder() {
        return new EntityBuilder(this);
    }

    /**
     * Returns the role associated to the entity in this ACL object.
     */
    public AccessRole getRole() {
        return permission;
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
        final AclEntry compared = (AclEntry) other;
        return Objects.equals(this.principal, compared.principal) && Objects.equals(this.permission, compared.permission) && Objects.equals(this.versionTag, compared.versionTag) && Objects.equals(this.key, compared.key);
    }

    static AclEntry fromObjectPb(BucketAccessControl bucketControl) {
        AccessRole permission = AccessRole.fromValue(bucketControl.getRole());
        AbstractEntity principal = AbstractEntity.fromProto(bucketControl.getEntity());
        return createBuilder(principal, permission).setEtag(bucketControl.getEtag()).setId(bucketControl.getId()).buildEntry();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("entity", principal).add("role", permission).add("etag", versionTag).add("id", key).toString();
    }

    /**
     * Returns the entity for this ACL object.
     */
    public AbstractEntity getEntity() {
        return principal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(principal, permission);
    }

    /**
     * Returns the ID of the ACL entry.
     */
    public String getId() {
        return key;
    }

    /**
     * Returns HTTP 1.1 Entity tag for the ACL entry.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return versionTag;
    }

    private AclEntry(EntityBuilder entityFactory) {
        this.principal = checkNotNull(entityFactory.principal);
        this.permission = checkNotNull(entityFactory.permission);
        this.key = entityFactory.key;
        this.versionTag = entityFactory.versionTag;
    }

}
