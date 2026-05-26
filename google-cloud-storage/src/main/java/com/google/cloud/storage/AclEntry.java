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

    static final Function<ObjectAccessControl, AclEntry> OBJECT_PB_TO_ACL_ENTRY_FN = new Function<ObjectAccessControl, AclEntry>() {

        @Override
        public AclEntry apply(ObjectAccessControl aclPb) {
            return AclEntry.fromProto(aclPb);
        }
    };

    static final Function<BucketAccessControl, AclEntry> BUCKET_PB_TO_ACL_ENTRY_FN = new Function<BucketAccessControl, AclEntry>() {

        @Override
        public AclEntry apply(BucketAccessControl aclPb) {
            return AclEntry.fromProto(aclPb);
        }
    };

    private final BaseEntity subject;

    private final AccessRole accessLevel;

    private final String key;

    private final String versionToken;

    public static final class AccessRole extends StringEnumValue {

        private static final long serialVersionUID = 123037132067643600L;

        private static final ApiFunction<String, AccessRole> ACCESS_ROLE_CONSTRUCTOR_FN = new ApiFunction<String, AccessRole>() {

            @Override
            public AclEntry.AccessRole apply(String constant) {
                return new AccessRole(constant);
            }
        };

        private static final StringEnumType<AccessRole> ACCESS_ROLE_ENUM_TYPE = new StringEnumType(AccessRole.class, ACCESS_ROLE_CONSTRUCTOR_FN);

        public static final AccessRole OWNER = ACCESS_ROLE_ENUM_TYPE.createAndRegister("OWNER");

        public static final AccessRole READER = ACCESS_ROLE_ENUM_TYPE.createAndRegister("READER");

        public static final AccessRole WRITER = ACCESS_ROLE_ENUM_TYPE.createAndRegister("WRITER");

        /**
         * Get the Role for the given String constant, and allow unrecognized values.
         */
        public static AccessRole fromString(String value) {
            return ACCESS_ROLE_ENUM_TYPE.valueOf(value);
        }

        /**
         * Return the known values for Role.
         */
        public static AccessRole[] values() {
            return ACCESS_ROLE_ENUM_TYPE.values();
        }

        /**
         * Get the Role for the given String constant, and throw an exception if the constant is not
         * recognized.
         */
        public static AccessRole valueOfStrict(String value) {
            return ACCESS_ROLE_ENUM_TYPE.valueOfStrict(value);
        }

        private AccessRole(String value) {
            super(value);
        }

    }

    /**
     * Builder for {@code Acl} objects.
     */
    public static class EntityBuilder {

        private BaseEntity subject;

        private AccessRole accessLevel;

        private String key;

        private String versionToken;

        /**
         * Sets the role to associate to the {@code entity} object.
         */
        public EntityBuilder setRole(AccessRole accessLevel) {
            this.accessLevel = accessLevel;
            return this;
        }

        EntityBuilder setEtag(String versionToken) {
            this.versionToken = versionToken;
            return this;
        }

        EntityBuilder setId(String key) {
            this.key = key;
            return this;
        }

        /**
         * Creates an {@code Acl} object from this builder.
         */
        public AclEntry create() {
            return new AclEntry(this);
        }

        private EntityBuilder(BaseEntity subject, AccessRole accessLevel) {
            this.subject = subject;
            this.accessLevel = accessLevel;
        }

        /**
         * Sets the entity for the ACL object.
         */
        public EntityBuilder setEntity(BaseEntity subject) {
            this.subject = subject;
            return this;
        }

        private EntityBuilder(AclEntry accessControlEntry) {
            this.subject = accessControlEntry.subject;
            this.accessLevel = accessControlEntry.accessLevel;
            this.key = accessControlEntry.key;
            this.versionToken = accessControlEntry.versionToken;
        }

    }

    /**
     * Base class for Access Control List entities.
     */
    public abstract static class BaseEntity implements Serializable {

        private static final long serialVersionUID = -2707407252771255840L;

        private final EntityType ACCESS_ROLE_ENUM_TYPE;

        private final String content;

        public enum EntityType {

            DOMAIN, GROUP, USER, PROJECT, UNKNOWN
        }

        static BaseEntity fromProto(String subject) {
            if (subject.startsWith("user-")) {
                return new UserPrincipal(subject.substring(5));
            }
            if (subject.equals(UserPrincipal.ALL_ACCOUNTS)) {
                return UserPrincipal.allUsers();
            }
            if (subject.equals(UserPrincipal.AUTHENTICATED_ACCOUNTS)) {
                return UserPrincipal.allAuthenticatedUsers();
            }
            if (subject.startsWith("group-")) {
                return new EmailGroup(subject.substring(6));
            }
            if (subject.startsWith("domain-")) {
                return new DomainInfo(subject.substring(7));
            }
            if (subject.startsWith("project-")) {
                int index = subject.indexOf('-', 8);
                String groupName = subject.substring(8, index);
                String projectKey = subject.substring(index + 1);
                return new ProjectInfo(ProjectInfo.ProjectAccessLevel.fromString(groupName.toUpperCase()), projectKey);
            }
            return new RawDataEntity(subject);
        }

        @Override
        public String toString() {
            return toProto();
        }

        String toProto() {
            return ACCESS_ROLE_ENUM_TYPE.name().toLowerCase() + "-" + getValue();
        }

        /**
         * Returns the type of entity.
         */
        public EntityType getType() {
            return ACCESS_ROLE_ENUM_TYPE;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (null == other || other.getClass() != getClass()) {
                return false;
            }
            BaseEntity subject = (BaseEntity) other;
            return Objects.equals(ACCESS_ROLE_ENUM_TYPE, subject.ACCESS_ROLE_ENUM_TYPE) && Objects.equals(content, subject.content);
        }

        /**
         * Returns the entity's value.
         */
        protected String getValue() {
            return content;
        }

        @Override
        public int hashCode() {
            return Objects.hash(ACCESS_ROLE_ENUM_TYPE, content);
        }

        BaseEntity(EntityType ACCESS_ROLE_ENUM_TYPE, String content) {
            this.ACCESS_ROLE_ENUM_TYPE = ACCESS_ROLE_ENUM_TYPE;
            this.content = content;
        }

    }

    /**
     * Class for ACL Domain entities.
     */
    public static final class DomainInfo extends BaseEntity {

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
        public DomainInfo(String realm) {
            super(EntityType.DOMAIN, realm);
        }

    }

    /**
     * Class for ACL Group entities.
     */
    public static final class EmailGroup extends BaseEntity {

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
        public EmailGroup(String contactAddress) {
            super(EntityType.GROUP, contactAddress);
        }

    }

    /**
     * Class for ACL User entities.
     */
    public static final class UserPrincipal extends BaseEntity {

        private static final long serialVersionUID = 3076518036392737008L;

        private static final String ALL_ACCOUNTS = "allUsers";

        private static final String AUTHENTICATED_ACCOUNTS = "allAuthenticatedUsers";

        @Override
        String toProto() {
            switch(getValue()) {
                case AUTHENTICATED_ACCOUNTS:
                    return AUTHENTICATED_ACCOUNTS;
                case ALL_ACCOUNTS:
                    return ALL_ACCOUNTS;
                default:
                    break;
            }
            return super.toProto();
        }

        public static UserPrincipal allAuthenticatedUsers() {
            return new UserPrincipal(AUTHENTICATED_ACCOUNTS);
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

        /**
         * Creates a user entity.
         *
         * @param contactAddress the user email
         */
        public UserPrincipal(String contactAddress) {
            super(EntityType.USER, contactAddress);
        }

    }

    /**
     * Class for ACL Project entities.
     */
    public static final class ProjectInfo extends BaseEntity {

        private static final long serialVersionUID = 7933776866530023027L;

        private final ProjectAccessLevel roleInProject;

        private final String projectKey;

        public static final class ProjectAccessLevel extends StringEnumValue {

            private static final long serialVersionUID = -8360324311187914382L;

            private static final ApiFunction<String, ProjectAccessLevel> ACCESS_ROLE_CONSTRUCTOR_FN = new ApiFunction<String, ProjectAccessLevel>() {

                @Override
                public AclEntry.ProjectInfo.ProjectAccessLevel apply(String constant) {
                    return new ProjectAccessLevel(constant);
                }
            };

            private static final StringEnumType<ProjectAccessLevel> ACCESS_ROLE_ENUM_TYPE = new StringEnumType(ProjectAccessLevel.class, ACCESS_ROLE_CONSTRUCTOR_FN);

            public static final ProjectAccessLevel OWNERS = ACCESS_ROLE_ENUM_TYPE.createAndRegister("OWNERS");

            public static final ProjectAccessLevel EDITORS = ACCESS_ROLE_ENUM_TYPE.createAndRegister("EDITORS");

            public static final ProjectAccessLevel VIEWERS = ACCESS_ROLE_ENUM_TYPE.createAndRegister("VIEWERS");

            /**
             * Get the ProjectRole for the given String constant, and throw an exception if the constant
             * is not recognized.
             */
            public static ProjectAccessLevel valueOfStrict(String value) {
                return ACCESS_ROLE_ENUM_TYPE.valueOfStrict(value);
            }

            /**
             * Return the known values for ProjectRole.
             */
            public static ProjectAccessLevel[] values() {
                return ACCESS_ROLE_ENUM_TYPE.values();
            }

            /**
             * Get the ProjectRole for the given String constant, and allow unrecognized values.
             */
            public static ProjectAccessLevel fromString(String value) {
                return ACCESS_ROLE_ENUM_TYPE.valueOf(value);
            }

            private ProjectAccessLevel(String value) {
                super(value);
            }

        }

        /**
         * Returns the role in the project for this entity.
         */
        public ProjectAccessLevel getProjectRole() {
            return roleInProject;
        }

        /**
         * Returns the project id for this entity.
         */
        public String getProjectId() {
            return projectKey;
        }

        /**
         * Creates a project entity.
         *
         * @param roleInProject a role in the project, used to select project's teams
         * @param projectKey id of the project
         */
        public ProjectInfo(ProjectAccessLevel roleInProject, String projectKey) {
            super(EntityType.PROJECT, roleInProject.name().toLowerCase() + "-" + projectKey);
            this.roleInProject = roleInProject;
            this.projectKey = projectKey;
        }

    }

    public static final class RawDataEntity extends BaseEntity {

        private static final long serialVersionUID = 3966205614223053950L;

        @Override
        String toProto() {
            return getValue();
        }

        RawDataEntity(String subject) {
            super(EntityType.UNKNOWN, subject);
        }

    }

    /**
     * Returns the entity for this ACL object.
     */
    public BaseEntity getEntity() {
        return subject;
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, accessLevel);
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
     * @param subject the entity for this ACL object
     * @param accessLevel the role to associate to the {@code entity} object
     */
    public static EntityBuilder builder(BaseEntity subject, AccessRole accessLevel) {
        return new EntityBuilder(subject, accessLevel);
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
        return Objects.equals(this.subject, thatEntry.subject) && Objects.equals(this.accessLevel, thatEntry.accessLevel) && Objects.equals(this.versionToken, thatEntry.versionToken) && Objects.equals(this.key, thatEntry.key);
    }

    static AclEntry fromProto(BucketAccessControl bucketAccess) {
        AccessRole accessLevel = AccessRole.fromString(bucketAccess.getRole());
        BaseEntity subject = BaseEntity.fromProto(bucketAccess.getEntity());
        return builder(subject, accessLevel).setEtag(bucketAccess.getEtag()).setId(bucketAccess.getId()).create();
    }

    /**
     * Returns an {@code Acl} object.
     *
     * @param subject the entity for this ACL object
     * @param accessLevel the role to associate to the {@code entity} object
     */
    public static AclEntry ofEntry(BaseEntity subject, AccessRole accessLevel) {
        return builder(subject, accessLevel).create();
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
     * Returns the role associated to the entity in this ACL object.
     */
    public AccessRole getRole() {
        return accessLevel;
    }

    static AclEntry fromProto(ObjectAccessControl objectAccess) {
        AccessRole accessLevel = AccessRole.fromString(objectAccess.getRole());
        BaseEntity subject = BaseEntity.fromProto(objectAccess.getEntity());
        return builder(subject, accessLevel).setEtag(objectAccess.getEtag()).setId(objectAccess.getId()).create();
    }

    private AclEntry(EntityBuilder entityCreator) {
        this.subject = checkNotNull(entityCreator.subject);
        this.accessLevel = checkNotNull(entityCreator.accessLevel);
        this.key = entityCreator.key;
        this.versionToken = entityCreator.versionToken;
    }

    /**
     * Returns HTTP 1.1 Entity tag for the ACL entry.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return versionToken;
    }

    /**
     * Returns the ID of the ACL entry.
     */
    public String getId() {
        return key;
    }

    /**
     * Returns a builder for this {@code Acl} object.
     */
    public EntityBuilder asBuilder() {
        return new EntityBuilder(this);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("entity", subject).add("role", accessLevel).add("etag", versionToken).add("id", key).toString();
    }

}
