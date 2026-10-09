# Requirements Document

## Introduction

This spec (**FOR-05-09-team-selection**) delivers **team selection and assignment** for a project
in the design stage: the people who will run, execute, finance, and receive the renovation are
attached to the project as `ProjectMember` rows. Every membership carries the user's single global
role (their Company_Role) and an **assignment status** (ACTIVE or INACTIVE). It turns the parent
design's Team surface (`design.md` §1 item 7, §2 "Team selection", §4.2 `ProjectMember`, §5
`PROJECT_MEMBERS`, §6.3 readiness gate `team`, §7 API sketch, Property 17) into concrete, testable
requirements, reusing the **existing** project-scoped ABAC resource `PROJECT_MEMBERS` rather than
introducing a new resource row.

**Staff model.** A project staff entry is the triple **(user, Project_Role, Worker_Type)**, plus an
Assignment_Status and Assignment_Tags. The **Project_Role is always equal to the user's current
Company_Role** — there is exactly one globally assigned role per user, and a project cannot re-role
a member (D2). The Worker_Type exists only for a WORKER member, is chosen in the same add / invite
dialog as the worker, is optional, and can be corrected later; a WORKER without a Worker_Type is
valid but flagged with a warning.

The feature has six parts:

1. **Team membership API (backend).** List, assign, update assignment attributes (worker type,
   tags, assignment status), deactivate / reactivate, and remove project members through
   `/api/project-members`, guarded by the **existing** project-scoped ABAC resource `PROJECT_MEMBERS`
   with its current grants (`ADMIN CRUD | MANAGER CRUD | FOREMAN R | ESTIMATOR R | FINANCIER R |
   WORKER — | CLIENT —`), with server-side team-composition rules (population compatibility, inactive
   users, last ACTIVE MANAGER / last ACTIVE CLIENT protection, lifecycle lock) and an enriched read
   model the UI can render without extra lookups.
2. **Candidate lookup, client invitation, and worker add / invitation.** A `PROJECT_MEMBERS`-gated
   candidate search so a MANAGER can pick staff, workers, and existing clients without holding the
   global `USERS` grant; reuse of the FOR-03-05 client-registration flow to invite a new CLIENT
   straight from the Team tab (multi-client, parent Property 17); a sibling worker-record flow that
   adds a new WORKER (a person, or a company such as an external contractor) **without** an
   invitation (record-keeping only, no login) and a later invite / re-send action by which the WORKER
   sets their **own** password through the staff invitation link (distinct from the CLIENT OTP flow).
3. **Workspace "Team" tab (frontend).** A new design-stage tab `team` ("Zespół" / "Команда") in
   the FOR-05-01 workspace shell: members rendered in three separate blocks (admin staff, workers,
   clients), each with its own add / invite actions, plus change worker type / edit tags /
   deactivate / reactivate / remove actions driven by the caller's `PROJECT_MEMBERS` operations,
   desktop table and mobile cards per block, pl + ru localization. The Project_Role is immutable, so
   there is no role-change control.
4. **Readiness contribution.** The `team` readiness gate (parent §6.3: `DONE` iff the project has
   at least one ACTIVE FOREMAN member, otherwise `BLOCKED`) computed server-side and shown as a named
   chip in the FOR-05-01 `ReadinessWidget`.
5. **Assignment attributes.** A WORKER membership carries zero or one FOR-05-06 worker type,
   which feeds margin and real-cost calculation and can be corrected later; a WORKER
   without a worker type is shown with a "missing worker type" warning. Every membership may carry
   up to ten free-text internal tags (for example a specialization or an alias) and an
   Assignment_Status. Worker type and tags are Internal_Attributes, hidden from WORKER and CLIENT
   readers and from every client-facing payload; Assignment_Status is visible to all readers.
6. **Team at project creation.** The FOR-04-13 creation orchestrator (`POST /api/projects`)
   accepts only admin staff in `members[]` plus the client through the client block; workers are
   added only from the Team tab after the project exists, and the FOR-04-13 create form offers only
   admin-staff users (D13).

### Scope boundaries

- **In scope:** reusing the existing `PROJECT_MEMBERS` resource and its current grants (no new
  resource seed); keeping / annotating the existing `ProjectMemberController` with
  `@PermissionResource("PROJECT_MEMBERS")`; project-scoped access on every membership endpoint;
  the new attribute-update and deactivate / reactivate operations; team-composition validation
  (including the WORKER population rule); candidate lookup; Team-tab client invitation via the
  existing FOR-03-05 flow; Team-tab worker record creation (person or company) without invitation
  and a later worker invite / re-send that lets the worker set their own password; the optional
  worker type of a WORKER membership (schema, validation, assignment, correction, audit,
  missing-worker-type warning); assignment tags; the Assignment_Status field and its lifecycle
  (default ACTIVE, deactivate / reactivate, audit, notification, visibility); audit and in-app
  notification of membership changes; multi-client reconciliation of the project list/read DTOs plus
  the CLIENT-caller suppression of the members projection; the `team` readiness gate; the
  three-block Team tab UI and its i18n; the restriction of the project-creation `members[]` to
  Admin_Staff_Roles (Client_Role kept for compatibility) and the matching admin-staff filter of the
  FOR-04-13 `TeamMemberSelect` (a small frontend change, Requirement 26).
- **Out of scope (Non-Goals):**
  - **FOR-06 execution-stage concerns** — scheduling people on the Gantt, work-report
    attribution, assigning ACTIVE members to specific jobs and reporting their progress, and any
    rule that ties membership removal to existing work reports. This spec owns the Assignment_Status
    field and its lifecycle, but the consumption of ACTIVE status by execution-stage job assignment
    is FOR-06. FOR-06 may tighten the rules of this spec for `ACTIVE` projects.
  - **Changing a user's global role** — a person's capacity is their Company_Role, changed only in
    the admin area (user administration), never per project. This spec cannot re-role a member.
  - **Payroll and worker rates** — `WorkerServiceRate`, salary and real-cost computation, and
    payouts belong to FOR-10 / FOR-11; the worker-type dictionary and its tier percentages belong
    to FOR-05-06. This spec only records which worker type, if any, a WORKER membership has (a
    read-only input for those specs). The Team tab shows no rate, tier percentage, or cost.
  - **Aggregate readiness endpoint** — computing the other parent §6.3 gates (rooms, works,
    priced, bom, offer). This spec contributes only the `team` gate.
  - **Notification channels beyond in-app** — email / SMS / Telegram and asynchronous fan-out
    remain future work (FOR-05-07 Non-Goals). The client invitation email is the existing
    FOR-03-05 email, unchanged; the worker invitation email is the standard FOR-03-02 staff
    password-set email, unchanged.
  - **Client portal (FOR-09)** — CLIENT users have no `PROJECT_MEMBERS` grant and see no Team tab;
    any minimal client-facing contact the client portal needs is a FOR-09 concern.
  - **Other changes to project creation UX** — apart from the admin-staff filter of
    `TeamMemberSelect` (Requirement 26, D13), the FOR-04-13 create form is unchanged: it gains no
    worker-type picker and no worker selection, and the client block stays as it is.
  - **Legal data of company workers beyond name, contact person, and NIP** (REGON, address,
    representation) needed for FOR-05-08 subcontract documents (see Q8).
  - **Custom (non-system) project roles** — only the system roles listed in the Glossary are
    assignable in this spec (Q7, future).

### Relationship to existing specs and code (source of truth)

- **Parent overview** (`OVERVIEW.md`) — child row 09 (`FOR-05-09-team-selection`, full-stack,
  depends on 01). The parent `design.md` §16 table still uses the pre-renumbering name
  `FOR-05-10-team-selection`; FOR-05-07 requirements also still say "FOR-05-09 turns it into a
  contract" (contract signing is now FOR-05-08). This spec is the renumbered team-selection spec.
- **Parent design §4.2 / §4.3 note / Property 17** — `ProjectMember{ project, user, projectRole }`,
  scope `project.id`; a project may have `>= 1` CLIENT member, added at any lifecycle stage; ABAC
  grants apply to every CLIENT member equally.
- **Parent design §5** — the `PROJECT_MEMBERS` row of the base matrix. The parent §5 matrix sketch
  used `WORKER R(own)` and no FINANCIER; this spec instead reuses the resource **as currently
  seeded** (FINANCIER keeps READ, WORKER has no grant). That divergence from the parent §5 sketch is
  an accepted deviation recorded in D1, not an open question.
- **Parent design §6.3** — `gate("team", DONE IF project has FOREMAN member ELSE BLOCKED)`;
  `headlinePct = round(100 × count(DONE) / count(gates))`. This spec counts ACTIVE FOREMAN members.
- **FOR-03-04 (project ownership)** — `project_members` table (changeset `014`, unique constraint
  `uk_project_members_user_project` on `(user_id, project_id)`), `ProjectMemberEntity`,
  `ProjectMemberDao`, `ProjectMemberService` (`assign` / `remove` / `listMembers` /
  `listProjects`, each mutation invalidates `ProjectAccessCache`), `ProjectMemberController`.
- **FOR-03-05 (client registration)** — `ClientRegistrationService.register` and
  `POST /api/users/client` (guarded `PROJECTS/EDIT`): creates an INVITED CLIENT user, sends the
  client-portal invitation email (OTP flow), and assigns the CLIENT membership server-side.
- **FOR-03-08 (API protection)** — `@PermissionResource` / `@PermissionOperation` /
  `@RequiresPermission`, `PermissionAnnotationValidator`, and its test cases TC-RP-03 / REG-03 /
  REG-05, which currently assert that `ProjectMemberController` resolves to `PROJECT_MEMBERS`.
- **FOR-04-13 (Project)** — `ProjectService` creation orchestrator (assigns `members[]` and the
  client block through `ProjectMemberService.assign`), `ProjectReadDto` / `ProjectListDto`
  carrying `members` and a single derived `client` (`deriveClient` = first CLIENT member);
  `TeamMemberSelect` in the create form (`ProjectFormSheet`). This spec restricts `members[]` to
  Admin_Staff_Roles (Client_Role kept for compatibility), filters `TeamMemberSelect` to admin-staff
  users (Requirement 26, D13), adds the `clients` list, and suppresses the `members` collection for
  CLIENT callers.
- **FOR-05-01 (workspace shell)** — `WORKSPACE_TABS` contract, `resolveWorkspaceTabs`,
  `resolveDesignSelectorTabs` (design tabs reachable from execution-stage projects), `stageOf`
  (`ACTIVE`/`COMPLETED` → execution), `ReadinessWidget` (currently rendered without data).
- **FOR-05-07 (offer approval)** — the generic in-app notification service (Requirement 13);
  `OfferNotificationEmitter` resolves "the project MANAGER" from `project_members`.
- **FOR-05-08 (document signing)** — resolves CLIENT signers and the client representative from
  `project_members`.
- **FOR-05-06 (packages & margins)** — the `WorkerType` cost-tier dictionary (table
  `worker_types`: `code`, `name_ru`, `name_pl`, `tier_pct`, `is_base`, `order_no`, `active`;
  `WorkerTypeEntity`, `WorkerTypeService`, `/api/worker-types`, ABAC resource `WORKER_TYPES`;
  seeded tiers `BASE`, `HIRED_NO_TOOLS`, `HIRED_SOLE_TRADER`, `FIRM`). FOR-05-06 Requirement 8
  defers "assign a WorkerType per project member during team selection" to this spec. This spec
  adds a guard to the FOR-05-06 WorkerType delete path: deleting a Worker_Type referenced by any
  Project_Member is rejected with HTTP 409 and message code `error.worker.type.in.use`
  (Requirement 14 criterion 11); deactivation stays allowed (Requirement 14 criterion 10).
- **ReadOnlyAdminService field masking** — the existing non-admin field masking mechanism:
  `getAdminOnlyFields()` returns a Set of field names and `maskAdminOnlyFields(model)` nulls them for
  non-admin callers, gated by `isCallerAdmin()`. This spec extends the gate predicate from "is ADMIN"
  to "is admin-staff" (not WORKER and not CLIENT) so that Internal_Attributes are masked only from
  WORKER and CLIENT callers (D12).
- **FOR-10 / FOR-11 (payroll, real costs)** — future read-only consumers of the worker type of a
  WORKER membership, including Uncategorized_Workers reported with a null Worker_Type.

### Baseline: existing implementation this spec builds on

Verified in the codebase while drafting:

- `ProjectMemberController` (`/api/project-members`) exposes `POST` (assign, body
  `{userId, projectId, projectRoleId}`), `DELETE ?userId&projectId`, `GET ?projectId`, and
  `GET /projects?userId`. Each handler carries `@RequiresPermission(resource = "PROJECT_MEMBERS")`.
- There is **no attribute-update (UPDATE) operation and no deactivate / reactivate operation** yet.
- The controller and `ProjectMemberService` perform **no project-membership check**: any
  non-ADMIN caller holding a `PROJECT_MEMBERS` grant can read or mutate the team of any project id,
  and `GET /projects?userId` returns any user's project ids.
- `ProjectMemberService.assign` checks duplicates (409), user existence (404), and role existence
  (404), but not project existence, user activity, or role suitability; no audit row is written.
- The response `ProjectMemberResponse{id, userId, projectId, projectRoleId, projectRoleCode}`
  carries no user name or localized role name.
- Resource `PROJECT_MEMBERS` is seeded (changeset `015`) with ADMIN CRUD, MANAGER CRUD,
  FOREMAN R, FINANCIER R, and no WORKER / CLIENT grant. ESTIMATOR (changeset `136`) mirrored
  FOREMAN's grants at seed time, so ESTIMATOR also holds `PROJECT_MEMBERS` READ. All four operations
  (CREATE, READ, UPDATE, DELETE) are already defined on the resource via the ADMIN / MANAGER CRUD
  grants. No `PROJECT_TEAM` resource exists and none is created by this spec.
- No backend readiness endpoint exists; the frontend has no Team tab.
- `POST /api/users/client` (`UserController.registerClient`) is guarded
  `@RequiresPermission(resource = "PROJECTS", operation = "EDIT")`; `ClientRegistrationService`
  fixes the role to `CLIENT` server-side, creates the user through `UserService.createClient`
  (INVITED, null password, client-portal OTP invitation email from the `afterCreate` hook), then
  calls `ProjectMemberService.assign` in the same transaction. No worker-record or worker-invitation
  flow exists.
- The `users` row carries `name`, `email`, `phone`, `role_id`, `active`, `status`, `locale`, and
  `display_preferences`; there is no person/company kind, contact person, or NIP field.
- `project_members` has no worker-type, tag, or assignment-status column.
- `ProjectService.create` assigns each `members[]` entry through
  `ProjectMemberService.assign(userId, projectId, projectRoleId)` with no Project_Role restriction,
  so a CLIENT (or WORKER) entry in `members[]` is accepted today; `ProjectMemberInput` has no
  `workerTypeId`. `TeamMemberSelect` lists every user from `GET /api/users` with no Company_Role
  filter and assigns each picked user under the user's Company_Role (resolved via
  `GET /api/users/{id}`).
- Changeset `125` seeds `WORKER_TYPES` with ADMIN CRUD and READ for MANAGER, FOREMAN, and
  FINANCIER, so a MANAGER can already read `/api/worker-types`.

### Decisions taken in this spec

- **D1 — reuse the existing `PROJECT_MEMBERS` resource; introduce no new resource.** All membership
  endpoints keep (and are explicitly annotated with) the project-scoped resource `PROJECT_MEMBERS`,
  which already exists with the four operations defined. No new resource row (no `PROJECT_TEAM`) is
  seeded. The spec keeps the current grants as-is (ADMIN CRUD, MANAGER CRUD, FOREMAN READ,
  ESTIMATOR READ, FINANCIER READ, no WORKER or CLIENT grant); it adds **no** WORKER grant and
  **removes** the FINANCIER grant from nothing. This diverges from the parent §5 matrix sketch
  (which proposed WORKER R(own) and no FINANCIER); the divergence is an accepted deviation because
  reusing the live resource avoids a destructive migration and a dual-resource split. FOR-03-08 test
  cases TC-RP-03 / REG-03 / REG-05 continue to assert `PROJECT_MEMBERS`, extended to the new UPDATE /
  deactivate handlers.
- **D2 — one global role per user; the Project_Role equals the user's Company_Role, always.** There
  is exactly one globally assigned role per user. Every Project_Member's Project_Role is set
  server-side to the user's current Company_Role and is immutable per membership (Requirement 7): a
  project cannot re-role a member. To act in another capacity the user's global Company_Role must
  change, which is user-administration work outside this spec. The existing unique constraint on
  `(user_id, project_id)` is kept. An assign request need not carry `projectRoleId`; if one is
  supplied and differs from the user's Company_Role, the assign is rejected with HTTP 400
  `error.project.member.role.mismatch`.
- **D3 — three disjoint populations by Company_Role.** Because the Project_Role equals the
  Company_Role, the three Team_Blocks are disjoint by construction: CLIENT-Company_Role users form
  the `CLIENTS` block, WORKER-Company_Role users the `WORKERS` block, and every other Company_Role
  the `ADMIN_STAFF` block. There is no way for a client or an external worker to obtain admin-staff
  access through a project role.
- **D4 — the team stays editable until the project is closed.** Editing is allowed in every status
  except `COMPLETED` and `CANCELLED`, matching parent Property 17 (clients may be added in design
  and execution).
- **D5 — no orphaned leadership or client; counted over ACTIVE members.** An operation (remove **or**
  deactivate) that would drop the number of ACTIVE MANAGER members, or of ACTIVE CLIENT members,
  from at least one to zero is rejected. The last ACTIVE MANAGER and the last ACTIVE CLIENT must
  always survive (Q2), so a project can never be left without a responsible manager or a client for
  offers, notifications, and signing.
- **D6 — three Team_Blocks derived purely from Company_Role; no cross-block move.** Members are
  presented and ordered in three blocks: ADMIN_STAFF, WORKERS, CLIENTS. Because the Project_Role is
  the user's immutable Company_Role (D2), a member's block is fixed for the life of the membership;
  moving a person between blocks is impossible within this spec (it would require a global role
  change, out of scope). Removing and re-adding under a different global role is the only path, and
  that path depends on the admin area changing the user's Company_Role first.
- **D7 — worker records and worker invitations are a sibling flow, split in two.** A new
  Worker_Record_Flow (`POST /api/users/worker`) creates a WORKER user **without** an invitation:
  status reflects "not invited", the user has no password and cannot authenticate; the record exists
  only for assigning and record-keeping. A later Worker_Invitation_Flow
  (`POST /api/users/worker/{id}/invite`) sends / re-sends the staff invitation to such a user. Both
  mirror the Client_Registration_Flow instead of generalizing it, so the FOR-03-05 client contract
  stays unchanged. Both are gated like the client invitation: `PROJECT_MEMBERS` CREATE plus
  `PROJECTS` EDIT.
- **D-new — a WORKER sets their own password; a CLIENT uses an OTP.** The worker invitation email is
  the standard FOR-03-02 **staff** password-set email: the WORKER follows the invitation link and
  sets their own password, then logs in to the staff application. This is deliberately different from
  the CLIENT invitation, which uses the client-portal OTP flow. The worker lifecycle is:
  created-uninvited (no password, cannot log in) → invited (email sent, password-set link issued) →
  active (password set). Re-sending the invitation is allowed while the worker is still not activated.
- **D8 — a company worker is one WORKER user.** A company (external contractor, firm) is one user
  with Company_Role WORKER whose display name is the company name; the Worker_Kind
  (`PERSON` / `COMPANY`), optional contact person, and optional NIP are stored as new nullable user
  attributes. Email is **optional** for an uninvited worker record and becomes required only to
  invite the worker later. Legal fields beyond name, contact person, and NIP are deferred (Q8).
- **D9 — worker-type options come from the existing dictionary endpoint.** MANAGER already holds
  `WORKER_TYPES` READ (changeset `125`), so the Team_Tab reads the options from
  `GET /api/worker-types`; this spec adds no `WORKER_TYPES` grant and no `PROJECT_MEMBERS`-gated
  options endpoint. The Team_Tab hides worker-type input for a viewer lacking `WORKER_TYPES` READ.
- **D10 — optional current worker type plus audit, no effective dating.** A WORKER membership
  stores zero or one current Worker_Type; history is reconstructable from the Audit_Log (Q9: no
  effective dating; the current worker type is assumed stable for the project lifetime but stays
  editable to correct mistakes). The Worker_Type is chosen in the add / invite dialog of the worker
  or assigned later through `PROJECT_MEMBERS` UPDATE. Once set, a Worker_Type can be replaced by
  another Active_Worker_Type but not cleared (a change request without a Worker_Type is rejected with
  `error.project.member.worker.type.invalid`). A WORKER membership without a Worker_Type (an
  Uncategorized_Worker) is valid: the Team_Tab warns about it, and read-only consumers receive it
  with an explicit null Worker_Type. The schema changeset performs no backfill, so existing WORKER
  memberships start as Uncategorized_Workers. The enforced invariant is one-directional:
  Worker_Type present ⇒ Project_Role WORKER (Requirement 14 criterion 1).
- **D11 — tags are free text per membership.** Assignment_Tags have no dictionary, belong to one
  Project_Member (not to the user), and are discarded when the membership is removed.
- **D12 — worker type, tags, and NIP are internal; masking is extended to admin-staff.** The
  Team_API returns the Internal_Attributes only to Internal_Attribute_Viewers (ADMIN plus every
  Admin_Staff_Role: MANAGER, FOREMAN, ESTIMATOR, FINANCIER) and omits them for WORKER and CLIENT
  readers, so a pay tier never leaks between workers and never reaches a client. This reuses the
  existing `ReadOnlyAdminService` masking mechanism (`getAdminOnlyFields()` +
  `maskAdminOnlyFields(model)`), with the gate predicate widened from `isCallerAdmin()` to
  "caller is admin-staff" (not WORKER and not CLIENT). The same extended masking applies to the
  `ProjectReadDto` / `ProjectListDto` members projection, so tags, worker type, NIP, and internal
  status notes never reach WORKER or CLIENT. The Assignment_Status itself is **not** internal and is
  returned to every reader.
- **D13 — project creation assigns admin staff and clients only.** The Project_Creation_Orchestrator
  (`POST /api/projects`) rejects a `members[]` entry under the Worker_Role with HTTP 400
  `error.project.member.role.not.allowed.at.creation`, and any `members[]` entry carrying a
  `workerTypeId` with HTTP 400 `error.project.member.worker.type.not.allowed`, rolling back the
  whole creation. A `members[]` entry under the Client_Role stays accepted, subject to D3, because
  the orchestrator accepts it today and rejecting it would break existing callers; the create form
  no longer offers it, and clients are added through the client block. The FOR-04-13
  `TeamMemberSelect` offers only users whose Company_Role is an Admin_Staff_Role. Workers are added
  only from the Team_Tab after the project exists (Requirement 26). Resolves Q11.
- **D14 — assignment status is a visible lifecycle field.** Every Project_Member has an
  Assignment_Status of ACTIVE or INACTIVE, defaulting to ACTIVE on assign. Deactivation is a soft
  action: the membership and its history remain visible (not deleted). A `PROJECT_MEMBERS` UPDATE
  operation deactivates (ACTIVE → INACTIVE) or reactivates (INACTIVE → ACTIVE); both are audited and
  notify the affected member. INACTIVE members still appear in list, blocks, and history, visually
  marked. Remove hard-deletes a membership (loses history) and is reserved for correcting mistakes;
  deactivate is the normal "person left" action that keeps history. The readiness FOREMAN count and
  the last-MANAGER / last-CLIENT invariants count only ACTIVE members (D5).

### Open questions

- **Q1 — RESOLVED.** Reuse the existing `PROJECT_MEMBERS` resource with its current grants; no new
  resource row, no `PROJECT_TEAM` (D1). FINANCIER keeps READ and WORKER has no grant, an accepted
  deviation from the parent §5 sketch.
- **Q2 — RESOLVED.** The last ACTIVE CLIENT and the last ACTIVE MANAGER must always survive. Both
  remove and deactivate are blocked when they would drop the last ACTIVE one to zero (D5,
  Requirement 9).
- **Q3 — RESOLVED (this spec), deferred downstream.** Assignment_Status is owned here (ACTIVE /
  INACTIVE, deactivate / reactivate, Requirement 27). Whether FOR-06 forbids removing or deactivating
  a WORKER / FOREMAN who has work reports is a FOR-06 concern; this spec does not check work reports.
- **Q4 — readiness rule, deferred to the readiness spec.** Parent §6.3 counts only FOREMAN. Whether
  the `team` gate should also require a MANAGER and/or a CLIENT, or treat an Uncategorized_Worker as
  not-ready, is decided in the readiness spec; this spec keeps the FOREMAN-only rule (Requirement 20).
- **Q5 — aggregate readiness owner, deferred to the readiness spec.** Which spec owns the aggregate
  readiness endpoint that combines all §6.3 gates. Until it exists, the headline percentage is
  computed over the gates the workspace actually has (Requirement 20), i.e. only `team` after this
  spec.
- **Q6 — RESOLVED.** CLIENT must not see project members at all, enforced by ABAC (CLIENT has no
  `PROJECT_MEMBERS` grant) and by the project read / list DTO omitting the `members` collection for
  CLIENT callers (Requirement 19 criterion 9). Any minimal client-facing contact the client portal
  needs is a FOR-09 concern.
- **Q7 — custom roles (future).** Non-system (custom) roles are not assignable project roles in this
  spec. Making them assignable later remains future work.
- **Q8 — legal fields of company workers (deferred).** Do COMPANY workers need more legal data
  (REGON, registered address, legal representative, KRS) for FOR-05-08 subcontract documents? This
  spec stores only company name, optional contact person, and optional NIP (D8).
- **Q9 — RESOLVED.** No effective-dated worker-type history. The current worker type is assumed
  stable for the project lifetime but stays editable to correct mistakes, and the Audit_Log is enough
  to reconstruct changes (D10).
- **Q10 — RESOLVED.** Internal_Attribute_Viewer = ADMIN plus every Admin_Staff_Role (MANAGER,
  FOREMAN, ESTIMATOR, FINANCIER); WORKER and CLIENT readers never see worker type, tags, or NIP. The
  existing masking mechanism is extended with the broader admin-staff predicate (D12).
- **Q11 — RESOLVED by D13.** The create form gains no worker-type picker; `members[]` accepts only
  Admin_Staff_Roles (Client_Role kept for compatibility), `TeamMemberSelect` offers only admin-staff
  users, and workers are added only from the Team_Tab after the project exists (Requirement 26).
- **Q12 — RESOLVED.** With the single-global-role model, a membership's Project_Role always equals
  the user's Company_Role, so the three populations are disjoint by construction and there is no
  cross-population membership to clean up. A membership whose user's Company_Role changes globally is
  reclassified by the new Company_Role on the next read.
- **Q13 — RESOLVED (rule deferred).** A WORKER without a Worker_Type (Uncategorized_Worker) does
  affect downstream payroll / margins: this spec keeps the missing-worker-type warning
  (Requirement 14 criteria 16–18), reports such members with an explicit null Worker_Type to
  downstream consumers (Requirement 14 criterion 13), and notes that the readiness gate MAY treat an
  Uncategorized_Worker as not-ready. The exact readiness rule is deferred to the readiness spec
  (Q4), so Requirement 20 keeps the FOREMAN-only rule for now.

---

## Glossary

- **Team_API**: the backend REST surface under `/api/project-members` that lists, assigns, updates
  the attributes of (worker type, tags, assignment status), deactivates, reactivates, and removes
  project members, and serves candidate lookup and the team readiness gate.
- **Team_Service**: the backend service behind the Team_API (today `ProjectMemberService`) that
  enforces the team-composition rules and persists `project_members` rows.
- **Project_Member**: one `project_members` row of one project; a project staff entry defined by
  the triple (user, Project_Role, Worker_Type), where the Project_Role always equals the user's
  Company_Role (D2) and the Worker_Type is present only for the Worker_Role and is optional there
  (D10), plus the member's Assignment_Status and Assignment_Tags.
- **Project_Role**: the `roles` row referenced by a Project_Member (`project_role_id`); always equal
  to the user's Company_Role at assign time and immutable per membership (D2).
- **Company_Role**: the user's single global role on the `users` row, independent of any project;
  the sole source of a member's Project_Role.
- **Assignable_Project_Role**: one of the system role codes `MANAGER`, `FOREMAN`, `ESTIMATOR`,
  `WORKER`, `FINANCIER`, `CLIENT`.
- **Admin_Staff_Role**: one of the Assignable_Project_Role codes `MANAGER`, `FOREMAN`,
  `ESTIMATOR`, `FINANCIER` (every Assignable_Project_Role except `WORKER` and `CLIENT`).
- **Worker_Role**: the Assignable_Project_Role `WORKER`.
- **Client_Role**: the Assignable_Project_Role `CLIENT`.
- **Uncategorized_Worker**: a Project_Member whose Project_Role code is `WORKER` and that has no
  Worker_Type.
- **Project_Creation_Orchestrator**: the FOR-04-13 `ProjectService` create path behind
  `POST /api/projects`, which assigns the `members[]` entries and the client block.
- **Team_Block**: one of `ADMIN_STAFF`, `WORKERS`, `CLIENTS`. The Team_Block of a Project_Member is
  `CLIENTS` when its Project_Role code is `CLIENT`, `WORKERS` when it is `WORKER`, and
  `ADMIN_STAFF` for every other Project_Role code. Because the Project_Role equals the Company_Role,
  the Team_Block compatible with a user is `CLIENTS` for Company_Role CLIENT, `WORKERS` for
  Company_Role WORKER, and `ADMIN_STAFF` otherwise.
- **Worker_Type**: one row of the FOR-05-06 `worker_types` dictionary (worker type / cost tier),
  identified by id and `code`, with localized names `name_pl` / `name_ru` and an `active` flag.
- **Active_Worker_Type**: a Worker_Type whose `active` flag is `true`.
- **Assignment_Tag**: a free-text internal label attached to one Project_Member (for example a
  specialization or an alias).
- **Assignment_Status**: the lifecycle status of a Project_Member, either `ACTIVE` or `INACTIVE`;
  `ACTIVE` on assign; `INACTIVE` marks a soft-deactivated membership whose history is kept (D14).
- **Tag_Normalization**: the transformation of a submitted tag list into its stored form: trim
  leading and trailing whitespace of each tag, then drop every tag that equals an earlier tag
  under case-insensitive comparison, keeping the first spelling and the original order.
- **Worker_Kind**: `PERSON` or `COMPANY`; the kind of a user with Company_Role WORKER.
- **Worker_Record_Flow**: the uninvited worker-record creation flow introduced by this spec, exposed
  as `POST /api/users/worker`; creates a WORKER user with no password and no login (D7).
- **Worker_Invitation_Flow**: the later worker invite / re-send flow introduced by this spec, exposed
  as `POST /api/users/worker/{id}/invite`; sends the staff password-set email so the worker sets
  their own password (D7, D-new).
- **Internal_Attribute_Viewer**: a caller who holds the `ADMIN` Company_Role or whose Company_Role is
  an Admin_Staff_Role (`MANAGER`, `FOREMAN`, `ESTIMATOR`, `FINANCIER`) — equivalently every caller
  except WORKER and CLIENT readers (D12, Q10).
- **Internal_Attributes**: the Worker_Type fields, the `workerTypeMissing` flag, the NIP, and the
  Assignment_Tags of a Team_Member_View (Assignment_Status is not internal).
- **Inactive_User**: a user whose `active` flag is `false`. Users with status `INVITED` and
  `active = true` are not Inactive_Users. An Inactive_User (a global user-account state) is distinct
  from an INACTIVE Assignment_Status (a per-membership state).
- **Accessible_Project**: for a non-ADMIN caller, a project id contained in the caller's
  `ProjectAccessCache` entry (the caller's own memberships); for an ADMIN caller, any project.
- **Locked_Status**: a `ProjectStatus` of `COMPLETED` or `CANCELLED`.
- **Editable_Status**: any `ProjectStatus` that is not a Locked_Status (`DRAFT`,
  `READY_TO_OFFER`, `OFFERED`, `APPROVED`, `ACTIVE`, `ON_HOLD`).
- **Team_Member_View**: the enriched read model of one Project_Member returned by the Team_API.
- **Candidate**: a user offered by the Team_API candidate lookup as assignable to a given project.
- **Team_Readiness_Gate**: the readiness item with key `team` defined by parent design §6.3.
- **Permission_Matrix**: the `resources` / `role_resources` / `role_resource_operations` tables.
- **Team_Tab**: the workspace tab with key `team` delivered by this spec.
- **Workspace_Shell**: the FOR-05-01 project workspace (`ProjectWorkspacePage`, `WORKSPACE_TABS`,
  `WorkspaceTabs`, `ReadinessWidget`).
- **Readiness_Widget**: the FOR-05-01 `ReadinessWidget` component in the workspace header.
- **Notification_Service**: the FOR-05-07 generic in-app notification service.
- **Client_Registration_Flow**: the FOR-03-05 `ClientRegistrationService.register` flow exposed
  as `POST /api/users/client`.
- **Audit_Log**: the platform audit log table used by the generic CRUD services.
- **Team_Notification_Type**: one of the five in-app Notification_Service notification types used by
  this spec: "team member assigned", "team worker type changed", "team assignment status changed",
  "team member removed", and (reused from FOR-03-02 / FOR-03-05) the invitation emails of the
  registration flows.
- **Member_Snapshot**: the audit snapshot of one Project_Member: user id, project id, Project_Role
  code, Worker_Type code (empty when the member has no Worker_Type, that is for a non-WORKER
  member or an Uncategorized_Worker), Assignment_Status, and Assignment_Tags.
- **Attribute_Update**: a Team_API operation that changes the Worker_Type, the Assignment_Tags, or
  the Assignment_Status of an existing Project_Member without changing its Project_Role (the
  Project_Role is immutable, D2).

---

## Requirements

### Requirement 1: `PROJECT_MEMBERS` resource and role grants (reuse of the existing resource)

**User Story:** As an administrator, I want team management governed by the existing project-scoped
`PROJECT_MEMBERS` resource in the permission matrix, so that each role's access to project teams is
explicit and configurable without introducing a redundant resource.

#### Acceptance Criteria

1. THE Permission_Matrix SHALL govern every membership endpoint through the already-seeded resource row with code `PROJECT_MEMBERS` (changeset `015`), and this spec SHALL seed no new resource row (in particular, no `PROJECT_TEAM` row) for team management.
2. THE Permission_Matrix SHALL keep the `ADMIN` role grant of exactly the operations `CREATE`, `READ`, `UPDATE`, and `DELETE` on `PROJECT_MEMBERS`, as seeded by changeset `015`, so that the ADMIN role can exercise the new UPDATE (Attribute_Update, deactivate, reactivate) and existing CREATE / READ / DELETE operations.
3. THE Permission_Matrix SHALL keep the `MANAGER` role grant of exactly the operations `CREATE`, `READ`, `UPDATE`, and `DELETE` on `PROJECT_MEMBERS`, as seeded by changeset `015`, so that the MANAGER role can exercise the new UPDATE operation and the existing CREATE / READ / DELETE operations.
4. THE Permission_Matrix SHALL keep the `FOREMAN`, `ESTIMATOR`, and `FINANCIER` role grants of exactly the `READ` operation on `PROJECT_MEMBERS`, as seeded by changesets `015` and `136`, with no `CREATE`, `UPDATE`, or `DELETE` operation for those roles.
5. THE Permission_Matrix SHALL contain no `PROJECT_MEMBERS` grant (neither a role-resource link nor any operation) for the `WORKER` role, the `CLIENT` role, or any role whose code is not one of `ADMIN`, `MANAGER`, `FOREMAN`, `ESTIMATOR`, or `FINANCIER`, including non-system (custom) roles, matching the current seed. In particular, WORKER has no grant (an accepted deviation from the parent §5 sketch that proposed WORKER R(own)) and FINANCIER keeps READ (an accepted deviation from the parent §5 sketch that proposed no FINANCIER) (D1).
6. THE spec SHALL add no changeset that alters the `PROJECT_MEMBERS` grants of any role; the four operations required by the Team_API (CREATE, READ, UPDATE, DELETE) are already defined on the resource through the ADMIN and MANAGER CRUD grants, so no grant migration is needed.
7. WHEN the application has started against the current database, THE Permission_Matrix SHALL expose, for the `PROJECT_MEMBERS` resource, exactly ADMIN CRUD, MANAGER CRUD, FOREMAN READ, ESTIMATOR READ, FINANCIER READ, and no WORKER or CLIENT grant.
8. WHEN an ADMIN opens the permission matrix in the admin panel, THE Permission_Matrix SHALL display `PROJECT_MEMBERS` with its localized name in the active UI language (Russian or Polish) and with the grants of criteria 2–5, and SHALL allow the ADMIN to change the `PROJECT_MEMBERS` operations of any role through the same editing controls used for other resources.

### Requirement 2: Controller guarding with `PROJECT_MEMBERS`

**User Story:** As a security reviewer, I want every membership endpoint guarded by `PROJECT_MEMBERS`, so that the Team tab and the API enforce the same matrix row, including the new attribute-update and deactivate / reactivate operations.

#### Acceptance Criteria

1. THE Team_API controller SHALL be annotated with `@PermissionResource("PROJECT_MEMBERS")`, and every request handler of the Team_API SHALL resolve to exactly one `(PROJECT_MEMBERS, operation)` pair as follows: `READ` for listing members, the Team_Readiness_Gate, and listing a user's project ids; `CREATE` for assign and candidate lookup (candidate lookup exists only to add members, Requirement 11); `UPDATE` for every Attribute_Update (Worker_Type change, Requirement 14; tag change, Requirement 15; Assignment_Status change — deactivate / reactivate, Requirement 27); `DELETE` for remove. No Team_API handler SHALL be left without a resolved `(resource, operation)` pair. There is no role-change handler, because the Project_Role is immutable (D2). Worker_Type options are not served by the Team_API; the Team_Tab reads them from the existing `GET /api/worker-types` endpoint guarded by `WORKER_TYPES` READ (D9).
2. THE Team_API controller SHALL contain no `PROJECT_TEAM` reference in any annotation, and the FOR-03-08 test cases TC-RP-03 and REG-03 SHALL assert that every Team_API handler resolves to `PROJECT_MEMBERS`, including the new UPDATE handlers for Attribute_Update and deactivate / reactivate; test assertions that already name `PROJECT_MEMBERS` need no change.
3. IF an authenticated caller whose role lacks the `PROJECT_MEMBERS` operation required by the called handler (per criterion 1) calls a Team_API endpoint, THEN THE Team_API SHALL respond with HTTP 403 and message code `error.access.denied`, with the same response whether or not the target project or user exists or is an Accessible_Project of the caller, and SHALL leave the `project_members` rows unchanged, write no Audit_Log row, and emit no Notification_Service notification.
4. IF a request calls a Team_API endpoint with no access token, an expired access token, or an access token that fails signature validation, THEN THE Team_API SHALL respond with HTTP 401 before any permission or project-scope check, and SHALL leave the `project_members` rows unchanged, write no Audit_Log row, and emit no Notification_Service notification.
5. WHEN the application starts, THE `PermissionAnnotationValidator` SHALL accept the Team_API controller annotations — the class-level `@PermissionResource("PROJECT_MEMBERS")` combined with the `@PermissionOperation` of each handler, including the new UPDATE handlers — and the application SHALL complete startup without a permission-annotation validation failure.
6. WHEN an authenticated caller whose role holds the `PROJECT_MEMBERS` operation required by the called handler, or a caller holding the `ADMIN` role, calls a Team_API endpoint, THE Team_API SHALL not respond with HTTP 401 or HTTP 403 for that request and SHALL pass the request on to the project-scoped access check of Requirement 3.
7. IF an authenticated caller holding the `FOREMAN`, `ESTIMATOR`, or `FINANCIER` role (each with only `PROJECT_MEMBERS` READ) calls the assign, Attribute_Update, deactivate, reactivate, or remove endpoint, THEN THE Team_API SHALL respond with HTTP 403 and message code `error.access.denied`, provided the caller's role grants have not been changed from the Requirement 1 seed.
8. IF an authenticated caller holding the `WORKER` or `CLIENT` role (no `PROJECT_MEMBERS` grant) calls any Team_API endpoint, THEN THE Team_API SHALL respond with HTTP 403 and message code `error.access.denied`, provided the caller's role grants have not been changed from the Requirement 1 seed.

### Requirement 3: Project-scoped access to team data

**User Story:** As a manager, I want to see and manage only the teams of projects I belong to, so that team data of other projects stays private.

#### Acceptance Criteria

1. THE Team_Service SHALL implement `ProjectScopedService` with `getProjectIdPath()` returning `"projectId"`.
2. WHEN a non-ADMIN caller lists members, assigns a member, performs an Attribute_Update (Worker_Type, tags, or Assignment_Status), deactivates, reactivates, removes a member, requests candidates, or requests the team readiness of a project, THE Team_Service SHALL perform the operation only if that project is an Accessible_Project of the caller at the time of the request.
3. IF a non-ADMIN caller targets an existing project that is not an Accessible_Project, THEN THE Team_API SHALL respond with HTTP 404 and message code `error.entity.not.found`, with a response body identical in structure and content to the response for a non-existent project id, and THE Team_Service SHALL create, change, or delete no Project_Member, write no Audit_Log row, and emit no Notification_Service notification.
4. IF any caller, ADMIN included, targets a project id that does not exist, THEN THE Team_API SHALL respond with HTTP 404 and message code `error.entity.not.found`, and THE Team_Service SHALL create, change, or delete no Project_Member.
5. WHILE the caller holds the `ADMIN` Company_Role, THE Team_Service SHALL skip the Accessible_Project check and allow the operation on any existing project, subject to all other validations of this spec (Requirements 5–10, 14, 15, and 27).
6. WHEN a caller requests the project ids of a user (`GET /api/project-members/projects`), THE Team_API SHALL respond with HTTP 200 and a list, ordered by project id ascending, of the project ids in which that user holds a Project_Member (of any Assignment_Status), restricted for a non-ADMIN caller to project ids that are also Accessible_Projects of the caller, and SHALL return an empty list (not HTTP 404) when the restricted set is empty or the user id does not exist.
7. IF a Team_API request fails more than one check, THEN THE Team_API SHALL return only the error of the first failing check in the following canonical order, which Requirements 5, 9, 10, 12, 13, 14, 15, 26, and 27 reference:
   1. HTTP 401 for a missing, expired, or invalid access token (Requirement 2 criterion 4);
   2. HTTP 403 `error.access.denied` for a missing `PROJECT_MEMBERS` operation (Requirement 2 criterion 3);
   3. HTTP 400 for missing or invalid mandatory fields (Requirement 4 criterion 8, Requirement 5 criterion 5, Requirement 8 criterion 5, Requirement 14 criterion 8, Requirement 27 criterion 5), including HTTP 400 `error.project.member.tag.invalid` for a tag list that violates Requirement 15 criterion 3 and HTTP 400 `error.project.member.role.mismatch` for a supplied `projectRoleId` that differs from the user's Company_Role (Requirement 5 criterion 11);
   4. HTTP 404 `error.entity.not.found` for a project that does not exist or is not an Accessible_Project of a non-ADMIN caller (criteria 3–4);
   5. HTTP 409 `error.project.team.locked` (Requirement 10 criterion 2);
   6. HTTP 404 `error.project.member.not.found` for an Attribute_Update, a deactivate / reactivate, or a remove (Requirement 8 criterion 2, Requirement 14 criterion 6, Requirement 15 criterion 5, Requirement 27 criterion 4), or HTTP 409 `error.project.member.duplicate` for an assign (Requirement 5 criterion 2);
   7. HTTP 404 `error.entity.not.found` for a non-existent user (Requirement 5 criterion 3);
   8. HTTP 400 team-composition rules, in this sub-order: first, for the Project_Creation_Orchestrator only, `error.project.member.role.not.allowed.at.creation` (Requirement 26 criterion 2); then Requirement 6 criteria 1–3 (ranked by Requirement 6 criterion 7); then the Worker_Type rule `error.project.member.worker.type.not.allowed` (Requirement 14 criterion 4, Requirement 26 criterion 3); then `error.project.member.worker.type.invalid` (Requirement 14 criterion 3);
   9. HTTP 409 `error.project.member.last.manager` / `error.project.member.last.client` (Requirement 9 criteria 1–2).

   Under this order, a non-ADMIN caller whose request passes steps 1–3 and targets an inaccessible project receives HTTP 404 regardless of whether the payload would otherwise be locked, duplicate, or invalid. The Client_Registration_Flow, the Worker_Record_Flow, and the Worker_Invitation_Flow (Requirements 12 and 13) apply the same order, with `PROJECT_MEMBERS` CREATE and `PROJECTS` EDIT checked at step 2, the HTTP 409 duplicate-email error in place of step 6 where a user is created, and the role fixed server-side (the user's Company_Role).
8. IF a non-ADMIN caller performs an Attribute_Update on, deactivates, reactivates, or removes a Project_Member of a project that is not an Accessible_Project of the caller, THEN THE Team_API SHALL respond with HTTP 404 and message code `error.entity.not.found` (step 4 of the order in criterion 7), with a response body identical to the response for a non-existent project, regardless of whether a Project_Member exists for the targeted `(userId, projectId)` pair, and THE Team_Service SHALL leave that Project_Member unchanged.

### Requirement 4: List project team members

**User Story:** As a project participant with team read access, I want to see who is on the project, in which role, and whether their assignment is active, so that I know whom to contact.

#### Acceptance Criteria

1. WHEN a caller with `PROJECT_MEMBERS` READ requests the members of an Accessible_Project, THE Team_API SHALL respond with HTTP 200 and a single unpaginated list containing exactly one Team_Member_View for every Project_Member of that project, including Project_Members whose user is an Inactive_User, whose user has status `INVITED`, or whose Assignment_Status is `INACTIVE`, and no Team_Member_View of any other project.
2. WHEN a caller with `PROJECT_MEMBERS` READ requests the members of an Accessible_Project that has zero Project_Members, THE Team_API SHALL respond with HTTP 200 and an empty list.
3. THE Team_Member_View SHALL contain the membership id, project id, user id, user display name (the user's `name` value as stored on the user record), user email, user status (`INVITED` / `ACTIVE`), user active flag, Project_Role id, Project_Role code, Project_Role name, Company_Role code (equal to the Project_Role code by D2), the member's Team_Block (`block`), and the member's Assignment_Status (`ACTIVE` / `INACTIVE`), each populated from the current persisted user, role, and membership data at the time of the request. For a member in the `WORKERS` Team_Block, the Team_Member_View SHALL also contain the Worker_Kind (`PERSON` when the user has no stored Worker_Kind) and the contact person (empty when none is stored).
4. WHEN the request locale language is `ru` (case-insensitive), THE Team_API SHALL return the Russian Project_Role name, and WHEN the request locale is any other language, absent, or unsupported, THE Team_API SHALL return the Polish Project_Role name.
5. THE Team_API SHALL keep the existing response fields (`id`, `userId`, `projectId`, `projectRoleId`, `projectRoleCode`) with unchanged names and meanings.
6. THE Team_API SHALL return the members ordered first by Team_Block in the sequence ADMIN_STAFF, WORKERS, CLIENTS; inside ADMIN_STAFF by Project_Role code in the sequence MANAGER, FOREMAN, ESTIMATOR, FINANCIER, with any Project_Role code outside this sequence placed after FINANCIER; then by user display name ascending using case-insensitive comparison; then by membership id ascending, so that two requests over unchanged data return identical order. Assignment_Status does not affect ordering (INACTIVE members keep their place in the block).
7. THE Team_Member_View SHALL contain no worker rate, tariff, Worker_Type tier percentage, cost, password, password hash, invitation token, OTP code, or access/refresh token field.
8. IF the members request omits the project id or supplies a project id that is not a positive integer, THEN THE Team_API SHALL respond with HTTP 400 and return no Team_Member_View.
9. WHEN an assign, Attribute_Update, deactivate, reactivate, or remove operation on a project completes successfully, THE Team_API SHALL reflect that change in the next members request for that project, with no stale or missing Team_Member_View.
10. WHILE the caller is an Internal_Attribute_Viewer, THE Team_Member_View SHALL contain the Internal_Attributes: for a `WORKERS` member the Worker_Type id, Worker_Type code, localized Worker_Type name (resolved as in Requirement 24 criterion 4), and Worker_Type active flag (each null for an Uncategorized_Worker), and the NIP (empty when none is stored); for every member the boolean `workerTypeMissing` of Requirement 14 criterion 16; and for every member the Assignment_Tags in their stored order (an empty list when none). The Assignment_Status is returned to every reader and is not an Internal_Attribute.
11. IF the caller is not an Internal_Attribute_Viewer (a WORKER or CLIENT reader), THEN THE Team_API SHALL omit every Internal_Attribute field from every Team_Member_View in the response, so that the response carries no Worker_Type, `workerTypeMissing`, NIP, or Assignment_Tag value of any member (D12), while still carrying the Assignment_Status.

### Requirement 5: Assign a member to the project team

**User Story:** As a manager, I want to add a user to my project, so that the right people get access; the member's role is always the user's own global role.

#### Acceptance Criteria

1. WHEN a caller with `PROJECT_MEMBERS` CREATE submits an assignment `{userId, projectId, projectRoleId?, workerTypeId?, tags?}` that passes the access checks of Requirements 2 and 3, the lifecycle check of Requirement 10, the composition checks of Requirement 6, the Worker_Type rules of Requirement 14, the tag rules of Requirement 15, and criteria 2–5 and 11 of this requirement, THE Team_Service SHALL persist exactly one Project_Member for that `(userId, projectId)` pair with the Project_Role set to the user's current Company_Role, Assignment_Status `ACTIVE`, the submitted Worker_Type (WORKER only; no Worker_Type when `workerTypeId` is omitted or null), and the Tag_Normalization of the submitted tags (an empty list when `tags` is absent), and THE Team_API SHALL respond with HTTP 201 and the Team_Member_View of the new Project_Member, whose `userId`, `projectId`, `projectRoleId` (= the user's Company_Role id), Assignment_Status `ACTIVE`, and (for an Internal_Attribute_Viewer) Worker_Type id and Assignment_Tags equal the submitted or defaulted values after Tag_Normalization.
2. IF a Project_Member already exists for the `(userId, projectId)` pair, regardless of its current Assignment_Status, THEN THE Team_API SHALL respond with HTTP 409 and message code `error.project.member.duplicate` and THE Team_Service SHALL leave the existing Project_Member unchanged (an INACTIVE member is reactivated through Requirement 27, not re-assigned).
3. IF no user exists with the submitted `userId`, THEN THE Team_API SHALL respond with HTTP 404 and message code `error.entity.not.found` and THE Team_Service SHALL persist no Project_Member.
4. IF either of `userId` or `projectId` is absent, null, or not a positive integer identifier, or a supplied non-null `projectRoleId` or `workerTypeId` is not a positive integer identifier, or a supplied `tags` value violates Requirement 15 criterion 3, THEN THE Team_API SHALL respond with HTTP 400 and a validation error identifying each offending field (message code `error.project.member.tag.invalid` for `tags`), before any project, user, role, or Worker_Type lookup and before any persistence. An absent or null `projectRoleId` is not a validation error (criterion 11).
5. WHEN the assignment is persisted, THE Team_Service SHALL increase the number of Project_Members of the project by exactly one and SHALL keep the membership id, user id, Project_Role, and Assignment_Status of every previously existing Project_Member of the project unchanged.
6. WHEN a CLIENT Project_Member is assigned to a project that already has one or more CLIENT Project_Members, and the assigned user's Company_Role is CLIENT (Requirement 6 criterion 2), THE Team_Service SHALL persist the new CLIENT Project_Member alongside the existing ones without altering them (multi-client, parent Property 17; no upper limit per Requirement 9 criterion 4).
7. IF an assignment fails more than one check, THEN THE Team_API SHALL return only the error of the first failing check in the canonical order of Requirement 3 criterion 7, which for an assignment is: authentication (Requirement 2 criterion 4), `PROJECT_MEMBERS` CREATE permission (Requirement 2 criterion 3), mandatory fields including the role-mismatch check (criterion 5 and criterion 11), project existence and accessibility (Requirement 3 criteria 3–4), Locked_Status (Requirement 10 criterion 2), duplicate membership (criterion 2), user existence (criterion 3), team-composition rules (Requirement 6 criteria 1–3), and the Worker_Type rules (Requirement 14 criteria 4 and 3, in that order).
8. IF an assignment is rejected with any 4xx response, THEN THE Team_Service SHALL persist no Project_Member, write no Audit_Log row, create no in-app notification, and leave the `ProjectAccessCache` entry of the targeted user unchanged.
9. IF two assignment requests for the same `(userId, projectId)` pair are processed concurrently, THEN THE Team_Service SHALL persist exactly one Project_Member, and THE Team_API SHALL respond to one request with HTTP 201 and to the other with HTTP 409 and message code `error.project.member.duplicate` (never with HTTP 500).
10. WHEN an assignment omits `projectRoleId` or sets it to null, THE Team_Service SHALL set the Project_Role of the new Project_Member to the user's current Company_Role, read at request time, with no separate role lookup required from the caller. A CLIENT-Company_Role user is assigned under the Client_Role, a WORKER-Company_Role user under the Worker_Role, and every other user under the Company_Role matching an Admin_Staff_Role, so a defaulted Project_Role places the member in the Team_Block of Requirement 6 criterion 2 by construction and never triggers the role-mismatch check (criterion 11).
11. IF an assignment supplies a non-null `projectRoleId` whose role code differs from the assigned user's current Company_Role code, THEN THE Team_API SHALL respond with HTTP 400 and message code `error.project.member.role.mismatch` and THE Team_Service SHALL persist no Project_Member, because the Project_Role is always the user's single global role (D2). A supplied `projectRoleId` equal to the user's Company_Role id is accepted and treated as the default. A user whose Company_Role code is not an Assignable_Project_Role (for example `ADMIN`) is rejected under Requirement 6 criterion 1. This criterion covers the Team_API assign endpoint only; the `members[]` contract of the Project_Creation_Orchestrator keeps its existing `projectRoleId` handling, which must also equal the user's Company_Role (Requirement 26).

### Requirement 6: Team-composition validation

**User Story:** As a product owner, I want the server to reject team configurations that make no sense or leak access, so that every client of the API gets the same safe behavior.

#### Acceptance Criteria

1. IF an assignment resolves to a Project_Role (the user's Company_Role) whose code is not an Assignable_Project_Role (including `ADMIN` and any non-system role), THEN THE Team_API SHALL respond with HTTP 400 and message code `error.project.member.role.not.assignable`. This covers a user whose global role cannot be a project role, for example an ADMIN user, who is managed through the admin area rather than as a project member.
2. THE Team_Service SHALL derive a member's Team_Block solely from the user's Company_Role (CLIENT ⇒ `CLIENTS`, WORKER ⇒ `WORKERS`, any Admin_Staff_Role ⇒ `ADMIN_STAFF`), so that, under the single-global-role model, no caller can place a user in a block incompatible with the user's global role. There is no separate role-incompatibility rejection: a supplied `projectRoleId` that disagrees with the user's Company_Role is rejected under Requirement 5 criterion 11 (`error.project.member.role.mismatch`), and a user whose Company_Role is not an Assignable_Project_Role is rejected under criterion 1.
3. IF an assignment names an Inactive_User (global `active = false`), THEN THE Team_API SHALL respond with HTTP 400 and message code `error.project.member.user.inactive`. A user with status `INVITED` and `active = true` SHALL be accepted, as long as criterion 1 passes.
4. THE Team_Service SHALL apply criteria 1 and 3 to every assignment path, for every caller including ADMIN. This covers the Team_API assign endpoint, the Project_Creation_Orchestrator (`members[]` and the client block; `members[]` is further restricted by Requirement 26), the Client_Registration_Flow, the Worker_Record_Flow, and the Worker_Invitation_Flow. The ADMIN ABAC bypass SHALL NOT skip these checks.
5. IF a validation of criterion 1 or 3, of Requirement 14 criteria 3–4, or of Requirement 26 criteria 2–3 fails during project creation, during the Client_Registration_Flow, during the Worker_Record_Flow, or during the Worker_Invitation_Flow, THEN THE system SHALL return the same HTTP 400 status and message code that the failed criterion specifies. It SHALL also roll back the whole transaction, leaving no new project row, no new Project_Member row, and no new client or worker user, and SHALL send no invitation email.
6. IF a single assignment violates more than one of criteria 1 and 3, THEN THE Team_API SHALL respond with only the message code of the lowest-numbered criterion violated.
7. IF the Team_API rejects an assignment under criteria 1 or 3, THEN THE Team_Service SHALL leave the project's Project_Member rows exactly as they were before the request. It SHALL write no Audit_Log entry and emit no Notification_Service notification for the rejected operation.

### Requirement 7: Project role is the user's global role (immutable per membership)

**User Story:** As a product owner, I want a member's project role to always equal the user's single global role and to be fixed for the life of the membership, so that a project cannot grant someone a capacity their global role does not have.

#### Acceptance Criteria

1. THE Team_Service SHALL set the Project_Role of every Project_Member to the user's Company_Role at assign time, and SHALL treat the Project_Role as immutable for the life of that membership. There SHALL be no Team_API operation that changes the Project_Role of an existing Project_Member.
2. IF a caller attempts to change a member's Project_Role (for example by submitting a `projectRoleId` to a membership update), THEN THE Team_API SHALL NOT re-role the member; a Worker_Type, tag, or Assignment_Status update (Requirements 14, 15, 27) SHALL ignore any submitted `projectRoleId`, and an assign that supplies a mismatching `projectRoleId` SHALL be rejected under Requirement 5 criterion 11 (`error.project.member.role.mismatch`).
3. WHEN a user's global Company_Role changes in the admin area, THE Team_API SHALL reflect the new Company_Role as the Project_Role code of that user's existing Project_Members on the next read, because the Team_Member_View resolves the Project_Role from the current user and role data (Requirement 4 criterion 3). This spec defines no project-side operation to trigger such a change; changing a person's capacity is a global role change, out of scope here.
4. THE Team_Service SHALL keep the member's Team_Block consistent with the user's Company_Role: a member stays in the block fixed by the user's global role, and because the role is immutable per membership, a member never moves between the `ADMIN_STAFF`, `WORKERS`, and `CLIENTS` blocks within this spec (D6). To place a person in another block, the user's global role must change first (admin area), after which the person may be removed and re-added.
5. THE last-ACTIVE-MANAGER and last-ACTIVE-CLIENT invariants (Requirement 9) SHALL depend only on assign, remove, and deactivate / reactivate operations, since no role change exists; a project's count of ACTIVE MANAGER or ACTIVE CLIENT members changes only through those operations.

### Requirement 8: Remove a member from the project team

**User Story:** As a manager, I want to remove a user from the project when the membership was a mistake, so that the record is corrected; the normal "person left" action is deactivation (Requirement 27), which keeps history.

#### Acceptance Criteria

1. WHEN a caller with `PROJECT_MEMBERS` DELETE removes an existing Project_Member, identified by the `(userId, projectId)` pair, of an Accessible_Project in an Editable_Status, and the removal passes the Requirement 9 (last ACTIVE MANAGER / last ACTIVE CLIENT) and Requirement 10 (lifecycle lock) checks, THE Team_Service SHALL hard-delete that Project_Member, discarding its history, and THE Team_API SHALL respond with HTTP 204 and an empty body. Remove is reserved for correcting mistakes; to record that a person left while keeping history, the caller deactivates the member instead (Requirement 27).
2. IF the targeted project is an Accessible_Project of the caller and no Project_Member exists for the targeted `(userId, projectId)` pair, including when the same pair was already removed by an earlier or concurrent request, THEN THE Team_API SHALL respond with HTTP 404 and message code `error.project.member.not.found` and THE Team_Service SHALL leave the team unchanged.
3. WHEN a Project_Member is removed, THE Team_Service SHALL delete no other Project_Member (of the same project or of any other project), no user row, and no project row, and SHALL leave the removed user's Company_Role, status (`INVITED` / `ACTIVE`), and active flag unchanged.
4. WHEN the removal targets a Project_Member whose user is an Inactive_User, whose user has status `INVITED`, or whose Assignment_Status is `INACTIVE`, THE Team_Service SHALL apply the same rules as criteria 1–3 and Requirements 9 and 10, without any additional user-state check. A member whose Assignment_Status is `INACTIVE` does not count toward the last-ACTIVE-MANAGER / last-ACTIVE-CLIENT invariants, so removing an already-INACTIVE member is never blocked by Requirement 9.
5. IF the removal request omits `userId` or `projectId`, or supplies a value that is not a valid identifier, THEN THE Team_API SHALL respond with HTTP 400 and THE Team_Service SHALL delete no Project_Member.
6. WHEN a removal responds with HTTP 204, THE Team_API SHALL return no Team_Member_View for the removed user in any subsequent list request for that project (Requirement 4), and the user SHALL appear again as a Candidate for that project (Requirement 11) unless the user is an Inactive_User.
7. WHEN a non-ADMIN caller removes the caller's own Project_Member and the removal passes the Requirement 9 checks, THE Team_Service SHALL delete it under criterion 1, and THE Team_API SHALL treat the project as not an Accessible_Project for that caller from the caller's next request onward (Requirement 16 criterion 2).

### Requirement 9: Team-composition invariants (last ACTIVE MANAGER, last ACTIVE CLIENT)

**User Story:** As a product owner, I want a project never to lose its last active manager or its last active client by accident, so that offers, notifications, and signing always have a responsible party.

#### Acceptance Criteria

1. IF a remove operation targets the only ACTIVE Project_Member of a project whose Project_Role code is MANAGER, or a deactivate operation (Requirement 27) would set that only ACTIVE MANAGER to `INACTIVE`, THEN THE Team_API SHALL respond with HTTP 409 and message code `error.project.member.last.manager`, and THE Team_Service SHALL leave every Project_Member row of the project unchanged, write no Audit_Log row, and emit no Notification_Service notification for that operation.
2. IF a remove operation targets the only ACTIVE Project_Member of a project whose Project_Role code is CLIENT, or a deactivate operation (Requirement 27) would set that only ACTIVE CLIENT to `INACTIVE`, THEN THE Team_API SHALL respond with HTTP 409 and message code `error.project.member.last.client`, and THE Team_Service SHALL leave every Project_Member row of the project unchanged, write no Audit_Log row, and emit no Notification_Service notification for that operation.
3. THE Team_Service SHALL apply criteria 1 and 2 to ADMIN callers as well as non-ADMIN callers, counting ACTIVE MANAGER and ACTIVE CLIENT Project_Members by the Project_Role code and Assignment_Status of the project's Project_Member rows, including ACTIVE members whose user is an Inactive_User or has status `INVITED`, and excluding members whose Assignment_Status is `INACTIVE`.
4. WHEN an assignment of a CLIENT Project_Member passes the validation of Requirements 5 and 6, THE Team_Service SHALL accept it regardless of how many CLIENT Project_Members the project already has (no upper limit; a project with 10 existing CLIENT Project_Members SHALL accept an 11th).
5. FOR ALL sequences of assign, remove, deactivate, and reactivate operations on a project, accepted or rejected, THE Team_Service SHALL ensure that, for each of the Project_Role codes MANAGER and CLIENT, a project with at least one ACTIVE Project_Member of that code before an operation still has at least one ACTIVE member of that code after it, and that a rejected operation leaves the count of ACTIVE Project_Members of every Project_Role code unchanged (invariant property).
6. WHILE a project has zero ACTIVE MANAGER Project_Members (or zero ACTIVE CLIENT Project_Members), THE Team_Service SHALL NOT reject assign or reactivate operations on that project under criterion 1 (or criterion 2 respectively); such operations may restore an ACTIVE member of that code.
7. IF an operation fails more than one check, THEN THE Team_API SHALL return only the error of the first failing check in the canonical order of Requirement 3 criterion 7, under which permission and project access (Requirements 2 and 3) precede the lifecycle lock (Requirement 10 criterion 2), the lifecycle lock precedes a missing Project_Member (Requirement 8 criterion 2, Requirement 27 criterion 4), a missing Project_Member precedes team-composition role validation (Requirement 6 criteria 1–3), and role validation precedes last ACTIVE MANAGER / last ACTIVE CLIENT (criteria 1 and 2).
8. IF two or more remove or deactivate operations on the same project are processed concurrently and accepting all of them would reduce the number of ACTIVE MANAGER (or ACTIVE CLIENT) Project_Members from one or more to zero, THEN THE Team_Service SHALL accept only operations that keep that number at one or more, and THE Team_API SHALL reject each remaining operation with HTTP 409 and the message code of criterion 1 (or criterion 2).

### Requirement 10: Lifecycle lock

**User Story:** As a manager, I want the team of a closed project to be frozen, so that the historical record of who worked on it is preserved.

#### Acceptance Criteria

1. WHILE a project is in an Editable_Status (`DRAFT`, `READY_TO_OFFER`, `OFFERED`, `APPROVED`, `ACTIVE`, or `ON_HOLD`), THE Team_Service SHALL accept assign, Attribute_Update, deactivate, reactivate, and remove operations that pass all other validation of Requirements 3, 5–9, 14, 15, and 27, for ADMIN and non-ADMIN callers alike.
2. IF an assign, Attribute_Update, deactivate, reactivate, or remove operation from any caller, including an ADMIN caller, targets an existing Accessible_Project whose status at the moment the operation is processed is a Locked_Status, THEN THE Team_API SHALL respond with HTTP 409 and message code `error.project.team.locked`, and THE Team_Service SHALL leave every Project_Member of the project (membership, user, Project_Role, Worker_Type, Assignment_Status, and Assignment_Tags) unchanged, write no Audit_Log entry for the rejected change, emit no Notification_Service notification, and leave every user's access to the project unchanged.
3. WHILE a project is in a Locked_Status, THE Team_API SHALL serve the list (Requirement 4), team readiness, and project-listing (Requirement 3 criterion 6) reads for callers holding `PROJECT_MEMBERS` READ with HTTP 200 and the same content and ordering rules it applies to a project in an Editable_Status.
4. IF an assign, Attribute_Update, deactivate, reactivate, or remove operation targets a project in a Locked_Status and would also fail a later check of the canonical order of Requirement 3 criterion 7 (duplicate member, non-existent Project_Member or user, team-composition or Worker_Type validation, or last ACTIVE MANAGER / last ACTIVE CLIENT protection), THEN THE Team_API SHALL respond with the lifecycle-lock error of criterion 2 instead of that other error. Under the same order, the 401 and 403 responses of Requirement 2, the 400 response for missing or invalid mandatory fields, and the 404 responses for non-existent or non-accessible projects of Requirement 3 SHALL take precedence over the lifecycle-lock error.
5. WHEN a project moves from an Editable_Status to a Locked_Status, THE Team_Service SHALL keep every Project_Member of that project, including its Project_Role, Worker_Type, Assignment_Status, and Assignment_Tags, unchanged, and SHALL neither add nor remove any Project_Member as part of that move.

### Requirement 11: Candidate lookup

**User Story:** As a manager without the global users permission, I want to search for people to add to my project, so that I can build the team from the Team tab.

#### Acceptance Criteria

1. WHEN a caller holding `PROJECT_MEMBERS` CREATE requests candidates for an existing Accessible_Project, THE Team_API SHALL respond with HTTP 200 and a paginated list of users that excludes every user who is a current Project_Member of that project (of any Assignment_Status) and excludes every Inactive_User, while including users with status `INVITED` and `active = true` and uninvited WORKER records (status reflecting "not invited", `active = true`).
2. WHERE a search term is supplied, THE Team_API SHALL trim leading and trailing whitespace from the term and return only Candidates whose display name or email contains the trimmed term as a substring, compared case-insensitively; a term that is empty or whitespace-only after trimming SHALL be treated as not supplied.
3. WHERE a target Project_Role code is supplied, THE Team_API SHALL return only Candidates whose Company_Role code equals that Project_Role code, since the Project_Role of any assignment equals the candidate's Company_Role (D2): only CLIENT-Company_Role users for the Client_Role; only WORKER-Company_Role users for the Worker_Role; only users whose Company_Role equals the requested Admin_Staff_Role for that role.
4. THE Candidate entry SHALL contain the user id, display name, email (empty for an uninvited worker record with no stored email), user status, Company_Role code, localized Company_Role name in the request locale (pl or ru), the compatible Team_Block of the user, and the Worker_Kind and contact person when the compatible Team_Block is `WORKERS`. The entry SHALL carry the user's Company_Role code as the role under which the candidate will be assigned (there is no separate default; the role is always the Company_Role).
5. THE Candidate entry SHALL contain no password, token, rate, cost, Worker_Type, NIP, or Assignment_Tag field.
6. IF a non-ADMIN caller requests candidates for a project that is not an Accessible_Project, or any caller requests candidates for a project id that does not exist, THEN THE Team_API SHALL respond with HTTP 404 and the same error body as for a non-existent project, and SHALL return no Candidate data.
7. THE Team_API SHALL return candidate pages with a default page size of 20 entries and a maximum of 50 entries, returning at most 50 entries when a larger page size is requested, ordered by display name ascending and then by user id ascending, and SHALL include the total number of matching Candidates in each page response.
8. IF a supplied target Project_Role code is not an Assignable_Project_Role, THEN THE Team_API SHALL respond with HTTP 400 and message code `error.project.member.role.not.assignable` and SHALL return no Candidate data.
9. IF a candidate request supplies a page size below 1, a negative page index, or a search term longer than 100 characters after trimming, THEN THE Team_API SHALL respond with HTTP 400 and an error indicating the invalid parameter, and SHALL return no Candidate data.
10. IF a caller holding `PROJECT_MEMBERS` READ but not `PROJECT_MEMBERS` CREATE requests candidates, THEN THE Team_API SHALL respond with HTTP 403 and message code `error.access.denied`, and SHALL return no Candidate data.
11. WHERE a target Team_Block (`ADMIN_STAFF`, `WORKERS`, or `CLIENTS`) is supplied, THE Team_API SHALL return only Candidates whose compatible Team_Block (fixed by their Company_Role) equals the supplied Team_Block; WHERE both a target Team_Block and a target Project_Role code are supplied, THE Team_API SHALL return only Candidates satisfying both filters.
12. IF a supplied target Team_Block is not one of `ADMIN_STAFF`, `WORKERS`, or `CLIENTS`, THEN THE Team_API SHALL respond with HTTP 400 and an error indicating the invalid parameter, and SHALL return no Candidate data.

### Requirement 12: Invite a new client from the Team tab

**User Story:** As a manager, I want to invite a co-owner as an additional client directly from the Team tab, so that every decision-maker can review the offer and sign.

#### Acceptance Criteria

1. WHEN a caller holding both `PROJECT_MEMBERS` CREATE and `PROJECTS` EDIT submits the Team_Tab invitation form with a name of 1 to 255 characters (after trimming whitespace), a syntactically valid email of at most 254 characters, optionally a phone number, and optionally a list of Assignment_Tags (Requirement 15), for an Accessible_Project in an Editable_Status, THE Workspace_Shell SHALL invoke the Client_Registration_Flow for that project, and THE Client_Registration_Flow SHALL, as one atomic unit, create exactly one user with Company_Role CLIENT and status `INVITED`, send exactly one client-portal invitation email (OTP flow) to the submitted address, and assign exactly one CLIENT Project_Member for that user and project with Assignment_Status `ACTIVE`, carrying the Tag_Normalization of the submitted tags (an empty list when none), leaving all existing Project_Members of the project unchanged (multi-client, parent Property 17). The optional `tags` field is an additive extension of the FOR-03-05 request; a request without it behaves exactly as before this spec.
2. WHEN the Client_Registration_Flow completes successfully, THE Team_Tab SHALL close the invitation form, show a localized success confirmation, and show the new member in the `CLIENTS` Team_Block with user status `INVITED`, Assignment_Status `ACTIVE`, and the submitted name and email, without a full page reload.
3. IF the submitted email belongs to an existing user (compared case-insensitively), THEN THE Client_Registration_Flow SHALL reject the invitation with HTTP 409 creating no user, no Project_Member, and no invitation email, and THE Team_Tab SHALL keep the form open with the entered values, show the localized duplicate-email error returned by the Client_Registration_Flow, and offer an action that opens the Requirement 11 candidate lookup for the `CLIENTS` Team_Block with the submitted email pre-filled as the search term.
4. IF a client invitation targets a project in a Locked_Status, THEN THE Client_Registration_Flow SHALL reject the invitation with HTTP 409 and message code `error.project.team.locked`, creating no user, no Project_Member, and no invitation email.
5. IF a non-ADMIN caller submits a client invitation for a project that is not an Accessible_Project, or any caller submits an invitation for a non-existent project, THEN THE Client_Registration_Flow SHALL respond with HTTP 404 and the same error body as Requirement 3 criterion 3, creating no user, no Project_Member, and no invitation email.
6. IF the submitted invitation has an empty or whitespace-only name, a name longer than 255 characters, an empty email, an email longer than 254 characters, an email that is not syntactically valid, or a tag list that violates Requirement 15 criterion 3, THEN THE Team_Tab SHALL show a localized field-level error on each invalid field and send no request, and THE Client_Registration_Flow SHALL respond with HTTP 400 to any such request it receives, creating no user, no Project_Member, and no invitation email.
7. WHILE the caller lacks `PROJECT_MEMBERS` CREATE or `PROJECTS` EDIT, or the project is in a Locked_Status, THE Team_Tab SHALL not show the invite-client action.

### Requirement 13: Add a new worker and later invite them from the Team tab

**User Story:** As a manager, I want to add a new worker, a person or a company such as an external contractor, to the project for assignment and record-keeping even before any invitation, and to invite or re-invite them later so they set their own password, so that the crew can be staffed without a separate user-administration step.

#### Acceptance Criteria

1. WHEN a caller holding both `PROJECT_MEMBERS` CREATE and `PROJECTS` EDIT submits the Team_Tab add-worker form for an Accessible_Project in an Editable_Status with a payload that is valid per criterion 2, THE Team_Tab SHALL invoke the Worker_Record_Flow for that project, and THE Worker_Record_Flow SHALL do the following as one atomic unit (criterion 12):
   - create exactly one user with Company_Role WORKER, a status that reflects "not invited" (the user has no password and cannot authenticate), and `active = true`;
   - give that user the submitted Worker_Kind and a display name equal to the trimmed person name (Worker_Kind `PERSON`) or the trimmed company name (Worker_Kind `COMPANY`);
   - store on that user the trimmed email when supplied (email is optional for an uninvited worker record), the trimmed phone (or none), and, for `COMPANY` only, the trimmed contact person (or none) and the normalized NIP of criterion 5 (or none);
   - assign exactly one WORKER Project_Member for that user and project with Assignment_Status `ACTIVE`, carrying the submitted Worker_Type (no Worker_Type when none is submitted, making the member an Uncategorized_Worker) and the Tag_Normalization of the submitted tags (an empty list when tags are omitted);
   - send no email;
   - leave every existing Project_Member of the project unchanged;
   - respond with HTTP 201 containing the new user id, the stored email (or empty), the project id, and the new membership id.
   THE Worker_Record_Flow SHALL resolve the WORKER role server-side, SHALL ignore any role, status, or active flag supplied by the caller, and SHALL create a WORKER user even if the request names another role.
2. THE Worker_Record_Flow SHALL treat a payload as valid when all of the following hold:
   - the Worker_Kind is exactly `PERSON` or exactly `COMPANY` (case-sensitive);
   - for `PERSON`, the person name is 1 to 255 characters after trimming;
   - for `COMPANY`, the company name is 1 to 255 characters after trimming, the optional contact person is 1 to 255 characters after trimming, and the optional NIP satisfies criterion 5;
   - the optional email, when supplied, is 1 to 254 characters after trimming and syntactically valid;
   - the optional phone, after trimming, is 1 to 50 characters, consists only of digits, spaces, and the characters `+`, `-`, `(`, `)`, has `+` only as its first character if at all, and contains 7 to 15 digits;
   - the optional Worker_Type id, when supplied, is a positive integer identifier of an Active_Worker_Type;
   - the optional tags satisfy Requirement 15 criterion 3.
   THE Worker_Record_Flow SHALL treat an optional field (email, phone, contact person, NIP, Worker_Type id, tags) that is absent, null, or empty or whitespace-only after trimming as not supplied. Names, contact persons, emails, and NIPs need not be unique across users, except that a supplied email must be unique (criterion 7).
3. WHERE the Worker_Kind is `PERSON`, THE Worker_Record_Flow SHALL ignore any company name, contact person, or NIP in the request without validating them, and SHALL store no contact person and no NIP for the user. WHERE the Worker_Kind is `COMPANY`, THE Worker_Record_Flow SHALL likewise ignore any person name in the request without validating it.
4. IF the payload has any of the following, THEN THE Worker_Record_Flow SHALL respond with HTTP 400 and a validation error listing every offending field:
   - a Worker_Kind that is missing or not exactly `PERSON` or `COMPANY`;
   - a missing name for the Worker_Kind, or one that is empty, whitespace-only, or longer than 255 characters after trimming;
   - a `COMPANY` contact person longer than 255 characters after trimming;
   - a supplied email that is empty after trimming, longer than 254 characters, or not syntactically valid;
   - a phone that violates the phone rule of criterion 2;
   - a supplied Worker_Type id that is not a positive integer identifier;
   - tags that violate Requirement 15 criterion 3.
   The field errors in that response SHALL carry the field-specific message codes of criteria 5 and 6 and Requirement 15 criterion 3 (`error.project.member.tag.invalid`) where those apply. THE Worker_Record_Flow SHALL create no user, no Project_Member, and no invitation email.
5. WHERE the Worker_Kind is `COMPANY` and a NIP is supplied, THE Worker_Record_Flow SHALL normalize it by removing all spaces and hyphens, and SHALL accept it only if the result is exactly 10 decimal digits whose checksum is valid. The checksum is valid when the sum of the first nine digits multiplied by the weights 6, 5, 7, 2, 3, 4, 5, 6, 7, taken modulo 11, is not 10 and equals the tenth digit. IF the normalized NIP fails this rule, THEN THE Worker_Record_Flow SHALL respond with HTTP 400 and message code `error.worker.nip.invalid` for the NIP field, creating no user, no Project_Member, and no invitation email.
6. WHEN the payload omits the Worker_Type id, THE Worker_Record_Flow SHALL accept the add under criterion 1 and create the WORKER Project_Member as an Uncategorized_Worker, after which the warning of Requirement 14 criteria 17–18 applies. IF the supplied Worker_Type id is not a positive integer identifier, THEN THE Worker_Record_Flow SHALL respond with HTTP 400 and message code `error.project.member.worker.type.invalid` at the field-validation step (criterion 11 item 3, canonical step 3 "mandatory fields"). IF the Worker_Type id is a positive integer that references no Worker_Type or an inactive Worker_Type, THEN THE Worker_Record_Flow SHALL respond with HTTP 400 and message code `error.project.member.worker.type.invalid` at the last step of criterion 11 (item 6, canonical step 8 "team-composition rules"). In both rejection cases THE Worker_Record_Flow SHALL create no user, no Project_Member, and no invitation email.
7. IF a supplied email equals the email of any existing user under case-insensitive comparison, whatever that user's Company_Role, status, or active flag, THEN THE Worker_Record_Flow SHALL respond with HTTP 409 and the same duplicate-email message code as the Client_Registration_Flow, creating no user, no Project_Member, and no invitation email. IF two concurrent worker-record requests, or a worker-record request and any other user-creation request, carry emails that are equal under case-insensitive comparison, THEN at most one user SHALL be committed and every other request SHALL receive this HTTP 409. On this error THE Team_Tab SHALL keep the form open with every entered value, show the localized duplicate-email error, and offer an action that opens the Requirement 11 candidate lookup for the `WORKERS` Team_Block with the trimmed submitted email pre-filled as the search term. An add-worker request with no email SHALL never fail with a duplicate-email error.
8. IF an add-worker request targets a project in a Locked_Status, THEN THE Worker_Record_Flow SHALL respond with HTTP 409 and message code `error.project.team.locked`, creating no user, no Project_Member, and no invitation email.
9. IF a non-ADMIN caller submits an add-worker request for an existing project that is not an Accessible_Project, or any caller (ADMIN included) submits one for a non-existent project id, THEN THE Worker_Record_Flow SHALL respond with HTTP 404 and message code `error.entity.not.found`, with a response body identical in structure and content for both cases (Requirement 3 criterion 3), creating no user, no Project_Member, and no invitation email.
10. IF a non-ADMIN caller lacks `PROJECT_MEMBERS` CREATE or `PROJECTS` EDIT, THEN THE Worker_Record_Flow SHALL respond with HTTP 403 and message code `error.access.denied`, creating no user, no Project_Member, and no invitation email.
11. IF an add-worker request fails more than one check, THEN THE Worker_Record_Flow SHALL return only the error of the first failing check, in this order (Requirement 3 criterion 7 as adapted for the record flow; the canonical step of each item is given in brackets):
    1. HTTP 401 for authentication [canonical step 1, authentication];
    2. HTTP 403 `error.access.denied` for `PROJECT_MEMBERS` CREATE plus `PROJECTS` EDIT [canonical step 2, permission];
    3. HTTP 400 field validation of criteria 4–6, covering the Worker_Kind, names, contact person, email, phone, NIP format and checksum, a supplied non-integer Worker_Type id, and tags, with all offending fields reported together [canonical step 3, mandatory fields];
    4. HTTP 404 `error.entity.not.found` for project existence and accessibility [canonical step 4, project existence and access];
    5. HTTP 409 `error.project.team.locked` for a Locked_Status [canonical step 5, Locked_Status];
    6. HTTP 409 duplicate email, only when an email is supplied [in place of canonical step 6, member existence / duplicate membership];
    7. HTTP 400 `error.project.member.worker.type.invalid` for the existence and activity of a supplied Worker_Type [canonical step 8, team-composition rules].
    No item corresponds to canonical step 7 (user existence), because the user is created by the flow and the role is fixed server-side, nor to canonical step 9 (last ACTIVE MANAGER / last ACTIVE CLIENT), because adding a member only adds.
12. IF any step of the Worker_Record_Flow fails, including user creation, membership assignment, or the Audit_Log write of Requirement 17 criterion 1, THEN THE Worker_Record_Flow SHALL roll back the whole unit and return an error response. After the rollback there SHALL be no new user, no new Project_Member, and no Audit_Log row of Requirement 17, and a later add with the same email SHALL not be rejected as a duplicate.
13. WHEN the Worker_Record_Flow responds with HTTP 201, THE Team_Tab SHALL, without a full page reload:
    - close the add-worker form;
    - show a localized success confirmation;
    - show the new member in the `WORKERS` Team_Block with a localized "not invited" badge, Assignment_Status `ACTIVE`, the stored display name, the Worker_Kind, and the email (or an empty value when none is stored);
    - for an Internal_Attribute_Viewer only, also show the Worker_Type name (or, when no Worker_Type was submitted, the missing-worker-type warning of Requirement 14 criterion 17), the Assignment_Tags, and (for `COMPANY`) the NIP;
    - offer on the new uninvited worker an "Invite" action (criterion 17) when the worker has a stored email, and a prompt to add an email first when none is stored.
    The `WORKERS` block count SHALL increase by exactly one.
14. IF a field of the add-worker form violates criteria 2–5, THEN THE Team_Tab SHALL show a localized field-level error on each invalid field and send no request. THE Team_Tab SHALL show the company name, contact person, and NIP fields only while the Worker_Kind `COMPANY` is selected, and the person name field only while `PERSON` is selected. THE Team_Tab SHALL show the email field as optional. THE Team_Tab SHALL send no value of a hidden field. THE Team_Tab SHALL show the Worker_Type selector as an optional field with no preselection, offer only Active_Worker_Types, read from `GET /api/worker-types` (D9), allow submission with no Worker_Type selected, and send no Worker_Type id in that case. WHILE an add-worker request is in flight, THE Team_Tab SHALL disable the submit action, so that one submission sends at most one request.
15. WHILE the caller lacks `PROJECT_MEMBERS` CREATE or `PROJECTS` EDIT, or the project is in a Locked_Status, THE Team_Tab SHALL not show the add-worker action. WHILE the caller holds both operations but lacks `WORKER_TYPES` READ, THE Team_Tab SHALL show the add-worker action without the Worker_Type selector, and the add SHALL create an Uncategorized_Worker.
16. THE Worker_Record_Flow SHALL store the Worker_Kind, contact person, and NIP as nullable attributes of the user. A new Liquibase changeset, registered last in `database_files/changelog.xml` and guarded by `onFail="MARK_RAN"` preconditions, SHALL add these attributes, so that a re-run changes no schema and no data. Every user existing before the changeset, of any Company_Role, SHALL keep these attributes empty. Every user created by a path other than the Worker_Record_Flow SHALL keep them empty as well.
17. WHEN a caller holding both `PROJECT_MEMBERS` CREATE and `PROJECTS` EDIT invokes the Worker_Invitation_Flow (`POST /api/users/worker/{id}/invite`) for a WORKER user who has a stored email and has not yet activated (status reflects "not invited" or "invited" and the user has no password set), THE Worker_Invitation_Flow SHALL send exactly one standard FOR-03-02 **staff** password-set email (not the client-portal OTP email) to that user's stored email, set the user's status to reflect "invited", issue a password-set link, and respond with HTTP 200; the WORKER sets their **own** password through that link, after which the user's lifecycle reaches "active" (D-new). Re-sending the invitation to a still-not-activated WORKER SHALL be allowed and SHALL issue a fresh password-set link, invalidating any earlier unused link.
18. IF the Worker_Invitation_Flow targets a WORKER user who has no stored email, THEN THE Worker_Invitation_Flow SHALL respond with HTTP 400 and message code `error.worker.email.required`, send no email, and change no user state, and THE Team_Tab SHALL prompt the caller to add an email to the worker first. IF the targeted user is not a WORKER user, does not exist, or has already activated (already set a password), THEN THE Worker_Invitation_Flow SHALL respond with HTTP 404 `error.entity.not.found` for a non-existent user, HTTP 400 `error.worker.invite.not.allowed` for a non-WORKER user, or HTTP 409 `error.worker.already.active` for an already-activated user, in each case sending no email. THE Worker_Invitation_Flow SHALL be gated by `PROJECT_MEMBERS` CREATE plus `PROJECTS` EDIT and SHALL respond with HTTP 403 `error.access.denied` to a caller lacking either operation.
19. WHILE a WORKER member in the `WORKERS` Team_Block has not activated, THE Team_Tab SHALL show, for a caller holding `PROJECT_MEMBERS` CREATE and `PROJECTS` EDIT and a project in an Editable_Status, an "Invite" action on a never-invited worker and a "Re-send invitation" action on an already-invited but not-activated worker, each invoking the Worker_Invitation_Flow; and SHALL show a localized "not invited" or "invited, awaiting activation" status indication accordingly. WHEN the Worker_Invitation_Flow responds with HTTP 200, THE Team_Tab SHALL show a localized success confirmation and update the worker's status indication without a full page reload.

### Requirement 14: Worker type of a WORKER assignment

**User Story:** As a manager, I want to give each worker on the project a worker type when I add them or to correct it later, and to see clearly which workers still lack one, so that margins, salary calculation, and real costs can use the right tier.

#### Acceptance Criteria

1. THE Team_Service SHALL store zero or one Worker_Type on every Project_Member whose Project_Role code is `WORKER`, and no Worker_Type on any other Project_Member. FOR ALL sequences of accepted or rejected assign, Attribute_Update, deactivate, reactivate, remove, worker-record, project-creation, and Worker_Type deactivation operations, including concurrent ones, every Project_Member SHALL satisfy: a Worker_Type is present ⇒ the Project_Role code is `WORKER` (invariant property). When a WORKER Project_Member is removed, its Worker_Type is discarded with it, and a later re-assignment of the same user SHALL be subject to criteria 2–4 again.
2. WHEN an assignment through the Team_API or the Worker_Record_Flow names the Worker_Role (via the user's Company_Role) and supplies no `workerTypeId` or a null `workerTypeId`, THE Team_Service SHALL persist the WORKER Project_Member without a Worker_Type (an Uncategorized_Worker), subject to every other check of the assignment path, and THE Team_API or the Worker_Record_Flow SHALL respond with its normal success response.
3. IF an assignment through the Team_API or the Worker_Record_Flow, or a Worker_Type change, supplies a `workerTypeId` that references no Worker_Type, or references a Worker_Type whose `active` flag is `false` at validation time and that is not the current Worker_Type of the targeted Project_Member, THEN THE system SHALL respond with HTTP 400 and message code `error.project.member.worker.type.invalid` and leave the `project_members` rows unchanged. This includes a change back to a formerly held Worker_Type that has since been deactivated.
4. IF an assignment through the Team_API, the Worker_Record_Flow, or the Client_Registration_Flow is for a Project_Role other than the Worker_Role (via the user's Company_Role) and supplies a non-null `workerTypeId`, or a `members[]` entry of the Project_Creation_Orchestrator supplies a non-null `workerTypeId` (Requirement 26 criterion 3), or a Worker_Type change targets a Project_Member whose Project_Role is not the Worker_Role, THEN THE system SHALL respond with HTTP 400 and message code `error.project.member.worker.type.not.allowed` and leave the `project_members` rows unchanged; for the Project_Creation_Orchestrator and the registration flows, the whole transaction SHALL be rolled back per Requirement 6 criterion 5.
5. WHEN a caller holding `PROJECT_MEMBERS` UPDATE submits a `workerTypeId` of an Active_Worker_Type that differs from the current Worker_Type of an existing WORKER Project_Member, or submits it for an Uncategorized_Worker (assigning or correcting the Worker_Type), identified by the `(userId, projectId)` pair, of an Accessible_Project in an Editable_Status, THE Team_Service SHALL set or replace the Worker_Type of that Project_Member in one transaction, write exactly one Audit_Log row per Requirement 17, and create the single notification of Requirement 18 criterion 10, and THE Team_API SHALL respond with HTTP 200 and the updated Team_Member_View carrying the new Worker_Type. The change SHALL keep the membership id, user id, project id, Project_Role, Assignment_Status, and Assignment_Tags unchanged and leave every other Project_Member unchanged.
6. IF no Project_Member exists for the targeted `(userId, projectId)` pair of an existing Accessible_Project in an Editable_Status, THEN THE Team_API SHALL respond to a Worker_Type change with HTTP 404 and message code `error.project.member.not.found` and leave the `project_members` rows unchanged.
7. WHEN a Worker_Type change that passes canonical steps 1–6 of Requirement 3 criterion 7 (step 1 authentication, step 2 `PROJECT_MEMBERS` UPDATE, step 3 mandatory fields, step 4 project existence and access, step 5 Locked_Status, step 6 member existence) names the Worker_Type the WORKER Project_Member already has, THE Team_Service SHALL leave the Project_Member unchanged, write no Audit_Log row, and create no notification, and THE Team_API SHALL respond with HTTP 200 and the current Team_Member_View (idempotent). This SHALL apply even when that Worker_Type has `active = false`; a same-type request that fails any of those steps SHALL return the error of that step instead. The step-8 Worker_Type rules do not reject a same-type request (criterion 3 exempts the current Worker_Type).
8. IF a Worker_Type change request omits `workerTypeId`, sets it to null, or supplies a `workerTypeId` that is not an integer in the range 1 to 9,223,372,036,854,775,807 (non-numeric, fractional, zero, or negative), THEN THE Team_API SHALL respond with HTTP 400 and message code `error.project.member.worker.type.invalid`. Clearing a Worker_Type is not supported: a Worker_Type once set can only be replaced by another Worker_Type (D10). This check SHALL rank at the mandatory-fields step (step 3) of the canonical order of Requirement 3 criterion 7 and SHALL complete before any persistence.
9. THE Team_Service SHALL apply criteria 3–4 to ADMIN callers as well as non-ADMIN callers (the ADMIN ABAC bypass SHALL NOT skip them), and SHALL rank them after Requirement 6 criteria 1 and 3 within the team-composition step (step 8) of the canonical order of Requirement 3 criterion 7, with `error.project.member.worker.type.not.allowed` returned before `error.project.member.worker.type.invalid`.
10. WHEN a Worker_Type referenced by one or more Project_Members is deactivated through FOR-05-06, THE Team_Service SHALL keep every such Project_Member and its Worker_Type unchanged and write no Audit_Log row and no notification for those members. THE Team_API SHALL report that Worker_Type with active flag `false` in the Team_Member_View. THE Team_Tab SHALL show the Worker_Type name with a localized "inactive" mark. In the worker-type selector, THE Team_Tab SHALL list every Active_Worker_Type, plus the current inactive Worker_Type of the edited member shown as the preselected value with the "inactive" mark, and SHALL offer no other inactive Worker_Type; for an Uncategorized_Worker the selector SHALL list only Active_Worker_Types with no preselection.
11. IF a deletion of a Worker_Type referenced by one or more Project_Members is attempted through FOR-05-06, THEN THE system SHALL reject the deletion with HTTP 409 and message code `error.worker.type.in.use`, indicating the Worker_Type is in use by project members, and SHALL leave the Worker_Type and every Project_Member unchanged.
12. THE Team_Service SHALL store the Worker_Type of a Project_Member in a nullable `worker_type_id` column of `project_members` referencing `worker_types`, added by a new Liquibase changeset that runs after the FOR-05-06 `worker_types` seed, is registered last in `database_files/changelog.xml`, and is guarded by `onFail="MARK_RAN"` preconditions. The changeset SHALL perform no backfill: every Project_Member existing before it, of any Project_Role, SHALL end with no Worker_Type, so that every existing WORKER Project_Member becomes an Uncategorized_Worker shown with the warning of criteria 17–18. The changeset SHALL write no Audit_Log row and no notification, SHALL not depend on the `is_base` flag of `worker_types`, and a re-run SHALL change no schema and no data.
13. THE Team_Service SHALL provide, for a given project id, one entry for each WORKER Project_Member carrying the user id, the membership id, the Assignment_Status, and the current Worker_Type id and code, including inactive Worker_Types, and carrying an explicit null `workerTypeId` and `workerTypeCode` for each Uncategorized_Worker, so that downstream consumers can flag such members. This read-only input serves FOR-05-06 margin computation and FOR-10 / FOR-11 payroll and real-cost computation, and reports each Uncategorized_Worker explicitly so a downstream consumer may treat it as not-ready; the readiness gate itself MAY treat an Uncategorized_Worker as not-ready, but the exact rule is deferred to the readiness spec (Q4, Q13). This read SHALL modify no `project_members` row. Computing pay, rates, or costs is out of scope.
14. IF two or more requests that change the Worker_Type of the same Project_Member are processed concurrently, THEN THE Team_Service SHALL serialize them so that the Project_Member ends with the Worker_Type of the last committed request. Each accepted request SHALL produce one Audit_Log row whose before Member_Snapshot equals the state committed immediately before it, and THE Team_API SHALL respond to each request with HTTP 200 or with a 4xx defined in this spec, never with HTTP 5xx. IF a Worker_Type change runs concurrently with a remove of the same Project_Member, THEN THE Team_API SHALL respond to the change either with HTTP 200 (change committed before the remove) or with HTTP 404 `error.project.member.not.found` (remove committed first). IF it runs concurrently with the deactivation of the requested Worker_Type, THEN THE Team_API SHALL respond either with HTTP 400 `error.project.member.worker.type.invalid` or with HTTP 200, after which criterion 10 applies.
15. IF an assignment or a Worker_Type change is rejected under any of criteria 3, 4, 6, or 8, THEN THE Team_Service SHALL write no Audit_Log row, create no notification, and leave the `ProjectAccessCache` entry of the targeted user unchanged.
16. WHILE the caller is an Internal_Attribute_Viewer, THE Team_Member_View SHALL contain a boolean `workerTypeMissing` that is `true` if and only if the member's Project_Role code is `WORKER` and the member has no Worker_Type, and `false` for every other member (consistency property: FOR ALL Team_Member_Views returned to an Internal_Attribute_Viewer, `workerTypeMissing` = (Project_Role code = `WORKER` and Worker_Type id is null)). For every other caller THE Team_API SHALL omit the field (Requirement 4 criterion 11, D12).
17. WHILE the viewer is an Internal_Attribute_Viewer, THE Team_Tab SHALL show a localized "worker type missing" warning badge on the table row and the card of every member whose `workerTypeMissing` is `true`. WHILE at least one such member is shown in the WORKERS Team_Block, THE Team_Tab SHALL show in the WORKERS block header a localized block-level warning stating the number of such members, and WHILE no such member is shown, THE Team_Tab SHALL show no block-level warning. The badge and the block-level warning SHALL carry a text label, so that the warning can be identified without relying on color alone.
18. WHILE the viewer is an Internal_Attribute_Viewer holding `PROJECT_MEMBERS` UPDATE and `WORKER_TYPES` READ and the project is in an Editable_Status, THE Team_Tab SHALL offer on every member whose `workerTypeMissing` is `true` an inline localized "Assign worker type" action that opens the Worker_Type selector of criterion 10 for that member. WHEN the resulting Worker_Type change of criterion 5 succeeds, THE Team_Tab SHALL remove the member's warning badge, decrease the count of the block-level warning by one (removing the warning when the count reaches zero), and show the assigned Worker_Type name, without a full page reload. IF the change fails, THEN THE Team_Tab SHALL keep the warning and handle the error per Requirement 23 criterion 11.

### Requirement 15: Assignment tags

**User Story:** As a manager, I want to mark each assignment with short internal tags such as a specialization or an alias, so that I can recognize and filter people within a project.

#### Acceptance Criteria

1. THE Team_Service SHALL store, for each Project_Member, an ordered list of 0 to 10 Assignment_Tags in the order produced by Tag_Normalization. The list SHALL belong to that Project_Member only, so the tags of a user in one project are independent of the tags of the same user in any other project (D11). Setting, changing, or discarding the tags of one Project_Member SHALL leave the Assignment_Tags of every other Project_Member unchanged.
2. WHEN an assignment (Requirement 5), a client invitation (Requirement 12), an add-worker (Requirement 13), or a tag change (criterion 5) supplies a tag list, THE Team_Service SHALL store the Tag_Normalization of that list. WHEN an assignment, including one made by the Project_Creation_Orchestrator, or an add-worker omits the tag list or supplies a null tag list, THE Team_Service SHALL store an empty Assignment_Tag list for the new Project_Member.
3. IF a submitted tag list is not a list of strings, contains a null element, contains a tag that has 0 characters or more than 50 characters after trimming leading and trailing whitespace, contains a tag holding a control character (line feed, carriage return, tab, or any other Unicode control character), or holds more than 10 tags after Tag_Normalization, THEN THE system SHALL respond with HTTP 400 and message code `error.project.member.tag.invalid`, reject the whole list without saving any part of it, and THE Team_Service SHALL leave the `project_members` rows unchanged. Characters SHALL be counted as Unicode code points. The 10-tag limit SHALL apply to the normalized list: for example, a submitted list of 12 entries that normalizes to 10 tags is accepted. A tag-change request (criterion 5) that omits the tag list or supplies a null tag list SHALL also be rejected with HTTP 400 and `error.project.member.tag.invalid`; clearing tags requires an explicit empty list.
4. FOR ALL valid tag lists, applying Tag_Normalization to an already normalized list SHALL return the same list (idempotence). The tags returned in the Team_Member_View after a save SHALL equal the Tag_Normalization of the submitted list (round-trip property). The stored list SHALL contain no tag with leading or trailing whitespace, no two tags that are equal under case-insensitive comparison, and no more tags than the submitted list.
5. WHEN a caller holding `PROJECT_MEMBERS` UPDATE submits a tag list for an existing Project_Member of an Accessible_Project in an Editable_Status, with the Project_Member identified by the `(userId, projectId)` pair and in any Team_Block, THE Team_Service SHALL replace the whole tag list of that Project_Member with the Tag_Normalization of the submitted list (replace, not merge). A tag present before but absent from the submitted list SHALL be removed, and an empty list SHALL clear all tags. THE Team_API SHALL respond with HTTP 200 and the updated Team_Member_View. The change SHALL keep the membership id, user id, project id, Project_Role, Worker_Type, and Assignment_Status unchanged. IF no Project_Member exists for the pair, THEN THE Team_API SHALL respond with HTTP 404 and message code `error.project.member.not.found`. IF the project is in a Locked_Status, THEN THE Team_API SHALL respond with HTTP 409 and message code `error.project.team.locked` (Requirement 10) and leave the tags unchanged.
6. WHEN a tag change yields, after Tag_Normalization, the list the Project_Member already has, THE Team_Service SHALL leave the Project_Member unchanged, write no Audit_Log row, and create no notification, and THE Team_API SHALL respond with HTTP 200 and the current Team_Member_View (idempotent). Two lists SHALL count as the same only when they have the same number of tags and identical strings in the same positions under case-sensitive comparison. A change that only reorders tags or only changes the letter case of a tag SHALL be stored and audited as a change (Requirement 17 criterion 8).
7. WHEN a Project_Member is removed, THE Team_Service SHALL discard its Assignment_Tags. WHEN the same user is assigned to the same project again, THE Team_Service SHALL store only the tags supplied with the new assignment, or an empty list when none are supplied. WHEN the Assignment_Status of a Project_Member changes (Requirement 27), THE Team_Service SHALL keep its Assignment_Tags unchanged.
8. WHILE the viewer is an Internal_Attribute_Viewer, THE Team_Tab SHALL show the Assignment_Tags of each member as chips in the member's table row and card, in the stored order and with the full tag text. THE Team_Tab SHALL also offer a client-side single-select tag filter that lists each distinct tag of the loaded members exactly once (compared case-insensitively, shown in the spelling of its first occurrence in the loaded member order) and sorts the entries alphabetically and case-insensitively. WHILE no loaded member has any Assignment_Tag, THE Team_Tab SHALL show no tag filter. A WORKER or CLIENT reader SHALL never see tag chips or the tag filter.
9. WHEN the viewer selects a tag in the tag filter, THE Team_Tab SHALL show, in every Team_Block, only the members whose Assignment_Tags contain that tag (compared case-insensitively). It SHALL update each block count to the number of members shown, show a localized "no matching members" text in a block left without members, and send no request. WHEN the viewer clears the filter, THE Team_Tab SHALL show all loaded members again. WHEN the member list is refreshed and no loaded member still carries the selected tag, THE Team_Tab SHALL clear the filter and show all loaded members.
10. WHEN a submitted tag consists of 1 to 50 characters after trimming and contains no control character, THE Team_Service SHALL accept it whatever else it contains (letters of any script, including Polish and Cyrillic letters, digits, punctuation, and inner spaces) and SHALL store its inner whitespace and letter case exactly as submitted.
11. IF an assignment, a client invitation, or an add-worker is rejected under criterion 3, THEN THE system SHALL create no Project_Member, create no user, send no invitation email, write no Audit_Log row, and create no notification for that request.

### Requirement 16: Access consistency after membership changes

**User Story:** As a user, I want my access to follow team changes promptly, so that a removed or deactivated member loses access and a new or reactivated member gains it.

#### Acceptance Criteria

1. WHEN an assign, remove, deactivate, or reactivate operation on a Project_Member commits, THE Team_Service SHALL invalidate the `ProjectAccessCache` entry of the affected user after the commit and before the Team_API returns the success response. THE Team_Service SHALL NOT wait for the cache idle expiry.
2. WHEN a Project_Member is removed and the Team_API has returned the success response, THE Team_API SHALL treat the project as not an Accessible_Project for the removed non-ADMIN user on every later request by that user. Project-scoped requests by that user for that project SHALL be rejected as for a non-accessible project (Requirement 3 criterion 3): by-id project-scoped reads and writes SHALL return HTTP 404 with message code `error.entity.not.found`, and the project SHALL be absent from that user's project-scoped list results. A removed ADMIN user SHALL keep access, because every project is an Accessible_Project for an ADMIN.
3. IF any step of an assign, remove, deactivate, or reactivate operation fails, THEN THE Team_Service SHALL roll back the whole operation and return an error response indicating the failure. Failing steps include a team-composition validation rejection, a database constraint violation, and an Audit_Log write failure. After the rollback, the `project_members` rows SHALL be unchanged, no Audit_Log row of Requirement 17 SHALL remain, no notification of Requirement 18 SHALL be created, and the affected user's set of Accessible_Projects on the next request SHALL equal the set before the failed operation.
4. WHEN a Project_Member is assigned and the Team_API has returned the success response, THE Team_API SHALL treat the project as an Accessible_Project for the assigned user on that user's next request. Access SHALL NOT depend on any earlier cached entry for that user.
5. WHEN a Project_Member's Assignment_Status, Worker_Type, or Assignment_Tags change and the Team_API has returned the success response, THE Team_API SHALL continue to treat the project as an Accessible_Project for that member on the member's next request, since an Attribute_Update, including deactivation, does not remove the membership. (Whether an INACTIVE member retains project access beyond this spec's membership-based rule is a FOR-06 concern.)
6. IF two concurrent assign requests target the same user and the same project, THEN THE Team_Service SHALL commit at most one Project_Member row for that pair. The other request SHALL be rejected with a duplicate-membership error and rolled back according to criterion 3.

### Requirement 17: Audit trail of team changes

**User Story:** As an administrator, I want every team change audited, so that I can reconstruct who granted, deactivated, or revoked project access.

#### Acceptance Criteria

1. WHEN a Project_Member is assigned through the Team_API, the Project_Creation_Orchestrator, the Client_Registration_Flow, or the Worker_Record_Flow, THE Team_Service SHALL write exactly one Audit_Log row with action `CREATE`, the membership id as subject, the id of the authenticated user who performed the operation (including ADMIN callers), the time of the change, and a snapshot (the Member_Snapshot) containing user id, project id, Project_Role code, Worker_Type code (empty when none), Assignment_Status (`ACTIVE`), and the Assignment_Tags of the new Project_Member.
2. WHEN a Project_Member's Assignment_Status is changed (deactivated or reactivated, Requirement 27), THE Team_Service SHALL write exactly one Audit_Log row with action `UPDATE`, the membership id as subject, the id of the performing user, the time of the change, and before and after Member_Snapshots, where only the Assignment_Status differs between the two snapshots.
3. WHEN a Project_Member is removed, THE Team_Service SHALL write exactly one Audit_Log row with action `DELETE`, the removed membership id as subject, the id of the performing user, the time of the change, and the Member_Snapshot the Project_Member held immediately before removal.
4. WHEN an Attribute_Update leaves the Worker_Type, the normalized Assignment_Tags, or the Assignment_Status unchanged (Requirement 14 criterion 7, Requirement 15 criterion 6, Requirement 27 criterion 6), THE Team_Service SHALL write no Audit_Log row. There is no role-change audit, because the Project_Role is immutable (D2).
5. IF an assign, Attribute_Update, deactivate, reactivate, or remove operation is rejected by authorization (Requirement 3), validation (Requirements 5, 6, 14, 15, 27), the composition invariants (Requirement 9), or the lifecycle lock (Requirement 10), or is rolled back (Requirement 16 criterion 3), THEN THE Team_Service SHALL persist no Audit_Log row for that operation.
6. IF writing the Audit_Log row for an assign, Attribute_Update, deactivate, reactivate, or remove operation fails, THEN THE Team_Service SHALL roll back the whole operation, leaving the `project_members` rows unchanged, and THE Team_API SHALL report the operation as failed to the caller.
7. THE Team_Service SHALL include no password, token, worker rate, tariff, Worker_Type tier percentage, or cost value in any team-change Audit_Log snapshot.
8. WHEN an Attribute_Update changes the Worker_Type or the Assignment_Tags of a Project_Member, THE Team_Service SHALL write exactly one Audit_Log row with action `UPDATE`, the membership id as subject, the id of the performing user, the time of the change, and before and after Member_Snapshots, where only the Worker_Type code (for a Worker_Type change, including a first assignment to an Uncategorized_Worker whose before snapshot has an empty Worker_Type code) or only the Assignment_Tags (for a tag change) differ between the two snapshots.

### Requirement 18: In-app notification of team changes

**User Story:** As a team member, I want to be notified when I am added to a project, my worker type changes, or my assignment is deactivated or reactivated, so that I know where I am expected to work.

#### Acceptance Criteria

1. WHEN a Project_Member is assigned by a user other than the member, THE Team_Service SHALL create exactly one in-app notification for the assigned user through the Notification_Service. The notification SHALL use the "team member assigned" Notification_Type and carry the project name, the assigned Project_Role, and a deep-link to that project's Workspace_Shell. This applies equally to assignments made from the Team_Tab, to the admin-staff members and the CLIENT member assigned by the Project_Creation_Orchestrator, to the CLIENT membership assigned by the Client_Registration_Flow, and to the WORKER membership assigned by the Worker_Record_Flow. In each case the assigned user receives one notification per assignment. (An uninvited worker with no login still receives an in-app notification record, visible once invited and activated.)
2. WHEN a Project_Member's Assignment_Status is changed (deactivated or reactivated, Requirement 27) by a user other than the member, THE Team_Service SHALL create exactly one in-app notification for the member through the Notification_Service. The notification SHALL use the "team assignment status changed" Notification_Type and carry the project name, the new Assignment_Status, and a deep-link to that project's Workspace_Shell.
3. WHEN a Project_Member is removed by a user other than the member, THE Team_Service SHALL create exactly one in-app notification for the removed user through the Notification_Service. The notification SHALL use the "team member removed" Notification_Type, carry the project name and the removed Project_Role, and have no deep-link. The removed user can still see it in their own notification list after losing access to the project.
4. WHEN the performing user is the affected member (self-assignment, self-deactivation, self-reactivation, or self-removal), THE Team_Service SHALL create no notification for that operation.
5. THE Team_Service SHALL create the notification only after the membership transaction commits. It SHALL create notifications only for the affected member, never for other members of the project.
6. IF a membership operation is rejected (by an access check, a team-composition rule such as D5, a Locked_Status, a duplicate, or validation) or its transaction rolls back, THEN THE Team_Service SHALL create no notification for that operation.
7. IF the Notification_Service fails to create the notification, THEN THE Team_Service SHALL log the failure, keep the committed membership change, and return the membership operation's normal success response to the caller without an error indication.
8. WHEN an Attribute_Update leaves the Assignment_Status, the Worker_Type, or the Assignment_Tags unchanged (idempotent update), THEN THE Team_Service SHALL create no notification.
9. THE System SHALL resolve the Team_Notification_Types used by this spec ("team member assigned", "team worker type changed", "team assignment status changed", "team member removed") and the Project_Role, Worker_Type, and Assignment_Status names in them to localized text via i18n keys. Those keys SHALL exist in both `pl.json` and `ru.json` with identical key sets and non-empty values, and the System SHALL never show a raw i18n key in the notification.
10. WHEN the Worker_Type of a WORKER Project_Member is set (for an Uncategorized_Worker) or changed to a different Worker_Type by a user other than the member, THE Team_Service SHALL create exactly one in-app notification, for the affected worker only, through the Notification_Service. The notification SHALL use the "team worker type changed" Notification_Type and carry the project name, the new Worker_Type name, and a deep-link to that project's Workspace_Shell, and SHALL carry no tier percentage, rate, or cost.
11. WHEN the Assignment_Tags of a Project_Member are set or changed, by any path, THE Team_Service SHALL create no notification for the tag change.
12. THE Team_Service SHALL include no Assignment_Tag in any notification, and no Worker_Type in any notification other than the one of criterion 10 sent to the affected worker.

### Requirement 19: Multi-client project projections and CLIENT suppression

**User Story:** As a manager, I want project lists and project details to show every client of a project, so that co-owners are not hidden behind a single "client" field; and I want a CLIENT caller never to see the project's member list.

#### Acceptance Criteria

1. THE `ProjectReadDto` and `ProjectListDto` SHALL contain a `clients` list holding one entry for every Project_Member whose Project_Role code is `CLIENT`, ordered by membership id ascending, where each entry has the same structure as the existing `client` field. This includes CLIENT members whose user has status `INVITED`, whose user is an Inactive_User, or whose Assignment_Status is `INACTIVE`.
2. IF a project has no Project_Member whose Project_Role code is `CLIENT`, THEN THE `ProjectReadDto` and `ProjectListDto` SHALL return `clients` as an empty list, never `null`.
3. THE `ProjectReadDto` and `ProjectListDto` SHALL keep the existing `client` field, populated with the CLIENT member having the lowest membership id, so that `client` equals the first entry of `clients`. The field SHALL be `null` when the project has no CLIENT member.
4. THE `members` collection of `ProjectReadDto` and `ProjectListDto` SHALL contain every Project_Member, including all CLIENT members, for every non-CLIENT caller, so that the number of `members` entries with Project_Role `CLIENT` equals the number of `clients` entries.
5. WHEN a CLIENT member is assigned, removed, deactivated, or reactivated through the Team_API or the Client_Registration_Flow, THE `clients` list, the `client` field, and the `members` collection of every `ProjectReadDto` and `ProjectListDto` returned for that project afterward SHALL reflect the change.
6. WHERE a project has more than one CLIENT member, THE projects list client cell SHALL show the name of the client in the `client` field, followed by a "+N" indicator localized in pl and ru, where N equals the number of `clients` entries minus 1.
7. IF a project has exactly one CLIENT member, THEN THE projects list client cell SHALL show only that client's name with no "+N" indicator. IF a project has no CLIENT member, THEN the cell SHALL show the same empty-value placeholder the projects list uses for other empty cells.
8. THE `members`, `clients`, and `client` entries of `ProjectReadDto` and `ProjectListDto` SHALL contain no Worker_Type, Worker_Type tier percentage, NIP, internal status note, or Assignment_Tag field for any caller, applying the same extended admin-staff masking as the Team_API (D12); the Assignment_Status is not internal and may be included.
9. IF the caller of a `ProjectReadDto` or `ProjectListDto` read has Company_Role CLIENT, THEN THE DTO SHALL omit the `members` collection entirely (an absent or empty collection carrying no member of any role), so that a CLIENT caller sees no project member list (Q6, parent ABAC gives CLIENT no `PROJECT_MEMBERS` access). The `client` and `clients` projections are likewise suppressed for a CLIENT caller through the DTO; any minimal client-facing contact that a client needs in the client portal is a FOR-09 concern and is not served by this DTO. A non-CLIENT caller is unaffected.

### Requirement 20: Team readiness gate

**User Story:** As a manager preparing a project for signing, I want the readiness tracker to show whether the team is assigned, so that I know whether staffing still blocks signing.

#### Acceptance Criteria

1. THE Team_Service SHALL compute the Team_Readiness_Gate of a project as `DONE` when the project has at least one Project_Member whose Project_Role is `FOREMAN` and whose Assignment_Status is `ACTIVE`, and as `BLOCKED` otherwise. The computation counts ACTIVE FOREMAN Project_Members whether or not their user is an Inactive_User, and does not count INACTIVE FOREMAN members. Project_Members under any other Assignable_Project_Role, including `ESTIMATOR`, do not count toward the gate. The gate never takes a state other than `DONE` or `BLOCKED`. Whether an Uncategorized_Worker should also affect this gate is deferred to the readiness spec (Q4, Q13); this spec keeps the FOREMAN-only rule.
2. WHEN a caller with `PROJECT_MEMBERS` READ requests the team readiness of an Accessible_Project, THE Team_API SHALL return the gate key `team`, the gate state, and one count of ACTIVE Project_Members for each of the six Assignable_Project_Roles. A role with no ACTIVE members is returned with a count of 0. The returned state and counts SHALL reflect every membership assignment, Assignment_Status change, and removal committed before the request was received.
3. FOR ALL teams, THE Team_Readiness_Gate state SHALL equal `DONE` if and only if the ACTIVE FOREMAN count returned with the gate is at least one (consistency property).
4. WHILE a design-stage project (one that `stageOf` does not classify as execution) is displayed and the viewer holds `PROJECT_MEMBERS` READ, THE Readiness_Widget SHALL display one chip for the Team_Readiness_Gate. The chip SHALL show the localized gate name and the localized state label (pl and ru). The `DONE` and `BLOCKED` states SHALL use two distinct visual styles, so the state can be identified from the state label text alone and not only from color.
5. WHILE the viewer lacks `PROJECT_MEMBERS` READ, THE Readiness_Widget SHALL omit the Team_Readiness_Gate chip, exclude the gate from the headline percentage, and send no team readiness request.
6. THE Readiness_Widget SHALL label every gate chip with its localized gate name in addition to the state label.
7. THE Readiness_Widget SHALL display the headline percentage as an integer from 0 to 100, computed as `round(100 × count(gates in state DONE) / count(gates displayed))` with halves rounded up. It SHALL display 0 when no gate is displayed. Only gates whose chip is currently displayed count in the numerator and the denominator.
8. WHEN a team change (member assignment, Assignment_Status change, member removal, client invitation, or add-worker) is saved successfully in the Team_Tab, THE Readiness_Widget SHALL update the Team_Readiness_Gate chip and the headline percentage to the server-computed values within 2 seconds of the save response, without a full page reload. IF the save fails, THEN the chip and headline percentage SHALL stay as they were.
9. IF the caller lacks `PROJECT_MEMBERS` READ, THEN THE Team_API SHALL reject the team readiness request with HTTP 403 and message code `error.access.denied` (Requirement 2 criterion 3). The response SHALL contain no gate state and no member counts.
10. IF a non-ADMIN caller requests the team readiness of a project that is not an Accessible_Project, or any caller, ADMIN included, requests the team readiness of a project id that does not exist, THEN THE Team_API SHALL reject the request with HTTP 404 and message code `error.entity.not.found`, with the same response body in both cases (Requirement 3 criteria 3–4). The response SHALL contain no gate state and no member counts.
11. IF the team readiness request from the Readiness_Widget fails, THEN THE Readiness_Widget SHALL omit the Team_Readiness_Gate chip, exclude the gate from the headline percentage, and show a localized indication that team readiness could not be loaded. All other workspace tabs and actions SHALL remain usable.

### Requirement 21: Team tab registration in the workspace

**User Story:** As a user of the project workspace, I want a Team tab next to the other design-stage tabs, so that staffing is part of preparing the project.

#### Acceptance Criteria

1. THE `WORKSPACE_TABS` contract SHALL contain exactly one tab with key `team`, stage `design`, owner `FOR-05`, required permission `{ resource: 'PROJECT_MEMBERS', operation: 'READ' }`, and label key `workspace.tab.team`. The tab SHALL be declared immediately after the `documentSigning` tab and before the first execution-stage tab, which makes `team` the last design-stage entry in declaration order.
2. WHILE the viewer holds `PROJECT_MEMBERS` READ, THE Workspace_Shell SHALL show the Team_Tab exactly once, as follows:
   - For a design-stage project (any status other than `ACTIVE` and `COMPLETED`, per `stageOf`), it SHALL appear as the last design-stage tab in the main tab strip and SHALL be absent from the design selector.
   - For an execution-stage project (`ACTIVE` or `COMPLETED`), it SHALL appear as the last entry of the design selector and SHALL be absent from the main tab strip.
3. WHILE the viewer lacks `PROJECT_MEMBERS` READ, THE Workspace_Shell SHALL hide the Team_Tab from both the main tab strip and the design selector for projects in every status. Under the base Permission_Matrix this includes WORKER and CLIENT viewers.
4. WHEN a viewer holding `PROJECT_MEMBERS` READ opens `/projects/:projectId/team`, THE Workspace_Shell SHALL render the Team_Tab as the active tab without changing the URL:
   - For a design-stage project, the Team_Tab SHALL be marked selected in the main tab strip.
   - For an execution-stage project, the Team_Tab SHALL be marked selected in the design selector.
5. IF a viewer lacking `PROJECT_MEMBERS` READ opens `/projects/:projectId/team`, THEN THE Workspace_Shell SHALL do all of the following:
   - Replace the URL (without adding a browser-history entry) with the default visible tab. This is the tab stored in the viewer's per-project tab memory if that tab is visible, otherwise `overview`.
   - Render that tab as active.
   - Render no Team_Tab content and send no request to the Team_API.
6. WHEN a viewer holding `PROJECT_MEMBERS` READ selects the Team_Tab, THE Workspace_Shell SHALL take all of the following actions:
   - Navigate to `/projects/:projectId/team`.
   - Render the Team_Tab as active.
   - Record `team` as the viewer's remembered tab for that project. Reopening `/projects/:projectId` without a tab segment then resolves to the Team_Tab while the viewer still holds `PROJECT_MEMBERS` READ.
7. THE Workspace_Shell SHALL render the Team_Tab label from the `workspace.tab.team` key as "Zespół" in the pl locale and "Команда" in the ru locale. Neither locale SHALL fall back to the raw key or to the other locale's text.

### Requirement 22: Team tab: viewing the team

**User Story:** As a manager or foreman, I want the team shown as three separate blocks (admin staff, workers, clients), so that I can see gaps at a glance on desktop and on mobile.

#### Acceptance Criteria

1. WHEN the Team_API returns the Team_Member_Views of the project, THE Team_Tab SHALL display all returned Team_Member_Views, without pagination or truncation, in three separate Team_Blocks rendered in the order ADMIN_STAFF, WORKERS, CLIENTS, placing each member in the Team_Block given by its `block` field and keeping the members of each block in the order returned by the Team_API (Requirement 4 criterion 6). THE Team_Tab SHALL render all three Team_Blocks whenever the member list request succeeds, including a block with zero members.
2. THE Team_Tab SHALL display for each Team_Block a heading containing the localized block title and a member count equal to the number of members rendered in that block.
3. WHILE a Team_Block has zero members (and no tag filter is active), THE Team_Tab SHALL display a localized empty state specific to that Team_Block inside that block, and SHALL keep the other blocks and that block's own actions (Requirement 23) visible.
4. THE Team_Tab SHALL show for each member the display name and email from the Team_Member_View, the localized Assignment_Status (an "inactive assignment" badge only when the Assignment_Status is `INACTIVE`), a localized "invited" badge only when the user status is `INVITED`, a localized "not invited" badge only when the WORKERS member has never been invited, and a localized "inactive" badge only when the user active flag is `false`; in addition: in ADMIN_STAFF the localized Project_Role name; in WORKERS the localized Worker_Kind label, the contact person when present, and, when returned, the localized Worker_Type name (with an "inactive" mark when the Worker_Type is inactive) or, for an Uncategorized_Worker, the missing-worker-type warning of Requirement 14 criterion 17; and in every block the Assignment_Tag chips when returned (Requirement 15 criterion 8).
5. WHILE the viewport width is 768px or greater, THE Team_Tab SHALL render each Team_Block as its own table whose rows are that block's members and whose columns are the fields of criterion 4 for that block plus the status badges and the row actions.
6. WHILE the viewport width is less than 768px, THE Team_Tab SHALL render each Team_Block as its own vertical stack of cards, one card per member, showing the same fields, badges, and actions as the table row.
7. WHILE the loaded member list contains zero ACTIVE FOREMAN members (including an empty team), THE Team_Tab SHALL display, in the ADMIN_STAFF block, a localized hint stating that an active foreman is required for readiness, and WHEN a refreshed member list contains at least one ACTIVE FOREMAN member, THE Team_Tab SHALL remove the hint without a full page reload.
8. WHILE the member list request is in progress, THE Team_Tab SHALL display a loading indicator and SHALL display neither the Team_Blocks, member rows, member cards, empty states, nor the foreman hint.
9. IF the member list request fails, THEN THE Team_Tab SHALL display a localized error message and a retry action instead of the Team_Blocks, and WHEN the user activates the retry action, THE Team_Tab SHALL re-issue the member list request and return to the loading state.
10. WHILE the viewer is not an Internal_Attribute_Viewer, THE Team_Tab SHALL show no Worker_Type, missing-worker-type warning, NIP, or Assignment_Tag and no tag filter; the Assignment_Status badge SHALL still be shown.

### Requirement 23: Team tab: editing the team

**User Story:** As a manager, I want each team block to have its own add and invite actions, and to re-categorize, tag, deactivate, reactivate, and remove members from the Team tab, so that I can staff the project without leaving the workspace; I cannot change a member's role, because the role is the user's global role.

#### Acceptance Criteria

1. WHILE the viewer holds `PROJECT_MEMBERS` CREATE and the project is in an Editable_Status, THE Team_Tab SHALL show in the ADMIN_STAFF block an "Add staff member" action that opens a dialog containing the candidate search of Requirement 11 scoped to the current project and to the `ADMIN_STAFF` Team_Block, an optional tag input, and a submit action that stays disabled until exactly one Candidate is selected. THE Team_Tab SHALL submit the assignment under the Candidate's Company_Role (the member's role is always the user's global role, D2) and SHALL show no Project_Role selector and no Worker_Type selector in this dialog.
2. WHILE the viewer holds `PROJECT_MEMBERS` CREATE and the project is in an Editable_Status, THE Team_Tab SHALL show in the WORKERS block an "Add worker" action that opens a dialog containing the candidate search scoped to the `WORKERS` Team_Block, an optional tag input, and a submit action that stays disabled until exactly one Candidate is selected; THE Team_Tab SHALL submit the assignment under the Worker_Role (the Company_Role of every `WORKERS` Candidate) and SHALL show no Project_Role selector. WHILE the viewer also holds `WORKER_TYPES` READ, the dialog SHALL contain an optional Worker_Type selector listing only Active_Worker_Types ordered by `order_no` with no preselection; WHEN the viewer submits without a Worker_Type, THE Team_Tab SHALL send no `workerTypeId`, and the new member SHALL be shown with the warning of Requirement 14 criterion 17.
3. WHILE the viewer holds `PROJECT_MEMBERS` CREATE and `PROJECTS` EDIT and the project is in an Editable_Status, THE Team_Tab SHALL show in the WORKERS block an "Add new worker" action (Requirement 13 criteria 1–16, no invitation), whose Worker_Type selector is optional (Requirement 13 criteria 14–15), and SHALL show on each uninvited or not-yet-activated WORKER member an "Invite" or "Re-send invitation" action (Requirement 13 criteria 17–19).
4. WHILE the viewer holds `PROJECT_MEMBERS` CREATE and the project is in an Editable_Status, THE Team_Tab SHALL show in the CLIENTS block an "Add client" action that opens a dialog containing the candidate search scoped to the `CLIENTS` Team_Block, an optional tag input, and a submit action that stays disabled until exactly one Candidate is selected; THE Team_Tab SHALL submit the assignment under the Client_Role (the Candidate's Company_Role). WHILE the viewer also holds `PROJECTS` EDIT, THE Team_Tab SHALL show in the CLIENTS block an "Invite new client" action (Requirement 12).
5. THE Team_Tab SHALL offer no role-change control on any member of any Team_Block, because a member's Project_Role is the user's immutable global role (D2, Requirement 7). To change a person's capacity, their global Company_Role must be changed in the admin area, which is out of scope here.
6. WHILE the viewer holds `PROJECT_MEMBERS` UPDATE and `WORKER_TYPES` READ and the project is in an Editable_Status, THE Team_Tab SHALL offer on each WORKERS member that has a Worker_Type a Worker_Type change control preselected with the member's current Worker_Type and listing the Active_Worker_Types (Requirement 14 criterion 10), offering no "none" option, and SHALL send no request when the viewer confirms the Worker_Type the member already has; on each Uncategorized_Worker THE Team_Tab SHALL offer the "Assign worker type" action of Requirement 14 criterion 18 instead.
7. WHILE the viewer holds `PROJECT_MEMBERS` UPDATE and the project is in an Editable_Status, THE Team_Tab SHALL offer on each member an edit-tags control prefilled with the member's current Assignment_Tags; IF the edited list violates Requirement 15 criterion 3, THEN THE Team_Tab SHALL show a localized field-level error and send no request; WHEN the Tag_Normalization of the edited list equals the current list, THE Team_Tab SHALL send no request.
8. WHILE the viewer holds `PROJECT_MEMBERS` UPDATE and the project is in an Editable_Status, THE Team_Tab SHALL offer on each ACTIVE member a "Deactivate" action and on each INACTIVE member a "Reactivate" action (Requirement 27), each opening a confirmation showing the member's display name and the project name, and SHALL send the request only after the viewer activates the confirm action.
9. WHILE the viewer holds `PROJECT_MEMBERS` DELETE and the project is in an Editable_Status, THE Team_Tab SHALL offer a remove action on each member of every Team_Block that opens a confirmation showing the member's display name and the project name and explaining that remove discards history (deactivate keeps it), and SHALL send the remove request only after the viewer activates the confirm action of that confirmation.
10. WHILE the project is in an Editable_Status, THE Team_Tab SHALL hide each action of criteria 1–9 whose required operations the viewer lacks; WHEN the viewer lacks all of these operations, THE Team_Tab SHALL render the team read-only.
11. WHEN an add, invite, Worker_Type change, tag change, deactivate, reactivate, or remove request succeeds, THE Team_Tab SHALL close the dialog or confirmation that issued it, refresh the member list without a full page reload so that it reflects the change, and show a localized success message naming the operation performed.
12. IF an add, invite, Worker_Type change, tag change, deactivate, reactivate, or remove request fails, THEN THE Team_Tab SHALL keep the issuing dialog open with the entered values retained, show the localized message for the returned message code (including `error.project.member.last.manager`, `error.project.member.last.client`, `error.project.member.role.not.assignable`, `error.project.member.role.mismatch`, `error.project.member.user.inactive`, `error.project.member.duplicate`, `error.project.member.not.found`, `error.project.member.worker.type.invalid`, `error.project.member.worker.type.not.allowed`, `error.project.member.tag.invalid`, `error.worker.nip.invalid`, `error.worker.email.required`, `error.worker.invite.not.allowed`, `error.worker.already.active`, and `error.project.team.locked`), show a generic localized error message when the response carries no message code, an unknown message code, or no response is received, and keep the member list unchanged.
13. THE Team_Tab controls SHALL be reachable with the Tab key and activatable with Enter or Space; every add, invite, Worker_Type change, edit-tags, deactivate, reactivate, and remove dialog SHALL move focus into itself on open, close on Escape, and return focus to the control that opened it on close; every icon-only control and every removable tag chip SHALL carry an accessible name in the viewer's active language (pl or ru).
14. WHILE the project is in a Locked_Status, THE Team_Tab SHALL hide the add, invite, Worker_Type change, edit-tags, deactivate, reactivate, and remove controls regardless of the viewer's `PROJECT_MEMBERS`, `PROJECTS`, and `WORKER_TYPES` operations and render the team read-only.
15. WHILE an add, invite, Worker_Type change, tag change, deactivate, reactivate, or remove request is pending, THE Team_Tab SHALL disable the submit or confirm action that sent it and SHALL send no further request for that action until the pending request completes with success or failure.
16. WHEN the viewer dismisses an add dialog, invite dialog, Worker_Type change control, edit-tags control, deactivate confirmation, reactivate confirmation, or remove confirmation by its cancel action or by Escape, THE Team_Tab SHALL send no request and leave the member list unchanged.

### Requirement 24: Localization

**User Story:** As a Polish- or Russian-speaking user, I want the Team tab and its messages in my language, so that I understand every label and error.

#### Acceptance Criteria

1. THE frontend locales `pl.json` and `ru.json` SHALL contain the same set of keys for every Team_Tab label, Team_Block title, per-block empty-state text, Team_Tab action (including "Add worker", "Invite", "Re-send invitation", "Deactivate", "Reactivate", and "Remove"), add-worker form label (Worker_Kind, person name, company name, contact person, NIP, optional email, Worker_Type), Worker_Kind value, worker status indication ("not invited", "invited, awaiting activation"), Assignment_Status label ("active assignment", "inactive assignment"), tag input and tag filter text, "inactive" Worker_Type mark, missing-worker-type badge, block-level missing-worker-type warning (with its count placeholder), "Assign worker type" action, project create form hint of Requirement 26 criterion 7, Team_Tab confirmation dialog, Readiness_Widget `team` gate name and status, success message, and error message introduced by this spec, where each value in both files is a non-blank string that differs from its own key and contains the same set of interpolation placeholders as its counterpart in the other file. No `PROJECT_TEAM` resource name is seeded or localized, since the spec reuses `PROJECT_MEMBERS` (D1).
2. THE backend message bundles SHALL contain a non-blank Polish text and a non-blank Russian text for each message code introduced or used by this spec (`error.project.member.role.not.assignable`, `error.project.member.role.mismatch`, `error.project.member.user.inactive`, `error.project.member.last.manager`, `error.project.member.last.client`, `error.project.member.role.not.allowed.at.creation`, `error.project.member.worker.type.invalid`, `error.project.member.worker.type.not.allowed`, `error.project.member.tag.invalid`, `error.worker.nip.invalid`, `error.worker.email.required`, `error.worker.invite.not.allowed`, `error.worker.already.active`, `error.project.team.locked`, and `error.worker.type.in.use`, the last one returned by the FOR-05-06 Worker_Type delete path per Requirement 14 criterion 11).
3. WHILE the selected UI language is Polish or Russian, THE Team_Tab SHALL render every visible label, column header, Team_Block heading, button, tooltip, dialog text, empty-state text, success notification, and error notification in the selected language, with no raw i18n key and no raw backend message code shown to the user, on both the desktop table and the mobile card layout.
4. WHEN the Team_API returns a Team_Member_View or a Candidate, THE Team_API SHALL return each Project_Role name, Company_Role name, and Worker_Type name in Russian if the request language is Russian, and in Polish for any other or absent request language, consistent with the existing localized-name resolution of `roles` (`name_ru` / `name_pl` of `worker_types` for a Worker_Type); IF the name in the resolved language is blank, THEN THE Team_API SHALL return the name in the other supported language, and IF both are blank, THEN THE Team_API SHALL return the role or Worker_Type code.
5. IF the Team_API rejects a request with one of the message codes listed in criterion 2, THEN THE Team_API SHALL return the error message text of that code in the request language resolved by the rule of criterion 4 (Russian for a Russian request, Polish otherwise).
6. WHEN the user switches the UI language between Polish and Russian while the Team_Tab is open, THE Team_Tab SHALL display all frontend-owned texts in the newly selected language without a full page reload, and SHALL display Project_Role, Company_Role, and Worker_Type names in the newly selected language no later than the next Team_API response.
7. IF the Team_Tab receives an error whose message code has no key in the frontend locale of the selected language, THEN THE Team_Tab SHALL display the localized error message text returned by the Team_API, and IF no such text is returned, THEN THE Team_Tab SHALL display a generic localized error message in the selected language instead of the code.

### Requirement 25: Regression safety and test artifacts

**User Story:** As a QA engineer, I want existing behavior to keep working and the new behavior to be covered by repeatable tests, so that the change can be released with confidence.

#### Acceptance Criteria

1. WHEN a project is created through `POST /api/projects` with a `members[]` list of Admin_Staff_Role entries without `workerTypeId` and a client block that both satisfy Requirement 6 criteria 1 and 3 and Requirement 26 criteria 2–3, THE Project_Creation_Orchestrator SHALL persist exactly one project row, exactly one Project_Member per `members[]` entry under the user's Company_Role with Assignment_Status `ACTIVE` and no Worker_Type, and the client as one CLIENT Project_Member, and SHALL return every response field it returned before this spec, with the `clients` list of Requirement 19 as the only added field for non-CLIENT callers. A project created with an empty `members[]` and only a client block, or with neither, SHALL be created as before this spec.
2. WHEN a new client is registered through the Client_Registration_Flow for an Accessible_Project in an Editable_Status, THE Client_Registration_Flow SHALL create exactly one CLIENT user with status `INVITED`, send the existing FOR-03-05 client-portal invitation email (OTP) once, and create exactly one CLIENT Project_Member for that project, as it did before this spec.
3. THE FOR-03-08 test cases TC-RP-03 and REG-03 SHALL be updated to assert that every `ProjectMemberController` handler, including the new UPDATE handlers for Attribute_Update and deactivate / reactivate, resolves to `PROJECT_MEMBERS`, that a non-ADMIN role holding no `PROJECT_MEMBERS` grant (for example WORKER or CLIENT) receives an access-denied response on every endpoint, that a FOREMAN / ESTIMATOR / FINANCIER holding only READ receives access-denied on the write endpoints, and that an ADMIN caller receives a success response. Assertions that already name `PROJECT_MEMBERS` need no change.
4. THE FOR-03-08 test case REG-05 SHALL be updated to assert that the `PROJECT_MEMBERS` resource exists with exactly the Requirement 1 grants (ADMIN CRUD, MANAGER CRUD, FOREMAN READ, ESTIMATOR READ, FINANCIER READ, no WORKER or CLIENT grant) and that no `PROJECT_TEAM` resource row exists (decision D1).
5. THE spec folder SHALL contain a `test-cases.md` written in Russian, grouped by feature, in which every test case has an ID, a title, preconditions, numbered steps, an expected result per step, and the numbers of the requirements it covers, with browser scenarios for the Team_Tab and the Readiness_Widget chip, API scenarios against the Dockerized application (base URL `http://localhost:8080`) for the Team_API, and at least one test case for each of Requirements 1–24, 26, and 27, including API scenarios for the Worker_Record_Flow (PERSON and COMPANY kinds, with and without Worker_Type, with and without email, NIP validation, duplicate email), the Worker_Invitation_Flow (invite, re-send, email-required, already-active), the role-equals-Company_Role rule and the role-mismatch rejection on assign, Worker_Type assignment (at add time and later) and correction, the `workerTypeMissing` flag, Assignment_Status deactivate / reactivate including the last-ACTIVE-MANAGER and last-ACTIVE-CLIENT guards, visibility per caller role (Requirement 4 criteria 10–11 and the CLIENT members suppression of Requirement 19 criterion 9), the project-creation restrictions of Requirement 26, and tag normalization, and browser scenarios for the three Team_Blocks, the missing-worker-type warning and its "Assign worker type" action, the admin-staff-only `TeamMemberSelect`, and the tag filter.
6. THE `test-cases.md` SHALL state, for each test-case group, its repeatability strategy (per-run data generator with a run-id embedded in every created identifier, or an explicit teardown step), such that every scenario passes on two consecutive runs against the same database without manual cleanup.
7. THE `test-cases.md` SHALL end with a regression group covering criteria 1–4 and 8 of this requirement and an MD report template table with the columns test case, step, request/action, expected, actual, and status, followed by a summary line of passed, failed, and skipped counts, the run-id, and the repeatability strategy, per the workspace `test-cases` standard.
8. IF a project is created through `POST /api/projects` with a `members[]` entry under the Worker_Role, a `members[]` entry carrying a `workerTypeId`, or a `members[]` entry whose `projectRoleId` differs from the user's Company_Role, THEN THE Project_Creation_Orchestrator SHALL respond with the HTTP 400 message code of Requirement 26 criterion 2, Requirement 26 criterion 3, or Requirement 5 criterion 11 (`error.project.member.role.mismatch`) respectively and roll back the whole creation (Requirement 6 criterion 5). This is a deliberate behavior change for existing FOR-04-13 clients (D13) and SHALL be covered by a regression test case; a `members[]` entry under the Client_Role for a CLIENT-Company_Role user SHALL keep being accepted (Requirement 26 criterion 4).
9. THE automated tests of this spec SHALL include property-based tests for the "Worker_Type present ⇒ Project_Role = WORKER" invariant (Requirement 14 criterion 1), the `workerTypeMissing` consistency property (Requirement 14 criterion 16), the idempotence and round-trip of Tag_Normalization (Requirement 15 criterion 4), the Team_Block ordering of Requirement 4 criterion 6, and the last-ACTIVE-MANAGER / last-ACTIVE-CLIENT invariant over sequences of assign, remove, deactivate, and reactivate (Requirement 9 criterion 5).

### Requirement 26: Team assignment at project creation

**User Story:** As a manager creating a project, I want to assign only the admin staff and the client at creation time, so that workers are added later from the Team tab, where their worker type is handled.

#### Acceptance Criteria

1. WHEN a project is created through `POST /api/projects` and every `members[]` entry names an Admin_Staff_Role, or the Client_Role per criterion 4, equal to the named user's Company_Role, and carries an absent or null `workerTypeId`, THE Project_Creation_Orchestrator SHALL assign each entry through the Team_Service in submitted order under the user's Company_Role with Assignment_Status `ACTIVE` and no Worker_Type, subject to Requirement 6 criteria 1 and 3, and SHALL then process the client block as before this spec (D13). An absent, null, or empty `members[]` SHALL be accepted and SHALL produce no `members[]` Project_Member. The `projectRoleId` of each `members[]` entry SHALL stay mandatory, as before this spec, and SHALL equal the named user's Company_Role (otherwise Requirement 5 criterion 11 applies).
2. IF a `members[]` entry names the Worker_Role, THEN THE Project_Creation_Orchestrator SHALL respond with HTTP 400 and message code `error.project.member.role.not.allowed.at.creation` and roll back the whole creation per Requirement 6 criterion 5, leaving no new project row, no new Project_Member, no new client user, no Audit_Log row of Requirement 17, no notification of Requirement 18, and no invitation email. This SHALL apply regardless of the Company_Role of the named user and regardless of whether the entry carries a `workerTypeId`.
3. IF a `members[]` entry carries a non-null integer `workerTypeId`, whatever its Project_Role and whether or not the value matches an existing or Active_Worker_Type, THEN THE Project_Creation_Orchestrator SHALL respond with HTTP 400 and message code `error.project.member.worker.type.not.allowed` and roll back the whole creation as in criterion 2, without looking up the Worker_Type. The `members[]` entry contract SHALL accept the `workerTypeId` property so that this rejection is applied instead of silently ignoring the value.
4. WHEN a `members[]` entry names the Client_Role for a user whose Company_Role at request time is CLIENT, THE Project_Creation_Orchestrator SHALL accept the entry as before this spec, subject to Requirement 6 criteria 3; IF a `members[]` entry names the Client_Role for a user whose Company_Role at request time is not CLIENT, THEN THE Project_Creation_Orchestrator SHALL respond with HTTP 400 and message code `error.project.member.role.mismatch` (Requirement 5 criterion 11) and roll back the whole creation as in criterion 2.
5. IF the creation request fails more than one check, THEN THE Project_Creation_Orchestrator SHALL return only one error, chosen as follows: first, field validation of the whole request, including a null or non-positive `userId` or `projectRoleId` in any `members[]` entry and a `projectRoleId` that differs from the user's Company_Role (`error.project.member.role.mismatch`, step 3 of Requirement 3 criterion 7); otherwise, the error of the first failing `members[]` entry in submitted order, and for that entry only its first failing check in the order: duplicate membership (criterion 9), user existence (step 7), then the step-8 sub-order of Requirement 3 criterion 7 (Requirement 6 criterion 1, then criterion 2 of this requirement, then Requirement 6 criterion 3, then criterion 3 of this requirement); and only when every `members[]` entry passes, the error of the client block.
6. THE FOR-04-13 `TeamMemberSelect` of the project create form SHALL offer only users whose Company_Role, as returned by the user list at the time the options are loaded, is an Admin_Staff_Role (`MANAGER`, `FOREMAN`, `ESTIMATOR`, `FINANCIER`), so that users with Company_Role `ADMIN`, `WORKER`, `CLIENT`, or a non-system role are not offered, and SHALL keep assigning each picked user under the user's Company_Role, shown and not chosen, as before this spec. The search, infinite scroll, and selection behavior SHALL otherwise stay unchanged (Q11, resolved by D13).
7. THE project create form SHALL contain no Worker_Type field and no worker selection, and SHALL show, inside the team-member field area and visible without any user interaction, a hint stating that workers are added from the project's Team tab after the project is created, rendered in Polish when the UI language is pl and in Russian when the UI language is ru.
8. IF the Project_Creation_Orchestrator rejects a creation with `error.project.member.role.not.allowed.at.creation`, `error.project.member.worker.type.not.allowed`, `error.project.member.role.not.assignable`, `error.project.member.role.mismatch`, `error.project.member.user.inactive`, `error.project.member.duplicate`, or `error.entity.not.found`, THEN THE project create form SHALL stay open with every entered value retained (project fields, picked team members, and client block), SHALL show the localized pl or ru message of the returned code with no raw message code shown, and SHALL create no project in the projects list.
9. IF the same `userId` appears in more than one `members[]` entry, or appears both in `members[]` and as the existing user of the client block, THEN THE Project_Creation_Orchestrator SHALL respond with HTTP 409 and message code `error.project.member.duplicate` for the second occurrence in processing order (`members[]` in submitted order, then the client block) and roll back the whole creation as in criterion 2.
10. WHEN a `members[]` entry names an Admin_Staff_Role for a user whose Company_Role at request time equals that Admin_Staff_Role, THE Project_Creation_Orchestrator SHALL accept the entry subject to Requirement 6 criterion 3; IF a `members[]` entry names the `ADMIN` Project_Role or a non-system Project_Role (the user's Company_Role is `ADMIN` or a non-system role), THEN THE Project_Creation_Orchestrator SHALL respond with HTTP 400 and message code `error.project.member.role.not.assignable` (Requirement 6 criterion 1) and roll back the whole creation as in criterion 2, because an ADMIN or non-system user is managed through the admin area and cannot be a project member.
11. IF the Company_Role or the active flag of a user picked in the `TeamMemberSelect` changes between selection and submission, THEN THE Project_Creation_Orchestrator SHALL validate the submitted entry against the user's Company_Role and active flag at request time (not at selection time), returning the code of the first failing check per criterion 5 (for example `error.project.member.role.not.assignable` for a user who became `ADMIN`, `error.project.member.role.mismatch` for a submitted `projectRoleId` that no longer equals the user's Company_Role after the user became WORKER or CLIENT, or `error.project.member.user.inactive` for a deactivated user), and THE project create form SHALL handle the rejection per criterion 8.

### Requirement 27: Assignment status (deactivate and reactivate)

**User Story:** As a manager, I want to deactivate a member who has left and reactivate one who returns, so that the project's history of who worked on it is preserved while the active crew reflects reality.

#### Acceptance Criteria

1. THE Team_Service SHALL store an Assignment_Status of `ACTIVE` or `INACTIVE` on every Project_Member, defaulting to `ACTIVE` when the member is assigned (Requirement 5 criterion 1, the Project_Creation_Orchestrator, the Client_Registration_Flow, and the Worker_Record_Flow). THE Team_Service SHALL store the Assignment_Status in a non-nullable column of `project_members` added by a new Liquibase changeset, registered last in `database_files/changelog.xml` and guarded by `onFail="MARK_RAN"` preconditions, with every Project_Member existing before the changeset backfilled to `ACTIVE`; a re-run SHALL change no schema and no data.
2. WHEN a caller holding `PROJECT_MEMBERS` UPDATE deactivates an existing `ACTIVE` Project_Member, identified by the `(userId, projectId)` pair, of an Accessible_Project in an Editable_Status, and the deactivation passes the last-ACTIVE-MANAGER / last-ACTIVE-CLIENT check of Requirement 9 criteria 1–2, THE Team_Service SHALL set that member's Assignment_Status to `INACTIVE` in one transaction, write exactly one Audit_Log row per Requirement 17 criterion 2, and create the single notification of Requirement 18 criterion 2, and THE Team_API SHALL respond with HTTP 200 and the updated Team_Member_View carrying Assignment_Status `INACTIVE`. The change SHALL keep the membership id, user id, project id, Project_Role, Worker_Type, and Assignment_Tags unchanged and leave every other Project_Member unchanged. The membership and its history remain visible (soft action; no row is deleted).
3. WHEN a caller holding `PROJECT_MEMBERS` UPDATE reactivates an existing `INACTIVE` Project_Member of an Accessible_Project in an Editable_Status, THE Team_Service SHALL set that member's Assignment_Status to `ACTIVE` in one transaction, write exactly one Audit_Log row per Requirement 17 criterion 2, and create the single notification of Requirement 18 criterion 2, and THE Team_API SHALL respond with HTTP 200 and the updated Team_Member_View carrying Assignment_Status `ACTIVE`. Reactivation of a MANAGER or CLIENT member SHALL always be allowed (it only adds an ACTIVE member of that code; Requirement 9 criterion 6).
4. IF no Project_Member exists for the targeted `(userId, projectId)` pair of an existing Accessible_Project, THEN THE Team_API SHALL respond with HTTP 404 and message code `error.project.member.not.found`, and THE Team_Service SHALL modify no `project_members` row.
5. IF a deactivate / reactivate request omits the target status or supplies a status other than `ACTIVE` or `INACTIVE`, THEN THE Team_API SHALL respond with HTTP 400 before any persistence, and THE Team_Service SHALL leave the Project_Member unchanged. This check ranks at the mandatory-fields step (step 3) of the canonical order of Requirement 3 criterion 7.
6. WHEN a deactivate / reactivate request that passes canonical steps 1–6 of Requirement 3 criterion 7 names the Assignment_Status the Project_Member already has, THE Team_Service SHALL leave the Project_Member unchanged, write no Audit_Log row, and create no notification, and THE Team_API SHALL respond with HTTP 200 and the current Team_Member_View (idempotent).
7. IF a deactivation would set the only ACTIVE MANAGER or the only ACTIVE CLIENT of the project to `INACTIVE`, THEN THE Team_API SHALL respond with HTTP 409 and message code `error.project.member.last.manager` or `error.project.member.last.client` respectively (Requirement 9 criteria 1–2), and THE Team_Service SHALL leave the member `ACTIVE`, write no Audit_Log row, and create no notification.
8. WHILE a Project_Member's Assignment_Status is `INACTIVE`, THE Team_API SHALL continue to return a Team_Member_View for that member in the list of Requirement 4 (visually marked, Requirement 22 criterion 4), SHALL exclude the member from the ACTIVE counts of the Team_Readiness_Gate (Requirement 20) and from the last-ACTIVE-MANAGER / last-ACTIVE-CLIENT invariants (Requirement 9), and SHALL keep the member in its Team_Block in the order of Requirement 4 criterion 6. An INACTIVE member may still be reactivated (criterion 3) or removed (Requirement 8).
9. IF a deactivate / reactivate operation targets a project in a Locked_Status, THEN THE Team_API SHALL respond with HTTP 409 and message code `error.project.team.locked` (Requirement 10 criterion 2), and THE Team_Service SHALL leave the Assignment_Status unchanged, write no Audit_Log row, and create no notification.
10. THE Assignment_Status SHALL be visible to every reader of the Team_Member_View, including WORKER and CLIENT readers of the Team_API where they have READ access, because the Assignment_Status is not an Internal_Attribute (D12, D14). Consuming the ACTIVE status to assign members to specific execution-stage jobs and to report their progress is out of scope (FOR-06).
