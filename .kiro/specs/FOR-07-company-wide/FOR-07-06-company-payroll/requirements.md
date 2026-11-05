# Requirements Document

## Introduction

This spec (**FOR-07-06-company-payroll**) is a child of the FOR-07 "company-wide / cross-project"
parent. It lifts the **implemented** FOR-06-11 payroll from the **worker × project** level to the
**worker** level and moves it from a project-workspace tab to a **company-wide main-menu screen**.

The reference model is the workbook `2026 ЗП Сметы Графики.xlsx`, sheet
`Андрей  и Сергей Andrii Zhylins`, monthly block **BG–BL** (July). For each month it has:
- an **Earned** half: one row per object (project), plus extra rows such as the supervision
  supplement ("Dop za prowadzenie") and a ZUS + PIT-4R deduction;
- a **Paid** half: payouts with amount, date and comment;
- **Total**, **Paid**, and a **Balance** carried into the next month.

The spec delivers:

1. **Company payroll table** (new main-menu item, `/company/payroll`): rows = workers, each
   expandable into the projects they worked on; columns **Earned**, **Paid**, **Reported work**,
   **Planned work**, **Other payouts** for a filtered period. Filters: workers and a date range
   with presets **current month** (default) and **current year**, **previous / next period**
   shift, and the state **persisted in the browser**.
2. **Worker payroll card** (click a worker): monthly blocks mirroring the workbook — earned per
   project (drillable to work lines), additional payroll lines, payouts, total, and the running
   balance from the worker's opening balance.
3. **Worker-wide payouts distributed onto reported work**: a payout belongs to a worker, not a
   project. On create its amount is **distributed** over the worker's unpaid `APPROVED` report
   lines across **all** projects, oldest first; the last line touched may be **partially paid**.
4. **Worker-wide advance credit**, using the same advance rule as FOR-06-11.
5. **Additional payroll lines**: free-text, signed one-off payments per worker per month (e.g. a
   Multisport reimbursement, a supervision supplement, a deduction), with previous-month hints.
6. **Rate inputs on the worker**: the cost tier comes from the **user's** worker type
   (`/worker-types` dictionary is the source of truth) plus a manually edited **seniority %** on
   the WORKER user.
7. **Removal of the project Payroll tab** and a **paid-status** indicator on reported work items,
   which is what foremen see.

### Relationship to existing specs (source of truth)

- **FOR-06-11-payroll (implemented)** — owns `WorkerPayout`, `PayoutStatus` (`PLANNED → PAID`),
  `PaymentChannel`, the advance derivation (`periodTo` after creation date), the advance-credit
  arithmetic, the suggestion breakdown, and the project Payroll tab. This spec **reshapes** it:
  the payout loses its project, the single `paidByPayout` FK is replaced by allocations, credit
  becomes worker-wide, and the tab is removed. Lifecycle, channel, statuses, and advance rule are
  kept.
- **FOR-06-10-worker-cost-rates** — owns `WorkerCostService` / `WorkerCostDerivation`, the single
  cost seam. This spec changes **where the worker type is resolved from** (user instead of project
  member) and adds the seniority uplift; the formula shape `Base_Cost × (1 + uplift)` is unchanged.
- **FOR-05-06** — owns the `/worker-types` dictionary (`tierPct`, `is_base`) and
  `MarginCostService`. Read as the source of truth; not changed.
- **FOR-02-07-users-ui** — owns the admin user form and `users.worker_type_id`. This spec adds the
  `seniorityPct` field for WORKER users.
- **FOR-06-01-work-reporting** — owns `WorkReport` / `WorkReportRoom` and `APPROVED`. This spec adds
  a paid amount and paid status per report line; the status machine is unchanged.
- **FOR-07-00-company-foundation** — owns the `nav.sections.company` menu section this spec adds an
  item to.
- **FOR-07-04-bank-ledger** (future) — will link bank transactions to worker-wide payouts.
- **FOR-06-06-margin-matrix** — resolves worker tiers itself from the project member; **not**
  changed by this spec (see Non-Goals).

### Scope boundaries

- **In scope:**
  - Schema: drop `work_report_rooms.paid_by_payout_id` and `worker_payouts.project_id`; add
    `work_report_rooms.paid_amount`, `worker_payouts.payout_date`, the allocation table, the
    additional-lines table, the worker payroll profile (opening balance), and
    `users.seniority_pct`. **No data migration** of existing payroll rows.
  - Worker-wide payout suggest / create / mark-paid / delete with oldest-first distribution and
    partial payment.
  - Worker-wide advance credit.
  - Worker-type resolution from `users.worker_type_id` with base-tier fallback + warning, and the
    seniority uplift, inside the FOR-06-10 cost seam.
  - Additional payroll lines CRUD + previous-month hints.
  - Opening balance and the monthly balance chain.
  - Company payroll table and worker payroll card read models + endpoints.
  - Frontend: menu item, `/company/payroll` page, worker card, payout dialog, lines editor,
    seniority field in the user form, paid-status badges in work reporting, removal of the
    project Payroll tab.
  - ABAC: same `PAYROLL` resource; FOREMAN `PAYROLL`/READ revoked.
  - PL + RU localization, backend + frontend tests, `test-cases.md`.
- **Out of scope (Non-Goals):**
  - Migrating or converting existing `worker_payouts` / `paid_by_payout_id` data.
  - Computing seniority from a start date or a ladder — it is entered manually.
  - Changing the FOR-06-06 margin-matrix tier resolution (it keeps using the project member's
    worker type). The divergence is recorded as a follow-up.
  - Changing the `/worker-types` dictionary or the FOR-06-01 report status machine.
  - Bank-ledger linkage (FOR-07-04), payment-provider integration, payslip documents.
  - Notifications (Requirement 13).
  - Closing or archiving a balance — balances are never closed.

## Glossary

- **Worker** — a user with the `WORKER` role.
- **Report line** — a `WorkReportRoom` row of an `APPROVED` `WorkReport` whose worker is the Worker.
- **Line cost (`Cost_Value`)** — `unitServiceCost(report.clientUnitPrice, effectiveTier, baseTier)
  × line.quantity`, computed through the FOR-06-10 seam. Null when unavailable.
- **Effective tier** — the Worker's own worker type (`users.worker_type_id`) with its uplift plus
  the Worker's `seniorityPct`: `Base_Cost × (1 + tierUplift + seniorityPct)`; for the base tier the
  tier uplift is 0.
- **Base-tier fallback** — when the Worker has no (active) worker type, the base tier is used and
  the line is flagged `baseTierFallback = true` (shown as a warning).
- **Paid amount (line)** — `work_report_rooms.paid_amount` = Σ allocation amounts on that line.
- **Unpaid remainder (line)** — `max(0, Cost_Value − paidAmount)`.
- **Line payment status** — `UNPAID` (paid amount 0), `PARTIAL` (0 < paid < cost), `PAID`
  (paid ≥ cost), `UNAVAILABLE` (cost null).
- **Allocation** — a row `{payout, reportLine, amount > 0}` recording how much of a payout went to
  a line.
- **Payout** — a reshaped `WorkerPayout`: `{worker, payoutDate, periodFrom, periodTo, amount,
  suggestedAmount, advanceApplied, paymentChannel, status, isAdvance, paidAt, note}` with **no
  project**.
- **Regular payout** — a payout whose `periodTo` is not after its creation date.
- **Advance payout** — a payout whose `periodTo` is after its creation date (FOR-06-11 rule).
- **Remaining advance credit (worker)** — Σ amounts of the Worker's advance payouts −
  Σ `advanceApplied` of the Worker's regular payouts, floored at 0, over **all** the Worker's
  payouts.
- **Additional payroll line** — `{worker, month, name, amount, note}`: a one-off payment not tied
  to work items; `name` is free text, `amount` is signed and non-zero.
- **Opening balance** — the Worker's starting balance (default 0), stored in the worker payroll
  profile.
- **Month attribution** — a report line belongs to the month of its report's `performedTo`; a
  payout to the month of its `payoutDate`; an additional line to its `month`.
- **Earned (month or period)** — Σ available line costs attributed to it + Σ additional line
  amounts attributed to it.
- **Paid (month or period)** — Σ amounts of the Worker's payouts (`PLANNED` and `PAID`) attributed
  to it.
- **Balance (end of month M)** — opening balance + Σ Earned of all months ≤ M − Σ Paid of all
  months ≤ M. Positive = the company owes the Worker.
- **Issuer** — a user with `PAYROLL` CREATE: ADMIN, MANAGER, FINANCIER.

## Requirements

### Requirement 1 — Schema changes (no data migration)

**User Story:** As the system, I want the payroll schema reshaped to worker level, so payouts can
span projects and lines can be partially paid.

#### Acceptance Criteria

1. THE SYSTEM SHALL drop the column `work_report_rooms.paid_by_payout_id` and its FK
   `fk_work_report_rooms_paid_by_payout` via a Liquibase changeset.
2. THE SYSTEM SHALL add `work_report_rooms.paid_amount` NUMERIC(19,2) NOT NULL DEFAULT 0 with a
   CHECK `paid_amount >= 0`.
3. THE SYSTEM SHALL drop `worker_payouts.project_id` (with its index `ix_worker_payouts_project` and
   FK) and add `worker_payouts.payout_date` DATE NOT NULL (default the current date).
4. THE SYSTEM SHALL create the allocation table `worker_payout_allocations` with `payout_id`
   (FK → `worker_payouts`, ON DELETE CASCADE), `work_report_room_id` (FK → `work_report_rooms`,
   ON DELETE CASCADE), `amount` NUMERIC(19,2) NOT NULL CHECK > 0, audit columns, and a unique
   constraint on `(payout_id, work_report_room_id)`.
5. THE SYSTEM SHALL create `worker_payroll_lines` with `worker_id` (FK → `users`), `month` DATE NOT
   NULL (always the first day of a month), `name` VARCHAR(255) NOT NULL, `amount` NUMERIC(19,2)
   NOT NULL CHECK `amount <> 0`, `note` TEXT nullable, audit columns, and an index on
   `(worker_id, month)`.
6. THE SYSTEM SHALL create `worker_payroll_profiles` with `worker_id` (FK → `users`, unique) and
   `opening_balance` NUMERIC(19,2) NOT NULL DEFAULT 0, plus audit columns.
7. THE SYSTEM SHALL add `users.seniority_pct` NUMERIC(6,4) NOT NULL DEFAULT 0 with a CHECK
   `seniority_pct >= 0 AND seniority_pct <= 1`.
8. EVERY changeset SHALL be idempotent (`NOT columnExists` / `NOT tableExists` / `columnExists` /
   `sqlCheck` + `onFail="MARK_RAN"`), follow the audit-column convention, and be registered last in
   `changelog.xml`.
9. THE SYSTEM SHALL NOT convert existing payroll data. After migration, every existing report line
   has `paid_amount = 0` and existing payouts have no project.

### Requirement 2 — Worker-type resolution and seniority in the cost seam

**User Story:** As a financier, I want a worker's pay rate to follow the worker's own type and
seniority, so pay is the same on every project.

#### Acceptance Criteria

1. THE FOR-06-10 `WorkerCostService` SHALL resolve a report's worker tier from the **worker user's**
   `users.worker_type_id` (when the type is active), not from `ProjectMember.workerType`.
2. WHEN the worker has no worker type, or it is inactive, THEN the base tier SHALL be used and the
   line SHALL carry `baseTierFallback = true`; the UI SHALL show a warning
   (`companyPayroll.warning.noWorkerType`) for that worker.
3. THE unit service cost SHALL be `Base_Cost × (1 + tierUplift + seniorityPct)`, where
   `tierUplift = 0` for the base tier and `tierPct` otherwise, and `Base_Cost` is the existing
   `MarginCostService.baseCost` (rounded to 0.5 zł). The `/worker-types` dictionary values are used
   as stored.
4. WHEN `seniorityPct = 0` THEN the result SHALL equal the FOR-06-10 result for the same tier
   (regression-preserving).
5. THE change SHALL apply to every `WorkerCostService` consumer (FOR-06-10 cost read model and
   payroll). THE FOR-06-06 margin matrix SHALL be left unchanged.
6. A null or zero `clientUnitPrice` or a null quantity SHALL still yield an unavailable cost (null),
   never a fabricated zero.

### Requirement 3 — Seniority on the WORKER user

**User Story:** As an admin, I want to enter a worker's seniority percentage, so it raises their
rate.

#### Acceptance Criteria

1. THE user create/update API SHALL accept an optional `seniorityPct` (a fraction, e.g. `0.02` for
   2 %); THE user read DTO SHALL return it.
2. `seniorityPct` SHALL apply only to WORKER-role users; for any other role it SHALL be stored as 0.
3. WHEN `seniorityPct` is negative or greater than 1 THEN the request SHALL be rejected with 400
   `error.user.seniorityPct.invalid`, changing nothing.
4. THE admin user form SHALL show a **Seniority %** input (entered and displayed as a percentage,
   0–100, up to 2 decimals) only when the selected role is WORKER, next to the worker-type field.
5. THE value SHALL be edited manually; THE SYSTEM SHALL NOT compute it from any date.

### Requirement 4 — Payout suggestion (worker-wide)

**User Story:** As an issuer, I want the suggested payout to cover the worker's unpaid work on all
projects, so I pay the worker once.

#### Acceptance Criteria

1. THE suggestion endpoint SHALL take `workerId`, `periodFrom`, `periodTo` and SHALL NOT take a
   project.
2. FOR a **regular** period (`periodTo` not after today) the candidate lines SHALL be the worker's
   `APPROVED` report lines on **any** project whose report `performedFrom <= periodTo` and whose
   unpaid remainder is > 0. `periodFrom` SHALL NOT exclude older unpaid lines.
3. THE candidate lines SHALL be ordered by report `performedFrom` ascending, then `performedTo`
   ascending, then report id, then line id (the **distribution order**).
4. THE gross SHALL be Σ unpaid remainders of the candidate lines with an available cost; lines with
   an unavailable cost SHALL be listed as unavailable and excluded.
5. THE suggested amount SHALL be `max(0, gross − remaining advance credit)` and the dialog SHALL
   show gross, remaining credit, credit to apply (`min(credit, gross)`), and suggested amount.
6. THE breakdown SHALL list each candidate line with: project name, work item, room, report period,
   worker type (with the base-tier-fallback marker), seniority %, quantity, unit cost, line cost,
   already paid amount, unpaid remainder, and the amount the suggested payout would allocate to it.
7. FOR an **advance** period (`periodTo` after today) THE suggestion SHALL use the FOR-06-11 planned
   basis across all the worker's projects (the `plan_quantity` of the worker's `APPROVED` lines
   whose report overlaps `[periodFrom, periodTo]`), apply no credit, and allocate nothing.
8. THE endpoint SHALL reject an unknown or non-WORKER `workerId` with 422
   `error.workerPayout.worker.invalid` and `periodTo < periodFrom` with 422
   `error.workerPayout.period.invalid`.

### Requirement 5 — Create a payout and distribute it

**User Story:** As an issuer, I want a payout's amount spread over the oldest unpaid work first, so
partial payments are tracked line by line.

#### Acceptance Criteria

1. THE create request SHALL carry `workerId`, `payoutDate` (default today), `periodFrom`, `periodTo`,
   `paymentChannel`, optional `amount` (default the suggested amount), and optional `note`. It SHALL
   NOT carry a project.
2. THE payout SHALL be saved as `PLANNED` with `suggestedAmount` = the suggestion's suggested
   amount, `isAdvance` derived per the FOR-06-11 rule, and `advanceApplied = min(remaining credit,
   gross)` for a regular payout (0 for an advance).
3. FOR a regular payout THE SYSTEM SHALL distribute `D = amount + advanceApplied` over the candidate
   lines in distribution order: each line receives `min(remaining D, line unpaid remainder)`, an
   allocation row is written for every line that receives > 0, the line's `paid_amount` is
   increased by the same amount, and distribution stops when `D` is exhausted or the lines run out.
4. AT MOST ONE line per distribution SHALL end partially paid (the last line touched).
5. WHEN `D` exceeds the gross THEN the surplus SHALL stay unallocated (the payout still counts in
   full towards Paid and the balance); THE SYSTEM SHALL NOT turn the surplus into advance credit.
6. AN advance payout SHALL write no allocations and SHALL increase the worker's remaining credit by
   its amount.
7. THE amount override SHALL accept any value ≥ 0; a negative or non-numeric amount SHALL be rejected
   with 422 `error.workerPayout.amount.invalid`; a missing channel with 422
   `error.workerPayout.channel.required`; an invalid period with 422
   `error.workerPayout.period.invalid`; an invalid worker with 422 `error.workerPayout.worker.invalid`.
8. THE create SHALL be transactional: the payout, its allocations, and the `paid_amount` updates
   commit together or not at all. Concurrent creates for the same worker SHALL NOT allocate more
   than a line's unpaid remainder (the candidate lines are locked for update during distribution).
9. FOR every line, `paid_amount` SHALL always equal Σ of its allocation amounts and SHALL never
   exceed the line cost at the time of allocation.

### Requirement 6 — Payout lifecycle (mark paid, delete)

**User Story:** As a financier, I want to mark payouts paid or delete planned ones, reversing their
effect.

#### Acceptance Criteria

1. Mark-paid SHALL move `PLANNED → PAID`, set `paidAt`, and freeze the payout (FOR-06-11
   behaviour). Any action on a `PAID` payout SHALL be rejected with 409
   `error.workerPayout.status.illegalTransition`.
2. Deleting a `PLANNED` regular payout SHALL remove its allocations and decrease each affected
   line's `paid_amount` by the allocation amount, in one transaction.
3. Deleting a `PLANNED` advance payout SHALL be rejected with 409
   `error.workerPayout.advance.alreadyApplied` WHEN, without it, the worker's Σ `advanceApplied` over
   regular payouts would exceed Σ amounts of the remaining advances (credit already consumed);
   checked worker-wide.
4. THE delete SHALL be a hard delete and SHALL be audited, as are create and mark-paid.

### Requirement 7 — Worker-wide advance credit

#### Acceptance Criteria

1. THE remaining advance credit SHALL be computed over **all** the worker's payouts, not per
   project, using the FOR-06-11 `AdvanceCreditCalculator` arithmetic.
2. THE credit SHALL never be negative.
3. THE worker card and the payout dialog SHALL show the worker's remaining credit.

### Requirement 8 — Additional payroll lines and previous-month hints

**User Story:** As a financier, I want to add one-off payments (reimbursements, supplements,
deductions) to a worker's month, and quickly repeat last month's ones.

#### Acceptance Criteria

1. AN issuer SHALL be able to create, edit, and delete an additional line `{worker, month, name,
   amount, note}`. `month` is normalized to the first day of the month.
2. `name` SHALL be free text, 1–255 characters after trimming; otherwise 422
   `error.payrollLine.name.required`. It SHALL NOT have i18n variants.
3. `amount` SHALL be a non-zero number (negative allowed, for deductions); zero or non-numeric SHALL
   be rejected with 422 `error.payrollLine.amount.invalid`.
4. Additional lines SHALL count in Earned and Balance of their month and in the **Other payouts**
   column. They SHALL NOT take part in payout suggestions or distribution.
5. THE hints endpoint for `(worker, month M)` SHALL return the worker's lines of month M−1 whose
   trimmed, case-insensitive name does not already exist in month M.
6. Hints SHALL be a visual helper only: nothing is created until the user clicks a hint, which
   pre-fills a new line (name + amount) for month M that the user saves.
7. AN unknown or non-WORKER worker SHALL be rejected with 422 `error.workerPayout.worker.invalid`.

### Requirement 9 — Opening balance and balance chain

#### Acceptance Criteria

1. EACH worker SHALL have an opening balance, default 0 when no profile row exists.
2. AN issuer with `PAYROLL` UPDATE SHALL be able to set the opening balance (any number, may be
   negative); a non-numeric value SHALL be rejected with 422
   `error.payrollProfile.openingBalance.invalid`.
3. THE balance at the end of month M SHALL be opening balance + Σ Earned(≤ M) − Σ Paid(≤ M), chained
   month to month, and SHALL never be closed or reset.
4. THE balance SHALL be computed on read; no balance is persisted.

### Requirement 10 — Company payroll table (read model)

**User Story:** As a financier or manager, I want one table of all workers and their projects for a
period, so I can see what was earned, paid, and is still planned.

#### Acceptance Criteria

1. THE endpoint SHALL take `from`, `to` (required, `from <= to`, else 422
   `error.payroll.period.invalid`) and optional `workerIds`.
2. THE result SHALL contain one row per WORKER user that has, in the period, any report line, payout,
   or additional line (or is in `workerIds`), with: worker name, worker type name, seniority %,
   base-tier-fallback warning flag, Earned, Paid, Reported work, Planned work, Other payouts, and the
   balance at `to`.
3. EACH worker row SHALL contain sub-rows, one per project with report lines in the period, with
   Reported work, Planned work, and Paid (Σ allocation amounts on that project's lines whose payout
   is attributed to the period).
4. Column definitions for the period:
   - **Reported work** = Σ available line costs of the worker's `APPROVED` lines attributed to the
     period;
   - **Planned work** = Σ planned-basis cost (`plan_quantity` costed by the same seam) of those lines;
   - **Other payouts** = Σ additional-line amounts attributed to the period;
   - **Earned** = Reported work + Other payouts;
   - **Paid** = Σ payout amounts attributed to the period.
5. THE table SHALL include a totals row over all visible workers.
6. THE read SHALL NOT be project-filtered: MANAGER and FINANCIER see every worker and project.

### Requirement 11 — Worker payroll card (read model)

**User Story:** As a financier or manager, I want a worker's monthly payroll like the workbook, so I
can settle with them.

#### Acceptance Criteria

1. THE endpoint SHALL take `workerId`, `from`, `to` and return the opening balance, the remaining
   advance credit, the worker type + seniority + fallback flag, and one **month block** per calendar
   month overlapping `[from, to]`.
2. EACH month block SHALL contain:
   - **Earned rows**: one per project (Σ available line costs attributed to the month), each
     drillable to its lines (work item, room, unit, unit cost, quantity, line cost, paid amount,
     payment status);
   - the month's **additional lines**;
   - **Total** = Σ earned rows + Σ additional lines;
   - **Paid rows**: the month's payouts (payout date, amount, channel, status, advance marker, note);
   - **Paid** = Σ paid rows;
   - **Balance** = previous balance + Total − Paid (the first block's previous balance is the balance
     at the end of the month before `from`).
3. THE card SHALL show the previous-month hints (Requirement 8.5) inside the current month's block.

### Requirement 12 — Line payment status on reported work

**User Story:** As a foreman, I want to see whether reported work was paid, so I can answer workers'
questions.

#### Acceptance Criteria

1. THE work-report read DTOs SHALL expose, per room line, `paidAmount` and `paymentStatus`
   (`UNPAID` / `PARTIAL` / `PAID` / `UNAVAILABLE`), and per report an aggregate `paymentStatus`
   (`PAID` if all available lines are paid, `UNPAID` if none has a paid amount, otherwise
   `PARTIAL`; `UNAVAILABLE` if no line has an available cost). Non-`APPROVED` reports SHALL have
   no payment status.
2. These fields SHALL be visible to every caller who can read the work report (existing
   `WORK_REPORTS` ABAC and project scope, unchanged); they SHALL NOT expose payout ids, channels,
   or balances.
3. THE work-reporting list SHALL show a payment badge next to the status for `APPROVED` reports, and
   the report review SHALL show per-room paid amount and badge.

### Requirement 13 — No notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** notifications. Payroll is internal bookkeeping by ADMIN / MANAGER /
   FINANCIER with no handoff to another role; foremen read paid status on demand. Any future
   notification (e.g. "payout paid" to the worker) would use the generic `Notification_Service`
   (FOR-05-07 Requirement 13) and is deferred.

### Requirement 14 — ABAC and surfaces

#### Acceptance Criteria

1. ALL payroll endpoints SHALL stay under the existing `PAYROLL` resource:
   `@PermissionResource("PAYROLL")` with a per-handler `@RequiresPermission` (READ for reads and
   suggestions, CREATE for creating payouts/lines, UPDATE for mark-paid, line edit and opening
   balance, DELETE for deletes). The application SHALL start without a
   `PermissionAnnotationValidator` failure.
2. THE payroll services SHALL no longer be project-scoped (`ProjectScopedService` removed for
   payouts); reads are company-wide for permitted roles.
3. THE `PAYROLL` matrix SHALL be: ADMIN, MANAGER, FINANCIER — CREATE/READ/UPDATE/DELETE (unchanged);
   **FOREMAN — no grant** (the READ grant is revoked by an idempotent changeset); CLIENT, WORKER — no
   grant.
4. THE project-workspace **Payroll tab SHALL be removed** (`WORKSPACE_TABS` entry, its tab key, and
   the `PayrollTab` panel). A stale `/projects/:id/payroll` URL SHALL fall back to the default tab
   (existing unknown-tab behaviour).
5. A new menu item **Payroll** SHALL be added to the `nav.sections.company` section at
   `/company/payroll`, gated on `PAYROLL`/READ, with the route `/company/payroll` (and
   `/company/payroll/:workerId` for the card) registered in the router and in `route-permissions`.

### Requirement 15 — Company payroll screen (frontend)

#### Acceptance Criteria

1. THE page SHALL render the table of Requirement 10 with expandable worker rows (collapsed by
   default), a totals row, money formatted with 2 decimals, and an unavailable value as `—`.
2. THE filter bar SHALL offer a multi-select **workers** filter, **from / to** date inputs, presets
   **Current month** (default) and **Current year**, and **previous / next** buttons that shift the
   window by one month (month preset or a custom range inside one month) or one year (year preset);
   a custom multi-month range shifts by its own length in days.
3. THE filter state (workers, from, to, preset) SHALL persist in `localStorage` under
   `foremen:company-payroll:filters` and be restored on return; an unreadable stored value SHALL
   fall back to the current month.
4. Clicking a worker row SHALL open the worker card at `/company/payroll/:workerId` keeping the
   period; a back action SHALL return to the table with the filters intact.
5. THE card SHALL render the month blocks of Requirement 11, the opening-balance editor, the
   additional-lines editor with hints, and a **Create payout** action (for issuers) opening the payout
   dialog with quick-select **current month / previous month / since last payout / advance**, the
   breakdown with the per-line allocation preview, gross / credit / applied credit / suggested
   amount, an overridable amount, payout date, channel, and note.
6. Mark-paid and delete actions SHALL be shown per payout to users holding UPDATE / DELETE; write
   controls SHALL be hidden for read-only callers.
7. A worker without a worker type SHALL show the warning badge in the table row and the card header.
8. Server errors SHALL be shown inline / as toasts via localized messages; a raw key SHALL never be
   shown.

### Requirement 16 — Internationalization

#### Acceptance Criteria

1. THE backend SHALL add **no** `nameRU`/`namePL` columns: payouts, allocations, profiles, and
   seniority are numbers/transactional data; additional-line `name` is free user text. Worker, work
   item, room, project, and worker-type names use the existing `ru → pl → code` fallback.
2. EVERY new backend message code (`error.workerPayout.worker.invalid`,
   `error.payrollLine.name.required`, `error.payrollLine.amount.invalid`,
   `error.payrollProfile.openingBalance.invalid`, `error.payroll.period.invalid`,
   `error.user.seniorityPct.invalid`) SHALL have PL (`messages.properties`) and RU
   (`messages_ru.properties`) text; the now-unused `error.workerPayout.worker.notMember` SHALL be
   removed from both.
3. THE frontend SHALL add every new string (menu item, page title, table columns, filters, presets,
   shift buttons, card sections, line editor, hints, opening balance, payout dialog additions,
   payment-status badges, seniority field, warnings, empty/loading/error states, toasts) to both
   `pl.json` and `ru.json` with real PL and RU values, per the key list in the design, and remove
   `workspace.tab.payroll`. The locale-parity tests SHALL pass.

### Requirement 17 — Regression and tests

#### Acceptance Criteria

1. FOR-06-01 work reporting (status machine, `WORK_REPORTS` ABAC), FOR-06-06 margin matrix, the
   FOR-07-02 staffing Gantt, and the other workspace tabs SHALL behave as before.
2. THE spec SHALL ship backend unit + jqwik property tests for: the effective-tier formula, the
   distribution (order, conservation `Σ allocations = min(D, gross)`, at most one partial line,
   `paid_amount = Σ allocations`), delete reversibility, worker-wide credit, balance chaining, month
   attribution, hints filtering; integration tests for migrations, the ABAC matrix (FOREMAN revoked)
   and startup validation; and frontend tests for the page, filters + persistence + shift, the card,
   the dialog, the user-form field, work-reporting badges, tab removal, and locale parity.
3. THE spec SHALL ship `test-cases.md` per `.kiro/steering/test-cases.md`.

---

## Round 1 amendments (bug-report E–H, 2026-11-05)

These amend the requirements above from the Round 1 bug-report (items E, F, G, H). Where a clause
revises an existing acceptance criterion, the original is amended in place by the paragraph below.

### Requirement 10.2 (amended) — planning mode: list every worker by default

**Replaces R10.2.** THE result SHALL contain **one row per WORKER user (planning mode, the
default)**, including workers with **no** report line, payout, or additional line in the period —
their period sums are 0, their project breakdown is empty, and their balance at `to` is the running
balance from the opening balance. When `workerIds` is non-empty, exactly those WORKER users are
listed (unchanged). Each row keeps the fields of the original R10.2 plus the status summary of
R10.7.

_Rationale:_ previously the default (unfiltered) branch dropped zero-activity workers
(`if (!filtered && !activity) continue;`), so a worker with nothing planned/reported/paid was
invisible. Planning requires seeing every worker, including idle ones.

### Requirement 10.7 (new) — per-worker payment-status summary column

1. EACH company-table row SHALL carry a derived **payment-status summary** classifying the worker
   from the row's already-computed figures (Reported work, Planned work, Paid, balance at `to`) —
   no extra query.
2. THE status SHALL be one of `FULLY_PAID` | `HAS_DEBT` | `UNREPORTED_WORK` | `NO_ACTIVITY`, resolved
   by this precedence (first match wins):
   1. `NO_ACTIVITY` — no reported, planned or paid work in the period **and** a zero balance at `to`;
   2. `HAS_DEBT` — balance at `to` is strictly positive (the company owes the worker); this wins over
      the planning signal below, so an idle worker still carrying an opening-balance debt is
      `HAS_DEBT`, not `NO_ACTIVITY`;
   3. `UNREPORTED_WORK` — planned work exceeds reported work with no outstanding debt;
   4. `FULLY_PAID` — otherwise (activity exists, balance settled, nothing left to report).
3. THE status SHALL render as a localized badge column on the table; its labels and column header
   SHALL be in both `pl.json` and `ru.json` (`companyPayroll.rowStatus.*`, `companyPayroll.column.status`).

### Requirement 10.8 (new) — create payment from a company-table row

1. FOR a caller holding `PAYROLL`/CREATE, each company-table row SHALL offer a **Create payment**
   control that opens the existing create-payout dialog pre-selecting that row's worker.
2. THE control SHALL be hidden for a caller without `PAYROLL`/CREATE, and activating it SHALL NOT
   open the worker card (it is an independent row affordance).

### Requirement 15.2 (extended) — month quick-select on the filter bar and the worker card

**Extends R15.2 / R15.5.** IN ADDITION to the from/to inputs, presets and prev/next shift, both the
company filter bar **and** the worker card's period bar SHALL offer a **month quick-select**
(a `YYYY-MM` month input) that sets the window to a whole calendar month (first..last day) with the
preset set to MONTH. On the filter bar it goes through the persisted-filters hook (so it persists);
on the card it rewrites the `from`/`to` query params. An invalid month value SHALL be ignored.

_Rationale (bug F):_ the worker card already groups earned/reported/planned work by month
(`workerCard` builds one block per calendar month); the only gap was that the single-month default
range made multi-month data unreachable. The month quick-select (plus prev/next) makes any month and
multi-month window reachable, so no change to the month-grouping read model is required.

### Requirement 8.8 (new) — previous-month payout-line hints in the create-payment dialog

1. A new read endpoint SHALL return, for `(worker, reference month M)`, the worker's **previous
   calendar month (M−1)** payable work-report lines — the unpaid `APPROVED` report lines whose
   report ends on or before the end of M−1, in distribution order (oldest first), **available lines
   only** (a line with an unresolvable cost has no amount to quick-add). Worker-wide (no project);
   `PAYROLL`/READ; reference month defaults to the current month when omitted.
2. This is distinct from the **additional-line** hints of R8.5 (which suggest free-text
   `WorkerPayrollLine` rows): R8.8 suggests **payout/report lines**.
3. THE create-payment dialog SHALL surface these hints with a **quick-add** that seeds the dialog's
   period with the previous month so the live suggestion folds in exactly those lines; the issuer may
   still adjust the resolved `[from, to]`. Hints SHALL be a visual helper only — nothing is created
   until the payout is submitted. Hint labels and the quick-add action SHALL be in both `pl.json`
   and `ru.json` (`companyPayroll.payoutHints.*`).

### Requirement 13 (reaffirmed) — no new notifications for Round 1

The Round 1 changes (planning-mode list, status column, create-from-list, month quick-select,
previous-month payout-line hints) are **read-side UI** plus a reuse of the **existing** payout-create
flow. Consistent with R13, they emit **no** notifications: there is no new cross-role handoff, and
foremen continue to read paid status on demand. This decision is explicit — the i18n/notification
workspace hooks are satisfied by surfacing changes in the read-side UI only.

### Requirement 16 (reaffirmed) — Round 1 i18n scope

1. THE Round 1 backend adds **no** `nameRU`/`namePL` entity columns. The only new entity-ish field is
   the company-row **status enum** (`CompanyPaymentStatus`), which is a derived, non-persisted
   classification surfaced as **localized frontend badges** — not a stored, human-named entity field;
   it therefore needs no backend i18n columns (R16.1 unchanged).
2. EVERY new user-facing string of Round 1 SHALL be added to both `pl.json` and `ru.json`:
   `companyPayroll.column.status`, `companyPayroll.column.actions`, `companyPayroll.rowStatus.*`
   (noActivity / hasDebt / unreportedWork / fullyPaid), `companyPayroll.filter.month`,
   `companyPayroll.payout.createShort`, and `companyPayroll.payoutHints.*` (title / addAll /
   addAllHint). The locale-parity tests SHALL pass.
