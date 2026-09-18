# Requirements Document

FOR-04-16: Material Dictionaries

## Introduction

FOR-04-16 delivers ALL material reference dictionaries derived from
`docs/Materiały pakiety - Lista.csv`, built IN PARALLEL and merged into ONE spec.
It covers FOUR dictionaries, each modeled exactly on FOR-04-09 (Material
Categories): fields `code`, `nameRU`, `namePL`, `active`; i18n only on `name`
(PL fallback); `code` immutable on update; create validates `code`/`nameRU`/`namePL`
non-blank. All four are GLOBAL admin resources, NOT project-scoped.

The four dictionaries:

1. **MaterialType** (`MATERIAL_TYPES`, Polish column `Typ`) — NEW. Table
   `material_types`, path `/api/material-types`. Seed = the 39-row distinct `Typ`
   set from the CSV.
2. **MaterialProducer** (`MATERIAL_PRODUCERS`, Polish column `Producent`) — NEW.
   Table `material_producers`, path `/api/material-producers`. Seed = 32 distinct
   real producers from the CSV (the bogus value `Podkład` is excluded — it is a
   data-entry error where `Producent` was filled with a `Typ`).
3. **Material** (`MATERIALS`, Polish column `Materiał`) — NEW. Table `materials`,
   path `/api/materials`. Seed = 11 distinct `Materiał` values from the CSV.
4. **MaterialCategory** (`MATERIAL_CATEGORIES`, Polish column `Kategoria`) — REUSE
   EXISTING (FOR-04-09). The resource, table, controller/service/entity and UI
   already exist and are NOT recreated. The ONLY change is a RE-SEED of the
   dictionary values: delete the two obsolete rows `construction`/`finishing`
   (seeded in changeset `033`) and insert the 5 distinct `Kategoria` values from
   the CSV.

Access for the three NEW resources is identical to `MATERIAL_CATEGORIES`: ADMIN
full CRUD; MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT none (deny-by-default).
The three new admin pages (`/material-types`, `/material-producers`, `/materials`)
carry interim `RESOURCE`/`READ` route guards until FOR-04-15 wires the menu. The
existing `/material-categories` page and API are reused as-is.

The current highest Liquibase changeset is `045`; all new changesets are numbered
`046+`, registered last in `changelog.xml`, and idempotent per
`.kiro/steering/entity-creation-rules.md`.

## Glossary

- **MaterialType**: dictionary entity for the material's type/kind (CSV column `Typ`).
- **MaterialProducer**: dictionary entity for the material producer/brand (CSV column `Producent`).
- **Material**: dictionary entity for the named material (CSV column `Materiał`).
- **MaterialCategory**: existing dictionary entity for the material category (CSV column `Kategoria`), delivered by FOR-04-09 and re-seeded here.
- **MATERIAL_TYPES / MATERIAL_PRODUCERS / MATERIALS / MATERIAL_CATEGORIES**: the ABAC resource codes guarding the respective controllers.
- **System**: the Foremen backend + frontend under this spec.
- **PL fallback**: localized `name` resolves to `nameRU` for `ru`, else `namePL`.
- **Dictionary vertical**: the full stack for one dictionary — entity + DAO + service + controller + Liquibase table + seed + ABAC + frontend admin page + i18n + tests.

## Requirements

### Requirement 1: MaterialType entity, table & seed

**User Story:** As a developer, I want a MaterialType dictionary seeded from the distinct Typ values, so that material types are managed with code/nameRU/namePL/active.

#### Acceptance Criteria

1. THE System SHALL map a `MaterialTypeEntity` extending `BaseEntity` with `code`, `nameRU`, `namePL`, `active`.
2. THE `material_types` table SHALL have `code` `NOT NULL UNIQUE`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).
4. THE System SHALL expose `MaterialTypeController` implementing `AdminController` at `/api/material-types`, annotated `@PermissionResource("MATERIAL_TYPES")`.
5. WHEN the request locale is `ru`, THE System SHALL resolve localized `name` to `nameRU`; otherwise THE System SHALL resolve `name` to `namePL` (PL fallback), including when sorting or filtering by `name`.
6. WHEN an update request is received, THE System SHALL keep `code` unchanged and SHALL update `nameRU`, `namePL`, `active`.
7. WHEN a create request is received, THE System SHALL validate `code`, `nameRU`, `namePL` as non-blank.
8. THE seed SHALL insert the 39 distinct `Typ` rows (`namePL` = CSV value, `code` = normalized slug, `nameRU` = Russian translation, `active = true`), each guarded by `sqlCheck` on `code` (`expectedResult="0"` + `MARK_RAN`).
9. THE `MaterialTypeService` SHALL NOT implement `ProjectScopedService` (global admin resource).

### Requirement 2: MaterialProducer entity, table & seed

**User Story:** As a developer, I want a MaterialProducer dictionary seeded from the distinct Producent values, so that producers/brands are managed with code/nameRU/namePL/active.

#### Acceptance Criteria

1. THE System SHALL map a `MaterialProducerEntity` extending `BaseEntity` with `code`, `nameRU`, `namePL`, `active`.
2. THE `material_producers` table SHALL have `code` `NOT NULL UNIQUE`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).
4. THE System SHALL expose `MaterialProducerController` implementing `AdminController` at `/api/material-producers`, annotated `@PermissionResource("MATERIAL_PRODUCERS")`.
5. WHEN the request locale is `ru`, THE System SHALL resolve localized `name` to `nameRU`; otherwise THE System SHALL resolve `name` to `namePL` (PL fallback), including when sorting or filtering by `name`.
6. WHEN an update request is received, THE System SHALL keep `code` unchanged and SHALL update `nameRU`, `namePL`, `active`.
7. WHEN a create request is received, THE System SHALL validate `code`, `nameRU`, `namePL` as non-blank.
8. THE seed SHALL insert the 32 distinct real `Producent` rows (proper brand names; `namePL` = the brand as-is; `nameRU` = the same brand name kept in Latin; `code` = normalized slug; `active = true`), each guarded by `sqlCheck` on `code`.
9. THE seed SHALL EXCLUDE the bogus `Producent` value `Podkład` (a data-entry error where `Producent` was filled with a `Typ`).
10. THE `MaterialProducerService` SHALL NOT implement `ProjectScopedService` (global admin resource).

### Requirement 3: Material entity, table & seed

**User Story:** As a developer, I want a Material dictionary seeded from the distinct Materiał values, so that named materials are managed with code/nameRU/namePL/active.

#### Acceptance Criteria

1. THE System SHALL map a `MaterialEntity` extending `BaseEntity` with `code`, `nameRU`, `namePL`, `active`.
2. THE `materials` table SHALL have `code` `NOT NULL UNIQUE`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).
4. THE System SHALL expose `MaterialController` implementing `AdminController` at `/api/materials`, annotated `@PermissionResource("MATERIALS")`.
5. WHEN the request locale is `ru`, THE System SHALL resolve localized `name` to `nameRU`; otherwise THE System SHALL resolve `name` to `namePL` (PL fallback), including when sorting or filtering by `name`.
6. WHEN an update request is received, THE System SHALL keep `code` unchanged and SHALL update `nameRU`, `namePL`, `active`.
7. WHEN a create request is received, THE System SHALL validate `code`, `nameRU`, `namePL` as non-blank.
8. THE seed SHALL insert the 11 distinct `Materiał` rows (`namePL` = CSV value, `code` = normalized slug, `nameRU` = Russian translation, `active = true`), each guarded by `sqlCheck` on `code`.
9. THE `MaterialService` SHALL NOT implement `ProjectScopedService` (global admin resource).

### Requirement 4: ABAC resources & grants for the three new dictionaries

**User Story:** As a security admin, I want MATERIAL_TYPES, MATERIAL_PRODUCERS and MATERIALS guarded by ABAC with ADMIN CRUD and read-only for other system roles, so that only authorized roles modify them.

#### Acceptance Criteria

1. FOR EACH new resource (`MATERIAL_TYPES`, `MATERIAL_PRODUCERS`, `MATERIALS`), THE seed SHALL insert the resource row (`nameRU`/`namePL` + `descriptionRU`/`descriptionPL`).
2. FOR EACH new resource, THE seed SHALL grant ADMIN CRUD and MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT SHALL receive no grant (deny-by-default).
3. ALL seed changesets SHALL be idempotent (`MARK_RAN` + `sqlCheck expectedResult="0"`).
4. THE new changeset files SHALL be registered last in `changelog.xml`, after the current highest (`045`), numbered `046+`.

### Requirement 5: Re-seed the existing MaterialCategory dictionary

**User Story:** As an admin, I want the existing MATERIAL_CATEGORIES dictionary re-seeded from the distinct Kategoria values, so that obsolete construction/finishing rows are replaced by the 5 real CSV categories.

#### Acceptance Criteria

1. THE re-seed SHALL be a NEW idempotent changeset numbered `046+`, registered last in `changelog.xml`.
2. THE re-seed SHALL DELETE the two obsolete rows `WHERE code IN ('construction','finishing')`, guarded so it is safe and idempotent (no dependent FK rows reference categories yet — no `FinishingMaterial` exists — so the delete is safe).
3. THE re-seed SHALL INSERT the 5 distinct `Kategoria` values (`drzwi`, `listwy_przypodlogowe`, `podloga`, `plytki`, `sanitariat`) with `nameRU`/`namePL` and `active = true`, each guarded by `sqlCheck` on `code` so re-runs insert nothing.
4. THE re-seed SHALL NOT touch the `MATERIAL_CATEGORIES` resource row or its ABAC grants (already seeded in `033`); only the dictionary values change.
5. THE existing `/material-categories` admin page and `/api/material-categories` API SHALL be reused as-is (no entity/controller/UI changes).

### Requirement 6: Admin CRUD UI for the three new dictionaries

**User Story:** As an admin, I want material-types, material-producers and materials admin pages on the shared DataTable, so that I can manage the dictionaries without using the API directly.

#### Acceptance Criteria

1. FOR EACH new dictionary, THE System SHALL provide a lazy-loaded page (`MaterialTypesPage` at `/material-types`, `MaterialProducersPage` at `/material-producers`, `MaterialsPage` at `/materials`) using the shared `DataTable` with columns `code`, `name`, `active`.
2. EACH page SHALL use server search/sort/filter/pagination; `active` SHALL render as a localized badge.
3. EACH page SHALL provide a create/edit form sheet (`code`, `nameRU`, `namePL`, `active`) with validation; `code` SHALL be read-only on edit; delete SHALL be via a confirmation dialog.
4. THE create/edit/delete controls SHALL be permission-gated via `usePermission` on the page's resource.
5. THE fetch adapter SHALL use the shared `buildFetchQuery`; mutations SHALL invalidate the list and SHALL show a toast.
6. ALL UI text SHALL be localized under `materialTypes.*`, `materialProducers.*` and `materials.*` in BOTH `pl.json`/`ru.json` at parity; no raw keys SHALL render.
7. UNTIL FOR-04-15 wires the menu, EACH new route SHALL be guarded by an interim `RESOURCE`/`READ` guard (`MATERIAL_TYPES`, `MATERIAL_PRODUCERS`, `MATERIALS`).

### Requirement 7: Backend tests

**User Story:** As a developer, I want integration tests for each API and seed, so that CRUD, i18n, immutability, grants and idempotency are verified.

#### Acceptance Criteria

1. FOR EACH new dictionary, THE System SHALL have a Testcontainers CRUD integration test (create/list/read/update/delete, i18n `name` resolution, `code` immutability, create validation `400`).
2. FOR EACH new dictionary, THE System SHALL have a Liquibase seed integration test: resource present; exact grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; seeded codes present; re-run idempotency.
3. THE System SHALL have a `MaterialCategories` re-seed integration test asserting `construction`/`finishing` are absent, the 5 new codes (`drzwi`, `listwy_przypodlogowe`, `podloga`, `plytki`, `sanitariat`) are present, and the re-seed is idempotent.

### Requirement 8: UI tests

**User Story:** As a developer, I want component and i18n-parity tests for each new page, so that the UI and translations stay correct.

#### Acceptance Criteria

1. FOR EACH new page, THE System SHALL have a component test for the list (rows with `code`/`name`/`active`, permission-gated controls, empty state).
2. FOR EACH new namespace (`materialTypes.*`, `materialProducers.*`, `materials.*`), THE System SHALL have a key-parity test across `pl.json`/`ru.json`.

### Requirement 9: Parallel delivery

**User Story:** As a delivery lead, I want the four dictionaries built as independent parallel tracks, so that they can progress concurrently without file conflicts.

#### Acceptance Criteria

1. THE tasks plan SHALL schedule MaterialType, MaterialProducer, Material and the MaterialCategory re-seed as independent parallel tracks in a wave-based Task Dependency Graph.
2. EACH of the three new dictionaries SHALL be its own backend vertical + changeset(s) + frontend page + i18n + tests.
3. THE MaterialCategory track SHALL consist ONLY of the re-seed changeset + a seed integration test (no entity/controller/UI work).
