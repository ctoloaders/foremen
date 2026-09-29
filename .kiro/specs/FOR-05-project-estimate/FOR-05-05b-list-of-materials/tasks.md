# Implementation Plan: FOR-05-05b — List of materials (the Materials tab)

## Overview

This plan implements the design in incremental, integrated steps that build on each other and end
with wiring the Materials tab together. It is a **read-and-annotate projection over the kosztorys**
(FOR-05-05): the only genuinely new persisted state is a project-level JSONB reserve map; every
quantity, price, and the fulfilment percentage are **derived** from the existing estimate graph via a
new read-only assembler — there is no second quantity or price formula.

The feature spans **two git repos** (per `.kiro/steering/git-repo-structure.md`):

- **Backend + migration + spec** (root repo): the single new `estimates.materials_reserve_map jsonb`
  column (changeset `124`), the `MaterialsReserveMap` Java model on `EstimateEntity`, the pure
  `MaterialsListAssembler` (concrete projection + per-room aggregation + the canonical
  `effectiveQuantity` helper + dashboard folds), the `MaterialsListService` (read model + DRAFT-gated,
  server-validated reserve write), the new `EstimateMaterialsController` on the reused `ESTIMATE`
  resource, and the read-model DTOs. Backend/spec commits are made **from the root repo**.
- **Frontend** (`foremen-frontend/`, nested repo): the `features/materials/*` feature (the
  `workspaceTabs.ts` tab registration, `MaterialsTab`, `MaterialsMatrix` + branch groups,
  `MaterialsDashboard`, `MaterialDetailPopup`, `ReserveInput` + `MassApplyControl`, the pure
  `materialsModel.ts`, the `reserveDraftStore`, the api layer) and the `materials.*` /
  `workspace.tab.materials` locale keys. Frontend commits are made **from inside `foremen-frontend/`**.

This feature spans both repos, so it needs **two commits — one per repo** (backend + migration + spec
in the root; all frontend source in `foremen-frontend/`).

**Entity-creation checklist note.** No **new** managed ABAC resource is introduced: the `ESTIMATE`
resource, its ADMIN CRUD / MANAGER CRU / FOREMAN·WORKER·FINANCIER READ grants (changeset `081`), and
`@PermissionResource("ESTIMATE")` on the estimate controllers are already shipped by FOR-05-03. The
reserve map lives on the existing `estimates` table (guarded transitively through `ESTIMATE`), so this
spec adds a **column, not a managed entity** — the checklist's seed-a-resource / annotate-a-controller
steps are already satisfied. The new `EstimateMaterialsController` MUST still be fully annotated
(`@PermissionResource("ESTIMATE")` at the class + method-level `@RequiresPermission` READ/UPDATE) or
`PermissionAnnotationValidator` fails startup. `MaterialsListService` stays a `ProjectScopedService`
with `getProjectIdPath()` resolving to the estimate's owning project.

**Migration.** The single new Liquibase changeset is `124`, follows the `NOT columnExists` /
`onFail="MARK_RAN"` conventions (mirroring `119-add-estimate-applied-package-code.xml`), and is
appended **last** in `changelog.xml` after `123`. The implementer MUST confirm `124` is the next free
number at build time.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing. Run only the
affected test classes with `--tests`, redirect to a temp log, and read the JUnit result XML
(`foremen-backend/build/test-results/test/TEST-<fqcn>.xml`). Use `compileJava` / `compileTestJava`
for compile-only checks and `getDiagnostics` for fast per-file checks. Run the full suite only if the
user explicitly asks (warn that it takes ~20 minutes).

**Languages.** Backend in Java (Spring Boot / JPA / MapStruct / Liquibase / jqwik). Frontend in
TypeScript/React (Vite, react-hook-form + zod, TanStack Query, zustand, the shared `DataTable` /
`AsyncEntitySelect`, `lucide-react`, i18next); property tests in fast-check + Vitest.

## Tasks

- [x] 1. Backend: reserve-map schema addition (idempotent Liquibase changeset, registered last)
  - [x] 1.1 Create the `materials_reserve_map` column changeset (`124`)
    - Create `foremen-backend/database_files/changesets/124-add-estimate-materials-reserve-map.xml` adding a nullable `materials_reserve_map jsonb` column to the shipped `estimates` table (one map per project, since `estimates.project_id` is UNIQUE), mirroring `119-add-estimate-applied-package-code.xml`.
    - Guard with `preConditions onFail="MARK_RAN"` (`NOT columnExists estimates.materials_reserve_map`) so a re-run against an already-migrated database makes no schema change. Register the file **last** in `changelog.xml` after `123` (confirm `124` is the next free number at build time).
    - _Requirements: 4.2_
  - [x] 1.2 Write the migration integration test
    - Assert the `materials_reserve_map` column exists post-apply and is JSONB/nullable; assert a re-run of `124` is a no-op (idempotent). Follow the shipped migration-integration-test pattern (Testcontainers).
    - _Requirements: 4.2_

- [x] 2. Backend: `MaterialsReserveMap` model on `EstimateEntity`
  - [x] 2.1 Add the `MaterialsReserveMap` record and map it as a JSONB column
    - Create the immutable `MaterialsReserveMap(Map<Long, ReserveEntry> byMaterialId)` record with the nested `ReserveEntry(BigDecimal percent, BigDecimal asIsQty, BigDecimal effectiveQty, BigDecimal bruttoTotal)` per design B1, serialized by Jackson (mirroring `RoomGeometry`). Add the `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "materials_reserve_map", columnDefinition = "jsonb") private MaterialsReserveMap materialsReserveMap;` field to `EstimateEntity` (nullable ⇒ "no reserves set").
    - Only `percent` is authoritative input; `asIsQty`/`effectiveQty`/`bruttoTotal` are computed on each write for display/reuse but are never trusted on read. A null column, an absent key, or a null/`0` percent all mean identity (no reserve).
    - _Requirements: 4.2, 4.5, 12.5_
  - [x] 2.2 Write property test for the reserve-map round-trip + stale keys (jqwik, ≥100 iterations)
    - **Property 4: Reserve map round-trips and tolerates stale keys**
    - **Validates: Requirements 4.2, 12.5**
    - `MaterialsReserveMapPropertyTest`, tagged `// Feature: for-05-05b-list-of-materials, Property 4: Reserve map round-trips and tolerates stale keys`, ≥100 iterations. Generate valid reserve maps (materialId → percent); assert JSONB serialize/deserialize yields an equal per-material percent set, a stale `materialId` (absent from a given kosztorys id-set) is ignored without error, and a missing/`null` column or absent key reads as identity.
    - _Requirements: 4.2, 12.5_

- [x] 3. Backend: `MaterialsListAssembler` (the read-only projection core)
  - [x] 3.1 Implement the canonical `effectiveQuantity` helper and the concrete-material projection
    - Create `MaterialsListAssembler` (`@Component`) per design B2 — a pure, stateless projection over the shipped `EstimateMatrixDto` (from `EstimateMatrixAssembler`) plus the estimate's `vatRate` and `materialsReserveMap`. Implement the single canonical `static BigDecimal effectiveQuantity(BigDecimal asIs, ConsumptionBasis basis, BigDecimal percent)` helper (as-is → reserve → ceiling for `PER_UNIT`; identity for `PER_ROOM`; `asIs == 0` ⇒ `0`, never a spurious ceiling) shared by the row total, the row brutto, and the dashboard effective figures so they never drift. Walk every kosztorys cell's `MaterialLineDto`s, keep only lines with a `concreteMaterialId` (skip Placeholders), group by branch then by distinct concrete material id, and fold: per-room as-is qty (`Σ MaterialLineDto.quantity`), per-room brutto (`asIsCellQty × concreteNet × (1 + vat/100)`, net verbatim), `Row_Total` as-is and effective, the effective row brutto, and the basis classification (a row is `PER_ROOM` only when ALL its contributing lines are `PER_ROOM`). Keep the fold in pure static helpers so it is property-testable without Spring.
    - _Requirements: 1.2, 1.3, 1.5, 2.2, 2.4, 2.5, 3.2, 3.3, 3.4, 4.3, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 7.1, 7.2, 7.3, 7.4, 12.3_
  - [x] 3.2 Implement the dashboard folds and the null-net / fulfilment handling
    - Add the per-branch and grand-total dashboard folds (consumption + price, as-is vs effective, in net and brutto), passing the kosztorys `fillIndicatorPct` through verbatim as `fulfilmentPct`. A row whose `concreteNet == null` shows quantities, renders brutto/net as `—`, and is **excluded** from every money total (not treated as zero). Prune/ignore stale reserve keys (materialIds absent from the kosztorys) on read.
    - _Requirements: 6.2, 6.3, 6.4, 6.5, 12.4, 12.5_
  - [x] 3.3 Write property test — concrete projection + branch partition (jqwik, ≥100 iterations)
    - **Property 1: Materials rows are exactly the distinct concrete materials, partitioned by branch**
    - **Validates: Requirements 1.2, 1.3, 1.5**
    - `MaterialsListAssemblerPropertyTest` (pure fold), tagged with the feature name + property title, ≥100 iterations. Generate kosztorys read models (cells with any mix of concrete/placeholder lines, materials repeated across cells/rooms); assert exactly one row per distinct `(branch, concreteMaterialId)`, no Placeholder-only row, every row in its own branch group, and the branch groups partition the rows.
    - _Requirements: 1.2, 1.3, 1.5_
  - [x] 3.4 Write property test — per-room aggregation + net→brutto (jqwik, ≥100 iterations)
    - **Property 2: Per-room quantity is the summed resolved Physical_Quantity and price is net×VAT**
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5, 7.1, 7.2, 7.3**
    - In `MaterialsListAssemblerPropertyTest`, a single jqwik test tagged with the feature name + property title, ≥100 iterations. Assert the `Room_Cell` as-is qty equals `Σ MaterialLineDto.quantity` over the room's cells using the material (empty when not consumed), no reserve/roundup at cell level, and the cell brutto equals `asIsCellQty × concreteNet × (1 + vat/100)` with net used verbatim.
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 7.1, 7.2, 7.3_
  - [x] 3.5 Write property test — `Effective_Quantity` reserve→ceiling / basis (jqwik, ≥100 iterations)
    - **Property 3: Effective_Quantity is reserve-then-ceiling for PER_UNIT and identity for PER_ROOM**
    - **Validates: Requirements 3.2, 3.3, 3.4, 4.3, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 7.4, 12.3**
    - In `MaterialsListAssemblerPropertyTest`, a single jqwik test over the pure `effectiveQuantity` core, tagged with the feature name + property title, ≥100 iterations. Assert `PER_UNIT` ⇒ `ceil(q × (1 + p/100))`, `PER_ROOM` ⇒ `q` unchanged, order aggregate→reserve→ceiling (never ceiling-before-reserve), `p`=0/unset ⇒ `ceil(q)`, `q`=0 ⇒ `0`, and the row brutto = `effectiveQty × concreteNet × (1 + vat/100)`.
    - _Requirements: 3.2, 3.3, 3.4, 4.3, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 7.4, 12.3_
  - [x] 3.6 Write property test — dashboard totals (jqwik, ≥100 iterations)
    - **Property 7: Dashboard totals sum per-material contributions per branch and project-wide, as-is vs effective, net vs brutto, excluding null-priced**
    - **Validates: Requirements 6.2, 6.3, 6.4, 12.4**
    - In `MaterialsListAssemblerPropertyTest`, a single jqwik test tagged with the feature name + property title, ≥100 iterations. Assert each per-branch total (and the grand total) equals the sum over that branch's rows (resp. all rows) of the row's net (`qty × concreteNet`) and brutto (`× (1 + vat/100)`) contribution — as-is from the as-is qty, effective from the effective qty — a null-`concreteNet` row contributes nothing, and the grand total equals the sum of the per-branch totals.
    - _Requirements: 6.2, 6.3, 6.4, 12.4_

- [x] 4. Backend: read-model DTOs
  - [x] 4.1 Add the materials read-model DTOs
    - Add `MoneyBrutto(net, brutto)`, `MaterialRoomCellDto(roomId, quantity, unit, price)`, `MaterialRowDto(materialId, materialName, branch, basis, unit, netUnitPrice, bruttoUnitPrice, cells, asIsTotalQty, reservePercent, effectiveTotalQty, rowTotalPrice)`, `BranchDashboardDto(branch, asIsTotal, effectiveTotal)`, the thin `MaterialsRoomColumnDto` / `MaterialBranchGroupDto` wrappers, and the top-level `MaterialsListDto(projectId, editable, rooms, branches, branchDashboards, grandTotal, fulfilmentPct)` per the design's Read model DTOs. Money is net+brutto pairs; a null net/brutto renders `—` and is excluded from totals. Wire `MaterialsListAssembler` (tasks 3.1–3.2) to build these.
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2, 6.2, 6.3, 6.5, 12.4_

- [x] 5. Backend: `MaterialsListService` (read model + reserve write)
  - [x] 5.1 Implement the read model and the `editable` resolution
    - Create `MaterialsListService` (`ProjectScopedService`, `getProjectIdPath()` → the estimate's project, exactly like `EstimateAssignmentService`). `getMaterials(projectId, editable)`: resolve the project's estimate (reusing the shipped kosztorys get-or-create so a pre-estimate project returns an empty structure, not `404`), obtain the kosztorys matrix, and delegate to `MaterialsListAssembler`; read-only and independent of DRAFT (R10.3). `isDraft(projectId)`: reuse `EstimateAssignmentService.isDraft` (or the same lifecycle check) to compute the `editable` flag.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 3.1, 6.5, 10.1, 10.3, 12.1, 12.2_
  - [x] 5.2 Implement `saveReserveMap` with the DRAFT gate, server validation, stale-key pruning, and recompute
    - `saveReserveMap(projectId, request)`: resolve the DRAFT estimate; call `DraftGateGuard.assertDraft` (409 `error.estimate.locked` otherwise, R10.2); **server-validate every entry** (percent in `[0..100]`, ≤2 decimals; reject the whole write on any invalid/malformed entry with a localized message, R9.4); prune stale keys (materialIds absent from the kosztorys, R12.5); recompute each entry's `asIsQty`/`effectiveQty`/`bruttoTotal` using the same computation core the assembler uses; persist the JSONB column; return the refreshed read model. There is no branch-level stored value — the request carries the client-expanded per-material list only.
    - _Requirements: 4.2, 4.3, 4.4, 8.3, 9.4, 10.1, 10.2, 12.5_
  - [x] 5.3 Write property test — reserve validation bounds (jqwik, ≥100 iterations)
    - **Property 6: Reserve validation accepts a value iff it is in [0..100] with at most two decimals**
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.4**
    - `ReserveValidationPropertyTest` over the server bounds (identical to the client zod bounds), tagged with the feature name + property title, ≥100 iterations. Generate candidate percents (in/out of range, >2 decimals, non-numeric, empty); assert accept iff a number in `[0,100]` with ≤2 decimals, empty ⇒ unset (identity), and a map write is accepted iff **every** entry is valid (any invalid entry rejects the whole write).
    - _Requirements: 9.1, 9.2, 9.3, 9.4_
  - [x] 5.4 Write service integration tests (Spring)
    - `getMaterials` returns the projection over a seeded estimate (1.x, 2.x, 6.x); `saveReserveMap` persists the JSONB and recomputes/stores totals (4.2, 4.3); reserve save on a non-DRAFT estimate → `409 error.estimate.locked` (10.2); the read works regardless of lifecycle (10.3).
    - _Requirements: 4.2, 4.3, 10.2, 10.3_

- [x] 6. Backend: `EstimateMaterialsController` (endpoints on the ESTIMATE resource)
  - [x] 6.1 Implement the controller endpoints with ABAC annotations
    - Create `@RestController @RequestMapping("/api/estimates") @PermissionResource("ESTIMATE")` `EstimateMaterialsController` (a second controller on the same resource is fine — `@PermissionResource` is per-class), fully annotated so `PermissionAnnotationValidator` classifies it COMPLETE at startup. `GET /project/{projectId}/materials` (`@RequiresPermission READ`) returns the `MaterialsListDto`; the `editable` flag = project DRAFT ∧ caller has `ESTIMATE` UPDATE (resolved exactly like `EstimateMatrixController#resolveEditable`, reusing `ForemenPermissionEvaluator` + `isDraft`). `PUT /project/{projectId}/materials/reserve` (`@RequiresPermission UPDATE`) accepts the `MaterialsReserveRequest` (`{ entries: [{ materialId, percent }] }`, already mass-apply-expanded by the client), persists via `MaterialsListService.saveReserveMap`, and returns the refreshed read model. Method annotations take precedence over the class default. No new ABAC resource/operation/role grant is introduced.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 10.2, 10.3_
  - [x] 6.2 Write ABAC + startup-annotation integration tests
    - The materials read requires `ESTIMATE` READ (8.1); the reserve PUT requires `ESTIMATE` UPDATE and is rejected server-side without it (8.3); no new resource/operation/role grant is seeded (8.4); `PermissionAnnotationValidator` asserts the new controller is fully annotated at startup.
    - _Requirements: 8.1, 8.3, 8.4_

- [x] 7. Checkpoint — backend compiles and its tests pass
  - Run `getDiagnostics` on the changed Java files, then `./gradlew compileJava compileTestJava` (redirect to a temp log). Run only the affected test classes with `--tests` (`MaterialsReserveMapPropertyTest`, `MaterialsListAssemblerPropertyTest`, `ReserveValidationPropertyTest`, the `MaterialsListService` integration test, the ABAC/startup test, and the migration integration test), redirect to a temp log, and verify via the JUnit result XML. Ensure all tests pass, ask the user if questions arise.

- [x] 8. Frontend: feature scaffold, read-model types, and API layer
  - [x] 8.1 Scaffold `features/materials/` and mirror the backend read-model types
    - Create `foremen-frontend/src/features/materials/` (`types/`, `api/`, `state/`, `components/`, `schemas/`) following the repo layout; add `MoneyBrutto`, `MaterialRoomCell`, `MaterialRow`, `BranchDashboard`, `MaterialsListDto`, `ReserveEntryRequest`, `MaterialsReserveRequest` exactly per the design's frontend read model.
    - _Requirements: 1.1, 1.2, 2.1, 4.2_
  - [x] 8.2 Implement `api/materials-api.ts`
    - Wire `apiRequest` to `GET /api/estimates/project/{projectId}/materials` (read model) and `PUT /api/estimates/project/{projectId}/materials/reserve` (the mass-apply-expanded per-material list), with TanStack Query query/mutation hooks.
    - _Requirements: 2.1, 4.2, 4.4, 8.1, 8.3_

- [x] 9. Frontend: pure client model (property-tested core)
  - [x] 9.1 Implement `state/materialsModel.ts`
    - Pure module mirroring the backend assembler per design F5 so the tab reflects a staged reserve change live without a round-trip: `projectRows(readModel)` (distinct concrete rows, branch-partitioned), `roomCellAgg`, `effectiveQuantity(asIs, basis, percent)` (identical reserve→ceiling→basis rule, R7.4), `rowTotals`, and `dashboardTotals` (per-branch + grand, as-is vs effective, net vs brutto, null-net excluded). Consumes the read model + the draft reserve edits.
    - _Requirements: 1.2, 1.5, 2.2, 2.4, 3.2, 3.3, 3.4, 5.2, 6.2, 6.3, 6.4, 7.4, 12.3, 12.4_
  - [x] 9.2 Write property test — concrete projection + branch partition (fast-check, ≥100 iterations)
    - **Property 1: Materials rows are exactly the distinct concrete materials, partitioned by branch**
    - **Validates: Requirements 1.2, 1.3, 1.5**
    - `features/materials/state/materialsModel.property.test.ts`, a single fast-check test tagged with the feature name + property title, ≥100 iterations.
    - _Requirements: 1.2, 1.3, 1.5_
  - [x] 9.3 Write property test — per-room aggregation + net→brutto (fast-check, ≥100 iterations)
    - **Property 2: Per-room quantity is the summed resolved Physical_Quantity and price is net×VAT**
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5, 7.1, 7.2, 7.3**
    - In `features/materials/state/materialsModel.property.test.ts`, a single fast-check test tagged with the feature name + property title, ≥100 iterations.
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 7.1, 7.2, 7.3_
  - [x] 9.4 Write property test — `Effective_Quantity` reserve→ceiling / basis (fast-check, ≥100 iterations)
    - **Property 3: Effective_Quantity is reserve-then-ceiling for PER_UNIT and identity for PER_ROOM**
    - **Validates: Requirements 3.2, 3.3, 3.4, 4.3, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 7.4, 12.3**
    - In `features/materials/state/materialsModel.property.test.ts`, a single fast-check test over the pure `effectiveQuantity` core (sharing the identical rule with the backend), tagged with the feature name + property title, ≥100 iterations.
    - _Requirements: 3.2, 3.3, 3.4, 4.3, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 7.4, 12.3_
  - [x] 9.5 Write property test — dashboard totals (fast-check, ≥100 iterations)
    - **Property 7: Dashboard totals sum per-material contributions per branch and project-wide, as-is vs effective, net vs brutto, excluding null-priced**
    - **Validates: Requirements 6.2, 6.3, 6.4, 12.4**
    - In `features/materials/state/materialsModel.property.test.ts`, a single fast-check test tagged with the feature name + property title, ≥100 iterations.
    - _Requirements: 6.2, 6.3, 6.4, 12.4_

- [x] 10. Frontend: reserve draft store, schema, and mass-apply transform
  - [x] 10.1 Implement `state/reserveDraftStore.ts` and the mass-apply expansion
    - Per-project zustand store holding the pending per-material reserve edits per design F5, with a pure `massApply(scope, percent)` transform that **expands** a branch- or project-wide set into per-material entries (there is no separate branch-level stored value); `Save` maps the draft to the `MaterialsReserveRequest` (the expanded per-material list) and clears the store on success.
    - _Requirements: 4.1, 4.4, 4.7_
  - [x] 10.2 Implement the client reserve zod schema
    - `schemas/reserve.ts`: a zod schema validating a percent (0..100, ≤2 decimals; empty ⇒ unset/identity) with a localized validation message, reused by `ReserveInput`.
    - _Requirements: 9.1, 9.2, 9.3_
  - [x] 10.3 Write property test — mass-apply transform (fast-check, ≥100 iterations)
    - **Property 5: Mass-apply writes the same percent to exactly the in-scope materials**
    - **Validates: Requirements 4.4**
    - `features/materials/state/reserveMassApply.property.test.ts`, a single fast-check test tagged with the feature name + property title, ≥100 iterations. Assert the transform assigns `p` to every in-scope material, leaves out-of-scope entries unchanged, and creates no branch-level or non-material key.
    - _Requirements: 4.4_
  - [x] 10.4 Write property test — reserve validation bounds (fast-check, ≥100 iterations)
    - **Property 6: Reserve validation accepts a value iff it is in [0..100] with at most two decimals**
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.4**
    - `features/materials/schemas/reserve.property.test.ts` over the client zod schema (identical bounds to the server), tagged with the feature name + property title, ≥100 iterations.
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

- [x] 11. Checkpoint — pure frontend core compiles and its property tests pass
  - Run the frontend type-check and the property tests for the materials model, the mass-apply transform, and the reserve schema (single-run, no watch mode). Ensure all tests pass, ask the user if questions arise.

- [x] 12. Frontend: matrix, branch groups, dashboard, and detail popup
  - [x] 12.1 Implement `MaterialsMatrix` + branch groups
    - Per design F2: rows = materials grouped by branch (construction, finishing), columns = rooms in the read model's stable order, a trailing `Row_Total` column. Each `Room_Cell` shows the aggregated quantity + unit and the brutto price; a room the material is not consumed in renders an empty placeholder. The `Row_Total` shows both figures (as-is and effective) plus the effective row brutto. An `Absolute_Per_Room_Material` row renders per-room/total like any other (only its reserve input is disabled and effective == as-is). Empty state (no concrete materials) renders the localized message and no rows; no rooms renders the dashboard + header with no columns and the empty state, no error.
    - _Requirements: 1.4, 1.5, 2.1, 2.2, 2.3, 3.1, 3.2, 5.3, 12.1, 12.2, 12.3, 12.4_
  - [x] 12.2 Implement `MaterialsDashboard`
    - Per design: render above the matrix the per-branch and grand-total summaries (consumption + total price, as-is vs effective, net + brutto) from `materialsModel.dashboardTotals`, plus the Materials_Fulfilment percentage passed through verbatim from the read model's `fulfilmentPct`.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
  - [x] 12.3 Implement `MaterialDetailPopup`
    - Opened from a row per design F3; shows the material's full catalog + estimate detail (name/label, branch, type, unit, net and brutto price, per-room breakdown, as-is total, reserve %, effective total, totals price). The inline table row stays the compact quantity+unit / brutto view.
    - _Requirements: 2.6_
  - [x] 12.4 Write matrix/dashboard/popup component tests
    - Row/column rendering and stable order (2.1); `Room_Cell` qty+unit / brutto and empty placeholder (2.2, 2.3); the two `Row_Total` figures + effective brutto (3.1, 3.2); `Absolute_Per_Room_Material` renders normally with reserve disabled (5.3, 4.6); detail popup content (2.6); fulfilment % passthrough equals the kosztorys value (6.5); empty state (1.4, 12.1, 12.2); null-net `—` render (12.4); live recompute on a staged reserve change.
    - _Requirements: 1.4, 2.1, 2.2, 2.3, 2.6, 3.1, 3.2, 4.6, 5.3, 6.5, 12.1, 12.2, 12.4_

- [x] 13. Frontend: reserve controls
  - [x] 13.1 Implement `ReserveInput` + `MassApplyControl`
    - Per design F4: `ReserveInput` is a per-row percentage input, zod-validated on the client (task 10.2) with a localized message; disabled for `Absolute_Per_Room_Material` rows and in read-only mode; staged into `reserveDraftStore`. `MassApplyControl` sets the same percent across a whole Branch or the whole project and **expands** into per-material entries in the draft store; disabled in read-only mode.
    - _Requirements: 4.1, 4.4, 4.6, 4.7, 8.2, 9.2, 9.3, 10.2_
  - [x] 13.2 Write reserve-control component tests
    - Per-row set stages into the draft store (4.1); mass-apply expands into per-material entries (4.4); empty ⇒ unset (9.3); client validation message on out-of-range/non-numeric (9.2); reserve + mass-apply disabled for `PER_ROOM` rows (4.6) and in read-only mode (8.2, 10.2).
    - _Requirements: 4.1, 4.4, 4.6, 8.2, 9.2, 9.3, 10.2_

- [x] 14. Frontend: `MaterialsTab` and workspace tab registration
  - [x] 14.1 Implement `MaterialsTab` and register the workspace tab
    - Per design F1: `MaterialsTab` (the `lazy` target, modelled on `EstimateTab`) computes `canEdit = editable && hasPermission('ESTIMATE','UPDATE')`, fetches the read model (TanStack Query), renders loading/empty/error with localized copy (no raw key), owns the per-project `reserveDraftStore`, and renders `MaterialsDashboard` + `MaterialsMatrix`. When `canEdit` is false, the reserve inputs and the mass-apply control are disabled; the matrix/dashboard/popup and persisted reserve values stay fully visible. `Save` PUTs the expanded reserve request and refetches + clears the store on success. **Add** the `materials` tab to `WORKSPACE_TABS` in `project-workspace/workspaceTabs.ts` (`owner: 'FOR-05'`, `requiredPermission: { resource: 'ESTIMATE', operation: 'READ' }`, `labelKey: 'workspace.tab.materials'`, `lazy: MaterialsTab`) — the tab gate satisfies R8.1 with no `route-permissions.ts` change.
    - _Requirements: 1.1, 1.6, 8.1, 8.2, 10.1, 10.2, 10.3, 11.3_
  - [x] 14.2 Write tab/registration component tests
    - Tab present in the workspace and gated by `ESTIMATE` READ (1.1, 8.1); read-only mode disables reserve + mass-apply while the matrix/dashboard/popup and persisted values stay visible (8.2, 10.2, 10.3); Save maps the draft to the expanded request and clears the store on success; loading/empty/error render localized copy with no raw key (11.3).
    - _Requirements: 1.1, 8.1, 8.2, 10.2, 10.3, 11.3_

- [x] 15. Frontend: i18n and locale parity
  - [x] 15.1 Add every new string under `materials.*` (and `workspace.tab.materials`) to both `pl.json` and `ru.json`
    - Add all new keys (tab title, column headers incl. rooms + total, the two `Row_Total` figures, dashboard labels, detail-popup labels, reserve controls, validation messages, empty state) under a `materials.*` namespace plus `workspace.tab.materials` to **both** locale files at strict key parity with non-empty values; the UI never surfaces a raw key.
    - _Requirements: 11.1, 11.2, 11.3_
  - [x] 15.2 Write locale-parity test
    - Automated key-set equality over `pl.json` / `ru.json` for the `materials.*` + `workspace.tab.materials` keys; assert no raw key is surfaced on the new screen.
    - _Requirements: 11.1, 11.2_

- [x] 16. Checkpoint — frontend compiles and its tests pass
  - Run the frontend type-check and the component/store/locale tests for tasks 12–15 (single-run, no watch mode). Ensure all tests pass, ask the user if questions arise.

- [x] 17. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via a run-id generator and/or teardown, a regression group, and an MD report template with tables). This is a **full-stack** spec, so scenarios cover both **browser-engine UI flows** (the Materials tab: matrix render + branch grouping, per-room qty+unit / brutto cells and empty placeholders, the two `Row_Total` figures + effective brutto, the material detail popup, the per-branch + grand dashboard and fulfilment %, `Absolute_Per_Room_Material` rows with reserve disabled, per-material reserve input + mass-apply, Save/refetch, empty/no-rooms states, read-only gating when not DRAFT / lacking UPDATE) and **API tests against the Dockerized app** (`http://localhost:8080`, auth under `/api/auth`) for `GET /api/estimates/project/{id}/materials` (read model, fulfilment passthrough, null-net exclusion, stale-key tolerance), `PUT /api/estimates/project/{id}/materials/reserve` with `ESTIMATE` READ/UPDATE gating, the server bounds validation (whole-write rejection), the DRAFT gate (`409 error.estimate.locked`), and the `124` migration. Result artifacts are MD reports with tables. `test-cases.md` is written in Russian.
  - _Requirements: 1.1-1.6, 2.1-2.6, 3.1-3.4, 4.1-4.7, 5.1-5.3, 6.1-6.5, 7.1-7.4, 8.1-8.4, 9.1-9.4, 10.1-10.3, 11.1-11.3, 12.1-12.5_

- [-] 18. Final checkpoint — full verification and per-repo commits
  - Backend: `getDiagnostics` + `compileJava`/`compileTestJava`, then run only the affected test classes with `--tests` (redirect to a temp log; verify via JUnit XML). Frontend: run the frontend type-check and the property/component/store/locale tests (single-run). Ensure all tests pass, ask the user if questions arise.
  - Commit per the two-repo rule: backend (`MaterialsReserveMap` on `EstimateEntity`, `MaterialsListAssembler`, `MaterialsListService`, `EstimateMaterialsController`, the read-model DTOs, migration `124`) + the spec docs committed **from the root repo**; all frontend source (`features/materials/*`, the `workspaceTabs.ts` registration, `locales/*`) committed **from inside `foremen-frontend/`**. This feature spans both repos → one commit per repo. Only commit when the user asks; push to a new branch only; no force-push; no git-config changes.

## Notes

- Tasks marked with `*` are optional (unit / property / integration / component / parity tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirement sub-clauses for traceability.
- Every design Correctness Property (1–7) has a dedicated property-test sub-task at ≥100 iterations, tagged with the feature name + property title. Backend properties use jqwik; frontend properties use fast-check. Properties P1, P2, P3, and P7 are covered on **both** sides (the backend `MaterialsListAssembler` fold and the pure frontend `materialsModel.ts` share the identical rules); P4 (reserve-map round-trip) is backend-only; P5 (mass-apply) is frontend-only; P6 (validation bounds) is covered on both sides with identical bounds. To keep the backend fold property-testable without Spring or a DB, it lives in pure static helpers (mirroring `EstimateMatrixAssembler`).
- Backend tests: run only affected classes with `--tests`, redirect to a temp log, and read the JUnit result XML; use `compileJava`/`compileTestJava` and `getDiagnostics` for cheap checks. Full suite only on explicit request (~20 min).
- No **new** managed ABAC resource: `ESTIMATE` (changeset `081`) already covers the estimate; the reserve map lives on the existing `estimates` table (guarded transitively). The new `EstimateMaterialsController` MUST still be fully annotated or `PermissionAnnotationValidator` fails startup. This spec adds a column, not a managed entity.
- The single new migration `124` is appended **last** in `changelog.xml` after `123`, idempotent (`NOT columnExists` + `onFail="MARK_RAN"`). Confirm `124` is the next free number at build time.
- The materials read model is **derived** from the kosztorys — there is no second quantity or price formula; the fulfilment percentage is the kosztorys fill indicator passed through verbatim.
- This feature spans both git repos, so it needs two commits — one in the root repo (backend + migration + spec) and one in `foremen-frontend/` (all frontend source).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "8.1"] },
    { "id": 1, "tasks": ["1.2", "2.2", "3.1", "8.2", "9.1", "10.1", "10.2"] },
    { "id": 2, "tasks": ["3.2", "5.1", "9.2", "9.3", "9.4", "9.5", "10.3", "10.4"] },
    { "id": 3, "tasks": ["3.3", "3.4", "3.5", "3.6", "4.1", "5.2", "12.1", "12.2", "12.3", "13.1"] },
    { "id": 4, "tasks": ["5.3", "5.4", "6.1", "12.4", "13.2", "14.1"] },
    { "id": 5, "tasks": ["6.2", "14.2", "15.1"] },
    { "id": 6, "tasks": ["15.2"] },
    { "id": 7, "tasks": ["17"] }
  ]
}
```
