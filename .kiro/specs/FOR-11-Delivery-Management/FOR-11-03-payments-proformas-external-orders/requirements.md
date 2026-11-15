# Requirements Document

## Introduction

This spec (**FOR-11-03-payments-proformas-external-orders**) delivers the **money half** of FOR-11
procurement. FOR-11-00 gave the position and its state machine (the `IN_PROFORMA`, `CLIENT_PAID`,
`WITH_BUYER`, `ORDERED`, `SUPPLIER_PAID` stages are SYSTEM-only edges written by this spec), FOR-11-01
gave the planner and the tick-to-proforma total, FOR-11-02 gave client acceptance (`ACCEPTED`). Today
the manager issues a proforma by hand, nobody checks that the client paid in full before buying, the
buyer tracks supplier orders in Bitrix, and the financier learns about supplier invoices by chat.

This spec adds:

1. **Client proforma** (new entity `ClientProforma`): the manager ticks `ACCEPTED` `AGREED` positions of
   one project, the system numbers the proforma, freezes the prices, renders a **PDF** and moves the
   positions to `IN_PROFORMA`. The client sees it read-only.
2. **The proforma-paid gate**: the proforma is paid by **bank-ledger links** (FOR-07-04 extended with a
   new link kind). When the Σ of links reaches the gross total the system moves every position
   `IN_PROFORMA -> CLIENT_PAID -> WITH_BUYER` and the order appears on the buyer side. Until then nothing
   moves on.
3. **External order** (new entity `ExternalOrder`): the buyer groups positions into one or several
   supplier orders per seller, with partial quantities, purchase prices (ceiling = sale price), delivery
   cost allocated to positions, expected dates and linked supplier invoices/proformas
   (`ProcurementDocument`). The buyer sends the order **for payment** to the financier.
4. **Financier hand-off**: the financier sees a queue, marks an order paid by hand or returns it to the
   buyer with a reason. A linked **outgoing bank transaction** also marks it paid and **the ledger wins**.
5. **FOR-07-04 extension**: two new link kinds (`PROFORMA_PAYMENT`, `EXTERNAL_ORDER_PAYMENT`), forward
   (bank line to target) and reverse (new target to open bank lines) matching, suggestions, accept/search.
6. **Planner extension** (reserved by FOR-11-01): grouping by **proforma** and **external order**.
7. **Lists**: client proformas and our external orders (supplier-side, with their invoices).
8. **Notifications** through the generic service (emit-only).

### Correction to the OVERVIEW (verified in the code)

The OVERVIEW describes the proforma as "the same generated document used today for client payments".
**No such generated document exists in FOR-06-07**: the only generator is `TrancheGenerator`, a pure
splitter of a total into installments. The product owner decided (this session, **option 1**):
**a separate `ClientProforma` entity with its own lines, numbering and PDF (rendered through the existing
`PdfRenderer`)**, paid through a new ledger link kind. The works payment schedule
(`PaymentSchedule` / `PaymentTranche`, one per project, based on the approved offer total) and its
`ReconciliationEngine` are **not changed**, so material money never mixes with works money.

### Product decisions this spec implements (from `../OVERVIEW.md`, revision 5, and the product owner)

- **Hard gate:** until the client has paid the proforma **in full**, nothing moves on (`AGREED` path).
  `PACKAGE` and `CLIENT_OWN` positions skip the proforma (FOR-11-00 manual edges).
- **Goods are bought at VAT 23% and sold at VAT 8%** (any rates from the VAT dictionary); the **final
  invoice is issued in the external accounting program** (out of scope). A proforma is not a VAT invoice.
- **Purchase price is never shown to the client.** The client sees proforma lines at the **sale price**
  only.
- **One request can become several external orders / shops / deliveries / invoices;** even one shop may
  need two orders. A position may be split across several external orders by quantity.
- **The buyer buys not above the sold price** (the sale price is the ceiling); exceeding it needs an
  explicit acknowledgement.
- **Delivery cost is allocated to positions proportionally to their purchase cost**, for margin.
- **External order "paid"** is set by the financier by hand **or** automatically by the linked outgoing
  bank transaction; **the ledger link wins**.
- **Single currency (PLN)**, net unit prices, as in FOR-11-00.
- **Bitrix is replaced;** no migration of existing cards.

### Scope boundaries

- **In scope:** the `ClientProforma` and `ExternalOrder` domains, PDF, numbering, the gate and release,
  the ledger extension (schema, link effects, matching, suggestions), financier hand-off, delivery-cost
  allocation, position stage synchronisation (`ORDERED`, `SUPPLIER_PAID`, reversions), planner grouping,
  datasets, ABAC resources and grants, five notifications, frontend (staff, client, buyer, financier,
  ledger dialogs), locale keys, tests and `test-cases.md`.
- **Out of scope:**
  - shipments, carrier tracking, delivery dates from carriers (FOR-11-04); goods receipt, quantity
    ledger, returns (FOR-11-05); a position becomes `IN_DELIVERY` only through FOR-11-04;
  - the final VAT invoice, accounting-program integration, credit notes;
  - online card payment of a proforma (the ledger is the only payment source);
  - construction-material purchases (FOR-06-03/04/05 flow is unchanged);
  - reminders or automatic actions for overdue proformas (a derived `overdue` flag only);
  - changes to the works `PaymentSchedule` and `ReconciliationEngine`;
  - the amendment flow for a price change after `IN_PROFORMA` (FOR-06-08); such changes are rejected;
  - multi-currency; e-mail/SMS delivery of the proforma (the PDF is downloadable; sending is future work).

### Notifications (confirmed, emit-only)

This spec **only emits** notifications through the generic notification service (in-app bell) and
implements no delivery channel. The five types below come from the OVERVIEW candidate list confirmed by
the product owner. Message text is the frontend title of the type (`t(type)`, PL/RU in Requirement 25);
the body is `null`. A notification is clickable because every type carries a deep link.

| Type | Recipient | Trigger | Deep link | PL title | RU title |
|------|-----------|---------|-----------|----------|----------|
| `notification.proforma.paid` | ACTIVE `MANAGER` members of the project | the proforma reached `PAID` (Σ of ledger links >= gross total) | `/projects/{projectId}/clientProformas?proforma={id}` | Proforma opłacona | Проформа оплачена |
| `notification.order.releasedToBuyer` | the assigned buyer of each `PurchaseRequest` of the released positions; all active `BUYER` users when no position has a request or no request has a buyer | positions of a paid proforma moved to `WITH_BUYER` | `/deliveries/external-orders?project={projectId}&proforma={id}` | Zamówienie przekazane zakupowcowi | Заказ передан закупщику |
| `notification.externalOrder.sentForPayment` | all active `FINANCIER` users | the buyer sent an external order for payment | `/deliveries/external-orders?order={id}` | Zamówienie do opłacenia | Заказ на оплату |
| `notification.externalOrder.paid` | the order's `assignedBuyer` (fallback: its creator, then all active `BUYER` users) | the order became `PAID` (by the financier or by the ledger) | `/deliveries/external-orders?order={id}` | Zamówienie opłacone, uzupełnij dostawę | Заказ оплачен, добавьте доставку |
| `notification.invoice.matchProposed` | all active `FINANCIER` users | a bank transaction received a proposed match to a proforma or an external order | `/bank-transactions?transaction={transactionId}` | Propozycja dopasowania płatności | Предложено сопоставление платежа |

Rules shared by all five: a recipient is notified at most once per event; **the actor is never notified**
(for example the financier who marked the order paid does not receive `externalOrder.paid`);
every emission is wrapped so a failure is logged and never rolls back or blocks the triggering operation.
Returning an order to the buyer and cancelling a proforma emit no notification (visible in the lists).

### i18n decisions

- **Backend entity fields requiring i18n:** only the seeded `resources` rows of the two new resources
  (`name_ru`/`name_pl`, `description_ru`/`description_pl`). `ClientProforma`, `ExternalOrder` and their
  lines, notes, reasons, references and links carry **user data**: no RU/PL variants. Material names,
  seller names and units come from existing dictionaries through the existing locale resolver. No new
  `roles` or `delivery_statuses` rows are seeded (the stage rows exist from FOR-11-00).
- **Backend messages:** every new `error.proforma.*` and `error.externalOrder.*` key exists in
  `messages.properties` (PL, default) and `messages_ru.properties` (RU), checked by a parity test.
- **Proforma PDF text:** rendered from a fixed template in the language of the request (`pl` default,
  `ru` optional) using a backend dictionary of PDF labels (design, "PDF labels").
- **Frontend:** every new string is a key in both `pl.json` and `ru.json` at parity: groups
  `clientProformas.*`, `externalOrders.*`, `ledgerLink.proforma*` / `ledgerLink.externalOrder*`,
  `purchasePlanner.group.*` additions, the tab label `workspace.tab.clientProformas`, the nav labels and
  five `notification.*` titles (all listed with values in the design). Enum labels (proforma status,
  external-order status, paid source) are frontend keys.

## Glossary

- **Proforma** / **ClientProforma**: our advance-payment document to the client for a set of positions.
- **Proforma_Line**: one position of a proforma, with a frozen snapshot of item, quantity and sale price.
- **Draft / Issued**: a proforma without / with a number, PDF and `IN_PROFORMA` positions.
- **Paid_Gross**: Σ of `PROFORMA_PAYMENT` link amounts on the proforma.
- **Release**: the automatic move of all positions of a fully paid proforma to `WITH_BUYER`.
- **External_Order** / **ExternalOrder**: a supplier-side order created by the buyer for one seller and
  one project; the financier pays it.
- **External_Line**: one position (partial or full quantity) of an external order, with purchase price.
- **Coverage**: Σ of External_Line quantities of one position over non-cancelled, non-draft orders.
- **Ceiling**: the position's sale net unit price; the buyer buys at or below it.
- **Payable_Gross**: Σ External_Line gross + delivery cost gross of an external order.
- **Supplier_Document**: an existing `ProcurementDocument` (invoice/proforma of the seller) linked to an
  external order.
- **Ledger_Link**: a `BankTransactionLink` of FOR-07-04.
- **Paid_Source**: `FINANCIER` (by hand) or `LEDGER` (by links) on a paid external order.
- **Financier_Queue**: external orders in `SENT_FOR_PAYMENT`, company-wide.
- **Staff**: `ADMIN`, `MANAGER`, `BUYER` (the `STAFF_FULL` audience of FOR-11-00); `FINANCIER` is
  money-privileged for external orders and proforma payments only.
- **Today**: the current date in `Europe/Warsaw`.

## Requirements

### Requirement 1: ABAC resources and grants

**User Story:** As an administrator, I want separate permission resources for proformas and external
orders, so that the client sees only proformas and the financier sees only what must be paid.

#### Acceptance Criteria

1. THE system SHALL seed the resources `CLIENT_PROFORMAS` and `EXTERNAL_ORDERS` (RU/PL name and
   description) in changeset `239-seed-proforma-external-order-resources.xml`, idempotent (`MARK_RAN`
   precondition per resource), registered last in `changelog.xml`.
2. THE grants SHALL be: `CLIENT_PROFORMAS`: `ADMIN` CREATE/READ/UPDATE/DELETE; `MANAGER`
   CREATE/READ/UPDATE/DELETE; `BUYER` READ; `FINANCIER` READ; `CLIENT` READ. `EXTERNAL_ORDERS`: `ADMIN`
   CREATE/READ/UPDATE/DELETE/APPROVE; `BUYER` CREATE/READ/UPDATE/DELETE; `MANAGER` READ; `FINANCIER`
   READ and APPROVE. `FOREMAN`, `WORKER` and `ESTIMATOR` SHALL have no grant on either resource; `CLIENT`
   SHALL have no grant on `EXTERNAL_ORDERS`.
3. THE operation `APPROVE` already exists; on `EXTERNAL_ORDERS` it means "mark paid / return to buyer".
4. THE `FINANCIER` role SHALL receive only the grants of 1.2, `NOTIFICATIONS` (same rows as `BUYER` from
   changeset 230, inserted only when missing), and the reference READ needed to render the queue
   (`MATERIAL_SELLERS`, `VAT_RATES`, `MEASUREMENT_UNITS`, inserted only when missing). It SHALL receive
   no grant on `PURCHASE_POSITIONS`, `PURCHASE_REQUESTS` or `PROJECT_MATERIALS`.
5. GRANTS SHALL use the same idempotent `NOT EXISTS` pattern as changeset 229.
6. EVERY handler of the new controllers SHALL carry an explicit `@RequiresPermission(resource, op)`; no
   controller SHALL carry a class-level `@PermissionResource` without a matching `@PermissionOperation`
   (`PermissionAnnotationValidator`).

### Requirement 2: Proforma model

**User Story:** As a manager, I want a stored proforma with frozen lines, so that what the client was
asked to pay never changes silently.

#### Acceptance Criteria

1. THE system SHALL map `ClientProformaEntity` (`client_proformas`, extends `BaseEntity`) with:
   `project` (required, immutable), `number` (nullable until issued, unique when present), `status`,
   `currency` (fixed `PLN`), `issueDate`, `dueDate` (nullable), `note` (<= 1000), `totalNet`, `totalVat`,
   `totalGross` (scale 2), `paidGross` (scale 2, default 0), `paidAt`, `releasedAt`, `pdfObjectKey`
   (<= 500), `pdfLanguage` (`pl`|`ru`), `cancelledAt`, `cancelReason` (<= 500), `issuedBy` (user).
2. THE system SHALL map `ClientProformaLineEntity` (`client_proforma_lines`) with: `proforma`,
   `position` (required), `lineNo`, `material` (reference), `description` (<= 500, frozen item name),
   `sku`, `quantity` (scale 3), `unitName` (<= 50), `unitSaleNet` (scale 2), `vatRate` (reference) and
   `vatRatePercent` (frozen), `net`, `vat`, `gross` (scale 2), `active` (boolean, default true).
3. `status` SHALL be one of `DRAFT`, `ISSUED`, `PARTIALLY_PAID`, `PAID`, `CANCELLED`, stored as `VARCHAR`.
   A pure `ProformaStatusMachine` SHALL define: `DRAFT -> ISSUED | CANCELLED`; `ISSUED <-> PARTIALLY_PAID`;
   `ISSUED | PARTIALLY_PAID -> PAID`; `PAID -> PARTIALLY_PAID` only before release (never after, see
   Requirement 12.6); `ISSUED -> CANCELLED` only while `paidGross = 0`. An illegal change SHALL be 409
   `error.proforma.status.notAllowed`.
4. A **partial unique index** on `client_proforma_lines (position_id) WHERE active` SHALL guarantee a
   position belongs to at most one live proforma (draft or issued). Lines of a cancelled proforma SHALL
   be set `active = false`.
5. THE number SHALL have the form `PF/{yyyy}/{seq:6}` where `seq` comes from a database sequence
   `client_proforma_number_seq` (global, gap-tolerant), assigned at issue.
6. A derived `overdue` flag SHALL be true when `dueDate < Today` and `status` is `ISSUED` or
   `PARTIALLY_PAID`; it SHALL NOT be persisted and SHALL trigger no automation.
7. EVERY write SHALL be audited through the existing audit mechanism of `AdminService`.
8. THE changeset `236-create-client-proformas.xml` SHALL create both tables and the sequence,
   idempotently, with indexes on `project_id`, `status`, `number` (unique partial), `(proforma_id)` on
   lines and `(position_id)` on lines.

### Requirement 3: Creating and editing a draft

**User Story:** As a manager, I want to tick accepted positions and see the proforma total before I
issue it.

#### Acceptance Criteria

1. `POST /api/client-proformas` with `{projectId, positionIds[1..200], dueDate?, note?}` SHALL create a
   `DRAFT` and return it with live lines and totals.
2. EVERY position SHALL belong to the given project, have `sourcingPath = AGREED`, `status = ACCEPTED`,
   a non-null `salePrice` and `saleVatRate`, and no active proforma line; otherwise 409 with the matching
   key (`error.proforma.position.projectMismatch`, `.notAgreed`, `.notAccepted`, `.noSalePrice`,
   `.alreadyInProforma`), naming the first offending position id.
3. THE project SHALL be in execution (`IN_PROGRESS` as in FOR-11-00 R9); otherwise 409
   `error.proforma.project.notActive`.
4. LINE amounts SHALL be computed with the FOR-11-01 `PositionMoneyMath.saleLine(unitNet, quantity,
   vatRatePercent)` and totals with `PositionMoneyMath.sum`, so the proforma gross equals the planner
   selection total for the same positions (a test asserts equality).
5. `PUT /api/client-proformas/{id}` SHALL update `dueDate`, `note` and the **set of positions** of a
   `DRAFT` only (add and remove), re-validating added positions; any other status SHALL be 409
   `error.proforma.notDraft`. A draft SHALL keep at least one position.
6. `DELETE /api/client-proformas/{id}` SHALL delete a `DRAFT` (lines cascade, positions untouched);
   other statuses SHALL be 409 `error.proforma.notDraft`.
7. A draft SHALL NOT change any position status; its lines claim the positions (Requirement 2.4) so the
   planner and other drafts cannot take them.
8. THE planner SHALL be able to start a draft from its selection (Requirement 18.3).

### Requirement 4: Issuing a proforma

**User Story:** As a manager, I want one action that fixes prices, numbers the document and starts the
payment wait.

#### Acceptance Criteria

1. `POST /api/client-proformas/{id}/issue` SHALL, in one transaction: re-validate every position as in
   Requirement 3.2, **re-snapshot** every line from the current position data, recompute totals, assign
   the number (Requirement 2.5), set `issueDate = Today`, `status = ISSUED`, `issuedBy`, render and store
   the PDF, and move every position `ACCEPTED -> IN_PROFORMA` through
   `PositionLifecycleService.transition(id, IN_PROFORMA, SYSTEM)`.
2. IF `dueDate` is null THEN the system SHALL set `dueDate = issueDate + foremen.proforma.default-due-days`
   (default 7). `dueDate` SHALL NOT precede `issueDate` (400 `error.proforma.dueDate.invalid`).
3. THE total gross SHALL be > 0; otherwise 409 `error.proforma.total.zero`.
4. THE PDF SHALL be rendered from an XHTML template through `PdfRenderer` and stored in the existing
   object storage; failure of rendering or storage SHALL roll the whole issue back
   (`error.proforma.pdf.failed`, 502).
5. THE PDF SHALL contain: seller (company profile: name, address, NIP, **bank account**), buyer (the
   project's client display name and address as available, otherwise the project name), number, issue
   date, due date, a payment title line `Proforma {number}` for the transfer, lines (name, SKU, quantity,
   unit, unit net, VAT %, net, VAT, gross), totals by VAT rate, total gross in words-free numeric form, and
   the note **"Proforma is not a VAT invoice."** in the document language. It SHALL contain **no
   purchase-side value**. IF the company profile lacks a bank account THEN issue SHALL fail with 409
   `error.proforma.companyProfile.incomplete`.
6. FROM `ISSUED` onward the positions in the proforma SHALL reject changes to `quantity`, `salePrice`,
   `saleVatRate`, `plannedMaterial`/`actualMaterial` (409 `error.proforma.position.locked`), and the FOR-11-02
   analog/proposal flow SHALL already be blocked from `IN_PROFORMA` (verified, not changed).
7. THE issued PDF SHALL NOT be regenerated; `GET /api/client-proformas/{id}/pdf` SHALL stream the stored
   bytes (`application/pdf`, `Content-Disposition: inline; filename="{number with / replaced by -}.pdf"`).
8. THE system SHALL publish `ProformaIssuedEvent(proformaId)` after commit so reverse matching starts
   (Requirement 10.3).

### Requirement 5: Cancelling a proforma

**User Story:** As a manager, I want to cancel an unpaid proforma and free its positions.

#### Acceptance Criteria

1. `POST /api/client-proformas/{id}/cancel {reason}` SHALL accept `DRAFT` or `ISSUED` with `paidGross = 0`
   and no Ledger_Link; a reason (1..500) is required for `ISSUED`.
2. FOR `ISSUED` the system SHALL set `status = CANCELLED`, `cancelledAt`, `cancelReason`, lines
   `active = false`, and move every position `IN_PROFORMA -> ACCEPTED` (SYSTEM); the PDF stays stored.
3. A proforma with any `PROFORMA_PAYMENT` link or `paidGross > 0` SHALL NOT be cancellable (409
   `error.proforma.cancel.hasPayments`); the user must first unlink the payment in the ledger while the
   proforma is not released.
4. A `CANCELLED` proforma SHALL be terminal; a replacement is a new draft.

### Requirement 6: Client-safe proforma surface

**User Story:** As a client, I want to see and download the proformas addressed to me and nothing else.

#### Acceptance Criteria

1. THE system SHALL expose `GET /api/client-proformas/client/projects/{projectId}`, `GET
   /api/client-proformas/client/{id}` and `GET /api/client-proformas/client/{id}/pdf`, each guarded by
   `@RequiresPermission(CLIENT_PROFORMAS, READ)` and available only to an ACTIVE `CLIENT` member of the
   proforma's project (others: 404, no existence leak).
2. THE client SHALL see only `ISSUED`, `PARTIALLY_PAID`, `PAID` and `CANCELLED` proformas (never `DRAFT`).
3. THE client DTO `ClientProformaClientDto` and `ClientProformaLineClientDto` SHALL contain only: number,
   status, issue date, due date, `overdue`, currency, totals, `paidGross`, `remainingGross`, line item
   name, SKU, quantity, unit, unit net, VAT %, net, VAT, gross, and the payment title. They SHALL contain
   **no** purchase price, seller, margin, delivery cost, position status, order dates, request, external
   order, or internal id other than the proforma id.
4. A reflection **leak guard** test SHALL assert that no field of the client DTOs matches the Internal_Field
   list (`purchase*`, `seller*`, `internal*`, `margin*`, `deliveryCost*`, `orderBy*`, `needBy*`,
   `request*`, `externalOrder*`, `positionStatus`), and a sentinel JSON test SHALL prove sentinel purchase
   values never appear in any client response (same technique as FOR-11-02 R4.8).
5. THE staff endpoints (`/api/client-proformas`, `/{id}`) SHALL refuse a `CLIENT` (403 by ABAC and by a
   role re-check in the controller mapper).
6. THE client SHALL NOT call any `/api/external-orders`, `/api/purchase-*` or `/api/procurement/*`
   endpoint (R23.9).

### Requirement 7: Ledger schema and API extension (FOR-07-04)

**User Story:** As a financier, I want to link a bank line to a proforma or to an external order the same
way I link it to anything else.

#### Acceptance Criteria

1. `LinkKind` SHALL gain `PROFORMA_PAYMENT` (target a proforma, **credit** lines only) and
   `EXTERNAL_ORDER_PAYMENT` (target an external order, **debit** lines only); the existing kinds and
   their direction rules SHALL be unchanged.
2. THE changeset `238-extend-bank-links-for-proforma-external-order.xml` SHALL add nullable columns
   `client_proforma_id` and `external_order_id` (with FKs and indexes) to `bank_transaction_links` and
   `bank_transaction_suggestions`, and SHALL **drop and recreate** the per-kind CHECK constraints
   (`ck_bank_transaction_links_kind`, `ck_bank_transaction_links_kind_target` and the equivalents on
   suggestions and counterparty rules) so the new kinds require exactly their own target column and
   leave foreign targets empty. It SHALL be idempotent and registered after 237.
3. `LinkRequest` SHALL gain `clientProformaId` and `externalOrderId`; `BankLinkService` SHALL resolve,
   validate and persist them, and `with*` helpers SHALL be extended.
4. LINK validation SHALL: require the target to exist and be linkable (proforma `ISSUED` or
   `PARTIALLY_PAID`; external order per 7.5); require the transaction
   currency `PLN` (409 `error.bank.link.currencyMismatch`); cap the amount by the line remainder and by the
   target remainder (proforma: `totalGross - paidGross`; external order: `payableGross - Σ
   EXTERNAL_ORDER_PAYMENT links`), using the existing `overAllocated` / `targetOverLinked` errors.
5. AN external order SHALL be linkable in `SENT_FOR_PAYMENT` and in `PAID` with `paidSource = FINANCIER`
   (so the ledger can confirm a hand-marked order); `DRAFT` and `CANCELLED` orders SHALL be rejected
   (`error.externalOrder.link.notLinkable`).
6. THE ledger DTOs (`BankTransactionDto` links and suggestions) SHALL expose the proforma number or the
   external-order id with seller name for display; they SHALL NOT expose purchase prices.
7. THE new kinds SHALL appear in the ledger filters and `linkStatus` derivation exactly as existing kinds
   (`LinkStatusDeriver` counts their amounts).
8. A **removed** link SHALL trigger the reverse effect of Requirements 12 and 14.5 (design: new
   `LinkEffects.onRemoved`).

### Requirement 8: Proforma payment effect

**User Story:** As a manager, I want the proforma status to follow the money.

#### Acceptance Criteria

1. AFTER a `PROFORMA_PAYMENT` link is created, removed or its amount replaced, the system SHALL recompute
   `paidGross = Σ link amounts` in the same transaction and set `status` by the pure
   `ProformaStatusDeriver`: `0 -> ISSUED`, `0 < paid < total -> PARTIALLY_PAID`, `paid >= total -> PAID`
   (cancelled and draft proformas are never touched).
2. WHEN the status becomes `PAID` for the first time the system SHALL set `paidAt` and run the **Release**
   (Requirement 9) in the same transaction.
3. A `PROFORMA_PAYMENT` link SHALL be **locked** (cannot be removed or replaced) once `releasedAt` is set;
   attempts SHALL be 409 `error.bank.link.locked` (existing key).
4. THE link amount SHALL never exceed the proforma remainder, so `paidGross <= totalGross`.
5. THE recompute SHALL be idempotent and safe under concurrent links (pessimistic lock on the proforma row).

### Requirement 9: The gate and release

**User Story:** As the business owner, I want purchases to start only after the client has paid in full.

#### Acceptance Criteria

1. THE **Release** SHALL, for every position of the proforma, call
   `PositionLifecycleService.transition(id, CLIENT_PAID, SYSTEM)` and then `transition(id, WITH_BUYER,
   SYSTEM)`; set `releasedAt`; and publish `ProformaPaidEvent(proformaId, positionIds)`.
2. THE gate rule SHALL remain only in `PositionTransitionPolicy` (FOR-11-00): this spec SHALL NOT bypass
   it; a position not in `IN_PROFORMA` at release time (for example cancelled by hand) SHALL be skipped
   and listed in the audit note.
3. A **partial** payment SHALL change no position status.
4. THE Release SHALL be idempotent: a second run (replay, concurrent link) SHALL change nothing once
   `releasedAt` is set.
5. WHEN the Release finishes the positions SHALL appear in the FOR-11-01 buyer kanban column `WITH_BUYER`
   without any further action.
6. A paid proforma SHALL never be un-released: removing the payment after `releasedAt` is blocked
   (Requirement 8.3).

### Requirement 10: Matching bank lines to proformas and external orders

**User Story:** As a financier, I want the system to propose the right proforma or order for a bank line
and the right bank line for a new document.

#### Acceptance Criteria

1. **Forward matching** (`DetectionService`): for a credit line the candidate set SHALL include proformas
   in `ISSUED`/`PARTIALLY_PAID`; for a debit line, external orders in `SENT_FOR_PAYMENT` (and `PAID` by
   `FINANCIER`). `TargetType` SHALL gain `CLIENT_PROFORMA` and `EXTERNAL_ORDER`.
2. THE pure `RuleMatcher` SHALL gain reason codes: `PROFORMA_NUMBER` (the title contains the number),
   `PROFORMA_AMOUNT` (amount equals the proforma remainder), `PROFORMA_CLIENT` (counterparty matches a
   CLIENT member as in the existing `CLIENT_MATCH`), `EXTERNAL_ORDER_AMOUNT` (amount equals the payable
   remainder), `EXTERNAL_ORDER_SELLER` (counterparty name or IBAN matches the seller or its documents),
   `EXTERNAL_ORDER_DOCUMENT` (title contains the invoice number of a linked Supplier_Document). Scores and
   thresholds follow the existing `MatchThresholds`; a number or document hit SHALL score at least
   as high as `INVOICE_NUMBER`.
3. **Reverse matching:** `ProformaIssuedEvent`, the transition of an external order to `SENT_FOR_PAYMENT`
   and a change of its linked documents SHALL start `ReverseProformaMatcher` /
   `ReverseExternalOrderMatcher` (modelled on `ReverseDocumentMatcher`), which look at unlinked or
   partially linked ledger lines in the date window of the existing provider and write suggestions.
4. SUGGESTIONS SHALL be created through `BankLinkService.replaceSuggestions/appendSuggestions` and accepted
   or rejected through the existing confirm/reject endpoints and UI; `applyAuto` MAY auto-apply a
   `PROFORMA_NUMBER` + exact amount match only when the existing auto-apply threshold allows it.
5. THE ledger **search** (manual target pick) SHALL find proformas by number, project, client name or
   amount, and external orders by id, seller, reference number, invoice number or amount.
6. WHEN a suggestion for the new kinds is written for a transaction the system SHALL publish
   `LedgerMatchProposedEvent(transactionId)` (once per transaction per detection run) for the notification
   of Requirement 19.
7. THE existing detection, reverse matching and the AI classifier SHALL keep their behavior for existing
   kinds; the AI catalog SHALL not receive proforma or external-order data.

### Requirement 11: External order model

**User Story:** As a buyer, I want one record per supplier order with its positions, prices and dates.

#### Acceptance Criteria

1. THE system SHALL map `ExternalOrderEntity` (`external_orders`, extends `BaseEntity`) with: `project`
   (required, immutable), `purchaseRequest` (nullable), `seller` (required, `MaterialSellerEntity`),
   `status`, `referenceNumber` (<= 100, the seller's order number), `orderLink` (<= 1000), `deliveryCostNet`
   (scale 2, default 0), `deliveryVatRate` (nullable reference), `expectedShipDate`,
   `expectedDeliveryDate`, `assignedBuyer` (nullable user, default the creator), `note` (<= 2000),
   `returnReason` (<= 500), `sentAt`, `paidAt`, `paidSource` (`FINANCIER`|`LEDGER`), `paidBy` (user),
   `paidNote` (<= 500), `cancelledAt`.
2. `ExternalOrderLineEntity` (`external_order_lines`): `externalOrder`, `position` (required), `quantity`
   (> 0, scale 3), `unitPurchaseNet` (scale 2, >= 0), `purchaseVatRate` (nullable), `deliveryCostShare`
   (scale 2, default 0), `aboveCeilingAcknowledged` (boolean). Unique `(external_order_id, position_id)`.
3. `ExternalOrderDocumentEntity` (`external_order_documents`): `externalOrder`, `procurementDocument`,
   `amount` (nullable, scale 2, the portion of the document total attributed to this order). Unique pair.
4. `status` SHALL be `DRAFT`, `SENT_FOR_PAYMENT`, `PAID`, `CANCELLED`. A pure `ExternalOrderStatusMachine`
   SHALL define: `DRAFT -> SENT_FOR_PAYMENT | CANCELLED`; `SENT_FOR_PAYMENT -> PAID | DRAFT (return) |
   CANCELLED`; `PAID -> SENT_FOR_PAYMENT` only by Requirement 16.4; `CANCELLED` terminal. An illegal change
   SHALL be 409 `error.externalOrder.status.notAllowed`.
5. DERIVED, not persisted: `linesNet`, `linesVat`, `linesGross`, `deliveryGross`, `payableGross`,
   `ledgerPaidGross` (Σ `EXTERNAL_ORDER_PAYMENT` links), `documentsPaid` (Requirement 16.2), `overdue`
   (`expectedDeliveryDate < Today` and status not `CANCELLED`).
6. THE table and indexes SHALL be created by `237-create-external-orders.xml` (idempotent), with indexes on
   `project_id`, `seller_id`, `status`, `assigned_buyer_id`, `purchase_request_id`, and on the line and
   document foreign keys.
7. EVERY write SHALL be audited; purchase prices SHALL be excluded from any audit snapshot readable by a
   non-staff audience.

### Requirement 12: Lines, quantity coverage and the ceiling

**User Story:** As a buyer, I want to split a position across orders without over-ordering.

#### Acceptance Criteria

1. `POST /api/external-orders` with `{projectId, sellerId, purchaseRequestId?, referenceNumber?, orderLink?,
   expectedShipDate?, expectedDeliveryDate?, deliveryCostNet?, deliveryVatRateId?, note?, lines[]}` SHALL
   create a `DRAFT`; `PUT /{id}` SHALL replace the header fields and `PUT /{id}/lines` SHALL replace all
   lines, both only in `DRAFT` (409 `error.externalOrder.notDraft`).
2. A line SHALL reference a position of the order's project whose `status` is `WITH_BUYER` and whose path
   is `AGREED` or `PACKAGE` (`CLIENT_OWN` is rejected: `error.externalOrder.position.notPurchasable`).
3. THE pure `ExternalOrderCoverageValidator` SHALL ensure, per position, that Σ quantity over lines of
   **all non-cancelled orders including this draft** is `<= position.quantity` (3 decimals); violation is
   409 `error.externalOrder.line.overCoverage`.
4. IF `unitPurchaseNet > position.salePrice` THEN the line SHALL require `aboveCeilingAcknowledged = true`
   (otherwise 409 `error.externalOrder.line.aboveCeiling`); the DTO SHALL expose `aboveCeiling` per line
   and the UI SHALL show a warning marker. The ceiling check SHALL use net unit prices.
5. `purchaseVatRate` SHALL default to the position's `purchaseVatRate`, then to the dictionary rate with
   the 23% value when present.
6. THE system SHALL NOT allow the seller to be changed once the order is `SENT_FOR_PAYMENT`.
7. `DELETE /api/external-orders/{id}` SHALL delete a `DRAFT`; other statuses SHALL be 409.

### Requirement 13: Delivery cost allocation

**User Story:** As a manager, I want delivery cost reflected in each position's cost, so that margin is
right.

#### Acceptance Criteria

1. ON every save of lines or `deliveryCostNet` of a non-cancelled order the system SHALL set each line's
   `deliveryCostShare = DeliveryCostAllocator.allocate(deliveryCostNet, [purchase net of each line])`
   (FOR-11-01), so the shares sum **exactly** to `deliveryCostNet`.
2. THE position's `deliveryCostShare` SHALL equal Σ of `deliveryCostShare` over its lines in orders in
   `SENT_FOR_PAYMENT` or `PAID` (committed orders; drafts and cancelled orders excluded), recomputed by
   `PositionOrderSyncService` (Requirement 17).
3. THE position's `actualPurchasePrice` SHALL equal the quantity-weighted average of
   `unitPurchaseNet` over the same committed lines (scale 2, `HALF_UP`), and `actualMaterial` SHALL not be
   changed by this spec.
4. `deliveryCostNet` SHALL be >= 0; a VAT rate SHALL be required when `deliveryCostNet > 0` and
   `deliveryVatRate` null falls back to the 23% rate (same rule as 12.5).
5. THE margin calculators of FOR-11-01 and FOR-06-06 SHALL pick up the changed fields without code
   changes (verified by an integration test).

### Requirement 14: Supplier documents

**User Story:** As a buyer, I want to attach the seller's invoice or proforma to the order so the financier
pays against a document.

#### Acceptance Criteria

1. `PUT /api/external-orders/{id}/documents` with `[{procurementDocumentId, amount?}]` SHALL replace the
   links, in `DRAFT` and `SENT_FOR_PAYMENT`; a `PAID` or `CANCELLED` order SHALL be 409
   `error.externalOrder.documents.locked`.
2. A document SHALL be an existing `ProcurementDocument` that the caller may read (FOR-06-03/04 scope; a
   company-level document with null project is allowed); the buyer MAY upload a new document through the
   existing upload endpoint first and link it by id. The system SHALL NOT duplicate upload or OCR.
3. `amount`, when given, SHALL be > 0 and the Σ of amounts of one document across all external orders
   SHALL NOT exceed its `documentTotal` (409 `error.externalOrder.documents.overAttributed`).
4. A document with a `supplierNip` that differs from the seller's `nip` (both present) SHALL be linkable
   but the DTO SHALL flag `sellerMismatch = true` and the UI SHALL warn.
5. WHEN a document is **unlinked** from an order with `paidSource = LEDGER` the system SHALL recompute the
   paid state (Requirement 16.5).
6. THE financier SHALL see the documents of the queue (name, number, total, `DocumentPaymentStatus`) and
   open the stored file through the existing document access endpoint with its own grant; this spec grants
   `FINANCIER` READ on the `PURCHASES` file-open operation only if the existing endpoint requires it (design
   checks; no other `PURCHASES` access).

### Requirement 15: Sending for payment and the financier hand-off

**User Story:** As a buyer and a financier, I want a clear hand-off, so that nothing is paid twice or
forgotten.

#### Acceptance Criteria

1. `POST /api/external-orders/{id}/send` (BUYER/ADMIN, `UPDATE`) SHALL require: `DRAFT`, at least one
   line, at least one linked Supplier_Document **or** a non-blank `referenceNumber`, `payableGross > 0`,
   every line's ceiling rule satisfied; then set `status = SENT_FOR_PAYMENT`, `sentAt`, run
   `PositionOrderSyncService` for every position and publish `ExternalOrderSentEvent`.
2. THE financier SHALL list `GET /api/external-orders?status=SENT_FOR_PAYMENT` company-wide (all projects),
   with seller, project, payable gross, documents with their payment status, dates and buyer.
3. `POST /api/external-orders/{id}/mark-paid {paidDate, note?}` (`APPROVE`) SHALL accept `SENT_FOR_PAYMENT`
   only, set `status = PAID`, `paidSource = FINANCIER`, `paidAt`, `paidBy`, `paidNote`, run the position
   sync and publish `ExternalOrderPaidEvent`. `paidDate` SHALL NOT be in the future.
4. `POST /api/external-orders/{id}/return {reason}` (`APPROVE`) SHALL accept `SENT_FOR_PAYMENT` only,
   require a reason (1..500), set `status = DRAFT`, store `returnReason`, run the position sync (positions
   back to `WITH_BUYER`), and clear `sentAt`. The buyer SHALL see the reason on the order card.
5. `POST /api/external-orders/{id}/unmark-paid` (`APPROVE`) SHALL revert a `PAID` order with
   `paidSource = FINANCIER` and no ledger confirmation to `SENT_FOR_PAYMENT` (mistake correction); a
   `LEDGER`-paid order SHALL be refused (409 `error.externalOrder.unmark.ledgerPaid`).
6. `POST /api/external-orders/{id}/cancel {reason?}` (`UPDATE`) SHALL accept `DRAFT` or `SENT_FOR_PAYMENT`
   with no `EXTERNAL_ORDER_PAYMENT` link; set `CANCELLED`, run the position sync. A `PAID` order SHALL NOT be
   cancellable; positions at `IN_DELIVERY` or later SHALL refuse the cancel of their order (409
   `error.externalOrder.cancel.positionsInDelivery`).
7. THE BUYER SHALL NOT call `mark-paid`, `return` or `unmark-paid`; the FINANCIER SHALL NOT create, edit,
   send or cancel (ABAC).

### Requirement 16: Paid determination (the ledger wins)

**User Story:** As a financier, I want the bank statement to confirm payment automatically and to take
precedence over a hand-set flag.

#### Acceptance Criteria

1. AN external order SHALL be considered **ledger-paid** when `ledgerPaidGross >= payableGross`.
2. IT SHALL also be ledger-paid when it has at least one linked Supplier_Document and **every** linked
   document has `DocumentPaymentStatus = PAID` (derived by the existing `DocumentPaymentStatusDeriver` from
   `PROCUREMENT` links). The two rules are OR-ed.
3. WHEN an order in `SENT_FOR_PAYMENT` becomes ledger-paid the system SHALL set `status = PAID`,
   `paidSource = LEDGER`, `paidAt = Today`, run the position sync and publish `ExternalOrderPaidEvent`
   (the actor is the ledger, so the buyer is notified).
4. WHEN an order is `PAID` with `paidSource = FINANCIER` and later becomes ledger-paid the system SHALL set
   `paidSource = LEDGER` (the ledger wins), keep `paidAt` of the ledger, and publish no further notification.
5. WHEN the ledger evidence disappears (a link removed or a document unlinked) for an order with
   `paidSource = LEDGER`, the system SHALL return it to `SENT_FOR_PAYMENT`, run the position sync (stage
   reversal of Requirement 17.4) and clear `paidAt`; an order with `paidSource = FINANCIER` SHALL stay
   `PAID`. Reversal SHALL be refused while any of its positions is at `IN_DELIVERY` or later (409
   `error.externalOrder.unlink.positionsInDelivery`).
6. THE evaluation SHALL be one pure `ExternalOrderPaymentDeriver` and one service entry point
   (`ExternalOrderPaymentService.reevaluate(orderId)`), called after every link change, document change and
   document payment-status change.

### Requirement 17: Position stage synchronisation

**User Story:** As a manager, I want the position stage to follow the supplier order.

#### Acceptance Criteria

1. `PositionOrderSyncService.sync(positionId)` SHALL recompute, from the position's lines in orders
   `SENT_FOR_PAYMENT` or `PAID`: `orderedQuantity` (Σ quantity), `deliveryCostShare` (13.2),
   `actualPurchasePrice` (13.3), and apply the pure `PositionOrderStageResolver`.
2. THE resolver SHALL return: `WITH_BUYER -> ORDERED` when `coverage == quantity`;
   `ORDERED -> SUPPLIER_PAID` when `coverage == quantity` and **every** covering order is `PAID`;
   `SUPPLIER_PAID -> ORDERED` when a covering order is no longer `PAID`; `ORDERED | SUPPLIER_PAID ->
   WITH_BUYER` when `coverage < quantity`; and **no change** for any other status (in particular
   `IN_DELIVERY` and later, which FOR-11-04/05 own).
3. THE service SHALL apply stage changes through `PositionLifecycleService.transition(.., SYSTEM)` one step
   at a time and be idempotent.
4. `PositionTransitionPolicy` SHALL gain the SYSTEM edges `ORDERED -> WITH_BUYER` and `SUPPLIER_PAID ->
   ORDERED` (FOR-11-00 state-machine amendment); the policy and its tests SHALL be updated, and no other
   edge SHALL change.
5. `WITH_BUYER -> ORDERED` SHALL NOT happen while the order is a `DRAFT`; a returned order moves its
   positions back (resolver) in the same transaction.
6. THE purchase request status derived by `PurchaseRequestStatusDeriver` (FOR-11-00) SHALL follow the
   position statuses without code change (verified by an integration test).

### Requirement 18: Planner extension

**User Story:** As a manager and a buyer, I want to group the plan by proforma and by external order and
start a proforma from my selection.

#### Acceptance Criteria

1. `PlannerGroupService.GroupBy` SHALL accept `PROFORMA` and `EXTERNAL_ORDER` in addition to `REQUEST` and
   `NONE`; the group key SHALL be the proforma or external-order id, or `NO_PROFORMA` / `NO_EXTERNAL_ORDER`.
   The error `error.purchasePlanner.groupBy.invalid` SHALL still guard unknown values.
2. GROUP totals SHALL be the sum of **the lines of that group**: proforma groups use proforma lines (sale
   side); external-order groups use external lines (purchase net, VAT, gross), additionally expose
   `deliveryCostNet`, `expectedShipDate`, `expectedDeliveryDate`, `seller`, `status`, and
   `paidSource`. A position split across several external orders appears in each of its groups.
3. THE planner selection toolbar SHALL offer **Create proforma** for a selection that is single-project and
   consists only of `ACCEPTED` `AGREED` positions (it calls `POST /api/client-proformas` and opens the
   draft); a mixed selection SHALL show the existing `mixedProjects` explanation.
4. THE planner rows SHALL expose `proformaId`, `proformaNumber`, `proformaStatus` and `externalOrderCount`
   (staff only) and filters `proformaId` and `externalOrderId`.
5. PRICE visibility SHALL be unchanged: client roles never reach the planner.

### Requirement 19: Notifications (emit-only)

**User Story:** As a manager, buyer or financier, I want to be told when something needs my action.

#### Acceptance Criteria

1. THE system SHALL emit the five types of the table in the Introduction through
   `NotificationService.create(recipientUserId, type, null, deepLink)` from a dedicated
   `ProformaOrderNotificationEmitter` listening with `@TransactionalEventListener(phase = AFTER_COMMIT,
   fallbackExecution = true)`; each emission is wrapped in try/catch + `log.error`.
2. EVENTS: `ProformaPaidEvent` (-> `proforma.paid` and `order.releasedToBuyer`), `ExternalOrderSentEvent`
   (-> `externalOrder.sentForPayment`), `ExternalOrderPaidEvent` (-> `externalOrder.paid`),
   `LedgerMatchProposedEvent` (-> `invoice.matchProposed`).
3. RECIPIENTS SHALL be resolved as in the table: project members by role code from ACTIVE `ProjectMember`
   rows, users by role code from `UserDao` (ACTIVE), buyer by assignment; de-duplicated; the actor of the
   operation removed.
4. `order.releasedToBuyer` SHALL be emitted **once per released proforma per recipient** (not per
   position).
5. `invoice.matchProposed` SHALL be emitted once per transaction per detection run, only for suggestions
   of the new kinds, and SHALL link to the ledger transaction.
6. THE frontend SHALL render each type as `t(type)` and navigate to the deep link on click (existing bell
   behavior); the five types have PL and RU titles (Requirement 25).

### Requirement 20: Company-wide access for BUYER and FINANCIER

**User Story:** As a buyer or financier, I want to see orders of all projects without being a project
member.

#### Acceptance Criteria

1. `ProcurementProjectAccess.allowedProjectIds` SHALL return all project ids for `FINANCIER` in addition
   to `BUYER`; this is safe because `FINANCIER` has no grant on `PURCHASE_POSITIONS`, `PURCHASE_REQUESTS`
   or `PROJECT_MATERIALS` (a test asserts it); the project-scope guard itself is not relaxed.
2. `ClientProformaService` and `ExternalOrderService` SHALL implement `ProjectScopedService` with
   `getProjectIdPath() = "project.id"` and delegate `allowedProjectIds` to the access component;
   `MANAGER` and `CLIENT` remain limited to ACTIVE memberships.
3. THE dataset predicate of Requirement 27 SHALL treat `BUYER` and `FINANCIER` as company-wide.

### Requirement 21: Lists

**User Story:** As a manager, buyer and financier, I want lists of proformas and of our supplier orders
with the usual filters.

#### Acceptance Criteria

1. `GET /api/client-proformas` SHALL support the standard admin list parameters and filters on `projectId`,
   `status`, `number`, `dueDate` range, `overdue`, and `clientName`; default sort `issueDate desc, id
   desc`.
2. `GET /api/external-orders` SHALL support filters on `projectId`, `sellerId`, `status`, `assignedBuyerId`,
   `purchaseRequestId`, `overdue`, `paidSource`, `referenceNumber`, `expectedDeliveryDate` range, and
   `hasDocuments`; default sort `updatedDate desc`.
3. BOTH lists SHALL return a page with the derived totals needed for the table (no N+1; lines aggregated by
   a single query), and a footer total over the filtered set (`totalGross` for proformas, `payableGross`
   for external orders).
4. A `CLIENT` SHALL be able to list only through Requirement 6; `MANAGER` SHALL see external orders of its
   projects read-only.

### Requirement 22: Frontend routes, tabs and navigation

**User Story:** As a user, I want the new screens where I expect them.

#### Acceptance Criteria

1. THE workspace tab contract SHALL gain `clientProformas` (`stage: 'execution'`, `owner: 'FOR-11'`,
   `requiredPermission: { resource: 'CLIENT_PROFORMAS', operation: 'READ' }`, `labelKey:
   'workspace.tab.clientProformas'`), visible to `CLIENT`, `MANAGER`, `ADMIN`, `BUYER` and `FINANCIER`
   through ABAC; it SHALL show a badge with the number of overdue unpaid proformas (staff) or unpaid
   proformas (client).
2. THE `/deliveries` page of FOR-11-01 SHALL gain two sibling routes `/deliveries/proformas` (permission
   `CLIENT_PROFORMAS` READ) and `/deliveries/external-orders` (permission `EXTERNAL_ORDERS` READ),
   rendered as tabs of the page, hidden by `usePermission` when the user has no grant; the existing
   route-permission config SHALL be updated; `FINANCIER` SHALL see only the external-orders tab and the
   menu item `nav.deliveries` through the grant.
3. DEEP links of Requirement 19 SHALL open the right tab and scroll to / highlight the proforma
   (`?proforma={id}`) or order (`?order={id}`), including when the tab is not yet mounted.
4. THE client panel SHALL branch on the caller's role code (`CLIENT` renders the client view), never on a
   client-controlled value.

### Requirement 23: Frontend proforma screens

**User Story:** As a manager and a client, I want to create, issue, follow and read proformas.

#### Acceptance Criteria

1. THE staff list SHALL show number, project, status chip, issue date, due date with an overdue badge,
   total gross, paid gross with a progress bar, and row actions (open, PDF, cancel); filters of 21.1.
2. THE draft editor SHALL show the selected positions with unit, quantity, sale net, VAT, gross, a live
   total, a due-date picker and a note, and the actions Issue (confirmation dialog repeating the total and
   the count), Save, Delete.
3. THE proforma card SHALL show lines, totals by VAT rate, the PDF button, the payment progress and the
   list of linked bank payments (date, amount) read-only, and the Cancel action with a reason dialog when
   allowed.
4. THE **client view** (tab `clientProformas` for `CLIENT`) SHALL list proformas as cards (usable at 360 px
   width), each with number, amounts, due date, status, payment progress, a PDF button and the transfer
   title with a copy button; it SHALL show a localized empty state.
5. THE client view SHALL NOT render or fetch any Internal_Field and SHALL perform only calls under
   `/api/client-proformas/client/`.
6. THE planner toolbar action of Requirement 18.3 SHALL navigate to the draft editor.

### Requirement 24: Frontend external-order screens

**User Story:** As a buyer and a financier, I want a workspace for supplier orders and a queue for
payment.

#### Acceptance Criteria

1. THE buyer SHALL see a list (filters of 21.2) and a card/editor with: seller picker (FOR-11-00
   `SellerPicker` with inline create), project, optional request, reference number, order link, expected
   dates, delivery cost and VAT, the lines table (position picker limited to `WITH_BUYER` positions of the
   project, quantity with the remaining quantity shown, unit purchase net, VAT, line gross, the ceiling
   marker with the acknowledge checkbox), documents (pick an existing document, upload a new one through
   the existing control, attach with an optional amount, seller-mismatch warning), totals (lines, delivery,
   payable) and actions Send for payment, Cancel, Delete.
2. THE financier SHALL see the queue (default filter `SENT_FOR_PAYMENT`, company-wide, sorted by
   `sentAt`), a card with the lines (net/gross), documents (open file, payment status), seller, buyer,
   delivery cost, and actions Mark paid (date picker defaulting to Today, note) and Return to buyer
   (reason required); and Unmark paid where allowed.
3. A `PAID` order SHALL show its `paidSource` badge (`Financier` / `Bank`), the paid date and note; a
   returned order SHALL show the return reason to the buyer.
4. THE page SHALL be usable on a laptop and degrade to a stacked layout on a tablet; the buyer and financier
   lists need not be mobile-first.
5. THE buyer card SHALL NOT show or edit proforma data except a read-only link to the originating proforma
   when the positions came from one.

### Requirement 25: Frontend i18n

**User Story:** As a PL or RU user, I want every label in my language.

#### Acceptance Criteria

1. EVERY new user-facing string SHALL be a key in `pl.json` and `ru.json` at parity (the existing
   `i18n.keys.test.ts` parity check passes) under `clientProformas.*`, `externalOrders.*`,
   `ledgerLink.*` additions, `purchasePlanner.group.*` additions, `workspace.tab.clientProformas`,
   `nav.*` additions if any, and the five `notification.*` titles.
2. NO string SHALL be hard-coded; dates SHALL use the existing locale-aware formatter; money SHALL use the
   existing PLN formatter.
3. ENUM labels (proforma status, external-order status, paid source, overdue) SHALL be keys, not backend
   strings.

### Requirement 26: Backend i18n and errors

**User Story:** As a user of either locale, I want errors in my language.

#### Acceptance Criteria

1. EVERY `error.proforma.*`, `error.externalOrder.*` and new `error.bank.link.*` key used by the code SHALL
   exist in `messages.properties` (PL) and `messages_ru.properties` (RU); a parity test SHALL fail on a
   missing or untranslated key.
2. THE resource seed of Requirement 1.1 SHALL carry RU and PL names and descriptions.
3. NO new RU/PL entity columns SHALL be introduced.
4. THE proforma PDF labels SHALL come from a dictionary of the two languages with a test asserting key
   parity.

### Requirement 27: Managed-entity checklist and datasets

**User Story:** As a maintainer, I want the new entities wired into the platform conventions.

#### Acceptance Criteria

1. BOTH resources follow `.kiro/steering/entity-creation-rules.md` steps 1 to 4: seed (R1), ADMIN grant,
   `@RequiresPermission` on every handler, `getProjectIdPath() = "project.id"`.
2. DATASETS (step 5): `ClientProformasDataset` (fields: project, status, issue date, due date, totals,
   paid gross, overdue) and `ExternalOrdersDataset` (project, seller, status, payable gross, delivery cost,
   paid source, sent/paid dates, expected dates) SHALL be registered with `requiredResources`,
   `projectPath = "project.id"`, and the date fields for the global date filter. NO field SHALL expose
   purchase-line prices, document ids/keys, notes, reasons, links, IBAN or NIP (`SensitiveFieldGuardTest`).
   Both datasets SHALL override `nullProjectPredicate` to be company-wide for `BUYER` and `FINANCIER`
   (FOR-11-00 technique).
3. `DatasetCoverageTest` and `SensitiveFieldGuardTest` SHALL pass.
4. NO library widgets or role defaults are seeded by this spec (the datasets are usable from the widget
   builder).

### Requirement 28: Verification and `test-cases.md`

**User Story:** As a maintainer, I want the spec covered by tests and a runnable scenario document.

#### Acceptance Criteria

1. UNIT and property tests SHALL cover: `ProformaStatusMachine`, `ProformaStatusDeriver`, proforma money
   (equal to the planner total), number formatter, `ExternalOrderStatusMachine`,
   `ExternalOrderCoverageValidator`, `ExternalOrderPaymentDeriver`, `PositionOrderStageResolver`, delivery
   allocation sum-exactness, the new `PositionTransitionPolicy` edges, `RuleMatcher` reason codes and the
   client-DTO leak guard.
2. INTEGRATION tests (Testcontainers) SHALL cover: draft create/edit/delete, issue (stages, PDF stored,
   number unique), cancel, partial payment (no release), full payment (release, kanban, notifications),
   over-link rejection, replay idempotence, locked link, external order create/lines/coverage/ceiling,
   delivery allocation, send, mark-paid, return, ledger-paid, ledger-wins, reversal, unmark, cancel rules,
   stage sync, ABAC (CLIENT cannot reach staff or external endpoints, BUYER cannot mark paid, FINANCIER
   cannot create), cross-project isolation, forward and reverse matching, suggestions, notification
   recipients and deep links, planner grouping, CHECK constraints, migration idempotency.
3. FRONTEND tests SHALL cover: client view (no internal field, call allow-list), draft editor total,
   issue dialog, external-order editor (coverage, ceiling), financier queue actions, ledger dialog new
   kinds, tab/route gating and the i18n parity.
4. `test-cases.md` SHALL be authored in Russian per `.kiro/steering/test-cases.md` (API part against the
   Dockerized app, UI part by browser engine, repeatability by generator and teardown, regression group,
   MD report template).

### Requirement 29: Non-regression

**User Story:** As a maintainer, I want existing behavior preserved.

#### Acceptance Criteria

1. THE works payment schedule, `ReconciliationEngine`, `PaymentTransactionService` and all `PAYMENTS`
   behavior SHALL be unchanged; existing `CLIENT_PAYMENT` links keep their effect and lock.
2. THE existing link kinds, their direction rules, the detection scores for them, the AI classifier, the
   ledger filters and the procurement OCR/document flow SHALL be unchanged; the existing FOR-07-04
   integration tests SHALL pass.
3. THE FOR-11-00 REST contract SHALL be unchanged except the two added SYSTEM edges of Requirement 17.4;
   the manual edges and gate tests SHALL pass.
4. THE FOR-11-01 planner contract SHALL be unchanged for `REQUEST` and `NONE` grouping and for existing
   filters; the new fields are additive.
5. THE FOR-11-02 approval flow, the notification bell and the FOR-06-12 communication SHALL be unchanged.
