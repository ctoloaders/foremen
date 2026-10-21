# Design Document: FOR-06-10 — Worker cost rates (worker service cost derivation)

## Overview

FOR-06-10 is the **worker cost-side** child of the FOR-06 "project in progress" (execution stage)
parent. It delivers the **single, reusable derivation of a worker's service cost** for the work
actually reported on an `ACTIVE` / `COMPLETED` project — the cost side that FOR-06-01 (work reporting)
deliberately left out (its `WorkReport` owns only the revenue side), and that **FOR-06-11 (payroll)**
and **FOR-06-06 (margin matrix)** consume.

The product-owner decision is explicit: there is **no `WorkerServiceRate` managed entity, no CRUD,
and no rate dictionary**. The worker cost is **always derived on the fly** from (1) the reported
worker's **`WorkerType`** (resolved from `ProjectMember.workerType`, fallback to the base tier) and
(2) the **current project's work-item rate** (`WorkReport.clientUnitPrice`), combined through the
FOR-05-06 pure cost core `MarginCostService` **verbatim**: take the percentage from the worker type
and apply it to the work-item rate. No second cost formula.

This is a **pure derivation** over existing read models. It introduces **no managed entity, no ABAC
resource, no dictionary, and no Liquibase changeset** (the `.kiro/steering/entity-creation-rules.md`
checklist therefore produces nothing): it reads the FOR-05-06 `WorkerType` tiers, the FOR-05-09
`ProjectMember.workerType`, and the FOR-06-01 approved work reports (`clientUnitPrice`,
`totalQuantity`), and folds via `MarginCostService`.

### The cost model (confirmed with the product owner)

- **Single cost core, reused.** The worker cost is always the FOR-05-06 `MarginCostService`:
  `Base_Cost = round(base.tierPct × Work_Item_Rate to 0.5 zł, HALF_UP)`, `Worker_Service_Cost =
  Base_Cost × (1 + tier.uplift)`. There is **no** second cost formula (R1.2). A null/zero rate ⇒ an
  **unavailable** cost, never a fabricated value (R1.5).
- **Worker type drives the cost.** For an approved report the tier is the report worker's
  `ProjectMember.workerType` on the project; an **Uncategorized** worker (null), a missing membership,
  or an **inactive** worker type falls back to the **base tier** (R2.1, R2.2).
- **Work-item rate comes from the report.** `Work_Item_Rate = WorkReport.clientUnitPrice` (FOR-06-01,
  frozen at approval); the service never re-prices it (R1.4).
- **Total cost of a reported work.** `Cost_Value = Worker_Service_Cost × WorkReport.totalQuantity`
  (R1.3).

### Why this is a service, not a rate dictionary

FOR-06-06 (margin matrix) today computes this exact fact-side labour cost **inline**
(`Tier_Cost(report.clientUnitPrice, Worker_Tier(report)) × report.totalQuantity`). The job of this
spec is to **extract that computation into one shared `WorkerCostService`** so cost is computed
**once**, identically, by both the margin matrix and the upcoming payroll — the single cost-side seam
the FOR-06 overview and the `WorkReport` Javadoc promise. The derivation lives in the FOR-05-06
`MarginCostService` already; this spec adds the thin **project-scoped resolution + aggregation** layer
around it (worker → tier → cost) and refactors FOR-06-06 to call it (no behaviour change).

### Amendment-correct pricing (memo, FOR-06-08)

Amendments (FOR-06-08) are not implemented. This spec reads `WorkReport.clientUnitPrice` **as-is** — a
value already frozen at approval as `EstimateLine.unitPrice` + signed amendment deltas (zero until
FOR-06-08 ships, FOR-06-01 R6). The worker cost is therefore amendment-correct **automatically** once
FOR-06-08 lands; **no amendment dependency here**. **Memo for FOR-06-08:** this spec reads the frozen
per-unit rate from `WorkReport.clientUnitPrice` and must be revisited only if FOR-06-08 moves where
that frozen rate is read from.

### Ownership boundary — read only

This spec owns the **worker-cost derivation service** and its read model. It does **not** own work
reporting / the status machine / the revenue valuation (FOR-06-01), the `WorkerType` dictionary or
tier percentages (FOR-05-06), worker-type assignment (FOR-05-09), payroll aggregation / payout
documents (FOR-06-11), or the margin matrix UI / plan-material-indirect folds (FOR-06-06). It changes
no report, no membership, no tier, and persists **no cost column** on `WorkReport`.

### Spans two git repos

Per `.kiro/steering/git-repo-structure.md`:

- **Root repo** — the `WorkerCostService` + its read-model DTOs/mappers + any read endpoint + the
  FOR-06-06 refactor + this spec. **No migration** (unless the optional idempotent FINANCIER
  `WORK_REPORTS` READ grant is needed — see R5.4). Committed from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — only if a new cost label / fallback marker string is
  surfaced beyond what FOR-06-06 already ships; committed from inside `foremen-frontend/`.

A full implementation needs **one** root-repo commit (and a frontend commit only if new strings are
added).

**Notation.** Backend in Java (Spring Boot / JPA / MapStruct / jqwik); frontend in TypeScript/React
(Vite, TanStack Query, i18next) only where a new string is introduced.

## Reused mechanisms (not re-implemented)

| Mechanism | Location | Reuse |
|-----------|----------|-------|
| FOR-05-06 pure cost core (`baseCost`, `tierCost`, `Margin`, `WorkerTypeTier`) | `foremen-backend/.../service/estimate/margins/MarginCostService.java` | The **single** worker-cost formula; reused verbatim. No second formula (R1.2). |
| FOR-05-06 `WorkerType` tiers (`tierPct`, `base`, `orderNo`, `active`) | `WorkerTypeDao.findActiveOrdered()` | The tier set; the base tier is the row flagged `base`, loaded as `WorkerTypeTier`. |
| FOR-05-09 `ProjectMember.workerType` (nullable) | `ProjectMemberEntity.workerType`, `ProjectMemberDao` | Resolves each reported worker's tier; null/inactive/missing ⇒ base tier (R2). |
| FOR-06-01 approved work reports (`worker`, `workItem`, `clientUnitPrice`, `totalQuantity`, `status`) | `foremen-backend/.../service` (`WorkReportService` / `WorkReportDao`) | The input set — approved reports per project. Read verbatim; no re-pricing. |
| FOR-06-01 `WORK_REPORTS` ABAC resource + role matrix | `database_files/changesets/*seed-work-reports*.xml` | The cost read rides `WORK_REPORTS`/READ; **no new resource** (R5.1). |
| `ProjectScopedService` + `getProjectIdPath()` | `foremen-backend/.../service/ProjectScopedService.java` | The cost service resolves the project id so reads are auto project-filtered (R5.3). |
| `ru → pl → code` localized-name fallback | existing dictionary read convention | Worker / worker-type-tier name — no new i18n column (R6.1). |
| FOR-06-06 `MarginMatrixAssembler` fact-cost call site | `foremen-backend/.../service/estimate/margins` (FOR-06-06) | Refactored to call `WorkerCostService` instead of computing the tier cost inline (R4). |

## Architecture

```mermaid
graph TD
    subgraph BE["foremen-backend/ (root repo) — NO migration (optional idempotent grant only)"]
      CORE(("reused: MarginCostService (cost core)")):::reuse
      WT(("reused: WorkerType tiers + ProjectMember.workerType")):::reuse
      REP(("reused: approved WorkReports (FOR-06-01)")):::reuse

      SVC["WorkerCostService<br/>implements ProjectScopedService<br/>resolve tier → unit cost → Cost_Value"]
      MODEL["WorkerCostReadModel (DTOs)<br/>per-report · per-worker · per-work-item"]
      CTRL["WorkerCostController (optional read endpoint)<br/>@PermissionResource(\"WORK_REPORTS\") READ"]

      M06["FOR-06-06 MarginMatrixAssembler<br/>(refactored to call WorkerCostService)"]
      M11["FOR-06-11 payroll (future consumer)"]
    end

    SVC --> CORE
    SVC --> WT
    SVC --> REP
    SVC --> MODEL
    CTRL --> SVC
    M06 --> SVC
    M11 -.future.-> SVC
    classDef reuse fill:#eef,stroke:#88a;
```

### Derivation flow (compute a reported work's cost)

```mermaid
sequenceDiagram
    participant Caller as Caller (FOR-06-06 / FOR-06-11 / read endpoint)
    participant S as WorkerCostService
    participant M as ProjectMember.workerType (FOR-05-09)
    participant W as WorkerType tiers (FOR-05-06)
    participant Core as MarginCostService (cost core)

    Caller->>S: costForProject(projectId)  /  costFor(report)
    S->>M: batch map userId → workerType for the project's members
    S->>W: active WorkerType tiers (base tier + tier names)
    loop each APPROVED report
        S->>S: Resolved_Worker_Type = member.workerType ?: base (if active, else base)
        S->>Core: Base_Cost(clientUnitPrice, base) ; Worker_Service_Cost = × (1 + tier.uplift)
        S->>S: Cost_Value = Worker_Service_Cost × totalQuantity  (null-safe)
    end
    S-->>Caller: WorkerCost read model (per report + per-worker + per-work-item aggregates)
```

## Data Models

No schema change. The cost figures are derived in memory from existing read models; **no cost column
is added to `WorkReport`** (consistent with the FOR-06-01 Javadoc deferring the cost side here).

### Tier resolution and unit cost

```
base_tier                 = the active WorkerType flagged base (tierPct = offer share, 0.40 by seed)
Resolved_Type(report)     = projectMember(report.worker, project).workerType   (if present and active)
                            else base_tier                                      // Uncategorized / inactive / missing → base (R2.2)
Base_Fallback(report)     = (Resolved_Type(report) == base_tier via fallback)   // R2.4

Base_Cost(rate)           = MarginCostService.baseCost(rate, base_tier)         // null when rate null/0 (R1.5)
Worker_Service_Cost(r)    = MarginCostService.tierCost(Base_Cost(r.clientUnitPrice), Resolved_Type(r))  // unit cost
Cost_Value(r)             = Worker_Service_Cost(r) × r.totalQuantity            // null-safe: null unit ⇒ null (R1.3, R1.5)
```

The unit cost and base cost come **only** from `MarginCostService` (no local arithmetic), so this spec
and FOR-06-06 share exactly one rule (R1.2, R4.3).

### Aggregation (over the project's APPROVED reports)

```
Approved(project)   = { r : r.project == project ∧ r.status == APPROVED }
Per_Worker(w)       = Σ_{r ∈ Approved, r.worker == w ∧ Cost_Value(r) available} Cost_Value(r)   // payroll basis (R3.2)
Per_WorkItem(m)     = Σ_{r ∈ Approved, r.workItem == m ∧ Cost_Value(r) available} Cost_Value(r)  // margin fact basis (R3.2)
```

A report whose `Cost_Value` is unavailable (null/zero rate) is **marked unavailable** and **excluded**
from every sum — never contributing a fabricated zero (R3.4).

### Read model DTOs (backend)

```java
public record WorkerCostLineDto(
    Long reportId, Long workerId, String workerName,
    Long workItemId, String workItemName,
    Long workerTypeId, String workerTypeName, boolean baseTierFallback,   // R2.4
    BigDecimal workItemRate,            // = clientUnitPrice (R1.4)
    BigDecimal totalQuantity,
    BigDecimal unitServiceCost,         // Worker_Service_Cost; null ⇒ unavailable (R1.5)
    BigDecimal costValue) {}            // unitServiceCost × totalQuantity; null ⇒ unavailable

public record WorkerCostByWorkerDto(
    Long workerId, String workerName,
    int reportCount, BigDecimal totalCost) {}        // Σ Cost_Value over the worker's approved reports (R3.2)

public record WorkerCostByWorkItemDto(
    Long workItemId, String workItemName,
    int reportCount, BigDecimal totalCost) {}        // Σ Cost_Value over the work item's approved reports (R3.2)

public record WorkerCostReadModelDto(
    Long projectId,
    List<WorkerCostLineDto> lines,                   // per approved report (R3.1)
    List<WorkerCostByWorkerDto> byWorker,            // payroll-facing aggregate
    List<WorkerCostByWorkItemDto> byWorkItem) {}     // margin-facing aggregate
```

The DTOs are the **cost-side companion** to FOR-06-01's revenue-side `clientUnitPrice` /
`revenueValue`. FOR-06-06 needs only `byWorkItem` (and the per-line detail for its drawer); FOR-06-11
will need `byWorker` (and the per-line detail for payout statements).

## Components and Interfaces

### B1 — `WorkerCostService` (the single cost-side seam, R1, R2, R3, R5)

A `ProjectScopedService`-backed `@Service` owning the derivation and its read:

- `getProjectIdPath()` resolves from the backing `WorkReport` entity to its owning project id
  (`"project.id"`), so reads are auto project-filtered (R5.3) — mirroring `WorkReportService`.
- A **pure derivation helper** (static, jqwik-testable) `unitServiceCost(rate, tier, baseTier)` and
  `costValue(rate, tier, baseTier, quantity)` that call `MarginCostService` only — the single formula
  (R1.2). The service does no cost arithmetic itself beyond these calls.
- `tierFor(report, memberIndex, baseTier)` — resolves `Resolved_Worker_Type` from a pre-built
  `userId → active workerType` map (built once per project to avoid N+1); null / inactive / missing →
  base tier with `Base_Fallback = true` (R2.1, R2.2, R2.5). Deterministic, side-effect free.
- `costForProject(projectId)` → `WorkerCostReadModelDto`: load the project's **approved** reports
  (reuse `WorkReportService` / `WorkReportDao`), load the active `WorkerType` tiers (base tier + tier
  names), batch the project's `ProjectMember` rows into the member map, derive each line, and fold the
  `byWorker` / `byWorkItem` aggregates (R3). Read-only; emits **no** notifications (R7).
- A per-report convenience `costFor(report, baseTier, memberMap)` used by FOR-06-06's assembler so it
  can derive a single report's cost in its existing loop without re-querying.

The service is **thin**: resolution + aggregation around `MarginCostService`. It persists nothing and
mutates nothing (R2.5).

### B2 — `WorkerCostController` (optional read endpoint, R5)

Only if a standalone cost surface is wanted (FOR-06-06 and FOR-06-11 can call the service directly
in-process). When present: `@RestController @PermissionResource("WORK_REPORTS")`, fully annotated so
`PermissionAnnotationValidator` classifies it COMPLETE at startup (R5.2):

| Endpoint | Op | Guard |
|----------|-----|------|
| `GET /api/projects/{projectId}/worker-costs` | worker-cost read model | READ |

No new ABAC resource / operation (R5.1). The read rides the `WORK_REPORTS` matrix, so it is readable
by the same roles that read reports (ADMIN / MANAGER / FOREMAN), plus **FINANCIER** (R5.4).

### B3 — FOR-06-06 refactor (single formula, R4)

`MarginMatrixAssembler` (FOR-06-06) currently computes the fact-side labour cost inline. The refactor
replaces that inline `Tier_Cost(report.clientUnitPrice, Worker_Tier(report)) × report.totalQuantity`
with a call to the shared `WorkerCostService.costFor(report, baseTier, memberMap)` (or by consuming
the `byWorkItem` aggregate). The change is **behaviour-preserving** — same `MarginCostService` core,
same base-tier fallback, penny-for-penny identical — so existing FOR-06-06 unit/property tests pass
unchanged (R4.2), and the duplicate inline formula is removed (R4.3).

### Optional ABAC grant (R5.4)

If FOR-06-01 did not grant **FINANCIER** `WORK_REPORTS` READ, this spec adds a single idempotent
Liquibase changeset granting it (`onFail="MARK_RAN"`, guarded by the join count), registered last in
`changelog.xml` per `.kiro/steering/entity-creation-rules.md`. If the grant already exists, **no
changeset is added** and the spec has no migration.

### Frontend

No new feature folder. FOR-06-10 produces no standalone UI; its cost numbers surface inside the
FOR-06-06 margin matrix (unchanged after the behaviour-preserving refactor) and later FOR-06-11
payroll. The only possible frontend change is **adding missing cost / fallback strings** to
`pl.json` + `ru.json` if FOR-06-06 did not already ship them (`marginMatrix.detail.cost`,
`marginMatrix.detail.baseTierFallback`); if present, nothing changes (R6.2).

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of the
system — a formal statement about what the system should do, bridging human-readable specifications
and machine-verifiable correctness guarantees.*

This feature's pure, input-varying core is the **unit worker-service cost (reusing
`MarginCostService`)**, the **`Cost_Value = unit × quantity` fold**, the **worker-type resolution with
base-tier fallback**, the **unavailable-rate propagation and its exclusion from sums**, the
**per-worker / per-work-item aggregation**, and the **FOR-06-06 parity**. The optional controller
startup validation and the project-scope filtering are **not** property targets and are covered by
integration / smoke tests.

### Property 1: Unit cost is the margin-core tier cost on the work-item rate

*For any* work-item rate and *any* worker-type tier, `Worker_Service_Cost = MarginCostService.tierCost(
MarginCostService.baseCost(rate, baseTier), tier)` — i.e. `round(base.tierPct × rate to 0.5 zł) × (1 +
tier.uplift)` — and equals the base cost exactly when the tier is the base tier (uplift 0). No second
formula produces a different number.

**Validates: Requirements 1.2, 2.3**

### Property 2: Cost value scales linearly with reported quantity

*For any* available unit cost and *any* non-negative `totalQuantity`, `Cost_Value = Worker_Service_Cost
× totalQuantity`; doubling the quantity doubles the cost, and a zero quantity yields a zero cost.

**Validates: Requirement 1.3**

### Property 3: Uncategorized / inactive / missing worker resolves to the base tier

*For any* report whose worker has no assigned worker type (null), an inactive type, or no ACTIVE
project membership, `Resolved_Worker_Type` is the base tier, the unit cost equals the base-tier cost,
and `Base_Fallback` is true; *for any* report whose worker has an active assigned type, that type is
used and the flag is false.

**Validates: Requirements 2.1, 2.2, 2.4**

### Property 4: Unavailable rate propagates and is excluded from sums

*For any* report whose `clientUnitPrice` is null or zero, `Worker_Service_Cost` and `Cost_Value` are
**unavailable** (null), never a fabricated zero; such a report is **excluded** from `Per_Worker` and
`Per_WorkItem` sums, so adding a null-rate report leaves every aggregate unchanged.

**Validates: Requirements 1.5, 3.4**

### Property 5: Aggregates equal the sum of their per-report parts (approved only)

*For any* multiset of approved reports, `Per_Worker(w)` equals the sum of `Cost_Value` over that
worker's available-cost approved reports and `Per_WorkItem(m)` the sum over that work item's; a
non-`APPROVED` report contributes to no aggregate; re-assigning one report to a different worker type
changes only that report's contribution and the two aggregates it belongs to.

**Validates: Requirements 3.1, 3.2, 3.3**

### Property 6: FOR-06-06 parity (single formula)

*For any* approved report, the fact cost FOR-06-06 produces after the refactor equals
`WorkerCostService` `Cost_Value(report)` penny-for-penny, with the identical base-tier fallback — so
the refactor changes no observable number.

**Validates: Requirements 4.1, 4.2, 4.3**

### Property 7: Locale parity (only if new strings are added)

*For any* state of the two locale files after this change, `pl.json` and `ru.json` have identical key
sets (the locale-parity test passes), including any new cost / fallback key.

**Validates: Requirement 6.2**

## Error Handling

| Condition | Code / behavior | Requirement |
|-----------|-----------------|-------------|
| Work-item rate null/zero | unit cost + `Cost_Value` = unavailable (null), excluded from sums | R1.5, R3.4 |
| Worker has no / inactive type, or no ACTIVE membership | base-tier fallback, `Base_Fallback` flag set, never an error | R2.1, R2.2 |
| No approved reports on the project | empty read model (empty lines + empty aggregates), not an error | R3.1 |
| Non-`APPROVED` report | excluded from the read model and all aggregates | R3.3 |
| Caller lacks `WORK_REPORTS` READ | 403 via ABAC on the optional endpoint | R5.1, R5.4 |
| `DRAFT` project | no cost surface reachable (stage gate; no approved reports exist) | R5.4 |
| FOR-06-06 refactor | behaviour-preserving; existing tests pass; no new error path | R4.2 |

The derivation is a read-only projection with **no mutating error codes**.

## Testing Strategy

**Dual approach.** Property-based tests verify the universal properties in §Correctness Properties
(the margin-core unit cost, the quantity fold, the base-tier fallback for Uncategorized / inactive /
missing, the unavailable-rate propagation + exclusion, the per-worker / per-work-item aggregation, and
the FOR-06-06 parity). Example-based unit + integration tests cover the service wiring, the optional
endpoint's ABAC + project-scope filtering, and the FOR-06-06 regression.

- **Backend unit / property (jqwik):** `WorkerCostService` pure helpers — `unitServiceCost` reusing
  `MarginCostService` (Property 1), `costValue` quantity fold (Property 2), `tierFor` base-tier
  fallback (Property 3), unavailable-rate propagation + exclusion (Property 4), `byWorker` /
  `byWorkItem` aggregation over approved-only reports (Property 5), and a **parity assertion** that the
  service's `Cost_Value` matches the FOR-06-06 assembler's fact cost (Property 6).
- **Backend integration:** the optional `WorkerCostController` read with `WORK_REPORTS` READ for
  ADMIN / MANAGER / FOREMAN / FINANCIER, a 403 for a role without the grant, and the project-scope
  filter (never returns another project's reports). The FOR-06-06 margin-matrix integration test
  continues to pass after the refactor (regression).
- **Frontend (only if new strings):** the cost / fallback label render and locale parity (Property 7).
- **API `test-cases.md`:** API-level scenarios against the Dockerized app per
  `.kiro/steering/test-cases.md` (worker-cost read model, base-tier fallback, unavailable-rate
  exclusion, per-worker / per-work-item aggregates, ABAC, FOR-06-06 parity), with run-id data
  generation and teardown for repeatability.

**Running tests.** Per `.kiro/steering/test-execution-rules.md`, run only the affected classes with
`--tests` filters redirected to a temp log; never the full suite. Verify pass/fail from the JUnit XML
under `foremen-backend/build/test-results/test/`.
