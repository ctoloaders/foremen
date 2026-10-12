# Requirements Document

## Introduction

This spec (**FOR-05-10b-gantt-granularity**) extends the planning Gantt delivered by
**FOR-05-10-project-gantt** from a per-work-category plan to a **per-worker** plan. Today a Gantt
row is a work category with at most one bar, and the crew is a flat count of ACTIVE WORKER members
with no notion of who does what. This spec gives every WORKER a list of **work categories** they
can perform, assigns the estimate's work to concrete workers by category, and renders one bar per
worker (not per category), each worker in their own color.

It builds directly on three existing specs:

- **FOR-05-10-project-gantt** — the schedule persistence, `ScheduleView`, auto-create, drag/save,
  readiness gate, ABAC resource `WORK_SCHEDULE`, and the frontend `scheduleDesign` Gantt tab.
- **FOR-05-09-team-selection** — `ProjectMemberEntity` (the project↔user membership), the WORKER
  invite flow (`POST /api/users/worker`), and the Team tab where member attributes are edited.
- **FOR-05-11-project-readiness** — the aggregated readiness widget and its contributor model
  (`TeamReadinessContributor`, `ScheduleReadinessContributor`).

The work-category catalog itself (`WorkCategoryEntity`: `code`, `orderNo`, `nameRU`, `namePL`,
`active`) already exists and is unchanged.

### Scope boundaries

In scope:

1. **Worker work-categories (default, user level).** A WORKER user carries a default, ordered list
   of the work categories they can perform. Editable in the worker invite / create flow and in the
   user admin editor. Shown only for WORKER users.
2. **Project-member work-categories (override, project level).** Each project member (a WORKER on a
   project) carries their own list of work categories. On membership creation it is **copied** from
   the user default; afterward it is edited independently in the Team tab and never writes back to
   the user default.
3. **Per-worker Gantt bars.** The schedule bar becomes keyed by `(projectMember, workCategory)`
   instead of by category alone. A single category can carry several parallel bars, one per worker
   assigned to it. Each worker renders in a distinct color.
4. **Half-day granularity.** Bar start and duration are counted in **half-days** (multiples of
   0.5 days). All auto-layout, drag, resize, manual entry, and validation operate on the 0.5-day
   grid.
5. **Auto-create by worker category.** Auto-create maps each category's work to the workers whose
   categories include it, splits that category's duration **equally and in parallel** among those
   workers, lays each worker's bars **sequentially** along the worker's own timeline, and allows a
   **1-day overlap** between two neighboring sequential bars when the neighbors belong to
   **different** workers. A category with **no** covering worker is laid out as if it had exactly
   one worker (so a bar still exists) and raises an auto-create **alert**.
6. **Drag cascade.** When a worker's bar is dragged in time, that worker's later (sequential) bars
   shift so each starts at the right edge of the bar before it; other workers' timelines are
   untouched.
7. **Manual bar creation on the grid.** Clicking a (date × work-category) intersection opens a
   dialog to pick a worker who has that category, pre-fills the auto-computed duration, and allows
   free entry (on the 0.5-day grid).
8. **Readiness.** The project is not ready when it has **no ACTIVE WORKER** at all, and not ready
   when the estimate has a work category that **no ACTIVE WORKER covers**, with a localized
   "no worker for work type {category}" description.
9. **ABAC, i18n (pl + ru), audit, regression safety, and test artifacts.**

Out of scope:

- Changes to the work-category catalog (`work_categories`) itself, or any new translated catalog
  columns. The two new links are pure FK join tables to the existing `work_categories` (D-i18n).
- Dependencies / critical path between bars beyond the per-worker sequential cascade and the
  single-day neighbor overlap defined here.
- Working-day calendars, weekend/holiday restrictions (inherited visual marks only, FOR-05-10).
- Worker **types** (hiring cost tier, FOR-05-09) — unrelated to work **categories**; untouched.
- The Daily_Output_Rate value and man-days display (still internal, FOR-05-10 D7/D9).
- Execution-stage plan/actual overlay (FOR-06).
- In-app notifications (see D-notify).

### Relationship to existing specs and code (source of truth)

- `FOR-05-10-project-gantt`: `ProjectScheduleEntity`, `ProjectScheduleBarEntity`
  (`work_category_id`, `start_day`, `duration_days`), `ProjectScheduleService`
  (`getView` / `saveBars` / `autoCreate` / `readiness`), `ScheduleRowDerivation`,
  `ScheduleCalculator`, controller `/api/project-schedules`, resource `WORK_SCHEDULE`, and the
  frontend `features/project-schedule` tab.
- `FOR-05-09-team-selection`: `ProjectMemberEntity` (`project_members`, `project_member_tags`
  pattern), `ProjectMemberController` / `ProjectMemberService` (`PATCH` attribute update),
  `POST /api/users/worker` worker create/invite, Team tab.
- `FOR-05-11-project-readiness`: `ProjectReadinessContributor`, `TeamReadinessContributor`,
  `ScheduleReadinessContributor`, `ReadinessWidget`, outstanding-item localization.
- `WorkCategoryEntity` / `WorkCategoryController` — the catalog read for the multiselect.

## Glossary

- **Work_Category** — a `WorkCategoryEntity` row; a kind of construction work. Distinct from a
  Worker_Type (hiring cost tier).
- **User_Work_Categories** — the ordered default list of Work_Categories on a WORKER user.
- **Member_Work_Categories** — the ordered list of Work_Categories on a project member; copied from
  User_Work_Categories at membership creation, edited per project thereafter.
- **Covering_Worker** (for a category) — an ACTIVE WORKER project member whose Member_Work_Categories
  include that category.
- **Worker_Bar** — a `(projectMember, workCategory, startHalfDay, durationHalfDays)` schedule
  segment; the per-worker replacement of FOR-05-10's per-category bar.
- **Half_Day_Grid** — all start/duration values are integer multiples of 0.5 days (stored as
  half-day integers; see D-storage).
- **Worker_Timeline** — the ordered sequence of a single worker's bars; the unit of the sequential
  auto-layout and of the drag cascade.
- **Uncovered_Category** — an estimate work category with no Covering_Worker.
- **Schedule_Anchor / Day 1 / Finish_Day** — as defined in FOR-05-10 (calendar days from the
  project start date, or an abstract Day 1 when there is no start date).

## Decisions

- **D-model.** `User_Work_Categories` is a `UserEntity` one-to-many to `WorkCategoryEntity` via a
  new ordered join table `user_work_categories(user_id, work_category_id, order_no)`.
  `Member_Work_Categories` is a `ProjectMemberEntity` one-to-many via a new ordered join table
  `project_member_work_categories(project_member_id, work_category_id, order_no)`. Both mirror the
  existing `project_member_tags` `@OrderColumn` + `ON DELETE CASCADE` pattern but join to
  `work_categories` (FK, `ON DELETE RESTRICT` so a referenced category cannot be hard-deleted).
- **D-worker-only.** User_Work_Categories apply only to WORKER users; the editor and the invite
  field are shown only for a WORKER. A non-WORKER user has an empty list and no editor.
- **D-copy.** On project-member creation for a WORKER, Member_Work_Categories is initialized as a
  copy of the current User_Work_Categories (order preserved). Later edits to either side are
  independent.
- **D-bar-key.** A Worker_Bar is keyed by `(projectMemberId, workCategoryId)`; the bar table gains
  `project_member_id` and the uniqueness becomes `(schedule_id, project_member_id, work_category_id)`.
  A category may thus own multiple bars (one per covering worker). This replaces FOR-05-10's
  `(schedule_id, work_category_id)` uniqueness.
- **D-storage.** Half-day granularity is stored as **half-day integer counts** (`start_half_days`,
  `duration_half_days`, each ≥ 1, i.e. 0.5-day units), avoiding floating point. The API and UI
  present days (`startDay = start_half_days / 2`, etc.). Max horizon stays 3650 days
  (= 7300 half-days).
- **D-split.** Auto-create splits a category's total duration equally and in parallel among its
  Covering_Workers: `perWorkerHalfDays = ceil( ceil(categoryHalfDays) / coveringWorkerCount )`,
  minimum 1 half-day. All covering workers' bars for that category share the same start on the
  category pass; sequencing happens per worker across categories (D-layout).
- **D-layout.** Each Worker_Timeline is laid out sequentially in `(WorkCategory.orderNo, id)` order:
  a worker's next bar starts when their previous bar finishes. When two neighboring sequential bars
  on one worker's timeline abut and are the **same** worker, they are back-to-back (no gap, no
  overlap). The **1-day neighbor overlap** (R-overlap) applies between **different** workers' bars
  that are adjacent in the global category sequence, not within one worker's timeline — i.e. a
  successor category's bars may begin up to 1 day (2 half-days) before the predecessor category's
  last finish when the two categories are performed by different workers.
- **D-uncovered.** An Uncovered_Category is laid out as if it had exactly one (virtual) worker: a
  single bar with no `projectMember` (null), duration = full category duration. The read model flags
  it, readiness blocks on it, and auto-create returns an alert listing every Uncovered_Category.
- **D-color.** Each worker gets a stable, deterministic color derived from `projectMemberId` on the
  frontend; the virtual uncovered bar uses a neutral "unassigned" color. No color is stored.
- **D-readiness.** Two new conditions feed the aggregated widget: `team` BLOCKED when there is no
  ACTIVE WORKER (new team outstanding item `readiness.team.noWorkers`); `schedule` not-ready with one
  `readiness.schedule.noWorkerForCategory {category}` item per Uncovered_Category.
- **D-i18n.** No new translated DB columns — both join tables reference the existing
  `work_categories` (which already has `name_ru` / `name_pl`). New strings are UI labels and
  `error.*` codes only, authored in `pl.json` / `ru.json` and the backend message bundles.
- **D-notify.** This spec emits **no in-app notifications** (consistent with FOR-05-10 D15). The
  "not all workers present" condition is surfaced via the readiness blockers (R8) and the inline
  auto-create alert (R5.7), not the notification bell.
- **D-abac.** No new ABAC resource. User_Work_Categories edits ride on the existing `USERS` resource
  (user admin + worker create). Member_Work_Categories edits ride on `PROJECT_MEMBERS` (Team tab).
  The Gantt still rides on `WORK_SCHEDULE`.

## Requirements

### Requirement 1 — Worker default work-categories (user level)

**User Story:** As a manager inviting a worker, I want to record which work categories the worker
performs, so the planner can assign them the right work.

#### Acceptance Criteria

1. WHEN a WORKER user is created or edited THEN the system SHALL accept an ordered list of
   `workCategoryId`s (0..N, distinct, each referencing an `active` work category) and persist it in
   `user_work_categories` preserving the submitted order.
2. WHEN the same `workCategoryId` appears more than once in the list THEN the system SHALL reject the
   request with `error.user.workCategories.duplicate` naming the id, changing nothing.
3. WHEN a `workCategoryId` does not reference an existing active work category THEN the system SHALL
   reject with `error.user.workCategories.invalid` naming the id, changing nothing.
4. WHEN a non-WORKER user is created or edited THEN the system SHALL ignore / reject a non-empty work
   categories list per D-worker-only (the field is not offered for non-WORKER users) and keep the
   user's list empty.
5. WHEN a WORKER user is read for editing THEN the response SHALL include the user's ordered work
   categories (id + localized name, ru → pl → code fallback).

### Requirement 2 — Project-member work-categories (project override)

**User Story:** As a manager, I want to adjust a worker's work categories for a specific project,
without changing their default, so project planning reflects the real on-site assignment.

#### Acceptance Criteria

1. WHEN a WORKER is added to a project team THEN the system SHALL initialize Member_Work_Categories
   as an ordered copy of the user's current User_Work_Categories (R-D-copy), persisted in
   `project_member_work_categories`.
2. WHEN a member's work categories are edited via the Team-tab `PATCH` THEN the system SHALL replace
   the member's list with the submitted ordered, distinct, active-category list, validating as in
   R1.2 / R1.3 with codes `error.member.workCategories.duplicate` / `error.member.workCategories.invalid`.
3. WHEN a member's list is edited THEN the user's default list SHALL be unchanged, and vice versa.
4. WHEN the Team_Member_View is read THEN each WORKER member SHALL carry its ordered work categories
   (id + localized name).
5. WHEN a member is removed THEN its `project_member_work_categories` rows SHALL be discarded
   (`ON DELETE CASCADE`).

### Requirement 3 — Per-worker schedule bars (half-day granularity)

**User Story:** As a planner, I want each worker's work shown as its own bar on a half-day grid, so
the plan reflects who does what and when, at half-day resolution.

#### Acceptance Criteria

1. WHEN the schedule is persisted THEN each bar SHALL be keyed by `(schedule, projectMember,
   workCategory)` with `start_half_days ≥ 1` and `duration_half_days ≥ 1`, unique on
   `(schedule_id, project_member_id, work_category_id)` (D-bar-key, D-storage).
2. WHEN a bar has a null `projectMember` THEN it SHALL represent an Uncovered_Category virtual bar
   (D-uncovered); at most one such bar may exist per category.
3. WHEN `start_half_days` / `duration_half_days` are presented via the API THEN they SHALL be exposed
   as day values (`startDay`, `durationDays`) that are multiples of 0.5, and the finish day SHALL be
   `(start_half_days + duration_half_days − 1) / 2` rounded to the 0.5 grid as defined in design.
4. WHEN any bar's finish exceeds the 3650-day horizon (7300 half-days) THEN the write SHALL be
   rejected with `error.schedule.too.long`, changing nothing.
5. WHEN a bar's `(startDay, durationDays)` is not on the 0.5 grid, or a half-null pair is submitted,
   THEN the write SHALL be rejected with `error.schedule.bar.invalid` naming the offending bar.

### Requirement 4 — Auto-create by worker category

**User Story:** As a planner, I want auto-create to assign work to the right workers by category and
split shared categories evenly, so I get a realistic multi-worker plan in one click.

#### Acceptance Criteria

1. WHEN auto-create runs THEN for each estimate work category the system SHALL find its
   Covering_Workers and create one Worker_Bar per covering worker, each with
   `perWorkerHalfDays = ceil( ceil(categoryHalfDays) / coveringWorkerCount )` (minimum 1 half-day,
   D-split).
2. WHEN a category has no Covering_Worker THEN the system SHALL create a single virtual
   Uncovered_Category bar (null member) with the full category duration (D-uncovered) and include the
   category in the auto-create alert.
3. WHEN laying out THEN each Worker_Timeline SHALL be sequenced in `(WorkCategory.orderNo, id)` order
   so a worker's next bar starts at their previous bar's finish (same-worker neighbors are
   back-to-back, D-layout).
4. WHEN two categories adjacent in the global category order are performed by **different** workers
   THEN the successor category's bars MAY start up to 1 day (2 half-days) before the predecessor
   category's last finish (the single-day neighbor overlap, R-overlap / D-layout), never producing a
   negative or sub-1-half-day start.
5. WHEN auto-create completes THEN the response SHALL include an `alert` object listing every
   Uncovered_Category (ids + localized names) and whether the crew is empty, in addition to the fresh
   Schedule_View.
6. WHEN the project has no ACTIVE WORKER at all THEN auto-create SHALL still lay every category out as
   an Uncovered_Category virtual bar and return the alert (it does **not** hard-fail on an empty crew;
   this differs from FOR-05-10's `error.schedule.crew.empty`, superseded here).
7. WHEN auto-create commits THEN it SHALL bump the schedule version exactly once and write one audit
   row (reusing the FOR-05-10 audit mechanism).

### Requirement 5 — Manual bar creation and editing on the grid

**User Story:** As a planner, I want to create a bar by clicking a date / work-category cell and
pick the worker and duration, so I can plan by hand at half-day resolution.

#### Acceptance Criteria

1. WHEN the user clicks a (date × work-category) intersection on an empty cell THEN the UI SHALL open
   a create dialog listing the Covering_Workers of that category as the worker choice.
2. WHEN the create dialog opens THEN it SHALL pre-fill the auto-computed per-worker duration for that
   category and allow free entry on the 0.5-day grid (minimum 0.5 day).
3. WHEN the category has no Covering_Worker THEN the create dialog SHALL say so and offer only the
   "unassigned (virtual)" option, or block creation, per design.
4. WHEN a worker + duration is confirmed THEN a Worker_Bar SHALL be created at the clicked start day
   (snapped to 0.5) for that `(member, category)`, subject to R3 validation.
5. WHEN the user drags or resizes a bar THEN the edit SHALL snap to the 0.5-day grid and clamp to
   `[0.5, 3650]`.

### Requirement 6 — Drag cascade along a worker's timeline

**User Story:** As a planner, when I move a worker's bar, I want that worker's following bars to
follow it, so I don't have to re-drag the whole chain.

#### Acceptance Criteria

1. WHEN a worker's bar is dragged to a new start THEN every later bar on the **same** worker's
   timeline SHALL shift so each starts at the finish of the bar before it (D-layout).
2. WHEN the cascade shifts bars THEN only that worker's bars SHALL move; other workers' bars SHALL be
   unchanged.
3. WHEN the cascade would push a bar past the 3650-day horizon THEN the save SHALL be rejected with
   `error.schedule.too.long`, changing nothing.
4. WHEN the user resizes (not moves) a bar THEN the same cascade SHALL re-flow that worker's later
   bars from the resized finish.

### Requirement 7 — Per-worker rendering

**User Story:** As a planner, I want each worker's bars in a distinct color, so I can read who does
what at a glance.

#### Acceptance Criteria

1. WHEN the Gantt renders THEN each worker's bars SHALL use a stable color derived from the project
   member id (D-color); a legend SHALL map color → worker name.
2. WHEN a category has multiple covering workers THEN its row SHALL show one bar per worker, which may
   run in parallel (overlapping), each in that worker's color.
3. WHEN a bar is an Uncovered_Category virtual bar THEN it SHALL render in a neutral "unassigned"
   style and be labeled as unassigned.

### Requirement 8 — Readiness

**User Story:** As a user, I want the readiness widget to tell me when the project has no workers or
is missing a worker for a work type, so I know what blocks planning.

#### Acceptance Criteria

1. WHEN the project has no ACTIVE WORKER THEN the `team` readiness area SHALL be BLOCKED with an
   outstanding item `readiness.team.noWorkers` targeting the Team tab.
2. WHEN the estimate has one or more Uncovered_Categories THEN the `schedule` readiness area SHALL be
   not-ready (PARTIAL or BLOCKED per design) with one `readiness.schedule.noWorkerForCategory`
   item per Uncovered_Category (param = localized category name) targeting the Team tab.
3. WHEN every estimate category has at least one Covering_Worker and at least one ACTIVE WORKER
   exists THEN neither new outstanding item SHALL be present.
4. WHEN readiness is read THEN it SHALL remain a pure read (never create a schedule row), consistent
   with FOR-05-10 and FOR-05-11.

### Requirement 9 — ABAC, audit, localization, regression, tests

#### Acceptance Criteria

1. WHEN User_Work_Categories are edited THEN the existing `USERS` grants SHALL guard the operation;
   WHEN Member_Work_Categories are edited THEN `PROJECT_MEMBERS` grants SHALL guard it; WHEN the
   schedule is read/written THEN `WORK_SCHEDULE` grants SHALL guard it (D-abac — no new resource).
2. WHEN a schedule write commits THEN exactly one audit row SHALL be written per the FOR-05-10 audit
   mechanism (unchanged shape, now covering per-worker bars).
3. WHEN any new user-facing string is shown THEN it SHALL be localized in both `pl` and `ru` with
   identical key sets (labels, placeholders, dialog text, readiness items, `error.*` codes).
4. WHEN this spec ships THEN the FOR-05-10 / FOR-05-09 / FOR-05-11 behaviors not changed here SHALL
   continue to pass (regression), and the spec SHALL ship its own unit, property, integration, and
   API-level tests plus `test-cases.md`.

## Notifications

This feature emits **no in-app notifications** (D-notify). Justification: the only
"something is missing" signals (no workers / no worker for a category / incomplete auto-create) are
planning-state conditions already surfaced synchronously in the readiness widget and the auto-create
result, so a persisted bell notification would be redundant. This is consistent with FOR-05-10,
which also emits none (its D15).
