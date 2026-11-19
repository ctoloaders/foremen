# Bug report: FOR-05-04-UI-estimate-packages-changes

Screen URL: https://dev.foremen.eu/projects/5/estimate (reproduce locally at `http://localhost:3000/projects/{id}/estimate`).

## Round 1 (2026-11-19)

- [~] 1. In the estimate: add "remove package"; the undo list shows every action as its own line; applying a package is one indivisible operation
  - Triage: a package can be applied but not removed; the staged-change list does not show individual actions. Expected: (a) "Remove package" reverts the cells that still equal the package result and keeps manually changed cells (approved); (b) every action is a separate line; (c) applying a package is one undo/redo step.
  - Layer: both. Suspect: `features/estimate/state/*`, `EstimateTab.tsx`, `EstimateAssignmentService`.
  - _Requirements: FOR-05-04-UI package apply/undo (to be amended)_
  - _Depends on: none_
- [ ] V1. Validate Round 1 in browser
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1.
- **Wave 2:** V1.

## Round 1 — validation results (2026-11-19)

Implemented: edit kind `REMOVE_PACKAGE` (preview and Save), `STAGE_BATCH` one-step undo/redo, "Zmiany (N)" popover with a line per action, "Usuń pakiet" button (seen on the estimate page of project 2). Tests pass (vitest estimate 368; `EstimateAssignmentServiceIntegrationTest` 35/35). Behavior of remove-package was not exercised in the browser, so the item stays `[~]`. Known limit: a cell assigned by hand before the package that equals the package result is also removed (no "created by package" marker).
