# Implementation Plan: FOR-06-10 — Worker cost rates (worker service cost derivation)

## Overview

This plan delivers the **single, reusable worker-cost derivation** as a thin, read-only service over
existing read models: the FOR-05-06 `WorkerType` tiers + `MarginCostService` cost core, the FOR-05-09
`ProjectMember.workerType`, and the FOR-06-01 approved work reports (`clientUnitPrice`,
`totalQuantity`). The worker cost is **always derived on the fly** — `Worker_Service_Cost =
round(base.tierPct × clientUnitPrice to 0.5 zł) × (1 + tier.uplift)` via `MarginCostService`
**verbatim**, `Cost_Value = Worker_Service_Cost × totalQuantity` — with the worker type resolved from
`ProjectMember.workerType` (null / inactive / missing → base tier). There is **no `WorkerServiceRate`
entity, no CRUD, and no rate dictionary** (product-owner decision).

It introduces **no new managed entity, no new ABAC resource, and no new dictionary** — so the
`.kiro/steering/entity-creation-rules.md` checklist produces nothing, and there is **no Liquibase
changeset** except an **optional, idempotent** FINANCIER `WORK_REPORTS` READ grant only if FOR-06-01
did not already grant it. It **reads** the approved reports, the `WorkerType` tiers, and the project
memberships; **rides** the FOR-06-01 `WORK_REPORTS` resource (cost = READ); and **reuses** the
FOR-05-06 `MarginCostService`. It then **refactors FOR-06-06** to call the shared service instead of
its inline fact-cost formula (behaviour-preserving, single formula).

**Ownership boundary.** This spec owns the worker-cost derivation service + its read model. It does
NOT own work reporting (FOR-06-01), the `WorkerType` dictionary or tier percentages (FOR-05-06),
worker-type assignment (FOR-05-09), payroll aggregation / payout documents (FOR-06-11), or the margin
matrix UI (FOR-06-06). It reads them and changes none, and persists no cost column on `WorkReport`.

**Amendment memo (FOR-06-08).** This spec reads `WorkReport.clientUnitPrice` as-is (amendment-correct
automatically once FOR-06-08 ships); it models and applies no amendment delta.

**Spans one git repo (backend-only)** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — the `WorkerCostService` + read-model DTOs/mappers + the optional read endpoint + the
  FOR-06-06 refactor + this spec. **No migration** (unless the optional FINANCIER grant is needed).
  Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — a change happens **only if** FOR-06-06 did not already
  ship the cost / fallback strings; then add them to `pl.json`/`ru.json` and commit from inside
  `foremen-frontend/`. Otherwise no frontend commit.

A full implementation is normally **one root-repo commit** (plus a frontend commit only if new strings
are introduced).

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-10-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks. Backend
property tests use **jqwik** (minimum 100 iterations each, tagged `Feature: FOR-06-10-worker-cost-
rates, Property {n}: ...`).

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1 (the pure cost-derivation helpers + read-model DTOs, reusing
  `MarginCostService`), 2.1 (worker-type resolution helper `tierFor` with base-tier fallback) —
  mutually independent pure units in the root repo (1.1 is the cost math; 2.1 is the tier resolution;
  they are composed later in 3.1).
- **Wave 2 (parallel):** 1.1a (property tests for the cost helpers), 2.1a (property tests for tier
  resolution) — each needs its own Wave-1 unit.
- **Wave 3:** 3.1 (`WorkerCostService` wiring: load approved reports + tiers + batched member map,
  compose 1.1 + 2.1 into per-line / per-worker / per-work-item read model) — needs the cost helpers
  (1.1) and the resolution helper (2.1).
- **Wave 4 (parallel):** 3.2 (optional `WorkerCostController` READ endpoint on `WORK_REPORTS`;
  optional idempotent FINANCIER grant), 4.1 (FOR-06-06 refactor to call `WorkerCostService`) — both
  need the service (3.1); they touch different code (the new controller vs the FOR-06-06 assembler).
- **Wave 5 (parallel):** 5.1 (backend example / integration / smoke tests incl. the FOR-06-06
  parity + regression), 6.1 (frontend strings + locale parity — only if new strings are needed) —
  independent.
- **Wave 6:** 7 (Author test-cases.md) — needs the full slice.
- **Wave 7:** 8 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: pure cost-derivation helpers + read-model DTOs
  - [x] 1.1 Implement the worker-cost helpers + DTOs
    - Add the read-model DTOs (design §Data Models): `WorkerCostLineDto`, `WorkerCostByWorkerDto`,
      `WorkerCostByWorkItemDto`, `WorkerCostReadModelDto`. Implement the pure, static, stateless
      cost-derivation helpers (design §B1): `unitServiceCost(rate, tier, baseTier) =
      MarginCostService.tierCost(MarginCostService.baseCost(rate, baseTier), tier)` and
      `costValue(rate, tier, baseTier, quantity) = unitServiceCost(...) × quantity`, using **only**
      `MarginCostService` (no second cost formula, R1.2). Propagate an unavailable (null/zero) rate →
      null unit cost and null `Cost_Value` (never a fabricated zero, R1.5). `Cost_Value` scales
      linearly with `totalQuantity` (R1.3). No I/O.
    - _Requirements: 1.2, 1.3, 1.5_
    - _Depends on: none_
  - [x] 1.1a Write property tests for the cost helpers (jqwik)
    - **Property 1: Unit cost is the margin-core tier cost on the work-item rate** — **Validates:
      Requirements 1.2, 2.3**.
    - **Property 2: Cost value scales linearly with reported quantity** — **Validates: Requirement
      1.3**.
    - **Property 4 (rate half): Unavailable rate propagates** — a null/zero rate yields a null unit
      cost and null `Cost_Value` — **Validates: Requirement 1.5**.
    - Generate work-item rates (null/zero/positive), worker-type tiers (base + non-base uplifts),
      quantities (zero / positive). Assert equality against a direct `MarginCostService` computation
      (single-formula guarantee) and linearity in quantity.
    - _Depends on: 1.1_

- [x] 2. Backend: worker-type resolution
  - [x] 2.1 Implement the tier-resolution helper
    - Add `tierFor(worker, memberMap, baseTier)` (design §B1): resolve `Resolved_Worker_Type` from a
      pre-built `userId → active workerType` map; a null membership, a missing membership, or an
      **inactive** worker type → the **base tier** with `Base_Fallback = true` (R2.1, R2.2, R2.4). An
      active assigned type → that type with the flag false. The **base tier** is the active
      `WorkerType` flagged `base` (R2.3). Deterministic and side-effect free — reads only, writes
      nothing, mutates no report / membership / tier (R2.5).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
    - _Depends on: none_
  - [x] 2.1a Write property tests for tier resolution (jqwik)
    - **Property 3: Uncategorized / inactive / missing worker resolves to the base tier** —
      **Validates: Requirements 2.1, 2.2, 2.4**.
    - Generate workers with: an active assigned type, a null type (Uncategorized), an inactive type,
      and no membership. Assert the resolved tier + `Base_Fallback` flag, and that the derived unit
      cost equals the base-tier cost on the fallback cases.
    - _Depends on: 2.1_

- [x] 3. Backend: the worker-cost service + optional endpoint
  - [x] 3.1 Add `WorkerCostService`
    - Project-scoped over the work report (design §B1); `getProjectIdPath()` → `"project.id"` so reads
      are auto project-filtered (R5.3, mirrors `WorkReportService`). `costForProject(projectId)`: load
      the project's **APPROVED** work reports (reuse `WorkReportService` / `WorkReportDao`); load the
      active `WorkerType` tiers (base tier + tier names); **batch** the project's `ProjectMember` rows
      into a `userId → active workerType` map once (avoid N+1); for each report compose `tierFor` (2.1)
      + `costValue` (1.1) into a `WorkerCostLineDto` (resolved tier + `Base_Fallback`, `workItemRate =
      clientUnitPrice` read as-is per R1.4, `unitServiceCost`, `costValue`); fold the `byWorker` and
      `byWorkItem` aggregates over **approved-only** reports, **excluding** unavailable-cost lines from
      every sum (R3). Read-only; emit **no** notifications (R7.1). Provide a per-report convenience
      `costFor(report, baseTier, memberMap)` for FOR-06-06's assembler (used in task 4.1).
    - _Requirements: 1.1, 1.3, 1.4, 3.1, 3.2, 3.3, 3.4, 5.3, 7.1_
    - _Depends on: 1.1, 2.1_
  - [x] 3.2 Add the optional `WorkerCostController` + optional FINANCIER grant
    - If a standalone cost surface is wanted, add `@RestController @PermissionResource("WORK_REPORTS")`
      with `GET /api/projects/{projectId}/worker-costs` → READ returning `WorkerCostReadModelDto`
      (design §B2); the in-scope handler carries a matching `@PermissionOperation`/
      `@RequiresPermission(... READ)`. Verify the app starts (no `PermissionAnnotationValidator`
      failure) and that **no new ABAC resource** was added (R5.1, R5.2). Check whether FOR-06-01 grants
      **FINANCIER** `WORK_REPORTS` READ; if not, add one idempotent Liquibase changeset
      (`onFail="MARK_RAN"`, guarded by the join count) granting it and register it last in
      `changelog.xml` per `.kiro/steering/entity-creation-rules.md`; if it already exists, add **no**
      changeset (R5.4). (If the service is consumed only in-process by FOR-06-06/FOR-06-11, the
      controller may be omitted; keep the FINANCIER-grant check regardless.)
    - _Requirements: 5.1, 5.2, 5.4_
    - _Depends on: 3.1_

- [x] 4. Backend: FOR-06-06 refactor (single formula)
  - [x] 4.1 Refactor the FOR-06-06 fact-cost call site
    - In `MarginMatrixAssembler` (FOR-06-06), replace the inline fact-side cost
      (`Tier_Cost(report.clientUnitPrice, Worker_Tier(report)) × report.totalQuantity`) with a call to
      the shared `WorkerCostService.costFor(report, baseTier, memberMap)` (or by consuming the
      `byWorkItem` aggregate), so there is **one** worker-cost formula (R4.1, R4.3). The change is
      **behaviour-preserving** — same `MarginCostService` core, same base-tier fallback for
      Uncategorized workers, penny-for-penny identical — so existing FOR-06-06 unit/property/integration
      tests pass unchanged (R4.2). Remove the now-duplicate inline formula.
    - _Requirements: 4.1, 4.2, 4.3_
    - _Depends on: 3.1_

- [x] 5. Tests
  - [x] 5.1 Backend example / integration / smoke tests
    - `costForProject` over a seeded project (approved reports with workers of differing worker types —
      an active-type worker, an Uncategorized worker, an inactive-type worker, a no-membership worker —
      plus a null/zero-rate report, seeded directly): assert each line's resolved tier + `Base_Fallback`
      + unit cost + `Cost_Value`, the per-worker and per-work-item aggregates (approved-only, excluding
      the unavailable-rate line, R3), and that non-`APPROVED` reports are excluded (R3.3). A
      project-scope check that the read never returns another project's reports (R5.3). If the endpoint
      exists: an integration test asserting `WORK_REPORTS` READ for ADMIN / MANAGER / FOREMAN /
      FINANCIER and a 403 for a role without the grant (R5.1, R5.4), plus a startup smoke asserting
      `PermissionAnnotationValidator` classifies the controller COMPLETE and **no new ABAC resource**
      was added (R5.2). **FOR-06-06 parity + regression:** assert the margin matrix's fact cost equals
      `WorkerCostService` `Cost_Value` after the refactor (Property 6) and that the FOR-06-06
      integration/property tests still pass (R4.2). Run only the affected classes with `--tests` →
      `/tmp/for-06-10-test.log`; verify via JUnit XML. (Property 5 aggregation lives alongside here;
      the cost-math/resolution properties live in 1.1a / 2.1a.)
    - _Requirements: 1.1, 3.1, 3.2, 3.3, 3.4, 4.1, 4.2, 5.1, 5.2, 5.3, 5.4, 8.1, 8.2_
    - _Depends on: 3.1, 3.2, 4.1_
  - [x] 6.1 Frontend strings + locale parity (only if new strings are needed)
    - Check whether FOR-06-06 already ships `marginMatrix.detail.cost` and
      `marginMatrix.detail.baseTierFallback`. If **not**, add the missing cost / fallback strings to
      **both** `pl.json` and `ru.json` with real PL + RU values (no English fallback) and run the
      locale-parity test (**Property 7: Locale parity** — **Validates: Requirement 6.2**). If the
      strings already exist, make **no** frontend change and record that this spec added no new
      user-facing string (R6.2, R6.3). The backend adds no new i18n column (R6.1).
    - _Requirements: 6.1, 6.2, 6.3_
    - _Depends on: 4.1_

- [x] 7. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown, a
    regression group, the MD report template). This is an **API-only** spec (no dedicated browser
    screen of its own): the worker-cost read model is run as **API tests** against the Dockerized app
    (`http://localhost:8080`, auth under `/api/auth`) — the per-report unit cost + `Cost_Value`, the
    base-tier fallback for an Uncategorized / inactive / no-membership worker, the unavailable-rate
    exclusion, the per-worker and per-work-item aggregates (approved-only), the ABAC `WORK_REPORTS`/
    READ gate (ADMIN / MANAGER / FOREMAN / FINANCIER vs a denied role), the project-scope gate, and the
    **FOR-06-06 parity** (the margin matrix's fact cost equals the derived `Cost_Value`). Approved
    reports (workers of differing worker types) and estimate work-item rates are seeded directly (no
    live reporting dependency). Written in Russian. Result artifacts are MD reports with tables.
  - _Requirements: 1.1, 1.3, 1.5, 2.1, 2.2, 3.2, 3.3, 3.4, 4.2, 5.1, 5.4_
  - _Depends on: 3.1, 3.2, 4.1, 5.1_

- [x] 8. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes with `--tests` → `/tmp/for-06-10-test.log` (verify via JUnit XML); confirm the app
    starts (no `PermissionAnnotationValidator` failure, no new ABAC resource, and either no migration
    or only the single idempotent FINANCIER grant). Confirm the FOR-06-06 margin matrix renders the
    identical fact cost after the refactor (parity, no behaviour change). If a new frontend string was
    added, type-check + unit-test the frontend and confirm the locale-parity test passes; otherwise
    confirm no frontend change was made. Ensure all affected tests pass; ask the user if questions
    arise. Then stage and commit from the root repo (backend + spec; plus a `foremen-frontend/` commit
    only if a new string was added) — only if the user asks.
  - _Requirements: all_
  - _Depends on: 1.1, 1.1a, 2.1, 2.1a, 3.1, 3.2, 4.1, 5.1, 6.1, 7_

## Notes

- Tasks marked with an `a` suffix are the property-test tasks placed next to the implementation they
  validate (the cost helpers / the tier resolution).
- Each task references specific requirement sub-clauses for traceability.
- Backend property tests use **jqwik** (minimum 100 iterations, tagged with the design property
  number). The only possible frontend change is a locale-parity string addition (fast-check + Vitest
  if added).
- **No `WorkerServiceRate` entity / resource / dictionary.** The worker cost is always derived from
  the worker type (`ProjectMember.workerType` → base-tier fallback) and the project's work-item rate
  (`WorkReport.clientUnitPrice`), reusing the FOR-05-06 `MarginCostService` verbatim. The only possible
  migration is an **optional idempotent** FINANCIER `WORK_REPORTS` READ grant.
- This spec **extracts** the FOR-06-06 inline fact-cost formula into one shared service and refactors
  FOR-06-06 to call it, so the margin matrix and the upcoming FOR-06-11 payroll share a single
  cost-side formula.
- **Amendment memo (FOR-06-08):** the service reads the already-frozen `WorkReport.clientUnitPrice`;
  it becomes amendment-correct automatically once FOR-06-08 ships.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 1, "tasks": ["1.1", "2.1"] },
    { "id": 2, "tasks": ["1.1a", "2.1a"] },
    { "id": 3, "tasks": ["3.1"] },
    { "id": 4, "tasks": ["3.2", "4.1"] },
    { "id": 5, "tasks": ["5.1", "6.1"] },
    { "id": 6, "tasks": ["7"] },
    { "id": 7, "tasks": ["8"] }
  ]
}
```
