# Implementation Plan: FOR-06-06 — Margin matrix (plan × fact work margin)

## Overview

This plan delivers the work-margin plan-vs-fact matrix as a **read-only projection** over existing
read models: the FOR-05 estimate (plan side), the FOR-06-01 approved work reports (labour fact), the
FOR-06-03/04 reconciled procurement lines (material + indirect fact), the FOR-05-06 `WorkerType` tiers
and the FOR-05-09 `ProjectMember.workerType`. Labour cost is computed by **reusing the FOR-05-06 pure
`MarginCostService` core verbatim** (base tier for the plan, each report's own worker tier for the
fact); the project-level overall margin folds the linked material cost and a **separate indirect-cost
sum broken down by `UnplannedExpenseCategory`**.

It introduces **no new managed entity, no new ABAC resource, no new dictionary, and no Liquibase
changeset** — so the `.kiro/steering/entity-creation-rules.md` checklist produces nothing. It **reads**
the estimate, the approved reports, and the reconciled lines; **rides** the FOR-06-01 `WORK_REPORTS`
resource (matrix = READ); and **reuses** the FOR-05-06 `MarginCostService` + `WorkerType` tiers and
the FOR-06-00 `margin` tab slot + `UnplannedExpenseCategory` labels.

**Ownership boundary.** This spec owns the matrix read model + panel. It does NOT own work reporting
(FOR-06-01), procurement (FOR-06-03/04), the materials matrix (FOR-06-05), the `WorkerType` dictionary
or `cost_net` (FOR-05-06), or worker rates / payroll (FOR-06-10/11). It reads them and changes none.

**Amendment memo (FOR-06-08).** This spec reads `WorkReport.clientUnitPrice` as-is (amendment-correct
automatically once FOR-06-08 ships); it models and applies no amendment delta. FOR-06-08 owns
price-before/after application.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — the matrix assembler + service + controller + read-model DTOs/mappers and this spec.
  **No migration.** Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `margin-matrix` feature folder, the `margin` tab
  `lazy` swap, the API client, and the `pl.json`/`ru.json` keys. Commit from inside `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-06-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks. Backend
property tests use **jqwik**; frontend property tests use **fast-check + Vitest** (minimum 100
iterations each, tagged `Feature: FOR-06-06-margin-matrix, Property {n}: ...`).

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1 (the pure `MarginMatrixAssembler` + read-model DTOs, reusing
  `MarginCostService`), 4.1 (frontend `margin` tab `lazy` swap scaffold) — mutually independent; 1.1
  is a self-contained unit in the root repo, 4.1 lives in the frontend repo.
- **Wave 2 (parallel):** 1.1a (assembler property tests), 2.1 (`MarginMatrixService` wiring the
  estimate plan read + the approved-report fact read + the worker-tier resolution + the procurement
  read into the assembler) — 1.1a needs the assembler (1.1); 2.1 needs the assembler + DTOs (1.1).
- **Wave 3:** 3.1 (`MarginMatrixController` — the `GET …/margin-matrix` READ endpoint on
  `WORK_REPORTS`) — needs the service (2.1).
- **Wave 4:** 5.1 (frontend `MarginMatrixPanel` + `MarginRow` + `ReportDetailDrawer` +
  `MarginDashboard` + `UnderMarginBadge` + `marginModel` + api client) — needs the controller live
  (3.1) and the tab scaffold (4.1).
- **Wave 5 (parallel):** 6.1 (i18n PL+RU), 6.2 (backend example/integration/smoke tests), 6.3
  (frontend tests) — independent.
- **Wave 6:** 7 (Author test-cases.md) — needs the full slice.
- **Wave 7:** 8 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: pure assembler + read-model DTOs
  - [x] 1.1 Implement `MarginMatrixAssembler` + DTOs
    - Add the read-model DTOs (design §Data Models): `MarginReportDetailDto`, `MarginRowDto`,
      `MarginCategoryGroupDto`, `IndirectCategoryDto`, `MarginDashboardDto`, `MarginMatrixDto`.
      Implement the pure, stateless `MarginMatrixAssembler` (design §B1): per estimate line fold the
      **plan** side at the base tier (`Plan_Cost = Tier_Cost(unitPrice, base) × quantity`,
      `Plan_Margin = labourMargin(valueNet, Plan_Cost)`, R1.2/2.3) and the **fact** side over the work
      item's approved reports (`Fact_Cost = Σ Tier_Cost(clientUnitPrice, Worker_Tier(report)) ×
      totalQuantity`, `Fact_Revenue = Σ revenueValue`, R1.3/2.4), using **only** `MarginCostService`
      (no second cost formula, R2.1). Compute `underMargin` from the two pcts vs
      `UNDER_MARGIN_THRESHOLD` (default 0.05; unavailable when either pct unavailable, R4.3). Group by
      work category (localized `ru→pl→code`, estimate order) with per-category plan/fact subtotals
      (R4.1). Fold `Linked_Material_Cost` (linked lines only), `Indirect_Cost` by
      `UnplannedExpenseCategory` (+ uncategorized bucket), and the `Overall_*` + `Plan_Overall_*`
      dashboard — indirect/linked only in the overall, never a per-row row (R3). Carry each report into
      the per-row detail with its resolved tier + `baseTierFallback` so the row fact cost equals the
      detail sum (R2.5, R4.2, R4.4). Propagate unavailable (null) price → unavailable cost/margin,
      excluded from aggregate denominators (R1.5/1.6). No I/O.
    - _Requirements: 1.2, 1.3, 1.5, 1.6, 2.1, 2.3, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4_
    - _Depends on: none_
  - [x] 1.1a Write property tests for the assembler (jqwik)
    - **Property 1: Fact cost sums each report at its own worker-type tier; the row fact cost equals
      the detail sum** — **Validates: Requirements 1.3, 2.1, 2.4, 4.4**.
    - **Property 2: Uncategorized / inactive / missing worker resolves to the base tier** —
      **Validates: Requirements 1.4, 2.5**.
    - **Property 3: Plan cost uses the base tier and the margin core; unavailable price propagates** —
      **Validates: Requirements 1.2, 1.6, 2.3, 3.5**.
    - **Property 4: Zero-report rows and divide-by-zero safety** — **Validates: Requirements 1.5,
      4.3**.
    - **Property 5: Linked material counts in, indirect accumulates separately by category, both only
      in the overall margin** — **Validates: Requirements 3.1, 3.2, 3.3, 3.4**.
    - **Property 6: Dashboard and category subtotals equal the sum of their parts** — **Validates:
      Requirements 3.4, 3.5, 4.1**.
    - Generate estimate lines (with/without a price, varied quantities), approved-report multisets
      (varied worker tiers incl. Uncategorized, varied quantities/prices, the empty set), and
      procurement-line sets (linked / unmatched / unplanned across categories). Drive the assembler as
      a pure function.
    - _Depends on: 1.1_

- [x] 2. Backend: matrix service
  - [x] 2.1 Add `MarginMatrixService`
    - Project-scoped over the work report (design §B2); `getProjectIdPath()` → `"project.id"` so reads
      are auto project-filtered (R6.3, mirrors `WorkReportService`). `getMatrix(projectId)`: resolve
      the FOR-05 estimate lines (reuse `EstimateService` get-or-create so a pre-estimate project
      returns an empty structure, not 404); load the project's **APPROVED** work reports; batch-resolve
      each report worker's tier from `ProjectMember.workerType` (null/inactive/missing → base tier,
      flag the fallback, avoid N+1); load the active `WorkerType` tiers (base tier + tier names); load
      the project's reconciled procurement lines; delegate to `MarginMatrixAssembler`. Read-write
      transaction only because the plan-side get-or-create may INSERT the estimate (mirrors
      `MarginsListService`). Emit **no** notifications (R8.1).
    - _Requirements: 1.1, 1.4, 2.2, 2.5, 6.3, 8.1_
    - _Depends on: 1.1_

- [x] 3. Backend: controller
  - [x] 3.1 Add `MarginMatrixController`
    - `@RestController @PermissionResource("WORK_REPORTS")` (design §B3):
      `GET /api/projects/{projectId}/margin-matrix` → READ, returning `MarginMatrixDto`. The in-scope
      handler carries a matching `@PermissionOperation`/`@RequiresPermission(... READ)`. Verify the app
      starts (no `PermissionAnnotationValidator` failure) and that **no new ABAC resource** and **no
      migration** were added (the `WORK_REPORTS` matrix is unchanged from FOR-06-01).
    - _Requirements: 6.1, 6.2, 6.4_
    - _Depends on: 2.1_

- [x] 4. Frontend: tab wiring
  - [x] 4.1 Swap the `margin` tab panel
    - In the workspace tab registry, change the `margin` entry's `lazy` from `PlaceholderTab` to the
      new `MarginMatrixPanel`. Do not touch the `WORKSPACE_TABS` contract shape, `resolveWorkspaceTabs`,
      or `stageOf`. The `workspace.tab.margin` label is already seeded by FOR-06-00 — reuse unchanged.
    - _Requirements: 6.5_
    - _Depends on: none_

- [x] 5. Frontend: margin-matrix panel
  - [x] 5.1 Build the `margin-matrix` feature folder
    - `features/project-execution/margin-matrix/` with `MarginMatrixPanel.tsx` (overall dashboard
      above; category-grouped rows in a `DataTable` with per-category subtotals; localized loading /
      empty / error; read-only, no write controls — identical on `COMPLETED`), `components/MarginRow.tsx`
      (planned qty + unit, plan revenue / cost / margin amount + pct with `—` when unavailable;
      reported qty, fact revenue / cost / margin; under-margin highlight; report count; detail
      disclosure), `components/ReportDetailDrawer.tsx` (contributing approved reports: worker, resolved
      worker-type tier with base-tier-fallback mark, reported qty, client unit price, revenue, derived
      tier cost; detail-sum = row fact cost), `components/MarginDashboard.tsx` (plan vs fact revenue /
      cost / margin; labour / linked-material / indirect split; indirect-by-category with the
      uncategorized bucket), `components/UnderMarginBadge.tsx` (localized indicator + tooltip),
      `state/marginModel.ts` (pure display mirror: `tierCost`/`labourMargin`/folds), `api/margin-matrix
      -api.ts` (`getMatrix` via the shared `apiRequest`), `schemas/`, `types/`, `__tests__/`.
    - _Requirements: 3.1, 3.2, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 5.5_
    - _Depends on: 3.1, 4.1_

- [x] 6. i18n + tests
  - [x] 6.1 Add i18n keys (PL + RU, mirrored)
    - Add the `marginMatrix.*` block (design §i18n): page title, columns (planned/reported qty /
      revenue / cost / margin, report count), the unavailable `—` label, the under-margin badge +
      tooltip (`{deltaPct}`), the dashboard labels (plan / fact / labour / linked-material / indirect /
      indirect-by-category / overall / revenue / cost / margin / uncategorized), the detail labels +
      columns (worker, tier, base-tier-fallback, qty, unit price, revenue, cost), and the loading /
      empty / error states, to **both** `pl.json` and `ru.json` with real PL + RU values (no English
      fallback). Reuse `workspace.tab.margin` (FOR-06-00) unchanged. Run the locale-parity test.
    - _Requirements: 7.2, 7.3_
    - _Depends on: 5.1_
  - [x] 6.2 Backend example / integration / smoke tests
    - `getMatrix` over a seeded project (estimate lines + approved reports with workers of differing
      worker types + reconciled lines seeded directly): assert the joined rows, the per-report tier
      cost mix, the base-tier fallback for an Uncategorized worker, the linked-material fold, the
      indirect-by-category separate sum appearing only in the overall margin, the unavailable-price
      `—`, the under-margin flag, and the dashboard totals; a project-scope check that the read never
      returns another project's data (R6.3); a startup smoke asserting `PermissionAnnotationValidator`
      classifies `MarginMatrixController` COMPLETE (R6.2) and that **no new ABAC resource** and **no
      migration** were added. Run only the affected classes with `--tests` → `/tmp/for-06-06-test.log`;
      verify via JUnit XML. (The plan/fact folds, tier cost, fallback, linked/indirect, and dashboard
      property tests live in 1.1a.)
    - _Requirements: 1.1, 2.5, 3.1, 3.2, 6.2, 6.3, 9.1_
    - _Depends on: 2.1, 3.1_
  - [x] 6.3 Frontend unit tests (Vitest + fast-check)
    - `MarginMatrixPanel` render with category groups + subtotals (R5.1); `MarginRow` plan-vs-fact
      margin + under-margin highlight + unavailable `—` (R5.1, R4.3, R5.5); `ReportDetailDrawer` report
      listing with the worker-type tier + base-tier-fallback mark and the detail-sum = fact-cost check
      (R5.3, R4.2, R4.4); `MarginDashboard` plan/fact + labour/material/indirect split +
      indirect-by-category (R5.2); the read-only + `COMPLETED` rendering (R5.4). **Property 7: Locale
      parity** over `pl.json` / `ru.json` — **Validates: Requirements 7.2**; plus a no-raw-key render
      assertion (R7.3). Resolver fixtures (`margin` present on `ACTIVE`/`COMPLETED` with
      `WORK_REPORTS`, absent on `DRAFT`, R6.4/R6.5).
    - _Requirements: 3.2, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 6.4, 6.5, 7.2, 7.3_
    - _Depends on: 5.1, 6.1_

- [x] 7. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown, a
    regression group, the MD report template). This is an **API + UI** spec: the matrix read model
    (the per-report worker-tier fact cost, the base-tier fallback for an Uncategorized worker, the
    base-tier plan cost, the linked-material fold, the indirect-by-category separate sum in the overall
    margin only, the unavailable-price `—`, the under-margin flag, the dashboard totals, the
    project-scope gate, the ABAC `WORK_REPORTS`/READ gate) is run as **API tests** against the
    Dockerized app (`http://localhost:8080`, auth under `/api/auth`); the Margin panel (category groups
    → rows → plan/fact margin → under-margin highlight → report detail → overall dashboard → read-only
    on `COMPLETED`) is a **browser-engine** scenario on an `ACTIVE` project. Estimate lines, approved
    reports (workers of differing worker types), and reconciled lines are seeded directly (no live
    reporting/OCR dependency). Written in Russian. Result artifacts are MD reports with tables.
  - _Requirements: 1.3, 1.4, 1.5, 2.5, 3.1, 3.2, 3.4, 4.1, 4.3, 5.1, 5.4, 6.1, 6.4_
  - _Depends on: 3.1, 5.1, 6.1, 6.2, 6.3_

- [x] 8. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes with `--tests` → `/tmp/for-06-06-test.log` (verify via JUnit XML); type-check +
    unit-test the frontend; confirm the locale-parity test passes and the app starts (no
    `PermissionAnnotationValidator` failure, no new ABAC resource, no migration). Confirm the `margin`
    tab appears for a permitted caller on an `ACTIVE`/`COMPLETED` project and the matrix renders the
    plan × fact rows, the per-report worker-tier fact cost (with the base-tier fallback for an
    Uncategorized worker), the under-margin highlight, the per-row report detail, and the overall
    dashboard with the linked-material fold and the separate indirect-by-category sum, and that it is
    read-only. Ensure all tests pass; ask the user if questions arise. Then stage and commit per repo
    (two commits — root for backend/spec, `foremen-frontend/` for the panel) — only if the user asks.
  - _Requirements: all_
  - _Depends on: 1.1, 1.1a, 2.1, 3.1, 4.1, 5.1, 6.1, 6.2, 6.3, 7_

## Notes

- Tasks marked with an `a` suffix are the property-test tasks placed next to the implementation they
  validate (the assembler).
- Each task references specific requirement sub-clauses for traceability.
- Backend property tests use **jqwik**; frontend property tests use **fast-check + Vitest** (minimum
  100 iterations, tagged with the design property number).
- The feature spans two git repos — backend/spec in the root repo, the matrix panel in
  `foremen-frontend/` — so a full commit is two commits, one per repo.
- **No new entity / resource / dictionary / migration.** This spec is a read-only projection over the
  FOR-05 estimate, the FOR-06-01 approved work reports, and the FOR-06-03/04 reconciled lines, reusing
  the FOR-05-06 `MarginCostService` and riding the FOR-06-01 `WORK_REPORTS` resource (READ).
- **Amendment memo (FOR-06-08):** the matrix reads the already-frozen `WorkReport.clientUnitPrice`; it
  becomes amendment-correct automatically once FOR-06-08 ships. FOR-06-08 owns applying price-before/
  after deltas.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 1, "tasks": ["1.1", "4.1"] },
    { "id": 2, "tasks": ["1.1a", "2.1"] },
    { "id": 3, "tasks": ["3.1"] },
    { "id": 4, "tasks": ["5.1"] },
    { "id": 5, "tasks": ["6.1", "6.2", "6.3"] },
    { "id": 6, "tasks": ["7"] },
    { "id": 7, "tasks": ["8"] }
  ]
}
```
