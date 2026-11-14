# Implementation Plan: FOR-11-00 — Procurement foundation

## Overview

This plan delivers the procurement foundation of FOR-11 as a backend-first slice with a thin
frontend (seller NIP lookup, reusable seller picker with inline creation, navigation): the
`BUYER` role and its access rule, project scope on the finishing-material
dictionary and a project-materials API, the seller NIP field with a lookup that searches our
dictionary first and then a **non-working, configuration-driven** registry seam, the `PurchasePosition`
and `PurchaseRequest` aggregates with the per-position **variant history**, the **proforma-paid gate**
in a pure state-machine policy, the price-visibility policy, the procurement stage seeds, the
`hideDelivered` filter, the foreman **upcoming-deliveries** endpoint with its synced
`plannedDeliveryDate`, the ABAC resources/grants/datasets, the **dashboard widgets** and BUYER/FOREMAN
defaults, two notifications with a one-shot overdue sweep, and the configuration.

**Reused, not re-implemented.** `ProjectScopedService` (extended only through `allowedProjectIds` in
the three new services), `ReadOnlyAdminService.addRequiredQuery` for hiding project rows, the
translation-seam shape for the NIP SPI, `NipValidator`, `OverdueSweepService` as the sweep model,
`NotificationService.create`, the FOR-07-03 / 209 resource-seed pattern, FOR-10 `Dataset` registry,
the `delivery_statuses` dictionary and the shared DataTable/zod/i18n frontend patterns.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):
- **Root repo** — changesets `223`–`232`, entities/DAOs/services/controllers/policies, the SPI,
  sweep/emitter, datasets, config, backend tests, messages, and this spec. Commit from the workspace
  root.
- **Frontend repo** (`foremen-frontend/`) — seller form/table/api, navigation and bottom bar, locale
  keys, frontend tests. Commit from inside `foremen-frontend/`.
A full implementation needs **two commits — one per repo**, made only when the user asks.

**Migrations.** New changesets begin at `223` (current tail is `222-seed-dashboard-role-defaults.xml`);
if a sibling spec lands further changesets first, use the next free `NNN`. Each is idempotent
(`columnExists` / `tableExists` / `SELECT COUNT(*)` preconditions with `onFail="MARK_RAN"`, or
`INSERT ... WHERE NOT EXISTS`) and is appended **last** in `changelog.xml` by tasks 1.7, 7.1 and
15.2 only (one task edits that file per wave).

**Test-run policy (backend).** Do NOT run the full Gradle suite (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected classes with `--tests`, redirect to
`/tmp/for-11-00-test.log`, read the log and the JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks and
`./gradlew compileJava compileTestJava` (redirected) for compile-only checks.

## Tasks

- [x] 1. Database changesets
  - [x] 1.1 Create `223-seed-buyer-role.xml`
    - Role `BUYER` (`system = true`, RU "Закупщик", PL "Specjalista ds. zakupów", RU/PL
      descriptions), guarded by `SELECT COUNT(*) FROM roles WHERE code='BUYER'` = 0 (`MARK_RAN`).
    - _Requirements: 1.1, 1.3, 16.3_
  - [x] 1.2 Create `224-add-finishing-materials-project-seller.xml`
    - Nullable `project_id` (FK `projects`, index) and `seller_id` (FK `material_sellers`,
      `ON DELETE SET NULL`) on `finishing_materials`, each as its own idempotent changeSet
      (`columnExists` / `foreignKeyConstraintExists` / `indexExists`).
    - _Requirements: 2.1_
  - [x] 1.3 Create `225-add-material-sellers-nip.xml`
    - `nip VARCHAR(10)` nullable + partial unique index `uk_material_sellers_nip WHERE nip IS NOT NULL`.
    - _Requirements: 4.1_
  - [x] 1.4 Create `226-create-purchase-requests.xml`
    - Table and indexes per design (`tableExists` guard); FK to `projects`, `delivery_categories`,
      `users`.
    - _Requirements: 11.1_
  - [x] 1.5 Create `227-create-purchase-positions.xml`
    - Table `purchase_positions` (incl. system-owned `planned_delivery_date`), FKs,
      `CHECK (kind = 'FINISHING')`, `CHECK (quantity > 0)` and indexes per design; plus a second
      changeSet creating `purchase_position_variants` (FK `ON DELETE CASCADE` to the position).
    - _Requirements: 7.1, 7.2, 7.4, 7.8, 11.11, 21.1_
  - [x] 1.6 Create `228-seed-procurement-delivery-stages.xml`
    - Insert the eight new `delivery_statuses` rows (RU/PL names) with `NOT EXISTS` guards; update
      `order_no` of `new`/`ordered`/`delivered`/`cancelled` only where they still hold 1/2/3/4.
    - _Requirements: 12.1, 12.2, 12.3, 16.3_
  - [x] 1.7 Register changesets 223–228 in `changelog.xml`
    - Append `<include .../>` lines **last**, in order, with a `FOR-11-00` comment.
    - _Requirements: 1.1, 2.1, 4.1, 7.1, 11.1, 12.1_
    - _Depends on: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

- [x] 2. NIP registry seam (shipped non-working)
  - [x] 2.1 Interface, result, properties, conditions, disabled fallback
    - `NipLookupService`, `NipLookupResult`, `NipLookupUnavailableException`, `NipLookupProperties`
      (`foremen.nip-lookup`: `enabled` false, `baseUrl`, `apiKey`, `timeout` 5 s), configured /
      not-configured conditions, `NipLookupConfig` (`@EnableConfigurationProperties`),
      `DisabledNipLookupService`. Model on `config/translation/*` and `service/translation/*`.
    - _Requirements: 6.1, 6.2, 6.6_
  - [x] 2.2 `RestNipLookupService` + unit tests
    - `RestClient` GET `{baseUrl}?nip=` with `X-Api-Key`, tolerant JSON mapping to `NipLookupResult`,
      404/empty -> empty, failures -> `NipLookupUnavailableException`, key never logged. Tests with
      `MockRestServiceServer`: request shape, mapping, unknown properties, 404, 5xx, timeout, no key in
      messages; condition test for bean selection.
    - _Requirements: 6.3, 6.4, 6.5_
    - _Depends on: 2.1_

- [x] 3. Seller NIP
  - [x] 3.1 Seller `nip`: entity, DTOs, mapper, service validation
    - Field on `MaterialSellerEntity`; create/update/response DTOs; normalize with `NipValidator`
      re-mapped to `error.materialSeller.nip.invalid`; duplicate check -> 409
      `error.materialSeller.nip.duplicate` (plus DB index); `nip` filterable/sortable; `DAO.findByNip`.
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6_
    - _Depends on: 1.3_
  - [x] 3.2 `SellerLookupService` + `GET /api/material-sellers/lookup`
    - Dictionary-first, then registry; `SellerLookupResponse` with `source`, `seller`, `candidate`,
      `registryAvailable`; 502 `error.nipLookup.unavailable`; handler annotated
      `@RequiresPermission(MATERIAL_SELLERS, READ)`; candidate never persisted.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 4.7_
    - _Depends on: 2.1, 3.1_

- [x] 4. Project-scoped materials
  - [x] 4.1 Finishing material project/seller mapping and global-list filtering
    - Add `project`, `seller` to `FinishingMaterialEntity`; `addRequiredQuery` returning
      `project IS NULL` in `FinishingMaterialService`; add `project IS NULL` to the DAO queries
      `findByActiveTrueAndRetailNetNotNull` and `findActiveByTypes`; additive `sellerId`/`sellerName`
      on the finishing DTOs; re-read each backend consumer to confirm it loads by id.
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_
    - _Depends on: 1.2_
  - [x] 4.2 `ProcurementProjectAccess`
    - Component with `allowedProjectIds(userId)` (BUYER: all project ids; others: ACTIVE
      memberships, same query as the other scoped services), `isBuyer`, `isMoneyPrivileged`.
      Unit-tested for every role.
    - _Requirements: 1.5, 1.6, 9.2_
  - [x] 4.3 `ProjectMaterialService` + controller + DTOs + mapper
    - Read `FinishingMaterialService` first; reuse its write validation (extract a shared validator
      if needed without changing behavior); `ProjectScopedService` (`project.id`); project required,
      `ACTIVE`, immutable; in-use delete guard against `PurchasePositionDao`;
      `@PermissionResource("PROJECT_MATERIALS")` controller at `/api/project-materials`.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_
    - _Depends on: 4.1, 4.2, 5.1_

- [x] 5. Positions and requests
  - [x] 5.1 Enums, entities, DAOs
    - `PositionKind`, `SourcingPath`, `PositionStatus`, `RequestStatus`; `PurchasePositionEntity`,
      `PurchaseRequestEntity` (+ inverse collection), `PurchasePositionDao`, `PurchaseRequestDao`
      (incl. overdue-candidate query and in-use query).
    - _Requirements: 7.1, 7.2, 7.4, 7.9, 8.1, 11.1_
    - _Depends on: 1.4, 1.5_
  - [x] 5.2 `PositionStage` + `PositionTransitionPolicy` with unit and property tests
    - Pure policy (graph, MANUAL vs SYSTEM, gate), stage -> `delivery_statuses` code mapping and rank.
      Property: for every path/stage/actor the gate never lets `AGREED` reach `WITH_BUYER` except from
      `CLIENT_PAID`; MANUAL never enters gated stages; stage mapping matches the seeded codes.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.8, 12.2, 12.5_
    - _Depends on: 5.1_
  - [x] 5.3 `PositionPriceVisibility` with unit and property tests
    - Pure policy for `STAFF_FULL`, `SITE`, `CLIENT`; property: `SITE` always all-null; `CLIENT` never
      a purchase-side field and nothing for `PACKAGE`/`CLIENT_OWN`.
    - _Requirements: 10.1, 10.2, 10.3, 10.4_
    - _Depends on: 5.1_
  - [x] 5.4 `PurchaseRequestStatusDeriver` + overdue helper with property tests
    - Pure derivation (OPEN/RELEASED/COMPLETED/CANCELLED) and overdue rule (Europe/Warsaw `Clock`).
    - _Requirements: 11.8, 11.9_
    - _Depends on: 5.1_
  - [x] 5.5 `PurchasePositionService`, `PositionLifecycleService`, controller, DTOs, project lookup
    - `ProjectScopedService` (`project.id`) using `ProcurementProjectAccess`; create/update/delete
      rules; `POST /{id}/transition` (MANUAL edges only); `GET /api/procurement/projects`; DTO mapper
      applying `PositionPriceVisibility`; Money_Field filter/sort rejection for non-`STAFF_FULL`;
      default sort `needByDate`; `@PermissionResource("PURCHASE_POSITIONS")`.
    - _Requirements: 8.6, 8.7, 9.1, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 10.5, 10.6_
    - _Depends on: 4.2, 5.2, 5.3_
  - [x] 5.6 `PurchaseRequestService` + controller + DTOs + assignment event
    - CRUD, attach/detach with the stage rule, buyer validation, derived `status`/`overdue`/
      `positionCount`, `PurchaseRequestAssignedEvent` on assignment change, clearing
      `overdueNotifiedAt` when the date moves to the future; `@PermissionResource("PURCHASE_REQUESTS")`.
    - _Requirements: 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.10, 13.4_
    - _Depends on: 4.2, 5.2, 5.4_

- [x] 6. Notifications
  - [x] 6.2 Events and `ProcurementNotificationEmitter`
    - `PurchaseRequestAssignedEvent`, `PurchaseRequestOverdueEvent`; AFTER_COMMIT best-effort emitter
      for `notification.request.assigned` and `notification.request.overdue` with the recipients and
      deep link of Requirement 13; recipients via `UserDao` (role `BUYER`, active) and
      `ProjectMemberDao` (ACTIVE `MANAGER`).
    - _Requirements: 13.1, 13.3, 13.5, 13.6, 13.7_
    - _Depends on: 5.1_
  - [x] 6.1 `PurchaseRequestOverdueSweepService`
    - `@Scheduled(cron = "${foremen.procurement.request-overdue-sweep.cron:-}")`, one-shot stamping
      via `overdueNotifiedAt`, publishes the overdue event after staging (model on
      `OverdueSweepService`).
    - _Requirements: 13.2, 13.4_
    - _Depends on: 5.1, 5.4, 6.2_

- [x] 7. ABAC and datasets
  - [x] 7.1 Create `229-seed-procurement-resources.xml` and `230-grant-buyer-reference-resources.xml`, register them
    - Three resources via the `<insert tableName="resources">` form, per-role grants per the matrix,
      BUYER reference grants, sellers CRU for BUYER, `MANAGER` CREATE on `MATERIAL_SELLERS`,
      `NOTIFICATIONS` copied from FOREMAN; all idempotent; append the includes to `changelog.xml`
      after 228.
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 20.1_
    - _Depends on: 1.7, 4.3, 5.5, 5.6_
  - [x] 7.2 Datasets and coverage
    - `PurchasePositionsDataset` (field catalog of Requirement 24.1, date field
      `plannedDeliveryDate`) and `PurchaseRequestsDataset` (no Money_Fields, notes, links, NIP,
      variants); both override `nullProjectPredicate` to return an always-true predicate for `BUYER`;
      `PROJECT_MATERIALS` in `DatasetCoverage.EXCLUDED`.
    - _Requirements: 14.6, 14.7, 14.8, 14.9, 24.1, 24.6_
    - _Depends on: 5.1, 7.1_

- [x] 8. Configuration
  - [x] 8.1 `application.yml`, `.env.example`, `docker-compose.yml`
    - `foremen.nip-lookup.*` and `foremen.procurement.request-overdue-sweep.cron` with `${VAR:-}`
      defaults and explanatory comments; no secrets.
    - _Requirements: 15.1, 15.2, 15.3, 6.7_
    - _Depends on: 2.1_

- [x] 9. Backend i18n
  - [x] 9.1 Messages PL + RU and parity test
    - All `error.*` keys of the design (including the quick-seller, variant and upcoming keys) in
      `messages.properties` and `messages_ru.properties`; a parity test modeled on
      `ProcurementMessagesParityTest`.
    - _Requirements: 16.1, 16.2_

- [x] 10. Frontend (inner repo `foremen-frontend/`)
  - [x] 10.1 Seller types, schema and API
    - `nip` on types and zod schema (checksum, optional), `lookupSellerByNip`, `useSellerLookup`.
    - _Requirements: 17.1, 17.2_
    - _Depends on: 3.2_
  - [x] 10.2 Seller form and table
    - NIP input + "Find by NIP" button + result messages (DICTIONARY / REGISTRY / NONE /
      unavailable / invalid), prefill only empty fields, `nip` table column.
    - _Requirements: 17.1, 17.2, 17.3_
    - _Depends on: 10.1_
  - [x] 10.3 Navigation re-point and BUYER bottom bar
    - `/deliveries` -> `PURCHASE_POSITIONS`/READ; `BOTTOM_NAV_PROFILES.BUYER`; update
      `navigation.test.ts`, `PermissionGuard.test.tsx`, `BottomNav.test.tsx`.
    - _Requirements: 17.4, 17.5_
    - _Depends on: 7.1_
  - [x] 10.4 Locale keys PL + RU
    - All keys of the design (seller form/lookup/validation, `sellerPicker.*`,
      `sellerQuickCreate.*`, the two notification titles) at parity.
    - _Requirements: 17.6_
    - _Depends on: 10.2, 10.3, 12.2, 6.2_
  - [x] 10.5 Frontend tests
    - Seller form lookup states, `nip` column, schema checksum, locale parity.
    - _Requirements: 17.7_
    - _Depends on: 10.4_

- [x] 11. Backend integration and seed tests
  - [x] 11.1 NIP and seller tests
    - `MaterialSellerNipIntegrationTest` (create/update/duplicate/invalid, filter, lookup
      DICTIONARY/NONE/`registryAvailable=false`/502 with a mocked service bean).
    - _Requirements: 4.*, 5.*, 6.*, 18.1_
    - _Depends on: 2.2, 3.2_
  - [x] 11.2 Project materials and finishing regression tests
    - `ProjectMaterialControllerIntegrationTest`, `FinishingMaterialProjectScopeTest`; run the
      existing finishing-material controller/validation tests.
    - _Requirements: 2.*, 3.*, 19.1, 18.1_
    - _Depends on: 4.3_
  - [x] 11.3 Position and request integration tests
    - `PurchasePositionControllerIntegrationTest`, `PurchaseRequestControllerIntegrationTest`
      (scoping per role incl. BUYER all projects and non-member isolation, validations, transitions,
      delete/detach rules, money visibility, forbidden filter).
    - _Requirements: 7.*, 8.*, 9.*, 10.*, 11.*, 18.1_
    - _Depends on: 5.5, 5.6_
  - [x] 11.4 Sweep and emitter tests
    - `ProcurementOverdueSweepIntegrationTest`, `ProcurementNotificationEmitterTest` (one-shot,
      reset on date change, recipients, best-effort failure swallowing).
    - _Requirements: 13.*, 18.1_
    - _Depends on: 6.1, 6.2, 5.6_
  - [x] 11.5 Seed, ABAC and dataset tests
    - `BuyerRoleSeedIntegrationTest`, `ProcurementResourcesSeedIntegrationTest` (exact matrix,
      idempotent re-run, BUYER has no bank/payroll/payments grant), update
      `DeliveryStatusesResourceSeedIntegrationTest`, `DatasetCoverageTest`, `SensitiveFieldGuardTest`,
      startup with `PermissionAnnotationValidator`, BUYER dashboard loads.
    - _Requirements: 1.*, 12.4, 14.*, 19.1, 19.2, 18.1_
    - _Depends on: 7.2_

  - [x] 11.6 Quick seller, variants, hideDelivered and upcoming tests
    - `MaterialSellerQuickIntegrationTest`, `SellerCodeGeneratorTest` (+ property),
      `PositionVariantIntegrationTest`, `VariantStagePolicyTest`, `HideDeliveredIntegrationTest`,
      `PlannedDeliveryDateSynchronizerTest`, `UpcomingDeliveriesIntegrationTest` (see design).
    - _Requirements: 20.*, 21.*, 22.*, 23.*, 11.11, 18.1_
    - _Depends on: 12.1, 13.2, 14.1, 14.2_

- [x] 12. Inline seller creation
  - [x] 12.1 Backend `POST /api/material-sellers/quick`
    - `SellerQuickRequest`/`SellerQuickResponse`, `SellerCodeGenerator` (pure, diacritic folding,
      collision suffix), `SellerQuickService.findOrCreate` (NIP hit returns the existing seller with
      `created=false`; unique-index race handled by re-read), handler on `MaterialSellerController`
      with `@RequiresPermission(MATERIAL_SELLERS, CREATE)`; unit tests for the generator.
    - _Requirements: 20.1, 20.2, 20.3, 20.4, 20.5_
    - _Depends on: 3.1, 3.2_
  - [x] 12.2 Frontend `SellerPicker` and `SellerQuickCreate` (inner repo)
    - Async searchable picker over `/api/material-sellers` (name/NIP search), "+ Add seller" footer
      shown only with `MATERIAL_SELLERS`/CREATE, inline popover with **Name** and **NIP**, "Find by
      NIP" (reuses `useSellerLookup`), create via `useQuickCreateSeller`, auto-select of the created
      or already-existing seller; standalone, embeddable by FOR-11-01; component tests.
    - _Requirements: 20.6, 20.7, 20.8, 20.9, 17.8_
    - _Depends on: 10.1, 12.1_

- [x] 13. Variant history
  - [x] 13.1 `PurchasePositionVariantEntity` + DAO
    - Entity, enum `VariantReason` (`ANALOG`, `REPRICED`, `REJECTED`, `MANUAL`), DAO with the history,
      count-by-positions (batched) and suggestions queries; project-material in-use query includes
      variants.
    - _Requirements: 21.1, 21.9, 3.7_
    - _Depends on: 5.1_
  - [x] 13.2 `PositionVariantService`, controller, DTOs, `variantCount`
    - `replace` / `restore` / `history` / `suggestions`, pure `VariantStagePolicy`, price visibility
      applied to every DTO, `variantCount` on the position DTO (batched), controller at
      `/api/purchase-positions/{id}/variants` with `@RequiresPermission(PURCHASE_POSITIONS, ...)`.
    - _Requirements: 21.2, 21.3, 21.4, 21.5, 21.6, 21.7, 21.8, 21.10_
    - _Depends on: 5.5, 13.1_

- [x] 14. Planner filter and upcoming deliveries
  - [x] 14.1 `hideDelivered` for positions and requests
    - Parameter on both list endpoints, Criteria `Specification`/subqueries composed with scope and
      user filters, OpenAPI description; deriver-equivalence for requests.
    - _Requirements: 22.1, 22.2, 22.3, 22.4, 22.5, 9.3_
    - _Depends on: 5.5, 5.6_
  - [x] 14.2 `plannedDeliveryDate` synchronizer and `GET /api/purchase-positions/upcoming`
    - `PlannedDeliveryDateSynchronizer` called by `PurchaseRequestService` on attach/detach/date
      change (terminal positions skipped); `UpcomingDeliveriesService` single-query projection;
      endpoint declared before `/{id}`; `days` validation.
    - _Requirements: 11.11, 23.1, 23.2, 23.3, 23.4, 23.5, 23.6_
    - _Depends on: 14.1_

- [x] 15. Dashboard widgets
  - [x] 15.1 `RouteWhitelist` `/deliveries`
    - Add the template, keep the frontend `route-permissions` in sync, update
      `RouteWhitelistContractTest`.
    - _Requirements: 24.3_
    - _Depends on: 7.2, 10.3_
  - [x] 15.2 Create `231-seed-procurement-dashboard-widgets.xml` and `232-seed-procurement-dashboard-defaults.xml`, register them
    - Three library widgets (validated config JSON, RU/PL names), role defaults for FOREMAN, MANAGER,
      BUYER, ADMIN on free cells chosen by reading `GridMath` and the existing placements; append the
      includes **last** in `changelog.xml`.
    - _Requirements: 24.2, 24.4, 24.5_
    - _Depends on: 7.2, 15.1, 14.2_
  - [x] 15.3 Widget tests
    - Update `DashboardWidgetLibrarySeedTest` and `DashboardRoleDefaultsSeedTest`; add
      `PurchaseDatasetsBuyerScopeTest` (BUYER sees non-member projects, FOREMAN only his own,
      widgets resolve with real data) and a check that every widget validates against the registry.
    - _Requirements: 24.6, 24.8, 14.7_
    - _Depends on: 15.2_

- [x] 16. Author test-cases.md
  - [x] 16.1 Create `test-cases.md`
    - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
      (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression
      group, MD report template). This is an API + UI spec: Part A = API tests against the Dockerized
      app (BUYER role access, project materials, seller NIP lookup, quick seller, positions, gate,
      variant history, hideDelivered, upcoming deliveries, requests, price visibility, overdue sweep,
      seeds, dashboard widgets); Part B = browser-engine scenarios (seller form NIP lookup, seller
      picker with inline creation, BUYER shell and Dostawy menu, dashboard widgets, PL/RU). Result
      artifacts are MD reports with tables.
    - _Requirements: 18.3, and the requirements covered by the test cases (1-24)_
    - _Depends on: 10.4, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 15.3_

- [x] 17. Checkpoint — full verification + two-repo commit
  - Run the affected backend test classes (`--tests`, redirected log, JUnit XML) covering the new
    policies, SPI, services, seeds, datasets, messages parity and the existing finishing-material,
    seller and delivery-status classes; run frontend unit tests and locale parity; check in the
    Docker stack that a BUYER user logs in, sees Dostawy, can read positions of every project, and
    cannot reach bank/payroll. Commit the root repo and the `foremen-frontend/` repo separately per
    `.kiro/steering/git-repo-structure.md`. Commit only when the user asks.
  - _Requirements: 18.1, 18.2, 19.1, 19.2, 19.3, 19.4_
  - _Depends on: all above_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "2.1", "4.2", "9.1"] },
    { "id": 1, "tasks": ["1.7", "2.2", "3.1", "4.1", "5.1", "8.1"] },
    { "id": 2, "tasks": ["3.2", "4.3", "5.2", "5.3", "5.4", "6.2", "13.1"] },
    { "id": 3, "tasks": ["5.5", "5.6", "6.1", "10.1", "11.1", "11.2", "12.1"] },
    { "id": 4, "tasks": ["7.1", "10.2", "11.3", "11.4", "12.2", "13.2", "14.1"] },
    { "id": 5, "tasks": ["7.2", "10.3", "14.2"] },
    { "id": 6, "tasks": ["10.4", "11.5", "11.6", "15.1"] },
    { "id": 7, "tasks": ["10.5", "15.2"] },
    { "id": 8, "tasks": ["15.3"] },
    { "id": 9, "tasks": ["16.1"] },
    { "id": 10, "tasks": ["17"] }
  ]
}
```
