# Requirements — FOR-04-06: Work Categories dictionary

## Introduction

FOR-04-06 adds the `WorkCategory` dictionary (backend CRUD + Liquibase seed + ABAC
+ frontend admin CRUD page). Same pattern as FOR-04-02, with one extra non-i18n
field `orderNo` (integer ordering). i18n only on `name` (PL fallback). The seed of
13 work categories (with Polish names as the source of truth) underpins the work
catalog (FOR-04-11) and estimate.

Access: ADMIN full CRUD; MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT none. Menu
entry stays with FOR-04-15; interim route guard until then.

## Requirements

### Requirement 1 — WorkCategory entity & table
1. THE system SHALL map a `WorkCategoryEntity` extending `BaseEntity` with `code`, `orderNo` (Integer), `nameRU`, `namePL`, `active`.
2. THE `work_categories` table SHALL have `code` `NOT NULL UNIQUE`, `order_no` `INT NOT NULL`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).

### Requirement 2 — Full CRUD API
1. THE system SHALL expose `WorkCategoryController` implementing `AdminController` at `/api/work-categories`.
2. Localized `name` SHALL resolve per `Accept-Language` (ru→nameRU, else namePL).
3. Sorting/filtering by `name` SHALL resolve to the locale column.
4. THE update request SHALL NOT change `code`; `orderNo`, `nameRU`, `namePL`, `active` updatable.
5. THE create request SHALL validate `code`, `nameRU`, `namePL` non-blank and `orderNo` non-null.

### Requirement 3 — ABAC resource & grants
1. `@PermissionResource("WORK_CATEGORIES")`.
2. Seed the `WORK_CATEGORIES` resource row (RU/PL name + description).
3. Grant ADMIN CRUD and MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT none.
4. All seed changesets idempotent (`MARK_RAN` + `sqlCheck expectedResult="0"`).
5. Register the changeset file(s) last in `changelog.xml`, after the current highest (025).

### Requirement 4 — Seed default work categories
1. THE seed SHALL insert the 13 categories with `orderNo` 1..13, `code`, `nameRU`, `namePL`, `active=true`:
   1 PRELIMINARY (PL "PRACE WSTĘPNE, DEMONTAŻE" / RU "Подготовительные работы, демонтажи");
   2 CONSTRUCTIONS_GK ("KONSTRUKCJE I GK" / "Конструкции и ГК");
   3 PLUMBING_ROUGH ("HYDRAULIKA STAN SUROWY" / "Сантехника черновая");
   4 PLUMBING_FINISH ("HYDRAULIKA BIAŁY MONTAŻ" / "Сантехника чистовая");
   5 ELECTRICAL_ROUGH ("ELEKTRYKA STAN SUROWY" / "Электрика черновая");
   6 ELECTRICAL_FINISH ("ELEKTRYKA BIAŁY MONTAŻ" / "Электрика чистовая");
   7 TILING ("PRACE GLAZURNICZE" / "Плиточные работы");
   8 PLASTERING ("GŁADZIE, PRZYGOTOWANIE PODŁOŻA" / "Шпаклёвка, подготовка основания");
   9 PAINTING_DECOR ("PRACE MALARSKIE, SZTUKATERIA, DEKOR" / "Малярные работы, лепнина, декор");
   10 FLOORS ("POSADZKI" / "Полы");
   11 CARPENTRY ("STOLARKA" / "Столярка");
   12 EXTRAS ("DODATKI" / "Дополнения");
   13 OTHER ("INNE / KOORDYNACJA / NIESTANDARDOWE" / "Прочее / координация / нестандартное").
2. THE seed SHALL be idempotent.

### Requirement 5 — Backend tests
1. Testcontainers CRUD integration test for `/api/work-categories` (create/list/read/update/delete, i18n, code immutability, orderNo update).
2. Liquibase seed integration test: `WORK_CATEGORIES` resource; exact grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; the 13 default codes seeded with orderNo 1..13; idempotency.

### Requirement 6 — Admin CRUD UI
1. `WorkCategoriesPage` at route `/work-categories`, lazy-loaded, shared `DataTable` for `entityKey="work-categories"` / `resource="WORK_CATEGORIES"` with columns `orderNo`, `code`, `name`, `active`.
2. Server search/sort/filter/pagination; default sort by `orderNo` asc; `active` via localized badge.
3. Create/edit form sheet (`code`, `orderNo`, `nameRU`, `namePL`, `active`) with validation; `code` read-only on edit; delete via dialog.
4. Controls permission-gated via `usePermission` on `WORK_CATEGORIES`.
5. Fetch adapter uses shared `buildFetchQuery`; mutations invalidate the list + toast.
6. ALL UI text localized under `workCategories.*` in BOTH `pl.json`/`ru.json` at parity; no raw keys render.
7. UNTIL FOR-04-15, the `/work-categories` route SHALL be guarded by interim `WORK_CATEGORIES`/`READ`.

### Requirement 7 — UI tests
1. Component test for the list (rows with `orderNo`/`code`/`name`/`active`, permission-gated controls, empty state).
2. `workCategories.*` key sets at parity in `pl.json`/`ru.json`.
