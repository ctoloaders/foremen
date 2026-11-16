# Requirements Document

## Introduction

FOR-11-06 collects the screen-level refinements that came out of the first walk-through of the delivery
chain (FOR-11-00 to FOR-11-05) on the running stack. It changes no product principle of FOR-11 (the proforma
gate, the three sourcing paths, item-by-item receipt stay), but it reshapes six areas:

1. **One "client payment" resource.** A planned **tranche for works** (FOR-06-07) and a **proforma for
   materials** (FOR-11-03) become two kinds of the same entity, `ClientPayment`, with the same fields, one
   paginated list, **one controller** and **one ABAC resource** (`CLIENT_PAYMENTS`). The staff tab
   `clientPayments` shows all planned payments in one list; the tab `clientProformas` is removed. The client
   sees the same combined list of upcoming payments instead of the `Proformy` tab.
2. **Payee bank account per project.** The payment-document card shows the bank account to pay to. The
   account is configured **per project** by the MANAGER in the project card.
3. **Estimate-driven finishing positions.** The finishing-materials part of the `materialsMatrix` tab moves
   into `positionApprovals`. The estimate is already the basis for purchases: for every finishing material
   of the estimate a position exists, linked to its works and their start. The user keeps the chosen
   material or replaces it with another one (project materials dictionary). Estimate lines with **no chosen
   material** also appear, and a concrete material is picked for them. The table gets a **thumbnail**.
4. **Multi-select with contextual actions** on `positionApprovals`: checkbox per row, selected total, only
   rows of one status, a different action per status.
5. **Buyer improvements:** a **payment link** on an external order for the financier; adding a **new
   position on his own** (bypassing the client-payment flow); a **quick purchase** from several paid
   positions of one project.
6. **Receipt delegation:** the MANAGER delegates the receipt of a shipment to a **worker of the project**;
   the worker sees only delegated receipts; the receipt records who accepted.

### Product decisions this spec implements (from the product owner)

- Planned payments are **one resource**. A works tranche and a materials tranche have the **same fields**;
  the materials one additionally links to concrete materials. **Pagination is shared**, the request goes to
  **one controller**, access is checked against **one ABAC resource**.
- The client sees **one combined list of upcoming payments**, in place of the `Proformy` tab.
- A payment document card shows the **bank account to pay to**; the account is a **project setting** edited
  by the MANAGER in the project card.
- Materials from the estimate are the **basis for starting purchases**. The estimate already ties them to
  works and work start. What remains is "use the chosen material" or "replace it" (stored in the project
  materials dictionary). Not-chosen estimate materials also arrive and get a concrete finishing material.
- Selection on `positionApprovals`: only rows in **one status** can be selected; the action depends on the
  status: planned -> send for approval; accepted -> create a proforma; proposed -> mark accepted / cancel
  (the same actions as for one row).
- The buyer can add a **payment link** for the financier.
- The buyer can **add a new position** to a purchase (example: feet for a bathtub). The client-payment flow
  is bypassed: the material hangs in delivery but is **not paid**; the **client is notified**; the manager can
  later add the material to a **new proforma**; a **delivered but unpaid** material is shown in tables as
  **not delivered**.
- The buyer can **quickly create a purchase / delivery** by ticking several positions that are paid by
  proforma (**one project only**).
- The manager can **delegate receipt to a worker** (with a notification and the name of the one who
  accepted); the worker's receipt section lists **only delegated receipts**; only a **worker of the project**
  can be chosen.

### Assumptions recorded by this spec (confirm during review)

- The client's combined list lives on the existing project tab key `clientPayments` (label "Payments"),
  which becomes visible to CLIENT. The text of the request named the `positionApprovals` URL for that list;
  this spec treats it as the `clientPayments` tab, because `positionApprovals` keeps the approval cards.
- The `PAYMENTS` and `CLIENT_PROFORMAS` resource codes are **retired** in favour of `CLIENT_PAYMENTS`. The
  effective access of every role is preserved (R1.7).
- The bank-account format is an IBAN (checksum validated); the project account **overrides** the company
  profile account and the company account is the fallback.
- The five notifications of the "Notifications" section are all in scope.
- A delegation covers a **whole shipment** (all its lines), not a single position.

### Facts verified in the code (basis of the design)

- Tranches: `payment_schedules` (one per project) and `payment_tranches` (`seq_no`, `due_date`,
  `amount_gross`, `paid_gross`, derived `TrancheStatus` PENDING / DUE / PARTIALLY_PAID / PAID / OVERDUE),
  `tranche_allocations`, controller `PaymentScheduleController` at `/api/payments` under the resource
  `PAYMENTS` (ADMIN, MANAGER, FINANCIER CRUD; FOREMAN, CLIENT READ), **no paginated list** (one schedule per
  project), no client-safe DTO, reconciliation `ReconciliationEngine` (FIFO by `seq_no`).
- Proformas: `client_proformas` (number `PF/yyyy/seq`, `ProformaStatus` DRAFT / ISSUED / PARTIALLY_PAID /
  PAID / CANCELLED, totals, `paid_gross`, `pdf_object_key`), `client_proforma_lines` (linked to
  `purchase_positions`), controllers `ClientProformaController` (`/api/client-proformas`, paginated) and
  `ClientProformaClientController` (`/api/client-proformas/client`, unpaginated), resource
  `CLIENT_PROFORMAS` (ADMIN, MANAGER CRUD; BUYER, FINANCIER, CLIENT READ), client-safe DTO
  `ClientProformaClientDto` guarded by `ClientProformaClientDtoLeakGuardTest`.
- The frontend tab `clientPayments` is gated on `CLIENT_PAYMENTS`, a code that is **not seeded** in the
  backend (the tab itself checks `PAYMENTS`); the tab `clientProformas` is gated on `CLIENT_PROFORMAS`.
- Bank links: `LinkKind.CLIENT_PAYMENT` targets `payment_schedule_id`, `PROFORMA_PAYMENT` targets
  `client_proforma_id` (changeset 238, CHECK constraints). Proforma payment state is `Σ links`.
- `ProformaPdfService` reads `company_profile.bank_account` and fails with 409 when it is blank; an issued
  PDF is a stored snapshot (`pdf_object_key`). `ProjectEntity` has **no** bank-account field.
- `purchase_positions.planned_material_id` is `NOT NULL`; there is **no code that creates positions from the
  estimate**; `estimate_reference_id` is informational and not unique. `WorkCategoryResolver` already
  resolves the work category and its start from the estimate and the schedule.
- The finishing branch of the materials matrix is **not position-based**: `MaterialsMatrixService` folds the
  FOR-05-05b materials list (plan) with reconciled procurement lines (fact); the finishing branch is one of
  two tabs of `MaterialsMatrixPanel`.
- `StaffApprovalsView` already has a `selected` set and a checkbox rendered only for `SEND`; the staff DTO
  carries no photo. The client DTO already carries `item.photo`, built from the raw object key.
- `PositionTransitionPolicy` guards `WITH_BUYER` for `AGREED` (gate violation unless from `CLIENT_PAID` or
  `ORDERED`). BUYER already holds `PURCHASE_POSITIONS` CREATE, READ, UPDATE and `EXTERNAL_ORDERS` write
  access, but **not** `PURCHASE_REQUESTS` CREATE.
- "Delivered" is the status `RECEIVED` only (`HideDeliveredFilter`, `PlannerSliceRules.TERMINAL`,
  `BoardService`, `PurchaseRequestStatusDeriver`).
- `ExternalOrderEntity` already has `orderLink` (the order in the supplier shop); there is no payment link.
- `GOODS_RECEIPTS` grants: ADMIN CRUD, FOREMAN and MANAGER CREATE/READ/UPDATE, BUYER READ; **WORKER none**.
  `goods_receipts.received_by` already records the author. `GoodsReceiptService.toReceive` filters by
  project scope only. The WORKER bottom bar has three slots (cap is five).
- `ProjectAccessCache` does not filter on `ACTIVE`; `ProjectMemberDao.findByProjectIdWithUserAndRole` gives
  user, project role and `assignmentStatus`.
- The last changeset is `246`; the next free number is `247`.

### Scope boundaries

- **In scope:** the unified `ClientPayment` aggregate and its single controller and resource, the staff and
  client combined lists, the project bank account, estimate-driven finishing positions with nullable
  material, removal of finishing from the matrix, the thumbnail, multi-select with bulk actions, the
  external-order payment link, buyer-added positions and the unpaid-delivered rule, the quick purchase, the
  receipt delegation, five notifications, locale keys (PL/RU), changesets, datasets, tests and
  `test-cases.md`.
- **Out of scope:**
  - online card payments, the final VAT invoice and the accounting program;
  - a payment-schedule PDF for works tranches (only materials payments have a PDF);
  - client price negotiation for buyer-added positions (the manager prices them when he adds them to a
    proforma; the client sees the notification only);
  - supplier claims and refunds, offline receipt capture;
  - delegating at the level of a single position, or to non-workers;
  - construction-material purchases (still FOR-06-03/04/05, untouched);
  - changing the three sourcing paths or the proforma-paid gate for `AGREED` positions.

### Notifications (emit-only through the generic `NotificationService`)

The spec only **emits** in-app notifications; no delivery channel is built. The actor is never notified.
The message text of a type is the frontend key of the same name (PL and RU); the in-app bell shows it, a
link makes it clickable.

| Type key | Recipient | Trigger | Deep link |
|----------|-----------|---------|-----------|
| `notification.position.addedByBuyer` | the project's CLIENT members and MANAGER members | a BUYER adds a position that bypasses the client-payment flow (R10) | CLIENT: `/projects/{projectId}/positionApprovals?position={id}`; MANAGER: `/deliveries?project={projectId}&position={id}` |
| `notification.receipt.delegated` | the delegated WORKER | a MANAGER delegates a shipment receipt (R12) | `/deliveries/receipt?shipment={shipmentId}` |
| `notification.receipt.delegationRevoked` | the previously delegated WORKER | the delegation is revoked or reassigned | none (not clickable) |
| `notification.externalOrder.paymentLinkAdded` | all active FINANCIER users | the payment link of an external order in `SENT_FOR_PAYMENT` is set or changed (R9) | `/deliveries/external-orders?order={id}` |
| `notification.receipt.acceptedByDelegate` | the MANAGER who delegated, and the project MANAGER members | a delegated worker records the first receipt event of the delegation | `/deliveries/shipments/{shipmentId}` |

The client notification carries **no price and no purchase data** (R10.6).

### i18n decisions

- **No entity gets `nameRU` / `namePL` fields.** The new data are user text (links, notes), numbers, flags,
  identifiers and one IBAN. No dictionary is introduced. Labels of enums (`kind` WORKS / MATERIALS,
  display status, delegation status) are **frontend keys**.
- Backend: new error keys in `messages.properties` and `messages_ru.properties` (groups
  `error.clientPayment.*`, `error.project.bankAccount.*`, `error.estimateSync.*`, `error.positionApproval.bulk.*`,
  `error.externalOrder.paymentLink.*`, `error.purchasePosition.buyerAdded.*`, `error.quickPurchase.*`,
  `error.goodsReceipt.delegation.*`) with PL/RU parity (existing parity tests).
- Frontend key groups with **PL/RU parity** in `pl.json` and `ru.json`: `clientPayments.*` (replaces
  `clientProformas.*` and the list part of `payments.*`), `project.bankAccount.*`,
  `positionApproval.selection.*`, `positionApproval.estimate.*`, `externalOrders.paymentLink.*`,
  `quickPurchase.*`, `positions.addedByBuyer.*`, `goodsReceipt.delegation.*`, `nav.*` where a label
  changes, and the five notification type keys of the table above.

## Glossary

- **Client_Payment**: a planned payment of the client for a project. Kind `WORKS` (a tranche of the payment
  schedule) or `MATERIALS` (a proforma with lines). Same common fields for both kinds.
- **Payment_Kind**: `WORKS` or `MATERIALS`.
- **Display_Status**: the derived, kind-independent status shown to users: `DRAFT`, `PLANNED`, `DUE`,
  `ISSUED`, `PARTIALLY_PAID`, `PAID`, `OVERDUE`, `CANCELLED`.
- **Payee_Account**: the bank account the client must pay to: the project account, else the company account.
- **Estimate_Position**: a finishing `PurchasePosition` created from an estimate material row; carries
  `estimate_source_key`.
- **Material_Pending_Position**: an Estimate_Position with no chosen material yet (`planned_material_id`
  is null): status `PLANNED`, labelled by its finishing material type.
- **Buyer_Added_Position**: a position created by a BUYER through the dedicated endpoint, flagged
  `added_by_buyer` and `client_unpaid` until a proforma that contains it is paid.
- **Client_Unpaid**: the flag `purchase_positions.client_unpaid`; a position with the flag counts as **not
  delivered** even when its status is `RECEIVED`.
- **Delivery_Complete**: `status = RECEIVED AND NOT client_unpaid`.
- **Receipt_Delegation**: the assignment of one shipment's receipt to one project worker.
- **Delegate**: the worker holding an `ACTIVE` Receipt_Delegation.
- **Selection_Status**: the single `PositionStatus` shared by all selected rows.
- **Quick_Purchase**: the buyer flow that turns a selection of paid positions of one project into external
  order drafts (one per seller); the shipment follows after the financier pays.

## Requirements

### Requirement 1: One client-payment resource, one controller, one ABAC resource

**User Story:** As a product owner, I want works tranches and materials proformas to be one entity with one
list, so that all planned client payments are read, paged, filtered and secured the same way.

#### Acceptance Criteria

1. A new aggregate `ClientPayment` (table `client_payments`) SHALL hold the **common fields** of both kinds:
   `id`, `project_id`, `kind`, `status`, `due_date`, `amount_gross`, `paid_gross`, `currency`, `note`,
   `created_date` / `created_by` (audit fields as elsewhere), `bank_account_snapshot` (R4.6). A works row
   additionally links to its schedule (`schedule_id`, `seq_no`); a materials row additionally has
   `number` (`PF/yyyy/seq`), `issue_date`, net / VAT totals, `pdf_object_key`, `pdf_language`,
   `released_at`, `cancelled_at`, `cancel_reason`, `issued_by` and **lines** linked to concrete positions
   and materials (`client_payment_lines`, the successor of `client_proforma_lines`).
2. The physical model SHALL be a shared base table with per-kind detail tables whose own ids and foreign
   keys (`tranche_allocations.tranche_id`, `bank_transaction_links` targets, `client_proforma_lines`) are
   **kept**, so history and links remain valid (design section 1). A changeset SHALL migrate every existing
   tranche and proforma into the base table without data loss and be idempotent.
3. ONE controller `ClientPaymentController` at `/api/client-payments` SHALL serve list, read and every action
   of both kinds (schedule generation, publish, re-plan, manual tranche edits, mark paid; proforma create,
   update, delete, issue, cancel, PDF). The controllers `PaymentScheduleController` and
   `ClientProformaController` / `ClientProformaClientController` SHALL be removed; their request and response
   contracts move under the new base path (design section 3 lists the mapping).
4. ONE ABAC resource `CLIENT_PAYMENTS` SHALL guard every handler of that controller (class-level
   `@PermissionResource("CLIENT_PAYMENTS")`, every handler a `@PermissionOperation`), seeded by a new changeset
   with `MARK_RAN` preconditions, an ADMIN grant, RU/PL names and descriptions, registered **last** in
   `changelog.xml`. The service SHALL implement `ProjectScopedService` with `getProjectIdPath()` =
   `"project.id"`.
5. `GET /api/client-payments` SHALL return **one paginated page** (`Pageable`, default sort `dueDate`
   ascending, then `id`) over both kinds, with filters `projectId`, `kind`, `status` (Display_Status),
   `overdue`, `dueFrom` / `dueTo`, and `query` (matches `number` and `note`). The page total counts both kinds.
6. The list item (and the read DTO) SHALL carry the common fields, `kind`, `displayStatus`, `overdue`,
   `remainingGross`, `effectiveBankAccount` (R4), and for `MATERIALS` also `number`, `issueDate`, `hasPdf`
   and a line summary (count and material names), never position ids for the client DTO (R3.3).
7. **Effective access SHALL be preserved**: the grants of `PAYMENTS` and `CLIENT_PROFORMAS` are merged into
   `CLIENT_PAYMENTS` as ADMIN CRUD, MANAGER CRUD, FINANCIER CRUD, FOREMAN READ, CLIENT READ, BUYER READ,
   and the service SHALL restrict by kind: FOREMAN reads `WORKS` only; BUYER reads `MATERIALS` only;
   FINANCIER manages `WORKS` (schedule, mark paid) and only reads `MATERIALS`; creating, issuing and
   cancelling a `MATERIALS` payment is limited to ADMIN and MANAGER (error `error.clientPayment.kind.forbidden`
   otherwise); CLIENT is limited to the client-safe surface (R3).
8. Bank-ledger links SHALL target a `ClientPayment`. The link kinds `CLIENT_PAYMENT` and `PROFORMA_PAYMENT`
   SHALL both stay valid persisted values (history, rules, audit); a link created from the ledger for a
   `WORKS` payment resolves to `CLIENT_PAYMENT`, for a `MATERIALS` payment to `PROFORMA_PAYMENT`. The works
   FIFO reconciliation (`ReconciliationEngine`) and the proforma `Σ links` derivation SHALL both keep working
   and SHALL write `paid_gross` / `status` into the base row.
9. Status values SHALL stay per kind in storage (`TrancheStatus`, `ProformaStatus`) and be mapped to the
   **Display_Status** by one pure function; `overdue` is derived (`dueDate < today` and not paid or
   cancelled) for both kinds.
10. The retired resources `PAYMENTS` and `CLIENT_PROFORMAS` SHALL no longer guard any handler. Their
    datasets (`PAYMENT_SCHEDULES`, `PAYMENT_TRANCHES`, `CLIENT_PROFORMAS`) SHALL be re-bound to
    `CLIENT_PAYMENTS` with **unchanged dataset codes**, so the seeded dashboard widgets keep working;
    `DatasetCoverageTest` SHALL pass.

### Requirement 2: Combined staff list on the `clientPayments` tab

**User Story:** As a manager, I want all planned client payments in one list, so that I see works tranches
and material proformas side by side.

#### Acceptance Criteria

1. The tab `clientPayments` SHALL show one server-paginated table over both kinds with columns: kind (chip
   "Works" / "Materials"), number or sequence, due date, amount gross, paid, remaining, display status, bank
   account (short), and actions.
2. Filters SHALL be kind, status, overdue and a due-date range. The tab SHALL show the totals of the filtered
   set (amount, paid, remaining) from the server, not from the current page.
3. Row actions SHALL depend on the kind: for `WORKS` the existing tranche actions (edit while the schedule is
   a draft, mark paid, transactions); for `MATERIALS` open card, download PDF, issue (draft), cancel
   (unpaid), edit (draft). A row opens a **detail drawer** with the document card (R4.5).
4. The schedule management panels (generator, manual scheduler, publish, re-plan) SHALL stay available from
   the tab for users with the matching grant, now calling the `ClientPaymentController` paths.
5. The tab key `clientProformas` SHALL be **removed** from `types.ts`, `workspaceTabs.ts`, the three
   workspace state property tests and `WorkspaceTabs.tsx` (the badge moves to `clientPayments` and counts
   issued, unpaid payments for the CLIENT and overdue payments for staff). The old URL
   `/projects/{id}/clientProformas` SHALL redirect to `/projects/{id}/clientPayments`, keeping the query
   string (`?proforma=` becomes `?payment=`).
6. The company-wide staff list `/deliveries/proformas` SHALL stay as a section list over `MATERIALS` payments
   and read the same endpoint with `kind=MATERIALS`; its route permission becomes `CLIENT_PAYMENTS` READ.
7. The tab gate SHALL use the seeded code `CLIENT_PAYMENTS` (fixing the unseeded code that gates the tab
   today).

### Requirement 3: The client sees the combined list of upcoming payments

**User Story:** As a client, I want one list of what I am going to pay, so that I do not look in two places.

#### Acceptance Criteria

1. For the role CLIENT the tab `clientPayments` SHALL be visible (label "Payments") and replace the
   `Proformy` tab. It SHALL list, project-scoped to the client's own projects, every `WORKS` tranche of a
   **published** schedule and every `MATERIALS` payment in status ISSUED, PARTIALLY_PAID or PAID, ordered by
   due date, paginated by the same endpoint.
2. The client list item SHALL show kind, number or sequence, due date, amount, paid, remaining, status, the
   **Payee_Account** with a "copy" action, the transfer title (`paymentTitle`) with a "copy" action, and for
   `MATERIALS` "Download PDF" and a line summary (material names and quantities, **without** purchase data).
3. The client DTO SHALL be a **client-safe** type separate from the staff DTO: no position, purchase,
   internal, margin, seller, audit or request field, and no staff-only works fields (`source`,
   `unallocatedCredit`). `ClientDtoLeakGuard` SHALL cover it. The existing guard test that forbids bank data
   in the client proforma DTO SHALL be **deliberately changed** to allow exactly one field,
   `payeeBankAccount`, and to keep rejecting every other bank or payment-internal field.
4. A CLIENT that is not a member of the project SHALL get 404 on the list filter and on a read by id (the
   same rule as `ClientProformaClientService`). A CLIENT SHALL get 403 on every staff action.
5. The deep link `?payment={id}` SHALL scroll to and open the card of that payment.

### Requirement 4: Payee bank account per project

**User Story:** As a manager, I want to set the account the client pays to for each project, so that the
client always sees where to transfer the money.

#### Acceptance Criteria

1. `projects` SHALL get a nullable column `bank_account` (`varchar(34)`, stored without spaces, upper case)
   and `ProjectEntity`, the service models and the DTOs SHALL carry it. The value SHALL be validated as an
   **IBAN** (length per country, mod-97 checksum); an invalid value gives 400
   `error.project.bankAccount.invalid`.
2. The account SHALL be edited by a dedicated endpoint `PUT /api/projects/{id}/bank-account` (body: the IBAN
   or null to clear), guarded by `PROJECTS` UPDATE **and** a service check that the caller is ADMIN or an
   `ACTIVE` MANAGER member of that project (otherwise 403 `error.project.bankAccount.forbidden`). Every
   change SHALL be written to the audit log. The generic project update SHALL ignore the field.
3. The project card (`ProjectFormSheet` and the project header) SHALL show the field "Bank account" with an
   IBAN mask and inline validation; the control SHALL be editable only for ADMIN and the project's MANAGER,
   read-only text for other staff roles.
4. **Effective account:** `effectiveBankAccount` = the project account, else `company_profile.bank_account`,
   else null. The effective account is exposed to staff and, as `payeeBankAccount`, to the CLIENT of the
   project (R3.3). It SHALL **not** be exposed to FOREMAN, BUYER or in any dashboard dataset
   (`SensitiveFieldGuardTest` stays green; the field is not added to any dataset catalog).
5. Every payment-document card (staff drawer and client card) SHALL display the effective account in a
   "Pay to" block; when it is null the card SHALL show a warning "Bank account is not configured" to staff
   and no block to the client.
6. When a `MATERIALS` payment is **issued**, the effective account SHALL be stored in
   `client_payments.bank_account_snapshot`, printed in the PDF and shown on the card from then on; later
   project edits SHALL NOT change an issued document. `WORKS` tranches have no PDF and show the **current**
   effective account.
7. `ProformaPdfService` SHALL no longer fail with 409 `COMPANY_INCOMPLETE` solely for a blank company
   account when the project has its own account; it still fails when **neither** account exists.

### Requirement 5: Estimate-driven finishing positions

**User Story:** As a manager, I want every finishing material of the estimate to appear as a purchase
position, so that I only keep or replace the chosen material and never re-enter the list.

#### Acceptance Criteria

1. A service `EstimateSyncService` SHALL create and maintain one `PurchasePosition` per **finishing row of
   the project's materials list** (the FOR-05-05b read model, aggregated over rooms, quantity =
   `effectiveTotalQty` including reserve, unit = the row unit), sourcing path `AGREED`, kind `FINISHING`,
   status `PLANNED`.
2. Each synced position SHALL carry a stable `estimate_source_key` (unique per project, partial unique index
   where not null) that identifies the estimate row (the chosen material id, or the finishing material type
   for a row with no chosen material), plus `estimate_reference_id` of a representative BoM row. The sync
   SHALL be **idempotent**: a second run creates nothing new.
3. For an estimate row **with a chosen material** the position's `planned_material_id` SHALL be that
   material (a global dictionary material). For a row **with no chosen material** the position SHALL be a
   **Material_Pending_Position**: `planned_material_id` is null, the label is the finishing type name, the
   price band of the estimate is shown as a hint, and the position cannot be sent for approval or put into a
   proforma until a concrete material is chosen (error `error.estimateSync.materialRequired`). The database
   SHALL enforce this with a CHECK: `planned_material_id IS NOT NULL OR (status = 'PLANNED' AND
   estimate_source_key IS NOT NULL)`.
4. The position's work link and dates SHALL come from the existing `WorkCategoryResolver` (work category,
   start of the earliest scheduled work), filling `needByDate` and `orderByDate` as the planner does today;
   a manual `work_category_id` override stays.
5. **Re-sync rules:** while a position is `PLANNED` the sync SHALL update quantity, unit and prices from the
   estimate; from `PROPOSED` onward it SHALL NOT change quantity, material or prices and SHALL only set the
   boolean `estimate_changed` when the estimate now differs, shown as a badge "Estimate changed"; a position
   whose estimate row disappeared SHALL be flagged `estimate_removed` (badge) and never deleted by the sync.
6. The sync SHALL run (a) automatically when the project becomes `ACTIVE` (hook in
   `ProjectActivationService`), and (b) on demand by the button "Refresh from estimate" on the
   `positionApprovals` tab, guarded by `PURCHASE_POSITIONS` CREATE. It SHALL return a summary
   (created, updated, unchanged, flagged). It SHALL be refused for a project that is not `ACTIVE`
   (409 `error.purchasePosition.project.notActive`).
7. Every query that joins `plannedMaterial` SHALL treat a null material (left join) and every DTO SHALL
   carry `materialTypeName` so pending rows are labelled; the planner, kanban, proforma, external-order,
   shipment and approval code paths SHALL reject or skip pending positions explicitly (they are never
   `ACCEPTED`).
8. The sync SHALL validate that the BoM row belongs to the project (closing the gap of
   `requireEstimateReference`) and SHALL NOT create positions for construction-branch rows.

### Requirement 6: Finishing materials leave the materials matrix

**User Story:** As a manager, I want finishing materials managed in one place, so that the materials matrix
keeps only construction materials.

#### Acceptance Criteria

1. `MaterialsMatrixService` SHALL exclude finishing planned rows and the fact lines that match a finishing
   planned material, so `MaterialsMatrixDto` has no finishing group and its grand totals cover construction
   only (design section 5).
2. `MaterialsMatrixPanel` SHALL render the construction branch only: no branch tabs, the dashboard shows the
   construction totals, and `matrixIsEmpty` ignores finishing rows.
3. The tab key `materialsMatrix`, its gate (`PURCHASES` READ), the endpoint and DTO shape SHALL stay;
   `CONSUMPTION_BRANCHES` and the Zod schemas MAY keep accepting `finishing` (never produced by the server).
4. The locale keys `materialsMatrix.tab.finishing*` SHALL be removed from `pl.json` and `ru.json` together,
   keeping `locale-parity.for0605` green; tests that asserted the finishing tab SHALL be rewritten.
5. The design-stage `project-materials` feature (FOR-05-05b) SHALL NOT be touched.

### Requirement 7: Approval table with thumbnail and material replacement

**User Story:** As a manager, I want the approvals table to show a picture of the material and let me
replace it, so that I see what I am sending to the client.

#### Acceptance Criteria

1. The staff table SHALL keep today's columns and add a **thumbnail** column. `StaffApprovalPositionDto`
   SHALL get `thumbnailUrl` (resolved through `ImageStorage.toCdnUrl` from the actual-or-planned material
   photo) and `materialTypeName`; `StaffPositionSource` and `StaffApprovalQueryService` SHALL select the
   photo with the same actual-vs-planned rule as the item fields. A material with no photo SHALL show a
   neutral placeholder.
2. The client DTO SHALL keep its existing `item.photo`; the assembler SHALL return a **CDN URL** (not the raw
   object key) so the card image loads. No new client field is added and the leak guard stays green.
3. A row action "Replace material" SHALL open the existing material picker (project materials dictionary
   with quick-create of a project material and seller) and call the existing variant replace
   (`POST /api/purchase-positions/{id}/variants/replace`) or, for a Material_Pending_Position, set
   `planned_material_id` for the first time (a "choose material" action that is allowed only while the
   status is `PLANNED`). The replaced / chosen material SHALL be stored in the **project** materials
   dictionary when it is a project material.
4. Rows from the estimate SHALL show a badge "From estimate" and the related works (name and start date);
   a Material_Pending_Position SHALL show the badge "Choose material" and its selection checkbox SHALL be
   disabled.
5. The staff view SHALL show the dates `needBy` and `orderBy` and the badge "Order by missed" with the same
   rules as the planner (no new rule).

### Requirement 8: Row selection and contextual bulk actions

**User Story:** As a manager, I want to tick several rows and act on them at once, so that I do not repeat
the same action row by row.

#### Acceptance Criteria

1. Every row of the staff table SHALL have a checkbox (replacing the checkbox that exists only for `SEND`).
   After the first tick the table SHALL show a sticky **selection bar** with the count and the **total
   selected amount** (net, VAT, gross) computed by the server for the ids
   (`POST /api/purchase-positions/selection-total`, extended to every `AGREED` status; a row without a sale
   price counts as unpriced and the bar says "n without price").
2. Only rows in **one status** are selectable: after the first tick, the checkboxes of rows in another status
   SHALL be disabled with a tooltip "Select rows with the same status". Selection SHALL be cleared when the
   status filter or the data changes.
3. The bar SHALL show the action of the Selection_Status:
   - `PLANNED` -> "Send for approval" (opens the existing send dialog for the selected ids; pending
     positions are not selectable);
   - `ACCEPTED` -> "Create proforma" (the existing `POST` that creates a draft `MATERIALS` payment from the
     ids, then opens its editor);
   - `PROPOSED` -> "Mark accepted" and "Cancel" (the same server actions as for one row:
     decide-on-behalf ACCEPT and withdraw);
   - any other status -> no action and the bar says "No bulk action for this status".
4. Two new bulk endpoints SHALL exist: `POST /api/position-approvals/staff/rounds/decide-on-behalf-bulk` and
   `POST /api/position-approvals/staff/rounds/withdraw-bulk`. Each takes a list of items, applies **the
   single-record service method per item in its own transaction** and returns
   `{succeeded: [positionId], failed: [{positionId, errorKey}]}`. The ABAC operations are those of the
   single actions (`APPROVE` and `UPDATE`). A failing item SHALL NOT roll back the others; the UI SHALL show a
   summary toast and keep the failed rows selected.
5. For "Mark accepted" the client's price **seen** SHALL be sent per item (`expectedUnitSalePrice`), so the
   stale-price check of one-row accept applies to each item.
6. The bulk actions SHALL honour each row's `allowedActions` from the server; a row that cannot take the
   action SHALL be reported as failed, not silently skipped.
7. The selection bar SHALL work on mobile (sticky bottom) and be keyboard accessible.

### Requirement 9: Payment link on an external order

**User Story:** As a buyer, I want to attach the supplier's payment link to an order, so that the financier
pays in one click.

#### Acceptance Criteria

1. `external_orders` SHALL get a nullable `payment_link` (`varchar(1000)`), distinct from `order_link` (the
   order in the supplier shop). The value SHALL be an absolute `https` or `http` URL; anything else gives 400
   `error.externalOrder.paymentLink.invalid`.
2. A dedicated endpoint `PUT /api/external-orders/{id}/payment-link` (body: URL or null) SHALL set or clear
   the link while the order is `DRAFT` or `SENT_FOR_PAYMENT` (409 `error.externalOrder.paymentLink.locked`
   once `PAID` or `CANCELLED`). It is guarded by `EXTERNAL_ORDERS` UPDATE and the money-audience check; the
   audit snapshot records the change. The header records of create and update SHALL also accept the field.
3. The buyer editor SHALL show a "Payment link" field, editable until the order is paid. The financier queue
   card SHALL show a "Pay" button that opens the link in a new tab (`rel="noopener noreferrer"`) and the link
   text; non-http(s) values SHALL never be rendered as a link (`safeHttpUrl`).
4. When the link of an order in `SENT_FOR_PAYMENT` is set or changed, `notification.externalOrder.paymentLinkAdded`
   SHALL be emitted to all active FINANCIER users (actor excluded).
5. The link SHALL NOT appear in any DTO of CLIENT, FOREMAN or WORKER, nor in a dashboard dataset.

### Requirement 10: Buyer-added position, unpaid flag and visibility

**User Story:** As a buyer, I want to add a missing item to a purchase myself (for example feet for a
bathtub), so that delivery is not blocked by the client-payment flow.

#### Acceptance Criteria

1. A dedicated endpoint `POST /api/purchase-positions/buyer-added` SHALL create a position with the BUYER's
   input (project, material, quantity, unit, planned purchase price, optional sale price, optional
   `externalOrderId` or `purchaseRequestId` to attach to, note). It is guarded by `PURCHASE_POSITIONS`
   CREATE and SHALL be refused for roles other than BUYER, MANAGER and ADMIN
   (`error.purchasePosition.buyerAdded.forbidden`). The generic create is unchanged.
2. The created position SHALL have `sourcing_path = AGREED`, `added_by_buyer = true`, `client_unpaid = true`
   and SHALL be moved by the **system** to `WITH_BUYER` through a dedicated, flag-guarded edge of
   `PositionTransitionPolicy`; the proforma-paid gate stays **unchanged for every position without the flag**
   (the existing property tests stay green; a new test pins the flagged edge).
3. From `WITH_BUYER` the position SHALL follow the normal tail: external order (`ORDERED`, `SUPPLIER_PAID`),
   shipment (`IN_DELIVERY`), receipt (`PARTIALLY_RECEIVED`, `RECEIVED`). Attaching it to an existing draft
   external order SHALL be possible in the same call.
4. **Unpaid shows as not delivered.** `Delivery_Complete` = `RECEIVED AND NOT client_unpaid`.
   `HideDeliveredFilter` (positions and requests), `PlannerSliceRules` (terminal and late rules, slices),
   `BoardService` (the `RECEIVED` column hides only complete positions), `PurchaseRequestStatusDeriver`
   (COMPLETED) and the upcoming-deliveries and receipt lists SHALL use `Delivery_Complete`. A received but
   unpaid position SHALL stay visible under "hide delivered", carry the badge "Delivered, unpaid", and belong
   to the planner slice `NOT_PAID`. The SQL-equivalence test of the slices SHALL be updated for both sides.
5. **Later payment.** The manager SHALL be able to put a Client_Unpaid position into a **new proforma**:
   `MATERIALS` payment creation SHALL accept positions with `client_unpaid` in statuses `WITH_BUYER` to
   `RECEIVED` (after the manager sets the sale price), without moving the position's status; the issued
   lines freeze quantity, sale price and VAT (the existing freeze rules). When that payment becomes `PAID`,
   the flag SHALL be cleared (a system event listener); cancelling the payment leaves the flag set.
6. **Client notification.** On creation `notification.position.addedByBuyer` SHALL go to the project's CLIENT
   and MANAGER members. The CLIENT notification SHALL carry **no price and no purchase data**: only the
   type key and the deep link. The CLIENT-facing surfaces SHALL show the item name and quantity only after the
   position is in a proforma (no price before that); `PositionPriceVisibility` SHALL keep hiding `salePrice`
   until then.
7. The position SHALL be removable (cancel) by BUYER, MANAGER or ADMIN while it has no shipment; a cancelled
   Client_Unpaid position SHALL no longer count.

### Requirement 11: Quick purchase from paid positions

**User Story:** As a buyer, I want to tick paid positions and get purchase orders in one go, so that I do not
build orders line by line.

#### Acceptance Criteria

1. On the buyer's board and on the planner (BUYER view) the cards and rows in status `WITH_BUYER` SHALL be
   selectable; the selection bar SHALL offer "Create purchase". Selecting positions of **different projects**
   SHALL be blocked in the UI and refused by the server (`error.quickPurchase.mixedProjects`).
2. `POST /api/external-orders/from-positions` SHALL take `positionIds`, optional `sellerId` per position or a
   default seller, an optional shared header (reference, expected dates, delivery cost) and a flag
   `createShipment`. It SHALL group the positions by seller (the material's seller, or the supplied one;
   a position with no seller and none supplied is refused with `error.quickPurchase.sellerRequired`) and
   create **one `DRAFT` external order per seller** with one line per position at the planned purchase price
   (the existing ceiling rule applies and requires the acknowledge flag per line).
3. **Delivery follows payment.** A shipment cannot exist before the supplier is paid (`ShipmentRules` allows
   shipping from `SUPPLIER_PAID`), so the quick purchase creates **orders only** and the flag
   `createShipment` is not part of the request. Instead, the shipment form SHALL **pre-tick every line with
   a remaining quantity** when an external order is selected, and the existing notification
   `notification.externalOrder.paid` SHALL link to `/deliveries/shipments?new=1&order={id}`, which opens the
   form with that order selected and all lines ticked, so creating the delivery is one confirmation. The call
   returns the created order ids.
4. The whole call SHALL be one transaction (all or nothing) and guarded by `EXTERNAL_ORDERS` CREATE; it SHALL
   refuse positions that are not `WITH_BUYER`, already on a live order, or in a project the caller cannot
   reach (404). BUYER does **not** need `PURCHASE_REQUESTS` CREATE.
5. After success the UI SHALL open the first order in the editor (or the list when several) with a toast that
   names the count; the buyer can then add the payment link (R9) and send for payment.

### Requirement 12: Receipt delegation to a project worker

**User Story:** As a manager, I want to hand the receipt of a delivery to a worker on site, so that the
foreman does not have to be there.

#### Acceptance Criteria

1. A new table `goods_receipt_delegations` SHALL store `id`, `shipment_id`, `project_id`, `assignee_id`,
   `delegated_by`, `delegated_at`, `status` (`ACTIVE`, `REVOKED`, `COMPLETED`), `revoked_at`, `revoked_by`,
   `completed_at`; a partial unique index SHALL allow **one `ACTIVE` delegation per shipment**.
   `goods_receipts` SHALL get a nullable `delegation_id`.
2. `POST /api/goods-receipts/delegations` (body `shipmentId`, `assigneeId`) SHALL create or **reassign** the
   delegation. The caller SHALL be ADMIN or an `ACTIVE` MANAGER member of the shipment's project. The
   assignee SHALL be an `ACTIVE` member of **that project** whose project role is `WORKER`; otherwise 400
   `error.goodsReceipt.delegation.assigneeInvalid`. A shipment that is `CANCELLED` or already fully received
   (`DELIVERED` by receipt) SHALL be refused (409). Reassigning revokes the previous delegation.
3. `DELETE /api/goods-receipts/delegations/{id}` SHALL revoke an `ACTIVE` delegation (ADMIN or the project
   MANAGER). `GET /api/goods-receipts/delegations?shipmentId=&assigneeId=&status=` SHALL list delegations
   (paged), project-scoped.
4. The `GOODS_RECEIPTS` resource SHALL gain a **WORKER grant** (`CREATE`, `READ`). A WORKER SHALL be able to
   read and record receipt events **only** for positions of shipments with an `ACTIVE` delegation to him;
   anything else is 404 (scope outside) or 403 (no delegation). The check SHALL live in
   `GoodsReceiptService`, in addition to project scope. FOREMAN, MANAGER, BUYER and ADMIN behaviour is
   unchanged and they keep seeing **all** receipts.
5. `GET /api/goods-receipts/to-receive` for a WORKER SHALL return only the positions and shipments delegated
   to him; the foreman and manager lists SHALL mark delegated shipments with `delegatedTo` (name) and
   `delegationId`.
6. Every receipt event recorded by a Delegate SHALL store `received_by` = the worker (already the case) and
   `delegation_id`; the DTO SHALL expose `receivedByName` and `delegatedByName`, and the staff history and the
   position ledger SHALL show "Accepted by {name} (delegated by {name})".
7. A delegation SHALL become `COMPLETED` when the shipment is closed by receipt (`closeByReceipt`); a revoked
   or completed delegation SHALL remove the shipment from the worker's list at once; events already
   recorded stay and keep their `delegation_id`.
8. **Notifications:** creating or reassigning a delegation emits `notification.receipt.delegated` to the new
   assignee; revoking or reassigning emits `notification.receipt.delegationRevoked` to the previous one; the
   first event recorded under a delegation emits `notification.receipt.acceptedByDelegate`.
9. The WORKER mobile navigation SHALL gain the entry `Odbiór dostaw` (`goodsReceipt`, bottom-bar slot 4 of
   max 5) and the route `/deliveries/receipt` SHALL be allowed for WORKER with `GOODS_RECEIPTS` READ. The
   WORKER's receipt screen SHALL show only delegated items, with the same accept / missing / return dialogs;
   the empty state says "No deliveries delegated to you".
10. The manager UI SHALL offer "Delegate receipt" on the shipment drawer and on the upcoming-deliveries card
    (a worker picker listing only the project's active workers) and "Revoke"; the shipments table SHALL show
    the delegate's name.
11. Delegation SHALL NOT change the ledger formulas, the void rules (author for the window, MANAGER and ADMIN
    always) or the re-delivery behaviour of FOR-11-05.

### Requirement 13: Notifications, deep links and message resolution

**User Story:** As a user, I want a clear notification with a working link for each of these events.

#### Acceptance Criteria

1. The five types of the notification table SHALL be emitted by transactional event listeners
   (`AFTER_COMMIT`, `fallbackExecution = true`) that never throw, exclude the actor, and call
   `NotificationService.create(userId, type, null, link)` (the pattern of `GoodsReceiptNotificationEmitter`).
2. Recipient resolution SHALL use `ACTIVE` project members of the role and `UserDao.findActiveByRoleCode` for
   the FINANCIER; a recipient list that resolves to nobody SHALL emit nothing.
3. Each type key SHALL have a PL and RU message in `pl.json` and `ru.json` and a notification-bell route in
   the frontend deep-link resolver; `notification.receipt.delegationRevoked` has no link and is not clickable.
4. The existing notification links that point to `/projects/{id}/clientProformas?proforma={id}` SHALL be
   changed to `/projects/{id}/clientPayments?payment={id}`; notifications already stored with the old link
   SHALL still resolve through the redirect of R2.5.
5. Existing payment notifications (`notification.payment.*`, `notification.proforma.paid`) SHALL keep their
   types and recipients; only their deep links change. `notification.externalOrder.paid` keeps its type and
   recipients, and its link becomes `/deliveries/shipments?new=1&order={id}` (R11.3).

### Requirement 14: Migrations, ABAC, datasets and managed-entity checklist

**User Story:** As a maintainer, I want the changes shipped as safe, idempotent migrations that follow the
managed-entity rules.

#### Acceptance Criteria

1. The migrations SHALL be new changesets numbered from `247`, each registered **last** in `changelog.xml` in
   sequence, idempotent, with `MARK_RAN` preconditions where a row or column may already exist (final
   numbering in design section 10): the `client_payments` base table with migration of tranches and
   proformas; the `CLIENT_PAYMENTS` resource and merged grants; `projects.bank_account`;
   `purchase_positions` changes (nullable material, `estimate_source_key`, `estimate_changed`,
   `estimate_removed`, `added_by_buyer`, `client_unpaid`, CHECK constraint); `external_orders.payment_link`;
   `goods_receipt_delegations`, `goods_receipts.delegation_id` and the WORKER grant.
2. The new controller SHALL follow the managed-entity checklist: the resource seeded, ADMIN grant, class
   `@PermissionResource` with every handler annotated, `getProjectIdPath()` on the service, dataset or
   exclusion. The delegation endpoints SHALL be annotated with the `GOODS_RECEIPTS` resource.
3. Datasets: `PaymentSchedulesDataset`, `PaymentTranchesDataset` and `ClientProformasDataset` SHALL require
   `CLIENT_PAYMENTS`; new fields (`client_unpaid`, `added_by_buyer`, `kind`) MAY be added to the field
   catalogs; the **bank account and the payment link SHALL NOT** be added; the seeded widget library and the
   role defaults SHALL keep working.
4. The migration of existing data SHALL be verified by a count-and-sum check (number of tranches and
   proformas, `Σ amount_gross`, `Σ paid_gross`) inside the migration integration test.
5. Existing bank links, tranche allocations and proforma lines SHALL keep their ids and totals after the
   migration; `GET /api/bank/transactions/{id}` SHALL still show links for old rows.

### Requirement 15: Frontend structure, routing and i18n

**User Story:** As a user, I want the screens to be consistent and fully localized.

#### Acceptance Criteria

1. The feature folders `features/project-payments` and `features/client-payments` (new) SHALL be merged into
   one `features/client-payments` module (list, filters, drawer, schedule panels, proforma editor, issue and
   cancel dialogs, client cards); `features/client-proformas` SHALL be removed after its parts move.
2. Route permissions SHALL be updated (`/deliveries/proformas`, `/deliveries/proformas/:id` gated on
   `CLIENT_PAYMENTS`), the redirect of R2.5 added, and `WorkspaceTabs.tsx` badge logic moved.
3. All new texts SHALL use i18n keys with **PL/RU parity** (the parity tests of the touched groups stay
   green; `no-raw-key` render tests cover the new views); backend errors come from the messages files.
4. The mobile layouts (selection bar, payments list, worker receipt) SHALL be usable at 390 px width.
5. Frontend changes commit from inside `foremen-frontend/`; specs and backend commit from the workspace
   root (workspace rule).

### Requirement 16: Test cases document

**User Story:** As a QA engineer, I want a test-cases document for this spec.

#### Acceptance Criteria

1. The spec folder SHALL contain `test-cases.md` in Russian following `.kiro/steering/test-cases.md`: API
   tests against the Dockerized app and browser scenarios, grouped by feature, step by step, with a
   repeatability strategy (run-id generator and teardown), a regression group and an MD report template.
2. The cases SHALL cover R1 to R15 and the roles MANAGER, CLIENT, FINANCIER, BUYER, FOREMAN, WORKER and ADMIN.

### Requirement 17: Non-regression

**User Story:** As a maintainer, I want the rest of FOR-11 and FOR-06 to keep working.

#### Acceptance Criteria

1. The proforma-paid gate for `AGREED` positions, the position state machine (except the flagged edge),
   the ledger formulas of FOR-11-05, the shipment rules and the bank matching of FOR-07-04 SHALL behave as
   before for every position and payment without the new flags.
2. Works-schedule behaviour (generation, publish, re-plan, FIFO reconciliation, overdue sweep, online
   payment seam) SHALL be unchanged; only its HTTP surface moves and its storage gains the base row.
3. Existing tests SHALL be updated only where this spec changes behaviour (listed in design section 11);
   all others SHALL pass unchanged.
