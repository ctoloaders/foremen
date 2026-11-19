# Implementation Plan

Notes for all tasks:
- No schema change, no new managed entity, no new permission (entity-creation-rules do not apply).
- Backend lives in the root repo (`foremen-backend`). Frontend lives in the nested repo `foremen-frontend` and is committed separately (see git-repo-structure). Commit only when the user asks.
- Backend tests: run only the affected classes with `--tests`, redirect to `/tmp/for-05-05c-test.log`, read results from `foremen-backend/build/test-results/test/TEST-<fqcn>.xml`. Never run the full suite. Frontend: `npx vitest --run <files>` from `foremen-frontend/`.

- [x] 1. Write bug condition exploration test (backend)
  - **Property 1: Bug Condition** - Labour adjustment ignores staged-only cells and is not a staged edit
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: Scope the property to the concrete failing case: a staged `ASSIGN` W/R (not persisted) plus an adjustment `(scope, targetId, pct)` with `pct` drawn from a small set (e.g. 10, 5, -20) for each scope `PROJECT`, `WORK_CATEGORY`, `WORK_ITEM`
  - Create a new class `com.foremen.service.estimate.LabourPriceAdjustmentExplorationIntegrationTest` (jqwik or JUnit parameterized, following the fixture of `EstimateAssignmentServiceIntegrationTest`)
  - Bug Condition (design `isBugCondition`): edit mode with staged-only in-scope cells
  - Test: call `previewStagedEdits` with a staged `ASSIGN` W/R, apply the adjustment through the only existing way (`applyLabourPriceAdjustment`) and preview again; assert the staged cell's labour equals `round2(unitPrice × (1 + pct/100)) × volume` (Expected Behavior from design)
  - Compile against current signatures only (no `ADJUST_LABOUR_PRICE` yet); the staged-kind assertions are completed in task 5.4
  - Run on UNFIXED code: `./gradlew test --tests "com.foremen.service.estimate.LabourPriceAdjustmentExplorationIntegrationTest" > /tmp/for-05-05c-test.log 2>&1; echo "EXIT=$?"`
  - **EXPECTED OUTCOME**: Test FAILS (staged-only cell keeps `labour = unitPrice × volume`, root cause 3)
  - Document counterexamples found (e.g. "PROJECT +10, staged ASSIGN W/R: labour not adjusted")
  - Mark task complete when the test is written, run, and the failure is documented
  - _Requirements: 1.2, 2.2_

- [x] 2. Write bug condition exploration test (frontend)
  - **Property 1: Bug Condition** - "%" control is an immediate write, not a staged edit
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **GOAL**: Surface counterexamples for root causes 1 and 2
  - **Scoped PBT Approach**: Scope to concrete cases: each of the three scopes with pct = 10
  - Create new file `foremen-frontend/src/features/estimate/EstimateTab.labourAdjustment.test.tsx` using the mocking pattern of `EstimateTab.test.tsx`
  - Case A (root cause 1): apply the project "%" control, assert the store holds an `ADJUST_LABOUR_PRICE` edit, the "Zmiany" count increased and no mutation was called
  - Case B (root cause 2): seed the matrix cache with a staged-projected value for cell A, apply the control with a DB-only matrix response, assert cell A keeps its staged value
  - Case C: stage the adjustment, undo, assert the matrix is as before
  - Run on UNFIXED code: `npx vitest --run src/features/estimate/EstimateTab.labourAdjustment.test.tsx` (cwd `foremen-frontend`)
  - **EXPECTED OUTCOME**: Tests FAIL (mutation is called, store stays empty, cell A resets)
  - Document counterexamples found
  - Mark task complete when the tests are written, run, and failures are documented
  - _Requirements: 1.1, 1.3, 2.1, 2.3, 2.6_

- [x] 3. Write preservation property tests (backend, BEFORE implementing fix)
  - **Property 2: Preservation** - Persisted result and all other staged kinds unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Create new class `com.foremen.service.estimate.LabourPriceAdjustmentPreservationPropertyTest`
  - Observe on UNFIXED code: `applyLabourPriceAdjustment` for each scope sets `unit_price_override = round2(unit_price × (1 + pct/100))` (HALF_UP), `pct = 0` resets, material lines untouched, `unit_price` / `value_net` unchanged
  - Observe: invalid scope, missing `targetId` for `WORK_CATEGORY` / `WORK_ITEM`, missing `pct` give 400 `error.validation`; non-DRAFT project is rejected; unknown target is a no-op
  - Observe: preview of staged sets made only of existing kinds (assign, unassign, set quantity, materials, apply package, apply cheapest, merge/remove package) returns the recorded matrix
  - Write jqwik property tests: for all random `pct` (scale 0-2, incl. negative and 0) and scope, persisted overrides equal the formula; for random existing-kind staged sets, preview output equals the recorded baseline
  - Run on UNFIXED code: `./gradlew test --tests "com.foremen.service.estimate.LabourPriceAdjustmentPreservationPropertyTest" --tests "com.foremen.service.estimate.EstimateAssignmentServiceIntegrationTest" > /tmp/for-05-05c-test.log 2>&1; echo "EXIT=$?"`
  - **EXPECTED OUTCOME**: Tests PASS (baseline behavior to preserve)
  - Mark task complete when the tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7_

- [x] 4. Write preservation property tests (frontend, BEFORE implementing fix)
  - **Property 2: Preservation** - Other staged edits, collapse and overlay unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Create new file `foremen-frontend/src/features/estimate/state/stagedEditsPreservation.test.ts`
  - Observe on UNFIXED code: `collapseStagedEdits` and `projectStagedEdits` output for sets made of existing kinds; stage / undo / redo / discard counts in the store; "%" controls disabled when the matrix is read-only
  - Write tests (property-based if `estimateMatrixStore.property.test.ts` already uses a property library, else example tests over a generated set) asserting those observed outputs
  - Run on UNFIXED code: `npx vitest --run src/features/estimate/state` (cwd `foremen-frontend`)
  - **EXPECTED OUTCOME**: Tests PASS
  - Mark task complete when the tests are written, run, and passing on unfixed code
  - _Requirements: 3.5, 3.6_

- [x] 5. Fix for labour price adjustment bypassing the staged-edit flow

  - [x] 5.1 Backend: `EditKind`, `StagedEdit`, helper and replay in `EstimateAssignmentService.java`
    - Add `EditKind.ADJUST_LABOUR_PRICE`
    - Add components `scope`, `targetId`, `pct` to the `StagedEdit` record, a 10-argument compat constructor delegating with `null, null, null`, and factory `adjustLabourPrice(scope, targetId, pct)`; existing factories pass `null`
    - Extract `adjustLinesInScope(estimate, scope, targetId, pct)` (validation, factor, loop over `estimate.getLines()`, HALF_UP scale 2); `applyLabourPriceAdjustment` becomes `resolveDraftEstimate` + helper + `flush`
    - Add `case ADJUST_LABOUR_PRICE` to `applyStagedEdit`; confirm `consumptionsForStagedEdits` and `latestAppliedPackageCode` tolerate null `workItemId`
    - Mark `applyLabourPriceAdjustment` `@Deprecated` with Javadoc pointing to the staged edit
    - Check cheaply with `./gradlew compileJava compileTestJava > /tmp/for-05-05c-compile.log 2>&1; echo "EXIT=$?"`
    - _Bug_Condition: isBugCondition(X) where X.editMode = true AND adjustment not recorded as a staged edit_
    - _Expected_Behavior: expectedBehavior(result) from design (replayed in order, in-scope persisted and staged-only lines, nothing persisted until Save)_
    - _Preservation: saved arithmetic, validation, DRAFT gate and `@DynamicUpdate` unchanged_
    - _Requirements: 2.2, 2.4, 3.1, 3.2, 3.3, 3.4_

  - [x] 5.2 Backend: `StagedEditPayload` in `controller/model/ApplyAssignmentsRequest.java`
    - Add `scope`, `targetId`, `pct`; 10-argument compat constructor; `toStagedEdit()` maps the new fields; update Javadoc
    - _Bug_Condition: isBugCondition(X) from design_
    - _Expected_Behavior: payload carries the adjustment through preview, Save and the shared draft_
    - _Requirements: 2.4, 2.5, 3.5_

  - [x] 5.3 Backend: deprecate the direct endpoint in `EstimateMatrixController.java`
    - Mark `adjustLabourPrice` `@Deprecated` with a Javadoc note (does not see staged or shared-draft edits); add `@Operation(deprecated = true)` only if springdoc annotations are already used there; path, body, permission unchanged
    - _Preservation: direct endpoint contract (design D3)_
    - _Requirements: 3.7_

  - [x] 5.4 Backend: staged replay integration tests (extend `EstimateAssignmentServiceIntegrationTest`)
    - Scopes `PROJECT` / `WORK_CATEGORY` / `WORK_ITEM` via staged edit + Save; `pct = 0` resets; material lines untouched; `unit_price` and `value_net` unchanged
    - Invalid scope / missing `targetId` / missing `pct` give 400 `error.validation` in preview and Save (Save leaves the DB unchanged); unknown target is a no-op; non-DRAFT project rejected on Save
    - Replay order rules D2.1-D2.5; preview rollback leaves `unit_price_override` unchanged
    - Complete the staged-kind assertions of task 1 against `ADJUST_LABOUR_PRICE`
    - Mixed set (assign, set quantity, `WORK_CATEGORY` adjustment) preview then Save gives consistent matrix labour and `unit_price_override`
    - _Requirements: 2.2, 2.3, 2.4, 2.6, 3.1, 3.2, 3.3_

  - [x] 5.5 Backend: property test `LabourPriceAdjustmentStagedReplayPropertyTest` (new class)
    - For random `pct`, scope and prior staged sets of existing kinds assert: in-scope lines equal `round2(unitPrice × (1 + pct/100))`; out-of-scope lines and every other edit's result equal the replay without the adjustment; staged path (Save) and direct path give identical overrides; preview then DB read shows no change
    - _Requirements: 2.1, 2.2, 2.4, 3.1, 3.5, 3.7_

  - [x] 5.6 Backend: payload and JSON round-trip tests (new class `StagedEditPayloadAdjustLabourPriceTest`)
    - Compat constructors leave the new components `null`; `toStagedEdit()` maps `scope` / `targetId` / `pct`
    - Jackson round trip (same mapper config as `EstimateCollabService`) keeps the new fields; a legacy body without them deserializes with `null`
    - _Requirements: 2.5, 3.5_

  - [x] 5.7 Frontend: types in `features/estimate/types/index.ts`
    - `StagedEditPayload.kind` gets `'ADJUST_LABOUR_PRICE'`; add optional `scope`, `targetId`, `pct`
    - _Requirements: 2.3_

  - [x] 5.8 Frontend: `collapseStagedEdits.ts` barrier
    - `isBarrier` returns true for `ADJUST_LABOUR_PRICE`; extend `collapseStagedEdits.test.ts` (assign/unassign pair not collapsed across it, two adjustments not merged)
    - _Requirements: 2.2, 3.5_

  - [x] 5.9 Frontend: `projectStagedEdits.ts` overlay no-op
    - Treat `ADJUST_LABOUR_PRICE` as a documented no-op (same group as `APPLY_CHEAPEST`); extend `projectStagedEdits.test.ts`
    - _Requirements: 2.1, 3.5_

  - [x] 5.10 Frontend: stage the adjustment in `EstimateTab.tsx` and `EstimateTab.test.tsx`
    - `handleAdjustLabourPrice` becomes `if (!canEdit) return; stage({ kind: 'ADJUST_LABOUR_PRICE', scope, targetId: targetId ?? undefined, pct })`; drop `useAdjustLabourPrice`, `stagedPreview.cancel()` + mutate, error toast and `isAdjustingLabourPrice`
    - Add `'ADJUST_LABOUR_PRICE'` to `CELL_EDIT_KINDS` so the debounced server preview is scheduled
    - Update `EstimateTab.test.tsx`: staging with the right `scope` / `targetId` / `pct`, no mutation call, no-op when `canEdit` is false
    - _Requirements: 2.1, 2.3, 3.6_

  - [x] 5.11 Frontend: staged list label and locales
    - `StagedChangesList.tsx`: label `estimate.changes.kind.ADJUST_LABOUR_PRICE` with `pct` and `target` (whole estimate, work category from a new `categoryNames` map, work from `workNames`); `categoryNames` is supplied from `EstimateTab.tsx` (task 5.10 must be done first)
    - `locales/pl.json` and `locales/ru.json`: same keys in both (PL "Korekta robocizny {{pct}}%: {{target}}", RU "Корректировка работ {{pct}}%: {{target}}")
    - Extend `StagedChangesList.test.tsx`: label per scope in PL and RU, entry has undo/remove
    - _Requirements: 2.3, 2.5_

  - [x] 5.12 Frontend: remove the direct-call hook and API function
    - Remove `useAdjustLabourPrice` from `api/mutation-hooks.ts`, `adjustLabourPrice` from `api/estimate-matrix-api.ts` (and `LabourPriceAdjustmentRequest` if unused), and their mocks in `EstimateTab.test.tsx`; `LabourPriceAdjustControl`, `WorkTypeGroupSection`, `WorksRoomsMatrix` keep their `onAdjustLabourPrice` prop
    - _Requirements: 2.3_

  - [x] 5.13 Frontend: store tests for stage / undo / redo / discard
    - New file `features/estimate/state/estimateMatrixStore.labourAdjustment.test.ts` (pattern of `estimateMatrixStore.batch.test.ts`): stage, undo, redo, discard, single-entry removal, "Zmiany" count, `localStorage` rehydration re-schedules the preview
    - _Requirements: 2.3, 2.6_

  - [x] 5.14 Verify bug condition exploration tests now pass
    - **Property 1: Expected Behavior** - Labour adjustment is a replayed staged edit
    - **IMPORTANT**: Re-run the SAME tests from tasks 1 and 2 - do NOT write new tests
    - Backend: `./gradlew test --tests "com.foremen.service.estimate.LabourPriceAdjustmentExplorationIntegrationTest" --tests "com.foremen.service.estimate.EstimateAssignmentServiceIntegrationTest" --tests "com.foremen.service.estimate.LabourPriceAdjustmentStagedReplayPropertyTest" --tests "com.foremen.controller.model.StagedEditPayloadAdjustLabourPriceTest" > /tmp/for-05-05c-test.log 2>&1; echo "EXIT=$?"` and read the JUnit XML
    - Frontend: `npx vitest --run src/features/estimate/EstimateTab.labourAdjustment.test.tsx src/features/estimate/EstimateTab.test.tsx src/features/estimate/state src/features/estimate/components/StagedChangesList.test.tsx` (cwd `foremen-frontend`)
    - **EXPECTED OUTCOME**: Tests PASS (confirms the bug is fixed)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

  - [x] 5.15 Verify preservation tests still pass
    - **Property 2: Preservation** - Persisted result and all other edits unchanged
    - **IMPORTANT**: Re-run the SAME tests from tasks 3 and 4 - do NOT write new tests
    - Backend: `./gradlew test --tests "com.foremen.service.estimate.LabourPriceAdjustmentPreservationPropertyTest" --tests "com.foremen.service.estimate.EstimateAssignmentServiceIntegrationTest" --tests "com.foremen.service.estimate.EstimateWorkSetsIntegrationTest" > /tmp/for-05-05c-test.log 2>&1; echo "EXIT=$?"`; include the existing stale-write test `labourPriceAdjustment_changesOnlyItsOwnScope_andSurvivesStaleConcurrentWrite`
    - Frontend: `npx vitest --run src/features/estimate/state` (cwd `foremen-frontend`)
    - **EXPECTED OUTCOME**: Tests PASS (no regressions)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

- [x] 6. Author test-cases.md
  - Create `test-cases.md` in this spec folder, in Russian, following the `.kiro/steering/test-cases.md` standard
  - UI scenarios (browser engine) on the estimate tab: "%" at project, work category and work item scopes; adjustment appears in "Zmiany (N)"; undo / redo / discard / removal of one entry; Save persists; staged-only cell is adjusted; other cells keep their staged values; pct = 0 reset; read-only matrix disables "%"
  - Collaborative scenario with two browser sessions (two users on the same project): user A stages an adjustment, user B sees it in the list and matrix with B's own edits preserved, and vice versa
  - API scenarios against the Dockerized app (`http://localhost:8080`, `docker compose up`): `POST /api/estimates/project/{projectId}/assignments` preview/save with an `ADJUST_LABOUR_PRICE` edit, validation 400 cases, non-DRAFT rejection, missing `ESTIMATE` UPDATE (403), legacy `labour-price-adjustment` endpoint still persists the override
  - Group by feature; each case has ID, title, preconditions, numbered steps with expected result per step
  - Repeatability: state the strategy per set (unique project/run-id generator per run, plus teardown removing created projects/estimates)
  - Regression group at the end (stale concurrent write, other staged kinds, offer snapshot reads the override)
  - MD report template with tables (step, action, expected, actual, status)
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.3, 3.5, 3.6, 3.7_

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests from tasks 5.14 and 5.15 pass; ask the user if questions arise
  - **Deploy-order risk**: deploy the backend before the frontend. An old backend node cannot deserialize `kind = ADJUST_LABOUR_PRICE` in a Save or a shared-draft entry, so a new frontend against an old backend breaks staging of adjustments. An old frontend works against the new backend because the deprecated endpoint stays. During a rolling backend deploy, old nodes reading a draft entry with the new kind can also fail; verify there are no editing sessions during rollout
  - Commit frontend changes in `foremen-frontend/` and spec/backend changes in the root repo separately, only when the user asks; check `git status` in both repos

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "2", "3", "4"] },
    { "id": 1, "tasks": ["5.1", "5.7", "5.8", "5.9"] },
    { "id": 2, "tasks": ["5.2", "5.3", "5.10", "5.13"] },
    { "id": 3, "tasks": ["5.4", "5.11", "5.12"] },
    { "id": 4, "tasks": ["5.6"] },
    { "id": 5, "tasks": ["5.5"] },
    { "id": 6, "tasks": ["5.14"] },
    { "id": 7, "tasks": ["5.15"] },
    { "id": 8, "tasks": ["6"] },
    { "id": 9, "tasks": ["7"] }
  ]
}
```
