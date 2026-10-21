# Requirements Document

## Introduction

This spec (**FOR-06-10-worker-cost-rates**) is the **worker cost-side** child of the FOR-06 "project
in progress" (execution stage) parent. It delivers the **single, reusable derivation of a worker's
service cost** (`Worker_Service_Rate`) for the work actually performed on an `ACTIVE` (or
`COMPLETED`) project — the **cost side** of the work report that FOR-06-01 (work reporting)
deliberately left out, and that **FOR-06-11 (payroll)** and **FOR-06-06 (margin matrix)** consume.

The core decision, confirmed with the product owner, is that the worker cost is **not a stored rate
dictionary**. There is **no `WorkerServiceRate` managed entity, no CRUD, and no rate card to
maintain.** The cost is **always derived on the fly** for a given reported work from:

1. the reported worker's **`WorkerType`** — resolved from the worker's **`ProjectMember.workerType`**
   on the project (fallback to the worker's own/default worker type, and ultimately to the **base
   tier** for an Uncategorized worker); and
2. the **current project's work-item rate** — the frozen `clientUnitPrice` / `EstimateLine.unitPrice`
   that already prices that work for the project;

combined through the FOR-05-06 pure cost core (`MarginCostService`) verbatim: take the **percentage
from the worker type** and apply it to the work-item rate — no second cost formula.

So FOR-06-10 is a **project-scoped, read-only cost-derivation service** (the `Worker_Cost_Service`),
not an entity. It **extracts and formalizes** the worker-cost derivation that FOR-06-06 computes
inline today into one place both payroll and the margin matrix call, so cost is computed **once**,
identically, penny-for-penny. It introduces **no new managed entity, no new ABAC resource, and no new
dictionary**, adds **no Liquibase changeset**, and emits **no notifications**.

### The cost model — the percentage from the worker type × the project work-item rate

The worker service cost **reuses the FOR-05-06 on-the-fly cost core verbatim** (`MarginCostService`) —
the single place the labour cost rules live on the backend:

- **Base cost** from the project's work-item rate (the client/offer price for that work):
  `Base_Cost = round(baseShare × work_item_rate to 0.5 zł, HALF_UP)`, where `baseShare` is the seeded
  **base** `WorkerType.tierPct` (0.40 by seed). A null/zero rate yields an **unavailable** (null)
  cost — never a fabricated value.
- **Tier cost** from the worker type: `Worker_Service_Cost = Base_Cost × (1 + uplift)`, where the
  uplift is the resolved worker type's `tierPct` for a non-base tier (e.g. +10 % hired-no-tools,
  +26.5 % sole-trader, +65 % firm) and 0 for the base tier.

The **work-item rate** is the per-unit price already carried by the reported work:
`WorkReport.clientUnitPrice` (FOR-06-01, frozen at approval as `EstimateLine.unitPrice` plus signed
amendment deltas, deltas treated as zero until FOR-06-08). The **cost of a reported work** is the
unit `Worker_Service_Cost` multiplied by the reported quantity: `Cost_Value = Worker_Service_Cost ×
WorkReport.totalQuantity`.

### Deriving the worker type

The **`WorkerType`** that drives the cost is resolved, in order:

1. the reported worker's **`ProjectMember.workerType`** on the project (FOR-05-09, nullable);
2. WHERE the member has **no** assigned worker type (Uncategorized worker, null), or the membership is
   missing, or the referenced worker type is inactive → the **base tier** (`WorkerType.base == true`).

The resolution is **never an error and never a fabricated cost** — it always lands on a concrete tier
(worst case the base tier), and the fallback is **surfaced** to consumers (so the margin matrix and
payroll can show "base-tier fallback" transparently).

### A read-only derivation service — no new entity, no new resource, no new dictionary

This spec is deliberately small and additive-free on the schema side. It does **not** introduce a
managed entity, an ABAC resource, or a dictionary, so the `.kiro/steering/entity-creation-rules.md`
checklist produces no resource seed and there is **no Liquibase changeset**:

- it **reads** the FOR-05-06 `WorkerType` dictionary (`tierPct`, `base`, `active`) and the FOR-05-09
  `ProjectMember.workerType` to resolve each worker's tier;
- it **reads** the FOR-06-01 work report's project work-item rate (`clientUnitPrice`) and reported
  quantity (`totalQuantity`);
- it **reuses** the FOR-05-06 pure `MarginCostService` cost core verbatim (no second cost formula);
- it **rides** the existing project-scoped `WORK_REPORTS` ABAC resource (seeded by FOR-06-01) — the
  cost figures are a `READ` surface readable by the same roles that read reports, plus FINANCIER;
- it persists **no worker rate**, computes **no payout** (that is FOR-06-11), and renders **no matrix**
  (that is FOR-06-06).

### Relationship to FOR-06-06 (margin matrix) and FOR-06-11 (payroll)

- **FOR-06-06-margin-matrix** currently computes the fact-side labour cost inline
  (`Tier_Cost(report.clientUnitPrice, Worker_Tier(report)) × report.totalQuantity`). This spec
  **extracts that computation into the shared `Worker_Cost_Service`**; FOR-06-06 is then refactored to
  call the service (no behaviour change — same `MarginCostService` core, same base-tier fallback,
  penny-for-penny identical), so there is **one** cost-side formula, not two.
- **FOR-06-11-payroll** consumes this service to compute each worker's payout = Σ over the worker's
  `APPROVED` reports of `Cost_Value`. Payroll logic (period aggregation, payout documents, statuses)
  is **out of scope** here; FOR-06-10 provides only the per-report / per-worker cost figure.

### Memo — amendment-correct pricing (FOR-06-08)

Amendments (FOR-06-08 `EstimateAmendment` / `AmendmentLine`) are **not yet implemented**. This spec
reads `WorkReport.clientUnitPrice` **as-is** — a value already frozen at approval as
`EstimateLine.unitPrice` plus the signed amendment deltas (treated as zero until FOR-06-08 ships, per
FOR-06-01 R6). The worker cost therefore becomes amendment-correct **automatically** once FOR-06-08
lands, with **no amendment dependency in this spec**. **Memo for FOR-06-08:** this spec reads the
frozen per-unit rate from `WorkReport.clientUnitPrice`; it must be revisited only if FOR-06-08 changes
where that frozen rate is read from.

### Scope boundaries

- **In scope:**
  - A **project-scoped, read-only `Worker_Cost_Service`** that, given a reported work (or a worker +
    project + work-item rate), derives the worker's `WorkerType` (membership → base-tier fallback),
    computes the unit `Worker_Service_Cost` via `MarginCostService` verbatim, and the reported
    `Cost_Value = Worker_Service_Cost × totalQuantity`.
  - The **worker-type resolution** rule (membership `workerType` → base-tier fallback for
    Uncategorized / missing / inactive), surfaced to consumers (the resolved tier + a base-fallback
    flag).
  - A **read endpoint / read model** exposing, per `APPROVED` work report on a project, the resolved
    worker type, the unit worker-service cost, and the report cost value (the cost-side companion to
    FOR-06-01's revenue-side values), so the cost is auditable and consumable by payroll and the
    margin matrix.
  - The **refactor of FOR-06-06** to call `Worker_Cost_Service` instead of its inline fact-cost
    computation (no behaviour change; the single-formula guarantee).
  - PL + RU localization of any new user-facing string surfaced by the cost read model (the resolved
    tier name and the base-fallback marker reuse existing entity i18n and FOR-06-06 labels), plus the
    spec's own tests + `test-cases.md`.
- **Out of scope (Non-Goals):**
  - **A `WorkerServiceRate` managed entity / dictionary / rate card** — explicitly **not** built; the
    cost is always derived from the worker type and the project work-item rate (product-owner
    decision).
  - **Payroll** (FOR-06-11) — period aggregation, payout documents, payout statuses, and any payout
    approval. This spec supplies only the per-report / per-worker cost figure.
  - **The margin matrix UI and its plan-side / material / indirect folds** (FOR-06-06) — this spec
    provides the labour **cost** derivation the matrix consumes; it renders no matrix and computes no
    revenue, material, or indirect cost.
  - **Work reporting, the report status machine, or the revenue valuation** (FOR-06-01). This spec
    **reads** approved reports (their `clientUnitPrice` / `totalQuantity` / `worker`); it creates,
    edits, approves, and prices none, and persists no cost column on `WorkReport`.
  - **Editing worker types, tier percentages, or worker-type assignments** (FOR-05-06 / FOR-05-09).
    All tier and membership inputs are read verbatim.
  - **Amendments** (FOR-06-08). This spec reads the already-frozen `clientUnitPrice` (see the memo).
  - **Defining a new ABAC resource / dictionary / managed entity** — none is introduced; **no
    Liquibase changeset.**
  - **Notifications** — none are emitted (see Requirement 7).

### Relationship to existing specs (source of truth)

- **FOR-05-06-packages-margins** — owns the `WorkerType` dictionary (`tierPct`, `base`, `orderNo`,
  `active`) and the pure `MarginCostService` cost core (`baseCost` / `tierCost`). This spec **reuses
  `MarginCostService` verbatim** and reads the `WorkerType` tiers; it adds no second cost formula.
- **FOR-05-09-team-selection** — owns `ProjectMember.workerType` (nullable; null = Uncategorized
  worker). This spec resolves each reported worker's tier from their project membership, falling back
  to the base tier when null / missing / inactive.
- **FOR-06-01-work-reporting** — owns `WorkReport` / `WorkReportRoom`: `workItem`, `worker`,
  `clientUnitPrice` (the project work-item rate), `totalQuantity`, `revenueValue`, and the `APPROVED`
  status, and the `WORK_REPORTS` ABAC resource. The `WorkReport` Javadoc explicitly defers the cost
  side (`workerUnitPrice` / `costValue` / `margin`) to FOR-06-10/11/06 — this spec fills that seam.
  The cost rides `WORK_REPORTS`/READ.
- **FOR-06-06-margin-matrix** — currently computes the fact-side labour cost inline; this spec
  extracts it into `Worker_Cost_Service` and FOR-06-06 is refactored to call it.
- **FOR-06-11-payroll** (next) — consumes `Worker_Cost_Service` to compute payouts from approved
  reports.
- **FOR-05-07-offer-approval Requirement 13** — the generic in-app `Notification_Service`. This spec
  is **not** a consumer (Requirement 7): it emits no notifications.
- **`.kiro/steering/entity-creation-rules.md`** — because this spec introduces **no** new managed
  entity, resource, or dictionary (it is a read-only derivation over existing read models), the
  new-entity checklist adds nothing; there is **no Liquibase changeset**.

## Glossary

- **Work_Item_Rate** — the per-unit client price already carried by the reported work:
  `WorkReport.clientUnitPrice` (frozen at approval from `EstimateLine.unitPrice` + amendment deltas).
  The "current project's workItem rate" the cost is based on.
- **WorkerType_Tier** (reused, FOR-05-06) — a `WorkerType` row with `tierPct` (base = offer share
  0.40; non-base = uplift on base) and the `base` flag. The source of the "percentage from the worker
  type".
- **Resolved_Worker_Type(report)** — the `WorkerType` of the report's `worker` resolved via
  `ProjectMember.workerType` on the project; the **base tier** when the member has no assigned type
  (Uncategorized), the membership is missing, or the referenced type is inactive.
- **Base_Cost(rate)** (reused, FOR-05-06 `MarginCostService`) — `round(base.tierPct × rate to 0.5 zł,
  HALF_UP)`; null/unavailable when the rate is null/zero.
- **Worker_Service_Cost(rate, tier)** — the **unit** cost: `Base_Cost(rate) × (1 + tier.uplift)`;
  unavailable (null) when `Base_Cost` is unavailable. The "себестоимость работника" per unit of work.
- **Cost_Value(report)** — `Worker_Service_Cost(report.clientUnitPrice, Resolved_Worker_Type(report))
  × report.totalQuantity`; the reported work's total worker cost. Unavailable when the unit cost is
  unavailable.
- **Base_Fallback(report)** — true when `Resolved_Worker_Type(report)` landed on the base tier via
  fallback (Uncategorized / missing / inactive) rather than an explicit membership tier; surfaced to
  consumers for transparency.
- **Worker_Cost_Service** — the project-scoped, read-only backend service this spec delivers: resolves
  the worker type, computes `Worker_Service_Cost` and `Cost_Value`, and is called by FOR-06-06 and
  FOR-06-11. The single cost-side seam.

## Requirements

### Requirement 1 — Worker service cost derivation (the single cost-side seam)

**User Story:** As a financier, I want each reported work's worker cost derived once, from the
worker's type and the project's work-item rate, so payroll and the margin matrix use one identical
cost figure.

#### Acceptance Criteria

1. THE SYSTEM SHALL provide a **project-scoped, read-only `Worker_Cost_Service`** that, given a
   reported work (a `WorkReport`, or equivalently a worker + project + work-item rate + quantity),
   returns the resolved worker type, the unit `Worker_Service_Cost`, and the total `Cost_Value`.
2. THE unit cost SHALL be `Worker_Service_Cost = Base_Cost(Work_Item_Rate) × (1 +
   Resolved_Worker_Type.uplift)`, where `Base_Cost = round(base.tierPct × Work_Item_Rate to 0.5 zł,
   HALF_UP)` — computed through the FOR-05-06 `MarginCostService` core **verbatim**, with **no** second
   cost formula.
3. THE total cost of a reported work SHALL be `Cost_Value = Worker_Service_Cost × totalQuantity`,
   where `totalQuantity` is the FOR-06-01 derived reported quantity.
4. THE `Work_Item_Rate` SHALL be the report's `WorkReport.clientUnitPrice` (the project work-item
   rate frozen at approval); the service SHALL NOT re-derive or re-price it.
5. WHERE the `Work_Item_Rate` is null or zero THE `Worker_Service_Cost` and `Cost_Value` SHALL be
   **unavailable** (null), never a fabricated zero-derived value; consumers SHALL render this as a
   localized `—`.

### Requirement 2 — Worker-type resolution and base-tier fallback

**User Story:** As a financier, I want the cost tied to the worker type that actually staffs the work,
with a safe fallback when none is assigned, so cost is always computable and the fallback is visible.

#### Acceptance Criteria

1. THE `Resolved_Worker_Type(report)` SHALL be the `WorkerType` of the report's `worker` resolved via
   that worker's **`ProjectMember.workerType`** on the project (FOR-05-09).
2. WHERE the member has **no** assigned worker type (Uncategorized, null), the membership is missing,
   or the referenced worker type is **inactive**, THE resolution SHALL fall back to the **base tier**
   (`WorkerType.base == true`) — never an error, never a fabricated cost.
3. THE **base tier** SHALL be the `WorkerType` row flagged `base` (its `tierPct` is the offer share,
   0.40 by seed); a **non-base tier's** `tierPct` SHALL be applied as the uplift on base.
4. THE service SHALL surface, per report, the **resolved tier** (name + code) and a **`Base_Fallback`
   flag** indicating whether the base tier was used via fallback, so consumers can display it
   transparently.
5. THE worker-type resolution SHALL be **deterministic and side-effect free** — it reads the
   membership and dictionary and computes; it writes nothing and never mutates the report, the
   membership, or the worker type.

### Requirement 3 — Cost read model for consumers

**User Story:** As the payroll and margin-matrix features, I want a stable read model of per-report
worker cost, so I can aggregate payouts and fact margins without duplicating the cost formula.

#### Acceptance Criteria

1. THE SYSTEM SHALL expose a **project-scoped read model** that, for the project's `APPROVED` work
   reports, yields per report: the `worker`, the `workItem`, the `Resolved_Worker_Type` (name/code +
   `Base_Fallback`), the `Work_Item_Rate`, the `totalQuantity`, the unit `Worker_Service_Cost`, and the
   total `Cost_Value` (the cost-side companion to FOR-06-01's revenue-side `clientUnitPrice` /
   `revenueValue`).
2. THE read model SHALL be consumable **per worker** (grouped, for payroll: Σ `Cost_Value` over the
   worker's approved reports) and **per work item** (for the margin matrix: Σ `Cost_Value` over the
   work item's approved reports), without changing the underlying per-report derivation.
3. THE read model SHALL include **only `APPROVED`** reports in cost aggregates (`SUBMITTED` /
   `CHANGES_REQUESTED` / `REJECTED` reports carry no confirmed cost), consistent with FOR-06-06's fact
   side and FOR-06-11's payout basis.
4. WHERE a report's cost is unavailable (Requirement 1.5) THE read model SHALL mark that report's cost
   unavailable and **exclude** it from any cost sum, never contributing a fabricated zero.

### Requirement 4 — Reuse by the margin matrix (single formula)

**User Story:** As a maintainer, I want exactly one worker-cost formula, so the margin matrix and
payroll can never diverge.

#### Acceptance Criteria

1. THE FOR-06-06 margin matrix fact-side labour cost SHALL be **refactored to call
   `Worker_Cost_Service`** instead of computing the tier cost inline.
2. THE refactor SHALL be **behaviour-preserving**: for every approved report the matrix's fact cost
   SHALL equal `Cost_Value(report)` from the service, penny-for-penny, with the identical base-tier
   fallback for Uncategorized workers; existing FOR-06-06 tests SHALL continue to pass.
3. AFTER the refactor there SHALL be **no** second worker-cost formula in the codebase — the inline
   `Tier_Cost(...) × totalQuantity` in FOR-06-06 SHALL be removed in favour of the service call.

### Requirement 5 — ABAC and stage gating (reuse WORK_REPORTS)

**User Story:** As a security owner, I want the cost figures available only on active/completed
projects to permitted roles, reusing the existing work-reports resource.

#### Acceptance Criteria

1. THE SYSTEM SHALL **not** define a new ABAC resource; any cost read endpoint SHALL be guarded by the
   existing project-scoped `WORK_REPORTS` resource (seeded by FOR-06-01) as a `READ` operation.
2. ANY new controller SHALL carry `@PermissionResource("WORK_REPORTS")`, every in-scope handler SHALL
   carry a matching `@PermissionOperation` (or `@RequiresPermission(... READ)`), and the application
   SHALL start without a `PermissionAnnotationValidator` failure.
3. THE backing service SHALL be **project-scoped** (`getProjectIdPath()` resolving from the underlying
   `WorkReport` to its owning project id), so reads are automatically project-filtered and never return
   another project's data.
4. WHEN a project is `ACTIVE` or `COMPLETED` THEN the cost read model SHALL be readable by callers with
   `WORK_REPORTS` READ (ADMIN, MANAGER, FOREMAN, and FINANCIER); WHEN `DRAFT` THEN no cost surface SHALL
   be reachable. THE FINANCIER READ grant on `WORK_REPORTS` SHALL be seeded by this spec IF FOR-06-01
   did not already grant it (idempotent, `onFail="MARK_RAN"`); otherwise no changeset is added.

### Requirement 6 — Internationalization completeness

#### Acceptance Criteria

1. THE backend SHALL add **no** `nameRU`/`namePL` i18n columns and **no** new persisted entity (the
   cost is a derived read model); the worker name and worker-type tier name SHALL be read from the
   existing entities with the `ru → pl → code` fallback, adding no new i18n column.
2. THE frontend SHALL add any new user-facing string surfaced by the cost read model — the
   worker-service-cost / cost-value column labels, the resolved-tier label, and the **base-tier
   fallback** marker — to **both** `src/locales/pl.json` and `src/locales/ru.json` with real PL and RU
   values (no English fallback), reusing the FOR-06-06 margin labels where they already exist, and the
   locale-parity test SHALL pass.
3. THE frontend SHALL never surface a raw i18n key to the user; an unavailable cost SHALL render as a
   localized `—`.

### Requirement 7 — No notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** user-facing notifications in this spec. Worker cost derivation is a
   **read-only computation** — it changes no domain state and has no cross-role handoff. Any future
   notification (e.g. a payout-ready alert) belongs to FOR-06-11 and SHALL emit via the generic
   per-recipient `Notification_Service` (FOR-05-07 Requirement 13) rather than re-implementing delivery.

### Requirement 8 — Regression and test artifacts

#### Acceptance Criteria

1. WHEN this spec ships THEN the FOR-05-06 margins (`MarginCostService`), the FOR-05-09
   `ProjectMember.workerType`, the FOR-06-01 work reporting + `WORK_REPORTS` resource, and the
   FOR-06-06 margin matrix SHALL continue to pass (regression): this spec reads them and, for
   FOR-06-06, refactors the cost call behind an unchanged result.
2. THE spec SHALL ship its own backend unit + property tests (the unit `Worker_Service_Cost` reusing
   `MarginCostService`, the `Cost_Value = unit × totalQuantity` fold, the worker-type resolution with
   base-tier fallback for Uncategorized / missing / inactive, the unavailable-rate propagation and its
   exclusion from sums, the per-worker and per-work-item aggregation, and the FOR-06-06 parity
   assertion), frontend unit tests for any new cost labels / fallback marker and locale parity, and an
   API-level `test-cases.md` run against the Dockerized app.
