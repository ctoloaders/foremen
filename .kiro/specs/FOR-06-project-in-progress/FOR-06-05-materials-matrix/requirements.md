# Requirements Document

## Introduction

This spec (**FOR-06-05-materials-matrix**) is the **materials plan-vs-fact** child of the FOR-06
"project in progress" (execution stage) parent. On an `ACTIVE` (or `COMPLETED`) project it delivers a
**read-only materials matrix** that puts the project's **planned materials** (the FOR-05-05b list of
concrete materials, with their reserve-adjusted quantities) **side by side with the actual purchases**
(the FOR-06-04 reconciled procurement lines) and derives, **per planned material**, a **fulfilment
percentage (% выборки / stopień realizacji)** that aggregates **every** purchase line linked to that
material — including analog substitutes bought under different products, different manufacturers, and
across all match tiers.

The feature answers a single operational question for the manager / financier: *for each material we
planned, how much of it has actually been bought so far, counting all the analogs and substitutes we
linked to it?* It is a **pure projection** over two existing read models (the FOR-05-05b materials
list on the plan side, the FOR-06-04 reconciled purchase lines on the fact side); it introduces **no
new managed entity, no new ABAC resource, and no new dictionary**, and emits **no notifications**.

### The headline metric — money-based % выборки, aggregating analogs across all tiers

The core design decision, confirmed with the product owner, is how fulfilment is measured:

- **Fact is aggregated per planned material, over every linked purchase line.** For a planned
  material row, the fact side gathers **all** reconciled procurement lines whose `matchedMaterialId`
  points at that material — both `AUTO` and `MANUAL_MATCHED`, and **regardless of match tier**
  (`EXACT` 100 % / `MANUFACTURER_CATEGORY` 80 % / `CATEGORY` 50 %). A line counts in **full** once it
  is linked; the tier is shown for transparency but does **not** scale the contribution. So three
  different products (different manufacturers, different exact names) all linked to one planned
  material **sum together** toward that material's fulfilment and the row can reach — or exceed —
  100 %.
- **The headline % is money-based.** `% выборки = Σ(linked line `lineTotal`) ÷ (planned brutto for the
  material)`. Money always aggregates cleanly across analogs regardless of their units, so it is the
  well-defined primary fulfilment number.
- **Quantity is shown as secondary, unit-aware information.** The fact quantity is summed **only
  across linked lines whose OCR `unitHint` matches the planned material's norm unit**; lines with a
  foreign/mismatched unit are **flagged** (a unit-mismatch indicator and count) and **excluded** from
  the quantity sum (they still count in the money %). There is no automatic unit conversion.
- **The real percentage is uncapped and over-purchase is highlighted.** The fulfilment value is the
  **raw** ratio (e.g. `118 %`); a fill indicator is capped at 100 % for the bar, but the real figure
  is shown as a secondary number and a row above 100 % is visually flagged as over-purchased.

### A read-only projection — no new entity, no new resource, no new dictionary

This spec is deliberately small and additive-free on the schema side. It does **not** introduce a
managed entity, an ABAC resource, or a dictionary, so the `.kiro/steering/entity-creation-rules.md`
checklist produces no resource seed and there is **no Liquibase changeset**:

- it **reads** the FOR-05-05b materials list read model (planned concrete materials, their norm unit,
  net/brutto unit price, reserve-adjusted effective quantity, branch, category) as the plan side;
- it **reads** the FOR-06-04 reconciled procurement lines (`matchedMaterialId`, `matchTier`,
  `matchStatus`, `quantity`, `unitHint`, `lineTotal`) as the fact side;
- it **rides** the existing `PURCHASES` ABAC resource (seeded by FOR-06-03, changeset 169) — the
  matrix is a `READ` surface — while the plan-side figures come from the FOR-05-05b `ESTIMATE`-guarded
  read model, which the same roles can read;
- it **reuses** the FOR-06-00 `materials` execution tab slot (reserved on `PURCHASES`/READ, pointing
  at `PlaceholderTab`), swapping only the tab's panel.

### Scope boundaries

- **In scope:**
  - A **project-scoped, read-only materials-matrix read model** combining the FOR-05-05b planned
    materials with the FOR-06-04 reconciled purchases, producing, **per planned material**: the
    planned quantity (reserve-adjusted effective) + planned brutto, the aggregated fact quantity
    (same-unit lines only) + fact brutto (all linked lines), the **money-based % выборки** (uncapped),
    a unit-mismatch flag + count, and the list of linked lines (for the per-material detail).
  - **Construction / finishing tabs** and **category groupings** within each tab, mirroring the
    FOR-05-05b branch/category structure.
  - **Per-branch and project-wide dashboard totals** (planned brutto, fact brutto, aggregate % выборки).
  - A **per-material detail** view listing every linked purchase line (source document, raw name,
    manufacturer, quantity + unit, tier, line total) with the unit-mismatch lines marked.
  - The swap of the FOR-06-00 `materials` execution tab from `PlaceholderTab` to the real
    materials-matrix panel, without reshaping the `WORKSPACE_TABS` contract.
  - PL + RU localization of every new user-facing string, plus the spec's own tests + `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **Document intake, OCR, auto-match** (FOR-06-03) and **reconciliation** — manual re-link / clear /
    classify (FOR-06-04). This spec **reads** the reconciled lines FOR-06-04 produces; it never
    changes a line's match.
  - **Editing the planned materials, reserves, chosen products, or prices** (FOR-05-05 / FOR-05-05b).
    The plan side is read verbatim.
  - **Unplanned-expense accounting** — lines classified with an `UnplannedExpenseCategory` (FOR-06-04)
    have no `matchedMaterialId`, so they never appear against a planned material; they are **excluded**
    from the matrix (the matrix is plan-vs-fact for *planned* materials only).
  - **Automatic unit conversion** between a purchase line's `unitHint` and the planned norm unit. A
    mismatch is flagged and the line is excluded from the quantity sum (money % is unaffected).
  - **The margin matrix** (FOR-06-06) and **client payments** (FOR-06-07).
  - **Defining a new ABAC resource / dictionary / managed entity** — none is introduced; **no Liquibase
    changeset.**
  - **Notifications** — none are emitted (see Requirement 7).
  - **Changing `stageOf` / `resolveWorkspaceTabs` / the `WORKSPACE_TABS` contract shape** — the
    `materials` tab slot on `PURCHASES`/READ was reserved by FOR-06-00; this spec swaps the panel only.

### Relationship to existing specs (source of truth)

- **FOR-05-05b-list-of-materials** — owns the planned concrete-material list read model: one row per
  distinct `(branch, concreteMaterialId)`, each with its norm unit, net/brutto unit price, the
  reserve-then-ceiling **effective quantity**, branch (construction / finishing), and category. This
  spec reads that read model as the **plan side** (the denominator of % выборки).
- **FOR-06-04-purchases-list** — owns the reconciled procurement lines: each line's
  `matchedMaterialId` (soft FK to a concrete material), `matchTier`, `matchStatus`
  (`AUTO`/`UNMATCHED`/`MANUAL_MATCHED`/`MANUAL_UNMATCHED`), `quantity`, `unitHint`, `lineTotal`,
  `rawName`, `manufacturerHint`, and the source document. This spec reads those lines as the **fact
  side**, aggregating every line with a non-null `matchedMaterialId` per planned material.
- **FOR-06-03-procurement-ocr** — owns the `ProcurementDocument` / `ProcurementLine` entities, the
  `PURCHASES` ABAC resource (seeded, changeset 169), and the `PlannedMaterialCandidateProvider`. This
  spec rides the `PURCHASES` resource (READ) and reuses the candidate provider's concrete-material
  resolution for the planned-material universe.
- **FOR-06-00-execution-foundation** — reserved the `materials` execution tab on `PURCHASES`/READ
  (pointing at `PlaceholderTab`). This spec swaps that tab's panel.
- **FOR-04-17-construction-materials** — the material model (`producer` = manufacturer, material
  `type` = category, `nameRU`/`namePL`) whose localized name/category is shown, read with the
  `ru → pl → code` fallback.
- **FOR-05-07-offer-approval Requirement 13** — the generic in-app `Notification_Service`. This spec
  is **not** a consumer (Requirement 7): it emits no notifications.
- **`.kiro/steering/entity-creation-rules.md`** — because this spec introduces **no** new managed
  entity, resource, or dictionary (it is a read-only projection over two existing read models), the
  new-entity checklist adds nothing; there is **no Liquibase changeset**.

## Glossary

- **Planned_Material** (reused, FOR-05-05b) — a `Concrete_Material` chosen on the project's estimate,
  as projected by the FOR-05-05b materials list: `materialId`, localized name, `branch`
  (construction / finishing), category, norm `unit`, `netUnitPrice` / `bruttoUnitPrice`, and the
  **effective** (reserve-then-ceiling) planned quantity and planned brutto total. The matrix's plan
  side and the **denominator** of % выборки.
- **Reconciled_Line** (reused, FOR-06-04) — a `ProcurementLine` carrying `matchedMaterialId`
  (nullable soft FK to a `Concrete_Material`), `matchTier`, `matchStatus`, `quantity`, `unitHint`,
  `lineTotal`, `rawName`, `manufacturerHint`, and its source document. A line is a **fact
  contribution** to a planned material iff its `matchedMaterialId` is that material (and `matchStatus`
  is `AUTO` or `MANUAL_MATCHED` — i.e. a matched, not cleared, line).
- **Linked_Lines(material)** — the set of all `Reconciled_Line`s whose `matchedMaterialId` equals that
  planned material, regardless of `matchTier` or which concrete product/manufacturer they name. The
  analog-aggregation set.
- **Fact_Brutto(material)** — `Σ lineTotal` over `Linked_Lines(material)`. The % выборки numerator.
- **Fact_Quantity(material)** — `Σ quantity` over the subset of `Linked_Lines(material)` whose
  `unitHint` matches the planned norm unit (case/space-insensitive compare). Lines with a foreign unit
  are excluded from this sum and counted in `Unit_Mismatch_Count`.
- **Unit_Mismatch_Count(material)** — the number of `Linked_Lines(material)` whose `unitHint` does
  **not** match the planned norm unit (excluded from `Fact_Quantity`, included in `Fact_Brutto`).
- **Fulfilment_Percent / % выборки** — the money-based ratio `Fact_Brutto(material) ÷
  planned_brutto(material) × 100`, **uncapped** (may exceed 100 %); `0` when nothing is linked; `null`
  (rendered `—`) when the planned brutto is null/zero (no meaningful denominator).
- **Fill_Indicator** — the display bar, `min(Fulfilment_Percent, 100)`; a row whose
  `Fulfilment_Percent > 100` is flagged **over-purchased** and shows the real uncapped figure as a
  secondary number.
- **Materials_Matrix** — the project-wide read model: construction / finishing tabs, category groups,
  one row per planned material with its plan quantity + brutto, fact quantity (+ unit-mismatch flag) +
  brutto, % выборки, linked-line count, and the per-material detail (the linked lines).

## Requirements

### Requirement 1 — Materials-matrix read model (plan × fact per planned material)

**User Story:** As a manager or financier, I want, for every material we planned, to see how much has
actually been purchased against it — counting all analogs and substitutes we linked — so I know our
real procurement progress.

#### Acceptance Criteria

1. THE SYSTEM SHALL expose a **project-scoped, read-only** materials-matrix read model whose rows are
   exactly the FOR-05-05b **planned materials** (one row per distinct `(branch, concreteMaterialId)`),
   each row carrying the planned data (localized material name, branch, category, norm unit,
   net/brutto unit price, the reserve-adjusted **effective planned quantity**, and the **planned
   brutto total**) read verbatim from the FOR-05-05b read model.
2. FOR each planned material row THE SYSTEM SHALL compute the **fact** side by aggregating **every**
   reconciled procurement line whose `matchedMaterialId` equals that material and whose `matchStatus`
   is `AUTO` or `MANUAL_MATCHED` (a matched line), **regardless of `matchTier`** — an `EXACT` (100 %),
   `MANUFACTURER_CATEGORY` (80 %), and `CATEGORY` (50 %) line each contribute in **full**; the tier
   never scales the contribution.
3. THE **`Fact_Brutto`** of a row SHALL be `Σ lineTotal` over its linked lines, and the
   **`Fulfilment_Percent` (% выборки)** SHALL be the money-based ratio
   `Fact_Brutto ÷ planned_brutto × 100`, **uncapped** (it MAY exceed 100 %).
4. WHERE a planned material has **no** linked lines THE row's `Fact_Brutto` SHALL be `0` and its
   `Fulfilment_Percent` SHALL be `0`; WHERE the planned brutto is `null` or `0` THE
   `Fulfilment_Percent` SHALL be `null` (rendered `—`), never a divide-by-zero.
5. THE read model SHALL include, per row, the **count of linked lines** and the **list of linked
   lines** (for the per-material detail of Requirement 4), and the row's `matchStatus`/`matchTier`
   mix SHALL be preserved per line for transparency (not aggregated away).
6. A line that is **unmatched** (`matchStatus` `UNMATCHED` / `MANUAL_UNMATCHED`, `matchedMaterialId`
   null) — including an `UnplannedExpenseCategory`-classified line — SHALL contribute to **no**
   planned material row (the matrix is plan-vs-fact for planned materials only).

### Requirement 2 — Fact quantity and unit-mismatch handling (secondary, unit-aware)

**User Story:** As a financier, I want to see the purchased quantity alongside the money figure, but
only summed where the units actually match the plan, so the quantity number is not misleading.

#### Acceptance Criteria

1. THE **`Fact_Quantity`** of a row SHALL be `Σ quantity` over **only** the linked lines whose OCR
   `unitHint` matches the planned material's **norm unit** (compared case- and whitespace-insensitive);
   a line with a foreign/missing unit SHALL be **excluded** from this sum.
2. THE SYSTEM SHALL compute a **`Unit_Mismatch_Count`** per row — the number of linked lines whose
   `unitHint` does not match the norm unit — and a boolean **unit-mismatch flag** (`count > 0`); those
   lines SHALL still be counted in `Fact_Brutto` / `Fulfilment_Percent` (money is unaffected by unit).
3. THE SYSTEM SHALL perform **no automatic unit conversion**; the quantity side is informational and
   the money-based `Fulfilment_Percent` (Requirement 1.3) is the authoritative fulfilment metric.
4. WHERE every linked line matches the norm unit THE `Unit_Mismatch_Count` SHALL be `0` and the flag
   SHALL be false; WHERE a row has no linked lines the `Fact_Quantity` SHALL be `0` and the flag false.

### Requirement 3 — Branch tabs, category groups, and dashboard totals

**User Story:** As a manager, I want the matrix split into construction / finishing and grouped by
category with summary totals, so I can read it the way the kosztorys is structured.

#### Acceptance Criteria

1. THE read model SHALL partition rows by **branch** (construction / finishing), matching the
   FOR-05-05b split, and within each branch SHALL group rows by **category** (the material `type`,
   localized `ru → pl → code`), preserving the FOR-05-05b ordering.
2. THE read model SHALL provide **per-branch** dashboard totals — the branch's planned brutto, fact
   brutto, and the aggregate % выборки (`Σ fact brutto ÷ Σ planned brutto × 100`, uncapped) — and a
   **project-wide grand total** of the same three figures; the grand total SHALL equal the sum of the
   per-branch totals.
3. A planned material with a `null` planned brutto SHALL be shown (quantities/linked lines visible)
   but SHALL be **excluded** from the money totals and from any aggregate % выборки denominator (never
   treated as zero-priced).

### Requirement 4 — Over-purchase highlighting and per-material detail

**User Story:** As a financier, I want to spot materials we have over-bought and drill into exactly
which purchases were linked to a planned material, so I can audit the fulfilment number.

#### Acceptance Criteria

1. THE SYSTEM SHALL expose, per row, both the **`Fill_Indicator`** (`min(Fulfilment_Percent, 100)`,
   for the progress bar) and the **real uncapped `Fulfilment_Percent`** (the secondary number); a row
   whose `Fulfilment_Percent > 100` SHALL carry an **over-purchased** flag.
2. THE per-material **detail** SHALL list every linked line with its source document (id, supplier,
   uploaded time), `rawName`, `manufacturerHint`, `quantity` + `unitHint`, `matchTier` (+ its derived
   percent for display), and `lineTotal`, with the unit-mismatch lines visibly marked (Requirement 2).
3. THE detail SHALL make clear that **analog** lines (different products/manufacturers linked to the
   same planned material) all count toward the row's fulfilment, by simply listing them together under
   the one planned material.

### Requirement 5 — Materials tab and matrix UI (frontend)

**User Story:** As a manager or financier, I want a Materials tab on the execution-stage project
workspace showing the plan-vs-fact matrix, so I can monitor procurement fulfilment.

#### Acceptance Criteria

1. THE SYSTEM SHALL render a **read-only** materials-matrix panel on the execution-stage `materials`
   tab, with construction / finishing tabs, category-grouped rows, and per-row: planned quantity +
   unit, planned brutto, fact quantity (+ unit-mismatch indicator) + fact brutto, the % выборки fill
   bar (capped) with the real uncapped figure, an over-purchase highlight, and the linked-line count.
2. THE panel SHALL render a **per-branch and project-wide dashboard** (planned brutto, fact brutto,
   aggregate % выборки) above the matrix (Requirement 3.2).
3. THE panel SHALL open a **per-material detail** (Requirement 4.2) listing the linked purchase lines
   with unit-mismatch lines marked and the tier badge shown per line (consistent with FOR-06-04 /
   FOR-06-03 tier badges).
4. THE panel SHALL be **read-only for everyone** — there are no write controls; on a `COMPLETED`
   project it renders identically (history view). The tab is reachable only on `ACTIVE` / `COMPLETED`
   (gated via `PURCHASES`/READ + the FOR-06-00 stage resolver) and unreachable on `DRAFT`.
5. THE panel SHALL render localized loading / empty (no planned materials) / error states and SHALL
   never surface a raw i18n key.

### Requirement 6 — Stage gating and ABAC (reuse PURCHASES)

**User Story:** As a security owner, I want the matrix available only on active/completed projects to
permitted roles, reusing the existing procurement resource.

#### Acceptance Criteria

1. THE SYSTEM SHALL **not** define a new ABAC resource; the matrix read endpoint SHALL be guarded by
   the existing project-scoped `PURCHASES` resource (seeded by FOR-06-03) as a `READ` operation. The
   plan-side figures are read through the FOR-05-05b `ESTIMATE` read model, readable by the same
   roles.
2. THE matrix controller SHALL carry `@PermissionResource("PURCHASES")`, every in-scope handler SHALL
   carry a matching `@PermissionOperation` (or `@RequiresPermission(... READ)`), and the application
   SHALL start without a `PermissionAnnotationValidator` failure.
3. THE backing service SHALL be **project-scoped** (`getProjectIdPath()` resolving from the underlying
   entity to its owning project id), so reads are automatically project-filtered and never return
   another project's data.
4. WHEN a project is `ACTIVE` or `COMPLETED` THEN the matrix SHALL be readable by callers with
   `PURCHASES` READ; WHEN `DRAFT` THEN no materials-matrix surface SHALL be reachable.
5. WHEN the `PURCHASES` resource is seeded (by FOR-06-03) THEN this spec SHALL swap the FOR-06-00
   `materials` execution tab's `lazy` from `PlaceholderTab` to the materials-matrix panel without
   reshaping the `WORKSPACE_TABS` contract.

### Requirement 7 — No notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** user-facing notifications in this spec. The materials matrix is a
   **read-only analytics surface** — it changes no domain state and has no cross-role handoff (a
   manager/financier opens the Materials tab to read fulfilment). Any future notify-on-threshold
   behaviour (e.g. "material over-purchased"), if introduced, SHALL emit via the generic per-recipient
   `Notification_Service` (FOR-05-07 Requirement 13) rather than re-implementing delivery.

### Requirement 8 — Internationalization completeness

#### Acceptance Criteria

1. THE backend SHALL add **no** `nameRU`/`namePL` i18n columns and **no** new persisted entity (the
   matrix is a derived read model); the material name, manufacturer, and category SHALL be read from
   the existing entities with the `ru → pl → code` fallback, adding no new i18n column.
2. THE frontend SHALL add every new user-facing string — the matrix page/panel title, the
   construction / finishing tab labels, the category group headers' framing, the column headers
   (planned qty / unit, planned brutto, fact qty, fact brutto, % выборки, linked count), the % выборки
   / fill / over-purchased labels, the unit-mismatch indicator + tooltip, the dashboard labels, the
   per-material detail labels and the linked-line columns, and the loading / empty / error states — to
   **both** `src/locales/pl.json` and `src/locales/ru.json` with real PL and RU values (no English
   fallback), and the locale-parity test SHALL pass.
3. THE frontend SHALL never surface a raw i18n key to the user. The `workspace.tab.materials` label
   (already seeded by FOR-06-00) SHALL be reused unchanged.

### Requirement 9 — Regression and test artifacts

#### Acceptance Criteria

1. WHEN this spec ships THEN the FOR-05-05b materials list (plan side), the FOR-06-04 reconciled
   purchases list and its reconciliation actions, and the FOR-06-03 OCR / `PURCHASES` resource SHALL
   continue to pass (regression): this spec reads them and changes none.
2. THE spec SHALL ship its own backend unit + property tests (the per-material fact aggregation across
   all tiers and analogs, the money-based % выборки with uncapped over-purchase and the null-brutto
   `—` case, the unit-match quantity sum + mismatch count, the unmatched-line exclusion, and the
   dashboard folds), frontend unit tests (the matrix render with branch tabs / category groups, the
   fill bar + over-purchase highlight, the unit-mismatch indicator, the per-material detail, the
   read-only / `COMPLETED` rendering, resolver fixtures, locale parity), and an API-level
   `test-cases.md` run against the Dockerized app.
