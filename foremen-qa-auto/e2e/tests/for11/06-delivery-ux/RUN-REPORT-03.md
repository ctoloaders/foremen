# FOR-11-03 (repointed to the FOR-11-06 unified API and screens): e2e run report

- Date: 2026-11-17, duration 8 min 31 s, EXIT=0.
- Command: `npx e2e run tests/for11/03-payments-proformas --action-timeout 5000 --retries 0` (from `foremen-qa-auto/e2e`, Node v24.18.0 via nvm).
- Environment: the same rebuilt stack as `RUN-REPORT.md`; backend and frontend include the BUG-06-1..3 fixes.
- Repeatability strategy: generator (`withCtx`: project `QA03-<rid>`, users `dash+<rid>-p03*@example.com`, seller `qa03_<rid>`) + SQL teardown in `finally`. psql after the run: 0 `QA03-%` projects, 0 `%-p03%` users.

## Summary

| Tests | Passed | Failed | Skipped |
|-------|--------|--------|---------|
| 96 | 92 | 0 | 4 |

Skips: A2-01, A2-03 and A3-01 (PDF) need a real issue, which fails with 502 while the PDF storage is down. A2-06 is skipped on purpose because it would change the shared company profile. The test-side changes for the unified API and screens are listed in `FINDINGS.md` section 3.

## Results per test

| # | File | Test case | Status | Skip reason |
|---|------|-----------|--------|-------------|
| 1 | `ledger` | TC-A8-01 a bank line with the proforma number and amount is matched to the proforma | ✅ pass |  |
| 2 | `ledger` | TC-A8-01 (b) a number-only match is a suggestion with reason PROFORMA_NUMBER and notifies the financier once | ✅ pass |  |
| 3 | `ledger` | TC-A8-02 an order sent after the bank line appears is matched by the reverse matcher | ✅ pass |  |
| 4 | `ledger` | TC-A8-03 proforma numbers without slashes are recognised | ✅ pass |  |
| 5 | `ledger` | TC-A8-04 a suggestion can be confirmed (link created) or rejected (suggestion gone) | ✅ pass |  |
| 6 | `ledger` | TC-A8-05 link targets are searchable by proforma number, order reference and amount | ✅ pass |  |
| 7 | `ledger` | TC-A9-01 the planner groups by proforma and by external order | ✅ pass |  |
| 8 | `ledger` | TC-A9-02 planner filters proformaId / externalOrderId and the new row fields | ✅ pass |  |
| 9 | `ledger` | TC-A9-03 noProforma / noExternalOrder filter on the server (rows, groups, slice counts) | ✅ pass |  |
| 10 | `ledger` | TC-A10-01 the role matrix of CLIENT_PROFORMAS and EXTERNAL_ORDERS | ✅ pass |  |
| 11 | `ledger` | TC-A10-02 a manager of another project cannot reach a proforma; buyer and financier can | ✅ pass |  |
| 12 | `ledger` | TC-A11-01 (partial) the seeded resources and grants have no duplicates | ✅ pass |  |
| 13 | `ledger` | TC-A11-02 CHECK constraints reject malformed ledger links | ✅ pass |  |
| 14 | `ledger` | TC-A12-01 the staff proforma card lists payments, the client card does not | ✅ pass |  |
| 15 | `ledger` | TC-A12-03 ledger links by HTTP: replace and remove effects on an order | ✅ pass |  |
| 16 | `ledger` | TC-A12-03 (4) ledger links by HTTP: replace on a proforma, released links are locked | ✅ pass |  |
| 17 | `ledger` | TC-A12-03 (5) invalid ledger link requests record nothing | ✅ pass |  |
| 18 | `orders` | TC-A5-01 a buyer creates a draft order and the delivery cost is split to exactly 50.00 | ✅ pass |  |
| 19 | `orders` | TC-A5-02 line quantities cannot exceed the uncovered position quantity | ✅ pass |  |
| 20 | `orders` | TC-A5-03 a purchase price above the sale price needs an explicit acknowledgement | ✅ pass |  |
| 21 | `orders` | TC-A5-04 unavailable positions cannot be ordered | ✅ pass |  |
| 22 | `orders` | TC-A5-05 lines of a sent order cannot be edited | ✅ pass |  |
| 23 | `orders` | TC-A6-01 sending moves fully covered positions to ORDERED and notifies the financier | ✅ pass |  |
| 24 | `orders` | TC-A6-02 an empty or reference-less order cannot be sent | ✅ pass |  |
| 25 | `orders` | TC-A6-03 the financier sees the queue without project membership but not positions | ✅ pass |  |
| 26 | `orders` | TC-A6-04 the financier returns an order to the buyer with a reason | ✅ pass |  |
| 27 | `orders` | TC-A6-05 financier and buyer actions are separated by role | ✅ pass |  |
| 28 | `orders` | TC-A6-06 the financier marks an order paid by hand | ✅ pass |  |
| 29 | `orders` | TC-A6-07 a hand-marked payment can be reverted | ✅ pass |  |
| 30 | `orders` | TC-A7-01 a bank payment marks the order paid with source LEDGER | ✅ pass |  |
| 31 | `orders` | TC-A7-02 the ledger overrides a hand-marked payment without a second notification | ✅ pass |  |
| 32 | `orders` | TC-A7-04 removing the bank link reverts a ledger-paid order | ✅ pass |  |
| 33 | `orders` | TC-A7-04 (b) removing a partial bank link keeps a hand-marked order PAID | ✅ pass |  |
| 34 | `orders` | TC-A7-05 orders with positions already in delivery cannot be cancelled or unlinked | ✅ pass |  |
| 35 | `orders` | TC-A7-05 (b) a sent order with positions in delivery cannot be cancelled | ✅ pass |  |
| 36 | `orders` | TC-A7-06 invalid link targets are refused | ✅ pass |  |
| 37 | `orders` | TC-A5-06 supplier documents attach to an order, over-attribution is refused, a foreign NIP is flagged | ✅ pass |  |
| 38 | `orders` | TC-A6-03 (b) the financier queue shows documents with their payment status | ✅ pass |  |
| 39 | `orders` | TC-A7-03 bank links that cover the attached document pay the order | ✅ pass |  |
| 40 | `orders` | TC-A12-02 an order shows the proformas its positions came from | ✅ pass |  |
| 41 | `orders` | TC-A12-02 (b) an order of a position without a proforma has no proformas | ✅ pass |  |
| 42 | `orders` | TC-A12-02 (c) positions from two proformas give two entries | ✅ pass |  |
| 43 | `proforma` | TC-A1-01 a draft is created with correct totals and leaves positions ACCEPTED | ✅ pass |  |
| 44 | `proforma` | TC-A1-01 step 3 selection total equals the proforma gross | ✅ pass |  |
| 45 | `proforma` | TC-A1-02 composition validations reject foreign, planned, package and unpriced positions | ✅ pass |  |
| 46 | `proforma` | TC-A1-03 a draft can be edited and deleted, positions stay ACCEPTED | ✅ pass |  |
| 47 | `proforma` | TC-A2-01 issue assigns a number, freezes positions and serves a PDF | ⏭ skip | ENV: PDF storage (GCS ADC token expired) -> POST /issue answers 502, real issue not testable |
| 48 | `proforma` | TC-A2-01 (partial) a failed issue leaves the draft and the positions untouched | ✅ pass |  |
| 49 | `proforma` | TC-A2-02 positions of an issued proforma are frozen | ✅ pass |  |
| 50 | `proforma` | TC-A2-03 proforma numbers are distinct and increasing | ⏭ skip | ENV: PDF storage (GCS ADC token expired) -> POST /issue answers 502, real issue not testable |
| 51 | `proforma` | TC-A2-04 an unpaid issued proforma can be cancelled and positions return to ACCEPTED | ✅ pass |  |
| 52 | `proforma` | TC-A2-05 cancelling an issued proforma without a reason is rejected | ✅ pass |  |
| 53 | `proforma` | TC-A2-06 a company profile without a bank account blocks the issue | ⏭ skip | company profile is not owned by this run; not touching shared data |
| 54 | `proforma` | TC-A3-01 a client sees the own issued proforma without internal data | ✅ pass |  |
| 55 | `proforma` | TC-A3-01 (PDF) a client downloads the proforma PDF | ⏭ skip | ENV: PDF storage (GCS ADC token expired) -> POST /issue answers 502, real issue not testable |
| 56 | `proforma` | TC-A3-02 a draft is invisible to the client | ✅ pass |  |
| 57 | `proforma` | TC-A3-03 client isolation: other project, staff endpoints and procurement endpoints | ✅ pass |  |
| 58 | `proforma` | TC-A3-04 purchase-price sentinels never reach the client surface | ✅ pass |  |
| 59 | `proforma` | TC-A4-01 a partial payment does not release positions | ✅ pass |  |
| 60 | `proforma` | TC-A4-02 full payment releases positions to the buyer and notifies | ✅ pass |  |
| 61 | `proforma` | TC-A4-03 a payment above the remaining amount is refused | ✅ pass |  |
| 62 | `proforma` | TC-A4-04 a link of a released proforma cannot be deleted | ✅ pass |  |
| 63 | `proforma` | TC-A4-05 deleting a link before release returns the proforma to ISSUED | ✅ pass |  |
| 64 | `proforma` | TC-A4-06 direction and currency are enforced | ✅ pass |  |
| 65 | `proforma` | TC-A4-07 (partial) repeated detection does not change statuses or notifications | ✅ pass |  |
| 66 | `proforma` | TC-A4-08 a proforma with payments cannot be cancelled | ✅ pass |  |
| 67 | `regression` | R-04 the FOR-11-00 gate: an AGREED position cannot jump to WITH_BUYER by hand | ✅ pass |  |
| 68 | `regression` | R-05 the manual PACKAGE branch works and CLIENT_OWN stays out of external orders | ✅ pass |  |
| 69 | `regression` | R-07 after IN_PROFORMA a position cannot enter a new approval round | ✅ pass |  |
| 70 | `ui-orders` | TC-B3-01 a buyer creates an order, the ceiling needs an acknowledgement, then sends it | ✅ pass |  |
| 71 | `ui-orders` | TC-B3-02 a returned order shows the reason and is editable again | ✅ pass |  |
| 72 | `ui-orders` | TC-B4-01 the financier returns an order with a reason, then marks the resent order paid | ✅ pass |  |
| 73 | `ui-orders` | TC-B4-02 the bell shows role-specific notifications and the order link highlights the order | ✅ pass |  |
| 74 | `ui-orders` | TC-B4-02 (b) the proforma-paid notification of a manager leads to the proforma | ✅ pass |  |
| 75 | `ui-orders` | TC-B5-01 a financier links a payment to a proforma from the ledger dialog | ✅ pass |  |
| 76 | `ui-orders` | TC-B5-02 a financier confirms a proforma suggestion and sees the reasons | ✅ pass |  |
| 77 | `ui-orders` | TC-B5-04 an order card links to its proformas | ✅ pass |  |
| 78 | `ui-orders` | TC-B5-04 (b) an order without a proforma shows no proforma block | ✅ pass |  |
| 79 | `ui-orders` | TC-B5-05 planner groups "Bez proformy" / "Bez zamówienia" request the server filter | ✅ pass |  |
| 80 | `ui-orders` | TC-B5-06 the ledger dialog sends clientProformaId / externalOrderId | ✅ pass |  |
| 81 | `ui-orders` | TC-B6-01 the proforma, order and queue screens have no raw i18n keys in PL and RU | ✅ pass |  |
| 82 | `ui-orders` | TC-B7-01 the BUYER and MANAGER open proformas, orders and the planner | ✅ pass |  |
| 83 | `ui-orders` | TC-B7-01 (fin) the FINANCIER sees only the order queue, not the planner | ✅ pass |  |
| 84 | `ui-orders` | TC-B7-01 (client) a CLIENT cannot open the staff proforma list | ✅ pass |  |
| 85 | `ui-orders` | TC-B7-01 (foreman) a FOREMAN cannot open proformas and orders | ✅ pass |  |
| 86 | `ui-orders` | TC-B7-02 the FINANCIER menu has only the orders entry under "Dostawy" | ✅ pass |  |
| 87 | `ui-orders` | TC-B7-02 the menu entry "Dostawy" of the FINANCIER leads to the order queue | ✅ pass |  |
| 88 | `ui-proforma` | TC-B1-01 a manager selects positions, creates a draft and reaches the issue dialog | ✅ pass |  |
| 89 | `ui-proforma` | TC-B1-01 (issue) confirming the issue shows the failure when the PDF store is down and keeps the draft | ✅ pass |  |
| 90 | `ui-proforma` | TC-B1-02 a mixed-project selection disables creating a proforma | ✅ pass |  |
| 91 | `ui-proforma` | TC-B1-03 an issued unpaid proforma is cancelled with a reason and positions return to ACCEPTED | ✅ pass |  |
| 92 | `ui-proforma` | TC-B2-01 a client sees the proforma card on a 390 px screen without horizontal scroll | ✅ pass |  |
| 93 | `ui-proforma` | TC-B2-01 (copy) copying the transfer title shows a confirmation | ✅ pass |  |
| 94 | `ui-proforma` | TC-B2-02 the client tab calls only client endpoints and shows no internal data | ✅ pass |  |
| 95 | `ui-proforma` | TC-B2-03 a project without proformas shows the empty state | ✅ pass |  |
| 96 | `ui-proforma` | TC-B5-03 the staff proforma card lists bank payments, an unpaid one shows the empty block, the client card none | ✅ pass |  |

Итог: 92 пройдено / 0 провалено / 4 пропущено. Стратегия повторяемости: генератор + teardown.
