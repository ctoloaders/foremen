# Labour Price Adjustment Bugfix Design

## Overview

The "%" labour price control (project / work category / work item scope) writes `unit_price_override` straight to the database through `POST /api/estimates/project/{projectId}/labour-price-adjustment` and replaces the cached matrix with the response. It is the only estimate edit that is not a staged edit. Consequences: the response matrix carries no staged edits (other cells, including collaborators' edits from the FOR-05-13 shared draft, reset until the next preview), the adjustment ignores cells that exist only as staged edits, and it is absent from "Zmiany (N)", undo/redo/discard, Save and the shared draft.

Fix approach: make the adjustment a staged edit.

- A new `EditKind.ADJUST_LABOUR_PRICE` carries `scope`, `targetId` and `pct`. The frontend stages it like any other edit, so it joins the staged list, undo/redo/discard, `localStorage` persistence and the collaborative draft with no extra plumbing.
- The backend replays it in order inside `applyStagedEdit`, which is shared by the debounced preview (`doAssemblePreview`, rolled back) and the batched Save (`doApplyAssignments`). Both paths run the same arithmetic as today's endpoint, through one extracted helper.
- The UI stops calling the direct endpoint. The endpoint stays for API callers (requirement 3.7) and is marked deprecated.

No schema change, no new managed entity, no new permission. The pricing contract is unchanged: only `unit_price_override` is written, `unit_price` and `value_net` are untouched, and `@DynamicUpdate` on `EstimateLineEntity` continues to protect the committed override from a stale concurrent write.

## Glossary

- **Bug_Condition (C)**: A labour adjustment is applied while editing with staged edits (own or from collaborators), or with staged-only cells, or in edit mode generally (see `isBugCondition` in bugfix.md).
- **Property (P)**: The adjustment is a staged edit, replayed in order, changing only in-scope labour prices, undoable, shared, and persisted only at Save.
- **Preservation**: Saved `unit_price_override` arithmetic, original `unit_price` / `value_net`, validation, DRAFT gate, permission, `@DynamicUpdate`, behaviour of every other staged edit, and the direct endpoint contract.
- **Staged edit**: one entry of the client staged set (`StagedEditPayload`), replayed by the server as `StagedEdit` (`EstimateAssignmentService.EditKind`).
- **Replay**: `applyStagedEdit` applied to the in-memory estimate graph in staged order, by preview (rolled back) or Save (committed).
- **Original price**: `EstimateLineEntity.unitPrice`, the frozen catalog price at the time the line was created.
- **Effective price**: `unitPriceOverride`, read by the matrix assembler, offer snapshot and `effectiveValueNet()`.
- **Staged-only cell**: a `(work, room)` cell created by a staged `ASSIGN` that exists in the replay graph but not yet in the database.
- `applyLabourPriceAdjustment` (`EstimateAssignmentService`): current direct write path.
- `useAdjustLabourPrice` (`features/estimate/api/mutation-hooks.ts`): current frontend mutation hook for it.

## Bug Details

### Bug Condition

`handleAdjustLabourPrice` in `EstimateTab.tsx` cancels the staged preview, calls the endpoint, and on success `setQueryData(matrix, response)`. The response is assembled from the database only. Anything held in the store (own edits) or in the shared draft (others' edits) is missing from it, and the adjustment itself never enters the store.

**Formal Specification:**
```
FUNCTION isBugCondition(X)
  INPUT: X of type LabourAdjustAction { scope, targetId, pct, editMode, stagedEdits, sharedDraft }
  OUTPUT: boolean

  RETURN X.editMode = true
         AND ( X.stagedEdits NOT EMPTY
               OR X.sharedDraft contains edits of other users
               OR adjustment(X) is not recorded as a staged edit )
END FUNCTION
```

Because the adjustment is never recorded as a staged edit on the unfixed code, every edit-mode adjustment satisfies the condition. This matches the condition in bugfix.md.

### Examples

- Staged unsaved `ASSIGN` W/R, collaborator staged `SET_QUANTITY` on another cell. User applies +10% at `WORK_ITEM` W. Actual: W/R keeps the original labour price, the collaborator's cell shows its pre-edit value until the next preview, "Zmiany" shows two entries. Expected: W/R labour = round2(unitPrice × 1.10) × volume, collaborator cell unchanged, "Zmiany" shows three entries.
- Applying +5% at `PROJECT`, then pressing undo. Actual: nothing is undone, the override is already in the database. Expected: the matrix returns to the pre-adjustment state, database untouched.
- Applying +10% at `WORK_CATEGORY` C, then Save. Actual: already written before Save. Expected: written by Save together with the other edits in one transaction.
- Edge: `pct = 0` at `WORK_ITEM` W resets W's override to its original price (staged as a normal edit, persisted at Save).
- Edge: no staged edits, no collaborators. The user still gets a staged entry instead of an immediate write (behaviour change by design, requirement 2.3).

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Saved result of an adjustment per line: `unit_price_override = round2(unit_price × (1 + pct/100))`, HALF_UP, `pct = 0` resets, material lines never touched (3.1).
- `unit_price` and `value_net` stay on the original price; matrix, offer snapshot and `effectiveValueNet()` keep reading `unit_price_override` (3.2).
- Validation, DRAFT gate, `ESTIMATE` UPDATE permission (3.3), `@DynamicUpdate` (3.4).
- Preview, share, undo and Save of every other staged kind (3.5).
- "%" controls disabled when the matrix is read-only (3.6).
- Direct endpoint accepts the same body and persists the override (3.7).

**Scope:**
All staged sets that contain no `ADJUST_LABOUR_PRICE` edit replay exactly as today. The new `switch` branch is reached only by the new kind, and the new record components are `null` for every existing kind.

## Design Decisions

### D1. Wire shape of the new staged edit

`StagedEdit` (service record) and `StagedEditPayload` (request / collab record, `ApplyAssignmentsRequest.java`) get three new trailing nullable components: `scope` (`LabourPriceAdjustmentScope`), `targetId` (`Long`), `pct` (`BigDecimal`).

- Reusing `workItemId` / `quantity` was rejected: `WORK_CATEGORY` has no matching field and `quantity` has a different meaning (a positive Volume or line quantity) and is used by `collapseStagedEdits`.
- Existing positional constructor calls (about 15 in `EstimateAssignmentServiceIntegrationTest`, `EstimateWorkSetsIntegrationTest` and the static factories) must keep compiling. Both records get a secondary 10-argument constructor that delegates with `null, null, null`. Only the factories and the `toStagedEdit()` mapper use the full constructor.
- `StagedEdit.adjustLabourPrice(scope, targetId, pct)` factory added, like `assign` / `removePackage`.
- Redis draft bodies written before the deploy have no such properties, so they deserialize with `null`. No draft migration. `EstimateCollabService.addEdit` stores `StagedEditPayload` as is, so the shared draft carries the new kind without change. A rolling deploy where an old node reads an entry with an unknown `kind` is covered in Risks.

### D2. (a) Replay order: a cell staged after an adjustment does NOT inherit it

The adjustment is a one-shot write at its position in the staged order, not a live multiplier. This is how the existing endpoint behaves and what bugfix.md assumes. Consequences stated explicitly:

1. Edits before the adjustment in staged order are covered. Edits after it are not.
2. A staged `ASSIGN` after the adjustment that adds a room to an existing line keeps the line's adjusted override (the override lives on the line, shared by all rooms of the work). A staged `ASSIGN` after the adjustment that creates a new line (no line for that work yet) gets `override = unit_price` (`createLine` seeds it).
3. An `UNASSIGN` of the last cell of a work removes the line, so an adjustment staged earlier for it has nothing left to apply to. A later re-assign creates a fresh line at the original price.
4. Overlapping adjustments are each computed from the ORIGINAL price (not compounding), so the later one wins on the lines both cover. `PROJECT +10` then `WORK_ITEM W +5` leaves W at +5% and every other line at +10%. The reverse order leaves everything at +10%.
5. Undoing or discarding an adjustment removes it from the set, so replay falls back to the previous adjustment or to the persisted override, never to a value invented by the client (requirement 2.6).

Alternative considered: make adjustments apply "retroactively and prospectively" (post-pass at the end of replay, or a stored `adjustmentPct` on the line). A post-pass would make staged order irrelevant between an adjustment and cell edits but would reorder adjustments relative to each other, so the "later wins" rule of the existing UI (a user who sets +10% then +5% on one work expects +5%) would need its own sub-ordering. A stored per-line pct would need a schema change and would break the `pct = 0` reset meaning and the one-shot contract of 3.1. Neither is clearly better, and the one-shot rule matches how the user performed the actions in order, so it stays. The hint in the UI tooltip is not required; the staged list shows order.

### D3. (b) The direct endpoint: keep for API callers, stop using it from the UI, mark deprecated

Decision: keep `POST .../labour-price-adjustment` and `applyLabourPriceAdjustment` with identical behaviour. Remove `useAdjustLabourPrice`, `adjustLabourPrice` (api function) and their wiring from the frontend. Add `@Deprecated` (Javadoc `@deprecated` pointing to the staged edit) on the service method and controller handler, and `@Operation(deprecated = true)` if springdoc annotations are already used in that controller.

Justification:
- Requirement 3.7 says existing API clients continue to work "unless the design explicitly deprecates it". Deprecating (not removing) honours that wording. Removing would break any external script for no gain.
- There is no known non-UI caller in the repo (the only caller is `EstimateTab.tsx`; only a backend integration test calls the service method). Keeping it costs one method.
- Keeping the endpoint means keeping the immediate-write semantics, which are by nature outside the staged flow. That is acceptable only for callers that do not use the staged UI. A comment states that it does not see staged or shared-draft edits.
- Both paths share one extracted private helper `adjustLinesInScope(estimate, scope, targetId, pct)` (validation + factor + loop). That guarantees the saved result of a staged adjustment and a direct call is identical (3.1) and avoids two copies of the rounding rule.

### D4. Validation and permissions for the staged kind

- Structural validation (null scope, null pct, null `targetId` for `WORK_CATEGORY` / `WORK_ITEM`) lives in the shared helper and throws the same `ForemenApiException(BAD_REQUEST, "error.validation")` as today. It throws in Save and in preview alike: a malformed payload is a client bug, and the existing direct endpoint rejects the same input (3.3). A target id that matches no line is a no-op (as today), not an error.
- Preview is a rolled-back transaction, so a bad payload cannot persist. Save is one transaction: an invalid adjustment aborts the whole Save atomically with HTTP 400, no partial write.
- Permission: the adjustment is persisted only through the Save endpoint, which already requires `ESTIMATE` UPDATE and a DRAFT estimate (`resolveDraftEstimate`). The preview endpoint stays `ESTIMATE` READ and never persists. No new `@PermissionResource` / `@PermissionOperation`, no new resource seed (entity-creation-rules do not apply: no new managed entity).
- The collab layer does not validate payload content beyond `kind != null`; a collaborator without UPDATE cannot Save, same as for other kinds. Not changed.

### D5. Interaction with collapse, persistence and cumulative semantics

- `collapseStagedEdits.ts` treats `ADJUST_LABOUR_PRICE` as a barrier (`isBarrier`), like `MERGE_PACKAGE_MATERIALS`. Collapsing an `ASSIGN`/`UNASSIGN` pair across an adjustment, or merging two adjustments, is not needed for correctness and the barrier keeps order-dependent semantics (D2.4) trivially safe.
- `pct` is relative to the original price, so repeated edits are not cumulative. This is unchanged.
- `unit_price_override` of a line whose adjustment is staged is not visible in the DB until Save. The offer snapshot / offer-impact check (`fetchOfferImpact`) runs on Save as for any estimate change; no new rule.

## Hypothesized Root Cause

1. **Immediate write outside the staged pipeline** (confirmed by code): `handleAdjustLabourPrice` calls a mutation that writes to the DB and never calls `stage(...)`. It is the single estimate write that bypasses the store, so it has no undo, no count and no draft entry.
2. **Cache overwrite with an unprojected matrix** (confirmed): `useAdjustLabourPrice.onSuccess` does `queryClient.setQueryData(matrix, response)`. The response is a pure DB read. The scheduled re-preview only runs when `useStore.getState().edits.length > 0`, i.e. never for edits that exist only in the shared draft of another user, and not synchronously, so the flicker/reset appears in between.
3. **Persisted-lines-only loop**: `applyLabourPriceAdjustment` iterates `estimate.getLines()` of the DB-loaded estimate. Staged cells are not in it, so they get no adjustment.
4. **Replay has no kind for it**: `EditKind` and `StagedEdit` have no way to express a scope/pct adjustment, so even a correct client could not stage one.

## Correctness Properties

Property 1: Bug Condition - Labour adjustment is a replayed staged edit

_For any_ labour adjustment `(scope, targetId, pct)` applied while the bug condition holds (isBugCondition returns true), the fixed system SHALL record it as a staged edit that is counted in the staged list, shared through the collaborative draft and undoable/redoable/discardable, SHALL change only the labour price of in-scope lines (persisted lines and lines created by earlier staged edits in the same staged set) to `round2(unitPrice × (1 + pct/100))`, SHALL leave every out-of-scope cell and every other staged edit unchanged, and SHALL persist nothing until Save.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6**

Property 2: Preservation - Persisted result and all other edits unchanged

_For any_ input where the bug condition does NOT hold (a staged set without an adjustment, or a direct API call to the legacy endpoint), the fixed system SHALL produce the same result as the original system: the same preview matrix and the same persisted rows for every other staged kind, `unit_price` and `value_net` untouched, the same `unit_price_override` for the same `(scope, targetId, pct)` on persisted lines, the same validation / permission / DRAFT-gate errors, and the committed override surviving a concurrent stale write (`@DynamicUpdate`).

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**

## Fix Implementation

### Changes Required

**Backend (root repo, `foremen-backend`)**

**File**: `service/estimate/EstimateAssignmentService.java`

1. **`EditKind`**: add `ADJUST_LABOUR_PRICE` (Javadoc: scoped labour override, one-shot at its staged position, labour only).
2. **`StagedEdit`**: add components `scope`, `targetId`, `pct` after `quantity`; add the 10-argument compatibility constructor; add factory `adjustLabourPrice(scope, targetId, pct)`. Update the existing static factories to pass `null` for the new components (or delegate through the compat constructor).
3. **Extract the helper** `adjustLinesInScope(EstimateEntity estimate, LabourPriceAdjustmentScope scope, Long targetId, BigDecimal pct)` containing the validation, `factor = 1 + pct/100`, loop over `estimate.getLines()` with `lineInScope`, and `setUnitPriceOverride(original × factor, scale 2, HALF_UP)`. `applyLabourPriceAdjustment` becomes: `resolveDraftEstimate` + helper + `flush` (behaviour identical).
4. **`applyStagedEdit`**: add `case ADJUST_LABOUR_PRICE -> adjustLinesInScope(estimate, edit.scope(), edit.targetId(), edit.pct());`. The switch is exhaustive over the enum, so the compiler enforces this. Lines created by earlier edits in the same replay are already in `estimate.getLines()` (that is how later edits resolve them via `findLine`), so staged-only cells are covered without a flush. The existing `entityManager.flush()` at the end of `doApplyAssignments` / `doAssemblePreview` materializes the writes.
5. **Deprecation**: `@Deprecated` + Javadoc on `applyLabourPriceAdjustment` (the staged path is the supported one).
6. **`latestAppliedPackageCode`** and `consumptionsForStagedEdits`: no change (the new kind has no package code and no work item to preload). Verify `consumptionsForStagedEdits` ignores edits with a null `workItemId` (it must not NPE).

**File**: `controller/model/ApplyAssignmentsRequest.java`

7. `StagedEditPayload`: add `scope`, `targetId`, `pct`; compat 10-argument constructor; `toStagedEdit()` passes them through. Update the Javadoc `@param` list.

**File**: `controller/EstimateMatrixController.java`

8. Mark `adjustLabourPrice` handler `@Deprecated` with a Javadoc note. Behaviour, path, body (`LabourPriceAdjustmentRequest`), permission annotation unchanged.

**File**: `collab/*`

9. No code change expected. A serialization round-trip test (see Testing) proves the new fields survive the Redis JSON round trip.

**Frontend (nested repo, `foremen-frontend`; commit separately per git-repo-structure)**

**File**: `features/estimate/types/index.ts`

10. `StagedEditPayload.kind` gets `'ADJUST_LABOUR_PRICE'`; add optional `scope?: LabourPriceAdjustmentScope`, `targetId?: number`, `pct?: number`. The existing `LabourPriceAdjustmentRequest` type stays only if still used; otherwise it is removed with the api function.

**File**: `features/estimate/EstimateTab.tsx`

11. `handleAdjustLabourPrice` becomes `if (!canEdit) return; stage({ kind: 'ADJUST_LABOUR_PRICE', scope, targetId: targetId ?? undefined, pct })`. Drop `useAdjustLabourPrice`, the `stagedPreview.cancel()` + mutate dance, the error toast and `isAdjustingLabourPrice` (no pending state: staging is synchronous). The `busy` prop of `LabourPriceAdjustControl` stays optional and simply unused.
12. Add `'ADJUST_LABOUR_PRICE'` to `CELL_EDIT_KINDS`, so staging schedules the debounced server preview (the numbers come from the server; the pure overlay cannot know the original price per line).

**File**: `features/estimate/state/projectStagedEdits.ts`

13. The overlay `switch` treats `ADJUST_LABOUR_PRICE` as a documented no-op (same group as `APPLY_CHEAPEST`), so the pure overlay stays total.

**File**: `features/estimate/state/collapseStagedEdits.ts`

14. `isBarrier` returns true for `ADJUST_LABOUR_PRICE`.

**File**: `features/estimate/components/StagedChangesList.tsx` and `locales/pl.json`, `locales/ru.json`

15. Label for the new kind under `estimate.changes.kind.ADJUST_LABOUR_PRICE`, e.g. PL "Korekta robocizny {{pct}}%: {{target}}", RU "Корректировка работ {{pct}}%: {{target}}". `target` resolves to the whole-estimate label (`PROJECT`), the work-category name (`WORK_CATEGORY`, from the matrix groups: add a `categoryNames` map next to `workNames`) or the work name (`WORK_ITEM`, existing `workNames`, using `targetId`). Pass `pct` as an interpolation value. `EstimateMessageBundleParityTest`-style key parity between PL and RU is respected.

**Files**: `api/mutation-hooks.ts`, `api/estimate-matrix-api.ts`, `EstimateTab.test.tsx`

16. Remove `useAdjustLabourPrice`, `adjustLabourPrice` and their mocks. `WorkTypeGroupSection`, `WorksRoomsMatrix` and `LabourPriceAdjustControl` keep their `onAdjustLabourPrice` prop and signature (no component API change).

**Collaboration (no code change in `EstimateCollabProvider`)**: the staged edit goes through `stage(...)`, which already publishes the payload to the shared draft; received entries are replayed by the same preview. Because the payload is a normal draft entry, other editors' edits are never reset by it.

### Order of work

Backend first (kind, records, helper, replay, tests), then frontend (types, stage, list label, removal of the hook). The frontend change is safe to deploy after the backend: an old frontend keeps working with the deprecated endpoint.

## Testing Strategy

### Validation Approach

Two phases: reproduce the bug on UNFIXED code with tests that fail for the reasons in the root cause analysis, then verify the fix and preservation. Per `.kiro/steering/test-execution-rules.md`: run only the affected classes with `--tests`, redirect to `/tmp/for-05-05c-test.log`, read results from `foremen-backend/build/test-results/test/TEST-<fqcn>.xml`, never the full `./gradlew build`. Frontend uses vitest `--run` on the affected files.

### Exploratory Bug Condition Checking

**Goal**: Show the bug on the unfixed code and confirm or refute the root cause.

**Test Plan**: Write the tests below against the current code (before the production change). They compile against current signatures where possible; the staged-kind tests are written against the intended API in the same commit as the fix and the exploration is performed on the unfixed behaviour through the legacy path.

**Test Cases**:
1. **Backend staged-only cell** (`EstimateAssignmentServiceIntegrationTest`): stage `ASSIGN` W/R in `previewStagedEdits`, then apply the adjustment through the only existing way (`applyLabourPriceAdjustment` followed by a preview). Assert the staged cell's labour reflects `+10%`. Fails on unfixed code: the legacy call only touches persisted lines (root cause 3).
2. **Frontend: staging** (`EstimateTab.test.tsx`): click the project "%" control, apply 10, assert the store has an `ADJUST_LABOUR_PRICE` edit and the "Zmiany" count increased. Fails on unfixed code: the mutation mock is called and the store stays empty (root cause 1).
3. **Frontend: cache overwrite** (`EstimateTab.test.tsx`, or a hook test of `useAdjustLabourPrice`): seed the matrix cache with a staged-projected value for cell A, simulate the adjustment `onSuccess` with a DB-only matrix, assert cell A is unchanged. Fails on unfixed code (root cause 2).
4. **Undo** (store test): stage an adjustment, undo, assert the matrix and persisted override are as before. Fails on unfixed code (no entry to undo).

**Expected Counterexamples**:
- Staged-only cell keeps `labour = unitPrice × volume`, not `round2(unitPrice × 1.10) × volume`.
- No `ADJUST_LABOUR_PRICE` entry in the store or staged list; cell A resets to its pre-edit value after the response.
- Possible causes: persisted-only loop, `setQueryData` with a DB-only matrix, missing kind. If tests 1-3 pass on unfixed code, re-hypothesize.

### Fix Checking

**Goal**: For every input where the bug condition holds, the fixed code behaves as in Property 1.

**Pseudocode:**
```
FOR ALL X WHERE isBugCondition(X) DO
  stagedSet' := stagedSet ++ [ADJUST_LABOUR_PRICE(X.scope, X.targetId, X.pct)]
  preview := previewStagedEdits(project, stagedSet')
  ASSERT every in-scope line's effective labour = round2(unitPrice × (1 + pct/100)) × volume
  ASSERT every out-of-scope cell = previewStagedEdits(project, stagedSet) cell
  ASSERT DB unchanged after the preview (rollback)
  saved := saveAndAssemble(project, stagedSet')
  ASSERT persisted override per in-scope line = round2(unitPrice × factor); unit_price, value_net unchanged
END FOR
```

### Preservation Checking

**Goal**: Staged sets without the new kind, and the legacy endpoint, behave as before.

**Pseudocode:**
```
FOR ALL stagedSet WHERE no edit.kind = ADJUST_LABOUR_PRICE DO
  ASSERT previewStagedEdits_original(stagedSet) = previewStagedEdits_fixed(stagedSet)
  ASSERT saveAndAssemble_original(stagedSet) = saveAndAssemble_fixed(stagedSet)
END FOR
FOR ALL (scope, targetId, pct) DO
  ASSERT overrides after applyLabourPriceAdjustment (fixed) = overrides after StagedEdit ADJUST_LABOUR_PRICE saved (fixed)
END FOR
```

**Testing Approach**: Property-based testing (jqwik on the backend, fast-check if already used for the store, else example tests) for the equivalence of the staged and direct paths and for out-of-scope invariance. The existing property/integration tests of the other kinds are the oracle for "unchanged".

**Test Cases**:
1. **Existing estimate tests**: `EstimateAssignmentServiceIntegrationTest` and `EstimateWorkSetsIntegrationTest` still pass (this exercises the compat constructor and every existing kind).
2. **Direct endpoint equivalence**: for the same `(scope, targetId, pct)`, the direct call and a staged adjustment + Save yield identical `unit_price_override` for every line.
3. **Concurrent stale write**: the existing `labourPriceAdjustment_changesOnlyItsOwnScope_andSurvivesStaleConcurrentWrite` test still passes (3.4).
4. **Frontend collapse/overlay**: existing `collapseStagedEdits.test.ts`, `projectStagedEdits.test.ts` unchanged and green.

### Unit Tests

Backend (affected classes only):
- `EstimateAssignmentServiceIntegrationTest` (extend): scope `PROJECT` / `WORK_CATEGORY` / `WORK_ITEM` via staged edit + Save; `pct = 0` resets; material lines untouched; invalid scope / missing `targetId` / missing `pct` → 400 `error.validation` in both Save and preview (Save leaves the DB unchanged); unknown target is a no-op; non-DRAFT project rejected on Save; `unit_price` and `value_net` unchanged; replay order rules D2.1-D2.5 (assign before adjustment is covered, assign creating a new line after is not, add-room after keeps the override, unassign last cell then re-assign resets, overlapping scopes later-wins).
- Preview rollback: after `previewStagedEdits` with an adjustment, `unit_price_override` in the DB is unchanged.
- A small test for `StagedEditPayload` / `StagedEdit`: compat constructor leaves the new components `null`; `toStagedEdit()` maps `scope` / `targetId` / `pct`.
- JSON round trip of `StagedEditPayload` with the new fields (Jackson, the same mapper config as `EstimateCollabService`), and deserialization of a legacy body without them.
- Controller: the legacy endpoint still returns the matrix and persists the override; permission denied without `ESTIMATE` UPDATE (existing controller tests, extended only if none cover this).

Frontend (vitest):
- `EstimateTab.test.tsx`: applying the control stages `ADJUST_LABOUR_PRICE` with the right `scope` / `targetId` / `pct`, does not call any mutation, schedules a preview, and is a no-op when `canEdit` is false.
- `collapseStagedEdits.test.ts`: `ADJUST_LABOUR_PRICE` is a barrier; an `ASSIGN`/`UNASSIGN` pair is not collapsed across it.
- `projectStagedEdits.test.ts`: the overlay leaves the matrix unchanged for the new kind.
- `StagedChangesList.test.tsx`: label for each scope (project / category / work) in PL and RU; the entry has undo/remove like others.
- Store test (`estimateMatrixStore.batch.test.ts` pattern): stage → undo → redo → discard sequences, "Zmiany" count, and persistence to `localStorage` rehydration re-scheduling the preview.

### Property-Based Tests

- Backend jqwik (new class `LabourPriceAdjustmentStagedReplayPropertyTest`, or additions to `EstimateApplyLayeringPropertyTest` if it fits its fixture): for random `pct` (scale 0-2, incl. negative and 0), random scope and random prior staged set made of existing kinds, assert (i) in-scope lines get exactly `round2(unitPrice × (1 + pct/100))`, (ii) out-of-scope lines and every non-adjustment edit's result equal the same replay without the adjustment, (iii) the staged path and the direct path produce the same overrides, (iv) preview then DB read shows no change.
- Frontend (only if a property library is already used in the estimate state tests, as in `estimateMatrixStore.property.test.ts`): random interleavings of stage / undo / redo / discard keep the store consistent and never leave an adjustment without its staged id.

### Integration Tests

- Backend end-to-end: stage a mixed set (assign, set quantity, adjustment at `WORK_CATEGORY`) → preview → Save → matrix labour and `unit_price_override` consistent; offer snapshot reads the override (FOR-05-07 contract).
- Collaboration: with the collab feature enabled against the test Redis setup if the repo has one, add an `ADJUST_LABOUR_PRICE` entry as user A and assert user B's `draft()` returns it in `seq` order with the other entries intact; a Save with `stagedIds` removes exactly the committed entries. If no Redis testcontainer exists for collab, cover it with the JSON round-trip unit test and leave a live check to the `test-cases.md` UI scenarios.
- Frontend integration (`EstimateTab.test.tsx`): staged own edit + received collaborator edit + new adjustment → rendered matrix keeps the other cells' staged values after the preview response.
- Post-fix UI/API checks against the Docker stack are described in `test-cases.md` (authored in the tasks phase per `.kiro/steering/test-cases.md`).

### Commands

Backend (affected classes only, results via the log and JUnit XML):
```
./gradlew test --tests "com.foremen.service.estimate.EstimateAssignmentServiceIntegrationTest" \
  --tests "com.foremen.service.estimate.EstimateWorkSetsIntegrationTest" \
  --tests "com.foremen.service.estimate.LabourPriceAdjustmentStagedReplayPropertyTest" \
  > /tmp/for-05-05c-test.log 2>&1; echo "EXIT=$?"
```
Frontend (from `foremen-frontend/`):
```
npx vitest --run src/features/estimate/EstimateTab.test.tsx src/features/estimate/state src/features/estimate/components/StagedChangesList.test.tsx
```
Use `getDiagnostics` and `./gradlew compileJava compileTestJava` (redirected to a log) for cheap compile checks after the record change.

## Risks and Notes

- **Rolling deploy**: an older backend node receiving a draft entry or a Save with `kind = ADJUST_LABOUR_PRICE` fails to deserialize the enum. Deploy backend before frontend (the order of work above); the shared draft TTL bounds stale entries. If the Redis entries must be tolerated by an old node, no mitigation is planned beyond the order.
- **Preview latency**: the adjusted numbers appear after the debounced preview, like `APPLY_CHEAPEST`. A client-side optimistic overlay is out of scope: it would need each row's original price in the matrix DTO, and the preview already gives exact server numbers.
- **`pct` bounds**: the current contract has no lower bound (a value of -100 or below gives a zero or negative price). Not changed here (3.3 says keep the existing validation); a separate decision if the business wants a bound.
- **Behaviour change**: without staged edits or collaborators, the adjustment is no longer immediate; it needs Save. This is requirement 2.3 and is intended.
- **Repos**: backend, spec and `test-cases.md` commit from the root repo; frontend files commit from `foremen-frontend/` (git-repo-structure standard). A final task must verify `git status` in both.

## Architecture

The adjustment travels the existing staged-edit pipeline. No new component or endpoint.

```
"%" control -> stage(ADJUST_LABOUR_PRICE) -> staged store (undo/redo/discard, localStorage)
                                          -> shared draft (STOMP/Redis, FOR-05-13)
                                          -> debounced POST .../preview  -> applyStagedEdit (rolled back)
Save -> POST .../assignments -> applyStagedEdit (committed) -> recompute -> unit_price_override persisted
```

Preview and Save share `applyStagedEdit`, so the displayed matrix and the persisted result cannot diverge.

## Components and Interfaces

- `EstimateAssignmentService`: `EditKind.ADJUST_LABOUR_PRICE`, `StagedEdit.adjustLabourPrice(scope, targetId, pct)`, private `adjustLinesInScope(...)`, deprecated `applyLabourPriceAdjustment(...)`.
- `ApplyAssignmentsRequest.StagedEditPayload`: new `scope`, `targetId`, `pct` (also the collab draft payload).
- `EstimateMatrixController.adjustLabourPrice`: unchanged contract, deprecated.
- Frontend: `EstimateTab.handleAdjustLabourPrice` (stages), `CELL_EDIT_KINDS`, `projectStagedEdits` (no-op), `collapseStagedEdits` (barrier), `StagedChangesList` (label).

## Data Models

No database change. `StagedEditPayload` / `StagedEdit` gain three nullable components (D1). `StagedEditPayload` in TypeScript gains `'ADJUST_LABOUR_PRICE'`, `scope?`, `targetId?`, `pct?`. Redis draft bodies stay backward compatible (missing properties read as `null`).

## Error Handling

- Malformed adjustment (null scope or pct, missing `targetId` for `WORK_CATEGORY` / `WORK_ITEM`): HTTP 400 `error.validation` from preview and Save; Save is atomic, so nothing is persisted.
- Non-DRAFT project or missing `ESTIMATE` UPDATE: rejected at Save by the existing gates.
- Unknown target id: no-op, no error.
- Preview failure: handled by the existing `useStagedPreview` error path; the staged entry stays in the store and the user can undo it.
