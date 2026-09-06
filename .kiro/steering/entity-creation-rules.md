---
inclusion: always
---

# Standard: New Managed Entity Checklist

When you add a new managed entity to the Foremen backend (a CRUD resource exposed through the admin
panel or the API), you MUST wire it into the ABAC permission matrix and the controller guarding
scheme. Skipping any step below leaves the endpoint either unreachable, unguarded, or startup-broken.

This checklist codifies Requirement 12 of FOR-03-08 (API protection). Follow all applicable steps.

## Checklist

### 1. Add the resource via a seed changeset registered in the changelog

Add the resource row to the `resources` table with a new Liquibase changeset and register it in the
changelog.

- Create `foremen-backend/database_files/changesets/NNN-seed-<entity>-resource.xml` following the
  `009` / `015` / `017-seed-users-resource.xml` pattern.
- Insert the resource row (`code`, `name_ru`, `name_pl`, `description_ru`, `description_pl`).
- Guard the changeset with `<preConditions onFail="MARK_RAN"><sqlCheck expectedResult="0">SELECT
  COUNT(*) FROM resources WHERE code = '<CODE>'</sqlCheck></preConditions>` so re-runs against an
  already-seeded database insert nothing (idempotent).
- Register the file **last in sequence** in `foremen-backend/database_files/changelog.xml`:
  `<include file="database_files/changesets/NNN-seed-<entity>-resource.xml"/>`.

### 2. Add the ADMIN role-matrix grant for the new resource

In the same seed changeset (a second `<changeSet>`), grant the ADMIN role the operations the guarded
endpoints resolve to.

- Insert into `role_resources` (join ADMIN role + the new resource), then insert into
  `role_resource_operations` the operation rows (e.g. `CREATE`, `READ`, `UPDATE`, `DELETE`).
- Guard with the same `onFail="MARK_RAN"` precondition (`SELECT COUNT(*) FROM role_resources ...
  WHERE r.code = 'ADMIN' AND res.code = '<CODE>'`) so re-runs make no duplicate insertions.
- Note: `ForemenPermissionEvaluator` bypasses the matrix for the exact `ADMIN` role code, so the
  ADMIN grant is a consistency/self-describing measure rather than an access gate — but it MUST still
  be seeded so the matrix is complete and non-ADMIN grants can later sit on a real resource row.
- Add grants for any other roles (`MANAGER`, `FOREMAN`, etc.) as the entity's access policy requires.

### 3. Annotate the concrete controller with `@PermissionResource`

Annotate the concrete `@RestController` class with
`@PermissionResource("<CODE>")` (`com.foremen.config.security.PermissionResource`).

- The `value` MUST match the resource `code` seeded in step 1.
- The class-level `@PermissionResource` combines with the `@PermissionOperation` already declared on
  each inherited CRUD `default` method (`AdminController` / `AdminReadOnlyController`) to produce the
  `(resource, operation)` pair the `PermissionInterceptor` enforces — no need to override CRUD
  methods just to attach a permission annotation.
- If a specific endpoint needs a different resource/operation than the class default, put a
  method-level `@RequiresPermission(resource, operation)` on that handler; `@RequiresPermission`
  takes precedence over the class `@PermissionResource` + method `@PermissionOperation` combination.
- Do NOT half-annotate: a controller with `@PermissionResource` whose in-scope handler lacks a
  matching `@PermissionOperation` (or the reverse) **fails application startup** via
  `PermissionAnnotationValidator`. Leave a controller with none of the three annotations only when it
  is intentionally unguarded (self-service / public), e.g. `AuthController`,
  `DisplayPreferencesController`.

### 4. Implement `getProjectIdPath()` for project-scoped entities

If the entity is project-scoped (access depends on the caller's project membership), its service MUST
implement `ProjectScopedService` and override `getProjectIdPath()`
(`com.foremen.service.ProjectScopedService`).

- Return the JPA path from the entity to its owning project id — a single segment (`"projectId"`) or
  a dotted association path (`"project.id"`, `"room.project.id"`).
- This single mandatory per-entity override drives both the automatic project filtering on LIST reads
  and the default `getProjectId(...)` action resolver; you do not need to override the CRUD methods,
  `addRequiredQuery()`, or `getProjectId()` unless a bespoke resolution is required.
- Skip this step only for entities that are NOT project-scoped (global admin resources such as
  `USERS`, `ROLES`, `RESOURCES`, `OPERATIONS`, `AUDIT`).
