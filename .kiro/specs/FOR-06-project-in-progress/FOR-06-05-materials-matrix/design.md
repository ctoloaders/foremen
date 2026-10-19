# Design Document: FOR-06-05 — Materials matrix (plan × fact)

## Overview

FOR-06-05 is the **materials plan-vs-fact** child of the FOR-06 "project in progress" (execution
stage) parent and the consumer end of the procurement chain `FOR-06-03 (intake + OCR + auto-match) →
FOR-06-04 (reconciliation) → FOR-06-05 (materials matrix)`. On an `ACTIVE` / `COMPLETED` project it
renders a **read-only materials matrix**: the FOR-05-05b **planned materials** down the rows, their
**actual purchases** aggregated per material on the fact side, and a **money-based % выборки**
(fulfilment) that **sums every reconciled line linked to a planned material — all analogs, all
manufacturers, all tiers**.

This is a **pure projection** over two existing read models. It introduces **no managed entity, no
ABAC resource, no dictionary, and no Liquibase changeset** (the `.kiro/steering/entity-creation-rules.md`
checklist therefore produces nothing): it reads the FOR-05-05b materials list (plan) and the FOR-06-04
reconciled procurement lines (fact), joins them by `concreteMaterialId`, and folds.

### The headline metric (confirmed with the product owner)

- **Fact aggregation per planned material, across all analogs and tiers.** For a row, the fact set is
  **all** reconciled lines with `matchedMaterialId == materialId` and `matchStatus ∈ {AUTO,
  MANUAL_MATCHED}`. The `matchTier` (`EXACT`/`MANUFACTURER_CATEGORY`/`CATEGORY`) is **not** a weight —
  each linked line contributes its **full** `lineTotal`. Three different products linked to one
  planned material sum together (R1.2).
- **Money-based % выборки (headline).** `Fulfilment_Percent = Fact_Brutto ÷ planned_brutto × 100`,
  **uncapped** — always well-defined across analogs regardless of unit (R1.3). This is option **(a)**
  from the requirements clarification.
- **Quantity secondary, unit-aware.** `Fact_Quantity` sums only linked lines whose `unitHint` matches
  the planned norm unit; foreign-unit lines are flagged (`Unit_Mismatch_Count`) and excluded from the
  quantity sum but **kept** in `Fact_Brutto` (R2). No unit conversion.
- **Over-purchase uncapped + highlighted.** `Fill_Indicator = min(pct, 100)` for the bar; the real
  uncapped figure is the secondary number; `pct > 100` ⇒ an **over-purchased** flag (R4.1).

### Ownership boundary — read only

This spec owns the **matrix read model** and its panel. It does **not** own intake / OCR / auto-match
(FOR-06-03), reconciliation (FOR-06-04), or the planned-materials list and reserves (FOR-05-05b). It
changes no match, no line, no plan. An `UnplannedExpenseCategory`-classified line has a null
`matchedMaterialId` and therefore belongs to no planned-material row — it is excluded (R1.6).

### Spans two git repos

Per `.kiro/steering/git-repo-structure.md`:

- **Root repo** — the matrix assembler + service + controller + read-model DTOs/mappers, the backend
  message bundle (if any error code is needed), and this spec. No migration. Committed from the
  workspace root.
- **Frontend repo** (`foremen-frontend/`) — the Materials-matrix panel, the `materials` tab `lazy`
  swap, the API client, and the `pl.json`/`ru.json` keys. Committed from inside `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Notation.** Backend in Java (Spring Boot / JPA / MapStruct / jqwik); frontend in TypeScript/React
(Vite, TanStack Query, the shared `DataTable`, i18next); frontend property tests in fast-check +
Vitest. No Liquibase changeset in this spec.

## Reused mechanisms (not re-implemented)

| Mechanism | Location | Reuse |
|-----------|----------|-------|
| FOR-05-05b materials list read model (`MaterialsListDto`: branch/category, norm unit, net/brutto unit price, **effective planned quantity**, planned brutto) | `foremen-backend/.../service` (`MaterialsListService` / `MaterialsListAssembler`, FOR-05-05b) | The **plan side** — rows, planned quantity, planned brutto, branch/category, localized name. Read verbatim; no second plan formula. |
| FOR-06-04 reconciled line read model (`matchedMaterialId`, `matchTier`, `matchStatus`, `quantity`, `unitHint`, `lineTotal`, `rawName`, `manufacturerHint`, source document) | `foremen-backend/.../service/procurement` (FOR-06-03/04) | The **fact side** — the linked-line set per planned material. |
| `PURCHASES` ABAC resource + role matrix | `database_files/changesets/169-seed-purchases-resource.xml` (FOR-06-03) | The matrix is `READ`; **no new resource seeded**. |
| `PlannedMaterialCandidateProvider` | `foremen-backend/.../service/procurement/` (FOR-06-03) | Resolves a `matchedMaterialId` to its localized material name / branch / category / unit for the fact-side join and the detail. |
| `ProjectScopedService` + `getProjectIdPath()` | `foremen-backend/.../service/ProjectScopedService.java` | The matrix service resolves the project id so reads are auto project-filtered (R6.3). |
| `ru → pl → code` localized-name fallback | existing material/dictionary read convention | Material name, manufacturer, category — no new i18n column (R8.1). |
| `stageOf(status)` / `resolveWorkspaceTabs` / `WORKSPACE_TABS` `materials` slot | `foremen-frontend/.../project-workspace/state/` + registry (FOR-06-00) | Unchanged; only the `materials` tab's `lazy` is swapped (R6.5). |
| `workspace.tab.materials` i18n label | `pl.json` / `ru.json` (FOR-06-00) | Reused unchanged. |
| `TierBadge` (100 / 80 / 50 / no-match) | FOR-06-04 / FOR-06-03 frontend | Reused in the per-material detail. |

## Architecture

```mermaid
graph TD
    subgraph FE["foremen-frontend/ (nested repo)"]
      TAB["project-workspace registry<br/>materials tab lazy: Placeholder → MaterialsMatrixPanel"]
      PANEL["features/project-execution/materials-matrix/MaterialsMatrixPanel.tsx<br/>branch tabs + category groups + dashboard"]
      ROW["MatrixRow.tsx<br/>plan qty/brutto · fact qty/brutto · % выборки fill + uncapped · over-purchase · unit-mismatch"]
      DETAIL["MaterialDetailDrawer.tsx<br/>linked lines (analogs) + TierBadge + mismatch marks"]
      DASH["MatrixDashboard.tsx<br/>per-branch + grand totals, aggregate % выборки"]
      MODEL["state/matrixModel.ts<br/>PURE mirror: pct, fill, mismatch (display only)"]
      API["api/materials-matrix-api.ts<br/>GET matrix read model"]
      L["locales/pl.json + ru.json<br/>materialsMatrix.* (strict parity)"]
    end

    subgraph BE["foremen-backend/ (root repo)  — NO migration"]
      CTRL["MaterialsMatrixController<br/>@PermissionResource(\"PURCHASES\") READ"]
      SVC["MaterialsMatrixService<br/>implements ProjectScopedService"]
      ASM["MaterialsMatrixAssembler (pure)<br/>join plan × fact by materialId, fold % выборки"]
      PLAN(("reused: MaterialsListService (plan side)")):::reuse
      FACT(("reused: reconciled lines (fact side)")):::reuse
      CAND(("reused: PlannedMaterialCandidateProvider")):::reuse
    end

    TAB --> PANEL
    PANEL --> ROW --> DETAIL
    PANEL --> DASH
    ROW --> MODEL
    DASH --> MODEL
    PANEL --> API --> CTRL --> SVC
    SVC --> PLAN
    SVC --> FACT
    SVC --> ASM
    ASM --> CAND
    classDef reuse fill:#eef,stroke:#88a;
```

### Read flow (open the Materials matrix tab)

```mermaid
sequenceDiagram
    participant U as User (Materials tab)
    participant C as MaterialsMatrixController (PURCHASES/READ)
    participant S as MaterialsMatrixService
    participant P as MaterialsListService (FOR-05-05b plan)
    participant F as reconciled lines (FOR-06-04 fact)
    participant A as MaterialsMatrixAssembler (pure)

    U->>C: GET /api/projects/{projectId}/materials-matrix [READ]
    C->>S: getMatrix(projectId)
    S->>P: planned materials (effective qty, planned brutto, branch/category, unit)
    S->>F: reconciled lines with matchedMaterialId != null (AUTO/MANUAL_MATCHED)
    S->>A: assemble(plannedRows, linkedLines)
    A->>A: group lines by matchedMaterialId; per row<br/>Fact_Brutto=Σ lineTotal (all tiers),<br/>Fact_Quantity=Σ qty of same-unit lines,<br/>Unit_Mismatch_Count, pct=factBrutto/plannedBrutto,<br/>fill=min(pct,100), overPurchased=pct>100
    A->>A: per-branch + grand dashboard totals
    A-->>C: MaterialsMatrixDto (branches → categories → rows + dashboards)
    C-->>U: read model (independent of write state)
```

## Data Models

No schema change. The read model is assembled in memory from the two existing read models.

### Fact aggregation (the core fold, per planned material)

For a planned material row `m` with `planned_brutto(m)` and norm `unit(m)`, over
`Linked_Lines(m) = { line : line.matchedMaterialId == m.materialId ∧ line.matchStatus ∈ {AUTO,
MANUAL_MATCHED} }`:

```
Fact_Brutto(m)        = Σ_{line ∈ Linked_Lines(m)}                       line.lineTotal
Fact_Quantity(m)      = Σ_{line ∈ Linked_Lines(m), unitEq(line.unitHint, m.unit)} line.quantity
Unit_Mismatch_Count(m)= | { line ∈ Linked_Lines(m) : ¬unitEq(line.unitHint, m.unit) } |
Fulfilment_Percent(m) = planned_brutto(m) ∈ {null, 0} ? null
                                                       : Fact_Brutto(m) / planned_brutto(m) * 100
Fill_Indicator(m)     = Fulfilment_Percent(m) == null ? null : min(Fulfilment_Percent(m), 100)
overPurchased(m)      = Fulfilment_Percent(m) != null ∧ Fulfilment_Percent(m) > 100
```

where `unitEq(a, b)` compares unit strings **case- and whitespace-insensitively** (`null`/blank
`unitHint` ⇒ mismatch). The tier never appears in these formulas — it is carried per line for the
detail only (R1.2). A line with `matchStatus ∈ {UNMATCHED, MANUAL_UNMATCHED}` or a null
`matchedMaterialId` is never in any `Linked_Lines(m)` (R1.6).

### Dashboard totals (R3.2)

Per branch `b` (and project-wide as the sum of branches), over rows with a non-null planned brutto:

```
planned_brutto(b) = Σ_{m ∈ b, planned_brutto(m) != null} planned_brutto(m)
fact_brutto(b)    = Σ_{m ∈ b, planned_brutto(m) != null} Fact_Brutto(m)
aggregate_pct(b)  = planned_brutto(b) == 0 ? null : fact_brutto(b) / planned_brutto(b) * 100   // uncapped
grand = Σ_b (planned_brutto, fact_brutto); grand aggregate_pct recomputed from grand sums
```

A row with a null planned brutto is shown (quantities/linked lines visible) but excluded from money
totals and the aggregate-% denominator (R3.3) — never treated as zero-priced.

### Read model DTOs (backend, mirrored by the frontend)

```java
public record MatrixLinkedLineDto(
    Long lineId, Long documentId, String supplierName, Instant uploadedAt,
    String rawName, String manufacturerHint,
    BigDecimal quantity, String unitHint,
    MatchTier matchTier, int tierPercent,        // 100/80/50/0 — display only, not a weight
    MatchStatus matchStatus,
    BigDecimal lineTotal,
    boolean unitMismatch) {}                     // ¬unitEq(unitHint, row.unit)

public record MatrixRowDto(
    Long materialId, String materialName,
    ConsumptionBranch branch, String category,   // localized ru→pl→code
    String unit,
    BigDecimal plannedQuantity,                  // FOR-05-05b effective quantity
    BigDecimal plannedBrutto,                    // nullable ⇒ pct null, excluded from totals
    BigDecimal factQuantity,                     // same-unit lines only (R2.1)
    int unitMismatchCount, boolean unitMismatch, // R2.2
    BigDecimal factBrutto,                        // Σ lineTotal, all linked lines (R1.3)
    BigDecimal fulfilmentPercent,                // uncapped; null when plannedBrutto null/0 (R1.4)
    BigDecimal fillIndicator,                    // min(pct,100); null when pct null (R4.1)
    boolean overPurchased,                        // pct > 100 (R4.1)
    int linkedLineCount,
    List<MatrixLinkedLineDto> linkedLines) {}    // R4.2 detail

public record MatrixCategoryGroupDto(String category, List<MatrixRowDto> rows) {}

public record MatrixBranchGroupDto(
    ConsumptionBranch branch,
    List<MatrixCategoryGroupDto> categories,
    BigDecimal plannedBrutto, BigDecimal factBrutto, BigDecimal aggregatePercent) {}  // per-branch dashboard

public record MaterialsMatrixDto(
    Long projectId,
    List<MatrixBranchGroupDto> branches,          // construction, finishing
    BigDecimal grandPlannedBrutto, BigDecimal grandFactBrutto, BigDecimal grandAggregatePercent) {}
```

### Frontend read model (mirrors backend DTOs)

```typescript
export interface MatrixLinkedLine {
  lineId: number; documentId: number; supplierName: string | null; uploadedAt: string
  rawName: string; manufacturerHint: string | null
  quantity: number | null; unitHint: string | null
  matchTier: 'EXACT' | 'MANUFACTURER_CATEGORY' | 'CATEGORY' | 'NONE'; tierPercent: number
  matchStatus: 'AUTO' | 'MANUAL_MATCHED'
  lineTotal: number | null
  unitMismatch: boolean
}
export interface MatrixRow {
  materialId: number; materialName: string
  branch: 'construction' | 'finishing'; category: string
  unit: string | null
  plannedQuantity: number | null; plannedBrutto: number | null
  factQuantity: number; unitMismatchCount: number; unitMismatch: boolean
  factBrutto: number
  fulfilmentPercent: number | null          // uncapped; null ⇒ '—'
  fillIndicator: number | null              // min(pct,100)
  overPurchased: boolean
  linkedLineCount: number; linkedLines: MatrixLinkedLine[]
}
export interface MatrixBranchGroup {
  branch: 'construction' | 'finishing'
  categories: { category: string; rows: MatrixRow[] }[]
  plannedBrutto: number | null; factBrutto: number | null; aggregatePercent: number | null
}
export interface MaterialsMatrixDto {
  projectId: number
  branches: MatrixBranchGroup[]
  grandPlannedBrutto: number | null; grandFactBrutto: number | null; grandAggregatePercent: number | null
}
```

## Components and Interfaces

### Backend components

#### B1 — `MaterialsMatrixAssembler` (pure projection, R1, R2, R3, R4)

A pure, stateless `@Component` that builds `MaterialsMatrixDto` from the FOR-05-05b planned rows plus
the FOR-06-04 linked lines. It performs no I/O beyond reading the passed-in models. It:

- indexes the linked lines by `matchedMaterialId` (only `matchStatus ∈ {AUTO, MANUAL_MATCHED}`), so an
  unmatched / cleared / unplanned line is dropped up front (R1.6);
- per planned row, folds `Fact_Brutto` (Σ `lineTotal`, all tiers, full weight), `Fact_Quantity`
  (same-unit lines only via `unitEq`), `Unit_Mismatch_Count`, `Fulfilment_Percent` (uncapped;
  `null` when planned brutto null/0), `Fill_Indicator` = `min(pct, 100)`, and `overPurchased`;
- groups rows by branch then category (localized, FOR-05-05b order) and computes per-branch + grand
  dashboard totals over non-null-brutto rows (R3).

The **canonical `unitEq`** and the **`fulfilment`** helpers are the single place these rules live
(shared by rows and the dashboard so figures never drift), and are property-tested:

```java
/** Case/whitespace-insensitive unit equality; a blank/null hint never matches. */
static boolean unitEq(String unitHint, String normUnit) {
    if (unitHint == null || unitHint.isBlank() || normUnit == null) return false;
    return unitHint.strip().equalsIgnoreCase(normUnit.strip());
}

/** Money-based % выборки, uncapped; null when there is no meaningful denominator (R1.3, R1.4). */
static BigDecimal fulfilment(BigDecimal factBrutto, BigDecimal plannedBrutto) {
    if (plannedBrutto == null || plannedBrutto.signum() == 0) return null;
    return factBrutto.multiply(HUNDRED).divide(plannedBrutto, 4, RoundingMode.HALF_UP);
}
```

#### B2 — `MaterialsMatrixService` (read model, R1, R6)

A `ProjectScopedService`-backed service owning the single read:

- `getProjectIdPath()` resolves from the backing procurement line to its owning project id (e.g.
  `document.project.id`), so reads are auto project-filtered (R6.3).
- `getMatrix(projectId)` — resolve the FOR-05-05b planned materials (reuse `MaterialsListService` so a
  pre-estimate project returns an empty structure, not 404); load the project's reconciled lines with
  a non-null `matchedMaterialId` and `matchStatus ∈ {AUTO, MANUAL_MATCHED}` (reuse the FOR-06-04 read
  path / query); delegate to `MaterialsMatrixAssembler`. Read-only, lifecycle-independent for the read
  itself; the tab is stage-gated on the frontend + `PURCHASES`/READ (R6.4).
- Emits **no** notifications (R7.1).

#### B3 — `MaterialsMatrixController` (endpoint on the PURCHASES resource, R6)

`@RestController @PermissionResource("PURCHASES")`, fully annotated so `PermissionAnnotationValidator`
classifies it COMPLETE at startup (R6.2):

| Endpoint | Op | Guard |
|----------|-----|------|
| `GET /api/projects/{projectId}/materials-matrix` | matrix read model | READ |

No new ABAC resource / operation / role grant (R6.1). The `PURCHASES` matrix already grants ADMIN /
FINANCIER / MANAGER / FOREMAN READ (and ESTIMATOR READ), so the matrix is readable by the same roles.

### Frontend components

Feature folder `foremen-frontend/src/features/project-execution/materials-matrix/` (sibling of
`purchases`), rendering inside the `materials` execution tab:

- `MaterialsMatrixPanel.tsx` — construction / finishing tabs; within a tab, category-grouped rows in a
  `DataTable`; the dashboard above; localized loading / empty (no planned materials) / error (R5.1,
  R5.5). Read-only — no write controls (R5.4).
- `components/MatrixRow.tsx` — per row: planned quantity + unit, planned brutto; fact quantity with a
  **unit-mismatch indicator** (icon + tooltip + count) when `unitMismatch`; fact brutto; the **%
  выборки fill bar** (`fillIndicator`, capped) with the real uncapped `fulfilmentPercent` as a
  secondary number; an **over-purchased** highlight when `overPurchased`; the linked-line count; a
  disclosure to open the detail (R5.1).
- `components/MaterialDetailDrawer.tsx` — the per-material detail: every linked line (source document,
  raw name, manufacturer, quantity + unit, `TierBadge`, line total), analog lines simply listed
  together under the one planned material, unit-mismatch lines marked (R4.2, R4.3, R5.3).
- `components/MatrixDashboard.tsx` — per-branch + grand totals (planned brutto, fact brutto, aggregate
  % выборки) (R5.2).
- `components/UnitMismatchBadge.tsx` — the localized unit-mismatch indicator + tooltip.
- `state/matrixModel.ts` — a **pure display mirror** (`fulfilment`, `fill`, `unitEq`, dashboard folds)
  so the panel can format consistently with the backend; the authoritative numbers come from the read
  model.
- `api/materials-matrix-api.ts` — `getMatrix(projectId)` via the shared `apiRequest`.
- `schemas/`, `types/`, `__tests__/`.

**Tab wiring.** Swap the `materials` entry's `lazy` from `PlaceholderTab` to `MaterialsMatrixPanel` in
the workspace tab registry. The `WORKSPACE_TABS` contract shape, `resolveWorkspaceTabs`, and `stageOf`
are untouched (R6.5). The `workspace.tab.materials` label is already seeded by FOR-06-00 and reused
unchanged.

### i18n keys (PL + RU, mirrored) — R8

Under a `materialsMatrix.*` namespace, every key in **both** `pl.json` and `ru.json` with real PL + RU
values (locale-parity test passes, no raw key surfaced, R8.2, R8.3):

- Page / tabs: `materialsMatrix.pageTitle`, `tab.construction`, `tab.finishing`.
- Columns: `column.material`, `column.plannedQty`, `column.unit`, `column.plannedBrutto`,
  `column.factQty`, `column.factBrutto`, `column.fulfilment`, `column.linkedCount`.
- Fulfilment / state: `materialsMatrix.fulfilment.label`, `fulfilment.overPurchased`,
  `fulfilment.none` (the `—` when no denominator), `fulfilment.uncapped`.
- Unit mismatch: `materialsMatrix.unitMismatch.badge`, `unitMismatch.tooltip` (with a `{count}`
  interpolation), `unitMismatch.excludedNote`.
- Dashboard: `materialsMatrix.dashboard.plannedBrutto`, `dashboard.factBrutto`, `dashboard.aggregate`,
  `dashboard.grandTotal`.
- Detail: `materialsMatrix.detail.title`, `detail.column.document`, `detail.column.rawName`,
  `detail.column.manufacturer`, `detail.column.quantity`, `detail.column.tier`,
  `detail.column.lineTotal`, `detail.analogNote`, `detail.unitMismatchMark`.
- States: `materialsMatrix.empty`, `loading`, `error`.

Indicative values (finalized during implementation):

| key | PL | RU |
|-----|----|----|
| `materialsMatrix.pageTitle` | `Materiały: plan / wykonanie` | `Материалы: план / факт` |
| `materialsMatrix.column.fulfilment` | `% wyboru` | `% выборки` |
| `materialsMatrix.fulfilment.overPurchased` | `Przekroczono plan` | `Перебор по плану` |
| `materialsMatrix.unitMismatch.badge` | `Inna jednostka` | `Другая единица` |

The `workspace.tab.materials` label (FOR-06-00) is reused unchanged.

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of the
system — a formal statement about what the system should do, bridging human-readable specifications
and machine-verifiable correctness guarantees.*

This feature's pure, input-varying core is the **per-material fact fold** (money-based % выборки
aggregating all analogs across all tiers), the **unit-aware quantity sum**, the **unmatched-line
exclusion**, the **dashboard folds**, and **locale parity**. UI rendering (tabs / rows / drawer /
bars), the controller-startup validation, and the project-scope filtering are **not** property targets
and are covered by component / integration / smoke tests.

### Property 1: Fact fold aggregates all linked analogs at full weight, tier-independent

*For any* planned material and *any* multiset of reconciled lines, `Fact_Brutto` equals the sum of
`lineTotal` over exactly the lines with `matchedMaterialId == materialId` and `matchStatus ∈ {AUTO,
MANUAL_MATCHED}`, and this sum is **invariant under the lines' `matchTier`** (re-labelling a linked
line's tier among `EXACT`/`MANUFACTURER_CATEGORY`/`CATEGORY` does not change `Fact_Brutto` or the
linked-line set) — so multiple distinct products linked to one material all contribute in full.

**Validates: Requirements 1.2, 1.3, 1.5**

### Property 2: Money-based % выборки is uncapped and divide-by-zero-safe

*For any* `Fact_Brutto` and planned brutto, `Fulfilment_Percent` is `Fact_Brutto ÷ plannedBrutto ×
100` when planned brutto is non-null and non-zero (and may exceed 100), is `null` when planned brutto
is null or zero, and is `0` when there are no linked lines; `Fill_Indicator = min(pct, 100)` (null
when pct null) and `overPurchased ⟺ pct > 100`.

**Validates: Requirements 1.3, 1.4, 4.1**

### Property 3: Fact quantity sums only unit-matching lines and counts the rest as mismatches

*For any* linked-line set and norm unit, `Fact_Quantity` equals the sum of `quantity` over exactly the
lines whose `unitHint` matches the norm unit (case/whitespace-insensitive; a blank/null hint never
matches), `Unit_Mismatch_Count` equals the number of linked lines that do not match, the
unit-mismatch flag is `count > 0`, and every linked line — matching or not — is still included in
`Fact_Brutto` (the money total is unit-independent).

**Validates: Requirements 2.1, 2.2, 2.3, 2.4**

### Property 4: Unmatched and unplanned lines contribute to no row

*For any* line set, a line whose `matchStatus` is `UNMATCHED` / `MANUAL_UNMATCHED` or whose
`matchedMaterialId` is null (including an `UnplannedExpenseCategory`-classified line) appears in no
planned-material row's linked set and contributes to no `Fact_Brutto` / `Fact_Quantity`.

**Validates: Requirements 1.6**

### Property 5: Dashboard totals sum per-material contributions per branch and project-wide, excluding null-brutto rows

*For any* set of rows, each per-branch dashboard total (and the grand total) equals the sum over that
branch's rows (resp. all rows) of the planned brutto and the fact brutto, **excluding** rows whose
planned brutto is null; the aggregate % выборки is `Σ fact ÷ Σ planned × 100` (uncapped, null when the
denominator is 0); and the grand total equals the sum of the per-branch totals.

**Validates: Requirements 3.2, 3.3**

### Property 6: Locale parity

*For any* state of the two locale files after this change, `pl.json` and `ru.json` have identical key
sets (the locale-parity test passes), including every new `materialsMatrix.*` key.

**Validates: Requirements 8.2**

## Error Handling

| Condition | Code / behavior | Requirement |
|-----------|-----------------|-------------|
| No planned materials | localized empty-state message, no rows, no error | R5.5 |
| Project has no estimate yet | plan side resolves via the FOR-05-05b get-or-create to an empty structure, not 404 | R1.1 |
| Planned brutto null/zero | `Fulfilment_Percent` = `—`; row shown, excluded from money totals | R1.4, R3.3 |
| No linked lines for a material | `Fact_Brutto` 0, `Fulfilment_Percent` 0, no unit mismatch | R1.4, R2.4 |
| Linked line in a foreign unit | excluded from `Fact_Quantity`, counted in `Unit_Mismatch_Count`, kept in `Fact_Brutto` | R2.1, R2.2 |
| Unmatched / unplanned line | contributes to no row | R1.6 |
| Caller lacks `PURCHASES` READ | 403 via ABAC; the Materials tab is hidden by the tab gate | R6.1, R6.4 |
| `DRAFT` project | no materials-matrix surface reachable (stage resolver) | R6.4 |
| Missing/absent locale key | both locale files carry every `materialsMatrix.*` key; a raw key is never surfaced | R8.2, R8.3 |

The matrix is a read-only projection, so it has **no mutating error codes**; any read-side failure
surfaces the localized `materialsMatrix.error` state on the frontend.

## Testing Strategy

**Dual approach.** Property-based tests verify the universal properties in §Correctness Properties
(the tier-independent full-weight fact fold, the uncapped divide-by-zero-safe % выборки, the
unit-aware quantity sum, the unmatched exclusion, the dashboard folds, locale parity); example-based
unit / integration tests cover concrete scenarios, UI rendering, wiring, and the project-scope read.

**Property-based testing library.** Backend: **jqwik**; frontend: **fast-check + Vitest**. Each
property test runs a **minimum of 100 iterations** and is tagged `Feature: FOR-06-05-materials-matrix,
Property {number}: {property_text}`.

**Backend property tests** — P1 tier-independent full-weight fact fold, P2 uncapped % выборки, P3
unit-aware quantity + mismatch, P4 unmatched exclusion, P5 dashboard folds — over generated planned
rows (with/without a planned brutto, varied norm units) and linked-line multisets (all tiers, all
match states, matching + foreign units, the empty set, over-purchase, multiple analogs per material),
driving the assembler as a pure function with no external calls.

**Backend example / integration / smoke tests** — `getMatrix` over a seeded project (planned materials
via FOR-05-05b + reconciled lines seeded directly) asserting the joined rows, the analog aggregation
(three products → one row > 100 %), the unit-mismatch exclusion, and the null-brutto `—`; a startup
smoke asserting `PermissionAnnotationValidator` classifies `MaterialsMatrixController` COMPLETE (R6.2)
and that **no new ABAC resource** was seeded and **no migration** was added; a project-scope check
that the read never returns another project's lines (R6.3).

**Frontend unit tests (Vitest + fast-check)** — `MaterialsMatrixPanel` render with branch tabs +
category groups (R5.1); `MatrixRow` fill bar + uncapped figure + over-purchase highlight + unit-
mismatch indicator (R5.1, R4.1, R2.2); `MaterialDetailDrawer` linked-line listing with analogs +
tier badges + mismatch marks (R5.3, R4.2, R4.3); `MatrixDashboard` totals (R5.2); the read-only +
`COMPLETED` rendering (R5.4); resolver fixtures (`materials` present on `ACTIVE`/`COMPLETED` with
`PURCHASES`, absent on `DRAFT`, R6.4/R6.5); the locale-parity test (P6 / R8.2) + a no-raw-key render
assertion (R8.3).

**Regression (R9.1).** The FOR-05-05b materials list, the FOR-06-04 reconciled purchases + its
actions, and the FOR-06-03 OCR / `PURCHASES` resource continue to pass; this spec reads them and
changes none.

**Per `.kiro/steering/test-execution-rules.md`:** run only the affected test classes with `--tests`,
redirect to `/tmp/for-06-05-test.log`, and verify via the JUnit result XML under
`foremen-backend/build/test-results/test/`. Do **not** run the full suite.

**API-level `test-cases.md`.** Per `.kiro/steering/test-cases.md`, this is an API + UI spec (a read
API plus a read-only matrix panel). The `test-cases.md` authored in the tasks phase covers the matrix
read model (analog aggregation across tiers, uncapped over-purchase, unit-mismatch exclusion, the
null-brutto `—`, the dashboard totals, the project-scope gate, the ABAC READ gate) as **API
scenarios** against the Dockerized app (`http://localhost:8080`, auth under `/api/auth`), with planned
materials + reconciled lines seeded directly (no live OCR) and a run-id generator for repeatability;
the Materials panel (branch tabs → rows → fill/over-purchase → detail drawer → read-only on
`COMPLETED`) is a **browser-engine** scenario on an `ACTIVE` project. Result artifacts are MD report
tables. Written in Russian.
