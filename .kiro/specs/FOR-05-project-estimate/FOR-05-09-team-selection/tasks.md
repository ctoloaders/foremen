# Implementation Plan: FOR-05-09 — Team selection and assignment

## Overview

This plan implements the design in incremental, integrated steps that build on each other and end by
wiring the project-workspace Team tab, its three blocks, dialogs, readiness chip, and the FOR-04-13
create-form filter together. The backend comes first (schema → entity/DTO → pure helpers → service
rules → controller/registration/worker flows → masking → audit/notifications → project-creation
restriction → readiness), then the frontend surface. Every step references specific requirements, and
every correctness property from the design (Properties 1–12) becomes a property-based test placed
next to the code it validates.

The feature **reuses** the already-seeded project-scoped ABAC resource `PROJECT_MEMBERS` (changeset
`015` + `136`) and introduces **no new managed entity** (per `.kiro/steering/entity-creation-rules.md`,
steps 1–2 are intentionally not repeated — see the note below). It extends two existing tables
(`project_members`, `users`) with the new Liquibase changesets `149`–`151`.

It spans **two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Backend + migrations + spec** (root repo): the `149`–`151` changesets; the
  `ProjectMemberEntity` / `UserEntity` / `project_member_tags` schema mapping; the
  `@PermissionResource("PROJECT_MEMBERS")` annotation + per-handler `@PermissionOperation`; the
  enriched `Team_Member_View` mapper + admin-staff masking; the composition rules and canonical
  rejection order in `ProjectMemberService` (made a `ProjectScopedService`); candidate lookup;
  readiness; the client-registration `tags` extension; the `Worker_Record_Flow` /
  `Worker_Invitation_Flow`; audit + notifications; the `ProjectService` creation restriction and
  multi-client DTO projection; the FOR-05-06 worker-type delete guard; backend message bundles; and
  the FOR-03-08 regression-test updates. Backend/spec commits are made **from the root repo**.
- **Frontend** (`foremen-frontend/`, nested repo): the `team` `WORKSPACE_TABS` entry; the `TeamTab`
  with three blocks and responsive table/card layouts; the candidate-search, invite-client,
  add-new-worker, change-worker-type, edit-tags, deactivate/reactivate/remove dialogs; the
  readiness chip in `ReadinessWidget`; the `TeamMemberSelect` admin-staff filter and create-form
  hint; the tag filter and missing-worker-type warning; and the `pl.json` + `ru.json` keys. Frontend
  commits are made **from inside `foremen-frontend/`**.

This feature spans both repos, so it needs **two commits — one per repo** (backend + migrations +
spec in the root; all frontend source in `foremen-frontend/`).

**Entity-creation checklist note** (per `.kiro/steering/entity-creation-rules.md`). This spec
introduces **no new managed entity**. It reuses the existing project-scoped `PROJECT_MEMBERS`
resource, so checklist steps 1–2 (seed a resource row + ADMIN grant) are intentionally **not**
repeated — re-seeding would duplicate the changeset `015` / `136` rows. The applicable steps are:
step 3, annotate the existing `ProjectMemberController` with `@PermissionResource("PROJECT_MEMBERS")`
+ per-handler `@PermissionOperation`; and step 4, make `ProjectMemberService` a `ProjectScopedService`
with `getProjectIdPath()` returning `"projectId"`. No `PROJECT_TEAM` resource is created (D1).

**Migrations.** New Liquibase changesets begin at `149` (current changelog tail is
`148-seed-company-profile-resource.xml`; if a sibling spec lands further changesets first, use the
next free `NNN`). The sequence is `149`–`151` per the design's changeset table
(`149-add-user-worker-attributes`, `150-add-project-member-attributes`,
`151-create-project-member-tags` — the design groups the `project_members` columns + the tag table
across `150`/`151`). Each follows the `NOT columnExists` / `NOT tableExists` / `onFail="MARK_RAN"`
idempotency convention, performs **no backfill of worker type** (existing WORKER members become
Uncategorized_Workers) and backfills `assignment_status` to `ACTIVE`, and is appended **last** in
`changelog.xml`.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (it takes
~20 minutes, per `.kiro/steering/test-execution-rules.md`). Run only the affected test classes with
`--tests`, redirect to a temp log (`/tmp/for-05-09-test.log`), and read the log / the JUnit result
XML under `foremen-backend/build/test-results/test/` to determine pass/fail. Use `getDiagnostics` and
`./gradlew compileJava` / `compileTestJava` for fast checks.

## Tasks

- [x] 1. Database schema: worker attributes, member attributes, and tags (changesets 149–151)
  - [x] 1.1 Add user worker attributes (changeset 149)
    - Create `foremen-backend/database_files/changesets/149-add-user-worker-attributes.xml` adding
      nullable `worker_kind` (VARCHAR), `contact_person` (VARCHAR(255)), and `nip` (VARCHAR(10)) to
      `users`, each guarded by a `NOT columnExists` precondition with `onFail="MARK_RAN"`; no
      backfill (every existing user and every non-worker-record user keeps them empty).
    - Register the file **last** in `foremen-backend/database_files/changelog.xml`.
    - _Requirements: 13.16_

  - [x] 1.2 Add project-member worker type and assignment status (changeset 150)
    - Create `foremen-backend/database_files/changesets/150-add-project-member-attributes.xml` with
      two changeSets: add nullable `worker_type_id` BIGINT FK → `worker_types(id)` (no backfill,
      not dependent on `is_base`); add non-null `assignment_status` VARCHAR defaulting to `ACTIVE`
      with existing rows backfilled to `ACTIVE`. Guard each with `NOT columnExists` +
      `onFail="MARK_RAN"`.
    - Register the file **last** in `changelog.xml`, after `149`.
    - _Requirements: 14.12, 27.1_

  - [x] 1.3 Create the project_member_tags child table (changeset 151)
    - Create `foremen-backend/database_files/changesets/151-create-project-member-tags.xml` creating
      `project_member_tags{ id, project_member_id BIGINT FK NOT NULL ON DELETE CASCADE, tag
      VARCHAR(50) NOT NULL, order_no INT NOT NULL }`, guarded by `NOT tableExists` +
      `onFail="MARK_RAN"`.
    - Register the file **last** in `changelog.xml`, after `150`.
    - _Requirements: 15.1_

  - [x] 1.4 Write a migration idempotence integration test
    - Verify the three changesets apply cleanly, add exactly the described columns/table, backfill
      `assignment_status` to `ACTIVE`, perform no worker-type backfill, and change no schema and no
      data on a second run.
    - _Requirements: 13.16, 14.12, 27.1, 15.1_

- [x] 2. Entity, DTO, and enum mapping for the new attributes
  - [x] 2.1 Extend `ProjectMemberEntity` and `UserEntity`
    - Map `project_members.worker_type_id` (nullable FK), `assignment_status` (non-null enum
      `ACTIVE`/`INACTIVE`), and an `@OrderColumn`-mapped `List<String>` tags via the
      `project_member_tags` child table on `ProjectMemberEntity`. Map `worker_kind`
      (`PERSON`/`COMPANY`, null → `PERSON` for a WORKER view), `contact_person`, and `nip` on
      `UserEntity`. Keep the existing `uk_project_members_user_project` constraint.
    - _Requirements: 14.1, 27.1, 15.1, 13.16_

  - [x] 2.2 Define the `Team_Member_View`, `Candidate`, and readiness response models
    - Create the enriched `Team_Member_View` carrying id/userId/projectId, projectRoleId/code/name,
      companyRoleCode, block, assignmentStatus, user name/email/status/active, workerKind/contactPerson
      (WORKERS), and the internal fields (workerTypeId/code/name/active, nip, workerTypeMissing, tags).
      Create the `Candidate` model (userId, name, email, status, companyRoleCode/Name, block,
      workerKind?, contactPerson?) and the readiness response `{key, state, counts{per role}}`. None
      of these carry rate/tier/cost/password/token/OTP.
    - _Requirements: 4.3, 4.5, 4.7, 4.10, 11.4, 11.5, 20.2_

- [x] 3. Pure helpers: tag normalization, NIP checksum, block derivation, ordering
  - [x] 3.1 Implement `Tag_Normalization` and tag validation
    - Trim each tag, drop case-insensitive duplicates keeping first spelling + original order, enforce
      1–50 code points after trim, reject control characters, reject >10 after normalization; reject a
      non-list, null element, or missing/null list on a tag-change with
      `error.project.member.tag.invalid`. Preserve inner whitespace and letter case verbatim.
    - _Requirements: 15.3, 15.4, 15.10_

  - [x] 3.2 Write property test for tag normalization idempotence and round-trip
    - **Property 5: Tag normalization is idempotent and round-trips through persistence**
    - **Validates: Requirements 15.4, 15.3**

  - [x] 3.3 Write property test for tag acceptance and rejection
    - **Property 6: Tag acceptance and rejection**
    - **Validates: Requirements 15.3, 15.10**

  - [x] 3.4 Implement NIP normalization and checksum
    - Strip spaces/hyphens, accept iff exactly 10 digits whose weighted checksum (weights
      6,5,7,2,3,4,5,6,7 mod 11, not 10, equal to the tenth digit) is valid; otherwise reject with
      `error.worker.nip.invalid`.
    - _Requirements: 13.5_

  - [x] 3.5 Write property test for NIP checksum validation
    - **Property 12: NIP checksum validation**
    - **Validates: Requirements 13.5**

  - [x] 3.6 Implement block derivation and Team_Block ordering comparator
    - Derive `block` purely from Company_Role (CLIENT→CLIENTS, WORKER→WORKERS, else ADMIN_STAFF).
      Implement the deterministic ordering: block (ADMIN_STAFF, WORKERS, CLIENTS); inside ADMIN_STAFF
      by role code (MANAGER, FOREMAN, ESTIMATOR, FINANCIER, then any other); then userName ascending
      case-insensitive; then id ascending. Assignment_Status does not affect order.
    - _Requirements: 4.6, 6.2_

  - [x] 3.7 Write property test for deterministic Team_Block ordering
    - **Property 7: Team_Block ordering is deterministic**
    - **Validates: Requirements 4.6**

- [x] 4. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Make `ProjectMemberService` project-scoped and annotate the controller
  - [x] 5.1 Implement `ProjectScopedService` on `ProjectMemberService`
    - Override `getProjectIdPath()` to return `"projectId"` so every by-id/by-pair operation runs the
      FOR-03-04 project-scope check with the ADMIN bypass; a non-accessible project returns 404
      `error.entity.not.found` byte-identical to a non-existent project.
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 3.8_

  - [x] 5.2 Annotate `ProjectMemberController` with `@PermissionResource("PROJECT_MEMBERS")`
    - Add the class-level annotation and a per-handler `@PermissionOperation`: READ for list,
      readiness, and projects; CREATE for assign and candidate lookup; UPDATE for the PATCH
      Attribute_Update; DELETE for remove. No `PROJECT_TEAM` reference. Keep `projectId` out of the
      URL. Ensure the application starts (PermissionAnnotationValidator accepts the controller).
    - _Requirements: 2.1, 2.5, 2.6_

  - [x] 5.3 Write ABAC per-role × per-endpoint unit tests
    - Assert 403 `error.access.denied` for WORKER/CLIENT (no grant) and for FOREMAN/ESTIMATOR/FINANCIER
      (READ only) on write endpoints; 401 before any permission/scope check; ADMIN success; and the
      404-indistinguishability of inaccessible vs non-existent projects.
    - _Requirements: 2.3, 2.4, 2.7, 2.8, 3.3, 3.4_

- [x] 6. Composition rules, canonical rejection order, and the project-ids read
  - [x] 6.1 Implement the canonical rejection order as one ordered checklist
    - In `ProjectMemberService`, enforce the deterministic sequence of Requirement 3 criterion 7
      (auth → permission → mandatory fields incl. role.mismatch + tag.invalid → project
      existence/access → lifecycle lock → member existence/duplicate → user existence →
      team-composition sub-order → last ACTIVE MANAGER/CLIENT), returning the first tripped check and
      leaving rows unchanged on any failure.
    - _Requirements: 3.7, 10.4_

  - [x] 6.2 Write property test for canonical-order precedence
    - **Property 11: The first failing check in the canonical order determines the response**
    - **Validates: Requirements 3.7, 10.4**

  - [x] 6.3 Implement team-composition validation (role assignability, inactive user, lifecycle lock)
    - Reject a resolved role that is not an Assignable_Project_Role with
      `error.project.member.role.not.assignable`; reject an Inactive_User with
      `error.project.member.user.inactive`; reject a mutating op on a Locked_Status project with
      `error.project.team.locked`. Apply to ADMIN and non-ADMIN alike (no ABAC bypass of these).
      Lowest-numbered criterion wins among 1 and 3.
    - _Requirements: 6.1, 6.3, 6.4, 6.6, 6.7, 10.1, 10.2, 10.3, 10.5_

  - [x] 6.4 Implement `GET /api/project-members/projects?userId`
    - Return the project ids (ascending) a user belongs to in any Assignment_Status, restricted for a
      non-ADMIN caller to Accessible_Projects; empty list (not 404) when empty or user missing.
    - _Requirements: 3.6_

- [x] 7. Assign, remove, and the enriched list read with masking
  - [x] 7.1 Implement assign (role = user's Company_Role, status ACTIVE)
    - Persist exactly one member for the pair with Project_Role = user's current Company_Role,
      Assignment_Status ACTIVE, submitted worker type (WORKER only), and normalized tags. Reject a
      duplicate pair (any status) with 409 `error.project.member.duplicate`; a missing user with 404;
      a mismatching non-null `projectRoleId` with 400 `error.project.member.role.mismatch`; validate
      mandatory fields before any lookup. Increase member count by one; leave existing members
      unchanged. Multi-client with no upper limit. Concurrency: exactly one row, the other 409.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10, 5.11, 9.4_

  - [x] 7.2 Write property test for assigned-role equality, derived block, and immutability
    - **Property 1: Assigned role equals the user's Company_Role, with derived block and immutability**
    - **Validates: Requirements 5.1, 5.10, 6.2, 7.1, 7.2**

  - [x] 7.3 Write property test for mismatching supplied-role rejection
    - **Property 2: A mismatching supplied role is rejected**
    - **Validates: Requirements 5.11, 7.2**

  - [x] 7.4 Implement remove (hard delete)
    - Hard-delete an existing member of an Accessible_Project in an Editable_Status after the
      last-ACTIVE guard and lifecycle check; 404 `error.project.member.not.found` for a missing pair;
      400 on invalid identifiers; delete no other row/user/project; self-remove drops the caller's
      own access.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_

  - [x] 7.5 Implement the enriched list read with ordering
    - Return one `Team_Member_View` per member (incl. INACTIVE, INVITED, inactive-user) in the
      deterministic order; empty list for an empty team; 400 on a missing/non-positive projectId;
      resolve localized role names (ru for ru requests, pl otherwise, with fallback to code); reflect
      committed changes on the next read.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.8, 4.9, 24.4_

  - [x] 7.6 Implement admin-staff internal-attribute masking
    - In `ReadOnlyAdminService`, widen the gate from `isCallerAdmin()` to `isCallerAdminStaff()`
      (ADMIN + MANAGER/FOREMAN/ESTIMATOR/FINANCIER; false for WORKER/CLIENT). Set
      `getAdminOnlyFields()` to `{workerTypeId, workerTypeCode, workerTypeName, workerTypeActive, nip,
      workerTypeMissing, tags}` and omit (not blank) them from the view for non-admin-staff readers;
      `assignmentStatus` is never masked.
    - _Requirements: 4.10, 4.11_

  - [x] 7.7 Write unit tests for view field preservation and secret-field exclusion
    - Assert existing field names/meanings kept, no rate/tariff/tier/cost/password/token/OTP present,
      and that WORKER/CLIENT readers get no internal fields but still get `assignmentStatus`.
    - _Requirements: 4.5, 4.7, 4.11_

- [x] 8. Last-ACTIVE invariants and assignment-status (deactivate/reactivate)
  - [x] 8.1 Implement the last-ACTIVE-MANAGER and last-ACTIVE-CLIENT guards
    - Reject a remove or deactivate that would drop the last ACTIVE MANAGER (or CLIENT) to zero with
      409 `error.project.member.last.manager` / `.last.client`, counting only ACTIVE members, for
      ADMIN and non-ADMIN alike; a rejected op changes no row/audit/notification; concurrency keeps at
      least one ACTIVE.
    - _Requirements: 9.1, 9.2, 9.3, 9.5, 9.6, 9.7, 9.8, 7.5_

  - [x] 8.2 Implement deactivate/reactivate via the PATCH Attribute_Update
    - On `assignmentStatus` present: set ACTIVE→INACTIVE or INACTIVE→ACTIVE in one transaction, keep
      id/user/project/role/workerType/tags unchanged, 404 for a missing pair, 400 for a missing/invalid
      status, 409 lifecycle lock; idempotent no-op returns 200 with the current view and writes no
      audit/notification; INACTIVE members stay listed, excluded from ACTIVE counts and invariants.
    - _Requirements: 27.1, 27.2, 27.3, 27.4, 27.5, 27.6, 27.7, 27.8, 27.10_

  - [x] 8.3 Write property test for the last-ACTIVE-MANAGER/CLIENT survival invariant
    - **Property 8: The last ACTIVE MANAGER and last ACTIVE CLIENT always survive**
    - **Validates: Requirements 9.5, 8.1, 27.2, 27.7**

- [x] 9. Worker type attribute-update and the FOR-05-06 delete guard
  - [x] 9.1 Implement worker-type set/replace via the PATCH Attribute_Update
    - On `workerTypeId` present (and no `assignmentStatus`): set/replace the worker type of a WORKER
      member in one transaction; reject a non-WORKER target with
      `error.project.member.worker.type.not.allowed`; reject a missing/null/non-integer id with
      `error.project.member.worker.type.invalid` at the mandatory-fields step; reject a nonexistent or
      inactive (non-current) type with `error.project.member.worker.type.invalid`; 404 for a missing
      pair; same-type request is idempotent (200, no audit/notification) even if inactive. Keep the
      Worker_Type-present ⇒ role=WORKER invariant. Concurrency serialized, never 5xx.
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.14, 14.15_

  - [x] 9.2 Write property test for the Worker_Type-present ⇒ WORKER invariant
    - **Property 3: Worker_Type present implies the Project_Role is WORKER (invariant)**
    - **Validates: Requirements 14.1**

  - [x] 9.3 Implement the `workerTypeMissing` flag and the downstream worker-type read
    - Compute `workerTypeMissing = (role==WORKER and workerTypeId is null)` for Internal_Attribute_
      Viewers (omitted otherwise). Provide the per-project read of each WORKER member (userId,
      membershipId, assignmentStatus, workerTypeId/code incl. inactive, explicit null for an
      Uncategorized_Worker) that modifies no row.
    - _Requirements: 14.13, 14.16_

  - [x] 9.4 Write property test for `workerTypeMissing` consistency
    - **Property 4: `workerTypeMissing` consistency**
    - **Validates: Requirements 14.16**

  - [x] 9.5 Implement tag replace via the PATCH Attribute_Update
    - On `tags` present (and neither status nor worker type): replace the whole normalized tag list,
      keep id/user/project/role/workerType/status unchanged, 404 for a missing pair, 409 lifecycle
      lock, idempotent no-op (same case-sensitive positions) returns 200 with no audit/notification;
      reorder/case change counts as a change; discard tags on remove.
    - _Requirements: 15.2, 15.5, 15.6, 15.7, 15.11_

  - [x] 9.6 Add the FOR-05-06 worker-type delete guard
    - In `WorkerTypeService`, reject deletion of a worker type referenced by any Project_Member with
      409 `error.worker.type.in.use`, leaving the type and members unchanged; deactivation stays
      allowed and the Team_API reports the inactive type with `active=false`.
    - _Requirements: 14.10, 14.11_

  - [x] 9.7 Write unit test for the idempotent attribute-update no-ops
    - Cover worker-type, tag, and status no-ops: 200 with current view, no audit row, no notification.
    - _Requirements: 14.7, 15.6, 27.6_

- [x] 10. Candidate lookup
  - [x] 10.1 Implement `GET /api/project-members/candidates`
    - Paginated candidate search excluding current members (any status) and Inactive_Users, including
      INVITED+active users and uninvited WORKER records; `term` trimmed case-insensitive substring on
      name/email; `role` == candidate Company_Role; `block` == candidate's compatible block; default
      page 20, max 50, order by name then id, with a total count; 400 on an unassignable role, a bad
      block, page<1/negative/term>100; 403 for READ-only callers; 404 for a non-accessible/nonexistent
      project. No password/token/rate/cost/worker-type/NIP/tag.
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9, 11.10, 11.11, 11.12_

  - [x] 10.2 Write property test for candidate-lookup soundness
    - **Property 10: Candidate lookup is sound against its filters**
    - **Validates: Requirements 11.1, 11.2, 11.3, 11.11**

- [x] 11. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Audit and in-app notifications
  - [x] 12.1 Write the audit snapshot per team change
    - Write exactly one Audit_Log row per assign (CREATE), worker-type/tag/status change (UPDATE), and
      remove (DELETE) with the `Member_Snapshot` (userId, projectId, roleCode, workerTypeCode or empty,
      assignmentStatus, tags) and the performing user id; no row on an idempotent no-op or a rejected
      op; an audit-write failure rolls back the whole operation; the snapshot never carries a
      password/token/rate/tariff/tier/cost.
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.6, 17.7, 17.8_

  - [x] 12.2 Emit the team notifications and invalidate the access cache
    - After commit, create exactly one in-app notification for the affected member on assign
      (member-assigned), status change (status-changed), worker-type change (worker-type-changed, to
      the worker only, no tier/rate/cost), and remove (member-removed, no deep-link); none for a
      self-operation, a tag change, an idempotent no-op, or a rejected op; a NotificationService
      failure is logged and the success response is still returned. Invalidate `ProjectAccessCache`
      for the affected user after commit and before the response on assign/remove/deactivate/reactivate.
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 18.1, 18.2, 18.3, 18.4, 18.5, 18.6, 18.7, 18.8, 18.10, 18.11, 18.12_

- [x] 13. Readiness gate
  - [x] 13.1 Implement `GET /api/project-members/readiness`
    - Return `{key:"team", state, counts}` where each count is ACTIVE members of that role and state is
      DONE iff ACTIVE FOREMAN ≥ 1 (BLOCKED otherwise); 403 for callers without READ; 404 for a
      non-accessible/nonexistent project; reflect every committed change.
    - _Requirements: 20.1, 20.2, 20.9, 20.10_

  - [x] 13.2 Write property test for readiness ⇔ ACTIVE FOREMAN
    - **Property 9: Readiness is DONE exactly when an ACTIVE FOREMAN exists**
    - **Validates: Requirements 20.1, 20.2, 20.3, 27.8**

- [x] 14. Client-registration tags extension and the worker flows
  - [x] 14.1 Extend `ClientRegistrationService` with optional tags
    - Add an optional `tags` field to the `POST /api/users/client` request, passed through to the
      membership assign with Tag_Normalization; the contract and OTP email are otherwise unchanged; a
      request without tags behaves exactly as before.
    - _Requirements: 12.1, 12.3, 12.4, 12.5, 12.6, 15.2_

  - [x] 14.2 Implement the `Worker_Record_Flow` (`POST /api/users/worker`)
    - Create one uninvited WORKER user (no password, cannot log in, active=true) + one WORKER
      membership (status ACTIVE, submitted worker type or Uncategorized, normalized tags) atomically;
      send no email; role fixed server-side; validate Worker_Kind/name/contact/email/phone/NIP/worker
      type/tags per the record-flow order; 409 on a duplicate email (case-insensitive, concurrency-safe,
      none when no email); 409 lifecycle lock; 404 non-accessible/nonexistent project; 403 missing
      CREATE+EDIT; roll back fully on any failure.
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 13.8, 13.9, 13.10, 13.11, 13.12, 14.2, 15.2_

  - [x] 14.3 Implement the `Worker_Invitation_Flow` (`POST /api/users/worker/{id}/invite`)
    - Send/re-send the FOR-03-02 staff password-set email to a not-yet-activated WORKER with a stored
      email, set status to "invited", issue a fresh password-set link invalidating earlier ones, 200;
      400 `error.worker.email.required` when no email; 404 for a nonexistent user, 400
      `error.worker.invite.not.allowed` for a non-WORKER, 409 `error.worker.already.active` for an
      activated user; 403 missing CREATE+EDIT; send no email on any rejection.
    - _Requirements: 13.17, 13.18_

  - [x] 14.4 Write integration tests for the registration/worker flows against the Dockerized stack
    - Client invitation (one user + one membership + one OTP email), Worker_Record_Flow (PERSON/COMPANY,
      with/without worker type, with/without email, NIP, duplicate email), Worker_Invitation_Flow
      (invite/re-send/email-required/already-active), and ProjectAccessCache propagation.
    - _Requirements: 12.1, 13.1, 13.7, 13.17, 13.18, 16.1_

- [x] 15. Project-creation restriction and multi-client DTO projection
  - [x] 15.1 Restrict `members[]` at project creation
    - In `ProjectService.create`, reject a `members[]` entry under the Worker_Role with 400
      `error.project.member.role.not.allowed.at.creation`, any entry carrying a non-null `workerTypeId`
      with 400 `error.project.member.worker.type.not.allowed`, an ADMIN/non-system role with
      `error.project.member.role.not.assignable`, and a `projectRoleId` ≠ Company_Role with
      `error.project.member.role.mismatch`; accept Client_Role for a CLIENT user; 409
      `error.project.member.duplicate` for a repeated userId; roll back the whole creation on any
      failure; validate at request time; apply the per-entry error order of Requirement 26 criterion 5.
    - _Requirements: 26.1, 26.2, 26.3, 26.4, 26.5, 26.9, 26.10, 26.11, 14.4, 25.1, 25.8_

  - [x] 15.2 Add the multi-client `clients` projection and CLIENT suppression to the DTOs
    - Add a `clients` list (one entry per CLIENT member, ordered by membership id, same shape as
      `client`) to `ProjectReadDto`/`ProjectListDto`; keep `client` = lowest-id CLIENT = `clients[0]`
      (null when none); keep `members` for non-CLIENT callers; omit `members`, `client`, and `clients`
      entirely for a CLIENT caller; apply the admin-staff masking to the members/clients/client
      projections (strip worker type/tier/NIP/internal note/tags; keep `assignmentStatus`). Extend
      `ProjectMemberSummaryDto` with `assignmentStatus` and the masked internal fields.
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.8, 19.9_

  - [x] 15.3 Write unit tests for the projection and the creation restriction
    - Cover the multi-client `clients`/`client` projection, CLIENT suppression, the worker-type delete
      guard (409), and the project-creation role/worker-type/mismatch/duplicate rejections with
      rollback.
    - _Requirements: 19.1, 19.9, 25.1, 25.8_

- [x] 16. Backend i18n and FOR-03-08 regression updates
  - [x] 16.1 Add backend message bundles (pl + ru)
    - Add non-blank pl and ru text for every code this spec introduces/uses
      (`error.project.member.role.not.assignable`, `.role.mismatch`, `.user.inactive`, `.last.manager`,
      `.last.client`, `.role.not.allowed.at.creation`, `.worker.type.invalid`, `.worker.type.not.allowed`,
      `.tag.invalid`, `error.worker.nip.invalid`, `error.worker.email.required`,
      `error.worker.invite.not.allowed`, `error.worker.already.active`, `error.project.team.locked`,
      `error.worker.type.in.use`), returned in the request language.
    - _Requirements: 24.2, 24.5, 18.9_

  - [x] 16.2 Update FOR-03-08 regression tests TC-RP-03 / REG-03 / REG-05
    - Assert every `ProjectMemberController` handler (incl. the new UPDATE handlers) resolves to
      `PROJECT_MEMBERS`; WORKER/CLIENT get access-denied everywhere; FOREMAN/ESTIMATOR/FINANCIER get
      access-denied on write endpoints; ADMIN succeeds; and `PROJECT_MEMBERS` has exactly the
      Requirement 1 grants with no `PROJECT_TEAM` row.
    - _Requirements: 2.2, 25.3, 25.4, 1.1, 1.7_

- [x] 17. Checkpoint — Ensure backend tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 18. Frontend: Team tab registration and shell wiring
  - [x] 18.1 Register the `team` tab in `WORKSPACE_TABS`
    - Add `{ key:'team', stage:'design', owner:'FOR-05', requiredPermission:{resource:'PROJECT_MEMBERS',
      operation:'READ'}, labelKey:'workspace.tab.team' }` immediately after `documentSigning` and
      before the first execution-stage tab, so `team` is the last design-stage entry; verify
      design-stage shows it in the main strip and execution-stage in the design selector; permission
      gating and `/projects/:projectId/team` routing with tab memory.
    - _Requirements: 21.1, 21.2, 21.3, 21.4, 21.5, 21.6_

- [x] 19. Frontend: Team tab view (three blocks, responsive, status)
  - [x] 19.1 Build `TeamTab` with three blocks and member rendering
    - Fetch and render all members in the ADMIN_STAFF/WORKERS/CLIENTS blocks in server order, each
      with a localized title + count and empty state; per-member name/email, Assignment_Status badge,
      invited/not-invited/inactive badges, role name (ADMIN_STAFF), worker kind/contact/worker type or
      missing-type warning (WORKERS); desktop table ≥768px and mobile cards <768px; loading, error+retry,
      and the "active foreman required" hint; hide all internal attributes for non-admin-staff viewers.
    - _Requirements: 22.1, 22.2, 22.3, 22.4, 22.5, 22.6, 22.7, 22.8, 22.9, 22.10_

  - [x] 19.2 Write UI component tests for the three-block rendering
    - Cover block ordering/counts/empty states, responsive table/card layouts, the foreman hint, and
      the internal-attribute hiding for WORKER/CLIENT viewers.
    - _Requirements: 22.1, 22.5, 22.6, 22.7, 22.10_

- [x] 20. Frontend: Team tab editing (dialogs and actions)
  - [x] 20.1 Build the candidate-search add dialogs (staff / worker / client)
    - Per-block "Add" dialogs scoped to the block with candidate search, optional tag input, submit
      disabled until exactly one candidate is selected; the WORKERS dialog adds an optional worker-type
      selector (Active types, no preselection) when the viewer holds `WORKER_TYPES` READ; no role
      selector anywhere; submit under the candidate's Company_Role.
    - _Requirements: 23.1, 23.2, 23.4_

  - [x] 20.2 Build the invite-client and add-new-worker dialogs
    - Invite-client: FOR-03-05 form + optional tags; success shows the new INVITED CLIENT; duplicate
      email keeps the form open and offers a candidate-lookup action pre-filled with the email.
      Add-new-worker: Worker_Kind toggle (person vs company fields), optional email, optional
      worker-type selector, NIP/phone validation, "not invited" badge on success, Invite / Re-send
      action on uninvited/not-activated workers, disable submit while in flight.
    - _Requirements: 12.2, 12.3, 12.7, 13.13, 13.14, 13.15, 13.19, 23.3_

  - [x] 20.3 Build the per-member edit actions
    - Change-worker-type control (preselected current, Active types + current inactive marked, no
      "none", no request on same type) and the "Assign worker type" action on Uncategorized_Workers;
      edit-tags control (prefilled, field-level validation, no request on normalized-equal); deactivate/
      reactivate confirmations; remove confirmation explaining history loss; hide actions the viewer
      lacks and render read-only in a Locked_Status; keyboard/focus accessibility; success refreshes
      the list, failure keeps the dialog open and maps the message code.
    - _Requirements: 14.17, 14.18, 23.5, 23.6, 23.7, 23.8, 23.9, 23.10, 23.11, 23.12, 23.13, 23.14, 23.15, 23.16_

  - [x] 20.4 Write UI component tests for the edit actions
    - Cover the missing-worker-type warning + "Assign worker type" action, the tag filter, action
      gating by operation/lifecycle, dialog focus/Escape behavior, and the duplicate-email recovery
      action.
    - _Requirements: 14.17, 14.18, 15.8, 15.9, 23.12, 23.13_

- [x] 21. Frontend: tag filter, readiness chip, and create-form filter
  - [x] 21.1 Implement the client-side tag filter
    - For Internal_Attribute_Viewers, show tag chips per member and a single-select tag filter (distinct
      tags case-insensitive, first spelling, sorted); filtering updates block counts and shows "no
      matching members", clears when no loaded member carries the tag; hidden when no member has tags;
      never shown to WORKER/CLIENT.
    - _Requirements: 15.8, 15.9_

  - [x] 21.2 Add the `team` readiness chip to `ReadinessWidget`
    - Show one chip (localized gate name + DONE/BLOCKED label with two distinct visual styles) for
      design-stage viewers with `PROJECT_MEMBERS` READ; compute the headline percentage over displayed
      gates; refresh within 2s of a successful team change; hide the chip and show a "could not load"
      indication on a failed readiness request without blocking other tabs.
    - _Requirements: 20.4, 20.5, 20.6, 20.7, 20.8, 20.11_

  - [x] 21.3 Filter `TeamMemberSelect` to admin-staff and add the create-form hint
    - Offer only Admin_Staff_Role users in the FOR-04-13 create form; keep assigning under the user's
      Company_Role; show a static pl/ru hint that workers are added from the Team tab after creation;
      no worker-type field, no worker selection; handle the creation rejections per Requirement 26.
    - _Requirements: 26.6, 26.7, 26.8_

  - [x] 21.4 Write UI component test for the admin-staff `TeamMemberSelect` filter
    - Assert ADMIN/WORKER/CLIENT/non-system users are not offered and the create-form hint renders in
      pl and ru.
    - _Requirements: 26.6, 26.7_

- [x] 22. Frontend i18n (pl + ru)
  - [x] 22.1 Add all Team-tab keys to `pl.json` and `ru.json`
    - Add `workspace.tab.team` ("Zespół"/"Команда") and every Team-tab label, block title, empty state,
      action, add-worker form label, Worker_Kind value, worker status indication, Assignment_Status
      label, tag input/filter text, inactive worker-type mark, missing-type badge + block warning (with
      count placeholder), "Assign worker type", create-form hint, confirmations, readiness gate
      name/state, and success/error messages — identical key sets and matching placeholders in both
      files, each value non-blank and differing from its key; render in the selected language with no
      raw key/code and handle an unknown code with a generic localized message.
    - _Requirements: 24.1, 24.3, 24.6, 24.7, 18.9_

- [x] 23. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard:
    written in Russian, grouped by feature, each case with ID/title/preconditions/numbered
    steps/expected-per-step/covered requirements. This is an API+UI spec, so include API scenarios
    against the Dockerized app (base URL `http://localhost:8080`) for the Team_API and the
    registration/worker flows, and browser-engine UI scenarios for the three Team_Blocks, the
    missing-worker-type warning and its "Assign worker type" action, the admin-staff-only
    `TeamMemberSelect`, the tag filter, and the readiness chip. State per-group repeatability
    (run-id embedded in generated emails such as `worker+{run-id}@example.com`, plus teardown of
    created memberships/users), end with a Regression group covering Requirement 25 criteria 1–4 and
    8, and an MD report template table (test case · step · request/action · expected · actual ·
    status) with a pass/fail/skip summary, the run-id, and the repeatability strategy. Include at
    least one case for each of Requirements 1–24, 26, and 27.
  - _Requirements: 25.5, 25.6, 25.7_

- [x] 24. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; they are the unit,
  integration, UI-component, and property-based tests.
- Each task references specific granular requirement criteria for traceability.
- Checkpoints ensure incremental validation at reasonable breaks.
- Property-based tests validate the twelve universal correctness properties; each property is its own
  sub-task placed next to the code it validates, annotated with its property number and the
  requirement criteria it checks. Per the design's Testing Strategy, use the existing JVM
  property-based library (jqwik), run each property ≥100 iterations, and tag each test
  `// Feature: FOR-05-09-team-selection, Property {n}: {text}`.
- Backend-first ordering (schema → entity/DTO → pure helpers → service rules → endpoints → masking →
  audit/notifications → creation restriction → readiness → registration/worker flows) precedes the
  frontend, which depends on the stabilized API shape.
- The feature spans two git repos: commit backend + migrations + spec from the root repo, and all
  frontend source from inside `foremen-frontend/` (per `.kiro/steering/git-repo-structure.md`).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "3.1", "3.4", "3.6"] },
    { "id": 1, "tasks": ["1.2", "1.3", "3.2", "3.3", "3.5", "3.7"] },
    { "id": 2, "tasks": ["1.4", "2.1"] },
    { "id": 3, "tasks": ["2.2", "5.1"] },
    { "id": 4, "tasks": ["5.2", "6.1", "6.3", "6.4"] },
    { "id": 5, "tasks": ["5.3", "6.2", "7.1", "7.4", "7.5"] },
    { "id": 6, "tasks": ["7.2", "7.3", "7.6", "8.1"] },
    { "id": 7, "tasks": ["7.7", "8.2", "9.1", "9.5", "9.6", "10.1"] },
    { "id": 8, "tasks": ["8.3", "9.2", "9.3", "9.7", "10.2"] },
    { "id": 9, "tasks": ["9.4", "12.1", "13.1"] },
    { "id": 10, "tasks": ["12.2", "13.2", "14.1", "14.2", "14.3"] },
    { "id": 11, "tasks": ["14.4", "15.1", "15.2", "16.1", "16.2"] },
    { "id": 12, "tasks": ["15.3", "18.1"] },
    { "id": 13, "tasks": ["19.1", "20.1", "20.2", "20.3", "21.3"] },
    { "id": 14, "tasks": ["19.2", "20.4", "21.1", "21.2", "21.4"] },
    { "id": 15, "tasks": ["22.1"] }
  ]
}
```
