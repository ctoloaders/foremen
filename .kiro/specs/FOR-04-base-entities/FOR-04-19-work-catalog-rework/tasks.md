# Implementation Plan: FOR-04-19 — Work catalog rework

## Overview

Full-stack rework of `/catalog/works`: synthetic consumption-count columns with filters, a full edit
page with an inline consumption table, a chip-based formula field with live validation, and the
retirement of `/catalog/prices` and `/catalog/material-consumption`. Backend work is limited to a
read-model addition (two synthetic counts + two `EXISTS` filters + one index). No new entity,
table or ABAC resource.

**Repositories** (steering `git-repo-structure.md`): frontend code is committed from inside
`foremen-frontend/`; changeset, backend tests and specs from the root repo. Commit only when asked.

**Test-run policy** (steering `test-execution-rules.md`): never run the full Gradle suite. Run only
the new/changed classes with `--tests`, redirect to `/tmp/for-04-19r-test.log`, read the JUnit XML.
Frontend: run only the affected vitest files with `--run`, then `tsc --noEmit`.

**Changeset numbering:** use the next free number at implementation time (last committed is 217;
216 exists untracked in the working tree) and register it last in `changelog.xml`.

## Tasks

- [x] 1. Backend: consumption counts and filters
  - [x] 1.1 Counts on the work-item DTO
    - Add `constructionConsumptionCount` / `finishingConsumptionCount` (`long`, default 0) to
      `WorkItemServiceModel` and `WorkItemDtoModel` (+ mapper). Add the grouped projection query
      `countByWorkItemIdsGroupedByBranch(ids)` to `WorkMaterialConsumptionDao` and
      `attachConsumptionCounts(rows)` to `WorkItemService.find(...)` and the single-item read.
      One statement per page; never join the consumption entity into the list query.
    - _Requirements: 2.4, 7.1, 7.2_
  - [x] 1.2 `EXISTS` filter resolver for the synthetic segments
    - Implement `WorkItemConsumptionCountQueryResolver` (modelled on
      `WorkItemPackagesQueryResolver`) for `constructionConsumptionCount` and
      `finishingConsumptionCount`: `>0`/`>=1`/`!=0` = has, `==0`/`<1`/`<=0` = none, other
      operators/values → standard 400. Verify whether `CustomQueryResolverRegistry` allows one
      component to register two segments; otherwise use a base class + two components. Add the two
      fields to the `/metadata` response as non-sortable synthetic numbers.
    - _Requirements: 2.5, 2.6, 7.3, 7.4_
  - [x] 1.3 Index changeset
    - Add the idempotent changeset creating `ix_wmc_work_item_branch` on
      `work_material_consumptions (work_item_id, branch)` (precondition `not indexExists`,
      `onFail="MARK_RAN"`); register last in `changelog.xml`.
    - _Requirements: 7.5_
  - [x] 1.4 Backend tests
    - Unit test the resolver predicate/operator matrix. Integration test (Testcontainers): counts
      for 0/1/N norms per branch, mixed page, `>0`/`==0` filters, unchanged paging totals, invalid
      operator → 400, and an N+1 guard via Hibernate statistics (statement count constant for page
      sizes 5 and 50). Run only these classes (log to `/tmp`, read the JUnit XML).
    - _Requirements: 10.3_
    - _Depends on: 1.1, 1.2, 1.3_

- [x] 2. Frontend: work list
  - [x] 2.1 Columns and filter
    - Add the two count fields to `WorkItemDto`; add the two columns (render `value ?? 0`, not
      sortable) and the three-state filter (All / Has norms / Has none → `field>0` / `field==0` /
      none). First check the DataTable's existing boolean/enum filter support; extend it minimally
      (`filterOptions`) if needed, keeping the persisted-state format backward compatible.
    - _Requirements: 2.1, 2.2, 2.3, 2.6, 2.8, 1.4_
    - _Depends on: 6.1_
  - [x] 2.2 Navigation to the edit page
    - Create button → `/catalog/works/new`; row click / edit icon → `/catalog/works/:id`;
      construction/finishing cost-range cells → `/catalog/works/:id#consumption`. Remove the sheet
      state and the `WorkMaterialConsumptionDrillIn` usage from `WorkCatalogPage`/`WorkCatalogList`.
    - _Requirements: 1.1, 1.3_
    - _Depends on: 2.1_

- [x] 3. Frontend: edit page and inline consumption table
  - [x] 3.1 Routes, guards and page shell with basic data + labour price
    - Add `/catalog/works/new` (`WORK_CATALOG`/CREATE, literal entry before the template) and
      `/catalog/works/:id` (READ) to `router.tsx` and `route-permissions.ts`. Create
      `pages/WorkItemEditPage.tsx` with header (back, title, active badge, delete), and the Basic
      data + Labour price sections (one Save: work item then `/api/work-prices`; create →
      redirect to `/catalog/works/:id`). Move the work-price API/hooks/schema into NEW files under
      `features/work-catalog/` (`api/work-price-api.ts`, `api/work-price-hooks.ts`,
      `schemas/work-price-schema.ts`) so this task does not touch the same files as 3.3.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.6_
    - _Depends on: 6.1_
  - [x] 3.2 Host the existing editors on the page
    - Render `WorkVolumeFormulaEditor`, `WorkPackageOverridesEditor`,
      `WorkRoomTypeAttachmentEditor` as page sections (edit mode; create-mode hint) and mount the
      consumption section at the bottom with the `#consumption` anchor/scroll. Remove
      `WorkItemFormSheet.tsx`.
    - _Requirements: 3.2, 3.5, 3.4_
    - _Depends on: 3.1, 3.3, 4.3_
  - [x] 3.3 Inline consumption table
    - Create `ConsumptionSection`/`ConsumptionRow` + row-draft hook: branch groups with counts and
      a branch filter, inline-editable cells, `AsyncEntitySelect` dropdowns (construction types /
      finishing types / measurement units), add row with focus, Enter/Esc commit/cancel, inline
      delete confirm, expandable details row, new-row source defaults, price range read-only,
      permission gating, query invalidation of the section and the work list. Move the consumption
      API/hooks/schema into NEW files under `features/work-catalog/`
      (`api/consumption-api.ts`, `api/consumption-hooks.ts`, `schemas/consumption-schema.ts`,
      `types/consumption.ts`) so this task does not touch the same files as 3.1.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11_
    - _Depends on: 6.1_
  - [x] 3.4 Unsaved-changes guard and read-only mode
    - `useBlocker` + `beforeunload` prompt covering the base form, section editors and open
      consumption drafts; read-only rendering for users without UPDATE/CREATE; responsive layout and
      a11y pass (landmarks, labels, focus order).
    - _Requirements: 3.6, 3.7, 3.8_
    - _Depends on: 3.2_

- [x] 4. Frontend: formula field
  - [x] 4.1 Validator
    - Implement `lib/formula-validator.ts` (`validateFormula`) per design §5.3 with offsets and
      problem codes; table-driven tests for every rule and the Req 6.3 examples; fixture parity
      test between `FORMULA_DIMENSIONS` and the backend `ROOM_DIMENSION_VARS`.
    - _Requirements: 6.1, 6.2, 6.3, 6.7_
  - [x] 4.2 Token editor and picker
    - Rework `FormulaInput` into the chip-based `contentEditable` editor: segment model
      (`parseSegments`/`serialize`), chip rendering with localized labels, caret preservation,
      atomic chips, paste handling, picker state machine (open on click/focus/letters; hide on
      digits/operators/parentheses/space; Arrow scroll-into-view; Enter/Tab select; Esc), ARIA
      combobox/listbox wiring, same props contract. Interaction tests for the whole matrix.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_
    - _Depends on: 4.1, 6.1_
  - [x] 4.3 Live validation in the editors
    - Wire the validator into `WorkVolumeFormulaEditor` (status line, Save disabled while invalid,
      server-error mapping kept) and `WorkPackageOverridesEditor` (per-row validation, commit
      blocked while invalid); stored-but-unsupported constructs shown as invalid and left
      untouched.
    - _Requirements: 6.1, 6.4, 6.5, 6.6_
    - _Depends on: 4.2_

- [x] 5. Retire the old screens
  - [x] 5.1 Redirects and removal
    - Replace the `/catalog/prices` and `/catalog/material-consumption` routes with
      `Navigate → /catalog/works`; delete the work-prices and work-material-consumption list
      pages, sheets, dialogs, drill-in and their tests (after grepping for cross-feature imports
      and re-exporting anything still used); drop the two `route-permissions` entries.
    - _Requirements: 1.2, 1.3_
    - _Depends on: 2.2, 3.2_
  - [x] 5.2 Update dependent tests and automation
    - Update `nav-consistency.for0705.test.ts`, `route-permissions*.test.ts`,
      `PermissionGuard.for0504.test.tsx`, assistant `page-context` (+ test) for `/catalog/works/…`
      and the `foremen-qa-auto` artifacts (`menu_grouping.feature`, `DictionarySteps`,
      `WorkPricesPage`). Run the affected vitest files and `tsc --noEmit`.
    - _Requirements: 10.1, 10.2_
    - _Depends on: 5.1_

- [x] 6. i18n and documentation
  - [x] 6.1 Add PL + RU keys
    - Add every key of Req 8.2 to `pl.json` and `ru.json` (identical key sets); extend the
      locale-parity test with the new keys.
    - _Requirements: 8.1, 8.2_
  - [x] 6.2 Remove dead keys
    - Grep and remove locale keys used only by the retired screens (work-prices list,
      consumption list/drill-in/sheet strings that are not reused by the inline table).
    - _Requirements: 8.2_
    - _Depends on: 5.1_
  - [x] 6.3 Update spec cross-references
    - Add the FOR-04 OVERVIEW row for this spec and update the FOR-07-05 / FOR-07 OVERVIEW notes
      that mention the pending rework (`/catalog/prices` and `/catalog/material-consumption` now
      redirect).
    - _Requirements: 1.2_

- [x] 7. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression
    group, MD report template). Browser-engine scenarios for the list columns/filter, the edit
    page, the inline consumption table and the formula field, plus API checks for the counts and
    filter. Result artifacts are MD reports with tables.
  - _Requirements: 2.1, 2.3, 3.1, 4.1, 5.3, 6.1, 10.5_
  - _Depends on: 1.4, 5.2, 6.2_

- [x] 8. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava` → `/tmp`), run the affected backend classes and the
    affected vitest files, `tsc --noEmit`, eslint on the touched frontend dirs; bring up the Docker
    stack (run Liquibase), walk the main flows per role on desktop and a narrow viewport, and write
    the MD report. Commit only if the user asks.
  - _Requirements: all_
  - _Depends on: 7_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "4.1", "6.1", "6.3"] },
    { "id": 1, "tasks": ["1.4", "2.1", "3.1", "3.3", "4.2"] },
    { "id": 2, "tasks": ["2.2", "4.3"] },
    { "id": 3, "tasks": ["3.2"] },
    { "id": 4, "tasks": ["3.4", "5.1"] },
    { "id": 5, "tasks": ["5.2", "6.2"] },
    { "id": 6, "tasks": ["7"] },
    { "id": 7, "tasks": ["8"] }
  ]
}
```
