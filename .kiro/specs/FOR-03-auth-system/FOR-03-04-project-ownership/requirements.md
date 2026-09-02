# Requirements Document

## Introduction

This specification defines the Project Ownership / project-scoping infrastructure for the Foremen backend (FOR-03-04, the fourth child spec of the FOR-03 auth system). It delivers the machinery that answers, for the currently authenticated user, the question: "which projects may this user see?" and then applies that answer automatically as a SQL-level filter to every read performed through the CRUD framework, with an ADMIN bypass that sees everything.

The feature builds directly on earlier deliverables:

- The role model from FOR-02-03 / FOR-02-04 (`RoleEntity`, table `roles`, seeded with `ADMIN`, `MANAGER`, `FOREMAN`, `WORKER`, `FINANCIER`, `CLIENT`).
- The JWT authentication from FOR-03-01, whose `JwtAuthenticationFilter` populates the Spring `SecurityContext` with a principal equal to the userId (from the `sub` claim, exposed as `Authentication.getName()`) and a single authority `ROLE_<roleCode>`.
- The permission evaluator from FOR-03-03, which established the Caffeine cache + `@ConfigurationProperties` conventions this spec mirrors for its own project-access cache.
- The CRUD framework from FOR-01, whose `ReadOnlyAdminService` exposes the `addRequiredQuery()` extension point combined into every read via `buildFinalSpecification()`.

Scope is strictly limited to:

- A Liquibase changeset (`014-create-project-members.xml`) creating the `project_members` table with a uniqueness constraint on `(user_id, project_id)`, registered in `changelog.xml`.
- A `ProjectMemberEntity` (extends `BaseEntity`) and `ProjectMemberDao`, including a query that loads the set of project ids for a user.
- A `ProjectMemberService` exposing assign, remove, and list operations, invalidating the project-access cache on every mutation.
- A `ProjectMemberController` (base path `/api/project-members`) exposing the `ProjectMemberService` operations over REST endpoints, provided now so membership management can be consumed later. This controller ships already protected by `@RequiresPermission` on the `PROJECT_MEMBERS` resource with the matching operation per endpoint; the wholesale `permitAll()` migration for OTHER controllers remains FOR-03-08.
- A Liquibase changeset (`015-seed-project-members-resource.xml`) registering the `PROJECT_MEMBERS` ABAC resource in the `resources` table and seeding per-system-role `role_resources` + `role_resource_operations` grants (ADMIN/MANAGER full CRUD, FOREMAN/FINANCIER read-only, WORKER/CLIENT none), registered in `changelog.xml` after `014-create-project-members.xml`, following the existing seed convention (009-seed-audit-resource).
- A `ProjectAccessCache` (Caffeine, `userId -> Set<Long> projectIds`) with a properties-bound TTL and explicit invalidation.
- A `ProjectAccessProperties` `@ConfigurationProperties` record with a code-level default TTL.
- A `ProjectScopedService` contract (an interface mixin for services) that plugs into `addRequiredQuery()` to filter reads by allowed project ids, with an ADMIN bypass, an empty-access-yields-empty-result rule, and nested project-id path resolution.
- Cache invalidation wiring on membership change and on user role change.
- Localization (PL + RU) of any new error message codes.
- Tests: jqwik property tests for the pure decision logic, example/unit tests, and Testcontainers integration tests for the DAO/migration and end-to-end filtering (using a throwaway test-only `@Entity` fixture that implements `ProjectScopedService`, since no real project entity exists yet).

### Explicitly Out of Scope

The following are explicitly OUT of scope and belong to other specs:

- The `projects` table and any foreign key from `project_members.project_id` to it — the `projects` table does not exist yet and arrives in FOR-06. In this spec, `project_id` is a plain `BIGINT` column with no foreign key.
- Concrete project entities and their services (Room, EstimateItem, MaterialPurchase, WorkOrder, etc.) — those arrive in FOR-06. This spec delivers only the infrastructure plus the base `ProjectScopedService` contract that future services will implement.
- Migration of the OTHER existing production controllers off `permitAll()` and onto `@RequiresPermission` — belongs to FOR-03-08. This spec does not wire the project-scoping filter onto any real project entity/controller; it proves the project-scoping mechanism with a test-only fixture. The new `ProjectMemberController` introduced here IS an exception to that deferral: it is given fine-grained `@RequiresPermission` authorization on the `PROJECT_MEMBERS` resource in this spec (Requirement 10), backed by the `PROJECT_MEMBERS` ABAC seed (Requirement 11). Only the migration of the other, pre-existing controllers remains FOR-03-08.
- User-list filtering by role — belongs to another spec and is not included here.
- OTP and frontend concerns (FOR-03-05, FOR-03-06, FOR-03-07).

This feature reuses existing infrastructure: `BaseEntity`, `RoleEntity` / `roles`, `UserEntity` / `users`, the Caffeine cache abstraction and `@ConfigurationProperties` pattern from FOR-03-03, `ForemenApiException` + `ForemenControllerAdvice`, the i18n message bundles (`messages.properties` PL base, `messages_ru.properties` RU), and the jqwik + JUnit 5 + Testcontainers conventions used in FOR-03-03 / FOR-02-03.

## Glossary

- **Project_Member**: A row in the `project_members` table associating one user with one project and one project role, expressing that the user has access to that project under that role.
- **Project_Id**: A `BIGINT` identifier of a project. Because the `projects` table does not exist yet (FOR-06), Project_Id is stored as a plain column with no foreign key in this spec.
- **Project_Role**: The role a Project_Member holds on a project, modeled as a reference to an existing `RoleEntity` via the `project_role_id` foreign key to `roles(id)`.
- **ProjectMemberEntity**: The JPA entity (`com.foremen.dao.model.ProjectMemberEntity`) mapped to `project_members`, extending `BaseEntity`, holding a `@ManyToOne` `UserEntity` (join column `user_id`), a plain `Project_Id` column, and a `@ManyToOne` `RoleEntity` (join column `project_role_id`).
- **ProjectMemberDao**: The Spring Data repository (`com.foremen.dao.ProjectMemberDao`) for `ProjectMemberEntity`, exposing a query that returns the set of Project_Ids for a given user id.
- **ProjectMemberService**: The service (`com.foremen.service.ProjectMemberService`) exposing assign-member, remove-member, and list operations, and invalidating the Project_Access_Cache on every mutation.
- **ProjectMemberController**: The REST controller (`com.foremen.controller.ProjectMemberController`) mapped to the base path `/api/project-members`, exposing the ProjectMemberService operations (assign, remove, list members of a project, list projects of a user) over HTTP under the existing REST conventions. Each endpoint is protected by a `@RequiresPermission` annotation on the PROJECT_MEMBERS_Resource with the matching operation (assign → CREATE, remove → DELETE, list-members → READ, list-projects → READ), enforced by the PermissionInterceptor.
- **PROJECT_MEMBERS_Resource**: The ABAC resource identified by the resource code `PROJECT_MEMBERS`, registered in the `resources` table by changeset `015-seed-project-members-resource.xml`, governing access to project-membership management via the RBAC matrix (`role_resources` + `role_resource_operations`).
- **RequiresPermission**: The existing method-level annotation `com.foremen.config.security.RequiresPermission` (from FOR-03-03) carrying `resource()` and `operation()` string attributes, declaring the ABAC permission a protected controller method requires.
- **PermissionInterceptor**: The existing Spring MVC `HandlerInterceptor` `com.foremen.config.security.PermissionInterceptor` (from FOR-03-03) that, for a handler method annotated with RequiresPermission, evaluates the caller's role against the RBAC matrix; it throws a ForemenApiException resolving to HTTP 401 `error.auth.unauthorized` when no authenticated principal is present and HTTP 403 `error.access.denied` when the permission is denied, and it bypasses ADMIN regardless of the matrix.
- **AssignProjectMemberRequest**: The request DTO for assigning a member, carrying the fields `userId`, `projectId`, and `projectRoleId`.
- **ProjectMemberResponse**: The response DTO representing a Project_Member, carrying the fields `id`, `userId`, `projectId`, `projectRoleId`, and `projectRoleCode`.
- **Allowed_Project_Ids**: The set of Project_Ids a given user may access, computed as the distinct `project_id` values of that user's Project_Member rows.
- **Project_Access_Cache**: A long-lived Caffeine cache mapping a user id to that user's Allowed_Project_Ids (`ProjectAccessCache`, `com.foremen.service.ProjectAccessCache`). Freshness is guaranteed by explicit per-user invalidation on every relevant change; the TTL is only an idle/safety-net expiry. This mirrors the FOR-03-03 `PermissionCache` decision (long-lived cache kept fresh by explicit invalidation, not by a short TTL).
- **Project_Access_Cache_TTL**: The idle/safety-net TTL of the Project_Access_Cache, defaulting to 1440 minutes (24 hours), configurable via config property `foremen.project-access.cache-ttl-minutes`. It is a long expiry measured from the entry's last write/access that bounds staleness only when no explicit invalidation has occurred; it is NOT the primary freshness mechanism, which is explicit per-user invalidation (Requirement 8).
- **ProjectAccessProperties**: The `@ConfigurationProperties(prefix = "foremen.project-access")` record (`com.foremen.config.security.ProjectAccessProperties`) binding `cache-ttl-minutes` with a code-level default of 1440 (24 hours), validated positive at startup.
- **ProjectScopedService**: The interface mixin (`com.foremen.service.ProjectScopedService`) that a project-scoped service implements to obtain automatic project filtering. It separates two concerns: (a) a default `addRequiredQuery()` implementation, provided by the interface, that contains ALL the shared decision logic (ADMIN_Bypass, no-auth match-nothing, empty-allowed-set match-nothing, non-empty IN over the resolved path) and is NOT meant to be overridden in the normal case; and (b) a separate `getProjectIdPath()` method that each concrete project-scoped service/entity overrides to declare its own JPA path to the Project_Id. `getProjectIdPath()` is the single designated override point for a concrete entity.
- **Project_Id_Path**: A JPA property path from the filtered entity to its Project_Id, returned by `getProjectIdPath()`, expressed in dot notation (for example `projectId`, `project.id`, or `room.project.id`). It is the sole per-entity customization point of a project-scoped service.
- **ADMIN_Bypass**: The rule by which a caller whose role is `ADMIN` receives an unfiltered view: `addRequiredQuery()` returns `null` so no project filter is combined into the read.
- **Empty_Access_Result**: The rule by which a non-ADMIN caller with an empty Allowed_Project_Ids set sees no rows: `addRequiredQuery()` returns a Specification that matches nothing.
- **ReadOnlyAdminService**: The existing generic CRUD interface (`com.foremen.service.ReadOnlyAdminService`) whose `buildFinalSpecification(rawQuery)` combines the parsed user query with `addRequiredQuery()` via `Specification.where(userSpec).and(accessSpec)` when `accessSpec` is non-null, applied to `find()`, `findExtended()`, and `getCount()`.
- **Current_User_Id**: The numeric id of the authenticated caller, resolved from `SecurityContextHolder.getContext().getAuthentication().getName()`, which the JWT layer sets to the `sub` claim (the userId as a String).
- **Role_Code**: The string code identifying a role (`ADMIN`, `MANAGER`, `FOREMAN`, `WORKER`, `FINANCIER`, `CLIENT`), exposed by the `ROLE_<roleCode>` authority in the SecurityContext.
- **ADMIN_Role_Code**: The literal role code `ADMIN`, available as the existing constant `com.foremen.service.permission.ForemenPermissionEvaluator.ADMIN_ROLE_CODE`.
- **BaseEntity**: The existing common superclass (`com.foremen.dao.model.BaseEntity`) providing the `id` and auditing columns (e.g. `created_date`).
- **RoleEntity**: The existing JPA entity (`com.foremen.dao.model.RoleEntity`) for a role, mapped to `roles`, carrying `code`, `nameRU`, `namePL`, and `system`.
- **UserEntity**: The existing JPA entity (`com.foremen.dao.model.UserEntity`) for a user, mapped to `users`, holding a `@ManyToOne` `RoleEntity role` (join column `role_id`).
- **UserService**: The existing service managing users, whose role-change update path is the documented touch point for Project_Access_Cache invalidation on a user role change.
- **ForemenApiException**: The existing application exception carrying an HTTP status, an i18n message code, and optional parameters.

## Requirements

### Requirement 1: project_members Schema and Uniqueness

**User Story:** As a backend developer, I want a `project_members` table linking users to projects with a project role, so that project access can be stored and queried.

#### Acceptance Criteria

1. WHEN the Liquibase changeset `014-create-project-members.xml` runs, THE Migration SHALL create a `project_members` table with an `id` primary key, a `user_id` column, a `project_id` column of type `BIGINT`, a `project_role_id` column of type `BIGINT`, and the `BaseEntity` audit column `created_date`.
2. THE Migration SHALL define a foreign key from `project_members.user_id` to `users(id)`.
3. THE Migration SHALL define a foreign key from `project_members.project_role_id` to `roles(id)`.
4. THE Migration SHALL define the `project_members.project_id` column as a plain `BIGINT` with no foreign key, because the `projects` table does not exist in this spec.
5. THE Migration SHALL define a unique constraint on the pair (`user_id`, `project_id`) so that a user has at most one Project_Member row per project.
6. THE Migration SHALL register `014-create-project-members.xml` in `changelog.xml` after changeset `013-create-invite-tokens.xml`.

---

### Requirement 2: Project Member Entity and DAO

**User Story:** As a backend developer, I want a JPA entity and repository for project members, so that membership rows can be persisted and read through the ORM.

#### Acceptance Criteria

1. THE ProjectMemberEntity SHALL extend BaseEntity and SHALL map to the `project_members` table.
2. THE ProjectMemberEntity SHALL declare a `@ManyToOne` association to UserEntity using the join column `user_id`.
3. THE ProjectMemberEntity SHALL declare a `@ManyToOne` association to RoleEntity using the join column `project_role_id` to model the Project_Role as a reference to the existing `roles` table.
4. THE ProjectMemberEntity SHALL declare the Project_Id as a plain `BIGINT` field with no association, because the `projects` table does not exist in this spec.
5. THE ProjectMemberEntity SHALL use lazy fetching for the UserEntity and RoleEntity associations, consistent with UserEntity's association to RoleEntity.
6. THE ProjectMemberDao SHALL expose a query that returns the distinct set of Project_Ids for a given user id.

---

### Requirement 3: Membership Management

**User Story:** As an administrator, I want to assign users to projects, remove them, and list memberships, so that project access is maintained.

#### Acceptance Criteria

1. WHEN ProjectMemberService assigns a member with a user id, a Project_Id, and a project role id, THE ProjectMemberService SHALL persist a Project_Member row associating that user with that Project_Id under the RoleEntity identified by the project role id.
2. IF ProjectMemberService is asked to assign a member for a (user id, Project_Id) pair that already has a Project_Member row, THEN THE ProjectMemberService SHALL raise a ForemenApiException indicating a duplicate membership rather than creating a second row.
3. IF ProjectMemberService is asked to assign a member with a project role id that does not resolve to an existing RoleEntity, THEN THE ProjectMemberService SHALL raise a ForemenApiException indicating the role was not found.
4. WHEN ProjectMemberService removes the membership of a user from a Project_Id, THE ProjectMemberService SHALL delete the corresponding Project_Member row.
5. WHEN ProjectMemberService lists the members of a Project_Id, THE ProjectMemberService SHALL return every Project_Member row whose `project_id` equals that Project_Id.
6. WHEN ProjectMemberService lists the projects of a user, THE ProjectMemberService SHALL return the distinct set of Project_Ids drawn from that user's Project_Member rows.

---

### Requirement 4: Project Access Cache Load and TTL

**User Story:** As an operator, I want each user's accessible projects cached in a long-lived cache kept fresh by explicit invalidation, so that repeated reads avoid repeated database lookups while revoked access is honored promptly through invalidation rather than through a short expiry.

#### Acceptance Criteria

1. WHEN the Project_Access_Cache is asked for a user id that is not present, THE Project_Access_Cache SHALL load the Allowed_Project_Ids from the database via ProjectMemberDao and store the result keyed by that user id.
2. WHEN the Project_Access_Cache is asked for a user id that is present, THE Project_Access_Cache SHALL return the cached Allowed_Project_Ids without querying the database.
3. WHEN a user has no Project_Member rows, THE Project_Access_Cache SHALL return an empty Allowed_Project_Ids set for that user id.
4. THE Project_Access_Cache SHALL rely on explicit per-user invalidation (Requirement 8) as the authoritative freshness mechanism, keeping entries long-lived rather than depending on a short expiry for correctness.
5. THE Project_Access_Cache SHALL expire each entry after the Project_Access_Cache_TTL as an idle/safety-net expiry that bounds staleness only when no explicit invalidation has occurred, mirroring the FOR-03-03 PermissionCache decision.
6. THE Project_Access_Cache_TTL SHALL default to 1440 minutes (24 hours) and SHALL be configurable via the config property `foremen.project-access.cache-ttl-minutes`.

---

### Requirement 5: Project Access Properties Configuration

**User Story:** As an operator, I want the project-access cache TTL bound from configuration with a safe default, so that environments without explicit config still start correctly.

#### Acceptance Criteria

1. THE ProjectAccessProperties SHALL bind the property `foremen.project-access.cache-ttl-minutes` from configuration.
2. WHERE `foremen.project-access.cache-ttl-minutes` is unset, THE ProjectAccessProperties SHALL supply a code-level default of 1440 minutes (24 hours) so that profiles without explicit configuration start cleanly.
3. IF `foremen.project-access.cache-ttl-minutes` is configured with a non-positive value, THEN THE ProjectAccessProperties SHALL abort application context startup with a configuration validation error.

---

### Requirement 6: Automatic CRUD Filtering With ADMIN Bypass

**User Story:** As a security reviewer, I want project-scoped reads to be filtered automatically to the caller's accessible projects, so that a non-ADMIN user never reads data from projects they do not belong to.

#### Acceptance Criteria

1. WHEN a ProjectScopedService computes its required query for a caller whose Role_Code equals ADMIN_Role_Code, THE ProjectScopedService SHALL return `null` so that no project filter is combined into the read (ADMIN_Bypass).
2. WHEN a ProjectScopedService computes its required query for a non-ADMIN caller whose Allowed_Project_Ids set is non-empty, THE ProjectScopedService SHALL return a Specification restricting the Project_Id_Path to values contained in the Allowed_Project_Ids set.
3. WHEN a ProjectScopedService computes its required query for a non-ADMIN caller whose Allowed_Project_Ids set is empty, THE ProjectScopedService SHALL return a Specification that matches no rows (Empty_Access_Result).
4. WHEN ReadOnlyAdminService builds the final specification for a ProjectScopedService, THE ReadOnlyAdminService SHALL combine the parsed user query with the ProjectScopedService required query so that the project filter is applied to `find`, `findExtended`, and `getCount`.
5. WHEN a ProjectScopedService resolves the Current_User_Id, THE ProjectScopedService SHALL read it from the authenticated principal name in the SecurityContext, interpreting the principal name as the numeric userId set by the JWT layer.
6. IF the SecurityContext holds no authenticated caller when a ProjectScopedService computes its required query, THEN THE ProjectScopedService SHALL return a Specification that matches no rows.
7. THE ProjectScopedService SHALL determine ADMIN status by an exact match of a granted authority against `ROLE_ADMIN` or `ADMIN`, independently of the private `isCallerAdmin()` method of ReadOnlyAdminService.
8. THE ProjectScopedService SHALL provide the entire decision logic of criteria 1 through 7 within a single default `addRequiredQuery()` implementation on the interface, so that this shared logic is defined once and is not intended to be overridden by a concrete project-scoped service in the normal case.
9. THE ProjectScopedService SHALL declare `getProjectIdPath()` as a separate method with no default project-id path, so that `getProjectIdPath()` is the single designated override point through which a concrete project-scoped service or entity declares its own Project_Id_Path.
10. WHEN a concrete project-scoped service mixes in ProjectScopedService and overrides only `getProjectIdPath()`, THE ProjectScopedService SHALL apply full project filtering to that service's reads using the inherited default `addRequiredQuery()`, without the concrete service reimplementing `addRequiredQuery()`.

---

### Requirement 7: Nested Project-Id Path Resolution

**User Story:** As a developer implementing a project-scoped service, I want to declare the JPA path to the project id in dot notation, so that entities that reach their project through associations are filtered correctly.

#### Acceptance Criteria

1. WHERE a Project_Id_Path is a single property name with no dot (for example `projectId`), THE ProjectScopedService SHALL restrict that property of the query root to the Allowed_Project_Ids.
2. WHERE a Project_Id_Path contains dot-separated segments (for example `project.id` or `room.project.id`), THE ProjectScopedService SHALL traverse each non-final segment as a JPA join and restrict the final segment to the Allowed_Project_Ids.
3. THE ProjectScopedService SHALL produce a filter whose result contains only rows whose resolved Project_Id is a member of the Allowed_Project_Ids set.

---

### Requirement 8: Cache Invalidation on Membership and Role Change

**User Story:** As an administrator, I want membership and role changes to take effect promptly, so that a revoked project access is not honored from a stale cache.

#### Acceptance Criteria

1. WHEN ProjectMemberService assigns a member, THE Project_Access_Cache SHALL evict the entry keyed by the affected user id.
2. WHEN ProjectMemberService removes a member, THE Project_Access_Cache SHALL evict the entry keyed by the affected user id.
3. WHEN the Project_Access_Cache entry for a user id has been evicted, THE Project_Access_Cache SHALL reload that user's Allowed_Project_Ids from the database on the next request for that user id.
4. WHEN a user's role changes through UserService, THE Project_Access_Cache SHALL evict the entry keyed by that user id.
5. THE Project_Access_Cache SHALL expose an explicit `invalidate(userId)` operation that removes the entry for a single user id.

---

### Requirement 9: Localization of New Error Codes

**User Story:** As an international user, I want project-membership errors in my language, so that the messages are understandable.

#### Acceptance Criteria

1. WHERE this spec introduces a message code for a duplicate membership, THE message bundle SHALL define a non-blank entry for that code in both the Polish base bundle (`messages.properties`) and the Russian bundle (`messages_ru.properties`).
2. WHERE this spec introduces a message code for a project role that was not found, THE message bundle SHALL define a non-blank entry for that code in both the Polish base bundle (`messages.properties`) and the Russian bundle (`messages_ru.properties`).
3. WHERE this spec introduces a message code for a membership that was not found, THE message bundle SHALL define a non-blank entry for that code in both the Polish base bundle (`messages.properties`) and the Russian bundle (`messages_ru.properties`).
4. WHEN a ForemenApiException raised by ProjectMemberService or ProjectMemberController is translated, THE ForemenControllerAdvice SHALL resolve the message text in the request locale.

---

### Requirement 10: Project Member Management API

**User Story:** As an administrator, I want REST endpoints for assigning, removing, and listing project memberships, so that project access can be managed over HTTP and consumed by later specs.

This requirement exposes the ProjectMemberService operations of Requirement 3 over REST; the service-level behavior (persistence, duplicate handling, role resolution, listing) is defined in Requirement 3 and is reused unchanged. Each endpoint is governed by the PROJECT_MEMBERS_Resource of Requirement 11 through the existing RequiresPermission + PermissionInterceptor mechanism from FOR-03-03. The broad `permitAll()` → `authenticated()` SecurityConfig migration for the OTHER controllers remains FOR-03-08; this controller ships already annotated with `@RequiresPermission` on PROJECT_MEMBERS, which is in scope here. The endpoint paths still need to be reachable through SecurityConfig; where FOR-03-08 has not yet tightened the SecurityConfig rules, the PermissionInterceptor still enforces authorization, mirroring how FOR-03-03 ordered 401-vs-403 with a defensive 401 in the interceptor when the SecurityContext holds no authenticated principal.

#### Acceptance Criteria

1. THE ProjectMemberController SHALL be mapped to the base path `/api/project-members`, carrying `projectId` in the request body or query rather than in the path, because no `projects` table exists in this spec.
2. THE ProjectMemberController assign-member endpoint SHALL be annotated `@RequiresPermission(resource = "PROJECT_MEMBERS", operation = "CREATE")`, THE remove-member endpoint SHALL be annotated `@RequiresPermission(resource = "PROJECT_MEMBERS", operation = "DELETE")`, and THE list-members and list-projects endpoints SHALL each be annotated `@RequiresPermission(resource = "PROJECT_MEMBERS", operation = "READ")`.
3. IF a caller invoking any ProjectMemberController endpoint lacks the required (PROJECT_MEMBERS, operation) permission, THEN THE PermissionInterceptor SHALL reject the request with HTTP status 403 and message code `error.access.denied` via the existing ForemenControllerAdvice.
4. IF an unauthenticated caller invokes any ProjectMemberController endpoint, THEN THE PermissionInterceptor SHALL reject the request with HTTP status 401 and message code `error.auth.unauthorized`.
5. WHERE the authenticated caller's Role_Code equals ADMIN_Role_Code, THE PermissionInterceptor SHALL allow the request to reach every ProjectMemberController endpoint through the existing ADMIN bypass regardless of the RBAC matrix.
6. WHEN a caller with the CREATE permission on PROJECT_MEMBERS invokes the assign-member endpoint with an AssignProjectMemberRequest carrying `userId`, `projectId`, and `projectRoleId`, THE ProjectMemberController SHALL invoke ProjectMemberService assign and return the created membership as a ProjectMemberResponse with HTTP status 201.
7. IF a caller invokes the assign-member endpoint for a (`userId`, `projectId`) pair that already has a Project_Member row, THEN THE ProjectMemberController SHALL return an error response produced from the ForemenApiException indicating a duplicate membership with HTTP status 409.
8. IF a caller invokes the assign-member endpoint with a `projectRoleId` that does not resolve to an existing RoleEntity, THEN THE ProjectMemberController SHALL return an error response produced from the ForemenApiException indicating the role was not found with HTTP status 404.
9. WHEN a caller invokes the remove-member endpoint for a `userId` and `projectId` that has a Project_Member row, THE ProjectMemberController SHALL invoke ProjectMemberService remove and return HTTP status 204 with no body.
10. IF a caller invokes the remove-member endpoint for a `userId` and `projectId` that has no Project_Member row, THEN THE ProjectMemberController SHALL return an error response produced from a ForemenApiException indicating the membership was not found with HTTP status 404.
11. WHEN a caller invokes the list-members endpoint for a `projectId`, THE ProjectMemberController SHALL return a ProjectMemberResponse for every Project_Member row whose `project_id` equals that `projectId`.
12. WHEN a caller invokes the list-projects endpoint for a `userId`, THE ProjectMemberController SHALL return the distinct set of Project_Ids drawn from that user's Project_Member rows.
13. THE AssignProjectMemberRequest SHALL carry the fields `userId`, `projectId`, and `projectRoleId`.
14. THE ProjectMemberResponse SHALL carry the fields `id`, `userId`, `projectId`, `projectRoleId`, and `projectRoleCode`.

---

### Requirement 11: PROJECT_MEMBERS ABAC Resource and Permission Seed

**User Story:** As a security administrator, I want the ProjectMember resource registered in ABAC with per-role operations seeded for system roles, so that access to project-membership management is governed by the existing RBAC matrix.

This requirement backs the endpoint authorization of Requirement 10 by registering the PROJECT_MEMBERS_Resource and seeding its per-system-role grants, following the seed convention established by `009-seed-audit-resource.xml` and `007-seed-permissions.xml` (resource INSERT guarded by a MARK_RAN precondition, join rows inserted via `SELECT ... FROM roles r, resources res WHERE r.code = ... AND res.code = ...` with `created_date NOW()` and `created_by 'system'`, and operations joined via `operations o ON o.code IN (...)`, all guarded by `sqlCheck` preconditions with `expectedResult="0"`).

#### Acceptance Criteria

1. WHEN the Liquibase changeset `015-seed-project-members-resource.xml` runs, THE Migration SHALL insert a resource row into `resources` with code `PROJECT_MEMBERS` and localized `name_ru`/`name_pl` and `description_ru`/`description_pl` values, following the `009-seed-audit-resource` convention, guarded by a MARK_RAN precondition so that a re-run does not duplicate the row.
2. THE Migration SHALL register `015-seed-project-members-resource.xml` in `changelog.xml` after `014-create-project-members.xml`.
3. WHEN the Migration seeds grants on the PROJECT_MEMBERS_Resource, THE Migration SHALL insert `role_resources` and `role_resource_operations` rows for the system roles only, granting ADMIN the operations CREATE, READ, UPDATE, and DELETE; granting MANAGER the operations CREATE, READ, UPDATE, and DELETE; granting FOREMAN the operation READ; and granting FINANCIER the operation READ.
4. THE Migration SHALL grant no operations on the PROJECT_MEMBERS_Resource to any role whose `system` value is false, so that custom roles receive no access to project-membership management by default.
5. THE Migration SHALL guard every INSERT with a `sqlCheck` precondition using `expectedResult="0"`, consistent with the existing seed changesets, so that the changeset is safe to re-run.
6. THE Migration SHALL draw the seeded operation codes from the existing operations catalog (CREATE, READ, UPDATE, DELETE) and SHALL NOT create new operation codes.
7. WHERE the WORKER and CLIENT system roles are concerned, THE Migration SHALL leave each with no `role_resources` entry (and therefore no operations) for the PROJECT_MEMBERS_Resource, expressing deny-by-default.
