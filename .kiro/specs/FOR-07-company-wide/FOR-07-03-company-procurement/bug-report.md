# Bug report: FOR-07-03-company-procurement

## Round 1 (2026-11-27)

Screen: mobile, "Dodaj wydatek" (`/expenses/new`, `ExpenseUploadPage`, R8.4).

- [x] 1. Adding a new expense must preselect the active project
  - Triage: observed the project select of the expense form starts empty ("no project") on `/expenses/new`;
    expected it preselected with the working (active) project when that project is in the form's project list.
    Layer: frontend. Cause: `ExpenseUploadForm` already takes `defaultProjectId`, but `ExpenseUploadPage` renders
    it without one. The project list arrives asynchronously (`fetchExpenseOptions`), so the default must be applied
    when the options load, and only if the working project is in `options.projects` (otherwise stay on "no
    project"). Files: `ExpenseUploadPage.tsx` (pass `getWorkingProjectId()`), `ExpenseUploadForm.tsx` (apply the
    default once the options are loaded and the user has not picked a value yet). No backend change, no i18n.
  - _Requirements: 8.4_
  - _Depends on: none_
- [ ] V1. Validate Round 1 on a mobile viewport
  - With a working project set, open "Dodaj wydatek" from the drawer and from the bottom bar: the project select
    shows the working project; with no working project (or one not in the list) it shows "no project".
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1.
- **Wave 2:** V1.

## Round 1 — validation results (2026-11-28)

Local stack (rebuilt backend and frontend), WORKER `info+valera@foremen.eu`, MANAGER `katarzyna@loaders.dev`, SALESMAN `probe.salesman@example.com`.

- Mobile, working project 2, drawer → "Dodaj wydatek": the project select shows project 2. ✅
- Unit tests: preselected when selectable; "no project" when the working project is not in the list or not set.
  `expense-upload` tests pass. V1 stays open until dev is redeployed.
