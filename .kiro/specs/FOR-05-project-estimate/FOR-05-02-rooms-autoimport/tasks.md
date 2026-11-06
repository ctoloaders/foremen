# Implementation Plan: FOR-05-02 — Rooms auto-import (AI), room media and wall plan

## Overview

Backend (root repo, `foremen-backend/`) adds an asynchronous Gemini-based import pipeline, a draft result contract, room media and `importMedia` support in the bulk save. Frontend (nested repo `foremen-frontend/`, committed separately) adds the "Generate rooms" flow on the Rooms tab, the room-media section and the SVG wall plan.

Test execution rule (workspace standard): run only the affected test classes with `--tests`, redirect to `/tmp/for-05-02-autoimport-test.log`, and read the JUnit XML under `foremen-backend/build/test-results/test/`. Never run the full suite unless the user asks. Frontend: run only the affected Vitest files (`vitest --run <file>`).

## Tasks

- [x] 1. Foundations
  - [x] 1.1 Liquibase `213-create-rooms-import-jobs.xml` + `RoomsImportJobEntity`, `RoomsImportStatus`, `RoomsImportJobDao`
    - Table per design B3 (jsonb `inputs`/`result`/`stage_log`, `applied_at`, **UNIQUE index on `project_id`**, index on `(status, expires_at)`), `onFail="MARK_RAN"` precondition, registered last in `changelog.xml`. DAO gets `findByProjectId` and `findByProjectIdForUpdate`.
    - _Requirements: 2.1, 2.3, 2.4, 16.1, 19.1_
    - _Depends on: none_
  - [x] 1.2 Liquibase `214-create-room-media.xml` + `215-add-rooms-ai-imported.xml`, entities and room DTO fields
    - Table `room_media` modelled on `document_media` (`RoomMediaEntity`, `RoomMediaKind`, `RoomMediaDao`), FK `ON DELETE CASCADE`, index on `room_id`; `rooms.ai_imported BOOLEAN NOT NULL DEFAULT false` (guarded by `not columnExists`) with `RoomEntity.aiImported`; `aiImported` added to `RoomDtoModel`/`RoomDtoExtendedModel` (read only, ignored by update mappers) and an optional `aiImported` list filter; both changesets registered after 213.
    - _Requirements: 10.1, 22.1, 22.3, 22.4_
    - _Depends on: 1.1_
  - [x] 1.3 Dependencies and configuration
    - Add pinned `poi-scratchpad` (same version as `poi-ooxml`) to `build.gradle`/`gradle.properties`; add `foremen.rooms-import.*` block and multipart limits (`max-file-size: 20MB`, `max-request-size: 220MB`) to `application.yml`; create `RoomsImportProperties` record (nested `Llm`, `Limits`, `Ttl`, `Validation`) following `OcrProperties`, plus `RoomsImportConfig` with the `roomsImportTaskExecutor` bean. Verify existing upload endpoints still enforce their own smaller limits (run their existing tests).
    - _Requirements: 1.4, 1.5, 1.7, 13.1, 13.3_
    - _Depends on: none_
  - [x] 1.4 Extract `GeminiClientFactory` from `GoogleOcrService`
    - Move `buildGenAiClient`/`resolveCredentials` logic to `com.foremen.service.ai.GeminiClientFactory`; `GoogleOcrService` delegates (behaviour-preserving). Run the existing OCR unit tests.
    - _Requirements: 13.1_
    - _Depends on: none_
  - [x] 1.5 `RoomGeometry.Opening.offset` (backend)
    - Add nullable `Double offset`; `RoomService` geometry validation accepts it when `≥ 0`, rejects negative with `error.room.geometry.invalid`; confirm `RoomCalculationService` ignores it; existing geometry property tests stay green.
    - _Requirements: 12.8_
    - _Depends on: none_

- [x] 2. Pure logic and extraction
  - [x] 2.0 Result model records and warning codes
    - `Warning`, `Severity`, `DraftRoom`, `DraftMedia`, `SourceRef`, `ImportResult`, `InputInfo`, `ModelInfo`, `ImportMediaRef`, and a `WarningCodes` constants class (the single source of truth for the codes listed in Requirements 3–9).
    - _Requirements: 7.1, 8.1, 8.2_
    - _Depends on: none_
  - [x] 2.1 `GeometryNormalizer` + unit and property tests
    - Unit conversion, vertex dedup/ordering, wall alignment, opening filtering (`OPENING_DROPPED`), rectangle synthesis from text dimensions (`GEOMETRY_SYNTHESIZED`); properties: idempotence, area scaling, `walls.size == vertices.size`.
    - _Requirements: 5.4_
    - _Depends on: 1.5, 2.0_
  - [x] 2.2 `DraftValidator` and rule classes + unit and property tests
    - All rules of Requirement 7.1 with `suggestedFix`, plus the pure `DuplicateOfExistingRule` (`POSSIBLE_DUPLICATE_OF_EXISTING`, input: draft rooms + existing `(id, label, roomTypeId)` triples); properties 1–3 of the design (calculated authority, never throws / never mutates, no false `AREA_MISMATCH`).
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 3.2, 3.5, 4.4, 21.3_
    - _Depends on: 1.5, 2.0_
  - [x] 2.3 `RoomTypeResolver` + unit tests
    - Validate model-supplied id; RU/PL/code name matching; `ROOM_TYPE_UNRESOLVED` with up to three candidates.
    - _Requirements: 4.1, 4.2, 4.3_
    - _Depends on: 2.0_
  - [x] 2.4 `RoomCropper` + unit tests
    - Normalised-bbox crop with margin and clamping, PNG output; `CROP_UNAVAILABLE` fallback for undecodable/HEIC.
    - _Requirements: 9.1, 9.2_
    - _Depends on: 2.0_
  - [x] 2.5 Input extractors + unit tests with fixtures
    - Text (encoding detection), DOCX/DOC, XLSX/XLS, PDF text + rasterize-on-low-text (page limit up front), image decode + EXIF orient + conversion; content sniffing helper; `FILE_UNREADABLE` handling; fixtures under `src/test/resources/roomsimport/`.
    - _Requirements: 1.4, 1.5, 6.1, 6.2, 6.3, 6.4, 6.5, 14.3_
    - _Depends on: 1.3, 2.0_

- [x] 3. AI integration
  - [x] 3.1 `RoomsVisionClient`, JSON schemas, prompts, `GeminiRoomsVisionClient`, `DisabledRoomsVisionClient`, `StubRoomsVisionClient`
    - Structured-output schemas for detect and measure; response validation with one bounded retry; per-call timeout; conditional wiring on `foremen.rooms-import.enabled` and a `stub` switch for tests/Docker; no user content in INFO logs. The stub is switched by `FOREMEN_ROOMS_IMPORT_LLM_STUB=true` and selects canned answers by the markers listed in `test-cases.md` (`STUB:THREE_ROOMS`, `STUB:AREA_MISMATCH`, `STUB:OPENINGS_TOO_BIG`, `STUB:UNKNOWN_TYPE`, `STUB:TEXT_ONLY_SCALARS`, `STUB:IMAGE_CROPS`, `STUB:ROOM_COUNT_MISMATCH`, `STUB:LLM_DOWN`, plus the modifier `STUB:SLOW=<seconds>` that delays the measuring stage) found in the prompt text or file name.
    - _Requirements: 3.1, 5.1, 13.1, 13.2, 13.4, 13.5_
    - _Depends on: 1.3, 1.4, 2.0_

- [x] 4. Pipeline, API and media (backend)
  - [x] 4.1 `RoomsImportService`, `RoomsImportWorker`, `RoomsImportPipeline`, `RoomsImportSweepService`
    - Job creation + input storage (server-generated keys under `rooms-import/{jobId}/`), after-commit event, `REQUIRES_NEW` stage commits, orchestration of extract → detect → classify → measure → validate, count/dedup/mismatch warnings, bounded measuring concurrency, per-room failure isolation, crop storage as draft media, sweep (stuck timeout, TTL cleanup of jobs, inputs and unattached media, orphan objects of cancelled jobs), restart recovery. **Single-job rule** (design B2a): start locks the project's job row, throws `429 error.roomsimport.in_progress` (params `jobId`, `stage`, `startedAt`) for an active job, replaces a terminal one, maps a unique-violation race to the same `429`; **cancel** = `RoomsImportService.cancel(jobId)` (conditional delete; `not_cancellable` for a terminal job) with `markStage` treating 0 updated rows as `JobCancelledException` and a `cancellation.assertActive(jobId)` check before each stage and before every per-room Gemini call (no new AI calls after cancel; unit test with a call-counting stub client); `RoomsImportJobTerminator.complete/fail` with status-conditional terminal updates returning the affected row count (used by the worker and the sweep); `findCurrent(projectId)`; loads existing rooms for the duplicate warning; never touches existing rooms.
    - _Requirements: 1.3, 1.8, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.3, 3.4, 3.5, 4.4, 5.1–5.6, 8.1–8.4, 9.1, 9.2, 13.3, 16.1, 16.2, 16.3, 19.1–19.7, 21.1–21.3, 24.3, 24.7_
    - _Depends on: 1.1, 2.1, 2.2, 2.3, 2.4, 2.5, 3.1_
  - [x] 4.1a `RoomsImportNotificationEmitter` and its hook into the terminator
    - Best-effort emitter (`emitCompleted`, `emitFailed(reason)`) calling `NotificationService.create(createdById, "notification.roomsImport.completed" | ".failed", null | reasonCode, "/projects/{projectId}/rooms")`, invoked by `RoomsImportJobTerminator` (created in 4.1) only when its status-conditional terminal update changed exactly one row — from both the worker and the sweep, so a stuck-job timeout also notifies, a cancelled job never does, and a worker/sweep race notifies once. Unit tests: emitter (type, deep-link, body, swallows exceptions, null recipient), terminator integration (0-row no-op ⇒ no emission).
    - _Requirements: 17.1, 17.1a, 17.2–17.8_
    - _Depends on: 4.1_
  - [x] 4.2 `RoomsImportController` (start, current, get, cancel, discard, draft media content)
    - Multipart validation (empty input, types, counts, sizes), `202` start, **`429` with `Retry-After` and `params`** (verify the shared `ForemenApiException`/handler supports params and an extra header; extend with a covering test if not), `503` disabled path with no job row, `GET /api/rooms/project/{id}/autoimport/current` (`200`/`204`), `POST /api/rooms/autoimport/{jobId}/cancel` (`204` / `409 error.roomsimport.not_cancellable` / `404`) and `DELETE` as discard-or-cancel, annotation on every handler with `@PermissionResource("ROOMS")`, project-access checks and `404` for foreign jobs; slice test for path precedence versus `/api/rooms/{id}`.
    - _Requirements: 1.1–1.6, 1.8, 2.1, 2.2, 2.5, 2.6, 9.3, 14.1, 14.2, 14.3, 14.4, 16.4, 19.4, 20.8, 24.1, 24.2, 24.4_
    - _Depends on: 4.1_
  - [x] 4.3 Room media backend
    - `RoomMediaService`, `RoomMediaController` (list, upload, content, delete) with type/size/count validation; grouped `mediaCount` added to `RoomDtoModel` without N+1; cascade delete of rows and best-effort object delete on room delete; audit.
    - _Requirements: 10.1–10.6, 14.1, 14.2_
    - _Depends on: 1.2_
  - [x] 4.4 `importMedia` / `importJobId` on bulk create, AI marker, `skippedMedia` / `skippedImportRefs`
    - Add `importMedia` and `importJobId` to `RoomCreateRequest` + `RoomControllerMapper` + `RoomServiceExtendedModel`; `RoomMediaAttachService` (same-project check, copy to `rooms/{roomId}/`, soft failure); `RoomImportLinkService` (verify same-project job, set `aiImported`, set job `applied_at`, soft failure); additive `skippedMedia` and `skippedImportRefs` in `RoomsSaveService.Result` and `RoomsSaveResponse`; `aiImported` never mapped from update requests; confirm requests without the new fields behave exactly as before and that no import code path updates or deletes existing rooms.
    - _Requirements: 9.4, 9.5, 9.6, 9.7, 21.1, 21.4, 21.5, 22.2, 22.3, 22.6, 22.7_
    - _Depends on: 4.1, 4.3_
  - [x] 4.5 Backend integration and property tests
    - Testcontainers tests with the stub client (including: a `COMPLETED` job creates one notification for the starter with the Rooms deep-link; a `FAILED` job — worker failure and sweep timeout — creates exactly one `…failed` notification with the reason code; none for cancel; cancel endpoint `204`/`409`/`404`, cancel stops further AI calls): start → poll → result → bulk save with `importMedia`/`importJobId`; stale/foreign media and job refs; disabled `503`; **concurrent starts (one `202`, rest `429`), terminal-job replacement, cancel of an active job, `current` endpoint states, `aiImported` marker and filter, append-only (existing rooms unchanged)**; media CRUD and cascade; permission matrix (allowed and denied roles); media-isolation and marker-integrity properties. Run only the touched classes per the test-execution rules.
    - _Requirements: 1.6, 1.8, 2.1, 2.5, 2.6, 9.4, 9.5, 10.2, 14.1, 14.2, 16.4, 19.1–19.4, 21.1, 21.2, 22.2–22.4, 17.1–17.6_
    - _Depends on: 4.1a, 4.2, 4.4_

- [x] 5. Frontend (`foremen-frontend`)
  - [x] 5.1 i18n keys and shared types
    - Add all `rooms.autoimport.*`, `rooms.media.*`, `rooms.plan.*` keys and the `error.roomsimport.*` / `error.room.media.*` messages to `pl.json` and `ru.json` (stages, buttons, dialog, every warning code incl. `POSSIBLE_DUPLICATE_OF_EXISTING`, empty states, legend, aria labels, kind badges, "Auto AI import is in progress", disabled-button tooltip, Cancel import, AI result hint banner, failed banner + Try again, `error.roomsimport.in_progress`, `AI` badge + tooltip); the cancel button, confirmation text ("Cancel the running AI import? Progress will be lost." in PL/RU) and "Import cancelled" toast, `error.roomsimport.not_cancellable`; the root-level notification types `notification.roomsImport.completed` (PL "Automatyczny import pokoi przez AI zakończony", RU "Автоматический импорт комнат через ИИ завершён") and `notification.roomsImport.failed` (PL "Automatyczny import pokoi przez AI nie powiódł się", RU "Автоматический импорт комнат через ИИ не удался") in the same place as the `notification.procurement.ocr.*` keys, covered by the notification-type key test; shared `WARNING_CODES` constant; extend the parity tests to cover the new namespaces and every warning code.
    - _Requirements: 15.1, 15.2, 15.4_
    - _Depends on: none_
  - [x] 5.2 Wall plan SVG
    - Pure `planGeometry.ts` (label placement, opening placement, centroid, bounds) with fast-check property tests (finite outputs, collinear points); extend `RoomPlanPreview` with dimensions, door/window symbols, area label, legend, `role="img"` + localized `aria-label` + per-opening `<title>`; optional `offset` input in `RoomOpeningsEditor` and the `EditorOpening` type; defaults keep existing usages unchanged.
    - _Requirements: 12.3, 12.4, 12.5, 12.6, 12.8_
    - _Depends on: 5.1_
  - [x] 5.3 Room media UI
    - `room-media-api`, query/mutation hooks, `RoomMediaSection`/`RoomMediaGrid`/`MediaLightbox` with client-side validation, delete confirm, empty state; wire into `RoomEditorPage` and the matrix column `mediaCount` badge; empty-state for rooms without geometry plan.
    - _Requirements: 12.1, 12.2, 12.7, 10.3_
    - _Depends on: 4.3, 5.1, 5.2_
  - [x] 5.4 Autoimport API client, current-job hook and store integration
    - `autoimport-api.ts` (start, current, get, discard), `useStartAutoimport` (writes the response into the current-job cache; maps `429` params), `useCurrentAutoimportJob(projectId)` (`204` ⇒ null; 3 s → 6 s backoff while active; paused when the page is hidden; mounted by the project workspace shell), `materialiseImport` + `materialisedImportJobs` localStorage record (no double append on reload; append after existing and pending rooms); `draftToNewRoom` (always sets `importJobId`); extend `NewRoomDraft` (`geometry`, `importMedia`, `warnings`, `importTempId`) with a store/storage version migration; include `geometry`, `importMedia` and `importJobId` in the save payload builder (create-only for imported columns); handle `skippedMedia` / `skippedImportRefs` in the save result (localized info toast). Vitest coverage for mapping, migration, no-double-materialise and payload.
    - _Requirements: 11.3, 11.4, 11.5, 11.8, 9.5, 20.2–20.4, 20.6, 21.2, 21.4_
    - _Depends on: 4.2, 4.4, 5.1_
  - [x] 5.5 Generate rooms UI (incl. **Cancel import**: `useCancelAutoimport` → `POST …/cancel`, confirmation dialog, `404` treated as success, `409` refreshes to the finished state, "Import cancelled" toast; button in the in-table indicator and in the open dialog)
    - `GenerateRoomsButton` (permission + gating; disabled with tooltip while a job is active), `GenerateRoomsDialog` (prompt, file picker with client-side validation, Start/Cancel import/Retry, closable while the job runs, localized stage progress, localized `429` message), `ImportInProgressPlaceholder` (spinner + "Auto AI import is in progress" inside the table area, matrix stays editable), tab-label spinner in the project tab strip, `AiResultBanner`, `ImportFailedBanner`, `AiBadge` (pending imported columns and saved rooms with `aiImported`, also in `RoomEditorPage` title), `WarningsBanner`, `RoomWarningsPopover` with **Apply suggestion** as an undoable edit, type-required marker for unresolved `roomTypeId`; discard/dismiss call `DELETE`; integrate into `RoomsMatrixTab`, `RoomDimensionsMatrix` column headers and `NewRoomColumnHeader`.
    - _Requirements: 11.1, 11.2, 11.3, 11.6, 11.7, 11.9, 20.3, 20.4, 20.5, 20.7, 22.5, 24.5, 24.6_
    - _Depends on: 5.4_
  - [x] 5.6 Frontend flow tests
    - React Testing Library tests: dialog validation, polling to completion, columns appear and nothing is saved until Save, discard/undo, apply-suggestion, failure, `503` and `429` states; in-progress placeholder and disabled button when a job is active on mount; completed result materialised once after reload; AI badges; closing the dialog while the job runs; `RoomMediaSection` behaviours; i18n parity tests pass.
    - _Requirements: 11.1–11.9, 12.1, 15.1_
    - _Depends on: 5.3, 5.5_

- [x] 6. Environment
  - [x] 6.1 Docker stack wiring
    - Add `FOREMEN_ROOMS_IMPORT_ENABLED`, `FOREMEN_ROOMS_IMPORT_LLM_*` and the stub switch to `docker-compose.yml` and `.env.example` (stub default for local/CI, live credentials optional); document in the spec's test-cases how to switch.
    - _Requirements: 13.1, 18.2_
    - _Depends on: 3.1_

- [x] 7. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). API scenarios run against the Dockerized app (stub LLM for determinism, one live-Gemini smoke case); UI scenarios run in a browser engine. Result artifacts are MD reports with tables. Written in Russian.
  - _Requirements: 1–17, 19–24_
  - _Depends on: 4.5, 5.6, 6.1_

- [x] 8. Final checkpoint
  - Ensure the affected backend test classes and the affected frontend test files pass; verify `git status` in BOTH repos (root: spec + backend + docker; `foremen-frontend/`: frontend source) so nothing is left behind.
  - _Requirements: all_
  - _Depends on: 7_

- [x] 9. Unit-aware extraction and plausibility correction
  - [x] 9.1 Schema, prompt and response records
    - Per-value `{ value, unit, unitSource }` (and `geometryUnit`/`geometryUnitSource`, per-opening length unit) in `RoomsVisionSchemas`, `MeasureResponse` and `RoomsVisionResponseValidator`; `RoomsImportPrompts.MEASURE_SYSTEM` gets the unit rules (copy exactly, never convert, report unit per value) and the mm drafting convention; update `StubRoomsVisionClient` canned data to the new shape and add `STUB:MM_UNITS` and `STUB:AMBIGUOUS_UNIT`; unit tests for schema, validator and stub.
    - _Requirements: 25.1, 25.2_
    - _Depends on: none_
  - [x] 9.2 `UnitConverter` and `PlausibilityCorrector`
    - Pure classes in `com.foremen.service.roomsimport.units`, `RoomsImportProperties.Plausibility` and the `foremen.rooms-import.plausibility.*` block in `application.yml` (this task is the only one editing `application.yml`); unit tests plus property tests (idempotence, in-range untouched, rescale round-trip, ambiguous ⇒ null).
    - _Requirements: 25.3, 25.4, 25.5, 25.6, 25.8_
    - _Depends on: none_
  - [x] 9.3 Pipeline integration
    - In `RoomsImportPipeline`: convert and plausibility-correct before validation (order per design B5a), `toDraftRoom` uses converted values; add `UNIT_CORRECTED` to `WarningCodes.ALL`; `DimensionMissingRule` skips fields flagged `DIMENSION_OUT_OF_RANGE`; pipeline unit tests (mm plan ⇒ metres; ambiguous ⇒ null + warning; scalar-only room); integration test with `STUB:MM_UNITS`.
    - _Requirements: 25.3, 25.4, 25.5, 25.6, 25.7, 5.4, 7.3_
    - _Depends on: 9.1, 9.2_
  - [x] 9.4 Frontend warning texts and restore
    - `UNIT_CORRECTED` in `WARNING_CODES`; PL/RU texts (original → corrected with units) and `DIMENSION_OUT_OF_RANGE` text showing the original value (this task is the only one editing the locale files); "Apply suggestion" restores the original as an undoable edit; i18n parity tests and a popover/apply-suggestion test.
    - _Requirements: 25.5, 25.7_
    - _Depends on: 9.3_
  - [x] 9.5 Update test-cases.md
    - In Russian, following the steering standard: API cases for `STUB:MM_UNITS` and `STUB:AMBIGUOUS_UNIT`, a non-blocking live-Gemini smoke case with an mm plan, a UI case for the `UNIT_CORRECTED` popover and restoring the original value; keep run-id/teardown and the MD report template.
    - _Requirements: 25.1–25.8_
    - _Depends on: 9.3, 9.4_
  - [x] 9.6 Checkpoint
    - Ensure the affected backend test classes and frontend test files pass; verify `git status` in BOTH repos (root and `foremen-frontend/`).
    - _Requirements: 25.1–25.8_
    - _Depends on: 9.5_

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 1.3, 1.4, 1.5, 2.0, 2.3, 2.4, 5.1 — independent roots (changelog; gradle/yml; OCR refactor; geometry model; result records; resolver; cropper; frontend i18n). No shared files.
- **Wave 2 (parallel):** 1.2, 2.1, 2.2, 2.5, 3.1, 5.2 — need Wave 1 outputs (`changelog.xml` serialised after 1.1; normalizer/validator need `offset` + records; extractors/AI need config + records; plan needs i18n keys).
- **Wave 3 (parallel):** 4.1, 4.3, 6.1 — pipeline orchestration (needs entities, pure logic, AI client), room media backend (needs `room_media`), Docker wiring (needs the stub switch).
- **Wave 4 (parallel):** 4.1a, 4.2, 4.4, 5.3 — completion notification emitter and pipeline hook; controller over the service; `importMedia` over service + media; media UI over the media API and plan.
- **Wave 5 (parallel):** 4.5, 5.4 — backend integration tests; frontend autoimport client/store (needs endpoints and save contract).
- **Wave 6:** 5.5 — dialog/button/warnings UI (needs the client and store).
- **Wave 7:** 5.6 — frontend flow tests.
- **Wave 8:** 7 — test-cases.md (needs implemented and tested behaviour).
- **Wave 9:** 8 — final checkpoint.
- **Wave 10 (parallel):** 9.1, 9.2 — disjoint files (`ai/*` and stub vs `units/*` + config and `application.yml`).
- **Wave 11:** 9.3 — pipeline integration (needs the new schema and the units classes).
- **Wave 12:** 9.4 — frontend texts and restore (needs the backend warning contract).
- **Wave 13:** 9.5 — test-cases.md update.
- **Wave 14:** 9.6 — checkpoint.

## Dependency checklist

- [x] Every leaf task has `_Depends on:_` (`none` for roots).
- [x] The dependency graph is acyclic.
- [x] A "Parallel execution plan (waves)" section groups every leaf task exactly once.
- [x] Tasks sharing a wave touch disjoint files (`changelog.xml` is edited by 1.1 then 1.2 in separate waves; `application.yml` only by 1.3 in Wave 1; locale files only by 5.1; in the unit group `application.yml` is edited only by 9.2 and locale files only by 9.4).
- [x] Regression/`Author test-cases.md`/checkpoint tasks sit in the final, dependency-heavy waves.
