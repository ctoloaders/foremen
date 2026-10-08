# Implementation Plan: FOR-05-08 — Document signing (generic signable-document module)

## Overview

This plan implements the design in incremental, integrated steps that build on each other and end
with wiring the project-workspace signing tab and the admin template editor together. Backend comes
first (schema → entities → pure collaborators → services → providers → controllers), then the
frontend surface. Every step references specific requirements, and every correctness property from
the design (Properties 1–7, with Property 1 being the parent Property 16 integrity invariant)
becomes a property-based test placed next to the code it validates.

It spans **two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Backend + migrations + spec** (root repo): the new `signable_document_types` /
  `document_templates` / `company_profile` / `signable_documents` / `document_form_fields` /
  `document_signatures` / `document_media` schema and the `SIGNABLE_DOCUMENTS` (project-scoped) +
  `SIGNABLE_DOCUMENT_TYPES` / `DOCUMENT_TEMPLATES` / `COMPANY_PROFILE` (global admin-only) ABAC
  seeds; the pure collaborators (`DocumentStatusMachine`, `SigningProgressCalculator`,
  `TemplateMergeEngine` + `MergeFieldResolver`s, `SigningAuthorizationGuard`); the services
  (`SignableDocumentService`, `SignatureService`, `PdfFreezeService`, `DocumentMediaService`,
  `DocumentTemplateService`, `DocumentNotificationEmitter`, `ProjectActivationSignal`); the
  `SignatureProvider` abstraction + `StubSignatureProvider`; and the controllers. Backend/spec
  commits are made **from the root repo**.
- **Frontend** (`foremen-frontend/`, nested repo): the `features/document-signing/*` module (the
  project-workspace `DocumentSigningTab`, `DocumentDetail`, `RequestSignaturesDialog`,
  `TabletParafkaCapture`, `ScanUpload`, `FormFieldsPanel`, the admin `TemplateEditor`), the
  `workspaceTabs.ts` registration, and the `documentSigning.*` locale keys. Frontend commits are
  made **from inside `foremen-frontend/`**.

This feature spans both repos, so it needs **two commits — one per repo** (backend + migrations +
spec in the root; all frontend source in `foremen-frontend/`).

**Entity-creation checklist note** (per `.kiro/steering/entity-creation-rules.md`). This spec
introduces **four new managed ABAC resources**:

- `SIGNABLE_DOCUMENTS` — **project-scoped**. Wired end-to-end: (1) resource-row seed changeset
  registered **last** in `changelog.xml`; (2) ADMIN grant + the full role matrix of Requirement 8.3
  in the same changeset; (3) class-level `@PermissionResource("SIGNABLE_DOCUMENTS")` on
  `SignableDocumentController`, combined with per-handler `@PermissionOperation` /
  `@RequiresPermission` (every signing action maps to `UPDATE` — **no new ABAC operation**, R8.2) so
  `PermissionAnnotationValidator` classifies the controller COMPLETE at startup; (4)
  `SignableDocumentService` is a `ProjectScopedService` with `getProjectIdPath()` → `"project.id"`
  (its signature/media/form-field children resolve through `document.project.id`).
- `SIGNABLE_DOCUMENT_TYPES`, `DOCUMENT_TEMPLATES`, `COMPANY_PROFILE` — **global, admin-only** (not
  project-scoped). Each gets a resource-row seed + ADMIN CRUD grant changeset and a fully-annotated
  `@PermissionResource` controller; standard CRUD only.

The `SignatureCallbackController` (`POST /api/signatures/callback`) is **intentionally unguarded**
(an unauthenticated provider webhook, like `AuthController`), validated instead by `providerRef` +
`contentHash`; it carries none of the three permission annotations.

**Migrations.** New Liquibase changesets begin at `137` (current changelog head is `136` —
`136-seed-estimator-role.xml`; if a sibling spec lands further changesets first, use the next free
`NNN`). The sequence is 137–148 per the design's changeset table. Each follows the
`NOT tableExists` / `NOT columnExists` / per-row `NOT EXISTS` / `onFail="MARK_RAN"` idempotency
convention, follows the `017-seed-users-resource.xml` two-changeSet resource+grant pattern for the
ABAC seeds, and is appended **last** in `changelog.xml`.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (it takes
~20 minutes). Run only the affected test classes with `--tests`, redirect to a temp log, and read the
JUnit result XML (`foremen-backend/build/test-results/test/TEST-<fqcn>.xml`). Use
`compileJava` / `compileTestJava` for compile-only checks and `getDiagnostics` for fast per-file
checks. Run the full suite only if the user explicitly asks.

**Languages.** Backend in Java (Spring Boot / JPA / MapStruct / Liquibase / jqwik). Frontend in
TypeScript/React (Vite, react-i18next, TanStack Query); property tests in fast-check + Vitest where
pure client reducers exist. The embedded editor and the DOCX/PDF-freeze libraries are chosen in this
tasks phase and isolated behind `TemplateEditor` (FE) and `TemplateMergeEngine` / `PdfFreezeService`
(BE).

## Tasks

- [x] 1. Schema and seed changesets (Liquibase, idempotent, registered last)
  - [x] 1.1 Create reference-table changesets 137–140
    - Add `137-create-signable-document-types.xml` (`signable_document_types` + unique `code`),
      `138-seed-signable-document-types.xml` (the 14 types of R2.3, per-row `NOT EXISTS`,
      `onFail="MARK_RAN"`), `139-create-document-templates.xml` (`document_templates` + partial
      unique index: one active template per `(document_type_id, locale)`),
      `140-create-company-profile.xml` (`company_profile` single-row reference).
    - Register all four **last in sequence** in `changelog.xml`.
    - _Requirements: 2.1, 2.3, 2.5, 3.1, 3.2_

  - [x] 1.2 Create domain-table changesets 141–144
    - Add `141-create-signable-documents.xml` (`signable_documents` + FKs to `projects` /
      `signable_document_types` / `users`, `status` default `DRAFT`, nullable `document_uri`,
      `content_hash`, `title`, `source_ref`, `signature_level`, `template_locale`),
      `142-create-document-form-fields.xml`, `143-create-document-signatures.xml` (FKs, `status`
      default `PENDING`, `level` default `AdES`), `144-create-document-media.xml`.
    - Register all four **last in sequence** in `changelog.xml`.
    - _Requirements: 1.1, 1.2, 1.8, 3.6, 4.1, 7.1_

  - [x] 1.3 Create ABAC resource + role-matrix seed changesets 145–148 (entity-creation-rules steps 1–2)
    - Add `145-seed-signable-documents-resource.xml` (project-scoped `SIGNABLE_DOCUMENTS` resource
      row + ADMIN CRUD grant + the full R8.3 matrix: ADMIN CRUD, MANAGER CRUD, FOREMAN CRU, WORKER R,
      FINANCIER R, CLIENT RU — standard CRUD only, **no** new operation),
      `146-seed-signable-document-types-resource.xml`, `147-seed-document-templates-resource.xml`,
      `148-seed-company-profile-resource.xml` (each: global resource row + ADMIN CRUD grant).
    - Follow the `017-seed-users-resource.xml` two-changeSet pattern; guard with
      `sqlCheck`/`onFail="MARK_RAN"`; register all four **last in sequence** in `changelog.xml`.
    - _Requirements: 8.1, 8.3, 8.7_

- [x] 2. JPA entities, enums, repositories, DTOs
  - [x] 2.1 Add enums and entities
    - Define enums `DocumentStatus`, `SignatureMethod`, `SignatureLevel`, `SignatureStatus`,
      `DocumentMediaKind`. Create `@Entity` classes `SignableDocumentTypeEntity`,
      `DocumentTemplateEntity`, `CompanyProfileEntity`, `SignableDocumentEntity`,
      `DocumentFormFieldEntity`, `DocumentSignatureEntity`, `DocumentMediaEntity` (extending
      `BaseEntity`) matching the Data Models tables, with Spring Data repositories for each.
    - _Requirements: 1.1, 1.2, 2.1, 3.1, 4.1, 5.1, 6.1, 7.1_

  - [x] 2.2 Add DTOs and MapStruct mappers
    - Create `SignableDocumentDto`, `SigningProgressDto`, `DocumentSignatureDto`,
      `DocumentMediaSummaryDto`, `FormFieldDto`, `DocumentTemplateDto`, `TestMergeResultDto`,
      `CreateDocumentRequest`, `RequestSignaturesInput`, `SignRequest`, `DeclineRequest`,
      `FormFieldValuesInput`, `ProviderCallback`. Client-reachable DTOs carry **no**
      cost/estimate/margin field.
    - _Requirements: 4.5, 9.6, 13.1, 13.3_

- [x] 3. Pure collaborators (property-testable, no persistence)
  - [x] 3.1 Implement `DocumentStatusMachine`
    - `transition(current, action) → next | throws error.document.illegal.transition`; encode exactly
      the legal command edges (`DRAFT→PENDING_SIGNATURES`, `DRAFT→VOID`, `PENDING_SIGNATURES→VOID`);
      `SIGNED` is never a direct target; terminal states have no exit.
    - _Requirements: 1.3_

  - [x] 3.2 Write property test for `DocumentStatusMachine`
    - **Property 2: Status machine permits only the legal transitions** (totality: every input
      returns a legal next state or throws)
    - **Validates: Requirements 1.3, 1.4, 6.6**

  - [x] 3.3 Implement `SigningProgressCalculator`
    - Pure `progress(List<DocumentSignature>) → { signedCount, totalCount, allSigned, outstanding[] }`
      with `allSigned == (signedCount == totalCount && totalCount > 0)`.
    - _Requirements: 4.5, 9.4_

  - [x] 3.4 Write property test for `SigningProgressCalculator`
    - **Property 5: Signing progress is a pure function of the signature set**
    - **Validates: Requirements 4.5, 9.4**

  - [x] 3.5 Implement `SigningAuthorizationGuard`
    - Operation-level guard: CLIENT may only `sign`/`decline`/`fillFormFields` on their **own**
      signature/own form fields; CLIENT `create`/`generate`/`saveBody`/`requestSignatures`/`void`/
      `delete` rejected with `403 error.document.operation.forbidden`; restrict write actions to
      MANAGER/ADMIN plus FOREMAN for its configured owned type set.
    - _Requirements: 8.4, 8.5_

  - [x] 3.6 Write unit tests for `SigningAuthorizationGuard`
    - CLIENT allow/deny matrix; FOREMAN-owned type set; MANAGER/ADMIN allowances.
    - _Requirements: 8.4, 8.5_

- [x] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Merge layer (`TemplateMergeEngine` + `MergeFieldResolver`s)
  - [x] 5.1 Implement `MergeContext`, `MergeFieldResolver` interface, and the concrete resolvers
    - Build `MergeContext` (project, CLIENT member(s), approved offer net total, rooms/area,
      MANAGER/FOREMAN representative, `CompanyProfile` requisites) and the resolvers
      `DocumentMetaResolver`, `ClientResolver`, `ScheduleResolver`, `OfferTotalsResolver`,
      `CompanyRequisitesResolver`, `PropertyResolver`, `RepresentativeResolver` per the R3.3 token map.
    - _Requirements: 3.3, 3.8, 14.4_

  - [x] 5.2 Implement `TemplateMergeEngine.render` with deterministic unresolved handling
    - Substitute resolved tokens; return the explicit `unresolvedPlaceholders` set; support both
      caller-selected modes — mark `«___»` blank OR fail with `422
      error.document.unresolved.placeholders` — never silent empty substitution. Choose and isolate
      the DOCX/OOXML toolkit behind this engine.
    - _Requirements: 3.3, 3.4, 3.8_

  - [x] 5.3 Write property test for the merge engine
    - **Property 6: Merge resolution is deterministic with reported unresolved placeholders**
    - **Validates: Requirements 3.3, 3.4, 3.8**

  - [x] 5.4 Write unit tests for the resolvers
    - Per-group token resolution and null→unresolved behavior for partial contexts.
    - _Requirements: 3.3, 3.4_

- [x] 6. Media, PDF-freeze, and provider abstraction
  - [x] 6.1 Implement `DocumentMediaService`
    - Project-scoped via `document.project.id`; validate content type + size (reject disallowed with
      `400 error.document.media.invalid`); store in object storage (FOR-12) with metadata + `kind` in
      DB; refuse to delete media backing a `SIGNED` signature (`409 error.document.media.locked`).
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 6.2 Write unit tests for `DocumentMediaService`
    - Content-type/size rejection; signed-evidence delete lock.
    - _Requirements: 7.3, 7.4_

  - [x] 6.3 Implement `PdfFreezeService`
    - Render the current DRAFT body (including declared form fields as fillable regions) to a
      canonical immutable PDF, store it, return `{ storageUri, contentHash }` (sha-256). Isolate the
      HTML/DOCX→PDF renderer behind this service.
    - _Requirements: 1.5, 3.6_

  - [x] 6.4 Implement `SignatureProvider` abstraction + `StubSignatureProvider`
    - Interface `createSession` / `verify`; `StubSignatureProvider` (default
      `foremen.signing.provider=stub`) returns a synthetic `providerRef`, drives
      initiate→pending→callback→signed/declined, and in `verify` recomputes/compares the evidence
      hash against the stored `contentHash` — no live QTSP/podpis.gov.pl call; absence never affects
      `PRINT`/`TABLET_INITIALS`.
    - _Requirements: 5.1, 5.3, 5.4, 5.5_

  - [x] 6.5 Write integration test for the stub provider flow
    - initiate → callback → `SIGNED` including the hash-verify branch (and mismatch rejection).
    - _Requirements: 5.3, 6.4_

- [x] 7. Core signing services
  - [x] 7.1 Implement `SignableDocumentService` (ProjectScopedService) lifecycle
    - Implement `getProjectIdPath()` → `"project.id"` and `create`/`generate`/`saveBody`/
      `requestSignatures`/`fillFormFields`/`voidDocument`/`progress`. `requestSignatures` freezes the
      PDF, sets `contentHash`, creates one `PENDING` `DocumentSignature` per resolved signer (≥1),
      transitions via `DocumentStatusMachine`; `generate`/`saveBody` reject on non-`DRAFT`
      (`409 error.document.frozen`); wire `SigningAuthorizationGuard` and `ProjectAccessCache`.
    - _Requirements: 1.1, 1.3, 1.5, 1.7, 1.8, 3.5, 3.7, 4.2, 4.3, 8.4_

  - [x] 7.2 Implement `recomputeSignedState` (derived SIGNED)
    - After every signature mutation, set `document.status = SIGNED` **iff** every
      `DocumentSignature` is `SIGNED` and ≥1 exists; never assign `SIGNED` imperatively. Single
      consistent operation.
    - _Requirements: 1.4, 6.6_

  - [x] 7.3 Write property test for the derived SIGNED invariant (core)
    - **Property 1 (parent Property 16): SignableDocument is SIGNED iff all signatures are SIGNED**,
      including method-specific evidence/integrity preconditions and the immutable-PDF invariant
      (contentHash stable once `PENDING_SIGNATURES`; body edits rejected).
    - **Validates: Requirements 1.4, 1.5, 1.6, 6.6**

  - [x] 7.4 Implement `SignatureService` (per-method completion)
    - `completeViaScan` (PRINT) / `completeViaTablet` (TABLET_INITIALS): store `DocumentMedia`
      evidence then mark the caller's signature `SIGNED` + `signedAt`; reject `SIGNED` without
      evidence (`409 error.document.evidence.required`). `completeViaProvider` / `onProviderCallback`
      (ONLINE/PODPIS_GOV_PL): verify sealed-evidence hash against `contentHash` (`409
      error.document.hash.mismatch`) before `SIGNED`. `decline` → `DECLINED` with reason (does not
      void). Call `recomputeSignedState` after each mutation.
    - _Requirements: 4.4, 5.3, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [x] 7.5 Write property test for evidence/integrity preconditions
    - **Property 4: Evidence and integrity preconditions for reaching SIGNED**
    - **Validates: Requirements 5.3, 6.2, 6.3, 6.4**

  - [x] 7.6 Write unit test for body/type/binding immutability after freeze
    - **Property 3: Body, binding, and type are immutable after freeze; SIGNED is fully immutable**
    - **Validates: Requirements 1.5, 1.6, 3.7**

- [x] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Notifications and activation hand-off
  - [x] 9.1 Implement `DocumentNotificationEmitter` (best-effort)
    - Wrap `NotificationService.create` for the five R11.2 types, each with a deep-link to the
      document in the signing tab; emit after commit (`AFTER_COMMIT`
      `@TransactionalEventListener`), swallow/log failures; emit nothing for DRAFT edits.
    - _Requirements: 11.1, 11.2, 11.3, 11.5_

  - [x] 9.2 Write unit tests for the emitter
    - Each trigger maps to the right type + recipient set + deep-link; failures swallowed; no DRAFT
      emission.
    - _Requirements: 11.2, 11.5_

  - [x] 9.3 Implement `ProjectActivationSignal`
    - `onContractSigned(doc)`: signal `DRAFT → ACTIVE` eligibility **iff** `CONTRACT_*` + `SIGNED` +
      project offer `APPROVED`; consume (not re-implement) FOR-05-07/13 lock/snapshot; non-contract
      triggers nothing; idempotent re-signal. Invoke from `recomputeSignedState` on full-sign.
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

  - [x] 9.4 Write property test for the activation signal
    - **Property 7: Contract-signed activation signal is idempotent and contract/offer-gated**
    - **Validates: Requirements 10.1, 10.3, 10.4**

- [x] 10. Template admin service
  - [x] 10.1 Implement `DocumentTemplateService` (admin, versioned)
    - CRUD over `DocumentTemplate`; `.docx` import (seed from `docs/templates/`); activate/deactivate
      per `(documentType, locale)` with the one-active partial-unique constraint; versioned saves
      (bump `version`, retain prior versions); `test-merge` preview returning rendered body +
      unresolved placeholders. Deactivate-never-delete.
    - _Requirements: 2.5, 3.1, 3.2, 3a.1, 3a.4, 3a.5_

  - [x] 10.2 Write unit tests for `DocumentTemplateService`
    - Version bump + prior-version retrieval; one-active enforcement; test-merge preview; deactivate
      not delete.
    - _Requirements: 3.2, 3a.1, 3a.5_

- [x] 11. Controllers (ABAC-wired per entity-creation-rules step 3)
  - [x] 11.1 Implement `SignableDocumentController`
    - Class-level `@PermissionResource("SIGNABLE_DOCUMENTS")`; all project document + signing
      endpoints of R13.1 (`list`, `read`, `create`, `generate`, `body`, `request-signatures`,
      `form-fields`, `sign`, `sign-tablet`, `media`, `decline`, `void`, `document` download); every
      handler carries `@PermissionOperation` / `@RequiresPermission` mapping to standard CRUD —
      signing actions are `UPDATE` (no new operation).
    - _Requirements: 8.1, 8.2, 8.6, 9.2, 13.1, 13.2_

  - [x] 11.2 Implement `SignatureCallbackController` (unguarded webhook)
    - `POST /api/signatures/callback`; intentionally no permission annotations (like `AuthController`);
      validated by `providerRef` + `contentHash`; delegates to `SignatureService.onProviderCallback`.
    - _Requirements: 5.3, 6.4, 13.1_

  - [x] 11.3 Implement global admin controllers
    - `SignableDocumentTypeController` `@PermissionResource("SIGNABLE_DOCUMENT_TYPES")` (type CRUD +
      activate/deactivate), `DocumentTemplateController` `@PermissionResource("DOCUMENT_TEMPLATES")`
      (template CRUD, `.docx` import, body edit, version/activate, test-merge), and a
      `CompanyProfileController` `@PermissionResource("COMPANY_PROFILE")`. Each fully annotated so
      `PermissionAnnotationValidator` passes.
    - _Requirements: 2.4, 2.5, 3a.1, 3a.6, 8.7, 13.1_

  - [x] 11.4 Write ABAC / startup integration tests
    - Each new resource reachable only with the right `(resource, operation)` grant + project
      membership; `PermissionAnnotationValidator` COMPLETE at startup; a structural test asserts no
      client-reachable DTO carries a cost/margin/estimate field name.
    - _Requirements: 8.1, 8.6, 9.6, 13.3_

- [x] 12. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. Frontend: project-workspace Document signing tab (`foremen-frontend/`)
  - [x] 13.1 Create the `features/document-signing/` module and data hooks
    - Add types/client and `useSignableDocuments` / `useDocumentTemplates` hooks over the REST
      endpoints; export server actions + components so FOR-06/FOR-09 reuse them without a rewrite.
    - _Requirements: 9.5, 14.3_

  - [x] 13.2 Implement `DocumentSigningTab` + `DocumentDetail` and register the tab
    - List documents (type, status, progress, created); role-aware action bar driven by
      `usePermission`; `DocumentDetail` shows body/artifact preview + per-signer progress. Register
      the `documentSigning` tab in `features/project-workspace/workspaceTabs.ts` (`stage: 'design'`,
      `requiredPermission: { resource: 'SIGNABLE_DOCUMENTS', operation: 'READ' }`), superseding the
      legacy `contract` placeholder; CLIENT view exposes only read + own-signature update, no
      cost/estimate/margin.
    - _Requirements: 9.1, 9.3, 9.4, 9.6_

  - [x] 13.3 Implement the signing/action components
    - `RequestSignaturesDialog` (pick signers + method + level; triggers freeze), `ScanUpload`
      (PRINT), `TabletParafkaCapture` (TABLET_INITIALS), `FormFieldsPanel` (PENDING only; CLIENT
      limited to own), plus decline/download/void actions.
    - _Requirements: 6.2, 6.3, 9.2_

  - [x] 13.4 Write component tests for the tab and action gating
    - Action visibility by permission; CLIENT confidentiality; progress refresh after actions.
    - _Requirements: 9.3, 9.4, 9.6_

- [x] 14. Frontend: admin template editor (`foremen-frontend/`)
  - [x] 14.1 Implement `TemplateEditor`
    - Admin WYSIWYG paginated Word-like surface; insert merge placeholders (from the R3.3 catalog) and
      form fields as chips (raw `{...}` still accepted); preview/test-merge showing rendered result +
      unresolved placeholders; version/activate controls; template CRUD + `.docx` import. Choose and
      isolate the embedded editor library.
    - _Requirements: 3a.1, 3a.2, 3a.3, 3a.4, 3a.5, 3a.6_

  - [x] 14.2 Write component tests for the editor
    - Placeholder/form-field chip insertion; test-merge preview with unresolved list; version/activate.
    - _Requirements: 3a.3, 3a.4, 3a.5_

- [x] 15. Internationalization (`foremen-frontend/`)
  - [x] 15.1 Add PL + RU locale keys at parity
    - Add `documentSigning.*` keys for: tab label; document-type names; status labels; method labels;
      level labels; every action; signer/progress labels; the template-editor chrome; validation/error
      messages; and the five notification type labels. Non-empty in both `pl.json` and `ru.json`.
    - _Requirements: 12.2, 12.4_

  - [x] 15.2 Write i18n completeness check
    - Assert every key introduced by this module has a non-empty value in both `pl.json` and `ru.json`.
    - _Requirements: 12.4_

- [x] 16. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via run-id generator / teardown,
    regression group, MD report template). This module has **both** an API surface (backend signing
    endpoints against the Dockerized app — `docker compose up`, base URL `http://localhost:8080`,
    `/api/signable-documents/...`, `/api/signatures/callback`) **and** a UI surface (the Document
    signing workspace tab + the admin template editor). So `test-cases.md` MUST cover both API-test
    scenarios (HTTP request → status/body/headers assertions) **and** browser-engine UI scenarios
    (navigate/input/click/wait steps), written **in Russian**, with explicit repeatability strategy,
    a regression group, and the MD report template with tables.
  - _Requirements: 1.3, 1.4, 1.5, 3.4, 4.5, 6.2, 6.3, 6.4, 8.4, 9.1, 9.2, 10.1, 11.2, 12.4, 13.1_

- [x] 17. Final checkpoint - Ensure all tests pass and verify
  - Run only the affected test classes with `--tests` filters (per `test-execution-rules`), redirect
    to a temp log, and read the JUnit XML for pass/fail. Run `compileJava` / `compileTestJava` for
    compile-only checks. Ensure all tests pass; ask the user if questions arise. Confirm the backend
    builds and the frontend module type-checks before considering the spec implementation-ready.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP (unit, property, and
  integration tests); core implementation tasks are never optional.
- Each task references specific requirements for traceability.
- Correctness Properties 1–7 from the design each become a property-based test placed next to the
  code it validates; Property 1 (the parent Property 16 integrity invariant) sits with
  `recomputeSignedState`.
- Checkpoints ensure incremental validation at natural boundaries (collaborators, services,
  controllers).
- Backend/spec changes commit from the **root repo**; frontend changes commit from inside
  `foremen-frontend/` — this feature needs **two commits, one per repo** (per `git-repo-structure`).
- `SignableDocumentService` is the only `ProjectScopedService`; the three global reference resources
  (`SIGNABLE_DOCUMENT_TYPES`, `DOCUMENT_TEMPLATES`, `COMPANY_PROFILE`) are admin-only, not
  project-scoped.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["1.3", "2.1"] },
    { "id": 3, "tasks": ["2.2", "3.1", "3.3", "3.5"] },
    { "id": 4, "tasks": ["3.2", "3.4", "3.6", "5.1", "6.1", "6.3", "6.4"] },
    { "id": 5, "tasks": ["5.2", "6.2", "6.5"] },
    { "id": 6, "tasks": ["5.3", "5.4", "7.1"] },
    { "id": 7, "tasks": ["7.2", "7.4", "10.1"] },
    { "id": 8, "tasks": ["7.3", "7.5", "7.6", "9.1", "9.3", "10.2"] },
    { "id": 9, "tasks": ["9.2", "9.4", "11.1", "11.2", "11.3"] },
    { "id": 10, "tasks": ["11.4", "13.1"] },
    { "id": 11, "tasks": ["13.2", "14.1"] },
    { "id": 12, "tasks": ["13.3", "14.2", "15.1"] },
    { "id": 13, "tasks": ["13.4", "15.2"] }
  ]
}
```
