# FOR-11-06 delivery UX refinements: e2e slice findings

Spec: `.kiro/specs/FOR-11-Delivery-Management/FOR-11-06-delivery-ux-refinements/` (test-cases.md parts A and B).
Run reports with tables: [`RUN-REPORT.md`](RUN-REPORT.md) (06 slice) and [`RUN-REPORT-03.md`](RUN-REPORT-03.md) (repointed FOR-11-03 slice).

## 1. Status (2026-11-17, local Docker stack)

| Slice | Files | Result |
|-------|-------|--------|
| FOR-11-06 API (part A) | `api-client-payments`, `api-estimate-approval`, `api-estimate-sync`, `api-buyer`, `api-delegation` | 72 passed, 8 skipped (environment) |
| FOR-11-06 UI (part B) | `ui-payments`, `ui-staff`, `ui-estimate`, `ui-buyer`, `ui-delegation` | 27 passed, 5 skipped (environment) |
| FOR-11-03 repointed | `tests/for11/03-payments-proformas/*` | 92 passed, 4 skipped (3 environment, 1 shared data) |

The final full run of the 06 folder took 6 min 8 s: `Tests 99 passed | 13 skipped (112)`, EXIT=0.

```
export PATH=$HOME/.nvm/versions/node/v24.18.0/bin:$PATH
cd foremen-qa-auto/e2e
npx e2e run tests/for11/06-delivery-ux --action-timeout 5000 --retries 0
npx e2e run tests/for11/03-payments-proformas --action-timeout 5000 --retries 0
```

The system Node (Homebrew) is 23.3, and e2e needs 24.8+. The user-local nvm already had Node v24.18.0, so it was put first in
`PATH` for these runs. Nothing global was changed.

Stack: the backend was rebuilt with `docker compose up -d --build backend` three times, once after each backend fix below. The
frontend was rebuilt twice: once at the start (its image predated FOR-11-06) and once after the frontend fix. Changesets
247-253 are applied (`databasechangelog` checked); `http://localhost:8080` answers 401 to an unauthenticated call.

## 2. Product bugs found and fixed (minimal fixes, each with a test)

### BUG-06-1: FOREMAN and BUYER received the payee bank account (R4.4)
- Case: TC-A4-05. `GET /api/client-payments` as FOREMAN returned `effectiveBankAccount` = the project IBAN on every WORKS row.
  For BUYER, `proforma.bankAccountSnapshot` was returned on MATERIALS rows.
- R4.4: the effective account "SHALL **not** be exposed to FOREMAN, BUYER".
- Fix: `foremen-backend/src/main/java/com/foremen/service/clientpayment/ClientPaymentAssembler.java` (`build`) nulls
  `effectiveBankAccount` and `bankAccountSnapshot` for the `WORKS_ONLY` / `MATERIALS_ONLY` visibilities (FOREMAN / BUYER).
- Tests: `ClientPaymentAssemblerTest.foremanAndBuyerDoNotGetTheBankAccount` (new). The existing
  `staffMaterialsDtoExposesSnapshotLinesAndOverdueDisplay` now builds with `ALL` (staff that may see the account) instead of
  BUYER's `MATERIALS_ONLY`. `ClientPaymentAssemblerTest` and `ClientPaymentServiceTest` are green. e2e TC-A4-05 is green.

### BUG-06-2: the "unpaid" badge never showed on the buyer board or in the planner (R10.4)
- Cases: TC-B5-02, TC-B5-03. A buyer-added position (`client_unpaid = true`) showed no "Nieopłacone przez klienta" /
  "Dostarczone, nieopłacone" badge on the board.
- Cause: `BoardCard.tsx` and `PlannerRowCells.tsx` render the badge from `clientUnpaid`, but neither
  `BoardService.Card` nor `PlannerRowDto` carried the field.
- Fix: `foremen-backend/src/main/java/com/foremen/service/procurement/planner/BoardService.java` (`Card.clientUnpaid`) and
  `PlannerRowDto.java` / `PlannerQueryService.java` (`clientUnpaid`). Both expose the flag to the staff audience only
  (`STAFF_FULL`) and give `false` to every other audience.
- Test: new e2e `api-buyer.e2e.ts` TC-A9-10 (planner row and board card carry `clientUnpaid`); B5-02 and B5-03 are green.

### BUG-06-3: "Utwórz proformę" in the planner opened the wrong document (R2.6, R18.3)
- Case: FOR-11-03 TC-B1-01. After the draft was created, the planner navigated to `/deliveries/proformas/{id}` with the
  **client-payment** id returned by `POST /api/client-payments/materials` (re-keyed by `ClientPaymentService.withPaymentId`).
  That route expects the **proforma** id, so the editor did not open, or another document would open if the ids collided.
- Fix (frontend): `src/features/client-payments/lib/proformaRoutes.ts` adds `proformaPaymentPath()` / `parsePaymentParam()`.
  `PlannerTab.tsx` navigates to `/deliveries/proformas?payment={paymentId}`, and `ProformaListPage.tsx` opens `?payment=` in the
  document sheet.
- Tests: `src/features/client-payments/__tests__/proformaRoutes.test.ts` (new). The vitest run over that file and
  `src/features/purchase-planner` passed (17 files, 104 tests); `tsc --noEmit` exit 0. e2e TC-B1-01 (03 slice) is green.

## 3. Test-side corrections made on the first runs (the code is the source of truth)

- `ui-buyer.e2e.ts`: `POST /api/external-orders` answers 200, not 201. A buyer-added position has no purchase request, so the BUYER
  board default filter "Moje" hides it, and the tests switch to "Wszyscy" (`boardAllBuyers`). The add dialog does not preselect the
  working project, so the project and the material are chosen by their comboboxes, and the price field is "Planowana cena zakupu". The quick-purchase
  button is `bulk-action-create-purchase` ("Szybki zakup"). Catalog materials have no seller, so the dialog asks for one; the
  test picks the run's seller through the search box. The shipment form needs carrier and tracking number.
- `ui-staff.e2e.ts`: the project edit form also validates the area (API-made projects have none), so the test fills "Powierzchnia". SEND opens
  `send-block-dialog` (confirm `send-confirm`), not the bulk confirm dialog. The bulk mark-accepted dialog needs the comment.
- `ui-delegation.e2e.ts`: the chip test id is `delegated-chip-{shipmentId}`.
- `api-estimate-sync.e2e.ts` / `ui-estimate.e2e.ts` (new): one room per BoM row (the matrix keeps one cell per line and room).
  The placeholder type must be one the work item consumes, because its norm unit comes from the consumption row.
- `support.ts` teardown: `tranche_allocations` (manual mark-paid) and the SQL-seeded `rooms` blocked `DELETE /api/projects`
  (500, FK). Before this fix, 29 projects of this session were left behind. They were removed by SQL (prefix `FOR1106-`), and the
  teardown now deletes both tables. After a re-run, psql finds 0 rows for `FOR1106-%` projects, `for1106+%` users, `qa06_%`
  sellers, `QA06-%` rooms and materials.
- FOR-11-03 slice (`tests/for11/03-payments-proformas`): `POST /api/client-payments/materials` answers the proforma-shaped DTO
  whose `id` is the client-payment id. `draftProforma()` now returns the proforma id (`paymentId` keeps the other one), and
  `issueProforma()` returns the flattened `getPf()` view (`number` lives under `proforma` in the unified DTO). The unified view
  has no `paidAt`, so TC-A4-02 checks `remainingGross = 0`. The UI cases were moved to the unified screens: the client cards
  on `/projects/{p}/clientPayments`, the staff drawer `?payment=`, the read-only sheet with `proforma-cancel`, the shipment form as the
  target of the paid-order notification (R11.6), and `?payment=` as the target of the proforma-paid notification. BUG-3 of the
  FOR-11-03 FINDINGS is fixed by FOR-11-06.

## 4. Estimate-sync cases added (SQL-seeded BoM, teardown via `cleanup()`)

`support.ts`: `consumptionPick()` picks a work item that consumes two finishing types: the first has two catalog materials, the
second becomes the placeholder. `seedFinishingBom(..., { workItemId })` creates one room per row. `seedWorkStart()` sets the
project start date and a schedule bar. `seedPhotoMaterial()` copies a catalog material into a project material with a photo key,
because no catalog material has a photo.

| Case | File | What is checked |
|------|------|-----------------|
| TC-A5-01 (+A5-02) | `api-estimate-sync.e2e.ts` | 3 BoM rows give `created=3`: 2 with a material, 1 without; all `PLANNED`, `FINISHING/AGREED`. A second sync gives `created=0, unchanged=3` |
| TC-A5-05 | `api-estimate-sync.e2e.ts` | Norm x2: PLANNED quantity doubles; PROPOSED keeps its quantity with `estimate_changed`; a deleted BoM row gives `estimate_removed` and the position is kept |
| TC-A5-06 | `api-estimate-sync.e2e.ts` | `need_by_date` = start of the bar; planner row `effectiveWorkCategoryId` and `workStartDate` |
| TC-B4-01 | `ui-estimate.e2e.ts` | "Odśwież z kosztorysu" creates 3 positions; "Z kosztorysu" badge, thumbnail, a row without material has "Wybierz materiał" and a disabled checkbox |
| TC-B4-02 | `ui-estimate.e2e.ts` | "Wybierz materiał" picks the project material; the row shows the model and the thumbnail |
| TC-B4-07 | `ui-estimate.e2e.ts` | CLIENT card has the material `<img>` (src checked in the DOM); no checkbox, selection bar, totals or purchase block |
| TC-B4-08 | `ui-estimate.e2e.ts` | Materials tab: no finishing branch, the finishing material is not listed; the API JSON has no `finishing` |

Note on B4-07: the image host of the dev bucket (`storage.googleapis.com`) is not in the frontend CSP `img-src`, so the
browser blocks the load. The card renders the `<img>`, and the test checks its `src`. This is a local configuration matter
(the CSP lists the `cdn.loaders.dev` hosts), not a FOR-11-06 defect.

## 5. Skipped (environment) and not automated

- PDF storage is down: the GCS ADC token is expired (`invalid_grant / invalid_rapt` in the backend log), so `POST .../issue`
  answers 502 `error.proforma.pdf.failed`. Skipped: A2-01..A2-05, A2-07, A2-08, A9-07, A12-04, B1-03, B2-01..B2-04 (06), and
  A2-01, A2-03, A3-01 (PDF) (03). FOR-11-03 A2-06 is skipped on purpose (it would change the shared company profile).
- Still not automated: A1-08 (migration before/after SQL), A6-02, the A3-03 nightly overdue sweep notification, and A4-06 beyond
  the masking check.
