# Implementation Plan: FOR-07-06 — Company payroll (worker-level, cross-project)

## Overview

This plan reshapes the implemented FOR-06-11 payroll to the worker level and moves it to the
company screen `/company/payroll`. Order: schema → pure collaborators (jqwik) → entities/DAOs →
cost seam (user worker type + seniority) → payroll services → controllers → backend tests →
frontend (locales, module move + tab removal, company page, worker card, navigation, user form,
work-reporting badges).

**Spans two git repos** (`.kiro/steering/git-repo-structure.md`):
- **Root repo** — changesets 192–198, backend code and tests, message bundles, this spec. Commit from
  the workspace root.
- **Frontend repo** (`foremen-frontend/`) — `features/company-payroll/`, removal of the project
  payroll tab, navigation/router/route-permissions, user form, work-reporting badges, locales.
  Commit from inside `foremen-frontend/`.

**Migrations.** Next free changeset is `192` (tail: `191-rooms-sort-order.xml`). If a sibling spec
lands changesets first, take the next free numbers in the same order. No data migration.

**Test-run policy (backend).** Never the full suite (~20 min). Run only affected classes with
`--tests`, redirect to `/tmp/for-07-06-test.log`, read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` first. Frontend: `npx vitest --run
<file>` from `foremen-frontend/`.

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 2.1, 2.2, 2.3, 2.4, 2.5, 8.1, 8.2 — the schema changesets (one task,
  since every changeset edits `changelog.xml`), the five independent pure collaborators (distinct
  files), the frontend locale keys, and the pure date-range lib + persisted-filter hook.
- **Wave 2 (parallel):** 1.2, 3.1 — the FOREMAN revoke changeset (edits `changelog.xml` after 1.1) and
  the entity/DAO changes (need the 1.1 columns/tables). Distinct files.
- **Wave 3 (parallel):** 4.1, 4.2 — cost seam (`WorkerCostService`, `WorkerCostDerivation`,
  `UserDao` query) and user seniority (`UserService`, user DTOs/mappers). Distinct files.
- **Wave 4 (parallel):** 5.1, 5.3, 5.4, 5.5, 8.8 — suggestion service, lines/profile services, read
  model, work-report payment status (distinct services), and the frontend user-form field (needs
  4.2 API).
- **Wave 5 (parallel):** 5.2, 6.2, 8.9 — payout service + allocation writer (needs 5.1), payroll
  controller (needs 5.3, 5.4), work-reporting badges (needs 5.5). Distinct files/repos.
- **Wave 6 (parallel):** 6.1, 6.3, 7.1 — payout controller (needs 5.2), message bundles (needs the
  final code set from 4.2/5.2/5.3), backend unit/property test updates (distinct test files).
- **Wave 7 (parallel):** 7.2, 8.3 — backend integration tests (need controllers + 1.2 + 6.3) and the
  frontend module move + tab removal (needs the 6.1 API shape and 8.1 keys). Distinct repos.
- **Wave 8:** 8.4 — company API client/hooks/types (needs 6.2 and the moved module from 8.3).
- **Wave 9 (parallel):** 8.5, 8.6 — company page/filter/table and worker card (distinct components;
  both need 8.4).
- **Wave 10:** 8.7 — navigation, router, route-permissions (needs both pages).
- **Wave 11:** 9 — `test-cases.md` (already authored with the spec; re-check against the final
  implementation).
- **Wave 12:** 10 — final checkpoint.

## Tasks

- [x] 1. Schema (root repo)
  - [x] 1.1 Create changesets 192–197 and register them in `changelog.xml`
    - `192-payroll-report-line-paid-amount.xml`: drop FK `fk_work_report_rooms_paid_by_payout` and
      column `work_report_rooms.paid_by_payout_id` (guard `columnExists`); add `paid_amount
      NUMERIC(19,2) NOT NULL DEFAULT 0` + CHECK `>= 0` (guard `NOT columnExists`).
    - `193-worker-payouts-worker-wide.xml`: drop `ix_worker_payouts_project`, the `project_id` FK and
      column; add `payout_date DATE NOT NULL DEFAULT CURRENT_DATE`; add index
      `ix_worker_payouts_worker_date (worker_id, payout_date)`.
    - `194-create-worker-payout-allocations.xml`, `195-create-worker-payroll-lines.xml`,
      `196-create-worker-payroll-profiles.xml`, `197-add-user-seniority-pct.xml` per the design table
      (FKs, CHECKs, unique/indices, audit columns, `NOT tableExists` / `NOT columnExists` guards,
      `onFail="MARK_RAN"`).
    - Append the six includes last in `changelog.xml`, each with a comment.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9_
    - _Depends on: none_
  - [x] 1.2 Create changeset 198 revoking FOREMAN `PAYROLL`/READ
    - `198-revoke-foreman-payroll-read.xml`: delete the FOREMAN `role_resource_operations` and
      `role_resources` rows for `PAYROLL`, guarded by a `sqlCheck` (MARK_RAN when already absent);
      XML comment documents FOREMAN as deny-by-default (paid status is visible via work reports).
    - Register last in `changelog.xml`.
    - _Requirements: 14.3_
    - _Depends on: 1.1_

- [x] 2. Pure collaborators + property tests (root repo)
  - [x] 2.1 `EffectiveTier` + `EffectiveTierPropertyTest`
    - In `service/workercost/`: `EffectiveTier.of(typeOrNull, seniorityPct, baseType)` →
      `WorkerCostDerivation.ResolvedTier` per the design (non-base: `uplift(tierPct + s)`; base:
      base tier when s = 0, else `uplift(s)`; null/inactive: base path + `baseTierFallback = true`).
    - Properties 1–2: `unitCost = baseCost × (1 + u + s)`, s = 0 equals the FOR-06-10 value,
      fallback flag set exactly when the type is missing/inactive.
    - _Requirements: 2.2, 2.3, 2.4_
    - _Depends on: none_
  - [x] 2.2 `PayoutDistributor` + `PayoutDistributorPropertyTest`
    - In `service/payroll/`: pure `distribute(BigDecimal d, List<PayableLine>)` →
      `Distribution{allocations, surplus}`; scale 2 HALF_UP.
    - Property 3: conservation, per-line cap, prefix order, at most one partial line, surplus.
    - _Requirements: 5.3, 5.4, 5.5_
    - _Depends on: none_
  - [x] 2.3 `MonthAttribution` + `BalanceCalculator` + property tests
    - `MonthAttribution` (line → `performedTo` month, payout → `payoutDate` month, additional line →
      `month`); `BalanceCalculator.chain(opening, earnedByMonth, paidByMonth, months)`.
    - Properties 7–8: chain recurrence, start = opening, each item in exactly one month.
    - _Requirements: 9.3, 9.4, 10.4, 11.2_
    - _Depends on: none_
  - [x] 2.4 `PayrollHintSelector` + `PayrollHintSelectorPropertyTest`
    - Pure filter: previous-month lines whose trimmed, case-insensitive name is absent in the current
      month; duplicates collapse to the latest.
    - Property 9.
    - _Requirements: 8.5_
    - _Depends on: none_
  - [x] 2.5 `LinePaymentStatus` / `ReportPaymentStatus` + property test
    - Enum `UNPAID / PARTIAL / PAID / UNAVAILABLE`; `of(cost, paidAmount)`; report aggregate rule.
    - Property 10.
    - _Requirements: 12.1_
    - _Depends on: none_

- [x] 3. Persistence (root repo)
  - [x] 3.1 Entities and DAOs
    - `WorkReportRoomEntity`: remove `paidByPayout`, add `paidAmount`.
    - `WorkerPayoutEntity`: remove `project`, add `payoutDate`.
    - New `WorkerPayoutAllocationEntity`, `WorkerPayrollLineEntity`, `WorkerPayrollProfileEntity`
      and DAOs (`WorkerPayoutAllocationDao.findByPayoutId`, `findByLineIds`;
      `WorkerPayrollLineDao.findByWorkerIdsAndMonthBetween`, `findByWorkerAndMonth`;
      `WorkerPayrollProfileDao.findByWorkerId(s)`).
    - `UserEntity.seniorityPct`.
    - `WorkerPayoutDao`: replace project queries with `findByWorker`, `findByWorkersAndPayoutDateLte`.
    - `WorkReportRoomDao`: `findPayableLines(workerId, periodTo)` + `@Lock(PESSIMISTIC_WRITE)`
      variant, `findApprovedLinesForWorkers(workerIds, to)` for the read model; remove
      `findByPaidByPayoutId`. `WorkReportDao.findApprovedInPeriodWithRooms` loses the project
      predicate and the `paidByPayout` fetch (renamed `findApprovedForWorkerInPeriodWithRooms`).
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 4.2, 4.3_
    - _Depends on: 1.1_

- [x] 4. Cost seam and user seniority (root repo)
  - [x] 4.1 `WorkerCostService` resolves the user's worker type + seniority
    - Add `UserDao.findWorkerProfiles(ids)` (JOIN FETCH `workerType`) and
      `WorkerCostService.workerProfilesById(ids)`; resolve tiers through `EffectiveTier`; remove the
      member-based map and `activeWorkerTypesByWorkerId(projectId)`; `costForProject` and `costFor`
      use the new resolution; `WorkerCostLineDto` gains `seniorityPct`.
    - Leave `MarginMatrixService` untouched; adjust `WorkerCostFor0606ParityPropertyTest` to pin
      parity only where user type = member type and seniority = 0, with a comment on the divergence.
    - Update `WorkerCostService` unit tests (`WorkerCostServiceUserTypeTest`).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_
    - _Depends on: 2.1, 3.1_
  - [x] 4.2 `seniorityPct` on the user API
    - `UserCreateRequest`, `UserUpdateRequest`, `UserDtoExtendedModel`, service models, controller and
      service mappers gain `seniorityPct`; `UserService.resolveSeniority(role, value)` (non-WORKER →
      0, null → 0, outside [0,1] → 400 `error.user.seniorityPct.invalid`) wired in
      `validateCreate/Update` and `create/update`.
    - `UserServiceSeniorityTest`.
    - _Requirements: 3.1, 3.2, 3.3, 3.5_
    - _Depends on: 3.1_

- [x] 5. Payroll services (root repo)
  - [x] 5.1 `PayoutSuggestionService` worker-wide
    - `suggest(workerId, from, to, advance)`: candidate lines from `findPayableLines`, remainder =
      cost − `paidAmount`, unavailable lines excluded, gross, worker-wide credit, applied, suggested,
      breakdown with project + paid + remainder + allocation preview (`PayoutDistributor`); advance:
      planned basis across all projects, no credit, no preview. `payableLines(...)` for create.
    - Extend `PayoutLineDto`; update `PayoutSuggestionServiceTest`.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 7.1_
    - _Depends on: 2.2, 4.1_
  - [x] 5.2 `WorkerPayoutService` reshape + `AllocationWriter`
    - Replace `ProjectScopedService` with company-wide `AdminService` plumbing; methods `suggest`,
      `create`, `markPaid`, `delete`, `list(PayoutFilter)`, `allocations(id)`.
    - `create`: validation order (period, channel, amount, `assertWorker`), advance derivation,
      locked candidate lines, save with `payoutDate`, distribute `amount + advanceApplied`,
      `AllocationWriter.write` (rows + `paid_amount +=`), audit.
    - `delete`: PLANNED only; advance → worker-wide consumed check; regular →
      `AllocationWriter.release` (`paid_amount -=`), audit, hard delete.
    - Delete `ReportLineLinker`; drop `SINCE_PROJECT_START` from `PeriodResolver`; worker-wide
      `SINCE_LAST_PAYOUT`.
    - Update `WorkerPayoutServiceTest`; replace `PayoutLinkageExactReversiblePropertyTest` with
      `AllocationReversiblePropertyTest` (properties 4–6).
    - _Requirements: 5.1, 5.2, 5.3, 5.6, 5.7, 5.8, 5.9, 6.1, 6.2, 6.3, 6.4, 7.1, 7.2_
    - _Depends on: 5.1_
  - [x] 5.3 `PayrollLineService`, `PayrollProfileService`
    - Lines CRUD with month normalization, name/amount validation, worker check; hints via
      `PayrollHintSelector`; opening-balance get/upsert; audit on writes.
    - `PayrollLineServiceTest`, `PayrollProfileServiceTest`.
    - _Requirements: 8.1, 8.2, 8.3, 8.5, 8.6, 8.7, 9.1, 9.2_
    - _Depends on: 2.4, 3.1_
  - [x] 5.4 `PayrollReadModelService` (table + card)
    - Batched loads per data kind for the selected workers, costing via `WorkerCostService`
      (`EffectiveTier`), `MonthAttribution`, `BalanceCalculator`; table rows + project sub-rows +
      totals + `balanceAtTo`; card month blocks, previous balance, hints for the current month;
      `workerTypeMissing` flag. DTOs per the design.
    - `PayrollReadModelServiceTest` (incl. table balance = card last-block balance).
    - _Requirements: 9.3, 9.4, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 11.1, 11.2, 11.3, 7.3_
    - _Depends on: 2.3, 2.5, 3.1, 4.1_
  - [x] 5.5 Payment status on work-report DTOs
    - Work-report read mapping adds per-room `paidAmount` + `paymentStatus` and the report aggregate
      (APPROVED only), costed once per page via `WorkerCostService`; no payout data exposed.
    - Update the affected `WorkReportService` tests.
    - _Requirements: 12.1, 12.2_
    - _Depends on: 2.5, 4.1_

- [x] 6. Controllers and messages (root repo)
  - [x] 6.1 `WorkerPayoutController` reshape
    - Remove `projectId` params; add `GET /{id}/allocations`; keep `@PermissionResource("PAYROLL")`
      + per-handler `@RequiresPermission` (READ/CREATE/UPDATE/DELETE).
    - _Requirements: 4.1, 5.1, 6.1, 14.1, 14.2_
    - _Depends on: 5.2_
  - [x] 6.2 `PayrollController` (`/api/payroll`)
    - Endpoints: `company`, `workers/{id}/card`, `workers/{id}/hints`, `lines` POST/PUT/DELETE,
      `workers/{id}/opening-balance` PUT; `error.payroll.period.invalid` on `from > to`; annotations
      per the design table.
    - _Requirements: 8.1, 9.2, 10.1, 11.1, 14.1_
    - _Depends on: 5.3, 5.4_
  - [x] 6.3 Backend message bundles
    - Add the six new codes to `messages.properties` (PL) and `messages_ru.properties` (RU) with the
      design texts; remove `error.workerPayout.worker.notMember` from both.
    - _Requirements: 16.1, 16.2_
    - _Depends on: 4.2, 5.2, 5.3_

- [x] 7. Backend verification (root repo)
  - [x] 7.1 Unit/property test updates
    - Adjust `WorkerPayoutLifecyclePropertyTest`, `PayoutGrossViaCostSeamPropertyTest`,
      `AdvanceCreditCalculatorPropertyTest` (worker-wide input), `PeriodResolverPropertyTest`
      (option removed); delete tests of removed classes. Run all payroll/workercost/user test classes
      with `--tests` → `/tmp/for-07-06-test.log`; all green.
    - _Requirements: 17.2_
    - _Depends on: 4.2, 5.2, 5.4, 5.5_
  - [x] 7.2 Integration tests
    - `CompanyPayrollMigrationIntegrationTest` (192–198 idempotent re-run, columns dropped/added,
      FOREMAN `PAYROLL` grant absent); `CompanyPayrollIntegrationTest` (two projects, oldest-first
      distribution, one partial line, delete reversal, concurrent create does not over-allocate,
      advance credit worker-wide); update `PayrollAbacStartupIntegrationTest` (startup OK; FOREMAN
      403 on `/api/payroll/**` and `/api/worker-payouts/**`; MANAGER/FINANCIER 200 without project
      filter); remove/replace `PayrollMigrationIntegrationTest` assertions on the dropped columns.
    - _Requirements: 1.8, 1.9, 5.8, 14.1, 14.3, 17.1, 17.2_
    - _Depends on: 1.2, 6.1, 6.2, 6.3_

- [x] 8. Frontend (foremen-frontend repo)
  - [x] 8.1 Locale keys PL + RU
    - Add every `companyPayroll.*`, `nav.companyPayroll`, `users.form.seniority*`,
      `users.validation.seniorityRange`, `payroll.error.workerInvalid` key from the design to
      `pl.json` and `ru.json`; remove `workspace.tab.payroll`, `payroll.period.sinceProjectStart`,
      `payroll.error.workerNotMember`; add `locale-parity.for0706.test.ts`; update
      `locale-parity.for0611.test.ts` for the removed keys.
    - _Requirements: 16.3_
    - _Depends on: none_
  - [x] 8.2 Date-range lib + persisted filters hook
    - `features/company-payroll/lib/dateRange.ts` (presets, `shift`) and
      `hooks/usePersistedPayrollFilters.ts` (`foremen:company-payroll:filters`, validated parse,
      fallback current month); `dateRange.property.test.ts` (property 11) and
      `usePersistedPayrollFilters.test.ts`.
    - _Requirements: 15.2, 15.3_
    - _Depends on: none_
  - [x] 8.3 Move the payroll module, remove the project tab
    - Move dialog, breakdown, row actions, `lib/*`, types, api, hooks from
      `features/project-execution/payroll/` to `features/company-payroll/`; drop `projectId` from api
      calls and query keys; remove `SINCE_PROJECT_START`; add payout date and the allocation preview
      columns to the dialog/breakdown.
    - Delete `PayrollTab.tsx`; remove the `payroll` entry + lazy import in `workspaceTabs.ts`, the tab
      key from `types.ts`, and hard-coded `'payroll'` in the three workspace state property tests;
      replace `payrollTab.resolver.test.ts` with `payrollTab.removed.test.ts`; move/update
      `CreatePayoutDialog.test.tsx` and `PayoutBreakdownTable.test.tsx`.
    - _Requirements: 4.6, 5.1, 14.4, 15.5_
    - _Depends on: 6.1, 8.1_
  - [x] 8.4 Company API client, hooks, types
    - `api/company-payroll-api.ts` (table, card, hints, lines CRUD, opening balance, allocations),
      `hooks/useCompanyPayroll.ts` with the design query keys; mutations invalidate
      `['companyPayroll']`; types per the DTO sketches.
    - _Requirements: 8.1, 9.2, 10.1, 11.1_
    - _Depends on: 6.2, 8.3_
  - [x] 8.5 Company payroll page, filter bar, table
    - `CompanyPayrollPage`, `PayrollFilterBar` (workers multi-select, from/to, presets, prev/next,
      reset), `CompanyPayrollTable` (expandable rows with `aria-expanded`, sub-rows, totals, `—` for
      unavailable, worker-type warning badge, row click → card keeping the period).
    - Tests: `CompanyPayrollPage.test.tsx`, `CompanyPayrollTable.test.tsx`,
      `PayrollFilterBar.test.tsx`.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.7, 15.8_
    - _Depends on: 8.2, 8.4_
  - [x] 8.6 Worker payroll card
    - `WorkerPayrollCard`, `PayrollMonthBlock` (earned per project with drill-down, additional
      lines, total, payouts with mark-paid/delete, paid, previous balance, balance),
      `AdditionalLinesEditor`, `PreviousMonthHints` (click pre-fills a new line), `OpeningBalanceEditor`,
      `WorkerTypeWarning`, Create payout action opening the moved dialog; write controls gated by
      `usePermission('PAYROLL', …)`.
    - Tests: `WorkerPayrollCard.test.tsx`, `AdditionalLinesEditor.test.tsx`.
    - _Requirements: 8.4, 8.6, 11.2, 11.3, 15.5, 15.6, 15.7, 15.8_
    - _Depends on: 8.3, 8.4_
  - [x] 8.7 Navigation, router, route permissions
    - Company section item `/company/payroll` (`nav.companyPayroll`, icon `wallet`, `PAYROLL`/READ);
      lazy routes `company/payroll` and `company/payroll/:workerId`; route-permissions extra for the
      `:workerId` template; `route-permissions.for0706.test.ts`; update `navigation.test.ts`.
    - _Requirements: 14.5, 15.4_
    - _Depends on: 8.5, 8.6_
  - [x] 8.8 Seniority field in the user form
    - Schema field (0–100 %), `UserFormSheet` field in the `isWorkerRole` block
      (`data-testid="user-seniority-field"`), percent ↔ fraction conversion on load/submit, types;
      `UserFormSheet` test for show/hide, conversion and range validation.
    - _Requirements: 3.1, 3.2, 3.4_
    - _Depends on: 4.2, 8.1_
  - [x] 8.9 Payment-status badges in work reporting
    - `PaymentStatusBadge`; render in `WorkReportingPanel` (APPROVED rows) and `WorkReportReview`
      (per-room paid amount + badge); types; `PaymentStatusBadge.test.tsx` + panel test update.
    - _Requirements: 12.3_
    - _Depends on: 5.5, 8.1_

- [x] 9. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group,
    MD report template). Part A: API tests against the Dockerized app; Part B: browser-engine
    scenarios for the company payroll screen, worker card, user form, and work-reporting badges.
    Result artifacts are MD reports with tables.
  - _Requirements: 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 17.3_
  - _Depends on: 1.1, 1.2, 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 4.1, 4.2, 5.1, 5.2, 5.3, 5.4, 5.5, 6.1, 6.2, 6.3, 7.1, 7.2, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9_

- [ ] 10. Final checkpoint
  - Backend: affected test classes green (log + JUnit XML); `./gradlew compileJava compileTestJava`
    clean. Frontend: `npm run lint`, `npm run build`, and the spec's vitest files green.
  - `docker compose up`: migrations 192–198 apply; smoke `/company/payroll` as FINANCIER and MANAGER;
    FOREMAN has no menu item and sees payment badges on reports.
  - Update `PROGRESS.md` and the FOR-07 `OVERVIEW.md` status; commit in both repos only when asked.
  - _Requirements: 17.1, 17.2_
  - _Depends on: 1.1, 1.2, 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 4.1, 4.2, 5.1, 5.2, 5.3, 5.4, 5.5, 6.1, 6.2, 6.3, 7.1, 7.2, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 9_
