# Bugfix Requirements Document

## Introduction

The estimate screen has a "%" labour price adjustment control (`LabourPriceAdjustControl`) at three scopes: project, work category and single work item. It was introduced by FOR-05-05 (bill of materials) Round 8 B and reported again in FOR-05-07 Round 5 item 1. The owning spec is `FOR-05-05-bill-of-materials`; the collaborative editing it interacts with is `FOR-05-13-collaborative-estimate`, and the staged-edit machinery comes from `FOR-05-04-estimate-packages-changes`.

The control calls `POST /api/estimates/project/{projectId}/labour-price-adjustment` (`EstimateMatrixController.adjustLabourPrice` → `EstimateAssignmentService.applyLabourPriceAdjustment`). That call is an immediate server write. It is not a staged edit, so it bypasses the staged-change flow used by every other estimate edit (staged list "Zmiany (N)", undo/redo, discard, Save, shared collaborative draft). Reported on `https://dev.foremen.eu` for project 5, with three consequences:

1. With simultaneous editing (several users / edit mode), applying the adjustment resets other cells.
2. The adjustment affects only cells already saved in the database. Cells that are only staged (not yet saved) get no adjustment.
3. The adjustment does not appear in the staged-change list, so it cannot be undone, redone, discarded or saved together with the other edits.

Item 1 is a different symptom from the `@DynamicUpdate` race already fixed in FOR-05-07 Round 5. That fix covers only the database row race (a concurrent Save overwriting `unit_price_override`). It does not cover the client side, where the endpoint response (a matrix without any staged edits) replaces the cached matrix and is not re-projected over the staged set held in the store, including edits received from other collaborators. The fix is to make the adjustment a staged edit that is replayed with the rest of the staged set.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a user applies a labour price adjustment while other cells have staged (unsaved) edits, own or received from other collaborators in the shared draft, THEN the system replaces the cached matrix with the endpoint response, which contains no staged edits, so the other cells visibly reset until a later preview re-projects them (the preview is rescheduled only when the local store has staged edits).

1.2 WHEN a user applies a labour price adjustment in edit mode and some in-scope cells are staged but not yet saved (for example newly assigned, or assigned and re-quantified) THEN the system adjusts only persisted `estimate_lines`, so the staged cells keep their original labour price and nothing is applied to them.

1.3 WHEN a user applies a labour price adjustment THEN the system writes it to the database immediately and does not add it to the staged-change list, so "Zmiany (N)" does not count it, and undo, redo and discard do not affect it. It also does not go through Save or the shared collaborative draft, so other editors are not informed through the draft.

### Expected Behavior (Correct)

2.1 WHEN a user applies a labour price adjustment while other cells have staged edits (own or from collaborators) THEN the system SHALL keep every other cell's staged state and displayed values unchanged, and SHALL change only the labour price of the in-scope cells.

2.2 WHEN a user applies a labour price adjustment in edit mode THEN the system SHALL apply it to all in-scope cells, both persisted cells and cells that exist only as staged edits, in the order the staged edits were made. The effective labour price of a staged cell SHALL be the original price multiplied by (1 + pct/100), rounded to two decimals HALF_UP, exactly as for persisted lines.

2.3 WHEN a user applies a labour price adjustment THEN the system SHALL record it as a staged edit (scope, target id, pct) in the staged-change list, counted in "Zmiany (N)", with undo, redo, discard and removal of a single entry working like any other staged edit, and with nothing persisted until Save.

2.4 WHEN a staged set that contains a labour price adjustment is previewed or saved THEN the system SHALL replay the adjustment in staged order together with the other edits, in the debounced preview (the displayed matrix) and in the batched Save (persisting `unit_price_override` for the in-scope lines), leaving the original `unit_price` and the persisted `value_net` untouched (the FOR-05-05 contract).

2.5 WHEN several collaborators edit the same estimate and one of them applies a labour price adjustment THEN the system SHALL share it through the collaborative draft like any other staged edit, so all editors see it in their staged-change list and in the matrix, and edits to other cells by other editors SHALL be preserved.

2.6 WHEN an adjustment is staged and then undone or discarded before Save THEN the system SHALL leave the persisted `unit_price_override` of every line exactly as it was before the staging.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a labour price adjustment with scope `PROJECT`, `WORK_CATEGORY` or `WORK_ITEM` is saved for persisted lines THEN the system SHALL CONTINUE TO set `unit_price_override = round2(unit_price × (1 + pct/100))`, with `pct = 0` resetting to the original, and never touch material lines.

3.2 WHEN a labour price adjustment is saved THEN the system SHALL CONTINUE TO keep `estimate_lines.unit_price` and `value_net` on the original price, and the offer snapshot and matrix labour SHALL CONTINUE TO read `unit_price_override` (FOR-05-07).

3.3 WHEN an adjustment has an invalid scope, a missing `targetId` for `WORK_CATEGORY` / `WORK_ITEM`, or a missing `pct`, or the project is not DRAFT, or the caller lacks `ESTIMATE` UPDATE THEN the system SHALL CONTINUE TO reject it with the existing validation, permission and DRAFT-gate errors.

3.4 WHEN a Save recompute runs concurrently with a committed override write THEN the system SHALL CONTINUE TO preserve the committed `unit_price_override` (the `@DynamicUpdate` guarantee of FOR-05-07 Round 5).

3.5 WHEN a user stages or saves any other estimate edit (assign, unassign, materials, quantities, apply package, apply cheapest, merge/remove package) with no adjustment in the staged set THEN the system SHALL CONTINUE TO preview, share, undo and save it exactly as today.

3.6 WHEN the matrix is read-only (non-DRAFT project, or no ESTIMATE UPDATE permission) THEN the system SHALL CONTINUE TO disable the "%" controls.

3.7 WHEN an existing API client calls `POST /api/estimates/project/{projectId}/labour-price-adjustment` directly THEN the system SHALL CONTINUE TO accept the same request body (`scope`, `targetId`, `pct`) and persist the override, unless the design explicitly deprecates it.

## Bug Condition

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type LabourAdjustAction { scope, targetId, pct, editMode, stagedEdits, sharedDraft }
  OUTPUT: boolean

  RETURN X.editMode = true
     OR X.stagedEdits NOT EMPTY
     OR X.sharedDraft contains edits of other users
END FUNCTION

// Property: Fix Checking
FOR ALL X WHERE isBugCondition(X) DO
  result ← adjust'(X)
  ASSERT result.stagedList CONTAINS adjustment(X.scope, X.targetId, X.pct)
     AND undo(result) restores the matrix to the state before X
     AND every cell outside X.scope is unchanged
     AND every in-scope cell, persisted or staged-only, has labour = round2(unitPrice × (1 + X.pct/100)) × volume
     AND nothing is persisted before Save
END FOR

// Property: Preservation Checking
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT persistedOverrides(F(X)) = persistedOverrides(F'(X) followed by Save)
END FOR
```

Counterexample: a project has a staged, unsaved `ASSIGN` for work W in room R and a collaborator's staged edit on another cell. The user applies +10% at `WORK_ITEM` W. Today the staged cell keeps the original price, the collaborator's cell flickers back to its pre-edit values, and "Zmiany" does not list the adjustment.
