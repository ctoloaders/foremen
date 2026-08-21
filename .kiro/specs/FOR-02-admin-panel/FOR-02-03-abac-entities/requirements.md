# Requirements Document

## Introduction

This specification defines the JPA entities for the access control model (ABAC) in the Foremen project: **Resource**, **Operation**, **Role**, and **RoleResource**. These entities form the foundation of the permission system: "Which role has which operations on which resource."

Entities are implemented on top of the CRUD framework from FOR-01:
- **Resource** and **Operation** — read-only reference entities (ReadOnlyAdminDao + ReadOnlyAdminService + AdminReadOnlyController)
- **Role** — full CRUD entity (AdminDao + AdminService + AdminController) with audit
- **RoleResource** — join entity, managed through Role endpoints

All entities inherit from `BaseEntity` (id, createdDate, createdBy, updatedDate, updatedBy) and follow the i18n convention (nameRU, namePL).

## Glossary

- **ResourceEntity**: JPA entity representing a system module/resource (projects, rooms, estimate, warehouse). Read-only, added via Liquibase.
- **OperationEntity**: JPA entity representing an action on a resource (CREATE, READ, UPDATE, DELETE). Read-only, added via Liquibase.
- **RoleEntity**: JPA entity representing a named user role (Admin, Manager, Foreman, Worker, Financier + custom roles).
- **RoleResourceEntity**: JPA join entity linking Role and Resource in an M:N relationship. Contains an M:N relationship with Operation via the `role_resource_operations` join table.
- **BaseEntity**: Abstract superclass of all entities (id, createdDate, createdBy, updatedDate, updatedBy).
- **ReadOnlyAdminDao**: Base generic interface for read-only DAO (from FOR-01-05).
- **AdminDao**: Base generic interface for full-CRUD DAO (from FOR-01-05).
- **ReadOnlyAdminService**: Base generic interface for read-only services (from FOR-01-06).
- **AdminService**: Base generic interface for full-CRUD services (from FOR-01-06).
- **AdminReadOnlyController**: Base generic interface for read-only REST controllers (from FOR-01-07).
- **AdminController**: Base generic interface for full-CRUD REST controllers (from FOR-01-07).
- **ServiceModel**: Locale-resolved service layer model (fields with resolved locale).
- **ServiceExtendedModel**: Full service layer model (all locales included).
- **ServiceToDaoMapper**: MapStruct mapper between service and dao layers.
- **ControllerToServiceMapper**: MapStruct mapper between controller and service layers.
- **AuditLog**: Audit log record for operations (JSONB snapshots before/after changes).

## Requirements

### Requirement 1: ResourceEntity — JPA Entity

**User Story:** As a developer, I want a JPA entity Resource to store the reference catalog of system modules/resources for the access control model.

#### Acceptance Criteria

1. THE ResourceEntity SHALL extend BaseEntity and be annotated with `@Entity` and `@Table(name = "resources")`
2. THE ResourceEntity SHALL contain a `code` field of type String with unique and not-null constraints
3. THE ResourceEntity SHALL contain a `nameRU` field of type String (not-null) for the Russian language name
4. THE ResourceEntity SHALL contain a `namePL` field of type String (not-null) for the Polish language name
5. THE ResourceEntity SHALL contain a `description` field of type String (nullable) for the resource description
6. THE ResourceEntity SHALL reside in the package `com.foremen.dao.model`

---

### Requirement 2: OperationEntity — JPA Entity

**User Story:** As a developer, I want a JPA entity Operation to store the reference catalog of available operations (CREATE, READ, UPDATE, DELETE).

#### Acceptance Criteria

1. THE OperationEntity SHALL extend BaseEntity and be annotated with `@Entity` and `@Table(name = "operations")`
2. THE OperationEntity SHALL contain a `code` field of type String with unique and not-null constraints
3. THE OperationEntity SHALL contain a `nameRU` field of type String (not-null) for the Russian language name
4. THE OperationEntity SHALL contain a `namePL` field of type String (not-null) for the Polish language name
5. THE OperationEntity SHALL reside in the package `com.foremen.dao.model`

---

### Requirement 3: RoleEntity — JPA Entity

**User Story:** As a developer, I want a JPA entity Role to store user roles with full CRUD management capability and protection of system roles from deletion.

#### Acceptance Criteria

1. THE RoleEntity SHALL extend BaseEntity and be annotated with `@Entity` and `@Table(name = "roles")`
2. THE RoleEntity SHALL contain a `code` field of type String with unique and not-null constraints
3. THE RoleEntity SHALL contain a `nameRU` field of type String (not-null) for the Russian language name
4. THE RoleEntity SHALL contain a `namePL` field of type String (not-null) for the Polish language name
5. THE RoleEntity SHALL contain a `description` field of type String (nullable) for the role description
6. THE RoleEntity SHALL contain a `system` field of type boolean (not-null, default false) marking system roles that cannot be deleted
7. THE RoleEntity SHALL contain a `@OneToMany` relationship with RoleResourceEntity (mappedBy role) for navigating to role permissions
8. THE RoleEntity SHALL reside in the package `com.foremen.dao.model`

---

### Requirement 4: RoleResourceEntity — Join Entity

**User Story:** As a developer, I want a join entity RoleResource to model the relationship "role X has operations [A, B, C] on resource Y."

#### Acceptance Criteria

1. THE RoleResourceEntity SHALL extend BaseEntity and be annotated with `@Entity` and `@Table(name = "role_resources")`
2. THE RoleResourceEntity SHALL contain a `role` field of type RoleEntity annotated with `@ManyToOne` and `@JoinColumn(name = "role_id", nullable = false)`
3. THE RoleResourceEntity SHALL contain a `resource` field of type ResourceEntity annotated with `@ManyToOne` and `@JoinColumn(name = "resource_id", nullable = false)`
4. THE RoleResourceEntity SHALL contain a `@ManyToMany` relationship with OperationEntity via the `role_resource_operations` join table (join columns: `role_resource_id`, inverse: `operation_id`)
5. THE RoleResourceEntity SHALL have a unique constraint on the combination (role_id, resource_id) to prevent duplicates
6. THE RoleResourceEntity SHALL reside in the package `com.foremen.dao.model`

---

### Requirement 5: Resource — DAO, Service, Controller (Read-Only)

**User Story:** As a developer, I want a read-only DAO, Service, and Controller for Resource to provide an API for reading the resource reference catalog without modification capability via REST.

#### Acceptance Criteria

1. THE ResourceDao SHALL extend ReadOnlyAdminDao parameterized with `<ResourceEntity, Long>` and reside in package `com.foremen.dao`
2. THE ResourceService SHALL implement ReadOnlyAdminService parameterized with ResourceServiceModel, ResourceServiceExtendedModel, ResourceEntity, and Long
3. THE ResourceService SHALL provide mappers for i18n field resolution (nameRU/namePL → name based on locale)
4. THE ResourceController SHALL implement AdminReadOnlyController and be mapped to `/api/resources`
5. THE ResourceController SHALL expose GET `/api/resources` (paginated list with optional query filter) and GET `/api/resources/{id}` (single resource by ID)

---

### Requirement 6: Operation — DAO, Service, Controller (Read-Only)

**User Story:** As a developer, I want a read-only DAO, Service, and Controller for Operation to provide an API for reading the operation reference catalog without modification capability via REST.

#### Acceptance Criteria

1. THE OperationDao SHALL extend ReadOnlyAdminDao parameterized with `<OperationEntity, Long>` and reside in package `com.foremen.dao`
2. THE OperationService SHALL implement ReadOnlyAdminService parameterized with OperationServiceModel, OperationServiceExtendedModel, OperationEntity, and Long
3. THE OperationService SHALL provide mappers for i18n field resolution (nameRU/namePL → name based on locale)
4. THE OperationController SHALL implement AdminReadOnlyController and be mapped to `/api/operations`
5. THE OperationController SHALL expose GET `/api/operations` (paginated list with optional query filter) and GET `/api/operations/{id}` (single operation by ID)

---

### Requirement 7: Role — DAO, Service, Controller (Full CRUD)

**User Story:** As a developer, I want a full-CRUD DAO, Service, and Controller for Role to manage roles via REST API with audit logging and protection of system roles.

#### Acceptance Criteria

1. THE RoleDao SHALL extend AdminDao parameterized with `<RoleEntity, Long>` and reside in package `com.foremen.dao`
2. THE RoleService SHALL implement AdminService parameterized with RoleServiceModel, RoleServiceExtendedModel, RoleEntity, and Long
3. THE RoleService SHALL provide mappers for i18n field resolution (nameRU/namePL → name based on locale)
4. THE RoleService SHALL save audit records (via saveAudit) upon create, update, and delete operations
5. IF a delete operation targets a RoleEntity where the `system` field is true, THEN THE RoleService SHALL throw a ForemenApiException with HTTP 403 status indicating that system roles cannot be deleted
6. THE RoleController SHALL implement AdminController and be mapped to `/api/roles`
7. THE RoleController SHALL expose POST `/api/roles` (create), PUT `/api/roles/{id}` (update), GET `/api/roles` (paginated list), GET `/api/roles/{id}` (single role), DELETE `/api/roles/{id}` (delete), and GET `/api/roles/audit/{id}` (audit history)

---

### Requirement 8: Role Permission Management (RoleResource)

**User Story:** As a developer, I want to manage role permissions (RoleResource associations with operations) through the Role API to assign and revoke operations on resources for a specific role.

#### Acceptance Criteria

1. THE RoleController SHALL expose PUT `/api/roles/{id}/permissions` accepting a list of permission objects (resource_id + list of operation_ids) to replace all RoleResource entries for the role
2. WHEN PUT `/api/roles/{id}/permissions` is called, THE RoleService SHALL delete all existing RoleResourceEntity records for the specified role and create new ones according to the request payload
3. WHEN PUT `/api/roles/{id}/permissions` is called with a role ID that does not exist, THE RoleService SHALL throw a ForemenApiException with HTTP 404 status
4. THE RoleController SHALL expose GET `/api/roles/{id}/permissions` returning all RoleResource entries for the role, each with the resource and associated operations
5. WHEN GET `/api/roles/{id}/permissions` is called with a role ID that does not exist, THE RoleService SHALL throw a ForemenApiException with HTTP 404 status

---

### Requirement 9: ServiceModel and ServiceExtendedModel for Each Entity

**User Story:** As a developer, I want Service models (localized and extended) for each ABAC entity to separate presentation layers and ensure correct i18n handling.

#### Acceptance Criteria

1. FOR EACH ABAC entity (Resource, Operation, Role), THE system SHALL define a ServiceModel record containing id, code, and locale-resolved `name` field
2. FOR EACH ABAC entity (Resource, Operation, Role), THE system SHALL define a ServiceExtendedModel record containing id, code, nameRU, and namePL fields
3. THE ResourceServiceExtendedModel SHALL additionally contain the `description` field
4. THE RoleServiceExtendedModel SHALL additionally contain the `description` and `system` fields
5. THE RoleServiceModel SHALL additionally contain the `system` field

---

### Requirement 10: MapStruct Mappers for ABAC Entities

**User Story:** As a developer, I want MapStruct mappers for each ABAC entity to provide automatic mapping between DAO↔Service↔Controller layers.

#### Acceptance Criteria

1. FOR EACH ABAC entity (Resource, Operation, Role), THE system SHALL define a ServiceToDaoMapper implementation that maps between DaoModel and ServiceModel/ServiceExtendedModel
2. THE ServiceToDaoMapper for each entity SHALL declare i18n-supported properties including "name" for locale resolution
3. THE RoleEntity mapper SHALL use `@BeanMapping(nullValuePropertyMappingStrategy = IGNORE)` for partial update mapping
4. THE RoleController SHALL use a ControllerToServiceMapper implementation that maps between DTO request/response models and ServiceExtendedModel
5. WHEN mapping from ServiceExtendedModel to DaoModel, THE mapper SHALL invoke `processI18nEmptyValues` to normalize blank i18n fields to null

---

### Requirement 11: DTO Models for Role Controller

**User Story:** As a developer, I want DTO models (request/response) for RoleController so that the controller works with typed request and response objects.

#### Acceptance Criteria

1. THE system SHALL define a RoleCreateRequest record containing code, nameRU, namePL, description (optional), and system (optional, defaults to false)
2. THE system SHALL define a RoleUpdateRequest record containing nameRU, namePL, description (optional), and system (optional)
3. THE system SHALL define a RoleDtoModel record containing id, code, and locale-resolved name for paginated list responses
4. THE system SHALL define a RoleDtoExtendedModel record containing id, code, nameRU, namePL, description, and system for detail responses
5. THE RoleCreateRequest SHALL have `@NotBlank` validation on code, nameRU, and namePL fields
6. THE RoleUpdateRequest SHALL have `@NotBlank` validation on nameRU and namePL fields

---

### Requirement 12: DTO Models for Permissions API

**User Story:** As a developer, I want DTO models for the role permission management API to type the input and output data of the permissions endpoints.

#### Acceptance Criteria

1. THE system SHALL define a RolePermissionRequest record containing a list of PermissionEntry objects (each with resourceId and list of operationIds)
2. THE system SHALL define a RolePermissionResponse record containing roleId and a list of PermissionEntry objects (each with resourceId, resourceCode, resourceName, and list of operations with operationId, operationCode, operationName)
3. WHEN `operationIds` list in a PermissionEntry is empty, THE system SHALL interpret this as revoking all operations for that resource from the role
4. IF a PermissionEntry references a resourceId that does not exist, THEN THE RoleService SHALL throw a ForemenApiException with HTTP 400 status
5. IF a PermissionEntry references an operationId that does not exist, THEN THE RoleService SHALL throw a ForemenApiException with HTTP 400 status

---

### Requirement 13: Relationship Correctness (Invariants)

**User Story:** As a developer, I want guarantees of domain model correctness so that data in the database remains consistent under all operations.

#### Acceptance Criteria

1. FOR ALL RoleResourceEntity records in the database, THE combination (role_id, resource_id) SHALL be unique — no duplicate role-resource pairs exist
2. WHEN a RoleEntity is deleted, THE system SHALL cascade-delete all associated RoleResourceEntity records (orphanRemoval or CASCADE DELETE)
3. WHEN a ResourceEntity is referenced by at least one RoleResourceEntity, THE system SHALL prevent deletion of that ResourceEntity (referential integrity via FK constraint)
4. WHEN an OperationEntity is referenced by at least one role_resource_operations join record, THE system SHALL prevent deletion of that OperationEntity (referential integrity via FK constraint)
5. FOR ALL RoleEntity records where `system` is true, THE RoleService SHALL reject deletion attempts with a 403 error
6. FOR ALL ABAC entities, THE `code` field SHALL be unique within its respective table (enforced by DB unique constraint)
