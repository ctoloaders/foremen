# Implementation Plan: FOR-11-03 — Payments, proformas and external orders

## Overview

Delivers the `ClientProforma` and `ExternalOrder` aggregates, the proforma-paid gate, the financier
hand-off and the FOR-07-04 ledger extension. Reuses (not re-implemented): `PositionLifecycleService` and
`PositionTransitionPolicy` (FOR-11-00), `PositionMoneyMath`, `DeliveryCostAllocator`, `SelectionTotalService`
(FOR-11-01), `PdfRenderer` and the object-storage seam of `PdfFreezeService` (FOR-05-08), `ProcurementDocument`
and `DocumentPaymentStatusDeriver` (FOR-06-03/04, FOR-07-04), `BankLinkService` / `LinkEffects` /
`DetectionService` / `ReverseDocumentMatcher` (FOR-07-04), `NotificationService`, `SellerPicker`. The works
`PaymentSchedule` and `ReconciliationEngine` are **not** touched.

**Spans two git repos.** Backend, specs and infra are committed from the workspace root repo; all frontend
code under `foremen-frontend/` is committed from inside that nested repo (the root repo ignores it). Commit
only when the user asks; push only to a new branch; never force-push.

**Migrations.** Changesets 236 to 239 (next free number after 235; if a sibling spec lands first use the
next free numbers), each idempotent and registered last in `database_files/changelog.xml`.

**Test-run policy** (workspace rule): run only the affected classes with `--tests`, redirect to
`/tmp/for-11-03-test.log`, and read `foremen-backend/build/test-results/test/TEST-<fqcn>.xml`. Never run the
full suite unless asked (about 20 minutes). Use `compileJava` / `compileTestJava` and diagnostics for fast
checks.

**Shared-code changes** (all additive, existing tests re-run): `PositionTransitionPolicy`, `LinkKind`,
`LinkRequest`, `LinkEffects`, `BankLinkService`, `DetectionCandidateProviders`, `RuleMatcher`, `TargetType`,
`PlannerGroupService`, `PlannerFilter`, `ProcurementProjectAccess`.

## Tasks

- [x] 1. Database
  - [x] 1.1 Create `236-create-client-proformas.xml`
    - Tables `client_proformas`, `client_proforma_lines`, sequence `client_proforma_number_seq`, indexes,
      partial unique indexes (number; active line per position), CHECKs. Idempotent (`tableExists` /
      `sequenceExists` guards). Register last in `changelog.xml`.
    - _Requirements: 2.1, 2.2, 2.4, 2.5, 2.8_
  - [x] 1.2 Create `237-create-external-orders.xml`
    - Tables `external_orders`, `external_order_lines`, `external_order_documents`, FKs, unique pairs,
      indexes. Idempotent. Register after 236.
    - _Requirements: 11.1, 11.2, 11.3, 11.6_
    - _Depends on: 1.1_
  - [x] 1.3 Create `238-extend-bank-links-for-proforma-external-order.xml`
    - First read changeset 203 for the exact CHECK names. Add `client_proforma_id` / `external_order_id`
      columns, FKs, indexes to links and suggestions; drop and recreate the kind CHECKs (links, suggestions,
      rules) with the two new kinds and their exact target columns; guard with `MARK_RAN` preconditions.
      Register after 237.
    - _Requirements: 7.2_
    - _Depends on: 1.2_
  - [x] 1.4 Create `239-seed-proforma-external-order-resources.xml`
    - Resources `CLIENT_PROFORMAS`, `EXTERNAL_ORDERS` with RU/PL names and descriptions; ADMIN and role
      grants of Requirement 1.2; FINANCIER `NOTIFICATIONS` and reference READ grants inserted only when
      missing; `NOT EXISTS` pattern of changeset 229. Update any resource-seed integration test that counts
      grants. Register after 238.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
    - _Depends on: 1.3_

- [x] 2. Pure rules (no Spring)
  - [x] 2.1 Proforma pure classes
    - `ProformaStatus`, `ProformaStatusMachine`, `ProformaStatusDeriver`, `ProformaNumberFormatter`,
      `ProformaMath` (wrapper over `PositionMoneyMath`). Unit and jqwik tests (status transitions, derivation
      boundaries, rounding, gross equals planner total for the same positions).
    - _Requirements: 2.3, 2.5, 3.4, 8.1, 28.1_
  - [x] 2.2 External-order pure classes
    - `ExternalOrderStatus`, `PaidSource`, `ExternalOrderStatusMachine`, `ExternalOrderMath`,
      `ExternalOrderCoverageValidator`, `ExternalOrderPaymentDeriver`, `PositionOrderStageResolver`,
      `CeilingCheck`. Unit and jqwik tests (coverage exactness, resolver exhaustiveness, allocation
      sum-exactness via `DeliveryCostAllocator`, ceiling edge cases).
    - _Requirements: 11.4, 11.5, 12.3, 12.4, 13.1, 16.1, 16.2, 17.2, 28.1_
  - [x] 2.3 Extend `PositionTransitionPolicy`
    - Add SYSTEM edges `ORDERED -> WITH_BUYER` and `SUPPLIER_PAID -> ORDERED`; update the state-machine
      diagram comment and the existing policy tests (including the gate tests, which must stay green).
    - _Requirements: 17.4, 29.3_

- [x] 3. Proforma backend
  - [x] 3.1 Entities and DAOs
    - `ClientProformaEntity`, `ClientProformaLineEntity`, `ClientProformaDao`, `ClientProformaLineDao`
      (aggregate queries for list totals, `existsByPositionIdAndActiveTrue`), enums.
    - _Requirements: 2.1, 2.2, 2.7_
    - _Depends on: 1.1_
  - [x] 3.2 `ClientProformaService` (draft)
    - `AdminService` + `ProjectScopedService`; create/update/delete draft; position validation; snapshot
      builder; map the unique-index violation to `error.proforma.position.alreadyInProforma`. Verify the
      FOR-11-00 position update rules and add the lock of R4.6 where missing. DTOs and mapper (staff).
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 4.6, 21.1_
    - _Depends on: 3.1, 2.1, 2.3_
  - [x] 3.3 Issue and PDF
    - `ProformaNumberService`, `ProformaIssueService`, `ProformaHtmlBuilder`, `ProformaPdfLabels` (+ two
      property files), `ProformaPdfService`. First read `PdfFreezeService` and `CompanyProfileService`:
      reuse the storage seam, confirm the bank-account and client-display data sources (add the bank-account
      field to the company profile and its form only if missing). Compensate a stored object when a later
      step fails. Publish `ProformaIssuedEvent`.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7, 4.8, 26.4_
    - _Depends on: 3.2_
  - [x] 3.4 Cancel
    - `ProformaCancelService` (R5), including the "no link" guard and position return to `ACCEPTED`.
    - _Requirements: 5.1, 5.2, 5.3, 5.4_
    - _Depends on: 3.3_
  - [x] 3.5 Client-safe DTOs and controllers
    - `ClientProformaClientDto` / `ClientProformaLineClientDto`, `ClientProformaController` (staff) and
      `ClientProformaClientController` (`/client/**`), role re-check, 404 without leak, PDF streaming,
      method-level `@RequiresPermission` everywhere. Reflection leak guard test and sentinel JSON test.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 1.6_
    - _Depends on: 3.2, 1.4_

- [x] 4. External-order backend
  - [x] 4.1 Entities and DAOs
    - `ExternalOrderEntity`, `ExternalOrderLineEntity`, `ExternalOrderDocumentEntity` and DAOs
      (`sumQuantityByPositionExcluding`, committed-lines fetch join, `sumAmountByExternalOrderId` on the
      link DAO).
    - _Requirements: 11.1, 11.2, 11.3, 11.7_
    - _Depends on: 1.2_
  - [x] 4.2 `ExternalOrderService`
    - Header CRUD, `replaceLines` (project, status, path, coverage, ceiling, VAT defaults, delivery
      allocation), `replaceDocuments` (scope, attribution cap, seller mismatch flag), delete draft, DTOs
      and mapper with derived totals, `ProjectScopedService`.
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 13.1, 13.4, 14.1, 14.2, 14.3, 14.4, 21.2_
    - _Depends on: 4.1, 2.2, 1.4_
  - [x] 4.3 `ExternalOrderLifecycleService` and `ExternalOrderPaymentService`
    - `send`, `markPaid`, `returnToBuyer`, `unmarkPaid`, `cancel`; `reevaluate(orderId)` with the four
      cases of R16; publish `ExternalOrderSentEvent` / `ExternalOrderPaidEvent`; implement
      `ExternalOrderLedgerEffects`.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 16.3, 16.4, 16.5, 16.6, 14.5_
    - _Depends on: 4.2, 4.4_
  - [x] 4.4 `PositionOrderSyncService`
    - Recompute `orderedQuantity`, `actualPurchasePrice`, `deliveryCostShare`; apply the resolver through
      `PositionLifecycleService` one step at a time; idempotent; skip `IN_DELIVERY` and later.
    - _Requirements: 13.2, 13.3, 17.1, 17.2, 17.3, 17.5, 17.6_
    - _Depends on: 4.1, 2.2, 2.3_
  - [x] 4.5 `ExternalOrderController`
    - Endpoints of the design table, `isMoneyPrivileged` re-check in the mapper, method-level
      `@RequiresPermission`.
    - _Requirements: 1.6, 15.7, 21.2_
    - _Depends on: 4.3, 1.4_

- [x] 5. Ledger extension (FOR-07-04)
  - [x] 5.1 Link kinds, request, validation, effects hooks
    - `LinkKind`, `LinkRequest`, link and suggestion entities, `BankLinkService` validation (direction,
      currency, linkable status, caps), `LinkEffects.apply` / new `onRemoved` / extended `isLocked` calling
      the ports `ProformaLedgerEffects` and `ExternalOrderLedgerEffects`; call `onRemoved` from remove,
      replace and ignore paths; publish `ProcurementDocumentPaymentStatusChangedEvent` for `PROCUREMENT`
      link changes; ledger DTOs extended (no purchase prices). Re-run the existing FOR-07-04 tests.
    - _Requirements: 7.1, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 29.2_
    - _Depends on: 1.3_
  - [x] 5.2 `ProformaPaymentService` and `ProformaReleaseService`
    - Recompute with row lock, `ProformaStatusDeriver`, release in the same transaction, idempotence,
      locked-link rule, publish `ProformaPaidEvent`.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_
    - _Depends on: 5.1, 3.3_
  - [x] 5.3 Ledger-driven external-order payment wiring
    - Implement the link and document-status triggers of `ExternalOrderPaymentService.reevaluate`
      (listener of `ProcurementDocumentPaymentStatusChangedEvent`, `ExternalOrderLedgerEffects`).
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.6_
    - _Depends on: 5.1, 4.3_
  - [x] 5.4 Matching: forward, reverse, search, events
    - `TargetType` additions, providers, `RuleMatcher` reason codes and number regex, `ReverseProformaMatcher`,
      `ReverseExternalOrderMatcher`, target search, `LedgerMatchProposedEvent`. Existing matching tests stay
      green.
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7_
    - _Depends on: 5.1, 3.3, 4.2_

- [x] 6. Notifications
  - [x] 6.1 `ProformaOrderNotificationEmitter`
    - Five types, recipients, de-dup, actor removal, AFTER_COMMIT with try/catch; confirm the ledger deep-link
      route against `BankNotificationEmitter`.
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5_
    - _Depends on: 5.2, 5.3, 4.3_

- [x] 7. Planner extension (backend)
  - [x] 7.1 Grouping, rows and filters
    - `GroupBy.PROFORMA`, `GroupBy.EXTERNAL_ORDER`, group DTO extras, row additions, filters
      `proformaId` / `externalOrderId`; keep `REQUEST` and `NONE` behavior.
    - _Requirements: 18.1, 18.2, 18.4, 18.5, 29.4_
    - _Depends on: 3.1, 4.1_

- [x] 8. Access and datasets
  - [x] 8.1 `ProcurementProjectAccess`, datasets, coverage
    - Add `FINANCIER` to the company-wide roles with the "no other grant" test; `ClientProformasDataset`,
      `ExternalOrdersDataset` with `nullProjectPredicate`; run `DatasetCoverageTest`,
      `SensitiveFieldGuardTest`.
    - _Requirements: 20.1, 20.2, 20.3, 27.1, 27.2, 27.3, 27.4_
    - _Depends on: 3.1, 4.1, 1.4_

- [x] 9. Frontend (inner repo `foremen-frontend/`)
  - [x] 9.1 Proforma API, types, hooks
    - `features/client-proformas` api/types/hooks for staff and client endpoints.
    - _Requirements: 23.1, 23.5_
    - _Depends on: 3.5_
  - [x] 9.2 Staff proforma screens and planner action
    - List, draft editor, issue and cancel dialogs, proforma card; planner group-by options, group header
      extras and the "Create proforma" toolbar action.
    - _Requirements: 18.3, 23.1, 23.2, 23.3, 23.6_
    - _Depends on: 9.1_
  - [x] 9.3 Client proforma view and workspace tab
    - Mobile-first cards, PDF, copy transfer title, empty state, tab `clientProformas` with badge, call
      allow-list.
    - _Requirements: 22.1, 22.4, 23.4, 23.5_
    - _Depends on: 9.1_
  - [x] 9.4 External-order API, types, hooks
    - `features/external-orders` api/types/hooks.
    - _Requirements: 24.1_
    - _Depends on: 4.5_
  - [x] 9.5 Buyer screens
    - List, editor (header, `SellerPicker`, lines grid with remaining quantity and ceiling acknowledge,
      documents panel with upload and attach, totals), send and cancel.
    - _Requirements: 24.1, 24.3, 24.4, 24.5_
    - _Depends on: 9.4_
  - [x] 9.6 Financier queue
    - Queue, card, Mark paid, Return, Unmark dialogs, paid-source badge.
    - _Requirements: 24.2, 24.3, 24.4_
    - _Depends on: 9.4_
  - [x] 9.7 Ledger dialog extension
    - New kinds in the link dialog, proforma and external-order pickers with search, suggestion rows and
      reason labels; verify the existing reason-label key prefix first.
    - _Requirements: 7.6, 10.4, 10.5_
    - _Depends on: 5.4_
  - [x] 9.8 Routes, tabs, permissions, deep links
    - `/deliveries/proformas`, `/deliveries/external-orders`, route-permission config, menu visibility for
      `FINANCIER`, deep-link highlight (`?proforma`, `?order`, `?project`).
    - _Requirements: 22.2, 22.3_
    - _Depends on: 9.2, 9.3, 9.5_
  - [x] 9.9 Locale keys
    - Add every key of the design table to `pl.json` and `ru.json` (one edit, parity checked by
      `i18n.keys.test.ts`).
    - _Requirements: 25.1, 25.2, 25.3_
    - _Depends on: 9.6, 9.7, 9.8_

- [x] 10. Checkpoint: backend
  - Compile (`compileJava`, `compileTestJava`), run the affected unit and property tests and the shared-code
    tests (policy, link, detection, planner, datasets). Ask the user if questions arise.
  - _Depends on: 6.1, 7.1, 8.1, 4.5, 5.4_

- [x] 11. Tests
  - [x] 11.1 Backend integration tests
    - Per Requirement 28.2: proforma lifecycle and release, ledger links, external order lifecycle, ledger
      wins and reversal, stage sync, ABAC, isolation, matching, notifications, planner grouping, CHECK
      constraints, migration idempotency, i18n parity (errors and PDF labels), leak guard.
    - _Requirements: 28.1, 28.2, 26.1, 26.4_
    - _Depends on: 10_
  - [x] 11.2 Frontend tests
    - Per Requirement 28.3.
    - _Requirements: 28.3_
    - _Depends on: 9.9_
  - [x] 11.3 Non-regression run
    - Re-run FOR-07-04 ledger and detection tests, FOR-06-07 payment tests, FOR-11-00 policy and gate tests,
      FOR-11-01 planner tests, FOR-11-02 approval tests; fix regressions.
    - _Requirements: 29.1, 29.2, 29.3, 29.4, 29.5_
    - _Depends on: 11.1, 11.2_

- [x] 12. Update the FOR-11 OVERVIEW
  - Row 03 was marked "Spec ready" with the correction (no payment-document generator exists; separate
    `ClientProforma`) when the spec was authored. At the end, re-read it and update it to the final
    implemented state (link kinds, the two added position edges, changesets 236 to 239) if anything changed.
  - _Requirements: 4.1_
  - _Depends on: 10_

- [x] 13. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature
    grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report
    template). For API-only parts, scenarios are API tests against the Dockerized app; for UI parts,
    browser-engine scenarios. Result artifacts are MD reports with tables.
  - _Requirements: 28.4_
  - _Depends on: 11.3_

- [x] 14. Final checkpoint
  - Verify `git status` in **both** repos (root and `foremen-frontend/`) shows nothing intended left behind;
    confirm all requirements are covered and report to the user.
  - _Depends on: 12, 13_

- [x] 15. Close the gaps found at the final checkpoint
  - [x] 15.1 Backend: proforma payments list and order-to-proforma reference
    - Add a read-only `payments` list (link id, booking date, amount, ledger line title; no counterparty account data) to `ClientProformaServiceExtendedModel` for the STAFF detail only (never in the client DTO; extend the leak guard test). Add `proformaIds` / `proformaNumbers` (proformas of the order's positions through active lines) to `ExternalOrderServiceExtendedModel` for the buyer card. Unit tests.
    - _Requirements: 23.3, 24.5, 6.3_
  - [x] 15.2 Backend: server-side planner filters for rows without proforma / order
    - Add `noProforma` and `noExternalOrder` filters to `PlannerFilter`, `PlannerQueryService.specification` and `PurchasePlannerController` (planner, groups, slice-counts); keep existing constructors. Integration test in `PlannerGroupsIntegrationTest`.
    - _Requirements: 18.1, 18.4, 29.4_
  - [x] 15.3 Backend: HTTP-level integration test for ledger links
    - SpringBootTest + MockMvc against the real schema: create, replace and delete PROFORMA_PAYMENT and EXTERNAL_ORDER_PAYMENT links through /api/bank/transactions/{id}/links and /api/bank/links/{id}, verifying proforma release, order paid state and the 409 cases.
    - _Requirements: 7.1, 7.6, 8.1, 16.3, 28.2_
  - [x] 15.4 Frontend: use the new data and add the missing UI test
    - Staff proforma card shows the payments list; buyer order card shows read-only proforma link(s); planner NO_PROFORMA / NO_EXTERNAL_ORDER groups use the new server filters (drop the client-side 100-row filtering); add a drawer-level test for creating a proforma and an external-order link in the ledger dialog. Add new locale keys to pl.json and ru.json with parity.
    - _Requirements: 23.3, 24.5, 18.3, 7.6, 25.1_
  - [x] 15.5 Final verification of the follow-up
    - Compile, run affected backend and frontend tests, update test-cases.md and the OVERVIEW if contracts changed.
    - _Requirements: 28.1, 28.4_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.2"] },
    { "id": 1, "tasks": ["1.2", "2.3", "3.1"] },
    { "id": 2, "tasks": ["1.3", "3.2", "4.1"] },
    { "id": 3, "tasks": ["1.4", "3.3", "4.4"] },
    { "id": 4, "tasks": ["3.4", "3.5", "4.2", "5.1"] },
    { "id": 5, "tasks": ["4.3", "5.2", "7.1", "8.1", "9.1"] },
    { "id": 6, "tasks": ["4.5", "5.3", "5.4", "9.2", "9.3"] },
    { "id": 7, "tasks": ["6.1", "9.4", "9.7"] },
    { "id": 8, "tasks": ["9.5", "9.6", "10"] },
    { "id": 9, "tasks": ["9.8", "11.1", "12"] },
    { "id": 10, "tasks": ["9.9"] },
    { "id": 11, "tasks": ["11.2"] },
    { "id": 12, "tasks": ["11.3"] },
    { "id": 13, "tasks": ["13"] },
    { "id": 14, "tasks": ["14"] },
    { "id": 15, "tasks": ["15.1", "15.2", "15.3"] },
    { "id": 16, "tasks": ["15.4"] },
    { "id": 17, "tasks": ["15.5"] }
  ]
}
```
