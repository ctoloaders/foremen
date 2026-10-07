# Requirements Document

## Introduction

This spec (**FOR-05-07-offer-approval**) covers the **offer preparation and client-negotiation stage**
of a project in the design (`DRAFT`) stage. It introduces the **Offer** (built from the project's
estimate in **any** status — pricing is no longer a precondition for preparing an offer),
**offer discounts/rebates**, and a **two-sided negotiation loop** between the executor
(MANAGER/ADMIN/ESTIMATOR) and the CLIENT that culminates in an **approved, immutable agreed offer
version**. That approved
version is the hand-off point that downstream specs consume: FOR-05-14 freezes it into an
`OfferPriceSnapshot` and seeds the `OFFER_BASE` project price, and FOR-05-09 turns it into a contract.

The offer's prices are a **live reference** to the estimate's client-facing final prices (never a
stored copy): the offer references the estimate by id and applies only its own discounts on top, so
estimate-side changes flow into the offer automatically (Requirement 19). A fully **APPROVED** offer
makes the project **eligible** to move to `ProjectStatus` `ACTIVE` (in-work), but the actual
`DRAFT → ACTIVE` transition happens only AFTER the contract is signed (FOR-05-09); once the project is
`ACTIVE`, the estimate is **service-locked** (`DraftGate` / `EstimateStatus`) and any further change to
prices, materials, or volumes goes through **amendments** (FOR-06), not direct estimate/offer edits
(Requirement 20).

The feature has four parts:

1. **Offer lifecycle + entity.** Prepare an `Offer` from the project's estimate **in any status**
   (parent design §4.2: `Offer{ project, estimate, selectedPackage?, status, totals }`) — pricing
   (`PRICED`) is NOT a precondition; readiness is always visible rather than gating preparation — send
   it to the client, run the negotiation status machine, and reach a terminal state (`APPROVED` /
   `REJECTED` / `WITHDRAWN`). Offer totals = estimate totals minus the effective applied discounts.

2. **Offer discounts (`OfferDiscount`).** Parent design §4.2: `OfferDiscount{ offer, scope
   (GLOBAL/CATEGORY/LINE), targetId, kind (PERCENT/ABSOLUTE), value }`. This spec defines their
   validation, application order, and how they recompute offer totals.

3. **Negotiation rounds (a full request thread).** The CLIENT opens a discount request carrying ONLY a
   scope + target + free-text justification (**no figure** — the client does NOT submit a value); the
   MANAGER decides the figure by either PROPOSING a discount (kind PERCENT/ABSOLUTE + value) or
   REJECTING the request with a reasoned explanation; the CLIENT may then ACCEPT the manager's proposal
   or DECLINE it, and on a decline the MANAGER may propose a different discount or reject the client's
   rejection (again with a reasoned explanation); a **history of rounds** is retained; the Offer carries
   a **revision/version**; an accepted proposal **materializes** into applied `OfferDiscount`(s) and a
   new offer revision; `APPROVED` applies only to the agreed version.

4. **Client actions + workspace-side UI.** The CLIENT (role `CLIENT`, project member, ABAC `OFFERS`
   `R(own)+approve`, parent design §5) may approve / reject the current offer version, request a
   discount (scope + target + justification only, WITHOUT a figure — the manager decides the value),
   select the commercial package, **choose concrete finishing materials for the package's
   finishing Placeholder slots**, and **request a per-material (LINE-scoped) discount**. This spec also
   delivers the **in-workspace** "Оферта / согласование" (Offer / approval) tab that renders the offer,
   discounts, negotiation thread, and the client finishing-material selection surface; the dedicated
   **client-facing portal rendering is delegated to FOR-09** (this spec exposes the reusable server
   actions + the in-workspace surface).

5. **Client finishing-material selection (reusable by FOR-09).** When the CLIENT changes the commercial
   package, the offer's finishing-material selection is (re-)derived from that package (reusing the
   FOR-05-05 package finishing-material propagation). The CLIENT may then pick a concrete finishing
   product for any finishing **Placeholder** slot (a client-scoped invocation of the FOR-05-05
   `chooseConcrete` write path), while everything else — already-chosen materials, all construction
   materials, works, volumes, and prices — stays **read-only** for the CLIENT. This client-side
   material-selection surface is designed to be **reused by the FOR-09 client portal** (same server
   actions).

6. **Generic notification service + in-app bell (reusable platform capability).** This spec also
   delivers a **reusable, cross-cutting notification capability** that is intentionally **NOT coupled to
   the Offer**: a per-recipient `Notification` domain persisted in the DB (the in-app base
   implementation), a generic "create a notification for a recipient user" server API (the extension
   point future delivery channels build on), and an in-app **Bell** surface in the top bar (unread badge
   + popup list). It is framed as its own set of requirements (Requirement 13) so that other specs
   (FOR-05-09, FOR-06) consume it. The Offer negotiation flow is wired as the **first consumer** of that
   service through **two concrete triggers** (Requirement 14): a client discount request / new round
   notifies the project MANAGER, and a manager approve/reject notifies the requesting CLIENT — both
   carrying a deep-link to the Offer_Tab. This spec implements only the **in-app (DB) base
   notification** plus those two triggers; additional delivery channels (email, SMS, Telegram) and the
   asynchronous fan-out `Delegating_Notification_Service` are explicitly **future work** (Non-Goals),
   and the domain is designed so they slot in later without redesign.

### Cost confidentiality, pricing-tab reuse, offer visibility, and client staging

This spec also encodes four cross-cutting product decisions that tighten and extend the parent design
§5 access model:

- **Hard cost confidentiality (security-critical).** A CLIENT SHALL NEVER see any self-cost / cost /
  себестоимость (the FOR-05-06 margins/cost model — `MarginCostService` base cost, tier costs,
  worker-type tiers, material `cost_net`, per-branch or per-tier margins) and SHALL NEVER see the
  estimate (kosztorys) at all. A CLIENT sees ONLY offer-level prices (the offer's client-facing
  net/gross totals, per-line/per-category offer prices, package prices, applied discounts, and the
  finishing `Type_Price_Range` / chosen product price). This is enforced **server-side** through a
  dedicated Client_Offer_Read_Model — it is impossible for a cost/estimate/margin field to reach a
  client through any endpoint (Requirement 15), stated as a confidentiality invariant (Requirement 10).
- **Tightened role/access model.** The ESTIMATE (kosztorys + FOR-05-06 margins/costs) is accessible to
  **MANAGER and ADMIN only**; OFFERS are accessible to **CLIENT (their own project, visibility-gated),
  MANAGER, and ADMIN only**. No other role reaches the estimate, costs/margins, or offers. This
  **deviates from parent design §5** (which granted FOREMAN/WORKER/FINANCIER read on ESTIMATE and a
  broader OFFERS matrix); this spec tightens those grants (Requirement 16).
- **Pricing-tab reuse + manager-gated visibility.** This spec repurposes the EXISTING workspace tab
  keyed `pricing` (route `/projects/:projectId/pricing`) as the client-facing Offer surface, changing
  its ABAC gate from `PROJECT_PRICING` to `OFFERS` and (optionally) renaming its label to
  "Оферта / согласование". The offer carries an Offer_Visibility_Status (`DRAFT` / `ON_APPROVAL` /
  `APPROVED`) the manager controls; the CLIENT reaches the offer ONLY AFTER the manager explicitly
  moves it out of `DRAFT` (Requirement 17).
- **Client staging with undo/redo.** The CLIENT's finishing-material selection operates in a UI
  **staging** mode with undo/redo, committed only on explicit save via the Requirement 11 write path;
  the server remains authoritative and re-validates on commit (Requirement 18).

### Relationship to existing specs (source of truth)

- **Parent overview** (`OVERVIEW.md`) — child row 07; dependency graph `06 → 07 → 14`, `07 → 09`.
- **Parent design §4.2** — `Offer`, `OfferDiscount`, `OfferPriceSnapshot` fields; multi-client
  membership (a project may have `>= 1` `CLIENT` member, added at any lifecycle stage).
- **Parent design §4.3** — project status machine `DRAFT → READY_TO_OFFER → OFFERED → APPROVED →
  ACTIVE`. This spec drives `READY_TO_OFFER → OFFERED` (on send) and `OFFERED → APPROVED` (on client
  approval); it does NOT own the `APPROVED → ACTIVE` (contract-signed) transition (FOR-05-09).
- **Parent design §4.3a / §4.6** — the price freeze (`OfferPriceSnapshot`) is **FOR-05-14's** job;
  this spec only guarantees a stable immutable agreed offer version at `APPROVED` for it to consume.
- **Parent design §5** — ABAC: `OFFERS` resource (project-scoped, `getProjectIdPath = offer.project.id`),
  matrix row `OFFERS: ADMIN CRUD; MANAGER CRUD(own); FOREMAN R(own); FINANCIER R(own); CLIENT
  R(own)+approve`; `PROJECT_PRICING` is a separate resource owned by FOR-05-14.
- **Parent design §2.1** — FOR-09 owns the client-facing portal rendering; discounts/prices remain
  MANAGER-controlled writes.
- **FOR-05-05 (bill-of-materials) / FOR-05-05b (list-of-materials)** — the material model this spec
  builds on: `EstimateLineRoomMaterialEntity` carries the `Branch` (construction/finishing), a copied
  `Type_Price_Range` (`rangeMin`/`rangeMax`) for a Placeholder, and the `Concrete_Material` FKs
  (`concreteMaterialId` + `concreteNet`) once chosen; a **Placeholder** is a finishing line with no
  concrete product. FOR-05-05 owns the concrete-material **catalog** and the kosztorys `chooseConcrete`
  **write path**, the package finishing-material propagation ("applied_from_package"), and the
  `Materials_Fulfilment` ratio. This spec adds only the **CLIENT-scoped, offer-stage,
  finishing-Placeholder-only** invocation of that write path plus its gating (Requirements 11–12).
- **`entity-creation-rules`** — `OFFERS` resource seed changeset, ADMIN grant + role matrix, class-level
  `@PermissionResource("OFFERS")`, `ProjectScopedService.getProjectIdPath()`, startup annotation
  completeness.

## Glossary

- **Offer**: the entity `Offer{ project (FK), estimate (FK), selectedPackage (FK, nullable), revision,
  status, totalNet, totalVat, totalGross }` (parent §4.2), representing a priced proposal sent to the
  client. Project-scoped (`offer.project.id`).
- **PRICED estimate**: an `Estimate` whose status is `PRICED` (parent §4.2 estimate statuses
  `DRAFT/PRICED/APPROVED/SIGNED`). `PRICED` is **no longer a precondition for preparing an Offer** —
  an Offer may be prepared from the project's estimate in ANY status (including `DRAFT`); the
  `PRICED` state now only informs the offer's readiness surface, not whether preparation is allowed
  (Requirement 1).
- **OfferDiscount**: a discount/rebate line `OfferDiscount{ offer (FK), scope (GLOBAL/CATEGORY/LINE),
  targetId, kind (PERCENT/ABSOLUTE), value }` (parent §4.2), applied on top of estimate totals.
- **Discount_Scope**: `GLOBAL` (whole offer), `CATEGORY` (a work category, `targetId` = category id),
  or `LINE` (a single estimate line, `targetId` = line id).
- **Discount_Kind**: `PERCENT` (a percentage of the scope's base) or `ABSOLUTE` (a fixed amount off the
  scope's base, in the offer currency).
- **Effective_Discount**: the money amount a discount actually removes from its scope base after
  validation and application-order rules (Requirement 2).
- **NegotiationRound** (a.k.a. **OfferRequest**): one entry in the negotiation thread `{ offer (FK),
  offerRevision, roundNo, initiatorRole, kind (DISCOUNT_REQUEST/MANAGER_PROPOSAL/MANAGER_REJECT/
  CLIENT_ACCEPT/CLIENT_DECLINE), scope (GLOBAL/CATEGORY/LINE), targetId, valueKind (PERCENT/ABSOLUTE,
  set only on a MANAGER_PROPOSAL), value (the manager's proposed figure, set only on a
  MANAGER_PROPOSAL), justification (free text, on the client's DISCOUNT_REQUEST), explanation (free
  text, REQUIRED on a MANAGER_REJECT), clientComment (nullable, LINE scope), status, createdBy,
  createdAt }`. The **figure is owned by the MANAGER**, never the client: a `DISCOUNT_REQUEST` carries
  ONLY scope + target + justification and NO value/kind; the MANAGER decides the value on a
  `MANAGER_PROPOSAL` (kind + value) or refuses on a `MANAGER_REJECT` (which MUST carry a non-empty
  reasoned `explanation`); the CLIENT then responds with `CLIENT_ACCEPT` or `CLIENT_DECLINE`
  (Requirement 4). Retains the full ordered request thread.
- **Agreed_Offer_Version**: the specific `Offer` revision that the CLIENT approved; it is **immutable**
  once `APPROVED` and is what FOR-05-14 / FOR-05-09 consume.
- **Offer_Revision**: a monotonically increasing integer on the Offer, incremented whenever an accepted
  negotiation round materializes new applied discounts (or the package selection changes the priced
  proposal).
- **Escalation_Threshold**: a configurable cap (percent and/or absolute) applied to the MANAGER'S
  PROPOSED discount value, above which the manager's proposal requires ADMIN approval before it can be
  proposed/accepted (Requirement 6). Its default is a **GLOBAL config value** with an optional
  per-project override (configurable).
- **Offer_Status**: one of `DRAFT`, `SENT`, `CHANGES_REQUESTED`, `COUNTERED`, `APPROVED`, `REJECTED`,
  `WITHDRAWN` (Requirement 3). `APPROVED` / `REJECTED` / `WITHDRAWN` are **terminal states**.
- **Executor**: a MANAGER, ADMIN, or ESTIMATOR acting on the offer/discount side; distinct from the
  CLIENT side. (ESTIMATOR shares the executor OFFERS write set `READ`/`CREATE`/`UPDATE` but, unlike
  MANAGER/ADMIN, has **no** `OFFERS APPROVE` grant — see the ESTIMATOR role glossary entry and
  Requirement 16.)
- **ESTIMATOR**: a new system role (code `ESTIMATOR`, Russian label "Сметчик", Polish label
  "Kosztorysant") whose resource grants are **exactly the FOREMAN resource grant set** (every resource
  and operation FOREMAN holds today — e.g. `READ` on `ROOM_TYPES`, `WORK_CATEGORIES`,
  `MEASUREMENT_UNITS`, `MATERIAL_SELLERS`, `MATERIALS`, `OFFER_PACKAGES`, `VAT_RATES`, `WORK_CATALOG`,
  `WORKER_TYPES`, `DELIVERY_STATUSES`, `MATERIALS_CONSTRUCTION`, and the rest; `READ`+`UPDATE` on
  `ROOMS`; `READ` on `PROJECT_MEMBERS`) PLUS two grants FOREMAN lacks: `OFFERS` with `READ`+`CREATE`+
  `UPDATE` (**no** `APPROVE`, no `DELETE`) and `ESTIMATE` with `READ`+`CREATE`+`UPDATE`. ESTIMATOR is
  an executor for offer preparation and the executor offer actions but can NOT approve an offer, and
  does NOT receive the FOR-05-06 cost/margin read models beyond what the `ESTIMATE` grant already
  conveys (Requirement 16).
- **Offer_Tab**: the in-workspace project tab ("Оферта / согласование" — Offer / approval), rendering
  the offer, discounts, and negotiation thread. Design/DRAFT stage.
- **Concrete_Material** (per FOR-05-05): a specific chosen `ConstructionMaterial` / `FinishingMaterial`
  product on an estimate material line — the product actually selected for a material type in a cell.
  Terminology is kept identical to FOR-05-05 / FOR-05-05b.
- **Placeholder (finishing slot)** (per FOR-05-05): a finishing-branch material line
  (`EstimateLineRoomMaterialEntity`, FOR-05-05b) that carries a copied `Type_Price_Range`
  (`rangeMin`/`rangeMax`) but has **no** `Concrete_Material` chosen yet (`concreteMaterialId` /
  `concreteNet` unset). A finishing Placeholder is the only material line a CLIENT may fill.
- **Branch (construction/finishing)** (per FOR-05-05): the split classifying every material line as
  construction or finishing. CLIENT concrete selection is confined to the **finishing** branch.
- **finishing material line** (per FOR-05-05b): an `EstimateLineRoomMaterialEntity` whose `Branch` is
  finishing — either a Placeholder (slot) or a line with a chosen `Concrete_Material`.
- **Type_Price_Range** (per FOR-05-05): the copied MIN..MAX price band (`rangeMin`/`rangeMax`) frozen
  onto a Placeholder material line; collapses to the product price once a `Concrete_Material` is chosen.
- **Materials_Fulfilment** (per FOR-05-05): the concrete-vs-total material-line ratio (the kosztorys
  fill indicator); this spec reuses it and does NOT redefine it.
- **Choose_Concrete** (per FOR-05-05 `chooseConcrete`): the kosztorys action that selects a
  `Concrete_Material` for a material line. This spec adds a **CLIENT-scoped, offer-stage,
  finishing-Placeholder-only** invocation of that same write path (the write path itself is owned by
  FOR-05-05); it is NOT a new catalog or a new estimate write.
- **Package_Derived_Finishing_Selection**: the offer's finishing-material selection (re-)derived from
  the offer's `selectedPackage` by reusing the FOR-05-05 package finishing-material propagation
  ("applied_from_package"); the set of finishing lines/Placeholders the CLIENT may then fill.
- **Client_Material_Selection_Surface**: the reusable server actions plus in-workspace UI surface that
  let a CLIENT (re-)derive the finishing selection on package change, choose concrete finishing
  products for Placeholders, and request per-material discounts; the same server actions are reused by
  the FOR-09 client portal (FOR-09 owns the portal rendering).
- **Per_Material_Discount_Request**: a `LINE`-scoped `DISCOUNT_REQUEST` `NegotiationRound`
  (Requirement 4, `scope = LINE`, `targetId` = the estimate line / material line id) opened by a CLIENT
  against a specific finishing material (chosen product or Placeholder slot). Like every client
  discount request it carries ONLY scope + target + free-text justification (and an optional
  `clientComment`) and **NO figure** — the MANAGER decides the value via a `MANAGER_PROPOSAL` or
  refuses via a `MANAGER_REJECT` (reasoned explanation required). It reuses the negotiation-round
  machinery and is not a separate discount mechanism.
- **Notification**: the entity `Notification{ recipient (FK user), type (Notification_Type i18n key),
  body (text), deepLink (nullable UI target), read (boolean read/unread state), createdAt, + audit }`,
  persisted **per recipient user** and always stored in the DB (the in-app base implementation). Not
  project-scoped; owned by (scoped to) the recipient user.
- **Notification_Type**: an i18n key identifying the kind of a `Notification` (e.g. an
  offer-negotiation-requested / discount-approved / discount-rejected type). New types are added as new
  notification-emitting flows appear; the type resolves to a localized title/label with PL/RU parity. A
  raw type key is never surfaced to the user.
- **Deep_Link**: an optional UI target stored on a `Notification` (e.g. a route/path such as the
  Offer_Tab of a project) that the user is navigated to when the notification message is clicked. A
  notification without a Deep_Link is non-interactive.
- **Bell**: the in-app notification surface — a bell icon in the top bar (positioned to the right,
  immediately before the language button) showing a badge with the count of the caller's UNREAD
  notifications, and a popup presenting a compact list of the caller's notifications sorted by
  `createdAt` DESCENDING, each row offering toggle-read/unread and delete actions.
- **Notification_Service**: the generic, in-app (DB) base notification service delivered by this spec —
  exposing a server API to "create a `Notification` for a recipient user" and the per-recipient
  read/mutate/delete + unread-count operations behind the Bell. It is the reusable extension point that
  future delivery channels and the `Delegating_Notification_Service` build on; it is NOT coupled to the
  Offer.
- **Delegating_Notification_Service** (forward-looking, out of scope now): a future service that, given
  a notification to emit, iterates all available notification services (in-app, email, SMS, Telegram)
  and ASYNCHRONOUSLY attempts delivery through each, logging (only) on error without rolling back the
  triggering domain transition. This spec does NOT implement it, but the `Notification_Service` domain
  is designed so it slots in later without redesign (Non-Goals).
- **Offer_Visibility_Status**: the manager-controlled tri-state governing whether/how the offer is
  visible to the CLIENT — `DRAFT` (черновик; not yet client-visible), `ON_APPROVAL` (на согласовании;
  the client-visible negotiable window), `APPROVED` (согласовано; agreed and ready to sign). It maps
  onto the Requirement 3 `Offer_Status` machine: `DRAFT` = `Offer_Status` `DRAFT` (no client access);
  `ON_APPROVAL` = the client-visible negotiable states `SENT` / `CHANGES_REQUESTED` / `COUNTERED`;
  `APPROVED` = `Offer_Status` `APPROVED`. It does not replace the Requirement 3 machine — it is the
  client-facing projection of it plus the client-visibility gate (Requirement 17).
- **Client_Offer_Read_Model**: the server-side, offer-level-only projection of an Offer returned to a
  CLIENT — it contains ONLY offer-level fields (offer net/gross totals, per-line/per-category offer
  prices, package prices, applied discounts, finishing `Type_Price_Range` / chosen product price) and
  by construction contains NO self-cost, cost, margin, worker-rate, or estimate-internal unit-price
  field (FOR-05-06 cost/margin model or kosztorys prices). The confidentiality guarantee of
  Requirement 15 / Requirement 10 is a property of this read model.
- **Offer_Readiness**: a readiness metric exposed WHILE the offer is `ON_APPROVAL`, measuring how close
  the offer is to `APPROVED` — the percentage of (a) unresolved negotiation proposals (open/unaccepted
  rounds, Requirement 4) and (b) unselected finishing materials in the package (finishing Placeholder
  slots not yet filled, reusing Materials_Fulfilment / the Package_Derived_Finishing_Selection),
  optionally combined into a single readiness percentage. Owned by this spec; MAY be surfaced through
  the FOR-05-01 workspace ReadinessWidget (Requirement 17).
- **Client_Staging**: the UI-side staging behavior for the CLIENT finishing-material selection — staged
  changes (choose a concrete finishing product for a Placeholder slot, package change) are held
  client-side with UNDO/REDO and DISCARD, and persisted only on an explicit save/submit that invokes
  the existing Requirement 11 `Choose_Concrete` / `selectPackage` write path; the server remains
  authoritative and re-validates on commit. Mirrors the FOR-05-05b `reserveDraftStore` staging pattern
  conceptually (Requirement 18).
- **Pricing_Tab (reused as Offer surface)**: the EXISTING workspace tab currently keyed `pricing`
  (route `/projects/:projectId/pricing`, labelKey `workspace.tab.pricing`, currently a PlaceholderTab
  gated by `PROJECT_PRICING` read). This spec repurposes it as the client-facing Offer surface (the
  Offer_Tab surface): its ABAC gate changes to `OFFERS` and it MAY be renamed to
  "Оферта / согласование". The exact final tab key/label rename is a design detail; the requirement is
  reuse, not a new parallel tab (Requirement 17).
- **Live_Referenced_Pricing**: the pricing model in which the Offer's prices are NOT a frozen copy of
  the estimate's prices but a **live reference** to the estimate's client-facing FINAL prices (the
  estimate's own frozen `unitPrice` / value-net and package prices — NOT the FOR-05-06 self-cost /
  margins). The offer adds ONLY its own discounts (Requirement 2) on top, so the offer effective price =
  referenced estimate client-facing final price − applied offer discounts. Because it is a reference,
  binding new materials, changing the `appliedPackageCode`, or changing volumes on the ESTIMATE changes
  the estimate's client-facing final prices, and — through the `Offer.estimate` FK — those changes are
  reflected in the offer automatically without any re-copy. The offer references the estimate by id only
  and maps ONLY the client-facing FINAL price into the offer read model, NEVER the self-cost / cost /
  margin (FOR-05-06) (Requirement 19, Requirement 15).
- **Estimate_Service_Lock**: the existing server-side write gate on the estimate — `EstimateStatus`
  past `DRAFT` combined with `DraftGateGuard.assertDraft(estimate)`, which throws `409
  error.estimate.locked` on every estimate write path (`EstimateAssignmentService`,
  `EstimateLineRoomQtyService`, `MaterialsListService.saveReserveMap`). Once the project reaches
  `ProjectStatus` `ACTIVE` (in-work) the estimate is locked: no changes to works, volumes, materials,
  prices, package, or discounts on the estimate/offer succeed at the service layer, and further changes
  go ONLY through FOR-06 amendments (Requirement 20). Server-enforced, not UI-only.

## Requirements

### Requirement 1: Prepare an Offer from the project's estimate (any status)

**User Story:** As an executor (MANAGER/ADMIN/ESTIMATOR), I want to prepare an offer from the project's
estimate regardless of its pricing status, so that I have a proposal to send to the client without
waiting for the estimate to reach `PRICED`.

#### Acceptance Criteria

1. WHEN an executor (MANAGER/ADMIN/ESTIMATOR) prepares an offer for a project, THE System SHALL create
   an `Offer` linked to that project and that estimate (parent §4.2) **regardless of the estimate's
   status** (including `DRAFT`), with `status = DRAFT`, `revision = 1`, and `selectedPackage` seeded
   (copied) from the estimate's applied package per criterion 1.6; pricing (`PRICED`) is NOT a
   precondition for preparation.
2. THE System SHALL NOT require the estimate to be `PRICED` to prepare an offer and SHALL NOT reject
   preparation on the grounds that the estimate is not `PRICED`; the only guards on preparation are the
   single-active-offer rule (criterion 1.4) and the existence of the project and its estimate. (The
   former not-`PRICED` rejection and its `error.offer.estimate.not.priced` message are removed.)
3. THE System SHALL compute the Offer totals as the estimate totals minus the effective applied
   discounts: `totalNet = estimate.totalNet − Σ Effective_Discount(net)`, with `totalVat` and
   `totalGross` recomputed from the discounted net using the project VAT rate; THE System SHALL compute
   these totals OVER the estimate's client-facing final prices referenced **live** through the
   `Offer.estimate` FK (Live_Referenced_Pricing, Requirement 19) — NOT over a snapshot the offer stores
   — and SHALL NOT persist its own copy of the per-line estimate prices: the offer references those
   prices and applies only its discounts on top.
4. THE System SHALL allow **exactly one non-terminal (active) Offer per project** at a time; a new Offer
   MAY be prepared only WHEN the project has no Offer in a non-terminal status. Negotiation is captured
   as revisions and rounds inside that single active Offer rather than as parallel Offers. A single
   non-terminal Offer MAY accumulate MULTIPLE negotiation propositions at three scopes — `LINE`
   (per work-item / estimate line), `CATEGORY` (per work-type group), and `GLOBAL` (project level) —
   governed by an OVERRIDE-AND-CANCEL hierarchy: a `CATEGORY` proposition OVERRIDES AND CANCELS the
   individual `LINE` propositions within that work-type group, and a `GLOBAL` proposition OVERRIDES AND
   CANCELS ALL existing propositions (both `LINE` and `CATEGORY`); only the surviving (non-superseded)
   propositions apply (Requirement 4.9, Requirement 2.5).
5. WHEN an executor sets `selectedPackage` to one of the seeded commercial packages (START / COMFORT /
   PRESTIGE, parent §4.2 `selectedPackage`), THE System SHALL persist the selection on the Offer and
   recompute totals for the selected package.
6. WHEN an executor prepares an offer, THE System SHALL copy the offer's initial `selectedPackage` from
   the estimate's applied package: it SHALL resolve `EstimateEntity.appliedPackageCode`
   (`estimates.applied_package_code`, the offer-package code last applied to the kosztorys, FOR-05-05
   Wave 1b) to the matching `OfferPackage` and set it as the new Offer's `selectedPackage`, then
   recompute totals for that package per criterion 1.3.
7. IF the estimate's `appliedPackageCode` is null (no package was ever applied to the kosztorys) OR does
   not resolve to a known `OfferPackage`, THEN THE System SHALL create the Offer with
   `selectedPackage = null` (it MAY be set later per criterion 1.5 / Requirement 11) and SHALL NOT fail
   offer creation.

### Requirement 2: Offer discounts (scope, kind, validation, application order)

**User Story:** As an executor, I want to apply scoped discounts to an offer, so that negotiated
rebates are reflected in the offer totals.

#### Acceptance Criteria

1. THE System SHALL persist each discount as an `OfferDiscount` with `scope ∈ {GLOBAL, CATEGORY, LINE}`,
   `targetId` (null for GLOBAL, category id for CATEGORY, line id for LINE), `kind ∈ {PERCENT,
   ABSOLUTE}`, and `value` (parent §4.2).
2. IF a discount `value` is negative, THEN THE System SHALL reject the write with a localized validation
   message.
3. IF a `PERCENT` discount `value` exceeds `100`, THEN THE System SHALL reject the write with a
   localized validation message.
4. IF an `ABSOLUTE` discount `value` exceeds the net subtotal of its scope base (line, category, or
   whole-offer subtotal respectively), THEN THE System SHALL reject the write with a localized
   validation message.
5. THE System SHALL apply discounts by the SAME scope OVERRIDE hierarchy as the negotiation propositions
   (Requirement 1.4): a `GLOBAL` discount SUPERSEDES the `CATEGORY` and `LINE` discounts it covers; a
   `CATEGORY` discount SUPERSEDES the `LINE` discounts within its work-type group; only the surviving
   (non-superseded) discounts are applied, so a scope's effective discount is the one from the HIGHEST
   surviving scope covering that scope. THE System SHALL compute this deterministically and reproducibly:
   resolve, for each estimate line, the single surviving discount (the `GLOBAL` discount if present,
   else the `CATEGORY` discount covering the line's work-type group if present, else the line's own
   `LINE` discount if present), apply only that surviving discount to the line's base, and derive the
   category/offer subtotals from the resulting per-line net — so the same discount set always yields the
   same totals regardless of insertion order.
6. WHEN discounts change (added, edited, removed), THE System SHALL recompute the Offer totals per
   Requirement 1.3.
7. THE System SHALL ensure the resulting Offer `totalNet` is never negative and no single discount's
   `Effective_Discount` exceeds its scope base.
8. WHILE a discount write is attempted by a non-executor (not MANAGER/ADMIN/ESTIMATOR), THE System
   SHALL reject it server-side (discounts remain executor-controlled writes via the `OFFERS` write
   grant, parent §5 as tightened by Requirement 16; the CLIENT never writes discounts directly).

### Requirement 3: Offer negotiation status machine

**User Story:** As an executor and as a client, I want a well-defined offer status machine, so that the
negotiation has predictable, legal transitions and terminal states.

#### Acceptance Criteria

1. THE System SHALL model the Offer status set `{DRAFT, SENT, CHANGES_REQUESTED, COUNTERED, APPROVED,
   REJECTED, WITHDRAWN}`, with `APPROVED`, `REJECTED`, and `WITHDRAWN` as **terminal** states.
2. WHEN an executor sends a `DRAFT` offer to the client, THE System SHALL transition the offer to `SENT`
   and transition the project status from `READY_TO_OFFER` to `OFFERED` (parent §4.3).
3. WHEN the CLIENT requests a discount (scope + target + justification, WITHOUT a figure — the value is
   the manager's to decide, Requirement 4.1) on a `SENT` or `COUNTERED` offer, THE System SHALL
   transition the offer to `CHANGES_REQUESTED` and open a new negotiation round (Requirement 4).
4. WHEN the executor responds to a `CHANGES_REQUESTED` offer with a `MANAGER_PROPOSAL` (a discount value
   the manager proposes, Requirement 4), THE System SHALL transition the offer to `COUNTERED`.
5. WHEN the CLIENT approves a `SENT` or `COUNTERED` offer, THE System SHALL transition the offer to
   `APPROVED` and transition the project status from `OFFERED` to `APPROVED` (parent §4.3).
6. WHEN the CLIENT rejects a `SENT` or `COUNTERED` offer, THE System SHALL transition the offer to
   `REJECTED`.
7. WHEN the executor withdraws a non-terminal offer, THE System SHALL transition the offer to
   `WITHDRAWN`.
8. IF any actor attempts a transition not defined by the status machine (including any transition out of
   a terminal state), THEN THE System SHALL reject it with a localized error and leave the offer
   unchanged.
9. WHILE an offer is in a terminal state, THE System SHALL reject all further discount writes,
   negotiation rounds, and package changes on that offer.

### Requirement 4: Negotiation rounds (client requests → manager proposes the value → client accepts/declines)

**User Story:** As a client and as an executor, I want a threaded negotiation with a full history in
which the client asks for a discount and the manager decides the figure, so that every request,
manager proposal, rejection, and client decision is recorded and the agreed terms are traceable.

#### Acceptance Criteria

1. WHEN the CLIENT opens a discount request, THE System SHALL create a `NegotiationRound` with
   `kind = DISCOUNT_REQUEST`, the requested `scope` (`GLOBAL`/`CATEGORY`/`LINE`), a `targetId` (null for
   `GLOBAL`, category/work-type-group id for `CATEGORY`, line id for `LINE`), and a free-text
   `justification`; THE System SHALL NOT accept any `value` or value `kind` on the client's request (the
   client requests a discount WITHOUT a figure — the figure is the manager's to decide).
2. WHERE the discount request is `LINE`-scoped, THE System SHALL allow an optional `clientComment`
   paired with that round.
3. WHEN the MANAGER responds to an open client request (or to a client's `CLIENT_DECLINE`), THE System
   SHALL allow exactly one of: a `MANAGER_PROPOSAL` (`kind ∈ {PERCENT, ABSOLUTE}` + a proposed
   `value`, carrying its scope/target) OR a `MANAGER_REJECT` (a refusal that MUST carry a non-empty,
   reasoned free-text `explanation`), each recorded as a `NegotiationRound` referencing the same offer
   revision.
4. WHEN the MANAGER has issued a `MANAGER_PROPOSAL`, THE System SHALL allow the CLIENT to respond with
   exactly one of: a `CLIENT_ACCEPT` (accept the manager's proposed discount) OR a `CLIENT_DECLINE`
   (decline it, which opens a new round the manager answers per criterion 3); WHEN the CLIENT issues a
   `CLIENT_DECLINE`, THE System SHALL allow the MANAGER to either issue a different `MANAGER_PROPOSAL` or
   a `MANAGER_REJECT` (with a reasoned `explanation`) per criterion 3.
5. WHEN the CLIENT issues a `CLIENT_ACCEPT` of a `MANAGER_PROPOSAL`, THE System SHALL materialize the
   manager's proposed terms into applied `OfferDiscount`(s) — respecting the scope OVERRIDE-AND-CANCEL
   hierarchy of Requirement 1.4 / Requirement 2.5 (a broader-scope accepted discount cancels/supersedes
   the narrower-scope discounts it subsumes) — and increment the Offer `revision`.
6. THE System SHALL retain the full ordered history of rounds (never delete a round), each carrying its
   `roundNo`, `initiatorRole`, `createdBy`, and `createdAt`, and SHALL bind each `NegotiationRound` to
   the `Offer_Revision` in effect when it was created, so history is unambiguous across revisions.
7. IF an actor attempts to act on a round that is already resolved (accepted/declined/rejected/
   superseded), THEN THE System SHALL reject the action with a localized error.
8. IF a `MANAGER_REJECT` is submitted with a missing or blank `explanation`, THEN THE System SHALL
   reject the action server-side with a localized error and SHALL NOT record the rejection (a reasoned
   explanation is mandatory on every manager rejection — whether rejecting a client's request or
   rejecting a client's `CLIENT_DECLINE`).
9. WHEN a broader-scope proposition is created for the offer, THE System SHALL cancel/supersede the
   narrower-scope propositions it subsumes — a `CATEGORY`-scoped proposition cancels the `LINE`
   propositions within its work-type group, and a `GLOBAL`-scoped proposition cancels ALL existing
   `LINE` and `CATEGORY` propositions — so that only the surviving (non-superseded) propositions and
   their resulting discounts apply (Requirement 1.4, Requirement 2.5).

### Requirement 5: Client actions and access control (ABAC)

**User Story:** As a client, I want to approve, reject, request a discount, and select a package on my
project's offer, so that I can negotiate and accept the proposal.

#### Acceptance Criteria

1. THE System SHALL gate all Offer and negotiation operations behind the project-scoped ABAC resource
   `OFFERS` (`getProjectIdPath = offer.project.id`, parent §5), auto-filtered to the caller's project
   membership (`own`), and SHALL restrict `OFFERS` access to `CLIENT` (own project, visibility-gated),
   `MANAGER`, `ADMIN`, and `ESTIMATOR` only — no other role has offer access (Requirement 16).
   `ESTIMATOR` holds `OFFERS` `READ`+`CREATE`+`UPDATE` but NOT `APPROVE`.
2. WHERE a caller is a `CLIENT` project member with `OFFERS R(own)+approve`, THE System SHALL permit the
   CLIENT to: approve the current offer version, reject it, open a discount request (Requirement 4),
   select the commercial package (START/COMFORT/PRESTIGE), **choose a `Concrete_Material` for a
   finishing Placeholder slot of the offer** (Requirement 11), and **request a per-material
   (`LINE`-scoped) discount** (Requirement 12); THE System SHALL keep everything else — already-chosen
   materials, all construction materials, works, volumes, and prices — READ-ONLY for the CLIENT.
3. WHERE a caller is a `CLIENT` project member, THE System SHALL deny that caller any write to
   `OfferDiscount` rows directly (discounts remain MANAGER-controlled, parent §5); the CLIENT influences
   discounts only through negotiation rounds.
4. WHERE a caller is a MANAGER, ADMIN, or ESTIMATOR with `OFFERS` write, THE System SHALL permit
   preparing, sending, countering, withdrawing offers and writing discounts; WHERE the caller is an
   ESTIMATOR, THE System SHALL additionally deny the `OFFERS APPROVE` sub-action server-side (ESTIMATOR
   has no `APPROVE` grant), so an ESTIMATOR cannot approve/reject an offer on the client's behalf.
5. WHERE a project has multiple `CLIENT` members (parent §4.2), THE System SHALL apply the CLIENT
   read/approve grants to every CLIENT member equally.
6. IF a caller without the required `OFFERS` grant attempts any offer/negotiation operation, THEN THE
   System SHALL reject it server-side (not only in the UI).
7. THE System SHALL seed the `OFFERS` resource, its ADMIN grant, and the role-matrix rows per
   `entity-creation-rules`, extending the parent §5 `OFFERS` row with the negotiation-round sub-actions,
   and SHALL pass startup annotation-completeness validation.
8. WHERE a caller is a `CLIENT` project member, THE System SHALL deny that caller any write to works,
   volumes, construction materials, prices, and to any finishing material line that already has a chosen
   `Concrete_Material`; the CLIENT's only material write is choosing a `Concrete_Material` for a
   finishing Placeholder slot of the caller's own project's offer (Requirement 11).
9. WHILE the offer's Offer_Visibility_Status is `DRAFT` (Requirement 17), THE System SHALL deny every
   `CLIENT` member any read or action on the offer server-side; a CLIENT SHALL reach the offer ONLY
   AFTER the executor moves it to `ON_APPROVAL` ("send to client") — a client-visibility gate on top of
   the `OFFERS R(own)` grant (Requirement 17).
10. THE System SHALL restrict all ESTIMATE (kosztorys) and FOR-05-06 cost/margin read models to
    `MANAGER` and `ADMIN` only, denying every other role — including `CLIENT`, `FOREMAN`, `WORKER`, and
    `FINANCIER` — any access server-side; this DEVIATES from parent design §5 and is tightened here
    (Requirement 16, Requirement 15).
11. IF a `CLIENT` caller attempts to read any estimate, cost, margin, worker-rate, or estimate-internal
    unit-price data through any offer/negotiation endpoint, THEN THE System SHALL reject or omit it
    server-side so only the Client_Offer_Read_Model (offer-level fields) is ever returned
    (Requirement 15).

### Requirement 6: Discount approval and escalation threshold

**User Story:** As an executor, I want discount requests approved with an escalation threshold, so that
large discounts require higher authority.

#### Acceptance Criteria

1. WHEN the CLIENT submits a discount request (scope + target + justification, no figure —
   Requirement 4.1), THE System SHALL route it to a MANAGER, who decides the figure by issuing a
   `MANAGER_PROPOSAL` (a proposed value) or a `MANAGER_REJECT` (with a reasoned explanation)
   (Requirement 4).
2. WHERE a MANAGER'S PROPOSED discount value (the `MANAGER_PROPOSAL` value, percent and/or absolute)
   does not exceed the `Escalation_Threshold`, THE System SHALL allow the MANAGER to propose it and the
   CLIENT to accept it without ADMIN involvement.
3. WHERE a MANAGER'S PROPOSED discount value exceeds the `Escalation_Threshold`, THE System SHALL
   require ADMIN approval before that proposal can be proposed/accepted (before it can be issued as a
   `MANAGER_PROPOSAL` or accepted by the client into an applied `OfferDiscount`).
4. THE System SHALL treat the `Escalation_Threshold` as a **configurable value** (percent and/or
   absolute cap) applied to the MANAGER'S PROPOSED value, with a default of a **GLOBAL config value**
   and an optional **per-project override** (configurable).
5. IF a `MANAGER_PROPOSAL` value would exceed the `Escalation_Threshold` and no ADMIN approval is
   present, THEN THE System SHALL reject the proposal/acceptance with a localized error.
6. IF a `MANAGER_REJECT` is submitted without a non-empty reasoned `explanation`, THEN THE System SHALL
   reject the action with a localized error (Requirement 4.8) — every manager rejection, of a client's
   request or of a client's `CLIENT_DECLINE`, requires a reasoned explanation.

### Requirement 7: Approved offer version is a stable hand-off point

**User Story:** As a downstream consumer (FOR-05-14 freeze, FOR-05-09 contract), I want the approved
offer version to be stable and immutable, so that I can freeze prices and generate a contract from it
safely.

#### Acceptance Criteria

1. WHEN an offer reaches `APPROVED`, THE System SHALL mark the current `Offer_Revision` as the
   `Agreed_Offer_Version` and record which revision was approved.
2. WHILE an offer is `APPROVED`, THE System SHALL reject any modification to that agreed version's
   applied discounts and package selection; because the offer's prices are a live reference to the
   estimate (Live_Referenced_Pricing, Requirement 19) rather than frozen copies the offer stores, THE
   System SHALL make the agreed version stable NOT by the offer freezing its own price numbers but by
   (a) the estimate being service-locked once the project is `ACTIVE` (Estimate_Service_Lock /
   `DraftGate`, Requirement 20) and (b) the FOR-05-14 signing `OfferPriceSnapshot` taken at signing
   (delegated); THE System SHALL NOT claim the offer freezes its own per-line price copies.
3. WHEN an offer reaches `APPROVED`, THE System SHALL emit a state-change event/signal that FOR-05-14
   consumes to freeze prices (`OfferPriceSnapshot`, parent §4.3a/§4.6) and FOR-05-09 consumes to
   generate a contract; THE System (this spec) SHALL NOT itself perform the freeze or contract
   generation.
4. THE System SHALL expose the agreed offer version (its totals, selected package, and applied
   discounts) as a read model for those downstream consumers.

### Requirement 8: Offer / approval tab (in-workspace surface)

**User Story:** As a workspace user, I want an Offer / approval tab, so that I can prepare and negotiate
the offer inside the single project workspace.

#### Acceptance Criteria

1. THE System SHALL surface the **Offer / approval** tab ("Оферта / согласование") by **reusing the
   existing `pricing` workspace tab** (route `/projects/:projectId/pricing`, formerly gated by
   `PROJECT_PRICING`), re-gating it to `OFFERS` read and MAY renaming its label/i18n; THE System SHALL
   NOT add a separate parallel tab (Requirement 17).
2. THE Offer_Tab SHALL render the current offer (status, revision, selected package, totals), the
   applied discounts, and the negotiation thread (ordered rounds with initiator, kind, value,
   justification, and status).
3. WHERE the caller has `OFFERS` write (MANAGER/ADMIN/ESTIMATOR), THE Offer_Tab SHALL expose the
   executor actions (prepare, set package, add/edit/remove discounts, send, counter, withdraw) subject
   to the current status machine state; WHERE the caller is an ESTIMATOR, THE Offer_Tab SHALL NOT
   expose any offer-approval action (ESTIMATOR has `OFFERS READ`+`CREATE`+`UPDATE` but no `APPROVE`).
4. WHERE the caller is a CLIENT member, THE Offer_Tab SHALL expose the client actions (approve, reject,
   request discount, select package) subject to the current status.
5. THE System SHALL delegate the dedicated **client-facing portal** rendering of the offer to FOR-09;
   this tab is the in-workspace surface and reuses the same server actions.
6. THE Offer_Tab SHALL render the Client_Material_Selection_Surface for the offer's
   Package_Derived_Finishing_Selection: each finishing Placeholder slot SHALL be presented as
   CLIENT-selectable (choose a `Concrete_Material`), while chosen finishing materials, all construction
   materials, works, volumes, and prices SHALL be presented READ-ONLY, and each finishing material row
   (chosen product or Placeholder) SHALL carry a per-material discount-request affordance (Requirement
   12).
7. WHERE the caller is a MANAGER or ADMIN, THE Offer_Tab SHALL render the same
   Client_Material_Selection_Surface (finishing selection + per-material discount thread) so the
   executor sees the client's material selection and per-material negotiation.
8. WHILE an offer is in a terminal state, THE Offer_Tab SHALL present the offer read-only with its
   terminal status and full negotiation history.
9. WHERE the caller is a `CLIENT` member, THE Offer_Tab SHALL become client-visible ONLY per the
   client-visibility gate (Requirement 17) — WHILE the offer's Offer_Visibility_Status is `DRAFT` the
   CLIENT SHALL have no access to the tab's offer content (server-enforced, Requirement 5.9).
10. WHERE the caller is a `CLIENT` member, THE Offer_Tab SHALL render ONLY offer-level data from the
    Client_Offer_Read_Model (offer prices/totals/discounts/package prices/finishing product price) and
    SHALL NOT render any estimate unit price, FOR-05-06 cost/margin, or worker rate (Requirement 15).

### Requirement 9: Internationalization (PL/RU parity)

**User Story:** As a bilingual user, I want the offer, statuses, and all user-facing text localized, so
that every label is in my language with no raw keys.

#### Acceptance Criteria

1. THE System SHALL localize all user-facing text of the Offer_Tab — tab title, offer status labels,
   discount scope/kind labels, negotiation-round action labels, validation messages, and empty states —
   via i18n keys.
2. THE new i18n keys SHALL exist in BOTH `pl.json` and `ru.json` at strict key parity (identical key
   sets), with non-empty values.
3. THE System SHALL never surface a raw i18n key to the user on any Offer_Tab screen.

### Requirement 10: Correctness properties (for later property tests)

**User Story:** As a maintainer, I want the offer's arithmetic and state machine to satisfy stated
invariants, so that discounts and negotiation cannot produce impossible states.

#### Acceptance Criteria

1. FOR ALL discount configurations, THE System SHALL guarantee the Offer `totalNet` is `>= 0` (discount
   application never yields a negative total) — an invariant.
2. FOR ALL discounts, THE System SHALL guarantee each discount's `Effective_Discount` does not exceed
   its scope base — an invariant.
3. FOR ALL priced estimates and discount sets, THE System SHALL guarantee `Offer.totalNet =
   estimate.totalNet − Σ Effective_Discount(net)` — a metamorphic/model relationship.
4. FOR ALL offers in `APPROVED`, THE System SHALL guarantee the `Agreed_Offer_Version` is immutable
   (its totals/discounts/package do not change after approval) — an invariant.
5. FOR ALL sequences of offer actions, THE System SHALL guarantee the status machine performs no illegal
   transition (Requirement 3) — an invariant.
6. FOR ALL client material writes, THE System SHALL guarantee a CLIENT can only ever write a
   `Concrete_Material` choice to a **finishing** Placeholder line of the caller's **own** project's
   offer — an invariant (Requirement 11).
7. FOR ALL client concrete-finishing choices, THE System SHALL guarantee the choice never alters any
   work, volume, or construction material, changing only the affected finishing line's chosen
   `Concrete_Material` (and the derived price) — an invariant (Requirement 11).
8. FOR ALL offers in `APPROVED`, THE System SHALL guarantee the Package_Derived_Finishing_Selection of
   the `Agreed_Offer_Version` is immutable (no further client concrete choice or re-derivation) — an
   invariant (Requirement 7, Requirement 11).
9. FOR ALL notification reads and mutations, THE System SHALL guarantee a user can read, toggle
   read/unread, or delete only `Notification`s whose `recipient` is that same user — an ownership
   invariant enforced by the service layer using the acting user's id (`actingUserId`), not via
   project-scoping (Requirement 13).
10. FOR ALL users at any time, THE System SHALL guarantee the Bell unread badge count equals the number
    of that user's `Notification`s whose `read` state is unread — an invariant (Requirement 13).
11. FOR ALL notification emissions triggered by a negotiation transition, THE System SHALL guarantee a
    failed emission never rolls back or blocks the triggering domain transition (emission is a
    best-effort, log-only-on-failure side effect) — an invariant (Requirement 14).
12. FOR ALL responses served to a `CLIENT`, THE System SHALL guarantee the payload contains NO
    self-cost, cost, margin, worker-rate, or estimate-internal unit-price field — a **confidentiality
    invariant** on the Client_Offer_Read_Model (Requirement 15, Requirement 16).
13. FOR ALL offers whose Offer_Visibility_Status is `DRAFT`, THE System SHALL guarantee no `CLIENT`
    member can read or act on the offer (the client-visibility gate holds) — an invariant
    (Requirement 17, Requirement 5.9).
14. WHILE a project is `ProjectStatus` `ACTIVE`, THE System SHALL guarantee no estimate/offer
    price, material, or volume write succeeds at the service layer (`Estimate_Service_Lock` →
    `409 error.estimate.locked`) — an invariant (Requirement 20).
15. FOR ALL offers, THE System SHALL guarantee the offer references its estimate by id and the
    Client_Offer_Read_Model's client price equals the live-referenced estimate client-facing final price
    minus applied discounts — a model relationship that is NEVER a stored per-line copy and NEVER a
    cost/margin field (Requirement 19, Requirement 15).
16. FOR ALL sets of negotiation propositions and applied discounts on an offer, THE System SHALL
    guarantee that only the surviving (non-superseded) proposition/discount at the HIGHEST scope
    covering a line applies — a `CATEGORY` proposition/discount supersedes the `LINE` ones in its
    work-type group and a `GLOBAL` proposition/discount supersedes ALL `LINE` and `CATEGORY` ones — and
    that an accepted proposal's applied discount respects this scope-override hierarchy; the resulting
    totals are deterministic and independent of insertion order — an invariant (Requirement 1.4,
    Requirement 2.5, Requirement 4.9).
17. FOR ALL manager rejections, THE System SHALL guarantee a `MANAGER_REJECT` with a missing or blank
    `explanation` is rejected server-side and never recorded — an invariant (Requirement 4.8,
    Requirement 6.6).
18. FOR ALL client discount requests, THE System SHALL guarantee the request carries no value/kind (the
    figure is owned by the manager) and that only a `MANAGER_PROPOSAL` carries a discount value — an
    invariant (Requirement 4.1, Requirement 4.3).
19. FOR ALL notification reads and mutations, THE System SHALL guarantee own-recipient-only access is
    enforced by the service layer using `actingUserId` (any notification whose `recipient` !=
    `actingUserId` is rejected or omitted) — an invariant (Requirement 13.13, Requirement 10.9).

### Requirement 11: Client finishing-material selection on package change

**User Story:** As a client, I want the offer's finishing materials to follow the package I pick and to
choose concrete finishing products for the open slots, so that I decide the exact finishes while
everything else stays fixed.

#### Acceptance Criteria

1. WHEN a CLIENT project member with `OFFERS R(own)+approve` changes the offer's `selectedPackage`
   (START/COMFORT/PRESTIGE), THE System SHALL (re-)derive the offer's Package_Derived_Finishing_Selection
   from the chosen package, reusing the FOR-05-05 package finishing-material propagation
   ("applied_from_package").
2. WHEN the (re-)derived finishing selection changes the priced proposal, THE System SHALL bump the
   `Offer_Revision` consistently with the revision rule (Requirement 1.5) and keep the change within the
   current non-terminal offer revision.
3. WHERE a finishing material line is a Placeholder (no `Concrete_Material` chosen), THE System SHALL let
   the CLIENT choose a concrete finishing product for that line — a client-invokable form of the
   FOR-05-05 `Choose_Concrete` write path, restricted to the finishing `Branch`, Placeholder slots, and
   the caller's own project's offer.
4. WHERE a finishing material line already has a chosen `Concrete_Material`, AND FOR ALL construction
   materials, works, volumes, and prices, THE System SHALL present them READ-ONLY to the CLIENT and
   SHALL reject any client write to them server-side.
5. THE System SHALL server-enforce that a CLIENT may choose a `Concrete_Material` only for finishing
   Placeholder lines of the caller's own project's offer, and SHALL reject any other client material
   write server-side (not merely disable it in the UI).
6. WHEN a client concrete finishing choice changes the offer's priced proposal, THE System SHALL
   recompute the Offer totals per Requirement 1.3 and bump the `Offer_Revision` consistently with the
   negotiation/revision rules (Requirement 4.5).
7. WHILE the offer is in a non-terminal negotiable state (`SENT`/`CHANGES_REQUESTED`/`COUNTERED`) AND the
   estimate is `DRAFT`, THE System SHALL permit client finishing-material selection; IF the offer is in
   a terminal state OR the estimate is not `DRAFT`, THEN THE System SHALL reject client material writes
   server-side and freeze the finishing selection (ties to Requirement 7 immutable agreed version).
8. THE System SHALL expose the client finishing-material selection through the same server actions the
   FOR-09 client portal reuses (the Client_Material_Selection_Surface); FOR-09 owns the portal rendering
   (Non-Goals).

### Requirement 12: Per-material (LINE-scoped) client discount request

**User Story:** As a client, I want to request a discount on a specific finishing material, so that I
can negotiate the price of individual finishes line by line.

#### Acceptance Criteria

1. WHERE the CLIENT views any finishing material (a chosen `Concrete_Material` or a Placeholder slot),
   THE System SHALL let the CLIENT open a Per_Material_Discount_Request as a `LINE`-scoped
   `DISCOUNT_REQUEST` `NegotiationRound` (Requirement 4) with `scope = LINE`, `targetId` = the estimate
   line / material line id, a free-text `justification`, and an optional `clientComment`, WITHOUT any
   figure — the MANAGER decides the value via a `MANAGER_PROPOSAL` (kind + value) or refuses via a
   `MANAGER_REJECT` (reasoned explanation), per Requirement 4.
2. THE System SHALL treat a Per_Material_Discount_Request as the same `LINE`-scoped round defined in
   Requirement 4 and SHALL NOT introduce a duplicate discount mechanism; the per-material request IS a
   `LINE`-scoped negotiation round.
3. THE System SHALL route and approve a Per_Material_Discount_Request through the same discount-approval
   and `Escalation_Threshold` flow as any other round (Requirement 6).
4. WHEN the CLIENT issues a `CLIENT_ACCEPT` of the manager's `MANAGER_PROPOSAL` for a
   Per_Material_Discount_Request, THE System SHALL materialize it into applied `OfferDiscount`(s) and
   bump the `Offer_Revision` per Requirement 4.5.
5. WHILE the offer is in a terminal state, THE System SHALL reject any new Per_Material_Discount_Request
   per Requirement 3.9.

### Requirement 13: Generic notification service and in-app bell (base implementation)

**User Story:** As any platform user, I want a reusable in-app notification service with a top-bar bell,
so that flows across specs (offer negotiation, contracts, and later features) can notify me and I can
read, manage, and act on my notifications in one place.

#### Acceptance Criteria

1. THE Notification_Service SHALL persist each `Notification` in the DB per recipient user, with fields:
   `recipient` (FK user), `type` (a Notification_Type i18n key), `body` (text), `deepLink` (nullable UI
   target), `read` (read/unread state), `createdAt`, and the standard audit fields.
2. THE Notification_Service SHALL expose a generic server API to create a `Notification` for a given
   recipient user (the in-app base implementation), usable by any consuming flow, decoupled from the
   Offer domain.
3. THE System SHALL render a Bell icon in the top bar positioned to the right, immediately BEFORE the
   language button, displaying a badge with the count of the caller's UNREAD `Notification`s.
4. WHEN a user opens the Bell, THE System SHALL present a compact popup list of that user's
   `Notification`s sorted by `createdAt` DESCENDING.
5. THE System SHALL present exactly two actions on each notification row: toggle read/unread (mark
   read ↔ unread) and delete.
6. WHEN a user toggles a notification's read/unread state, THE System SHALL persist the new `read` state
   and update the Bell unread badge count accordingly.
7. WHEN a user deletes a notification, THE System SHALL remove that notification from the user's list and
   update the Bell unread badge count accordingly.
8. THE System SHALL return and display to a user ONLY `Notification`s whose `recipient` is that same
   user, enforced server-side (own-recipient filter), not merely in the UI.
9. IF a user attempts to read, toggle, or delete a `Notification` whose `recipient` is a different user,
   THEN THE System SHALL reject the operation server-side with a localized error.
10. WHERE a `Notification` has a `deepLink`, THE System SHALL render its message as CLICKABLE and
    navigate the user to that UI target on click; WHERE a `Notification` has no `deepLink`, THE System
    SHALL render its message as non-interactive (no navigation action).
11. THE System SHALL resolve each `Notification` `type` to a localized title/label via its i18n key, and
    SHALL localize every Bell UI string (badge, empty state, row actions, action labels) via i18n keys.
12. THE new i18n keys for every Notification_Type and every Bell UI string SHALL exist in BOTH `pl.json`
    and `ru.json` at strict key parity (identical key sets) with non-empty values, and THE System SHALL
    never surface a raw i18n key to the user.
13. THE System SHALL gate notification operations behind a project-independent ABAC resource
    `NOTIFICATIONS` that ALL roles possess, granting `READ`, `UPDATE` (read-state toggle), and `DELETE`
    operations to EVERY role, seeded per `entity-creation-rules` (seed resource + ADMIN grant +
    role-matrix rows for every role + class-level `@PermissionResource("NOTIFICATIONS")` + startup
    annotation-completeness); there SHALL be NO `CREATE` grant for end users (notifications are created
    by the generic Notification_Service on behalf of flows), so the user-facing operations are
    READ/UPDATE/DELETE only. THE SERVICE LAYER SHALL GUARANTEE own-recipient-only access — a user may
    read, update, or delete ONLY `Notification`s whose `recipient` is the acting user — enforced using
    the acting user's id (`actingUserId`), NOT via project-scoping: the service SHALL reject or omit any
    notification whose `recipient` != `actingUserId` (Requirement 10 ownership invariant, reaffirmed as
    a service-layer guarantee via `actingUserId`).
14. THE System SHALL treat the in-app (DB) `Notification` as the base implementation and the generic
    create API as the extension point on which future delivery channels and the
    Delegating_Notification_Service build (Non-Goals), without requiring redesign of the
    `Notification` domain.

### Requirement 14: Offer negotiation notifications (concrete consumers of the notification service)

**User Story:** As a project manager and as a client, I want to be notified when the other side acts on
the offer negotiation, so that I can respond promptly without polling the Offer_Tab.

#### Acceptance Criteria

1. WHEN a CLIENT submits a discount request or opens a negotiation round on the offer (Requirement 4 /
   Requirement 12), THE System SHALL emit, via the Notification_Service (Requirement 13), a
   `Notification` to the project's MANAGER member with an offer-negotiation Notification_Type (i18n key)
   and a `deepLink` to the Offer_Tab of that project.
2. WHEN a MANAGER issues a `MANAGER_PROPOSAL` on a client's discount request/round (Requirement 6 /
   Requirement 4), THE System SHALL emit, via the Notification_Service, a `Notification` to the
   requesting CLIENT with a proposal-variant Notification_Type (i18n key) and a `deepLink` to the
   Offer_Tab.
3. WHEN a MANAGER issues a `MANAGER_REJECT` on a client's discount request/round (Requirement 6 /
   Requirement 4), THE System SHALL emit, via the Notification_Service, a `Notification` to the
   requesting CLIENT with a rejected-variant Notification_Type (i18n key) and a `deepLink` to the
   Offer_Tab.
4. THE System SHALL emit these notifications as best-effort side effects at the corresponding
   negotiation state changes, tied to the state-change event of Requirement 7.3.
5. IF emitting a negotiation notification fails, THEN THE System SHALL log the failure and SHALL NOT roll
   back or block the triggering negotiation transition (log-only on failure, mirroring the future
   asynchronous fan-out behavior).

### Requirement 15: Client sees only offer-level prices; estimate and costs are never exposed to the client

**User Story:** As the business owner, I want the client to see only offer-level prices and never the
estimate, costs, or margins, so that our internal cost structure stays confidential no matter how the
client interacts with the system.

#### Acceptance Criteria

1. THE System SHALL return offer data to a `CLIENT` ONLY through a dedicated Client_Offer_Read_Model
   that contains ONLY offer-level fields: the offer's client-facing net and gross totals, per-line and
   per-category offer prices, package prices, applied discounts, and the finishing `Type_Price_Range` /
   chosen finishing product price.
2. THE System SHALL guarantee the Client_Offer_Read_Model contains NO self-cost, cost (себестоимость),
   margin, worker-rate, worker-type-tier, material `cost_net`, per-branch or per-tier margin, or any
   FOR-05-06 cost/margin field, and NO estimate (kosztorys) unit price.
3. THE System SHALL NEVER expose the estimate (kosztorys) itself to a `CLIENT` through any endpoint —
   a CLIENT has no access to the estimate at all.
4. THE System SHALL enforce this confidentiality **server-side**: for ALL offer read models served to a
   `CLIENT`, no field derived from cost, margin, or estimate-internal pricing is present, so it is
   impossible for such a field to reach a client through any endpoint — it SHALL NOT rely on the UI to
   hide fields.
5. IF any offer/negotiation endpoint would otherwise include a cost, margin, worker-rate, or
   estimate-internal price field in a response to a `CLIENT`, THEN THE System SHALL omit that field from
   the Client_Offer_Read_Model before the response is served.
6. THE System SHALL treat this offer-level-only exposure as a **confidentiality invariant**
   (Requirement 10.12): FOR ALL responses served to a `CLIENT`, the payload contains no self-cost,
   cost, margin, worker-rate, or estimate-internal price field.

### Requirement 16: Estimate and offer access is restricted by role

**User Story:** As the business owner, I want the estimate and offers locked to specific roles, so that
only the people who should see internal pricing or negotiate the offer can reach them.

#### Acceptance Criteria

1. THE System SHALL restrict access to the ESTIMATE (kosztorys) to `MANAGER`, `ADMIN`, and `ESTIMATOR`
   only (`ESTIMATOR` holds `ESTIMATE` `READ`+`CREATE`+`UPDATE`); and SHALL restrict the FOR-05-06
   margins/cost model (costs, tier costs, worker-type tiers, material `cost_net`, per-branch/per-tier
   margins) to `MANAGER` and `ADMIN` only (criterion 16.4) — the ESTIMATOR's ESTIMATE grant does NOT
   itself grant the FOR-05-06 cost/margin read models beyond what the ESTIMATE grant already conveys.
2. IF a caller whose role is `FOREMAN`, `WORKER`, `FINANCIER`, `CLIENT`, or any role other than
   `MANAGER` / `ADMIN` / `ESTIMATOR` attempts to access the estimate, THEN THE System SHALL reject the
   request server-side with a localized error; AND IF a caller whose role is other than `MANAGER` /
   `ADMIN` attempts to access any FOR-05-06 cost/margin data, THEN THE System SHALL reject the request
   server-side with a localized error (ESTIMATOR is NOT on the cost/margin allow-list).
3. THE System SHALL restrict `OFFERS` access to `CLIENT` (their own project, subject to the
   client-visibility gate of Requirement 17), `MANAGER`, `ADMIN`, and `ESTIMATOR` only; no other role
   has any offer access. `ESTIMATOR` holds `OFFERS` `READ`+`CREATE`+`UPDATE` (NO `APPROVE`,
   no `DELETE`), so an ESTIMATOR may prepare/read/update offers and discounts but may not approve.
4. THE System SHALL expose all FOR-05-06 margin/cost read models to `ADMIN` and `MANAGER` only,
   reaffirming that costs and margins never reach a `CLIENT` and are NOT granted to `ESTIMATOR` beyond
   what already flows from the ESTIMATE grant — costs/margins remain `ADMIN`/`MANAGER`-only
   (Requirement 15).
5. THE System SHALL record that this role model **DEVIATES from parent design §5** — which granted
   `FOREMAN` / `WORKER` / `FINANCIER` `R(own)` on ESTIMATE and defined a broader `OFFERS` matrix — and
   SHALL tighten those grants to the model stated in this requirement; the deviation is intentional and
   documented here.
6. THE System SHALL seed and enforce these tightened grants through the ABAC matrix per
   `entity-creation-rules` (resource seed + role-matrix rows + class-level `@PermissionResource` +
   startup annotation-completeness), so the restriction is server-enforced, not UI-only.
7. THE System SHALL seed a NEW system role `ESTIMATOR` (code `ESTIMATOR`, Russian label "Сметчик",
   Polish label "Kosztorysant") whose grant set is **exactly the FOREMAN resource grant set** (every
   resource/operation FOREMAN holds today — e.g. `READ` on `ROOM_TYPES`, `WORK_CATEGORIES`,
   `MEASUREMENT_UNITS`, `MATERIAL_SELLERS`, `MATERIALS`, `OFFER_PACKAGES`, `VAT_RATES`, `WORK_CATALOG`,
   `WORKER_TYPES`, `DELIVERY_STATUSES`, `MATERIALS_CONSTRUCTION`, and the rest; `READ`+`UPDATE` on
   `ROOMS`; `READ` on `PROJECT_MEMBERS`) PLUS the two extra grants FOREMAN lacks: `OFFERS`
   `READ`+`CREATE`+`UPDATE` (NO `APPROVE`, no `DELETE`) and `ESTIMATE` `READ`+`CREATE`+`UPDATE`. THE
   System SHALL seed the role idempotently (insert the role guarded by an `onFail="MARK_RAN"`
   precondition on `roles.code = 'ESTIMATOR'`), then seed the `role_resources` + `role_resource_operations`
   grant rows (each self-guarded by a `NOT EXISTS` precondition), in a new Liquibase changeset
   registered **LAST** in `changelog.xml`, per `entity-creation-rules` / the existing role-seed pattern.
8. THE System SHALL seed the `ESTIMATOR` grants as fresh, intentional grants that are NOT subject to the
   changeset-135 (`retighten-estimate-and-offers-grants`) removal: changeset 135 archives and removes
   only the `FOREMAN` / `WORKER` / `FINANCIER` grants on `ESTIMATE` / `OFFERS`, so because `ESTIMATOR`
   is a brand-new role (not in that set) and is seeded in a later changeset (after 135), its ESTIMATE
   and OFFERS grants are retained and intentional — the retighten SHALL NOT catch or remove them.

### Requirement 17: Reuse the pricing tab as the offer surface, manager-gated client visibility, and offer visibility status

**User Story:** As a manager, I want to control when the client can see the offer and to reuse the
existing pricing tab as the offer surface, so that the client only ever sees an offer I have explicitly
released and I can track how close it is to being agreed.

#### Acceptance Criteria

1. THE System SHALL repurpose the EXISTING `pricing` workspace tab (route `/projects/:projectId/pricing`,
   labelKey `workspace.tab.pricing`, currently a PlaceholderTab gated by `PROJECT_PRICING` read) as the
   client-facing Offer surface (the Offer_Tab surface), and SHALL NOT introduce a brand-new parallel
   tab.
2. THE System SHALL change the reused tab's ABAC gate from `PROJECT_PRICING` to `OFFERS` (the exact
   final tab key/label rename to "Оферта / согласование" being a design detail), so a `CLIENT` reaches
   the offer through this tab only under the `OFFERS` grant plus the client-visibility gate below.
3. THE System SHALL extend the offer with an Offer_Visibility_Status of exactly `DRAFT` (черновик),
   `ON_APPROVAL` (на согласовании), or `APPROVED` (согласовано, ready to sign the contract), controlled
   by the manager.
4. THE System SHALL map the Offer_Visibility_Status onto the Requirement 3 `Offer_Status` machine
   without contradicting it: `DRAFT` corresponds to `Offer_Status` `DRAFT` (not client-visible);
   `ON_APPROVAL` corresponds to the client-visible negotiable states `SENT` / `CHANGES_REQUESTED` /
   `COUNTERED`; `APPROVED` corresponds to `Offer_Status` `APPROVED`.
5. WHILE the Offer_Visibility_Status is `DRAFT`, THE System SHALL deny every `CLIENT` member any access
   to the offer server-side (Requirement 5.9); the offer becomes visible to the CLIENT ONLY AFTER the
   manager EXPLICITLY moves it out of `DRAFT` into `ON_APPROVAL` ("send to client").
6. WHEN the manager sends the offer to the client, THE System SHALL move the Offer_Visibility_Status to
   `ON_APPROVAL` consistently with the Requirement 3 `DRAFT → SENT` transition, making the offer
   client-visible.
7. WHILE the Offer_Visibility_Status is `ON_APPROVAL`, THE System SHALL maintain and expose an
   Offer_Readiness metric measuring how close the offer is to `APPROVED`, expressed as the percentage of
   (a) unresolved negotiation proposals (open/unaccepted rounds, Requirement 4) and (b) unselected
   finishing materials in the package (finishing Placeholder slots not yet filled, reusing
   Materials_Fulfilment / the Package_Derived_Finishing_Selection), optionally combined into a single
   readiness percentage.
8. WHERE the workspace ReadinessWidget concept (FOR-05-01) is present, THE System MAY surface the
   Offer_Readiness through it; regardless, the offer-approval readiness is owned by this spec.
9. THE System SHALL keep the Offer_Visibility_Status consistent with the Requirement 3 machine at all
   times and SHALL reject any visibility change that would contradict a legal `Offer_Status` transition
   (Requirement 3.8).

### Requirement 18: Client finishing-material selection is staged on the UI with undo/redo

**User Story:** As a client, I want to try out finishing choices and package changes with undo/redo
before saving, so that I can experiment freely and only commit when I am ready.

#### Acceptance Criteria

1. WHERE a `CLIENT` selects finishing materials (choose a concrete finishing product for a Placeholder
   slot, or change the commercial package), THE System SHALL stage those changes client-side in a
   Client_Staging store WITHOUT persisting them to the server.
2. THE System SHALL let the CLIENT UNDO and REDO across staged changes while nothing is persisted.
3. WHEN the CLIENT discards the staged changes, THE System SHALL clear the Client_Staging store and
   restore the last committed selection with no server write.
4. WHEN the CLIENT explicitly saves/submits the staged changes, THE System SHALL commit them by invoking
   the existing Requirement 11 write path (the `Choose_Concrete` finishing-Placeholder write and/or the
   `selectPackage` write), and only then persist the selection.
5. THE System SHALL keep the server authoritative on commit: it SHALL re-validate the committed staged
   changes server-side per Requirement 11 (finishing Placeholder only, own project, non-terminal
   negotiable state) and SHALL reject any invalid staged change even if it was allowed in the UI staging.
6. THE System SHALL treat Client_Staging + undo/redo as a UI behavior only (mirroring the FOR-05-05b
   `reserveDraftStore` staging pattern conceptually); it SHALL NOT introduce a new server-side draft or
   a second write path beyond the Requirement 11 commit.

### Requirement 19: Offer prices are a live reference to estimate prices (offer = referenced estimate final price + discount; DTO never embeds the estimate)

**User Story:** As the business owner, I want the offer's prices to track the estimate's client-facing
final prices live and add only discounts on top, so that estimate changes flow into the offer
automatically and the offer never copies or leaks internal cost data.

#### Acceptance Criteria

1. THE System SHALL treat the Offer's prices as a **live reference** (Live_Referenced_Pricing) to the
   estimate's client-facing final prices (the estimate's own frozen `unitPrice` / value-net and package
   prices, NOT the FOR-05-06 self-cost / margins) and SHALL NOT store a frozen copy of those prices on
   the Offer; the offer's effective price SHALL equal the referenced estimate client-facing final price
   minus the applied offer discounts (Requirement 2).
2. WHEN new materials are bound, the `appliedPackageCode` is changed, or volumes are changed on the
   ESTIMATE (WHILE the estimate is still writable), THE System SHALL reflect the resulting change to the
   estimate's client-facing final prices in the offer automatically through the `Offer.estimate`
   reference, WITHOUT re-copying prices onto the offer.
3. THE System SHALL define the Offer DTO so that it NEVER embeds or exposes the Estimate DTO: the Offer
   DTO SHALL reference the estimate ONLY by id and SHALL map ONLY the client-facing FINAL price from the
   estimate into the offer read model (the price the client pays), and SHALL NEVER map the self-cost,
   cost, margin, or any FOR-05-06 cost/margin field (strengthening the Client_Offer_Read_Model of
   Requirement 15 and the confidentiality guarantee of Requirement 10).
4. THE System SHALL compute the Offer totals over the live-referenced estimate prices plus the applied
   discounts per Requirement 1.3, and SHALL NOT persist the offer's own per-line price copies; the
   offer references the estimate prices and applies discounts on top (cross-reference Requirement 1,
   Requirement 2, Requirement 7, Requirement 15).

### Requirement 20: Approved offer gates the project to ACTIVE; the estimate is service-locked once ACTIVE; further changes go through amendments

**User Story:** As the business owner, I want an approved offer to make the project eligible to go
active and the estimate to be locked once the project is active, so that agreed prices stay stable and
any later change is a controlled amendment rather than an ad-hoc edit.

#### Acceptance Criteria

1. WHEN the offer is fully `APPROVED` (agreed), THE System SHALL make the project ELIGIBLE to move to
   `ProjectStatus` `ACTIVE` (in-work); THE System (this spec) SHALL provide only the `APPROVED`
   precondition and SHALL NOT itself perform the `DRAFT → ACTIVE` transition — the actual transition
   happens only AFTER the contract is signed and is owned by FOR-05-09 (parent §4.3
   `APPROVED → ACTIVE`).
2. WHILE the project is `ProjectStatus` `ACTIVE` (in-work), THE System SHALL reject all estimate writes
   at the SERVICE level through the existing Estimate_Service_Lock (`DraftGate` / `EstimateStatus` →
   `409 error.estimate.locked`): no changes to works, volumes, materials, prices, package, or discounts
   on the estimate/offer SHALL succeed; THE System SHALL enforce this server-side, not only in the UI.
3. WHILE the project is `ProjectStatus` `ACTIVE`, THE System SHALL require that any change to prices,
   materials, or volumes go through AMENDMENTS (owned by FOR-06) and SHALL NOT allow such changes
   through direct estimate/offer edits (amendments themselves are a Non-Goal here / FOR-06).
4. WHILE the project is `ACTIVE`, THE System SHALL keep the referenced offer prices effectively stable
   without the offer storing copies, BECAUSE the offer holds a live reference to the estimate
   (Requirement 19) and the estimate is service-locked (criterion 2); THE System SHALL rely on this plus
   the FOR-05-14 signing `OfferPriceSnapshot` for post-activation stability (reconciling with
   Requirement 7), NOT on the offer freezing its own price copies.
5. FOR ALL projects that are `ACTIVE`, THE System SHALL guarantee no estimate/offer price, material, or
   volume write succeeds at the service layer — an invariant (Requirement 10.14); AND THE System SHALL
   guarantee the offer always references the estimate by id and the offer read model's client price
   equals the live estimate client-facing final price minus applied discounts, never a stored copy and
   never a cost/margin (Requirement 10.15, Requirement 19).

### Requirement 21: "Prepare offer" action on the Estimate screen

**User Story:** As an executor (MANAGER/ADMIN/ESTIMATOR), I want a "Prepare offer" action directly on
the project's Estimate screen, so that I can start an offer from the estimate I am working on without
first navigating to the Offer tab and regardless of whether the estimate is priced.

#### Acceptance Criteria

1. WHERE the caller is a `MANAGER`, `ADMIN`, or `ESTIMATOR` (an executor with `OFFERS CREATE`), THE
   System SHALL present a "Prepare offer" action on the project's Estimate screen; WHERE the caller is
   any other role, THE System SHALL NOT present the action.
2. THE System SHALL keep the "Prepare offer" action **always enabled** for an executor regardless of
   the estimate's status (no `PRICED` gate on the action), consistent with Requirement 1.
3. WHEN an executor activates the "Prepare offer" action, THE System SHALL call
   `POST /api/offers/project/{projectId}/prepare` for the current project.
4. WHEN the prepare call succeeds, THE System SHALL navigate the caller into the Offer / approval tab
   (the Offer_Tab, Requirement 8 / Requirement 17) of the current project.
5. IF the prepare call fails (for example because the project already has a non-terminal offer per
   Requirement 1.4), THEN THE System SHALL surface the error as a localized toast and SHALL NOT
   navigate away from the Estimate screen.
6. THE System SHALL keep the EXISTING prepare entry point in the Offer_Tab (Requirement 8.3) unchanged;
   the Estimate-screen action is an ADDITIONAL entry point to the same server operation, not a
   replacement.
7. THE System SHALL localize the "Prepare offer" action label and its error-toast text via i18n keys
   present in BOTH `pl.json` and `ru.json` at strict key parity with non-empty values, and SHALL never
   surface a raw i18n key (Requirement 9).

## Non-Goals

- **Client-facing portal UI** — the dedicated client portal rendering of the offer AND of the client
  finishing-material selection surface is owned by **FOR-09** (parent §2.1). This spec exposes the
  reusable server actions + the in-workspace Offer_Tab only; FOR-09 is the portal owner that reuses the
  same Client_Material_Selection_Surface server actions.
- **Concrete-material catalog and the kosztorys `chooseConcrete` write path** — owned by **FOR-05-05**
  (bill-of-materials). This spec does NOT add a new catalog or a new estimate write path; it adds only
  the CLIENT-scoped, offer-stage, finishing-Placeholder-only invocation of the existing FOR-05-05
  `Choose_Concrete` write path plus its gating (Requirement 11). The `Materials_Fulfilment` ratio and
  the package finishing-material propagation ("applied_from_package") likewise remain FOR-05-05's.
- **Price freeze / `OfferPriceSnapshot` / `OFFER_BASE` seeding** — owned by **FOR-05-14** (parent
  §4.3a/§4.6). This spec only triggers the `APPROVED` transition and exposes the agreed version.
- **Contract generation and e-signing** — owned by **FOR-05-09** (contracts-esign). This spec ends at a
  stable `APPROVED` offer.
- **Notification delivery channels beyond in-app** — email, SMS, and Telegram notification services to
  the same user are **future work**, out of scope now. This spec delivers only the **in-app (DB) base**
  `Notification` + Bell (Requirement 13) and the two offer negotiation triggers (Requirement 14). The
  `Notification_Service` generic create API is designed as the extension point these channels plug into
  later without redesign.
- **Delegating_Notification_Service (asynchronous fan-out)** — a future service that iterates all
  available notification services (in-app, email, SMS, Telegram) and ASYNCHRONOUSLY attempts delivery
  through each, logging (only) on error, is **out of scope now**. This spec's log-only-on-failure,
  best-effort emission (Requirement 14.5) mirrors that future behavior so the fan-out slots in later
  without redesign.
- **Further notification types for later flows** — additional Notification_Types for flows built later
  (e.g. manager → client request to sign the contract, client-signed-contract → manager) are added as
  those flows are built (FOR-05-09, FOR-06) and are out of scope of this spec, which encodes only the
  offer-negotiation types (Requirement 14).
- **Amendments** (post-activation changes to prices/materials/volumes/dates) — owned by **FOR-06**
  (project in progress). Once the project is `ACTIVE` the estimate is service-locked and every
  price/material/volume change goes through FOR-06 amendments, NOT through direct estimate/offer edits
  (Requirement 20); this spec is DRAFT-stage offer preparation only.
- **The `APPROVED → ACTIVE` (contract-signed) project transition and contract signing** — driven by
  **FOR-05-09**, not here. This spec only provides the `APPROVED` precondition that makes the project
  eligible for `ACTIVE` (Requirement 20.1); the actual `DRAFT/APPROVED → ACTIVE` transition happens
  only after the contract is signed.
- **The signing `OfferPriceSnapshot`** — taken at contract signing and owned by **FOR-05-14**. Combined
  with the estimate service-lock (Requirement 20) it is what stabilizes the agreed offer prices
  post-activation; this spec's offer prices remain a live reference (Requirement 19) and do NOT freeze
  their own copies.
- **Exposing costs/margins or the estimate to the client** — the FOR-05-06 cost/margin surfaces
  (base cost, tier costs, worker-type tiers, material `cost_net`, per-branch/per-tier margins) stay
  **ADMIN/MANAGER-only** and NEVER reach a `CLIENT`, and the ESTIMATE (kosztorys) stays
  **MANAGER/ADMIN-only** (Requirement 15, Requirement 16). This spec does NOT build any client-facing
  cost, margin, or estimate view — the client sees only the offer-level Client_Offer_Read_Model.
