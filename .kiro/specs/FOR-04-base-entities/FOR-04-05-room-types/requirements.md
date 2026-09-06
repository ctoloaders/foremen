# Requirements — FOR-04-05: Room Types dictionary

## Introduction

FOR-04-05 adds the `RoomType` dictionary as a generic full-CRUD managed resource
(backend CRUD + Liquibase seed + ABAC) plus a frontend admin CRUD page on the
shared DataTable. Same pattern as FOR-04-02 (Measurement Units): fields `code`,
`nameRU`, `namePL`, `active`; i18n only on `name` (PL fallback).

Access differs from the other dictionaries: ADMIN full CRUD; MANAGER/FOREMAN/WORKER
READ; FINANCIER and CLIENT none (deny-by-default). Menu entry stays with FOR-04-15;
interim route guard until then.

## Requirements

### Requirement 1 — RoomType entity & table
1. THE system SHALL map a `RoomTypeEntity` extending `BaseEntity` with `code`, `nameRU`, `namePL`, `active`.
2. THE `room_types` table SHALL have `code` `NOT NULL UNIQUE`, `name_ru`/`name_pl` `NOT NULL`, `active` `NOT NULL` default `true`, plus the four audit columns.
3. THE table-creation changeset SHALL be idempotent (`tableExists` + `MARK_RAN`).

### Requirement 2 — Full CRUD API
1. THE system SHALL expose `RoomTypeController` implementing `AdminController` at `/api/room-types`.
2. Localized `name` SHALL resolve to `nameRU` for `ru` and `namePL` otherwise (PL fallback).
3. Sorting/filtering by `name` SHALL resolve to the locale column.
4. THE update request SHALL NOT change `code` (immutable); `nameRU`, `namePL`, `active` updatable.
5. THE create request SHALL validate `code`, `nameRU`, `namePL` as non-blank.

### Requirement 3 — ABAC resource & grants
1. THE controller SHALL be annotated `@PermissionResource("ROOM_TYPES")`.
2. THE seed SHALL insert the `ROOM_TYPES` resource row (RU/PL name + description).
3. THE seed SHALL grant ADMIN CRUD and MANAGER/FOREMAN/WORKER READ; FINANCIER and CLIENT SHALL receive no grant (deny-by-default).
4. ALL seed changesets SHALL be idempotent (`MARK_RAN` + `sqlCheck expectedResult="0"`).
5. THE new changeset file(s) SHALL be registered last in `changelog.xml`, after the current highest (023).

### Requirement 4 — Seed default room types
1. THE seed SHALL insert room types with codes `przedpokoj`, `hol`, `kuchnia`, `salon`, `biuro`, `master`, `pokoj`, `lazienka`, each with `nameRU`/`namePL` and `active = true`.
2. THE seed SHALL be idempotent.

### Requirement 5 — Backend tests
1. Testcontainers CRUD integration test for `/api/room-types` (create/list/read/update/delete, i18n, code immutability).
2. Liquibase seed integration test: `ROOM_TYPES` resource; exact grants ADMIN CRUD / MANAGER,FOREMAN,WORKER READ / FINANCIER,CLIENT none; the eight default codes seeded; re-run idempotency.

### Requirement 6 — Admin CRUD UI
1. `RoomTypesPage` at route `/room-types`, lazy-loaded, shared `DataTable` for `entityKey="room-types"` / `resource="ROOM_TYPES"` with columns `code`, `name`, `active`.
2. Server search/sort/filter/pagination; `active` via localized badge.
3. Create/edit form sheet (`code`, `nameRU`, `namePL`, `active`) with validation; `code` read-only on edit; delete via dialog.
4. Create/edit/delete controls permission-gated via `usePermission` on `ROOM_TYPES`.
5. Fetch adapter uses shared `buildFetchQuery`; mutations invalidate the list + toast.
6. ALL UI text localized under `roomTypes.*` in BOTH `pl.json`/`ru.json` at parity; no raw keys render.
7. UNTIL FOR-04-15, the `/room-types` route SHALL be guarded by interim `ROOM_TYPES`/`READ`.

### Requirement 7 — UI tests
1. Component test for the list (rows with `code`/`name`/`active`, permission-gated controls, empty state).
2. `roomTypes.*` key sets at parity in `pl.json`/`ru.json`.
