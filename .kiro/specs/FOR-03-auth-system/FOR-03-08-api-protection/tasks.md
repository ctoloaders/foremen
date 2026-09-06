# Implementation Plan: FOR-03-08 API Protection

## Overview

This plan implements a declarative, inheritance-aware controller-guarding scheme AND tightens the Spring Security filter chain for the Foremen backend (module `foremen-backend`, package root `com.foremen`). Today the `PermissionInterceptor` resolves only the method-level `@RequiresPermission` on the final handler, so inherited CRUD `default` methods cannot be guarded without overriding them in every concrete controller; and the `SecurityFilterChain`'s catch-all `anyRequest()` rule is still the historical `permitAll()`. This feature adds two new annotations (`@PermissionResource` on the controller class, `@PermissionOperation` on the interface CRUD methods), a `PermissionResolver` that combines them with `@RequiresPermission` precedence, a startup validator that fails boot on a half-annotated controller, applies the annotations across the admin controllers, seeds the missing `USERS` matrix resource, migrates the `SecurityFilterChain` catch-all from `permitAll()` to `authenticated()`, and codifies an entity-creation steering checklist.

It proceeds bottom-up: first the two annotation types (no dependencies), then the pure `PermissionResolver` (resolve + classifyCompleteness) proven with jqwik property tests before any wiring, then the interceptor delegation edit and the startup validator, then the applied annotations across the interfaces and concrete controllers (ordered so the startup validator never trips on a half-annotated controller), then the `SecurityConfig` filter-chain migration (after the controllers are annotated so tightening authentication does not break the now-guarded endpoints), then the seed changeset and its changelog registration, then integration tests over the real MVC + Spring Security context and the Dockerized seed, and finally the steering documents (`entity-creation-rules.md` and the mandatory `test-cases.md`). Every step wires into the previous ones so no code is orphaned.

The `ForemenPermissionEvaluator` (allow/deny + exact-code ADMIN bypass) and the interceptor's 401/403 error mapping are unchanged in behavior; the only interceptor change is delegating `(resource, operation)` derivation to the `PermissionResolver`. The filter-chain migration is a single-line change (`anyRequest().permitAll()` → `anyRequest().authenticated()`) preserving the three preceding matchers and their order and all other filter-chain settings (`JwtAuthenticationFilter` registration, `JwtAuthenticationEntryPoint`, STATELESS session, disabled CSRF, disabled frame options). `ProjectMemberController` (per-method `@RequiresPermission`), `DisplayPreferencesController`, and `AuthController` (both Unguarded) keep their annotation behavior as-is. No new message codes are introduced (`error.auth.unauthorized` / `error.access.denied` already exist in both bundles).

Test conventions:
- Property tests use **jqwik** (`*PropertyTest.java`, `@Property(tries = 100)`, minimum 100 iterations) tagged `// Feature: FOR-03-08-api-protection, Property N: ...`, one jqwik method per design property.
- Example/unit tests use JUnit 5 (reflection-based structural asserts for annotation shape and applied values).
- Integration tests use `@SpringBootTest` over the full MVC + Spring Security context (and Testcontainers postgres for the seed idempotency test).
- Optional test sub-tasks are postfixed with `*` and may be skipped for a faster MVP; core implementation tasks are never optional.
- Each task cites the requirement numbers it satisfies and, where applicable, the design property number.

Scope guards: does NOT change the `ForemenPermissionEvaluator` decision logic or the ADMIN bypass; does NOT change `ProjectMemberController`'s existing `@RequiresPermission` usages; does NOT migrate `AuthController` / `DisplayPreferencesController` off their Unguarded / self-check behavior; does NOT add new `SecurityFilterChain` matchers or change the existing matcher rules/order beyond the single `anyRequest()` line; does NOT change the `JwtAuthenticationFilter`, `JwtAuthenticationEntryPoint`, or STATELESS/CSRF/frame-options config.

## Tasks

- [x] 1. Add the two permission annotation types
  - [x] 1.1 Create the `@PermissionResource` annotation
    - Create `com.foremen.config.security.PermissionResource`: `@Retention(RUNTIME)`, `@Target(TYPE)`, single member `String value()` naming a resource code
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 1.2 Create the `@PermissionOperation` annotation
    - Create `com.foremen.config.security.PermissionOperation`: `@Retention(RUNTIME)`, `@Target(METHOD)`, single member `String value()` naming an operation code
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 1.3 Write annotation-structure unit tests
    - JUnit 5 reflection asserts: `@PermissionResource` is `RUNTIME`/`TYPE` with a single `String value`; `@PermissionOperation` is `RUNTIME`/`METHOD` with a single `String value`
    - _Requirements: 1.1, 1.2, 2.1, 2.2_

- [x] 2. Implement the `PermissionResolver`
  - [x] 2.1 Implement `PermissionResolver.resolve` and `classifyCompleteness`
    - Create `com.foremen.config.security.PermissionResolver` (`@Component`) with the nested `ResolvedPair(String resource, String operation)` record and the `Completeness` enum (`COMPLETE`, `RESOURCE_WITHOUT_OPERATION`, `OPERATION_WITHOUT_RESOURCE`)
    - `resolve(HandlerMethod)`: if `@RequiresPermission` present on the method → `ResolvedPair` from its `resource`/`operation` (precedence, ignore the others); else read `@PermissionResource` from `HandlerMethod#getBeanType()` and `@PermissionOperation` from the declaring method (via `AnnotatedElementUtils.findMergedAnnotation`, resolving through any Bridge_Method) → `ResolvedPair` when both present; else `null` (Unguarded)
    - `classifyCompleteness(HandlerMethod)`: `COMPLETE` when `@RequiresPermission` present; else `RESOURCE_WITHOUT_OPERATION` when resource present and operation absent; `OPERATION_WITHOUT_RESOURCE` when operation present and resource absent; `COMPLETE` when both present or neither present
    - Add private `resourceOf`/`operationOf` helpers reading the bean type and the declaring method respectively
    - _Requirements: 3.1, 4.1, 4.2, 4.3, 5.1, 6.2, 6.3, 6.4, 6.6_

  - [x] 2.2 Write property test for combined resolution
    - **Property 1: Combined resolution derives resource from the class and operation from the method**
    - **Validates: Requirements 1.3, 2.3, 4.1, 4.3, 10.1**
    - jqwik `PermissionResolverPropertyTest` (`@Property(tries = 100)`, tagged `// Feature: FOR-03-08-api-protection, Property 1: ...`): over generated fixture handlers whose bean type carries `@PermissionResource(R)` and whose declaring method carries `@PermissionOperation(O)` and no `@RequiresPermission`, assert `resolve` returns `ResolvedPair(R, O)`

  - [x] 2.3 Write property test for `@RequiresPermission` precedence
    - **Property 2: `@RequiresPermission` always wins**
    - **Validates: Requirements 3.1, 3.2**
    - jqwik `@Property(tries = 100)` (tagged `// Feature: FOR-03-08-api-protection, Property 2: ...`): for a method carrying `@RequiresPermission(R, O)` across all present/absent combinations of `@PermissionResource`/`@PermissionOperation`, assert `resolve` returns `ResolvedPair(R, O)` from the `@RequiresPermission` and ignores the others

  - [x] 2.4 Write property test for Unguarded fallback
    - **Property 3: No declaration at all resolves to Unguarded**
    - **Validates: Requirements 5.1, 10.4**
    - jqwik `@Property(tries = 100)` (tagged `// Feature: FOR-03-08-api-protection, Property 3: ...`): for a handler whose bean type has no `@PermissionResource`, whose method has no `@PermissionOperation`, and no `@RequiresPermission`, assert `resolve` returns `null`

  - [x] 2.5 Write property test for completeness classification
    - **Property 4: Completeness classification of a handler's annotations**
    - **Validates: Requirements 6.2, 6.3, 6.4, 6.6**
    - jqwik `@Property(tries = 100)` (tagged `// Feature: FOR-03-08-api-protection, Property 4: ...`): over all eight present/absent combinations of `@PermissionResource`/`@PermissionOperation`/`@RequiresPermission`, assert `classifyCompleteness` returns `COMPLETE` iff `@RequiresPermission` present, or both present, or none present; returns `RESOURCE_WITHOUT_OPERATION` / `OPERATION_WITHOUT_RESOURCE` exactly in the two half-annotated cases with `@RequiresPermission` absent

  - [x] 2.6 Write bridge-vs-declared lookup edge-case test
    - JUnit 5: build a `HandlerMethod` over a concrete controller's inherited CRUD `default` method whose `@PermissionOperation` lives on the interface; assert `resolve` finds the operation despite the Bridge_Method
    - _Requirements: 4.2_

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Delegate interceptor resolution and add startup validation
  - [x] 4.1 Edit `PermissionInterceptor` to delegate to `PermissionResolver`
    - Inject `PermissionResolver` into `PermissionInterceptor`; in `preHandle`, keep the non-`HandlerMethod` early-return (proceed), then call `resolver.resolve(handlerMethod)`: `null` → proceed (Unguarded); otherwise keep the existing role-code extraction and 401 (`error.auth.unauthorized`) / 403 (`error.access.denied`) / allow mapping unchanged, submitting the `ResolvedPair` to `ForemenPermissionEvaluator`
    - Do not change the evaluator, the ADMIN bypass, or the error pipeline (`ForemenApiException` → `ForemenControllerAdvice`)
    - _Requirements: 5.1, 5.2, 7.1, 7.2, 7.3, 7.4_

  - [x] 4.2 Implement `PermissionAnnotationValidator` startup component
    - Create `com.foremen.config.security.PermissionAnnotationValidator` (`@Component` implementing `SmartInitializingSingleton`), injecting `RequestMappingHandlerMapping` and `PermissionResolver`; in `afterSingletonsInstantiated()` iterate `getHandlerMethods()`, call `resolver.classifyCompleteness(...)`, collect an actionable message for each `RESOURCE_WITHOUT_OPERATION` / `OPERATION_WITHOUT_RESOURCE` naming the controller class and method, log each, and throw `IllegalStateException` when any problem exists to fail startup
    - Register/verify the validator is picked up by the security config (`PermissionInterceptorConfig` if explicit registration is used)
    - _Requirements: 6.1, 6.2, 6.3, 6.5_

  - [x] 4.3 Write interceptor branch unit tests
    - JUnit 5 with a stubbed evaluator/resolver: non-`HandlerMethod` proceeds; Unguarded (`resolve` returns `null`) proceeds; resolved + no principal → 401 `error.auth.unauthorized`; resolved + deny → 403 `error.access.denied`; resolved + allow → proceed
    - _Requirements: 5.2, 7.1, 7.2, 7.3_

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Apply `@PermissionOperation` to the CRUD interfaces
  - [x] 6.1 Annotate `AdminController` default methods
    - Add `@PermissionOperation` to each `AdminController` `default` method: `create`/`createBulk` → `CREATE`; `update` → `UPDATE`; `find`/`findExtended`/`findById`/`getCount`/`getAudit`/`getI18nProperties`/`getMetadata` → `READ`; `deleteById`/`setPropertiesToNull` → `DELETE`. Leave the non-mapped helper `addCustomQueryCondition` unannotated
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [x] 6.2 Annotate `AdminReadOnlyController` default methods
    - Add `@PermissionOperation("READ")` to `AdminReadOnlyController`'s `find`, `findById`, and `getMetadata` `default` methods
    - _Requirements: 8.5_

  - [x] 6.3 Write interface operation-mapping unit tests
    - JUnit 5 reflection asserts each named `AdminController` / `AdminReadOnlyController` `default` method carries `@PermissionOperation` with the expected CREATE/READ/UPDATE/DELETE value
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 7. Apply `@PermissionResource` to the concrete controllers
  - [x] 7.1 Annotate `UserController` and its declared handler
    - Add `@PermissionResource("USERS")` to `UserController`; leave `registerClient` (`POST /api/users/client`) with its existing `@RequiresPermission(PROJECTS, EDIT)` (precedence keeps it resolving to `PROJECTS`/`EDIT`, and `classifyCompleteness` treats it as complete)
    - _Requirements: 9.1, 10.1, 10.2_

  - [x] 7.2 Annotate `RoleController` and its declared handlers
    - Add `@PermissionResource("ROLES")` to `RoleController`; add `@PermissionOperation` to each declared handler so it does not trip startup validation: `getPermissions` (`GET /{id}/permissions`) → `READ`, `replacePermissions` (`PUT /{id}/permissions`) → `UPDATE`, `batchReplacePermissions` (`PUT /permissions/batch`) → `UPDATE`
    - _Requirements: 9.2, 10.1_

  - [x] 7.3 Annotate `AuditController`, `ResourceController`, and `OperationController`
    - Add `@PermissionResource("AUDIT")` to `AuditController`, `@PermissionResource("RESOURCES")` to `ResourceController`, and `@PermissionResource("OPERATIONS")` to `OperationController`; each inherits its read-only CRUD endpoints resolving to `AUDIT`/`RESOURCES`/`OPERATIONS` + `READ`
    - Confirm `ProjectMemberController`, `DisplayPreferencesController`, and `AuthController` are left unchanged (no `@PermissionResource`), preserving their existing `@RequiresPermission` (project members) / Unguarded (display prefs, auth) behavior
    - _Requirements: 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 10.3, 10.4_

  - [x] 7.4 Write controller resource-mapping unit tests
    - JUnit 5 reflection asserts `UserController`/`RoleController`/`AuditController`/`ResourceController`/`OperationController` carry the expected `@PermissionResource`, and that `ProjectMemberController`/`DisplayPreferencesController`/`AuthController` carry no `@PermissionResource`
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8_

- [x] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Migrate the `SecurityFilterChain` catch-all to `authenticated()`
  - [x] 9.1 Change `SecurityConfig.securityFilterChain` `anyRequest()` from `permitAll()` to `authenticated()`
    - In `com.foremen.config.security.SecurityConfig.securityFilterChain`, change the single catch-all line `.anyRequest().permitAll()` to `.anyRequest().authenticated()`. Preserve the three preceding matchers and their declaration order exactly: `/api/auth/me` → `authenticated()`, `POST /api/auth/resend-invite` → `hasRole("ADMIN")`, `/api/auth/**` → `permitAll()`. Leave every other filter-chain setting unchanged (the `JwtAuthenticationFilter` registration before `UsernamePasswordAuthenticationFilter`, the `JwtAuthenticationEntryPoint` wiring, `SessionCreationPolicy.STATELESS`, disabled CSRF, disabled frame options). This is done after the controllers are annotated (task 7) so tightening authentication does not break the now-guarded endpoints
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6_

  - [x] 9.2 Write filter-chain composed 401/403/200 integration tests
    - `@SpringBootTest` + `MockMvc` over the real MVC + Spring Security context: a request to a protected `/api/**` endpoint (e.g. `GET /api/users`) with **no token** yields **401** via `JwtAuthenticationEntryPoint` (filter chain short-circuits, interceptor never runs); a request with a **valid token whose role lacks** the required matrix grant yields **403** `error.access.denied` (interceptor); a request with a **valid token whose role holds** the grant (or ADMIN) yields **200**
    - _Requirements: 13.5, 14.1, 14.2, 15.1, 15.2, 15.3_

  - [x] 9.3 Write public-auth-matcher and matcher-order integration tests
    - `@SpringBootTest` + `MockMvc`: an unauthenticated request to a `/api/auth/**` endpoint is **not** rejected with 401 by the filter chain (reaches its handler); an unauthenticated `GET /api/auth/me` yields **401**; an unauthenticated `POST /api/auth/resend-invite` yields **401**; assert the declared matcher order in `SecurityConfig` (`/api/auth/me`, then `POST /api/auth/resend-invite`, then `/api/auth/**`, then `anyRequest()`) so the specific auth rules still take precedence over the migrated catch-all
    - _Requirements: 13.6, 14.3, 14.4, 14.5, 14.6, 15.4, 16.3_

  - [x] 9.4 Write Unguarded-now-requires-authentication integration test
    - `@SpringBootTest` + `MockMvc`: an unauthenticated request to a `DisplayPreferencesController` endpoint (a non-`Filter_Chain_Public` path) yields **401** at the filter chain; an authenticated request reaches the controller and is **not** subjected to a matrix check (its own self-versus-requested-id logic runs), confirming `Authenticated_But_Not_Matrix_Checked`; confirm the migrated chain remains STATELESS with CSRF and frame options disabled and existing JWT-auth integration tests pass unmodified
    - _Requirements: 16.1, 16.2, 16.4_

- [x] 10. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Seed the missing `USERS` matrix resource
  - [x] 11.1 Create the `017-seed-users-resource.xml` changeset
    - Create `foremen-backend/database_files/changesets/017-seed-users-resource.xml` following the `009`/`015` pattern: a `017-seed-users-resource` changeset inserting the `USERS` resource row (with RU/PL name/description), guarded by `preConditions onFail="MARK_RAN"` with a `sqlCheck` on `resources WHERE code='USERS'`; and a `017-seed-users-permissions-admin` changeset inserting the ADMIN `role_resources` row and the CRUD `role_resource_operations` grants, guarded by an existence `sqlCheck` on the ADMIN/USERS join, for idempotency
    - _Requirements: 11.1, 11.2, 11.3, 11.4_

  - [x] 11.2 Register the changeset in `changelog.xml`
    - Add `<include file="database_files/changesets/017-seed-users-resource.xml"/>` to `foremen-backend/database_files/changelog.xml` in sequence after the `016` include (last)
    - _Requirements: 11.5_

  - [x] 11.3 Write changelog-registration unit test
    - JUnit 5: assert `changelog.xml` includes `017-seed-users-resource.xml` last in sequence
    - _Requirements: 11.5_

- [x] 12. Integration tests over the real MVC context and seed
  - [x] 12.1 Write end-to-end resolution integration tests
    - `@SpringBootTest` over the real MVC chain: `GET /api/users` resolves `USERS`/`READ`, `POST /api/users` resolves `USERS`/`CREATE`, `POST /api/users/client` resolves `PROJECTS`/`EDIT`; `ProjectMemberController` endpoints resolve `PROJECT_MEMBERS` pairs; `AuthController` / `DisplayPreferencesController` endpoints proceed without a matrix check; assert ADMIN reaches guarded endpoints and existing allow/deny/401 outcomes are preserved
    - _Requirements: 3.2, 7.1, 7.2, 7.3, 7.4, 10.1, 10.2, 10.3, 10.4, 13.1_

  - [x] 12.2 Write startup-validation success and failure integration tests
    - Success: the real application context starts with the applied annotations (no half-annotated controller) — Req 6.1. Failure: a test context containing a deliberately half-annotated controller (resource-without-operation and, separately, operation-without-resource) fails to start; assert the failure/log names the offending controller class and method
    - _Requirements: 6.1, 6.2, 6.3, 6.5, 13.3_

  - [x] 12.3 Write seed completeness and idempotency integration test
    - Testcontainers postgres: after Liquibase runs, assert the six resource rows (`USERS`, `ROLES`, `AUDIT`, `RESOURCES`, `OPERATIONS`, `PROJECT_MEMBERS`) exist and ADMIN holds the `USERS` CRUD grants; run the changelog again against an already-seeded database and assert no duplicate insertions
    - _Requirements: 11.1, 11.2, 11.3, 11.4_

- [x] 13. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 14. Author the `entity-creation-rules.md` steering document
  - Create `.kiro/steering/entity-creation-rules.md` codifying the "new managed entity" checklist: (1) add the resource via a seed changeset registered in `changelog.xml`; (2) add the ADMIN role-matrix grant for the new resource; (3) annotate the concrete controller with `@PermissionResource`; (4) implement `getProjectIdPath()` for project-scoped entities
  - _Requirements: 12.1, 12.2, 12.3, 12.4_

- [x] 15. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). This is a pure API/backend spec (no browser screens), so scenarios are API tests against the Dockerized app (docker compose up, base URL http://localhost:8080, /api/auth for auth, ADMIN bootstrap via FOREMEN_ADMIN_* env vars). Include the filter-chain 401/403/200 scenarios: no-token requests to protected `/api/**` endpoints return 401, valid-token/wrong-role returns 403, valid-token/right-role or ADMIN returns 200, and the `/api/auth/**` public matchers stay reachable while `GET /api/auth/me` and `POST /api/auth/resend-invite` return 401 unauthenticated. Result artifacts are MD reports with tables.
  - _Requirements: 7, 10, 11, 14, 15, 16_

- [x] 16. Final checkpoint - Ensure all tests pass
  - Ensure the backend build compiles and all tests pass (`./gradlew build`); ask the user if questions arise.
  - _Requirements: 13.1, 13.2, 13.4, 13.5, 13.6_

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements for traceability; property test tasks additionally reference the design property number they implement.
- All four design correctness properties are covered by jqwik property tests: Property 1 (task 2.2), Property 2 (task 2.3), Property 3 (task 2.4), Property 4 (task 2.5).
- The only behavior change to existing code is `PermissionInterceptor` delegating pair derivation to `PermissionResolver` (task 4.1); the `ForemenPermissionEvaluator`, ADMIN bypass, and 401/403 pipeline are unchanged.
- The applied-annotation ordering annotates the interfaces (task 6) before the concrete controllers (task 7), and annotates `RoleController`'s declared handlers (task 7.2) so the new startup validator (task 4.2) never trips on a half-annotated controller.
- The `SecurityFilterChain` migration (task 9.1) is a single-line change (`anyRequest().permitAll()` → `anyRequest().authenticated()`) placed after the controllers are annotated (task 7) so tightening authentication does not leave a now-guarded endpoint unreachable; it preserves the three preceding matchers, their order, and all other filter-chain settings. Its integration tests (tasks 9.2–9.4) cover the composed 401/403/200 outcomes, the public auth matchers, matcher order, and the `Authenticated_But_Not_Matrix_Checked` state.
- No new message code is introduced: `error.auth.unauthorized` (401) and `error.access.denied` (403) already exist in both `messages.properties` and `messages_ru.properties`.
- `test-cases.md` is written in Russian per the workspace standard; run artifacts are MD reports with tables, not screenshots. Its API scenarios now include the filter-chain 401/403/200 cases against the Dockerized app.
- Checkpoints (tasks 3, 5, 8, 10, 13, 16) ensure incremental validation.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "2.4", "2.5", "2.6"] },
    { "id": 3, "tasks": ["4.1", "4.2", "6.1", "6.2", "11.1"] },
    { "id": 4, "tasks": ["4.3", "6.3", "7.1", "7.2", "7.3", "11.2"] },
    { "id": 5, "tasks": ["7.4", "11.3", "9.1"] },
    { "id": 6, "tasks": ["9.2", "9.3", "9.4", "12.1", "12.2", "12.3"] },
    { "id": 7, "tasks": ["14", "15"] }
  ]
}
```
