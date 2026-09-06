# Tasks — FOR-04-11: Work Catalog (WorkItem) entity

- [x] 1. Backend entity, DAO, models
- [x] 1.1 `WorkItemEntity` (@Table("work_items"), @ManyToOne workCategory + unit, nameRU/namePL/active) + `WorkItemDao extends AdminDao`
  - _Requirements: 1.1, 1.2_
- [x] 1.2 Service + controller models (list exposes FK ids + referenced names; extended has FK ids + RU/PL; Create/Update/Response records with flat FK ids)
  - _Requirements: 2.2, 2.5_

- [x] 2. Backend mappers, service, controller (FK resolution)
- [x] 2.1 `WorkItemServiceMapper` — i18n=name; FK resolution flat id → association via entityManager.getReference (create + update); read maps association.id → *Id and referenced localized name → *Name
  - _Requirements: 2.3, 2.4, 2.5_
- [x] 2.2 `WorkItemControllerMapper` (ignore id/create+update, active→true default)
  - _Requirements: 2.2_
- [x] 2.3 `WorkItemService implements AdminService<...>` (NOT ProjectScopedService)
  - _Requirements: 2.1_
- [x] 2.4 `WorkItemController` at `/api/work-items`, `@PermissionResource("WORK_CATALOG")`
  - _Requirements: 2.1, 2.6, 2.7, 3.1_

- [x] 3. Liquibase
- [x] 3.1 `036-create-work-items.xml` (table with FK columns work_category_id/unit_id + named FK constraints; idempotent; register last)
  - _Requirements: 1.2, 1.3, 1.4, 3.5_
- [x] 3.2 `037-seed-work-catalog-resource.xml` (resource WORK_CATALOG + grants ADMIN CRUD / MANAGER CREATE,READ,UPDATE / FOREMAN,WORKER,FINANCIER READ / CLIENT none; NO default work items; idempotent; register last)
  - _Requirements: 3.2, 3.3, 3.4, 3.5, 4.1_

- [x] 4. Checkpoint — backend compiles (MapStruct FK mapping generates cleanly)
  - _Requirements: 2.3_

- [x] 5. Backend tests
- [x] 5.1 `WorkItemControllerIntegrationTest` (CRUD with FK ids, rows expose FK ids + referenced names, i18n, filter by workCategory.id)
  - _Requirements: 5.1, 2.2, 2.4, 2.5, 2.6_
- [x] 5.2 `WorkCatalogResourceSeedIntegrationTest` (resource; grants ADMIN CRUD / MANAGER CRU / FOREMAN,WORKER,FINANCIER READ / CLIENT none; idempotency)
  - _Requirements: 5.2, 3.3_

- [x] 6. Checkpoint — run only the two backend test classes
  - _Requirements: 5.1, 5.2_

- [x] 7. Frontend admin CRUD page
- [x] 7.1 Scaffolding (types with FK ids + referenced names, api via buildFetchQuery, hooks, zod schema)
  - _Requirements: 6.1, 6.5_
- [x] 7.2 List (columns name/workCategory(ref)/unit(ref)/active) + page + form sheet (category/unit selects) + delete dialog
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
- [x] 7.3 Routing + interim guard (`/catalog/works`)
  - _Requirements: 6.1, 6.7_
- [x] 7.4 i18n `workCatalog.*` + `nav.workCatalog` (PL+RU parity)
  - _Requirements: 6.6_
- [x] 7.5 UI component test + i18n parity
  - _Requirements: 7.1, 7.2_

- [x] 8. Checkpoint — frontend tsc + the new UI test file
  - _Requirements: 7.1_

- [x] 9. Author test-cases.md (RU; API + UI; run-id/teardown; regression; report template)
  - _Requirements: 1, 2, 3, 5, 6_

- [x] 10. Final checkpoint — backend two classes + frontend tsc/UI test
  - _Requirements: 5.1, 5.2, 7.1_
