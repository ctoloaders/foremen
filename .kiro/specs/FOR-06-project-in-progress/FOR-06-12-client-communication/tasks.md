# Implementation Plan: FOR-06-12 — Client communication

## Overview

This plan delivers the admin/internal side of a per-project client↔staff conversation as a full
vertical slice: the project-scoped `ClientMessage` aggregate (+ `ClientMessageTranslation` and
`ClientMessageMedia` children) → the pluggable `MessageTranslationService` seam (Gemini-backed impl +
disabled fallback) → the media service (`ImageStorage.storeObject` + own type/size validation) →
the lifecycle `ClientMessageService` (`ProjectScopedService`) → the `@PermissionResource(
"CLIENT_MESSAGES")` `ClientMessageController` → DTOs/mappers → the `CLIENT_MESSAGES` ABAC seed → the
best-effort `ClientMessageNotificationEmitter`, and the frontend **Komunikacja** panel wired into the
already-reserved `clientCommunication` execution tab.

**Reused, not re-implemented.** The generic `NotificationService.create` + the in-app Bell
(FOR-05-07 R13), the `ImageStorage` object store + signed download URLs (FOR-04-17) with the
`DocumentMediaService` attachment pattern (FOR-05-08), the Google Gen AI (Gemini) wiring approach
(`google-genai` SDK, `foremen.*` properties, lazy client, `@Conditional` disabled fallback) from
FOR-06-03 OCR (approach only — **no OCR code import**), `ProjectScopedService` + ABAC (FOR-03), the
FOR-05-09 `ProjectMember` crew (authorSide + recipient resolution by `projectRole.code` +
`AssignmentStatus.ACTIVE`), and the FOR-06-00 reserved `clientCommunication` tab slot are consumed
as-is.

**Seam-first.** The shared `LocaleAttributeConverter` and the translation seam (interface + disabled
fallback) land before the service so the service composes configuration-agnostic dependencies; the
Gemini impl is isolated and provider-only.

**Standard `Locale` + user-locale migration (cross-cutting).** All locales are `java.util.Locale`
(no custom enum), persisted via the shared `LocaleAttributeConverter` (lowercase language tag;
null/blank/unsupported → PL). The same converter migrates the existing `UserEntity.locale`
`String → Locale` **backwards-compatibly with no DB changeset** (the column keeps its lowercase
`"pl"`/`"ru"` values), touching `EmailLocaleResolver`, the mail senders, `UserService`, and user
DTOs/mappers — covered by regression tests.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — changesets `183`–`186`, the `ClientMessage*` entities + enums, the shared
  `LocaleAttributeConverter` + the `UserEntity.locale` `String → Locale` migration, the translation
  seam + impls, the media service, the service/controller/DTOs/mappers, the notification
  event/emitter, the `CLIENT_MESSAGES` ABAC seed, the backend PL+RU message bundle, and this spec.
  Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `features/project-execution/client-communication/`
  module, the `clientCommunication` tab `lazy` swap, the api client/hook, and the `pl.json`/`ru.json`
  keys. Commit from inside `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** New changesets begin at `183` (current changelog tail is `182` —
`182-seed-payroll-resource.xml`; if a sibling spec lands further changesets first, use the next free
`NNN`). Each follows the `NOT tableExists` / `SELECT COUNT(*)` / `onFail="MARK_RAN"` idempotency
convention and is appended **last** in `changelog.xml`. The `186-seed-client-messages-resource`
changeset follows the `017`/`158`/`175` two-changeSet resource+grant pattern.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-12-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks.

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 1.2, 1.3, 2.1 — the three table changesets are distinct files
  (184 FK → 183, 185 FK → 183, but as Liquibase includes they can be authored together; ordering in
  `changelog.xml` is 183 then 184/185); and the shared `LocaleAttributeConverter` is persistence-free
  and independent.
- **Wave 2 (parallel):** 2.2 (seam interface + disabled fallback), 2.5 (`UserEntity.locale` →
  `Locale` migration) — both depend only on the converter (2.1); distinct files (seam vs. user/email
  surfaces).
- **Wave 3 (parallel):** 3.1 (JPA entities + enums), 2.3 (converter + disabled-seam unit tests),
  2.4 (Gemini-backed translation impl + config/properties) — entities need the table changesets +
  converter; the tests need the converter + seam; the Gemini impl needs the seam interface. Distinct
  files.
- **Wave 4 (parallel):** 3.2 (DTOs + mappers), 4.1 (`ClientMessageMediaService`) — DTOs need the
  entities; the media service needs the media entity + `ImageStorage`. Distinct files.
- **Wave 5:** 5.1 (`ClientMessageService`) — needs entities, DTOs, the media service, and the
  translation seam.
- **Wave 6 (parallel):** 6.1 (`ClientMessageController`), 7.1 (`ClientMessageNotificationEmitter` +
  event), 9.1 (frontend `clientCommunication` tab `lazy` swap scaffold) — distinct files/repos; the
  controller needs the service, the emitter needs the event published by the service (wave 5), the
  tab swap is independent.
- **Wave 7:** 8.1 (`CLIENT_MESSAGES` ABAC seed) — the resource/grant changeset; verified against the
  live controller at startup.
- **Wave 8:** 9.2 (frontend Komunikacja module) — needs the controller + ABAC seed live + the tab
  swap.
- **Wave 9 (parallel):** 10.1 (backend unit tests), 10.2 (backend integration + ABAC/startup tests),
  11.1 (i18n PL+RU + backend messages) — distinct test/i18n surfaces, independent. (10.1 also covers
  the converter round-trip + the `UserEntity.locale` migration regression, R12.6/R13.)
- **Wave 10:** 12.1 (Author test-cases.md) — depends on the full slice being testable end-to-end.

## Tasks

- [x] 1. Data model (changesets)
  - [x] 1.1 Create `183-create-client-messages.xml`
    - `client_messages` (project_id FK → projects ON DELETE CASCADE, author_id FK, author_side
      VARCHAR, original_text TEXT NOT NULL, original_locale VARCHAR(35) NOT NULL — lowercase language
      tag stored via `LocaleAttributeConverter`, read_at TIMESTAMPTZ NULL, read_by_id FK NULL, audit
      cols); index on project_id. Idempotent (`NOT tableExists` + `MARK_RAN`); registered last in
      `changelog.xml`.
    - _Requirements: 1.1, 1.4_
    - _Depends on: none_
  - [x] 1.2 Create `184-create-client-message-translations.xml`
    - `client_message_translations` (message_id FK → client_messages ON DELETE CASCADE, locale
      VARCHAR(35) — language tag via `LocaleAttributeConverter`, text TEXT NOT NULL, machine BOOLEAN
      NOT NULL, audit) UNIQUE(message_id, locale). Idempotent; registered after 183.
    - _Requirements: 1.2, 1.6_
    - _Depends on: 1.1_
  - [x] 1.3 Create `185-create-client-message-media.xml`
    - `client_message_media` (message_id FK → client_messages ON DELETE CASCADE, file_name,
      content_type, size_bytes BIGINT, storage_uri, kind VARCHAR NOT NULL, uploaded_by_id FK NOT
      NULL, audit). Idempotent; registered after 184.
    - _Requirements: 1.3, 1.6_
    - _Depends on: 1.1_

- [x] 2. Locale type + translation seam (R10, R12)
  - [x] 2.1 Shared `LocaleAttributeConverter`
    - `AttributeConverter<Locale, String>`: write `toLanguageTag().toLowerCase()` (`"pl"`/`"ru"`),
      read `forLanguageTag`, resolve null/blank/unsupported → PL (`FALLBACK`). Centralize the PL/RU
      supported set used by the converter and validation.
    - _Requirements: 12.1, 12.2_
    - _Depends on: none_
  - [x] 2.2 Define `MessageTranslationService` interface + `DisabledMessageTranslationService`
    - Interface `translate(text, source: Locale, target: Locale) → Optional<String>`,
      `detectLocale(text, authorPreferred: Locale) → Locale`, `isConfigured()`. Disabled fallback
      (`@Conditional` `TranslationNotConfiguredCondition`) returns empty translation and falls back to
      the author-preferred locale (PL when null/unsupported).
    - _Requirements: 10.1, 10.3, 6.3_
    - _Depends on: 2.1_
  - [x] 2.3 Unit-test the converter + the disabled fallback
    - Converter: `"pl"`/`"ru"` ⇄ `Locale`, blank/`"en"`/null → PL. Disabled seam → empty translation,
      detect falls back to preferred (PL when null/unsupported).
    - _Requirements: 12.1, 12.2, 10.3, 6.1_
    - _Depends on: 2.1, 2.2_
  - [x] 2.4 Implement `GeminiMessageTranslationService` + `TranslationProperties` + conditions
    - `@Conditional(TranslationConfiguredCondition)`; `foremen.translation.*` properties
      (credentials/model) via a record; lazy `google-genai` client; a strict translate prompt + a
      locale-classification prompt constrained to PL/RU (returns a `Locale`); per-call failure logged
      → empty / preferred. No OCR code import.
    - _Requirements: 10.2, 10.4_
    - _Depends on: 2.2_
  - [x] 2.5 Migrate `UserEntity.locale` `String → Locale` (cross-cutting, R12)
    - `@Convert(converter = LocaleAttributeConverter.class)` on `UserEntity.locale` (no DB changeset);
      adapt `EmailLocaleResolver`, the mail senders, `UserService.validateLocale` + default literals,
      and user DTOs/mappers to `Locale` while preserving behavior (RU → Russian, else → PL). Reuse the
      converter from 2.1.
    - _Requirements: 12.3, 12.4, 12.5_
    - _Depends on: 2.1_

- [x] 3. Entities + DTOs
  - [x] 3.1 JPA entities + enums
    - `ClientMessageEntity`, `ClientMessageTranslationEntity`, `ClientMessageMediaEntity`,
      `AuthorSide`, `MediaKind`; `originalLocale`/`locale` are `java.util.Locale` with
      `@Convert(LocaleAttributeConverter)`; cascade/orphan-removal on children; no `nameRU`/`namePL`
      columns.
    - _Requirements: 1.1, 1.2, 1.3, 11.1, 12.5_
    - _Depends on: 1.1, 1.2, 1.3, 2.1_
  - [x] 3.2 DTOs + mappers (`ThreadView`, `ClientMessageDto`, `PostMessageRequest`, media summary)
    - Per-reader-locale text selection (keyed by `Locale`) + `originalText`/`originalLocale` +
      per-media signed URL + read state + unread count in the read model.
    - _Requirements: 6.4, 5.4, 4.4_
    - _Depends on: 3.1_

- [x] 4. Media service
  - [x] 4.1 `ClientMessageMediaService`
    - Own `ALLOWED` content types (image/pdf + video), per-kind caps (10 MB image/doc, 100 MB video),
      `kind` from content type, `storeObject(file, "client-messages")`, signed download URL,
      `deleteIfOrphan`. 400 `error.clientMessage.media.invalid` / `…tooLarge`.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_
    - _Depends on: 3.1_

- [x] 5. Service
  - [x] 5.1 `ClientMessageService` (`ProjectScopedService`, `getProjectIdPath()="project.id"`)
    - `post` (authorSide derivation, empty-post + ACTIVE-stage validation, locale detect, original +
      best-effort machine translations, media, audit, publish event); `listThread` (per-reader
      locale); `markRead` (MANAGER-only, first-read-wins). MANAGER-only reply/mark-read enforced on
      top of ABAC.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4, 5.1, 5.2, 5.3, 6.1, 6.2, 6.6, 8.5, 13.1_
    - _Depends on: 3.2, 4.1, 2.2_

- [x] 6. Controller
  - [x] 6.1 `ClientMessageController` (`@PermissionResource("CLIENT_MESSAGES")`)
    - `GET` list (READ), `POST` multipart post (CREATE), `POST /{id}/read` mark-read (UPDATE); every
      handler `@RequiresPermission`; startup annotation-complete.
    - _Requirements: 8.3, 9.*_
    - _Depends on: 5.1_

- [x] 7. Notifications
  - [x] 7.1 `ClientMessageNotificationEvent` + `ClientMessageNotificationEmitter`
    - `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`; fromClient →
      ACTIVE MANAGER+FOREMAN, fromStaff → CLIENT; deep-link
      `/projects/{projectId}/clientCommunication?message={id}`; per-recipient try/catch (log+swallow);
      no-recipient = no-op.
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_
    - _Depends on: 5.1_

- [x] 8. ABAC seed
  - [x] 8.1 Create `186-seed-client-messages-resource.xml`
    - Resource `CLIENT_MESSAGES` (RU/PL name+description) + grants: ADMIN CRUD; CLIENT CREATE+READ;
      MANAGER CREATE+READ+UPDATE; FOREMAN READ; other admin-staff (FINANCIER/ESTIMATOR) READ; WORKER
      deny-by-default (commented). Idempotent two-changeSet pattern; registered after 185.
    - _Requirements: 8.1, 8.2, 8.4_
    - _Depends on: 6.1_

- [x] 9. Frontend
  - [x] 9.1 Swap the `clientCommunication` tab `lazy` + scaffold `ClientCommunicationTab`
    - In `workspaceTabs.ts` swap `PlaceholderTab` → lazy `ClientCommunicationTab`; add the
      `clientMessagesApi` client (`list`/`post`/`markRead`). No contract/resolver change.
    - _Requirements: 8.6, 9.1_
    - _Depends on: none_
  - [x] 9.2 Komunikacja panel (thread + composer)
    - `MessageThread` (author, client/staff badge, timestamp, reader-locale text, show-original
      quick-view, media); `MessageComposer` (MANAGER-only text+media+send); mark-read on first open
      for MANAGER; unread count; inline validation errors.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 6.4, 6.5, 5.4_
    - _Depends on: 6.1, 8.1, 9.1_

- [x] 10. Backend tests
  - [x] 10.1 Unit/property tests
    - authorSide derivation; MANAGER-only reply+mark-read; first-read-wins; empty-post; media
      type/size; translation fallback (disabled/empty, failure swallowed); per-reader-locale
      projection; `LocaleAttributeConverter` round-trip (`"pl"`/`"ru"` ⇄ `Locale`) + blank/unknown →
      PL; `UserEntity.locale` migration regression (email-locale resolution + user create/update
      preserve behavior).
    - _Requirements: 2.*, 3.*, 4.2, 4.3, 5.*, 6.*, 10.3, 10.4, 12.1, 12.2, 12.6, 13.3_
    - _Depends on: 5.1, 4.1, 7.1, 2.3, 2.5_
  - [x] 10.2 Integration + ABAC/startup tests
    - ABAC matrix per role; project-scope isolation; seed idempotency (double-run); notification
      recipient resolution; `PermissionAnnotationValidator` startup-complete.
    - _Requirements: 8.*, 7.1, 7.2, 13.2, 13.3_
    - _Depends on: 8.1, 6.1, 7.1_

- [x] 11. i18n
  - [x] 11.1 PL+RU frontend keys + backend message bundle + notification titles
    - All panel/composer/badge/toast keys in `pl.json` + `ru.json` (parity); every
      `error.clientMessage.*` PL+RU; the two `notification.clientMessage.*` titles PL+RU.
    - _Requirements: 11.2, 11.3, 11.4, 11.5_
    - _Depends on: 9.2, 6.1_

- [x] 12. Author test-cases.md
  - [x] 12.1 Create `test-cases.md`
    - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
      (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression
      group, MD report template). This is an API + UI spec: Part A = API tests against the Dockerized
      app (post/list/read/reply, media upload+signed download, ABAC denials, notifications,
      translation per locale / original-only when unconfigured); Part B = browser-engine scenarios
      for the Komunikacja panel (thread render per locale, show-original, MANAGER composer, read
      marking). Result artifacts are MD reports with tables.
    - _Requirements: 13.3, and the requirements covered by the test cases (1–12)_
    - _Depends on: 9.2, 10.1, 10.2, 11.1_

- [~] 13. Checkpoint — full verification + two-repo commit
  - Run the affected backend test classes (`--tests`, redirected log) including the converter +
    `UserEntity.locale` migration regression; frontend unit tests + locale parity; confirm the tab
    appears on an ACTIVE project for permitted roles and is hidden otherwise. Commit the root repo
    (changesets/entities/converter+user-migration/services/seed/spec) and the `foremen-frontend/`
    repo (module/tab swap/locales) separately per `.kiro/steering/git-repo-structure.md`. Commit only
    when the user asks.
  - _Requirements: 13.1, 13.2, 13.3_
  - _Depends on: all above_
