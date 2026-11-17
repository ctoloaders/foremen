# FOR-11-06: e2e run report (API part A + UI part B)

- Date: 2026-11-17, start 14:46:49 (Europe/Warsaw), duration 6 min 8 s, EXIT=0.
- Command: `npx e2e run tests/for11/06-delivery-ux --action-timeout 5000 --retries 0` (from `foremen-qa-auto/e2e`, Node v24.18.0 via nvm).
- Environment: local Docker stack (`backend` :8080 profile `docker`, `frontend` :3000), both rebuilt from the working tree on top of root `e5c3b63` / frontend `32a28d5` (uncommitted FOR-11-06 changes included). Changesets 247-253 applied.
- Run-id: one generator id per test (`makeWorld()`: `for1106+<run-id>-<role>@example.com`, projects `FOR1106-<run-id>`, `FOR1106-OTHER-<run-id>`, seller `qa06_<run-id>`).
- Repeatability strategy: generator + teardown (`cleanup()` in `finally`). After the teardown fix (FINDINGS section 3), a re-run left 0 rows for every prefix.
- Per-step traces of each test: `foremen-qa-auto/e2e/.e2e/results/<test>/trace.md` (overwritten by the next run); aggregate: `.e2e/summary.md`.

## Summary

| Part | Tests | Passed | Failed | Skipped |
|------|-------|--------|--------|---------|
| A (API) | 80 | 72 | 0 | 8 |
| B (UI) | 32 | 27 | 0 | 5 |
| **Total** | **112** | **99** | **0** | **13** |

All skips have the same environment cause: the PDF storage is down (expired GCS ADC token), so `POST /api/client-payments/{id}/issue` answers 502 `error.proforma.pdf.failed` and no materials payment can be issued for real. Product bugs found during the runs and fixed: BUG-06-1, BUG-06-2, BUG-06-3 (see `FINDINGS.md`).

## Results per test

| # | File | Test case | Status | Skip reason |
|---|------|-----------|--------|-------------|
| 1 | `api-buyer` | TC-A8-01 the buyer sets the payment link on a DRAFT order | ✅ pass |  |
| 2 | `api-buyer` | TC-A8-02 only http(s) links are accepted; null clears | ✅ pass |  |
| 3 | `api-buyer` | TC-A8-03 the link is locked after payment and after cancellation | ✅ pass |  |
| 4 | `api-buyer` | TC-A8-04 changing the link of a sent order notifies the financiers; the same link again does not | ✅ pass |  |
| 5 | `api-buyer` | TC-A8-05 CLIENT, FOREMAN and WORKER cannot read or set the link | ✅ pass |  |
| 6 | `api-buyer` | TC-A9-01 buyer-added position: WITH_BUYER, added_by_buyer, client_unpaid, AGREED, line in the draft order | ✅ pass |  |
| 7 | `api-buyer` | TC-A9-02 the payment gate is intact and the endpoint is closed to other roles | ✅ pass |  |
| 8 | `api-buyer` | TC-A9-03 client and manager are notified, with no prices in the notification | ✅ pass |  |
| 9 | `api-buyer` | TC-A9-04 the tail of the cycle runs; the unpaid flag stays through to RECEIVED | ✅ pass |  |
| 10 | `api-buyer` | TC-A9-05 A received but unpaid position is not hidden by hideDelivered; a normal received one is | ✅ pass |  |
| 11 | `api-buyer` | TC-A9-10 the planner and the board rows of a buyer-added position carry clientUnpaid (feeds the "unpaid" badge) | ✅ pass |  |
| 12 | `api-buyer` | TC-A9-07 a proforma may include a WITH_BUYER..RECEIVED unpaid position; paying it clears the flag, cancelling does not | ⏭ skip | ENV: PDF storage unavailable (issue answers 502) |
| 13 | `api-buyer` | TC-A9-08 the client does not see the sale price of an unpaid buyer-added position before the proforma | ✅ pass |  |
| 14 | `api-buyer` | TC-A9-09 a buyer-added position can be cancelled before dispatch | ✅ pass |  |
| 15 | `api-buyer` | TC-A10-01 quick purchase: one DRAFT order per seller, planned prices | ✅ pass |  |
| 16 | `api-buyer` | TC-A10-02 positions of two projects answer 409 mixedProjects and create nothing | ✅ pass |  |
| 17 | `api-buyer` | TC-A10-03 no seller for a position answers 409 sellerRequired and rolls back | ✅ pass |  |
| 18 | `api-buyer` | TC-A10-04 a planned purchase price above the sale price needs acknowledgement | ✅ pass |  |
| 19 | `api-buyer` | TC-A10-05 only WITH_BUYER positions; CLIENT and FOREMAN get 403 | ✅ pass |  |
| 20 | `api-buyer` | TC-A10-06 after the financier pays, the buyer is sent to the shipment form with the order | ✅ pass |  |
| 21 | `api-client-payments` | TC-A1-01 one list returns both kinds with the common fields | ✅ pass |  |
| 22 | `api-client-payments` | TC-A1-02 pagination is shared by both kinds and ordered by due date, then id | ✅ pass |  |
| 23 | `api-client-payments` | TC-A1-03 filters narrow the list and the summary covers the whole filtered set | ✅ pass |  |
| 24 | `api-client-payments` | TC-A1-04 one ABAC resource: anonymous 401/403, WORKER 403, the old resources are gone from the matrix | ✅ pass |  |
| 25 | `api-client-payments` | TC-A1-05 visibility by kind: FOREMAN works only, BUYER materials only, FINANCIER cannot write materials | ✅ pass |  |
| 26 | `api-client-payments` | TC-A1-06 project scope: a manager outside the project gets 404 / an empty page, ADMIN sees all | ✅ pass |  |
| 27 | `api-client-payments` | TC-A1-07 display status: tranche PLANNED/OVERDUE, proforma DRAFT then CANCELLED | ✅ pass |  |
| 28 | `api-client-payments` | TC-A2-01 and A2-02 draft then issue: number PF/<year>/<n>, ISSUED, positions IN_PROFORMA | ⏭ skip | ENV: PDF storage unavailable (issue answers 502) - the issued state is not reachable in this stack |
| 29 | `api-client-payments` | TC-A2-03 the PDF is application/pdf with the Polish labels and the payee account | ⏭ skip | ENV: PDF storage unavailable (issue answers 502) - the issued state is not reachable in this stack |
| 30 | `api-client-payments` | TC-A2-04 the payee account is a snapshot taken at issue | ⏭ skip | ENV: PDF storage unavailable (issue answers 502) - the issued state is not reachable in this stack |
| 31 | `api-client-payments` | TC-A2-05 a statement row linked by clientPaymentId pays the proforma and releases the positions | ⏭ skip | ENV: PDF storage unavailable (issue answers 502) - the issued state is not reachable in this stack |
| 32 | `api-client-payments` | TC-A2-06 a link by the id of a works payment closes the nearest tranche first (FIFO) | ✅ pass |  |
| 33 | `api-client-payments` | TC-A2-07 the old link fields (clientProformaId) still work | ⏭ skip | ENV: PDF storage unavailable (issue answers 502) - the issued state is not reachable in this stack |
| 34 | `api-client-payments` | TC-A2-08 cancel: an unpaid payment returns the positions to ACCEPTED, a paid one answers 409 | ⏭ skip | ENV: PDF storage unavailable (issue answers 502) - the issued state is not reachable in this stack |
| 35 | `api-client-payments` | TC-A2-09 a non-manager cannot issue (403 kind.forbidden) | ✅ pass |  |
| 36 | `api-client-payments` | TC-A3-01 works lifecycle through the new paths: schedule, tranches, publish, listed as WORKS | ✅ pass |  |
| 37 | `api-client-payments` | TC-A3-02 manual mark-paid by the financier is idempotent | ✅ pass |  |
| 38 | `api-client-payments` | TC-A3-03 overdue tranche is flagged and the overdue notification points at the payments tab | ✅ pass |  |
| 39 | `api-client-payments` | TC-A3-04 the old paths are gone | ✅ pass |  |
| 40 | `api-client-payments` | TC-A4-01 the project account is stored without spaces, upper case | ✅ pass |  |
| 41 | `api-client-payments` | TC-A4-02 validation: bad checksum, letter, too short -> 400; null clears | ✅ pass |  |
| 42 | `api-client-payments` | TC-A4-03 rights: only a project MANAGER or ADMIN; the generic project update ignores the field | ✅ pass |  |
| 43 | `api-client-payments` | TC-A4-04 effective account: project account, else the company account | ✅ pass |  |
| 44 | `api-client-payments` | TC-A4-05 visibility: CLIENT sees payeeBankAccount, FOREMAN and BUYER get no account field | ✅ pass |  |
| 45 | `api-client-payments` | TC-A4-06 the change is audited with a masked value | ✅ pass |  |
| 46 | `api-client-payments` | cleanup sanity: deleting a draft payment removes it from the list | ✅ pass |  |
| 47 | `api-delegation` | TC-A11-01 the project manager delegates a shipment to a worker | ✅ pass |  |
| 48 | `api-delegation` | TC-A11-02 invalid assignees: other project, inactive member, FOREMAN, CLIENT, BUYER | ✅ pass |  |
| 49 | `api-delegation` | TC-A11-03 who may delegate: ADMIN and a member manager; an outside manager and FOREMAN not | ✅ pass |  |
| 50 | `api-delegation` | TC-A11-04 one active delegation per shipment: re-delegating revokes the previous one | ✅ pass |  |
| 51 | `api-delegation` | TC-A11-05 a worker sees only the delegated shipments and their receipts | ✅ pass |  |
| 52 | `api-delegation` | TC-A11-06 and A11-07 the worker records a receipt on the delegated shipment only; a retry adds nothing | ✅ pass |  |
| 53 | `api-delegation` | TC-A11-08 closing the shipment completes the delegation and it leaves the worker list | ✅ pass |  |
| 54 | `api-delegation` | TC-A11-09 revoking hides the shipment from the worker and keeps the receipts already made | ✅ pass |  |
| 55 | `api-delegation` | TC-A11-10 existing rights are unchanged: FOREMAN and MANAGER receive, BUYER reads, FINANCIER and CLIENT 403 | ✅ pass |  |
| 56 | `api-delegation` | TC-A11-11 the history names the receiver and the delegator | ✅ pass |  |
| 57 | `api-delegation` | TC-A11-12 a cancelled or fully received shipment cannot be delegated (409 shipmentClosed) | ✅ pass |  |
| 58 | `api-delegation` | TC-A12-01 receipt.delegated reaches the worker with the deep link; the initiator gets nothing | ✅ pass |  |
| 59 | `api-delegation` | TC-A12-02 receipt.delegationRevoked reaches the previous worker without a link, on revoke and on re-assignment | ✅ pass |  |
| 60 | `api-delegation` | TC-A12-03 the first receipt of a delegate notifies the delegator; the second one does not | ✅ pass |  |
| 61 | `api-delegation` | TC-A12-04 the proforma-paid notification links to the unified payments tab | ⏭ skip | ENV: PDF storage unavailable (issue answers 502) |
| 62 | `api-estimate-approval` | TC-A5-01 and A5-02 sync answers a summary and is idempotent | ✅ pass |  |
| 63 | `api-estimate-approval` | TC-A5-03 a position without a material cannot be sent for approval (409 materialRequired) | ✅ pass |  |
| 64 | `api-estimate-approval` | TC-A5-04 the material can be chosen while PLANNED | ✅ pass |  |
| 65 | `api-estimate-approval` | TC-A5-07 sync of a project that is not ACTIVE answers 409 notActive | ✅ pass |  |
| 66 | `api-estimate-approval` | TC-A5-09 DB guards: a non-PLANNED position without material and a duplicate source key are rejected | ✅ pass |  |
| 67 | `api-estimate-approval` | TC-A5-10 rights: FOREMAN and CLIENT 403, BUYER and MANAGER 200 | ✅ pass |  |
| 68 | `api-estimate-approval` | TC-A5-08 activating a DRAFT project does not fail because of the estimate sync | ✅ pass |  |
| 69 | `api-estimate-approval` | TC-A6-01 the materials matrix has no finishing branch | ✅ pass |  |
| 70 | `api-estimate-approval` | TC-A6-03 the matrix keeps the PURCHASES READ gate | ✅ pass |  |
| 71 | `api-estimate-approval` | TC-A7-01 the staff view carries thumbnailUrl (CDN URL or null) and the estimate flags | ✅ pass |  |
| 72 | `api-estimate-approval` | TC-A7-02 selection total: count, unpriced, sums | ✅ pass |  |
| 73 | `api-estimate-approval` | TC-A7-03 bulk accept on behalf: partial success is reported per position | ✅ pass |  |
| 74 | `api-estimate-approval` | TC-A7-04 bulk withdraw closes the rounds; a position without an open round is a failure entry | ✅ pass |  |
| 75 | `api-estimate-approval` | TC-A7-05 more than 200 items answer 400 tooMany | ✅ pass |  |
| 76 | `api-estimate-approval` | TC-A7-06 bulk endpoints are staff only | ✅ pass |  |
| 77 | `api-estimate-approval` | TC-A7-07 mass send and a proforma from several positions go in one request each | ✅ pass |  |
| 78 | `api-estimate-sync` | TC-A5-01 three BoM rows give three PLANNED / AGREED / FINISHING positions: two with a material, one without; a second sync changes nothing | ✅ pass |  |
| 79 | `api-estimate-sync` | TC-A5-05 re-sync rules: PLANNED follows the estimate, PROPOSED keeps its quantity and is flagged, a removed row is flagged and kept | ✅ pass |  |
| 80 | `api-estimate-sync` | TC-A5-06 the position takes the work category and the need-by date from the work schedule | ✅ pass |  |
| 81 | `ui-buyer` | TC-B5-01 payment link: the buyer saves it, a non-http(s) value is blocked, the financier pays through the link | ✅ pass |  |
| 82 | `ui-buyer` | TC-B5-01 step 3 the field is hidden from roles outside the money audience | ✅ pass |  |
| 83 | `ui-buyer` | TC-B5-02 "Dodaj pozycję" on the board creates a buyer-added card and notifies the client | ✅ pass |  |
| 84 | `ui-buyer` | TC-B5-03 a delivered unpaid card stays visible under "Ukryj dostarczone" with the "Dostarczone, nieopłacone" badge | ✅ pass |  |
| 85 | `ui-buyer` | TC-B5-04 quick purchase: three board cards, "Utwórz zakup" creates draft orders; a second project blocks the selection | ✅ pass |  |
| 86 | `ui-buyer` | TC-B5-05 after the financier pays, the buyer notification opens the shipment form with the order preselected | ✅ pass |  |
| 87 | `ui-buyer` | TC-B5-06 the order and shipment forms list all projects, and the team of a project is not shown to the buyer | ✅ pass |  |
| 88 | `ui-delegation` | TC-B6-01 MANAGER delegates a shipment to a project worker and the chip appears in the shipments table | ✅ pass |  |
| 89 | `ui-delegation` | TC-B6-02 WORKER at 390 px sees "Odbiór dostaw" with only the delegated shipment; without delegations an empty state | ✅ pass |  |
| 90 | `ui-delegation` | TC-B6-03 WORKER accepts part of the shipment and the manager history reads "Przyjął {name} (przekazał {name})" | ✅ pass |  |
| 91 | `ui-delegation` | TC-B6-04 revoking the delegation removes the shipment from the worker and the notification carries no link | ✅ pass |  |
| 92 | `ui-estimate` | TC-B4-01 "Odśwież z kosztorysu" creates the positions: badge, thumbnail, a row without material has no usable checkbox | ✅ pass |  |
| 93 | `ui-estimate` | TC-B4-02 "Wybierz materiał" on a row without a material: pick from the project materials, the row gets the model and the thumbnail | ✅ pass |  |
| 94 | `ui-estimate` | TC-B4-07 CLIENT sees the material picture on the position card and no selection controls or sums | ✅ pass |  |
| 95 | `ui-estimate` | TC-B4-08 the materials matrix tab has no finishing branch: no branch tabs, the finishing material is not listed | ✅ pass |  |
| 96 | `ui-payments` | TC-B1-01 MANAGER sees works tranches and the materials payment in one table with kind chips and columns | ✅ pass |  |
| 97 | `ui-payments` | TC-B1-02 the kind filter narrows the rows and the totals follow it | ✅ pass |  |
| 98 | `ui-payments` | TC-B1-02 step 2 the "overdue only" checkbox shows exactly the overdue rows of the API | ✅ pass |  |
| 99 | `ui-payments` | TC-B1-03 clicking a row opens the card with the lines, the "pay to" block and the PDF button | ⏭ skip | the PDF storage is down: the materials payment stays a draft (no PDF, no lines snapshot) |
| 100 | `ui-payments` | TC-B1-04 the "Proformy" tab is gone and the legacy URL lands on "Płatności" with its query | ✅ pass |  |
| 101 | `ui-payments` | TC-B1-05 an effective account decides between the "Pay to" block and the missing-account warning | ✅ pass |  |
| 102 | `ui-payments` | TC-B2-01 CLIENT sees "Płatności" cards of issued documents, no "Proformy" tab | ⏭ skip | the PDF storage is down: no issued materials payment for the client |
| 103 | `ui-payments` | TC-B2-02 the card shows the payee account and the transfer title with copy buttons; materials add PDF | ⏭ skip | the PDF storage is down: no issued materials payment for the client |
| 104 | `ui-payments` | TC-B2-03 the client page and its API answers carry no purchase prices, seller names or position ids; a foreign project is refused | ⏭ skip | the PDF storage is down: no issued materials payment for the client |
| 105 | `ui-payments` | TC-B2-04 ?payment={id} highlights the card of that payment | ⏭ skip | the PDF storage is down: no issued materials payment for the client |
| 106 | `ui-payments` | TC-B2-01 step 3 a worker-free project member list: FOREMAN sees the staff table, not client cards | ✅ pass |  |
| 107 | `ui-staff` | TC-B3-01 MANAGER edits the IBAN: invalid value shows an error, a valid one is saved and masked | ✅ pass |  |
| 108 | `ui-staff` | TC-B3-02 FOREMAN and a MANAGER of another project do not get an editable field | ✅ pass |  |
| 109 | `ui-staff` | TC-B4-03 one status per selection: other statuses are disabled, the bar shows count and gross sum | ✅ pass |  |
| 110 | `ui-staff` | TC-B4-04 the action follows the status: PLANNED sends, ACCEPTED creates a proforma | ✅ pass |  |
| 111 | `ui-staff` | TC-B4-05 bulk "mark accepted" with one stale price reports partial success and keeps the failed row selected | ✅ pass |  |
| 112 | `ui-staff` | TC-B4-06 at 390 px the bar is fixed to the bottom and the actions stay reachable | ✅ pass |  |

Итог: 99 пройдено / 0 провалено / 13 пропущено (среда: PDF-хранилище). Стратегия повторяемости: генератор + teardown.
