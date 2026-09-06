# Requirements — FOR-04-12: Work Prices (WorkPrice) entity + Excel price-list seed

## Introduction

FOR-04-12 adds the `WorkPrice` entity — the price history for catalog work items —
as a generic full-CRUD managed resource (backend CRUD + Liquibase seed + ABAC)
plus a frontend admin CRUD page on the shared DataTable. `WorkPrice` holds TWO
foreign keys: `workItem` (→ WorkItem, FOR-04-11) and `currency` (→ Currency,
FOR-04-03), plus `netPrice` (BigDecimal), `validFrom` (date) and `validTo` (date,
`null` = current price). Price history is preserved: the current price is the row
with `validTo IS NULL`.

The seed parses the existing Excel price list
(`docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`). Because FOR-04-11 seeded NO
default WorkItem rows, this spec seeds BOTH the WorkItem catalog rows AND their
current WorkPrice rows (PLN, `validFrom` = seed date, `validTo = null`).

Access: ADMIN full CRUD; MANAGER CREATE/READ/UPDATE (no DELETE); FOREMAN/WORKER/
FINANCIER READ; CLIENT none (deny-by-default) — same as WORK_CATALOG. Menu entry
stays with FOR-04-15; interim route guard until then.

## Requirements

### Requirement 1 — WorkPrice entity & table
1. THE system SHALL map a `WorkPriceEntity` extending `BaseEntity` with `@ManyToOne` associations `workItem` (→ `WorkItemEntity`) and `currency` (→ `CurrencyEntity`), plus `netPrice` (BigDecimal), `validFrom` (LocalDate), `validTo` (LocalDate, nullable).
2. THE `work_prices` table SHALL have `work_item_id BIGINT NOT NULL` (FK → `work_items(id)`, `fk_work_prices_work_item`), `currency_id BIGINT NOT NULL` (FK → `currencies(id)`, `fk_work_prices_currency`), `net_price NUMERIC(12,2) NOT NULL`, `valid_from DATE NOT NULL`, `valid_to DATE NULL`, plus the four audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).

### Requirement 2 — Full CRUD API with FK fields & price semantics
1. THE system SHALL expose `WorkPriceController` implementing `AdminController` at `/api/work-prices`.
2. THE create/update requests SHALL accept the two FKs as flat ids `workItemId` and `currencyId` (both `@NotNull Long`), `netPrice` (`@NotNull` positive BigDecimal), `validFrom` (`@NotNull` date), `validTo` (nullable date).
3. THE service layer SHALL resolve `workItemId`/`currencyId` into JPA associations via `entityManager.getReference(...)` in the ServiceMapper.
4. THE list/read DTOs SHALL expose the FK ids (`workItemId`, `currencyId`) AND the referenced display values (`workItemName` localized, `currencyCode`), plus `netPrice`, `validFrom`, `validTo`, and a derived `current` boolean (`validTo == null`).
5. Filtering by `workItem.id` / `currency.id` SHALL be supported through the reference-filter query grammar (automatic via `@ManyToOne` + `SpecificationBuilder`).
6. THE `/api/work-prices/metadata` endpoint SHALL emit reference descriptors for `workItem` and `currency` (automatic via `EntityMetadataResolver`).

### Requirement 3 — ABAC resource & grants
1. THE controller SHALL be annotated `@PermissionResource("WORK_PRICES")`.
2. THE seed SHALL insert the `WORK_PRICES` resource row (RU/PL name + description).
3. THE seed SHALL grant ADMIN CRUD; MANAGER CREATE/READ/UPDATE (NO DELETE); FOREMAN/WORKER/FINANCIER READ; CLIENT no grant (deny-by-default).
4. ALL seed changesets SHALL be idempotent (`MARK_RAN` + `sqlCheck expectedResult="0"`).
5. THE new changeset file(s) SHALL be registered last in `changelog.xml`, after the current highest (037).

### Requirement 4 — Seed WorkItems + current WorkPrices from the Excel price list
1. THE seed SHALL parse `docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`.
2. Category-header rows (integer `LP`, uppercase `ZAKRES`) SHALL map to the already-seeded `WorkCategory` (by `name_pl`); item rows (`"N,MM"` `LP`) SHALL become `WorkItem` rows (`namePL = ZAKRES`, `nameRU = ПЕРЕЧЕНЬ РАБОТ`, `unit = JM` code, `workCategory` = the current section's category, `active = true`).
3. FOR each seeded WorkItem with a parseable positive `CENA`, THE seed SHALL insert a current `WorkPrice` (`currency = PLN`, `net_price = CENA`, `valid_from = <seed date>`, `valid_to = NULL`).
4. Rows without a real `ZAKRES`/`JM`/parseable positive `CENA` (headers, blanks, zero/empty price) SHALL be skipped; skipped rows SHALL be documented.
5. WorkItem inserts SHALL resolve `work_category_id` via `SELECT id FROM work_categories WHERE code=...` and `unit_id` via `SELECT id FROM measurement_units WHERE code=...`; WorkPrice inserts SHALL resolve `work_item_id` by matching `name_pl` AND `work_category_id`, and `currency_id` via `SELECT id FROM currencies WHERE code='PLN'`.
6. THE seed SHALL be idempotent (`sqlCheck expectedResult="0"` on `work_items` / `work_prices`).

### Requirement 5 — Backend tests
1. Testcontainers CRUD integration test for `/api/work-prices`: create with valid FK ids + price + dates, list (rows expose FK ids + referenced values + `current`), read, update, delete; filter by `workItem.id`; `current` derives from `validTo == null`.
2. Liquibase seed integration test: `WORK_PRICES` resource; grants ADMIN CRUD / MANAGER CREATE,READ,UPDATE / FOREMAN,WORKER,FINANCIER READ / CLIENT none; work_items and work_prices seeded from CSV (COUNT > 0; every current price has `valid_to IS NULL` and `currency = PLN`); re-run idempotency.

### Requirement 6 — Admin CRUD UI
1. `WorkPricesPage` at route `/catalog/prices`, lazy-loaded, shared `DataTable` for `entityKey="work-prices"` / `resource="WORK_PRICES"` with columns `workItem` (reference), `currency` (reference), `netPrice`, `validFrom`, `validTo`, `current` (badge).
2. Server search/sort/filter/pagination; `workItem`/`currency` columns use the reference filter; `current` via localized badge (Current/Historic).
3. Create/edit form sheet (`workItemId` select, `currencyId` select, `netPrice`, `validFrom`, `validTo` optional) with validation; delete via dialog.
4. Create/edit/delete controls permission-gated via `usePermission` on `WORK_PRICES` (delete hidden for MANAGER).
5. Fetch adapter uses shared `buildFetchQuery`; mutations invalidate the list + toast.
6. ALL UI text localized under `workPrices.*` in BOTH `pl.json`/`ru.json` at parity; no raw keys render.
7. UNTIL FOR-04-15, the `/catalog/prices` route SHALL be guarded by interim `WORK_PRICES`/`READ`.

### Requirement 7 — UI tests
1. Component test for the list (rows with `workItem`/`currency`/`netPrice`/`validFrom`/`current`, permission-gated controls, empty state).
2. `workPrices.*` key sets at parity in `pl.json`/`ru.json`.
