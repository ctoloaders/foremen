# Requirements Document

FOR-04-13: Project (Project) entity + project-scoped anchor + custom create + Google Places proxy

## Introduction

FOR-04-13 adds the `Project` entity — the PROJECT-SCOPED ANCHOR of the FOR-04
base-entities series — backed by a real `projects` table, plus its ABAC wiring, a
custom project-creation API, backend Google Places proxy endpoints, and a first
frontend admin page on the shared DataTable. This spec creates the real
`projects` table. Before FOR-04, a `projects` reference elsewhere in the schema
was a bare `BIGINT` with no foreign key; FOR-04-13 introduces the actual table
that project-scoped access resolves against.

`Project` is an operational entity (real-world proper names), so it uses `name` /
`address` fields rather than the `nameRU`/`namePL` i18n pair used by
dictionary/reference tables. The project itself does NOT store its client or
manager as columns or foreign keys. The entire project team — including the
client — is modeled through the `project_members` join table (FOR-03-04):
`(user_id, project_id, project_role_id → roles)`. "Own projects" and "the
project's client" are resolved from `project_members`, not from the `projects`
table. The `projects` table carries only descriptive attributes: `name`,
`address`, `googlePlaceId`, `formattedAddress`, `latitude`, `longitude`, `area`,
`startDate`, `endDate`, and a `status` enum (`ProjectStatus`: `DRAFT`, `ACTIVE`,
`ON_HOLD`, `COMPLETED`, `CANCELLED`; default `DRAFT`).

Project creation is a custom operation. The generic CRUD create inherited from
`AdminController` is overridden to be unsupported — projects are created only
through a dedicated custom endpoint that accepts the base project fields, a
team-member list, and an optional client block, and performs all work in a single
transaction. Read, list, update, and delete remain generic CRUD (still
project-scoped).

The backend also exposes two Google Places proxy endpoints (autocomplete and
details) called with a SERVER-side API key so the key never reaches the browser.
The frontend project form uses these proxies to capture `googlePlaceId`,
`formattedAddress`, `latitude`, and `longitude` for an address.

As the project-scoped anchor, `ProjectService` implements `ProjectScopedService`
and returns `"id"` from `getProjectIdPath()` (the project's own id), driving
automatic `project_members` filtering on LIST reads for non-ADMIN roles
(per FOR-03-04).

Access follows the FOR-04 matrix: ADMIN full CRUD; MANAGER CREATE/READ/UPDATE
(own, no DELETE); FOREMAN/WORKER/FINANCIER/CLIENT READ (own); DELETE is
ADMIN-only. "(own)" = automatic `project_members` filtering. Menu entry stays
with FOR-04-15; interim route guard until then.

Explicitly OUT OF SCOPE: the full project creation wizard and the dedicated
project menu with tabs (general info, rooms with dimensions, work lists, team,
schedules) belong to FOR-05. This spec delivers only a simple (non-wizard)
create form and a basic admin list/edit/delete page.

Depends on FOR-04-01 (table-reference-filter), FOR-03-04 (`project_members` +
`ProjectMemberService`), FOR-03-05 (client OTP registration / client-portal
invite flow), FOR-03-02 (invitation email), and the existing `users` and `roles`
tables.

## Glossary

- **Project**: The operational entity introduced by this spec, backed by the `projects` table. Uses real-world proper-name fields (`name`, `address`) rather than the `nameRU`/`namePL` i18n pair of dictionary/reference tables. The entity holds no client or manager foreign key.
- **ProjectStatus**: The Java enum for a project's lifecycle state, persisted as a string. Allowed values: `DRAFT`, `ACTIVE`, `ON_HOLD`, `COMPLETED`, `CANCELLED`; default `DRAFT`.
- **Project-scoped anchor**: The entity whose own id defines the project boundary for ABAC filtering. `ProjectService` is the anchor because `getProjectIdPath()` returns `"id"` (the project's own id), so project-scoped filtering resolves against the `projects` table itself.
- **project_members**: The join table (from FOR-03-04) linking users to the projects they belong to, with columns `(user_id, project_id, project_role_id)`. It is the sole source of a project's team and client, and it drives automatic filtering of LIST reads to the projects a non-ADMIN caller is a member of.
- **ProjectMemberService**: The existing FOR-03-04 service providing `assign(userId, projectId, projectRoleId)`, which persists a `project_members` row and rejects a duplicate `(userId, projectId)` with HTTP 409 `error.project.member.duplicate`.
- **CreateProjectRequest**: The request body of the custom project-creation endpoint, carrying the base project fields, a `members` array, and an optional `client` block.
- **Project_Member_Input**: An element of the `CreateProjectRequest` `members` array, an object `{ userId, projectRoleId }` naming a team member and the role under which the member is assigned to the project. By default `projectRoleId` equals the user's company role.
- **Client_Block**: The optional `client` section of the `CreateProjectRequest`, either `existingClientUserId` (a Long referencing an existing CLIENT user) or `newClient` (`{ name, email, phone?, locale? }` describing a client to create).
- **CLIENT_Role**: The seeded `RoleEntity` whose `code` equals `CLIENT`, resolved by the server (never supplied by the caller) and used as the project role for the client member.
- **Client-registration flow**: The FOR-03-05 logic that creates a CLIENT user (status `INVITED`, no password), dispatches the FOR-03-02 client-portal invitation email, and attaches the user to a project via `ProjectMemberService`. This spec reuses that flow for the `newClient` case.
- **Google Places proxy**: The pair of backend endpoints (`autocomplete`, `details`) that call the Google Places API using a server-side API key and return normalized results, so the key is never exposed to the frontend.
- **Google_Places_API_Key**: The server-side Google API key, read from configuration property `foremen.google-places.api-key` (environment variable `GOOGLE_PLACES_API_KEY`), used by the Google Places proxy endpoints.
- **googlePlaceId**: The Google-assigned place identifier (`place_id`), persisted in column `google_place_id`, used for future map work and for resolving canonical address details.
- **formattedAddress**: The canonical Google-formatted address string, persisted in column `formatted_address`.
- **Reference filter**: The query grammar (from FOR-04-01) that supports filtering by entity attributes via the `SpecificationBuilder`, and emits reference descriptors through `EntityMetadataResolver`.
- **ABAC resource**: A named permission subject (here `PROJECTS`) declared via `@PermissionResource` and seeded as a row in the resources table, against which role grants are checked.
- **ABAC grant**: A role-to-operation permission on a resource (CREATE/READ/UPDATE/DELETE) seeded for a given role on the `PROJECTS` resource.
- **own / (own) filtering**: Automatic restriction of a caller's visible rows to the projects the caller belongs to via `project_members`. "(own)" in the access matrix denotes this membership-scoped filtering for non-ADMIN roles.

## Requirements

### Requirement 1: Project entity & table

**User Story:** As a backend developer, I want a `Project` entity mapped to a real `projects` table with descriptive attributes and no client/manager foreign keys, so that project-scoped access has a concrete table to resolve against while the team is modeled through `project_members`.

#### Acceptance Criteria

1. THE system SHALL map a `ProjectEntity` extending `BaseEntity` with the following fields: `name` (String, NOT NULL, maximum length 255 characters), `address` (String, nullable, maximum length 500 characters), `googlePlaceId` (String, nullable, column `google_place_id`, maximum length 255 characters), `formattedAddress` (String, nullable, column `formatted_address`, maximum length 500 characters), `latitude` (BigDecimal, nullable, column `latitude`, precision 10, scale 7), `longitude` (BigDecimal, nullable, column `longitude`, precision 10, scale 7), `area` (BigDecimal, nullable, precision 12, scale 2, permitted range 0.00 to 9,999,999,999.99), `startDate` (LocalDate, nullable), `endDate` (LocalDate, nullable), and `status` (Java enum `ProjectStatus`, persisted `@Enumerated(EnumType.STRING)`, NOT NULL, default value `DRAFT`).
2. THE `ProjectEntity` SHALL define no `client` field, no `manager` field, and no `@ManyToOne` association to `UserEntity`.
3. THE `ProjectStatus` enum SHALL define exactly the five values `DRAFT`, `ACTIVE`, `ON_HOLD`, `COMPLETED`, and `CANCELLED`, in that declaration order, and no other values.
4. THE `projects` table SHALL define the columns `name VARCHAR(255) NOT NULL`, `address VARCHAR(500) NULL`, `google_place_id VARCHAR(255) NULL`, `formatted_address VARCHAR(500) NULL`, `latitude NUMERIC(10,7) NULL`, `longitude NUMERIC(10,7) NULL`, `area NUMERIC(12,2) NULL`, `start_date DATE NULL`, `end_date DATE NULL`, `status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'`, plus the four `BaseEntity` audit columns `created_at`, `created_by`, `updated_at`, and `updated_by`.
5. THE `projects` table SHALL define no `client_id` column, no `manager_id` column, and no foreign key constraint referencing the `users` table.
6. WHEN the table-creation changeset runs against a database that does not contain the `projects` table, THE system SHALL create the `projects` table with all columns defined in criterion 4.
7. IF the table-creation changeset runs against a database that already contains the `projects` table, THEN THE system SHALL mark the changeset as run (`MARK_RAN` via a `tableExists` precondition) and SHALL make no schema changes.

### Requirement 2: Custom project-creation API

**User Story:** As a project manager, I want a single custom endpoint that creates a project together with its team and its client, so that I can set up a project and its membership in one transactional call.

#### Acceptance Criteria

1. THE system SHALL expose a custom project-creation endpoint `POST /api/projects` that accepts a `CreateProjectRequest` and resolves to the `PROJECTS`/`CREATE` permission (via `@RequiresPermission` or the `@PermissionResource` + `@PermissionOperation` combination).
2. THE `CreateProjectRequest` SHALL accept the base project fields `name` (`@NotNull`, 1 to 255 characters after trimming), `address` (nullable, at most 500 characters), `googlePlaceId` (nullable), `formattedAddress` (nullable, at most 500 characters), `latitude` (nullable BigDecimal), `longitude` (nullable BigDecimal), `area` (nullable BigDecimal in the range 0.01 to 999999999.99), `startDate` (nullable ISO date), `endDate` (nullable ISO date), and `status` (nullable; one of the defined `ProjectStatus` values).
3. WHEN `status` is omitted or null in the request, THE system SHALL default the persisted status to `DRAFT`.
4. THE `CreateProjectRequest` SHALL accept a `members` array of `Project_Member_Input` objects, each carrying `userId` (`@NotNull Long`) and `projectRoleId` (`@NotNull Long`), where each member is assigned to the project under the supplied `projectRoleId` and, by default, `projectRoleId` equals the user's company role.
5. THE `CreateProjectRequest` SHALL accept an optional `client` block that is EITHER `existingClientUserId` (a Long referencing an existing CLIENT user) OR `newClient` (`{ name (@NotNull), email (@NotNull), phone (optional), locale (optional) }`), and SHALL NOT accept a role identifier for the client.
6. WHEN a valid `CreateProjectRequest` is received, THE service SHALL, within a single transaction, create the project, then assign each `members` entry via `ProjectMemberService.assign(userId, projectId, projectRoleId)`, then process the `client` block if present.
7. WHERE the `client` block supplies `existingClientUserId`, THE service SHALL assign that existing CLIENT user to the created project under the server-resolved CLIENT project role via `ProjectMemberService.assign(...)`.
8. WHERE the `client` block supplies `newClient`, THE service SHALL create a CLIENT user (status `INVITED`, no password), dispatch the FOR-03-02 client-portal invitation email, and assign the created user to the project under the server-resolved CLIENT project role, reusing the FOR-03-05 client-registration flow, with the role fixed by the server to `CLIENT`.
9. IF any step of the creation transaction fails (unknown member, invalid role, duplicate membership, duplicate client email, or Google resolution failure when required), THEN THE system SHALL roll back the entire transaction so that no project, no `project_members` row, and no client user remains, and SHALL return an error response indicating the failing condition.
10. WHEN project creation succeeds, THE endpoint SHALL return the created project including its generated id and its persisted `status`, with success status HTTP 201.

### Requirement 3: Generic create overridden, generic read/update/delete retained

**User Story:** As an API consumer, I want the generic create disabled while list, read, update, and delete stay available, so that projects are created only through the custom endpoint but still managed through standard CRUD.

#### Acceptance Criteria

1. THE `ProjectService` (or `ProjectController`) SHALL override the generic `AdminController` create operation to be unsupported, and WHEN the generic create path is invoked, THE system SHALL reject the request by throwing a `ForemenApiException` carrying a client-error status and a message code indicating that generic project creation is not supported.
2. THE system SHALL retain the generic `AdminController` list, read, update, and delete operations for projects at `/api/projects`, each subject to the project-scoping rules of Requirement 4.
3. THE update request SHALL accept the base project fields `name` (non-null, 1 to 255 characters after trimming), `address` (nullable, at most 500 characters), `googlePlaceId` (nullable), `formattedAddress` (nullable, at most 500 characters), `latitude` (nullable), `longitude` (nullable), `area` (nullable, range 0.01 to 999999999.99), `startDate` (nullable), `endDate` (nullable), and `status` (one of the defined `ProjectStatus` values).
4. THE list/read DTOs SHALL expose `name`, `address`, `googlePlaceId`, `formattedAddress`, `latitude`, `longitude`, `area`, `startDate`, `endDate`, and `status`.
5. IF a create or update request supplies a `status` value that is not one of the defined `ProjectStatus` values, or a `name` that is null/blank or exceeds 255 characters, or an `area` outside the range 0.01 to 999999999.99, THEN THE system SHALL reject the request, return a client-error response identifying the invalid field, and persist no changes.
6. IF a create or update request supplies both `startDate` and `endDate` and `endDate` is earlier than `startDate`, THEN THE system SHALL reject the request, return a client-error response indicating the invalid date range, and persist no changes.
7. IF a `members` entry references a `userId` or `projectRoleId` that does not reference an existing row, THEN THE system SHALL reject the request with a client-error response identifying the offending field, and persist no changes.
8. IF a `members` entry, or the client assignment, produces a duplicate `(userId, projectId)`, THEN THE system SHALL surface the `ProjectMemberService` duplicate outcome (HTTP 409 `error.project.member.duplicate`) and roll back the transaction.
9. IF the `newClient` email already belongs to an existing user, THEN THE system SHALL reject the request per the existing user-creation uniqueness behavior (HTTP 409 duplicate-email error) and roll back the transaction.

### Requirement 4: Project-scoped anchor

**User Story:** As a non-ADMIN user, I want to see only the projects I belong to, so that project-scoped access is enforced automatically on the projects list.

#### Acceptance Criteria

1. THE `ProjectService` SHALL implement `ProjectScopedService` and override `getProjectIdPath()` to return `"id"` (the project's own id).
2. WHERE the caller holds a non-ADMIN role, WHEN the caller requests a LIST read of `/api/projects`, THE system SHALL return only the set of projects for which a matching row exists in `project_members` joining the caller to the project (per FOR-03-04), and SHALL exclude every project for which no such membership row exists.
3. WHERE the caller holds the ADMIN role, WHEN the caller requests any read or write operation on `/api/projects`, THE system SHALL bypass both the ABAC permission matrix and the `project_members` membership filtering and SHALL return or operate on all projects without membership-based exclusion.
4. WHERE the caller holds a non-ADMIN role AND the caller is a member of zero projects, WHEN the caller requests a LIST read of `/api/projects`, THE system SHALL return a successful response containing an empty collection of zero projects, and SHALL NOT return an error.
5. IF the caller holds a non-ADMIN role AND requests a READ, UPDATE, or DELETE operation on a specific project for which no `project_members` row joins the caller to that project, THEN THE system SHALL deny the operation, SHALL NOT return or modify the target project's data, and SHALL return an access-denied response indicating the caller lacks membership of the requested project.

### Requirement 5: Google Places proxy endpoints

**User Story:** As a frontend developer, I want backend proxy endpoints for Google Places autocomplete and details, so that the project form can search addresses without exposing the Google API key to the browser.

#### Acceptance Criteria

1. THE system SHALL expose `GET /api/projects/address/autocomplete` accepting a `query` parameter and returning a list of predictions, each containing at least a description and a `place_id`, obtained from the Google Places Autocomplete API.
2. THE system SHALL expose `GET /api/projects/address/details` accepting a `placeId` parameter and returning `formattedAddress`, `latitude`, `longitude`, and, where available, minimal address components, obtained from the Google Places Details API.
3. THE Google Places proxy endpoints SHALL call the Google Places API using the server-side Google_Places_API_Key, and SHALL NOT expose the key in any response or to the frontend.
4. THE system SHALL read the Google_Places_API_Key from configuration property `foremen.google-places.api-key` (environment variable `GOOGLE_PLACES_API_KEY`), documented in `application.yml` and `.env.example`.
5. IF the Google Places feature is enabled AND the Google_Places_API_Key is empty, THEN THE system SHALL fail application startup with a clear configuration error (fail-fast).
6. THE autocomplete and details endpoints SHALL resolve to the `PROJECTS`/`READ` permission, so that only callers holding `PROJECTS` READ (or an ADMIN via bypass) may invoke them.
7. WHEN a project is created or updated with a non-null `googlePlaceId` and no supplied `formattedAddress`, `latitude`, or `longitude`, THE system SHALL resolve those values through the Google Places details lookup and persist them on the project.

### Requirement 6: ABAC resource & grants

**User Story:** As a security administrator, I want the `PROJECTS` resource and its role grants seeded, so that access to project endpoints is governed by the ABAC matrix.

#### Acceptance Criteria

1. THE project controller SHALL be annotated with `@PermissionResource("PROJECTS")` whose value exactly matches the seeded `PROJECTS` resource `code`, with the custom create endpoint resolving to `PROJECTS`/`CREATE` and the address proxy endpoints resolving to `PROJECTS`/`READ`.
2. IF the project controller is annotated with `@PermissionResource` but has any in-scope guarded handler lacking a matching `@PermissionOperation` or `@RequiresPermission` (or the reverse), THEN THE application SHALL fail to start during `PermissionAnnotationValidator` validation with an error indicating the incomplete annotation.
3. WHEN the seed changeset runs against a database with no existing `PROJECTS` resource, THE seed SHALL insert exactly one `PROJECTS` resource row populated with non-null `code`, `name_ru`, `name_pl`, `description_ru`, and `description_pl` values.
4. WHEN the seed changeset runs, THE seed SHALL grant the following operations via `role_resources` and `role_resource_operations` for the `PROJECTS` resource: ADMIN = CREATE, READ, UPDATE, DELETE; MANAGER = CREATE, READ, UPDATE; FOREMAN = READ; WORKER = READ; FINANCIER = READ; CLIENT = READ.
5. THE seed SHALL grant the DELETE operation on the `PROJECTS` resource to the ADMIN role only, and to no other role.
6. IF a seed changeset re-runs against a database where its target row(s) already exist, THEN THE changeset SHALL insert zero additional rows, guarded by `<preConditions onFail="MARK_RAN">` with an `<sqlCheck expectedResult="0">` counting the existing row(s).
7. THE new changeset file(s) SHALL be registered last in `changelog.xml`, after `039-seed-work-prices-resource.xml`, using a sequence number of 040 or higher.

### Requirement 7: Backend tests

**User Story:** As a maintainer, I want integration tests covering the custom create, generic CRUD, project-scoping, Google proxy, and seed, so that project behavior and permissions stay verified.

#### Acceptance Criteria

1. WHEN the Testcontainers integration test issues a custom create request to `POST /api/projects` with valid base fields, a `members` list, and a `client` block using `existingClientUserId`, THE System SHALL create the project, assign each team member and the existing client via `project_members`, and return the created project with its generated id and `status`.
2. WHEN the Testcontainers integration test issues a custom create request with a `newClient` block, THE System SHALL create a CLIENT user (status `INVITED`, no password), assign it to the project under the CLIENT role, and return the created project.
3. WHEN the Testcontainers integration test issues a custom create request whose member assignment or client processing fails, THE System SHALL roll back the transaction so that no project, no `project_members` row, and no client user remains.
4. WHEN the Testcontainers integration test invokes the generic create path for a project, THE System SHALL reject the request as not supported and persist no project.
5. WHILE the authenticated caller holds a non-ADMIN role, WHEN the Testcontainers integration test lists `/api/projects`, THE System SHALL return only projects for which the caller has project membership and SHALL exclude all projects for which the caller has no membership.
6. WHEN the Testcontainers integration test issues read, update, and delete requests for an existing project id, THE System SHALL return the project on read, persist the updated fields on update, and remove the project on delete such that a subsequent read indicates the project no longer exists.
7. WHEN the Testcontainers integration test lists `/api/projects` with a filter on `status`, THE System SHALL return only projects whose `status` equals the supplied value.
8. WHEN the Testcontainers integration test calls the Google Places autocomplete proxy with a mocked Google client, THE System SHALL return the expected prediction shape (description plus `place_id`) using the configured key, and SHALL NOT include the key in the response.
9. WHEN the Testcontainers integration test calls the Google Places details proxy with a mocked Google client, THE System SHALL return the expected `formattedAddress`, `latitude`, and `longitude` shape using the configured key.
10. IF the application starts with the Google Places feature enabled and an empty Google_Places_API_Key, THEN THE System SHALL fail startup, verified by a startup/configuration test.
11. WHEN the Liquibase seed integration test runs against a freshly migrated database, THE System SHALL contain exactly one `resources` row whose code is `PROJECTS`.
12. WHEN the Liquibase seed integration test inspects the role-permission grants for the `PROJECTS` resource, THE System SHALL show grants of CREATE, READ, UPDATE, and DELETE for ADMIN; CREATE, READ, and UPDATE for MANAGER; READ for each of FOREMAN, WORKER, FINANCIER, and CLIENT; and DELETE for ADMIN only (no DELETE grant for any non-ADMIN role).
13. WHEN the Liquibase seed changesets are applied a second time against an already-seeded database, THE System SHALL add no additional and no duplicate `PROJECTS` resource, `role_resources`, or `role_resource_operations` rows, leaving the seeded row counts unchanged.

### Requirement 8: Admin UI

**User Story:** As an admin user, I want a projects management page with a simple create form, so that I can create projects with a team and client, and view, edit, and delete projects through the shared DataTable UI.

#### Acceptance Criteria

1. WHEN a user navigates to route `/projects`, THE System SHALL lazy-load `ProjectsPage` and render the shared `DataTable` configured with `resource="PROJECTS"` and the columns `name`, `address` (or `formattedAddress`), `area`, `startDate`, `endDate`, and `status` (localized badge), such that all listed columns are visible in the rendered table header, and SHALL render no client or manager reference column.
2. WHEN the `DataTable` requests list data, THE System SHALL apply search, sort, filter, and pagination server-side; and THE System SHALL render `status` as exactly one localized badge corresponding to the row's `ProjectStatus` value (one of DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED).
3. WHILE the list query returns zero rows, THE System SHALL display a localized empty-state message under `projects.*` and SHALL render no data rows.
4. WHEN an admin user opens the create form, THE System SHALL display the base fields `name`, `area`, `startDate`, `endDate`, and `status` (select); an address field backed by a Google address autocomplete component that calls the backend proxies `/api/projects/address/autocomplete` and `/api/projects/address/details` and stores `googlePlaceId`, `formattedAddress`, `latitude`, and `longitude`; a team selector allowing multi-selection of users where each user's project role equals the user's company role (shown, not separately chosen); and a Client block allowing selection of an existing client OR adding a new client through the reused invite form (name, phone, email, WITHOUT a role selector).
5. WHEN the admin user submits the create form with all required fields valid, THE System SHALL call the custom create endpoint, persist the project with its team and client, and close the form.
6. WHEN an admin user opens the edit form, THE System SHALL allow editing of the base project fields via the generic update, and MAY provide minimal or deferred team management in this first version.
7. IF a form field fails validation on submit, THEN THE System SHALL block submission, retain all entered field values, and display a localized validation message identifying each invalid field.
8. IF `endDate` is earlier than `startDate` on submit, THEN THE System SHALL block submission and display a localized validation message indicating that `endDate` must be on or after `startDate`.
9. WHEN an admin user confirms deletion in the delete dialog, THE System SHALL delete the selected record and close the dialog.
10. WHERE the current user lacks the `PROJECTS` DELETE permission (any non-ADMIN role), THE System SHALL hide the delete control; and WHERE the current user lacks the `PROJECTS` CREATE or UPDATE permission, THE System SHALL hide the corresponding create or edit control, as resolved by `usePermission` on `PROJECTS`.
11. THE System SHALL build all list fetch requests using the shared `buildFetchQuery` adapter.
12. WHEN a create, edit, or delete mutation completes successfully, THE System SHALL invalidate the projects list query and display a localized success toast; and IF a create, edit, or delete mutation fails, THEN THE System SHALL retain the existing list state and display a localized error toast indicating the operation failed.
13. THE System SHALL render all UI text from keys under `projects.*` defined at parity in BOTH `pl.json` and `ru.json` (including the five status labels DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED, the Google address component text, and the Client block text), and SHALL NOT render any raw i18n key.
14. WHILE FOR-04-15 has not added the menu entry, THE System SHALL guard the `/projects` route with an interim `PROJECTS`/`READ` permission check and SHALL deny access to users lacking `PROJECTS` READ permission.

### Requirement 9: UI tests

**User Story:** As a maintainer, I want component and localization tests for the projects UI, so that the create form, list rendering, and translations stay verified.

#### Acceptance Criteria

1. WHEN the create form is rendered, THE System SHALL be covered by a component test asserting that the Google address autocomplete calls the mocked backend proxy and stores the returned `googlePlaceId`, `formattedAddress`, `latitude`, and `longitude`, that a team member can be selected, and that the Client block supports both the existing-client and new-client cases.
2. WHEN the projects list is rendered with a non-empty result set, THE System SHALL be covered by a component test asserting that each row displays the `name`, `address`, `area`, `startDate`, `endDate`, and `status` fields, and that the number of rendered rows equals the number of supplied project records.
3. WHEN the projects list renders a row, THE System SHALL be covered by a component test asserting that the `status` cell displays a localized badge whose label corresponds to the row's `ProjectStatus` value for each of the five defined status values.
4. WHILE the current user lacks the required permission for a gated control, THE System SHALL be covered by a component test asserting that the gated control is not present in the rendered output; and WHILE the current user holds the required permission, the test SHALL assert the gated control is present.
5. WHEN the projects list is rendered with an empty result set (zero records), THE System SHALL be covered by a component test asserting that the empty-state indicator is displayed and that zero data rows are rendered.
6. THE System SHALL be covered by an i18n parity test asserting that the set of `projects.*` keys is identical in `pl.json` and `ru.json`, with no key present in one file and absent in the other, including the five `ProjectStatus` status-label keys, the Google address component text, and the Client block text, all present with non-empty values in both files.
