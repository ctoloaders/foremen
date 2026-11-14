# Requirements Document

## Introduction

This spec (**FOR-11-01-purchase-planner**) is the first screen-level child of the FOR-11 "Delivery
Management" parent (`../OVERVIEW.md`). FOR-11-00 delivered the procurement data, state machine, access
rules and APIs; this spec builds the **working screens on top of them** and replaces the `/deliveries`
placeholder:

1. The manager's **purchase planner**: one table of all positions of a project (or of all projects for
   a buyer), sorted by **site-delivery date**, with an **urgent** pick, a link to the **work schedule**,
   quick **slices** (on schedule, late, changed, paid, not paid), grouping with totals by internal
   request, and a **tick-to-proforma running total**.
2. The **BUYER board** (kanban): read-only columns by stage for everything released to the buyer.
3. The **company-wide purchase view with project filters**: the same planner opened without a project
   filter by a BUYER or ADMIN.
4. The **position form** (create / edit) with a material picker (global + project materials), the
   00 `SellerPicker`, an automatic (editable) work-category link and the dates, plus the **variant-history expander** and the
   manual **row actions** (send to buyer, client-own, cancel, reopen, delete).
5. **Internal request** handling from the planner: create a request from ticked positions, assign a
   buyer, set the expected delivery date, detach positions, and the `/deliveries?request={id}` deep
   link that the 00 notifications already carry.
6. **Per-position margin** with proportionally allocated delivery cost (a pure allocator that FOR-11-03
   will feed), and **purchase price hidden from every non-staff audience**.
7. The **"hide delivered" switcher** (default ON, remembered per user) over the 00 `hideDelivered`
   parameter.

### Product decisions this spec implements (from `../OVERVIEW.md`, revision 5)

- The planner is **per project by default**, sorted by site-delivery date, so work is not blocked; the
  manager takes the most urgent positions and later (FOR-11-03) clicks the accepted ones into a
  proforma. This spec only computes the **total of the ticked positions**; it does not create a
  proforma.
- **Purchase price is never shown to the client** and never to the foreman; the buyer sees the **sale
  price as the ceiling** for buying.
- **Delivery cost is allocated to positions proportionally to their cost** for margin. The delivery
  cost itself arrives with external orders (FOR-11-03); until then `deliveryCostShare` is `0` and the
  margin ignores it.
- **Estimate materials are reference only**: positions may be added ad hoc, without any estimate
  reference.
- The **kanban is read-only** apart from the existing manual transitions of FOR-11-00 (no drag and
  drop, no new stage edges).

### Scope

- **In scope:** everything listed above; the backend read models and small write endpoints that the
  screens need (planner list, group totals, selection total, board, create-request-from-positions,
  work-category link on the position), the pure helpers (money math, margin, delivery-cost allocator,
  slice rules, work-category resolver, work-start resolver), the frontend feature, locale keys, tests and `test-cases.md`.
- **Out of scope:** the proforma and its payment (03), external orders and their grouping (03), client
  agreement and the client portal (02, FOR-09), shipments, carrier tracking and the Gantt overlay (04),
  goods receipt (05), the foreman's upcoming-deliveries screen (04), drag-and-drop between kanban
  columns, promotion of a project material into the global dictionary, construction materials,
  furniture, multi-currency, export to Excel/PDF.
- **Reserved for later children (no behavior now):** grouping by external order and by proforma. The
  grouping selector offers only the values that exist in this spec; 03 adds the two others.

### Notifications (confirmed: none)

This spec **emits no notifications**. Justification: it adds screens and read models over state changes
that FOR-11-00 already covers (`notification.request.assigned`, `notification.request.overdue`); the
create-request-from-positions endpoint reuses the existing assignment event of 00 when a buyer is set,
so the 00 notification is emitted by the 00 code path and is not re-implemented here. The only
notification touchpoint is **consuming** the existing deep link `/deliveries?request={id}`
(Requirement 14.6). Candidate notifications for the other children stay in `../OVERVIEW.md`.

### i18n decisions (confirmed)

- **Backend entity fields requiring i18n:** none. Positions, requests and the new `work_category_id`
  hold ids or user data. Names shown to the user (material producer, seller, unit, work category,
  delivery category) are resolved from existing `nameRU`/`namePL` columns by the existing locale
  resolver. No new `resources`, `roles` or `delivery_statuses` rows are seeded by this spec.
- **Backend message keys:** the `error.purchasePlanner.*`, `error.purchaseRequest.fromPositions.*` and
  `error.purchasePosition.workCategory.*` keys listed in the design, in `messages.properties` (PL) and
  `messages_ru.properties` (RU), with a parity test.
- **Frontend:** new key groups `purchasePlanner.*` and `buyerKanban.*` (PL + RU at parity, listed with
  values in the design); `nav.deliveries` and the two 00 notification titles are reused unchanged;
  `pages.deliveries.*` placeholder keys are removed. Enum labels (stage, sourcing path, slice,
  group-by, row action) are frontend keys.

## Glossary

- **Position**: a `PurchasePosition` of FOR-11-00.
- **Request** / **Purchase_Request**: the internal `PurchaseRequest` of FOR-11-00.
- **Site_Delivery_Date**: `COALESCE(plannedDeliveryDate, needByDate)`: the date the goods must be on
  site; the planner's default sort key.
- **Terminal_Stage**: `RECEIVED` or `CANCELLED`.
- **Slice**: a mutually exclusive quick filter: `ALL`, `ON_SCHEDULE`, `LATE`, `CHANGED`, `PAID`,
  `NOT_PAID` (Requirement 2).
- **Urgent**: a position that must be handled soon (Requirement 2.5).
- **STAFF_FULL / SITE / CLIENT**: the price-visibility audiences of FOR-11-00 Requirement 10.
- **Ceiling**: the net sale unit price; the buyer should not buy above it.
- **Effective work category**: the manual work category of a position, otherwise the one resolved
  automatically from its material type through the work-catalog consumption norms (Requirement 5).
- **Work_Start_Date**: the calendar start date of the earliest schedule bar of the effective work category.
- **Scheduled category**: a work category with a stored bar in the project's schedule that is a current schedule row.
- **Board**: the BUYER kanban of Requirement 8.
- **Today**: the current date in `Europe/Warsaw`.

## Requirements

### Requirement 1: Planner read model

**User Story:** As a manager or buyer, I want one list of positions with everything I need on a row,
so that I can plan purchases without opening each position.

#### Acceptance Criteria

1. THE system SHALL expose `GET /api/purchase-positions/planner`, guarded by
   `@RequiresPermission(PURCHASE_POSITIONS, READ)`, in a new `PurchasePlannerController`, returning a
   page of `PlannerRowDto`.
2. THE endpoint SHALL accept: `page`, `size` (default 20, max 100), `sort` (`siteDeliveryDate` default
   ascending with nulls last, `needByDate`, `orderByDate`, `decisionDeadline`, `createdDate`, `status`;
   direction `asc|desc`), `projectId`, `status` (repeatable), `sourcingPath`, `requestId`, `noRequest`
   (boolean: only positions without a request), `buyer` (`ALL` default, `MINE`, `UNASSIGNED`), `slice`
   (Requirement 2), `urgent` (boolean), `q` (case-insensitive text over material model, SKU, producer
   name, seller name, request reference number and notes), `hideDelivered` (FOR-11-00 Requirement 22
   semantics). An unknown `sort` or `slice` value SHALL be rejected with 400
   (`error.purchasePlanner.sort.invalid`, `error.purchasePlanner.slice.invalid`).
3. EVERY filter SHALL compose with every other, with pagination and with project scoping, and SHALL be
   applied in the database query so totals and pages are correct.
4. THE result SHALL be scoped by the existing `ProcurementProjectAccess` rule (ADMIN and BUYER: all
   projects; others: ACTIVE-membership projects); a caller with no accessible project SHALL get an
   empty page.
5. `buyer = MINE` SHALL keep positions whose request is assigned to the caller; `UNASSIGNED` SHALL keep
   positions with no request or a request without an assigned buyer.
6. EACH row SHALL carry: the identifiers and enums of the position; the **effective material label**
   (`model`, `sku`, `producerName`, `sellerId`, `sellerName`, `link`, `photo` of
   `COALESCE(actualMaterial, plannedMaterial)`) and the planned material id; `quantity`, `unitName`,
   `orderedQuantity`, `acceptedQuantity`; `blockNo`; `deliveryCategoryName`; the four dates and
   `siteDeliveryDate`; `requestId`, `requestReferenceNumber`, `assignedBuyerName`; the work-link
   fields of Requirement 5.6 (`workCategoryId`, `effectiveWorkCategoryId/Name`, `workCategorySource`,
   `workCategoryCandidates`, `workStartDate`, `suggestedNeedByDate`); the derived flags `late`,
   `urgent`, `deliveryRisk`, `hasVariants`, `variantCount`; the Money block (Requirement 4); and
   `allowedActions`.
7. `allowedActions` SHALL list, for the caller and the row, the manual actions the UI may offer, taken
   from the existing `PositionTransitionPolicy` and role rules (not re-implemented in the frontend):
   `EDIT`, `DELETE`, `SEND_TO_BUYER` (`PACKAGE`, `PLANNED`, MANAGER/ADMIN), `MARK_CLIENT_OWN_IN_DELIVERY`
   (`CLIENT_OWN`, `PLANNED`, MANAGER/ADMIN), `CANCEL`, `REOPEN`, `REPLACE_VARIANT` (stage `PLANNED` or
   `PROPOSED`). A caller holding only READ SHALL receive an empty list.
8. Names (`unitName`, `producerName`, `sellerName`, `effectiveWorkCategoryName`, `deliveryCategoryName`) SHALL be
   resolved to the caller's locale.
9. THE endpoint SHALL NOT issue per-row queries (batched joins/fetches; a test asserts a constant
   number of statements for a page of 100 rows).

### Requirement 2: Slices, urgency and lateness

**User Story:** As a manager, I want quick slices and an urgent pick, so that I can see what slips and
take the most urgent positions first.

#### Acceptance Criteria

1. A position is **late** WHEN it is not in a Terminal_Stage AND (its Site_Delivery_Date is before
   Today OR (its `orderByDate` is before Today AND its stage is before `ORDERED`)).
2. Slice `ON_SCHEDULE` SHALL keep non-terminal positions that are not late; `LATE` SHALL keep late
   positions; `CHANGED` SHALL keep positions with `variantCount > 0`; `PAID` SHALL keep `AGREED`
   positions at stage `CLIENT_PAID` or later (excluding `CANCELLED`); `NOT_PAID` SHALL keep `AGREED`
   positions before `CLIENT_PAID` (excluding `CANCELLED`); `ALL` SHALL apply no slice. `PACKAGE` and
   `CLIENT_OWN` positions skip the proforma gate, so they never appear in `PAID` or `NOT_PAID`.
3. THE slices SHALL be mutually exclusive filter values (single select) and compose with the other
   filters.
4. THE definitions SHALL live in one pure `PlannerSliceRules` class used both for the row flags and for
   the SQL predicates; a test asserts that the SQL result equals the pure result over a matrix of
   stage / path / date / variant combinations.
5. A position is **urgent** WHEN it is not in a Terminal_Stage, its stage is before `ORDERED`, and its
   Site_Delivery_Date is on or before Today plus `foremen.procurement.urgent-days` (default 14), or it
   is late. `urgent = true` SHALL filter to those positions.
6. `GET /api/purchase-positions/planner/slice-counts` (same filters except `slice`) SHALL return the
   count per slice so the UI can show badges; counts SHALL use the same predicates.

### Requirement 3: Grouping and group totals

**User Story:** As a manager, I want positions grouped by internal request with totals, so that I can
see what each request is worth.

#### Acceptance Criteria

1. THE system SHALL expose `GET /api/purchase-positions/planner/groups` with all planner filters
   (`projectId`, `status`, `sourcingPath`, `buyer`, `slice`, `urgent`, `q`, `hideDelivered`) plus `groupBy` (`REQUEST`,
   default; `NONE` returns a single synthetic group), returning a page of groups ordered by the earliest
   Site_Delivery_Date of the group (nulls last), then by request id.
2. EACH group SHALL carry: `key`, `requestId` (null for the synthetic "no request" group), the request
   header fields (`referenceNumber`, `referenceLink`, `expectedDeliveryDate`, `assignedBuyerId`,
   `assignedBuyerName`, derived `status` and `overdue`), `positionCount` (after filters), and the
   totals of Requirement 4.5.
3. THE rows of a group SHALL be loaded through the planner endpoint with `requestId` or `noRequest`
   (lazy, per expanded group); the groups endpoint never returns rows.
4. `groupBy` values for external orders and proformas are **reserved**: a request with `groupBy =
   EXTERNAL_ORDER` or `PROFORMA` SHALL be rejected with 400 `error.purchasePlanner.groupBy.invalid`
   until FOR-11-03 adds them.
5. Group totals SHALL be computed over the same filtered set as the rows and SHALL reconcile: the sum of
   the group totals across all pages equals the totals of the flat list for the same filters (property
   test).

### Requirement 4: Money math, margin and delivery-cost allocation

**User Story:** As a manager, I want each position's margin with its share of the delivery cost, so
that I see what a purchase really earns.

#### Acceptance Criteria

1. THE system SHALL provide a pure `PositionMoneyMath` working in PLN with `BigDecimal` and
   `RoundingMode.HALF_UP`: line net = `round2(unitNet × quantity)`, line VAT = `round2(lineNet × rate /
   100)`, line gross = net + VAT; totals are the **sum of the rounded lines**, so a total never differs
   from the sum of what the rows show.
2. THE system SHALL provide a pure `PositionMarginCalculator`: revenue = sale line net; cost =
   `round2(unitPurchaseNet × quantity) + deliveryCostShare`, where `unitPurchaseNet` is
   `actualPurchasePrice` when set, otherwise `plannedPurchasePrice`; the margin is computed with the
   existing `MarginCostService.materialMargin(revenue, cost)` (amount and percentage of revenue). Margin
   is **unavailable** (null) when the sale price or both purchase prices are null; a zero revenue yields
   a null percentage.
3. THE system SHALL provide a pure `DeliveryCostAllocator.allocate(totalCost, weights)` that splits an
   amount across positions **proportionally to their cost** (weight = purchase line net) with the
   largest-remainder method on cents, so the shares **sum exactly** to the total; zero total weight
   splits evenly; an empty weight list returns an empty list. It is delivered and tested here and is
   consumed by FOR-11-03 when an external order carries a delivery cost. This spec does not write
   `deliveryCostShare`.
4. THE Money block of a row SHALL contain `salePrice`, `plannedPurchasePrice`, `actualPurchasePrice`,
   `purchaseVatRateId`, `saleVatRateId`, `deliveryCostShare`, `saleNetTotal`, `saleGrossTotal`,
   `costNetTotal`, `marginAmount`, `marginPct` and `overCeiling` (`actualPurchasePrice > salePrice`),
   and SHALL pass through `PositionPriceVisibility`: for every audience except `STAFF_FULL` the whole
   block is `null`.
5. GROUP totals SHALL be: `saleNet`, `saleGross`, `costNet`, `deliveryCost`, `marginAmount`,
   `marginPct`, summed over positions that are not `CANCELLED`; positions with an unavailable margin
   are counted in `positionCount` but skipped in the margin sum, and the group carries
   `marginIncomplete = true` when any were skipped. For a non-STAFF_FULL caller the totals are `null`.
6. Sorting or filtering by a Money field is not offered by the planner endpoint (no such parameter);
   the 00 rule `error.purchasePosition.field.forbidden` still applies to the generic list.

### Requirement 5: Link to the work schedule (automatic by material consumption, manual override)

**User Story:** As a manager, I want each position tied automatically to the scheduled work that uses
its material, and to be able to correct that link, so that I see when the work starts and whether the
delivery is late for it without maintaining the link by hand.

#### Acceptance Criteria

1. **Automatic resolution.** THE system SHALL resolve a position's **effective work category** from its
   material type through the **material consumption norms of the work catalog**
   (`work_material_consumptions`, branch `finishing`): effective material = `COALESCE(actualMaterial,
   plannedMaterial)`; material type = its `type`; candidate works = works that consume that type. The
   resolution SHALL run in two tiers and stop at the first that yields a scheduled category:
   1. **Tier 1 (estimate):** candidate works limited to the works of the project's **estimate lines**;
   2. **Tier 2 (catalog):** candidate works from the whole work catalog (used for ad hoc materials that
      the estimate does not cover).
   Candidate works map to their `work_category`; a candidate category counts only when it is
   **scheduled in the project** (Requirement 5.2).
2. **Scheduled category.** A work category is *scheduled in the project* WHEN the project's schedule holds
   a stored bar for it AND it is a current schedule row (the existing readiness rule: bars of categories
   that are no longer rows are ignored). The system SHALL expose
   `GET /api/purchase-positions/scheduled-categories?projectId=&q=` (`PURCHASE_POSITIONS` READ, scoped by
   project access, 404 for an inaccessible project) returning `{ id, name, startDate }` of those categories
   (name in the caller's locale, `startDate` per Requirement 5.5, earliest bar start), filtered by a
   case-insensitive `q` over `nameRU`/`namePL` (SQL wildcards in `q` escaped), ordered by `startDate`
   then name, limited to 50 rows.
3. **Choice among several matches.** WHEN more than one candidate category is scheduled in the first
   tier that yields any, THE system SHALL pick the one with the **earliest start date** (the material
   must arrive before the first work that consumes it). Ties are broken by name, then by id, so the
   result is deterministic. The other scheduled candidates SHALL be returned as
   `workCategoryCandidates` (with their start dates) and do not change the choice. WHEN nothing matches
   (no material type, no consumption norm, or no matching category scheduled), `workCategorySource` is
   `NONE` and the position has no effective category.
4. **Manual override.** THE system SHALL add a nullable `workCategoryId` field to the position (create,
   update and DTO). It stores only the manual override; the automatic result is never stored. The value
   SHALL be validated (the category exists and is active; it need NOT be scheduled), otherwise 400
   `error.purchasePosition.workCategory.invalid`. It SHALL be changeable only before stage `ORDERED`
   (otherwise 409). WHEN it is set, the effective category is that category with
   `workCategorySource = MANUAL`, and it survives a change of the material. Resetting to automatic is
   `DELETE /api/purchase-positions/{id}/property?properties=workCategory` (the generic clear-property
   endpoint of the CRUD framework, with the same stage lock); a `PUT` with a null value is not the reset
   path.
5. **Work start date.** `workStartDate` of a row SHALL be the calendar date of the **earliest** bar
   start of the effective category in the project's schedule, converted with the same anchor and
   calendar rule as `ScheduleCalculator` (read it before implementing; half-day offsets from the project
   start date). It is `null` when there is no schedule, no bar, no project start date or no effective
   category.
6. **Row fields.** THE planner row SHALL expose `workCategoryId` (the manual override or null),
   `effectiveWorkCategoryId`, `effectiveWorkCategoryName` (caller's locale), `workCategorySource`
   (`MANUAL`, `AUTO`, `NONE`), `workCategoryCandidates`, `workStartDate` and `suggestedNeedByDate`.
7. **Suggested need-by date and delivery risk.** `suggestedNeedByDate` SHALL equal `workStartDate`
   (null when there is none); it is only a suggestion that the form can copy into `needByDate` ("set
   from schedule"). `deliveryRisk` SHALL be true WHEN `workStartDate` and the Site_Delivery_Date are both
   not null and the Site_Delivery_Date is after `workStartDate`; otherwise false. Board cards use the
   same rule.
8. **Computed on read.** THE automatic link SHALL be recomputed on every read and SHALL NOT be
   persisted, so a variant replacement, an estimate change or a schedule change re-resolves without
   editing the position.
9. THE resolution SHALL be implemented as a pure class `WorkCategoryResolver` (inputs: material type id, estimate-work
   consumption map, catalog consumption map, scheduled categories with start dates; output: effective
   category, source, candidates) so the tiers and the earliest-start rule are unit- and property-tested
   without a database; the data loading SHALL be batched per page (no per-row queries).
10. THE system SHALL expose `GET /api/purchase-positions/work-link-preview?projectId=&materialId=`
    (`PURCHASE_POSITIONS` READ, scoped) returning the automatic resolution (`effectiveWorkCategoryId`,
    `effectiveWorkCategoryName`, `workCategorySource`, `workCategoryCandidates`, `startDate`) for a material
    in a project before the position exists, so the form shows the correct link while the user creates or
    changes the position.
11. THE field SHALL NOT be added to the FOR-10 position dataset in this spec (reviewed against the
    managed-entity checklist: no dashboard use case yet).

### Requirement 6: Selection total (tick-to-proforma)

**User Story:** As a manager, I want to tick accepted positions and see their total, so that I know the
amount of the proforma I am about to issue.

#### Acceptance Criteria

1. THE system SHALL expose `POST /api/purchase-positions/selection-total` with body `{ positionIds }`
   (1..500 ids; otherwise 400 `error.purchasePlanner.selection.empty` / `.tooLarge`), in a new
   `PurchaseSelectionController`, guarded by `@RequiresPermission(PURCHASE_POSITIONS, READ)` and
   restricted to the `STAFF_FULL` audience (others get 403 `error.access.denied`).
2. THE response SHALL contain `count`, `saleNet`, `vat`, `saleGross` (Requirement 4.1), the `vatBreakdown`
   per sale-VAT rate, `projectId` (when all counted positions share one project) and a `skipped` list
   `{positionId, reason}`.
3. A position SHALL be **counted** only when it is `AGREED`, in stage `ACCEPTED`, has a sale price and
   belongs to a project the caller may access. Otherwise it SHALL be listed in `skipped` with reason
   `NOT_ACCEPTED`, `NOT_AGREED`, `NO_SALE_PRICE` or `NOT_FOUND` (an inaccessible position is reported as
   `NOT_FOUND`, never revealing it).
4. WHEN the counted positions belong to more than one project, the response SHALL set
   `mixedProjects = true` and the UI SHALL block "proforma" use (FOR-11-03 needs one project).
5. THE endpoint SHALL be read-only and SHALL NOT change any position.

### Requirement 7: Create a request from positions

**User Story:** As a manager, I want to put ticked positions into an internal request in one action, so
that the buyer gets a single work item.

#### Acceptance Criteria

1. THE system SHALL expose `POST /api/purchase-requests/from-positions` with body `{ positionIds,
   deliveryCategoryId?, referenceNumber?, referenceLink?, expectedDeliveryDate?, assignedBuyerId?,
   notes? }`, guarded by `@RequiresPermission(PURCHASE_REQUESTS, CREATE)`, returning the created
   request DTO.
2. THE endpoint SHALL run in **one transaction** and reuse the 00 services: create the request with the
   existing validation (buyer role, project), then attach the positions with the existing attach rules
   (same project, no other request, not `CANCELLED`) and the `plannedDeliveryDate` synchronizer; any
   failure SHALL roll everything back and return the 00 error key.
3. `positionIds` SHALL be non-empty and all of ONE project (otherwise 400
   `error.purchaseRequest.fromPositions.empty` / 400 `error.purchaseRequest.fromPositions.mixedProjects`).
4. WHEN `assignedBuyerId` is given the existing assignment event of FOR-11-00 SHALL be published by the
   00 code path (no notification code is added here).
5. THE endpoint SHALL be reachable by MANAGER and ADMIN (the 00 grants); a BUYER (who has only
   READ/UPDATE on requests) SHALL get 403.

### Requirement 8: BUYER board

**User Story:** As a buyer, I want a board of everything released to me by stage, so that I see what to
order, pay, track and receive.

#### Acceptance Criteria

1. THE system SHALL expose `GET /api/purchase-positions/board`, guarded by
   `@RequiresPermission(PURCHASE_POSITIONS, READ)`, in a new `PurchaseBoardController`, accepting
   `projectId`, `buyer` (`ALL`, `MINE`, `UNASSIGNED`; default `ALL`), `q`, `hideDelivered` and
   `limit` (cards per column, default 30, max 100; otherwise 400 `error.purchasePlanner.board.limit.invalid`).
2. THE response SHALL contain six columns in this fixed order: `WITH_BUYER`, `ORDERED`, `SUPPLIER_PAID`,
   `IN_DELIVERY`, `PARTIALLY_RECEIVED`, `RECEIVED`. Each carries `status`, `statusCode`, `count` (all
   matching positions), `cards` (up to `limit`, ordered by Site_Delivery_Date ascending nulls last, then
   id) and `hasMore`.
3. WHEN `hideDelivered = true` the `RECEIVED` column SHALL be returned with `count` and no cards.
4. EACH card SHALL carry: `positionId`, `projectId`, `projectName`, material label (model, producer,
   photo), `sellerName`, `quantity`, `unitName`, `requestId`, `requestReferenceNumber`,
   `siteDeliveryDate`, `late`, `deliveryRisk`, `orderByDate`, `sourcingPath`, and the Money subset
   `salePrice`, `plannedPurchasePrice`, `actualPurchasePrice`, `overCeiling` (null for non-STAFF_FULL).
5. THE board SHALL include only `AGREED` positions that passed the gate and `PACKAGE` / `CLIENT_OWN`
   positions that reached the listed stages; positions before `WITH_BUYER` never appear.
6. THE board SHALL be scoped like the planner (BUYER and ADMIN: all projects). Further cards of a column
   are loaded through the planner endpoint with `status={column}` (Requirement 1), not through the board.
7. THE board is **read-only**: there is no write endpoint in this requirement and no drag-and-drop; a
   card links to the position detail (Requirement 13).

### Requirement 9: Access and price visibility

**User Story:** As the business owner, I want purchase prices visible only to staff, so that margin
stays confidential.

#### Acceptance Criteria

1. THE new endpoints SHALL NOT add resources, roles or grants; they reuse `PURCHASE_POSITIONS` READ
   (planner, slice counts, groups, board, selection total) and `PURCHASE_REQUESTS` CREATE (from
   positions). A caller without the grant SHALL get 403.
2. EVERY Money value (rows, cards, group totals, selection total) SHALL pass through
   `PositionPriceVisibility`: `STAFF_FULL` (ADMIN, MANAGER, BUYER) sees all; `SITE` (FOREMAN and any
   other READ-only role) sees `null`; no endpoint of this spec is exposed to the `CLIENT` audience.
3. THE frontend SHALL hide every money column, total bar and margin cell whenever the corresponding
   values are `null`, and SHALL hide all write controls for callers without the matching grant or an
   empty `allowedActions`.
4. THE FOREMAN SHALL see the planner as a read-only list (no money, no selection, no actions) limited to
   the projects of his ACTIVE memberships.
5. A caller SHALL never be able to infer a hidden price through the planner: the endpoints expose no
   money filter or sort, and slice rules use only non-money data.

### Requirement 10: Configuration and migration

#### Acceptance Criteria

1. THE changeset `233-add-purchase-positions-work-category.xml` SHALL add `work_category_id` and its
   index idempotently (`columnExists` / `indexExists`, `onFail="MARK_RAN"`) and SHALL be registered
   **last** in `changelog.xml`. If a sibling spec adds a changeset first, use the next free number.
2. `application.yml` SHALL contain `foremen.procurement.urgent-days: ${FOREMEN_PROCUREMENT_URGENT_DAYS:14}`
   (range 1..90; invalid values fail startup validation); `.env.example` and the `backend.environment`
   block of `docker-compose.yml` SHALL pass the variable with a default.
3. NO new ABAC resource, role seed, dashboard dataset or widget is added (reviewed against the
   managed-entity checklist; recorded in the design).

### Requirement 11: Backend i18n

#### Acceptance Criteria

1. EVERY new `error.*` key listed in the design SHALL exist in `messages.properties` (PL) and
   `messages_ru.properties` (RU).
2. A parity test (modeled on the 00 messages parity test) SHALL fail when a new key is missing in either
   bundle.

### Requirement 12: Deliveries page shell, tabs and URL state

**User Story:** As a user of the procurement module, I want one Dostawy screen that fits my role, so
that I find my work immediately.

#### Acceptance Criteria

1. THE `/deliveries` route SHALL render the new `PurchasePlannerPage`; the placeholder page and its
   `pages.deliveries.*` keys SHALL be removed. The navigation item, route permission and BUYER bottom
   bar from FOR-11-00 SHALL stay unchanged.
2. THE page SHALL show tabs by role and permission: **Planner** (everyone with READ), **Buyer board**
   (BUYER, MANAGER, ADMIN). The default tab SHALL be **Board** for `BUYER` and **Planner** for every
   other role; a FOREMAN sees only the Planner in read-only mode.
3. THE state of the page SHALL be kept in the URL query string so views can be shared and survive a
   reload: `tab`, `project`, `slice`, `urgent`, `groupBy`, `q`, `request`, `buyer`, `page`.
4. THE **project filter** SHALL list the projects from `GET /api/procurement/projects`; the planner
   opened by a BUYER or ADMIN with no project selected is the **company-wide purchase view** (all
   accessible projects, a project column visible); for MANAGER and FOREMAN the project defaults to
   the working project when set, otherwise "all my projects".
5. THE **"hide delivered" switcher** SHALL default to ON, SHALL be remembered per user in
   `localStorage` under `foremen:purchase-planner:hide-delivered:{userId}`, SHALL tolerate missing or
   corrupt storage, and SHALL be passed as `hideDelivered` to the planner, groups, slice-counts and board
   calls.
6. THE page SHALL be responsive: tables render as cards on small screens (the shared `DataTable`
   behavior), the board columns scroll horizontally on desktop and are shown one column at a time with a
   column selector on mobile.

### Requirement 13: Planner table

**User Story:** As a manager, I want a sortable, filterable table with groups, slices and a running
total, so that I can pick positions for a proforma in seconds.

#### Acceptance Criteria

1. THE table SHALL show, per row: selection checkbox, project (only when no single project is filtered),
   material (photo thumbnail, model, producer, link icon), seller, quantity with unit, stage badge,
   sourcing-path badge, `blockNo`, Site_Delivery_Date, `needByDate`, `orderByDate`,
   `decisionDeadline`, work start (with a risk marker when `deliveryRisk`), request reference, and, for
   STAFF_FULL only, sale net total, purchase net total, margin (amount and %) with an incomplete marker.
   Late rows SHALL be highlighted and `overCeiling` SHALL be flagged.
2. THE toolbar SHALL offer: slice buttons with counts (Requirement 2.6), the **Urgent** toggle, a text
   search, the project filter, the stage and sourcing-path filters, the buyer filter (`ALL`/`MINE`/
   `UNASSIGNED`), the group-by selector (`REQUEST` default, `NONE`), the hide-delivered switcher, and
   "Add position" (hidden without `PURCHASE_POSITIONS`/CREATE).
3. IN grouped mode THE table SHALL show collapsible group rows (the 00-style `DocumentGroupRow`
   pattern): header with request reference, expected delivery date, assigned buyer, derived status,
   overdue marker, position count and the group totals; rows load lazily on expansion; the synthetic
   "no request" group is listed last.
4. THE user SHALL be able to **tick** positions; the sticky **selection bar** SHALL show the count and the
   server-computed totals from `POST /selection-total` (debounced, net / VAT / gross), the list of
   skipped positions with reasons, the `mixedProjects` warning, and the actions "Create request"
   (Requirement 15) and "Clear". Only positions the UI can tick are those with a sale price; non-counted
   ticks remain allowed so the skipped reasons are visible. The bar is hidden for non-STAFF_FULL users.
5. A **"Select urgent"** action SHALL tick all urgent positions on the current page.
6. THE table SHALL support server-side sorting by the columns of Requirement 1.2 and pagination, using the
   shared data-table conventions.
7. THE `/deliveries?request={id}` deep link SHALL switch to the Planner tab, set group-by `REQUEST`,
   clear conflicting filters, expand and scroll to the group of that request and highlight it; WHEN the
   request is not visible to the caller the page SHALL show a "request not found or no access" message
   and remain usable.
8. THE empty and loading states SHALL be skeletons / an explicit empty message with a hint to clear
   filters.

### Requirement 14: Position form, variants and row actions

**User Story:** As a manager, I want to add and edit a position with its material, seller and dates in
one form, and to see or swap its analog options, so that I never leave the planner.

#### Acceptance Criteria

1. THE form (a sheet/dialog) SHALL create and edit a position with the fields of the 00 create/update
   requests: project (create only), sourcing path, kind fixed to `FINISHING`, material, quantity, unit
   (defaults from the material), delivery category, block number, work category, needBy / orderBy /
   decisionDeadline dates, prices (STAFF_FULL only: planned purchase, sale, VAT rates defaulting from the
   00 rule), and notes. Fields that the 00 rules lock for the current stage SHALL be shown disabled.
2. THE **material picker** SHALL search the global finishing materials and the project's materials
   (`/api/project-materials` of the chosen project) in one list, marking project materials; a seller
   filter built on the 00 `SellerPicker` (with inline quick-create) narrows the list by seller.
3. THE picker SHALL offer "Add project material" (visible with `PROJECT_MATERIALS`/CREATE) opening a
   compact form with the fields the 00 API requires (category, material kind/type, unit, model, SKU,
   producer, seller via `SellerPicker`, link, retail net, cost net, offer packages) and selecting the
   created material.
4. THE form SHALL show the **work-category link**: the automatically resolved category with a badge
   "automatic" (or "manual" / "not found"), and a **searchable dropdown** of the categories scheduled in the
   project (`GET /scheduled-categories?q=`, each option with its start date) to change it; selecting an
   option stores the manual override, and "Reset to automatic" clears it. WHEN the source is `NONE` the
   dropdown is highlighted with the hint "choose the work this material is for". **"Set from
   schedule"** next to `needByDate` fills the date from the effective category's start date (editable;
   disabled while there is no start date). Changing the material re-resolves the automatic link.
5. EACH row SHALL expose a **variant-history expander**: the main row shows only the current option; the
   expander lists the retired options (`GET /variants`), lets the user restore one or replace the current
   option through the **suggestions** list (`GET /variants/suggestions`) and `replace` with a reason, and
   is enabled only when `allowedActions` contains `REPLACE_VARIANT` (read-only listing otherwise).
6. THE row action menu SHALL offer only the actions present in `allowedActions`: Edit, Send to buyer,
   Mark client-own in delivery, Cancel, Reopen, Delete; transitions call
   `POST /api/purchase-positions/{id}/transition`, with a confirmation for Cancel and Delete; 409 errors
   show the localized server message.
7. SERVER validation errors (400/409) SHALL be mapped to field or toast messages using the localized
   message returned by the API; the form SHALL keep the user's input on error.

### Requirement 15: Requests from the planner

**User Story:** As a manager, I want to form an internal request from ticked positions and assign a
buyer, so that purchasing starts from a clear work item.

#### Acceptance Criteria

1. THE selection bar action "Create request" SHALL open a dialog with: reference number, reference link,
   expected delivery date (defaulting to the earliest Site_Delivery_Date of the ticked positions),
   delivery category, assigned buyer (active `BUYER` users) and notes, and SHALL call
   `POST /api/purchase-requests/from-positions`. It is disabled when the ticks span several projects.
2. A request group header SHALL offer "Edit request" (reference, link, date, buyer, notes, via the 00
   `PUT`), "Detach position" per row (00 detach rules) and "Delete" for an empty request.
3. AFTER creating or editing a request THE page SHALL refresh the groups, slice counts and selection and
   keep the user's filters.
4. THE dialog and actions SHALL be hidden without the matching `PURCHASE_REQUESTS` grant.

### Requirement 16: Buyer board UI

**User Story:** As a buyer, I want to see my purchases as columns, so that I know what needs my action.

#### Acceptance Criteria

1. THE board SHALL render the six columns of Requirement 8 with title, count and cards; each card shows
   project, material (photo, model, producer), seller, quantity and unit, request reference, site
   delivery date with a late / risk marker, and (STAFF_FULL) the sale price as the ceiling next to the
   actual or planned purchase price with an over-ceiling warning.
2. THE board toolbar SHALL offer the project filter, the buyer filter (`MINE` default for the BUYER role,
   `ALL` otherwise), a text search and the hide-delivered switcher.
3. A column with `hasMore` SHALL show "Load more", which pages the planner endpoint with the column's
   stage and appends cards.
4. A card click SHALL open the position detail drawer (the form in read-only/edit mode per
   `allowedActions`, plus the variant history); there is no drag-and-drop.
5. THE board SHALL refetch on window focus and every 60 seconds while visible (React Query
   `refetchInterval`), with no websocket.

### Requirement 17: Frontend i18n and structure

#### Acceptance Criteria

1. ALL new strings SHALL be keys in `pl.json` and `ru.json` at parity (groups `purchasePlanner.*`,
   `buyerKanban.*`, listed in the design); no hard-coded user-facing text.
2. THE frontend feature SHALL live in `features/purchase-planner/` (`api/`, `components/`, `hooks/`,
   `lib/`, `types/`, `schemas/`, `__tests__/`) and reuse `DataTable`, `buildFetchQuery`,
   `usePermission`, `SellerPicker`, the persisted-filter pattern and the notification deep-link handler;
   it SHALL NOT add a drag-and-drop or kanban library.
3. THE locale parity test (`src/lib/i18n.keys.test.ts`) SHALL pass.

### Requirement 18: Verification and test-cases.md

#### Acceptance Criteria

1. Backend tests: unit and property tests for the pure helpers (money math, margin, allocator, slice
   rules, work-category resolver, work-start resolver mapping), integration tests (Testcontainers) for the planner, slice
   counts, groups, selection total, create-from-positions, board and the work-category field, the
   ABAC/visibility matrix, and the messages parity test.
2. Per `.kiro/steering/test-execution-rules.md` only the affected classes are run (`--tests`, log
   redirected to `/tmp/for-11-01-test.log`, JUnit XML read); the full suite is not run.
3. Frontend tests (vitest): filter/URL-state builder, persisted hide-delivered preference, planner
   table (grouping, selection bar, hidden money for SITE), position form (material picker, set from
   schedule, locked fields), request dialog, board, deep link, and locale parity.
4. A `test-cases.md` (Russian) SHALL be authored per `.kiro/steering/test-cases.md`: Part A API tests
   against the Dockerized app, Part B browser scenarios, run-id generator + teardown, a regression
   group, and the MD report template.

### Requirement 19: Non-regression

#### Acceptance Criteria

1. THE FOR-11-00 classes and tests (position, request, variants, upcoming, hideDelivered, datasets,
   seller picker) SHALL keep passing; the only changed 00 contracts are the additive `workCategoryId` (manual override)
   on the position DTO and requests.
2. NO existing role SHALL gain or lose a grant; no existing endpoint changes its path, semantics or
   default.
3. THE construction-material flow (FOR-06-03/04/05) and the dashboard widgets of FOR-11-00 SHALL be
   unchanged.
4. THE two git repositories SHALL be committed separately (root: backend, changeset, spec; inner
   `foremen-frontend/`: the feature) per `.kiro/steering/git-repo-structure.md`, only when the user
   asks.
