# Implementation Plan: ABAC Entities (FOR-02-03-abac-entities)

## Overview

Implement four JPA entities (Resource, Operation, Role, RoleResource) and their full stack (DAO, Service, Controller, Mapper, DTO) for the Foremen ABAC permission model. The implementation follows the FOR-01 CRUD framework: read-only entities use `ReadOnlyAdminDao` + `ReadOnlyAdminService` + `AdminReadOnlyController`, while Role uses `AdminDao` + `AdminService` + `AdminController`. RoleResource is managed implicitly through permission endpoints on the RoleController.

Key design decisions reflected in this plan:
- **i18n descriptions**: ResourceEntity and RoleEntity have `descriptionRU`/`descriptionPL` (not a single `description`). Service mappers declare `Set.of("name", "description")` for locale resolution.
- **Lombok @Getter on services**: ResourceService and OperationService use `@Getter @RequiredArgsConstructor` with field naming matching interface methods — no manual `@Override` boilerplate. RoleService uses the same pattern but retains custom logic (deleteById, permission methods).

## Tasks

- [x] 1. Create Liquibase changesets for ABAC database schema
  - [x] 1.1 Create changeset `002-create-resources.xml` with table DDL and seed data
    - Create `database_files/changesets/002-create-resources.xml`
    - Define `resources` table: id (BIGSERIAL PK), code (VARCHAR(100) UNIQUE NOT NULL), name_ru (VARCHAR(255) NOT NULL), name_pl (VARCHAR(255) NOT NULL), description_ru (VARCHAR(500) nullable), description_pl (VARCHAR(500) nullable), created_date, created_by, updated_date, updated_by
    - Seed reference data: PROJECTS, ROOMS, ESTIMATE, WAREHOUSE with RU and PL names and descriptions (both description_ru and description_pl)
    - Use `<preConditions onFail="MARK_RAN">` for idempotency
    - Register the changeset in `database_files/changelog.xml`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [x] 1.2 Create changeset `003-create-operations.xml` with table DDL and seed data
    - Create `database_files/changesets/003-create-operations.xml`
    - Define `operations` table: id (BIGSERIAL PK), code (VARCHAR(50) UNIQUE NOT NULL), name_ru (VARCHAR(255) NOT NULL), name_pl (VARCHAR(255) NOT NULL), created_date, created_by, updated_date, updated_by
    - Seed reference data: CREATE, READ, UPDATE, DELETE with RU and PL names
    - Register the changeset in `database_files/changelog.xml`
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 1.3 Create changeset `004-create-roles.xml` with table DDL and seed data
    - Create `database_files/changesets/004-create-roles.xml`
    - Define `roles` table: id (BIGSERIAL PK), code (VARCHAR(100) UNIQUE NOT NULL), name_ru (VARCHAR(255) NOT NULL), name_pl (VARCHAR(255) NOT NULL), description_ru (VARCHAR(500) nullable), description_pl (VARCHAR(500) nullable), system (BOOLEAN NOT NULL DEFAULT FALSE), created_date, created_by, updated_date, updated_by
    - Seed system roles: ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER (all with system=true, both description_ru and description_pl populated)
    - Register the changeset in `database_files/changelog.xml`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [x] 1.4 Create changeset `005-create-role-resources.xml` with join tables
    - Create `database_files/changesets/005-create-role-resources.xml`
    - Define `role_resources` table: id (BIGSERIAL PK), role_id (BIGINT FK NOT NULL), resource_id (BIGINT FK NOT NULL), created_date, created_by, updated_date, updated_by
    - Add unique constraint on (role_id, resource_id)
    - Define `role_resource_operations` table: role_resource_id (BIGINT FK), operation_id (BIGINT FK), composite PK
    - Add cascade delete from roles to role_resources
    - Register the changeset in `database_files/changelog.xml`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 13.1, 13.2_

- [x] 2. Implement JPA entities
  - [x] 2.1 Create ResourceEntity
    - Create `src/main/java/com/foremen/dao/model/ResourceEntity.java`
    - Extend BaseEntity, annotate with @Entity, @Table(name = "resources")
    - Fields: code (String, unique, not-null), nameRU (String, not-null, column "name_ru"), namePL (String, not-null, column "name_pl"), descriptionRU (String, nullable, column "description_ru"), descriptionPL (String, nullable, column "description_pl")
    - Use Lombok @Getter, @Setter, @NoArgsConstructor
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [x] 2.2 Create OperationEntity
    - Create `src/main/java/com/foremen/dao/model/OperationEntity.java`
    - Extend BaseEntity, annotate with @Entity, @Table(name = "operations")
    - Fields: code (String, unique, not-null), nameRU (String, not-null, column "name_ru"), namePL (String, not-null, column "name_pl")
    - Use Lombok @Getter, @Setter, @NoArgsConstructor
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 2.3 Create RoleEntity
    - Create `src/main/java/com/foremen/dao/model/RoleEntity.java`
    - Extend BaseEntity, annotate with @Entity, @Table(name = "roles")
    - Fields: code (String, unique, not-null), nameRU (String, not-null, column "name_ru"), namePL (String, not-null, column "name_pl"), descriptionRU (String, nullable, column "description_ru"), descriptionPL (String, nullable, column "description_pl"), system (boolean, not-null, default false)
    - Add @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true) for roleResources
    - Use Lombok @Getter, @Setter, @NoArgsConstructor
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

  - [x] 2.4 Create RoleResourceEntity
    - Create `src/main/java/com/foremen/dao/model/RoleResourceEntity.java`
    - Extend BaseEntity, annotate with @Entity, @Table(name = "role_resources") with @UniqueConstraint on (role_id, resource_id)
    - Fields: role (@ManyToOne, @JoinColumn "role_id"), resource (@ManyToOne, @JoinColumn "resource_id"), operations (@ManyToMany via "role_resource_operations" join table, FetchType.EAGER)
    - Use Lombok @Getter, @Setter, @NoArgsConstructor
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 3. Implement DAO interfaces
  - [x] 3.1 Create ResourceDao, OperationDao, RoleDao, and RoleResourceDao
    - Create `src/main/java/com/foremen/dao/ResourceDao.java` extending ReadOnlyAdminDao<ResourceEntity, Long>
    - Create `src/main/java/com/foremen/dao/OperationDao.java` extending ReadOnlyAdminDao<OperationEntity, Long>
    - Create `src/main/java/com/foremen/dao/RoleDao.java` extending AdminDao<RoleEntity, Long>
    - Create `src/main/java/com/foremen/dao/RoleResourceDao.java` extending AdminDao<RoleResourceEntity, Long> with custom methods: findAllByRoleId(Long), deleteAllByRoleId(Long)
    - All annotated with @Repository
    - _Requirements: 5.1, 6.1, 7.1, 8.2_

- [x] 4. Implement service models and mappers
  - [x] 4.1 Create service model records
    - Create `src/main/java/com/foremen/service/model/ResourceServiceModel.java` — record(Long id, String code, String name, String description) — name and description are locale-resolved
    - Create `src/main/java/com/foremen/service/model/ResourceServiceExtendedModel.java` — record(Long id, String code, String nameRU, String namePL, String descriptionRU, String descriptionPL)
    - Create `src/main/java/com/foremen/service/model/OperationServiceModel.java` — record(Long id, String code, String name)
    - Create `src/main/java/com/foremen/service/model/OperationServiceExtendedModel.java` — record(Long id, String code, String nameRU, String namePL)
    - Create `src/main/java/com/foremen/service/model/RoleServiceModel.java` — record(Long id, String code, String name, String description, boolean system) — name and description are locale-resolved
    - Create `src/main/java/com/foremen/service/model/RoleServiceExtendedModel.java` — record(Long id, String code, String nameRU, String namePL, String descriptionRU, String descriptionPL, boolean system)
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [x] 4.2 Create ServiceToDaoMapper implementations
    - Create `src/main/java/com/foremen/service/model/mapper/ResourceServiceMapper.java` — extends ServiceToDaoMapper, `getI18nSupportedProperties()` returns `Set.of("name", "description")`
    - Create `src/main/java/com/foremen/service/model/mapper/OperationServiceMapper.java` — extends ServiceToDaoMapper, `getI18nSupportedProperties()` returns `Set.of("name")`
    - Create `src/main/java/com/foremen/service/model/mapper/RoleServiceMapper.java` — extends ServiceToDaoMapper, `getI18nSupportedProperties()` returns `Set.of("name", "description")`, with @Mapping(target = "roleResources", ignore = true) and @Mapping(target = "id", ignore = true) on toCreateDaoModel and updateFields
    - All annotated with @Mapper(config = ForemenMapperConfig.class)
    - _Requirements: 10.1, 10.2, 10.3, 10.5_

- [x] 5. Checkpoint - Verify entities, DAOs, and mappers compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement services
  - [x] 6.1 Create ResourceService (read-only, Lombok @Getter)
    - Create `src/main/java/com/foremen/service/ResourceService.java`
    - Implement ReadOnlyAdminService<ResourceServiceModel, ResourceServiceExtendedModel, ResourceEntity, Long>
    - Annotate with `@Service`, `@RequiredArgsConstructor`, `@Getter`
    - Declare fields: `private final ResourceDao readDao;`, `private final ResourceServiceMapper mapper;`, `private final EntityManager entityManager;`, `private final Class<ResourceEntity> daoModelClass = ResourceEntity.class;`
    - NO manual @Override methods — Lombok @Getter generates getReadDao(), getMapper(), getEntityManager(), getDaoModelClass() matching interface method names
    - _Requirements: 5.2, 5.3_

  - [x] 6.2 Create OperationService (read-only, Lombok @Getter)
    - Create `src/main/java/com/foremen/service/OperationService.java`
    - Implement ReadOnlyAdminService<OperationServiceModel, OperationServiceExtendedModel, OperationEntity, Long>
    - Annotate with `@Service`, `@RequiredArgsConstructor`, `@Getter`
    - Declare fields: `private final OperationDao readDao;`, `private final OperationServiceMapper mapper;`, `private final EntityManager entityManager;`, `private final Class<OperationEntity> daoModelClass = OperationEntity.class;`
    - NO manual @Override methods — Lombok @Getter generates getReadDao(), getMapper(), getEntityManager(), getDaoModelClass() matching interface method names
    - _Requirements: 6.2, 6.3_

  - [x] 6.3 Create RoleService (full CRUD + permissions, Lombok @Getter)
    - Create `src/main/java/com/foremen/service/RoleService.java`
    - Implement AdminService<RoleServiceModel, RoleServiceExtendedModel, RoleEntity, Long>
    - Annotate with `@Service`, `@RequiredArgsConstructor`, `@Getter`, `@Transactional`
    - Declare fields: `private final RoleDao dao;`, `private final RoleResourceDao roleResourceDao;`, `private final ResourceDao resourceDao;`, `private final OperationDao operationDao;`, `private final RoleServiceMapper mapper;`, `private final AuditLogDao auditLogDao;`, `private final EntityManager entityManager;`, `private final Class<RoleEntity> daoModelClass = RoleEntity.class;`
    - NO manual @Override methods for getDao(), getMapper(), getAuditLogDao(), getEntityManager(), getDaoModelClass() — Lombok @Getter handles these
    - Only custom logic remains: override deleteById to check `system` flag → throw ForemenApiException(403) if true
    - Implement getPermissions(Long roleId): fetch RoleResourceEntities, map to RolePermissionResponse with locale-resolved names
    - Implement replacePermissions(Long roleId, RolePermissionRequest): delete all existing + create new entries, validate resources/operations exist, throw 400 if not found
    - Use saveAudit for DELETE and UPDATE_PERMISSIONS actions
    - _Requirements: 7.2, 7.3, 7.4, 7.5, 8.1, 8.2, 8.3, 8.4, 8.5, 12.3, 12.4, 12.5, 13.5_

- [x] 7. Implement controller DTOs and mapper
  - [x] 7.1 Create Role DTO models
    - Create `src/main/java/com/foremen/controller/model/RoleDtoModel.java` — record(Long id, String code, String name)
    - Create `src/main/java/com/foremen/controller/model/RoleDtoExtendedModel.java` — record(Long id, String code, String nameRU, String namePL, String descriptionRU, String descriptionPL, boolean system)
    - Create `src/main/java/com/foremen/controller/model/RoleCreateRequest.java` — record with @NotBlank on code, nameRU, namePL; includes descriptionRU (optional), descriptionPL (optional), system (optional)
    - Create `src/main/java/com/foremen/controller/model/RoleCreateResponse.java` — record(Long id, String code, String nameRU, String namePL, String descriptionRU, String descriptionPL, boolean system)
    - Create `src/main/java/com/foremen/controller/model/RoleUpdateRequest.java` — record with @NotBlank on nameRU, namePL; includes descriptionRU (optional), descriptionPL (optional), system (optional)
    - Create `src/main/java/com/foremen/controller/model/RoleUpdateResponse.java` — record(Long id, String code, String nameRU, String namePL, String descriptionRU, String descriptionPL, boolean system)
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6_

  - [x] 7.2 Create Permission DTO models
    - Create `src/main/java/com/foremen/controller/model/RolePermissionRequest.java` — record(List<PermissionEntryRequest> permissions)
    - Create `src/main/java/com/foremen/controller/model/PermissionEntryRequest.java` — record(Long resourceId, List<Long> operationIds)
    - Create `src/main/java/com/foremen/controller/model/RolePermissionResponse.java` — record(Long roleId, List<PermissionEntryResponse> permissions)
    - Create `src/main/java/com/foremen/controller/model/PermissionEntryResponse.java` — record(Long resourceId, String resourceCode, String resourceName, List<OperationInfo> operations)
    - Create `src/main/java/com/foremen/controller/model/OperationInfo.java` — record(Long operationId, String operationCode, String operationName)
    - _Requirements: 12.1, 12.2_

  - [x] 7.3 Create RoleControllerMapper
    - Create `src/main/java/com/foremen/controller/model/mapper/RoleControllerMapper.java`
    - Extend ControllerToServiceMapper with Role-specific type parameters
    - Add @Mapping(target = "id", ignore = true) on toServiceExtendedModel(RoleCreateRequest)
    - Annotate with @Mapper(config = ForemenMapperConfig.class)
    - _Requirements: 10.4_

- [x] 8. Implement controllers
  - [x] 8.1 Create ResourceController (read-only)
    - Create `src/main/java/com/foremen/controller/ResourceController.java`
    - Implement AdminReadOnlyController, mapped to `/api/resources`
    - Inject ResourceService, provide getService() implementation
    - _Requirements: 5.4, 5.5_

  - [x] 8.2 Create OperationController (read-only)
    - Create `src/main/java/com/foremen/controller/OperationController.java`
    - Implement AdminReadOnlyController, mapped to `/api/operations`
    - Inject OperationService, provide getService() implementation
    - _Requirements: 6.4, 6.5_

  - [x] 8.3 Create RoleController (full CRUD + permissions)
    - Create `src/main/java/com/foremen/controller/RoleController.java`
    - Implement AdminController, mapped to `/api/roles`
    - Inject RoleService, RoleControllerMapper, provide getService() and getMapper() implementations
    - Add GET `/{id}/permissions` endpoint → delegates to roleService.getPermissions(id)
    - Add PUT `/{id}/permissions` endpoint → delegates to roleService.replacePermissions(id, request) with @Valid @RequestBody
    - _Requirements: 7.6, 7.7, 8.1, 8.4_

- [x] 9. Checkpoint - Verify full stack compiles and application starts
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Write property-based tests (jqwik)
  - [x] 10.1 Write property test for i18n locale resolution
    - **Property 1: I18n Locale Resolution**
    - **Validates: Requirements 5.3, 6.3, 7.3**
    - Generate arbitrary non-null String pairs for nameRU/namePL (and descriptionRU/descriptionPL for Resource and Role) across all 3 entity mappers
    - Verify ServiceModel's `name` field resolves to the correct locale field based on LocaleContextHolder
    - For Resource and Role: also verify `description` resolves to descriptionRU or descriptionPL based on locale
    - Resource and Role mappers declare `Set.of("name", "description")`; Operation mapper declares `Set.of("name")`

  - [x] 10.2 Write property test for system role deletion protection
    - **Property 2: System Role Deletion Protection**
    - **Validates: Requirements 7.5, 13.5**
    - Generate random RoleEntity instances with system=true
    - Verify deleteById throws ForemenApiException(403) and entity remains in DB

  - [x] 10.3 Write property test for permission replacement correctness
    - **Property 3: Permission Replacement Correctness**
    - **Validates: Requirements 8.2, 12.3, 13.1**
    - Generate random subsets of resources and operations
    - Call replacePermissions, verify result matches input exactly (count, resource IDs, operation IDs)

  - [x] 10.4 Write property test for role-resource uniqueness invariant
    - **Property 4: Role-Resource Uniqueness Invariant**
    - **Validates: Requirements 13.1**
    - Generate sequences of replacePermissions calls, verify no duplicate (role_id, resource_id) after each call

  - [x] 10.5 Write property test for i18n empty value normalization
    - **Property 5: I18n Empty Value Normalization**
    - **Validates: Requirements 10.5**
    - Generate blank strings (empty, whitespace-only), verify mapping to DaoModel yields null for i18n fields (nameRU, namePL, descriptionRU, descriptionPL)

- [x] 11. Write integration tests (Testcontainers)
  - [x] 11.1 Write integration tests for ResourceController and OperationController
    - Test GET /api/resources returns seeded resources paginated
    - Test GET /api/resources/{id} returns single resource with locale-resolved name and description
    - Test GET /api/operations returns seeded operations paginated
    - Test GET /api/operations/{id} returns single operation with locale-resolved name
    - _Requirements: 5.4, 5.5, 6.4, 6.5_

  - [x] 11.2 Write integration tests for RoleController CRUD
    - Test POST /api/roles creates role (with descriptionRU, descriptionPL), returns 200
    - Test POST /api/roles with duplicate code returns 409
    - Test PUT /api/roles/{id} updates role fields (nameRU, namePL, descriptionRU, descriptionPL)
    - Test GET /api/roles returns paginated roles
    - Test GET /api/roles/{id} returns role detail with descriptionRU, descriptionPL
    - Test DELETE /api/roles/{id} (non-system) deletes successfully
    - Test DELETE /api/roles/{id} (system) returns 403
    - Test GET /api/roles/audit/{id} returns audit records
    - _Requirements: 7.4, 7.5, 7.6, 7.7, 13.5, 13.6_

  - [x] 11.3 Write integration tests for permission management
    - Test GET /api/roles/{id}/permissions returns current permissions
    - Test PUT /api/roles/{id}/permissions replaces permissions atomically
    - Test PUT with empty operations revokes operations for that resource
    - Test PUT with non-existent role returns 404
    - Test PUT with non-existent resource returns 400
    - Test PUT with non-existent operation returns 400
    - Test cascade delete: delete role → verify RoleResource records removed
    - Test unique constraint: verify no duplicate (role_id, resource_id)
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 12.3, 12.4, 12.5, 13.1, 13.2, 13.3, 13.4_

- [x] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Integration tests use Testcontainers with PostgreSQL for realistic DB behavior
- All entities inherit from BaseEntity (id, createdDate, createdBy, updatedDate, updatedBy)
- The FOR-01 CRUD framework (BaseEntity, ReadOnlyAdminDao, AdminDao, ReadOnlyAdminService, AdminService, AdminReadOnlyController, AdminController, ServiceToDaoMapper, ControllerToServiceMapper, I18nPropertiesMapper) is already implemented and available
- **i18n description**: ResourceEntity and RoleEntity use `descriptionRU`/`descriptionPL` (two columns), not a single `description`. The resolved `description` field appears in ServiceModel; extended models and DTOs expose both variants.
- **Lombok @Getter on services**: ResourceService, OperationService, and RoleService use `@Getter @RequiredArgsConstructor` with field names matching interface method names (e.g., `readDao` → `getReadDao()`). No manual `@Override` methods needed for standard getters.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3"] },
    { "id": 1, "tasks": ["1.4", "2.1", "2.2"] },
    { "id": 2, "tasks": ["2.3", "2.4"] },
    { "id": 3, "tasks": ["3.1"] },
    { "id": 4, "tasks": ["4.1", "4.2"] },
    { "id": 5, "tasks": ["6.1", "6.2", "6.3"] },
    { "id": 6, "tasks": ["7.1", "7.2"] },
    { "id": 7, "tasks": ["7.3"] },
    { "id": 8, "tasks": ["8.1", "8.2", "8.3"] },
    { "id": 9, "tasks": ["10.1", "10.5"] },
    { "id": 10, "tasks": ["10.2", "10.3", "10.4", "11.1", "11.2", "11.3"] }
  ]
}
```
