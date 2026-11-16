# Implementation Plan: FOR-11-06 — Delivery UX refinements

## Overview

Implements the unified `ClientPayment` resource (one controller, one ABAC resource `CLIENT_PAYMENTS`, one
paginated list over works tranches and materials proformas), the per-project payee bank account, the
estimate-driven finishing positions with thumbnail, multi-select with contextual bulk actions, the buyer
improvements (payment link, buyer-added position with the unpaid rule, quick purchase) and the receipt
delegation to a project worker. Implementation language: Java (backend, Spring Boot) and TypeScript / React
(frontend), as in the design.

- **Reuse:** `PaymentScheduleService`, `ReconciliationEngine`, `ClientProformaService`, `ProformaIssueService`,
  `ProformaPdfService`, `BankLinkService`, `MaterialsListService`, `WorkCategoryResolver`,
  `PositionLifecycleService`, `SelectionTotalService`, `ExternalOrderService`, `GoodsReceiptService`,
  `GoodsReceiptNotificationEmitter` (pattern), `ProjectMemberDao`, `ImageStorage`, `DatasetRegistry`.
- **Spans two git repos:** backend, specs and infrastructure commit from the workspace root; all
  `foremen-frontend/` changes commit from inside `foremen-frontend/` (set the tool `cwd` to
  `foremen-frontend`; the root repo ignores that directory). Stage specific files, never force-push, commit
  only when asked. Before reporting a feature done, check `git status` in both repos.
- **Migrations:** changesets **247** to **252** registered last in `changelog.xml` after 246 (task 1.7), and
  **253** (cleanup, task 9.2) after verification. Never edit an already applied changeset.
- **Test-run policy (workspace rule):** run only the affected test classes with `--tests`, redirect output to
  a temp log and read the JUnit XML. Never run the full suite unless the user asks (about 20 minutes).
  Example:
  `./gradlew test --tests "com.foremen.service.clientpayment.ClientPaymentStatusMapperPropertyTest" > /tmp/for-11-06-test.log 2>&1; echo "EXIT=$?"`,
  then read `/tmp/for-11-06-test.log` or
  `foremen-backend/build/test-results/test/TEST-<fqcn>.xml`. Compile-only checks use
  `./gradlew compileJava compileTestJava` (also redirected to a temp log). Frontend tests run with
  `vitest --run` on the affected files only. Known failing test unrelated to this spec:
  `ProjectServiceProjectionTest.createProject assigns an admin-staff member and processes no client block when none is supplied`.
- **Single-writer files (for the waves):** `changelog.xml` (1.7, then 9.2), `messages.properties` /
  `messages_ru.properties` (9.1 only), `pl.json` / `ru.json` (15.1 only), `workspaceTabs.ts`, `types.ts` of the
  workspace, `WorkspaceTabs.tsx`, `route-permissions.ts`, `router.tsx` (10.6 only), `navigation.ts` (14.1
  only), `ShipmentFormDialog.tsx` (13.4 only), `StaffApprovalsView.tsx` (12.1, then 12.2),
  `BoardCard` / `PlannerDataRow` (13.2, then 13.3), `ExternalOrderController` (7.1, then 7.5),
  `ProformaIssueService` (3.3, then 7.4), `ProformaOrderNotificationEmitter` and the other emitters (7.6 only),
  `GoodsReceiptService` (8.2 only), `PurchasePositionEntity` and its joins (4.2 only).
- **Controllers added as new classes** so that tasks in one wave never edit the same controller:
  `EstimateSyncController` (4.3), `BuyerAddedPositionController` (7.2), both under the resource
  `PURCHASE_POSITIONS`.
- **Entity checklist:** steps 1 to 5 of `.kiro/steering/entity-creation-rules.md` are covered by tasks 1.2,
  2.5 and 2.7 (resource seed, ADMIN grant, annotations, `getProjectIdPath()`, dataset) for `CLIENT_PAYMENTS`,
  and by 1.6 and 8.1 for the delegation endpoints under the existing `GOODS_RECEIPTS`.

## Tasks

- [x] 1. Database
  - [x] 1.1 Create `247-create-client-payments.xml`
    - Three changeSets (create base table and nullable `client_payment_id` columns; backfill both kinds with
      temporary legacy-id columns and drop them; NOT NULL, unique FKs, CHECKs and indexes), all with
      `MARK_RAN` preconditions as in design 1.2. Copy stored statuses as they are.
    - _Requirements: 1.1, 1.2, 14.1, 14.4, 14.5_
  - [x] 1.2 Create `248-seed-client-payments-resource.xml`
    - Resource `CLIENT_PAYMENTS` (RU/PL name and description), merged grants of design section 3 (ADMIN CRUD,
      MANAGER CRUD, FINANCIER CRUD, FOREMAN R, BUYER R, CLIENT R), removal of the old `PAYMENTS` and
      `CLIENT_PROFORMAS` `role_resources` grants; guards `onFail="MARK_RAN"` (pattern of 239 and 245).
    - _Requirements: 1.4, 1.7, 1.10, 14.1, 14.2_
  - [x] 1.3 Create `249-add-project-bank-account.xml`
    - `projects.bank_account varchar(34)`, column-exists `MARK_RAN` precondition.
    - _Requirements: 4.1, 14.1_
  - [x] 1.4 Create `250-purchase-position-estimate-and-buyer-flags.xml`
    - `planned_material_id` nullable; `estimate_source_key`, `estimate_changed`, `estimate_removed`,
      `added_by_buyer`, `client_unpaid`; partial unique index and the two CHECK constraints of design 6.1.
    - _Requirements: 5.2, 5.3, 10.2, 14.1_
  - [x] 1.5 Create `251-add-external-order-payment-link.xml`
    - `external_orders.payment_link varchar(1000)`.
    - _Requirements: 9.1, 14.1_
  - [x] 1.6 Create `252-goods-receipt-delegations.xml`
    - Table `goods_receipt_delegations` with CHECK, the partial unique index for one `ACTIVE` delegation per
      shipment and the lookup indexes; `goods_receipts.delegation_id`; second changeSet seeds the WORKER grant
      `GOODS_RECEIPTS` `CREATE`, `READ` (idempotent).
    - _Requirements: 12.1, 12.4, 14.1_
  - [x] 1.7 Register changesets 247 to 252 in `changelog.xml`
    - Add the six `<include>` lines last, in order, each with the comment style of the existing entries.
    - _Requirements: 14.1_
    - _Depends on: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

- [x] 2. Unified client payment (backend)
  - [x] 2.1 Base entity, detail refactor and DAOs
    - `ClientPaymentEntity` (`@OneToOne(mappedBy)` to the two detail entities), `ClientPaymentDao`;
      `PaymentTrancheEntity` and `ClientProformaEntity` get `payment` and **delegating** getters / setters for
      the moved columns (design 1.3); `ClientPaymentKind` enum. Compile the whole backend; run the payment
      and proforma unit tests that do not need Spring.
    - _Requirements: 1.1, 1.2, 1.9_
    - _Depends on: 1.7_
  - [x] 2.2 `ClientPaymentStatusMapper` and `DisplayStatus`
    - Pure mapper `(kind, storedStatus, dueDate, today) -> DisplayStatus`, its inverse for the status filter,
      and `overdue(...)`; property test over all kind and status pairs (total, deterministic, inverse round
      trip).
    - _Requirements: 1.9_
  - [x] 2.3 Read DTOs and assemblers
    - `ClientPaymentDto` (staff), `ClientPaymentClientDto` (client-safe, with `payeeBankAccount`),
      `ClientPaymentView` supertype, `ClientPaymentAudienceResolver`, `ClientPaymentAssembler`; line summary
      for materials; `effectiveBankAccount` through the resolver of 3.3. Extend `ClientDtoLeakGuard`.
    - _Requirements: 1.6, 3.2, 3.3, 4.4_
    - _Depends on: 2.1, 2.2, 3.3_
  - [x] 2.4 `ClientPaymentQueryService` (list, summary)
    - Specification with kind, status, overdue, due range, query, project scope and the per-role kind rule
      (R1.7, R3.1); stable default sort `dueDate ASC NULLS LAST, id ASC`; `summary` aggregates over the same
      specification; BUYER and FINANCIER widening through `ProcurementProjectAccess`.
    - _Requirements: 1.4, 1.5, 1.7, 2.2, 3.1, 3.4_
    - _Depends on: 2.1, 2.2_
  - [x] 2.5 `ClientPaymentController` and `ClientPaymentService`
    - One controller at `/api/client-payments` with every endpoint of the table in design section 3, class
      `@PermissionResource("CLIENT_PAYMENTS")`, every handler `@PermissionOperation`; service-level kind
      restrictions; remove `PaymentScheduleController`, `ClientProformaController`,
      `ClientProformaClientController`; re-annotate `BankFeedIngestController`; keep the existing request
      and response models of the moved actions; update OpenAPI.
    - _Requirements: 1.3, 1.4, 1.7, 3.4_
    - _Depends on: 2.3, 2.4_
  - [x] 2.6 Bank-link target `clientPaymentId`
    - `LinkRequest.clientPaymentId`, resolution in `BankLinkService.resolveTargets` (WORKS -> `CLIENT_PAYMENT`
      with its schedule, MATERIALS -> `PROFORMA_PAYMENT` with its proforma), ledger search and reverse
      matcher addressing; no column or CHECK change; old fields stay accepted.
    - _Requirements: 1.8_
    - _Depends on: 2.1_
  - [x] 2.7 Re-bind datasets
    - `PaymentSchedulesDataset`, `PaymentTranchesDataset`, `ClientProformasDataset` require
      `CLIENT_PAYMENTS`; codes and catalogs unchanged; `DatasetCoverageTest` and `SensitiveFieldGuardTest`
      stay green; seeded widgets keep working.
    - _Requirements: 1.10, 14.3_
    - _Depends on: 2.5_
  - [x] 2.8 Backend tests for the unified payment
    - `ClientPaymentMigrationIntegrationTest` (counts and sums, links and allocations resolve),
      `ClientPaymentControllerAbacTest` (replaces the two old ABAC tests), `ClientPaymentClientDtoLeakGuardTest`
      (allows only `payeeBankAccount`), list / filter / summary / role-kind integration test, link resolution
      test, updates of `PaymentMessagesParityTest` and the migration integration test of 239. Run only these
      classes and the existing payment, proforma and ledger test classes affected.
    - _Requirements: 1.1 to 1.10, 3.3, 3.4, 14.4, 14.5, 17.2_
    - _Depends on: 2.5, 2.6, 2.7, 3.3_

- [x] 3. Project bank account (backend)
  - [x] 3.1 `IbanValidator`
    - Pure validator (normalization, country length table, mod-97); unit and property tests including the
      Polish NRB without prefix.
    - _Requirements: 4.1_
  - [x] 3.2 `ProjectBankAccountService` and endpoint
    - `PUT /api/projects/{id}/bank-account`, authorization (ADMIN or `ACTIVE` MANAGER member), audit with a
      masked before / after, DTO exposure only for ADMIN and MANAGER, generic update ignores the field.
    - _Requirements: 4.1, 4.2_
    - _Depends on: 1.7, 3.1_
  - [x] 3.3 Effective account, snapshot on issue and PDF
    - `EffectiveBankAccountResolver`; `ProformaIssueService` stores `bank_account_snapshot` before rendering;
      `ProformaPdfService` and `ProformaHtmlBuilder` use the snapshot; relax `COMPANY_INCOMPLETE` as in
      design section 4.
    - _Requirements: 4.4, 4.5, 4.6, 4.7_
    - _Depends on: 1.7, 3.1, 2.1_
  - [x] 3.4 Tests for the bank account
    - Validator, authorization matrix (ADMIN, project MANAGER, other MANAGER, FOREMAN, CLIENT), snapshot
      immutability after a project edit, fallback chain, PDF text with Polish letters (extend
      `ProformaPdfTest`), `SensitiveFieldGuardTest` unchanged.
    - _Requirements: 4.1 to 4.7_
    - _Depends on: 3.2, 3.3_

- [x] 4. Estimate-driven positions (backend)
  - [x] 4.1 Expose estimate rows for the sync
    - Read `MaterialsListService` and decide how placeholder rows (no chosen material) and a stable row key
      are exposed; add a read-only `placeholders()` and the `sourceKey` without duplicating the quantity
      formula. Record the decision in a short note at the end of this file.
    - _Requirements: 5.1, 5.3_
  - [x] 4.2 Nullable material: entity, joins and guards
    - `PurchasePositionEntity` fields of changeset 250 (including the buyer flags); `PositionLabel`; left joins
      at every `plannedMaterial` site (grep list in the task notes); `error.estimateSync.materialRequired`
      guards in `PositionApprovalService.send`, `ClientProformaService.create`, `ExternalOrderService.applyLines`,
      `ShipmentService.validateLines`; `validateUpdate` allows choosing the first material while `PLANNED`.
    - _Requirements: 5.3, 5.7, 7.3_
    - _Depends on: 1.7_
  - [x] 4.3 `EstimateSyncService`, controller and activation listener
    - Algorithm of design 6.2, `EstimateSyncController` (`POST /api/purchase-positions/sync-from-estimate`,
      `PURCHASE_POSITIONS` CREATE), `EstimateSyncOnActivation` listener (best effort), summary DTO, project
      must be `ACTIVE`, BoM-row validation.
    - _Requirements: 5.1, 5.2, 5.4, 5.5, 5.6, 5.8_
    - _Depends on: 4.1, 4.2_
  - [x] 4.4 Tests for the estimate positions
    - Idempotency, create / update / flag rules, pending position rejected by send / proforma / order /
      shipment, CHECK constraints, work link and dates, non-ACTIVE project, construction rows ignored.
    - _Requirements: 5.1 to 5.8_
    - _Depends on: 4.3_

- [x] 5. Materials matrix without finishing
  - [x] 5.1 Backend filter in `MaterialsMatrixService`
    - Skip finishing planned rows and the fact lines matched to finishing materials; rewrite
      `MaterialsMatrixServiceTest` and `MaterialsMatrixAssemblerPropertyTest` expectations.
    - _Requirements: 6.1, 6.3_
  - [x] 5.2 Frontend panel
    - `MaterialsMatrixPanel` renders the construction table only; `matrixIsEmpty`, `defaultBranch` and the
      dashboard ignore finishing; update `materials-matrix-panel.test.tsx`, `no-raw-key.render.test.tsx`
      and `locale-parity.for0605.test.ts` (the locale keys are removed in 15.1).
    - _Requirements: 6.2, 6.3, 6.4_
    - _Depends on: 5.1_

- [x] 6. Approvals backend
  - [x] 6.1 Thumbnail and type fields
    - `StaffPositionSource.photo`, `StaffApprovalQueryService` select, `StaffViewAssembler.thumbnailUrl` and
      `materialTypeName`; `ClientViewAssembler` returns a CDN URL for `item.photo`; leak guard stays green.
    - _Requirements: 7.1, 7.2_
    - _Depends on: 4.2_
  - [x] 6.2 Selection total for every `AGREED` status
    - Extend `SelectionTotalService` and its response with `count` and `unpriced`; keep the STAFF_FULL rule.
    - _Requirements: 8.1_
  - [x] 6.3 Bulk endpoints
    - `decide-on-behalf-bulk` (`APPROVE`) and `withdraw-bulk` (`UPDATE`) in `PositionApprovalStaffController`
      with the per-item `REQUIRES_NEW` executor, 200-item cap, per-item result.
    - _Requirements: 8.4, 8.5, 8.6_
  - [x] 6.4 Tests for the approvals backend
    - Staff DTO thumbnail, selection total with unpriced rows, bulk partial failure (stale price, no open
      round), ABAC of the two endpoints, client DTO CDN URL.
    - _Requirements: 7.1, 7.2, 8.1, 8.4 to 8.6_
    - _Depends on: 6.1, 6.2, 6.3_

- [x] 7. Buyer backend
  - [x] 7.1 External-order payment link
    - Field, validation (`HttpUrls`), `PUT /{id}/payment-link`, header support, lifecycle method and event;
      DTOs of non-money roles never map it.
    - _Requirements: 9.1, 9.2, 9.5_
    - _Depends on: 1.7_
  - [x] 7.2 Buyer-added position
    - `BuyerAddedPositionController` (`POST /api/purchase-positions/buyer-added`), `BuyerAddedPositionService`,
      `PositionTransitionPolicy.decideBuyerAdded` and `PositionLifecycleService.transitionBuyerAdded`,
      optional attach to a draft order or request, `PositionAddedByBuyerEvent`; the existing `decide` and the
      gate stay unchanged.
    - _Requirements: 10.1, 10.2, 10.3, 10.7_
    - _Depends on: 1.7, 4.2_
  - [x] 7.3 `Delivery_Complete` across the code
    - `PositionDelivery` helper; `HideDeliveredFilter`, `PlannerSliceRules` (snapshot flag, terminal rules,
      `NOT_PAID`, Criteria predicates), `BoardService`, `PurchaseRequestStatusDeriver`; update the
      SQL-equivalence test.
    - _Requirements: 10.4_
    - _Depends on: 1.7, 4.2_
  - [x] 7.4 Proforma inclusion and flag clearing
    - `ClientProformaService.create` accepts a flagged position in `WITH_BUYER..RECEIVED`;
      `ProformaIssueService` and `ProformaReleaseService` skip the status transitions for a flagged position;
      `ClientUnpaidClearer` on `ProformaPaidEvent`; warning field "contains delivered items" in the read DTO.
    - _Requirements: 10.5_
    - _Depends on: 7.2, 3.3, 2.1_
  - [x] 7.5 Quick purchase
    - `QuickPurchaseService` and `POST /api/external-orders/from-positions` (one transaction, grouping by
      seller, ceiling acknowledgements, one project only).
    - _Requirements: 11.1 to 11.4_
    - _Depends on: 7.1_
  - [x] 7.6 Emitters for the new and changed notifications
    - `notification.position.addedByBuyer`, `notification.externalOrder.paymentLinkAdded`,
      `notification.receipt.delegated`, `.delegationRevoked`, `.acceptedByDelegate`; the changed deep links of
      `notification.proforma.paid`, `notification.payment.*` and `notification.externalOrder.paid`; all
      `AFTER_COMMIT`, best effort, actor excluded.
    - _Requirements: 10.6, 9.4, 12.8, 13.1 to 13.5_
    - _Depends on: 7.1, 7.2, 8.1, 2.5_
  - [x] 7.7 Tests for the buyer flows
    - Payment link validation and lock, buyer-added creation and the flagged edge (and the gate for an
      unflagged position), unpaid-delivered visibility across filter, planner, board and request status,
      proforma inclusion and flag clearing, quick purchase grouping / mixed projects / ceiling, emitter tests.
    - _Requirements: 9, 10, 11, 13_
    - _Depends on: 7.3, 7.4, 7.5, 7.6_

- [x] 8. Receipt delegation (backend)
  - [x] 8.1 Delegation entity, service and endpoints
    - `GoodsReceiptDelegationEntity`, DAO, `GoodsReceiptDelegationService` (delegate, reassign, revoke,
      list, `activeShipmentIdsFor`), `GoodsReceiptDelegationController` methods under `GOODS_RECEIPTS`,
      assignee validation through `ProjectMemberDao`, caller check (ADMIN or project MANAGER).
    - _Requirements: 12.1, 12.2, 12.3_
    - _Depends on: 1.7_
  - [x] 8.2 Audience filtering in `GoodsReceiptService`
    - `DELEGATE` audience for WORKER in `record`, `toReceive`, `list`, `get`, `ledger`; `delegation_id` on the
      events; `completeForShipment` in `closeResolvedShipments`; DTO fields `delegatedTo`, `delegationId`,
      `receivedByName`, `delegatedByName`; void rule unchanged.
    - _Requirements: 12.4, 12.5, 12.6, 12.7, 12.11_
    - _Depends on: 8.1_
  - [x] 8.3 Tests for delegation
    - Assignee must be an `ACTIVE` WORKER of the shipment's project, one active delegation per shipment,
      reassign, revoke, completion on closure, worker sees only delegated data (list, to-receive, ledger),
      worker cannot record on an undelegated shipment (403), staff behaviour unchanged, notification emission.
    - _Requirements: 12.1 to 12.8, 12.11_
    - _Depends on: 8.2, 7.6_

- [x] 9. Messages and cleanup
  - [x] 9.1 Backend error keys
    - All keys of design section 13 in `messages.properties` and `messages_ru.properties`; the parity tests
      pass.
    - _Requirements: 15.3_
    - _Depends on: 2.5, 3.2, 4.3, 6.3, 7.5, 8.2_
  - [x] 9.2 Changeset 253 and registration
    - `253-drop-legacy-payment-columns.xml` drops the deprecated common columns of the detail tables, only
      after 2.8 and 10.8 have passed; register last in `changelog.xml`.
    - _Requirements: 1.2, 14.1_
    - _Depends on: 2.8, 10.8_

- [x] 10. Client payments (frontend)
  - [x] 10.1 API client, types, hooks, query keys
    - `features/client-payments/api` for the table of design section 3 (list, summary, read, PDF, schedule and
      proforma actions), Zod schemas, `queryKeys.ts`.
    - _Requirements: 15.1_
    - _Depends on: 2.5_
  - [x] 10.2 List, filters, summary and drawer
    - `ClientPaymentsTab`, `ClientPaymentsTable` (server pagination), `ClientPaymentFilters`, totals bar,
      `ClientPaymentDrawer`, `PayToBlock` with the missing-account warning.
    - _Requirements: 2.1, 2.2, 2.3, 4.5_
    - _Depends on: 10.1_
  - [x] 10.3 Schedule panels
    - Move generator, manual scheduler, publish, re-plan and transactions from `project-payments` onto the new
      API inside the tab toolbar.
    - _Requirements: 2.4, 15.1_
    - _Depends on: 10.1_
  - [x] 10.4 Proforma editor and dialogs
    - Move the draft editor, issue and cancel dialogs and the PDF button from `client-proformas` onto the new
      API; the "contains delivered items" warning.
    - _Requirements: 2.3, 10.5, 15.1_
    - _Depends on: 10.1_
  - [x] 10.5 Client cards
    - `ClientPaymentCards` for the CLIENT role with copy buttons (account, transfer title), PDF, line summary,
      deep link `?payment=`.
    - _Requirements: 3.1, 3.2, 3.5_
    - _Depends on: 10.1_
  - [x] 10.6 Tab registry, routes, redirect, badge, permissions
    - Remove `clientProformas` from `types.ts`, `workspaceTabs.ts`, `WorkspaceTabs.tsx`; gate `clientPayments`
      on `CLIENT_PAYMENTS`; redirect `/projects/{id}/clientProformas` to `clientPayments` keeping the query;
      badge counts; route permissions of `/deliveries/proformas*`; `DeliveriesSectionNav`; update the three
      workspace state property tests.
    - _Requirements: 2.5, 2.6, 2.7, 3.1, 15.2_
    - _Depends on: 10.2, 10.5_
  - [x] 10.7 Remove the old modules
    - Delete `features/project-payments` and `features/client-proformas` after their parts are moved; fix
      imports (planner `LinkedGroupExtras`, dashboard builder references).
    - _Requirements: 15.1_
    - _Depends on: 10.3, 10.4, 10.6_
  - [x] 10.8 Frontend tests for the payments
    - Table and filters, role views (staff, client), redirect, badge, drawer, copy actions, `no-raw-key`
      render test, API contract tests; move the surviving old tests.
    - _Requirements: 2, 3, 15.3_
    - _Depends on: 10.7_

- [x] 11. Project bank account (frontend)
  - [x] 11.1 Field in the project card
    - `ProjectFormSheet` input with an IBAN mask, zod refinement, `canEditBankAccount`, dedicated save call;
      tests for the editable and read-only cases.
    - _Requirements: 4.3_
    - _Depends on: 3.2_

- [x] 12. Approvals table (frontend)
  - [x] 12.1 Thumbnail, badges, refresh and choose material
    - `ThumbnailCell`, `EstimateBadges` ("From estimate", "Estimate changed", "Choose material"), related
      works and start date, `RefreshFromEstimateButton`, "Replace / choose material" with `MaterialPicker`.
    - _Requirements: 5.6, 7.1, 7.3, 7.4, 7.5_
    - _Depends on: 6.1, 4.3_
  - [x] 12.2 Selection, selection bar and bulk actions
    - `useStatusSelection`, generic `PositionSelectionBar`, `BULK_ACTIONS` per status, totals from the server,
      summary toast and kept failed rows, mobile sticky bar.
    - _Requirements: 8.1 to 8.7_
    - _Depends on: 12.1, 6.2, 6.3, 10.1_
  - [x] 12.3 Tests for the approvals table
    - Update `staffView.test.tsx`; selection rules, per-status actions, bulk partial failure, pending rows not
      selectable, thumbnail and placeholder.
    - _Requirements: 7, 8_
    - _Depends on: 12.2_

- [x] 13. Buyer (frontend)
  - [x] 13.1 Payment link
    - Field in `ExternalOrderEditor`, "Pay" button on the financier queue card with `safeHttpUrl`.
    - _Requirements: 9.3_
    - _Depends on: 7.1_
  - [x] 13.2 Buyer-added position and unpaid badges
    - Dialog "Add position" on the buyer board and order editor; badge "Delivered, unpaid" on
      `PlannerDataRow`, `BoardCard` and the approvals table.
    - _Requirements: 10.1, 10.4_
    - _Depends on: 7.2, 7.3_
  - [x] 13.3 Quick purchase UI
    - Selection mode on the board column `Do zamówienia` and the buyer planner using `PositionSelectionBar`,
      `QuickPurchaseDialog`, one-project rule, result navigation.
    - _Requirements: 11.1, 11.5_
    - _Depends on: 7.5, 12.2, 13.2_
  - [x] 13.4 Shipment form prefill and deep link
    - Tick every line with a remaining quantity when an order is selected; `?new=1&order={id}` opens the
      dialog preselected; update `shipmentFormDialog.test.tsx`.
    - _Requirements: 11.3_
  - [x] 13.5 Tests for the buyer UI
    - Payment link editor and queue card, buyer-added dialog, quick purchase flow, prefill, unpaid badges.
    - _Requirements: 9, 10, 11_
    - _Depends on: 13.1, 13.3, 13.4_

- [x] 14. Receipt delegation (frontend)
  - [x] 14.1 Worker navigation and receipt screen
    - WORKER bottom bar gains `goodsReceipt`; empty state "No deliveries delegated to you"; update the
      navigation and bottom-bar tests.
    - _Requirements: 12.9_
    - _Depends on: 8.2_
  - [x] 14.2 Delegate dialog and chips
    - `DelegateReceiptDialog` (project's active WORKER members), "Delegate" / "Revoke" on `ShipmentDrawer` and
      the upcoming card, "Delegated to {name}" chips, "Accepted by {name} (delegated by {name})" in history.
    - _Requirements: 12.6, 12.10_
    - _Depends on: 8.2_
  - [x] 14.3 Tests for the delegation UI
    - Worker list shows only delegated items, dialog lists only workers, revoke, chips.
    - _Requirements: 12.9, 12.10_
    - _Depends on: 14.1, 14.2_

- [x] 15. Locales and notifications (frontend)
  - [x] 15.1 `pl.json` and `ru.json`
    - All key groups of the i18n decisions (PL/RU parity), the five notification message keys, label changes in
      `nav.*` and `workspace.tab.*`, removal of `materialsMatrix.tab.finishing*`, of the retired
      `clientProformas.*` and list-only `payments.*` keys; parity and `no-raw-key` tests green.
    - _Requirements: 6.4, 13.3, 15.3_
    - _Depends on: 10.8, 11.1, 12.3, 13.5, 14.3, 5.2_
  - [x] 15.2 Notification deep-link resolution
    - Routes for the five types and the changed links; `notification.receipt.delegationRevoked` not
      clickable; test in `NotificationBell.test.tsx`.
    - _Requirements: 13.3, 13.4_
    - _Depends on: 7.6_

- [x] 16. End-to-end suites
  - [x] 16.1 API e2e slice `for11/06-delivery-ux`
    - Playwright-style API tests against the Docker stack, run-id generator and teardown, covering R1 to R14
      by the cases of `test-cases.md` part A.
    - _Requirements: 1 to 14_
    - _Depends on: 2.8, 3.4, 4.4, 6.4, 7.7, 8.3, 9.1_
  - [x] 16.2 UI e2e scenarios
    - Browser scenarios for the combined payments tab (staff and client), the selection bar, the buyer
      flows, the worker receipt, the project bank account; screenshots are not required, MD reports are.
    - _Requirements: 2, 3, 4, 7, 8, 9, 10, 11, 12_
    - _Depends on: 15.1, 15.2, 16.1_

- [x] 17. Author test-cases.md
  - [x] 17.1 Author test-cases.md
    - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature
      grouping, step-by-step scenarios, repeatability via generator / clean-up, regression group, MD report
      template). Part A: API scenarios against the Dockerized app; part B: browser-engine scenarios. Result
      artifacts are MD reports with tables.
    - _Requirements: 16, 1 to 15_
    - _Depends on: 16.2_

- [x] 18. Checkpoint
  - [x] 18.1 Final verification
    - Run only the affected backend test classes and frontend test files, `compileJava`, `compileTestJava`,
      `tsc`, the parity and `no-raw-key` tests, redirected to temp logs; confirm Requirement 17
      (non-regression); check `git status` in both repos. Ask the user before any full-suite run.
    - _Requirements: 17_
    - _Depends on: 9.2, 15.1, 16.2, 17.1_

## Notes

- Tasks marked with `*` are optional; none is marked, because every property and ABAC test here protects a
  security or data-integrity rule.
- Each task references specific requirements for traceability.
- Waves keep `changelog.xml`, the messages files, the locale files, the workspace tab registry,
  `navigation.ts`, `StaffApprovalsView`, `ExternalOrderController`, `ProformaIssueService`, the emitters and
  `GoodsReceiptService` single-writer per wave.
- **Task 4.1 note (decided):** `MaterialsListService.getMaterials` skips placeholder lines
  (`concreteMaterialId == null`), so they are exposed separately. Both kinds of row come from the same
  kosztorys matrix, so task 4.3 can ship both together (no deferral needed).
  - **Chosen-material rows:** `getMaterials(projectId, false)`, finishing branch only. `MaterialRowDto.sourceKey()`
    returns `"M:" + materialId` (a derived method, not a record component, so existing constructors are unchanged).
    `effectiveTotalQty` already includes the reserve map and the piece-unit rounding.
  - **Placeholder rows:** new read-only `MaterialsListService.placeholders(projectId)` returning
    `List<PlaceholderRowDto>(finishingTypeId, typeName, unit, qty, rangeMin, rangeMax)`, one per finishing type;
    `PlaceholderRowDto.sourceKey()` returns `"T:" + finishingTypeId`. Implemented by the pure static
    `MaterialsListAssembler.placeholders(matrix)`.
  - **No formula duplication:** `qty` is the sum of the matrix's resolved `line.quantity()` passed through the
    existing `effectiveQuantity(asIs, basis, null)` and `roundUpForPieceUnit` (no reserve, since the reserve map
    is keyed by concrete material id). `rangeMin` / `rangeMax` are the min / max of the copied per-unit type
    price ranges (money = qty x range, as in `EstimateMatrixAssembler`).
  - **Tests:** `MaterialsListPlaceholdersTest` (folding by type, PER_ROOM / piece rounding, keys, null matrix).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "2.2", "3.1", "4.1", "5.1", "6.2", "6.3", "13.4"] },
    { "id": 1, "tasks": ["1.7", "5.2"] },
    { "id": 2, "tasks": ["2.1", "3.2", "4.2", "7.1", "8.1"] },
    { "id": 3, "tasks": ["2.4", "3.3", "2.6", "4.3", "6.1", "7.2", "7.3", "7.5", "8.2", "11.1", "13.1"] },
    { "id": 4, "tasks": ["2.3", "3.4", "4.4", "6.4", "7.4", "12.1", "13.2", "14.1", "14.2"] },
    { "id": 5, "tasks": ["2.5", "14.3"] },
    { "id": 6, "tasks": ["2.7", "7.6", "9.1", "10.1"] },
    { "id": 7, "tasks": ["2.8", "7.7", "8.3", "10.2", "10.3", "10.4", "10.5", "12.2", "15.2"] },
    { "id": 8, "tasks": ["10.6", "12.3", "13.3", "16.1"] },
    { "id": 9, "tasks": ["10.7", "13.5"] },
    { "id": 10, "tasks": ["10.8"] },
    { "id": 11, "tasks": ["15.1", "9.2"] },
    { "id": 12, "tasks": ["16.2"] },
    { "id": 13, "tasks": ["17.1"] },
    { "id": 14, "tasks": ["18.1"] }
  ]
}
```
