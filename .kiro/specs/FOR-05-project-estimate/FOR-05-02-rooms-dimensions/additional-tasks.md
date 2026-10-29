# Additional tasks: FOR-05-02-rooms-dimensions

Screen URL: http://localhost:3000/projects/{id}/rooms

Enhancement batch (2026-10-29). Approved by the user. These are NEW feature tasks, not bug
fixes. English per the spec-doc standard; the `test-cases.md` scenarios will be in Russian per
the test-cases standard.

## Scope

Add a leading tiny "always filled manually" flag column to the rooms dimensions matrix. The flag is
a **hardcoded, global, per-dimension** property (the same for every project and room type) and is
**purely visual** — it marks dimensions that are, by policy, always entered by hand (never derived
from geometry). It changes NO calculation, NO persistence, and NO backend behavior. It is a frontend
affordance only; there is NO backend "always-manual" concept and none is introduced.

### i18n

- New frontend strings (PL + RU parity in `foremen-frontend/src/locales/pl.json` and `ru.json`):
  - `roomsSummary.manualFlagHeader` — the tiny column header / aria-label (e.g. PL "Ręcznie",
    RU "Вручную"), kept short.
  - `roomsSummary.manualFlagTooltip` — hover text, e.g. PL "Zawsze wypełniane ręcznie",
    RU "Всегда заполняется вручную".
- No new backend i18n entity fields (no backend change at all).

### Notifications

None. A purely visual column change emits no user-facing notification. Justified: no domain state
changes.

## Tasks

- [x] 1. Add the `alwaysManual` descriptor flag to the dimension registry
  - DONE: added `alwaysManual: boolean` to `DimensionRow` and set it per `DIMENSION_ROWS` entry
    (`true` for the 10 non-geometry metrics, `false` for the 5 `MeasureValue` metrics).
  - In `foremen-frontend/src/features/rooms/dimensions.ts`, add `alwaysManual: boolean` to the
    `DimensionRow` interface and set it per entry in `DIMENSION_ROWS`. Hardcode `true` for the
    dimensions that are always entered manually (the non-geometry-derivable metrics: `doorCount`,
    `doorHeight`, `doorWidth`, `wallGap`, `finishGap`, `windowCount`, `windowHeight`, `windowWidth`,
    `internalCorners`, `ceilingHeight`), and `false` for the five geometry-derivable `MeasureValue`
    metrics (`floorArea`, `wallArea`, `perimeter`, `doorArea`, `windowArea`). This is a hardcoded
    global policy, not user-editable and not persisted.
  - _Requirements: FOR-05-02 R2 (dimensions matrix presentation)_
  - _Depends on: none_

- [x] 2. Render the leading flag column in the matrix (header + per-row cell)
  - DONE: added a sticky `left-0 w-8` flag column (header + per-row cell) in `RoomDimensionsMatrix.tsx`;
    the dimension-label column now sticks at `left-8`. A `Lock` marker renders only on `alwaysManual`
    rows with the tooltip.
  - In `foremen-frontend/src/features/rooms/components/workspace/RoomDimensionsMatrix.tsx`, insert a
    new FIRST `<th>` in the header row (before the sticky dimension-label column) and a matching
    leading `<td>`/`<th>` in each `DIMENSION_ROWS.map` body row. The cell shows a small marker (e.g.
    a lock / hand icon) only when `row.alwaysManual` is `true`, with the `roomsSummary.manualFlagTooltip`
    tooltip on hover; otherwise it renders empty. Keep the column narrow (w-8/w-10) and sticky with
    the existing left-sticky label column so it stays visible on horizontal scroll. The Σ column and
    all value columns are unchanged.
  - _Requirements: FOR-05-02 R2 (dimensions matrix presentation)_
  - _Depends on: 1_

- [x] 3. Add the i18n keys (PL + RU)
  - DONE: added `roomsSummary.manualFlagHeader` + `roomsSummary.manualFlagTooltip` to `pl.json`
    ("Ręcznie" / "Zawsze wypełniane ręcznie") and `ru.json` ("Вручную" / "Всегда заполняется вручную").
  - _Requirements: FOR-05-02 R2_
  - _Depends on: none_

- [ ] 4. Author test-cases.md
  - Create/append `test-cases.md` per `.kiro/steering/test-cases.md` (UI browser-engine scenarios,
    Russian): the flag column renders, the marker appears on the always-manual rows and not on the
    geometry-derivable rows, the tooltip shows on hover, and the column is sticky on horizontal
    scroll. Repeatability: read-only navigation, no data mutation (no teardown needed). Regression:
    confirm the existing value columns, Σ, and inline editing still work with the new leading column.
  - _Requirements: the requirements covered by the test cases (FOR-05-02 R2)_
  - _Depends on: 1, 2, 3_

- [x] V. Validate in the browser
  - PASS (2026-10-29): on `/projects/2/rooms` (live Docker stack) the leading flag column renders;
    all 15 flag cells present; the lock marker shows on the 10 always-manual rows (doorCount,
    doorHeight, doorWidth, wallGap, finishGap, windowCount, windowHeight, windowWidth,
    internalCorners, ceilingHeight) and NOT on the 5 geometry-derivable metrics (floorArea, wallArea,
    perimeter, doorArea, windowArea). `tsc` clean; 287 rooms tests pass.
  - _Requirements: as tasks 1–3_
  - _Depends on: 1, 2, 3_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1, 3 — the registry flag and the i18n keys are independent files.
- **Wave 2:** 2 — the matrix render depends on the registry flag (1).
- **Wave 3:** 4 — test-cases.md depends on the implementation (1, 2, 3).
- **Wave 4:** V — browser validation depends on all implementation (1, 2, 3).
