# Implementation Plan: FOR-06-05 — Materials matrix (plan × fact)

## Overview

This plan delivers the materials plan-vs-fact matrix as a **read-only projection** over two existing
read models: the FOR-05-05b planned-materials list (plan side) and the FOR-06-04 reconciled
procurement lines (fact side). Per planned material it aggregates **every** linked line — all analogs,
all manufacturers, all match tiers at full weight — into a **money-based % выборки**, with quantity
shown unit-aware (same-unit sum + mismatch flag) and over-purchase shown uncapped and highlighted.

It introduces **no new managed entity, no new ABAC resource, no new dictionary, and no Liquibase
changeset** — so the `.kiro/steering/entity-creation-rules.md` checklist produces nothing. It **reads**
the FOR-05-05b materials list and the FOR-06-04 reconciled lines, **rides** the FOR-06-03 `PURCHASES`
resource (matrix = READ), and **reuses** the FOR-06-03 `PlannedMaterialCandidateProvider` and the
FOR-06-00 `materials` tab slot.

**Ownership boundary.** This spec owns the matrix read model + panel. It does NOT own intake / OCR /
auto-match (FOR-06-03), reconciliation (FOR-06-04), or the planned materials / reserves (FOR-05-05b).
It reads them and changes none.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Root repo** — the matrix assembler + service + controller + read-model DTOs/mappers, any backend
  message keys, and this spec. **No migration.** Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `materials-matrix` feature folder, the `materials` tab
  `lazy` swap, the API client, and the `pl.json`/`ru.json` keys. Commit from inside `foremen-frontend/`.

A full implementation needs **two commits — one per repo**.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected test classes with `--tests`, redirect to
`/tmp/for-06-05-test.log`, and read the log / JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks. Backend
property tests use **jqwik**; frontend property tests use **fast-check + Vitest** (minimum 100
iterations each, tagged `Feature: FOR-06-05-materials-matrix, Property {n}: ...`).

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1 (the pure `MaterialsMatrixAssembler` + `unitEq`/`fulfilment` helpers +
  read-model DTOs), 4.1 (frontend `materials` tab `lazy` swap scaffold) — mutually independent; 1.1 is
  a pure self-contained unit in the root repo, 4.1 lives in the frontend repo.
- **Wave 2 (parallel):** 1.1a (assembler property tests), 2.1 (`MaterialsMatrixService` wiring the
  FOR-05-05b plan read + the FOR-06-04 fact read into the assembler) — 1.1a needs the assembler (1.1);
  2.1 needs the assembler + DTOs (1.1).
- **Wave 3:** 3.1 (`MaterialsMatrixController` — the `GET …/materials-matrix` READ endpoint on
  `PURCHASES`) — needs the service (2.1).
- **Wave 4:** 5.1 (frontend `MaterialsMatrixPanel` + `MatrixRow` + `MaterialDetailDrawer` +
  `MatrixDashboard` + `UnitMismatchBadge` + `matrixModel` + api client) — needs the controller live
  (3.1) and the tab scaffold (4.1).
- **Wave 5 (parallel):** 6.1 (i18n PL+RU), 6.2 (backend tests), 6.3 (frontend tests) — independent.
- **Wave 6:** 7 (Author test-cases.md) — needs the full slice.
- **Wave 7:** 8 (final verification + checkpoint) — depends on everything.

## Tasks

- [x] 1. Backend: pure assembler + read-model DTOs
  - [x] 1.1 Implement `MaterialsMatrixAssembler` + DTOs
    - Add the read-model DTOs (design §Data Models): `MatrixLinkedLineDto`, `MatrixRowDto`,
      `MatrixCategoryGroupDto`, `MatrixBranchGroupDto`, `MaterialsMatrixDto`. Implement the pure,
      stateless `MaterialsMatrixAssembler` (design §B1): index linked lines by `matchedMaterialId`
      (keep only `matchStatus ∈ {AUTO, MANUAL_MATCHED}`, drop unmatched/unplanned, R1.6); per planned
      row fold `Fact_Brutto` (Σ `lineTotal`, all tiers full weight, R1.2/1.3), `Fact_Quantity`
      (same-unit lines via `unitEq`, R2.1), `Unit_Mismatch_Count` + flag (R2.2), `Fulfilment_Percent`
      (uncapped, null when planned brutto null/0, R1.3/1.4), `Fill_Indicator` = `min(pct,100)` +
      `overPurchased` = `pct>100` (R4.1); group by branch → category (localized `ru→pl→code`,
      FOR-05-05b order, R3.1); per-branch + grand dashboard totals over non-null-brutto rows (R3.2/3.3).
      Add the canonical `unitEq(unitHint, normUnit)` and `fulfilment(factBrutto, plannedBrutto)`
      helpers (design §B1). No I/O.
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 4.1, 4.2_
    - _Depends on: none_
  - [x] 1.1a Write property tests for the assembler (jqwik)
    - **Property 1: Fact fold aggregates all linked analogs at full weight, tier-independent** —
      **Validates: Requirements 1.2, 1.3, 1.5**.
    - **Property 2: Money-based % выборки is uncapped and divide-by-zero-safe** — **Validates:
      Requirements 1.3, 1.4, 4.1**.
    - **Property 3: Fact quantity sums only unit-matching lines and counts the rest as mismatches** —
      **Validates: Requirements 2.1, 2.2, 2.3, 2.4**.
    - **Property 4: Unmatched and unplanned lines contribute to no row** — **Validates: Requirements
      1.6**.
    - **Property 5: Dashboard totals sum per-material contributions per branch and project-wide,
      excluding null-brutto rows** — **Validates: Requirements 3.2, 3.3**.
    - Generate planned rows (with/without a planned brutto, varied norm units) and linked-line
      multisets (all tiers, all match states, matching + foreign units, empty set, over-purchase,
      multiple analogs per material). Drive the assembler as a pure function.
    - _Depends on: 1.1_

- [x] 2. Backend: matrix service
  - [x] 2.1 Add `MaterialsMatrixService`
    - Project-scoped over the reconciled procurement line (design §B2); `getProjectIdPath()` →
      `"document.project.id"` so reads are auto project-filtered (R6.3). `getMatrix(projectId)`:
      resolve the FOR-05-05b planned materials (reuse `MaterialsListService` so a pre-estimate project
      returns an empty structure, not 404); load the project's reconciled lines with a non-null
      `matchedMaterialId` and `matchStatus ∈ {AUTO, MANUAL_MATCHED}` (reuse the FOR-06-04 read path);
      delegate to `MaterialsMatrixAssembler`. Resolve matched-material localized name / branch /
      category / unit via the reused `PlannedMaterialCandidateProvider`. Emit **no** notifications
      (R7.1).
    - _Requirements: 1.1, 1.6, 6.3, 7.1_
    - _Depends on: 1.1_

- [x] 3. Backend: controller
  - [x] 3.1 Add `MaterialsMatrixController`
    - `@RestController @PermissionResource("PURCHASES")` (design §B3):
      `GET /api/projects/{projectId}/materials-matrix` → READ, returning `MaterialsMatrixDto`. The
      in-scope handler carries a matching `@PermissionOperation`/`@RequiresPermission(... READ)`.
      Verify the app starts (no `PermissionAnnotationValidator` failure) and that **no new ABAC
      resource** and **no migration** were added (the `PURCHASES` matrix is unchanged from FOR-06-03).
    - _Requirements: 6.1, 6.2, 6.4_
    - _Depends on: 2.1_

- [x] 4. Frontend: tab wiring
  - [x] 4.1 Swap the `materials` tab panel
    - In the workspace tab registry, change the `materials` entry's `lazy` from `PlaceholderTab` to the
      new `MaterialsMatrixPanel`. Do not touch the `WORKSPACE_TABS` contract shape,
      `resolveWorkspaceTabs`, or `stageOf`. The `workspace.tab.materials` label is already seeded by
      FOR-06-00 — reuse unchanged.
    - _Requirements: 6.5_
    - _Depends on: none_

- [x] 5. Frontend: materials-matrix panel
  - [x] 5.1 Build the `materials-matrix` feature folder
    - `features/project-execution/materials-matrix/` with `MaterialsMatrixPanel.tsx` (construction /
      finishing tabs, category-grouped rows in a `DataTable`, the dashboard above, localized
      loading / empty / error; read-only, no write controls — identical on `COMPLETED`),
      `components/MatrixRow.tsx` (planned qty + unit, planned brutto, fact qty + unit-mismatch
      indicator, fact brutto, the % выборки fill bar capped + the real uncapped figure,
      over-purchase highlight, linked count, detail disclosure), `components/MaterialDetailDrawer.tsx`
      (linked lines: source document, raw name, manufacturer, qty + unit, `TierBadge`, line total;
      analogs listed together; mismatch lines marked), `components/MatrixDashboard.tsx` (per-branch +
      grand totals), `components/UnitMismatchBadge.tsx` (localized indicator + tooltip),
      `state/matrixModel.ts` (pure display mirror: `fulfilment`/`fill`/`unitEq`/dashboard folds),
      `api/materials-matrix-api.ts` (`getMatrix` via the shared `apiRequest`), `schemas/`, `types/`,
      `__tests__/`. Reuse the FOR-06-04/03 `TierBadge`.
    - _Requirements: 4.2, 4.3, 5.1, 5.2, 5.3, 5.4, 5.5_
    - _Depends on: 3.1, 4.1_

- [x] 6. i18n + tests
  - [x] 6.1 Add i18n keys (PL + RU, mirrored)
    - Add the `materialsMatrix.*` block (design §i18n): page title + branch tabs, columns, fulfilment /
      over-purchased / none / uncapped labels, unit-mismatch badge + tooltip (`{count}`) + excluded
      note, dashboard labels, detail labels + columns + analog note + mismatch mark, and the
      loading / empty / error states, to **both** `pl.json` and `ru.json` with real PL + RU values (no
      English fallback). Reuse `workspace.tab.materials` (FOR-06-00) unchanged. Run the locale-parity
      test.
    - _Requirements: 8.2, 8.3_
    - _Depends on: 5.1_
  - [x] 6.2 Backend example / integration / smoke tests
    - `getMatrix` over a seeded project (planned materials via FOR-05-05b + reconciled lines seeded
      directly): assert the joined rows, the analog aggregation (three distinct products → one row,
      > 100 %, uncapped), the unit-mismatch exclusion + count, the null-brutto `—`, and the dashboard
      totals; a project-scope check that the read never returns another project's lines (R6.3); a
      startup smoke asserting `PermissionAnnotationValidator` classifies `MaterialsMatrixController`
      COMPLETE (R6.2) and that **no new ABAC resource** and **no migration** were added. Run only the
      affected classes with `--tests` → `/tmp/for-06-05-test.log`; verify via JUnit XML. (The fact
      fold, % выборки, unit, exclusion, and dashboard property tests live in 1.1a.)
    - _Requirements: 1.1, 6.2, 6.3, 9.1_
    - _Depends on: 2.1, 3.1_
  - [x] 6.3 Frontend unit tests (Vitest + fast-check)
    - `MaterialsMatrixPanel` render with branch tabs + category groups (R5.1); `MatrixRow` fill bar +
      uncapped figure + over-purchase highlight + unit-mismatch indicator (R5.1, R4.1, R2.2);
      `MaterialDetailDrawer` linked-line listing with analogs + tier badges + mismatch marks (R5.3,
      R4.2, R4.3); `MatrixDashboard` totals (R5.2); the read-only + `COMPLETED` rendering (R5.4).
      **Property 6: Locale parity** over `pl.json` / `ru.json` — **Validates: Requirements 8.2**; plus
      a no-raw-key render assertion (R8.3). Resolver fixtures (`materials` present on
      `ACTIVE`/`COMPLETED` with `PURCHASES`, absent on `DRAFT`, R6.4/R6.5).
    - _Requirements: 2.2, 4.1, 4.2, 4.3, 5.1, 5.2, 5.3, 5.4, 6.4, 6.5, 8.2, 8.3_
    - _Depends on: 5.1, 6.1_

- [x] 7. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via a run-id generator / teardown, a
    regression group, the MD report template). This is an **API + UI** spec: the matrix read model
    (analog aggregation across tiers, uncapped over-purchase, unit-mismatch exclusion, the null-brutto
    `—`, the dashboard totals, the project-scope gate, the ABAC `PURCHASES`/READ gate) is run as **API
    tests** against the Dockerized app (`http://localhost:8080`, auth under `/api/auth`); the
    Materials panel (branch tabs → rows → fill/over-purchase → detail drawer → read-only on
    `COMPLETED`) is a **browser-engine** scenario on an `ACTIVE` project. Planned materials and
    reconciled lines are seeded directly (no live OCR dependency). Written in Russian. Result artifacts
    are MD reports with tables.
  - _Requirements: 1.2, 1.3, 1.4, 1.6, 2.1, 2.2, 3.2, 4.1, 4.2, 5.1, 5.4, 6.1, 6.4_
  - _Depends on: 3.1, 5.1, 6.1, 6.2, 6.3_

- [x] 8. Final verification + checkpoint
  - Compile backend (`./gradlew compileJava compileTestJava` → temp log) and run only the affected
    test classes with `--tests` → `/tmp/for-06-05-test.log` (verify via JUnit XML); type-check +
    unit-test the frontend; confirm the locale-parity test passes and the app starts (no
    `PermissionAnnotationValidator` failure, no new ABAC resource, no migration). Confirm the
    `materials` tab appears for a permitted caller on an `ACTIVE`/`COMPLETED` project and the matrix
    renders the plan × fact rows, the money-based % выборки with analog aggregation (> 100 % uncapped
    + highlight), the unit-mismatch indicator, the dashboard totals, and the per-material detail, and
    that it is read-only. Ensure all tests pass; ask the user if questions arise. Then stage and commit
    per repo (two commits — root for backend/spec, `foremen-frontend/` for the panel) — only if the
    user asks.
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
  FOR-05-05b materials list and the FOR-06-04 reconciled lines, riding the FOR-06-03 `PURCHASES`
  resource (READ).

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
