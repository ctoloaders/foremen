# Implementation Plan: FOR-05-11 — Project readiness (aggregated)

## Overview

This plan implements the design incrementally: the `ProjectReadinessContributor` superinterface and
the shared `Area_Contribution` / `ReadinessState` / `ReadinessMath` core (with property tests) →
the six area contributors (rooms, estimate, offer, documents, team, schedule) → the aggregator
service and controller (access gate, permission scoping, ordering, composition) → backend tests →
the frontend API/types/hook → the real `readiness` tab panel → the `ReadinessWidget` switch →
frontend i18n → parent docs → `test-cases.md`. Every correctness property from the design
(Properties 1–7) becomes a property-based test next to the code it validates.

**Entity-creation checklist** (`.kiro/steering/entity-creation-rules.md`): this spec introduces
**no new managed entity and no new ABAC resource** (Decision D7). The aggregate endpoint rides on the
existing `PROJECTS` resource and each contributor rides on its area's existing resource, so steps 1
(resource seed), 2 (ADMIN grant), and 4 (`getProjectIdPath`) do **not** apply. Step 3 applies only in
its spirit: the new controller carries `@PermissionResource("PROJECTS")` + `@PermissionOperation(READ)`
so `PermissionAnnotationValidator` accepts it.

**Two git repos** (`.kiro/steering/git-repo-structure.md`): backend, spec, and parent OVERVIEW /
design updates are committed from the **root repo**; everything under `foremen-frontend/src/**` is
committed from **inside `foremen-frontend/`**. Two commits in total.

**Tests** (`.kiro/steering/test-execution-rules.md`): never run the full suite; run only the affected
classes with `--tests`, redirect to `/tmp/for-05-11-test.log`, and read the JUnit XML under
`foremen-backend/build/test-results/test/`. Use `./gradlew compileJava` / `compileTestJava` for fast
checks and `getDiagnostics` for per-file checks. Frontend: `npx vitest --run <files>`.

## Tasks

- [x] 1. Readiness contract and pure math core
  - [x] 1.1 Define the `ProjectReadinessContributor` superinterface and carriers
    - Create `ProjectReadinessContributor` (`areaKey()`, `areaResource()`, `order()`,
      `contribute(Long projectId)`), the shared `ReadinessState { DONE, PARTIAL, BLOCKED }` enum, and
      the records `AreaContribution { key, nameKey, percentage, state, items }` and
      `OutstandingItem { descriptionKey, params, targetTab }` under `com.foremen.service.readiness`.
    - _Requirements: 1.1, 1.2, 1.4_

  - [x] 1.2 Implement `ReadinessMath` and its property tests (Properties 2, 3, 4)
    - `fallbackPercentage` (DONE 100 / PARTIAL 50 / BLOCKED 0), `overallPercentage`
      (round(mean), 0 for empty), `overallState` (BLOCKED > PARTIAL > DONE; empty → BLOCKED).
      jqwik: fallback mapping, equal-weight average with permutation invariance, state roll-up with
      permutation invariance.
    - _Requirements: 1.5, 4.3, 4.4_

- [x] 2. Area contributors
  - [x] 2.1 Implement `RoomsReadinessContributor` + its property test (Property 7, rooms)
    - `order 10`, `ROOMS`, `targetTab = rooms`, `nameKey = workspace.tab.rooms`. No rooms → BLOCKED/0
      + `readiness.rooms.none`; else `round(100 × withPositiveArea / total)`, DONE iff all have area,
      one `readiness.rooms.noDimensions {roomName}` per room without area, ordered by room id.
    - _Requirements: 1.3, 5.1, 5.7, 5.8_

  - [x] 2.2 Implement `EstimateReadinessContributor` + its property test (Property 7, estimate)
    - `order 20`, `ESTIMATE`, `targetTab = estimate`, `nameKey = workspace.tab.estimate`. No
      estimate/lines → BLOCKED/0 + `readiness.estimate.noWorks`; else percentage = mean of the
      priced sub-fraction and the BoM sub-fraction (BoM = 100% when no material lines); DONE iff all
      lines priced AND all material lines have consumption; items `readiness.estimate.unpricedLines
      {count}` and `readiness.estimate.bomIncomplete {count}` when nonzero. Traverse line → roomQtys
      → materials; counts only in params (no money).
    - _Requirements: 1.3, 4.8, 5.2, 5.7, 5.8_

  - [x] 2.3 Implement `OfferReadinessContributor`
    - `order 30`, `OFFERS`, `targetTab = pricing`, `nameKey = workspace.tab.offer`. Current offer via
      `OfferDao.findByProjectIdOrderByIdAsc`; map via `OfferVisibilityResolver`: APPROVED → DONE/100;
      SENT/CHANGES_REQUESTED/COUNTERED → PARTIAL/50 + `readiness.offer.onApproval`; else → BLOCKED/0
      + `readiness.offer.notReady`.
    - _Requirements: 5.3, 5.7_

  - [x] 2.4 Implement `DocumentsReadinessContributor`
    - `order 40`, `SIGNABLE_DOCUMENTS`, `targetTab = documentSigning`, `nameKey =
      workspace.tab.documentSigning`. Newest `CONTRACT_*`-typed `SignableDocument` via
      `SignableDocumentDao.findByProjectIdOrderByIdDesc`: SIGNED → DONE/100; PENDING_SIGNATURES →
      PARTIAL/50 + `readiness.documents.pending`; else → BLOCKED/0 + `readiness.documents.notReady`.
    - _Requirements: 5.4, 5.7_

  - [x] 2.5 Implement `TeamReadinessContributor` and `ScheduleReadinessContributor`
    - Team (`order 50`, `PROJECT_MEMBERS`, `targetTab = team`): delegate to
      `ProjectMemberService.readiness`; DONE→DONE/100, BLOCKED→BLOCKED/0 + `readiness.team.noForeman`.
      Schedule (`order 60`, `WORK_SCHEDULE`, `targetTab = scheduleDesign`): delegate to
      `ProjectScheduleService.readiness`; percentage `round(100 × scheduledCount / rowCount)` (0 for
      no rows); map state 1:1; item `readiness.schedule.unscheduledRows {count}` when not DONE.
    - _Requirements: 5.5, 5.6, 5.7_

- [x] 3. Aggregator service and controller
  - [x] 3.1 Implement `ProjectReadinessService`
    - Inject `List<ProjectReadinessContributor>`, `ForemenPermissionEvaluator`, and the
      project-access collaborator. `getReadiness(projectId)`: access gate (byte-identical 404 for
      missing/inaccessible, mirroring `ProjectScheduleService.loadAccessibleProject`), include a
      contributor iff `isAllowed(role, areaResource, READ)` (ADMIN bypass), call each included
      `contribute`, sort by `order()`, compose via `ReadinessMath`. `@Transactional(readOnly=true)`;
      a throwing contributor is logged and omitted.
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 6.1, 6.2, 6.3, 6.5, 7.1, 7.4, 11.2_

  - [x] 3.2 Define `ProjectReadinessView` DTO and `ProjectReadinessController`
    - `ProjectReadinessView { projectId, overallPercentage, overallState, areas[] }` and the area/item
      view records. Controller `@RequestMapping("/api/projects") @PermissionResource("PROJECTS")`,
      `GET /{projectId}/readiness @PermissionOperation(READ)` → service.
    - _Requirements: 2.1, 2.2, 3.4, 4.1, 4.6, 7.2, 7.3_

  - [x] 3.3 Write service unit/property tests (Properties 1, 5, 6)
    - Contribution well-formedness contract across contributors; order independence of composition
      and declared-order output; permission scoping (included areas = readable areas, nothing leaks
      about omitted areas); empty-areas → 200 + 0% + BLOCKED; 404 behavior.
    - _Requirements: 1.2, 1.3, 3.2, 3.3, 4.4, 6.2, 6.3, 6.4, 7.1, 7.4_

- [x] 4. Checkpoint — Ensure all tests pass
  - Run tasks 1.2, 2.x, and 3.3 test classes only (see Overview). Ask the user if questions arise.

- [x] 5. Backend ABAC and integration tests
  - [x] 5.1 Write ABAC per-role integration test (Testcontainers)
    - 401 unauthenticated; `PROJECTS`-READ caller → 200; a caller with only `ROOMS` READ sees only
      the rooms area and a headline over that one area; a caller with no area READ → 200 + empty
      areas + 0% + BLOCKED; 404 for non-member and non-existent project with identical bodies.
    - _Requirements: 2.3, 2.4, 3.2, 3.3, 6.2, 6.4_

  - [x] 5.2 Write an end-to-end readiness integration test
    - A project walked through states (no rooms/estimate → add rooms + priced lines → send offer →
      approve → add foreman → schedule rows) and assert the aggregated `overallPercentage` /
      `overallState` / per-area items change accordingly; confirm the read performs no write / no
      audit row.
    - _Requirements: 4.3, 4.4, 4.7, 5.1, 5.2, 5.3, 5.5, 5.6, 11.1, 11.2_

  - [x] 5.3 Update the FOR-03-08 enumeration test
    - Add `ProjectReadinessController` → `PROJECTS` READ to the guarded-controller list; assert **no**
      new resource row is seeded.
    - _Requirements: 2.2, 12.4_

- [x] 6. Checkpoint — Ensure all tests pass
  - Run tasks 3.3, 5.1, 5.2, 5.3 test classes only. Ask the user if questions arise.

- [x] 7. Frontend API, types, and hook
  - [x] 7.1 Implement the readiness feature module
    - `features/project-readiness/`: `types/index.ts` (`ReadinessState`, `OutstandingItemView`,
      `AreaContributionView`, `ProjectReadinessView`), `api/readinessApi.ts`
      (`GET /api/projects/:projectId/readiness`), `api/query-hooks.ts` (`useProjectReadiness` +
      `readinessKeys.view(projectId)`), and `describeOutstandingItem.ts`.
    - _Requirements: 9.1, 10.3_

- [x] 8. The `readiness` tab panel
  - [x] 8.1 Wire the real panel into `WORKSPACE_TABS`
    - Point the `readiness` tab's `lazy` at the new `ReadinessTab`, keeping key, stage (`design`),
      `PROJECTS` READ gate, label key, and position unchanged. Existing resolver tests must pass
      unchanged.
    - _Requirements: 8.1, 12.2_

  - [x] 8.2 Implement `ReadinessTab`, `ReadinessAreaRow`, `ReadinessDonut`
    - Overall donut + ordered area rows (localized name, localized state label, percentage,
      outstanding items as localized sentences); click an item/row → navigate to its `targetTab`;
      loading skeleton, localized error + retry, localized empty state; only localized text (resolve
      API keys + params).
    - _Requirements: 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_

  - [x] 8.3 Invalidate the aggregate on area changes
    - Add `readinessKeys.view(projectId)` to the invalidation sets of the area mutations (team,
      schedule, estimate, offer, signing, rooms) so the tab and widget refetch after a change.
    - _Requirements: 9.6_

  - [x] 8.4 Write `ReadinessTab` component tests
    - Loading/error/empty/populated, jump-to-tab navigation, localized state labels and item
      sentences, never a raw key.
    - _Requirements: 8.3, 8.4, 8.5, 8.6, 8.7_

- [x] 9. The `ReadinessWidget` switch
  - [x] 9.1 Switch `ReadinessWidget` to the aggregated endpoint
    - Replace the per-area fan-out (`useTeamReadiness` / `useScheduleReadiness`) with
      `useProjectReadiness`; render the donut from `overallPercentage` and one chip per area
      (`name: state`); omitted areas absent and uncounted; localized failure note; keep the
      design-stage render gate at the `ProjectWorkspacePage` call site; drop the obsolete `readiness`
      prop / base-gate plumbing.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [x] 9.2 Update `ReadinessWidget` tests
    - Donut from overall %, one chip per returned area, omitted area absent, failure note, no crash;
      stage gating unchanged at the call site.
    - _Requirements: 9.2, 9.4, 9.5_

- [x] 10. Frontend localization
  - [x] 10.1 Add the `readiness.*` keys to `pl.json` and `ru.json`
    - Exactly the design key table (`readiness.headline`, `readiness.state.*`, `readiness.area.goToTab`,
      the panel states, `readiness.widget.loadError`, and every `readiness.<area>.*` description with
      its placeholders); same key set and placeholders in both files; locale-parity test passes.
    - _Requirements: 10.1, 10.2, 10.4_

- [x] 11. Parent documentation updates
  - [x] 11.1 Update OVERVIEW and parent design
    - `FOR-05-project-estimate/OVERVIEW.md`: mark row 11 (`FOR-05-11-project-readiness`) implemented.
      `FOR-05-project-estimate/design.md` §6.3: note the contributor-based architecture chosen in
      this spec (area-owned readiness, equal-weight average, `PROJECTS`-READ aggregate endpoint).
    - _Requirements: 12.3_

- [x] 12. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression
    group, MD report template). This is a UI + API spec: API scenarios against the Dockerized app
    (`http://localhost:8080`) for `GET /api/projects/{projectId}/readiness` (per-role scoping,
    404 scoping, state/percentage per area) and browser-engine scenarios for the `readiness` tab and
    the `ReadinessWidget` (`http://localhost:3000`). Result artifacts are MD reports with tables.
    Include at least one case for each of Requirements 1–11.
  - _Requirements: 12.5_

- [x] 13. Final checkpoint — Ensure all tests pass
  - Run only the test classes and Vitest files added or changed by this spec. Ask the user if
    questions arise.
