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
