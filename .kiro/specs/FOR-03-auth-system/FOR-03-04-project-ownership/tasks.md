# Implementation Plan: FOR-03-04 Project Ownership

## Overview

This plan implements the project-scoping infrastructure for the Foremen backend (module `foremen-backend`, package root `com.foremen`). It answers, for the authenticated caller, "which projects may this user see?" and applies that answer automatically as a SQL-level filter to every read that flows through the CRUD framework's `addRequiredQuery()` extension point, with an ADMIN bypass that sees everything.

It proceeds bottom-up, mirroring the sibling FOR-03-03 plan: the `project_members` schema and its entity/DAO first, then the config properties and the dedicated Caffeine project-access cache, then the pure `ProjectScopedService` decision-logic mixin with its property tests, then the membership service and REST controller, then the ABAC seed, then the `UserService` cache-invalidation touch point and localization, and finally a test-only `@Entity` fixture that proves end-to-end filtering plus the controller integration tests. Each task builds on the previous one and ends by wiring the pieces into the running application.

Property tests use jqwik (`*PropertyTest.java`, `@Property(tries = 100)`) and are tagged `// Feature: FOR-03-04-project-ownership, Property N: ...`. Example/unit tests use JUnit 5. Integration tests use `@SpringBootTest` + Testcontainers (postgresql) + Spring Security Test with `@ActiveProfiles("integration-test")`.

This spec DELIVERS the mechanism plus tests, the `ProjectMemberController` (already protected by `@RequiresPermission` on `PROJECT_MEMBERS`), and its ABAC seed. It MUST NOT create the `projects` table or any real project entity/service (FOR-06), and MUST NOT migrate the OTHER existing controllers off `permitAll()` (FOR-03-08). The project-scoping filter is proven end to end against a test-only `@Entity` fixture.

Test conventions:
- Property test files: `*PropertyTest.java`, minimum 100 iterations, tagged with the design property number.
- Optional test sub-tasks are postfixed with `*` and may be skipped for a faster MVP; core implementation tasks are never optional.
- Each task cites the requirement numbers it satisfies and, where applicable, the design property number.

## Tasks

- [x] 1. Create the `project_members` schema, entity, and DAO
  - [x] 1.1 Add the `014-create-project-members.xml` changeset and register it
    - Create `foremen-backend/database_files/changesets/014-create-project-members.xml` guarded by `preConditions onFail="MARK_RAN"` (`<not><tableExists.../></not>`): a `project_members` table with `id` (`BIGSERIAL` PK), `user_id` (`BIGINT` NOT NULL, FK → `users(id)`), `project_id` (plain `BIGINT` NOT NULL, no FK), `project_role_id` (`BIGINT` NOT NULL, FK → `roles(id)`), and the `BaseEntity` audit columns (`created_date`, `created_by`, `updated_date`, `updated_by`); add a unique constraint `uk_project_members_user_project` on `(user_id, project_id)`
    - Register `014-create-project-members.xml` in `changelog.xml` immediately after `013-create-invite-tokens.xml`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [x] 1.2 Create `ProjectMemberEntity`
    - Add `com.foremen.dao.model.ProjectMemberEntity` extending `BaseEntity`, `@Table(name = "project_members", uniqueConstraints = @UniqueConstraint(name = "uk_project_members_user_project", columnNames = {"user_id", "project_id"}))`: `@ManyToOne(fetch = LAZY) UserEntity user` (join column `user_id`), plain `Long projectId` (`@Column(name = "project_id")`, no association), `@ManyToOne(fetch = LAZY) RoleEntity projectRole` (join column `project_role_id`)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 1.3 Create `ProjectMemberDao`
    - Add `com.foremen.dao.ProjectMemberDao` extending `AdminDao<ProjectMemberEntity, Long>` with `@Query("SELECT DISTINCT pm.projectId FROM ProjectMemberEntity pm WHERE pm.user.id = :userId") Set<Long> findDistinctProjectIdsByUserId(Long userId)`, `Optional<ProjectMemberEntity> findByUserIdAndProjectId(Long, Long)`, `boolean existsByUserIdAndProjectId(Long, Long)`, and `List<ProjectMemberEntity> findByProjectId(Long)`
    - _Requirements: 2.6, 3.5, 3.6_

  - [x] 1.4 Write integration test for the migration, unique constraint, and distinct-ids query
    - `@SpringBootTest` + Testcontainers postgres, `@ActiveProfiles("integration-test")`: assert `014` creates `project_members`; insert two rows for the same `(user_id, project_id)` and assert the unique constraint rejects the second; seed several rows and assert `findDistinctProjectIdsByUserId` returns the distinct project-id set for a user
    - _Requirements: 1.1, 1.5, 2.6_

- [x] 2. Add the project-access configuration properties
  - [x] 2.1 Create `ProjectAccessProperties`
    - Add `com.foremen.config.security.ProjectAccessProperties` as a `@Validated @ConfigurationProperties(prefix = "foremen.project-access")` record with `@Positive Integer cacheTtlMinutes`, defaulting `null → 1440` (24 hours) in the compact constructor, mirroring `PermissionProperties`; fail fast on non-positive values
    - _Requirements: 5.1, 5.2, 5.3_

  - [x] 2.2 Write smoke tests for the properties default and fail-fast
    - Assert the code-level default is 1440 when the property is unset (5.2); assert a non-positive `foremen.project-access.cache-ttl-minutes` aborts context startup with a validation error (5.3)
    - _Requirements: 5.2, 5.3_

- [x] 3. Add the dedicated project-access cache
  - [x] 3.1 Create `ProjectAccessCache`
    - Add `com.foremen.service.ProjectAccessCache` as a `@Component` building its own `Caffeine<Long, Set<Long>>` with `expireAfterAccess(Duration.ofMinutes(cacheTtlMinutes))` (idle safety net) and `maximumSize(1_000)`, mirroring `PermissionCache`; expose self-loading `get(Long userId)` via `cache.get(userId, projectMemberDao::findDistinctProjectIdsByUserId)` and `invalidate(Long userId)`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 8.5_

  - [x] 3.2 Write property test for cache load-then-hit
    - **Property 3: Cache load-then-hit serves without reloading**
    - **Validates: Requirements 4.1, 4.2, 4.3**
    - Use a counting/spy `ProjectMemberDao`; for any user id and N repeated `get(userId)` calls with no intervening invalidation, assert exactly one DAO load and identical returned sets (including an empty set for a user with no memberships)
    - _Requirements: 4.1, 4.2, 4.3_

  - [x] 3.3 Write property test for the invalidation round-trip
    - **Property 4: Invalidation forces a reload (invalidation round-trip)**
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5**
    - Using a counting `ProjectMemberDao`, cache an entry via `get(userId)`, call `invalidate(userId)`, and assert the next `get(userId)` reloads from the DAO and reflects the current rows
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 4. Implement the `ProjectScopedService` decision-logic mixin
  - [x] 4.1 Create `ProjectScopedService`
    - Add `com.foremen.service.ProjectScopedService<DaoModel>` with `String getProjectIdPath()` (no default — the single per-entity override point), `Set<Long> allowedProjectIds(Long userId)` hook, and the single `default Specification<DaoModel> addRequiredQuery()` containing ALL decision logic: no authenticated caller → match-nothing; exact-authority ADMIN check (`ROLE_ADMIN` or bare `ADMIN` using `ForemenPermissionEvaluator.ADMIN_ROLE_CODE`) → `null`; non-numeric/absent principal name → match-nothing; empty allowed set → match-nothing; non-empty → `resolvePath(root, getProjectIdPath()).in(allowed)`
    - Add the private helpers: `parseUserId` (`Long.parseLong`, null on blank/non-numeric), `resolvePath` (single segment → `root.get(seg)`, dotted → `from.join(seg)` for each non-final segment then `.get(finalSeg)`), and `matchNothing` (`(root, query, cb) -> cb.disjunction()`)
    - _Requirements: 6.1, 6.2, 6.3, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10, 7.1, 7.2, 7.3_

  - [x] 4.2 Write property test for the decision logic
    - **Property 1: addRequiredQuery decision matches the caller's access state**
    - **Validates: Requirements 6.1, 6.2, 6.3, 6.5, 6.6, 6.7**
    - Drive `addRequiredQuery()` with generated `SecurityContext` states — (i) no principal, (ii) ADMIN authority, (iii) non-ADMIN with empty allowed set, (iv) non-ADMIN with non-empty allowed set — over generated project-id paths; assert `null` iff ADMIN, match-nothing iff unauthenticated or non-ADMIN-empty, and an `IN`-over-path specification iff non-ADMIN with a non-empty set; include a non-numeric principal-name edge case (treated as no-auth)
    - _Requirements: 6.1, 6.2, 6.3, 6.5, 6.6, 6.7_

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement `ProjectMemberService`
  - [x] 6.1 Create `ProjectMemberService`
    - Add `com.foremen.service.ProjectMemberService` (`@Service`, `@Transactional`) injecting `ProjectMemberDao`, `UserDao`, `RoleDao`, `ProjectAccessCache`: `assign(userId, projectId, projectRoleId)` pre-checks duplicate → `ForemenApiException(CONFLICT, "error.project.member.duplicate")`, resolves the user (existing `error.entity.not.found`) and the role → `ForemenApiException(NOT_FOUND, "error.project.role.not.found")`, saves the row, then `projectAccessCache.invalidate(userId)`; `remove(userId, projectId)` loads the row → `ForemenApiException(NOT_FOUND, "error.project.member.not.found")`, deletes it, then invalidates; `listMembers(projectId)` and `listProjects(userId)` are `@Transactional(readOnly = true)`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 8.1, 8.2_

  - [x] 6.2 Write example/unit tests for the service branches
    - JUnit 5 with stubbed DAOs: assign persists and invalidates on success (3.1, 8.1); duplicate `(userId, projectId)` raises the duplicate exception without a second insert (3.2); unknown `projectRoleId` raises role-not-found (3.3); remove deletes and invalidates (3.4, 8.2); remove of a missing membership raises member-not-found (3.4); listMembers returns rows for the project id (3.5); listProjects returns the distinct project-id set (3.6)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 8.1, 8.2_

- [x] 7. Implement `ProjectMemberController` and its DTOs
  - [x] 7.1 Create the request/response DTOs
    - Add `com.foremen.controller.model.AssignProjectMemberRequest` as a `record(userId, projectId, projectRoleId)` with all three `@NotNull`, and `com.foremen.controller.model.ProjectMemberResponse` as a `record(id, userId, projectId, projectRoleId, projectRoleCode)`
    - _Requirements: 10.13, 10.14_

  - [x] 7.2 Create `ProjectMemberController`
    - Add `com.foremen.controller.ProjectMemberController` mapped to `/api/project-members`, carrying `projectId` in the body/query (never in the path): `POST` assign `@RequiresPermission(resource = "PROJECT_MEMBERS", operation = "CREATE")` returning 201 with `ProjectMemberResponse`; `DELETE` remove `@RequiresPermission(... operation = "DELETE")` reading `userId`/`projectId` query params, returning 204 no-body; `GET` listMembers `@RequiresPermission(... operation = "READ")` on `projectId`; `GET /projects` listProjects `@RequiresPermission(... operation = "READ")` on `userId`; map entities to `ProjectMemberResponse` including `projectRoleCode` from the associated role
    - _Requirements: 10.1, 10.2, 10.6, 10.9, 10.11, 10.12_

- [x] 8. Seed the `PROJECT_MEMBERS` ABAC resource
  - [x] 8.1 Add the `015-seed-project-members-resource.xml` changeset and register it
    - Create `foremen-backend/database_files/changesets/015-seed-project-members-resource.xml` following the `009-seed-audit-resource`/`007-seed-permissions` convention: insert the `PROJECT_MEMBERS` resource row (localized `name_ru`/`name_pl`/`description_ru`/`description_pl`) guarded by `MARK_RAN` + `sqlCheck expectedResult="0"`; seed `role_resources` + `role_resource_operations` per system role — ADMIN and MANAGER full `CREATE, READ, UPDATE, DELETE`; FOREMAN and FINANCIER `READ` only; WORKER and CLIENT no `role_resources` row at all (deny-by-default); every INSERT guarded by its own `sqlCheck expectedResult="0"`; operation codes drawn from the existing catalog (no new codes)
    - Register `015-seed-project-members-resource.xml` in `changelog.xml` after `014-create-project-members.xml`
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

- [x] 9. Wire cache invalidation on user role change
  - [x] 9.1 Evict the project-access cache entry on a role-changing user update
    - Inject `ProjectAccessCache` into `com.foremen.service.UserService` and, on the role-changing update path (overriding `update`/the post-update hook), call `projectAccessCache.invalidate(userId)` for the updated user after the update completes; keep the scope limited to this touch point — no FOR-03-08 controller migration
    - _Requirements: 8.4_

- [x] 10. Localize the new error codes
  - [x] 10.1 Add the new message codes to both bundles
    - Add non-blank entries for `error.project.member.duplicate`, `error.project.role.not.found`, and `error.project.member.not.found` to `messages.properties` (Polish base) AND `messages_ru.properties` (Russian)
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [x] 10.2 Write property test for PL/RU localization presence
    - **Property 5: New error codes are localized in PL and RU**
    - **Validates: Requirements 9.1, 9.2, 9.3**
    - Read both bundles from the classpath; for each of the three new codes assert a non-blank entry in `messages.properties` (PL) and `messages_ru.properties` (RU)
    - _Requirements: 9.1, 9.2, 9.3_

- [x] 11. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Prove end-to-end filtering with a test-only fixture
  - [x] 12.1 Add the test-only `@Entity` fixture and its service
    - Under `src/test`, add `com.foremen.scoping.ScopedFixtureEntity` (`@Entity` with a plain `projectId`, plus a nested-path variant reaching a project id through an association to exercise dotted paths) and `ScopedFixtureService` implementing both `ReadOnlyAdminService` and `ProjectScopedService`, overriding `getProjectIdPath()` and wiring `allowedProjectIds(userId)` to `ProjectAccessCache.get(userId)`; provide the fixture's test-profile schema (test-only Liquibase changeset or JPA DDL scoped to the fixture). Keep the fixture out of production sources
    - _Requirements: 6.4, 6.10_

  - [x] 12.2 Write property test for nested path resolution
    - **Property 2: Nested path resolution filters by the resolved project id**
    - **Validates: Requirements 6.2, 7.1, 7.2, 7.3**
    - Testcontainers postgres: for generated fixture rows reachable through a single-segment path (`projectId`) or a dotted path (`project.id`, `room.project.id`) and any non-empty allowed set, assert the filtered query returns exactly the rows whose resolved project id is in the allowed set — no out-of-set row survives and every in-set row is retained
    - _Requirements: 6.2, 7.1, 7.2, 7.3_

  - [x] 12.3 Write end-to-end filtering integration tests through the fixture service
    - `@SpringBootTest` + Testcontainers postgres, `@ActiveProfiles("integration-test")`: ADMIN caller reads unfiltered (6.1, 6.4); non-ADMIN caller with memberships sees only rows in the allowed set (6.2, 6.4, 6.10); non-ADMIN caller with no memberships sees no rows (6.3); no authenticated caller sees no rows (6.6); confirm the filter is applied to `find`, `findExtended`, and `getCount` via `buildFinalSpecification` (6.4)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.6, 6.10_

  - [x] 12.4 Write `ProjectMemberController` integration tests
    - `@SpringBootTest` + Testcontainers postgres + Spring Security Test: assign returns 201 with `ProjectMemberResponse` (10.6); duplicate assign returns 409 `error.project.member.duplicate` (10.7); unknown `projectRoleId` returns 404 `error.project.role.not.found` (10.8); remove returns 204 (10.9); remove of a missing membership returns 404 `error.project.member.not.found` (10.10); listMembers returns rows for the project id (10.11); listProjects returns the distinct project-id set (10.12); a caller lacking the required `(PROJECT_MEMBERS, op)` permission gets 403 `error.access.denied` (10.3); an unauthenticated caller gets 401 `error.auth.unauthorized` (10.4); an ADMIN caller reaches every endpoint through the bypass (10.5)
    - _Requirements: 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10, 10.11, 10.12_

  - [x] 12.5 Write the `015` ABAC seed integration test
    - Testcontainers postgres: after migrations, assert the `PROJECT_MEMBERS` resource exists and the per-system-role grants are exactly ADMIN/MANAGER full CRUD, FOREMAN/FINANCIER READ only, and WORKER/CLIENT no `role_resources` row (11.3, 11.4, 11.7); assert re-running the changeset does not duplicate rows (11.5)
    - _Requirements: 11.1, 11.3, 11.4, 11.5, 11.7_

- [x] 13. Author test-cases.md
  - Create `test-cases.md` in `.kiro/specs/FOR-03-auth-system/FOR-03-04-project-ownership/` following the `.kiro/steering/test-cases.md` standard. This is an API-only spec: scenarios are API tests against the Dockerized backend (`docker compose up` in the repo root brings up postgres:5432, liquibase, backend:8080 profile `docker`, frontend:3000; auth under `/api/auth`; first ADMIN via `FOREMEN_ADMIN_CREATE=true` / `FOREMEN_ADMIN_EMAIL` / `FOREMEN_ADMIN_PASSWORD`; base URL `http://localhost:8080`). Group by feature (project_members schema/uniqueness, membership assign/remove/list, PROJECT_MEMBERS RBAC enforcement 403/401 + ADMIN bypass, project-scoped read filtering ADMIN-vs-member-vs-none, cache invalidation on membership/role change, PL/RU localization). Write step-by-step request/expectation scenarios, ensure repeatability via a unique run-id generator (e.g. `test+{run-id}@example.com`) and/or teardown of created memberships/users, include a dedicated Регрессия (regression) group, and provide an MD-report-with-tables template. Note `test-cases.md` is written in Russian per the standard; run artifacts are MD reports with tables, not screenshots.
  - _Requirements: 1, 3, 6, 8, 9, 10, 11_

- [x] 14. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements for traceability; property test tasks additionally reference the design property number they implement.
- All 5 design correctness properties are covered: Property 1 (task 4.2), Property 2 (12.2), Property 3 (3.2), Property 4 (3.3), Property 5 (10.2).
- Cache invalidation is wired at every relevant touch point keyed by user id: `ProjectMemberService.assign`/`remove` (task 6.1) and the `UserService` role-change path (task 9.1); scope stays limited to these touch points with no FOR-03-08 controller migration.
- The three NEW codes (`error.project.member.duplicate`, `error.project.role.not.found`, `error.project.member.not.found`) are added to both bundles (task 10.1); `error.access.denied`, `error.auth.unauthorized`, and `error.entity.not.found` already exist and are reused unchanged.
- Property tests are jqwik `*PropertyTest.java` at ≥100 iterations, tagged `// Feature: FOR-03-04-project-ownership, Property N: ...`.
- Scope guards: the `projects` table and any FK from `project_members.project_id` are OUT (FOR-06); `project_id` is a plain `BIGINT`. Only `ProjectMemberController` is annotated with `@RequiresPermission`; the other production controllers are NOT migrated (FOR-03-08). End-to-end filtering is proven against a test-only `@Entity` fixture, not a real project entity.
- Checkpoints (tasks 5, 11, 14) ensure incremental validation.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1"] },
    { "id": 1, "tasks": ["1.3", "2.2"] },
    { "id": 2, "tasks": ["1.4", "3.1"] },
    { "id": 3, "tasks": ["3.2", "3.3", "4.1"] },
    { "id": 4, "tasks": ["4.2", "6.1", "7.1", "8.1", "10.1"] },
    { "id": 5, "tasks": ["6.2", "7.2", "9.1", "10.2", "12.1"] },
    { "id": 6, "tasks": ["12.2", "12.3", "12.4", "12.5", "13"] }
  ]
}
```
