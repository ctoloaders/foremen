# FOR-05-12 — Project activation (hand over to work)

## Introduction

Today a project moves from the design stage to the execution stage (`DRAFT` → `ACTIVE`) only
implicitly, via `ProjectActivationSignal.onContractSigned(...)` when a `CONTRACT_*` document is
signed and the project has an `APPROVED` offer. There is **no** explicit, user-triggered way to
hand a project over to work, and the generic `PUT /api/projects/{id}` lets any UPDATE-capable caller
set `status = ACTIVE` with **no** readiness check at all.

This feature adds an explicit, readiness-gated **activation** action:

- The design-stage **readiness widget** gains an **"Hand over to work"** action that appears only
  when overall readiness is **100%**. Pressing it activates the project (`DRAFT` → `ACTIVE`).
- An **ADMIN** or **MANAGER** may also activate a project from the **project edit form** by setting
  the status to `ACTIVE`. When they do, the backend **validates readiness**: if readiness is below
  100% it rejects the change with **HTTP 409 Conflict**, a localized "project not ready" message,
  and the **list of not-ready areas/items**.
- On a 409, the frontend surfaces the not-ready breakdown and offers a **"Hand over to work
  anyway"** (force) action; the force path re-sends the activation with a `force` flag and activates
  the project despite incomplete readiness.

The readiness gate is evaluated **only** when the target status is `ACTIVE` and the project is in a
pre-activation status; all other status changes behave exactly as they do today. The gate computes
readiness over **all** readiness areas server-side (it does not trust the per-caller, permission-
scoped aggregate), so "100%" is an authoritative, project-wide fact.

This spec builds on FOR-05-11 (project readiness aggregation + `ReadinessWidget`) and the existing
project update path; it introduces no new entities and no new database schema.

## Glossary

- **Activation** — the `DRAFT` → `ACTIVE` project status transition that hands the project over to
  the execution stage.
- **Readiness (overall)** — the equal-weight percentage over all readiness areas produced by
  `ProjectReadinessService` / `ReadinessMath`; a project is **fully ready** iff
  `overallPercentage == 100` (equivalently `overallState == DONE`).
- **Full readiness (project-wide)** — readiness computed over **every** area regardless of the
  caller's per-area READ permission (the authoritative activation gate), as opposed to the
  caller-scoped aggregate returned by `GET /api/projects/{id}/readiness`.
- **Pre-activation status** — a project status from which activation is allowed:
  `DRAFT`, `READY_TO_OFFER`, `OFFERED`, `APPROVED` (mirrors
  `ProjectActivationSignal.isEligibleForActivation`).
- **Force activation** — activation requested with an explicit `force = true` flag that bypasses the
  readiness gate (but never the access/permission gate). Available to ADMIN / MANAGER only.
- **Not-ready breakdown** — the list of areas whose state is not `DONE` (with their localized names,
  states, percentages, and outstanding items), returned in the 409 body and rendered by the client.

## Requirements

### Requirement 1 — Explicit activation endpoint

**User Story:** As an executor who prepared a project, I want an explicit action to hand the project
over to work, so that the project enters the execution stage deliberately rather than only as a side
effect of contract signing.

#### Acceptance Criteria

1. WHEN an authenticated, project-accessible caller with `PROJECTS` UPDATE invokes the activation
   action on a project in a **pre-activation** status THEN the system SHALL set the project status to
   `ACTIVE` and return the updated project.
2. WHEN the project is already `ACTIVE` (or in any later / terminal status) THEN the activation
   action SHALL be a no-op success or a clear rejection (see Requirement 6), never a silent status
   corruption.
3. WHEN a caller lacks `PROJECTS` UPDATE on the project THEN the activation action SHALL be refused
   with the standard authorization outcome (`403`), and the project status SHALL be unchanged.
4. WHEN a caller cannot access the project (non-member, non-ADMIN) THEN the activation action SHALL
   be refused with the standard project-access outcome (`404`, byte-identical for missing vs
   out-of-scope), and nothing SHALL be leaked.
5. The activation action SHALL be a **dedicated** operation (not the generic `PUT /api/projects/{id}`
   body), so the readiness gate is scoped to activation only and the generic update path is not
   overloaded.

### Requirement 2 — Readiness gate on activation

**User Story:** As a stakeholder, I want activation blocked when the project is not fully ready, so
that work never starts on an incomplete project by accident.

#### Acceptance Criteria

1. WHEN activation is requested **without** the force flag AND project-wide readiness is **below
   100%** THEN the system SHALL reject the activation with **HTTP 409 Conflict**, SHALL leave the
   status unchanged, and SHALL return the not-ready breakdown (Requirement 4).
2. WHEN activation is requested AND project-wide readiness is **100%** THEN the system SHALL activate
   the project regardless of the force flag.
3. The readiness gate SHALL compute readiness over **all** readiness areas server-side, independent
   of the caller's per-area READ permissions, so the gate reflects the true project-wide readiness
   and not a caller-scoped subset.
4. "Fully ready" SHALL be defined as `overallPercentage == 100`.
5. The readiness gate SHALL run **only** when the activation target is `ACTIVE` from a pre-activation
   status; it SHALL NOT alter the behavior of any other status change.

### Requirement 3 — Force activation (override)

**User Story:** As an ADMIN or MANAGER, I want to hand a not-fully-ready project over to work when I
judge it appropriate, so that I am not blocked by the readiness gate in exceptional cases.

#### Acceptance Criteria

1. WHEN an **ADMIN** or **MANAGER** requests activation with `force = true` THEN the system SHALL
   activate the project even if project-wide readiness is below 100%, provided the access and
   permission gates pass.
2. WHEN a caller who is **neither ADMIN nor MANAGER** requests activation with `force = true` THEN
   the system SHALL ignore/reject the force flag and apply the normal readiness gate (i.e. a
   non-ADMIN/MANAGER caller can activate **only** at 100%).
3. The force flag SHALL bypass **only** the readiness gate; it SHALL NEVER bypass the project-access
   gate (Requirement 1.4) or the `PROJECTS` UPDATE permission gate (Requirement 1.3).
4. A forced activation SHALL perform the same status transition and return the same updated-project
   shape as a gated activation.

### Requirement 4 — Not-ready conflict response

**User Story:** As a user who tried to activate a not-ready project, I want to see exactly what is
not ready, so that I can finish the outstanding work or make an informed override decision.

#### Acceptance Criteria

1. WHEN activation is rejected by the readiness gate THEN the 409 response SHALL carry a localized
   top-level message indicating the project is not ready to be handed over to work.
2. The 409 response SHALL include the **not-ready areas**: for each area whose state is not `DONE`,
   its area key, its localized area name, its state, its percentage, and its outstanding items
   (each item localized), reusing the FOR-05-11 readiness area/item shape.
3. The 409 response SHALL NOT include fully-ready (`DONE`) areas in the not-ready breakdown.
4. All human-readable text in the 409 response (top-level message, area names, item messages) SHALL
   be resolved to the request locale.

### Requirement 5 — Readiness widget "Hand over to work" action

**User Story:** As an executor viewing the readiness widget in the design stage, I want a clear
action to hand the project over to work once everything is ready, so that activation is one click
from where I track readiness.

#### Acceptance Criteria

1. WHEN overall readiness shown in the `ReadinessWidget` is **100%** AND the caller holds `PROJECTS`
   UPDATE THEN the widget SHALL show a **"Hand over to work"** action.
2. WHEN overall readiness is **below 100%** THEN the widget SHALL NOT show the "Hand over to work"
   action (the gate is enforced in the UI as well as the server).
3. WHEN the caller lacks `PROJECTS` UPDATE THEN the widget SHALL NOT show the action regardless of
   readiness.
4. WHEN the user presses "Hand over to work" THEN the client SHALL call the activation endpoint
   (without force), and on success SHALL reflect the project as `ACTIVE` (the readiness widget is a
   design-stage surface and is no longer shown once the project is in the execution stage).
5. WHEN the activation succeeds THEN the client SHALL show a localized success confirmation.

### Requirement 6 — Activation from the project edit form

**User Story:** As an ADMIN or MANAGER, I want to set a project's status to `ACTIVE` from the edit
form and be stopped (with the reason) if it is not ready, so that I keep the familiar edit flow but
cannot accidentally activate an incomplete project.

#### Acceptance Criteria

1. WHEN an ADMIN / MANAGER sets `status = ACTIVE` in the project edit form on a pre-activation
   project AND project-wide readiness is 100% THEN the project SHALL be activated.
2. WHEN they set `status = ACTIVE` AND readiness is below 100% THEN the system SHALL reject the
   change with the 409 not-ready response (Requirement 4) and leave the status unchanged.
3. The readiness gate SHALL apply on the status-change path used by the edit form **only** when the
   new status is `ACTIVE` and the current status is a pre-activation status; all other edits
   (renaming, dates, other status transitions) SHALL behave exactly as today.
4. WHEN the edit-form activation is rejected (409) THEN the client SHALL surface the not-ready
   breakdown and offer a **"Hand over to work anyway"** (force) action.
5. WHEN an ADMIN / MANAGER confirms "Hand over to work anyway" THEN the client SHALL re-send the
   activation with `force = true`, and on success SHALL reflect the project as `ACTIVE`.
6. WHEN the force re-send succeeds THEN the client SHALL show a localized success confirmation and
   close/refresh the form.

### Requirement 7 — Harden the generic update path

**User Story:** As a maintainer, I want the readiness gate to be unavoidable, so that activation
cannot slip through an unguarded code path.

#### Acceptance Criteria

1. The system SHALL ensure that a `DRAFT`/pre-activation → `ACTIVE` transition cannot be performed
   through any update path **without** going through the readiness gate (gated or explicitly forced
   by an ADMIN / MANAGER).
2. WHEN the generic `PUT /api/projects/{id}` receives a `status = ACTIVE` change from a pre-activation
   status THEN the system SHALL apply the same readiness gate and the same 409 not-ready outcome as
   the dedicated activation endpoint (so the edit form and any other PUT caller are consistent).
3. The automatic `ProjectActivationSignal.onContractSigned(...)` path (contract-signed activation)
   SHALL remain unchanged and SHALL NOT be subjected to the interactive readiness gate (it already
   has its own offer + contract gate).

### Requirement 8 — Localization (PL + RU)

**User Story:** As a Polish- or Russian-speaking user, I want every new activation message to be in
my language, so that the action and its errors are understandable.

#### Acceptance Criteria

1. Every new user-facing string (the "Hand over to work" action label, the "Hand over to work
   anyway" action label, the success confirmation, the not-ready dialog/heading, the backend
   not-ready message) SHALL have **PL and RU** values at parity (identical key sets in both
   catalogs), consistent with the existing `readiness.*` / `projects.*` i18n conventions.
2. The backend not-ready conflict message SHALL be a message code resolved via the standard
   `MessageResolver` against `messages.properties` (PL) and `messages_ru.properties` (RU).
3. The not-ready breakdown SHALL reuse the existing FOR-05-11 area-name and outstanding-item i18n
   keys (e.g. `workspace.tab.*`, `readiness.*`) rather than duplicating them.

### Requirement 9 — Notifications

This feature emits **no** notifications. Activation is a synchronous, user-initiated status change
whose outcome is shown inline to the actor (success confirmation or the 409 not-ready breakdown);
there is no separate recipient to inform at this stage. If downstream work (e.g. FOR-06) later needs
to notify the crew that a project was handed over to work, that notification will be added in that
spec via the generic notification service.

#### Acceptance Criteria

1. The activation action SHALL NOT emit any in-app, email, SMS, or Telegram notification.

## Non-goals

- No change to **how readiness is computed** per area (owned by FOR-05-11 and the per-area features).
- No new entities, columns, or database migrations.
- No change to the automatic contract-signed activation (`ProjectActivationSignal`) beyond leaving it
  intact.
- No notifications (Requirement 9).
- No new project status values; the transition remains `pre-activation → ACTIVE`.
