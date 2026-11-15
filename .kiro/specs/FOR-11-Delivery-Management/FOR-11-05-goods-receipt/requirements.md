# Requirements Document

## Introduction

FOR-11-05 closes the FOR-11 chain. FOR-11-04 tracks a shipment up to the carrier's claim `DELIVERED`,
which deliberately does **not** change the position. This spec adds the **receipt on site**: the
foreman accepts the goods **item by item**, records what arrived, what is missing and what was
returned, and the system derives a **quantity ledger** per position, moves the position through
`IN_DELIVERY -> PARTIALLY_RECEIVED -> RECEIVED`, and opens a **new shipment** for outstanding units.

The spec delivers:

1. A **receipt event** entity (`GoodsReceipt`): append-only, per position, of kind `ACCEPTED`,
   `MISSING` or `RETURNED`, with quantity, comment, author, time and optional link to a shipment.
2. **Photos** per event, stored through the existing object-storage pattern.
3. A **quantity ledger** derived from the events (accepted net, missing, returned, outstanding,
   surplus), kept in step with `PurchasePosition.acceptedQuantity`.
4. **Position status derivation** through the existing SYSTEM edges (no new edge in the position machine).
5. **Re-shipment**: missing or returned units become shippable again (the documented
   `ShipmentRules.capFor` seam) and the system opens a new shipment for them.
6. **Shipment closure**: a shipment whose lines are fully resolved by receipt is closed, so tracking stops.
7. A mobile-first **receipt screen** for the foreman and a read view for staff, plus a foreman
   **bottom-bar entry** and a "Receive" action on the upcoming-deliveries card.
8. One **notification**, `notification.receipt.discrepancy`, a **FOR-10 dataset**, the new ABAC resource
   `GOODS_RECEIPTS`, locale keys (PL/RU), `test-cases.md`.

### Product decisions this spec implements (from ../OVERVIEW.md)

- Receipt is recorded as **events per position** (accepted / missing / returned), so the ledger is
  derivable and auditable. There is **no credit-note modelling**: a complaint only changes quantities
  and creates a new delivery for the outstanding units.
- Worked example the ledger must reproduce: **ordered 3, shipped 2** (1 never arrived), of the 2
  received **1 is returned** -> accepted net 1, **outstanding 2** (1 missing + 1 returned).
- **Real over-delivery** ("ordered 15, delivered 17", FOR-11-03 design) is a receipt event handled here.
- Delivery goes **straight to the site** (no warehouse): the foreman of the project is the receiver.
- The process is **per project**; BUYER is company-wide, FOREMAN is project-scoped.

### Facts verified in the code (basis of the design)

- `PositionStatus` already has `IN_DELIVERY`, `PARTIALLY_RECEIVED`, `RECEIVED`. `PositionTransitionPolicy`
  already has the SYSTEM edges `IN_DELIVERY <-> PARTIALLY_RECEIVED`, `IN_DELIVERY` / `PARTIALLY_RECEIVED
  <-> RECEIVED`, for every sourcing path. **No new edge is needed**; receipt must use `Actor.SYSTEM`
  through `PositionLifecycleService.transition(...)`.
- `PurchasePositionEntity` has `quantity`, `orderedQuantity` and `acceptedQuantity` (default 0). It has no
  shipped, received, missing or returned column; "shipped" is derived from active shipment lines.
- `ShipmentRules.capFor(path, orderedQuantity, quantity)` returns `quantity` for `CLIENT_OWN` and
  `orderedQuantity` otherwise; `ShipmentService.validateLines` sums **active** lines (not `DELIVERED`,
  not `CANCELLED`). `ShipmentRules.canShip` already admits `PARTIALLY_RECEIVED`.
- `ShipmentService.moveToInDelivery` only moves `SUPPLIER_PAID` positions; `reverseToSupplierPaid`
  treats "has a delivered line" as "awaits receipt" and honours `acceptedQuantity`.
- `ShipmentController` grants: FOREMAN has **READ only** on `SHIPMENTS`. A new resource is needed for
  the foreman to create receipt events.
- Notification emitters (`ShipmentNotificationEmitter`, `ProformaOrderNotificationEmitter`) use
  `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`, never throw, remove the actor,
  and call `NotificationService.create(userId, type, null, link)`. Type keys resolve on the frontend.
- The last changeset is `243`; the next free number is `244`.
- Image storage: `ImageStorage.storeObject(MultipartFile, keyNamespace)`, `signedDownloadUrl`,
  `deleteIfOrphan`; `RoomMediaController` / `RoomMediaService` is the working multipart pattern.
- Foreman bottom bar: `BOTTOM_NAV_PROFILES.FOREMAN` has three slots, the cap is `MAX_BOTTOM_SLOTS = 5`.

### Scope boundaries

- **In scope:** the entities, ledger, status derivation, re-shipment and shipment closure, endpoints,
  ABAC resource and grants, one notification, one dataset, the foreman receipt screen, staff history
  view, bottom-bar entry, upcoming-deliveries action, locale keys, tests and `test-cases.md`.
- **Out of scope:**
  - credit notes, supplier claims and refunds, the final VAT invoice, the accounting program;
  - recalculating margin or the supplier payment from received quantities (the margin keeps using the
    ordered quantity and `actualPurchasePrice`; a financial correction is a separate change);
  - client visibility of receipts (no client-safe surface, same as FOR-11-04);
  - warehouse stock, storage locations, serial numbers, barcode scanning;
  - offline capture (the screen needs a connection; a retry-safe request id is provided, see R4);
  - construction-material delivery tracking (still FOR-06-03/04/05, untouched).

### Notifications (emit-only; confirmed in OVERVIEW revision 5)

The spec only **emits** through the generic `NotificationService` (in-app, DB). No delivery channel is built.

| Type key | Recipient | Trigger | Deep link |
|----------|-----------|---------|-----------|
| `notification.receipt.discrepancy` | project MANAGER members and all active BUYER users | a `MISSING` or `RETURNED` event is recorded, or an `ACCEPTED` event makes the accepted net exceed the ordered cap (surplus) | `/deliveries/receipt?position={positionId}` |

The actor is never notified. One notification per recorded event (a batch of events yields one per
event with a discrepancy). A fully clean receipt emits nothing.

### i18n decisions

- **No entity gets `nameRU` / `namePL` fields**: receipt events hold user data (comment, quantities)
  and no new dictionary is introduced. The receipt kind, status chips and ledger labels are
  **frontend enum keys**.
- Frontend key group `goodsReceipt.*` plus `nav.goodsReceipt` and `notification.receipt.discrepancy`,
  with **PL/RU parity** in `pl.json` and `ru.json`. Backend error keys `error.goodsReceipt.*` go into the
  existing backend messages files (`messages.properties`, `messages_ru.properties`).

## Glossary

- **Receipt_Event**: one append-only row of a position: kind, quantity, comment, author, time.
- **Kind**: `ACCEPTED` (units that arrived and were kept), `MISSING` (units expected on a delivery that
  did not arrive), `RETURNED` (units that arrived and are sent back or rejected).
- **Voided event**: an event cancelled by the author or a manager with a reason; it is kept for the
  audit and excluded from the ledger.
- **Cap**: `ShipmentRules.capFor(...)`: `orderedQuantity` (AGREED, PACKAGE) or `quantity` (CLIENT_OWN).
- **Accepted_Net**: sum of non-voided `ACCEPTED` quantities minus non-voided `RETURNED` events flagged
  `fromAccepted`.
- **Outstanding**: `max(0, Cap - Accepted_Net)`; the units still to be delivered.
- **Surplus**: `max(0, Accepted_Net - Cap)`.
- **In_Flight**: units on active shipments plus units on `DELIVERED` shipment lines not yet resolved by a
  receipt event.
- **Re_Delivery_Shipment**: a shipment created by the system for outstanding units after `MISSING` or
  `RETURNED`.
- **Receiver**: a user with `GOODS_RECEIPTS / CREATE` on the position's project (normally the foreman).

## Requirements

### Requirement 1: ABAC resource, grants and project scope

**User Story:** As an administrator, I want receipt guarded by the permission matrix, so that only the
right people record and read it.

#### Acceptance Criteria

1. A new resource `GOODS_RECEIPTS` SHALL be seeded by changeset `245-seed-goods-receipts-resource.xml`
   (RU/PL name and description), registered **last** in `changelog.xml`, with `MARK_RAN` preconditions
   (the entity-creation checklist, steps 1 and 2).
2. Grants SHALL be: ADMIN `CREATE/READ/UPDATE/DELETE`; FOREMAN `CREATE/READ/UPDATE`; MANAGER
   `CREATE/READ/UPDATE`; BUYER `READ`; FINANCIER, WORKER, CLIENT none.
3. The controller SHALL carry `@PermissionResource("GOODS_RECEIPTS")` and every handler a matching
   `@PermissionOperation`, so `PermissionAnnotationValidator` passes at startup.
4. The service SHALL implement `ProjectScopedService` with `getProjectIdPath()` = `"project.id"`. FOREMAN
   and MANAGER are limited to their projects; ADMIN and BUYER are company-wide through
   `ProcurementProjectAccess` (the guard is not relaxed).
5. A request on a position of another project SHALL fail with 403 (not 404), as for shipments.

### Requirement 2: Data model

**User Story:** As a developer, I want a small append-only model, so that the ledger is auditable.

#### Acceptance Criteria

1. Changeset `244-create-goods-receipts.xml` SHALL create `goods_receipts`: `id`, `project_id`,
   `position_id`, `shipment_id` (nullable), `kind` (CHECK in `ACCEPTED`, `MISSING`, `RETURNED`),
   `quantity` `numeric(14,3)` CHECK `> 0`, `from_accepted` boolean default false, `comment`
   (length 2000), `occurred_at`, `received_by` (user id), `client_request_id` (uuid, unique where
   present), `voided_at`, `voided_by`, `void_reason`, plus the base-entity columns.
2. It SHALL create `goods_receipt_media` (`id`, `receipt_id`, `storage_uri`, `content_type`,
   `size_bytes`, `file_name`) with an index on `receipt_id`, and indexes on `goods_receipts(position_id)`
   and `(project_id, occurred_at)`.
3. `from_accepted` SHALL be allowed only on `RETURNED`; the CHECK rejects it on other kinds.
4. Rows SHALL never be updated except for the void columns and the comment; a quantity or kind change
   is made by voiding and recording again.
5. Changesets SHALL be registered last in sequence, with `MARK_RAN` preconditions where re-runs apply.

### Requirement 3: Quantity ledger

**User Story:** As a manager or buyer, I want a per-position ledger, so that I see exactly what is
accepted, missing, returned and still to come.

#### Acceptance Criteria

1. For a position the ledger SHALL expose: `cap`, `acceptedNet`, `missing`, `returned`, `outstanding`,
   `surplus` and `inFlight`, all derived from non-voided events and shipment lines.
2. The ledger SHALL reproduce the worked example: cap 3, events `ACCEPTED 1`, `RETURNED 1`, `MISSING 1`
   give `acceptedNet = 1`, `outstanding = 2`, `missing = 1`, `returned = 1`.
3. `Position.acceptedQuantity` SHALL be recomputed in the same transaction after every recorded or voided
   event and always equal `acceptedNet`.
4. `Accepted_Net` SHALL never be negative: a `RETURNED` event with `fromAccepted = true` is rejected
   (409 `error.goodsReceipt.returnExceedsAccepted`) when it would make it negative.
5. The ledger SHALL be a pure function (`GoodsReceiptRules.ledger(...)`) covered by property tests.
6. The ledger SHALL contain **no money**.

### Requirement 4: Recording a receipt event

**User Story:** As a foreman, I want to record what arrived item by item, so that the office knows the
real state.

#### Acceptance Criteria

1. `POST /api/goods-receipts` SHALL record one or more events in one request (`events[]`: `positionId`,
   `kind`, `quantity`, `fromAccepted`, `comment`, `shipmentId`, `clientRequestId`); the whole request is
   one transaction.
2. A position SHALL accept events only in `IN_DELIVERY` or `PARTIALLY_RECEIVED`, and in `RECEIVED` only
   for `RETURNED` with `fromAccepted = true` (a later complaint). Other statuses fail with 409
   `error.goodsReceipt.notReceivable`.
3. `quantity` SHALL be greater than 0 with at most 3 decimals, and an integer when the position's unit is
   a piece unit (the same rule the shipment lines use).
4. `MISSING` SHALL be allowed only for units that are In_Flight: the sum of non-voided `MISSING` and
   unresolved quantity SHALL NOT exceed the units on active or delivered shipment lines
   (409 `error.goodsReceipt.exceedsInFlight`); for a `CLIENT_OWN` position without shipments the cap applies.
5. `ACCEPTED` MAY exceed the cap (real over-delivery); the response SHALL carry `surplus` and the
   event SHALL trigger the discrepancy notification (R12).
6. `RETURNED` with `fromAccepted = false` SHALL count units that arrived and were rejected at the door;
   it does not reduce `Accepted_Net`.
7. A non-blank `comment` SHALL be **required** for `MISSING` and `RETURNED`
   (400 `error.goodsReceipt.comment.required`); it is optional for `ACCEPTED`.
8. A repeated request with the same `clientRequestId` SHALL return the original result and create nothing
   (safe retry from a weak mobile connection).
9. The event time SHALL be server time; `occurred_at` is not client-editable.
10. If `shipmentId` is given it SHALL belong to the same project and carry a line of that position
    (400 `error.goodsReceipt.shipment.mismatch`); a `CANCELLED` shipment is refused.
11. Recording SHALL work for all three sourcing paths (`AGREED`, `PACKAGE`, `CLIENT_OWN`).

### Requirement 5: Void a receipt event

**User Story:** As a foreman or manager, I want to cancel a mistaken entry, so that the ledger is right
without deleting history.

#### Acceptance Criteria

1. `POST /api/goods-receipts/{id}/void` with a mandatory `reason` SHALL mark the event voided
   (`voided_at`, `voided_by`, `void_reason`); the row stays.
2. The author MAY void within 24 hours of `occurred_at`; MANAGER and ADMIN MAY void at any time. Others get
   403 `error.goodsReceipt.void.forbidden`.
3. Voiding SHALL recompute the ledger, `acceptedQuantity` and the position status (R6) in the same
   transaction and SHALL NOT notify.
4. A voided event SHALL be read-only and cannot be voided twice (409 `error.goodsReceipt.alreadyVoided`).
5. Voiding an event whose effect already has an auto-created, unchanged Re_Delivery_Shipment SHALL cancel
   that shipment when it still has no tracking data and no other lines (R8.5); otherwise the shipment
   stays and the response says so.

### Requirement 6: Position status derivation

**User Story:** As a manager, I want the position status to follow receipt automatically, so that the
planner and the buyer kanban stay true.

#### Acceptance Criteria

1. After every recorded or voided event the service SHALL derive the status and transition through
   `PositionLifecycleService` with `Actor.SYSTEM`:
   - `acceptedNet >= cap` -> `RECEIVED`;
   - otherwise, at least one non-voided event exists -> `PARTIALLY_RECEIVED`;
   - otherwise (no non-voided events) -> `IN_DELIVERY`.
2. Only legal edges SHALL be used; no new edge is added to `PositionTransitionPolicy`. A derived status
   equal to the current one SHALL be a no-op.
3. `RECEIVED` SHALL be re-opened to `PARTIALLY_RECEIVED` when a `fromAccepted` return or a void takes
   `acceptedNet` below the cap.
4. Each transition SHALL publish the existing `PositionStatusChangedEvent` (audit, dashboards).
5. A position with `PARTIALLY_RECEIVED` and a new active shipment SHALL go back to `IN_DELIVERY`
   (R8.3), and receipt of that shipment returns it to `PARTIALLY_RECEIVED` or `RECEIVED`.
6. When the position reaches `RECEIVED`, `plannedDeliveryDate` SHALL be left as the last value and the
   position SHALL leave the upcoming-deliveries list and the downtime-risk calculation (both already
   exclude `RECEIVED`).

### Requirement 7: Photos

**User Story:** As a foreman, I want to attach photos of damage or of the delivery, so that a complaint
is documented.

#### Acceptance Criteria

1. `POST /api/goods-receipts/{id}/media` (multipart, `files`) SHALL attach images to a non-voided event
   through `ImageStorage`, key namespace `goods-receipts`.
2. Allowed content types: JPEG, PNG, WebP, HEIC; at most 8 files per request, 10 per event, 10 MB each
   (the limits are configuration properties). Violations give 400 `error.goodsReceipt.media.*`.
3. `GET /api/goods-receipts/{id}/media/{mediaId}/content` SHALL stream or redirect to a signed URL, and
   `DELETE .../{mediaId}` SHALL remove a file (author within 24 hours, MANAGER, ADMIN); orphaned objects
   SHALL be cleaned with `deleteIfOrphan`.
4. Photos SHALL be **optional** for every kind in this phase (a photo is encouraged in the UI for
   `MISSING` and `RETURNED`).
5. Storage keys and URIs SHALL NOT appear in any DTO list or dataset; only media id, file name, size and
   content type do.

### Requirement 8: Re-shipment of outstanding units and shipment closure

**User Story:** As a buyer, I want missing and returned units to become a new delivery automatically, so
that nothing is forgotten.

#### Acceptance Criteria

1. `ShipmentRules.capFor` SHALL be extended so that the shippable remainder of a position is
   `Cap - Accepted_Net - In_Flight`, never negative; units freed by `MISSING` and `RETURNED` events are
   shippable again. Existing behaviour for positions without receipt events SHALL be unchanged
   (non-regression of FOR-11-04 cases).
2. When a `MISSING` or `RETURNED` event is recorded and `outstanding > 0` with nothing In_Flight for that
   position, the system SHALL create a **Re_Delivery_Shipment** in the same transaction: same project and
   external order (if any), one line with the outstanding quantity, status `CREATED`, a note
   `Re-delivery after receipt {receiptId}`, a delivery method that needs no tracking data (the design
   picks the least demanding method valid under `ShipmentRules.validateMethod`), and `etaDate` empty.
3. Creating a shipment for a `PARTIALLY_RECEIVED` position SHALL move it to `IN_DELIVERY`
   (`ShipmentService.moveToInDelivery` extended; edge exists).
4. When several events of one request concern the same position, only one Re_Delivery_Shipment SHALL be
   created for the final outstanding quantity.
5. When the Re_Delivery_Shipment becomes unnecessary (event voided, units accepted later) and it is still
   `CREATED` without tracking data, it SHALL be cancelled by the system; otherwise it is left to the buyer.
6. **Shipment closure:** when every line of a non-terminal shipment is fully resolved by non-voided events
   linked to that shipment (`ACCEPTED + MISSING + RETURNED >= line quantity` per line), the shipment SHALL
   be set `DELIVERED` with source `MANUAL`, `deliveredAt` stamped and `nextCheckAt` cleared, so the
   scheduler stops. A void that reopens a line SHALL NOT reopen the shipment (documented limitation;
   the buyer can edit it).
7. `reverseToSupplierPaid` SHALL keep working: a position with any non-voided receipt event is never
   moved back to `SUPPLIER_PAID`.
8. A shipment SHALL NOT be changed in any other way by a receipt event.

### Requirement 9: Read API

**User Story:** As staff, I want to list and open receipts, so that I can check what was received.

#### Acceptance Criteria

1. `GET /api/goods-receipts` SHALL list events with filters `projectId`, `positionId`, `shipmentId`,
   `kind`, `includeVoided` (default false), `from`, `to`, standard paging and sort (default newest first),
   project scoping per R1.4.
2. `GET /api/goods-receipts/{id}` SHALL return the event with its media metadata and the position summary
   (material name, unit, work category).
3. `GET /api/goods-receipts/to-receive?projectId=` SHALL return the positions in `IN_DELIVERY` or
   `PARTIALLY_RECEIVED` of the project with their ledger and shipments (ETA, status, carrier code), sorted
   by ETA (nulls last), including the shipments that are `DELIVERED` first.
4. `GET /api/purchase-positions/{id}/ledger` SHALL return the ledger of R3.1 (planner drawer, buyer view).
5. No DTO SHALL contain a price, purchase or sale amount, or VAT.

### Requirement 10: Foreman receipt screen (mobile-first)

**User Story:** As a foreman on site, I want a simple screen to receive goods, so that I can do it with
one hand in a few taps.

#### Acceptance Criteria

1. A route `/deliveries/receipt` SHALL show **To receive** (the `to-receive` list of the working project)
   and **History** (events of the project) as two tabs, usable at 360 px width.
2. Each position card SHALL show material, unit, expected / on-shipment / accepted quantities, the
   shipment status and ETA, and the action **Receive**.
3. **Receive** SHALL open a sheet with: kind selector (Accepted / Missing / Returned), quantity stepper
   (default = the in-flight quantity for `ACCEPTED`), "from already accepted" switch for returns, comment,
   photo capture or pick, and Save. Client-side validation SHALL mirror R4.
4. A shortcut **Accept all** on a shipment SHALL record `ACCEPTED` for each line at the line quantity in
   one batch request, after a confirmation.
5. After Save the card SHALL update from the response (no full reload) and show the new ledger and status;
   loading, error and retry states SHALL be handled; a failed upload keeps the saved event and offers
   to retry the photo only.
6. The deep link `?position={id}` SHALL scroll to and highlight the card and open its history.
7. A voided event SHALL show struck through with its reason in the history; the **Void** action SHALL
   require a reason and SHALL be shown only to users allowed by R5.2.
8. The screen SHALL show **no money**.
9. A user without `GOODS_RECEIPTS / READ` SHALL not see the route or navigation item.

### Requirement 11: Navigation, upcoming deliveries and staff view

**User Story:** As a foreman, I want one tap from the bottom bar to the receipt screen.

#### Acceptance Criteria

1. `navigation.ts` SHALL get an item `goodsReceipt` (`/deliveries/receipt`, `matchPrefix`, permission
   `GOODS_RECEIPTS / READ`, label `nav.goodsReceipt`) and the FOREMAN bottom profile SHALL include it
   (four slots, within `MAX_BOTTOM_SLOTS`).
2. The `/deliveries/upcoming` delivery card SHALL show a **Receive** button when the user may create
   receipts and the position is receivable; it links to `/deliveries/receipt?position={id}`.
3. For MANAGER and BUYER the planner and kanban position drawer SHALL show the **ledger** (R9.4) and the
   event history, read-only.
4. The router SHALL register the new route behind the permission guard; the existing navigation and
   bottom-bar tests SHALL be extended, not replaced.

### Requirement 12: Notification

**User Story:** As a manager or buyer, I want to be told about discrepancies, so that I can react at once.

#### Acceptance Criteria

1. A `GoodsReceiptDiscrepancyEvent` SHALL be published after a `MISSING` or `RETURNED` event, or an
   `ACCEPTED` event that produces a surplus.
2. A new `GoodsReceiptNotificationEmitter` SHALL follow the existing emitter pattern (AFTER_COMMIT,
   fallback execution, never throws) and create `notification.receipt.discrepancy` with link
   `/deliveries/receipt?position={positionId}` for the active MANAGER members of the project and all
   active BUYER users, removing the actor and de-duplicating.
3. A clean `ACCEPTED` event without surplus SHALL emit nothing; a void SHALL emit nothing.
4. The notification text SHALL be resolved on the frontend from the type key (PL/RU).

### Requirement 13: API surface

**User Story:** As a developer, I want a predictable API, so that the screens and tests are simple.

#### Acceptance Criteria

1. Endpoints: `POST /api/goods-receipts` (CREATE), `GET /api/goods-receipts`, `GET /{id}`,
   `GET /to-receive` (READ), `POST /{id}/void`, `POST /{id}/media`, `DELETE /{id}/media/{mediaId}`
   (UPDATE), `GET /{id}/media/{mediaId}/content` (READ), `GET /api/purchase-positions/{id}/ledger` (READ on
   `GOODS_RECEIPTS`).
2. Errors SHALL use `ForemenApiException` with `error.goodsReceipt.*` keys present in both backend
   messages files; conflicts are 409, validation 400, scope 403.
3. Writes SHALL be audited through the existing audit mechanism (create, void, media add, media delete).

### Requirement 14: Managed-entity checklist and dataset

**User Story:** As a developer, I want the entity wired as the standard requires.

#### Acceptance Criteria

1. The seed changeset (R1.1) SHALL follow the entity-creation checklist steps 1 and 2; the controller
   steps 3; the service step 4.
2. A `GoodsReceiptsDataset` (`code = "GOODS_RECEIPTS"`, `requiredResources = GOODS_RECEIPTS`, project
   path `project.id`, date field `occurredAt`) SHALL be registered. Selectable, filterable, groupable,
   aggregatable fields: `kind`, `quantity`, `occurredAt`, `project`, `position`, `voided`. The `comment`,
   `void_reason`, storage fields and `client_request_id` SHALL NOT be exposed; `SensitiveFieldGuardTest`
   and `DatasetCoverageTest` SHALL stay green.
3. `goods_receipt_media` SHALL be added to the `DatasetCoverage` exclusion list with a one-line
   justification if it is seeded as a resource; otherwise it is not a resource and needs none.

### Requirement 15: Internationalisation

**User Story:** As a PL or RU user, I want the screens and messages in my language.

#### Acceptance Criteria

1. The frontend keys `goodsReceipt.*` (tabs, card labels, kind names, ledger labels, sheet fields,
   validation messages, empty / error states, void dialog), `nav.goodsReceipt` and
   `notification.receipt.discrepancy` SHALL exist in `pl.json` and `ru.json` with identical key sets.
2. Backend error keys `error.goodsReceipt.*` SHALL exist in `messages.properties` and
   `messages_ru.properties`.
3. A locale-parity test SHALL cover the new groups.

### Requirement 16: Verification and test-cases.md

**User Story:** As a tester, I want repeatable scenarios, so that the flow is proven end to end.

#### Acceptance Criteria

1. Unit and property tests SHALL cover the ledger, the status derivation (including the worked example),
   the extended cap, the void rules and the notification recipient set; integration tests SHALL cover
   record, batch, retry with the same `clientRequestId`, void, re-shipment, shipment closure and the
   permission matrix.
2. A security test SHALL prove that no DTO of this spec contains money and that a foreman of another
   project gets 403.
3. `test-cases.md` SHALL be authored **in Russian** per the workspace standard: API scenarios against the
   Dockerized app and browser scenarios for the receipt screen, run-id generator for repeatability,
   a regression group, and the MD report template.
4. Test runs SHALL select only the affected classes and write to a temp log (workspace test rule).

### Requirement 17: Non-regression

**User Story:** As a maintainer, I want the earlier FOR-11 specs unchanged in behaviour.

#### Acceptance Criteria

1. Existing FOR-11-04 shipment tests (cap, `EXCEEDS_CAP`, `EXCEEDS_ORDER_LINE`, reverse to `SUPPLIER_PAID`,
   propagation) SHALL pass without edits to their expectations.
2. `PositionTransitionPolicy` SHALL be unchanged; the existing transition tests SHALL pass.
3. The margin, the proforma and the external-order flows SHALL not read the new tables.
4. Other roles' navigation and bottom bars SHALL be unchanged.

## Open points for review (assumptions made here)

1. **`RETURNED` semantics.** `fromAccepted = false` counts units rejected at the door; `true` takes units
   back out of the accepted net (a later complaint). Confirm this two-way flag, or prefer a fourth kind.
2. **Re-delivery shipment is created automatically** with a method that needs no tracking data. If
   `ShipmentRules.validateMethod` cannot accept that, the design adds a minimal exception for system-created shipments.
3. **Shipment closure** sets `DELIVERED` by hand-source when all lines are resolved; it does not reopen on void.
4. **Void window** for the foreman: 24 hours; photos optional; photo limits are configuration.
5. **Recipients** of the discrepancy notification: project MANAGER members and all BUYER users (BUYER is
   company-wide, so a buyer user lookup by role is needed in the emitter).
6. Over-delivery is accepted and flagged; it does not raise `orderedQuantity` or the supplier payment.
