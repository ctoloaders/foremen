# Additional tasks: FOR-07-01-cross-project-schedule-core (gantt backend redesign)

Screen URL (consumer): http://localhost:3000/company/staffing-gantt

> **Status (2026-10-29):** NOT started. Staged for a dedicated session. Large projection + DTO change
> (manager rows, manager-as-foreman synthesis, role ordering, active-in-range inclusion).

Enhancement batch (2026-10-29). Approved by the user. NEW feature tasks (not bug fixes). This spec
owns the backend projection: `CrossProjectScheduleService`, the pure `CrossProjectScheduleProjector`,
and the `CompanyScheduleView` / `MemberRow` / `ProjectedBar` DTOs. The UI redesign is in FOR-07-02.

## Scope (user intent — backend parts)

The company staffing gantt is redesigned to: rows = workers shown by **main role**, each unfolding
into per-project bars (merged timeline when collapsed); default + date-range inclusion by project
activity; managers shown as foremen only for projects with no foreman, with those bars emphasized.
The backend changes:

1. **Admit MANAGER into the projection.** Today only WORKER bars + ACTIVE FOREMAN spans are built;
   MANAGER members never appear. Surface MANAGER members as rows and, per project, synthesize a
   foreman-style span for a manager ONLY when that project has no ACTIVE foreman (user decisions 5b,
   6d). Mark such spans as "manager acting as foreman" so the UI can emphasize them.
2. **Row "main role".** Each row carries the user's main role (MANAGER / FOREMAN / WORKER) so the UI
   can group/order managers → foremen → workers and label the row (user decision 6d: "show users by
   their main role").
3. **Per-project grouping within a row.** The projection must let a row be unfolded by project — i.e.
   bars carry their `projectId`/`projectName` (already do) and the response supports a per-project
   grouping/merged-timeline view (the merge itself can be UI-side, but the backend must return every
   project's bars for the user and a stable ordering — user decision 6a).
4. **Project active-in-range inclusion.** Define a project as included when
   **`status == ACTIVE` OR `endDate` is after `now() - 2 weeks`** for the DEFAULT view; when a date
   range is chosen, include projects whose `[startDate, endDate]` intersects the window (user
   decisions 6b, 6c). This is an explicit project-level inclusion rule, replacing today's purely
   emergent per-bar intersection for project membership.

### Design anchors (grounded in code)

- `CrossProjectScheduleService.toProjectInput` builds `ProjectInput` from WORKER schedule bars +
  ACTIVE FOREMAN members; `isActiveWithRole` filters by role code. Add MANAGER surfacing + the
  "manager-as-foreman when no foreman" synthesis here (resolve each project's ACTIVE foreman set
  first; if empty, promote its ACTIVE managers to synthesized foreman spans flagged
  `managerActingAsForeman`).
- `CrossProjectScheduleProjector.project` groups bars by user, forces row `companyRole` to FOREMAN on
  any foreman span, orders rows by `(userName, userId)`. Change: (a) row role must admit MANAGER;
  (b) add a role-rank comparator (MANAGER < FOREMAN < WORKER) as the primary row sort; (c) carry the
  "manager acting as foreman" flag on the relevant `ProjectedBar`.
- Project inclusion: `CrossProjectScheduleService` currently loads all in-scope projects regardless
  of status/date and lets `includeBar` drop by date. Add an explicit project-inclusion filter: for
  the default (no window) view, keep projects with `status == ACTIVE` OR `endDate > now() - 14d`; for
  a windowed view, keep projects whose `[startDate, endDate]` intersects `[from, to]`.
- DTO changes: `MemberRow.companyRole` must admit `MANAGER`; `ProjectedBar` gains a
  `managerActingAsForeman` boolean (emphasis flag). The frontend `CompanyRole` type + `ProjectedBar`
  mirror change is in FOR-07-02.

### i18n

- Backend: no new entity i18n fields (category names already localized per request locale).

### Notifications

None. The gantt is a read-only analytics projection (consistent with the existing R8 "no cost/
notifications" posture).

## Tasks

- [ ] 1. Surface MANAGER + manager-as-foreman synthesis in `toProjectInput`
  - In `CrossProjectScheduleService.toProjectInput`, resolve each project's ACTIVE FOREMAN set; if it
    is empty, promote the project's ACTIVE MANAGER(s) to synthesized foreman spans flagged
    `managerActingAsForeman = true`. Always surface MANAGER members as projection inputs (so manager
    rows exist even when the project has a foreman — their row shows by main role). Keep the existing
    foreman-also-worker precedence for workers.
  - _Requirements: FOR-07-01 R1 (projection), R2; user decisions 5b/6d_
  - _Depends on: none_

- [ ] 2. Explicit project active-in-range inclusion
  - Add a project-inclusion filter in `CrossProjectScheduleService` before projecting: default
    (no Date_Window) → include a project iff `status == ACTIVE` OR `endDate` is after `now() - 14
    days`; windowed → include iff `[startDate, endDate]` intersects `[from, to]`. Document the rule;
    keep the existing allowed-set / Project_Filter intersection.
  - _Requirements: FOR-07-01 R6/R7; user decisions 6b/6c_
  - _Depends on: none_

- [ ] 3. Projector: role-rank ordering + MANAGER row role + emphasis flag
  - In `CrossProjectScheduleProjector`: (a) admit `MANAGER` as a row `companyRole` (the user's main
    role, deterministic precedence manager > foreman > worker, or however "main role" is resolved —
    use the highest-ranked role the user holds across projects); (b) order rows by a role-rank
    comparator MANAGER(0) → FOREMAN(1) → WORKER(2), then `(userName, userId)`; (c) carry
    `managerActingAsForeman` onto the synthesized spans so the UI can emphasize them.
  - _Requirements: FOR-07-01 R1; user decision 6d_
  - _Depends on: 1_

- [ ] 4. DTO changes: `MemberRow.companyRole` admits MANAGER; `ProjectedBar.managerActingAsForeman`
  - Extend `CompanyScheduleView`/`MemberRow`/`ProjectedBar` DTOs: `companyRole` may be `MANAGER`;
    add `boolean managerActingAsForeman` to `ProjectedBar`. Update the projector to populate them.
  - _Requirements: FOR-07-01 R1_
  - _Depends on: 1, 3_

- [ ] 5. Author test-cases.md
  - Create/append `test-cases.md` per `.kiro/steering/test-cases.md`. API scenarios (Dockerized app):
    a MANAGER appears as a row with role MANAGER; a project with no foreman promotes its manager to a
    foreman-style span flagged `managerActingAsForeman`; a project WITH a foreman does NOT promote the
    manager; rows ordered managers→foremen→workers; default view includes ACTIVE projects and those
    with `endDate > now()-14d` and excludes older completed ones; a chosen window includes projects
    intersecting it. Repeatability via generated projects/members/schedules + run-id + teardown;
    regression for the existing worker-bar / foreman-span projection + Date_Window intersection.
  - _Requirements: the requirements covered (FOR-07-01 R1/R2/R6/R7, decisions 6b/6c/6d/5b)_
  - _Depends on: 1, 2, 3, 4_

- [ ] V. Validate (API)
  - Rebuild backend; call `GET /api/company-schedule` (default and with a window) and assert the new
    rows/ordering/flags and project inclusion. (Browser validation of the view is in FOR-07-02.)
  - _Requirements: as all tasks_
  - _Depends on: 1, 2, 3, 4_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1 (manager surfacing + synthesis), 2 (project inclusion) — independent
  (different methods of `CrossProjectScheduleService`).
- **Wave 2:** 3 (projector ordering + emphasis) — depends on 1.
- **Wave 3:** 4 (DTO changes) — depends on 1, 3.
- **Wave 4:** 5 — test-cases.md depends on all implementation.
- **Wave 5:** V — API validation depends on all implementation.
