# Implementation Plan

FOR-04-16: Material Dictionaries

## Overview

Four material dictionaries built IN PARALLEL as independent tracks, merged into one
spec:

- **Track A — MaterialType** (`MATERIAL_TYPES`, table `material_types`, changesets `046`/`047`): NEW backend CRUD vertical + frontend admin page `/material-types` + tests.
- **Track B — MaterialProducer** (`MATERIAL_PRODUCERS`, table `material_producers`, changesets `048`/`049`): NEW backend CRUD vertical + frontend admin page `/material-producers` + tests.
- **Track C — Material** (`MATERIALS`, table `materials`, changesets `050`/`051`): NEW backend CRUD vertical + frontend admin page `/materials` + tests.
- **Track D — MaterialCategory re-seed** (`MATERIAL_CATEGORIES`, existing table/resource/UI from FOR-04-09, changeset `052`): ONLY a re-seed changeset + a seed integration test. No entity/controller/UI work.

Each new track mirrors FOR-04-09. Changesets are numbered `046+` (current highest is
`045`), each registered last in `changelog.xml`, idempotent per
`.kiro/steering/entity-creation-rules.md`. The three tracks (A/B/C) and the re-seed
(D) proceed in parallel (see the Task Dependency Graph). Interim route guards
(`RESOURCE`/`READ`) protect the three new pages until FOR-04-15 wires the menu.

## Tasks

- [x] 1. Track A — MaterialType backend vertical
  - [x] 1.1 `MaterialTypeEntity` (`@Table("material_types")`, code/nameRU/namePL/active) + `MaterialTypeDao extends AdminDao` + service/controller models (localized + extended; Create/Update(no code)/Response records)
    - _Requirements: 1.1, 1.2, 1.6, 1.7_
  - [x] 1.2 Mappers (`MaterialTypeServiceMapper` i18n=name, ignore id+code on update; `MaterialTypeControllerMapper` ignore id/create, id+code/update, active→true) + `MaterialTypeService implements AdminService<...>` (NOT `ProjectScopedService`) + `MaterialTypeController` at `/api/material-types`, `@PermissionResource("MATERIAL_TYPES")`
    - _Requirements: 1.4, 1.5, 1.6, 1.9_
  - [x] 1.3 `046-create-material-types.xml` (table, idempotent `tableExists`+`MARK_RAN`; register last)
    - _Requirements: 1.2, 1.3, 4.4_
  - [x] 1.4 `047-seed-material-types-resource.xml` (resource `MATERIAL_TYPES` + grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none + 39 `Typ` seed rows; idempotent `MARK_RAN`+`sqlCheck`; register last)
    - _Requirements: 1.8, 4.1, 4.2, 4.3, 4.4_

- [x] 2. Track B — MaterialProducer backend vertical
  - [x] 2.1 `MaterialProducerEntity` (`@Table("material_producers")`, code/nameRU/namePL/active) + `MaterialProducerDao extends AdminDao` + service/controller models
    - _Requirements: 2.1, 2.2, 2.6, 2.7_
  - [x] 2.2 Mappers + `MaterialProducerService implements AdminService<...>` (NOT `ProjectScopedService`) + `MaterialProducerController` at `/api/material-producers`, `@PermissionResource("MATERIAL_PRODUCERS")`
    - _Requirements: 2.4, 2.5, 2.6, 2.10_
  - [x] 2.3 `048-create-material-producers.xml` (table, idempotent; register last)
    - _Requirements: 2.2, 2.3, 4.4_
  - [x] 2.4 `049-seed-material-producers-resource.xml` (resource `MATERIAL_PRODUCERS` + grants + 32 `Producent` seed rows, EXCLUDING `Podkład`; idempotent; register last)
    - _Requirements: 2.8, 2.9, 4.1, 4.2, 4.3, 4.4_

- [x] 3. Track C — Material backend vertical
  - [x] 3.1 `MaterialEntity` (`@Table("materials")`, code/nameRU/namePL/active) + `MaterialDao extends AdminDao` + service/controller models
    - _Requirements: 3.1, 3.2, 3.6, 3.7_
  - [x] 3.2 Mappers + `MaterialService implements AdminService<...>` (NOT `ProjectScopedService`) + `MaterialController` at `/api/materials`, `@PermissionResource("MATERIALS")`
    - _Requirements: 3.4, 3.5, 3.6, 3.9_
  - [x] 3.3 `050-create-materials.xml` (table, idempotent; register last)
    - _Requirements: 3.2, 3.3, 4.4_
  - [x] 3.4 `051-seed-materials-resource.xml` (resource `MATERIALS` + grants + 11 `Materiał` seed rows; idempotent; register last)
    - _Requirements: 3.8, 4.1, 4.2, 4.3, 4.4_

- [x] 4. Track D — MaterialCategory re-seed
  - [x] 4.1 `052-reseed-material-categories.xml` — DELETE `construction`/`finishing` (guarded, safe: no FinishingMaterial FK rows yet) + INSERT 5 `Kategoria` rows (`drzwi`, `listwy_przypodlogowe`, `podloga`, `plytki`, `sanitariat`), each guarded by `sqlCheck` on `code`; do NOT touch the `MATERIAL_CATEGORIES` resource/grants; register last
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [x] 5. Checkpoint — backend compiles, changelog registers 046–052 in order
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 4.4, 5.1_

- [x] 6. Backend tests
  - [x] 6.1 `MaterialTypeControllerIntegrationTest` (CRUD, i18n name ru/pl/fallback, code immutable, create validation 400)
    - _Requirements: 7.1, 1.5, 1.6, 1.7_
  - [x] 6.2 `MaterialTypesResourceSeedIntegrationTest` (resource; grants incl. FINANCIER READ / CLIENT none; seeded codes e.g. `laminat`/`plytka_scienna`; idempotency)
    - _Requirements: 7.2, 4.1, 4.2, 1.8_
  - [x] 6.3 `MaterialProducerControllerIntegrationTest` (CRUD, i18n, code immutable, create validation 400)
    - _Requirements: 7.1, 2.5, 2.6, 2.7_
  - [x] 6.4 `MaterialProducersResourceSeedIntegrationTest` (resource; grants; seeded codes e.g. `egger`/`villeroy_boch`; `Podkład` absent; idempotency)
    - _Requirements: 7.2, 4.1, 4.2, 2.8, 2.9_
  - [x] 6.5 `MaterialControllerIntegrationTest` (CRUD, i18n, code immutable, create validation 400)
    - _Requirements: 7.1, 3.5, 3.6, 3.7_
  - [x] 6.6 `MaterialsResourceSeedIntegrationTest` (resource; grants; seeded codes e.g. `podloga`/`plytki`; idempotency)
    - _Requirements: 7.2, 4.1, 4.2, 3.8_
  - [x] 6.7 `MaterialCategoriesReseedIntegrationTest` (`construction`/`finishing` absent; 5 new codes present with nameRU/namePL; idempotent)
    - _Requirements: 7.3, 5.2, 5.3_

- [x] 7. Checkpoint — run only the affected backend test classes
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 7.1, 7.2, 7.3_

- [x] 8. Track A — MaterialType admin page
  - [x] 8.1 Scaffolding (types, `material-types-api.ts` via `buildFetchQuery`, hooks `materialTypeKeys`, zod schema)
    - _Requirements: 6.1, 6.5_
  - [x] 8.2 `MaterialTypesList` + `MaterialTypesPage` + `MaterialTypeFormSheet` + `DeleteMaterialTypeDialog` (columns code/name/active; code read-only on edit) + routing + interim guard (`/material-types` → `MATERIAL_TYPES`/`READ`) + i18n `materialTypes.*` + `nav.materialTypes` (PL+RU parity)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_
  - [x] 8.3 `MaterialTypesList.test.tsx` + `materialTypes.*` i18n parity test
    - _Requirements: 8.1, 8.2_

- [x] 9. Track B — MaterialProducer admin page
  - [x] 9.1 Scaffolding (types, `material-producers-api.ts`, hooks `materialProducerKeys`, zod schema)
    - _Requirements: 6.1, 6.5_
  - [x] 9.2 `MaterialProducersList` + `MaterialProducersPage` + form sheet + delete dialog + routing + interim guard (`/material-producers` → `MATERIAL_PRODUCERS`/`READ`) + i18n `materialProducers.*` + `nav.materialProducers` (PL+RU parity)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_
  - [x] 9.3 `MaterialProducersList.test.tsx` + `materialProducers.*` i18n parity test
    - _Requirements: 8.1, 8.2_

- [x] 10. Track C — Material admin page
  - [x] 10.1 Scaffolding (types, `materials-api.ts`, hooks `materialKeys`, zod schema)
    - _Requirements: 6.1, 6.5_
  - [x] 10.2 `MaterialsList` + `MaterialsPage` + form sheet + delete dialog + routing + interim guard (`/materials` → `MATERIALS`/`READ`) + i18n `materials.*` + `nav.materials` (PL+RU parity)
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_
  - [x] 10.3 `MaterialsList.test.tsx` + `materials.*` i18n parity test
    - _Requirements: 8.1, 8.2_

- [x] 11. Checkpoint — frontend tsc + the new UI test files
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 8.1_

- [x] 12. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping per dictionary, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). API scenarios are API tests against the Dockerized app (`http://localhost:8080`, auth `/api/auth`, first ADMIN via `FOREMEN_ADMIN_*`); PLUS browser-engine UI scenarios against `http://localhost:3000` for each new page. Result artifacts are MD reports with tables. Document in Russian. Cover all four dictionaries incl. the categories re-seed regression.
  - _Requirements: 1, 2, 3, 4, 5, 6, 7, 8_

- [x] 13. Final checkpoint — affected backend classes + frontend tsc/UI tests
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 7.1, 7.2, 7.3, 8.1_

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP.
- Each task references specific requirements for traceability.
- Changesets are numbered `046+` (current highest is `045`); all files registered last in `changelog.xml` in order `046`→`052`.
- PBT is not applicable (plain dictionary CRUD + data re-seed) — no property test sub-tasks.
- Run ONLY the affected classes/files per the workspace test standard.
- The four dictionaries are independent parallel tracks: A (types) / B (producers) / C (materials) / D (categories re-seed). Tracks touch disjoint files (separate entities, changesets, features), so they can be developed concurrently.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "3.1", "4.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.2", "2.3", "3.2", "3.3"] },
    { "id": 2, "tasks": ["1.4", "2.4", "3.4"] },
    { "id": 3, "tasks": ["6.1", "6.2", "6.3", "6.4", "6.5", "6.6", "6.7", "8.1", "9.1", "10.1"] },
    { "id": 4, "tasks": ["8.2", "9.2", "10.2"] },
    { "id": 5, "tasks": ["8.3", "9.3", "10.3"] }
  ]
}
```
