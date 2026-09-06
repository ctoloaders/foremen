# Requirements — FOR-04-08: Delivery Statuses dictionary

## Introduction

FOR-04-08 adds the `DeliveryStatus` dictionary as a generic full-CRUD managed
resource (backend CRUD + Liquibase seed + ABAC) plus a frontend admin CRUD page
on the shared DataTable. Same pattern as FOR-04-06 (Work Categories): fields
`code`, `orderNo` (Integer), `nameRU`, `namePL`, `active`; i18n only on `name`
(PL fallback).

Access matches DELIVERY_CATEGORIES: ADMIN full CRUD; MANAGER/FOREMAN/WORKER READ;
FINANCIER and CLIENT none (deny-by-default). Menu entry stays with FOR-04-15;
interim route guard until then.

## Requirements

### Requirement 1 — DeliveryStatus entity & table
1. THE system SHALL map a `DeliveryStatusEntity` extending `BaseEntity` with `code`, `orderNo`, `nameRU`, `namePL`, `active`.
2. THE `delivery_statuses` table SHALL have `code` `NOT NULL UNIQUE`, `order_no` `INT NOT NULL`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).

### Requirement 2 — Full CRUD API
1. THE system SHALL expose `DeliveryStatusController` implementing `AdminController` at `/api/delivery-statuses`.
2. Localized `name` SHALL resolve to `nameRU` for `ru` and `namePL` otherwise (PL fallback).
3. Sorting/filtering by `name` SHALL resolve to the locale column.
4. THE update request SHALL NOT change `code` (immutable); `orderNo`, `nameRU`, `namePL`, `active` updatable.
5. THE create request SHALL validate `code`, `nameRU`, `namePL` as non-blank and `orderNo` as non-null.

### Requirement 3 — ABAC resource & grants
1. THE controller SHALL be annotated `@PermissionResource("DELIVERY_STATUSES")`.
2. THE seed SHALL insert the `DELIVERY_STATUSES` resource row (RU/PL name + description).
3. THE seed SHALL grant ADMIN CRUD and MANAGER/FOREMAN/WORKER READ; FINANCIER and CLIENT SHALL receive no grant (deny-by-default).
4. ALL seed changesets SHALL be idempotent (`MARK_RAN` + `sqlCheck expectedResult="0"`).
5. THE new changeset file(s) SHALL be registered last in `changelog.xml`, after the current highest (029).

### Requirement 4 — Seed default delivery statuses
1. THE seed SHALL insert four delivery statuses with `orderNo` 1..4, `nameRU`/`namePL`, `active = true`: Nowe, Zamówione, Dostarczone, Anulowane.
2. THE seed SHALL be idempotent.

### Requirement 5 — Backend tests
1. Testcontainers CRUD integration test for `/api/delivery-statuses` (create/list/read/update/delete, i18n, code immutability, orderNo update).
2. Liquibase seed integration test: `DELIVERY_STATUSES` resource; exact grants ADMIN CRUD / MANAGER,FOREMAN,WORKER READ / FINANCIER,CLIENT none; the four default codes seeded with orderNo 1..4; re-run idempotency.

### Requirement 6 — Admin CRUD UI
1. `DeliveryStatusesPage` at route `/delivery-statuses`, lazy-loaded, shared `DataTable` for `entityKey="delivery-statuses"` / `resource="DELIVERY_STATUSES"` with columns `orderNo`, `code`, `name`, `active`, default sort by `orderNo` asc.
2. Server search/sort/filter/pagination; `active` via localized badge.
3. Create/edit form sheet (`code`, `orderNo`, `nameRU`, `namePL`, `active`) with validation; `code` read-only on edit; delete via dialog.
4. Create/edit/delete controls permission-gated via `usePermission` on `DELIVERY_STATUSES`.
5. Fetch adapter uses shared `buildFetchQuery`; mutations invalidate the list + toast.
6. ALL UI text localized under `deliveryStatuses.*` in BOTH `pl.json`/`ru.json` at parity; no raw keys render.
7. UNTIL FOR-04-15, the `/delivery-statuses` route SHALL be guarded by interim `DELIVERY_STATUSES`/`READ`.

### Requirement 7 — UI tests
1. Component test for the list (rows with `orderNo`/`code`/`name`/`active`, permission-gated controls, empty state).
2. `deliveryStatuses.*` key sets at parity in `pl.json`/`ru.json`.
