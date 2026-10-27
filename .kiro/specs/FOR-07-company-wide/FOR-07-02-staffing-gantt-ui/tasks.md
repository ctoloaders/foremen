# Implementation Plan: FOR-07-02 — Staffing Gantt UI

## Overview

Frontend-only, read-only company-wide staffing Gantt. The plan lands the pure foundation first
(types mirroring the FOR-07-01 DTOs, the index-based `projectColor`, the date-window + bar-date
helpers, the query serializer, and the view-state model — each with its property/unit tests), then
the api + hooks, then the Gantt components and the page, then the shared-config wiring (nav
re-point, route-permission, router lazy route) and the i18n keys, then `test-cases.md`, then final
verification.

**Single git repo** (per `.kiro/steering/git-repo-structure.md`): all code lives in the **frontend
repo** (`foremen-frontend/`) except this spec's `.kiro/` documents. Any code commit is made **from
inside `foremen-frontend/`**.

**No backend change, no migration.** This spec reuses the FOR-07-01 `COMPANY_SCHEDULE` resource and
`GET /api/company-schedule`; the changelog and all backend code are untouched.

**Test-run policy.** Frontend tests only (Vitest) — no Gradle run. Verify with the company-schedule
unit/property/component tests plus the navigation, route-permissions, and locale-parity tests.

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 1.2, 1.3, 1.4, 1.5 — the DTO/types file, the `projectColor` palette,
  the `dateWindow` helper, the `barDates` helper, and the `viewState`/`serializeQuery` helpers are
  independent pure modules in distinct files.
- **Wave 2 (parallel):** 2.1, 2.2, 2.3, 2.4 — the property/unit tests for the four pure helpers
  (distinct test files; each depends only on its helper from Wave 1).
- **Wave 3 (parallel):** 3.1, 3.2 — the api module + query keys (depends on types) and the
  view-state hook (depends on viewState lib); distinct files.
- **Wave 4:** 3.3 — the `useCompanySchedule` read hook (depends on the api module).
- **Wave 5 (parallel):** 4.1, 4.2 — the read-only `CompanyGanttBar` and the `ProjectLegend` +
  `UndatedBarsRail` + `ConflictBadge` leaf components (distinct files; depend on types + projectColor).
- **Wave 6:** 4.3 — `CompanyTimelineHeader` + `CompanyGanttRow` + `CompanyGanttGrid` (depend on the
  bar + leaf components + timeline/holidays + barDates).
- **Wave 7:** 4.4 — `CompanyScheduleFilterBar` (depends on types + dateWindow + view-state shape).
- **Wave 8:** 4.5 — `CompanySchedulePage` (default export; composes grid + legend + filter bar +
  states; depends on the hooks + all components).
- **Wave 9 (parallel):** 5.1, 5.2 — shared-config wiring (nav + route-permission + router) and the
  i18n keys (distinct files).
- **Wave 10:** 6 — component/config/routing tests (depend on the page + wiring).
- **Wave 11:** 7 — Author `test-cases.md` (depends on the implementation + tests).
- **Wave 12:** 8 — final verification + checkpoint (depends on everything).

## Tasks

- [x] 1. Pure foundation (types + helpers)
  - [x] 1.1 Types mirroring the FOR-07-01 DTOs
    - Create `src/features/company-schedule/types.ts`: `BarKind`, `CompanyRole`, `ProjectedBar`,
      `ConflictPair`, `MemberRow`, `ProjectLegendEntry`, `CompanyScheduleView`, `Granularity`,
      `CompanyScheduleQuery` (design §Types). No money/man-days/rate/pay-tier field anywhere.
    - _Requirements: 2.5, 9.4_
    - _Depends on: none_
  - [x] 1.2 Index-based `projectColor`
    - Create `src/features/company-schedule/lib/projectColor.ts`: `projectColor(colorIndex): string`
      = `WORKER_COLOR_PALETTE[((i % n) + n) % n]`, importing the palette hex list from
      `@/features/project-schedule/lib/workerColor`. Index-based (NOT the hash-based `workerColor`).
    - _Requirements: 4.3, 7.1_
    - _Depends on: none_
  - [x] 1.3 Date-window helper
    - Create `src/features/company-schedule/lib/dateWindow.ts`: given `(granularity, period,
      enabled)` return `{ from, to }` — WEEK → ISO Monday..Sunday, MONTH → first..last day of the
      month, disabled → `{ from: null, to: null }`. Pure; uses `date-fns` consistent with the
      existing Gantt.
    - _Requirements: 6.3, 6.4, 6.5_
    - _Depends on: none_
  - [x] 1.4 Bar-date layout helper
    - Create `src/features/company-schedule/lib/barDates.ts`: `anchorOf(view, window)` (window `from`
      or earliest datable `startDate`, else today) and `barPosition(bar, anchor, timeline)` →
      `{ left, width } | null` (null for an Undatable_Bar) using the reused `dateToDay` / `barLeftPx`
      / `barWidthPx` with the inclusive `endDay − startDay + 1` width.
    - _Requirements: 4.1, 4.2, 5.4_
    - _Depends on: 1.1_
  - [x] 1.5 View-state + query serializer
    - Create `src/features/company-schedule/lib/viewState.ts`: `PersistedViewState`, `DEFAULT_VIEW`,
      `parseViewState(raw)` (fallback to default on absent/invalid), `toQuery(viewState)` →
      `CompanyScheduleQuery` (both/none roles → omit; date window from `dateWindow.ts`), and
      `serializeQuery(query)` (canonical stable key — sorted ids, normalized roles, from/to). Stores
      only view dimensions, never a payload.
    - _Requirements: 2.1, 2.3, 2.4, 6.5, 8.1, 8.3, 8.4_
    - _Depends on: 1.1, 1.3_

- [x] 2. Tests for the pure foundation
  - [x] 2.1 `projectColor` tests
    - **Property 3: Index-based project color is stable and in-palette** — in-palette, `index % n`,
      stable for equal indices (and non-negative-safe for any integer).
    - **Validates: Requirements 4.3, 7.1**
    - _Depends on: 1.2_
  - [x] 2.2 `dateWindow` tests
    - **Property 6: Granularity maps deterministically to the date window** — WEEK → Monday..Sunday;
      MONTH → first..last; disabled → null/null; granularity never leaks into params.
    - **Validates: Requirements 6.3, 6.4, 6.5**
    - _Depends on: 1.3_
  - [x] 2.3 `barDates` tests
    - **Property 4: Datable bar position matches its inclusive date interval**
    - **Property 5: Undatable bars are never positioned on the axis** (the `barPosition` → null half)
    - **Validates: Requirements 4.1, 4.2, 5.4**
    - _Depends on: 1.4_
  - [x] 2.4 `viewState` + `serializeQuery` tests
    - **Property 2: Query contains only supported params** (via `toQuery` → the api URL builder;
      `roles` only when exactly one role; no `page`/`size`/`sort`/`query`).
    - **Property 7: Persisted view state round-trips** (parse∘serialize = identity; invalid/absent →
      default; dimensions-only).
    - **Validates: Requirements 2.1, 2.3, 2.4, 8.2, 8.3, 8.4**
    - _Depends on: 1.5_

- [x] 3. API, query keys, and hooks
  - [x] 3.1 API module + query keys
    - Create `src/features/company-schedule/api/companyScheduleApi.ts`
      (`fetchCompanySchedule(query)` over `apiRequest`, building the URL with only
      `roles`/`projectIds`/`memberIds`/`from`/`to`, `roles` only when one role) and
      `src/features/company-schedule/api/queryKeys.ts` (`companyScheduleKeys.view(query)` using
      `serializeQuery`).
    - _Requirements: 2.1, 2.2, 2.3, 2.4_
    - _Depends on: 1.1, 1.5_
  - [x] 3.2 View-state hook
    - Create `src/features/company-schedule/hooks/useCompanyScheduleViewState.ts`: holds the view
      state, derives `query = toQuery(viewState)`, and persists/replays `PersistedViewState` to
      localStorage key `foremen:gantt:company-schedule` via a small typed hook modeled on
      `useLocalStoragePersistence` (prefix convention, ~500 ms debounce, restore-on-mount, try/catch).
      Exposes `{ query, viewState, setRoles, setProjects, setMembers, setGranularity, setPeriod,
      setDateWindowEnabled, clear }`.
    - _Requirements: 6.1, 6.2, 6.6, 8.1, 8.2, 8.3, 8.4_
    - _Depends on: 1.5_
  - [x] 3.3 Read hook
    - Create `src/features/company-schedule/hooks/useCompanySchedule.ts`: `useCompanySchedule(query)`
      = `useQuery({ queryKey: companyScheduleKeys.view(query), queryFn, staleTime: 30_000, retry: 2 })`.
    - _Requirements: 2.1, 2.2, 2.3_
    - _Depends on: 3.1_

- [x] 4. Gantt components and the page
  - [x] 4.1 Read-only bar
    - Create `src/features/company-schedule/components/CompanyGanttBar.tsx`: static focusable
      (`role="button"`, `tabIndex=0`) element filled with the project color; NO drag/resize/edit and
      NO mutation; click + Enter/Space → `onOpen(projectId)`; accessible labels for worker / foreman /
      undated variants from the i18n templates; conflicted emphasis + accessible conflict mention.
    - _Requirements: 4.3, 4.4, 4.5, 7.2, 10.2, 12.1, 12.2, 12.3_
    - _Depends on: 1.1, 1.2_
  - [x] 4.2 Legend, undated rail, conflict badge
    - Create `ProjectLegend.tsx` (swatch via `projectColor` + `projectName`, server legend order;
      optionally toggles the Project_Filter keeping `projectIds` consistent), `UndatedBarsRail.tsx`
      (chips for a row's Undatable_Bars with project name + color), and `ConflictBadge.tsx`
      (accessible warning, not color-only).
    - _Requirements: 5.1, 5.2, 7.1, 7.4, 10.1, 12.3_
    - _Depends on: 1.1, 1.2_
  - [x] 4.3 Timeline header, row, grid
    - Create `CompanyTimelineHeader.tsx` (reuse `buildTimeline` columns + `polishHolidays` shading),
      `CompanyGanttRow.tsx` (sticky label: name + localized role badge; track with lane-packed
      datable bars; undated rail when no window; conflict badge), and `CompanyGanttGrid.tsx` (sticky
      label column + scrolling date-axis timeline, ResizeObserver height sync, rows in server order).
    - _Requirements: 3.1, 3.2, 3.3, 4.1, 4.2, 4.6, 5.1, 5.3, 10.1_
    - _Depends on: 1.1, 1.4, 4.1, 4.2_
  - [x] 4.4 Filter bar
    - Create `CompanyScheduleFilterBar.tsx`: Role_Filter (worker/foreman/both), Project_Filter
      multiselect (legend options, names), Member_Filter multiselect (row options, names),
      granularity toggle (WEEK/MONTH) + period picker + "all dates", and "clear filters". Each change
      updates the view state (and thus the query); labels are localized and associated with controls.
    - _Requirements: 6.1, 6.2, 6.6, 6.7, 12.4_
    - _Depends on: 1.1, 1.3, 3.2_
  - [x] 4.5 Page (lazy default export)
    - Create `src/features/company-schedule/components/CompanySchedulePage.tsx` (default export):
      gates on `usePermission('COMPANY_SCHEDULE','READ')`; wires `useCompanyScheduleViewState` +
      `useCompanySchedule`; renders loading / error (+ retry) / empty / loaded (filter bar + legend +
      grid); surfaces a 403 as forbidden handling; deep-link `onOpen(projectId)` →
      `navigate('/projects/'+projectId+'/scheduleDesign')`; renders no money field.
    - _Requirements: 3.4, 7.2, 7.3, 9.1, 9.2, 9.3, 9.4, 9.5_
    - _Depends on: 3.2, 3.3, 4.3, 4.4_

- [x] 5. Shared-config wiring + i18n
  - [x] 5.1 Nav re-point + route-permission + router route
    - In `src/config/navigation.ts`: change the Staffing Gantt item's `requiredPermission` to
      `{ resource: 'COMPANY_SCHEDULE', operation: 'READ' }` (path/label/icon/bottomNav unchanged). In
      `src/config/route-permissions.ts`: add the self-describing `extra` entry
      `'/company/staffing-gantt': { resource: 'COMPANY_SCHEDULE', operation: 'READ' }`. In
      `src/app/router.tsx`: add the `React.lazy` import for `CompanySchedulePage` and the route
      `{ path: 'company/staffing-gantt', element: <SuspenseWrapper><CompanySchedulePage /></SuspenseWrapper> }`
      under the `PermissionGuard` children. Add no new ABAC resource; change no backend code.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
    - _Depends on: 4.5_
  - [x] 5.2 i18n keys (PL + RU, mirrored)
    - Add the `companySchedule.*` keys (design §Localization) to BOTH `src/locales/pl.json` and
      `src/locales/ru.json` with non-empty values. Do NOT re-add `nav.staffingGantt` (exists). Reuse
      the existing `projectSchedule.holiday.*` / `projectSchedule.legend.*` keys for calendar marks.
      Run the locale-parity test.
    - _Requirements: 11.1, 11.2, 11.3, 11.4_
    - _Depends on: none_

- [x] 6. Tests (component + config + routing)
  - [x] 6.1 Component tests
    - `CompanySchedulePage` loading/error(+retry)/empty/loaded + rows in server order + worker/foreman
      badges + no money text (**Property 9**); `CompanyGanttBar` read-only + click/Enter/Space deep-link
      with the right projectId + conflicted emphasis + accessible labels; `CompanyScheduleFilterBar`
      each filter rebuilds the query + clear resets + named options; `UndatedBarsRail` shown only
      without a window.
    - **Validates: Requirements 3.1, 4.4, 5.1, 5.3, 6.2, 6.6, 7.2, 9.1, 9.2, 9.3, 9.4, 10.1, 12.1, 12.2**
    - _Depends on: 4.5_
  - [x] 6.2 Config + routing tests
    - **Property 1: Resource alignment across menu, route, and page** — a `navigation` test asserting
      the Staffing Gantt item's `requiredPermission` is `COMPANY_SCHEDULE`/READ and NOT
      `WORK_SCHEDULE`, and `isNavItemVisible` true under `COMPANY_SCHEDULE:READ` / false otherwise; a
      `route-permissions` test asserting `requirementForPath('/company/staffing-gantt')` resolves to
      `{ COMPANY_SCHEDULE, READ }`. Confirm the `i18n.keys.test.ts` parity test passes.
    - **Validates: Requirements 1.2, 1.3, 1.4, 11.3**
    - _Depends on: 5.1, 5.2_

- [x] 7. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template). FOR-07-02 is a UI spec, so the scenarios are
    **browser-engine checks** against the Dockerized frontend (`http://localhost:3000`) with the
    backend at `http://localhost:8080`: the menu item appears only for ADMIN/MANAGER; the page loads
    the projection; filters (role/project/member/week|month window) change the rendered slice;
    legend colors match bars; a conflict badge appears for a double-booked worker; clicking a bar
    deep-links to the project schedule tab; a non-privileged user is redirected to `/403`. Result
    artifacts are MD reports with tables.
  - _Requirements: 1.4, 2.1, 3.1, 4.1, 6.1, 7.2, 9.1, 10.1_
  - _Depends on: 4.5, 5.1, 5.2_

- [x] 8. Final verification + checkpoint
  - Type-check + run the frontend unit/property/component tests plus the navigation,
    route-permissions, and locale-parity tests; confirm green. Confirm the Staffing Gantt menu item
    + route are gated by `COMPANY_SCHEDULE`/READ and the page renders the projection for an
    ADMIN/MANAGER. Confirm no backend diff exists. Then stage and commit from inside
    `foremen-frontend/` (single commit) — only if the user asks.
  - _Requirements: all_
  - _Depends on: 6.1, 6.2, 7_

## Notes

- Tasks marked with `*` would be optional (tests); none are marked optional here because the
  property/unit tests gate the pure helpers that carry the core correctness.
- **Git-repo structure** (`.kiro/steering/git-repo-structure.md`): the implementation is
  **frontend-only**, so the code commit MUST be made from **inside `foremen-frontend/`**; a root
  commit would capture only the `.kiro/` spec docs and silently miss all frontend source.
- No backend code, no Liquibase changeset, no new ABAC resource — the FOR-07-01 `COMPANY_SCHEDULE`
  resource and `GET /api/company-schedule` are reused as-is.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4", "1.5"] },
    { "id": 1, "tasks": ["2.1", "2.2", "2.3", "2.4"] },
    { "id": 2, "tasks": ["3.1", "3.2"] },
    { "id": 3, "tasks": ["3.3"] },
    { "id": 4, "tasks": ["4.1", "4.2"] },
    { "id": 5, "tasks": ["4.3"] },
    { "id": 6, "tasks": ["4.4"] },
    { "id": 7, "tasks": ["4.5"] },
    { "id": 8, "tasks": ["5.1", "5.2"] },
    { "id": 9, "tasks": ["6.1", "6.2"] },
    { "id": 10, "tasks": ["7"] },
    { "id": 11, "tasks": ["8"] }
  ]
}
```
