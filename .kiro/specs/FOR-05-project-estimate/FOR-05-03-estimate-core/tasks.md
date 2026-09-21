# Implementation Plan: FOR-05-03 — Estimate core

## Overview

This plan builds the estimate substrate on the existing Foremen backend framework
(`AdminController`/`AdminService` generic CRUD, `ProjectScopedService`, MapStruct, i18n,
Liquibase, ABAC `@PermissionResource`/`@PermissionOperation`). Implementation language is
**Java / Spring Boot** (the design's notation section fixes this; algorithms shown in
pseudocode are realized in Java).

The dependency order is: (1) database schema for the five tables, (2) the `ESTIMATE` ABAC
seed registered last, (3) enums + entities + DTOs + mappers, (4) the pure derivation
services (`applyDiscount`, `EstimateRecomputeService`, `DraftGateGuard`, cross-project
guard), (5) the snapshot service reading the FOR-04-12b `EffectivePriceResolver`, (6) the
four project-scoped services + `getOrCreateForProject`, (7) the four annotated controllers +
i18n message keys, then (8) wiring, tests, and the mandatory `test-cases.md`. Each step
builds on the previous and ends wired into the running app.

## Tasks

- [x] 1. Database schema — five estimate tables (Liquibase)
  - [x] 1.1 Create `estimates`, `estimate_lines`, `estimate_line_room_qty` changesets
    - Add `NNN-create-estimates.xml`, `NNN-create-estimate-lines.xml`,
      `NNN-create-estimate-line-room-qty.xml` under `foremen-backend/database_files/changesets/`
      using the next free sequence numbers after `075`.
    - `estimates`: `project_id` FK **UNIQUE** (1:1), `currency_id` FK (default PLN),
      `vat_rate_id` FK nullable, `status` (default `DRAFT`), `total_net`, `total_vat`,
      `total_gross`, plus `BaseEntity` audit columns.
    - `estimate_lines`: `estimate_id` FK, `work_item_id` FK, `work_price_id` FK nullable
      **`ON DELETE SET NULL`** (provenance), `unit_id` FK, `line_no`, `comment` nullable,
      `unit_price` snapshot, `quantity` (derived), `value_net` (derived).
    - `estimate_line_room_qty`: `line_id` FK, `room_id` FK, `quantity` (`CHECK >= 0`).
    - Register each `<include>` in `changelog.xml` in creation order.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 10.4_

  - [x] 1.2 Create `estimate_line_package_prices` + `estimate_line_package_price_history` changesets
    - `estimate_line_package_prices`: `line_id` FK, `offer_package_id` FK,
      `work_package_price_id` FK nullable **`ON DELETE SET NULL`** (provenance),
      `original_unit_price` nullable, `discount_kind` nullable, `discount_value` nullable,
      `unit_price` (effective, derived) nullable, `unpriced` boolean; UNIQUE
      `(line_id, offer_package_id)`.
    - `estimate_line_package_price_history` (append-only): `package_price_id` FK,
      `original_unit_price`, `discount_kind`, `discount_value`, `unit_price`, `changed_by`,
      `changed_at`.
    - Register both `<include>`s in `changelog.xml`.
    - _Requirements: 4.2, 4.4, 5.1, 6.1, 10.4_

  - [x] 1.3 Write Testcontainers migration test for the five tables
    - Assert tables, the `estimates.project_id` UNIQUE, the
      `(line_id, offer_package_id)` UNIQUE, and the two `ON DELETE SET NULL` provenance FKs
      exist after migrate; assert re-migrate is a no-op.
    - _Requirements: 1.1, 4.2, 4.4, 2.4_

- [x] 2. `ESTIMATE` ABAC resource seed (entity-creation checklist)
  - [x] 2.1 Author idempotent `NNN-seed-estimate-resource.xml`, registered LAST in changelog
    - Following the `017-seed-users-resource.xml` pattern: first `<changeSet>` inserts the
      `ESTIMATE` `resources` row (`code`, `name_ru`, `name_pl`, `description_ru`,
      `description_pl`) guarded by `<preConditions onFail="MARK_RAN"><sqlCheck
      expectedResult="0">SELECT COUNT(*) FROM resources WHERE code = 'ESTIMATE'</sqlCheck>`.
    - Second `<changeSet>` grants `ADMIN` the CRUD operations via `role_resources` +
      `role_resource_operations`, guarded by the same `onFail="MARK_RAN"` precondition.
    - Add non-ADMIN grants per the design matrix: `MANAGER` = C R U, `FOREMAN` = R,
      `WORKER` = R, `FINANCIER` = R (the `(own)` filter is applied at runtime by
      `ProjectScopedService`); `CLIENT` = none. Each grant guarded by its own
      `onFail="MARK_RAN"` precondition.
    - Register `<include file="database_files/changesets/NNN-seed-estimate-resource.xml"/>`
      **last in sequence** in `changelog.xml` (after `075`).
    - _Requirements: 9.1, 9.2, 9.5, 9.6_

  - [x] 2.2 Write Testcontainers idempotent-seed test
    - **Property 1 support / seed idempotency**: assert `ESTIMATE` resource row + ADMIN CRUD
      grants + the non-ADMIN grants exist after migrate, and that a re-run inserts nothing
      (row/grant counts unchanged).
    - _Requirements: 9.1, 9.2, 9.5, 9.6_

- [x] 3. Enums with i18n labels
  - [x] 3.1 Implement `EstimateStatus` and `DiscountKind` enums
    - `EstimateStatus { DRAFT, PRICED, APPROVED, SIGNED }` (default `DRAFT`);
      `DiscountKind { PERCENT, ABSOLUTE }`.
    - Each value carries `nameRU`/`namePL` labels with PL fallback so no raw key is surfaced.
    - _Requirements: 1.3, 5.1, 11.1, 11.2_

  - [x] 3.2 Write unit test for enum i18n labels
    - **Property 13: i18n parity** — **Validates: Requirements 11.1, 11.2, 11.3**
    - Assert every enum value has non-empty `nameRU` and `namePL`.
    - _Requirements: 11.1, 11.2, 11.3_

- [x] 4. JPA entities
  - [x] 4.1 Implement `EstimateEntity`
    - Extend `BaseEntity`; `project` (unique FK), `currency` (FK, default PLN), `vatRate`
      (FK nullable), `status` (`EstimateStatus`, default `DRAFT`), derived `totalNet`,
      `totalVat`, `totalGross`.
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 4.2 Implement `EstimateLineEntity`
    - `estimate` FK, `workItem` FK, `workPrice` FK nullable (provenance only), `unit` FK,
      `lineNo`, `comment` nullable, `unitPrice` snapshot, derived `quantity`, `valueNet`.
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 4.3 Implement `EstimateLineRoomQtyEntity`
    - `line` FK, `room` FK, `quantity` (`>= 0`).
    - _Requirements: 3.1, 3.2_

  - [x] 4.4 Implement `EstimateLinePackagePriceEntity` and `EstimateLinePackagePriceHistoryEntity`
    - Package price: `line` FK, `offerPackage` FK, `workPackagePrice` FK nullable
      (provenance), `originalUnitPrice` nullable, `discountKind`/`discountValue` nullable,
      effective `unitPrice` derived, `unpriced` boolean.
    - History: `packagePrice` FK, snapshot of `originalUnitPrice`/`discountKind`/
      `discountValue`/`unitPrice`, `changedBy`, `changedAt` (append-only).
    - _Requirements: 4.2, 4.4, 5.1, 6.1_

- [x] 5. DTOs, service models, and MapStruct mappers
  - [x] 5.1 Create service models + DAO/service DTOs for the four entities
    - `EstimateServiceModel`/`EstimateServiceExtendedModel` and models for line, room-qty,
      package-price (+ read-only history model).
    - _Requirements: 10.1_

  - [x] 5.2 Implement MapStruct mappers with derived columns ignored inbound
    - Map i18n fields per repo convention; mark `quantity`, `valueNet`, `total*`, and
      effective `unitPrice` read-only/ignored on inbound so client payloads cannot set them.
    - _Requirements: 8.4, 10.1, 2.6, 2.7_

  - [x] 5.3 Write unit test asserting inbound derived fields are ignored
    - Feed a payload with `quantity`/`valueNet`/`total*`/effective `unitPrice` set and assert
      the mapper drops them.
    - _Requirements: 8.4, 2.6, 2.7_

- [x] 6. Checkpoint — schema, seed, entities, mappers compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Pure derivation logic
  - [x] 7.1 Implement `applyDiscount(originalUnitPrice, discountKind, discountValue)`
    - Null-passthrough for unpriced; neutral (return original) on absent/zero discount;
      PERCENT / ABSOLUTE math; floor at zero; `round2`.
    - Implemented as `DiscountCalculator.applyDiscount` in `com.foremen.service.pricing`,
      mirroring the `EffectivePriceResolver` stateless-`@Component` convention.
    - _Requirements: 5.2, 5.3_

  - [x] 7.2 Write property test for discount derivation
    - **Property 8: Discount neutrality & derivation** — **Validates: Requirements 5.2, 5.3**
    - `DiscountCalculatorPropertyTest` (jqwik): null-original passthrough, absent/zero-discount
      neutrality, PERCENT/ABSOLUTE derivation floored at zero, and determinism. All 4
      properties pass (100 tries each).
    - _Requirements: 5.2, 5.3_

  - [x] 7.3 Implement `EstimateRecomputeService.recomputeEstimate(estimate)`
    - Per §6.1: `line.quantity = Σ roomQty.quantity`, `line.valueNet = round2(unitPrice ×
      quantity)`, `totalNet = Σ valueNet`, `totalVat = round2(totalNet × vat)`,
      `totalGross = round2(totalNet + totalVat)`; empty estimate ⇒ all totals 0.
    - _Requirements: 2.6, 2.7, 3.3, 3.4, 8.1, 8.2, 8.3, 8.5_

  - [x] 7.4 Write property test for line value derivation
    - **Property 2: Line value derivation** — **Validates: Requirements 2.6, 2.7, 8.1**
    - _Requirements: 2.6, 2.7, 8.1_

  - [x] 7.5 Write property test for total derivation
    - **Property 3: Total derivation** — **Validates: Requirements 8.2, 8.3, 8.5**
    - _Requirements: 8.2, 8.3, 8.5_

  - [x] 7.6 Write property test for non-negative quantities
    - **Property 9: Non-negative quantities** — **Validates: Requirements 3.2, 3.4**
    - _Requirements: 3.2, 3.4_

- [x] 8. `DraftGateGuard` and cross-project room validation
  - [x] 8.1 Implement `DraftGateGuard.assertDraft(estimate)`
    - Per §6.4: throw `409 error.estimate.locked` when `status <> DRAFT`.
    - _Requirements: 7.1, 7.2, 7.3_

  - [x] 8.2 Write property test for the DRAFT gate
    - **Property 11: DRAFT gate** — **Validates: Requirements 7.1, 7.2, 7.3**
    - `DraftGateGuardPropertyTest` (jqwik): DRAFT never throws, every non-DRAFT status
      throws `409 error.estimate.locked`, and a combined property restates the iff over the
      full `EstimateStatus` enum. 3/3 pass (jqwik exhaustively covers the 4-value enum).
    - _Requirements: 7.1, 7.2, 7.3_

  - [x] 8.3 Implement cross-project room validation (`validateRoomQty`)
    - Per §6.5: reject `room.project.id <> line.estimate.project.id` with
      `400 error.estimate.room.cross.project`; reject `quantity < 0` with
      `400 error.estimate.qty.negative`.
    - _Requirements: 3.2, 3.6_

  - [x] 8.4 Write property test for cross-project rejection
    - **Property 10: Cross-project rejection** — **Validates: Requirements 3.6**
    - _Requirements: 3.6_

- [x] 9. `PackagePriceSnapshotService` (copy-at-add-time)
  - [x] 9.1 Implement `snapshotForLine(line)` reading the FOR-04-12b `EffectivePriceResolver`
    - Per §6.2: one `EstimateLinePackagePrice` per `OfferPackage` existing at add-time;
      resolve via MAX-fallback; set `originalUnitPrice` + provenance FK when present, else
      `unpriced=true` with null prices (never fabricate); set effective `unitPrice` via
      `applyDiscount`; do not retro-populate later packages.
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 4.6, 4.7, 10.2, 12.7_

  - [x] 9.2 Write property test for snapshot completeness at add-time
    - **Property 5: Snapshot completeness at add-time** — **Validates: Requirements 4.1, 4.2, 4.6**
    - _Requirements: 4.1, 4.2, 4.6_

  - [x] 9.3 Write property test for MAX-fallback copy correctness
    - **Property 6: MAX-fallback copy correctness** — **Validates: Requirements 4.3, 4.7**
    - _Requirements: 4.3, 4.7_

  - [x] 9.4 Write property test for provenance FK never driving value
    - **Property 7: Provenance FK never drives value** — **Validates: Requirements 2.4, 4.4**
    - _Requirements: 2.4, 4.4_

  - [x] 9.5 Write property test for snapshot immutability under catalog change
    - **Property 4: Snapshot immutability under catalog change** — **Validates: Requirements 2.5, 4.5**
    - _Requirements: 2.5, 4.5_

- [x] 10. Price-history capture skeleton
  - [x] 10.1 Implement append-only `captureHistory(packagePrice)` on price changes
    - Write an `EstimateLinePackagePriceHistory` row on every change to `originalUnitPrice`,
      discount, or effective `unitPrice`; never update/delete prior rows; queryable per price.
    - _Requirements: 6.1, 6.2, 6.3_

  - [x] 10.2 Write property test for price-history capture
    - **Property 12: Price-history capture** — **Validates: Requirements 6.1, 6.2**
    - _Requirements: 6.1, 6.2_

- [x] 11. Checkpoint — derivation, snapshot, gate, history pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Project-scoped services
  - [x] 12.1 Implement `EstimateService` with `getOrCreateForProject`
    - `getProjectIdPath()` = `"project.id"`; default currency PLN + status DRAFT on create;
      `getOrCreateForProject(projectId)` upsert enforcing the 1:1 invariant (single estimate);
      reject an explicit second create with `409 error.estimate.already.exists`; orchestrate
      totals recompute.
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 1.6, 8.2, 8.3, 9.4_

  - [x] 12.2 Write property test for single estimate per project
    - **Property 1: Single estimate per project** — **Validates: Requirements 1.1, 1.6**
    - _Requirements: 1.1, 1.5, 1.6_

  - [x] 12.3 Implement `EstimateLineService`
    - `getProjectIdPath()` = `"estimate.project.id"`; persist line fields + `unitPrice`
      snapshot; trigger `PackagePriceSnapshotService.snapshotForLine` at add-time; trigger
      recompute; enforce `DraftGateGuard` on free-edit create/update/delete.
    - _Requirements: 2.1, 2.2, 2.3, 2.6, 2.7, 4.1, 7.1, 7.2, 9.4_

  - [x] 12.4 Implement `EstimateLineRoomQtyService`
    - `getProjectIdPath()` = `"line.estimate.project.id"`; apply cross-project + non-negative
      validation; trigger recompute on add/change/remove; enforce `DraftGateGuard`.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 7.1, 7.2, 9.4_

  - [x] 12.5 Implement `EstimateLinePackagePriceService`
    - `getProjectIdPath()` = `"line.estimate.project.id"`; own discount fields, derive
      effective `unitPrice` via `applyDiscount`, capture history on change, enforce
      `DraftGateGuard`; expose read-only history endpoint (resolves to READ).
    - _Requirements: 4.2, 5.1, 5.2, 5.3, 6.1, 6.2, 7.1, 7.2, 9.4_

- [x] 13. Controllers annotated `@PermissionResource("ESTIMATE")`
  - [x] 13.1 Implement `EstimateController` and `EstimateLineController`
    - Thin generic `AdminController` subtypes annotated `@PermissionResource("ESTIMATE")`;
      expose the `getOrCreateForProject` endpoint on `EstimateController`.
    - _Requirements: 9.3, 10.1, 1.5_

  - [x] 13.2 Implement `EstimateLineRoomQtyController` and `EstimateLinePackagePriceController`
    - Both annotated `@PermissionResource("ESTIMATE")`; the read-only history handler resolves
      to `READ`; no half-annotated controller (startup validator passes).
    - _Requirements: 9.3, 10.1_

- [x] 14. i18n message keys (PL/RU parity)
  - [x] 14.1 Add error + label message keys to the i18n bundles
    - Add `error.estimate.already.exists`, `error.estimate.locked`,
      `error.estimate.qty.negative`, `error.estimate.room.cross.project`, plus entity display
      names and enum labels, at PL/RU parity; no raw key surfaced.
    - _Requirements: 11.1, 11.2, 11.3_

  - [x] 14.2 Write unit test asserting message-bundle PL/RU parity
    - **Property 13: i18n parity** — **Validates: Requirements 11.1, 11.2, 11.3**
    - Assert each FOR-05-03 key has non-empty PL and RU values.
    - _Requirements: 11.1, 11.2, 11.3_

- [x] 15. Integration and wiring
  - [x] 15.1 Wire services, snapshot, recompute, gate, and controllers end-to-end
    - Connect `EstimateLineService` → snapshot + recompute, room-qty/price changes → recompute,
      free-edit hooks → `DraftGateGuard`; ensure no orphaned code.
    - _Requirements: 2.6, 2.7, 3.3, 4.1, 7.1, 7.2, 8.1, 8.2, 8.3_

  - [x] 15.2 Write Testcontainers integration test — startup with annotated controllers
    - Assert the app starts with the four `@PermissionResource("ESTIMATE")` controllers (no
      `PermissionAnnotationValidator` half-annotation failure).
    - _Requirements: 9.3_

  - [x] 15.3 Write Testcontainers end-to-end integration test
    - Flow: create project → `getOrCreateForProject` → add line (snapshot copies package
      prices) → add room qty (totals recompute) → catalog price edit does NOT move the
      snapshot → advance status past DRAFT → free edit blocked with `error.estimate.locked`.
    - _Requirements: 1.5, 4.1, 4.5, 8.1, 8.2, 7.2_

- [x] 16. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md`
    standard (written in Russian). This is an API-only spec, so scenarios are **API tests
    against the Dockerized app** (`http://localhost:8080`, auth under `/api/auth`; first ADMIN
    via `FOREMEN_ADMIN_*`). Include: feature grouping (estimate 1:1 / get-or-create, lines +
    snapshot copy, room qty + recompute, per-package price + discount, price-history, DRAFT
    gate, ABAC/i18n), detailed step-by-step scenarios (ID, preconditions, numbered steps with
    request + expected result per step), repeatability via a **run-id generator** (unique
    project/entity names per run), a **Регрессия** group (snapshot immutability under catalog
    edit, single-estimate invariant, cross-project room rejection), and the MD report template
    with result tables. Result artifacts are MD report tables.
  - _Requirements: 1.5, 1.6, 2.3, 3.6, 4.1, 4.5, 5.2, 6.2, 7.2, 8.2, 9.3, 11.3_

- [x] 17. Final checkpoint — affected tests pass
  - Run only the affected test classes with `--tests` filters (per
    `.kiro/steering/test-execution-rules.md`), redirect to a temp log, and verify via the
    JUnit result XML. Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (unit, property, and integration tests) and can be
  skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements for traceability.
- Property tests use jqwik (repo standard) and each references its numbered correctness
  property from design.md § Correctness Properties.
- Checkpoints ensure incremental validation; run only affected test classes per the
  test-execution standard (never the ~20-minute full suite unless explicitly requested).
- Backend + spec changes are committed from the root repo per `.kiro/steering/git-repo-structure.md`.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "3.1"] },
    { "id": 1, "tasks": ["1.2", "2.1", "3.2"] },
    { "id": 2, "tasks": ["1.3", "2.2", "4.1", "4.2", "4.3", "4.4"] },
    { "id": 3, "tasks": ["5.1"] },
    { "id": 4, "tasks": ["5.2", "7.1", "7.3", "8.1", "8.3", "14.1"] },
    { "id": 5, "tasks": ["5.3", "7.2", "7.4", "7.5", "7.6", "8.2", "8.4", "9.1", "14.2"] },
    { "id": 6, "tasks": ["9.2", "9.3", "9.4", "9.5", "10.1"] },
    { "id": 7, "tasks": ["10.2", "12.1", "12.3", "12.4", "12.5"] },
    { "id": 8, "tasks": ["12.2", "13.1", "13.2"] },
    { "id": 9, "tasks": ["15.1"] },
    { "id": 10, "tasks": ["15.2", "15.3", "16"] }
  ]
}
```
