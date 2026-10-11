# FOR-QA-AUTO-11: Detailed E2E — Project readiness (aggregated readiness surface)

## Overview

FOR-QA-AUTO-11 is the **detailed** (deep, per-feature) E2E slice for **FOR-05-11 — Project readiness
(агрегированная готовность проекта)**. It is the executable counterpart of that spec's
`test-cases.md`, turning its **UI** test cases (`TC-UI-…`, Part B) into runnable Cucumber-JVM +
Playwright-for-Java scenarios that drive the real browser UI against the live Docker stack.

It lives in the shared `foremen-qa-auto/` module and reuses the framework delivered by
FOR-QA-AUTO-SMOKE and the conventions established by FOR-QA-AUTO-09 / FOR-QA-AUTO-10 (Hooks, World,
ApiHelper, DataGen, lazy page objects, LIFO teardown, RU inline narration). The scenarios are tagged
`@detailed @FOR-QA-AUTO-11 @ui`, placed under
`src/test/resources/features/detailed/for-qa-auto-11/`, and run via the dedicated Gradle task
`featureTestForQaAuto11` (tag slice `@detailed and @FOR-QA-AUTO-11`).

FOR-05-11 ships **both an API surface and a browser screen**. The **API** half (controller guarding
+ `PROJECTS`-READ aggregate gate, project scope `404`, per-area READ composition, the equal-weight
average + `BLOCKED > PARTIAL > DONE` roll-up, the per-area completion rules, the end-to-end
stage-walk, and the read-is-side-effect-free/no-audit invariant) stays in the spec's own
`test-cases.md` API part (Part A) — those are backend contracts not observable through the browser,
out of FOR-QA-AUTO's UI scope except where a UI scenario depends on them for setup/verification. This
slice automates the **UI-visible subset** (Part B, Фичи 7–9 of the `test-cases.md`): the `readiness`
workspace tab (donut, ordered area rows, localized state labels, outstanding-item sentences,
jump-to-tab, empty state) and the header `ReadinessWidget` (donut from the overall percentage, one
chip per readable area, stage gate at the call site).

## Scope boundaries

- **In scope**: the browser-observable behavior of the `readiness` tab panel and the
  `ReadinessWidget` header strip. API calls are used only for **setup** (seed a project with a
  manager + client; drive an area change via the Team API to flip a gate) and for **verification** of
  an outcome a UI action produces (e.g. the aggregated `GET /api/projects/{id}/readiness` reflecting a
  committed area change behind the tab refetch).
- **Out of scope** (owned by the FOR-05-11 `test-cases.md` API part): `TC-SETUP-*`, `TC-GUARD-*`,
  `TC-SCOPE-*`, `TC-READ-*`, `TC-AREA-*`, `TC-PSCOPE-*`, `TC-E2E-*`, and the `TC-REG-*` backend
  regression cases. These are backend/ABAC contracts verified by the backend's own unit / property /
  Testcontainers suites and the spec's API test-cases, not through the browser.

## Feature file

| File | Area (FOR-05-11 test-cases фича) | Scenarios (TC ids) |
|------|----------------------------------|--------------------|
| `readiness_ui.feature` | Фичи 7–9 — вкладка `readiness`, виджет `ReadinessWidget`, i18n | TC-UI-TAB-01, TC-UI-TAB-02, TC-UI-TAB-04, TC-UI-WIDGET-01, TC-UI-WIDGET-04 |

**Automated: 5 scenarios in one feature file. All 5 pass against the live stack** (backend + frontend
images must contain the FOR-05-11 commit — see the "stale image" note below).

Every scenario carries inline **RU narration** (a feature description, a scenario-intent line
referencing the TC id, and a `# что:` / `# ожидание:` pair after each step), so the generated
`run-report.md` reads as the documented case set. Business-readable English Gherkin drives the steps;
the RU narration lives in the comments.

## TC → scenario coverage map

| TC | Automated? | Scenario / note |
|----|:----------:|-----------------|
| TC-UI-TAB-01 | ✅ | `readiness_ui.feature` — on a design project the `readiness` tab is visible, opens, the URL ends with `/readiness`, the `readiness-tab` container renders, the overall donut (`readiness-donut-pct`) and localized overall-state label (`readiness-overall-state`) are shown, the area rows render in the server's declared order (rooms → estimate → offer → documents → team → schedule), each with a localized name + localized state label (`readiness-area-state-<key>`) + a percentage (`readiness-area-pct-<key>`), and no raw `readiness.*` / `workspace.tab.*` i18n key leaks |
| TC-UI-TAB-02 | ✅ | jump-to-tab — activating the `rooms` area header navigates the workspace to `/projects/{id}/rooms`; activating the `team` area header navigates to `/projects/{id}/team` (each `targetTab` is a valid `WORKSPACE_TABS` key) |
| TC-UI-TAB-03 | ❌ | **Not automated** — empty state (a caller with no readable area → `readiness-tab-empty` at `0%` / `BLOCKED`). No stock role holds `PROJECTS` READ with **zero** `Area_Resource` READ: a `FOREMAN` still reads `ROOMS` / `WORK_SCHEDULE` / `PROJECT_MEMBERS` / `SIGNABLE_DOCUMENTS` (verified against the live grant matrix), so it always sees several readiness areas and never the empty state. Producing a true no-readable-area caller needs a bespoke custom role with a hand-built grant matrix (`PROJECTS` READ only) — the same reason TC-UI-WIDGET-02 is deferred. The empty-state contract is a server-composition contract fully covered by the FOR-05-11 API test-cases (`TC-PSCOPE-02`) and the backend `ProjectReadinessService` test; the frontend empty branch is covered by the `ReadinessTab` component test (vitest). |
| TC-UI-TAB-04 | ✅ | refetch after an area change — open the `readiness` tab with the `team` area `BLOCKED` (no active foreman), assign an ACTIVE `FOREMAN` via the Team API, and the `team` row flips to `DONE` and the overall donut rises **without a full page reload** (the `readinessKeys.view(projectId)` invalidation / the tab's own refetch); the row/donut are polled until the new state resolves |
| TC-UI-WIDGET-01 | ✅ | the `ReadinessWidget` header shows the donut from the server's overall percentage and one chip per readable area labeled "`<area name>`: `<state>`" (e.g. "Pomieszczenia: …" PL / "Помещения: …" RU); the chip strip (`workspace-readiness-gates`) renders one chip per area the caller may read |
| TC-UI-WIDGET-04 | ✅ | stage gate — the `ReadinessWidget` renders on a **design**-stage project (call-site gate present) and is **absent** on an **execution** (ACTIVE) project; reuses the existing `WorkspaceSteps` readiness-widget shown/not-shown assertions |
| TC-UI-WIDGET-02 | ❌ | **Not automated** — "only `ROOMS` READ → a single chip, omitted areas absent". This needs a caller holding `PROJECTS` READ + exactly `ROOMS` READ and no other `Area_Resource` READ, i.e. a bespoke custom role with a hand-built grant matrix. The per-area permission scoping is a server contract fully covered by the FOR-05-11 API test-cases (`TC-PSCOPE-01`) and the backend `ProjectReadinessService` permission-scoping property tests; the UI merely renders what the server returns, which TC-UI-WIDGET-01 already exercises. The empty-scope end (no readable area) IS automated via TC-UI-TAB-03. |
| TC-UI-WIDGET-03 | ❌ | **Not automated** — "forced request failure → localized `readiness.widget.loadError` note". Deterministically failing the aggregated read from the browser requires fault injection (network stub / backend outage) that the live-stack harness does not provide; the no-crash/localized-note path is covered by the frontend `ReadinessWidget` component test (vitest). |
| TC-UI-I18N-01 | ❌ | **Not automated** — PL↔RU language toggle on the readiness surface; the topbar language control is not a stable, locale-independent affordance to drive in this slice (same rationale as FOR-QA-AUTO-10 `TC-UI-I18N-01`). RU/PL parity is owned by the frontend `ru.json`/`pl.json` parity unit test; RU rendering is indirectly asserted by the RU-narration locators and the "no raw i18n key" checks here. |

## How to run

Bring up the stack first (from the repo root):

```
docker compose up
```

Then run the slice (from the module):

```
./gradlew featureTestForQaAuto11
```

or, equivalently, via the generic detailed runner with the tag slice:

```
./gradlew featureTest -DQA_TAGS="@detailed and @FOR-QA-AUTO-11"
```

The `featureTestForQaAuto11` task is defined in `foremen-qa-auto/build.gradle`, pinned to
`@detailed and @FOR-QA-AUTO-11`. One-time browser setup: `./gradlew installBrowsers`.

> The aggregated `GET /api/projects/{id}/readiness` endpoint and the `readiness` tab panel ship with
> FOR-05-11 (reusing the existing `PROJECTS` ABAC resource — no new resource / changeset). The Docker
> backend **and** frontend images must contain the FOR-05-11 commit for this slice to pass — a stale
> image (predating FOR-05-11) returns 404/renders the placeholder readiness tab and no widget chips.
> Rebuild with `docker compose build backend frontend && docker compose up -d --no-deps backend frontend`
> after pulling FOR-05-11.

## New / reused infrastructure

- **New page object**: `com.foremen.qa.pages.ReadinessTab` (`TAB_KEY = "readiness"`; locators for the
  tab container `readiness-tab`, the loading/error/empty states `readiness-tab-loading` /
  `readiness-tab-error` / `readiness-tab-empty`, the retry `readiness-retry`, the overall donut
  `readiness-donut-pct` + overall-state label `readiness-overall-state`, the areas list
  `readiness-areas`, and per-area `readiness-area-<key>` / `readiness-area-header-<key>` /
  `readiness-area-state-<key>` / `readiness-area-pct-<key>` / `readiness-area-items-<key>` /
  `readiness-item-<key>-<n>`). Holds no assertions.
- **New steps**: `com.foremen.qa.steps.ReadinessUiSteps` (project + manager/client setup via the API,
  browser navigation to the `readiness` tab, the donut/row/state/percentage/order assertions, the
  jump-to-tab navigation checks, the empty-state assertion for a no-readable-area member, the
  area-change refetch via the Team API with a polled row/donut wait, and the widget chip assertions).
- **Reused**: `World` (`api()`, `registerTeardown`), `ApiHelper` (admin login, project/user CRUD),
  `TeamApiHelper` (assign an ACTIVE FOREMAN to flip the `team` gate), `DataGen` (run-id-scoped
  names), `Hooks.currentPage()` (fresh context per scenario), `TestConfig.frontendUrl()`,
  `WorkspaceTabs` (tab strip click + visibility), `ProjectWorkspacePage` (route helpers + the
  readiness-widget locator), `CommonSteps` (`the application stack is ready`,
  `I am logged in as the seeded admin`), `TestUserSteps` (`a test user with role "FOREMAN" exists`,
  `I log in through the UI as the test user`, sign-out), and `WorkspaceSteps`' readiness-widget
  shown/not-shown assertions (TC-UI-WIDGET-01 / -04).
- **Duplicate-step hygiene**: step phrasings are prefixed with "readiness" (e.g.
  `the readiness tab is rendered`, `the readiness area row {string} shows a localized state and percentage`)
  to avoid colliding with `WorkspaceSteps` / `ScheduleUiSteps` / `TeamSelectionUiSteps` — Cucumber
  fails the whole run on a duplicate step definition. The existing
  `the readiness widget is shown` / `is not shown` steps (WorkspaceSteps) are reused as-is.

## Data & repeatability

**Generator-based unique data + LIFO teardown**, matching the `test-cases.md` repeatability standard
and FOR-QA-AUTO-09/10:

- A per-run design project `Readiness UI {run-id}` created via the API with its mandatory ACTIVE
  manager + client (the FOR-05-09 create invariant) but **no active foreman** — so the `team` area
  starts `BLOCKED` and the stage-walk / refetch scenario (TC-UI-TAB-04) has a gate to flip. Torn down
  LIFO through `world.registerTeardown(...)`.
- An execution-stage (`ACTIVE`) project for the widget stage-gate scenario (TC-UI-WIDGET-04), created
  with an inline `status=ACTIVE` and torn down the same way.
- The `readiness` surface is strictly **read-only** (the aggregated read writes nothing and emits no
  audit), so repeated runs are idempotent — only the seeded project / membership / foreman created
  for setup are torn down; seed roles and reference data are never modified.

## i18n note

FOR-QA-AUTO-11 is a **QA automation** slice. It introduces **no new domain entities** and **no new
user-facing UI text**; it only **verifies** existing localized behavior (the `readiness.state.*` and
`workspace.tab.*` labels, the area-row / widget-chip text, the absence of raw `readiness.*` /
`workspace.tab.*` keys). It also emits **no notifications** — the readiness surface is a read-only
projection. The spec document is written in **English**; the `readiness_ui.feature` RU narration
follows the `test-cases.md` steering standard.

---

*Created: October 2026 — detailed E2E slice for FOR-05-11.*
