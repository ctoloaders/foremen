# Requirements Document

## Introduction

This spec (**FOR-06-07-client-payments**) covers the **client payment plan/actual** area of a project
in the execution (`ACTIVE`) stage. It owns the **internal/admin** side of client payments: building a
**payment schedule** of tranches (generated from the project total or authored manually), ingesting
**incoming payments** and reconciling each against the **nearest open tranche**, tracking tranche
**statuses and overdue state**, and wiring up the two external channels the business uses — a
**mass-collect bank account** (incoming-transaction feed) and **przelewy24** online payment — behind
an **abstraction that no-ops cleanly until real credentials are supplied**. Commissions are borne by
the client (payer). It renders the **Płatności** execution-stage tab (owner `FOR-06`, parent OVERVIEW
§"Табы стадии исполнения") inside the FOR-05 project workspace.

The project total this spec schedules against is the **approved offer total** handed off by FOR-05-07
(`Offer.totalGross` on the project's `APPROVED` offer / the `OFFER_BASE` project price frozen by
FOR-05-14). This spec **reads** that total; it does not recompute pricing. Amendments (FOR-06-08/09)
may change the project total after signing; the schedule exposes a **re-plan** path for the remaining
(unpaid) amount but never rewrites already-settled tranches.

The **client-facing** rendering — the client viewing the schedule and paying online — is delegated to
**FOR-09** (client portal). FOR-06-07 owns the domain, the reconciliation engine, the admin UI, and
the reusable server actions FOR-09 consumes.

### Scope boundaries (what this spec owns vs. delegates)

| Concern | Owner |
|---|---|
| `PaymentSchedule` / `PaymentTranche` / `PaymentTransaction` domain + status machines | **this spec** |
| Tranche generator (split a total into N tranches) + manual/custom scheduler | **this spec** |
| Incoming-payment reconciliation against the nearest open tranche (under/over payment) | **this spec** |
| Overdue detection + status derivation | **this spec** |
| Mass-collect bank incoming-transaction ingest (abstraction + no-op stub) | **this spec** |
| przelewy24 online-payment initiation + webhook (abstraction + no-op stub) | **this spec** |
| Payment-related notifications (emit only, via the generic `Notification_Service`) | **this spec** |
| `PAYMENTS` ABAC resource seed + role matrix | **this spec** |
| Płatności workspace tab (admin side) | **this spec** |
| Generic `Notification_Service` + in-app Bell | FOR-05-07 (reused, NOT re-implemented) |
| Approved offer total / `OFFER_BASE` project price | FOR-05-07 / FOR-05-14 (consumed) |
| Amendment business flow + re-pricing of the project total | FOR-06-08 / FOR-06-09 (consumed) |
| Client-facing schedule view + client online-pay UI | FOR-09 (reuses this spec's server actions) |
| Live przelewy24 credentials, live bank-feed credentials, deployment | FOR-12 / config (only the abstraction + stub here) |

### Relationship to existing specs (source of truth)

- **Parent overview** (`OVERVIEW.md`) — child row 07 `FOR-06-07-client-payments`; depends on
  "Сумма проекта/оффер (FOR-05-07)", the internal bank-feed/transactions (folded in from former
  FOR-10), and FOR-09 (client views the schedule and pays).
- **Parent design** (`FOR-05 design.md` §"FOR-06") — "client payment plan/actual
  (mass-collect/przelewy24, bank reconciliation)" is an execution-stage tab owned by FOR-06.
- **FOR-05-07 (offer-approval)** — the generic `Notification_Service` + in-app Bell, the DB
  `notifications` table (changeset 131) and `NOTIFICATIONS` ABAC resource (changeset 134) are reused
  as-is; the `APPROVED` offer total is the schedule base.
- **FOR-06-00 (execution-foundation)** — the Płatności tab already exists as a `PlaceholderTab` in the
  realigned execution `WORKSPACE_TABS` (gated by `PAYMENTS`/READ); this spec swaps its lazy target to
  the real panel. The `UnplannedExpenseCategory` mini-dictionary is NOT reused here (payments carry no
  unplanned category).
- **FOR-05-08 (document-signing)** — the "full implementation behind an abstraction + no-op stub until
  real credentials" pattern used for e-signing is the template for the przelewy24 / bank-feed providers
  here.
- **`entity-creation-rules`** — `PAYMENTS` resource seed changeset, ADMIN grant + role matrix,
  class-level `@PermissionResource("PAYMENTS")`, `ProjectScopedService.getProjectIdPath()`, startup
  annotation completeness.
- **`test-cases.md` / `tasks-parallel-waves` / `git-repo-structure` / `test-execution-rules`** —
  workspace standards applied to this spec's `tasks.md` and `test-cases.md`.

## Glossary

- **PaymentSchedule**: the per-project payment plan root
  `PaymentSchedule{ project (FK, UNIQUE), currency, basisGross (the total being scheduled), source
   (GENERATED/MANUAL), status (Schedule_Status), + audit }`. Project-scoped
  (`getProjectIdPath = project.id`). At most **one** schedule per project.
- **PaymentTranche**: one installment of a schedule
  `PaymentTranche{ schedule (FK), seqNo, dueDate, amountGross, paidGross (running settled total),
   status (Tranche_Status), + audit }`. Ordered by `seqNo`; `dueDate` drives overdue detection.
- **PaymentTransaction**: one recorded incoming payment
  `PaymentTransaction{ schedule (FK), channel (Payment_Channel), externalRef (nullable, the bank/
   przelewy24 reference), amountGross, feeGross (commission, client-borne), receivedAt,
   status (Transaction_Status), allocations (to tranches), + audit }`.
- **Tranche_Allocation**: the many-to-one settlement link recording how much of a `PaymentTransaction`
  was applied to a given `PaymentTranche` (a transaction may span tranches on overpayment; a tranche
  accumulates allocations across transactions on underpayment).
- **Schedule_Status**: `DRAFT` (editable, not yet client-visible) → `PUBLISHED` (client-visible via
  FOR-09, tranches locked for structural edits except a re-plan of the unpaid remainder) →
  `SETTLED` (all tranches `PAID`). Manager-controlled publish.
- **Tranche_Status**: `PENDING` (not yet due, unpaid), `DUE` (due date reached, unpaid or partly paid),
  `PARTIALLY_PAID` (`0 < paidGross < amountGross`), `PAID` (`paidGross >= amountGross`),
  `OVERDUE` (past `dueDate` and not `PAID`). Derived from `paidGross`, `amountGross`, `dueDate`, and the
  clock — never set by hand.
- **Transaction_Status**: `PENDING` (initiated online, awaiting confirmation), `CONFIRMED` (funds
  received, allocated), `FAILED` (przelewy24 reported failure), `REFUNDED` (reversed). A bank-feed
  transaction is ingested directly as `CONFIRMED`.
- **Payment_Channel**: `BANK_TRANSFER` (mass-collect bank incoming feed), `ONLINE` (przelewy24),
  `MANUAL` (an operator-recorded payment, e.g. cash/off-platform). Localized via the enum-label i18n
  convention (never the raw enum name).
- **Nearest_Open_Tranche**: the lowest-`seqNo` tranche that is not yet `PAID` (status in
  `{PENDING, DUE, PARTIALLY_PAID, OVERDUE}`). A confirmed incoming payment settles against it first,
  then cascades to the next open tranche on overpayment.
- **Underpayment**: a confirmed payment smaller than the Nearest_Open_Tranche's remaining amount; the
  tranche becomes `PARTIALLY_PAID`, the shortfall is recorded, and the tranche stays open.
- **Overpayment / Carry_Over**: a confirmed payment larger than the Nearest_Open_Tranche's remaining
  amount; the tranche is settled to `PAID` and the surplus **carries over** to the next open tranche(s)
  in `seqNo` order, settling them in turn; any final surplus with no open tranche left is held as an
  **unallocated credit** on the schedule.
- **Tranche_Generator**: the server routine that splits `basisGross` into N tranches by a chosen
  strategy — `EQUAL` (N equal parts, rounding remainder onto the last), `PERCENT` (an ordered list of
  percentages summing to 100), or `CUSTOM` (an explicit list of amounts summing to `basisGross`) — with
  a start date and a periodicity (`WEEKLY`/`BIWEEKLY`/`MONTHLY`/`CUSTOM_DATES`) assigning each tranche a
  `dueDate`.
- **Manual_Scheduler**: the admin UI + server path to author/edit tranches directly (amounts + due
  dates) without the generator, used for bespoke schedules; the sum of tranche amounts MUST reconcile
  to `basisGross` (a validated invariant).
- **Re_Plan**: re-generating or re-authoring ONLY the tranches that are still open (not `PAID`) so their
  amounts re-sum to the **unpaid remainder** (`basisGross − Σ paidGross`), used after an amendment
  changes the project total; already-`PAID` tranches and their allocations are immutable.
- **Payment_Provider_Port**: the server abstraction (interface) over an external payment channel —
  `OnlinePaymentProvider` (przelewy24: initiate a payment, handle a webhook) and `BankFeedProvider`
  (mass-collect: poll/receive incoming transactions). The real implementation is credential-gated; when
  credentials are absent a **No-Op provider** is wired that succeeds-without-effect (no throw), so the
  rest of the system runs unchanged until FOR-12 supplies credentials.
- **No_Op_Provider**: the default `Payment_Provider_Port` implementation active when no przelewy24 /
  bank-feed credentials are configured — initiating an online payment returns a disabled result (no
  external call), and the bank feed yields no transactions; neither throws. Mirrors the FOR-05-08
  disabled-signer / `DisabledImageStorage` pattern.
- **Payer_Borne_Fee**: the model where the commission (`feeGross`) of an online payment is paid by the
  client (payer), recorded on the `PaymentTransaction` for reporting, and NOT deducted from the amount
  allocated to tranches.
- **Payments_Tab (Płatności)**: the execution-stage workspace tab keyed `payments`
  (route `/projects/:projectId/payments`, labelKey `workspace.tab.payments`, gated by `PAYMENTS`/READ),
  realigned by FOR-06-00 as a `PlaceholderTab`; this spec swaps its lazy target to the real admin panel.
- **Notification_Service** (per FOR-05-07): the generic, in-app (DB) base notification service —
  "create a `Notification` for a recipient user" + the per-recipient read/mutate/delete + unread-count
  behind the Bell. This spec is a **consumer** (emit only); it does NOT re-implement delivery.
- **Notification_Type** (per FOR-05-07): an i18n key identifying a notification kind; this spec adds new
  `notification.payment.*` types. A raw type key is never surfaced to the user; it resolves to a
  localized title with PL/RU parity.
- **Deep_Link** (per FOR-05-07): the optional UI target a notification navigates to on click; this
  spec's payment notifications deep-link to the project's Płatności tab.
- **Enum_Label_I18n**: the convention that every user-facing enum (`Schedule_Status`, `Tranche_Status`,
  `Transaction_Status`, `Payment_Channel`, generator strategy/periodicity) is rendered through an i18n
  key (e.g. `payments.trancheStatus.PAID`) with PL + RU parity — the raw enum constant is NEVER shown.

## Requirements

### Requirement 1: Payment schedule aggregate (one per project)

**User Story:** As a MANAGER, I want one payment schedule per active project seeded from the approved
offer total, so that I have a single source of truth for what the client owes and when.

#### Acceptance Criteria

1. THE System SHALL allow **exactly one** `PaymentSchedule` per project (enforced by a UNIQUE
   constraint on `schedule.project_id`).
2. WHEN a MANAGER (or ADMIN/FINANCIER) creates a schedule for an `ACTIVE` project, THE System SHALL seed
   `basisGross` from the project's **approved offer total** (`Offer.totalGross` of the project's
   `APPROVED` offer / the `OFFER_BASE` price) and `currency` from the project, with `status = DRAFT` and
   `source` per the chosen creation path (`GENERATED` or `MANUAL`).
3. IF the project has no `APPROVED` offer / no resolvable project total, THEN THE System SHALL reject
   schedule creation with a localized validation message (`error.payment.schedule.noProjectTotal`) and
   SHALL NOT create an empty schedule.
4. THE System SHALL expose the schedule with its tranches, the aggregate `Σ amountGross`, `Σ paidGross`,
   the unpaid remainder, any unallocated credit, and the derived `Schedule_Status`.
5. WHILE a schedule is `DRAFT`, THE System SHALL allow full structural edits (add/remove/re-order
   tranches, re-run the generator); WHILE `PUBLISHED`, THE System SHALL restrict structural edits to the
   **Re_Plan of the unpaid remainder** (Requirement 6) and reject other structural mutations with a
   localized message.
6. THE System SHALL compute `Schedule_Status` as `SETTLED` IFF every tranche is `PAID`; otherwise it
   retains `DRAFT` (unpublished) or `PUBLISHED`.

### Requirement 2: Tranche generator

**User Story:** As a MANAGER, I want to split the project total into tranches by a strategy and
periodicity, so that I can produce a payment plan quickly.

#### Acceptance Criteria

1. THE System SHALL generate tranches from `basisGross` by a `Tranche_Generator` strategy
   `EQUAL | PERCENT | CUSTOM`, a start date, and a periodicity
   `WEEKLY | BIWEEKLY | MONTHLY | CUSTOM_DATES`, assigning each tranche a `seqNo` (1..N) and a `dueDate`.
2. FOR `EQUAL`, THE System SHALL split `basisGross` into N equal parts to 2 decimals and add the
   rounding remainder to the LAST tranche so `Σ amountGross == basisGross` exactly.
3. FOR `PERCENT`, THE System SHALL accept an ordered list of percentages; IF they do not sum to exactly
   100, THEN THE System SHALL reject with a localized message (`error.payment.generator.percentSum`).
4. FOR `CUSTOM`, THE System SHALL accept an ordered list of amounts; IF they do not sum to exactly
   `basisGross`, THEN THE System SHALL reject with a localized message
   (`error.payment.generator.amountSum`).
5. IF N < 1, OR any amount/percentage is negative, OR the start date is absent, THEN THE System SHALL
   reject with a localized validation message.
6. WHEN the generator runs on a `DRAFT` schedule that already has tranches, THE System SHALL replace the
   existing tranches (which are all unpaid in `DRAFT`) with the newly generated set.

### Requirement 3: Manual / custom scheduler (incl. adding a tranche by hand at any time)

**User Story:** As a MANAGER or FINANCIER, I want to author or adjust tranches by hand — and add a new
tranche manually at any time, including on an already-published schedule — so that I can match a
bespoke agreement with the client and record extra installments (e.g. an agreed late installment or an
amendment surcharge) without re-planning the whole schedule.

#### Acceptance Criteria

1. THE System SHALL allow a MANAGER or FINANCIER to add, edit (amount + due date), re-order, and remove
   tranches on a `DRAFT` schedule via the `Manual_Scheduler` path, setting `source = MANUAL`.
2. THE System SHALL re-derive `seqNo` as a contiguous 1..N sequence after any add/remove/re-order.
3. THE System SHALL allow a MANAGER or FINANCIER to **add a single new tranche manually at any time** —
   including while the schedule is `PUBLISHED` — through a dedicated add-tranche action (amount + due
   date; it is appended and its `seqNo` assigned by `dueDate` order, re-deriving the contiguous 1..N
   sequence per criterion 3.2). Adding a tranche is permitted regardless of whether existing tranches
   are already settled and does NOT touch any `PAID` tranche or its `Tranche_Allocation` rows
   (immutable per Requirement 6.2).
4. WHEN a tranche is added manually to a `PUBLISHED` schedule, THE System SHALL increase `basisGross` by
   the added amount (the manual add is an explicit intentional change to the total owed, not a
   reconciliation error), keep the schedule `PUBLISHED`, re-evaluate the derived statuses (Requirement
   10), and re-run allocation of any existing unallocated credit (Requirement 5.5) against the newly
   open tranche. The reconcile invariant `Σ amountGross == basisGross` SHALL hold after the add.
5. THE System SHALL validate that `Σ amountGross == basisGross` before allowing a `DRAFT` schedule to be
   published; IF it does not reconcile, THEN THE System SHALL reject publish with a localized message
   (`error.payment.schedule.sumMismatch`).
6. IF a tranche amount is negative (or zero) or a due date is absent, THEN THE System SHALL reject the
   add/edit write with a localized validation message.
7. THE add-tranche and manual-scheduler write paths SHALL be gated by the `PAYMENTS` UPDATE operation
   (Requirement 11), so only ADMIN / MANAGER(own) / FINANCIER(own) can invoke them; a FOREMAN (R(own))
   cannot.

### Requirement 4: Publish the schedule

**User Story:** As a MANAGER, I want to publish the schedule, so that the client can see it (via FOR-09)
and payments can be reconciled against it.

#### Acceptance Criteria

1. WHEN a MANAGER publishes a reconciling `DRAFT` schedule, THE System SHALL transition it to
   `PUBLISHED`, after which the client-facing surface (FOR-09) may read it.
2. THE System SHALL reject publishing a schedule whose tranche amounts do not sum to `basisGross`
   (Requirement 3.5) or that has zero tranches, with a localized message.
3. WHEN a schedule is published, THE System SHALL emit the "schedule published" notification to the
   CLIENT handled by Requirement 9 (recipient model), carrying a deep-link.
4. THE System SHALL NOT allow un-publishing a schedule that already has any `CONFIRMED` transaction
   allocated; it MAY be re-planned (Requirement 6) instead.

### Requirement 5: Incoming-payment reconciliation (nearest open tranche)

**User Story:** As the System, I want to apply each confirmed incoming payment to the nearest open
tranche with correct under/overpayment handling, so that tranche settlement always reflects reality.

#### Acceptance Criteria

1. WHEN a `PaymentTransaction` becomes `CONFIRMED` (bank-feed ingest, przelewy24 webhook success, or a
   MANUAL record), THE System SHALL allocate its `amountGross` to the `Nearest_Open_Tranche` first.
2. IF the payment is **less than** the nearest open tranche's remaining amount (`Underpayment`), THEN THE
   System SHALL increase that tranche's `paidGross`, set its status to `PARTIALLY_PAID`, and leave it
   open; the shortfall remains due.
3. IF the payment **equals** the remaining amount, THEN THE System SHALL settle the tranche to `PAID`.
4. IF the payment is **greater than** the remaining amount (`Overpayment`), THEN THE System SHALL settle
   the nearest open tranche to `PAID` and **carry over** the surplus to the next open tranche(s) in
   `seqNo` order, recording a `Tranche_Allocation` per tranche touched.
5. IF surplus remains after the LAST open tranche is `PAID`, THEN THE System SHALL hold it as an
   **unallocated credit** on the schedule (surfaced in Requirement 1.4) and SHALL NOT create phantom
   tranches.
6. WHEN every tranche reaches `PAID`, THE System SHALL set `Schedule_Status = SETTLED`.
7. THE System SHALL record each allocation (`Tranche_Allocation`: transaction, tranche, amount) so the
   settlement of each tranche is fully auditable and reproducible.
8. THE System SHALL treat allocation as idempotent per transaction: re-processing an already-allocated
   `CONFIRMED` transaction (e.g. a re-delivered webhook with the same `externalRef`) SHALL NOT
   double-allocate.
9. THE System SHALL allow a MANAGER or FINANCIER to **mark a single tranche as paid manually** through a
   dedicated mark-tranche-paid action — independent of the nearest-open-tranche cascade — so that an
   operator can settle a specific tranche confirmed off-platform (e.g. cash, a reconciled bank line not
   on the feed, or an agreed waiver). The action targets the tranche directly, not the nearest open
   tranche.
10. WHEN a tranche is marked paid manually, THE System SHALL record a `PaymentTransaction` with
    `channel = MANUAL` and `status = CONFIRMED` for the tranche's remaining amount
    (`amountGross − paidGross`), create a `Tranche_Allocation` to exactly that tranche, set
    `paidGross = amountGross`, and derive its status to `PAID` (Requirement 10). THE System SHALL
    attribute the manual settlement to the acting user and MAY accept an optional free-text note +
    received-at date on the action.
11. THE manual mark-paid action SHALL be idempotent: marking an already-`PAID` tranche paid again SHALL
    be a no-op (no second transaction, no double allocation); it SHALL settle only the remaining amount
    of a `PARTIALLY_PAID` tranche (not re-charging the already-paid part).
12. THE mark-tranche-paid action SHALL be gated by the `PAYMENTS` UPDATE operation (Requirement 11), so
    only ADMIN / MANAGER(own) / FINANCIER(own) can invoke it; a FOREMAN (R(own)) cannot. A manual
    mark-paid SHALL trigger the same downstream effects as any confirmed settlement: the `tranchePaid`
    notification (Requirement 9.2 row 2), the `SETTLED` roll-up when it is the last open tranche
    (criterion 5.6), and a recorded, auditable allocation (criterion 5.7).

### Requirement 6: Re-plan after an amendment changes the total

**User Story:** As a MANAGER, I want to re-plan the unpaid part of a published schedule after an
amendment changes the project total, so that the remaining tranches reflect the new amount without
disturbing settled payments.

#### Acceptance Criteria

1. WHEN the project total changes (an amendment signed via FOR-06-08/09 updates `OFFER_BASE`) and a
   MANAGER triggers a `Re_Plan`, THE System SHALL recompute `basisGross` to the new total and
   re-generate/re-author ONLY the still-open (not `PAID`) tranches so their amounts re-sum to the unpaid
   remainder (`new basisGross − Σ paidGross`).
2. THE System SHALL leave every `PAID` tranche and all existing `Tranche_Allocation` rows **immutable**.
3. IF the new unpaid remainder is negative (already overpaid relative to the reduced total), THEN THE
   System SHALL set the remaining open tranches to zero / closed and surface the excess as an
   unallocated credit, with a localized informational message.
4. THE System SHALL keep the schedule `PUBLISHED` through a re-plan (no un-publish).

### Requirement 7: przelewy24 online payment (abstraction + no-op stub)

**User Story:** As the System, I want to initiate an online payment and process its result through a
provider abstraction, so that przelewy24 can be plugged in later without reworking the domain.

#### Acceptance Criteria

1. THE System SHALL define an `OnlinePaymentProvider` (`Payment_Provider_Port`) with an initiate
   operation (create a payment intent for a tranche/amount, returning a redirect/opaque result) and a
   webhook-handling operation (confirm/fail/refund by `externalRef`).
2. WHEN przelewy24 credentials are NOT configured, THE System SHALL wire a `No_Op_Provider`: initiate
   returns a disabled result (no external call, no throw) and no online payment is marked `CONFIRMED` by
   the stub; the rest of the system runs unchanged.
3. WHEN a webhook reports success for a known `externalRef`, THE System SHALL mark the matching
   `PaymentTransaction` `CONFIRMED` and run reconciliation (Requirement 5); on failure → `FAILED`; on
   reversal → `REFUNDED`.
4. THE System SHALL record the commission as `feeGross` on the transaction under the `Payer_Borne_Fee`
   model and SHALL NOT deduct the fee from the amount allocated to tranches.
5. THE webhook endpoint SHALL verify the provider signature when credentials are present and SHALL
   reject unverified calls; under the `No_Op_Provider` it accepts no live webhooks.
6. THE initiate operation is exposed as a reusable server action for FOR-09 (the client triggers
   online pay from the portal); FOR-06-07 owns the server action, FOR-09 owns the client UI.

### Requirement 8: Mass-collect bank incoming feed (abstraction + no-op stub)

**User Story:** As the System, I want to ingest incoming bank transactions and match them to a project's
schedule, so that bank transfers settle tranches automatically once the feed is live.

#### Acceptance Criteria

1. THE System SHALL define a `BankFeedProvider` (`Payment_Provider_Port`) that yields incoming
   transactions (amount, currency, `externalRef`, received-at, and a project-correlation key such as a
   per-project virtual account / payment reference).
2. WHEN bank-feed credentials are NOT configured, THE System SHALL wire a `No_Op_Provider` that yields
   no transactions and never throws.
3. WHEN an incoming bank transaction is ingested and correlates to a project schedule, THE System SHALL
   create a `PaymentTransaction` with `channel = BANK_TRANSFER`, `status = CONFIRMED`, and run
   reconciliation (Requirement 5), idempotently by `externalRef` (Requirement 5.8).
4. IF an ingested transaction cannot be correlated to any schedule, THEN THE System SHALL record it as
   **unmatched** (not allocated) and surface it for manual correlation, without failing ingest.
5. THE System SHALL expose a MANUAL record path (`channel = MANUAL`) so an operator can register an
   off-platform payment that reconciles identically.

### Requirement 9: Payment notifications (emit via the generic Notification_Service)

**User Story:** As a MANAGER or FINANCIER, I want to be notified of payment events, so that I can act on
received, overdue, and anomalous payments without polling the schedule.

#### Acceptance Criteria

1. THE System SHALL emit all notifications through the generic `Notification_Service` (FOR-05-07) and
   SHALL NOT re-implement delivery; each notification is persisted per-recipient and surfaced in the
   in-app Bell.
2. THE internal recipients are the project's **MANAGER and FINANCIER** members; CLIENT-facing
   notifications are limited to the "schedule published" case (criterion 9.3 row 5) and are delivered to
   the project's CLIENT member(s) (rendered in FOR-09). The System SHALL emit the following set, each as
   a new `notification.payment.*` `Notification_Type` with a deep-link to the Płatności tab (CLIENT rows
   deep-link to the FOR-09 client surface):

   | # | Trigger (domain event) | Recipient(s) | Type key | Deep-link |
   |---|---|---|---|---|
   | 1 | A `PaymentTransaction` becomes `CONFIRMED` and is allocated | MANAGER + FINANCIER | `notification.payment.received` | Płatności tab |
   | 2 | A tranche reaches `PAID` | MANAGER + FINANCIER | `notification.payment.tranchePaid` | Płatności tab |
   | 3 | A tranche becomes `OVERDUE` | MANAGER + FINANCIER | `notification.payment.trancheOverdue` | Płatności tab |
   | 4 | A confirmed payment results in under/overpayment (shortfall or carry-over) | MANAGER + FINANCIER | `notification.payment.amountMismatch` | Płatności tab |
   | 5 | The schedule is published | CLIENT | `notification.payment.schedulePublished` | FOR-09 client surface |
   | 6 | An online (przelewy24) payment is `FAILED` or `REFUNDED` | MANAGER + FINANCIER | `notification.payment.onlineFailed` | Płatności tab |

3. THE System SHALL fire the `OVERDUE` notification (row 3) **once per tranche** when it first crosses
   into `OVERDUE`; it SHALL NOT re-notify on subsequent evaluations of the same already-overdue tranche
   (no recurring reminder in this spec).
4. THE System SHALL resolve each `Notification_Type` to a localized title with PL + RU parity; a raw
   type key SHALL NEVER be surfaced.
5. WHEN emitting a notification fails, THE System SHALL NOT roll back the triggering payment/tranche
   transition (notifications are best-effort, consistent with the FOR-05-07 service contract).

### Requirement 10: Overdue detection + status derivation

**User Story:** As a MANAGER, I want tranche statuses to reflect due dates and payments automatically,
so that overdue amounts are visible without manual bookkeeping.

#### Acceptance Criteria

1. THE System SHALL derive each `Tranche_Status` purely from (`paidGross`, `amountGross`, `dueDate`,
   now): `PAID` if `paidGross >= amountGross`; else `OVERDUE` if `now > dueDate`; else `PARTIALLY_PAID`
   if `paidGross > 0`; else `DUE` if `now >= dueDate`; else `PENDING`.
2. THE System SHALL evaluate overdue state on read and on a periodic sweep, firing the Requirement 9.3
   overdue notification exactly once per tranche on first crossing.
3. THE System SHALL never persist a hand-set tranche status; status is always derived (a stored
   `status` column, if present, is a materialized cache written only by the derivation routine).

### Requirement 11: ABAC resource + role matrix

**User Story:** As a platform owner, I want payments gated by a dedicated ABAC resource, so that only the
right roles read and mutate schedules.

#### Acceptance Criteria

1. THE System SHALL introduce a project-scoped `PAYMENTS` ABAC resource (seed changeset +
   `@PermissionResource("PAYMENTS")` on the controller, `ProjectScopedService.getProjectIdPath()`
   returning the path to the owning project id), per `entity-creation-rules`.
2. THE System SHALL seed the role matrix: **ADMIN** CRUD; **MANAGER** CRUD(own); **FINANCIER** CRUD(own);
   **FOREMAN** R(own); **CLIENT** R(own) (the client reads their own schedule; client online-pay is a
   dedicated action surfaced through FOR-09, not a `PAYMENTS` write). WORKER receives no grant.
3. THE System SHALL enforce ownership (project membership) server-side via `ProjectScopedService`, so a
   caller reaches only schedules of projects they belong to.
4. THE application SHALL start cleanly (no `PermissionAnnotationValidator` failure): the `PAYMENTS`
   controller's in-scope handlers carry matching `@PermissionOperation`s.

### Requirement 12: Płatności workspace tab (admin side)

**User Story:** As a MANAGER/FINANCIER, I want a Payments tab in the project workspace, so that I can
build the schedule, see tranche statuses, and record/inspect payments.

#### Acceptance Criteria

1. THE System SHALL swap the `payments` execution-stage tab (realigned by FOR-06-00 as a
   `PlaceholderTab`, keyed `payments`, labelKey `workspace.tab.payments`, gated by `PAYMENTS`/READ) to
   the real admin panel, WITHOUT adding a parallel tab.
2. THE panel SHALL render the schedule (basis, tranche table with derived statuses, `Σ paid`, remainder,
   unallocated credit), the generator + manual-scheduler controls, an add-tranche action (Requirement
   3.3), a publish action, a transactions list with allocations, a MANUAL-record action, and a
   per-tranche **mark-as-paid** action (Requirement 5.9) — each permission-gated by the matching
   `PAYMENTS` operation.
3. THE panel SHALL render every enum (`Schedule_Status`, `Tranche_Status`, `Transaction_Status`,
   `Payment_Channel`, generator strategy/periodicity) through the `Enum_Label_I18n` convention (PL + RU),
   never the raw enum constant.
4. THE panel SHALL be read-only for a FOREMAN (R(own)) and surface write controls only when the caller
   holds the corresponding write operation.

### Requirement 13: Internationalization (enum labels, UI text, notifications)

**User Story:** As a user, I want all payment UI text, enum labels, and notification titles in my
language, so that nothing surfaces a raw key or English-only string.

#### Acceptance Criteria

1. THE System SHALL localize all Płatności UI text (tab, tranche/transaction tables, scheduler form,
   buttons, placeholders, validation/error messages) and all new notification titles in **PL and RU**
   with full parity (the locale-parity test passes).
2. THE System SHALL localize every user-facing enum via the `Enum_Label_I18n` convention — e.g.
   `payments.scheduleStatus.*`, `payments.trancheStatus.*`, `payments.transactionStatus.*`,
   `payments.channel.*`, `payments.generator.strategy.*`, `payments.generator.periodicity.*` — with a
   key present in BOTH `pl.json` and `ru.json`.
3. THE System SHALL NOT introduce any new **backend entity** `nameRU`/`namePL` i18n column in this spec:
   the payment entities carry no human-named fields; all user-facing labeling is enum-label i18n handled
   on the frontend (plus the reused `UnplannedExpenseCategory` dictionary, which this spec does not
   extend).
4. THE Polish locale is the primary user-facing language (consistent with the existing
   `workspace.tab.payments` = "Płatności"); RU is maintained at parity.

### Requirement 14: Non-Goals (explicit out of scope)

#### Acceptance Criteria

1. THE spec SHALL NOT implement live przelewy24 or live mass-collect bank credentials/integration — only
   the provider abstraction plus a no-op stub active until FOR-12 supplies credentials.
2. THE spec SHALL NOT re-implement the generic `Notification_Service` or the Bell (FOR-05-07, reused).
3. THE spec SHALL NOT render the client-facing payment views or the client online-pay UI (FOR-09); it
   exposes the reusable server actions only.
4. THE spec SHALL NOT recompute project pricing or own the amendment flow (FOR-05 pricing / FOR-06-08/09
   amendments); it reads the project total and re-plans the unpaid remainder.
5. THE spec SHALL NOT add email/SMS/Telegram delivery or the asynchronous fan-out
   `Delegating_Notification_Service` (future work per FOR-05-07).
