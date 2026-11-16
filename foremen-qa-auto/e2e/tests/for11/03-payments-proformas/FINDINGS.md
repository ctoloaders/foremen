# FOR-11-03 (payments, proformas, external orders): E2E findings

## 1. Summary

**96 tests: 90 passed, 3 skipped as product bugs (BUG-1..3), 3 skipped because of an environment blocker (PDF storage). Not automated: see section 2.**

Final run (exit 0, 10 min):

```
export PATH=/Users/alexander.borohov/.nvm/versions/node/v24.18.0/bin:$PATH NPM_CONFIG_USERCONFIG=/Users/alexander.borohov/.kiro/settings/e2e.npmrc
cd foremen-qa-auto/e2e && npx e2e run tests/for11/03-payments-proformas --action-timeout 5000 --retries 0
=> Tests  90 passed | 6 skipped (96), EXIT=0
```

Files (all in `tests/for11/03-payments-proformas/`): `support.ts` (fixture, API helpers, SQL fixtures, teardown), `ui.support.ts`,
`proforma.e2e.ts` (A1-A4), `orders.e2e.ts` (A5-A7, A12-02), `ledger.e2e.ts` (A8-A12), `regression.e2e.ts`,
`ui-proforma.e2e.ts` (B1, B2, B5-03), `ui-orders.e2e.ts` (B3-B7).

Repeatability: every test builds its own run-id fixture (`withCtx`): project `QA03-<rid>` (ACTIVE, MANAGER and CLIENT members),
users `dash+<rid>-p03{m,c,b,f}@example.com`, seller `qa03_<rid>`, CASH/BANK accounts `qa03-<rid>-*`, ledger rows titled `qa-<rid> ...`,
STUB bank connection, procurement documents `qa03-<rid>/*`. Teardown (SQL, dependency order) runs in `finally`.
After the final run psql finds 0 leftovers for all of these prefixes. The company profile table was empty; tests insert a complete
profile (needed to issue a proforma) only when it is empty and delete it again in teardown.

Environment notes:
- `.e2e/results` is shared with the other agents' parallel runs, so per-test `trace.md` files were overwritten; failures were read from `/tmp/for11-03.log`.
- One run (exit 130) was interrupted by something outside this suite; the 4 orphaned users it left were removed by SQL (see section 4).

## 2. Coverage

| TC | Test (file: title) | Status |
|----|--------------------|--------|
| A1-01 | proforma: TC-A1-01 a draft is created... ; step 3 selection total | pass (step 3 compares `selection-total` with the proforma gross) |
| A1-02 | proforma: TC-A1-02 composition validations | pass (5 error keys) |
| A1-03 | proforma: TC-A1-03 a draft can be edited and deleted | pass |
| A2-01 | proforma: TC-A2-01 issue assigns a number... | **ENV-skipped** (POST /issue 502); `TC-A2-01 (partial)` passes: failed issue leaves draft and positions untouched |
| A2-02 | proforma: TC-A2-02 positions of an issued proforma are frozen | pass (issue emulated in SQL, see section 4) |
| A2-03 | proforma: TC-A2-03 numbers distinct and increasing | **ENV-skipped** |
| A2-04 | proforma: TC-A2-04 cancel unpaid issued | pass |
| A2-05 | proforma: TC-A2-05 cancel without reason | pass |
| A2-06 | proforma: TC-A2-06 company profile without bank account | pass |
| A3-01 | proforma: TC-A3-01 client sees own proforma w/o internal data | pass; `(PDF)` **ENV-skipped** |
| A3-02, A3-03, A3-04 | proforma: TC-A3-02 / 03 / 04 | pass |
| A4-01..A4-06, A4-08 | proforma: TC-A4-01 ... TC-A4-08 | pass (A4-06: status 400 or 409 accepted, see drift) |
| A4-07 | proforma: TC-A4-07 (partial) | partial: idempotency checked through repeated detection (no API to re-run release) |
| A5-01..A5-05 | orders: TC-A5-01 ... TC-A5-05 | pass |
| A5-06 | orders: TC-A5-06 | pass with SQL-inserted PARSED documents (no OCR upload) |
| A6-01..A6-07 | orders: TC-A6-01 ... TC-A6-07 | pass (A6-03 also checks documents/paymentStatus in `TC-A6-03 (b)`) |
| A7-01, A7-02, A7-04, A7-05, A7-06 | orders: TC-A7-* | pass |
| A7-03 | orders: TC-A7-03 | pass (PROCUREMENT link covering the attached document pays the order) |
| A8-01..A8-05 | ledger: TC-A8-01 ... TC-A8-05 | pass (BANK_FEED rows on a STUB connection; A8-02 order reverse matcher; reverse proforma matcher not testable, see section 4) |
| A8-06 | not automated | needs CLIENT_PAYMENT/payment-schedule data of FOR-06-07 (regression of other kinds) |
| A9-01..A9-03 | ledger: TC-A9-01 ... TC-A9-03 | pass |
| A10-01, A10-02 | ledger: TC-A10-01 / 02 | pass (full role matrix incl. FOREMAN; A10-02 covers proformas only, no order in a foreign project) |
| A11-01 | ledger: TC-A11-01 (partial) | partial: no duplicates in resources/grants (liquibase container not re-run) |
| A11-02 | ledger: TC-A11-02 | pass |
| A12-01 | ledger: TC-A12-01 | pass |
| A12-02 | orders: TC-A12-02 / (b) / (c) | pass (step 5 "cancelled proforma disappears" not automated) |
| A12-03 | ledger: TC-A12-03, (4), (5) | pass |
| B1-01 | ui-proforma: TC-B1-01, TC-B1-01 (issue) | partial: select, draft, issue dialog, failure toast; steps 4-5 (number, status "Wystawiona", planner stage) blocked by PDF store |
| B1-02, B1-03 | ui-proforma: TC-B1-02, TC-B1-03 | pass |
| B2-01 | ui-proforma: TC-B2-01, TC-B2-01 (copy) | pass; PDF button opening a document not tested (no PDF) |
| B2-02, B2-03 | ui-proforma | pass |
| B3-01 | ui-orders: TC-B3-01 | partial: create, ceiling ack, save, send; document attach in UI not done |
| B3-02 | ui-orders: TC-B3-02 | pass |
| B4-01 | ui-orders: TC-B4-01 | pass |
| B4-02 | ui-orders: TC-B4-02 (FINANCIER, MANAGER, BUYER bell; order deep link highlight) | pass; `(b)` manager proforma link **BUG-3** |
| B5-01, B5-02 | ui-orders | pass |
| B5-03 | ui-proforma: TC-B5-03 | pass |
| B5-04, B5-05, B5-06 | ui-orders | pass |
| B6-01 | ui-orders: TC-B6-01 | partial: no raw i18n keys on 4 screens in PL and RU (FINANCIER); no check of translated bell titles |
| B7-01 | ui-orders: TC-B7-01 (BUYER/MANAGER, fin, foreman, client) | pass; client **BUG-1** |
| B7-02 | ui-orders: TC-B7-02 | pass (menu "Dostawy" -> orders); only-orders menu **BUG-2** |
| R-04, R-05, R-07 | regression.e2e.ts | pass |
| R-06 | ledger TC-A9-01 | partial: `groupBy=REQUEST` and `NONE` unchanged |
| R-01, R-02, R-03, R-08, R-09, R-10 | not automated | belong to FOR-06-07 / FOR-07-04 / FOR-06-03 / notification specs; R-08 touched only through the bell tests |

## 3. Bugs

### BUG-1: a CLIENT can open the staff proforma list; it shows an error
- Screen: `/deliveries/proformas` (login as CLIENT, project member).
- Repro: log in as CLIENT, open `/deliveries/proformas`.
- Observed: URL stays `/deliveries/proformas`, page renders "Proformy / Wystąpił błąd. Spróbuj ponownie." because `GET /api/client-proformas` answers 403 for a CLIENT (`rejectClient`). `/deliveries/external-orders` correctly goes to `/403`.
- Expected (TC-B7-01, R22.2/R22.4): CLIENT and FOREMAN cannot reach `/deliveries/*`; the client sees proformas only on the project tab.
- Evidence: `ui-orders.e2e.ts` `TC-B7-01 (client)` (skipped, run unskipped: `expected URL /\/403/, observed /deliveries/proformas`); API `GET /api/client-proformas` as CLIENT -> 403 (TC-A3-03, passes).
- Layer: frontend (the backend is right).
- Suspected: `foremen-frontend/src/config/route-permissions.ts:173-174` gate `/deliveries/proformas[/:id]` only by `CLIENT_PROFORMAS READ`, a grant the CLIENT role holds on purpose (client endpoints). The route needs a role guard that excludes `CLIENT`.
- Requirement: R22.2, R22.4, TC-B7-01. Not feature-sized.

### BUG-2: the FINANCIER sees and can open "Proformy"
- Screen: left menu / `/deliveries/proformas` as FINANCIER.
- Observed: under "Dostawy" the menu lists "Proformy", "Zamówienia zewnętrzne", "Przesyłki"; `/deliveries/proformas` opens the staff list.
- Expected (R22.2): "FINANCIER SHALL see only the external-orders tab and the menu item `nav.deliveries`".
- Note: the requirements conflict: R1.2 seeds `FINANCIER READ` on `CLIENT_PROFORMAS` (seed 239), and the navigation is permission-driven. Decide whether to drop the grant or hide the item by role.
- Evidence: `ui-orders.e2e.ts` `TC-B7-02 the FINANCIER menu has only the orders entry` (skipped; run unskipped: `Proformy` link count 1, expected 0).
- Layer: frontend (`config/navigation.ts`, `route-permissions.ts`) or seed `239-seed-proforma-external-order-resources.xml` if the grant is wrong. Not feature-sized.

### BUG-3: the manager's "Proforma opłacona" notification does not lead to the proforma
- Endpoint/screen: notification `notification.proforma.paid`, deep link `/projects/{projectId}/clientProformas?proforma={id}` (`ProformaOrderNotificationEmitter.java:90`).
- Repro: pay a proforma via a ledger link; as MANAGER open the bell, click "Proforma opłacona".
- Observed: the project tab shows only a heading and a link "Otwórz listę proform" (`staff-proformas-link`); no proforma row/card is shown or highlighted.
- Expected (R22.3): the deep link "opens the right tab and scrolls to / highlights the proforma (`?proforma={id}`)". For CLIENT the cards do highlight; for staff the panel ignores the parameter.
- Evidence: `ui-orders.e2e.ts` `TC-B4-02 (b)` (skipped; unskipped: `proforma-row-<id>` / `proforma-card` not found).
- Layer: frontend. Suspected: `features/client-proformas/components/ClientProformasPanel.tsx` (staff branch ignores `?proforma`), or point the staff notification to `/deliveries/proformas?proforma={id}`, which `ProformaListTable` already highlights. Not feature-sized.

## 4. Blocked and notes

- **PDF storage is down in the local stack (environment, not a product bug).** `POST /api/client-proformas/{id}/issue` returns 502 `error.proforma.pdf.failed`: the backend log shows `StorageException ... oauth2.googleapis.com/token invalid_grant (invalid_rapt)`, an expired Google ADC token (`.secrets/adc.json`). No proforma can be issued through the API in this stack, so I could not run the end-to-end issue (A2-01, A2-03, client PDF, B1-01 steps 4-5) and did not restart containers. `issueProforma()` therefore falls back to emulating the issue in SQL (number `PF/<yyyy>/9xxxxx`, dates, `ISSUED`, positions `IN_PROFORMA`) so payment, release, order, matching and UI flows are still tested; it uses the real endpoint first and the three PDF-dependent tests re-enable themselves when the endpoint works.
- The reverse matcher for a proforma issued after the bank line (TC-A8-02 for proformas) hangs off the `ProformaIssuedEvent` of the real issue and cannot run for the same reason. The order side (`ExternalOrderSentEvent`) is tested and passes.
- Documentation drift (not bugs): TC-A6-02 names `error.externalOrder.send.incomplete`, the code has `send.noLines` / `send.noDocument`; `markPaid.dateInFuture` vs `paidDate.future`; `external-order-paid-source` badge test id exists on the order card but the financier queue uses `paid-source-badge`; the financier return dialog uses `return-order-reason` (the buyer banner uses `external-order-return-reason`); TC-A4-06 expects 409 for a debit row on a proforma but TC-A12-03 and the code answer 400 (`kindDirection`); TC-A8-01 expects a suggestion for number + amount, the system auto-links it (score above the auto threshold, `AUTO_LINKED`, by design of FOR-07-04); a ledger-paid order that is cancelled answers `error.externalOrder.status.notAllowed` rather than `cancel.hasPayments`; TC-B7-01 says FOREMAN cannot open `/deliveries/*`, but FOREMAN opens the planner `/deliveries` (FOR-11-01 behaviour; proformas and orders answer `/403`).
- The test-cases cannot check error keys directly: the API error body has no key, only the localized message, so `errMatches()` matches the message against `messages.properties`.
- Cleanup: the first exploration run (project 1623) and an interrupted run (exit 130, caused outside this suite) left a project and 4 users; they were removed by a one-off SQL script. Final psql check: 0 rows for `QA03-%` projects, `%-p03%@example.com` users, `qa03_%` sellers, `qa03-%` accounts/connections/documents, `qa-%` ledger rows; `company_profile` is empty as before.
