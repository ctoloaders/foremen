# Requirements Document

## Introduction

This spec (**FOR-06-01-work-reporting**) is the first feature child of the FOR-06 "project in
progress" (execution stage) parent. It delivers **work reporting**: the surface where a WORKER (or a
FOREMAN on their behalf) records the construction work actually performed on an `ACTIVE` project,
and where a FOREMAN reviews, approves, rejects, or returns those reports for correction. A reported
quantity of work, once approved, becomes the fact that every downstream execution surface reads:
the plan/actual Gantt (FOR-06-02), the margin matrix (FOR-06-06), and payroll (FOR-06-11).

The feature is built around the `WorkReport` + `WorkReportRoom` model already sketched in the FOR-05
parent design (§4.4), promoted here into concrete, testable requirements. A report is authored at
**per-element granularity** — a single reported row is `(worker · work item · room · quantity)` — and
a report may group several such rows (several rooms, several works) for a day or a date range in one
submission. A draft is held client-side (localStorage) until the author submits it; on submit it
enters a review cycle owned by the project's FOREMEN.

### Revenue vs. cost split (ownership boundary)

A `WorkReport` carries both a **revenue** side (what the CLIENT pays for the reported work) and a
**cost** side (what the WORKER is paid). This spec owns the **revenue** side only:

- **Revenue side (this spec).** `clientUnitPrice` = the frozen `EstimateLine.unitPrice` (FOR-05
  §4.3a) of the reported work plus the signed amendment deltas (`AmendmentLine.extraUnitPrice`,
  FOR-06-08) that apply to it; `revenueValue = clientUnitPrice × totalQuantity`. The amendment
  deltas are read from FOR-06-08 if present and treated as zero until FOR-06-08 ships.
- **Cost side (NOT this spec).** `workerUnitPrice`, `costValue`, and `margin` depend on worker cost
  rates owned by **FOR-06-10** (`WorkerServiceRate`) and are consumed by **FOR-06-11** (payroll) and
  **FOR-06-06** (margin matrix). This spec persists no worker rate and computes no cost or margin;
  it exposes only the approved quantity and the revenue valuation. A read model MAY expose a null
  cost/margin placeholder for forward compatibility, but no cost figure is computed here.

### Terminology

- **Execution stage** — a project whose `Project.status` is `ACTIVE` (reporting is open) as mapped by
  the existing `stageOf` (FOR-05-01). A `COMPLETED` project is read-only for reporting (R10).
- **Author** — the user who creates a report: a WORKER reporting their own work, or a FOREMAN
  reporting on a worker's behalf.
- **Approver** — an **ACTIVE FOREMAN** member of the project (the only role that reviews reports).
- **Report row / line** — one `(workItem, room, quantity)` tuple inside a report; persisted as a
  `WorkReportRoom`. A report has one `workItem`; the per-room split lives in its rows.
- **Reported quantity** — the quantity entered for a row, expressed in the work item's measurement
  unit, OR as a percentage of the plan quantity for that `(workItem, room)` (R2).
- **Plan quantity** — the planned quantity for a `(workItem, room)`, read from the estimate
  (`EstimateLineRoomQty.quantity`, FOR-05).

### Scope boundaries

- **In scope:**
  - The `WorkReport` + `WorkReportRoom` entities, their table/changesets, and the project-scoped
    service, persisting the revenue side of a report.
  - Per-element authoring: pick worker, work item (matched to the worker's work categories, R3),
    one or more rooms, and a reported quantity per room (absolute in the work unit, or a percentage
    of the plan quantity).
  - The report status machine `DRAFT → SUBMITTED → APPROVED / REJECTED → (resubmit)` with a
    FOREMAN review cycle (approve / reject / request changes).
  - The FOREMAN self-report fast path: a report authored by a FOREMAN is created **already
    APPROVED**, with no review step and no notification (per product decision).
  - Client-side draft persistence in localStorage (no server draft), restored on return.
  - Report media attachments (photos / videos / documents) via the FOR-12 object store.
  - The revenue valuation (`clientUnitPrice`, `revenueValue`) derived from the frozen estimate line
    price plus FOR-06-08 amendment deltas.
  - A new project-scoped ABAC resource `WORK_REPORTS` with the full entity-creation checklist, and
    the realigned `workReporting` execution tab (slot reserved by FOR-06-00) swapped to the real
    panel.
  - Four in-app notifications emitted via the generic notification service (R9).
  - PL + RU localization of every user-facing string, and the spec's own tests + `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **Worker cost, payroll, and margin** — `WorkerServiceRate`, `workerUnitPrice`, `costValue`,
    `margin` (FOR-06-10 / FOR-06-11 / FOR-06-06).
  - **The plan/actual Gantt overlay and trajectory alerts** (FOR-06-02); this spec produces the
    approved facts the overlay reads but renders no Gantt.
  - **Amendments themselves** (FOR-06-08); this spec only *reads* approved amendment deltas into the
    revenue valuation and treats them as zero until FOR-06-08 ships.
  - **Worker work-category assignment** — the worker↔work-category links (user default and
    project-member override) are owned by **FOR-05-10b** and reused unchanged for work matching (R3).
  - **New notification delivery channels** — email / SMS / Telegram and the async fan-out remain
    future work; this spec only *emits* via the existing in-app (DB) `Notification_Service`
    (FOR-05-07 Requirement 13).
  - **The object store itself** (FOR-12); this spec references it for media.
  - **Changing `stageOf` / `resolveWorkspaceTabs` / the `WORKSPACE_TABS` contract shape** — the tab
    slot and its `WORK_REPORTS` gate were reserved by FOR-06-00; this spec seeds the resource and
    swaps the tab's panel only.

### Relationship to existing specs (source of truth)

- **FOR-05 parent design §4.4** — the `WorkReport` / `WorkReportRoom` fields and the revenue/cost
  derivations; this spec implements the revenue half and defers the cost half to FOR-06-10/11.
- **FOR-05 parent design §4.3a** — `EstimateLine.unitPrice` is a **frozen snapshot**; it is the
  `clientUnitPrice` source and changes after signing only through a signed amendment (FOR-06-08).
- **FOR-06-00-execution-foundation** — reserved the `workReporting` tab on `WORK_REPORTS`/READ
  (points at `PlaceholderTab`, hidden until the resource is seeded). This spec seeds the resource
  and swaps the panel.
- **FOR-05-09-team-selection** — `ProjectMemberEntity`, the ACTIVE/INACTIVE assignment status, and
  the per-project crew; "ACTIVE FOREMAN member" and "ACTIVE WORKER member" are read from it.
- **FOR-05-10b-gantt-granularity** — `user_work_categories` and `project_member_work_categories`
  (a worker's work categories, default + project override). Work matching (R3) reuses the
  project-member work categories; this spec adds no new category link.
- **FOR-05-07-offer-approval Requirement 13** — the generic `Notification` entity, the
  `Notification_Service` "create a notification for a recipient user" API, and the in-app Bell. This
  spec is a consumer: it adds four `Notification_Type` keys and emits through that service, adding no
  new delivery channel.
- **`.kiro/steering/entity-creation-rules.md`** — `WORK_REPORTS` resource seed changeset, ADMIN
  grant + role matrix, class-level `@PermissionResource("WORK_REPORTS")`, `getProjectIdPath()`,
  startup annotation completeness.

## Glossary

- **WorkReport** — the entity `WorkReport{ project (FK), workItem (FK), author (user FK),
  worker (user FK), performedFrom (date), performedTo (date), status, approvedBy (user FK, nullable),
  approvedAt (nullable), rejectionReason (text, nullable), clientUnitPrice (frozen), totalQuantity
  (derived), revenueValue (derived), note (text, nullable), + audit }`. Project-scoped on
  `project.id`.
- **WorkReportRoom** — the entity `WorkReportRoom{ report (FK), room (FK), quantity, planQuantity
  (snapshot, nullable), inputMode (ABSOLUTE/PERCENT) }`. Project-scoped on `report.project.id`.
- **Report_Status** — one of `DRAFT` (never persisted server-side; a client-only localStorage
  state), `SUBMITTED` (awaiting approver review), `APPROVED` (accepted by an approver, or
  auto-approved for a FOREMAN author), `REJECTED` (declined), `CHANGES_REQUESTED` (returned to the
  author for correction). `APPROVED` and `REJECTED` are terminal; `CHANGES_REQUESTED` returns to the
  author who may resubmit (back to `SUBMITTED`).
- **Worker** — the `ProjectMember` whose Company_Role is WORKER and whose performed work the report
  records. The `worker` FK on a report points at this user.
- **Author** — the user who created the report (`author` FK). Equal to `worker` for a WORKER
  self-report; a FOREMAN author reports on a worker's behalf.
- **Approver** — an ACTIVE FOREMAN member of the project.
- **Covering_Work** (for a worker) — a work item whose `WorkCategory` is in the worker's
  project-member work categories (FOR-05-10b). Only Covering_Works may be reported for that worker
  (R3).
- **Reported_Quantity** — the quantity for a row, entered either ABSOLUTE (in the work item's
  measurement unit) or PERCENT (a percent of the row's Plan_Quantity, resolved to an absolute
  quantity on save).
- **Plan_Quantity** — the estimate's planned quantity for `(workItem, room)` from
  `EstimateLineRoomQty` (FOR-05); snapshotted onto the `WorkReportRoom` at save for provenance.
- **clientUnitPrice** — the frozen `EstimateLine.unitPrice` of the reported work item plus signed
  approved amendment deltas (FOR-06-08), captured on the report; the revenue unit price.
- **revenueValue** — `clientUnitPrice × totalQuantity`; the client-facing value of the reported work.
- **Notification_Service** — the generic in-app (DB) notification service (FOR-05-07 Requirement 13);
  this spec emits through it and does not re-implement delivery.

## Requirements

### Requirement 1 — WorkReport / WorkReportRoom entity and persistence

**User Story:** As the system, I want work reports persisted with a per-room quantity split and a
frozen revenue price, so approved work becomes a stable fact for downstream execution surfaces.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a project-scoped entity `WorkReport` extending `BaseEntity` with fields
   `project` (FK, not null), `workItem` (FK, not null), `author` (user FK, not null), `worker`
   (user FK, not null), `performedFrom` (date, not null), `performedTo` (date, not null, ≥
   `performedFrom`), `status` (enum, not null), `approvedBy` (user FK, nullable), `approvedAt`
   (timestamp, nullable), `rejectionReason` (text, nullable), `clientUnitPrice` (numeric, not null,
   ≥ 0), `totalQuantity` (numeric, derived), `revenueValue` (numeric, derived), and `note` (text,
   nullable).
2. THE SYSTEM SHALL define a project-scoped entity `WorkReportRoom` extending `BaseEntity` with
   fields `report` (FK, not null, `ON DELETE CASCADE`), `room` (FK, not null), `quantity` (numeric,
   not null, ≥ 0), `planQuantity` (numeric, nullable snapshot), and `inputMode` (enum
   `ABSOLUTE`/`PERCENT`, not null), unique on `(report_id, room_id)`.
3. THE SYSTEM SHALL create the `work_reports` and `work_report_rooms` tables via Liquibase
   changesets following the FK + audit-column convention, idempotent via `NOT tableExists` +
   `onFail="MARK_RAN"`, registered last in `changelog.xml`.
4. THE `totalQuantity` SHALL be derived as `Σ WorkReportRoom.quantity` for the report and SHALL
   never be hand-entered.
5. THE `revenueValue` SHALL be derived as `clientUnitPrice × totalQuantity`, rounded to two
   decimals, and SHALL never be hand-entered.
6. WHEN a report is deleted THEN its `work_report_rooms` rows SHALL be discarded (`ON DELETE
   CASCADE`).

### Requirement 2 — Per-element authoring (worker · work · room · quantity)

**User Story:** As a worker or foreman, I want to record exactly which work I did, in which rooms,
and how much, so my progress is captured precisely.

#### Acceptance Criteria

1. WHEN a report is created THEN the request SHALL carry a `workItem`, a `worker`, a
   `[performedFrom, performedTo]` date interval, and one or more rows, each a `(room, quantity,
   inputMode)` tuple.
2. WHEN a row's `inputMode` is `ABSOLUTE` THEN `quantity` SHALL be the reported amount in the work
   item's measurement unit; WHEN `inputMode` is `PERCENT` THEN `quantity` SHALL be a percent in
   `(0, 100]` of the row's Plan_Quantity, resolved on save to an absolute quantity
   `planQuantity × percent / 100`.
3. WHEN a row references a `room` for which no Plan_Quantity exists (the estimate has no
   `(workItem, room)` plan) AND `inputMode` is `PERCENT` THEN the write SHALL be rejected with
   `error.workReport.room.noPlan` naming the room; ABSOLUTE entry SHALL still be allowed and the
   `planQuantity` snapshot SHALL be null.
4. WHEN the same `room` appears in more than one row of a single report THEN the write SHALL be
   rejected with `error.workReport.room.duplicate` naming the room, changing nothing.
5. WHEN a row carries a negative, zero, or non-numeric `quantity` THEN the write SHALL be rejected
   with `error.workReport.quantity.invalid` naming the row, changing nothing.
6. WHEN a report is saved THEN each row SHALL snapshot its Plan_Quantity (nullable) for provenance,
   and the report's `totalQuantity` and `revenueValue` SHALL be (re)derived (R1.4, R1.5).

### Requirement 3 — Work matching by the worker's work categories

**User Story:** As a planner, I want a worker to report only the kinds of work they are assigned, so
reported work maps cleanly onto the plan.

#### Acceptance Criteria

1. WHEN the author picks a `worker` THEN the selectable work items SHALL be limited to Covering_Works
   — work items whose `WorkCategory` is in that worker's **project-member** work categories
   (`project_member_work_categories`, FOR-05-10b).
2. WHEN a report is submitted for a `(worker, workItem)` whose work category is NOT in the worker's
   project-member work categories THEN the write SHALL be rejected with
   `error.workReport.workItem.notCovered` naming the work item, changing nothing.
3. WHEN the worker has an empty project-member work-category list THEN no work item SHALL be
   selectable and the UI SHALL explain that the worker has no assigned work categories.
4. WHEN the reported `workItem` is not present on the project's estimate at all THEN the write SHALL
   be rejected with `error.workReport.workItem.notInEstimate` naming the work item (a report values
   only estimated work).

### Requirement 4 — Report status machine and review cycle

**User Story:** As a foreman, I want to review submitted reports and approve, reject, or return them,
so only verified work becomes fact.

#### Acceptance Criteria

1. WHEN a non-FOREMAN author submits a report THEN its status SHALL become `SUBMITTED` and it SHALL
   await approver review.
2. WHEN an approver (ACTIVE FOREMAN) approves a `SUBMITTED` or `CHANGES_REQUESTED` report THEN the
   status SHALL become `APPROVED`, `approvedBy`/`approvedAt` SHALL be set, and the revenue valuation
   SHALL be frozen (R6).
3. WHEN an approver rejects a `SUBMITTED` report THEN the status SHALL become `REJECTED` and a
   non-empty `rejectionReason` SHALL be required; a reject without a reason SHALL be refused with
   `error.workReport.rejection.reasonRequired`.
4. WHEN an approver requests changes on a `SUBMITTED` report THEN the status SHALL become
   `CHANGES_REQUESTED` with a non-empty reason, returning the report to its author for correction.
5. WHEN the author edits and resubmits a `CHANGES_REQUESTED` report THEN the status SHALL return to
   `SUBMITTED` and the review cycle SHALL repeat.
6. WHEN any actor attempts a transition not allowed from the report's current status (e.g. editing an
   `APPROVED` report, approving a `REJECTED` report) THEN the write SHALL be rejected with
   `error.workReport.status.illegalTransition`, changing nothing. `APPROVED` and `REJECTED` are
   terminal.
7. WHEN a report is in `SUBMITTED` or `CHANGES_REQUESTED` THEN only its author (edit/resubmit) and an
   approver (approve/reject/request-changes) SHALL act on it; a WORKER SHALL never approve any
   report, including their own.

### Requirement 5 — Foreman self-report fast path

**User Story:** As a foreman reporting my own work, I want my report accepted immediately, so I don't
review myself.

#### Acceptance Criteria

1. WHEN the author of a report is a FOREMAN THEN the report SHALL be created directly in `APPROVED`
   status (no `SUBMITTED` step), with `approvedBy` set to the authoring FOREMAN and `approvedAt` set
   to the creation time.
2. WHEN a FOREMAN-authored report is auto-approved THEN **no** notification SHALL be emitted (neither
   a submit notification nor an approval notification).
3. WHEN a FOREMAN reports on **another worker's** behalf (the `worker` is a different WORKER) THEN the
   fast path SHALL still apply (the FOREMAN is a trusted approver), auto-approving the report and
   emitting no submit notification; the `worker` field records whose work it is.
4. WHERE a report is auto-approved by the fast path THE revenue valuation SHALL be frozen exactly as
   in a normal approval (R6).

### Requirement 6 — Revenue valuation (frozen client price)

**User Story:** As a manager, I want the client value of reported work fixed at the estimate price
(with signed amendments), so approved facts carry a stable revenue figure.

#### Acceptance Criteria

1. WHEN a report is approved (normally or via the fast path) THEN `clientUnitPrice` SHALL be set to
   the frozen `EstimateLine.unitPrice` of the reported `workItem` on the project's estimate PLUS the
   sum of signed, approved amendment deltas (`AmendmentLine.extraUnitPrice`, FOR-06-08) that apply to
   that line.
2. WHERE FOR-06-08 has not shipped (no amendments exist) THE amendment delta SHALL be treated as
   zero, so `clientUnitPrice` equals the frozen `EstimateLine.unitPrice`.
3. WHEN `clientUnitPrice` and `totalQuantity` are known THEN `revenueValue` SHALL be
   `round2(clientUnitPrice × totalQuantity)`.
4. WHEN a report is `APPROVED` THEN its `clientUnitPrice` and `revenueValue` SHALL be immutable; a
   later estimate or amendment change SHALL NOT retroactively alter an already-approved report.
5. THE SYSTEM SHALL expose `clientUnitPrice`, `totalQuantity`, and `revenueValue` on the report read
   model; it SHALL expose no `workerUnitPrice`, `costValue`, or `margin` (those are FOR-06-10/11),
   optionally exposing them as explicit nulls for forward compatibility.

### Requirement 7 — Client-side draft (localStorage)

**User Story:** As an author, I want my in-progress report kept locally until I submit, so I don't
lose work and don't create server clutter.

#### Acceptance Criteria

1. WHILE an author is composing a report THE UI SHALL persist the in-progress form (worker, work
   item, dates, rows, note) to `localStorage`, keyed per project and author, with no server write.
2. WHEN the author returns to the reporting surface for the same project THEN the UI SHALL restore
   the last unsaved draft and offer to continue or discard it.
3. WHEN the author submits the draft THEN the server SHALL persist the report (R2) and the UI SHALL
   clear the corresponding `localStorage` draft.
4. THE SYSTEM SHALL persist **no** server-side `DRAFT` status; `DRAFT` is a client-only state and a
   report first exists server-side at `SUBMITTED` (or `APPROVED` via the fast path).

### Requirement 8 — Media attachments

**User Story:** As an author, I want to attach photos, videos, or documents to a report, so a foreman
can verify the work remotely.

#### Acceptance Criteria

1. WHEN composing or editing a report (before it is terminal) THE author SHALL be able to attach zero
   or more media files (photo / video / document) stored via the FOR-12 object store, referenced by
   the report.
2. WHEN a report is read THEN its media references SHALL be returned so the reviewer can view/download
   each attachment.
3. WHERE a report is `APPROVED` or `REJECTED` (terminal) THE media set SHALL be read-only.
4. THE media attachment visibility SHALL follow the report's `WORK_REPORTS` project-scoped access;
   media SHALL never be reachable by a user without READ on the report's project.

### Requirement 9 — Notifications

**User Story:** As a participant in the review cycle, I want to be told when a report needs my review
or when my report is decided, so the cycle moves without polling.

The feature emits via the generic `Notification_Service` (FOR-05-07 Requirement 13) only; it adds no
new delivery channel. Each notification is persisted per recipient and surfaced in the in-app Bell.

#### Acceptance Criteria

1. WHEN a non-FOREMAN author submits a report (status → `SUBMITTED`) THEN the system SHALL emit a
   notification of type `notification.workReport.submitted` to **every ACTIVE FOREMAN member** of the
   project, carrying a deep-link to that report in the Work Reporting tab.
2. WHEN an approver approves a report THEN the system SHALL emit `notification.workReport.approved` to
   the report's **author**, with a deep-link to the report.
3. WHEN an approver rejects a report THEN the system SHALL emit `notification.workReport.rejected` to
   the report's **author**, carrying the rejection reason and a deep-link to the report.
4. WHEN an approver requests changes THEN the system SHALL emit
   `notification.workReport.changesRequested` to the report's **author**, carrying the reason and a
   deep-link to the report.
5. WHEN a report is auto-approved via the FOREMAN self-report fast path (R5) THEN the system SHALL
   emit **no** notification.
6. WHERE a notification type is introduced THE type key SHALL resolve to a localized title in both PL
   and RU, and a raw type key SHALL never be shown to the user.

### Requirement 10 — Stage gating and ABAC

**User Story:** As a security owner, I want reporting available only on active projects to permitted
roles, so facts can only be recorded in the right stage by the right people.

#### Acceptance Criteria

1. WHEN a project is `ACTIVE` THEN the reporting surface SHALL be open (create / submit / review);
   WHEN a project is `COMPLETED` THEN the surface SHALL be read-only (history visible, no new reports
   or transitions); WHEN a project is `DRAFT` (design stage) THEN no reporting surface SHALL be
   reachable.
2. THE SYSTEM SHALL define a new **project-scoped** ABAC resource `WORK_REPORTS`, its backing service
   implementing `ProjectScopedService` with `getProjectIdPath()` returning the path from
   `WorkReport` to its owning project id, so LIST reads are automatically project-filtered.
3. THE controller SHALL be annotated `@PermissionResource("WORK_REPORTS")`, every in-scope handler
   SHALL carry a matching `@PermissionOperation` (or `@RequiresPermission`), and the application SHALL
   start without a `PermissionAnnotationValidator` failure.
4. THE SYSTEM SHALL seed the `WORK_REPORTS` resource row idempotently and grant, on top of the
   project-scope filter (R10.2):
   - **ADMIN** — CREATE, READ, UPDATE, DELETE (matrix completeness; the evaluator bypasses for the
     literal ADMIN role).
   - **FOREMAN** — CREATE, READ, UPDATE, DELETE over **all** workers' reports on the project
     (author on anyone's behalf, review, and remove).
   - **WORKER** — CREATE, READ, UPDATE, DELETE restricted to **own** records only
     (`report.worker = the caller's projectMember.user`); a WORKER can never read or mutate another
     worker's report (R10.5).
   - **MANAGER** — READ, UPDATE, DELETE (**no CREATE**: a manager does not author reports but may
     correct or remove them).
   - **Other admin-staff members of the project** (every project member whose Company_Role is an
     admin-staff role other than MANAGER/FOREMAN — e.g. ESTIMATOR, FINANCIER) — **READ** only.
   - **CLIENT** — no grant (deny-by-default), documented with an explanatory XML comment.
   The custom review actions (approve / reject / request-changes) map onto the UPDATE operation (a
   state transition on an existing report); the role rule that only a FOREMAN (or ADMIN) may approve
   is enforced in the service on top of the ABAC UPDATE grant (R4.7).
5. WHEN a WORKER caller reads **or writes** reports THEN the operation SHALL be restricted
   server-side to reports where they are the `worker` (own-records), for CREATE (self only), READ,
   UPDATE, and DELETE alike: a WORKER create whose `worker` is another user SHALL be rejected with
   `error.workReport.worker.notSelf`, and a WORKER read/update/delete of another worker's report
   SHALL be rejected with `error.workReport.access.notOwner`. A WORKER SHALL never approve, reject,
   or request changes on any report, including their own (R4.7).
7. WHEN a MANAGER caller issues a CREATE THEN it SHALL be rejected (a MANAGER holds no CREATE grant);
   a MANAGER READ / UPDATE / DELETE SHALL succeed over any of the project's reports, but a MANAGER
   SHALL NOT approve, reject, or request changes (those are FOREMAN/ADMIN only, R4.7).
8. WHEN a project admin-staff member other than MANAGER/FOREMAN (e.g. ESTIMATOR, FINANCIER) issues a
   write THEN it SHALL be rejected; a READ SHALL succeed. WHEN a CLIENT issues any operation THEN it
   SHALL be rejected (no grant).
6. WHEN the `WORK_REPORTS` resource is seeded THEN the FOR-06-00 `workReporting` execution tab (gated
   `WORK_REPORTS`/READ) SHALL become visible to permitted callers on an `ACTIVE` project, and this
   spec SHALL swap that tab's `lazy` from `PlaceholderTab` to the real reporting panel without
   reshaping the `WORKSPACE_TABS` contract.

### Requirement 11 — Work Reporting tab (frontend)

**User Story:** As a worker or foreman, I want a reporting tab in the project workspace, so I can
create reports and (as a foreman) review them.

#### Acceptance Criteria

1. THE SYSTEM SHALL render a Work Reporting panel on the execution-stage `workReporting` tab,
   listing the project's reports with worker, work item, period, total quantity, revenue value, and
   status, filterable by status, worker, and period.
2. THE panel SHALL provide a report builder: pick worker (R3-limited work items), add one or more
   rooms with a per-room quantity (absolute or percent-of-plan toggle), a date/period selector,
   media upload, a note, and a client-side draft (R7).
3. WHERE the caller is an approver (ACTIVE FOREMAN) THE panel SHALL expose approve / reject /
   request-changes actions on `SUBMITTED` and `CHANGES_REQUESTED` reports, each with a reason field
   where required.
4. WHERE the caller is a WORKER THE panel SHALL show only their own reports and offer create /
   edit (while non-terminal) but no review actions.
5. THE panel SHALL show each report's status with a localized badge and surface server validation
   errors (R2/R3/R4) inline.

### Requirement 12 — Internationalization completeness

#### Acceptance Criteria

1. THE backend `WorkReport` / `WorkReportRoom` entities SHALL carry **no** `nameRU`/`namePL` i18n
   columns (they are transactional, not dictionaries); every referenced dictionary name (work item,
   work category, measurement unit, room) SHALL be read from the existing entity with the
   `ru → pl → code` fallback, adding no new i18n column.
2. THE frontend SHALL add every new user-facing string — tab/page title, list columns, filters,
   status badges, builder labels/placeholders (worker, work item, room, quantity, percent/absolute
   toggle, dates, note, media), review action labels and reason fields, and all success/error
   toasts — to **both** `src/locales/pl.json` and `src/locales/ru.json` with real PL and RU values
   (no English fallback), and the locale-parity test SHALL pass.
3. THE four `notification.workReport.*` type keys SHALL have PL + RU titles mirrored key-for-key.
4. EVERY `error.workReport.*` message code SHALL have PL + RU messages in the backend message bundle.

### Requirement 13 — Audit, regression, and test artifacts

#### Acceptance Criteria

1. WHEN a report is created, submitted, approved, rejected, returned for changes, or edited THEN an
   audit row SHALL be written via the existing audit mechanism.
2. WHEN this spec ships THEN the FOR-05 / FOR-06-00 behaviors not changed here (the workspace shell,
   `resolveWorkspaceTabs`, `stageOf`, the other execution tabs' placeholders, the notification
   service) SHALL continue to pass (regression).
3. THE spec SHALL ship its own backend unit + property tests (status machine, valuation,
   work-matching, ABAC matrix, seed idempotency), frontend unit tests (builder, percent/absolute
   resolution, draft persistence, resolver fixtures), and an API-level `test-cases.md` run against
   the Dockerized app.
