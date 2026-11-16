# FOR-11-06 delivery UX refinements: API e2e slice

Spec: `.kiro/specs/FOR-11-Delivery-Management/FOR-11-06-delivery-ux-refinements/` (test-cases.md part A).
Files: `support.ts`, `api-client-payments.e2e.ts` (A1-A4), `api-estimate-approval.e2e.ts` (A5-A7), `api-buyer.e2e.ts` (A8-A10), `api-delegation.e2e.ts` (A11-A12).
Run: `npx e2e run tests/for11/06-delivery-ux --action-timeout 5000 --retries 0` (Node 24.8+, Docker stack up).

## Status: written, NOT run

The slice has not been executed. Two blockers at authoring time:
- The running backend container (started before the FOR-11-06 code) has none of the new endpoints (`/api/client-payments` answers 401) and the database lacks the changesets 247-252 (`client_payments`, `goods_receipt_delegations`). The stack must be rebuilt first: `docker compose up -d --build backend liquibase`.
- The local Node is 23.3; e2e needs 24.8+. Files were only syntax-checked.

Expect a first-run fix pass against the real responses. Unverified assumptions are listed below.

## Repeatability
`makeWorld()` creates users `for1106+<run-id>-<role>@example.com`, projects `FOR1106-<run-id>` and `FOR1106-OTHER-<run-id>`, a seller `qa06_<run-id>`, CASH accounts `qa06-<run-id>-acc`. `world.cleanup()` runs in `finally` and removes receipts, delegations, bank rows, orders, proformas, positions, projects (cascades client payments and schedules), notifications and users. A company profile is created only when the table is empty and removed again.

## Where the slice differs from test-cases.md (the code is the source of truth)
- Unified list field is `status` (display status), not `displayStatus`; client DTO has `payeeBankAccount`, staff DTO `effectiveBankAccount`.
- Error bodies carry only the localized message, so keys are checked through `messages.properties` (`errMatches`).
- Position DTO has no `addedByBuyer`/`clientUnpaid`; both are read from the DB columns.
- Quick purchase takes `sellerId` / `sellerByPosition` / `acknowledgedPositionIds`; above-ceiling key is `error.externalOrder.line.aboveCeiling`.
- Payment link longer than 1000 characters fails bean validation (400, generic message), not `paymentLink.invalid`.
- A8-05 expects 403 for CLIENT/FOREMAN/WORKER on the order (money audience), not only a hidden field.

## Environment-dependent cases (use `test.skip` at run time)
- PDF storage down (issue answers 502): A2-03..A2-08, A9-07, A12-04.
- Schedule cannot be published without an estimate basis: A2-06, A3-02, A3-03, A4-05.

## Not automated through REST
- A5-01/A5-05/A5-06 with real estimate rows: the finishing BoM cannot be built over REST. The sync endpoint is tested on the empty estimate; estimate-derived positions are inserted by SQL (A5-03/04/09). A5-08 only checks that activation is not refused by the sync.
- A1-08 (migration data preservation, needs before/after SQL), A6-02, A3-03 notification link (nightly sweep), A4-06 only checks masking.
- A9-03 CLIENT link and A10-03 rely on notification/deep-link formats from the design.

## Unverified assumptions to check on the first run
- `POST /api/client-payments/schedules/{id}/tranches` accepts a MANUAL schedule created without a basis.
- `GET /api/goods-receipts` rows expose `receivedById`, `receivedByName`, `delegatedByName`, `positionId`.
- `to-receive` for WORKER returns an array; `POST /api/shipments/{id}/cancel` exists.
- `GET /api/projects/{id}/materials-matrix` JSON has no `finishing` anywhere.
- Project `bank_account` column name is `bank_account` (A4-03).

## UI slice (test-cases.md part B): written, NOT run

Files: `ui-support.ts` (login, working project, tab helpers), `ui-payments.e2e.ts` (B1, B2), `ui-staff.e2e.ts` (B3 project IBAN, B4-03..06 selection bar), `ui-buyer.e2e.ts` (B5), `ui-delegation.e2e.ts` (B6). They reuse `makeWorld()` and `cleanup()` from `support.ts`. Results come from the e2e `markdown` reporter (tables, no screenshots).
Run: `npx e2e run tests/for11/06-delivery-ux/ui-*.e2e.ts --action-timeout 5000 --retries 0` (Node 24.8+, rebuilt stack).
Checked only with `tsc --noEmit --strict` (exit 0). Not executed: Node is 23.3 and the stack predates FOR-11-06 (see above).

Not automated in the UI: B4-01/02/07/08 (estimate-derived rows, material replacement, client card, matrix; they need an estimate basis that REST cannot build, same limit as A5).

Differences from the case wording (the PL locale is the source): delegate button is "Przekaż odbiór" (not "Deleguj odbiór"), chip "Przekazano: {name}", empty state "Nie przekazano Ci żadnych dostaw", quick-purchase button "Utwórz zakup", mark-accepted is "Oznacz jako zaakceptowane".

Unverified selectors and assumptions for the first run:
- Project edit sheet is opened by the button "Edytuj projekt" in the workspace header; the IBAN field is found by its label "Rachunek bankowy (IBAN)".
- B5-02 fills the dialog by labels "Ilość" and "Cena zakupu" and the first combobox (material); adjust to the real dialog.
- B5-01 expects one `financier-pay-online` button on the queue page; leftovers of other runs with links would make it ambiguous.
- B5-05 opens `/deliveries/shipments?new=1&order={id}` and expects a button "Utwórz przesyłkę".
- B6-03 opens `/deliveries/receipt?position={id}` and the "Historia" tab to read "Przyjął … (przekazał …)".
- Notification type `notification.position.addedByBuyer` and `notification.receipt.delegationRevoked` come from the API slice.
