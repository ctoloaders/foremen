# Tasks — FOR-04-03: Currencies dictionary

- [x] 1. Backend entity, DAO, models
- [x] 1.1 Create `CurrencyEntity` and `CurrencyDao`
  - `dao/model/CurrencyEntity` extends `BaseEntity` (`code` unique NOT NULL, `symbol` NOT NULL, `nameRU`/`namePL` NOT NULL, `boolean active = true`), `@Table("currencies")`; `dao/CurrencyDao extends AdminDao<CurrencyEntity, Long>`
  - _Requirements: 1.1, 1.2_
- [x] 1.2 Create service + controller models
  - `service/model`: `CurrencyServiceModel` (localized `name` + symbol, `@Data`), `CurrencyServiceExtendedModel` (record). `controller/model` records: DtoModel, DtoExtendedModel, CreateRequest (`@NotBlank code/symbol/nameRU/namePL`, `Boolean active`), UpdateRequest (no `code`), CreateResponse, UpdateResponse
  - _Requirements: 2.4, 2.5_

- [x] 2. Backend mappers, service, controller
- [x] 2.1 Create the MapStruct mappers
  - `CurrencyServiceMapper` (`getI18nSupportedProperties()=Set.of("name")`, `updateFields` ignoring `id`+`code`); `CurrencyControllerMapper` (ignore `id` on create, `id`+`code` on update; default `active` true when omitted)
  - _Requirements: 2.2, 2.3, 2.4_
- [x] 2.2 Create `CurrencyService`
  - `implements AdminService<...>`, Lombok `@Getter @RequiredArgsConstructor`, fields `dao, mapper, auditLogDao, entityManager, daoModelClass`
  - _Requirements: 2.1_
- [x] 2.3 Create `CurrencyController`
  - `implements AdminController<...>` at `/api/currencies`, `@PermissionResource("CURRENCIES")`; no CRUD overrides
  - _Requirements: 2.1, 3.1_

- [x] 3. Liquibase migrations
- [x] 3.1 Create table changeset `020-create-currencies.xml`
  - Create `currencies` (id BIGSERIAL PK; code NOT NULL UNIQUE; symbol NOT NULL; name_ru/name_pl NOT NULL; active BOOLEAN NOT NULL default true; 4 audit columns), idempotent `tableExists` + `MARK_RAN`; register last in `changelog.xml`
  - _Requirements: 1.2, 1.3, 3.5_
- [x] 3.2 Create seed changeset `021-seed-currencies-resource.xml`
  - Resource row `CURRENCIES`; per-role grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; default currencies `PLN,EUR,USD` (symbol + RU/PL, active=true); all idempotent (`sqlCheck expectedResult="0"`); register last in `changelog.xml`
  - _Requirements: 3.2, 3.3, 3.4, 3.5, 4.1, 4.2_

- [x] 4. Checkpoint - Backend compiles and startup validation passes
  - `./gradlew compileJava compileTestJava` (temp log); `PermissionAnnotationValidator` covered by the integration tests
  - _Requirements: 3.1_

- [x] 5. Backend tests
- [x] 5.1 Write `CurrencyControllerIntegrationTest`
  - Testcontainers + MockMvc CRUD (create/list/read/update/delete), i18n `name` (RU vs PL), code immutability + symbol update; mirror `MeasurementUnitControllerIntegrationTest`
  - _Requirements: 5.1, 2.2, 2.4_
- [x] 5.2 Write `CurrenciesResourceSeedIntegrationTest`
  - Real changelog run: resource exists; grants exactly ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; three default codes present; idempotent re-run; mirror `MeasurementUnitsResourceSeedIntegrationTest`
  - _Requirements: 5.2, 3.3, 4.1_

- [x] 6. Checkpoint - Backend affected tests pass
  - Run ONLY the two new test classes with `--tests`, temp log, read JUnit XML; never the full suite
  - _Requirements: 5.1, 5.2_

- [x] 7. Frontend admin CRUD page
- [x] 7.1 Create the feature scaffolding (types, api, hooks, schema)
  - `features/currencies/`: types; `api/currencies-api.ts` (list via shared `buildFetchQuery`, fetch one, create, update, delete); `api/query-hooks.ts` + `api/mutation-hooks.ts`; `schemas/currency-schema.ts` (zod create + update(omit code))
  - _Requirements: 6.1, 6.5_
- [x] 7.2 Build the list + page + form sheet + delete dialog
  - `CurrenciesList` (ColumnConfig code/symbol/name/active-badge, `usePermission('CURRENCIES', …)`, fetchFn adapter, DataTable); `CurrenciesPage`; `CurrencyFormSheet` (create/edit, code read-only on edit, symbol field); `DeleteCurrencyDialog`; local `ActiveBadge`
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
- [x] 7.3 Wire routing + interim guard
  - `router.tsx`: lazy import + `{ path: 'currencies', ... }`; `route-permissions.ts`: interim `extra['/currencies'] = { resource:'CURRENCIES', operation:'READ' }`
  - _Requirements: 6.1, 6.7_
- [x] 7.4 Add i18n keys (PL + RU)
  - `currencies.*` namespace (pageTitle, search, actions, list, table[code/symbol/name/active/actions], badge, form[+symbol], delete, toast, errors, validation) + `nav.currencies` in BOTH `pl.json` and `ru.json` at parity
  - _Requirements: 6.6_
- [x] 7.5 Write the UI component test + i18n parity check
  - `__tests__/CurrenciesList.test.tsx` mirroring `MeasurementUnitsList.test.tsx` (rows with code/symbol/name/active badge, permission-gated controls, empty state); assert `currencies.*` PL/RU parity
  - _Requirements: 7.1, 7.2_

- [x] 8. Checkpoint - Frontend compiles and affected tests pass
  - `npx tsc -b --noEmit` (temp log) + run ONLY the new vitest file scoped; never the full frontend suite
  - _Requirements: 7.1_

- [x] 9. Author test-cases.md
  - Create `test-cases.md` following `.kiro/steering/test-cases.md` (Russian, feature grouping, repeatability via run-id/teardown, regression group, MD report template). Cover BOTH API scenarios (HTTP against the Dockerized app at `http://localhost:8080`) AND browser-engine UI scenarios for the CRUD page
  - _Requirements: 1, 2, 3, 4, 6_

- [x] 10. Final checkpoint - Ensure affected tests pass
  - Backend: compile + the two backend test classes. Frontend: `tsc` + the new UI test file. Read from logs/JUnit XML; never the full suites
  - _Requirements: 5.1, 5.2, 7.1_
