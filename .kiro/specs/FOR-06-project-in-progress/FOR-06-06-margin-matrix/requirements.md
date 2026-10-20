# Requirements Document

## Introduction

This spec (**FOR-06-06-margin-matrix**) is the **work-margin plan-vs-fact** child of the FOR-06
"project in progress" (execution stage) parent. On an `ACTIVE` (or `COMPLETED`) project it delivers a
**read-only margin matrix** that puts, per work item, the project's **planned (kosztorys) work value
and cost** side by side with the **actually reported work value and cost**, and derives the resulting
**margin (profitability / маржа)** — plan vs fact — where the **cost is computed from the worker type
and the percentage derived from that worker type** (the FOR-05-06 on-the-fly cost model).

The feature answers a single operational question for the manager / financier: *for each work we
planned, how profitable is it turning out to be as it is actually reported and staffed, compared to
what the estimate assumed?* It is a **pure projection** over existing read models — the FOR-05
estimate (kosztorys) on the plan side and the FOR-06-01 approved work reports on the fact side — plus
the FOR-06-03/04 procurement lines for the actual material cost. It introduces **no new managed
entity, no new ABAC resource, and no new dictionary**, adds **no Liquibase changeset**, and emits
**no notifications**.

### The cost model — worker type and the percentage derived from it

The core design decision, confirmed with the product owner, is how work cost (and therefore margin)
is computed. It **reuses the FOR-05-06 on-the-fly cost core verbatim** (`MarginCostService`) — the
single place the labour cost + margin rules live on the backend:

- **Base cost** from the client/offer price: `Base_Cost = round(baseShare × client_unit_price to
  0.5 zł, HALF_UP)`, where `baseShare` is the seeded **base** `WorkerType.tierPct` (0.40 by seed). A
  null/zero price yields an unavailable (null) cost — never a fabricated value.
- **Tier cost** from the worker type: `Tier_Cost = Base_Cost × (1 + uplift)`, where the uplift is the
  worker type's `tierPct` for a non-base tier (e.g. +10 % hired-no-tools, +26.5 % sole-trader,
  +65 % firm) and 0 for the base tier.
- **Labour margin:** `amount = price − Tier_Cost`, `pct = amount ÷ price`.

The **worker type** that drives the fact-side cost is read from the reported worker's **project
membership**: `ProjectMember.workerType` (FOR-05-09, nullable). A worker with **no** assigned worker
type (an *Uncategorized_Worker*, `workerType == null`) falls back to the **base** tier. The **plan
(kosztorys) side** has no single chosen hiring (the estimate is priced for the client, not staffed),
so its cost is computed at the **base** tier as the planning baseline — the same basis FOR-05-06
presents as the base-tier column.

### Plan vs fact per work item

- **Plan side (kosztorys).** For each estimate line (work item), the planned revenue is the frozen
  `EstimateLine.valueNet` (`unitPrice × planned quantity`); the planned labour cost is the base-tier
  `Tier_Cost` applied to the line's planned quantity; the planned margin is `planned revenue −
  planned cost`.
- **Fact side (reported work).** For each work item, the fact aggregates **every approved work
  report** (`WorkReportStatus == APPROVED`) for that work item on the project: the reported revenue
  is `Σ WorkReport.revenueValue`; the reported labour cost is `Σ (Tier_Cost(report.worker's type,
  report.clientUnitPrice) × report.totalQuantity)` — i.e. each report costed at its own worker's tier;
  the reported margin is `reported revenue − reported cost`.
- **Fact material cost (linked purchases).** On top of the labour fact, the matrix folds the
  **actual material cost** from procurement: the sum of `ProcurementLine.lineTotal` over the project's
  **linked** purchase lines (a non-null `matchedMaterialId`, `matchStatus ∈ {AUTO, MANUAL_MATCHED}`).
  Linked material cost is a **project-level** figure (purchases are not attributed to a single work
  item), folded into the **overall** project margin, not into a per-work-item row.
- **Indirect / unrelated direct costs.** Purchase lines that are **not** linked to a planned material
  — the `UnplannedExpenseCategory`-classified lines (reclamation, unforeseen, materials, fuel, other,
  FOR-06-04) and the still-unmatched lines — accumulate in a **separate sum**, broken down by
  category, that is **excluded from any per-work-item margin** but **included in the overall project
  margin** (they are real costs the project bore).

### A read-only projection — no new entity, no new resource, no new dictionary

This spec is deliberately small and additive-free on the schema side. It does **not** introduce a
managed entity, an ABAC resource, or a dictionary, so the `.kiro/steering/entity-creation-rules.md`
checklist produces no resource seed and there is **no Liquibase changeset**:

- it **reads** the FOR-05 estimate (`EstimateLine.unitPrice` / `valueNet` / `quantity`, the work
  item + its work category) as the plan side;
- it **reads** the FOR-06-01 approved work reports (`workItem`, `worker`, `clientUnitPrice`,
  `revenueValue`, `totalQuantity`, `status`) as the labour fact side;
- it **reads** the FOR-06-03/04 reconciled procurement lines (`matchedMaterialId`, `matchStatus`,
  `lineTotal`, `unplannedCategory`) as the material + indirect fact side;
- it **reads** the FOR-05-06 `WorkerType` dictionary (`tierPct`, `base`) and the FOR-05-09
  `ProjectMember.workerType` to resolve each worker's tier;
- it **reuses** the FOR-05-06 pure `MarginCostService` cost core verbatim (no second cost formula);
- it **rides** the existing project-scoped `WORK_REPORTS` ABAC resource (seeded by FOR-06-01) — the
  matrix is a `READ` surface readable by the same roles that read reports;
- it **reuses** the FOR-06-00 `margin` execution tab slot (reserved on `WORK_REPORTS`/READ, pointing
  at `PlaceholderTab`), swapping only the tab's panel.

### Memo — amendment-correct pricing (FOR-06-08)

The FOR-06 overview requires that "prices before / after an amendment apply correctly." Amendments
(FOR-06-08 `EstimateAmendment` / `AmendmentLine`) are **not yet implemented**. This spec reads
`WorkReport.clientUnitPrice` **as-is** — a value already frozen at approval as
`EstimateLine.unitPrice` plus the signed amendment deltas (treated as zero until FOR-06-08 ships, per
FOR-06-01 R6). The matrix therefore becomes amendment-correct **automatically** once FOR-06-08 lands,
with **no amendment dependency in this spec**. The plan-side (kosztorys) revenue uses the current
frozen `EstimateLine` price. **Memo for FOR-06-08:** FOR-06-08 owns applying price-before / price-
after deltas to the estimate line and to the report valuation; this spec does not model or apply
amendment deltas and must be revisited only if FOR-06-08 changes where the frozen price is read from.

### Scope boundaries

- **In scope:**
  - A **project-scoped, read-only margin-matrix read model** combining the FOR-05 estimate (plan) with
    the FOR-06-01 approved work reports (labour fact) and the FOR-06-04 linked purchases (material
    fact), producing, **per work item**: planned quantity / revenue / cost / margin and reported
    quantity / revenue / cost / margin, where cost is the worker-type tier cost (base tier for the
    plan; each report's own worker tier for the fact).
  - A **project-level overall margin** that folds the per-work-item labour fact, the **linked material
    cost**, and the **separate indirect-cost sum** (by category) into a project revenue / cost /
    margin summary.
  - **Work-category grouping** of rows (mirroring the kosztorys grouping) and an **over/under-margin**
    highlight (reported margin % materially below the planned margin %).
  - A **per-work-item detail** listing the contributing approved reports (worker, worker type tier,
    reported quantity, revenue, derived cost) so the fact margin is auditable.
  - A **project-level indirect-cost breakdown** (per `UnplannedExpenseCategory`, plus an
    "unmatched / uncategorized" bucket) shown in the overall dashboard.
  - The swap of the FOR-06-00 `margin` execution tab from `PlaceholderTab` to the real margin-matrix
    panel, without reshaping the `WORKSPACE_TABS` contract.
  - PL + RU localization of every new user-facing string, plus the spec's own tests + `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **Work reporting, the report status machine, or the revenue valuation** (FOR-06-01). This spec
    **reads** approved reports; it creates, edits, approves, and prices none.
  - **Procurement intake / OCR / auto-match / reconciliation** (FOR-06-03 / FOR-06-04). This spec
    **reads** reconciled lines and their unplanned classification; it changes no match and no line.
  - **The materials plan-vs-fact matrix** (FOR-06-05). Material *fulfilment* (% выборки) is FOR-06-05;
    this spec only folds the **money** actual material cost into the margin, it does not reproduce the
    materials matrix.
  - **Worker cost rates as a dedicated entity** (`WorkerServiceRate`, FOR-06-10) and **payroll**
    (FOR-06-11). This spec computes labour cost on the fly from the worker type + the FOR-05-06 cost
    core; it persists no worker rate and computes no payout.
  - **Amendments** (FOR-06-08). This spec reads the already-frozen `clientUnitPrice` and does not
    model, apply, or display amendment deltas (see the memo above).
  - **Editing the estimate, prices, worker types, or worker-type assignments.** All plan and tier
    inputs are read verbatim.
  - **Defining a new ABAC resource / dictionary / managed entity** — none is introduced; **no
    Liquibase changeset.**
  - **Notifications** — none are emitted (see Requirement 8).
  - **Changing `stageOf` / `resolveWorkspaceTabs` / the `WORKSPACE_TABS` contract shape** — the
    `margin` tab slot on `WORK_REPORTS`/READ was reserved by FOR-06-00; this spec swaps the panel only.

### Relationship to existing specs (source of truth)

- **FOR-05 estimate (FOR-05-03 / FOR-05-05)** — owns `EstimateEntity` / `EstimateLineEntity`: the
  frozen `unitPrice`, the derived `quantity` and `valueNet`, and the `workItem` (→ `workCategory`).
  This spec reads the estimate lines as the **plan side** (planned revenue, quantity, and the base-
  tier planned cost).
- **FOR-05-06-packages-margins** — owns the `WorkerType` dictionary (`tierPct`, `base`, `orderNo`,
  `active`), the per-material `cost_net`, and the pure `MarginCostService` cost + margin core
  (`baseCost` / `tierCost` / `labourMargin`). This spec **reuses `MarginCostService` verbatim** and
  reads the `WorkerType` tiers; it adds no second cost formula.
- **FOR-05-09-team-selection** — owns `ProjectMember.workerType` (nullable; null =
  Uncategorized_Worker). This spec resolves each reported worker's tier from their project membership,
  falling back to the base tier when null.
- **FOR-06-01-work-reporting** — owns `WorkReport` / `WorkReportRoom`: `workItem`, `worker`,
  `clientUnitPrice`, `totalQuantity`, `revenueValue`, and the `APPROVED` status. This spec reads
  approved reports as the **labour fact side** and the `WORK_REPORTS` ABAC resource it seeds (the
  matrix rides `WORK_REPORTS`/READ).
- **FOR-06-03 / FOR-06-04** — own `ProcurementDocument` / `ProcurementLine`: `matchedMaterialId`,
  `matchStatus`, `lineTotal`, and the `unplannedCategory` classification. This spec reads the linked
  lines as the **actual material cost** and the unlinked/unplanned lines as the **separate indirect
  cost**.
- **FOR-06-00-execution-foundation** — reserved the `margin` execution tab on `WORK_REPORTS`/READ
  (pointing at `PlaceholderTab`) and the `UnplannedExpenseCategory` dictionary. This spec swaps that
  tab's panel and reads the dictionary for the indirect-cost breakdown labels.
- **FOR-06-08-amendments** (future) — will own amendment price deltas; this spec reads the already-
  frozen price and becomes amendment-correct automatically (see the memo).
- **FOR-05-07-offer-approval Requirement 13** — the generic in-app `Notification_Service`. This spec
  is **not** a consumer (Requirement 8): it emits no notifications.
- **`.kiro/steering/entity-creation-rules.md`** — because this spec introduces **no** new managed
  entity, resource, or dictionary (it is a read-only projection over existing read models), the new-
  entity checklist adds nothing; there is **no Liquibase changeset**.

## Glossary

- **WorkItem_Row** — a row of the matrix, one per distinct work item on the project's estimate: its
  localized name, its `WorkCategory` (for grouping), its unit, the plan figures, the fact figures, and
  the contributing approved reports.
- **WorkerType_Tier** (reused, FOR-05-06) — a `WorkerType` row with `tierPct` (base = offer share
  0.40; non-base = uplift on base) and the `base` flag. The source of the "percentage derived from
  worker type".
- **Worker_Tier(report)** — the `WorkerType` of the report's `worker` resolved via
  `ProjectMember.workerType` on the project; **base tier** when the member has no assigned type
  (Uncategorized_Worker).
- **Tier_Cost(price, tier)** (reused, FOR-05-06 `MarginCostService`) — `round(base.tierPct × price to
  0.5 zł) × (1 + tier.uplift)`; null/unavailable when the price is null/zero.
- **Plan_Revenue(row)** — `EstimateLine.valueNet` (`unitPrice × planned quantity`); the plan-side
  revenue.
- **Plan_Cost(row)** — `Tier_Cost(unitPrice, base) × planned quantity`; the base-tier planned labour
  cost. Null when the price is unavailable.
- **Plan_Margin(row)** — `Plan_Revenue − Plan_Cost` (amount) and `÷ Plan_Revenue` (pct); unavailable
  when either input is unavailable.
- **Approved_Reports(row)** — the set of `WorkReport`s with `workItem == row.workItem`, `status ==
  APPROVED`, on the project. The fact-contribution set.
- **Fact_Revenue(row)** — `Σ WorkReport.revenueValue` over `Approved_Reports(row)`.
- **Fact_Cost(row)** — `Σ (Tier_Cost(report.clientUnitPrice, Worker_Tier(report)) ×
  report.totalQuantity)` over `Approved_Reports(row)`; each report costed at its own worker's tier.
- **Fact_Margin(row)** — `Fact_Revenue − Fact_Cost` (amount) and `÷ Fact_Revenue` (pct); 0-report rows
  have `Fact_Revenue = Fact_Cost = 0` and an unavailable margin pct.
- **Linked_Material_Cost** — `Σ ProcurementLine.lineTotal` over the project's lines with a non-null
  `matchedMaterialId` and `matchStatus ∈ {AUTO, MANUAL_MATCHED}`. A **project-level** actual material
  cost.
- **Indirect_Cost** — `Σ ProcurementLine.lineTotal` over the project's lines **not** linked to a
  planned material (`matchStatus ∈ {UNMATCHED, MANUAL_UNMATCHED}` or a null `matchedMaterialId`),
  broken down by `UnplannedExpenseCategory` (plus an "uncategorized" bucket for unmatched lines with
  no category). A **project-level** cost, excluded from per-work-item margins, included in the overall
  margin.
- **Overall_Margin** — the project summary: `Overall_Revenue = Σ Fact_Revenue(row)`;
  `Overall_Cost = Σ Fact_Cost(row) + Linked_Material_Cost + Indirect_Cost`;
  `Overall_Margin = Overall_Revenue − Overall_Cost`.
- **Margin_Matrix** — the project-wide read model: work-category groups, one `WorkItem_Row` per work
  item with its plan and fact margin, the per-row contributing reports, and the overall dashboard
  (labour + linked material + indirect, with the indirect breakdown).

## Requirements

### Requirement 1 — Margin-matrix read model (plan × fact per work item)

**User Story:** As a manager or financier, I want, for every work we planned, to see the planned
margin against the margin we are actually achieving as the work is reported and staffed, so I know our
real profitability per work.

#### Acceptance Criteria

1. THE SYSTEM SHALL expose a **project-scoped, read-only** margin-matrix read model whose rows are
   exactly the project's estimate work items (one `WorkItem_Row` per distinct work item on the
   estimate), each carrying the localized work name, its `WorkCategory`, and its unit, read verbatim
   from the FOR-05 estimate.
2. FOR each row THE SYSTEM SHALL compute the **plan** side from the estimate: `Plan_Revenue =
   EstimateLine.valueNet`, `Plan_Cost = Tier_Cost(unitPrice, base tier) × planned quantity` (the
   base-tier labour cost), and `Plan_Margin = Plan_Revenue − Plan_Cost` (amount and, where
   `Plan_Revenue ≠ 0`, percentage).
3. FOR each row THE SYSTEM SHALL compute the **fact** side from the approved reports: `Fact_Revenue =
   Σ WorkReport.revenueValue`, `Fact_Cost = Σ (Tier_Cost(report.clientUnitPrice, Worker_Tier(report))
   × report.totalQuantity)` over the work item's `APPROVED` reports, and `Fact_Margin = Fact_Revenue −
   Fact_Cost` (amount and, where `Fact_Revenue ≠ 0`, percentage).
4. THE `Worker_Tier(report)` SHALL be the `WorkerType` of the report's worker resolved via that
   worker's `ProjectMember.workerType` on the project; WHERE the member has **no** assigned worker
   type (Uncategorized_Worker, null) THE base tier SHALL be used.
5. WHERE a work item has **no** approved reports THE row's `Fact_Revenue` and `Fact_Cost` SHALL be `0`
   and its `Fact_Margin` percentage SHALL be unavailable (rendered `—`), never a divide-by-zero; the
   row SHALL still be shown with its plan side.
6. WHERE the client/offer price of a row (or a report) is null or zero THE corresponding `Tier_Cost`
   and margin SHALL be **unavailable** (rendered `—`), never a fabricated zero-derived value, and the
   row SHALL be excluded from the aggregate margin denominators (Requirement 3).

### Requirement 2 — Worker-type cost model (reuse FOR-05-06)

**User Story:** As a financier, I want the reported work cost derived from the worker type actually
staffing it and the same tier percentages the estimate used, so plan and fact are compared on one
cost model.

#### Acceptance Criteria

1. THE SYSTEM SHALL compute every labour cost through the FOR-05-06 pure `MarginCostService` core —
   `Base_Cost = round(base.tierPct × price to 0.5 zł, HALF_UP)` and `Tier_Cost = Base_Cost × (1 +
   tier.uplift)` — reusing it verbatim and adding **no** second cost formula.
2. THE **base tier** SHALL be the `WorkerType` row flagged `base` (its `tierPct` is the offer share,
   0.40 by seed); a **non-base tier's** `tierPct` SHALL be applied as the uplift on base.
3. THE **plan-side** cost of every row SHALL use the **base tier** (the project is priced for the
   client, not staffed; the base tier is the planning baseline), consistent with FOR-05-06's base-tier
   column.
4. THE **fact-side** cost of a report SHALL use the report's own `Worker_Tier(report)` (Requirement
   1.4), so two reports on the same work item staffed by different worker types contribute different
   costs.
5. WHEN a referenced worker type is inactive or the worker's membership is missing THE resolution
   SHALL fall back to the **base tier** (never an error, never a fabricated cost), and the fallback
   SHALL be surfaced in the per-row detail (Requirement 4) for transparency.

### Requirement 3 — Material cost, indirect cost, and the overall margin

**User Story:** As a manager, I want the project's overall margin to count not just labour but the
materials we actually bought and the unplanned direct costs we bore, so the bottom line is real.

#### Acceptance Criteria

1. THE SYSTEM SHALL compute a project-level **`Linked_Material_Cost`** = `Σ ProcurementLine.lineTotal`
   over the project's purchase lines with a non-null `matchedMaterialId` and `matchStatus ∈ {AUTO,
   MANUAL_MATCHED}` (only linked, bought materials count), folded into the overall cost — not into any
   per-work-item row.
2. THE SYSTEM SHALL compute a project-level **`Indirect_Cost`** = `Σ ProcurementLine.lineTotal` over
   the project's purchase lines **not** linked to a planned material (`matchStatus ∈ {UNMATCHED,
   MANUAL_UNMATCHED}` or a null `matchedMaterialId`), accumulated as a **separate sum broken down by
   `UnplannedExpenseCategory`** (reclamation / unforeseen / materials / fuel / other, FOR-06-04) with
   an **"uncategorized"** bucket for unmatched lines carrying no category.
3. THE indirect cost SHALL be **excluded** from every per-work-item margin (Requirement 1) but
   **included** in the overall project cost (it is a real cost the project bore).
4. THE SYSTEM SHALL expose a project-level **overall dashboard**: `Overall_Revenue = Σ Fact_Revenue`,
   `Overall_Cost = Σ Fact_Cost + Linked_Material_Cost + Indirect_Cost`, `Overall_Margin =
   Overall_Revenue − Overall_Cost` (amount and, where `Overall_Revenue ≠ 0`, percentage), together
   with the labour/material/indirect split and the indirect breakdown by category.
5. THE SYSTEM SHALL also expose the **plan-side overall totals** (`Σ Plan_Revenue`, `Σ Plan_Cost`,
   `Σ Plan_Margin`) so the dashboard shows plan vs fact at the project level, excluding rows whose
   plan revenue is unavailable from the plan denominators (Requirement 1.6).

### Requirement 4 — Work-category grouping, per-row detail, and margin highlighting

**User Story:** As a financier, I want the matrix grouped the way the kosztorys is and to drill into
exactly which reports produced a work's fact margin, so I can audit and spot works slipping below
plan.

#### Acceptance Criteria

1. THE read model SHALL group rows by **work category** (the work item's `WorkCategory`, localized
   `ru → pl → code`), preserving the estimate's category ordering, with per-category subtotals (plan
   and fact revenue / cost / margin).
2. THE SYSTEM SHALL carry, per row, the list of **contributing approved reports** — each with the
   worker (localized name), the resolved `Worker_Tier` (name + whether it was a base-tier fallback),
   the reported quantity, the revenue value, and the derived tier cost — so the fact margin is
   auditable (Requirement 2.5).
3. THE SYSTEM SHALL flag a row **under-margin** WHEN its `Fact_Margin` percentage is available and
   materially below its `Plan_Margin` percentage (a configurable threshold, default 5 percentage
   points), so a manager can spot works turning less profitable than planned; the flag SHALL be
   unavailable (not raised) when either margin percentage is unavailable.
4. THE per-row detail SHALL make clear that the fact cost mixes worker-type tiers by showing each
   report's own resolved tier, and the row's fact cost SHALL equal the sum of the detail rows' derived
   costs (no hidden aggregation).

### Requirement 5 — Margin tab and matrix UI (frontend)

**User Story:** As a manager or financier, I want a Margin tab on the execution-stage project
workspace showing the plan-vs-fact margin matrix, so I can monitor profitability.

#### Acceptance Criteria

1. THE SYSTEM SHALL render a **read-only** margin-matrix panel on the execution-stage `margin` tab,
   with work-category-grouped rows and per-row: planned quantity / revenue / cost / margin and
   reported quantity / revenue / cost / margin, an under-margin highlight (Requirement 4.3), and a
   disclosure to open the per-row report detail.
2. THE panel SHALL render a **project-level overall dashboard** above the matrix (plan vs fact
   revenue / cost / margin, the labour / linked-material / indirect split, and the indirect breakdown
   by category) (Requirement 3).
3. THE panel SHALL open a **per-row detail** (Requirement 4.2) listing the contributing approved
   reports with the worker, the resolved worker-type tier (base-tier fallback marked), the reported
   quantity, the revenue, and the derived cost.
4. THE panel SHALL be **read-only for everyone** — there are no write controls; on a `COMPLETED`
   project it renders identically (history view). The tab is reachable only on `ACTIVE` / `COMPLETED`
   (gated via `WORK_REPORTS`/READ + the FOR-06-00 stage resolver) and unreachable on `DRAFT`.
5. THE panel SHALL render localized loading / empty (no estimate work items) / error states and SHALL
   never surface a raw i18n key; an unavailable margin SHALL render as a localized `—`.

### Requirement 6 — Stage gating and ABAC (reuse WORK_REPORTS)

**User Story:** As a security owner, I want the matrix available only on active/completed projects to
permitted roles, reusing the existing work-reports resource.

#### Acceptance Criteria

1. THE SYSTEM SHALL **not** define a new ABAC resource; the matrix read endpoint SHALL be guarded by
   the existing project-scoped `WORK_REPORTS` resource (seeded by FOR-06-01) as a `READ` operation.
   The plan-side figures are read through the FOR-05 `ESTIMATE` read model, readable by the same
   roles.
2. THE matrix controller SHALL carry `@PermissionResource("WORK_REPORTS")`, every in-scope handler
   SHALL carry a matching `@PermissionOperation` (or `@RequiresPermission(... READ)`), and the
   application SHALL start without a `PermissionAnnotationValidator` failure.
3. THE backing service SHALL be **project-scoped** (`getProjectIdPath()` resolving from the underlying
   entity to its owning project id), so reads are automatically project-filtered and never return
   another project's data.
4. WHEN a project is `ACTIVE` or `COMPLETED` THEN the matrix SHALL be readable by callers with
   `WORK_REPORTS` READ; WHEN `DRAFT` THEN no margin-matrix surface SHALL be reachable.
5. WHEN the `WORK_REPORTS` resource is seeded (by FOR-06-01) THEN this spec SHALL swap the FOR-06-00
   `margin` execution tab's `lazy` from `PlaceholderTab` to the margin-matrix panel without reshaping
   the `WORKSPACE_TABS` contract.

### Requirement 7 — Internationalization completeness

#### Acceptance Criteria

1. THE backend SHALL add **no** `nameRU`/`namePL` i18n columns and **no** new persisted entity (the
   matrix is a derived read model); the work name, work category, worker name, worker-type tier name,
   and the unplanned-category name SHALL be read from the existing entities with the `ru → pl → code`
   fallback, adding no new i18n column.
2. THE frontend SHALL add every new user-facing string — the matrix page/panel title, the column
   headers (planned qty / revenue / cost / margin, reported qty / revenue / cost / margin), the
   category group framing and subtotals, the under-margin highlight label, the overall-dashboard
   labels (plan vs fact, labour / material / indirect split, indirect-category breakdown), the per-row
   detail labels and columns (worker, tier, base-tier-fallback mark, reported qty, revenue, cost), the
   unavailable `—` label, and the loading / empty / error states — to **both** `src/locales/pl.json`
   and `src/locales/ru.json` with real PL and RU values (no English fallback), and the locale-parity
   test SHALL pass.
3. THE frontend SHALL never surface a raw i18n key to the user. The `workspace.tab.margin` label
   (already seeded by FOR-06-00) SHALL be reused unchanged.

### Requirement 8 — No notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** user-facing notifications in this spec. The margin matrix is a
   **read-only analytics surface** — it changes no domain state and has no cross-role handoff (a
   manager/financier opens the Margin tab to read profitability). Any future notify-on-threshold
   behaviour (e.g. "work turning unprofitable"), if introduced, SHALL emit via the generic
   per-recipient `Notification_Service` (FOR-05-07 Requirement 13) rather than re-implementing
   delivery.

### Requirement 9 — Regression and test artifacts

#### Acceptance Criteria

1. WHEN this spec ships THEN the FOR-05 estimate / FOR-05-06 margins (plan side + `MarginCostService`),
   the FOR-06-01 work reporting + `WORK_REPORTS` resource (fact side), and the FOR-06-03/04
   procurement (material + indirect fact) SHALL continue to pass (regression): this spec reads them and
   changes none.
2. THE spec SHALL ship its own backend unit + property tests (the per-row plan/fact margin folds, the
   worker-type tier cost reusing `MarginCostService`, the base-tier fallback for Uncategorized_Workers,
   the linked-material-cost fold, the indirect-cost separate sum by category and its inclusion in the
   overall margin only, the unavailable-price propagation, and the overall dashboard folds), frontend
   unit tests (the matrix render with category groups and subtotals, the plan-vs-fact per-row margin,
   the under-margin highlight, the per-row report detail with the tier fallback mark, the overall
   dashboard with the indirect breakdown, the read-only / `COMPLETED` rendering, resolver fixtures,
   locale parity), and an API-level `test-cases.md` run against the Dockerized app.
