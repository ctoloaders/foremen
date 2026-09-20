# Requirements Document

FOR-05-02: Rooms tab in the project workspace — an interactive, inline-editable "dimensions × rooms" matrix (Excel `Wymiary` layout) with client-tracked changes, bulk save in one transaction, undo/redo, quick room creation and deletion, plus a full-page room editor reusing the existing room geometry mini-editor

## Introduction

FOR-05-02 is a child spec of FOR-05 (Project estimate). It replaces the placeholder `rooms`
workspace tab (shipped as a `PlaceholderTab` by FOR-05-01) with a real, **interactive matrix** that
mirrors the client's Excel `Wymiary` sheet: room **dimensions in rows**, the project's **rooms in
columns**, a leading unit column, and a trailing per-row **Σ (sum)** column. It also adds a
full-page room editor inside the workspace that reuses the FOR-04-14 geometry mini-editor.

The spec is **mostly frontend** but requires a **small, generic backend addition**: a bulk-update
endpoint on the shared CRUD framework (mirroring the existing bulk-create), and a per-field manual
override in `RoomService` so a single geometry-derived metric can be overridden to a manual value.
No new entity, table, field, or ABAC resource is added; the shipped `Room` entity, `/api/rooms`
CRUD, and the `ROOMS` resource are reused.

The interaction model is **Excel-like**:

- **Inline editing.** Clicking any value cell (or the room's label) turns it into an editable
  field. Every editable value can be changed in place; there is no per-cell save.
- **Client-tracked changes.** Multiple edits accumulate as a **pending change set held on the
  frontend** and persisted in `localStorage` **keyed to the specific project**, so the work
  survives a reload. Two separate stores are kept: one for **edits to existing rooms** and one for
  **newly created rooms**.
- **One "Save".** A single Save button commits all pending changes. Edits to existing rooms are
  sent as **one bulk-update transaction**; new rooms are sent as **one bulk-create transaction**.
  On success the pending stores are **cleared**.
- **Undo/redo + discard.** Single changes can be undone and redone; a "discard all" clears the
  entire pending change set.
- **Live totals.** The Σ column and per-room aggregates are computed from the **current values
  including pending edits**, so totals update as the user types.
- **Every inline edit is a manual value.** A value changed inline is saved with `MeasureSource =
  MANUAL`. For a room that has geometry, overriding one of the five geometry-derived metrics
  overrides **only that field** to `MANUAL`, leaving the room's other geometry-derived metrics
  untouched.
- **Quick create.** A "+" affordance adds a new room **column** immediately with all values `0`
  (Excel-style). The new column's room type must be chosen inline (a required selector) before the
  change set can be saved.
- **Delete.** Any room can be deleted with a confirmation (assertion) dialog.

Editability is gated on the project lifecycle: **rooms are editable only while the project is in
the design stage and NOT past `ACTIVE`** — concretely, room dimensions are editable only while
`Project.status === 'DRAFT'`, and are **locked** once the project reaches `ACTIVE` (in work) or any
later status. In locked states the matrix and the room editor are strictly read-only.

The source of truth for the table layout is the client's Excel workbook
(`EBRD copy Matrix Foremen v3.0.xlsx`, sheet **`Wymiary`**): dimensions in rows, rooms in columns,
a unit column (`m2`/`mb`/`szt`), and a trailing `sum` column. All 14 `Wymiary` dimensions already
exist as fields on the shipped `Room` entity (verified against `RoomEntity`); the entity also
carries `windowCount`. No new dimension is added to the model.

This spec builds on the real patterns: the FOR-05-01 workspace tab contract (`WORKSPACE_TABS`,
`ProjectTabProps = { projectId, project }`, lazy panels in a `SuspenseWrapper` boundary), the
projects/rooms feature modules, the generic `AdminController`/`AdminService` CRUD framework (whose
`createBulk` = `POST /bulk` already exists), `usePermission()`, and i18n via react-i18next with
`locales/pl.json` + `locales/ru.json` at a single active language at a time.

## Glossary

- **Rooms tab**: The workspace tab keyed `rooms` in `WORKSPACE_TABS` (FOR-05-01), currently a `PlaceholderTab`; FOR-05-02 replaces its `lazy` target with the real interactive matrix panel.
- **Dimensions matrix**: The interactive table rendered by the Rooms tab — dimension rows × room columns, a leading unit column, a trailing per-row `Σ` column, with inline-editable cells.
- **Dimension**: One of the 14 `Wymiary` rows, each backed by an existing `Room` field: `floorArea`, `wallArea`, `perimeter`, `doorCount`, `doorHeight`, `doorWidth`, `doorArea`, `wallGap`, `finishGap`, `windowHeight`, `windowWidth`, `windowArea`, `internalCorners`, `ceilingHeight` (plus `windowCount`).
- **Unit**: The measurement unit of a dimension row — `m²` (area), `mb` (running metre), or `szt` (count), matching the Excel `B` column.
- **Source flag**: The `MeasureSource` of a metric value — `CALCULATED` (derived from geometry) or `MANUAL` (hand-entered) — carried by the five area/perimeter metrics via `MeasureValueDto { value, source }`.
- **Σ (sum) column**: The trailing column that totals each dimension row across all room columns (the Excel `sum` column), computed from current values including pending edits.
- **Pending change set**: The client-side, not-yet-saved edits and new rooms for a project, held in memory and persisted in `localStorage` keyed to the project.
- **Edits store**: The `localStorage` store of pending edits to existing rooms (per project).
- **New-rooms store**: The separate `localStorage` store of pending newly created rooms (per project).
- **Bulk save**: Committing the pending change set — edits via one bulk-update transaction, new rooms via one bulk-create transaction — after which the stores are cleared.
- **Bulk-update endpoint**: A new generic `PUT /api/{resource}/bulk` on `AdminController` applying a list of per-row updates in one transaction (mirroring the existing `createBulk`), inherited by every controller including rooms.
- **Per-field manual override**: Saving an explicit value for a single geometry-derived metric so that one field becomes `MANUAL` while the room keeps its geometry and its other derived metrics.
- **Quick create**: Adding a new room column inline (all zeros) with a required inline room-type selector, saved via bulk-create.
- **Room editor page**: The full-page workspace surface at `/projects/:projectId/rooms/:roomId` for editing one room, reusing the geometry mini-editor + manual-metrics override.
- **Geometry mini-editor**: The existing `RoomOpeningsEditor` (per-wall openings producing the `geometry` JSON) + read-only `RoomPlanPreview` (SVG) from FOR-04-14.
- **Editable stage**: `Project.status === 'DRAFT'`. **Locked stage**: `ACTIVE` (in work) and any later status (`COMPLETED`, etc.), in which rooms are read-only.
- **`ProjectTabProps`**: The FOR-05-01 tab-panel contract `{ projectId: string; project: ProjectReadDto }`.

## Requirements

### Requirement 1: Rooms tab panel replaces the placeholder

**User Story:** As a user viewing a project workspace, I want the Rooms tab to show all of the project's rooms and their dimensions in one interactive matrix, so that I can review and edit room measurements in one place.

#### Acceptance Criteria

1. THE system SHALL replace the `rooms` tab's `lazy` target in `WORKSPACE_TABS` with a real matrix panel component, without changing the tab contract (`key`, `stage`, `owner`, `requiredPermission`, `labelKey`) established in FOR-05-01.
2. THE `rooms` tab SHALL remain gated by its existing `requiredPermission` `{ resource: 'ROOMS', operation: 'READ' }`.
3. THE panel SHALL accept the FOR-05-01 `ProjectTabProps` and SHALL fetch the project's rooms via the existing `GET /api/rooms` filtered to `project.id`.
4. WHEN the project has no rooms AND no pending new rooms, THE panel SHALL render a graceful empty state (never a raw key) that still exposes the quick-create "+" affordance when editable.
5. WHILE the rooms list is loading, THE panel SHALL render a non-blocking loading state and SHALL NOT render a raw i18n key.
6. IF the rooms API request fails, THEN THE panel SHALL render a graceful error state (i18n key) and SHALL NOT crash the workspace shell.

### Requirement 2: Dimensions matrix (Excel `Wymiary` orientation)

**User Story:** As a designer, I want a dense matrix with dimensions in rows and rooms in columns like the Excel `Wymiary` sheet, so that I can compare and fill in every room's measurements at a glance.

#### Acceptance Criteria

1. THE matrix SHALL render dimensions **in rows** and rooms **in columns**, matching the Excel `Wymiary` orientation.
2. THE matrix SHALL render the dimension rows in this order, each backed by the named `Room` field: `floorArea`, `wallArea`, `perimeter`, `doorCount`, `doorHeight`, `doorWidth`, `doorArea`, `wallGap`, `finishGap`, `windowCount`, `windowHeight`, `windowWidth`, `windowArea`, `internalCorners`, `ceilingHeight`.
3. THE matrix SHALL render a leading **unit** column: `m²` for `floorArea`/`wallArea`/`doorArea`/`windowArea`; `mb` for `perimeter`/`doorHeight`/`doorWidth`/`wallGap`/`finishGap`/`windowHeight`/`windowWidth`/`ceilingHeight`; `szt` for `doorCount`/`windowCount`/`internalCorners`.
4. THE matrix SHALL render one column per room, including pending new-room columns; WHEN the room has a `label`, THE header SHALL show the `label` AND ALSO the localized `roomTypeName` in parentheses in a smaller font (e.g. `Label (RoomType)`); WHEN the room has no `label`, THE header SHALL show the localized `roomTypeName` alone.
5. THE matrix SHALL render a trailing **Σ** column that totals each dimension row across all room columns, summed within the single dimension so the unit is consistent.
6. WHEN a room's value for a dimension is absent (`null`), THE cell SHALL render a neutral empty indicator (`—`) and SHALL be treated as `0` in the Σ, and SHALL NOT render `null`/`NaN`.
7. THE Σ column and any per-room aggregates SHALL be computed on the client from the **current values including pending edits and pending new rooms**, and SHALL be recomputed live as the user edits (Requirement 4).
8. THE matrix SHALL be responsive: a dense horizontally scrollable grid with a sticky dimension-label (first) column on desktop/tablet and mobile, so no data is lost on small screens; THE sticky first column SHALL be content-sized to fit the longest dimension label (`width: max-content`, no wrapping) rather than a fixed min-width, while remaining sticky and part of the horizontally scrollable dense grid.

### Requirement 3: Source flags (calculated vs. manual)

**User Story:** As a foreman, I want to see which values were calculated from geometry and which were entered by hand, so that I can tell my manual overrides from derived values.

#### Acceptance Criteria

1. FOR each cell of a source-carrying dimension (`floorArea`, `wallArea`, `perimeter`, `doorArea`, `windowArea` — the `MeasureValueDto` metrics), THE matrix SHALL indicate the value's source (`CALCULATED` or `MANUAL`) using the existing indicator pattern (`RoomSourceBadge`/`MeasureValueCell`) with localized labels (`rooms.source.calculated`/`rooms.source.manual`).
2. WHERE a dimension does not carry a source flag (counts, raw door/window dimensions, ceiling height), THE matrix SHALL render the plain numeric value with no source indicator.
3. WHILE a cell has a pending inline edit, THE matrix SHALL indicate that value as `MANUAL` (the pending value overrides the persisted source in the display), reflecting that a saved inline edit becomes `MANUAL` (Requirement 5).

### Requirement 4: Inline editing & client-tracked change set

**User Story:** As a designer, I want to click a cell and type a new value, make several changes, and see the totals update, so that filling in room dimensions feels like editing a spreadsheet.

#### Acceptance Criteria

1. WHILE the project is editable (Requirement 8) AND the user is granted `ROOMS`/`UPDATE`, WHEN the user clicks a value cell, THE matrix SHALL turn it into an editable input seeded with the current value, committing the new value on blur/Enter and cancelling on Escape.
2. THE matrix SHALL allow inline editing of every dimension value AND of the room `label` (the column header), including for rooms that have geometry.
3. THE matrix SHALL accumulate all inline edits into a client-side **pending change set** WITHOUT calling the API per cell, and SHALL visibly indicate which cells/columns have unsaved changes and show an aggregate "unsaved changes" indicator.
4. WHEN a value is edited, THE Σ column and per-room aggregates SHALL recompute immediately from the current values including the pending edit (Requirement 2.7), so totals reflect edits before saving.
5. THE pending change set SHALL validate values with the existing rooms zod schema (non-negative, within range); an invalid pending value SHALL be flagged inline and SHALL block Save until corrected, without discarding other pending edits.
6. THE pending change set SHALL be persisted in `localStorage` keyed to the specific project, in TWO separate stores — one for edits to existing rooms and one for pending new rooms — so a reload restores the exact in-progress state per project and per store.
7. THE pending stores SHALL be independent per `projectId`, so switching projects shows each project's own pending change set.
8. WHILE the project is editable AND the user is granted `ROOMS`/`UPDATE`, THE matrix SHALL support keyboard navigation across its editable value cells while editing dimensions:
   - WHEN the user presses an arrow key (Up/Down/Left/Right), THE matrix SHALL move the active cell to the adjacent editable value cell and open it immediately for input (the target cell becomes active/editable).
   - WHEN the user presses Tab / Shift+Tab, THE matrix SHALL move to the next / previous editable value cell; a Tab from the right-most room column SHALL wrap to the first editable cell of the next row when one is available/editable.
   - WHEN the user presses Enter, THE matrix SHALL commit the current value in place WITHOUT moving.
   - THE navigation SHALL be confined to editable value cells, skipping the unit column, the Σ column, and any non-editable/locked cells; it applies only while the project is editable and the user has `ROOMS`/`UPDATE`.

### Requirement 5: Manual source on save & per-field geometry override

**User Story:** As a foreman, I want a value I typed to be recorded as a manual value even for a room with a drawn plan, so that my correction to one number does not require redrawing the whole room.

#### Acceptance Criteria

1. THE system SHALL persist every inline-edited metric value with `MeasureSource = MANUAL`.
2. WHEN the edited room has geometry AND the edited field is one of the five geometry-derived metrics, THE save SHALL override **only that field** to the manual value and mark it `MANUAL`, WHILE leaving the room's geometry and its other geometry-derived metrics unchanged (a per-field override, not an all-or-nothing detach).
3. THE backend `RoomService` normalization SHALL be extended so that an explicit metric value supplied in an update overrides the geometry-derived value for that field (stamping it `MANUAL`), instead of the current all-or-nothing rule where present geometry overwrites all five metrics as `CALCULATED`.
4. WHERE an update supplies geometry but NO explicit override for a given metric, THE backend SHALL keep deriving that metric from geometry as `CALCULATED` (unchanged behavior for non-overridden fields).
5. THE per-field override behavior SHALL be covered by backend property/integration tests asserting that overriding one metric leaves the others `CALCULATED` and the overridden one `MANUAL`.

### Requirement 6: Bulk save in one transaction

**User Story:** As a user, I want a single Save that commits all my changes atomically, so that I do not end up with a half-applied set of edits.

#### Acceptance Criteria

1. THE panel SHALL provide a single **Save** action that commits the entire pending change set for the project.
2. THE edits to existing rooms SHALL be committed via a **generic bulk-update endpoint** `PUT /api/rooms/bulk` that applies the list of per-row updates in **one transaction**; the endpoint SHALL be a new default method on `AdminController` (mirroring the existing `createBulk` `POST /bulk`), annotated `@PermissionOperation("UPDATE")`, backed by a `@Transactional` batch update in `AdminService`, and inherited by every controller.
3. THE pending new rooms SHALL be committed via the existing bulk-create endpoint `POST /api/rooms/bulk` (`createBulk`) in one transaction, `@PermissionOperation("CREATE")`.
4. WHEN both edits and new rooms are pending, THE Save SHALL apply both; the design SHALL define the ordering and failure handling so that a failure surfaces a clear error and does not leave the client stores in an inconsistent state.
5. WHEN the Save succeeds, THE system SHALL clear the pending stores for that project (both edits and new-rooms), refetch the rooms, and show a localized success toast; the Σ column SHALL then reflect the persisted values.
6. IF the Save is rejected (validation or server error), THEN THE system SHALL retain the pending change set (nothing cleared), keep the user's edits, and render a localized error explaining the failure.
7. THE bulk endpoints SHALL enforce the same project-scoping/ABAC as the single-row endpoints, so a caller may only bulk-update/create rooms in projects they may access. THIS SHALL be achieved by delegating each row to the existing single-row `update(id, model)`, which already routes through the shipped `ProjectScopedService.assertProjectAccess(id)` guard; FOR-05-02 SHALL NOT add any new access-control method to, nor modify, `ProjectScopedService` (the project-scoped edit/delete guard is already implemented and is reused as-is).

### Requirement 7: Undo / redo & discard all

**User Story:** As a user, I want to undo a mistaken edit, redo it, or discard everything, so that I can experiment freely before saving.

#### Acceptance Criteria

1. THE panel SHALL support **undo** of the most recent single change and **redo** of an undone change, operating on the pending change set (single-step granularity per edit).
2. THE undo/redo SHALL cover inline value edits, label edits, quick-create of a new room column, and room deletions staged in the pending set, restoring the prior state on undo.
3. THE panel SHALL provide a **discard all** action that clears the entire pending change set (both edits and new rooms) after a confirmation, returning the matrix to the last persisted values.
4. WHEN the pending change set is empty, THE undo, redo, discard, and Save actions SHALL be disabled/hidden appropriately (nothing to undo/redo/discard/save).
5. THE undo/redo history SHALL be scoped to the current session/project and SHALL degrade gracefully (no crash) if the persisted pending state is restored from `localStorage` without a full history.

### Requirement 8: Draft-only editability (locked at ACTIVE and beyond)

**User Story:** As a project manager, I want room dimensions frozen once work starts, so that measurements are stable during execution.

#### Acceptance Criteria

1. THE system SHALL treat rooms as editable ONLY WHILE `Project.status === 'DRAFT'`.
2. WHILE `Project.status` is `ACTIVE` (in work) OR any later status (`COMPLETED`, etc.), THE matrix and the room editor SHALL be strictly **read-only**: no inline editing, no quick-create, no delete, no Save — with a localized caption explaining that editing is available only in draft.
3. WHILE the user lacks `ROOMS`/`UPDATE` (regardless of status), THE edit affordances SHALL be hidden and the matrix/editor SHALL be read-only.
4. THE draft-only rule SHALL be enforced in the UI as a guard on all edit affordances (inline edit, quick-create, delete, Save); FOR-05-02 SHALL NOT add backend project-status enforcement to `/api/rooms` (the status gate is a UI concern; the backend contract stays status-agnostic).
5. WHEN a project transitions from `DRAFT` to a locked status WHILE a pending change set exists in `localStorage`, THE panel SHALL render read-only and SHALL surface the still-pending (now un-committable) changes with a clear message, without applying them and without crashing.

### Requirement 9: Quick room creation (Excel-style "+")

**User Story:** As a designer starting an empty project, I want to add a room column with a plus button and fill it in like Excel, so that I can build the room list quickly.

#### Acceptance Criteria

1. WHILE editable, THE matrix SHALL render the "+" quick-create affordance as a dedicated header cell/column in the matrix header, positioned **between the last room column header and the Σ column header** (not in a separate toolbar); WHEN clicked, THE affordance SHALL add a new room **column** immediately, initialized with all dimension values `0`.
2. THE new column SHALL require choosing a **room type inline** (a required selector in the column header) — matching decision (a): the room type must be picked inline before the change set can be saved.
3. THE new column SHALL allow inline editing of its `label` and all dimension values like any other column; a pending new room's `label` and dimension cell values SHALL be edited inline and tracked in the **new-rooms** store, persisted per project, and SHALL NOT be conflated with (written to) the existing-room **edits** store.
4. WHILE a pending new column has no room type selected, THE Save SHALL be blocked and the column flagged invalid (the room type is required by the backend `@NotNull roomTypeId`), without discarding other pending changes.
5. WHEN the Save succeeds, THE new rooms SHALL be created via `POST /api/rooms/bulk` in one transaction and SHALL appear as persisted columns after the refetch; the new-rooms store SHALL be cleared.
6. THE Σ column SHALL include pending new columns in its live totals before saving (Requirement 2.7).

### Requirement 10: Room deletion with confirmation

**User Story:** As a designer, I want to delete a room with a confirmation, so that I can remove a room I added or no longer need without deleting it by accident.

#### Acceptance Criteria

1. WHILE editable, THE matrix SHALL provide a per-room delete affordance in each room column header.
2. WHEN the user triggers delete, THE system SHALL show a confirmation (assertion) dialog naming the room, reusing the existing `DeleteRoomDialog` pattern, and SHALL delete only on confirmation.
3. WHEN a **persisted** room is confirmed for deletion, THE system SHALL delete it via the existing `DELETE /api/rooms/{id}` and refetch; the design SHALL define whether deletion is immediate or staged into the pending set (and if staged, it participates in undo per Requirement 7.2).
4. WHEN a **pending (unsaved) new** room column is deleted, THE system SHALL simply remove it from the new-rooms store with no API call.
5. IF a deletion is rejected by the server, THEN THE system SHALL render a localized error and SHALL NOT remove the column from the view.

### Requirement 11: Full-page room editor (reusing the geometry mini-editor)

**User Story:** As a designer, I want a full-page editor for a room inside the workspace that reuses the existing geometry mini-editor, so that I can edit a room's plan and dimensions in detail without leaving the project.

#### Acceptance Criteria

1. THE system SHALL add a nested workspace route `/projects/:projectId/rooms/:roomId` that lazy-loads a `RoomEditorPage`, wrapped in `SuspenseWrapper`, under `ProtectedLayout` + `PermissionGuard`, gated by `ROOMS`/`READ` for viewing.
2. THE editor SHALL hydrate the room via the existing `GET /api/rooms/{roomId}` and the project via the FOR-05-01 working-project state.
3. THE editor SHALL reuse `RoomOpeningsEditor` (per-wall openings producing the `geometry` JSON) and the read-only `RoomPlanPreview` (SVG), plus the manual-metrics override, so no new geometry editor is built.
4. THE editor SHALL let the user edit `label`, `roomType`, `ceilingHeight`, `internalCorners`, `geometry` (via the openings mini-editor), and the manual metrics, honoring the extended backend contract (geometry-derived metrics `CALCULATED`; an explicit metric override for a field → `MANUAL` per Requirement 5).
5. WHEN the user saves, THE editor SHALL persist via `PUT /api/rooms/{roomId}` and show a localized success toast, then return to (or refresh) the Rooms tab so the matrix and Σ reflect the new values.
6. IF the save is rejected, THEN THE editor SHALL retain the entered values and render a localized error without navigating away.
7. IF the `roomId` does not resolve to a room in the current project the user may access, THEN THE editor SHALL render a graceful "unavailable" state (i18n key) and SHALL NOT crash.
8. WHILE the project is in a locked stage (Requirement 8) or the user lacks `ROOMS`/`UPDATE`, THE editor SHALL render read-only.
9. THE editor SHALL NOT introduce a free-text description field; "reuse of the room description model" means reuse of the existing `geometry` JSON model and metric fields only.

### Requirement 12: Reuse, backend scope, graceful degradation

**User Story:** As a maintainer, I want FOR-05-02 to reuse the existing room model and CRUD, add only a small generic bulk-update to the framework and a per-field override to `RoomService`, so that its footprint stays minimal.

#### Acceptance Criteria

1. THE spec SHALL NOT add any new entity, table, field, ABAC resource, or room-specific controller; it SHALL reuse the shipped `Room` entity, `/api/rooms`, and the `ROOMS` resource.
2. THE only backend changes SHALL be: (a) a generic bulk-update default method on `AdminController` + a `@Transactional` batch update in `AdminService` (inherited by all controllers), and (b) the `RoomService` per-field manual override (Requirement 5.3). Both reuse the existing `ROOMS` `CREATE`/`UPDATE` operations; no new ABAC operation is added.
3. THE spec SHALL declare a hard dependency on FOR-04-14 (`Room` entity, `/api/rooms`, geometry mini-editor components, `rooms.*` i18n) and FOR-05-01 (workspace shell, tab contract, working-project state, router + route-permissions matcher).
4. THE frontend SHALL reuse existing rooms building blocks (`RoomOpeningsEditor`, `RoomPlanPreview`, `MeasureValueCell`, `RoomSourceBadge`, `DeleteRoomDialog`, zod schema, API client) rather than duplicating them.
5. IF the rooms API is unavailable, THEN the panel SHALL degrade to its error/empty state and SHALL NOT crash the workspace shell; a pending change set already in `localStorage` SHALL be preserved for a later successful save.
6. THE spec SHALL confirm that all 14 `Wymiary` dimensions already exist as `Room` fields, so no dimension is missing and no model change is required.

### Requirement 13: i18n — single active language, PL/RU parity

**User Story:** As a Polish- or Russian-speaking user, I want all Rooms-matrix and editor text in one active language at a time, so that the UI is never a mix of both.

#### Acceptance Criteria

1. THE system SHALL define all new strings under a `roomsSummary.*` namespace present at parity in BOTH `locales/pl.json` and `locales/ru.json`, rendering a single active language at a time, reusing existing `rooms.*` keys where an equivalent label exists.
2. THE system SHALL NOT render any raw i18n key for any Rooms-matrix or editor string.
3. THE `roomsSummary.*` namespace SHALL include, at minimum, keys for: the section title; the unit-column, room-column, and Σ-column headers; empty/loading/error states; the "editable only in draft" caption; the unsaved-changes indicator; Save, Discard all, Undo, and Redo actions; the quick-create ("+") affordance and its tooltip; the new-room default label and required-room-type prompt/validation; the delete confirmation title/description; the open-editor / edit affordances; the editor page title; the not-found state; save success/error and partial-failure toasts — each with PL and RU values.
4. THE dimension row labels SHALL reuse existing `rooms.form.*` labels where present (`floorArea`, `wallArea`, `perimeter`, `ceilingHeight`, `internalCorners`, `doorArea`, `windowArea`, `wallGap`, `finishGap`), and SHALL add the missing dimension labels (`doorCount`, `doorHeight`, `doorWidth`, `windowCount`, `windowHeight`, `windowWidth`) under `roomsSummary.dimension.*` at PL/RU parity, using the Excel `Wymiary` wording as the PL reference.
5. THE unit labels SHALL be `m²` / `mb` / `szt`, defined under `roomsSummary.unit.*` at PL/RU parity.
6. THE `roomsSummary.*` key set SHALL be covered by a localization parity test asserting identical keys in `pl.json` and `ru.json`, all values non-empty, with no raw key rendered.
