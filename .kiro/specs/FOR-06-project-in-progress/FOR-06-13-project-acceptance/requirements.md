# Requirements Document

## Introduction

This spec (**FOR-06-13-project-acceptance**) is the acceptance and quality-control child of the
FOR-06 "project in progress" (execution stage) parent. It delivers a module for **acceptance acts**
(developer handover, internal stage acceptance, final acceptance) used on a tablet or phone directly
on site: an inspector walks the rooms, marks every checklist position OK or Defect with comment and
photos, the system turns defects into one **remediation task for the foreman**, the foreman
acknowledges and distributes defects among project workers, workers report fixes with "after" photos,
the inspector re-checks, and the defect is closed. The whole life of a defect is stored on the
position of the act it was found on. PDF reports (client report, internal report) are generated from
the act.

The goal is to replace the current situation in which defects live in WhatsApp/Telegram, photos live
on a phone, and fix deadlines are tracked by hand.

### Product decisions (from the customer and the product owner)

- **Four act types** (`AcceptanceType`): `DEVELOPER_HANDOVER` (apartment acceptance from the
  developer), `INTERNAL_ROUGH` (internal Foremen acceptance of rough works), `INTERNAL_TILING_PAINTING`
  (internal Foremen acceptance of tiling and painting works), `FINAL` (final acceptance before
  handing the object to the client).
- **Checklist positions come from the work catalog.** Each `WorkItem` of the work catalog (FOR-04-11)
  gets a new ordered list of **checkpoints** (i18n name, optional i18n tolerance/standard text, the
  act types it applies to). The checklist of an act is built from the checkpoints of the works that
  really exist in the project's estimate (FOR-05), per room. This replaces separately maintained
  checklist templates: editing a work's checkpoints in the work catalog is how the company edits its
  checklists.
- **Inspectors** (who may create/fill an act and re-check fixes) are `ADMIN` and `MANAGER`.
- **Executors** of a defect are any **project workers** (project members with the WORKER project
  role), including workers without a system login and external contractors (a worker record without
  login).
- **Praise** is chosen from the project's workers.
- **Visibility** has three levels: internal Foremen (`INTERNAL`), foreman (`FOREMAN`), client
  (`CLIENT`). Internal comments never reach the client report.
- **Reuse over rebuild.** This spec reuses the generic notification service, the object store
  (`ImageStorage`) and the media-attachment pattern, the `PdfRenderer` seam, `ProjectScopedService` +
  ABAC, the project-member crew, room and estimate models, the dashboard dataset registry and the
  scheduled-sweep pattern. No new infrastructure is introduced.

### Scope boundaries

- **In scope:**
  - A `WorkItemCheckpoint` child list on `WorkItem`, edited through the existing work-item aggregate
    save and the work-edit page, with PL/RU names and tolerance texts.
  - The act aggregate: `Acceptance` (act) → `AcceptanceRoom` → `AcceptanceItem` (checked position,
    carrying the defect lifecycle) → `AcceptanceItemPhoto` and `AcceptanceItemEvent` (history),
    plus the `AcceptanceRemediationTask` (one general task for the foreman per act).
  - Building the proposed checklist from the estimate and the work catalog, per selected room, and
    adding/removing positions manually.
  - Fast checking UI (large OK / Defect buttons, comment, photos), photo capture from the device
    camera, defect-location markers on photos, "before" and "after" photos.
  - Defect lifecycle with seven visible statuses, foreman acknowledgement, distribution to
    executors, fix report, re-check, history.
  - Visibility levels per position data, per comment and per photo, and the foreman confirmation of
    the client report.
  - PDF reports: client report and internal report, frozen when published to the client.
  - Filters and the project-wide defect list.
  - Praise marks.
  - Eight in-app notifications via the generic notification service and an overdue sweep.
  - A project-scoped ABAC resource `ACCEPTANCES`, a dashboard dataset, the project workspace tab
    "Acceptances", PL + RU localization, own tests and `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **The client-facing portal UI** (how a CLIENT reads published acts) — FOR-09. This spec exposes
    the visibility-filtered read model and the published PDF that FOR-09 consumes.
  - **New notification delivery channels** (email, SMS, Telegram, async fan-out) — future work; this
    spec only emits through the existing in-app (DB) notification service.
  - **Offline mode / service worker sync.** Every mark is saved immediately (autosave per position)
    and retried on failure; a full offline queue is not part of this spec.
  - **Automatic sending of tasks to workers.** The foreman distributes defects manually (R8).
  - **Changing the estimate, room or work-catalog models** other than adding the checkpoint list to
    `WorkItem`.
  - **Photo editing beyond defect markers** (no cropping, filters, drawing tools other than markers).
  - **Payroll or margin effects** of defects or rework.

### Relationship to existing specs (source of truth)

- **FOR-04-11-work-catalog** — `WorkItem`, `WorkItemAggregateSaveService` /
  `PUT /api/work-items/{id}/aggregate`, the work-edit page. Checkpoints are one more optional part of
  the aggregate save (`null` = unchanged, `[]` = clear), like `relatedWorkIds`.
- **FOR-04-05-room-types / FOR-04-06-work-categories / FOR-04-14-room** — room types, work
  categories ("work type" of the act) and project rooms.
- **FOR-05-05 estimate** — `EstimateLine` (work item) and `EstimateLineRoomQty` (the rooms a work
  applies to) decide which works exist in which room.
- **FOR-05-09-team-selection** — `ProjectMemberEntity`, `AssignmentStatus.ACTIVE`, project role codes
  (MANAGER, FOREMAN, WORKER, CLIENT). Recipients and executors are read from it.
- **FOR-05-07-offer-approval Requirement 13** — the generic `NotificationService.create(recipientUserId,
  type, body, deepLink)` and the in-app Bell.
- **FOR-05-08-document-signing / FOR-06-12-client-communication** — the media pattern
  (`ImageStorage.storeObject(file, namespace)`, own type/size validation, signed download URLs,
  `deleteIfOrphan`), the `PdfRenderer` seam and PDF freeze.
- **FOR-06-07-client-payments `OverdueSweepService`** — the cron-property sweep pattern (disabled by
  default via the `"-"` sentinel, one-shot overdue notification marker).
- **FOR-06-00-execution-foundation** — the execution-stage tab slots in `WORKSPACE_TABS`.
- **FOR-10-dashboard** — `DatasetRegistry`, `DatasetCoverageTest`, `SensitiveFieldGuardTest`.
- **FOR-03-08-api-protection** — `@PermissionResource`, `@RequiresPermission`,
  `PermissionAnnotationValidator`.
- **`.kiro/steering/entity-creation-rules.md`** — resource seed + ADMIN grant changesets,
  `@PermissionResource`, `getProjectIdPath()`, dataset coverage.

## Glossary

- **Acceptance** (act) — `Acceptance{ project (projectId), type (AcceptanceType), status
  (IN_PROGRESS/COMPLETED/CLOSED), inspector (user FK, the author), title (optional free text),
  inspectedOn (date), completedAt, defaultFixDueDate (date, nullable), publishedToClientAt
  (timestamp, nullable), publishedBy (user FK, nullable) + audit }`. Project-scoped on `projectId`.
- **AcceptanceRoom** — `AcceptanceRoom{ acceptance (FK), room (FK to project room) }`, unique per
  `(acceptance, room)`. The room is a project room (FOR-04-14).
- **AcceptanceItem** — one checked position in one room of an act. Carries a **snapshot** of the
  position text (so later catalog edits never change an existing act): `nameRu`, `namePl`,
  `toleranceRu`, `tolerancePl` (for catalog-derived positions), or `customName` (for manually
  added positions); `workCategory` (FK, nullable), `workItem` (FK, nullable), `checkpoint` (FK,
  nullable, `ON DELETE SET NULL`), `sortOrder`, `status` (Item_Status), `comment` (text, nullable),
  `commentVisibility`, `assignee` (user FK, nullable), `dueDate` (date, nullable), `praisedWorker`
  (user FK, nullable), `praiseComment`, `fixedAt`, `closedAt`, `overdueNotifiedAt`, + audit.
- **AcceptanceItemPhoto** — `AcceptanceItemPhoto{ item (FK, ON DELETE CASCADE), phase
  (BEFORE/AFTER), visibility, fileName, contentType, sizeBytes, storageUri (object key), markers
  (JSON, nullable), uploadedBy (user FK) }`. Metadata only; bytes live in the object store.
- **AcceptanceItemEvent** — append-only history row `AcceptanceItemEvent{ item (FK, ON DELETE
  CASCADE), type (Event_Type), actor (user FK), onBehalfOf (user FK, nullable), fromStatus,
  toStatus, text (nullable), visibility, createdDate }`.
- **AcceptanceRemediationTask** — the one general task for the foreman per act:
  `{ acceptance (FK, unique), status (PENDING_ACK/ACKNOWLEDGED/DONE), acknowledgedBy (user FK,
  nullable), acknowledgedAt, dueDate (nullable) + audit }`.
- **WorkItemCheckpoint** — `WorkItemCheckpoint{ workItem (FK, ON DELETE CASCADE), nameRu, namePl
  (not null), toleranceRu, tolerancePl (nullable), acceptanceTypes (non-empty set of
  AcceptanceType), sortOrder, active }`. Lives in the work catalog.
- **Item_Status** (persisted lifecycle): `NOT_CHECKED`, `ACCEPTED`, `DEFECT`, `IN_PROGRESS`,
  `AWAITING_RECHECK`, `CLOSED`.
- **Effective_Status** (what the UI shows and filters on): the persisted status, except `OVERDUE`
  replaces `DEFECT`/`IN_PROGRESS` when `dueDate` is before today (derived on read, R9).
- **Visibility_Level** — `INTERNAL` (Foremen office staff only), `FOREMAN` (also foreman and assigned
  executors), `CLIENT` (also the client). A reader sees data whose level reaches their audience: staff see all
  levels, a foreman-audience reader sees `FOREMAN` and `CLIENT`, a client sees only `CLIENT` (R10).
- **Inspector** — a user with project-scoped create/update access on acts whose company role is
  `ADMIN` or `MANAGER`.
- **Foreman** — an ACTIVE project member with project role `FOREMAN`.
- **Executor** — a project member with project role `WORKER` (any `WorkerKind`, with or without a
  login).
- **Notification_Service** — the generic in-app notification service (FOR-05-07 R13); this spec
  only emits through it.

## Requirements

### Requirement 1 — Checkpoints in the work catalog

**User Story:** As a company administrator, I want to define checklist checkpoints on each work in
the work catalog, so that acceptance checklists are built automatically from the works of a project
and I can edit them in one place.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a child entity `WorkItemCheckpoint` of `WorkItem` extending `BaseEntity`
   with fields `workItem` (FK, not null, `ON DELETE CASCADE`), `nameRu` and `namePl` (not null,
   non-blank, max 255), `toleranceRu` and `tolerancePl` (nullable text, max 1000), `acceptanceTypes`
   (non-empty set of `AcceptanceType`, persisted in a join table), `sortOrder` (int, not null) and
   `active` (boolean, default true), created via an idempotent Liquibase changeset registered last in
   `changelog.xml`.
2. WHEN a checkpoint is saved with a blank `nameRu` or `namePl` THEN the write SHALL be rejected with
   a validation error naming the field, changing nothing.
3. WHEN a checkpoint is saved with an empty `acceptanceTypes` set THEN the write SHALL be rejected
   with `error.workItemCheckpoint.types.required`.
4. THE `WorkItemAggregateSaveRequest` SHALL accept an optional `checkpoints` list as a full
   replacement of the work's checkpoints (`null` = unchanged, `[]` = clear); the replacement SHALL be
   applied in the same transaction as the other aggregate parts and SHALL preserve the ids of
   checkpoints that are kept (matched by `id`) so existing act items keep their `checkpoint` link.
5. THE work-edit page SHALL show a "Checkpoints" section where the user can add, edit, reorder,
   deactivate and delete checkpoints, edit PL and RU name and tolerance, and pick the act types each
   checkpoint applies to, staged like the other aggregate sections (draft + single save).
6. WHEN a checkpoint is deleted THEN act items referencing it SHALL keep their snapshot text and
   their `checkpoint` link SHALL become null (`ON DELETE SET NULL`).
7. THE checkpoint editing SHALL be governed by the existing `WORK_CATALOG` resource permissions; no
   new resource SHALL be introduced for it.
8. THE work-catalog read model (`WorkItemDtoExtendedModel`) SHALL expose the checkpoint list and a
   checkpoint count so the catalog list can show which works have checklists.

### Requirement 2 — Acceptance act model and persistence

**User Story:** As the system, I want acts, rooms, positions, photos, history and the remediation
task persisted with snapshots, so that an act stays reproducible after the catalog changes.

#### Acceptance Criteria

1. THE SYSTEM SHALL define the entities `Acceptance`, `AcceptanceRoom`, `AcceptanceItem`,
   `AcceptanceItemPhoto`, `AcceptanceItemEvent` and `AcceptanceRemediationTask` as described in the
   Glossary, each extending `BaseEntity` (audit columns), with FKs and `ON DELETE CASCADE` from
   children to the act/item, created via idempotent Liquibase changesets (`NOT tableExists` +
   `onFail="MARK_RAN"`) registered last in `changelog.xml`.
2. THE SYSTEM SHALL define `AcceptanceType` as the enum `DEVELOPER_HANDOVER`, `INTERNAL_ROUGH`,
   `INTERNAL_TILING_PAINTING`, `FINAL`; `Acceptance.status` as `IN_PROGRESS`, `COMPLETED`, `CLOSED`;
   and `Item_Status` as `NOT_CHECKED`, `ACCEPTED`, `DEFECT`, `IN_PROGRESS`, `AWAITING_RECHECK`,
   `CLOSED`. Status names SHALL be stored as codes; their PL/RU names live in the frontend.
3. THE `Acceptance` entity SHALL be project-scoped; its service SHALL implement
   `ProjectScopedService` with `getProjectIdPath()` returning `"projectId"`.
4. WHEN an item is created THEN the system SHALL snapshot the position text from the checkpoint
   (`nameRu`, `namePl`, `toleranceRu`, `tolerancePl`) and the work category and work item, so a later
   edit or deletion of the checkpoint never changes the act. A manually added position SHALL store
   only `customName` (free text entered in the inspector's language, shown as-is in both locales).
5. THE structure of an act SHALL be Project → Acceptance → Room → Work type (work category) →
   Positions; the work-type grouping SHALL be derived from `AcceptanceItem.workCategory` (items
   without a category fall into an "Other" group).
6. THE SYSTEM SHALL store every item's status transition as an `AcceptanceItemEvent` (R11); events
   are append-only and SHALL NOT be updated or deleted through the API.
7. WHEN an act is deleted (only allowed while `IN_PROGRESS` and by an inspector, R3.6) THEN its rooms,
   items, photos, events and task SHALL be discarded and the photo objects SHALL be reclaimed from the
   object store via `ImageStorage.deleteIfOrphan`.

### Requirement 3 — Creating an act and building the checklist from the estimate

**User Story:** As an inspector, I want to create an act, choose its type and rooms, and get the
checklist proposed automatically from the project's estimate, so that I do not build it by hand for
each object.

#### Acceptance Criteria

1. WHEN an inspector creates an act THEN the request SHALL carry the project, the `AcceptanceType`,
   the selected project rooms (at least one) and an optional title; the server SHALL persist the act
   as `IN_PROGRESS` with the caller as inspector and today as `inspectedOn`.
2. FOR the types `INTERNAL_ROUGH`, `INTERNAL_TILING_PAINTING` and `FINAL` THE SYSTEM SHALL propose,
   for each selected room, the active checkpoints that (a) belong to a work item present on an
   estimate line of the project's current estimate with a positive quantity in that room
   (`EstimateLineRoomQty`), and (b) list the act's type in their `acceptanceTypes`.
3. FOR the type `DEVELOPER_HANDOVER` THE SYSTEM SHALL propose, for each selected room, the active
   checkpoints listing `DEVELOPER_HANDOVER` whose work item is active and is attached to the room's
   room type (or has no room-type attachment, which means all rooms, FOR-05-05 R10.3), independent of
   the estimate.
4. THE proposed positions SHALL be ordered by work category, then work item, then checkpoint
   `sortOrder`, and SHALL be created as items with status `NOT_CHECKED`.
5. WHEN no checkpoint matches a room THEN the room SHALL still be added to the act with no proposed
   positions, and the response SHALL tell the inspector the room has an empty checklist.
6. WHILE an act is `IN_PROGRESS` THE inspector SHALL be able to add a room, remove a room, add a
   position manually (from any active checkpoint of the catalog or as a custom text), remove a
   `NOT_CHECKED` position, and re-run the proposal ("refresh from estimate"), which SHALL add only
   positions that are not already present for the room (matched by `checkpoint` id) and SHALL never
   remove or change a position that already has a result. An act SHALL be deletable only while
   `IN_PROGRESS` and when no item has been checked.
7. WHEN the project has no estimate and the type is not `DEVELOPER_HANDOVER` THEN the creation SHALL
   still succeed with empty room checklists, so positions can be added manually.
8. WHEN an act is created on a project that is not `ACTIVE` THEN the write SHALL be rejected with
   `error.acceptance.project.notActive`, changing nothing.
9. THE SYSTEM SHALL allow several acts of the same type on one project (e.g. repeated final
   acceptance), each independent.

### Requirement 4 — Fast checking on site

**User Story:** As an inspector on a tablet or phone, I want to pass through the whole apartment with
two large buttons per position and add comment and photos in place, so that I do not open many
windows.

#### Acceptance Criteria

1. THE act screen SHALL show, per room, the positions grouped by work type; each position row SHALL
   show the position name, the tolerance text under it (in the user's locale; `customName` as-is),
   and two large touch targets (at least 48×48 CSS px): "✓ OK" and "✕ Defect", plus the current
   status badge.
2. WHEN the inspector taps "✓ OK" THEN the item SHALL move to `ACCEPTED` and the change SHALL be
   saved immediately (autosave per position); the screen SHALL stay on the same list without
   navigation.
3. WHEN the inspector taps "✕ Defect" THEN an inline panel SHALL open under the position with the
   comment field, the "take photo / choose photo" control, the visibility selector, and (collapsed)
   the assignee and due-date fields; the item SHALL move to `DEFECT` on first save.
4. WHEN an item is marked `DEFECT` THEN a comment OR at least one photo SHALL be required before the
   act can be completed (R7.2); the UI SHALL show the position as incomplete until then.
5. WHILE an act is `IN_PROGRESS` THE inspector SHALL be able to change a mark (OK ⇄ Defect ⇄ not
   checked) and every change SHALL be stored as an `AcceptanceItemEvent`.
6. WHEN a position is `ACCEPTED` THE inspector SHALL be able to attach a **praise** (R12) without
   opening another screen.
7. THE act screen SHALL show progress per room and for the act (checked / total, defects count) and
   SHALL allow jumping to the next unchecked position.
8. IF a save fails (network error) THEN the UI SHALL keep the user's input on screen, mark the
   position "not saved", and retry on demand and automatically when the connection returns; a failed
   save SHALL NOT silently lose a mark.
9. THE UI SHALL be usable at tablet and phone widths and SHALL keep every text legible on site
   (minimum 16 px body text for position names, high-contrast status badges).

### Requirement 5 — Photos

**User Story:** As an inspector, I want photos attached to the exact position of the act, taken
directly with the device camera, with the defect location marked, so that the defect is unambiguous
and the "before" and "after" evidence sits on the same position.

#### Acceptance Criteria

1. THE SYSTEM SHALL attach photos to an `AcceptanceItem` (never only to the act); an item SHALL be
   able to hold several photos. Each photo SHALL carry a `phase`: `BEFORE` (taken during checking) or
   `AFTER` (taken when the fix is reported or re-checked).
2. THE photo control SHALL offer capturing directly from the device camera (HTML file input with
   `accept="image/*"` and `capture`) and choosing from the gallery, and SHALL allow selecting several
   files at once.
3. THE SYSTEM SHALL store the bytes through `ImageStorage.storeObject(file, "acceptances")` and only
   metadata in `AcceptanceItemPhoto`; it SHALL validate the content type (allow-list `image/png`,
   `image/jpeg`, `image/webp`) and the size (max 10 MB per photo) itself, rejecting with
   `error.acceptance.photo.invalid` / `error.acceptance.photo.tooLarge` and storing nothing. The
   frontend SHOULD downscale large camera images before upload so the limit is rarely reached.
4. THE SYSTEM SHALL serve photos only through short-lived signed download URLs from the private
   bucket, never through a public link.
5. WHEN the inspector marks the defect location THEN the photo SHALL store `markers`: a JSON array
   (max 20) of `{x, y, kind}` where `x` and `y` are normalized coordinates in [0,1] and `kind` is a
   marker style (`CIRCLE`, `ARROW`); the original image SHALL remain unchanged, and the UI and the
   PDF SHALL draw the markers over it.
6. WHEN a photo is added or removed THEN an `AcceptanceItemEvent` (`PHOTO_ADDED` / `PHOTO_REMOVED`)
   SHALL be stored. A photo SHALL be removable only by its uploader or an inspector, and only while
   the item is not `CLOSED`; the object SHALL be reclaimed via `deleteIfOrphan`.
7. WHEN a fix is reported (R9.2) THEN at least one `AFTER` photo SHALL be required.
8. THE SYSTEM SHALL keep each photo's `visibility` (R10) and SHALL NOT serve a photo to a reader
   whose audience level is narrower than the photo's level.

### Requirement 6 — Statuses and their display

**User Story:** As any user of the module, I want statuses that are easy to tell apart at a glance,
so that I can see the state of every position without reading details.

#### Acceptance Criteria

1. THE SYSTEM SHALL expose these seven statuses to the user: **Not checked** (`NOT_CHECKED`),
   **Accepted** (`ACCEPTED`), **Defect** (`DEFECT`), **In progress** (`IN_PROGRESS`), **Fixed,
   awaiting re-check** (`AWAITING_RECHECK`), **Closed** (`CLOSED`), **Overdue** (`OVERDUE`,
   effective status only).
2. THE SYSTEM SHALL derive `Effective_Status` as `OVERDUE` WHEN the persisted status is `DEFECT` or
   `IN_PROGRESS` AND `dueDate` is before the current date (in the application time zone); every other
   case SHALL use the persisted status. A position `AWAITING_RECHECK` SHALL NOT be shown overdue.
3. THE frontend SHALL render each status as a badge with a distinct colour **and** a distinct icon
   or text (never colour alone), with an accessible name, using one shared status-badge component
   across the act screen, the defect list, the foreman task and reports.
4. THE status badge colours SHALL come from the theme tokens already used in the app (FOR-02-08), and
   the seven badges SHALL be distinguishable in a colour-blind simulation (icon/text differ).
5. THE persisted transitions SHALL be exactly those listed in R7, R8, R9 and R10 and SHALL be
   enforced by one state machine in the service; any other transition SHALL be rejected with
   `error.acceptance.item.invalidTransition`, changing nothing.

### Requirement 7 — Completing an act and the remediation task

**User Story:** As an inspector, I want to complete the act and have the system create one general
task with all defects for the foreman, so that nothing stays in chats and the foreman gets a single
clear list.

#### Acceptance Criteria

1. WHEN the inspector completes an `IN_PROGRESS` act THEN the system SHALL validate that no item is
   `NOT_CHECKED` (the inspector removes irrelevant positions first), otherwise reject with
   `error.acceptance.complete.unchecked` listing the count.
2. THE completion SHALL require every `DEFECT` item to have a comment or at least one photo, and a
   `dueDate`; an item without a `dueDate` SHALL inherit the act's `defaultFixDueDate` supplied at
   completion, and the completion SHALL be rejected with `error.acceptance.complete.dueDateRequired`
   when neither exists.
3. WHEN the act is completed AND it has at least one `DEFECT` item THEN the system SHALL, in the same
   transaction, set the act to `COMPLETED` and create exactly one `AcceptanceRemediationTask` in
   `PENDING_ACK` listing all its defects (room, work type, defect description, photos, due date),
   and SHALL emit the notification of R13 row 1.
4. WHEN the act is completed AND it has no `DEFECT` item THEN the system SHALL set the act directly to
   `CLOSED`, create no task and emit no remediation notification.
5. AFTER completion THE inspector SHALL NOT be able to change `ACCEPTED`/`DEFECT` marks of the act's
   positions; further changes happen only through the lifecycle in R8–R10. THE inspector MAY still
   add a comment and change a defect's `dueDate` or `assignee` (each stored as an event).
6. THE act SHALL move to `CLOSED` automatically WHEN its last `DEFECT`-originated item becomes
   `CLOSED`, and the remediation task SHALL move to `DONE` in the same transaction.
7. THE project-scoped service SHALL enforce that only an inspector (`ADMIN` or `MANAGER`) can create,
   fill and complete an act.

### Requirement 8 — Foreman acknowledgement and distribution

**User Story:** As a foreman, I want to receive the list of defects, confirm that I have seen it and
hand defects over to specific workers or contractors, so that every defect has an owner.

#### Acceptance Criteria

1. WHEN a remediation task is `PENDING_ACK` THEN every ACTIVE FOREMAN member of the project (and, as
   a fallback, any ACTIVE MANAGER, and `ADMIN`) SHALL be able to open it and acknowledge it; the
   acknowledgement SHALL set `acknowledgedBy` / `acknowledgedAt`, move the task to `ACKNOWLEDGED`,
   and move every `DEFECT` item of the act to `IN_PROGRESS` in one transaction, storing an
   `ACKNOWLEDGED` event on each item.
2. WHILE the task is `PENDING_ACK` THE foreman SHALL NOT be able to distribute defects or report
   fixes; the defects keep the status `DEFECT`.
3. WHEN the foreman distributes defects THEN the request SHALL carry a set of item ids and one
   executor; the system SHALL validate that the executor is a project member with project role
   `WORKER` (ACTIVE assignment) and that every item belongs to the act, then set `assignee` on all of
   them in one transaction and store an `ASSIGNED` event for each. A worker record without a system
   login and an external contractor are valid executors.
4. THE foreman SHALL be able to reassign a defect to another executor or clear the assignee while the
   defect is `IN_PROGRESS`; each change SHALL be stored as an event.
5. THE foreman SHALL be able to see, per executor, the list of defects assigned to them, and the
   unassigned remainder, and SHALL be able to filter the task view by room, work type and executor.
6. WHEN an item is assigned to an executor who has a system login THEN the assignee notification of
   R13 row 3 SHALL be emitted; an executor without a login SHALL be skipped silently (no error).
7. WHEN the project has no ACTIVE FOREMAN member THEN the task SHALL be shown to and acknowledgeable
   by the project's ACTIVE MANAGER members, and the notification SHALL go to them.

### Requirement 9 — Fix report

**User Story:** As a foreman or an assigned executor, I want to mark a defect fixed and add photos of
the result, so that the inspector can re-check it.

#### Acceptance Criteria

1. THE following users SHALL be able to report a fix on an `IN_PROGRESS` item: the item's assignee
   (when they have a login and are a project member), an ACTIVE FOREMAN member, an ACTIVE MANAGER
   member, `ADMIN`. A user outside this set SHALL be rejected with `error.acceptance.item.forbidden`.
2. WHEN a fix is reported THEN the request SHALL carry at least one `AFTER` photo (R5.7) and an
   optional comment; the item SHALL move to `AWAITING_RECHECK`, `fixedAt` SHALL be set and a `FIXED`
   event SHALL be stored with `actor` = the caller; WHEN the foreman reports on behalf of an executor
   without a login THEN `onBehalfOf` SHALL record that executor.
3. WHEN the item moves to `AWAITING_RECHECK` THEN the notification of R13 row 4 SHALL be emitted.
4. THE fix report SHALL be possible per item and as a batch (several items with a shared comment,
   each requiring its own `AFTER` photo); the batch SHALL be all-or-nothing.
5. A WORKER with a login SHALL see and be able to act on **only** the defects assigned to them
   (R10.5); other items of the act SHALL NOT be returned to them.

### Requirement 10 — Visibility

**User Story:** As Foremen staff, I want to control who sees an act, a comment or a photo, so that
internal remarks never reach the client.

#### Acceptance Criteria

1. THE SYSTEM SHALL define the audience levels in increasing breadth: `INTERNAL` (Foremen staff:
   `ADMIN`, `MANAGER` and any other project staff role except FOREMAN/WORKER/CLIENT), `FOREMAN`
   (staff + the project's FOREMAN members + assigned executors for their own defects), `CLIENT`
   (everyone above + the project's CLIENT members).
2. EACH comment, event text and photo SHALL carry a `visibility` of `INTERNAL`, `FOREMAN` or
   `CLIENT`, defaulting to `FOREMAN`; a reader SHALL receive only data whose level reaches the
   reader's audience (a CLIENT reader gets only `CLIENT` data; a FOREMAN reader gets `FOREMAN` and
   `CLIENT`; staff get all).
3. THE position name, tolerance text, status and the existence of a defect SHALL be visible to the
   `FOREMAN` audience and above by default; they reach the CLIENT only when the act is published to
   the client (R14) and only the `CLIENT`-level comments and photos of each position are included.
4. THE `visibility` of a comment or photo SHALL be editable by an inspector at any time; each change
   SHALL be stored as an event. A FOREMAN SHALL be able to set the visibility of data they add to
   `INTERNAL` or `FOREMAN` only, never `CLIENT`; raising data to `CLIENT` is an inspector or
   confirmation action (R14.5).
5. THE read model SHALL apply the visibility filter on the server, for every read path (act view,
   defect list, task view, PDF, dashboard dataset); the frontend SHALL NOT be the only filter.
6. THE CLIENT member SHALL be able to read an act only AFTER it is published (R14.1) and only through
   the filtered view.

### Requirement 11 — Re-check and history

**User Story:** As an inspector, I want to open a fixed defect and either accept it or return it,
with the full history preserved, so that closing a defect is always a deliberate decision.

#### Acceptance Criteria

1. THE inspector (`ADMIN` or `MANAGER`) SHALL be able to open an `AWAITING_RECHECK` item and choose
   "✓ Accepted" or "✕ Not fixed"; any other user SHALL be rejected with
   `error.acceptance.item.forbidden`.
2. WHEN "✓ Accepted" is chosen THEN the item SHALL move to `CLOSED`, `closedAt` SHALL be set and the
   notification of R13 row 6 SHALL be emitted.
3. WHEN "✕ Not fixed" is chosen THEN a comment SHALL be required, the item SHALL move back to
   `IN_PROGRESS` (keeping its assignee), the inspector MAY set a new `dueDate` (otherwise the old one
   stays), and the notification of R13 row 5 SHALL be emitted; the previous `AFTER` photos SHALL be
   kept in the history.
4. THE system SHALL keep the whole history of a position in `AcceptanceItemEvent` rows from the
   moment of detection (`DETECTED`) to the closing (`CLOSED`): at least `DETECTED`, `COMMENTED`,
   `PHOTO_ADDED`, `PHOTO_REMOVED`, `DUE_DATE_CHANGED`, `ASSIGNED`, `ACKNOWLEDGED`, `FIXED`,
   `RECHECK_REJECTED`, `RECHECK_ACCEPTED` (closing), `VISIBILITY_CHANGED`, `OVERDUE`.
5. THE position detail view SHALL show the history as a timeline (who, when, what, from/to status,
   comment, photos by phase), filtered by the reader's visibility (R10), and SHALL keep `BEFORE` and
   `AFTER` photos of every cycle grouped by the event that added them.
6. THE history SHALL stay on the position and SHALL NOT be moved to the act or a separate task; a
   `CLOSED` position SHALL stay readable with its full history.
7. EVERY transition and every create/update/delete in the module SHALL also write an audit row via
   the existing audit mechanism (FOR-01-08).

### Requirement 12 — Praise

**User Story:** As an inspector, I want to mark outstanding work and name the worker, so that good
work is recognised and appears in the report.

#### Acceptance Criteria

1. WHEN an item is `ACCEPTED` THEN the inspector SHALL be able to set `praisedWorker` (chosen from the
   project's ACTIVE workers, i.e. project members with role `WORKER`; a worker without a login or an
   external contractor is allowed) and an optional `praiseComment` (free text).
2. THE praise SHALL be rejected on a position that is not `ACCEPTED`, and SHALL be cleared
   automatically WHEN the position moves away from `ACCEPTED`.
3. THE praise SHALL carry a `visibility` (default `FOREMAN`); only praise with a level reaching the
   reader's audience SHALL be shown and printed.
4. THE client report and the internal report SHALL contain a "Commendations" section listing praised
   positions with the worker name and comment (subject to visibility).
5. THE system SHALL store a `PRAISED` event for each set or change of a praise.

### Requirement 13 — Notifications

**User Story:** As a participant, I want an in-app notification when something requires my action in
an act, with a link straight to the position.

#### Acceptance Criteria

1. THE SYSTEM SHALL emit the following in-app notifications through the generic
   `NotificationService.create(recipientUserId, type, body, deepLink)`; each carries a deep-link to
   `/projects/{projectId}/acceptances/{acceptanceId}` (item-level events add
   `?item={itemId}`). No new delivery channel is introduced.

   | # | Trigger | Recipient | Type (i18n key) |
   |---|---------|-----------|-----------------|
   | 1 | Act completed with defects, task created | ACTIVE FOREMAN members (fallback: ACTIVE MANAGER members) | `notification.acceptance.defects.assigned` |
   | 2 | Foreman acknowledged the task | the act's inspector | `notification.acceptance.defects.acknowledged` |
   | 3 | Defect assigned to an executor | the executor (only if they have a login) | `notification.acceptance.defect.assigned` |
   | 4 | Defect moved to `AWAITING_RECHECK` | the act's inspector | `notification.acceptance.defect.awaitingRecheck` |
   | 5 | Re-check rejected ("Not fixed") | the executor (if any, with login) and the ACTIVE FOREMAN members | `notification.acceptance.defect.rejected` |
   | 6 | Defect closed | the executor (if any, with login) and the ACTIVE FOREMAN members | `notification.acceptance.defect.closed` |
   | 7 | Defect first becomes overdue | the executor (if any, with login) and the ACTIVE FOREMAN members | `notification.acceptance.defect.overdue` |
   | 8 | Client report published | the project's ACTIVE CLIENT members | `notification.acceptance.report.published` |

2. THE notifications SHALL be emitted from an `AFTER_COMMIT` `@TransactionalEventListener`
   (`fallbackExecution = true`), best-effort: each recipient's emission SHALL be wrapped so a failure
   is logged and swallowed, never rolls back the transition and never starves other recipients,
   mirroring `ClientMessageNotificationEmitter`.
3. WHEN no recipient of the relevant kind exists THEN nothing SHALL be emitted and this SHALL NOT be
   an error.
4. THE notification type values SHALL be pure i18n keys with PL + RU titles in the frontend; the
   message body SHALL be a short localized text naming the object, the room and the position (item
   events) or the number of defects (task events).
5. THE overdue notification (row 7) SHALL be produced by a scheduled sweep driven by the
   `foremen.acceptance.overdue-sweep.cron` property (never hard-coded; default the `"-"` disabled
   sentinel, mirroring `OverdueSweepService`). The sweep SHALL scan `DEFECT`/`IN_PROGRESS` items with
   `dueDate` before today and `overdueNotifiedAt` null, SHALL stamp `overdueNotifiedAt`, store an
   `OVERDUE` event and publish the event exactly once per item (a new `dueDate` set later by an
   inspector SHALL clear the marker, so a re-opened defect can notify again). The effective status
   `OVERDUE` itself SHALL be derived on read (R6.2) and SHALL NOT depend on the sweep running.

### Requirement 14 — Reports (PDF) and publication to the client

**User Story:** As a manager or foreman, I want a PDF report of an act for the client and an internal
one for Foremen, and I want the foreman to confirm what the client sees, so that the client gets a
reliable document.

#### Acceptance Criteria

1. THE SYSTEM SHALL generate PDF reports through the existing `PdfRenderer` seam from an HTML model,
   in two variants: `CLIENT` and `INTERNAL`, on demand ("Download PDF") for a `COMPLETED`/`CLOSED`
   act, and for an `IN_PROGRESS` act as a draft marked "Draft".
2. THE `CLIENT` report SHALL contain: the object (project name and address), the act type and date,
   the inspected positions per room and work type, the defects with their comments and photos
   (including markers), the checked-OK positions, the commendations section (R12) — only data
   reaching the `CLIENT` level (R10). THE `INTERNAL` report SHALL additionally contain executors,
   due dates, effective statuses, internal comments (all levels) and the status history summary.
3. THE report language SHALL default to the generator's UI locale and the generator SHALL be able to
   choose PL or RU; position texts SHALL use the matching `nameXx`/`toleranceXx` snapshot (custom
   positions as-is).
4. THE PDF SHALL embed photos downscaled for print (max edge ≈ 1280 px) with markers drawn over them
   and SHALL stay generatable for acts with up to 300 photos without exceeding the request timeout
   (rendering photos in page-sized rows; heavier acts MAY be generated asynchronously — see design).
5. THE foreman (an ACTIVE FOREMAN member, or `MANAGER`/`ADMIN`) SHALL be able to **confirm** the client
   report: the confirmation SHALL set `publishedToClientAt` / `publishedBy`, SHALL freeze the `CLIENT`
   PDF into the object store (like `PdfFreezeService`: hash + stored bytes) so the client always gets
   exactly the confirmed document, and SHALL emit the notification of R13 row 8. Only a `COMPLETED`
   or `CLOSED` act can be published.
6. WHEN a published act changes afterwards (a defect is closed, a visible comment is added) THEN the
   frozen PDF SHALL stay unchanged and the UI SHALL offer "Publish an updated report", which creates
   a new frozen version; older versions SHALL stay available, each with its publication time.
7. THE client read endpoints SHALL return only published acts, only the visibility-filtered content
   and the frozen PDF versions (R10.6).
8. THE internal report SHALL be available to staff and FOREMAN members; it SHALL NOT be available to
   CLIENT or WORKER users.

### Requirement 15 — Filters and the project defect list

**User Story:** As a manager or foreman, I want to filter positions and see all defects of the whole
object at once, so that I can control the fix process.

#### Acceptance Criteria

1. THE act screen and the project-wide **Defects** view SHALL offer these filters: **All**, **Only
   defects**, **By room**, **By work type**, **By executor**, **Overdue**, **Awaiting re-check**,
   **Fixed**. "Fixed" SHALL mean positions that were reported fixed at least once (`AWAITING_RECHECK`
   or `CLOSED` after a `FIXED` event). "Only defects" SHALL mean positions that ever had a defect
   status, in any lifecycle state.
2. THE filters SHALL be combinable (e.g. executor + overdue) and SHALL be applied server-side
   (query parameters), with the visibility filter (R10) always applied first.
3. THE project-wide Defects view SHALL list defects across all acts of the project with room, work
   type, status badge, executor, due date, the act it belongs to and a link to the position, sorted
   by `dueDate` ascending (overdue first), with paging.
4. THE views SHALL show counters per status for the current filter scope.
5. THE FOREMAN SHALL see all defects of the project; a WORKER with a login SHALL see only defects
   assigned to them.

### Requirement 16 — ABAC, project scoping and API surface

**User Story:** As the platform owner, I want the module protected by the permission matrix and the
project scope, so that nobody reads or changes acts outside their rights.

#### Acceptance Criteria

1. THE SYSTEM SHALL seed a project-scoped resource `ACCEPTANCES` (`code`, `name_ru`, `name_pl`,
   `description_ru`, `description_pl`) via an idempotent seed changeset registered last in
   `changelog.xml`, and an ADMIN grant for `CREATE`, `READ`, `UPDATE`, `DELETE` in a second changeSet,
   both guarded with `onFail="MARK_RAN"` preconditions (entity-creation-rules steps 1–2).
2. THE role matrix SHALL grant: `MANAGER` CRUD; `FOREMAN` `READ` and `UPDATE`; `WORKER` `READ` and
   `UPDATE`; `CLIENT` `READ`. Operation-level grants are coarse by design: the service SHALL enforce
   the finer rules of R3, R7, R8, R9, R10, R11 and R14 (who may do what on which state) regardless of
   the grant.
3. THE controller SHALL be annotated with `@PermissionResource("ACCEPTANCES")` and, because it is a
   bespoke controller, every handler SHALL also carry `@RequiresPermission`, so
   `PermissionAnnotationValidator` classifies it complete at startup.
4. EVERY endpoint SHALL apply the project-scope gate (`ProjectScopedService`) so a user outside the
   project receives the standard project-access denial.
5. THE API SHALL be exposed under `/api/acceptances` and SHALL cover: list/create/delete acts, get the
   act view (visibility-filtered), manage rooms and positions, set a mark (OK/Defect/not checked),
   edit comment/visibility/assignee/due date, photo upload/remove/markers, complete, acknowledge the
   task, distribute, report fix (single/batch), re-check, praise, filters, project defect list,
   history, PDF generate, confirm/publish, published versions.
6. THE dashboard coverage rule SHALL be satisfied: an `AcceptanceItemsDataset` SHALL be registered in
   `DatasetRegistry` for `ACCEPTANCES` (project-scoped on `acceptance.projectId`; fields: status,
   effective-status-relevant `dueDate`, act type, room, work category, assignee (owner path, workers
   restricted to their own), counts; date field `createdDate`), exposing **no** free-text comments,
   praise comments, photo storage keys or file names; `DatasetCoverageTest` and
   `SensitiveFieldGuardTest` SHALL pass.
7. THE project workspace SHALL get an execution-stage tab "Acceptances" (`acceptances`) gated by
   `ACCEPTANCES`/`READ`, added to `WORKSPACE_TABS` without changing `stageOf` /
   `resolveWorkspaceTabs`; the tab SHALL contain the acts list (create act), the act screen and the
   project Defects view.

### Requirement 17 — Localization

**User Story:** As a Polish or Russian user, I want every label of the module in my language, so
that the app is usable on site.

#### Acceptance Criteria

1. THE backend SHALL store PL/RU texts only for catalog-defined content: `WorkItemCheckpoint`
   `nameRu`/`namePl` and `toleranceRu`/`tolerancePl`, and the snapshots of the same four fields on
   `AcceptanceItem`. User-entered content (comments, defect descriptions, praise comments,
   `customName`, task comments) SHALL NOT be translated.
2. THE frontend SHALL add every new user-facing string to **both** `src/locales/pl.json` and
   `src/locales/ru.json` with real PL and RU values (no English fallback), and the locale-parity
   test SHALL pass. The strings cover at least: the "Acceptances" tab label; the four act types; the
   seven statuses; the status-badge accessible names; the buttons "✓ OK", "✕ Defect", "Fixed",
   "Accepted", "Not fixed", "Create act", "Complete act", "Acknowledge", "Distribute", "Refresh from
   estimate", "Add position", "Praise"; the visibility levels (Internal / Foreman / Client); the
   eight filters; the two report names ("Client report", "Internal report"), "Publish to client",
   "Publish an updated report", "Draft"; the checkpoints section of the work-edit page; photo
   controls ("Take photo", "Choose photo", "Before", "After", "Mark defect location"); column
   headers, placeholders, empty/loading states, validation errors and toasts.
3. THE eight `notification.acceptance.*` type keys SHALL have PL + RU titles mirrored key-for-key.
4. EVERY new backend message code (`error.acceptance.*`, `error.workItemCheckpoint.*`) SHALL have PL +
   RU messages in the backend message bundle.
5. THE PDF labels (section titles, column headers, status names, act type names, "Draft") SHALL be
   available in PL and RU through a labels class (pattern: `ProformaPdfLabels`).

### Requirement 18 — Regression, test artifacts and performance

**User Story:** As an engineer, I want the change covered by tests and not to break existing
behaviour.

#### Acceptance Criteria

1. THE existing work-catalog behaviour (aggregate save without `checkpoints`, price, formula,
   package overrides, consumptions, related works), the workspace shell and `resolveWorkspaceTabs`,
   the notification service, the object store and the PDF freeze SHALL continue to pass.
2. THE spec SHALL ship backend unit + property tests (proposal builder per type and room, snapshot
   immutability, state machine and role matrix, effective-status derivation, visibility filtering
   incl. a property that no `INTERNAL` data leaks to a CLIENT/FOREMAN reader, filters, sweep
   one-shot marker, checkpoint replace keeps ids, ABAC matrix, seed idempotency, dataset coverage),
   frontend unit tests (status badges, position row interactions, autosave failure/retry, photo
   markers, filters, work-edit checkpoints section, locale parity), and an API + UI `test-cases.md`
   in Russian per `.kiro/steering/test-cases.md`.
3. THE act view for an act with up to 20 rooms and 600 positions SHALL load in a single request
   without N+1 queries (fetch-joins/batched), and a single mark (OK/Defect) SHALL save with one
   small request.
4. THE module SHALL not require changes to existing tables other than adding the checkpoint tables
   (new tables only); the `work_items` table SHALL be unchanged.
