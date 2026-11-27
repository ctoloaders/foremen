# Requirements Document

## FOR-05-04-estimate-AI — "Create estimate" with AI: one pipeline from design project to a priced offer, AI calculation rules in the work catalog, and an offer-editing chat

## Introduction

This is a child spec of **FOR-05 (Project Estimate)**. It extends **FOR-05-02-rooms-autoimport** (AI import of rooms) into a full "design project to priced offer" pipeline.

Today the client builds estimates in ChatGPT: a custom instruction ("FOREMEN AI Estimator", `FOREMEN AI Estimator — стандарт расчёта смет.docx`) plus a Google Sheet with price rates, with one block of rules per work category. The result is a filled `Oferta` sheet. Foremen already has what that setup lacks: room import, a work catalog with prices and codes, the estimate matrix (apply work to rooms, volume override, price markup/discount), and offer snapshots with restore. This spec adds only what is missing and reuses everything else.

What is added:

1. **AI calculation rules in the catalog.** A free-text `aiInstructions` field on every **work category** and every **work item**, managed in the existing admin UI. This is where the "nuances of calculation per kind of work" from the standard live (for example "tile waterproofing: whole floor plus walls to full height", "GK WC enclosure standard size up to 120 x 100 cm").
2. **One "Create estimate" pipeline.** The existing "Generate rooms" button becomes **"Create estimate"**. One asynchronous job: detects rooms, saves them, selects catalog works, computes volumes per room, and writes the result as an **AI offer** (a DRAFT offer with a snapshot flagged as AI). Results are saved **stage by stage**: rooms are rendered in the UI as soon as they are saved, but are **locked** while the job runs.
3. **AI offer.** The job result is stored on the server as an offer snapshot (not in the browser), marked `aiGenerated`, visible to every staff role and never to the client. It can be viewed, compared, restored into the estimate and edited with the existing offer functions.
4. **Offer chat.** A chat panel on the Offer tab. The user writes "add 10% markup to the tiling works", "set the volume of work 7.03 in Bathroom to 12 m2" and the assistant performs it through tools that are thin adapters over the existing estimate/offer service methods. Each applied turn produces a new snapshot version and can be undone.

Scope decisions:

- **One process, staged persistence.** There is no review gate between rooms and works. The job saves each stage when it completes: rooms first, then the offer. A cancelled or failed job keeps what was already saved (rooms stay; there is no offer if the offer stage did not finish).
- **Estimate staging stays in the browser** (FOR-05-05 / FOR-05-13). The AI does **not** write staged edits into the browser. It produces a server-side **offer snapshot**, which survives a new session or another device. To work on it in the estimate matrix the user uses the existing **Restore estimate from offer** action.
- **No changes to the live estimate by the job.** The job never writes estimate lines; it only creates rooms (stage 1) and one DRAFT offer with a snapshot (stage 2).
- **Materials come from the consumption norms, like today, and are stored in the snapshot.** The AI never picks materials. For every AI cell the job seeds the consumable-material lines from the work's consumption norms by the same code path that "assign work" uses, and the **offer snapshot stores these material lines per cell** (norm, effective quantity, quantity-override flag with the manual quantity, price range, chosen product when any). Restoring the offer rebuilds exactly these lines, so the resulting estimate (works, volumes, materials, materials list, BoM) equals the one a person would get by applying the same works with the same volumes (Requirement 23). Offer totals stay what they are today (labour); materials never reach a client read model.
- **Every AI cell is explained.** Each cell the AI creates or changes carries an AI marker and an explanation: why the work is in this room, whether the room formula was used or the unit fallback, which correction was applied to the volume and why, and which price markup applies and why (Requirement 21). Numbers about formulas come from the engine, texts of reasons come from the model or the user's chat instruction.
- **Catalog is the only price source.** Rates come from the catalog price of each work item, frozen into the snapshot exactly as the existing snapshot builder does. Works the model needs but that are not in the catalog are reported as **unpriced** items, never priced at zero.
- **Pricing coefficients are never applied silently.** A markup (for example the +15% of section 6 of the standard) is applied only when the user's prompt or a chat message states it, through the existing labour price adjustment.
- **Existing rooms-only import stays available** for API callers (mode `ROOMS_ONLY`, the FOR-05-02 behaviour: draft, review, bulk save). The UI button starts the new mode `ESTIMATE`.
- **Backend i18n.** The only new text column is `aiInstructions` on two entities. It is stored in **one language** (no RU/PL pair), because it is a prompt for the model, not user-facing UI text. The model reads it in any language. Other messages are returned as codes + params.
- **Notifications: two** (Requirement 16), emitted through the existing generic `NotificationService`.
- Out of scope: XLSX export of the AI result (the offer PDF already exists), automatic sending of the AI offer to the client, learning from previous estimates (section 10 of the standard), materials/BoM generation, Gantt, material cost, per-project price lists, image-less drawings (DWG/DXF/IFC).

## Rework (direct-to-estimate AI job)

The first implementation built the AI result off-line (rollback-only scratch replay) and published it as a DRAFT **AI offer**. This rework changes the main path: the job **writes work volumes and materials directly into the live estimate while it runs**, one committed assignment at a time, so the user watches the estimate fill in. Requirements 24 to 27 define the new behaviour. Older requirements keep their numbers. The parts that contradict the rework carry a **Superseded** note and are not deleted, so history stays readable.

| Old behaviour | Status | Replaced by |
|---|---|---|
| Stages `QUANTIFYING` and `BUILDING_OFFER`, offer created by the job (2.2, 2.3, 4.4, 4.5, 8, 16.1) | Superseded | 24, 25 |
| Job never writes the live estimate (6.1, 6.6, 12.1, 12.2 `EMPTY` seed for the job) | Superseded for the job | 24 |
| AI report stored only on the offer (9.1, 9.4) | Superseded | 25 (report on the estimate, copied to the offer on creation) |
| Completion deep link to the Offer tab (13.5, 16.1) | Superseded | 24.9, 27.5 |
| Progress banner on Rooms and Offer tabs (13.2, 13.3) | Superseded | 26 |

Unchanged and still valid: AI instructions (1), catalog planning (5), the system prompt (7), the chat on DRAFT offers (10, 11), per-cell AI meta (21, 22), materials in the offer snapshot (23, for offers created later from the estimate), notifications except the deep link (16.2 to 16.5), security (17), configuration (18), i18n (19).


## Glossary

- **Standard**: the client's document "FOREMEN AI Estimator", sections 1 to 15. It is the source of the default system prompt and of the sample content of the catalog `aiInstructions`.
- **Matrix code**: the position code of the client's Matrix such as `1.01` or `13.04`. It equals `work_items.code` (the LP key). It is the primary key the model uses to name a work.
- **AI instructions**: the new `aiInstructions` text on `work_categories` and `work_items`.
- **Estimate job**: a `rooms_import_jobs` row with `mode = ESTIMATE`. One job per project (FOR-05-02 Requirement 19 stays).
- **Phase**: `ROOMS` (extraction, detection, classification, measuring, validation, saving of rooms) and `ESTIMATE` (works planning, quantifying, offer building).
- **AI offer**: an `offers` row with `ai_generated = true`, status `DRAFT`, whose snapshot was built by the job.
- **Assumption / question / unpriced item**: the three report categories of section 8 and 9.2 of the Standard: *assumption* (accepted without drawing proof), *question* (needs the employee's answer), *unpriced* (needed work with no catalog entry).
- **AI report**: `{ assumptions[], questions[], unpriced[], warnings[] }`, stored on the offer (`ai_report`) so it survives the job TTL.
- **Locked project (AI)**: a project with an active estimate job. Room and estimate writes are refused for it.
- **Snapshot material line**: the per-cell record of an `EstimateLineRoomMaterial` stored in the snapshot (Requirement 23.2).
- **Consumption norms**: the existing per-work material consumption rows (`work_material_consumption`: material type/branch, norm per unit of work volume) that "assign work" turns into `EstimateLineRoomMaterial` lines with a copied price range.
- **Scratch replay**: a rollback-only transaction in which a seed (empty, an offer snapshot or the live estimate) and a list of staged edits are replayed on the estimate entities and the result is read as an offer snapshot; the same mechanism as `POST /api/estimates/project/{projectId}/preview`, which already discards its mutations.
- **Cell explanation (`ai` meta)**: the per-cell record `{ origin, volume{source, formulaKey, formulaVolume, finalVolume, correction}, rationale, adjustmentReason, price{pct, scope, reason}, status, confidence, source }` stored in the snapshot cell and, after restore, on the estimate cell. It is what the UI shows as the AI marker and the "Why" card.
- **Chat turn**: one user message with the assistant answer, the tool calls it made and, when the offer changed, the snapshot version before and after.

## Requirements

### Requirement 1: AI instructions on work categories and work items

**User Story:** As a manager, I want to write calculation rules per category and per work in the catalog UI, so that the estimating AI follows the company's rules without a developer.

#### Acceptance Criteria

1. THE backend SHALL add a nullable `ai_instructions TEXT` column to `work_categories` and to `work_items` (Liquibase changesets, guarded by `not columnExists`, registered last in `changelog.xml`) and expose `aiInstructions` on the category and work item DTOs (read, create, update). The value SHALL be a single text in any language, trimmed, at most **8000** characters (`error.validation` otherwise), and `null`/blank SHALL mean "no instructions".
2. THE field SHALL be covered by the existing audit of both entities and SHALL NOT appear in any client-facing read model (offer PDF, client offer, client portal).
3. THE work categories admin page and the work catalog page SHALL show the field as a multi-line "AI calculation rules" input (create/edit forms, and a compact indicator in the table when it is filled). It SHALL be editable by users who may update the respective resource (existing ABAC, no new resource).
4. THE category field is the **section rule** (applies to every work of the category); THE work item field is the **work rule** (applies to that work only). BOTH SHALL be sent to the model: the section rule once per category in the catalog context, the work rule next to the work.
5. A one-time **seed changeset** SHALL fill `ai_instructions` of the 13 work categories from sections 5.1 to 5.13 of the Standard (condensed to rules, not prices), guarded by `onFail="MARK_RAN"` and by "only when the column is NULL" so it never overwrites edited text. Categories are matched by the position prefix of their work codes (`1.xx` to `13.xx`); an unmatched category is left empty.
6. IF the catalog is edited while a job is running, THEN the job SHALL use the instructions as read at the start of its ESTIMATE phase.

### Requirement 2: One "Create estimate" job (modes and stages)

> **Superseded by 24 / 26:** the stage list of 2.2 (`QUANTIFYING`, `BUILDING_OFFER`) and the `offerId` in 2.3 are replaced by `SAVING_ROOMS → PLANNING_WORKS → ASSIGNING → FINALIZING` and by progress counters, an action log and no offer link. Start parameters, guards and 2.4 to 2.8 stay valid.

**User Story:** As a foreman, I want to upload the design project once and get rooms and a priced offer, so that I do not run two separate tools.

#### Acceptance Criteria

1. THE existing start endpoint `POST /api/rooms/project/{projectId}/autoimport` SHALL accept an optional multipart text part `packageCode` (an existing offer package code; default = the estimate's currently applied package, or none) used for the material price ranges of Requirement 23 and recorded as the AI offer snapshot's `appliedPackageCode`, and an optional multipart text part `mode` with values `ROOMS_ONLY` (default, current behaviour, FOR-05-02 unchanged) and `ESTIMATE`. Guards: `ROOMS` CREATE and, for `ESTIMATE`, additionally `ESTIMATE` UPDATE and `OFFERS` CREATE, plus project access. Missing permission SHALL yield `403` before anything is stored.
2. FOR `ESTIMATE`, THE job SHALL run the FOR-05-02 phase `ROOMS` unchanged (`EXTRACTING → DETECTING_ROOMS → CLASSIFYING → MEASURING → VALIDATING`), then the new stages `SAVING_ROOMS → PLANNING_WORKS → QUANTIFYING → BUILDING_OFFER → COMPLETED` (or `FAILED`). Each stage change SHALL be committed in its own `REQUIRES_NEW` transaction so polling shows progress.
3. THE job row SHALL gain `mode`, `phase`, `offer_id` (nullable FK to the created AI offer), `rooms_saved_at` and `rooms_created` (count). `GET /api/rooms/autoimport/{jobId}` and `.../current` SHALL return them, plus `result.estimate` summary (`offerId`, `lines`, `totalNet`, the AI report) when `COMPLETED`.
4. THE job SHALL use the same input files and prompt for both phases (inputs live in storage until the job TTL, FOR-05-02 Requirement 2.4). The prompt MAY contain estimate instructions ("standard Q3 plastering", "apply the +15% coefficient"); they are passed to the planning step.
5. THE `ESTIMATE` job SHALL NOT start when the project has no `DRAFT` estimate state that allows writes: IF the estimate is locked (`error.estimate.locked`), THEN the start SHALL be refused with `409 error.estimate.locked` and nothing stored.
6. `POST /api/rooms/autoimport/{jobId}/cancel` and `DELETE .../{jobId}` SHALL work for `ESTIMATE` jobs as in FOR-05-02 Requirement 24, with the staged-persistence rule of Requirement 4.
7. THE one-active-job-per-project rule (`429 error.roomsimport.in_progress`) SHALL cover both modes; the job table keeps the unique `project_id`.
8. THE feature flag, Gemini credentials and `Disabled*` fallback of FOR-05-02 (`503 error.roomsimport.unavailable`) SHALL apply; an additional switch `foremen.estimate-ai.enabled` SHALL turn the `ESTIMATE` mode and the chat off independently (`503 error.estimateai.unavailable`).

### Requirement 3: Staged persistence — rooms first, offer second

> **Superseded by 24:** "offer second" is replaced by "estimate lines as they are assigned". Criteria 3.1 to 3.5 (rooms saved first, skipped rooms, saved room ids) stay valid.

**User Story:** As a foreman, I want to see the rooms as soon as they are recognised, even while the estimate is still being calculated, so that I can verify them early.

#### Acceptance Criteria

1. WHEN the phase `ROOMS` of an `ESTIMATE` job finishes, THE stage `SAVING_ROOMS` SHALL create all draft rooms in one transaction through the existing bulk-save service (`RoomsSaveService.save`), with the draft's geometry and dimensions, `aiImported = true`, the job id, and the draft media (cropped room pictures) attached as in FOR-05-02 Requirement 9. The "append-only, never update or delete existing rooms" rule (FOR-05-02 Requirement 21) SHALL hold.
2. A room draft that cannot be saved (for example `roomTypeId` unresolved) SHALL NOT fail the job: such a room SHALL be skipped and reported in the AI report as a **question** (`ROOM_NOT_SAVED`, with `params { label, reason }`); the job continues with the saved rooms. IF no room could be saved and none existed, THEN the job SHALL fail with `error.estimateai.failed.no_rooms`.
3. WHEN rooms are saved, THE job row SHALL record `rooms_saved_at` and `rooms_created`, and the UI (Requirement 12) SHALL render the saved rooms while the job is still running.
4. THE ESTIMATE phase SHALL use the **saved room ids** and the saved (calculated) 15 dimensions of those rooms plus the project's pre-existing rooms (Requirement 6.1); it SHALL NOT use unsaved draft values.
5. THE job SHALL run both phases without any user action in between.

### Requirement 4: Project lock while the job runs; cancel and failure semantics

> **Superseded by 24.8:** 4.4 and 4.5 (no offer unless `BUILDING_OFFER` committed; atomic offer creation) are replaced by "already assigned lines are kept on cancel or failure". 4.1 to 4.3, 4.6 and 4.7 stay valid.

**User Story:** As a foreman, I want the rooms to stay consistent while the AI works on them, so that the estimate matches what I see.

#### Acceptance Criteria

1. WHILE a project has an **active** `ESTIMATE` job (non-terminal status), THE backend SHALL refuse every write to the project's rooms (create, update, delete, bulk save, media upload) and to the project's estimate (`assignments` Save, labour price adjustment, offer restore, offer prepare/refresh/update, chat turns) with `409 error.project.ai.locked` and `params { jobId, stage }`. Reads SHALL stay allowed. A `ROOMS_ONLY` job SHALL NOT lock anything (FOR-05-02 behaviour).
2. THE lock SHALL be derived from the job row (no separate flag): it ends automatically when the job reaches `COMPLETED`, `FAILED`, is cancelled/deleted, or is failed by the sweep.
3. THE guard SHALL be implemented once in a shared component called by the room and estimate write paths (a single `AiProjectLockGuard`), not copied per controller, and SHALL be covered by a test that fails when a new write endpoint of rooms/estimate does not call it.
4. WHEN an `ESTIMATE` job is **cancelled** or **fails** after `SAVING_ROOMS` completed, THEN the already saved rooms SHALL be kept (staged persistence), unlocked, and still marked `aiImported`; no offer SHALL exist unless `BUILDING_OFFER` committed. WHEN it is cancelled or fails before `SAVING_ROOMS`, nothing SHALL have been created.
5. THE offer creation (`BUILDING_OFFER`) SHALL be atomic: the offer row, its snapshot and the report are written in one transaction, so a failure never leaves an offer without a snapshot.
6. THE failed job SHALL carry a stable `failureReason` (`error.estimateai.failed.*`: `llm_unavailable`, `no_rooms`, `plan_invalid`, `timeout`) and the UI SHALL offer **Try again**; a retry SHALL start a new job which, per FOR-05-02 Requirement 21, appends rooms again, so before retry the UI SHALL tell the user that rooms already exist and let them choose "reuse existing rooms" (see 4.7).
7. THE start endpoint SHALL accept the option `useExistingRooms` (boolean, `ESTIMATE` mode). WHEN `true`, THE job SHALL skip the `ROOMS` phase and the `SAVING_ROOMS` stage entirely and plan works for the rooms already in the project (also usable by a user who has rooms and only wants the estimate). Then at least one room SHALL exist, otherwise `400 error.estimateai.no_rooms`.

### Requirement 5: Work selection against the catalog (PLANNING_WORKS)

**User Story:** As an estimator, I want the AI to choose works from our own catalog, with our rules, so that the result uses our codes and prices.

#### Acceptance Criteria

1. THE planning step SHALL give Gemini: the system prompt (the Standard's rules, Requirement 7), the user prompt, the design files/text, the saved rooms with their dimensions, the **active catalog** (per work category: `code`, name PL/RU, unit, `aiInstructions` of the category, and per work: `code`, name, unit, room type attachment, `aiInstructions`) and **no prices**. The catalog context SHALL be built once per job and be bounded by `foremen.estimate-ai.max-catalog-chars` (config, default 120000); IF exceeded, THEN categories SHALL be sent in batches and the plans merged.
2. THE structured (JSON-schema) answer SHALL list, per proposed work: `workCode`, `roomRefs[]` (saved room ids), an optional `volume` per room (number + unit + `basis`), a `confidence`, a `source` (file + page), a `status` in `CONFIRMED | ASSUMED | QUESTION` and a mandatory per-room **`rationale`** (why this work in this room, at most 500 chars, Requirement 21.1), plus top-level `assumptions[]`, `questions[]` and `unpriced[] { name, unit, estimatedVolume, reason }`.
3. EACH `workCode` SHALL be resolved server-side to an **active** `WorkItem` by exact `code` match; an unknown code SHALL NOT be guessed: it SHALL move to `unpriced` with reason `UNKNOWN_CODE`. A work whose catalog entry has no price SHALL move to `unpriced` with reason `NO_PRICE` (never a zero line).
4. A work whose Room_Type_Attachment excludes a room's type SHALL still be accepted when the model gave a reason; the report SHALL carry the warning `WORK_ROOM_TYPE_MISMATCH` (`params { workCode, roomId }`). Alternative works (for example plastering Q1/Q2/Q3, FOREMEN Standard section 5.8) SHALL be checked by the rule validator of 5.5.
5. A deterministic **plan validator** (no LLM) SHALL run after planning and add warnings, never reject: `NEGATIVE_VOLUME` (dropped), `DUPLICATE_WORK_ROOM` (merged, last wins), `ALTERNATIVES_BOTH_PRESENT` (works of the same `alternativeGroup` hint returned by the model), `VOLUME_EXCEEDS_ROOM` (floor/wall work volume above 1.5 times the room's corresponding dimension), `ROOM_WITHOUT_WORKS`. All carry `code`, `severity`, `roomId`, `workCode`, `params`.
6. THE step SHALL be retried once on an invalid structured answer and SHALL fail the job with `error.estimateai.failed.plan_invalid` after the second invalid answer.

### Requirement 6: Volumes and prices through the existing engine (QUANTIFYING)

> **Superseded by 24:** the volume and price rules (6.2 to 6.5) stay valid and are applied by the per-assignment path of 24.2. 6.1 (scratch replay) and 6.6 (replay is rolled back) are superseded: the job commits each assignment. `EDIT_REJECTED` now applies to a rejected assignment call.

**User Story:** As an estimator, I want volumes and prices computed by the same engine as a manual estimate, so that AI numbers behave like any other estimate.

#### Acceptance Criteria

1. FOR each planned `(work, room)` pair, THE quantifying step SHALL assign the work to the room on an scratch replay (Requirement 12) by replaying `ASSIGN` staged edits through the existing estimate engine (`VolumeResolver`: work formula, else unit fallback, against the 15 room dimensions; catalog price frozen as `unitPrice` with its `workPrice` provenance), so the volume equals what the manual "apply work" would give.
2. WHEN the model supplied a volume that the formula cannot give or that differs from the formula result by more than a configurable tolerance (default 5 %), THE step SHALL apply the model's volume as a **manual volume override** (`SET_QUANTITY`, `volumeOverridden = true`), and record the formula value in the report line (`formulaVolume`, `overrideVolume`, `basis`) and in the cell explanation (Requirement 21), together with the model's **`volumeReason`** (why the formula was not enough). WHEN the formula gives a volume and the model gave none or one within tolerance, no override SHALL be set.
3. THE per-room cells and per-line totals SHALL be computed by the engine, never by the model. Money SHALL use the engine's `BigDecimal` scale 2 `HALF_UP` rules.
4. A markup/discount stated in the prompt SHALL be applied with the existing scoped labour price adjustment (`ADJUST_LABOUR_PRICE`, scope `PROJECT`, `WORK_CATEGORY` or `WORK_ITEM`, signed `pct`) in the scratch replay, so `unit_price_override` is set and `unit_price` stays original. WHEN no coefficient is stated, none SHALL be applied.
5. The **coordination work** (code `13.01`, "2% of sections 1 to 12") and any percentage-based catalog work SHALL be handled by the existing engine rule for that work when one exists; IF the catalog unit is a percentage with no engine support, THEN the work SHALL be reported as `unpriced` with reason `UNSUPPORTED_UNIT` rather than guessed.
6. THE replay SHALL not persist anything (rolled back). IF a staged edit is rejected by the engine, THEN the pair SHALL be dropped, reported as a warning `EDIT_REJECTED` (`params { workCode, roomId, reason }`), and the job continues.

### Requirement 7: System prompt and rules of the Standard

**User Story:** As a company, I want the Standard's global rules to guide the model, so that results follow our practice.

#### Acceptance Criteria

1. THE backend SHALL keep the global system prompt as a versioned classpath resource (`estimate-ai/system-prompt.md`, English, derived from sections 1 to 4, 6 to 8 of the Standard: role, analysis order, geometry rules, assumptions/questions/unpriced split, no invented data, no duplicates, alternatives are exclusive, coefficients only when stated) with a `version` property recorded on the job (`result.model.promptVersion`).
2. THE global prompt SHALL NOT contain price rates (section 15 of the Standard is replaced by the catalog) and SHALL tell the model to refer to works only by Matrix code from the supplied catalog.
3. THE category/work `aiInstructions` (Requirement 1) SHALL be appended in a fixed structure `### <category code> <category name>` / `#### <work code> <work name>` so the model can attribute the rule.
4. THE prompt text of the user SHALL be passed as a separate, clearly delimited **user instruction** block; instructions inside uploaded documents SHALL be treated as data (stated in the system prompt).
5. THE Gemini model id, temperature and timeout SHALL be configurable under `foremen.estimate-ai.llm.*`, and a `foremen.estimate-ai.llm.stub=true` switch SHALL return a deterministic canned plan for tests (as in FOR-05-02).

### Requirement 8: AI offer and snapshot (BUILDING_OFFER)

> **Superseded by 24 and 25:** the job no longer creates an offer, a snapshot, `BUILDING_OFFER`, a job-to-offer link or the `CREATE_AI_OFFER` audit. The offer columns `ai_generated` and `ai_report` stay and are filled when an offer is **created from the estimate** (25.3). 8.5 (client never sees AI data) stays valid.

**User Story:** As a manager, I want the AI result saved as an offer on the server, so that it survives a new session or another device and can be edited with the tools we already have.

#### Acceptance Criteria

1. THE `BUILDING_OFFER` stage SHALL build an `OfferSnapshot` (the FOR-05-07b version-2 structure: rooms, groups, rows with `workItemId`, `lineNo`, `unitPrice`, `unitPriceOverride`, `comment`, `workPriceId`, and cells extended as in Requirement 21.3: `roomId`, `quantity`, `volumeOverridden`, `formulaKey`, `fallbackUsed`, `ai`) from the scratch replay of Requirement 6 using the existing `OfferSnapshotBuilder` logic, **without** reading or writing the live estimate rows.
2. IT SHALL create an `offers` row: status `DRAFT`, `ai_generated = true`, `ai_job_id`, name `AI estimate <yyyy-MM-dd HH:mm>` (or the name given at start), `revision = 1`, totals computed from the snapshot (FOR-05-07b D7), `ai_report` JSONB (Requirement 9), the project's estimate as the `estimate` reference. New nullable columns `offers.ai_generated BOOLEAN NOT NULL DEFAULT false`, `offers.ai_job_id BIGINT`, `offers.ai_report JSONB` are added by Liquibase.
3. THE project MAY hold several non-terminal offers (FOR-05-07b), so an existing offer SHALL NOT be touched; each successful `ESTIMATE` job adds one AI offer. Re-running the job therefore yields a **new** AI offer, never overwrites an older one.
4. THE job SHALL write one audit row (`CREATE_AI_OFFER`) with the job id, line count and totals.
5. THE AI offer SHALL be **visible to every staff role** with `OFFERS` READ in the project (ABAC and project access unchanged) and **never visible to a CLIENT**: it is `DRAFT`, so the existing client visibility gate returns `404`. WHEN staff later send it (the existing send action), it follows the normal offer lifecycle; the `aiGenerated` flag and the `ai_report` SHALL be **removed from every client-facing read model** (client offer, client PDF, client portal, comparison data returned to a CLIENT).
6. THE AI offer SHALL be restorable into the live estimate with the existing `POST /api/offers/{offerId}/restore` (same endpoint and contract; for an AI offer the restore also seeds the materials, Requirement 23), SHALL be comparable with other offers (FOR-05-07b 2.12), SHALL render as a matrix with the existing snapshot view and be exportable to PDF.
7. THE AI offer SHALL be marked in the list and the card (Requirement 13) and in the offer read models for staff via `aiGenerated` and `aiJobId`.
8. WHEN the snapshot has zero rows, THE job SHALL still complete (an empty offer is created with the report listing why) and the notification SHALL say it is empty only via the report (no separate type).

### Requirement 9: AI report (assumptions, questions, unpriced items)

> **Superseded by 25 for storage:** the report is stored on the estimate (`estimates.ai_summary`) and copied to `offers.ai_report` on offer creation. The report content (9.1 to 9.3) stays valid. 9.4 reads the report from the estimate or from the offer. 9.5 (chat appends `changes[]`) stays valid for the offer copy.

**User Story:** As an estimator, I want to see what the AI assumed, what it could not decide and what is missing from the catalog, as section 9.2 and 9.4 of the Standard require.

#### Acceptance Criteria

1. THE `ai_report` SHALL contain `assumptions[]`, `questions[]`, `unpriced[]`, `warnings[]` and `summary { roomsArea?, lines, totalNet, perM2?, byCategory[] }`; text is returned as **codes + params** wherever the backend generates it, and as the model's own short text (in the language of the user prompt, default Polish) for assumptions/questions the model produced. Model text SHALL be length-limited (500 chars) and stored as plain text.
2. EACH assumption/question/warning SHALL reference, where known, `workCode`, `roomId` and `source` (file + page), so the UI can link to a row.
3. `unpriced[]` items SHALL carry `name`, `unit`, `estimatedVolume?`, `reason` (`UNKNOWN_CODE`, `NO_PRICE`, `UNSUPPORTED_UNIT`, `NOT_IN_CATALOG`) and SHALL be shown prominently; the offer totals SHALL display the flag "incomplete" when `unpriced` is not empty (Standard section 14: "mark incompleteness of the total").
4. THE report SHALL be readable through the offer read model for staff (`GET /api/offers/{offerId}`), with the `OFFERS` READ guard, and be included in the job result while the job is retained.
5. THE report SHALL be updated by chat turns only by appending a `changes[]` entry (Requirement 11.9); assumptions/questions are not rewritten.

### Requirement 10: Offer chat — conversation and persistence

**User Story:** As a manager, I want to refine the AI offer by chatting, so that I can say "add a 10% markup to tiling" instead of clicking through the matrix.

#### Acceptance Criteria

1. THE backend SHALL expose, under the existing `OFFERS` resource and project access: `GET /api/offers/{offerId}/ai-chat` (messages, `OFFERS` READ), `POST /api/offers/{offerId}/ai-chat` (send a user message, body `{ message, clientMessageId }`; `OFFERS` UPDATE + `ESTIMATE` UPDATE) and `POST /api/offers/{offerId}/ai-chat/undo` (undo the last applied turn; same guards). No new ABAC resource SHALL be introduced; the changeset note and a test SHALL state this explicitly.
2. THE chat SHALL be available for **any DRAFT offer with a snapshot** of a project (AI-generated or not). It SHALL be refused with `409 error.offer.not.editable` for a non-DRAFT or terminal offer and with `409 error.project.ai.locked` while an estimate job is active.
3. THE conversation SHALL be persisted per offer in a new table `offer_ai_chat_messages` (`id`, `offer_id` FK ON DELETE CASCADE, `user_id`, `role` USER/ASSISTANT, `content`, `tool_calls` JSONB, `snapshot_before` JSONB nullable, `version_before`, `version_after`, `undone_at`, `created_at`) so a new browser session or another staff member sees the history. Messages SHALL be visible to every staff member who can read the offer and never to a CLIENT.
4. `POST ai-chat` SHALL be synchronous (a turn is a few seconds): it SHALL run the model with the tools of Requirement 11 in a bounded loop (at most `foremen.estimate-ai.chat.max-tool-rounds`, default 8), and answer `{ messages: [user, assistant], applied: boolean, offer: <updated offer summary>, snapshotVersion }`. Concurrent turns on the same offer SHALL be serialised by a per-offer lock; a second concurrent request SHALL receive `409 error.estimateai.chat.busy`.
5. THE message length SHALL be limited (default 4000 characters, `400 error.validation`), and the number of turns per offer per hour SHALL be limited by `foremen.estimate-ai.chat.rate-limit` (default 60; `429 error.estimateai.chat.rate`).
6. THE model context for a turn SHALL be: the system prompt of Requirement 7 (chat variant), the catalog context needed for the tools (loaded lazily through the `find_work` tool, not dumped), a compact **text rendering of the current snapshot** (rooms, works, volumes, prices, overrides, totals) and the last N turns (default 12).
7. THE assistant SHALL answer in the language of the user's message (PL or RU). Backend-generated confirmations SHALL be codes + params rendered by the frontend; model text is shown as is.
8. A model failure SHALL return `503 error.estimateai.unavailable` and persist nothing (neither the user message nor a partial answer), so the user can resend the message.

### Requirement 11: Chat tools — adapters over existing estimate/offer methods

**User Story:** As a developer, I want the chat to act only through functions the system already has, so that behaviour, validation and permissions stay identical to manual editing.

#### Acceptance Criteria

1. THE chat SHALL expose to the model a **fixed toolset**, each tool a thin adapter that builds the same `StagedEdit` (or calls the same service method) as the corresponding existing controller path and replays it in a scratch replay seeded from the offer snapshot (Requirement 12): 
   - `find_work(query, categoryCode?)`: read-only catalog search (code, name PL/RU, unit, active, price present), the same data as the work catalog list;
   - `get_offer_state(filter?)`: read-only compact view of rows/rooms/totals;
   - EVERY write tool takes a mandatory short `reason` argument (why, quoting the user's instruction), which becomes the cell explanation (Requirement 21.5);
   - `assign_work(workCode, roomIds | "all_matching")`: `ASSIGN` edits (volume by the formula, as "apply work" does);
   - `unassign_work(workCode, roomIds | "all")`: `UNASSIGN` edits;
   - `set_volume(workCode, roomId, quantity)` / `clear_volume_override(workCode, roomId)`: `SET_QUANTITY` / `CLEAR_QUANTITY` (manual volume override);
   - `adjust_price(scope, targetCode?, pct)`: `ADJUST_LABOUR_PRICE` with scope `PROJECT | WORK_CATEGORY | WORK_ITEM` and a signed percentage (markup > 0, discount < 0), the same behaviour as the labour price adjustment;
   - `set_line_comment(workCode, text)`: sets the line comment.
2. THE tools SHALL NOT be able to: change room data, delete rooms, send/approve/reject/withdraw offers, change package or discounts, touch the live estimate rows, or call any endpoint outside the list. Adding a tool SHALL require registering it in one `OfferChatToolRegistry` with a name, JSON schema, the permission it needs and a unit test.
3. EACH tool SHALL check the **caller's** permission (`ESTIMATE` UPDATE and `OFFERS` UPDATE for write tools, `OFFERS` READ for read tools) and the project access, using the same ABAC checks as the controllers; a tool executes with the caller's identity, never an elevated one.
4. A write tool SHALL **validate before applying**: an unknown or inactive `workCode` (`error.estimateai.tool.unknown_work`), a room not in the project, a negative quantity, a percentage outside `[-100, 1000]`, a scope target that does not exist. The error SHALL be returned to the model as a tool result (so it can correct itself or ask the user), not as an HTTP error.
5. WHEN the model asks for a destructive or very wide change (more than `foremen.estimate-ai.chat.confirm-threshold` lines affected, default 20, or an `adjust_price` with scope `PROJECT`), THE turn SHALL end with a **confirmation request** instead of applying: the assistant message carries `pendingAction` (the serialised planned edits and a preview of the total before/after), and the user's next "yes"/confirm click (`POST ai-chat` with `confirmPendingId`) SHALL apply exactly those edits. Unconfirmed pending actions expire with the next user message.
6. A turn that applies edits SHALL do it **atomically** at the end of the turn: all edits of the turn are replayed in one scratch replay; IF any edit fails validation by the engine, THEN none of the turn's edits SHALL be applied and the assistant SHALL report the failure.
7. AFTER a successful applying turn, THE backend SHALL rebuild the snapshot from the scratch replay result, update the offer (version + 1, totals recomputed, status unchanged, audit row `AI_CHAT_EDIT` with the turn id, previous version and totals), and store `snapshot_before` in the turn row.
8. **Undo:** `POST ai-chat/undo` SHALL restore the snapshot from the latest applied, not-yet-undone turn's `snapshot_before` (version + 1, audit `AI_CHAT_UNDO`), mark that turn `undone_at`, and append a system message. Only the most recent applied turn can be undone; a later manual snapshot update (refresh/update from estimate) SHALL make older turns non-undoable (`409 error.estimateai.chat.undo_stale`).
9. EVERY applied turn SHALL append a `changes[]` entry to the offer's `ai_report` (turn id, user, summary codes) for staff traceability.

### Requirement 12: Scratch replay — calculate on a seed, persist nothing, never touch the live estimate

> **Scope narrowed by 24.10:** the job no longer uses the scratch replay. `EstimateScratchService` stays **only for the offer chat** (10, 11), where edits target a DRAFT offer snapshot and must not touch the live estimate. 12.1 to 12.7 apply to the chat use only; the `EMPTY` seed is no longer used.

**User Story:** As a developer, I want one engine for estimate calculation, so that AI and manual edits can never disagree.

#### Acceptance Criteria

1. THE existing preview mechanism of `EstimateAssignmentService` (replay of staged edits on the estimate entities inside a transaction that is marked rollback-only, `previewStagedEdits`) SHALL be reused through a new `EstimateScratchService.compute(projectId, seed, edits)`. It SHALL run in its own `REQUIRES_NEW` transaction, take the per-project estimate lock (`SELECT ... FOR UPDATE` on the estimate row, the same lock the Save path takes), apply the **seed**, replay the edits with the existing `applyStagedEdit`, build the result `OfferSnapshot` with `OfferSnapshotBuilder` **inside** the transaction, mark it rollback-only, and return the immutable snapshot value. Nothing SHALL be persisted.
2. THE seed SHALL be one of: `EMPTY` (the estimate's lines are removed inside the scratch transaction, used by the job), `SNAPSHOT(offerId)` (the lines are replaced inside the scratch transaction from the offer snapshot using the planning/replace logic of `OfferRestoreService`, extracted into a shared helper, used by the chat), `LIVE` (no seed; current preview behaviour).
3. THE scratch transaction SHALL NOT fire collaboration broadcasts, `EstimateChangedEvent`, audit rows or notifications (all rolled back or suppressed through the existing replay-cache hook), and SHALL NOT change `estimates.total_*` persistently.
4. THE snapshot seed SHALL honour frozen unit price, `unitPriceOverride`, comments, `volumeOverridden` cells (carried by the extended snapshot cell of Requirement 21.3, which also makes restore preserve the override flag), line numbers and the applied package; cells of rooms removed from the project since the snapshot SHALL be reported (as the restore's `skippedRooms`) and SHALL NOT be changed by tools.
5. THE refactoring of `OfferRestoreService` (shared helper) and of the material seeding (`EstimateMaterialSeeder`, Requirement 23.2) and of the preview code SHALL be behaviour-preserving: existing restore, preview and save tests SHALL stay green, and the endpoints keep their exact contracts.
6. A **round-trip property** SHALL hold and be tested: `snapshot -> scratch (SNAPSHOT seed, no edits) -> snapshot` yields an equal snapshot (rows, cells, prices, overrides, material lines with their override flags, totals), and restoring the resulting snapshot with `OfferRestoreService.restore` yields the same estimate as restoring the original.
7. THE scratch transaction SHALL hold the estimate lock only for its duration and SHALL be time-boxed (`foremen.estimate-ai.scratch-timeout`, default 15 s, `error.estimateai.scratch.timeout`), so a user who presses Save at the same moment waits at most that long. The spec accepts this short serialisation; the alternative (a purely in-memory engine) is rejected as a rewrite of the estimate engine (see design, Alternatives).

### Requirement 13: UI — "Create estimate" button, progress and locked rooms

> **Superseded by 26 and 27:** 13.2 (stepper on the Rooms tab), 13.3 (banner on the Estimate and Offer tabs) and 13.5 ("Open offer" link) are replaced by the shared banner (26) and the Estimate-tab behaviour (27). 13.1, 13.4 and 13.6 stay valid.

**User Story:** As a foreman, I want one button, clear progress, and rooms visible as soon as they are ready.

#### Acceptance Criteria

1. THE "Generate rooms" button on the Rooms tab SHALL be renamed **"Create estimate"** (PL "Utwórz kosztorys", RU "Создать смету") and open the existing generate dialog extended with: prompt, files, an optional offer name, a "use existing rooms (skip room recognition)" toggle (shown only when the project has rooms) and a note that the process takes several minutes and can be left. Submitting SHALL start the job with `mode = ESTIMATE`.
2. THE Rooms tab SHALL show the job progress stepper with the stages of Requirement 2.2 (PL/RU labels), a **Cancel** action, and, as soon as `rooms_saved_at` is set, SHALL render the saved rooms in the matrix (refetch on stage change). While the project is AI-locked, THE rooms matrix, the room editor and the room media actions SHALL be **read-only** with a banner "Estimate is being created. Rooms are locked until it finishes" and disabled controls (not hidden); the backend refusal `409 error.project.ai.locked` SHALL be handled with the same message.
3. THE Estimate tab and the Offer tab SHALL show the same banner and disable their write actions while locked; reading stays possible. A cancelled/failed job SHALL show a banner with the failure reason, **Try again** and, when rooms were saved, the line "N rooms were saved" and the "use existing rooms" option.
4. THE project tab strip SHALL show the running/finished job state as it does for the rooms import (FOR-05-02 Requirement 20): non-blocking, discoverable after navigation or a new session through the current-job endpoint.
5. WHEN the job completes, THE Rooms tab banner SHALL show "Estimate created" with a link **Open offer** to the Offer tab with the AI offer selected, plus the counts of assumptions/questions/unpriced items.
6. THE old rooms-only review flow (draft pre-population, pending change set) SHALL remain reachable through the API only; the UI button no longer starts it. Existing FOR-05-02 UI tests SHALL be updated for the renamed button and kept for the draft components that stay in use.

### Requirement 14: UI — AI offer in the Offer tab, AI report and restore

> **Partly superseded by 25.3:** an offer carries the AI badge and report only when it was created from an estimate that has AI marks (`aiGenerated`). 14.1 to 14.4 stay valid for such offers. The job itself no longer produces an offer.

**User Story:** As a manager, I want to see the AI offer among the other offers, read its report and continue with the normal tools.

#### Acceptance Criteria

1. THE offers list and card SHALL show an **AI** badge (PL "AI", RU "ИИ") for `aiGenerated` offers (staff only) with a tooltip "Created by AI from the design project".
2. THE AI offer's detail view SHALL add a **report panel** (collapsible) with the three lists (assumptions, questions, unpriced), the warnings and the `changes[]`; rows link to the matrix row/room when a `workCode`/`roomId` is known; an "incomplete total" indicator appears when `unpriced` is not empty.
3. THE existing actions SHALL work on the AI offer without change: view snapshot matrix, compare, PDF, **Restore estimate from offer**, refresh/update from the estimate, deactivate, send, delete if allowed. The send action SHALL show a one-time warning for an `aiGenerated` offer with unresolved questions or unpriced items ("This offer was created by AI and has open questions"), with the choice to continue.
4. A **CLIENT** SHALL never receive the badge, the report, the chat, any cell explanation or any `ai*` field (verified by a contract test on the client read models).

### Requirement 15: UI — Offer chat panel

**User Story:** As a manager, I want a chat next to the offer, so that edits are one sentence away.

#### Acceptance Criteria

1. THE Offer tab SHALL show a **chat panel** (docked side panel on desktop, bottom sheet on mobile) for the selected DRAFT offer with a snapshot; for other offers the panel SHALL be hidden or read-only history.
2. THE panel SHALL show the persisted history, an input with send, a "working..." state while a turn runs, example prompt chips (PL/RU), the applied-change summary under each applied assistant message, the **pending confirmation** card with **Confirm** and **Cancel** and the before/after total, and an **Undo last change** button on the most recent applied turn.
3. WHEN a turn applies, THE offer snapshot query, the offer totals and the open snapshot matrix SHALL be invalidated and refreshed without a page reload (FOR-05-07b 2.9).
4. THE panel SHALL be disabled with the lock message while the project is AI-locked, and with a message when the offer is not editable.
5. THE panel SHALL work after a reload or in a new session because the history and the offer live on the server; unsent input text MAY be kept in the browser.
6. THE chat SHALL not be offered where it cannot act: no panel for CLIENT users.

### Requirement 16: Notifications — estimate ready / failed

> **Superseded by 24.9 for the deep link:** the `completed` notification links to `/projects/{projectId}/estimate`. The failure link, types, texts and emission rules stay.

**User Story:** As a foreman who started a long job and navigated elsewhere, I want to be told when the estimate is ready or has failed, so that I do not have to keep checking.

#### Acceptance Criteria

1. WHEN an `ESTIMATE` job transitions to `COMPLETED`, THE backend SHALL create exactly one in-app notification for the **user who started the job**, type `notification.estimateAi.completed`, body `null`, deep-link `/projects/{projectId}/offer?offerId={offerId}`. Texts: PL "Kosztorys AI gotowy", RU "Смета ИИ готова" (EN reference: "AI estimate ready").
2. WHEN an `ESTIMATE` job transitions to `FAILED` (pipeline failure or the stuck-job sweep), THE backend SHALL create exactly one notification for the same user, type `notification.estimateAi.failed`, body = the stable failure reason code, deep-link `/projects/{projectId}/rooms`. Texts: PL "Tworzenie kosztorysu przez AI nie powiodło się", RU "Создание сметы ИИ не удалось" (EN: "AI estimate creation failed").
3. NO notification SHALL be sent for a cancelled job, for chat turns, or for a `ROOMS_ONLY` job (it keeps its FOR-05-02 notifications). The ROOMS phase of an `ESTIMATE` job SHALL NOT emit `notification.roomsImport.*` (one job, one outcome).
4. EMISSION SHALL go through the existing `NotificationService.create(recipient, type, body, deepLink)` (`REQUIRES_NEW`), after the terminal status commits, best-effort (logged and swallowed), at most once per job (status-conditional update, as in FOR-05-02 Requirement 17.8), and skipped when the job row or the recipient no longer exists. This spec SHALL NOT implement any new delivery channel.
5. THE notification type keys SHALL be added to the frontend notification type translations in PL and RU so the bell renders them via `t(type)`; the failure body code SHALL render a localized reason when a translation exists.

### Requirement 17: Security and access

**User Story:** As the company, I want AI features to follow the same access rules as manual work.

#### Acceptance Criteria

1. NO new ABAC resource SHALL be introduced: start/poll/cancel stay under `ROOMS`, offer/AI offer/chat under `OFFERS`, estimate edits under `ESTIMATE`. `AI instructions` follow the existing `WORK_CATEGORIES` and `WORK_ITEMS` resources. The checklist of `entity-creation-rules.md` therefore does not require new resource seeds; `offer_ai_chat_messages` is an internal child table (project scope via `offer.project.id`), covered by tests rather than by a new resource. A grant review SHALL list which roles receive the chat (`OFFERS` UPDATE and `ESTIMATE` UPDATE).
2. EVERY endpoint SHALL verify project access of the offer/job; out-of-scope ids SHALL answer `404 error.entity.not.found`.
3. UPLOADED files and the model output SHALL be treated as untrusted: model-produced text is stored and rendered as plain text (no HTML/markdown execution), identifiers from the model are resolved against the database and never trusted, and the instructions in documents cannot widen the toolset (the toolset is fixed by Requirement 11.2).
4. THE system prompt, catalog context, document text and chat content SHALL NOT be written to application logs above `DEBUG`; logs carry job/offer ids, stage names, durations, token counts and counts only.
5. THE `aiInstructions`, `ai_report` and chat history SHALL NOT be exposed to a CLIENT, to the loaders agent role without the corresponding permission, or to the public bot endpoints.

### Requirement 18: Configuration, observability and failure handling

**User Story:** As an operator, I want to switch the feature off, tune it and see what happened.

#### Acceptance Criteria

1. THE configuration block `foremen.estimate-ai.*` SHALL define: `enabled`, `llm.model`, `llm.temperature`, `llm.timeout`, `llm.stub`, `max-catalog-chars`, `volume-tolerance-pct` (5), `chat.max-tool-rounds` (8), `chat.history-turns` (12), `chat.confirm-threshold` (20), `chat.rate-limit` (60/h), `timeout` for the whole job (default 20 minutes, covered by the FOR-05-02 sweep). It SHALL reuse `GeminiClientFactory` and the `foremen.rooms-import.llm` credentials by default.
2. THE job `stage_log` SHALL record per stage: start/end, counts (works proposed, resolved, unpriced), token counts and retries. No document text SHALL be logged.
3. THE stuck-job sweep (FOR-05-02) SHALL cover `ESTIMATE` jobs with the new job timeout, fail them with `error.estimateai.failed.timeout`, and release the lock.
4. THE estimate job SHALL run on a separate bounded executor `estimateAiTaskExecutor` (default 2 threads, queue 10) so it does not starve the rooms import or OCR executors.
5. CHAT turns SHALL be limited by the Gemini timeout; a timed-out turn SHALL return `503 error.estimateai.unavailable` and leave the offer unchanged.

### Requirement 19: i18n — PL/RU parity (frontend)

**User Story:** As a user, I want the feature in my language.

#### Acceptance Criteria

1. ALL new UI strings SHALL be added to `pl.json` and `ru.json` with identical key sets (parity test as in `rooms.i18n.test.ts`): the renamed button and dialog texts, the stage labels (`SAVING_ROOMS`, `PLANNING_WORKS`, `QUANTIFYING`, `BUILDING_OFFER`), banners (locked, failed, completed), the AI badge and tooltip, the report panel (titles, empty states, reason codes `UNKNOWN_CODE`, `NO_PRICE`, `UNSUPPORTED_UNIT`, `NOT_IN_CATALOG`), the warning codes of Requirement 5.5 and 3.2, the chat panel (title, placeholder, send, examples, working, confirm/cancel, undo, empty history, errors), the catalog field label/help/indicator, the send warning of Requirement 14.3, the package select and the materials lines of the cell card and report (Requirement 23), the cell explanation card of Requirement 22 (titles, volume source labels, adjustment and price labels, status labels, placeholder when no rationale) and the two notification types.
2. BACKEND error/warning codes (`error.estimateai.*`, `error.project.ai.locked`, `error.offer.not.editable`) SHALL each have a PL and RU translation.
3. THE only new backend text column is `aiInstructions` (single language, a model prompt, not UI text). NO RU/PL column pairs are added.
4. NUMBERS, money and dates SHALL use the existing locale formatters.

### Requirement 20: Test-cases artifact

> **Updated by the rework:** `test-cases.md` is rewritten for the direct-to-estimate flow (Requirements 24 to 27); scenarios about job-created offers are replaced.

**User Story:** As QA, I want an executable test-cases document.

#### Acceptance Criteria

1. THE spec SHALL contain `test-cases.md` (Russian, per `.kiro/steering/test-cases.md`) with API scenarios (Part A) and UI scenarios (Part B) against the Dockerized stack, the repeatability strategy (unique run-id names, teardown), a regression group and the MD report template, covering at least: catalog fields, stage-by-stage persistence and lock, cancel/fail semantics, unknown code → unpriced, volume override, markup only when stated, AI offer visibility (staff yes, client no), restore from the AI offer, chat edit/confirm/undo, concurrent chat turn, notifications, materials seeded from norms with parity to a manual estimate (restore of an AI offer, override volume driving material quantity, work without norms, package choice, restore of a manual offer unchanged), per-cell AI marker and explanation (formula used / unit fallback / model override with reason, price markup with reason, chat-edited cell, marker cleared after a manual edit, restore carrying the marker, client never sees it).
2. THE LLM SHALL be run with `foremen.estimate-ai.llm.stub=true` (and the existing rooms vision stub) in the Docker stack so the scenarios are deterministic.

### Requirement 21: Per-cell AI marker and explanation

**User Story:** As an estimator, I want every AI cell to tell me why the AI decided so, whether the room formula was used and what was corrected and why, so that I can trust or fix each number without guessing.

#### Acceptance Criteria

1. EACH cell created or changed by the AI (job or chat) SHALL carry an **AI marker** and an `ai` explanation record with: `origin` (`JOB | CHAT`, with `turnId` for chat), `rationale` (why this work is placed in this room, at most 500 chars, plain text), `status` (`CONFIRMED | ASSUMED | QUESTION`), `confidence` (0..1), `source` (file + page, when known), the `volume` block of 21.2 and the optional `price` block of 21.4.
2. THE `volume` block SHALL state, using facts from the engine and never from the model: `source` in `FORMULA | UNIT_FALLBACK | MODEL_OVERRIDE | CHAT_OVERRIDE`; `formulaKey` and `formulaVolume` (what the engine computed from the room's 15 dimensions through `VolumeResolver.Resolution`, including `Source` and `fallbackUsed`); `finalVolume`; and, when the final volume differs from the formula volume, a `correction` `{ delta, pct }` together with `adjustmentReason`. WHEN the formula was used unchanged, `source = FORMULA`, there is no correction and no `adjustmentReason`. WHEN no formula applied and the unit fallback supplied the value, `source = UNIT_FALLBACK`.
3. THE snapshot cell SHALL be extended backward-compatibly: `OfferSnapshot.Cell(roomId, quantity)` gains optional `volumeOverridden`, `formulaKey`, `fallbackUsed` and `materials[]` (the snapshot material lines of Requirement 23.2) and `ai` (the explanation record), the snapshot `version` becomes 3 for newly built snapshots, readers SHALL accept version 2 (cells without the new fields), and `OfferRestoreService.restore` SHALL set the restored cell's `volumeOverridden` and recreate its material lines from the snapshot (today the snapshot drops it). WHEN the restore sets an estimate cell, it SHALL copy `ai` into the new column `estimate_line_room_qty.ai_meta` (JSONB NULL, new changeset), so the AI marker and explanation are visible in the estimate matrix too.
4. THE `price` block SHALL be present on a cell whose line has an AI-applied price adjustment (`unitPriceOverride` set by the job from a stated coefficient or by a chat `adjust_price`): `{ basePrice, effectivePrice, pct, scope, reason }`. The price block is derived per line and shown on every cell of that line.
5. THE explanation text sources SHALL be: `rationale`, `volumeReason` and the price `reason` from the model (job) or from the tool `reason` argument that quotes the user's instruction (chat), in the language of the user's prompt or message, never translated by the backend; engine facts (`source`, `formulaKey`, volumes, `pct`, prices) are computed by the backend. A chat tool call that changes a cell without a `reason` SHALL be rejected as a tool error so the model supplies one.
6. WHEN the planner returns a cell without `rationale`, THE backend SHALL ask once more for the missing rationales; IF still missing, THE cell SHALL keep the AI marker with `rationale = null` and the report SHALL carry the warning `RATIONALE_MISSING` (`params { workCode, roomId }`). THE backend SHALL NOT invent explanation text. WHEN a model override has no `volumeReason`, the explanation SHALL carry the code `MODEL_VOLUME_DIFFERS` (rendered by the UI) and the warning `ADJUSTMENT_REASON_MISSING`.
7. WHEN a cell later changes by a **non-AI path** (manual `ASSIGN`/`UNASSIGN`/`SET_QUANTITY`/`CLEAR_QUANTITY` in the estimate Save, or a room dimension change that recomputes a non-overridden volume), THE marker SHALL be cleared from that cell (`ai_meta = NULL`) so the marker never describes a number the AI did not produce; a manual `ADJUST_LABOUR_PRICE` SHALL clear the `price` block of the in-scope cells. A chat turn that changes a cell SHALL replace its explanation (`origin = CHAT`, new `rationale` from the tool `reason`, `volume.correction` relative to the formula volume).
8. THE explanation SHALL be staff-only: `ai` is excluded from every client read model (covered by the contract test of 14.4, whose reflection check includes nested cell types), from the client PDF and from the client portal.
9. THE matrix read model (`CellDto`) and the snapshot read model SHALL expose `ai` for staff (`ESTIMATE` READ / `OFFERS` READ); the existing `formulaUsed`, `formulaKey`, `fallbackUsed` and `volumeOverridden` fields of `CellDto` SHALL be reused, not duplicated.

### Requirement 22: UI — per-cell AI marker and "Why" card

**User Story:** As an estimator, I want to see at a glance which cells are AI cells and open the reasoning in one click.

#### Acceptance Criteria

1. IN the snapshot matrix of an offer and in the Estimate matrix, EVERY cell with `ai` SHALL show a small **AI marker** (PL "AI", RU "ИИ") whose colour follows `status` (confirmed, assumed, question) and a distinct icon when the volume was corrected (override) or the price marked up. Cells without `ai` show nothing.
2. CLICKING the cell marker (or focusing it and pressing Enter) SHALL open a **"Why" card** (popover on desktop, bottom sheet on mobile) showing, in this order: the decision `rationale`; the **volume line**: "Formula {formulaKey}: {formulaVolume} {unit}" or "Unit fallback: {value}" or "Corrected: formula {formulaVolume} to {finalVolume} ({+/-pct}%)" followed by the `adjustmentReason`; the **price line** when present: "Base {basePrice}, markup {pct}% to {effectivePrice}" with the reason; `status`, `confidence` and `source` (file, page); the origin ("Created by the estimate job" or "Edited in chat at {time} by {user}").
3. WHEN `rationale` is `null`, THE card SHALL show the localized placeholder "AI gave no rationale for this cell" and the warning from the report; WHEN the reason code is `MODEL_VOLUME_DIFFERS`, THE card SHALL render its localized text.
4. THE markers SHALL be keyboard accessible, have an accessible name (`aria-label` with status), and SHALL NOT shift the cell layout (they overlay a corner). A filter "Show only AI cells" and "Show only corrected" SHALL be available in the snapshot and estimate matrices.
5. WHEN a user edits an AI cell in the estimate matrix, THE marker SHALL disappear after the change is staged (preview shows the cleared state) in line with Requirement 21.7.
6. THE chat panel messages that changed cells SHALL list the changed cells (work, room, old to new) and open the card of a cell on click.
7. THE markers and cards SHALL NOT be rendered for CLIENT users and SHALL NOT appear in the offer PDF for the client.

### Requirement 23: Consumable materials from the consumption norms, stored in the snapshot (estimate parity)

**User Story:** As an estimator, I want the AI estimate to carry the same consumable materials a manual estimate gets from the work norms, including quantity overrides, and to keep them in the offer snapshot, so that restoring the offer gives the estimate I would have built by hand.

#### Acceptance Criteria

1. WHEN the job (or a chat turn) computes the scratch replay, EVERY assigned cell SHALL get its consumable-material lines seeded from the work's consumption norms through the **same seeding code** as "assign work" (one `EstimateLineRoomMaterial` per consumption type and branch, copied `Type_Price_Range` via `PriceRangeResolver` / the package-less or package-aware `FinishingPriceRangeResolver`, norm quantity per work unit), using the **cell's final volume** (formula or override), so the material quantity follows the volume exactly as in a manual estimate. The seeding SHALL be one reusable component `EstimateMaterialSeeder` extracted (behaviour-preserving) from `EstimateAssignmentService` and used by the assign path, the scratch replay and nowhere else; there SHALL be no second implementation.
2. THE snapshot cell SHALL store `materials[]`, one **snapshot material line** per `(cell, branch, material type)` with: `branch` (`construction | finishing`), `constructionTypeId` or `finishingTypeId`, `normQty`, `consumptionBasis`, `rangeMin`, `rangeMax`, `qtyOverridden` (boolean, the **override flag**), `manualQty` (the manual physical quantity when `qtyOverridden`), `quantity` (the effective physical quantity: `manualQty` when overridden, else norm times the cell volume), the chosen product reference when one is set (`concreteConstructionMaterialId` / `concreteFinishingMaterialId` and `concreteNet`), `appliedFromPackage`, and provenance ids (`sourceConstructionMaterialId` / `sourceFinishingMaterialId`). The record SHALL contain everything needed to rebuild the estimate material line exactly.
3. THE snapshot builder SHALL write `materials[]` for **every** snapshot it builds from now on (AI-built and manually prepared offers alike, snapshot version 3), reading the estimate's material lines (including manual overrides and chosen products) in the same transaction. Snapshots of version 2 (no `materials`) SHALL stay readable and SHALL restore without materials exactly as today.
4. WHEN a version-3 snapshot is restored (`POST /api/offers/{offerId}/restore`) THE backend SHALL recreate each cell's material lines **from the snapshot** (not from the norms), preserving `qtyOverridden`, `manualQty`, `normQty`, price range, chosen product and package flag, before the usual recompute. A restored line whose material type or chosen product no longer exists SHALL be recreated as a placeholder (chosen product dropped) or, when the type is gone, skipped, and counted in the restore result (`skippedMaterials`), like `skippedRooms`.
5. THE scratch replay seeded from a snapshot (chat) SHALL load the snapshot's material lines first, so manual quantity overrides and chosen products survive chat edits. WHEN a chat turn changes a cell volume, assigns or unassigns a work, THE replay SHALL recompute (non-overridden lines follow the new volume, overridden lines keep `manualQty`), reseed (new assignment, from the norms) or remove the cell's material lines through the same component, and the new snapshot SHALL store the result.
6. **Parity (acceptance test).** FOR a given set of `(work, room, volume)` triples, THE estimate obtained by restoring the AI offer SHALL equal the estimate obtained by manually applying the same works to the same rooms with the same volumes (and the same overrides and price adjustments) in: lines and line numbers, cell volumes and override flags, labour prices and overrides, material lines (type, branch, norm quantity, effective quantity, override flag, copied price range, count), and the estimate totals. A **round-trip** test SHALL assert `estimate -> snapshot -> restore -> estimate` is identity for materials, including a line with `qtyOverridden = true` and one with a chosen product.
7. THE AI SHALL NOT choose concrete products and the chat tools SHALL NOT add, remove, choose or override materials in this spec: the AI's lines are placeholders at type level, exactly like a manual assign. Overrides and products set by people (in the estimate before a snapshot is built, or carried through a chat edit) are preserved by 23.2 to 23.5.
8. THE materials price ranges SHALL use the package given at start (`packageCode`, Requirement 2.1) or, if absent, the estimate's applied package, or none (the widest range, as manual assign does without a package). The AI offer snapshot SHALL record it as `appliedPackageCode`.
9. A work without consumption norms SHALL yield no material lines and SHALL NOT be an error or a warning. THE AI report `summary` SHALL carry `materials { lines, cellsWithMaterials, overriddenLines, worksWithoutNorms[] }` (informational) and a norm whose material type has no price range SHALL yield the existing "unpriced material" state of the matrix plus the warning `MATERIAL_PRICE_RANGE_MISSING` (`params { workCode, materialTypeId }`).
10. THE snapshot material lines are **staff-only data**: THE client offer read model, client PDF, client portal DTOs, comparison data returned to a CLIENT and the offer totals SHALL NOT include them. Offer totals, offer PDF and comparison stay labour-only. THE staff snapshot matrix view MAY show the stored material lines using the existing estimate cell presentation.
11. THE cell explanation (Requirement 21) SHALL carry `materials { seededFromNorms: true, lines: n, overridden: m }` for each AI cell (engine facts), shown on the "Why" card (Requirement 22.2) as "Materials: n lines from the consumption norms (m overridden)".
12. THE restored estimate SHALL be consistent for the dependent tabs: the Materials tab (FOR-05-05b), BoM and Margins (FOR-05-06) SHALL show the restored lines without any AI-specific code.

### Requirement 24: Direct-to-estimate AI job (per-assignment tool)

**User Story:** As a foreman, I want the AI to write works, volumes and materials straight into the estimate as it works, so that I see the estimate grow and can use the result without restoring an offer.

#### Acceptance Criteria

1. THE worker SHALL expose to the model one write tool `assign_work(workItemId, roomIds[], volume?, volumeReason?, rationale, status, confidence, source?, priceReason?)` that SHALL be executed once per assignment, where an assignment is one work applied to one room or to a listed set of rooms with their volumes. THE planner SHALL NOT produce one plan that is applied at the end.
2. EACH `assign_work` call SHALL go through the normal assign path of `EstimateAssignmentService` with an `AiJobContext` that bypasses the AI project lock for the job's own writes and keeps every other check (project access of the job creator, active work item, price present, formula and unit rules of 6.2 to 6.5).
3. EACH call SHALL run in its own transaction (`REQUIRES_NEW`) and commit, so the cell, its quantity and its materials are visible to any reader immediately after the call returns.
4. FOR every cell assigned by a call, THE call SHALL seed the consumable-material lines from the work's consumption norms through `EstimateMaterialSeeder` (the same component as a manual assign), using the cell's final volume and the job's package (2.1, 23.8). A cell SHALL NOT be committed without its material lines. A work without consumption norms yields no material lines and is not an error.
5. IN the same call and transaction, THE backend SHALL write the per-cell annotation `estimate_line_room_qty.ai_meta` (`CellAiMeta` of Requirement 21: `rationale`, `volume` block with source and override reason, `price` block with reason when a stated coefficient applies, `materials` summary, `status`, `confidence`, `source`, `origin = JOB`). Engine facts come from `CellFactsResolver`, reason texts from the tool arguments. A call that fails SHALL roll back its own transaction completely.
6. WHEN a model volume differs from the formula result by more than `volume-tolerance-pct`, THE call SHALL store it as a manual volume override (`volumeOverridden = true`) with the `volumeReason`; otherwise THE call SHALL use the formula volume (6.2).
7. WHEN a call is rejected (unknown or inactive work, no price, room not in the project, negative volume, missing `rationale`), THE tool SHALL return an error result to the model, THE job SHALL count the rejection in the summary (`EDIT_REJECTED`, `UNKNOWN_CODE` or `NO_PRICE`) and SHALL continue.
8. WHEN the job is cancelled or fails, THE lines, cells, materials and annotations already committed SHALL stay, the lock SHALL end, and the job SHALL stop issuing calls before the next assignment. No rollback of earlier assignments SHALL be attempted.
9. THE job stages SHALL be the FOR-05-02 phase stages, `SAVING_ROOMS`, `PLANNING_WORKS`, `ASSIGNING`, `FINALIZING`, then `COMPLETED` or `FAILED`. `ASSIGNING` SHALL carry the counters of 26.2. `FINALIZING` SHALL write the summary of 25.1. WHEN the job reaches `COMPLETED`, THE completion notification (16.1) SHALL deep-link to `/projects/{projectId}/estimate`.
10. THE job SHALL NOT create an offer, an offer snapshot, a `BUILDING_OFFER` stage, an `AiOfferWriter`, `OfferService.createAiOffer` or a job-to-offer link. Dead code for these SHALL be removed. `EstimateScratchService` SHALL remain only for the offer chat (design decision D-R1).
11. WHILE the job is active, THE backend SHALL keep the estimate and rooms locked for every writer except the job (4.1). The lock SHALL end with the terminal status.
12. WHEN a call repeats an existing `(work, room)` pair, THE call SHALL update the existing cell (last wins) and SHALL NOT create a second line or cell.
13. THE `ai_meta` marks SHALL be visible to every role except `CLIENT`, in the estimate matrix read model (`CellDto.ai`) and in later offer snapshots.

### Requirement 25: AI summary stored on the estimate and copied to offers

**User Story:** As an estimator, I want the AI report to stay with the estimate it describes, and to follow into any offer I create from it.

#### Acceptance Criteria

1. IN `FINALIZING`, THE job SHALL write the report into the new column `estimates.ai_summary` (JSONB NULL, new Liquibase changeset guarded by `not columnExists`): `assumptions[]`, `questions[]`, `unpriced[]`, `warnings[]`, the `incomplete` flag (true when `unpriced` is not empty or the job did not complete), `summary { lines, cells, totalNet, byCategory[], materials { lines, cellsWithMaterials, overriddenLines, worksWithoutNorms[] }, assignments { done, rejected } }`, `promptVersion`, `jobId`, `generatedAt`.
2. THE column SHALL be one shared field per estimate. WHEN a new job completes on the same project, THE job SHALL replace the value. WHEN a job is cancelled or fails after at least one committed assignment, THE job SHALL write the partial summary with `incomplete = true`.
3. WHEN an offer is created from the estimate (the existing prepare action), THE backend SHALL copy `estimates.ai_summary` into `offers.ai_report` and set `offers.ai_generated = true` if the estimate holds at least one cell with `ai_meta` or a non-null `ai_summary`. Per-cell `ai_meta` SHALL continue into the snapshot cell (21.3). Otherwise the offer SHALL keep `ai_generated = false` and `ai_report = null`.
4. THE summary and the cell marks SHALL be visible to every role except `CLIENT`, through the estimate read model (field `aiSummary`) and the staff offer read models. THE client redaction contract test (1.3, 21.8) SHALL fail on any property named `ai*` in a client read model, including `aiSummary`.
5. WHEN a manual change clears the marks of cells (21.7), THE backend SHALL keep `ai_summary` unchanged, and the UI SHALL show its `generatedAt`.
6. THE Estimate tab SHALL show the summary in a collapsible panel (three lists, warnings, counts, totals, materials summary) with an "incomplete total" indicator.

### Requirement 26: Job progress counters, action log and shared banner

**User Story:** As a foreman, I want to see what the AI is doing right now and how far it is, without opening another tab.

#### Acceptance Criteria

1. THE job row SHALL gain `action_log JSONB` and the columns `rooms_total`, `rooms_done`, `works_total`, `works_done` (INT NULL) in one Liquibase changeset guarded by `not columnExists`. THE log SHALL hold at most **200** entries, newest first; WHEN a 201st entry is added, THE backend SHALL drop the oldest. An entry SHALL be `{ at, stage, code, params }`, where `code` is a stable key (for example `ROOM_MEASURED`, `WORK_ASSIGNED`, `MATERIALS_SEEDED`) and `params` holds room label, work code and name, volume and unit. THE log SHALL contain no document text.
2. THE poll DTOs (`GET /api/rooms/autoimport/{jobId}` and `.../current`) SHALL return `actionLog[]` (newest first) and `roomsTotal`, `roomsDone`, `worksTotal`, `worksDone`. `roomsTotal` SHALL be set once detection finishes. `worksTotal` SHALL be set once planning finishes. `worksDone` SHALL increase after each committed assignment. A counter that is unknown for the current stage SHALL be `null`.
3. EACH of these events SHALL append one action entry: a room measured, a room saved, a plan produced (count of works), an assignment committed, material norms applied for an assignment, an assignment rejected, the summary written. Where the event is a database write, THE entry SHALL be appended in the same transaction, so the log never shows an action that was not committed.
4. THE frontend SHALL have ONE shared component `EstimateJobBanner`, used identically on the Rooms tab and the Estimate tab and removed from the Offer tab. It SHALL show the current stage, the lock message, Cancel, and the failed or completed state.
5. WHERE a stage has a known total, THE banner SHALL show text and a bar: "4 of 6 rooms ready", "25 of 200 works assigned" (and materials or cells counts when useful). WHERE the stage is indeterminate, THE banner SHALL show only the step label.
6. THE banner SHALL have an expandable list of the individual actions of the current stage, for example `Room "Kitchen": measured`, `Assigned 3.01 Painting to Bedroom (12.4 m2)`, `Material norms applied`, rendered from `actionLog` by localized templates (PL and RU). The list SHALL be collapsed by default, keyboard accessible, and keep its expanded state across polls.
7. THE banner SHALL NOT render for a `CLIENT` or for a project without a job.

### Requirement 27: UI — live estimate during the job and the go-to-estimate prompt

**User Story:** As a foreman, I want to be invited to the Estimate tab as soon as rooms are ready and to see the estimate fill in there.

#### Acceptance Criteria

1. WHEN the job reports `roomsSavedAt`, THE Rooms tab SHALL show a non-blocking prompt (toast or inline notice, not a modal) "Rooms are ready. Open the estimate to watch it being built" with the action **Go to Estimate**. THE prompt SHALL appear once per job and be dismissible.
2. WHILE an `ESTIMATE` job is active, THE Estimate tab SHALL refetch the estimate matrix every 2 seconds, and also when `worksDone` changes, and SHALL render new lines, quantities, material lines and AI marks as they appear, without a page reload and without losing scroll position or an open "Why" card.
3. WHEN the job reaches a terminal status, THE Estimate tab SHALL do one final refetch and stop polling.
4. WHILE the job is active, THE Estimate tab SHALL stay read-only with the lock banner (4.1); staging controls SHALL be disabled, not hidden.
5. WHEN the user opens the completion notification, THE app SHALL open the Estimate tab (24.9).
6. WHEN `aiSummary` is present, THE Estimate tab SHALL show the summary panel of 25.6 to every role except `CLIENT`.
7. THE Offer tab SHALL NOT show a job banner. Its chat panel SHALL stay disabled with the lock message while a job is active (15.4).
8. ALL new UI strings (banner counters, action templates, prompt, summary panel) SHALL be added to `pl.json` and `ru.json` with identical key sets.
