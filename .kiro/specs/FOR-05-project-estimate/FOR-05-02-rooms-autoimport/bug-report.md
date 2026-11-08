# Bug report: FOR-05-02-rooms-autoimport

## Round 1 (2026-11-06)

Screen URL: `http://localhost:3000/projects/2/rooms/1697` (room editor → Media section; one `SOURCE_CROP` file).

- [ ] 1. Fix the media files render
  - Triage: observed — clicking the media item opens the lightbox but the picture never appears. Browser console: `Loading the image 'blob:http://localhost:3000/…' violates the following Content Security Policy directive: "img-src 'self' data: https://cdn.loaders.dev …"`. The grid shows only a generic icon and the stored file name (a UUID such as `2085f3d6-….png`), no thumbnail and no size. Expected (Requirement 12.1) — thumbnails with file name, size and kind badge, a full-size image preview, PDF opened in a new tab.
  - Root cause: the nginx CSP (`foremen-frontend/nginx.conf`, SPA `location /`) does not allow `blob:` in `img-src` and `frame-src`, so the authenticated blob preview (`MediaLightbox.tsx`) and the PDF iframe are blocked. Vite dev has no CSP, so it only fails in the Docker/prod build.
  - Fix:
    - Add `blob:` to `img-src` and `frame-src` in the CSP (no wider change).
    - `RoomMediaGrid`: real image thumbnails through a small reusable authenticated-blob hook (`useAuthBlobUrl`, created/revoked per item, lazy) with the icon as fallback; show file size; PDF items keep an icon.
    - `MediaLightbox`: PDF opens in a new tab from the blob URL (as the requirement says), images stay in the lightbox.
    - Friendly display name for AI crops (for example "Fragment planu — {room}") instead of the UUID; the stored name is unchanged.
  - Layer: frontend only (nginx config + rooms media components). Backend content endpoint already returns the correct `Content-Type` and `inline` disposition.
  - i18n (PL/RU, `rooms.media.*`): file size format label, "open PDF in new tab", crop display name. No notifications.
  - _Requirements: 12.1, 10.2_
  - _Depends on: none_
- [ ] V1. Validate Round 1 in browser
  - Rebuild the frontend container, open `/projects/2/rooms/1697`: thumbnail visible in the grid, lightbox shows the full image, no CSP error in the console; upload a PDF and check it opens in a new tab.
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1 — CSP + media grid/lightbox (frontend).
- **Wave 2:** V1 — browser validation; depends on 1.

## Round 2 (2026-11-07)

Screen URL: `http://localhost:3000/projects/2/rooms` (Generate rooms → AI import result columns / room editor plan).

- [~] 1. Adjust instructions for AI room import — first try to use the most matching template
  - Triage: observed — the measure step asks Gemini for free polygon vertices only (`RoomsImportPrompts.MEASURE_SYSTEM`, `RoomsVisionSchemas.measure`), so simple rooms come back as noisy polygons (e.g. 89.999° corners, slightly skewed walls). Expected — the model first picks the closest of the six room templates (the same as the editor's "Шаблоны": RECTANGLE, L_SHAPE, CUT_CORNER, T_SHAPE, U_SHAPE, CHAMFERED) with its dimensions and rotation, and only returns a free polygon when no template fits.
  - Fix (backend):
    - Schema: optional `template { id, rotation (0|90|180|270, clockwise), dims { <key>: Measured } , confidence }` next to `vertices`; `vertices` becomes optional when `template` is given. Dimension keys exactly as in the frontend `roomTemplates.ts` (width, length, cutWidth, cutLength, cutSize, topDepth, stemWidth, stemDepth, notchWidth, notchDepth, chamferSize).
    - Prompt: describe each template (shape, its dimension keys, the wall order starting at the bottom-left corner going counter-clockwise, so openings can be put on the right `walls[i]`), instruct "choose the template that matches the room outline; use free vertices only if none fits or the room has non-right angles that no template has; copy dimensions as written (units rules unchanged)".
    - New pure Java `RoomTemplates` (mirror of the frontend builder + the Round 9 rotation, same vertex order/start) in `com.foremen.service.roomsimport.geometry`; the pipeline converts the template dims with `UnitConverter`, runs `PlausibilityCorrector` on them, builds the vertices, then continues as today (normalizer → validators). If the template id is unknown or its dims are invalid (cut ≥ outer size etc.), it falls back to the model's `vertices` when present, otherwise to scalar values, with `GEOMETRY_INVALID` (reason `TEMPLATE_INVALID`).
    - New INFO warning `GEOMETRY_FROM_TEMPLATE` (`params { template, rotation }`) so the user sees which template was used; added to `WarningCodes.ALL` and the frontend `WARNING_CODES` with PL/RU texts (template name from `rooms.form.templates.names.*`).
    - Stub: new marker `STUB:TEMPLATE_L` returning an L-shape template answer (mm dims, rotation 90, a door on wall 0); existing markers unchanged.
  - Parity test: Java `RoomTemplates` and the frontend `roomTemplates.ts` produce the same vertices for the same template, dims and rotation (shared fixture JSON under `src/test/resources/roomsimport/templates-fixtures.json`, read by both test suites… frontend reads a copy in `src/features/rooms/lib/__tests__/fixtures/`).
  - Layer: both (backend main; frontend only the warning code + texts). Depends on rooms-dimensions Round 9 #1 for the rotation convention.
  - _Requirements: 5.1, 5.2, 5.4, 13.2, 25.1_
  - _Depends on: none (cross-spec: rooms-dimensions Round 9 #1)_
- [ ] V2. Validate Round 2
  - Backend tests (pipeline + `STUB:TEMPLATE_L` integration test); in the browser with the stub on: generate rooms with `STUB:TEMPLATE_L`, the imported column shows the `GEOMETRY_FROM_TEMPLATE` info and the room plan is a clean L (all 90°). Live Gemini smoke check optional (needs a refreshed Google login).
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 2

- **Wave 1:** 1 — after rooms-dimensions Round 9 #1 (rotation convention), backend + frontend warning text.
- **Wave 2:** V2 — validation.

## Round 2 — validation results (2026-11-07)

- **#1 Template-first import — implemented, verified by tests only (`[~]`).** Backend: Java `RoomTemplates` (parity with the frontend fixture, 24 entries), template block in the measure schema/validator, prompt describes the six templates with wall order and rotation, pipeline builds the outline from the template (dims converted, outline plausibility-checked, vertices rounded to 3 decimals) with `GEOMETRY_FROM_TEMPLATE` INFO, fallback to free vertices / scalars with `GEOMETRY_INVALID` `TEMPLATE_INVALID`; stub marker `STUB:TEMPLATE_L`. Frontend: `GEOMETRY_FROM_TEMPLATE` in `WARNING_CODES` (last, as in `WarningCodes.ALL`), PL/RU texts with the localized template name.
  - Tests: backend `com.foremen.service.roomsimport.*` + `RoomsAutoimportFlowIT` 253 passed (incl. `STUB:TEMPLATE_L` → L outline (0,0)(2,0)(2,2)(4,2)(4,5)(0,5), 16.00 m²); frontend 886 passed.
  - Not verified in the browser: the local stack runs live Gemini (`FOREMEN_ROOMS_IMPORT_LLM_STUB=false`) and the Google login expired (`invalid_rapt`), so an import cannot start. To check: refresh the login and import a plan, or switch the stub on and use `STUB:TEMPLATE_L`.
- [ ] V2 stays open until the browser check.

## Round 3 (2026-11-08)

Screen URL: `https://dev.foremen.eu/projects/<id>/rooms` (Generate rooms → AI import). Environment: dev VM (`infra/dev`).

- [ ] 1. "Automatic room import via AI is unavailable" on dev.foremen.eu
  - Triage: observed — starting an AI import on dev returns `503 error.roomsimport.unavailable` ("Автоматический импорт комнат через ИИ недоступен"). Expected (Requirements 13.1, 18.2) — import starts on dev with live Gemini.
  - Root cause (config, not code): `RoomsImportService.start` throws 503 when `!visionClient.isEnabled() || !storage.isConfigured()`.
    - `visionClient.isEnabled()` is false: `foremen.rooms-import.enabled` defaults to `false` (`application.yml`), which wires `DisabledRoomsVisionClient`. The dev stack never sets it: `infra/dev/docker-compose.dev.yml` (backend env) and `infra/dev/vm-startup.sh` (the generated `.env`) contain no `FOREMEN_ROOMS_IMPORT_*` variables at all (only `FOREMEN_OCR_*`). Local Docker works only because the root `docker-compose.yml` defaults it to `true` and `docker-compose.override.yml` turns on live Gemini; that was never mirrored to the dev VM.
    - `storage.isConfigured()` is NOT the cause: dev already sets `FOREMEN_IMAGE_STORAGE_BUCKET` / `FOREMEN_IMAGE_STORAGE_CDN_BASE`.
  - Missing on dev (checklist):
    1. `FOREMEN_ROOMS_IMPORT_ENABLED=true` (backend env in `docker-compose.dev.yml`).
    2. `FOREMEN_ROOMS_IMPORT_LLM_STUB=false` — pin explicitly (backend default is already false, but the root compose defaults it to true, so pin it to avoid drift).
    3. Gemini credential: nothing to add. The same key is shared with OCR (confirmed by the user): `llm.api-key` falls back to `foremen.ocr.llm.api-key`, which dev already passes as `FOREMEN_OCR_LLM_API_KEY` (from Secret Manager `foremen-dev-ocr-gemini-key`). Local override sets the same key explicitly only because it has no fallback chain wired in compose.
    4. Optional: `FOREMEN_ROOMS_IMPORT_LLM_MODEL` (defaults to the OCR model `gemini-3.8-flash`, same as dev's OCR model).
    - Local vs dev diff (everything else already matches): image storage bucket/CDN/signer SA/`GOOGLE_CLOUD_PROJECT` are present on dev; `FOREMEN_OCR_ENABLED=false` locally too, so OCR being off is not a factor; ADC mount is not needed on the VM (attached SA).
    5. Redeploy the backend on the VM so the new compose/env are applied (`infra/dev/55-redeploy.sh` / `56-sync-stack.sh`). The live copy `infra/dev/.vm-live/` is git-ignored and must be kept in sync with `docker-compose.dev.yml` (it is currently already out of sync: it lacks the FOR-07-04 bank block).
  - Fix: add the variables above to `infra/dev/docker-compose.dev.yml` (default `true` / stub `false`), and optionally to `vm-startup.sh` only if a separate key is wanted (not needed when reusing the OCR key). No application code change, no migration, no i18n or notification change.
  - Layer: infrastructure (dev deployment config).
  - _Requirements: 13.1, 18.2_
  - _Depends on: none_
- [ ] V3. Validate Round 3 on dev
  - After redeploy, `GET`/start import on `https://dev.foremen.eu/projects/<id>/rooms`: the dialog no longer shows the unavailable message, a job is created and reaches a terminal state (success or a domain failure, not 503). If it fails with `llm_unavailable`, check the Gemini secret (item 3). Requires access to the dev VM / deploy pipeline, so it may need the user to run the redeploy.
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 3

- **Wave 1:** 1 — dev compose/env change.
- **Wave 2:** V3 — redeploy + validation on dev.

## Round 3 — validation results (2026-11-08)

- **#1 Dev config — applied, verified on the VM only (`[~]`).** Added `FOREMEN_ROOMS_IMPORT_ENABLED` (default `true`) and `FOREMEN_ROOMS_IMPORT_LLM_STUB` (default `false`) to `infra/dev/docker-compose.dev.yml`; ran `infra/dev/56-sync-stack.sh` (drift check passed, compose + DB backed up as `*.bak.20261108-162255` / `pg-20261108-162255.sql.gz`, backend and frontend redeployed, both stably healthy). Inside `foremen-backend-1`: `FOREMEN_ROOMS_IMPORT_ENABLED=true`, `FOREMEN_ROOMS_IMPORT_LLM_STUB=false`, OCR Gemini key present, model `gemini-3.8-flash`.
- **V3 stays open** until an import is started from `https://dev.foremen.eu/projects/<id>/rooms` in the browser (the dev login was not driven from here).
