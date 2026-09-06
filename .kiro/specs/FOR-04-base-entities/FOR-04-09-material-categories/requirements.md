# Requirements — FOR-04-09: Material Categories dictionary

## Introduction

FOR-04-09 adds the `MaterialCategory` dictionary as a generic full-CRUD managed
resource (backend CRUD + Liquibase seed + ABAC) plus a frontend admin CRUD page
on the shared DataTable. Same pattern as FOR-04-07 (Delivery Categories): fields
`code`, `nameRU`, `namePL`, `active`; i18n only on `name` (PL fallback).

Access includes FINANCIER: ADMIN full CRUD; MANAGER/FOREMAN/WORKER/FINANCIER
READ; CLIENT none (deny-by-default). Menu entry stays with FOR-04-15; interim
route guard until then.

## Requirements

### Requirement 1 — MaterialCategory entity & table
1. THE system SHALL map a `MaterialCategoryEntity` extending `BaseEntity` with `code`, `nameRU`, `namePL`, `active`.
2. THE `material_categories` table SHALL have `code` `NOT NULL UNIQUE`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).

### Requirement 2 — Full CRUD API
1. THE system SHALL expose `MaterialCategoryController` implementing `AdminController` at `/api/material-categories`.
2. Localized `name` SHALL resolve to `nameRU` for `ru` and `namePL` otherwise (PL fallback).
3. Sorting/filtering by `name` SHALL resolve to the locale column.
4. THE update request SHALL NOT change `code` (immutable); `nameRU`, `namePL`, `active` updatable.
5. THE create request SHALL validate `code`, `nameRU`, `namePL` as non-blank.

### Requirement 3 — ABAC resource & grants
1. THE controller SHALL be annotated `@PermissionResource("MATERIAL_CATEGORIES")`.
2. THE seed SHALL insert the `MATERIAL_CATEGORIES` resource row (RU/PL name + description).
3. THE seed SHALL grant ADMIN CRUD and MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT SHALL receive no grant (deny-by-default).
4. ALL seed changesets SHALL be idempotent (`MARK_RAN` + `sqlCheck expectedResult="0"`).
5. THE new changeset file(s) SHALL be registered last in `changelog.xml`, after the current highest (031).

### Requirement 4 — Seed default material categories
1. THE seed SHALL insert two material categories with `nameRU`/`namePL` and `active = true`: Construction, Finishing.
2. THE seed SHALL be idempotent.

### Requirement 5 — Backend tests
1. Testcontainers CRUD integration test for `/api/material-categories` (create/list/read/update/delete, i18n, code immutability).
2. Liquibase seed integration test: `MATERIAL_CATEGORIES` resource; exact grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; the two default codes seeded; re-run idempotency.

### Requirement 6 — Admin CRUD UI
1. `MaterialCategoriesPage` at route `/material-categories`, lazy-loaded, shared `DataTable` for `entityKey="material-categories"` / `resource="MATERIAL_CATEGORIES"` with columns `code`, `name`, `active`.
2. Server search/sort/filter/pagination; `active` via localized badge.
3. Create/edit form sheet (`code`, `nameRU`, `namePL`, `active`) with validation; `code` read-only on edit; delete via dialog.
4. Create/edit/delete controls permission-gated via `usePermission` on `MATERIAL_CATEGORIES`.
5. Fetch adapter uses shared `buildFetchQuery`; mutations invalidate the list + toast.
6. ALL UI text localized under `materialCategories.*` in BOTH `pl.json`/`ru.json` at parity; no raw keys render.
7. UNTIL FOR-04-15, the `/material-categories` route SHALL be guarded by interim `MATERIAL_CATEGORIES`/`READ`.

### Requirement 7 — UI tests
1. Component test for the list (rows with `code`/`name`/`active`, permission-gated controls, empty state).
2. `materialCategories.*` key sets at parity in `pl.json`/`ru.json`.
