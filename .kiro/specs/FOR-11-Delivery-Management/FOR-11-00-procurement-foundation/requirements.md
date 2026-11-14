# Requirements Document

## Introduction

This spec (**FOR-11-00-procurement-foundation**) is the foundation child of the FOR-11 "Delivery
Management" parent (see `../OVERVIEW.md`). It lays the **domain and access groundwork** for finishing-
material procurement so that the later children (planner, client approval, proformas and external
orders, shipments, goods receipt) build on stable ground. It delivers a backend foundation plus three
thin frontend touches; the procurement screens themselves belong to FOR-11-01.

**What this spec delivers**

1. A new **`BUYER` role** with its own access: it sees all procurement data of **all projects**,
   even without project membership.
2. **Project scope on the existing finishing-material dictionary**: a material can be created
   *inside a project* (any new material, even one that was never in the estimate). Project materials
   are hidden from the global dictionary and pickers and shown in the project's procurement lists.
3. **Sellers by NIP**: a `nip` field on `material_sellers`, a lookup that searches **our seller
   dictionary first** and then an **external NIP registry service**, behind an SPI that ships as a
   **non-working stub** (configurable URL + API key, plain REST/JSON call, disabled by default).
4. The **purchase position** (`PurchasePosition`): the unit of control for finishing-material
   procurement, with sourcing path, planned vs actual item, quantities, prices, VAT rates and three
   dates, plus a **position state machine** that enforces the **"proforma paid" gate**.
5. The **internal purchase request** (`PurchaseRequest`): the Bitrix-card replacement that groups
   positions for the buyer (expected site-delivery date, reference number and link, assigned buyer,
   derived status, derived overdue).
6. The **procurement stage dictionary seeds** (extra `delivery_statuses` rows that label the position
   stages and drive the later buyer kanban), a **price-visibility policy**, the ABAC resources and
   grants, FOR-10 datasets, two notifications, and the configuration.
7. **Inline seller creation**: a one-call "find or create" endpoint taking only a name and an optional
   NIP, plus a reusable frontend seller picker with an inline quick-create form, so a seller can be
   added from the position form without leaving the page (the form itself is FOR-11-01).
8. **Variant history per position**: every replaced or re-agreed material option is kept; the main
   (current) option is shown, the history is available on demand and lets the user pick a known
   analog quickly.
9. **Planner and foreman support**: a `hideDelivered` filter (the planner switcher, default ON in
   the UI) and a **foreman "upcoming deliveries" list** backed by a system-owned
   `plannedDeliveryDate`.
10. **Dashboard widgets**: a dataset extension, library widgets (upcoming deliveries, procurement by
    stage, positions waiting for the buyer) and role defaults, including a working BUYER dashboard.

### Product decisions this spec implements (from `../OVERVIEW.md`, revision 5)

- **Estimate materials are reference only.** A position may carry an optional reference to an
  estimate material line, but it is never bound to it. Positions can be added for materials that were
  never in the estimate.
- **Material kinds.** The model carries `kind` = `FINISHING` / `CONSTRUCTION` / `FURNITURE` from day
  one; "finishing" includes lighting, sockets and similar purchases. **Only `FINISHING` positions can
  be created in this spec**; the construction purchase flow (FOR-06-03/04/05) and its delivery
  tracking are untouched, and furniture comes later.
- **Three sourcing paths:** `CLIENT_OWN` (client buys it himself), `PACKAGE` (client chose from the
  offer package), `AGREED` (agreed with the client). `PACKAGE` and `CLIENT_OWN` **skip** the
  "proforma paid" gate; `AGREED` must pass it.
- **Gate:** until the client has paid the materials proforma **in full**, an `AGREED` position never
  reaches the buyer. This spec enforces the rule in the state machine; the proforma and the "paid"
  signal arrive in FOR-11-03.
- **Substitution** by an analog needs full client acceptance (item and price): `plannedMaterial` vs
  `actualMaterial` (null = same). The acceptance flow is FOR-11-02.
- **Delivery cost** is later allocated to positions proportionally to their cost for the margin
  (FOR-11-01/03); this spec only reserves the `deliveryCostShare` column.
- **VAT:** cost at 23% and sale at 8% are stored as references to the existing VAT-rate dictionary.
- **Single currency (PLN)** is assumed for all procurement amounts in this phase.

### Scope boundaries

- **In scope:** everything listed under "What this spec delivers", the backend i18n bundle entries,
  the thin frontend slice (seller NIP lookup UI, `/deliveries` navigation re-point, BUYER mobile
  bottom bar, locale keys), the spec's own tests and `test-cases.md`.
- **Frontend limits:** this spec ships only the reusable `SellerPicker` + `SellerQuickCreate`
  components and the seller-form NIP lookup; the planner table with its "hide delivered" switcher,
  the variant-history expander and the position form that embeds the picker are built in FOR-11-01;
  the foreman's upcoming-deliveries screen and Gantt overlay in FOR-11-04/05. Until then the foreman
  sees upcoming deliveries through the dashboard widget (Requirement 24).
- **Out of scope (other FOR-11 children):** the planner/kanban/list screens and the position/request
  forms (01); the client acceptance, deadlines, analog proposals and the client-facing views (02);
  proformas, external orders, financier hand-off and bank-ledger linking (03); shipments, tracking and
  the Gantt overlay (04); site receipt (05). Also out: promotion of a project material into the
  global dictionary, `ConstructionMaterial` project scope, any change to the construction purchase
  flow, the production NIP registry integration, and the BUYER dashboard widgets.

### Notifications (confirmed, emit-only)

This spec **only emits** notifications through the generic notification service (in-app bell). It
does not implement any delivery channel. Two types are emitted (see Requirement 13):

| Type | Recipient | Trigger | Deep link |
|------|-----------|---------|-----------|
| `notification.request.assigned` | the newly assigned BUYER | a purchase request is assigned to a buyer (create or change) | `/deliveries?request={id}` |
| `notification.request.overdue` | assigned BUYER (or all active BUYER users when unassigned) and the project's ACTIVE MANAGER members | the request's expected delivery date has passed while it is not completed or cancelled (one-shot) | `/deliveries?request={id}` |

### i18n decisions

- **Backend entity fields requiring i18n:** only `Role` (`BUYER`: `nameRU`/`namePL`,
  `descriptionRU`/`descriptionPL`), `Resource` rows (`name_ru`/`name_pl`, `description_ru`/
  `description_pl` for the three new resources) and the new `delivery_statuses` rows
  (`nameRU`/`namePL`). `PurchasePosition`, `PurchaseRequest`, project materials and sellers carry
  **user data** (notes, reference numbers, links, free-text names): no i18n variants. Enum values
  (kind, sourcing path, position status, request status) are labelled on the frontend.
- **Backend messages:** every new `error.*` key exists in `messages.properties` (PL, default) and
  `messages_ru.properties` (RU).
- **Frontend:** every new user-facing string is a key in both `pl.json` and `ru.json` at parity; the
  keys and values are listed in the design (i18n section).

## Glossary

- **Position** / **PurchasePosition**: one line of finishing-material procurement in one project.
- **Purchase_Request**: the internal request that groups positions for a buyer (replaces the Bitrix
  card). Not the same as an *external order* to a supplier (FOR-11-03).
- **Project_Material**: a `finishing_materials` row with `project_id` set.
- **Global_Material**: a `finishing_materials` row with `project_id IS NULL` (the dictionary as today).
- **Stage**: the code-owned position status; each stage maps to a seeded `delivery_statuses` row.
- **Gate**: the rule that an `AGREED` position enters `WITH_BUYER` only from `CLIENT_PAID`.
- **Money_Field**: `plannedPurchasePrice`, `actualPurchasePrice`, `purchaseVatRate`, `salePrice`,
  `saleVatRate`, `deliveryCostShare`.
- **Procurement_Access**: the project-access rule used by the three new services: ADMIN bypass,
  BUYER all projects, other roles ACTIVE project membership.
- **NIP_Lookup_Service**: the SPI that resolves a NIP to company data from an external registry.

## Requirements

### Requirement 1: BUYER role

**User Story:** As an administrator, I want a dedicated buyer role, so that purchasing staff can work
across all projects without being added to each project.

#### Acceptance Criteria

1. THE system SHALL seed a role with `code = 'BUYER'`, `system = true`, `nameRU` "Закупщик",
   `namePL` "Specjalista ds. zakupów", and RU/PL descriptions, in an idempotent changeset
   (`SELECT COUNT(*) FROM roles WHERE code='BUYER'` = 0 precondition, `MARK_RAN`).
2. THE role SHALL appear in the user form role selector without frontend code (roles are loaded from
   the API) and a user with role `BUYER` SHALL be creatable and able to log in.
3. THE `BUYER` role SHALL receive **only** the grants listed in Requirement 14; it SHALL NOT receive
   grants on bank, payroll, payments, estimate, offer, or user-administration resources.
4. THE `BUYER` role SHALL NOT be added to `ReadOnlyAdminService.ADMIN_STAFF_ROLE_CODES` or to the
   project team-member pickers (BUYER is not a project team member in this phase).
5. THE system SHALL let a `BUYER` read and write procurement data (Requirements 3, 9, 11) of **all
   projects** regardless of project membership, through the shared Procurement_Access rule, without
   relaxing the membership rule of any other service.
6. THE `BUYER` role SHALL NOT be able to read project data outside procurement (rooms, estimate,
   offers, schedule, team): the `PROJECTS` resource is not granted; project names are obtained only
   through the procurement project lookup (Requirement 9.8).
7. THE BUYER dashboard SHALL load without error and SHALL receive the default widgets of
   Requirement 24, showing procurement data of **all projects** without membership.

### Requirement 2: Project scope on the finishing-material dictionary

**User Story:** As a buyer or manager, I want to add a material to a project without polluting the
global dictionary, so that project-specific items stay inside their project.

#### Acceptance Criteria

1. THE `finishing_materials` table SHALL gain a nullable `project_id` (FK to `projects`, indexed) and
   a nullable `seller_id` (FK to `material_sellers`, `ON DELETE SET NULL`), via idempotent changesets
   (`columnExists` / `foreignKeyConstraintExists` / `indexExists` preconditions, `MARK_RAN`).
2. `FinishingMaterialEntity` SHALL map `project` (ManyToOne, lazy) and `seller` (ManyToOne, lazy);
   existing rows SHALL keep `project_id = NULL` (Global_Material) and behave exactly as before.
3. THE list, count and picker queries of the existing `/api/finishing-materials` endpoint SHALL
   exclude Project_Materials (`addRequiredQuery` returns `project_id IS NULL`).
4. THE custom DAO queries used by price-range and consumption features
   (`findByActiveTrueAndRetailNetNotNull`, `findActiveByTypes`) SHALL exclude Project_Materials.
5. Fetching a Project_Material **by id** through code that already holds its id (estimate, offer,
   position views) SHALL keep working.
6. THE existing `/api/finishing-materials` create/update contract SHALL be unchanged; a Global_Material
   cannot be given a project through that endpoint.
7. THE seller SHALL be exposed (read) on the finishing-material DTOs without breaking existing
   clients (additive field `sellerId`/`sellerName`, nullable).
8. `ConstructionMaterialEntity` and its endpoints SHALL NOT change in this spec.

### Requirement 3: Project materials API

**User Story:** As a buyer or manager, I want to create and maintain materials inside a project, so
that I can add any new material to the procurement list.

#### Acceptance Criteria

1. THE system SHALL expose `ProjectMaterialController` at `/api/project-materials`, annotated
   `@PermissionResource("PROJECT_MATERIALS")`, with list, read, create, update and delete over
   Project_Materials.
2. THE list SHALL be scoped by Procurement_Access, accept a `projectId` filter, and return only rows
   with `project_id` set.
3. THE create request SHALL require `projectId`, `categoryId`, `materialId`, `unitId`; and accept
   `typeId`, `producerId`, `sellerId`, `model`, `sku`, `features`, `purchasePrice`, `retailGross`,
   `retailNet`, `link`, `photo`, and SHALL apply the same validation rules as the existing finishing
   material write path (it SHALL reuse that validation, not duplicate it).
4. THE `link` and `photo` fields SHALL follow the existing finishing-material semantics (`photo` is an
   object-storage key obtained through the existing image upload flow).
5. THE `projectId` of an existing Project_Material SHALL NOT be changeable (409 / 400
   `error.projectMaterial.project.immutable`).
6. THE project SHALL be `ACTIVE` to create a Project_Material (409
   `error.projectMaterial.project.notActive`).
7. DELETE SHALL be rejected with 409 `error.projectMaterial.inUse` while any `PurchasePosition`
   references the material (as planned or actual item) or any position variant (history row)
   references it.
8. THE endpoint SHALL NOT create or modify Global_Materials; promotion to the global dictionary is out
   of scope.

### Requirement 4: Seller NIP

**User Story:** As a buyer, I want each seller identified by NIP, so that I can find or add a seller
quickly and avoid duplicates.

#### Acceptance Criteria

1. THE `material_sellers` table SHALL gain a nullable `nip` `VARCHAR(10)` and a **partial unique
   index** on `nip` where `nip IS NOT NULL`, via idempotent changesets.
2. `MaterialSellerEntity` and the seller create/update/response DTOs SHALL expose `nip` (optional).
3. WHEN a `nip` is supplied on create or update, THE system SHALL normalize and checksum-validate it
   with the existing `NipValidator.normalize`, storing the normalized ten digits; an invalid value
   SHALL be rejected with 400 `error.materialSeller.nip.invalid`.
4. A duplicate `nip` SHALL be rejected with 409 `error.materialSeller.nip.duplicate`.
5. Existing sellers (no NIP) SHALL remain valid; `nip` is never required.
6. THE seller list SHALL be filterable and sortable by `nip`.
7. THE seller `nip` SHALL NOT be exposed through any dashboard dataset (sensitive-field rule).

### Requirement 5: Seller lookup by NIP

**User Story:** As a buyer, I want to type a NIP and get the seller from our dictionary or, if it is
not there, from the Polish registers, so that adding a seller takes seconds.

#### Acceptance Criteria

1. THE system SHALL expose `GET /api/material-sellers/lookup?nip={nip}`, guarded by
   `@RequiresPermission(resource = "MATERIAL_SELLERS", operation = "READ")`.
2. THE endpoint SHALL normalize/validate the NIP (400 `error.materialSeller.nip.invalid` on failure).
3. THE endpoint SHALL **search the seller dictionary first**; WHEN a seller with that NIP exists, it
   SHALL respond `source = DICTIONARY` with the seller and SHALL NOT call the external service.
4. OTHERWISE, WHEN the NIP_Lookup_Service is configured, THE endpoint SHALL call it and respond
   `source = REGISTRY` with a `candidate` (`nip`, `name`, `address`, `regon`, `krs`, `registry`); the
   candidate SHALL NOT be persisted.
5. WHEN the registry has no record, THE endpoint SHALL respond `source = NONE`.
6. WHEN the NIP_Lookup_Service is not configured (default), THE endpoint SHALL respond
   `source = NONE` with `registryAvailable = false`, and SHALL NOT fail.
7. WHEN the external call fails (timeout, 5xx, malformed JSON), THE endpoint SHALL respond 502
   `error.nipLookup.unavailable`; THE failure SHALL be logged without the API key.
8. THE response SHALL include `registryAvailable` in all cases.

### Requirement 6: NIP registry service (SPI, shipped non-working)

**User Story:** As a developer, I want a configurable REST seam for the registry lookup, so that the
real service can be connected later by configuration only.

#### Acceptance Criteria

1. THE system SHALL define `NipLookupService` (interface: `Optional<NipLookupResult> lookup(String
   nip)`, `boolean isConfigured()`) following the project's seam pattern (interface +
   `@ConfigurationProperties` + configured/not-configured conditions + disabled fallback).
2. `NipLookupProperties` (`foremen.nip-lookup`) SHALL hold `enabled` (default `false`), `baseUrl`,
   `apiKey` and `timeout` (default 5 s).
3. WHEN `enabled` is true and `baseUrl` is set, THE REST implementation SHALL perform
   `GET {baseUrl}` with query parameter `nip` and header `X-Api-Key: {apiKey}` (when an API key is
   set) and map the JSON object to `NipLookupResult`: `nip`, `name`, `address`, `regon`, `krs`,
   `registry` (`JDG` | `KRS` | `UNKNOWN`); unknown JSON properties SHALL be ignored; HTTP 404 or an
   empty body SHALL map to `Optional.empty()`.
4. THE REST implementation SHALL use Spring's `RestClient` with the configured timeout.
5. THE API key SHALL never be logged or returned in any response.
6. THE default configuration SHALL select `DisabledNipLookupService` (returns `Optional.empty()`,
   `isConfigured() = false`); **the real implementation ships but is not enabled and not verified
   against any real service**.
7. THE properties SHALL be documented in `application.yml`, `.env.example` and `docker-compose.yml`
   (Requirement 15).

### Requirement 7: PurchasePosition model

**User Story:** As a manager, I want every purchase tracked as a position with its own plan, prices
and dates, so that each line can be controlled individually.

#### Acceptance Criteria

1. THE system SHALL map `PurchasePositionEntity` (`purchase_positions`, extends `BaseEntity`) with:
   `project` (required), `kind`, `sourcingPath`, `status`, `plannedMaterial` (required FK to
   `finishing_materials`), `actualMaterial` (nullable; null = same as planned), `deliveryCategory`
   (nullable FK to `delivery_categories`), `blockNo` (nullable int), `quantity`, `unit` (required FK to
   `measurement_units`), `orderedQuantity`, `acceptedQuantity`, `plannedPurchasePrice`, `salePrice`,
   `actualPurchasePrice`, `purchaseVatRate` and `saleVatRate` (nullable FKs to `vat_rates`),
   `deliveryCostShare`, `needByDate`, `orderByDate`, `decisionDeadline`, `plannedDeliveryDate`
   (system-owned, nullable: the date the goods are planned to arrive on site), `purchaseRequest`
   (nullable FK), `estimateReference` (nullable FK to `estimate_line_room_materials`), `notes`.
2. `kind` SHALL be one of `FINISHING`, `CONSTRUCTION`, `FURNITURE`; `sourcingPath` one of
   `CLIENT_OWN`, `PACKAGE`, `AGREED`; both stored as `VARCHAR` enum names.
3. Create SHALL accept only `kind = FINISHING`; any other kind SHALL be rejected with
   `error.purchasePosition.kindNotSupported`.
4. `quantity` SHALL be `> 0` (scale 3); `orderedQuantity`, `acceptedQuantity` (default 0) and
   `plannedDeliveryDate` are system-owned (the first two written by FOR-11-03/05, the date by
   Requirement 11.11 and later by FOR-11-04); all amounts SHALL be `>= 0` (scale 2) and are **net unit
   prices in PLN**.
5. `plannedMaterial` SHALL be a Global_Material or a Project_Material **of the same project**;
   `actualMaterial` likewise.
6. THE `unit` SHALL default to the planned material's unit when omitted on create.
7. THE `purchaseVatRate` / `saleVatRate` SHALL default to the VAT-rate dictionary entries with rates
   23 and 8 respectively when those exist and the request omits them; otherwise they remain null.
8. THE table SHALL have indexes on `project_id`, `status`, `purchase_request_id`, `need_by_date`, and
   a CHECK constraint restricting `kind` to `FINISHING` for now.
9. THE entity SHALL be audited through the existing audit mechanism of `AdminService`.

### Requirement 8: Position lifecycle and the proforma-paid gate

**User Story:** As the business owner, I want purchasing to start only after the client has paid the
proforma, so that we never buy on our own money.

#### Acceptance Criteria

1. THE system SHALL define the stages `PLANNED`, `PROPOSED`, `ACCEPTED`, `IN_PROFORMA`, `CLIENT_PAID`,
   `WITH_BUYER`, `ORDERED`, `SUPPLIER_PAID`, `IN_DELIVERY`, `PARTIALLY_RECEIVED`, `RECEIVED`,
   `CANCELLED`, and a pure `PositionTransitionPolicy` that decides whether `(path, from, to, actor)`
   is allowed, `actor` being `MANUAL` (a user through the REST API) or `SYSTEM` (internal services of
   later children).
2. THE forward chain for `AGREED` SHALL be `PLANNED -> PROPOSED -> ACCEPTED -> IN_PROFORMA ->
   CLIENT_PAID -> WITH_BUYER -> ORDERED -> SUPPLIER_PAID -> IN_DELIVERY -> PARTIALLY_RECEIVED ->
   RECEIVED`; allowed back edges: `PROPOSED -> PLANNED`, `ACCEPTED -> PROPOSED`, `IN_PROFORMA ->
   ACCEPTED`, `IN_DELIVERY <-> PARTIALLY_RECEIVED`, `RECEIVED -> PARTIALLY_RECEIVED`.
3. **Gate:** for `AGREED`, entering `WITH_BUYER` SHALL be allowed only from `CLIENT_PAID`, and
   `CLIENT_PAID` SHALL be reachable only by `SYSTEM` from `IN_PROFORMA`; no `MANUAL` transition SHALL
   enter `PROPOSED` .. `CLIENT_PAID` or `WITH_BUYER` for `AGREED`.
4. For `PACKAGE`, `PLANNED -> WITH_BUYER` SHALL be allowed (`MANUAL`, MANAGER/ADMIN); for
   `CLIENT_OWN`, `PLANNED -> IN_DELIVERY` SHALL be allowed (`MANUAL`, MANAGER/ADMIN). From those
   points the chain continues as in 8.2 (`PACKAGE`: `WITH_BUYER -> ORDERED -> ...`;
   `CLIENT_OWN`: `IN_DELIVERY -> PARTIALLY_RECEIVED -> RECEIVED`).
5. `CANCELLED` SHALL be reachable from any stage before `RECEIVED` (`MANUAL`, MANAGER/ADMIN) and
   `CANCELLED -> PLANNED` (reopen) SHALL be allowed; `RECEIVED` has no exit other than the back edge
   in 8.2, and `CANCELLED` has no exit other than reopen.
6. THE system SHALL expose `PositionLifecycleService.transition(positionId, target, actor)` as the
   single write path of `status`; every transition SHALL be validated by the policy and audited;
   an invalid one SHALL be rejected with 409 `error.purchasePosition.transition.notAllowed`, and a
   gate violation with `error.purchasePosition.transition.gateViolation`.
7. THE REST endpoint `POST /api/purchase-positions/{id}/transition` SHALL accept only `MANUAL`
   transitions (8.4, 8.5); every other edge is reachable only through internal services.
8. EACH stage SHALL map to a seeded `delivery_statuses` code (Requirement 12) through a single
   `PositionStage` mapping used by every consumer.

### Requirement 9: Position API, scoping and access

**User Story:** As a manager or buyer, I want to create, list and maintain positions, so that the
procurement plan reflects what must be bought.

#### Acceptance Criteria

1. THE system SHALL expose `PurchasePositionController` implementing `AdminController` at
   `/api/purchase-positions`, annotated `@PermissionResource("PURCHASE_POSITIONS")`.
2. THE service SHALL implement `ProjectScopedService` with `getProjectIdPath() = "project.id"` and
   `allowedProjectIds(userId)` provided by Procurement_Access: ADMIN bypass (existing), **BUYER: all
   project ids**, others: ACTIVE `ProjectMember` projects.
3. THE list SHALL support filters on `project.id`, `status`, `sourcingPath`, `kind`,
   `purchaseRequest.id`, `needByDate`, `orderByDate`, `decisionDeadline`, `plannedDeliveryDate`, and
   sorting by the same fields and by `createdDate`; it SHALL also accept the `hideDelivered`
   parameter of Requirement 22.
4. Create SHALL require an `ACTIVE` project (409 `error.purchasePosition.project.notActive`), valid
   enums, a valid planned material (Requirement 7.5) and `quantity > 0`.
5. Update SHALL NOT change `status`, `orderedQuantity`, `acceptedQuantity`, `deliveryCostShare` or
   `plannedDeliveryDate` (system-owned; supplying them SHALL be rejected with 400); the planned
   material and the agreed prices are changed only through the variant operations of Requirement 21
   once the position has a history, and directly only while the position has no variant yet; `project` SHALL be immutable;
   `plannedMaterial`, `sourcingPath` and `quantity` SHALL be editable only while the stage is
   `PLANNED` or `PROPOSED`; `actualMaterial` and `actualPurchasePrice` only from `WITH_BUYER` on.
6. Delete SHALL be allowed only in `PLANNED` or `CANCELLED` (409 `error.purchasePosition.delete.notAllowed`
   otherwise) and only for MANAGER/ADMIN.
7. A position SHALL belong to at most one purchase request.
8. THE system SHALL expose `GET /api/procurement/projects` returning `{id, name, address, status}` of
   the projects the caller may work with in procurement (BUYER and ADMIN: all `ACTIVE` projects;
   others: their ACTIVE-membership projects), guarded by `PURCHASE_POSITIONS`/READ, for the project
   filter.
9. A caller without membership (and not BUYER/ADMIN) SHALL see no positions and SHALL get 403/404 for
   a position of another project.

### Requirement 10: Price visibility

**User Story:** As the business owner, I want purchase prices hidden from people who must not see
them, so that margin stays confidential.

#### Acceptance Criteria

1. THE system SHALL provide a pure `PositionPriceVisibility` policy deciding, per audience, which
   Money_Fields are visible.
2. AUDIENCE `STAFF_FULL` (ADMIN, MANAGER, BUYER): all Money_Fields visible.
3. AUDIENCE `SITE` (FOREMAN and any other role holding `PURCHASE_POSITIONS`/READ): **no
   Money_Field** is visible; they are serialized as `null`.
4. AUDIENCE `CLIENT` (not exposed by any endpoint in this spec): only `salePrice` of an `AGREED`
   position in stage `ACCEPTED` or later; never any purchase-side field; **nothing** for `PACKAGE`
   and `CLIENT_OWN`. The policy is implemented and unit-tested now so later client-facing endpoints
   cannot leak.
5. THE controller/mapper layer SHALL apply the policy to every position DTO (list and single).
6. A non-`STAFF_FULL` caller SHALL NOT be able to filter or sort by a Money_Field (rejected with 400
   `error.purchasePosition.field.forbidden`), so that values cannot be inferred.

### Requirement 11: PurchaseRequest (internal request) model and API

**User Story:** As a manager, I want to group positions into an internal request for a buyer, so that
the buyer gets a clear work item with a site-delivery date.

#### Acceptance Criteria

1. THE system SHALL map `PurchaseRequestEntity` (`purchase_requests`) with `project` (required),
   `deliveryCategory` (nullable), `referenceNumber` (nullable text, free), `referenceLink` (nullable
   URL), `expectedDeliveryDate` (nullable), `assignedBuyer` (nullable FK to users), `notes`,
   `overdueNotifiedAt`, and the inverse `positions` collection.
2. THE system SHALL expose `PurchaseRequestController` implementing `AdminController` at
   `/api/purchase-requests`, `@PermissionResource("PURCHASE_REQUESTS")`, and a project-scoped service
   using Procurement_Access.
3. `assignedBuyer` SHALL reference an active user whose role is `BUYER` (400
   `error.purchaseRequest.buyer.invalid`).
4. `POST /api/purchase-requests/{id}/positions` (body `{positionIds}`) SHALL attach positions that
   belong to the same project, are not in another request and are not `CANCELLED`
   (`error.purchaseRequest.position.projectMismatch`, `.alreadyAssigned`).
5. `DELETE /api/purchase-requests/{id}/positions/{positionId}` SHALL detach a position only while its
   stage is `PLANNED`, `PROPOSED` or `ACCEPTED` (409 `error.purchaseRequest.position.detachNotAllowed`).
6. DELETE of a request SHALL be allowed only when it has no positions.
7. THE request DTO SHALL include derived `status`, derived `overdue`, `positionCount`; no Money_Field.
8. **Derived status** (pure `PurchaseRequestStatusDeriver`): no positions -> `OPEN`; all positions
   `CANCELLED` -> `CANCELLED`; all non-cancelled `RECEIVED` -> `COMPLETED`; all non-cancelled at
   stage `WITH_BUYER` or later -> `RELEASED`; otherwise `OPEN`.
9. **Derived overdue:** `expectedDeliveryDate` is before today (Europe/Warsaw) and the status is
   neither `COMPLETED` nor `CANCELLED`.
10. WHEN `assignedBuyer` is set or changed, THE system SHALL publish the assignment event
    (Requirement 13).
11. **Planned delivery date sync:** a position's `plannedDeliveryDate` SHALL equal its request's
    `expectedDeliveryDate`. It is set when the position is attached, set to `null` when detached,
    and re-applied to every non-terminal (not `RECEIVED`, not `CANCELLED`) position of a request
    whenever the request's `expectedDeliveryDate` changes. Terminal positions keep their last value.
    (FOR-11-04 may later overwrite it with a shipment ETA.)

### Requirement 12: Procurement stage dictionary

**User Story:** As an administrator, I want the position stages to be labelled in the existing
delivery-status dictionary, so that the later kanban columns are admin-readable and localised.

#### Acceptance Criteria

1. THE system SHALL seed additional `delivery_statuses` rows (idempotent, `NOT EXISTS` guards) with
   codes and RU/PL names: `proposed` (Предложено / Zaproponowane), `accepted` (Акцептовано /
   Zaakceptowane), `in_proforma` (В проформе / W proformie), `client_paid` (Оплачено клиентом /
   Opłacone przez klienta), `with_buyer` (У закупщика / U zakupowca), `supplier_paid` (Оплачено
   поставщику / Opłacone dostawcy), `in_delivery` (В доставке / W dostawie), `partially_received`
   (Частично принято / Częściowo odebrane).
2. THE existing codes SHALL map as `new` -> `PLANNED`, `ordered` -> `ORDERED`, `delivered` ->
   `RECEIVED`, `cancelled` -> `CANCELLED`.
3. THE seed SHALL set `order_no` so that the funnel reads: `new` 1, `proposed` 2, `accepted` 3,
   `in_proforma` 4, `client_paid` 5, `with_buyer` 6, `ordered` 7, `supplier_paid` 8, `in_delivery` 9,
   `partially_received` 10, `delivered` 11, `cancelled` 12; the four pre-existing rows SHALL be updated
   **only if they still hold their original seeded `order_no`** (admin edits are preserved).
4. THE existing delivery-status seed test SHALL be updated accordingly (the four original codes still
   exist; the `order_no` assertion reflects the new ordering).
5. Code-owned logic SHALL depend only on the `PositionStage` enum; deactivating or renaming a status
   row SHALL NOT change any state-machine behavior.

### Requirement 13: Overdue sweep and notifications

**User Story:** As a buyer or manager, I want to be told when a request is assigned or late, so that
nothing silently slips.

#### Acceptance Criteria

1. `notification.request.assigned` SHALL be emitted to the newly assigned buyer when `assignedBuyer`
   is set or changed (not when unchanged, not on unassign).
2. A scheduled `PurchaseRequestOverdueSweepService` (`foremen.procurement.request-overdue-sweep.cron`,
   default `"-"` = disabled) SHALL find requests that are overdue (11.9) with `overdueNotifiedAt IS
   NULL`, stamp `overdueNotifiedAt` and publish a one-shot overdue event.
3. `notification.request.overdue` SHALL go to the assigned buyer (or all active `BUYER` users when
   unassigned) and to the project's ACTIVE `MANAGER` members; each recipient gets one notification per
   request per overdue episode.
4. WHEN `expectedDeliveryDate` is moved to a future date, `overdueNotifiedAt` SHALL be cleared so a
   later lateness can notify again.
5. Both notifications SHALL carry the deep link `/deliveries?request={id}`; the body is `null` and the
   frontend resolves the title from the type key.
6. Emitters SHALL be `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`,
   best-effort: a delivery failure is logged and swallowed and never rolls back the business
   transition.
7. THE spec SHALL emit through the generic `NotificationService.create` only.

### Requirement 14: ABAC resources, grants and datasets

**User Story:** As an administrator, I want procurement guarded by the permission matrix, so that each
role sees only what it should.

#### Acceptance Criteria

1. THE system SHALL seed resources `PURCHASE_POSITIONS`, `PURCHASE_REQUESTS`, `PROJECT_MATERIALS`
   (RU/PL names and descriptions), each in an idempotent changeset registered last in
   `changelog.xml`, using the `<insert tableName="resources">` form that `DatasetCoverageTest` parses.
2. THE controllers SHALL be annotated `@PermissionResource` with matching operations and
   no half-annotated handlers (`PermissionAnnotationValidator` passes at startup); custom handlers
   use `@RequiresPermission`.
3. THE grants SHALL be (C = CREATE, R = READ, U = UPDATE, D = DELETE; absent = deny):

   | Resource | ADMIN | MANAGER | BUYER | FOREMAN | others |
   |----------|-------|---------|-------|---------|--------|
   | `PURCHASE_POSITIONS` | CRUD | CRUD | CRU | R | none |
   | `PURCHASE_REQUESTS` | CRUD | CRUD | RU | R | none |
   | `PROJECT_MATERIALS` | CRUD | CRUD | CRUD | R | none |

4. THE `BUYER` role SHALL additionally receive READ on `MATERIALS_FINISHING`, `MATERIALS`,
   `MATERIAL_CATEGORIES`, `MATERIAL_TYPES`, `MATERIAL_PRODUCERS`, `MEASUREMENT_UNITS`, `VAT_RATES`,
   `CURRENCIES`, `DELIVERY_CATEGORIES`, `DELIVERY_STATUSES`; READ+CREATE+UPDATE on `MATERIAL_SELLERS`
   (quick add by NIP); and the same `NOTIFICATIONS` grant as `FOREMAN`. THE `MANAGER` role SHALL
   additionally receive **CREATE** on `MATERIAL_SELLERS` (it already holds READ), so a manager can
   add a seller inline from the position form (Requirement 20).
5. ALL grant changesets SHALL be idempotent (`INSERT ... SELECT ... WHERE NOT EXISTS`).
6. THE system SHALL register `PurchasePositionsDataset` (code `PURCHASE_POSITIONS`, requires
   `PURCHASE_POSITIONS`, `projectPath = project.id`, date field `plannedDeliveryDate`) and
   `PurchaseRequestsDataset` (code `PURCHASE_REQUESTS`, requires `PURCHASE_REQUESTS`, `projectPath =
   project.id`, date field `expectedDeliveryDate`) in the FOR-10 registry.
7. THE datasets SHALL NOT expose any Money_Field, `notes`, `referenceLink`, NIP or other field on the
   `SensitiveFieldGuardTest` deny list. Both datasets SHALL override `nullProjectPredicate` to
   return an always-true predicate for the `BUYER` role, so a BUYER sees all projects' rows through
   widgets while every other role stays membership-scoped.
8. `PROJECT_MATERIALS` SHALL be listed in `DatasetCoverage.EXCLUDED` with a one-line justification
   ("project-scoped extension of the finishing-materials catalog, not a dashboard subject").
9. `DatasetCoverageTest` and `SensitiveFieldGuardTest` SHALL pass.

### Requirement 15: Configuration

#### Acceptance Criteria

1. `application.yml` SHALL contain `foremen.nip-lookup.{enabled, base-url, api-key, timeout}` with
   environment placeholders (`FOREMEN_NIP_LOOKUP_ENABLED` default `false`, `_BASE_URL`, `_API_KEY`,
   `_TIMEOUT`) and a comment explaining that the disabled fallback is wired when `enabled` is false.
2. `application.yml` SHALL contain `foremen.procurement.request-overdue-sweep.cron:
   ${FOREMEN_PROCUREMENT_OVERDUE_SWEEP_CRON:-}`.
3. `.env.example` and the `backend.environment` block of `docker-compose.yml` SHALL pass the new
   variables with empty defaults (`${VAR:-}`); no secrets are committed.

### Requirement 16: Backend i18n

#### Acceptance Criteria

1. EVERY new `error.*` key (listed in the design) SHALL exist in `messages.properties` (PL) and
   `messages_ru.properties` (RU).
2. A parity test (modeled on `ProcurementMessagesParityTest`) SHALL fail when a new key is missing in
   either bundle.
3. THE seeded role, resource and delivery-status rows SHALL carry both RU and PL text.

### Requirement 17: Frontend slice

**User Story:** As a buyer, I want to find a seller by NIP in the seller form and to open Dostawy from
the menu, so that I can start working.

#### Acceptance Criteria

1. THE seller form SHALL gain an optional **NIP** field validated client-side with the same checksum
   rule, and a **"Find by NIP"** button that calls the lookup endpoint (Requirement 5).
2. WHEN the result is `DICTIONARY`, THE form SHALL show an inline message with the existing seller's
   name and SHALL NOT overwrite the form; WHEN `REGISTRY`, it SHALL prefill `nameRU`, `namePL` (both
   with the legal name) and a suggested `code` (empty fields only) and show an info message; WHEN
   `NONE`, a not-found message, or an "unavailable, fill in manually" message when
   `registryAvailable = false` or on 502.
3. THE seller table SHALL show a `nip` column.
4. THE `/deliveries` navigation item and route permission SHALL be re-pointed from the non-existent
   `DELIVERIES`/READ to `PURCHASE_POSITIONS`/READ (nav tests and `PermissionGuard` tests updated); the
   page itself stays the placeholder until FOR-11-01.
5. THE mobile bottom bar SHALL gain a `BUYER` profile `[item('deliveries')]` (slots hidden without
   permission follow the existing rule).
6. ALL new strings SHALL be keys in `pl.json` and `ru.json` at parity, including the two notification
   titles (`notification.request.assigned`, `notification.request.overdue`).
7. Component tests SHALL cover the seller form lookup states, the table column, and the locale parity.
8. THE frontend SHALL provide the reusable `SellerPicker` (async searchable select over
   `/api/material-sellers`) and `SellerQuickCreate` (inline form with **name** and **NIP** only, with
   the same "Find by NIP" button) components described in Requirement 20.

### Requirement 18: Verification and test-cases.md

#### Acceptance Criteria

1. Backend tests: policy and deriver unit/property tests, NIP SPI tests (`MockRestServiceServer`),
   service and controller integration tests (Testcontainers), seed tests (resources, grants,
   idempotency, delivery statuses), scoping/ABAC tests, dataset tests, sweep and emitter tests,
   messages parity.
2. Per `.kiro/steering/test-execution-rules.md`, only the affected test classes are run (`--tests`,
   log redirected, JUnit XML read); the full suite is not run.
3. A `test-cases.md` (Russian) SHALL be authored per `.kiro/steering/test-cases.md`: Part A API tests
   against the Dockerized app, Part B browser scenarios for the seller form and the BUYER shell,
   repeatability via run-id generator + teardown, a regression group, and the MD report template.

### Requirement 19: Non-regression

#### Acceptance Criteria

1. THE existing finishing-material tests (FOR-04-18), seller tests (FOR-04), estimate/offer/margin
   consumers of `FinishingMaterialEntity`, and the delivery-status seed test SHALL pass (the latter
   after the update in 12.4).
2. NO existing role SHALL lose or gain a grant other than those listed in Requirement 14
   (including `MANAGER` CREATE on `MATERIAL_SELLERS`); the existing seller-resource seed test is
   updated for that single grant.
3. THE construction-material endpoints and the FOR-06-03/04/05 procurement flow SHALL be unchanged.
4. THE two git repositories SHALL be committed separately (root: backend, changesets, spec; inner
   `foremen-frontend/`: UI slice) per `.kiro/steering/git-repo-structure.md`, only when the user asks.

### Requirement 20: Inline seller creation (name + NIP only)

**User Story:** As a buyer or manager filling in a position, I want to add a missing seller right
there, typing only a name and a NIP, so that I never leave the page.

#### Acceptance Criteria

1. THE system SHALL expose `POST /api/material-sellers/quick` with body `{ name, nip? }`, guarded by
   `@RequiresPermission(resource = "MATERIAL_SELLERS", operation = "CREATE")`.
2. `name` SHALL be required (1..100 characters, trimmed); `nip` SHALL be optional and, when given,
   normalized and checksum-validated as in Requirement 4.3.
3. WHEN a `nip` is given and a seller with that NIP already exists, THE endpoint SHALL **not**
   create a duplicate: it SHALL return that seller with `created = false` (HTTP 200).
4. OTHERWISE THE endpoint SHALL create an active seller with `nameRU = namePL = name`, an
   auto-generated unique `code` (lowercase slug of the name plus a short numeric suffix on
   collision, matching the existing `^[a-z0-9_]{1,100}$` rule), the given `nip`, and return it with
   `created = true` (HTTP 201).
5. THE seller `code` SHALL be immutable afterwards as for every seller; the admin may later complete
   the RU/PL names, website and other fields on the sellers page.
6. THE frontend SHALL provide a reusable **`SellerPicker`**: an async searchable select over
   `/api/material-sellers` (search by name or NIP) with a footer action "+ Add seller" that opens the
   **`SellerQuickCreate`** inline popover (no navigation): fields **Name** and **NIP**, a "Find by
   NIP" button (Requirement 5; a dictionary hit selects the existing seller, a registry hit prefills
   the name), and a "Create" button.
7. AFTER creation or a duplicate hit, THE picker SHALL select the returned seller automatically and
   close the popover; a duplicate hit SHALL show an "already exists, selected" message.
8. THE popover SHALL be shown only to users holding `MATERIAL_SELLERS`/CREATE; others see the picker
   without the "Add seller" action.
9. THE components SHALL work standalone so FOR-11-01 (position form) and the project-material form
   can embed them unchanged.

### Requirement 21: Variant history per position

**User Story:** As a manager or buyer, I want every replaced or re-agreed option of a position kept,
so that I can show only the current option yet quickly return to or pick from the known ones.

#### Acceptance Criteria

1. THE system SHALL persist `purchase_position_variants` (entity `PurchasePositionVariantEntity`,
   extends `BaseEntity`): `position` (required FK, `ON DELETE CASCADE`), `material` (required FK to
   `finishing_materials`), `unitSalePrice` and `unitPlannedPurchasePrice` (nullable, net), `reason`
   (`ANALOG` | `REPRICED` | `REJECTED` | `MANUAL`) and `note` (nullable, up to 500 characters). The
   retirement time and user are the entity's `createdDate` / `createdBy`.
2. THE position itself SHALL always hold the **current (main)** option (`plannedMaterial`,
   `salePrice`, `plannedPurchasePrice`); the history holds only **retired** options. Lists and
   single reads of positions SHALL show only the current option plus `variantCount` (and
   `hasVariants`).
3. `POST /api/purchase-positions/{id}/variants/replace` (body `{ materialId, salePrice?,
   plannedPurchasePrice?, reason, note? }`, `PURCHASE_POSITIONS`/UPDATE) SHALL snapshot the current
   option into the history with the given `reason`, then make the supplied option current. The new
   material SHALL be a Global_Material or a Project_Material of the same project, and SHALL differ
   from the current one unless the price changes (`REPRICED`).
4. `POST /api/purchase-positions/{id}/variants/{variantId}/restore` (UPDATE) SHALL make a history
   option current: the present current option is snapshotted with reason `MANUAL`, and the restored
   row is removed from the history (the audit log keeps the trail).
5. `GET /api/purchase-positions/{id}/variants` (READ) SHALL return the history, newest first, with
   material label fields (`model`, `producer`, `seller`, `link`, `photo`), `reason`, `note`,
   retirement time/user, and money fields filtered by the price-visibility policy (Requirement 10:
   `SITE` audience sees none).
6. `GET /api/purchase-positions/{id}/variants/suggestions?limit=` (READ) SHALL return distinct
   materials already known in the **same project** for the **same material type** (the position's
   current material `type`), taken from the current option and the history of all positions of that
   project, excluding the position's own current material, each with its latest known sale price
   (money filtered as above), so a user can pick a known analog quickly. Default and maximum limit:
   10 / 50.
7. Variant writes (replace, restore) SHALL be allowed only while the position stage is `PLANNED` or
   `PROPOSED` (MANUAL, MANAGER/ADMIN/BUYER); at `ACCEPTED` or later a replacement needs client
   re-acceptance (FOR-11-02) and is a SYSTEM operation of `PositionVariantService`; a manual attempt
   SHALL be rejected with 409 `error.purchasePosition.variant.stageNotAllowed`.
8. Replace and restore SHALL write in one transaction, keep `plannedMaterial` valid per
   Requirement 7.5, and be audited.
9. Deleting a position SHALL delete its variants; the project-material in-use check includes
   variants (Requirement 3.7).
10. Variants SHALL NOT be exposed through any dashboard dataset.

### Requirement 22: "Hide delivered" filter

**User Story:** As a manager or buyer, I want the planned-purchases table to hide what is already
fully delivered by default, so that I only see work that remains.

#### Acceptance Criteria

1. `GET /api/purchase-positions` SHALL accept `hideDelivered` (boolean, default `false` on the API);
   WHEN `true`, positions in stage `RECEIVED` SHALL be excluded. `PARTIALLY_RECEIVED` positions and
   positions that returned from `RECEIVED` to `PARTIALLY_RECEIVED` SHALL stay visible.
2. `GET /api/purchase-requests` SHALL accept `hideDelivered`; WHEN `true`, requests whose derived
   status is `COMPLETED` SHALL be excluded (a request with no positions or with any non-terminal
   position stays).
3. THE filter SHALL compose with every other filter, sort, pagination and the project scoping, and
   SHALL be applied in the database query (correct totals and pagination).
4. THE API default stays `false` so existing consumers are unaffected; **the UI switcher defaults
   to ON** (hide delivered) and its state is remembered per user (`localStorage`); the switcher
   itself is delivered by FOR-11-01 against this parameter.
5. THE `hideDelivered` parameter SHALL be documented in the controller's OpenAPI description.

### Requirement 23: Upcoming deliveries for the foreman

**User Story:** As a foreman, I want a list of the deliveries planned for the near future on my
projects, so that I can prepare the site and avoid downtime.

#### Acceptance Criteria

1. THE system SHALL expose `GET /api/purchase-positions/upcoming?days={n}&projectId={id}`
   (`PURCHASE_POSITIONS`/READ); `days` defaults to 14, allowed range 1..60 (otherwise 400
   `error.purchasePosition.upcoming.days.invalid`); `projectId` is optional.
2. THE result SHALL contain positions that are **planned deliveries**: stage in `WITH_BUYER`,
   `ORDERED`, `SUPPLIER_PAID`, `IN_DELIVERY`, `PARTIALLY_RECEIVED`, with a non-null
   `plannedDeliveryDate` on or before today + `days`. Positions whose planned date is already past
   SHALL be included and flagged `overdue = true`. Positions without a planned date SHALL NOT be
   included.
3. THE result SHALL be sorted by `plannedDeliveryDate` ascending, then by project name, and
   scoped by Procurement_Access (a FOREMAN sees only his ACTIVE-membership projects; BUYER and ADMIN
   all).
4. EACH item SHALL carry: `positionId`, `projectId`, `projectName`, material label (`model` of the
   actual material when set, otherwise of the planned one) and `producer`, `quantity`, `unitName`
   (resolved to the caller's locale), `plannedDeliveryDate`, `status`, `overdue`, `requestId`; and
   **no Money_Field** for any audience.
5. THE endpoint SHALL be read-only and cheap (one query, no N+1), paginated by `days`/`projectId`
   only (the window bounds the size).
6. THE list SHALL be the data source of the foreman-facing screens delivered by FOR-11-04/05 and is
   immediately usable through the dashboard widget of Requirement 24.

### Requirement 24: Dashboard widgets

**User Story:** As a foreman, manager or buyer, I want procurement widgets on my dashboard, so that I
see what arrives soon and what needs action without opening the planner.

#### Acceptance Criteria

1. THE `PurchasePositionsDataset` field catalog SHALL expose (selectable, filterable, groupable where
   meaningful; no Money_Field, notes or links): `id`, `project.id`, `project.name`, `status` (ENUM
   with the stage values), `sourcingPath` (ENUM), `kind` (ENUM), `plannedMaterial.model`,
   `plannedMaterial.producer.nameRU`/`.namePL`, `quantity`, `unit.nameRU`/`.namePL`, `needByDate`,
   `orderByDate`, `decisionDeadline`, `plannedDeliveryDate`, `purchaseRequest.id`, `createdDate`.
2. THE system SHALL seed three library widgets (system seed, idempotent by code, config validated by
   the production `WidgetConfigValidator`), each with RU/PL name and description:
   - `UPCOMING_DELIVERIES` — `TABLE`, dataset `PURCHASE_POSITIONS`, columns project, material,
     quantity + unit, planned delivery date, status; filter `status IN (WITH_BUYER, ORDERED,
     SUPPLIER_PAID, IN_DELIVERY, PARTIALLY_RECEIVED)` and `plannedDeliveryDate IS_NOT_NULL`; sort
     `plannedDeliveryDate ASC`; limit 10; ROW link to `/deliveries`; date field
     `plannedDeliveryDate`.
   - `PROCUREMENT_BY_STAGE` — `DONUT`, dimension `status`, measure `COUNT(id)`, excluding
     `CANCELLED`.
   - `POSITIONS_WITH_BUYER` — `KPI`, `COUNT(id)` where `status = WITH_BUYER`, ROW/KPI link to
     `/deliveries`.
3. THE route `/deliveries` SHALL be added to the server `RouteWhitelist` and to the frontend
   `route-permissions` so the whitelist contract test passes.
4. THE system SHALL seed role defaults (free grid cells, validated by the existing grid/seed tests):
   `FOREMAN` — `UPCOMING_DELIVERIES`; `MANAGER` — `UPCOMING_DELIVERIES`, `PROCUREMENT_BY_STAGE`;
   `BUYER` — `POSITIONS_WITH_BUYER`, `UPCOMING_DELIVERIES`, `PROCUREMENT_BY_STAGE`; `ADMIN` —
   `PROCUREMENT_BY_STAGE`. Users that already have a dashboard are not changed (they add the
   widgets from the library); new users get the defaults.
5. A user without `PURCHASE_POSITIONS` access SHALL NOT see these widgets in the library (existing
   FOR-10 access rule).
6. THE widgets SHALL be project-scoped for non-BUYER roles through membership, and company-wide for
   BUYER through the dataset's `nullProjectPredicate` override (Requirement 14.7).
7. THE spec SHALL document, as a follow-up, widgets that need relative dates (e.g. "overdue
   deliveries" KPI) because the current widget filters accept only static values; the
   `UPCOMING_DELIVERIES` table covers the need by sorting on the planned date so past-due items
   come first.
8. `DashboardWidgetLibrarySeedTest` and `DashboardRoleDefaultsSeedTest` SHALL be updated for the new
   widgets and defaults and pass.
