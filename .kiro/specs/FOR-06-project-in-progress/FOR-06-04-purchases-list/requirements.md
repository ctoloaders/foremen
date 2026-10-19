# Requirements Document

## Introduction

This spec (**FOR-06-04-purchases-list**) is the procurement-reconciliation child of the FOR-06
"project in progress" (execution stage) parent. It delivers the **second half of the procurement
domain**: on an `ACTIVE` project, an authorized user (FINANCIER / MANAGER / FOREMAN) works through
the procurement lines that **FOR-06-03-procurement-ocr** extracted and auto-matched, turning the
read-only proposed-match review into an **actionable reconciled purchases list**:

- a **project-wide purchases list** — every extracted `ProcurementLine` across the project's
  procurement documents, each row showing the line against the plan (its matched material + tier) or
  its unplanned-expense classification, filterable by document / match state / unplanned category;
- **manual re-linking** — an authorized user can override a line's auto-match: pick a different
  planned material, or clear the match; the override is recorded as a distinct **manual** match state
  so a later re-process does not silently discard it;
- **unplanned-expense classification** — a line that is not (or should not be) matched to a planned
  material can be left intentionally unmatched and tagged with a category from the FOR-06-00
  `UnplannedExpenseCategory` mini-dictionary (reclamation / unforeseen / materials / fuel / other);
- the swap of the FOR-06-00 `purchases` execution tab from `PlaceholderTab` to the real reconciled
  purchases list panel.

This spec owns **reconciliation**: the manual re-link workflow, the unplanned-expense classification,
and the reconciled purchases-list surface. It does **not** own document intake, OCR, or the automatic
tier matching (all FOR-06-03), and it does **not** own the plan-vs-fact materials matrix aggregation
(FOR-06-05, which reads the reconciled lines this spec produces).

### Reuse, not re-definition

This spec is a **thin additive layer** over FOR-06-03's domain. It:

- **reuses** the `ProcurementDocument` / `ProcurementLine` entities (FOR-06-03), extending the line
  with the manual-override + unplanned-category fields rather than introducing a parallel entity;
- **reuses** the `PURCHASES` ABAC resource (seeded by FOR-06-03) — it adds **no** new resource; the
  reconciliation endpoints are guarded `UPDATE` / `READ` on the existing `PURCHASES` resource;
- **reuses** the FOR-06-00 `UnplannedExpenseCategory` global dictionary (seeded by FOR-06-00,
  changeset 158) for the classification options — it adds **no** new dictionary;
- **reuses** the FOR-05-05b concrete-material candidate projection (through the FOR-06-03
  `PlannedMaterialCandidateProvider`) so a manual re-link can only target a material that is actually
  on the project's estimate;
- **reuses** the FOR-06-00 `purchases` execution tab slot (reserved on `PURCHASES`/READ, pointing at
  `PlaceholderTab`), swapping only the tab's panel.

### The manual-override match state

FOR-06-03 persists each line's `matchStatus` as `AUTO` (set by the auto-match engine at a tier) or
`UNMATCHED` (tier `NONE`). This spec introduces the manual states so that a human decision is durable
and distinguishable from an automatic one:

- **`MANUAL_MATCHED`** — a user has re-linked the line to a chosen planned material (overriding the
  auto-match or matching a previously-`UNMATCHED` line);
- **`MANUAL_UNMATCHED`** — a user has deliberately cleared the match and (optionally) classified the
  line as an unplanned expense.

A line that still carries its automatic result stays `AUTO` / `UNMATCHED` (unchanged by this spec
until a user acts on it). The re-process rule from FOR-06-03 Requirement 8.4 is completed here:
**a re-process replaces only the lines still in an automatic state (`AUTO` / `UNMATCHED`) and
preserves any line a user has manually reconciled (`MANUAL_MATCHED` / `MANUAL_UNMATCHED`).**

### Scope boundaries

- **In scope:**
  - Extending `ProcurementLine` (FOR-06-03) with an `unplannedCategory` FK (nullable, to
    `UnplannedExpenseCategory`) and broadening `matchStatus` to include `MANUAL_MATCHED` /
    `MANUAL_UNMATCHED`, via an additive Liquibase changeset.
  - A **reconciliation service** operation set on the existing `PURCHASES`-guarded surface:
    re-link a line to a chosen planned material, clear a line's match, and classify / re-classify an
    unmatched line with an `UnplannedExpenseCategory` (or clear the classification).
  - The **reconciled purchases-list read model**: the project's procurement lines across all
    documents, each with its source document, raw text, amounts, the resolved matched-material
    localized name + tier + match state, and the unplanned-category localized name where set;
    filterable by document, by match state, and by unplanned category.
  - The completion of the FOR-06-03 **re-process preservation** rule (Requirement 8.4): a re-process
    replaces only `AUTO` / `UNMATCHED` lines and preserves `MANUAL_*` lines.
  - The swap of the FOR-06-00 `purchases` execution tab from `PlaceholderTab` to the real reconciled
    purchases list panel, without reshaping the `WORKSPACE_TABS` contract.
  - PL + RU localization of every new user-facing string, plus the spec's own tests +
    `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **Document intake, OCR, white-background cleanup, and automatic tier matching** (FOR-06-03). This
    spec consumes the documents + lines + auto-matches FOR-06-03 produces.
  - **The materials matrix** (FOR-06-05) — plan-vs-fact aggregation / % выборки over the reconciled
    purchases. FOR-06-05 reads this spec's reconciled lines.
  - **The margin matrix** (FOR-06-06) and **client payments** (FOR-06-07).
  - **Defining a new ABAC resource** — reconciliation rides the existing `PURCHASES` resource
    (FOR-06-03). **No new resource is seeded.**
  - **Defining a new dictionary** — the unplanned-expense categories are the FOR-06-00
    `UnplannedExpenseCategory` dictionary, reused as-is. **No new dictionary is seeded.**
  - **Editing the extracted raw line text / amounts** — the OCR-extracted `rawName`, `quantity`,
    `unitHint`, `unitPrice`, `lineTotal`, and `manufacturerHint` are verbatim intake data; this spec
    reconciles the *match*, not the extracted values.
  - **Notifications** — none are emitted (see Requirement 8).
  - **Changing `stageOf` / `resolveWorkspaceTabs` / the `WORKSPACE_TABS` contract shape** — the
    `purchases` tab slot on `PURCHASES`/READ was reserved by FOR-06-00; this spec swaps the panel
    only.

### Relationship to existing specs (source of truth)

- **FOR-06-03-procurement-ocr** — owns the `ProcurementDocument` / `ProcurementLine` entities, the
  upload + OCR + auto-match pipeline, the `PURCHASES` ABAC resource (seeded, changeset 169), the
  `PlannedMaterialCandidateProvider`, and the `procurement` tab. This spec extends the line entity,
  rides the `PURCHASES` resource, reuses the candidate provider, and completes the re-process
  preservation rule (FOR-06-03 R8.4).
- **FOR-06-00-execution-foundation** — owns the `UnplannedExpenseCategory` global dictionary
  (changesets 157/158, with the five default rows reclamation / unforeseen / materials / fuel /
  other) and reserved the `purchases` execution tab on `PURCHASES`/READ (pointing at
  `PlaceholderTab`, hidden until `PURCHASES` is seeded). This spec reuses that dictionary and swaps
  the `purchases` tab's panel.
- **FOR-05-05-bill-of-materials** / **FOR-05-05b-list-of-materials** — the `EstimateLine` material
  lines and the project's concrete-material list (`materialId`-keyed) that define the valid targets
  for a manual re-link; a re-link can only point at a material on the project's estimate.
- **FOR-04-17-construction-materials** — the material model (`producer` = manufacturer, material
  `type` = category, `nameRU`/`namePL`) whose localized name is shown for a matched line, read with
  the `ru → pl → code` fallback.
- **FOR-05-07-offer-approval Requirement 13** — the generic in-app `Notification_Service`. This spec
  is **not** a consumer (Requirement 8): it emits no notifications.
- **`.kiro/steering/entity-creation-rules.md`** — because this spec introduces **no** new managed
  entity (it extends the FOR-06-03 line and rides the FOR-06-03 `PURCHASES` resource), the new-entity
  checklist does not add a resource seed; the additive column changeset still follows the idempotent
  `MARK_RAN` convention and registers last in `changelog.xml`.

## Glossary

- **Procurement_Line** (reused, FOR-06-03, extended here) — a single extracted purchased item on a
  `ProcurementDocument`: `document` (FK, `ON DELETE CASCADE`), `lineNo`, `rawName`, `quantity`,
  `unitHint`, `unitPrice`, `lineTotal`, `manufacturerHint`, `matchedMaterialId` (nullable soft FK to
  the chosen Concrete_Material), `matchTier`, and `matchStatus`. **This spec adds** `unplannedCategory`
  (nullable FK to `UnplannedExpenseCategory`) and **broadens** `matchStatus` to include the manual
  states. Project-scoped on `document.project.id`.
- **Match_Status** (reused, FOR-06-03, broadened here) — the provenance of a line's match:
  `AUTO` (set by the FOR-06-03 auto-match engine at a tier), `UNMATCHED` (auto-match found nothing,
  tier `NONE`), `MANUAL_MATCHED` (a user re-linked the line to a chosen material), or
  `MANUAL_UNMATCHED` (a user deliberately cleared the match; may carry an unplanned category).
- **Match_Tier** (reused, FOR-06-03) — the confidence tier of the line's match: `EXACT` (100 %),
  `MANUFACTURER_CATEGORY` (80 %), `CATEGORY` (50 %), or `NONE`. On a `MANUAL_MATCHED` line the tier is
  set to `EXACT` (a user confirmation is the highest confidence); on a `MANUAL_UNMATCHED` line the
  tier is `NONE`.
- **Planned_Material** (reused, FOR-06-03 `PlannedMaterialCandidateProvider`) — a `Concrete_Material`
  chosen on the project's estimate (FOR-05-05/05b): the set a manual re-link may target, carrying
  `producer` (manufacturer), material `type` (category), and `nameRU`/`namePL`. A re-link to a
  material **not** in this set is rejected.
- **Unplanned_Expense_Category** (reused, FOR-06-00) — a row of the global `UnplannedExpenseCategory`
  dictionary (`code`, `nameRU`, `namePL`, `active`): reclamation / unforeseen / materials / fuel /
  other. A line's optional classification when it is deliberately not matched to a planned material.
- **Reconciled_Purchases_List** — the project-wide read model: every `ProcurementLine` across the
  project's `ProcurementDocument`s, each carrying its source document, raw text + amounts, the
  resolved matched-material localized name + tier + match state, and the unplanned-category localized
  name where set.
- **Reconciliation** — the set of user actions this spec adds: **re-link** (choose a planned
  material, `matchStatus → MANUAL_MATCHED`), **clear match** (`matchStatus → MANUAL_UNMATCHED`,
  `matchedMaterialId = null`), and **classify / clear classification** (set / clear
  `unplannedCategory` on a `MANUAL_UNMATCHED` or `UNMATCHED` line).

## Requirements

### Requirement 1 — ProcurementLine reconciliation fields

**User Story:** As the system, I want each procurement line to record whether its match is automatic
or a human decision, and its unplanned-expense classification, so reconciliation is durable and
survives re-processing.

#### Acceptance Criteria

1. THE SYSTEM SHALL extend the existing `ProcurementLine` entity (FOR-06-03) with an
   `unplannedCategory` field — a nullable FK to `UnplannedExpenseCategory` (FOR-06-00) — via an
   additive Liquibase changeset following the FK convention, idempotent via a
   `NOT columnExists` + `onFail="MARK_RAN"` guard, registered last in `changelog.xml`.
2. THE SYSTEM SHALL broaden the `ProcurementLine.matchStatus` enum from `{AUTO, UNMATCHED}` to
   `{AUTO, UNMATCHED, MANUAL_MATCHED, MANUAL_UNMATCHED}` without a schema change (the column already
   stores the enum name as a string); existing rows keep their `AUTO` / `UNMATCHED` value.
3. THE `unplannedCategory` FK SHALL be nullable and SHALL be settable only on a line whose
   `matchStatus` is `UNMATCHED` or `MANUAL_UNMATCHED` (a matched line carries no unplanned category);
   the FK SHALL `ON DELETE SET NULL` so deleting a dictionary row clears the classification rather
   than cascading to the line.
4. THE line invariants SHALL hold after any reconciliation action:
   `matchedMaterialId != null ⟺ matchStatus ∈ {AUTO, MANUAL_MATCHED}` and `matchTier != NONE`;
   `matchedMaterialId == null ⟺ matchStatus ∈ {UNMATCHED, MANUAL_UNMATCHED}` and `matchTier == NONE`;
   `unplannedCategory != null ⟹ matchedMaterialId == null`.

### Requirement 2 — Manual re-link to a planned material

**User Story:** As a financier or manager, I want to correct a line's matched material, so a wrong or
missing auto-match reflects the real purchase.

#### Acceptance Criteria

1. WHEN an authorized caller re-links a line to a chosen `Planned_Material` THEN the system SHALL set
   the line's `matchedMaterialId` to that material, set `matchStatus` to `MANUAL_MATCHED`, set
   `matchTier` to `EXACT`, and clear any `unplannedCategory`, writing an audit row.
2. WHEN the chosen material is **not** in the project's `Planned_Material` candidate set (the
   FOR-05-05b concrete-material projection read via the FOR-06-03 `PlannedMaterialCandidateProvider`)
   THEN the re-link SHALL be rejected with `error.purchases.relink.notPlanned`, leaving the line
   unchanged.
3. WHEN an authorized caller clears a line's match THEN the system SHALL set `matchedMaterialId` to
   null, set `matchStatus` to `MANUAL_UNMATCHED`, and set `matchTier` to `NONE`, writing an audit row.
4. THE re-link / clear operations SHALL be project-scoped and gated `UPDATE` on the existing
   `PURCHASES` resource; a caller without a `PURCHASES` UPDATE grant SHALL be rejected.
5. WHEN the line's document belongs to a project that is not `ACTIVE` THEN any reconciliation action
   SHALL be rejected with `error.purchases.stage.notActive` (reads stay allowed on `COMPLETED`).

### Requirement 3 — Unplanned-expense classification

**User Story:** As a financier or foreman, I want to tag a purchase that has no place in the plan
(reclamation, fuel, an unforeseen cost), so it is accounted for without being forced onto a planned
material.

#### Acceptance Criteria

1. WHEN an authorized caller classifies an `UNMATCHED` or `MANUAL_UNMATCHED` line with an
   `UnplannedExpenseCategory` THEN the system SHALL set the line's `unplannedCategory`, set
   `matchStatus` to `MANUAL_UNMATCHED`, ensure `matchedMaterialId` is null and `matchTier` is `NONE`,
   and write an audit row.
2. WHEN the caller clears a line's classification THEN the system SHALL set `unplannedCategory` to
   null, leaving `matchStatus` `MANUAL_UNMATCHED` (the line stays a deliberate non-match).
3. WHEN the caller attempts to classify a **matched** line (`AUTO` or `MANUAL_MATCHED`) THEN the
   action SHALL be rejected with `error.purchases.classify.matchedLine`; a line must first be cleared
   (Requirement 2.3) before it can be classified.
4. WHEN the referenced `UnplannedExpenseCategory` does not exist or is inactive THEN the action SHALL
   be rejected with `error.purchases.classify.unknownCategory`.
5. THE classify / clear-classification operations SHALL be project-scoped and gated `UPDATE` on the
   existing `PURCHASES` resource, and subject to the same `ACTIVE`-stage gate as Requirement 2.5.

### Requirement 4 — Reconciled purchases-list read model

**User Story:** As a financier or manager, I want a single list of every purchased line across the
project's documents, so I can see at a glance what is matched to the plan and what is unplanned.

#### Acceptance Criteria

1. THE SYSTEM SHALL expose a project-scoped read model listing every `ProcurementLine` across the
   project's `ProcurementDocument`s, each row carrying: the source document (id, supplier, uploaded
   time), `lineNo`, `rawName`, `quantity`, `unitHint`, `unitPrice`, `lineTotal`, `manufacturerHint`,
   the resolved matched-material **localized name** (`ru → pl → code`) or null, `matchTier`, the
   derived tier percent (100/80/50/0), `matchStatus`, and the `unplannedCategory` **localized name**
   (`ru → pl → code`) or null.
2. THE read model SHALL be filterable by source document, by `matchStatus` (auto / unmatched /
   manually matched / manually unmatched), and by `unplannedCategory`.
3. THE read model SHALL be project-filtered automatically by the FOR-06-03 `ProjectScopedService`
   plumbing (it never returns lines from a project the caller cannot access) and gated `READ` on the
   existing `PURCHASES` resource.
4. THE list SHALL reflect the current reconciliation state immediately after any re-link / clear /
   classify action (no stale match state).

### Requirement 5 — Re-process preserves manual reconciliation (completes FOR-06-03 R8.4)

**User Story:** As a user, I want re-running OCR on a document to keep the corrections I have already
made, so I do not lose manual reconciliation work.

#### Acceptance Criteria

1. WHEN a FOR-06-03 document is re-processed THEN the system SHALL replace only the lines still in an
   automatic state (`AUTO` or `UNMATCHED`) with the freshly extracted + auto-matched lines, and SHALL
   preserve every line whose `matchStatus` is `MANUAL_MATCHED` or `MANUAL_UNMATCHED` (with its
   `matchedMaterialId` / `unplannedCategory` intact).
2. WHERE a document has no manually reconciled lines THE re-process behaviour SHALL be identical to
   FOR-06-03 (replace all lines).
3. THE preserved-manual rule SHALL be a pure, deterministic partition of the prior line set into
   "preserve" (`MANUAL_*`) and "replace" (`AUTO` / `UNMATCHED`), so it is unit- and property-testable
   independently of the OCR provider.

### Requirement 6 — Purchases tab and reconciliation UI (frontend)

**User Story:** As a financier or manager, I want a Purchases tab showing the reconciled list where I
can re-link and classify lines, so I can reconcile the project's purchases against the plan.

#### Acceptance Criteria

1. THE SYSTEM SHALL render a reconciled purchases-list panel on the execution-stage `purchases` tab,
   listing the project's procurement lines (Requirement 4) with the source document, raw text,
   amounts, the matched-material name + tier badge or the unplanned-category name, and the match-state
   indicator, with filters by document / match state / unplanned category.
2. THE panel SHALL provide, on each line, a **re-link** control (choose a planned material from the
   project's candidate set, or clear the match) and, on an unmatched line, a **classify** control
   (choose an active `UnplannedExpenseCategory`, or clear the classification), reflecting the result
   immediately and showing toasts.
3. THE panel SHALL visually distinguish the four match states (`AUTO` / `UNMATCHED` /
   `MANUAL_MATCHED` / `MANUAL_UNMATCHED`) with localized labels, and SHALL render the tier badge
   (100 % / 80 % / 50 % / no-match) consistently with FOR-06-03.
4. WHEN a caller has only `PURCHASES` READ THEN the panel SHALL be read-only — the re-link and
   classify controls SHALL be hidden — and on a `COMPLETED` project the controls SHALL be hidden for
   everyone (history visible, no reconciliation).
5. THE panel SHALL surface server validation errors (not-planned material, classify-matched-line,
   unknown category, stage) inline and never surface a raw i18n key.

### Requirement 7 — Stage gating and ABAC (reuse PURCHASES)

**User Story:** As a security owner, I want reconciliation available only on active projects to
permitted roles, reusing the existing procurement resource.

#### Acceptance Criteria

1. THE SYSTEM SHALL **not** define a new ABAC resource; the reconciliation endpoints SHALL be guarded
   by the existing project-scoped `PURCHASES` resource (seeded by FOR-06-03), with re-link / clear /
   classify as `UPDATE` and the read model as `READ`.
2. THE reconciliation controller SHALL carry `@PermissionResource("PURCHASES")` (or reuse the
   FOR-06-03 procurement controller), every in-scope handler SHALL carry a matching
   `@PermissionOperation` (or `@RequiresPermission`), and the application SHALL start without a
   `PermissionAnnotationValidator` failure.
3. THE backing service SHALL be project-scoped (`getProjectIdPath()` resolving from `ProcurementLine`
   to its owning project id, e.g. `document.project.id`), so LIST reads are automatically
   project-filtered.
4. WHEN a project is `ACTIVE` THEN reconciliation SHALL be open to callers with `PURCHASES` UPDATE;
   WHEN `COMPLETED` THEN the list SHALL be read-only (no re-link / classify); WHEN `DRAFT` THEN no
   purchases surface SHALL be reachable.
5. WHEN the `PURCHASES` resource is seeded (by FOR-06-03) THEN this spec SHALL swap the FOR-06-00
   `purchases` execution tab's `lazy` from `PlaceholderTab` to the reconciled purchases list panel
   without reshaping the `WORKSPACE_TABS` contract. (The sibling `procurement` tab, also on
   `PURCHASES`, is swapped by FOR-06-03.)

### Requirement 8 — No notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** user-facing notifications in this spec. Reconciliation (re-link,
   clear, classify) is a synchronous authoring action performed by a FINANCIER / MANAGER / FOREMAN
   on a list they already see in the Purchases tab — there is no cross-role handoff. A user discovers
   lines needing reconciliation by opening the Purchases tab. Any future notify-on-handoff behaviour,
   if introduced, SHALL emit via the generic per-recipient `Notification_Service` (FOR-05-07
   Requirement 13) rather than re-implementing delivery.

### Requirement 9 — Internationalization completeness

#### Acceptance Criteria

1. THE backend `ProcurementLine` extension SHALL add **no** `nameRU`/`namePL` i18n columns (it is
   transactional); the matched-material name, manufacturer, material category, and
   unplanned-category name SHALL be read from the existing entities / dictionary with the
   `ru → pl → code` fallback, adding no new i18n column.
2. THE frontend SHALL add every new user-facing string — the `purchases` page/panel title, the
   reconciled-list columns and filters, the match-state labels (`auto` / `unmatched` /
   `manualMatched` / `manualUnmatched`), the re-link control (material picker, clear-match), the
   classify control (category picker, clear-classification), and all success/error toasts — to
   **both** `src/locales/pl.json` and `src/locales/ru.json` with real PL and RU values (no English
   fallback), and the locale-parity test SHALL pass.
3. EVERY `error.purchases.*` message code SHALL have PL + RU messages in the backend message bundle.
4. THE frontend SHALL never surface a raw i18n key to the user. The `workspace.tab.purchases` label
   (already seeded by FOR-06-00) SHALL be reused unchanged.

### Requirement 10 — Audit, regression, and test artifacts

#### Acceptance Criteria

1. WHEN a line is re-linked, cleared, classified, or re-classified THEN an audit row SHALL be written
   via the existing audit mechanism.
2. WHEN this spec ships THEN the FOR-06-03 behaviors not changed here (document upload, OCR,
   auto-matching, the read-only `procurement` tab, the `PURCHASES` resource + matrix) and the
   FOR-06-00 `UnplannedExpenseCategory` dictionary SHALL continue to pass (regression), and a
   re-process of a document with no manual lines SHALL remain FOR-06-03-equivalent (Requirement 5.2).
3. THE spec SHALL ship its own backend unit + property tests (the line invariants of Requirement 1.4
   across every reconciliation action, the not-planned re-link rejection, the classify-matched-line
   rejection, the re-process manual-preservation partition of Requirement 5, and the read-model
   filtering + `ru → pl → code` resolution), frontend unit tests (the reconciled list render, the
   re-link + classify flows, the read-only / `COMPLETED` gating, the match-state labels and tier
   badges, resolver fixtures, locale parity), and an API-level `test-cases.md` run against the
   Dockerized app.
