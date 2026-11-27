# Additional tasks: FOR-04-11-work-catalog (internal price, related works, standard work set, matrix)

Source: bug-report Round 2, items 2, 3 and 4 (2026-11-19). English per the spec-doc standard; `test-cases.md` in Russian.
Screen URL: `http://localhost:3000/catalog/works/{id}`.

## Scope and decisions
- **Internal price:** optional `internalPrice` on the work. Payroll base = `internalPrice` if set, otherwise the base price. Payroll (FOR-06-10, FOR-06-11) reads the effective base.
- **Related works:** one-way link parent -> related, set explicitly on the parent. Selecting the parent in the estimate also adds its related works. One level only, no transitive expansion, cycles ignored (a work never adds itself; links of a related work are not followed). Added in the same undo step as the parent.
- **Standard work set:** configurable set of works by room type (separate menu). When a room is added to a project, the matching standard works are applied right away. Assumption to confirm: also for AI-imported rooms; added as staged estimate edits until Save.
- Entities: related-work links and standard-set rows are managed entities, so `.kiro/steering/entity-creation-rules.md` applies (resource seed and ADMIN grant, `@PermissionResource`, dashboard dataset check; reference data, so likely the `DatasetCoverage` exclusion list with a justification). Standard-set rows are company-wide, not project-scoped.
- i18n: no new translated entity fields. Frontend keys (PL/RU): menu "Standard work sets" / "Стандартные наборы работ", table columns, add and remove, empty state; work page: internal price label and hint, related works label and picker.
- Notifications: none.
- The work-page save logic (bug-report Round 2 item 1) is done; tasks 1 and 5 edit the same page.

## Tasks
- [x] 1. Internal price: Liquibase column, entity/DTO/mapper, validation (>= 0), field on the edit page.
  - DONE: changeset 262 (`work_items.internal_price`, CHECK >= 0), entity/DTOs/mappers, `@PositiveOrZero`, field + validation on the edit page, part of the draft and aggregate save.
  - _Depends on: none_
- [x] 2. Payroll: effective base (`internalPrice` else base price) in worker pay; tests in affected payroll classes.
  - DONE: `work_reports.payroll_base_price` frozen at valuation (262); `WorkReportEntity.payrollBase()` = internal price else client unit price; the 8 payroll / worker-cost / margin read sites use it. Existing reports keep NULL (unchanged pay).
  - _Depends on: 1_
- [x] 3. Related works: link table and changeset, resource seed and grants, entity, service, mapper, controller annotations; validation (no self link, no duplicates).
  - DONE: changeset 263 (`work_related_works`, unique pair, no self-link CHECK, cascade), resource `WORK_RELATED_WORKS` + grants (ADMIN and every role that has `WORK_CATALOG`), `WorkRelatedWorkEntity/Dao/Service`, `GET/PUT /api/work-items/{id}/related-works` with `@RequiresPermission`, aggregate `relatedWorkIds`, `DatasetCoverage` exclusion.
  - _Depends on: none_
- [x] 4. Related works: expansion in estimate assignment (one level, explicit links, cycle-safe, same undo step); preview includes them.
  - DONE: staged `ASSIGN` replay adds the explicit related works of the work to the same room, one level, skipping self, already assigned cells and rooms excluded by the related work attachment; calculate previews (apply-package / apply-work) do not expand, so the client diff cannot expand twice. The staged preview and Save both expand, one undo step.
  - _Depends on: 3_
- [x] 5. Related works: picker on the work edit page, staged in the page draft (PL/RU keys).
  - DONE: `WorkRelatedWorksEditor` (chips + picker) staged in the page draft (`relatedWorkIds` fragment), was/now row, PL/RU keys.
  - _Depends on: 3_
- [x] 6. Standard work set: table (work, room type), changeset, resource seed and grants, CRUD service and controller, dataset exclusion entry.
  - DONE: changeset 264 (`standard_work_set_items`, unique `(room_type, work)`), resource `STANDARD_WORK_SETS` + grants, CRUD service/controller/mappers (`/api/standard-work-sets`, 409 on duplicate), `DatasetCoverage` exclusion.
  - _Depends on: none_
- [x] 7. Standard work set: menu item, route, route permission and configuration screen (PL/RU keys).
  - DONE: menu item in Resources, route `/catalog/standard-work-sets`, route permission, `StandardWorkSetsPage` (grouped by room type, add/remove, permission-aware), PL/RU keys.
  - _Depends on: 6_
- [x] 8. Standard work set: apply on room creation, integrated with estimate staging.
  - DONE: `RoomsSaveService` applies the standard set of the room type to every created room after the sibling copy (also for AI-imported rooms, same save path), creates the estimate when missing, goes through the estimate-change approval dialog (409 without `force`).
  - _Depends on: 4, 6_
- [x] 9. Tests for 1 to 8 (affected classes and suites only).
  - DONE: `EstimateWorkSetsIntegrationTest` (10), `WorkItemAggregateSaveControllerIntegrationTest` (+4), `PayoutSuggestionServiceTest` (+1), `WorkReportPayrollBaseTest` (3), vitest `WorkItemInternalPrice`, `WorkRelatedWorksEditor`, `StandardWorkSetsPage` + nav/route tests; `DatasetCoverageTest`, `SensitiveFieldGuardTest`, `PermissionAnnotationValidatorIntegrationTest` green.
  - _Depends on: 2, 4, 5, 7, 8_
- [x] 10. Author test-cases.md
  - DONE: Part C of `test-cases.md` (Russian): API + UI scenarios, generator + teardown, regression, report rows.
  - Per `.kiro/steering/test-cases.md`; repeatability via generated names and teardown.
  - _Depends on: 9_
- [ ] 11. Checkpoint: browser validation.
  - _Depends on: 10_

## Round 2: standard work set as an estimate-style matrix (2026-11-19)

Source: user request 2026-11-19 ("должно выглядеть 1-в-1 как смета, только вместо конкретных помещений —
только типы помещений; работа считается в стандартной смете, если обозначена в ячейке работа/тип помещения").
Screen URL: `http://localhost:3000/catalog/standard-work-sets`.

### Scope and decisions
- **Layout:** a matrix with the look of the estimate `WorksRoomsMatrix`: rows = all works of the work
  catalog, grouped by work category in collapsible group sections (same header, sticky work column, cell
  grid); columns = all room types instead of rooms. The work row shows name, unit and catalog price.
- **Membership:** a work is in the standard set of a room type exactly when the cell (work, room type) is
  marked. Data model unchanged: `standard_work_set_items (room_type, work)`; no new table.
- **Editing:** like the estimate. A cell click stages a toggle; the toolbar shows "Changes (N)", undo,
  redo, Discard and Save; Save sends all staged changes in one atomic request. Leaving with unsaved
  changes asks for confirmation. Without write permission on `STANDARD_WORK_SETS` the matrix is read-only.
- **Not carried over:** summary cards, packages, materials, money totals, fill-state legend, labour price
  adjustment, offer actions. A group header shows the number of marked cells instead.
- **Backend:** new `PUT /api/standard-work-sets/matrix` with `{add: [{roomTypeId, workItemId}], remove: [...]}`.
  Atomic and idempotent (adding an existing pair or removing a missing one is a no-op), 400 on unknown ids,
  guarded by `STANDARD_WORK_SETS` / `UPDATE`; a seed changeset adds `UPDATE` where a role holds the
  resource without it. CRUD endpoints and apply-on-room-creation (task 8) stay unchanged.
- i18n: PL/RU keys for the toolbar, empty state and cell labels; unused add-form keys removed.

### Tasks
- [x] 12. Backend: `PUT /api/standard-work-sets/matrix` (atomic add/remove, idempotent, 400 on unknown ids),
  - DONE: `PUT /api/standard-work-sets/matrix` (removals before additions, idempotent, 400 `error.standard.work.set.matrix.unknown`, 200 `{added, removed, pairs}`), changeset 265 (UPDATE grant), `StandardWorkSetMatrixIntegrationTest` (4).
  UPDATE grant seed changeset if missing; integration test for the endpoint only.
  - _Depends on: none_
- [x] 13. Frontend: matrix data hook (works by category, room types, current pairs) and staged-change state
  - DONE: `useStandardWorkSetMatrix` and `state/matrixStagedState.ts` (toggle, undo, redo, discard, net diff), `groupMatrixWorks`; a refetch resyncs only when nothing is staged.
  with undo / redo / discard and the save mutation.
  - _Depends on: 12_
- [x] 14. Frontend: `StandardWorkSetMatrix` on the estimate matrix layout (group sections, sticky work
  - DONE: `components/StandardWorkSetMatrix.tsx` on the estimate layout (work column capped at 20rem so the room-type columns fit), toolbar, `useUnsavedGuard`, read-only mode, PL/RU keys; group counter "W zestawie: N" / "В наборе: N".
  column, room-type columns, toggle cells with accessible labels), toolbar, unsaved-changes guard,
  read-only mode; replaces the form and grouped list in `StandardWorkSetsPage`; PL/RU keys.
  - _Depends on: 13_
- [x] 15. Frontend tests: vitest for toggle, undo/redo, discard, save payload, read-only; update
  - DONE: `StandardWorkSetsPage.test.tsx` (15 tests) plus hook and state tests; standard-work-sets vitest 25/25, locale parity green.
  `StandardWorkSetsPage.test.tsx`.
  - _Depends on: 14_
- [x] 16. Update `test-cases.md` Part C for the matrix screen (Russian).
  - DONE: Part C: `TC-SWM-01..06` (API), `TC-UI-SWM-01..09` (UI), `TC-REG-C7/C8`, report rows.
  - _Depends on: 14_
- [x] 17. e2e `foremen-qa-auto/e2e/tests/for04/11-standard-work-sets.e2e.ts` for the Part C UI cases.
  - DONE: `foremen-qa-auto/e2e/tests/for04/11-standard-work-sets.e2e.ts`, 11 tests pass; README coverage row.
  - _Depends on: 15, 16_
- [x] 18. Checkpoint: rebuild the stack, validate in the browser side by side with the estimate.
  - _Depends on: 17_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "3", "6"] },
    { "id": 1, "tasks": ["2", "4", "5", "7"] },
    { "id": 2, "tasks": ["8"] },
    { "id": 3, "tasks": ["9"] },
    { "id": 4, "tasks": ["10"] },
    { "id": 5, "tasks": ["11", "12"] },
    { "id": 6, "tasks": ["13"] },
    { "id": 7, "tasks": ["14"] },
    { "id": 8, "tasks": ["15", "16"] },
    { "id": 9, "tasks": ["17"] },
    { "id": 10, "tasks": ["18"] }
  ]
}
```
