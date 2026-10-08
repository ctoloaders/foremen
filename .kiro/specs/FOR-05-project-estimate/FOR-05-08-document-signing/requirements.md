# Requirements Document

## Introduction

This spec (**FOR-05-08-document-signing**) delivers a **generic, reusable signable-document
module** for the Foremen platform. It generalizes the contract-signing surface of the project
design stage into a cross-cutting domain that **any** part of the application can reuse to
generate, send, sign, and store legally meaningful documents. It replaces and unifies the two
previously-planned child specs `FOR-05-09-contracts-esign` (the contract specialization) and
`FOR-05-17-signable-documents` (the generalized engine): the contract is simply a
`SignableDocument` whose type is `CONTRACT`.

The authoritative model already exists in the parent design (`design.md` §4.8 entities, §5 ABAC,
§6 API shape, §11 Polish/eIDAS legal grounding, Property 16). This spec turns that design into
concrete, testable requirements and extends it with:

1. **Server-side document generation by template merge.** The business owns a library of Word
   templates (`docs/templates/`) that use merge placeholders (e.g. `{ContactName}`,
   `{Begindate}`, `{Closedate}`, `{TotalBeforeTax}`, executor requisites). A `DocumentTemplate`
   per document type is stored; the module renders a concrete document body by substituting
   placeholders with values resolved from project / offer / contract domain data, then freezes a
   `contentHash` of the rendered artifact.

2. **An expanded, seeded `SignableDocumentType` catalog** matching the business templates (work
   contract, interior-design contract, complex contract, packages contract, reservation
   contract, subcontract, amendment, handover-to-renovation protocol, works-acceptance protocol,
   internal defect form, works-manager statement, room-acceptance protocol, key handover).

3. **A signing lifecycle state machine** (`DRAFT → PENDING_SIGNATURES → SIGNED`, plus `VOID`)
   with multiple designated signers and the invariant that a document is `SIGNED` **iff** all of
   its signatures are `SIGNED` (parent design Property 16).

4. **Four signing methods** (`PRINT`, `ONLINE`, `PODPIS_GOV_PL`, `TABLET_INITIALS`) and three
   eIDAS **signature levels** (`SES` / `AdES` / `QES`, default `AdES`). `PRINT` (upload a scan of
   a wet-ink signature) and `TABLET_INITIALS` (capture an on-the-spot *parafka* on a tablet) are
   **fully implemented**. `ONLINE` (commercial QTSP/e-sign ceremony) and `PODPIS_GOV_PL`
   (podpis.gov.pl / Profil Zaufany) are modeled behind a **provider abstraction** with the full
   status flow but **no live provider integration** in this spec (pluggable later via
   FOR-12/config).

5. **ABAC `SIGNABLE_DOCUMENTS`** project-scoped resource wired per the `entity-creation-rules`
   checklist, with the role matrix of parent design §5 (CLIENT reads own + `sign`; MANAGER/ADMIN
   create and manage; FOREMAN creates/manages protocols; WORKER/FINANCIER read within scope).

6. **Notifications** emitted through the **existing generic notification service** (FOR-05-07
   Requirement 13 — the in-app/DB bell), never a reimplemented delivery path.

**Stage ownership.** Document signing is part of the **project design (`DRAFT`) stage** — this is
where the `CONTRACT` is prepared and signed, and signing it is the event that moves the project
`DRAFT → ACTIVE`. The **same generic engine** is later reused in the **working (`ACTIVE`) project**
for **amendments** (`AMENDMENT`) and execution-stage protocols (works/room acceptance, key
handover, defect form) — but those execution-stage flows are driven by **FOR-06**, which consumes
this module rather than re-implementing it. The module is deliberately **not coupled to a single
caller**: FOR-06 (amendments/acceptances) and **FOR-09** (the client portal signing surface) reuse
the same server actions and components.

### Scope boundaries

- **In scope:** the generic `SignableDocument` domain; `DocumentTemplate` storage + merge-based
  body generation; the signing state machine; `PRINT` + `TABLET_INITIALS` end-to-end; `ONLINE` +
  `PODPIS_GOV_PL` as provider-abstraction stubs; ABAC; notifications via the generic service; the
  in-workspace **Document signing** tab; i18n (entity + UI); the `CONTRACT`-signed →
  `ProjectStatus.ACTIVE` eligibility hand-off.
- **Out of scope (Non-Goals):** a live QTSP / podpis.gov.pl integration (only the abstraction +
  stub); the FOR-06 amendment business flow (08 only provides the engine + `AMENDMENT` type);
  the FOR-09 client-portal rendering (08 exposes reusable server actions + the in-workspace
  surface); email / SMS / Telegram delivery of notifications and the asynchronous fan-out service
  (future work, per FOR-05-07 Non-Goals); a WYSIWYG template editor (templates are managed
  artifacts, uploaded/seeded, not authored in-app in this spec).

### Relationship to existing specs (source of truth)

- **Parent overview** (`OVERVIEW.md`) — child row 08; the dependency graph edge `07 →
  08 → FOR-06`.
- **Parent design §4.8** — `SignableDocument`, `SignableDocumentType`, `DocumentSignature`,
  `DocumentMedia` entities, the seeded types, and the four signing methods.
- **Parent design §5** — ABAC: the `SIGNABLE_DOCUMENTS` resource (project-scoped,
  `getProjectIdPath = document.project.id`) and its role matrix.
- **Parent design §6** — the API shape (`/api/signable-documents/...`, request-signatures,
  sign-tablet, media upload, document download, provider callback).
- **Parent design §11** — the Polish/eIDAS legal grounding, `SignatureLevel` enum, default
  `AdES`, pluggable QTSP provider.
- **Parent design Property 16** — a `SignableDocument` is `SIGNED` iff all its signatures are
  `SIGNED`; `contentHash` integrity for `ONLINE`/`PODPIS_GOV_PL`; `DocumentMedia` evidence
  required for `PRINT`/`TABLET_INITIALS`.
- **FOR-05-07 (offer-approval)** — the generic notification service + in-app bell (Requirement
  13) this spec emits through; the `APPROVED` offer that a `CONTRACT` is generated from; the
  `OfferPriceSnapshot`/`DraftGate` hand-off.
- **FOR-03-04 / FOR-03-08** — `ProjectMember`, `ProjectScopedService`, `@PermissionResource`,
  `PermissionInterceptor`, and the `entity-creation-rules` checklist.
- **FOR-12** — object storage for `DocumentMedia` / rendered documents, and the pluggable
  e-signature provider / QTSP.

### Terminology

- **Document** — a `SignableDocument` instance (one row), of a given `SignableDocumentType`,
  attached to a project.
- **Template** — a `DocumentTemplate` (stored source file with merge placeholders) bound to a
  document type; the source for generated bodies.
- **Merge field / placeholder** — a `{...}` token in a template resolved from domain data.
- **Signer** — a `DocumentSignature` row: a party expected to sign (user and/or role), with a
  method, level, and status.
- **Executor (`Wykonawca`)** — the Foremen company (requisites from config/constant).
- **Client (`Zamawiający` / `Klient`)** — the project `CLIENT` member(s).

---

## Requirements

### Requirement 1 — SignableDocument domain and lifecycle

**User Story:** As a manager, I want a project document to progress through a clear lifecycle, so
that everyone can see whether a document is a draft, awaiting signatures, fully signed, or voided.

#### Acceptance Criteria

1. WHEN a `SignableDocument` is created THEN the system SHALL persist it with: `project` (FK),
   `documentType` (FK → `SignableDocumentType`), `status` (default `DRAFT`), `createdBy` (user
   FK), `createdAt`, and nullable `documentUri`, `contentHash`, `title`, `sourceRef` (an optional
   reference to the originating domain object, e.g. an offer id).
2. The `status` SHALL be one of `DRAFT`, `PENDING_SIGNATURES`, `SIGNED`, `VOID`.
3. The lifecycle transitions SHALL be exactly: `DRAFT → PENDING_SIGNATURES` (on request-signatures),
   `PENDING_SIGNATURES → SIGNED` (when all signatures complete), `DRAFT → VOID` and
   `PENDING_SIGNATURES → VOID` (on cancel/void). No other transition SHALL be permitted; an
   attempt SHALL fail with a validation error.
4. A `SignableDocument` SHALL reach `SIGNED` **if and only if** every one of its
   `DocumentSignature`s has status `SIGNED` (parent design Property 16).
5. WHEN a document transitions `DRAFT → PENDING_SIGNATURES` THEN the system SHALL **freeze an
   immutable PDF rendering** of the current body as the canonical signing artifact and compute its
   `contentHash`. From that point the body text SHALL be immutable; the ONLY permitted additions
   SHALL be **form-field entries** (fill-in blanks defined on the document) and **signatures /
   signature evidence**. Any attempt to change the body text, template binding, or type of a
   `PENDING_SIGNATURES` or `SIGNED` document SHALL be rejected.
6. WHILE a document is `SIGNED` THE system SHALL reject any mutation whatsoever of the artifact,
   form-field values, signer set, or type (the signed artifact is fully immutable).
7. WHEN a document is `VOID` THEN it SHALL be excluded from "pending" and "to-sign" lists but
   SHALL remain readable for audit.
8. The entity SHALL be project-scoped via `ProjectScopedService` with
   `getProjectIdPath() = "project.id"`.

### Requirement 2 — SignableDocumentType catalog (seeded, i18n)

**User Story:** As an administrator, I want the system to know every kind of document we sign, so
that each document is correctly labeled and bound to the right template.

#### Acceptance Criteria

1. The system SHALL define `SignableDocumentType` with fields `code` (unique), `namePL`,
   `nameRU`, `active` (boolean, default `true`), and an optional `defaultSignatureLevel`.
2. `namePL` and `nameRU` SHALL be the only backend i18n fields of this module; display SHALL use
   `namePL` with PL fallback when a locale value is missing (consistent with existing reference
   entities).
3. The system SHALL seed the following types (idempotent seed changeset, `onFail="MARK_RAN"`):

   | code | namePL | nameRU |
   |------|--------|--------|
   | `CONTRACT_WORKS` | Umowa o wykonanie prac wykończeniowych | Договор на выполнение отделочных работ |
   | `CONTRACT_WORKS_PL_RU` | Umowa PL-RU o wykonanie prac wykończeniowych | Договор (PL-RU) на отделочные работы |
   | `CONTRACT_DESIGN` | Umowa o wykonanie projektu aranżacji wnętrza | Договор на дизайн-проект интерьера |
   | `CONTRACT_COMPLEX` | Umowa kompleks (prace + projekt) | Комплексный договор (работы + проект) |
   | `CONTRACT_PACKAGES` | Umowa Pakiety Foremen | Договор «Пакеты Foremen» |
   | `CONTRACT_RESERVATION` | Umowa rezerwacyjna usług wykończeniowych | Договор резервации отделочных услуг |
   | `CONTRACT_SUBCONTRACTOR` | Umowa o podwykonawstwo | Договор субподряда |
   | `AMENDMENT` | Aneks do umowy | Анекс к договору |
   | `HANDOVER_TO_RENOVATION` | Protokół przekazania lokalu do remontu | Протокол передачи помещения в ремонт |
   | `WORKS_ACCEPTANCE` | Protokół odbioru prac wykończeniowych | Протокол приёмки отделочных работ |
   | `DEFECT_FORM` | Formularz usterkowy wewnętrzny | Внутренний дефектный формуляр |
   | `WORKS_MANAGER_STATEMENT` | Oświadczenie kierownika robót | Заявление руководителя работ |
   | `ROOM_ACCEPTANCE` | Protokół odbioru pomieszczenia | Протокол приёмки помещения |
   | `KEY_HANDOVER` | Przekazanie kluczy | Передача ключей |

4. A document type SHALL be creatable/editable by ADMIN only; it is a global reference (not
   project-scoped).
5. A document type SHALL be **deactivated** (set `active = false`), never deleted. A deactivated
   type SHALL be hidden from the "create document" picker and the active-template bindings, but it
   SHALL remain readable so existing `SignableDocument`s and `DocumentTemplate`s that reference it
   stay valid and auditable. Reactivation (set `active = true`) SHALL be allowed.

### Requirement 3 — Document templates and merge-field generation

**User Story:** As a manager, I want the document body generated automatically from our standard
template and the project data, so that I don't retype client, address, date, and total fields and
can't introduce typos.

#### Acceptance Criteria

1. The system SHALL define a `DocumentTemplate` with: `documentType` (FK), `name`, `locale`
   (`PL`/`RU`/bilingual), `storageUri` (the stored source file), `version`, `active`,
   `uploadedBy`, `uploadedAt`.
2. A document type MAY have multiple templates; exactly one SHALL be `active` per
   (`documentType`, `locale`); generation SHALL use the active template for the requested locale.
3. The system SHALL support template merge placeholders in the `{Token}` syntax used by the
   business templates in `docs/templates/`. The following placeholder groups SHALL be resolvable,
   each mapped to a documented domain source:

   | Placeholder(s) | Domain source |
   |----------------|---------------|
   | `{Id}` | the document's human id / number |
   | `{DocumentCreateTime}` | document creation date |
   | `{ContactName}`, `{ContactLastName}`, `{ContactEmail}`, `{ContactAddress}`, `{RequisitePrimaryAddressText}` | the project CLIENT member (name, contacts, address) |
   | `{Begindate}`, `{Closedate}` | project / offer planned start and end dates |
   | `{TotalBeforeTax}` | the approved offer net total |
   | executor requisites (`{UfCrm1663076108Requisite…}`: company full name, registered address, NIP, REGON, representative name/role, email) | Foremen company requisites from config/constant |
   | property/area (`{UfCrm1662541716442}` address of works, `{UfCrm1691765118573}` usable area) | project / room data |
   | representative (`{AssignedName}`, `{AssignedLastName}`, `{UfCrm1692271880…}`) | the project MANAGER / FOREMAN (executor representative) |

4. WHEN a placeholder in a template has no resolvable value THEN generation SHALL either (a) leave
   a clearly marked blank for manual completion, or (b) fail with a listed set of unresolved
   placeholders — the behavior SHALL be deterministic and reported to the caller (no silent
   empty substitution that looks like real data).
5. WHEN a document body is generated THEN the system SHALL store the editable DRAFT body
   (`documentUri`) and compute and store its `contentHash`. WHILE `DRAFT`, the body MAY be further
   edited in the template editor (Requirement 3a) and re-generated; each change recomputes the
   hash.
6. A template / document MAY declare **form fields** (fill-in blanks that remain enterable after
   the body is frozen — e.g. PESEL, ID-document number, hand-dated fields). Form fields SHALL be
   part of the frozen artifact (Requirement 1.5) and SHALL be the only textual content a signer
   may add to a `PENDING_SIGNATURES` document.
7. Re-generating or editing the body SHALL be rejected once the document is `PENDING_SIGNATURES`
   or `SIGNED`; at that point only form-field entry and signatures are permitted (Requirement 1.5).
8. The merge/field-resolution layer SHALL be a named, unit-testable component (so new document
   types/placeholders can be added without changing the signing lifecycle).

### Requirement 3a — Template editor and template CRUD

**User Story:** As an administrator, I want to create and edit document templates in an in-app
rich editor that looks and feels like an online document (Word/Google-Docs-like), inserting merge
placeholders and form fields visually, so that I can maintain our document library without
external tooling.

#### Acceptance Criteria

1. The system SHALL provide **CRUD over `DocumentTemplate`**: list, create, read, update (edit
   content + metadata), activate/deactivate per (`documentType`, `locale`), and import an existing
   `.docx` (e.g. seeded from `docs/templates/`). Templates SHALL be **deactivated, not deleted**,
   when a type is referenced, consistent with Requirement 2.5.
2. The system SHALL provide an **embedded rich-text document editor** rendering the template as a
   paginated, document-like WYSIWYG surface (headings, paragraphs, tables, lists, bold/italic) —
   an online-docx look-and-feel — not a raw code/markup box.
3. The editor SHALL let the author **insert merge placeholders** from a catalog of known tokens
   (Requirement 3.3) as visually distinct chips, and **insert form fields** (fill-in blanks,
   Requirement 3.6), without hand-typing `{...}` syntax (though raw tokens SHALL still be accepted).
4. The editor SHALL offer a **preview / test-merge** against sample or a selected project's data,
   showing the rendered result and listing any unresolved placeholders (Requirement 3.4).
5. Editing SHALL be versioned: saving SHALL create or bump a `DocumentTemplate.version`; the
   previously active version SHALL remain retrievable for audit; activating a version SHALL make it
   the one used by generation (Requirement 3.2).
6. Template editing SHALL be **ADMIN-only** (the template library is global, not project-scoped);
   it SHALL be governed by the admin-only template grant (Requirement 8.6), introducing no new
   ABAC action.
7. The editor's chrome (toolbar, placeholder/field pickers, dialogs, validation messages) SHALL be
   fully localized PL + RU (Requirement 12).

### Requirement 4 — Designated signers and the signature records

**User Story:** As a manager, I want to choose who must sign a document and track each party's
signature independently, so that I know exactly what is outstanding.

#### Acceptance Criteria

1. The system SHALL define `DocumentSignature` with: `document` (FK), `signerUser` (FK, nullable),
   `signerRole`, `method` (enum `SignatureMethod`), `level` (enum `SignatureLevel`), `providerRef`
   (nullable), `signedAt` (nullable), `evidenceUri` (nullable), `status`
   (`PENDING`/`SIGNED`/`DECLINED`), project-scoped via `document.project.id`.
2. WHEN a manager requests signatures THEN they SHALL provide a signer set (≥ 1) and a `method`;
   each signer SHALL be created with status `PENDING` and the document SHALL move to
   `PENDING_SIGNATURES`.
3. A signer MAY be identified by user, by role, or both; a CLIENT signer SHALL resolve to the
   project CLIENT member(s).
4. A `DocumentSignature` SHALL become `SIGNED` only when its method's completion condition is met
   (Requirement 6); a signer MAY `DECLINE` with a reason, which SHALL move that signature to
   `DECLINED` and SHALL NOT by itself void the document.
5. The system SHALL expose, per document, the aggregate signing progress (count signed / total,
   and the outstanding signer list).

### Requirement 5 — Signature levels (eIDAS) and provider abstraction

**User Story:** As a manager, I want to pick the legal strength of a signature appropriate to the
document, so that contracts are enforceable under Polish/EU law.

#### Acceptance Criteria

1. The system SHALL define `SignatureLevel = { SES, AdES, QES }`.
2. The default level for contracts and amendments SHALL be `AdES`; the manager SHALL be able to
   override the level per document before requesting signatures.
3. The system SHALL define a **provider abstraction** (interface) for the `ONLINE` and
   `PODPIS_GOV_PL` ceremonies that supplies: initiate-signing, a callback/verification entry
   point, and sealed-evidence retrieval.
4. In this spec the provider SHALL be a **stub implementation** that drives the full status flow
   (initiate → pending → callback → signed/declined) without contacting a live QTSP /
   podpis.gov.pl; the concrete provider is pluggable later via FOR-12/config without redesign.
5. The abstraction SHALL be chosen by configuration; absence of a configured live provider SHALL
   NOT break `PRINT`/`TABLET_INITIALS`, which do not use it.

### Requirement 6 — Signing methods (PRINT, TABLET_INITIALS, ONLINE, PODPIS_GOV_PL)

**User Story:** As a signer, I want to sign a document in the way that fits the situation — on
paper, on a tablet on site, or electronically — so that signing is practical.

#### Acceptance Criteria

1. The system SHALL define `SignatureMethod = { PRINT, ONLINE, PODPIS_GOV_PL, TABLET_INITIALS }`.
2. **PRINT:** WHEN a signer signs via `PRINT` THEN they SHALL upload a scan of the wet-ink signed
   document; the scan SHALL be stored as a `DocumentMedia`; the signature SHALL NOT reach `SIGNED`
   until that evidence is attached.
3. **TABLET_INITIALS:** WHEN a signer signs via `TABLET_INITIALS` THEN the captured handwritten
   initial / *parafka* image SHALL be stored as a `DocumentMedia`; the signature SHALL NOT reach
   `SIGNED` until that image is attached.
4. **ONLINE / PODPIS_GOV_PL:** WHEN a signer signs via `ONLINE` or `PODPIS_GOV_PL` THEN the
   signature SHALL be completed through the provider abstraction (Requirement 5); the returned
   sealed evidence's integrity SHALL be verified against the document's stored `contentHash`
   before the signature is marked `SIGNED`.
5. For every method, `signedAt` SHALL be recorded on completion.
6. A document SHALL transition to `SIGNED` when the last `PENDING` signature becomes `SIGNED`
   (Property 16); the transition SHALL be a single consistent operation.

### Requirement 7 — DocumentMedia (evidence and attachments)

**User Story:** As an auditor, I want every uploaded scan or captured signature image stored with
its metadata, so that signed documents have verifiable evidence.

#### Acceptance Criteria

1. The system SHALL define `DocumentMedia` with: `document` (FK), `fileName`, `contentType`,
   `sizeBytes`, `storageUri`, `kind` (`SCAN`/`TABLET_INITIAL`/`RENDERED_BODY`/`ATTACHMENT`),
   `uploadedBy` (user FK), `uploadedAt`, project-scoped via `document.project.id`.
2. Media SHALL be stored in object storage (FOR-12) with only metadata in the DB (consistent with
   `RoomMedia`/`ProjectMedia`).
3. Uploads SHALL validate content type and size; disallowed types SHALL be rejected with a clear
   error.
4. Deleting a `DocumentMedia` that is the completion evidence of a `SIGNED` signature SHALL be
   rejected.

### Requirement 8 — ABAC SIGNABLE_DOCUMENTS resource and role matrix

**User Story:** As a security owner, I want document access governed by the project ABAC matrix,
so that each role sees and does only what it should within its projects.

#### Acceptance Criteria

1. The system SHALL add a project-scoped `SIGNABLE_DOCUMENTS` resource wired per the
   `entity-creation-rules` checklist: seed the resource row (idempotent changeset), seed the ADMIN
   role-matrix grant, annotate the concrete controller with `@PermissionResource("SIGNABLE_DOCUMENTS")`,
   and implement `getProjectIdPath()` on the service.
2. The module SHALL introduce **no new ABAC action/operation**. All access SHALL map onto the
   standard CRUD operations (`CREATE`/`READ`/`UPDATE`/`DELETE`). In particular, **signing is an
   `UPDATE`** of the document (the signer updates their own `DocumentSignature` on it): the CLIENT
   "sign" capability is granted purely as `UPDATE(own)`, not a bespoke `sign`/`approve` action.
3. The role matrix SHALL be (standard CRUD only):

   | Resource | ADMIN | MANAGER | FOREMAN | WORKER | FINANCIER | CLIENT |
   |----------|-------|---------|---------|--------|-----------|--------|
   | SIGNABLE_DOCUMENTS | CRUD | CRUD(own) | CRU(own) | R(own) | R(own) | RU(own) |

4. The CLIENT `UPDATE(own)` grant SHALL be constrained **server-side** so a CLIENT may only
   complete/decline **their own** `DocumentSignature` on a document within their project; the same
   grant SHALL NOT allow a CLIENT to create a document, edit its body, request signatures, void, or
   delete — those write paths SHALL be rejected for CLIENT even though they are nominally `UPDATE`,
   via an operation-level guard in the service (an authorization check beyond the coarse CRUD bit).
5. `CREATE` / body generation / template binding / request-signatures / `void` SHALL be
   MANAGER/ADMIN operations (FOREMAN additionally `CREATE`/`UPDATE` for the protocol / acceptance /
   defect-form / works-manager-statement / key-handover types it owns). These all map to
   `CREATE`/`UPDATE`/`DELETE` — no new action.
6. Every guarded endpoint SHALL carry a matching `@PermissionOperation` (or method-level
   `@RequiresPermission`) so startup validation (`PermissionAnnotationValidator`) passes.
7. The `SignableDocumentType` reference and `DocumentTemplate` management (incl. the template
   editor, Requirement 3a) SHALL be governed by an **admin-only** grant on the relevant global
   reference resource(s), not project-scoped, and SHALL likewise use only standard CRUD operations.

### Requirement 9 — In-workspace "Document signing" tab

**User Story:** As a manager, I want a tab in the project workspace to generate, send, sign, and
download project documents, so that signing lives inside the project context.

#### Acceptance Criteria

1. The system SHALL render a project-workspace tab (keyed e.g. `documentSigning`, label "Podpisanie
   dokumentów / Подписание документов") listing the project's documents with type, status, signing
   progress, and created date.
2. The tab SHALL let an authorized user: create a document (pick type + template locale), generate
   and edit its DRAFT body (in the Requirement 3a editor), request signatures (pick signers,
   method, level) — which **freezes the immutable PDF** (Requirement 1.5) — fill form fields, sign
   (per method), decline (with reason), upload a scan, capture a tablet *parafka*, download the
   current artifact, and void.
3. Actions SHALL be shown only when the current user's ABAC permissions allow them (menu/action
   visibility consistent with FOR-03-07), using the standard CRUD operations (Requirement 8 — no
   new action).
4. The tab SHALL reflect per-signer progress and the overall document status, and SHALL update
   after each action.
5. The signing surface (server actions + components, including the Requirement 3a template editor)
   SHALL be designed for reuse by the FOR-09 client portal and FOR-06 (amendments/acceptances)
   without a server-side rewrite.
6. The CLIENT-facing view in this tab SHALL expose only read + the CLIENT's own-signature
   `UPDATE` (sign / decline / fill their form fields) and SHALL NOT expose costs, estimate, or
   margins (consistent with FOR-05-07 confidentiality).

### Requirement 10 — Contract-signed → project activation hand-off

**User Story:** As a manager, I want signing the contract to make the project ready to move into
execution, so that the design→execution boundary is driven by a real signature.

#### Acceptance Criteria

1. WHEN a `SignableDocument` of a contract type (`CONTRACT_*`) reaches `SIGNED` on a project whose
   offer is `APPROVED` THEN the project SHALL become **eligible** for `ProjectStatus` `DRAFT →
   ACTIVE`.
2. This spec SHALL perform the eligibility signaling / transition hook; it SHALL NOT re-implement
   the estimate service-lock (`DraftGate`/`EstimateStatus`) or the `OfferPriceSnapshot` freeze —
   those remain owned by FOR-05-07 / FOR-05-13 and are consumed here.
3. A non-contract document reaching `SIGNED` SHALL NOT trigger any project-status transition.
4. The hand-off SHALL be idempotent (re-signaling an already-active project SHALL be a no-op).

### Requirement 11 — Notifications (via the generic notification service)

**User Story:** As a participant, I want to be notified when a document needs my signature or when
its signing status changes, so that signing doesn't stall.

#### Acceptance Criteria

1. The module SHALL emit notifications ONLY through the existing generic notification service
   (FOR-05-07 Requirement 13); it SHALL NOT persist or deliver notifications itself. Only the
   in-app (DB) channel is in scope; email/SMS/Telegram are future work.
2. The module SHALL emit the following notification types, each carrying a **deep-link** to the
   document in the signing tab:

   | Type (i18n key) | Recipient | Trigger |
   |-----------------|-----------|---------|
   | `DOCUMENT_SENT_FOR_SIGNING` | each designated signer | manager requests signatures |
   | `DOCUMENT_SIGNED_BY_PARTY` | document owner (creator/manager) | a signer completes their signature |
   | `DOCUMENT_FULLY_SIGNED` | document owner + all signers | the last signature completes (document → `SIGNED`) |
   | `DOCUMENT_SIGNING_DECLINED` | document owner (creator/manager) | a signer declines |
   | `DOCUMENT_VOIDED` | all still-`PENDING` signers | manager voids a `PENDING_SIGNATURES` document |

3. Each notification SHALL identify the document (type + title/id) in its message and SHALL be
   clickable (it always carries a deep-link).
4. New notification type codes SHALL be added as i18n keys (PL + RU) per Requirement 12.
5. No notification SHALL be emitted for pure `DRAFT`-stage edits (create/generate/regenerate)
   before signatures are requested.

### Requirement 12 — Internationalization (entity + UI, PL/RU completeness)

**User Story:** As a Polish or Russian speaking user, I want every label and message in my
language, so that the signing surface is fully localized.

#### Acceptance Criteria

1. **Backend i18n fields:** the ONLY backend i18n entity fields SHALL be
   `SignableDocumentType.namePL` and `SignableDocumentType.nameRU`. All other classifiers
   (`status`, `SignatureMethod`, `SignatureLevel`, `DocumentMedia.kind`,
   `DocumentSignature.status`) SHALL be enums localized on the frontend (no DB i18n columns).
2. **Frontend i18n elements:** PL and RU values SHALL be provided for: the tab label; document-type
   names; status labels (`DRAFT`/`PENDING_SIGNATURES`/`SIGNED`/`VOID`); signing-method labels
   (print & sign, online, podpis.gov.pl, tablet *parafka*); level labels (SES/AdES/QES); every
   action (create, generate, edit body, request signatures, sign, decline, upload scan, capture
   *parafka*, download, void, fill form field); signer-list and progress labels; the **template
   editor chrome** (toolbar, placeholder/form-field pickers, preview/test-merge, version/activate
   controls, dialogs — Requirement 3a); validation/error messages; and the five notification type
   labels of Requirement 11.
3. Display SHALL use `namePL` with PL fallback when a locale value is missing (entity side).
4. Every i18n key introduced by this module SHALL have a **non-empty** value in BOTH `pl.json` and
   `ru.json`; a key missing from either locale, or present with an empty value, SHALL be treated as
   a defect (verified by a locale-completeness check).

### Requirement 13 — API surface

**User Story:** As a frontend developer, I want a clear REST surface for the module, so that the
workspace tab and future portals integrate consistently.

#### Acceptance Criteria

1. The system SHALL expose (parent design §6, project-scoped, guarded by `SIGNABLE_DOCUMENTS`):
   - `GET /api/signable-documents?projectId={id}` — list a project's documents.
   - `GET /api/signable-documents/{id}` — read one (with signatures + media summary).
   - `POST /api/signable-documents` — create `{projectId, documentTypeId, templateLocale?, title?, sourceRef?}` → `DRAFT`.
   - `POST /api/signable-documents/{id}/generate` — render the DRAFT body by template merge → sets `documentUri` + `contentHash` (DRAFT only).
   - `PUT /api/signable-documents/{id}/body` — save edited DRAFT body from the template editor (DRAFT only); recomputes `contentHash`.
   - `POST /api/signable-documents/{id}/request-signatures` — `{method, level?, signers[]}` → **freezes the immutable PDF** + `contentHash`, moves to `PENDING_SIGNATURES`.
   - `PUT /api/signable-documents/{id}/form-fields` — set/fill form-field values (permitted while `PENDING_SIGNATURES`; CLIENT limited to own fields).
   - `POST /api/signable-documents/{id}/sign` — complete the caller's `ONLINE`/`PODPIS_GOV_PL` signature via provider (an `UPDATE`).
   - `POST /api/signable-documents/{id}/sign-tablet` — multipart: captured *parafka* image → `DocumentMedia` (TABLET_INITIALS) (an `UPDATE`).
   - `POST /api/signable-documents/{id}/media` — multipart: upload scan of wet-ink signed doc → `DocumentMedia` (PRINT) (an `UPDATE`).
   - `POST /api/signable-documents/{id}/decline` — `{reason}` → the caller's signature `DECLINED` (an `UPDATE`).
   - `POST /api/signable-documents/{id}/void` — move DRAFT/PENDING → `VOID`.
   - `GET /api/signable-documents/{id}/document` — download the current artifact (range-capable).
   - `POST /api/signatures/callback` — provider webhook (stub) for `ONLINE`/`PODPIS_GOV_PL`.
   - Admin-only (global reference, standard CRUD): `SignableDocumentType` CRUD + activate/deactivate; `DocumentTemplate` CRUD, `.docx` import, body edit (template editor), version + activate/deactivate, test-merge preview.
2. All write endpoints SHALL validate the lifecycle preconditions of Requirements 1–7 and return
   clear errors on violation; all SHALL map to standard CRUD ABAC operations (Requirement 8).
3. The client-reachable responses SHALL never expose cost/estimate/margin data (FOR-05-07
   confidentiality invariant).

### Requirement 14 — Reusability and non-coupling

**User Story:** As a platform owner, I want the signing module usable by any feature, so that we
build one engine instead of per-feature signing.

#### Acceptance Criteria

1. The domain, services, and API SHALL NOT depend on the Offer or any single caller; the only
   coupling SHALL be the project scope and the (optional) `sourceRef`.
2. Creating a document of any seeded type on any project SHALL work through the same create →
   generate → request-signatures → sign path.
3. FOR-06 (amendments, acceptances, key handover) and FOR-09 (client portal) SHALL be able to
   reuse the server actions and the signing components without modifying this module's core.
4. Adding a new document type + template SHALL require only a new type seed + template upload +
   field-mapping entries, with no change to the lifecycle or signing code.

---

## Non-Functional / Cross-Cutting

1. **Security:** all endpoints project-scoped and ABAC-guarded using standard CRUD only (no new
   action); CLIENT confined to read + own-signature `UPDATE`; no cost/estimate/margin leakage;
   `contentHash` integrity enforced for provider-sealed methods; evidence required for
   `PRINT`/`TABLET_INITIALS`.
2. **Auditability:** creation, generation, signature requests, each signature/decline, and void
   SHALL be audit-logged (reuse the platform audit pattern).
3. **Idempotency:** seed changesets guarded with `onFail="MARK_RAN"`; the activation hand-off
   idempotent.
4. **Integrity invariant (testable):** a `SignableDocument` is `SIGNED` iff all its signatures are
   `SIGNED` (parent design Property 16) — asserted by a property test.
5. **Immutable-PDF invariant (testable):** once `PENDING_SIGNATURES`, the frozen PDF body is
   byte-immutable; only form-field values and signatures/evidence may be added; any body-text edit
   is rejected — asserted by a test.
6. **Storage:** large artifacts and evidence in object storage (FOR-12), metadata in DB.
7. **i18n completeness:** PL + RU non-empty for every introduced key (Requirement 12.4).

## Resolved product decisions

- **Document body format.** The DRAFT body is an editable rich document (template-editor surface);
  on `request-signatures` the system **freezes an immutable PDF** that becomes the canonical,
  hashed signing artifact. After the freeze only form-field entries and signatures may be added
  (Requirements 1.5, 3.6).
- **Type / template lifecycle.** Types and templates are **deactivated, never deleted**
  (Requirements 2.5, 3a.1).
- **ABAC.** No new action is introduced; signing is an `UPDATE` (Requirement 8).
- **Stage ownership.** Signing lives in the project **design (`DRAFT`) stage**; the same engine is
  reused for **amendments** in the working (`ACTIVE`) project through FOR-06 (Introduction).

## Open Questions (to resolve in design)

1. Executor company requisites source: a single app-config constant vs. a small `CompanyProfile`
   reference entity (several templates need full requisites: name, address, NIP, REGON,
   representative).
2. Template storage/upload mechanics: seeded from `docs/templates/` at migration time vs.
   admin-uploaded/edited at runtime vs. both (the template editor implies at least runtime edit).
3. Signer identity for CLIENT when a project has multiple CLIENT members (sign-any vs. sign-all).
4. Embedded editor technology choice (which rich-text/doc editor library produces a reliable PDF
   freeze with form fields) — an implementation decision for design.
