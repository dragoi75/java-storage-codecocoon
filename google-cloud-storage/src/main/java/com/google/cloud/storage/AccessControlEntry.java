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

    static final Function<ObjectAccessControl, AccessControlEntry> FROM_OBJECT_PROTO_FUNCTION = new Function<ObjectAccessControl, AccessControlEntry>() {

        @Override
        public AccessControlEntry apply(ObjectAccessControl aclPb) {
            return AccessControlEntry.fromProto(aclPb);
        }
    };

    static final Function<BucketAccessControl, AccessControlEntry> FROM_BUCKET_PROTO_FUNCTION = new Function<BucketAccessControl, AccessControlEntry>() {

        @Override
        public AccessControlEntry apply(BucketAccessControl aclPb) {
            return AccessControlEntry.fromProto(aclPb);
        }
    };

    private final AbstractEntity abstractEntityInstance;

    private final RoleType roleType;

    private final String identifier;

    private final String eTagValue;

    public static final class RoleType extends StringEnumValue {

        private static final long serialVersionUID = 123037132067643600L;

        private RoleType(String constantValue) {
            super(constantValue);
        }

        private static final ApiFunction<String, RoleType> STRING_TO_ROLE_CONSTRUCTOR = new ApiFunction<String, RoleType>() {

            @Override
            public AccessControlEntry.RoleType apply(String constant) {
                return new RoleType(constant);
            }
        };

        private static final StringEnumType<RoleType> ROLE_TYPE_ENUM = new StringEnumType(RoleType.class, STRING_TO_ROLE_CONSTRUCTOR);

        public static final RoleType OWNER = ROLE_TYPE_ENUM.createAndRegister("OWNER");

        public static final RoleType READER = ROLE_TYPE_ENUM.createAndRegister("READER");

        public static final RoleType WRITER = ROLE_TYPE_ENUM.createAndRegister("WRITER");

        /**
         * Get the Role for the given String constant, and throw an exception if the constant is not
         * recognized.
         */
        public static RoleType valueOfStrict(String constantValue) {
            return ROLE_TYPE_ENUM.valueOfStrict(constantValue);
        }

        /**
         * Get the Role for the given String constant, and allow unrecognized values.
         */
        public static RoleType fromString(String constantValue) {
            return ROLE_TYPE_ENUM.valueOf(constantValue);
        }

        /**
         * Return the known values for Role.
         */
        public static RoleType[] values() {
            return ROLE_TYPE_ENUM.values();
        }
    }

    /**
     * Builder for {@code Acl} objects.
     */
    public static class EntityBuilder {

        private AbstractEntity abstractEntityInstance;

        private RoleType roleType;

        private String identifier;

        private String eTagValue;

        private EntityBuilder(AbstractEntity abstractEntityInstance, RoleType roleType) {
            this.abstractEntityInstance = abstractEntityInstance;
            this.roleType = roleType;
        }

        private EntityBuilder(AccessControlEntry accessControlEntry) {
            this.abstractEntityInstance = accessControlEntry.abstractEntityInstance;
            this.roleType = accessControlEntry.roleType;
            this.identifier = accessControlEntry.identifier;
            this.eTagValue = accessControlEntry.eTagValue;
        }

        /**
         * Sets the entity for the ACL object.
         */
        public EntityBuilder setEntity(AbstractEntity abstractEntityInstance) {
            this.abstractEntityInstance = abstractEntityInstance;
            return this;
        }

        /**
         * Sets the role to associate to the {@code entity} object.
         */
        public EntityBuilder setRole(RoleType roleType) {
            this.roleType = roleType;
            return this;
        }

        EntityBuilder setId(String identifier) {
            this.identifier = identifier;
            return this;
        }

        EntityBuilder setEtag(String eTagValue) {
            this.eTagValue = eTagValue;
            return this;
        }

        /**
         * Creates an {@code Acl} object from this builder.
         */
        public AccessControlEntry buildInstance() {
            return new AccessControlEntry(this);
        }
    }

    /**
     * Base class for Access Control List entities.
     */
    public abstract static class AbstractEntity implements Serializable {

        private static final long serialVersionUID = -2707407252771255840L;

        private final EntityType ROLE_TYPE_ENUM;

        private final String valueStr;

        public enum EntityType {

            DOMAIN, GROUP, USER, PROJECT, UNKNOWN
        }

        AbstractEntity(EntityType ROLE_TYPE_ENUM, String valueStr) {
            this.ROLE_TYPE_ENUM = ROLE_TYPE_ENUM;
            this.valueStr = valueStr;
        }

        /**
         * Returns the type of entity.
         */
        public EntityType getType() {
            return ROLE_TYPE_ENUM;
        }

        /**
         * Returns the entity's value.
         */
        protected String getValue() {
            return valueStr;
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj == this) {
                return true;
            }
            if (null == otherObj || otherObj.getClass() != getClass()) {
                return false;
            }
            AbstractEntity abstractEntityInstance = (AbstractEntity) otherObj;
            return Objects.equals(ROLE_TYPE_ENUM, abstractEntityInstance.ROLE_TYPE_ENUM) && Objects.equals(valueStr, abstractEntityInstance.valueStr);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ROLE_TYPE_ENUM, valueStr);
        }

        @Override
        public String toString() {
            return toProto();
        }

        String toProto() {
            return ROLE_TYPE_ENUM.name().toLowerCase() + "-" + getValue();
        }

        static AbstractEntity fromProto(String abstractEntityInstance) {
            if (abstractEntityInstance.startsWith("user-")) {
                return new UserIdentity(abstractEntityInstance.substring(5));
            }
            if (abstractEntityInstance.equals(UserIdentity.EVERY_USER)) {
                return UserIdentity.allUsers();
            }
            if (abstractEntityInstance.equals(UserIdentity.AUTHENTICATED_USER_GROUP)) {
                return UserIdentity.allAuthenticatedUsers();
            }
            if (abstractEntityInstance.startsWith("group-")) {
                return new EmailGroup(abstractEntityInstance.substring(6));
            }
            if (abstractEntityInstance.startsWith("domain-")) {
                return new DomainValue(abstractEntityInstance.substring(7));
            }
            if (abstractEntityInstance.startsWith("project-")) {
                int index = abstractEntityInstance.indexOf('-', 8);
                String teamName = abstractEntityInstance.substring(8, index);
                String projectIdentifier = abstractEntityInstance.substring(index + 1);
                return new ProjectInfo(ProjectInfo.ProjectMemberRole.fromString(teamName.toUpperCase()), projectIdentifier);
            }
            return new RawDataEntity(abstractEntityInstance);
        }
    }

    /**
     * Class for ACL Domain entities.
     */
    public static final class DomainValue extends AbstractEntity {

        private static final long serialVersionUID = -3033025857280447253L;

        /**
         * Creates a domain entity.
         *
         * @param domainName the domain associated to this entity
         */
        public DomainValue(String domainName) {
            super(EntityType.DOMAIN, domainName);
        }

        /**
         * Returns the domain associated to this entity.
         */
        public String getDomain() {
            return getValue();
        }
    }

    /**
     * Class for ACL Group entities.
     */
    public static final class EmailGroup extends AbstractEntity {

        private static final long serialVersionUID = -1660987136294408826L;

        /**
         * Creates a group entity.
         *
         * @param address the group email
         */
        public EmailGroup(String address) {
            super(EntityType.GROUP, address);
        }

        /**
         * Returns the group email.
         */
        public String getEmail() {
            return getValue();
        }
    }

    /**
     * Class for ACL User entities.
     */
    public static final class UserIdentity extends AbstractEntity {

        private static final long serialVersionUID = 3076518036392737008L;

        private static final String EVERY_USER = "allUsers";

        private static final String AUTHENTICATED_USER_GROUP = "allAuthenticatedUsers";

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

        @Override
        String toProto() {
            switch(getValue()) {
                case AUTHENTICATED_USER_GROUP:
                    return AUTHENTICATED_USER_GROUP;
                case EVERY_USER:
                    return EVERY_USER;
                default:
                    break;
            }
            return super.toProto();
        }

        public static UserIdentity allUsers() {
            return new UserIdentity(EVERY_USER);
        }

        public static UserIdentity allAuthenticatedUsers() {
            return new UserIdentity(AUTHENTICATED_USER_GROUP);
        }
    }

    /**
     * Class for ACL Project entities.
     */
    public static final class ProjectInfo extends AbstractEntity {

        private static final long serialVersionUID = 7933776866530023027L;

        private final ProjectMemberRole role;

        private final String projectIdentifier;

        public static final class ProjectMemberRole extends StringEnumValue {

            private static final long serialVersionUID = -8360324311187914382L;

            private ProjectMemberRole(String constantValue) {
                super(constantValue);
            }

            private static final ApiFunction<String, ProjectMemberRole> STRING_TO_ROLE_CONSTRUCTOR = new ApiFunction<String, ProjectMemberRole>() {

                @Override
                public AccessControlEntry.ProjectInfo.ProjectMemberRole apply(String constant) {
                    return new ProjectMemberRole(constant);
                }
            };

            private static final StringEnumType<ProjectMemberRole> ROLE_TYPE_ENUM = new StringEnumType(ProjectMemberRole.class, STRING_TO_ROLE_CONSTRUCTOR);

            public static final ProjectMemberRole OWNERS = ROLE_TYPE_ENUM.createAndRegister("OWNERS");

            public static final ProjectMemberRole EDITORS = ROLE_TYPE_ENUM.createAndRegister("EDITORS");

            public static final ProjectMemberRole VIEWERS = ROLE_TYPE_ENUM.createAndRegister("VIEWERS");

            /**
             * Get the ProjectRole for the given String constant, and throw an exception if the constant
             * is not recognized.
             */
            public static ProjectMemberRole valueOfStrict(String constantValue) {
                return ROLE_TYPE_ENUM.valueOfStrict(constantValue);
            }

            /**
             * Get the ProjectRole for the given String constant, and allow unrecognized values.
             */
            public static ProjectMemberRole fromString(String constantValue) {
                return ROLE_TYPE_ENUM.valueOf(constantValue);
            }

            /**
             * Return the known values for ProjectRole.
             */
            public static ProjectMemberRole[] values() {
                return ROLE_TYPE_ENUM.values();
            }
        }

        /**
         * Creates a project entity.
         *
         * @param role a role in the project, used to select project's teams
         * @param projectIdentifier id of the project
         */
        public ProjectInfo(ProjectMemberRole role, String projectIdentifier) {
            super(EntityType.PROJECT, role.name().toLowerCase() + "-" + projectIdentifier);
            this.role = role;
            this.projectIdentifier = projectIdentifier;
        }

        /**
         * Returns the role in the project for this entity.
         */
        public ProjectMemberRole getProjectRole() {
            return role;
        }

        /**
         * Returns the project id for this entity.
         */
        public String getProjectId() {
            return projectIdentifier;
        }
    }

    public static final class RawDataEntity extends AbstractEntity {

        private static final long serialVersionUID = 3966205614223053950L;

        RawDataEntity(String abstractEntityInstance) {
            super(EntityType.UNKNOWN, abstractEntityInstance);
        }

        @Override
        String toProto() {
            return getValue();
        }
    }

    private AccessControlEntry(EntityBuilder entityFactory) {
        this.abstractEntityInstance = checkNotNull(entityFactory.abstractEntityInstance);
        this.roleType = checkNotNull(entityFactory.roleType);
        this.identifier = entityFactory.identifier;
        this.eTagValue = entityFactory.eTagValue;
    }

    /**
     * Returns the entity for this ACL object.
     */
    public AbstractEntity getEntity() {
        return abstractEntityInstance;
    }

    /**
     * Returns the role associated to the entity in this ACL object.
     */
    public RoleType getRole() {
        return roleType;
    }

    /**
     * Returns the ID of the ACL entry.
     */
    public String getId() {
        return identifier;
    }

    /**
     * Returns HTTP 1.1 Entity tag for the ACL entry.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-3.11">Entity Tags</a>
     */
    public String getEtag() {
        return eTagValue;
    }

    /**
     * Returns a builder for this {@code Acl} object.
     */
    public EntityBuilder asBuilder() {
        return new EntityBuilder(this);
    }

    /**
     * Returns an {@code Acl} object.
     *
     * @param abstractEntityInstance the entity for this ACL object
     * @param roleType the role to associate to the {@code entity} object
     */
    public static AccessControlEntry create(AbstractEntity abstractEntityInstance, RoleType roleType) {
        return builder(abstractEntityInstance, roleType).buildInstance();
    }

    /**
     * Returns a builder for {@code Acl} objects.
     *
     * @param abstractEntityInstance the entity for this ACL object
     * @param roleType the role to associate to the {@code entity} object
     */
    public static EntityBuilder builder(AbstractEntity abstractEntityInstance, RoleType roleType) {
        return new EntityBuilder(abstractEntityInstance, roleType);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("entity", abstractEntityInstance).add("role", roleType).add("etag", eTagValue).add("id", identifier).toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(abstractEntityInstance, roleType);
    }

    @Override
    public boolean equals(Object otherObj) {
        if (otherObj == this) {
            return true;
        }
        if (null == otherObj || otherObj.getClass() != getClass()) {
            return false;
        }
        final AccessControlEntry thatEntry = (AccessControlEntry) otherObj;
        return Objects.equals(this.abstractEntityInstance, thatEntry.abstractEntityInstance) && Objects.equals(this.roleType, thatEntry.roleType) && Objects.equals(this.eTagValue, thatEntry.eTagValue) && Objects.equals(this.identifier, thatEntry.identifier);
    }

    BucketAccessControl toBucketProto() {
        BucketAccessControl bucketAcl = new BucketAccessControl();
        bucketAcl.setEntity(getEntity().toString());
        bucketAcl.setRole(getRole().toString());
        bucketAcl.setId(getId());
        bucketAcl.setEtag(getEtag());
        return bucketAcl;
    }

    ObjectAccessControl toObjectProto() {
        ObjectAccessControl objectAcl = new ObjectAccessControl();
        objectAcl.setEntity(getEntity().toProto());
        objectAcl.setRole(getRole().name());
        objectAcl.setId(getId());
        objectAcl.setEtag(getEtag());
        return objectAcl;
    }

    static AccessControlEntry fromProto(ObjectAccessControl acl) {
        RoleType roleType = RoleType.fromString(acl.getRole());
        AbstractEntity abstractEntityInstance = AbstractEntity.fromProto(acl.getEntity());
        return builder(abstractEntityInstance, roleType).setEtag(acl.getEtag()).setId(acl.getId()).buildInstance();
    }

    static AccessControlEntry fromProto(BucketAccessControl bucketAcl) {
        RoleType roleType = RoleType.fromString(bucketAcl.getRole());
        AbstractEntity abstractEntityInstance = AbstractEntity.fromProto(bucketAcl.getEntity());
        return builder(abstractEntityInstance, roleType).setEtag(bucketAcl.getEtag()).setId(bucketAcl.getId()).buildInstance();
    }
}
