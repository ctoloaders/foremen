# FOR-QA-AUTO-10: Detailed E2E — Planning Gantt (Harmonogram)

## Overview

FOR-QA-AUTO-10 is the **detailed** (deep, per-feature) E2E slice for **FOR-05-10 — Planning Gantt
(Harmonogram / планировочный график проекта)**. It is the executable counterpart of that spec's
`test-cases.md`, turning its **UI** test cases (`TC-UI-…`) into runnable Cucumber-JVM +
Playwright-for-Java scenarios that drive the real browser UI against the live Docker stack.

It lives in the shared `foremen-qa-auto/` module and reuses the framework delivered by
FOR-QA-AUTO-SMOKE and the conventions established by FOR-QA-AUTO-09 (Hooks, World, ApiHelper,
DataGen, lazy page objects, LIFO teardown, RU inline narration). The scenarios are tagged
`@detailed @FOR-QA-AUTO-10 @ui`, placed under
`src/test/resources/features/detailed/for-qa-auto-10/`, and run via the dedicated Gradle task
`featureTestForQaAuto10` (tag slice `@detailed and @FOR-QA-AUTO-10`).

FOR-05-10 ships **both an API surface and a browser screen**. The **API** half (resource/ABAC,
project scope, schedule rows & reads, auto-create, bar save/validation, status-lock & concurrency,
readiness, audit, API i18n) stays in the spec's own `test-cases.md` API part — those are backend
contracts not observable through the browser, out of FOR-QA-AUTO's UI scope except where a UI
scenario depends on them for setup/verification. This slice automates the **UI-visible subset**
(Features 10–14 of the `test-cases.md`): the Harmonogram workspace tab routing & render, the schedule
view (rows, volume, week/day scale, calendar, money masking), editing (keyboard move, discard,
edit-dialog validation, remove-from-schedule, UI auto-create, read-only lock), and the schedule
readiness chip in the ReadinessWidget.

## Scope boundaries

- **In scope**: the browser-observable behavior of the `scheduleDesign` tab and the ReadinessWidget
  schedule chip. API calls are used only for **setup** (seed project with a dated anchor, crew of two
  ACTIVE workers, manager; apply a package to the estimate so the schedule has category rows) and for
  **verification** of an outcome a UI action produces (e.g. readiness `DONE` after a UI auto-create).
- **Out of scope** (owned by the FOR-05-10 `test-cases.md` API part): `TC-SETUP-*`, `TC-ABAC-*`,
  `TC-SCOPE-*`, `TC-READ-*`, `TC-AUTO-*`, `TC-SAVE-*`, `TC-LOCK-*`, `TC-READY-01` (API),
  `TC-AUDIT-*`, `TC-I18N-API-*`, and the `REG-*` backend regression cases.

## Feature file

| File | Area (FOR-05-10 test-cases фича) | Scenarios (TC ids) |
|------|----------------------------------|--------------------|
| `schedule_ui.feature` | Фичи 10–14 — вкладка, просмотр, редактирование, chip готовности, i18n | TC-UI-TAB-01, TC-UI-VIEW-01, TC-UI-VIEW-02, TC-UI-VIEW-02b, TC-UI-VIEW-04, TC-UI-EDIT-01, TC-UI-EDIT-02, TC-UI-EDIT-03, TC-UI-EDIT-04, TC-UI-EDIT-05, TC-UI-EDIT-05b, TC-UI-EDIT-06, TC-UI-READY-01 |

Every scenario carries inline **RU narration** (a feature description, a scenario-intent line
referencing the TC id, and a `# что:` / `# ожидание:` pair after each step), so the generated
`run-report.md` reads as the documented case set. Business-readable English Gherkin drives the steps;
the RU narration lives in the comments.

**Automated: 13 scenarios in one feature file. All 13 pass against the live stack.**

## TC → scenario coverage map

| TC | Automated? | Scenario / note |
|----|:----------:|-----------------|
| TC-UI-TAB-01 | ✅ | `schedule_ui.feature` — the `scheduleDesign` tab is visible in the strip, opens, the URL ends with `/scheduleDesign`, the `schedule-tab` container renders, and no raw `projectSchedule.*` i18n keys leak |
| TC-UI-TAB-02 | ❌ | **Not automated** — "no READ permission hides the tab" revokes and re-grants a grant inside the case; this is an ABAC negative on a resource grant, not deterministically drivable without a dedicated restricted role for `WORK_SCHEDULE` and a grant-mutation API. The positive tab-visibility is covered by TC-UI-TAB-01; the resource/ABAC seeding is verified by the API half (`TC-ABAC-*`). |
| TC-UI-VIEW-01 | ✅ | one row per estimate category; each row shows its line count and planned duration with **no** man-days / rate; the week view header + calendar legend are shown; weekend cells are shaded; switching to day view shows the day header |
| TC-UI-VIEW-02 | ✅ | money columns (category net sums) are shown for the admin (Money_Viewer); the summary shows the active crew size |
| TC-UI-VIEW-02b | ✅ | split out from TC-UI-VIEW-02 — for a `FOREMAN` member the money columns are **hidden** (sign out of admin → UI-login as the foreman member → open the tab) |
| TC-UI-VIEW-03 | ❌ | **Not automated** — "project with no start date" clears and restores `startDate` inside the case; the no-start-hint (`schedule-no-start-hint`) locator exists in the page object, but seeding and restoring a null anchor on a run project is omitted to keep the slice stable. The dated-anchor path (weekend shading, headers) is covered by TC-UI-VIEW-01. |
| TC-UI-VIEW-04 | ✅ | empty-estimate state — a project with no estimate shows the empty state (`schedule-tab-empty`, "Brak prac w kosztorysie"). (Browser login as the seeded admin runs before opening the tab.) Loading/error sub-states are transient and not asserted. |
| TC-UI-VIEW-05 | ❌ | **Not automated** — mobile layout; the responsive Gantt is a visual breakpoint check. The desktop view path is fully covered; a dedicated mobile-viewport scenario is omitted for now. |
| TC-UI-EDIT-01 | ✅ | UI auto-create to get bars, then move the first bar with the keyboard (focus bar + Arrow), the draft/unsaved badge appears, Save issues one PUT /bars, the badge clears |
| TC-UI-EDIT-02 | ✅ | after a keyboard move, "Odrzuć zmiany" (discard) reverts the bar and clears the draft badge |
| TC-UI-EDIT-03 | ✅ | open the edit dialog (Enter on a bar), enter an invalid duration → the validation error shows, the Apply is blocked; cancel closes the dialog |
| TC-UI-EDIT-04 | ✅ | "Usuń z harmonogramu" (remove from schedule) marks the row unscheduled in the draft ("Nie zaplanowano") |
| TC-UI-EDIT-05 | ✅ | UI auto-create — open the schedule, click "Utwórz automatycznie", confirm in the dialog (word-only crew explanation, no rate field), one POST /auto-create, bars rebuilt; readiness verified `DONE` via API |
| TC-UI-EDIT-05b | ✅ | split out from TC-UI-EDIT-05 — the auto-create button is **disabled** with a "Zespół" hint when the project has no active workers |
| TC-UI-EDIT-06 | ✅ | read-only — an `ACTIVE` (execution-stage) project (reached through the grouped **design selector**) shows the read-only banner, no interactive bars, and no Save button |
| TC-UI-READY-01 | ✅ | the ReadinessWidget shows the schedule gate chip ("Harmonogram: …" PL / "График: …" RU); the chip text is polled until the schedule state resolves |
| TC-UI-I18N-01 | ❌ | **Not automated** — language toggle (PL↔RU) on the schedule screen; the topbar language control is not a stable, locale-independent affordance to drive in this slice. RU/PL parity is owned by the frontend's own `ru.json`/`pl.json` parity unit test; RU rendering is indirectly asserted by the RU-narration locators here. |

## A frontend bug found and fixed during automation

Automating TC-UI-EDIT-06 (read-only on an execution-stage project) surfaced a genuine frontend bug.
On an **execution-stage** project the design-stage tabs — including `scheduleDesign` — are reached
through the grouped **design selector** (FOR-05-01 FIX-2), so they live in `designSelectorTabs`, not
the main-strip `tabs`. `WorkspaceTabs.tsx` resolved the active panel with
`tabs.find((t) => t.key === activeKey)` **only**, so selecting a design-selector tab on an execution
project resolved to `null` and rendered a **blank panel**.

Fix (in `foremen-frontend/src/features/project-workspace/components/WorkspaceTabs.tsx`): the
`activeTab` lookup now falls back to `designSelectorTabs.find(...)` after the main-strip lookup, and
`designSelectorTabs` was added to the `useMemo` dependency array:

```ts
const activeTab = useMemo(
  () =>
    tabs.find((tab) => tab.key === activeKey)
      ?? designSelectorTabs.find((tab) => tab.key === activeKey)
      ?? null,
  [tabs, designSelectorTabs, activeKey],
)
```

This made the execution-stage `scheduleDesign` panel mount, which is what TC-UI-EDIT-06 asserts (the
read-only banner + absence of interactive bars). The existing `WorkspaceTabs.test.tsx` suite stays
green; the one unrelated `roomsTabSwapAndRouteGating.test.tsx` failure is a known pre-existing stale
contract (it still expects the `pricing` tab → `PlaceholderTab`, but FOR-05-07 swapped
`pricing` → `OfferTab`), independent of this change — it renders a lazy panel in isolation and never
touches `WorkspaceTabs.tsx`.

## How to run

Bring up the stack first (from the repo root):

```
docker compose up
```

Then run the slice (from the module):

```
./gradlew featureTestForQaAuto10
```

or, equivalently, via the generic detailed runner with the tag slice:

```
./gradlew featureTest -DQA_TAGS="@detailed and @FOR-QA-AUTO-10"
```

The `featureTestForQaAuto10` task is defined in `foremen-qa-auto/build.gradle`, pinned to
`@detailed and @FOR-QA-AUTO-10`. One-time browser setup: `./gradlew installBrowsers`.

> The schedule tables and the `WORK_SCHEDULE` ABAC resource are shipped by FOR-05-10 (liquibase
> changesets 152/153). The Docker backend **and** frontend images must contain the FOR-05-10 commit
> for this slice to pass — a stale image (predating the FOR-05-10 commit) returns 401 on
> `/api/project-schedules` and renders no schedule chip. Rebuild with
> `docker compose build backend frontend && docker compose up -d --no-deps backend frontend` after
> pulling FOR-05-10.

## New / reused infrastructure

- **New page object**: `com.foremen.qa.pages.ScheduleTab` (`TAB_KEY = "scheduleDesign"`; locators for
  the tab container, loading/error/empty states, retry, read-only banner, summary + crew, week/day
  zoom toggles, save/discard/auto-create, unsaved badge, gantt grid, timeline, headers, legend,
  weekend/holiday cells, project-end marker, per-row label/name/lines/duration/value/toggle/schedule/
  edit/clear/unscheduled, editable & static bars, the bar-edit dialog, the auto-create dialog, the
  conflict dialog, and the leave-confirmation dialog).
- **New support helper**: `com.foremen.qa.support.ScheduleApiHelper` (schedule `view` / `readiness`,
  `autoCreate`, `assignWorker`, `patchAssignmentStatus`; a `Resp` record exposing `rowCategoryIds()`,
  `versionField()`, `crewSizeField()`), used for setup and for verifying UI-driven outcomes.
- **New steps**: `com.foremen.qa.steps.ScheduleUiSteps` (project + crew + estimate-package setup via
  the API, browser navigation to the tab incl. the design-selector path, the view/edit/readiness
  assertions, sign-out + foreman-membership steps, and the readiness-chip wait).
- **Reused**: `World` (`api()`, `registerTeardown`), `ApiHelper` (admin login, project/user CRUD),
  `DataGen` (run-id-scoped names), `Hooks.currentPage()` (fresh context per scenario),
  `TestConfig.frontendUrl()`, `EstimateTab` (apply-package path to seed estimate lines through the
  real UI), `TeamApiHelper`/conventions from FOR-QA-AUTO-09, `CommonSteps`
  (`the application stack is ready`, `I am logged in as the seeded admin`), `TestUserSteps`
  (`I log in through the UI as the test user`), `WorkspaceSteps` (the readiness widget assertion).
- **Duplicate-step hygiene**: a would-be duplicate `the readiness widget is shown` was dropped in
  favour of `WorkspaceSteps`', and `the workspace URL ends with {string}` was renamed to
  `the schedule workspace URL ends with {string}` to avoid colliding with `TeamSelectionUiSteps`
  (Cucumber fails the whole run on a duplicate step definition).

## Data & repeatability

**Generator-based unique data + LIFO teardown**, matching the `test-cases.md` repeatability standard
and FOR-QA-AUTO-09:

- A per-run project `Gantt Test {run-id}` with a dated anchor (`startDate = 2026-11-02` Mon,
  `endDate = 2026-11-27`), two ACTIVE `WORKER`s, and a manager, all created via the API and torn down
  LIFO through `world.registerTeardown(...)`.
- Estimate lines are seeded through the **real UI** apply-package path (`EstimateTab`:
  select first package → apply → save), because the generic estimate-line CRUD requires a complex
  estimate/workitem/room-qty chain. This also exercises the "schedule rows follow the estimate"
  contract from the UI side.
- The read-only (TC-UI-EDIT-06) scenario uses an `ACTIVE` project reached through the design selector;
  the empty-state (TC-UI-VIEW-04) scenario uses a project created with no estimate.

## i18n note

FOR-QA-AUTO-10 is a **QA automation** slice. It introduces **no new domain entities** and **no new
user-facing UI text**; it only **verifies** existing localized behavior (PL schedule labels, the
"Harmonogram"/"График" readiness chip, absence of raw `projectSchedule.*` keys). The spec document is
written in **English**; the `schedule_ui.feature` RU narration follows the `test-cases.md` steering
standard.

---

*Created: October 2026 — detailed E2E slice for FOR-05-10.*
