# Implementation Plan: FOR-06-02 — Plan/actual Gantt overlay

## Overview

This plan delivers the plan/actual overlay as a **read-only analytics slice**: a pure
`TrajectoryCalculator` helper, a `PlanActualService` assembler that joins the planned bars
(FOR-05-10/10b), the planned quantities (estimate), and the reported facts (FOR-06-01) into a
transient `PlanActualView`, a new READ endpoint on the existing schedule controller, and a read-only
frontend panel wired into the already-reserved `planActual` execution tab.

**No new entity, no new table, no Liquibase changeset, and no new ABAC resource.** The overlay rides
the existing `WORK_SCHEDULE` project-scoped resource at READ, so
`.kiro/steering/entity-creation-rules.md` does **not** apply (no resource seed / `@PermissionResource`
on a new entity / `getProjectIdPath()` on a new service — the schedule stack already has them).

**Ownership boundary.** This spec reads three worlds (plan dates, plan quantity, actual reports) and
computes completion + trajectory on the fly. It writes no domain data, computes no cost/margin/payroll
(FOR-06-06/10/11), and emits no notifications (requirements R8).

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — the `PlanActualView` DTO family, `TrajectoryCalculator`, `PlanActualService`, the
  new READ handler on `ProjectScheduleController`, and this spec. Commit from the workspace root.
  **No changeset.**
- **Frontend repo** (`foremen-frontend/`) — the `plan-actual` feature folder, the `planActual` tab
  `lazy` swap, the API client, and the `pl.json`/`ru.json` keys. Commit from inside
  `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** None — this spec adds no changeset (read-only overlay).

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-02-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks.

## Parallel execution plan (waves)

- **Wave 0 (parallel):** 1.1 (trajectory helper), 1.2 (DTO family), 4.1 (frontend tab `lazy` swap
  scaffold) — mutually independent (a pure helper, a set of DTO records, and an isolated frontend
  registry edit).
- **Wave 1:** 2.1 (`PlanActualService` assembler) — needs the DTOs + the trajectory helper.
- **Wave 2:** 3.1 (controller READ handler) — needs the service.
- **Wave 3:** 4.2 (frontend panel + overlay + at-risk list + drill-down + api) — needs the endpoint
  live.
- **Wave 4 (parallel):** 5.1 (i18n PL+RU), 5.2 (backend tests), 5.3 (frontend tests) — independent.
- **Wave 5:** 6 (Author test-cases.md) — needs the full slice.
- **Wave 6:** 7 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: trajectory helper + DTOs
  - [x] 1.1 Add the `TrajectoryCalculator` pure helper
    - Add `com.foremen.service.schedule.TrajectoryCalculator` (sibling of `ScheduleCalculator`):
      `classify(elapsedShare, confirmedCompletion, riskMargin)` → `TrajectoryStatus`
      (`ON_TRACK`/`AT_RISK`/`OVERDUE`) and `projectFinishDay(minStartDay, plannedDurationDays,
      elapsedShare, confirmedCompletion)` → `OptionalDouble`, per design §4.2 (completion ≥ 1 ⇒
      ON_TRACK; elapsed ≥ 1 ∧ completion < 1 ⇒ OVERDUE; completion < elapsed − riskMargin ⇒ AT_RISK;
      projected finish pace-extrapolated + horizon cap). `riskMargin` + horizon read from config
      (`foremen.plan-actual.*`), not hard-coded.
    - _Requirements: 4.2, 4.3, 4.4, 4.6, 7.4_
    - _Depends on: none_
  - [x] 1.2 Add the `PlanActualView` DTO family
    - In `com.foremen.controller.model.schedule`, add `PlanActualView`, `PlanActualTotals`,
      `PlanActualCategory`, `PlanActualBar`, `PlanActualCell`, `PlanActualReportRef`,
      `PlanActualMoney`, and the `TrajectoryStatus` enum per design §4.4 (localized names carried as
      strings; money block nullable for non-money-viewers; per-worker bars carried from
      `ScheduleView`; per-cell drill-down with report refs).
    - _Requirements: 1.2, 1.3, 1.4, 6.5_
    - _Depends on: none_

- [x] 2. Backend: plan/actual assembler
  - [x] 2.1 Add `PlanActualService`
    - Add `com.foremen.service.PlanActualService` with `getPlanActual(projectId)` per design §4.1:
      frame from the schedule read (anchor, finishDay, category rows, per-worker bars, version,
      computedAt) under the same project scope; index planned quantity from `EstimateLineRoomQty`;
      fold reported quantity from `WorkReport`/`WorkReportRoom` into confirmed (APPROVED) / pending
      (SUBMITTED+CHANGES_REQUESTED) / rejected (REJECTED) buckets keeping report refs; per category
      compute the plan window, planned/confirmed/pending roll-ups, quantity-weighted
      `confirmedCompletion` with `overReported`/`noPlan` flags (R2), trajectory via
      `TrajectoryCalculator` (elapsed share from today vs the calendar anchor; `noAnchor` → ON_TRACK),
      and the per-cell drill-down; project roll-up + per-status counts + overall projected finish;
      money roll-up for money-viewers only; empty-plan path returns estimate-derived category rows
      with empty windows and ON_TRACK, creating no schedule row. Strictly read-only (no write, no
      version bump). WORKER drill-down filtered to own reports (R3.4). Bounded queries, in-memory fold
      (no N+1).
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.3, 3.4, 4.1, 4.5, 6.5_
    - _Depends on: 1.1, 1.2_

- [x] 3. Backend: controller endpoint
  - [x] 3.1 Add the `GET /plan-actual` READ handler
    - On the existing `ProjectScheduleController` (`@PermissionResource("WORK_SCHEDULE")`), add
      `@GetMapping("/plan-actual") @PermissionOperation("READ")` taking a required `@RequestParam Long
      projectId` and returning `PlanActualService.getPlanActual(projectId)`. No new resource, no new
      grant. Verify the app starts (no `PermissionAnnotationValidator` failure).
    - _Requirements: 1.1, 6.1, 6.2, 6.3, 6.4, 7.1, 7.2_
    - _Depends on: 2.1_

- [x] 4. Frontend: tab wiring + plan/actual panel
  - [x] 4.1 Swap the `planActual` tab panel
    - In `workspaceTabs.ts`, change the `planActual` entry's `lazy` from `PlaceholderTab` to the new
      `PlanActualPanel`. Do not touch the `WORKSPACE_TABS` contract shape, `resolveWorkspaceTabs`, or
      `stageOf` (the slot + `WORK_SCHEDULE`/READ gate were reserved by FOR-06-00).
    - _Requirements: 5.1, 6.1_
    - _Depends on: none_
  - [x] 4.2 Build the `plan-actual` feature folder
    - `features/project-execution/plan-actual/` with `PlanActualPanel.tsx` (fetch + at-risk list +
      overlay Gantt + status/worker filters + loading/empty states, strictly read-only — no
      edit/drag/resize/auto-create/save), `components/PlanActualGantt.tsx` +
      `components/PlanActualBar.tsx` (reuse FOR-05-10 `GanttGrid`/`TimelineHeader`/`GanttRow`
      primitives read-only; planned bar + confirmed-progress fill + distinct pending band),
      `components/TrajectoryBadge.tsx`, `components/AtRiskList.tsx`,
      `components/CategoryDrillDown.tsx` (work-item/room cells with planned/confirmed/pending/rejected
      + flags + report deep-links to `/projects/:projectId/workReporting?report=:id`),
      `api/plan-actual-api.ts` (`fetchPlanActual`), `lib/overlay.ts` (fill + pending-band geometry),
      `types/`, `__tests__/`.
    - _Requirements: 3.3, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_
    - _Depends on: 3.1, 4.1_

- [x] 5. i18n + tests
  - [x] 5.1 Add i18n keys (PL + RU, mirrored)
    - Add the `planActual.*` feature block (legend plan/confirmed/pending/rejected, completion label,
      the three trajectory badges, projected-finish + no-anchor, at-risk list heading/rows,
      drill-down columns incl. over-reported/no-plan + report-ref columns, status/worker filters,
      loading/empty) to both `pl.json` and `ru.json` with real PL+RU values (design §5.1). Reuse
      `workspace.tab.planActual` (seeded by FOR-06-00) unchanged. Run the locale-parity test.
    - _Requirements: 9.1, 9.2, 9.3_
    - _Depends on: 4.2_
  - [x] 5.2 Backend unit/property tests
    - `TrajectoryCalculator` boundary cases (P5: just-on-track, just-at-risk, elapsed exactly 1,
      completion = 1 while time-overdue, no-anchor → P6); `PlanActualService` completion roll-up
      incl. unplanned + over-report (P4), status bucketing confirmed/pending/rejected (P3),
      empty-plan (P7), money-viewer gate (P9), and a read-only assertion (no write / no version bump,
      P1) via a Testcontainers read against a seeded schedule + reports. Run only these classes with
      `--tests` → `/tmp/for-06-02-test.log`; verify via JUnit XML.
    - _Requirements: 1.5, 1.6, 2.3, 2.4, 2.5, 3.2, 4.1, 4.6, 6.5, 10.1, 10.3_
    - _Depends on: 2.1, 3.1_
  - [x] 5.3 Frontend unit tests
    - Overlay geometry (confirmed-progress fill + pending band from a fixture `PlanActualView`),
      at-risk list render, drill-down table + report deep-links, resolver fixtures (`planActual`
      present on ACTIVE/COMPLETED, absent on DRAFT — stage gating), locale parity (P10).
    - _Requirements: 5.2, 5.3, 5.4, 6.1, 9.2_
    - _Depends on: 4.2, 5.1_

- [x] 6. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template). The plan/actual read is API-tested against the Dockerized
    app (`http://localhost:8080`, `GET /api/project-schedules/plan-actual?projectId=`): seed a
    schedule + estimate + reports in mixed statuses, assert the confirmed/pending/rejected buckets,
    completion roll-up, trajectory classification, and the read-only invariant (no row created, no
    version bump). The overlay flow (progress fill, pending band, at-risk list, category drill-down
    with report deep-links, read-only with no edit affordances) is a browser-engine scenario on an
    `ACTIVE` project. Result artifacts are MD reports with tables.
  - _Requirements: 1.1, 2.2, 3.1, 3.3, 4.2, 5.2, 5.4, 5.5, 6.2, 10.1_
  - _Depends on: 3.1, 4.2, 5.1, 5.2, 5.3_

- [x] 7. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes; type-check + unit-test the frontend; confirm the locale-parity test passes and the
    app starts (no `PermissionAnnotationValidator` failure). Confirm the `planActual` tab renders the
    read-only overlay for a permitted caller on an `ACTIVE` project (progress fill + pending band +
    at-risk list + drill-down), is absent on a `DRAFT` project, and that the endpoint writes nothing
    (no schedule row / version bump). Then stage and commit per repo (two commits) — only if the user
    asks.
  - _Requirements: all_
  - _Depends on: 1.1, 1.2, 2.1, 3.1, 4.1, 4.2, 5.1, 5.2, 5.3, 6_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "4.1"] },
    { "id": 1, "tasks": ["2.1"] },
    { "id": 2, "tasks": ["3.1"] },
    { "id": 3, "tasks": ["4.2"] },
    { "id": 4, "tasks": ["5.1", "5.2", "5.3"] },
    { "id": 5, "tasks": ["6"] },
    { "id": 6, "tasks": ["7"] }
  ]
}
```
