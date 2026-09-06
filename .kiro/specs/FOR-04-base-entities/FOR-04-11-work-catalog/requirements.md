# Requirements — FOR-04-11: Work Catalog (WorkItem) entity

## Introduction

FOR-04-11 adds the `WorkItem` entity — the catalog of work positions — as a
generic full-CRUD managed resource (backend CRUD + Liquibase resource seed +
ABAC) plus a frontend admin CRUD page on the shared DataTable. Unlike the flat
dictionaries (FOR-04-02..10), `WorkItem` holds TWO foreign keys: `workCategory`
(→ WorkCategory, FOR-04-06) and `unit` (→ MeasurementUnit, FOR-04-02). Fields:
`workCategory` (FK), `unit` (FK), `nameRU`, `namePL`, `active`. i18n only on
`name` (PL fallback).

The default WorkItem rows are seeded in FOR-04-12 from the Excel price list; this
spec seeds only the `WORK_CATALOG` resource + grants (no default work items).

Access: ADMIN full CRUD; MANAGER CREATE/READ/UPDATE (no DELETE); FOREMAN/WORKER/
FINANCIER READ; CLIENT none (deny-by-default). Menu entry stays with FOR-04-15;
interim route guard until then.

## Requirements

### Requirement 1 — WorkItem entity & table
1. THE system SHALL map a `WorkItemEntity` extending `BaseEntity` with `@ManyToOne` associations `workCategory` (→ `WorkCategoryEntity`) and `unit` (→ `MeasurementUnitEntity`), plus `nameRU`, `namePL`, `active`.
2. THE `work_items` table SHALL have `work_category_id BIGINT NOT NULL` (FK → `work_categories(id)`), `unit_id BIGINT NOT NULL` (FK → `measurement_units(id)`), `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four audit columns.
3. THE FK columns SHALL declare named foreign-key constraints (`fk_work_items_category`, `fk_work_items_unit`).
4. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).

### Requirement 2 — Full CRUD API with FK fields
1. THE system SHALL expose `WorkItemController` implementing `AdminController` at `/api/work-items`.
2. THE create/update requests SHALL accept the two FKs as flat ids `workCategoryId` and `unitId` (both `@NotNull Long`); `nameRU`/`namePL` non-blank.
3. THE service layer SHALL resolve `workCategoryId`/`unitId` into the JPA associations via `entityManager.getReference(...)` in the ServiceMapper (flat id → association).
4. Localized `name` SHALL resolve to `nameRU` for `ru` and `namePL` otherwise (PL fallback).
5. THE list/read DTOs SHALL expose the FK ids (`workCategoryId`, `unitId`) AND the referenced entities' localized display names (`workCategoryName`, `unitName`) for table rendering.
6. Filtering by `workCategory.id` / `unit.id` SHALL be supported through the existing reference-filter query grammar (automatic via `@ManyToOne` + `SpecificationBuilder` dotted paths).
7. THE `/api/work-items/metadata` endpoint SHALL emit reference descriptors for `workCategory` and `unit` (automatic via `EntityMetadataResolver`), enabling the frontend reference filter/options dropdown.

### Requirement 3 — ABAC resource & grants
1. THE controller SHALL be annotated `@PermissionResource("WORK_CATALOG")`.
2. THE seed SHALL insert the `WORK_CATALOG` resource row (RU/PL name + description).
3. THE seed SHALL grant ADMIN CRUD; MANAGER CREATE/READ/UPDATE (NO DELETE); FOREMAN/WORKER/FINANCIER READ; CLIENT no grant (deny-by-default).
4. ALL seed changesets SHALL be idempotent (`MARK_RAN` + `sqlCheck expectedResult="0"`).
5. THE new changeset file(s) SHALL be registered last in `changelog.xml`, after the current highest (035).

### Requirement 4 — No default data seed
1. THE spec SHALL NOT seed default WorkItem rows; the catalog rows are seeded in FOR-04-12 from the Excel price list.

### Requirement 5 — Backend tests
1. Testcontainers CRUD integration test for `/api/work-items`: create with valid FK ids, list (rows expose FK ids + referenced names), read, update (change FKs + names), delete; i18n RU/PL/fallback on `name`; filter by `workCategory.id`.
2. Liquibase seed integration test: `WORK_CATALOG` resource exists; exact grants ADMIN CRUD / MANAGER CREATE,READ,UPDATE / FOREMAN,WORKER,FINANCIER READ / CLIENT none; re-run idempotency.

### Requirement 6 — Admin CRUD UI
1. `WorkCatalogPage` at route `/catalog/works`, lazy-loaded, shared `DataTable` for `entityKey="work-items"` / `resource="WORK_CATALOG"` with columns `name`, `workCategory` (reference), `unit` (reference), `active`.
2. Server search/sort/filter/pagination; `workCategory`/`unit` columns use the reference filter (options fetched from `/api/work-categories`, `/api/measurement-units`); `active` via localized badge.
3. Create/edit form sheet (`workCategoryId` select, `unitId` select, `nameRU`, `namePL`, `active`) with validation; delete via dialog.
4. Create/edit/delete controls permission-gated via `usePermission` on `WORK_CATALOG` (delete hidden for MANAGER).
5. Fetch adapter uses shared `buildFetchQuery`; mutations invalidate the list + toast.
6. ALL UI text localized under `workCatalog.*` in BOTH `pl.json`/`ru.json` at parity; no raw keys render.
7. UNTIL FOR-04-15, the `/catalog/works` route SHALL be guarded by interim `WORK_CATALOG`/`READ`.

### Requirement 7 — UI tests
1. Component test for the list (rows with `name`/`workCategory`/`unit`/`active`, permission-gated controls, empty state).
2. `workCatalog.*` key sets at parity in `pl.json`/`ru.json`.
