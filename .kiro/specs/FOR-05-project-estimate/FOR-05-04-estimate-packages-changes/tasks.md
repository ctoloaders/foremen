# Implementation Plan: FOR-05-04 — Estimate packages & pricing model change

## Overview

This plan executes a **migration/deprecation** spec on the existing Foremen backend framework
(`AdminController`/`AdminService` generic CRUD, `ProjectScopedService`, MapStruct, i18n,
Liquibase, ABAC `@PermissionResource`/`@PermissionOperation` + startup validator). Implementation
language is **Java / Spring Boot** (fixed by the design's Notation section; algorithms shown in
`pascal` pseudocode are realized in Java). All new Liquibase changesets use the next free
sequence numbers after `081` and are registered in `changelog.xml` (the `PACKAGE_ASSORTMENT`
resource seed registered **last**).

Dependency order: (1) archive-before-drop migrations — collapse `work_prices`, retire the
`estimate_line_package_prices*` vertical, collapse `work_material_consumptions`; (2) new tables
for the formula engine, package overrides, and the zł/m² assortment; (3) the `PACKAGE_ASSORTMENT`
ABAC seed registered last; (4) the AST model + pure formula engine (parser/validator/planner/
evaluator) — the primary property-test target; (5) the pure MAX-collapse and `PackageZlM2Resolver`
rules; (6) entity collapses/removals + new entities + DTOs + mappers; (7) estimate integration
(`FormulaRoomQtyDeriver`, single-price copy on `EstimateLineService`); (8) assortment services +
controllers wired into ABAC; (9) i18n keys, wiring, tests, and the mandatory `test-cases.md`.
Each step builds on the previous and ends wired into the running app — no orphaned code.

## Tasks

- [x] 1. Migration — archive + collapse work prices to a single price (Liquibase)
  - [x] 1.1 Author `082-archive-and-collapse-work-prices.xml`, registered in changelog
    - Following the archive-before-drop pattern (§Migration step 1): first `<changeSet>` creates
      `work_package_prices_archive` and `INSERT … SELECT`s every `work_package_prices` row plus
      `archived_at` and `resolution_rule='MAX_NETPRICE'`.
    - Second `<changeSet>` adds `currency_id` (FK) and `net_price` to `work_prices`, then
      backfills `net_price = (SELECT MAX(net_price) FROM work_package_prices WHERE
      work_price_id = work_prices.id)` and `currency_id` from the winning row's currency.
    - Third `<changeSet>` drops `work_package_prices` (and its FKs/constraints).
    - Each changeset idempotent via `<preConditions onFail="MARK_RAN">`; register the
      `<include>` in `changelog.xml` in order.
    - _Requirements: 1.1, 1.2, 1.4, 8.1, 8.3, 8.4_

  - [x] 1.2 Write Testcontainers migration test for the work-price collapse
    - Assert `work_package_prices` is gone, `work_prices` now carries `currency_id`/`net_price`,
      the archive table holds the original per-package row count, backfilled `net_price` equals
      the per-work MAX, and a re-migrate is a no-op.
    - _Requirements: 1.1, 1.4, 8.4_

- [x] 2. Migration — retire the estimate-line per-package price vertical (Liquibase)
  - [x] 2.1 Author `083-archive-and-drop-estimate-line-package-prices.xml`, registered in changelog
    - Per §Migration step 2: create `estimate_line_package_prices_archive` and
      `estimate_line_package_price_history_archive`; `INSERT … SELECT` all rows (+ `archived_at`).
    - Drop `estimate_line_package_price_history` then `estimate_line_package_prices` (child first).
    - Idempotent `onFail="MARK_RAN"` preconditions; register the `<include>` in `changelog.xml`.
    - _Requirements: 8.1, 8.2, 8.4, 8.6_

  - [x] 2.2 Write Testcontainers migration test for the ELPP retirement
    - Assert both `estimate_line_package_prices*` tables are gone, both archive tables hold the
      original counts, and re-migrate is a no-op.
    - _Requirements: 8.2, 8.4_

- [x] 3. Migration — collapse the material-consumption package dimension (Liquibase)
  - [x] 3.1 Author `084-collapse-work-material-consumptions-package.xml`, registered in changelog
    - Per §Migration step 3: create `work_material_consumptions_pkg_archive` and `INSERT …
      SELECT` all rows (+ `archived_at`, `resolution_rule='MAX_NORMQTY'`).
    - Dedupe to one row per `(work_item_id, material_type_id, branch)` with `norm_qty =
      MAX(norm_qty)` across packages; delete the losing rows.
    - Drop the `offer_package_id` FK + column from `work_material_consumptions`.
    - Idempotent `onFail="MARK_RAN"` preconditions; register the `<include>` in `changelog.xml`.
    - _Requirements: 7.2, 7.3, 8.1, 8.2, 8.3, 8.4_

  - [x] 3.2 Write Testcontainers migration test for the consumption collapse
    - Assert `work_material_consumptions` has no `offer_package_id`, exactly one row per
      `(work_item, material_type, branch)` with the MAX `norm_qty`, the archive holds the
      pre-collapse count, and re-migrate is a no-op.
    - _Requirements: 7.2, 7.3, 8.4_

- [x] 4. Migration — new tables for formula engine, overrides, and assortment (Liquibase)
  - [x] 4.1 Author `085-create-work-volume-formulas.xml` and `086-create-work-package-overrides.xml`
    - `work_volume_formulas`: `work_item_id` FK **UNIQUE**, `source_text` (text, not null),
      `parsed_ast` (jsonb, not null), plus `BaseEntity` audit columns.
    - `work_package_overrides`: `work_item_id` FK, `offer_package_id` FK, `member` boolean
      (default true), `override_source_text` (text, nullable), `override_parsed_ast` (jsonb,
      nullable), UNIQUE `(work_item_id, offer_package_id)`.
    - Register both `<include>`s in `changelog.xml`.
    - _Requirements: 2.1, 2.6, 4.1, 4.5_

  - [x] 4.2 Author `087-create-assortment-groups.xml` and `088-create-assortment-line-items.xml`
    - `assortment_groups`: `name_ru`, `name_pl`, `sort_order`, `BaseEntity` columns.
    - `assortment_line_items`: `assortment_group_id` FK, `offer_package_id` FK, `name_ru`,
      `name_pl`, `min_price`, `avg_price`, `max_price`, `qty_ref50` (not null),
      `typical_material_id` FK **`ON DELETE SET NULL`** (nullable, provenance).
    - Register both `<include>`s in `changelog.xml`.
    - _Requirements: 6.1, 6.2, 6.7_

  - [x] 4.3 Write Testcontainers migration test for the four new tables
    - Assert the four tables, the two UNIQUE constraints (`work_volume_formulas.work_item_id`,
      `work_package_overrides (work_item_id, offer_package_id)`), and the
      `typical_material_id` `ON DELETE SET NULL` FK exist after migrate; re-migrate is a no-op.
    - _Requirements: 2.1, 4.1, 6.1, 6.7_

- [x] 5. `PACKAGE_ASSORTMENT` ABAC resource seed (entity-creation checklist)
  - [x] 5.1 Author idempotent `089-seed-package-assortment-resource.xml`, registered LAST in changelog
    - Following the `017-seed-users-resource.xml` pattern: first `<changeSet>` inserts the
      `PACKAGE_ASSORTMENT` `resources` row (`code`, `name_ru`, `name_pl`, `description_ru`,
      `description_pl`) guarded by `<preConditions onFail="MARK_RAN"><sqlCheck
      expectedResult="0">SELECT COUNT(*) FROM resources WHERE code = 'PACKAGE_ASSORTMENT'`.
    - Second `<changeSet>` grants `ADMIN` the CRUD operations via `role_resources` +
      `role_resource_operations`, guarded by the same `onFail="MARK_RAN"` precondition.
    - Add non-ADMIN grants per the design matrix: `MANAGER` = R, `FOREMAN` = R, `FINANCIER` = R;
      `WORKER` = none, `CLIENT` = none. Each grant guarded by its own `onFail="MARK_RAN"`.
    - Register `<include file="database_files/changesets/089-seed-package-assortment-resource.xml"/>`
      **last in sequence** in `changelog.xml`.
    - _Requirements: 6.1, 8.1_

  - [x] 5.2 Write Testcontainers idempotent-seed test
    - Assert the `PACKAGE_ASSORTMENT` resource row + ADMIN CRUD grants + the non-ADMIN grants
      exist after migrate, and a re-run inserts nothing (row/grant counts unchanged).
    - _Requirements: 6.1_

- [x] 6. Checkpoint — all migrations apply idempotently
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Formula AST model and JSON persistence
  - [x] 7.1 Implement the `FormulaAst` node model (jsonb-serializable)
    - Tagged node kinds per §Components Component 4: `Const`, `Var`, `WorkRef` (`mode`
      volume|present), `BinOp` (`+ - * /`), `Cond` (`IFS` cases + optional else), `Count`
      (`COUNTIF`). Annotate for `@JdbcTypeCode(SqlTypes.JSON)` round-tripping.
    - Define the `ROOM_DIMENSION_VARS` set of the 14 variable names (§Data Models binding table).
    - _Requirements: 2.1, 2.2, 2.6, 3.1_

  - [x] 7.2 Write unit test for AST JSON round-trip
    - Serialize each node kind to JSON and reload; assert structural equality.
    - _Requirements: 2.6_

- [x] 8. Pure formula engine — parser, validator, planner, evaluator
  - [x] 8.1 Implement `FormulaParser.parse(source)`
    - Per §6.2: accept the `Oferta` arithmetic subset (`+ - * /`, parentheses), numeric
      constants, `Var` names, `WorkRef` cell refs, `IFS`, and `COUNTIF`; produce a `FormulaAst`;
      reject out-of-grammar input with `400 error.formula.illegal.operator`.
    - _Requirements: 2.1, 2.3, 2.4, 3.1_

  - [x] 8.2 Implement `FormulaValidator.validate(ast, knownWorkRefs)`
    - Per §6.2: every `Var` ∈ the 14 dimensions (else `400 error.formula.unknown.variable`);
      every `WorkRef` ∈ `knownWorkRefs` (else `400 error.formula.unknown.work.reference`);
      operators/arities legal.
    - _Requirements: 2.2, 2.3, 2.4_

  - [x] 8.3 Write property test for formula parse/validate round-trip
    - **Property 3: Formula parse/validate round-trip**
    - **Validates: Requirements 2.3, 2.4, 2.6**
    - _Requirements: 2.3, 2.4, 2.6_

  - [x] 8.4 Implement `FormulaEvaluationPlanner.planOrder(roomFormulas)`
    - Per §6.3: build the WorkRef dependency graph over works present in the room, topologically
      sort with ascending-WorkRef tie-break (deterministic), and throw `409 error.formula.cycle`
      (listing the involved works) on a back-edge; a ref to an absent work is not an edge.
    - _Requirements: 3.2, 3.3, 3.5_

  - [x] 8.5 Write property test for deterministic order respecting references
    - **Property 4: Deterministic evaluation order respects references**
    - **Validates: Requirements 3.2, 3.5**
    - _Requirements: 3.2, 3.5_

  - [x] 8.6 Write property test for cycle rejection at save
    - **Property 5: Cycle rejection at save**
    - **Validates: Requirements 3.3**
    - _Requirements: 3.3_

  - [x] 8.7 Implement `FormulaEvaluator.evaluate(ast, vars, resolvedVolumeOf)`
    - Per §6.4: `Const`→literal; `Var`→`vars[name]` (null dimension → 0); `WorkRef(volume)`→
      `resolvedVolumeOf(ref)`; `WorkRef(present)`→`ref>0 ? 1 : 0`; `BinOp`→arithmetic (`/0` →
      `400 error.formula.division.by.zero`); `Cond`→first matching case's `then` else `else`/0;
      `Count`→1 if the comparison holds else 0. Pure and deterministic.
    - _Requirements: 3.1, 3.4, 3.5_

  - [x] 8.8 Write property test for absent reference resolving to zero
    - **Property 6: Absent reference resolves to zero**
    - **Validates: Requirements 3.4**
    - _Requirements: 3.4_

- [x] 9. Pure MAX-collapse and package zł/m² resolver
  - [x] 9.1 Implement the `collapseToSingle` MAX rule as a pure helper
    - Per §6.1: given a work item's per-package values return `MAX(value)`; used only from the
      migration helpers (steps 1 & 3), not the read path. MAY reuse the `EffectivePriceResolver`
      fallback value but does NOT retain it on the read path (R8.5).
    - _Requirements: 1.5, 7.3, 8.3, 8.5_

  - [x] 9.2 Write property test for MAX-collapse correctness
    - **Property 1: MAX-collapse correctness**
    - **Validates: Requirements 1.5, 7.3, 8.3**
    - _Requirements: 1.5, 7.3, 8.3_

  - [x] 9.3 Implement `PackageZlM2Resolver` (pure)
    - Per §6.6: `groupContribution` = (Σ `avgPrice × qtyRef50` for the package's lines) ÷ 50;
      `packageZlM2` = Σ group contributions for the package, `round2`, recomputed from current
      data (never cached). Independent of `typicalProduct` presence.
    - _Requirements: 6.3, 6.4, 6.5, 6.6, 6.8_

  - [x] 9.4 Write property test for package zł/m² computation
    - **Property 9: Package zł/m² is Σ of group contributions ÷ 50**
    - **Validates: Requirements 6.3, 6.4**
    - _Requirements: 6.3, 6.4_

  - [x] 9.5 Write property test for typical product never driving the value
    - **Property 10: Typical product never drives the value**
    - **Validates: Requirements 6.6, 6.7**
    - _Requirements: 6.6, 6.7_

- [x] 10. Checkpoint — formula engine and pure resolvers pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Collapse `WorkPriceEntity` and remove the retired price entities
  - [x] 11.1 Collapse `WorkPriceEntity` to the single-price shape
    - Move `currency` (FK) and `netPrice` up onto `WorkPriceEntity`; remove the `packagePrices`
      list; no `validFrom`/`validTo`. Keep `workItem` UNIQUE (one price per work).
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 11.2 Remove `WorkPackagePriceEntity` and drop `EffectivePriceResolver` from the read path
    - Delete `WorkPackagePriceEntity` + its DAO/mapper/any per-package sub-controller; adjust
      `WorkPriceService`/`WorkPriceController` (resource `WORK_PRICES`) to expose the single
      price directly with no MAX-fallback. `EffectivePriceResolver` is no longer read-path (kept
      only, if reused, behind the migration helper of task 9.1).
    - _Requirements: 1.3, 1.4, 8.2, 8.5_

  - [x] 11.3 Remove the `EstimateLinePackagePrice*` code vertical
    - Delete `EstimateLinePackagePriceEntity`/`…HistoryEntity`, their service(s), DAO(s),
      mapper(s), and the `EstimateLinePackagePriceController` (`/api/estimate-line-package-prices`
      incl. `/{id}/history`). Leave the `ESTIMATE` resource row intact.
    - _Requirements: 8.2, 8.6_

  - [x] 11.4 Write property test for single price read with no fallback
    - **Property 2: Single price read has no fallback**
    - **Validates: Requirements 1.1, 1.3**
    - _Requirements: 1.1, 1.3_

- [x] 12. New catalog entities — volume formula and package override
  - [x] 12.1 Implement `WorkVolumeFormulaEntity`
    - Extend `BaseEntity`; `workItem` FK UNIQUE, `sourceText` (text), `parsedAst` (jsonb via
      `@JdbcTypeCode(SqlTypes.JSON)`). Validate `sourceText` through parser + validator on write,
      persisting the AST (R2.6).
    - _Requirements: 2.1, 2.5, 2.6_

  - [x] 12.2 Implement `WorkPackageOverrideEntity`
    - `workItem` FK, `offerPackage` FK, `member` boolean, nullable `overrideSourceText` +
      `overrideParsedAst` (jsonb); UNIQUE `(work_item, offer_package)`; carries **no price**.
      Present override ⇒ member; validate override text through parser + validator on write.
    - _Requirements: 4.1, 4.2, 4.3, 4.5_

  - [x] 12.3 Write unit test for override carrying no price
    - **Property 8: Override carries no price**
    - **Validates: Requirements 4.5**
    - _Requirements: 4.5_

- [x] 13. New assortment entities — group and line item
  - [x] 13.1 Implement `AssortmentGroupEntity`
    - Extend `BaseEntity`; `nameRU`/`namePL` (PL fallback), `sortOrder`. Not project-scoped.
    - _Requirements: 6.1_

  - [x] 13.2 Implement `AssortmentLineItemEntity`
    - `group` FK, `offerPackage` FK, `nameRU`/`namePL`, `minPrice`/`avgPrice`/`maxPrice`,
      `qtyRef50` (not null), nullable `typicalProduct` FK (`ON DELETE SET NULL`, assistive only).
    - _Requirements: 6.1, 6.2, 6.7_

- [x] 14. Collapse `WorkMaterialConsumptionEntity` and adjust resolvers (kept)
  - [x] 14.1 Remove `offerPackage` from `WorkMaterialConsumptionEntity`
    - Drop the `offerPackage` FK so the row is `(workItem, materialType, branch, normQty)`; keep
      the entity, table, DAO, service, and controller otherwise unchanged.
    - _Requirements: 7.1, 7.2_

  - [x] 14.2 Adjust `MaterialRangeResolver` / `WorkCatalogAggregationResolver` to the package-less shape
    - Drop the `offerPackageId` axis from `BatchKey`/pivot; keep the money-range math unchanged;
      switch construction/finishing cost to read the single work price; introduce NO 20% (or any)
      fallback — a branch is either norm-covered or explicitly `unpriced` (R7.5).
    - _Requirements: 7.1, 7.4, 7.5_

  - [x] 14.3 Write property test for three-part work cost with no fabricated fallback
    - **Property 11: Three-part work cost, no fabricated fallback**
    - **Validates: Requirements 7.4, 7.5**
    - _Requirements: 7.4, 7.5_

- [x] 15. DTOs, service models, and MapStruct mappers
  - [x] 15.1 Create service models + DAO/service DTOs for the new/changed entities
    - Models for the collapsed `WorkPrice`, `WorkVolumeFormula`, `WorkPackageOverride`,
      `AssortmentGroup`, `AssortmentLineItem`, and the collapsed `WorkMaterialConsumption`.
    - _Requirements: 1.1, 2.1, 4.1, 6.1, 6.2, 7.2_

  - [x] 15.2 Implement MapStruct mappers with derived/assistive fields handled correctly
    - Map i18n fields per repo convention; ignore the parsed-AST on inbound (set from source
      text by the service), and treat `typicalProduct` as a provenance FK that never sources
      min/avg/max on inbound.
    - _Requirements: 2.6, 6.6, 6.7_

  - [x] 15.3 Write unit test asserting parsed-AST and typical-product are handled per contract
    - Feed a payload setting `parsedAst` and asserting it is derived from source, not client;
      feed a payload setting `typicalProduct` and asserting it does not alter min/avg/max.
    - _Requirements: 2.6, 6.6, 6.7_

- [x] 16. Checkpoint — entities, mappers, resolvers compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 17. Estimate line integration — single-price copy and formula-derived quantity
  - [x] 17.1 Copy the single work price into `EstimateLine.unitPrice` on create
    - In `EstimateLineService`, set `unitPrice` from the work's single `WorkPrice.netPrice`
      (provenance FK for lineage only); create NO `EstimateLinePackagePrice` rows (retired).
    - _Requirements: 5.1, 5.2_

  - [x] 17.2 Implement `FormulaRoomQtyDeriver.deriveForRoom(room, applicableFormulas)`
    - Per §6.4/§6.5: resolve the active package context (`WorkPackageOverride` override wins,
      else default `WorkVolumeFormula`, else no derivation → hand entry); populate
      `RoomVariables` from the room's 14 dimension columns; plan order + evaluate; emit a
      `DerivationTrace(formulaSourceText, resolvedInputs, resolvedValue)` per derived line (R5.5).
    - _Requirements: 4.2, 5.2, 5.3, 5.4, 5.5_

  - [x] 17.3 Write property test for package override precedence
    - **Property 7: Package override precedence**
    - **Validates: Requirements 4.2, 5.3**
    - _Requirements: 4.2, 5.3_

  - [x] 17.4 Write unit test for formula-vs-hand-entry fallback and derivation trace
    - Assert a work with no applicable formula preserves hand-entered `EstimateLineRoomQty`
      (R5.4), and a derived quantity exposes its formula + resolved inputs (R5.5).
    - _Requirements: 5.4, 5.5_

- [x] 18. Assortment services and controllers (ABAC `PACKAGE_ASSORTMENT`)
  - [x] 18.1 Implement `AssortmentGroupService` and `AssortmentLineItemService`
    - Generic `AdminService` subtypes; not project-scoped (no `ProjectScopedService`). Expose the
      computed package zł/m² via `PackageZlM2Resolver`, recomputed from current data (R6.5),
      exposed for the downstream FOR-05-06 consumer without applying `× floor area` (R6.8).
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.8_

  - [x] 18.2 Implement `AssortmentGroupController` and `AssortmentLineItemController`
    - Both annotated `@PermissionResource("PACKAGE_ASSORTMENT")`; inherited CRUD `default`
      methods carry `@PermissionOperation` so the startup validator passes (no half-annotation).
    - _Requirements: 6.1, 6.2_

  - [x] 18.3 Annotate the formula/override controllers under `WORK_ITEMS`
    - Expose `WorkVolumeFormula` and `WorkPackageOverride` CRUD via controllers annotated
      `@PermissionResource("WORK_ITEMS")` (owned extensions, no new resource code), inherited
      grants; ensure each guarded handler resolves a `(resource, operation)` pair.
    - _Requirements: 2.1, 4.1_

- [x] 19. i18n message keys (PL/RU parity)
  - [x] 19.1 Add formula + assortment error/label keys to the i18n bundles
    - Add `error.formula.unknown.variable`, `error.formula.unknown.work.reference`,
      `error.formula.illegal.operator`, `error.formula.cycle`,
      `error.formula.division.by.zero`, plus entity display names for the new entities, at
      PL/RU parity; no raw key surfaced.
    - _Requirements: 2.2, 2.3, 3.3_

  - [x] 19.2 Write unit test asserting message-bundle PL/RU parity
    - Assert each FOR-05-04 key has non-empty PL and RU values.
    - _Requirements: 2.2, 2.3, 3.3_

- [x] 20. Integration and wiring
  - [x] 20.1 Wire the formula engine, single-price copy, deriver, and assortment end-to-end
    - Connect `WorkVolumeFormula`/`WorkPackageOverride` write paths → parser+validator+cycle
      check; `EstimateLineService` → single-price copy + `FormulaRoomQtyDeriver`; assortment
      services → `PackageZlM2Resolver`; ensure the removed ELPP wiring leaves no orphaned code.
    - _Requirements: 3.3, 5.1, 5.3, 6.4, 8.6_

  - [x] 20.2 Write Testcontainers startup test with retired + new controllers
    - Assert the app starts with `EstimateLinePackagePriceController` removed and the two
      `@PermissionResource("PACKAGE_ASSORTMENT")` controllers annotated (no
      `PermissionAnnotationValidator` half-annotation failure); `/api/estimate-line-package-prices`
      returns `404`.
    - _Requirements: 8.6_

  - [x] 20.3 Write Testcontainers end-to-end integration test
    - Flow: seed a work + default formula + a cross-work reference → add an estimate line (single
      price copied, no ELPP rows) → derive a room quantity from the formula → reject a cyclic
      formula at save → assortment CRUD → package zł/m² recomputed from current data.
    - _Requirements: 3.3, 5.1, 5.3, 6.3, 6.4, 6.5_

- [x] 21. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md`
    standard (written in Russian). This is an API-only spec, so scenarios are **API tests
    against the Dockerized app** (`http://localhost:8080`, auth under `/api/auth`; first ADMIN
    via `FOREMEN_ADMIN_*`). Include: feature grouping (single work price read, volume formula
    CRUD + validation + cycle rejection, package override membership, formula-derived room
    quantity + trace, package zł/m² assortment CRUD + computation, material-consumption
    package-collapse, retired ELPP endpoint returns 404, ABAC/i18n), detailed step-by-step
    scenarios (ID, preconditions, numbered steps with request + expected result per step),
    repeatability via a **run-id generator** (unique work/assortment names per run), a
    **Регрессия** group (migration archive integrity, MAX-collapse resolution, typical-product
    never drives value, single-price no-fallback), and the MD report template with result
    tables. Result artifacts are MD report tables.
  - _Requirements: 1.1, 1.3, 2.2, 3.3, 4.2, 5.1, 6.3, 6.4, 6.6, 7.2, 8.4, 8.6_

- [x] 22. Final checkpoint — affected tests pass
  - Run only the affected test classes with `--tests` filters (per
    `.kiro/steering/test-execution-rules.md`), redirect to a temp log, and verify via the
    JUnit result XML. Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (unit, property, integration, and migration tests) and can
  be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements for traceability.
- Property tests use jqwik (repo standard) and each references its numbered correctness property
  from design.md § Correctness Properties (≥ 100 iterations, tagged
  `Feature: FOR-05-04-estimate-packages-changes, Property N`).
- The formula engine (parser/planner/evaluator), the MAX-collapse rule, and the zł/m² resolver
  are the pure PBT targets; migration mechanics use idempotent re-run integration tests +
  archive-count assertions instead of PBT.
- Migrations follow archive-before-drop: every destructive changeset first snapshots the retired
  rows into an `*_archive` table with the resolution rule recorded (R8.4).
- Checkpoints ensure incremental validation; run only affected test classes per the
  test-execution standard (never the ~20-minute full suite unless explicitly requested).
- Backend + spec changes are committed from the root repo per `.kiro/steering/git-repo-structure.md`.
- **FOR-05-06 is blocked** (R8.7) until the reconciliation open question is resolved; this spec
  does not implement margin/package-value presentation.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "4.1", "4.2", "7.1"] },
    { "id": 1, "tasks": ["1.2", "2.1", "3.1", "4.3", "7.2", "8.1", "9.1", "9.3", "13.1"] },
    { "id": 2, "tasks": ["2.2", "3.2", "5.1", "8.2", "8.4", "8.7", "9.2", "9.4", "9.5", "11.1", "13.2"] },
    { "id": 3, "tasks": ["5.2", "8.3", "8.5", "8.6", "8.8", "11.2", "11.3", "12.1", "12.2", "14.1"] },
    { "id": 4, "tasks": ["11.4", "12.3", "14.2", "15.1", "18.3", "19.1"] },
    { "id": 5, "tasks": ["14.3", "15.2", "17.1", "17.2", "18.1", "19.2"] },
    { "id": 6, "tasks": ["15.3", "17.3", "17.4", "18.2", "20.1"] },
    { "id": 7, "tasks": ["20.2", "20.3", "21"] }
  ]
}
```
