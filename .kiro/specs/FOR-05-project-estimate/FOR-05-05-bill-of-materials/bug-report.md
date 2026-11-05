# Bug report: FOR-05-05-bill-of-materials

Screen URL: http://localhost:3000/projects/1/estimate (the estimate works×rooms matrix). Clicking an
assigned cell opens the **Cell_Report** popup — the room-materials assignment dialog.

Owning spec: `.kiro/specs/FOR-05-project-estimate/FOR-05-05-bill-of-materials`. The popup is
`foremen-frontend/src/features/estimate/components/CellReport.tsx` (the `CellReport` dialog; its own
doc header references FOR-05-05). It renders a shadcn/Radix `Dialog`
(`foremen-frontend/src/components/ui/dialog.tsx`) with: a calculation header (`formula`, editable
`Volume`, work-item cost), a per-material-type table (type, norm, quantity, money, state badge +
concrete picker, remove), a cell total footer, and an "Add material" row. i18n lives under
`estimate.cellReport.*` in `src/locales/{pl,ru}.json`.

All six items are **frontend-only UI/UX polish** on this one dialog. No backend, no data-model, and
no new user-facing copy (we remove/repurpose existing labels and reuse their text as hover hints);
the only i18n touch is keeping the `estimate.cellReport.perRoomBasis` / `perRoomBasisHint` strings as
the hover-hint text (both PL/RU already exist).

## Round 1 (2026-10-28)

- [x] 1. Make the cell-report popup wider (~80% of viewport) to avoid horizontal scroll; ellipsis a too-long header
  - Triage: observed the popup is narrow (`DialogContent` `max-w-2xl`), so the material table
    horizontally scrolls. Expected: the dialog is ~80% of the viewport width so the table fits with
    no horizontal scroll, and a long title gets an ellipsis instead of overflowing/wrapping badly.
    Layer: frontend. Fix direction: widen `CellReport`'s `DialogContent` to ~80vw (e.g.
    `w-[80vw] max-w-[80vw]`, keeping `max-h-[90vh]`); add `truncate` (+ `title`) to the `DialogTitle`
    so an over-long work name ellipsizes on one line. Suspect: `CellReport.tsx` (DialogContent +
    DialogTitle).
  - _Requirements: 6.1_
  - _Depends on: none_

- [x] 2. Freeze the workItem-Volume row at the top and the "Add material" row at the bottom
  - Triage: observed the calculation header (formula / Volume / work cost) and the "Add material"
    affordance scroll away with the material list. Expected: the Volume/calculation header stays
    pinned at the top and the Add-material row stays pinned at the bottom while only the material
    table scrolls between them. Layer: frontend. Fix direction: in `CellReport` restructure the
    dialog body into three regions — a non-scrolling top block (the calc-header `<dl>` incl. the
    editable Volume), a `min-h-0 flex-1 overflow-y-auto` middle holding the materials table, and a
    non-scrolling bottom block holding the Add-material row (sticky above the footer). Suspect:
    `CellReport.tsx` (the `overflow-y-auto` body wrapper + placement of the header and
    `AddMaterialLine`).
  - _Requirements: 6.1, 6.2_
  - _Depends on: 1_

- [x] 3. Vertically center the text in each material row
  - Triage: observed row cells use `align-top`, so multi-line cells (type + branch + badges, or the
    concrete picker) sit top-aligned and look ragged. Expected: each row's content is vertically
    centered. Layer: frontend. Fix direction: change the `MaterialLineRow` `<td>`s (and the norm /
    quantity / money / state / actions cells) from `align-top` to `align-middle`. Suspect:
    `CellReport.tsx` (`MaterialLineRow` cell classes).
  - _Requirements: 6.1_
  - _Depends on: none_

- [x] 4. Render the Norm column value and unit on one line
  - Triage: observed the norm cell renders `{norm} {normUnit}` but can wrap to two lines in the
    narrow column. Expected: value + unit on a single line. Layer: frontend. Fix direction: add
    `whitespace-nowrap` to the norm `<td>` (it already composes `${norm} ${unit}`). Suspect:
    `CellReport.tsx` (norm cell).
  - _Requirements: 6.1_
  - _Depends on: none_

- [x] 5. Replace the visible `Na pomieszczenie` (PER_ROOM) label with a pale color accent + hover hint
  - Triage: observed a PER_ROOM line shows a sky-tinted pill reading "Na pomieszczenie". Expected:
    drop the visible text; instead apply a very pale color accent to the current label/row cell and
    surface the same label text ("Na pomieszczenie") only on hover (tooltip). Layer: frontend. Fix
    direction: for a `consumptionBasis === 'PER_ROOM'` line, remove the text pill and instead tint
    the type cell (or add a small pale accent marker) with a `title`/tooltip carrying
    `estimate.cellReport.perRoomBasis` (reuse the existing string; keep `perRoomBasisHint` as the
    longer explanation). Suspect: `CellReport.tsx` (`MaterialLineRow` PER_ROOM badge).
  - _Requirements: 6.1_
  - _Depends on: 3_

- [x] 6. Remove the `Symbol zastępczy` / `Konkretny` state labels (placeholder vs concrete is self-evident)
  - Triage: observed each material line shows a state pill reading "Symbol zastępczy" (placeholder)
    or "Konkretny" (concrete). Expected: remove these labels — whether a concrete product is chosen
    is obvious from the concrete-product picker (empty = placeholder, a product = concrete). Layer:
    frontend. Fix direction: remove the state badge (`estimate-cell-report-line-state-*`) from
    `MaterialLineRow`, keeping the concrete picker + unchoose control; leave the `colConcrete`
    column. Confirm whether the whole state column/badge should go or only the textual pill — the
    picker already conveys the state, so the badge is redundant. Suspect: `CellReport.tsx`
    (`MaterialLineRow` state badge) and its `CellReport.test.tsx` assertions on
    `estimate-cell-report-line-state-*`.
  - _Requirements: 6.6_
  - _Depends on: 3_

- [x] V1. Validate Round 1 in the browser
  - Open `/projects/1/estimate`, click an assigned cell, and confirm: (1) the dialog is ~80vw with no
    horizontal scroll, long title ellipsizes; (2) the Volume header stays pinned on top and the Add
    row pinned at the bottom while the material list scrolls; (3) row text is vertically centered;
    (4) the norm shows value+unit on one line; (5) PER_ROOM lines show a pale accent + hover hint, no
    visible "Na pomieszczenie" text; (6) no "Symbol zastępczy"/"Konkretny" pills. Mark `[x]` only on a
    confirmed browser pass.
  - _Requirements: 6.1, 6.2, 6.6_
  - _Depends on: 1, 2, 3, 4, 5, 6_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 3, 4 — independent CSS/structure tweaks (dialog width+title; row vertical
  alignment; norm nowrap). They touch `CellReport.tsx` but different, non-overlapping spots.
- **Wave 2 (parallel):** 2, 5, 6 — 2 restructures the body scroll regions (header/body/add); 5 and 6
  change the `MaterialLineRow` badges (depend on the row alignment from 3). All in `CellReport.tsx`;
  serialize if edits collide.
- **Wave 3:** V1 — browser validation of all six; depends on 1–6.

## Round 1 — validation results (2026-10-28)

All six are frontend-only changes in `CellReport.tsx` (+ test updates in `CellReport.test.tsx`).
Decisions applied: Bug 6 removes only the textual state pill (the "Konkretny produkt" picker column
stays); Bug 5 keeps a very pale sky accent dot with the label on hover.

Stack rebuilt (frontend); validated at `/projects/1/estimate` as ADMIN by assigning a cell and
opening its report.

| # | Item | Verified | Evidence |
|---|------|----------|----------|
| 1 | Wider popup + ellipsis title | ✅ | Dialog width = 2048px = **80%** of the 2560px viewport; no horizontal table scroll; `DialogTitle` has `truncate` + full `title`. |
| 2 | Freeze Volume header + Add row | ✅ | Calc header (formula / Volume / work cost) sticky at top with solid bg; the Rodzaj/Typ/"Dodaj materiał" row sticky at the bottom; the material list scrolls between them. |
| 3 | Vertically centered rows | ✅ | Row cells use `align-middle`; type+branch, norm, qty input, cost and picker are centered. |
| 4 | Norm value+unit one line | ✅ | `0,10 m2`, `0,20 szt`, `0,13 l`, `2,00 kg` render on a single line (`whitespace-nowrap`). |
| 5 | PER_ROOM pale accent + hover | ✅ | PER_ROOM lines (Taśma malarska/tynkarska, Narożnik, Tarcza) show a pale sky dot, no "Na pomieszczenie" text; label+hint in the tooltip/aria-label. |
| 6 | Remove state pills | ✅ | No "Symbol zastępczy"/"Konkretny" badges (0 `line-state` nodes); the Konkretny-produkt picker column stays. |

Result: 6 fixed / 0 failed. Validation: live Docker stack + browser. tsc clean; 36 CellReport tests
and 235 estimate-feature tests pass.

## Round 2 (2026-10-28)

- [x] 7. Group "apply cheapest" (hammer) transiently zeroes other filled cells
  - Triage: observed clicking a work-category group's apply-cheapest button (the `BadgeDollarSign`
    "hammer" in the group header, aria "Zastosuj najtańsze produkty w grupie …") makes already-filled
    cells in OTHER rows briefly render `0` / `0,00`; clicking again restores them — looks random.
    Expected: applying cheapest to a group only affects that group's assigned cells and never zeroes
    unrelated filled cells. Layer: **frontend**. Root cause (traced): the group button passes EVERY
    row of the group — `onApplyCheapestGroup?.(group.rows.map((r) => r.workItemId))` in
    `WorkTypeGroupSection.tsx` — including rows with NO assigned cells.
    `EstimateTab.handleApplyCheapestGroup` stages one `APPLY_CHEAPEST { workItemId }` per id; each is a
    no-op in the pure client overlay (`projectStagedEdits`) but schedules the debounced staged-set
    `/preview`. The preview POSTs the whole staged set (apply-cheapest for works that have nothing
    assigned) and bakes the server response into the matrix cache via `setQueryData`. The matrix
    renders money live from `cellCostRange(cell) = labour + Σ materialLineRange(line, volume)`
    (`MatrixCell` + `costModel`), so the preview's assigned-but-zero projection for those over-broad
    rows (volume/labour 0, empty materials) shows as `0,00` on other cells until a later
    preview/refetch replaces the cache — hence the "restores on next click" flicker.
    Fix direction: scope the group apply-cheapest to rows that actually have assigned cells — filter
    in `handleApplyCheapestGroup` (it has `renderMatrix`, mirroring `handleClearWorkRow`'s
    assigned-cell iteration) so only rows with ≥1 assigned cell stage `APPLY_CHEAPEST`; a group with
    no assigned rows stages nothing. (Optionally also guard `handleApplyCheapestRow` the same way.)
    Suspect: `foremen-frontend/src/features/estimate/EstimateTab.tsx` (`handleApplyCheapestGroup`);
    `components/WorkTypeGroupSection.tsx` (the ids it passes).
  - _Requirements: 6.1, 6.4_
  - _Depends on: none_

- [x] V2. Validate Round 2 in the browser
  - On `/projects/1/estimate`, assign cells in some rows of a group, then click the group
    apply-cheapest button: confirm other rows' filled cells keep their values (no transient `0`),
    while the group's assigned cells get cheapest products applied. Mark `[x]` only on a confirmed
    browser pass.
  - _Requirements: 6.1, 6.4_
  - _Depends on: 7_

## Parallel execution plan (waves) — Round 2

- **Wave 1:** 7 — single-handler scope fix in `EstimateTab`.
- **Wave 2:** V2 — browser validation; depends on 7.

## Round 2 — validation results (2026-10-28)

Fix: `EstimateTab.handleApplyCheapestGroup` now stages `APPLY_CHEAPEST` only for the group's rows
that have ≥1 assigned cell (new `rowHasAssignedCell(matrix, workItemId)` helper); `handleApplyCheapestRow`
got the same guard. The group button still passes all row ids, but empty rows are skipped so no bare
apply-cheapest for an unassigned row reaches the debounced `/preview`.

Stack rebuilt (frontend); validated at `/projects/1/estimate` as ADMIN.

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 7 | Group apply-cheapest zeroes other cells | ✅ Fixed | Expanded group 1, assigned cell 1-1 (191,85 – 193,69) and 2-1 (2 116,94 – 2 117,94), left rows 3–12 unassigned, clicked the group hammer: both assigned cells correctly collapsed to their cheapest single price (191,85 / 2 116,94) with NO transient `0` on either; the 10 unassigned rows no longer stage a meaningless apply-cheapest. |

Result: 1 fixed / 0 failed. Validation: live Docker stack + browser. tsc clean; 235 estimate-feature
tests pass (the group-scope test now asserts only assigned rows stage APPLY_CHEAPEST).

## Round 3 (2026-10-28)

- [x] 8. Newly-assigned cell flashes "0,00" for ~1s before showing its cost
  - Triage: observed clicking an unassigned matrix cell to assign it: the cell shows `0,00` for
    about a second, then displays its real cost range. (Reported as "others show 0"; reproduced and
    narrowed to the **newly-clicked** cell — existing assigned cells keep their values thanks to the
    overlay's already-assigned guard. Confirmed via sampling: after an assign click the clicked cell
    read `0,00` for ~360ms then flipped to e.g. `1 426,64 – 1 428…`; other filled cells stayed put.)
    Layer: **frontend**. Root cause: the client overlay's `assignCell` (`state/projectStagedEdits.ts`)
    marks a freshly-assigned cell with an all-zero placeholder (`volume:0, labour:0, materials:[],
    costRange:{0,0}`) because the real numbers (formula Volume, labour, material ranges) are
    server-computed; they only arrive when the 300ms-debounced staged-set `/preview` resolves
    (`useStagedPreview` → `setQueryData`). During that window `MatrixCell` renders
    `formatMoneyRange(cellCostRange(cell))` = `0,00` for the placeholder, so the cell flashes 0 then
    the computed value — the ~1s is debounce + round-trip. Expected: while a just-assigned cell's
    server numbers are pending, show a neutral "pending" cue (e.g. `…`), not a misleading `0,00`.
    Fix direction: in `MatrixCell`, detect the pending placeholder — an assigned cell that is still
    the overlay zero placeholder (`volume === 0 && labour === 0 && materials.length === 0`, which a
    real formula-computed assigned cell never is) — and render a pending indicator (`…` / subtle
    dash) instead of `0,00` until the preview reconciles. Suspect:
    `foremen-frontend/src/features/estimate/components/MatrixCell.tsx` (assigned-branch render);
    optionally expose a small `pending` helper in `costModel`.
  - _Requirements: 6.1, 7.1_
  - _Depends on: none_

- [x] V3. Validate Round 3 in the browser
  - On `/projects/1/estimate`, click an unassigned cell and confirm it shows a pending cue (not
    `0,00`) until its real cost appears; other assigned cells never flash 0; a genuinely computed
    cell still shows its range. Mark `[x]` only on a confirmed browser pass.
  - _Requirements: 6.1, 7.1_
  - _Depends on: 8_

## Parallel execution plan (waves) — Round 3

- **Wave 1:** 8 — single render-guard in `MatrixCell` (+ optional `costModel` helper).
- **Wave 2:** V3 — browser validation; depends on 8.

## Round 3 — validation results (2026-10-28)

Fix: added `isPendingCell(cell)` to `state/costModel.ts` (assigned cell still the overlay zero
placeholder: `volume===0 && labour===0 && materials.length===0`). `MatrixCell` renders a neutral
`…` pending cue (new `estimate.cell.pendingAria` i18n, PL+RU) instead of `formatMoneyRange` while a
just-assigned cell's server numbers are pending, then the real cost once the debounced `/preview`
reconciles.

Stack rebuilt (frontend); validated at `/projects/1/estimate` as ADMIN.

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 8 | New cell flashes 0,00 | ✅ Fixed | Assigning a fresh cell transitions `""` → `…` → `405,09 – 405,80`; sampled at 40ms intervals: `sawEllipsis: true`, `sawZero: false` — the cell never renders `0,00`, it shows the pending cue then the computed cost. |

Result: 1 fixed / 0 failed. Validation: live Docker stack + browser. tsc clean; 235 estimate-feature
tests pass.

## Round 4 (2026-10-28)

- [x] 9. Staged estimate edits don't reconcile after a page reload — filled cells show 0 volume / empty materials
  - Triage: observed — fill several rows (prices/forks render), then **reload the page**: the
    previously-filled cells render 0 (opening the Cell_Report shows Volume 0 and an empty materials
    list). Re-clicking a row's hammer refills only that row; clicking another row makes the previous
    one revert to 0, and so on. Expected: staged (unsaved) edits persist across reload with their
    real Volume / materials / price forks intact (no 0 volumes, no empty consumptions). Layer:
    **frontend**. Root cause: the staged-edit store IS persisted to per-project localStorage and
    re-hydrates on mount (`createEstimateMatrixStore(projectId)` in `EstimateTab`). On reload the base
    matrix is GET-fetched CLEAN and the overlay (`projectStagedEdits`) re-applies the hydrated edits —
    but `APPLY_CHEAPEST` / `ADD_MATERIAL` / `CHOOSE_CONCRETE` / `APPLY_PACKAGE` are **no-ops in the
    pure client overlay** (their numbers are server-computed) and `assignCell` only lays down a ZERO
    placeholder (volume 0, no materials). The real numbers normally come from the debounced
    `/preview`, but `stagedPreview.schedule()` is ONLY called from `stage()` (a NEW edit) and
    `reconcileMatrix()` (undo/redo/discard) — **nothing re-fires the preview on mount for an
    already-hydrated staged set**. So after reload the cells stay at the zero placeholder until the
    user makes another edit; re-clicking one row's hammer stages a new APPLY_CHEAPEST that previews
    only against the current set and re-bakes, which is why "only the clicked row fills and others
    reset" (same over-broad-preview interaction as Round 2, now triggered by the missing mount
    reconcile). Fix direction: on `EstimateTab` mount, once the base `matrix` has loaded AND the
    hydrated store has ≥1 staged edit, fire `stagedPreview.schedule()` ONCE to reconcile the server
    numbers for the persisted staged set (mirrors what `stage()` does, but for the reload path). Guard
    it to run a single time per load (not per edit). Suspect:
    `foremen-frontend/src/features/estimate/EstimateTab.tsx` (add a mount/`matrix`-ready effect that
    schedules the preview when persisted edits exist); `api/useStagedPreview.ts` (reused as-is).
  - Fix: added a mount-time reconcile effect in `EstimateTab.tsx` (right after the `projectedMatrix`
    overlay). Keyed on `[matrix, useStore]`, guarded by a `reloadReconciledForStoreRef` that holds the
    per-project store instance so it fires AT MOST ONCE per store (re-arms only when the store is
    recreated on a `projectId` change, never on subsequent edits). When the base `matrix` has loaded
    and the hydrated `useStore.getState().edits` has ≥1 entry, it calls `stagedPreview.schedule()` once
    to reconcile the real server numbers for the whole persisted staged set — the reload-path
    equivalent of what `stage()` does for a new edit. `useStagedPreview.ts` reused unchanged.
  - Verified: `npx tsc -b` clean; `npx vitest run src/features/estimate` → 14 files / 235 tests pass.
  - _Requirements: 6.1, 6.5_
  - _Depends on: none_

- [x] V4. Validate Round 4 in the browser
  - On `/projects/1/estimate`, assign/fill ~3 rows (price forks render), reload the page, and confirm
    the filled cells keep their real cost forks (no 0,00), the Cell_Report shows the correct Volume +
    materials, and clicking one row's hammer no longer resets other rows. Mark `[x]` only on a
    confirmed browser pass.
  - Result (2026-10-28, PASS): assigned works 1/2/3 in room 1 → cells showed real forks
    `191,85–193,69` / `2 116,94–2 117,94` / `1 426,64–1 428,25`; localStorage held 3 staged edits.
    After a full page reload the three cells kept the IDENTICAL forks (no 0,00) and the console logged
    the one-shot reconcile exactly once: `[estimate] hydrated staged set → one-shot reload preview
    reconcile {editCount: 3}` followed by `[estimate] staged edits → debounced preview {editCount: 3}`.
    Opening the Cell_Report for cell 2-1 after reload showed a full consumption (labour 2 050,00; four
    material lines with norms/quantities/costs; row total 2 116,94–2 117,94) — no Volume 0, no empty
    list. Cleaned up afterwards: Discard cleared the staged store (`foremen.estimateMatrix.1` → null)
    and all three cells returned to unassigned.
  - _Requirements: 6.1, 6.5_
  - _Depends on: 9_

## Parallel execution plan (waves) — Round 4

- **Wave 1:** 9 — a mount-time preview-reconcile effect in `EstimateTab`.
- **Wave 2:** V4 — browser validation (reload path); depends on 9.

## Round 5 (2026-11-02)

Source: meeting notes, reported against `https://dev.foremen.eu/projects/2/estimate`. Reproduce
locally at `http://localhost:3000/projects/{id}/estimate`.

- [x] 10. A removed cell, when clicked again, comes back without works and materials (must behave like clicking a new cell)
  - Triage: observed — a cell is removed (Cell_Report "remove" → `UNASSIGN`, or clear-row /
    clear-estimate), then the same cell is clicked again: it shows as assigned but with Volume 0,
    labour 0 and an empty materials list. Expected — re-clicking a removed cell behaves exactly like
    clicking a never-assigned cell: formula Volume, labour and the default material lines seeded from
    the work's `WorkMaterialConsumption` with their `Type_Price_Range` (R3.1, R3.4, R3.5).
    Layer: **frontend**. The backend is correct: `doUnassign` hard-deletes the room-qty and its
    material lines, and a later `ASSIGN` goes through `doAssign` → `seedMaterialLines`, so the
    debounced `/preview` for `[…, UNASSIGN(w,r), ASSIGN(w,r)]` already returns the freshly seeded cell.
    Root cause: the client overlay `projectStagedEdits` (`state/projectStagedEdits.ts` ~92-149)
    re-folds ALL staged edits over the cached base, which by then is the baked-in preview. The
    `UNASSIGN` blanks the preview's correct cell (`unassignCell` ~257-271), then the `ASSIGN` reaches
    `assignCell` (~238-254). Its #13 guard (`if (cell.assigned) return cell`) checks the **folded**
    cell (now unassigned), not the base cell, so it lays the zero placeholder over the server's seeded
    numbers. A brand-new cell has no preceding `UNASSIGN`, so the guard keeps the preview cell. That is
    why only removed-then-re-clicked cells are affected. Contributing: the store's `STAGE` reducer
    (`state/estimateMatrixStore.ts` ~212-220) never collapses an `UNASSIGN`/`ASSIGN` pair on the same cell.
    Fix direction: when an `ASSIGN` follows an `UNASSIGN` of the same cell in the fold, take the
    cell from the ORIGINAL base matrix once the base reflects this staged set (the preview has baked
    it). Before that, keep the pending placeholder (Round 3 `isPendingCell` → `…`). Never restore the
    pre-removal saved cell: it would bring back the old customized materials instead of the defaults.
    One option is for `useStagedPreview` to record the `stagedId`s its last baked preview covered;
    the overlay then lets covered `UNASSIGN`→`ASSIGN` pairs yield to the base. Do NOT just UNSTAGE
    the earlier `UNASSIGN`: that also brings back saved materials and prior `REMOVE_MATERIAL` effects.
    During the fix, also check the post-Save path (remove → Save → click). It should already work like
    a new cell. If it doesn't, check whether collab or localStorage hydrate re-inserts a stale `UNASSIGN`.
    Suspect: `foremen-frontend/src/features/estimate/state/projectStagedEdits.ts`,
    `foremen-frontend/src/features/estimate/api/useStagedPreview.ts`,
    `foremen-frontend/src/features/estimate/EstimateTab.tsx` (`projectedMatrix` memo ~262-265).
    Add a unit test in `projectStagedEdits` for `[UNASSIGN, ASSIGN]` over an assigned base.
  - _Requirements: 3.1, 3.4, 3.5, 15.1, 15.3_
  - _Depends on: none_

- [x] 11. Add a column "total works in units of measurement"
  - Triage: observed — the matrix has the work column, one column per room and the trailing package
    money column. There is no per-work total quantity. Expected — a new trailing column showing, for
    each work row, the work's total Volume across all rooms (Σ volume of the row's assigned cells) with
    the work's unit of measurement (e.g. `42,50 m²`). It shows `—` when nothing is assigned and
    updates live with staged edits. The group header row gets an empty, aligned cell, because volumes
    of different works/units must not be summed. Layer: **both**.
    - Backend: `WorkRowDto` (`service/estimate/matrix/WorkRowDto.java`, built at
      `EstimateMatrixAssembler.java` ~164-166) has no unit. Add `unitCode` + a localized `unitName`
      (from `MeasurementUnitEntity.nameRU/namePL`, chosen by the request locale like `workItemName`).
      No new entity fields and no migration.
    - Frontend: mirror the fields in `types/index.ts` `WorkRowDto`. Compute the total client-side with
      a `totalVolumeOfRow(row)` helper in `state/costModel.ts` (next to `packageVolumeOfRow`), so it
      follows the staged overlay with no `projectStagedEdits` recompute. Render a header `<th>` in
      `components/WorksRoomsMatrix.tsx` (next to `estimate-package-col`, ~297-305). Add a per-row
      `<td>` (`estimate-work-total-volume-{workItemId}`) and a matching empty group-header `<th>` in
      `components/WorkTypeGroupSection.tsx` (~223-238, ~359-381). Update the
      `WorksRoomsMatrix`/`WorkTypeGroupSection` tests' column-count assumptions.
    - Column placement (before or after the package column) is a UI choice. Default: right after the
      room columns, before the package money column.
    - i18n (PL + RU parity, merged into the existing `estimate.matrix` object, no duplicate top-level
      key): `estimate.matrix.totalVolumeColumn` (PL "Razem pracy (j.m.)", RU "Итого работ (ед. изм.)")
      and `estimate.matrix.totalVolumeColumnHint` (PL "Łączna ilość pracy we wszystkich
      pomieszczeniach", RU "Суммарный объём работы по всем помещениям").
    - Notifications: none (read-only display column).
  - **User decision (2026-11-02):** put the column **before the first room** (right after the
    sticky work-name column), and enrich it with **volume, unit and price per unit**. Each row cell
    shows the total volume + unit (e.g. `42,50 m²`) and, under it, the labour price per unit (e.g.
    `50,00 / m²`). Backend `WorkRowDto` gains `unitCode`, `unitName` (localized from
    `MeasurementUnitEntity.nameRU/namePL`) and `unitPrice`. `unitPrice` is the line's frozen
    `EstimateLine.unitPrice` when the work is assigned, otherwise the catalog `WorkPrice.netPrice`
    (batch-loaded, `null` when unpriced). Extra i18n:
    `estimate.matrix.unitPrice` (PL "{{price}} / {{unit}}", RU "{{price}} / {{unit}}") and
    `estimate.matrix.unitPriceHint` (PL "Cena robocizny za jednostkę", RU "Цена работы за единицу").
  - _Requirements: 1, 4.1, 18_
  - _Depends on: none_

- [x] V5. Validate Round 5 in the browser
  - Rebuild backend + frontend. On `/projects/{id}/estimate`, check bug 10 for both a saved and an
    unsaved cell: remove it, click it again, and confirm it shows the same Volume / labour / default
    materials as a fresh cell (Cell_Report lists the seeded material lines), with no `0,00` and no
    empty list. Check bug 11: the new column shows Σ room volumes + unit per row, `—` for unassigned
    rows, updates on assign/unassign/SET_QUANTITY, stays aligned in group header rows, and is
    localized in PL and RU. Mark `[x]` only on a confirmed browser pass.
  - _Requirements: as 10, 11_
  - _Depends on: 10, 11_

## Parallel execution plan (waves) — Round 5

- **Wave 1 (parallel):** 10, 11 — independent. 10 is the overlay/preview logic
  (`projectStagedEdits`, `useStagedPreview`). 11 is backend `WorkRowDto` + assembler, plus the
  matrix components, `costModel` helper and i18n. If 10 needs a new `costModel` helper, serialize the
  `costModel.ts` edit after 11's.
- **Wave 2:** V5 — browser validation; depends on 10, 11.

## Round 5 — validation results (2026-11-02)

Fixes:
- **#10:** `useStagedPreview` marks the matrix it bakes into the cache with the `previewedStagedIds`
  it covered (client-only `EstimateMatrixDto.previewedStagedIds`). `EstimateTab` overlays only the
  edits not yet covered (new pure `editsNotCoveredByPreview`). The server preview is authoritative
  for the edits it includes, so `UNASSIGN` → `ASSIGN` no longer blanks the freshly seeded cell.
  `projectStagedEdits` itself is unchanged. Tests: 3 new cases in `projectStagedEdits.test.ts`; the
  `useStagedPreview` test was updated for the marker.
- **#11:** backend `WorkRowDto` gains `unitCode`, `unitName` (localized) and `unitPrice` (frozen line
  price, else catalog `WorkPrice.netPrice`, batch-loaded via `WorkPriceDao.findByWorkItemIdIn`), with
  a 6-arg compatibility constructor. Frontend: `totalVolumeOfRow` / `hasAssignedCell` in `costModel`,
  a new column before the first room (`estimate-total-volume-col`, per-row
  `estimate-work-total-volume-{id}`), group header `colSpan` +1. i18n `estimate.matrix.totalVolumeColumn`,
  `totalVolumeColumnHint`, `unitPrice`, `unitPriceHint`, `totalVolumeAria` (PL/RU). Two new assembler
  unit tests.

Validation environment: same as FOR-07-02 Round 1 (temporary backend from a clean `HEAD` worktree +
this round's backend files; the shared working tree's backend did not compile because of the
in-progress FOR-07-06 work). Logged in as ADMIN.

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 10 | Removed cell comes back empty | ✅ Fixed | Project 2 (DRAFT), cell work 1 × room 2 (`484,65`, saved with chosen products). Cell_Report → clear cell, then the cell is empty and the row total drops to 47,00 m². Click again: `…` pending, then `411,11 – 414,04`, row total back to 74,00 m². The Cell_Report shows labour 268,65 and all 9 default material lines as placeholders ("Wybierz produkt"), the same as a new cell. Discard restored `484,65`, staged store empty. Only the unsaved path was exercised (the reported one). The post-Save path goes through the backend `doUnassign` hard delete and was not re-run. |
| 11 | "Total works" column with volume, unit, price | ✅ Fixed | Header order: Robota → `Razem pracy (j.m.)` → rooms → package. Rows: `75,00 m² / 9,95 / m²`, `62,50 m² / 164,00 / m²`, `25,00 mb / 86,90 / mb`; an unassigned row shows `— / 275,00 / kpl.`. Live update on unassign/assign (74 → 47 → 74 m²). Group header colSpan = rooms + 2. |

Result: 2 fixed / 0 failed. Tests: `EstimateMatrixAssemblerPackageDivergenceTest` (7),
`MaterialsListAssemblerPropertyTest`, `MarginsListAssemblerPropertyTest` green. Frontend `tsc -b`
clean; estimate suites pass. The one failure in the full multi-folder run,
`DataTable-action-gating.property.test.tsx`, passes alone and is unrelated (load flake).

## Round 6 (2026-11-02)

Source: meeting notes, reported against `https://dev.foremen.eu/projects/4/estimate`.

- [x] 12. When scrolling down, keep the header row fixed (`Robota | Razem pracy (j.m.) | rooms… | Z pakietu`)
  - Triage: observed — when the page scrolls down, the matrix header row scrolls away, so you lose
    track of which column is which room / total / package. Expected — the whole header row stays
    visible at the top while scrolling down. The sticky first column still works while scrolling
    sideways. Layer: **frontend** only.
    Root cause: every header `<th>` is already `sticky top-0`, but the wrapper in
    `components/WorksRoomsMatrix.tsx` (~231-234) is `overflow-x-auto overflow-y-visible`. Per CSS,
    when one overflow axis is not `visible` the other computes to `auto`, so the wrapper is a scroll
    container on both axes. With no height limit it never scrolls vertically, so `top: 0` sticks to
    the top of a box that never moves, and the header scrolls away with the page. The code comment
    there assumes `overflow-y-visible` releases the vertical axis; it doesn't.
    Fix direction: make the wrapper the vertical scroller too:
    `overflow-auto` + `max-h-[calc(100vh-<offset>)]`, where the offset leaves room for the app bar
    and tab header. The `sticky top-0` header row and the `sticky left-0` work column then both work
    inside it, and the top-left corner (`z-30`) stays above both. Update the misleading comment.
    Check that the busy overlay (`absolute inset-0`) still covers the matrix, and that the group
    header rows, which are not sticky, scroll under the header.
    Suspect: `foremen-frontend/src/features/estimate/components/WorksRoomsMatrix.tsx`.
    i18n: none. Notifications: none.
  - _Requirements: 1 (matrix rendering), FOR-05-05 Round 5 #11 column_
  - _Depends on: none_

- [x] V6. Validate Round 6 in the browser
  - On `/projects/{id}/estimate` with expanded groups: scroll down. The header row (Robota, Razem
    pracy, every room, Z pakietu) stays at the top of the matrix. Scroll sideways; the work column
    stays left and the corner cell stays on top. Mark `[x]` only on a confirmed browser pass.
  - _Depends on: 12_

## Parallel execution plan (waves) — Round 6

- **Wave 1:** 12 — single-file layout fix.
- **Wave 2:** V6 — browser validation; depends on 12.

## Round 6 — validation results (2026-11-03)

Fix: `WorksRoomsMatrix` now has a non-scrolling `relative overflow-hidden` frame, which holds the
busy overlay, around an inner `estimate-matrix-scroll` div
(`max-h-[calc(100vh-14rem)] min-h-[16rem] overflow-auto`). The table scrolls inside it on both axes,
so the existing `sticky top-0` header row and `sticky left-0` work column work. The misleading
`overflow-y-visible` comment is replaced.

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 12 | Header row fixed on scroll | ✅ Fixed | Project 2, all groups expanded (content 9935 px in a 966 px box). After `scrollTop = 1500`, the "Razem pracy (j.m.)" header and the corner cell stay at offset 0 from the top of the scroll box. A screenshot deep in the HYDRAULIKA group shows the header row Robota / Razem pracy / rooms / Z pakietu pinned. Horizontal: the table fit the viewport (2240 = 2240), so the sticky-left column was not exercised; that code is unchanged. |

Cosmetic leftover: with `border-collapse`, a few pixels of the scrolled rows show through above the
pinned header (the collapsed borders don't stick). If it bothers you, switch the table to
`border-separate border-spacing-0`.

## Round 7 (2026-11-04)

Screen URL: `http://localhost:3000/projects/{id}/estimate` (Works × Rooms matrix). Source: meeting
notes (RU). Reported bug (one line): "в смете чтобы было в ячейке виден объём работы и цена … с
возможностью сформировать цену работы, материала, работы с материалом (переключалка над таблицей —
как показывать цены: Р, М, Р+М)". Clarified with the user: the toggle is the **"Итого работ
(ед. изм.)" column header** itself — clicking it cycles the price mode; each matrix cell shows the
work **volume** plus the **price for the selected mode**.

Background (grounded in code): each `MatrixCell` (`features/estimate/components/MatrixCell.tsx`)
currently renders only one combined money range (`cellCostRange`), no volume and no work/material
split. `CellDto` (`features/estimate/types/index.ts`) already carries `volume`, `labour`, and
per-material lines, and the pure cost model (`features/estimate/state/costModel.ts`) already has the
building blocks — `cellCostRange` (combined = Р+М), a private `cellWorksRange` (labour = Р), and
`cellBranchMaterialRange` (per-branch material). The "total works" column header
(`estimate.matrix.totalVolumeColumn`, testid `estimate-total-volume-col`) lives in
`WorksRoomsMatrix.tsx`; the per-row total/volume cell lives in `WorkTypeGroupSection.tsx`. This is a
**frontend-only** change — no new backend fields are required (Р, М, Р+М are all derivable from
`CellDto`).

Scope note on material format: the user said material price needs "другой формат" later; at this
stage М mode reuses the existing material money-range format (no new formatting introduced now).

- [x] 1. Add a price-mode cycle on the "Итого работ (ед. изм.)" column header (Р → М → Р+М)
  - Triage: observed — the total-works header is a static label; cells show one combined price.
    Expected — clicking the header cycles a price mode held in UI state (default **Р+М**, matching
    today's combined behaviour), with Р = работа (labour), М = материал (material), Р+М = both. The
    chosen mode is **persisted in the browser session** (per the user's decision). The header shows
    the active mode (e.g. a `Р / М / Р+М` affordance) and is keyboard-accessible (button,
    `aria-label`). Layer: **frontend**.
    - Hold the mode in a small store mirroring `estimatePositioningStore`, persisting to
      `sessionStorage` (per project); thread the selected mode into `WorkTypeGroupSection` →
      `MatrixCell`.
    - New i18n keys (PL + RU) for the mode labels and the header aria/tooltip (e.g.
      `estimate.priceMode.work` = `Р`/`R`-work label, `.material`, `.both`, `.cycleAria`,
      `.hint`). No hard-coded UI text.
  - _Requirements: 1.2, 7.2, 14.1_
  - _Depends on: none_
- [x] 2. Add `cellWorkRange` / `cellMaterialRange` helpers and render volume + mode price per cell
  - Triage: observed — `MatrixCell` renders `volume`-less, combined-only price. Expected — each
    assigned cell shows the work **volume** (with unit) plus the money for the active mode: Р =
    labour (`cellWorkRange`), М = Σ material lines (`cellMaterialRange`, both branches), Р+М =
    `cellCostRange` (unchanged). Pending/zero/placeholder cues preserved. Layer: **frontend**.
    - Export `cellWorkRange` (expose the existing private `cellWorksRange` labour point) and add
      `cellMaterialRange` (Σ `materialLineRange` over all of the cell's lines) to `costModel.ts`;
      keep the module pure/total and update its property tests.
    - In `MatrixCell`, pick the range by mode and render `volume` + formatted range; verify group /
      header subtotals stay consistent (they already expose `works`/`construction`/`finishing`).
  - _Requirements: 1.2, 4.1, 7.2_
  - _Depends on: 1_
- [x] V7. Validate Round 7 in browser
  - Bring up the local stack, open the estimate matrix on a DRAFT project with assigned cells.
    Confirm: (a) clicking the "Итого работ" header cycles Р → М → Р+М; (b) each cell shows the work
    volume and the price for the active mode; (c) Р+М matches the previous combined value; (d)
    subtotals/totals remain correct.
  - _Requirements: 1.2, 4.1, 7.2, 14.1_
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 7

- **Wave 1:** 1 — the header toggle + mode state + i18n (entry point the cell render depends on).
- **Wave 2:** 2 — cost-model helpers + cell render (depends on the mode threaded from 1).
- **Wave 3:** V7 — browser validation; depends on 1, 2.

## Round 7 — validation results (2026-11-04)

Stack: local Docker (`backend` :8080, `frontend` :3000, admin `cto+1@loaders.dev`). Screen:
`http://localhost:3000/projects/2/estimate` (DRAFT project, 270 assigned cells).

Decisions applied: default mode **Р+М (BOTH)**; the chosen mode is persisted in the **browser
session** (`sessionStorage` key `foremen.estimatePriceMode.<projectId>`).

- **#1 Header price-mode toggle — ✅ FIXED.** The "Итого работ (ед. изм.)" header is a button
  showing the active mode as a badge; it defaults to **Р+М** and cycling it goes **Р+М → Р → М**.
  Session persistence confirmed: set to М, reloaded the page, the toggle restored to М (badge `М`,
  `sessionStorage` = `MATERIAL`).
- **#2 Cell volume + mode price — ✅ FIXED.** Sample cell (work 1 × room 2, volume `27,00 м²`):
  - Р+М: price `484,65`
  - Р (work): price `268,65`
  - М (material): price `216,00`
  - `268,65 + 216,00 = 484,65` — the work/material split sums to the combined value, and each cell
    shows the work volume (`27,00 м²`) above the mode's price. Р+М equals the prior combined value.
  Unit tests: `costModel.property.test.ts` and the 35 `WorksRoomsMatrix.test.tsx` cases green.

Result: 2 fixed / 0 failed. All Round 7 bugs closed.

## Round 8 (2026-11-05)

Screen URL: `http://localhost:3000/projects/{id}/estimate` (Works × Rooms matrix). Source: meeting
notes. Two items agreed with the user: (A) a per-row Total-column money sum that follows the active
price mode, and (B) a per-work-item price override expressed as a scoped percentage adjustment.

Background (grounded in code):
- The per-row "total works" column lives in `WorkTypeGroupSection.tsx` (the IIFE rendering
  `estimate-work-total-volume-<id>`). It shows the row's total **volume** + unit and the per-unit
  labour price, but no money SUM and it does not react to the price mode. The price mode
  (`WORK`/`MATERIAL`/`BOTH`, Р/М/Р+М) is already threaded into this component and down to
  `MatrixCell` (Round 7). The pure cost model (`state/costModel.ts`) has per-cell mode helpers
  (`cellWorkRange`, `cellMaterialRange`, `cellCostRange`) and row aggregators (`totalVolumeOfRow`,
  `packageMoneyOfRow`) but no price-mode-aware row money sum.
- The project already stores its own frozen per-work-item price: `EstimateLineEntity.unitPrice`
  (`estimate_lines.unit_price`) is seeded from the catalog `WorkPrice.netPrice` at line creation
  (`EstimateLineService.copySingleWorkPrice`) and is the source of truth for the line value; the
  matrix assembler reads it into `WorkRowDto.unitPrice` and `CellDto.labour = unitPrice × volume`.
  There is NO percentage-override / base-vs-effective concept anywhere today (confirmed by grep).

### A — Per-row Total column: show the row's P / M / P+M money sum (frontend only)

- [x] 1. Add a price-mode-aware row money sum and render it in the Total column
  - Triage: observed — the `estimate-work-total-volume-<id>` cell shows volume + unit price only;
    no money total, ignores the price mode. Expected — the Total column also shows the row's SUM
    money for the active mode: Р = Σ labour, М = Σ materials, Р+М = Σ combined, summed across the
    row's assigned room cells (collapsing range, same rule as cells). Layer: **frontend**.
    - Add `rowModeMoney(row: WorkRowDto, mode: PriceMode): MoneyRange` to `costModel.ts` (Σ over
      assigned cells of `cellWorkRange`/`cellMaterialRange`/`cellCostRange` by mode); keep the module
      pure/total and extend `costModel.property.test.ts` / `costModel.test.ts`.
    - In `WorkTypeGroupSection.tsx` total-column IIFE, render the money (new testid
      `estimate-work-total-money-<id>`) using `formatMoneyRange`, driven by the in-scope `priceMode`;
      keep the volume + unit-price lines; update the cell `aria-label`.
    - i18n: add a row-total-money label/aria key in `pl.json` + `ru.json` (mirror
      `estimate.matrix.unitPrice` / `totalVolumeAria`). No backend change (data already in the DTO).
  - _Requirements: 1.2, 2.2, 7.2, 14.1_
  - _Depends on: none_

### B — Scoped LABOUR-price override (keep original, add an overridden column)

Semantics agreed with the user (corrected): the **original** labour price stays untouched — today's
`EstimateLine.unitPrice` remains the frozen original and is NEVER rewritten. A **new column** holds
the possibly-adjusted labour price (the effective/overridden value, default = the original). A
%-adjust applied at a scope — PROJECT, WORK_CATEGORY, or WORK_ITEM — writes **each in-scope line's new
column** (original × `(1 + pct/100)`), leaving the original column intact; it is a one-shot write per
line (not a live multiplier), so each work item keeps its own concrete overridden value. The matrix
labour (`CellDto.labour`) uses the **effective** (overridden) price. When a line's overridden price ≠
its original, the UI renders a (discount|surcharge) badge with the delta %. **Labour only** —
material prices are not affected by the override.

- [x] 2. Persist an overridden labour-price column + a scoped percentage-adjust operation (backend)
  - Triage: expected — every estimate line keeps its **original** `unitPrice` (unchanged) plus a new
    `unitPriceOverride` column (default = `unitPrice`; seeded equal on line create and backfilled =
    `unit_price` for existing rows). A new endpoint applies a signed percentage to all lines in a
    scope (PROJECT = all lines of the estimate; WORK_CATEGORY = lines whose work's category matches;
    WORK_ITEM = the single line), writing each in-scope line's `unitPriceOverride = unitPrice ×
    (1 + pct/100)` and leaving `unitPrice` untouched. DRAFT-gated + project-scoped. Layer:
    **backend**.
    - DB: new changeset adds `estimate_lines.unit_price_override` (NUMERIC, nullable→backfilled =
      `unit_price`; new lines seed it = `unitPrice` in `copySingleWorkPrice`). Take the next free
      changeset number at implementation time and register it last in `changelog.xml`.
    - Service + controller: a project-scoped `applyLabourPriceAdjustment(estimateId, scope, targetId,
      pct)` that resolves the in-scope lines and sets each line's `unitPriceOverride` (never the
      original). Guard: DRAFT project + `ESTIMATE` UPDATE (reuse the `ESTIMATE` resource with a
      method-level `@RequiresPermission`; follow the entity-creation checklist only if a new managed
      surface is introduced).
    - Labour value: the estimate matrix labour must use the **override** — `labour = unitPriceOverride
      × volume` in `EstimateMatrixAssembler.assembleCell` and the row effective price in the row
      build. Decide/document whether `valueNet` (the persisted line value `unitPrice × quantity`) now
      tracks the override; per the user this override drives the displayed/offer labour, so compute
      the effective labour from the override while keeping the original `unitPrice` column as the
      untouched source of truth. Confirm no unintended recompute cascade beyond the estimate/offer
      labour read. **Materials are untouched.**
  - _Requirements: 1.2 (and a new estimate-labour-price-override requirement in requirements.md)_
  - _Depends on: none_
- [x] 3. Surface original vs overridden labour price in the matrix DTO (backend)
  - Triage: expected — `WorkRowDto` carries the **effective** labour unit price (the override) plus
    the **original** `unitPrice` so the frontend can render the divergence badge without a second
    call. Layer: **backend**.
    - Extend `WorkRowDto` (backend record + frontend `types/index.ts`): keep `unitPrice` as the
      effective/override labour unit price shown in the row, and add `baseUnitPrice` (the untouched
      original). Populate both in `EstimateMatrixAssembler` from the line's `unit_price` (original)
      and `unit_price_override` (effective), falling back to the catalog for unassigned works. No
      change to material math.
  - _Requirements: 1.2 (+ new estimate-labour-price-override requirement)_
  - _Depends on: 2_
- [x] 4. Labour-price override UI at three scopes + divergence badge (frontend)
  - Triage: expected — the user can apply a %-adjust at project scope (matrix header /
    `WorksRoomsMatrix.tsx`), group scope (group header in `WorkTypeGroupSection.tsx`, alongside the
    existing group actions), and per-work-item (the row header). After apply, the row shows a
    (discount|surcharge) badge with the delta % whenever the overridden price ≠ the original. Layer:
    **frontend**.
    - Add a small adjust control (signed % input + apply) at each scope wired to the new backend
      endpoint; invalidate/refetch the matrix on success. Render the badge in the row total column
      (reuses A's cell) when `unitPrice` (effective) ≠ `baseUnitPrice` (original).
    - i18n: new keys for the adjust control (label, placeholder, apply, discount/surcharge badge) in
      `pl.json` + `ru.json`.
  - _Requirements: 1.2 (+ new estimate-labour-price-override requirement)_
  - _Depends on: 3_
- [x] 5. Add the estimate-price requirement to requirements.md + author/refresh test-cases.md
  - Add a requirement for the scoped per-work-item price override (base vs effective, scope
    precedence/semantics, DRAFT gate) to `requirements.md`, and cover A + B in `test-cases.md`
    (API tests for the adjust endpoint against the Dockerized app; browser-engine scenarios for the
    matrix total money + the three-scope adjust + the badge) per the test-cases standard.
  - _Requirements: all of Round 8_
  - _Depends on: 1, 4_
- [x] V8. Validate Round 8 in browser
  - Rebuild the stack, open the estimate matrix on a DRAFT project. Confirm: (A) the Total column
    shows the row money sum for Р / М / Р+М and it follows the header toggle; (B) applying a labour
    %-adjust at project / category / work-item scope sets each in-scope line's OVERRIDE column
    (leaving the original `unitPrice` intact), the row shows a (discount|surcharge) badge with the
    delta, labour totals reflect the override, materials are unchanged, and subtotals update
    consistently.
  - _Requirements: all of Round 8_
  - _Depends on: 1, 2, 3, 4_

## Parallel execution plan (waves) — Round 8

- **Wave 1 (parallel):** 1 (frontend Total-money, independent) and 2 (backend base-price + adjust
  endpoint, independent) — different repos/files, no shared state.
- **Wave 2:** 3 — matrix DTO base-price (depends on 2's column).
- **Wave 3:** 4 — three-scope UI + badge (depends on 3's DTO field; also uses 1's total cell).
- **Wave 4:** 5 — requirements + test-cases (depends on 1 and 4 being defined).
- **Wave 5:** V8 — browser validation; depends on 1, 2, 3, 4.

## Round 8 — validation results (2026-11-05)

Stack: local Docker rebuilt (backend :8080, frontend :3000, admin `cto+1@loaders.dev`). DB migrated
with the new `estimate_lines.unit_price_override` column (changeset 211). Screen:
`http://localhost:3000/projects/2/estimate` (DRAFT, 76 work rows).

- **A — per-row Total money by mode — ✅ FIXED.** The Total column now shows a row money sum
  (testid `estimate-work-total-money-<id>`) on all 76 rows and it follows the header toggle: work 1
  row total read **1 220,82** in Р+М and **736,30** in Р (WORK) after one toggle click.
- **B — scoped labour-price override — ✅ FIXED.** Applied **+10%** at WORK_ITEM scope on work 1
  (`estimate-labour-adjust-work-1`): the effective labour unit price went **9,95 → 10,95 / м²**
  (override = original × 1.1), the WORK row total went **736,30 → 810,30**, and the row cell shows
  the divergence badge **+10,05%** (surcharge vs the untouched original). The original `unit_price`
  is preserved; materials are unchanged. Project and group scopes use the same endpoint/mechanism.
  Backend: 29 affected tests green, `compileJava/compileTestJava` green. Frontend: 253 estimate
  tests green; full `tsc -b` clean.

Note: task 5 (add the estimate-labour-price-override requirement to `requirements.md` + author
`test-cases.md`) remains open as documentation follow-up; the code (tasks 1–4) is complete and
verified.

Result: A + B fixed / 0 failed.

## Round 9 (2026-11-05)

Screen URL: `http://localhost:3000/projects/{id}/estimate`. Source: follow-up to Round 8-B (labour
price override). Reported: the TOTAL labour cost must compare the original sum with the override sum
and render the divergence the same way the per-row badge does. Visible to internal roles as today
(the estimate matrix is `ESTIMATE`-READ gated, so CLIENT never receives it).

Background (grounded in code): `CellDto` carries only the effective `labour`
(`EstimateMatrixAssembler.assembleCell`, = `unit_price_override x volume`), so the frontend cannot
total the original. `costModel.headerTotals` folds `works` from `cell.labour`;
`EstimateHeader.HeaderTotals` renders it as a `TotalCard`. The per-row badge lives in
`WorkTypeGroupSection.tsx` (delta% vs `baseUnitPrice`, `data-divergence` discount/surcharge).

- [x] 1. Expose the original labour per cell in the matrix DTO (backend)
  - Triage: expected - each assigned cell also carries `baseLabour = unit_price x volume` (the
    untouched original), alongside the existing effective `labour`. Layer: **backend**.
    - Add `baseLabour` to the backend `CellDto` (and its constructors/fixtures) and compute it in
      `EstimateMatrixAssembler.assembleCell` from `line.getUnitPrice()` (falling back as the row's base
      price does). Unassigned cells: zero. Mirror in the frontend `CellDto` type
      (`features/estimate/types/index.ts`, optional for older fixtures). No change to material math or
      to `labour`/`costRange`.
  - _Requirements: 1.2, 14.1 (+ the Round 8 labour-override requirement)_
  - _Depends on: none_
- [x] 2. Original-labour fold + header total comparison with divergence badge (frontend)
  - Triage: expected - the header "works" total card shows the effective labour sum, and when the
    original labour sum differs it also shows the original sum and a discount/surcharge badge
    (delta% = (effective - original) / original), styled like the per-row badge. Same for group
    subtotals if the group header shows the works subtotal. Layer: **frontend**.
    - `costModel.ts`: add a pure `baseLabourTotal(matrix)` (and group variant) summing
      `cell.baseLabour` over assigned cells; extract the delta/badge computation shared with
      `WorkTypeGroupSection` into a small pure helper so row and total use one rule; extend
      `costModel.property.test.ts` (no override => original == effective; scaling property).
    - `EstimateHeader.tsx` `HeaderTotals`/`TotalCard`: render the comparison (testids e.g.
      `estimate-total-works-original`, `estimate-total-works-divergence`).
    - i18n: reuse `estimate.matrix.discountBadge`/`surchargeBadge` or add `estimate.totals.*` keys in
      BOTH `pl.json` and `ru.json`.
  - _Requirements: 14.1, 14.2 (+ the Round 8 labour-override requirement)_
  - _Depends on: 1_
- [x] 3. Tests + docs
  - Backend: extend the matrix assembler test with an override scenario asserting `baseLabour` vs
    `labour`. Add the comparison to `requirements.md`/`test-cases.md` together with the still-open
    Round 8 task 5. Notifications: none. New i18n: frontend labels only (PL + RU).
  - _Requirements: all of Round 9_
  - _Depends on: 1, 2_
- [x] V9. Validate Round 9 in browser
  - Apply a labour %-adjust at project scope; the header works total shows effective and original sums
    and the badge with the correct delta; clearing the adjustment (0%) hides the badge. No override =>
    no comparison rendered.
  - _Requirements: all of Round 9_
  - _Depends on: 1, 2_

## Parallel execution plan (waves) - Round 9

- **Wave 1:** 1 - backend `baseLabour` (FE depends on the new field).
- **Wave 2:** 2 - frontend fold + header badge.
- **Wave 3:** 3 - tests/docs (depends on 1, 2).
- **Wave 4:** V9 - browser validation; depends on 1, 2.

## Round 9 - validation results (2026-11-05)

Stack: local Docker rebuilt. Screen `http://localhost:3000/projects/2/estimate` as ADMIN.

| # | Check | Evidence | Result |
|---|-------|----------|--------|
| 1 | `baseLabour` on the cell (API) | With +10% on the project: Σ`labour` 200 316,10, Σ`baseLabour` 182 104,61. After reset to 0%: both 182 104,61 and no row with `unitPrice != baseUnitPrice` | PASS |
| 2 | Header comparison, surcharge | `estimate-total-works` 200 316,10, `-original` "Исходно: 182 104,61", `-divergence` `+10,00%` surcharge | PASS |
| 3 | Group subtotals | +10% on the project: all 12 groups show `+10,00%` surcharge | PASS |
| 4 | Category-scoped discount | −10% on category 1 only: only `estimate-group-works-1` shows `−10,00%` (discount); header shows 179 347,38, original 182 104,61, `−1,51%` discount | PASS |
| 5 | No override, no comparison | After reset to 0%: header 182 104,61, zero `*-original` and `*-divergence` nodes | PASS |

Tests: backend `EstimateMatrixAssemblerLabourOverrideTest` 5/5 (new), plus `...PackageDivergenceTest`
7/7, `MaterialsListAssemblerPropertyTest` 6/6, `MarginsListAssemblerPropertyTest` 3/3. Frontend:
estimate suite 268 tests (including new `priceDivergence`, `costModel.baseLabour` property tests and
`LabourDivergenceNote`), project-offer suite 42 tests, `tsc -b` clean.

Observation: after a change made directly through the API (outside the UI), the first page load still
showed the earlier matrix from cache; the next load was correct. Changes made in the UI refetch the
matrix, so users are not affected.

Round 8 task 5 (requirements and test-cases for the override) is now covered by requirements 20-22
and the Rounds 7-9 test-case section. Result: 5 of 5 checks passed. Round 9 closed.
