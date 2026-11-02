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
