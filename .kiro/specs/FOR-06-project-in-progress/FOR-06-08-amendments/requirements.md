# Requirements Document

## Introduction

This spec (**FOR-06-08-amendments**) covers the **amendment (aneks) authoring** area of a project in
the execution (`ACTIVE`) stage. It owns the **domain model and the authoring wizard** that let a
MANAGER (or ADMIN/FINANCIER) accumulate **execution-stage deltas** on top of the project's already
**signed / frozen estimate** — without mutating that estimate. Three kinds of delta are in scope:

1. **Schedule change** — extend (or shorten) planned deadlines of the project schedule.
2. **Additional work** (доп работы) — add a brand-new work item to the project, with its own volume,
   room, and (usually higher) unit price.
3. **Volume change** — change the volume (quantity) of an existing estimate work item in a room, at a
   stated unit price.

All deltas accumulate inside **one open (unsigned) amendment** per project. The spec renders the
**Aneksy** execution-stage tab (owner `FOR-06`, parent `OVERVIEW.md` §"Табы стадии исполнения") inside
the FOR-05 project workspace: a standard list of amendments and the create-amendment wizard.

### Scope boundary — this spec STOPS at "ready to sign"

This spec is the **08 half** of the OVERVIEW's 08/09 split. It owns everything **up to and including a
draft amendment that is ready to be sent for signing**:

- the amendments **list**,
- the **single-open-amendment** rule (at most one unsigned amendment accumulates all changes),
- the create-amendment **wizard** (three change-type options, each rendered as an icon button),
- the **merge** rules that collapse two changes with the same subject into one (user chooses),
- **per-line deletion** and **whole-amendment deletion** while the amendment is unsigned,
- the amendment **domain model** (`EstimateAmendment` + `AmendmentLine`) with its stored price/volume/
  schedule deltas and its (not-yet-effective) `effectiveDate` field,
- the **dormant effective-date seam** in `WorkReportService.resolveClientUnitPrice` — this spec makes
  the seam read `AmendmentLine.extraUnitPrice`, but gated so it contributes **zero** until an amendment
  is both signed AND effective (which only FOR-06-09 can make true).

Everything **after** "ready to sign" is explicitly delegated to **FOR-06-09-amendment-signing**:
sending the amendment for signing through the generic `SignableDocument` engine, the signature flow,
flipping the amendment to signed, setting/reaching the effective date, applying the deltas so that
plan/fact Gantt, the materials matrix, and margin calculations reflect the new deadlines / volumes /
prices, and all amendment-lifecycle notifications. This split matches the OVERVIEW dependency graph
(`A08 amendments → A09 amendment-signing`).

### Scope boundaries (what this spec owns vs. delegates)

| Concern | Owner |
|---|---|
| `EstimateAmendment` / `AmendmentLine` domain + the open-amendment invariant | **this spec** |
| Amendments list (standard table) | **this spec** |
| Create-amendment wizard (3 change-type icon buttons) | **this spec** |
| Same-subject merge rules (schedule / volume-change / additional-work) with explicit user choice | **this spec** |
| Per-line delete + whole-amendment delete (while unsigned) | **this spec** |
| The dormant effective-date seam in `resolveClientUnitPrice` (reads `extraUnitPrice`, gated to zero until effective) | **this spec** |
| `AMENDMENTS` ABAC resource seed + role matrix | **this spec** |
| Aneksy workspace tab (list + wizard) | **this spec** |
| Sending for signing, the signature flow, flip-to-signed | FOR-06-09 (delegated) |
| Setting/reaching the effective date and **applying** the deltas (plan/fact, materials, margin) | FOR-06-09 (delegated) |
| All amendment-lifecycle notifications | FOR-06-09 (delegated) |
| The signed estimate / frozen line prices read as the amendment baseline | FOR-05-05 / FOR-05-07 / FOR-05-14 (consumed) |
| The project schedule (half-day bars) read as the schedule baseline | FOR-05-10 / FOR-05-10b (consumed) |
| The generic `SignableDocument` engine | FOR-05-08 (consumed by FOR-06-09, not by this spec) |

### Relationship to existing specs (source of truth)

- **Parent overview** (`OVERVIEW.md`) — child row 08 `FOR-06-08-amendments`:
  "`EstimateAmendment` + `AmendmentLine`: accumulation of deltas (schedule/volumes/work cost) in one
  open amendment, recompute relative to the signed estimate; storing price deltas on lines". Row 09
  `FOR-06-09-amendment-signing` owns signing + applying the deltas.
- **FOR-06-01 (work-reporting)** — `WorkReportService.resolveClientUnitPrice(EstimateLineEntity)`
  already documents the seam: the revenue unit price is the line's frozen `unitPrice` **plus the sum of
  approved amendment deltas (`AmendmentLine.extraUnitPrice`)**; the delta is currently hard-coded to
  zero with the comment that FOR-06-08 wires it. This spec introduces `AmendmentLine.extraUnitPrice`
  and makes the seam read it, gated to zero until an amendment is signed+effective.
- **FOR-06-06 (margin-matrix)** — reads `WorkReport.clientUnitPrice` **as-is**; price-before/after an
  amendment is resolved purely by **when** each report froze its `clientUnitPrice`. So this spec needs
  no change to the margin layer — only the `resolveClientUnitPrice` seam.
- **FOR-05-05 (bill-of-materials)** — `EstimateLineEntity` (`workItem`, frozen `unitPrice`, derived
  `quantity`/`valueNet`) and `EstimateLineRoomQtyEntity` (per-`(line, room)` quantity) are the
  baseline a volume-change / additional-work amendment references.
- **FOR-05-10 / FOR-05-10b (project Gantt)** — `ProjectScheduleEntity` / `ProjectScheduleBarEntity`
  (half-day `startHalfDays` / `durationHalfDays`; no stored dates — `ScheduleCalculator` derives
  calendar dates from `Project.startDate`). A schedule-change amendment records a half-day deadline
  delta; applying it is FOR-06-09.
- **FOR-05-08 (document-signing)** — the generic `SignableDocument`/`SignableDocumentType`/
  `DocumentSignature` engine. An amendment is signed via a new `AMENDMENT` document type in FOR-06-09;
  because that code does NOT start with `CONTRACT_`, it does not re-trigger project activation.
- **FOR-06-00 (execution-foundation)** — the Aneksy tab already exists as a `PlaceholderTab` in the
  realigned execution `WORKSPACE_TABS` (gated by `AMENDMENTS`/READ); this spec swaps its lazy target to
  the real list + wizard panel.
- **`entity-creation-rules`** — `AMENDMENTS` resource seed changeset, ADMIN grant + role matrix,
  class-level `@PermissionResource("AMENDMENTS")`, `ProjectScopedService.getProjectIdPath()`, startup
  annotation completeness.
- **`test-cases.md` / `tasks-parallel-waves` / `git-repo-structure` / `test-execution-rules`** —
  workspace standards applied to this spec's `tasks.md` and `test-cases.md`.

## Glossary

- **EstimateAmendment**: the per-project amendment aggregate root
  `EstimateAmendment{ project (FK), seqNo, status (Amendment_Status), effectiveDate (nullable),
   signableDocument (FK, nullable — set by FOR-06-09), note (nullable free text),
   @OneToMany lines, + audit }`. Project-scoped (`getProjectIdPath = project.id`). **At most one**
   amendment per project in a non-terminal unsigned status (the Open_Amendment invariant).
- **AmendmentLine**: one delta within an amendment. A single polymorphic row discriminated by
  `changeType` (`Change_Type`), carrying only the fields relevant to its type:
  `AmendmentLine{ amendment (FK), changeType (Change_Type), workItem (FK, nullable),
   room (FK, nullable), deltaQuantity (nullable), extraUnitPrice (nullable),
   scheduleDeltaHalfDays (nullable), workCategory (FK, nullable), description (nullable free text),
   + audit }`. See `Change_Type` for which fields each type populates.
- **Change_Type**: the amendment-line kind, an enum rendered via the Enum_Label_I18n convention:
  - `SCHEDULE_CHANGE` — a deadline delta. Populates `scheduleDeltaHalfDays` (signed half-days; positive
    = extend, negative = shorten) and optionally `workCategory` (null = whole-project schedule shift).
  - `ADDITIONAL_WORK` — a brand-new work item not on the signed estimate. Populates `workItem`, `room`,
    `deltaQuantity` (the added volume, positive), `extraUnitPrice` (the full new unit price), and
    optionally `description`.
  - `VOLUME_CHANGE` — a quantity change of an EXISTING estimate line. Populates `workItem`, `room`,
    `deltaQuantity` (signed; positive = add volume, negative = remove), and `extraUnitPrice` (the unit
    price applied to the delta volume).
- **Amendment_Status**: `OPEN` (unsigned, editable, accumulating changes) → `SENT_FOR_SIGNING`
  (handed to FOR-06-09; frozen for edits) → `SIGNED` (FOR-06-09) → `EFFECTIVE` (FOR-06-09, deltas
  apply from `effectiveDate`); plus `DELETED` (a whole unsigned amendment removed, terminal). This
  spec drives only `OPEN` and the transition **out of** `OPEN` into `SENT_FOR_SIGNING` is **owned by
  FOR-06-09**; `SIGNED`/`EFFECTIVE` are entirely FOR-06-09. This spec may set `DELETED`.
- **Open_Amendment**: the single `OPEN` amendment of a project. A project has **at most one** at a
  time (enforced by a partial-unique invariant on `(project_id) WHERE status = 'OPEN'`). All new
  changes accumulate into it; a new amendment can be created only when no `OPEN` amendment exists.
- **Amendment_Subject**: the identity a merge compares against. For `SCHEDULE_CHANGE` the subject is
  `(SCHEDULE_CHANGE, workCategory)` (two whole-project schedule changes share the subject `null`
  category; two changes to the same category's schedule share that category). For `ADDITIONAL_WORK`
  and `VOLUME_CHANGE` the subject is `(changeType, workItem, room)` — the same work item in the same
  room.
- **Merge_Conflict**: when a user adds a new change whose `Amendment_Subject` already exists on an
  open amendment line. The System does NOT silently combine; it surfaces the **existing** line and the
  **new** input and requires the user to explicitly choose how to resolve (Requirement 5).
- **Merge_Resolution**: the user's explicit choice on a `Merge_Conflict` — `REPLACE` (overwrite the
  existing line's delta with the new one), `KEEP_EXISTING` (discard the new input), or `ADD_TO`
  (combine: for schedule, sum the half-day deltas; for volume/additional-work, sum `deltaQuantity` and
  take the new `extraUnitPrice`). The set of offered resolutions depends on `changeType` (Requirement 5).
- **Schedule_Baseline**: the project's planning Gantt (`ProjectScheduleEntity` + the half-day
  `ProjectScheduleBarEntity` bars, FOR-05-10/10b) read read-only as the baseline a `SCHEDULE_CHANGE`
  delta is expressed against. This spec stores the half-day delta; FOR-06-09 applies it.
- **Estimate_Baseline**: the project's signed estimate (`EstimateEntity` + `EstimateLineEntity` with
  frozen `unitPrice`, + `EstimateLineRoomQtyEntity` per-room quantities, FOR-05-05) read read-only as
  the baseline an `ADDITIONAL_WORK` / `VOLUME_CHANGE` delta references.
- **Effective_Date_Seam**: `WorkReportService.resolveClientUnitPrice(EstimateLineEntity)` — the single
  place the client unit price for an execution work report is resolved. This spec changes it to add
  `Σ AmendmentLine.extraUnitPrice` for the line's `workItem`, **but only for amendments that are both
  `EFFECTIVE` and whose `effectiveDate` is on/before the report date**; since no amendment can reach
  `EFFECTIVE` without FOR-06-09, the seam contributes **zero** in this spec's build (behaviour
  unchanged until FOR-06-09 lands).
- **Amendments_Tab (Aneksy)**: the execution-stage workspace tab keyed `amendments`
  (route `/projects/:projectId/amendments`, labelKey `workspace.tab.amendments`, gated by
  `AMENDMENTS`/READ), realigned by FOR-06-00 as a `PlaceholderTab`; this spec swaps its lazy target to
  the real list + wizard panel.
- **Enum_Label_I18n**: the convention that every user-facing enum (`Change_Type`, `Amendment_Status`)
  is rendered through an i18n key (e.g. `amendments.changeType.ADDITIONAL_WORK`) with PL + RU parity —
  the raw enum constant is NEVER shown. `EstimateAmendment` / `AmendmentLine` carry **no** paired
  `nameRU`/`namePL` columns (they are operational records, not dictionaries); the only free text is the
  untranslated `note` / `description`.

## Requirements

### Requirement 1: Amendments list

**User Story:** As a MANAGER or FINANCIER, I want to see all amendments of the project in a standard
list, so that I know what has changed and what is pending.

#### Acceptance Criteria

1. THE System SHALL render an `Amendments_Tab` list of the project's amendments as a standard table
   with at least: sequence number (`seqNo`), status (`Amendment_Status`, localized label), number of
   change lines, effective date (if set), and created/updated metadata.
2. THE System SHALL order the list by `seqNo` (or creation order) with the most relevant amendment
   (the `OPEN` one, if present) clearly distinguishable.
3. THE System SHALL expose a **Create amendment** action that is enabled ONLY when the project has no
   `OPEN` amendment (Requirement 2); when an `OPEN` amendment already exists, the action instead opens
   that existing open amendment for continued editing.
4. THE System SHALL allow opening any amendment row to view its change lines (read model: each line's
   `changeType`, subject, delta, and — for volume/additional-work — volume, room, unit price).
5. THE list and all amendment reads SHALL be gated by the `AMENDMENTS` READ operation (Requirement 9)
   and scoped to projects the caller belongs to.

### Requirement 2: Single open (unsigned) amendment per project

**User Story:** As a MANAGER, I want all my in-flight changes to accumulate into one unsigned
amendment, so that the client signs a single consolidated aneks rather than many fragments.

#### Acceptance Criteria

1. THE System SHALL allow **at most one** `OPEN` amendment per project at any time (enforced by a
   partial unique constraint on `(project_id) WHERE status = 'OPEN'`).
2. WHEN a MANAGER/FINANCIER creates an amendment on a project with no `OPEN` amendment, THE System SHALL
   create a new `EstimateAmendment` with `status = OPEN`, the next `seqNo`, and an empty line set.
3. IF a MANAGER/FINANCIER attempts to create a second amendment while an `OPEN` one exists, THEN THE
   System SHALL NOT create a second amendment and SHALL direct the caller to the existing `OPEN`
   amendment (server rejects the create with a localized message `error.amendment.openExists`).
4. WHILE an amendment is `OPEN`, THE System SHALL allow adding, merging, and deleting change lines
   (Requirements 3–6); once it leaves `OPEN` (sent for signing — FOR-06-09), THE System SHALL reject
   any line mutation with a localized message `error.amendment.notOpen`.
5. THE System SHALL require the project to be `ACTIVE` (execution stage) and to have a signed estimate
   baseline before an amendment can be created; otherwise it rejects creation with a localized message.

### Requirement 3: Create-amendment wizard (three change-type options)

**User Story:** As a MANAGER, I want a wizard that lets me pick what kind of change I am making, so that
I can author schedule, additional-work, and volume changes from one place.

#### Acceptance Criteria

1. WHEN a MANAGER/FINANCIER triggers **Create amendment** (or **Add change** on the open amendment),
   THE System SHALL present a wizard popup offering the three `Change_Type` options, each rendered as a
   **button with an icon**:
   (a) **Change the schedule** (`SCHEDULE_CHANGE`),
   (b) **Add additional work** (`ADDITIONAL_WORK`, доп работы),
   (c) **Change the volume of an existing work item** (`VOLUME_CHANGE`).
2. WHEN the user picks **Change the schedule**, THE System SHALL collect the schedule delta: a signed
   amount (extend/shorten) expressed in the schedule's half-day granularity (surfaced to the user as
   days/weeks and converted to half-days), and an optional target (whole project vs a specific
   `workCategory`'s bar), per the `Schedule_Baseline`.
3. WHEN the user picks **Add additional work**, THE System SHALL require **full data**: the work
   (`workItem`, selectable from the catalog — a work NOT already on the estimate, or flagged as extra),
   the `room`, the `deltaQuantity` (volume, positive), and the `extraUnitPrice` (the new unit price,
   typically higher than catalog), plus an optional `description`.
4. WHEN the user picks **Change the volume**, THE System SHALL require the existing estimate line's
   `workItem` and `room` (both drawn from the `Estimate_Baseline`), the signed `deltaQuantity`
   (positive = add, negative = remove), and the `extraUnitPrice` applied to the delta volume.
5. WHEN the wizard input is valid and no `Merge_Conflict` exists (Requirement 5), THE System SHALL
   append a new `AmendmentLine` of the chosen `Change_Type` to the `OPEN` amendment (creating the open
   amendment first if none exists, per Requirement 2).
6. IF required fields for the chosen `Change_Type` are missing or invalid (e.g. `ADDITIONAL_WORK`
   without volume/room/price; `VOLUME_CHANGE` with a zero delta; a `deltaQuantity` that would drive a
   line's resulting volume negative; a non-positive `extraUnitPrice`), THEN THE System SHALL reject the
   write with a localized validation message and SHALL NOT create the line.
7. THE wizard SHALL be gated by the `AMENDMENTS` CREATE/UPDATE operations (Requirement 9).

### Requirement 4: Multiple changes accumulate in one amendment

**User Story:** As a MANAGER, I want one amendment to hold many different changes, so that a single
aneks captures everything agreed in this round.

#### Acceptance Criteria

1. THE System SHALL allow an `OPEN` amendment to contain multiple `AmendmentLine` rows of mixed
   `Change_Type`.
2. THE System SHALL keep each line independently inspectable and deletable (Requirement 6) while the
   amendment is `OPEN`.
3. THE System SHALL expose, for the open amendment, an aggregate view (count of lines by type, and —
   for cost-bearing lines — the total added value `Σ deltaQuantity × extraUnitPrice`) for the authoring
   UI; this aggregate is informational and is NOT applied to any downstream data in this spec.

### Requirement 5: Same-subject merge with explicit user choice

**User Story:** As a MANAGER, I want to be asked how to combine a new change with an existing one on the
same subject, so that I never accidentally end up with two conflicting deltas for the same thing.

#### Acceptance Criteria

1. WHEN a new change's `Amendment_Subject` matches an existing line on the `OPEN` amendment, THE System
   SHALL detect a `Merge_Conflict` and SHALL NOT silently create a second line or silently combine the
   two; it SHALL surface the **existing** line and the **new** input and require an explicit
   `Merge_Resolution`.
2. FOR `SCHEDULE_CHANGE`, the subject is `(SCHEDULE_CHANGE, workCategory)`. Example: the schedule was
   already extended by 2 weeks and the user now adds a 3-week extension for the same target — THE System
   SHALL ask the user to choose between the existing option and the new option, offering `REPLACE`
   (use 3 weeks), `KEEP_EXISTING` (keep 2 weeks), or `ADD_TO` (sum → 5 weeks).
3. FOR `ADDITIONAL_WORK` and `VOLUME_CHANGE`, the subject is `(changeType, workItem, room)`; two lines
   merge ONLY when the **work item AND room coincide**. THE System SHALL offer `REPLACE` (use the new
   volume + new price), `KEEP_EXISTING`, or `ADD_TO` (sum the `deltaQuantity` and take the new
   `extraUnitPrice`). Full data (volume, room, price) MUST be provided for the new input before a merge
   is offered (Requirement 3.3/3.4).
4. WHEN the user chooses a `Merge_Resolution`, THE System SHALL apply it to the single surviving line
   (`REPLACE`/`ADD_TO` update the existing line in place; `KEEP_EXISTING` discards the new input) so
   that at most ONE line per `Amendment_Subject` remains on the amendment.
5. IF the work item OR the room differs (for `ADDITIONAL_WORK`/`VOLUME_CHANGE`), THEN THE System SHALL
   treat the change as a **distinct** line (no conflict, no merge prompt) and simply append it.
6. THE System SHALL enforce the one-line-per-subject invariant server-side (not only in the UI): a
   direct API write that would create a duplicate-subject line is rejected with a localized message
   `error.amendment.subjectConflict` unless it carries an explicit `Merge_Resolution`.

### Requirement 6: Delete a line / delete the whole amendment (before signing)

**User Story:** As a MANAGER, I want to remove a single change or scrap the whole amendment before it is
signed, so that I can correct mistakes without consequences.

#### Acceptance Criteria

1. WHILE an amendment is `OPEN`, THE System SHALL allow deleting any single `AmendmentLine`, removing it
   from the amendment; the amendment remains `OPEN`.
2. WHILE an amendment is `OPEN`, THE System SHALL allow deleting the **whole** amendment, transitioning
   it to `DELETED` (or hard-deleting it with its lines — an implementation choice, but it MUST no longer
   count as the project's `OPEN` amendment so a fresh one can be created).
3. WHEN the last line of an `OPEN` amendment is deleted, THE System SHALL keep the (now empty) amendment
   `OPEN` (the user may add new lines or delete the amendment); THE System SHALL NOT allow sending an
   empty amendment for signing (that guard is enforced by FOR-06-09).
4. THE System SHALL reject deleting a line or the amendment once it has left `OPEN` (sent for signing /
   signed / effective) with a localized message `error.amendment.notOpen`; post-signing reversal is out
   of scope for this spec (FOR-06-09 territory).
5. Deleting a line or an amendment SHALL be gated by the `AMENDMENTS` DELETE/UPDATE operation
   (Requirement 9) and SHALL leave the `Estimate_Baseline`, `Schedule_Baseline`, work reports, and all
   downstream data **untouched** (an unsigned amendment has no effect on anything).

### Requirement 7: Deltas are stored against the signed baseline, not applied (this spec)

**User Story:** As the System, I want amendment deltas stored as a precise, reproducible diff against the
signed estimate and schedule, so that FOR-06-09 can apply them correctly once signed and effective.

#### Acceptance Criteria

1. THE System SHALL store each `AmendmentLine` as a delta **relative to the signed baseline**:
   `VOLUME_CHANGE` stores `deltaQuantity` (not an absolute new quantity) against the existing line's
   `(workItem, room)`; `ADDITIONAL_WORK` stores the full new `(workItem, room, deltaQuantity,
   extraUnitPrice)`; `SCHEDULE_CHANGE` stores `scheduleDeltaHalfDays` against the schedule bar(s).
2. THE System SHALL store `extraUnitPrice` on cost-bearing lines so the downstream revenue seam
   (`Effective_Date_Seam`) can read it per `workItem` (the field name the existing
   `WorkReportService` javadoc already anticipates).
3. THE System SHALL NOT mutate `EstimateEntity` / `EstimateLineEntity` / `EstimateLineRoomQtyEntity` /
   `ProjectScheduleBarEntity` / any `WorkReport` while an amendment is unsigned — the signed estimate
   and schedule remain the frozen source of truth; amendments are an overlay.
4. THE System SHALL carry a nullable `effectiveDate` on the amendment that is **unset** in this spec;
   only FOR-06-09 sets it (on/after signing).

### Requirement 8: Dormant effective-date revenue seam

**User Story:** As the System, I want the client-unit-price resolution to be amendment-aware but
inert until an amendment is actually signed and effective, so that FOR-06-09 can switch it on with no
change to this spec's contract.

#### Acceptance Criteria

1. THE System SHALL update `WorkReportService.resolveClientUnitPrice(EstimateLineEntity line)` to
   compute `line.unitPrice + Σ extraUnitPrice` over all `AmendmentLine`s that: (a) reference the line's
   `workItem`, (b) belong to an amendment whose `status = EFFECTIVE`, and (c) whose amendment
   `effectiveDate` is on/before the applicable report date.
2. BECAUSE no amendment can reach `EFFECTIVE` in this spec's build (that transition is owned by
   FOR-06-09), THE seam SHALL contribute **zero** delta, so the resolved price is unchanged from today
   (`line.unitPrice`) — i.e. this spec is behaviour-preserving for existing work-reporting.
3. THE System SHALL cover the seam with a unit test proving the delta is zero while no `EFFECTIVE`
   amendment exists, and (with a test-only `EFFECTIVE` fixture) that the sum is applied correctly when
   one does — documenting the contract FOR-06-09 relies on.

### Requirement 9: ABAC resource + role matrix

**User Story:** As a platform owner, I want amendments gated by a dedicated ABAC resource, so that only
the right roles read and author amendments.

#### Acceptance Criteria

1. THE System SHALL introduce a project-scoped `AMENDMENTS` ABAC resource (seed changeset +
   `@PermissionResource("AMENDMENTS")` on the controller, `ProjectScopedService.getProjectIdPath()`
   returning the path to the owning project id), per `entity-creation-rules`.
2. THE System SHALL seed the role matrix: **ADMIN** CRUD; **MANAGER** CRUD(own); **FINANCIER** CRUD(own);
   **FOREMAN** R(own); **CLIENT** no grant in this spec (the client sees the aneks through FOR-06-09 /
   FOR-09 signing surface, not through `AMENDMENTS`); WORKER no grant.
3. THE System SHALL enforce ownership (project membership) server-side via `ProjectScopedService`, so a
   caller reaches only amendments of projects they belong to.
4. THE application SHALL start cleanly (no `PermissionAnnotationValidator` failure): the `AMENDMENTS`
   controller's in-scope handlers carry matching `@PermissionOperation`s.

### Requirement 10: Aneksy workspace tab (list + wizard)

**User Story:** As a MANAGER/FINANCIER, I want an Amendments tab in the project workspace, so that I can
see amendments and author changes in one place.

#### Acceptance Criteria

1. THE System SHALL swap the `amendments` execution-stage tab (realigned by FOR-06-00 as a
   `PlaceholderTab`, keyed `amendments`, labelKey `workspace.tab.amendments`, gated by `AMENDMENTS`/
   READ) to the real panel, WITHOUT adding a parallel tab.
2. THE panel SHALL render the amendments list (Requirement 1) and the create-amendment wizard
   (Requirement 3) with the merge-conflict chooser (Requirement 5) and the delete actions
   (Requirement 6).
3. THE panel SHALL permission-gate write actions (create change, resolve merge, delete line, delete
   amendment) on the resource's CREATE/UPDATE/DELETE operations; a FOREMAN (R own) sees a read-only
   view.

### Requirement 11: Internationalization (PL + RU)

**User Story:** As a bilingual user, I want all amendment UI text and enum labels localized in Polish
and Russian, so that the feature reads naturally in either language.

#### Acceptance Criteria

1. THE System SHALL render every user-facing enum (`Change_Type`, `Amendment_Status`) through an
   `Enum_Label_I18n` key with PL + RU parity; the raw enum constant SHALL NEVER be surfaced.
2. THE System SHALL localize all wizard text (the three change-type button labels + icons, field
   labels/placeholders, the merge-conflict chooser options, delete-line / delete-amendment
   confirmations), the amendments list table headers, validation / error messages
   (`error.amendment.*`), and the tab label, in both `pl.json` and `ru.json` with full key parity
   (Polish primary, Russian secondary), verified by the locale-parity test.
3. THE `EstimateAmendment` / `AmendmentLine` entities SHALL carry **no** paired `nameRU`/`namePL`
   columns; the only free text (`note`, line `description`) is stored untranslated as entered.

### Requirement 12: Notifications — none in this spec

**User Story:** As a MANAGER, I do not need any notification while I am still drafting an amendment, so
that my bell is not noisy with in-progress edits.

#### Acceptance Criteria

1. THE System SHALL emit **no notifications** in this spec. Authoring, merging, and deleting lines of an
   unsigned amendment are private draft edits by the acting manager and have no other recipient.
2. ALL amendment-lifecycle notifications (sent-for-signing → CLIENT; signed / declined / effective →
   MANAGER + FINANCIER) are owned by **FOR-06-09-amendment-signing**, which emits them via the generic
   `NotificationService` (FOR-05-07) — this spec neither emits nor re-implements delivery.
