# FOR-QA-AUTO-05: Detailed E2E — Project Workspace Shell

## Overview

FOR-QA-AUTO-05 is the **detailed** (deep, per-feature) E2E slice for **FOR-05-01 — Project workspace
shell**. It is the executable counterpart of that spec's `test-cases.md`, turning its numbered test
cases (TC-…) into runnable Cucumber-JVM + Playwright-for-Java scenarios that drive the real UI
against the live Docker stack.

It lives in the shared `foremen-qa-auto/` module and reuses the framework delivered by
FOR-QA-AUTO-SMOKE (Hooks, World, ApiHelper, DataGen, page objects, `TestUserFixture`). No harness
change is needed: the scenarios are tagged `@detailed @FOR-QA-AUTO-05`, placed under
`src/test/resources/features/detailed/for-qa-auto-05/`, and discovered automatically by the existing
`RunSmokeTest` `@Suite` (which selects the `features` classpath resource and filters by tag).

FOR-05-01 is a **frontend-only** spec: it adds no backend entity, table, migration, or ABAC
resource. These scenarios verify **client behavior** — the `/projects/:projectId/:tab` route,
client-side working-project state, the always-visible working-project surface, the stage-dependent
tab set, URL-driven active-tab memory, the readiness widget, the mode caption, and the projects-list
row-click override.

## Priority: the two just-fixed bugs

Two bugs were fixed in FOR-05-01 and are the coverage priority here:

- **FIX-1 — TC-04-05**: selecting a project row updates the working-project surface (sidebar button /
  mobile chip) **immediately, without a reload** (the shared `useWorkingProject` store notifies all
  surfaces synchronously). Covered in `working_project_surface.feature`.
- **FIX-2 — TC-06-06 / TC-06-07**: an execution-stage project exposes the design-stage tabs through
  a grouped **design selector** as the last strip element; a design-stage project has **no** separate
  selector. Covered in `stage_tabs.feature`. (TC-06-08 — "selector hidden when zero design tabs
  permitted" — is **not automatable** on the current tab contract; see the coverage map below.)

## Feature files

| File | Area (FOR-05-01 test-cases фича) | Scenarios (TC ids) |
|------|----------------------------------|--------------------|
| `workspace_route.feature` | Фича 1 — маршрут и страница | TC-01-01, TC-01-02, TC-01-03 |
| `working_project_surface.feature` | Фичи 3 & 4 — сайдбар-кнопка / чип / взаимоисключение / синхронизация | TC-03-01, TC-03-02, TC-03-03, TC-03-04, TC-04-01, TC-04-02, TC-04-03, TC-04-04, **TC-04-05 (FIX-1)**, TC-02-03 |
| `stage_tabs.feature` | Фича 6 — стадийные вкладки + design-селектор | TC-06-01, TC-06-02, TC-06-03, **TC-06-06 (FIX-2)**, **TC-06-07 (FIX-2)** |
| `active_tab_memory.feature` | Фича 9 — URL-driven вкладка + память | TC-09-01, TC-09-02, TC-09-03, TC-09-04, TC-09-05 |
| `readiness_mode.feature` | Фича 8 — виджет готовности + подпись режима | TC-08-01, TC-08-02, TC-08-03 |
| `row_click_override.feature` | Фича 5 — переопределение клика по строке | TC-05-01, TC-05-02, TC-05-03 |

All scenarios carry inline **RU narration** (feature description, optional scenario intent line
referencing the TC id, and a `# что:` / `# ожидание:` pair after every step), so the generated
`run-report.md` reads as the documented case set. Business-readable English Gherkin drives the steps;
the RU narration lives in the comments.

## TC → scenario coverage map

| TC | Automated? | Scenario / note |
|----|:----------:|-----------------|
| TC-01-01 | ✅ | `workspace_route.feature` — workspace renders, list still reachable |
| TC-01-02 | ✅ | bare route → replace to `/overview`, Back does not return to bare route |
| TC-01-03 | ✅ | restricted user (role granting only `USERS/READ`, i.e. no `PROJECTS/READ`) deep-links → `/403` |
| TC-01-04 | ❌ | **Not automated** — for a missing project id `GET /api/projects/{id}` returns 404 (verified), but on this stack the client `useProject` keeps the workspace in a perpetual loading skeleton and does not switch to the `ProjectUnavailable` (`workspace-project-unavailable`) state within an observable time (verified > 30s via a direct browser run). The unavailable state is therefore not deterministically observable through the UI (analogous to TC-01-05). |
| TC-01-05 | ❌ | **Not automated** — requires artificial slowing of `GET /api/projects/{id}` (network throttling / route interception); the non-blocking loading state is not deterministically observable without it. |
| TC-02-01 | ❌ | **Not automated** — asserts the *absence* of any new "working project" network endpoint (network-call inspection); the visible outcome (working project set) is covered by TC-05-01 / TC-04-05. |
| TC-02-02 | ❌ | **Not automated** — hydration via `GET /api/projects/{id}` on an arbitrary route is an internal network contract; the visible outcome (name + status shown) is covered by TC-03-01 / TC-02-03. |
| TC-02-03 | ✅ | `working_project_surface.feature` — working project survives reload |
| TC-02-04 | ❌ | **Not automated** — requires seeding an unresolvable stored id and asserting `clearWorkingProject()` internals; the choose-affordance fallback is covered by TC-03-03 / TC-04-03. |
| TC-03-01 | ✅ | `working_project_surface.feature` — desktop sidebar button shows name + status |
| TC-03-02 | ✅ | desktop button click → workspace |
| TC-03-03 | ✅ | no working project → choose affordance → `/projects` |
| TC-03-04 | ✅ | change affordance → `/projects` |
| TC-04-01 | ✅ | mobile chip shows name |
| TC-04-02 | ✅ | mobile chip click → workspace |
| TC-04-03 | ✅ | mobile chip choose affordance → `/projects` |
| TC-04-04 | ✅ | breakpoint mutual exclusivity (desktop button vs mobile chip) |
| **TC-04-05** | ✅ | **FIX-1** — row select updates surface immediately without reload; switch to another project switches immediately |
| TC-05-01 | ✅ | `row_click_override.feature` — row click opens workspace + sets working project, not the edit form |
| TC-05-02 | ✅ | per-row edit (Pencil) icon opens the edit form (stopPropagation), row-click not intercepted |
| TC-05-03 | ✅ | overview edit affordance `workspace.edit` opens the edit form |
| TC-06-01 | ✅ | `stage_tabs.feature` — design available ordered tab set (overview, readiness, rooms); estimate/procurement/pricing absent (resources not seeded) |
| TC-06-02 | ✅ | execution available tab set (overview only in the main strip; procurement/rooms absent); no readiness widget |
| TC-06-03 | ✅ | tab click changes the URL to the tab key (rooms, then readiness) |
| TC-06-04 | ❌ | **Not automated as a standalone case** — hiding of unseeded-resource tabs (`pricing`/`scheduleDesign`/`contract`) is asserted inline in TC-06-01 (`pricing` hidden) and is a static consequence of the not-yet-seeded resources. |
| TC-06-05 | ❌ | **Not automated as a standalone case** — per-resource ABAC tab hiding for a `PROJECTS/READ`-only user is exercised structurally by TC-06-08 (that same user sees zero design tabs). A dedicated "rooms hidden but order preserved" case is omitted to avoid provisioning every negative grant. |
| **TC-06-06** | ✅ | **FIX-2** — execution project shows the design selector last; open it; clicking `rooms` → `/projects/:id/rooms` + writes `foremen.projectTab`; restored after reload on the bare route |
| **TC-06-07** | ✅ | **FIX-2** — design project has no separate design selector |
| TC-06-08 | ❌ | **Not automated** — the "zero permitted design tabs" precondition is impossible on the current tab contract: `readiness` is a design-stage tab gated by `PROJECTS/READ`, the very permission required to reach the workspace at all. Any user who can open the workspace therefore always has at least `readiness` as a permitted design tab, so the execution-project design selector is never empty in practice and the "selector hidden when zero design tabs permitted" state cannot be reproduced. |
| TC-07-01 | ❌ | **Not automated** — adaptive strip vs mobile overflow: partially covered by the `data-variant` / overflow locators in the `WorkspaceTabs` page object, but a dedicated overflow-menu scenario is omitted for now (the mobile inline-limit behavior depends on how many tabs the seeded grants surface). |
| TC-07-02 | ❌ | **Not automated as a standalone case** — placeholder vs real panel keyed by `key`: the `activePanelTestId()` helper exists, but a dedicated scenario is omitted; overview-vs-placeholder is implicitly exercised by the tab-switch scenarios. |
| TC-07-03 | ❌ | **Not automated** — requires simulating a lazy-load chunk failure to trip the error boundary; not deterministically drivable through the UI. |
| TC-08-01 | ✅ | `readiness_mode.feature` — design readiness widget present |
| TC-08-02 | ✅ | execution: no readiness widget |
| TC-08-03 | ✅ | mode caption design ("Проектирование") vs execution ("В работе") |
| TC-09-01 | ✅ | `active_tab_memory.feature` — URL tab wins over stored memory |
| TC-09-02 | ✅ | tab switch writes memory |
| TC-09-03 | ✅ | bare route restores stored tab |
| TC-09-04 | ✅ | unknown / cross-stage tab normalizes to overview |
| TC-09-05 | ✅ | per-project memory independence — asserted via the independent stored keys (`foremen.projectTab.{A}=rooms`, `{B}=readiness`, each surviving work on the other). The cross-project bare-route *restore* is intentionally not asserted here: on this frontend the bare `/projects/:id` route always resolves relative to the **working** project, so a bare route to a non-working project redirects to the working project's tab (verified). Bare-route restore for the working project is covered by TC-09-03. |
| TC-10-01 | ❌ | **Not automated** — "all `workspace.*` strings in one active language (RU)" is a visual/i18n-parity check; the RU narration and RU-text-based locators indirectly assert RU rendering, but a dedicated raw-key-scan scenario is omitted. |
| TC-10-02 | ❌ | **Not automated** — language toggle to PL: the topbar language control is not readily drivable as a stable, locale-independent affordance in this slice; i18n parity is covered by the frontend's own `ru.json`/`pl.json` parity test (RG-03). |
| RG-01 … RG-06 | ❌ | **Not automated as standalone regression scenarios** — the critical end-to-end paths they describe (list → working project → tab → reload; instant surface sync; execution design selector) are directly covered by TC-05-01, TC-02-03, TC-04-05, TC-06-06. The i18n-parity regression (RG-03) is owned by the frontend unit test suite. |

**Automated: 22 scenarios across 6 feature files** (TC-01-01/02/03, TC-02-03, TC-03-01/02/03/04,
TC-04-01/02/03/04/05, TC-05-01/02/03, TC-06-01/02/03/06/07, TC-08-01/02/03, TC-09-01/02/03/04/05).
TC-06-08 and TC-01-04 are **not automatable** on this stack (see the coverage map for the reasons).

## How to run

Bring up the stack first (from the repo root):

```
docker compose up
```

Then run the slice (from the repo root or the module):

```
./gradlew :foremen-qa-auto:featureTestForQaAuto05
```

or, equivalently, via the generic detailed runner with the tag slice:

```
./gradlew :foremen-qa-auto:featureTest -DQA_TAGS="@detailed and @FOR-QA-AUTO-05"
```

The `featureTestForQaAuto05` task already exists in `foremen-qa-auto/build.gradle`, pinned to
`@detailed and @FOR-QA-AUTO-05` (with `-DQA_TAGS` as an override). One-time browser setup:
`./gradlew :foremen-qa-auto:installBrowsers`.

> A full run needs the live Docker stack. Compilation and Cucumber tag/discovery wiring can be
> verified without a stack via `./gradlew :foremen-qa-auto:compileTestJava`.

## Reused infrastructure

- **Support**: `World` (`api()`, `registerTeardown(...)`), `ApiHelper`
  (`loginAdmin`, `createProject(Object) → id`, `deleteProject(id)`, `resolveIdByName`), `DataGen`
  (`nextProjectName`, `runId`), `Hooks.currentPage()` (fresh incognito context per scenario),
  `TestConfig.frontendUrl()`.
- **Reused steps**: `CommonSteps` (`the application stack is ready`, `I am logged in as the seeded
  admin`), `TestUserSteps` (`I log in through the UI as the test user`), `NavVisibilitySteps`
  (`a role granting only "X" "READ" exists`, `a test user in the restricted role exists`) for the
  restricted-user ABAC cases (TC-01-03, TC-06-08).
- **New steps**: `com.foremen.qa.steps.WorkspaceSteps` (project setup via `world.api()` +
  teardown, localStorage clear/read/seed, viewport desktop/mobile, and the workspace/tab/surface
  assertions).
- **Page objects**: new `ProjectWorkspacePage`, `WorkspaceTabs`, `WorkingProjectSurface`; reused
  `ProjectsPage` (extended with `clickRow` + per-row edit/delete locators), `DataTablePage`,
  `AppShell`, `LoginPage`, `ForbiddenPage`.

## Project stage setup

Projects are created via `world.api().createProject(body)` as the seeded ADMIN and deleted in
teardown (`deleteProject`):

- **Design stage** = status `DRAFT` — the default; a name-only body works.
- **Execution stage** = status `ACTIVE` — the body carries `{name, status: "ACTIVE"}`. The backend
  `CreateProjectRequest` accepts an inline `status` (`ProjectStatus`), defaulting to `DRAFT` when
  null, so no separate status-transition call is needed.

Each scenario starts from a clean working-project / tab state via a **selective** localStorage clear
on the app origin — only `foremen.workingProjectId` and every `foremen.projectTab.*` key are removed;
the auth tokens (`foremen-access-token` / `foremen-refresh-token`) are **preserved** so the browser
stays logged in. A blanket `localStorage.clear()` would wipe those tokens and log the browser out, so
every subsequent workspace navigation would redirect to `/login` and the scenario would time out; the
admin UI login therefore runs before the clear and the clear does not touch the tokens. Hooks already
isolates scenarios with a fresh incognito context; the explicit selective clear keeps each scenario
self-describing per the repeatability standard. Unique project names come from
`DataGen.nextProjectName()`.

## Seeded-resource assumptions

On this stack the only ABAC-seeded resources backing workspace tabs are `PROJECTS` and `ROOMS`
(verified via `GET /api/resources`: `ESTIMATE`, `DELIVERIES`, `PROJECT_PRICING`, `WORK_SCHEDULE`,
`CONTRACTS`, `DOCUMENTS`, … are **not** seeded). Consequently, for the seeded ADMIN:

- a **design** project shows only: `overview` (common) and `readiness` (`PROJECTS/READ`), plus
  `rooms` (`ROOMS/READ`) — in `WORKSPACE_TABS` order `overview, readiness, rooms`. All other design
  tabs (`estimate` → `ESTIMATE`, `pricing` → `PROJECT_PRICING`, `scheduleDesign`, `contract`) are
  hidden because their resources are not seeded;
- an **execution** project shows only `overview` in the main strip (`procurement` → `DELIVERIES` and
  the other execution tabs are hidden), plus the design selector exposing the permitted design tabs
  `readiness` and `rooms`. The design-stage readiness widget does not render for execution.

The scenarios assert exactly this available subset (only tabs whose resource is seeded:
`PROJECTS` → `overview`/`readiness`, `ROOMS` → `rooms`), so they stay stable until sibling specs seed
the remaining resources. Tabs whose resource is not seeded (`estimate`, `procurement`, `garbage`) are
used only as deliberately-invalid values to exercise normalization to `overview`.

---

*Created: 2026 — detailed E2E slice for FOR-05-01.*
