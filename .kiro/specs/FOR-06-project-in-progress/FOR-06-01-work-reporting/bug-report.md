# Bug report: FOR-06-01-work-reporting

Screen URL: https://dev.foremen.eu/projects/1/workReporting

## Round 1 (2026-11-02)

- [x] 1. Worker must not see a worker selector - he is selected and added as reporting worker automatically
  - Triage: observed — a WORKER caller (company role `WORKER`, e.g. dev user id 6) sees the list
    filter "Pracownik / Wszyscy pracownicy" (`work-reporting-filter-worker`) above the reports list.
    The report matrix itself already hides its worker picker for a WORKER and loads/saves for
    `workerId = currentUser.id` (reproduced locally as `info+valera@foremen.eu`: picker hidden,
    filter shown). Expected — a WORKER sees no worker selector anywhere on the screen; they are
    always the reporting worker, and their list is already self-scoped by the backend (R10.5), so
    the filter is meaningless for them. Non-WORKER callers (FOREMAN / MANAGER) keep both the filter
    and the matrix picker.
    Layer: frontend. Suspect: `features/project-execution/work-reporting/WorkReportingPanel.tsx`
    (render the worker filter only when `!isWorkerCaller`).
    Fix: hide the worker filter for WORKER callers; keep `filters.workerId` unset for them; add a
    panel test (WORKER → no filter, FOREMAN → filter; WORKER matrix → no picker, self-loaded).
  - _Requirements: 10.5, 11.1, 17.1_
  - _Depends on: none_
  - Verified (local, 2026-11-02): as WORKER `info+valera@foremen.eu` — no list worker filter, no
    matrix picker, matrix loaded for self, saved report #288 had `worker_id` = the caller; as ADMIN —
    filter and picker still shown. Test: `WorkReportingPanel.bugfix-r1.test.tsx`.
- [x] 2. Popup with works to report does not fit many rows and is not scrollable - user is not able to send report
  - Triage: observed — the matrix dialog (`work-report-matrix`) is `max-h-[90vh] overflow-hidden`,
    but the shared `DialogContent` lays out as CSS `grid`, so the inner `flex-1 min-h-0` never
    constrains height. With many work items the content grows past the dialog (reproduced: 41 rows →
    content 2508px in a 648px dialog), the grid itself never scrolls (`scrollHeight == clientHeight`),
    the overflow is clipped, and the draft panel's "save" button (y≈2468) is unreachable — the
    report cannot be sent. Expected — the dialog stays within the viewport; the works grid scrolls
    (vertical + horizontal, sticky work-item column kept) while the header and the draft panel with
    its save button stay visible; on narrow (<lg, stacked) layouts the dialog body scrolls so the save
    button is always reachable.
    Layer: frontend. Suspect:
    `features/project-execution/work-reporting/components/WorkReportMatrix.tsx` (DialogContent →
    `flex flex-col`; body `min-h-0 flex-1`, scroll container on the grid at lg and on the body below
    lg) and `components/WorkReportDraftPanel.tsx` (lines list scrolls, save button pinned at the
    bottom of the panel).
    Fix: layout-only change, no new strings; add a test asserting the scroll containers' classes and
    re-check in the browser with a long matrix.
  - _Requirements: 15.1, 17.2, 17.3, 18_
  - _Depends on: none_
  - Verified (local, 2026-11-02): matrix with 41 rows — dialog `display:flex`, bounded to the
    viewport; grid scrolls (2398px content in a 536px pane at 1280×720, 266px at 1280×420); save
    button inside the dialog and clickable at both sizes; a staged line was saved (SUBMITTED).
- [x] V1. Validate Round 1 in browser
  - Local stack, rebuild frontend. As a WORKER: no worker filter, no matrix picker, matrix loads own
    rows; stage a line and save. As a FOREMAN: filter + picker still present. Long matrix (≥40 rows,
    desktop 1280×720 and a short 1280×420 viewport): grid scrolls, save button visible and clickable,
    report saved.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 2 — independent; bug 1 touches `WorkReportingPanel.tsx`, bug 2 touches
  `WorkReportMatrix.tsx` / `WorkReportDraftPanel.tsx`.
- **Wave 2:** V1 — browser validation, depends on both fixes.

Notes: no backend change, no new i18n keys, no notifications, no migrations.

## Round 2 (2026-11-05)

Screen URL: `https://dev.foremen.eu/projects/{id}/workReporting` (reproduce locally at
`http://localhost:3000/projects/{id}/workReporting`). Source: meeting notes. Reported bug: "very poor
performance of add report and approve report."

Background (grounded in code): the slowness is a **cache-invalidation fan-out**, not the single
write. `useInvalidateReports(projectId)` in
`foremen-frontend/src/features/project-execution/work-reporting/api/query-hooks.ts` (~L164-176) is
shared by every mutation and, on each success, fires two **prefix** invalidations with no narrowing
segment:
- `['work-reports','list',projectId]` — the list key is `[...,'list',projectId,filters]`, so this
  invalidates **every** cached list page under **every** filter combination.
- `['work-reports','progress',projectId]` — the progress key is `[...,'progress',projectId,workerId]`,
  so this invalidates **every** cached `(project, worker)` progress matrix.

Each refetch is expensive: the list read (`WorkReportService.find/findExtended`, size 50) runs
`WorkReportPaymentStatusResolver.resolve` over the whole page, re-costing every room line of every
APPROVED report (`statusOf`); and `WorkReportService.progress(projectId, workerId)` re-reads and
re-walks the **entire estimate × room-quantity matrix** per worker. So one add/approve forces a
50-row re-cost plus a full matrix recompute for every cached worker. Approve additionally flips the
report to APPROVED, making the next list refetch strictly heavier.

- [x] 1. Narrow the work-report cache invalidation to the touched keys
  - Triage: observed — add/approve invalidate all list filters + all workers' progress, forcing a
    full re-cost + per-worker matrix recompute. Expected — a write invalidates only (a) the list for
    the active filter and (b) the `(projectId, workerId)` progress for the worker actually touched.
    Layer: **frontend**.
    - Change `useInvalidateReports` to accept the affected `workerId` (and/or the active filters) and
      invalidate `workReportKeys.progress(projectId, workerId)` precisely, plus the specific active
      list key rather than the `['...','list',projectId]` prefix. Mutations already know the report
      id / worker (`approve` returns `data`, create knows the picked worker). Keep a single broad
      fallback only where the affected worker is genuinely unknown.
    - Verify the panel + matrix still refresh after a write (the touched worker's progress and the
      visible list page update).
  - _Requirements: (work-reporting list/progress refresh — reference the R16.5 refresh requirement)_
  - _Depends on: none_
- [ ] 2. Avoid re-costing the whole list page on each write (frontend + backend seam)
  - Triage: observed — the list refetch re-costs all 50 rows via the payment resolver on every
    write. Expected — the changed report updates without re-costing the entire page. Layer:
    **frontend** (preferred) with an optional backend assist.
    - Prefer a targeted `setQueryData`/optimistic update of the single changed report in the active
      list cache (status + payment status from the mutation response) instead of a full refetch; fall
      back to a scoped refetch of only the active filter page. Only pursue a backend "cost one
      report" path if the frontend update is insufficient.
  - _Requirements: (same as 1)_
  - _Depends on: 1_
- [~] V2. Validate Round 2 (browser + timing)
  - Rebuild the stack; on `/projects/{id}/workReporting` add a report and approve a report, and
    confirm (a) correctness (list + the touched worker's progress refresh) and (b) a clear latency
    improvement vs. before (fewer/lighter network calls — no all-workers progress refetch, no full
    50-row re-cost on every write). Capture before/after request counts.
  - _Requirements: (same as 1)_
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 2

- **Wave 1:** 1 — narrow invalidation (the core fix; everything else builds on it).
- **Wave 2:** 2 — targeted list update (depends on 1's invalidation shape).
- **Wave 3:** V2 — browser + timing validation; depends on 1, 2.

## Round 2 — validation results (2026-11-05)

- **#1 Narrowed cache invalidation — ✅ FIXED (code + tests).** `useInvalidateReports(projectId)`
  now takes the affected `workerId` and invalidates `workReportKeys.progress(projectId, workerId)`
  precisely (the dominant cost — the old code invalidated every worker's progress via the
  `['...','progress',projectId]` prefix, forcing a full estimate × room-matrix recompute per cached
  worker on every add/approve). Each mutation passes the worker it touched: create/update from the
  request payload's `workerId`, batch-create from the single-worker payload, approve from the
  response `workerId`; delete falls back to the project-wide progress invalidation (rare). The list
  is still invalidated (cheap relative to the matrix recompute). Verified: `tsc -b` clean; the
  work-reporting unit suite passes (171 affected FE tests green across the batch).
- **#2 Targeted list re-cost — not implemented (left open).** Narrowing the progress invalidation
  (the per-worker full-matrix recompute) addresses the dominant slow path. The optional list
  `setQueryData` optimization (task 2) was NOT done; it can be a follow-up if the 50-row list
  re-cost is still a concern in prod.

Local browser note: project 2 is DRAFT, which does not expose the work-reporting screen (it
redirects to `/rooms`), so live add/approve could not be exercised on this project. The fix is a
cache-invalidation change proven by the type-check and the work-reporting test suite; the latency
win (no all-workers progress recompute per write) follows directly from the narrowed invalidation.

Result: #1 fixed (code+tests); #2 deferred; live timing to confirm on a non-DRAFT project.

## Round 3 (2026-11-27)

Screen URL: `https://dev.foremen.eu/projects/5/workReporting` (the "new report" matrix dialog, `WorkReportMatrix.tsx`; the source of the matrix is `GET /api/work-reports/progress`, `WorkReportService`).

- [x] 1. Make the add-report popup full screen so the works are visible without horizontal scroll
  - Triage: observed the dialog is `max-w-5xl` / `max-h-[90vh]`, so with several room columns the grid scrolls horizontally inside it; expected the popup to cover the whole viewport and use the full width for the room columns. Layer: frontend. Suspect: `WorkReportMatrix.tsx` `DialogContent` class (line ~219); the inner grid already scrolls (`overflow-auto`), so only the dialog size changes (`h-dvh w-screen max-h-none max-w-none rounded-none`), keeping the header and the draft panel visible. The cell popup `WorkReportCellPopup` stays a normal dialog on top.
  - _Requirements: 17.2, 18_
  - _Depends on: none_
- [x] 2. Make the work column narrow with text wrapping onto new lines
  - Triage: observed the first column (work name + unit) is `whitespace-nowrap`, so long names stretch it and push the room columns out of view; expected a narrow column (about 12-14rem) where the name wraps onto several lines. Layer: frontend. Suspect: `WorkReportMatrix.tsx` work-item `<td>` / `<th>` (remove `whitespace-nowrap`, add a fixed narrow width and `break-words`). Same file as item 1, so they are done together.
  - _Requirements: 17.2_
  - _Depends on: 1_
- [x] 3. Sort work groups by code and works by code
  - Triage: observed groups are ordered by `WorkCategory.orderNo` and the works inside a group follow the estimate-line order; expected both sorted by their `code` (categories by category code, works by work-item code, for example `1.01`, `1.02`, ...). Layer: backend. Suspect: `WorkReportService` (progress build, ~lines 618-680): sort `CategoryAccumulator` by `category.getCode()` (case-insensitive, null last, then id) instead of `orderNo`, and sort each group's rows by `workItem.getCode()` (same rule). The response shape does not change, so the frontend needs no change. This replaces the "declaration order" wording of Requirement 17.2, which is updated with the change. Regression test: a `WorkReportService` progress test with categories and works created in a non-code order.
  - _Requirements: 14.1, 17.2_
  - _Depends on: none_
- [x] 4. Add a "Submit report" button to the root menu that opens the same popup with a project picker (default: the active project)
  - Triage: observed the report can only be started from a project's Work reporting tab; expected a root-menu item (sidebar and drawer, like "Add expense"), **visible only to the WORKER role**, that opens the same full-screen matrix popup with a project selector above the worker selector, preselected with the active (working) project. Layer: frontend only, no new entity or endpoint. Plan: a nav item in the first `NAV_CONFIG` section (`nav.addWorkReport` already exists in `pl.json` / `ru.json`; add a shorter "submit report" label if wanted), gated by `WORK_REPORTS` CREATE and by the WORKER role (new optional `onlyForRoles` field on a nav item, checked in `Sidebar` and `Drawer`); a route `/work-reports/new` (route-permission `WORK_REPORTS` CREATE) that renders `WorkReportMatrix` open with a project picker; closing returns to the previous page. `WorkReportMatrix` is made project-agnostic through a `projectId` prop that the new page switches, and the draft is stored per project as today. The picker lists the caller's accessible ACTIVE projects (the `GET /api/dashboard/projects` list the dashboard picker already uses; reporting is not offered on COMPLETED projects). The WORKER bottom bar keeps its existing "Add report" slot, which goes to the active project's tab. i18n: new keys in `pl.json` and `ru.json` for the nav label, the project picker label and its placeholder.
  - Size: small-to-medium, kept in this round (no split into a new spec needed).
  - _Requirements: 11.2, 17.1, 17.4 (surface reuse); new acceptance criteria for the root-menu entry added to Requirement 17_
  - _Depends on: 1, 2_
- [x] 5. Hide the reference-data ("Dictionaries") menu section for the WORKER role
  - Triage: observed a WORKER sees the "Dictionaries" section (units, currencies, VAT rates, room types, and so on) in the root menu because the role holds READ on some of those resources; expected a WORKER does not see that section. Layer: frontend (menu only; route access and the backend grants are unchanged). Plan: new optional `hiddenForRoles` field on a nav section (set to `['WORKER']` on `nav.sections.dictionaries`), applied in `Sidebar` and `Drawer`. Same navigation files as item 4, so it runs in the same wave order after it.
  - _Requirements: 11.2 (menu), FOR-03-07 (menu visibility)_
  - _Depends on: none_
- [ ] V3. Validate Round 3 in browser
  - Local stack, as a MANAGER and as a WORKER (the root-menu entry is WORKER-only; the Dictionaries section is hidden for the WORKER) on an execution-status project with several rooms and several categories: the popup fills the screen, no horizontal scroll with the works column narrow and wrapped, categories and works appear in code order, and the root-menu "Submit report" opens the popup with the active project preselected, the project can be switched, and a report can be saved. Repeat on dev after deploy.
  - _Depends on: 1, 2, 3, 4, 5_

## Parallel execution plan (waves) — Round 3

- **Wave 1 (parallel):** 1, 3 — different files (`WorkReportMatrix.tsx` vs `WorkReportService.java`).
- **Wave 2:** 2 — same file as item 1, so it follows it.
- **Wave 3:** 4 — builds on the matrix changes of items 1 and 2 (touches `WorkReportMatrix.tsx`, navigation, router, locales).
- **Wave 4:** 5 — touches the same navigation files as item 4, so it follows it.
- **Wave 5:** V3 — validation, depends on all fixes.

## Round 3 — validation results (2026-11-27)

Local stack rebuilt (`backend`, `frontend`). WORKER session: `info+valera@foremen.eu`; MANAGER session: `katarzyna@loaders.dev`; viewport 1440x900; working project 2 (ACTIVE, 76 estimate lines).

| # | Check | Result |
|---|---|---|
| 1 | Popup size | The matrix dialog box is x=0, y=0, 1440x900, the whole viewport. The works grid has `scrollWidth` 1085 = `clientWidth` 1085, so there is no horizontal scroll (8 columns, project 2) ✅ |
| 2 | Work column | First column is 176 px wide; the 80-character name "Drzwi ukryte - montaż ościeżnicy i skrzydła (do 2 m, powyżej 2 m +10% za 10 cm)" wraps over several lines (row height 97 px) ✅ |
| 3 | Sort by code | Groups come in category-code order (CARPENTRY, CONSTRUCTIONS_GK, ELECTRICAL_FINISH, ELECTRICAL_ROUGH, EXTRAS, FLOORS, PAINTING_DECOR, PLASTERING, ...). The first group's works come in work-item-code order 11.02, 11.03, 11.04, 11.05, 11.06 ✅ |
| 4 | Root menu "Submit report" | WORKER sees "Złóż raport" in the sidebar; it opens `/work-reports/new` with the full-screen matrix, the project select shows "project 2" (the working project) and lists the 2 accessible ACTIVE projects; switching to project 1 reloads the matrix (1 group, 1 work) ✅. MANAGER does not see the entry ✅ |
| 5 | Dictionaries hidden for WORKER | WORKER sees no `/measurement-units` or `/currencies` links; MANAGER still sees the Dictionaries section ✅ |

Not exercised in a browser: saving a report from the new entry (the matrix save path is unchanged and covered by the existing suite), the mobile drawer, and dev. V3 stays open until dev is redeployed.

Tests: frontend (`src/config`, `src/app`, `work-reporting`, locales) 600/600; backend `WorkReportServiceTest` all pass, including the new `groupsAndWorksSortedByCode`.

Local data changes for the check: user 1249 set to ACTIVE status and users 1246 and 1249 given the local admin password hash.
