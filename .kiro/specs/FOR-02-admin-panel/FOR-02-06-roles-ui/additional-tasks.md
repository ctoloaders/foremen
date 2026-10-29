# Additional tasks: FOR-02-06-roles-ui (seed system role SALESMAN)

Screen URL: http://localhost:3000/roles

Enhancement batch (2026-10-29). Approved by the user. NEW feature task (not a bug fix). Seed a new
built-in system role **SALESMAN** with a grant set derived from MANAGER plus offer/signable-docs
write access.

## Scope (user intent)

Add a system role `SALESMAN` whose grants are:

1. **READ access to all resources MANAGER can read** — derived at seed time via `INSERT ... SELECT`
   joins against MANAGER's actual `role_resources` / `role_resource_operations` rows, filtered to the
   READ operation, so SALESMAN mirrors MANAGER's read surface and stays correct regardless of which
   resources MANAGER holds (mirror the ESTIMATOR seed pattern, changeset 136).
2. **CRU (CREATE + READ + UPDATE, NO DELETE, NO APPROVE)** on:
   - `OFFERS`
   - `SIGNABLE_DOCUMENTS`
   - **NOT** `SIGNABLE_DOCUMENT_TYPES` and **NOT** `DOCUMENT_TEMPLATES` (user decision 4).

Display names (backend entity i18n fields on the role row): `name_ru = "Продавец"`,
`name_pl = "Sprzedawca"`, with `description_ru` / `description_pl` (e.g. "Продажи: оферты и документы
на подпись" / "Sprzedaż: oferty i dokumenty do podpisu").

### Design anchors (grounded in code)

- Role seed pattern: `foremen-backend/database_files/changesets/136-seed-estimator-role.xml` is the
  exact template — changeSet 1 inserts the role (idempotent `sqlCheck` precondition), changeSet 2
  mirrors another role's grants via `INSERT ... SELECT` with `NOT EXISTS` idempotency, changeSet 3
  adds the extra resource grants. Register LAST in `changelog.xml`.
- Resource codes confirmed: `OFFERS` (133), `SIGNABLE_DOCUMENTS` (145), `SIGNABLE_DOCUMENT_TYPES`
  (146), `DOCUMENT_TEMPLATES` (147).
- The MANAGER-read mirror must filter to the READ operation only (unlike ESTIMATOR, which mirrored
  FOREMAN's full operation set). SALESMAN gets MANAGER's READ grants + CRU on the two write resources.
- The `/roles` UI lists roles generically; a newly seeded role appears automatically — no UI change
  is required unless system/immutable-role handling needs the new code added to a protected set
  (verify during implementation and add if such a set exists).

### i18n

- Backend entity i18n fields: the new role row's `name_ru` / `name_pl` / `description_ru` /
  `description_pl` (seeded in the changeset).
- Frontend: the `/roles` screen renders role names from the backend entity fields; no new frontend
  i18n keys expected. Verify no hardcoded role-name mapping exists that needs SALESMAN added.

### Notifications

None. Seeding a role emits no user-facing notification.

## Tasks

- [x] 1. Seed changeset — insert SALESMAN + MANAGER-read mirror + OFFERS/SIGNABLE_DOCUMENTS CRU
  - DONE: `foremen-backend/database_files/changesets/189-seed-salesman-role.xml` (3 changeSets:
    insert role `system=true` with PL/RU names; mirror MANAGER's READ grants; CRU on OFFERS +
    SIGNABLE_DOCUMENTS). Registered last in `changelog.xml`. Verified in DB: SALESMAN has READ on all
    35 MANAGER-read resources, CREATE/READ/UPDATE on OFFERS and SIGNABLE_DOCUMENTS, and NO
    SIGNABLE_DOCUMENT_TYPES / DOCUMENT_TEMPLATES rows.
  - Create `foremen-backend/database_files/changesets/189-seed-salesman-role.xml` (choose the next
    free number at implementation time if 189 is taken) following the ESTIMATOR template:
    - changeSet 1: insert the `SALESMAN` role (idempotent `sqlCheck` on `code = 'SALESMAN'`), with
      `name_ru`/`name_pl`/`description_ru`/`description_pl`.
    - changeSet 2: mirror MANAGER's `role_resources` + `role_resource_operations` filtered to the
      READ operation onto SALESMAN via `INSERT ... SELECT` with `NOT EXISTS` idempotency.
    - changeSet 3: grant SALESMAN CRU (CREATE/READ/UPDATE) on `OFFERS` and on `SIGNABLE_DOCUMENTS`
      (role_resources row + the three operation rows each, `NOT EXISTS`-guarded). No DELETE, no
      APPROVE, no SIGNABLE_DOCUMENT_TYPES, no DOCUMENT_TEMPLATES.
  - Register the file LAST in `foremen-backend/database_files/changelog.xml`.
  - _Requirements: FOR-02-06 (roles management); mirrors FOR-05-07 R16 ESTIMATOR-seed precedent_
  - _Depends on: none_

- [x] 2. Verify roles UI + any protected/system-role set
  - DONE: `/roles` lists SALESMAN as "Sprzedawca" (PL). No hardcoded protected-role set exists —
    `RoleService` enforces immutability via the `roles.system` boolean (delete → 403
    `error.role.system.cannot.delete`; name change → 403 `error.role.system.name.immutable`). Seeding
    SALESMAN with `system=true` makes it non-deletable and name-immutable automatically; no code
    change needed.
  - _Requirements: FOR-02-06_
  - _Depends on: 1_

- [ ] 3. Author test-cases.md
  - Create/append `test-cases.md` per `.kiro/steering/test-cases.md`. API scenarios (Dockerized app):
    SALESMAN role exists with localized names; SALESMAN can READ every resource MANAGER can READ;
    SALESMAN has CREATE/READ/UPDATE but NOT DELETE on OFFERS and SIGNABLE_DOCUMENTS; SALESMAN has NO
    grant on SIGNABLE_DOCUMENT_TYPES / DOCUMENT_TEMPLATES; the seed is idempotent on re-run.
    Repeatability: read-only assertions against the seeded role (no mutation); regression: existing
    MANAGER/ESTIMATOR grants unchanged.
  - _Requirements: the requirements covered (FOR-02-06; grant matrix above)_
  - _Depends on: 1_

- [x] V. Validate in the browser + API
  - PASS (2026-10-29): migration applied on the live Docker stack; `/roles` shows SALESMAN
    ("Sprzedawca"). DB query confirms the full grant matrix: READ on all 35 MANAGER-read resources,
    CRU (CREATE/READ/UPDATE) on OFFERS and SIGNABLE_DOCUMENTS, and no DELETE / no
    SIGNABLE_DOCUMENT_TYPES / no DOCUMENT_TEMPLATES.
  - _Requirements: as task 1_
  - _Depends on: 1, 2_

## Parallel execution plan (waves)

- **Wave 1:** 1 — the seed changeset (single migration file; serialize before all).
- **Wave 2 (parallel):** 2 (UI/protected-set verify), 3 (test-cases.md) — both depend only on 1.
- **Wave 3:** V — validation depends on 1 (+ 2).
