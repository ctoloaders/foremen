# Requirements Document

## Introduction

This specification defines the runtime permission evaluation mechanism for the Foremen backend (FOR-03-03, the third child spec of the FOR-03 auth system). It delivers the machinery that answers, for the currently authenticated user, the question: "does the current user's role hold operation Y on resource Z?" and enforces that answer before a protected controller method runs.

The feature builds directly on two earlier deliverables:
- The ABAC model from FOR-02-03 (`RoleEntity`, `RoleResourceEntity`, `ResourceEntity`, `OperationEntity`) which stores the "role × resource × operations" matrix in the database.
- The JWT authentication from FOR-03-01, whose `JwtAuthenticationFilter` populates the Spring `SecurityContext` with a principal equal to the userId (from the `sub` claim) and a single authority `ROLE_<roleCode>` (the JWT `role` claim carries the role CODE, not the id).

Scope is strictly limited to:
- A `ForemenPermissionEvaluator` service that resolves a role's permission set from the database and evaluates a (resource, operation) pair against it.
- A `@RequiresPermission(resource, operation)` annotation placed on controller methods.
- A single enforcement component (a Spring `HandlerInterceptor` registered via `WebMvcConfigurer`) that reads the current authentication from the `SecurityContext` and rejects requests that lack the required permission before the controller method executes.
- Caffeine caching of a role's permission set (cache key = role code), distinct from the global cache spec, sized to hold every role and relying primarily on explicit invalidation when a role's permissions or the role itself change; a long idle-expiry safety net (24 hours after last access) guards only against a missed invalidation.
- ADMIN bypass: a user whose role code equals `ADMIN` is always allowed without consulting the matrix.
- A denied-permission outcome of HTTP 403 with message code `error.access.denied`, and an unauthenticated outcome of HTTP 401 with message code `error.auth.unauthorized`, both localized in Polish (PL) and Russian (RU).

The following are explicitly OUT of scope and belong to other FOR-03 child specs:
- Project-ownership data filtering (`project_members`, SQL-level scoping) belongs to FOR-03-04.
- Wholesale replacement of `permitAll()` in `SecurityConfig` and annotating every existing controller with `@RequiresPermission` belongs to FOR-03-08. This spec DELIVERS the mechanism plus its tests and MAY annotate one demonstrative endpoint or a dedicated test-only controller to prove enforcement, but MUST NOT migrate the existing controllers.

This feature reuses existing infrastructure: the ABAC entities and DAOs (`RoleDao.findByCode`, `RoleResourceDao.findAllByRoleId`), `ForemenApiException` + `ForemenControllerAdvice`, the i18n message bundles (`messages.properties` PL base, `messages_ru.properties` RU), the Caffeine cache abstraction from FOR-01, and the jqwik + JUnit 5 + Testcontainers + Spring Security Test conventions used in FOR-03-01/FOR-02-03.

## Glossary

- **Permission**: A pair (resource code, operation code) — for example (`PROJECTS`, `CREATE`) — expressing the right to perform an operation on a resource.
- **Permission_Set**: The complete collection of Permissions granted to a single role, derived from that role's `RoleResourceEntity` rows and their associated `OperationEntity` codes.
- **ForemenPermissionEvaluator**: The service component that loads a role's Permission_Set and evaluates whether a given (resource code, operation code) pair is present in it, applying the ADMIN bypass.
- **RequiresPermission**: A runtime-retained Java annotation, targeting methods, declaring the resource code and operation code required to invoke the annotated controller endpoint.
- **PermissionInterceptor**: The Spring `HandlerInterceptor` that inspects the matched handler method for a `@RequiresPermission` declaration and enforces it before the controller method executes.
- **WebMvcConfigurer**: The Spring MVC configuration hook used to register the PermissionInterceptor into the interceptor chain.
- **SecurityContext**: The Spring Security `SecurityContextHolder` context populated by `JwtAuthenticationFilter`, holding the authentication whose principal is the userId and whose single authority is `ROLE_<roleCode>`.
- **Role_Code**: The string code identifying a role (`ADMIN`, `MANAGER`, `FOREMAN`, `WORKER`, `FINANCIER`, `CLIENT`), carried in the JWT `role` claim and exposed by the `ROLE_<roleCode>` authority.
- **Resource_Code**: The string code identifying a system resource (`PROJECTS`, `ROOMS`, `ESTIMATE`, `WAREHOUSE`, and meta resources `ROLES`, `OPERATIONS`, `RESOURCES`).
- **Operation_Code**: The string code identifying an operation (`CREATE`, `READ`, `UPDATE`, `DELETE`).
- **ADMIN_Bypass**: The rule by which any request whose Role_Code equals `ADMIN` is granted access to any `@RequiresPermission` endpoint without consulting the matrix.
- **Permission_Cache**: A Caffeine cache mapping Role_Code to a Permission_Set, kept separate from the global cache specification. Because a role's permissions rarely change and every change explicitly invalidates the affected entry, the cache is sized to hold every role and entries are retained until either explicitly invalidated or idle for the Permission_Cache_Idle_TTL. There is no short write-based expiry.
- **Permission_Cache_Idle_TTL**: The idle expiry (expire-after-access) of the Permission_Cache, defaulting to 24 hours (1440 minutes), configurable via config property `foremen.permission.cache-ttl-minutes`. It is a safety net for a missed invalidation, not the primary freshness mechanism; explicit invalidation on a permission change is authoritative.
- **RoleEntity**: The existing JPA entity for a role (`com.foremen.dao.model.RoleEntity`), carrying a `code` field and a list of `RoleResourceEntity`.
- **RoleResourceEntity**: The existing JPA join entity (`com.foremen.dao.model.RoleResourceEntity`) mapping a role to a resource and its eagerly-fetched list of operations.
- **RoleDao**: The existing repository (`com.foremen.dao.RoleDao`) exposing `findByCode(String)`.
- **RoleResourceDao**: The existing repository (`com.foremen.dao.RoleResourceDao`) exposing `findAllByRoleId(Long)`.
- **RoleService**: The existing service (`com.foremen.service.RoleService`) whose `replacePermissions`, `create`, `update`, `updateAll`, and `deleteById` methods are the documented cache-invalidation touch points for the Permission_Cache.
- **ForemenApiException**: The existing application exception carrying an HTTP status, an i18n message code, and optional parameters.
- **ForemenControllerAdvice**: The existing global exception handler translating ForemenApiException into HTTP responses.
- **JwtAuthenticationEntryPoint**: The existing Spring Security entry point that writes the HTTP 401 body (message code `error.auth.unauthorized`) for unauthenticated access to a protected endpoint.

## Requirements

### Requirement 1: Permission Evaluation Correctness

**User Story:** As a backend developer, I want a service that decides whether the current role holds a given operation on a given resource, so that access rules are enforced consistently from the database matrix.

#### Acceptance Criteria

1. WHEN the ForemenPermissionEvaluator is asked whether a Role_Code holds a given Resource_Code and Operation_Code, THE ForemenPermissionEvaluator SHALL return an allow decision if and only if the role's Permission_Set contains that (Resource_Code, Operation_Code) pair.
2. WHEN the ForemenPermissionEvaluator loads a role's Permission_Set, THE ForemenPermissionEvaluator SHALL resolve the RoleEntity by Role_Code using RoleDao and derive the Permission_Set from that role's RoleResourceEntity rows and their associated Operation_Codes.
3. IF a Role_Code does not resolve to an existing RoleEntity, THEN THE ForemenPermissionEvaluator SHALL return a deny decision.
4. THE ForemenPermissionEvaluator SHALL treat Resource_Code and Operation_Code comparisons as exact string matches.

---

### Requirement 2: Deny When No Matrix Entry Exists

**User Story:** As a security reviewer, I want the evaluator to deny by default, so that a missing matrix entry never grants access.

#### Acceptance Criteria

1. IF a role's Permission_Set contains no entry for the requested Resource_Code, THEN THE ForemenPermissionEvaluator SHALL return a deny decision.
2. IF a role has a Permission_Set entry for the requested Resource_Code but that entry does not include the requested Operation_Code, THEN THE ForemenPermissionEvaluator SHALL return a deny decision.
3. WHILE a role's Permission_Set is empty, THE ForemenPermissionEvaluator SHALL return a deny decision for every non-ADMIN Role_Code and every (Resource_Code, Operation_Code) pair.

---

### Requirement 3: ADMIN Bypass

**User Story:** As an administrator, I want the ADMIN role to be allowed everywhere, so that administration is never blocked by the matrix.

#### Acceptance Criteria

1. WHEN the ForemenPermissionEvaluator evaluates a request whose Role_Code equals `ADMIN`, THE ForemenPermissionEvaluator SHALL return an allow decision without consulting the Permission_Set.
2. WHEN a Role_Code equals `ADMIN`, THE ForemenPermissionEvaluator SHALL return an allow decision for every Resource_Code and Operation_Code pair.
3. THE ForemenPermissionEvaluator SHALL apply the ADMIN_Bypass using an exact match of the Role_Code against the literal `ADMIN`.

---

### Requirement 4: RequiresPermission Annotation Semantics

**User Story:** As a controller author, I want to declare the required resource and operation on an endpoint, so that the enforcement layer knows what to check.

#### Acceptance Criteria

1. THE RequiresPermission annotation SHALL declare a `resource` attribute of type String and an `operation` attribute of type String.
2. THE RequiresPermission annotation SHALL be retained at runtime and SHALL be applicable to methods only.
3. WHERE a `@RequiresPermission` declaration exists on a handler method, THE PermissionInterceptor SHALL use that method-level declaration's Resource_Code and Operation_Code as the required Permission.
4. WHERE a matched handler method carries no method-level `@RequiresPermission`, THE PermissionInterceptor SHALL treat the endpoint as unannotated and SHALL NOT evaluate any Permission (no type-level fallback).

---

### Requirement 5: Enforcement Before Controller Execution

**User Story:** As a security reviewer, I want the permission check to run before the controller method, so that unauthorized logic never executes.

#### Acceptance Criteria

1. WHERE a matched handler method carries an effective `@RequiresPermission` declaration, THE PermissionInterceptor SHALL evaluate the required Permission and return control to the framework before the controller method executes.
2. IF the PermissionInterceptor denies a request, THEN THE PermissionInterceptor SHALL prevent the controller method from executing.
3. WHERE a matched handler method carries no effective `@RequiresPermission` declaration, THE PermissionInterceptor SHALL allow the request to proceed to the controller method without evaluating any Permission.
4. WHEN the PermissionInterceptor evaluates a request, THE PermissionInterceptor SHALL read the current Role_Code from the `ROLE_<roleCode>` authority present in the SecurityContext.

---

### Requirement 6: Denied Access Yields HTTP 403

**User Story:** As an API consumer, I want a clear 403 when I lack permission, so that I can distinguish authorization failure from other errors.

#### Acceptance Criteria

1. IF the PermissionInterceptor evaluates an authenticated request against an effective `@RequiresPermission` declaration and the ForemenPermissionEvaluator returns a deny decision, THEN THE PermissionInterceptor SHALL raise a ForemenApiException with HTTP status 403 and message code `error.access.denied`.
2. WHEN a ForemenApiException with status 403 and code `error.access.denied` is raised, THE ForemenControllerAdvice SHALL translate it into an HTTP 403 response carrying the localized `error.access.denied` message.

---

### Requirement 7: Unauthenticated Access Yields HTTP 401

**User Story:** As an API consumer, I want a 401 when I call a protected endpoint without authentication, so that I know to authenticate first.

#### Acceptance Criteria

1. IF a request reaches an endpoint that requires authentication without a valid Access_Token in the SecurityContext, THEN THE JwtAuthenticationEntryPoint SHALL produce an HTTP 401 response with message code `error.auth.unauthorized` before the PermissionInterceptor evaluates any Permission.
2. IF the PermissionInterceptor evaluates a request that carries an effective `@RequiresPermission` declaration and the SecurityContext holds no authenticated principal, THEN THE PermissionInterceptor SHALL raise a ForemenApiException with HTTP status 401 and message code `error.auth.unauthorized`.

---

### Requirement 8: Permission Cache Load and Retention

**User Story:** As an operator, I want role permissions cached and kept until they change, so that repeated checks avoid repeated database reads and stay fresh through explicit invalidation rather than a short timeout.

#### Acceptance Criteria

1. WHEN the ForemenPermissionEvaluator resolves a Permission_Set for a Role_Code that is not present in the Permission_Cache, THE ForemenPermissionEvaluator SHALL load the Permission_Set from the database and store it in the Permission_Cache keyed by Role_Code.
2. WHEN the ForemenPermissionEvaluator resolves a Permission_Set for a Role_Code that is present in the Permission_Cache, THE ForemenPermissionEvaluator SHALL return the cached Permission_Set without querying the database.
3. THE Permission_Cache SHALL be sized to hold at least as many entries as there are roles, so that no role's Permission_Set is evicted for capacity reasons under normal operation.
4. THE Permission_Cache SHALL retain each entry until it is explicitly invalidated or until it has been idle (not read) for the Permission_Cache_Idle_TTL, which SHALL default to 24 hours and SHALL be configured independently of the global cache specification.
5. THE Permission_Cache SHALL NOT apply a short write-based expiry; freshness after a permission change is guaranteed by explicit invalidation (Requirement 9), not by a timeout.

---

### Requirement 9: Permission Cache Invalidation

**User Story:** As an administrator, I want role changes (create, update, delete, and permission changes) to take effect promptly, so that a revoked grant is not honored from a stale cache.

#### Acceptance Criteria

1. WHEN RoleService.replacePermissions changes a role's matrix entries, THE Permission_Cache SHALL evict the entry keyed by that role's Role_Code.
2. WHEN RoleService.deleteById removes a role, THE Permission_Cache SHALL evict the entry keyed by that role's Role_Code, using the code captured before the role is deleted.
3. WHEN RoleService.update or RoleService.updateAll modifies a role, THE Permission_Cache SHALL evict the entry keyed by the affected role's Role_Code.
4. WHEN RoleService.create creates a role, THE Permission_Cache SHALL evict any entry keyed by the created role's Role_Code so that no stale entry for a reused code is served.
5. WHEN the Permission_Cache entry for a Role_Code has been evicted, THE ForemenPermissionEvaluator SHALL reload that role's Permission_Set from the database on the next evaluation for that Role_Code.

---

### Requirement 10: Localization of Authorization Messages

**User Story:** As an international user, I want authorization errors in my language, so that the messages are understandable.

#### Acceptance Criteria

1. THE message bundle SHALL define a non-blank `error.access.denied` entry in both the Polish base bundle (`messages.properties`) and the Russian bundle (`messages_ru.properties`).
2. THE message bundle SHALL define a non-blank `error.auth.unauthorized` entry in both the Polish base bundle (`messages.properties`) and the Russian bundle (`messages_ru.properties`).
3. WHEN a ForemenApiException with code `error.access.denied` is translated, THE ForemenControllerAdvice SHALL resolve the message text in the request locale.

---

### Requirement 11: Demonstrative Enforcement Endpoint

**User Story:** As a reviewer, I want at least one annotated endpoint exercised by tests, so that end-to-end enforcement is proven without migrating existing controllers.

#### Acceptance Criteria

1. THE spec SHALL provide a single annotated endpoint (a dedicated test-only controller or one demonstrative endpoint) carrying a `@RequiresPermission` declaration to exercise the PermissionInterceptor end to end.
2. WHEN an authenticated request whose role holds the required Permission calls the demonstrative endpoint, THE PermissionInterceptor SHALL allow the request and the endpoint SHALL return its success response.
3. IF an authenticated request whose role lacks the required Permission calls the demonstrative endpoint, THEN THE PermissionInterceptor SHALL produce an HTTP 403 response with code `error.access.denied`.
4. THE spec SHALL NOT add `@RequiresPermission` to the existing production controllers, deferring that migration to FOR-03-08.
