# Design Document: FOR-06-06 — Margin matrix (plan × fact work margin)

## Overview

FOR-06-06 is the **work-margin plan-vs-fact** child of the FOR-06 "project in progress" (execution
stage) parent. On an `ACTIVE` / `COMPLETED` project it renders a **read-only margin matrix**: the
FOR-05 estimate (kosztorys) work items down the rows, their **planned** revenue / cost / margin on the
plan side, their **reported** revenue / cost / margin on the fact side, and a project-level **overall
margin** that folds labour, the **actual linked material cost** (procurement), and a **separate
indirect-cost sum** (unplanned / unmatched purchases).

This is a **pure projection** over existing read models. It introduces **no managed entity, no ABAC
resource, no dictionary, and no Liquibase changeset** (the `.kiro/steering/entity-creation-rules.md`
checklist therefore produces nothing): it reads the FOR-05 estimate (plan), the FOR-06-01 approved
work reports (labour fact), the FOR-06-03/04 reconciled procurement lines (material + indirect fact),
the FOR-05-06 `WorkerType` tiers, and the FOR-05-09 `ProjectMember.workerType`, and folds — reusing
the FOR-05-06 pure `MarginCostService` cost core **verbatim**.

### The cost model (confirmed with the product owner)

- **Single cost core, reused.** Labour cost is always the FOR-05-06 `MarginCostService`:
  `Base_Cost = round(base.tierPct × price to 0.5 zł, HALF_UP)`, `Tier_Cost = Base_Cost × (1 +
  tier.uplift)`. There is no second cost formula (R2.1). A null/zero price ⇒ an **unavailable** cost,
  never a fabricated value (R1.6).
- **Worker type drives the fact cost.** For an approved report, the tier is the report worker's
  `ProjectMember.workerType` on the project; an **Uncategorized_Worker** (null) falls back to the
  **base** tier (R1.4, R2.5).
- **Plan side at the base tier.** The estimate is priced for the client, not staffed, so planned cost
  uses the base tier — the FOR-05-06 base-tier baseline (R2.3).
- **Material + indirect fold at the project level.** Linked purchases (a non-null `matchedMaterialId`,
  `AUTO`/`MANUAL_MATCHED`) sum into `Linked_Material_Cost`; unlinked/unplanned lines sum into a
  **separate** `Indirect_Cost` broken down by `UnplannedExpenseCategory` (plus an uncategorized
  bucket). Indirect is excluded from per-work-item margins but included in the overall margin (R3).

### Amendment-correct pricing (memo, FOR-06-08)

Amendments (FOR-06-08) are not implemented. This spec reads `WorkReport.clientUnitPrice` **as-is** — a
value already frozen at approval as `EstimateLine.unitPrice` + signed amendment deltas (zero until
FOR-06-08 ships, FOR-06-01 R6). The matrix is therefore amendment-correct **automatically** once
FOR-06-08 lands; **no amendment dependency here**. FOR-06-08 owns applying price-before/after deltas;
this spec must be revisited only if FOR-06-08 moves where the frozen price is read from.

### Ownership boundary — read only

This spec owns the **matrix read model** and its panel. It does **not** own work reporting / the
status machine / the revenue valuation (FOR-06-01), procurement intake / OCR / reconciliation
(FOR-06-03/04), the materials fulfilment matrix (FOR-06-05), the `WorkerType` dictionary or the
`cost_net` (FOR-05-06), or worker rates / payroll (FOR-06-10/11). It changes no report, no line, no
price, no tier.

### Spans two git repos

Per `.kiro/steering/git-repo-structure.md`:

- **Root repo** — the matrix assembler + service + controller + read-model DTOs/mappers and this
  spec. **No migration.** Committed from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the Margin-matrix panel, the `margin` tab `lazy` swap, the
  API client, and the `pl.json`/`ru.json` keys. Committed from inside `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Notation.** Backend in Java (Spring Boot / JPA / MapStruct / jqwik); frontend in TypeScript/React
(Vite, TanStack Query, the shared `DataTable`, i18next); frontend property tests in fast-check +
Vitest. No Liquibase changeset in this spec.

## Reused mechanisms (not re-implemented)

| Mechanism | Location | Reuse |
|-----------|----------|-------|
| FOR-05-06 pure cost + margin core (`baseCost`, `tierCost`, `labourMargin`, `Margin`, `WorkerTypeTier`) | `foremen-backend/.../service/estimate/margins/MarginCostService.java` | The **single** labour cost + margin formula; reused verbatim for both plan (base tier) and fact (worker tier). No second formula. |
| FOR-05 estimate read model (`EstimateEntity`, `EstimateLineEntity`: `unitPrice`, `quantity`, `valueNet`, `workItem` → `workCategory`, `unit`) | `foremen-backend/.../service/estimate/*` (`EstimateService.getOrCreateEntityForProject`, `EstimateMatrixAssembler`) | The **plan side** — rows, planned quantity/revenue, category, localized work name. Pre-estimate project ⇒ empty, not 404 (mirrors `MarginsListService`). |
| FOR-05-06 `WorkerType` tiers (`tierPct`, `base`, `orderNo`, `active`) | `WorkerTypeDao.findActiveOrdered()` | The tier set; the base tier is the row flagged `base`. Loaded as `WorkerTypeTier` for `MarginCostService`. |
| FOR-05-09 `ProjectMember.workerType` (nullable) | `ProjectMemberEntity.workerType`, `ProjectMemberDao` | Resolves each reported worker's tier; null ⇒ base tier (Uncategorized_Worker). |
| FOR-06-01 approved work reports (`workItem`, `worker`, `clientUnitPrice`, `totalQuantity`, `revenueValue`, `status`) | `foremen-backend/.../service` (`WorkReportService` / `WorkReportDao`, FOR-06-01) | The **labour fact side** — approved reports per work item. Read verbatim; no re-pricing. |
| FOR-06-03/04 reconciled lines (`matchedMaterialId`, `matchStatus`, `lineTotal`, `unplannedCategory`) | `foremen-backend/.../service/procurement` (`PurchaseLineReconciliationService.list`) | The **material + indirect fact side** (linked lineTotal; unlinked by category). |
| `WORK_REPORTS` ABAC resource + role matrix | `database_files/changesets/*seed-work-reports*.xml` (FOR-06-01) | The matrix is `READ`; **no new resource seeded**. |
| FOR-06-00 `UnplannedExpenseCategory` dictionary | FOR-06-00 | The indirect-cost breakdown labels (localized `ru→pl→code`). |
| `ProjectScopedService` + `getProjectIdPath()` | `foremen-backend/.../service/ProjectScopedService.java` | The matrix service resolves the project id so reads are auto project-filtered (R6.3). |
| `ru → pl → code` localized-name fallback | existing dictionary read convention | Work / category / worker / tier / unplanned-category name — no new i18n column (R7.1). |
| `stageOf(status)` / `resolveWorkspaceTabs` / `WORKSPACE_TABS` `margin` slot | `foremen-frontend/.../project-workspace/state/` + registry (FOR-06-00) | Unchanged; only the `margin` tab's `lazy` is swapped (R6.5). |
| `workspace.tab.margin` i18n label | `pl.json` / `ru.json` (FOR-06-00) | Reused unchanged. |

## Architecture

```mermaid
graph TD
    subgraph FE["foremen-frontend/ (nested repo)"]
      TAB["project-workspace registry<br/>margin tab lazy: Placeholder → MarginMatrixPanel"]
      PANEL["features/project-execution/margin-matrix/MarginMatrixPanel.tsx<br/>category groups + overall dashboard"]
      ROW["MarginRow.tsx<br/>plan qty/rev/cost/margin · fact qty/rev/cost/margin · under-margin flag"]
      DETAIL["ReportDetailDrawer.tsx<br/>contributing approved reports + worker + tier (+fallback mark) + derived cost"]
      DASH["MarginDashboard.tsx<br/>plan vs fact · labour/material/indirect split · indirect-by-category"]
      MODEL["state/marginModel.ts<br/>PURE mirror: tierCost, margin, folds (display only)"]
      API["api/margin-matrix-api.ts<br/>GET matrix read model"]
      L["locales/pl.json + ru.json<br/>marginMatrix.* (strict parity)"]
    end

    subgraph BE["foremen-backend/ (root repo)  — NO migration"]
      CTRL["MarginMatrixController<br/>@PermissionResource(\"WORK_REPORTS\") READ"]
      SVC["MarginMatrixService<br/>implements ProjectScopedService"]
      ASM["MarginMatrixAssembler (pure)<br/>plan × fact fold, overall + indirect"]
      CORE(("reused: MarginCostService (cost core)")):::reuse
      EST(("reused: estimate (plan side)")):::reuse
      REP(("reused: approved WorkReports (labour fact)")):::reuse
      PROC(("reused: reconciled lines (material+indirect fact)")):::reuse
      WT(("reused: WorkerType tiers + ProjectMember.workerType")):::reuse
    end

    TAB --> PANEL
    PANEL --> ROW --> DETAIL
    PANEL --> DASH
    ROW --> MODEL
    DASH --> MODEL
    PANEL --> API --> CTRL --> SVC
    SVC --> EST
    SVC --> REP
    SVC --> PROC
    SVC --> WT
    SVC --> ASM
    ASM --> CORE
    classDef reuse fill:#eef,stroke:#88a;
```

### Read flow (open the Margin matrix tab)

```mermaid
sequenceDiagram
    participant U as User (Margin tab)
    participant C as MarginMatrixController (WORK_REPORTS/READ)
    participant S as MarginMatrixService
    participant E as estimate (FOR-05 plan)
    participant R as approved WorkReports (FOR-06-01 fact)
    participant P as reconciled lines (FOR-06-04 fact)
    participant A as MarginMatrixAssembler (pure) + MarginCostService

    U->>C: GET /api/projects/{projectId}/margin-matrix [READ]
    C->>S: getMatrix(projectId)
    S->>E: estimate lines (workItem, unitPrice, quantity, valueNet, category)
    S->>R: APPROVED reports (workItem, worker→tier, clientUnitPrice, totalQuantity, revenueValue)
    S->>P: reconciled lines (matchedMaterialId, matchStatus, lineTotal, unplannedCategory)
    S->>S: resolve worker tiers (ProjectMember.workerType; null→base), load WorkerType tiers
    S->>A: assemble(planLines, approvedReports+tiers, procurementLines, baseTier)
    A->>A: per row: Plan_* (base tier), Fact_* (per-report worker tier via MarginCostService)
    A->>A: project: Linked_Material_Cost, Indirect_Cost by category, Overall_*
    A-->>C: MarginMatrixDto (categories → rows + report detail + dashboard)
    C-->>U: read model (independent of write state)
```

## Data Models

No schema change. The read model is assembled in memory from the existing read models.

### Tier resolution

```
base_tier               = the active WorkerType flagged base (tierPct = offer share, 0.40 by seed)
Worker_Tier(report)     = projectMember(report.worker, project).workerType  (if active)
                          else base_tier                                    // Uncategorized / inactive / missing → base (R2.5)
Tier_Cost(price, tier)  = MarginCostService.tierCost(
                              MarginCostService.baseCost(price, base_tier), tier)   // null when price null/0
```

### Per-work-item fold (the core, over estimate line `m` and its approved reports)

```
Plan_Revenue(m)   = m.valueNet                                   // unitPrice × planned quantity
Plan_Cost(m)      = Tier_Cost(m.unitPrice, base_tier) × m.quantity    // null when price unavailable
Plan_Margin(m)    = labourMargin(Plan_Revenue(m), Plan_Cost(m))  // amount, pct = amount/Plan_Revenue

Approved(m)       = { r : r.workItem == m.workItem ∧ r.status == APPROVED }
Fact_Revenue(m)   = Σ_{r ∈ Approved(m)} r.revenueValue
Fact_Cost(m)      = Σ_{r ∈ Approved(m)} Tier_Cost(r.clientUnitPrice, Worker_Tier(r)) × r.totalQuantity
Fact_Margin(m)    = labourMargin(Fact_Revenue(m), Fact_Cost(m))  // pct null when Fact_Revenue 0 (R1.5)
underMargin(m)    = Plan_Margin(m).pct != null ∧ Fact_Margin(m).pct != null
                    ∧ (Plan_Margin(m).pct − Fact_Margin(m).pct) ≥ UNDER_MARGIN_THRESHOLD  // default 0.05
```

A row whose `m.unitPrice` is null/zero has an **unavailable** `Plan_Cost` / `Plan_Margin` and is
excluded from the plan aggregate denominators (R1.6); a report whose `clientUnitPrice` is null/zero
contributes `0` revenue by its own derived `revenueValue` and an **unavailable** tier cost (so the
report is excluded from the fact cost sum, surfaced in the detail). `Tier_Cost` and the margins come
only from `MarginCostService` (no local arithmetic), so plan and fact share exactly one rule.

### Project-level fold (R3)

```
Linked_Material_Cost = Σ_{line : line.matchedMaterialId != null ∧ line.matchStatus ∈ {AUTO, MANUAL_MATCHED}} line.lineTotal
Indirect_By_Category = group Σ line.lineTotal by line.unplannedCategory
                       over { line : line.matchedMaterialId == null ∨ line.matchStatus ∈ {UNMATCHED, MANUAL_UNMATCHED} }
                       // a line with no category → the "uncategorized" bucket
Indirect_Cost        = Σ Indirect_By_Category

Overall_Revenue      = Σ_m Fact_Revenue(m)
Overall_Cost         = Σ_m Fact_Cost(m) + Linked_Material_Cost + Indirect_Cost
Overall_Margin       = labourMargin(Overall_Revenue, Overall_Cost)     // pct = amount/Overall_Revenue

Plan_Overall_Revenue = Σ_{m : Plan_Revenue(m) available} Plan_Revenue(m)
Plan_Overall_Cost    = Σ_{m : Plan_Cost(m) available}    Plan_Cost(m)
Plan_Overall_Margin  = labourMargin(Plan_Overall_Revenue, Plan_Overall_Cost)
```

`Indirect_Cost` never touches a per-work-item row (R3.3); it enters only `Overall_Cost` (R3.4).
`Linked_Material_Cost` likewise is project-level (purchases are not attributed to one work item, R3.1).

### Read model DTOs (backend, mirrored by the frontend)

```java
public record MarginReportDetailDto(
    Long reportId, Long workerId, String workerName,
    Long workerTypeId, String workerTypeName, boolean baseTierFallback,   // R2.5, R4.2
    BigDecimal reportedQuantity, BigDecimal clientUnitPrice,
    BigDecimal revenueValue,
    BigDecimal derivedTierCost) {}                 // Tier_Cost × qty; null when price unavailable

public record MarginRowDto(
    Long workItemId, String workItemName, String category, String unit,
    BigDecimal plannedQuantity,
    BigDecimal planRevenue, BigDecimal planCost,   // planCost null ⇒ unavailable (R1.6)
    BigDecimal planMarginAmount, BigDecimal planMarginPct,
    BigDecimal factQuantity,                       // Σ report totalQuantity
    BigDecimal factRevenue, BigDecimal factCost,
    BigDecimal factMarginAmount, BigDecimal factMarginPct,  // pct null ⇒ '—' (R1.5)
    boolean underMargin,                            // R4.3
    int reportCount,
    List<MarginReportDetailDto> reports) {}         // R4.2

public record MarginCategoryGroupDto(
    String category,
    List<MarginRowDto> rows,
    BigDecimal planRevenue, BigDecimal planCost, BigDecimal planMarginAmount,     // subtotals (R4.1)
    BigDecimal factRevenue, BigDecimal factCost, BigDecimal factMarginAmount) {}

public record IndirectCategoryDto(
    Long categoryId, String categoryName,          // null id + localized "uncategorized" for the bucket
    BigDecimal amount) {}

public record MarginDashboardDto(
    BigDecimal planRevenue, BigDecimal planCost, BigDecimal planMarginAmount, BigDecimal planMarginPct,
    BigDecimal factLabourRevenue, BigDecimal factLabourCost,
    BigDecimal linkedMaterialCost,
    BigDecimal indirectCost, List<IndirectCategoryDto> indirectByCategory,
    BigDecimal overallRevenue, BigDecimal overallCost,
    BigDecimal overallMarginAmount, BigDecimal overallMarginPct) {}

public record MarginMatrixDto(
    Long projectId,
    List<MarginCategoryGroupDto> categories,
    MarginDashboardDto dashboard) {}
```

### Frontend read model (mirrors backend DTOs)

```typescript
export interface MarginReportDetail {
  reportId: number; workerId: number; workerName: string
  workerTypeId: number | null; workerTypeName: string; baseTierFallback: boolean
  reportedQuantity: number; clientUnitPrice: number | null
  revenueValue: number; derivedTierCost: number | null
}
export interface MarginRow {
  workItemId: number; workItemName: string; category: string; unit: string | null
  plannedQuantity: number
  planRevenue: number; planCost: number | null
  planMarginAmount: number | null; planMarginPct: number | null
  factQuantity: number
  factRevenue: number; factCost: number
  factMarginAmount: number | null; factMarginPct: number | null
  underMargin: boolean
  reportCount: number; reports: MarginReportDetail[]
}
export interface IndirectCategory { categoryId: number | null; categoryName: string; amount: number }
export interface MarginDashboard {
  planRevenue: number; planCost: number | null
  planMarginAmount: number | null; planMarginPct: number | null
  factLabourRevenue: number; factLabourCost: number
  linkedMaterialCost: number
  indirectCost: number; indirectByCategory: IndirectCategory[]
  overallRevenue: number; overallCost: number
  overallMarginAmount: number | null; overallMarginPct: number | null
}
export interface MarginMatrixDto {
  projectId: number
  categories: { category: string; rows: MarginRow[]
    planRevenue: number; planCost: number | null; planMarginAmount: number | null
    factRevenue: number; factCost: number; factMarginAmount: number | null }[]
  dashboard: MarginDashboard
}
```

## Components and Interfaces

### Backend components

#### B1 — `MarginMatrixAssembler` (pure projection, R1, R2, R3, R4)

A pure, stateless `@Component` that builds `MarginMatrixDto` from the estimate lines, the approved
reports (each already carrying its resolved `WorkerTypeTier`), the procurement lines, and the base
tier. It performs no I/O beyond reading the passed-in models. It:

- per estimate line, folds the **plan** side at the base tier and the **fact** side over the work
  item's approved reports (each report costed at its own resolved tier), via `MarginCostService`
  **only** — `Tier_Cost`, `labourMargin`, and the amounts/percentages come from the shipped core, so
  plan and fact never drift (R2.1);
- computes `underMargin` from the two margin percentages against `UNDER_MARGIN_THRESHOLD` (default
  `0.05`), unavailable when either pct is unavailable (R4.3);
- groups rows by work category (localized, estimate order) with per-category plan/fact subtotals
  (R4.1);
- folds `Linked_Material_Cost`, `Indirect_Cost` by `UnplannedExpenseCategory` (+ the uncategorized
  bucket), and the `Overall_*` + `Plan_Overall_*` dashboard (R3);
- carries each report into the per-row detail with the resolved tier and a `baseTierFallback` flag
  (R2.5, R4.2), guaranteeing the row fact cost equals the sum of the detail derived costs (R4.4).

The **tier-cost/margin helpers are not re-implemented** here — they are `MarginCostService` calls; the
assembler only wires inputs and aggregates. The assembler is directly jqwik-testable (no Spring).

#### B2 — `MarginMatrixService` (read model, R1, R2, R6)

A `ProjectScopedService`-backed service owning the single read:

- `getProjectIdPath()` resolves from the backing entity (the work report) to its owning project id
  (`"project.id"`), so reads are auto project-filtered (R6.3) — mirroring `WorkReportService`.
- `getMatrix(projectId)` — resolve the FOR-05 estimate lines (reuse `EstimateService.getOrCreate…` so
  a pre-estimate project returns an empty structure, not 404); load the project's **approved** work
  reports; resolve each report worker's tier from `ProjectMember.workerType` (null/inactive/missing →
  base tier); load the active `WorkerType` tiers (for the base tier + the tier names); load the
  project's reconciled procurement lines; delegate to `MarginMatrixAssembler`. Read-only,
  lifecycle-independent for the read itself; the tab is stage-gated on the frontend + `WORK_REPORTS`/
  READ (R6.4). Runs read-write transactionally only because the plan-side get-or-create may INSERT the
  estimate (mirrors `MarginsListService`).
- Emits **no** notifications (R8.1).

Worker-tier resolution batches the project's `ProjectMember` rows once (a map `userId → workerType`)
to avoid N+1; a report whose worker has no ACTIVE membership resolves to the base tier with the
fallback flag set.

#### B3 — `MarginMatrixController` (endpoint on the WORK_REPORTS resource, R6)

`@RestController @PermissionResource("WORK_REPORTS")`, fully annotated so
`PermissionAnnotationValidator` classifies it COMPLETE at startup (R6.2):

| Endpoint | Op | Guard |
|----------|-----|------|
| `GET /api/projects/{projectId}/margin-matrix` | matrix read model | READ |

No new ABAC resource / operation / role grant (R6.1). The `WORK_REPORTS` matrix already grants ADMIN /
MANAGER / FOREMAN / (admin-staff) READ, so the matrix is readable by the same roles that read reports.

### Frontend components

Feature folder `foremen-frontend/src/features/project-execution/margin-matrix/` (sibling of
`work-reporting` / `materials-matrix`), rendering inside the `margin` execution tab:

- `MarginMatrixPanel.tsx` — the overall dashboard above; category-grouped rows in a `DataTable` with
  per-category subtotals; localized loading / empty (no estimate work items) / error (R5.1, R5.5).
  Read-only — no write controls; identical on `COMPLETED` (R5.4).
- `components/MarginRow.tsx` — per row: planned qty + unit, planned revenue / cost / margin (amount +
  pct, `—` when unavailable); reported qty, reported revenue / cost / margin; an **under-margin**
  highlight when `underMargin`; the report count; a disclosure to open the detail (R5.1, R4.3).
- `components/ReportDetailDrawer.tsx` — the per-row detail: every contributing approved report
  (worker, resolved worker-type tier with a **base-tier-fallback** mark, reported quantity, client
  unit price, revenue, derived tier cost); the sum of the derived costs equals the row fact cost
  (R4.2, R4.4, R5.3).
- `components/MarginDashboard.tsx` — plan vs fact revenue / cost / margin; the labour / linked-material
  / indirect split; the indirect breakdown by category (localized, with the uncategorized bucket)
  (R5.2, R3).
- `components/UnderMarginBadge.tsx` — the localized under-margin indicator + tooltip.
- `state/marginModel.ts` — a **pure display mirror** (`tierCost`, `labourMargin`, the folds) so the
  panel formats consistently with the backend; the authoritative numbers come from the read model.
- `api/margin-matrix-api.ts` — `getMatrix(projectId)` via the shared `apiRequest`.
- `schemas/`, `types/`, `__tests__/`.

**Tab wiring.** Swap the `margin` entry's `lazy` from `PlaceholderTab` to `MarginMatrixPanel` in the
workspace tab registry. The `WORKSPACE_TABS` contract shape, `resolveWorkspaceTabs`, and `stageOf` are
untouched (R6.5). The `workspace.tab.margin` label is already seeded by FOR-06-00 and reused unchanged.

### i18n keys (PL + RU, mirrored) — R7

Under a `marginMatrix.*` namespace, every key in **both** `pl.json` and `ru.json` with real PL + RU
values (locale-parity test passes, no raw key surfaced, R7.2, R7.3):

- Page: `marginMatrix.pageTitle`.
- Columns: `column.work`, `column.unit`, `column.plannedQty`, `column.planRevenue`, `column.planCost`,
  `column.planMargin`, `column.factQty`, `column.factRevenue`, `column.factCost`, `column.factMargin`,
  `column.reportCount`.
- State / margin: `marginMatrix.unavailable` (the `—`), `underMargin.badge`, `underMargin.tooltip`
  (with a `{deltaPct}` interpolation), `marginPct`.
- Dashboard: `marginMatrix.dashboard.title`, `dashboard.plan`, `dashboard.fact`, `dashboard.labour`,
  `dashboard.linkedMaterial`, `dashboard.indirect`, `dashboard.indirectByCategory`,
  `dashboard.overall`, `dashboard.revenue`, `dashboard.cost`, `dashboard.margin`,
  `dashboard.uncategorized`.
- Detail: `marginMatrix.detail.title`, `detail.column.worker`, `detail.column.tier`,
  `detail.baseTierFallback`, `detail.column.quantity`, `detail.column.unitPrice`,
  `detail.column.revenue`, `detail.column.cost`.
- States: `marginMatrix.empty`, `loading`, `error`.

Indicative values (finalized during implementation):

| key | PL | RU |
|-----|----|----|
| `marginMatrix.pageTitle` | `Marża: plan / wykonanie` | `Маржа: план / факт` |
| `marginMatrix.column.factMargin` | `Marża (wykonanie)` | `Маржа (факт)` |
| `marginMatrix.underMargin.badge` | `Poniżej planu` | `Ниже плана` |
| `marginMatrix.dashboard.indirect` | `Koszty pośrednie` | `Косвенные расходы` |
| `marginMatrix.detail.baseTierFallback` | `Typ bazowy (brak przypisania)` | `Базовый тип (не назначен)` |

The `workspace.tab.margin` label (FOR-06-00) is reused unchanged.

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of the
system — a formal statement about what the system should do, bridging human-readable specifications
and machine-verifiable correctness guarantees.*

This feature's pure, input-varying core is the **per-work-item plan/fact margin fold** (reusing
`MarginCostService`), the **per-report worker-tier cost with the base-tier fallback**, the
**linked-material + separate-indirect project fold**, the **unavailable-price propagation**, the
**dashboard folds**, and **locale parity**. UI rendering, the controller-startup validation, and the
project-scope filtering are **not** property targets and are covered by component / integration /
smoke tests.

### Property 1: Fact cost sums each report at its own worker-type tier; the row fact cost equals the detail sum

*For any* work item and *any* multiset of approved reports, `Fact_Cost` equals the sum over those
reports of `Tier_Cost(report.clientUnitPrice, Worker_Tier(report)) × report.totalQuantity`, each
report using its own resolved tier, and this equals the sum of the row's detail `derivedTierCost`
entries — so re-staffing one report to a different worker type changes only that report's contribution.

**Validates: Requirements 1.3, 2.1, 2.4, 4.4**

### Property 2: Uncategorized / inactive / missing worker resolves to the base tier

*For any* report whose worker has no assigned worker type (null), an inactive type, or no ACTIVE
project membership, `Worker_Tier` is the base tier, the derived cost equals the base-tier cost, and
the detail carries `baseTierFallback = true`; *for any* report whose worker has an active assigned
type, that type is used and the flag is false.

**Validates: Requirements 1.4, 2.5**

### Property 3: Plan cost uses the base tier and the margin core; unavailable price propagates

*For any* estimate line, `Plan_Cost = Tier_Cost(unitPrice, base) × quantity` and `Plan_Margin =
labourMargin(valueNet, Plan_Cost)` come from `MarginCostService`; when `unitPrice` is null/zero the
`Plan_Cost`, `Plan_Margin`, and the row's plan percentage are **unavailable** (never a fabricated
zero) and the row is excluded from the plan aggregate denominators.

**Validates: Requirements 1.2, 1.6, 2.3, 3.5**

### Property 4: Zero-report rows and divide-by-zero safety

*For any* work item with no approved reports, `Fact_Revenue` and `Fact_Cost` are `0` and the fact
margin percentage is unavailable (`—`); *for any* row, a margin percentage is `null` exactly when its
revenue base is `0`/unavailable — never a division by zero — and `underMargin` is false whenever
either the plan or the fact percentage is unavailable.

**Validates: Requirements 1.5, 4.3**

### Property 5: Linked material counts in, indirect accumulates separately by category, both only in the overall margin

*For any* set of procurement lines, `Linked_Material_Cost` is the sum of `lineTotal` over exactly the
lines with a non-null `matchedMaterialId` and `matchStatus ∈ {AUTO, MANUAL_MATCHED}`; `Indirect_Cost`
is the sum over exactly the complementary lines, partitioned by `unplannedCategory` (a null category
in the uncategorized bucket); neither enters any per-work-item row; and `Overall_Cost = Σ Fact_Cost +
Linked_Material_Cost + Indirect_Cost`, so reclassifying a line between linked and unplanned moves its
amount between the two buckets but leaves `Overall_Cost` unchanged.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4**

### Property 6: Dashboard and category subtotals equal the sum of their parts

*For any* set of rows, each category subtotal equals the sum over that category's rows of plan/fact
revenue / cost / margin amount; the dashboard `Overall_Revenue` equals `Σ Fact_Revenue(row)`, the plan
overall totals sum only the rows with an available plan figure, and the overall/plan margin
percentages are `amount ÷ revenue` (null when revenue is `0`).

**Validates: Requirements 3.4, 3.5, 4.1**

### Property 7: Locale parity

*For any* state of the two locale files after this change, `pl.json` and `ru.json` have identical key
sets (the locale-parity test passes), including every new `marginMatrix.*` key.

**Validates: Requirements 7.2**

## Error Handling

| Condition | Code / behavior | Requirement |
|-----------|-----------------|-------------|
| No estimate work items | localized empty-state message, no rows, no error | R5.5 |
| Project has no estimate yet | plan side resolves via get-or-create to an empty structure, not 404 | R1.1 |
| Row price null/zero | `Plan_Cost` / `Plan_Margin` = `—`; row shown, excluded from plan totals | R1.6, R3.5 |
| Report price null/zero | report derived cost `—`, excluded from the fact cost sum, shown in detail | R1.6 |
| Work item with no approved reports | `Fact_Revenue`/`Fact_Cost` = 0, fact margin pct `—` | R1.5 |
| Worker has no / inactive type, or no membership | base-tier fallback, `baseTierFallback` flag set | R2.5 |
| Linked purchase line | counted in `Linked_Material_Cost` (overall only) | R3.1 |
| Unplanned / unmatched line | counted in `Indirect_Cost` by category (overall only) | R3.2, R3.3 |
| Caller lacks `WORK_REPORTS` READ | 403 via ABAC; the Margin tab is hidden by the tab gate | R6.1, R6.4 |
| `DRAFT` project | no margin-matrix surface reachable (stage resolver) | R6.4 |
| Missing/absent locale key | both locale files carry every `marginMatrix.*` key; a raw key is never surfaced | R7.2, R7.3 |

The matrix is a read-only projection, so it has **no mutating error codes**; any read-side failure
surfaces the localized `marginMatrix.error` state on the frontend.

## Testing Strategy

**Dual approach.** Property-based tests verify the universal properties in §Correctness Properties
(the per-report worker-tier fact cost + detail-sum equality, the base-tier fallback, the base-tier
plan cost + unavailable-price propagation, the zero-report/divide-by-zero safety, the linked/indirect
split folded only into the overall margin, the dashboard/subtotal folds, locale parity); example-based
unit / integration tests cover concrete scenarios, UI rendering, wiring, and the project-scope read.

**Property-based testing library.** Backend: **jqwik**; frontend: **fast-check + Vitest**. Each
property test runs a **minimum of 100 iterations** and is tagged `Feature: FOR-06-06-margin-matrix,
Property {number}: {property_text}`.

**Backend property tests** — P1 per-report tier fact cost + detail-sum, P2 base-tier fallback, P3
base-tier plan cost + unavailable price, P4 zero-report / divide-by-zero, P5 linked/indirect split,
P6 dashboard/subtotal folds — over generated estimate lines (with/without a price, varied quantities),
approved-report multisets (varied worker tiers incl. Uncategorized, varied quantities/prices, the
empty set), and procurement-line sets (linked / unmatched / unplanned across categories), driving the
assembler as a pure function with no external calls.

**Backend example / integration / smoke tests** — `getMatrix` over a seeded project (estimate lines +
approved reports with workers of differing worker types + reconciled lines seeded directly): assert
the joined rows, the per-report tier cost mix, the base-tier fallback for an Uncategorized worker, the
linked-material fold, the indirect-by-category separate sum and its appearance only in the overall
margin, the unavailable-price `—`, the under-margin flag, and the dashboard totals; a startup smoke
asserting `PermissionAnnotationValidator` classifies `MarginMatrixController` COMPLETE (R6.2) and that
**no new ABAC resource** was seeded and **no migration** was added; a project-scope check that the read
never returns another project's data (R6.3).

**Frontend unit tests (Vitest + fast-check)** — `MarginMatrixPanel` render with category groups +
subtotals (R5.1); `MarginRow` plan-vs-fact margin + under-margin highlight + unavailable `—` (R5.1,
R4.3, R5.5); `ReportDetailDrawer` report listing with the worker-type tier + base-tier-fallback mark
and the detail-sum = fact-cost check (R5.3, R4.2, R4.4); `MarginDashboard` plan/fact + labour/material/
indirect split + indirect-by-category (R5.2, R3); the read-only + `COMPLETED` rendering (R5.4);
resolver fixtures (`margin` present on `ACTIVE`/`COMPLETED` with `WORK_REPORTS`, absent on `DRAFT`,
R6.4/R6.5); the locale-parity test (P7 / R7.2) + a no-raw-key render assertion (R7.3).

**Regression (R9.1).** The FOR-05 estimate / FOR-05-06 margins + `MarginCostService`, the FOR-06-01
work reporting + `WORK_REPORTS` resource, and the FOR-06-03/04 procurement continue to pass; this spec
reads them and changes none.

**Per `.kiro/steering/test-execution-rules.md`:** run only the affected test classes with `--tests`,
redirect to `/tmp/for-06-06-test.log`, and verify via the JUnit result XML under
`foremen-backend/build/test-results/test/`. Do **not** run the full suite.

**API-level `test-cases.md`.** Per `.kiro/steering/test-cases.md`, this is an API + UI spec (a read
API plus a read-only matrix panel). The `test-cases.md` authored in the tasks phase covers the matrix
read model (the per-report worker-tier fact cost, the base-tier fallback for an Uncategorized worker,
the base-tier plan cost, the linked-material fold, the indirect-by-category separate sum in the overall
margin only, the unavailable-price `—`, the under-margin flag, the dashboard totals, the project-scope
gate, the ABAC `WORK_REPORTS`/READ gate) as **API scenarios** against the Dockerized app
(`http://localhost:8080`, auth under `/api/auth`), with estimate lines + approved reports (workers of
differing worker types) + reconciled lines seeded directly (no live reporting/OCR) and a run-id
generator for repeatability; the Margin panel (category groups → rows → plan/fact margin → under-margin
highlight → report detail → overall dashboard → read-only on `COMPLETED`) is a **browser-engine**
scenario on an `ACTIVE` project. Result artifacts are MD report tables. Written in Russian.
