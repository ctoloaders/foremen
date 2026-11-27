# Bug report: FOR-05-09-team-selection

Screen URL: http://localhost:3000/projects/1/team

Owning spec: `.kiro/specs/FOR-05-project-estimate/FOR-05-09-team-selection`. The `/projects/{id}/team`
URL renders the `project-team` feature tab (`foremen-frontend/src/features/project-team/**`), whose
add-member flow is `AddMemberDialog.tsx`; the backend is `ProjectMemberService.assign(...)` +
`POST /api/project-members`, with the candidate picker served by
`GET /api/project-members/candidates` (`Candidate` read model).

## Round 1 (2026-10-28)

- [x] 1. Team add-member: when a WORKER is chosen, preselect the per-project worker-type from that user's user-level worker type
  - Request (user): on `/projects/1/team`, when a worker is chosen as a project member, preselect
    the worker-type selector from the user's (user-level) worker type.
  - Context: FOR-02-07 (this session) just added an OPTIONAL user-level worker type on the User
    entity (`users.worker_type_id` → `UserEntity.workerType`). The team add-member dialog
    (`AddMemberDialog.tsx`, WORKERS block) renders an OPTIONAL per-project worker-type `<select>`
    (`worker-type-select`) sourced from `/api/worker-types`, whose state (`workerTypeId`) currently
    defaults to EMPTY ("no preselection" per the current Req 23 criterion 2). The per-project
    `ProjectMemberEntity.workerType` is distinct from the user-level one.
  - Layer: **both**. Root cause / gap: the candidate picker option (`Candidate` read model —
    `controller/model/Candidate.java` + frontend `types.ts`) deliberately OMITS the user's worker
    type (Req 11.5 currently bars worker type from candidates), so the frontend cannot preselect from
    the chosen user today. `ProjectMemberService.toCandidate(...)` does not read
    `user.getWorkerType()`; and `assign(...)` sets the member worker type only from the submitted
    `workerTypeId`, never defaulting from the user.
  - Fix direction (approved emphasis — visible UI preselect + backend safety net):
    1. **Backend read model:** expose the user's worker type on the WORKERS candidate — add
       `workerTypeId` + localized `workerTypeName` to `Candidate` (`controller/model/Candidate.java`)
       and populate them in `ProjectMemberService.toCandidate(...)` from `user.getWorkerType()` for
       `block == WORKERS` (localized ru→pl→code, mirroring `localizedRoleName`). This is a deliberate,
       user-approved relaxation of the Req 11.5 "no worker type on candidate" note: the worker type
       is a tier reference (not a cost/rate/margin secret), and the selector is already gated on
       `WORKER_TYPES` READ (D9). Documented as such.
    2. **Frontend preselect:** add `workerTypeId?`/`workerTypeName?` to the frontend `Candidate` type;
       in `AddMemberDialog`, when the selected candidate is a worker carrying a `workerTypeId`, seed
       `setWorkerTypeId(selected.workerTypeId)` (an effect keyed on the selection) so the `<select>`
       defaults to the user's type — still editable/clearable (optional).
    3. **Backend default (safety net):** in `ProjectMemberService.assign(...)`, for a WORKER member
       when no `workerTypeId` was submitted, default `member.setWorkerType(user.getWorkerType())`
       (guard on the type being active), mirroring the adjacent `copyUserWorkCategoriesOnCreate(...)`
       so the user's type is applied even if the client sends nothing.
  - Suspect: `foremen-backend/src/main/java/com/foremen/controller/model/Candidate.java`;
    `foremen-backend/src/main/java/com/foremen/service/ProjectMemberService.java` (`toCandidate`,
    `assign`); `foremen-frontend/src/features/project-team/types.ts` (Candidate);
    `foremen-frontend/src/features/project-team/components/AddMemberDialog.tsx`.
  - Implemented (backend, root repo): added `workerTypeId` + `workerTypeName` to the `Candidate`
    record (documented Req 11.5 relaxation — a tier reference, gated by WORKER_TYPES READ, not a
    cost/rate secret); `ProjectMemberService.toCandidate` populates them from `user.getWorkerType()`
    for WORKERS (localized via existing `localizedWorkerTypeName`); `assign(...)` defaults the member
    worker type from the user's user-level type when none is submitted AND the role is WORKER AND the
    user default is ACTIVE (mirrors `copyUserWorkCategoriesOnCreate`). Updated the two candidate
    confidentiality tests to permit `workertype` (secrets NIP/tag/password/token/rate/cost still
    barred) and added a positive candidate test.
  - Implemented (frontend, foremen-frontend repo): `Candidate` type gains `workerTypeId?`/
    `workerTypeName?`; `AddMemberDialog` seeds `setWorkerTypeId(selected.workerTypeId ?? null)` in an
    effect keyed on the selection (only when the worker-type field is shown), still editable/clearable;
    header comment updated.
  - Verified: backend `./gradlew compileJava compileTestJava` clean; `ProjectMemberServiceCandidateTest`
    (17) + `CandidateLookupSoundnessPropertyTest` (1) pass. Frontend `npx tsc -b` clean;
    `npx vitest run src/features/project-team` → 9 files / 146 tests pass.
  - _Requirements: FOR-05-09 R5 (assign), R11 (candidate read model — relaxed to carry the worker-type reference), R14 (worker type), R23 (add dialog)_
  - _Depends on: none_

- [x] V1. Validate Round 1 in the browser
  - On `/projects/1/team`, open the WORKERS add dialog and pick a worker who has a user-level worker
    type (e.g. a user whose worker type was set on `/users`): confirm the worker-type selector is
    PRESELECTED to that type (still changeable/clearable). Pick a worker with NO user-level type:
    the selector defaults to empty. Submit and confirm the member persists the (preselected or
    changed) worker type. Also confirm a non-WORKER candidate shows no worker-type selector. Mark
    `[x]` only on a confirmed browser pass.
  - Result (2026-10-28, PASS): set user 2579 "Probe Worker" user-level worker type to "Baza" (BASE)
    via `/users`; `GET /api/project-members/candidates?...&block=WORKERS` returned the candidate with
    `workerTypeId:1, workerTypeName:"Baza"`. In the WORKERS add dialog, picking "Probe Worker"
    PRESELECTED the worker-type `<select>` to "Baza" (value "1"); submitting persisted the membership
    with `worker_type_id = 1` (BASE). Backend safety net: removing the member and re-assigning via
    `POST /api/project-members` with NO `workerTypeId` still defaulted the member to BASE from the
    user's user-level type. Cleaned up afterward: member removed (204) and the user's worker type
    reset to null. (Non-WORKER candidates carry no worker-type fields — covered by the backend
    `mapsAdminStaffCandidateWithoutWorkerAttributes` test.)
  - _Requirements: as bug 1_
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — the candidate read model (backend) + preselect (frontend) + assign default
  (backend). Backend and frontend halves are one logical change, serialized backend→frontend because
  the preselect consumes the new candidate `workerTypeId`.
- **Wave 2:** V1 — browser validation; depends on 1.

## Round 2 (2026-11-25)

Screen URL: https://dev.foremen.eu/projects ("Nowy projekt" form) — reproduced locally at
http://localhost:3000/projects.

- [x] 1. Creating a project with team members as a MANAGER fails with 404 "Nie znaleziono encji o ID 27." (`error.entity.not.found`)
  - Triage: observed `POST /api/projects` (MANAGER, `members:[{userId:5, projectRoleId:2}]`,
    `client.newClient`) → 404 `error.entity.not.found` carrying the id of the project that was just
    created in the same transaction (and rolled back). Expected 201 with the project, the submitted
    team, the auto-added creator and the client. Layer: backend. Root cause:
    `ProjectMemberService.assignAtCreation` ran the step-4 Accessible_Project gate
    (`projectAccessCheck`). A non-ADMIN creator is not a member of the brand-new project yet (the
    creator is auto-added only AFTER `members[]` are processed), so the gate rejected the first
    submitted member with 404. ADMIN bypasses the gate, which is why it was not caught. The
    creator-specific `assignCreator` already omitted this gate for the same reason.
    Fix: `assignAtCreation` omits the step-4 gate as well (the orchestrator is guarded by
    `PROJECTS`/`CREATE`, and the project provably exists in the caller's transaction); every other
    check (role.mismatch, lifecycle lock, duplicate, user existence, creation team-composition) is
    unchanged. Client attachment (step 5) keeps the regular gate and passes because the creator is
    a member by then. Files: `foremen-backend/.../service/ProjectMemberService.java`.
    Regression test: `ProjectCustomCreateIT.create_asRealManager_withMembersAndNewClient_returns201`;
    the IT now mints tokens for a real persisted caller (the previous phantom user id 1 hid the bug),
    and the existing assertions were updated for the auto-added creator membership.
  - _Requirements: 26 (creation-time team assignment), 2.6/2.9 of FOR-04-13 project creation_
  - _Depends on: none_
- [x] V2. Validate Round 2 (API + browser)
  - Result (2026-11-25, PASS):
    - Before the fix (old local backend): the same payload as a local MANAGER (user 1246) →
      `404 "Nie znaleziono encji o ID 9 603."` — identical to dev.
    - After `docker compose up -d --build backend`: same request → `201`, project 9604 with
      memberships FOREMAN 1247 (ACTIVE), MANAGER creator 1246 (ACTIVE), new CLIENT 23856 (INVITED).
    - Browser (`/projects` → "Nowy projekt", MANAGER session): name, area 39.9, VAT 8%, team member
      Tomasz Lewandowski, "Nowy klient" Olga Olegowna → "Utwórz projekt" sent
      `members:[{userId:1247,projectRoleId:3}]` + `client.newClient` and got `201` (project 9605);
      the sheet closed. Note: the local MANAGER has no `USERS` READ grant, so the team/client pickers
      get 403 from `/api/users` locally; for this check only those two picker reads were stubbed in
      the browser with the real user 1247. The `POST /api/projects` itself hit the real backend. On dev
      the picker works, as the reported request shows.
    - Tests: `ProjectCustomCreateIT` 5/5 pass (the new regression test fails with the exact 404 on
      the pre-fix code); `ProjectCreateAutoAddCreatorTest` 5/5 pass. Failures that already existed
      before this change and are unrelated (they fail on the pre-fix code too):
      `ProjectMemberServiceTest` readiness ×2, `ProjectServiceProjectionTest` ×1,
      `ProjectClientRolePropertyTest` ×2.
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 2
- **Wave 1:** 1 — single backend change in `ProjectMemberService.assignAtCreation` + IT.
- **Wave 2:** V2 — API + browser validation; depends on 1.

## Round 3 (2026-11-27)

Screen URL: `https://dev.foremen.eu/projects/30/team` (Team tab; owning feature `foremen-frontend/src/features/project-team/**`, buttons in `TeamTab.tsx`).

- [x] 1. A MANAGER or FOREMAN does not see "Add new worker" (WORKERS block) or "Invite new client" (CLIENTS block); only ADMIN does
  - Triage: observed both buttons hidden for MANAGER and FOREMAN; expected them visible for the roles that may manage the team (Requirements 12.7, 13). Layer: both (frontend visibility and backend guard). Suspect: `TeamTab.tsx` lines 638-643 (`canInviteNewClient`, `canAddNewWorker`, `canInviteWorker`), `UserController.java` lines 82, 106, 127, `WorkerRecordService`, `WorkerInvitationService`.
  - Cause 1 (affects MANAGER and FOREMAN): the code checks the operation `PROJECTS` / `EDIT`, but the operations table has only `CREATE`, `READ`, `UPDATE`, `DELETE` (changeset 003). No role can hold `EDIT`; only ADMIN passes, because `ADMIN` bypasses the matrix. The frontend hides the buttons, and the three backend endpoints (`POST /api/users/client`, `POST /api/users/worker`, `POST /api/users/worker/{id}/invite`) would return 403 for MANAGER and FOREMAN even if the buttons were shown.
  - Cause 2 (FOREMAN only): checked on the dev database, the effective grants are: MANAGER `PROJECTS` CREATE/READ/UPDATE and `PROJECT_MEMBERS` CREATE/READ/UPDATE/DELETE; FOREMAN `PROJECTS` READ only and `PROJECT_MEMBERS` CREATE/READ/UPDATE/DELETE. After fixing cause 1, MANAGER works; FOREMAN still lacks `PROJECTS` UPDATE.
  - Decision needed for FOREMAN, pick one:
    - (A, recommended) Rename `EDIT` to `UPDATE` everywhere (fixes MANAGER) and for these three team actions require only `PROJECT_MEMBERS` CREATE (plus project access and Editable_Status, which are already checked). MANAGER and FOREMAN both hold it, and no extra rights are granted. This changes Requirements 12.1/12.7 and 13.1/13.10, which name `PROJECTS` EDIT; the requirements text is updated with the change.
    - (B) Rename `EDIT` to `UPDATE` and grant FOREMAN `PROJECTS` UPDATE through a new seed changeset. This keeps the requirements as written, but gives FOREMAN the right to edit project fields (`PUT /api/projects/{id}`), which is wider than the team actions need.
  - Files to change (A): `TeamTab.tsx`, `UserController.java` (3 annotations), the comments in `WorkerRecordService`/`WorkerInvitationService`, backend tests that grant `PROJECTS`/`EDIT` (`WorkerRegistrationFlowIntegrationTest`, `ClientRegistrationControllerIntegrationTest`, `WorkerRecordServiceTest`, `WorkerInvitationServiceTest`, `EndToEndResolutionIntegrationTest`), `EditActionsCoverage.test.tsx`, plus a regression test with a real MANAGER and FOREMAN token. No migration, no i18n change.
  - _Requirements: 12.7, 13.1, 13.10, 23_
  - _Depends on: none_
- [x] 2. "Add new worker" without an email fails with 409 "Adres email 'null' jest już używany" (found while validating item 1)
  - Triage: observed `POST /api/users/worker` with no `email` returns 409 `error.user.email.already.exists` with the value `null`; expected 201 and a WORKER user with no email (Requirement 13.1, email is optional). Layer: backend (DB schema + error mapping). Root cause: the `users.email` column is `NOT NULL` in the Liquibase schema and no changeset drops that constraint; Postgres rejects the insert (`null value in column "email" of relation "users" violates not-null constraint`), and `UserService.createWorker` maps every `DataIntegrityViolationException` to the duplicate-email 409. The tests do not catch it because the integration-test profile builds the schema from Hibernate, where `email` is nullable. A worker with an email is created fine (201).
  - Fix (proposal, needs approval): a new changeset `NNN-users-email-nullable.xml` registered last in `changelog.xml` that drops `NOT NULL` on `users.email` (the unique index stays; Postgres allows many NULLs under a unique index). Check that login and invite code paths handle a null email. Optionally narrow the 409 mapping in `UserService.createWorker` to the unique-email constraint only, so other constraint errors are not reported as duplicates.
  - _Requirements: 13.1, 13.7_
  - _Depends on: none_
- [x] V3. Validate Round 3 in browser and by API
  - Local stack: as MANAGER and as FOREMAN open the Team tab of an accessible project in an Editable_Status: both buttons visible; add a new worker and invite a new client (201). As a user without `PROJECT_MEMBERS` CREATE, or in a Locked_Status project: buttons hidden and the API returns 403. Repeat on dev after redeploy.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 3

- **Wave 1 (parallel):** 1, 2 — independent (1: permission checks; 2: schema changeset + error mapping).
- **Wave 2:** V3 — validation, depends on 1 and 2.

## Round 3 — validation results (2026-11-27)

- **#1 fixed (option A), verified locally.** `TeamTab.tsx` now shows "Add new worker", "Invite new client" and the worker invite action when the viewer holds `PROJECT_MEMBERS` CREATE and the project is in an Editable_Status. `UserController` guards `POST /api/users/client`, `POST /api/users/worker` and `POST /api/users/worker/{id}/invite` with `@RequiresPermission(PROJECT_MEMBERS, CREATE)` instead of the non-existent `PROJECTS`/`EDIT`. Requirements 12 and 13 text updated to match.
  - Browser (local stack, project 9605, DRAFT): MANAGER `katarzyna@loaders.dev` and FOREMAN `tomasz@loaders.dev` both see "Dodaj nowego robotnika" and "Zaproś nowego klienta".
  - API: MANAGER `POST /api/users/client` → 201; FOREMAN `POST /api/users/client` → 201; MANAGER `POST /api/users/worker` with email → 201.
  - Local data changes for the check: the two users got the local admin's password hash; the local FOREMAN role got `PROJECT_MEMBERS` CREATE to mirror dev (locally FOREMAN had only READ). Dev already grants it, so no seed changeset is added.
  - Tests: frontend `src/features/project-team` 147/147 pass; backend `WorkerRegistrationFlowIntegrationTest` 12/12, `EndToEndResolutionIntegrationTest` 12/12, `WorkerRecordServiceTest` 17/17, `WorkerInvitationServiceTest` 13/13. `ClientRegistrationControllerIntegrationTest` has 4/9 failures (404/409) that are identical on the unchanged code, so they pre-date this fix.
- **#2:** approved and fixed in Round 4 (see the Round 4 validation results).
- **V3 open:** dev still runs the old build; re-check on `https://dev.foremen.eu/projects/30/team` after deploy.

## Round 4 (2026-11-27)

Screen URL: `https://dev.foremen.eu/projects/30/team`.

- [x] 1. Workers and admin staff must be addable in any project status
  - Triage: observed that in a Locked_Status (`COMPLETED`, `CANCELLED`) the Team tab hides every add control and the backend rejects every assign with 409 `error.project.team.locked`; expected that workers and admin staff (any non-CLIENT Company_Role) can be added in any status. Clients, member edits, deactivate, reactivate and remove keep the lock. Layer: both. Files: `ProjectMemberService.java` (new `addLifecycleLockCheck` used by `assign`; new `assertProjectAccessible`), `WorkerRecordService.java` (uses `assertProjectAccessible`), `TeamTab.tsx` (`canAddToBlock`), tests, `requirements.md` (Requirement 10.2, 13.15, 23.14).
  - _Requirements: 10.2, 13.15, 23.14_
  - _Depends on: none_
- [x] 2. Round 3 #2 implementation: worker without email
  - Changeset `279-users-email-nullable.xml` (registered last in `changelog.xml`, precondition makes re-runs a no-op) drops `NOT NULL` on `users.email`; `uk_users_email` stays. `UserService.createWorkerRecord` maps a constraint error to the duplicate-email 409 only when an email was supplied.
  - _Requirements: 13.1, 13.7_
  - _Depends on: none_
- [x] V4. Validate Round 4 on dev
  - Local validation passed (below). Re-check on dev after deploy: project 30 team tab as MANAGER and FOREMAN.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 4

- **Wave 1 (parallel):** 1, 2 — different files (lock rule vs. schema and worker create).
- **Wave 2:** V4 — validation on dev, depends on 1 and 2.

## Round 4 — validation results (2026-11-27)

Local stack rebuilt (`liquibase`, `backend`, `frontend`); `279-users-email-nullable` EXECUTED, `users.email` is nullable.

| # | Check | Caller | Result |
|---|---|---|---|
| 1 | `POST /api/users/worker` without email, DRAFT project 9605 | MANAGER, FOREMAN | 201, `email` empty ✅ |
| 2 | `POST /api/users/worker`, COMPLETED project 9604 | MANAGER, FOREMAN | 201 ✅ |
| 3 | `POST /api/users/client`, COMPLETED project 9604 | MANAGER, FOREMAN | 409 `error.project.team.locked`, no user left behind ✅ |
| 4 | `POST /api/project-members` existing FOREMAN, COMPLETED project | MANAGER | 201 ✅ |
| 5 | `POST /api/project-members` existing CLIENT, COMPLETED project | MANAGER | 409 `error.project.team.locked` ✅ |
| 6 | Browser, team tab of COMPLETED project 9604 | MANAGER, FOREMAN | ADMIN_STAFF add, WORKERS add, "add new worker" shown; CLIENTS add and "invite client" hidden; no member edit menus ✅ |
| 7 | Browser, "add new worker" dialog, name only, COMPLETED project | FOREMAN | `POST /api/users/worker` 201, dialog closed ✅ |

Project 9604 was set to COMPLETED for the check and restored to DRAFT afterwards.

Tests: frontend `src/features/project-team` 147/147. Backend `ProjectMemberServiceTest` passes except the two readiness tests that already failed before (Round 2 note), including the new parameterized `assignNonClientOnLockedProjectSkipsLifecycleLock` (WORKER, FOREMAN, MANAGER); `WorkerRecordServiceTest` 17/17.


## Dev validation (2026-11-27, after deploy of root `3f2cf7b` and frontend `7012e3f`)

Setup: dedicated test users created through the app (ADMIN `POST /api/users` + the invite `set-password` flow): MANAGER 13, FOREMAN 14, WORKER 15 (`test+qa-*-11272111@example.com`), and a test project 31 created by the MANAGER. After the checks the users (13-18) were deactivated and project 31 was renamed and set to CANCELLED (delete is refused with `error.project.hasDependents`). Project 5 was only opened read-only; nothing was saved there.

| # | Check | Caller | Result |
|---|---|---|---|
| 1 | `POST /api/projects` with a FOREMAN member (Round 2) | MANAGER | 201, project 31 ✅ |
| 2 | Team tab: "Add new worker" and "Invite new client" visible | MANAGER, FOREMAN | both visible ✅ |
| 3 | "Add new worker" dialog, name only (no email) | FOREMAN | `POST /api/users/worker` 201, `email: ""` ✅ |
| 4 | `POST /api/users/client`, DRAFT project | FOREMAN | 201 ✅ |
| 5 | `POST /api/project-members` existing WORKER, DRAFT | FOREMAN | 201 ✅ |
| 6 | `POST /api/users/worker`, project COMPLETED | FOREMAN | 201 ✅ |
| 7 | `POST /api/users/client`, project COMPLETED | FOREMAN | 409 `error.project.team.locked` ✅ |

Changeset `279-users-email-nullable` is EXECUTED on dev and `users.email` is nullable.
