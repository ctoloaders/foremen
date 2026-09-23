# Implementation Plan: FOR-05-04-UI — Estimate packages UI alignment & material-side collapse

## Overview

This plan implements the design in incremental, integrated steps. It spans **two git repos**
(per `.kiro/steering/git-repo-structure.md`):

- **Frontend** (`foremen-frontend/`, nested repo): the four re-aligned features, the two new UIs
  (assortment, formula), route gating, and locale keys. Frontend commits are made **from inside
  `foremen-frontend/`**.
- **Backend + spec** (root repo): the construction-material collapse migration, entity/resolver/DTO
  cleanup, and the consumption write-path cleanup. Backend/spec commits are made **from the root
  repo**.

The material collapse (Requirement 5) is a single logical change that spans both repos, so it needs
**two commits — one per repo** (frontend CM screen changes; backend migration + resolver + DTOs).

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing. Run only the
affected test classes with `--tests`, redirect to a temp log, and read the JUnit result XML
(`foremen-backend/build/test-results/test/TEST-<fqcn>.xml`). Use `compileJava` / `compileTestJava`
for compile-only checks and `getDiagnostics` for fast per-file checks. Run the full suite only if
the user explicitly asks (warn that it takes ~20 minutes).

**Languages.** Frontend in TypeScript/React (Vite, react-hook-form + zod, TanStack Query, shared
`DataTable` / `AsyncEntitySelect`). Backend in Java (Spring Boot / JPA / MapStruct / Liquibase).

## Tasks

- [x] 1. Backend: construction-material collapse migration (archive-before-drop, idempotent)
  - [x] 1.1 Author the Liquibase changeset and register it last in the changelog
    - Create `foremen-backend/database_files/changesets/NNN-archive-and-drop-construction-material-packages.xml` with two `<changeSet>`s: (1) create `construction_material_packages_archive` (`construction_material_id`, `offer_package_id`, `archived_at`) and `INSERT … SELECT *, now()` from `construction_material_packages`; (2) drop the `construction_material_packages` join table and its FKs.
    - Guard both changesets with `preConditions onFail="MARK_RAN"` (`sqlCheck expectedResult="0"` on the archive table's existence for step 1; a table-existence precondition for the drop in step 2) so a re-run against an already-collapsed DB changes nothing.
    - Register the file **last in sequence** in `foremen-backend/database_files/changelog.xml`.
    - _Requirements: 5.4, 5.9, 8.8, 8.9_
  - [x] 1.2 Write Liquibase migration integration test
    - Assert `construction_material_packages_archive` row count equals the source count pre-drop, the join table is gone after apply, and a re-run makes no schema/data change (idempotent).
    - _Requirements: 5.4, 5.9, 8.8, 8.9_

- [x] 2. Backend: re-key the price-range resolver and DTO by construction-material type
  - [x] 2.1 Re-key `PriceRangeResolver` and `PriceRangeEntry` to type only
    - Change `PriceRangeResolver.PriceRangeKey` to `record PriceRangeKey(Long constructionMaterialTypeId)`; fold MIN/MAX of `retailNet` per type across active, non-null-`retailNet` materials; drop the `packageId` param from `rangeFor(...)`; keep empty buckets as `PriceRange(null, null)` with no fabricated fallback.
    - Re-key the `PriceRangeEntry` DTO to `(constructionMaterialTypeId, min, max)`.
    - _Requirements: 5.3, 5.7_
  - [x] 2.2 Write property test for the re-keyed resolver (jqwik, ≥100 iterations)
    - **Property 1: Construction-material price range is type-keyed, package-independent, unfabricated**
    - **Validates: Requirements 5.3, 5.7**
    - Tag with `// Feature: FOR-05-04-UI-estimate-packages-changes, Property 1: Construction-material price range is type-keyed, package-independent, unfabricated`; run at ≥100 iterations. Generate random materials varying `type`, `retailNet` (incl. `null`), `active`, and (irrelevant-by-design) package assignments; assert the type-keyed range equals MIN..MAX of qualifying `retailNet`, `null..null` when none qualify, and is invariant under any change to package assignment.
    - _Requirements: 5.3, 5.7_
  - [x] 2.3 Write example unit tests for the resolver
    - Single type, multiple types, `null` `retailNet` excluded, inactive excluded, empty bucket → `null..null`.
    - _Requirements: 5.7_

- [x] 3. Backend: drop the package dimension from `ConstructionMaterial` entity and DTOs/requests
  - [x] 3.1 Remove the M:N and clean the DTOs/requests/mappers
    - Remove the `@ManyToMany Set<OfferPackageEntity> packages` from `ConstructionMaterialEntity`.
    - Drop `packages`/`offerPackageIds` from `ConstructionMaterialDtoModel` and `…DtoExtendedModel`; remove `@NotEmpty Set<Long> offerPackageIds` from `ConstructionMaterialCreateRequest`/`…UpdateRequest`; update `ConstructionMaterialControllerMapper` / service mapper accordingly.
    - _Requirements: 5.1, 5.2, 5.3, 5.7_
  - [x] 3.2 Write DTO/mapper unit tests
    - Assert `ConstructionMaterial*` DTOs/requests no longer carry `packages`/`offerPackageIds`.
    - _Requirements: 5.1, 5.2_

- [x] 4. Backend: remove the stale `offerPackageId` from the consumption write path
  - [x] 4.1 Drop the dead `@NotNull offerPackageId` and its resolution
    - Remove `@NotNull Long offerPackageId` from `WorkMaterialConsumptionCreateRequest`/`…UpdateRequest`, remove its `requirePresent`/`requireExisting` checks and the `OfferPackageDao` dependency in `WorkMaterialConsumptionService`, and remove the `offerPackageId` field from the service/extended model. (The entity already has no such field; changeset `084` dropped the column.)
    - _Requirements: 5.5, 5.6_
  - [x] 4.2 Write consumption write-path unit tests
    - Assert `WorkMaterialConsumption*Request` no longer carry `offerPackageId` and create/update succeed without it.
    - _Requirements: 5.6_

- [x] 5. Checkpoint — backend collapse compiles and its tests pass
  - Run `getDiagnostics` on the changed Java files, then `./gradlew compileJava compileTestJava` (redirect to a temp log). Run only the affected test classes with `--tests` (resolver property + example tests, the CM DTO/mapper tests, the consumption tests, and the migration integration test), redirect to a temp log, and verify via the JUnit result XML. Ensure all tests pass, ask the user if questions arise.

- [x] 6. Frontend: works catalog list shows the single cost cell (R1)
  - [x] 6.1 Re-type the work-item row and render three cost columns
    - In `work-catalog/types/index.ts` add `MoneyRangeDto`, `WorkCostCellDto`, and re-type `WorkItemDto` with `costCell` (mirroring `WorkItemDtoModel`); remove `packagePivot`, `WorkCatalogPackageCellDto`, `SeededPackage`, `PivotInfo`.
    - In `WorkCatalogList.tsx` replace the per-package pivot columns with three static columns (`labourPrice`, `construction` range, `finishing` range) read from `row.costCell`; render null `labourPrice` as the localized placeholder; render a `0..0` range literally as `0..0` (do not collapse `min===max` for the `0..0` case); remove `buildColumns`' pivot loop and the `WorkMaterialConsumptionDrillIn` wiring.
    - Remove `useSeededPackages` from `work-catalog/api/query-hooks.ts`; delete `__tests__/WorkCatalogPackageColumn.test.tsx`.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
  - [x] 6.2 Write work-catalog list component tests
    - Renders labour price + construction + finishing as three columns; null labour → placeholder; `0..0` renders literally; no pivot columns.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 7. Frontend: work-item edit selects show names, not ids (R2)
  - [x] 7.1 Seed `selectedLabel` from the list row
    - Pass the clicked row's `workCategoryName` / `unitName` into `WorkItemFormSheet` as `initialWorkCategoryLabel` / `initialUnitLabel`, forwarded to the two `AsyncEntitySelect`s as `selectedLabel`; keep the backend extended DTO untouched.
    - _Requirements: 2.1, 2.2, 2.3_
  - [x] 7.2 Write work-item edit select tests
    - With `selectedLabel` seeded, a closed select shows the localized name rather than `#<id>`.
    - _Requirements: 2.1, 2.2, 2.3_

- [x] 8. Frontend: prices list flat row (R3)
  - [x] 8.1 Re-type and render three columns
    - In `work-prices/types/index.ts` re-type `WorkPriceDto` to the flat `{ id, workItemId, workItemName, currencyId, currencyCode, netPrice }`; remove `PackagePriceDto`, `SeededPackage`, `PivotInfo`.
    - In `WorkPricesList.tsx` render three columns (`workItemName`, `currencyCode`, `netPrice`); remove the pivot-column builder; remove `useSeededPackages` from `work-prices/api/query-hooks.ts`.
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  - [x] 8.2 Write prices list component test
    - Three columns (work item, currency, net price); no pivot columns.
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 9. Frontend: prices form single-price, loads without hanging (R4)
  - [x] 9.1 Re-type the contract and rebuild the form
    - In `work-prices/types/index.ts` re-type `WorkPriceExtendedDto`, `WorkPriceCreateRequest`, `WorkPriceUpdateRequest` to the flat shapes.
    - In `WorkPriceFormSheet.tsx` present three fields — a work-item `AsyncEntitySelect` (`/api/work-items`), a currency `AsyncEntitySelect` (`/api/currencies`, `sort=code,asc`), and a `netPrice` numeric input; remove the `useFieldArray` package rows, `useSeededPackages`, and the `|| packages.length === 0` clause from `isLoadingForm`; prefill edit mode from `WorkPriceExtendedDto` and seed both selects' `selectedLabel` from `workItemName` / `currencyCode`; submit flat `{ workItemId, currencyId, netPrice }`.
    - Update `work-prices/schemas/work-price-schema.ts` to validate `workItemId >= 1`, `currencyId >= 1`, `netPrice ∈ [0, 9_999_999_999.99]`; update `work-prices/api/*` request bodies.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_
  - [x] 9.2 Write prices form component tests (incl. the hang regression)
    - Create shows work-item/currency/net-price fields; **edit prefills and leaves the skeleton** (R4.2 regression); submit body is exactly `{workItemId, currencyId, netPrice}`; no per-package fields.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [x] 10. Frontend: construction materials drop the package dimension (R5, frontend half)
  - [x] 10.1 Remove packages column/multi-select; render one type-keyed range per row
    - Remove the `packages` column from `ConstructionMaterialsList.tsx` and the `PackagesMultiSelect` from `ConstructionMaterialFormSheet.tsx`; delete `components/PackagesMultiSelect.tsx`.
    - Drop `packages`/`offerPackageIds` from the DTOs, create/update requests, and the zod schema's `offerPackageIds` field in `construction-material-schema.ts`.
    - Re-type `PriceRangeEntry` to `{ constructionMaterialTypeId, min, max }`; `renderPriceRanges` picks the entry whose `constructionMaterialTypeId === row.type.id` and drops the `row.packages.map` loop (one range per row).
    - _Requirements: 5.1, 5.2, 5.3_
  - [x] 10.2 Write construction-materials component tests
    - No packages column, no multi-select; exactly one type-keyed range per row.
    - _Requirements: 5.1, 5.2, 5.3_

- [x] 11. Checkpoint — re-aligned frontend screens compile and their tests pass
  - Run the frontend type-check and the component tests for tasks 6–10 (single-run, no watch mode). Ensure all tests pass, ask the user if questions arise.

- [x] 12. Frontend: assortment UI — groups, line items, zł/m² readout (R6)
  - [x] 12.1 Scaffold the assortment feature and mirror the backend DTO types
    - Create `foremen-frontend/src/features/assortment/` (`types/`, `api/{assortment-api,query-hooks,mutation-hooks}.ts`, `schemas/`, `components/`, a page) mirroring the existing feature layout; add `AssortmentGroupDto`, `AssortmentGroupExtendedDto`, `AssortmentLineItemDto`, `AssortmentLineItemCreateRequest`, `PackageZlM2Response` exactly per the design; wire `apiRequest` to `/api/assortment-groups`, `/api/assortment-line-items`, and `GET /api/assortment-line-items/package-zl-m2`.
    - _Requirements: 6.1, 6.2_
  - [x] 12.2 Implement group + line-item CRUD, grouped rendering, and provenance-only typical product
    - Full CRUD for groups (`nameRU`, `namePL`, `sortOrder`) and line items (fields per R6.2) using the shared `DataTable` + Sheet form; line-item form uses `AsyncEntitySelect` for `assortmentGroupId`, `offerPackageId`, and optional `typicalProductId`, seeding `selectedLabel` from resolved names.
    - Render line items grouped by `AssortmentGroup` (group header sorted by `sortOrder` then name, reading `assortmentGroupName` off each row); render `typicalProductName` as a provenance label only — never an input that drives min/avg/max, sent only as the optional `typicalProductId`.
    - _Requirements: 6.1, 6.2, 6.3, 6.4_
  - [x] 12.3 Implement the zł/m² readout with recompute-on-change semantics
    - A component that calls `package-zl-m2?packageCode={code}` via TanStack Query with a query key including the package code and `staleTime: 0`, rendering `value` directly (not multiplied by floor area); invalidate `['package-zl-m2']` on the `onSuccess` of every group/line-item create/update/delete so a re-view refetches the recomputed value; render `0` when the endpoint returns `value = 0`.
    - _Requirements: 6.5, 6.6, 6.8_
  - [x] 12.4 Write assortment component tests
    - Group + line-item CRUD forms; rows grouped by group; typical product shown as provenance and never sent as a value; zł/m² readout shows the endpoint `value`, is not multiplied by area, and a mutation invalidates the readout query so a re-view refetches.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.8_

- [x] 13. Frontend: formula UI — default volume formula + per-package override (R7)
  - [x] 13.1 Mirror the formula DTO types and add the editors to the work-item edit flow
    - In `work-catalog/` add `WorkVolumeFormulaDto`, `WorkVolumeFormulaCreateRequest`, `WorkPackageOverrideDto`, `WorkPackageOverrideCreateRequest` per the design; wire `/api/work-volume-formulas` (CRUD, one default per work item) and `/api/work-package-overrides` (CRUD).
    - Add a default-volume-formula editor (free `sourceText`; the UI only sends `sourceText`) and a per-package membership + override editor (one row per offer package with a `member` toggle and `overrideSourceText`, no price field anywhere).
    - _Requirements: 7.1, 7.2, 7.4_
  - [x] 13.2 Enforce present-override-implies-member and translate the five error keys
    - When `overrideSourceText` is entered, force `member = true` and disable un-checking while text is present.
    - On a rejected submit, display the backend's localized message inline for `error.formula.unknown.variable`, `error.formula.unknown.work.reference`, `error.formula.illegal.operator`, `error.formula.cycle`, `error.formula.division.by.zero`; never surface a raw key (use a `formulaErrors.*` locale fallback).
    - _Requirements: 7.3, 7.5, 7.6, 7.7, 7.8, 7.9, 7.10_
  - [x] 13.3 Write formula UI component tests
    - Default-formula and per-package override editors; entering `overrideSourceText` forces `member = true`; no price field present; each of the five error keys renders its localized message and never the raw key.
    - _Requirements: 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 7.10_

- [x] 14. Frontend: route gating, entry points, and locale parity (R8)
  - [x] 14.1 Gate the assortment route and wire entry points
    - Add the new assortment route(s) to `extra` in `config/route-permissions.ts` with `{ resource: 'PACKAGE_ASSORTMENT', operation: 'READ' }` (deny → redirect to `/403`); wire navigation in `config/navigation.ts` and `app/router.tsx`. Add the "Manage assortment / view zł/m²" action to `OfferPackageFormSheet` (edit mode) that navigates to the assortment route scoped to the package `code`, and show the package's zł/m² readout inline in the edit sheet.
    - _Requirements: 6.7, 8.5_
  - [x] 14.2 Add all new/changed locale keys to both `pl.json` and `ru.json`
    - Add every new/changed key under `workCatalog.*`, `workPrices.*`, `constructionMaterials.*`, `offerPackages.*`, and the new `assortment.*` and `formulaErrors.*` namespaces to **both** locale files; ensure the UI never renders a raw key.
    - _Requirements: 8.6, 8.7_
  - [x] 14.3 Write route-gating and locale-parity tests
    - Guard test: a caller lacking `PACKAGE_ASSORTMENT` READ is redirected from the assortment route; existing gates for `WORK_CATALOG`, `WORK_PRICES`, `MATERIALS_CONSTRUCTION`, `OFFER_PACKAGES` still hold. Automated key-set equality check over `pl.json` / `ru.json`.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_

- [x] 15. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via run-id generator and/or teardown, a regression group, and an MD report template with tables). This is a **full-stack** spec, so scenarios cover both **browser-engine UI flows** (re-aligned works/prices/construction-materials screens, the assortment CRUD + zł/m² readout, the formula editors) and **API tests against the Dockerized app** (`http://localhost:8080`, auth under `/api/auth`) for the construction-material collapse, the assortment endpoints (incl. `package-zl-m2` recompute), the formula error keys, and route gating. Result artifacts are MD reports with tables. `test-cases.md` is written in Russian.
  - _Requirements: 1.1-1.5, 2.1-2.3, 3.1-3.4, 4.1-4.5, 5.1-5.9, 6.1-6.8, 7.1-7.10, 8.1-8.10_

- [x] 16. Final checkpoint — full verification and per-repo commits
  - Frontend: run the frontend type-check and the component/guard/locale tests (single-run). Backend: `getDiagnostics` + `compileJava`/`compileTestJava`, then run only the affected test classes with `--tests` (redirect to a temp log; verify via JUnit XML). Ensure all tests pass, ask the user if questions arise.
  - Commit per the two-repo rule: frontend changes committed **from inside `foremen-frontend/`** (assortment feature, the four re-aligned features, `config/*`, `locales/*`); backend migration + entity + resolver + DTOs + consumption cleanup and the spec docs committed **from the root repo**. The material collapse spans both repos → one commit per repo. Only commit when the user asks; push to a new branch only; no force-push; no git-config changes.

## Notes

- Tasks marked with `*` are optional (unit/property/integration/component tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirement sub-clauses for traceability.
- The single Correctness Property (Property 1) has a dedicated jqwik property-test sub-task (2.2) at ≥100 iterations, tagged with the feature name and property title.
- Backend tests: run only affected classes with `--tests`, redirect to a temp log, and read the JUnit result XML; use `compileJava`/`compileTestJava` and `getDiagnostics` for cheap checks. Full suite only on explicit request (~20 min).
- The material collapse (R5) spans both git repos, so it needs two commits — one in `foremen-frontend/` (CM screen) and one in the root repo (migration + resolver + DTOs).
- `PACKAGE_ASSORTMENT` was already seeded by backend changeset `089`; the only ABAC work here is the frontend route gate (task 14.1). No new backend seed/annotation is needed.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "6.1", "7.1", "8.1", "9.1", "10.1", "12.1", "13.1"] },
    { "id": 1, "tasks": ["1.2", "2.2", "2.3", "3.1", "4.1", "6.2", "7.2", "8.2", "9.2", "10.2", "12.2", "13.2", "14.1", "14.2"] },
    { "id": 2, "tasks": ["3.2", "4.2", "12.3"] },
    { "id": 3, "tasks": ["12.4", "13.3", "14.3"] },
    { "id": 4, "tasks": ["15"] }
  ]
}
```

## Addendum — Follow-up tasks (iterative work on top of the original plan)

These general tasks capture the assortment rework and UI iterations delivered after the original
plan. They are recorded as completed for traceability.

- [x] A1. Rework assortment to positions + per-package prices
  - Replace `AssortmentLineItem` with `AssortmentPosition` (material-type-backed, `UNIQUE (group,
    material_type)`) + `AssortmentPositionPrice` (per `(position, package)` min/avg/max). Add group
    `referenceQty`/`referenceUnit` and the `offer_packages.zl_m2` cache. Archive-and-drop the legacy
    table; migrate matching rows. Idempotent Liquibase changesets 094–100.
  - _Requirements: A1, A8_

- [x] A2. Per-price quantity overrides + MAX headline
  - Add `min_qty`/`avg_qty`/`max_qty` (changeset 101). Rewrite `PackageZlM2Resolver` to
    `Σ(price × qty)/50` per band; resolve effective qty (override ?? group `referenceQty`); compute
    and persist the MAX headline. Allow override `≥ 0` (0 legitimate).
  - _Requirements: A2, A3_

- [x] A3. Clear-qty endpoint (immediate, untracked)
  - `POST /api/assortment-positions/package-clear-qty` nulls one band's qty override in place
    (`PACKAGE_ASSORTMENT` UPDATE); frontend clear (✕) control drops the local pending edit and calls
    it immediately.
  - _Requirements: A3_

- [x] A4. All-packages editor (no picker) + group-scoped headings
  - Render every package as side-by-side price columns over shared groups/positions; column heading
    shows whole-package MAX on the name line and group-scoped Bieżące (MAX) + min…avg.
  - _Requirements: A2, A4_

- [x] A5. Group/position inline edit + empty-group visibility
  - Edit action (left of delete) for groups and positions; empty groups still render.
  - _Requirements: A6_

- [x] A6. Locale-tolerant decimal inputs
  - Shared `NumberInput` accepts comma/dot and preserves mid-typed decimals across the editor.
  - _Requirements: A5_

- [x] A7. Finishing-material price picker
  - Magnifier per cell opens the finishing-materials catalog filtered by `type.id` + `packages.id`,
    sorted by price desc; row select → price-field choice populates the cell. Per-target DataTable
    instance to avoid stale cache/closures.
  - _Requirements: A7_

- [x] A8. Verification + i18n
  - Backend affected-class tests green; frontend `tsc` + assortment/number-input tests pass;
    new/changed strings provided in `pl.json` and `ru.json`.
  - _Requirements: A2, A3, A4, A5, A6, A7_
