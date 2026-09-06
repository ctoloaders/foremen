# Requirements Document

## Introduction

FOR-03-08 (API protection) is the originally-planned final stage of the FOR-03 auth system: it
migrates the existing controllers onto real ABAC guarding AND tightens the Spring Security filter
chain, replacing the historical `SecurityConfig.permitAll()` default with real authentication rules.
It has two enforcement layers that compose: the Spring Security **filter chain**
(the `SecurityFilterChain`) decides whether a request needs an authenticated principal, and the
Spring MVC `PermissionInterceptor` decides whether the authenticated principal's role holds the
required ABAC matrix grant.

On the authorization layer, the Foremen backend enforces the ABAC permission matrix at the
controller boundary through a single method-level annotation, `@RequiresPermission(resource,
operation)`, resolved by the `PermissionInterceptor`. Most concrete controllers implement generic
CRUD interfaces (`AdminController`, `AdminReadOnlyController`) whose REST endpoints are `default`
methods. Because `@RequiresPermission` is method-only and the interceptor resolves it only on the
final handler method, the only way to guard an inherited CRUD endpoint today is to override the
entire `default` method in every concrete controller solely to attach the annotation. This is
verbose, error-prone, and defeats the purpose of the shared interface.

This feature therefore introduces a declarative, inheritance-aware guarding scheme so a controller
can be guarded without overriding CRUD methods. It adds two new annotations — `@PermissionResource`
on the concrete controller class and `@PermissionOperation` on the interface CRUD methods — and a
resolution rule in the interceptor that combines them, while preserving the existing
`@RequiresPermission` behavior exactly. A handler is treated as unguarded only when it carries no
permission declaration at all; a half-annotated controller (a resource without a matching operation,
or an operation without a resource) is a misconfiguration that fails application startup rather than
silently becoming an unguarded endpoint. It then applies these annotations across the existing admin
controllers and seeds any missing matrix entries.

On the authentication layer, this feature migrates the `SecurityFilterChain` so its catch-all
`anyRequest()` rule changes from `permitAll()` to `authenticated()`. After the migration every
request outside the public auth matchers requires a valid JWT principal, while the public auth
endpoints (`/api/auth/**`), the authenticated `/api/auth/me`, and the ADMIN-only
`POST /api/auth/resend-invite` keep their existing behavior and matcher ordering. The two layers
compose: an unauthenticated request to a protected `/api/**` endpoint yields HTTP 401 (via the
existing `JwtAuthenticationEntryPoint`), an authenticated request lacking the required matrix grant
yields HTTP 403 (via the `PermissionInterceptor`), and an authenticated-and-permitted request
proceeds. Note that "interceptor-level Unguarded" (no matrix check) is distinct from "filter-chain
public": an endpoint the interceptor treats as Unguarded (for example the `DisplayPreferencesController`
self-check) still requires an authenticated principal at the filter-chain layer once `anyRequest()`
is `authenticated()`.

Finally, this feature codifies a reusable "new entity" checklist as steering.

This spec produces only planning artifacts (requirements, design, tasks, test-cases) and a steering
file authored during the tasks phase. No production code is written in this workflow.

## Glossary

- **Permission_Matrix**: The ABAC data model of `roles` × `resources` × `operations` grants stored
  in `role_resources` / `role_resource_operations` and evaluated by the `Permission_Evaluator`.
- **Permission_Evaluator**: `ForemenPermissionEvaluator#isAllowed(roleCode, resource, operation)`,
  which returns allow/deny with an exact-code ADMIN bypass. Unchanged by this feature.
- **Permission_Interceptor**: `PermissionInterceptor`, the Spring MVC `HandlerInterceptor` that
  guards handler methods before controller execution.
- **Permission_Resolver**: The resolution component (a helper delegated to by the
  `Permission_Interceptor`) that determines the `(resource, operation)` pair required for a handler
  method, per the resolution algorithm defined in this document.
- **RequiresPermission**: The existing method-level annotation
  `@RequiresPermission(String resource, String operation)`, `@Target(METHOD)`, `@Retention(RUNTIME)`.
- **PermissionResource**: The new class-level annotation `@PermissionResource(String value)`,
  `@Target(TYPE)`, `@Retention(RUNTIME)`, naming the resource a concrete controller manages.
- **PermissionOperation**: The new method-level annotation `@PermissionOperation(String value)`,
  `@Target(METHOD)`, `@Retention(RUNTIME)`, naming the operation a CRUD method performs.
- **CRUD_Interface**: A generic controller interface (`AdminController`, `AdminReadOnlyController`)
  whose REST endpoints are `default` methods inherited by concrete controllers.
- **Concrete_Controller**: A Spring `@RestController` class that implements a `CRUD_Interface`
  and/or declares its own handler methods (e.g. `UserController`, `RoleController`,
  `AuditController`, `ResourceController`, `OperationController`, `ProjectMemberController`,
  `DisplayPreferencesController`, `AuthController`).
- **Bean_Type**: The concrete controller class of a matched handler, obtained via
  `HandlerMethod#getBeanType()`.
- **Bridge_Method**: A synthetic/bridge method Spring may present for an interface `default` method;
  the governing annotations live on the interface's declared method, not the synthetic copy.
- **Resolved_Pair**: The `(resource, operation)` pair the `Permission_Resolver` produces for a
  handler, which the `Permission_Interceptor` submits to the `Permission_Evaluator`.
- **Unguarded**: A handler that carries no permission declaration at all — neither a
  `RequiresPermission` annotation nor any of (`PermissionResource` on the `Bean_Type` /
  `PermissionOperation` on the declaring method); no `Resolved_Pair` exists and the request proceeds
  without a matrix check (matching today's behavior for endpoints intentionally left unannotated,
  such as `AuthController` and `DisplayPreferencesController`). A partially annotated handler is NOT
  Unguarded — it is a misconfiguration rejected by `Startup_Validation`.
- **Startup_Validation**: The `Annotation_Completeness_Validation` performed once at application
  startup that scans every Spring MVC controller handler method and fails application startup when a
  controller's permission annotations are incomplete (a `PermissionResource` without a matching
  `PermissionOperation` on an in-scope handler method, or a `PermissionOperation` without a
  `PermissionResource` on its declaring controller).
- **Annotation_Completeness_Validation**: See `Startup_Validation`.
- **In_Scope_Handler_Method**: For a given `Concrete_Controller`, a REST-mapped handler method that
  is subject to the `Permission_Interceptor` — namely the CRUD `default` methods inherited from a
  `CRUD_Interface` plus any handler method the controller declares with a Spring `@RequestMapping`
  (or a composed variant such as `@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping`/
  `@PatchMapping`). Arbitrary non-mapped helper methods are NOT in scope.
- **Entity_Creation_Rules**: A steering document codifying the checklist for adding a new managed
  entity to the system.
- **Security_Filter_Chain**: The Spring Security `SecurityFilterChain` configured in `SecurityConfig`
  that authenticates requests before they reach any controller. It runs the
  `JwtAuthenticationFilter`, applies the `authorizeHttpRequests` matcher rules, and delegates
  unauthenticated access to protected endpoints to the `JwtAuthenticationEntryPoint`. This is the
  authentication layer, distinct from the `Permission_Interceptor` authorization layer.
- **JwtAuthenticationEntryPoint**: The existing entry point that produces the HTTP 401 response body
  for a request the `Security_Filter_Chain` rejects as unauthenticated.
- **Public_Auth_Matchers**: The `Security_Filter_Chain` matchers that stay publicly reachable
  (`permitAll`) after the migration — namely `/api/auth/**` (login, refresh, logout, set-password,
  and the OTP `request`/`verify` endpoints) — together with `/api/auth/me` (`authenticated`) and
  `POST /api/auth/resend-invite` (`hasRole('ADMIN')`).
- **Filter_Chain_Public**: A request path matched by a `permitAll` matcher in the
  `Security_Filter_Chain`, reachable without any authenticated principal. Distinct from
  interceptor-level `Unguarded`: an `Unguarded` handler skips the matrix check but, once
  `anyRequest()` is `authenticated()`, still requires an authenticated principal unless its path is
  also `Filter_Chain_Public`.
- **Authenticated_But_Not_Matrix_Checked**: The end state of a handler the `Permission_Interceptor`
  classifies as `Unguarded` whose path is NOT `Filter_Chain_Public`: the request must carry a valid
  JWT principal (enforced by the `Security_Filter_Chain`) but is not subjected to a matrix grant
  check (e.g. `DisplayPreferencesController`, which performs its own self-versus-requested-id check).

## Requirements

### Requirement 1: PermissionResource annotation

**User Story:** As a backend developer, I want a class-level annotation that names the resource a
controller manages, so that I can guard every inherited CRUD endpoint without overriding methods.

#### Acceptance Criteria

1. THE PermissionResource SHALL be declared with retention `RUNTIME` and target `TYPE`.
2. THE PermissionResource SHALL expose a single `String value` member that names a resource code.
3. WHERE a Concrete_Controller is annotated with PermissionResource, THE Permission_Resolver SHALL
   treat the annotation `value` as the resource code for that controller's handler methods.

### Requirement 2: PermissionOperation annotation

**User Story:** As a backend developer, I want a method-level annotation that names the operation a
CRUD method performs, so that the shared interface declares operations once for all implementors.

#### Acceptance Criteria

1. THE PermissionOperation SHALL be declared with retention `RUNTIME` and target `METHOD`.
2. THE PermissionOperation SHALL expose a single `String value` member that names an operation code.
3. WHERE a CRUD_Interface `default` method is annotated with PermissionOperation, THE
   Permission_Resolver SHALL treat the annotation `value` as the operation code for that method.

### Requirement 3: Resolution precedence — RequiresPermission wins

**User Story:** As a maintainer, I want existing `@RequiresPermission` usages to keep working
unchanged, so that adopting the new scheme carries no regression risk.

#### Acceptance Criteria

1. WHEN the final handler method carries a RequiresPermission annotation, THE Permission_Resolver
   SHALL produce the Resolved_Pair from that annotation's `resource` and `operation` and SHALL
   ignore any PermissionResource or PermissionOperation present.
2. WHEN a handler resolves via RequiresPermission, THE Permission_Interceptor SHALL evaluate the
   Resolved_Pair against the Permission_Evaluator identically to the pre-change behavior.

### Requirement 4: Resolution via PermissionResource + PermissionOperation

**User Story:** As a maintainer, I want the class resource and the interface operation combined into
a permission check, so that inherited CRUD endpoints are guarded declaratively.

#### Acceptance Criteria

1. WHEN the final handler method carries no RequiresPermission annotation AND the Bean_Type carries
   a PermissionResource annotation AND the handler's declaring method carries a PermissionOperation
   annotation, THE Permission_Resolver SHALL produce the Resolved_Pair from the PermissionResource
   `value` as the resource and the PermissionOperation `value` as the operation.
2. WHEN resolving PermissionOperation for a handler backed by a CRUD_Interface `default` method,
   THE Permission_Resolver SHALL locate the annotation on the interface's declared method rather
   than on any Bridge_Method.
3. WHEN resolving PermissionResource for a handler, THE Permission_Resolver SHALL read the
   annotation from the Bean_Type obtained via `HandlerMethod#getBeanType()`.

### Requirement 5: Fallback to unguarded when no permission declaration is present

**User Story:** As a maintainer, I want handlers that carry no permission declaration at all to
remain unguarded, so that public and self-service endpoints keep working exactly as before.

#### Acceptance Criteria

1. IF a handler carries neither a RequiresPermission annotation NOR any of a PermissionResource
   annotation on its Bean_Type or a PermissionOperation annotation on its declaring method, THEN THE
   Permission_Resolver SHALL classify the handler as Unguarded and THE Permission_Interceptor SHALL
   allow the request to proceed without a matrix check.
2. WHEN the matched handler is not a `HandlerMethod`, THE Permission_Interceptor SHALL allow the
   request to proceed without resolution.

### Requirement 6: Startup validation of annotation completeness

**User Story:** As a maintainer, I want a half-annotated controller to fail application startup with
a clear, actionable log, so that a misconfigured permission declaration can never silently degrade
into an unguarded endpoint.

The intended mechanism is a startup-time component (for example an `ApplicationRunner`, a
`SmartInitializingSingleton`, or a bean that runs after context refresh) that iterates the handler
methods registered in `RequestMappingHandlerMapping` and checks each `In_Scope_Handler_Method` for
annotation completeness. The design phase selects the concrete mechanism; this requirement fixes the
behavior (WHAT), not the implementation (HOW).

#### Acceptance Criteria

1. WHEN the application starts, THE Startup_Validation SHALL scan all Spring MVC controller handler
   methods registered in `RequestMappingHandlerMapping` and validate annotation completeness for
   each In_Scope_Handler_Method.
2. IF a Concrete_Controller class is annotated with a PermissionResource annotation AND one or more
   of its In_Scope_Handler_Methods carries no PermissionOperation annotation AND that method carries
   no RequiresPermission annotation, THEN THE application SHALL fail to start and THE Startup_Validation
   SHALL emit an error log naming the offending controller class and each offending method.
3. IF an In_Scope_Handler_Method is annotated with a PermissionOperation annotation AND its declaring
   Bean_Type carries no PermissionResource annotation AND that method carries no RequiresPermission
   annotation, THEN THE application SHALL fail to start and THE Startup_Validation SHALL emit an error
   log naming the offending method and its declaring controller class.
4. WHERE an In_Scope_Handler_Method carries a RequiresPermission annotation, THE Startup_Validation
   SHALL treat that method as complete and SHALL raise no error for that method regardless of whether
   a PermissionResource or PermissionOperation annotation is present.
5. WHEN the Startup_Validation reports one or more misconfigurations, THE error log SHALL name each
   specific controller class and method so that the misconfiguration is actionable.
6. WHERE a Concrete_Controller carries neither a PermissionResource annotation nor any
   PermissionOperation annotation on its In_Scope_Handler_Methods, THE Startup_Validation SHALL treat
   the controller as intentionally Unguarded and SHALL raise no error.

### Requirement 7: Authorization outcomes preserved

**User Story:** As a security owner, I want the allow/deny/401 outcomes to be unchanged for guarded
endpoints, so that the resolution refactor does not weaken enforcement.

#### Acceptance Criteria

1. WHEN a handler produces a Resolved_Pair AND no authenticated principal is present, THE
   Permission_Interceptor SHALL respond with HTTP 401 and message key `error.auth.unauthorized`.
2. WHEN a handler produces a Resolved_Pair AND the Permission_Evaluator denies the current role for
   that pair, THE Permission_Interceptor SHALL respond with HTTP 403 and message key
   `error.access.denied`.
3. WHEN a handler produces a Resolved_Pair AND the Permission_Evaluator allows the current role for
   that pair, THE Permission_Interceptor SHALL allow the request to proceed.
4. WHILE the current role code equals `ADMIN`, THE Permission_Evaluator SHALL allow every
   Resolved_Pair without a matrix lookup.

### Requirement 8: CRUD operation annotations on the interfaces

**User Story:** As a backend developer, I want each generic CRUD endpoint annotated with its
operation, so that every implementing controller inherits the correct operation classification.

#### Acceptance Criteria

1. THE AdminController `create` method and `createBulk` method SHALL each carry a PermissionOperation
   with value `CREATE`.
2. THE AdminController `update` method SHALL carry a PermissionOperation with value `UPDATE`.
3. THE AdminController `find`, `findExtended`, `findById`, `getCount`, `getAudit`,
   `getI18nProperties`, and `getMetadata` methods SHALL each carry a PermissionOperation with value
   `READ`.
4. THE AdminController `deleteById` method and `setPropertiesToNull` method SHALL each carry a
   PermissionOperation with value `DELETE`.
5. THE AdminReadOnlyController `find`, `findById`, and `getMetadata` methods SHALL each carry a
   PermissionOperation with value `READ`.

### Requirement 9: Resource annotations on the concrete controllers

**User Story:** As a backend developer, I want each admin controller mapped to its resource, so that
its inherited CRUD endpoints resolve to the correct resource in the matrix.

#### Acceptance Criteria

1. THE UserController SHALL carry a PermissionResource with value `USERS`.
2. THE RoleController SHALL carry a PermissionResource with value `ROLES`.
3. THE AuditController SHALL carry a PermissionResource with value `AUDIT`.
4. THE ResourceController SHALL carry a PermissionResource with value `RESOURCES`.
5. THE OperationController SHALL carry a PermissionResource with value `OPERATIONS`.
6. THE ProjectMemberController SHALL retain its existing per-method RequiresPermission annotations on
   the `PROJECT_MEMBERS` resource without a PermissionResource annotation.
7. THE DisplayPreferencesController SHALL carry neither a PermissionResource nor a RequiresPermission
   annotation, preserving its self-versus-requested-id authorization.
8. THE AuthController SHALL carry neither a PermissionResource nor a RequiresPermission annotation,
   preserving its public and self-service endpoints as Unguarded.

### Requirement 10: Guarding behavior per controller after the change

**User Story:** As a reviewer, I want a defined end-state for how every controller is guarded, so
that the applied annotations can be verified against an explicit expectation.

#### Acceptance Criteria

1. WHEN any inherited CRUD endpoint on UserController, RoleController, AuditController,
   ResourceController, or OperationController is invoked, THE Permission_Resolver SHALL produce a
   Resolved_Pair of that controller's PermissionResource value and the endpoint's PermissionOperation
   value.
2. WHEN the UserController `registerClient` endpoint (`POST /api/users/client`) is invoked, THE
   Permission_Resolver SHALL produce the Resolved_Pair `PROJECTS` / `EDIT` from its existing
   RequiresPermission annotation.
3. WHEN any ProjectMemberController endpoint is invoked, THE Permission_Resolver SHALL produce a
   Resolved_Pair on the `PROJECT_MEMBERS` resource from its existing RequiresPermission annotations.
4. WHEN any DisplayPreferencesController or AuthController endpoint is invoked, THE
   Permission_Interceptor SHALL classify the handler as Unguarded and allow the request to proceed
   without a matrix check.

### Requirement 11: Matrix seed completeness

**User Story:** As a system operator, I want every resource referenced by a controller annotation to
exist in the matrix with ADMIN fully granted, so that the admin panel keeps working after the change.

#### Acceptance Criteria

1. THE Permission_Matrix SHALL contain a resource row for each of `USERS`, `ROLES`, `AUDIT`,
   `RESOURCES`, `OPERATIONS`, and `PROJECT_MEMBERS`.
2. WHERE a resource referenced by a controller PermissionResource annotation has no matrix resource
   row, THE seed changeset SHALL insert the resource row.
3. WHERE the ADMIN role lacks a grant for a resource-operation pair that a guarded endpoint resolves
   to, THE seed changeset SHALL insert the corresponding ADMIN grant.
4. WHEN a seed changeset re-runs against a database that already contains the seeded rows, THE seed
   changeset SHALL make no duplicate insertions.
5. THE new seed changeset SHALL be registered in `database_files/changelog.xml` in sequence after
   the existing changesets.

### Requirement 12: Entity creation steering rules

**User Story:** As a backend developer, I want a documented checklist for adding a new managed
entity, so that resource, matrix, controller, and project-scope wiring is consistent every time.

#### Acceptance Criteria

1. THE Entity_Creation_Rules steering document SHALL instruct the developer to add the resource via
   a seed changeset registered in the changelog.
2. THE Entity_Creation_Rules steering document SHALL instruct the developer to add the ADMIN
   role-matrix grant for the new resource.
3. THE Entity_Creation_Rules steering document SHALL instruct the developer to annotate the concrete
   controller with PermissionResource.
4. THE Entity_Creation_Rules steering document SHALL instruct the developer to implement
   `getProjectIdPath()` for project-scoped entities.

### Requirement 13: Backward compatibility and verification

**User Story:** As a maintainer, I want the existing tests to keep passing and the new resolution,
startup validation, and filter-chain migration to be covered by tests, so that the change is safe to
merge.

#### Acceptance Criteria

1. WHEN the existing Permission_Evaluator and Permission_Interceptor test suites run after the
   change, THE test suites SHALL pass without modification to their assertions about existing
   RequiresPermission behavior.
2. THE Permission_Resolver behavior SHALL be covered by automated tests for each resolution branch:
   RequiresPermission precedence, PermissionResource-plus-PermissionOperation combination, and the
   Unguarded fallback (no permission declaration at all).
3. THE Startup_Validation behavior SHALL be covered by an automated test in which a deliberately
   half-annotated controller (a PermissionResource with a missing PermissionOperation, or a
   PermissionOperation with a missing PermissionResource) causes the application context to fail to
   start, and THE test SHALL assert that the validation reports the offending controller class and
   method.
4. WHEN the backend build and test task runs, THE build SHALL compile and all tests SHALL pass.
5. THE Security_Filter_Chain migration SHALL be covered by integration tests against the running
   application that assert: a request to a protected `/api/**` endpoint with no token yields HTTP
   401; a request with a valid token whose role lacks the required matrix grant yields HTTP 403; and
   a request with a valid token whose role holds the required matrix grant yields HTTP 200.
6. THE Security_Filter_Chain migration SHALL be covered by integration tests asserting that the
   Public_Auth_Matchers remain reachable as before: an unauthenticated request to a `/api/auth/**`
   endpoint is not rejected with HTTP 401 by the Security_Filter_Chain, an unauthenticated request to
   `/api/auth/me` yields HTTP 401, and an unauthenticated `POST /api/auth/resend-invite` yields HTTP
   401.

### Requirement 14: Security filter-chain migration from permitAll to authenticated

**User Story:** As a security owner, I want the Spring Security filter chain to require an
authenticated principal for every request outside the public auth matchers, so that the historical
`permitAll()` default no longer leaves protected endpoints reachable without a JWT.

#### Acceptance Criteria

1. THE Security_Filter_Chain SHALL configure its catch-all `anyRequest()` rule as `authenticated()`
   rather than `permitAll()`.
2. WHEN an incoming request matches none of the Public_Auth_Matchers, THE Security_Filter_Chain SHALL
   require an authenticated JWT principal for that request.
3. THE Security_Filter_Chain SHALL keep `/api/auth/**` as `permitAll`, covering login, refresh,
   logout, set-password, and the OTP `request` and `verify` endpoints.
4. THE Security_Filter_Chain SHALL keep `/api/auth/me` as `authenticated`.
5. THE Security_Filter_Chain SHALL keep `POST /api/auth/resend-invite` as `hasRole('ADMIN')`.
6. THE Security_Filter_Chain SHALL preserve the existing matcher declaration order: `/api/auth/me`,
   then `POST /api/auth/resend-invite`, then `/api/auth/**`, then the catch-all `anyRequest()`.

### Requirement 15: Composed enforcement outcomes after the migration

**User Story:** As a security owner, I want the filter-chain authentication layer and the interceptor
authorization layer to compose predictably, so that the migration produces the intended 401/403/200
outcomes without weakening either layer.

#### Acceptance Criteria

1. IF an unauthenticated request targets a protected `/api/**` endpoint that is not a
   Filter_Chain_Public path, THEN THE Security_Filter_Chain SHALL reject the request via the
   JwtAuthenticationEntryPoint with HTTP 401.
2. WHEN an authenticated request reaches a handler that produces a Resolved_Pair AND the
   Permission_Evaluator denies the current role for that pair, THE Permission_Interceptor SHALL
   respond with HTTP 403 and message key `error.access.denied`.
3. WHEN an authenticated request reaches a handler that produces a Resolved_Pair AND the
   Permission_Evaluator allows the current role for that pair, THE Permission_Interceptor SHALL allow
   the request to proceed.
4. WHERE a request path is Filter_Chain_Public, THE Security_Filter_Chain SHALL allow the request to
   reach its handler without an authenticated principal.

### Requirement 16: Unguarded handlers become authenticated but remain matrix-unchecked

**User Story:** As a reviewer, I want the interaction between interceptor-level Unguarded handlers
and the tightened filter chain to be explicit, so that "Unguarded" is never misread as "public".

#### Acceptance Criteria

1. WHERE a handler is classified as Unguarded by the Permission_Interceptor AND the handler's path is
   NOT Filter_Chain_Public, THE Security_Filter_Chain SHALL require an authenticated principal and THE
   Permission_Interceptor SHALL allow the request to proceed without a matrix check, making the
   handler Authenticated_But_Not_Matrix_Checked.
2. WHEN a DisplayPreferencesController endpoint is invoked, THE Security_Filter_Chain SHALL require an
   authenticated principal AND THE Permission_Interceptor SHALL classify the handler as Unguarded so
   that DisplayPreferencesController performs its own self-versus-requested-id authorization.
3. WHERE an AuthController endpoint path matches `/api/auth/**`, THE Security_Filter_Chain SHALL keep
   the endpoint Filter_Chain_Public so that AuthController public and self-service endpoints stay
   reachable without authentication.
4. THE requirement that interceptor-level Unguarded classification skips the matrix check (Requirement
   5, Requirement 10) SHALL remain unchanged; the filter-chain authentication requirement adds an
   authentication precondition for non-Filter_Chain_Public paths and SHALL NOT reclassify any handler
   as guarded.
