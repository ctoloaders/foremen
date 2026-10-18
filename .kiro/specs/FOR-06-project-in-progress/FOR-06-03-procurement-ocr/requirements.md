# Requirements Document

## Introduction

This spec (**FOR-06-03-procurement-ocr**) is the procurement-intake child of the FOR-06 "project in
progress" (execution stage) parent. It delivers the **first half of the procurement domain**: on an
`ACTIVE` project, an authorized user (FINANCIER / MANAGER / FOREMAN) **uploads a primary procurement
document** (a receipt or invoice, as an image or a PDF), the system **OCR-extracts its line items**,
and then **matches each extracted line to a planned project material** by a deterministic tier rule
(producer + category + name = 100%, producer + category = 80%, category = 50%, otherwise no match).
The result is a persisted procurement document with a set of extracted lines, each carrying its
proposed match and tier, ready for the reconciled purchases list, manual re-linking, and
unplanned-expense classification delivered by the sibling **FOR-06-04-purchases-list**.

This spec owns the **intake + automatic matching**; it does not own the reconciled purchases list
UI, the manual re-link workflow, or the unplanned-expense classification (all FOR-06-04). It shares
and **seeds** the `PURCHASES` ABAC resource with FOR-06-04 (per FOR-06-00 §3: each execution resource
is seeded by the child that first needs it — this child seeds `PURCHASES`; FOR-06-04 reuses it).

### A new backend OCR service (reusing Google resources + the bot's logic)

This spec delivers a **new backend (Java) OCR service** — it does **not** reuse, import, or depend on
the Telegram bot's TypeScript code. What it reuses is the **Google resources** (Google Cloud Vision
and the Google LLM) and the **proven logic/approach** the `телеграм-бот/telegram-bot` receipt bot has
validated in production:

- **Image pre-processing — white-background cleanup** (approach from the bot's `services/reprocess.ts`
  + `services/document-detect.ts`): auto-orient the page by EXIF, ask the Google vision model for the
  document's four corners, and paint everything **outside** the detected document quadrilateral white,
  keeping the original dimensions (no crop, no perspective warp, no scaling). On any detection failure
  the page falls back to its oriented original. OCR then runs on this cleaned image.
- **Raw text extraction** via Google Cloud Vision `documentTextDetection` (approach from
  `services/ocr.ts`), including multi-page concatenation for PDFs.
- **Structured per-line parse** of the OCR text via a Google LLM prompt (approach from
  `services/gemini.ts`) returning JSON, with a tolerant parser that strips markdown fences and
  validates fields — **extended from the bot's single receipt summary to one object per purchased
  line** (raw name, quantity, unit, unit price, line total, and a manufacturer/brand hint when
  present), plus the document-level supplier name and total.
- **PDF / multi-page handling** (approach from `services/pdf.ts`): a PDF is split to per-page images,
  each pre-processed and OCR'd, and the page texts concatenated before the parse.

The service is implemented with the Google Java client libraries; the Vision + LLM providers,
credentials, and model are read from configuration (reusing the existing `foremen.*` GCS credential
conventions), never hardcoded.

### Storing both the raw and the white-background image

For each uploaded document the system retains **two renditions** in the object store: the **raw
upload** exactly as received, and the **processed white-background image** produced by the cleanup
step above (the rendition OCR is run on). Both are referenced by object key on the document (for a
multi-page PDF the processed rendition is the per-page set). Where the cleanup falls back for a page,
its processed rendition equals its oriented original.

### Reuse: the existing object store

The uploaded document is persisted through the **existing** backend object-store facility
(`com.foremen.service.image.ImageStorage` / `GcsImageStorage`, FOR-04-17), whose allowed content
types already include `application/pdf` alongside `image/png`, `image/jpeg`, and `image/webp`. This
spec stores the raw upload via that facility (namespaced per project) and references it by object
key; it does **not** introduce a second object store. Where the deployment runs with the
`DisabledImageStorage` fallback (object store not configured), upload is rejected gracefully exactly
as for material images.

### Scope boundaries

- **In scope:**
  - A project-scoped `ProcurementDocument` entity (uploader, project, **raw** object-store key,
    **processed white-background** object-store key, source MIME, supplier/store name, document total,
    OCR/parse status, uploaded-at) and its child `ProcurementLine` entity (one row per extracted
    purchased item: raw name, quantity, unit hint, unit price, line total, manufacturer hint, proposed
    matched material, match tier, match status).
  - The upload endpoint: accept an image or PDF, store the **raw** upload via `ImageStorage`, create
    the `ProcurementDocument`, and kick off cleanup + OCR + parse + per-line matching.
  - A **new backend (Java) `OcrService`** reusing the Google resources (Cloud Vision + LLM) and the
    bot's logic: white-background cleanup, Vision text extraction, multi-page/PDF split, and the LLM
    per-line parse — with a disabled/stub fallback so the app runs without the OCR provider configured.
  - Storing **both** image renditions per document: the raw upload and the processed white-background
    image (the latter is what OCR runs on), both via `ImageStorage`.
  - The **per-line match engine**: a pure, deterministic function mapping each extracted line to a
    planned project material at one of the tiers 100 % / 80 % / 50 % / no-match (Requirement 5).
  - A read model exposing a document with its lines, each line's proposed match + tier + status.
  - The new project-scoped ABAC resource `PURCHASES` with the full entity-creation checklist, and the
    swap of the FOR-06-00 `procurement` execution tab from `PlaceholderTab` to the real upload panel.
  - PL + RU localization of every user-facing string, plus the spec's own tests + `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **The reconciled purchases list, manual re-linking, and unplanned-expense classification**
    (FOR-06-04). This spec produces the documents + lines + proposed matches that FOR-06-04 reconciles;
    it renders the intake/upload surface and a read-only review of proposed matches only.
  - **The materials matrix** (FOR-06-05) — plan-vs-fact aggregation over purchases.
  - **The object store itself** (FOR-04-17 `ImageStorage`) — reused, not re-implemented.
  - **Reusing the Telegram bot's code** — this spec writes a new Java service; it reuses only the
    Google resources (Vision + LLM) and the bot's validated logic/approach, not its TypeScript.
  - **Choosing/operating an OCR vendor account** — provider credentials/config are deployment concerns.
  - **Notifications** — none are emitted (see Requirement 9); a FINANCIER or MANAGER sees new
    documents in the Procurement tab.
  - **The unplanned-expense mini-dictionary** (`UnplannedExpenseCategory`) — owned/seeded by FOR-06-00
    and consumed by FOR-06-04, not by this spec.
  - **Changing `stageOf` / `resolveWorkspaceTabs` / the `WORKSPACE_TABS` contract shape** — the
    `procurement` tab slot gated on `PURCHASES`/READ was reserved by FOR-06-00; this spec seeds the
    resource and swaps the tab's panel only.

### Relationship to existing specs (source of truth)

- **FOR-05-05-bill-of-materials** — the `EstimateLine` material lines and their chosen
  `Concrete_Material` (a `ConstructionMaterial` / `FinishingMaterial` with a `producer`, a material
  **type**, and `nameRU`/`namePL`). The planned-material set the match engine targets is read from
  here.
- **FOR-05-05b-list-of-materials** — the project's concrete-material list (`materialId`-keyed) that
  defines the candidate set for matching; this spec reuses that projection and adds no second list.
- **FOR-04-17-construction-materials** — the material model (`producer` = manufacturer, material
  `type` = category, `nameRU`/`namePL`) the three match tiers are defined over, and the
  `ImageStorage` object-store facility (already PDF-capable) reused for the upload.
- **FOR-06-00-execution-foundation** — reserved the `procurement` execution tab on `PURCHASES`/READ
  (pointing at `PlaceholderTab`, hidden until the resource is seeded). This spec seeds `PURCHASES` and
  swaps the panel.
- **`телеграм-бот/telegram-bot` (services/ocr.ts, gemini.ts, pdf.ts, document-detect.ts,
  reprocess.ts)** — the validated **logic/approach** (white-background cleanup, Vision text
  extraction, PDF/multi-page split, LLM parse) that the new Java `OcrService` re-implements and
  extends to per-line extraction. Only the Google resources and the approach are reused, not the code.
- **FOR-05-07-offer-approval Requirement 13** — the generic in-app `Notification_Service`. This spec
  is **not** a consumer (Requirement 9): it emits no notifications.
- **`.kiro/steering/entity-creation-rules.md`** — `PURCHASES` resource seed changeset, ADMIN grant +
  role matrix, class-level `@PermissionResource("PURCHASES")`, `getProjectIdPath()`, startup
  annotation completeness.

## Glossary

- **Procurement_Document** — the entity `ProcurementDocument{ project (FK), uploadedBy (user FK),
  rawObjectKey (object-store key of the raw upload), processedObjectKey (object-store key of the
  white-background rendition OCR runs on, nullable until cleanup runs), sourceMimeType, supplierName
  (text, nullable, from parse), documentTotal (numeric, nullable, from parse), status
  (Processing_Status), uploadedAt, parsedAt (nullable), + audit }`. Project-scoped on `project.id`.
  For a multi-page PDF the processed rendition is a per-page set keyed from `processedObjectKey`.
- **Procurement_Line** — the entity `ProcurementLine{ document (FK, ON DELETE CASCADE), lineNo (int),
  rawName (text), quantity (numeric, nullable), unitHint (text, nullable), unitPrice (numeric,
  nullable), lineTotal (numeric, nullable), manufacturerHint (text, nullable), matchedMaterialId (FK,
  nullable), matchTier (Match_Tier), matchStatus (Match_Status) }`. Project-scoped on
  `document.project.id`.
- **Processing_Status** — the document lifecycle: `UPLOADED` (stored, OCR not yet run), `PROCESSING`
  (OCR + parse + matching in flight), `PARSED` (lines extracted and matched), `FAILED` (OCR or parse
  failed; the raw upload is retained and the document is re-processable).
- **Match_Tier** — the confidence tier of a line's proposed material match: `EXACT` (100 % —
  manufacturer + category + name), `MANUFACTURER_CATEGORY` (80 % — manufacturer + category),
  `CATEGORY` (50 % — category only), or `NONE` (no match). The numeric score is a presentational
  label derived from the tier, not a free continuous value.
- **Match_Status** — `AUTO` (set by the match engine at this tier) or `UNMATCHED` (tier `NONE`). The
  manual-override status lives in FOR-06-04, not here.
- **Planned_Material** — a `Concrete_Material` chosen on the project's estimate (FOR-05-05/05b): the
  candidate set the match engine ranks each extracted line against, carrying `producer` (manufacturer),
  material `type` (category), and `nameRU`/`namePL`.
- **OCR_Service** — the new backend (Java) service that, given the stored document bytes + MIME,
  (a) produces the white-background cleaned rendition, (b) extracts text via Google Cloud Vision, and
  (c) returns the structured per-line parse via the Google LLM — reusing the bot's logic/approach, not
  its code. A disabled fallback leaves a document `UPLOADED`/`FAILED` without extracting lines.
- **Match_Engine** — the pure, deterministic function mapping an extracted `Procurement_Line` against
  the project's `Planned_Material` set to a `(matchedMaterialId, matchTier)` at the highest reachable
  tier (Requirement 5).

## Requirements

### Requirement 1 — ProcurementDocument / ProcurementLine entity and persistence

**User Story:** As the system, I want uploaded procurement documents and their extracted lines
persisted per project, so each purchase can later be reconciled against the plan.

#### Acceptance Criteria

1. THE SYSTEM SHALL define a project-scoped entity `ProcurementDocument` extending `BaseEntity` with
   fields `project` (FK, not null), `uploadedBy` (user FK, not null), `rawObjectKey` (string, not
   null — the object-store key of the raw upload), `processedObjectKey` (string, nullable — the
   object-store key of the white-background rendition, set once cleanup runs), `sourceMimeType`
   (string, not null), `supplierName` (text, nullable), `documentTotal` (numeric, nullable, ≥ 0),
   `status` (Processing_Status enum, not null), `uploadedAt` (timestamp, not null), and `parsedAt`
   (timestamp, nullable).
2. THE SYSTEM SHALL define a project-scoped entity `ProcurementLine` extending `BaseEntity` with
   fields `document` (FK, not null, `ON DELETE CASCADE`), `lineNo` (int, not null, ≥ 1), `rawName`
   (text, not null), `quantity` (numeric, nullable, ≥ 0), `unitHint` (text, nullable), `unitPrice`
   (numeric, nullable, ≥ 0), `lineTotal` (numeric, nullable, ≥ 0), `manufacturerHint` (text,
   nullable), `matchedMaterialId` (FK to the chosen Concrete_Material, nullable), `matchTier` (enum,
   not null), and `matchStatus` (enum, not null), unique on `(document_id, line_no)`.
3. THE SYSTEM SHALL create the `procurement_documents` and `procurement_lines` tables via Liquibase
   changesets following the FK + audit-column convention, idempotent via `NOT tableExists` +
   `onFail="MARK_RAN"`, registered last in `changelog.xml`.
4. WHEN a `ProcurementDocument` is deleted THEN its `procurement_lines` rows SHALL be discarded
   (`ON DELETE CASCADE`).
5. THE `matchedMaterialId` SHALL be nullable and SHALL be null for every line whose `matchTier` is
   `NONE`; it SHALL reference a Concrete_Material that is on the document's project's estimate.

### Requirement 2 — Document upload and storage

**User Story:** As a financier or manager, I want to upload a procurement receipt or invoice, so the
system can read it and match its items to the plan.

#### Acceptance Criteria

1. WHEN an authorized caller uploads a file to the document endpoint THEN the system SHALL accept a
   single image (`image/png`, `image/jpeg`, `image/webp`) or `application/pdf`, store the raw bytes via
   the existing `ImageStorage` facility namespaced per project, and create a `ProcurementDocument` in
   status `UPLOADED` referencing the returned `rawObjectKey`, the caller as `uploadedBy`, and the
   source MIME type (`processedObjectKey` null until cleanup runs).
2. WHEN the uploaded content type is none of the accepted types THEN the write SHALL be rejected with
   `error.procurement.upload.unsupportedType`, storing nothing and creating no document.
3. WHEN the uploaded file exceeds the configured maximum upload size THEN the write SHALL be rejected
   with `error.procurement.upload.tooLarge`, storing nothing and creating no document (reusing the
   `ImageStorage` size rule).
4. WHERE the deployment runs the `DisabledImageStorage` fallback (object store not configured) THE
   upload SHALL be rejected gracefully with `error.procurement.upload.storageUnavailable`, creating no
   document.
5. WHEN a `ProcurementDocument` is created THEN the system SHALL record `uploadedAt` and trigger OCR +
   parse + matching (Requirement 3), transitioning the document to `PROCESSING`.

### Requirement 3 — White-background cleanup, OCR, and per-line parse (new Java service)

**User Story:** As a user, I want the uploaded document's purchased items read automatically, so I do
not have to type each line by hand.

#### Acceptance Criteria

0. BEFORE text extraction THE SYSTEM SHALL produce a **white-background** rendition of the document:
   split a PDF into per-page images, auto-orient each page by EXIF, detect the document's four corners
   via the Google vision model, and white-out everything outside the detected quadrilateral at the
   page's original dimensions (no crop, warp, or scaling); on a per-page detection failure the page
   SHALL fall back to its oriented original. THE processed rendition SHALL be encoded as **lossless
   PNG** (`image/png`), keeping the receipt pixels untouched. THE SYSTEM SHALL store this rendition
   via `ImageStorage` and set `processedObjectKey`, retaining the raw upload unchanged.
1. THE SYSTEM SHALL extract text from the **processed** rendition using the Google Cloud Vision
   `documentTextDetection` flow (new Java implementation of the bot's `services/ocr.ts` logic),
   concatenating per-page texts for a multi-page PDF before the structured parse.
2. THE SYSTEM SHALL parse the extracted text into **per-line** structured items via a new Java LLM
   parse (the bot's `services/gemini.ts` approach, extended from a single receipt summary to one
   object per purchased item), each item carrying a raw name and, where present, quantity, unit hint,
   unit price, line total, and a manufacturer/brand hint; it SHALL also capture the document-level
   supplier name and total where present.
3. WHEN the parse succeeds THEN the system SHALL persist one `ProcurementLine` per extracted item
   (sequential `lineNo` from 1), set the document's `supplierName` / `documentTotal` from the parse,
   set `parsedAt`, and transition the document to `PARSED`.
4. WHEN cleanup, OCR, or the parse fails (provider error, unparseable output, or no lines extracted)
   THEN the document SHALL transition to `FAILED`, the raw upload SHALL be retained, and the document
   SHALL be re-processable (Requirement 8); the failure SHALL NOT delete the stored object(s) or the
   document.
5. THE cleanup + OCR + parse SHALL sit behind the new backend `OCR_Service`; WHERE the service is not
   configured (disabled fallback) THE document SHALL remain `UPLOADED` (or be marked `FAILED` on an
   explicit process request) with no processed rendition and no lines extracted, and the application
   SHALL start and run without the OCR provider configured.
6. THE provider credentials and model configuration SHALL be read from configuration/environment
   (reusing the existing `foremen.*` GCS credential conventions), never hardcoded.

### Requirement 4 — Planned-material candidate set

**User Story:** As a planner, I want extracted lines matched against the materials actually planned
for this project, so matches reflect the project's own plan.

#### Acceptance Criteria

1. THE match engine SHALL rank each extracted line against the set of `Planned_Material`s — the
   distinct Concrete_Materials chosen on the project's estimate (FOR-05-05/05b) — and SHALL never
   match a line to a material that is not on the project's estimate.
2. WHERE the project has no chosen Concrete_Materials THE match engine SHALL mark every extracted line
   `NONE`/`UNMATCHED` without error.
3. THE candidate set SHALL be read from the existing FOR-05-05b concrete-material projection; this spec
   SHALL NOT define a second, divergent material list.
4. EACH `Planned_Material` SHALL expose, for matching, its manufacturer (`producer`), its category
   (material `type`), and its localized name (`nameRU`/`namePL`), read from the existing material
   entity with the `ru → pl → code` fallback.

### Requirement 5 — Per-line tier match engine

**User Story:** As a user, I want each extracted line matched to a planned material at a confidence
tier, so I can trust high-confidence matches and review low-confidence ones.

#### Acceptance Criteria

1. THE match engine SHALL be a pure, deterministic, total function from `(extracted line, candidate
   Planned_Material set)` to a single `(matchedMaterialId, matchTier)` at the **highest reachable**
   tier, with no per-element I/O (so it is unit- and property-testable).
2. WHEN a candidate's manufacturer, category, AND name all match the line (manufacturer from the
   line's `manufacturerHint`, name from `rawName`, category inferred from the matched candidate) THEN
   the engine SHALL assign tier `EXACT` (100 %).
3. WHEN no `EXACT` candidate exists BUT a candidate's manufacturer AND category match THEN the engine
   SHALL assign tier `MANUFACTURER_CATEGORY` (80 %).
4. WHEN no `EXACT` or `MANUFACTURER_CATEGORY` candidate exists BUT a candidate's category matches THEN
   the engine SHALL assign tier `CATEGORY` (50 %).
5. WHEN no candidate matches at any tier THEN the engine SHALL assign tier `NONE` (no match),
   `matchedMaterialId` null, `matchStatus` `UNMATCHED`.
6. WHEN more than one candidate qualifies at the winning tier THEN the engine SHALL pick a single
   deterministic winner by a stable, documented tie-break (e.g. the closest name similarity, then the
   lowest material id), so the same input always yields the same output.
7. THE name and manufacturer comparison SHALL be case- and whitespace-insensitive and diacritic-
   tolerant (so `"Śnieżka"` / `"sniezka"` compare equal), matching the multilingual (PL/RU) material
   names; the exact normalization SHALL be documented and property-tested.
8. THE tier→percentage mapping (EXACT=100, MANUFACTURER_CATEGORY=80, CATEGORY=50, NONE=0) SHALL be a
   presentational label; the engine persists the tier, not a free continuous score.

### Requirement 6 — Procurement tab and proposed-match review (frontend)

**User Story:** As a financier or manager, I want a Procurement tab to upload a document and see its
extracted lines with their proposed matches, so I can confirm the intake before reconciliation.

#### Acceptance Criteria

1. THE SYSTEM SHALL render a Procurement panel on the execution-stage `procurement` tab, listing the
   project's uploaded documents with uploader, upload time, supplier, document total, and processing
   status.
2. THE panel SHALL provide an upload control accepting an image or PDF, showing upload progress and
   the resulting processing status (`UPLOADED` → `PROCESSING` → `PARSED` / `FAILED`).
3. WHEN a document is `PARSED` THEN the panel SHALL show its extracted lines with raw name, quantity,
   unit, unit price, line total, the proposed matched material name, and a localized **tier badge**
   (100 % / 80 % / 50 % / no-match), and SHALL let the user view the stored images — both the raw
   upload and the processed white-background rendition.
4. WHEN a document is `FAILED` THEN the panel SHALL show the failure state and offer a re-process
   action (Requirement 8); the raw upload (and the processed rendition if produced) SHALL remain
   viewable/downloadable.
5. THE proposed-match review in this spec SHALL be **read-only** — confirming, re-linking, and
   classifying unmatched lines are delivered by FOR-06-04; this panel surfaces the auto-match result
   and server validation errors inline.
6. THE panel SHALL surface server validation errors (Requirement 2) inline on upload.

### Requirement 7 — Stage gating and ABAC

**User Story:** As a security owner, I want procurement intake available only on active projects to
permitted roles, so documents can only be uploaded in the right stage by the right people.

#### Acceptance Criteria

1. WHEN a project is `ACTIVE` THEN the procurement surface SHALL be open (upload / process / read);
   WHEN a project is `COMPLETED` THEN the surface SHALL be read-only (history visible, no new uploads
   or re-processing); WHEN a project is `DRAFT` (design stage) THEN no procurement surface SHALL be
   reachable.
2. THE SYSTEM SHALL define a new **project-scoped** ABAC resource `PURCHASES`, its backing service
   implementing `ProjectScopedService` with `getProjectIdPath()` returning the path from
   `ProcurementDocument` to its owning project id, so LIST reads are automatically project-filtered.
3. THE controller SHALL be annotated `@PermissionResource("PURCHASES")`, every in-scope handler SHALL
   carry a matching `@PermissionOperation` (or `@RequiresPermission`), and the application SHALL start
   without a `PermissionAnnotationValidator` failure.
4. THE SYSTEM SHALL seed the `PURCHASES` resource row idempotently and grant, on top of the
   project-scope filter:
   - **ADMIN** — CREATE, READ, UPDATE, DELETE (matrix completeness; the evaluator bypasses for the
     literal ADMIN role).
   - **FINANCIER** — CREATE, READ, UPDATE, DELETE (the primary procurement role).
   - **MANAGER** — CREATE, READ, UPDATE, DELETE.
   - **FOREMAN** — CREATE, READ, UPDATE, DELETE (the foreman buys materials on site).
   - **Other admin-staff members** (e.g. ESTIMATOR) — READ only.
   - **WORKER** and **CLIENT** — no grant (deny-by-default), documented with an explanatory XML comment.
   The upload/process custom actions map onto CREATE/UPDATE on the document.
5. WHEN a caller without a `PURCHASES` grant issues any operation THEN it SHALL be rejected; a reader
   with only READ SHALL see the panel read-only and SHALL NOT upload or re-process.
6. WHEN the `PURCHASES` resource is seeded THEN the FOR-06-00 `procurement` execution tab (gated
   `PURCHASES`/READ) SHALL become visible to permitted callers on an `ACTIVE` project, and this spec
   SHALL swap that tab's `lazy` from `PlaceholderTab` to the real procurement panel without reshaping
   the `WORKSPACE_TABS` contract. (The sibling `purchases` tab, also on `PURCHASES`, is swapped by
   FOR-06-04.)

### Requirement 8 — Re-processing a document

**User Story:** As a user, I want to re-run OCR and matching on a document, so a transient failure or
an improved plan does not force me to re-upload.

#### Acceptance Criteria

1. WHERE a document is `FAILED` or `PARSED` THE system SHALL offer a re-process action that re-runs
   cleanup + OCR + parse + matching on the already-stored raw bytes (no re-upload), regenerating the
   white-background rendition and replacing `processedObjectKey` (the superseded processed object is
   cleaned up via `ImageStorage.deleteIfOrphan`).
2. WHEN a document is re-processed THEN its previous `ProcurementLine`s SHALL be replaced atomically by
   the freshly extracted lines, and the document SHALL transition `PROCESSING` → `PARSED`/`FAILED`.
3. WHEN re-processing fails THEN the document SHALL return to `FAILED` with its prior raw upload intact
   (never losing the stored raw object).
4. WHERE a line was manually re-linked in FOR-06-04 THE re-process behaviour on that line SHALL be
   defined by FOR-06-04 (this spec replaces only auto-matched lines); until FOR-06-04 ships, a
   re-process replaces all lines.

### Requirement 9 — No notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** user-facing notifications in this spec. Document upload, OCR, and
   auto-matching are a synchronous authoring action with no cross-role handoff: a FINANCIER or MANAGER
   discovers new documents by opening the Procurement tab. Any later notify-on-handoff behaviour
   (e.g. unmatched lines awaiting review) belongs to the reconciliation surface (FOR-06-04) and, if
   introduced, SHALL emit via the generic per-recipient `Notification_Service` (FOR-05-07 Requirement
   13) rather than re-implementing delivery.

### Requirement 10 — Internationalization completeness

#### Acceptance Criteria

1. THE backend `ProcurementDocument` / `ProcurementLine` entities SHALL carry **no** `nameRU`/`namePL`
   i18n columns (they are transactional, not dictionaries); every referenced dictionary name (material,
   manufacturer/producer, material category/type, measurement unit) SHALL be read from the existing
   entity with the `ru → pl → code` fallback, adding no new i18n column. The extracted `rawName`,
   `unitHint`, and `manufacturerHint` are verbatim OCR text, not localized.
2. THE frontend SHALL add every new user-facing string — tab/page title, document-list columns and
   filters, processing-status badges, upload control labels/placeholders, the raw/processed image
   viewer labels, the extracted-line table columns, the match-tier badges (100 % / 80 % / 50 % /
   no-match), the re-process action, and all success/error toasts — to **both** `src/locales/pl.json`
   and `src/locales/ru.json` with real PL and RU values (no English fallback), and the locale-parity
   test SHALL pass.
3. EVERY `error.procurement.*` message code SHALL have PL + RU messages in the backend message bundle.
4. THE frontend SHALL never surface a raw i18n key to the user.

### Requirement 11 — Audit, regression, and test artifacts

#### Acceptance Criteria

1. WHEN a document is uploaded, processed, re-processed, or deleted THEN an audit row SHALL be written
   via the existing audit mechanism.
2. WHEN this spec ships THEN the FOR-05 / FOR-06-00 behaviors not changed here (the workspace shell,
   `resolveWorkspaceTabs`, `stageOf`, the other execution tabs' placeholders, the `ImageStorage`
   facility, the material catalogs) SHALL continue to pass (regression).
3. THE spec SHALL ship its own backend unit + property tests (the match engine across all four tiers
   and the tie-break + normalization, the white-background cleanup corner/fallback logic, the LLM
   per-line parse tolerance, the upload type/size rules, dual-rendition storage, the ABAC matrix, seed
   idempotency), frontend unit tests (upload flow, status rendering, tier badges, image-viewer,
   resolver fixtures), and an API-level `test-cases.md` run against the Dockerized app.
