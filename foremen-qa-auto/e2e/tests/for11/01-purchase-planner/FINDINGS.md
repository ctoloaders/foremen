# FOR-11-01 purchase planner: e2e findings

## 1. Summary

**94 tests passed, 8 skipped as known product bugs (BUG-1..BUG-4), 0 failed. Not automated: 5 items,
see section 2.** Final run (folder, exit 0):

```
cd foremen-qa-auto/e2e
export PATH=/Users/alexander.borohov/.nvm/versions/node/v24.18.0/bin:$PATH NPM_CONFIG_USERCONFIG=/Users/alexander.borohov/.kiro/settings/e2e.npmrc
npx e2e run tests/for11/01-purchase-planner --output .e2e-for11-01 --action-timeout 5000 --retries 0
-> Test Files 11 passed (11); Tests 94 passed | 8 skipped (102); EXIT=0
```

`tests/for11/01-purchase-planner/run.sh <log> <files...>` wraps that command. `--output .e2e-for11-01` keeps the report
and traces out of the shared `.e2e/` directory that parallel agents overwrite (it must be inside the project root).

Files: `support.ts` (helpers, SQL fixtures, dataset builder), `api-list`, `api-request`, `api-board-rights`,
`api-worklink` (API, Part A), `ui-shell`, `ui-selection`, `ui-rows`, `ui-board`, `ui-deeplink`, `ui-form`, `ui-misc`
(UI, Part B).

Repeatability: each file creates its own run-id users (`dash+<id>-f11*@example.com`), two projects (`F11-<id>-A/B`),
positions (`qa-<id>-*`) and requests (`QA-<id>-*`) in `beforeAll` and deletes them in `afterAll` (children first, then
the projects through the API, then users). After the final run `psql` shows 0 rows for projects `F11-%`, users
`dash+%-f11%`, positions `notes LIKE 'qa-%'`, requests `reference_number LIKE 'QA-%'`.

Fixtures that REST cannot reach are SQL on this run's rows only: stages ACCEPTED / CLIENT_PAID / RECEIVED / ORDERED,
project `status='ACTIVE'` (the API refuses DRAFT -> ACTIVE without rooms and an estimate), P2 `planned_delivery_date`
-3 days, P9 `actual_purchase_price=150`, estimate lines and schedule bars for project A. Catalog data used read-only:
materials 40..43, 61 ("MT9", type without norms), 464 (no type), 569 ("MT1", type 41 with norms for work item 73 =
category 4 "C1" and work item 192 = category 11 "C2"), work item 155 (category 9 "C3").

## 2. Coverage table

| TC | Test (file: title) | Status |
|----|--------------------|--------|
| A1-01 | api-list: sorted by siteDeliveryDate asc, nulls last, ties by id; unknown sort field 400 localized | pass |
| A1-02 | api-list: row composition and money of P3 (PL and RU unit names) | pass (`marginPct` is 0.331522, test-case says 0.3315; compared to 3 decimals) |
| A1-03 | api-list: status / sourcingPath / requestId, noRequest, q with `%` `_`, buyer MINE/UNASSIGNED, pagination | pass |
| A1-04 | api-list: hideDelivered | pass |
| A1-05 | api-list: BUYER company-wide vs MANAGER own projects | pass |
| A2-01 | api-list: slice definitions, invalid slice 400 | pass (PAID also contains the RECEIVED P10, per requirements R2.2; test-case lists P5 only) |
| A2-02 | api-list: slice-counts equal slice selections | pass |
| A2-03 | api-list: urgent filter equals flag | pass |
| A3-01 | api-list: groups ordered by earliest delivery date, totals, marginIncomplete | pass (P2 is pinned late by SQL, so the order is R1, R2, no-request) |
| A3-02 | api-list: group sums equal row sums | pass |
| A3-03 | api-list: NONE one group, unknown groupBy 400 | partial: `EXTERNAL_ORDER` / `PROFORMA` are now valid (FOR-11-03 landed), so the "reserved, 400" part of test-case/R3.4 is obsolete and not asserted |
| A4-01 | api-list: selection total, skip reasons, empty / 501 ids | pass |
| A4-02 | api-list: FOREMAN 403, BUYER 200 | pass |
| A4-03 | api-list: mixedProjects | pass |
| A5-01 | api-request: request from positions, planned date set | pass |
| A5-02 | api-request: 409 atomic, mixed projects 400, empty 400, foreign-project position refused | pass |
| A5-03 | api-request: single request.assigned notification + deep link; non-BUYER assignee 400 | pass |
| A5-04 | api-request: BUYER and FOREMAN 403 | pass |
| A5-05 | api-request: buyers list | partial: MANAGER sees active BUYERs only, deactivated buyer disappears, FINANCIER 403. FOREMAN gets 200, not 403 (see note N-1) |
| A6-01 | api-board-rights: columns order, cards, over-ceiling, ordering | pass |
| A6-02 | api-board-rights: limit, hasMore, invalid limit, hideDelivered, buyer=MINE | pass |
| A6-03 | api-board-rights: FOREMAN board has no money | pass |
| A7-01..A7-08 | api-worklink (13 tests) | pass |
| A8-01 | api-board-rights: role matrix for read endpoints, selection-total, from-positions | pass for ADMIN/MANAGER/BUYER/FOREMAN/FINANCIER; `procurement/buyers` FOREMAN deviates (N-1) |
| A8-02 | api-board-rights: FOREMAN money null everywhere; `sort=salePrice` 400 | pass |
| A8-03 | api-board-rights: allowedActions by role/path | pass |
| A9-01 | not automated: needs SQL statement counting in the backend (test profile / log); not available against the running stack |
| B1-01 | ui-shell: BUYER board default + planner tab; MANAGER planner default + board tab; FOREMAN single tab | pass |
| B1-02 | ui-shell: filters in URL, survive reload | pass |
| B2-01 | ui-misc: column headers, late badge, delivery-risk badge, over-ceiling badge (BUYER), sort header | pass (sort order change itself not asserted) |
| B2-02 | ui-shell: slice counters and narrowing | pass |
| B2-03 | ui-misc: select urgent | pass |
| B2-04 | ui-shell: grouped mode collapsed, lazy expand | pass (switch back to ungrouped is exercised through the URL `groupBy=NONE` in other tests) |
| B2-05 | ui-shell: default on, persists after reload, corrupt storage; ui-misc: per-user key | partial: "log in as another user and see default on" is covered by the key check, not by a second login |
| B3-01 | ui-selection: totals, skipped reason, clear, mixed-project warning (ADMIN), BUYER has no create button | pass (after clear the bar stays with count 0, test-case says it disappears: N-2) |
| B3-02 | ui-selection: request created with buyer | pass (default date in the dialog not asserted) |
| B3-03 | ui-selection: detach, edit request; delete of the emptied request | partial: detach and edit pass; delete of an emptied request = BUG-1 (skipped) |
| B4-01 | ui-form | BUG-3 (skipped); project-material quick-create, seller quick-create not automated (create sheet unreachable) |
| B4-02 | ui-form | create-sheet variants BUG-3, edit-sheet variant BUG-4 (all skipped); the same logic is covered at API level (A7) |
| B4-03 | ui-form: ACCEPTED locked fields, quantity 0 message | partial pass; "edit and save" and "server error keeps input" = BUG-4 (skipped) |
| B5-01 | ui-rows: history, restore, pick-analog block, no controls on ACCEPTED | pass (analog suggestion list is empty in the fresh data, selecting an analog not automated) |
| B5-02 | ui-rows: send to buyer, client-own in delivery, cancel/reopen/delete with confirm, allowed actions only | pass (BUYER keeps Edit: N-3) |
| B6-01 | ui-board: six columns, own vs all buyers; card content | partial: columns/filter pass; card content = BUG-2 (skipped) |
| B6-02 | ui-board: hide-delivered, load more (31 extra cards), drawer opens, no drag handle | pass |
| B6-03 | ui-board: cancelled card disappears after reload | partial: the focus / 60 s auto-refresh is timing dependent, not asserted |
| B7-01 | ui-deeplink: bell -> request group expanded and highlighted; unknown id; foreign project | pass |
| B8-01 | ui-misc: RU UI without raw keys (planner and board), PL/RU key parity for `purchasePlanner` / `buyerKanban`, `pages.deliveries` removed | pass (server error messages by Accept-Language checked in A1-01, A1-02 only; the dialogs/form in RU are not walked) |
| B8-02 | ui-misc: phone viewport 390x844: cards instead of table, board one column | partial (sticky bottom selection bar and BUYER bottom nav not asserted) |
| B9-01 | ui-shell: FOREMAN no add button, no selection, no actions, one tab | pass |
| R-01 | api-board-rights | pass |
| R-02 | api-board-rights | pass |
| R-03 | api-board-rights | partial: BUYER has no access to payroll and bank; the full grant matrix against FOR-11-00 is not compared |
| R-04 | not automated: dashboard widgets are covered by the FOR-10 suite; menu item "Dostawy" and BUYER bottom nav are exercised implicitly by navigation only |
| R-05 | api-board-rights | partial: column exists once and changeset 233 recorded; re-running liquibase needs a container restart (forbidden) |
| R-06 | api-board-rights | partial: project-scoped finishing_materials are not in the global list when filtered by project; the planner/estimate dropdowns are not checked (no project material could be created, BUG-3) |

Not automated: A9-01, R-04, B4-01 project-material/seller quick-create, analog selection, auto-refresh in B6-03, R-05 re-run.

## 3. Bugs

All four skipped tests keep the correct assertions; remove `.skip` when fixed.

### BUG-1: an emptied request disappears from the planner, "Delete empty request" is unreachable
- Screen: `/deliveries?project=<id>` (planner, grouped by request). API: `GET /api/purchase-positions/planner/groups`.
- Repro: create a request from one position (`POST /api/purchase-requests/from-positions`), detach the position
  (`DELETE /api/purchase-requests/{id}/positions/{positionId}` or the unlink button in the UI), open the planner.
- Observed: `GET /api/purchase-requests` still returns the request with `positionCount: 0`, but `planner/groups` returns only
  the synthetic "no request" group; the request has no group header, so no delete button. Frontend shows delete only
  when `group.positionCount === 0`, which the backend never returns.
- Expected (R15.2): "Delete for an empty request" in the request group header.
- Evidence: debug run: `groups [[null,1]]` vs `list [[178,0]]`. Skipped test: `ui-selection.e2e.ts` "TC-B3-03 an emptied request stays reachable ...".
- Layer: both. Suspected files: `foremen-backend/.../service/procurement/planner/PlannerGroupService.java` (groups built from the position query,
  empty requests never included); `foremen-frontend/src/features/purchase-planner/components/RequestHeaderActions.tsx` (`canDelete` needs `positionCount === 0`).
- Requirement: R15.2 (and R3.1/3.2 group definition). Feature-sized: no (small: include requests without positions when no row filter is active, or show them in a separate section).

### BUG-2: buyer board cards ignore the API payload: model shows "—", no price/ceiling block, no over-ceiling warning
- Screen: `/deliveries?tab=board&project=<id>` as BUYER. API: `GET /api/purchase-positions/board`.
- Repro: open the board with a WITH_BUYER card whose position has sale price 100 and actual purchase price 150.
- Observed: card text is "<project> — 1 m² <request> Na budowę: <date>": model is "—", no `board-card-money`, no "Powyżej limitu".
  The API card has nested `material {model, producerName, photo}` and `money {salePrice, plannedPurchasePrice, actualPurchasePrice, overCeiling}`
  (verified, A6-01 passes), but `BoardCard` reads flat `card.model`, `card.photo`, `card.salePrice`, `card.actualPurchasePrice`, `card.overCeiling`.
- Expected (R16.1): material (photo, model, producer), sale price as ceiling next to the purchase price, over-ceiling warning.
- Evidence: trace shows `text="—"` and no money node for cards 2578/2581; skipped test `ui-board.e2e.ts` "TC-B6-01 card content ...".
- Layer: frontend (type `BoardCard` in `features/purchase-planner/types/index.ts` does not match the backend `BoardService.BoardCard` record).
  Suspected files: `components/BoardCard.tsx`, `components/BoardColumn.tsx`, `types/index.ts` (mapping from the board response; `rowToCard` maps planner rows only).
- Requirement: R16.1. Feature-sized: no.

### BUG-3: "Dodaj pozycję" (Add position) does nothing, positions cannot be created from the UI
- Screen: `/deliveries` (planner tab) as MANAGER/ADMIN.
- Repro: click "Dodaj pozycję".
- Observed: nothing opens. `PurchasePlannerPage` renders `<PlannerTab state update hideDelivered />` without `onAddPosition`; the button's `onClick` is
  `undefined`. `PositionFormSheet` in `create` mode is mounted nowhere outside unit tests, so the material picker, seller quick-create and
  "Add project material" flow (B4-01) are unreachable.
- Expected (R13.2, R14.1-14.3): the button opens the create sheet.
- Evidence: tap succeeds, no dialog in the screen tree (trace); `grep` finds no non-test use of `kind: 'create'` for the form. Skipped tests (3): `ui-form.e2e.ts` TC-B4-01, TC-B4-02 (two).
- Layer: frontend. Suspected files: `foremen-frontend/src/features/purchase-planner/PurchasePlannerPage.tsx` (missing state + `PositionFormSheet` + `onAddPosition`), `components/PlannerTab.tsx`.
- Requirement: R13.2, R14.1. Feature-sized: no (wire the existing sheet).

### BUG-4: the edit sheet of an existing position cannot be saved (project and material lost on open)
- Screen: `/deliveries?...` row menu -> "Edytuj".
- Repro: open Edit on any position (PLANNED, ACCEPTED, CLIENT_PAID), change the note, press "Zapisz".
- Observed: the sheet stays open. The project select and the material combobox are empty ("Wybierz materiał"), the work-category block shows
  "Nie znaleziono" (data-source NONE even for a type-41 material), and validation errors appear: raw English "Number must be greater than 0" under
  Project (unlocalized, zod default for `projectId`) and "Wybierz materiał." Quantity, prices and notes are filled correctly. `GET /api/purchase-positions/{id}`
  returns the right `projectId` and `plannedMaterialId`, so the form values are lost after the reset.
- Suspected root cause: in `PositionFormSheet.tsx` the project `<Select>` `onValueChange` does `field.onChange(Number(v)); setValue('plannedMaterialId', 0); setValue('workCategoryId', null)`;
  the Radix select fires `onValueChange` with an empty value when the controlled value has no matching `SelectItem` yet (projects list not loaded), which zeroes
  `projectId` and `plannedMaterialId`. Not verified beyond the symptom.
- Expected (R14.1, R14.7): edit saves; server errors keep the input and show the localized message.
- Evidence: trace screen of `dbg` run (project/material combobox empty, alerts present); the locked-fields check (P3) and the quantity-0 message still work. Skipped tests (3): `ui-form.e2e.ts` "an existing position can be edited and saved", "the edit sheet shows the automatic work category ...", "a server error keeps the sheet open ...".
- Layer: frontend. Suspected files: `components/PositionFormSheet.tsx` (project Select handler, reset effect), `schemas/position-form.ts`.
- Requirement: R14.1, R14.4, R14.7. Feature-sized: no. Note: with BUG-3 and BUG-4 the whole position form (R14) is unusable from the UI.

## 4. Test-case deviations that are not product bugs, and blockers

- N-1: TC-A5-05 / A8-01 expect 403 on `GET /api/procurement/buyers` for FOREMAN ("no PURCHASE_REQUESTS READ"). FOREMAN holds the seeded
  `PURCHASE_REQUESTS` READ grant (FOR-11-00), the exact guard design.md names, so 200 is by design. The test asserts 200. Low risk: it exposes buyer names to FOREMAN; decide in the spec whether FOREMAN should lose the grant.
- N-2: TC-B3-01 says the selection panel disappears after "Wyczyść". The sticky bar stays with "Zaznaczono: 0" (it hosts "select urgent"); the test asserts that.
- N-3: TC-B5-02 says BUYER and FOREMAN get no row menu. BUYER holds `PURCHASE_POSITIONS` UPDATE, so `allowedActions` has EDIT (and REPLACE_VARIANT in early stages), consistent with R1.7; FOREMAN has none. Asserted accordingly.
- N-4: the Setup in test-cases.md makes P2 late through `needByDate = -3` while in request R1. Attaching to a request sets `plannedDeliveryDate = request.expectedDeliveryDate`, which becomes the site delivery date, so P2 is not late. The dataset pins P2 `planned_delivery_date` to -3 by SQL. TC-A2-01 PAID also contains the RECEIVED P10; TC-A3-03 `EXTERNAL_ORDER`/`PROFORMA` are now valid groupings.
- Blockers / limits: the e2e results directory `.e2e/` is shared and overwritten by parallel agents, so this suite uses `--output .e2e-for11-01` (remove it after reading traces; it is untracked). The project cannot be activated through the API without rooms/estimate, hence the SQL fixture. Midnight crossing during a run (Europe/Warsaw) can shift date-relative expectations by one day.
