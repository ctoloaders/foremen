# Requirements — FOR-04-03: Currencies dictionary

## Introduction

FOR-04-03 adds the `Currency` dictionary as a generic full-CRUD managed resource:
a backend CRUD API seeded via Liquibase and wired into the ABAC matrix, plus a
frontend admin CRUD page built on the shared DataTable. It follows the same
pattern as FOR-04-02 (Measurement Units): the CRUD framework (FOR-01), the
`@PermissionResource`/`@PermissionOperation` guarding scheme (FOR-03-08), the
`entity-creation-rules` standard, and the existing admin page pattern (Roles /
Measurement Units).

The entity has an ISO-4217 `code` (unique, e.g. PLN/EUR/USD), a `symbol`
(non-i18n, e.g. zł/€/$), i18n on `name` (`nameRU`/`namePL`, PL fallback), and an
`active` flag. Access: ADMIN full CRUD; MANAGER/FOREMAN/WORKER/FINANCIER READ;
CLIENT none.

Scope note: the CRUD page + its route are delivered here. The main-menu
"Dictionaries" group entry (navigation grouping) remains with FOR-04-15; an
interim route-level permission guard keeps the page protected until then.

## Requirements

### Requirement 1 — Currency entity & table
**User Story:** As a developer, I want a `Currency` JPA entity and table, so that currencies are persisted with an ISO code, symbol, i18n names, and an active flag.

#### Acceptance Criteria
1. WHEN the app starts THEN the system SHALL map a `CurrencyEntity` extending `BaseEntity` with fields `code`, `symbol`, `nameRU`, `namePL`, and `active`.
2. THE `currencies` table SHALL have `code` as `NOT NULL UNIQUE`, `symbol` as `NOT NULL`, `name_ru`/`name_pl` as `NOT NULL`, `active` as `NOT NULL` defaulting to `true`, plus the four `BaseEntity` audit columns.
3. THE Liquibase table-creation changeset SHALL be idempotent (guarded by a `tableExists` precondition with `onFail="MARK_RAN"`).

### Requirement 2 — Full CRUD API
**User Story:** As an admin, I want CRUD endpoints for currencies, so that I can manage them through the generic admin framework.

#### Acceptance Criteria
1. THE system SHALL expose `CurrencyController` implementing `AdminController` at base path `/api/currencies` with the generic create/read/update/delete/list/count/metadata/i18n endpoints.
2. WHEN listing or reading a currency THEN the localized `name` SHALL resolve to `nameRU` for `Accept-Language: ru` and `namePL` otherwise (PL fallback), via the existing i18n mapping.
3. WHEN sorting or filtering by `name` THEN the system SHALL resolve to the locale column (`nameRU`/`namePL`) via the existing i18n sort/filter resolution.
4. THE update request SHALL NOT change `code` (code is immutable after creation); `symbol`, `nameRU`, `namePL`, `active` SHALL be updatable.
5. THE create request SHALL validate `code`, `symbol`, `nameRU`, `namePL` as non-blank.

### Requirement 3 — ABAC resource & grants
**User Story:** As a security owner, I want `CURRENCIES` in the ABAC matrix, so that access follows the standard policy and the endpoints are guarded.

#### Acceptance Criteria
1. THE controller SHALL be annotated `@PermissionResource("CURRENCIES")`, combining with the inherited `@PermissionOperation` on each CRUD method (startup annotation validation passes).
2. THE Liquibase seed SHALL insert the `CURRENCIES` resource row (with RU/PL name + description).
3. THE seed SHALL grant ADMIN `CREATE,READ,UPDATE,DELETE` and MANAGER, FOREMAN, WORKER, FINANCIER `READ`; CLIENT SHALL receive no grant (deny-by-default).
4. ALL seed changesets SHALL be idempotent (`onFail="MARK_RAN"` + `sqlCheck expectedResult="0"`) so re-running the changelog inserts nothing.
5. THE new changeset file(s) SHALL be registered last in `changelog.xml`, numbered after the current highest (019).

### Requirement 4 — Seed default currencies
**User Story:** As an admin, I want the standard currencies pre-populated, so that estimates and prices can reference them immediately.

#### Acceptance Criteria
1. THE seed SHALL insert currencies with ISO-4217 codes `PLN`, `EUR`, `USD`, each with a `symbol` (`zł`, `€`, `$`), `nameRU`/`namePL`, and `active = true`.
2. THE currency seed SHALL be idempotent (guarded so re-running inserts no duplicates).

### Requirement 5 — Backend tests
**User Story:** As a developer, I want tests proving CRUD, i18n, and the seed/grants, so that the resource is verified.

#### Acceptance Criteria
1. THE system SHALL have a Testcontainers CRUD integration test for `/api/currencies` (create/list/read/update/delete, i18n name resolution, code immutability on update, symbol update).
2. THE system SHALL have a Liquibase seed integration test asserting the `CURRENCIES` resource exists, the per-role grants are exactly ADMIN=CRUD / MANAGER,FOREMAN,WORKER,FINANCIER=READ / CLIENT=none, the default currency codes are seeded, and re-running the changelog is idempotent.

### Requirement 6 — Admin CRUD UI
**User Story:** As an admin, I want a Currencies page in the admin panel, so that I can list, search, sort, filter, create, edit, and deactivate currencies through the UI.

#### Acceptance Criteria
1. THE frontend SHALL provide a `CurrenciesPage` at route `/currencies`, lazy-loaded and reachable, rendering the shared `DataTable` for `entityKey="currencies"` / `resource="CURRENCIES"` with columns `code`, `symbol`, `name`, `active`.
2. THE list SHALL support the shared DataTable behaviors (server search, sort, filter, pagination) and render `active` via a localized badge; `name`, `code`, `symbol` SHALL be searchable/sortable as appropriate.
3. THE page SHALL provide create and edit via a form sheet (fields `code`, `symbol`, `nameRU`, `namePL`, `active`) with client-side validation; on edit, `code` SHALL be shown read-only (immutable). Delete SHALL be confirmed via a dialog.
4. THE create/edit/delete controls SHALL be permission-gated via `usePermission` on `CURRENCIES` (`CREATE`/`UPDATE`/`DELETE`); a user with only READ SHALL see the list without action controls.
5. THE fetch adapter SHALL use the shared `buildFetchQuery` serializer so `page`/`size`/`query`/`sort` are sent consistently; mutations SHALL invalidate the list query on success and surface success/error via toasts.
6. ALL user-facing UI text (page title, table headers, form labels, buttons, placeholders, badges, toasts, validation messages) SHALL be localized under a `currencies.*` i18n namespace present in BOTH `pl.json` and `ru.json` at parity; no raw i18n keys SHALL render.
7. UNTIL the FOR-04-15 menu entry lands, the `/currencies` route SHALL be guarded by an interim `CURRENCIES`/`READ` route-permission requirement.

### Requirement 7 — UI tests
**User Story:** As a developer, I want tests for the currencies page, so that its rendering, permission gating, and API wiring are verified.

#### Acceptance Criteria
1. THE system SHALL have a component test for the currencies list (renders rows with `code`/`symbol`/`name`/`active` badge, permission-gated create/edit/delete controls, empty state) mirroring the Measurement Units list test harness.
2. THE i18n `currencies.*` key sets in `pl.json` and `ru.json` SHALL be at parity (no key present in one file but missing in the other).
