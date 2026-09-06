# Requirements — FOR-04-04: VAT Rates dictionary

## Introduction

FOR-04-04 adds the `VatRate` dictionary as a generic full-CRUD managed resource
(backend CRUD API + Liquibase seed + ABAC) plus a frontend admin CRUD page on the
shared DataTable. Same pattern as FOR-04-02 / FOR-04-03, with two extra non-i18n
fields: `rate` (numeric percent) and `isDefault` (boolean marking the default
rate). i18n only on `name` (`nameRU`/`namePL`, PL fallback).

Access: ADMIN full CRUD; MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT none.

Scope note: the CRUD page + route are delivered here; the main-menu "Dictionaries"
group entry stays with FOR-04-15, with an interim route guard until then.

## Requirements

### Requirement 1 — VatRate entity & table
1. WHEN the app starts THEN the system SHALL map a `VatRateEntity` extending `BaseEntity` with fields `code`, `rate` (BigDecimal), `nameRU`, `namePL`, `isDefault` (boolean), `active` (boolean).
2. THE `vat_rates` table SHALL have `code` `NOT NULL UNIQUE`, `rate` `NUMERIC NOT NULL`, `name_ru`/`name_pl` `NOT NULL`, `is_default` `NOT NULL` default `false`, `active` `NOT NULL` default `true`, plus the four `BaseEntity` audit columns.
3. THE Liquibase table-creation changeset SHALL be idempotent (`tableExists` + `onFail="MARK_RAN"`).

### Requirement 2 — Full CRUD API
1. THE system SHALL expose `VatRateController` implementing `AdminController` at `/api/vat-rates` with the generic CRUD/list/count/metadata/i18n endpoints.
2. WHEN listing/reading THEN the localized `name` SHALL resolve to `nameRU` for `Accept-Language: ru` and `namePL` otherwise (PL fallback).
3. WHEN sorting/filtering by `name` THEN the system SHALL resolve to the locale column via the existing i18n resolution.
4. THE update request SHALL NOT change `code` (immutable); `rate`, `nameRU`, `namePL`, `isDefault`, `active` SHALL be updatable.
5. THE create request SHALL validate `code`, `nameRU`, `namePL` as non-blank and `rate` as non-null.

### Requirement 3 — ABAC resource & grants
1. THE controller SHALL be annotated `@PermissionResource("VAT_RATES")` (startup annotation validation passes).
2. THE Liquibase seed SHALL insert the `VAT_RATES` resource row (RU/PL name + description).
3. THE seed SHALL grant ADMIN CRUD and MANAGER/FOREMAN/WORKER/FINANCIER READ; CLIENT none (deny-by-default).
4. ALL seed changesets SHALL be idempotent (`onFail="MARK_RAN"` + `sqlCheck expectedResult="0"`).
5. THE new changeset file(s) SHALL be registered last in `changelog.xml`, numbered after the current highest (021).

### Requirement 4 — Seed default VAT rates
1. THE seed SHALL insert rates `23%`, `8%`, `5%`, `0%` (with `rate` values 23, 8, 5, 0), each with `nameRU`/`namePL` and `active = true`; the `23%` row SHALL have `is_default = true` and the others `false`.
2. THE seed SHALL be idempotent (guarded so re-running inserts no duplicates).

### Requirement 5 — Backend tests
1. THE system SHALL have a Testcontainers CRUD integration test for `/api/vat-rates` (create/list/read/update/delete, i18n name, code immutability, rate/isDefault update).
2. THE system SHALL have a Liquibase seed integration test asserting the `VAT_RATES` resource exists, exact per-role grants (ADMIN CRUD / MANAGER,FOREMAN,WORKER,FINANCIER READ / CLIENT none), the four default rates seeded (with the 23% default flag), and re-run idempotency.

### Requirement 6 — Admin CRUD UI
1. THE frontend SHALL provide a `VatRatesPage` at route `/vat-rates`, lazy-loaded, rendering the shared `DataTable` for `entityKey="vat-rates"` / `resource="VAT_RATES"` with columns `code`, `rate`, `name`, `isDefault`, `active`.
2. THE list SHALL support server search/sort/filter/pagination; `active` and `isDefault` SHALL render via localized badges.
3. THE page SHALL provide create/edit via a form sheet (fields `code`, `rate`, `nameRU`, `namePL`, `isDefault`, `active`) with client-side validation; on edit `code` SHALL be read-only. Delete SHALL be confirmed via a dialog.
4. THE create/edit/delete controls SHALL be permission-gated via `usePermission` on `VAT_RATES`.
5. THE fetch adapter SHALL use the shared `buildFetchQuery`; mutations SHALL invalidate the list query and toast success/error.
6. ALL user-facing UI text SHALL be localized under a `vatRates.*` i18n namespace present in BOTH `pl.json` and `ru.json` at parity; no raw keys render.
7. UNTIL the FOR-04-15 menu entry lands, the `/vat-rates` route SHALL be guarded by an interim `VAT_RATES`/`READ` route-permission requirement.

### Requirement 7 — UI tests
1. THE system SHALL have a component test for the list (rows with `code`/`rate`/`name`/`isDefault`/`active`, permission-gated controls, empty state).
2. THE `vatRates.*` key sets in `pl.json`/`ru.json` SHALL be at parity.
