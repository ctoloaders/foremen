# Implementation Plan: FOR-03-03 Permission Evaluator

## Overview

This plan implements the runtime permission-enforcement mechanism for the Foremen backend (module `foremen-backend`, package root `com.foremen`). It proceeds bottom-up: the annotation and immutable permission-set value first, then the dedicated Caffeine cache and its config properties, then the pure evaluator logic with its property tests, then the MVC interceptor and its registration, then the cache-invalidation touch point in `RoleService`, and finally a demonstrative annotated endpoint with end-to-end integration tests. Each task builds on the previous one and ends by wiring the pieces into the running application.

Property tests use jqwik (`*PropertyTest.java`, `@Property(tries = 100)`) and are tagged `// Feature: FOR-03-03-permission-evaluator, Property N: ...`. Example tests use JUnit 5. Integration tests use `@SpringBootTest` + Testcontainers (postgresql) + Spring Security Test with `@ActiveProfiles("integration-test")`.

This spec DELIVERS the mechanism plus tests and a single demonstrative annotated endpoint; it MUST NOT annotate the existing production controllers (that is FOR-03-08) and MUST NOT implement project-ownership filtering (FOR-03-04).

Test conventions:
- Property test files: `*PropertyTest.java`, minimum 100 iterations, tagged with the design property number.
- Optional test sub-tasks are postfixed with `*` and may be skipped for a faster MVP; core implementation tasks are never optional.
- Each task cites the requirement numbers it satisfies and, where applicable, the design property numbers.

## Tasks

- [x] 1. Define the annotation and permission-set value type
  - [x] 1.1 Create the `@RequiresPermission` annotation
    - Add `com.foremen.config.security.RequiresPermission` with `@Retention(RUNTIME)`, `@Target(METHOD)` (targets methods only), and String attributes `resource()` and `operation()`
    - _Requirements: 4.1, 4.2_

  - [x] 1.2 Write example tests for the annotation structure
    - Reflectively assert `resource()`/`operation()` return String, `@Retention` is RUNTIME, and `@Target` is METHOD only
    - _Requirements: 4.1, 4.2_

  - [x] 1.3 Create the `PermissionSet` value type
    - Add `com.foremen.service.permission.PermissionSet` as an immutable `record PermissionSet(Set<String> grants)` with `static String key(resource, operation)` (`"R:O"`), `boolean allows(resource, operation)` (exact membership), and `static PermissionSet empty()`
    - _Requirements: 1.1, 1.4, 2.1, 2.2_

  - [x] 1.4 Write property tests for `PermissionSet`
    - **Property 1: Decision equals matrix membership (deny by default)** — **Validates: Requirements 1.1, 2.1, 2.2**
    - **Property 6: Grants match resource and operation codes exactly** — **Validates: Requirements 1.4**
    - Generate random grant sets and query pairs (including absent-resource, partial-operation, and case-differing variants); assert `allows` equals exact membership
    - _Requirements: 1.1, 1.4, 2.1, 2.2_

- [x] 2. Add the dedicated permission cache and its configuration
  - [x] 2.1 Create `PermissionProperties`
    - Add `com.foremen.config.security.PermissionProperties` as a `@Validated @ConfigurationProperties(prefix = "foremen.permission")` record with `@Positive Integer cacheTtlMinutes`, defaulting to 1440 (24 hours) in the compact constructor; fail fast on non-positive values
    - _Requirements: 8.3, 8.4_

  - [x] 2.2 Create `PermissionCache`
    - Add `com.foremen.service.permission.PermissionCache` as a `@Component` building its own `Caffeine<String, PermissionSet>` with `expireAfterAccess(cacheTtlMinutes)` (idle safety net, independent of the global `foremen.cache.spec`) and a large `maximumSize` sized to hold every role; expose `get(roleCode, loader)` and `invalidate(roleCode)`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 9.1_

  - [x] 2.3 Write edge-case and smoke tests for the cache
    - Using a Caffeine test ticker, assert entries expire on idle (8.4) and that capacity holds every role (8.3); assert the cache is a dedicated Caffeine instance not derived from the global spec, and that the default idle TTL is 1440 minutes (8.4)
    - _Requirements: 8.3, 8.4_

- [x] 3. Implement the permission evaluator
  - [x] 3.1 Create `ForemenPermissionEvaluator`
    - Add `com.foremen.service.permission.ForemenPermissionEvaluator` (`@Service`) with `boolean isAllowed(String roleCode, String resource, String operation)`: exact-match ADMIN bypass first (constant `ADMIN_ROLE_CODE = "ADMIN"`), otherwise `cache.get(roleCode, this::loadPermissionSet).allows(...)`
    - Add `@Transactional(readOnly = true) PermissionSet loadPermissionSet(String roleCode)`: resolve via `RoleDao.findByCode`; unknown role → `PermissionSet.empty()`; otherwise flatten each `RoleResourceEntity`'s resource code against each operation code into grant keys
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 8.1, 8.2_

  - [x] 3.2 Write property tests for evaluation logic
    - **Property 2: Permission set derivation from the role graph** — **Validates: Requirements 1.2**
    - **Property 3: An empty permission set denies everything** — **Validates: Requirements 2.3**
    - **Property 4: ADMIN is always allowed** — **Validates: Requirements 3.1, 3.2**
    - **Property 5: ADMIN bypass requires an exact code match** — **Validates: Requirements 3.3**
    - Use generated `RoleEntity` graphs and a mock/stub `RoleDao`; include unknown-role deny as an edge case (1.3)
    - _Requirements: 1.2, 1.3, 2.3, 3.1, 3.2, 3.3_

  - [x] 3.3 Write property tests for cache interaction
    - **Property 8: A cache hit serves without reloading** — **Validates: Requirements 8.2**
    - **Property 9: Invalidation forces a reload (invalidation round-trip)** — **Validates: Requirements 9.1, 9.2**
    - Use a spy loader / counting `RoleDao`; assert exactly one load for N repeated calls, and a second load after `invalidate`
    - _Requirements: 8.1, 8.2, 9.1, 9.2_

- [x] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement the MVC enforcement interceptor
  - [x] 5.1 Create `PermissionInterceptor`
    - Add `com.foremen.config.security.PermissionInterceptor` implementing `HandlerInterceptor`; in `preHandle`: skip non-`HandlerMethod` handlers; resolve the method-level `@RequiresPermission` via `getMethodAnnotation` (method-only; no type fallback); if none, proceed; read the role code from the `ROLE_<code>` authority in the `SecurityContext`; if no authenticated principal, throw `ForemenApiException(401, "error.auth.unauthorized")`; if `evaluator.isAllowed(...)` is false, throw `ForemenApiException(403, "error.access.denied")`; otherwise proceed
    - _Requirements: 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 6.1, 7.2_

  - [x] 5.2 Write property and example tests for the interceptor
    - **Property 7: Role code is extracted from the ROLE_ authority** — **Validates: Requirements 5.4**
    - Example tests (JUnit 5, `MockHttpServletRequest`/`HandlerMethod`): method-level annotation used (4.3); a handler without the method annotation is treated as unannotated and proceeds without evaluation (4.4); no annotation → proceed without evaluation (5.3); cleared context on annotated handler → 401 `error.auth.unauthorized` (7.2); deny → 403 `error.access.denied` (6.1); cache miss triggers exactly one load (8.1)
    - _Requirements: 4.3, 4.4, 5.3, 5.4, 6.1, 7.2, 8.1_

  - [x] 5.3 Register the interceptor via `WebMvcConfigurer`
    - Add `com.foremen.config.security.PermissionInterceptorConfig` (`@Configuration`, `@EnableConfigurationProperties(PermissionProperties.class)`) implementing `WebMvcConfigurer.addInterceptors` to register `PermissionInterceptor`
    - _Requirements: 5.1, 5.2_

- [x] 6. Wire the cache-invalidation touch points
  - [x] 6.1 Evict the role's cache entry on permission change
    - Inject `PermissionCache` into `com.foremen.service.RoleService` and, at the end of `replacePermissions(roleId, request)` (after `roleResourceDao.saveAll` + flush), call `permissionCache.invalidate(role.getCode())`; `batchReplacePermissions` inherits this because it delegates to `replacePermissions`
    - Do NOT perform any other controller/security migration (deferred to FOR-03-08)
    - _Requirements: 9.1, 9.5_

  - [x] 6.2 Evict on role deletion
    - In the existing overridden `deleteById(id)`, capture `String code = role.getCode()` after loading the role and BEFORE `dao.deleteById(id)`, then call `permissionCache.invalidate(code)` after the delete + flush
    - _Requirements: 9.2, 9.5_

  - [x] 6.3 Evict on role update
    - Override `update`/`updateAll` in `RoleService` (currently the default `AdminService` implementations) to call `permissionCache.invalidate(code)` for each affected role's code after the update; because `RoleUpdateRequest` exposes no `code`, the affected code equals the existing role's code
    - _Requirements: 9.3, 9.5_

  - [x] 6.4 Evict on role creation (defensive)
    - Override the `afterCreate(RoleEntity)` hook (added in FOR-03-02 task 11.1) in `RoleService` to call `permissionCache.invalidate(role.getCode())`; new roles start empty, so this defensively clears any stale entry for a reused code
    - _Requirements: 9.4, 9.5_

  - [x] 6.5 Write invalidation round-trip test for replacePermissions and deleteById
    - **Property 9: Invalidation forces a reload (invalidation round-trip)**
    - Integration or service test with a counting loader: assert the next evaluation reloads from the database after `replacePermissions` AND after `deleteById`
    - _Requirements: 9.1, 9.2, 9.5_

- [x] 7. Localization guard for authorization messages
  - [x] 7.1 Write property test for message localization
    - **Property 10: Authorization message codes are localized in PL and RU** — **Validates: Requirements 10.1, 10.2**
    - Read both bundles from the classpath; for each of `error.access.denied` and `error.auth.unauthorized` assert a non-blank entry in `messages.properties` (PL) and `messages_ru.properties` (RU). (Both codes already exist; this guards against regression — add them if missing.)
    - _Requirements: 10.1, 10.2_

- [x] 8. Demonstrative endpoint and end-to-end enforcement
  - [x] 8.1 Add a test-only annotated controller
    - Under `src/test`, add a demonstrative controller exposing a method annotated with `@RequiresPermission(resource = "PROJECTS", operation = "READ")` used solely to exercise the interceptor; do NOT place it in production `com.foremen.controller`
    - _Requirements: 11.1, 11.4_

  - [x] 8.2 Write integration tests for the demonstrative endpoint
    - `@SpringBootTest` + Testcontainers postgres + Spring Security Test, `@ActiveProfiles("integration-test")`: authorized role holding the permission → 200 and the controller body executes (5.1, 11.2); role lacking the permission → 403 with body code `error.access.denied` and the controller body does not execute (5.2, 6.1, 6.2, 11.3); unauthenticated request to the annotated endpoint → 401 (7.1, 7.2); ADMIN role → 200 regardless of matrix (3.1); `Accept-Language` pl vs ru yields different 403 message text (10.3); role deletion invalidates the cache so a subsequently-denied role gets 403 (9.2)
    - _Requirements: 3.1, 5.1, 5.2, 6.1, 6.2, 7.1, 7.2, 9.2, 10.3, 11.2, 11.3_

  - [x] 8.3 Write a scope-guard reflection test
    - Scan production package `com.foremen.controller` and assert no production controller type or method carries `@RequiresPermission` (enforces the FOR-03-08 deferral)
    - _Requirements: 11.4_

- [x] 9. Author test-cases.md
  - Create `test-cases.md` in `.kiro/specs/FOR-03-auth-system/FOR-03-03-permission-evaluator/` following the `.kiro/steering/test-cases.md` standard. This is an API-only spec: scenarios are API tests against the Dockerized backend (`docker compose up`, base URL `http://localhost:8080`, auth under `/api/auth`, ADMIN via `FOREMEN_ADMIN_CREATE/_EMAIL/_PASSWORD`). Group by feature (permission evaluation / deny-by-default, ADMIN bypass, annotation enforcement before controller, 403 on denied, 401 on unauthenticated, cache load/TTL/invalidation, PL/RU localization). Because enforcement needs an annotated endpoint (test-only) and matrix editing needs `PUT /api/roles/{id}/permissions`, document how each scenario is driven (log in as ADMIN to edit the matrix, then log in as the target role to hit the protected endpoint). Write step-by-step request/expectation scenarios, ensure repeatability via a unique-role/`run-id` generator and/or teardown of created roles and users, include a dedicated regression group, and provide an MD-report-with-tables template. Run artifacts are MD reports with tables, not screenshots.
  - _Requirements: 1.1, 2.1, 2.3, 3.1, 5.1, 6.1, 6.2, 7.1, 7.2, 8.1, 9.1, 10.1, 10.3, 11.2, 11.3_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements for traceability; property test tasks additionally reference the design property number they implement.
- All 10 design correctness properties are covered: Property 1 & 6 (task 1.4), Properties 2–5 (3.2), Properties 8–9 (3.3, and Property 9 additionally via the round-trip test 6.5), Property 7 (5.2), Property 10 (7.1).
- Cache invalidation is wired into every `RoleService` mutation point keyed by role code: `replacePermissions` (6.1), `deleteById` (6.2), `update`/`updateAll` (6.3), and `create`/`afterCreate` (6.4); scope stays limited to `RoleService` with no FOR-03-08 controller migration.
- Property tests are jqwik `*PropertyTest.java` at ≥100 iterations, tagged `// Feature: FOR-03-03-permission-evaluator, Property N: ...`.
- `error.access.denied` and `error.auth.unauthorized` already exist in both PL and RU bundles; task 7.1 guards against regression rather than adding new codes.
- Checkpoints (tasks 4, 10) ensure incremental validation.
- Scope guards: no production controllers are annotated (task 8.3), and no project-ownership filtering is implemented (FOR-03-04).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "2.1"] },
    { "id": 1, "tasks": ["1.2", "1.4", "2.2"] },
    { "id": 2, "tasks": ["2.3", "3.1"] },
    { "id": 3, "tasks": ["3.2", "3.3", "5.1"] },
    { "id": 4, "tasks": ["5.2", "5.3", "6.1", "6.2", "6.3", "6.4", "7.1"] },
    { "id": 5, "tasks": ["8.1"] },
    { "id": 6, "tasks": ["6.5", "8.2", "8.3", "9"] }
  ]
}
```
