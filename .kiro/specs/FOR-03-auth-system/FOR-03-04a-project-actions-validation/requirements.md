# Requirements Document

## Introduction

This specification defines the Project Actions Validation layer for the Foremen backend (FOR-03-04a, an improvement spec branching off FOR-03-04-project-ownership). It closes a security gap left open by FOR-03-04: project-scoped **read lists** are filtered by the caller's accessible projects, but project-scoped **single-entity actions** (read-by-id, update, delete, and their bulk/soft-delete variants) are not.

FOR-03-04 delivered `ProjectScopedService`, whose default `addRequiredQuery()` produces a `WHERE <projectIdPath> IN (:allowedIds)` predicate that `ReadOnlyAdminService.buildFinalSpecification()` combines into `find`, `findExtended`, and `getCount`. That predicate is a list-level filter only. The single-entity CRUD paths in `AdminService` — `findById`, `update(id, model)`, `deleteById(id)`, `updateAll`, `updateSingleField`, `deleteAll`, `softDelete`, `setPropertiesToNull` — resolve the target row by a raw DAO `findById`/id-based query that never applies `addRequiredQuery()`. Combined with the resource+operation check (`@RequiresPermission` + `PermissionInterceptor`), this means a non-ADMIN caller who holds, for example, `(ROOMS, UPDATE)` can update or delete a Room that belongs to a project they are not a member of, because nothing verifies that the targeted entity's project is one of the caller's allowed projects.

This spec adds a mandatory, second level of authorization for project-scoped entities. Beyond level 1 (authenticated) and level 2 (role has the resource+operation permission), it introduces level 3 (project ownership on the specific entity being acted upon): before a single-entity action proceeds, the system MUST verify that the acted-upon entity's project id is contained in the caller's Allowed_Project_Ids, applying the same ADMIN bypass and empty-access-denies rules already established by FOR-03-04's read filter.

The chosen structure is a single CRUD contract for project-scoped services: `ProjectScopedService` **extends** `AdminService`. A project-scoped service implements exactly one CRUD interface — `ProjectScopedService` — and is never a plain `AdminService`. `ProjectScopedService` remains an interface built entirely from `default` methods (no class inheritance), so it stays lightweight and testable; it simply overrides the CRUD single-entity methods it inherits from `AdminService` so that each mutating/reading-by-id operation first runs the project-ownership check and then delegates to the inherited `AdminService` behavior via `AdminService.super.<method>(...)`. Consequently a concrete project-scoped service gets ordinary out-of-the-box CRUD behavior plus level-3 protection for free, and the only per-entity code it must write (beyond the standard CRUD plumbing every service supplies) is `getProjectIdPath()` — the single JPA path that drives both the list filter and (by default) the single-entity action resolver.

The mechanism rests on a resolution method on the contract, `Long getProjectId(ID entityId)`, which returns the owning project id for a given entity id. It is provided as a **default** on `ProjectScopedService`: the default derives the owning project id from the already-declared `getProjectIdPath()` — it builds a Criteria query that projects the value at that JPA path for the row whose id equals `entityId`, executes it via the inherited `EntityManager`, and returns the single value (or `null` when no such row exists). Because the default is derived from `getProjectIdPath()`, the **single mandatory per-entity override is `getProjectIdPath()`**; a concrete service overrides `getProjectId(ID)` only when it wants a bespoke resolution (e.g. a purpose-built DAO query). The shared decision logic (auth check, ADMIN bypass, allowed-set membership test, deny outcome) lives once on the interface (in `assertProjectAccess(ID)`) and reuses the caller-resolution and ADMIN-detection logic already present in `ProjectScopedService`, so every inherited CRUD override calls the same audited decision.

The feature builds directly on earlier deliverables:

- `ProjectScopedService` (`com.foremen.service.ProjectScopedService`) from FOR-03-04, whose default `addRequiredQuery()`, `getProjectIdPath()` override point, `allowedProjectIds(userId)` hook, ADMIN detection (`ForemenPermissionEvaluator.ADMIN_ROLE_CODE`), and principal-name-as-userId parsing this spec reuses. FOR-03-04 declared it as a standalone mixin; this spec re-declares it to **extend `AdminService`** so it becomes the single CRUD contract for project-scoped services (see the design for the migration of the FOR-03-04 read-filter members).
- The CRUD framework from FOR-01: `ReadOnlyAdminService.findById` and `AdminService.create`/`update`/`deleteById`/`updateAll`/`updateSingleField`/`deleteAll`/`softDelete`/`setPropertiesToNull`, and the write hooks `validateCreate`, `afterCreate`, and `validateUpdate` already defined as no-op defaults. `ProjectScopedService` inherits all of these and overrides the single-entity read/mutation methods to insert the ownership check ahead of the inherited behavior.
- `ProjectAccessCache` (`com.foremen.service.ProjectAccessCache`, `get(userId)` self-loading + `invalidate(userId)`) and `ProjectMemberDao` (`findDistinctProjectIdsByUserId`, `existsByUserIdAndProjectId`) from FOR-03-04.
- The permission enforcement layer from FOR-03-03: `@RequiresPermission`, `PermissionInterceptor` (401 `error.auth.unauthorized`, 403 `error.access.denied`, ADMIN bypass), and `ForemenPermissionEvaluator`.
- `ForemenApiException` + `ForemenControllerAdvice` from FOR-01-03, and the i18n bundles `messages.properties` (Polish base) and `messages_ru.properties` (Russian).

### Explicitly Out of Scope

The following are explicitly OUT of scope and belong to other specs:

- The `projects` table and any real project entity/service (Room, EstimateItem, MaterialPurchase, WorkOrder, etc.) — those arrive in FOR-06. This spec extends the infrastructure and proves the action-validation mechanism against the same test-only `@Entity` fixture family FOR-03-04 introduced; it does not wire the check onto any real project entity.
- Migration of the existing production controllers off `permitAll()` and onto `@RequiresPermission` — belongs to FOR-03-08. This spec does not add or change any `@RequiresPermission` annotations on production controllers.
- Any change to the list-level read filter contract (`addRequiredQuery()` / `getProjectIdPath()` semantics) beyond adding the new action-validation method and reusing shared helpers. The existing FOR-03-04 read-filtering behavior remains unchanged.
- The `project_members` schema, `ProjectMemberEntity`/`Dao`/`Service`/`Controller`, the `PROJECT_MEMBERS` ABAC seed, and the `ProjectAccessCache`/`ProjectAccessProperties` — all delivered by FOR-03-04 and reused here unchanged.
- CREATE-time project validation on association payloads (verifying the caller may attach a new entity to a given project id) — this spec validates actions on EXISTING entities resolved by id. CREATE-path project validation is noted as a follow-up and is not required here.
- Frontend concerns and any UI (FOR-03-06, FOR-03-07). This spec has no frontend surface and introduces no new entity fields requiring i18n variants; the only localization is one new error message code (Requirement 6).

This feature reuses existing infrastructure: `ProjectScopedService`, `ProjectAccessCache`, `ProjectMemberDao`, `ReadOnlyAdminService`/`AdminService`, `ForemenApiException` + `ForemenControllerAdvice`, `ForemenPermissionEvaluator.ADMIN_ROLE_CODE`, the i18n bundles, and the jqwik + JUnit 5 + Testcontainers conventions used across FOR-03.

## Glossary

- **Project_Scoped_Action**: A single-entity operation performed on a project-scoped entity resolved by its id — namely read-by-id, update-by-id, delete-by-id, and their multi-id/soft variants — as opposed to a list read filtered by a Specification.
- **Owning_Project_Id**: The project id that a given project-scoped entity belongs to, resolved for a specific entity id via the project-scoped service's project-id resolution.
- **Allowed_Project_Ids**: The set of project ids the current caller may access, computed as the distinct `project_id` values of the caller's `project_members` rows, obtained through `ProjectAccessCache.get(userId)`. (Defined by FOR-03-04; reused unchanged.)
- **Current_User_Id**: The numeric id of the authenticated caller, resolved from `SecurityContextHolder.getContext().getAuthentication().getName()`, which the JWT layer sets to the `sub` claim (the userId as a String). (Defined by FOR-03-04; reused unchanged.)
- **ADMIN_Role_Code**: The literal role code `ADMIN`, available as `com.foremen.service.permission.ForemenPermissionEvaluator.ADMIN_ROLE_CODE`.
- **ADMIN_Bypass**: The rule by which a caller whose role is ADMIN skips project-ownership validation entirely and may act on any entity, mirroring the FOR-03-04 read-filter ADMIN bypass.
- **Empty_Access_Denies**: The rule by which a non-ADMIN caller whose Allowed_Project_Ids set is empty is denied every Project_Scoped_Action, because no owning project id can be a member of an empty set.
- **ProjectScopedService**: The CRUD contract for project-scoped services (`com.foremen.service.ProjectScopedService`), declared in FOR-03-04 as a standalone mixin and, by this spec, re-declared to **extend `AdminService`** and be parameterized by the full CRUD type set — `ProjectScopedService<ServiceModel, ServiceExtendedModel, DaoModel, ID>` (abbreviated `<S, SE, D, ID>`). It remains an interface composed only of `default` methods (no class inheritance). It retains the FOR-03-04 read-filter members (`getProjectIdPath()`, `allowedProjectIds(userId)`, default `addRequiredQuery()`), and adds the `default` project-id action resolver `getProjectId(ID)` (derived from `getProjectIdPath()`, overridable), the shared `assertProjectAccess(ID)` decision, and `default` overrides of every inherited single-entity read/mutation method that run `assertProjectAccess` before delegating to the inherited `AdminService` behavior.
- **Project_Scoped_Exclusivity**: The rule that a project-scoped service implements exactly one CRUD contract — `ProjectScopedService` — and is never declared as a plain `AdminService` and never lists both `AdminService` and `ProjectScopedService` in its `implements` clause, so that the project-ownership overrides can never be bypassed by a sibling CRUD contract.
- **GetProjectId**: The method `Long getProjectId(ID entityId)` on ProjectScopedService that returns the Owning_Project_Id for the entity identified by `entityId`, or `null` when no such entity exists. It is a **default** method derived from the declared Project_Id_Path: the default builds a Criteria query projecting the value at Project_Id_Path for the row whose id equals `entityId` and executes it through the inherited EntityManager. A concrete service may override it to supply a bespoke resolution (for example a purpose-built DAO query), but is not required to.
- **AssertProjectAccess**: The new default method `assertProjectAccess(ID entityId)` on ProjectScopedService that enforces project ownership for a Project_Scoped_Action on a given entity id. It contains all the shared decision logic (auth resolution, ADMIN_Bypass, Empty_Access_Denies, membership test) and either returns normally (access allowed) or raises a ForemenApiException (access denied). It is invoked by the inherited CRUD overrides, not by concrete services directly.
- **Mutating_Crud_Method**: Any single-entity or by-id CRUD method inherited from AdminService/ReadOnlyAdminService whose target row(s) are resolved by id — namely `findById`, `update`, `updateAll`, `updateSingleField`, `deleteById`, `deleteAll`, `softDelete`, and `setPropertiesToNull` — that ProjectScopedService overrides to run AssertProjectAccess before delegating. (`create` is excluded: it has no existing entity id to resolve; CREATE-path validation is out of scope, see below.)
- **Project_Id_Path**: The JPA dot-path from the entity to its project id declared by `getProjectIdPath()` (for example `projectId`, `project.id`, or `room.project.id`). (Defined by FOR-03-04; reused, and available as one implementation strategy for GetProjectId.)
- **ReadOnlyAdminService**: The existing generic read interface (`com.foremen.service.ReadOnlyAdminService`) whose `findById(ID)` resolves a single entity by a raw DAO lookup and whose `buildFinalSpecification()` applies `addRequiredQuery()` to list reads. (Defined by FOR-01; `AdminService` extends it and `ProjectScopedService` extends `AdminService`, so its `findById` override point is inherited.)
- **AdminService**: The existing generic CRUD interface (`com.foremen.service.AdminService` extending ReadOnlyAdminService) exposing `create`, `update`, `deleteById`, `updateAll`, `updateSingleField`, `deleteAll`, `softDelete`, and `setPropertiesToNull`, plus the write hooks `validateCreate`, `afterCreate`, and `validateUpdate`. (Defined by FOR-01.) In this spec it is the direct super-interface of ProjectScopedService; its default method bodies are reached by the overrides via `AdminService.super.<method>(...)`.
- **ForemenApiException**: The existing application exception carrying an HTTP status, an i18n message code, and optional message parameters. (Defined by FOR-01-03.)
- **ForemenControllerAdvice**: The existing `@RestControllerAdvice` that translates a ForemenApiException's message code in the request locale and responds with the exception's HTTP status. (Defined by FOR-01-03.)
- **Access_Denied_Outcome**: The response produced when a Project_Scoped_Action is denied by project ownership: a `ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", entityId)`, chosen so an out-of-scope entity is indistinguishable from a non-existent one, consistent with how the list filter already makes out-of-scope rows invisible.
- **Entity_Not_Found_Outcome**: The response produced when the target entity id resolves to no entity at all: the existing `ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", entityId)` already raised by the CRUD framework.

## Requirements

### Requirement 1: Owning-Project Resolution Contract

**User Story:** As a backend developer, I want a single per-entity method that returns the owning project id for a given entity id, so that action-level project validation can be applied uniformly to any project-scoped entity regardless of how it reaches its project.

#### Acceptance Criteria

1. THE ProjectScopedService SHALL declare `getProjectIdPath()` as the single mandatory per-entity override, and SHALL provide `Long getProjectId(ID entityId)` as a default derived from `getProjectIdPath()`, so that a concrete project-scoped service obtains action validation by declaring only `getProjectIdPath()`.
2. THE default `getProjectId(ID entityId)` SHALL resolve the Owning_Project_Id by building a Criteria query that selects the value at the Project_Id_Path (reusing the same dot-path resolution the list filter uses, including join traversal for nested paths) for the row whose id equals `entityId`, and SHALL execute it through the inherited EntityManager.
3. WHEN the default `getProjectId(ID entityId)` is called with an entity id that resolves to no row, THE default SHALL return `null` to signal absence, so that the caller can distinguish a missing entity from an out-of-scope entity, without throwing.
4. THE ProjectScopedService SHALL allow a concrete service to override `getProjectId(ID entityId)` with a bespoke resolution (for example a purpose-built DAO query) that returns the owning project id or `null`, so that entities needing custom resolution are supported without changing the contract.
5. THE ProjectScopedService SHALL be parameterized by the full CRUD type set (`ServiceModel`, `ServiceExtendedModel`, `DaoModel`, and the id type `ID`) so that it can extend `AdminService` and so that `getProjectId` accepts the same id type the CRUD framework uses for that entity.
6. THE ProjectScopedService SHALL extend `AdminService`, so that it is the single CRUD contract a project-scoped service implements and inherits the full CRUD surface (create, read, update, delete, batch) directly, and so that the default `getProjectId` can reach the inherited `EntityManager` and DaoModel class.
7. THE ProjectScopedService SHALL be composed only of `default` (and abstract) interface members, introducing no class-level inheritance, so that it remains a lightweight interface a concrete service can supply with the standard CRUD plumbing.

---

### Requirement 2: Action Validation Decision Logic

**User Story:** As a security reviewer, I want the project-ownership decision for a single-entity action to be defined once on the interface with the same auth and ADMIN rules as the read filter, so that action protection is consistent, auditable, and not reimplemented per entity.

#### Acceptance Criteria

1. THE ProjectScopedService SHALL provide a default method `assertProjectAccess(ID entityId)` that enforces project ownership for a Project_Scoped_Action and contains the entire decision logic of this requirement, so that a concrete project-scoped service does not reimplement it.
2. WHEN `assertProjectAccess(ID entityId)` is invoked for a caller whose Role_Code equals ADMIN_Role_Code, THE ProjectScopedService SHALL return without raising, allowing the action to proceed (ADMIN_Bypass), and SHALL NOT call `getProjectId`.
3. IF the SecurityContext holds no authenticated caller when `assertProjectAccess(ID entityId)` is invoked, THEN THE ProjectScopedService SHALL raise the Access_Denied_Outcome.
4. WHEN `assertProjectAccess(ID entityId)` resolves the Current_User_Id, THE ProjectScopedService SHALL read it from the authenticated principal name in the SecurityContext, interpreting the principal name as the numeric userId set by the JWT layer, reusing the same parsing already used by the read filter; IF the principal name is blank or non-numeric, THEN THE ProjectScopedService SHALL raise the Access_Denied_Outcome.
5. WHEN `assertProjectAccess(ID entityId)` is invoked for a non-ADMIN caller whose Allowed_Project_Ids set is empty, THE ProjectScopedService SHALL raise the Access_Denied_Outcome (Empty_Access_Denies).
6. WHEN `assertProjectAccess(ID entityId)` is invoked for a non-ADMIN caller with a non-empty Allowed_Project_Ids set, THE ProjectScopedService SHALL resolve the Owning_Project_Id via `getProjectId(entityId)` and SHALL return without raising IF the Owning_Project_Id is a member of the Allowed_Project_Ids set.
7. IF `getProjectId(entityId)` returns a project id that is NOT a member of the caller's Allowed_Project_Ids set, THEN THE ProjectScopedService SHALL raise the Access_Denied_Outcome.
8. IF `getProjectId(entityId)` returns `null` for a non-ADMIN caller with a non-empty Allowed_Project_Ids set, THEN THE ProjectScopedService SHALL raise the Access_Denied_Outcome, so that a non-existent target entity and an out-of-scope target entity are indistinguishable to a non-ADMIN caller.
9. THE ProjectScopedService SHALL determine ADMIN status by an exact match of a granted authority against `ROLE_ADMIN` or `ADMIN`, reusing the same ADMIN-detection logic the read filter uses, so that the ADMIN rule is identical across read filtering and action validation.
10. THE ProjectScopedService SHALL obtain the Allowed_Project_Ids through the same `allowedProjectIds(userId)` hook the read filter uses, so that action validation and read filtering read the caller's accessible projects from one source (the ProjectAccessCache) and honor the same invalidation.

---

### Requirement 3: Enforcement on Single-Entity Read

**User Story:** As a security reviewer, I want a read-by-id of a project-scoped entity to be denied when the entity belongs to a project the caller cannot access, so that a non-ADMIN caller cannot read a single record outside their projects even though the list read already hides it.

#### Acceptance Criteria

1. THE ProjectScopedService SHALL override the inherited read-by-id method (`findById`) with a `default` implementation that invokes `assertProjectAccess(id)` and then delegates to the inherited behavior via `AdminService.super`, so that every project-scoped service reads a single entity by id through the ownership check without writing that override itself.
2. WHEN `assertProjectAccess(id)` returns normally for a read-by-id, THE ProjectScopedService SHALL return the entity exactly as the inherited CRUD behavior would (`AdminService.super.findById(id)`).
3. IF `assertProjectAccess(id)` raises the Access_Denied_Outcome for a read-by-id, THEN THE ProjectScopedService SHALL propagate that exception without delegating to the inherited behavior, so that the caller receives HTTP status 404 with message code `error.entity.not.found` via ForemenControllerAdvice.
4. WHEN an ADMIN caller reads a single entity by id, THE ProjectScopedService SHALL return the entity without a project-ownership check, through the ADMIN_Bypass.

---

### Requirement 4: Enforcement on Single-Entity Update

**User Story:** As a security reviewer, I want an update of a project-scoped entity to be denied when the entity belongs to a project the caller cannot access, so that holding the resource+operation permission is not sufficient to modify a record outside the caller's projects.

#### Acceptance Criteria

1. THE ProjectScopedService SHALL override the inherited update-by-id method (`update`) with a `default` implementation that invokes `assertProjectAccess(id)` for the targeted entity id and then delegates to the inherited behavior via `AdminService.super`, so that the check runs before any change is persisted.
2. IF `assertProjectAccess(id)` raises the Access_Denied_Outcome for an update, THEN THE ProjectScopedService SHALL not delegate to the inherited behavior, so that no change is persisted and the exception propagates as HTTP status 404 with message code `error.entity.not.found`.
3. WHEN `assertProjectAccess(id)` returns normally for an update, THE ProjectScopedService SHALL perform the update exactly as the inherited CRUD behavior would (`AdminService.super.update(id, model)`), including the existing validation and audit behavior.
4. WHEN an ADMIN caller updates a single entity by id, THE ProjectScopedService SHALL perform the update without a project-ownership check, through the ADMIN_Bypass.
5. THE ProjectScopedService SHALL override the inherited multi-id update path (`updateAll`) and single-field update path (`updateSingleField`) with `default` implementations that invoke `assertProjectAccess(id)` for each targeted entity id and abort the whole operation IF the check raises for any targeted id, before delegating to the inherited behavior.

---

### Requirement 5: Enforcement on Single-Entity Delete

**User Story:** As a security reviewer, I want a delete of a project-scoped entity to be denied when the entity belongs to a project the caller cannot access, so that a non-ADMIN caller cannot remove records outside their projects.

#### Acceptance Criteria

1. THE ProjectScopedService SHALL override the inherited delete-by-id method (`deleteById`) with a `default` implementation that invokes `assertProjectAccess(id)` for the targeted entity id and then delegates to the inherited behavior via `AdminService.super`, so that the check runs before the row is removed.
2. IF `assertProjectAccess(id)` raises the Access_Denied_Outcome for a delete, THEN THE ProjectScopedService SHALL not delegate to the inherited behavior, so that the row is not removed and the exception propagates as HTTP status 404 with message code `error.entity.not.found`.
3. WHEN `assertProjectAccess(id)` returns normally for a delete, THE ProjectScopedService SHALL perform the delete exactly as the inherited CRUD behavior would (`AdminService.super.deleteById(id)`), including the existing audit behavior.
4. WHEN an ADMIN caller deletes a single entity by id, THE ProjectScopedService SHALL perform the delete without a project-ownership check, through the ADMIN_Bypass.
5. THE ProjectScopedService SHALL override the inherited multi-id delete path (`deleteAll`), soft-delete path (`softDelete`), and set-properties-to-null path (`setPropertiesToNull`) with `default` implementations that invoke `assertProjectAccess(id)` for each targeted entity id and abort the whole operation IF the check raises for any targeted id, before delegating to the inherited behavior.

---

### Requirement 6: Localization of the New Error Handling

**User Story:** As an international user, I want an out-of-scope action to produce a message in my language, so that the denial is understandable and consistent with existing errors.

#### Acceptance Criteria

1. WHEN a Project_Scoped_Action is denied by project ownership, THE Access_Denied_Outcome SHALL carry the message code `error.entity.not.found`, which already has non-blank entries in both the Polish base bundle (`messages.properties`) and the Russian bundle (`messages_ru.properties`), so that no new code is required for the denial itself.
2. WHERE this spec introduces any new message code for an action-validation condition beyond the reused `error.entity.not.found`, THE message bundle SHALL define a non-blank entry for that code in both `messages.properties` (Polish) and `messages_ru.properties` (Russian).
3. WHEN a ForemenApiException raised by the action-validation logic is translated, THE ForemenControllerAdvice SHALL resolve the message text in the request locale using the existing translation mechanism.

---

### Requirement 7: Integration Without Regressing Read Filtering

**User Story:** As a maintainer, I want the new action validation added without changing the existing FOR-03-04 read-filter behavior, so that the enhancement is additive and does not alter previously verified guarantees.

#### Acceptance Criteria

1. THE ProjectScopedService SHALL retain its existing `getProjectIdPath()`, `allowedProjectIds(userId)`, and default `addRequiredQuery()` members with unchanged read-filter behavior after being re-declared to extend `AdminService`.
2. WHEN a project-scoped read list is performed, THE ProjectScopedService SHALL apply the existing `addRequiredQuery()` list filter exactly as before this spec, so that list reads for ADMIN, non-ADMIN-with-memberships, and non-ADMIN-with-none remain unchanged.
3. WHEN a concrete project-scoped service declares `implements ProjectScopedService` and supplies the standard CRUD plumbing plus `getProjectIdPath()` (and nothing else), THE ProjectScopedService SHALL provide both list-level read filtering and action-level validation without the concrete service overriding any CRUD method, `addRequiredQuery()`, `assertProjectAccess(id)`, or `getProjectId(id)`.
4. THE project-ownership overrides SHALL exist ONLY on ProjectScopedService, so that a plain `AdminService` (users, roles, resources) inherits the unchanged CRUD behavior and is unaffected; per Project_Scoped_Exclusivity, a project-scoped service SHALL NOT be declared as a plain `AdminService`, and SHALL NOT list both `AdminService` and `ProjectScopedService` in its `implements` clause.
5. THE ProjectScopedService SHALL override every Mutating_Crud_Method it inherits (`findById`, `update`, `updateAll`, `updateSingleField`, `deleteById`, `deleteAll`, `softDelete`, `setPropertiesToNull`) so that no by-id read or mutation reaches the inherited behavior without first passing `assertProjectAccess`; the `create` method (which resolves no existing id) SHALL be left inheriting the unchanged behavior.

---

### Requirement 8: Verification

**User Story:** As a maintainer, I want the action-validation decision and its enforcement proven by tests, so that the security guarantee is regression-protected.

#### Acceptance Criteria

1. THE feature SHALL include a jqwik property test that drives `assertProjectAccess(id)` with generated caller states — no principal, ADMIN authority, non-ADMIN with an empty allowed set, non-ADMIN with a non-empty allowed set — and generated owning-project-id outcomes (in-set, out-of-set, null), asserting: return without raising iff ADMIN, or non-ADMIN with a non-empty set whose resolved owning project id is in the set; and the Access_Denied_Outcome otherwise (no principal, non-numeric principal, empty set, out-of-set owning id, or null owning id for a non-ADMIN).
2. THE feature SHALL include integration tests (`@SpringBootTest` + Testcontainers postgres, `@ActiveProfiles("integration-test")`) that exercise read-by-id, update-by-id, and delete-by-id through a test-only project-scoped fixture service (which supplies only `getProjectIdPath()` and relies on the default `getProjectId`) for: an ADMIN caller (allowed regardless of ownership), a non-ADMIN caller acting on an in-scope entity (allowed), a non-ADMIN caller acting on an out-of-scope entity (denied with 404 `error.entity.not.found` and no state change on write paths), a non-ADMIN caller with no memberships (denied), and an unauthenticated caller (denied).
3. THE feature SHALL include an integration test asserting that a denied update and a denied delete leave the target row unchanged and present, respectively, confirming no partial mutation occurs.
4. THE feature SHALL include a test confirming that the existing list-read filtering (`find`/`findExtended`/`getCount`) behavior is unchanged for ADMIN, non-ADMIN-with-memberships, and non-ADMIN-with-none, so that the enhancement is proven additive.
5. THE feature SHALL include a guard test that enumerates every Mutating_Crud_Method declared on `AdminService`/`ReadOnlyAdminService` and asserts that `ProjectScopedService` overrides each one, so that a future mutating method added to the CRUD framework cannot silently reach the inherited behavior without an ownership check (`create` is the documented exception).
6. THE feature SHALL include an integration test proving the default `getProjectId(id)` resolves the owning project id from `getProjectIdPath()` for both a single-segment path (e.g. `projectId`) and a nested/dotted path (e.g. `project.id`), and returns `null` for a non-existent id, using the test-only fixture without a custom `getProjectId` override.
7. Property tests SHALL be jqwik `*PropertyTest.java` at a minimum of 100 iterations, tagged `// Feature: FOR-03-04a-project-actions-validation, Property N: ...`, and integration tests SHALL follow the FOR-03 Testcontainers conventions.
