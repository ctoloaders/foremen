# Implementation Plan: FOR-06-07 — Client payments
# Implementation Plan: FOR-06-07 — Client payments

## Overview

This plan delivers the internal/admin side of client payments as a full vertical slice: the four
backend entities (`PaymentSchedule`, `PaymentTranche`, `PaymentTransaction`, `TrancheAllocation`)
with their schema → entities → **pure collaborators** (`TrancheGenerator`, `ReconciliationEngine`,
`TrancheStatusDeriver`, all jqwik property-tested) → services (schedule lifecycle, manual scheduler,
reconciliation, transactions, overdue sweep, notification emitter) → provider ports + No-Op stubs →
`@PermissionResource("PAYMENTS")` controllers (schedule, public webhook, bank-feed ingest) → DTOs/
mappers → the `PAYMENTS` ABAC seed, and the frontend Płatności panel wired into the already-reserved
`payments` execution tab.

**Pure-collaborator-first.** All invariant arithmetic/ordering/derivation lives in three pure,
persistence-free collaborators so the 9 correctness properties are property-testable in isolation;
the services are thin apply-layers over them.

**Reused, not re-implemented.** The generic `NotificationService.create(...)` + Bell (FOR-05-07),
the `APPROVED` offer / `OFFER_BASE` total (FOR-05-07/14), `ProjectScopedService` + ABAC
(FOR-03-04/08), and the FOR-05-08 disabled-provider pattern are consumed as-is.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — changesets `171`–`175`, the entities / pure collaborators / services / providers /
  controllers / DTOs / mappers, the `PAYMENTS` ABAC seed, the notification emitter, the backend PL+RU
  message bundle, and this spec. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `features/project-payments/` module, the `payments`
  tab `lazy` swap, the api client/hook, and the `pl.json`/`ru.json` keys. Commit from inside
  `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** New changesets begin at `171` (current changelog tail is `170` —
`170-add-procurement-line-unplanned-category.xml`; if a sibling spec lands further changesets first,
use the next free `NNN`). Each follows the `NOT tableExists` / `SELECT COUNT(*)` / `onFail="MARK_RAN"`
idempotency convention and is appended **last** in `changelog.xml`. The `175-seed-payments-resource`
changeset follows the `017-seed-users-resource.xml` two-changeSet resource+grant pattern.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-07-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks.

## Parallel execution plan (waves)

- **Wave 0 (parallel):** 1.1, 1.2, 1.3, 1.4, 2.1, 2.3, 2.5 — each table changeset is a distinct
  file, and the three pure collaborators are independent persistence-free classes; all mutually
  independent.
- **Wave 1 (parallel):** 2.2, 2.4, 2.6 — the property tests for the three pure collaborators (each
  needs only its own collaborator from wave 0; distinct test files).
- **Wave 2:** 3.1 (JPA entities + enums) — needs the four table changesets.
- **Wave 3:** 3.2 (DTOs + mappers) — needs the entities.
- **Wave 4 (parallel):** 4.1 (provider ports + No-Op stubs), 9.1 (frontend tab `lazy` swap scaffold)
  — independent of each other and of the services.
- **Wave 5:** 5.1 (`PaymentScheduleService`) — needs entities + DTOs + `TrancheGenerator` +
  `TrancheStatusDeriver`.
- **Wave 6 (parallel):** 5.2 (`ManualSchedulerService`), 5.3 (`ReconciliationService`),
  5.4 (`PaymentTransactionService`) — distinct service files over the shared entities/collaborators;
  all depend on 5.1 but not on each other.
- **Wave 7 (parallel):** 4.2 (No-Op provider unit tests), 5.5 (`OverdueSweepService`) — distinct
  files; 4.2 needs only the providers from wave 4, the sweep needs the deriver + schedule service.
- **Wave 8 (parallel):** 5.6 (`PaymentNotificationEmitter`), 6.1 (`PaymentScheduleController`),
  6.2 (`PaymentWebhookController`), 6.3 (`BankFeedIngestController`) — distinct files; the emitter
  needs the reconcile/transaction/sweep transitions to hook `AFTER_COMMIT` (5.3/5.4/5.5), each
  controller needs its backing service + the providers; none of these four depend on each other.
- **Wave 9:** 7.1 (`PAYMENTS` ABAC seed) — the resource/grant changeset; verified against the live
  controllers at startup.
- **Wave 10:** 9.2 (frontend `project-payments` module) — needs the controllers + ABAC seed live.
- **Wave 11 (parallel):** 8.1 (backend unit tests), 8.2 (backend integration + ABAC/startup tests),
  10.1 (i18n PL+RU + backend messages) — distinct test/i18n surfaces, independent.
- **Wave 12 (parallel):** 10.2 (frontend tests), 11 (Author test-cases.md) — 10.2 needs the module +
  i18n (9.2/10.1); test-cases.md needs the full API + UI slice; neither depends on the other.
- **Wave 13:** 12 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: table changesets
  - [x] 1.1 Create the `payment_schedules` table changeset
    - Add `171-create-payment-schedules.xml` (`id` BIGSERIAL PK; `project_id` BIGINT NOT NULL FK
      `projects` + **UNIQUE**; `currency` VARCHAR(3) NOT NULL; `basis_gross` NUMERIC(19,2) NOT NULL
      CHECK >= 0; `source` VARCHAR(16) NOT NULL; `status` VARCHAR(16) NOT NULL DEFAULT `'DRAFT'`;
      `unallocated_credit` NUMERIC(19,2) NOT NULL DEFAULT 0; audit cols). Guard `NOT tableExists` +
      `MARK_RAN`; register last in `changelog.xml`.
    - _Requirements: 1.1, 1.2, 1.6, 5.5_
    - _Depends on: none_
  - [x] 1.2 Create the `payment_tranches` table changeset
    - Add `172-create-payment-tranches.xml` (`id` PK; `schedule_id` BIGINT NOT NULL FK
      `payment_schedules` `ON DELETE CASCADE`; `seq_no` INT NOT NULL; `due_date` DATE NOT NULL;
      `amount_gross` NUMERIC(19,2) NOT NULL CHECK > 0; `paid_gross` NUMERIC(19,2) NOT NULL DEFAULT 0
      CHECK >= 0; `status` VARCHAR(16) NOT NULL DEFAULT `'PENDING'`; `overdue_notified_at`
      TIMESTAMPTZ NULL; UNIQUE(`schedule_id`, `seq_no`); audit cols). Guard `NOT tableExists` +
      `MARK_RAN`; register last in `changelog.xml`.
    - _Requirements: 2.1, 3.2, 3.6, 5.2, 9.3, 10.1, 10.3_
    - _Depends on: none_
  - [x] 1.3 Create the `payment_transactions` table changeset
    - Add `173-create-payment-transactions.xml` (`id` PK; `schedule_id` BIGINT NULL FK
      `payment_schedules` (null = unmatched); `channel` VARCHAR(16) NOT NULL; `external_ref`
      VARCHAR(255) NULL; `amount_gross` NUMERIC(19,2) NOT NULL CHECK >= 0; `fee_gross` NUMERIC(19,2)
      NOT NULL DEFAULT 0; `received_at` TIMESTAMPTZ NOT NULL; `status` VARCHAR(16) NOT NULL; `note`
      TEXT NULL; `recorded_by_id` BIGINT NULL FK `users`; audit cols) **plus** the partial unique
      index `UNIQUE (schedule_id, channel, external_ref) WHERE external_ref IS NOT NULL` for
      idempotency. Guard `NOT tableExists` + `MARK_RAN`; register last in `changelog.xml`.
    - _Requirements: 5.8, 7.4, 8.4, 5.10_
    - _Depends on: none_
  - [x] 1.4 Create the `tranche_allocations` table changeset
    - Add `174-create-tranche-allocations.xml` (`id` PK; `transaction_id` BIGINT NOT NULL FK
      `payment_transactions` `ON DELETE CASCADE`; `tranche_id` BIGINT NOT NULL FK `payment_tranches`;
      `amount` NUMERIC(19,2) NOT NULL CHECK > 0; audit cols). Guard `NOT tableExists` + `MARK_RAN`;
      register last in `changelog.xml`.
    - _Requirements: 5.4, 5.7, 6.2_
    - _Depends on: none_

- [x] 2. Backend: pure collaborators + their property tests
  - [x] 2.1 Implement `TrancheGenerator` (pure)
    - `generate(basisGross, strategy, parts, startDate, periodicity, customDates) →
      List<GeneratedTranche>` with enums `GeneratorStrategy{EQUAL,PERCENT,CUSTOM}` and
      `Periodicity{WEEKLY,BIWEEKLY,MONTHLY,CUSTOM_DATES}`. `EQUAL`: N equal parts to 2 decimals,
      remainder onto the last so `Σ == basisGross`. `PERCENT`: reject `error.payment.generator.percentSum`
      unless Σpct == 100. `CUSTOM`: reject `error.payment.generator.amountSum` unless Σ == basisGross.
      Assign `seqNo` 1..N + `dueDate` by periodicity (or explicit `customDates`); reject `N<1`,
      negative amount/percent, absent start date (`error.payment.generator.invalid`). No persistence,
      no clock.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
    - _Depends on: none_
  - [x] 2.2 Write property test for `TrancheGenerator`
    - **Property 2: GeneratorSumAndSeq** — EQUAL/PERCENT/CUSTOM sum-exactness, ≤2-decimal amounts,
      contiguous `seqNo` {1..N} ordered by dueDate, invalid-input rejection (jqwik, ≥100 iterations).
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 3.2, 3.3, 3.4**
    - _Depends on: 2.1_
  - [x] 2.3 Implement `ReconciliationEngine` (pure allocation planner)
    - `plan(List<OpenTranche> openBySeqNo, BigDecimal amount) → AllocationPlan{ List<Allocation{
      trancheSeqNo, applied }>, leftoverCredit }`. Cascade nearest-open-first by `seqNo`: partial if
      `amount < remaining`, settle on `==`, settle + carry surplus forward on `>`; hold final surplus
      as `leftoverCredit` (no phantom tranche). Conservation `Σ applied + leftoverCredit == amount`;
      never over-settle a tranche. No persistence, no clock.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.7_
    - _Depends on: none_
  - [x] 2.4 Write property test for `ReconciliationEngine`
    - **Property 3: ReconciliationConservation** — conservation, per-tranche cap, strictly-increasing
      nearest-open ordering, leftover-only-after-last-settled, fee exclusion, MANUAL-channel parity
      (jqwik, ≥100 iterations).
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.7, 7.4, 8.5**
    - _Depends on: 2.3_
  - [x] 2.5 Implement `TrancheStatusDeriver` (pure)
    - `derive(paidGross, amountGross, dueDate, now) → TrancheStatus` in the exact R10.1 order
      (`PAID` ≥ amount; else `OVERDUE` if now>due; else `PARTIALLY_PAID` if paid>0; else `DUE` if
      now≥due; else `PENDING`) with enum `TrancheStatus{PENDING,DUE,PARTIALLY_PAID,PAID,OVERDUE}`; and
      `deriveSchedule(List<TrancheStatus>, current) → ScheduleStatus` returning `SETTLED` iff non-empty
      and all `PAID`, else retaining `DRAFT`/`PUBLISHED` (enum `ScheduleStatus{DRAFT,PUBLISHED,SETTLED}`).
      Pure, no persistence.
    - _Requirements: 1.6, 5.6, 10.1, 10.3_
    - _Depends on: none_
  - [x] 2.6 Write property tests for `TrancheStatusDeriver`
    - **Property 1: SettledIff** — `deriveSchedule(T) == SETTLED ⟺ (T non-empty ∧ ∀t PAID)`.
    - **Property 7: StatusDerivation** — `derive` matches the spec ordering exactly; the materialized
      status equals `derive(current fields)` after any mutation (jqwik, ≥100 iterations each).
    - **Validates: Requirements 1.6, 5.6, 10.1, 10.3**
    - _Depends on: 2.5_

- [x] 3. Backend: entities + DTOs
  - [x] 3.1 Add the JPA entities + enums
    - `PaymentScheduleEntity`, `PaymentTrancheEntity`, `PaymentTransactionEntity`,
      `TrancheAllocationEntity` (all `extends BaseEntity`) per design §Data Models (schedule↔project
      UNIQUE; tranche cascade + unique `(schedule, seqNo)`; transaction nullable schedule + partial
      idempotency index; allocation append-only). Enums `TransactionStatus{PENDING,CONFIRMED,FAILED,
      REFUNDED}`, `PaymentChannel{BANK_TRANSFER,ONLINE,MANUAL}`, `ScheduleSource{GENERATED,MANUAL}`
      (reuse `ScheduleStatus`/`TrancheStatus` from 2.5). Repositories for each.
    - _Requirements: 1.1, 1.2, 5.7, 5.8, 8.4_
    - _Depends on: 1.1, 1.2, 1.3, 1.4_
  - [x] 3.2 Add the DTOs + mappers
    - `PaymentScheduleView { id, projectId, currency, basisGross, source, status, tranches[],
      sumAmountGross, sumPaidGross, unpaidRemainder, unallocatedCredit }`, `PaymentTrancheDto`,
      `PaymentTransactionDto` (+ `TrancheAllocationDto[]`), and the inputs `CreateScheduleRequest`,
      `GenerateRequest`, `RePlanRequest`, `ManualTxnInput`, `ManualPayInput`, `AddTrancheInput`,
      `InitiatePayment`; MapStruct mappers. The view computes aggregates + renders derived statuses.
    - _Requirements: 1.4_
    - _Depends on: 3.1_

- [x] 4. Backend: provider ports + No-Op stubs
  - [x] 4.1 Add `OnlinePaymentProvider` / `BankFeedProvider` ports + No-Op defaults
    - Ports: `OnlinePaymentProvider { OnlineInitiateResult initiate(InitiatePayment); boolean
      verifySignature(RawWebhook); OnlineWebhookEvent parse(RawWebhook); }` and `BankFeedProvider {
      List<IncomingBankTxn> poll(); }`. Config-selected (`foremen.payments.online.provider`,
      `foremen.payments.bankfeed.provider`). Defaults `NoOpOnlinePaymentProvider` (initiate → disabled
      result, no throw, nothing CONFIRMED; `verifySignature` → false) and `NoOpBankFeedProvider`
      (`poll` → empty, no throw) — mirroring the FOR-05-08 disabled-provider pattern.
    - _Requirements: 7.1, 7.2, 7.5, 8.1, 8.2, 14.1_
    - _Depends on: none_
  - [x] 4.2 Write unit tests for the No-Op providers
    - `NoOpOnlinePaymentProvider.initiate` returns disabled / no CONFIRMED / no throw;
      `verifySignature` false; `NoOpBankFeedProvider.poll` empty / no throw.
    - _Requirements: 7.2, 7.5, 8.2_
    - _Depends on: 4.1_

- [x] 5. Backend: services
  - [x] 5.1 Implement `PaymentScheduleService` (project-scoped)
    - `implements ProjectScopedService<...>`; `getProjectIdPath() → "project.id"`; `allowedProjectIds`
      → `ProjectAccessCache`. `create` seeds `basisGross` from the `APPROVED` offer / `OFFER_BASE`
      (reject `422 error.payment.schedule.noProjectTotal` when none; `409 error.payment.schedule.exists`
      on the UNIQUE collision). `generate` (DRAFT only; replaces existing tranches via `TrancheGenerator`).
      `publish` (assert `Σ amountGross == basisGross` + ≥1 tranche, else `422
      error.payment.schedule.sumMismatch`; emits `schedulePublished`; refuses un-publish once a
      CONFIRMED allocation exists → `409 error.payment.schedule.cannotUnpublish`). `rePlan` (recompute
      basis to new total, re-author only open tranches to the unpaid remainder, PAID tranches +
      allocations immutable, stay PUBLISHED; negative remainder → zero/close open tranches + surface
      excess as unallocated credit). `get` → `PaymentScheduleView`. Reject non-DRAFT structural edits
      `409 error.payment.schedule.locked`. Status via `TrancheStatusDeriver`.
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 2.6, 4.1, 4.2, 4.3, 4.4, 6.1, 6.2, 6.3, 6.4_
    - _Depends on: 2.1, 2.5, 3.2_
  - [x] 5.2 Implement `ManualSchedulerService`
    - DRAFT: add/edit(amount+dueDate)/re-order/remove, `source=MANUAL`, re-derive contiguous `seqNo`
      1..N by `dueDate`. `addTranche` (add-tranche-anytime, permitted while PUBLISHED): append,
      re-derive `seqNo`, raise `basisGross` by the added amount, keep PUBLISHED, re-derive statuses,
      re-run allocation of held unallocated credit against the new open tranche, never touch a PAID
      tranche/its allocations; preserve `Σ amountGross == basisGross`. Reject negative/zero amount or
      absent due date `422 error.payment.tranche.invalid`. All write paths gated by `PAYMENTS` UPDATE.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.6, 3.7_
    - _Depends on: 5.1_
  - [x] 5.3 Implement `ReconciliationService`
    - `reconcile(confirmed)`: run `ReconciliationEngine.plan(openBySeqNo, amountGross)`, write
      `TrancheAllocation` rows, bump `paidGross`, add `leftoverCredit` to `schedule.unallocatedCredit`,
      re-derive each touched tranche via `TrancheStatusDeriver`, set `SETTLED` iff all PAID.
      `markTranchePaid(trancheId, ManualPayInput)`: record a `MANUAL`/`CONFIRMED` transaction for the
      remaining amount, allocate to **exactly that tranche** (not the cascade), set
      `paidGross = amountGross`, derive PAID, attribute `recordedBy`, accept optional note +
      received-at; idempotent (PAID → no-op; PARTIALLY_PAID → settle only the remainder). Gated by
      `PAYMENTS` UPDATE.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.9, 5.10, 5.11, 5.12_
    - _Depends on: 5.1_
  - [x] 5.4 Implement `PaymentTransactionService`
    - Idempotency by `(schedule, channel, externalRef)` (already-recorded CONFIRMED ref = no-op).
      `ingestBankTransaction` (channel=BANK_TRANSFER, CONFIRMED → reconcile; uncorrelated → record
      unmatched, no fail). `onOnlineWebhook` (CONFIRMED → reconcile; FAILED → FAILED; reversal →
      REFUNDED; emit `onlineFailed` on failure/reversal; `feeGross` recorded under `Payer_Borne_Fee`,
      never deducted). `recordManual` (channel=MANUAL). Delegates allocation to `ReconciliationService`.
    - _Requirements: 5.8, 7.3, 7.4, 8.3, 8.4, 8.5_
    - _Depends on: 5.1_
  - [x] 5.5 Implement `OverdueSweepService` (scheduled)
    - A `@Scheduled` task re-deriving tranche statuses across PUBLISHED schedules; fire
      `trancheOverdue` **exactly once** per tranche on first crossing into OVERDUE, guarded by the
      persisted `overdueNotifiedAt` marker so later sweeps do not re-notify. Overdue also re-derived
      on read.
    - _Requirements: 9.3, 10.2_
    - _Depends on: 5.1_
  - [x] 5.6 Implement `PaymentNotificationEmitter` (best-effort)
    - An `AFTER_COMMIT` `@TransactionalEventListener` wrapping `NotificationService.create(recipient,
      type, body, deepLink)` for the six R9.2 triggers (received / tranchePaid / trancheOverdue /
      amountMismatch → MANAGER+FINANCIER; schedulePublished → CLIENT (FOR-09 deep-link); onlineFailed
      → MANAGER+FINANCIER). Failures logged and swallowed — never roll back the triggering transition.
      Deep-links to the Płatności tab (CLIENT row to FOR-09).
    - _Requirements: 9.1, 9.2, 9.4, 9.5_
    - _Depends on: 5.3, 5.4, 5.5_

- [x] 6. Backend: controllers (ABAC-wired)
  - [x] 6.1 Add `PaymentScheduleController`
    - `@RestController @PermissionResource("PAYMENTS")` exposing all schedule/tranche/transaction/
      action endpoints, each with a matching method-level `@PermissionOperation`/`@RequiresPermission`
      mapping to standard CRUD (`create`/`generate`/`publish` → CREATE/UPDATE; `addTranche`/`rePlan`/
      `markTranchePaid`/`recordManual` → UPDATE; reads → READ) — no new ABAC operation. The reusable
      initiate-online-pay + read-schedule server actions are exported for FOR-09. App starts clean
      (`PermissionAnnotationValidator`).
    - _Requirements: 5.9, 7.6, 11.1, 11.3, 11.4, 12.2, 14.3_
    - _Depends on: 4.1, 5.1, 5.2, 5.3, 5.4_
  - [x] 6.2 Add `PaymentWebhookController` (public)
    - `POST /api/payments/webhook/online` — intentionally unguarded (no `@PermissionResource`, like
      `AuthController` per `entity-creation-rules`), verifies the provider signature when credentials
      are present (reject `401 error.payment.webhook.unverified`; No-Op accepts no live webhook),
      parses to a CONFIRMED/FAILED/REFUNDED event by `externalRef`, and delegates to
      `PaymentTransactionService.onOnlineWebhook`.
    - _Requirements: 7.3, 7.5_
    - _Depends on: 4.1, 5.4_
  - [x] 6.3 Add `BankFeedIngestController` (internal)
    - `@PermissionResource("PAYMENTS")` internal trigger/poll endpoint (ingest = UPDATE) that pulls
      `BankFeedProvider.poll()` and feeds each line through
      `PaymentTransactionService.ingestBankTransaction` (idempotent by `externalRef`; uncorrelated →
      unmatched). Method-level `@PermissionOperation` UPDATE; app starts clean.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 11.4_
    - _Depends on: 4.1, 5.4_

- [x] 7. Backend: ABAC seed
  - [x] 7.1 Seed the `PAYMENTS` resource + role matrix
    - Add `175-seed-payments-resource.xml` (two changeSets per the `017-seed-users-resource.xml`
      pattern): changeSet 1 inserts the `PAYMENTS` resource row (+ PL/RU name/description); changeSet 2
      grants the project-scoped role matrix — ADMIN CRUD, MANAGER CRUD(own), FINANCIER CRUD(own),
      FOREMAN R(own), CLIENT R(own); WORKER no grant. Each guarded by `sqlCheck` /
      `SELECT COUNT(*)` + `onFail="MARK_RAN"` + per-row `NOT EXISTS`; registered **last** in
      `changelog.xml`. `(own)` scoping enforced by `ProjectScopedService`.
    - _Requirements: 11.1, 11.2_
    - _Depends on: 1.1, 1.2, 1.3, 1.4_

- [x] 8. Backend: unit + integration + property-dependent tests
  - [x] 8.1 Backend unit tests (services)
    - `PaymentScheduleService.create` (seed from APPROVED offer R1.2; reject no-total R1.3; duplicate
      R1.1); `publish` (happy path R4.1; sum-mismatch/zero-tranche reject R4.2; cannot-unpublish R4.4);
      `PaymentTransactionService` (webhook CONFIRMED/FAILED/REFUNDED mapping R7.3; uncorrelated →
      unmatched R8.4; `feeGross` recorded not deducted R7.4); notification recipient mapping
      (MANAGER+FINANCIER vs CLIENT R9.2). Run only these classes with `--tests` →
      `/tmp/for-06-07-test.log`; verify via JUnit XML.
    - **Property 4: AllocationIdempotency** — re-processing by `(schedule, channel, externalRef)` is a
      no-op (R5.8, 8.3).
    - **Property 5: MarkPaidIdempotency** — targets its tranche, settles only the remainder, second
      call no-op (R5.9, 5.10, 5.11).
    - **Property 6: RePlanImmutability** — PAID tranches + allocations immutable, open tranches re-sum
      to the unpaid remainder (R6.1–6.4).
    - **Property 9: BestEffortEmission** — a throwing `NotificationService` never rolls back the
      transition (R9.1, 9.5).
    - **Validates: Requirements 1.1, 1.2, 1.3, 4.1, 4.2, 4.4, 5.8, 5.9, 5.10, 5.11, 6.1, 6.2, 6.3, 6.4, 7.3, 7.4, 8.3, 8.4, 9.1, 9.2, 9.5**
    - _Depends on: 5.1, 5.2, 5.3, 5.4, 5.6_
  - [x] 8.2 Backend integration + ABAC/startup tests (Dockerized-scoped classes)
    - Bank-feed ingest → CONFIRMED → reconcile wiring, idempotent on re-poll (R8.3); manual
      mark-tranche-paid end-to-end downstream (tranchePaid + allocation + SETTLED roll-up R5.12);
      webhook signature-verification path when credentials present (R7.5). ABAC: `PAYMENTS` reachable
      only with the right `(resource, operation)` + project membership; WORKER denied; FOREMAN
      read-only; `PermissionAnnotationValidator` passes at startup with `PaymentScheduleController`
      fully annotated.
    - **Property 8: OverdueOnce** — one-shot overdue emission across repeated sweeps (R9.3, 10.2).
    - **Validates: Requirements 5.12, 7.5, 8.3, 9.3, 10.2, 11.1, 11.2, 11.3, 11.4**
    - _Depends on: 5.5, 6.1, 6.2, 6.3, 7.1_

- [x] 9. Frontend: tab wiring + payments panel
  - [x] 9.1 Swap the `payments` tab panel
    - In the workspace tab registry, change the `payments` entry's `lazy` from `PlaceholderTab` to the
      new `PaymentsTab`. Do not touch the `WORKSPACE_TABS` contract shape, `resolveWorkspaceTabs`, or
      `stageOf`; no parallel tab is added.
    - _Requirements: 12.1_
    - _Depends on: none_
  - [x] 9.2 Build the `features/project-payments/` module
    - `components/PaymentsTab.tsx` (schedule header: basis, Σ paid, remainder, unallocated credit,
      derived status), `components/TrancheTable.tsx` (rows with derived `TrancheStatus` label + amount
      + due date + mark-as-paid action), `components/GeneratorForm.tsx` (strategy/parts/startDate/
      periodicity + client-side sum validation), `components/ManualSchedulerPanel.tsx` (add/edit/
      reorder/remove DRAFT + add-tranche-anytime PUBLISHED), `components/PublishButton.tsx` +
      `components/RePlanDialog.tsx`, `components/TransactionsList.tsx` (transactions + per-tranche
      allocations + MANUAL-record action + unmatched surface), `hooks/usePaymentSchedule.ts` (REST
      data hook + the exported initiate-online-pay action for FOR-09). Every write control gated via
      `usePermission` on the matching `PAYMENTS` operation and read-only/hidden for FOREMAN; every enum
      rendered via `Enum_Label_I18n`.
    - _Requirements: 7.6, 12.2, 12.3, 12.4, 14.3_
    - _Depends on: 6.1, 7.1, 9.1_

- [x] 10. i18n + frontend tests
  - [x] 10.1 Add i18n keys (PL + RU, mirrored) + backend messages
    - Add the `payments.*` UI block + every enum label (`payments.scheduleStatus.*`,
      `payments.trancheStatus.*`, `payments.transactionStatus.*`, `payments.channel.*`,
      `payments.generator.strategy.*`, `payments.generator.periodicity.*`) + the six
      `notification.payment.*` titles to both `pl.json` and `ru.json` (PL primary); add every
      `error.payment.*` code to the backend PL + RU message bundle. Run the locale-parity test.
      (`workspace.tab.payments` already seeded by FOR-06-00 — reuse unchanged.)
    - _Requirements: 13.1, 13.2, 13.4_
    - _Depends on: 5.1, 5.6, 9.2_
  - [x] 10.2 Frontend unit tests
    - `GeneratorForm` client-side sum validation, `TrancheTable` derived-status label rendering,
      `PaymentsTab` permission-gated controls (FOREMAN read-only), enum-label rendering (no raw
      constant), and the locale-parity test.
    - _Requirements: 2.2, 12.3, 12.4, 13.2_
    - _Depends on: 9.2, 10.1_

- [x] 11. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template; authored in Russian). This spec has **both** surfaces: the
    schedule/tranche/transaction/reconciliation/webhook/bank-feed-ingest flow is **API-tested** against
    the Dockerized app (`docker compose up`, `http://localhost:8080`, `/api/payments...` +
    `/api/payments/webhook/online`) — schedule create/generate/publish, reconcile via MANUAL record,
    mark-paid, re-plan; and the **Płatności admin tab** is a browser-engine UI scenario (schedule
    header, generator/manual-scheduler controls, publish/re-plan, transactions + mark-as-paid,
    FOREMAN read-only, enum labels PL/RU). The regression group covers idempotency (duplicate
    externalRef), re-plan PAID-immutability, and the one-shot overdue notification. Result artifacts
    are MD reports with tables.
  - _Requirements: 1.2, 2.2, 3.3, 4.1, 5.1, 5.8, 5.9, 6.1, 7.3, 8.3, 9.3, 11.2, 12.2, 13.2_
  - _Depends on: 6.1, 6.2, 6.3, 7.1, 9.2, 10.1_

- [x] 12. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes with `--tests`; type-check + unit-test the frontend; confirm the locale-parity test
    passes and the app starts (no `PermissionAnnotationValidator` failure). Confirm the `payments` tab
    renders the real panel for a permitted caller on an `ACTIVE` project and the create → generate →
    publish → reconcile (MANUAL) → mark-paid → re-plan cycle works end-to-end, with the No-Op providers
    leaving the system unchanged. Ensure all tests pass, ask the user if questions arise. Then stage
    and commit per repo (two commits — root + `foremen-frontend/`) — only if the user asks.
  - _Requirements: all_
  - _Depends on: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 4.1, 4.2, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 6.1, 6.2, 6.3, 7.1, 8.1, 8.2, 9.1, 9.2, 10.1, 10.2, 11_

## Notes

- Tasks marked with `*` are optional (property / unit / integration tests) and can be skipped for a
  faster MVP; the core implementation tasks are never optional.
- Each task references the specific requirement acceptance criteria it covers and declares its
  dependencies; the dependency graph is a DAG.
- The three pure collaborators (`TrancheGenerator`, `ReconciliationEngine`, `TrancheStatusDeriver`)
  are implemented and property-tested before the services that apply them, so the 9 correctness
  properties are validated in isolation.
- Backend/spec/infra changes commit from the **root** repo; the `features/project-payments/` module
  and the `pl.json`/`ru.json` keys commit from inside **`foremen-frontend/`** (two commits).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4", "2.1", "2.3", "2.5"] },
    { "id": 1, "tasks": ["2.2", "2.4", "2.6"] },
    { "id": 2, "tasks": ["3.1"] },
    { "id": 3, "tasks": ["3.2"] },
    { "id": 4, "tasks": ["4.1", "9.1"] },
    { "id": 5, "tasks": ["5.1"] },
    { "id": 6, "tasks": ["5.2", "5.3", "5.4"] },
    { "id": 7, "tasks": ["4.2", "5.5"] },
    { "id": 8, "tasks": ["5.6", "6.1", "6.2", "6.3"] },
    { "id": 9, "tasks": ["7.1"] },
    { "id": 10, "tasks": ["9.2"] },
    { "id": 11, "tasks": ["8.1", "8.2", "10.1"] },
    { "id": 12, "tasks": ["10.2", "11"] },
    { "id": 13, "tasks": ["12"] }
  ]
}
```
