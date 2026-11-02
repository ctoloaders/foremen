# Bug report: FOR-07-02-staffing-gantt-ui

Screen URL: http://localhost:3000/company/staffing-gantt (reported against
`https://dev.foremen.eu/company/staffing-gantt`).

Owning spec: `.kiro/specs/FOR-07-company-wide/FOR-07-02-staffing-gantt-ui` (the UI:
`foremen-frontend/src/features/company-schedule/**` — `CompanySchedulePage`,
`CompanyScheduleFilterBar`, `CompanyGanttGrid`, `CompanyGanttRow`, `CompanyGanttBar`,
`CompanyTimelineHeader`, `lib/viewState.ts`, `hooks/useCompanyScheduleViewState.ts`). The projection
backend is FOR-07-01 (`CrossProjectScheduleService`, `CrossProjectScheduleProjector`,
`CrossProjectScheduleController`). Backend changes in this report are listed here, with a cross-ref
note, and cite FOR-07-01 requirements.

Notifications: none (read-only view). No new backend i18n entity fields.

## Round 1 (2026-11-02)

Source: meeting notes.

- [x] 1. Ability to select multiple projects and workers
  - Triage: the Project_Filter and Member_Filter are already checkbox multiselects
    (`CompanyScheduleFilterBar.tsx` `Multiselect` ~94-190, used ~289-307), and the API and backend
    already take lists (`companyScheduleApi.ts` ~37-62 sends repeated `projectIds`/`memberIds`;
    `CrossProjectScheduleController` takes `List<Long>`). Observed — once one project or worker is
    picked, the other options disappear from the list, so a second one can't be added. Expected — the
    option lists always offer every project and member in the caller's scope, whatever is currently
    selected, so several can be ticked.
    Root cause: the options come from the **filtered** response. `projectOptions = legend.map(...)`
    and `memberOptions = rows.map(...)` (`CompanyScheduleFilterBar.tsx` ~227-234), fed by the same
    `useCompanySchedule(query)` result (`CompanySchedulePage.tsx` ~81, ~165-168). The backend legend
    and rows only contain what survives the filters (`CrossProjectScheduleProjector.java` ~211-218,
    ~273-284). Layer: **frontend** (the spec wording in R6.1, "options drawn from the projection
    legend / returned rows", is what produced this; amend it).
    Fix direction: derive the option lists from a query that **excludes the Project/Member filters**
    and keeps the role and date window. Use a second `useCompanySchedule` call with
    `projectIds`/`memberIds` stripped (a cached, cheap read), and merge in any currently-selected ids
    that are missing so a selection is never orphaned. Show the selected items checked at the top. No
    backend change.
    Suspect: `components/CompanyScheduleFilterBar.tsx`, `CompanySchedulePage.tsx`,
    `hooks/useCompanySchedule*.ts`, `lib/viewState.ts` (`toQuery`).
    i18n: none new (reuses `companySchedule.filter.*` and the shared `referenceFilter.*` keys).
  - **User decision (2026-11-02):** reuse the existing shared single/multi-select filter component,
    the one used on `/catalog/works` for the work-category column filter, i.e. `ReferenceFilter`
    (`foremen-frontend/src/components/data-table/ReferenceFilter.tsx`). Clicking a name selects only
    that item and closes; clicking a checkbox toggles membership; it also has search, chips and
    clear. The gantt's local `Multiselect` is removed.
    The gantt options aren't a paged admin endpoint: MANAGER has no `USERS` READ, and `/api/users`
    would list every user, not the people on the gantt. So `ReferenceFilter` gains an optional
    `staticOptions` prop (client-side search, no fetch) plus an optional `optionTestIdPrefix`. Its
    DataTable behavior is unchanged. The gantt feeds it the options from the unfiltered (no
    project/member filter) projection.
  - _Requirements: FOR-07-02 R6.1, R6.2, R6.6, R6.7, R7.4; FOR-07-01 R7_
  - _Depends on: none_

- [x] 2. Also show foremen
  - Triage: observed — foremen are missing from the staffing gantt (on dev). Expected — every
    project's foremen appear as rows with their foreman span over the project, alongside managers and
    workers (managers → foremen → workers). Layer: **backend first** (FOR-07-01 projection), plus
    small UI parts. The renderer handles FOREMAN rows fine (`CompanyGanttRow.tsx` ~137-146,
    `CompanyGanttBar.tsx` ~98-122), so the rows aren't being produced.
    How rows are produced today: a user becomes a row only if they get at least one bar
    (`CrossProjectScheduleProjector.java` ~245-248). Foremen come only from project team members who
    are `ACTIVE` with project role `FOREMAN` (`CrossProjectScheduleService.toProjectInput` ~259-270,
    `isActiveWithRole` ~352-355). Each one gets one `FOREMAN_SPAN` over the project dates.
    Candidate causes, to confirm against dev data first (the spec owner picks the fix):
    - (a) The foreman's membership isn't `ACTIVE` (still proposed/invited from team selection) or
      uses another role code, so it's ignored.
    - (b) The project has no `startDate`/`endDate`. `foremanSpanDates` (~397-402) returns
      `{null, null}`, so the span only lands in the undated rail, and with a Date_Window on,
      `includeBar` (~304-306) drops it, which leaves no row.
    - (c) The project fails `isIncluded` (not ACTIVE and ended more than 14 days ago, or outside the
      window).
    - (d) Managers get a span only as stand-ins when a project has **no** foreman (~271-280).
    - (e) UI: the Role toggle (`CompanyScheduleFilterBar.tsx` ~215-239) has no MANAGER option.
      Choosing WORKER hides every FOREMAN_SPAN, and a stale Role/Member filter persisted in
      localStorage (`foremen:gantt:company-schedule`) can hide foremen without any visible sign.
    Fix direction (proposed): (1) Reproduce on a local copy of the dev project/team data and record
    which cause applies. (2) Backend: emit a FOREMAN_SPAN row for every ACTIVE FOREMAN member of each
    included project. For an undated project under a Date_Window, keep the foreman as a row instead of
    dropping it, e.g. anchor the span to the project's known date or the clamped window. That's a
    FOR-07-01 R4/R7.5 behavior change, so confirm it. (3) UI: add a MANAGER option to the Role
    toggle, make the "both/all" choice include managers and foremen, and make a filter that hides
    foremen visible (the clear-filters button is already there).
    Suspect: `foremen-backend/.../service/CrossProjectScheduleService.java`,
    `.../service/companyschedule/CrossProjectScheduleProjector.java`, and
    `components/CompanyScheduleFilterBar.tsx`. Backend tests to run (targeted only):
    `CrossProjectScheduleProjector*Test`, `CrossProjectScheduleService*Test`.
    i18n (PL + RU parity) if the MANAGER role option is added: reuse `companySchedule.role.manager`;
    `companySchedule.filter.roleAll` only if "both" is renamed to "all" (PL "Wszyscy", RU "Все").
  - **User report (2026-11-02):** `https://dev.foremen.eu/projects/1/team` has both a manager and a
    foreman set, but the gantt shows only the manager.
  - **Root cause (reproduced locally, 2026-11-02):** cause (b). The project has a `startDate` but no
    `endDate`. Worker bars are anchored on `startDate`, so they're dated and drawn on the axis. The
    FOREMAN_SPAN needs BOTH project dates (`foremanSpanDates`), so it's undatable. In the default view
    it only appears as a chip in the undated rail of the foreman's row. With a Date_Window, `includeBar`
    drops it, and the foreman, who has no other bars, loses their row entirely. Local repro: project 1
    with `start_date = 2026-10-20`, `end_date = NULL`. Without a window the foreman row shows
    `FOREMAN_SPAN (null, null, unanchored)` and the workers show dated bars. With
    `from=2026-10-01&to=2026-10-31` only the 2 worker rows come back.
  - **Fix (backend, FOR-07-01 projector):** derive the foreman span from what the project actually
    has. Start = `project.startDate` if set, otherwise the earliest dated worker bar of that project.
    End = `project.endDate` if set, otherwise the latest dated worker-bar end of that project,
    otherwise the start (a one-day span). The span is undatable only when no start can be found
    (project without `startDate`, so its worker bars are undatable too). The MANAGER role-toggle
    option is not in scope (not requested).
  - _Depends on: none_

- [x] 3. Add month as the top row above weeks
  - Triage: observed — the timeline header (`CompanyTimelineHeader.tsx`) has a week-number band
    (~157-193) over a weekday band in week zoom, and a single per-day band in day zoom (~238+). There
    is no month context. Expected — a month band as the **top** header row (e.g. "listopad 2026" /
    "ноябрь 2026"), with each month cell as wide as its days (`dayCount × dayWidth`). Weeks that
    straddle two months are split correctly. It shows in both week zoom and day zoom (day zoom is used
    when a Date_Window is on). Layer: **frontend only**.
    Fix direction: add a pure `monthColumns(days, anchor)` helper that groups the timeline `days` by
    `dayToDate(...)` year+month into `{ key, startDay, dayCount, date }`. Grouping by days handles
    split weeks. Keep it local to `company-schedule/lib` so the per-project Gantt (`project-schedule`)
    isn't affected. Render it as a new first `<div className="flex border-b">` inside
    `company-schedule-timeline`, for both zoom branches. Label with date-fns
    `format(date, 'LLLL yyyy', { locale: dateFnsLocale(i18n.language) })`; `LLLL` is the standalone
    month name in PL/RU. The sticky label column header must follow the taller header: the grid
    measures it, with fallbacks at `CompanyGanttGrid.tsx` ~87-89. Update those fallbacks and check
    that row alignment stays at 0px delta. Add a unit test for `monthColumns` (month boundary
    mid-week, year boundary).
    Suspect: `components/CompanyTimelineHeader.tsx`, `components/CompanyGanttGrid.tsx`, a new
    `lib/monthColumns.ts`.
    i18n: month names come from the date-fns locale (no keys). Add `companySchedule.timeline.month`
    for the band's aria-label (PL "Miesiąc", RU "Месяц").
  - _Requirements: FOR-07-02 R4.1, R4.6, R11, R12_
  - _Depends on: none_

- [x] V1. Validate Round 1 in the browser
  - Rebuild frontend (and backend if bug 2 changed it). On `/company/staffing-gantt`:
    - Bug 1: tick 2+ projects and 2+ workers; every option stays listed, the grid shows exactly the
      union, and clearing restores everything.
    - Bug 2: the project foremen in the test data appear as rows with spans (managers → foremen →
      workers), including under a Date_Window, and the MANAGER role option works.
    - Bug 3: a month band sits above the weeks in week zoom and above the days in day zoom; it splits
      straddling weeks correctly, is localized in PL/RU, and the label/track rows stay aligned.
    Restore any temporary data afterwards. Mark `[x]` only on a confirmed browser pass.
  - _Requirements: as 1, 2, 3_
  - _Depends on: 1, 2, 3_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 2-backend (projection, FOR-07-01 files), 3 (`CompanyTimelineHeader` +
  `CompanyGanttGrid` + new `lib/monthColumns.ts`). These are independent files.
- **Wave 2:** 1, then the UI part of 2. Both edit `CompanyScheduleFilterBar.tsx`, so run them one
  after the other: 1 (option sourcing), then 2-UI (Role toggle MANAGER option).
- **Wave 3:** V1 — browser validation; depends on 1, 2, 3.

## Round 1 — validation results (2026-11-02)

Fixes:
- **#1:** the gantt's local `Multiselect` now wraps the shared `ReferenceFilter`. It gained an
  optional `staticOptions` prop (client-side search, no fetch) and an `optionTestIdPrefix` prop;
  DataTable usage is unchanged. The options come from a second `useCompanySchedule` query without
  the project/member filters. Found during validation: every toggle made the page drop into its
  loading state, which unmounted the filter bar and closed the dropdown. Fixed with
  `placeholderData: keepPreviousData` in `useCompanySchedule`.
- **#2:** `CrossProjectScheduleProjector.foremanSpanDates(start, end, latestWorkerEnd)`. A project
  with a start date and no end date dates the span `start → latest worker-bar end` (or the start).
  New `CrossProjectScheduleProjectorForemanSpanTest` (3 tests). Existing projector property tests
  still pass.
- **#3:** new `lib/monthColumns.ts` (grouped by day, with unit test) and a month band as the first
  header row in both zooms. Labels use date-fns `LLLL yyyy` in the active locale. The grid's
  header-height fallbacks were raised. New i18n key `companySchedule.timeline.month` (PL/RU).

Validation environment: the shared working tree's backend did not compile at the time, because a
concurrent FOR-07-06 payroll refactor was in progress. The backend was therefore validated from a
temporary image built from a clean `HEAD` worktree plus only this round's three backend files
(`SPRING_JPA_HIBERNATE_DDL_AUTO=none`, because the local DB already carries FOR-07-06 changesets
192–198). The frontend container was rebuilt from the working tree. Logged in as ADMIN.

| # | Bug | Verified | Evidence |
|---|-----|----------|----------|
| 1 | Multi-select projects/workers | ✅ Fixed | Member dropdown: ticked 2 of 3 checkboxes. It stayed open, all 3 options stayed listed, trigger `Osoby: 2`, grid showed exactly Foremen Biuro + Valera. Clicking the name `Valera` selected only Valera and closed the dropdown. |
| 2 | Show foremen | ✅ Fixed | Project 1 temporarily set to `start_date=2026-10-20`, `end_date=NULL` (restored afterwards). API: foreman `FOREMAN_SPAN 2026-10-20 → 2026-10-31`, also present with `from=2026-10-01&to=2026-10-31`; before the fix it was undatable, and its row was dropped under the window. Browser: Kierownik row with a dated span bar in both the default view and the October window. |
| 3 | Month row above weeks | ✅ Fixed | Week zoom: `październik 2026` (13 days = 182px, from ISO week 43) then `listopad 2026`, above the week band. Day zoom (Oct window): `październik 2026` 1240px (31 × 40px) above the day columns. Rows aligned. |

Result: 3 fixed / 0 failed. Tests: backend `com.foremen.service.companyschedule.*` green.
Frontend `tsc -b` clean; `company-schedule` + `ReferenceFilter` suites 96/96 pass.
