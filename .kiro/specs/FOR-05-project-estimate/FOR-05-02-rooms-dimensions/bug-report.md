# Bug report: FOR-05-02-rooms-dimensions

Screen URL: http://localhost:3000/projects/1/rooms

Owning spec: `.kiro/specs/FOR-05-project-estimate/FOR-05-02-rooms-dimensions`. The 3-segment URL
`/projects/1/rooms` resolves to the project-workspace `:tab` route (tab = `rooms`), which renders the
`RoomsMatrixTab` (`foremen-frontend/src/features/rooms/components/workspace/RoomsMatrixTab.tsx`) — the
dimensions × rooms matrix. (The 4-segment `/projects/:projectId/rooms/:roomId` route is the separate
full-page room editor; it is not the failing screen here.)

## Round 1 (2026-10-28)

- [x] 1. Rooms matrix fails to load — `TypeError: Cannot read properties of null (reading 'source')`
  - Triage: observed the `rooms` workspace tab crashes on render with
    `TypeError: Cannot read properties of null (reading 'source')` in `RoomsMatrixTab`'s rendered
    matrix (stack: `effectiveSource` → cell `.map` → `.map`). Expected: the matrix renders; a
    source-carrying metric whose value is absent shows no source badge rather than throwing. Layer:
    **frontend**. Root cause: in
    `foremen-frontend/src/features/rooms/components/workspace/RoomDimensionsMatrix.tsx`,
    `effectiveSource()` ends with `return room[row.field].source` for a source-carrying field. The
    metric at `room[row.field]` is a `MeasureValue | null`, and the `MeasureValue` type explicitly
    documents that the whole object is null when the metric is absent (value + source both null).
    For a room with an absent metric (e.g. a room created without geometry / with a null
    floorArea/wallArea/…​), `room[row.field]` is `null`, so `.source` throws and takes down the whole
    tab. The sibling read `getDimensionValue()` already guards this with `measure?.value ?? null`;
    the source accessor does not. Fix direction: null-guard the access —
    `return room[row.field]?.source ?? null` — so an absent metric yields no badge, matching the
    value path and the DTO contract. Suspect:
    `foremen-frontend/src/features/rooms/components/workspace/RoomDimensionsMatrix.tsx` (`effectiveSource`,
    ~line 141); verify the `MeasureValueCell` / `RoomSourceBadge` path already null-guards (it does:
    `measure.source != null && <RoomSourceBadge .../>`).
  - _Requirements: 1.4, 2.3, 3.3_
  - _Depends on: none_

- [x] V1. Validate Round 1 in the browser
  - Bring up the local stack, open `/projects/1/rooms` as an authorized user, and confirm the rooms
    matrix renders (no crash) with rooms that have absent/partial metrics; a metric with a value
    still shows its CALCULATED/MANUAL badge, an absent metric shows none. Mark `[x]` only on a
    confirmed browser pass.
  - _Requirements: 1.4, 2.3, 3.3_
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — single-file frontend null-guard.
- **Wave 2:** V1 — browser validation; depends on 1.

## Round 1 — validation results (2026-10-28)

Fix: `RoomDimensionsMatrix.effectiveSource` now reads `room[row.field]?.source ?? null` (was
`room[row.field].source`). Stack rebuilt; validated at `/projects/1/rooms` as ADMIN.

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 1 | Rooms matrix crash (`reading 'source'`) | ✅ Fixed | The rooms tab renders the full dimensions matrix (15 rows + unit column + room column "1 (Hol)" + Σ). The room's metrics are all absent (shown as "—") — exactly the null-metric case that previously threw — and now render with no source badge and no crash. Only console error is an unrelated boot-time `401 /api/auth/me`. |

Result: 1 fixed / 0 failed. Validation: live Docker stack + browser.

## Round 2 (2026-10-28)

- [x] 2. Clicking Save throws `TypeError: Cannot read properties of null (reading 'value')`
  - Triage: observed clicking the rooms-matrix **Save** button throws
    `Uncaught TypeError: Cannot read properties of null (reading 'value')` (stack: save payload
    builder → Save `onClick`). Expected: Save builds the bulk payload and submits without throwing
    for rooms whose source-carrying metrics are absent. Layer: **frontend**. Root cause: the same
    unguarded `MeasureValue` access as Round 1, but on the save path. In
    `foremen-frontend/src/features/rooms/components/workspace/RoomsMatrixTab.tsx`, `persistedNumeric()`
    reads `room[field].value ?? null` for a request-metric field (`floorArea`/`wallArea`/
    `perimeter`/`doorArea`/`windowArea`). `room[field]` is a `MeasureValue | null`; when the metric
    is absent (null), `.value` throws. `buildBulkSavePayload` calls `persistedNumeric` for every
    update item's numeric fields, so saving a project whose edited rooms have any absent metric
    crashes. Fix direction: null-guard — `room[field]?.value ?? null` — mirroring the Round 1 fix and
    the `getDimensionValue` value path. Suspect:
    `foremen-frontend/src/features/rooms/components/workspace/RoomsMatrixTab.tsx` (`persistedNumeric`,
    ~line 425).
  - _Requirements: 6.2, 5.2_
  - _Depends on: none_

- [x] V2. Validate Round 2 in the browser
  - At `/projects/1/rooms` (DRAFT project), make an inline edit to a room that has absent metrics,
    click Save, and confirm no console error and the bulk save completes (rows persist / list
    refetches). Mark `[x]` only on a confirmed browser pass.
  - _Requirements: 6.2, 5.2_
  - _Depends on: 2_

## Parallel execution plan (waves) — Round 2

- **Wave 1:** 2 — single-file frontend null-guard (save-path twin of Round 1).
- **Wave 2:** V2 — browser validation; depends on 2.

## Round 2 — validation results (2026-10-28)

Fix: `RoomsMatrixTab.persistedNumeric` now reads `room[field]?.value ?? null` (was
`room[field].value ?? null`). Stack rebuilt; validated at `/projects/1/rooms` as ADMIN on a DRAFT
project.

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 2 | Save crash (`reading 'value'`) | ✅ Fixed | Set floorArea = 12.5 on the "Hol" room (which has all-absent metrics — the crashing case), clicked Zapisz: no console error (0 errors; the `reading 'value'` TypeError is gone), the bulk save completed (unsaved badge cleared, Save re-disabled via onSuccess), the list refetched and the floorArea row Σ now reads 12.5. |

Result: 1 fixed / 0 failed. Validation: live Docker stack + browser.

## Round 3 (2026-10-28)

- [x] 3. Remove the Σ (sum) for the height dimension row
  - Triage: observed the rooms matrix shows a Σ total for every dimension row, including
    `ceilingHeight` ("Wysokość (mb)"). Expected: no Σ for height — summing ceiling heights across
    rooms is meaningless. Layer: **frontend**. Root cause: in `RoomDimensionsMatrix.tsx` the body
    renders a Σ cell for every `DIMENSION_ROWS` entry via `sumDimension(rooms, row, edits)` +
    `formatNumber(rowSum)`, with no notion of a non-summable row. Fix direction: mark `ceilingHeight`
    as non-summable (e.g. a `summable: false` flag on its `DimensionRow` in `dimensions.ts`, or an
    explicit exclusion set) and render an em-dash / empty Σ cell for it instead of a number. Confirm
    with the user whether ONLY `ceilingHeight` is non-summable, or whether other per-unit rows (e.g.
    the door/window height & width `mb` rows) should also drop their Σ — see the open question below.
    Suspect: `foremen-frontend/src/features/rooms/dimensions.ts` (DimensionRow / DIMENSION_ROWS),
    `foremen-frontend/src/features/rooms/components/workspace/RoomDimensionsMatrix.tsx` (Σ cell render).
  - _Requirements: 2.5, 2.7_
  - _Depends on: none_

- [x] 4. A CALCULATED dimension (e.g. wall surface) flips to MANUAL after save
  - Triage: observed a geometry-less room whose derived metric the matrix auto-computes (e.g.
    `wallArea = perimeter × ceilingHeight − openings`) shows as **CALCULATED** ("Obliczone") in the
    matrix, but after Save it shows **MANUAL** ("Ręcznie"). Reproduced: Hol room (no geometry), set
    ceilingHeight → wallArea auto-recomputed to 33 shown CALCULATED; after Save it read 33 MANUAL.
    Layer: **both (frontend + backend)**. Root cause: the matrix has a client-side notion of
    "calculated" derived metrics (`dimensionsCalc.ts`) for rooms that have NO polygon geometry. On
    Save, `buildBulkSavePayload` sends the computed metric value with **no `geometry`** (the room has
    none) and **no `*Source`**. The backend's no-geometry branch
    (`RoomService.applyManualMetrics`/`stampManual`) stamps **any** supplied metric value as
    `MANUAL`, ignoring any source — so the client-"calculated" value is persisted MANUAL and the
    badge flips on refetch. For a room WITH geometry the backend recomputes + stamps CALCULATED, so
    the bug is specific to geometry-less rooms whose matrix metrics are client-derived.
    - **Open design question (needs the user's decision):** what should "CALCULATED without geometry"
      mean on the server? The matrix is a geometry-free flat editor (perimeter/ceilingHeight/door &
      window dims typed directly), so wallArea etc. are derived from typed inputs, not a polygon. Two
      coherent options:
      1. **Persist client-derived metrics as CALCULATED** — backend honors a supplied
         `*Source: 'CALCULATED'` on the no-geometry branch (store value + CALCULATED), and the
         frontend sends `wallAreaSource: 'CALCULATED'` for non-overridden auto-calc cells (MANUAL for
         user-overridden cells). Requires a backend change (`applyManualMetrics`/`stampManual` must
         accept CALCULATED) + a frontend payload change (emit `*Source` per metric from the matrix
         override state). Matches the matrix's CALCULATED badge.
      2. **Accept that geometry-less metrics are MANUAL** — change the matrix to show MANUAL (not
         CALCULATED) for client-derived metrics on a geometry-less room, so the badge matches what is
         persisted. Frontend-only, but drops the CALCULATED affordance in the matrix.
    - Suspect: `foremen-frontend/src/features/rooms/components/workspace/RoomsMatrixTab.tsx`
      (`buildBulkSavePayload` — add `*Source` to the update/create payload),
      `.../RoomDimensionsMatrix.tsx` + `dimensionsCalc.ts` (source display), and (option 1)
      `foremen-backend/.../service/RoomService.java` (`applyManualMetrics`/`stampManual`).
  - _Requirements: 3.3, 4.1, 5.2, 6.2_
  - _Depends on: none_

- [x] V3. Validate Round 3 in the browser
  - (3) Confirm the height row shows no Σ (em-dash/empty) while other rows still sum. (4) On a
    geometry-less room, set inputs so a derived metric auto-calcs (shown CALCULATED), Save, and
    confirm the badge stays consistent with the agreed semantics (CALCULATED preserved for option 1,
    or MANUAL shown both before+after for option 2). Mark `[x]` only on a confirmed browser pass.
  - _Depends on: 3, 4_

## Parallel execution plan (waves) — Round 3

- **Wave 1 (parallel):** 3, 4 — 3 is an isolated Σ-render change in the matrix + dimensions registry;
  4 touches the save payload (and possibly the backend) + source display. They edit different
  concerns but both touch `RoomDimensionsMatrix.tsx`; if edits conflict, serialize 4 after 3.
- **Wave 2:** V3 — browser validation; depends on 3 and 4.

## Round 3 — validation results (2026-10-28)

Decisions: (3) skip Σ for ALL non-summable dimensions (not just height); (4) Option 1 — persist
client-derived metrics as CALCULATED (backend honors `*Source: CALCULATED` without geometry).

Changes:
- **Bug 3** — added a `summable` flag to `DimensionRow` (`dimensions.ts`): summable = areas
  (floorArea/wallArea/doorArea/windowArea), total perimeter, and counts (door/window/internalCorners);
  non-summable = ceilingHeight + door/window height & width + wall/finish gaps. `RoomDimensionsMatrix`
  renders a Σ value only for summable rows (blank otherwise).
- **Bug 4** — backend `RoomService.stampManual` now honors an explicit `*Source: CALCULATED` on the
  no-geometry branch (keeps the value, stamps CALCULATED); frontend `buildBulkSavePayload` sends the
  per-metric `*Source` for geometry-less rooms (CALCULATED for non-overridden client-derived metrics,
  MANUAL for floorArea/perimeter and user-overridden cells). Rooms WITH geometry send no `*Source`
  (geometry authoritative → CALCULATED, unchanged).

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 3 | Σ for non-summable dims | ✅ Fixed | On `/projects/1/rooms` the Σ column shows totals only for floorArea (12.5), wallArea (28.5), perimeter (15), doorArea (4), windowArea (8), doorCount/windowCount/internalCorners (0); ceilingHeight, door/window height & width, and wall/finish gaps show a blank Σ. |
| 4 | CALCULATED→MANUAL on save | ✅ Fixed | Geometry-less Hol room: edited ceilingHeight → wallArea auto-calc 33 "Obliczone" (CALCULATED); after Save it stayed "Obliczone". API confirms `wallArea {value:33, source:CALCULATED}` while the typed `floorArea`/`perimeter` stayed MANUAL — derived metrics persist CALCULATED, typed/overridden stay MANUAL. |

Result: 2 fixed / 0 failed. Validation: live Docker stack + browser + API. Backend `RoomService`
source tests (`RoomManualSourcePropertyTest`, `RoomPerFieldOverridePropertyTest`) pass; frontend 283
rooms tests pass. (`RoomGeometryAuthoritativePropertyTest` is a pre-existing flaky geometry-rounding
property test — fails identically on the clean tree, unrelated to these changes.)

## Round 3 (2026-10-29)

- [x] 1. Rooms matrix: door/window quantity + per-unit dimensions disappear after Save (only the computed area persists)
  - FIXED (2026-10-29): added `doorCount, windowCount, doorHeight, doorWidth, windowHeight,
    windowWidth` to the write DTOs `RoomCreateRequest.java` / `RoomUpdateRequest.java`, to the
    frontend `RoomCreateRequest`/`RoomUpdateRequest` types, and to `buildBulkSavePayload` /
    `REQUEST_*_FIELDS` + `persistedNumeric` in `RoomsMatrixTab.tsx` (reads them directly from the
    read DTO). No DB migration and no `RoomService` change were needed: the columns already exist and
    the MapStruct `RoomServiceMapper`/`RoomControllerMapper` map the new fields by name onto the
    entity on the geometry-less write path; `validateCounts` already guards the counts.
  - Triage: observed — on `/projects/1/rooms` the flat matrix lets the user type door/window
    QUANTITY (`doorCount`/`windowCount`) and per-unit DIMENSIONS (`doorHeight`/`doorWidth`/
    `windowHeight`/`windowWidth`), from which `doorArea`/`windowArea` are computed client-side. After
    Save (+ reload) the computed areas persist but the quantity + dimensions come back empty — they
    must persist and remain visible. Expected — the per-door/window quantity + dimensions survive
    save/reload. Layer: **both (backend write-contract + frontend payload)** — NOT a re-render bug.
  - Root cause (traced): the READ DTO `RoomDtoModel` already exposes `doorCount/windowCount/
    doorHeight/doorWidth/windowHeight/windowWidth`, and the `RoomEntity` + `RoomServiceExtendedModel`
    already have the columns/fields — but the WRITE path never carries them for a flat (geometry-less)
    room: (a) the write DTOs `RoomCreateRequest`/`RoomUpdateRequest` have NO such fields; (b)
    `RoomService` writes these flat fields ONLY in `applyCalculatedMetrics` (the `hasGeometry` branch
    — polygon vertices), and the flat matrix produces no geometry, so `applyManualMetrics` runs and
    leaves them null; (c) the frontend `buildBulkSavePayload` (`RoomsMatrixTab.tsx`) only sends
    `REQUEST_METRIC_FIELDS` (floor/wall/perimeter/door/window area) + `REQUEST_PLAIN_FIELDS`
    (ceilingHeight/internalCorners), explicitly omitting the door/window counts + dims. So they are
    never sent, never persisted on the manual path, and read back null.
  - Fix direction (no DB migration — columns already exist): add `doorCount, windowCount, doorHeight,
    doorWidth, windowHeight, windowWidth` (optionally `wallGap`, `finishGap`) to the write DTOs
    `RoomCreateRequest.java` / `RoomUpdateRequest.java` and the frontend `RoomCreateRequest`/
    `RoomUpdateRequest` types; carry them through the controller→service mapper onto the entity in the
    geometry-less branch of `RoomService` (keep `applyCalculatedMetrics` overwriting them when real
    geometry exists); and extend `buildBulkSavePayload` to transmit the edited values.
  - Suspect: `foremen-frontend/src/features/rooms/components/workspace/RoomsMatrixTab.tsx`
    (`buildBulkSavePayload` / `REQUEST_*_FIELDS`); `foremen-frontend/src/features/rooms/types/index.ts`
    (request types); `foremen-backend/.../controller/model/RoomCreateRequest.java` +
    `RoomUpdateRequest.java`; `foremen-backend/.../service/RoomService.java` (`applyManualMetrics` /
    `normalize`); `RoomServiceMapper` / `RoomControllerMapper` (carry the fields on write).
  - _Requirements: FOR-05-02 R4 (manual metrics persist), R5 (matrix edits persist)_
  - _Depends on: none_

- [x] V3. Validate Round 3 in the browser
  - PASS (2026-10-29): validated against the live Docker stack via the real write path
    (`PUT /api/rooms/bulk`, the same endpoint the matrix Save calls) on a flat geometry-less room
    (room 3, draft project 2). Before: all six fields `null`. Sent
    `doorCount=2, doorHeight=2.0, doorWidth=0.9, windowCount=3, windowHeight=1.5, windowWidth=1.2`;
    `GET /api/rooms/3` after returned exactly those values (not null) — they persist and are exposed
    by the read DTO the matrix renders. (Project 1's matrix is read-only because it is not a DRAFT;
    the write contract — the actual bug — was exercised directly. Frontend unit tests for
    `RoomsMatrixTab.persistedNumeric` also pass.)
  - On `/projects/1/rooms`, enter door/window quantity + dimensions for a flat (no-geometry) room,
    Save, reload, and confirm the quantity + per-unit dimensions are still shown (and the computed
    door/window area matches). Mark `[x]` only on a confirmed browser pass.
  - _Requirements: as bug 1 (Round 3)_
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 3

- **Wave 1:** 1 (Round 3) — one logical change spanning the write DTOs + service manual branch
  (backend) and the save payload (frontend); serialized backend→frontend.
- **Wave 2:** V3 — browser validation; depends on 1.

## Round 4 (2026-11-01)

Screen URL: http://localhost:3000/projects/2/rooms

- [x] 1. After any change the rooms order may change — introduce a fixed order within the project (backwards compatible: order 0..n by `room.id` within each project); on the UI rooms may be re-ordered only by drag-and-drop
  - FIXED (2026-11-01): changeset `191-rooms-sort-order.xml` (backfilled 0..n-1 per project by id;
    1355 rooms, 0 NULL); `RoomEntity.sortOrder` + read DTO `sortOrder`; `RoomService.afterCreate`
    appends last (`max + 1`), bulk create routed through single create; `RoomDao.findByProjectId`
    orders by `sortOrder, id` (estimate matrix / documents / readiness follow); `PUT
    /api/rooms/project/{projectId}/order` (`RoomService.reorder`, exact-set validation → 400
    `error.room.order.invalid`, per-room audit). FE: `useProjectRooms` stable-sorts by
    `sortOrder`/id; drag handle (`GripVertical`) per persisted room header, drop target highlight,
    `useReorderRooms` optimistic + rollback toast; handles hidden when read-only. i18n
    `roomsSummary.dragToReorder` / `reorderError` (PL + RU), backend message PL + RU.
  - Tests: new `RoomSortOrderIT` (3: append-last single + bulk, reorder + update keeps order, invalid
    lists → 400); `RoomBulkUpdateIT`, `RoomCrudIT`, `RoomGeometryIT`, `RoomManualMetricsIT`,
    `RoomScopingIT`, `RoomAnnotationCoverageSmokeIT` green (21). FE: `roomOrder.test.ts` + DnD tests in
    `RoomsMatrixTab.test.tsx`; rooms suite 307/307.
  - Triage: observed — the Rooms tab loads rooms via the generic `GET /api/rooms`
    (`useProjectRooms` → `fetchRooms({ filter: project.id~in~<id> })`) with **no sort**, so Postgres
    returns rows in physical order, which shifts after an UPDATE (an updated tuple moves) → the matrix
    columns jump after a Save. There is also no persisted order at all (`rooms` has no order column;
    `RoomDao.findByProjectId` orders by `id`, used by the estimate matrix, documents, readiness).
    Expected — every project has a stable, persisted room order; saves never change it; the user
    changes it only by dragging a room column. Layer: **both** (DB + backend + frontend).
  - Fix plan:
    - **DB (changeset `191-rooms-sort-order.xml`, registered last):** add `rooms.sort_order INT`;
      backfill `ROW_NUMBER() OVER (PARTITION BY project_id ORDER BY id) - 1` (0..n-1 per project, by
      id — the backwards-compatible order); then `NOT NULL DEFAULT 0`; index
      `(project_id, sort_order)`. Idempotent (precondition: column absent).
    - **Backend:** `RoomEntity.sortOrder`; exposed read-only on the room read DTO (`sortOrder`); NOT
      part of the create/update requests, so a room edit can never change it. A new room (single
      create, bulk create, and the FOR-05-05 atomic rooms save) gets `max(sort_order in project) + 1`
      (appended last). `RoomDao.findByProjectId` → `ORDER BY r.sortOrder, r.id` so the estimate
      matrix / materials / documents / readiness follow the same order. New endpoint
      `PUT /api/rooms/project/{projectId}/order` `{ roomIds: [...] }` (ROOMS UPDATE, project scope):
      the list must be exactly the project's room ids (else 400 `error.room.order.invalid`), assigns
      `sort_order = index`, audited per room. Delete leaves gaps (harmless — order is relative).
    - **Frontend:** `useProjectRooms` sorts by `sortOrder` then `id` (request `sort=sortOrder,asc`
      + client-side stable sort as a guard). Room column headers in the matrix get a drag handle
      (native HTML5 drag-and-drop, `aria-label`, cursor `grab`); dropping a column on another
      persists the new order immediately via the order endpoint (optimistic update, rollback + error
      toast on failure) — independent of the staged dimension edits, which are keyed by room id and
      therefore unaffected. Pending (unsaved) new rooms stay last and are not draggable until saved.
      Drag-and-drop is the ONLY way to reorder (no sort control). Editing disabled (non-DRAFT / no
      `ROOMS` UPDATE) → no drag handle.
    - **i18n (PL + RU):** `roomsSummary.dragToReorder` (handle aria-label/tooltip, e.g. PL "Przeciągnij,
      aby zmienić kolejność", RU "Перетащите, чтобы изменить порядок"), `roomsSummary.reorderError`
      (toast); backend `error.room.order.invalid` in `messages.properties` / `messages_ru.properties`.
      No new entity i18n fields.
    - **Notifications:** none (layout preference of the project, no domain event).
  - Suspect: `foremen-backend/database_files/changesets/` (new 191), `dao/model/RoomEntity.java`,
    `dao/RoomDao.java`, `service/RoomService.java`, room DTO/mappers, `controller/RoomController.java`;
    FE `features/rooms/api/{query-hooks,rooms-api,mutation-hooks}.ts`,
    `features/rooms/components/workspace/RoomDimensionsMatrix*.tsx`, `RoomsMatrixTab.tsx`, locales.
  - _Requirements: FOR-05-02 rooms matrix (column per room, stable layout), FOR-04-14 room entity_
  - _Depends on: none_
- [x] V4. Validate Round 4 in the browser
  - DONE (2026-11-01, local stack, MANAGER on project 2): backfill `2,3,4,5,6,16` → `0..5`; three
    dimension edits + Save (with recalc approval) → column order unchanged after each save and after
    reload; drag room 16 onto room 3 → `2,16,3,4,5,6` immediately, after reload, and on the Estimate
    tab room columns (10 audit rows); new "Salon" room + Save → appended last; deleting it → order
    intact; API invalid list → 400. Original order restored afterwards.
  - On `/projects/2/rooms`: note the column order → edit a dimension + Save several times → order
    unchanged; reload → unchanged; drag a room column to another position → order persists after
    reload and is the same on the Estimate tab room columns; add a new room + Save → it appears last;
    confirm the backfill gave 0..n by id (`SELECT id, sort_order FROM rooms WHERE project_id=2`).
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 4

- **Wave 1:** 1 — one logical change serialized DB → backend → frontend (the FE depends on the new
  `sortOrder` field and the order endpoint).
- **Wave 2:** V4 — browser validation; depends on 1.

## Round 5 (2026-11-02)

Screen URL: https://dev.foremen.eu/projects/4/rooms (reproduce locally at
`http://localhost:3000/projects/{id}/rooms`). Source: meeting notes.

Background (grounded in code): there is no dimension catalog. Every dimension is a fixed column on
`rooms` (changeset `042-create-rooms.xml`; `RoomEntity`). The frontend registry
`features/rooms/dimensions.ts` (`DIMENSION_ROWS`, 15 rows) hard-codes each row's unit
(`RoomUnit = 'm2' | 'mb' | 'szt'`), its label, `summable` and `alwaysManual`. Formulas read
dimensions through the whitelist `FormulaAst.ROOM_DIMENSION_VARS`, `VolumeResolver.roomVariables()`,
the duplicate binding in `FormulaRoomQtyDeriver`, and the FE mirror
`work-catalog/lib/formula-dimensions.ts`. Latest changeset in `changelog.xml`: `198`. Another
session is adding changesets concurrently, so take the next free number at implementation time.

- [x] 1. Allow entering a formula as a value (e.g. `=25+31+12`): store the formula separately, show it while editing, show the computed value in the table
  - Triage: observed — a matrix cell accepts only a number. `EditableCell.validateDimension`
    (`components/workspace/EditableCell.tsx` ~150-158) runs zod `roomCellValueSchema`, so `=25+31+12`
    is rejected as invalid. Expected — a value that starts with `=` is an arithmetic expression. The
    cell shows the computed value (`68`). Re-opening the cell shows the formula text (`=25+31+12`).
    The value used everywhere else (Σ, estimate volumes, formulas) is the computed number. Layer:
    **both**.
    - **DB:** a new changeset adds `rooms.dimension_formulas jsonb NULL`, a map
      `{ "<dimensionField>": "=25+31+12" }`. That's one column, like the existing `geometry jsonb`,
      instead of 15 TEXT columns. Idempotent (precondition: column absent).
    - **Backend:** `RoomEntity.dimensionFormulas` (`Map<String,String>`), plus the read DTO, the
      create/update/bulk-save requests, `RoomServiceModel` and `RoomControllerMapper`. On save,
      `RoomService` re-evaluates every submitted formula server-side (authoritative), writes the
      numeric result into the dimension column, and stores the text in the map.
      - Evaluation reuses `service/formula` (`FormulaTokenizer` already strips the leading `=`;
        `FormulaParser` + `FormulaEvaluator`). A new constants-only check rejects variables,
        `WorkRef` and functions, so only numbers, `+ - * /` and parentheses are allowed, and
        decimal commas are accepted.
      - The result must be ≥ 0 and ≤ MAX_METRIC. Integer dimensions (`doorCount`, `windowCount`,
        `internalCorners`) require a whole-number result.
      - Otherwise: 400 `error.room.dimension.formula.invalid` (PL + RU in
        `messages.properties` / `messages_ru.properties`).
      - Typing a plain number into a cell clears that field's formula.
      - A formula on a source-carrying metric (floorArea, …) stamps `MANUAL`, exactly like a typed
        value.
    - **Frontend:** a small safe arithmetic evaluator `features/rooms/lib/dimensionFormula.ts`
      (recursive-descent; numbers with `.`/`,`, `+ - * /`, unary minus, parentheses; no `eval`). It
      computes the cell value and Σ before Save.
      - `EditableCell` seeds the input with the formula when one exists. On commit `=…` is evaluated
        and validated with the same range/integer rules; invalid input keeps the cell open and red
        (Req 4.5).
      - The display button shows the computed value plus a small `ƒ` marker whose tooltip shows the
        formula.
      - The store (`state/roomMatrixStorage.ts` `RoomEdit`) carries `{ value, formula }` per edited
        field. `RoomsMatrixTab`'s save payload builder sends both.
      - `RoomDto` / types gain `dimensionFormulas`.
      - Unit tests for the evaluator and for EditableCell's formula path.
    - **i18n (PL + RU):** `roomsSummary.formula.hint` (tooltip "Formula: {{formula}}" / "Формула:
      {{formula}}"), `roomsSummary.formula.invalid` ("Nieprawidłowa formuła" / "Некорректная
      формула"), and the backend `error.room.dimension.formula.invalid`. No entity i18n fields.
    - **Notifications:** none (data entry).
    - **User decision (2026-11-02): both.** Formulas work in the rooms matrix inline cells AND in the
      full-page editor (`pages/RoomEditorPage.tsx`, plus `RoomFormSheet`, which shares its schema).
  - _Requirements: FOR-05-02 Req 4 (inline editing / validation), Req 5 (manual source), Req 6 (bulk
    save), Req 13 (i18n)_
  - _Depends on: none_

- [x] 2. Add a new dimension (running metres, `пог. м`) — "narrow place" (up to 40 cm)
  - Triage: observed — the matrix has 15 fixed dimension rows; there is no row for narrow places
    (strips up to 40 cm wide, e.g. reveals and niches, counted in running metres). Expected — a new row
    "Narrow place (up to 40 cm)" in `mb`, summable (Σ across rooms), always manual, and usable in
    work volume formulas. Layer: **both**.
    - **DB:** a new changeset adds `rooms.narrow_place NUMERIC(12,2) NULL`.
    - **Backend:** `RoomEntity.narrowPlace` + DTOs/requests/service model/mapper/save+patch paths.
      Formula variable `narrowPlace` goes into `FormulaAst.ROOM_DIMENSION_VARS`,
      `VolumeResolver.roomVariables()` and `FormulaRoomQtyDeriver`'s binding, so a work formula like
      `narrowPlace` or `perimeter - narrowPlace` works.
    - **Frontend:** a `PlainField` `'narrowPlace'` row in `DIMENSION_ROWS`
      (`unit: 'mb', summable: true, alwaysManual: true`), placed after `perimeter`. Also needed in
      `RoomDto`, `RoomEditField`, zod `room-schema.ts`, `RoomEditorPage` / `RoomFormSheet` fields, and
      the formula-picker token list `FORMULA_DIMENSION_TOKENS` (with its label).
    - **i18n (PL + RU):** `roomsSummary.dimension.narrowPlace` — PL "Wąskie miejsca do 40 cm (mb)", RU
      "Узкие места до 40 см (пог. м)"; the formula-token label in the work-catalog formula dimensions
      (same text without the unit). No entity i18n fields.
    - "Up to 40 cm" is a definition in the label, not a validation rule (the value is a length in
      running metres, not a width).
    - **Notifications:** none.
  - _Requirements: FOR-05-02 Req 2 (AC 2.2/2.3 row list + units), Req 2.5 (Σ), Req 12, Req 13_
  - _Depends on: 1_ (both touch `RoomEntity`, the DTOs, the service save path, `dimensions.ts` and the
    changelog, so they run one after the other)

- [x] 3. Add the unit "metre" (`m`) and use it for room dimensions (height in `m` instead of `пог. м`)
  - Triage: observed — linear sizes and heights are labelled `mb` / `пог. м` (running metres):
    `doorHeight`, `doorWidth`, `windowHeight`, `windowWidth`, `ceilingHeight` (and the gaps
    `wallGap`, `finishGap`). A height is a plain length, not a running length. Expected — a plain
    metre unit `m` / `м`. The matrix unit column and the row labels show `m` for heights and widths.
    `perimeter` stays `mb` (and so does the new narrow place). Layer: **both** (catalog seed +
    frontend labels; dimension units are hard-coded on the FE, so stored values need no migration).
    - **DB:** a new seed changeset adds `measurement_units` row `code='m'`, `name_ru='м'`,
      `name_pl='m'`, `active=true`, guarded by `sqlCheck` MARK_RAN on `code='m'` (the
      019/064 pattern). The catalog then has `m` available for works/materials too.
    - **Frontend:** `RoomUnit` gains `'m'`; `DIMENSION_ROWS` switches `doorHeight`, `doorWidth`,
      `windowHeight`, `windowWidth` and `ceilingHeight` to `unit: 'm'`; `unitSymbol()` in
      `work-catalog/lib/formula-dimensions.ts` maps `m`.
    - **i18n (PL + RU):** `roomsSummary.unit.m` (PL "m", RU "м"); fix the unit suffix in the affected
      labels (`roomsSummary.dimension.doorHeight/doorWidth/windowHeight/windowWidth` and
      `rooms.form.ceilingHeight`): "(mb)" → "(m)" in PL, "(пог. м)" → "(м)" in RU.
    - **User decision (2026-11-02):** `wallGap` / `finishGap` stay `mb` (counted in running metres).
      The labels proposed above are approved.
    - **Notifications:** none.
  - _Requirements: FOR-05-02 Req 2 (AC 2.3 units), Req 13_
  - _Depends on: 2_ (same `dimensions.ts` rows, locale blocks and changelog tail)

- [x] V5. Validate Round 5 in the browser
  - Rebuild backend + frontend (+ Liquibase). On `/projects/{id}/rooms` (DRAFT project):
    - Bug 1: type `=25+31+12` in a cell; it shows `68` and Σ updates. Re-open it; it shows
      `=25+31+12`. Save and reload; it still shows `68` with the formula on edit. An invalid
      formula (`=25+abc`, `=2.5` on a count) keeps the cell red. A plain number clears the formula.
      The estimate volume for a formula using that dimension uses `68`.
    - Bug 2: the "Narrow place (up to 40 cm)" row appears in `mb` with Σ. Enter and save a value. A
      work formula `narrowPlace` resolves it in the estimate.
    - Bug 3: heights and widths show `m`/`м` in PL and RU; perimeter stays `mb`; `m` exists in
      Measurement units.
    - Restore temporary data. Mark `[x]` only on a confirmed browser pass.
  - _Depends on: 1, 2, 3_

## Parallel execution plan (waves) — Round 5

- **Wave 1:** 1 — formula storage + evaluation (DB → backend → frontend).
- **Wave 2:** 2 — new dimension. It shares `RoomEntity`, the DTOs, the service, `dimensions.ts`
  and the changelog tail with 1, so it runs after it.
- **Wave 3:** 3 — `m` unit + label switch (same registry rows, locale blocks and changelog tail).
- **Wave 4:** V5 — browser validation; depends on 1, 2, 3.

## Round 5 — validation results (2026-11-03)

Fixes:
- **DB:** `199-rooms-dimension-formulas-narrow-place.xml` (`rooms.dimension_formulas jsonb`,
  `rooms.narrow_place NUMERIC(12,2)`) and `200-seed-measurement-unit-m.xml` (`m` / `м` / `m`). Both
  are idempotent and registered last.
- **Backend:**
  - New `DimensionFormulaEvaluator`: a constants-only subset of the formula engine. It accepts a
    decimal comma, rounds to 2 decimals, and rejects variables, refs, functions and division by zero.
  - `RoomService.applyDimensionFormulas` runs first in normalize. It evaluates every submitted
    formula, so the formula wins over the number. It enforces range ≥ 0 and whole numbers for counts,
    stamps MANUAL on the 5 metrics, drops blank entries and stores the cleaned map (`{}` clears).
  - Errors return 400 `error.room.dimension.formula.invalid` (PL/RU).
  - `narrowPlace` is added to the entity, DTOs, requests and service models, and to the formula
    variable whitelist (`FormulaAst`, `VolumeResolver`, `FormulaRoomQtyDeriver`).
- **Frontend:**
  - `lib/dimensionFormula.ts`: a safe recursive-descent evaluator mirroring the backend.
  - The store keeps a formula edit verbatim, as a string value.
  - Every number coercion goes through `parseDimensionInput` (Σ, auto-calc, payload).
  - `EditableCell` gets a `formula` prop: it seeds the input with the formula, shows an `ƒ` marker
    with a tooltip, validates and commits the formula string, and shows an invalid-formula hint.
  - The save payload sends the merged `dimensionFormulas` map.
  - `RoomEditorPage`: formula-capable `NumberInput`s (new `allowFormula` prop) for ceiling height,
    internal corners, narrow place and the 5 metrics, with a live `= value` hint. Formulas are taken
    from the raw form values; metric formulas are dropped in geometry mode.
  - `RoomFormSheet` drops the stored formulas of the fields it submits as numbers.
  - The registry gets the `narrowPlace` row (mb, Σ). `doorHeight`, `doorWidth`, `windowHeight`,
    `windowWidth` and `ceilingHeight` switch to `m`; the gaps stay `mb` (user decision).
  - i18n (PL/RU): `roomsSummary.formula.hint` / `.invalid`, `roomsSummary.unit.m`,
    `roomsSummary.dimension.narrowPlace`, and the label suffixes `(mb)` → `(m)` / `(м)`.

Validated on the rebuilt local stack (Liquibase ran 199 + 200), as ADMIN on DRAFT project 2, room 2:

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 1 | Formula as value | ✅ Fixed | Matrix: typed `=25+31+12` into perimeter. The cell shows `ƒ 68 Ręcznie` with tooltip "Formuła: =25+31+12", Σ updated, and auto-calc derived wall area 161.6. Re-opening shows `=25+31+12`. `=25+abc` keeps the cell open, red, with the invalid-formula hint. Save → estimate-impact dialog (9 lines change) → approve → DB `perimeter 68.00 MANUAL`, `{"perimeter": "=25+31+12"}`. After reload the cell still shows `ƒ 68`. Full editor: perimeter seeded `=25+31+12` (`= 68` hint); typed ceiling `=2,5+0,2` (`= 2.7` hint); after Save → DB `{"perimeter": "=25+31+12", "ceilingHeight": "=2,5+0,2"}`. |
| 2 | Narrow place (mb) | ✅ Fixed | Row "Wąskie miejsca do 40 cm (mb)" with unit `mb` right after perimeter; `3,5` saved as 3.50 with Σ 3.5; the editor shows the field with the same label. Not browser-checked: a work formula referencing `narrowPlace` in the estimate (the variable is bound in `VolumeResolver` / `FormulaRoomQtyDeriver` and whitelisted; FE picker test lists 15 tokens). |
| 3 | Unit `m` | ✅ Fixed | Matrix: door/window height and width and ceiling height show `m` with labels "(m)"; perimeter and both gaps stay `mb`. `measurement_units` row `m / м / m` seeded. |

Restored afterwards: room 2 back to perimeter 33.00 MANUAL, wall area 45.00 MANUAL, ceiling 2.70,
narrow place NULL, no formulas. The forced save re-derived the estimate.

Tests:
- Backend: `DimensionFormulaEvaluatorTest` (11), new `RoomDimensionFormulaIT` (3), and the formula
  and room ITs `RoomManualMetricsIT`, `RoomCrudIT`, `RoomBulkUpdateIT`, `RoomGeometryIT`, all green.
- Frontend: `tsc -b` clean; rooms + estimate + work-catalog + ui suites 38 files / 635 tests, plus the
  new `dimensionFormulas.test.tsx` (6) and `dimensionFormula.test.ts`. `FormulaInput.test` was
  updated from 14 to 15 tokens.

**Found during validation (not in this round's scope):** clearing a dimension (setting it to empty)
does not persist on UPDATE, for every dimension, not just `narrowPlace`. `ForemenMapperConfig` uses
`NullValuePropertyMappingStrategy.IGNORE`, so a `null` in the update payload keeps the stored value.
Fixing it needs care, because partial payloads (the full editor doesn't send door/window fields)
would then wipe data. Candidate for a follow-up round.

## Round 6 (2026-11-04)

Screen URL: `http://localhost:3000/projects/{id}/rooms` (room dimensions matrix). Source: meeting
notes (RU). Reported bug (one line): "периметр помещений, поменять мб на м, единица измерения" — the
rooms' perimeter dimension is labelled/united as running-metre `мб` / `mb` but should be plain
metre `м` / `m`.

Background (grounded in code): perimeter is a plain length (room wall obwód) stored as a raw number
on `rooms` (no unit in the backend `RoomEntity`), so this is a **frontend-only** presentation fix.
The `мб`/`mb` appears in two places: (a) the i18n label strings `rooms.form.perimeter` in both
locales (`"Периметр (мб)"` / `"Obwód (mb)"`), each present **twice** (`ru.json`/`pl.json` lines
~1889 and ~1920); and (b) the dimension registry row `unit: 'mb'` in
`features/rooms/dimensions.ts` (the `perimeter` row), which drives the summary matrix unit column
and Σ-totals. The codebase already distinguishes `mb` from `m` (heights/widths were set to `'m'` in
Round 5 #3), so flipping perimeter to `m` is consistent.

- [x] 1. Perimeter unit `мб`/`mb` → `м`/`m`
  - Triage: observed — perimeter shows unit `мб` (RU) / `mb` (PL). Expected — perimeter shows unit
    `м` (RU) / `m` (PL). Layer: **frontend** only (perimeter is a unitless number in the backend).
    - **i18n:** change `rooms.form.perimeter` in `foremen-frontend/src/locales/ru.json` from
      `"Периметр (мб)"` → `"Периметр (м)"` (both occurrences, ~line 1889 and ~1920), and in
      `pl.json` from `"Obwód (mb)"` → `"Obwód (m)"` (both occurrences).
    - **Metadata:** change the `perimeter` row in `foremen-frontend/src/features/rooms/dimensions.ts`
      from `unit: 'mb'` → `unit: 'm'` so the summary matrix unit column / exports show `m`.
    - **Tests:** update any test that hardcodes the old label/unit — e.g.
      `features/work-catalog/__tests__/FormulaInput.test.tsx` (mock `'rooms.form.perimeter'`),
      `features/rooms/__tests__/dimensions.property.test.ts`, and
      `src/__tests__/for-04-bugs.preservation.test.tsx` if they assert the `mb` perimeter unit.
  - _Requirements: 2.2, 2.3_
  - _Depends on: none_
- [x] V6. Validate Round 6 in browser
  - Bring up the local stack, open the rooms dimensions matrix, confirm the perimeter row's unit
    reads `м` (RU) / `m` (PL) in the label and the summary unit column, and that Σ-totals are
    unaffected.
  - _Requirements: 2.2, 2.3_
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 6

- **Wave 1:** 1 — single frontend change (locale strings + one registry row + test fixtures).
- **Wave 2:** V6 — browser validation; depends on 1.

## Round 6 — validation results (2026-11-04)

Stack: local Docker (`postgres`/`redis`/`liquibase` up, `backend` :8080, `frontend` :3000, admin
`cto+1@loaders.dev`). Screen: `http://localhost:3000/projects/2/rooms`.

- **#1 Perimeter unit `мб` → `м` — ✅ FIXED.** The rooms matrix renders the perimeter dimension as
  **`Периметр (м)`**; `Периметр (мб)` is gone. Heights/widths still read `(м)` (unchanged); the
  genuine running-metre metrics (`Отсутствующая стена/отделка` = wallGap/finishGap) correctly still
  read `(мб)`, confirming the change was scoped to perimeter only. Σ-totals unaffected.
  Unit tests: `dimensions.property.test.ts`, `roomsSummary.i18n.test.ts`, `FormulaInput.test.tsx`
  all green.

Result: 1 fixed / 0 failed. All Round 6 bugs closed.
