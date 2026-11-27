# Implementation Plan: FOR-05-04-estimate-AI — "Create estimate" with AI

## Overview

Backend (root repo, `foremen-backend/`) extends the FOR-05-02 rooms import job with the `ESTIMATE` mode (save rooms, plan works from the catalog, compute with the existing engine in a rollback-only scratch replay, write an AI offer), adds `aiInstructions` to the work catalog, the project lock, and the offer chat. Frontend (nested repo `foremen-frontend/`, committed separately) renames the button, shows staged progress with locked rooms, the AI badge/report and the chat panel.

Test execution rule (workspace standard `test-execution-rules.md`): run only the affected test classes with `--tests`, redirect to `/tmp/for-05-04-estimate-ai-test.log`, read the JUnit XML under `foremen-backend/build/test-results/test/`. Never run the full suite unless the user asks. Frontend: run only the affected Vitest files (`vitest --run <file>`). Entity-creation checklist: no new managed entity and no new ABAC resource (see design B1, B8), so steps 1 to 5 of `entity-creation-rules.md` do not apply; task 1.3 asserts this with a test.

Git: backend, specs, docs go to the root repo; frontend files go to the nested `foremen-frontend/` repo. Commit only when asked.

## Tasks

- [x] 1. Foundations
  - [x] 1.1 Liquibase changesets and entities
    - One task owns `changelog.xml` for this spec, registering in order: `add-ai-instructions-to-work-catalog` (`work_categories.ai_instructions`, `work_items.ai_instructions`, `not columnExists` guards), `extend-rooms-import-jobs-for-estimate` (`mode` default `ROOMS_ONLY`, `use_existing_rooms`, `offer_name`, `package_code`, `offer_id` FK `ON DELETE SET NULL`, `rooms_saved_at`, `rooms_created`), `add-offers-ai-columns` (`ai_generated`, `ai_job_id`, `ai_report` JSONB), `create-offer-ai-chat-messages` (FK `ON DELETE CASCADE`, index on `offer_id`), `add-estimate-line-room-qty-ai-meta` (`ai_meta` JSONB NULL). All `onFail="MARK_RAN"`/`columnExists`/`tableExists` guarded and idempotent.
    - Entities: `WorkCategoryEntity.aiInstructions`, `WorkItemEntity.aiInstructions`, `RoomsImportJobEntity` new fields, `RoomsImportStatus` new values (`SAVING_ROOMS`, `PLANNING_WORKS`, `QUANTIFYING`, `BUILDING_OFFER`, non-terminal), `OfferEntity` new fields, `OfferAiChatMessageEntity` + `OfferAiChatMessageDao` (`getProjectIdPath = "offer.project.id"`), `EstimateLineRoomQtyEntity.aiMeta` with a JSON converter, and the `CellAiMeta`/`CellMetaInput` records (design B11). Update any status check constraint.
    - Run the Liquibase/migration test and the `RoomsImportStatus` tests.
    - _Requirements: 1.1, 2.3, 8.2, 10.3, 21.3_
    - _Depends on: none_
  - [x] 1.2 Catalog DTOs, mappers and validation for `aiInstructions`
    - Add `aiInstructions` to category/work-item DTOs, create/update requests and MapStruct mappers; `@Size(max = 8000)`, trim, blank to `null`; audit snapshot includes it; add `aiInstructions` and `aiReport` to the `SensitiveFieldGuardTest` deny-list; confirm client-facing read models do not map it.
    - Tests: controller/mapper tests for create, update, length limit, blank handling, audit.
    - _Requirements: 1.1, 1.2, 17.5_
    - _Depends on: 1.1_
  - [x] 1.3 Job and offer DTO fields, client redaction contract test, no-new-resource test
    - Expose `mode`, `phase`, `offerId`, `roomsSavedAt`, `roomsCreated`, `result.estimate` on `RoomsImportJobDto`/mapper; expose `aiGenerated`, `aiJobId`, `aiReport` on **staff** offer read models only. Contract test (reflection) that no client read model, client PDF model, comparison data returned to a CLIENT or client portal DTO has a property starting with `ai`. Test that the `resources` seed contains no new code for this spec.
    - _Requirements: 2.3, 8.5, 8.7, 14.4, 17.1, 17.5_
    - _Depends on: 1.1_
  - [x] 1.4 Configuration, prompts and stub client
    - `EstimateAiProperties` (`foremen.estimate-ai.*`: `enabled`, `llm.*`, `max-catalog-chars`, `volume-tolerance-pct`, `scratch-timeout`, `timeout`, `chat.*`), `estimateAiTaskExecutor` bean (2 threads, queue 10), `application.yml` block, classpath resources `estimate-ai/system-prompt.md` and `estimate-ai/chat-system-prompt.md` (English, derived from sections 1 to 4 and 6 to 8 of the Standard, no price table, `version` recorded), `Disabled*`/`Stub*` fallback beans (stub returns a deterministic plan and deterministic tool calls), `foremen.estimate-ai.llm.stub` in the docker profile.
    - _Requirements: 2.8, 7.1, 7.2, 7.4, 7.5, 18.1, 18.4_
    - _Depends on: none_

- [x] 2. Catalog UI and seed
  - [x] 2.1 Seed `aiInstructions` of the 13 categories
    - Changeset `seed-ai-instructions-categories`: condensed rules from sections 5.1 to 5.13 (no prices), only `WHERE ai_instructions IS NULL`, categories matched by the code prefix of their works, unmatched left empty, `MARK_RAN` precondition, registered after 1.1's changesets. Integration test: seeded once, re-run changes nothing, an edited value is never overwritten.
    - _Requirements: 1.5_
    - _Depends on: 1.2_
  - [x] 2.2 Frontend: AI calculation rules field in work categories and work catalog
    - Textarea with counter (max 8000) in create/edit forms of `features/work-categories` and `features/work-catalog`, indicator icon in the tables, zod schema and API types updated, PL/RU locale keys (`workCatalog.aiInstructions.*`, `workCategories.aiInstructions.*`) added to both `pl.json` and `ru.json`. Vitest for forms and indicator.
    - _Requirements: 1.3, 19.1_
    - _Depends on: 1.2_

- [x] 3. Backend pipeline
  - [x] 3.1 `AiProjectLockGuard` and wiring
    - Component and DAO query; call from the room write paths (`RoomService`, `RoomsSaveService` with an explicit job-context bypass, room media), estimate write paths (`EstimateAssignmentService.saveAndAssemble`, `applyLabourPriceAdjustment`), `OfferRestoreService.restore` and the offer prepare/refresh/update paths in `OfferService`. Integration test with an active `ESTIMATE` job row: every write endpoint answers `409 error.project.ai.locked`, reads work, a `ROOMS_ONLY` job locks nothing, the lock ends with the terminal status.
    - _Requirements: 4.1, 4.2, 4.3_
    - _Depends on: 1.1_
  - [x] 3.2 Behaviour-preserving extraction of replay, replace and material seeding logic
    - Also extract `seedMaterialLines` into `EstimateMaterialSeeder` (used by the assign path and the scratch replay), keeping the assign behaviour identical.
    - Extract the delete/insert part of `OfferRestoreService.restore` into `EstimateReplaceHelper` (planning part reusable) and expose the `applyStagedEdit` replay as a package-visible `StagedEditReplayer` used by both the preview path and the new scratch service. No behaviour change; existing restore, preview and save tests must stay green.
    - Tests: run `OfferRestoreService*`, `EstimateAssignmentService*` preview/save and matrix tests.
    - _Requirements: 12.5, 23.1_
    - _Depends on: none_
  - [x] 3.3 `EstimateScratchService`
    - **Rework note:** used by the job only until the rework; **superseded by 9.2/9.3 for the job** (the job writes the live estimate). Stays for the offer chat (design D-R1); the `EMPTY` seed and job-specific `metaInputs` path are removed in 9.4.
    - `compute(projectId, seed, edits, metaInputs)` as in design B6 (the `metaInputs` parameter is passed through to the overlay of 3.11; an empty list is a no-op): `REQUIRES_NEW`, timeout, estimate row lock, seeds `EMPTY`/`SNAPSHOT`/`LIVE`, replay, `OfferSnapshotBuilder` inside the transaction, rollback-only, event/collab/audit suppression. Property test: snapshot round-trip identity and restore equivalence; test that nothing persists (row counts, totals, audit) and that no event is published; timeout test.
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.6, 12.7_
    - _Depends on: 3.2, 1.1_
  - [x] 3.4 Plan model and pure logic
    - `EstimatePlan`, `PlannedWork`, `Unpriced`, `ReportWarning`, `WarningCodes`; `PlanResolver` (code to active work item, `UNKNOWN_CODE`/`NO_PRICE`/`UNSUPPORTED_UNIT`, negative volumes dropped, duplicates merged), `PlanValidator` (rules of Requirement 5.5). Unit and property tests (never emits a zero-price line, never throws, idempotent merge).
    - _Requirements: 5.3, 5.4, 5.5, 6.5_
    - _Depends on: 1.4_
  - [x] 3.5 Catalog/room context builders and the planner client
    - `CatalogContextBuilder` (structure of Requirement 7.3, no prices, batching by `max-catalog-chars`), `RoomContextBuilder`, `EstimateAiPlanner` (GenAi JSON transport, response schema with the required per-cell `rationale` and optional `volumeReason`, one retry, one re-ask for missing rationales, `plan_invalid` failure), stub implementation. Unit tests for batching, merge, structure and prompt delimiting of the user instruction.
    - _Requirements: 5.1, 5.2, 5.6, 7.3, 7.4, 21.6_
    - _Depends on: 1.2, 3.4_
  - [x] 3.6 `PlanToEditsConverter`
    - **Rework note:** **superseded by 9.2/9.3**: `PlanToEditsConverter` is replaced by the per-assignment `assign_work` path; removed in 9.4. Tolerance and coefficient rules move to `AssignWorkService`.
    - Plan to ordered `StagedEdit`s plus the `CellMetaInput` list (rationale, volumeReason, status, confidence, source, price reason per cell): `ASSIGN`, `SET_QUANTITY` when model volume differs beyond tolerance (formula volume vs override recorded in the report), `ADJUST_LABOUR_PRICE` only from stated coefficients. Unit and property tests (no coefficient means no adjust edit; override only beyond tolerance).
    - _Requirements: 6.1, 6.2, 6.4, 21.1, 21.5_
    - _Depends on: 3.4_
  - [x] 3.7 `EstimateAiRoomsSaver` (stage `SAVING_ROOMS`)
    - Draft rooms to `RoomCreateRequest`, save through `RoomsSaveService` in one transaction under the creator's re-checked permissions, skip-and-report (`ROOM_NOT_SAVED`), `no_rooms` failure, commit `rooms_saved_at` and `rooms_created` in `REQUIRES_NEW`, append-only rule, draft media attach. Integration test: rooms visible while the job is still active.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 4.4_
    - _Depends on: 3.1_
  - [x] 3.8 `AiOfferWriter` and the two `OfferService` methods
    - **Rework note:** `AiOfferWriter` and `OfferService.createAiOffer` are **superseded by 9.6 and 9.4** (the job creates no offer). `replaceAiEditedSnapshot` and `AiReportBuilder` summary logic stay valid for the chat and for 9.6.
    - `OfferService.createAiOffer(...)` and `OfferService.replaceAiEditedSnapshot(...)` (version + 1, totals from snapshot, audit `AI_CHAT_EDIT`/`AI_CHAT_UNDO`/`CREATE_AI_OFFER`, DRAFT only), `AiReportBuilder` (summary by category), atomic offer creation with the status-conditional job completion, empty-offer handling.
    - Tests: offer is DRAFT/aiGenerated, snapshot restorable with `OfferRestoreService`, client gets 404, totals equal snapshot totals, cancelled job writes nothing.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.8, 9.1, 9.2, 9.3, 9.4, 4.5_
    - _Depends on: 1.3, 3.3_
  - [x] 3.9 `EstimateAiWorker`, controller changes, sweep
    - **Rework note:** stage orchestration (`QUANTIFYING`, `BUILDING_OFFER`, offer link) is **superseded by 9.3**; the start parameters, cancel, sweep and `ROOMS_ONLY` path stay.
    - `mode`, `useExistingRooms`, `offerName` and `packageCode` request parts, permission checks for `ESTIMATE` mode, estimate-locked refusal, new worker orchestration of all stages with `REQUIRES_NEW` stage commits and cancellation checks at each boundary, pipeline hand-off at `VALIDATING`, poll/current DTOs, cancel/delete semantics (keep saved rooms), sweep for `ESTIMATE` jobs with the job timeout, `ROOMS_ONLY` path unchanged. Tests: stage sequence with the stub, cancel after `SAVING_ROOMS` keeps rooms and creates no offer, failure reasons, `useExistingRooms`, in-progress 429, `ROOMS_ONLY` regression.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 3.5, 4.4, 4.6, 4.7, 6.3, 6.6, 18.2, 18.3, 18.5_
    - _Depends on: 3.5, 3.6, 3.7, 3.8, 3.10, 3.11_
  - [x] 3.11 Cell AI meta: facts, overlay, snapshot v3, restore, marker clearing
    - `CellFactsResolver` (engine facts via `VolumeResolver`, also for overridden cells), `CellMetaOverlay` inside `EstimateScratchService.compute`, `OfferSnapshot.Cell` extension (version 3, version 2 still readable, old constructor kept), `OfferSnapshotBuilder` copies `aiMeta`, `OfferRestoreService`/`EstimateReplaceHelper` restore `volumeOverridden` and `ai_meta`, `CellDto.ai` in `EstimateMatrixAssembler`, marker clearing in the manual `ASSIGN`/`UNASSIGN`/`SET_QUANTITY`/`CLEAR_QUANTITY`/`ADJUST_LABOUR_PRICE` paths and in the recompute (replay origin flag), `RATIONALE_MISSING` and `ADJUSTMENT_REASON_MISSING` warnings in `PlanValidator`. Client read models stay free of `ai` (extend the contract test of 1.3 to nested cell types).
    - Tests: round-trip with `ai`; restore keeps the override flag; manual edit clears the marker; overridden cell reports the formula volume; v2 snapshots still restore; existing matrix/restore/preview tests stay green.
    - _Requirements: 21.2, 21.3, 21.4, 21.6, 21.7, 21.8, 21.9, 12.4, 12.6_
    - _Depends on: 3.3, 3.4_
  - [x] 3.12 Materials in the snapshot: store, restore, scratch seed, report summary, parity tests
    - **Rework note:** snapshot materials stay for offers created later from the estimate; seeding inside the scratch replay for the job is **superseded by 9.2** (seeding happens in each assignment).
    - `OfferSnapshot.Cell.materials[]` (`MaterialLine` record mirroring the estimate material line: branch, type ids, `normQty`, `consumptionBasis`, range, `qtyOverridden`, `manualQty`, effective `quantity`, chosen product ids and `concreteNet`, `appliedFromPackage`, source ids), `OfferSnapshotBuilder` writes it for every snapshot (AI and manual offers, version 3); `EstimateReplaceHelper` recreates the lines from the snapshot on restore and as the scratch seed (placeholders or skipped when a type or product is gone, `skippedMaterials` in `RestoreResult`); version-2 snapshots restore without materials as today; `packageCode` start option stored on the job and used by the scratch replay and the snapshot; report `summary.materials` (`lines`, `cellsWithMaterials`, `overriddenLines`, `worksWithoutNorms[]`), warning `MATERIAL_PRICE_RANGE_MISSING`, `materials` block of the cell explanation; extend the client-model reflection contract test to `materials`.
    - Tests: **parity** (manual staged edits vs AI snapshot + restore), **round-trip** `estimate -> snapshot -> restore` for materials with an overridden quantity and a chosen product, chat edit keeps overridden quantities and products, non-overridden lines follow a new volume, work without norms, package choice, version-2 snapshot restores unchanged, snapshot size sanity, existing restore/assign/snapshot tests green.
    - _Requirements: 23.1, 23.2, 23.3, 23.4, 23.5, 23.6, 23.8, 23.9, 23.10, 23.11, 23.12, 2.1_
    - _Depends on: 3.2, 3.8, 3.11_
  - [x] 3.10 `EstimateAiNotificationEmitter`
    - **Rework note:** the `completed` deep link to the Offer tab is **superseded by 9.4** (link to the Estimate tab).
    - Types `notification.estimateAi.completed` / `.failed`, deep-links, exactly-once through the status-conditional update, skipped for cancelled jobs, `ROOMS_ONLY` jobs keep the rooms emitter and the rooms emitter is skipped for `ESTIMATE`. Unit tests with the same cases as `RoomsImportNotificationEmitter` tests.
    - _Requirements: 16.1, 16.2, 16.3, 16.4_
    - _Depends on: 1.1_

- [x] 4. Offer chat backend
  - [x] 4.1 `SnapshotTextRenderer` and `OfferChatToolRegistry` with the seven tools (materials are not touched by tools)
    - Every write tool requires a `reason` (a call without it is a tool error) and returns `CellMetaInput`s for the touched cells (price block for every cell of in-scope lines on `adjust_price`). Renderer (compact, bounded size, includes the existing cell explanations in short form), registry with schema/permission/run, tools `find_work`, `get_offer_state`, `assign_work`, `unassign_work`, `set_volume`, `clear_volume_override`, `adjust_price`, `set_line_comment`; validation errors returned as tool results; per-tool permission checks through the existing evaluator; a test that the registry contains exactly the allowed tools.
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 21.5_
    - _Depends on: 3.3, 1.3_
  - [x] 4.2 `OfferAiChatService`, controller, undo
    - Endpoints `GET/POST ai-chat`, `POST ai-chat/undo` under `OFFERS`, guards (DRAFT, snapshot, lock guard, rate limit, per-offer lock plus advisory lock, `busy`), turn loop with max rounds, confirmation threshold and `pendingAction`/`confirmPendingId`, atomic apply through the scratch service and `replaceAiEditedSnapshot`, `snapshot_before`, `ai_report.changes[]`, undo with staleness rule, persisted history visible to staff only, nothing persisted on model failure. Tests with the stub: apply, confirm, undo, undo_stale, busy, rate limit, non-DRAFT 409, CLIENT 404.
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 11.5, 11.6, 11.7, 11.8, 11.9, 9.5_
    - _Depends on: 4.1, 3.1, 3.8, 3.11_

- [x] 5. Frontend (nested repo)
  - [x] 5.1 "Create estimate" dialog, job state and lock hook
    - Rename the button and rework the dialog (offer name, optional package select defaulting to the estimate's applied package, use-existing-rooms toggle, long-running note), API types for the extended job DTO, `useProjectAiLock(projectId)` from the current-job query, stage labels, notification type translations (`notification.estimateAi.*`) added to both locale files. Update the FOR-05-02 tests affected by the rename.
    - _Requirements: 13.1, 13.4, 16.5, 19.1_
    - _Depends on: 3.9_
  - [x] 5.2 Rooms tab: progress, early rooms render, locked state
    - **Rework note:** banner/stepper is **superseded by 9.7** (one shared banner with progress and action log); locked rooms and early render stay.
    - `EstimateJobBanner` (stepper, cancel, failed with retry and "N rooms were saved", completed with link to the offer and report counts), render rooms as soon as `roomsSavedAt` is set, read-only matrix/editor/media with the lock banner and disabled controls, handling of `409 error.project.ai.locked`. Locale keys in both files. Vitest for each state.
    - _Requirements: 13.2, 13.3, 13.5, 4.1, 19.1_
    - _Depends on: 5.1_
  - [x] 5.3 Estimate/Offer tabs: lock banner, AI badge, report panel, send warning
    - **Rework note:** the lock banner on the Offer tab is **superseded by 9.7** (no job banner on the Offer tab; chat stays disabled while locked).
    - Lock banner and disabled write actions in the Estimate and Offer tabs, `AiBadge`, `AiReportPanel` (assumptions, questions, unpriced, warnings, changes, incomplete-total indicator, materials summary, row links), one-time send warning, staff-only rendering (never for CLIENT). Locale keys in both files.
    - _Requirements: 13.3, 14.1, 14.2, 14.3, 14.4, 9.3, 19.1_
    - _Depends on: 5.2_
  - [x] 5.5 Cell AI marker, "Why" card and filters
    - `CellAiMarker`, `CellWhyCard` (content of Requirement 22.2, placeholders and reason codes), "AI cells" and "Corrected" filters, used in the Estimate matrix and in the offer snapshot matrix view; keyboard and aria support; marker cleared in the staged preview after a manual edit; never rendered for CLIENT. Locale keys in both files. Vitest for marker states, card content for each volume source, the null-rationale placeholder, and the filters.
    - _Requirements: 22.1, 22.2, 22.3, 22.4, 22.5, 22.7, 23.11, 19.1_
    - _Depends on: 5.3, 3.11_
  - [x] 5.4 Offer chat panel and i18n parity test
    - `OfferChatPanel` (docked on desktop, sheet on mobile), `useOfferAiChat` (history query, send mutation, confirm, undo, optimistic user message, invalidation of snapshot/offer queries), changed-cell links that open the "Why" card, example chips, disabled states, locale keys in both files; parity test over every key added by this spec (`estimateAi.*`, `offer.ai.*`, `cellAi.*`, `workCatalog.aiInstructions.*`, `notification.estimateAi.*`).
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 19.1, 19.2, 22.6_
    - _Depends on: 5.5, 4.2_

- [x] 6. Integration, docs and verification
  - [x] 6.1 Backend end-to-end integration test (stub LLM)
    - **Rework note:** **superseded by 9.10** (job path no longer creates an offer).
    - Testcontainers: start an `ESTIMATE` job, assert stage order, rooms saved before completion, lock during, no estimate rows written by the job, AI offer created and restorable, material lines stored in the snapshot and present after restore, notification once, client 404 on the offer.
    - _Requirements: 2.2, 3.3, 4.1, 8.1, 8.5, 8.6, 16.1, 23.1_
    - _Depends on: 3.9, 3.12_
  - [x] 6.2 Update the loaders agent API document
    - Add the `mode`/`useExistingRooms`/`offerName` parts to the FOR-08 `openapi/README.md` toolset notes; state that the chat endpoints are not part of the agent toolset.
    - _Requirements: 2.1_
    - _Depends on: 3.9_
  - [x] 6.3 Chat backend end-to-end integration test (stub LLM)
    - Full path: AI offer, chat adjust price with confirmation, set volume, undo, restore into the estimate and compare totals with the snapshot totals, concurrency `busy`.
    - _Requirements: 10.4, 11.5, 11.7, 11.8, 12.6_
    - _Depends on: 4.2_
  - [x] 6.4 Regression run of touched existing tests
    - Run only the affected existing classes: rooms import (`RoomsImport*`), `OfferRestoreService*`, `OfferService*`, estimate matrix/preview/save, offer snapshot, client offer read models; and the frontend rooms/offer/catalog Vitest files. Fix any regression caused by the extraction (3.2) or the lock guard (3.1).
    - _Requirements: 12.5, 4.3_
    - _Depends on: 5.4, 6.1, 6.3_
- [x] 7. Author test-cases.md
    - **Rework note:** **updated by 9.11** (rewrite for the direct-to-estimate flow).
    - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group, MD report template). For this mixed spec: API scenarios (Part A) against the Dockerized app with the LLM stub, and UI browser-engine scenarios (Part B). Result artifacts are MD reports with tables.
    - _Requirements: 20.1, 20.2 (and all requirements covered by the cases)_
    - _Depends on: 6.4_
- [x] 8. Final checkpoint
    - All affected backend and frontend tests green, `git status` checked in both repos, `PROGRESS.md` updated, nothing intended left uncommitted.
    - _Depends on: 7_

- [x] 9. Rework: direct-to-estimate AI job
  - [x] 9.1 DB changesets and entities for the rework
    - Changesets (guarded, registered last in `changelog.xml`): `add-estimates-ai-summary` (`estimates.ai_summary` JSONB NULL), `add-rooms-import-jobs-progress` (`action_log` JSONB NULL, `rooms_total`, `rooms_done`, `works_total`, `works_done` INT NULL), and a migration that moves in-flight jobs with status `QUANTIFYING` or `BUILDING_OFFER` to `FAILED` (`error.estimateai.failed.superseded`). Entities: `EstimateEntity.aiSummary` (JSON converter), `RoomsImportJobEntity` new fields, `RoomsImportStatus` gains `ASSIGNING` and `FINALIZING` (old values removed in 9.4). Update any status check constraint.
    - Run the Liquibase/migration test and `RoomsImportStatus` tests.
    - _Requirements: 24.9, 25.1, 26.1_
    - _Depends on: none_
  - [x] 9.2 `AssignWorkService`, `AiJobContext` and the `assign_work` tool
    - `AssignWorkService.assign(AiJobContext, AssignWorkCommand)` in `REQUIRES_NEW`: resolve the work, check rooms, normal assign path of `EstimateAssignmentService` (with `EstimateMaterialSeeder` for every cell), volume override beyond tolerance with `volumeReason`, stated coefficient via `ADJUST_LABOUR_PRICE`, `CellAiMeta` through `CellFactsResolver` written to `ai_meta` in the same transaction, action-log append and `works_done + 1`, idempotent for an existing `(work, room)`, typed `AssignRejected`. `AiProjectLockGuard.assertUnlocked(projectId, ctx)` accepts the job's own context. `AssignWorkTool` function declaration (`assign_work(workItemId, roomIds, volume, volumeReason, rationale, status, confidence, source, priceReason)`).
    - Tests: unit tests for tolerance, coefficient only when stated, rejections, lock bypass only for the owning job, committed-and-visible after the call.
    - _Requirements: 24.1, 24.2, 24.3, 24.4, 24.5, 24.6, 24.7, 24.12, 6.2, 6.4_
    - _Depends on: 9.1, 9.5_
  - [x] 9.3 Planner/worker rewrite: stages `ASSIGNING` and `FINALIZING`
    - `EstimateAiWorker`: after `PLANNING_WORKS` (set `works_total`), call `AssignWorkService` once per resolved assignment in order, each in its own transaction; cancel check before every call; rejected calls become warnings and the loop continues; `FINALIZING` writes the summary through 9.6 (also for cancel/failure with `works_done > 0`); `rooms_total`/`rooms_done` set during detection, measuring and saving; the sweep and the lock stay status-based. No offer is created.
    - _Requirements: 24.1, 24.7, 24.8, 24.9, 24.11, 26.2, 26.3, 3.5_
    - _Depends on: 9.2, 9.5, 9.6_
  - [x] 9.4 Dead-code cleanup of the offer path in the job
    - Remove `AiOfferWriter`, `OfferService.createAiOffer`, the `CREATE_AI_OFFER` audit, `PlanToEditsConverter`, the `EMPTY` seed and the job-specific `metaInputs` use in `EstimateScratchService` (the service and `CellMetaOverlay` stay for the chat), statuses `QUANTIFYING` and `BUILDING_OFFER`, the `job.offer_id` write and DTO field, and the offer deep link; the `completed` notification links to `/projects/{projectId}/estimate`; remove obsolete stage keys from the frontend types in 9.7. Keep the chat tests green.
    - _Requirements: 24.9, 24.10, 16.1_
    - _Depends on: 9.3, 9.6_
  - [x] 9.5 `JobActionLog` and the poll DTO with counters
    - `JobActionLog.append(jobId, stage, code, params)` with bounded prepend (200, newest first, no lost concurrent appends, in the caller's transaction when present, otherwise `REQUIRES_NEW`); `RoomsImportJobDto` and mapper return `actionLog[]`, `roomsTotal`, `roomsDone`, `worksTotal`, `worksDone` for `GET .../{jobId}` and `.../current`; unknown counters are `null`. Action codes: `ROOM_MEASURED`, `ROOM_SAVED`, `PLAN_READY`, `WORK_ASSIGNED`, `MATERIALS_SEEDED`, `ASSIGNMENT_REJECTED`, `SUMMARY_WRITTEN`.
    - Tests: bound and order, concurrent appends, DTO shape.
    - _Requirements: 26.1, 26.2, 26.3_
    - _Depends on: 9.1_
  - [x] 9.6 Summary persistence, estimate read model and copy to the offer
    - `AiSummaryWriter` (writes `estimates.ai_summary`, partial and `incomplete` on cancel/failure); `EstimateMatrixAssembler` returns `aiSummary` and `CellDto.ai` to all roles except `CLIENT`; `OfferService.prepare` (and refresh/update for an `aiGenerated` offer with a newer summary) copies the summary into `offers.ai_report` and sets `ai_generated` when AI marks or a summary exist; extend the client-model reflection contract test to `aiSummary`.
    - Tests: copy on create, no copy without marks, client redaction, summary survives marker clearing.
    - _Requirements: 25.1, 25.2, 25.3, 25.4, 25.5, 24.13_
    - _Depends on: 9.1_
  - [x] 9.7 Frontend: shared `EstimateJobBanner` with progress and action log
    - One component in `features/estimate-job` used on the Rooms tab and the Estimate tab, removed from the Offer tab (chat stays disabled while locked). Progress text and bar for `roomsDone/roomsTotal` and `worksDone/worksTotal`, only the step label for indeterminate stages, expandable `ActionLogList` from `actionLog` with PL/RU templates, Cancel, failed/completed states, no render for CLIENT. Stage types and labels updated (`ASSIGNING`, `FINALIZING`). Locale keys in both files. Vitest: progress texts, expand/collapse persistence across polls, identical DOM on both tabs, absent on the Offer tab.
    - _Requirements: 26.4, 26.5, 26.6, 26.7, 27.7, 27.8_
    - _Depends on: 9.5_
  - [x] 9.8 Frontend: live Estimate tab, go-to-estimate prompt, summary panel
    - `GoToEstimatePrompt` (non-blocking toast once per job when `roomsSavedAt` appears), `useEstimateLiveRefresh` (refetch every 2 s and on `worksDone` change while the job is active, final refetch on terminal status, keep scroll and open "Why" card), read-only lock state, `AiSummaryPanel` from `aiSummary`, completion notification opens the Estimate tab. Locale keys in both files. Vitest with fake timers.
    - _Requirements: 27.1, 27.2, 27.3, 27.4, 27.5, 27.6, 25.6, 24.9_
    - _Depends on: 9.6, 9.7_
  - [ ]* 9.9 Property tests for the direct job
    - **Property 1: Direct assignment parity with manual apply** (**Validates: 24.2, 24.4, 24.6**), **Property 2: Assignment is idempotent per work and room** (**Validates: 24.12**), **Property 3: Every committed cell has materials and an annotation** (**Validates: 24.4, 24.5**), **Property 4: Cancel keeps exactly the committed assignments** (**Validates: 24.8, 26.2**), **Property 5: Action log is bounded and ordered** (**Validates: 26.1**), **Property 6: Summary copy into the offer** (**Validates: 25.3**), **Property 7: Client redaction** (**Validates: 25.4**). jqwik, at least 100 tries each, one test per property.
    - _Requirements: 24.2, 24.4, 24.5, 24.6, 24.8, 24.12, 25.3, 25.4, 26.1_
    - _Depends on: 9.3, 9.6_
  - [x] 9.10 Integration test updates: `EstimateAiWorkerIT` and `EstimateAiEndToEndIT`
    - `EstimateAiWorkerIT`: stage order `SAVING_ROOMS, PLANNING_WORKS, ASSIGNING, FINALIZING`, cells and materials visible between assignments, `ai_meta` per cell, no offer rows created, cancel keeps assigned lines and stops, counters and action log in the poll DTO, lock until terminal. `EstimateAiEndToEndIT`: job, then create an offer from the estimate: `ai_report` copied, `ai_generated = true`, snapshot has marks and materials, restore parity, client 404, notification link to the Estimate tab. Remove assertions about job-created offers.
    - _Requirements: 24.3, 24.4, 24.5, 24.8, 24.9, 24.10, 25.3, 26.2_
    - _Depends on: 9.4_
  - [x] 9.11 Author test-cases.md (rework update)
    - Rewrite `test-cases.md` in Russian per `.kiro/steering/test-cases.md` for the direct-to-estimate flow: A-part API scenarios on the Dockerized app with the LLM stub (lines appear during the job, materials per cell, `ai_meta`, cancel keeps lines, `ai_summary`, copy into the offer, counters and action log, client redaction), B-part UI scenarios (shared banner on Rooms and Estimate, expandable actions, progress texts, go-to-estimate prompt, live Estimate tab, absent banner on Offer tab), repeatability strategy, regression group, MD report template.
    - _Requirements: 20.1, 20.2, 24.1, 24.4, 24.8, 25.3, 26.4, 26.6, 27.1, 27.2_
    - _Depends on: 9.12_
  - [x] 9.12 Regression run of touched tests
    - Run only affected classes (`RoomsImport*`, `EstimateAi*`, `AssignWork*`, `OfferService*`, `OfferRestoreService*`, estimate matrix/preview/save, snapshot, client read-model contract) and affected Vitest files (rooms, estimate, estimate-job, offer, catalog). Logs to `/tmp/for-05-04-rework-test.log`; read the JUnit XML.
    - _Requirements: 4.3, 12.5, 24.10_
    - _Depends on: 9.8, 9.10_
  - [x] 9.13 Final checkpoint
    - Ensure all tests pass, ask the user if questions arise. `git status` checked in both repos, nothing intended left uncommitted.
    - _Depends on: 9.11_

## Notes

- Tasks 1 to 8 are done and stay checked. Where behaviour is replaced, the old task carries a "Rework note" instead of being deleted.
- Group 9 tasks are unchecked. Task 9.9 is optional (`*`).
- Backend tasks commit in the root repo, frontend tasks (9.7, 9.8) in the nested `foremen-frontend/` repo.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.4", "3.2"] },
    { "id": 1, "tasks": ["1.2", "1.3", "3.1", "3.3", "3.4"] },
    { "id": 2, "tasks": ["2.1", "2.2", "3.5", "3.6", "3.7", "3.8", "3.10", "3.11", "4.1"] },
    { "id": 3, "tasks": ["3.9", "3.12", "4.2"] },
    { "id": 4, "tasks": ["5.1", "6.1", "6.2", "6.3"] },
    { "id": 5, "tasks": ["5.2"] },
    { "id": 6, "tasks": ["5.3"] },
    { "id": 7, "tasks": ["5.5"] },
    { "id": 8, "tasks": ["5.4"] },
    { "id": 9, "tasks": ["6.4"] },
    { "id": 10, "tasks": ["7"] },
    { "id": 11, "tasks": ["8"] },
    { "id": 12, "tasks": ["9.1"] },
    { "id": 13, "tasks": ["9.5", "9.6"] },
    { "id": 14, "tasks": ["9.2", "9.7"] },
    { "id": 15, "tasks": ["9.3", "9.8"] },
    { "id": 16, "tasks": ["9.4", "9.9"] },
    { "id": 17, "tasks": ["9.10"] },
    { "id": 18, "tasks": ["9.12"] },
    { "id": 19, "tasks": ["9.11"] },
    { "id": 20, "tasks": ["9.13"] }
  ]
}
```
