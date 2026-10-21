# Design Document

## Overview

FOR-06-12-client-communication implements the **admin/internal side** of a per-project
client↔staff conversation: a single ordered message thread owned by the project, where the CLIENT
asks questions and the MANAGER replies, each message auto-translated into the app's supported locales
and optionally carrying media. It introduces one project-scoped aggregate (`ClientMessage` with two
children), a project-scoped service + bespoke controller guarded by a new `CLIENT_MESSAGES` ABAC
resource, a best-effort notification emitter, a pluggable translation seam, and the admin-side
Komunikacja panel that swaps the FOR-06-00 placeholder tab.

The design reuses established patterns verbatim rather than inventing new infrastructure:

- **Project scoping / ABAC** — `ProjectScopedService` + `getProjectIdPath() = "project.id"` +
  `@PermissionResource` + per-handler `@RequiresPermission`, exactly like `WorkerPayoutService` /
  `WorkerPayoutController` (FOR-06-11).
- **Media** — `ImageStorage.storeObject(file, "client-messages")` + a metadata child entity + own
  content-type/size validation + signed download URLs, mirroring `DocumentMediaService` (FOR-05-08)
  and `ProcurementDocumentService` (FOR-06-03).
- **Notifications** — a `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`
  emitter calling `NotificationService.create(recipientUserId, type, body, deepLink)` best-effort,
  resolving recipients from `ProjectMemberDao`, mirroring `WorkReportNotificationEmitter` (FOR-06-01).
- **Pluggable provider seam** — a service interface with a provider-backed impl (reusing the
  `google-genai` Gemini wiring approach from FOR-06-03) and a `@Conditional` disabled fallback,
  mirroring `ImageStorage`/`GcsImageStorage`/`DisabledImageStorage` and `OcrService`/
  `GoogleOcrService`/`DisabledOcrService`.
- **Workspace tab** — the `clientCommunication` slot in `workspaceTabs.ts` (gated
  `CLIENT_MESSAGES`/READ, reserved by FOR-06-00) has its `lazy` swapped from `PlaceholderTab` to the
  real panel; no change to `resolveWorkspaceTabs` / `stageOf` / the `WORKSPACE_TABS` contract shape.
- **Locale type** — all locales are the standard `java.util.Locale` (no custom enum), persisted via a
  shared `LocaleAttributeConverter`. The same change migrates the existing `UserEntity.locale`
  `String → Locale` backwards-compatibly (no DB migration); legacy/unsupported values resolve to PL.

## Architecture

```mermaid
graph TD
    subgraph Frontend
        TAB[clientCommunication tab\nworkspaceTabs.ts] --> PANEL[ClientCommunicationTab panel]
        PANEL --> THREAD[MessageThread\n(per-reader-locale render + show-original)]
        PANEL --> COMPOSER[MessageComposer\n(MANAGER only: text + media)]
        PANEL --> API[clientMessagesApi]
    end
    subgraph Backend
        CTRL[ClientMessageController\n@PermissionResource CLIENT_MESSAGES] --> SVC[ClientMessageService\nProjectScopedService]
        SVC --> DAO[ClientMessageDao]
        SVC --> MEDIA[ClientMessageMediaService\n(storeObject + validation)]
        SVC --> TR[MessageTranslationService seam]
        SVC -. publishes .-> EVT[ClientMessageNotificationEvent]
        EVT -. AFTER_COMMIT .-> EMIT[ClientMessageNotificationEmitter]
        EMIT --> NOTIF[NotificationService.create]
        EMIT --> PMDAO[ProjectMemberDao\n(recipients)]
        MEDIA --> STORE[ImageStorage.storeObject / signedDownloadUrl]
        TR --> GEMINI[GeminiMessageTranslationService\n(configured)]
        TR --> DISABLED[DisabledMessageTranslationService\n(fallback)]
    end
    API --> CTRL
```

### Request flow — post a message

1. Author (CLIENT or MANAGER) POSTs text + optional media to `ClientMessageController`.
2. ABAC gate: `CLIENT_MESSAGES`/CREATE on the project (project scope enforced by the service).
3. `ClientMessageService.post(...)`:
   - resolves the author's `ProjectMemberEntity` → derives `authorSide` (`CLIENT` if role code is
     `CLIENT`, else `STAFF`); rejects a non-MANAGER STAFF reply (R3.2);
   - validates non-empty text or at least one media (R2.2) and project `ACTIVE` (R2.4/R8.1);
   - detects `originalLocale` via `MessageTranslationService.detectLocale(text, authorLocale)` (R6.1);
   - persists the `ClientMessage` + the original-locale `ClientMessageTranslation` (`machine=false`);
   - for each other supported locale, best-effort `translate(...)` → `ClientMessageTranslation`
     (`machine=true`); failures leave the locale untranslated (R6.3);
   - stores media via `ClientMessageMediaService` (R4);
   - writes audit (R12.1) and publishes a `ClientMessageNotificationEvent`.
4. After commit, `ClientMessageNotificationEmitter` fans out the notification (R7).

### Request flow — read the thread

`GET /api/client-messages?projectId=…` → `CLIENT_MESSAGES`/READ → service returns the ordered thread;
for each message it selects the reader's-locale text (translation row for the reader locale, else
`originalText`) and includes `originalText` + `originalLocale` + per-media signed download URLs. For a
MANAGER reader, unread `CLIENT`-side messages are marked read on first open (R5) via a dedicated
mark-read endpoint invoked by the panel (keeps the LIST read side-effect-free).

## Components and Interfaces

### Backend domain

```java
// Project-scoped aggregate root.
@Entity @Table(name = "client_messages")
class ClientMessageEntity extends BaseEntity {
    @Column(name = "project_id", nullable = false) Long projectId;      // getProjectIdPath = "project.id"
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "author_id", nullable = false) UserEntity author;
    @Enumerated(STRING) @Column(name = "author_side", nullable = false) AuthorSide authorSide; // CLIENT|STAFF
    @Column(name = "original_text", nullable = false, columnDefinition = "text") String originalText;
    @Convert(converter = LocaleAttributeConverter.class)
    @Column(name = "original_locale", nullable = false, length = 35) Locale originalLocale;   // java.util.Locale
    @Column(name = "read_at") Instant readAt;                            // first MANAGER read (R5)
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "read_by_id") UserEntity readBy;
    @OneToMany(mappedBy = "message", cascade = ALL, orphanRemoval = true) List<ClientMessageTranslationEntity> translations;
    @OneToMany(mappedBy = "message", cascade = ALL, orphanRemoval = true) List<ClientMessageMediaEntity> media;
}

@Entity @Table(name = "client_message_translations",
        uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "locale"}))
class ClientMessageTranslationEntity extends BaseEntity {
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "message_id", nullable = false) ClientMessageEntity message;
    @Convert(converter = LocaleAttributeConverter.class)
    @Column(nullable = false, length = 35) Locale locale;   // java.util.Locale
    @Column(nullable = false, columnDefinition = "text") String text;
    @Column(nullable = false) boolean machine;   // false = original-locale mirror, true = auto-translation
}

@Entity @Table(name = "client_message_media")
class ClientMessageMediaEntity extends BaseEntity {
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "message_id", nullable = false) ClientMessageEntity message;
    @Column(name = "file_name") String fileName;
    @Column(name = "content_type") String contentType;
    @Column(name = "size_bytes") long sizeBytes;
    @Column(name = "storage_uri") String storageUri;   // ImageStorage object key
    @Enumerated(STRING) @Column(nullable = false) MediaKind kind;  // IMAGE|VIDEO|DOCUMENT
    @ManyToOne(fetch = LAZY) @JoinColumn(name = "uploaded_by_id", nullable = false) UserEntity uploadedBy;
}

enum AuthorSide { CLIENT, STAFF }
enum MediaKind { IMAGE, VIDEO, DOCUMENT }
// No custom locale enum — locales are java.util.Locale, persisted via LocaleAttributeConverter.
```

> **Project FK note.** Following the FOR-06-07/FOR-06-11 precedent, `project_id` is a real FK to
> `projects` with `ON DELETE CASCADE` (the `projects` table exists by the execution stage);
> `getProjectIdPath()` returns `"project.id"` regardless of whether the Java side maps it as a plain
> `Long` or an association — matching `WorkerPayoutService`.

### Locale type, converter, and the `UserEntity.locale` migration (R12)

Locales are the standard `java.util.Locale`; there is no custom locale enum. A single shared
converter stores/reads them as the lowercase language tag the codebase already uses:

```java
@Converter(autoApply = false)
class LocaleAttributeConverter implements AttributeConverter<Locale, String> {
    static final Locale FALLBACK = Locale.forLanguageTag("pl");   // R12.2

    public String convertToDatabaseColumn(Locale locale) {
        return locale == null ? null : locale.toLanguageTag().toLowerCase(Locale.ROOT);  // "pl"/"ru"
    }
    public Locale convertToEntityAttribute(String db) {
        if (db == null || db.isBlank()) return FALLBACK;
        Locale parsed = Locale.forLanguageTag(db.trim());
        return isSupported(parsed) ? parsed : FALLBACK;             // legacy/unsupported → PL (R12.2)
    }
}
```

- **Backwards compatibility (R12.3):** `users.locale` already holds lowercase `"pl"`/`"ru"`;
  `forLanguageTag("ru") ⇄ "ru"` and `forLanguageTag("pl") ⇄ "pl"` round-trip unchanged, so
  `UserEntity.locale` is migrated `String → Locale` with `@Convert(converter =
  LocaleAttributeConverter.class)` and **no DB migration** (the column stays `VARCHAR`). The
  `@Column(length = 5)` on `users.locale` stays as-is (a language tag `"pl"`/`"ru"` fits).
- **Affected user/email/locale call sites (R12.4):** `EmailLocaleResolver` is simplified to take a
  `Locale` (or is kept for the string boundary and fed `locale.getLanguage()`), the mail senders
  (`ThymeleafInvitationMailSender`, `ThymeleafOtpMailSender`) pass the `Locale` through,
  `UserService.validateLocale` keeps the PL/RU `SUPPORTED_LOCALES` check (adapted to `Locale`), the
  `"ru"`/`"pl"` default literals and user DTOs/mappers are updated to `Locale`. Behavior is preserved
  (RU → Russian, everything else → PL).
- **Reader/author locale:** the **reader locale** on a read is the caller's `UserEntity.getLocale()`
  (now a `Locale`); the **author preferred locale** fed to `detectLocale` is the author's
  `UserEntity.getLocale()`. Both already flow through the converter's PL fallback.
- **Supported-locale set:** PL/RU membership stays a validation concern over `Locale` (reuse/centralize
  the existing `{"pl","ru"}` set), not a type guarantee.

### ClientMessageService (project-scoped)

```java
@Service @Transactional
class ClientMessageService
        implements ProjectScopedService<ClientMessageModel, ClientMessageExtendedModel, ClientMessageEntity, Long> {

    @Override public String getProjectIdPath() { return "project.id"; }                 // R8.2
    @Override public Set<Long> allowedProjectIds(Long userId) { return projectAccessCache.get(userId); }

    ClientMessageDto post(Long projectId, PostMessageRequest req, List<MultipartFile> media); // R2, R3, R4, R6
    ThreadView listThread(Long projectId, Locale readerLocale);                               // R6.4, R5.4
    ClientMessageDto markRead(Long messageId);                                                // R5 (MANAGER only)
}
```

- **authorSide derivation (R2.1):** read the caller's `ProjectMemberEntity` for the project; `CLIENT`
  role → `CLIENT`, otherwise `STAFF`. Never from the request body.
- **MANAGER-only reply/mark-read (R3):** a `STAFF` post or a `markRead` requires the caller's project
  role code to be `MANAGER` (ADMIN bypasses). A FOREMAN/other-staff attempt → 403
  `error.clientMessage.reply.notManager` / `error.clientMessage.read.notManager`. Enforced in the
  service on top of the ABAC grant.
- **first-read-wins (R5.1/R5.2):** `markRead` sets `readAt`/`readBy` only when `readAt` is null and
  the message is `CLIENT`-side; otherwise a no-op returning the current state.
- **translation (R6):** after persisting the message + original-locale row, iterate the other
  supported locales and call the seam best-effort; wrap each call so a failure logs and skips that
  locale.

### ClientMessageMediaService

Mirrors `DocumentMediaService`:

```java
@Service
class ClientMessageMediaService {
    static final Set<String> IMAGE_DOC_TYPES = Set.of("image/png","image/jpeg","image/webp","application/pdf");
    static final Set<String> VIDEO_TYPES     = Set.of("video/mp4","video/webm","video/quicktime");
    static final long MAX_IMAGE_DOC_BYTES = DataSize.ofMegabytes(10).toBytes();   // R4.3
    static final long MAX_VIDEO_BYTES     = DataSize.ofMegabytes(100).toBytes();  // R4.3
    static final String NAMESPACE = "client-messages";

    ClientMessageMediaEntity store(ClientMessageEntity msg, MultipartFile file); // validates type+size, storeObject
    String downloadUrl(ClientMessageMediaEntity media);                           // signedDownloadUrl (private)
}
```

- `kind` is derived from the content type (`image/*` → IMAGE, `application/pdf` → DOCUMENT,
  `video/*` → VIDEO). The size cap is chosen by kind (R4.3). Invalid type → 400
  `error.clientMessage.media.invalid`; oversize → 400 `error.clientMessage.media.tooLarge`.
- Read returns a short-lived V4 signed URL (private bucket), matching `ProcurementDocumentService`
  (never a public CDN link).

### MessageTranslationService seam (R10)

```java
interface MessageTranslationService {
    /** Best-effort translation; returns Optional.empty() when unavailable/failed (never throws for provider errors). */
    Optional<String> translate(String sourceText, Locale source, Locale target);
    /** Original-locale detection from the text + the author's preferred locale; always returns a supported Locale (PL/RU). */
    Locale detectLocale(String text, Locale authorPreferred);
    boolean isConfigured();
}

@Service @Conditional(TranslationConfiguredCondition.class)
class GeminiMessageTranslationService implements MessageTranslationService { … }   // google-genai, lazy client, foremen.translation.*

@Service @Conditional(TranslationNotConfiguredCondition.class)
class DisabledMessageTranslationService implements MessageTranslationService {
    public Optional<String> translate(...) { return Optional.empty(); }            // R6.3
    public Locale detectLocale(String text, Locale authorPreferred) {              // fall back to preferred, PL if null/unsupported
        return (authorPreferred != null && isSupported(authorPreferred)) ? authorPreferred : LocaleAttributeConverter.FALLBACK;
    }
    public boolean isConfigured() { return false; }
}
```

- The configured impl reads credentials/model from a `foremen.translation.*` block via a
  `TranslationProperties` record (mirroring `OcrProperties`), builds the Gemini client lazily on
  first use, and prompts for (a) a strict source→target translation and (b) a locale classification
  constrained to the supported set. It does **not** import OCR code (R10.2).
- Both conditions mirror `ImageStorageConfiguredCondition` / `…NotConfiguredCondition`.

### ClientMessageController (bespoke, project-scoped)

```java
@RestController @RequestMapping("/api/client-messages") @PermissionResource("CLIENT_MESSAGES")
class ClientMessageController {
    @GetMapping                    @RequiresPermission(resource="CLIENT_MESSAGES", operation="READ")   ThreadView list(@RequestParam Long projectId);
    @PostMapping(consumes=MULTIPART) @RequiresPermission(resource="CLIENT_MESSAGES", operation="CREATE") ClientMessageDto post(@RequestParam Long projectId, @RequestPart PostMessageRequest body, @RequestPart(required=false) List<MultipartFile> media);
    @PostMapping("/{id}/read")     @RequiresPermission(resource="CLIENT_MESSAGES", operation="UPDATE")  ClientMessageDto markRead(@PathVariable Long id);
}
```

Every handler carries `@RequiresPermission` so `PermissionAnnotationValidator` classifies the
controller COMPLETE at startup (R8.3). The `markRead` action maps onto UPDATE (a state change on an
existing row) — no new ABAC operation.

### Frontend

- `workspaceTabs.ts`: swap the `clientCommunication` entry's `lazy: PlaceholderTab` → the real
  `ClientCommunicationTab` (lazy import). No other field changes.
- `ClientCommunicationTab` panel: fetches the thread for the current project; renders `MessageThread`
  (each message bubble shows author, a client/staff badge, timestamp, the **reader-locale** text, a
  "show original" quick-view icon when `shownText !== originalText`, and media thumbnails/links);
  renders `MessageComposer` only for a MANAGER (text + media upload + send); marks unread client
  messages read on first open for a MANAGER; shows the unread count.
- `clientMessagesApi`: `list(projectId)`, `post(projectId, {text}, files)`, `markRead(id)`.

## Data Model / Migrations

Four new changesets, registered **last** in `changelog.xml` after 182, idempotent via
`NOT tableExists` + `onFail="MARK_RAN"` (tables) and `sqlCheck COUNT(*)=0` + `MARK_RAN` (seed):

| # | Changeset | Purpose |
|---|-----------|---------|
| 183 | `183-create-client-messages.xml` | `client_messages` (project FK CASCADE, author FK, author_side, original_text TEXT, original_locale VARCHAR(35) — lowercase language tag via the converter, read_at/read_by nullable, audit); index on project_id. |
| 184 | `184-create-client-message-translations.xml` | `client_message_translations` (message FK CASCADE, locale, text TEXT, machine BOOL) UNIQUE(message_id, locale). |
| 185 | `185-create-client-message-media.xml` | `client_message_media` (message FK CASCADE, file_name, content_type, size_bytes, storage_uri, kind, uploaded_by FK). |
| 186 | `186-seed-client-messages-resource.xml` | Seed `CLIENT_MESSAGES` resource + role matrix (R8.4). |

> **No `users.locale` migration changeset.** The `UserEntity.locale` `String → Locale` change (R12)
> is a **Java-only** change via `@Convert(LocaleAttributeConverter)`: the column stays `VARCHAR`
> holding the existing lowercase `"pl"`/`"ru"` values, which the converter round-trips. No DDL/DML
> changeset is added for it.

### ABAC seed (186) — role matrix (R8.4)

Two-changeSet resource+grant pattern per `158`/`165`/`175`:

- **ChangeSet A** — insert `resources` row `CLIENT_MESSAGES` (name_ru «Сообщения клиента», name_pl
  «Wiadomości klienta», descriptions), guarded by `COUNT(*) WHERE code='CLIENT_MESSAGES' = 0`.
- **ChangeSet B (ADMIN)** — CREATE, READ, UPDATE, DELETE.
- **ChangeSet C (CLIENT)** — CREATE, READ.
- **ChangeSet D (MANAGER)** — CREATE, READ, UPDATE.
- **ChangeSet E (FOREMAN)** — READ.
- **ChangeSet F (FINANCIER / ESTIMATOR other admin-staff)** — READ.
- **WORKER** — deny-by-default (commented, not seeded).

The MANAGER-only reply/mark-read and CLIENT-side-only authoring are refined in the service above the
matrix (R3, R8.5); project scope is enforced by `ProjectScopedService` (`getProjectIdPath()` =
`"project.id"`), not the matrix.

## Notifications (R7)

| Type key | Trigger | Recipients | Deep-link | Body |
|----------|---------|------------|-----------|------|
| `notification.clientMessage.fromClient` | CLIENT posts a message | every ACTIVE MANAGER + ACTIVE FOREMAN member | `/projects/{projectId}/clientCommunication?message={messageId}` | short message preview (reader-locale resolved on render) |
| `notification.clientMessage.fromStaff` | MANAGER posts a reply | the project's CLIENT member(s) | same | short preview |

`ClientMessageNotificationEmitter` is an `@TransactionalEventListener(AFTER_COMMIT,
fallbackExecution = true)` on `ClientMessageNotificationEvent{ projectId, messageId, authorSide }`;
it resolves recipients from `ProjectMemberDao.findByProjectId` filtered by Assignment_Status ACTIVE +
role code, calls `notificationService.create(recipientUserId, type, body, deepLink)` wrapped
per-recipient in try/catch (log + swallow), and never blocks or rolls back the committed post (R7.4).
No recipient of the relevant kind ⇒ notify nobody, not an error (R7.3).

## i18n (R11)

- **Backend:** no `nameRU`/`namePL` on the message entities (transactional); message localization is
  the `ClientMessageTranslation` per-locale rows, keyed by `java.util.Locale` (PL/RU). The
  `CLIENT_MESSAGES` resource row carries RU/PL name/description. Every `error.clientMessage.*` code
  gets PL + RU bundle entries. The shared `LocaleAttributeConverter` + the `UserEntity.locale`
  migration (R12) keep one locale representation across users and messages.
- **Frontend:** every new string (tab label `workspace.tab.clientCommunication`, panel title, side
  badges, read/unread + unread count, composer labels/placeholders, send, "show original" + original
  locale label, media labels, empty/loading, success/error toasts) added to both `pl.json` and
  `ru.json` with real values; locale-parity test passes. The two `notification.clientMessage.*` keys
  get PL + RU titles.
- **Auto-translation** makes each message readable in both PL and RU regardless of original locale
  when the seam is configured; when unconfigured the reader sees the original text with its locale
  labeled (R6.5).

## Error Handling

| Code | HTTP | Condition |
|------|------|-----------|
| `error.clientMessage.empty` | 400 | post with blank text and no media (R2.2) |
| `error.clientMessage.reply.notManager` | 403 | non-MANAGER admin-staff posts a STAFF reply (R3.2) |
| `error.clientMessage.read.notManager` | 403 | non-MANAGER marks a client message read (R3.3) |
| `error.clientMessage.media.invalid` | 400 | disallowed media content type (R4.2) |
| `error.clientMessage.media.tooLarge` | 400 | media exceeds the per-kind size cap (R4.3) |
| `error.entity.not.found` | 404 | unknown message id |

Translation/detection provider failures are **not** surfaced as errors — logged and treated as "no
translation" / "fall back to preferred locale" (R6.3, R10.4).

## Testing Strategy

- **Backend unit/property:** authorSide derivation; MANAGER-only reply + mark-read guard;
  first-read-wins; empty-post rejection; media type/size validation (10 MB image/doc, 100 MB video);
  translation seam fallback (disabled → original-only, provider failure swallowed); per-reader-locale
  thread projection; notification emitter recipient resolution (fromClient → MANAGER+FOREMAN;
  fromStaff → CLIENT); ABAC matrix + startup annotation-completeness; seed idempotency (double-run);
  `LocaleAttributeConverter` round-trip (`"pl"`/`"ru"` ⇄ `Locale`) + blank/unsupported → PL, and the
  `UserEntity.locale` migration regression (email-locale resolution, user create/update preserve
  behavior).
- **Frontend unit:** thread render in each reader locale; show-original toggle visibility; composer
  visible only for MANAGER; mark-read-on-open for MANAGER; resolver fixture (tab visible on ACTIVE
  with `CLIENT_MESSAGES`/READ, hidden otherwise); locale-parity.
- **API `test-cases.md`:** against the Dockerized app — CLIENT posts → MANAGER notified + reads +
  replies → CLIENT notified; FOREMAN read-only; media upload/download (signed URL); translation
  visible per locale (or original-only when unconfigured); ABAC denials.
- **Regression:** FOR-05-01 shell, `resolveWorkspaceTabs`/`stageOf`, other execution placeholders,
  `NotificationService`, `ImageStorage`, OCR/Gemini wiring unchanged; the user/email/locale surfaces
  touched by the `UserEntity.locale` migration (R12) continue to pass.
