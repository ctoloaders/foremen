# Tasks — FOR-04-02: Measurement Units dictionary

- [x] 1. Entity, DAO, and models
- [x] 1.1 Create `MeasurementUnitEntity` and `MeasurementUnitDao`
  - `dao/model/MeasurementUnitEntity` extends `BaseEntity` (`code` unique NOT NULL, `nameRU`/`namePL` NOT NULL, `boolean active = true`), `@Table("measurement_units")`; `dao/MeasurementUnitDao extends AdminDao<MeasurementUnitEntity, Long>`
  - _Requirements: 1.1, 1.2_
- [x] 1.2 Create service + controller models
  - `service/model`: `MeasurementUnitServiceModel` (localized `name`, `@Data`), `MeasurementUnitServiceExtendedModel` (record, all locales). `controller/model` records: DtoModel, DtoExtendedModel, CreateRequest (`@NotBlank code/nameRU/namePL`, `Boolean active`), UpdateRequest (no `code`), CreateResponse, UpdateResponse
  - _Requirements: 2.4, 2.5_

- [x] 2. Mappers, service, controller
- [x] 2.1 Create the MapStruct mappers
  - `MeasurementUnitServiceMapper extends ServiceToDaoMapper<...>` with `getI18nSupportedProperties()=Set.of("name")` and `updateFields` ignoring `id`+`code`; `MeasurementUnitControllerMapper extends ControllerToServiceMapper<...>` (ignore `id` on create; default `active` to true when omitted)
  - _Requirements: 2.2, 2.3, 2.4_
- [x] 2.2 Create `MeasurementUnitService`
  - `implements AdminService<...>`, Lombok `@Getter @RequiredArgsConstructor`, fields `dao, mapper, auditLogDao, entityManager, daoModelClass`
  - _Requirements: 2.1_
- [x] 2.3 Create `MeasurementUnitController`
  - `implements AdminController<...>` at `/api/measurement-units`, `@PermissionResource("MEASUREMENT_UNITS")`, delegating `getMapper()`/`getService()`; no CRUD method overrides
  - _Requirements: 2.1, 3.1_

- [x] 3. Liquibase migrations
- [x] 3.1 Create table changeset `018-create-measurement-units.xml`
  - Create `measurement_units` (id BIGSERIAL PK; code NOT NULL UNIQUE; name_ru/name_pl NOT NULL; active BOOLEAN NOT NULL default true; 4 audit columns), idempotent `tableExists` + `MARK_RAN`; register last in `changelog.xml`
  - _Requirements: 1.2, 1.3, 3.5_
- [x] 3.2 Create seed changeset `019-seed-measurement-units-resource.xml`
  - Resource row `MEASUREMENT_UNITS`; per-role grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; default units `m2,mb,szt,kpl,godz,proc,m3` (RU/PL, active=true); all idempotent (`sqlCheck expectedResult="0"`); register last in `changelog.xml`
  - _Requirements: 3.2, 3.3, 3.4, 3.5, 4.1, 4.2_

- [x] 4. Checkpoint - Ensure it compiles and startup validation passes
  - `./gradlew compileJava compileTestJava` (temp log); rely on `PermissionAnnotationValidator` at boot in the integration tests
  - _Requirements: 3.1_

- [x] 5. Backend tests
- [x] 5.1 Write `MeasurementUnitControllerIntegrationTest`
  - Testcontainers + MockMvc CRUD (create/list/read/update/delete), i18n `name` (RU vs PL), code immutability on update; mirror `RoleControllerIntegrationTest`
  - _Requirements: 5.1, 2.2, 2.4_
- [x] 5.2 Write `MeasurementUnitsResourceSeedIntegrationTest`
  - Real changelog run: resource exists; grants exactly ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; seven default unit codes present; idempotent re-run; mirror `ProjectMembersResourceSeedIntegrationTest`
  - _Requirements: 5.2, 3.3, 4.1_

- [x] 6. Checkpoint - Ensure affected backend tests pass
  - Run ONLY the two new test classes with `--tests`, redirect to a temp log, read pass/fail from JUnit XML; never the full suite
  - _Requirements: 5.1, 5.2_

- [x] 7. Frontend admin CRUD page
- [x] 7.1 Create the feature scaffolding (types, api, hooks, schema)
  - `features/measurement-units/types/index.ts` (Dto, ExtendedDto, Create/Update requests, FormMode); `api/measurement-units-api.ts` (fetch list via shared `buildFetchQuery`, fetch one, create, update, delete over `apiRequest`); `api/query-hooks.ts` + `api/mutation-hooks.ts` (`measurementUnitKeys`, `useMeasurementUnit`, `useCreate|Update|DeleteMeasurementUnit` with list invalidation); `schemas/measurement-unit-schema.ts` (zod create + update(omit code))
  - _Requirements: 6.1, 6.5_
- [x] 7.2 Build the list + page + form sheet + delete dialog
  - `MeasurementUnitsList` with `ColumnConfig` (code, name, active-badge), `usePermission('MEASUREMENT_UNITS', …)` gating, `fetchFn` adapter, `DataTable entityKey="measurement-units" resource="MEASUREMENT_UNITS"`; `MeasurementUnitsPage` (title + sheet/dialog state + success toasts); `MeasurementUnitFormSheet` (create/edit, code read-only on edit, inline validation); `DeleteMeasurementUnitDialog`
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
- [x] 7.3 Wire routing + interim guard
  - `src/app/router.tsx`: lazy import + `{ path: 'measurement-units', ... }` route; `src/config/route-permissions.ts`: interim `extra['/measurement-units'] = { resource:'MEASUREMENT_UNITS', operation:'READ' }`
  - _Requirements: 6.1, 6.7_
- [x] 7.4 Add i18n keys (PL + RU)
  - Add the `measurementUnits.*` namespace (pageTitle, search, actions, list, table, badge, form, delete, toast, errors, validation) + `nav.measurementUnits` to BOTH `pl.json` and `ru.json` at parity; no raw keys render
  - _Requirements: 6.6_
- [x] 7.5 Write the UI component test + i18n parity check
  - `__tests__/MeasurementUnitsList.test.tsx` mirroring `RolesListTab.test.tsx` (rows with code/name/active badge, permission-gated controls, empty state); assert `measurementUnits.*` PL/RU key parity
  - _Requirements: 7.1, 7.2_

- [x] 8. Checkpoint - Ensure frontend compiles and affected tests pass
  - `npx tsc -b --noEmit` (temp log) + run ONLY the new vitest file(s) scoped; never the full frontend suite
  - _Requirements: 7.1_

- [x] 9. Author test-cases.md
  - Create `test-cases.md` in the spec folder following `.kiro/steering/test-cases.md` (Russian, feature grouping, repeatability via run-id/teardown, regression group, MD report template). Cover BOTH the API scenarios (HTTP against the Dockerized app at `http://localhost:8080`) AND browser-engine UI scenarios for the CRUD page (list/search/sort/filter, create/edit/delete, permission gating, i18n)
  - _Requirements: 1, 2, 3, 4, 6_

- [x] 10. Final checkpoint - Ensure affected tests pass
  - Backend: compile + the two backend test classes. Frontend: `tsc` + the new UI test file(s). Read results from logs/JUnit XML; never the full suites
  - _Requirements: 5.1, 5.2, 7.1_
