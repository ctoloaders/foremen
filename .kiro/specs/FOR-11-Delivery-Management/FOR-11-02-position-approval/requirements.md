# Requirements Document

## Introduction

This spec (**FOR-11-02-position-approval**) adds the **client agreement step** to FOR-11 procurement.
Today the manager agrees each position with the client in a chat and records it in a table; the client
has no "Accept" and price changes live only in the chat. FOR-11-00 delivered the position, its state
machine (`PLANNED -> PROPOSED -> ACCEPTED` are SYSTEM-only edges), the variant history and the
`CLIENT` price-visibility audience. This spec delivers the **workflow that drives those edges** and the
**screens for both sides**:

1. **Approval rounds**: the manager sends positions (a whole block, with a decision deadline) to the
   client; the client **accepts with the item and the final price fixed**, rejects with a reason,
   proposes an own link, or declares "I will buy it myself". The manager can propose an **analog**
   (item **and** price must be accepted again). For `PACKAGE` positions the manager offers 2..5 options
   and the client picks one **without any price**.
2. **A client-safe backend surface**: separate client DTOs and endpoints that contain **no internal
   price field at all** (purchase price, purchase VAT, delivery cost, margin, seller, order dates).
   Internal prices live in explicitly named admin-only (`internal*`) fields that are never mapped to the
   client types. The client logs in and sees only what the ABAC matrix allows.
3. **The client UI**: a workspace tab "Position approval" that, for a `CLIENT`, renders a mobile-friendly
   decision list grouped by block (accept, reject, own link, buy myself, history, deadline badges) and,
   for staff, renders the manager's control table (send block, propose analog, withdraw, record a
   decision on the client's behalf, resolve client suggestions).
4. **Notifications** through the generic service (emit-only).
5. **Seam for later**: a derived "decision overdue" flag that the amendment flow (FOR-06-08) and the
   shipment schedule (FOR-11-04) can read. No automatic action is taken on a late decision.

### Product decisions this spec implements (from `../OVERVIEW.md`, revision 5, and the product owner)

- **Client agreement is explicit and fixed**: "Accept" stores who accepted, when, which item and which
  unit sale price; later price changes need a new round. Price history is kept.
- **Substitution by an analog needs full client acceptance: the item and the price.**
- **Purchase price is never shown to the client.** Sale price only; `PACKAGE` and `CLIENT_OWN`
  positions show **no price at all**.
- **Sourcing paths:** `AGREED` runs the full flow; `PACKAGE` is a choice among options without prices
  and never changes the position status; `CLIENT_OWN` ("buying myself") moves the position to the
  client-own path and skips the proforma gate (FOR-11-00).
- **Blocks and deadlines:** the manager sends a block (`blockNo`, for example rough plumbing first)
  with a **decision deadline**; the deadline is shown to both sides and flagged when missed.
- **The client can log in now.** The CLIENT role sees only resources granted by ABAC and only projects
  where it is an ACTIVE member; this spec adds one resource for that.
- **Single currency (PLN)**, net unit prices, as in FOR-11-00.

### Scope boundaries

- **In scope:** the approval-round domain and API, the client-safe read model, staff and client
  endpoints, the CLIENT-audience change in `PositionPriceVisibility`, ABAC resource and grants, six
  notifications, the frontend tab (client view and staff view), locale keys, tests and `test-cases.md`.
- **Out of scope:**
  - the proforma and payment (FOR-11-03), shipments (FOR-11-04), receipt (FOR-11-05);
  - the amendment flow for a late decision or for a change after `IN_PROFORMA` (FOR-06-08; this spec only
    exposes the derived `overdue` flag and rejects such changes with a clear error);
  - a position-linked chat: discussion stays in the FOR-06-12 "Komunikacja" thread, reached by a button
    (no schema change to `client_messages`);
  - automatic expiry or reminders for missed deadlines;
  - the client portal shell of FOR-09 (this spec renders the client view **inside the existing project
    workspace**, reusing the shell, route guards and bell);
  - client visibility of shipments, tracking and delivery dates (FOR-11-04);
  - multi-currency.

### Notifications (confirmed, emit-only)

This spec **only emits** notifications through the generic notification service (in-app bell) and
implements no delivery channel. Six types are emitted (Requirement 15). Five come from the
OVERVIEW candidate list confirmed by the product owner; `suggestionReceived` is added here because a
client-supplied link otherwise reaches nobody.

| Type | Recipient | Trigger | Deep link |
|------|-----------|---------|-----------|
| `notification.position.decisionRequested` | every ACTIVE `CLIENT` member of the project (one per send operation, not per position) | the manager sends one or more positions for a decision | `/projects/{projectId}/positionApprovals` |
| `notification.position.analogProposed` | every ACTIVE `CLIENT` member | the manager proposes an analog | `/projects/{projectId}/positionApprovals?position={positionId}` |
| `notification.position.accepted` | ACTIVE `MANAGER` members of the project | the client accepts a round | `/projects/{projectId}/positionApprovals?position={positionId}` |
| `notification.position.rejected` | ACTIVE `MANAGER` members | the client rejects a round | same |
| `notification.position.clientOwn` | ACTIVE `MANAGER` members, and the assigned buyer of the position's request (or all active `BUYER` users when there is no request or no assigned buyer) | the client declares "buying myself" | same |
| `notification.position.suggestionReceived` | ACTIVE `MANAGER` members | the client proposes an own link | same |

Decisions recorded **on behalf of the client** by a manager emit no manager-side notification (the
manager is the actor). Message text is the frontend title of the type (`t(type)`); the body is `null`.

### i18n decisions

- **Backend entity fields requiring i18n:** only the seeded `resources` row of the new resource
  `POSITION_APPROVALS` (`name_ru`/`name_pl`, `description_ru`/`description_pl`). Approval rounds,
  options, notes, comments and links carry **user data**: no RU/PL variants. Names shown to the client
  (material producer, unit) come from existing dictionaries and the existing locale resolver. No new
  `roles` or `delivery_statuses` rows are seeded.
- **Backend messages:** every new `error.positionApproval.*` key exists in `messages.properties` (PL,
  default) and `messages_ru.properties` (RU), checked by a parity test.
- **Frontend:** every new string is a key in both `pl.json` and `ru.json` at parity: groups
  `positionApproval.*`, the tab label `workspace.tab.positionApprovals` and six
  `notification.position.*` titles (all listed with values in the design). Enum labels (round kind,
  round status, client status, action) are frontend keys.

## Glossary

- **Round** / **Approval_Round**: one decision request about a position, with a kind, a status, a
  deadline and 1..5 options.
- **Option**: a material (and, for `AGREED`, a unit sale price) offered inside a round.
- **Open_Round**: a round with status `OPEN` whose kind is not `CLIENT_SUGGESTION`; at most one per
  position.
- **Suggestion**: a `CLIENT_SUGGESTION` round: an own link proposed by the client, awaiting the manager.
- **Internal_Field**: a field whose name starts with `internal` or that holds a purchase-side value
  (purchase price, purchase VAT, delivery cost, margin, seller, order-by and need-by dates). Never part
  of a client type.
- **Client_View** / **Staff_View**: the two read models of Requirement 4; chosen by endpoint and
  re-checked by role.
- **Client_Member**: an ACTIVE project member whose role code is `CLIENT`.
- **Staff**: `ADMIN`, `MANAGER`, `BUYER` (the `STAFF_FULL` audience of FOR-11-00).
- **On_Behalf**: a decision recorded by a `MANAGER`/`ADMIN` for the client, with a mandatory comment.
- **Client_Status**: the coarse status shown to the client (Requirement 5.3).
- **Today**: the current date in `Europe/Warsaw`.

## Requirements

### Requirement 1: ABAC resource and grants

**User Story:** As an administrator, I want one permission resource for the approval workflow, so that
the client sees only the approval tab and nothing internal.

#### Acceptance Criteria

1. THE system SHALL seed the resource `POSITION_APPROVALS` (RU/PL name and description) in changeset
   `235-seed-position-approvals-resource.xml`, idempotent (`MARK_RAN` precondition), registered last in
   `changelog.xml`.
2. THE grants SHALL be: `ADMIN` CREATE/READ/UPDATE/DELETE/APPROVE; `MANAGER` CREATE/READ/UPDATE/DELETE
   and APPROVE (on-behalf decisions); `BUYER` READ; `CLIENT` READ and APPROVE. `FOREMAN`, `FINANCIER`,
   `WORKER` and `ESTIMATOR` SHALL have no grant. The operation `APPROVE` already exists (changeset 133).
3. THE grants SHALL be inserted with the same idempotent `NOT EXISTS` pattern as changeset 229.
4. THE `CLIENT` role SHALL receive **no** grant on `PURCHASE_POSITIONS`, `PURCHASE_REQUESTS`,
   `PROJECT_MATERIALS`, `PURCHASES` or any dictionary resource through this spec.
5. EVERY handler of the new controllers SHALL carry an explicit `@RequiresPermission(POSITION_APPROVALS,
   op)`; no controller SHALL carry a class-level `@PermissionResource` without a matching
   `@PermissionOperation` (no half-annotation, `PermissionAnnotationValidator`).

### Requirement 2: Approval round and option model

**User Story:** As a manager, I want every decision request stored with its history, so that I can show
who accepted which item at which price.

#### Acceptance Criteria

1. THE system SHALL map `PositionApprovalRoundEntity` (`position_approval_rounds`, extends
   `BaseEntity`) with: `position` (required), `roundNo` (per position, from 1), `kind`, `status`,
   `decisionDeadline` (nullable date), `previousStatus` (`PositionStatus` before the round opened),
   `baseMaterial` and `baseUnitSalePrice` (the position's option at the moment of sending, for the
   stale check), `quantity` (snapshot, scale 3), `managerNote` (<= 1000), `suggestionLink` (<= 1000),
   `sourceSuggestion` (nullable self-reference), `decidedBy` (nullable user), `decidedAt`,
   `decidedSide` (`CLIENT` or `STAFF`), `onBehalf` (boolean, default false), `decisionComment` (<= 1000)
   and `chosenOption` (nullable).
2. THE system SHALL map `PositionApprovalOptionEntity` (`position_approval_options`) with `round`,
   `material` (FK to `finishing_materials`), `sortOrder`, `unitSalePrice` (nullable, scale 2, net PLN),
   and the single **admin-only** field `internalUnitPurchasePrice` (nullable, scale 2). No other price
   column exists on the option.
3. `kind` SHALL be one of `PROPOSAL`, `ANALOG`, `PACKAGE_CHOICE`, `CLIENT_SUGGESTION`, `CLIENT_OWN`;
   `status` one of `OPEN`, `ACCEPTED`, `REJECTED`, `SUPERSEDED`, `WITHDRAWN`, `DECLARED` (kind
   `CLIENT_OWN`), `RESOLVED`, `DECLINED` (kind `CLIENT_SUGGESTION`); both stored as `VARCHAR` enum names.
4. A pure `ApprovalRoundStatusMachine` SHALL define the legal status changes: `OPEN -> ACCEPTED |
   REJECTED | SUPERSEDED | WITHDRAWN` for `PROPOSAL`, `ANALOG`, `PACKAGE_CHOICE`; `OPEN -> RESOLVED |
   DECLINED` for `CLIENT_SUGGESTION`; `CLIENT_OWN` is created as `DECLARED` and has no exit; every other
   status is terminal. An illegal change SHALL be rejected with 409 `error.positionApproval.round.notOpen`.
5. THE table SHALL have a **partial unique index** allowing at most one `OPEN` round per position whose
   kind is not `CLIENT_SUGGESTION`, plus indexes on `position_id`, `(position_id, round_no)` unique,
   `status`, and `position_approval_options(round_id)`.
6. `PROPOSAL` and `ANALOG` rounds SHALL have exactly one option; `PACKAGE_CHOICE` 2..5 options with
   distinct materials and **no** `unitSalePrice`; `CLIENT_SUGGESTION` and `CLIENT_OWN` have none.
7. EVERY round write SHALL be audited through the existing audit mechanism of `AdminService`, with the
   admin-only fields excluded from any snapshot readable by a non-staff audience.
8. THE changeset `234-create-position-approval-tables.xml` SHALL create both tables, idempotently, and
   be registered in `changelog.xml` (next free number after the current tail, see design).

### Requirement 3: Client visibility rule and price visibility

**User Story:** As the business owner, I want the client to see only what we have sent for a decision,
and never an internal price, so that margin stays confidential.

#### Acceptance Criteria

1. A position SHALL be visible to a client **iff** it has at least one round (any kind and status) in a
   project where the caller is a Client_Member. A position never sent stays invisible, including
   `PACKAGE` and `CLIENT_OWN` positions without a round.
2. THE `CLIENT` case of `PositionPriceVisibility` SHALL be extended: a client sees `salePrice` of an
   `AGREED` position from stage `PROPOSED` onward (previously `ACCEPTED`); `PACKAGE` and `CLIENT_OWN`
   positions expose **no** money. The existing policy tests SHALL be updated accordingly.
3. THE price shown inside an `OPEN` round SHALL be the option's `unitSalePrice` (not the position's
   current price), because an analog changes the item only on acceptance.
4. A non-`AGREED` option SHALL never carry a price; an attempt to supply one SHALL be rejected with 400
   `error.positionApproval.option.priceNotAllowed`.
5. THE client SHALL never receive: `plannedPurchasePrice`, `actualPurchasePrice`,
   `internalUnitPurchasePrice`, `purchaseVatRate`, `deliveryCostShare`, margin values, seller id or name,
   `needByDate`, `orderByDate`, `plannedDeliveryDate`, request data, audit snapshots, or the FOR-11-00
   variants/suggestions endpoints (the `CLIENT` role has no `PURCHASE_POSITIONS` grant).
6. A non-`STAFF_FULL` caller SHALL NOT be able to read the Staff_View (Requirement 4.5).

### Requirement 4: Read models (Client_View and Staff_View)

**User Story:** As a client, I want a clear list of what I must decide, and as a manager I want the
same list with the internal numbers, so that we talk about the same positions.

#### Acceptance Criteria

1. THE system SHALL define **separate** DTO types: `ClientApprovalPositionDto` (and nested
   `ClientRoundDto`, `ClientOptionDto`, `ClientMoneyDto`) and `StaffApprovalPositionDto` (nested
   `StaffRoundDto`, `StaffOptionDto`). The client types SHALL NOT extend, wrap or embed any staff type.
2. `ClientApprovalPositionDto` SHALL contain only: `positionId`, `blockNo`, the current item
   (`model`, `sku`, `producerName`, `link`, `photo`), `quantity`, `unitName`, `clientStatus`,
   `sourcingPath`, `money` (Requirement 4.4), `decisionDeadline`, `overdue`, the open round (if any),
   the open suggestion (if any), the closed-round history and `allowedActions`.
3. `clientStatus` SHALL be derived from the position status: `AWAITING_DECISION` (open round),
   `ACCEPTED` (`ACCEPTED`), `IN_PROGRESS` (`IN_PROFORMA` .. `SUPPLIER_PAID`), `IN_DELIVERY`
   (`IN_DELIVERY`, `PARTIALLY_RECEIVED`), `RECEIVED`, `CANCELLED`, `CLIENT_OWN` (path `CLIENT_OWN`),
   `NOT_STARTED` (a visible position with no open round and status `PLANNED`).
4. `ClientMoneyDto` SHALL contain `unitNet`, `vatRatePercent`, `unitGross`, `lineNet`, `lineGross`
   (PLN, `HALF_UP`, scale 2, per-line rounding), computed from the sale side only, and SHALL be `null`
   whenever the visibility policy hides money.
5. `StaffApprovalPositionDto` SHALL add the internal values for a `STAFF_FULL` caller:
   `internalUnitPurchasePrice` per option, the position's planned purchase price, margin per unit and per
   line, seller name, `needByDate`, `orderByDate`, `plannedDeliveryDate`, request reference and
   `onBehalf` flags. A caller whose audience is not `STAFF_FULL` SHALL be rejected with 403
   `error.positionApproval.staff.forbidden`.
6. THE history SHALL list closed rounds newest first with: `roundNo`, `kind`, `status`, options (client
   type: sale price only), `decisionComment`, `decidedAt`, `decidedSide`, `onBehalf`; names of deciding
   users are **not** exposed to the client.
7. `allowedActions` SHALL be computed on the server per caller and row, for the client: `ACCEPT`,
   `REJECT`, `CLIENT_OWN`, `SUGGEST_LINK`; for staff: `SEND`, `SEND_CHOICE`, `PROPOSE_ANALOG`, `WITHDRAW`,
   `CHANGE_DEADLINE`, `DECIDE_ON_BEHALF`, `RESOLVE_SUGGESTION`. The frontend SHALL NOT re-implement the
   rules.
8. A structural **leak guard** SHALL exist: a test that walks every field of every client DTO class
   (recursively, including generics) and fails if a field name matches the Internal_Field deny-list
   (`purchase`, `internal`, `margin`, `delivery`, `seller`, `needBy`, `orderBy`, `plannedDelivery`,
   `audit`, `request`), and a runtime test that serializes representative client responses and fails if
   they contain any sentinel internal value.

### Requirement 5: Client list and detail API

**User Story:** As a client, I want to open the project and see my pending decisions first.

#### Acceptance Criteria

1. `GET /api/position-approvals/client/projects/{projectId}` SHALL return the Client_View list for the
   project, guarded by `@RequiresPermission(POSITION_APPROVALS, READ)`, ordered by `blockNo` ascending
   (nulls last), then open rounds before closed, then by `decisionDeadline` ascending (nulls last).
2. THE endpoint SHALL accept `status` (a `clientStatus`), `onlyPending` (boolean, default false) and
   `blockNo`; the filters SHALL be applied in the database query.
3. THE endpoint SHALL return **only** the Client_View, for every caller (a manager previewing the client
   view gets the same DTO); it SHALL return 404 for a project the caller cannot access
   (`ProcurementProjectAccess`: ADMIN/BUYER all projects, others ACTIVE membership) and an empty list
   when nothing was sent.
4. `GET /api/position-approvals/client/positions/{positionId}` SHALL return one Client_View item with
   the same scoping and visibility rule; a position with no round SHALL yield 404 for a client.
5. `GET /api/position-approvals/client/projects/{projectId}/summary` SHALL return counts:
   `awaitingDecision`, `overdue`, `accepted`, `clientOwn`, used by the tab badge.

### Requirement 6: Sending positions for a decision (manager)

**User Story:** As a manager, I want to send a block of agreed positions to the client with a deadline,
so that the client decides in time and work is not blocked.

#### Acceptance Criteria

1. `POST /api/position-approvals/staff/send` (body `{positionIds, decisionDeadline, managerNote}`),
   `@RequiresPermission(POSITION_APPROVALS, CREATE)`, SHALL create one `PROPOSAL` round per position and
   move each position `PLANNED -> PROPOSED` through `PositionLifecycleService.transition(..., SYSTEM)`.
2. EACH position SHALL be `AGREED`, in stage `PLANNED`, of an `ACTIVE` project accessible to the caller,
   with `salePrice > 0` and a `saleVatRate` (409 `error.positionApproval.send.priceMissing`), and not
   already in an open round; otherwise the whole request SHALL be rejected, naming the offending id
   (`error.positionApproval.send.notEligible`, with the position id as parameter). The operation is
   all-or-nothing in one transaction.
3. THE round SHALL copy the position's current option as its single option (material, `unitSalePrice =
   salePrice`, `internalUnitPurchasePrice = plannedPurchasePrice`), `baseMaterial`/`baseUnitSalePrice`,
   `quantity`, `previousStatus = PLANNED`, `decisionDeadline`, `managerNote`.
4. `decisionDeadline` SHALL be optional but, when present, `>= Today` (400
   `error.positionApproval.deadline.invalid`); the system SHALL mirror it into the position's
   `decisionDeadline` (a system write through the position service).
5. All positions of one request MAY belong to different blocks; the UI sends one block at a time but the
   API does not require it.
6. WHEN the operation commits, THE system SHALL publish one `PositionsSentForDecisionEvent` per request
   (project id, position ids), consumed by Requirement 15.
7. `POST /api/position-approvals/staff/send-choice` (body `{positionId, materialIds[2..5],
   decisionDeadline, managerNote}`), `CREATE`, SHALL create a `PACKAGE_CHOICE` round for a `PACKAGE`
   position in stage `PLANNED`, with one price-less option per material; materials SHALL be global or of
   the same project, distinct (400 `error.positionApproval.choice.optionsInvalid`). The position status is
   unchanged.

### Requirement 7: Client accepts (fixed item and price)

**User Story:** As a client, I want to accept an item at a stated price, so that the price is fixed and
cannot change without my consent.

#### Acceptance Criteria

1. `POST /api/position-approvals/client/rounds/{roundId}/accept` (body `{optionId,
   expectedUnitSalePrice}`), `@RequiresPermission(POSITION_APPROVALS, APPROVE)`, SHALL be allowed for a
   Client_Member of the position's project on an `OPEN` round of kind `PROPOSAL`, `ANALOG` or
   `PACKAGE_CHOICE`.
2. FOR a priced option the request SHALL carry the price the client saw; a mismatch with the stored
   `unitSalePrice` SHALL be rejected with 409 `error.positionApproval.priceChanged` and the round left
   open.
3. THE system SHALL verify that the position is still in the state the round expects (stage `PROPOSED`
   for `AGREED`, stage `PLANNED` for `PACKAGE_CHOICE`) and that, for `PROPOSAL`, the position's current
   material and `salePrice` still equal `baseMaterial`/`baseUnitSalePrice`; otherwise 409
   `error.positionApproval.stale`.
4. ON accept the system SHALL, in **one transaction**: (a) for `ANALOG` and `PACKAGE_CHOICE`, when the
   chosen option differs from the position's current item or price, call `PositionVariantService.replace(
   positionId, ReplaceCommand(materialId, unitSalePrice, internalUnitPurchasePrice, ANALOG, note), SYSTEM)`;
   (b) for `AGREED`, call `PositionLifecycleService.transition(positionId, ACCEPTED, SYSTEM)`; (c) set the
   round to `ACCEPTED` with `chosenOption`, `decidedBy`, `decidedAt`, `decidedSide = CLIENT`; (d) clear
   the position's `decisionDeadline`.
5. A `PACKAGE_CHOICE` accept SHALL require `optionId`, SHALL NOT change the position status, and SHALL
   NOT accept or return any price.
6. A caller who is not a Client_Member of that project SHALL receive 404; a round that is not `OPEN`
   SHALL yield 409 `error.positionApproval.round.notOpen`.
7. WHEN the commit succeeds, THE system SHALL publish `PositionDecisionEvent(accepted)` (Requirement 15).

### Requirement 8: Client rejects

**User Story:** As a client, I want to refuse a proposal with a reason, so that the manager can come
back with another item or price.

#### Acceptance Criteria

1. `POST /api/position-approvals/client/rounds/{roundId}/reject` (body `{comment}`), `APPROVE`, SHALL
   require a non-blank `comment` (<= 1000; 400 `error.positionApproval.decision.commentRequired`).
2. ON reject the round SHALL become `REJECTED`; for `AGREED` the position SHALL move to `PLANNED`
   (`PROPOSED -> PLANNED`, SYSTEM) whatever `previousStatus` was; for `PACKAGE_CHOICE` the status is
   unchanged; the position's `decisionDeadline` SHALL be cleared.
3. THE manager SHALL then re-send after editing (FOR-11-00 variant operations, reason `REJECTED` or
   `REPRICED`); this spec adds no automatic counter-proposal.
4. WHEN the commit succeeds, THE system SHALL publish `PositionDecisionEvent(rejected)`.

### Requirement 9: Analog proposal (manager)

**User Story:** As a manager, I want to propose an analog with its own price, so that the client can
accept the substitution knowingly.

#### Acceptance Criteria

1. `POST /api/position-approvals/staff/positions/{positionId}/propose-analog` (body `{materialId,
   unitSalePrice, internalUnitPurchasePrice, decisionDeadline, managerNote, sourceSuggestionId}`),
   `@RequiresPermission(POSITION_APPROVALS, CREATE)`, SHALL create an `ANALOG` round.
2. THE position SHALL be `AGREED` and in stage `PROPOSED` (the open round, if any, becomes `SUPERSEDED`)
   or `ACCEPTED` (the system first moves it `ACCEPTED -> PROPOSED`, SYSTEM, `previousStatus = ACCEPTED`).
   Any other stage SHALL be rejected with 409 `error.positionApproval.stage.notAllowed` (from
   `IN_PROFORMA` on, a change needs an amendment, see Requirement 14).
3. `unitSalePrice` SHALL be required and `> 0`; `internalUnitPurchasePrice` optional and `>= 0`; the
   material SHALL be global or of the same project; the option SHALL differ from the position's current
   material or price (409 `error.positionApproval.option.sameAsCurrent`).
4. WHEN `sourceSuggestionId` is given, THE referenced open suggestion of the same position SHALL become
   `RESOLVED` and its id stored in `sourceSuggestion`.
5. WHEN the commit succeeds, THE system SHALL publish `PositionAnalogProposedEvent`.
6. THE round, not the position, SHALL carry the proposed item and price until acceptance (Requirement
   7.4); the position's variant history gains an entry only on acceptance.

### Requirement 10: Client suggestion (own link)

**User Story:** As a client, I want to point to the exact item I prefer, so that the manager can price it.

#### Acceptance Criteria

1. `POST /api/position-approvals/client/positions/{positionId}/suggest` (body `{link, comment}`),
   `APPROVE`, SHALL create a `CLIENT_SUGGESTION` round (`OPEN`) for a visible `AGREED` position in stage
   `PROPOSED` or `ACCEPTED`; the position and its open round are unchanged.
2. `link` SHALL be an absolute `http` or `https` URL, <= 1000 chars (400
   `error.positionApproval.suggestion.linkInvalid`); `comment` optional <= 1000. Several suggestions MAY
   be open at once.
3. `POST /api/position-approvals/staff/suggestions/{roundId}/decline` (body `{managerNote}`), `UPDATE`,
   SHALL set the suggestion `DECLINED` and store the reason, visible to the client.
4. A suggestion becomes `RESOLVED` only through Requirement 9.4.
5. WHEN the commit succeeds, THE system SHALL publish `PositionSuggestionEvent`.

### Requirement 11: "Buying myself"

**User Story:** As a client, I want to say that I will buy this item myself, so that we do not buy it
for the proforma but still track its delivery.

#### Acceptance Criteria

1. `POST /api/position-approvals/client/positions/{positionId}/client-own` (body `{comment}`), `APPROVE`,
   SHALL be allowed for a visible `AGREED` position in stage `PROPOSED` or `ACCEPTED` (409
   `error.positionApproval.clientOwn.notAllowed` otherwise, including `PACKAGE` positions and any stage
   from `IN_PROFORMA` on).
2. THE system SHALL, in one transaction: set any open round of the position to `SUPERSEDED`; create a
   `CLIENT_OWN` round with status `DECLARED`; move the position to `PLANNED` through the allowed SYSTEM
   edges (`ACCEPTED -> PROPOSED -> PLANNED`); set `sourcingPath = CLIENT_OWN` (a system write that is
   audited); clear `decisionDeadline`.
3. THE position's `salePrice` SHALL be left untouched but SHALL no longer be visible to the client
   (path `CLIENT_OWN` exposes no money, Requirement 3.2).
4. FROM then on the existing FOR-11-00 manual action `PLANNED -> IN_DELIVERY` (`CLIENT_OWN`,
   MANAGER/ADMIN) applies; the manager may revert the path while the stage is `PLANNED` through the
   existing position update.
5. WHEN the commit succeeds, THE system SHALL publish `PositionDecisionEvent(clientOwn)`.

### Requirement 12: Decisions on behalf of the client

**User Story:** As a manager, I want to record a decision the client gave me by phone or chat, so that
the system stays the single source of truth.

#### Acceptance Criteria

1. `POST /api/position-approvals/staff/rounds/{roundId}/decide-on-behalf` (body `{decision: ACCEPT |
   REJECT, optionId, expectedUnitSalePrice, comment}`) and `POST
   /api/position-approvals/staff/positions/{positionId}/client-own-on-behalf` (body `{comment}`) SHALL be
   guarded by `@RequiresPermission(POSITION_APPROVALS, APPROVE)` and an explicit role check for `MANAGER`
   or `ADMIN` (403 `error.positionApproval.onBehalf.forbidden` for a `BUYER`).
2. THEY SHALL apply exactly the same rules and the same single code path as Requirements 7, 8 and 11,
   with `decidedSide = STAFF`, `onBehalf = true` and a **mandatory** non-blank `comment` that records
   the channel (400 `error.positionApproval.decision.commentRequired`).
3. THE client history SHALL show the `onBehalf` flag; no manager-side notification is emitted for these
   (Requirement 15.5).

### Requirement 13: Withdraw and change deadline (manager)

**User Story:** As a manager, I want to withdraw a mistaken request or move the deadline, so that the
client is not confused.

#### Acceptance Criteria

1. `POST /api/position-approvals/staff/rounds/{roundId}/withdraw`, `UPDATE`, SHALL set an `OPEN`
   `PROPOSAL`, `ANALOG` or `PACKAGE_CHOICE` round to `WITHDRAWN`; for `AGREED` the position SHALL return
   to `PLANNED`, or to `ACCEPTED` when the round's `previousStatus` was `ACCEPTED`; the position's
   `decisionDeadline` SHALL be cleared.
2. `POST /api/position-approvals/staff/rounds/{roundId}/deadline` (body `{decisionDeadline}`), `UPDATE`,
   SHALL change the deadline of an open round (`>= Today`, or `null` to remove) and mirror it into the
   position.
3. A round that is not `OPEN` SHALL yield 409 `error.positionApproval.round.notOpen`.

### Requirement 14: Overdue flag and the late-decision seam

**User Story:** As a manager, I want to see which decisions are late, so that I can chase the client
before the work is blocked.

#### Acceptance Criteria

1. `overdue` SHALL be derived (never stored): an `OPEN` round with `decisionDeadline` before Today.
2. BOTH read models SHALL expose `overdue`; the list SHALL be filterable by it for staff
   (`GET /api/position-approvals/staff/projects/{projectId}?overdue=true`).
3. THE system SHALL take **no automatic action** on a missed deadline (no status change, no
   notification, no scheduler). Any later automation reads the derived flag.
4. WHEN a change is requested for a position at `IN_PROFORMA` or later, THE API SHALL reject it with 409
   `error.positionApproval.stage.notAllowed`; the amendment flow (FOR-06-08) is the later owner.
   THE design SHALL document the exact seam (`ApprovalStageGuard` and the `overdue` flag).

### Requirement 15: Notifications (emit-only)

**User Story:** As a client or manager, I want to be told when something needs my action, so that
decisions are not missed.

#### Acceptance Criteria

1. THE system SHALL emit the six types of the table in the Introduction through
   `NotificationService.create(recipientUserId, type, null, deepLink)`, from a dedicated
   `PositionApprovalNotificationEmitter` listening with `@TransactionalEventListener(phase =
   AFTER_COMMIT, fallbackExecution = true)`; every emission SHALL be wrapped so a failure is logged and
   never rolls back or blocks the triggering operation.
2. `decisionRequested` SHALL be emitted **once per Client_Member per send operation** (not once per
   position), carrying the project deep link.
3. `analogProposed`, `accepted`, `rejected`, `clientOwn` and `suggestionReceived` SHALL carry the position
   deep link `/projects/{projectId}/positionApprovals?position={positionId}`.
4. RECIPIENTS SHALL be resolved from ACTIVE `ProjectMember` rows by role code (`CLIENT`, `MANAGER`);
   `clientOwn` additionally targets the assigned buyer of the position's request, or all active `BUYER`
   users when the position has no request or the request has no buyer; a recipient SHALL be notified at
   most once per event.
5. DECISIONS recorded On_Behalf SHALL NOT notify managers; a notification SHALL never be addressed to the
   actor.
6. THE frontend SHALL render each type as `t(type)` and navigate to the deep link on click (existing
   bell behavior); the six types have PL and RU titles (Requirement 19).

### Requirement 16: Backend i18n and errors

**User Story:** As a user of either locale, I want errors in my language.

#### Acceptance Criteria

1. EVERY `error.positionApproval.*` key used by the code SHALL exist in `messages.properties` (PL) and
   `messages_ru.properties` (RU); a parity test SHALL fail on a missing or untranslated key.
2. THE resource seed of Requirement 1.1 SHALL carry RU and PL names and descriptions.
3. NO new RU/PL entity columns SHALL be introduced.

### Requirement 17: Frontend tab and routing

**User Story:** As a client or manager, I want one tab in the project workspace for position approval.

#### Acceptance Criteria

1. THE workspace tab contract SHALL gain the entry `positionApprovals` (`stage: 'execution'`, `owner:
   'FOR-11'`, `requiredPermission: { resource: 'POSITION_APPROVALS', operation: 'READ' }`, `labelKey:
   'workspace.tab.positionApprovals'`), and the `owner` union SHALL include `'FOR-11'`.
2. THE tab SHALL be visible to `CLIENT`, `MANAGER`, `ADMIN` and `BUYER` only through ABAC; `FOREMAN` and
   others SHALL not see it.
3. THE tab SHALL show a badge with `awaitingDecision` (Requirement 5.5) for the client and `overdue` for
   staff, without extra permissions.
4. THE panel SHALL branch on the caller's role code: `CLIENT` renders the Client_View, every other
   permitted role renders the Staff_View; a role switch is never client-controlled.
5. THE deep links of Requirement 15 SHALL open the tab and scroll to / highlight the position
   (`?position={id}`), including when the tab is not yet mounted.
6. THE tab SHALL be reachable for a `CLIENT` on a project whose stage is `execution`; for a design-stage
   project the tab is hidden by the existing stage filter.

### Requirement 18: Frontend client view

**User Story:** As a client, I want to understand what is asked of me and decide in a few taps, on a
phone as well.

#### Acceptance Criteria

1. THE client view SHALL list positions grouped by block (`Block N`, `No block` last), pending first,
   each group showing its earliest decision deadline and an overdue badge when missed; a filter chip row
   (Pending, Accepted, Own, All) SHALL be provided.
2. A position card SHALL show: photo, producer, model, SKU, link to the shop, quantity and unit, status
   chip, and for priced options unit net, VAT %, unit gross and line gross; **no** field of the
   Internal_Field list (they are not in the payload).
3. FOR an open `PROPOSAL`/`ANALOG` round the card SHALL offer **Accept**, **Reject**, **Buy myself**, and
   **Propose own link**; for an analog it SHALL show the replaced item and the new item side by side with
   both prices.
4. Accept SHALL open a confirmation dialog that repeats the item and the gross unit price and sends the
   displayed price as `expectedUnitSalePrice`; a `priceChanged` or `stale` error SHALL show a refresh
   prompt and reload the data.
5. Reject SHALL require a reason; Buy myself SHALL show an explanation and an optional comment; Propose
   own link SHALL validate the URL on the client before sending.
6. FOR an open `PACKAGE_CHOICE` round the card SHALL show 2..5 options **without any price** and a
   single "Choose this option" action per option.
7. EACH card SHALL show the decision history (rounds, statuses, comments, on-behalf marks) in an
   expandable block and a **Discuss in chat** button that navigates to the `clientCommunication` tab.
8. THE view SHALL be usable at 360 px width (cards, no horizontal scroll) and SHALL show a localized
   empty state.
9. THE view SHALL NOT call any `/api/purchase-*`, `/api/procurement/*` or `/api/finishing-materials`
   endpoint (a test asserts the client panel performs only calls under
   `/api/position-approvals/client/`).

### Requirement 19: Frontend staff view

**User Story:** As a manager, I want to send blocks, propose analogs and handle the client's answers.

#### Acceptance Criteria

1. THE staff view SHALL list the project's positions in `AGREED` and `PACKAGE` paths grouped by block
   with: status, round state, deadline and overdue badge, sale price, **internal purchase price and
   margin** (visible to staff only), and the allowed actions of the row.
2. **Send block**: choose a block (or ticked positions), set the decision deadline and note, confirm; the
   dialog lists positions that are not eligible and why (missing price).
3. **Propose analog**: material picker (reuse of the FOR-11-00 suggestions where the caller may read them
   and of the project materials list), sale price, optional internal purchase price, deadline, note; the
   dialog shows the client-visible preview (what the client will see).
4. **Send choice** for `PACKAGE` positions: pick 2..5 materials.
5. Row actions: withdraw, change deadline, decide on behalf (with mandatory comment), client-own on
   behalf.
6. A **suggestions inbox** SHALL list open client suggestions with a link, the comment and the actions
   "propose analog from this" (prefills `sourceSuggestionId`) and "decline" (reason).
7. A round history drawer SHALL show all rounds with options (including internal prices for staff).
8. THE frontend SHALL gate buttons by `allowedActions` returned by the server and by `usePermission`.

### Requirement 20: Frontend i18n

**User Story:** As a PL or RU user, I want every label in my language.

#### Acceptance Criteria

1. EVERY new user-facing string SHALL be a key in `pl.json` and `ru.json` at parity (the existing
   `i18n.keys.test.ts` parity check passes), under `positionApproval.*`, `workspace.tab.positionApprovals`
   and the six `notification.position.*` types.
2. NO string SHALL be hard-coded; dates SHALL use the existing locale-aware formatter; money SHALL use
   the existing PLN formatter.
3. ENUM labels (round kind, round status, client status, action, decision) SHALL be keys, not backend
   strings.

### Requirement 21: Managed-entity checklist and datasets

**User Story:** As a maintainer, I want the new entity wired into the platform conventions.

#### Acceptance Criteria

1. THE resource, its grants and its seed follow `.kiro/steering/entity-creation-rules.md` steps 1-3
   (Requirement 1); the controllers use method-level `@RequiresPermission`.
2. THE round service SHALL implement `ProjectScopedService` with `getProjectIdPath() = "position.project.id"`
   and `allowedProjectIds` from `ProcurementProjectAccess`.
3. DATASET coverage: `POSITION_APPROVALS` SHALL be added to the `DatasetCoverage` exclusion list with the
   justification "workflow history and action-only permission; positions are already covered by the
   FOR-11-00 dataset". `DatasetCoverageTest` and `SensitiveFieldGuardTest` SHALL pass.

### Requirement 22: Verification and `test-cases.md`

**User Story:** As a maintainer, I want the spec covered by tests and a runnable scenario document.

#### Acceptance Criteria

1. UNIT and property tests SHALL cover: `ApprovalRoundStatusMachine`, the client money math, the
   `clientStatus` mapper, `ApprovalStageGuard`, the extended `PositionPriceVisibility`, the deadline rules
   and the leak guard (Requirement 4.8).
2. INTEGRATION tests (Testcontainers) SHALL cover: send, accept (AGREED, ANALOG, PACKAGE), stale and
   price-changed rejection, reject, analog from `ACCEPTED`, suggestion flow, client-own, on-behalf,
   withdraw, ABAC (CLIENT cannot call staff endpoints, cannot read the staff view, cannot reach
   `/api/purchase-positions`), cross-project isolation, notifications recipients and deep links.
3. FRONTEND tests SHALL cover the client view (no internal field rendered, call allow-list), accept
   dialog, package choice without prices, staff dialogs, tab gating and the i18n parity.
4. `test-cases.md` SHALL be authored in Russian per `.kiro/steering/test-cases.md` (API part against the
   Dockerized app, UI part by browser engine, repeatability by generator and teardown, regression group,
   MD report template).

### Requirement 23: Non-regression

**User Story:** As a maintainer, I want existing behavior preserved.

#### Acceptance Criteria

1. THE FOR-11-00 REST contract SHALL be unchanged: staff DTOs, `POST /transition` (MANUAL edges only) and
   the variants endpoints behave as before; the only shared change is Requirement 3.2.
2. THE `PURCHASE_POSITIONS` ABAC grants SHALL be unchanged; no existing role loses or gains access outside
   `POSITION_APPROVALS`.
3. THE FOR-05-07 offer flow, FOR-06-12 communication and the notification bell SHALL be unchanged.
4. THE existing `PositionPriceVisibility` tests, updated for Requirement 3.2, and the FOR-11-00 variant
   tests SHALL pass.
