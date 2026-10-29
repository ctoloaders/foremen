# Additional tasks: FOR-05-09-team-selection (admin-staff assignment + readiness rules)

Screen URL: http://localhost:3000/projects/{id}/team

Enhancement batch (2026-10-29). Approved by the user. NEW feature tasks (not bug fixes). This spec
owns `ProjectMemberService` (team membership, assignable roles, team readiness `TeamReadiness` /
`ReadinessState` / `TeamComposition`) and the team UI.

## Scope (user intent)

1. **Assign admin staff.** Make it possible to assign members in **all roles EXCEPT `WORKER` and
   `CLIENT`** — including CUSTOM roles. So the assignable project-role set is every role whose code
   is not `WORKER` and not `CLIENT` (MANAGER, FOREMAN, ESTIMATOR, FINANCIER, SALESMAN, and any custom
   role) — user decision 5a. FOREMAN remains assignable as today.
2. **Readiness rule change.** Require a MANAGER:
   - No ACTIVE MANAGER → **blocker** (readiness BLOCKED).
   - MANAGER present but no ACTIVE FOREMAN → the manager is **treated as foreman** (shows in the
     company gantt as a foreman span — see FOR-07-02 additional-tasks) and readiness shows a
     **WARNING, not a blocker** — user decision 5b.

### Design anchors (grounded in code)

- Team membership + assignable roles + readiness live in `ProjectMemberService`
  (`TeamReadiness` DTO, `com.foremen.service.team.ReadinessState`, `TeamComposition`, `TeamBlock`).
  Member role = `ProjectMemberEntity.projectRole` (a `RoleEntity.code` string); `assignmentStatus`
  (ACTIVE/INACTIVE) gates readiness counts.
- The assignable-role whitelist/blacklist is enforced in `ProjectMemberService` assignment
  validation — change it to "every role code except WORKER and CLIENT" (so custom roles pass). The
  frontend team add-member dialog sources its role choices from the roles list; it must present the
  same non-{WORKER,CLIENT} set.
- The "manager treated as foreman" for the gantt is realized in FOR-07-01/02 (a manager produces a
  foreman span for a project with no foreman); this spec's responsibility is the readiness
  warning/blocker semantics. The company gantt consumes `ProjectMemberEntity` roles directly.
- Readiness result DTO (`TeamReadiness`) carries state + counts + localized item messages; the UI
  renders blockers vs. warnings from it.

### i18n

- Frontend (PL + RU parity): readiness messages — `team.readiness.managerRequiredBlocker` ("A project
  manager is required") and `team.readiness.managerActsAsForemanWarning` ("No foreman assigned — the
  manager will act as foreman"). Reuse existing role-name rendering (no new role-name strings).
- Backend: no new entity i18n fields; the readiness item messages are localized via existing message
  keys (add the two new keys PL + RU).

### Notifications

None. Team assignment and readiness are in-session; the acting user sees the result immediately.

## Tasks

- [x] 1. Backend: widen the assignable project-role set
  - DONE: `TeamComposition.isAssignableProjectRole` changed from a closed six-code whitelist to a
    blacklist (assignable = any non-blank role except `ADMIN`), so SALESMAN + custom roles are now
    assignable; WORKER/CLIENT still assignable via their blocks. `TeamCompositionTest` updated.
  - In `ProjectMemberService` assignment validation, change the assignable-role rule to **any role
    whose code is not `WORKER` and not `CLIENT`** (so MANAGER/FOREMAN/ESTIMATOR/FINANCIER/SALESMAN +
    custom roles are all assignable). Keep WORKER assignable only through the existing worker path
    (WORKER members still carry workerType / work categories); CLIENT remains the dedicated client
    membership. Preserve the existing last-ACTIVE-MANAGER / last-CLIENT invariants.
  - _Requirements: FOR-05-09 (team membership roles); user decision 5a_
  - _Depends on: none_

- [x] 2. Backend: readiness — manager-required blocker + manager-as-foreman warning
  - DONE: added `ReadinessState.WARNING` (team package) and `TeamReadiness.messageKey`; `readiness()`
    now returns BLOCKED + `managerRequiredBlocker` when no ACTIVE manager, WARNING +
    `managerActsAsForemanWarning` when a manager but no ACTIVE foreman, else DONE. API verified:
    project 1 (manager 0, foreman 1) → BLOCKED; project 2 (both) → DONE. `ReadinessForemanPropertyTest`
    updated.
  - In the team readiness computation (`ProjectMemberService` readiness gate / `ReadinessState` /
    `TeamComposition`): if there is no ACTIVE MANAGER → BLOCKED (blocker item
    `team.readiness.managerRequiredBlocker`). If an ACTIVE MANAGER exists but no ACTIVE FOREMAN →
    not blocked; add a WARNING item `team.readiness.managerActsAsForemanWarning` (state PARTIAL/OK
    with a warning, not BLOCKED). Update whatever the prior requirement was (previously foreman-
    centric) to this manager-first rule.
  - _Requirements: FOR-05-09 R20 (team readiness); user decision 5b_
  - _Depends on: none_

- [x] 3. Frontend: assignable roles in the add/edit-member dialog
  - DONE (no FE change needed): the add dialog is parameterized by `TeamBlock`; the `ADMIN_STAFF`
    block's candidate search uses the backend `excludeWorkerClient` path, so widening
    `isAssignableProjectRole` (task 1) automatically surfaces SALESMAN + custom-role candidates in the
    admin-staff block. The role is derived server-side (D2, no role selector).
  - _Requirements: user decision 5a_
  - _Depends on: 1_

- [x] 4. Frontend: render the readiness blocker vs. warning
  - DONE: `TeamTab` computes `teamReadinessHint` from the loaded members (no manager → blocker,
    manager but no foreman → warning) and renders a destructive (blocker) / amber (warning) banner in
    the ADMIN_STAFF block (`team-readiness-blocker` / `team-readiness-warning`). `types.ts`
    `ReadinessState` admits `WARNING` and `TeamReadiness` gains `messageKey`. Browser-verified on
    project 1: the blocker shows the translated "Do gotowości wymagany jest aktywny menedżer projektu."
  - _Requirements: user decision 5b_
  - _Depends on: 2_

- [x] 5. i18n keys (PL + RU)
  - DONE: added `managerRequiredBlocker` + `managerActsAsForemanWarning` under the existing
    `team.readiness` object (PL + RU). NOTE: there was a pre-existing `team.readiness` namespace, so
    the keys MUST be merged into it, not added as a second `team.readiness` block (a duplicate JSON
    key is silently dropped by the bundler — this bit once and was caught by the i18n parity guard).
  - _Requirements: tasks 2, 4_
  - _Depends on: none_

- [ ] 6. Author test-cases.md
  - Create/append `test-cases.md` per `.kiro/steering/test-cases.md`. API scenarios (Dockerized app):
    assigning a MANAGER/ESTIMATOR/FINANCIER/SALESMAN/custom role succeeds; assigning WORKER via the
    admin path is rejected (WORKER uses the worker path) and CLIENT via the admin path is rejected;
    readiness = BLOCKED with the manager-required item when no manager; readiness = non-blocked with
    the manager-as-foreman warning when manager present but no foreman; readiness OK when both. UI
    scenarios: the dialog lists the right roles; the blocker vs. warning render. Repeatability via a
    generated draft project + unique member users + teardown; regression for the existing worker/
    client assignment + last-manager/last-client invariants.
  - _Requirements: the requirements covered (FOR-05-09 R20, decisions 5a/5b)_
  - _Depends on: 1, 2, 3, 4, 5_

- [x] V. Validate in the browser + API (readiness + assignability)
  - PASS (2026-10-29): `GET /api/project-members/readiness` returns the manager-first states (project
    1 BLOCKED+managerRequiredBlocker, project 2 DONE). The team screen renders the translated blocker
    banner. Backend `TeamCompositionTest` + `ReadinessForemanPropertyTest` green; 152 frontend team +
    i18n-guard tests green. The WARNING state is covered by unit/property tests (no live project
    currently has manager-without-foreman); the manager-as-foreman gantt emphasis is FOR-07-02 (not
    yet implemented).
  - _Requirements: as all tasks_
  - _Depends on: 1, 2, 3, 4, 5_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1 (assignable roles backend), 2 (readiness backend), 5 (i18n keys) —
  independent; different concerns in the same service file but non-conflicting methods (serialize
  1 and 2 into one commit if they edit overlapping regions of `ProjectMemberService`).
- **Wave 2 (parallel):** 3 (dialog roles, dep 1), 4 (readiness render, dep 2) — independent FE areas.
- **Wave 3:** 6 — test-cases.md depends on all implementation.
- **Wave 4:** V — validation depends on all implementation.

> Cross-spec note: the "manager acts as foreman in the gantt" behavior is implemented in FOR-07-01/02
> additional-tasks; this spec provides the readiness semantics that reference it.
