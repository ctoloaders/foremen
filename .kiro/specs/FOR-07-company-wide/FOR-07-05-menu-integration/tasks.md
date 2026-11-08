# Implementation Plan: FOR-07-05 — Menu integration

## Overview

Restructure the main menu into its final layout, delete the unused placeholder routes/pages, make
the mobile bottom bar role-specific, and add one backend grant (`FOREMAN` × `COMPANY_SCHEDULE`
READ). Frontend work is committed from inside `foremen-frontend/`; the changeset, changelog and spec
files from the root repo (`.kiro/steering/git-repo-structure.md`).

**Test-run policy** (`.kiro/steering/test-execution-rules.md`): never run the full Gradle suite.
Run only the new integration class with `--tests`, redirect to `/tmp/for-07-05-test.log`, and read
the JUnit XML. Frontend: run only the affected vitest files with `--run`.

## Tasks

- [x] 1. Backend: FOREMAN company-schedule grant
  - [x] 1.1 Add changeset 217 and register it
    - Create `foremen-backend/database_files/changesets/217-grant-foreman-company-schedule.xml`
      modelled on `187-seed-company-schedule-resource.xml`: two changeSets (role_resources row,
      then `READ`-only operation row) for `FOREMAN` × `COMPANY_SCHEDULE`, each guarded by
      `<preConditions onFail="MARK_RAN">` so re-runs insert nothing. Register it **last** in
      `changelog.xml` (after `216-users-status-check.xml`). No Java change.
    - _Requirements: 6.1_
  - [x] 1.2 Integration test for the FOREMAN grant
    - Add an integration test: a FOREMAN calling `GET /api/company-schedule` gets `200` and only
      member-project data; a user with no grant still gets `403`. Run only this class and read
      `foremen-backend/build/test-results/test/TEST-<fqcn>.xml`.
    - _Requirements: 6.2, 6.3_
    - _Depends on: 1.1_

- [x] 2. Frontend: menu restructure, bottom-nav profiles, cleanup
  - [x] 2.1 Restructure `NAV_CONFIG`
    - In `src/config/navigation.ts` apply design §1: new `nav.sections.resources` section
      (Deliveries, Construction materials, Finishing materials, Work catalog) after Company;
      drop the `Catalog` and `Warehouse` sections and the `Finances`, `Prices`,
      `Material consumption` items; reorder Company to Gantt → Procurement → Payroll → Bank
      transactions; keep Dictionaries/System/Settings untouched. Remove the `bottomNav` field from
      `NavItemConfig` and add optional `id` (`addExpense`, `staffingGantt`, `companyProcurement`,
      `bankTransactions`) plus `findNavItem(id)`.
    - _Requirements: 1.1, 1.6, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 4.1, 4.4_
  - [x] 2.2 Role-specific bottom navigation
    - In `navigation.ts` (or `bottom-nav.ts`) add `BottomSlot`, `BOTTOM_NAV_PROFILES`,
      `DEFAULT_BOTTOM_SLOTS` and the pure `resolveBottomNav(roleCode, hasPermission,
      workingProjectId, projectName)`. Rewrite `BottomNav.tsx` to use it with `useAuthStore`,
      `usePermission` and `useWorkingProject()`; add optional `label` prop to `NavItem`
      (bottom-nav variant). Cap at 5 slots; path-prefix highlight for the dynamic slots.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_
    - _Depends on: 2.1, 2.4_
  - [x] 2.3 Remove dead routes, pages and permission entries
    - In `router.tsx` delete the `rooms`, `estimate`, `finances` routes and lazy imports; delete
      `RoomsPage.tsx`, `EstimatePage.tsx`, `FinancesPage.tsx` and their tests; delete
      `'/rooms'`, `'/estimate'`, `'/finances'` from `route-permissions.ts` (and their test rows).
      Keep `/projects/**`, `/deliveries`, `/catalog/**`, `/materials/**`.
    - _Requirements: 1.2, 1.3, 1.4, 1.7_
  - [x] 2.4 Add new i18n keys (PL + RU)
    - Add `nav.sections.resources` (Zasoby / Ресурсы), `nav.activeProject` (Projekt / Проект),
      `nav.addWorkReport` (Dodaj raport / Добавить отчёт) to `pl.json` and `ru.json`.
    - _Requirements: 7.1, 7.2_
  - [x] 2.5 Remove dead i18n keys
    - Grep `src/` for `nav.rooms`, `nav.estimate`, `nav.finances`, `nav.sections.warehouse`,
      `nav.sections.catalog`, `pages.rooms`, `pages.estimate`, `pages.finances`; remove each key
      from both locale files only when no non-test file references it. Keep `nav.workPrices` and
      `nav.materialConsumption` (used by the still-routed catalog pages / FOR-04-19).
    - _Requirements: 1.5_
    - _Depends on: 2.3, 2.4_
  - [x] 2.6 Frontend tests
    - Update `navigation.test.ts`, `BottomNav.test.tsx`, Sidebar/Drawer tests, `route-permissions`
      tests; add: `resolveBottomNav` per-role matrix (with/without permissions and working
      project), NAV_CONFIG ↔ router ↔ route-permissions consistency, unique paths, section order,
      locale parity for the new keys, mobile TopBar menu toggle present. Run only these files with
      `vitest --run`; then `tsc --noEmit`.
    - _Requirements: 9.1, 9.2, 9.3, 7.1_
    - _Depends on: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 3. Register the new child spec in the FOR-07 OVERVIEW
  - In `.kiro/specs/FOR-07-company-wide/OVERVIEW.md`: rewrite the child-table row 05 to this spec's
    final scope (menu cleanup + role bottom-nav + FOREMAN grant), add row **07**
    `FOR-07-07-company-deliveries` (full-stack, replaces the `/deliveries` placeholder; Dostawy slot
    in Resources), mention `FOR-04-19-work-catalog-rework` as owner of the Katalog prac slot target,
    and update the main-menu section/dependency graph (05 depends on 07 only for the real page, not
    for the slot).
  - _Requirements: 4.2, 4.3_

- [x] 4. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression
    group, MD report template). Browser-engine scenarios against the Dockerized stack at mobile
    (390px) and desktop (1280px): per-role menu and bottom bar, removed routes → NotFound, FOREMAN
    Gantt access, PL/RU labels. Result artifacts are MD reports with tables.
  - _Requirements: 1.2, 2.2, 4.1, 5.1, 5.4, 5.5, 5.7, 6.2, 7.1, 9.4_
  - _Depends on: 1.2, 2.6, 3_

- [x] 5. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava`, logged to `/tmp/for-07-05-compile.log`), run the
    affected frontend tests and `tsc --noEmit`, bring up the Docker stack and walk each role on
    mobile and desktop. Confirm no removed route is linked anywhere. Commit only if the user asks
    (frontend repo and root repo separately).
  - _Requirements: all_
  - _Depends on: 4_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.3", "2.4", "3"] },
    { "id": 1, "tasks": ["1.2", "2.2", "2.5"] },
    { "id": 2, "tasks": ["2.6"] },
    { "id": 3, "tasks": ["4"] },
    { "id": 4, "tasks": ["5"] }
  ]
}
```
