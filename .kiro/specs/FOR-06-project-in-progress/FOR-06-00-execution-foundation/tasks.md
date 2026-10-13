# Implementation Plan: FOR-06-00 — Execution foundation

## Overview

This plan delivers the three foundation parts in dependency order: the backend
`UnplannedExpenseCategory` vertical slice (schema → entity → service → controller → ABAC seed),
then the frontend admin CRUD page, and — independently — the frontend execution-stage
`WORKSPACE_TABS` realignment. It introduces **one** new managed entity
(`UnplannedExpenseCategory`), so it follows `.kiro/steering/entity-creation-rules.md` in full for
that entity; it is global, so step 4 (`ProjectScopedService`) is intentionally skipped.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — changesets `157`–`158`, the entity/service/controller/DTOs/mappers, the ABAC
  seed, and this spec. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `WORKSPACE_TABS` realignment, the
  `unplanned-expense-categories` feature folder, the route + nav entry, and the `pl.json`/`ru.json`
  keys. Commit from inside `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** New changesets begin at `157` (current changelog tail is `156`; if a sibling spec
lands further changesets first, use the next free `NNN`). Each follows the `NOT tableExists` /
`SELECT COUNT(*)` / `onFail="MARK_RAN"` idempotency convention and is appended **last** in
`changelog.xml`.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect
to `/tmp/for-06-00-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks.

## Tasks

- [x] 1. Backend: UnplannedExpenseCategory vertical slice
  - [x] 1.1 Create the table changeset
    - Add `157-create-unplanned-expense-categories.xml` (id BIGSERIAL PK, `code` VARCHAR(100)
      UNIQUE, `name_ru`/`name_pl` VARCHAR(255) NOT NULL, `active` BOOLEAN DEFAULT true, audit cols);
      guard `NOT tableExists` + `MARK_RAN`; register last in `changelog.xml`.
    - _Requirements: 2.1, 2.2_
    - _Depends on: none_
  - [x] 1.2 Add the JPA entity
    - `UnplannedExpenseCategoryEntity extends BaseEntity` with `code`, `nameRU` (`name_ru`),
      `namePL` (`name_pl`), `active`, mirroring `DeliveryCategoryEntity`.
    - _Requirements: 2.1, 2.4, 6.1_
    - _Depends on: 1.1_
  - [x] 1.3 Add the DTO + mapper classes
    - `UnplannedExpenseCategory{ServiceModel, ServiceExtendedModel}`, the controller DTOs
      (`Dto, DtoExtended, CreateRequest, CreateResponse, UpdateRequest, UpdateResponse`), and the
      service + controller mappers, per the delivery-categories convention.
    - _Requirements: 3.1_
    - _Depends on: 1.1_
  - [x] 1.4 Add the service
    - `UnplannedExpenseCategoryService implements AdminService<...>` (plain `AdminService`, NOT
      `ProjectScopedService`); wire dao, mapper, auditLogDao, `daoModelClass`.
    - _Requirements: 3.7_
    - _Depends on: 1.2, 1.3_
  - [x] 1.5 Add the controller
    - `@RestController @RequestMapping("/api/unplanned-expense-categories")
      @PermissionResource("UNPLANNED_EXPENSE_CATEGORIES")` implementing the generic `AdminController`;
      override only `getMapper()/getService()/getAuditServiceMapper()`. Verify the app starts
      (no `PermissionAnnotationValidator` failure).
    - _Requirements: 3.1, 3.2, 3.6_
    - _Depends on: 1.4_

- [x] 2. Backend: ABAC seed + default rows
  - [x] 2.1 Seed resource, grants, and default categories
    - Add `158-seed-unplanned-expense-categories-resource.xml`: resource row
      `UNPLANNED_EXPENSE_CATEGORIES` (+ PL/RU name/description); ADMIN CRUD; MANAGER/FOREMAN/FINANCIER
      READ; WORKER/CLIENT omitted (commented); seed the five default rows (`reclamation`,
      `unforeseen`, `materials`, `fuel`, `other`) with PL + RU names. Each changeSet idempotent via
      the standard preconditions; register last in `changelog.xml`.
    - _Requirements: 2.3, 3.3, 3.4, 3.5_
    - _Depends on: 1.1_

- [x] 3. Frontend: execution-stage tab realignment
  - [x] 3.1 Realign WORKSPACE_TABS + WorkspaceTabKey
    - In `workspaceTabs.ts`, replace the execution entries with the ten FOR-06 tabs from design §3.1
      (each `stage:'execution'`, `owner:'FOR-06'`, correct `requiredPermission`, `labelKey`,
      `lazy: PlaceholderTab`); in `types.ts`, update the `WorkspaceTabKey` union + count comment and
      narrow `WorkspaceTab.owner` to `'FOR-05' | 'FOR-06'`. Update resolver fixtures/tests that
      assert the execution tab list. Do not touch `resolveWorkspaceTabs` or `stageOf`.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_
    - _Depends on: none_

- [x] 4. Frontend: admin CRUD page
  - [x] 4.1 Build the unplanned-expense-categories feature folder
    - `features/unplanned-expense-categories/` with `UnplannedExpenseCategoriesPage.tsx`, `api/`,
      `components/` (list, form sheet, delete dialog), `schemas/` (zod; `code` required + immutable
      on edit), `types/`, `__tests__/`. Permission-gate write actions on the resource's
      CREATE/UPDATE/DELETE ops.
    - _Requirements: 4.1, 4.2, 2.4_
    - _Depends on: 1.5, 2.1_
  - [x] 4.2 Register route + nav entry
    - Add the page route and a nav entry gated by `UNPLANNED_EXPENSE_CATEGORIES`/`READ` in the
      dictionaries menu group.
    - _Requirements: 4.3_
    - _Depends on: 4.1_

- [x] 5. i18n + backend tests
  - [x] 5.1 Add i18n keys (PL + RU, mirrored)
    - Add the `nav.unplannedExpenseCategories` + `unplannedExpenseCategories.*` feature block and the
      realigned `workspace.tab.*` keys to both `pl.json` and `ru.json` (values per design §5.1);
      remove the obsolete `workspace.tab.documents` / `workspace.tab.priceHistory` keys. Run the
      locale-parity test.
    - _Requirements: 4.4, 6.2, 6.3_
    - _Depends on: 3.1, 4.1_
  - [x] 5.2 Backend ABAC + idempotency tests
    - Add the matrix property test (ADMIN CRUD; MANAGER/FOREMAN/FINANCIER READ; WORKER/CLIENT none)
      and confirm seed idempotency through the Testcontainers migration run. Run only these classes
      with `--tests` → `/tmp/for-06-00-test.log`; verify via JUnit XML.
    - _Requirements: 3.4, 3.5, 3.6, 2.3_
    - _Depends on: 2.1_

- [x] 6. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template). FOR-06-00 is API-only on the backend side, so the
    dictionary scenarios are API tests against the Dockerized app (`http://localhost:8080`,
    `/api/unplanned-expense-categories`); the tab-realignment scenarios are browser-engine checks of
    the execution tab set for an `ACTIVE` project. Result artifacts are MD reports with tables.
  - _Requirements: 1.1, 1.3, 2.3, 3.4, 3.6, 4.1_
  - _Depends on: 1.5, 2.1, 3.1, 4.2, 5.1, 5.2_

- [x] 7. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes; type-check + unit-test the frontend; confirm the locale-parity test passes and the
    app starts. Confirm the execution tab set renders for an `ACTIVE` project (seeded tabs appear,
    unseeded stay hidden). Then stage and commit per repo (two commits) — only if the user asks.
  - _Requirements: all_
  - _Depends on: 1.5, 2.1, 3.1, 4.2, 5.1, 5.2, 6_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "3.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["1.4"] },
    { "id": 3, "tasks": ["1.5"] },
    { "id": 4, "tasks": ["2.1"] },
    { "id": 5, "tasks": ["4.1"] },
    { "id": 6, "tasks": ["4.2"] },
    { "id": 7, "tasks": ["5.1", "5.2"] },
    { "id": 8, "tasks": ["6"] },
    { "id": 9, "tasks": ["7"] }
  ]
}
```
