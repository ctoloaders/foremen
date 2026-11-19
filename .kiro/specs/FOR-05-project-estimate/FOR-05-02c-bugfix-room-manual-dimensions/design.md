# Room Manual Dimensions Bugfix Design

## Overview

In the rooms matrix, a room with saved polygon geometry loses the manually entered `wallGap` and `finishGap`. `RoomService.applyCalculatedMetrics` always overwrites them with the sum of the per-wall gaps in the geometry (0 when all are `null`) and recomputes `wallArea` from those sums. The two values have no `MANUAL`/`CALCULATED` source, so a root-level value can never win. Two related display gaps share the same rule: the perimeter has no breakdown of wall lengths, and door/window height and width are not derived from the geometry or flagged manual.

The fix extends the existing per-field MANUAL override rule (used for `floorArea`, `wallArea`, `perimeter`, `doorArea`, `windowArea`) to six more fields and adds one explanation:

- Six new source columns (`wall_gap_source`, `finish_gap_source`, `door_height_source`, `door_width_source`, `window_height_source`, `window_width_source`) added by a new Liquibase changeset, exposed through the entity, service models, DTOs and requests.
- `applyCalculatedMetrics` keeps a root value only when its source is `MANUAL`; otherwise it derives the value from geometry and stamps `CALCULATED`.
- Opening size is derived from geometry only when the type's total count is exactly 1; otherwise it is `null` (dash on screen).
- `RoomMetricExplainer` and `metricExplanation.ts` produce a perimeter explanation listing the wall lengths and their sum.
- `RoomsMatrixTab.tsx` sends `MANUAL` for edited gap and size fields, re-sends persisted `MANUAL` sources, and resets a field to `CALCULATED` when the cell is cleared.

Rooms without geometry and the existing five-metric MANUAL rule stay unchanged.

## Glossary

- **Bug_Condition (C)**: A save of a room with geometry where a root `wallGap`/`finishGap` or a door/window size is supplied as manual, or the geometry contains openings/walls whose size or perimeter breakdown is not derived or shown.
- **Property (P)**: The manual root value is persisted, stamped `MANUAL` and returned unchanged. Otherwise the value is geometry-derived and stamped `CALCULATED`. `wallArea` uses the effective gaps.
- **Preservation**: Behavior for rooms without geometry, per-wall gaps, per-wall gap formulas, the five existing source-carrying metrics, counts, areas, range validation, AI import and the visual editor.
- **Manual override**: An explicit non-null value together with source `MANUAL` (same test as `RoomService.isManualOverride`).
- **Gap fields**: `wallGap` (missing wall) and `finishGap` (missing finish), in running metres.
- **Size fields**: `doorHeight`, `doorWidth`, `windowHeight`, `windowWidth`.
- **applyCalculatedMetrics**: The method in `foremen-backend/src/main/java/com/foremen/service/RoomService.java` that resolves metrics for a room with geometry.
- **RoomCalculationService.calculate**: The pure geometry engine in `foremen-backend/src/main/java/com/foremen/service/RoomCalculationService.java` returning `RoomMetrics`.
- **RoomMetricExplainer**: Builds formula explanations of `CALCULATED` metrics (`foremen-backend/src/main/java/com/foremen/service/RoomMetricExplainer.java`).
- **metricExplanation.ts**: Frontend counterpart for unsaved edits (`foremen-frontend/src/features/rooms/lib/metricExplanation.ts`).

## Bug Details

### Bug Condition

The bug manifests when a room has geometry (at least one vertex) and the caller supplies a root `wallGap`/`finishGap` or door/window size that should win over the geometry. `applyCalculatedMetrics` ends with `setWallGap(metrics.wallGap())` and `setFinishGap(metrics.finishGap())` and never reads a source for gaps or sizes. `RoomCalculationService.calculate` computes `wallArea` from the per-wall gap sums, so a manual gap cannot influence it. The size fields are copied from the request or stored value and never derived from openings.

**Formal Specification:**
```
FUNCTION isBugCondition(X)
  INPUT: X of type RoomSaveInput (geometry, wallGap, finishGap, doorHeight, doorWidth,
         windowHeight, windowWidth, *Source fields, dimensionFormulas)
  OUTPUT: boolean

  hasGeo <- X.geometry != null AND size(X.geometry.vertices) > 0
  RETURN hasGeo AND (
       (X.wallGap   != null AND X.wallGapSource   = MANUAL AND X.wallGap   != SUM(wall.wallGap))
    OR (X.finishGap != null AND X.finishGapSource = MANUAL AND X.finishGap != SUM(wall.finishGap))
    OR any size field s: (X.s != null AND X.sSource = MANUAL)
    OR X.geometry has openings of a type                    // size not derived
    OR size(X.geometry.walls) > 0                           // perimeter breakdown not shown
  )
END FUNCTION
```

### Examples

- Room 1701 "Przedpokój" (project 1531): save with `wallGap=2`, `finishGap=3`, `wallArea=12.93`, one DOOR 0.58 x 2.0. Actual: DB keeps `wall_gap=0`, `finish_gap=0`, `wall_area=26.43`. Expected: `wall_gap=2` (MANUAL), `finish_gap=3` (MANUAL), `wall_area=12.93` after recalculation, equal to the value before the save.
- Same room, no manual values: `door_height`/`door_width` stay 0. Expected: 2.0 / 0.58, source `CALCULATED`, because the door count is exactly 1.
- Room with two DOOR openings (or one opening with `count=2`): expected `doorHeight=null`, `doorWidth=null` (dash), `doorArea` still the sum over openings.
- Room with two doors and `doorHeight=2.1` sent with source `MANUAL`: expected 2.1 persisted, `MANUAL`; a later save without change keeps it.
- Perimeter of a rectangular room 4 x 3: expected explanation `wall1 + wall2 + wall3 + wall4` with values 4, 3, 4, 3 and result 14.
- Edge: wall length entered as the formula `=2*1.5` in `dimensionFormulas["geometry.walls[0].length"]`: the explanation element shows `2*1.5` as the formula for that wall.
- Edge: `wallGap = -1` with source `MANUAL`: rejected with the existing metric-range error (unchanged).

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Rooms without geometry keep submitted `wallGap`/`finishGap`/size values and compute `wallArea = max(0, (perimeter - finishGap - wallGap) x ceilingHeight - doorArea - windowArea)` through `applyManualMetrics`.
- A room with geometry and no manual root value uses the sum of per-wall gaps as `wallGap`/`finishGap`.
- Per-wall gap formulas (`geometry.walls[i].wallGap|finishGap`) are evaluated and written into the geometry as before (`applyDimensionFormulas`).
- The per-field override rule of `floorArea`, `wallArea`, `perimeter`, `doorArea`, `windowArea` is untouched.
- `doorCount`, `windowCount`, `doorArea`, `windowArea`, `floorArea`, `perimeter` stay geometry-derived.
- The wall-area, door-area and window-area formula popovers show the same formula and values.
- AI import and the visual editor send no manual sources and behave as before.
- Values outside `[0, 9999999999.99]` are rejected with the existing metric-range error.

**Scope:**
All saves where no gap/size field carries `source = MANUAL` with a value, and all rooms without geometry, produce the same persisted metrics as before. The only differences for geometry rooms are the newly derived door/window sizes (`CALCULATED`) and the `CALCULATED` stamps on the new source columns.

## Hypothesized Root Cause

Confirmed by reading the code, not only hypothesized:

1. **Unconditional overwrite of gaps**: `applyCalculatedMetrics` ends with `model.setWallGap(metrics.wallGap())` and `model.setFinishGap(metrics.finishGap())` for every geometry save.
2. **No source model for gaps or sizes**: `RoomEntity`, `RoomServiceModel`, `RoomServiceExtendedModel`, `RoomUpdateRequest`/create request and `RoomDtoModel` have `*Source` only for the five metrics, so manual and calculated values cannot be told apart.
3. **`wallArea` computed inside the engine**: `RoomCalculationService.calculate` always uses per-wall gap sums, so even a preserved manual gap would not reach `wallArea` unless the area is recomputed after the gap decision.
4. **Sizes never derived**: `RoomMetrics` carries counts and areas but no per-type opening size; the size fields are never written from geometry.
5. **No perimeter explanation**: `RoomMetricExplainer.explain` handles `doorArea`, `windowArea`, `wallArea` only. `Input` has no `perimeterSource` or `dimensionFormulas`.
6. **Frontend sends no source for geometry rooms**: `RoomsMatrixTab.tsx` deliberately omits `*Source` when `roomHasGeometry`, and its source map only covers `REQUEST_METRIC_FIELDS`.

## Correctness Properties

Property 1: Bug Condition - Manual gaps and sizes win over geometry

_For any_ save of a room with geometry where a gap or size field is supplied non-null with source `MANUAL`, the fixed `RoomService` SHALL persist that value unchanged, stamp its source `MANUAL`, return it in the response and on the next read, and, when `wallAreaSource` is not `MANUAL`, compute `wallArea = max(0, (perimeter - finishGap - wallGap) x ceilingHeight - doorArea - windowArea)` from the effective (manual or summed) gaps.

**Validates: Requirements 2.1, 2.2, 2.7**

Property 2: Bug Condition - Geometry-derived values and stamps

_For any_ save of a room with geometry where a gap or size field has no manual value (source absent or `CALCULATED`, or value `null`), the fixed `RoomService` SHALL set a gap to the sum of the per-wall gaps and stamp it `CALCULATED`, and SHALL set `{door|window}Height`/`Width` to the single opening's height/width when the total count of that type is exactly 1, else `null`, stamped `CALCULATED`. `doorArea`/`windowArea` SHALL remain the sum over individual openings.

**Validates: Requirements 2.3, 2.5, 2.6, 2.8**

Property 3: Bug Condition - Perimeter explanation

_For any_ room with geometry whose `perimeterSource` is `CALCULATED`, the explanation of `perimeter` SHALL contain one element per wall carrying that wall's length (or its `geometry.walls[i].length` formula when present) and a formula whose evaluation equals the perimeter within rounding.

**Validates: Requirements 2.4**

Property 4: Preservation - Unaffected inputs

_For any_ input where the bug condition does NOT hold (no geometry; or geometry with no manual gap/size and no openings), the fixed code SHALL produce the same persisted metrics, sources of the five existing metrics, and explanations of `wallArea`/`doorArea`/`windowArea` as the original code, including the existing range-error behavior and per-field override of the five metrics.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8**

## Fix Implementation

### Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Source storage | Six nullable `VARCHAR(20)` columns holding `MeasureSource`, same type as `perimeter_source` | Reuses the enum and the mapper pattern |
| What is manual | Non-null value AND source `MANUAL` | Identical to `isManualOverride` for the five metrics; clients that send no source (import, visual editor) behave as before (3.7) |
| Manual vs geometry | Manual root value wins | Requirement 2.1, 2.7 |
| Opening size from geometry | Only when the type's total count (sum of `opening.count`) is exactly 1; else `null` | Sum or average of sizes is meaningless (2.6) |
| Source when size is `null` | `CALCULATED` | A dash is a derived state, not a manual one |
| Reset to CALCULATED | Client clears the cell: sends `null` value and no source (or `CALCULATED`) | Same gesture as the existing derived-metric override (clearing releases it) |
| No-geometry rooms | Values kept as submitted; supplied source is validated and stored as given, `null` when absent | Keeps 3.1 unchanged |
| Backfill | All existing rows with a non-null value get `CALCULATED` | Existing values were produced by the geometry path or typed in a source-less model; nothing was flagged manual |

### Changes Required

**Database**: new `foremen-backend/database_files/changesets/NNN-room-manual-dimension-sources.xml`

1. Take the next free number NNN at edit time (265 is the last used at the time of writing, so the next is 266 unless another spec has taken it). Register it LAST in `foremen-backend/database_files/changelog.xml`.
2. ChangeSet 1: `addColumn` on `rooms` for `wall_gap_source`, `finish_gap_source`, `door_height_source`, `door_width_source`, `window_height_source`, `window_width_source` (`VARCHAR(20)`, nullable). Precondition `<preConditions onFail="MARK_RAN"><not><columnExists tableName="rooms" columnName="wall_gap_source"/></not></preConditions>`.
3. ChangeSet 2: backfill, `UPDATE rooms SET wall_gap_source='CALCULATED' WHERE wall_gap IS NOT NULL AND wall_gap_source IS NULL`, and the same for the other five columns. `onFail="MARK_RAN"` precondition via `sqlCheck` that no row yet has a non-null `wall_gap_source` (idempotent re-run).
4. Add the six `@Enumerated(EnumType.STRING) @Column(name=..., length = 20)` fields to `RoomEntity`.

**Models and mapping**: `RoomServiceModel`, `RoomServiceExtendedModel`, `RoomDtoModel`, `RoomDtoExtendedModel`, `RoomUpdateRequest`, the create request, `RoomServiceMapper`, `RoomControllerMapper`

1. Add the six `MeasureSource` fields and accept them on create and update requests. They are plain companion fields next to the plain numeric (not `MeasureValue`), so the existing read shape `wallGap: number` stays and the frontend reads `wallGapSource` etc. as new properties.
2. Add them to the audit snapshot serialization if `serializeEntity` lists fields explicitly.

**File**: `foremen-backend/src/main/java/com/foremen/service/RoomService.java`, `applyCalculatedMetrics`

1. Resolve gaps first, before `wallArea`. For each gap: if `isManualOverride(field, value, source)` keep it and stamp `MANUAL`; else set `metrics.wallGap()`/`metrics.finishGap()` and stamp `CALCULATED`.
2. Recompute `wallArea` when its source is not manual, using the effective gaps. Do not change `RoomCalculationService.calculate`. Add an overload or a small public helper `RoomCalculationService.wallArea(perimeter, height, doorArea, windowArea, finishGap, wallGap)` (the existing private method made package-visible/public) and call it with the already-resolved `perimeter`, `doorArea`, `windowArea`, so the formula lives in one place and is rounded as before. Resolve `perimeter`, `doorArea`, `windowArea` (manual or calculated) before this step so a manual `doorArea` still feeds `wallArea` as in the Round 10 rule.
3. Derive sizes. Add `openingSize(geometry, OpeningType)` returning `Optional<OpeningSize(height, width)>` present only when the sum of `count` over openings of the type equals 1. Add `doorSize`/`windowSize` to `RoomMetrics` (or compute in the service from the geometry). For each size field: manual override keeps value and stamps `MANUAL`; else set the derived value (or `null`) and stamp `CALCULATED`. Range-check manual values through `validateMetricRange`.
4. In `applyManualMetrics` (no geometry): call `validateSource` on the six new sources and keep values as submitted. Do not stamp.
5. Update the class and method Javadoc: gaps and sizes are now overridable.

**File**: `RoomMetricExplainer.java`

1. Add `perimeterSource` and `dimensionFormulas` (`Map<String,String>`) to `Input`.
2. When geometry exists and `perimeterSource == CALCULATED`, add `out.put("perimeter", ...)`. Build the formula `wall1 + wall2 + ... + wallN`, with variables `wall1..wallN` = rounded length of wall `i` (distance from vertex `i` to vertex `(i+1) mod n`, the same edge definition as `RoomCalculationService.perimeter`). For wall `i` with `dimensionFormulas["geometry.walls[i].length"]` present, use that formula text in place of the variable name and keep the resulting length as its value. The constant `PERIMETER_FORMULA_PREFIX` is not needed.
3. Update the callers of `Input` (`RoomServiceMapper`/service read model wiring) and `RoomMetricExplainerTest`.

**File**: `foremen-frontend/src/features/rooms/lib/metricExplanation.ts`

1. Add `buildPerimeterExplanation(geometry, dimensionFormulas): MetricExplanation | null` that mirrors the backend: elements `wall1..wallN` with each wall's length; a formula entered as `geometry.walls[i].length` is shown as that formula. Calculation is only the vertex distance used for display; no new business rule.
2. Keep `METRIC_FORMULAS` and `buildMetricExplanation` unchanged. The saved room uses the backend explanation; the unsaved edit state uses this builder.
3. Show the explanation on the perimeter cell with the existing formula popover component (`RoomDimensionsMatrix.tsx`), only for rooms with geometry.

**Types**: `foremen-frontend/src/features/rooms/types/index.ts` adds `wallGapSource`, `finishGapSource`, `doorHeightSource`, `doorWidthSource`, `windowHeightSource`, `windowWidthSource` (`MeasureSource | null`) to `RoomDto` and `RoomCreateRequest`/`RoomUpdateRequest`.

**File**: `foremen-frontend/src/features/rooms/components/workspace/RoomsMatrixTab.tsx`

1. Add `REQUEST_SOURCE_PLAIN_FIELDS = ['wallGap','finishGap','doorHeight','doorWidth','windowHeight','windowWidth']` and a `plainSourceField(field)` helper (`${field}Source`).
2. Seed loop (persisted values): for a room with geometry, also seed each persisted `*Source` that is `MANUAL` so an unrelated edit does not drop a manual flag.
3. Edit loop: when one of the six fields is edited with a non-null value, send `data[field] = v` and `data[field+'Source'] = 'MANUAL'` (geometry rooms and no-geometry rooms alike; the no-geometry backend path stores it as given). When the cell is cleared (`v === null`), send `null` and delete the source, which resets the field to `CALCULATED` on the backend. This matches how clearing an overridden derived metric releases the override.
4. Display: a cell with source `MANUAL` shows the existing "Manual" badge; a size cell with a `null` value on a room with geometry and openings renders the dash. Both reuse the badge logic in `RoomDimensionsMatrix.tsx` (`effectiveSource` returns the persisted source for these fields instead of always `MANUAL`).
5. Client auto-calc (`dimensionsCalc.ts`) keeps treating `wallGap`/`finishGap` and sizes as base inputs, so a manual `wallGap` immediately updates `wallArea` on screen with the same formula the backend applies.
6. Add i18n keys only if a new tooltip for the reset gesture is needed (`pl.json`, `ru.json`).

## Architecture

The change stays inside the existing room save pipeline: `RoomController` -> `RoomService.normalize` -> `applyCalculatedMetrics` (geometry) or `applyManualMetrics` (no geometry) -> mapper -> `RoomEntity`. The read path adds `RoomMetricExplainer` output to `RoomDto`. No new service or endpoint is introduced.

## Components and Interfaces

- `RoomService.applyCalculatedMetrics`: resolves gaps, perimeter, areas, sizes in dependency order (gaps and areas first, then `wallArea`).
- `RoomCalculationService`: unchanged `calculate`; the wall-area formula helper becomes reusable by the service.
- `RoomMetricExplainer.explain(Input)`: adds a `perimeter` entry.
- Room create/update request and DTO: six new optional `MeasureSource` properties.
- Frontend `RoomsMatrixTab.tsx`, `RoomDimensionsMatrix.tsx`, `metricExplanation.ts`, `types/index.ts`.

## Data Models

`rooms` gains six nullable `VARCHAR(20)` columns: `wall_gap_source`, `finish_gap_source`, `door_height_source`, `door_width_source`, `window_height_source`, `window_width_source` (values `MANUAL` | `CALCULATED`). The matching DTO/request properties are `wallGapSource`, `finishGapSource`, `doorHeightSource`, `doorWidthSource`, `windowHeightSource`, `windowWidthSource`.

## Error Handling

- A manual gap or size outside `[0, 9999999999.99]` is rejected by `validateMetricRange` with the existing metric-range error, before any write.
- An invalid source value is rejected by `validateSource` as before.
- A geometry-derived size that cannot be determined (count other than 1) is `null`, not an error.

## Testing Strategy

### Validation Approach

Two phases: first an exploration test that reproduces the bug on the unfixed code, then fix-check and preservation property tests. Per `.kiro/steering/test-execution-rules.md`, run only the affected test classes with `--tests`, write the output to `/tmp/for-05-02c-test.log`, and read the JUnit XML under `foremen-backend/build/test-results/test/`. Never run the full suite.

### Exploratory Bug Condition Checking

**Goal**: Show the bug on unfixed code and confirm the cause (unconditional overwrite in `applyCalculatedMetrics`).

**Test Plan**: Add `RoomManualDimensionsExplorationTest` (service level, same style as `RoomPerFieldOverridePropertyTest`). Run it on the UNFIXED code and expect failures.

**Test Cases**:
1. **Room 1701 reproduction**: rectangle geometry, one DOOR 0.58 x 2.0, `wallGap=2`, `finishGap=3`, sources `MANUAL`. Assert stored gaps are 2/3 and `wallArea` equals the on-screen formula (will fail on unfixed code: gaps 0, wall area 26.43).
2. **Opening size**: same room, no manual sizes. Assert `doorHeight=2.0`, `doorWidth=0.58` (will fail on unfixed code: 0).
3. **Two doors**: assert sizes are `null` (may pass trivially on unfixed code if the stored value is null).
4. **Manual size survives re-save**: save twice with `doorHeight=2.1` `MANUAL` (will fail on unfixed code).

**Expected Counterexamples**:
- `wall_gap`, `finish_gap` reset to 0 and `wall_area = 26.43`.
- Sizes not derived. The cause is confirmed by the unconditional setters at the end of `applyCalculatedMetrics` and by the missing source columns.

### Fix Checking

**Goal**: For every input where the bug condition holds, the fixed save returns the expected behavior.

**Pseudocode:**
```
FOR ALL X WHERE isBugCondition(X) DO
  result := save'(X)
  ASSERT result.wallGap = X.wallGap AND result.wallGapSource = MANUAL          // when supplied MANUAL
  ASSERT result.finishGap = X.finishGap AND result.finishGapSource = MANUAL
  ASSERT result.wallArea = max(0, (result.perimeter - result.finishGap - result.wallGap)
                                   * X.ceilingHeight - result.doorArea - result.windowArea)
         WHEN wallAreaSource = CALCULATED
  FOR t IN {DOOR, WINDOW}:
    IF manual size supplied THEN result.size(t) = manual AND source = MANUAL
    ELSE IF totalCount(t) = 1 THEN result.size(t) = opening size AND source = CALCULATED
    ELSE result.size(t) = null AND source = CALCULATED
  ASSERT explanation(perimeter) lists every wall length and the sum
END FOR
```

### Preservation Checking

**Goal**: For every input where the bug condition does not hold, the fixed save equals the original save.

**Pseudocode:**
```
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT save(X) = save'(X)    // metrics, five existing sources, explanations
END FOR
```

**Testing Approach**: Property-based testing (jqwik, as in the existing room property tests) generates rooms with random polygons, gaps, openings and ceiling heights, so edge cases (zero gaps, `wallArea` clamped to 0, `count > 1`) are covered.

**Test Plan**: Run the existing classes on the unfixed code first (they must pass), then keep them green after the fix.

**Test Cases**:
1. **No-geometry rooms**: gaps, sizes and `wallArea` identical before and after (`RoomManualSourcePropertyTest`).
2. **Per-wall gap sum**: geometry rooms without manual gap keep the sum (`RoomCalculationFinishGapTest`, `RoomGeometryAuthoritativePropertyTest`).
3. **Five-metric override**: unchanged (`RoomPerFieldOverridePropertyTest`).
4. **Counts and areas**: unchanged (`RoomOpeningTotalsPropertyTest`, `RoomWallAreaPropertyTest`, `RoomFloorAreaPropertyTest`).
5. **Out-of-range manual value**: still rejected with the metric-range error.

### Unit Tests

- `RoomMetricExplainerTest`: perimeter explanation for a rectangle (4 elements, sum), for a wall length formula, and no perimeter entry when `perimeterSource` is `MANUAL`.
- Opening size helper: count 1, count 2 on one opening, two openings, no openings, mixed door/window.
- Frontend `metricExplanation` test: `buildPerimeterExplanation` for a rectangle and for a wall with a formula.
- Frontend `RoomsMatrixTab` payload test (`RoomsMatrixTab.test.tsx`): edited `wallGap` sends `wallGapSource: 'MANUAL'`; clearing it sends `null` without a source; a persisted `MANUAL` size is re-sent as `MANUAL`.

### Property-Based Tests

- `RoomManualDimensionsFixPropertyTest` (Properties 1 and 2): random geometry plus random manual/non-manual gap and size combinations, asserting the fix-check pseudocode.
- `RoomManualDimensionsPreservationPropertyTest` (Property 4): random no-geometry rooms and geometry rooms without manual gap/size, asserting equality with the pre-fix results for the five metrics, counts and wall area.
- Perimeter explanation property: the evaluated sum of the element values equals `perimeter` within 0.01 per wall rounding.

### Integration Tests

- Service-level save/reload of room 1701's data on Testcontainers (one class only, `RoomManualDimensionsIntegrationTest`): the persisted `wall_gap`, `finish_gap`, `wall_area` and sources survive reload; a second save without changes keeps them.
- Changeset check: the migration applies on a database with existing rooms and backfills `CALCULATED`.
- Frontend: matrix flow with a geometry room, edit `wallGap`, save, and see the payload; the perimeter popover opens and lists the wall lengths.

Backend command (affected classes only):
```
./gradlew test \
  --tests "com.foremen.service.RoomManualDimensionsExplorationTest" \
  --tests "com.foremen.service.RoomManualDimensionsFixPropertyTest" \
  --tests "com.foremen.service.RoomManualDimensionsPreservationPropertyTest" \
  --tests "com.foremen.service.RoomMetricExplainerTest" \
  --tests "com.foremen.service.RoomPerFieldOverridePropertyTest" \
  --tests "com.foremen.service.RoomManualSourcePropertyTest" \
  --tests "com.foremen.service.RoomCalculationFinishGapTest" \
  > /tmp/for-05-02c-test.log 2>&1; echo "EXIT=$?"
```
