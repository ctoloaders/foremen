# Implementation Plan: FOR-05-02 — Interactive Rooms matrix + full-page room editor

## Overview

This plan builds FOR-05-02 in dependency order: first the small **generic backend additions**
(bulk-update on the CRUD framework + the `RoomService` per-field manual override), then the
frontend — the pure dimension/aggregation layer, the client change-set store + localStorage, the
interactive matrix (inline cells, live Σ, unsaved indicator), quick-create, delete, the bulk-save
orchestration, the full-page editor, the wiring (tab swap, route, route-permission), i18n parity,
and finally the tests and the Russian `test-cases.md`.

The backend change is **generic** (a `PUT /api/{resource}/bulk` inherited by every controller,
mirroring the existing `createBulk` `POST /bulk`) plus a `RoomService` normalization tweak; both
reuse the existing `ROOMS` `CREATE`/`UPDATE` operations — no new entity, table, field, or ABAC
resource. The frontend reuses the FOR-04-14 geometry mini-editor (`RoomOpeningsEditor`,
`RoomPlanPreview`), metric cells (`MeasureValueCell`, `RoomSourceBadge`), `DeleteRoomDialog`, the
zod schema, and the FOR-05-01 workspace shell.

Backend is Java (Spring Boot / JPA / jqwik / Testcontainers); frontend is TypeScript/React (Vitest
`--run` + RTL, `fast-check` for properties). Per the workspace test-execution standard, run only the
affected backend classes with `--tests` and read the JUnit XML; never run the full suite unless
explicitly asked. Test sub-tasks are marked optional with `*`.

## Tasks

### Backend — generic bulk-update + rooms per-field override

- [x] 1. Generic heterogeneous bulk-update on the CRUD framework
  - [x] 1.1 Add `AdminService.update(List<IdModel<ID, ServiceExtendedModel>>)` + the `IdModel` record
    - Add a `@Transactional default List<ServiceExtendedModel> update(List<IdModel<ID, ServiceExtendedModel>> items)`
      that maps each item through the existing single-row `update(id, model)` so validation
      (`validateUpdate` → `RoomService.normalize`), before-snapshot, `updateFields`, per-row audit, AND
      the existing `ProjectScopedService.assertProjectAccess` guard all run identically; the shared
      `@Transactional` makes the whole batch atomic. Add a small generic `record IdModel<ID, M>(ID id,
      M model)`.
    - MUST delegate to the per-row `update(id, model)` (do NOT bypass with a raw `saveAll`), so the
      shipped per-row project-scope guard fires for every row. Do NOT add any new method to, or modify,
      `ProjectScopedService` — its `assertProjectAccess` (reached via the overridden `update(id, model)`)
      already enforces project scoping and is reused as-is.
    - _Requirements: 6.2, 6.7_
  - [x] 1.2 Add `AdminController.updateBulk` (`PUT /bulk`) + the `BulkUpdateItem` request record
    - Add a `@PutMapping("/bulk") @PermissionOperation("UPDATE") default` method accepting
      `@Valid @RequestBody List<BulkUpdateItem<ID, UpdateRequestModel>>`, mapping each to
      `IdModel<ID, ServiceExtendedModel>` via `getMapper().toUpdateServiceExtendedModel(...)`, calling
      `getService().update(list)`, and returning `List<UpdateResponseModel>`. Add a generic
      `record BulkUpdateItem<ID, T>(ID id, @Valid T data)`. Every controller (incl. `RoomController`)
      inherits it with no per-controller code.
    - _Requirements: 6.2, 6.7, 12.2_
  - [x] 1.3 * Write `AdminService`/bulk-update unit + property coverage
    - Unit: `update(List)` applies per-row values (not one model to many), preserves order, writes one
      audit per row. Property (jqwik, ≥100): for a random list of (id, partial update), the batch result
      equals applying each `update(id, model)` in order. Tag `Feature: FOR-05-02-rooms-dimensions, Property (bulk)`.
    - _Requirements: 6.2_

- [x] 2. RoomService per-field manual override
  - [x] 2.1 Extend `applyCalculatedMetrics` with a per-metric override guard
    - For each of the five metrics (`floorArea`, `wallArea`, `perimeter`, `doorArea`, `windowArea`), if
      the incoming model carries an explicit value AND `source == MANUAL`, keep the supplied value and
      leave the source `MANUAL`; otherwise set the geometry-calculated value and stamp `CALCULATED`.
      Non-overridden metrics keep deriving from geometry unchanged.
    - _Requirements: 5.2, 5.3, 5.4_
  - [x] 2.2 * Write property test P4 — per-field override
    - **Property 4: for a room with geometry, overriding any subset K of the five metrics (value +
      MANUAL) persists exactly K as MANUAL and the rest CALCULATED.** jqwik, ≥100 iterations; class
      `RoomPerFieldOverridePropertyTest`; tag `Feature: FOR-05-02-rooms-dimensions, Property 4`.
    - **Validates: Requirements 5.2, 5.3, 5.4, 5.5**
  - [x] 2.3 * Write `RoomBulkUpdateIT` (Testcontainers)
    - Through `PUT /api/rooms/bulk`: heterogeneous per-row updates persist per row; a geometry room with
      one overridden metric ends with that field `MANUAL` and the rest `CALCULATED`; one invalid row
      rolls back the whole batch (nothing persisted); project-scoping/ABAC denies rooms outside the
      caller's projects.
    - _Requirements: 5.2, 6.2, 6.7_

- [x] 3. Checkpoint — backend compiles + affected classes pass
  - Run `./gradlew compileJava compileTestJava` (temp log; `getDiagnostics` first), then the classes from
    tasks 1.3/2.2/2.3 with `--tests` (temp log + JUnit XML). Do NOT run the full suite. Ask the user if
    questions arise.
  - _Requirements: 5.2, 6.2_

### Frontend — pure layer, stores, matrix, editor

- [x] 4. Dimension registry and pure helpers (`src/features/rooms/dimensions.ts`)
  - [x] 4.1 Create `DIMENSION_ROWS` + `getDimensionValue` / `getEffectiveValue` / `sumDimension`
    - `DIMENSION_ROWS` = 15 rows in `Wymiary` order (per Requirements 2.2/2.3), `hasSource` true only for
      the five metrics. `getDimensionValue(room, row)` reads `.value` iff `row.hasSource`, else the plain
      field; never throws. `getEffectiveValue(room, row, edits)` overlays a pending edit.
      `sumDimension(rooms, row, edits)` = `Σ (getEffectiveValue ?? 0)`, finite.
    - Reuse `rooms.form.*` label keys where present; new `roomsSummary.dimension.*` for
      `doorCount`/`doorHeight`/`doorWidth`/`windowCount`/`windowHeight`/`windowWidth`.
    - _Requirements: 2.2, 2.3, 2.5, 2.6, 2.7, 3.1, 3.2_
  - [x] 4.2 * Write property tests for the pure helpers
    - **Property 1: Σ equals per-dimension sum incl. pending** (null→0, finite). **Property 2: value
      extraction total & source-correct** (reads `.value` iff `hasSource`, never throws; `hasSource` true
      exactly for the five metrics). ≥100 iterations each. Tags `Feature: FOR-05-02-rooms-dimensions,
      Property 1` / `Property 2`. **Validates: 2.5, 2.6, 2.7, 3.1, 3.2**

- [x] 5. Client change-set store + localStorage
  - [x] 5.1 Implement the two project-keyed stores in `src/features/rooms/state/roomMatrixStorage.ts`
    - `foremen.roomMatrix.edits.<projectId>` (edits to existing rooms) and
      `foremen.roomMatrix.newRooms.<projectId>` (pending new rooms), with null-safe try/catch-guarded
      get/set/clear per store; independent per `projectId`; a `storage`-event hook for cross-tab sync.
    - _Requirements: 4.6, 4.7, 9.3, 12.5_
  - [x] 5.2 Implement the change-set reducer + undo/redo in `src/features/rooms/state/roomMatrixStore.ts`
    - State `{ edits, newRooms, deletedRoomIds, past, future }`; actions `EDIT_CELL`, `EDIT_LABEL`,
      `ADD_ROOM`, `SET_NEW_ROOM_TYPE`, `DELETE_ROOM`, `UNDO`, `REDO`, `DISCARD_ALL`, `HYDRATE`, `CLEAR`.
      Each mutating action pushes its inverse to `past` and clears `future`; `UNDO`/`REDO` move between
      stacks; every change writes through to the two stores (task 5.1); `HYDRATE` seeds from localStorage
      (empty history is fine); `CLEAR` empties state + stores after save.
    - _Requirements: 4.3, 4.6, 7.1, 7.2, 7.3, 7.4, 7.5_
  - [x] 5.3 * Write property + unit tests for the store
    - **Property 3: undo inverts / redo re-applies / discard empties** for any action sequence. ≥100
      iterations. Tag `Feature: FOR-05-02-rooms-dimensions, Property 3`. Unit: write-through to both
      localStorage stores; hydrate round-trip; per-project independence. **Validates: 7.1, 7.2, 7.3, 4.6, 4.7**

- [x] 6. API + save orchestration (`src/features/rooms/api`)
  - [x] 6.1 Add `createRoomsBulk`, `bulkUpdateRooms`, `useProjectRooms`, and `useBulkSaveRooms`
    - `createRoomsBulk(list)` → `POST /api/rooms/bulk`; `bulkUpdateRooms(items)` → `PUT /api/rooms/bulk`
      (`{id,data}[]`); `useProjectRooms(projectId)` → `fetchRooms({ size:200, filter:'project.id~in~<id>' })`
      (add a thin `filter?` passthrough to `fetchRooms` if absent, additive only). `useBulkSaveRooms`
      orchestrates create → update → delete (staged `deletedRoomIds`), each atomic; on full success
      `CLEAR` + refetch + success toast; on failure retain pending + localized error; surface
      partial-group failures clearly.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 10.3, 10.4_

- [x] 7. Interactive matrix panel + grid + editable cell
  - [x] 7.1 Implement `EditableCell` in `src/features/rooms/components/workspace/EditableCell.tsx`
    - When `canEdit`, click → controlled input seeded with the effective value; commit on blur/Enter
      (dispatch `EDIT_CELL`), cancel on Escape; validate with the rooms zod schema, flag invalid inline
      and block Save; show unsaved-cell styling; treat a pending value as `MANUAL` for display.
    - _Requirements: 4.1, 4.5, 3.3_
  - [x] 7.2 Implement `RoomDimensionsMatrix` in `src/features/rooms/components/workspace/RoomDimensionsMatrix.tsx`
    - Header (dimension col + unit col + one col per persisted room + per pending new room + Σ col); body
      one row per `DIMENSION_ROWS`; source-carrying cells via `MeasureValueCell`/`RoomSourceBadge`, plain
      cells as numbers, `null → '—'`; live Σ via `sumDimension` incl. pending; room header shows name,
      open-editor control (`/projects/:projectId/rooms/:roomId`), and delete control;
      `overflow-x-auto` + sticky first column.
    - _Requirements: 2.1, 2.4, 2.5, 2.6, 2.7, 2.8, 3.1, 3.2, 3.3, 9.6, 10.1_
  - [x] 7.3 Implement `NewRoomColumnHeader` (quick create)
    - "+" affordance (only when `canEdit`) dispatches `ADD_ROOM` (all-`0`); the new column header renders a
      **required** room-type selector (reference select); until chosen the column is flagged invalid and
      Save blocked; cells/label editable; draft lives in the new-rooms store.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.6_
  - [x] 7.4 Implement `RoomsMatrixTab` (the tab panel) in `src/features/rooms/components/workspace/RoomsMatrixTab.tsx`
    - `FC<ProjectTabProps>` (default export). `useProjectRooms` fetch with loading/empty/error states (no
      raw key); `isDraft = project.status==='DRAFT'`, `canEdit = isDraft && hasPermission('ROOMS','UPDATE')`;
      not-draft → read-only + `roomsSummary.readOnlyDraftHint`; init/hydrate the change-set store for the
      project; render the matrix + a toolbar (Save, Discard all, Undo, Redo, unsaved indicator) disabled
      when no pending changes or `!canEdit`; wire delete via `DeleteRoomDialog`; handle the locked-with-
      pending-changes case (read-only + message, no apply).
    - _Requirements: 1.3, 1.4, 1.5, 1.6, 4.3, 6.1, 7.4, 8.1, 8.2, 8.3, 8.5, 10.2_
  - [x] 7.5 * Component tests for the matrix
    - Inline edit updates the change set + live Σ; unsaved indicator; invalid value blocks Save;
      quick-create adds a column and requires a room type before Save; delete shows the confirm dialog
      (persisted → staged/committed on save; pending-new → dropped); draft-lock renders read-only + hint;
      Save success clears + refetches, Save error retains pending.
    - _Requirements: 4.1, 4.4, 4.5, 6.5, 6.6, 8.2, 9.2, 9.4, 10.2, 10.4_

- [x] 8. Checkpoint — frontend tsc + new test files
  - Run frontend `tsc` and the new Vitest files with `--run`. Ask the user if questions arise.
  - _Requirements: 2.5, 4.3, 7.1_

- [x] 9. Full-page room editor
  - [x] 9.1 Implement `RoomEditorPage` in `src/features/rooms/pages/RoomEditorPage.tsx`
    - Read `:projectId`/`:roomId`; hydrate room via `useRoom` and project via `useWorkingProject`; form via
      react-hook-form + `roomUpdateSchema`; reuse `RoomOpeningsEditor` + `RoomPlanPreview` + manual metrics;
      `canEdit = project?.status==='DRAFT' && hasPermission('ROOMS','UPDATE')`, else read-only + hint; save
      via `PUT /api/rooms/{id}` (per-field MANUAL override honored) → toast → back to the rooms tab; error
      retains values; unresolved/cross-project → `roomsSummary.notFound`; no description field.
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9_
  - [x] 9.2 * Component tests for `RoomEditorPage`
    - Editable in draft+UPDATE with reused mini-editor; read-only when locked or lacking UPDATE; save
      success/back; save error retains values; not-found/cross-project state; no description field.
    - _Requirements: 11.3, 11.5, 11.6, 11.7, 11.8, 11.9_

- [x] 10. Wire the tab, route, and route-permission
  - [x] 10.1 Swap the `rooms` tab `lazy` target in `src/features/project-workspace/workspaceTabs.ts`
    - `lazy` → `React.lazy(() => import('@/features/rooms/components/workspace/RoomsMatrixTab'))`; leave
      `key`/`stage`/`owner`/`requiredPermission` (`{ ROOMS, READ }`)/`labelKey` unchanged.
    - _Requirements: 1.1, 1.2_
  - [x] 10.2 Add the editor route + requirement
    - `React.lazy` import of `RoomEditorPage`; add `projects/:projectId/rooms/:roomId` in the pathless
      `PermissionGuard` child array wrapped in `SuspenseWrapper`; register
      `'/projects/:projectId/rooms/:roomId': { resource: 'ROOMS', operation: 'READ' }` in
      `route-permissions.ts` (4-segment template — no collision with the 3-segment `:tab`).
    - _Requirements: 11.1_
  - [x] 10.3 * Tests for the tab swap and route gating
    - The `rooms` tab renders `RoomsMatrixTab` (not the placeholder), contract unchanged; a user lacking
      `ROOMS`/`READ` at the editor route → `/403`; `/projects/:id/rooms` still resolves the rooms tab.
    - _Requirements: 1.1, 1.2, 11.1_

- [x] 11. i18n — `roomsSummary.*` namespace with a parity test
  - [x] 11.1 Add `roomsSummary.*` keys to `pl.json` and `ru.json`
    - Add the full `roomsSummary.*` namespace from design.md (summary keys + `unit.*` + `dimension.*` +
      edit-mode keys: save, discardAll, undo, redo, unsavedChanges, addRoom, newRoomLabel, selectRoomType,
      roomTypeRequired, deleteRoom, discardConfirm, saveSuccess, saveError) at parity, one active language
      at a time; reuse `rooms.form.*`, `rooms.source.*`, `rooms.delete.*` where they exist.
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_
  - [x] 11.2 * Write the i18n parity test
    - Following `rooms.i18n.test.ts`, assert identical `roomsSummary.*` key sets in `pl.json`/`ru.json`, all
      non-empty, no raw key.
    - _Requirements: 13.1, 13.6_

- [x] 12. Checkpoint — backend + frontend
  - Run the affected backend classes with `--tests` (temp log + JUnit XML) and the frontend `tsc` + new
    `--run` Vitest files; confirm all pass. Do NOT run the full backend suite unless asked. Ask the user if
    questions arise.
  - _Requirements: 5.2, 6.2, 2.5, 7.1, 13.6_

- [x] 13. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD
    report template). This is an API+UI spec: include **API tests against the Dockerized app** for the
    bulk endpoints (`POST /api/rooms/bulk` create, `PUT /api/rooms/bulk` heterogeneous update atomicity +
    rollback, per-field MANUAL override on a geometry room, project-scoping/ABAC) and **browser-engine
    scenarios** for the matrix (inline edit + live Σ, unsaved indicator, undo/redo, discard-all,
    quick-create with required room type, delete with confirm, bulk Save clears stores, draft-lock
    read-only at ACTIVE, per-project localStorage isolation) and the room editor. Result artifacts are MD
    reports with tables. Write test-cases.md in **Russian**, with an explicit repeatability strategy
    (per-run project/room label generator + teardown of created rooms/project via API and cleared
    `localStorage` keys `foremen.roomMatrix.edits.<pid>` / `foremen.roomMatrix.newRooms.<pid>` /
    `foremen.workingProjectId` / `foremen.projectTab.<pid>`).
  - _Requirements: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13_

- [x] 14. Final checkpoint — affected backend classes + frontend tsc/UI tests
  - Run the affected backend test classes with `--tests` (temp log + JUnit XML) and the frontend `--run`
    tests; confirm all pass. Do NOT run the full suite unless explicitly asked. Ask the user if questions
    arise.
  - _Requirements: 5.2, 6.2, 2.5, 7.1, 13.6_

## Notes

- Tasks marked `*` are optional (property/component/integration/parity tests).
- Backend footprint is generic (`AdminController.updateBulk` + `AdminService.update(List)` inherited by
  all controllers) plus the `RoomService` per-field override; both reuse the existing `ROOMS`
  `CREATE`/`UPDATE` operations, so no new ABAC resource/operation and the `entity-creation-rules`
  checklist does not apply (nothing new is a managed entity/resource).
- The only FOR-05-01 shell change is swapping the `rooms` tab `lazy` target; the tab contract is
  unchanged.
- The editor route is a 4-segment path so it does not collide with the 3-segment `:tab` route under the
  `requirementForPath` equal-segment matcher.
- Draft-only editability is a UI guard (`DRAFT` + `ROOMS`/`UPDATE`); no backend project-status
  enforcement is added and `/api/rooms` stays status-agnostic.
- Each bulk endpoint is individually atomic; the client keeps the pending change set on any failure so
  the user can retry (Save is idempotent from the client's perspective).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "4.1", "5.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.2", "4.2", "5.2"] },
    { "id": 2, "tasks": ["2.3", "3", "5.3", "6.1"] },
    { "id": 3, "tasks": ["7.1", "7.2", "7.3"] },
    { "id": 4, "tasks": ["7.4", "9.1"] },
    { "id": 5, "tasks": ["7.5", "8", "9.2", "10.1", "10.2", "11.1"] },
    { "id": 6, "tasks": ["10.3", "11.2", "12"] },
    { "id": 7, "tasks": ["13", "14"] }
  ]
}
```
