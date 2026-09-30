# Implementation Plan

- [x] 1. Backend: WorkerType dictionary (entity + schema + seed)
- [x] 1.1 Create `WorkerTypeEntity` and its schema changeset (`125`)
  - Add `WorkerTypeEntity` (`worker_types`): `code` (unique), `nameRU`, `namePL`, `tierPct`
    (`NUMERIC(6,4)`), `base` (`is_base`), `orderNo`, `active`. Create changeset
    `125-create-worker-types.xml` (table + audit cols), idempotent (`NOT tableExists` / `MARK_RAN`),
    registered last in `changelog.xml`.
  - _Requirements: 1.3, 1.6_
- [x] 1.2 Seed the four tiers + ABAC `WORKER_TYPES` resource in changeset `125`
  - Seed BASE 0.4000 (is_base), HIRED_NO_TOOLS 0.1000, HIRED_SOLE_TRADER 0.2650, FIRM 0.6500 with
    RU/PL names (from the Excel `Pracownicy price` sheet). Seed the ABAC `WORKER_TYPES` resource +
    ADMIN CRUD grants (+ READ for margin-viewing roles) per the entity-creation checklist. All inserts
    idempotent.
  - _Requirements: 1.4, 6.1, 6.2, 6.3_
- [x] 1.3 Write the WorkerType migration + seed integration test
  - Testcontainers: after migrate, `worker_types` exists with the 4 seeded tiers (exactly one
    `is_base`), the `WORKER_TYPES` resource + ADMIN grants are seeded; re-run is a no-op.
  - _Requirements: 1.4, 6.3_

- [x] 2. Backend: WorkerType CRUD + ABAC
- [x] 2.1 Implement the WorkerType service/controller (generic CRUD) with validation
  - DTO + mapper + service + `@RestController @PermissionResource("WORKER_TYPES")` fully annotated.
    Validation: unique/non-blank `code`; `tierPct ≥ 0`; base share in `(0,1]`; exactly one `base=true`
    across the dictionary; localized error messages (PL/RU bundles).
  - _Requirements: 1.1, 1.2, 1.5, 7.1_
- [x] 2.2 Write CRUD + ABAC + startup-annotation tests
  - CRUD happy-path; the single-base-tier and tier-pct bound validations reject with localized codes;
    ABAC read/write gating; `PermissionAnnotationValidator` classifies the controller COMPLETE.
  - _Requirements: 1.2, 1.5_

- [x] 3. Backend: material `cost_net` (schema + back-fill + CRUD)
- [x] 3.1 Add `cost_net` to construction & finishing materials (changesets `126`, `127`)
  - Add `cost_net NUMERIC(12,2) NOT NULL DEFAULT 0`; back-fill `= round(retail_net×0.9, 2)` where
    `retail_net` is not null (else keep 0). Two idempotent changesets (`126` construction, `127`
    finishing), `NOT columnExists` guarded, registered last.
  - _Requirements: 3.1, 3.2, 3.3_
- [x] 3.2 Extend the material entities/DTOs/forms for `cost_net`
  - Add `costNet` to both entities + their service DTOs + mappers; expose in the material list and
    edit form. `cost_net` independent of `retailNet` after seed (no auto-overwrite on retail edit).
  - _Requirements: 3.4, 3.5_
- [x] 3.3 Write the material-cost migration integration test
  - Testcontainers: both columns present, NOT NULL, back-filled `retail×0.9` for non-null retail and
    `0` for null retail; idempotent re-run.
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 4. Backend: cost core + margins projection
- [x] 4.1 Implement `MarginCostService` (pure cost core)
  - `baseCost(offer)` = round(0.40×offer to 0.5, HALF_UP), null/0 ⇒ null; `tierCost(base, tier)` =
    base×(1+uplift), base tier uplift 0; `labourMargin(offer, cost)`; `materialMargin(retail, cost)`.
  - _Requirements: 2.1, 2.2, 2.4, 2.5_
- [x] 4.2 Implement `MarginsListAssembler` (read-only projection, grouped by work type)
  - Pure static core over `EstimateMatrixDto` + worker types + material `cost_net`s: per work row emit
    offer, base cost, per-tier costs + labour margins, min/avg/max labour profit, per-branch material
    retail/cost/margin; group by work type (kosztorys category order); + dashboard folds (per-tier
    labour totals, per-branch material totals). Null-price exclusion.
  - _Requirements: 4.2, 4.3, 4.4, 4.6, 5.3, 5.4, 2.3_
- [x] 4.3 Add the margins read-model DTOs
  - `MoneyMargin`, `TierCostDto`, `BranchMaterialDto`, `MarginRowDto`, `MarginWorkGroupDto`,
    `TierTotalDto`, `MarginsDashboardDto`, `WorkerTypeRefDto`, `MarginsListDto`.
  - _Requirements: 4.3, 5.3_
- [x] 4.4 Write property tests — cost core (jqwik, ≥100 iterations)
  - Base rounding to 0.5 (Property 1), tier = base×(1+uplift) monotonic (Property 2), labour min/avg/max
    (Property 4), unavailable propagation (Property 8).
  - _Requirements: 2.1, 2.2, 2.4, 2.5, 4.6_
- [x] 4.5 Write property tests — assembler (jqwik, ≥100 iterations)
  - Labour margins across tiers (Property 4), material margin per branch kept separate (Property 5),
    dashboard = Σ rows (Property 9).
  - _Requirements: 4.4, 4.6, 5.3_

- [x] 5. Backend: margins service + controller
- [x] 5.1 Implement `MarginsListService` + `EstimateMarginsController`
  - `getMargins(projectId)` reuses the kosztorys get-or-create + matrix, loads worker types + material
    costs, delegates to the assembler; `GET /project/{projectId}/margins`
    `@RequiresPermission(ESTIMATE, READ)` on a fully-annotated `@PermissionResource("ESTIMATE")`
    controller (no new tab resource).
  - _Requirements: 4.1, 5.1, 5.5_
- [x] 5.2 Write service integration + ABAC/startup tests
  - Spring integration over a seeded estimate (rows grouped by work type; per-tier costs; per-branch
    material margins; pre-estimate project ⇒ empty, not 404); ESTIMATE READ gating; controller COMPLETE.
  - _Requirements: 4.1, 4.2, 5.1_

- [x] 6. Checkpoint — backend compiles and its tests pass
  - `getDiagnostics` + `compileJava`/`compileTestJava`; run only the affected test classes with
    `--tests` (temp log; verify via JUnit XML). Do not run the full suite.

- [x] 7. Frontend: WorkerType admin CRUD
- [x] 7.1 Implement `features/worker-types/` (list + form) and nav entry
  - Reference-dictionary screen (mirror an existing dictionary feature): list (code, localized name,
    tier %, base, active) + create/edit form; zod validation (tier pct ≥ 0, single-base guard mirrored
    client-side); wired to the generic admin API against `WORKER_TYPES`; nav entry.
  - _Requirements: 1.1, 1.2, 1.5_
- [x] 7.2 Write WorkerType CRUD component tests
  - list render, create/edit, validation messages (localized), single-base guard.
  - _Requirements: 1.1, 1.5_

- [x] 8. Frontend: material `cost_net` UI
- [x] 8.1 Add `cost_net` column + form field to both material catalogs
  - List column + form field (react-hook-form + zod, ≥0, 2 decimals); optional "recompute from retail
    (−10%)" convenience that never auto-overwrites on retail edit.
  - _Requirements: 3.4, 3.5_
- [x] 8.2 Write material cost-field component tests
  - field renders/edits; recompute button fills without auto-overwrite; validation.
  - _Requirements: 3.4, 3.5_

- [x] 9. Frontend: pure margins model + api
- [x] 9.1 Scaffold `features/project-margins/` + mirror read-model types + `api/margins-api.ts`
  - Types mirror the backend DTOs exactly; api wires `GET /api/estimates/project/{id}/margins` (+ the
    worker-types api if not already generic) with a TanStack Query hook.
  - _Requirements: 4.1, 4.3_
- [x] 9.2 Implement `state/marginsModel.ts` (pure core mirroring the backend)
  - `baseCost` (round 0.5), `tierCost`, `labourMargin`, `materialMargin`, group + dashboard folds;
    identical rounding to the backend so the live view matches the server read model.
  - _Requirements: 2.1, 2.2, 4.4, 4.6, 5.3_
- [x] 9.3 Write property tests — margins model (fast-check, ≥100 iterations)
  - Base rounding, tier = base×(1+uplift), labour min/avg/max, material margin per branch, unavailable
    propagation, dashboard = Σ rows.
  - _Requirements: 2.1, 2.2, 4.4, 4.6, 5.3, 5.4_

- [x] 10. Frontend: Margins matrix, dashboard, tab
- [x] 10.1 Implement `MarginsMatrix` (works × cost, grouped by work type)
  - Rows grouped by work type; columns offer → per-tier cost → construction material cost → finishing
    material cost → min/avg/max labour profit; per-branch material margin shown distinctly; `—` for
    null price; shared money formatter.
  - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.7_
- [x] 10.2 Implement `MarginsDashboard`
  - Per-tier labour totals (offer/cost/margin abs+%) + per-branch material totals; all tiers shown
    (DRAFT); live-derived.
  - _Requirements: 5.1, 5.2, 5.3, 5.4_
- [x] 10.3 Implement `MarginsTab` and register the `margins` workspace tab
  - `lazy` target modelled on the Materials tab; fetch read model, loading/empty/error localized copy;
    register `margins` in `WORKSPACE_TABS` (design stage, `ESTIMATE` READ, `workspace.tab.margins`).
  - _Requirements: 4.1, 5.1_
- [x] 10.4 Write matrix/dashboard/tab component tests
  - column layout + grouping, per-tier costs, min/avg/max labour profit, per-branch material margin,
    null-price `—`, dashboard totals, tab registration + ESTIMATE READ gating.
  - _Requirements: 4.2, 4.3, 4.4, 4.5, 5.2, 5.3_

- [x] 11. Frontend: i18n (PL/RU parity)
- [x] 11.1 Add all new keys to `pl.json` and `ru.json`
  - `margins.*` (tab title `workspace.tab.margins`, column headers, tier label fallback,
    min/avg/max profit, branch labels, dashboard labels, empty/error), `workerTypes.*` (CRUD), and the
    material `cost_net` list/form labels — both locales, strict parity, non-empty.
  - _Requirements: 7.1, 7.2, 7.3_
- [x] 11.2 Write locale-parity test
  - key-set equality over the new `margins.*` / `workerTypes.*` / material-cost keys; no raw key on the
    new screens.
  - _Requirements: 7.2, 7.3_

- [x] 12. Checkpoint — frontend compiles and its tests pass
  - Frontend type-check + run the affected tests single-run (no watch).

- [x] 13. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group,
    MD report template). This spec has UI screens (Margins tab, WorkerType CRUD, material cost form) →
    browser-engine scenarios with MD-report artifacts; plus API scenarios against the Dockerized app
    (`GET /api/estimates/project/{id}/margins`, WorkerType CRUD, material cost persistence, the
    125–127 migrations). Written in Russian.
  - _Requirements: 1.x, 2.x, 3.x, 4.x, 5.x, 6.x, 7.x_

- [-] 14. Final checkpoint — full verification and per-repo commits
  - Backend: `getDiagnostics` + compile, then run only the affected test classes with `--tests` (temp
    log; verify via JUnit XML). Frontend: type-check + property/component/locale tests (single-run).
  - Commit per the two-repo rule: backend (WorkerType entity/CRUD, migrations 125–127, material
    cost_net, margins assembler/service/controller + DTOs) + spec docs from the **root repo**; all
    frontend (worker-types admin, project-margins tab, material cost UI, locales) from **inside
    `foremen-frontend/`**. Two commits, one per repo. Commit only when the user asks; new branch only;
    no force-push; no git-config changes.
