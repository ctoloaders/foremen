# FOR-05-12 — Project activation (hand over to work) — Tasks

Implementation plan for the readiness-gated project activation (`pre-activation → ACTIVE`). Each leaf
task is independently actionable, references requirements, and carries an explicit `_Depends on:_`.
The parallel-execution plan (waves) is at the end.

## Tasks

- [ ] 1. Backend: readiness gate foundation
  - [ ] 1.1 Add the all-areas readiness variant
    - Add `ProjectReadinessService.getReadinessAllAreas(Long projectId)` that computes readiness over
      EVERY contributor (skips the per-area READ filter), returning the same `ProjectReadinessResult`
      shape. Leave the existing caller-scoped `getReadiness` untouched. Factor the pre-activation
      status set (`DRAFT, READY_TO_OFFER, OFFERED, APPROVED`) into a shared constant reused by the
      activation gate and `ProjectActivationSignal`.
    - _Requirements: 2.3, 2.4_
    - _Depends on: none_
  - [ ] 1.2 Add the not-ready exception + 409 body + advice handler
    - Add `ProjectNotReadyException(List<AreaContribution> notReadyAreas)`, the
      `ProjectNotReadyError(message, areas[])` DTO (localized strings), and a
      `ForemenControllerAdvice#handleProjectNotReady` `@ExceptionHandler` returning HTTP 409 with the
      body, resolving `error.project.not.ready` + each area name / item message to the request
      locale. Mirror the `WorkReportBatchItemException` handler pattern.
    - _Requirements: 4.1, 4.2, 4.3, 4.4_
    - _Depends on: none_

- [ ] 2. Backend: activation service + endpoint
  - [ ] 2.1 Add `ProjectActivationService`
    - `activate(projectId, force)`: access gate (404) → UPDATE gate (403) → pre-activation check
      (idempotent for ACTIVE, illegal-transition 409 for terminal) → compute full readiness
      (`getReadinessAllAreas`) → block with `ProjectNotReadyException` when `<100%` unless
      (`force` && caller is ADMIN/MANAGER) → set `ACTIVE` + save. Role check reads the `ROLE_<code>`
      authority (ADMIN bypass + explicit MANAGER).
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.5, 3.1, 3.2, 3.3, 3.4_
    - _Depends on: 1.1, 1.2_
  - [ ] 2.2 Add `POST /api/projects/{id}/activate`
    - Add `ProjectController.activate(id, ActivateProjectRequest{force})` guarded
      `@RequiresPermission(PROJECTS, UPDATE)`, returning the updated project DTO; `force` defaults
      false when the body is omitted. App starts clean (`PermissionAnnotationValidator`).
    - _Requirements: 1.5, 3.1_
    - _Depends on: 2.1_
  - [ ] 2.3 Gate the generic update path
    - In `ProjectService` update guard, detect a `pre-activation → ACTIVE` status change and route it
      through the activation gate (non-force), yielding the same 409 not-ready outcome; leave every
      other status change and the automatic `ProjectActivationSignal` untouched.
    - _Requirements: 6.3, 7.1, 7.2, 7.3_
    - _Depends on: 2.1_

- [ ] 3. Backend: messages
  - [ ] 3.1 Add backend message codes (PL + RU)
    - Add `error.project.not.ready` (and `error.project.status.illegalTransition` if not already
      present) to `messages.properties` (PL) and `messages_ru.properties` (RU) at parity.
    - _Requirements: 8.1, 8.2_
    - _Depends on: none_

- [ ] 4. Frontend: api client + mutation + error parsing
  - [ ] 4.1 Add the activation client + hook + 409 parsing
    - Add `activateProject(id, force)` to `projects-api.ts`, `useActivateProject()` in
      `mutation-hooks.ts` (invalidate `projectKeys` + `readinessKeys.view`), the `ProjectNotReadyError`
      type, and extend `ApiError` to expose the parsed 409 `notReady` body.
    - _Requirements: 1.1, 4.2, 6.5_
    - _Depends on: none_

- [ ] 5. Frontend: surfaces
  - [ ] 5.1 Readiness widget "Hand over to work" action
    - In `ReadinessWidget.tsx`, render the action only when `overallPercentage === 100` and the
      caller holds `PROJECTS` UPDATE; on click call `activateProject(id,false)`; success toast.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_
    - _Depends on: 4.1_
  - [ ] 5.2 Edit-form 409 breakdown + "anyway" force
    - In `ProjectFormSheet.tsx`, on a `ProjectNotReadyError` 409 render the not-ready breakdown
      (reuse the readiness item rendering) and a "Hand over to work anyway" action for ADMIN/MANAGER
      that re-sends `activateProject(id,true)`; success toast + close/refresh. Keep other update
      errors as the current toast.
    - _Requirements: 6.1, 6.2, 6.4, 6.5, 6.6_
    - _Depends on: 4.1_

- [ ] 6. Frontend: i18n
  - [ ] 6.1 Add frontend i18n keys (PL + RU)
    - Add `readiness.activate.action`, `projects.activate.anyway`, `projects.activate.success`,
      `projects.activate.notReadyTitle`, `projects.activate.notReadyHint` to `ru.json` and `pl.json`
      at parity; reuse existing `workspace.tab.*` / `readiness.*` for area names + items. Run the
      locale-parity test.
    - _Requirements: 8.1, 8.3_
    - _Depends on: 5.1, 5.2_

- [ ] 7. Tests
  - [ ] 7.1 Backend tests
    - `ProjectActivationServiceTest` (gate matrix: 100% activate; <100% no force → 409 with correct
      not-ready areas; <100% + ADMIN/MANAGER force → ACTIVE; <100% + non-ADMIN/MANAGER force →
      rejected; already ACTIVE idempotent; terminal → illegal; gate over ALL areas), a
      `getReadinessAllAreas` scoping test, and the advice 409 test. Run only the affected classes
      with `--tests`.
    - _Requirements: 1.1, 1.2, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 7.2_
    - _Depends on: 2.1, 2.3, 1.2, 3.1_
  - [x] 7.2 Frontend tests
    - `useActivateProject` posts `{force}`; `ApiError` parses the 409 `notReady` body; widget shows
      the action only at 100% + UPDATE; form renders the breakdown + "anyway" on 409 and re-sends
      with `force:true`. Type-check + lint + vitest green.
    - _Requirements: 4.2, 5.1, 5.2, 6.4, 6.5_
    - _Depends on: 5.1, 5.2, 6.1_

- [ ] 8. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via run-id generator + teardown,
    regression group, MD report template). Part A (API): `POST /api/projects/{id}/activate` at 100%
    → ACTIVE; at <100% → 409 not-ready body; force by ADMIN/MANAGER → ACTIVE; force by
    non-ADMIN/MANAGER → still 409; generic `PUT status=ACTIVE` gated identically; access/permission
    gates. Part B (UI): readiness widget "Hand over to work" at 100%; edit-form status→ACTIVE at
    <100% → breakdown → "anyway" → ACTIVE. Scenarios are API tests + browser-engine scenarios against
    the Dockerized app; run artifacts are MD reports with tables.
  - _Requirements: 1.1, 2.1, 3.1, 5.1, 6.4_
  - _Depends on: 2.2, 2.3, 5.1, 5.2_

- [ ] 9. Final verification + checkpoint
  - Compile backend + run the affected test classes; type-check + unit-test the frontend; locale
    parity green; app starts clean. Confirm end-to-end on the live stack: activate at 100% via the
    widget, and edit-form status→ACTIVE at <100% → 409 breakdown → force (ADMIN) → ACTIVE, with a
    non-ADMIN/MANAGER blocked at <100%. Commit per repo only if the user asks.
  - _Requirements: all_
  - _Depends on: 7.1, 7.2, 8_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 1.2, 3.1, 4.1 — independent foundations (readiness variant + shared
  constant, not-ready exception/DTO/advice, backend messages, frontend client/hook/error-parsing);
  touch disjoint files.
- **Wave 2 (parallel):** 2.1, 5.1, 5.2 — `ProjectActivationService` (needs 1.1 + 1.2) and the two
  frontend surfaces (each needs only 4.1); the surfaces touch different files
  (`ReadinessWidget.tsx` vs `ProjectFormSheet.tsx`) from each other and from 2.1 (backend), so all
  three run together.
- **Wave 3 (parallel):** 2.2, 2.3 — controller endpoint and the generic-update gate; both depend on
  2.1 and touch different files (`ProjectController` vs `ProjectService`).
- **Wave 4:** 6.1 — frontend i18n keys (depends on the surfaces 5.1/5.2 so the referenced keys are
  final); single file set (`ru.json`/`pl.json`).
- **Wave 5 (parallel):** 7.1, 7.2 — backend tests (depend on 2.1/2.3/1.2/3.1) and frontend tests
  (depend on 5.1/5.2/6.1); disjoint repos/files.
- **Wave 6:** 8 — Author test-cases.md (depends on the implemented endpoints + surfaces).
- **Wave 7:** 9 — Final verification + checkpoint (depends on 7.1, 7.2, 8).
