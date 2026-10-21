# Implementation Plan: FOR-06-11 — Payroll (worker payouts)

## Overview

This plan delivers worker payroll as a full vertical slice: the new project-scoped
`WorkerPayout` entity + the nullable `paidByPayout` FK on the existing FOR-06-01
`WorkReportRoom` → **pure collaborators** (`AdvanceCreditCalculator`, `AdvanceDeriver`,
`PeriodResolver`, all jqwik property-tested) → the cost-folding `PayoutSuggestionService` and the
FK-mutating `ReportLineLinker` → the lifecycle `WorkerPayoutService` (`ProjectScopedService`) →
`@PermissionResource("PAYROLL")` `WorkerPayoutController` → DTOs/mappers → the `PAYROLL` ABAC seed,
and the frontend **Rozliczenie wynagrodzeń** panel wired into the already-reserved `payroll`
execution tab.

**Pure-collaborator-first.** All invariant arithmetic/derivation lives in three pure,
persistence-free collaborators so the correctness properties are property-testable in isolation; the
service is a thin apply-layer over them. The cost itself is **never re-derived** —
`PayoutSuggestionService` folds FOR-06-10 `WorkerCostService`'s `Cost_Value` verbatim (no second cost
formula, Non-Goal / R3.1).

**Reused, not re-implemented.** FOR-06-10 `WorkerCostService` (the single cost seam), the FOR-06-01
`WorkReportRoom` report line + `APPROVED` status (status machine unchanged — only the new FK is
set/cleared), FOR-05-09 `ProjectMember` (worker picker + ACTIVE-member check), `ProjectScopedService`
+ ABAC (FOR-03-04/08), and the FOR-06-00 reserved `payroll` tab slot are consumed as-is.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — changesets `180`–`182`, the `WorkerPayoutEntity` + the `paidByPayout` FK on the
  existing `WorkReportRoomEntity`, the pure collaborators / services / controller / DTOs / mappers,
  the `PAYROLL` ABAC seed, the backend PL+RU message bundle, and this spec. Commit from the workspace
  root.
- **Frontend repo** (`foremen-frontend/`) — the `features/project-execution/payroll/` module, the
  `payroll` tab `lazy` swap, the api client/hook, and the `pl.json`/`ru.json` keys. Commit from
  inside `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** New changesets begin at `180` (current changelog tail is `179` —
`179-seed-amendments-resource.xml`; if a sibling spec lands further changesets first, use the next
free `NNN`). Each follows the `NOT tableExists` / `NOT columnExists` / `SELECT COUNT(*)` /
`onFail="MARK_RAN"` idempotency convention and is appended **last** in `changelog.xml`. The
`182-seed-payroll-resource` changeset follows the `017-seed-users-resource.xml` two-changeSet
resource+grant pattern.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-11-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks.

## Parallel execution plan (waves)

- **Wave 0 (parallel):** 1.1, 2.1, 2.2, 2.3 — the `worker_payouts` table changeset is a distinct
  file, and the three pure collaborators are independent persistence-free classes; all mutually
  independent. (The `181` FK changeset depends on `180` existing as the FK target, so it is wave 1.)
- **Wave 1 (parallel):** 1.2, 2.4, 2.5, 2.6 — `181` FK changeset (targets the `worker_payouts` table
  from 1.1); and the property tests for the three pure collaborators (each needs only its own
  collaborator from wave 0; distinct test files).
- **Wave 2:** 3.1 (JPA entities + enums + the `paidByPayout` FK field) — needs both table/column
  changesets.
- **Wave 3:** 3.2 (DTOs + mappers) — needs the entities.
- **Wave 4:** 4.1 (`PayoutSuggestionService` + `ReportLineLinker`) — need the entities/DTOs + the
  FOR-06-10 cost seam.
- **Wave 5:** 5.1 (`WorkerPayoutService`) — needs the suggestion/linker collaborators + the pure
  calculators + DTOs.
- **Wave 6 (parallel):** 6.1 (`WorkerPayoutController`), 9.1 (frontend `payroll` tab `lazy` swap
  scaffold) — distinct files/repos; the controller needs the service, the tab swap is independent.
- **Wave 7:** 7.1 (`PAYROLL` ABAC seed) — the resource/grant changeset; verified against the live
  controller at startup.
- **Wave 8:** 9.2 (frontend `payroll` module) — needs the controller + ABAC seed live + the tab swap.
- **Wave 9 (parallel):** 8.1 (backend unit tests), 8.2 (backend integration + ABAC/startup tests),
  10.1 (i18n PL+RU + backend messages) — distinct test/i18n surfaces, independent.
- **Wave 10 (parallel):** 10.2 (frontend tests), 11 (Author test-cases.md) — 10.2 needs the module +
  i18n (9.2/10.1); test-cases.md needs the full API slice; neither depends on the other.
- **Wave 11:** 12 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: schema changesets
  - [x] 1.1 Create the `worker_payouts` table changeset
    - Add `180-create-worker-payouts.xml` (`id` BIGSERIAL PK; `project_id` BIGINT NOT NULL FK
      `projects`; `worker_id` BIGINT NOT NULL FK `users`; `period_from` DATE NOT NULL; `period_to`
      DATE NOT NULL; `amount` NUMERIC(19,2) NOT NULL CHECK >= 0; `suggested_amount` NUMERIC(19,2)
      NOT NULL CHECK >= 0; `advance_applied` NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK >= 0;
      `payment_channel` VARCHAR(16) NOT NULL; `status` VARCHAR(16) NOT NULL DEFAULT `'PLANNED'`;
      `is_advance` BOOLEAN NOT NULL; `paid_at` TIMESTAMPTZ NULL; `note` TEXT NULL; CHECK
      `period_to >= period_from`; audit cols). Guard `NOT tableExists` + `MARK_RAN`; register last in
      `changelog.xml`.
    - _Requirements: 1.1, 1.3, 4.5_
    - _Depends on: none_
  - [x] 1.2 Add the `work_report_rooms.paid_by_payout_id` FK column changeset
    - Add `181-add-work-report-room-paid-by-payout.xml` (`addColumn work_report_rooms.paid_by_payout_id
      BIGINT NULL` + FK to `worker_payouts` `ON DELETE SET NULL`). Guard `NOT columnExists` +
      `MARK_RAN`; register last in `changelog.xml`. No new `WorkReport` status, no FOR-06-01 machine
      change.
    - _Requirements: 1.3, 6.1_
    - _Depends on: 1.1_

- [x] 2. Backend: pure collaborators + their property tests
  - [x] 2.1 Implement `AdvanceCreditCalculator` (pure)
    - `remaining(List<WorkerPayoutEntity>) → BigDecimal` = `Σ(advance amounts) − Σ(advanceApplied on
      regular payouts)`, floored at 0 (never negative); `applied(remainingCredit, gross) →
      min(remaining, gross)`; `suggested(gross, remainingCredit) → max(0, gross − remaining)`. All
      `NUMERIC(19,2)` / `RoundingMode.HALF_UP`. No persistence, no clock.
    - _Requirements: 3.2, 5.5, 5.6_
    - _Depends on: none_
  - [x] 2.2 Implement `AdvanceDeriver` (pure)
    - `isAdvance(LocalDate periodTo, LocalDate createdDate) → periodTo.isAfter(createdDate)`. The sole
      classification rule — derived from the dates alone, never a user-set flag.
    - _Requirements: 5.3_
    - _Depends on: none_
  - [x] 2.3 Implement `PeriodResolver` (pure)
    - `resolve(QuickSelect option, LocalDate today, LocalDate projectStart, LocalDate
      lastPayoutPeriodTo) → DateRange` with enum `QuickSelect{CURRENT_MONTH, PREVIOUS_MONTH,
      SINCE_LAST_PAYOUT, SINCE_PROJECT_START, ADVANCE}`: current/previous-month calendar bounds;
      since-last-payout from `lastPayoutPeriodTo` (else `projectStart`); since-project-start from
      `projectStart`; advance = a forward-looking interval whose `periodTo` is after `today` (so
      `isAdvance` holds). No persistence.
    - _Requirements: 5.1, 5.2, 5.4_
    - _Depends on: none_
  - [x] 2.4 Write property tests for `AdvanceCreditCalculator`
    - **Property 2: SuggestedAndAppliedCredit** — for any `gross ≥ 0`, `remainingCredit ≥ 0`:
      `appliedCredit == min(remaining, gross)`, `suggested == max(0, gross − remaining)` (jqwik,
      ≥100 iterations).
    - **Property 3: AdvanceCreditBalance** — for any sequence of advance + regular payouts the
      remaining credit equals `Σ(advances) − Σ(advanceApplied)`, never negative; a regular payout
      decrements by exactly `min(remaining, gross)`, an advance increments by exactly its amount.
    - **Validates: Requirements 3.2, 5.5, 5.6**
    - _Depends on: 2.1_
  - [x] 2.5 Write property test for `AdvanceDeriver`
    - **Property 5: IsAdvanceDerivation** — for any `(periodTo, createdDate)`, `isAdvance` is true iff
      `periodTo` is strictly after `createdDate` (jqwik, ≥100 iterations).
    - **Validates: Requirements 5.3**
    - _Depends on: 2.2_
  - [x] 2.6 Write property test for `PeriodResolver`
    - **Property 6: PeriodResolver** — for any reference date, current/previous month resolve to the
      respective calendar-month bounds; since-last-payout resolves from the latest `periodTo` (else
      project start); since-project-start from the project start; advance resolves forward-looking so
      `periodTo` is after the reference date (jqwik, ≥100 iterations).
    - **Validates: Requirements 5.1, 5.4**
    - _Depends on: 2.3_

- [x] 3. Backend: entities + DTOs
  - [x] 3.1 Add the `WorkerPayoutEntity` + enums + the `paidByPayout` FK on `WorkReportRoomEntity`
    - `WorkerPayoutEntity extends BaseEntity` per design §Data Models (`project`/`worker` FKs,
      `periodFrom`/`periodTo`, `amount`, `suggestedAmount`, `advanceApplied`, `paymentChannel`,
      `status`, `isAdvance`, `paidAt`, `note`; no `nameRU`/`namePL` columns). Enums
      `PaymentChannel{BANK_TRANSFER, CASH}` and `PayoutStatus{PLANNED, PAID}`. Add the nullable
      `@ManyToOne` `paidByPayout` FK field to the **existing** `WorkReportRoomEntity` (FOR-06-01),
      with the R6.1/R6.5 doc comment; the FOR-06-01 status machine is untouched. Repository for
      `WorkerPayout`.
    - _Requirements: 1.1, 2.1, 6.1, 8.1_
    - _Depends on: 1.2_
  - [x] 3.2 Add the DTOs + mappers
    - `PayrollView { projectId, payouts: WorkerPayoutDto[], workers: WorkerCreditDto[] }`,
      `WorkerCreditDto { workerId, workerName, remainingAdvanceCredit }`, `WorkerPayoutDto`,
      `PayoutSuggestionDto { gross, remainingCredit, appliedCredit, suggested, isAdvance,
      breakdown: PayoutLineDto[] }`, `PayoutLineDto { workReportRoomId, workItemName, roomName,
      periodFrom, periodTo, workerTypeName, baseTierFallback, quantity, unitServiceCost, costValue,
      available }`; inputs `CreatePayoutRequest { workerId, periodFrom, periodTo, paymentChannel,
      amount, note? }`, `PayrollFilter { workerId?, status? }`. MapStruct mappers; names read with the
      `ru → pl → code` fallback.
    - _Requirements: 1.4, 3.3, 11.1_
    - _Depends on: 3.1_

- [x] 4. Backend: cost-folding suggestion + FK linker collaborators
  - [x] 4.1 Implement `PayoutSuggestionService` + `ReportLineLinker`
    - `PayoutSuggestionService.suggest(projectId, workerId, from, to)`: for a **non-advance** period
      read the worker's unpaid (`paidByPayout` null) `APPROVED` report lines in `[from, to]` via
      FOR-06-10 `WorkerCostService` **verbatim**, sum available `Cost_Value` into `gross`, compute
      `appliedCredit`/`suggested` via `AdvanceCreditCalculator`, and build the per-line breakdown
      (each line's work item, room, period, resolved tier + base-tier-fallback marker, qty, unit
      `Worker_Service_Cost`, line `Cost_Value`, `available`); an unavailable (null/zero-rate) line is
      **marked unavailable and excluded** from `gross` (never a fabricated zero) and never linked. For
      an **advance** period use the worker's **planned** work (assigned items × planned qty via
      `WorkerCostService`) as basis, applying **no** credit deduction. Adds no cost arithmetic of its
      own. `ReportLineLinker`: within a payout create/delete transaction only, set `paidByPayout` on
      every covered unpaid `APPROVED` line / clear it on every line referencing a deleted payout;
      never runs for an advance; never reachable from the FOR-06-01 surface.
    - _Requirements: 3.1, 3.3, 3.4, 3.5, 6.2, 6.4, 6.5_
    - _Depends on: 2.1, 3.2_

- [x] 5. Backend: service
  - [x] 5.1 Implement `WorkerPayoutService` (project-scoped)
    - `implements ProjectScopedService<...>`; `getProjectIdPath() → "project.id"`; `allowedProjectIds`
      → `ProjectAccessCache` (R10.2, R10.5). `suggest` delegates to `PayoutSuggestionService` (R3).
      `create`: validate period (`periodTo >= periodFrom`, else `422
      error.workerPayout.period.invalid`), channel (`422 error.workerPayout.channel.required`), amount
      (`≥ 0`, else `422 error.workerPayout.amount.invalid`), ACTIVE membership (`422
      error.workerPayout.worker.notMember`); derive `isAdvance` via `AdvanceDeriver`; persist `PLANNED`
      with `suggestedAmount` snapshot and `amount` defaulting to it (overridable, independent of
      linkage/credit). For an **advance**: `advanceApplied = 0`, link no line, increase the worker's
      remaining credit by `amount`. For a **regular** payout, in **one transaction**: set
      `advanceApplied = min(remaining, gross)`, decrement the credit, link every covered unpaid
      `APPROVED` line via `ReportLineLinker` (R4.2, 4.3, 4.7, 5.5, 5.6). `markPaid`: `PLANNED → PAID`,
      set `paidAt`, freeze (`409 error.workerPayout.status.illegalTransition` otherwise) (R7.1, 7.2).
      `delete`: hard-delete `PLANNED` only, release linked lines + reverse credit; reject a `PAID`
      delete (R7.2), and reject an advance delete whose credit a later regular payout already consumed
      (`409 error.workerPayout.advance.alreadyApplied`) (R5.7, 7.3). `list` → `PayrollView` with the
      per-worker remaining credit (R11.1, 5.8). Write an audit row on create/pay/delete (R12.1). Emit
      **no** notifications (R9.1).
    - _Requirements: 1.4, 1.5, 2.2, 3.1, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 5.5, 5.6, 5.7, 5.8, 6.3, 7.1, 7.2, 7.3, 9.1, 10.2, 10.5, 11.1, 12.1_
    - _Depends on: 2.1, 2.2, 2.3, 3.2, 4.1_

- [x] 6. Backend: controller (ABAC-wired)
  - [x] 6.1 Add `WorkerPayoutController`
    - `@RestController @RequestMapping("/api/worker-payouts") @PermissionResource("PAYROLL")` exposing
      `GET ?projectId=&workerId=&status=` (READ, list + per-worker credit), `GET /suggest` (READ,
      dialog preview), `POST` (CREATE), `POST /{id}/pay` (UPDATE, mark paid), `DELETE /{id}` (DELETE,
      hard-delete `PLANNED` only). Each in-scope handler carries a matching
      `@PermissionOperation`/`@RequiresPermission` mapping to standard CRUD — **no new ABAC
      operation**; mark-paid → UPDATE, delete-planned → DELETE. App starts clean
      (`PermissionAnnotationValidator` classifies the controller COMPLETE).
    - _Requirements: 4.1, 7.4, 10.3, 11.1_
    - _Depends on: 5.1_

- [x] 7. Backend: ABAC seed
  - [x] 7.1 Seed the `PAYROLL` resource + role matrix
    - Add `182-seed-payroll-resource.xml` (two changeSets per the `017-seed-users-resource.xml`
      pattern): changeSet A inserts the `PAYROLL` resource row (+ PL/RU name/description), guarded
      `SELECT COUNT(*) FROM resources WHERE code='PAYROLL' = 0`; changeSet B+ grants the project-scoped
      role matrix — ADMIN CRUD, MANAGER CRUD, FINANCIER CRUD, FOREMAN READ; CLIENT + WORKER no grant
      (deny-by-default, explanatory XML comment). Each guarded by `sqlCheck` / `SELECT COUNT(*)` +
      `onFail="MARK_RAN"`; registered **last** in `changelog.xml`. `(own)`-style scoping enforced by
      `ProjectScopedService`.
    - _Requirements: 7.4, 10.1, 10.4, 10.6_
    - _Depends on: 1.1_

- [x] 8. Backend: unit + integration + property-dependent tests
  - [x] 8.1 Backend unit tests (service + suggestion)
    - `create` happy paths — a regular payout links lines + applies credit + sets `PLANNED` (R4.2); an
      advance adds credit + links no line (R5.5). Error edges — missing channel (R2.2), negative
      amount (R4.4), invalid period (R4.5), non-member worker (R4.6), illegal transition on a `PAID`
      payout (R7.2), advance-already-applied delete (R5.7). `suggest` — breakdown carries every
      per-line field (R3.3) and renders an unavailable line as excluded (R3.4). No-notification
      assertion — create/pay/delete trigger **zero** `NotificationService` calls (R9.1). Enum
      definition — `PaymentChannel` is exactly `{BANK_TRANSFER, CASH}` (R2.1). Run only these classes
      with `--tests` → `/tmp/for-06-11-test.log`; verify via JUnit XML.
    - **Property 1: GrossViaCostSeam** — gross == Σ FOR-06-10 `Cost_Value` over the unpaid in-period
      available lines; unavailable lines excluded, adding one leaves gross unchanged (R3.1, 3.4).
    - **Property 4: LinkageExactReversible** — link-on-create / release-on-delete identity on the FK
      state, advance-links-none, paid/payable predicate, override-independence of the linked set +
      `advanceApplied` (R1.2, 4.3, 4.4, 6.1–6.4).
    - **Property 7: SuggestedSnapshotImmutable** — `suggestedAmount` is a creation snapshot unchanged
      by later ops; `amount` defaults to it and may be any `≥ 0` override (R1.4, 4.2).
    - **Property 8: Lifecycle** — `PLANNED → PAID` only; `PAID` terminal/immutable; delete rules
      (R7.1–7.3).
    - **Validates: Requirements 1.2, 1.4, 2.1, 2.2, 3.1, 3.3, 3.4, 4.2, 4.3, 4.4, 4.5, 4.6, 5.5, 5.7, 6.1, 6.2, 6.3, 6.4, 7.1, 7.2, 7.3, 9.1**
    - _Depends on: 5.1_
  - [x] 8.2 Backend integration + ABAC/startup tests (Dockerized-scoped classes)
    - **Transactional create** — force a mid-create failure; assert nothing persisted (no payout, no
      FK change, no credit drift) (R4.7). **Project scope** — a read never returns another project's
      payouts; a write is confined to the resolved project (R10.5). **Audit** — a create/pay/delete
      each write an audit row (R12.1). **Regression** — FOR-06-10 `WorkerCostService`, FOR-06-01 work
      reporting (+ the new `paidByPayout` FK, status machine unchanged), FOR-06-06 margin matrix, and
      the FOR-05/FOR-06-00 workspace shell + tab resolver continue to pass (R12.2). ABAC/startup —
      `PAYROLL` reachable only with the right `(resource, operation)` + project membership; FOREMAN
      read-only; CLIENT/WORKER denied; `PermissionAnnotationValidator` passes at startup with
      `WorkerPayoutController` fully annotated.
    - **Property 9: RoleMatrix** — exact `PAYROLL` grants per role + project scope (R7.4, 10.4, 10.5).
    - **Property 10: StageGating** — payroll reachable iff `ACTIVE`/`COMPLETED`, absent on `DRAFT`
      (R10.1).
    - **Property 11: SeedIdempotency** — re-applying changesets 180–182 yields no duplicate
      table/column/resource/grant (R1.3, 10.4).
    - **Property 13: StartupAnnotationCompleteness** — `WorkerPayoutController` classified COMPLETE
      (R10.3).
    - **Validates: Requirements 1.3, 4.7, 7.4, 10.1, 10.3, 10.4, 10.5, 12.1, 12.2**
    - _Depends on: 6.1, 7.1_

- [x] 9. Frontend: tab wiring + payroll panel
  - [x] 9.1 Swap the `payroll` tab panel
    - In the workspace tab registry (`features/project-workspace/workspaceTabs.ts`), change the
      `payroll` entry's `lazy` from `PlaceholderTab` to the new `PayrollTab`. Do not touch the
      `WORKSPACE_TABS` contract shape, `resolveWorkspaceTabs`, or `stageOf`; no parallel tab is added.
    - _Requirements: 10.6_
    - _Depends on: none_
  - [x] 9.2 Build the `features/project-execution/payroll/` module
    - `PayrollTab.tsx` (payout list: worker, period, advance marker, suggested amount, applied advance
      credit, paid amount, channel, status; worker/status filters; per-worker remaining advance
      credit), `components/CreatePayoutDialog.tsx` (worker pick; period quick-select — current month /
      previous month / since last payout / since project start / advance payment — with an adjustable
      `[from, to]`; the unpaid-work (or planned-work) summary; the gross / remaining-credit /
      applied-credit / suggested figures; the overridable amount; the channel selector; the note),
      `components/PayoutBreakdownTable.tsx` (per-line: work item, room, period, resolved tier +
      base-fallback marker, qty, unit `Worker_Service_Cost`, line `Cost_Value`, unavailable `—`),
      `components/PayoutRowActions.tsx` (mark-as-paid + delete planned-only with confirm; status
      badge), `hooks/usePayroll.ts` (REST hook over list / suggest / create / pay / delete). Every
      write control gated via `usePermission` on the matching `PAYROLL` operation and hidden for a
      FOREMAN (READ only); every enum (`PayoutStatus`, `PaymentChannel`) rendered via the enum-label
      i18n convention; an unavailable cost renders a localized `—`, never a raw key.
    - _Requirements: 8.4, 10.4, 11.1, 11.2, 11.3, 11.4, 11.5_
    - _Depends on: 6.1, 7.1, 9.1_

- [x] 10. i18n + frontend tests
  - [x] 10.1 Add i18n keys (PL + RU, mirrored) + backend messages
    - Add the `payroll.*` UI block + every enum label (`payroll.status.*` for `PLANNED`/`PAID`,
      `payroll.channel.*` for `BANK_TRANSFER`/`CASH`), the create-dialog labels/placeholders (worker,
      period quick-select options, the summary table headers, the gross/remaining-credit/applied-credit/
      suggested fields + the amount override hint, the channel selector, the note), the advance marker,
      the remaining-advance-credit indicator, the base-tier-fallback marker, and all success/error
      toasts to both `pl.json` and `ru.json` (PL primary, RU at parity, no English fallback); add every
      `error.workerPayout.*` code to the backend PL + RU message bundle. Run the locale-parity test.
      (`workspace.tab.payroll` already seeded by FOR-06-00 — reuse unchanged.)
    - _Requirements: 8.2, 8.3, 8.4_
    - _Depends on: 5.1, 9.2_
  - [x] 10.2 Frontend unit tests
    - `CreatePayoutDialog` — period quick-select resolution display, the gross / remaining-credit /
      applied-credit / suggested figures, the amount override, the channel selector (R11.2).
      `PayrollTab` — list columns, worker/status filters, the advance marker, the per-worker remaining
      credit, status/channel badges (R11.1, 11.3, 11.4). `PayoutBreakdownTable` — an unavailable cost
      renders a localized `—`, never a raw key (R11.5). Resolver fixtures — `payroll` tab present on
      `ACTIVE`/`COMPLETED`, absent on `DRAFT` (R10.1, 10.6); the locale-parity test.
    - **Property 12: LocaleParity** — identical non-empty key sets across `pl.json`/`ru.json` + the
      backend bundle (R2.3, 8.2, 8.3).
    - **Validates: Requirements 2.3, 8.2, 8.3, 10.1, 10.6, 11.1, 11.2, 11.3, 11.4, 11.5**
    - _Depends on: 9.2, 10.1_

- [x] 11. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template; authored in Russian). This is an **API-centric** spec: the
    scenarios are HTTP requests against the Dockerized app (`docker compose up`,
    `http://localhost:8080`, auth under `/api/auth`, `/api/worker-payouts...`) — the suggested-amount
    preview, create a regular payout (link lines + apply credit), create an advance (add credit), mark
    paid, delete (release lines + reverse credit), the double-payment guard (a linked line not
    re-offered), the advance-already-applied delete rejection, the `PAYROLL` ABAC matrix
    (ADMIN/MANAGER/FINANCIER vs FOREMAN read-only vs a denied CLIENT/WORKER), and the project-scope
    gate. The regression group covers linkage release-on-delete and the advance-credit running
    balance. Result artifacts are MD reports with tables.
  - _Requirements: 3.1, 4.1, 4.2, 5.5, 5.6, 5.7, 6.2, 6.3, 7.1, 7.3, 10.1, 10.4, 10.5, 12.3_
  - _Depends on: 6.1, 7.1, 9.2, 10.1_

- [x] 12. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes with `--tests` (→ `/tmp/for-06-11-test.log`, verify via JUnit XML); type-check +
    unit-test the frontend; confirm the locale-parity test passes and the app starts (no
    `PermissionAnnotationValidator` failure). Confirm the `payroll` tab renders the real panel for a
    permitted caller on an `ACTIVE` project and the suggest → create (regular, links + credit) →
    create (advance, adds credit) → mark-paid → delete (release + reverse) cycle works end-to-end, the
    double-payment guard holds, and no notifications are emitted. Ensure all tests pass, ask the user
    if questions arise. Then stage and commit per repo (two commits — root + `foremen-frontend/`) —
    only if the user asks.
  - _Requirements: all_
  - _Depends on: 1.1, 1.2, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 4.1, 5.1, 6.1, 7.1, 8.1, 8.2, 9.1, 9.2, 10.1, 10.2, 11_

## Notes

- Tasks marked with `*` are optional (property / unit / integration tests) and can be skipped for a
  faster MVP; the core implementation tasks are never optional.
- Each task references the specific requirement acceptance criteria it covers and declares its
  dependencies; the dependency graph is a DAG.
- The three pure collaborators (`AdvanceCreditCalculator`, `AdvanceDeriver`, `PeriodResolver`) are
  implemented and property-tested before the service that applies them, so the arithmetic/derivation
  correctness properties are validated in isolation. The cost itself is **not** re-tested here —
  FOR-06-10 owns it; Property 1 asserts only that the gross equals the sum of its `Cost_Value`.
- Backend/spec/infra changes commit from the **root** repo; the `features/project-execution/payroll/`
  module and the `pl.json`/`ru.json` keys commit from inside **`foremen-frontend/`** (two commits).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.2", "2.3"] },
    { "id": 1, "tasks": ["1.2", "2.4", "2.5", "2.6"] },
    { "id": 2, "tasks": ["3.1"] },
    { "id": 3, "tasks": ["3.2"] },
    { "id": 4, "tasks": ["4.1"] },
    { "id": 5, "tasks": ["5.1"] },
    { "id": 6, "tasks": ["6.1", "9.1"] },
    { "id": 7, "tasks": ["7.1"] },
    { "id": 8, "tasks": ["9.2"] },
    { "id": 9, "tasks": ["8.1", "8.2", "10.1"] },
    { "id": 10, "tasks": ["10.2", "11"] },
    { "id": 11, "tasks": ["12"] }
  ]
}
```
