# Requirements Document

FOR-04-14: Room entity + project-scoped dimensions, polygon geometry, per-wall openings, and calculated-vs-manual metrics

## Introduction

FOR-04-14 adds the `Room` entity — a **project-scoped** child of the FOR-04-13
`Project` — backed by a real `rooms` table, plus its ABAC wiring and a first
frontend admin page on the shared DataTable. A room belongs to exactly one
project (`project_id` FK) and references exactly one room type (`room_type_id` FK
to the FOR-04-05 `ROOM_TYPES` dictionary). Because the localized room-type name
comes from the referenced `RoomType`, `Room` is an **operational** entity and
carries **no** `nameRU`/`namePL` i18n pair; it may carry an optional single
free-text `label` (a plain, non-localized string) so two rooms of the same type
can be told apart (for example "Bathroom 1" / "Bathroom 2").

A room captures measurement data used downstream by the estimate (FOR-05) and by
package offers. Measurements can be produced two ways, and the entity records
which way each metric came from:

- **Calculated** — the room's geometry is drawn as a polygon (an ordered list of
  vertices with edge/wall segments and, per wall, a set of openings such as doors
  and windows). Floor area, perimeter, wall area, and opening totals are derived
  from that geometry by the backend.
- **Manual** — when the geometry is not drawn (known ahead of time, or too slow
  to draw), the areas/perimeter can be typed directly. In that case the linear
  geometry is left empty and each typed metric is flagged as **manually entered**
  rather than calculated.

Every derived metric therefore carries a **source flag** (`CALCULATED` or
`MANUAL`) so the UI and downstream consumers can tell whether a number was
computed from geometry or entered by hand. The geometry itself is stored as
**structured JSON** (vertices, walls, per-wall openings, opening dimensions); the
frontend renders that JSON as an SVG drawing. This structured geometry + metric
model is intentionally reusable by a later floor-plan recognition feature, which
will produce the same JSON shape.

The measurement set (with the units currently used on projects) is:

| Metric | Unit | Notes |
| --- | --- | --- |
| Floor area (`floorArea`) | m² | derived from polygon, or manual |
| Wall area (`wallArea`) | m² | derived from perimeter × height − openings, or manual |
| Perimeter / outline (`perimeter`) | mb | derived from polygon edge lengths, or manual |
| Door count (`doorCount`) | szt | count of door openings |
| Door height (`doorHeight`) | mb | per-opening input |
| Door width (`doorWidth`) | mb | per-opening input |
| Door area (`doorArea`) | m² | derived per opening (height × width) summed, or manual |
| Missing wall (`wallGap`) | mb | length of wall to exclude |
| Missing finish (`finishGap`) | mb | length of finish to exclude |
| Window height (`windowHeight`) | mb | per-opening input |
| Window width (`windowWidth`) | mb | per-opening input |
| Window area (`windowArea`) | m² | derived per opening (height × width) summed, or manual |
| Internal corners (`internalCorners`) | szt | count |
| Height (`ceilingHeight`) | mb | ceiling height |

Openings are attached **per wall**: for each wall the user can add openings with a
quantity (count) and, per opening unit, its linear dimensions (height, width),
from which the opening area is derived.

Rooms follow the standard FOR-04 vertical: `Room` extends `BaseEntity`; CRUD is
the generic `AdminController`/`AdminService` (no custom create in this spec); the
service is a `ProjectScopedService` whose `getProjectIdPath()` returns
`"project.id"`, so non-ADMIN LIST reads are membership-filtered and by-id
reads/writes assert project membership (per FOR-03-04). The menu entry stays with
FOR-04-15; until then an interim `ROOMS`/`READ` route guard protects `/rooms`.

Access follows the FOR-04 matrix as refined for this spec: ADMIN full CRUD;
MANAGER CREATE/READ/UPDATE/DELETE (own); FOREMAN READ/UPDATE (own);
WORKER/FINANCIER/CLIENT READ. "(own)" = automatic `project_members` filtering.

Explicitly OUT OF SCOPE: an interactive SVG polygon drawing editor. This spec
delivers the full backend data model + calculation engine + source flags, and a
**basic** create/edit form that accepts geometry and metrics as structured input
plus a **read-only** SVG rendering of stored geometry. The rich interactive
drawing/annotation editor is deferred to FOR-05. Floor-plan recognition is a
separate future feature.

Depends on FOR-04-13 (`projects` table + `ProjectScopedService` anchor +
`project_members` filtering), FOR-04-05 (`room_types` dictionary + `ROOM_TYPES`
resource), FOR-04-01 (table reference filter + metadata), FOR-03-04
(`project_members` + `ProjectAccessCache`), and the FOR-01 CRUD framework.

## Glossary

- **Room**: The operational entity introduced by this spec, backed by the `rooms` table. It belongs to one `Project` (`project_id` FK) and references one `RoomType` (`room_type_id` FK). It uses an optional non-localized `label` rather than the `nameRU`/`namePL` pair of dictionary entities.
- **Project-scoped child**: An entity whose project boundary is resolved through an association to `projects`. `RoomService` implements `ProjectScopedService` and returns `"project.id"` from `getProjectIdPath()`, so project-scoped filtering resolves through the room's owning project.
- **RoomType**: The FOR-04-05 dictionary entity (`ROOM_TYPES` resource) carrying the localized `nameRU`/`namePL`; a room references it and shows the room-type name in its list.
- **RoomGeometry**: The structured JSON describing a room's shape: an ordered list of `vertices` (points), the `walls` (edges between consecutive vertices) and, per wall, its `openings`. Persisted in a JSON column and rendered as SVG on the frontend; it is the source of truth for calculated metrics.
- **Wall**: An edge of the room polygon between two consecutive vertices, carrying zero or more `openings`.
- **Opening**: A door or window on a wall, described by a `type` (`DOOR` or `WINDOW`), a `count` (quantity, szt), and per-unit linear dimensions `height` and `width` (mb), from which the opening's total area (m²) is derived.
- **MeasureValue**: A metric value paired with a **source flag** — `{ value, source }` where `source` is `CALCULATED` (derived from geometry) or `MANUAL` (typed directly).
- **CALCULATED**: A `MeasureValue.source` indicating the value was derived by the backend calculation engine from the room's geometry.
- **MANUAL**: A `MeasureValue.source` indicating the value was entered directly by the user, with the geometry left empty for that metric.
- **Calculation engine**: The backend component that, given a `RoomGeometry`, derives `floorArea`, `perimeter`, `wallArea`, `doorArea`, `windowArea`, and the door/window counts, and records each derived metric with source `CALCULATED`.
- **Metric set**: The fields listed in the Introduction table (floor area, wall area, perimeter, door count/height/width/area, missing wall, missing finish, window height/width/area, internal corners, height) with their units.
- **project_members**: The FOR-03-04 join table `(user_id, project_id, project_role_id)`; the sole source of "own projects" and the driver of membership-scoped filtering of room LIST reads and by-id access for non-ADMIN callers.
- **ABAC resource**: A named permission subject (here `ROOMS`) declared via `@PermissionResource` and seeded as a row in the resources table, against which role grants are checked.
- **ABAC grant**: A role-to-operation permission (CREATE/READ/UPDATE/DELETE) seeded for a given role on the `ROOMS` resource.
- **own / (own) filtering**: Automatic restriction of a caller's visible rows to the rooms whose owning project the caller belongs to via `project_members`.

## Requirements

### Requirement 1: Room entity & table

**User Story:** As a backend developer, I want a `Room` entity mapped to a real `rooms` table with a project FK, a room-type FK, an optional label, a geometry JSON column, and the full metric set with source flags, so that rooms carry the measurement data the estimate depends on.

#### Acceptance Criteria

1. THE system SHALL map a `RoomEntity` extending `BaseEntity` with a mandatory `@ManyToOne project` association (column `project_id`, NOT NULL, foreign key to `projects`) and a mandatory `@ManyToOne roomType` association (column `room_type_id`, NOT NULL, foreign key to `room_types`).
2. THE `RoomEntity` SHALL define an optional `label` field (String, nullable, maximum length 255 characters) and SHALL define no `nameRU` and no `namePL` field.
3. THE `RoomEntity` SHALL persist the room geometry in a JSON column `geometry` (nullable) holding a `RoomGeometry` structure of `vertices`, `walls`, and per-wall `openings`.
4. THE `RoomEntity` SHALL define the metric fields `floorArea`, `wallArea`, `doorArea`, `windowArea` (each `NUMERIC(12,2)`, m², nullable), `perimeter`, `wallGap`, `finishGap`, `doorHeight`, `doorWidth`, `windowHeight`, `windowWidth`, `ceilingHeight` (each `NUMERIC(12,2)`, mb, nullable), and `doorCount`, `windowCount`, `internalCorners` (each integer count, szt, nullable).
5. THE `RoomEntity` SHALL define, for each of `floorArea`, `wallArea`, `perimeter`, `doorArea`, and `windowArea`, a companion source flag column (`floor_area_source`, `wall_area_source`, `perimeter_source`, `door_area_source`, `window_area_source`) persisted as a string enum `MeasureSource` (`CALCULATED`, `MANUAL`), nullable when the metric is absent.
6. THE `MeasureSource` enum SHALL define exactly the two values `CALCULATED` and `MANUAL`, in that declaration order, and no other values.
7. WHEN the table-creation changeset runs against a database that does not contain the `rooms` table, THE system SHALL create the `rooms` table with all columns above plus the four `BaseEntity` audit columns (`created_date`, `created_by`, `updated_date`, `updated_by`), a `project_id BIGINT NOT NULL` foreign key to `projects(id)`, and a `room_type_id BIGINT NOT NULL` foreign key to `room_types(id)`.
8. IF the table-creation changeset runs against a database that already contains the `rooms` table, THEN THE system SHALL mark the changeset as run (`MARK_RAN` via a `tableExists` precondition) and SHALL make no schema changes.

### Requirement 2: Geometry model & per-wall openings

**User Story:** As a project user, I want to describe a room as a polygon with per-wall openings, so that the system can derive its areas and perimeter and later render its plan.

#### Acceptance Criteria

1. THE `RoomGeometry` JSON SHALL contain an ordered `vertices` array of points, each with numeric `x` and `y` coordinates, and a `walls` array where wall `i` connects vertex `i` to vertex `(i+1) mod n`.
2. THE `RoomGeometry` SHALL allow each wall to carry a `openings` array, each opening an object with `type` (one of `DOOR`, `WINDOW`), `count` (positive integer, szt), `height` (positive number, mb), and `width` (positive number, mb).
3. WHEN a room is created or updated with a `geometry` whose polygon has fewer than 3 vertices, THE system SHALL reject the request with a client-error response identifying the geometry as invalid, and persist no changes.
4. IF an opening in the geometry has a non-positive `count`, `height`, or `width`, or a `type` outside `{DOOR, WINDOW}`, THEN THE system SHALL reject the request with a client-error response identifying the invalid opening, and persist no changes.
5. THE `RoomGeometry` SHALL allow expressing, per wall, a "missing wall" (`wallGap`, mb) and a "missing finish" (`finishGap`, mb) segment length that reduce the wall/finish surface, and the room-level `wallGap` and `finishGap` metrics SHALL equal the sum across walls when the geometry is present.

### Requirement 3: Calculation engine (calculated metrics)

**User Story:** As a project user, I want floor area, perimeter, wall area, and opening totals derived automatically from the geometry, so that I do not have to compute them by hand.

#### Acceptance Criteria

1. WHEN a room is saved with a non-empty `geometry`, THE system SHALL compute `floorArea` (m²) as the absolute area of the polygon described by `vertices` (shoelace formula), record it, and set `floorArea` source to `CALCULATED`.
2. WHEN a room is saved with a non-empty `geometry`, THE system SHALL compute `perimeter` (mb) as the sum of the lengths of all wall edges, record it, and set `perimeter` source to `CALCULATED`.
3. WHEN a room is saved with a non-empty `geometry` AND a `ceilingHeight`, THE system SHALL compute `wallArea` (m²) as `(perimeter × ceilingHeight) − doorArea − windowArea − (wallGap × ceilingHeight)`, never below zero, record it, and set `wallArea` source to `CALCULATED`.
4. WHEN a room is saved with a non-empty `geometry`, THE system SHALL compute `doorArea` (m²) as the sum over all `DOOR` openings of `count × height × width`, `windowArea` (m²) as the sum over all `WINDOW` openings of `count × height × width`, `doorCount` as the sum of `DOOR` opening counts, and `windowCount` as the sum of `WINDOW` opening counts, and SHALL set `doorArea` and `windowArea` source to `CALCULATED`.
5. THE calculation engine SHALL be deterministic and side-effect free: given the same `geometry` it SHALL always produce the same metric values.

### Requirement 4: Manual metrics (source flags)

**User Story:** As a project user, I want to type areas and perimeter directly when I don't draw the geometry, and have them marked as manually entered, so that it is clear which numbers were computed and which were entered by hand.

#### Acceptance Criteria

1. WHEN a room is created or updated with a null/empty `geometry` AND a directly supplied value for one of `floorArea`, `wallArea`, `perimeter`, `doorArea`, or `windowArea`, THE system SHALL persist that value and set its source flag to `MANUAL`.
2. WHERE the `geometry` is present, THE system SHALL treat geometry as authoritative for `floorArea`, `perimeter`, `wallArea`, `doorArea`, and `windowArea`, overwrite any directly supplied values for those metrics with the calculated results, and set their sources to `CALCULATED`.
3. THE list/read DTO SHALL expose, for each of `floorArea`, `wallArea`, `perimeter`, `doorArea`, and `windowArea`, both the numeric value and its source flag.
4. IF a create or update request supplies a metric source flag value that is not one of `CALCULATED` or `MANUAL`, THEN THE system SHALL reject the request with a client-error response identifying the invalid field, and persist no changes.
5. IF a create or update request supplies a numeric metric outside the range 0.00 to 9,999,999,999.99, or a negative count for `doorCount`/`windowCount`/`internalCorners`, THEN THE system SHALL reject the request with a client-error response identifying the invalid field, and persist no changes.

### Requirement 5: Generic CRUD, project-scoped

**User Story:** As an API consumer, I want standard project-scoped CRUD for rooms at `/api/rooms`, so that rooms are managed with the shared framework and access is membership-filtered.

#### Acceptance Criteria

1. THE system SHALL expose the generic `AdminController` create, list, read, update, delete, count, metadata, and i18n operations for rooms at `/api/rooms`.
2. THE `RoomService` SHALL implement `ProjectScopedService` and override `getProjectIdPath()` to return `"project.id"`.
3. WHERE the caller holds a non-ADMIN role, WHEN the caller requests a LIST read of `/api/rooms`, THE system SHALL return only rooms whose owning project has a matching `project_members` row joining the caller, and SHALL exclude every room whose project the caller is not a member of.
4. WHERE the caller holds the ADMIN role, WHEN the caller requests any read or write operation on `/api/rooms`, THE system SHALL bypass both the ABAC matrix and membership filtering.
5. IF a non-ADMIN caller requests a READ, UPDATE, or DELETE on a specific room whose owning project the caller is not a member of, THEN THE system SHALL deny the operation, return an access-denied response, and neither return nor modify the target room.
6. IF a create or update references a `projectId` or `roomTypeId` that does not reference an existing row, THEN THE system SHALL reject the request with a client-error response identifying the offending field, and persist no changes.
7. WHEN the caller lists `/api/rooms` with a reference filter on `project.id` or `roomType.id`, THE system SHALL return only rooms matching the supplied reference value(s).

### Requirement 6: ABAC resource & grants

**User Story:** As a security administrator, I want the `ROOMS` resource and its role grants seeded, so that access to room endpoints is governed by the ABAC matrix.

#### Acceptance Criteria

1. THE room controller SHALL be annotated with `@PermissionResource("ROOMS")` whose value exactly matches the seeded `ROOMS` resource `code`, and each inherited CRUD handler SHALL carry its `@PermissionOperation`.
2. IF the room controller is annotated with `@PermissionResource` but has any in-scope guarded handler lacking a matching `@PermissionOperation` or `@RequiresPermission` (or the reverse), THEN THE application SHALL fail to start during `PermissionAnnotationValidator` validation with an error indicating the incomplete annotation.
3. WHEN the seed changeset runs against a database with no existing `ROOMS` resource, THE seed SHALL insert exactly one `ROOMS` resource row populated with non-null `code`, `name_ru`, `name_pl`, `description_ru`, and `description_pl` values.
4. WHEN the seed changeset runs, THE seed SHALL grant the following operations via `role_resources` and `role_resource_operations` for the `ROOMS` resource: ADMIN = CREATE, READ, UPDATE, DELETE; MANAGER = CREATE, READ, UPDATE, DELETE; FOREMAN = READ, UPDATE; WORKER = READ; FINANCIER = READ; CLIENT = READ.
5. THE seed SHALL grant CREATE and DELETE on the `ROOMS` resource to ADMIN and MANAGER only, and to no other role.
6. IF a seed changeset re-runs against a database where its target row(s) already exist, THEN THE changeset SHALL insert zero additional rows, guarded by `<preConditions onFail="MARK_RAN">` with an `<sqlCheck expectedResult="0">` counting the existing row(s).
7. THE new changeset file(s) SHALL be registered last in `changelog.xml`, after `041-seed-projects-resource.xml`, using a sequence number of 042 or higher.

### Requirement 7: Backend tests

**User Story:** As a maintainer, I want tests covering the entity, geometry validation, calculation engine, manual/source-flag behavior, project scoping, and seed, so that room behavior and permissions stay verified.

#### Acceptance Criteria

1. WHEN the calculation engine unit/property test computes metrics for a generated polygon geometry, THE System SHALL produce `floorArea` equal to the shoelace area, `perimeter` equal to the summed edge lengths, and `doorArea`/`windowArea` equal to the summed `count × height × width` per opening type, each flagged `CALCULATED`.
2. WHEN the Testcontainers integration test creates a room with geometry, THE System SHALL persist the calculated metrics with `CALCULATED` sources and reject a geometry with fewer than 3 vertices or an invalid opening.
3. WHEN the Testcontainers integration test creates a room with null geometry and directly supplied areas/perimeter, THE System SHALL persist those values with `MANUAL` sources.
4. WHEN the Testcontainers integration test supplies both geometry and manual metric values, THE System SHALL persist the geometry-derived values with `CALCULATED` sources and ignore the supplied manual values for those metrics.
5. WHILE the authenticated caller holds a non-ADMIN role, WHEN the Testcontainers integration test lists `/api/rooms`, THE System SHALL return only rooms whose owning project the caller belongs to, and by-id read/update/delete of a non-member room SHALL be denied.
6. WHEN the Testcontainers integration test issues read, update, and delete requests for an existing room id, THE System SHALL return the room on read, persist updated fields on update, and remove the room on delete.
7. WHEN the Testcontainers integration test lists `/api/rooms` with a reference filter on `project.id` and on `roomType.id`, THE System SHALL return only the rooms matching each supplied reference value.
8. WHEN the Liquibase seed integration test runs against a freshly migrated database, THE System SHALL contain exactly one `resources` row whose code is `ROOMS`, with the exact grant matrix of Requirement 6.4 (CREATE/DELETE for ADMIN and MANAGER only), and re-applying the seed SHALL leave the counts unchanged.
9. WHEN a clean-startup smoke test runs, THE System SHALL confirm the fully annotated `RoomController` starts cleanly under `PermissionAnnotationValidator`.

### Requirement 8: Admin UI

**User Story:** As an admin user, I want a rooms management page with a create/edit form and a read-only plan preview, so that I can manage rooms with their room type, geometry, and metrics through the shared DataTable UI.

#### Acceptance Criteria

1. WHEN a user navigates to route `/rooms`, THE System SHALL lazy-load `RoomsPage` and render the shared `DataTable` configured with `resource="ROOMS"` and columns for `project`, `roomType`, `label`, `floorArea` (with source indicator), `wallArea` (with source indicator), `perimeter` (with source indicator), and `ceilingHeight`, such that all listed columns are visible in the rendered table header.
2. WHEN the `DataTable` requests list data, THE System SHALL apply search, sort, filter, and pagination server-side, and SHALL render reference filters for `project.id` and `roomType.id` per FOR-04-01.
3. WHILE the list query returns zero rows, THE System SHALL display a localized empty-state message under `rooms.*` and SHALL render no data rows.
4. WHEN an admin user opens the create/edit form, THE System SHALL display a room-type selector (reference to `ROOM_TYPES`), a project selector (reference to `PROJECTS`), an optional `label`, `ceilingHeight`, `internalCorners`, and per-wall opening entry (per wall, add openings with a `type`, `count`, `height`, `width`), plus a manual-entry mode allowing direct input of `floorArea`, `wallArea`, `perimeter`, `doorArea`, and `windowArea` when no geometry is drawn.
5. WHEN the form has a geometry, THE System SHALL show the derived `floorArea`, `perimeter`, `wallArea`, `doorArea`, and `windowArea` as read-only values labeled "Calculated"; and WHEN the form is in manual mode, THE System SHALL accept typed values labeled "Manual".
6. WHEN a room with stored geometry is displayed, THE System SHALL render a read-only SVG plan of the polygon and its openings from the stored `geometry` JSON.
7. IF a form field fails validation on submit (missing project or room type, invalid opening, out-of-range metric), THEN THE System SHALL block submission, retain all entered values, and display a localized validation message identifying each invalid field.
8. WHEN an admin user confirms deletion in the delete dialog, THE System SHALL delete the selected room and close the dialog.
9. WHERE the current user lacks the `ROOMS` CREATE, UPDATE, or DELETE permission, THE System SHALL hide the corresponding create, edit, or delete control, as resolved by `usePermission` on `ROOMS`.
10. THE System SHALL build all list fetch requests using the shared `buildFetchQuery` adapter.
11. WHEN a create, edit, or delete mutation completes successfully, THE System SHALL invalidate the rooms list query and display a localized success toast; and IF a mutation fails, THEN THE System SHALL retain the existing list state and display a localized error toast.
12. THE System SHALL render all UI text from keys under `rooms.*` defined at parity in BOTH `pl.json` and `ru.json` (including all metric labels with their units, the source-flag labels "Calculated"/"Manual", opening type labels, and the empty state), and SHALL NOT render any raw i18n key.
13. WHILE FOR-04-15 has not added the menu entry, THE System SHALL guard the `/rooms` route with an interim `ROOMS`/`READ` permission check and SHALL deny access to users lacking `ROOMS` READ permission.

### Requirement 9: UI tests

**User Story:** As a maintainer, I want component and localization tests for the rooms UI, so that the list rendering, form, SVG preview, and translations stay verified.

#### Acceptance Criteria

1. WHEN the rooms list is rendered with a non-empty result set, THE System SHALL be covered by a component test asserting each row displays `project`, `roomType`, `label`, `floorArea`, `wallArea`, `perimeter`, and `ceilingHeight`, that each area/perimeter cell shows its source indicator, and that the rendered row count equals the number of supplied records.
2. WHEN the create/edit form is rendered, THE System SHALL be covered by a component test asserting that adding a per-wall opening updates the form state, that manual mode accepts direct area/perimeter input, and that geometry mode shows derived values read-only.
3. WHEN a room with geometry is shown, THE System SHALL be covered by a component test asserting the read-only SVG plan renders a polygon path from the stored `geometry` JSON.
4. WHILE the current user lacks the required permission for a gated control, THE System SHALL be covered by a component test asserting the gated control is absent; and WHILE the user holds the permission, the test SHALL assert the control is present.
5. THE `rooms.*` key sets SHALL be covered by a localization test asserting identical keys in `pl.json` and `ru.json` (including all metric labels, source-flag labels, and opening type labels), all non-empty, with no raw key rendered.
