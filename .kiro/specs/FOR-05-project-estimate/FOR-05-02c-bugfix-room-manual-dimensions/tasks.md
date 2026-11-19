# Implementation Plan

Backend tests run only for the affected classes (`.kiro/steering/test-execution-rules.md`): `./gradlew test --tests "<fqcn>" ... > /tmp/for-05-02c-test.log 2>&1; echo "EXIT=$?"`, then read `foremen-backend/build/test-results/test/TEST-<fqcn>.xml`. Never run the full suite. Backend and spec files commit from the root repo. Frontend code lives in the nested `foremen-frontend/` repo and is committed from there (`.kiro/steering/git-repo-structure.md`).

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Manual gaps and opening sizes are lost on the geometry path
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: Scope the property to the concrete failing case from design (room 1701): rectangle geometry with one DOOR 0.58 x 2.0, `wallGap=2`, `finishGap=3`, per-wall gaps `null`, plus the two-door and manual-size cases
  - Create `foremen-backend/src/test/java/com/foremen/service/RoomManualDimensionsExplorationTest.java`, service level, same style as `RoomPerFieldOverridePropertyTest`
  - The test MUST compile on UNFIXED code, where the six `*Source` properties do not exist. Build the save request from a JSON map through `ObjectMapper` (unknown properties ignored) so `wallGapSource: "MANUAL"` etc. are sent when the property exists and silently dropped otherwise. Do not reference the new getters/setters directly
  - Assert (isBugCondition from design): saved `wallGap = 2` and `finishGap = 3`; `wallArea = max(0, (perimeter - 3 - 2) * ceilingHeight - doorArea - windowArea)` (room 1701: 12.93); after reload the same values are returned
  - Assert one door gives `doorHeight = 2.0`, `doorWidth = 0.58`; two doors (or one opening with `count = 2`) give `doorHeight = null`, `doorWidth = null` while `doorArea` stays the sum over openings
  - Assert a manual `doorHeight = 2.1` on a two-door room survives a second save without change
  - Run on UNFIXED code: `./gradlew test --tests "com.foremen.service.RoomManualDimensionsExplorationTest" > /tmp/for-05-02c-test.log 2>&1; echo "EXIT=$?"`
  - **EXPECTED OUTCOME**: Test FAILS (this is correct - it proves the bug exists)
  - Document counterexamples (expected: `wall_gap = 0`, `finish_gap = 0`, `wall_area = 26.43`, sizes 0 instead of 2.0 / 0.58) and confirm the cause is the unconditional `setWallGap`/`setFinishGap` at the end of `RoomService.applyCalculatedMetrics`
  - Mark task complete when the test is written, run, and the failure is documented
  - _Requirements: 1.1, 1.2, 1.3, 1.5, 1.6, 2.1, 2.2, 2.5, 2.6, 2.7_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Rooms without geometry and geometry rooms without manual gap/size
  - **IMPORTANT**: Follow observation-first methodology
  - Observe on UNFIXED code: a no-geometry room keeps submitted `wallGap`/`finishGap`/door/window sizes and `wallArea = max(0, (perimeter - finishGap - wallGap) x ceilingHeight - doorArea - windowArea)`
  - Observe on UNFIXED code: a geometry room with per-wall gaps and no manual root value stores the per-wall sums as `wallGap`/`finishGap`; counts, `doorArea`, `windowArea`, `floorArea`, `perimeter` equal the geometry-derived values; the five-metric MANUAL/CALCULATED rule is unchanged; a value outside `[0, 9999999999.99]` is rejected with the metric-range error
  - Create `foremen-backend/src/test/java/com/foremen/service/RoomManualDimensionsPreservationPropertyTest.java` (jqwik). Generate random no-geometry rooms and random geometry rooms (random polygons, per-wall gaps, openings, ceiling heights, zero gaps, clamped `wallArea`) with NO gap/size source `MANUAL`. Assert the persisted `wallGap`, `finishGap`, `wallArea`, counts, areas and the five existing sources equal the observed baseline formulas. Do not assert door/window sizes of geometry rooms (they are newly derived by the fix)
  - Run on UNFIXED code together with the existing preservation classes: `RoomPerFieldOverridePropertyTest`, `RoomManualSourcePropertyTest`, `RoomCalculationFinishGapTest`, `RoomGeometryAuthoritativePropertyTest`, `RoomOpeningTotalsPropertyTest`, `RoomWallAreaPropertyTest`, `RoomFloorAreaPropertyTest` (all under `com.foremen.service`; run only those that exist)
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [x] 3. Fix for lost manual gaps and missing opening sizes on the geometry path

  - [x] 3.1 Add the Liquibase changeset and entity columns
    - Create `foremen-backend/database_files/changesets/266-room-manual-dimension-sources.xml`. Number 266 was free at the time of writing (last used: 265). Before editing, re-check `ls foremen-backend/database_files/changesets | grep -E '^26[6-9]'` and the tail of `changelog.xml`. Spec FOR-05-07b takes 267 in parallel. If 266 is already taken, use the next free number, rename the file and the changelog line accordingly, and state the actual number in the task notes and in `test-cases.md`
    - ChangeSet 1: `addColumn` on `rooms`: `wall_gap_source`, `finish_gap_source`, `door_height_source`, `door_width_source`, `window_height_source`, `window_width_source`, each `VARCHAR(20)` nullable; precondition `<preConditions onFail="MARK_RAN"><not><columnExists tableName="rooms" columnName="wall_gap_source"/></not></preConditions>`
    - ChangeSet 2: backfill `UPDATE rooms SET <col>_source='CALCULATED' WHERE <col> IS NOT NULL AND <col>_source IS NULL` for all six; `onFail="MARK_RAN"` `sqlCheck` precondition (no row has a non-null `wall_gap_source`) so a re-run changes nothing
    - Register the file LAST in `foremen-backend/database_files/changelog.xml` after the 265 include
    - Add six `@Enumerated(EnumType.STRING) @Column(name = "...", length = 20) MeasureSource` fields to `RoomEntity`
    - _Bug_Condition: isBugCondition(X) from design where a gap/size source is needed to tell manual from calculated_
    - _Expected_Behavior: sources persisted and backfilled `CALCULATED`_
    - _Preservation: existing rows and the five existing source columns unchanged_
    - _Requirements: 1.3, 2.3_

  - [x] 3.2 Expose the six sources on service models, DTOs, requests and mappers
    - Add `wallGapSource`, `finishGapSource`, `doorHeightSource`, `doorWidthSource`, `windowHeightSource`, `windowWidthSource` (`MeasureSource`, nullable, plain companion fields next to the numeric values) to `RoomServiceModel`, `RoomServiceExtendedModel`, `RoomDtoModel`, `RoomDtoExtendedModel`, `RoomUpdateRequest` and the create request
    - Map them in `RoomServiceMapper` and `RoomControllerMapper`; keep the numeric shape (`wallGap: number`) unchanged
    - Add them to the audit snapshot if `serializeEntity` lists fields explicitly
    - Run `./gradlew compileJava compileTestJava > /tmp/for-05-02c-compile.log 2>&1; echo "EXIT=$?"`
    - _Bug_Condition: isBugCondition(X) from design_
    - _Expected_Behavior: expectedBehavior from design (sources readable and writable)_
    - _Preservation: clients that send no source (AI import, visual editor) behave as before_
    - _Requirements: 2.3, 3.7_

  - [x] 3.3 Fix `RoomService.applyCalculatedMetrics` and the wall-area helper
    - In `foremen-backend/src/main/java/com/foremen/service/RoomCalculationService.java` make the existing wall-area formula reusable: `wallArea(perimeter, ceilingHeight, doorArea, windowArea, finishGap, wallGap)` (same rounding), do not change `calculate`. Add `openingSize(geometry, OpeningType)` returning an optional height/width present only when the sum of `count` over openings of the type is exactly 1
    - In `foremen-backend/src/main/java/com/foremen/service/RoomService.java` resolve in dependency order: perimeter, `doorArea`, `windowArea` (existing per-field rule), then the gaps (`isManualOverride` keeps the value and stamps `MANUAL`, otherwise per-wall sum and `CALCULATED`), then `wallArea` from the effective gaps when its source is not `MANUAL`, then the four sizes (manual keeps the value and stamps `MANUAL` after `validateMetricRange`; otherwise derived value or `null`, stamped `CALCULATED`)
    - In `applyManualMetrics` (no geometry): `validateSource` on the six new sources, keep values as submitted, do not stamp
    - Keep per-wall gap formula evaluation (`applyDimensionFormulas`) as is. Update the Javadoc of the class and method
    - _Bug_Condition: isBugCondition(X) where X.geometry != null AND (manual gap/size OR openings present)_
    - _Expected_Behavior: expectedBehavior(result) from design (manual wins and is stamped `MANUAL`; wallArea from effective gaps; size = single opening else null)_
    - _Preservation: Preservation Requirements from design (no-geometry path, per-wall sums, five-metric rule, range validation)_
    - _Requirements: 2.1, 2.2, 2.3, 2.5, 2.6, 2.7, 2.8, 3.1, 3.2, 3.3, 3.4, 3.5, 3.8_

  - [x] 3.4 Add the perimeter explanation to the backend
    - In `foremen-backend/src/main/java/com/foremen/service/RoomMetricExplainer.java` add `perimeterSource` and `dimensionFormulas` to `Input`. When geometry exists and `perimeterSource == CALCULATED`, add a `perimeter` entry: formula `wall1 + ... + wallN`, `wall<i>` = rounded length of edge `i` (vertex `i` to `(i+1) mod n`, the same edge definition as the perimeter calculation), result = perimeter. When `dimensionFormulas["geometry.walls[i].length"]` is present, show that formula text for wall `i` and keep the length as its value
    - Update the callers that build `Input` and `foremen-backend/src/test/java/com/foremen/service/RoomMetricExplainerTest.java`: rectangle 4 x 3 gives four elements 4, 3, 4, 3 and result 14; wall length formula `2*1.5`; no perimeter entry when `perimeterSource` is `MANUAL`
    - Run: `./gradlew test --tests "com.foremen.service.RoomMetricExplainerTest" > /tmp/for-05-02c-test.log 2>&1; echo "EXIT=$?"`
    - _Bug_Condition: isBugCondition(X) where size(X.geometry.walls) > 0_
    - _Expected_Behavior: explanation(perimeter) lists every wall length and their sum_
    - _Preservation: wallArea/doorArea/windowArea explanations unchanged_
    - _Requirements: 2.4, 3.6_

  - [x] 3.5 Frontend types and perimeter explanation builder (repo `foremen-frontend`)
    - `foremen-frontend/src/features/rooms/types/index.ts`: add the six `*Source` properties (`MeasureSource | null`) to `RoomDto` and the create/update request types
    - `foremen-frontend/src/features/rooms/lib/metricExplanation.ts`: add `buildPerimeterExplanation(geometry, dimensionFormulas): MetricExplanation | null` mirroring the backend (elements `wall1..wallN`, formula from `geometry.walls[i].length` shown when present, result = sum). Keep `METRIC_FORMULAS` and `buildMetricExplanation` unchanged
    - _Bug_Condition: isBugCondition(X) where size(X.geometry.walls) > 0_
    - _Expected_Behavior: perimeter breakdown is available for unsaved edits_
    - _Preservation: existing explanations unchanged_
    - _Requirements: 2.4, 3.6_

  - [x] 3.6 Matrix UI: send and show manual flags, perimeter popover, size dash (repo `foremen-frontend`)
    - `foremen-frontend/src/features/rooms/components/workspace/RoomsMatrixTab.tsx`: add `REQUEST_SOURCE_PLAIN_FIELDS = ['wallGap','finishGap','doorHeight','doorWidth','windowHeight','windowWidth']` and a `plainSourceField(field)` helper. Seed persisted `MANUAL` sources for geometry rooms so an unrelated edit does not drop them. An edit with a non-null value sends `data[field] = v` and `data[field+'Source'] = 'MANUAL'`. Clearing the cell sends `null` and no source (reset to `CALCULATED`)
    - `foremen-frontend/src/features/rooms/components/workspace/RoomDimensionsMatrix.tsx`: `effectiveSource` returns the persisted source for the six fields so the existing Manual badge is shown for `MANUAL`; a size cell with `null` on a geometry room with openings renders a dash; the perimeter cell opens the existing formula popover with `buildPerimeterExplanation` (saved room: backend explanation) only for rooms with geometry
    - Leave `dimensionsCalc.ts` unchanged (a manual `wallGap` updates `wallArea` on screen with the formula the backend applies). Add `pl.json`/`ru.json` keys only if a reset tooltip is added
    - Commit from inside `foremen-frontend/` only when the user asks
    - _Bug_Condition: isBugCondition(X) from design (manual gap/size; openings; walls)_
    - _Expected_Behavior: expectedBehavior(result) from design (manual flag sent and displayed; dash for several openings; perimeter popover)_
    - _Preservation: no-geometry rooms and existing popovers behave as before_
    - _Requirements: 1.4, 2.4, 2.5, 2.6, 2.7, 3.1, 3.6_

  - [x] 3.7 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Manual gaps and opening sizes win or derive from geometry
    - **IMPORTANT**: Re-run the SAME test from task 1 - do NOT write a new test
    - The test from task 1 encodes the expected behavior
    - Run: `./gradlew test --tests "com.foremen.service.RoomManualDimensionsExplorationTest" > /tmp/for-05-02c-test.log 2>&1; echo "EXIT=$?"` and read `TEST-com.foremen.service.RoomManualDimensionsExplorationTest.xml`
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.1, 2.2, 2.5, 2.6, 2.7_

  - [x] 3.8 Verify preservation tests still pass
    - **Property 2: Preservation** - Rooms without geometry and geometry rooms without manual gap/size
    - **IMPORTANT**: Re-run the SAME tests from task 2 - do NOT write new tests
    - Run `RoomManualDimensionsPreservationPropertyTest` and the existing classes listed in task 2 in one invocation with multiple `--tests`
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

- [x] 4. Add fix-check, integration and frontend tests

  - [x] 4.1 Write the fix-check property test
    - Create `foremen-backend/src/test/java/com/foremen/service/RoomManualDimensionsFixPropertyTest.java` (jqwik): random geometry plus random manual/non-manual gap and size combinations, asserting the fix-check pseudocode from design (value and `MANUAL` source persisted, `wallArea` from effective gaps when not manual, size = single opening or `null` with `CALCULATED`, `doorArea`/`windowArea` still summed per opening, clearing a manual value returns to `CALCULATED`, out-of-range manual value rejected). Add a perimeter explanation property: the sum of element values equals `perimeter` within 0.01 per wall
    - Run: `./gradlew test --tests "com.foremen.service.RoomManualDimensionsFixPropertyTest" > /tmp/for-05-02c-test.log 2>&1; echo "EXIT=$?"`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 3.8_

  - [x] 4.2 Write the Testcontainers integration test (one class only)
    - Create `foremen-backend/src/test/java/com/foremen/service/RoomManualDimensionsIntegrationTest.java`: save the data of room 1701 (`wallGap=2`, `finishGap=3`, one DOOR 0.58 x 2.0), reload, assert persisted `wall_gap`, `finish_gap`, `wall_area` 12.93, the sources and `doorHeight=2.0`/`doorWidth=0.58`; a second save without changes keeps them. Add a changeset check: the migration applies on a database with existing rooms and backfills `CALCULATED`, and a re-run changes nothing
    - Run only this class (slow, Testcontainers): `./gradlew test --tests "com.foremen.service.RoomManualDimensionsIntegrationTest" > /tmp/for-05-02c-test.log 2>&1; echo "EXIT=$?"`
    - _Requirements: 2.1, 2.2, 2.3, 2.5, 3.2_

  - [x] 4.3 Write the frontend tests (repo `foremen-frontend`)
    - `metricExplanation` test: `buildPerimeterExplanation` for a rectangle and for a wall with a length formula
    - `RoomsMatrixTab.test.tsx` payload test: editing `wallGap` sends `wallGapSource: 'MANUAL'`; clearing it sends `null` without a source; a persisted `MANUAL` size is re-sent as `MANUAL`; the perimeter popover lists the wall lengths for a geometry room; a size cell with several openings shows a dash
    - Run only these files with `npx vitest --run <files>` from `foremen-frontend/`
    - _Requirements: 2.4, 2.5, 2.6, 2.7_

- [x] 5. Author test-cases.md
  - Create `test-cases.md` in this spec folder in Russian, following `.kiro/steering/test-cases.md`: feature grouping, step-by-step scenarios with ID, title, preconditions, numbered steps and expected result per step
  - UI scenarios (browser engine, `http://localhost:3000/projects/1531/rooms`): manual `wallGap`/`finishGap` on a geometry room survive save and reload and `wallArea` matches the screen (room 1701 case); perimeter popover lists wall lengths and a wall length formula; one door shows its height/width; several doors show a dash; manual door size persists and clearing it returns the geometry value; Manual/Calculated badges
  - API scenarios against the Dockerized app (`http://localhost:8080`, `POST /api/rooms/project/{id}/save`, read-back via the room read endpoint): gaps and sources round trip, `wallArea` from effective gaps, sizes derived or `null`, out-of-range value rejected with the metric-range error, room without geometry unchanged
  - Repeatability: state the strategy explicitly (Setup: create a project and a room with geometry through the API with a `run-id` suffix in names; Teardown: delete the created rooms/project at the end). Name the actual Liquibase changeset number used (266 or the next free one from task 3.1)
  - Regression group at the end: no-geometry room, per-wall gaps without manual root value, per-wall gap formulas, five-metric MANUAL override, wall/door/window area formula popovers, AI import and visual editor without sources, metric-range error
  - Result artifacts are MD reports with tables; end the file with the report template (`| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |`, summary line with run-id and repeatability strategy)
  - _Requirements: 1.1, 1.2, 1.4, 1.5, 2.1, 2.2, 2.4, 2.5, 2.6, 2.7, 3.1, 3.2, 3.3, 3.4, 3.6, 3.7, 3.8_

- [x] 6. Checkpoint - Ensure all tests pass
  - Run only the affected backend classes: `RoomManualDimensionsExplorationTest`, `RoomManualDimensionsFixPropertyTest`, `RoomManualDimensionsPreservationPropertyTest`, `RoomMetricExplainerTest`, `RoomPerFieldOverridePropertyTest`, `RoomManualSourcePropertyTest`, `RoomCalculationFinishGapTest`, plus the frontend files from task 4.3. Do not run the full suite unless the user asks (about 20 minutes)
  - Ensure all tests pass, ask the user if questions arise.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "2"] },
    { "id": 1, "tasks": ["3.1", "3.5"] },
    { "id": 2, "tasks": ["3.2", "3.6"] },
    { "id": 3, "tasks": ["3.3", "4.3"] },
    { "id": 4, "tasks": ["3.4"] },
    { "id": 5, "tasks": ["3.7"] },
    { "id": 6, "tasks": ["3.8"] },
    { "id": 7, "tasks": ["4.1"] },
    { "id": 8, "tasks": ["4.2", "5"] },
    { "id": 9, "tasks": ["6"] }
  ]
}
```
