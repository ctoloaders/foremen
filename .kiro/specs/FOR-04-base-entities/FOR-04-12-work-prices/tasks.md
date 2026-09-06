# Tasks — FOR-04-12: Work Prices (WorkPrice) entity + Excel price-list seed

- [x] 1. Backend entity, DAO, models
- [x] 1.1 `WorkPriceEntity` (@Table("work_prices"), @ManyToOne workItem + currency, netPrice, validFrom, validTo) + `WorkPriceDao extends AdminDao`
  - _Requirements: 1.1, 1.2_
- [x] 1.2 Service + controller models (list exposes FK ids + referenced values + current; extended has FK ids + price + dates; Create/Update/Response records)
  - _Requirements: 2.2, 2.4_

- [x] 2. Backend mappers, service, controller (FK resolution + current)
- [x] 2.1 `WorkPriceServiceMapper` — abstract class; FK resolution via entityManager.getReference; read maps association.id → *Id, currency.code → currencyCode, workItem localized name → workItemName, current = validTo==null
  - _Requirements: 2.3, 2.4_
- [x] 2.2 `WorkPriceControllerMapper` (ignore id/create+update)
  - _Requirements: 2.2_
- [x] 2.3 `WorkPriceService implements AdminService<...>` (NOT ProjectScopedService)
  - _Requirements: 2.1_
- [x] 2.4 `WorkPriceController` at `/api/work-prices`, `@PermissionResource("WORK_PRICES")`
  - _Requirements: 2.1, 2.5, 2.6, 3.1_

- [x] 3. Liquibase
- [x] 3.1 `038-create-work-prices.xml` (table with FK columns work_item_id/currency_id + named FK constraints + net_price/valid_from/valid_to; idempotent; register last)
  - _Requirements: 1.2, 1.3, 3.5_
- [x] 3.2 `039-seed-work-prices-resource.xml` — resource WORK_PRICES + grants (ADMIN CRUD / MANAGER CRU / FOREMAN,WORKER,FINANCIER READ / CLIENT none); idempotent; register last
  - _Requirements: 3.2, 3.3, 3.4, 3.5_
- [x] 3.3 Parse `docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv` (throwaway script) → generate WorkItem inserts (resolve work_category_id + unit_id via subselects) into changeset 039, guarded idempotent — 209 items across 13 categories
  - _Requirements: 4.1, 4.2, 4.5, 4.6_
- [x] 3.4 Generate current WorkPrice inserts (PLN, net_price=CENA, valid_from=seed date, valid_to=NULL; resolve work_item_id by name_pl+category) into changeset 039, guarded idempotent; skip header/blank/zero-price rows; delete the parse script — 209 prices
  - _Requirements: 4.3, 4.4, 4.5, 4.6_

- [x] 4. Checkpoint — backend compiles (MapStruct FK mapping generates cleanly)
  - _Requirements: 2.3_

- [x] 5. Backend tests
- [x] 5.1 `WorkPriceControllerIntegrationTest` (CRUD with FK ids + price + dates, rows expose FK ids + referenced values + current, filter by workItem.id)
  - _Requirements: 5.1, 2.2, 2.4, 2.5_
- [x] 5.2 `WorkPricesResourceSeedIntegrationTest` (resource; grants ADMIN CRUD / MANAGER CRU / FOREMAN,WORKER,FINANCIER READ / CLIENT none; work_items & work_prices seeded from CSV; current prices valid_to NULL + PLN; idempotency)
  - _Requirements: 5.2, 3.3, 4.1, 4.3, 4.6_

- [x] 6. Checkpoint — run only the two backend test classes
  - _Requirements: 5.1, 5.2_

- [x] 7. Frontend admin CRUD page
- [x] 7.1 Scaffolding (types with FK ids + referenced values + current, api via buildFetchQuery, hooks, zod schema)
  - _Requirements: 6.1, 6.5_
- [x] 7.2 List (columns workItem(ref)/currency(ref)/netPrice/validFrom/validTo/current) + page + form sheet (workItem/currency selects + price + dates) + delete dialog
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
- [x] 7.3 Routing + interim guard (`/catalog/prices`)
  - _Requirements: 6.1, 6.7_
- [x] 7.4 i18n `workPrices.*` + `nav.workPrices` (PL+RU parity)
  - _Requirements: 6.6_
- [x] 7.5 UI component test + i18n parity
  - _Requirements: 7.1, 7.2_

- [x] 8. Checkpoint — frontend tsc + the new UI test file
  - _Requirements: 7.1_

- [x] 9. Author test-cases.md (RU; API + UI; run-id/teardown; regression; report template)
  - _Requirements: 1, 2, 3, 4, 5, 6_

- [x] 10. Final checkpoint — backend two classes + frontend tsc/UI test
  - _Requirements: 5.1, 5.2, 7.1_
