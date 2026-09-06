# Tasks — FOR-04-06: Work Categories dictionary

- [x] 1. Backend entity, DAO, models
- [x] 1.1 `WorkCategoryEntity` (@Table("work_categories"), code/orderNo/nameRU/namePL/active) + `WorkCategoryDao extends AdminDao`
  - _Requirements: 1.1, 1.2_
- [x] 1.2 Service + controller models (with orderNo; Create/Update(no code)/Response)
  - _Requirements: 2.4, 2.5_

- [x] 2. Backend mappers, service, controller
- [x] 2.1 Mappers (`WorkCategoryServiceMapper` i18n=name, ignore id+code; `WorkCategoryControllerMapper` ignore id/create, id+code/update, active→true)
  - _Requirements: 2.2, 2.3, 2.4_
- [x] 2.2 `WorkCategoryService implements AdminService<...>`
  - _Requirements: 2.1_
- [x] 2.3 `WorkCategoryController` at `/api/work-categories`, `@PermissionResource("WORK_CATEGORIES")`
  - _Requirements: 2.1, 3.1_

- [x] 3. Liquibase
- [x] 3.1 `026-create-work-categories.xml` (table with order_no INT NOT NULL, idempotent; register last)
  - _Requirements: 1.2, 1.3, 3.5_
- [x] 3.2 `027-seed-work-categories-resource.xml` (resource + grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none + 13 defaults orderNo 1..13; idempotent; register last)
  - _Requirements: 3.2, 3.3, 3.4, 3.5, 4.1, 4.2_

- [x] 4. Checkpoint — backend compiles
  - _Requirements: 3.1_

- [x] 5. Backend tests
- [x] 5.1 `WorkCategoryControllerIntegrationTest` (CRUD, i18n, code immutable, orderNo update)
  - _Requirements: 5.1, 2.2, 2.4_
- [x] 5.2 `WorkCategoriesResourceSeedIntegrationTest` (resource, grants, 13 defaults + orderNo 1..13, idempotency)
  - _Requirements: 5.2, 3.3, 4.1_

- [x] 6. Checkpoint — run only the two backend test classes
  - _Requirements: 5.1, 5.2_

- [x] 7. Frontend admin CRUD page
- [x] 7.1 Scaffolding (types, api via buildFetchQuery, hooks, zod schema)
  - _Requirements: 6.1, 6.5_
- [x] 7.2 List + page + form sheet + delete dialog (columns orderNo/code/name/active; default sort orderNo asc; orderNo numeric input)
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
- [x] 7.3 Routing + interim guard
  - _Requirements: 6.1, 6.7_
- [x] 7.4 i18n `workCategories.*` + `nav.workCategories` (PL+RU parity)
  - _Requirements: 6.6_
- [x] 7.5 UI component test + i18n parity
  - _Requirements: 7.1, 7.2_

- [x] 8. Checkpoint — frontend tsc + the new UI test file
  - _Requirements: 7.1_

- [x] 9. Author test-cases.md (RU; API + UI; run-id/teardown; regression; report template)
  - _Requirements: 1, 2, 3, 4, 6_

- [x] 10. Final checkpoint — backend two classes + frontend tsc/UI test
  - _Requirements: 5.1, 5.2, 7.1_
