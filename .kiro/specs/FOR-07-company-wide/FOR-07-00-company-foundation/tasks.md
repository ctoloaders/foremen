# Implementation Plan: FOR-07-00 — Company foundation

## Overview

This plan delivers a **frontend-only** main-menu realignment: remove `/projects`, `/rooms`,
`/estimate` from the menu, and add a `nav.sections.company` section with two items that **reuse
existing resources** — Staffing Gantt on `WORK_SCHEDULE`/READ and Company procurement on
`PURCHASES`/READ. It adds **no** backend entity, **no** table, **no** Liquibase changeset, and **no**
new ABAC resource. Project scope is honored for free because the services behind those resources are
already `ProjectScopedService` implementations (recorded invariant, no code in 00). The Bank / Finance
menu slot is **deferred to FOR-07-04**.

**Single git repo** (per `.kiro/steering/git-repo-structure.md`): everything lives in the **frontend
repo** (`foremen-frontend/`) except this spec's own `.kiro/` documents. The code change is a **single
commit from inside `foremen-frontend/`**.

**No migrations.** This spec introduces no changeset; the changelog is untouched.

**Test-run policy.** No Gradle run is needed (no backend change). Use the frontend unit tests
(navigation, route-permissions, locale-parity) for verification.

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 1.2 — the `NAV_CONFIG` realignment and the nav i18n keys are
  independent edits (different files).
- **Wave 2:** 1.3 — frontend navigation + route-permissions + locale-parity tests (depend on 1.1 + 1.2).
- **Wave 3:** 2 — Author `test-cases.md` (depends on the implementation + tests).
- **Wave 4:** 3 — Final verification + checkpoint (depends on everything).

## Tasks

- [x] 1. Frontend: main-menu realignment + i18n + tests
  - [x] 1.1 Realign NAV_CONFIG
    - In `src/config/navigation.ts`: remove the `Projects` (`/projects`), `Rooms` (`/rooms`), and
      `Estimate` (`/estimate`) items from the first (unnamed) section (leaving only `Dashboard`); add
      a new `nav.sections.company` section with the two items from design §3.2 — Staffing Gantt →
      `WORK_SCHEDULE`/READ (`labelKey: 'nav.staffingGantt'`), Company procurement → `PURCHASES`/READ
      (`labelKey: 'nav.companyProcurement'`) — each with an icon, `bottomNav: false`, and a mandatory
      `requiredPermission` at READ on the **reused** resource. Add NO new resource. Do NOT touch
      `isNavItemVisible`, `route-permissions.ts`, `WORKSPACE_TABS`, `ProjectScopedService`, or the
      active-project surface components. Leave the `/projects` route + `ProjectsPage` + its
      route-requirement entry intact (R1.3). Do NOT add a Bank menu item (deferred to FOR-07-04).
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 3.3_
    - _Depends on: none_
  - [x] 1.2 Add nav i18n keys (PL + RU, mirrored)
    - Add `nav.sections.company`, `nav.staffingGantt`, `nav.companyProcurement` to both
      `src/locales/pl.json` and `src/locales/ru.json` (values per design §5). Place
      `nav.sections.company` inside the existing `nav.sections` object. Do NOT remove
      `nav.projects` / `nav.rooms` / `nav.estimate` (R5.3). Run the locale-parity test.
    - _Requirements: 5.1, 5.3_
    - _Depends on: none_
  - [x] 1.3 Frontend tests (navigation + route-permissions + parity)
    - Add/extend a `navigation` test: the three removed items are absent and `Dashboard` kept (P1);
      the `nav.sections.company` section exists with the two items at the specified paths/labels and
      reused resources `WORK_SCHEDULE` / `PURCHASES` (P3); `isNavItemVisible` returns `true` for the
      Staffing Gantt item under a `WORK_SCHEDULE:READ` predicate and for Company procurement under a
      `PURCHASES:READ` predicate, and `false` under neither (P4). Add/extend a `route-permissions`
      regression asserting `requirementForPath('/projects')` still resolves to `{ PROJECTS, READ }`
      (P2). Confirm the locale-parity test passes (P6).
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.3, 5.1_
    - _Depends on: 1.1, 1.2_

- [x] 2. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown,
    regression group, MD report template). FOR-07-00 is frontend-only, so the scenarios are
    browser-engine checks against the Dockerized frontend (`http://localhost:3000`): the three removed
    items are gone; the two company items are present and gated by the reused resources; project
    selection still works via the active-project surface; and (where a two-project fixture exists) a
    non-ADMIN caller's company list is narrowed to their member projects while ADMIN sees all. Result
    artifacts are MD reports with tables.
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.3, 3.2_
  - _Depends on: 1.1, 1.2, 1.3_

- [x] 3. Final verification + checkpoint
  - Type-check + unit-test the frontend; confirm the navigation, route-permissions, and locale-parity
    tests pass. Confirm the main menu renders without the three removed items and the company section
    shows the two items to a user holding the reused resources (and hides each item from a user who
    holds neither). Confirm no backend diff exists and the app starts unchanged. Then stage and commit
    from inside `foremen-frontend/` (single commit) — only if the user asks.
  - _Requirements: all_
  - _Depends on: 1.1, 1.2, 1.3, 2_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3"] },
    { "id": 2, "tasks": ["2"] },
    { "id": 3, "tasks": ["3"] }
  ]
}
```
