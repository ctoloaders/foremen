# Design Document

FOR-04-13: Project entity + project-scoped anchor + custom create + Google Places proxy

## Overview

FOR-04-13 introduces the `Project` — the **project-scoped anchor** of the FOR-04 series — as a
real `projects` table plus its full backend/frontend surface: entity + `ProjectStatus` enum, an
idempotent Liquibase table-creation changeset, a custom transactional creation API, generic
read/update/delete CRUD (create disabled), a **standalone reusable Google Places proxy controller**
(`AddressController`), ABAC `PROJECTS` resource + role grants, and a first admin page on the shared
`DataTable`.

The `projects` list also surfaces the **project team**: `ProjectEntity` carries a read-oriented
`@OneToMany` `members` collection (mapped from the existing FOR-03-04 `project_members` table), the
list/read DTO exposes those members, the list renders them in a single custom `DataTable` cell, and
the members collection is filterable through the FOR-04-01 nested reference-filter mechanism
(`members.user.id~in~[...]`, `members.projectRole.code==CLIENT`). A dedicated **Client column** —
derived from the member whose project role is `CLIENT` — carries its own independent filter (a
compound `members.user.id~in~[...] AND members.projectRole.code==CLIENT` predicate). The `projects`
table itself still holds **no** `client_id`/`manager_id` column: the client is always resolved from
`project_members`, never from a column.

`Project` is an **operational** entity (real-world proper names), so it uses `name`/`address`
fields rather than the `nameRU`/`namePL` i18n pair used by dictionary/reference entities. The
`projects` table carries **descriptive attributes only**: `name`, `address`, `googlePlaceId`,
`formattedAddress`, `latitude`, `longitude`, `area`, `startDate`, `endDate`, `status`. It holds
**no** `client_id`/`manager_id` column and **no** FK to `users`. The whole project team — client
included — lives in the `project_members` join table (FOR-03-04): `(user_id, project_id,
project_role_id → roles)`. "Own projects" and "the project's client" are resolved from
`project_members`, never from `projects`.

Two behaviors distinguish `Project` from the other FOR-04 entities:

1. **Custom transactional create.** The generic `AdminController` create is overridden to be
   unsupported. Projects are created only through `POST /api/projects` accepting a
   `CreateProjectRequest` (base fields + `members[]` + optional `client` block), doing all work
   in one transaction: create project → assign each member via `ProjectMemberService.assign(...)`
   → process the `client` block (existing CLIENT user, or a new INVITED CLIENT user through the
   reused FOR-03-05 client-registration flow). Any failure rolls back the whole unit.

2. **Project-scoped anchor.** `ProjectService implements ProjectScopedService` and
   `getProjectIdPath()` returns `"id"` (the project's own id), so non-ADMIN LIST reads are
   automatically filtered to the caller's `project_members` rows and by-id reads/writes assert
   membership; ADMIN bypasses both the ABAC matrix and membership filtering.

The backend also proxies the Google Places Autocomplete/Details APIs with a **server-side** key
so the key never reaches the browser; the project form uses these proxies to capture
`googlePlaceId`/`formattedAddress`/`latitude`/`longitude`. Because address lookup is **cross-cutting**
(procurement and other future features also capture addresses), the proxy lives in a dedicated
`AddressController` at the neutral path `/api/addresses/*`, guarded by an **authenticated-any-user**
rule (not tied to `PROJECTS`/`READ`), so it is reusable without granting `PROJECTS` access.

**Out of scope** (deferred to FOR-05): the full creation wizard and the dedicated project menu
with tabs (rooms, work lists, schedules). This spec delivers a simple (non-wizard) create form
and a basic list/edit/delete page. The `/projects` menu entry arrives in FOR-04-15; until then an
interim `PROJECTS`/`READ` route guard protects `/projects`.

### Research summary

Findings that inform the design, gathered by reading the existing codebase:

- **CRUD contracts.** `AdminController<...>` (interface with default REST methods) is implemented
  by a thin `@RestController` (e.g. `WorkPriceController`) that supplies `getMapper()` +
  `getService()`. Each default method carries a method-level `@PermissionOperation("CREATE"|
  "READ"|"UPDATE"|"DELETE")`, combined with a class-level `@PermissionResource(...)`.
- **Project scoping (FOR-03-04 / FOR-03-04a).** `ProjectScopedService<...> extends
  AdminService<...>` supplies the list filter (`addRequiredQuery()`) and by-id access assertions
  (`assertProjectAccess`) as `default` methods; the sole mandatory per-entity override is
  `getProjectIdPath()`. ADMIN is detected by exact authority `ADMIN`/`ROLE_ADMIN` and bypasses
  the filter (returns `null`) and the assertion (returns early). `allowedProjectIds(userId)` wires
  to `ProjectAccessCache.get(userId)`. `ScopedFixtureService` is the reference implementation.
- **Members (FOR-03-04).** `ProjectMemberService.assign(userId, projectId, projectRoleId)`
  persists a `project_members` row, rejecting a duplicate `(userId, projectId)` with 409
  `error.project.member.duplicate`, missing user with 404 `error.entity.not.found`, missing role
  with 404 `error.project.role.not.found`, and invalidates `ProjectAccessCache` for the user.
  `ProjectMemberEntity` maps `@ManyToOne user` (`user_id`), `@ManyToOne projectRole` (`project_role_id`),
  and — because the `projects` table did not exist before this spec — a **plain `Long projectId`**
  column (`project_id`) with no association. FOR-04-13 now owns the `projects` table, so the
  `ProjectEntity.members` side is added as a **read-only `@OneToMany` mapped by the `project_id`
  column** (see Data Models); the write path (`ProjectMemberService.assign`) is unchanged and still
  the only way members are created. Nested filter paths therefore reference the member's association
  fields as they are actually named: `members.user.id` and `members.projectRole.code`.
- **Filter grammar & nested joins (FOR-04-01 / FOR-01/02).** `SpecificationBuilder.resolvePath`
  already navigates dot paths and, for a **collection** segment (e.g. `members`), transparently
  creates (and reuses) a JOIN via `getOrCreateJoin` and sets `query.distinct(true)` to collapse the
  duplicate rows a to-many join produces. So `members.user.id~in~1,2` joins `project_members` +
  `users` and `members.projectRole.code==CLIENT` joins `project_members` + `roles` with no new
  engine code. The real operator symbols are `==` (equality) and `~in~` (comma-joined set, no
  parentheses) per `QueryOperator` — the requirements' illustrative `~eq~`/`[...]` read as
  `==` and comma-joined `~in~` on the wire. Multiple filter clauses compose with AND, so a compound
  `members.user.id~in~... AND members.projectRole.code==CLIENT` reuses the same `members` JOIN
  (join reuse keyed by attribute name), correctly meaning "a single member row that is both the
  named user AND has the CLIENT role".
- **Metadata reference descriptors (FOR-04-01).** `EntityMetadataResolver` emits a
  `ReferenceInfo(targetResource, optionsPath, labelField, labelI18n, idPath)` for `@ManyToOne`/
  `@OneToOne` fields, with `idPath = fieldName + ".id"`. It does **not** currently emit descriptors
  for `@OneToMany` collections; exposing the `members` collection's nested reference paths for the
  frontend dropdown is an additive extension described in Components and Interfaces.
- **Client registration (FOR-03-05).** `ClientRegistrationService.register(...)` resolves the
  `CLIENT` role server-side via `RoleDao.findByCode("CLIENT")`, creates an INVITED CLIENT user via
  `UserService.createClient(name, email, phone, locale, clientRole)` (which dispatches the
  FOR-03-02 invitation email through its `afterCreate` hook, and surfaces the established 409
  duplicate-email error), then assigns membership. It runs in a single `@Transactional` unit.
- **BaseEntity audit columns.** `BaseEntity` maps `createdDate`/`createdBy`/`updatedDate`/
  `updatedBy`; the physical columns in create-table changesets are `created_date`, `created_by`,
  `updated_date`, `updated_by` (see `038-create-work-prices.xml`). This design uses those real
  names (the requirements' "created_at/updated_at" wording refers to the four audit columns
  generically).
- **Liquibase idempotency.** Create-table changesets guard with
  `<preConditions onFail="MARK_RAN"><not><tableExists.../></not></preConditions>`; seed changesets
  guard with `<preConditions onFail="MARK_RAN"><sqlCheck expectedResult="0">SELECT COUNT(*)...`.
  Seed files register **last** in `changelog.xml`; the current tail is
  `039-seed-work-prices-resource.xml`, so new files use sequence `040+`.
- **Google config.** Existing `foremen.google.client-id` binds via `GoogleProperties`
  (`@ConfigurationProperties(prefix = "foremen.google")`), intentionally not `@NotBlank`. This
  spec adds a **separate** `foremen.google-places.api-key` namespace that IS fail-fast when the
  feature is enabled. The config + `GooglePlacesService`/`GooglePlacesClient` are unchanged in
  behavior; only the HTTP surface relocates behind the new `AddressController` (they are no longer
  owned by `ProjectController`).
- **Frontend.** A feature folder (`features/work-prices/`) exposes a `*Page` composing a
  DataTable-backed `*List` (columns + `usePermission('RESOURCE','OP')` gating + `fetchFn` adapter),
  a form sheet, and a delete dialog. The API module serializes list params through the shared
  `buildFetchQuery` and calls `apiRequest`. Routes register in `app/router.tsx`; the interim guard
  is a `route-permissions.ts` entry consumed by `PermissionGuard`.
- **Google Places API shape** (external): Autocomplete returns `predictions[]` each with
  `description` + `place_id`; Details returns a `result` with `formatted_address` and
  `geometry.location.{lat,lng}` plus `address_components[]`. Referenced from Google's public Places
  API documentation (https://developers.google.com/maps/documentation/places/web-service/overview).
  Content was rephrased for compliance with licensing restrictions.

## Architecture

```mermaid
flowchart TB
  subgraph FE[Frontend]
    PP[ProjectsPage]
    PL[ProjectsList - DataTable]
    PF[ProjectFormSheet - create/edit]
    GA[GoogleAddressAutocomplete]
    TS[Team multi-select]
    CB[Client block existing/new]
    RG[PermissionGuard + route-permissions]
    PP --> PL
    PP --> PF
    PF --> GA
    PF --> TS
    PF --> CB
  end

  subgraph BE[Backend]
    PC[ProjectController @PermissionResource PROJECTS]
    AC[AddressController /api/addresses - authenticated any user]
    PS[ProjectService implements ProjectScopedService]
    SB[SpecificationBuilder - nested collection joins + distinct]
    PMS[ProjectMemberService FOR-03-04]
    CRS[ClientRegistrationService FOR-03-05]
    GPS[GooglePlacesService + GooglePlacesClient]
    PInt[PermissionInterceptor + ForemenPermissionEvaluator]
  end

  subgraph DB[(PostgreSQL)]
    PT[(projects)]
    PMT[(project_members)]
    UT[(users)]
    RT[(roles)]
    RES[(resources / role_resources / role_resource_operations)]
  end

  GEXT[[Google Places API]]

  PL -- GET /api/projects search/sort/filter --> PInt --> PC --> PS --> SB --> PT
  SB -- members.* nested join + distinct --> PMT
  PMT --> UT
  PMT --> RT
  PF -- POST /api/projects --> PInt --> PC --> PS
  PS --> PMS --> PMT
  PS --> CRS --> UT
  CRS --> PMS
  GA -- GET /api/addresses/* --> PInt --> AC --> GPS --> GEXT
  PC -. reads .-> RES
```

### Request routing and guarding

| Endpoint | Handler | Resolves to | Notes |
|---|---|---|---|
| `POST /api/projects` | `ProjectController.createProject` (custom) | `PROJECTS`/`CREATE` | `@RequiresPermission(resource="PROJECTS", operation="CREATE")`; transactional |
| `POST /api/projects` (generic) | `ProjectService.create` override | — | throws `ForemenApiException` (unsupported) |
| `GET /api/projects` | inherited `find` | `PROJECTS`/`READ` | membership-filtered for non-ADMIN |
| `GET /api/projects/{id}` | inherited `findById` | `PROJECTS`/`READ` | `assertProjectAccess` |
| `PUT /api/projects/{id}` | inherited `update` | `PROJECTS`/`UPDATE` | `assertProjectAccess` |
| `DELETE /api/projects/{id}` | inherited `deleteById` | `PROJECTS`/`DELETE` | `assertProjectAccess`; ADMIN-only grant |
| `GET /api/addresses/autocomplete` | `AddressController.autocomplete` | authenticated (any role) | not tied to `PROJECTS`; reusable |
| `GET /api/addresses/details` | `AddressController.details` | authenticated (any role) | not tied to `PROJECTS`; reusable |

Because `POST /api/projects` collides on the `AdminController.create` default mapping, the controller
does **not** implement the generic create as a live endpoint: the class-level `@PermissionResource`
+ each inherited method's `@PermissionOperation` still satisfy `PermissionAnnotationValidator`, while
the custom `createProject` handler declares its own `@RequiresPermission` (which takes precedence).
The generic create is disabled at the **service** layer (override throws), so even bulk-create is
rejected before any persistence.

### Address proxy guarding decision (Change 1)

The Google Places proxy is **cross-cutting**: address capture matters to procurement and other
future features, so binding it to `PROJECTS`/`READ` (as an earlier draft did) would force unrelated
callers to hold `PROJECTS` access. It is therefore extracted into a standalone `AddressController`
mapped at the neutral `/api/addresses/*` and guarded by an **authenticated-any-user** rule rather
than an ABAC resource operation.

- **Chosen approach:** `AddressController` carries **none** of the three ABAC annotations
  (`@PermissionResource` / `@PermissionOperation` / `@RequiresPermission`). Per the New Managed
  Entity Checklist, a controller with none of the three is treated as **intentionally unguarded by
  ABAC** (like `AuthController` / `DisplayPreferencesController`) and does not trip
  `PermissionAnnotationValidator`. Access is instead gated by the existing authentication layer
  (JWT filter / `authorizeHttpRequests`): `/api/addresses/**` requires an authenticated principal
  but no specific resource grant. An anonymous request is rejected 401 by the security chain.
- **Rationale:** address lookup exposes no project data — only Google predictions/geometry keyed by
  a free-text query or `placeId`. Any authenticated user of the app may resolve an address; there is
  no per-project scoping to enforce. Keeping it out of the ABAC matrix avoids coupling every future
  address-consuming feature to `PROJECTS` grants and matches the codebase pattern for self-service /
  cross-cutting endpoints that are auth-gated but not resource-gated.
- **Alternative considered:** a dedicated lightweight `ADDRESSES` resource with its own READ grant
  seeded for every role. Rejected for this spec because it adds a resource + per-role grants purely
  to express "any authenticated user", with no differentiated access to model; the authenticated
  guard captures the same intent with less seed surface. A dedicated resource can be introduced
  later if a feature needs to withhold address lookup from some roles.

**Requirements divergence (recorded).** This supersedes the original Requirement 5 / 6.1 / 8.4
wording that placed the proxy at `/api/projects/address/*` under `PROJECTS`/`READ`. The functional
behavior of Requirement 5 (autocomplete/details shapes, server-side key, fail-fast config, key never
exposed, server-side details resolution on create/update — 5.1–5.5, 5.7) is unchanged; only the
**path** (`/api/addresses/*`) and the **guard** (authenticated-any-user instead of `PROJECTS`/`READ`,
so 5.6 is relaxed to "any authenticated caller") change. The `PROJECTS` ABAC resource + grants
(Req 6) are unaffected because the proxy no longer resolves to `PROJECTS`. The frontend contract of
Req 8.4 stands except the address component now calls `/api/addresses/*`.

## Components and Interfaces

### Backend

**`ProjectStatus`** (enum): `DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED` in exactly that order.

**`ProjectEntity extends BaseEntity`** — mapped to `projects`; descriptive columns only, no user FK
(see Data Models). `status` is `@Enumerated(EnumType.STRING)`, `nullable = false`. Adds a
**read-oriented** `@OneToMany` `members` collection mapped from `project_members` by its `project_id`
column (no owning side, `insertable=false`), used only for LIST/read projection and nested filtering
— the collection is never mutated through `ProjectEntity` (writes stay in `ProjectMemberService.assign`).

**`ProjectDao extends AdminDao<ProjectEntity, Long>`** — standard JPA/Specification DAO.

**`ProjectService implements ProjectScopedService<ProjectServiceModel, ProjectServiceExtendedModel,
ProjectEntity, Long>`** — supplies CRUD plumbing (`getDao`, `getMapper`, `getEntityManager`,
`getDaoModelClass`, `getAuditLogDao`), the anchor override `getProjectIdPath() → "id"`, and
`allowedProjectIds(userId) → projectAccessCache.get(userId)`. Adds two custom concerns:

- `create(...)` override → throws `ForemenApiException(BAD_REQUEST, "error.project.create.unsupported")`
  for both single and bulk generic-create signatures (disables generic create, Req 3.1).
- `createProject(CreateProjectRequest)` (`@Transactional`) — the custom creation orchestrator:
  1. Validate base fields + date range (delegated to bean validation + a `validateDateRange` guard).
  2. Resolve `googlePlaceId` details when needed (Req 5.7) and build/persist the `ProjectEntity`,
     defaulting `status` to `DRAFT` when null (Req 2.3).
  3. For each `members[i]`: `projectMemberService.assign(userId, projectId, projectRoleId)`.
  4. Process `client`: `existingClientUserId` → `projectMemberService.assign(existingId, projectId,
     clientRole.id)`; `newClient` → `clientRegistrationService.register(...)` (creates INVITED CLIENT
     user, dispatches invite email, assigns membership under server-resolved CLIENT role).
  5. Return the persisted project. Any thrown exception rolls back the whole transaction (Req 2.9).

**`ProjectController` (`@RestController @RequestMapping("/api/projects") @PermissionResource("PROJECTS")`)**
implements `AdminController<...>` (inherits list/read/update/delete/count/metadata/i18n) and adds only
the custom create (the address proxy is no longer here — it moved to `AddressController`):

```java
@PostMapping                                   // overrides the generic create mapping
@RequiresPermission(resource = "PROJECTS", operation = "CREATE")
ResponseEntity<ProjectCreateResponse> createProject(@Valid @RequestBody CreateProjectRequest request);
```

The custom `createProject` returns HTTP `201 Created` (Req 2.10).

**`AddressController` (`@RestController @RequestMapping("/api/addresses")`)** — a standalone,
**ABAC-unannotated** controller (authenticated-any-user, see the routing section) wrapping the
relocated Google Places proxy. It depends on `GooglePlacesService` and exposes:

```java
@GetMapping("/autocomplete")
ResponseEntity<List<PlacePredictionDto>> autocomplete(@RequestParam String query);

@GetMapping("/details")
ResponseEntity<PlaceDetailsDto> details(@RequestParam String placeId);
```

It carries none of `@PermissionResource`/`@PermissionOperation`/`@RequiresPermission`, so
`PermissionAnnotationValidator` treats it as intentionally unguarded by ABAC; the security chain
requires an authenticated principal for `/api/addresses/**`. Being resource-agnostic, it is directly
reusable by future features (e.g. procurement) without a `PROJECTS` grant.

**`GooglePlacesProperties`** (`@Validated @ConfigurationProperties("foremen.google-places")`,
record `{ boolean enabled, String apiKey }`). A `@PostConstruct`/`InitializingBean` fail-fast check
throws when `enabled` is true and `apiKey` is blank (Req 5.5), aborting startup.

**`GooglePlacesClient`** (interface) + `RestClientGooglePlacesClient` (production) — wraps the two
Google HTTP calls, injecting the server-side key; the interface is mockable in tests (Req 7.8, 7.9).
**`GooglePlacesService`** normalizes client responses into `PlacePredictionDto {description,
placeId}` and `PlaceDetailsDto {formattedAddress, latitude, longitude, components}` and provides
`resolveDetails(placeId)` used both by `AddressController` (the HTTP proxy) and by
`ProjectService.createProject`/`update` (server-side details resolution, Req 5.7). Neither ever
echoes the key. `GooglePlacesService`/`GooglePlacesClient`/`GooglePlacesProperties` are unchanged by
Change 1 except that the HTTP entry point is now `AddressController` rather than `ProjectController`.

**`ProjectService` LIST projection & member DTO mapping.** The list/read mapper materializes the
`members` collection into `ProjectMemberSummaryDto { userId, userName, roleCode, roleName }` and
derives the `client` field from the single member whose `projectRole.code == CLIENT`. To avoid an
N+1 across the list page, the members (and their `user`/`projectRole`) are batch-loaded — either via
an `@BatchSize` on the `members` collection or an `@EntityGraph`/join-fetch on the list query keyed
by the page's project ids — so one additional bounded query hydrates all members for the page rather
than one query per row. Fetch type stays **LAZY** (the collection is only touched during list/read
projection, never during filtering, which uses SQL joins).

**`SpecificationBuilder` nested-collection handling (reused, not modified).** LIST filtering on
`members.*` reuses the existing `resolvePath` behavior: a `members` segment is a collection, so the
builder creates/reuses a JOIN to `project_members` and sets `query.distinct(true)` to collapse the
duplicate project rows a to-many join yields. Continuing the path (`.user.id`, `.projectRole.code`)
joins `users`/`roles`. Because joins are reused per attribute name, a compound clause combining
`members.user.id~in~...` **and** `members.projectRole.code==CLIENT` shares one `project_members`
JOIN, correctly requiring a single member row satisfying both — which is exactly the Client filter
semantics (Change 3). No new query-engine code is needed; the design only wires these paths through
metadata and the frontend.

**`EntityMetadataResolver` collection reference descriptors (additive, Change 2/3).** So the frontend
filter dropdown can build `members.user.id~in~[...]`, the metadata resolver is extended to emit, for
the `members` `@OneToMany`, the nested reference descriptors for its filterable association leaves:
`members.user.id` (target resource `users`, options endpoint `/api/users`, label = user display
name) and `members.projectRole.code` (target `roles`). This mirrors the existing `ReferenceInfo`
shape but with a collection-qualified `idPath` (`members.user.id` instead of `role.id`). The Client
column advertises a bespoke descriptor whose emitted fragment is the compound
`members.user.id~in~... AND members.projectRole.code==CLIENT` (see frontend).

### Frontend

Feature folder `features/projects/`:

- `ProjectsPage.tsx` — composes `ProjectsList`, `ProjectFormSheet` (create/edit), `DeleteProjectDialog`
  (mirrors `WorkPricesPage`).
- `components/ProjectsList.tsx` — `DataTable<ProjectDto>` with `resource="PROJECTS"`,
  `entityKey="projects"`, columns `name`, `address`/`formattedAddress`, `area`, `startDate`,
  `endDate`, `status` (localized badge via `ProjectStatusBadge`), a **`members` custom cell** (lists
  each member as "User — Role" via `ProjectMembersCell`), and a dedicated **`client` custom cell**
  (renders the CLIENT member's user name via `ProjectClientCell`); gates create/edit/delete with
  `usePermission('PROJECTS', 'CREATE'|'UPDATE'|'DELETE')`; `fetchFn` adapter → `fetchProjects`.
  It wires two independent filter controls (below): a general **members** multi-select filter and a
  separate **client** multi-select filter.
- `components/ProjectMembersCell.tsx` — renders `members: ProjectMemberSummaryDto[]` in a single
  DataTable cell, one line per member formatted `"{userName} — {roleName}"` (roleName localized),
  with a consistent placeholder when the project has no members.
- `components/ProjectClientCell.tsx` — renders the client's `userName` (the member whose
  `roleCode === 'CLIENT'`), placeholder when none.
- `components/ProjectMembersFilter.tsx` — a users multi-select (reusing the FOR-04-01
  `ReferenceFilter` against `/api/users`) that emits `members.user.id~in~<id1,id2,...>` (or
  `members.user.id==<id>` for a single selection). Surfaced as the `members` column filter.
- `components/ProjectClientFilter.tsx` — an **independent** users multi-select whose emitted fragment
  is the compound `members.user.id~in~<...> AND members.projectRole.code==CLIENT` (single selection
  collapses `~in~` to `==`), distinct from the general members filter. Surfaced as the `client`
  column filter.
- `components/ProjectFormSheet.tsx` — create form with base fields (`name`, `area`, `startDate`,
  `endDate`, `status` select), `GoogleAddressAutocomplete`, team multi-select, and the `ClientBlock`.
  Edit form uses the generic update (base fields only; team management deferred, Req 8.6).
- `components/GoogleAddressAutocomplete.tsx` — debounced calls to the **new neutral** proxy
  `/api/addresses/autocomplete`; on selection calls `/api/addresses/details` and
  stores `googlePlaceId`/`formattedAddress`/`latitude`/`longitude` into form state. (Being tied to
  the reusable `/api/addresses/*` surface, this component can later be lifted out of the projects
  feature for reuse.)
- `components/ClientBlock.tsx` — toggle between selecting an existing CLIENT user and adding a new
  client via the reused invite form (`name`, `phone`, `email`, **no** role selector).
- `components/ProjectStatusBadge.tsx` — one localized badge per `ProjectStatus` value.
- `components/DeleteProjectDialog.tsx` — confirm + `useDeleteProject`.
- `api/projects-api.ts` — `fetchProjects` (via `buildFetchQuery`), `fetchProject`, `createProject`
  (custom endpoint), `updateProject`, `deleteProject`.
- `api/address-api.ts` — `autocompleteAddress`, `fetchAddressDetails` calling the reusable
  `/api/addresses/*` endpoints (a standalone module, not project-specific, so procurement can import
  it later).
- `schemas/` + `types/` — zod schema (incl. `endDate >= startDate` refinement, Req 8.8) and DTO types.

Routing: register `path: 'projects'` in `app/router.tsx`; add interim
`'/projects': { resource: 'PROJECTS', operation: 'READ' }` to `route-permissions.ts` (Req 8.14).

## Data Models

### `projects` table (Liquibase `040-create-projects.xml`)

| Column | Type | Constraints |
|---|---|---|
| `id` | `BIGSERIAL` | PK, not null |
| `name` | `VARCHAR(255)` | not null |
| `address` | `VARCHAR(500)` | null |
| `google_place_id` | `VARCHAR(255)` | null |
| `formatted_address` | `VARCHAR(500)` | null |
| `latitude` | `NUMERIC(10,7)` | null |
| `longitude` | `NUMERIC(10,7)` | null |
| `area` | `NUMERIC(12,2)` | null |
| `start_date` | `DATE` | null |
| `end_date` | `DATE` | null |
| `status` | `VARCHAR(20)` | not null, default `'DRAFT'` |
| `created_date` | `TIMESTAMP` | not null, default `NOW()` |
| `created_by` | `VARCHAR(255)` | null |
| `updated_date` | `TIMESTAMP` | null |
| `updated_by` | `VARCHAR(255)` | null |

No `client_id`, no `manager_id`, no FK to `users` (Req 1.5). Guarded by
`<preConditions onFail="MARK_RAN"><not><tableExists tableName="projects"/></not></preConditions>`
(Req 1.6, 1.7).

### `ProjectEntity` field mapping (Req 1.1–1.3)

```java
@Entity @Table(name = "projects")
public class ProjectEntity extends BaseEntity {
    @Column(nullable = false, length = 255) private String name;
    @Column(length = 500) private String address;
    @Column(name = "google_place_id", length = 255) private String googlePlaceId;
    @Column(name = "formatted_address", length = 500) private String formattedAddress;
    @Column(precision = 10, scale = 7) private BigDecimal latitude;
    @Column(precision = 10, scale = 7) private BigDecimal longitude;
    @Column(precision = 12, scale = 2) private BigDecimal area;
    @Column(name = "start_date") private LocalDate startDate;
    @Column(name = "end_date") private LocalDate endDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.DRAFT;

    // Read-only team association (Change 2). project_members has a plain project_id column
    // (no owning FK side there), so this side is mapped by that column and is NOT the write path.
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id",
                insertable = false, updatable = false)
    @BatchSize(size = 100)   // batch-hydrate members across a list page to avoid N+1
    private List<ProjectMemberEntity> members = new ArrayList<>();
}
```

The `members` side is **read-oriented**: it is populated for LIST/read projection and traversed by
`SpecificationBuilder` joins for nested filtering, but never mutated through `ProjectEntity`.
Member creation stays entirely in the custom create transaction via `ProjectMemberService.assign`
(the write path is unchanged). `insertable=false, updatable=false` guarantees Hibernate never writes
this side. On the list query, `@BatchSize` (or an equivalent `@EntityGraph`/join-fetch) bounds member
hydration to one extra query per page rather than one per row. No schema change is made to
`project_members` (`project_id` remains a plain `BIGINT`); this is purely the reverse mapping now
that the `projects` table exists.

### `CreateProjectRequest` (Req 2.2, 2.4, 2.5)

```
CreateProjectRequest {
  name: String            // @NotBlank, 1..255 after trim
  address: String?        // <= 500
  googlePlaceId: String?
  formattedAddress: String?  // <= 500
  latitude: BigDecimal?
  longitude: BigDecimal?
  area: BigDecimal?       // 0.01 .. 999999999.99 when present
  startDate: LocalDate?   // ISO
  endDate: LocalDate?     // ISO; must be >= startDate when both present
  status: ProjectStatus?  // defaults to DRAFT when null
  members: List<ProjectMemberInput>   // { userId: @NotNull Long, projectRoleId: @NotNull Long }
  client: ClientBlock?    // exactly one of existingClientUserId | newClient
}
ClientBlock {
  existingClientUserId: Long?
  newClient: { name: @NotNull, email: @NotNull, phone?: String, locale?: String }?
}  // NO role identifier accepted
```

The update request (`ProjectUpdateRequest`) carries the base fields only (Req 3.3); list/read DTOs
expose `name, address, googlePlaceId, formattedAddress, latitude, longitude, area, startDate,
endDate, status` (Req 3.4), plus (Change 2/3) a `members` collection and a derived `client` summary.
There is still NO client/manager reference **column** on the entity/table — `client` is computed from
`project_members` at projection time (Req 8.1 invariant preserved):

```
ProjectListDto / ProjectReadDto {
  ...base fields (name, address, ..., status)...
  members: List<ProjectMemberSummaryDto>       // Change 2
  client:  ProjectMemberSummaryDto?            // Change 3: the member whose roleCode == CLIENT, else null
}
ProjectMemberSummaryDto {
  userId:   Long
  userName: String        // user's display name
  roleCode: String        // e.g. "CLIENT", "FOREMAN"
  roleName: String        // localized project-role name
}
```

`client` is the single member with `roleCode == "CLIENT"` (one client per project via the FOR-03-05
flow); when a project has no CLIENT member it is null. It is **derived**, not persisted.

### Filter path descriptors (Change 2/3)

The nested-collection filter paths surfaced through metadata / used by the frontend controls:

| Frontend control | Column | Emitted query fragment | Resolves via |
|---|---|---|---|
| `ProjectMembersFilter` (users multi-select) | `members` | `members.user.id~in~<id1,id2,...>` (or `members.user.id==<id>`) | `project_members` JOIN + `users`, `distinct` |
| `ProjectClientFilter` (users multi-select) | `client` | `members.user.id~in~<...> AND members.projectRole.code==CLIENT` | one `project_members` JOIN (shared) + `users` + `roles`, `distinct` |

Both filters run over the same `members` collection but are **independent controls**; the client
filter adds the `members.projectRole.code==CLIENT` conjunct so it matches only projects whose CLIENT
member is among the selected users. Because `SpecificationBuilder` reuses the JOIN per attribute name,
the two conjuncts of the client filter bind to the **same** member row (correct "this user is the
client" semantics), and `query.distinct(true)` removes the duplicate project rows the to-many join
would otherwise produce. `EntityMetadataResolver` advertises `members.user.id` (target `users`) and
`members.projectRole.code` (target `roles`) as the collection's filterable reference leaves.

### ABAC seed (Liquibase `041-seed-projects-resource.xml`, registered last)

- Resource row: `code='PROJECTS'` with non-null `name_ru`/`name_pl`/`description_ru`/`description_pl`
  (Req 6.3), guarded by `sqlCheck expectedResult="0"` on `resources WHERE code='PROJECTS'` (Req 6.6).
- Grants (one guarded `<changeSet>` per role, mirroring `039`): ADMIN = CREATE, READ, UPDATE, DELETE;
  MANAGER = CREATE, READ, UPDATE; FOREMAN/WORKER/FINANCIER/CLIENT = READ. DELETE granted to ADMIN
  only (Req 6.4, 6.5). Registered after `039-seed-work-prices-resource.xml` at sequence `040+`
  (Req 6.7). Note: `ForemenPermissionEvaluator` bypasses the matrix for the exact ADMIN role code,
  so the ADMIN grant is a completeness/self-describing measure per the New Managed Entity Checklist.

### Google Places DTOs

```
PlacePredictionDto { description: String, placeId: String }        // Req 5.1
PlaceDetailsDto   { formattedAddress: String, latitude: BigDecimal, longitude: BigDecimal,
                    components: List<AddressComponentDto> }         // Req 5.2
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a
system — essentially, a formal statement about what the system should do. Properties serve as the
bridge between human-readable specifications and machine-verifiable correctness guarantees.*

These properties apply to the pure resolution and validation logic of the custom create path, to
the membership-filter decision, and to the nested member/client filter predicate over generated
membership graphs (Properties 7–8, testing the composition of query paths into the correct
project set, executed against an in-memory/Testcontainers DB with generated data). They are NOT
applied to Liquibase migrations/seed (infrastructure), the Google Places proxy (external service,
mocked), config fail-fast (startup), or UI rendering (component/snapshot tests) — those are covered
by integration/smoke/example tests in the Testing Strategy. The generic ADMIN-bypass and non-member-denial decisions reuse the
`ProjectScopedService` logic already property-tested in FOR-03-04/04a, so they are re-confirmed here
by integration tests rather than duplicated as properties.

### Property 1: Status defaults to DRAFT

*For any* `CreateProjectRequest`, the persisted project's `status` equals the request's `status`
when one of the five `ProjectStatus` values is supplied, and equals `DRAFT` when the request's
`status` is null or omitted.

**Validates: Requirements 2.3**

### Property 2: Project creation is all-or-nothing

*For any* `CreateProjectRequest` whose processing fails at any step (unknown member `userId`/
`projectRoleId`, invalid role, duplicate `(userId, projectId)`, duplicate `newClient` email, or a
required Google resolution failure), the post-state contains no new `projects` row, no new
`project_members` row, and no new client `users` row relative to the pre-state; and for any request
that succeeds, exactly the project plus all its member rows and its client membership persist.

**Validates: Requirements 2.6, 2.9**

### Property 3: The client is always assigned under the server-resolved CLIENT role

*For any* `client` block (whether `existingClientUserId` or `newClient`, and regardless of any data
the caller supplies in it), the resulting client `project_members` row references the role whose
`code` is `CLIENT`, resolved server-side; no caller-supplied field can change the client's project
role.

**Validates: Requirements 2.7, 2.8**

### Property 4: Invalid base fields are rejected and nothing persists

*For any* create or update request whose `name` is null/blank/whitespace-only or exceeds 255
characters, or whose `area` is present and outside the range 0.01 to 999999999.99, or whose `status`
is not one of the five defined `ProjectStatus` values, the system rejects the request with a
client-error response identifying the invalid field and persists no changes.

**Validates: Requirements 3.5**

### Property 5: End date must not precede start date

*For any* create or update request that supplies both `startDate` and `endDate`, the request is
rejected with a client-error indicating the invalid date range if and only if `endDate` is earlier
than `startDate`; requests where `endDate >= startDate`, or where either date is null, pass this
check.

**Validates: Requirements 3.6, 8.8**

### Property 6: Non-ADMIN list returns exactly the caller's membership set

*For any* set of users, projects, and `project_members` rows, when a non-ADMIN caller requests a
LIST read of `/api/projects`, the set of returned project ids equals exactly the set of project ids
for which a `project_members` row joins the caller to the project — and is the empty collection
(never an error) when the caller has zero memberships.

**Validates: Requirements 4.2, 4.4**

### Property 7: Member filter returns exactly the projects having a matching member

*For any* set of projects, users, and `project_members` rows, and any non-empty set `U` of selected
user ids, a LIST read filtered by `members.user.id~in~U` returns exactly the set of projects for
which at least one `project_members` row joins that project to some user in `U`, with no project
appearing more than once (duplicate rows from the to-many join are collapsed).

**Validates: Requirements 8.2**

### Property 8: Client filter returns exactly the projects whose CLIENT member matches

*For any* set of projects, users, roles (including the seeded `CLIENT` role), and `project_members`
rows, and any non-empty set `U` of selected user ids, a LIST read filtered by the compound client
predicate (`members.user.id~in~U AND members.projectRole.code==CLIENT`) returns exactly the set of
projects that have a `project_members` row whose user is in `U` **and** whose `projectRole.code`
equals `CLIENT` — i.e. projects whose client is one of the selected users — with each qualifying
project appearing exactly once, and excludes projects where the selected users are non-CLIENT members
only.

**Validates: Requirements 8.1, 8.2**

## Error Handling

| Condition | Status | Message code | Requirement |
|---|---|---|---|
| Generic create invoked (single/bulk) | 400 | `error.project.create.unsupported` | 3.1 |
| `name` blank/whitespace or > 255 | 400 | `error.validation` (field: name) | 3.5 |
| `area` outside 0.01..999999999.99 | 400 | `error.validation` (field: area) | 3.5 |
| `status` not a defined value | 400 | `error.validation` (field: status) | 3.5 |
| `endDate` earlier than `startDate` | 400 | `error.project.date.range` | 3.6, 8.8 |
| `members[i].userId` not found | 404 | `error.entity.not.found` | 3.7 |
| `members[i].projectRoleId` not found | 404 | `error.project.role.not.found` | 3.7 |
| Duplicate `(userId, projectId)` (member or client) | 409 | `error.project.member.duplicate` | 3.8 |
| `newClient.email` already exists | 409 | `error.user.email.already.exists` | 3.9 |
| CLIENT role not seeded | 500 | `error.role.client.missing` | 2.8 |
| Non-member by-id read/update/delete | 404 | `error.entity.not.found` (indistinguishable) | 4.5 |
| Google Places key blank while enabled | startup failure | config error | 5.5 |

- Every failure inside `createProject` propagates so the class-level `@Transactional` rolls the whole
  unit back (Req 2.9). The existing exception advice maps `ForemenApiException` to the standard error
  body; no new advice is introduced.
- The non-member by-id decision deliberately returns 404 `error.entity.not.found` (via
  `ProjectScopedService.assertProjectAccess`) so an out-of-scope project is indistinguishable from a
  missing one (Req 4.5).
- The Google proxy (now served by `AddressController` at `/api/addresses/*`) never includes the API
  key in any response or error body (Req 5.3); client/HTTP failures surface as a generic upstream
  error without leaking the key. An anonymous call to `/api/addresses/**` is rejected 401 by the
  security chain (authenticated-any-user guard, Change 1).
- Nested member/client filters navigate `project_members`; an unknown path segment still surfaces as
  `error.query.invalid.field.path` (400) from `SpecificationBuilder`, unchanged.

## Testing Strategy

Per the workspace test-execution standard, tests select only the affected classes with `--tests`
(never the full suite), redirect output to a temp log, and read the JUnit XML for pass/fail. The
full `./gradlew build` runs only on explicit request.

**Dual approach:** property tests cover the universal create/validation/scoping logic (Properties
1–6); unit/example tests cover concrete shapes and edge cases; integration (Testcontainers) tests
cover CRUD, scoping end-to-end, the Google proxy (mocked client), and the Liquibase seed; smoke
tests cover config fail-fast and clean startup.

### Property-based tests (backend)

- Library: **jqwik** (already used in the backend, per existing `service/property/*PropertyTest`
  classes and the `.jqwik-database`). Do NOT hand-roll property testing.
- Minimum **100 iterations** per property test.
- Each test is tagged with a comment: `Feature: FOR-04-13-project, Property {n}: {property text}`.
- One property-based test per correctness property:
  - P1 → `ProjectCreateStatusDefaultPropertyTest`
  - P2 → `ProjectCreateAtomicityPropertyTest` (failure-injection over generated failing steps)
  - P3 → `ProjectClientRolePropertyTest`
  - P4 → `ProjectFieldValidationPropertyTest`
  - P5 → `ProjectDateRangePropertyTest`
  - P6 → `ProjectMembershipListFilterPropertyTest` (generated membership graphs)
  - P7 → `ProjectMemberFilterPropertyTest` (generated membership graphs; `members.user.id~in~U`
    returns exactly the projects with a matching member, each once)
  - P8 → `ProjectClientFilterPropertyTest` (generated graphs incl. CLIENT and non-CLIENT roles; the
    compound client predicate returns exactly the projects whose CLIENT member is in `U`)

### Unit / example tests (backend)

- `ProjectStatusEnumTest` — `values()` equals the five values in order (Req 1.3).
- `GooglePlacesServiceTest` — normalization of a mocked client response into `PlacePredictionDto`/
  `PlaceDetailsDto`; asserts the key never appears in outputs (Req 5.1–5.3).

### Integration tests (Testcontainers, backend — Req 7)

- `ProjectCustomCreateIT` — 7.1 (existing client), 7.2 (newClient → INVITED, invite dispatched),
  7.3 (failure rolls back), 7.4 (generic create rejected, nothing persisted).
- `ProjectScopingIT` — 7.5 (non-ADMIN LIST membership-filtered), plus ADMIN bypass (Req 4.3) and
  non-member by-id denial (Req 4.5).
- `ProjectCrudIT` — 7.6 (read/update/delete), 7.7 (filter on `status`).
- `ProjectMemberFilterIT` — nested member filter end-to-end: `GET /api/projects?query=members.user.id~in~<ids>`
  returns exactly the projects having a matching member, deduplicated (Change 2); and the compound
  client filter `GET /api/projects?query=members.user.id~in~<ids> AND members.projectRole.code==CLIENT`
  returns exactly the projects whose CLIENT member matches, excluding projects where the selected
  users are non-CLIENT members (Change 3). Also asserts the list/read DTO exposes the `members`
  collection and the derived `client` summary.
- `AddressProxyIT` — the relocated Google proxy against **`/api/addresses/*`**: 7.8 (autocomplete
  shape), 7.9 (details shape), both with a mocked `GooglePlacesClient`, asserting the key is absent
  from responses; asserts an authenticated non-`PROJECTS` caller can reach the endpoints (reusable,
  not tied to `PROJECTS`/`READ`) while an anonymous caller gets 401. Server-side details resolution
  on create/update (Req 5.7) is exercised via `ProjectService`/`ProjectCustomCreateIT`.
- `ProjectsSeedIT` — 7.11 (exactly one `PROJECTS` resource), 7.12 (exact grant matrix, DELETE=ADMIN
  only), 7.13 (idempotent re-apply leaves counts unchanged); plus the create-table migration (Req
  1.4–1.7).

### Smoke tests (backend)

- `GooglePlacesConfigStartupTest` — context fails to start when the feature is enabled and the key
  is blank (Req 5.5, 7.10).
- Clean-startup coverage confirms the fully annotated `ProjectController` does not trip
  `PermissionAnnotationValidator`, and that the intentionally ABAC-unannotated `AddressController`
  (none of the three annotations) also starts cleanly (Req 6.2, Change 1).

### Frontend tests (Req 9) — Vitest + React Testing Library, run with `--run`

- `ProjectFormSheet.test.tsx` — Google autocomplete calls the mocked **`/api/addresses/*`** proxy and
  stores `googlePlaceId`/`formattedAddress`/`latitude`/`longitude`; a team member can be selected;
  the Client block supports existing-client and new-client cases (Req 9.1).
- `ProjectsList.test.tsx` — non-empty result renders `name`/`address`/`area`/`startDate`/`endDate`/
  `status`, row count equals record count (Req 9.2); each of the five statuses renders its localized
  badge (Req 9.3); gated controls appear/hide with permission (Req 9.4); empty result shows the
  empty state with zero data rows (Req 9.5).
- `ProjectMembersCell.test.tsx` / `ProjectClientCell.test.tsx` — the members cell renders one
  `"{userName} — {roleName}"` line per member (and a placeholder when empty); the client cell renders
  only the CLIENT member's name (placeholder when none) (Change 2/3).
- `ProjectMembersFilter.test.tsx` — selecting users emits `members.user.id~in~<ids>` (single selection
  collapses to `members.user.id==<id>`) and composes into the list fetch query.
- `ProjectClientFilter.test.tsx` — selecting users emits the compound
  `members.user.id~in~<...> AND members.projectRole.code==CLIENT`, independent of the general members
  filter, and both filters can be active simultaneously.
- `projects.i18n.test.ts` — the set of `projects.*` keys is identical in `pl.json` and `ru.json`
  (including the five status labels, the Google address component text, and the Client block text),
  all present with non-empty values, and no raw key is rendered (Req 8.13, 9.6).

## Steering compliance — New Managed Entity Checklist

- **Seed resource + register last:** `041-seed-projects-resource.xml` inserts the `PROJECTS` resource
  (idempotent `MARK_RAN`) and registers last in `changelog.xml` at sequence `040+`.
- **ADMIN role-matrix grant:** the seed grants ADMIN CREATE/READ/UPDATE/DELETE (plus MANAGER and the
  READ roles), guarded idempotently.
- **`@PermissionResource` on the controller:** `@PermissionResource("PROJECTS")` on `ProjectController`,
  with the custom create carrying `@RequiresPermission(...,"CREATE")` and inherited CRUD methods
  keeping their `@PermissionOperation` — complete per `PermissionAnnotationValidator`. The relocated
  Google Places proxy lives on `AddressController`, which intentionally carries none of the three
  ABAC annotations (authenticated-any-user, cross-cutting) — a valid unguarded-by-ABAC controller
  per the checklist, so it neither needs a `PROJECTS` grant nor trips the validator (Change 1).
- **`getProjectIdPath()` for the project-scoped anchor:** `ProjectService implements
  ProjectScopedService` and returns `"id"` (the project's own id), driving automatic
  `project_members` LIST filtering and by-id access assertions.
