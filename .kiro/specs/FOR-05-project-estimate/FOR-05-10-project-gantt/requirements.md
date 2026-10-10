# Requirements Document

## Introduction

This spec (**FOR-05-10-project-gantt**) delivers the **planning Gantt (estimation schedule)** of a
project in the design stage. It is rendered entirely inside the existing workspace tab
`scheduleDesign` ("Harmonogram" / "График"). The tab shows one row per work category of the
project's estimate, each row with its volume of work and at most one planned bar. Users can lay
the bars out automatically from the crew size, or drag them by hand.

It turns the parent design's one-line "design-stage project-estimation Gantt is planning-only"
decision (`../design.md` §2.1, §6.10, §7.3, §8) into concrete, testable requirements. The plan it
produces is the **baseline** that the execution-stage schedule (`FOR-06-04-work-schedule`) and the
plan/actual overlay (`FOR-06-06-execution-plan-fact-gantt`) consume once the project becomes
`ACTIVE`.

### Scope boundaries

In scope:

1. **Schedule persistence (backend).** A per-project schedule (`project_schedules`) holding an
   optimistic-lock version, and its bars (`project_schedule_bars`), one per work category, stored
   as a day offset plus a duration in days.
2. **Schedule API (backend).** Read the Schedule_View (rows, volumes, bars, anchor, crew size,
   finish, warnings), save bars, auto-create the schedule, and read the schedule readiness gate,
   all under `/api/project-schedules`.
3. **ABAC.** A new project-scoped resource `WORK_SCHEDULE` with its seed, role grants, controller
   guarding, and `getProjectIdPath()`. The existing tab placeholder is already gated on it.
4. **Auto-create.** Durations derived from the category's net value, the Daily_Output_Rate
   (a global configuration value, default 2000 zł per worker per day, never shown in the UI) and
   the number of ACTIVE workers on the project team; bars laid out one after another in category
   order, every calendar day counting as a working day.
5. **Harmonogram tab (frontend).** A custom Gantt (no third-party Gantt library) with a fixed
   category column, a two-level time scale (week view by default, day view on zoom), Polish
   calendar marks (weekends and public holidays), drag to move and resize bars with day snapping,
   overlapping bars allowed, a keyboard and dialog alternative to dragging, save / discard, and
   desktop and mobile layouts.
6. **Readiness gate** `schedule` in the Readiness_Widget.
7. **Audit** of every committed schedule change, **localization** (pl + ru), regression safety and
   test artifacts.

Out of scope:

- Dependencies between rows (finish-to-start links, lags, critical path). Rows are independent and
  their bars may overlap freely.
- Working-day calendars, per-project calendars, resource levelling, and any restriction on
  placing work on weekends or holidays. Weekends and holidays are visual marks only.
- Assigning workers, worker types, or teams to individual rows (FOR-06).
- Plan/actual overlay, % completion, automatic updates from work reports, and manual re-planning
  from amendments (FOR-06-04 / FOR-06-06).
- Writing the computed finish back to `Project.startDate` / `Project.endDate`.
- Including the schedule as a "Harmonogram robót" appendix in FOR-05-08 signable documents.
- Custom (non-category) rows such as milestones.
- Displaying man-days or the Daily_Output_Rate anywhere, and any per-project override of the
  Daily_Output_Rate (D7, D9).
- In-app notifications (see D15).

### Relationship to existing specs and code (source of truth)

- Parent: `../OVERVIEW.md` (child spec table, row 10 after the renumbering in D1) and
  `../design.md` §2.1 ("Both Gantts relate to FOR-06"), §6.10 (stage tab sets), §7.3 / §8 (tab
  key and component names).
- `FOR-05-01-workspace-shell`: `WORKSPACE_TABS`, `resolveWorkspaceTabs`, `resolveActiveTab`,
  per-project tab memory, `ReadinessWidget`. The tab entry `scheduleDesign` already exists, gated on
  `{ resource: 'WORK_SCHEDULE', operation: 'READ' }` with label key `workspace.tab.scheduleDesign`
  ("Harmonogram" / "График") and the generic `PlaceholderTab`.
- `FOR-05-03-estimate-core` / `FOR-05-05-bill-of-materials`: `EstimateEntity` (one per project,
  `EstimateDao.findByProjectId`), `EstimateLineEntity` (`workItem`, `quantity`, `unit`,
  `valueNet`), `WorkItemEntity.workCategory`, `WorkCategoryEntity` (`code`, `orderNo`, `nameRU`,
  `namePL`, `active`).
- `FOR-05-07-offer-approval`: changeset `135` restricted `ESTIMATE` to ADMIN / MANAGER (and
  changeset `136` added ESTIMATOR with ESTIMATE CRU). The schedule therefore shows monetary values
  only to callers who hold `ESTIMATE` READ (D9).
- `FOR-05-09-team-selection`: `ProjectMemberEntity` with Project_Role and Assignment_Status
  (`ACTIVE` / `INACTIVE`); the team readiness gate pattern (`GET /api/project-members/readiness`,
  `team.readiness.*` keys) that this spec mirrors for the `schedule` gate.
- `FOR-03-08` API protection: `@PermissionResource`, `@PermissionOperation`,
  `PermissionAnnotationValidator`, `ProjectScopedService.getProjectIdPath()`, and the workspace
  `entity-creation-rules` checklist.
- Global configuration pattern: `foremen.offer.escalation.*` in `application.yml` (FOR-05-07).
- `FOR-06-project-in-progress` (folder `FOR-06-work-schedule`): FOR-06-04 and FOR-06-06 read this
  spec's bars as their baseline.

### Baseline: existing implementation this spec builds on

- No schedule, gantt, task, or calendar entity or table exists; no labour-norm or productivity
  field exists on work items or prices.
- No `WORK_SCHEDULE` resource is seeded, so the `scheduleDesign` tab is hidden for everybody
  today.
- `ProjectEntity` has nullable `startDate` and `endDate` (`LocalDate`).
- The last Liquibase changeset is `151-create-project-member-tags.xml`; this spec's changesets
  start at `152`.
- The frontend has no Gantt, timeline, or chart library; it has Radix UI, Tailwind, lucide-react,
  date-fns 4, TanStack Query 5, i18next.

### Decisions taken in this spec

- **D1 — Spec number.** The planning Gantt is **FOR-05-10** (previously listed as FOR-05-15 and,
  before that, FOR-05-16). The OVERVIEW, PROGRESS, and FOR-06 OVERVIEW references are updated.
- **D2 — Full stack, single tab.** All UI lives in the existing `scheduleDesign` tab; its key, its
  position in `WORKSPACE_TABS`, its gate (`WORK_SCHEDULE` READ), and its label key are kept. Its
  `owner` changes from `FOR-06` to `FOR-05` and its `lazy` panel from `PlaceholderTab` to the real
  `ScheduleTab`. The Russian label stays "График".
- **D3 — A row is a work category.** The Schedule_Rows of a project are exactly the work
  categories that have at least one estimate line in the project's estimate. Rows are always
  ordered by `WorkCategory.orderNo` ascending, then by category id; users cannot reorder them.
- **D4 — Day-offset model.** A Bar is `(startDay, durationDays)`, both whole numbers ≥ 1, counted
  in calendar days from the Schedule_Anchor. Day 1 is the Schedule_Anchor itself:
  `Project.startDate` when set, otherwise the abstract "Day 1". A bar covers days `startDay` …
  `startDay + durationDays − 1`. Changing `Project.startDate` shifts every bar with it.
- **D5 — Polish calendar, visual only.** When the anchor is a date, Saturdays, Sundays, and Polish
  statutory public holidays are visibly marked on the timeline. Bars may start, end, or span on any
  day; no validation uses the calendar. With no anchor date, no weekend or holiday marks are shown.
- **D6 — Two-level time scale.** The atomic unit is one day. The timeline is shown in week view by
  default and can be zoomed to day view. Dragging and resizing snap to whole days in both views.
- **D7 — Auto-create.** For each row:
  `durationDays = max(1, ceil(categoryValueNet / (dailyOutputRate × crewSize)))`. Bars are laid out
  one after another in row order starting at day 1, and every calendar day (weekends and holidays
  included) counts as a working day. Auto-create replaces all bars. It requires at least one ACTIVE
  WORKER on the project team. The Daily_Output_Rate is **2000** (estimate currency, normally PLN)
  per worker per day, configured globally as `foremen.schedule.daily-output-per-worker`. It is used
  only as this default inside auto-create: it is never returned by the API, never shown in the UI,
  and has no per-project override.
- **D8 — Free manual editing.** A caller with `WORK_SCHEDULE` UPDATE may drag any bar to any start
  day, resize it to any duration, plan an unscheduled row, or clear a bar. Overlaps between bars are
  allowed. There are no dependencies.
- **D9 — Volume of work and money masking.** Every row shows its volume of work as the number of
  estimate lines and its planned duration, and can be expanded to its estimate lines (work item,
  quantity, unit). The category net value is returned and shown only to callers who hold
  `ESTIMATE` READ, because changeset 135 restricted estimate money to ADMIN / MANAGER / ESTIMATOR.
  Man-days and the Daily_Output_Rate are internal to auto-create and are never returned or shown
  to anyone.
- **D10 — Project end date is advisory.** The schedule shows its computed finish and a warning when
  the finish falls after `Project.endDate`. It never writes project dates.
- **D11 — Lifecycle.** The schedule is editable while the project is in a Schedule_Editable_Status
  (`DRAFT`, `READY_TO_OFFER`, `OFFERED`, `APPROVED`, `ON_HOLD`). In every other status (`ACTIVE`,
  `COMPLETED`, `CANCELLED`) it is read-only; from `ACTIVE` onward FOR-06-04 takes it as its
  baseline.
- **D12 — Rows follow the estimate.** Rows are derived from the estimate at read time. A category
  that gains its first estimate line appears as an unscheduled row; a category that loses its last
  line disappears. Stored bars of categories that are no longer rows are never returned and are
  deleted by the next schedule write.
- **D13 — `WORK_SCHEDULE` grants.** ADMIN CRUD, MANAGER CRUD, FOREMAN READ + UPDATE, ESTIMATOR
  READ + UPDATE (mirrors FOREMAN per changeset 136), WORKER READ, FINANCIER READ, CLIENT READ; all
  project-scoped for non-ADMIN callers. Every write endpoint resolves to UPDATE.
- **D14 — Readiness gate `schedule`.** `DONE` when the project has at least one row and every row
  has a bar; `PARTIAL` when at least one but not every row has a bar; `BLOCKED` when no row has a
  bar or the project has no rows.
- **D15 — No notifications.** This spec emits no in-app notification. The planning schedule is a
  draft edited inside the workspace with no hand-over event; change notifications on the execution
  schedule belong to FOR-06.
- **D16 — No new translated entity fields.** Row names come from the existing `WorkCategory`
  `nameRU` / `namePL`, line names from `WorkItem` `nameRU` / `namePL`. The only new localized
  backend data is the `WORK_SCHEDULE` resource row (`name_ru`, `name_pl`, `description_ru`,
  `description_pl`) and the new error message codes.
- **D17 — Draft editing with explicit save.** Drag, resize, and dialog edits change a client-side
  draft; "Save" sends all changed bars in one request, "Discard" drops the draft. Saves and
  auto-create carry the Schedule_Version; a stale version is rejected with HTTP 409.
- **D18 — No Gantt library.** The timeline is built with the existing stack (Tailwind, pointer
  events, date-fns), matching the prototype's plain-CSS bars.

### Open questions

- **Q1 — RESOLVED.** Every calendar day is a working day for the Gantt; auto-create does not skip
  weekends or public holidays (D7).
- **Q2 — RESOLVED.** Man-days and the Daily_Output_Rate are not visible to anyone; the rate is only
  the configured default used by auto-create (D7, D9). There is no per-project override.
- **Q3 — RESOLVED.** ESTIMATOR gets READ + UPDATE on `WORK_SCHEDULE`, mirroring FOREMAN (D13).
- **Q4 — RESOLVED.** The crew size counts every ACTIVE WORKER member of the project. Per-category
  or per-tag crews are deferred to FOR-06.

## Glossary

- **Schedule_API**: the backend REST surface under `/api/project-schedules` defined by this spec.
- **Schedule_Service**: the backend service behind the Schedule_API that builds the Schedule_View,
  validates and persists bars, runs Auto_Create, and computes the Schedule_Readiness_Gate.
- **Project_Schedule**: the single `project_schedules` row of a project, holding the
  Schedule_Version. It is created on the first schedule write.
- **Schedule_Row**: one work category that has at least one estimate line in the project's
  estimate (D3). A Schedule_Row has zero or one Bar.
- **Bar**: one `project_schedule_bars` row: a Schedule_Row's planned period `(startDay,
  durationDays)` (D4).
- **Unscheduled_Row**: a Schedule_Row without a Bar.
- **Orphan_Bar**: a stored Bar whose work category is not currently a Schedule_Row (D12).
- **Schedule_Anchor**: Day 1 of the schedule: `Project.startDate` when set, otherwise the abstract
  "Day 1" (D4).
- **Finish_Day**: for a Bar, `startDay + durationDays − 1`; for the schedule, the largest Finish_Day
  of all Bars (absent when there are no Bars).
- **Category_Value**: the sum of `valueNet` of the estimate lines whose work item belongs to the
  Schedule_Row's work category, in the estimate currency.
- **Daily_Output_Rate**: the net estimate value one worker is assumed to produce per calendar day:
  the application configuration value `foremen.schedule.daily-output-per-worker`, default `2000`.
  Internal to the backend; never returned or displayed.
- **Crew_Size**: the number of Project_Members of the project whose Project_Role is `WORKER` and
  whose Assignment_Status is `ACTIVE`.
- **Computed_Duration**: for a Schedule_Row and a Crew_Size ≥ 1,
  `max(1, ceil(Category_Value / (Daily_Output_Rate × Crew_Size)))` calendar days.
- **Suggested_Duration**: the Computed_Duration of a Schedule_Row with the current Crew_Size, or
  null when the Crew_Size is zero; returned so the UI can prefill a new bar without exposing the
  rate or man-days.
- **Auto_Create**: the operation of D7 that replaces all Bars with the computed sequential layout.
- **Schedule_Version**: the optimistic-lock version of the Project_Schedule (0 when no
  Project_Schedule exists yet).
- **Schedule_View**: the read model returned by the Schedule_API (Requirement 5).
- **Schedule_Editable_Status**: `DRAFT`, `READY_TO_OFFER`, `OFFERED`, `APPROVED`, or `ON_HOLD`.
- **Schedule_Locked_Status**: any other project status (`ACTIVE`, `COMPLETED`, `CANCELLED`).
- **Accessible_Project**: for a non-ADMIN caller, a project the caller is a Project_Member of; for
  ADMIN, every project (the existing `ProjectScopedService` rule).
- **Money_Viewer**: a caller who holds `ESTIMATE` READ.
- **Schedule_Tab**: the real panel of the `scheduleDesign` workspace tab.
- **Schedule_Draft**: the client-side set of unsaved Bar changes in the Schedule_Tab.
- **Week_View** / **Day_View**: the two zoom levels of the Schedule_Tab timeline (D6).
- **Polish_Public_Holiday**: a Polish statutory non-working day: 1 Jan, 6 Jan, Easter Sunday,
  Easter Monday, 1 May, 3 May, Pentecost Sunday, Corpus Christi, 15 Aug, 1 Nov, 11 Nov, 24 Dec
  (from 2025), 25 Dec, 26 Dec.
- **Schedule_Readiness_Gate**: the readiness gate with key `schedule` (D14).
- **Readiness_Widget**: the FOR-05-01 workspace readiness component.
- **Workspace_Shell**: the FOR-05-01 project workspace route, tab strip, and design selector.
- **Audit_Log**: the existing `audit_log` mechanism used by FOR-05-09.

## Requirements

### Requirement 1: `WORK_SCHEDULE` resource and role grants

**User Story:** As an administrator, I want the planning schedule governed by its own project-scoped
resource, so that I can control who sees and edits it from the permission matrix.

#### Acceptance Criteria

1. THE database migration SHALL insert exactly one `resources` row with code `WORK_SCHEDULE`, Russian name "График работ", Polish name "Harmonogram prac", and non-blank Russian and Polish descriptions, in a changeset guarded by `onFail="MARK_RAN"` on `SELECT COUNT(*) FROM resources WHERE code = 'WORK_SCHEDULE'` and registered last in `changelog.xml` at the time of this spec.
2. THE database migration SHALL grant `WORK_SCHEDULE` to ADMIN with CREATE, READ, UPDATE, and DELETE, and to MANAGER with CREATE, READ, UPDATE, and DELETE.
3. THE database migration SHALL grant `WORK_SCHEDULE` to FOREMAN and to ESTIMATOR with READ and UPDATE only.
4. THE database migration SHALL grant `WORK_SCHEDULE` to WORKER, FINANCIER, and CLIENT with READ only.
5. WHEN the migration runs against a database on which the resource and grants of criteria 1–4 already exist, THE migration SHALL insert no row and change no row.
6. WHEN an ADMIN opens the permission matrix in the admin panel, THE Permission_Matrix SHALL display `WORK_SCHEDULE` with its localized name in the active UI language and the grants of criteria 2–4, editable through the same controls used for other resources.

### Requirement 2: Controller guarding with `WORK_SCHEDULE`

**User Story:** As a security reviewer, I want every schedule endpoint guarded by `WORK_SCHEDULE`, so that the tab and the API enforce the same matrix row.

#### Acceptance Criteria

1. THE schedule controller SHALL be annotated with `@PermissionResource("WORK_SCHEDULE")`, and every handler SHALL carry a `@PermissionOperation`, so that `PermissionAnnotationValidator` accepts the application at startup.
2. THE Schedule_API SHALL resolve the Schedule_View read (Requirement 5) and the readiness read (Requirement 12) to READ, and the bar save (Requirement 7) and Auto_Create (Requirement 8) to UPDATE.
3. IF an unauthenticated request reaches any Schedule_API endpoint, THEN THE Schedule_API SHALL respond with HTTP 401.
4. IF an authenticated caller lacks the operation of criterion 2 for the called endpoint, THEN THE Schedule_API SHALL respond with HTTP 403 and message code `error.access.denied` and change no data.
5. WHEN a WORKER, FINANCIER, or CLIENT caller with the Requirement 1 grants calls a write endpoint, THE Schedule_API SHALL respond with HTTP 403 and message code `error.access.denied`.

### Requirement 3: Project-scoped access

**User Story:** As a manager, I want to see and edit only the schedules of projects I belong to, so that other projects' plans stay private.

#### Acceptance Criteria

1. THE Schedule_Service SHALL implement `ProjectScopedService` with `getProjectIdPath()` returning the path from the Project_Schedule to its project id.
2. IF a non-ADMIN caller calls any Schedule_API endpoint for a project that is not an Accessible_Project of the caller, THEN THE Schedule_API SHALL respond with HTTP 404 and message code `error.entity.not.found` and change no data.
3. IF any caller, ADMIN included, calls any Schedule_API endpoint for a project id that does not exist, THEN THE Schedule_API SHALL respond with HTTP 404 and message code `error.entity.not.found`, with a response body identical to the one of criterion 2.
4. IF a Schedule_API request omits the `projectId` parameter or supplies a non-numeric value, THEN THE Schedule_API SHALL respond with HTTP 400 and change no data.
5. THE Schedule_API SHALL evaluate checks in this order: authentication (401), permission (403), request shape (400 for missing or malformed parameters and body), project existence and accessibility (404), lifecycle lock (409, Requirement 10), Schedule_Version (409, Requirement 11), then business validation (400 / 409 of Requirements 7–8).

### Requirement 4: Schedule rows derived from the estimate

**User Story:** As a manager, I want the schedule to always list exactly the work categories of my estimate, in catalog order, so that I never plan work that is not in the estimate or miss work that is.

#### Acceptance Criteria

1. THE Schedule_Service SHALL derive the Schedule_Rows of a project as the set of distinct work categories of the work items of all estimate lines of the project's estimate, with exactly one Schedule_Row per such category.
2. THE Schedule_Service SHALL order the Schedule_Rows by `WorkCategory.orderNo` ascending and then by work category id ascending, and THE Schedule_API SHALL never accept a client-supplied row order.
3. WHILE the project has no estimate or its estimate has no lines, THE Schedule_Service SHALL return zero Schedule_Rows.
4. WHEN a work category gains its first estimate line in the project's estimate, THE Schedule_View returned by the next read SHALL include that category as an Unscheduled_Row, unless a Bar for it is already stored.
5. WHEN a work category loses its last estimate line in the project's estimate, THE Schedule_View returned by the next read SHALL not include that category and SHALL not return its Bar.
6. WHEN any schedule write (Requirements 7–8) commits, THE Schedule_Service SHALL delete every Orphan_Bar of that project in the same transaction.
7. THE Schedule_Service SHALL include a work category as a Schedule_Row regardless of its `active` flag, as long as the estimate has at least one line in it.

### Requirement 5: Read the schedule

**User Story:** As a project participant with schedule access, I want to see every category with its volume and planned period, so that I understand how long the project is expected to take.

#### Acceptance Criteria

1. WHEN a caller with `WORK_SCHEDULE` READ requests the Schedule_View of an Accessible_Project, THE Schedule_API SHALL respond with HTTP 200 and a Schedule_View containing: the project id; the Schedule_Anchor date (`Project.startDate`, or null); `Project.endDate` (or null); an `editable` flag that is true only when the project is in a Schedule_Editable_Status and the caller holds `WORK_SCHEDULE` UPDATE; the Schedule_Version; the Crew_Size; the schedule Finish_Day (or null); a `finishDate` equal to the anchor date plus Finish_Day − 1 days when both exist (otherwise null); an `exceedsProjectEnd` flag; and the ordered Schedule_Rows.
2. THE Schedule_View SHALL return for each Schedule_Row: the work category id, code, `orderNo`, localized name, the number of estimate lines in the category, the Suggested_Duration (or null), the Bar's `startDay`, `durationDays`, and Finish_Day (all null for an Unscheduled_Row), and the list of the category's estimate lines, each with the estimate line id, work item id, localized work item name, quantity, and unit label, in estimate line order.
3. WHERE the caller is a Money_Viewer, THE Schedule_View SHALL also return the Category_Value of each Schedule_Row and the estimate currency code; WHERE the caller is not a Money_Viewer, THE Schedule_View SHALL omit both fields entirely and SHALL contain no line value or unit price.
4. THE Schedule_View SHALL contain no man-days value and no Daily_Output_Rate for any caller, ADMIN included; THE Schedule_Service SHALL compute the Suggested_Duration of each row with the configured Daily_Output_Rate and the current Crew_Size, and return null when the Crew_Size is zero.
5. THE Schedule_Service SHALL set `exceedsProjectEnd` to true if and only if `Project.startDate`, `Project.endDate`, and the schedule Finish_Day all exist and `finishDate` is after `Project.endDate`; otherwise false.
6. WHEN a caller requests the Schedule_View of a project that has no Project_Schedule row, THE Schedule_API SHALL respond with HTTP 200, Schedule_Version 0, every Schedule_Row unscheduled, and SHALL create no row.
7. THE Schedule_API SHALL return localized names in Russian when the request language is Russian and in Polish for any other or absent request language; IF the name in the resolved language is blank, THEN it SHALL return the other language's name, and IF both are blank, the code.
8. THE Schedule_View read SHALL reflect every estimate, team, project-date, and schedule change committed before the request was received.

### Requirement 6: Schedule persistence model

**User Story:** As a developer of FOR-06, I want the planning schedule stored in a simple, stable shape, so that the execution schedule can take it as its baseline.

#### Acceptance Criteria

1. THE database migration SHALL create table `project_schedules` with a unique, non-null foreign key to `projects` with ON DELETE CASCADE, a non-null `version` bigint, and the standard `BaseEntity` audit columns, and SHALL store no Daily_Output_Rate per project.
2. THE database migration SHALL create table `project_schedule_bars` with a non-null foreign key to `project_schedules` with ON DELETE CASCADE, a non-null foreign key to `work_categories`, a non-null integer `start_day`, a non-null integer `duration_days`, a unique constraint on (`schedule_id`, `work_category_id`), check constraints `start_day >= 1` and `duration_days >= 1`, and the standard `BaseEntity` audit columns.
3. THE Schedule_Service SHALL represent an Unscheduled_Row by the absence of a `project_schedule_bars` row and SHALL never store a Bar with a null start or duration.
4. WHEN the first schedule write for a project commits, THE Schedule_Service SHALL create exactly one Project_Schedule for that project, even under concurrent first writes; a concurrent first write that loses SHALL be rejected under Requirement 11 criterion 2.
5. THE Schedule_Service SHALL keep stored Bars unchanged when `Project.startDate` changes, so that every Bar keeps its day offset and moves with the new anchor (D4).

### Requirement 7: Save bars

**User Story:** As a manager, I want to save the bars I dragged or edited, so that the plan is kept for everybody.

#### Acceptance Criteria

1. WHEN a caller with `WORK_SCHEDULE` UPDATE submits a bar save for an Accessible_Project in a Schedule_Editable_Status with the current Schedule_Version and a list of entries `{workCategoryId, startDay, durationDays}`, THE Schedule_Service SHALL, in one transaction, set the Bar of each listed category to the given values (creating it if absent), remove the Bar of each listed category whose `startDay` and `durationDays` are both null, leave the Bars of unlisted categories unchanged, increment the Schedule_Version by one, and THE Schedule_API SHALL respond with HTTP 200 and the updated Schedule_View.
2. THE Schedule_Service SHALL accept any `startDay` and `durationDays` that are whole numbers from 1 to 3650 inclusive whose Finish_Day is at most 3650, regardless of weekends, public holidays, the project end date, and overlaps with other Bars.
3. IF an entry has exactly one of `startDay` and `durationDays` null, or either value is not a whole number from 1 to 3650, or its Finish_Day exceeds 3650, THEN THE Schedule_API SHALL respond with HTTP 400 and message code `error.schedule.bar.invalid` naming the offending work category id, and THE Schedule_Service SHALL change no Bar.
4. IF an entry's `workCategoryId` is not a current Schedule_Row of the project, or the same `workCategoryId` appears more than once in the request, THEN THE Schedule_API SHALL respond with HTTP 400 and message code `error.schedule.category.invalid`, and THE Schedule_Service SHALL change no Bar.
5. WHEN a bar save contains only entries equal to the stored state (and no Orphan_Bar exists), THE Schedule_Service SHALL change no data, keep the Schedule_Version, write no Audit_Log row, and THE Schedule_API SHALL respond with HTTP 200 and the unchanged Schedule_View.
6. IF the request body is missing, is not valid JSON, or has no entry list, THEN THE Schedule_API SHALL respond with HTTP 400 and change no data.

### Requirement 8: Auto-create the schedule

**User Story:** As a manager with an assigned crew, I want the system to lay out the whole schedule from the estimate values and the crew size, so that I get a realistic first plan in one click and only fine-tune it.

#### Acceptance Criteria

1. WHEN a caller with `WORK_SCHEDULE` UPDATE requests Auto_Create for an Accessible_Project in a Schedule_Editable_Status with the current Schedule_Version, a Crew_Size of at least one, and at least one Schedule_Row, THE Schedule_Service SHALL, in one transaction, replace all Bars of the project with exactly one Bar per Schedule_Row computed by criteria 2–3, delete every other Bar, increment the Schedule_Version by one, and THE Schedule_API SHALL respond with HTTP 200 and the updated Schedule_View.
2. THE Schedule_Service SHALL set the `durationDays` of each Schedule_Row to its Computed_Duration, using exact decimal arithmetic, the configured Daily_Output_Rate, and the Crew_Size at the moment of the request.
3. THE Schedule_Service SHALL set the `startDay` of the first Schedule_Row (in Requirement 4 order) to 1 and the `startDay` of every following row to the previous row's Finish_Day + 1, so that the Bars are consecutive and do not overlap. Every calendar day, including Saturdays, Sundays, and Polish_Public_Holidays, SHALL count as a working day, so no gap is inserted for them.
4. FOR ALL Schedule_Rows with a positive Category_Value, the computed duration SHALL satisfy `(durationDays − 1) × Daily_Output_Rate × Crew_Size < Category_Value ≤ durationDays × Daily_Output_Rate × Crew_Size` (capacity property).
5. IF the Crew_Size is zero, THEN THE Schedule_API SHALL respond with HTTP 409 and message code `error.schedule.crew.empty`, and THE Schedule_Service SHALL change no Bar.
6. IF the project has no Schedule_Row, THEN THE Schedule_API SHALL respond with HTTP 409 and message code `error.schedule.rows.empty`, and THE Schedule_Service SHALL change no Bar.
7. IF the computed layout would place any Finish_Day beyond 3650, THEN THE Schedule_API SHALL respond with HTTP 409 and message code `error.schedule.too.long`, and THE Schedule_Service SHALL change no Bar.
8. THE Auto_Create result SHALL be deterministic: two Auto_Create requests on the same estimate, Crew_Size, and Daily_Output_Rate SHALL produce identical Bars.

### Requirement 9: Daily output rate configuration

**User Story:** As an administrator, I want the daily output per worker to be a single system default used only by auto-create, so that planners get sensible durations without seeing or tuning internal productivity figures.

#### Acceptance Criteria

1. THE application configuration SHALL define `foremen.schedule.daily-output-per-worker` with default value `2000`, overridable by the environment variable `FOREMEN_SCHEDULE_DAILY_OUTPUT_PER_WORKER`, and THE application SHALL fail to start if the configured value is not a number greater than 0.
2. THE Schedule_Service SHALL use the configured Daily_Output_Rate as the only rate for every project, and THE Schedule_API SHALL offer no endpoint or request field that sets or overrides a rate.
3. THE Schedule_API SHALL return the Daily_Output_Rate and man-days in no response, and THE Schedule_Service SHALL write neither into any Audit_Log snapshot.
4. WHEN the configured Daily_Output_Rate changes (application restart with a new value), THE Schedule_Service SHALL leave every stored Bar unchanged; only later Auto_Create requests and Suggested_Duration values use the new rate.

### Requirement 10: Lifecycle lock

**User Story:** As a manager, I want the planning schedule frozen once the contract is signed, so that the baseline handed to execution does not drift.

#### Acceptance Criteria

1. WHILE a project is in a Schedule_Editable_Status, THE Schedule_Service SHALL accept the bar save and Auto_Create operations that pass all other checks, for ADMIN and non-ADMIN callers alike.
2. IF a bar save or Auto_Create operation from any caller, ADMIN included, targets a project in a Schedule_Locked_Status, THEN THE Schedule_API SHALL respond with HTTP 409 and message code `error.schedule.locked`, and THE Schedule_Service SHALL change no data and write no Audit_Log row.
3. WHILE a project is in a Schedule_Locked_Status, THE Schedule_API SHALL serve the Schedule_View and readiness reads with HTTP 200 and the same content rules as in a Schedule_Editable_Status, with `editable` false.
4. WHEN a project moves to a Schedule_Locked_Status, THE Schedule_Service SHALL keep the Project_Schedule and every Bar unchanged, so that FOR-06-04 can read them as the baseline.
5. WHILE a project is in a Schedule_Locked_Status, THE Schedule_Service SHALL not delete Orphan_Bars of that project.

### Requirement 11: Concurrent edits

**User Story:** As a manager working with a colleague, I want my save rejected rather than silently overwriting a newer plan, so that nobody loses work.

#### Acceptance Criteria

1. THE bar save and Auto_Create requests SHALL carry the Schedule_Version the client last read.
2. IF the supplied Schedule_Version differs from the current Schedule_Version of the project, THEN THE Schedule_API SHALL respond with HTTP 409 and message code `error.schedule.conflict`, and THE Schedule_Service SHALL change no data.
3. IF two writes with the same Schedule_Version are processed concurrently, THEN THE Schedule_Service SHALL commit at most one of them, and THE Schedule_API SHALL reject every other one under criterion 2.

### Requirement 12: Schedule readiness gate

**User Story:** As a manager preparing a project for signing, I want the readiness tracker to show whether the work is planned, so that I know whether scheduling still blocks signing.

#### Acceptance Criteria

1. THE Schedule_Service SHALL compute the Schedule_Readiness_Gate as `DONE` when the project has at least one Schedule_Row and every Schedule_Row has a Bar, `PARTIAL` when at least one but not every Schedule_Row has a Bar, and `BLOCKED` when no Schedule_Row has a Bar or the project has no Schedule_Row.
2. WHEN a caller with `WORK_SCHEDULE` READ requests the schedule readiness of an Accessible_Project, THE Schedule_API SHALL respond with HTTP 200, the gate key `schedule`, the gate state, the number of Schedule_Rows, and the number of scheduled Schedule_Rows.
3. FOR ALL schedules, THE gate state SHALL be `DONE` if and only if the row count is positive and equals the scheduled count, and `BLOCKED` if and only if the scheduled count is zero (consistency property).
4. WHILE a design-stage project is displayed and the viewer holds `WORK_SCHEDULE` READ, THE Readiness_Widget SHALL display one chip for the Schedule_Readiness_Gate with the localized gate name and the localized state label, using three visually distinct styles for `DONE`, `PARTIAL`, and `BLOCKED` so that the state can be identified from the label text alone.
5. WHILE the viewer lacks `WORK_SCHEDULE` READ, THE Readiness_Widget SHALL omit the chip, exclude the gate from the headline percentage, and send no schedule readiness request.
6. THE Readiness_Widget SHALL count the Schedule_Readiness_Gate in the headline percentage with the existing rule `round(100 × count(DONE) / count(displayed gates))`; a `PARTIAL` gate counts in the denominator only.
7. WHEN a bar save or Auto_Create succeeds in the Schedule_Tab, or an estimate change adds or removes a Schedule_Row, THE Readiness_Widget SHALL show the server-computed gate state no later than the next readiness refresh after the save response, and within 2 seconds of a successful Schedule_Tab save, without a full page reload.
8. IF the schedule readiness request fails, THEN THE Readiness_Widget SHALL omit the chip, exclude the gate from the headline percentage, and show a localized indication that schedule readiness could not be loaded; all other tabs SHALL remain usable.

### Requirement 13: Audit trail of schedule changes

**User Story:** As an administrator, I want every committed schedule change audited, so that I can reconstruct who planned what and when.

#### Acceptance Criteria

1. WHEN a bar save or Auto_Create commits a change, THE Schedule_Service SHALL write exactly one Audit_Log row with action `UPDATE`, the Project_Schedule as subject, the id of the performing user, the time of the change, the operation kind (`SAVE_BARS` or `AUTO_CREATE`), and before and after snapshots containing the list of `(workCategoryCode, startDay, durationDays)` of all Bars.
2. WHEN the first schedule write of a project creates the Project_Schedule, THE Schedule_Service SHALL record the before snapshot as an empty bar list.
3. IF a schedule write is rejected or changes no data (Requirement 7 criterion 5), THEN THE Schedule_Service SHALL write no Audit_Log row.
4. THE Schedule_Service SHALL include no Category_Value, line value, unit price, man-days value, or Daily_Output_Rate in any Audit_Log snapshot.

### Requirement 14: Harmonogram tab registration

**User Story:** As a user of the project workspace, I want the existing Harmonogram tab to open the real schedule, so that planning happens next to the estimate.

#### Acceptance Criteria

1. THE `WORKSPACE_TABS` contract SHALL keep exactly one tab with key `scheduleDesign`, stage `design`, required permission `{ resource: 'WORK_SCHEDULE', operation: 'READ' }`, and label key `workspace.tab.scheduleDesign`, at its current position (after `margins`, before `documentSigning`), with `owner` `FOR-05` and `lazy` pointing at the Schedule_Tab.
2. WHILE the viewer holds `WORK_SCHEDULE` READ, THE Workspace_Shell SHALL show the tab in the main strip for a design-stage project and in the design selector for an execution-stage project, exactly once, using the unchanged `resolveWorkspaceTabs` / `resolveDesignSelectorTabs` resolvers.
3. WHILE the viewer lacks `WORK_SCHEDULE` READ, THE Workspace_Shell SHALL hide the tab everywhere, and IF such a viewer opens `/projects/:projectId/scheduleDesign`, THEN THE Workspace_Shell SHALL replace the URL with the default visible tab, render it, and send no Schedule_API request.
4. WHEN a viewer holding `WORK_SCHEDULE` READ selects the tab or opens `/projects/:projectId/scheduleDesign`, THE Workspace_Shell SHALL render the Schedule_Tab as active and record `scheduleDesign` as the remembered tab for that project.
5. THE Workspace_Shell SHALL render the tab label as "Harmonogram" in the pl locale and "График" in the ru locale.

### Requirement 15: Harmonogram tab: viewing the schedule

**User Story:** As a project participant, I want a clear Gantt with one row per category, its volume of work, and a calendar that shows weekends and holidays, so that I can read the plan at a glance on desktop and mobile.

#### Acceptance Criteria

1. WHEN the Schedule_View loads, THE Schedule_Tab SHALL render one row per Schedule_Row in the returned order, each with a fixed label column showing the localized category name and its volume of work (the number of estimate lines, the planned duration in days when the row has a Bar, and, for a Money_Viewer, the Category_Value formatted in the estimate currency; never man-days or the Daily_Output_Rate), and a timeline track showing the row's Bar or a localized "not scheduled" indication.
2. WHEN the viewer expands a row, THE Schedule_Tab SHALL show the row's estimate lines with localized work item name, quantity, and unit beneath it, and WHEN the viewer collapses it, SHALL hide them; the expand control SHALL carry the localized "show items" / "hide items" accessible name.
3. THE Schedule_Tab SHALL show the timeline in Week_View when it opens and SHALL offer a zoom control switching between Week_View and Day_View without a reload and without losing the Schedule_Draft.
4. WHILE the Schedule_Anchor is a date, THE Schedule_Tab SHALL label the timeline with calendar dates: in Week_View one column per ISO week (Monday–Sunday) labelled with the week number and its date range, in Day_View one column per day labelled with the day of month and the localized weekday, and SHALL show Bar start and end as dates.
5. WHILE the Schedule_Anchor is a date, THE Schedule_Tab SHALL visibly mark every Saturday and Sunday and every Polish_Public_Holiday on the timeline in both views with distinct styles, SHALL show the localized holiday name on hover and focus of a holiday mark, and SHALL show a legend for the weekend, holiday, and bar styles.
6. WHILE the Schedule_Anchor is absent, THE Schedule_Tab SHALL label the timeline with relative days ("Day 1", "Day 2", …) and relative weeks ("Week 1" = days 1–7, …), show Bar start and end as day numbers, show no weekend or holiday marks, and show a localized hint that the project has no start date and the schedule counts from day 1.
7. THE Schedule_Tab SHALL show a summary with the schedule finish (date or day number), the total number of days from day 1 to the Finish_Day, and the Crew_Size, and WHILE `exceedsProjectEnd` is true, a localized warning naming `Project.endDate`; WHILE `Project.endDate` lies within the displayed range, it SHALL draw a project-end marker on the timeline.
8. THE timeline SHALL span at least from day 1 to the later of the schedule Finish_Day, the project end day (when both dates exist), and day 28, plus 7 days, and SHALL scroll horizontally while the label column stays fixed.
9. WHILE the viewport width is less than 768px, THE Schedule_Tab SHALL keep the same rows, bars, and controls with a narrower label column showing the category name and the number of estimate lines, the timeline scrolling horizontally, and toolbar actions collapsed into an overflow menu.
10. WHILE the Schedule_View request is in progress, THE Schedule_Tab SHALL show a loading indicator and no rows; IF it fails, THEN it SHALL show a localized error and a retry action that re-issues the request.
11. WHILE the project has no Schedule_Row, THE Schedule_Tab SHALL show a localized empty state that points to the Estimate tab, and no timeline.
12. THE Schedule_Tab SHALL compute Polish_Public_Holidays for any year, including the movable feasts derived from Easter Sunday (Easter Monday = Easter + 1, Pentecost = Easter + 49, Corpus Christi = Easter + 60) and 24 December only from 2025 onward.

### Requirement 16: Harmonogram tab: editing the schedule

**User Story:** As a manager or foreman, I want to drag and resize any bar freely, plan or clear rows, and auto-create the whole plan, so that I can shape the schedule quickly.

#### Acceptance Criteria

1. WHILE the Schedule_View `editable` flag is true, THE Schedule_Tab SHALL let the viewer move a Bar by dragging its body and change its start or end by dragging its left or right edge, snapping to whole days in both views, with a live preview of the resulting start, end, and duration; a drag that would make the duration less than 1 day or the start less than day 1 SHALL be clamped to 1.
2. THE Schedule_Tab SHALL allow Bars of different rows to overlap and SHALL apply no restriction based on weekends, holidays, or the project end date.
3. WHILE `editable` is true, THE Schedule_Tab SHALL offer on every row an edit action that opens a dialog with the start (a date picker when the anchor is a date, a day number otherwise) and the duration in days, validating whole numbers from 1 to 3650 with Finish_Day ≤ 3650 and showing a localized field error otherwise; on an Unscheduled_Row it SHALL offer a "Schedule" action that opens the same dialog prefilled with start = schedule Finish_Day + 1 (or 1) and duration = the row's Suggested_Duration (or 1 when it is null); on a scheduled row it SHALL offer a "Remove from schedule" action.
4. WHILE `editable` is true, a focused Bar SHALL move by one day with Left / Right arrow keys and change its duration by one day with Shift + Left / Right, and Enter SHALL open the edit dialog of criterion 3.
5. THE Schedule_Tab SHALL record every move, resize, dialog edit, schedule, and remove in the Schedule_Draft, mark changed rows, show a localized "unsaved changes" indicator, and enable "Save" and "Discard" only while the Schedule_Draft is not empty.
6. WHEN the viewer activates "Save", THE Schedule_Tab SHALL send one bar save with the Schedule_Version and the changed rows only; WHEN it succeeds, SHALL clear the Schedule_Draft, render the returned Schedule_View, refresh the readiness chip, and show a localized success message; IF it fails, THEN SHALL keep the Schedule_Draft and show the localized message of the returned code (Requirement 17 criterion 3).
7. WHEN the viewer activates "Discard", THE Schedule_Tab SHALL drop the Schedule_Draft and show the last loaded Schedule_View without sending a request.
8. WHILE `editable` is true, THE Schedule_Tab SHALL show an "Auto-create" action; WHILE the Crew_Size is zero, the action SHALL be disabled with a localized hint pointing to the Team tab.
9. WHEN the viewer activates "Auto-create", THE Schedule_Tab SHALL open a confirmation dialog that explains in words that durations are derived from the estimate and the number of active workers and that categories are placed one after another from day 1, shows the Crew_Size, a warning that all current bars will be replaced, and, when the Schedule_Draft is not empty, a warning that unsaved changes will be lost; the dialog SHALL show no rate, no man-days, and no rate input. WHEN confirmed, it SHALL send one Auto_Create request carrying only the Schedule_Version; WHEN it succeeds, SHALL drop the Schedule_Draft, render the returned Schedule_View, refresh the readiness chip, and show a localized success message.
10. WHILE `editable` is false, THE Schedule_Tab SHALL render the schedule read-only (no drag handles, edit, schedule, remove, auto-create, save, or discard controls) and, when the project is in a Schedule_Locked_Status, show a localized read-only banner.
11. WHILE a save or Auto_Create request is pending, THE Schedule_Tab SHALL disable Save, Discard, Auto-create, and dragging, and SHALL send no further write request until it completes.
12. WHEN the viewer tries to leave the tab or the page while the Schedule_Draft is not empty, THE Schedule_Tab SHALL ask for localized confirmation before discarding the draft.
13. THE Schedule_Tab controls SHALL be reachable with the Tab key and operable with Enter or Space; every Bar SHALL carry a localized accessible name stating the category, start, end, and duration; dialogs SHALL trap focus, close on Escape, and return focus to their opener; drag operations SHALL always have the keyboard and dialog alternatives of criteria 3–4.

### Requirement 17: Localization

**User Story:** As a Polish- or Russian-speaking user, I want the Harmonogram tab and its messages in my language, so that I understand every label and error.

#### Acceptance Criteria

1. THE frontend locales `pl.json` and `ru.json` SHALL contain the same set of `projectSchedule.*` keys for every Schedule_Tab label, column header, toolbar action, zoom level, timeline label, legend entry, Polish_Public_Holiday name, summary text, warning, hint, banner, dialog text, field error, accessible name, success message, error message, and the Readiness_Widget `schedule` gate name, states, and load error, where each value is a non-blank string that differs from its key and has the same interpolation placeholders as its counterpart.
2. THE backend message bundles `messages.properties` (Polish) and `messages_ru.properties` SHALL contain a non-blank text for each new message code: `error.schedule.bar.invalid`, `error.schedule.category.invalid`, `error.schedule.crew.empty`, `error.schedule.rows.empty`, `error.schedule.too.long`, `error.schedule.locked`, and `error.schedule.conflict`.
3. IF the Schedule_API rejects a request with a code of criterion 2, THEN it SHALL return the message text in Russian for a Russian request and in Polish otherwise, and THE Schedule_Tab SHALL show its own localized text for that code, falling back to the returned text, and then to a generic localized error, never to a raw code.
4. WHILE the UI language is Polish or Russian, THE Schedule_Tab SHALL render dates, weekday names, and numbers in that locale's format, with no raw i18n key shown.
5. WHEN the user switches the UI language while the tab is open, THE Schedule_Tab SHALL show all frontend texts in the new language without a reload and the category and work item names in the new language no later than the next Schedule_API response, keeping the Schedule_Draft.

### Requirement 18: Regression safety and test artifacts

**User Story:** As a QA engineer, I want existing behavior to keep working and the new behavior covered by repeatable tests, so that the change can be released with confidence.

#### Acceptance Criteria

1. THE estimate, materials, margins, offer, team, and document-signing tabs and APIs SHALL behave as before this spec; this spec SHALL modify no existing table other than adding the rows of Requirement 1.
2. THE `WORKSPACE_TABS` key list and order SHALL be unchanged, so that the existing resolver tests pass without modification.
3. THE FOR-03-08 test cases that enumerate guarded controllers and seeded resources SHALL be updated to include the schedule controller and the `WORK_SCHEDULE` resource with the Requirement 1 grants.
4. THE automated tests of this spec SHALL include property-based tests for the Auto_Create capacity property and contiguity (Requirement 8 criteria 3–4), Auto_Create determinism (Requirement 8 criterion 8), the readiness consistency property (Requirement 12 criterion 3), the bar validation bounds (Requirement 7 criteria 2–3), the row derivation and ordering (Requirement 4 criteria 1–2), the Polish_Public_Holiday computation (Requirement 15 criterion 12), and the frontend day/date mapping and drag snapping (Requirement 16 criterion 1).
5. THE spec folder SHALL contain a `test-cases.md` written in Russian, grouped by feature, in which every test case has an ID, a title, preconditions, numbered steps, an expected result per step, and the requirements it covers, with API scenarios against the Dockerized application (base URL `http://localhost:8080`) for the Schedule_API and browser scenarios for the Schedule_Tab and the readiness chip, and at least one test case for each of Requirements 1–17.
6. THE `test-cases.md` SHALL state, for each group, its repeatability strategy (per-run data generator with a run-id in every created identifier, or explicit teardown), so that every scenario passes on two consecutive runs without manual cleanup, and SHALL end with a regression group and an MD report template per the workspace `test-cases` standard.
