# Implementation Plan: FOR-06-03 — Procurement intake + OCR auto-match

## Overview

This plan delivers procurement intake + automatic matching as a full vertical slice: the backend
`ProcurementDocument` + `ProcurementLine` entities (schema → entities → candidate provider → pure
match engine → new Java OCR service → project-scoped service → `@PermissionResource` controller →
DTOs/mappers → ABAC seed) and the frontend Procurement panel (document list + upload + read-only
proposed-match review + image viewer) wired into the already-reserved `procurement` execution tab.

It introduces **two** new persisted entities (`procurement_documents`, `procurement_lines`) and
**one** new project-scoped ABAC resource `PURCHASES` (shared with the sibling FOR-06-04, seeded here
because this child needs it first), so it follows `.kiro/steering/entity-creation-rules.md` in full —
the entities ARE project-scoped, so step 4 (`ProjectScopedService` with `getProjectIdPath()` →
`"project.id"`) applies.

**Ownership boundary.** This spec owns **intake + automatic matching** only: the document + line
entities, the upload endpoint, the OCR service, the pure match engine, and the **read-only** review.
It does NOT own the reconciled purchases list, the manual re-link workflow, or the unplanned-expense
classification (those are FOR-06-04). `Match_Status` here is only `AUTO` or `UNMATCHED`.

**A new backend OCR service.** `OcrService` is a **new Java implementation** reusing the Google
resources (Cloud Vision `documentTextDetection` + the Google LLM) and the Telegram bot's validated
logic/approach (white-background cleanup, PDF/multi-page split, tolerant JSON parse), extended from a
single receipt summary to **one object per purchased line**. It does NOT import or transliterate the
bot's TypeScript. A `DisabledOcrService` fallback lets the app start and run without the provider
configured; credentials/model are read from a `foremen.ocr.*` config block, never hardcoded.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — changesets `167`–`169`, the entities / enums / candidate provider / match engine /
  OCR service / project-scoped service / controller / DTOs / mappers, the ABAC `PURCHASES` seed, the
  backend `error.procurement.*` message bundle, and this spec. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `procurement` feature folder, the `procurement` tab
  `lazy` swap, the API client, and the `pl.json`/`ru.json` keys. Commit from inside
  `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Migrations.** New changesets begin at `167` (current changelog tail is `166`; if a sibling spec
lands further changesets first, use the next free `NNN`). Each follows the `NOT tableExists` /
`SELECT COUNT(*)` / `onFail="MARK_RAN"` idempotency convention and is appended **last** in
`changelog.xml`. Changesets 167 → 168 → 169 share the changelog include chain and the
`procurement_lines.document_id` FK references `procurement_documents`, so they are **serialized**
(not placed in the same wave).

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect
to `/tmp/for-06-03-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks. Backend
property tests use **jqwik**; frontend property tests use **fast-check + Vitest** (minimum 100
iterations each, tagged `Feature: FOR-06-03-procurement-ocr, Property {n}: ...`).

## Parallel execution plan (waves)

- **Wave 0:** 1.1 — the `procurement_documents` table changeset (167), first link in the changelog
  chain.
- **Wave 1:** 1.2 — the `procurement_lines` table changeset (168); its FK references 167, and it is
  the next changelog include, so it follows 1.1.
- **Wave 2 (parallel):** 2.1 (entities + enums), 7.1 (frontend tab `lazy` swap scaffold) —
  independent of each other and of the DTO/engine work; 7.1 lives in the frontend repo.
- **Wave 3 (parallel):** 3.1 (pure `MatchEngine` + normalization + tie-break), 3.2 (OCR pure
  helpers: corner-parse / page-text concat / per-line parse) — both are pure, self-contained units
  in separate files; 2.2 (DTOs/mappers) also runs here (needs only the entities).
- **Wave 4:** 4.1 (candidate provider) + 4.2 (`GoogleOcrService` + `DisabledOcrService` I/O
  orchestrators wrapping the Wave-3 pure helpers) — 4.2 depends on 3.2; 4.1 depends on 2.1 but is
  grouped here as it feeds the service in Wave 5.
- **Wave 5:** 5.1 (`ProcurementDocumentService` — upload / process / reprocess / delete,
  orchestrating OCR + candidate provider + match engine) — needs entities, DTOs, engine, OCR, and
  candidate provider.
- **Wave 6 (parallel):** 6.1 (controller), 6.2 (ABAC `PURCHASES` seed, 169) — the controller needs
  the service; the seed needs the tables (167/168); independent of each other.
- **Wave 7:** 8.1 (frontend procurement panel + upload + line table + tier badge + image viewer +
  api client) — needs the controller + seed live and the tab scaffold.
- **Wave 8 (parallel):** 9.1 (i18n PL+RU + backend messages), 9.2 (backend tests), 9.3 (frontend
  tests) — independent.
- **Wave 9:** 10 (Author test-cases.md) — needs the full slice.
- **Wave 10:** 11 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: table changesets
  - [x] 1.1 Create the `procurement_documents` table changeset
    - Add `167-create-procurement-documents.xml` (id BIGSERIAL PK; `project_id`, `uploaded_by_id`
      BIGINT NOT NULL FKs; `raw_object_key` VARCHAR(512) NOT NULL; `processed_object_key`
      VARCHAR(512) NULL; `source_mime_type` VARCHAR(100) NOT NULL; `supplier_name` TEXT NULL;
      `document_total` NUMERIC(19,2) NULL CHECK ≥ 0; `status` VARCHAR(16) NOT NULL; `uploaded_at`
      TIMESTAMPTZ NOT NULL; `parsed_at` TIMESTAMPTZ NULL; `failure_reason` TEXT NULL; audit cols).
      Guard `NOT tableExists` + `MARK_RAN`; register last in `changelog.xml`.
    - _Requirements: 1.1, 1.3_
    - _Depends on: none_
  - [x] 1.2 Create the `procurement_lines` table changeset
    - Add `168-create-procurement-lines.xml` (id PK; `document_id` BIGINT NOT NULL FK `ON DELETE
      CASCADE`; `line_no` INT NOT NULL; `raw_name` TEXT NOT NULL; `quantity` NUMERIC(19,3) NULL;
      `unit_hint` TEXT NULL; `unit_price` NUMERIC(19,2) NULL; `line_total` NUMERIC(19,2) NULL;
      `manufacturer_hint` TEXT NULL; `matched_material_id` BIGINT NULL; `match_tier` VARCHAR(32) NOT
      NULL; `match_status` VARCHAR(16) NOT NULL; UNIQUE(`document_id`, `line_no`); audit cols). Guard
      `NOT tableExists` + `MARK_RAN`; register last in `changelog.xml`, after 167.
    - _Requirements: 1.2, 1.3, 1.4_
    - _Depends on: 1.1_

- [x] 2. Backend: entities + DTOs
  - [x] 2.1 Add the JPA entities + enums
    - `ProcurementDocumentEntity extends BaseEntity` and `ProcurementLineEntity extends BaseEntity`
      per design §4.1/§4.2 (document `@OneToMany` lines with `cascade = ALL` + `orphanRemoval` +
      `@OrderBy("lineNo ASC")`; line unique `(document_id, line_no)`; `matchedMaterialId` as a soft
      FK by id, not a JPA `@ManyToOne`). Enums `ProcessingStatus`
      (`UPLOADED`/`PROCESSING`/`PARSED`/`FAILED`), `MatchTier`
      (`EXACT`/`MANUFACTURER_CATEGORY`/`CATEGORY`/`NONE`), `MatchStatus` (`AUTO`/`UNMATCHED`).
    - _Requirements: 1.1, 1.2, 4.3_
    - _Depends on: 1.1, 1.2_
  - [x] 2.2 Add the DTO + mapper classes
    - `ProcurementDocument{ServiceModel, ServiceExtendedModel}`, the controller DTOs
      (`Dto, DtoExtended, UploadResponse, ReprocessResponse`), the `ProcurementLine{Model}` row
      sub-DTO, and the service + controller mappers (design §5.6). The read DTO carries the uploader
      localized name, `uploadedAt`, `supplierName`, `documentTotal`, `status`, `failureReason`, the
      resolved raw + processed image URLs, and the ordered lines. Each `ProcurementLineModel` carries
      `lineNo`, `rawName`, `quantity`, `unitHint`, `unitPrice`, `lineTotal`, `manufacturerHint`, the
      proposed matched-material **localized name** (`ru → pl → code`), `matchTier`, the **derived
      tier percent** (100/80/50/0), and `matchStatus`. No `nameRU`/`namePL` columns on the entities.
    - _Requirements: 5.8, 10.1_
    - _Depends on: 2.1_

- [x] 3. Backend: pure match engine + OCR pure helpers
  - [x] 3.1 Implement the pure `MatchEngine` + normalization + tie-break
    - Add `MatchEngine` (pure, deterministic, total `@Component`, no per-element I/O) with
      `match(ExtractedLine, List<PlannedMaterial>) → MatchResult` and the records `PlannedMaterial`,
      `ExtractedLine`, `MatchResult` (design §5.3). Implement `normalize(String)` (NFD decomposition,
      strip combining marks, explicit `ł→l`/`Ł→L`, lower-case, collapse whitespace, trim — R5.7);
      highest-reachable tier evaluation (EXACT = manufacturer + category + name; MANUFACTURER_CATEGORY
      = manufacturer + category; CATEGORY = category containment; else NONE); the documented tie-break
      (highest normalized-name token Jaccard overlap, then lowest `materialId`); and the R1.5
      invariant helper (`matchedMaterialId == null ⟺ tier == NONE`).
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 1.5, 4.1, 4.2_
    - _Depends on: none_
  - [x] 3.1a Write property tests for the match engine (jqwik)
    - **Property 1: Match soundness** (matched id null ⟺ tier NONE, otherwise on-estimate
      candidate) — **Validates: Requirements 1.5, 4.1, 5.5**.
    - **Property 2: Highest-reachable tier assignment** (incl. empty candidate set → NONE) —
      **Validates: Requirements 4.2, 5.2, 5.3, 5.4, 5.5**.
    - **Property 3: Deterministic, order-independent winner + tie-break** — **Validates:
      Requirements 5.1, 5.6**.
    - **Property 4: Normalization invariance** (case/whitespace/diacritics) — **Validates:
      Requirements 5.7**.
    - Generate `ExtractedLine` + `List<PlannedMaterial>` varying case / whitespace / PL diacritics /
      Cyrillic, manufacturer/category/name overlaps, duplicates, and the empty set.
    - _Depends on: 3.1_
  - [x] 3.2 Implement the OCR pure helpers
    - Add the pure, I/O-free helpers the OCR orchestrator wraps (design §5.2):
      `parseCornerResponse(text) → NormalizedQuad?` (strip markdown fences / prose, validate four
      points, clamp to `[0,1]`), `normalizedToPixelQuad` + `quadAreaFraction` (reject degenerate or
      `< 0.15` area → full-page fallback), `concatenatePageTexts(List<String>)` (join with
      `--- Page n ---`, empty iff all blank), and `parsePerLineResponse(text) → OcrParse?` (tolerant
      array parse, drop lines with no `rawName`, `abs()`-normalize negative amounts, capture
      supplier/total, `null` on unparseable). Define the records `OcrResult`, `ProcessedPage`,
      `ParsedLine` and the `CORNER_DETECT_PROMPT` / `PER_LINE_PARSE_PROMPT` constants.
    - _Requirements: 3.0, 3.1, 3.2_
    - _Depends on: none_
  - [x] 3.2a Write property tests for the OCR pure helpers (jqwik)
    - **Property 5: Corner-response parse is tolerant and range-clamped** — **Validates:
      Requirements 3.0**.
    - **Property 6: Page-text concatenation preserves order and detects emptiness** — **Validates:
      Requirements 3.1**.
    - **Property 7: Per-line parse tolerance and amount normalization** — **Validates: Requirements
      3.2**.
    - Generate model-response strings (bare / fenced / prose-wrapped JSON, out-of-range points,
      negative amounts, missing `rawName`, empty arrays).
    - _Depends on: 3.2_

- [x] 4. Backend: candidate provider + OCR service wirings
  - [x] 4.1 Implement `PlannedMaterialCandidateProvider`
    - A thin `@Component` reading the project's `Planned_Material` set from the existing FOR-05-05b
      concrete-material projection (`MaterialsListService` / the materials read model) and mapping
      each distinct chosen `Concrete_Material` to `PlannedMaterial(materialId, manufacturer=producer,
      category=type, [nameRU, namePL])` with the `ru → pl → code` fallback (design §5.4). No second
      material list; empty set when the project has no chosen concrete materials.
    - _Requirements: 4.1, 4.3, 4.4_
    - _Depends on: 2.1_
  - [x] 4.2 Implement the `OcrService` interface + `GoogleOcrService` + `DisabledOcrService`
    - Define the `OcrService` interface (`isEnabled()`, `process(rawBytes, mimeType, keyNamespace) →
      OcrResult`). `GoogleOcrService` (`@ConditionalOnProperty foremen.ocr.enabled=true`): the thin
      I/O orchestrator wrapping the Wave-3 pure helpers — PDF rasterization (PDFBox `PDFRenderer`),
      per-page EXIF orient → Google vision corner detect → white-out outside the quad at original
      dimensions → lossless PNG (full-page fallback on failure), Google Cloud Vision
      `documentTextDetection` per page, and the LLM per-line parse. `DisabledOcrService` (default /
      `foremen.ocr.enabled=false`): `isEnabled()` false, `process` throws `OcrUnavailableException`.
      Add the `foremen.ocr.*` config block (`enabled`, `vision.credentials.*`, `llm.model`,
      `llm.credentials.*`) mirroring `foremen.image-storage.*`; nothing hardcoded. Define
      `OcrUnavailableException` / `OcrFailedException`.
    - _Requirements: 3.0, 3.1, 3.2, 3.5, 3.6_
    - _Depends on: 3.2_

- [x] 5. Backend: project-scoped service
  - [x] 5.1 Add `ProcurementDocumentService`
    - `implements ProjectScopedService<...>` (design §5.1); `getProjectIdPath()` → `"project.id"`;
      `allowedProjectIds` → `ProjectAccessCache`; standard CRUD plumbing.
      **`upload(projectId, MultipartFile)`**: reject when `ImageStorage.isConfigured()` false
      (`error.procurement.upload.storageUnavailable`, store nothing); validate content type ∈
      {png, jpeg, webp, pdf} (`error.procurement.upload.unsupportedType`); size guard reused from
      `ImageStorage` (`error.procurement.upload.tooLarge`); `storeObject(.., "procurement/{projectId}/")`
      → `rawObjectKey`; persist `UPLOADED` doc (`uploadedAt`, `uploadedBy`, `sourceMimeType`); audit
      row; transition `PROCESSING` + invoke `process`. **`process(document)`**: run `OcrService`,
      store the processed PNG rendition(s) via `storeObject(.., "procurement/{projectId}/processed/")`,
      set `processedObjectKey`, match each line via `MatchEngine` over the candidate provider set,
      replace lines atomically (sequential `lineNo` from 1), set `supplierName`/`documentTotal`/
      `parsedAt`, transition `PARSED`; on any failure transition `FAILED` with a stable
      `failureReason`, retaining the raw object + document. **`reprocess(id)`** (FAILED/PARSED only):
      re-run `process` on the already-stored raw bytes, regenerate + replace `processedObjectKey`
      (superseded object cleaned via `deleteIfOrphan`), replace all lines atomically, reject on a
      non-`ACTIVE` project. **`delete(id)`**: cascade to lines, `deleteIfOrphan` on both keys, audit.
      **Stage gate**: `upload`/`reprocess` refused unless project `ACTIVE`
      (`error.procurement.stage.notActive`); reads allowed on `ACTIVE` + `COMPLETED`. Enforce the
      R1.5 invariant before persisting each line. Emit **no** notifications (R9.1).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.3, 3.4, 3.5, 7.1, 7.2, 8.1, 8.2, 8.3, 8.4, 9.1, 11.1, 1.5_
    - _Depends on: 2.2, 3.1, 4.1, 4.2_
  - [x] 5.1a Write property/unit tests for the service (jqwik + stubbed seams)
    - **Property 8: Unsupported content type is rejected and stores nothing** — **Validates:
      Requirements 2.2**.
    - **Property 9: A processing failure retains the raw upload and the document** — **Validates:
      Requirements 3.4, 8.3**.
    - **Property 10: Re-processing replaces the line set atomically** — **Validates: Requirements
      8.2**.
    - Drive the service with a stubbed `OcrService` (pass/fail modes) and a stub/in-memory
      `ImageStorage` so no external calls run. Add example unit tests for upload happy paths per
      accepted type, the storage-unavailable path, `uploadedAt` + `PROCESSING` trigger, sequential
      `lineNo` + supplier/total/`parsedAt` on `PARSED`, cascade delete, `deleteIfOrphan` on
      re-process, the no-notification assertion, and audit rows on each lifecycle op.
    - _Depends on: 5.1_

- [x] 6. Backend: controller + ABAC seed
  - [x] 6.1 Add `ProcurementDocumentController`
    - `@RestController @RequestMapping("/api/procurement-documents") @PermissionResource("PURCHASES")`
      (design §5.5): `POST /` (multipart `projectId`,`file`) → CREATE; `GET /?projectId=` → READ
      (project-scoped list); `GET /{id}` → READ (document + lines + matches); `POST /{id}/reprocess`
      → UPDATE; `DELETE /{id}` → DELETE; `GET /{id}/images` → READ (resolve raw + processed URLs).
      Each in-scope handler carries a matching `@PermissionOperation`/`@RequiresPermission`. Verify
      the app starts (no `PermissionAnnotationValidator` failure).
    - _Requirements: 6.1, 7.3, 8.1_
    - _Depends on: 5.1_
  - [x] 6.2 Seed the `PURCHASES` resource + grants
    - Add `169-seed-purchases-resource.xml` (design §4.5/§4.6, per entity-creation-rules §1–§2):
      ChangeSet A inserts the `PURCHASES` resource row (+ PL/RU name/description, guarded
      `COUNT(*)=0`); ADMIN CRUD; FINANCIER CRUD; MANAGER CRUD; FOREMAN CRUD; other admin-staff
      (ESTIMATOR) READ only; WORKER + CLIENT omitted with an explanatory XML comment (deny-by-default).
      Each grant idempotent via the `role_resources` precondition; register last in `changelog.xml`,
      after 168.
    - _Requirements: 7.2, 7.4, 7.5_
    - _Depends on: 1.1, 1.2_
  - [x] 6.2a Write the ABAC matrix + seed-idempotency tests (jqwik + Testcontainers)
    - **Property 11: PURCHASES ABAC matrix** (ADMIN/FINANCIER/MANAGER/FOREMAN → full CRUD; ESTIMATOR
      → READ; WORKER/CLIENT → {}) — **Validates: Requirements 7.4, 7.5**.
    - Add the seed-idempotency check (changesets 167–169 insert no duplicate table / resource / grant
      on re-run) and a startup smoke asserting `PermissionAnnotationValidator` classifies
      `ProcurementDocumentController` COMPLETE, `foremen.ocr.*` binds with no hardcoded key, and the
      app starts with the OCR provider **disabled** (R3.5).
    - _Depends on: 6.1, 6.2_

- [x] 7. Frontend: tab wiring
  - [x] 7.1 Swap the `procurement` tab panel
    - In the workspace tab registry, change the `procurement` entry's `lazy` from `PlaceholderTab`
      to the new `ProcurementPanel`. Do not touch the `WORKSPACE_TABS` contract shape,
      `resolveWorkspaceTabs`, or `stageOf`. The `workspace.tab.procurement` label is already seeded
      by FOR-06-00 — reuse unchanged.
    - _Requirements: 7.6_
    - _Depends on: none_

- [x] 8. Frontend: procurement panel
  - [x] 8.1 Build the `procurement` feature folder
    - `features/project-execution/procurement/` with `ProcurementPanel.tsx` (document list: uploader,
      upload time, supplier, document total, processing-status badge; the upload control; the
      expandable per-document **read-only** proposed-match review — no confirm/re-link/classify;
      upload / re-process controls hidden for a READ-only caller), `components/DocumentUpload.tsx`
      (image or PDF, progress, status chain `UPLOADED → PROCESSING → PARSED/FAILED`, inline server
      errors — unsupportedType / tooLarge / storageUnavailable / notActive),
      `components/ProcurementLineTable.tsx` (raw name, quantity, unit, unit price, line total,
      proposed matched-material name, `TierBadge`), `components/TierBadge.tsx` (`matchTier` → localized
      colour-coded 100%/80%/50%/no-match badge), `components/DocumentStatusBadge.tsx` (localized
      status badge; `FAILED` offers re-process, hidden for READ-only + on `COMPLETED`),
      `components/DocumentImageViewer.tsx` (raw + processed white-background renditions, per-page for a
      PDF; viewable on `FAILED`), `api/procurement-api.ts` (`list/upload/fetchOne/reprocess/delete/
      images` via the shared `apiRequest` + `buildFetchQuery`), `schemas/`, `types/`, `__tests__/`.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 7.5, 8.1_
    - _Depends on: 6.1, 6.2, 7.1_

- [x] 9. i18n + tests
  - [x] 9.1 Add i18n keys (PL + RU, mirrored) + backend messages
    - Add the `procurement.*` feature block (design §6.1): page/list title + columns, status badges
      (`uploaded|processing|parsed|failed`), upload labels, line-table columns, tier badges
      (`exact`/`manufacturerCategory`/`category`/`none`), image-viewer labels, actions
      (`reprocess`/`viewImages`), toasts, and the inline upload-error codes
      (`unsupportedType`/`tooLarge`/`storageUnavailable`/`notActive`) to **both** `pl.json` and
      `ru.json` with real PL + RU values (no English fallback). Add every `error.procurement.*` code
      (R2 upload rules, R3 OCR failures, the stage gate) to the backend PL + RU message bundle. Run
      the locale-parity test.
    - _Requirements: 10.2, 10.3, 10.4_
    - _Depends on: 5.1, 6.1, 8.1_
  - [x] 9.2 Backend example / integration / smoke tests
    - Candidate provider reading the FOR-05-05b projection with `ru → pl → code` resolution (R4.3,
      R4.4); the migration run (changesets 167–169) via Testcontainers; the Vision/LLM seams
      mocked (their pure helpers already carry property coverage in 3.2a). Run only the affected
      classes with `--tests` → `/tmp/for-06-03-test.log`; verify via JUnit XML. (Match-engine,
      OCR-helper, service, and ABAC property tests live in 3.1a / 3.2a / 5.1a / 6.2a.)
    - _Requirements: 4.3, 4.4, 11.3_
    - _Depends on: 4.1, 5.1, 6.1, 6.2_
  - [x] 9.3 Frontend unit tests (Vitest + fast-check)
    - `ProcurementPanel` / `DocumentUpload` render + upload status chain + inline server-error
      mapping (R6.1, R6.2, R6.6); `ProcurementLineTable` + `TierBadge` over the four tiers (R6.3);
      the read-only assertion (no confirm/re-link/classify controls, R6.5); `DocumentImageViewer`
      showing raw + processed, incl. on `FAILED` (R6.4); re-process control hidden for a READ-only
      caller and on a `COMPLETED` project. **Property 12: Stage gating** — resolver fixtures
      (`procurement` present on `ACTIVE` with `PURCHASES`, read-only on `COMPLETED`, absent on
      `DRAFT`) — **Validates: Requirements 7.1**. **Property 13: Locale parity** over `pl.json` /
      `ru.json` — **Validates: Requirements 10.2**; plus a no-raw-key render assertion (R10.4).
    - _Requirements: 6.1, 6.3, 6.5, 7.1, 7.6, 10.2, 10.4_
    - _Depends on: 8.1, 9.1_

- [x] 10. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown, a
    regression group, the MD report template). This is an **API-only + UI** spec: the backend intake
    + OCR + match + ABAC + stage gate are **API tests** against the Dockerized app
    (`http://localhost:8080`, auth under `/api/auth`) — upload type/size/disabled-storage rules, the
    `UPLOADED → PROCESSING → PARSED/FAILED` lifecycle, re-processing, the proposed-match tiers over a
    seeded planned-material set, the dual-image renditions, the `PURCHASES` matrix per role, and the
    stage gate; the Procurement panel (upload → status chain → read-only review → tier badges → image
    viewer → re-process) is a **browser-engine** scenario on an `ACTIVE` project. Where the OCR
    provider is not configured in the Docker stack, the OCR-dependent scenarios assert the
    disabled-fallback behavior (`UPLOADED`/`FAILED`, no lines) and the deterministic match-engine
    scenarios are driven through seeded lines. Written in Russian. Result artifacts are MD reports
    with tables.
  - _Requirements: 2.2, 2.3, 2.4, 3.3, 3.4, 5.2, 5.3, 5.4, 5.5, 6.1, 7.1, 7.4, 7.5, 8.1, 11.3_
  - _Depends on: 6.1, 6.2, 8.1, 9.1, 9.2, 9.3_

- [x] 11. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes with `--tests` → `/tmp/for-06-03-test.log` (verify via JUnit XML); type-check +
    unit-test the frontend; confirm the locale-parity test passes and the app starts (no
    `PermissionAnnotationValidator` failure, `foremen.ocr.*` binds, OCR provider disabled). Confirm
    the `procurement` tab appears for a permitted caller on an `ACTIVE` project and the upload →
    process → read-only review (with seeded lines or the disabled-OCR fallback) + re-process flow
    works end-to-end. Ensure all tests pass; ask the user if questions arise. Then stage and commit
    per repo (two commits — root for backend/migrations/spec, `foremen-frontend/` for the panel) —
    only if the user asks.
  - _Requirements: all_
  - _Depends on: 1.1, 1.2, 2.1, 2.2, 3.1, 3.2, 4.1, 4.2, 5.1, 6.1, 6.2, 7.1, 8.1, 9.1, 9.2, 9.3, 10_

## Notes

- Tasks marked with `*` are optional (test sub-tasks) and can be skipped for a faster MVP; core
  implementation tasks are never optional.
- Each task references specific requirement sub-clauses for traceability.
- Backend property tests use **jqwik**; frontend property tests use **fast-check + Vitest** (minimum
  100 iterations, tagged with the design property number).
- Property tests are placed next to the implementation they validate (match engine, OCR helpers,
  service, ABAC) to catch errors early.
- Checkpoints (task 11) ensure incremental validation; run only affected test classes per
  `.kiro/steering/test-execution-rules.md`.
- The feature spans two git repos — backend/migrations/spec in the root repo, the Procurement panel
  in `foremen-frontend/` — so a full commit is two commits, one per repo.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["2.1", "7.1"] },
    { "id": 3, "tasks": ["2.2", "3.1", "3.1a", "3.2", "3.2a"] },
    { "id": 4, "tasks": ["4.1", "4.2"] },
    { "id": 5, "tasks": ["5.1", "5.1a"] },
    { "id": 6, "tasks": ["6.1", "6.2", "6.2a"] },
    { "id": 7, "tasks": ["8.1"] },
    { "id": 8, "tasks": ["9.1", "9.2", "9.3"] },
    { "id": 9, "tasks": ["10"] },
    { "id": 10, "tasks": ["11"] }
  ]
}
```

---

## Amendment A1 — Asynchronous OCR tasks

Implements Requirements 14–19 (design Amendment A1). Supersedes the synchronous `upload → process`
path. **Spans two repos** (per `.kiro/steering/git-repo-structure.md`): backend/spec in the root
repo, the Procurement panel + locale files in `foremen-frontend/`.

### Parallel execution plan (waves) — Amendment A1

- **Wave A1-0:** A1.1 — extend `ProcessingStatus` enum + the stage-documenting changeset.
- **Wave A1-1 (parallel):** A1.2 (OCR executor bean + `OcrStageListener` seam), A1.3 (status DTO +
  `GET /{id}/status` endpoint) — independent; A1.2 touches OCR config, A1.3 touches controller/DTO.
- **Wave A1-2:** A1.4 (`ProcurementDocumentService` async: upload/reprocess publish event, worker
  `ProcurementOcrWorker` with per-stage commits + failure mapping) — needs the enum (A1.1), the
  executor (A1.2), and consumes the stage listener.
- **Wave A1-3 (parallel):** A1.5 (terminal-state `ProcurementOcrNotificationEmitter`), A1.6
  (crash-recovery sweep) — both depend on the worker/service (A1.2/A1.4) but are independent of each
  other.
- **Wave A1-4:** A1.7 (frontend: status polling client/hook + upload spinner + per-row spinner + retry
  + stage/failure rendering) — needs the backend status endpoint (A1.3) and async upload (A1.4) live.
- **Wave A1-5 (parallel):** A1.8 (i18n PL+RU: status/stage enum labels, failure messages, processing
  UX, notification type keys + backend message codes), A1.9 (backend tests), A1.10 (frontend tests) —
  independent.
- **Wave A1-6:** A1.11 (update `test-cases.md` for async: poll-for-terminal instead of asserting on
  upload) — needs the slice.
- **Wave A1-7:** A1.12 (final verification + deploy to dev + checkpoint) — depends on everything.

### Tasks — Amendment A1

- [x] A1.1 Extend the status model + stage changeset
  - Extend `ProcessingStatus` to `QUEUED, RASTERIZING, CLEANING, OCR_TEXT, PARSING, MATCHING, PARSED,
    FAILED, UPLOADED` (design A1.1). Confirm `VARCHAR(16)` still fits (`RASTERIZING` = 11). Add a
    comment-only / no-op changeset `NNN-procurement-status-stages.xml` documenting the extended
    lifecycle (guarded + `MARK_RAN`, registered last in `changelog.xml`); no column widening.
  - _Requirements: 15.1, 15.3_
  - _Depends on: none_
- [x] A1.2 OCR executor + stage-listener seam
  - Add `@Bean("ocrTaskExecutor") ThreadPoolTaskExecutor` (corePoolSize 1–2, bounded queue,
    `CallerRunsPolicy`, prefix `ocr-`) in the OCR config (mirrors `MailConfig`). Define the
    `OcrStageListener` callback interface and thread it through `OcrService.process(..., listener)`;
    `GoogleOcrService` invokes it as it enters CLEANING / OCR_TEXT / PARSING; `DisabledOcrService`
    ignores it. Keep the no-listener overload for existing callers/tests.
  - _Requirements: 14.3, 15.1, 15.2_
  - _Depends on: none_
- [x] A1.3 Status endpoint + DTO
  - Add `ProcurementDocumentStatusResponse { id, status, stage, failureReason }` and
    `GET /api/procurement-documents/{id}/status` (`@RequiresPermission(PURCHASES, READ)`, same
    project-scope guard as `findById`). Add the derived `stage` field to the list + extended DTOs and
    their mappers (stage == status while non-terminal, else null).
  - _Requirements: 16.1, 16.4_
  - _Depends on: none_
- [x] A1.4 Make upload/reprocess async + the worker
  - Rework `ProcurementDocumentService.upload` to the fast transactional part only (validate → store
    raw → persist `QUEUED` → audit → commit → return), publishing `ProcurementOcrRequested(id)`.
    Rework `reprocess` to guard → `QUEUED` → commit → publish the same event (no inline OCR). Add
    `ProcurementOcrWorker` (`@Async("ocrTaskExecutor") @TransactionalEventListener(AFTER_COMMIT)`)
    that re-loads by id and drives the six stages with per-stage commits, maps failures to the
    stage-specific `failureReason` codes (design A1.3/R18.1), retains the raw on failure, and sets the
    terminal state. Remove the old inline `process` call from the request path.
  - _Requirements: 14.1, 14.2, 14.3, 14.4, 15.1, 15.2, 18.1, 18.3_
  - _Depends on: A1.1, A1.2_
- [x] A1.5 Terminal-state notifications
  - Add `ProcurementOcrNotificationEmitter` invoked by the worker after the terminal commit: on
    `PARSED` → `notification.procurement.ocr.parsed`, on `FAILED` →
    `notification.procurement.ocr.failed`, recipient `document.uploadedBy`, deep-link
    `/projects/{projectId}/procurement?doc={id}`, via `NotificationService.create(...)` in try/catch
    (log-and-swallow). No notification for the OCR-disabled resting case.
  - _Requirements: 17.1, 17.2, 17.3, 17.4_
  - _Depends on: A1.4_
- [x] A1.6 Crash-recovery sweep
  - Add a `@Scheduled` + `@SchedulerLock` sweep (mirror `OverdueSweepService`; cron default `-`,
    enabled per profile) + a startup pass that re-enqueues (or fails as retryable) every document left
    in a non-terminal stage beyond a staleness window, with a small attempt budget. Idempotent,
    single-flighted.
  - _Requirements: 18.4_
  - _Depends on: A1.4_
- [x] A1.7 Frontend async UX
  - `procurement-api.ts`: add `fetchProcurementDocumentStatus(id)`. Add
    `useProcurementDocumentStatus` (React Query `refetchInterval` active only while non-terminal,
    capped backoff, stop on terminal/unmount; invalidate the full-document query on terminal).
    `DocumentUpload`: spinner + localized processing text + stage label from the polled status.
    `ProcurementPanel` / `DocumentStatusBadge`: per-row spinner + stage label for non-terminal rows,
    poll only those; `FAILED` rows show the localized failure reason + a Retry (reprocess) button,
    hidden for READ-only + non-ACTIVE.
  - _Requirements: 16.2, 16.3, 19.1, 19.2, 19.3, 19.4_
  - _Depends on: A1.3, A1.4_
- [x] A1.8 i18n (PL + RU) incl. enum keys + notification keys
  - Add to both `pl.json` and `ru.json` (real PL + RU, no English fallback): `procurement.status.*`
    (queued/rasterizing/cleaning/ocrText/parsing/matching/parsed/failed/uploaded),
    `procurement.failure.*` (rasterize/vision/noText/parse/noLines/storage/generic/disabled),
    `procurement.processing.*` (inProgress/retry/retrying/toasts), and the notification type keys
    `notification.procurement.ocr.parsed` / `.failed`. Add any new `error.procurement.*` upload codes
    to the backend PL + RU bundles. Run the locale-parity test; no raw key may render (R10.4/R19.5).
  - _Requirements: 15.4, 17.5, 18.2, 19.5_
  - _Depends on: A1.4, A1.5, A1.7_
- [x] A1.9 Backend tests
  - Unit/integration for: upload returns `QUEUED` and runs no inline OCR (event published); worker
    drives QUEUED→…→PARSED with per-stage commits (observable via repeated reads) using a stubbed
    `OcrService` with a stage listener; failure mapping per stage sets the right `failureReason` and
    retains the raw; reprocess re-enqueues; the terminal notification is emitted to the uploader with
    the deep-link (stubbed `NotificationService`, assert type + recipient); the sweep re-enqueues a
    stale non-terminal doc. Run only affected classes with `--tests` → `/tmp/for-06-03-async-test.log`;
    verify via JUnit XML.
  - _Requirements: 14.1, 14.3, 15.2, 17.1, 17.2, 18.1, 18.4_
  - _Depends on: A1.4, A1.5, A1.6_
- [x] A1.10 Frontend tests (Vitest + fast-check)
  - Upload shows spinner + stage label and polls until terminal then fetches the full doc; list renders
    per-row spinners only for non-terminal rows and polls only those; `FAILED` row shows the localized
    reason + Retry (hidden for READ-only / non-ACTIVE); polling stops on terminal/unmount and backs
    off. Locale-parity over the new keys.
  - _Requirements: 16.2, 16.3, 19.1, 19.2, 19.3, 19.4, 19.5_
  - _Depends on: A1.7, A1.8_
- [x] A1.11 Update test-cases.md for async
  - Update `test-cases.md`: the upload API scenario asserts `status = QUEUED` on the upload response,
    then **polls** `GET /{id}/status` until a terminal state (PARSED/FAILED) rather than asserting the
    terminal state on upload; add the retry (reprocess → QUEUED → poll) scenario, the terminal-
    notification scenario (uploader receives the bell notification), and the per-row-spinner UI
    scenario. Keep the repeatability (run-id / teardown) and MD-report template. Russian.
  - _Requirements: 14.1, 16.2, 17.1, 18.3, 19.2_
  - _Depends on: A1.4, A1.5, A1.7, A1.8, A1.9, A1.10_
- [ ] A1.12 Final verification + deploy + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava`), run only affected classes with
    `--tests`; type-check + unit-test the frontend; locale-parity passes. Deploy to dev (both repos
    push → Cloud Build), confirm an upload returns `QUEUED` immediately (no 504), the stage chain is
    observable via `GET /{id}/status`, a real receipt reaches `PARSED` with a bell notification to the
    uploader, and a forced failure shows a descriptive reason + working Retry. Clean up test docs.
    Commit per repo only if the user asks.
  - _Requirements: all A1_
  - _Depends on: A1.1, A1.2, A1.3, A1.4, A1.5, A1.6, A1.7, A1.8, A1.9, A1.10, A1.11_

### Task Dependency Graph — Amendment A1

```json
{
  "waves": [
    { "id": "A1-0", "tasks": ["A1.1"] },
    { "id": "A1-1", "tasks": ["A1.2", "A1.3"] },
    { "id": "A1-2", "tasks": ["A1.4"] },
    { "id": "A1-3", "tasks": ["A1.5", "A1.6"] },
    { "id": "A1-4", "tasks": ["A1.7"] },
    { "id": "A1-5", "tasks": ["A1.8", "A1.9", "A1.10"] },
    { "id": "A1-6", "tasks": ["A1.11"] },
    { "id": "A1-7", "tasks": ["A1.12"] }
  ]
}
```
