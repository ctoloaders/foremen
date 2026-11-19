# Additional tasks: FOR-04-11-work-catalog (internal price, related works, standard work set)

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

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "3", "6"] },
    { "id": 1, "tasks": ["2", "4", "5", "7"] },
    { "id": 2, "tasks": ["8"] },
    { "id": 3, "tasks": ["9"] },
    { "id": 4, "tasks": ["10"] },
    { "id": 5, "tasks": ["11"] }
  ]
}
```
