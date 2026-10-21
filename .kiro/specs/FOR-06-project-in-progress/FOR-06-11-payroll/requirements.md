# Requirements Document

## Introduction

This spec (**FOR-06-11-payroll**) is the **worker payout** child of the FOR-06 "project in progress"
(execution stage) parent. It delivers **payroll**: the surface where a FINANCIER, MANAGER, or ADMIN
issues a **`WorkerPayout`** to a worker for the work performed on an `ACTIVE` (or `COMPLETED`)
project, and marks that payout as paid. A regular payout aggregates the worker's cost — Σ `Cost_Value`
over the worker's **unpaid** `APPROVED` `WorkReport`s in the chosen period — using the single
cost-side seam delivered by **FOR-06-10** (`WorkerCostService`), with **no second cost formula**.

Payroll is the execution-stage counterpart to FOR-06-07 (client payments): where client payments
track money **in** (what the client pays), payroll tracks money **out** (what each worker is paid).
It renders the **Rozliczenie wynagrodzeń** (Payroll) execution tab inside the FOR-05 project
workspace.

### Revenue vs. cost — where payroll sits

- **Revenue side (FOR-06-01).** A `WorkReport` carries `clientUnitPrice` / `revenueValue` — what the
  CLIENT pays for the reported work. Payroll does not read or alter the revenue side.
- **Cost side (FOR-06-10).** `WorkerCostService` derives, per approved report, the resolved worker
  type, the unit `Worker_Service_Cost`, and `Cost_Value = Worker_Service_Cost × totalQuantity`, and
  exposes a **per-worker** aggregate (`WorkerCostByWorkerDto`). Payroll **consumes** this service
  verbatim to compute a payout's suggested amount and the breakdown shown in the create dialog. It
  adds **no** cost arithmetic of its own.

### What payroll adds over FOR-06-10

FOR-06-10 is a **read-only** derivation with no persisted state. FOR-06-11 adds the **persisted
payout record**, its lifecycle, the create dialog with period quick-select, the advance-credit
mechanism, and the UI tab:

- a new **project-scoped managed entity `WorkerPayout`** with the full
  `.kiro/steering/entity-creation-rules.md` checklist — a new ABAC resource `PAYROLL`, a seed
  changeset + role matrix, `@PermissionResource`, and `getProjectIdPath()`;
- a **two-status lifecycle** `PLANNED → PAID`, with hard-delete allowed only while `PLANNED`;
- a **create dialog** that, for a chosen worker + period, shows the summary of all **unpaid** reported
  work items, auto-fills the amount (Σ `Cost_Value` **minus** the worker's remaining advance credit),
  and lets the issuer **override** the amount;
- **period quick-select** — current month, previous month, since the last payout, since project
  start, and **advance payment**;
- a **payment channel** (`BANK_TRANSFER` / `CASH`) enum with localized names;
- the realigned **Payroll** execution tab (slot reserved by the FOR-06 overview), gated on the new
  `PAYROLL` resource.

### Preventing double payment — the report item carries a paid-by FK (no new report status)

A reported work item that has already been paid must never be paid twice. Rather than add a new
`WorkReport` status, this spec adds a **nullable FK on the report item** —
`WorkReportRoom.paidByPayout` (the report line) — pointing at the regular `WorkerPayout` that paid it.
A report line is **paid** iff that FK is set, and **payable (unpaid)** iff it is null. There is **no
new report-status enum value**; the FOR-06-01 status machine is unchanged.

When a regular (reports-based) `WorkerPayout` is created, the `APPROVED`, **unpaid** report lines it
covers are **linked** to that payout (their `paidByPayout` FK set) within the create transaction, so
they cannot be paid again. The "unpaid reported work items" summary in the create dialog is therefore
exactly the worker's `APPROVED` report lines in the period whose `paidByPayout` is null. Deleting a
`PLANNED` payout **releases** its report lines (clears the FK) so they become payable again.

### Advance payment — a prepaid worker credit, drawn down at payout time (never splitting a report)

An **advance** is **derived**, not a status the user sets: a payout is an **advance payment** when its
period **ends after** the payout's creation date (the issuer pays for a period that has not fully
elapsed). Because no reports exist yet for that future work, an advance is **memoized as a prepaid
credit** for the worker on the project: the advance amount increases the worker's **remaining advance
credit**. An advance links **no** report lines and sets **no** `paidByPayout` FK.

The caveat confirmed with the product owner: the advance credit is **never drawn down at the report
level** — a report line is **never split** between "advance-paid" and "payable". Instead the credit is
applied **at the time a later regular payout is issued**:

- when a regular payout is created, its suggested amount is `Σ Cost_Value(covered unpaid reports)` **minus**
  the worker's **remaining advance credit** (floored at 0);
- the amount of advance credit actually applied (`min(remaining credit, Σ Cost_Value)`) is **deducted
  from the worker's remaining credit** and recorded on the payout as `advanceApplied`;
- the covered report lines are **fully** linked to the regular payout (FK set) regardless of how much
  credit offset the cash amount — the credit reduces the **ongoing payment**, it does not mark
  individual reports as advance-paid.

So a worker's remaining advance credit is a running balance: advances **increase** it; regular payouts
**decrease** it by the applied offset. Reconciliation is automatic through this balance; there is no
per-report advance accounting and no report is ever partially paid.

### Scope boundaries

- **In scope:**
  - The project-scoped `WorkerPayout` entity (worker, period `[from, to]`, amount, `suggestedAmount`,
    `advanceApplied`, payment channel, status, `isAdvance` derivation, paid-at, note) and the
    `worker_payouts` table/changesets, plus the project-scoped service.
  - The **nullable `WorkReportRoom.paidByPayout` FK** (paid-by marker) added to the FOR-06-01 report
    line, with its changeset; no new `WorkReport` status.
  - The `Payout_Status` lifecycle `PLANNED → PAID`, with hard-delete allowed only for `PLANNED`.
  - The create dialog: worker pick, period quick-select (current month / previous month / since last
    payout / since project start / advance), the unpaid-work-items summary, the auto-filled
    (Σ `Cost_Value` − remaining advance credit) + overridable amount, and the payment channel.
  - Linking covered unpaid `APPROVED` report lines to a regular payout (set FK) within the create
    transaction; releasing them (clear FK) on payout delete.
  - The **advance-credit** mechanism: an advance increases the worker's remaining credit; a regular
    payout applies (min(remaining credit, Σ Cost_Value)) against the ongoing payment and decrements the
    credit; the credit is never drawn at report level and never splits a report.
  - The `Payment_Channel` enum (`BANK_TRANSFER` / `CASH`) with PL + RU localized names.
  - A new project-scoped ABAC resource `PAYROLL` with the full entity-creation checklist, and the
    realigned **Payroll** execution tab (slot reserved by the FOR-06 overview) swapped to the real
    panel.
  - PL + RU localization of every user-facing string, and the spec's own tests + `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **The worker-cost formula** (FOR-06-10) — payroll consumes `WorkerCostService` verbatim and adds
    no second cost formula.
  - **Work reporting and the report status machine up to `APPROVED`** (FOR-06-01). This spec reads
    `APPROVED` report lines and only sets/clears the new `paidByPayout` FK on them; it adds no report
    status and changes no FOR-06-01 transition.
  - **The `WorkerType` dictionary / tier percentages** (FOR-05-06) and **worker-type assignment**
    (FOR-05-09) — read verbatim through FOR-06-10.
  - **Client payments** (FOR-06-07), the margin matrix (FOR-06-06), and the plan/actual Gantt
    (FOR-06-02).
  - **Payout approval workflow / multi-step signing** — a payout is `PLANNED` then `PAID`; there is no
    payout document signing.
  - **Per-report advance accounting / partial report payment** — the advance is a worker-level credit
    balance applied at payout time; a report line is never split.
  - **Bank-feed reconciliation / mass-collect / online payment** — payroll records the channel and the
    paid mark only; it integrates with no payment provider.
  - **Notifications** — none are emitted (see Requirement 9).
  - **Amendments** (FOR-06-08) — the cost rides the already-frozen `WorkReport.clientUnitPrice` via
    FOR-06-10 (amendment-correct automatically once FOR-06-08 ships).

### Relationship to existing specs (source of truth)

- **FOR-06-10-worker-cost-rates** — owns `WorkerCostService` (the single cost-side seam): per-report
  `Cost_Value`, the resolved worker type + base-tier fallback, and the **per-worker** aggregate.
  Payroll consumes it verbatim for both the reports-based suggested amount and the advance's planned
  amount; it adds no cost formula.
- **FOR-06-01-work-reporting** — owns `WorkReport` / `WorkReportRoom`, the `APPROVED` status, and the
  `WORK_REPORTS` resource. Payroll reads `APPROVED` report lines and adds a nullable `paidByPayout` FK
  on `WorkReportRoom` as the paid-by marker; it adds **no** report status and alters no transition.
- **FOR-05-09-team-selection** — owns `ProjectMember` / `ProjectMember.workerType`; the worker picker
  and the advance's "assigned work items" read from the worker's project membership.
- **FOR-06-00-execution-foundation** — reserved the Payroll execution tab slot in `WORKSPACE_TABS`;
  this spec seeds the `PAYROLL` resource and swaps the tab's panel.
- **FOR-06-07-client-payments** — the sibling money-out/in surface; payroll mirrors its project-scoped
  entity + status + channel shape (money out instead of in).
- **FOR-05-07-offer-approval Requirement 13** — the generic in-app `Notification_Service`. This spec
  is **not** a consumer (Requirement 9): it emits no notifications.
- **`.kiro/steering/entity-creation-rules.md`** — because this spec introduces a new managed entity
  (`WorkerPayout`), it adds the `PAYROLL` resource seed changeset, the ADMIN grant + role matrix, a
  class-level `@PermissionResource("PAYROLL")`, `getProjectIdPath()`, and startup annotation
  completeness.

## Glossary

- **WorkerPayout** — the new project-scoped entity `WorkerPayout{ project (FK), worker (user FK),
  periodFrom (date), periodTo (date), amount (numeric, ≥ 0), suggestedAmount (numeric snapshot of the
  computed Σ Cost_Value at creation, ≥ 0), advanceApplied (numeric, ≥ 0; credit offset applied on a
  regular payout, else 0), paymentChannel (enum), status (enum), paidAt (nullable), note (text,
  nullable), + audit }`, project-scoped on `project.id`.
- **Payout_Status** — `PLANNED` (created, not yet paid; hard-deletable) or `PAID` (settled). `PAID` is
  terminal.
- **Payment_Channel** — `BANK_TRANSFER` or `CASH`; an enum with PL + RU localized display names.
- **Period** — the `[periodFrom, periodTo]` interval a payout covers, set by the quick-select or
  manually.
- **Advance payment (`isAdvance`)** — derived true WHEN a payout's `periodTo` is **after** the
  payout's creation date; such a payout increases the worker's advance credit, links no reports, and
  uses the planned-work basis for its suggested amount.
- **paidByPayout** — the new nullable FK on `WorkReportRoom` pointing at the regular `WorkerPayout`
  that paid that report line; set at regular-payout creation, cleared on payout delete. A report line
  is **paid** iff this FK is set. (Replaces the earlier `PAID` report-status idea — no new status.)
- **Unpaid reported work item** — an `APPROVED` `WorkReportRoom` of the worker on the project whose
  `paidByPayout` FK is null.
- **Remaining advance credit (for a worker on a project)** — a running balance = Σ(advance payout
  amounts) − Σ(`advanceApplied` on the worker's regular payouts); never negative. Increased by an
  advance, decreased by the offset applied on a regular payout.
- **Cost_Value / WorkerCostService** — the FOR-06-10 per-report cost and the single cost-side seam
  payroll consumes. Reused verbatim.
- **Issuer** — a user with `PAYROLL` CREATE on the project: ADMIN, MANAGER, or FINANCIER.

## Requirements

### Requirement 1 — WorkerPayout entity and persistence

**User Story:** As the system, I want worker payouts persisted with the covered report lines linked
back, so a payout carries a stable record of what it paid and cannot silently change.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a project-scoped entity `WorkerPayout` extending `BaseEntity` with fields
   `project` (FK, not null), `worker` (user FK, not null), `periodFrom` (date, not null), `periodTo`
   (date, not null, ≥ `periodFrom`), `amount` (numeric, not null, ≥ 0), `suggestedAmount` (numeric,
   not null, ≥ 0), `advanceApplied` (numeric, not null, ≥ 0, default 0), `paymentChannel` (enum, not
   null), `status` (enum, not null), `paidAt` (timestamp, nullable), and `note` (text, nullable).
2. THE SYSTEM SHALL record which `APPROVED` report lines a **regular** payout covers via the nullable
   `WorkReportRoom.paidByPayout` FK (Requirement 6) — not a copy table — so the covered report lines
   are recoverable by querying report lines whose `paidByPayout` references the payout.
3. THE SYSTEM SHALL create the `worker_payouts` table via a Liquibase changeset and add the
   `work_report_rooms.paid_by_payout_id` nullable FK column via a changeset, both following the FK +
   audit-column convention, idempotent via `NOT tableExists` / `NOT columnExists` +
   `onFail="MARK_RAN"`, registered last in `changelog.xml`.
4. THE `suggestedAmount` SHALL be the computed suggested value (Requirement 3) captured at creation and
   SHALL never change afterwards; THE `amount` SHALL default to `suggestedAmount` and MAY be overridden
   by the issuer at creation (Requirement 4.4).
5. WHEN a payout is deleted (Requirement 7) THEN any report lines it linked SHALL have their
   `paidByPayout` FK cleared (Requirement 6.3), and any advance credit it contributed SHALL be removed
   from the worker's running balance (Requirement 5.6).

### Requirement 2 — Payment channel enum

**User Story:** As a financier, I want to record how a worker was paid, so the payout history
distinguishes bank transfers from cash.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a `Payment_Channel` enum with exactly the values `BANK_TRANSFER` and
   `CASH`, persisted on `WorkerPayout.paymentChannel` (not null).
2. WHEN a payout is created THEN a valid `paymentChannel` SHALL be required; a missing or unknown
   channel SHALL be rejected with `error.workerPayout.channel.required`, changing nothing.
3. THE `Payment_Channel` values SHALL each resolve to a localized display name in **both** PL and RU
   (Requirement 8); a raw enum value SHALL never be shown to the user.

### Requirement 3 — Suggested amount from the single cost seam (less advance credit)

**User Story:** As an issuer, I want the payout amount pre-computed from the worker's unpaid approved
work with any prepaid advance already deducted, so I do not re-derive labour cost by hand.

#### Acceptance Criteria

1. WHEN the create dialog is opened for a worker + a **non-advance** period THE SYSTEM SHALL compute
   the gross value as Σ `Cost_Value` over the worker's **unpaid** (`paidByPayout` null) `APPROVED`
   report lines on the project whose `[performedFrom, performedTo]` falls in the selected period, using
   **FOR-06-10 `WorkerCostService`** verbatim — with **no** second cost formula.
2. THE suggested amount for a non-advance payout SHALL be `max(0, gross − remaining advance credit)`;
   the dialog SHALL display the gross value, the remaining advance credit, the credit to be applied
   (`min(remaining credit, gross)`), and the resulting suggested amount.
3. THE SYSTEM SHALL present the per-report breakdown backing the gross value — per unpaid report line:
   the work item, the room, the period, the resolved worker type (with the base-tier-fallback marker),
   the `quantity`, the unit `Worker_Service_Cost`, and the line `Cost_Value`.
4. WHERE a report line's `Cost_Value` is unavailable (null/zero work-item rate, per FOR-06-10 R1.5) THE
   SYSTEM SHALL mark that line unavailable in the breakdown and **exclude** it from the gross value,
   never contributing a fabricated zero, and SHALL never set its `paidByPayout` FK.
5. WHEN the selected period is an **advance** (Requirement 5) THE suggested amount SHALL instead be
   computed from the worker's **planned** work (assigned work items × planned quantity via
   `WorkerCostService`), the breakdown SHALL present the planned basis, and **no** advance-credit
   deduction SHALL apply to it (an advance creates credit, it does not consume it).

### Requirement 4 — Create a payout (issuer, create dialog)

**User Story:** As a financier/manager, I want to create a payout for a worker over a period with an
overridable amount and a payment channel, so I can settle a worker's pay.

#### Acceptance Criteria

1. WHEN an issuer (ADMIN / MANAGER / FINANCIER) creates a payout THEN the request SHALL carry a
   `worker`, a `[periodFrom, periodTo]` interval, a `paymentChannel`, an `amount`, and an optional
   `note`.
2. WHEN a payout is created THEN it SHALL be persisted in `PLANNED` status, its `suggestedAmount` set
   to the computed suggested value (Requirement 3), its `advanceApplied` set per Requirement 5.5 (0
   for an advance), and its `amount` set to the request amount (defaulting to `suggestedAmount`).
3. WHEN a **non-advance** payout is created THEN, within the create transaction, every unpaid
   `APPROVED` report line included in the gross value SHALL have its `paidByPayout` FK set to this
   payout (Requirement 6.1) so it cannot be paid again, and the worker's remaining advance credit SHALL
   be decremented by `advanceApplied` (Requirement 5.5).
4. THE issuer SHALL be able to **override** the `amount` to any value ≥ 0 (differing from
   `suggestedAmount`); a negative or non-numeric amount SHALL be rejected with
   `error.workerPayout.amount.invalid`, changing nothing. Overriding the cash `amount` SHALL NOT change
   which report lines are linked or how much advance credit is applied (those follow the gross
   computation, not the overridden cash amount).
5. WHEN the selected `[periodFrom, periodTo]` is invalid (`periodTo` < `periodFrom`) THEN the create
   SHALL be rejected with `error.workerPayout.period.invalid`, changing nothing.
6. WHEN the chosen `worker` is not an ACTIVE member of the project THEN the create SHALL be rejected
   with `error.workerPayout.worker.notMember`, changing nothing.
7. THE create SHALL be **transactional**: the payout row, the report-line FK updates, and the
   advance-credit balance change SHALL all commit together or not at all, so a report line is never
   linked without a persisted payout and the credit balance never drifts.

### Requirement 5 — Period quick-select, advance derivation, and advance credit

**User Story:** As an issuer, I want to quickly pick a standard period and issue advances that are
automatically reconciled against later payouts, so prepayments are handled without manual bookkeeping.

#### Acceptance Criteria

1. THE create dialog SHALL offer period quick-select options resolving to a `[periodFrom, periodTo]`:
   **current month**, **previous month**, **since the last payout** (from the latest existing payout's
   `periodTo` for the worker on the project, or project start if none), **since project start**, and
   **advance payment**.
2. THE "since the last payout" and "since project start" options SHALL be computed from existing data
   and SHALL pre-fill the interval, which the issuer MAY still adjust manually.
3. A payout SHALL be classified an **advance payment** (`isAdvance = true`) WHEN its `periodTo` is
   **after** the payout's creation date; otherwise it is a regular reports-based payout. The
   classification SHALL be derived from the dates, not a separate flag the user sets directly.
4. WHEN the "advance payment" quick-select is chosen THE period SHALL default to a forward-looking
   interval (so `periodTo` is after the creation date) and the suggested amount SHALL use the planned
   basis (Requirement 3.5).
5. WHEN an **advance** payout is created THEN its `advanceApplied` SHALL be 0, it SHALL link **no**
   report lines, and the worker's **remaining advance credit** SHALL be **increased** by the payout
   `amount`.
6. WHEN a **regular** payout is created THEN the applied credit SHALL be `min(remaining credit,
   gross)`, recorded as the payout's `advanceApplied` and **subtracted** from the worker's remaining
   credit; the worker's remaining credit SHALL never go negative.
7. WHEN an advance payout is **deleted** (while `PLANNED`) THEN the credit it added SHALL be removed
   from the worker's remaining balance; IF removing it would make an already-applied balance
   inconsistent (the credit was already consumed by a later regular payout) THEN the delete SHALL be
   rejected with `error.workerPayout.advance.alreadyApplied`, changing nothing.
8. THE worker's remaining advance credit SHALL be exposed on the payroll read model (per worker) so the
   issuer can see the running prepaid balance.

### Requirement 6 — Report paid-by linkage (no double payment, no new status)

**User Story:** As a financier, I want a paid report line tied to the payout that paid it, so the same
work is never paid twice — without adding a new report status.

#### Acceptance Criteria

1. THE SYSTEM SHALL add a nullable FK `WorkReportRoom.paidByPayout` → `WorkerPayout`; a report line is
   **paid** iff the FK is set and **payable** iff it is null. No new `WorkReport` status value SHALL be
   introduced and the FOR-06-01 status machine SHALL be unchanged.
2. THE "unpaid reported work items" set for a worker + period SHALL be exactly the project's
   `APPROVED` report lines of that worker in the period whose `paidByPayout` FK is null; a line whose
   FK is set SHALL NOT be offered to any further payout.
3. WHEN a `PLANNED` payout is deleted (Requirement 7) THEN every report line whose `paidByPayout`
   references it SHALL have that FK **cleared**, becoming payable again.
4. WHEN a payout is an **advance** (Requirement 5.3) THEN it SHALL set **no** `paidByPayout` FK (there
   is no completed approved work to link; the advance is tracked only as worker credit).
5. THE `paidByPayout` FK SHALL be set/cleared **only** by payout create/delete and SHALL never be
   mutated through the FOR-06-01 work-reporting surface.
6. WHEN a worker reports new work and its line is later approved THEN that new line's `paidByPayout`
   SHALL be null (payable); it becomes linked only when a subsequent regular payout covers it — any
   prepaid advance is reconciled through the worker credit balance at that payout's creation
   (Requirement 5.6), not by linking the line to the advance.

### Requirement 7 — Payout lifecycle (mark paid, delete)

**User Story:** As a financier, I want to mark a planned payout as paid or delete it while planned, so
the payout list reflects reality.

#### Acceptance Criteria

1. WHEN an issuer (ADMIN / MANAGER / FINANCIER) marks a `PLANNED` payout as paid THEN its status SHALL
   become `PAID`, `paidAt` SHALL be set, and the payout SHALL become immutable (its amount, period,
   channel, linked report lines, and applied credit frozen).
2. `PAID` SHALL be terminal: WHEN any actor attempts to edit, re-pay, or delete a `PAID` payout THEN
   the operation SHALL be rejected with `error.workerPayout.status.illegalTransition`, changing
   nothing.
3. WHEN an issuer deletes a `PLANNED` payout THEN the payout SHALL be **hard-deleted**, its linked
   report lines released (Requirement 6.3), and its advance-credit effect reversed (Requirement 5.7); a
   delete of a `PAID` payout SHALL be rejected (Requirement 7.2).
4. THE mark-paid and delete actions SHALL be available to ADMIN, MANAGER, and FINANCIER only (per the
   `PAYROLL` matrix, Requirement 10); no other role SHALL perform them.

### Requirement 8 — Internationalization completeness

#### Acceptance Criteria

1. THE backend `WorkerPayout` entity and the `WorkReportRoom.paidByPayout` FK SHALL carry **no**
   `nameRU`/`namePL` i18n columns (they are transactional, not dictionaries); every referenced name
   (worker, work item, room, worker-type tier) SHALL be read from the existing entities with the
   `ru → pl → code` fallback, adding no new i18n column.
2. THE frontend SHALL add every new user-facing string — the tab/page title, list columns and filters,
   status badges (`PLANNED` / `PAID`), payment-channel names (`BANK_TRANSFER` / `CASH`), the
   create-dialog title and labels/placeholders (worker, period quick-select options, the unpaid/planned
   summary table headers, the gross / remaining-credit / applied-credit / suggested-amount fields and
   the amount override hint, the channel selector, the note), the advance-payment marker, the
   remaining-advance-credit indicator, the base-tier-fallback marker (reused from FOR-06-06 where it
   exists), and all success/error toasts — to **both** `src/locales/pl.json` and `src/locales/ru.json`
   with real PL and RU values (no English fallback), and the locale-parity test SHALL pass.
3. EVERY `error.workerPayout.*` message code SHALL have PL + RU messages in the backend message bundle,
   and the `Payment_Channel` + `Payout_Status` labels SHALL be mirrored key-for-key in PL and RU.
4. THE frontend SHALL never surface a raw i18n key to the user; an unavailable cost SHALL render as a
   localized `—`.

### Requirement 9 — No notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** user-facing notifications in this spec. Payroll is **internal admin
   bookkeeping** performed by FINANCIER / MANAGER / ADMIN with no cross-role handoff to a worker or
   client; creating, paying, or deleting a payout triggers no notification. Any future notification
   (e.g. notifying a worker their payout was paid) would emit via the generic per-recipient
   `Notification_Service` (FOR-05-07 Requirement 13) rather than re-implementing delivery, and is
   explicitly deferred.

### Requirement 10 — Stage gating and ABAC

**User Story:** As a security owner, I want payroll available only on active/completed projects to
finance-capable roles, so payouts are issued in the right stage by the right people.

#### Acceptance Criteria

1. WHEN a project is `ACTIVE` or `COMPLETED` THEN the payroll surface SHALL be reachable; WHEN a
   project is `DRAFT` (design stage) THEN no payroll surface SHALL be reachable.
2. THE SYSTEM SHALL define a new **project-scoped** ABAC resource `PAYROLL`, its backing service
   implementing `ProjectScopedService` with `getProjectIdPath()` returning the path from `WorkerPayout`
   to its owning project id, so LIST reads are automatically project-filtered.
3. THE controller SHALL be annotated `@PermissionResource("PAYROLL")`, every in-scope handler SHALL
   carry a matching `@PermissionOperation` (or `@RequiresPermission`), and the application SHALL start
   without a `PermissionAnnotationValidator` failure.
4. THE SYSTEM SHALL seed the `PAYROLL` resource row idempotently and grant, on top of the project-scope
   filter:
   - **ADMIN** — CREATE, READ, UPDATE, DELETE (matrix completeness; the evaluator bypasses for the
     literal ADMIN role).
   - **MANAGER** — CREATE, READ, UPDATE, DELETE (issue, mark paid, delete planned).
   - **FINANCIER** — CREATE, READ, UPDATE, DELETE (issue, mark paid, delete planned).
   - **FOREMAN** — READ only (visibility into the project's payouts; no issuing).
   - **CLIENT** and **WORKER** — no grant (deny-by-default), documented with an explanatory XML
     comment.
   The mark-paid action maps onto UPDATE; the delete-planned action maps onto DELETE; the `PLANNED`-only
   delete and `PLANNED`-only mutability rules are enforced in the service on top of the ABAC grant
   (Requirement 7).
5. THE backing service SHALL be project-scoped so a read never returns another project's payouts, and
   every write SHALL be confined to the resolved project.
6. WHEN the `PAYROLL` resource is seeded THEN the FOR-06 Payroll execution tab (gated `PAYROLL`/READ)
   SHALL become visible to permitted callers on an `ACTIVE`/`COMPLETED` project, and this spec SHALL
   swap that tab's `lazy` from its placeholder to the real payroll panel without reshaping the
   `WORKSPACE_TABS` contract.

### Requirement 11 — Payroll tab (frontend)

**User Story:** As a financier, I want a payroll tab in the project workspace, so I can see and issue
worker payouts and track prepaid advances.

#### Acceptance Criteria

1. THE SYSTEM SHALL render a Payroll panel on the execution-stage Payroll tab, listing the project's
   payouts with worker, period, advance marker, suggested amount, applied advance credit, paid amount,
   payment channel, and status, filterable by worker and status, and showing each worker's remaining
   advance credit.
2. THE panel SHALL provide a **create-payout dialog**: pick worker, period quick-select (current month
   / previous month / since last payout / since project start / advance payment) with an adjustable
   `[from, to]`, the unpaid-work-items (or planned-work) summary table, the gross / remaining-credit /
   applied-credit / suggested-amount figures, the overridable amount, the payment-channel selector, and
   an optional note.
3. THE panel SHALL expose **mark-as-paid** and **delete** (planned-only, with a confirm) actions to
   ADMIN / MANAGER / FINANCIER, and show each payout's status with a localized badge.
4. THE panel SHALL indicate when a payout is an **advance** (period ends after creation date) and
   surface server validation errors (Requirements 2/4/5/7) inline.
5. THE panel SHALL render an unavailable report `Cost_Value` as a localized `—` and never surface a
   raw i18n key.

### Requirement 12 — Audit, regression, and test artifacts

#### Acceptance Criteria

1. WHEN a payout is created, marked paid, or deleted THEN an audit row SHALL be written via the
   existing audit mechanism.
2. WHEN this spec ships THEN the FOR-06-10 `WorkerCostService`, the FOR-06-01 work reporting +
   `WORK_REPORTS` resource (now carrying the nullable `paidByPayout` FK on `WorkReportRoom`, with its
   status machine unchanged), the FOR-06-06 margin matrix, and the FOR-05/FOR-06-00 workspace shell +
   tab resolver SHALL continue to pass (regression): this spec consumes them and adds the `paidByPayout`
   FK + the `PAYROLL` resource without altering their behaviour.
3. THE spec SHALL ship its own backend unit + property tests (the gross Σ `Cost_Value` reusing
   `WorkerCostService`, the unavailable-rate exclusion, the advance-vs-regular classification by
   period-end-after-creation, the advance-credit balance arithmetic — increase on advance, apply
   `min(remaining, gross)` and decrement on a regular payout, never negative, never splitting a report
   — the `paidByPayout` linkage + release-on-delete, the delete-rejection when an advance was already
   applied, the `PLANNED`-only mutability / delete, the `PAYROLL` ABAC matrix, and seed idempotency),
   frontend unit tests (the create dialog, period quick-select resolution, the gross/credit/suggested
   math display, override, channel/status badges, locale parity), and an API-level `test-cases.md` run
   against the Dockerized app.
