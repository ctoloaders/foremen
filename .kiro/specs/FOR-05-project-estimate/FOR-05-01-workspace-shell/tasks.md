# Implementation Plan: FOR-05-01 — Project Workspace Shell

## Overview

This plan builds the frontend-only project workspace shell in
`foremen-frontend/src` as a series of incremental, dependency-ordered coding steps.
It starts from the pure, side-effect-free contract and resolver layer (types, `WORKSPACE_TABS`,
`stageOf`, `resolveWorkspaceTabs`, `resolveActiveTab`), then the client-only `localStorage` state
modules (`workingProject.ts` + `useWorkingProject`, `projectTabMemory.ts`), then the presentational
workspace components (header, mode caption, readiness widget shell, tab host, panels, unavailable
state), then the app-shell surfaces (`WorkingProjectButton`, `WorkingProjectChip`) wired into the
existing `Sidebar` / `TopBar`, then the router changes with `PROJECTS`/`READ` gating and the
projects-list row-click override, then i18n parity, and finally the property-based tests and the
Russian `test-cases.md` artifact.

Each step builds on the previous ones and ends by wiring the new code into an existing surface, so
no code is left orphaned. Property tests validate the five correctness properties from the design;
they are placed next to the pure logic they test. Test sub-tasks are marked optional with `*`.

Implementation language: **TypeScript / React** (the design uses concrete TS throughout; no
language selection was required).

## Tasks

- [x] 1. Establish the workspace tab contract and types
  - [x] 1.1 Define workspace types in `src/features/project-workspace/types.ts`
    - Add `WorkspaceStage = 'design' | 'execution' | 'common'`, the 13-member `WorkspaceTabKey`
      union, the `WorkspaceTab` interface (`key`, `stage`, `owner`, `requiredPermission?`,
      `labelKey`, `lazy`), and `ProjectTabProps = { projectId: string; project: ProjectReadDto }`.
    - Reuse `ProjectReadDto` from `src/features/projects/types`.
    - _Requirements: 6.2, 6.3, 7.3, 7.4_

  - [x] 1.2 Declare `WORKSPACE_TABS` in `src/features/project-workspace/workspaceTabs.ts`
    - Declare the ordered array as the single source of truth, in exact declaration order:
      `overview` (common); design `readiness`, `pricing`, `rooms`, `estimate`, `scheduleDesign`,
      `contract`; execution `procurement`, `documents`, `planActual`, `payroll`, `amendments`,
      `priceHistory`.
    - Attach each tab's `stage`, `owner`, `labelKey` (`workspace.tab.<key>`), and a **mandatory**
      `requiredPermission` `{ resource, operation: 'READ' }` on EVERY tab — no tab is exempt and
      there is no "always visible" mode. Every tab is permission-gated exactly like a main-menu
      entry (`isNavItemVisible`). Use these `(resource, operation READ)` pairs: `overview`→PROJECTS,
      `readiness`→PROJECTS, `pricing`→PROJECT_PRICING, `rooms`→ROOMS, `estimate`→ESTIMATE,
      `scheduleDesign`→WORK_SCHEDULE, `contract`→CONTRACTS, `procurement`→DELIVERIES,
      `documents`→DOCUMENTS, `planActual`→WORK_REPORTS, `payroll`→PAYROLL, `amendments`→AMENDMENTS,
      `priceHistory`→PRICE_HISTORY. `overview` and `readiness` reuse the already-seeded `PROJECTS`
      requirement so the workspace entry is never hidden from a user who can read the project.
    - Point every tab's `lazy` at `PlaceholderTab` except `overview`, which points at `OverviewTab`
      (both created in a later task; use lazy dynamic imports so the reference resolves at load time).
    - _Requirements: 6.2, 6.3, 6.6, 6.7, 7.3, 7.4_

- [x] 2. Implement the pure resolver layer
  - [x] 2.1 Implement `stageOf` in `src/features/project-workspace/state/stageOf.ts`
    - Export `WorkspaceProjectStage = 'design' | 'execution'` and a total `stageOf(status: string)`
      returning `'execution'` iff status ∈ `{ACTIVE, COMPLETED}`, else `'design'`; never throw.
    - _Requirements: 6.1_

  - [x] 2.2 Write property test for `stageOf`
    - **Property 1: `stageOf` is total and single-valued**
    - **Validates: Requirements 6.1**
    - Use `fast-check` (≥100 iterations); generate shipped statuses, parent-design statuses, and
      arbitrary strings; assert the result is always `'design'|'execution'`, execution ⟺
      `ACTIVE|COMPLETED`, and never throws. Tag `Feature: FOR-05-01-workspace-shell, Property 1`.

  - [x] 2.3 Implement `resolveWorkspaceTabs` in `src/features/project-workspace/state/resolveWorkspaceTabs.ts`
    - Filter `WORKSPACE_TABS` to `stage === 'common' || stage === stageOf(project.status)`, then
      apply an **unconditional** ABAC filter `hasPermission(t.requiredPermission.resource,
      t.requiredPermission.operation)` — every tab has a mandatory `requiredPermission`, so there
      is no `requiredPermission == null` branch, exactly like `isNavItemVisible(item, hasPermission)`
      gates a main-menu entry. Preserve declaration order so the result is exactly the stage ∩
      permitted set. Signature takes `(project, hasPermission: (resource, operation) => boolean)`.
    - _Requirements: 6.4, 6.5, 6.6, 6.7_

  - [x] 2.4 Write property test for `resolveWorkspaceTabs`
    - **Property 2: Resolved tabs = stage tabs ∩ permitted tabs, in declaration order**
    - **Validates: Requirements 6.4, 6.5, 6.6, 6.7**
    - Generate a random project status and a random `hasPermission` predicate (random grant subset);
      assert the result equals the hand-computed stage∩permitted set and is a subsequence of
      `WORKSPACE_TABS`. ≥100 iterations. Tag `Feature: FOR-05-01-workspace-shell, Property 2`.

  - [x] 2.5 Implement `resolveActiveTab` in `src/features/project-workspace/state/resolveActiveTab.ts`
    - Compute `visible = resolveWorkspaceTabs(project, hasPermission)`; return the URL tab when it is
      a visible key (`normalizedFromUrl: false`); else the stored `getProjectTab(project.id)` when
      valid (`normalizedFromUrl: true`); else `overview` (`normalizedFromUrl: true`). Reads
      `getProjectTab` from the memory module (task 4.1).
    - _Requirements: 12.2, 12.5, 12.6, 12.9_

  - [x] 2.6 Write property tests for `resolveActiveTab`
    - **Property 3: URL-tab resolution priority** and **Property 4: A valid, permitted URL tab
      always wins over stored memory**
    - **Validates: Requirements 12.1, 12.2, 12.5, 12.6, 12.9**
    - Generate project, predicate, and arbitrary `urlTab`/stored tab from valid keys plus junk;
      assert priority order, that the returned key is always in `resolveWorkspaceTabs`, and that a
      valid+permitted URL tab wins over any stored value. ≥100 iterations. Tag
      `Feature: FOR-05-01-workspace-shell, Property 3` / `Property 4`.

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement client-only state modules (localStorage)
  - [x] 4.1 Implement per-project tab memory in `src/features/project-workspace/state/projectTabMemory.ts`
    - Export `projectTabKey(projectId)` = `foremen.projectTab.<projectId>`, plus null-safe
      `getProjectTab(projectId)` and `setProjectTab(projectId, tab)`, all `localStorage` access
      wrapped in try/catch so a disabled/full store degrades to "no stored tab".
    - _Requirements: 12.3, 12.8, 12.9, 12.10_

  - [x] 4.2 Write property test for per-project tab memory
    - **Property 5: Per-project tab memory is independent per project**
    - **Validates: Requirements 12.8**
    - Generate pairs of distinct ids and tab values; assert `setProjectTab` on one never changes
      `getProjectTab` for the other. ≥100 iterations. Tag
      `Feature: FOR-05-01-workspace-shell, Property 5`.

  - [x] 4.3 Implement working-project state + hook in `src/features/project-workspace/state/workingProject.ts`
    - Export `WORKING_PROJECT_KEY = 'foremen.workingProjectId'` and null-safe
      `getWorkingProjectId()` / `setWorkingProjectId(id)` / `clearWorkingProject()` over that key
      (try/catch guarded).
    - Add the `useWorkingProject()` hook returning `{ workingProjectId, project, isLoading,
      setWorkingProject, clearWorkingProject }`; hydrate the summary via the existing
      `GET /api/projects/{id}` (reuse the projects feature API client / query hook); on 403/404 or
      API-unavailable call `clearWorkingProject()` and resolve `project: null` without throwing;
      subscribe to the `storage` event to keep surfaces in sync across tabs.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 11.3, 11.4_

  - [x] 4.4 Write unit tests for working-project state and hook
    - Test set/get/clear round-trips; hydration success; 403/404 → `clearWorkingProject`; and the
      `localStorage`-unavailable path (degrades to "no working project").
    - _Requirements: 2.2, 2.4, 2.5, 2.6, 11.3, 11.4_

- [x] 5. Implement workspace panels and presentational components
  - [x] 5.1 Implement `PlaceholderTab` and `OverviewTab` in `src/features/project-workspace/components/tabs/`
    - `PlaceholderTab.tsx`: a generic stable panel accepting `ProjectTabProps`, keyed by tab `key`,
      that later child specs replace without touching the contract.
    - `OverviewTab.tsx`: a minimal real panel showing the project name, status pill
      (`ProjectStatusBadge`), and the `workspace.edit` affordance opening the existing project edit
      form. Both are the lazy targets referenced by `WORKSPACE_TABS` (task 1.2).
    - _Requirements: 5.3, 7.4, 7.5_

  - [x] 5.2 Implement `ProjectUnavailable` in `src/features/project-workspace/components/ProjectUnavailable.tsx`
    - Render the graceful "project unavailable" state using i18n key `workspace.notFound`; never a
      raw error or raw key.
    - _Requirements: 1.6_

  - [x] 5.3 Implement `ReadinessWidget` shell in `src/features/project-workspace/components/ReadinessWidget.tsx`
    - Render a readiness donut + gate checklist consuming a `ReadinessDto`
      (`{ headlinePct, gates: { key, state: 'done'|'partial'|'blocked' }[] }`) that MAY be stubbed;
      render gate states via `workspace.gate.done`/`.partial`/`.blocked` and a headline percentage;
      label the shell `workspace.readiness`; render gracefully when stubbed/unavailable (no crash,
      no raw key).
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [x] 5.4 Write unit tests for `ReadinessWidget`
    - Renders the shell with a stubbed DTO without crashing; renders gate labels and headline pct.
    - _Requirements: 8.2, 8.4_

  - [x] 5.5 Implement `WorkspaceHeader` + mode caption in `src/features/project-workspace/components/WorkspaceHeader.tsx`
    - Render project name, status pill (`ProjectStatusBadge`), the `workspace.edit` affordance, and
      the mode caption derived from `stageOf(project.status)`: `workspace.mode.design` in design
      stage, `workspace.mode.execution` in execution stage.
    - _Requirements: 5.3, 9.1, 9.2, 9.3_

  - [x] 5.6 Implement `WorkspaceTabs` tab host in `src/features/project-workspace/components/WorkspaceTabs.tsx`
    - Render the resolved tab set responsively via `useBreakpoint`: a horizontal strip on
      desktop/tablet, a scrollable/segmented control with an overflow menu on mobile. Derive the
      active tab from the `:tab` segment. Each panel renders the tab's `lazy` inside a
      `SuspenseWrapper`-style error+Suspense boundary, passing `ProjectTabProps`. On tab click,
      `navigate` to `/projects/:projectId/:tab` and `setProjectTab(projectId, key)`.
    - _Requirements: 6.8, 7.1, 7.2, 7.6, 7.7, 12.3_

  - [x] 5.7 Write unit tests for `WorkspaceTabs`
    - Desktop strip vs mobile segmented+overflow rendering; tab click updates URL and writes memory.
    - _Requirements: 7.1, 7.7, 12.3_

- [x] 6. Implement the workspace page and wire the panels + resolvers together
  - [x] 6.1 Implement `ProjectWorkspacePage` in `src/features/project-workspace/pages/ProjectWorkspacePage.tsx`
    - Read `:projectId` / `:tab`; set the working project on open; hydrate the summary via
      `useWorkingProject()`. While loading render a non-blocking loading state (no raw key); on
      403/404/unresolved render `ProjectUnavailable`. Compute
      `tabs = resolveWorkspaceTabs(project, hasPermission)` and
      `active = resolveActiveTab(project, hasPermission, urlTab)`; when `urlTab` is absent or differs
      from `active`, `navigate(..., { replace: true })` to `/projects/:projectId/:active`. Render
      `WorkspaceHeader` (+ `ReadinessWidget` only in design stage) and `WorkspaceTabs` with the
      active panel.
    - _Requirements: 1.5, 1.6, 1.7, 2.7, 7.6, 8.1, 8.5, 12.4, 12.6, 12.7_

  - [x] 6.2 Write unit tests for `ProjectWorkspacePage`
    - Bare `/projects/:projectId` redirects (replace) to resolved `:tab`; unresolved id →
      `workspace.notFound`; loading state renders no raw key; design stage shows the readiness
      widget and execution stage does not.
    - _Requirements: 1.6, 1.7, 8.1, 8.5, 12.4_

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement and wire the always-visible working-project surfaces
  - [x] 8.1 Implement `WorkingProjectButton` in `src/components/shell/WorkingProjectButton.tsx`
    - Desktop/tablet sidebar button reading `useWorkingProject()`; when set, show name + status pill
      (`ProjectStatusBadge`) and navigate to `/projects/:projectId` on click, plus a "change project"
      affordance (`workspace.changeProject`) routing to `/projects`; when unset, show the
      `workspace.chooseProject` affordance routing to `/projects`; degrade to `chooseProject`/hide if
      the API is unavailable.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 11.3_

  - [x] 8.2 Implement `WorkingProjectChip` in `src/components/shell/WorkingProjectChip.tsx`
    - Mobile TopBar chip reading the same `useWorkingProject()` state; compact status dot + truncated
      name navigating to `/projects/:projectId` when set; `workspace.chooseProject` routing to
      `/projects` when unset; graceful fallback when API unavailable.
    - _Requirements: 4.1, 4.3, 4.4, 4.5, 11.3_

  - [x] 8.3 Wire the surfaces into `Sidebar` and `TopBar` with breakpoint mutual exclusivity
    - Render `WorkingProjectButton` in `Sidebar` directly under the Dashboard nav entry (desktop/
      tablet) and `WorkingProjectChip` in `TopBar` left of the language control (mobile), selected by
      `useBreakpoint` so exactly one renders per breakpoint.
    - _Requirements: 3.1, 4.1, 4.2, 4.5_

  - [x] 8.4 Write unit tests for the working-project surfaces
    - Render name + status pill when set, `chooseProject` when not; breakpoint mutual exclusivity
      (only one renders per breakpoint); click navigation targets.
    - _Requirements: 3.2, 3.3, 3.4, 4.2, 4.3, 4.4, 4.5_

- [x] 9. Add the router changes and the projects-list row-click override
  - [x] 9.1 Add the workspace routes in `src/app/router.tsx` with `PROJECTS`/`READ` gating
    - Add `React.lazy` import of `ProjectWorkspacePage`; add `projects/:projectId` and
      `projects/:projectId/:tab` inside the same pathless `PermissionGuard` child array, each wrapped
      in `SuspenseWrapper`; leave `/projects` unchanged; register both new paths in the
      route→requirement map with `{ resource: 'PROJECTS', operation: 'READ' }`.
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 9.2 Override the projects-list row-click in `src/features/projects/.../ProjectsList.tsx`
    - Change the single `onRowClick` wiring so a row-click calls `setWorkingProject(String(id))` and
      `navigate('/projects/:id')` instead of opening the edit form; keep the per-row edit/delete
      `rowActions` (which already `stopPropagation`); do not change the generic `DataTable`
      `onRowClick` contract or any other feature's behavior.
    - _Requirements: 5.1, 5.2, 5.4, 2.7_

  - [x] 9.3 Write tests for the router gating and the row-click override
    - A user lacking `PROJECTS`/`READ` hitting `/projects/:projectId/:tab` is redirected to `/403`
      identically to `/projects`; row-click sets the working project + navigates to `/projects/:id`
      (not the edit form); edit/delete row actions still fire and `stopPropagation`; other features'
      `DataTable` row-click behavior unchanged (regression).
    - _Requirements: 1.4, 5.1, 5.2, 5.4_

- [x] 10. Add `workspace.*` i18n keys with a parity test
  - [x] 10.1 Add `workspace.*` and `workspace.tab.*` keys to `pl.json` and `ru.json`
    - Add all keys with exact PL/RU values from Requirements 10.3 and 10.4 under a `workspace.*`
      namespace in `foremen-frontend/src/lib/locales/pl.json` and `ru.json`, at parity, one active
      language at a time.
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

  - [x] 10.2 Write the i18n parity test
    - Following the existing `rooms.i18n.test.ts` flatten pattern, assert identical `workspace.*` /
      `workspace.tab.*` key sets in `pl.json` and `ru.json`, all values non-empty, no raw key.
    - _Requirements: 10.1, 10.5_

- [x] 11. Track sibling-spec seeding of workspace tab resources (TODO)
  - Add a short tracking note (a markdown section or a TODO checklist) to this spec — e.g. append a
    `## Follow-up: sibling-spec resource seeding (TODO)` section to `design.md`, or add a
    `SEEDING-TODO.md` in the spec folder — documenting that FOR-05-01 references but does **not** seed
    the tab resources `PROJECT_PRICING`, `WORK_SCHEDULE`, `CONTRACTS`, `DOCUMENTS`, `WORK_REPORTS`,
    `PAYROLL`, `AMENDMENTS`, and `PRICE_HISTORY`, so each corresponding tab (`pricing`,
    `scheduleDesign`, `contract`, `documents`, `planActual`, `payroll`, `amendments`, `priceHistory`)
    stays hidden until its owning sibling spec seeds the resource row + role grants per the
    `.kiro/steering/entity-creation-rules.md` checklist.
  - Record the owner mapping as a checklist so each tab appears correctly once its resource is added:
    `pricing`→FOR-05 (PROJECT_PRICING), `contract`→FOR-05 (CONTRACTS), `amendments`→FOR-05
    (AMENDMENTS), `priceHistory`→FOR-05 (PRICE_HISTORY), `scheduleDesign`→FOR-06 (WORK_SCHEDULE),
    `planActual`→FOR-06 (WORK_REPORTS), `procurement`→FOR-07 (DELIVERIES, already seeded),
    `documents`→FOR-10 (DOCUMENTS), `payroll`→FOR-11 (PAYROLL). Note `PROJECTS`, `ROOMS`, `ESTIMATE`,
    and `DELIVERIES` are already seeded today.
  - Clarify that FOR-05-01 itself performs NO backend seeding (no changeset, no resource row, no role
    grant), consistent with it being a frontend-only spec.
  - _Requirements: 7.4, 11.5, 11.6_

- [x] 12. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard.
    This is a UI spec, so scenarios are browser-engine scenarios (headless browser automation) whose
    run artifacts are MD report tables (step → expectation → actual → status), not screenshots.
    Write it in Russian, with feature grouping (working-project surface, workspace route & page,
    stage-dependent tabs, URL-driven active tab + per-project memory, readiness widget shell + mode
    caption, projects-list row-click override, i18n single active language), detailed numbered
    step-by-step scenarios, an explicit repeatability strategy (generator/teardown — e.g. per-run
    project ids and cleaned `localStorage` keys), a regression group, and the MD report template.
  - _Requirements: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12_

- [ ] 13. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; they are the unit,
  property, and integration tests.
- Each task references the specific requirements it satisfies for traceability.
- Property tests (tasks 2.2, 2.4, 2.6, 4.2) cover the five correctness properties from the design
  and are placed next to the pure logic they validate to catch errors early.
- Unit/example tests cover routing, hydration, the two shell surfaces, and i18n parity — the
  UI/integration concerns the design explicitly excludes from property testing.
- Every workspace tab carries a mandatory `requiredPermission` and is permission-gated exactly
  like a main-menu entry (`isNavItemVisible`); there is no "always visible" tab mode.
- FOR-05-01 introduces no backend entity, table, migration, or ABAC resource, and performs no
  backend seeding; the not-yet-seeded tab resources are owned by sibling specs and tracked as a
  TODO (task 11) so each tab appears once its resource + role grants are seeded.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "4.1"] },
    { "id": 3, "tasks": ["2.4", "2.5", "4.2", "4.3"] },
    { "id": 4, "tasks": ["2.6", "4.4", "5.1", "5.2", "5.3", "5.5", "8.1", "8.2"] },
    { "id": 5, "tasks": ["5.4", "5.6", "8.3", "9.2", "10.1"] },
    { "id": 6, "tasks": ["5.7", "6.1", "8.4", "9.1", "10.2"] },
    { "id": 7, "tasks": ["6.2", "9.3"] },
    { "id": 8, "tasks": ["11", "12"] }
  ]
}
```
