# Bug report: FOR-11-01-purchase-planner

Screen URL: http://localhost:3000/deliveries?project=<id> (planner, board tab)

## Round 1 (2026-11-16)

Source: e2e run of `foremen-qa-auto/e2e/tests/for11/01-purchase-planner` (94 passed, 8 skipped as bugs).
Evidence: `tests/for11/01-purchase-planner/FINDINGS.md`.

- [x] 1. An emptied request disappears from the planner, so "Delete empty request" is unreachable
  - Triage: observed: after its last position is removed the request is still returned by `GET /api/purchase-requests` (`positionCount: 0`) but `GET /api/purchase-positions/planner/groups` no longer returns its group, so the header action never shows; expected: the empty request group stays visible with "Delete" (R15.2). Layer: both. Suspect: `service/procurement/planner/PlannerGroupService.java` (groups built from positions only), `components/RequestHeaderActions.tsx` (`canDelete` needs `positionCount === 0`).
  - _Requirements: R15.2, R3.1, R3.2_
  - _Depends on: none_
- [x] 2. Buyer board cards ignore the API payload (model "—", no price/ceiling block, no over-ceiling warning)
  - Triage: observed: card shows "—" for the model and no `board-card-money`; expected: material (photo, model, producer), sale price as ceiling next to the purchase price, "Powyżej limitu" warning. Layer: frontend. Suspect: `features/purchase-planner/types/index.ts` (`BoardCard` is flat, backend `BoardService.BoardCard` nests `material{}`/`money{}`), `components/BoardCard.tsx`, `BoardColumn.tsx`.
  - _Requirements: R16.1_
  - _Depends on: none_
- [x] 3. "Dodaj pozycję" does nothing; positions cannot be created from the UI (also found by FOR-11-00)
  - Triage: observed: the button has no handler; expected: it opens the create sheet. Layer: frontend. Suspect: `PurchasePlannerPage.tsx` renders `<PlannerTab … />` without `onAddPosition`; `PositionFormSheet` is mounted only in edit mode (`RowActionsMenu.tsx`).
  - _Requirements: R13.2, R14.1; FOR-11-00 R17.8, R20.6_
  - _Depends on: none_
- [x] 4. The edit sheet of an existing position cannot be saved (project and material lost on open)
  - Triage: observed: project and material are empty on open, save fails with the raw "Number must be greater than 0"; expected: the sheet is prefilled and saves; server errors keep input. Layer: frontend. Suspect: `components/PositionFormSheet.tsx` (project `Select` `onValueChange` fires with an empty value and resets `projectId`/`plannedMaterialId`), `schemas/position-form.ts` (raw message).
  - _Requirements: R14.1, R14.4, R14.7_
  - _Depends on: 3_ (same component)
- [x] 5. Every URL filter change remounts the whole page, so text typed into the planner search is lost (found during validation)
  - Triage: observed: TC-B1-02 typed a search right after toggling "Pilne"; the `q` filter never reached the URL and the box was empty; live check showed every query-string change remounts the route subtree (all node ids change). Expected: filters kept in the URL update without a remount (R12.3). Layer: frontend (app shell, all pages). Cause: `foremen-frontend/src/app/layout/RouteErrorBoundary.tsx` keyed the boundary by `location.key`, which changes on every navigation, including `replace` of the query string.
  - _Requirements: R12.3_
  - _Depends on: none_
- [x] V1. Validate Round 1
  - Rebuild, remove `.skip` from the BUG tests, run `npx e2e run tests/for11/01-purchase-planner`.
  - _Depends on: 1, 2, 3, 4, 5_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 2, 3 — different files.
- **Wave 2:** 4 — depends on 3 (`PositionFormSheet.tsx`).
- **Wave 3:** V1

## Round 1 — fixes and validation results (2026-11-16)

| Bug | Fix | Files |
|-----|-----|-------|
| 1 | For `groupBy=REQUEST` with no row-narrowing filter, request groups without positions are added (count 0, project scope applied), so "Delete empty request" is reachable. | `service/procurement/planner/PlannerGroupService.java`, `PlannerQueryService.java` |
| 2 | `BoardCard` type follows the backend shape (`material{}`, `money{}`); model, producer, photo, price/ceiling block and "Powyżej limitu" render. | `features/purchase-planner/types/index.ts`, `components/BoardCard.tsx`, `BoardColumn.tsx`, `PositionDrawer.tsx` |
| 3 | `PlannerTab` opens `PositionFormSheet` in create mode from "Dodaj pozycję" (default project = current filter). | `components/PlannerTab.tsx` |
| 4 | The project `Select` ignores empty/unchanged values (no reset on open); the schema uses the localized `purchasePlanner.form.validation.project`. | `components/PositionFormSheet.tsx`, `schemas/position-form.ts` |
| 5 | The route error boundary is keyed by `pathname` instead of `location.key`. | `app/layout/RouteErrorBoundary.tsx` |

Tests: `PlannerGroupsIntegrationTest` (new case `emptyRequestStaysVisible`), `PlannerGroupServiceTest`,
`PlannerAbacMatrixIntegrationTest`; vitest `purchase-planner` (fixtures updated, new form test).
The e2e test TC-B4-03 now injects the server refusal (a note-only edit is legal for any status).
Validation: `npx e2e run tests/for11/01-purchase-planner` — 0 failed, all former BUG tests unskipped;
full `tests/for10 tests/for11` run green (see FOR-11-00 report).
