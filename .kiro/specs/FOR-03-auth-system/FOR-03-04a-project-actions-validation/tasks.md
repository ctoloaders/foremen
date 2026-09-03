# Implementation Plan: FOR-03-04a Project Actions Validation

## Overview

This plan implements action-level project-ownership validation for the Foremen backend (module `foremen-backend`, package root `com.foremen`), an additive improvement over FOR-03-04. FOR-03-04 filters project-scoped LIST reads via `ProjectScopedService.addRequiredQuery()`; this spec adds mandatory project-ownership checks on single-entity ACTIONS (read-by-id, update, delete, and their multi-id/soft variants), which today bypass that filter.

It proceeds top-down from the contract (Variant A): re-declare `ProjectScopedService` to **extend `AdminService<S, SE, D, ID>`**, add the `getProjectId(ID)` override point and the shared `default assertProjectAccess(ID)` decision (reusing the existing ADMIN/auth/allowed-set helpers), and add `default` overrides of every by-id CRUD method that run `assertProjectAccess` then delegate via `AdminService.super`; then prove the pure decision with a jqwik property test and example tests and lock the override set with a reflection guard test; then collapse the test-only fixture to the single-contract shape (`implements ProjectScopedService` only, no CRUD overrides) and implement `getProjectId`; then prove enforcement, no-partial-mutation, and read-filter non-regression end to end against the fixture; and finally author `test-cases.md`.

The only production source modified is `ProjectScopedService.java`. No schema, DTO, controller, migration, ABAC seed, or message-bundle change is required: the denial reuses the existing `error.entity.not.found` (404), which is already localized in both bundles. The FOR-03-04 test fixture (`ScopedFixtureEntity` / `ScopedFixtureService`) is reused and simplified in `src/test`.

Structure rules (Variant A): a project-scoped service implements exactly one CRUD contract — `ProjectScopedService` — and is never a plain `AdminService` and never lists both (project-scoped exclusivity). `ProjectScopedService` stays a pure interface (only `default`/abstract members). The by-id CRUD overrides live on the interface, so concrete services inherit them and write only the plumbing + `getProjectIdPath()` + `getProjectId(ID)`. `create` is intentionally not overridden.

Property tests use jqwik (`*PropertyTest.java`, `@Property(tries = 100)`) tagged `// Feature: FOR-03-04a-project-actions-validation, Property N: ...`. Example/unit tests use JUnit 5. Integration tests use `@SpringBootTest` + Testcontainers (postgresql) + Spring Security Test with `@ActiveProfiles("integration-test")`.

Scope guards: does NOT create the `projects` table or any real project entity/service (FOR-06); does NOT add or change `@RequiresPermission` on production controllers (FOR-03-08); does NOT change the existing FOR-03-04 read-filter behavior (`addRequiredQuery()` / `getProjectIdPath()` remain unchanged). Action validation is proven against the test-only fixture, not a real project entity.

Test conventions:
- Property test files: `*PropertyTest.java`, minimum 100 iterations, tagged with the design property number.
- Optional test sub-tasks are postfixed with `*` and may be skipped for a faster MVP; core implementation tasks are never optional.
- Each task cites the requirement numbers it satisfies and, where applicable, the design property number.

## Tasks

- [x] 1. Rework the `ProjectScopedService` contract to extend `AdminService` and add action validation
  - [x] 1.1 Re-declare `ProjectScopedService extends AdminService<S, SE, D, ID>` and add the `getProjectId` override point
    - In `com.foremen.service.ProjectScopedService`, change the declaration from a standalone mixin `<DaoModel>` to `interface ProjectScopedService<S, SE, D, ID> extends AdminService<S, SE, D, ID>` (the same type set `AdminService` uses); keep the existing `getProjectIdPath()`, `allowedProjectIds(Long)`, and `addRequiredQuery()` (now annotated `@Override` of the inherited default) with unchanged behavior; keep the interface composed only of `default`/abstract members (no class inheritance)
    - Add a `default Long getProjectId(ID entityId)` derived from `getProjectIdPath()`: build a `CriteriaQuery<Long>` via `getEntityManager().getCriteriaBuilder()`, `from(getDaoModelClass())`, select `resolvePath(root, getProjectIdPath()).as(Long.class)` where `cb.equal(root.get("id"), entityId)`, execute with `getEntityManager().createQuery(cq).setMaxResults(1).getResultList()`, and return the single value or `null` when empty (never `getSingleResult()`); mirror the existing `AdminService.softDelete`/`setPropertiesToNull` Criteria idiom. `getEntityManager()`/`getDaoModelClass()` are inherited from `AdminService`, so no new accessor is needed. The method stays overridable for a bespoke resolution (do not make it abstract). `getProjectIdPath()` remains the single mandatory per-entity override
    - Update the two existing implementors in `src/test` (`com.foremen.scoping.ScopedFixtureService` and the `StubScopedService` inside `ProjectScopedServiceDecisionPropertyTest`) to the new signature so the module still compiles (`ScopedFixtureService` drops the separate `ReadOnlyAdminService`/`AdminService` from its `implements` clause and the manual `addRequiredQuery()` diamond-resolver; it supplies CRUD plumbing + `getProjectIdPath()` and relies on the default `getProjectId`, landing in task 4.1)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 7.1_

  - [x] 1.2 Add the shared `assertProjectAccess(ID)` decision logic
    - Add `default void assertProjectAccess(ID entityId)` to `ProjectScopedService` containing ALL decision logic and reusing the existing private helpers: no/blank/non-numeric principal → throw `denied(entityId)`; ADMIN authority (exact `ROLE_ADMIN`/`ADMIN` via `ForemenPermissionEvaluator.ADMIN_ROLE_CODE`) → return WITHOUT calling `getProjectId`; non-ADMIN with empty/`null` allowed set (from `allowedProjectIds(userId)`) → throw; otherwise resolve `getProjectId(entityId)` and return iff non-null and contained in the allowed set, else throw
    - Add a private static helper `denied(Object entityId)` returning `new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", entityId)`, so an out-of-scope entity is indistinguishable from a missing one and no new message code is introduced
    - Reuse the existing `isAdmin`, `parseUserId`, and `allowedProjectIds` so the action decision and the read-filter decision read from the same source (`ProjectAccessCache`) and honor the same invalidation
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 6.1_

  - [x] 1.3 Add the `default` by-id CRUD overrides that delegate via `AdminService.super`
    - In `ProjectScopedService`, add `default` overrides (each `@Override`) of every by-id CRUD method inherited from `AdminService`/`ReadOnlyAdminService` — `findById`, `update`, `updateAll`, `updateSingleField`, `deleteById`, `deleteAll`, `softDelete`, `setPropertiesToNull` — each calling `assertProjectAccess(id)` (per targeted id for the batch variants `updateAll`/`deleteAll`/`softDelete`, aborting on the first deny) and then delegating to the inherited body via `AdminService.super.<method>(...)`
    - Match each inherited signature 1:1 (verified in source: e.g. `updateSingleField(ID, V, BiConsumer<D, V>)`, `softDelete(String, Set<ID>)`) so each is a genuine override, not an overload; deliberately do NOT override `create` (it resolves no existing id)
    - _Requirements: 3.1, 3.2, 3.3, 4.1, 4.2, 4.3, 4.5, 5.1, 5.2, 5.3, 5.5, 7.3, 7.4, 7.5_

- [x] 2. Prove the decision logic and lock the override set
  - [x] 2.1 Write the property test for `assertProjectAccess`
    - **Property 1: assertProjectAccess decision matches the caller's access state and the entity's owning project**
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9**
    - jqwik `*PropertyTest.java` (`@Property(tries = 100)`, tagged): drive `assertProjectAccess(id)` via a minimal stub implementor of `ProjectScopedService` (supplying no-op CRUD plumbing so it can be instantiated, since the interface now extends `AdminService`) over generated `SecurityContext` states — (i) no principal, (ii) non-numeric principal, (iii) ADMIN authority, (iv) non-ADMIN empty allowed set, (v) non-ADMIN non-empty allowed set — and a stubbed/spy `getProjectId` returning generated in-set / out-of-set / `null` values; assert: returns normally iff ADMIN, or non-ADMIN-non-empty with an in-set non-null owning id; throws `ForemenApiException` status 404 code `error.entity.not.found` in every other case; and `getProjectId` is NEVER invoked on the ADMIN path (spy)
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9_

  - [x] 2.2 Write example/unit tests for each decision branch
    - JUnit 5 with the same stub implementor and stubbed `allowedProjectIds`/`getProjectId`: assert one explicit case per branch — no-auth deny, non-numeric-principal deny, ADMIN allow (and `getProjectId` not called), empty-set deny, in-set allow, out-of-set deny, null-owning-id deny — each asserting the thrown exception's status (404) and code (`error.entity.not.found`)
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [x] 2.3 Write the mutating-method override guard test
    - JUnit 5 reflection test `ProjectScopedServiceGuardTest`: enumerate every by-id `Mutating_Crud_Method` declared on `AdminService`/`ReadOnlyAdminService` (`findById`, `update`, `updateAll`, `updateSingleField`, `deleteById`, `deleteAll`, `softDelete`, `setPropertiesToNull`) and assert `ProjectScopedService` declares a matching `default` override for each; assert `create` is intentionally NOT overridden. This fails fast if the CRUD framework later grows a mutating method left unprotected
    - _Requirements: 7.5, 8.5_

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Collapse the test fixture to the single-contract shape
  - [x] 4.1 Simplify `ScopedFixtureService` to `implements ProjectScopedService` only, relying on the default `getProjectId`
    - In `com.foremen.scoping.ScopedFixtureService`, change the `implements` clause to `ProjectScopedService<ScopedFixtureServiceModel, ScopedFixtureServiceExtendedModel, ScopedFixtureEntity, Long>` only — remove the separate `ReadOnlyAdminService`/`AdminService` and the manual `addRequiredQuery()` diamond-resolver; keep the CRUD plumbing and `allowedProjectIds` → `ProjectAccessCache.get(userId)`
    - Do NOT override `getProjectId` — rely on the default derived from `getProjectIdPath()`; keep the test control that switches `getProjectIdPath()` between `"projectId"` (single-segment) and `"project.id"` (dotted/join) so the default resolver is exercised for both path shapes
    - Do NOT override any CRUD method — the by-id checks are inherited from `ProjectScopedService`; reuse `ScopedFixtureEntity` unchanged; keep the fixture in test sources only. Optionally add a tiny variant (or flag) that overrides `getProjectId` once to prove the override path is honored
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 3.1, 4.1, 5.1, 7.3, 7.4_

- [x] 5. Prove enforcement end to end against the fixture
  - [x] 5.1 Write read/update/delete enforcement integration tests
    - `@SpringBootTest` + Testcontainers postgres + Spring Security Test, `@ActiveProfiles("integration-test")`: seed fixture rows across multiple project ids and `project_members` for a non-ADMIN user; for `findById`, `update`, and `deleteById` assert — ADMIN caller allowed regardless of ownership (3.4, 4.4, 5.4); non-ADMIN acting on an in-scope entity allowed (3.2, 4.3, 5.3); non-ADMIN acting on an out-of-scope entity denied with 404 `error.entity.not.found` (3.3, 4.2, 5.2); non-ADMIN with no memberships denied (2.5); unauthenticated caller denied (2.3)
    - _Requirements: 2.3, 2.5, 3.2, 3.3, 3.4, 4.2, 4.3, 4.4, 5.2, 5.3, 5.4, 8.2_

  - [x] 5.2 Write the no-partial-mutation and multi-id integration tests
    - Assert a denied `update` leaves the target row's fields unchanged and a denied `deleteById` leaves the row present (no write reaches the DAO because `assertProjectAccess` throws first); assert the multi-id variant aborts the WHOLE operation when any one targeted id is out of scope (no row in the batch is mutated)
    - _Requirements: 4.2, 4.5, 5.2, 5.5, 8.3_

  - [x] 5.3 Write the read-filter non-regression integration test
    - Re-assert the FOR-03-04 list filter is unchanged by this spec: `find` / `findExtended` / `getCount` through the fixture service return unfiltered for ADMIN, only in-membership rows for a non-ADMIN with memberships, and no rows for a non-ADMIN with none
    - _Requirements: 7.1, 7.2, 8.4_

  - [x] 5.4 Write the default `getProjectId` resolution integration tests
    - Testcontainers postgres, through the fixture with NO custom `getProjectId`: assert the default resolver returns the correct owning project id for a single-segment path (`getProjectIdPath() == "projectId"`) and for a dotted/join path (`"project.id"`), and returns `null` for a non-existent id; add one case with a fixture variant that overrides `getProjectId` to confirm the override is honored
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 8.6_

  - [x] 5.5 Write the edge-case test
    - ADMIN acting on a genuinely non-existent id still yields 404 via the framework `findById` (ownership bypassed, the default `getProjectId` is not consulted on the ADMIN path, so no query is issued)
    - _Requirements: 2.2_

- [x] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Author test-cases.md
  - Create `test-cases.md` in `.kiro/specs/FOR-03-auth-system/FOR-03-04a-project-actions-validation/` following the `.kiro/steering/test-cases.md` standard. This is an API-only spec: scenarios are API tests against the Dockerized backend (`docker compose up` in the repo root brings up postgres:5432, liquibase, backend:8080 profile `docker`, frontend:3000; auth under `/api/auth`; first ADMIN via `FOREMEN_ADMIN_CREATE=true` / `FOREMEN_ADMIN_EMAIL` / `FOREMEN_ADMIN_PASSWORD`; base URL `http://localhost:8080`). Because no real project-scoped entity/endpoint exists yet (FOR-06) and no production controller is annotated (FOR-03-08), the runnable API surface for level-3 action validation is limited; state this explicitly and drive scenarios through the closest available surface (the FOR-03-04 `/api/project-members` endpoints and/or a demonstration project-scoped endpoint where present), otherwise mark those cases as pending FOR-06/FOR-03-08 with the exact expected request/response. Group by feature (owning-project resolution contract; action decision ADMIN-bypass vs non-ADMIN-in-scope vs out-of-scope vs empty-access vs unauthenticated; read-by-id enforcement; update-by-id enforcement + no partial mutation; delete-by-id enforcement + no partial mutation; multi-id abort-on-first-deny; read-filter non-regression). Write step-by-step request/expectation scenarios, ensure repeatability via a unique run-id generator (e.g. `test+{run-id}@example.com`) and/or teardown of created memberships/users, include a dedicated Регрессия (regression) group, and provide an MD-report-with-tables template. Note `test-cases.md` is written in Russian per the standard; run artifacts are MD reports with tables, not screenshots.
  - _Requirements: 2, 3, 4, 5, 6, 7_

- [x] 8. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements for traceability; the property test task additionally references the design property number it implements.
- The single design correctness property is covered: Property 1 (task 2.1). The complete-override guarantee is locked by the guard test (task 2.3).
- The only production source modified is `com.foremen.service.ProjectScopedService`: it now `extends AdminService<S, SE, D, ID>`, adds a `default getProjectId` (Criteria resolver derived from `getProjectIdPath()`, overridable) + `assertProjectAccess` + the `denied` helper + `default` overrides of the eight by-id CRUD methods (delegating via `AdminService.super`), reusing the existing `isAdmin`/`parseUserId`/`allowedProjectIds`/`resolvePath`/`matchNothing`. `create` is intentionally not overridden.
- Variant A structure: a project-scoped service implements exactly one CRUD contract (`ProjectScopedService`), never a plain `AdminService` and never both; the interface stays a pure `default`/abstract interface; concrete services inherit the by-id overrides and the default `getProjectId`, and write only plumbing + `getProjectIdPath()` (the single mandatory per-entity override).
- No new message code: the denial reuses `error.entity.not.found`, already present and localized (PL/RU) in both bundles. Requirement 6.2 only applies if a future refinement introduces a new code.
- Scope guards: `projects` table and real project entities are OUT (FOR-06); production controller `@RequiresPermission` migration is OUT (FOR-03-08); the FOR-03-04 read filter (`addRequiredQuery()` / `getProjectIdPath()`) is unchanged. Enforcement is proven against the test-only `ScopedFixtureService`, not a real project entity.
- Checkpoints (tasks 3, 6, 8) ensure incremental validation.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["1.3"] },
    { "id": 3, "tasks": ["2.1", "2.2", "2.3"] },
    { "id": 4, "tasks": ["4.1"] },
    { "id": 5, "tasks": ["5.1", "5.2", "5.3", "5.4", "5.5"] },
    { "id": 6, "tasks": ["7"] }
  ]
}
```
