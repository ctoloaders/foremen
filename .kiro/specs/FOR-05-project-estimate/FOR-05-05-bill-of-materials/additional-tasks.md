# Additional tasks: FOR-05-05-bill-of-materials (estimate auto-recalculation on room changes)

Screen URLs: http://localhost:3000/projects/{id}/rooms and http://localhost:3000/projects/{id}/estimate

> **Status (2026-11-01):** tasks 1–9 and V are DONE and browser + API validated. The auto-recalc
> engine landed as ONE atomic rooms save — `POST /api/rooms/project/{projectId}/save?force=` — that
> applies deletes → updates → creates and the estimate derivation in a single transaction, diffs the
> estimate before/after, and (without `force`) throws a 409 that rolls everything back. The matrix
> tab AND the full-page room editor both use it. Task 10 (test-cases) remains open.
>
> Implementation map: `service/estimate/recalc/{RoomsSaveService, EstimateSnapshot, EstimateImpact,
> EstimateChangedEvent}`, `EstimateAssignmentService.{rederiveRoom, unassignRoom,
> replicateFromSibling}`, `EstimateRecalcConflictException` → `ForemenControllerAdvice` 409,
> `collab/EstimateChangedCollabListener` (refresh open estimate tabs); FE
> `rooms/api/{recalc.ts, saveProjectRooms}`, `useBulkSaveRooms` (single request), `useSaveRoomInProject`,
> `rooms/components/EstimateRecalcDialog`.
>
> Decisions taken while implementing:
> - Re-derivation uses the estimate's applied package (`appliedPackageCode`) — the same formula
>   context as apply-package. Cells whose stored Volume predates current formulas therefore also
>   show up in the diff; the user sees and approves them.
> - Add-room sibling = the same-room-type room with the most assigned works (tie → lowest id); its
>   works are assigned through `doAssign`, so material lines are seeded from consumption norms
>   (package-merged finishing allocations are not copied — re-run apply-package for those).
> - `EstimateChangedEvent` is published for a forced rooms save (`ROOMS_CHANGED`) and for an
>   Estimate-tab Save whose net total changed (`ESTIMATE_SAVED`).
> - A room delete against a NON-draft estimate that still has cells for the room is rejected with
>   the existing `error.estimate.locked` (never silently cascades a locked estimate).
> - Fixed alongside (pre-existing): the room editor seeded `{value, source}` metric objects into the
>   number fields, so every metric rendered empty and the form could not be saved.

Enhancement batch (2026-10-29). Approved by the user. NEW feature tasks (not bug fixes). This spec
owns the estimate derivation engine (`EstimateRecomputeService`, `EstimateAssignmentService`,
`EstimateLineRoomQtyService.deriveRoomQuantitiesForRoom`, `EstimateMatrixAssembler`,
consumption-norm seeding). The estimate matrix UI lives in FOR-05-04 (`EstimateTab`); UI-only tasks
here reference it. The offer cancel/re-version reaction is owned by FOR-05-07 (see that spec's
`additional-tasks.md`); this spec only emits the estimate-changed signal it consumes.

## Scope (user intent)

On `/projects/{id}/estimate`, the estimate must stay in sync with room dimensions automatically:

1. **Auto-recalc on room change.** When room dimensions change, recompute the estimate — labour
   (Volume × unit price) and material quantities (consumption norm × Volume) — for every cell/line
   that is NOT manually filled. A cell with a manual Volume override (`EstimateLineRoomQtyEntity.
   volumeOverridden = true`) and a material line with a manual quantity override
   (`EstimateLineRoomMaterialEntity.qtyOverridden = true`) are LEFT UNTOUCHED (user decision 2a).
2. **Change-impact approval (force-flag protocol).** When room dimensions are saved, the backend
   first does a dry run with **no force flag**: it validates the potential estimate changes. If any
   estimate cell/line would change, respond **HTTP 409 Conflict** with a short description of what
   will change (per-line: work name, room, old→new Volume / labour / material qty). The UI shows a
   warning dialog with Approve / Decline. On **Approve**, the UI re-sends the SAME room-save request
   with **force = true**, which performs the room save AND the estimate recalculation. On
   **Decline**, nothing is persisted (neither the room change nor the estimate change) — user
   decision 2b.
3. **Add room → auto-apply same logic.** When a NEW room is saved, if the project's estimate already
   has works/materials applied (manually or via a package) to **another room of the same room type**,
   apply the same set to the new room and derive its quantities — gated by the same approval
   (user decision 2c). "Same logic" = replicate the works assigned (and their package-applied
   material lines) from an existing room of the same `roomTypeId`.
4. **Delete room → cascade-remove from estimate.** Relax the current `estimate_line_room_qty.room_id`
   `ON DELETE RESTRICT`: deleting a room now cascade-removes its estimate cells (`EstimateLineRoomQty`
   rows and their material lines), gated by the same approval warning (user decision 2d). A line left
   with no rooms is dropped (mirrors `doUnassign`).
5. **Manual-cell icon in the estimate.** In the estimate matrix, show a visible icon on cells that
   have a manually set Volume (and/or a material line with a manual quantity), with a hover tooltip
   explaining it is a manual override that auto-recalc will not change.

### Design anchors (grounded in code)

- The single recompute funnel is `EstimateRecomputeService.recomputeEstimate(estimate)`; the room→
  estimate derivation already exists as `EstimateLineRoomQtyService.deriveRoomQuantitiesForRoom(
  roomId, activePackageId)` but is **unwired** (no room-save hook). The approval protocol wires a
  dry-run (validate-only) vs. force (apply) path around it.
- Manual guards already exist: `volumeOverridden` (cell Volume) and `qtyOverridden` (material qty).
  Auto-recalc MUST respect both (skip overridden cells/lines) — the existing `doAssign`
  `!isVolumeOverridden()` guard is the precedent.
- Room save flows through the rooms bulk-save path; the force flag is a new request param on that
  path (or a sibling endpoint) that, when true, also runs the estimate derivation after the room
  write in the same transaction.
- "Active package" context for derivation = the estimate's applied package (`estimate.appliedPackageCode`
  / the resolved `OfferPackageEntity`), already the input to `deriveRoomQuantitiesForRoom`.

### i18n

- Backend: no new entity i18n fields.
- Frontend (PL + RU parity in `pl.json` / `ru.json`):
  - `estimate.recalc.warningTitle`, `estimate.recalc.warningBody`, `estimate.recalc.approve`,
    `estimate.recalc.decline`, and a per-change line template
    `estimate.recalc.changeLine` (work / room / old→new).
  - `estimate.manualCell.icon` aria-label + `estimate.manualCell.tooltip` hover text.
  - `rooms.recalc.*` equivalents for the warning shown on the rooms-save path if the dialog is hosted
    there (reuse the `estimate.recalc.*` keys if the dialog component is shared).

### Notifications

None in this spec. The approval is an in-session dialog; the acting user sees the result
immediately. (The offer withdraw/new-version notification is owned by FOR-05-07.)

## Tasks

- [x] 1. DB: relax room-delete restriction for estimate cells
  - DONE: `190-estimate-line-room-qty-room-cascade.xml` drops `fk_elrq_room` and recreates it
    `ON DELETE CASCADE` (was RESTRICT, 078); registered last in `changelog.xml`. Migration applied on
    the live stack — verified `pg_constraint.confdeltype = 'c'` (CASCADE) for `fk_elrq_room`.
    (Numbered 190, since 189 is the SALESMAN seed.)
  - _Requirements: FOR-05-05 R3/R19 (estimate line room qty lifecycle)_
  - _Depends on: none_

- [x] 2. Backend: estimate change-impact computation (dry run)
  - Add a pure/read-only impact computation: given a project and a prospective set of room-dimension
    changes (and/or a new/deleted room), compute which estimate cells/lines WOULD change under
    derivation — skipping `volumeOverridden` cells and `qtyOverridden` material lines — and return a
    structured diff (per line: workItem, room, old→new Volume, labour, material quantities). Reuse
    `deriveRoomQuantitiesForRoom`'s derivation against a replay (mirror the `assemblePreview`
    rollback-only pattern so nothing persists).
  - _Requirements: FOR-05-05 R3 (derived quantities), R2a (skip manual overrides)_
  - _Depends on: none_

- [x] 3. Backend: force-flag wiring on the room-save path
  - Add a `force` flag to the room bulk-save request path. On `force = false` (default), after
    staging the room change, run task 2's impact computation; if the diff is non-empty, abort with
    **409** carrying the localized diff payload (new error key `error.estimate.recalc.conflict` +
    the structured changes). On `force = true`, persist the room change AND run
    `deriveRoomQuantitiesForRoom` + `recomputeEstimate` in the same transaction so the estimate is
    recalculated. Respect the manual guards (skip overridden cells/lines).
  - _Requirements: FOR-05-05 R3; user decision 2a/2b_
  - _Depends on: 2_

- [x] 4. Backend: add-room propagation from a same-room-type sibling
  - When a new room is saved (force path), if the estimate has works/material lines applied to an
    existing room of the SAME `roomTypeId`, replicate that room's assigned works (and
    package-applied material lines) onto the new room via the existing assign/seed path, then derive
    its quantities. Included in the dry-run diff so it is part of the approval. If no same-type
    sibling has applications, do nothing for the new room.
  - _Requirements: FOR-05-05 R3; user decision 2c_
  - _Depends on: 2, 3_

- [x] 5. Backend: delete-room propagation to the estimate
  - When a room is deleted (force path), cascade-remove its estimate cells (relies on task 1's FK +
    JPA orphan removal) and drop any line left with no rooms (mirror `EstimateAssignmentService.
    doUnassign`), then `recomputeEstimate`. Included in the dry-run diff for the approval.
  - _Requirements: FOR-05-05 R3/R19; user decision 2d_
  - _Depends on: 1, 2, 3_

- [x] 6. Backend: publish an estimate-changed signal for the offer reaction
  - After a committed estimate recalculation (the force path's recompute+flush), publish an
    `EstimateChangedEvent(projectId, estimateId)` via `ApplicationEventPublisher` (mirror
    `OfferStateChangeEvent`), consumed by the FOR-05-07 offer reaction via
    `@TransactionalEventListener(AFTER_COMMIT)`. This spec only publishes; FOR-05-07 owns the
    consumer. (Also published by estimate matrix saves that change numbers, so an estimate edit
    likewise triggers the offer reaction.)
  - _Requirements: FOR-05-05 R3; cross-ref FOR-05-07_
  - _Depends on: 3_

- [x] 7. Frontend: change-impact approval dialog + force re-send (rooms save)
  - In the rooms bulk-save flow (`RoomsMatrixTab`), on a 409 `error.estimate.recalc.conflict`,
    render a warning dialog listing the estimate changes (work / room / old→new) with Approve /
    Decline. On Approve, re-send the identical save with `force = true`. On Decline, keep the pending
    change set unsaved (nothing persisted). Localized via `estimate.recalc.*`.
  - _Requirements: user decision 2b_
  - _Depends on: 3_

- [x] 8. Frontend: manual-override icon on estimate cells
  - DONE: `MatrixCell` renders a `Pin` icon (`estimate-cell-manual-{work}-{room}`) on an assigned cell
    when `cell.volumeOverridden` or any material line is `quantityOverridden`, with the
    `estimate.manualCell.tooltip` hover text. Browser-verified on project 1: 11 assigned cells, 1
    manual pin on cell 1-1 with the translated tooltip. 235 estimate tests pass.
  - _Requirements: FOR-05-05 R-manual-cell visibility_
  - _Depends on: none_

- [x] 9. Frontend + backend i18n keys
  - DONE: `estimate.manualCell.tooltip` (task 8) plus `estimate.recalc.*` (dialog title/body, column
    headers, change kinds, total line, manual note, approve/decline, success toast) in PL + RU, and
    the backend `error.estimate.recalc.conflict` in `messages.properties` / `messages_ru.properties`.
  - _Requirements: all above_
  - _Depends on: none_

- [ ] 10. Author test-cases.md
  - Create/append `test-cases.md` per `.kiro/steering/test-cases.md`. API scenarios (Dockerized app):
    the no-force room save returns 409 with a change diff when derived cells would change; the force
    save applies the recalculation and skips overridden cells/lines; add-room propagation from a
    same-type sibling; delete-room cascades estimate cells; manual-override cells are never
    recomputed. UI scenarios: the approval dialog Approve/Decline behavior, the manual-cell icon +
    tooltip. Repeatability via a generated draft project / unique run-id and teardown; regression
    group for existing estimate assign/package-apply paths.
  - _Requirements: the requirements covered by the test cases (FOR-05-05 R3/R19, decisions 2a–2d)_
  - _Depends on: 1, 2, 3, 4, 5, 6, 7, 8, 9_

- [x] V. Validate end-to-end in the browser + API
  - Rebuild backend + frontend. On a draft project: change a room dimension → confirm the 409 warning
    lists the right changes → Approve → estimate recomputes (non-overridden cells only) → an
    overridden cell stays. Add a room of an existing type → same works applied with approval. Delete
    a room → its estimate cells removed with approval. Confirm the manual-cell icon.
  - DONE (2026-11-01, project 2, local stack): API dry run → 409 with 17 changes and NOTHING
    persisted (floor + total unchanged); force → room + estimate committed; a `volumeOverridden` cell
    stayed put while its neighbour re-derived. Browser: matrix edit → dialog (13 changes + total
    line) → Decline keeps the pending edit and persists nothing → Approve commits + toast; add a Hol
    room → 40 ADDED cells = the Hol sibling's works; delete it → 40 REMOVED, room + cells gone, total
    back; room editor save → dialog → Approve → redirect. Estimate tab shows the refreshed total.
  - _Requirements: as all tasks_
  - _Depends on: 1, 2, 3, 4, 5, 6, 7, 8, 9_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1 (DB cascade), 2 (dry-run impact), 8 (manual-cell icon, FE-only),
  9 (i18n keys) — independent; different files/layers.
- **Wave 2 (parallel):** 3 (force wiring, depends on 2) — serialized before its dependents.
- **Wave 3 (parallel):** 4 (add-room propagation, dep 2,3), 5 (delete-room propagation, dep 1,2,3),
  6 (estimate-changed event, dep 3), 7 (approval dialog, dep 3) — all depend on the force path but
  touch different areas (assign-replicate / delete / event publish / FE dialog).
- **Wave 4:** 10 — test-cases.md depends on all implementation.
- **Wave 5:** V — browser+API validation depends on all implementation.
