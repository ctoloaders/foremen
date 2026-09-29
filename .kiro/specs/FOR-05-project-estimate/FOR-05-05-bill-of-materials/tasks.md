# Implementation Plan: FOR-05-05 — Bill of materials (the Estimate tab)

## Overview

This plan implements the design in incremental, integrated steps that build on each other and end
with wiring the Estimate tab together. It spans **two git repos**
(per `.kiro/steering/git-repo-structure.md`):

- **Backend + migrations + spec** (root repo): the two new schema additions
  (`estimate_line_room_materials`, `work_room_types`), the full-coverage volume-formula seed, the new
  `FinishingPriceRangeResolver`, the `EstimateAssignmentService` write orchestrator, the volume
  fallback resolver, and the `EstimateMatrixController` read/write endpoints. Backend/spec commits are
  made **from the root repo**.
- **Frontend** (`foremen-frontend/`, nested repo): the `features/estimate/*` feature (tab, matrix,
  cell/work panels, stores, pure cost model), the `workspaceTabs.ts` swap, and the `estimate.*` locale
  keys. Frontend commits are made **from inside `foremen-frontend/`**.

This feature spans both repos, so it needs **two commits — one per repo** (backend + migrations +
spec in the root; all frontend source in `foremen-frontend/`).

**Entity-creation checklist note.** No **new** managed ABAC resource is introduced: the `ESTIMATE`
resource, its ADMIN CRUD / MANAGER CRU / FOREMAN·WORKER·FINANCIER READ grants (changeset `081`), and
`@PermissionResource("ESTIMATE")` on the estimate controllers are already shipped by FOR-05-03. The
new material-line and room-type tables are guarded transitively through `ESTIMATE` (estimate writes)
and `WORK_CATALOG` (room-type attachment edits). The new `EstimateMatrixController` MUST still be
fully annotated (`@PermissionResource("ESTIMATE")` at the class + `@RequiresPermission` READ/UPDATE on
handlers) or `PermissionAnnotationValidator` fails startup. The estimate services stay
`ProjectScopedService` with `getProjectIdPath()` resolving to the owning project.

**Migrations.** New Liquibase changesets are `102`–`104`, follow the archive/`NOT tableExists`/
per-row `NOT EXISTS`/`onFail="MARK_RAN"` conventions, and are appended **last** in `changelog.xml`
after `101`.

**Test-run policy (backend).** Do NOT run the full Gradle suite while implementing. Run only the
affected test classes with `--tests`, redirect to a temp log, and read the JUnit result XML
(`foremen-backend/build/test-results/test/TEST-<fqcn>.xml`). Use `compileJava` / `compileTestJava`
for compile-only checks and `getDiagnostics` for fast per-file checks. Run the full suite only if the
user explicitly asks (warn that it takes ~20 minutes).

**Languages.** Backend in Java (Spring Boot / JPA / MapStruct / Liquibase / jqwik). Frontend in
TypeScript/React (Vite, react-hook-form + zod, TanStack Query, zustand, shared `DataTable` /
`AsyncEntitySelect`, `lucide-react`, i18next); property tests in fast-check + Vitest.

## Tasks

- [x] 1. Backend: schema additions (idempotent Liquibase changesets, registered last)
  - [x] 1.1 Create the `estimate_line_room_materials` changeset (`102`)
    - Create `foremen-backend/database_files/changesets/102-create-estimate-line-room-materials.xml` creating `estimate_line_room_materials` with columns per design B1/Data Models: `room_qty_id` (FK → `estimate_line_room_qty`, `ON DELETE CASCADE`), `branch` (VARCHAR, `construction`|`finishing`), `construction_type_id` / `finishing_type_id` (nullable, exactly one non-null per `branch`; `ON DELETE RESTRICT`), `norm_qty`, `range_min`, `range_max`, `source_construction_material_id` / `source_finishing_material_id` (`ON DELETE SET NULL`), `concrete_construction_material_id` / `concrete_finishing_material_id` (`ON DELETE SET NULL`), `concrete_net`, plus `BaseEntity` columns.
    - Add `UNIQUE (room_qty_id, branch, construction_type_id, finishing_type_id)` (R13.4).
    - Guard with `preConditions onFail="MARK_RAN"` (`NOT tableExists`) so a re-run makes no change (R19.2). Register the file **last** in `changelog.xml`.
    - _Requirements: 13.1, 13.2, 13.4, 19.2, 19.3_
  - [x] 1.2 Create the `work_room_types` changeset (`103`)
    - Create `foremen-backend/database_files/changesets/103-create-work-room-types.xml` creating the M:N `work_room_types(work_item_id, room_type_id)` with `UNIQUE (work_item_id, room_type_id)` and both FKs (`work_items`, `room_types`).
    - Guard with `NOT tableExists` + `onFail="MARK_RAN"`; register **last** in `changelog.xml` after `102`.
    - _Requirements: 10.1, 19.2_
  - [x] 1.3 Create the full-coverage volume-formula seed changeset (`104`)
    - Create `foremen-backend/database_files/changesets/104-seed-work-volume-formulas-all.xml` seeding a default `work_volume_formula` for **every** work item that lacks one, extending the shipped `092` seed. Each seeded `parsed_ast` uses only the 14 known dimension variables (and known cross-work refs) so it passes `FormulaValidator` (R17.2).
    - Make it idempotent and non-overwriting with a per-row `NOT EXISTS` guard on `work_volume_formulas` for that work item (R17.3), plus `onFail="MARK_RAN"`; register **last** in `changelog.xml` after `103` (R17.4, R19.2).
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 19.2_
  - [x] 1.4 Write Liquibase migration integration tests
    - Assert both tables exist post-apply with the documented unique constraints and cascade/`SET NULL` FK actions; assert every work item has a volume formula after `104` and every seeded AST passes `FormulaValidator` (17.1, 17.2); assert a re-run of `102`–`104` is a no-op and preserves existing formulas (17.3, 17.4, 19.2).
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 19.2, 19.3_

- [x] 2. Backend: JPA entities and mappings
  - [x] 2.1 Add `EstimateLineRoomMaterialEntity` and wire the cascade collection
    - Create `EstimateLineRoomMaterialEntity` (extends `BaseEntity`) mapping `estimate_line_room_materials` exactly per design B1 (`roomQty` owner, `branch` enum `ConsumptionBranch`, the two type FKs, `normQty`, `rangeMin`/`rangeMax`, the source + concrete FKs, `concreteNet`). Add a cascade-orphan-removal collection of these on `EstimateLineRoomQtyEntity` so they cascade-delete with the room-qty and its owning line (R19.3).
    - _Requirements: 13.1, 13.2, 13.4, 6.6, 19.3_
  - [x] 2.2 Add the `work_room_types` attachment on `WorkItemEntity`
    - Add `@ManyToMany Set<RoomTypeEntity> roomTypes` (mapping `work_room_types`) to `WorkItemEntity` per design B2; empty ⇒ attaches to all rooms on apply, non-empty ⇒ only rooms of those types (governs *which* rooms, never *how* Volume is computed).
    - _Requirements: 10.1, 10.4_
  - [x] 2.3 Write entity mapping unit tests
    - Assert the material collection is `(roomQty, branch, type)`-keyed and cascade-removes with its room-qty/line; assert `WorkItem.roomTypes` maps `work_room_types`.
    - _Requirements: 13.4, 19.3, 10.1_

- [x] 3. Backend: `FinishingPriceRangeResolver` (the one new pricing primitive)
  - [x] 3.1 Implement the pure finishing type[/package] price-range resolver
    - Create `FinishingPriceRangeResolver` (`@Component`) mirroring `PriceRangeResolver` per design B3: `Key(finishingMaterialTypeId, offerPackageId)` (null package ⇒ package-less), `PriceRange(min, max)` with `EMPTY = (null, null)`, `compute(materials, offerPackageId)` and `rangeFor(materials, typeId, offerPackageId)` folding MIN..MAX of `retailNet` over active, non-null-`retailNet` finishing materials of the type; when `offerPackageId != null`, restrict to materials whose `finishing_material_packages` contains that package (R12.5). No fabricated fallback (empty bucket ⇒ `EMPTY`). Keep the logic in pure static helpers so it is property-testable without Spring.
    - _Requirements: 12.5, 3.4, 6.1_
  - [x] 3.2 Write property test for the finishing price range (jqwik, ≥100 iterations)
    - **Property 8: Finishing type[/package] price range is MIN..MAX over qualifying materials**
    - **Validates: Requirements 12.5**
    - `FinishingPriceRangeResolverPropertyTest`, tagged `// Feature: for-05-05-bill-of-materials, Property 8: Finishing type[/package] price range is MIN..MAX over qualifying materials`, ≥100 iterations. Generate finishing materials varying `type`, `retailNet` (incl. null), `active`, and package membership; assert `(T,P)` range equals MIN..MAX over exactly active + non-null + type-`T` + (when `P` given) member materials, `EMPTY` when none qualify, and is unaffected by inactive/null/other-type/non-member materials.
    - _Requirements: 12.5_
  - [x] 3.3 Write example unit tests for the resolver
    - Package-less fold across all packages (assignment default), package-scoped restriction, `null` retailNet excluded, inactive excluded, empty bucket → `EMPTY`.
    - _Requirements: 12.5, 3.4_

- [x] 4. Backend: volume resolution + unit→dimension fallback
  - [x] 4.1 Implement the `VolumeFallbackResolver` and the volume resolution core
    - Add a pure `VolumeFallbackResolver` implementing the unit→dimension fallback (`m2`→`floorArea`, `m`→`perimeter`, `szt`→`1` per room, unmapped→`0`; ambiguous `m2` uses documented default `floorArea`), and a pure volume-resolution core composing the shipped `FormulaRoomQtyDeriver.resolveApplicableFormula` (override AST → default AST → `null`) + `FormulaEvaluator.evaluate` against the room's 14 dims (missing dim → 0), falling back to `VolumeFallbackResolver` when no formula applies. Surface whether the fallback was used (for R5.3 disclosure).
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 1.4, 3.2, 3.3_
  - [x] 4.2 Write property test for Volume resolution (jqwik, ≥100 iterations)
    - **Property 3: Volume resolution follows override → default → unit fallback**
    - **Validates: Requirements 1.4, 3.2, 3.3, 5.1, 5.2, 11.3**
    - Tagged `// Feature: for-05-05-bill-of-materials, Property 3: Volume resolution follows override → default → unit fallback`, ≥100 iterations. Generate works with optional override/default formulas and rooms; assert override→default→unit-fallback precedence, `m2`/`m`/`szt`/unmapped mapping, and missing-dimension → 0.
    - _Requirements: 1.4, 3.2, 3.3, 5.1, 5.2, 11.3_

- [x] 5. Backend: `EstimateAssignmentService` write orchestrator
  - [x] 5.1 Implement assign / unassign, the frozen price copy, and the batched Save write path
    - Create `EstimateAssignmentService` (`ProjectScopedService`, `getProjectIdPath()` → the estimate's project). `assign(projectId, workItemId, roomId, packageCode?)`: create/extend the `EstimateLine`, add an `EstimateLineRoomQty` whose `quantity` = the resolved Volume (task 4), copy the work `unitPrice` + `workPrice` provenance, and create a `Material_Line` per consumption type with the copied `Type_Price_Range` (construction via `PriceRangeResolver`, finishing via the package-less `FinishingPriceRangeResolver`). `unassign(...)`: remove the `(line, room)` room-qty (cascade removes its material lines), dropping the line when it has no rooms left. Also implement the single batched `Save` write method `applyAssignments(projectId, stagedEdits)` (design B4): persist the whole staged set in one transaction — cell edits (assign/unassign/material add-remove/choose-concrete/bulk-choose) AND any `APPLY_PACKAGE` / `RECOMPUTE_FINISHING`-originated staged edits — upserting lines / room-qtys / material lines and **re-copying the finishing ranges for recompute-originated edits**. Finish each write by calling the shipped `EstimateRecomputeService.recomputeEstimate(...)` to recompute totals.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 13.1, 13.2, 15.2, 15.3, 15.7_
  - [x] 5.2 Implement material add/remove, choose-concrete, and bulk choose
    - `addMaterialLine`/`removeMaterialLine(roomQtyId, branch, typeId)` (copy the resolver range on add); `chooseConcrete(materialLineId, materialId)` (set the concrete product + copy its `retailNet` into `concreteNet`, collapsing the line); `bulkChooseConcreteForWork(workItemId, branch, typeId, materialId)` (apply a concrete product to that type across ALL of the work's assigned cells). Recompute after each.
    - _Requirements: 6.2, 6.3, 6.4, 9.3_
  - [x] 5.3 Implement the read-only calculate/preview methods for apply-package, work-row apply, and recompute-finishing
    - These are **calculate-then-stage** operations — they compute and RETURN the result and persist NOTHING (design B4). `calculateApplyPackage(projectId, packageCode)`: for every `WorkPackageOverride.member` work, compute the attachment to applicable rooms (Room_Type_Attachment or all when empty), Volume from `overrideParsedAst` else default formula via `resolveApplicableFormula`, **without** overriding existing volumes (layering), plus the finishing `Assortment_Placeholder`s (matching each work's finishing consumption type to the package's assortment position material type); return the computed assignments for the client to stage. `calculateApplyWorkToRooms(projectId, workItemId, packageCode?)`: the work-row hammer — same layering computation scoped to one work over its Room_Type_Attachment; returns the computed result. `calculateRecomputeFinishingPrices(projectId, packageCode)`: compute the recomputed range for already-assigned finishing material lines via the **package-scoped** `FinishingPriceRangeResolver` only (touches neither construction, labour, volumes, nor chosen concrete) and return the new ranges. None of these write to the DB — persistence flows through the batched `applyAssignments` Save (task 5.1) when the client saves the staged result. Keep the room/attachment/existing-assignment planning and the recompute range as **pure calculation cores** for property testing.
    - _Requirements: 9.5, 9.6, 10.2, 10.3, 11.2, 11.3, 11.4, 11.5, 11.6, 12.1, 12.2, 12.3, 12.4, 12.5, 15.3, 15.6_
  - [x] 5.4 Write property test for apply layering + attachment (jqwik, ≥100 iterations)
    - **Property 6: Apply attaches by room type and layers without overriding existing volumes**
    - **Validates: Requirements 9.5, 9.6, 10.2, 10.3, 11.2, 11.4, 11.5**
    - Tagged `// Feature: for-05-05-bill-of-materials, Property 6: Apply attaches by room type and layers without overriding existing volumes`, ≥100 iterations, over the pure apply calculate core. Assert the core produces the calculated attachment/Volume result **without persisting** (a pure, non-persisting computation) and that the result is a staged set written only on Save — never an immediate server write.
    - _Requirements: 9.5, 9.6, 10.2, 10.3, 11.2, 11.4, 11.5, 15.3, 15.6_
  - [x] 5.5 Write property test for assortment placeholder seeding (jqwik, ≥100 iterations)
    - **Property 7: Apply seeds finishing placeholders by material-type match**
    - **Validates: Requirements 11.6**
    - Tagged `// Feature: for-05-05-bill-of-materials, Property 7: Apply seeds finishing placeholders by material-type match`, ≥100 iterations.
    - _Requirements: 11.6_
  - [x] 5.6 Write property tests for recompute, snapshot stability, and material-line keying (jqwik, ≥100 iterations each)
    - **Property 9: Recompute changes finishing ranges only and preserves selections** — **Validates: 12.2, 12.3, 12.4** — assert the recompute **calculate core is pure/non-persisting**: its calculated result differs from the input only in already-assigned finishing ranges (never construction, labour, volumes, or a chosen concrete product), and the result is staged (undoable) and written only on Save, not persisted at compute time.
    - **Property 10: Copied prices are a stable snapshot** — **Validates: 3.4, 13.1, 13.2, 13.3**
    - **Property 11: Material lines are keyed uniquely per assignment by branch and type** — **Validates: 13.4**
    - Three separate `EstimateAssignmentService` property tests over an in-memory graph, each tagged with its feature name + property title, each ≥100 iterations.
    - _Requirements: 12.2, 12.3, 12.4, 15.3, 15.6, 3.4, 13.1, 13.2, 13.3, 13.4_
  - [x] 5.7 Write service integration tests (Spring)
    - assign creates line + room-qty (3.1); material add/remove (6.2); choose concrete (6.3); bulk choose across a work's cells (9.3); cascade delete of the material collection with its line/room-qty (19.3).
    - _Requirements: 3.1, 6.2, 6.3, 9.3, 19.3_

- [x] 6. Backend: `EstimateMatrixController` read model + write endpoints
  - [x] 6.1 Implement the matrix read model DTOs and mapper
    - Add the DTOs mirroring the design's frontend read model (`MoneyRange`, `MaterialLineDto`, `CellDto` with `volume`/`formulaUsed`/`fallbackUsed`/`labour`/`materials`/`costRange`/`fillState`, `WorkRowDto`, `WorkTypeGroupDto` with per-branch `subtotals`, `EstimateMatrixDto` with `editable`/`rooms`/`groups`/`totals`/`fillIndicatorPct`) and a MapStruct/assembler that builds them from the estimate graph, computing cell cost ranges, group subtotals, header totals, and the fill indicator server-side using the pure resolvers.
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 4.3, 5.3, 6.4, 8.1, 14.1, 14.2, 14.3_
  - [x] 6.2 Implement the controller endpoints with ABAC annotations
    - Create `@RestController @RequestMapping("/api/estimates") @PermissionResource("ESTIMATE")` `EstimateMatrixController` (or add handlers on the existing estimate controller): `GET /project/{projectId}/matrix` (`@RequiresPermission READ`) returning the read model. The `.../apply-package`, `.../apply-work`, and `.../recompute-finishing` endpoints are **read-only calculate/preview** endpoints that persist NOTHING (POST-with-body, but read-only in effect) — each `@RequiresPermission(resource="ESTIMATE", operation="READ")`, returning the calculated result for the client to stage. Only the batched Save endpoint `POST /project/{projectId}/assignments` is `@RequiresPermission(resource="ESTIMATE", operation="UPDATE")` (it persists the whole staged set — cell edits AND apply/recompute-originated staged edits); the material add/remove/choose endpoints (if exposed for non-staged direct writes) stay `UPDATE`. Method annotations take precedence over the class default; ensure full annotation so `PermissionAnnotationValidator` passes startup (R19.1).
    - _Requirements: 3.1, 6.2, 6.3, 9.3, 11.2, 12.1, 12.2, 15.2, 15.6, 15.7, 19.1_
  - [x] 6.3 Write ABAC + startup-annotation integration tests
    - The matrix read and the apply-package / apply-work / recompute-finishing **calculate** endpoints require `ESTIMATE` READ (they persist nothing); only the batched Save endpoint (`/assignments`) requires `ESTIMATE` UPDATE. `PermissionAnnotationValidator` asserts the controller is fully annotated at startup.
    - _Requirements: 15.6, 15.7, 19.1_

- [x] 7. Checkpoint — backend compiles and its tests pass
  - Run `getDiagnostics` on the changed Java files, then `./gradlew compileJava compileTestJava` (redirect to a temp log). Run only the affected test classes with `--tests` (the finishing-resolver property + example tests, the volume-resolution property test, the `EstimateAssignmentService` property + integration tests, the ABAC/startup test, and the migration integration test), redirect to a temp log, and verify via the JUnit result XML. Ensure all tests pass, ask the user if questions arise.

- [x] 8. Frontend: feature scaffold, read-model types, and API layer
  - [x] 8.1 Scaffold `features/estimate/` and mirror the backend read-model types
    - Create `foremen-frontend/src/features/estimate/` (`types/`, `api/{estimate-matrix-api,query-hooks,mutation-hooks}.ts`, `state/`, `components/`, `schemas/`) following the repo layout; add `MoneyRange`, `MaterialLineDto`, `CellDto`, `WorkRowDto`, `WorkTypeGroupDto`, `EstimateMatrixDto` exactly per the design; wire `apiRequest` to `GET /api/estimates/project/{projectId}/matrix` and the write endpoints (`assignments`, `apply-package`, `apply-work`, `recompute-finishing`, material add/remove/choose).
    - _Requirements: 1.1, 1.2, 3.1, 11.2, 12.1_

- [x] 9. Frontend: pure cost model and grouping (property-tested core)
  - [x] 9.1 Implement `costModel.ts`
    - Pure module per design F6: `cellCostRange(cell)` (`labour = unitPrice × Volume` point + each material `norm × Volume × [rangeMin..rangeMax]` placeholder or `norm × Volume × concreteNet` point; collapse to a point when every line is concrete), `fillState(cell)` (filled/partial/placeholder), `groupSubtotals(group)` (works/construction/finishing collapsing ranges), `headerTotals(matrix)` (three totals, each collapses when all its lines are concrete), `fillIndicator` (concrete lines ÷ all assigned lines, 0 when none). All computed from the read model + staged edits.
    - _Requirements: 1.2, 2.2, 4.1, 4.2, 4.3, 6.4, 7.2, 8.1, 14.1, 14.2, 14.3, 14.4_
  - [x] 9.2 Implement the work-type grouping helper
    - Pure grouping of work rows into `Work_Type_Group`s by work category (partition), used by the matrix and subtotals.
    - _Requirements: 2.1_
  - [x] 9.3 Write property test — cell/group/header cost math (fast-check, ≥100 iterations)
    - **Property 1: Cell cost range uses one Volume and collapses when fully concrete** — **Validates: 1.2, 4.1, 4.2, 4.3, 6.4, 7.2**
    - **Property 5: Group subtotals sum the group's cell contributions and collapse per branch** — **Validates: 2.2, 2.5**
    - **Property 12: Header totals sum all cell contributions per branch and collapse when fully concrete** — **Validates: 14.1, 14.2**
    - **Property 13: Fill indicator is the concrete-line ratio** — **Validates: 14.3**
    - `features/estimate/state/costModel.property.test.ts`, each property a single fast-check test tagged with its feature name + property title, ≥100 iterations.
    - _Requirements: 1.2, 2.2, 2.5, 4.1, 4.2, 4.3, 6.4, 7.2, 14.1, 14.2, 14.3_
  - [x] 9.4 Write property test — fill-state classification and work-summary aggregation (fast-check, ≥100 iterations)
    - **Property 2: Fill-state classification is exhaustive and matches concreteness** — **Validates: 7.1, 8.1**
    - **Property 14: Work-material summary aggregates a work's lines across its rooms** — **Validates: 9.2**
    - In `features/estimate/state/costModel.property.test.ts`, each a single fast-check test tagged with its feature name + property title, ≥100 iterations.
    - _Requirements: 7.1, 8.1, 9.2_
  - [x] 9.5 Write property test — work rows partition into type groups (fast-check, ≥100 iterations)
    - **Property 4: Work rows partition into type groups**
    - **Validates: Requirements 2.1**
    - `features/estimate/state/grouping.property.test.ts`, tagged with feature name + property title, ≥100 iterations.
    - _Requirements: 2.1_

- [x] 10. Frontend: staged-edits store and positioning store
  - [x] 10.1 Implement `estimateMatrixStore` (staged edits + undo/redo)
    - Per-project zustand store built with a pure self-inverting reducer directly mirroring `roomMatrixStore` per design F4: `past`/`future` inverse-patch stacks (session-scoped, not persisted), write-through of pending *values* to `foremen.estimateMatrix.<projectId>` in `localStorage` (null-safe/`try`-`catch` guarded like `roomMatrixStorage`), `HYDRATE`/`CLEAR`/`DISCARD_ALL`, staged action kinds `ASSIGN`/`UNASSIGN`/`ADD_MATERIAL`/`REMOVE_MATERIAL`/`CHOOSE_CONCRETE`/`BULK_CHOOSE_CONCRETE`, and — new — `APPLY_PACKAGE` and `RECOMPUTE_FINISHING`. `APPLY_PACKAGE` and `RECOMPUTE_FINISHING` are ordinary **staged, self-inverting patches** carrying the calculated result: applying them stages the resulting assignments / material-lines / ranges and their recorded inverse patch restores the prior state, so they push onto the same `past`/`future` stacks and are fully **undoable/redoable** exactly like the cell edits. They are persisted to the server only on the batched Save and cleared by `Discard`; there is no immediate server write and no non-undoable boundary.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.6, 15.7_
  - [x] 10.2 Implement `estimatePositioningStore`
    - Separate per-project `localStorage` store (`foremen.estimatePositioning.<projectId>`) per design F5 holding each `Work_Type_Group`'s expand/collapse and any scroll/layout position, **independent** of the staged-edits store; groups default all-collapsed on first visit; null-safe/malformed → defaults, never throws.
    - _Requirements: 16.1, 16.2, 16.3_
  - [x] 10.3 Write property test — undo/redo reducer (fast-check, ≥100 iterations)
    - **Property 15: Undo inverts, redo re-applies, and discard/clear empties the staged set**
    - **Validates: Requirements 15.3**
    - `features/estimate/state/estimateMatrixStore.property.test.ts` over the pure reducer, tagged with feature name + property title, ≥100 iterations. The generated action sequences MUST explicitly include `APPLY_PACKAGE` and `RECOMPUTE_FINISHING` alongside `ASSIGN`/`UNASSIGN`/`ADD_MATERIAL`/`REMOVE_MATERIAL`/`CHOOSE_CONCRETE`/`BULK_CHOOSE_CONCRETE`, asserting they are undoable/redoable staged actions exactly like every other edit and never a non-undoable boundary.
    - _Requirements: 15.3, 15.6, 15.7_
  - [x] 10.4 Write property test — positioning round-trip and independence (fast-check, ≥100 iterations)
    - **Property 16: Positioning store round-trips and defaults when absent** — **Validates: 16.1, 16.2**
    - **Property 17: Positioning is independent of staged edits** — **Validates: 16.3**
    - `features/estimate/state/estimatePositioning.property.test.ts`, each a single fast-check test tagged with feature name + property title, ≥100 iterations.
    - _Requirements: 16.1, 16.2, 16.3_

- [x] 11. Checkpoint — pure frontend core compiles and its property tests pass
  - Run the frontend type-check and the property tests for the cost model, grouping, staged-edits reducer, and positioning store (single-run, no watch mode). Ensure all tests pass, ask the user if questions arise.

- [x] 12. Frontend: matrix, cells, and fill-state rendering
  - [x] 12.1 Implement `WorksRoomsMatrix`, `WorkTypeGroup`, and cells
    - Per design F2: works in rows grouped into `Work_Type_Group`s (collapsed by default, expand/collapse on header click reading the positioning store), rooms in columns; group headers show works/construction/finishing subtotals as collapsing ranges (F6); assigned cells show their `Cell_Cost_Range`, unassigned cells render empty/assignable; click empty → stage assign, click assigned → open `Cell_Report`; each cell carries the `Fill_State` color (theme-derived tints/shades of the primary via `theme-store`, respecting light/dark) + a non-color cue (icon/`aria-label`); a header legend maps each color to its state; each work row carries a magnifier (opens `Work_Material_Summary`) and a hammer (`applyWorkToRooms`).
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 7.1, 7.3, 8.1, 8.2, 8.3, 8.4, 8.5, 9.1_
  - [x] 12.2 Write matrix/group component tests
    - Row/column rendering (1.1), unassigned cell (1.3), group collapse default + toggle (2.3), fill-state legend + non-color cue (7.3, 8.2, 8.4, 8.5), magnifier + hammer presence (9.1), live subtotal recompute on a staged change (2.4).
    - _Requirements: 1.1, 1.3, 2.3, 2.4, 7.3, 8.2, 8.4, 8.5, 9.1_

- [x] 13. Frontend: cell report and work-material summary panels
  - [x] 13.1 Implement `CellReport` and `WorkMaterialSummary`
    - Per design F3: `CellReport` shows the formula used + resolved Volume + work sum and per material type its `norm`, `norm × Volume`, and money range `norm × Volume × Type_Price_Range`; allows add/remove of a material line (either branch) and picking a `Concrete_Material` via `AsyncEntitySelect` (choosing collapses the line's range to the product price); discloses fallback use (R5.3); shows Placeholder vs concrete per line (R6.6). `WorkMaterialSummary` (magnifier) aggregates the work's material lines across ALL assigned rooms (per type: norm, aggregated qty and money range, concrete/placeholder state) and supports **bulk** concrete selection + add/remove applied to every affected cell; every change is undoable via the store. In read-only mode the magnifier stays enabled (read-only summary); hammer + fill/add/remove are disabled.
    - _Requirements: 4.4, 5.3, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 9.2, 9.3, 9.4, 9.7_
  - [x] 13.2 Write panel component tests
    - `Cell_Report` content + fallback disclosure + placeholder/concrete state (4.4, 5.3, 6.1, 6.6); add/remove + choose-concrete collapse (6.2, 6.3, 6.4); live recompute on a staged change (6.5); `Work_Material_Summary` aggregation + bulk fill (9.2, 9.3); read-only disabling with magnifier still enabled (9.7).
    - _Requirements: 4.4, 5.3, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 9.2, 9.3, 9.7_

- [x] 14. Frontend: `EstimateTab`, header, and tab wiring
  - [x] 14.1 Implement `EstimateTab` and `EstimateHeader`, and swap the workspace tab
    - Per design F1: `EstimateTab` owns the per-project staged-edits store via `useRef` (re-created on `projectId` change), computes `canEdit = isDraft && hasPermission('ESTIMATE','UPDATE')`, fetches the matrix read model, renders loading/empty/error with localized copy, and renders `EstimateHeader` + `WorksRoomsMatrix`. `Save` maps the staged set to the batched assignment payload and clears the store on success; `Discard`/`Undo`/`Redo` dispatch to the store. `Apply_Package`/`Recompute` call the **read-only calculate endpoint** (or compute via the pure `costModel.ts`) and then **dispatch the calculated result into the store as staged `APPLY_PACKAGE` / `RECOMPUTE_FINISHING` edits** (undoable/redoable) — no immediate server write and no refetch; they are persisted only on `Save`. `EstimateHeader` provides the Offer_Package selector, Apply/Recompute actions, the three header totals (works/construction/finishing, each collapsing when fully concrete) computed from the staged state, the Materials_Fill_Indicator, the unsaved-changes indicator, and the fill-state legend. Swap the `estimate` tab's `lazy` in `project-workspace/workspaceTabs.ts` from `PlaceholderTab` to `EstimateTab`.
    - _Requirements: 3.5, 11.1, 11.2, 11.3, 11.9, 12.1, 12.2, 14.1, 14.2, 14.3, 14.4, 15.1, 15.2, 15.3, 15.5, 15.6, 15.7_
  - [x] 14.2 Write tab/header/store component + unit tests
    - Package selector + Recompute affordance (11.1, 12.1); header totals + fill indicator live recompute (14.4); staged persistence + unsaved indicator (15.1); Save/Discard transitions (15.2); session-scoped history vs persisted values (15.4); read-only gating when not DRAFT / lacking UPDATE (15.5); apply-package/recompute dispatch a **staged, undoable** edit with no immediate server write and no refetch, persisted only on Save (15.6, 15.7).
    - _Requirements: 11.1, 12.1, 14.4, 15.1, 15.2, 15.4, 15.5, 15.6, 15.7_

- [x] 15. Frontend: i18n and locale parity
  - [x] 15.1 Add every new string under `estimate.*` to both `pl.json` and `ru.json`
    - Add all new/changed keys (tab title, package selector, apply/recompute, cell-report + work-summary labels, add/remove material, concrete-product picker, not-all-selected indicator + fill-state legend, totals labels, group headers, errors incl. localized `error.formula.*`) under an `estimate.*` namespace to **both** locale files; the UI never surfaces a raw key. No new backend i18n entity field.
    - _Requirements: 18.1, 18.2, 18.3_
  - [x] 15.2 Write locale-parity test
    - Automated key-set equality over `pl.json` / `ru.json` for the `estimate.*` namespace; assert no raw key is surfaced on the new screens.
    - _Requirements: 18.1, 18.2_

- [x] 16. Checkpoint — frontend compiles and its tests pass
  - Run the frontend type-check and the component/store/locale tests for tasks 12–15 (single-run, no watch mode). Ensure all tests pass, ask the user if questions arise.

- [x] 17. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via a run-id generator and/or teardown, a regression group, and an MD report template with tables). This is a **full-stack** spec, so scenarios cover both **browser-engine UI flows** (the Estimate tab: matrix render + group collapse, assign-by-click, cell report + material management, fill-state legend, work-row magnifier/hammer, package apply + recompute, header totals + fill indicator, staged edits/undo/redo/save/discard, persisted positioning, read-only gating) and **API tests against the Dockerized app** (`http://localhost:8080`, auth under `/api/auth`) for the matrix read model, assignment/material/apply/recompute endpoints with `ESTIMATE` READ/UPDATE gating, the frozen-snapshot invariant, the finishing price-range recompute, and the `102`–`104` migrations. Result artifacts are MD reports with tables. `test-cases.md` is written in Russian.
  - _Requirements: 1.1-1.6, 2.1-2.5, 3.1-3.5, 4.1-4.4, 5.1-5.4, 6.1-6.6, 7.1-7.3, 8.1-8.5, 9.1-9.7, 10.1-10.4, 11.1-11.7, 12.1-12.5, 13.1-13.4, 14.1-14.4, 15.1-15.6, 16.1-16.3, 17.1-17.4, 18.1-18.3, 19.1-19.4_

- [x] 18. Final checkpoint — full verification and per-repo commits
  - Backend: `getDiagnostics` + `compileJava`/`compileTestJava`, then run only the affected test classes with `--tests` (redirect to a temp log; verify via JUnit XML). Frontend: run the frontend type-check and the property/component/store/locale tests (single-run). Ensure all tests pass, ask the user if questions arise.
  - Commit per the two-repo rule: backend (entities, `FinishingPriceRangeResolver`, `VolumeFallbackResolver`, `EstimateAssignmentService`, `EstimateMatrixController`, migrations `102`–`104`) + the spec docs committed **from the root repo**; all frontend source (`features/estimate/*`, the `workspaceTabs.ts` swap, `locales/*`) committed **from inside `foremen-frontend/`**. This feature spans both repos → one commit per repo. Only commit when the user asks; push to a new branch only; no force-push; no git-config changes.

- [x] 19. (Amend) Seed the Room_Type_Attachment M:N by work category (changeset `105`)
  - Add idempotent, non-overwriting Liquibase changeset `105-seed-work-room-types.xml` seeding `work_room_types` by WORK CATEGORY → ROOM TYPES (mapping B): wet works (TILING/PLUMBING_ROUGH/PLUMBING_FINISH) → {kuchnia, lazienka}; FLOORS → the 6 dry rooms; CARPENTRY → the 6 dry living/circulation rooms; all other categories left with no rows (empty = all rooms, R10.3). Categories/room-types resolved by `code`; guarded by `onFail="MARK_RAN"` + `tableExists` + per-work `NOT EXISTS`. Register LAST in `changelog.xml` after `104`. Add `WorkRoomTypeSeedSchemaMigrationIntegrationTest` (Testcontainers) asserting the per-category attachment sets and re-run idempotency. Note the seed in requirements (R10.6) and design (Component B2).
  - _Requirements: 10.2, 10.3, 10.6, 19.2_

## Notes

- Tasks marked with `*` are optional (unit / property / integration / component tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirement sub-clauses for traceability.
- Every design Correctness Property (1–17) has a dedicated property-test sub-task at ≥100 iterations, tagged with the feature name + property title. Backend properties use jqwik; frontend properties use fast-check. To keep the backend cost/apply logic property-testable without Spring or a DB, it lives in pure static helpers / a stateless core (mirroring `MaterialRangeResolver`'s `compute`/`branchRange` split).
- Backend tests: run only affected classes with `--tests`, redirect to a temp log, and read the JUnit result XML; use `compileJava`/`compileTestJava` and `getDiagnostics` for cheap checks. Full suite only on explicit request (~20 min).
- No **new** managed ABAC resource: `ESTIMATE` (changeset `081`) already covers the estimate writes; the new tables are guarded transitively. The `EstimateMatrixController` MUST still be fully annotated or `PermissionAnnotationValidator` fails startup.
- New migrations `102`–`104` are appended **last** in `changelog.xml` after `101`, each idempotent (`NOT tableExists` / per-row `NOT EXISTS` / `onFail="MARK_RAN"`).
- This feature spans both git repos, so it needs two commits — one in the root repo (backend + migrations + spec) and one in `foremen-frontend/` (all frontend source).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "3.1", "4.1", "8.1"] },
    { "id": 1, "tasks": ["1.4", "2.1", "2.2", "3.2", "3.3", "4.2", "9.1", "9.2", "10.1", "10.2"] },
    { "id": 2, "tasks": ["2.3", "5.1", "9.3", "9.4", "9.5", "10.3", "10.4"] },
    { "id": 3, "tasks": ["5.2", "5.3", "12.1"] },
    { "id": 4, "tasks": ["5.4", "5.5", "5.6", "5.7", "6.1", "12.2", "13.1"] },
    { "id": 5, "tasks": ["6.2", "13.2", "14.1"] },
    { "id": 6, "tasks": ["6.3", "14.2", "15.1"] },
    { "id": 7, "tasks": ["15.2"] },
    { "id": 8, "tasks": ["17"] }
  ]
}
```
