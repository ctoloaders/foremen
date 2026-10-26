# Requirements Document

## Introduction

This spec (**FOR-07-01-cross-project-schedule-core**) is the **backend-only** core of the FOR-07
company-wide staffing Gantt. It delivers a **cross-project READ model** over the existing per-project
planning Gantt: it reads every accessible project's schedule bars and projects them into a
**member-indexed, date-ranged** aggregation keyed by `User` (worker / foreman) across all projects,
with a **deterministic per-project color** and **per-worker overlap/conflict detection**. The
rendered staffing-Gantt UI that consumes this contract is **FOR-07-02** and is explicitly out of
scope here.

The projection has **two distinct bar sources** that are combined into one member-indexed read model:

- **Worker category bars** — a WORKER's planned `project_schedule_bars` rows, each pinned to a
  Work_Category over a half-day range (the FOR-05-10b per-worker model). These participate in
  per-worker overlap/conflict detection.
- **Foreman span bars** — a synthesized bar per project a FOREMAN leads, spanning the **whole
  project** from `ProjectEntity.startDate` to `ProjectEntity.endDate`. A foreman is in charge of the
  entire project, not a single work category over a sub-range, so a foreman's row is built from these
  project-span bars and **not** from `project_schedule_bars`. Foreman span bars never participate in
  conflict detection — a foreman legitimately runs several overlapping projects at once.

This spec **reads and projects only** — it adds **no mutable domain entity or table**, and it does
**not** re-model or mutate the per-project schedule. The one persistence change it makes is a single
idempotent **seed changeset** adding a new read-only ABAC resource (`COMPANY_SCHEDULE`) and its READ
grants, following the established seed pattern (changeset `153-seed-work-schedule-resource.xml` /
`017-seed-users-resource.xml`), registered last in `changelog.xml`.

### Source-of-truth model (verified in code)

- The per-project planning Gantt is `ProjectScheduleEntity` (one per project, `@OneToOne project`,
  `@Version`, table `project_schedules`) owning `ProjectScheduleBarEntity` bars (table
  `project_schedule_bars`). After the FOR-05-10b rework, each bar is keyed per worker:
  `(schedule, projectMember, workCategory)`, with a **nullable `projectMember`** (null = an
  `Uncovered_Category` virtual bar, which has NO worker) and half-day fields `startHalfDays` /
  `durationHalfDays` (1-based half-days from the schedule anchor; both `>= 1`).
- A bar's worker is `ProjectScheduleBarEntity.projectMember.user` (a `UserEntity`, which has a
  `name`). The member carries `assignmentStatus` (ACTIVE / INACTIVE) and `projectRole` (a
  `RoleEntity` with a `code` — the Company_Role, e.g. `WORKER` / `FOREMAN`). `ProjectMemberEntity`
  carries a plain `projectId` (BIGINT) and an owning `project`.
- `ProjectEntity` (table `projects`) has a `name`, a nullable `startDate` and a nullable `endDate`
  (both `LocalDate` — verified in `ProjectEntity`), and a `status`. Day 1 of a schedule is the
  anchor (`startDate`) itself (`anchor.plusDays(day - 1)`). The foreman span bar's absolute dates are
  taken directly from `ProjectEntity.startDate` / `ProjectEntity.endDate` (no half-day conversion).
  `ProjectEntity` also exposes a read-only `members` collection (mapped by `project_id`,
  `insertable=false, updatable=false`) used for the foreman lookup.
- `ScheduleCalculator` (pure, static) converts half-days to days/dates:
  `startDayFromHalfDays(h) = h / 2.0`, `durationDaysFromHalfDays(h) = h / 2.0`,
  `finishHalfDay(s, d) = s + d - 1`, and `calendarDayOfHalfDay(f) = (f + 1) / 2` — the whole calendar
  day a half-day finish lands on (`ceil(f / 2)` for a positive offset). These apply to **worker
  category bars** only; foreman span bars use the project's own calendar dates directly.
- Project-scoped access is `ProjectScopedService.addRequiredQuery()`: a non-ADMIN caller's reads are
  restricted to `ProjectAccessCache.get(userId)` (their member project ids); an **exact-authority
  ADMIN** returns a `null` filter (sees all). `ProjectScheduleService` is a `ProjectScopedService`
  whose `getProjectIdPath()` is `project.id`, and whose custom `Schedule_API` methods gate up front
  with `loadAccessibleProject(projectId)`.

### Resolved design decisions

- **RESOLVED — Foreman as whole-project span.** A FOREMAN's row is built from Foreman_Span_Bars (one
  per led project), not from `project_schedule_bars`. If a `User` who is a FOREMAN in a project also
  happens to own worker category bars in that same project, the projection emits only that user's
  Foreman_Span_Bar for that project and does **not** also pull their category bars, to keep the
  "foreman = whole project" semantics clean (Requirement 1).
- **RESOLVED — Partial project dates are undatable.** A Foreman_Span_Bar is datable only when
  **both** `project.startDate` and `project.endDate` are present. If either is null the span bar is
  undatable (null `startDate` / `endDate`, flagged unanchored), excluded from Date_Window filtering
  and from conflict detection — consistent with how Unanchored_Projects are handled for worker bars
  (Requirements 4, 5, 7).
- **RESOLVED — Unanchored projects.** Worker bars of an Unanchored_Project (null `project.startDate`)
  are included with null dates and an unanchored flag, excluded from Date_Window filtering and from
  conflict detection (Requirements 4, 5, 7).
- **RESOLVED — `COMPANY_SCHEDULE` READ audience.** The resource grants READ to ADMIN and MANAGER
  only; no CREATE / UPDATE / DELETE (Requirement 9).

## Glossary

- **Cross_Project_Schedule_Service**: the new backend read/aggregation service that builds the
  company staffing projection from worker category bars and synthesized foreman span bars across the
  caller's accessible projects. It owns no mutable state.
- **Cross_Project_Schedule_Controller**: the new bespoke read-only REST endpoint
  (`@PermissionResource("COMPANY_SCHEDULE")`, READ) that returns the projection. It is NOT an
  `AdminController` CRUD controller.
- **Staffing_Projection**: the returned read model — a list of `Member_Row`s plus the project
  legend, over the caller's accessible projects within the supplied filters.
- **Member_Row**: one logical row per `User` who contributes at least one included bar (a WORKER's
  worker category bar or a FOREMAN's span bar) in an accessible project, carrying that user's name,
  Company_Role code, and that user's Projected_Bars across all their accessible projects, plus the
  user's conflict set. (Replaces the earlier role-specific name "Worker_Row" so the same row type
  carries both workers and foremen.)
- **Projected_Bar**: one bar of a `Member_Row`. It is one of two variants:
  - **Worker_Category_Bar**: derived from a non-null-member `project_schedule_bars` row — carries the
    owning project id, project name, Project_Color index, the work category id and localized name,
    the raw `startHalfDays` / `durationHalfDays`, and the bar's absolute `startDate` / `endDate`
    (when datable).
  - **Foreman_Span_Bar**: a synthesized project-span bar — carries the owning project id, project
    name, Project_Color index, the span's absolute `startDate` (= `project.startDate`) and `endDate`
    (= `project.endDate`) when datable, a `kind = FOREMAN_SPAN` marker, no work category, and no
    half-day fields.
- **Worker_Category_Bar**: a Projected_Bar synthesized from a Real_Bar (a worker's category bar).
- **Foreman_Span_Bar**: a Projected_Bar spanning an entire led project for a FOREMAN member,
  independent of `project_schedule_bars`; datable only when both `project.startDate` and
  `project.endDate` are present.
- **Real_Bar**: a `project_schedule_bars` row whose `projectMember` is **non-null** (it has a
  worker). An `Uncovered_Category` (null-member) bar is NOT a Real_Bar.
- **Uncovered_Bar**: a `project_schedule_bars` row whose `projectMember` is null (virtual
  Uncovered_Category bar). It has no worker and is excluded from the Staffing_Projection. (Only
  worker category bars have an Uncovered variant; Foreman_Span_Bars are a separate synthesized
  source with no uncovered notion.)
- **Foreman_Member**: an ACTIVE `ProjectMemberEntity` whose `projectRole.code` is `FOREMAN`.
- **Schedule_Anchor**: a project's `startDate`; day 1 of the schedule is the anchor itself. A project
  with a `null` startDate is an **Unanchored_Project**.
- **Unanchored_Project**: an accessible project whose `startDate` is `null`; its worker bars have
  only relative half-day offsets and no absolute calendar date.
- **Undatable_Bar**: a Projected_Bar with null absolute `startDate` / `endDate` and an unanchored
  flag — either a worker bar of an Unanchored_Project or a Foreman_Span_Bar of a project missing
  `startDate` and/or `endDate`.
- **Project_Color**: a deterministic palette index (0-based) assigned to each project appearing in
  the projection, stable across the projection and across requests for the same project.
- **Conflict**: for a single `Member_Row`, a pair of that member's datable **Worker_Category_Bars**
  whose absolute date intervals overlap (the worker is double-booked across projects or categories in
  the same window). Foreman_Span_Bars never form or participate in a Conflict.
- **Accessible_Project**: a project the caller may read — any project for a (exact-authority) ADMIN;
  a project in `ProjectAccessCache.get(userId)` for a non-ADMIN caller.
- **Company_Schedule_Resource**: the new read-only ABAC resource `COMPANY_SCHEDULE`, with only the
  READ operation used by this spec.
- **Date_Window**: an optional `[from, to]` inclusive calendar-date filter supplied by the caller;
  a Projected_Bar is in the window when its absolute date interval intersects `[from, to]`. A UI
  month or week picker (FOR-07-02) maps onto this single inclusive date window; the backend models no
  separate month/week enum.
- **Role_Filter**: an optional server-side filter over `projectRole.code` with accepted values
  `FOREMAN`, `WORKER`, or both; default = both roles.
- **Project_Filter**: an optional server-side filter listing one or more project ids; a supplied id
  outside the caller's Accessible_Projects is ignored (never leaked), never widening scope.
- **Member_Filter**: an optional server-side filter listing one or more `User` ids (the multiselect);
  default = all members.
- **Persisted_View_State**: the saved Gantt-view selection the FOR-07-02 UI stores and later replays
  — the chosen Role_Filter, Project_Filter, Member_Filter, and the chosen time granularity
  (week / month) plus the selected period — persisted by the house table-state mechanism
  (`useTableState` + `useLocalStoragePersistence`, localStorage keyed by a stable entity key,
  debounced ~500ms; `foremen-frontend/src/components/data-table/hooks/`). The UI maps its
  week/month granularity + selected period onto this read contract's single `[from, to]` Date_Window;
  the backend models no granularity enum. Persistence of this state is a FOR-07-02 (UI) concern, not a
  backend concern; this backend contract only has to be fully re-playable from it.
- **DataTable_List_Contract**: the house list contract for paginated, server-sorted admin tables —
  Spring `Pageable` (`page` / `size` / `sort`) plus an RSQL `query` filter, served by
  `AdminController.find`. This Gantt endpoint deliberately does NOT use the DataTable_List_Contract
  (see Requirement 11).

## Requirements

### Requirement 1: Member-indexed cross-project projection (workers and foremen)

**User Story:** As a manager, I want every worker's and foreman's planned bars aggregated into one
row per person across all the projects I can see, so I can view company-wide staffing load in a
single projection without opening each project.

#### Acceptance Criteria

1. WHEN the Cross_Project_Schedule_Service builds the Staffing_Projection THEN the
   Cross_Project_Schedule_Service SHALL produce exactly one Member_Row per distinct `User` who
   contributes at least one included bar — a Worker_Category_Bar (from a Real_Bar) or a
   Foreman_Span_Bar — in an Accessible_Project.
2. WHEN a Member_Row is built for a `User` acting as a WORKER THEN the Cross_Project_Schedule_Service
   SHALL include in that row every Real_Bar of that `User` across all Accessible_Projects, each as a
   Worker_Category_Bar.
3. WHEN a Member_Row is built for a `User` acting as a FOREMAN THEN the Cross_Project_Schedule_Service
   SHALL include in that row one Foreman_Span_Bar per Accessible_Project in which that `User` is a
   Foreman_Member, each spanning that project from `project.startDate` to `project.endDate`.
4. WHEN a Member_Row is built THEN the Cross_Project_Schedule_Service SHALL set the row's
   Company_Role code to the `projectRole.code` of the member that contributes the bars, so the
   consumer can distinguish Foreman_Span_Bars from Worker_Category_Bars and group foremen apart from
   workers.
5. WHERE a `User` is a member of two or more Accessible_Projects THE Cross_Project_Schedule_Service
   SHALL emit a single Member_Row for that `User` aggregating that user's bars from every such
   project, not one row per project.
6. WHEN a Worker_Category_Bar is built THEN the Cross_Project_Schedule_Service SHALL carry the owning
   project id, the owning project name, the work category id, the localized work category name, the
   raw `startHalfDays` and `durationHalfDays`, and the Project_Color index of the owning project.
7. WHEN a Foreman_Span_Bar is built THEN the Cross_Project_Schedule_Service SHALL carry the owning
   project id, the owning project name, the Project_Color index of the owning project, and a
   `FOREMAN_SPAN` kind marker, and SHALL carry no work category and no half-day fields.
8. IF a `User` is a Foreman_Member of an Accessible_Project and that same `User` also owns worker
   category bars in that same project THEN the Cross_Project_Schedule_Service SHALL emit only that
   user's Foreman_Span_Bar for that project and SHALL NOT also emit that user's Worker_Category_Bars
   for that project, preserving the "foreman = whole project" semantics.

### Requirement 2: Uncovered (null-member) worker bars excluded

**User Story:** As a consumer of the staffing projection, I want virtual uncovered-category bars left
out, so the projection shows only actual worker assignments.

#### Acceptance Criteria

1. WHEN the Cross_Project_Schedule_Service reads a project's bars THEN the
   Cross_Project_Schedule_Service SHALL exclude every Uncovered_Bar (a bar whose `projectMember` is
   null) from the Staffing_Projection.
2. IF an Accessible_Project's schedule contains only Uncovered_Bars and the project has no
   Foreman_Member THEN the Cross_Project_Schedule_Service SHALL contribute no Member_Row and no
   Projected_Bar from that project.
3. THE Cross_Project_Schedule_Service SHALL never emit a Member_Row that has no identifiable `User`.
4. THE exclusion in this requirement SHALL apply only to worker category bars; Foreman_Span_Bars are
   a separate synthesized source and have no uncovered variant.

### Requirement 3: Deterministic per-project color

**User Story:** As a consumer, I want each project to have a stable color across the projection and
across requests, so the same project reads as the same color every time.

#### Acceptance Criteria

1. WHEN the Staffing_Projection is built THEN the Cross_Project_Schedule_Service SHALL assign each
   distinct project appearing in the projection a Project_Color index, and SHALL use that same index
   for every Projected_Bar of that project within one response, including both Worker_Category_Bars
   and Foreman_Span_Bars of that project.
2. WHEN the same set of project ids appears in two separate requests THEN the
   Cross_Project_Schedule_Service SHALL assign each project id the same Project_Color index in both
   responses (deterministic by project id, independent of request timing).
3. THE Staffing_Projection SHALL include a project legend listing each project id, its name, and its
   assigned Project_Color index, so the consumer can render a stable legend.

### Requirement 4: Absolute date-axis projection, unanchored and partial-date bars

**User Story:** As a consumer, I want bars from different projects placed on a common absolute
calendar date axis, so I can compare staffing across projects that start on different dates.

#### Acceptance Criteria

1. WHERE a Worker_Category_Bar belongs to an anchored project (non-null `startDate`) THE
   Cross_Project_Schedule_Service SHALL compute the bar's absolute `startDate` as
   `anchor.plusDays(calendarDayOfHalfDay(startHalfDays) - 1)` and its inclusive `endDate` as
   `anchor.plusDays(calendarDayOfHalfDay(startHalfDays + durationHalfDays - 1) - 1)`, reusing
   `ScheduleCalculator.calendarDayOfHalfDay`.
2. WHERE a Foreman_Span_Bar belongs to a project with both a non-null `startDate` and a non-null
   `endDate` THE Cross_Project_Schedule_Service SHALL set the bar's absolute `startDate` to
   `project.startDate` and its inclusive `endDate` to `project.endDate`, with no half-day conversion.
3. IF a Worker_Category_Bar belongs to an Unanchored_Project (null `startDate`) THEN the
   Cross_Project_Schedule_Service SHALL set the bar's absolute `startDate` and `endDate` to `null`
   and SHALL mark the bar as an Undatable_Bar, while still carrying its raw `startHalfDays` /
   `durationHalfDays`.
4. IF a Foreman_Span_Bar belongs to a project missing its `startDate`, its `endDate`, or both THEN
   the Cross_Project_Schedule_Service SHALL set the bar's absolute `startDate` and `endDate` to
   `null` and SHALL mark the bar as an Undatable_Bar (a span is datable only when both project dates
   are present).
5. WHEN a response is built with no Date_Window THEN the Cross_Project_Schedule_Service SHALL still
   include Undatable_Bars (unanchored worker bars and partial-date foreman spans) in their member's
   row, so staffing is visible even though those bars have no absolute date.

### Requirement 5: Per-worker overlap / conflict detection (worker category bars only)

**User Story:** As a manager, I want to see when a worker is double-booked across projects or
categories in the same date window, so I can resolve staffing conflicts — while a foreman running
several overlapping projects is not flagged.

#### Acceptance Criteria

1. WHEN a Member_Row has two or more datable Worker_Category_Bars (both anchored) whose absolute date
   intervals overlap THEN the Cross_Project_Schedule_Service SHALL record that pair as a Conflict on
   that Member_Row.
2. THE Cross_Project_Schedule_Service SHALL treat a bar's absolute date interval as the inclusive
   closed interval `[startDate, endDate]`, so two bars conflict exactly when
   `a.startDate <= b.endDate AND b.startDate <= a.endDate`.
3. WHERE two Worker_Category_Bars belong to the same project and the same work category of the same
   worker THE Cross_Project_Schedule_Service SHALL still apply the same overlap rule (distinct bars
   are compared uniformly; a single bar never conflicts with itself).
4. IF either bar of a candidate pair is an Undatable_Bar (null `startDate` / `endDate`) THEN the
   Cross_Project_Schedule_Service SHALL NOT record a Conflict for that pair, because an undatable bar
   has no absolute interval to compare.
5. THE Cross_Project_Schedule_Service SHALL exclude Foreman_Span_Bars from conflict detection
   entirely — a Foreman_Span_Bar SHALL never be part of any Conflict pair, so overlapping led
   projects are never flagged as a conflict.
6. THE Conflict detection SHALL be scoped per member — the Cross_Project_Schedule_Service SHALL never
   report a conflict between bars of two different `User`s.

### Requirement 6: Project-scoped access honored

**User Story:** As a non-admin user, I want the company projection to show only the projects I am a
member of, so reusing a cross-project view does not leak other projects' staffing.

#### Acceptance Criteria

1. WHEN a non-ADMIN caller requests the Staffing_Projection THEN the Cross_Project_Schedule_Service
   SHALL include only bars (worker category bars and foreman span bars) from projects in
   `ProjectAccessCache.get(userId)` for that caller.
2. WHEN an exact-authority ADMIN caller requests the Staffing_Projection THEN the
   Cross_Project_Schedule_Service SHALL include bars from all projects (no project filter), mirroring
   the `ProjectScopedService` ADMIN bypass.
3. IF the caller is a non-ADMIN with an empty allowed-project set THEN the
   Cross_Project_Schedule_Service SHALL return an empty Staffing_Projection (no Member_Rows, empty
   legend).
4. THE Cross_Project_Schedule_Service SHALL resolve the caller's allowed project set from the same
   `ProjectAccessCache` the per-project schedule service uses, so a membership change invalidated in
   that cache is honored identically by the company projection.
5. THE Cross_Project_Schedule_Service SHALL apply the scope filter explicitly in the service (via
   `ProjectAccessCache`) rather than through a `ProjectScopedService.getProjectIdPath()` LIST
   filter, because the company endpoint is a bespoke read and not an `AdminController` CRUD surface.

### Requirement 7: Backend read contract and server-side filters

**User Story:** As the staffing-Gantt UI (FOR-07-02), I want a single read endpoint returning the
projection with role, project, member, and time filters, so I can fetch exactly the slice I render.

#### Acceptance Criteria

1. THE Cross_Project_Schedule_Controller SHALL expose a single read-only HTTP GET endpoint that
   returns the Staffing_Projection for the authenticated caller.
2. WHERE the caller supplies a Role_Filter THE Cross_Project_Schedule_Service SHALL include only
   members whose `projectRole.code` matches a value in the Role_Filter, accepting the values
   `FOREMAN` and `WORKER`; WHERE the caller supplies no Role_Filter THE Cross_Project_Schedule_Service
   SHALL include both roles.
3. WHERE the caller supplies a Project_Filter THE Cross_Project_Schedule_Service SHALL include only
   bars from the supplied project ids that are also Accessible_Projects; IF a supplied project id is
   not an Accessible_Project THEN the Cross_Project_Schedule_Service SHALL ignore that id and SHALL
   neither error nor expose that the id exists.
4. WHERE the caller supplies a Member_Filter THE Cross_Project_Schedule_Service SHALL include only
   Member_Rows whose `User` id is in the supplied list; WHERE the caller supplies no Member_Filter
   THE Cross_Project_Schedule_Service SHALL include all members.
5. WHERE the caller supplies a Date_Window (`from` and/or `to` inclusive calendar dates) THE
   Cross_Project_Schedule_Service SHALL include a Projected_Bar only when its absolute date interval
   intersects the Date_Window, and SHALL exclude every Undatable_Bar (unanchored worker bar or
   partial-date foreman span) whenever any Date_Window bound is supplied, because an undatable bar has
   no date to test.
6. WHERE the caller supplies no Date_Window THE Cross_Project_Schedule_Service SHALL include every
   Worker_Category_Bar and Foreman_Span_Bar of every Accessible_Project (datable and undatable).
7. WHEN more than one filter is supplied THEN the Cross_Project_Schedule_Service SHALL apply all
   supplied filters conjunctively (ANDed), and SHALL never let any filter widen scope beyond the
   caller's Accessible_Projects.
8. IF the caller supplies a Date_Window whose `from` is after its `to` THEN the
   Cross_Project_Schedule_Controller SHALL reject the request with HTTP 400 and change no data.
9. THE read endpoint SHALL be strictly read-only — the Cross_Project_Schedule_Service SHALL create,
   update, or delete no `project_schedules` or `project_schedule_bars` row and SHALL bump no schedule
   version while serving it.

### Requirement 8: No money or sensitive fields exposed

**User Story:** As a user, I want the staffing projection to be a pure time/assignment view, so it
never leaks rates, pay tiers, or man-days.

#### Acceptance Criteria

1. THE Staffing_Projection SHALL expose no money figure, no Daily_Output_Rate, no man-days value, and
   no worker pay tier / worker-type cost field in any Member_Row, Projected_Bar, or legend entry.
2. THE Cross_Project_Schedule_Service SHALL derive a Worker_Category_Bar from only the bar's time
   fields (`startHalfDays` / `durationHalfDays` / derived dates), its project, its worker identity and
   role, and its work category — and from no cost-bearing source.
3. THE Cross_Project_Schedule_Service SHALL derive a Foreman_Span_Bar from only the project's
   identity and calendar dates and the foreman's identity and role — and from no cost-bearing source.

### Requirement 9: ABAC resource and controller guarding

**User Story:** As a platform owner, I want the company schedule endpoint gated by a dedicated
read-only ABAC resource granted to the right roles, so only authorized roles can read cross-project
staffing.

#### Acceptance Criteria

1. THE SYSTEM SHALL seed a new ABAC resource `COMPANY_SCHEDULE` via a single idempotent Liquibase
   seed changeset following the `153-seed-work-schedule-resource.xml` pattern (an `onFail="MARK_RAN"`
   `COUNT(*)=0` resource precondition plus per-row `NOT EXISTS` self-guarded grant inserts),
   registered last in `changelog.xml`.
2. THE seed changeset SHALL grant the READ operation on `COMPANY_SCHEDULE` to ADMIN and MANAGER, and
   SHALL NOT grant any CREATE, UPDATE, or DELETE operation, because the resource is read-only.
3. THE seed changeset SHALL insert `name_ru`, `name_pl`, `description_ru`, and `description_pl` for
   the `COMPANY_SCHEDULE` resource row, matching the existing resource-seed column shape.
4. THE Cross_Project_Schedule_Controller SHALL be annotated `@PermissionResource("COMPANY_SCHEDULE")`
   with its read handler carrying a `@PermissionOperation("READ")` (or an equivalent
   `@RequiresPermission(resource = "COMPANY_SCHEDULE", operation = "READ")`), so it passes
   `PermissionAnnotationValidator` at startup and the `PermissionInterceptor` enforces READ.
5. THE SYSTEM SHALL add **no** new mutable managed entity or domain table — the only persistence
   change is the resource-seed changeset; entity-creation-rules steps 1 (resource seed) and 2 (ADMIN
   grant) apply, step 3 (controller annotation) applies, and step 4 (`getProjectIdPath()` for a
   project-scoped CRUD service) does **not** apply because this is a bespoke read endpoint, not a
   `ProjectScopedService` CRUD entity.

### Requirement 10: Empty and edge cases

**User Story:** As a consumer, I want well-defined empty results, so the UI can render an empty state
without errors.

#### Acceptance Criteria

1. IF the caller has no Accessible_Projects THEN the Cross_Project_Schedule_Service SHALL return a
   Staffing_Projection with zero Member_Rows and an empty legend.
2. IF an Accessible_Project has no `project_schedules` row (never scheduled) THEN the
   Cross_Project_Schedule_Service SHALL contribute no Worker_Category_Bar from that project and SHALL
   NOT fail, while still contributing a Foreman_Span_Bar for each Foreman_Member of that project.
3. WHEN every bar of a member is removed by the supplied filters (Role_Filter, Project_Filter,
   Member_Filter, or Date_Window) THEN the Cross_Project_Schedule_Service SHALL omit that member's
   Member_Row entirely — a Member_Row is emitted only when the member has at least one included
   Projected_Bar (Worker_Category_Bar or Foreman_Span_Bar) after all filters are applied.
4. THE Cross_Project_Schedule_Service SHALL produce a deterministic ordering of Member_Rows,
   Projected_Bars within a row, and legend entries, so identical inputs yield an identical response.

### Requirement 11: Persisted Gantt-view replay via the table-state standard

**User Story:** As the staffing-Gantt UI (FOR-07-02), I want the read contract to accept every
dimension of a persisted Gantt view, so I can faithfully restore a user's saved view from
localStorage by replaying those dimensions as read-contract filters, without the backend paginating
or server-sorting a Gantt.

#### Acceptance Criteria

1. THE Cross_Project_Schedule_Controller SHALL accept, as explicit server-side filters, every
   dimension a Persisted_View_State needs to be replayed: the Role_Filter, the Project_Filter, the
   Member_Filter, and the Date_Window (onto which the UI's week / month granularity plus selected
   period map), so no persisted Gantt-view dimension is un-restorable through the read contract.
2. WHEN the Cross_Project_Schedule_Service is called twice with the same Role_Filter, Project_Filter,
   Member_Filter, and Date_Window by the same caller THEN the Cross_Project_Schedule_Service SHALL
   return an identical Staffing_Projection on both calls, so a replayed Persisted_View_State yields
   the same projection (consistent with Requirement 10.4 determinism).
3. THE Cross_Project_Schedule_Controller SHALL NOT accept any pagination parameter (`page`, `size`),
   any server-side sort parameter (`sort`), or an RSQL `query` parameter, because the surface is a
   Gantt rather than a paginated DataTable; the projection ordering SHALL remain the fixed
   deterministic ordering of Requirement 10.4 and SHALL NOT be caller-controllable.
4. THE Cross_Project_Schedule_Controller SHALL expose only the filter parameters of Requirement 7
   (`roles`, `projectIds`, `memberIds`, `from`, `to`) as its complete query surface, so the
   DataTable_List_Contract (`Pageable` + RSQL `query` via `AdminController.find`) is deliberately not
   used for this Gantt endpoint.
5. THE persistence of a Persisted_View_State (the selected Role / Project / Member selection plus the
   time granularity and selected period) SHALL be a FOR-07-02 (UI) responsibility implemented through
   the house table-state mechanism (`useTableState` + `useLocalStoragePersistence`, localStorage keyed
   by a stable entity key, debounced), and SHALL NOT be a Cross_Project_Schedule_Service concern — the
   backend SHALL store no per-user view state and SHALL only serve the replayed filters.
