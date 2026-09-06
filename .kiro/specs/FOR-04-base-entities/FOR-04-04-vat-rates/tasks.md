# Tasks — FOR-04-04: VAT Rates dictionary

- [x] 1. Backend entity, DAO, models
- [x] 1.1 `VatRateEntity` + `VatRateDao`
  - `dao/model/VatRateEntity` extends `BaseEntity` (`code` unique NOT NULL, `rate` BigDecimal NOT NULL, `nameRU`/`namePL` NOT NULL, `boolean isDefault=false`, `boolean active=true`), `@Table("vat_rates")`; `VatRateDao extends AdminDao<VatRateEntity, Long>`
  - _Requirements: 1.1, 1.2_
- [x] 1.2 Service + controller models
  - service `VatRateServiceModel`/`VatRateServiceExtendedModel`; controller records DtoModel, DtoExtendedModel, CreateRequest (`@NotBlank code/nameRU/namePL`, `@NotNull rate`, `Boolean isDefault/active`), UpdateRequest (no `code`), Create/UpdateResponse
  - _Requirements: 2.4, 2.5_

- [x] 2. Backend mappers, service, controller
- [x] 2.1 Mappers (`VatRateServiceMapper` i18n=name, updateFields ignore id+code; `VatRateControllerMapper` ignore id/create, id+code/update, default active→true & isDefault→false)
  - _Requirements: 2.2, 2.3, 2.4_
- [x] 2.2 `VatRateService implements AdminService<...>`
  - _Requirements: 2.1_
- [x] 2.3 `VatRateController` at `/api/vat-rates`, `@PermissionResource("VAT_RATES")`
  - _Requirements: 2.1, 3.1_

- [x] 3. Liquibase
- [x] 3.1 `022-create-vat-rates.xml` (table, idempotent; register last)
  - _Requirements: 1.2, 1.3, 3.5_
- [x] 3.2 `023-seed-vat-rates-resource.xml` (resource + grants + defaults 23/8/5/0 with 23 default; idempotent; register last)
  - _Requirements: 3.2, 3.3, 3.4, 3.5, 4.1, 4.2_

- [x] 4. Checkpoint — backend compiles (`compileJava compileTestJava`)
  - _Requirements: 3.1_

- [x] 5. Backend tests
- [x] 5.1 `VatRateControllerIntegrationTest` (CRUD, i18n, code immutable, rate+isDefault update)
  - _Requirements: 5.1, 2.2, 2.4_
- [x] 5.2 `VatRatesResourceSeedIntegrationTest` (resource, grants, four defaults + 23% default flag, idempotency)
  - _Requirements: 5.2, 3.3, 4.1_

- [x] 6. Checkpoint — run only the two backend test classes
  - _Requirements: 5.1, 5.2_

- [x] 7. Frontend admin CRUD page
- [x] 7.1 Scaffolding (types, api via buildFetchQuery, hooks, zod schema)
  - _Requirements: 6.1, 6.5_
- [x] 7.2 List + page + form sheet + delete dialog (columns code/rate/name/isDefault/active; rate numeric input; isDefault+active checkboxes)
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
- [x] 7.3 Routing + interim guard
  - _Requirements: 6.1, 6.7_
- [x] 7.4 i18n `vatRates.*` + `nav.vatRates` (PL+RU parity)
  - _Requirements: 6.6_
- [x] 7.5 UI component test + i18n parity
  - _Requirements: 7.1, 7.2_

- [x] 8. Checkpoint — frontend `tsc` + the new UI test file
  - _Requirements: 7.1_

- [x] 9. Author test-cases.md (RU; API + UI; run-id/teardown; regression; report template)
  - _Requirements: 1, 2, 3, 4, 6_

- [x] 10. Final checkpoint — backend two classes + frontend tsc/UI test
  - _Requirements: 5.1, 5.2, 7.1_
