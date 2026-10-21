# Requirements Document

## Introduction

This spec (**FOR-06-12-client-communication**) is the client-communication feature child of the
FOR-06 "project in progress" (execution stage) parent. It delivers the **admin/internal side** of a
free-form conversation between a project's **CLIENT** and the project's **admin staff**: a single
running message thread per project where the client asks questions and the manager replies, each
message optionally carrying media (photos, videos, documents) and being **auto-translated** so every
reader sees it in their own locale.

The feature owns the message **domain** (the `ClientMessage` + media + translation model, its
project-scoped service, and the `CLIENT_MESSAGES` ABAC resource) and the **execution-stage
Komunikacja tab** reserved for it by FOR-06-00 in the FOR-05 project workspace. The **client-facing
surface** (how a CLIENT reads and posts from the client portal) is rendered by **FOR-09** and is out
of scope here; this spec exposes the domain and the admin-side panel that FOR-09 later consumes.

### Participant and permission model (product decision)

- **Author of a message** is either the project's **CLIENT** or a member of the project's **admin
  staff** (any project member whose Company_Role is not WORKER/CLIENT — MANAGER, FOREMAN, ESTIMATOR,
  FINANCIER, …).
- **Every admin-staff member of the project can READ** the thread (full visibility).
- **Only the MANAGER may reply** (post an admin-side message) and **only the MANAGER may mark a
  client message read** (R5). FOREMAN and other admin staff are read-only on the thread.
- A **client message** notifies the project's **MANAGER + FOREMAN** members; an **admin reply**
  notifies the project's **CLIENT** (R7).

### Auto-translation (product decision)

Every message stores its **original text** and its **detected original locale**, plus a
**translation into each other supported locale** (PL/RU). A reader sees the message in **their own
locale** by default and may reveal the original text via a quick-view control (R6). The original
locale is detected from the author's language preference combined with the text itself. Translation
is produced through a **pluggable translation service seam** (interface + a provider-backed
implementation reusing the project's existing Google Gen AI / Gemini wiring from FOR-06-03, plus a
disabled fallback) so the feature **degrades gracefully to original-only text** when translation is
not configured — it never blocks sending a message.

### Locale type (cross-cutting decision)

All locales in this spec are the **standard `java.util.Locale`** — there is no custom locale enum.
`Locale` is persisted through a shared `LocaleAttributeConverter` (`Locale ↔ String` via
`toLanguageTag()` / `forLanguageTag(...)`, storing the lowercase language tag `"pl"`/`"ru"` the
codebase already uses) so it is **backwards compatible** with the existing `users.locale` string
column (no data migration). A `null`, blank, or unsupported/legacy stored value resolves to **PL**
(the base/fallback locale, consistent with the existing `EmailLocaleResolver`). Because `Locale` is
open-ended, the "supported locale" set (PL/RU) remains a **validation** concern, not a type
guarantee. As part of this spec's cross-cutting work, the existing `UserEntity.locale` field is
migrated from `String` to `Locale` using the same converter (R12).

### Scope boundaries

- **In scope:**
  - The `ClientMessage` entity + a `ClientMessageTranslation` child (per-locale translated text) +
    a `ClientMessageMedia` child (attachment metadata; bytes in the object store), their tables /
    changesets, and the project-scoped service.
  - A **single thread per project**: all of a project's messages form one ordered conversation; no
    multiple topics/threads in this spec.
  - Posting a message (client question or manager reply), optional media attachments, auto-detect of
    the original locale, and auto-translation into the other supported locale(s).
  - Per-recipient-locale read model: a reader gets each message in their own locale with the
    original text available on demand.
  - First-MANAGER-read marking of a client message (a single `readAt`/`readBy` on the message); no
    other read/resolution state.
  - A new project-scoped ABAC resource `CLIENT_MESSAGES` with the full entity-creation checklist, and
    the FOR-06-00 `clientCommunication` execution tab (slot reserved on `CLIENT_MESSAGES`/READ)
    swapped from `PlaceholderTab` to the real admin panel.
  - Two in-app notifications emitted via the generic notification service (R7).
  - The admin-side Komunikacja panel (frontend).
  - A pluggable `Message_Translation_Service` seam (provider impl + disabled fallback).
  - A shared `LocaleAttributeConverter` and the **cross-cutting migration of `UserEntity.locale`
    from `String` to `java.util.Locale`** (backwards compatible, no data migration), with regression
    coverage of the user/email/locale surfaces that read it (R12).
  - PL + RU localization of every user-facing string, and the spec's own tests + `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **The client-facing UI** (how a CLIENT reads/writes from the portal) — FOR-09; this spec only
    owns the domain + admin panel.
  - **New notification delivery channels** — email / SMS / Telegram and the async fan-out remain
    future work; this spec only *emits* via the existing in-app (DB) notification service.
  - **The object store itself** (FOR-04-17 `ImageStorage`); this spec references it for media.
  - **Multiple threads / topics, message editing, deletion-by-client, reactions, typing
    indicators, read receipts beyond the single manager-read marker.**
  - **A general-purpose translation platform** — translation is scoped to message text into the
    app's supported locales (PL/RU) through the seam only.
  - **Changing `stageOf` / `resolveWorkspaceTabs` / the `WORKSPACE_TABS` contract shape** — the tab
    slot and its `CLIENT_MESSAGES` gate were reserved by FOR-06-00; this spec seeds the resource and
    swaps the tab's panel only.

### Relationship to existing specs (source of truth)

- **FOR-06-00-execution-foundation** — reserved the `clientCommunication` tab on
  `CLIENT_MESSAGES`/READ (points at `PlaceholderTab`, hidden until the resource is seeded). This spec
  seeds the resource and swaps the panel.
- **FOR-05-09-team-selection** — `ProjectMemberEntity`, the ACTIVE/INACTIVE Assignment_Status, the
  per-project crew, and the `projectRole.code` strings (CLIENT / MANAGER / FOREMAN / …). "ACTIVE
  MANAGER member", "ACTIVE FOREMAN member", "the project's CLIENT", and "admin-staff member" are read
  from it.
- **FOR-05-07-offer-approval Requirement 13** — the generic `Notification` entity, the
  `NotificationService.create(recipientUserId, type, body, deepLink)` API, and the in-app Bell. This
  spec is a consumer: it adds two `Notification_Type` keys and emits through that service, adding no
  new delivery channel.
- **FOR-05-08-document-signing `DocumentMediaService`** — the reusable media-attachment pattern
  (bytes via `ImageStorage.storeObject(file, namespace)`, metadata + `kind` in the DB, own
  content-type/size validation, `deleteIfOrphan` on delete). `ClientMessageMedia` mirrors it under a
  `client-messages` namespace.
- **FOR-06-03-procurement-ocr** — the Google Gen AI (Gemini) client wiring (`google-genai` SDK,
  `foremen.ocr.*` / `OcrProperties`, lazy client build, `Disabled*` fallback via a
  `@Conditional`). The `Message_Translation_Service` seam reuses this provider/credential approach;
  it does **not** import OCR code.
- **`.kiro/steering/entity-creation-rules.md`** — `CLIENT_MESSAGES` resource seed changeset, ADMIN
  grant + role matrix, class-level `@PermissionResource("CLIENT_MESSAGES")`, `getProjectIdPath()`,
  startup annotation completeness.

## Glossary

- **ClientMessage** — the entity `ClientMessage{ project (FK/projectId), author (user FK), authorSide
  (enum CLIENT/STAFF), originalText (text), originalLocale (`Locale`, persisted via
  `LocaleAttributeConverter`), readAt (timestamp, nullable), readBy (user FK, nullable), + audit }`.
  Project-scoped on `project.id`. One per posted message; all of a project's messages are one thread
  ordered by `createdDate`.
- **ClientMessageTranslation** — the entity `ClientMessageTranslation{ message (FK, ON DELETE
  CASCADE), locale (`Locale`, persisted via `LocaleAttributeConverter`), text (text), machine
  (boolean) }`, unique on `(message_id, locale)`. Holds the message text in a non-original supported
  locale; `machine=true` marks an auto-translation (vs. the original locale's row, which mirrors
  `originalText`).
- **ClientMessageMedia** — the entity `ClientMessageMedia{ message (FK, ON DELETE CASCADE), fileName,
  contentType, sizeBytes, storageUri (object key), kind (enum IMAGE/VIDEO/DOCUMENT), uploadedBy (user
  FK) }`. Metadata only; bytes live in the object store (R4).
- **Author_Side** — `CLIENT` (the message is from the project's client) or `STAFF` (from the
  manager). Derived from the author's project role, not client-supplied.
- **Admin staff** (of a project) — every ACTIVE project member whose `projectRole.code` is not
  `WORKER` and not `CLIENT` (MANAGER, FOREMAN, ESTIMATOR, FINANCIER, …).
- **Original_Locale** — the supported locale (PL/RU) the author wrote in, detected from the author's
  language preference and the message text (R6.1); typed as `java.util.Locale`.
- **Supported_Locales** — the application's localization set, PL and RU (the existing
  `{"pl","ru"}` set). Membership is a validation concern over `Locale`, not a type.
- **LocaleAttributeConverter** — the shared JPA `AttributeConverter<Locale, String>` that stores a
  `Locale` as its lowercase language tag (`"pl"`/`"ru"`) and reads it back with `forLanguageTag`,
  resolving `null`/blank/unsupported/legacy stored values to **PL**. Backwards compatible with the
  existing `users.locale` column.
- **Message_Translation_Service** — the pluggable seam that, given a source text + source `Locale`,
  returns the text in a target `Locale`; a provider-backed implementation (Gemini) when configured, a
  disabled fallback that returns no translation otherwise (R6).
- **Notification_Service** — the generic in-app (DB) notification service (FOR-05-07 Requirement 13);
  this spec emits through it and does not re-implement delivery.

## Requirements

### Requirement 1 — ClientMessage / translation / media model and persistence

**User Story:** As the system, I want client-communication messages persisted as a single
per-project thread with per-locale text and attachment metadata, so a conversation survives and reads
cleanly in any locale.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a project-scoped entity `ClientMessage` extending `BaseEntity` with fields
   `project`/`projectId` (not null), `author` (user FK, not null), `authorSide` (enum
   `CLIENT`/`STAFF`, not null), `originalText` (text, not null, non-blank), `originalLocale`
   (`java.util.Locale`, not null, persisted as a lowercase language tag via
   `LocaleAttributeConverter`), `readAt` (timestamp, nullable), and `readBy` (user FK, nullable).
2. THE SYSTEM SHALL define a child entity `ClientMessageTranslation` extending `BaseEntity` with
   fields `message` (FK, not null, `ON DELETE CASCADE`), `locale` (`java.util.Locale`, not null,
   persisted via `LocaleAttributeConverter`), `text` (text, not null), and `machine` (boolean, not
   null), unique on `(message_id, locale)`.
3. THE SYSTEM SHALL define a child entity `ClientMessageMedia` extending `BaseEntity` with fields
   `message` (FK, not null, `ON DELETE CASCADE`), `fileName`, `contentType`, `sizeBytes`,
   `storageUri` (object key), `kind` (enum `IMAGE`/`VIDEO`/`DOCUMENT`, not null), and `uploadedBy`
   (user FK, not null).
4. THE SYSTEM SHALL create the `client_messages`, `client_message_translations`, and
   `client_message_media` tables via Liquibase changesets following the FK + audit-column convention,
   idempotent via `NOT tableExists` + `onFail="MARK_RAN"`, registered last in `changelog.xml`.
5. THE thread of a project SHALL be all of its `ClientMessage` rows ordered by `createdDate`
   ascending; THE SYSTEM SHALL keep **one thread per project** and SHALL NOT model multiple
   threads/topics.
6. WHEN a `ClientMessage` is deleted THEN its `client_message_translations` and `client_message_media`
   rows SHALL be discarded (`ON DELETE CASCADE`).

### Requirement 2 — Posting a message

**User Story:** As a client or a manager, I want to post a message into the project thread, so the
other side sees my question or answer.

#### Acceptance Criteria

1. WHEN an authorized author (the project's CLIENT, or the project's MANAGER) posts a message THEN
   the request SHALL carry the message text and zero or more media files, and the server SHALL
   persist a `ClientMessage` with `authorSide` derived from the author's project role
   (`CLIENT` → `CLIENT`, else `STAFF`) — never taken from the request.
2. WHEN the posted text is empty or blank AND no media is attached THEN the write SHALL be rejected
   with `error.clientMessage.empty`, changing nothing.
3. WHEN a message is posted THEN its `originalLocale` SHALL be set to the detected Original_Locale
   (R6.1) and a `ClientMessageTranslation` row for that locale SHALL be stored mirroring
   `originalText` with `machine=false`.
4. WHEN a message is posted on a project whose `Project.status` is not `ACTIVE` THEN the write SHALL
   be rejected (the thread is read-only off the execution stage, R8.1).
5. THE SYSTEM SHALL set `readAt`/`readBy` to null on a newly posted client message; a staff reply
   SHALL be created already "read" by its author side (a reply needs no manager-read marker).

### Requirement 3 — Reply and read authority (MANAGER only)

**User Story:** As a product owner, I want only the manager to answer and to mark client messages
read, so the client always has a single accountable responder.

#### Acceptance Criteria

1. WHEN a project member whose `projectRole.code` is `MANAGER` (or ADMIN) posts a message THEN it
   SHALL be accepted as a `STAFF` reply.
2. WHEN a project member who is admin staff but **not** MANAGER (e.g. FOREMAN, ESTIMATOR, FINANCIER)
   attempts to post a reply THEN the write SHALL be rejected with `error.clientMessage.reply.notManager`,
   changing nothing.
3. WHEN a non-MANAGER attempts to mark a client message read THEN the operation SHALL be rejected with
   `error.clientMessage.read.notManager`, changing nothing.
4. THE reply and mark-read actions SHALL map onto the `CLIENT_MESSAGES` **UPDATE/CREATE** operations
   (R8); the MANAGER-only rule SHALL be enforced in the service **on top of** the ABAC grant, so a
   FOREMAN with READ can never reply or mark read even though the resource is visible to them.

### Requirement 4 — Media attachments

**User Story:** As an author, I want to attach photos, videos, or documents to a message, so I can
show the other side what I mean.

#### Acceptance Criteria

1. WHEN posting a message THE author SHALL be able to attach zero or more media files; the bytes SHALL
   be stored via the object store (`ImageStorage.storeObject`) under a `client-messages` namespace and
   only the metadata + `kind` SHALL be persisted (mirroring `DocumentMediaService`).
2. THE accepted content types SHALL be images (`image/png`, `image/jpeg`, `image/webp`), documents
   (`application/pdf`), and video (`video/mp4`, `video/webm`, `video/quicktime`); any other type SHALL
   be rejected with `error.clientMessage.media.invalid`, storing nothing.
3. THE maximum upload size SHALL be **10 MB** for an image or document and **100 MB** for a video; an
   oversized file SHALL be rejected with `error.clientMessage.media.tooLarge` naming the size and the
   limit, storing nothing.
4. WHEN a message is read THEN its media references SHALL be returned (via a time-boxed signed
   download URL for private objects, mirroring the document/procurement media read path) so the
   reader can view/download each attachment.
5. THE media visibility SHALL follow the message's `CLIENT_MESSAGES` project-scoped access; media
   SHALL never be reachable by a user without READ on the message's project.

### Requirement 5 — First-manager-read marking

**User Story:** As a manager, I want a client message marked read when I first open it, so the team
sees which questions still need attention.

#### Acceptance Criteria

1. WHEN a MANAGER first reads a `CLIENT`-side message that has no `readAt` THEN the system SHALL set
   `readAt` to the current time and `readBy` to that manager.
2. WHEN a message is already read (non-null `readAt`) THEN a subsequent read SHALL NOT change `readAt`
   or `readBy` (first read wins).
3. A `STAFF`-side message SHALL carry no unread state (it is the manager's own reply); only
   `CLIENT`-side messages participate in the read marker.
4. THE read model SHALL expose each client message's read state (`readAt`, `readBy`) so the admin
   panel can show read/unread, and SHALL expose an **unread client-message count** for the project.

### Requirement 6 — Auto-translation and per-locale rendering

**User Story:** As a reader, I want every message shown in my own language with the original a click
away, so I understand the client and the manager regardless of who wrote in which language.

#### Acceptance Criteria

1. WHEN a message is posted THEN the system SHALL determine its Original_Locale from the author's
   language preference combined with the message text; the resolved locale SHALL be one of the
   Supported_Locales (PL/RU) and SHALL be persisted on the message.
2. WHEN a message is posted THEN for **every other** Supported_Locale the system SHALL obtain a
   translation of `originalText` via the `Message_Translation_Service` and persist it as a
   `ClientMessageTranslation` with `machine=true`.
3. WHERE the `Message_Translation_Service` is not configured OR a translation call fails THE system
   SHALL persist **no** machine translation for that locale and the message SHALL remain valid
   (original-only); posting SHALL NOT be blocked by a translation failure (best-effort, like the
   notification emission).
4. WHEN a reader reads the thread THEN each message SHALL be rendered in the **reader's** locale: the
   `ClientMessageTranslation` for the reader's locale when present, else `originalText`; the response
   SHALL also carry the `originalText` + `originalLocale` so the UI can offer an "original" quick view.
5. WHERE a message has no translation for the reader's locale (translation unconfigured/failed, R6.3)
   THE reader SHALL see `originalText` with its `originalLocale` labeled, and the "show original"
   control SHALL be a no-op/hidden (original and shown text are the same).
6. THE translation obtained SHALL be stored once at post time (not re-computed per read); a later
   locale added to the app does not retroactively translate historical messages in this spec.

### Requirement 7 — Notifications

**User Story:** As a participant, I want to be told when the other side writes, so the conversation
moves without polling.

The feature emits via the generic `NotificationService` (FOR-05-07 Requirement 13) only; it adds no
new delivery channel. Each notification is persisted per recipient and surfaced in the in-app Bell.

#### Acceptance Criteria

1. WHEN a CLIENT posts a message THEN the system SHALL emit a notification of type
   `notification.clientMessage.fromClient` to **every ACTIVE MANAGER and every ACTIVE FOREMAN member**
   of the project, carrying a deep-link to the Komunikacja tab
   (`/projects/{projectId}/clientCommunication?message={messageId}`).
2. WHEN a MANAGER posts a reply THEN the system SHALL emit `notification.clientMessage.fromStaff` to
   the project's **CLIENT** member(s), carrying the same-shape deep-link.
3. WHERE a project has no recipient of the relevant kind (no ACTIVE MANAGER/FOREMAN, or no CLIENT) THE
   emission SHALL notify nobody and SHALL NOT be an error.
4. THE notifications SHALL be emitted **after** the message-post transaction commits, best-effort: a
   notification failure SHALL be logged and swallowed and SHALL NOT roll back or block the committed
   message (mirroring `WorkReportNotificationEmitter`).
5. WHERE a notification type is introduced THE type key SHALL resolve to a localized title in both PL
   and RU, and a raw type key SHALL never be shown to the user.

### Requirement 8 — Stage gating and ABAC

**User Story:** As a security owner, I want the thread reachable only on active projects by the right
roles, so only the client and permitted staff can converse, and only in the right stage.

#### Acceptance Criteria

1. WHEN a project is `ACTIVE` THEN the Komunikacja surface SHALL be open (read for admin staff + the
   client; post for CLIENT + MANAGER); WHEN a project is `COMPLETED` THEN the surface SHALL be
   read-only (history visible, no new messages); WHEN a project is `DRAFT` THEN no Komunikacja surface
   SHALL be reachable.
2. THE SYSTEM SHALL define a new **project-scoped** ABAC resource `CLIENT_MESSAGES`, its backing
   service implementing `ProjectScopedService` with `getProjectIdPath()` returning `"project.id"`, so
   LIST reads are automatically project-filtered.
3. THE controller SHALL be annotated `@PermissionResource("CLIENT_MESSAGES")`, every in-scope handler
   SHALL carry a matching `@RequiresPermission`/`@PermissionOperation`, and the application SHALL start
   without a `PermissionAnnotationValidator` failure.
4. THE SYSTEM SHALL seed the `CLIENT_MESSAGES` resource row idempotently and grant, on top of the
   project-scope filter (R8.2):
   - **ADMIN** — CREATE, READ, UPDATE, DELETE (matrix completeness; the evaluator bypasses for the
     literal ADMIN role).
   - **CLIENT** — CREATE, READ (post questions and read the thread on **own** project; no reply, no
     mark-read, no delete). The CLIENT-only authoring side is `CLIENT` and is server-derived.
   - **MANAGER** — CREATE, READ, UPDATE (reply, read the thread, and mark client messages read).
   - **FOREMAN** — READ only (full visibility; may not reply or mark read).
   - **Other admin-staff members of the project** (ESTIMATOR, FINANCIER, …) — READ only.
   - **WORKER** — no grant (deny-by-default), documented with an explanatory XML comment.
   The reply and mark-read actions map onto CREATE/UPDATE; the rule that only a MANAGER (or ADMIN) may
   reply or mark read is enforced in the service on top of the ABAC grant (R3).
5. WHEN a CLIENT caller reads or writes THEN the operation SHALL be restricted server-side to the
   caller's **own** project membership: a CLIENT SHALL only ever see and post to a project they are a
   member of; a CLIENT post whose derived `authorSide` would not be `CLIENT` SHALL be impossible
   (the side is derived from their role).
6. WHEN the `CLIENT_MESSAGES` resource is seeded THEN the FOR-06-00 `clientCommunication` execution
   tab (gated `CLIENT_MESSAGES`/READ) SHALL become visible to permitted callers on an `ACTIVE`
   project, and this spec SHALL swap that tab's `lazy` from `PlaceholderTab` to the real admin panel
   without reshaping the `WORKSPACE_TABS` contract.

### Requirement 9 — Komunikacja tab (frontend, admin side)

**User Story:** As admin staff, I want a Komunikacja tab in the project workspace, so I can read the
client conversation and (as a manager) reply.

#### Acceptance Criteria

1. THE SYSTEM SHALL render a Komunikacja panel on the execution-stage `clientCommunication` tab,
   showing the project's single thread as an ordered conversation with, per message, the author, the
   author side (client/staff badge), the timestamp, the message text **in the reader's locale**, a
   "show original" quick-view control, and any media (thumbnails/links).
2. WHERE the caller is a MANAGER THE panel SHALL provide a composer (text + media upload) to post a
   reply, and client messages SHALL be marked read when first opened by the manager (R5).
3. WHERE the caller is admin staff other than MANAGER THE panel SHALL be read-only (no composer, no
   mark-read), with every message still rendered in the reader's locale.
4. THE panel SHALL show each client message's read/unread state and an unread count, and SHALL
   surface server validation errors (R2/R3/R4) inline.
5. THE "show original" control SHALL reveal `originalText` with its `originalLocale` labeled, and
   SHALL be hidden/disabled when the shown text already equals the original (R6.5).

### Requirement 10 — Message_Translation_Service seam

**User Story:** As an engineer, I want translation behind a pluggable seam with a graceful disabled
fallback, so the feature works with or without a translation provider configured.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a `Message_Translation_Service` interface
   `translate(sourceText, sourceLocale: Locale, targetLocale: Locale) → translatedText` and a
   locale-detection capability `detectLocale(text, authorPreferred: Locale) → Locale`, both typed on
   `java.util.Locale` and returning a Supported_Locale (PL/RU).
2. THE SYSTEM SHALL provide a provider-backed implementation reusing the existing Google Gen AI
   (Gemini) wiring approach (configuration-driven credentials/model under a `foremen.*` property
   block, lazy client build), active only when translation is configured; it SHALL NOT import or
   depend on the OCR service code.
3. THE SYSTEM SHALL provide a disabled fallback (wired when translation is not configured, via a
   `@Conditional`, mirroring `DisabledImageStorage`/`DisabledOcrService`) whose `translate` returns
   no translation and whose `detectLocale` falls back to the author's preferred locale (defaulting to
   PL when that is null/blank/unsupported), so posting always succeeds.
4. A translation or detection failure SHALL be handled per message (logged, treated as "no
   translation"/"fall back to preferred locale"), never surfaced as a 5xx and never blocking the post.

### Requirement 11 — Internationalization completeness

#### Acceptance Criteria

1. THE backend `ClientMessage` / `ClientMessageTranslation` / `ClientMessageMedia` entities SHALL
   carry **no** `nameRU`/`namePL` dictionary columns; message localization is modeled by the
   `ClientMessageTranslation` per-locale rows (original + machine translations), and any referenced
   dictionary/display name (project, user) SHALL use the existing `ru → pl → code` fallback, adding no
   new i18n column.
2. THE frontend SHALL add every new user-facing string — the `workspace.tab.clientCommunication` tab
   label, panel title, author/side badges (client/staff), read/unread labels, unread count, composer
   labels/placeholders (message, attach media, send), the "show original" control + original-locale
   label, media labels, empty/loading states, and all success/error toasts — to **both**
   `src/locales/pl.json` and `src/locales/ru.json` with real PL and RU values (no English fallback),
   and the locale-parity test SHALL pass.
3. THE two `notification.clientMessage.*` type keys SHALL have PL + RU titles mirrored key-for-key.
4. EVERY `error.clientMessage.*` message code SHALL have PL + RU messages in the backend message
   bundle.
5. THE per-message auto-translation (R6) SHALL make a message **readable** in both PL and RU
   regardless of the author's original locale, so a reader never sees an untranslated foreign-locale
   message when translation is configured.

### Requirement 12 — Standard `Locale` type and `UserEntity.locale` migration (cross-cutting)

**User Story:** As an engineer, I want locales represented by the standard `java.util.Locale`
everywhere, with the existing user locale migrated compatibly, so the codebase has one locale type and
no data migration risk.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a shared JPA `LocaleAttributeConverter` implementing
   `AttributeConverter<Locale, String>` that persists a `Locale` as its lowercase language tag
   (`Locale.toLanguageTag()` lowercased, e.g. `"pl"`/`"ru"`) and reads it back via
   `Locale.forLanguageTag(...)`.
2. THE converter SHALL resolve a `null`, blank, or unsupported/legacy stored value to **PL** on read
   (consistent with the existing `EmailLocaleResolver` PL fallback), so an unexpected column value
   never yields a null or invalid `Locale`.
3. THE SYSTEM SHALL migrate the existing `UserEntity.locale` field from `String` to
   `java.util.Locale` using `@Convert(converter = LocaleAttributeConverter.class)` **without** a
   database migration: the `users.locale` column stays `VARCHAR` holding the existing lowercase
   `"pl"`/`"ru"` values, which the converter round-trips unchanged (verified: `forLanguageTag("ru")`
   ⇄ `"ru"`, `forLanguageTag("pl")` ⇄ `"pl"`).
4. WHERE code reads `UserEntity.locale` (e.g. `EmailLocaleResolver`, the mail senders, `UserService`
   locale validation, user DTOs/mappers) THE call sites SHALL be adapted to the `Locale` type while
   preserving current behavior (RU → Russian, everything else → PL); the `SUPPORTED_LOCALES`
   validation SHALL continue to accept only PL/RU.
5. THE `ClientMessage.originalLocale` and `ClientMessageTranslation.locale` fields SHALL use the same
   `LocaleAttributeConverter`, so message locales and the user locale share one representation.
6. THE migration SHALL be covered by regression tests proving existing lowercase `users.locale` rows
   deserialize to the correct `Locale`, a blank/unknown value yields PL, and the email-locale
   resolution and user create/update paths behave as before.

### Requirement 13 — Audit, regression, and test artifacts

#### Acceptance Criteria

1. WHEN a message is posted, a reply is posted, media is attached, or a client message is marked read
   THEN an audit row SHALL be written via the existing audit mechanism.
2. WHEN this spec ships THEN the FOR-05 / FOR-06-00 behaviors not changed here (the workspace shell,
   `resolveWorkspaceTabs`, `stageOf`, the other execution tabs' placeholders, the notification
   service, the object store, the OCR/Gemini wiring) SHALL continue to pass (regression), AND the
   user/email/locale surfaces touched by the `UserEntity.locale` migration (R12) SHALL continue to
   pass.
3. THE spec SHALL ship its own backend unit + property tests (post validation, authorSide derivation,
   MANAGER-only reply/mark-read, first-read-wins, media type/size validation, translation seam
   fallback, `LocaleAttributeConverter` round-trip + PL fallback, ABAC matrix, seed idempotency),
   frontend unit tests (thread rendering per reader locale, show-original toggle, composer, read
   marking, resolver fixtures), and an API-level `test-cases.md` run against the Dockerized app.
