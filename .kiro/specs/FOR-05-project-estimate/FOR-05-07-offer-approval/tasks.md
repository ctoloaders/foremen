# Implementation Plan: FOR-05-07 — Offer preparation and client negotiation

## Overview

This plan implements the design in incremental, integrated steps that build on each other and end
with wiring the reused offer tab and the notification bell together. Every step references specific
requirements and every correctness property from the design becomes a property-based test placed
next to the code it validates.

It spans **two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Backend + migrations + spec** (root repo): the new `offers` / `offer_discounts` /
  `offer_negotiation_rounds` / `notifications` / `offer_project_settings` schema, the `OFFERS` +
  `NOTIFICATIONS` ABAC seeds, the tightened ESTIMATE/OFFERS grant retighten changeset, the pure
  collaborators (`DiscountResolver`, `OfferTotalsCalculator`, `OfferStatusMachine`,
  `OfferVisibilityResolver`, `OfferReadinessCalculator`, `EscalationPolicy`), the `OfferService`,
  `OfferDiscountService`, `NegotiationService`, `NotificationService`, the client
  finishing-selection controller (which reuses FOR-05-05 `EstimateAssignmentService.chooseConcrete`),
  and the read-model assemblers. Backend/spec commits are made **from the root repo**.
- **Frontend** (`foremen-frontend/`, nested repo): the `features/project-offer/*` module (the reused
  `pricing` tab's real `OfferTab`, negotiation thread, client material-selection surface, the
  `offerStagingStore` undo/redo), the `features/notifications/*` module (`NotificationBell` in
  `TopBar`), the `workspaceTabs.ts` re-gate, and the `offer.*` / `notifications.*` locale keys.
  Frontend commits are made **from inside `foremen-frontend/`**.

This feature spans both repos, so it needs **two commits — one per repo** (backend + migrations +
spec in the root; all frontend source in `foremen-frontend/`).

**Entity-creation checklist note** (per `.kiro/steering/entity-creation-rules.md`). This spec
introduces **two new managed ABAC resources**, `OFFERS` and `NOTIFICATIONS`, each wired end-to-end:
(1) a resource-row seed changeset registered **last** in `changelog.xml`; (2) the ADMIN grant + role
matrix in the same changeset; (3) class-level `@PermissionResource("OFFERS")` /
`@PermissionResource("NOTIFICATIONS")` on the concrete controllers, combined with per-handler
`@PermissionOperation` / `@RequiresPermission` so `PermissionAnnotationValidator` classifies each
controller COMPLETE at startup; (4) `OfferService` is a `ProjectScopedService` with
`getProjectIdPath()` → `"project.id"` (its discount/round children resolve through
`offer.project.id`), while `NotificationService` is **NOT** project-scoped — ownership is enforced by
the acting user's id (`actingUserId`). The `OFFERS` seed also adds a custom `APPROVE` operation row
(the operations seed `003` currently defines only CREATE/READ/UPDATE/DELETE) so the client
"approve" sub-action is a first-class `(OFFERS, APPROVE)` grant. `NOTIFICATIONS` grants
READ/UPDATE/DELETE to **every** role and **no** CREATE to end users.

**Migrations.** New Liquibase changesets begin at `128` (current changelog head is `127`; if
FOR-05-06 lands further changesets first, use the next free `NNN`). Each follows the
`NOT tableExists` / `NOT columnExists` / per-row `NOT EXISTS` / `onFail="MARK_RAN"` idempotency and
the archive-before-delete convention (for the grant retighten), and is appended **last** in
`changelog.xml`.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (it takes
~20 minutes). Run only the affected test classes with `--tests`, redirect to a temp log, and read the
JUnit result XML (`foremen-backend/build/test-results/test/TEST-<fqcn>.xml`). Use
`compileJava` / `compileTestJava` for compile-only checks and `getDiagnostics` for fast per-file
checks. Run the full suite only if the user explicitly asks.

**Languages.** Backend in Java (Spring Boot / JPA / MapStruct / Liquibase / jqwik). Frontend in
TypeScript/React (Vite, react-hook-form + zod, TanStack Query, zustand, i18next); property tests in
fast-check + Vitest.

## Tasks

- [x] 1. Backend: schema additions (idempotent Liquibase changesets, registered last)
  - [x] 1.1 Create the `offers` changeset (`128`)
    - Create `foremen-backend/database_files/changesets/128-create-offers.xml` creating `offers` per the Data Models table: `project` FK (`projects`, NOT NULL), `estimate` FK (`estimates`, NOT NULL, **no per-line price copy**), `selected_package` FK (`offer_packages`, nullable), `revision` INT NOT NULL DEFAULT 1, `status` VARCHAR(32) NOT NULL, `approved_revision` INT nullable, `total_net`/`total_vat`/`total_gross` NUMERIC(14,2), plus `BaseEntity` columns.
    - Add the partial unique index enforcing **at most one non-terminal offer per project**: `UNIQUE (project_id) WHERE status NOT IN ('APPROVED','REJECTED','WITHDRAWN')`.
    - Guard with `NOT tableExists` + `onFail="MARK_RAN"`; register **last** in `changelog.xml`.
    - _Requirements: 1.1, 1.3, 1.4, 1.5, 3.1, 7.1, 19.4_
  - [x] 1.2 Create the `offer_discounts` changeset (`129`)
    - Create `129-create-offer-discounts.xml`: `offer` FK (`offers`, NOT NULL, ON DELETE CASCADE), `scope` VARCHAR(16) NOT NULL, `target_id` BIGINT nullable, `kind` VARCHAR(16) NOT NULL, `value` NUMERIC(14,4) NOT NULL with CHECK `value >= 0`, `source_round` FK (`offer_negotiation_rounds`, nullable), audit cols.
    - Idempotent (`NOT tableExists` + `onFail="MARK_RAN"`); register **last** after `128`.
    - _Requirements: 2.1, 2.2, 4.5_
  - [x] 1.3 Create the `offer_negotiation_rounds` changeset (`130`)
    - Create `130-create-offer-negotiation-rounds.xml` per the Data Models table: `offer` FK NOT NULL, `offer_revision` INT NOT NULL, `round_no` INT NOT NULL, `initiator_role` VARCHAR(16) NOT NULL, `kind` VARCHAR(24) NOT NULL, `scope`/`target_id` nullable, `value_kind`/`value` nullable, `justification`/`explanation`/`client_comment` TEXT nullable, `status` VARCHAR(16) NOT NULL, `admin_approved` BOOLEAN NOT NULL DEFAULT false, audit cols.
    - Add the figure-ownership DB CHECK: `value` and `value_kind` are non-null **iff** `kind = 'MANAGER_PROPOSAL'`; and `explanation` is non-blank when `kind = 'MANAGER_REJECT'`.
    - Idempotent; register **last** after `129`.
    - _Requirements: 4.1, 4.2, 4.3, 4.6, 4.8, 10.18_
  - [x] 1.4 Create the `notifications` changeset (`131`)
    - Create `131-create-notifications.xml`: `recipient` FK (`users`, NOT NULL), `type` VARCHAR(128) NOT NULL, `body` TEXT, `deep_link` VARCHAR(512) nullable, `read` BOOLEAN NOT NULL DEFAULT false, audit cols. Add indexes `(recipient_id, read)` and `(recipient_id, created_at DESC)`. **Not** project-scoped.
    - Idempotent; register **last** after `130`.
    - _Requirements: 13.1_
  - [x] 1.5 Create the `offer_project_settings` changeset (`132`)
    - Create `132-create-offer-project-settings.xml`: `project` FK (`projects`, NOT NULL, UNIQUE), `escalation_percent_cap` NUMERIC(6,4) nullable, `escalation_absolute_cap` NUMERIC(14,2) nullable, audit cols (per-project escalation override; GLOBAL default lives in application config).
    - Idempotent; register **last** after `131`.
    - _Requirements: 6.4_
  - [x] 1.6 Create the `OFFERS` resource + `APPROVE` operation + role-matrix seed changeset (`133`)
    - Create `133-seed-offers-resource.xml` following `017-seed-users-resource.xml`: insert the `OFFERS` resource row (code, `name_ru`/`name_pl`, descriptions), guarded `SELECT COUNT(*) FROM resources WHERE code = 'OFFERS'` = 0 / `MARK_RAN`.
    - In a second changeSet, idempotently insert the custom `APPROVE` operation row into `operations` (guarded `... WHERE code = 'APPROVE'` = 0), then grant ADMIN CRUD + APPROVE, MANAGER CRUD + APPROVE, and CLIENT READ + APPROVE on `OFFERS` (per-role `role_resources` + `role_resource_operations`), each guarded by a `NOT EXISTS` precondition. Grant **no** OFFERS to FOREMAN/WORKER/FINANCIER (Requirement 16 tightened model).
    - Register **last** after `132`.
    - _Requirements: 5.1, 5.4, 5.7, 16.3, 16.6_
  - [x] 1.7 Create the `NOTIFICATIONS` resource + all-roles matrix seed changeset (`134`)
    - Create `134-seed-notifications-resource.xml`: insert the `NOTIFICATIONS` resource row (idempotent), ADMIN grant, and READ/UPDATE/DELETE for **every** role (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER, CLIENT), each guarded by a `NOT EXISTS` precondition. Grant **no** CREATE to any end-user role.
    - Register **last** after `133`.
    - _Requirements: 13.13_
  - [x] 1.8 Create the ESTIMATE/OFFERS grant retighten changeset (`135`)
    - Create `135-retighten-estimate-and-offers-grants.xml`: archive-before-delete the FOREMAN/WORKER/FINANCIER grants on `ESTIMATE` (and any broader `OFFERS` grants the parent §5 matrix implied) so the estimate/costs/margins and offers are MANAGER/ADMIN (+ CLIENT for OFFERS) only. Snapshot the removed `role_resource_operations`/`role_resources` rows into an archive table, then delete; guard idempotently with `onFail="MARK_RAN"`.
    - Register **last** after `134`.
    - _Requirements: 16.1, 16.2, 16.5_
  - [x] 1.9 Write Liquibase migration integration tests
    - Assert all five tables exist post-apply with the documented columns, the offers partial unique index (one non-terminal offer/project), the discount `value >= 0` CHECK, and the round figure-ownership + reject-explanation CHECKs; assert the `OFFERS` resource + `APPROVE` operation + CLIENT/MANAGER/ADMIN grants and the `NOTIFICATIONS` all-roles READ/UPDATE/DELETE (no CREATE) are seeded; assert `135` removed the FOREMAN/WORKER/FINANCIER ESTIMATE grants and archived them; assert a re-run of `128`–`135` is a no-op.
    - _Requirements: 5.7, 13.13, 16.1, 16.2, 16.6_

- [x] 2. Backend: JPA entities, enums, and DTOs
  - [x] 2.1 Add the enums and extend `ProjectStatus`
    - Add `OfferStatus` (`DRAFT, SENT, CHANGES_REQUESTED, COUNTERED, APPROVED, REJECTED, WITHDRAWN`; last three terminal), `OfferVisibilityStatus` (`DRAFT, ON_APPROVAL, APPROVED`), `DiscountScope` (`GLOBAL, CATEGORY, LINE`), `DiscountKind` (`PERCENT, ABSOLUTE`), `NegotiationRoundKind` (`DISCOUNT_REQUEST, MANAGER_PROPOSAL, MANAGER_REJECT, CLIENT_ACCEPT, CLIENT_DECLINE`), `NegotiationRoundStatus` (`OPEN, ACCEPTED, DECLINED, REJECTED, SUPERSEDED`). Extend the existing `ProjectStatus` enum with `READY_TO_OFFER`, `OFFERED`, `APPROVED` (this spec drives only `READY_TO_OFFER → OFFERED` and `OFFERED → APPROVED`).
    - _Requirements: 3.1, 3.2, 3.5, 17.3_
  - [x] 2.2 Add `OfferEntity`, `OfferDiscountEntity`, `OfferNegotiationRoundEntity`, `NotificationEntity`, `OfferProjectSettingsEntity`
    - Create the five `BaseEntity` subclasses mapping the tables from task 1 exactly (FKs, nullable columns, the discount `sourceRound` back-reference, the round `offerRevision`/`roundNo`/`status`/`adminApproved`). `OfferDiscount` and `OfferNegotiationRound` are cascade/collection children of `Offer`; `Notification` has a `recipient` user FK and is standalone.
    - _Requirements: 1.1, 2.1, 4.1, 4.6, 6.3, 13.1_
  - [x] 2.3 Add the read-model DTOs and MapStruct/assembler skeletons
    - Add `ClientOfferReadModel` (offer-level fields ONLY: `status`, `visibilityStatus`, `revision`, `selectedPackageCode`, totals, `perLineOfferPrice[]`, `perCategoryOfferPrice[]`, `packagePrices[]`, `appliedDiscounts[]`, `finishing[]`, `negotiationThread[]`, `readiness`; references the estimate **only by id**, no cost/margin/worker-rate/estimate-unit-price field), `ExecutorOfferReadModel` (client fields + manager-control state), and `AgreedOfferView` (approved revision totals/package/discounts). Wire the DTOs/mappers so the CLIENT model type graph structurally contains no cost/margin field name.
    - _Requirements: 7.4, 15.1, 15.2, 19.3_
  - [x] 2.4 Write entity + DTO structural tests
    - Assert the offer children cascade-remove with the offer; assert the round figure-ownership shape (value/kind only on `MANAGER_PROPOSAL`); assert a **structural** test over `ClientOfferReadModel`'s type graph finds no field whose name matches cost/margin/worker-rate/estimate-unit-price (a compile/reflection guard for Property 21).
    - _Requirements: 15.2, 19.3, 10.12_

- [x] 3. Backend: pure collaborators — discount resolver and totals
  - [x] 3.1 Implement `DiscountResolver` (pure, order-independent)
    - Create `DiscountResolver` (`@Component`, pure static core): `resolveEffective(discounts, estimateLines) → Map<lineId, EffectiveDiscount>` picking the single surviving discount per line — `GLOBAL` if present, else the `CATEGORY` covering the line's work-type group, else the line's own `LINE` — and clamping the effective amount to the line's net base. Order-independent (Requirements 2.5 / 4.9 / 10.16).
    - _Requirements: 2.5, 4.9, 10.16_
  - [x] 3.2 Implement `OfferTotalsCalculator` (pure)
    - Create `OfferTotalsCalculator`: `compute(estimateClientPrices, effectiveDiscounts, vatRate) → {totalNet, totalVat, totalGross}` with `totalNet = estimateTotalNet − Σ EffectiveDiscount(net)`, clamped so `totalNet >= 0` and no single effective discount exceeds its base; VAT/gross recomputed from the discounted net. Operates over the **live-referenced** estimate client-facing final prices, never a stored offer copy.
    - _Requirements: 1.3, 2.6, 2.7, 10.1, 10.2, 10.3, 10.15, 19.4_
  - [x] 3.3 Write property test for offer totals (jqwik, ≥100 iterations)
    - **Property 1: Offer totals equal live-referenced estimate net minus effective discounts**
    - **Validates: Requirements 1.3, 2.6, 10.3, 10.15, 19.4**
    - _Requirements: 1.3, 2.6, 10.3, 10.15, 19.4_
  - [x] 3.4 Write property test for discount bounds (jqwik, ≥100 iterations)
    - **Property 2: Discount application never violates its bounds**
    - **Validates: Requirements 2.7, 10.1, 10.2**
    - _Requirements: 2.7, 10.1, 10.2_
  - [x] 3.5 Write property test for scope override-and-cancel (jqwik, ≥100 iterations)
    - **Property 3: Scope override-and-cancel is deterministic and order-independent**
    - **Validates: Requirements 1.4, 2.5, 4.9, 10.16**
    - _Requirements: 1.4, 2.5, 4.9, 10.16_

- [x] 4. Backend: pure collaborators — status machine, visibility, readiness, escalation
  - [x] 4.1 Implement `OfferStatusMachine` (pure)
    - Create `OfferStatusMachine`: `transition(current, action, actorRole) → next | IllegalTransition`, encoding Requirement 3's legal transitions and terminal states; any undefined transition (including any exit from a terminal state) throws `409 error.offer.illegal.transition`. Single source of truth for R3 / R10.5.
    - _Requirements: 3.1, 3.2, 3.4, 3.5, 3.6, 3.7, 3.8, 10.5_
  - [x] 4.2 Implement `OfferVisibilityResolver` (pure)
    - Create `OfferVisibilityResolver`: `visibilityOf(offerStatus)` per the R17.4 mapping (`DRAFT→DRAFT`; `SENT`/`CHANGES_REQUESTED`/`COUNTERED→ON_APPROVAL`; `APPROVED→APPROVED`); `assertClientVisible(...)` throws `404 error.entity.not.found` (indistinguishable from missing) when a CLIENT reaches a `DRAFT`-visibility offer.
    - _Requirements: 5.9, 17.4, 17.5, 17.9_
  - [x] 4.3 Implement `OfferReadinessCalculator` (pure)
    - Create `OfferReadinessCalculator`: `readiness(openRounds, totalRounds, unfilledPlaceholders, totalPlaceholders) → percentage` combining (a) unresolved negotiation proposals and (b) unfilled finishing Placeholders; equals 100% exactly when there are no open rounds and no unfilled placeholders.
    - _Requirements: 17.7_
  - [x] 4.4 Implement `EscalationPolicy` + `OfferSettings` config
    - Create `EscalationPolicy` resolving the effective `Escalation_Threshold` = per-project `offer_project_settings` override else the GLOBAL `foremen.offer.escalation.percent-cap`/`...absolute-cap` application config; `requiresAdminApproval(kind, value, scopeBase)` returns true when the proposed value exceeds the cap.
    - _Requirements: 6.2, 6.3, 6.4_
  - [x] 4.5 Write property test for the status machine (jqwik, ≥100 iterations)
    - **Property 4: The status machine performs no illegal transition**
    - **Validates: Requirements 3.8, 10.5**
    - _Requirements: 3.8, 10.5_
  - [x] 4.6 Write property tests for visibility projection and readiness (jqwik, ≥100 iterations each)
    - **Property 10: Offer visibility is a consistent projection of the offer status** — **Validates: 17.4, 17.9**
    - **Property 23: Offer readiness is a correct function of open rounds and unfilled placeholders** — **Validates: 17.7**
    - Two separate jqwik property tests, each tagged with its feature name + property title, each ≥100 iterations.
    - _Requirements: 17.4, 17.7, 17.9_
  - [x] 4.7 Write property test for the escalation gate (jqwik, ≥100 iterations)
    - **Property 11: Escalation gates proposals above the threshold**
    - **Validates: Requirements 6.2, 6.3, 6.5, 12.3**
    - _Requirements: 6.2, 6.3, 6.5, 12.3_

- [x] 5. Backend: `OfferService` (project-scoped lifecycle)
  - [x] 5.1 Implement `OfferService` prepare / select-package / send / withdraw
    - Create `OfferService` (`ProjectScopedService`, `getProjectIdPath()` → `"project.id"`, `allowedProjectIds` wired to `ProjectAccessCache`). `prepareOffer(projectId)`: precondition `estimate.status == PRICED` (else `400 error.offer.estimate.not.priced`); reject a second non-terminal offer (`409 error.offer.active.exists`); create `Offer{status=DRAFT, revision=1}`; resolve `estimate.appliedPackageCode → OfferPackage` for `selectedPackage` (null-safe per R1.7); compute totals via `OfferTotalsCalculator` over the live estimate prices. `selectPackage(offerId, packageCode)`: persist, re-derive the finishing selection (delegate to FOR-05-05 propagation), recompute totals, bump revision when the priced proposal changes. `send(offerId)`: `DRAFT → SENT`, project `READY_TO_OFFER → OFFERED`, visibility `ON_APPROVAL`. `withdraw(offerId)`: non-terminal → `WITHDRAWN`. `approve()`: `SENT`/`COUNTERED → APPROVED`, project `OFFERED → APPROVED`, mark `approvedRevision` as `Agreed_Offer_Version`, emit the state-change event downstream consumers (FOR-05-14/09) read. All transitions delegate to `OfferStatusMachine`; writes touching package/finishing selection assert the estimate is not service-locked via `DraftGateGuard`.
    - _Requirements: 1.1, 1.2, 1.5, 1.6, 1.7, 3.2, 3.5, 3.7, 5.4, 7.1, 7.3, 20.1_
  - [x] 5.2 Implement terminal-immutability + agreed-version read model
    - Enforce that every mutator asserts the offer is non-terminal (else `409 error.offer.illegal.transition`); expose `AgreedOfferView` for FOR-05-14/09 (approved revision totals, selected package, applied discounts) and guarantee it does not change after `APPROVED`.
    - _Requirements: 3.9, 7.2, 7.4, 10.4, 10.8_
  - [x] 5.3 Write property test for preparing an offer and package seeding (jqwik, ≥100 iterations each)
    - **Property 16: Preparing an offer requires a PRICED estimate** — **Validates: 1.2**
    - **Property 17: The initial package is seeded from the estimate's applied package** — **Validates: 1.6, 1.7**
    - Two jqwik property tests, each tagged with its feature name + property title, ≥100 iterations.
    - _Requirements: 1.2, 1.6, 1.7_
  - [x] 5.4 Write property test for terminal/approved immutability (jqwik, ≥100 iterations)
    - **Property 5: Terminal and approved offers are immutable**
    - **Validates: Requirements 3.9, 7.2, 10.4, 10.8, 12.5**
    - _Requirements: 3.9, 7.2, 10.4, 10.8, 12.5_
  - [x] 5.5 Write `OfferService` integration tests (Spring)
    - prepare requires PRICED (1.2), seeds package from `appliedPackageCode`, null-safe when unresolved (1.6/1.7); one-non-terminal-offer-per-project uniqueness (1.4); send drives `READY_TO_OFFER → OFFERED` + visibility `ON_APPROVAL` (3.2, 17.6); approve drives `OFFERED → APPROVED` and records the agreed revision (3.5, 7.1).
    - _Requirements: 1.2, 1.4, 1.6, 1.7, 3.2, 3.5, 7.1, 17.6_

- [x] 6. Backend: `OfferDiscountService`
  - [x] 6.1 Implement discount write/validation and totals recompute
    - Create `OfferDiscountService`: `write(...)` validates `value >= 0` (R2.2), `PERCENT <= 100` (R2.3), `ABSOLUTE <= scope base` (R2.4); rejects non-MANAGER/ADMIN writers server-side (R2.8 / R5.3); rejects writes on a terminal offer (R3.9); resolves effective discounts via `DiscountResolver` and recomputes totals via `OfferTotalsCalculator` on every change (R2.6).
    - _Requirements: 2.2, 2.3, 2.4, 2.6, 2.8, 3.9, 5.3_
  - [x] 6.2 Write `OfferDiscountService` unit tests
    - negative/percent-over-100/absolute-over-base rejections with localized codes; non-executor rejected server-side (5.3); terminal-offer write rejected (3.9); totals recomputed on add/edit/remove (2.6).
    - _Requirements: 2.2, 2.3, 2.4, 2.6, 2.8, 3.9, 5.3_

- [x] 7. Backend: `NegotiationService` + `EscalationPolicy` wiring
  - [x] 7.1 Implement the negotiation mutators
    - Create `NegotiationService`: `openDiscountRequest(offerId, scope, targetId, justification, clientComment?)` — CLIENT only; creates a `DISCOUNT_REQUEST` round with **no** value/kind (R4.1); `SENT`/`COUNTERED → CHANGES_REQUESTED`. `managerPropose(roundId, kind, value)` — MANAGER only; `EscalationPolicy.check(value)` gates on the threshold (ADMIN approval required when exceeded, R6.3/R6.5); records a `MANAGER_PROPOSAL`; `CHANGES_REQUESTED → COUNTERED`. `managerReject(roundId, explanation)` — MANAGER only; **non-blank explanation required** (R4.8/R6.6 → `400 error.offer.reject.explanation.required`); records `MANAGER_REJECT`. `clientAccept(roundId)` — CLIENT only; materializes the proposal into applied `OfferDiscount`(s) respecting scope override-and-cancel and bumps `revision` (R4.5). `clientDecline(roundId)` — CLIENT only. Every mutator guards round state (R4.7 → `409 error.offer.round.resolved`), binds the round to the current `Offer_Revision` (R4.6), and supersedes narrower-scope propositions when a broader-scope one is created (R4.9). Per-material `LINE`-scoped requests reuse this exact machinery (R12.1/R12.2).
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 6.1, 6.3, 6.5, 6.6, 12.1, 12.2, 12.3, 12.4, 12.5_
  - [x] 7.2 Write property test for the figure-ownership invariant (jqwik, ≥100 iterations)
    - **Property 13: Only a manager proposal carries a discount figure**
    - **Validates: Requirements 4.1, 4.3, 10.18, 12.1**
    - _Requirements: 4.1, 4.3, 10.18, 12.1_
  - [x] 7.3 Write property test for accept-materialization + revision bump (jqwik, ≥100 iterations)
    - **Property 14: Accepting a proposal materializes discounts respecting scope override and bumps the revision**
    - **Validates: Requirements 4.5, 12.4**
    - _Requirements: 4.5, 12.4_
  - [x] 7.4 Write property tests for resolved-round rejection and reject-explanation (jqwik, ≥100 iterations each)
    - **Property 15: Acting on a resolved round is rejected** — **Validates: 4.7**
    - **Property 12: A manager rejection requires a non-blank explanation** — **Validates: 4.8, 6.6, 10.17**
    - Two jqwik property tests, each tagged with its feature name + property title, ≥100 iterations (the reject-explanation generator MUST include all-whitespace strings).
    - _Requirements: 4.7, 4.8, 6.6, 10.17_
  - [x] 7.5 Write `NegotiationService` integration tests (Spring)
    - open request (no figure) → `CHANGES_REQUESTED` (3.3, 4.1); manager propose → `COUNTERED` (3.4); manager reject requires explanation server-side (4.8); client accept materializes discounts + bumps revision (4.5); broader-scope proposition supersedes narrower ones (4.9); escalation over threshold requires ADMIN approval (6.3/6.5).
    - _Requirements: 3.3, 3.4, 4.1, 4.5, 4.8, 4.9, 6.3, 6.5_

- [x] 8. Backend: `NotificationService` + `OfferNotificationEmitter`
  - [x] 8.1 Implement the recipient-scoped `NotificationService`
    - Create `NotificationService` (**not** project-scoped): `create(recipientUserId, type, body, deepLink?)` (the generic extension point, no end-user CREATE grant); `listForActingUser(actingUserId)` (own notifications, `createdAt` DESC); `toggleRead(actingUserId, notificationId)` / `delete(actingUserId, notificationId)` (reject/omit any notification whose `recipient != actingUserId` → `403 error.notification.forbidden`); `unreadCount(actingUserId)`. Ownership enforced via `actingUserId`, never via project-scoping.
    - _Requirements: 13.1, 13.2, 13.4, 13.6, 13.7, 13.8, 13.9, 10.9, 10.10, 10.19_
  - [x] 8.2 Implement `OfferNotificationEmitter` (best-effort)
    - Create `OfferNotificationEmitter` wrapping `NotificationService.create` for the two offer triggers: a client discount request / new round notifies the project MANAGER; a manager propose/reject notifies the requesting CLIENT — each with an Offer_Tab deep-link. Emission runs after the negotiation transition commits (or in a way a failure cannot roll it back): failures are logged and swallowed. Wire it into `NegotiationService`.
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5_
  - [x] 8.3 Write property tests for notification ownership, badge count, and best-effort emission (jqwik, ≥100 iterations each)
    - **Property 18: Notification access is limited to the acting user's own notifications** — **Validates: 10.9, 10.19, 13.8, 13.9, 13.13**
    - **Property 19: The bell unread badge count equals the user's unread notifications** — **Validates: 10.10**
    - **Property 20: A failed notification emission never blocks the triggering transition** — **Validates: 10.11, 14.5**
    - Three separate jqwik property tests, each tagged with its feature name + property title, each ≥100 iterations.
    - _Requirements: 10.9, 10.10, 10.11, 10.19, 13.8, 13.9, 13.13, 14.5_

- [x] 9. Backend: controllers + client finishing-material selection (reusing FOR-05-05)
  - [x] 9.1 Implement `OfferController`, `OfferNegotiationController`, `NotificationController`
    - `OfferController` (`@RestController @RequestMapping("/api/offers") @PermissionResource("OFFERS")`): prepare/select-package/send/withdraw/approve/reject with per-handler `@RequiresPermission` (READ/UPDATE/APPROVE as appropriate; approve/reject on the CLIENT use `(OFFERS, APPROVE)`). CLIENT reads go through `ClientOfferReadModelAssembler`; MANAGER/ADMIN reads through `ExecutorOfferReadModel`; `assertClientVisible` gates CLIENT access on visibility. `OfferNegotiationController` (`@PermissionResource("OFFERS")`): the round mutators. `NotificationController` (`@PermissionResource("NOTIFICATIONS")`): list/toggle/delete/unread-count for the acting user. Full annotation so `PermissionAnnotationValidator` classifies each COMPLETE at startup.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.6, 5.9, 5.11, 8.10, 13.13, 15.1, 15.4, 15.5, 17.5_
  - [x] 9.2 Implement `ClientOfferController` finishing-Placeholder choose-concrete (reuse)
    - `ClientOfferController` (`@PermissionResource("OFFERS")`): `POST /api/offers/{offerId}/finishing/{materialLineId}/choose-concrete` — CLIENT (own project) or MANAGER/ADMIN. Delegate to the **existing** `EstimateAssignmentService.chooseConcrete(projectId, materialLineId, materialId)` after asserting the line is `branch = finishing` AND a Placeholder (`concrete*Material == null`) AND the offer is non-terminal negotiable AND the estimate is `DRAFT`. Reject any other client material write server-side. Package change re-derives `Package_Derived_Finishing_Selection` via FOR-05-05 propagation and bumps revision. Recompute totals per R1.3.
    - _Requirements: 5.8, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 18.5_
  - [x] 9.3 Write property tests for client material-write confinement (jqwik, ≥100 iterations each)
    - **Property 6: A client may write only a finishing Placeholder of its own project's offer** — **Validates: 5.8, 10.6, 11.4, 11.5**
    - **Property 7: A client concrete-finishing choice changes only the affected finishing line** — **Validates: 10.7**
    - **Property 8: Client material selection is permitted only while negotiable and estimate is DRAFT** — **Validates: 11.7**
    - Three separate jqwik property tests, each tagged with its feature name + property title, each ≥100 iterations.
    - _Requirements: 5.8, 10.6, 10.7, 11.4, 11.5, 11.7_
  - [x] 9.4 Write ABAC + confidentiality + service-lock integration tests
    - `OFFERS`/`NOTIFICATIONS` gating incl. `(OFFERS, APPROVE)` for client approve; a `DRAFT`-visibility offer denies CLIENT access (Property 9 as example, R5.9); a CLIENT response never contains a cost/margin/estimate-unit-price field (Property 21 as endpoint example, R15); an `ACTIVE` project rejects estimate/offer writes via `DraftGateGuard` `409 error.estimate.locked` (Property 22 as example, R20); FOREMAN/WORKER/FINANCIER denied ESTIMATE/OFFERS (R16); `PermissionAnnotationValidator` classifies all controllers COMPLETE.
    - _Requirements: 5.9, 5.11, 15.4, 16.1, 16.2, 16.3, 20.2, 20.5_

- [x] 10. Checkpoint — backend compiles and its tests pass
  - Run `getDiagnostics` on the changed Java files, then `./gradlew compileJava compileTestJava` (redirect to a temp log). Run only the affected test classes with `--tests` (the discount/totals/status/visibility/readiness/escalation/negotiation/notification property tests, the `OfferService`/`NegotiationService`/`NotificationService` integration tests, the ABAC + confidentiality + migration tests), redirect to a temp log, and verify via the JUnit result XML. Ensure all tests pass, ask the user if questions arise.

- [x] 11. Frontend: feature scaffold, read-model types, API layer, and tab re-gate
  - [x] 11.1 Scaffold `features/project-offer/` and mirror the read-model types
    - Create `foremen-frontend/src/features/project-offer/` (`types/`, `api/{offer-api,query-hooks,mutation-hooks}.ts`, `state/`, `components/`, `schemas/`); mirror `ClientOfferReadModel`/`ExecutorOfferReadModel`/`AgreedOfferView` and the negotiation-round/discount shapes; wire `apiRequest` to the offer, negotiation, and client choose-concrete endpoints.
    - _Requirements: 8.2, 8.3, 8.4, 11.8_
  - [x] 11.2 Re-gate the `pricing` workspace tab to the real `OfferTab`
    - In `features/project-workspace/workspaceTabs.ts`, change the `pricing` tab's `requiredPermission` from `{ PROJECT_PRICING, READ }` to `{ OFFERS, READ }`, change `labelKey` to `workspace.tab.offer`, and swap `lazy` from `PlaceholderTab` to the new `OfferTab` in `features/project-offer/`. Do NOT add a parallel tab.
    - _Requirements: 8.1, 17.1, 17.2_
  - [x] 11.3 Scaffold `features/notifications/` and mirror the notification types + API
    - Create `foremen-frontend/src/features/notifications/` (`types/`, `api/`, `components/`, `hooks/`); mirror the `Notification` DTO; wire the list/toggle/delete/unread-count endpoints with a TanStack Query hook (`useNotifications`).
    - _Requirements: 13.1, 13.4_

- [x] 12. Frontend: pure staging store (property-tested core)
  - [x] 12.1 Implement `offerStagingStore` (zustand + pure reducer, undo/redo/discard)
    - Per-project zustand store built on a pure reducer modeled on `features/project-materials/state/reserveDraftStore.ts`: an undo/redo history stack over staged client selection ops (`CHOOSE_FINISHING`, `CHANGE_PACKAGE`), `discard` restoring the last committed selection, committed only on explicit save via the Requirement 11 write path. Nothing persisted until commit; the server re-validates on commit.
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.6_
  - [x] 12.2 Write property test for the staging reducer (fast-check, ≥100 iterations)
    - **Property 24: Client staging undo/redo/discard round-trips**
    - **Validates: Requirements 18.1, 18.2, 18.3, 18.4**
    - `features/project-offer/state/offerStagingStore.property.test.ts` over the pure reducer, tagged with feature name + property title, ≥100 iterations.
    - _Requirements: 18.1, 18.2, 18.3, 18.4_

- [x] 13. Frontend: OfferTab, negotiation thread, client material selection, and the Bell
  - [x] 13.1 Implement `OfferTab`, `NegotiationThread`, and `ClientMaterialSelection`
    - `OfferTab` (panel for the reused `pricing` tab): renders offer status/revision/selected package/totals, applied discounts, the ordered negotiation thread, and role-aware actions (executor: prepare/set package/add-edit-remove discounts/send/counter/withdraw subject to the status machine; CLIENT: approve/reject/request discount/select package). Terminal offers render read-only with full history. CLIENT view renders ONLY offer-level data from the read model. `NegotiationThread` renders each round (initiator, kind, value, justification/explanation, status). `ClientMaterialSelection` renders the `Package_Derived_Finishing_Selection`: finishing Placeholders CLIENT-selectable (choose concrete + per-material discount-request affordance), everything else read-only; commits stage through `offerStagingStore`. MANAGER/ADMIN see the same surface (read the client's selection + per-material thread). Surface `Offer_Readiness` while `ON_APPROVAL` (optionally through the FOR-05-01 ReadinessWidget).
    - _Requirements: 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 8.10, 11.3, 12.1, 17.7, 17.8_
  - [x] 13.2 Implement `NotificationBell` in `TopBar`
    - Create `NotificationBell` in `features/notifications/components/` and render it into `app/layout/TopBar.tsx` immediately BEFORE the language toggle button (preserving the mobile `WorkingProjectChip` ordering): bell icon + unread badge count, popup list of the acting user's notifications sorted `createdAt` DESC, per-row toggle-read/unread + delete, and deep-link navigation (clickable when `deepLink` present, non-interactive otherwise).
    - _Requirements: 13.3, 13.4, 13.5, 13.6, 13.7, 13.10_
  - [x] 13.3 Write component tests for OfferTab, thread, material selection, and Bell
    - role-aware action gating + terminal read-only (8.3, 8.4, 8.8); client-only offer-level rendering / no cost field (8.10); finishing Placeholder selectable while chosen/construction lines read-only + per-material discount affordance (8.6, 11.3, 12.1); bell badge + popup ordering + toggle/delete + deep-link vs non-interactive (13.3–13.7, 13.10).
    - _Requirements: 8.3, 8.4, 8.6, 8.8, 8.10, 11.3, 12.1, 13.3, 13.4, 13.5, 13.6, 13.7, 13.10_

- [x] 14. Frontend: i18n (PL/RU parity)
  - [x] 14.1 Add all new keys to `pl.json` and `ru.json`
    - `workspace.tab.offer` ("Оферта / согласование" / "Oferta / uzgodnienie"), `offer.*` (status labels, discount scope/kind labels, negotiation-round action labels, readiness, validation messages, empty states), `notifications.*` (every Notification_Type i18n key + every Bell UI string: badge, empty state, row actions), all in BOTH locales at strict parity with non-empty values.
    - _Requirements: 9.1, 9.2, 9.3, 13.11, 13.12_
  - [x] 14.2 Write locale-parity test
    - key-set equality over the new `offer.*` / `notifications.*` / `workspace.tab.offer` keys; assert no raw key on the offer/bell screens.
    - _Requirements: 9.2, 9.3, 13.12_

- [x] 15. Checkpoint — frontend compiles and its tests pass
  - Run the frontend type-check and the property/component/locale tests (single-run, no watch mode). Ensure all tests pass, ask the user if questions arise.

- [x] 16. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template), written in Russian. This spec has both UI screens (the reused Offer tab, negotiation thread, client finishing selection, the notification Bell) → browser-engine scenarios with MD-report artifacts; and API surfaces against the Dockerized app (`http://localhost:8080`) → API scenarios: prepare offer from a PRICED estimate, discount validation, the negotiation round flow (request → propose → accept/reject, escalation), client choose-concrete on a finishing Placeholder, the `OFFERS`/`NOTIFICATIONS` ABAC gating incl. `(OFFERS, APPROVE)`, the CLIENT confidentiality (no cost/margin field), the `DRAFT`-visibility client-access gate, and the notification ownership/badge endpoints. Repeatability via a per-run generator (unique project/offer per run-id) or explicit teardown.
  - _Requirements: 1.x, 2.x, 3.x, 4.x, 5.x, 6.x, 7.x, 8.x, 11.x, 12.x, 13.x, 14.x, 15.x, 16.x, 17.x, 18.x, 20.x_

- [x] 17. Final checkpoint — full verification and per-repo commits
  - Backend: `getDiagnostics` + compile, then run only the affected test classes with `--tests` (temp log; verify via JUnit XML). Frontend: type-check + property/component/locale tests (single-run).
  - Commit per the two-repo rule: backend (the five entities/services, migrations 128–135, ABAC seeds, read-model assemblers) + spec docs from the **root repo**; all frontend (`features/project-offer/*`, `features/notifications/*`, the `workspaceTabs.ts` re-gate, `TopBar` bell, locales) from **inside `foremen-frontend/`**. Two commits, one per repo. Commit only when the user asks; new branch only; no force-push; no git-config changes.

## Section 18: ESTIMATOR role, prepare-from-any-status, and the Estimate-screen prepare entry point

These tasks amend the shipped feature per the updated Requirements 1, 2.8, 5, 8.3, 16 (criteria 16.1–16.4, 16.7, 16.8), and the new Requirement 21. Backend + spec changes commit from the root repo; the Estimate-screen UI commits from inside foremen-frontend/.

- [x] 18.1 Seed the new ESTIMATOR system role and its grants
  - Add a new Liquibase changeset database_files/changesets/136-seed-estimator-role.xml and register it last in database_files/changelog.xml. ChangeSet 1: insert role ESTIMATOR (code ESTIMATOR, name_ru "Сметчик", name_pl "Kosztorysant"), guarded onFail="MARK_RAN" by SELECT COUNT(*) FROM roles WHERE code = 'ESTIMATOR' = 0. ChangeSet 2: seed role_resources + role_resource_operations replicating the full FOREMAN grant set (INSERT ... SELECT joining the FOREMAN grants so ESTIMATOR mirrors FOREMAN exactly) PLUS OFFERS READ+CREATE+UPDATE (no APPROVE/DELETE) and ESTIMATE READ+CREATE+UPDATE, each block self-guarded by NOT EXISTS. Runs after 135, so ESTIMATOR grants are retained.
  - _Requirements: 16.7, 16.8, 16.1, 16.3_
- [x] 18.2 Remove the PRICED precondition from OfferService.prepareOffer
  - Delete the estimate != PRICED guard and the ESTIMATE_NOT_PRICED_MESSAGE constant/throw in OfferService.prepareOffer; remove the now-unused error.offer.estimate.not.priced key from messages.properties and messages_ru.properties. Keep package-seeding (1.6/1.7) and totals (1.3).
  - _Requirements: 1.1, 1.2_
- [x] 18.3 Update affected backend tests for prepare-from-any-status
  - Update OfferServicePrepareOfferPropertyTest and OfferServiceIntegrationTest so a non-PRICED (DRAFT) estimate prepares successfully; drop assertions keyed on error.offer.estimate.not.priced. Run only affected --tests classes (temp log + JUnit XML).
  - _Requirements: 1.1, 1.2, 10.3_
- [x] 18.4 Add ESTIMATOR to offer ABAC + executor authorization and update the ABAC test
  - Treat ESTIMATOR as executor (READ+CREATE+UPDATE) on OFFERS write/discount paths while (OFFERS, APPROVE) stays denied to ESTIMATOR. Extend OfferControllersAbacTest with ESTIMATOR cases (prepare/read/update + write discounts OK; approve denied; estimate read OK). Verify startup annotation-completeness unaffected.
  - _Requirements: 2.8, 5.1, 5.4, 16.3_
- [x] 18.5 Add the "Prepare offer" action on the Estimate screen (frontend)
  - In foremen-frontend, add a "Prepare offer" button to the project Estimate screen, shown only to OFFERS/CREATE callers (MANAGER/ADMIN/ESTIMATOR), always enabled (no PRICED gate). On click call POST /api/offers/project/{projectId}/prepare (reuse usePrepareOffer/prepareOffer); on success navigate to the Offer/approval tab; on failure show a localized toast and stay. Keep the OfferTab prepare entry point unchanged. Add pl.json/ru.json keys at strict parity.
  - _Requirements: 21.1, 21.2, 21.3, 21.4, 21.5, 21.6, 21.7_
- [x] 18.6 Component test for the Estimate-screen prepare action
  - Vitest/RTL: action visible for executor, absent for non-executor; click calls prepare with project id; success navigates to the offer tab; failure renders the toast and does not navigate; i18n keys resolve. Single-run.
  - _Requirements: 21.1, 21.3, 21.4, 21.5, 21.7_
- [x] 18.7 Update test-cases.md for the Section 18 changes
  - Extend test-cases.md (Russian) with API cases: prepare from a non-PRICED (DRAFT) estimate succeeds; ESTIMATOR prepare/read/update + write discounts OK but (OFFERS, APPROVE) 403; ESTIMATOR FOREMAN-equivalent reads + estimate read; UI case for the Estimate-screen Prepare button. Keep run-id/teardown repeatability + regression group.
  - _Requirements: 1.1, 1.2, 16.1, 16.3, 16.7, 21.1_
- [x] 18.8 Section 18 checkpoint — verification and per-repo commits
  - Backend: getDiagnostics + compile, then affected --tests classes (OfferService*, OfferControllersAbacTest, new-changeset migration IT) — temp log + JUnit XML. Frontend: type-check + new component test (single-run) + i18n parity. Commit per the two-repo rule: 136 changeset + changelog + OfferService/messages + backend tests + spec docs from the root repo; the Estimate-screen UI + locales from inside foremen-frontend/. Two commits. Commit only when the user asks; new branch only; no force-push; no git-config changes.
  - _Requirements: 1.1, 16.7, 21.1_

## Section 19: Post-release defect remediation (offer approval E2E)

Two defects shipped in the FOR-05-07 implementation were caught by the FOR-QA-AUTO-05 offer-approval
E2E slice (they slipped past the unit/property/component tests because those mock the API layer and
the visibility resolver was never exercised for a terminal client read). Backend+spec changes commit
from the root repo; the frontend fix commits from inside foremen-frontend/.

- [x] 19.1 Backend: make the client visibility projection total for terminal offers
  - `reject()` / `withdraw()` (and any `getOffer` of a terminal offer) returned HTTP 500 because
    `OfferVisibilityResolver.visibilityOf` threw for REJECTED/WITHDRAWN while
    `ClientOfferReadModelAssembler.toClientReadModel` always projects a visibility. Add a terminal
    `OfferVisibilityStatus.CLOSED`, map REJECTED/WITHDRAWN → CLOSED (projection now total), and keep
    `assertClientVisible` denying only DRAFT (the owning client may read its own closed offer).
    Update Property 10's test to assert the total projection (REJECTED/WITHDRAWN → CLOSED).
  - _Requirements: 3.6, 3.7, 5.6, 5.9, 17.4, 17.9_
- [x] 19.2 Frontend: align the negotiation API paths to the OfferNegotiationController contract
  - `features/project-offer/api/offer-api.ts` posted to unmapped paths (401): fix
    open-discount-request → `/{offerId}/rounds/discount-request`, propose/reject/accept/decline →
    `/rounds/{roundId}/{action}` (reject, not reject-round; no offerId segment on the round actions).
    Add `'CLOSED'` to the frontend `OfferVisibilityStatus` union to mirror the backend enum.
  - _Requirements: 4.1, 4.3, 4.4, 4.8, 8.4_
- [x] 19.3 Re-verify the FOR-QA-AUTO-05 offer-approval E2E slice is green end to end
  - Rebuild the backend + frontend docker images, bring the stack up, and run
    `./gradlew featureTestForQaAuto05OfferApproval` — all five scenarios (TC-07-01..05) pass.
  - _Requirements: 3.6, 4.x, 8.4_
- [x] 19.4 Backend: gate client-initiated negotiation rounds under (OFFERS, APPROVE)
  - `OfferNegotiationController` annotated every round mutator `(OFFERS, UPDATE)`, but a CLIENT holds
    only `OFFERS READ + APPROVE` (Req 5.2), so discount-request / accept / decline returned 403 before
    the service role check. Re-map the three client-initiated mutators to `(OFFERS, APPROVE)`; keep
    propose / reject on `(OFFERS, UPDATE)` (manager/ESTIMATOR); the NegotiationService role split stays
    the second gate. Update OfferControllersAbacTest.
  - _Requirements: 5.1, 5.2, 5.6, 4.1, 4.4_
- [x] 19.5 Backend (follow-up, NOT required for the E2E): client select-package / choose-concrete ABAC
  - Per Req 5.2 the CLIENT also selects the commercial package and chooses a concrete finishing
    material for a Placeholder, but `OfferController.selectPackage` and
    `ClientOfferController.chooseConcrete` are gated `(OFFERS, UPDATE)` which the client lacks — so a
    client package/concrete selection would also 403. These endpoints are performed by BOTH the client
    (APPROVE) and executors incl. ESTIMATOR (UPDATE), and `@RequiresPermission` supports only one
    operation (no OR), so the correct fix needs either an OR-capable permission check or a dedicated
    operation. Not exercised by the FOR-QA-AUTO-05 E2E (the admin selects the package there), so it is
    tracked here as a follow-up rather than fixed in the E2E remediation pass.
  - RESOLVED (permissions-only, no app-code change): the gap is closed by granting the CLIENT role
    `(OFFERS, UPDATE)` AND `(MATERIALS_FINISHING, READ)` AND `(OFFER_PACKAGES, READ)` through the
    roles admin API (GET the role matrix + add the operation + PUT the merged matrix back — NOT a seed
    changeset). OFFERS:UPDATE unblocks the choose-concrete / select-package write endpoints;
    MATERIALS_FINISHING:READ lets the client load the concrete-product picker options (the finishing
    picker reads `GET /api/finishing-materials`, gated `MATERIALS_FINISHING:READ`) — without it the
    picker shows a no-access state and the client has no products to choose; OFFER_PACKAGES:READ lets
    the client load the selectable commercial packages (`GET /api/offer-packages`, gated
    `OFFER_PACKAGES:READ`) so the per-line package Select renders — without it the fetch 403s,
    `packageOptions` stays empty, and the "choose from another package" path cannot run. Both reads
    are read-only reference-catalog grants (safe). This is safe because the
    service layer keeps the dangerous transitions client-inaccessible even with UPDATE:
    `OfferStatusMachine` requires an EXECUTOR role for SEND/WITHDRAW, `NegotiationService` role-gates
    PROPOSE/REJECT, and `OfferService.chooseFinishingConcrete` enforces finishing-Placeholder-only +
    non-terminal + negotiable with project-scope confinement — so UPDATE only unblocks the client's
    legitimate choose-concrete / select-package. Covered by the E2E scenario TC-07-08 (client picks a
    product for a placeholder from the offered package AND from another package; `choose-concrete`
    returns 2xx — no longer 403). All three grants are applied idempotently in the test and reverted
    in teardown so the shared CLIENT role is restored to `OFFERS {READ, APPROVE}` (no OFFER_PACKAGES /
    MATERIALS_FINISHING / OFFERS:UPDATE left behind).
  - _Requirements: 5.2, 11.3, 12.1_
- [x] 19.6 Backend: expose the round id (and adminApproved) on the negotiation read model
  - The backend `NegotiationRoundView` omitted the round `id` (and `adminApproved`) that the frontend
    `NegotiationRoundView` contract consumes, so client round-action URLs were built as
    `/rounds/undefined/{propose|accept|decline|reject}` → 500. Add `id` (first component) and
    `adminApproved` to the record and populate them in
    `ClientOfferReadModelAssembler.negotiationThread`; update all call sites + DTO/assembler tests.
  - _Requirements: 4.3, 4.5, 6.3, 8.5, 15.1_
- [x] 19.7 Backend: persist offer-negotiation notifications (REQUIRES_NEW)
  - `NotificationService.create` ran `@Transactional` (REQUIRED) and called `entityManager.flush()`,
    but `OfferNotificationEmitter` invokes it from an `AFTER_COMMIT` `@TransactionalEventListener`
    (no active transaction), so the flush threw `TransactionRequiredException` and NO notification was
    ever persisted (the bell was always empty; the emitter swallowed the error best-effort). Change
    `create` to `@Transactional(propagation = REQUIRES_NEW)` so the post-commit emission writes in its
    own transaction. Verified by FOR-QA-AUTO-05 TC-07-06 (manager receives the client's
    discount-request notification; client receives the manager's proposal notification).
  - _Requirements: 13.1, 13.2, 14.1, 14.2, 14.5_
- [x] 19.8 Frontend/QA: offer-tab chosen-material card, bell deep-link E2E, settled screenshots, initiator i18n
  - Chosen finishing lines now render a product mini-card (thumbnail + concrete product name + price +
    Change) instead of a bare type dropdown (the picker shows only on Change). Added
    `offer.round.initiator.ADMIN`/`ESTIMATOR` i18n keys (ADMIN-initiated rounds showed a raw key).
    Added NotificationBell testids + an E2E step that opens the bell, verifies the manager-proposal
    notification title (localized, not a raw key) and unread badge, and follows the deep link to the
    offer tab. Report screenshots now wait for content (no skeleton). Added an i18n raw-key guard on
    the bell.
  - _Requirements: 8.6, 11.3, 13.3, 13.5, 13.10, 13.11, 9.2, 9.3_

- [x] 19.9 Backend: chosen finishing-product name shows product model + manufacturer
  - The offer chosen-material card showed only the generic material/type name (e.g. "Двери"). Compose
    `ClientOfferReadModelAssembler` `chosenProductName` from the finishing product's `model` (its
    specific name, material-name fallback) + its `producer` (manufacturer) as "{model} · {producer}";
    fetch `concreteFinishingMaterial.producer` in the DAO entity graph. Update the assembler tests.
  - _Requirements: 8.6, 11.4, 15.1_

- [x] 19.10 QA: E2E — explicit un-choose of two package finishing materials after apply-cheapest
  - New TC-07-07: after apply-cheapest (readiness 100%), the admin opens estimate cells and un-chooses
    two FINISHING package materials via the CellReport eraser (back to Placeholder), saves, and the
    scenario asserts readiness drops below 100% and the offer tab shows ≥2 unchosen Placeholder
    positions. Added EstimateTab cell-report helpers (assigned-cell scan, open/close report, un-choose
    finishing line) and OfferTab placeholder/chosen counters.
  - _Requirements: 11.4, 17.7, 8.6_

- [x] 19.11 Frontend: product-picker filter uses the AND keyword (not ';') so package-filtered options load
  - The offer `finishingConcreteFilter` and the estimate `concreteFilter` / `bulkConcreteFilter` joined
    the type clause and the package clause with `;`, which the backend QueryTokenizer rejects (400),
    so the concrete-product pickers never loaded options when a package clause was present. Switched
    the combinator to the ` AND ` keyword (QueryParser's AND token). Fixes the client self-service
    product choice (offered package + another package) and the estimate concrete pickers. Covered by
    E2E TC-07-08.
  - _Requirements: 11.3, 11.4, 8.6_

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP; core implementation
  tasks are never optional.
- Each task references specific requirements for traceability; every one of the 24 design correctness
  properties is realized as a property-based test (jqwik on the backend, fast-check on the frontend),
  annotated with its property number and the requirements it validates, placed next to the code it
  checks.
- Access-control wiring, ABAC seeding, UI rendering, i18n parity, and confidentiality/service-lock
  guarantees are covered by example / integration / structural tests (tasks 1.9, 2.4, 5.5, 6.2, 7.5,
  9.4, 13.3, 14.2), not as universally-quantified properties — matching the design's Testing Strategy.
- This spec spans two git repos; the final checkpoint commits backend/spec from the root repo and all
  frontend from inside `foremen-frontend/` (per `.kiro/steering/git-repo-structure.md`).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8"] },
    { "id": 1, "tasks": ["1.9", "2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "3.1", "4.1", "4.2", "4.3", "4.4"] },
    { "id": 3, "tasks": ["2.4", "3.2", "4.5", "4.6", "4.7"] },
    { "id": 4, "tasks": ["3.3", "3.4", "3.5", "5.1"] },
    { "id": 5, "tasks": ["5.2", "6.1"] },
    { "id": 6, "tasks": ["5.3", "5.4", "5.5", "6.2", "7.1"] },
    { "id": 7, "tasks": ["7.2", "7.3", "7.4", "7.5", "8.1"] },
    { "id": 8, "tasks": ["8.2", "9.1", "9.2"] },
    { "id": 9, "tasks": ["8.3", "9.3", "9.4"] },
    { "id": 10, "tasks": ["11.1", "11.3"] },
    { "id": 11, "tasks": ["11.2", "12.1"] },
    { "id": 12, "tasks": ["12.2", "13.1", "13.2"] },
    { "id": 13, "tasks": ["13.3", "14.1"] },
    { "id": 14, "tasks": ["14.2"] },
    { "id": 15, "tasks": ["18.1", "18.2", "18.3", "18.4", "18.5", "18.6", "18.7", "18.8"] }
  ]
}
```
