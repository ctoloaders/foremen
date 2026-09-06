# Requirements — FOR-04-10: Offer Packages dictionary

## Introduction

FOR-04-10 adds the `OfferPackage` dictionary as a generic full-CRUD managed
resource (backend CRUD + Liquibase seed + ABAC) plus a frontend admin CRUD page
on the shared DataTable. Same pattern as FOR-04-08 (Delivery Statuses): fields
`code`, `orderNo` (Integer), `nameRU`, `namePL`, `active`; i18n only on `name`
(PL fallback).

Access includes FINANCIER: ADMIN full CRUD; MANAGER/FOREMAN/WORKER/FINANCIER
READ; CLIENT none (deny-by-default). Menu entry stays with FOR-04-15; interim
route guard until then.

## Requirements

### Requirement 1 — OfferPackage entity & table
1. THE system SHALL map an `OfferPackageEntity` extending `BaseEntity` with `code`, `orderNo`, `nameRU`, `namePL`, `active`.
2. THE `offer_packages` table SHALL have `code` `NOT NULL UNIQUE`, `order_no` `INT NOT NULL`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).

### Requirement 2 — Full CRUD API
1. THE system SHALL expose `OfferPackageController` implementing `AdminController` at `/api/offer-packages`.
2. Localized `name` SHALL resolve to `nameRU` for `ru` and `namePL` otherwise (PL fallback).
3. Sorting/filtering by `name` SHALL resolve to the locale column.
4. THE update request SHALL NOT change `code` (immutable); `orderNo`, `nameRU`, `namePL`, `active` updatable.
5. THE create request SHALL validate `code`, `nameRU`, `namePL` as non-blank and `orderNo` as non-null.

### Requirement 3 — ABAC resource & grants
1. THE controller SHALL be annotated `@PermissionResource("OFFER_PACKAGES")`.
2. THE seed SHALL insert the `OFFER_PACKAGES` resource row (RU/PL name + description).
3. THE seed SHALL grant ADMIN CRUD and MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT SHALL receive no grant (deny-by-default).
4. ALL seed changesets SHALL be idempotent (`MARK_RAN` + `sqlCheck expectedResult="0"`).
5. THE new changeset file(s) SHALL be registered last in `changelog.xml`, after the current highest (033).

### Requirement 4 — Seed default offer packages
1. THE seed SHALL insert three offer packages with `orderNo` 1..3, `nameRU`/`namePL`, `active = true`: Budget/START, Norm/COMFORT, Lux/PRESTIGE.
2. THE seed SHALL be idempotent.

### Requirement 5 — Backend tests
1. Testcontainers CRUD integration test for `/api/offer-packages` (create/list/read/update/delete, i18n, code immutability, orderNo update).
2. Liquibase seed integration test: `OFFER_PACKAGES` resource; exact grants ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none; the three default codes seeded with orderNo 1..3; re-run idempotency.

### Requirement 6 — Admin CRUD UI
1. `OfferPackagesPage` at route `/offer-packages`, lazy-loaded, shared `DataTable` for `entityKey="offer-packages"` / `resource="OFFER_PACKAGES"` with columns `orderNo`, `code`, `name`, `active`, default sort by `orderNo` asc.
2. Server search/sort/filter/pagination; `active` via localized badge.
3. Create/edit form sheet (`code`, `orderNo`, `nameRU`, `namePL`, `active`) with validation; `code` read-only on edit; delete via dialog.
4. Create/edit/delete controls permission-gated via `usePermission` on `OFFER_PACKAGES`.
5. Fetch adapter uses shared `buildFetchQuery`; mutations invalidate the list + toast.
6. ALL UI text localized under `offerPackages.*` in BOTH `pl.json`/`ru.json` at parity; no raw keys render.
7. UNTIL FOR-04-15, the `/offer-packages` route SHALL be guarded by interim `OFFER_PACKAGES`/`READ`.

### Requirement 7 — UI tests
1. Component test for the list (rows with `orderNo`/`code`/`name`/`active`, permission-gated controls, empty state).
2. `offerPackages.*` key sets at parity in `pl.json`/`ru.json`.
