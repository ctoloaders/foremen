# Additional tasks: FOR-07-02-staffing-gantt-ui (gantt view redesign)

Screen URL: http://localhost:3000/company/staffing-gantt

> **Status (2026-10-29):** NOT started. Depends on FOR-07-01's backend changes. Staged for a
> dedicated session (worker-row unfold-into-projects, manager rows, bar emphasis).

Enhancement batch (2026-10-29). Approved by the user. NEW feature tasks (not bug fixes). This spec
owns the staffing-gantt UI (`CompanySchedulePage`, `CompanyGanttGrid`, `CompanyGanttRow`,
`CompanyGanttBar`, `types.ts`, the view-state + fetch hooks). The backend projection changes are in
FOR-07-01's additional-tasks.

## Scope (user intent — UI parts)

1. **Worker rows that unfold into projects.** Each row is a worker shown by their **main role**.
   Collapsed, the row shows a **merged timeline** of all the worker's bars across projects; clicking
   the row unfolds it to show, per project, that project's bars for the worker (user decisions 6a,
   6d).
2. **Default + date-range inclusion.** Default view includes all workers and all projects the backend
   includes (ACTIVE or `endDate > now()-2w`); changing the date range shows projects active during the
   period — both driven by the FOR-07-01 backend inclusion rule (user decisions 6b, 6c). The UI just
   renders what the backend returns and keeps the Date_Window controls.
3. **Ordering + emphasis.** Rows ordered managers → foremen → workers (server order, rendered
   verbatim). Bars that represent a manager acting as foreman are **emphasized** (distinct styling)
   per the backend `managerActingAsForeman` flag (user decision 6d).

### Design anchors (grounded in code)

- `CompanyGanttGrid` renders `view.rows` verbatim (no client re-sort) — keep that; the new
  managers→foremen→workers order comes from the backend (FOR-07-01 task 3).
- `CompanyGanttRow` currently lane-packs a worker's bars flat. Change to: a collapsed MERGED timeline
  (one lane summarizing the worker's spans) + an expand control that reveals per-project sub-rows of
  that worker's bars. Expansion state is UI-local (per row).
- `types.ts` `CompanyRole` must admit `'MANAGER'`; `ProjectedBar` must add `managerActingAsForeman:
  boolean` to mirror the backend DTO (FOR-07-01 task 4).
- `CompanyGanttBar` renders a project-colored bar; add an emphasis variant when
  `bar.managerActingAsForeman` is true (e.g. a border/hatch + a role badge/tooltip).
- Role badge/label in `CompanyGanttRow` label cell must render MANAGER too (today only worker/foreman
  labels exist).

### i18n

- Frontend (PL + RU parity in `pl.json` / `ru.json`):
  - `companySchedule.role.manager` (role badge).
  - `companySchedule.expand` / `companySchedule.collapse` (aria-labels for the unfold control).
  - `companySchedule.managerAsForeman` (tooltip/label on an emphasized manager-as-foreman bar).

### Notifications

None (read-only view).

## Tasks

- [ ] 1. Mirror the backend DTO changes in `types.ts`
  - Extend `CompanyRole` to include `'MANAGER'`; add `managerActingAsForeman: boolean` to
    `ProjectedBar`. Keep the rest of `CompanyScheduleView`/`MemberRow` in sync with FOR-07-01's DTO.
  - _Requirements: FOR-07-02 R-view; mirrors FOR-07-01 task 4_
  - _Depends on: none_

- [ ] 2. Row unfold: merged collapsed timeline + per-project expansion
  - Rework `CompanyGanttRow` (and `CompanyGanttGrid` row rendering) so a collapsed row shows a MERGED
    timeline of the worker's bars across all projects, and a click toggles an expanded state showing
    per-project sub-rows (grouped by `projectId`, using `projectName` + project color). Expansion
    state is UI-local per row (e.g. a `Set<userId>` in the grid). Preserve conflict badges and the
    sticky label column.
  - _Requirements: user decisions 6a/6d_
  - _Depends on: 1_

- [ ] 3. Role label (incl. MANAGER) + managers→foremen→workers order
  - Render the MANAGER role badge in the row label (add `companySchedule.role.manager`). Rows are
    rendered in the server's order (managers→foremen→workers from FOR-07-01 task 3) with no client
    re-sort — verify the UI respects it and the badge matches `row.companyRole`.
  - _Requirements: user decision 6d_
  - _Depends on: 1_

- [ ] 4. Emphasize manager-as-foreman bars
  - In `CompanyGanttBar`, when `bar.managerActingAsForeman` is true, render an emphasized variant
    (distinct border/hatch + `companySchedule.managerAsForeman` tooltip) so these bars stand out.
  - _Requirements: user decision 6d_
  - _Depends on: 1_

- [ ] 5. i18n keys (PL + RU)
  - Add `companySchedule.role.manager`, `companySchedule.expand`, `companySchedule.collapse`,
    `companySchedule.managerAsForeman` to `pl.json` and `ru.json` at parity.
  - _Requirements: tasks 2, 3, 4_
  - _Depends on: none_

- [ ] 6. Author test-cases.md
  - Create/append `test-cases.md` per `.kiro/steering/test-cases.md` (UI browser-engine scenarios,
    Russian): a worker row shows a merged timeline collapsed and expands to per-project sub-rows;
    rows ordered managers→foremen→workers with the right role badges; manager-as-foreman bars are
    visually emphasized with the tooltip; the default view shows ACTIVE + recently-ended projects and
    a chosen Date_Window re-scopes them. Repeatability: read-only navigation against seeded data (no
    teardown); regression: the existing Date_Window / granularity controls, project legend, conflict
    badges, and bar deep-link still work.
  - _Requirements: the requirements covered (FOR-07-02 R-view, decisions 6a/6b/6c/6d)_
  - _Depends on: 1, 2, 3, 4, 5_

- [ ] V. Validate in the browser
  - Rebuild the frontend container (and backend for FOR-07-01). Open `/company/staffing-gantt`:
    confirm worker rows ordered managers→foremen→workers, collapsed merged timelines, per-project
    unfold on click, emphasized manager-as-foreman bars, default inclusion of ACTIVE + recently-ended
    projects, and date-range re-scoping.
  - _Requirements: as all tasks_
  - _Depends on: 1, 2, 3, 4, 5_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1 (types mirror), 5 (i18n keys) — independent files.
- **Wave 2 (parallel):** 2 (row unfold), 3 (role label/order), 4 (bar emphasis) — all depend on the
  types mirror (1) but touch different components (`CompanyGanttRow`/`Grid`, row label,
  `CompanyGanttBar`).
- **Wave 3:** 6 — test-cases.md depends on all implementation.
- **Wave 4:** V — browser validation depends on all implementation (+ FOR-07-01 backend).

> Cross-spec dependency: all tasks depend on FOR-07-01's DTO/projection changes (manager rows,
> `managerActingAsForeman`, role ordering, project inclusion). Schedule FOR-07-01 first.
