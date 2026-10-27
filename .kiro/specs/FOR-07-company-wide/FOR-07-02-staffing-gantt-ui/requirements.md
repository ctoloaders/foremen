# Requirements Document

## Introduction

This spec (**FOR-07-02-staffing-gantt-ui**) is the **frontend-only** company-wide staffing Gantt
screen. It renders the cross-project staffing projection delivered by the backend
**FOR-07-01-cross-project-schedule-core** (`GET /api/company-schedule`) as a **read-only** Gantt:
rows are workers / foremen (one row per `User`), the X axis is an absolute calendar-date axis, and
each bar is colored by its owning project. The screen adds a project legend, a filter bar
(role / project / member / date-window with a week|month granularity), a per-worker conflict
indicator, and a click-through deep-link from a bar to that project's planning-schedule tab. It is
reached from the **Staffing Gantt** top-level menu slot that FOR-07-00 added.

This spec writes **no backend code and no entity**. The company-schedule projection, its
`COMPANY_SCHEDULE` ABAC resource, and the localized resource row are all owned by FOR-07-01. The one
behavioral change this spec makes to shared frontend config is to **re-point the Staffing Gantt
navigation item and its route guard** from the interim `WORK_SCHEDULE`/READ requirement (a
placeholder FOR-07-00 wired because `COMPANY_SCHEDULE` did not yet exist) to the real
`COMPANY_SCHEDULE`/READ requirement, so the menu item, the route guard, the page's own permission
check, and the backend endpoint all agree on the same audience (ADMIN + MANAGER).

### Source-of-truth model (verified against FOR-07-01 and the frontend codebase)

- The backend endpoint is `GET /api/company-schedule`
  (`CrossProjectScheduleController`, `@PermissionResource("COMPANY_SCHEDULE")`, READ) with the
  complete query surface `roles` / `projectIds` / `memberIds` / `from` / `to` — **no**
  `page` / `size` / `sort` / `query` (it is a Gantt, not a paginated DataTable).
- The response is `CompanyScheduleView`: `rows[]` (one `MemberRow` per contributing `User`) plus a
  top-level `legend[]` (project id → name → `colorIndex`). A `MemberRow` carries `userId`,
  `userName`, `companyRole` (`"WORKER"` | `"FOREMAN"`), `bars[]`, and `conflicts[]` (pairs of bar
  indices into that row's `bars`).
- A `ProjectedBar` has `kind` (`WORKER_CATEGORY` | `FOREMAN_SPAN`), `projectId`, `projectName`,
  `colorIndex`, `workCategoryId` / `workCategoryName` (null for a foreman span), `startHalfDays` /
  `durationHalfDays` (null for a foreman span), `startDate` / `endDate` (ISO `yyyy-MM-dd`, both null
  for an Undatable_Bar), and `unanchored` (true for an Undatable_Bar).
- `COMPANY_SCHEDULE` READ is seeded to **ADMIN + MANAGER only** (FOR-07-01 R9.2). A non-ADMIN
  caller's projection is already scoped to that caller's accessible projects on the server
  (FOR-07-01 R6) — the UI performs no scope filtering of its own.
- Reusable frontend assets (verified present): `src/features/project-schedule/lib/timeline.ts`
  (`dayToDate`, `dateToDay`, `buildTimeline`, `barLeftPx`, `barWidthPx`, `DAY_WIDTH`),
  `src/features/project-schedule/lib/polishHolidays.ts`
  (`polishHolidays(year)`, `easterSunday`), `src/hooks/usePermission.ts` (`usePermission` +
  `PermissionRequirement`), the TanStack-Query `staleTime: 30_000` / `retry: 2` convention, the
  `apiRequest` client, the `React.lazy` + default-export router pattern, the loading / error / empty
  state pattern from `ScheduleTab.tsx`, and the PL/RU parity test `src/lib/i18n.keys.test.ts`.
- The per-project planning Gantt tab is `scheduleDesign` (FOR-05-10), mounted at
  `/projects/:projectId/scheduleDesign` inside the workspace shell (deep-link target).
- The existing color util `src/features/project-schedule/lib/workerColor.ts` exposes
  `WORKER_COLOR_PALETTE` (a fixed hex list) but maps by a **hash of a member id**; the backend
  instead sends a **0-based `colorIndex`**, so this spec adds an **index-based** `projectColor`
  function (reusing the same palette hex list) rather than reusing the hash mapping.

### Resolved design decisions

- **RESOLVED — Resource alignment.** The Staffing Gantt nav item, its route-permission entry, and
  the page's `usePermission` gate are all set to `COMPANY_SCHEDULE`/READ (not the interim
  `WORK_SCHEDULE`), matching the backend endpoint. No backend change (Requirement 1).
- **RESOLVED — Read-only.** The screen never mutates the projection; there is no drag, resize, save,
  or auto-create. Bars are static (Requirement 4).
- **RESOLVED — No notifications.** The screen emits no user-facing notification: it only reads the
  FOR-07-01 projection and performs no domain state change (see "Notifications" below).
- **RESOLVED — Granularity maps onto the date window.** The backend models only a `[from, to]`
  inclusive date window; the UI's week|month granularity + selected period compute that window and
  send `from` / `to`. No granularity value is sent to the server (Requirement 6, 8).
- **RESOLVED — Deep-link target.** A bar's click navigates to `/projects/:projectId/scheduleDesign`
  (the FOR-05-10 planning-schedule tab of the bar's owning project) (Requirement 7).
- **RESOLVED — View persistence.** The chosen filters + granularity + period are persisted to
  localStorage under a stable key via a small typed hook modeled on the house
  `useLocalStoragePersistence` (the existing hook persists only the paginated `TableState` shape),
  and replayed on next mount (Requirement 8).

## Glossary

- **Company_Schedule_Page**: the new top-level, read-only page component rendering the staffing
  Gantt, reached from the Staffing Gantt menu slot.
- **Staffing_Projection**: the `CompanyScheduleView` returned by `GET /api/company-schedule` — the
  `rows[]` plus the `legend[]`.
- **Member_Row**: one rendered row per `User` — a worker or a foreman — carrying that user's name,
  `companyRole`, bars, and conflict pairs.
- **Projected_Bar**: one bar on a Member_Row; a `WORKER_CATEGORY` bar (a worker's planned category
  over a date range) or a `FOREMAN_SPAN` bar (a foreman's whole-project span).
- **Undatable_Bar**: a Projected_Bar with null `startDate` / `endDate` and `unanchored = true` (an
  unanchored worker bar or a partial-date foreman span); it has no position on the date axis.
- **Project_Legend**: the rendered legend mapping each project in the projection to its color and
  name, from the response `legend[]`.
- **Project_Color**: the CSS color the UI derives from a bar's / legend entry's 0-based `colorIndex`
  via the index-based `projectColor(colorIndex)` palette function.
- **Conflict_Indicator**: the per-row visual mark shown when a Member_Row has at least one
  `conflicts[]` entry (the worker is double-booked across overlapping datable worker bars).
- **Filter_Bar**: the screen's controls for the Role_Filter, Project_Filter, Member_Filter, and the
  Date_Window (a week|month granularity toggle plus a period picker).
- **Role_Filter**: the UI control selecting `FOREMAN`, `WORKER`, or both; sent as the `roles` query
  param (omitted = both).
- **Project_Filter**: the UI multiselect of project ids (populated from the legend / projection);
  sent as the `projectIds` query param (omitted = all accessible).
- **Member_Filter**: the UI multiselect of `User` ids; sent as the `memberIds` query param
  (omitted = all).
- **Granularity**: the UI's time-window unit, `WEEK` or `MONTH`; it is a UI-only concept that, with
  the selected period, computes the Date_Window. It is never sent to the server.
- **Date_Window**: the `[from, to]` inclusive calendar-date pair the UI computes from the
  Granularity + selected period and sends as `from` / `to`.
- **Persisted_View_State**: the serialized `{ roles, projectIds, memberIds, granularity, period }`
  the UI writes to localStorage and replays on next mount.
- **Deep_Link**: the navigation from a clicked bar to `/projects/:projectId/scheduleDesign` for that
  bar's owning project.
- **Timeline_Lib**: the reused pure `src/features/project-schedule/lib/timeline.ts` module
  (`dayToDate` / `dateToDay` / `buildTimeline` / `barLeftPx` / `barWidthPx` / `DAY_WIDTH`).
- **Company_Schedule_Resource**: the `COMPANY_SCHEDULE` ABAC resource (READ) the page, nav item, and
  route guard are aligned to.

## Notifications

This feature is a **read-only** cross-project Gantt view. It emits **no notifications**: it only
reads the FOR-07-01 `GET /api/company-schedule` projection and performs no domain state change (no
create / update / delete of any entity). There is therefore no recipient, trigger, type, message, or
deep-linked notification in scope for this spec. (Confirmed with the user.)

## Requirements

### Requirement 1: Menu slot, route, and resource alignment

**User Story:** As an ADMIN or MANAGER, I want the Staffing Gantt menu item to open the company
staffing Gantt and to be gated by the same resource as the backend endpoint, so the menu, the route,
and the API agree and no under-privileged user is shown a dead link.

#### Acceptance Criteria

1. THE SYSTEM SHALL register a route for the Company_Schedule_Page at the path
   `/company/staffing-gantt` (the slot FOR-07-00 reserved), rendering the page as a lazy-loaded
   default export, consistent with the existing `React.lazy` + `SuspenseWrapper` router pattern.
2. THE SYSTEM SHALL set the Staffing Gantt navigation item's `requiredPermission` to
   `{ resource: 'COMPANY_SCHEDULE', operation: 'READ' }`, replacing the interim
   `WORK_SCHEDULE`/READ requirement FOR-07-00 wired for the slot.
3. THE SYSTEM SHALL ensure `requirementForPath('/company/staffing-gantt')` resolves to
   `{ COMPANY_SCHEDULE, READ }`, so the route guard redirects a caller lacking `COMPANY_SCHEDULE`
   READ to `/403`, identically to how the menu item is hidden from that caller.
4. WHEN a caller holds `COMPANY_SCHEDULE` READ (ADMIN via bypass, or MANAGER via the grant) THEN the
   SYSTEM SHALL show the Staffing Gantt menu item and allow the route; WHEN a caller lacks it THEN
   the SYSTEM SHALL hide the menu item (via `isNavItemVisible`) and deny the route (redirect to
   `/403`).
5. THE change in this requirement SHALL be nav-config + route-permission only — the SYSTEM SHALL add
   no new ABAC resource and SHALL NOT modify any backend code, reusing the FOR-07-01
   `COMPANY_SCHEDULE` resource as-is.

### Requirement 2: Fetch the projection with the filter contract

**User Story:** As the Company_Schedule_Page, I want to fetch the staffing projection with exactly
the FOR-07-01 filter parameters, so I render the slice the user selected without inventing an
unsupported contract.

#### Acceptance Criteria

1. THE Company_Schedule_Page SHALL fetch `GET /api/company-schedule` through the shared `apiRequest`
   client, sending only the supported query params: `roles`, `projectIds`, `memberIds`, `from`, and
   `to` (each omitted when the corresponding filter is unset).
2. THE Company_Schedule_Page SHALL use a TanStack-Query read hook whose query key encodes the active
   filter set, so a filter change refetches the matching slice and an identical filter set reuses the
   cache, following the house `staleTime: 30_000` / `retry: 2` convention.
3. THE Company_Schedule_Page SHALL NOT send any `page`, `size`, `sort`, or `query` parameter, because
   the endpoint is a Gantt and rejects / does not model those (FOR-07-01 R11.3).
4. WHEN the Role_Filter selects both roles (or neither is excluded) THEN the Company_Schedule_Page
   SHALL omit the `roles` param; WHEN it selects exactly one role THEN the Company_Schedule_Page
   SHALL send that single `FOREMAN` or `WORKER` value.
5. THE Company_Schedule_Page SHALL render exactly the rows, bars, legend, and conflicts the server
   returns, performing no client-side project-scope filtering of its own (scope is enforced
   server-side, FOR-07-01 R6).

### Requirement 3: Member rows for workers and foremen

**User Story:** As a manager, I want one row per worker and foreman with their name and role, so I
can read company-wide staffing load per person.

#### Acceptance Criteria

1. THE Company_Schedule_Page SHALL render one Member_Row per `MemberRow` in the response, in the
   server's returned order (the projection is deterministically ordered, FOR-07-01 R10.4), applying
   no client re-sort.
2. THE Company_Schedule_Page SHALL render each Member_Row's `userName` and a localized label for its
   `companyRole` (`WORKER` / `FOREMAN`) in the row's sticky label cell.
3. THE Company_Schedule_Page SHALL visually group or distinguish foreman rows from worker rows (e.g.
   a role badge or section) using `companyRole`, so a foreman's whole-project spans read apart from a
   worker's category bars.
4. WHEN the response has zero rows THEN the Company_Schedule_Page SHALL render the empty state
   (Requirement 9), not an empty grid shell.

### Requirement 4: Read-only date-axis Gantt bars colored by project

**User Story:** As a manager, I want each person's bars placed on a shared calendar date axis and
colored by project, so I can compare staffing across projects at a glance.

#### Acceptance Criteria

1. THE Company_Schedule_Page SHALL render a shared absolute calendar-date axis spanning the datable
   bars in view, computing a common anchor (the earliest visible bar `startDate`, or the Date_Window
   `from` when a window is set) and positioning each datable bar with the reused Timeline_Lib
   (`dayToDate` / `dateToDay` / `barLeftPx` / `barWidthPx`).
2. WHEN a Projected_Bar has non-null `startDate` and `endDate` THEN the Company_Schedule_Page SHALL
   position it at `barLeftPx(dateToDay(anchor, startDate), firstGridDay, dayWidth)` with width
   `barWidthPx(dateToDay(anchor, endDate) − dateToDay(anchor, startDate) + 1, dayWidth)`, so its span
   matches the inclusive `[startDate, endDate]` interval.
3. THE Company_Schedule_Page SHALL fill each bar with the Project_Color derived from the bar's
   `colorIndex` via `projectColor(colorIndex)`, so all bars of one project share one color.
4. THE Company_Schedule_Page SHALL render bars as **read-only** — it SHALL expose no drag, resize,
   edit, save, auto-create, or any write affordance, and SHALL fire no mutation request.
5. WHEN a `WORKER_CATEGORY` bar is rendered THEN the Company_Schedule_Page SHALL make its localized
   `workCategoryName`, `projectName`, and date range available (e.g. an accessible label / tooltip);
   WHEN a `FOREMAN_SPAN` bar is rendered THEN it SHALL present the `projectName` and the span dates
   and omit any work-category text.
6. THE Company_Schedule_Page SHALL render weekend and Polish-holiday shading on the date axis by
   reusing `polishHolidays`, consistent with the per-project Gantt, for the years the axis covers.

### Requirement 5: Undatable bars surfaced without a date-axis position

**User Story:** As a manager, I want workers and foremen whose bars have no absolute date to still be
visible, so unanchored projects and partial-date spans are not silently dropped.

#### Acceptance Criteria

1. WHEN no Date_Window is set and a Member_Row has one or more Undatable_Bars (`unanchored = true`)
   THEN the Company_Schedule_Page SHALL surface those bars in that row in a dedicated "undated" area
   (e.g. a left-rail chip list on the row), not positioned on the date axis.
2. THE Company_Schedule_Page SHALL render an Undatable_Bar's project name and Project_Color so the
   viewer can still read which project it belongs to.
3. WHEN a Date_Window is set THEN the Company_Schedule_Page SHALL rely on the server having excluded
   Undatable_Bars (FOR-07-01 R7.5) and SHALL NOT render an undated area for the filtered result.
4. THE Company_Schedule_Page SHALL never position an Undatable_Bar on the date axis.

### Requirement 6: Filter bar — role, project, member, date window

**User Story:** As a manager, I want to filter the Gantt by role, project, member, and a time window,
so I can focus on exactly the staffing slice I care about.

#### Acceptance Criteria

1. THE Filter_Bar SHALL provide a Role_Filter (worker / foreman / both), a Project_Filter multiselect
   (options drawn from the projection legend), a Member_Filter multiselect (options drawn from the
   returned rows), and a Date_Window control (a WEEK|MONTH granularity toggle plus a period picker).
2. WHEN the user changes any filter THEN the Company_Schedule_Page SHALL recompute the query params
   and refetch, so the rendered projection reflects the new selection.
3. WHEN the Granularity is WEEK and a week is selected THEN the Company_Schedule_Page SHALL set
   `from` to that ISO week's Monday and `to` to its Sunday; WHEN the Granularity is MONTH and a month
   is selected THEN it SHALL set `from` to the first day and `to` to the last day of that month.
4. THE Company_Schedule_Page SHALL send no granularity value to the server — only the computed
   `from` / `to` — because the backend models only a date window (FOR-07-01 R11.1).
5. WHERE the user clears the Date_Window (selects an "all dates" option) THE Company_Schedule_Page
   SHALL omit `from` and `to`, so the server returns datable and undatable bars (FOR-07-01 R7.6).
6. THE Filter_Bar SHALL provide a "clear filters" affordance that resets to the default view (both
   roles, all projects, all members, no Date_Window) and refetches.
7. THE Member_Filter and Project_Filter option lists SHALL present localized / human-readable labels
   (member `userName`, project `projectName`) rather than raw ids.

### Requirement 7: Project legend and deep-link to the project schedule

**User Story:** As a manager, I want a project color legend and a way to jump from a bar to that
project's schedule, so I can interpret colors and drill into a specific project's plan.

#### Acceptance Criteria

1. THE Company_Schedule_Page SHALL render a Project_Legend from the response `legend[]`, showing each
   project's `projectName` and a swatch of its `projectColor(colorIndex)`, in the legend's returned
   order.
2. WHEN the user activates (clicks / presses Enter on) a Projected_Bar THEN the Company_Schedule_Page
   SHALL navigate via the Deep_Link to `/projects/:projectId/scheduleDesign` for that bar's
   `projectId`, using client-side (SPA) navigation.
3. THE Deep_Link target route SHALL be reached through the existing project-workspace route; the
   target tab's own permission is enforced by the workspace / that tab, so the Company_Schedule_Page
   SHALL NOT pre-check the target's resource before navigating.
4. THE Project_Legend entries SHALL also be a usable affordance to toggle the Project_Filter (e.g.
   clicking a legend entry filters to / from that project), OR SHALL be purely informational — this
   is a UI choice, but WHERE a legend entry is interactive it SHALL keep the sent `projectIds`
   consistent with the Project_Filter control.

### Requirement 8: Persisted, replayable view state

**User Story:** As a manager, I want my Gantt view (filters, granularity, period) remembered, so
returning to the page restores the slice I was looking at.

#### Acceptance Criteria

1. THE Company_Schedule_Page SHALL persist the Persisted_View_State
   (`{ roles, projectIds, memberIds, granularity, period }`) to localStorage under a stable key
   (e.g. `foremen:gantt:company-schedule`), via a small typed hook modeled on the house
   `useLocalStoragePersistence` (same prefix convention, debounced write, restore-on-mount,
   try/catch graceful degradation).
2. WHEN the page mounts and a Persisted_View_State exists THEN the Company_Schedule_Page SHALL restore
   it and issue the first fetch with the replayed filters, so the restored view matches the saved one
   (the backend returns an identical projection for an identical filter set, FOR-07-01 R11.2).
3. WHEN the persisted value is absent or invalid JSON THEN the Company_Schedule_Page SHALL fall back
   to the default view (both roles, all projects, all members, no Date_Window) without error.
4. THE persistence SHALL store only the view dimensions — it SHALL NOT cache the projection payload
   itself (the payload comes from the server each fetch).

### Requirement 9: Loading, error, empty, and no-money states

**User Story:** As a user, I want clear loading, error, and empty states, so the page never shows a
broken or misleading grid, and I never see cost data.

#### Acceptance Criteria

1. WHILE the projection query is loading THE Company_Schedule_Page SHALL render a loading state
   (spinner + message), not a partial grid, mirroring the `ScheduleTab` loading pattern.
2. WHEN the projection query errors THE Company_Schedule_Page SHALL render an error state with a
   retry affordance that refetches, mirroring the `ScheduleTab` error pattern.
3. WHEN the projection is empty (zero rows) THE Company_Schedule_Page SHALL render an empty state
   explaining there is nothing to show for the current filters (and, for a fresh default view,
   that there is no accessible scheduled staffing), not a blank grid.
4. THE Company_Schedule_Page SHALL render no money, man-days, rate, or pay-tier value anywhere,
   because the FOR-07-01 projection carries none (FOR-07-01 R8); it SHALL rely on the time /
   assignment fields only.
5. WHEN the server responds 403 (caller lacks the grant despite reaching the page) THE
   Company_Schedule_Page SHALL surface that as the standard forbidden handling rather than a generic
   error, consistent with the route guard already redirecting unauthorized callers.

### Requirement 10: Conflict indicator

**User Story:** As a manager, I want double-booked workers flagged, so I can spot and resolve
staffing conflicts.

#### Acceptance Criteria

1. WHEN a Member_Row has one or more `conflicts[]` entries THEN the Company_Schedule_Page SHALL show
   a Conflict_Indicator on that row (e.g. a warning badge with a localized tooltip).
2. THE Company_Schedule_Page SHALL interpret each `conflicts[]` entry as a pair of indices into that
   row's `bars[]` and MAY visually emphasize the two conflicting bars, without recomputing overlap
   client-side (the server is authoritative, FOR-07-01 R5).
3. THE Company_Schedule_Page SHALL never show a Conflict_Indicator for a foreman's overlapping spans,
   because the server never emits a conflict for `FOREMAN_SPAN` bars (FOR-07-01 R5.5) — the UI simply
   reflects the returned `conflicts[]`.

### Requirement 11: Internationalization (PL / RU)

**User Story:** As a Polish- or Russian-speaking user, I want every label on the staffing Gantt
localized, so the screen reads naturally in my language.

#### Acceptance Criteria

1. THE Company_Schedule_Page SHALL source every user-facing string from i18n keys under a single new
   `companySchedule.*` namespace, covering at least: the page title, loading / error / retry / empty
   messages, the role labels and role filter, the project / member filter labels and placeholders,
   the granularity toggle and period picker labels, the "all dates" and "clear filters" labels, the
   legend header, the conflict tooltip, the deep-link action label, the undated-bars area label, and
   the bar accessible-label templates.
2. THE Company_Schedule_Page SHALL reuse the existing weekend / Polish-holiday legend keys where it
   renders the same calendar marks, rather than duplicating them.
3. THE SYSTEM SHALL add every new `companySchedule.*` key to both `src/locales/pl.json` and
   `src/locales/ru.json` with non-empty values, so the `src/lib/i18n.keys.test.ts` parity test
   passes (identical key sets, every leaf a non-empty string).
4. THE SYSTEM SHALL add no new navigation i18n key — the `nav.staffingGantt` label already exists
   (FOR-07-00); this spec only re-points that item's `requiredPermission`.

### Requirement 12: Accessibility and read-only interaction

**User Story:** As a keyboard and screen-reader user, I want the Gantt navigable and described, so I
can use it without a pointer.

#### Acceptance Criteria

1. THE Company_Schedule_Page SHALL render each bar with an accessible label naming the project, the
   work category (for a worker bar), and the date range (or an "undated" indication for an
   Undatable_Bar).
2. THE Company_Schedule_Page SHALL make a bar keyboard-focusable and activatable (Enter / Space) to
   trigger the Deep_Link, since the bar is an interactive navigation control.
3. THE Company_Schedule_Page SHALL expose the Conflict_Indicator with an accessible description, not
   color alone, so the conflict is perceivable without relying on color.
4. THE Filter_Bar controls SHALL have associated labels, so each control is identifiable to assistive
   technology.

> Note: full WCAG conformance requires manual testing with assistive technologies and expert review;
> these criteria define the in-scope, testable accessibility behaviors for this screen.
