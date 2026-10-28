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
