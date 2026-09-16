# Requirements Document

FOR-05-01: Project workspace shell — `/projects/:projectId/:tab` route, client-only working-project state, always-visible working-project surface, stage-dependent tab host, readiness widget shell

## Introduction

FOR-05-01 is a **frontend-only** child spec of FOR-05 (Project estimate). It introduces
**no backend entities**, no table, no migration, and no ABAC resource. It delivers the
**project workspace shell**: the `/projects/:projectId` route and its `ProjectWorkspacePage`,
the **client-only working-project state**, an **always-visible working-project surface** in
the app shell (desktop sidebar button + mobile top-bar chip), the **stage-dependent tab host**
with lazy placeholder panels, a **readiness widget shell**, and the **projects-list row-click
override**. The individual tab **contents** (Estimate, Rooms, Pricing, Procurement, etc.) are
delivered by later FOR-05 child specs; FOR-05-01 only provides the shell, the tab contract, and
stable placeholders that later specs plug into.

The **active workspace tab is URL-driven**: it is encoded as a URL path segment
`/projects/:projectId/:tab` (chosen over a query param for clean shareable links and natural
nested react-router routing), and the bare `/projects/:projectId` redirects to the resolved
tab. Each project also remembers its last active tab in `localStorage` under
`foremen.projectTab.<projectId>` so returning to a project restores its last view. The **URL
is the source of truth**; `localStorage` is only "last tab memory", so a shared link with an
explicit tab always wins over the stored per-project tab.

The **working project** (parent design §4.3b) is the id of the project the user is currently
working on. It is **pure client-side UI state**, persisted in `localStorage` under the key
`foremen.workingProjectId`. It is not a backend entity and not an ABAC resource; it exists only
so the app can always surface "the project you're in" and re-open it after a reload. Selecting a
working project is just clicking a row in the existing `/projects` list (parent design §8.2) —
there is no separate picker.

The visible workspace tabs depend on the **project stage** derived from `Project.status`, split
at the contract-signing boundary into **design** (`DRAFT`, `READY_TO_OFFER`, `OFFERED`,
`APPROVED`) and **execution** (`ACTIVE`, `COMPLETED`) stages, then filtered by the existing ABAC
per-tab permission predicate via `resolveWorkspaceTabs(project, user)` (parent design §6.10).

This spec builds on the real frontend patterns: the react-router `createBrowserRouter` tree in
`src/app/router.tsx` (lazy pages wrapped in `SuspenseWrapper`, gated by `ProtectedLayout` +
`PermissionGuard`); `src/config/navigation.ts` (`NAV_CONFIG`, `isNavItemVisible`,
`requiredPermission`); `src/app/layout/AppShell.tsx` (desktop/tablet `Sidebar` + `TopBar`, mobile
`TopBar` + `BottomNav` + `Drawer`, driven by `useBreakpoint`); i18n via react-i18next with
`locales/pl.json` + `ru.json` and a **single active language** at a time (never dual labels);
and the `usePermission()` predicate `(resource, operation) => boolean`.

A static HTML prototype at `../prototype/project-workspace-full.html` is the **visual target**
for the workspace shell, header, readiness widget, and tab strip. Per the workspace test-cases
standard, a `test-cases.md` (in Russian) will be authored during the tasks phase of this spec.

This spec has a hard dependency on FOR-04-13 (`Project` entity + `/projects` list + projects
API) for working-project hydration and the row-click override. Because FOR-04-13/14 may not be
fully landed, the shell must **degrade gracefully** where the projects API is not yet available.

## Glossary

- **Working project**: The client-only UI state holding the id of the project the user is currently working on, persisted in `localStorage` under `foremen.workingProjectId` (parent design §4.3b). Not a backend entity, not an ABAC resource.
- **`workingProject.ts` state module**: `src/features/project-workspace/state/workingProject.ts` exposing `getWorkingProjectId()` / `setWorkingProjectId(id)` / `clearWorkingProject()` over `localStorage`, plus the `useWorkingProject()` React hook that holds the id and hydrates the project summary from the projects API.
- **`ProjectWorkspacePage`**: The page rendered at route `/projects/:projectId` inside the `AppShell`; it sets the working project on open, hydrates the project summary, and renders the workspace header + tab host.
- **Tab host**: The responsive local tab strip + lazy panel area inside `ProjectWorkspacePage` that renders one panel per resolved workspace tab (desktop horizontal strip; mobile scrollable/segmented + overflow).
- **Stage**: The project mode derived from `Project.status` at the contract-signing boundary — **design** (`DRAFT`, `READY_TO_OFFER`, `OFFERED`, `APPROVED`) or **execution** (`ACTIVE`, `COMPLETED`).
- **`stageOf(status)`**: The total, single-valued function mapping every project status to exactly one of `{design, execution}` (parent design Property 14).
- **`resolveWorkspaceTabs(project, user)`**: The resolver (parent design §6.10) that returns the ordered, ABAC-filtered list of workspace tabs for a project's stage: the current stage's tabs (plus common) intersected with the tabs the user is permitted to see.
- **Workspace tab contract**: The declared shape of a tab — `key`, `stage`, `owner`, `requiredPermission`, `labelKey`, and a `lazy` component — declared once in `WORKSPACE_TABS` as the single ordered source of truth.
- **Readiness widget**: The design-stage header widget showing a readiness donut + gate checklist (done/partial/blocked) with a headline percentage; in FOR-05-01 it is a shell consuming a readiness DTO that may be stubbed until the FOR-05 readiness endpoint lands.
- **Working-project button / chip**: The always-visible app-shell surface for the working project — the desktop sidebar button under the Dashboard nav entry and the mobile top-bar chip.
- **Mode caption**: The header caption derived from stage — `workspace.mode.design` ("Projektowanie" / "Проектирование") or `workspace.mode.execution` ("W realizacji" / "В работе").
- **Active tab (URL-driven)**: The currently selected workspace tab, encoded as the `:tab` path segment of `/projects/:projectId/:tab`. Shareable; the URL is the source of truth.
- **Per-project tab memory**: The last active tab for a project, persisted in `localStorage` under `foremen.projectTab.<projectId>`, used to restore the view when the user returns to that project via the bare `/projects/:projectId`.

## Requirements

### Requirement 1: Workspace route & page

**User Story:** As an authenticated user, I want a `/projects/:projectId/:tab` workspace page inside the app shell, protected and permission-gated like other routes, so that I can open a specific project's workspace at a specific tab while the projects list stays available.

#### Acceptance Criteria

1. THE system SHALL add a nested route `/projects/:projectId/:tab` that lazy-loads `ProjectWorkspacePage` wrapped in `SuspenseWrapper` and rendered inside the `AppShell`, under `ProtectedLayout` and `PermissionGuard`, matching the existing router pattern in `src/app/router.tsx`, with the `:tab` segment rendered by the tab host (Requirement 7).
2. THE system SHALL keep the existing `/projects` list route unchanged and reachable, so `/projects` renders the projects list and `/projects/:projectId/:tab` renders the workspace.
3. WHEN a user navigates to the bare `/projects/:projectId` (no `:tab`), THE system SHALL resolve the active tab (per Requirement 12) and redirect (replace) to `/projects/:projectId/:tab`.
4. THE workspace route SHALL be gated by the same `PROJECTS`/`READ` permission requirement as the `/projects` list, so a user lacking `PROJECTS` READ is redirected to `/403` by `PermissionGuard`.
5. WHEN a user navigates to `/projects/:projectId/:tab` with a `projectId` that resolves to a project the user may access, THE system SHALL render `ProjectWorkspacePage` for that project.
6. IF the `projectId` does not resolve to an accessible project (missing, deleted, or the user has no access), THEN THE system SHALL render a graceful "project unavailable" state using the i18n key `workspace.notFound` and SHALL NOT crash or render a raw error.
7. WHILE the project summary is being hydrated, THE system SHALL render a non-blocking loading state and SHALL NOT render a raw i18n key.

### Requirement 2: Working-project state (client-only)

**User Story:** As a user, I want the app to remember which project I am working on across reloads, so that I can always return to it without re-selecting.

#### Acceptance Criteria

1. THE system SHALL persist the working-project id in `localStorage` under the exact key `foremen.workingProjectId`, and SHALL NOT create any backend entity, table, migration, or ABAC resource for it.
2. THE system SHALL provide a state module `src/features/project-workspace/state/workingProject.ts` exposing `getWorkingProjectId()`, `setWorkingProjectId(projectId)`, and `clearWorkingProject()` over that `localStorage` key.
3. THE system SHALL provide a `useWorkingProject()` hook that returns the current working-project id, the hydrated project summary, a loading flag, and `setWorkingProject(projectId)` / `clearWorkingProject()` callbacks.
4. WHEN a working-project id is set, THE `useWorkingProject()` hook SHALL hydrate the project summary from the existing projects API (`GET /api/projects/{id}`).
5. WHEN the application reloads WHILE a working-project id is stored, THE system SHALL restore that id from `localStorage` so the working project survives the reload.
6. IF the stored working-project id no longer resolves (project deleted, or the user lost access), THEN THE system SHALL clear the working-project state gracefully via `clearWorkingProject()` and SHALL NOT crash.
7. WHEN a project is opened via the projects-list row-click (Requirement 5), THE system SHALL set that project as the working project through the state module.
8. THE working-project id SHALL be held in a single shared client store observed by all `useWorkingProject()` consumers in a tab (not a per-hook copy), so that every consumer observes the same value and, WHEN any consumer calls `setWorkingProject(projectId)` or `clearWorkingProject()`, all `useWorkingProject()` consumers in the same tab SHALL re-render with the new value synchronously and without a reload — in addition to the cross-tab and reload persistence in criteria 1 and 5.

### Requirement 3: Always-visible working-project button (desktop/tablet)

**User Story:** As a desktop user, I want the working project always visible in the sidebar, so that I can see and reopen it from anywhere in the app.

#### Acceptance Criteria

1. WHERE the breakpoint is desktop or tablet, THE system SHALL render a working-project button/badge directly under the Dashboard nav entry in the `Sidebar`, living in the app shell (not inside a project route) so it is visible from every route.
2. WHILE a working project is set, THE working-project button SHALL show the working project's name and a status pill derived from the hydrated project summary.
3. WHEN the user clicks the working-project button WHILE a working project is set, THE system SHALL navigate to that project's workspace at `/projects/:projectId`.
4. WHILE no working project is set, THE working-project button SHALL show a "choose project" affordance labeled with i18n key `workspace.chooseProject`.
5. WHEN the user clicks the "choose project" affordance, THE system SHALL navigate to the `/projects` list.
6. WHILE a working project is set, THE working-project button SHALL expose a "change project" affordance labeled with i18n key `workspace.changeProject` that navigates to the `/projects` list.

### Requirement 4: Always-visible working-project surface (mobile)

**User Story:** As a mobile user, I want the working project always visible even though the sidebar is not persistent, so that I never lose track of the project I am in.

#### Acceptance Criteria

1. WHERE the breakpoint is mobile, THE system SHALL render a compact working-project chip in the `TopBar` so the working project is always visible without opening the drawer.
2. THE responsive rule SHALL follow `useBreakpoint`: desktop/tablet render the sidebar working-project button (Requirement 3) and mobile renders the top-bar chip, and the two surfaces SHALL NOT both be rendered at the same breakpoint.
3. WHEN the user activates the mobile working-project chip WHILE a working project is set, THE system SHALL navigate to that project's workspace at `/projects/:projectId`.
4. WHILE no working project is set, THE mobile working-project chip SHALL show the `workspace.chooseProject` affordance that navigates to the `/projects` list.
5. THE mobile working-project chip and the desktop working-project button SHALL read the same `useWorkingProject()` state, so they display the same working project.
6. WHEN the working project changes from any surface or from the projects-list row-click, THE system SHALL update all working-project surfaces (the sidebar working-project button and the top-bar chip) immediately within the same tab with no reload, because the working-project state is a single shared client store observed by all `useWorkingProject()` consumers (Requirement 2.8).

### Requirement 5: Projects-list row-click override (projects feature only)

**User Story:** As a user, I want clicking a project row to open its workspace and set it as my working project, so that opening a project is a single action rather than opening an edit form.

#### Acceptance Criteria

1. WHEN the user clicks a project row in the `/projects` list, THE system SHALL navigate to `/projects/:projectId` for that project AND set it as the working project via the state module.
2. THE row-click SHALL NOT open the standard project edit form.
3. THE system SHALL make editing project fields a secondary action inside the workspace Overview, labeled with i18n key `workspace.edit`.
4. THE row-click override SHALL apply only to the projects feature's list and SHALL NOT change the default `DataTable` row-click behavior of any other feature.

### Requirement 6: Stage-dependent tab set

**User Story:** As a user, I want the workspace to show only the tabs relevant to the project's stage and to my permissions, so that I see the right in-project views without clutter.

#### Acceptance Criteria

1. THE system SHALL derive the project stage from `Project.status` via `stageOf(status)`, mapping `DRAFT`, `READY_TO_OFFER`, `OFFERED`, `APPROVED` to the design stage and `ACTIVE`, `COMPLETED` to the execution stage, so every status maps to exactly one stage.
2. THE system SHALL declare the design-stage tabs in order: `overview`, `readiness`, `pricing`, `rooms`, `estimate`, `scheduleDesign`, `contract`.
3. THE system SHALL declare the execution-stage tabs in order: `overview`, `procurement`, `documents`, `planActual`, `payroll`, `amendments`, `priceHistory`.
4. THE system SHALL provide `resolveWorkspaceTabs(project, user)` that returns the ordered tab list for the project's stage, filtered by the mandatory ABAC per-tab permission predicate using `usePermission`, identical to `isNavItemVisible(item, hasPermission)` in `src/config/navigation.ts`.
5. THE tab set returned by `resolveWorkspaceTabs(project, user)` SHALL equal exactly the current stage's tabs intersected with the tabs the user is permitted to see (order-preserving), so no cross-stage tab is shown and no permitted in-stage tab is hidden.
6. THE system SHALL treat `requiredPermission` as a mandatory field on every tab, and SHALL include a tab in the resolved set only WHILE `usePermission` grants that tab's `(resource, operation)` requirement, with no tab exempt from permission filtering (there is no "always visible for its stage" mode).
7. THE returned tab list SHALL preserve the declaration order of `WORKSPACE_TABS`.
8. WHEN the user clicks a tab in the strip, THE system SHALL navigate to `/projects/:projectId/:tab` so the URL is updated to the clicked tab's `key`.
9. THE resolved and ABAC-validated tab set returned by this Requirement SHALL constrain which `:tab` values are valid for the project's stage, and any `:tab` value outside that set SHALL be handled per Requirement 12.
10. WHILE a project is in the execution stage, THE system SHALL provide a separate grouped "design tabs" selector (a dropdown/menu) rendered as the LAST element of the tab strip, exposing the design-stage tabs (`readiness`, `pricing`, `rooms`, `estimate`, `scheduleDesign`, `contract` — excluding the common `overview`, which is already in the main strip) filtered by the same mandatory ABAC per-tab permission predicate as Requirement 6.4, in `WORKSPACE_TABS` declaration order; and THE selector SHALL be rendered ONLY IF at least one design tab is permitted (≥ 1 available), otherwise it SHALL be hidden.
11. WHEN the user selects an item in the execution-stage design-tabs selector, THE system SHALL navigate to `/projects/:projectId/:tab` for that design tab's `key` and write the tab `key` to per-project tab memory, exactly like a normal tab click (Requirements 6.8, 12.3).
12. WHILE a project is in the design stage, THE system SHALL NOT render the separate design-tabs selector (the design tabs are already the main strip).
13. A design tab surfaced via the execution-stage design-tabs selector SHALL be a valid `:tab` value for that execution project, and active-tab resolution (Requirement 12) SHALL treat the valid set as the resolved stage tabs (Requirement 6.5) UNION the design-selector tabs (criterion 6.10).

### Requirement 7: Tab host & placeholders

**User Story:** As a developer of later FOR-05 child specs, I want a stable tab host and tab contract with placeholder panels, so that I can plug real tab content in without changing the shell.

#### Acceptance Criteria

1. THE system SHALL render a responsive local tab strip: a horizontal strip on desktop and a scrollable/segmented strip with an overflow affordance on mobile.
2. THE system SHALL lazy-load the panel component for the active tab and render it inside a `SuspenseWrapper`-style boundary so a panel load failure does not crash the shell.
3. THE system SHALL define the workspace tab contract as `WORKSPACE_TABS`, where each tab declares `key`, `stage`, `owner`, a **mandatory** `requiredPermission` `{ resource, operation }`, `labelKey`, and a `lazy` component, as the single ordered source of truth consumed by `resolveWorkspaceTabs`.
4. THE system SHALL declare a mandatory `requiredPermission` on EVERY tab in `WORKSPACE_TABS` — `overview`, `readiness`, `pricing`, `rooms`, `estimate`, `scheduleDesign`, `contract`, `procurement`, `documents`, `planActual`, `payroll`, `amendments`, and `priceHistory` — with no tab omitting it, using the following `(resource, operation READ)` pairs where the resource is the tab's primary child entity: `overview` → `PROJECTS`, `readiness` → `PROJECTS`, `pricing` → `PROJECT_PRICING`, `rooms` → `ROOMS`, `estimate` → `ESTIMATE`, `scheduleDesign` → `WORK_SCHEDULE`, `contract` → `CONTRACTS`, `procurement` → `DELIVERIES`, `documents` → `DOCUMENTS`, `planActual` → `WORK_REPORTS`, `payroll` → `PAYROLL`, `amendments` → `AMENDMENTS`, `priceHistory` → `PRICE_HISTORY`, so that `overview` and `readiness` reuse the already-seeded `PROJECTS` resource and the workspace entry is never hidden from a user who can read the project.
5. FOR each tab in the resolved set, FOR-05-01 SHALL render a stable placeholder panel keyed by the tab `key`, so later child specs can replace the placeholder without changing the tab contract.
6. THE system SHALL render a minimal Overview panel for the `overview` tab that shows the project name, status pill, and the `workspace.edit` affordance (Requirement 5.3).
7. THE tab host SHALL derive the active tab from the `:tab` URL segment; and WHEN the bare `/projects/:projectId` route is used, THE tab host SHALL use the resolved tab from Requirement 12 (priority URL > per-project memory > first stage tab = `overview`).
8. WHEN the user selects a tab, THE tab host SHALL update the URL via navigation to `/projects/:projectId/:tab` (not merely update internal state).

### Requirement 8: Readiness widget shell (design stage)

**User Story:** As a design-stage user, I want a readiness widget in the workspace header, so that I can see how close the project is to being ready to sign.

#### Acceptance Criteria

1. WHILE the project is in the design stage, THE system SHALL render a readiness widget shell in the workspace header showing a readiness donut and a gate checklist.
2. THE gate checklist SHALL render each gate item with a done / partial / blocked status using i18n keys `workspace.gate.done`, `workspace.gate.partial`, and `workspace.gate.blocked`, and SHALL show a headline percentage.
3. THE readiness widget SHALL consume a readiness DTO, which MAY be a stub/placeholder in FOR-05-01 until the FOR-05 readiness endpoint lands.
4. WHILE the readiness DTO is stubbed or unavailable, THE readiness widget SHALL render its shell gracefully without crashing and without rendering a raw i18n key, labeled with i18n key `workspace.readiness`.
5. WHILE the project is in the execution stage, THE system SHALL NOT render the design-stage readiness widget.

### Requirement 9: Mode caption

**User Story:** As a user, I want the workspace header to state whether the project is in design or execution, so that the mode is unambiguous.

#### Acceptance Criteria

1. THE system SHALL render a mode caption in the workspace header derived from `stageOf(project.status)`.
2. WHILE the project is in the design stage, THE mode caption SHALL render the i18n key `workspace.mode.design` ("Projektowanie" / "Проектирование").
3. WHILE the project is in the execution stage, THE mode caption SHALL render the i18n key `workspace.mode.execution` ("W realizacji" / "В работе").

### Requirement 10: i18n single active language

**User Story:** As a Polish- or Russian-speaking user, I want all workspace text in one active language at a time, so that the UI is never a mix of both.

#### Acceptance Criteria

1. THE system SHALL define all new strings under a `workspace.*` namespace present at parity in BOTH `locales/pl.json` and `locales/ru.json`, and SHALL render a single active language at a time (never dual labels).
2. THE system SHALL NOT render any raw i18n key for any `workspace.*` string.
3. THE `workspace.*` namespace SHALL include the following keys with the given PL / RU values:
   - `workspace.workingProject` = "Projekt roboczy" / "Рабочий проект"
   - `workspace.chooseProject` = "Wybierz projekt" / "Выбрать проект"
   - `workspace.changeProject` = "Zmień projekt" / "Сменить проект"
   - `workspace.designTabs` = "Projektowanie" / "Проектирование"
   - `workspace.readiness` = "Gotowość do podpisania" / "Готовность к подписанию"
   - `workspace.mode.design` = "Projektowanie" / "Проектирование"
   - `workspace.mode.execution` = "W realizacji" / "В работе"
   - `workspace.edit` = "Edytuj projekt" / "Редактировать проект"
   - `workspace.notFound` = "Projekt niedostępny" / "Проект недоступен"
   - `workspace.gate.done` = "Gotowe" / "Готово"
   - `workspace.gate.partial` = "Częściowo" / "Частично"
   - `workspace.gate.blocked` = "Zablokowane" / "Блок"
4. THE `workspace.tab.*` keys SHALL be defined with the given PL / RU values:
   - `workspace.tab.overview` = "Przegląd" / "Обзор"
   - `workspace.tab.readiness` = "Gotowość" / "Готовность"
   - `workspace.tab.pricing` = "Cennik" / "Расценки"
   - `workspace.tab.rooms` = "Pomieszczenia" / "Помещения"
   - `workspace.tab.estimate` = "Kosztorys" / "Смета"
   - `workspace.tab.scheduleDesign` = "Harmonogram" / "График"
   - `workspace.tab.contract` = "Umowa" / "Договор"
   - `workspace.tab.procurement` = "Zakupy" / "Закупки"
   - `workspace.tab.documents` = "Dokumenty" / "Документы"
   - `workspace.tab.planActual` = "Plan/wykonanie" / "План-факт"
   - `workspace.tab.payroll` = "Wynagrodzenia" / "Расчёт ЗП"
   - `workspace.tab.amendments` = "Aneksy" / "Амендменты"
   - `workspace.tab.priceHistory` = "Historia cen" / "История цен"
5. THE `workspace.*` and `workspace.tab.*` key sets SHALL be covered by a localization test asserting identical keys in `pl.json` and `ru.json`, all non-empty, with no raw key rendered.

### Requirement 11: Dependencies & graceful degradation

**User Story:** As a maintainer, I want the workspace shell to depend cleanly on the projects feature and degrade gracefully when it is not fully landed, so that FOR-05-01 can ship ahead of every sibling.

#### Acceptance Criteria

1. THE system SHALL declare a hard dependency on FOR-04-13 (the `Project` entity, the `/projects` list, and the projects API) for working-project hydration and the projects-list row-click override.
2. THE tab contents for every non-Overview tab SHALL come from sibling FOR-05 child specs; FOR-05-01 SHALL provide only stable placeholder panels (Requirement 7.4).
3. IF the projects API is not yet available, THEN THE working-project surface SHALL degrade gracefully by hiding the working-project button/chip or showing the `workspace.chooseProject` affordance, and SHALL NOT crash.
4. IF hydration of a stored working-project id fails because the projects API is unavailable or the project is inaccessible, THEN THE system SHALL clear the working-project state gracefully (Requirement 2.6) and continue to render the app shell.
5. IF a tab's mandatory `requiredPermission` references a resource code not yet seeded in the backend — namely `PROJECT_PRICING`, `WORK_SCHEDULE`, `CONTRACTS`, `DOCUMENTS`, `WORK_REPORTS`, `PAYROLL`, `AMENDMENTS`, or `PRICE_HISTORY` — THEN `usePermission` SHALL NOT grant that requirement and THE system SHALL simply hide the corresponding tab, and SHALL NOT crash.
6. THE FOR-05-01 spec SHALL NOT seed the resource codes `PROJECT_PRICING`, `WORK_SCHEDULE`, `CONTRACTS`, `DOCUMENTS`, `WORK_REPORTS`, `PAYROLL`, `AMENDMENTS`, or `PRICE_HISTORY`; each SHALL be seeded (resource row + role grants) by the owning sibling spec (FOR-05, FOR-06, FOR-07, FOR-10, or FOR-11) per the entity-creation-rules checklist, at which point the corresponding tab SHALL become visible to permitted users, and this follow-up seeding SHALL be tracked as a task (TODO) so the tabs appear correctly once the resources are added.

### Requirement 12: URL-driven active tab & per-project persistence

**User Story:** As a foreman or worker, I want to share a link to a specific project tab and have each project remember my last tab, so that a shared link opens the exact view and returning to a project restores where I left off.

#### Acceptance Criteria

1. THE system SHALL encode the active tab as the `:tab` path segment of `/projects/:projectId/:tab`, where the `:tab` value is the tab `key` from `WORKSPACE_TABS` (e.g. `estimate`, `pricing`).
2. THE system SHALL resolve the active tab by a deterministic priority: (1) IF the URL `:tab` is a valid tab for the project's stage AND permitted by ABAC, THEN use it; (2) ELSE IF a per-project stored tab (`foremen.projectTab.<projectId>`) is valid for the stage and permitted, THEN use it; (3) ELSE use the first stage tab (`overview`) and normalize the URL via replace. FOR this criterion the set of valid tabs for the project SHALL be the resolved stage tabs (Requirement 6.5) UNION the execution-stage design-selector tabs (Requirement 6.10), so a design tab surfaced via the execution-stage design-tabs selector is a valid, permitted `:tab` for that execution project.
3. WHEN the user switches tabs, THE system SHALL update the URL to `/projects/:projectId/:tab` (navigation) AND write the tab `key` to `localStorage` under `foremen.projectTab.<projectId>`.
4. WHEN the user opens the bare `/projects/:projectId`, THE system SHALL resolve the active tab by the priority in criterion 2 and redirect (replace) to the explicit `/projects/:projectId/:tab`.
5. WHEN a user opens a shared link `/projects/:projectId/:tab` with a valid, permitted tab, THE system SHALL open that exact tab regardless of the per-project stored tab (the URL wins).
6. IF the `:tab` is unknown, not valid for the current stage, or not permitted by ABAC, THEN THE system SHALL fall back per the priority in criterion 2 (stored tab, else `overview`) and normalize the URL via replace, without crashing; here "valid for the current stage" means the tab is in the resolved stage tabs UNION the execution-stage design-selector tabs (criterion 2), so a permitted design tab reached via the execution-stage design-tabs selector is treated as valid and not normalized away.
7. WHEN the user returns to a project via the bare route AND a valid per-project stored tab exists, THE system SHALL restore that tab.
8. THE per-project tab memory SHALL be stored per `projectId` under `foremen.projectTab.<projectId>` and SHALL be independent per project, so switching projects restores each project's own last tab.
9. THE system SHALL ignore invalid or stale stored values gracefully and fall back per the priority in criterion 2.
10. THE system SHALL introduce NO new i18n strings for this behavior (the `:tab` slug is a technical identifier; visible tab labels remain the existing `workspace.tab.*` keys) AND NO backend entity (the tab memory is client-only `localStorage`).
