# Requirements Document

## Introduction

This spec (**FOR-06-09-amendment-signing**) is the **09 half** of the OVERVIEW 08/09 amendment
split. FOR-06-08-amendments stops at an `OPEN` amendment that is **ready to be sent for signing**;
this spec owns **everything after that point**:

1. **Send for signing** — turn the project's `OPEN` amendment into a signable `AMENDMENT` document
   through the **existing generic signing engine** (FOR-05-08), link that document back to the
   amendment, and move the amendment `OPEN → SENT_FOR_SIGNING`.
2. **Signing** — drive the signature ceremony entirely through the FOR-05-08
   `SignableDocument`/`DocumentSignature` engine (reused, never re-implemented). The amendment does
   not invent its own signing lifecycle; it reflects the document's lifecycle.
3. **Signed → effective** — when the linked document reaches `SIGNED`, flip the amendment
   `SENT_FOR_SIGNING → SIGNED`, set the amendment `effectiveDate` to **the date all parties signed**
   (the document's full-sign date), and move `SIGNED → EFFECTIVE`.
4. **Apply the deltas** — once an amendment is `EFFECTIVE`, its stored deltas (FOR-06-08
   `AmendmentLine`s) take effect:
   - **Price deltas** activate automatically: FOR-06-08 already wired the dormant
     `WorkReportService.resolveClientUnitPrice` seam to sum `extraUnitPrice` over `EFFECTIVE`
     amendments whose `effectiveDate <= reportDate`. This spec makes amendments reach `EFFECTIVE`, so
     the seam starts contributing without further change to work-reporting or the margin matrix
     (FOR-06-06 reads `WorkReport.clientUnitPrice` as-is).
   - **Schedule deltas** apply as a shift of the **project end date only**: the net
     `scheduleDeltaHalfDays` of the amendment's `SCHEDULE_CHANGE` lines moves `Project.endDate`. The
     planning Gantt bars (`ProjectScheduleBarEntity`, derived from `Project.startDate`) are **not**
     changed.
   - **Volume deltas** (`VOLUME_CHANGE` / `ADDITIONAL_WORK`) take effect as the revenue/price overlay
     via the same `extraUnitPrice` seam; the signed estimate rows remain the frozen baseline
     (overlay, never mutate).
5. **Notifications** — all amendment-lifecycle notifications, emitted through the existing generic
   notification service (FOR-05-07), reusing the FOR-05-08 generic document notifications where they
   already cover the transition and adding amendment-specific ones for what they do not.

### Scope boundary — this spec STARTS at "ready to sign"

| Concern | Owner |
|---|---|
| `EstimateAmendment` / `AmendmentLine` domain, the open-amendment invariant, the create wizard, merge, line/amendment delete | FOR-06-08 (consumed) |
| The dormant `resolveClientUnitPrice` seam (reads `extraUnitPrice`, gated to zero until `EFFECTIVE`) | FOR-06-08 (consumed; this spec makes an amendment reach `EFFECTIVE` so the seam goes live) |
| The generic `SignableDocument` / `DocumentSignature` / `DocumentMedia` engine, the signing state machine, the four signing methods, the `AMENDMENT` document type, the generic document notifications | FOR-05-08 (consumed, never re-implemented) |
| **Send the open amendment for signing** (create + link the `AMENDMENT` document, `OPEN → SENT_FOR_SIGNING`) | **this spec** |
| **Reflect the document lifecycle onto the amendment** (`SENT_FOR_SIGNING → SIGNED → EFFECTIVE`, plus `SENT_FOR_SIGNING → OPEN` on decline/withdraw) | **this spec** |
| **Set the amendment `effectiveDate`** = the all-parties-signed date | **this spec** |
| **Apply the schedule delta to `Project.endDate`** | **this spec** |
| **Amendment-lifecycle notifications** | **this spec** |
| The frozen estimate / schedule read as the amendment baseline | FOR-05-05 / FOR-05-10 / FOR-05-10b (consumed) |

### Relationship to existing specs (source of truth)

- **Parent overview** (`OVERVIEW.md`) — child row 09 `FOR-06-09-amendment-signing`: "signing
  amendments through the generalized signable-document engine (type «Анекс/Aneks»), effect →
  applying deltas"; dependency edge `A08 amendments → A09 amendment-signing` and
  `S08 FOR-05-08 signable-documents -.model.-> A09`.
- **FOR-06-08 (amendments)** — provides `EstimateAmendmentEntity` (with the already-present nullable
  `effectiveDate` and `signableDocument` FK, "written only by FOR-06-09"), `AmendmentLineEntity`, the
  `AmendmentStatus` enum (`OPEN, SENT_FOR_SIGNING, SIGNED, EFFECTIVE, DELETED` — this spec drives the
  `SENT_FOR_SIGNING / SIGNED / EFFECTIVE` transitions FOR-06-08 left to it), the `AMENDMENTS` ABAC
  resource, the Aneksy tab, and the dormant `resolveClientUnitPrice` seam (FOR-06-08 R7.4, R8).
- **FOR-05-08 (document-signing)** — the generic engine consumed here: `SignableDocumentService`
  (`create`, `generate`, `requestSignatures`, `voidDocument`, `progress`), `SignatureService`
  (per-method completion, decline, provider callback), the `AMENDMENT` `SignableDocumentType` (already
  seeded, changeset `138-seed-signable-document-type-amendment`), the signing lifecycle
  `DRAFT → PENDING_SIGNATURES → SIGNED (+ VOID)`, the derived "SIGNED iff all signatures SIGNED"
  invariant (Property 16), and the generic `DocumentNotificationEmitter`
  (`notification.document.sentForSigning` / `.signedByParty` / `.fullySigned` / `.signingDeclined` /
  `.voided`, deep-linking to the signing tab). Because the `AMENDMENT` type's code does NOT start with
  `CONTRACT_`, signing an amendment does NOT trigger the FOR-05-08 project-activation hand-off.
- **FOR-06-01 (work-reporting)** — `WorkReportService.resolveClientUnitPrice` is the price seam that
  goes live when an amendment reaches `EFFECTIVE`; this spec adds no change to work-reporting beyond
  making the gate (an `EFFECTIVE` amendment) reachable.
- **FOR-06-06 (margin-matrix)** — reads `WorkReport.clientUnitPrice` as-is; amendment price
  correctness is automatic once the seam is live. No change here.
- **FOR-05-10 / FOR-05-10b (project Gantt)** — `Project.startDate` anchors the derived bars;
  `Project.endDate` is the schedule finish this spec shifts by the net `scheduleDeltaHalfDays`. Bars
  are not mutated.
- **FOR-05-07 (offer-approval)** — the generic `NotificationService` + in-app bell this spec emits
  through; only the in-app (DB) channel is in scope.
- **`entity-creation-rules`** — no new managed entity/resource is introduced (the `AMENDMENTS`
  resource and `SIGNABLE_DOCUMENTS` resource already exist); this spec rides on them.
- **`test-cases.md` / `tasks-parallel-waves` / `git-repo-structure` / `test-execution-rules`** —
  workspace standards applied to this spec's `tasks.md` and `test-cases.md`.

## Glossary

- **Open amendment** — the single `EstimateAmendment` of a project in `AmendmentStatus.OPEN`
  (FOR-06-08 invariant). The only amendment this spec can send for signing.
- **Amendment document** — a `SignableDocument` of `SignableDocumentType.code = 'AMENDMENT'`, created
  and linked to an amendment when it is sent for signing (`EstimateAmendment.signableDocument`).
- **Send for signing** — the action that generates the amendment document body from the amendment's
  lines, freezes it via the FOR-05-08 engine (`requestSignatures`), and moves the amendment
  `OPEN → SENT_FOR_SIGNING`.
- **All-parties-signed date** — the date the linked document reaches `SIGNED` (its last signature
  completes). This becomes the amendment's `effectiveDate`.
- **Effective** — `AmendmentStatus.EFFECTIVE`: the amendment's deltas are live. The price seam sums
  its `extraUnitPrice`; the project end date reflects its schedule delta.
- **Amendment_Status transitions (this spec):** `OPEN → SENT_FOR_SIGNING` (send for signing);
  `SENT_FOR_SIGNING → OPEN` (signing withdrawn / document declined or voided — reopen for editing);
  `SENT_FOR_SIGNING → SIGNED` (document fully signed); `SIGNED → EFFECTIVE` (deltas applied). FOR-06-08
  owns `OPEN` and `DELETED`.
- **Net schedule delta** — the sum of `scheduleDeltaHalfDays` across the amendment's `SCHEDULE_CHANGE`
  lines (signed half-days; positive = extend, negative = shorten), converted to days and added to
  `Project.endDate`.
- **Enum_Label_I18n** — the FOR-06-08 convention that `AmendmentStatus` is rendered through an i18n key
  (`amendments.status.*`) with PL + RU parity; the raw enum constant is never shown.

## Requirements

### Requirement 1: Send the open amendment for signing

**User Story:** As a MANAGER or FINANCIER, I want to send the project's open amendment to the client
for signing, so that the agreed changes can be made legally binding.

#### Acceptance Criteria

1. THE System SHALL expose a **Send for signing** action on an `OPEN` amendment that has at least one
   line; it SHALL reject sending an **empty** amendment with a localized message
   `error.amendment.emptyNotSignable` (completing the FOR-06-08 R6.3 guard).
2. WHEN a MANAGER/FINANCIER sends an `OPEN` amendment for signing, THE System SHALL create a
   `SignableDocument` of `SignableDocumentType.code = 'AMENDMENT'` on the amendment's project through
   the FOR-05-08 engine, generate its body from the amendment's lines + the signed-estimate/schedule
   baseline, request signatures (freezing the immutable PDF via the engine), and set
   `EstimateAmendment.signableDocument` to the created document.
3. WHEN the amendment document is created and sent, THE System SHALL transition the amendment
   `OPEN → SENT_FOR_SIGNING`; from that point the amendment's lines SHALL be immutable (FOR-06-08 R2.4
   / R6.4 already reject line mutation once the amendment leaves `OPEN`).
4. THE System SHALL designate the signer set as the project **CLIENT** member(s) and the executor
   representative (MANAGER) per the amendment-document policy, reusing the FOR-05-08 signer model; the
   signing **method** and **level** SHALL default per the engine (`AdES`) and MAY be chosen in the send
   dialog.
5. IF the project has no `OPEN` amendment, OR the amendment is already `SENT_FOR_SIGNING` / `SIGNED` /
   `EFFECTIVE` / `DELETED`, THEN THE System SHALL reject the send with a localized message
   `error.amendment.notOpen`.
6. THE send action SHALL be gated by the `AMENDMENTS` UPDATE operation (FOR-06-08 R9) and by the
   FOR-05-08 `SIGNABLE_DOCUMENTS` CREATE/UPDATE grant for the acting role.
7. THE System SHALL NOT trigger the FOR-05-08 project-activation hand-off: the `AMENDMENT` type is not
   a `CONTRACT_*` type, so reaching `SIGNED` SHALL NOT move `Project.status`.

### Requirement 2: Reflect the signing lifecycle onto the amendment

**User Story:** As a MANAGER, I want the amendment status to follow the document's signing progress,
so that the Aneksy list always shows the true state (sent, signed, effective).

#### Acceptance Criteria

1. THE System SHALL treat the linked `SignableDocument` as the single source of truth for signing
   progress; the amendment SHALL NOT maintain a parallel signature set.
2. WHEN the linked document reaches `SIGNED` (FOR-05-08 "SIGNED iff all signatures SIGNED"), THE System
   SHALL transition the amendment `SENT_FOR_SIGNING → SIGNED`.
3. WHEN the linked document is **declined** by a signer or **voided** before completion, THE System
   SHALL transition the amendment `SENT_FOR_SIGNING → OPEN` (reopen for editing), clear
   `EstimateAmendment.signableDocument`, and leave the amendment lines intact so the manager can amend
   and resend; the voided/declined document remains readable for audit (FOR-05-08 R1.7).
4. THE amendment status transitions driven by signing SHALL be applied reliably after the document
   transition commits (an after-commit reaction), so a notification/side-effect failure cannot roll
   back the signing itself.
5. THE System SHALL expose, on the amendment read model (FOR-06-08 R1), the linked document id, the
   signing progress (signed/total, outstanding signers — read from the FOR-05-08 progress), and the
   amendment status, so the Aneksy tab can render them without a second lifecycle.

### Requirement 3: Set the effective date and make the amendment effective

**User Story:** As the System, I want a signed amendment to become effective on the date all parties
signed, so that the agreed price and schedule changes take effect from exactly that date.

#### Acceptance Criteria

1. WHEN the amendment transitions to `SIGNED`, THE System SHALL set `EstimateAmendment.effectiveDate`
   to the **all-parties-signed date** (the date the linked document reached `SIGNED`).
2. IMMEDIATELY after setting `effectiveDate`, THE System SHALL transition the amendment
   `SIGNED → EFFECTIVE` (the effective date is the signing date, so there is no deferred/future
   effective window in this spec).
3. THE `SIGNED → EFFECTIVE` transition SHALL be **idempotent**: re-processing an already-`EFFECTIVE`
   amendment SHALL be a no-op and SHALL NOT re-apply deltas or re-shift the project end date.
4. THE System SHALL NOT allow more than one amendment per project to be in flight: because FOR-06-08
   permits a new `OPEN` amendment only when none is `OPEN`, and this spec reopens a declined/voided one
   back to `OPEN`, at most one amendment is ever `SENT_FOR_SIGNING` at a time.
5. ONCE an amendment is `EFFECTIVE`, its lines and effective date SHALL be immutable.

### Requirement 4: Apply the price deltas (activate the dormant seam)

**User Story:** As the System, I want an effective amendment's price deltas to flow into
work-reporting revenue and the margin matrix automatically, so that reports created after the
effective date use the amended prices.

#### Acceptance Criteria

1. THE System SHALL make the FOR-06-08 `WorkReportService.resolveClientUnitPrice` seam contribute the
   amendment's `extraUnitPrice` by making the amendment reach `AmendmentStatus.EFFECTIVE` with a set
   `effectiveDate`; this spec SHALL NOT change the seam's formula (it already sums `extraUnitPrice`
   over `EFFECTIVE` amendments whose `effectiveDate <= reportDate`).
2. THE price delta SHALL apply to work reports whose applicable report date is **on or after** the
   amendment `effectiveDate`; reports before that date SHALL keep the baseline price (the seam's
   `effectiveDate <= reportDate` condition).
3. THE margin matrix (FOR-06-06) SHALL require **no change**: it reads `WorkReport.clientUnitPrice`
   as-is, which already reflects the seam at report time.
4. THE System SHALL cover with a test that, once an amendment is `EFFECTIVE`, a new work report on an
   amended `(workItem)` resolves `line.unitPrice + Σ extraUnitPrice`, and a report dated before the
   effective date resolves the baseline `line.unitPrice`.

### Requirement 5: Apply the schedule delta to the project end date

**User Story:** As a MANAGER, I want an effective schedule change to move the project end date, so that
the project deadline reflects the agreed extension without touching the planning Gantt bars.

#### Acceptance Criteria

1. WHEN an amendment becomes `EFFECTIVE`, THE System SHALL compute the **net schedule delta** = the
   sum of `scheduleDeltaHalfDays` over the amendment's `SCHEDULE_CHANGE` lines (signed half-days), and
   SHALL shift `Project.endDate` by that delta converted to days (`halfDays / 2`, rounded per the
   half-day grid convention).
2. THE System SHALL change **only** `Project.endDate`; it SHALL NOT modify `Project.startDate` or any
   `ProjectScheduleBarEntity` (the planning Gantt bars are unchanged, per the user decision "Gantt will
   not change — only the project end date").
3. IF the amendment has no `SCHEDULE_CHANGE` line, THEN THE System SHALL leave `Project.endDate`
   unchanged.
4. THE end-date shift SHALL be applied exactly once (idempotent with Requirement 3.3): re-processing an
   already-`EFFECTIVE` amendment SHALL NOT shift the end date again.
5. IF shifting would produce an end date before the project start date (a net shortening larger than
   the remaining schedule), THEN THE System SHALL clamp to / reject per a deterministic, tested rule
   and surface a localized validation message `error.amendment.endDateInvalid` rather than silently
   produce an invalid range.

### Requirement 6: Aneksy tab — signing surface

**User Story:** As a MANAGER/FINANCIER, I want to send an amendment for signing and watch its signing
progress from the Aneksy tab, so that I manage the whole amendment lifecycle in one place.

#### Acceptance Criteria

1. THE System SHALL extend the FOR-06-08 Aneksy panel (keyed `amendments`) with a **Send for signing**
   action on an eligible `OPEN` amendment (non-empty), gated on `AMENDMENTS` UPDATE.
2. THE panel SHALL show, for a `SENT_FOR_SIGNING` amendment, the signing progress (signed/total,
   outstanding signers) read from the linked document, and a link to the document on the FOR-05-08
   signing surface for the actual sign/decline/upload actions (the ceremony is the engine's surface,
   reused — not re-implemented on the Aneksy tab).
3. THE panel SHALL reflect the amendment status (`SENT_FOR_SIGNING` / `SIGNED` / `EFFECTIVE`) via the
   FOR-06-08 `amendments.status.*` labels, and SHALL show the `effectiveDate` once set.
4. A FOREMAN (R own on `AMENDMENTS`) SHALL see the signing status read-only and SHALL NOT see the
   send-for-signing action; CLIENT reaches the amendment only through the FOR-05-08 / FOR-09 signing
   surface, not through `AMENDMENTS`.

### Requirement 7: Notifications (amendment lifecycle)

**User Story:** As a project participant, I want to be notified when an amendment is sent for signing
and when it becomes effective, so that the amendment flow does not stall and everyone knows when the
new terms apply.

#### Acceptance Criteria

1. THE System SHALL emit notifications ONLY through the existing generic `NotificationService`
   (FOR-05-07); it SHALL NOT persist or deliver notifications itself. Only the in-app (DB) bell channel
   is in scope (email/SMS/Telegram are future work).
2. THE System SHALL **reuse the FOR-05-08 generic document notifications** already emitted by the
   engine for the document ceremony it drives — `notification.document.sentForSigning` (to each
   designated signer, i.e. the CLIENT), `notification.document.signedByParty`,
   `notification.document.fullySigned`, `notification.document.signingDeclined`,
   `notification.document.voided` — and SHALL NOT duplicate those with amendment-specific equivalents.
   These deep-link to the signing surface.
3. THE System SHALL ADD the following **amendment-specific** notification types (new i18n keys under
   `notification.amendment.*`), each carrying a **deep-link to the amendment on the Aneksy tab**, for
   the amendment-level transitions the generic document notifications do not cover:

   | Type (i18n key) | Recipient | Trigger | Message (gist) | Deep-link |
   |---|---|---|---|---|
   | `notification.amendment.sentForSigning` | amendment owner (MANAGER) + project FINANCIER | a MANAGER/FINANCIER sends the amendment for signing (`OPEN → SENT_FOR_SIGNING`) | amendment #N of project X sent for signing | Aneksy tab, the amendment row |
   | `notification.amendment.reopened` | amendment owner (MANAGER) + project FINANCIER | the amendment returns to `OPEN` because its document was declined/voided | amendment #N reopened for editing | Aneksy tab, the amendment row |
   | `notification.amendment.effective` | amendment owner (MANAGER) + project FINANCIER | the amendment becomes `EFFECTIVE` (deltas applied, end date shifted) | amendment #N is now effective from {date} | Aneksy tab, the amendment row |

4. THE CLIENT-facing side of the ceremony (sent-for-signing, fully-signed, declined) is covered by the
   reused FOR-05-08 `notification.document.*` types (R7.2); this spec SHALL NOT emit a second
   CLIENT-facing amendment notification for the same event.
5. Every amendment-specific notification SHALL identify the amendment (project + seqNo) in its message
   and SHALL be clickable (it always carries a deep-link).
6. Notification emission SHALL be a best-effort after-commit side effect; a notification failure SHALL
   NOT roll back the amendment status transition (consistent with FOR-05-08 §Key decision 7 and
   Requirement 2.4).

### Requirement 8: ABAC — no new resource

**User Story:** As a platform owner, I want amendment signing to reuse the existing ABAC resources, so
that no new permission surface is introduced for a flow that is just "an amendment + its document".

#### Acceptance Criteria

1. THE System SHALL introduce **no new ABAC resource**. The send-for-signing and status-reflection
   actions on the amendment SHALL be gated by the existing `AMENDMENTS` resource (FOR-06-08 R9); the
   document ceremony SHALL be gated by the existing `SIGNABLE_DOCUMENTS` resource (FOR-05-08 R8), each
   using only standard CRUD operations.
2. THE send-for-signing action SHALL require `AMENDMENTS` UPDATE on the acting role (ADMIN / MANAGER /
   FINANCIER per the FOR-06-08 matrix) AND the FOR-05-08 `SIGNABLE_DOCUMENTS` CREATE/UPDATE grant.
3. THE System SHALL rely on the FOR-05-08 CLIENT own-signature guard for the signing ceremony; a CLIENT
   SHALL NOT gain any `AMENDMENTS` grant from this spec.
4. THE application SHALL start cleanly (no `PermissionAnnotationValidator` failure): any new endpoint
   added to the `AMENDMENTS` controller for send-for-signing SHALL carry a matching
   `@PermissionOperation` / `@RequiresPermission`.

### Requirement 9: Internationalization (PL + RU)

**User Story:** As a bilingual user, I want all new signing-related amendment UI text and the new
notification types localized in Polish and Russian, so that the feature reads naturally in either
language.

#### Acceptance Criteria

1. **Backend i18n fields:** this spec SHALL add **no new backend i18n entity fields**. The amendment
   entities carry no `namePL`/`nameRU` (FOR-06-08 R11.3); the `AMENDMENT` document type's `namePL`/
   `nameRU` already exist (FOR-05-08). `AmendmentStatus` is labeled on the frontend
   (`amendments.status.*`, keys already defined by FOR-06-08).
2. **Frontend i18n elements:** PL and RU values SHALL be provided for: the **Send for signing** action
   label and its confirm/send dialog (method/level pickers reuse the FOR-05-08 labels), the
   send-empty/not-open/end-date error messages (`error.amendment.emptyNotSignable`,
   `error.amendment.notOpen`, `error.amendment.endDateInvalid`), the signing-progress labels on the
   Aneksy row (reusing FOR-05-08 progress labels where possible), the effective-date label, and the
   three new notification types `notification.amendment.sentForSigning` / `.reopened` / `.effective`.
3. Every i18n key introduced by this spec SHALL have a **non-empty** value in BOTH `pl.json` and
   `ru.json` (Polish primary, Russian secondary), verified by the locale-parity test.

### Requirement 10: Overlay integrity — do not mutate the frozen baseline

**User Story:** As the System, I want an effective amendment to remain a pure overlay (except the one
agreed project-end-date shift), so that the signed estimate and planning Gantt stay the frozen source
of truth and the deltas remain reproducible.

#### Acceptance Criteria

1. THE System SHALL NOT mutate `EstimateEntity` / `EstimateLineEntity` / `EstimateLineRoomQtyEntity` /
   any `WorkReport` when an amendment becomes `EFFECTIVE`; price/volume effects flow only through the
   read-time `resolveClientUnitPrice` seam.
2. THE System SHALL NOT mutate `ProjectScheduleBarEntity`; the only schedule mutation is the single
   `Project.endDate` shift of Requirement 5.
3. THE stored `AmendmentLine` deltas SHALL remain the reproducible record of what changed; applying an
   amendment SHALL be derivable from its lines + the frozen baseline.
