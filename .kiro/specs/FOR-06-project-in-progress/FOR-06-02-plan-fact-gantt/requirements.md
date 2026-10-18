# Requirements Document

## Introduction

This spec (**FOR-06-02-plan-fact-gantt**) is the second feature child of the FOR-06 "project in
progress" (execution stage) parent. It delivers the **plan/actual Gantt overlay**: a read-only
execution-stage surface that takes the planning Gantt built in FOR-05-10 / FOR-05-10b (the planned
per-worker, per-work-category bars) and overlays the **work actually reported** (FOR-06-01) on top of
it, so a FOREMAN or MANAGER can see, per work category, how much of the plan is done, whether the
project is on track, and which categories are at risk of running past their planned finish.

The feature is a **pure read/analytics overlay**. It introduces **no new persisted entity**, no new
table, and **no new ABAC resource**: it reuses the existing `WORK_SCHEDULE` project-scoped resource
(the `planActual` execution tab was already reserved by FOR-06-00 and gated on `WORK_SCHEDULE`/READ),
reads the planned bars from FOR-05-10/10b, the planned quantities from the estimate
(`EstimateLineRoomQty`), and the reported quantities from FOR-06-01 `WorkReport` facts, and computes a
derived plan/actual view on the fly. Nothing in this spec writes domain data.

### Plan side vs. actual side (what is overlaid)

- **Plan side (read from FOR-05-10/10b).** The project's `ProjectSchedule` and its
  `ProjectScheduleBar` rows give, per `(worker, work category)`, a planned `start` and `duration`
  in half-days from the schedule anchor (`Project.startDate` or an abstract Day 1). A category's
  planned finish is the latest finish across its bars. The planned **quantity** of a work item in a
  room comes from the estimate: `EstimateLineRoomQty.quantity` for the `(workItem, room)` pair.
- **Actual side (read from FOR-06-01).** Each `WorkReport` carries one `workItem`, one `worker`, a
  `[performedFrom, performedTo]` interval, a `status`, and per-room reported quantities
  (`WorkReportRoom.quantity`). The actual reported quantity for a `(workItem, room)` is the sum of
  those row quantities over the project's reports for that work item and room.

### Completion vs. trajectory (the two derived numbers)

- **Completion** is a quantity ratio: for a work item in a room, `reported ÷ planned` (clamped to a
  displayed 0–100 %, with any over-report surfaced separately). It rolls up to a work category
  (quantity-weighted across its work items and rooms) and to the whole project.
- **Trajectory** is a time/pace projection: given the elapsed share of a category's planned window
  and its current completion, the overlay projects a likely finish and classifies the category as
  **on-track**, **at-risk**, or **overdue**. Trajectory is advisory; it is recomputed on every read
  and is never persisted.

### All reports visible, but only approved work counts as fact

Per the product decision in this spec, the overlay shows **all** reports regardless of status
(SUBMITTED, CHANGES_REQUESTED, APPROVED, REJECTED), not only approved ones — a FOREMAN needs to see
pending and returned work, not just confirmed work. But the two numbers are kept honest:

- **Confirmed completion** (the authoritative fact) counts **APPROVED** reports only.
- **Pending** reported quantity (SUBMITTED + CHANGES_REQUESTED) is shown as a separate, visually
  distinct overlay band so the reviewer can see what is awaiting review without it inflating the
  confirmed figure.
- **REJECTED** reports are visible in the per-cell drill-down for context but contribute **zero** to
  both confirmed and pending quantity.
- The **trajectory** classification is computed from **confirmed completion only** (approved fact),
  so an at-risk flag is never masked by not-yet-reviewed work. The pending band is informational.

### Terminology

- **Execution stage** — a project whose `Project.status` is `ACTIVE` or `COMPLETED`, as mapped by the
  existing `stageOf` (FOR-05-01). The `planActual` tab is an execution-stage tab.
- **Planned bar** — a `ProjectScheduleBar` `(schedule, projectMember|null, workCategory,
  startHalfDays, durationHalfDays)` from FOR-05-10/10b. A null `projectMember` is the virtual
  "uncovered category" bar.
- **Category window** — for a work category, `[earliest bar start, latest bar finish]` across all its
  bars, in days from the anchor (half-days ÷ 2).
- **Planned quantity** — `EstimateLineRoomQty.quantity` for a `(workItem, room)`; the category's
  planned quantity is derived from its work items' room quantities.
- **Reported quantity** — `Σ WorkReportRoom.quantity` over the project's reports for a `(workItem,
  room)`, partitioned by report status (confirmed = APPROVED; pending = SUBMITTED +
  CHANGES_REQUESTED; rejected contributes nothing).
- **Confirmed completion** — `reported(APPROVED) ÷ planned`, clamped to 0–100 % for display.
- **Trajectory status** — one of `ON_TRACK`, `AT_RISK`, `OVERDUE`, derived per category from elapsed
  time vs. confirmed completion.

### Scope boundaries

- **In scope:**
  - A **read-only** plan/actual projection endpoint on the existing `WORK_SCHEDULE` resource that,
    for a project, returns per work category: the planned window (from the schedule bars), the
    planned quantity, the confirmed and pending reported quantity, the confirmed completion %, the
    trajectory status with a projected finish, and a per-work-item / per-room drill-down.
  - The **trajectory calculation** (elapsed share, projected finish, on-track / at-risk / overdue
    classification) as a pure, tested backend helper.
  - Swapping the already-reserved `planActual` execution tab's `lazy` from `PlaceholderTab` to the
    real read-only plan/actual panel, reusing the FOR-05-10 Gantt rendering primitives, without
    reshaping the `WORKSPACE_TABS` contract.
  - A read-only overlay rendering: the planned bars with an actual-progress fill, a distinct pending
    band, per-category completion %, an at-risk alert list, and a per-category drill-down to
    work-item / room cells.
  - **All-status visibility** (R3): the overlay surfaces SUBMITTED, CHANGES_REQUESTED, APPROVED, and
    REJECTED reported quantities, with confirmed vs. pending vs. rejected clearly separated.
  - PL + RU localization of every new user-facing string, and the spec's own tests + `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **Editing the plan** — the planning Gantt (bars, auto-create, save) stays owned by
    FOR-05-10/10b; this tab never mutates a bar. It is read-only.
  - **Creating / reviewing reports** — authoring and the approve/reject/request-changes cycle are
    FOR-06-01; this spec only *reads* report facts.
  - **The margin matrix** (FOR-06-06) and **payroll** (FOR-06-11) — this spec computes no cost,
    margin, or payout; it exposes revenue only where FOR-06-01 already froze it, and may surface a
    category's revenue roll-up for money-viewers but computes no new financial figure.
  - **Amendments** (FOR-06-08) — the planned window and planned quantity are read as they stand; a
    signed amendment that shifts the plan is reflected because the overlay re-reads the (amended)
    schedule and estimate, but this spec implements no amendment logic.
  - **Notifications** — this spec emits **none** (see Requirement 8); at-risk status is surfaced
    in-panel only.
  - **A new ABAC resource or a new persisted entity / i18n column** — the overlay rides the existing
    `WORK_SCHEDULE` resource and reads existing entities; it adds no table, no resource, and no
    `nameRU`/`namePL` column.
  - **Changing `stageOf` / `resolveWorkspaceTabs` / the `WORKSPACE_TABS` contract shape** — the
    `planActual` slot and its `WORK_SCHEDULE`/READ gate were reserved by FOR-06-00; this spec swaps
    the tab's panel only.

### Relationship to existing specs (source of truth)

- **FOR-05-10-project-gantt** + **FOR-05-10b-gantt-granularity** — the `ProjectSchedule` /
  `ProjectScheduleBar` model (per `(worker, work category)`, half-day start/duration), the
  `ProjectScheduleService` (`ProjectScopedService`, `getProjectIdPath()` = `"project.id"`), the
  `ProjectScheduleController` (`/api/project-schedules`, `@PermissionResource("WORK_SCHEDULE")`), and
  the `ScheduleView` read model with `anchorDate` / `finishDay` / day↔half-day math. This spec reads
  the planned bars from there and reuses the Gantt rendering primitives on the frontend.
- **FOR-06-01-work-reporting** — the `WorkReport` / `WorkReportRoom` facts (one `workItem` + one
  `worker` per report, per-room `quantity`, `status` ∈ {SUBMITTED, APPROVED, REJECTED,
  CHANGES_REQUESTED}). This spec reads those facts; its progress projection
  (`GET /api/work-reports/progress`) is a close cousin and is reused/extended where sensible, but the
  plan/actual view is a distinct, schedule-anchored projection owned here.
- **FOR-05-03/05 estimate** — `EstimateLine` (frozen `unitPrice`) and `EstimateLineRoomQty`
  (planned quantity per `(workItem, room)`); read for planned quantity and (for money-viewers) the
  revenue roll-up. Not mutated.
- **FOR-06-00-execution-foundation** — reserved the `planActual` execution tab on `WORK_SCHEDULE`/
  READ (pointing at `PlaceholderTab`). This spec swaps that tab's panel to the real overlay.
- **FOR-05-07-offer-approval Requirement 13** — the generic in-app `Notification_Service`. This spec
  is **not** a consumer (Requirement 8 — no notifications).
- **`.kiro/steering/entity-creation-rules.md`** — **not triggered**: this spec adds no new managed
  entity and no new ABAC resource, so the entity-creation checklist does not apply (documented
  explicitly in R7).

## Glossary

- **ProjectSchedule / ProjectScheduleBar** — the planning-Gantt aggregate (FOR-05-10/10b). A bar is
  `(schedule, projectMember|null, workCategory, startHalfDays ≥ 1, durationHalfDays ≥ 1)`. Day math:
  `startDay = startHalfDays / 2`, `durationDays = durationHalfDays / 2`,
  `finishDay = (startHalfDays + durationHalfDays − 1) / 2`.
- **Anchor / Day 1** — the schedule origin: `Project.startDate` when set, else an abstract Day 1 with
  no calendar date. `date(day) = anchorDate + (day − 1)` when `anchorDate` exists.
- **Category window** — `[minStartDay, maxFinishDay]` across a category's bars; `plannedDurationDays
  = maxFinishDay − minStartDay + 1`.
- **Planned quantity (cell)** — `EstimateLineRoomQty.quantity` for a `(workItem, room)` on the
  project's estimate; null when the estimate has no plan for that cell.
- **Reported quantity (cell), by bucket** — over the project's `WorkReport`s for a `workItem` and a
  `room`: `confirmedQty = Σ quantity WHERE status = APPROVED`; `pendingQty = Σ quantity WHERE status
  ∈ {SUBMITTED, CHANGES_REQUESTED}`; REJECTED rows sum to nothing but are listed in the drill-down.
- **Confirmed completion (cell)** — `planned > 0 ? min(1, confirmedQty / planned) : (confirmedQty > 0
  ? 1 : 0)`; `overReported = confirmedQty > planned` (surfaced as an over-report flag, not as > 100 %).
- **Category completion** — quantity-weighted roll-up of its cells' confirmed completion, weighted by
  planned quantity (a plan-less cell with a report counts as fully complete with weight 0, surfaced
  as "unplanned actual").
- **Elapsed share (category)** — `clamp01((today − anchorDate − minStartDay + 1) / plannedDurationDays)`
  when a calendar anchor exists; when there is no calendar anchor, elapsed share is **undefined** and
  the category's trajectory status is `ON_TRACK` with a "no calendar anchor" note (no false alert).
- **Trajectory status (category)** — from `elapsedShare e` and `confirmedCompletion c`:
  `OVERDUE` when `e ≥ 1` and `c < 1`; `AT_RISK` when `e < 1` and `c < e − riskMargin`; else
  `ON_TRACK`. `riskMargin` is a small configured tolerance (default 0.1). A category fully complete
  (`c ≥ 1`) is always `ON_TRACK` (done).
- **Projected finish (category)** — when `c > 0` and a calendar anchor exists, the pace-extrapolated
  finish day `minStartDay + plannedDurationDays × (e / c)` (capped to a sane horizon); null when
  `c = 0` or no anchor.
- **Money-viewer** — a caller permitted to see monetary figures, resolved exactly as FOR-05-10's
  `ScheduleView` does (money fields present only for money-viewers); the overlay follows the same
  rule for any revenue roll-up it exposes.

## Requirements

### Requirement 1 — Plan/actual projection (read model)

**User Story:** As a foreman or manager, I want a per-category plan-vs-actual view of an active
project, so I can see at a glance how much of each category is done and where we stand against the
plan.

#### Acceptance Criteria

1. THE SYSTEM SHALL expose a **read-only** endpoint `GET /api/project-schedules/plan-actual?projectId=`
   on the existing `WORK_SCHEDULE` resource at READ, returning a `PlanActualView` for the project.
2. THE `PlanActualView` SHALL carry the schedule framing reused from `ScheduleView`: `projectId`,
   `anchorDate` (nullable), `projectEndDate` (nullable), `finishDay`, `version` (the schedule
   version it was computed from), and a server `computedAt` timestamp.
3. FOR each work category that is a schedule row, THE view SHALL return: `workCategoryId`, localized
   `name` (ru→pl→code), the planned window (`startDay`, `finishDay`, `plannedDurationDays`), the
   `plannedQuantity` roll-up, the `confirmedQuantity` and `pendingQuantity` roll-ups, the
   `confirmedCompletion` (0–1), an `overReported` flag, the `trajectoryStatus`
   (`ON_TRACK`/`AT_RISK`/`OVERDUE`), the `projectedFinishDay` (nullable), and the per-worker planned
   bars (`projectMemberId|null`, `workerName`, `startDay`, `durationDays`, `finishDay`) carried
   through unchanged from the schedule so the overlay can render them.
4. THE view SHALL return a project-level roll-up: overall `plannedQuantity`, `confirmedQuantity`,
   `pendingQuantity`, `confirmedCompletion`, the count of categories per `trajectoryStatus`, and the
   overall `projectedFinishDay` (the latest category projected finish).
5. WHEN a project has a `ProjectSchedule` with no bars, OR no `ProjectSchedule` row at all, THEN the
   view SHALL return the category rows derived from the estimate with empty planned windows and
   `trajectoryStatus = ON_TRACK` (nothing planned ⇒ nothing at risk), creating no schedule row
   (mirrors FOR-05-10 Requirement 5.6).
6. THE endpoint SHALL be strictly read-only: it SHALL create, update, or delete no row, and SHALL
   bump no schedule version.

### Requirement 2 — Completion calculation (quantity ratio)

**User Story:** As a manager, I want completion measured by reported quantity against planned
quantity, so the percentage reflects real progress, not just elapsed time.

#### Acceptance Criteria

1. FOR a `(workItem, room)` cell, THE planned quantity SHALL be `EstimateLineRoomQty.quantity` for
   that pair on the project's estimate (null when the estimate plans nothing for the cell).
2. FOR a cell, THE confirmed reported quantity SHALL be `Σ WorkReportRoom.quantity` over the
   project's reports for that `workItem` and `room` whose `status = APPROVED`; THE pending reported
   quantity SHALL be the same sum over `status ∈ {SUBMITTED, CHANGES_REQUESTED}`; REJECTED reports
   SHALL contribute zero to both.
3. THE cell confirmed completion SHALL be `min(1, confirmedQty / plannedQty)` when `plannedQty > 0`;
   WHEN `plannedQty = 0` or null AND `confirmedQty > 0` THEN the cell SHALL be treated as a fully
   complete **unplanned actual** (completion 1, plan weight 0, surfaced as unplanned) rather than a
   division by zero.
4. WHEN `confirmedQty > plannedQty` (`plannedQty > 0`) THEN the cell SHALL set an `overReported`
   flag and the displayed completion SHALL clamp at 100 %, the surplus surfaced in the drill-down.
5. THE category confirmed completion SHALL be the planned-quantity-weighted mean of its cells'
   confirmed completion; THE project confirmed completion SHALL be the planned-quantity-weighted mean
   across all categories. A category with zero total planned quantity SHALL report completion 1 when
   it has any confirmed actual, else 0, and SHALL be flagged `noPlan` in the drill-down.
6. ALL completion figures SHALL be derived on read and SHALL never be persisted.

### Requirement 3 — All-status visibility (confirmed, pending, rejected)

**User Story:** As a foreman, I want to see every report on the overlay — approved, awaiting review,
or returned — not only approved work, so I have the full picture without leaving the Gantt.

#### Acceptance Criteria

1. THE overlay SHALL surface reported quantity in three distinct buckets per cell and per category:
   **confirmed** (APPROVED), **pending** (SUBMITTED + CHANGES_REQUESTED), and **rejected**
   (REJECTED), each separately totalled.
2. THE confirmed bucket SHALL drive the authoritative completion and trajectory (R2, R4); THE pending
   bucket SHALL be shown as a visually distinct overlay band and SHALL NOT inflate the confirmed
   completion or alter the trajectory status; THE rejected bucket SHALL appear only in the per-cell
   drill-down for context and SHALL contribute zero quantity everywhere else.
3. THE per-cell drill-down SHALL list the contributing reports with their `status`, `worker`,
   `performedFrom`/`performedTo`, reported `quantity`, and a deep-link reference to the report in the
   Work Reporting tab (FOR-06-01), so a reviewer can jump from the overlay to the report.
4. WHERE a caller is a WORKER (if ever granted READ on `WORK_SCHEDULE` for a project they are a member
   of) THE reports they can see through the drill-down SHALL still respect FOR-06-01's own-records
   rule — a WORKER SHALL never see another worker's individual report rows through this overlay; the
   category/cell aggregate quantity remains visible, but the per-report drill-down is filtered to
   their own reports. (The default `WORK_SCHEDULE` grant for WORKER is READ per FOR-05-10.)

### Requirement 4 — Trajectory calculation and at-risk classification

**User Story:** As a manager, I want each category classified as on-track, at-risk, or overdue based
on pace, so I can intervene before a deadline is missed.

#### Acceptance Criteria

1. FOR a category with a calendar anchor, THE elapsed share SHALL be
   `clamp01((today − date(minStartDay) + 1) / plannedDurationDays)`; FOR a category with no calendar
   anchor (abstract Day 1), THE elapsed share SHALL be undefined and the category SHALL be classified
   `ON_TRACK` with a `noAnchor` note (no false alert without a real calendar).
2. THE trajectory status SHALL be: `OVERDUE` WHEN elapsed share `≥ 1` AND confirmed completion `< 1`;
   `AT_RISK` WHEN elapsed share `< 1` AND confirmed completion `< elapsedShare − riskMargin`;
   otherwise `ON_TRACK`. A category with confirmed completion `≥ 1` SHALL always be `ON_TRACK` (done).
3. THE `riskMargin` SHALL be a single configured tolerance (default `0.1`) read from configuration,
   not hard-coded at the call site, so the at-risk sensitivity is tunable.
4. WHEN confirmed completion `> 0` AND a calendar anchor exists THEN the projected finish day SHALL be
   `minStartDay + plannedDurationDays × (elapsedShare / confirmedCompletion)`, capped at a sane
   horizon; otherwise the projected finish SHALL be null.
5. THE trajectory SHALL be computed from the **confirmed** bucket only (R3.2); pending or rejected
   reported quantity SHALL NOT change a category's trajectory status or projected finish.
6. THE trajectory classification SHALL be a pure, deterministic function of
   `(elapsedShare, confirmedCompletion, riskMargin)` and SHALL be unit-tested across the boundary
   cases (just-on-track, just-at-risk, exactly elapsed = 1, completion = 1 while overdue-by-time).

### Requirement 5 — Plan/actual tab (frontend, read-only overlay)

**User Story:** As a foreman or manager, I want a plan/actual Gantt tab in the project workspace, so I
can read progress on the same timeline I planned on.

#### Acceptance Criteria

1. THE SYSTEM SHALL render a read-only Plan/Actual panel on the execution-stage `planActual` tab,
   reusing the FOR-05-10 Gantt rendering primitives (timeline header, rows, bars) so the plan reads
   on the same half-day timeline.
2. EACH category row SHALL render its planned bars with an **actual-progress fill** proportional to
   the category's confirmed completion, a **distinct pending band** for pending reported quantity
   (R3.2), the completion **percentage label**, and a **trajectory badge** (on-track / at-risk /
   overdue) with the localized status.
3. THE panel SHALL show an **at-risk alert list** summarizing every `AT_RISK` and `OVERDUE` category
   with its completion % and projected finish, so risks are visible without scanning every bar.
4. CLICKING a category SHALL open a **drill-down** listing its work items and rooms with planned,
   confirmed, pending, and (for context) rejected quantities per cell, the `overReported` / `noPlan`
   flags, and the contributing reports with deep-links to the Work Reporting tab (R3.3).
5. THE panel SHALL be strictly read-only: it SHALL expose **no** bar edit, drag, resize, auto-create,
   or save affordance (those remain on the FOR-05-10 design-stage tab), and SHALL render a loading
   state and an empty state (no schedule / no reports) without error.
6. THE panel SHALL offer filters by `trajectoryStatus` and by worker, and SHALL reuse the FOR-05-10
   calendar/weekend legend and day↔date rendering so the two Gantts look consistent.

### Requirement 6 — Stage gating and access

**User Story:** As a security owner, I want the plan/actual overlay available only on execution-stage
projects to callers permitted to read the schedule, so progress is visible to the right people in the
right stage.

#### Acceptance Criteria

1. WHEN a project is `ACTIVE` or `COMPLETED` (execution stage per `stageOf`) THEN the `planActual` tab
   SHALL be reachable; WHEN a project is `DRAFT` (design stage) THEN the `planActual` tab SHALL NOT be
   reachable (the design-stage `scheduleDesign` tab is the one shown instead).
2. THE plan/actual endpoint SHALL be guarded by the existing project-scoped `WORK_SCHEDULE` resource
   at READ: the same callers who may read the planning schedule (per FOR-05-10's grants — ADMIN /
   MANAGER / FOREMAN / ESTIMATOR / WORKER / FINANCIER / CLIENT per their seeded operations) may read
   the overlay, filtered to projects they are members of by the inherited project scope.
3. THE endpoint SHALL add **no** new ABAC resource and **no** new grant; it SHALL be a new READ
   handler on the existing `ProjectScheduleController` (or a sibling controller) carrying
   `@PermissionResource("WORK_SCHEDULE")` + `@PermissionOperation("READ")`, and the application SHALL
   start without a `PermissionAnnotationValidator` failure.
4. WHERE a caller lacks READ on `WORK_SCHEDULE` for the project THE endpoint SHALL deny the request
   exactly as the existing schedule read does (no bypass).
5. THE money roll-up (any revenue figure the view exposes) SHALL be present only for **money-viewers**,
   resolved the same way FOR-05-10's `ScheduleView` gates its money fields; a non-money-viewer SHALL
   receive the view with the money fields omitted/null.

### Requirement 7 — No new entity, resource, or persisted i18n (overlay-only)

**User Story:** As a maintainer, I want it explicit that this spec persists nothing new, so the
entity-creation checklist and migration machinery are not needlessly invoked.

#### Acceptance Criteria

1. THE spec SHALL introduce **no** new JPA entity, **no** new table, and **no** Liquibase changeset;
   it reads existing `project_schedules`, `project_schedule_bars`, `estimate_line_room_qty`,
   `work_reports`, and `work_report_rooms` and computes a transient view.
2. THE spec SHALL introduce **no** new ABAC resource and **no** new role grant; it rides the existing
   `WORK_SCHEDULE` resource (R6). The `.kiro/steering/entity-creation-rules.md` checklist SHALL
   therefore be documented as **not applicable** here.
3. THE spec SHALL add **no** `nameRU`/`namePL` or other persisted i18n column; every displayed
   dictionary name (work category, work item, room, worker) SHALL be read from its existing entity
   with the `ru → pl → code` fallback.
4. WHERE the trajectory tolerance (`riskMargin`) or a horizon cap is configurable, it SHALL live in
   application configuration (a property), not in a new table.

### Requirement 8 — Notifications (none)

**User Story:** As a product owner, I want at-risk status surfaced in the overlay itself without
generating notification noise, so the feature stays a passive analytics view for now.

#### Acceptance Criteria

1. THIS spec SHALL emit **no** notifications. At-risk and overdue status SHALL be surfaced **only**
   in-panel (the trajectory badges and the at-risk alert list, R5.2/R5.3).
2. **Justification.** Trajectory is a continuously recomputed, read-time derivation; emitting a
   notification would require a scheduled evaluator and a "fired-once" dedupe state that this spec
   does not introduce. A future spec MAY add an at-risk notification (e.g. the first time a category
   crosses into `AT_RISK`/`OVERDUE`) via the generic `Notification_Service`; it is explicitly out of
   scope here.
3. WHERE a later spec adds such a notification THE emission SHALL go through the generic in-app
   `Notification_Service` (FOR-05-07 Requirement 13) and SHALL NOT be reimplemented.

### Requirement 9 — Internationalization completeness

#### Acceptance Criteria

1. THE backend SHALL add **no** persisted i18n column (R7.3); every name on the view SHALL be a
   localized read of an existing entity (ru→pl→code).
2. THE frontend SHALL add every new user-facing string — the reused `workspace.tab.planActual` label
   (already seeded by FOR-06-00, reused unchanged), the overlay legend (plan / confirmed actual /
   pending / rejected), the completion % label, the three trajectory badges
   (`on-track`/`at-risk`/`overdue`), the at-risk alert list heading and rows, the projected-finish
   label, the drill-down column headers (planned / confirmed / pending / rejected / over-reported /
   no-plan), the filters (status, worker), and the loading/empty states — to **both**
   `src/locales/pl.json` and `src/locales/ru.json` with real PL and RU values (no English fallback),
   and the locale-parity test SHALL pass.
3. ANY `trajectoryStatus` / bucket key the backend returns SHALL be an enum/stable key that the
   frontend maps to a localized label; a raw key SHALL never be shown to the user.

### Requirement 10 — Audit, regression, and test artifacts

#### Acceptance Criteria

1. THE read-only endpoint SHALL write **no** audit row (it mutates nothing); the regression suite
   SHALL confirm it creates no `project_schedules` / `project_schedule_bars` row and bumps no version
   (R1.6).
2. WHEN this spec ships THEN the FOR-05-10/10b behaviors (the planning schedule read/save/auto-create,
   `stageOf`, `resolveWorkspaceTabs`, the other execution tabs' placeholders) and the FOR-06-01
   reporting behaviors SHALL continue to pass (regression).
3. THE spec SHALL ship its own backend unit/property tests (completion roll-up including unplanned /
   over-report, status bucketing confirmed/pending/rejected, the trajectory classifier boundary
   cases, the read-only / no-write property, the money-viewer gating), frontend unit tests (overlay
   render with progress fill + pending band, at-risk list, drill-down, resolver fixtures showing
   `planActual` present on ACTIVE/COMPLETED and absent on DRAFT, locale parity), and an API-level
   `test-cases.md` run against the Dockerized app.
