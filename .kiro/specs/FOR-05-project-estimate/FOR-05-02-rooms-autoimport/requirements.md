# Requirements Document

## FOR-05-02-rooms-autoimport — AI auto-import of rooms (text / images / PDF / office documents), validation, bulk create, per-room media and wall plan drawing

## Introduction

This is a child spec of **FOR-05 (Project Estimate)** and an extension of **FOR-05-02-rooms-dimensions**. Today a foreman types every room and its 15 dimensions by hand into the Rooms matrix. This spec adds an **AI-assisted import**: given a free-text prompt and/or uploaded files, the backend determines how many rooms there are, the type of each room, all 15 dimensions of each room (including wall geometry with doors and windows when it can be derived), validates the result with the existing deterministic calculation engine, and returns a **draft rooms list** for the user's (or the loaders agent's) approval. Approved rooms are created through the existing bulk-save endpoint, which is extended to accept wall geometry and per-room media.

The feature has two consumers of one API:

- **The loaders agent** — calls the API as a tool, using the standard Foremen JWT authentication and ABAC permissions. It shows warnings to the user and proposes corrections or "save as is".
- **The browser UI** — a "Generate rooms" button on the project Rooms tab (`/projects/:projectId/rooms`) pre-populates the Rooms matrix with the draft (the existing pending-change-set mechanism, kept in the browser until **Save**).

The spec also improves the room editor: **each room can have its own media files**; when the room was recognised from an image the **cropped room picture** is attached automatically; when wall geometry exists, an **SVG drawing with wall dimensions and doors/windows** is rendered.

Scope decisions:

- The analysis is **asynchronous**: the start call returns a `jobId`; the caller polls the job (same shape as the FOR-06-03 procurement OCR pipeline).
- **At most ONE autoimport job row exists per project** at any time. While it is running, a new start is refused with `429`. A finished (completed / failed) job is kept as the project's "current job" so a user who navigated away can come back and still see the result; starting a new import replaces it.
- The job runs on the server independently of the browser: the user is **free to navigate** while it runs. The Rooms tab (and the project tab strip) discover the running/finished job through a **current job** endpoint.
- Import is **append-only**: it never updates or deletes existing rooms of the project; imported rooms are only added after the existing ones.
- Rooms created from an import carry a persisted **AI-imported** marker, shown in the UI.
- **Validation never rejects**: problems are reported as `warnings[]` (with an optional suggested fix) and the draft is always returned. Creation is never blocked by a warning.
- The 15 dimensions are the 14 `Wymiary` rows of FOR-05-02 plus `windowCount`; `narrowPlace` (added in FOR-05-02 Round 5) is carried as well.
- **Notifications: two.** When a job reaches `COMPLETED` the user who started it receives "Auto AI import completed"; when it reaches `FAILED` (including a job failed by the stuck-job sweep) the same user receives "Auto AI import failed". Both link to the project's Rooms screen and are emitted through the existing generic `NotificationService` (no bespoke delivery). A cancelled job sends nothing (the user cancelled it themselves).
- A running import can be **cancelled** by the user from the UI (and by the agent through the API); cancellation stops the AI work and frees the project immediately (Requirement 24).
- **Backend i18n: none.** No translatable entity column is introduced. Validation warnings and errors are returned as **codes + params**; the frontend (and the agent) render them. Room types are existing `RoomType` references that already carry RU/PL names.
- Out of scope: images embedded inside DOCX/XLSX files, DWG/DXF/IFC CAD files, editing of the draft on the server (the draft is edited client-side), re-ordering of media, video.

## Glossary

- **Autoimport job**: A persisted asynchronous analysis run (`rooms_import_jobs`) for one project, created by one user, with a status, a stage and — once finished — a result draft.
- **Current job**: The single autoimport job row of a project (any status), returned by `GET /api/rooms/project/{projectId}/autoimport/current`. At most one exists per project.
- **Active job**: A current job whose status is non-terminal (`QUEUED` … `VALIDATING`).
- **AI-imported room**: A room created with a valid `importJobId` of its project; stored in the `rooms.ai_imported` boolean column and exposed as `aiImported` on the room DTO.
- **Applied job**: A job whose draft rooms have been saved (at least one room created with its `importJobId`); it carries `appliedAt`.
- **Draft room**: One recognised room in the job result. It carries a `tempId`, a ready-to-save `room` (shape-compatible with `RoomCreateRequest`), `confidence`, `source` (file name + page), `media[]` (draft media refs), `calculated` metrics and `warnings[]`.
- **Draft media**: A temporary stored file (typically the cropped room picture) produced by a job, addressed by `(jobId, mediaId)`, with a TTL. It becomes **Room media** when a room is saved with a reference to it.
- **Room media**: A persisted file attached to a room (`room_media`), with `kind` = `SOURCE_CROP` (cropped from the source image) or `UPLOAD` (added manually).
- **15 dimensions**: `floorArea`, `wallArea`, `perimeter`, `doorCount`, `doorHeight`, `doorWidth`, `doorArea`, `wallGap`, `finishGap`, `windowCount`, `windowHeight`, `windowWidth`, `windowArea`, `internalCorners`, `ceilingHeight` (plus `narrowPlace`).
- **Geometry-derived metrics**: `floorArea`, `wallArea`, `perimeter`, `doorArea`, `windowArea` (and the counts/gaps/opening sizes), computed by `RoomCalculationService` from `RoomGeometry` and `ceilingHeight`.
- **Stated value**: A dimension value read literally from the source (for example "pow. 12,4 m²" printed on a plan, or a number in the prompt), kept in the draft separately from the value calculated from geometry, so the two can be cross-checked.
- **Warning**: `{ code, severity (INFO | WARNING), roomTempId, field, params, suggestedFix? }`. `suggestedFix` is an optional patch (`{ field: value, … }`) the agent or UI may offer to apply.
- **Stage**: The progress step of a job: `QUEUED → EXTRACTING → DETECTING_ROOMS → CLASSIFYING → MEASURING → VALIDATING → COMPLETED` (terminal OK) or `FAILED` (terminal error).
- **Wall plan**: The client-rendered SVG of a room's polygon with per-wall length labels and door/window symbols.
- **Opening offset**: A new optional `offset` (mb, from the wall's start vertex to the opening's near edge) on `RoomGeometry.Opening`, used only for drawing.

## Requirements

### Requirement 1: Start an autoimport job

**User Story:** As the loaders agent or a foreman, I want to submit a text prompt and/or files for a project and get a job id back immediately, so that long AI analysis does not block my request.

#### Acceptance Criteria

1. THE backend SHALL expose `POST /api/rooms/project/{projectId}/autoimport` accepting `multipart/form-data` with an optional text part `prompt` and zero or more file parts `files`; AT LEAST one of a non-blank `prompt` or one file SHALL be supplied, otherwise the response SHALL be `400 error.roomsimport.input.empty`.
2. THE endpoint SHALL be guarded by `@RequiresPermission(resource = "ROOMS", operation = "CREATE")` plus project-access verification for `{projectId}`; the existing `ROOMS` resource SHALL be reused (no new resource is seeded).
3. WHEN the input is valid, THE backend SHALL persist the job in status `QUEUED`, store the uploaded files, publish an after-commit event handled by an `@Async` worker, and respond `202 Accepted` with `{ jobId, status }`.
4. THE endpoint SHALL accept these file types: images `image/jpeg`, `image/png`, `image/webp`, `image/heic`, `image/heif`, `image/gif`, `image/bmp`, `image/tiff`; documents `application/pdf`; office/text `.docx`, `.doc`, `.xlsx`, `.xls`, `.txt`, `.csv`, `.md`. THE type SHALL be detected from the content type AND the file extension; an unsupported type SHALL yield `415 error.roomsimport.file.type` naming the offending file in `params`.
5. THE endpoint SHALL enforce at most **10 files**, at most **20 MB per file**, and at most **50 PDF pages** in total; violations SHALL yield `413`/`400` with `error.roomsimport.file.size`, `error.roomsimport.file.count` or `error.roomsimport.pdf.pages` and the limit in `params`. The limits SHALL be configurable under `foremen.rooms-import.*`.
6. IF the feature is disabled or the Gemini client is not configured (`foremen.rooms-import.enabled=false`), THEN the endpoint SHALL respond `503 error.roomsimport.unavailable` and persist nothing (a `Disabled*` fallback, mirroring the OCR pattern).
7. THE Spring multipart limits SHALL be raised so a 20 MB file and a request of ten such files can be received (`spring.servlet.multipart.max-file-size`, `max-request-size`); the change SHALL be verified not to break existing upload endpoints that enforce their own smaller limits.
8. IF the project already has an **active** job, THEN the endpoint SHALL respond `429 Too Many Requests` with code `error.roomsimport.in_progress`, a `Retry-After` header (seconds, an estimate), and `params { jobId, stage, startedAt }`, and SHALL NOT store the uploaded files or create anything (see Requirement 19).

### Requirement 2: Poll the job and read the result

**User Story:** As the agent or the UI, I want to poll a job and read its stage and result, so that I can show progress and then act on the draft.

#### Acceptance Criteria

1. THE backend SHALL expose `GET /api/rooms/autoimport/{jobId}` returning `{ jobId, projectId, status, stage, failureReason?, createdAt, completedAt?, result? }`, guarded by `@RequiresPermission(resource = "ROOMS", operation = "READ")` plus project access to the job's project (`404 error.entity.not.found` otherwise).
2. WHILE the job is not terminal, THE response SHALL omit `result`; WHEN `status = COMPLETED`, THE response SHALL include `result` (Requirement 8); WHEN `status = FAILED`, THE response SHALL carry a stable `failureReason` code (`error.roomsimport.failed.*`) and no `result`.
3. EACH stage transition SHALL be committed in its own `REQUIRES_NEW` transaction so a poller observes progress (same technique as `ProcurementOcrWorker.markStage`).
4. THE backend SHALL run a sweep that marks jobs stuck in a non-terminal status beyond a configurable timeout as `FAILED` (`error.roomsimport.failed.timeout`), and deletes jobs, their stored input files and unattached draft media after the configured TTLs (job result 72 h, default; configurable).
5. THE backend SHALL expose `DELETE /api/rooms/autoimport/{jobId}` (discard) which deletes the job, its inputs and its draft media; `ROOMS` CREATE + project access. It SHALL be idempotent (`204` even if already gone). WHEN the job is **active**, the delete acts as a **cancel**: the row is removed, the worker detects the missing row at its next stage boundary and stops without writing a result, and the project is immediately free for a new import.
6. THE backend SHALL expose `GET /api/rooms/project/{projectId}/autoimport/current` returning the project's current job in the same shape as Requirement 2.1 (including `result` when `COMPLETED`, and `appliedAt` when applied), or `204 No Content` when the project has no job; `ROOMS` READ + project access. The loaders agent and the UI use it to check whether an import is running before starting one.

### Requirement 3: Determine the room count

**User Story:** As a foreman, I want the system to work out how many rooms the source describes, so that I do not have to enumerate them.

#### Acceptance Criteria

1. THE pipeline SHALL first run a **room detection** step over all inputs (text and/or each image/page) using Gemini with a structured (JSON-schema) response listing every room: `tempId`, a human `label` (as written in the source when present), a `typeHint`, `sourceRef` (file + page) and, for images, a normalized bounding box.
2. WHEN the prompt states an explicit count ("3 rooms", "dwa pokoje i łazienka"), THE pipeline SHALL pass it as a hint and, IF the detected count differs, THE draft SHALL carry the warning `ROOM_COUNT_MISMATCH` with `params { stated, detected }`.
3. WHEN several inputs describe the same room (the same label and similar dimensions), THE pipeline SHALL de-duplicate to one draft room and add the warning `DUPLICATE_ROOM_SUSPECTED` listing the merged sources, rather than silently dropping either.
4. IF no room can be detected, THEN the job SHALL complete with an empty `rooms[]` and the warning `NO_ROOMS_DETECTED` (not `FAILED`), so the caller can ask the user for better input.
5. WHEN a detected room has the same normalized label and room type as a room that **already exists** in the project, THE draft SHALL carry `POSSIBLE_DUPLICATE_OF_EXISTING` (`params { existingRoomId, label }`); the draft room is still returned (append-only, Requirement 21).

### Requirement 4: Determine each room's type

**User Story:** As a foreman, I want each recognised room mapped to one of our configured room types, so that the estimate rules apply.

#### Acceptance Criteria

1. THE classification step SHALL provide the project-visible `RoomType` catalogue (id, code, RU and PL names) to the model and map each draft room to one `roomTypeId`; the mapping SHALL be validated server-side (the id must exist), never trusted blindly.
2. WHEN the model returns a type name instead of an id, THE backend SHALL resolve it by case-insensitive match against RU/PL/code names.
3. IF a room type cannot be resolved, THEN `room.roomTypeId` SHALL be `null` and the draft SHALL carry the warning `ROOM_TYPE_UNRESOLVED` with `suggestedFix` listing the best candidates; the draft SHALL still be returned (the caller, or the UI's type picker, must set it before saving, because `roomTypeId` is mandatory at creation).
4. EACH draft room SHALL carry a `confidence` in `[0,1]`; WHEN it is below a configurable threshold (default `0.6`), THE draft SHALL carry `LOW_CONFIDENCE`.

### Requirement 5: Determine the 15 dimensions and wall geometry

**User Story:** As a foreman, I want all 15 dimensions filled for each room, preferably from real wall geometry, so that the estimate is accurate.

#### Acceptance Criteria

1. FOR each draft room, THE measuring step SHALL ask Gemini (structured JSON) for: polygon `vertices` in metres (when the shape can be read), per-wall `openings` (type, count, height, width, optional `offset`), per-wall `wallGap`/`finishGap` when stated, `ceilingHeight`, `internalCorners`, `narrowPlace`, and any **stated** values (areas, perimeter, door/window sizes).
2. WHEN geometry is obtained, THE draft `room` SHALL contain `geometry` (vertices + walls + openings) and the backend SHALL compute the geometry-derived metrics with `RoomCalculationService` so the draft's `calculated` block and the saved room agree exactly with the standard save behaviour (geometry present ⇒ the five metrics are `CALCULATED`).
3. WHEN only scalar values can be extracted (a text such as "łazienka 4,2 m², sufit 2,6"), THE draft `room` SHALL carry those values without geometry; on save they become `MANUAL` per the existing contract.
4. THE geometry SHALL be normalised before being returned: vertices ordered, consecutive duplicates removed, units converted to metres (cm/mm/ft hints from the source are applied), walls list length = vertices length, openings with non-positive size dropped with a `OPENING_DROPPED` warning.
5. FOR any of the 15 dimensions that could be neither read nor derived, THE draft SHALL carry `DIMENSION_MISSING` with `field` set, and the value SHALL be left `null` (never invented).
6. THE scale of an image SHALL be derived from written dimensions on the plan; IF no scale can be established, THEN the geometry SHALL be omitted and the warning `SCALE_UNKNOWN` added.

### Requirement 6: Input parsing by type

**User Story:** As a user, I want to give the system whatever document I have, so that I do not need to re-type it.

#### Acceptance Criteria

1. FOR plain text, CSV, MD, DOCX, DOC, XLSX and XLS inputs, THE backend SHALL extract the text (Apache POI for office formats; sheets rendered as tab-separated rows with sheet names) and feed it to the detection and measuring steps together with the prompt; no OCR is used.
2. FOR a PDF, THE backend SHALL extract the text layer with PDFBox; IF a page has too little text (configurable, default < 40 characters) it SHALL rasterize that page (200 DPI, as the OCR pipeline does) and treat it as an image page.
3. FOR image inputs and rasterized pages, THE backend SHALL send the image to Gemini vision. Formats Gemini accepts natively (JPEG, PNG, WEBP, HEIC, HEIF) are sent as is; other raster formats (GIF, BMP, TIFF) SHALL be converted to PNG first.
4. EXIF orientation SHALL be applied before analysis and cropping.
5. A file that cannot be parsed SHALL NOT fail the whole job: it SHALL be skipped with the warning `FILE_UNREADABLE` (`params { fileName }`); IF every file is unreadable and there is no prompt text, THEN the job SHALL fail with `error.roomsimport.failed.no_readable_input`.

### Requirement 7: Validation by calculated fields

**User Story:** As a foreman or the agent, I want the draft cross-checked against the calculation engine, so that obviously wrong numbers are flagged before they reach the estimate.

#### Acceptance Criteria

1. THE validation step SHALL be deterministic (no LLM) and SHALL emit the following warnings, each with `roomTempId`, `field` where applicable, and `params`:
   - `GEOMETRY_INVALID` — fewer than 3 vertices, zero area, or a self-intersecting polygon;
   - `AREA_MISMATCH` — a stated `floorArea` differs from the calculated one by more than 2 % (`params { stated, calculated }`);
   - `PERIMETER_MISMATCH` — same rule for `perimeter`;
   - `OPENINGS_EXCEED_WALL` — door area + window area > gross wall area (perimeter × ceiling height);
   - `OPENING_TOO_WIDE` — an opening wider than its wall (`params { wallIndex, wallLength, width }`);
   - `CEILING_HEIGHT_OUT_OF_RANGE` — outside `[1.8, 6.0]` mb (configurable);
   - `INTERNAL_CORNERS_MISMATCH` — the stated `internalCorners` differs from the count of reflex vertices of the polygon;
   - `DIMENSION_OUT_OF_RANGE` — a value outside the range the existing manual-path range check would accept.
2. WHEN a deterministic fix exists, THE warning SHALL include `suggestedFix` (for example the calculated area to replace a stated one, or the reflex-vertex count for `internalCorners`).
3. EVERY validation outcome SHALL be a warning; validation SHALL NOT remove a room, change a value, or fail the job.
4. THE `calculated` block of each draft room SHALL be the unmodified output of `RoomCalculationService` (so the caller can display the numbers that will be persisted).
5. THE rules SHALL be implemented as pure functions with unit and property-based tests (determinism; an exact polygon with its own stated area never produces `AREA_MISMATCH`).

### Requirement 8: Draft result contract

**User Story:** As the agent, I want one self-contained response I can show to the user and then send for creation, so that no re-mapping is needed.

#### Acceptance Criteria

1. THE job `result` SHALL be `{ rooms: DraftRoom[], warnings: Warning[], inputs: [{fileName, kind, pages?, rasterizedPages?, status}], modelInfo: { model } }` where project-level warnings (for example `ROOM_COUNT_MISMATCH`, `FILE_UNREADABLE`) live in `warnings` and room-level warnings in `rooms[].warnings`.
2. `DraftRoom` SHALL be `{ tempId, confidence, source: { fileName, page? }, room: RoomCreateRequest-compatible, calculated: { floorArea, wallArea, perimeter, doorArea, windowArea, … }, stated: { … }, media: [{ mediaId, kind, contentType, previewUrl }], warnings: Warning[] }`.
3. `room.projectId` SHALL be pre-filled so `room` can be placed into `RoomsSaveRequest.createList` unchanged (plus the media references of Requirement 9).
4. THE result SHALL NOT contain the user's raw uploaded files or any secret; `previewUrl` SHALL point at the authenticated media endpoint of Requirement 9.3 (not a public bucket URL).

### Requirement 9: Draft media, cropped room pictures and attaching them on bulk create

**User Story:** As a foreman, I want the cropped picture of each recognised room to travel with the room, so that the room keeps its visual reference.

#### Acceptance Criteria

1. WHEN a room was recognised on an image/page and a bounding box is available, THE pipeline SHALL crop the room (with a small configurable margin) and store it as draft media `kind = SOURCE_CROP` under a job-scoped storage namespace (`rooms-import/{jobId}/`), using the `ImageStorage` seam (generic binary store; PNG).
2. IF the image cannot be decoded for cropping (for example HEIC), THEN the draft room SHALL carry the whole source image reference as draft media instead and the warning `CROP_UNAVAILABLE`.
3. THE backend SHALL expose `GET /api/rooms/autoimport/{jobId}/media/{mediaId}` streaming the draft media bytes (`ROOMS` READ + project access).
4. THE bulk save request item SHALL be extended with an optional `importJobId` (Requirement 22) and an optional `importMedia: [{ jobId, mediaId }]` on `RoomCreateRequest`. WHEN a room is created, THE backend SHALL verify each reference belongs to a job of the **same project** and the same tenant access, copy the object into the room's permanent namespace (`rooms/{roomId}/`), create a `RoomMedia` row, and keep the draft until the job is discarded or swept.
5. IF a referenced draft media no longer exists (TTL swept, job discarded), THEN the room SHALL still be created and the save response SHALL list the skipped refs in an additive `skippedMedia` field; the save SHALL NOT fail.
6. THE existing contract SHALL remain valid: a `RoomCreateRequest` without `importMedia` behaves exactly as before; `geometry` (with walls and openings) in `createList` is already supported and SHALL be accepted unchanged.
7. THE bulk save SHALL stay a single transaction (FOR-05-02 Requirement 6); media copying failure of one file SHALL NOT roll back room creation (it is reported in `skippedMedia`).

### Requirement 10: Room media API and storage

**User Story:** As a foreman, I want to add, view and remove files on a room, so that photos and plans stay with the room.

#### Acceptance Criteria

1. THE backend SHALL add a `RoomMedia` entity and table `room_media` (`id`, `room_id` FK ON DELETE CASCADE, `file_name`, `content_type`, `size_bytes`, `storage_uri`, `kind` ∈ `SOURCE_CROP | UPLOAD`, `uploaded_by_id`, audit columns) via a Liquibase changeset registered last in `changelog.xml`, modelled on `document_media`.
2. THE backend SHALL expose, under the existing `ROOMS` resource: `GET /api/rooms/{roomId}/media` (READ), `POST /api/rooms/{roomId}/media` multipart (UPDATE), `GET /api/rooms/{roomId}/media/{mediaId}/content` (READ), `DELETE /api/rooms/{roomId}/media/{mediaId}` (UPDATE); all verify project access via the room's project.
3. UPLOADED room media SHALL accept images (JPEG, PNG, WEBP) and PDF, at most 20 MB each and at most 20 files per room (configurable); violations SHALL yield `error.room.media.type` / `error.room.media.size` / `error.room.media.count`.
4. THE room read DTO (`RoomDtoModel`) SHALL gain an additive `mediaCount` (for the matrix badge) without including binary data.
5. WHEN a room is deleted, THE backend SHALL delete its media rows and best-effort delete the stored objects (an orphan-reconcile pass mirrors the `ImageStorage` reconcile contract).
6. Room media endpoints SHALL be audited in the same way as other room mutations.

### Requirement 11: "Generate rooms" button and review flow (Rooms tab)

**User Story:** As a foreman, I want to generate rooms from a prompt or files on the Rooms tab and review them in the matrix before saving, so that I stay in control.

#### Acceptance Criteria

1. THE Rooms tab toolbar SHALL show a **Generate rooms** button, visible to users with `ROOMS` CREATE permission, enabled only while the project/estimate state allows room edits (same gating as the existing Add-room action) AND while the project has no active job (Requirement 20); while a job is active the button SHALL be disabled with a localized tooltip.
2. CLICKING it SHALL open a dialog with: a multi-line prompt, a file drop zone / picker (accepted types and limits of Requirement 1 shown and validated client-side), and a **Start** action.
3. AFTER start, THE dialog MAY be closed at any time (the job keeps running on the server and the user may navigate freely); progress after closing is shown by the in-table indicator of Requirement 20. While the dialog is open it SHALL show the current stage (localized) via the current-job polling of Requirement 20.3. A **Cancel import** action SHALL call `DELETE` on the job (Requirement 2.5).
4. WHEN the job completes (observed live or discovered on a later visit to the Rooms tab, Requirement 20.4), THE UI SHALL convert each draft room into a **new-room draft column** in the existing pending-change-set store (`roomMatrixStore`, persisted in `localStorage` per project), including `geometry`, `importMedia` refs, `importJobId` and the room's warnings, WITHOUT saving anything to the backend, and SHALL append the columns after the project's existing rooms and pending new rooms.
5. THE pending drafts SHALL remain in the browser until the user presses **Save** (existing bulk save, `POST /api/rooms/project/{id}/save`) or discards them; existing undo/redo and "discard all" SHALL apply to imported columns.
6. EACH imported column SHALL show a warning indicator; opening it SHALL list the room's warnings (localized from the codes, with params) and, where present, an **Apply suggestion** action that applies `suggestedFix` to the pending column as a normal edit. Project-level warnings SHALL be shown in a dismissible banner above the matrix.
7. A column whose `roomTypeId` is unresolved SHALL be visually marked and SHALL require choosing a type before Save is allowed (client-side, using the existing required-field behaviour of the new-room column).
8. THE flow SHALL treat the `409 error.estimate.recalc.conflict` of the existing save unchanged (existing `EstimateRecalcDialog`).
9. IF the job fails or the feature is disabled (`503`), THE dialog SHALL show a localized error and allow retrying.

### Requirement 12: Room editor — media section and wall plan drawing

**User Story:** As a foreman, I want to see and manage a room's pictures and see its plan with dimensions, doors and windows, so that I can verify the room at a glance.

#### Acceptance Criteria

1. THE room editor (`RoomEditorPage`) SHALL gain a **Media** section listing the room's media as thumbnails with file name, size and kind badge (`Cropped from source` / `Uploaded`), an upload control (multi-file, type/size validated client-side), a delete action with confirmation, and a full-size preview (images in a lightbox; PDF opened in a new tab).
2. THE Rooms matrix column header SHALL show a small media-count badge when `mediaCount > 0`.
3. WHEN the room has geometry, THE editor SHALL render a **wall plan SVG** (extending `RoomPlanPreview`): the room polygon; a length label on every wall (metres, 2 decimals, placed outside the wall, rotated to follow it); the floor area label at the polygon's centroid; door symbols (gap + swing arc) and window symbols (double line) on their walls, positioned by `offset` when present and centred otherwise; a legend (door / window); and a north-agnostic scale note.
4. THE plan SHALL be rendered client-side from `geometry` (no SVG is stored), SHALL scale to the container, and SHALL be readable with up to 20 vertices; labels SHALL avoid overlapping the polygon and each other where reasonably possible.
5. THE plan SHALL update live as the user edits openings in `RoomOpeningsEditor`.
6. THE plan SHALL be accessible: the SVG SHALL have `role="img"` with a localized `aria-label` summarising the room (area, perimeter), and each opening SHALL have a `<title>` with its type and size.
7. WHEN geometry is absent, THE section SHALL show a localized empty state instead of the plan.
8. `RoomGeometry.Opening` SHALL accept the optional `offset` field; omitting it SHALL not change any calculation or existing stored geometry.

### Requirement 13: Gemini integration and configuration

**User Story:** As an operator, I want the AI integration configured like the existing OCR integration, so that deployment is familiar and safe.

#### Acceptance Criteria

1. THE backend SHALL read configuration from a new `foremen.rooms-import.*` block: `enabled` (default `false`), `llm.{model, project, location, api-key, credentials.{path,location}}`, file limits, TTLs, thresholds, `max-concurrency`; the Gemini client SHALL be built lazily with the same logic as `GoogleOcrService` (API key ⇒ Developer API; otherwise Vertex with ADC or a service-account JSON), extracted into a shared helper rather than duplicated.
2. ALL Gemini calls SHALL request structured JSON output with an explicit response schema and SHALL validate the parsed output; malformed output SHALL trigger one bounded retry and then a per-room (not per-job) failure warning `ROOM_ANALYSIS_FAILED`.
3. THE per-room measuring calls SHALL run with bounded concurrency (`max-concurrency`) and a per-call timeout.
4. NO user content SHALL be logged at INFO level; logs SHALL contain job id, stage, counts and timings only.
5. IF Gemini is unreachable or returns an unrecoverable error for the detection step, THEN the job SHALL fail with `error.roomsimport.failed.llm_unavailable`.

### Requirement 14: Security and access

**User Story:** As an administrator, I want the feature covered by the existing permission model, so that no new bypass is created.

#### Acceptance Criteria

1. ALL new endpoints SHALL be annotated with `@RequiresPermission` on the existing `ROOMS` resource (CREATE for start/discard, READ for polling/media reads, UPDATE for room-media writes); no controller SHALL be left half-annotated (`PermissionAnnotationValidator`).
2. EVERY endpoint SHALL verify project access for the job's/room's project (reusing `ProjectAccessCache`/the `RoomsSaveService.assertProjectAccess` approach) and SHALL return `404` for jobs/rooms outside the caller's access, to avoid existence leaks.
3. UPLOADED content SHALL be treated as untrusted: content-type sniffing in addition to the declared type, no execution of embedded macros (POI read-only text extraction), PDF rendering with PDFBox only, size and page limits enforced before processing, and stored under server-generated keys (the client file name is metadata only, sanitised).
4. THE agent SHALL authenticate with the standard Foremen JWT; no service token or new auth scheme is introduced.

### Requirement 15: i18n — PL/RU parity (frontend)

**User Story:** As a user of either supported language, I want the new UI localized, so that I can use it in my language.

#### Acceptance Criteria

1. ALL new user-facing frontend text SHALL be added to both `foremen-frontend/src/locales/pl.json` and `ru.json` under the existing `rooms` namespace (sub-namespaces `rooms.autoimport.*` and `rooms.media.*` and `rooms.plan.*`), and the existing i18n parity tests SHALL pass.
2. THE localized texts SHALL cover at least: the **Generate rooms** button; dialog title, prompt label/placeholder, file zone hint with accepted types and limits; Start/Cancel/Retry; each stage label (`queued`, `extracting`, `detecting`, `classifying`, `measuring`, `validating`, `completed`, `failed`); the warnings banner title and dismiss; the **Apply suggestion** action; a localized message for **every warning code** of Requirements 3–8 with interpolated params; the localized error for every `error.roomsimport.*` and `error.room.media.*` code; the bell notification types `notification.roomsImport.completed` and `notification.roomsImport.failed` (Requirement 17), the cancel button, its confirmation dialog and the "import cancelled" toast (Requirement 24); the in-table progress text "Auto AI import is in progress" and the tab-label spinner title; the disabled-button tooltip; **Cancel import**; the "generated by AI — review before saving" hint banner; the failed-job banner and **Try again**; the `error.roomsimport.in_progress` message (with the running stage); the **AI** badge and its tooltip "Created by AI import" (matrix header and editor); the Media section title, upload/delete/confirm texts, kind badges (`Cropped from source`, `Uploaded`), empty states; the wall-plan `aria-label`, legend (door, window), and scale note.
3. THE backend SHALL return only codes and params for warnings and errors; it SHALL NOT add translatable columns or message bundles.
4. THE locale values SHALL be authored in the approved PL/RU terminology of the rooms feature (`Wymiary`, room-type names from the catalogue).

### Requirement 16: Observability and failure handling

**User Story:** As an operator, I want to see what the import did and recover from failures.

#### Acceptance Criteria

1. THE job row SHALL store stage timestamps, number of inputs, number of detected rooms, number of warnings and the model id, queryable for diagnostics (no raw content).
2. A failure in one room's measuring SHALL NOT fail the job (Requirement 13.2); a failure in input extraction of one file SHALL NOT fail the job (Requirement 6.5).
3. THE worker SHALL be restart-safe: a job left in a non-terminal status after an application restart SHALL be failed or re-queued by the sweep (same recovery approach as `ProcurementOcrSweepService`).
4. THE feature SHALL include a "Gemini unavailable" integration test using the `Disabled*` fallback to prove the `503` path and that no job row is created.

### Requirement 17: Notifications — auto import completed / failed

**User Story:** As a foreman who started a long import and navigated elsewhere, I want to be told when it is done or has failed and jump straight to the Rooms screen, so that I do not have to keep checking.

#### Acceptance Criteria

1. WHEN an autoimport job transitions to `COMPLETED`, THE backend SHALL create exactly one in-app notification for the **recipient = the user who started the job** (`created_by_id`; for an agent-started job, the agent's authenticated user). No other project member is notified.
1a. WHEN an autoimport job transitions to `FAILED` (a pipeline failure such as `error.roomsimport.failed.llm_unavailable` or `…no_readable_input`, **or** a failure set by the stuck-job sweep `…timeout`), THE backend SHALL create exactly one in-app notification for the same recipient with the **type** `notification.roomsImport.failed`, the **body** = the stable failure-reason code (so the frontend can render a reason-specific suffix, as the procurement OCR failure notification does), and the same deep-link `/projects/{projectId}/rooms`. THE localized message text SHALL be: PL "Automatyczny import pokoi przez AI nie powiódł się", RU "Автоматический импорт комнат через ИИ не удался" (EN reference: "Auto AI import failed"). On arrival, the Rooms tab shows the failed-job banner with **Try again** (Requirement 20.5).
2. THE notification SHALL be created through the generic per-recipient `NotificationService.create(recipientUserId, type, body, deepLink)` (the same service used by `ProcurementOcrNotificationEmitter`), surfaced in the existing bell (unread count, own-notifications-only, mark read/unread, delete); this spec SHALL NOT implement any new delivery channel (email, SMS and Telegram remain future work).
3. THE notification **type** SHALL be the i18n key `notification.roomsImport.completed`; the **body** SHALL be `null` (the localized message is rendered by the frontend from the type key, like the procurement OCR types). THE localized message text SHALL be: PL "Automatyczny import pokoi przez AI zakończony", RU "Автоматический импорт комнат через ИИ завершён" (EN reference: "Auto AI import completed").
4. THE notification SHALL carry the **deep-link** `/projects/{projectId}/rooms`, making it clickable in the bell and navigating to the project's Rooms screen; on arrival the Rooms tab discovers the completed job through the current-job query and renders the result per Requirement 20.4.
5. THE emission SHALL happen after the `COMPLETED` status and result are committed, SHALL be best-effort (a failure to notify is logged and swallowed, never changes the job's terminal state), and SHALL be skipped when the job row no longer exists (cancelled or replaced) or when the recipient user no longer exists.
6. THE completed notification SHALL be emitted also when the draft contains zero rooms or only warnings (the job completed). NEITHER notification SHALL be emitted for a **cancelled** job (Requirement 24), including a job whose row was deleted while the worker was finishing.
7. THE notification type keys `notification.roomsImport.completed` and `notification.roomsImport.failed` SHALL be added to the frontend notification type translations (PL and RU) so the bell renders them via `t(type)`; the backend SHALL store only the key (and, for failures, the reason code in the body).
8. THE failed notification SHALL be emitted at most once per job: the transition to `FAILED` is guarded by a status-conditional update (`… WHERE id = ? AND status NOT IN (terminal)`), so a worker failure racing the sweep cannot produce two notifications.

### Requirement 19: One job per project and concurrency control

**User Story:** As an operator, I want at most one import per project, so that two runs cannot flood the AI service or produce conflicting drafts.

#### Acceptance Criteria

1. THE table `rooms_import_jobs` SHALL carry a **unique constraint on `project_id`**, so at most one job row exists per project at any time (race-safe under concurrent requests).
2. THE start operation SHALL run in one transaction that locks the project's job row (`SELECT … FOR UPDATE` / unique-violation handling): IF the existing job is **active**, THEN respond `429 error.roomsimport.in_progress` (Requirement 1.8); IF it is **terminal** (`COMPLETED` or `FAILED`), THEN delete it (row, stored inputs, unattached draft media) and insert the new job; IF none exists, insert.
3. WHEN two start requests race, EXACTLY one SHALL succeed with `202` and the other SHALL receive the same `429` (a unique-violation on insert is mapped to `429`, never `500`).
4. THE `429` body SHALL be the standard error envelope with a meaningful `message`/`code` and `params { jobId, stage, startedAt }`, and SHALL carry a `Retry-After` header; the agent can use it to wait and poll `GET …/autoimport/current` instead of retrying blindly.
5. WHEN a terminal job is replaced by a new start, draft media of the replaced job SHALL become unavailable; saving a room that still references them SHALL follow the soft-skip rule of Requirement 9.5.
6. THE sweep SHALL fail jobs stuck beyond `stuck-timeout` (Requirement 2.4) so that a crashed worker never blocks a project permanently; a failed job is "terminal" for the purposes of criterion 2.
7. THE limit SHALL be per project only: different projects may import concurrently, bounded globally by `max-concurrency` of the worker executor (excess jobs wait in `QUEUED`).

### Requirement 20: Current job discovery and non-blocking UX

**User Story:** As a foreman, I want a long import to keep running while I work elsewhere, and to see its state or result when I come back, so that I am never blocked.

#### Acceptance Criteria

1. THE job SHALL run entirely server-side; closing the dialog, reloading or navigating to other pages or projects SHALL NOT affect it.
2. THE Rooms tab SHALL query the current job (`GET /api/rooms/project/{projectId}/autoimport/current`) on mount; IF the response is `204`, no import UI is shown beyond the **Generate rooms** button.
3. WHILE the current job is **active**, THE Rooms tab SHALL poll it (every 3 s, backing off to 6 s after 60 s; paused while the browser tab is hidden) and SHALL render, **inside the rooms table area**, a spinner with the localized text "Auto AI import is in progress" (PL/RU equivalents) plus the localized current stage; existing rooms and pending edits remain visible and editable around it. THE project tab strip SHALL show a small spinner on the Rooms tab label while the job is active.
4. WHEN the current job is **COMPLETED** and not yet applied, THE Rooms tab SHALL render its result as pending new-room columns (Requirement 11.4) together with a dismissible hint banner "These rooms were generated by AI — review before saving" (localized) and an **AI** marker on each imported column. THE UI SHALL remember in `localStorage` which job ids were already materialised so the same result is not appended twice on reload.
5. WHEN the current job is **FAILED**, THE Rooms tab SHALL show a localized error banner with the failure reason and a **Try again** action (opens the dialog); dismissing the banner SHALL call `DELETE` on the failed job.
6. WHEN the current job is **COMPLETED and applied** (`appliedAt` set), THE UI SHALL NOT re-materialise it; the saved rooms are identified by their AI-imported marker (Requirement 22.4).
7. WHEN the user presses **Discard all** while imported columns are pending, THE UI SHALL call `DELETE` on the job. Closing the AI hint banner only hides it (no server call). A terminal job needs no explicit deletion to allow a new import, because starting replaces it (Requirement 19.2).
8. THE agent SHALL be able to call the same current-job endpoint before starting an import to learn whether one is running (and its stage), and to fetch the result of a finished one.

### Requirement 21: Append-only import

**User Story:** As a foreman, I want an import to never change or remove rooms I already have, so that an existing room plan is safe.

#### Acceptance Criteria

1. THE autoimport pipeline and its result SHALL contain **create-only** data: it SHALL NOT update, merge into, or delete any existing room.
2. WHEN the project already has rooms, THE imported rooms SHALL be added **after** them (the existing `sort_order` append-on-create behaviour), and the UI SHALL append the pending columns after the existing rooms and any pending new rooms.
3. THE pipeline SHALL read the project's existing rooms only to produce `POSSIBLE_DUPLICATE_OF_EXISTING` warnings (Requirement 3.5); duplicates are NOT removed or skipped automatically — the user (or the agent) decides.
4. THE documented agent contract SHALL state that approved drafts are saved with `createList` only (no `updateItems`/`deleteIds`); the backend SHALL NOT add any auto-update/auto-delete behaviour keyed on `importJobId`.
5. THE existing estimate recalculation gate (`409 error.estimate.recalc.conflict`, `force`) SHALL apply unchanged to saving imported rooms.

### Requirement 22: AI-imported marker on rooms

**User Story:** As a user, I want rooms created by the AI import to be marked, so that I can tell them from hand-entered rooms and review them.

#### Acceptance Criteria

1. THE `rooms` table SHALL gain a column `ai_imported BOOLEAN NOT NULL DEFAULT false` (Liquibase changeset, idempotent) and `RoomEntity` a matching `aiImported` field; existing rows SHALL be `false`.
2. `RoomCreateRequest` SHALL accept an optional `importJobId`. WHEN present, THE backend SHALL verify that the job exists, belongs to **the same project** and that the caller has access to it; ON success it SHALL set `ai_imported = true` on the created room and set the job's `applied_at` (first time); ON failure (unknown/foreign/expired job) the room SHALL still be created with `ai_imported = false` and the reference SHALL be reported in `skippedMedia`-style response field `skippedImportRefs` — saving is never blocked.
3. THE marker SHALL NOT be settable by a plain boolean in the request (it is derived only from a verified `importJobId`), and SHALL NOT be changeable through `PUT` update requests.
4. `RoomDtoModel` and `RoomDtoExtendedModel` SHALL expose `aiImported: boolean`; the `GET /api/rooms` list SHALL support an optional filter `aiImported=true|false`.
5. THE Rooms matrix SHALL show a small localized **AI** badge in the column header of every saved room with `aiImported = true` (tooltip "Created by AI import"), and the room editor SHALL show the same badge near the title.
6. THE marker SHALL persist after the user edits the room (the rooms remains "AI-imported"; a manual edit does not clear it).
7. THE marker SHALL be written to the audit trail like other room fields.

### Requirement 24: Cancel a running import

**User Story:** As a foreman or the loaders agent, I want to cancel an import that is running, so that a wrong upload or a stuck run does not block the project or waste AI calls.

#### Acceptance Criteria

1. THE backend SHALL expose `POST /api/rooms/autoimport/{jobId}/cancel` (`ROOMS` CREATE + project access) for an **active** job: it removes the job (row, stored inputs, draft media) and responds `204`; the project is free for a new import immediately. `DELETE /api/rooms/autoimport/{jobId}` (Requirement 2.5) remains and behaves identically for an active job.
2. IF the job is already terminal, THEN `…/cancel` SHALL respond `409 error.roomsimport.not_cancellable` (nothing deleted; discarding a finished job is `DELETE`); IF the job does not exist (already cancelled or replaced), THEN `404 error.entity.not.found`. The UI treats `404` after a cancel click as success.
3. CANCELLATION SHALL be cooperative and prompt: the pipeline checks a cancellation signal (job row still present) before each stage and before launching every per-room Gemini call, no further AI calls are started, in-flight calls are allowed to finish and their output is discarded, and no result, warning or notification is written for the job.
4. ANY user with `ROOMS` CREATE on the project MAY cancel the project's running job (not only the starter); the agent MAY cancel through the same endpoint.
5. THE Rooms tab SHALL show a **Cancel import** button in the in-table progress indicator (Requirement 20.3) and in the open dialog; clicking it SHALL ask for confirmation (localized: "Cancel the running AI import? Progress will be lost."), call the cancel endpoint, then immediately refresh the current-job query so the spinner disappears and **Generate rooms** becomes enabled; a localized "Import cancelled" toast SHALL be shown.
6. IF the job finishes between the click and the request (`409`), THE UI SHALL show the finished state (completed result or failed banner) instead of an error.
7. A cancelled job SHALL emit no notification (Requirement 17.6) and leave no row in `rooms_import_jobs`; stored objects of the cancelled job SHALL be removed after commit and any late-written objects by the sweep (design B2a).

### Requirement 23: Test-cases artifact

**User Story:** As QA, I want a documented test-case suite for this feature.

#### Acceptance Criteria

1. THE spec folder SHALL contain `test-cases.md` (written in Russian) following the `.kiro/steering/test-cases.md` standard: API scenarios against the Dockerized app (start → poll → draft → bulk create → media), UI browser scenarios for the Rooms tab and room editor, repeatability via a generated run-id and teardown, a regression section and an MD report template.
2. Scenarios needing Gemini SHALL state how determinism is achieved (a stub LLM profile for CI; a live-Gemini smoke scenario marked as non-blocking).
