# Implementation Plan: FOR-11-01 — Purchase planner and buyer board

## Overview

This plan delivers the screens of FOR-11 on top of the FOR-11-00 foundation: the manager **planner**
(site-delivery-date sort, urgent pick, slices, grouping with totals by request, tick-to-proforma
running total, per-position margin, link to the work schedule), the **BUYER board**, the
**company-wide purchase view** (the planner without a project filter), the **position form** with
material picker and variant history, **requests from the planner**, and the per-user
**hide-delivered** switcher. The backend part is small: three read controllers, one write endpoint, one
additive column and six pure helpers. No notifications, no new resource/role/seed.

**Reused, not re-implemented.** `ProcurementProjectAccess`, `PositionPriceVisibility`,
`PositionTransitionPolicy`, `HideDeliveredFilter`, `PurchaseRequestService` (create/attach/assignment
event/date synchronizer), `MarginCostService.materialMargin`, `ScheduleCalculator`, the 00 variants API,
`SellerPicker`; frontend `DataTable`, the `DocumentGroupRow` grouped-row pattern, `buildFetchQuery`,
`usePermission`, the persisted-filter pattern, and the notification deep-link handler.

**Spans two git repos** (per `.kiro/steering/git-repo-structure.md`):
- **Root repo** — changeset `233`, services/controllers/pure helpers, config, backend tests, messages,
  and this spec. Commit from the workspace root.
- **Frontend repo** (`foremen-frontend/`) — the `features/purchase-planner/` feature, router/page swap,
  locale keys, frontend tests. Commit from inside `foremen-frontend/`.
A full implementation needs **two commits — one per repo**, made only when the user asks.

**Migrations.** The only changeset is `233-add-purchase-positions-work-category.xml` (current tail is
`232-seed-procurement-dashboard-defaults.xml`); if a sibling spec lands first, use the next free `NNN`.
It is idempotent and appended **last** in `changelog.xml` by task 1.2 only.

**Test-run policy (backend).** Do NOT run the full Gradle suite (~20 min, per
`.kiro/steering/test-execution-rules.md`). Run only affected classes with `--tests`, redirect to
`/tmp/for-11-01-test.log`, read the log and the JUnit XML under
`foremen-backend/build/test-results/test/`. Use `getDiagnostics` for fast per-file checks and
`./gradlew compileJava compileTestJava` (redirected) for compile-only checks. Frontend: `vitest --run`
on the affected files only.

## Tasks

- [x] 1. Database and configuration
  - [x] 1.1 Create `233-add-purchase-positions-work-category.xml`
    - Nullable `work_category_id` (FK `work_categories`, `ON DELETE SET NULL`, index
      `idx_purchase_positions_work_category`), each as its own idempotent changeSet (`columnExists` /
      `foreignKeyConstraintExists` / `indexExists`, `MARK_RAN`).
    - _Requirements: 5.1, 10.1_
  - [x] 1.2 Register changeset 233 in `changelog.xml`
    - Append the include **last**, after 232.
    - _Requirements: 10.1_
    - _Depends on: 1.1_
  - [x] 1.3 Add `foremen.procurement.urgent-days` configuration
    - `application.yml` (`${FOREMEN_PROCUREMENT_URGENT_DAYS:14}`), a `@Validated` properties record with
      `@Min(1) @Max(90)`, `.env.example` and `docker-compose.yml` (`backend.environment`, `${VAR:-14}`).
    - _Requirements: 2.5, 10.2_

- [x] 2. Pure helpers (backend, package `com.foremen.service.procurement.planner`)
  - [x] 2.1 `PositionMoneyMath` and `DeliveryCostAllocator` with tests
    - Per-line rounding (`HALF_UP`, scale 2), VAT per rate, totals as the sum of rounded lines;
      largest-remainder allocator (sum of shares equals the total, zero weights, empty list).
      Unit tests plus property tests (sum reconciliation, non-negativity, proportionality within one
      cent).
    - _Requirements: 4.1, 4.3_
  - [x] 2.2 `PlannerSliceRules` with pure functions and JPA predicate builders
    - `isLate`, `isUrgent`, `slice` over a `PositionSnapshot`; the same definitions as `Predicate`
      builders (`latePredicate`, `urgentPredicate`, `slicePredicate`); stage sets and `Today` in
      `Europe/Warsaw` through an injected `Clock`. Unit tests of the pure side; the SQL equivalence test
      is task 6.1.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  - [x] 2.3 `PositionMarginCalculator` with tests
    - Reuses `MarginCostService.materialMargin`; unavailable propagation, zero revenue, actual vs planned
      purchase price, delivery share. Unit and property tests.
    - _Requirements: 4.2, 4.4_
    - _Depends on: 2.1_

- [x] 3. Work-category link
  - [x] 3.1 Add the manual `workCategory` override to the position entity, requests, DTO and service
    - Entity field (`LAZY`), `workCategoryId` (manual override, null = automatic) in
      `PurchasePositionCreateRequest`/`UpdateRequest`/`PurchasePositionDtoModel` and the mappers; service
      validation (exists, active, edit only before `ORDERED`; a non-scheduled category is allowed) with
      `error.purchasePosition.workCategory.invalid`; update the affected 00 tests for the additive field.
    - _Requirements: 5.4, 19.1_
    - _Depends on: 1.2_
  - [x] 3.2 `WorkStartResolver`
    - Read `ScheduleCalculator` and `ProjectScheduleService` first to copy the exact half-day to date
      rule; batched `resolve(Collection<(projectId, workCategoryId)>)` (earliest bar start per pair, one
      query); null for no schedule / bar / project start date. Unit tests for the conversion and the
      earliest-bar rule.
    - _Requirements: 5.5_
  - [x] 3.3 `WorkCategoryResolver`, `WorkConsumptionLookup` and `ScheduledCategoryService`
    - Pure `WorkCategoryResolver` (manual wins; tier 1 estimate works, tier 2 catalog; intersect with
      scheduled categories; earliest start wins with deterministic tie-break; candidates; `NONE`).
      `ScheduledCategoryService` (current schedule rows from `ScheduleRowDerivation` intersected with stored
      bars, earliest bar per category, start date via `WorkStartResolver`). `WorkConsumptionLookup` with two
      new `WorkMaterialConsumptionDao` queries (finishing branch only): categories by type for a set of
      work items, and categories by type over the catalog (run only for unresolved types). Unit and
      property tests for the resolver; integration test for the lookup.
    - _Requirements: 5.1, 5.2, 5.3, 5.8, 5.9_
    - _Depends on: 3.2_

- [x] 4. Planner services and endpoints (backend)
  - [x] 4.1 `PlannerFilter`, `PlannerRowDto` and `PlannerQueryService`
    - Specification composition (scope, filters, `buyer` modes, slice/urgent predicates, reused
      `HideDeliveredFilter`, escaped `q`), `siteDeliveryDate` sort with nulls last, batched row assembly
      (fetch joins, `fillVariantCounts`, `WorkCategoryResolver` + lookups + `WorkStartResolver`), derived flags, Money block through
      `PositionPriceVisibility`, `allowedActions` from the transition/variant policies and caller role,
      slice counts in one statement.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.6, 4.4, 5.6, 5.7, 5.8, 9.2, 9.5_
    - _Depends on: 2.1, 2.2, 2.3, 3.1, 3.3_
  - [x] 4.2 `PurchasePlannerController`: planner, slice counts, scheduled categories, work-link preview
    - `GET /api/purchase-positions/planner`, `/planner/slice-counts`, `/scheduled-categories`,
      `/work-link-preview` with
      `@RequiresPermission(PURCHASE_POSITIONS, READ)`, validation of `sort`/`slice`, OpenAPI docs of all
      parameters.
    - _Requirements: 1.1, 1.2, 2.6, 5.2, 5.10, 9.1_
    - _Depends on: 4.1_
  - [x] 4.3 `PlannerGroupService` and the groups endpoint
    - `GET /api/purchase-positions/planner/groups` on `PurchasePlannerController`: paged groups by
      earliest site-delivery date, request headers with derived status/overdue, totals aggregated in Java
      with the money helpers, synthetic "no request" group, reserved `groupBy` values rejected.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 4.5_
    - _Depends on: 4.2_
  - [x] 4.4 `SelectionTotalService` and `PurchaseSelectionController`
    - `POST /api/purchase-positions/selection-total` (STAFF_FULL only), counted/skipped classification,
      VAT breakdown, `mixedProjects`, size limits.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
    - _Depends on: 2.1_
  - [x] 4.5 `RequestFromPositionsService` and `POST /api/purchase-requests/from-positions`
    - One transaction: create the request through the 00 service (validation, assignment event), attach
      the positions (attach rules, date synchronizer); extract a service-level create method only if the
      00 create path is reachable only through the controller mapper, without changing its behavior.
      `@RequiresPermission(PURCHASE_REQUESTS, CREATE)`.
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_
  - [x] 4.6 `BoardService` and `PurchaseBoardController`
    - `GET /api/purchase-positions/board`: six fixed columns, counts, cards by site-delivery date
      (window-function or six `LIMIT` queries), `hideDelivered` count-only for `RECEIVED`, ceiling flag,
      visibility step, `limit` validation.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_
    - _Depends on: 4.1_

- [x] 5. Backend i18n
  - [x] 5.1 Add the new `error.*` keys and the parity test
    - Nine keys of the design in `messages.properties` (PL) and `messages_ru.properties` (RU); parity
      test modeled on the 00 one. Single owner of both bundles in this spec.
    - _Requirements: 11.1, 11.2_
    - _Depends on: 3.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 6. Backend tests
  - [x] 6.1 Planner integration tests
    - `PurchasePlannerIntegrationTest` (filters compose, default sort, `q` escaping, `buyer` modes,
      hideDelivered, scoping, statement count, `allowedActions`, null money), `PlannerSliceCountsIntegrationTest`,
      `PlannerSliceRulesSqlEquivalenceIT`, `PositionWorkCategoryIntegrationTest` (manual override, auto tiers,
      candidates, scheduled-categories search, work-link preview, risk flag).
    - _Requirements: 1.*, 2.*, 5.*, 18.1_
    - _Depends on: 4.2, 5.1_
  - [x] 6.2 Groups integration test
    - `PlannerGroupsIntegrationTest` (order, reconciliation of group totals with the flat list property,
      synthetic group, cancelled excluded, reserved `groupBy`).
    - _Requirements: 3.*, 4.5, 18.1_
    - _Depends on: 4.3, 5.1_
  - [x] 6.3 Selection total and create-from-positions integration tests
    - `SelectionTotalIntegrationTest`, `RequestFromPositionsIntegrationTest` (atomic rollback, single
      assignment event, date sync, BUYER 403).
    - _Requirements: 6.*, 7.*, 18.1_
    - _Depends on: 4.4, 4.5, 5.1_
  - [x] 6.4 Board integration test
    - `PurchaseBoardIntegrationTest` (columns, order, limit/hasMore, hideDelivered, gate exclusion,
      ceiling flag, FOREMAN null money).
    - _Requirements: 8.*, 18.1_
    - _Depends on: 4.6, 5.1_
  - [x] 6.5 ABAC and visibility matrix
    - `PlannerAbacMatrixIntegrationTest`: every new endpoint x ADMIN / MANAGER / BUYER / FOREMAN /
      FINANCIER / CLIENT, no money leak for non-staff, startup passes `PermissionAnnotationValidator`.
    - _Requirements: 9.1, 9.2, 9.4, 9.5, 19.2_
    - _Depends on: 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 7. Frontend foundation (inner repo `foremen-frontend/`)
  - [x] 7.1 Types, API client, query/mutation hooks and `lib/`
    - `features/purchase-planner/types`, `api/purchase-planner-api.ts` (planner, slice-counts, groups,
      board, selectionTotal, scheduledCategories, workLinkPreview, fromPositions, variants, transition, request update/detach/delete),
      `query-hooks.ts`/`mutation-hooks.ts` with the invalidation set (planner, groups, slice-counts, board),
      `lib/filters.ts` (URL state parse/serialize, API params), `lib/format.ts`, and
      `hooks/usePersistedHideDelivered.ts` (per-user key, default ON, tolerant storage). Unit tests for
      the pure parts.
    - _Requirements: 12.3, 12.5, 17.2_
  - [x] 7.2 Locale keys
    - Add the `purchasePlanner.*` and `buyerKanban.*` groups to `pl.json` and `ru.json` at parity
      (values from the design); remove `pages.deliveries.*`.
    - _Requirements: 17.1, 17.3_

- [x] 8. Frontend screens
  - [x] 8.1 Page shell, tabs, URL state and routing
    - `PurchasePlannerPage` (tabs by role, default tab, project filter from `/api/procurement/projects`,
      hide-delivered switcher), `usePlannerUrlState`, `app/pages/DeliveriesPage.tsx` re-export, router
      unchanged; company-wide view when a BUYER/ADMIN has no project selected.
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_
    - _Depends on: 7.1, 7.2_
  - [x] 8.2 Planner toolbar, slice bar and flat/grouped table skeleton
    - `PlannerToolbar`, `SliceBar` (counts), `PlannerTable`, `PlannerRowCells` (money cells only when
      `money != null`, late highlight, risk and over-ceiling markers), sorting, pagination, empty and
      loading states, mobile cards, read-only FOREMAN mode.
    - _Requirements: 13.1, 13.2, 13.6, 13.8, 9.3, 9.4_
    - _Depends on: 8.1_
  - [x] 8.3 Group rows, selection bar and deep link
    - `PlannerGroupRow` (lazy rows, totals, overdue marker), selection state across paging, `SelectionBar`
      (debounced selection total, skipped reasons, mixed-project warning, hidden for non-staff, "Select
      urgent"), `?request={id}` expand/scroll/highlight and the not-found message.
    - _Requirements: 13.3, 13.4, 13.5, 13.7_
    - _Depends on: 8.2, 8.7_
  - [x] 8.4 Position form sheet and material picker
    - `PositionFormSheet` (zod schema mirroring the 00 rules, locked fields per stage, money inputs for
      staff only, "Set from schedule"), `WorkCategoryLink` (auto badge + searchable dropdown of scheduled
      categories + reset to automatic, preview on material change), `MaterialPicker` (catalog + project materials merged and tagged,
      seller filter via `SellerPicker`).
    - _Requirements: 14.1, 14.2, 14.4, 14.7_
    - _Depends on: 8.1_
  - [x] 8.5 Project-material quick create
    - `ProjectMaterialQuickCreate` opened from the picker (visible with `PROJECT_MATERIALS`/CREATE) with
      the fields the 00 create request requires, `SellerPicker` for the seller; selects the created
      material.
    - _Requirements: 14.3_
    - _Depends on: 8.4_
  - [x] 8.6 Variant expander and row actions
    - `VariantExpander` (history, suggestions, replace, restore; write controls only with
      `REPLACE_VARIANT`), `RowActionsMenu` (only `allowedActions`, confirmations, localized 409 messages),
      query invalidation.
    - _Requirements: 14.5, 14.6_
    - _Depends on: 8.3_
  - [x] 8.7 Request dialog and request header actions
    - `RequestDialog` (create from selection, default date = earliest site-delivery date, buyer select),
      `RequestHeaderActions` (edit, detach, delete when empty). First check which endpoint a MANAGER may
      call to list active BUYER users; add a small `GET /api/procurement/buyers` (guarded by
      `PURCHASE_REQUESTS` READ) only if none is available, and then also add it to task 6.5.
    - _Requirements: 15.1, 15.2, 15.3, 15.4_
    - _Depends on: 8.1_
  - [x] 8.8 Buyer board tab
    - `BoardTab`, `BoardColumn`, `BoardCard`, `PositionDrawer`; filters (project, buyer with BUYER default
      `MINE`, search), "Load more" through the planner endpoint, refetch on focus and every 60 s, mobile
      column selector, hide-delivered handling for the `RECEIVED` column.
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 12.6_
    - _Depends on: 8.1_

- [x] 9. Frontend tests
  - [x] 9.1 Component and hook tests
    - URL state, persisted preference, `PlannerTable` (grouping, lazy expand, hidden money), `SelectionBar`,
      `PositionFormSheet`, `MaterialPicker` and `ProjectMaterialQuickCreate`, `VariantExpander`,
      `RowActionsMenu`, `RequestDialog`, `BoardTab`, deep link, role defaults, and locale parity
      (`src/lib/i18n.keys.test.ts`).
    - _Requirements: 18.3, 17.3_
    - _Depends on: 8.3, 8.5, 8.6, 8.8_

- [x] 10. Author test-cases.md
  - [x] 10.1 Create `test-cases.md`
    - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
      (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group,
      MD report template). This is an API + UI spec: Part A = API tests against the Dockerized app
      (planner filters and slices, groups and totals, selection total, request from positions, board,
      work category and work start, price visibility per role, ABAC); Part B = browser-engine scenarios
      (planner, selection bar, position form, variants, requests, board, deep link, hide-delivered
      persistence, PL/RU, mobile). Result artifacts are MD reports with tables.
    - _Requirements: 18.4, and the requirements covered by the test cases (1-17)_
    - _Depends on: 6.1, 6.2, 6.3, 6.4, 6.5, 9.1_

- [x] 11. Checkpoint — verification and two-repo commit
  - Run the affected backend classes (`--tests`, redirected log, JUnit XML) for the new helpers, services,
    controllers, messages parity and the touched 00 position tests; run the affected frontend tests and
    locale parity; in the Docker stack check as BUYER (board default, company-wide planner, no bank/payroll),
    MANAGER (planner, tick, selection total, create request, position form) and FOREMAN (read-only, no
    money). Commit the root repo and the `foremen-frontend/` repo separately per
    `.kiro/steering/git-repo-structure.md`. Commit only when the user asks.
  - _Requirements: 18.1, 18.2, 19.1, 19.2, 19.3, 19.4_
  - _Depends on: all above_

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "2.1", "2.2", "3.2", "4.5", "7.1", "7.2"] },
    { "id": 1, "tasks": ["1.2", "2.3", "3.3", "4.4", "8.1"] },
    { "id": 2, "tasks": ["3.1", "8.2", "8.4", "8.7", "8.8"] },
    { "id": 3, "tasks": ["4.1", "8.3", "8.5"] },
    { "id": 4, "tasks": ["4.2", "4.6", "8.6"] },
    { "id": 5, "tasks": ["4.3", "9.1"] },
    { "id": 6, "tasks": ["5.1"] },
    { "id": 7, "tasks": ["6.1", "6.2", "6.3", "6.4", "6.5"] },
    { "id": 8, "tasks": ["10.1"] },
    { "id": 9, "tasks": ["11"] }
  ]
}
```
