# Implementation Plan: FOR-10 — Configurable user dashboard

## Overview

Backend work is committed in the **root repo** (`foremen-backend/`, `.kiro/`, `docker-compose.yml`,
`.env.example`); frontend work is committed in the **`foremen-frontend/` repo** (it is ignored by the
root repo). Tests follow the project rule: run only affected classes with `--tests`, write the output to
`/tmp/for-10-test.log` and read the JUnit XML.

Conventions: Liquibase changesets are numbered from `219` and registered LAST in
`database_files/changelog.xml` (re-check the tail before starting). The new managed entity follows the
`entity-creation-rules` checklist.

## Tasks

- [x] 1. Foundation (root repo)
  - [x] 1.1 Create dashboard tables, entities and DAOs
    - Changeset `219-create-dashboard-tables.xml`: `dashboard_widgets`, `user_dashboard_widgets`
      (unique indexes incl. `COALESCE(project_id,0)`), `role_default_widgets`, `user_dashboard_state`,
      `user_dashboard_appearance`, `user_dashboard_widget_style`; CHECK constraints for spans,
      `cell_index`, `project_scope`. Register in `changelog.xml`.
    - Entities `DashboardWidgetEntity`, `UserDashboardWidgetEntity`, `RoleDefaultWidgetEntity`,
      `UserDashboardStateEntity`, `UserDashboardAppearanceEntity`, `UserDashboardWidgetStyleEntity`
      (jsonb `config` / `palette` via `@JdbcTypeCode(SqlTypes.JSON)`), DAOs.
    - _Requirements: 4.1, 1.3, 3.6, 5.8, 5.10, 15.1, 18.3_
  - [x] 1.2 Seed the `DASHBOARD_WIDGETS` resource and ADMIN grant
    - Changeset `220-seed-dashboard-widgets-resource.xml` per entity-creation-rules (two changesets,
      `MARK_RAN` preconditions, RU/PL names and descriptions). Register last.
    - _Requirements: 4.2_
  - [x] 1.3 Add backend error message bundles (RU/PL)
    - Add all `error.dashboard.*` codes listed in design to the message bundles.
    - _Requirements: 16.2_

- [x] 2. Backend core (root repo)
  - [x] 2.1 Widget_Config model, validator and route whitelist
    - `WidgetConfig` records (Jackson), `WidgetConfigValidator` collecting all violations with the
      shape rules of the design table, `RouteWhitelist`, 64 KB cap, `schemaVersion` check,
      `WidgetStyleCatalog` and the `presentation.style` / `presentation.colors` checks.
    - Property tests (jqwik): unknown field / route always rejected; valid generated configs accepted.
    - _Requirements: 5.1–5.6, 10.2, 12.6_
  - [x] 2.2 Access evaluation
    - Add `PermissionSet.hasAnyOperation(resource)`; `DashboardAccessService` (`missingResources`,
      `evaluate`, `WidgetState`) using `ForemenPermissionEvaluator`/`PermissionCache`; ADMIN bypass.
    - Unit + property tests (monotonicity, ADMIN, unknown role denies).
    - _Requirements: 6.3, 8.1, 8.2, 9.1, 9.3, 9.5_
  - [x] 2.3 Dataset framework and query engine
    - `Dataset`, `FieldDescriptor`, `DatasetRegistry` (fails startup if a dataset's project path does
      not resolve), `QueryContext`, `DashboardQueryEngine` (Criteria, grouping, date buckets,
      aggregations, previous-period query, truncation, timeout), `WidgetResult`, dataset catalog DTO.
    - _Requirements: 5.5, 6.1, 6.4–6.6, 7.2, 8.3, 8.4_
  - [x] 2.4 Implement the dataset catalog (38 datasets, see design "Dataset catalog")
    - Each dataset: field catalog with sensitive fields excluded, project/owner paths, date field,
      LOOKUP fields, extra predicates. Confirm entity paths against the real model (open points in
      design). Reference-data resources are excluded; datasets may only join them as label fields.
    - _Requirements: 6.2, 6.2a–6.2d, 8.3–8.5_
    - [x] 2.4.1 Projects, team and schedule
      - `PROJECTS`, `PROJECT_MEMBERS`, `ROOMS`, `SCHEDULE_BARS`, `COMPANY_SCHEDULE_BARS`
    - [x] 2.4.2 Estimate, offers, amendments, documents
      - `ESTIMATES`, `ESTIMATE_LINES`, `OFFERS`, `OFFER_NEGOTIATION_ROUNDS`, `AMENDMENTS`,
        `AMENDMENT_LINES`, `SIGNABLE_DOCUMENTS`, `SIGNABLE_DOCUMENT_TYPES`, `DOCUMENT_TEMPLATES`,
        `COMPANY_PROFILE`
    - [x] 2.4.3 Catalogs and materials
      - `WORK_CATALOG`, `WORK_PRICES`, `WORK_MATERIAL_CONSUMPTION`, `CONSTRUCTION_MATERIALS`,
        `FINISHING_MATERIALS`
    - [x] 2.4.4 Execution, procurement and money
      - `WORK_REPORTS`, `PURCHASES`, `PURCHASE_LINES`, `COMPANY_PURCHASES`, `COMPANY_PURCHASE_LINES`,
        `CLIENT_MESSAGES`, `PAYMENTS`, `PAYMENT_SCHEDULES`, `PAYMENT_TRANCHES`, `PAYROLL`,
        `WORKER_PAYOUTS`, `BANK_CONNECTIONS`, `BANK_ACCOUNTS`, `BANK_LEDGER`; verify the
        `BANK_ACCOUNTS` required resource against the existing endpoint.
    - [x] 2.4.5 System datasets and guard tests
      - `USERS`, `ROLES`, `AUDIT_LOG`, `NOTIFICATIONS` (always-own); `DatasetCoverageTest` (every
        resource covered or explicitly excluded) and `SensitiveFieldGuardTest` (deny-list).
  - [x] 2.5 Grid math and layout service
    - `GridMath` (fits, footprint, firstFit), `DashboardLayoutService` (read with states, add/remove
      under per-user advisory lock, duplicate and project rules), property tests for grid safety.
    - _Requirements: 1.2–1.6, 3.1–3.7, 9.3, 12.1–12.3, 12.5_
  - [x] 2.6 Data fetch service and user controller
    - `DashboardDataService.fetch` / `previewFetch` implementing the ordered pipeline,
      `DashboardController` (`/api/dashboard`: layout, library, add, delete, data, projects picker),
      library `available` / `missingResources`, date validation (max 5 years).
    - _Requirements: 2.5, 2.6, 3.7, 6.3, 6.7, 8.6, 8.8, 9.1–9.5, 12.2, 12.4, 18.1_
  - [x] 2.8 Appearance service and endpoints
    - `PaletteValidator`, `DashboardAppearanceService`, `GET`/`PUT /api/dashboard/appearance`
      (own data only, partial update, null resets, placement ownership check); extend `GET
      /api/dashboard` with per-item effective `style`, `defaultStyle`, `availableStyles`. Property
      tests: style fallback, appearance isolation, palette validation.
    - _Requirements: 5.7–5.11_
  - [x] 2.7 Admin widget service and controller
    - `DashboardWidgetService` (CRUD, activate/deactivate publishing `DashboardWidgetDeactivatedEvent`,
      in-use and seed protections, `visibleToRoles`), `DashboardWidgetController` with
      `@PermissionResource("DASHBOARD_WIDGETS")`, datasets catalog, `validate`, `preview`, audit log
      entries; run `PermissionAnnotationValidator` startup check.
    - _Requirements: 4.3–4.9, 5.6, 8.7, 11.2–11.4, 18.4_

- [x] 3. Seeds and defaults (root repo)
  - [x] 3.1 Seed the default widget library
    - Changeset `221-seed-dashboard-widget-library.xml` with the 20 widgets, RU/PL names and
      descriptions, configs and links; test that validates every seeded config with the production
      validator and executes it against a seeded test database.
    - _Requirements: 14.1–14.3_
  - [x] 3.2 Seed role defaults and backfill existing users
    - Changeset `222-seed-dashboard-role-defaults.xml` with the final placements of the design table,
      the access check (drop defaults a role cannot access), and the idempotent backfill + state rows.
    - _Requirements: 15.1, 15.2, 15.5, 15.6_
  - [x] 3.3 Default widgets for new users
    - `DashboardDefaultsService.seedFor`, `UserCreatedEvent` published from every user-creation path,
      AFTER_COMMIT `REQUIRES_NEW` listener (best-effort), lazy seeding in layout read.
    - _Requirements: 15.3, 15.4_

- [x] 4. Notifications (root repo)
  - [x] 4.1 Dashboard notification emitter
    - `DashboardNotificationEmitter` (`notification.dashboard.ai.failed`,
      `notification.dashboard.widget.deactivated`), listener for `DashboardWidgetDeactivatedEvent`
      fanning out one notification per distinct user in chunks; follow the existing emitter pattern.
    - _Requirements: 17.1, 17.2_

- [x] 5. AI assistant (root repo)
  - [x] 5.1 Properties, env vars and compose wiring
    - `DashboardAiProperties` (`foremen.dashboard.ai.*`), `application.yml`, `.env.example`,
      `docker-compose.yml`, mirroring the rooms-import block.
    - _Requirements: 13.4_
  - [x] 5.2 Draft client
    - `WidgetDraftClient` + Gemini/Stub/Disabled implementations, prompts, response schema from the
      config schema, bounded retry with validator violations, 90 s timeout, size-only logs.
    - _Requirements: 13.3, 13.5, 13.6_
  - [x] 5.3 AI service and endpoint
    - `DashboardAiService` (rate limit 10/min per admin, validation, warnings, failure notification),
      `POST /api/dashboard-widgets/ai-draft` guarded by `DASHBOARD_WIDGETS/CREATE`.
    - _Requirements: 13.1, 13.2, 13.6–13.8, 17.1_

- [x] 6. Frontend (foremen-frontend repo)
  - [x] 6.1 Types, API client, hooks and pure libs
    - `features/dashboard/types`, `api/*` (react-query hooks), `lib/grid.ts`, `lib/date-range.ts`,
      `lib/format.ts`, `lib/link-resolver.ts` with unit tests for grid and date-range.
    - _Requirements: 1.2–1.5, 2.2–2.5, 10.3, 10.4_
  - [x] 6.2 Date filter bar
    - `DateFilterBar`, `QuickRangeSelect`, `useDateFilter` (sessionStorage, default current month,
      validation messages), reusing the existing `DatePicker`.
    - _Requirements: 2.1–2.7_
  - [x] 6.3 Widget renderers
    - Add `recharts` at a pinned exact version (confirm dependency); lazy-loaded
      Table/List/Bar/Line/Area/Pie/Donut/Kpi/ProjectCard renderers, formatting, a11y labels,
      clickable elements through the link resolver; implement all style variants of the style
      catalog per renderer and accept `palette` / `style` props.
    - _Requirements: 5.9, 7.1–7.5, 10.1–10.6_
  - [x] 6.4 Grid, tiles, picker and dialogs
    - `DashboardGrid` (CSS grid at 4/3/1 columns), `WidgetTile`, `DisabledWidgetTile`,
      `EmptyCellTile` ("+"), `WidgetPicker` with disabled-with-warning entries, `ProjectPicker`,
      `RemoveWidgetDialog`.
    - _Requirements: 1.1–1.8, 3.1–3.6, 9.2, 9.4, 12.2, 12.3_
  - [x] 6.5 Dashboard page wiring
    - Replace the stub `DashboardPage`: layout query, per-tile data queries, date filter, add/remove
      mutations, error mapping to localized messages.
    - _Requirements: 1.1, 1.7, 1.8, 2.6, 3.3, 3.5_
  - [x] 6.6 Admin navigation, routes and permissions
    - `/dashboard-widgets`, `/dashboard-widgets/new`, `/dashboard-widgets/:id` in `router.tsx`,
      `route-permissions.ts`, `NAV_CONFIG` System section item; export `route-whitelist.json`.
    - _Requirements: 4.3_
  - [x] 6.7 Admin list, form and builder
    - `DashboardWidgetsPage`, `DashboardWidgetFormPage`, structured builder (dataset, fields by
      capability, filters, dimensions, measures, sort, limit, links, presentation), advanced JSON view,
      roles-visibility badge, activate/deactivate/delete with in-use handling; default style and
      default colors controls with a "show all styles" preview.
    - _Requirements: 4.4–4.8, 5.9, 11.1, 11.4_
  - [x] 6.8 Admin live preview
    - `WidgetPreview` using the real renderers at the widget's span, debounced
      `preview`/`validate` calls, inline violations, "as administrator" label.
    - _Requirements: 11.2, 11.3, 8.7_
  - [x] 6.9 Admin AI assistant panel
    - `AiAssistantPanel`: prompt, generate, show explanation and warnings, apply draft to the form
      (never auto-save), disabled/failed states.
    - _Requirements: 13.1, 13.6, 13.7_
  - [x] 6.11 User appearance (palette and styles)
    - `lib/color.ts` (theme-derived palette, hex validation, `ensureContrast`), `useChartPalette`
      (custom palette → theme palette → config colors → default; reacts to theme changes),
      appearance dialog (palette editor, reset, "as theme"), per-tile style selector, API hooks for
      `/api/dashboard/appearance`, wiring into tiles.
    - _Requirements: 5.7, 5.8, 5.10, 5.11_
  - [x] 6.10 Localization (RU/PL)
    - Add all `dashboard.*`, `admin.dashboardWidgets.*`, `nav.dashboardWidgets` and
      `notification.dashboard.*` keys from the design to `ru.json` and `pl.json`, including error codes
      and enum labels.
    - _Requirements: 16.1, 16.3, 17.3_

- [x] 7. Tests (optional, post-implementation hardening)
  - [x] 7.1 Backend integration: engine, scoping and access
    - Testcontainers tests per dataset: project scoping, ADMIN handling, WORKER owner restriction,
      null-project rule, date range, truncation; access matrix changes flip state; fetch/list
      agreement; foreign placement 403.
    - _Requirements: 6.3, 8.1–8.5, 9.5_
  - [x] 7.2 Backend integration: placements, defaults and notifications
    - Concurrent adds, overlap and duplicate rules, project widget copies, deactivation fan-out,
      backfill idempotency, new-user seeding on every creation path, lazy seeding.
    - _Requirements: 3.3–3.7, 12.3, 15.2–15.4, 17.2_
  - [x] 7.3 Frontend tests
    - Date filter and session storage, layout at three breakpoints, picker disabled state, delete
      confirmation, link with/without permission, renderers with fixtures, admin builder validation.
    - _Requirements: 1, 2, 3, 9, 10_
  - [x] 7.4 AI client tests
    - Stub and fake-transport tests: valid draft, invalid-then-valid retry, invalid twice, provider
      error, disabled, rate limit.
    - _Requirements: 13.3, 13.6, 13.8_

- [x] 8. Contract checks
  - [x] 8.1 Route whitelist contract test
    - Backend test comparing `RouteWhitelist` with the frontend-exported `route-whitelist.json`.
    - _Requirements: 10.2_
  - [x] 8.2 Extend the entity-creation steering
    - Step 5 "Provide the entity's Dataset to the widget registry" added to
      `.kiro/steering/entity-creation-rules.md` (done with this spec revision). At implementation,
      keep it consistent with the final package and test names (`DatasetRegistry`,
      `DatasetCoverageTest`, `SensitiveFieldGuardTest`, `DatasetCoverage` exclusion list).
    - _Requirements: 6.2e_

- [x] 9. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression group,
    MD report template). This spec has browser screens and API endpoints: UI scenarios are
    browser-engine scenarios; API scenarios run against the Dockerized app. Result artifacts are MD
    reports with tables. Written in Russian.
  - _Requirements: 1–17_

- [x] 10. Final checkpoint
  - Run the affected test classes only (per the test-execution rules), frontend lint/type-check/tests,
    verify both repos with `git status`, and confirm every requirement is covered.

- [x] 11. Amendment A1: Layout UX, backend (Requirements 19-22; root repo)
  - [x] 11.1 GridMath additions and `classify`-based add
    - Add to `GridMath`: `Placement {OK, INVALID, OCCUPIED}`, `Fit`, `isFree`, `freeCells`, `classify`,
      `maximalFits`, `fitSize` (largest area, tie: wider), `fitsInAny`, `lastOccupiedRow`,
      `visibleRows` (see design A1.2). Refactor `DashboardLayoutService.addPlacement` to call
      `classify`; behavior and error codes stay unchanged.
    - _Requirements: 19.3, 20.2, 20.10, 21.1, 21.8, 21.9, 21.10, 21.11_
  - [x] 11.2 Shared grid test vectors and backend contract test
    - Generate `foremen-backend/src/test/resources/dashboard/grid-vectors.json` from the Java
      reference and review it by hand (empty grid, full grid, single tile, holes in a row, tall thin
      gaps, edge cells 3/32/35, cell outside 0..35, oversized spans, the 2x1-fits-while-Fit_Size-is-1xN
      case). Add `GridVectorsContractTest` asserting `occupiedCells`, `freeCells`, `lastOccupiedRow`,
      `visibleRows`, `maximalFits`, `fitSize`, `classify`.
    - _Requirements: 21.10_
  - [x] 11.3 Move and swap endpoints
    - `DashboardLayoutService.movePlacement` and `swapPlacements` (per-user advisory lock first;
      missing and foreign both 403 `error.dashboard.forbidden`; idempotent move to the current cell
      without write; 400 `cell.invalid` / 409 `cell.occupied` through `classify`; swap checks both
      footprints against others and against each other, both or neither persisted; no Has_Access and
      no active check). `DashboardController`: `PUT /api/dashboard/items/{id}/move` and
      `POST /api/dashboard/items/swap` with `MoveItemRequest`, `SwapItemsRequest`, `SwapResponse`
      (null body fields give 400 `error.dashboard.cell.invalid`). Self-service: no
      `@PermissionResource`.
    - _Requirements: 19.7, 19.8, 19.9, 19.10, 19.13, 19.14, 19.18_
  - [x] 11.4 Library `ThumbnailShape`
    - `ThumbnailShape` record, `ThumbnailShapeFactory.from(DashboardWidgetEntity)` built from the
      stored config only, new last component `thumbnail` of `LibraryItem`, filled in
      `DashboardLibraryService.toItem` for every entry regardless of access; no strings other than
      `visualization` and `style`, no dataset lookup. `PlacementView` unchanged.
    - _Requirements: 22.1, 22.2_
  - [x] 11.5 Write property tests for GridMath
    - Extend `GridMathPropertyTest` (jqwik, 100+ tries) with a brute-force reference.
    - **Property 15: Placement classification is consistent between backend and frontend (backend side)**
    - **Property 20: Free cells are disjoint from footprints and Add_Slots follow the visible rows**
    - **Property 21: Fit_Size and maximal fits are correct**
    - **Validates: Requirements 19.3, 20.1, 20.2, 20.5, 20.10, 21.1, 21.2, 21.8, 21.11**
  - [x] 11.6 Write service tests for move and swap
    - `DashboardLayoutServiceTest` with the existing DAO fakes: move OK / 400 / 409 / foreign and
      missing give identical 403 / idempotent no-write / widget and project id untouched /
      `NO_ACCESS`, `INACTIVE`, `PROJECT_UNAVAILABLE` placements movable; swap OK / 400 / 409 /
      same id / mutual overlap / all-or-nothing. Sequence properties:
    - **Property 16: Moves and swaps never create overlaps**
    - **Property 17: Move round trip and idempotence**
    - **Property 18: Moves change only the cell**
    - **Validates: Requirements 19.7, 19.8, 19.9, 19.10, 19.13, 19.14, 19.17**
  - [x] 11.7 Write Testcontainers integration test for move and swap
    - Real HTTP: move and swap happy paths, foreign vs missing id give the same 403, swap rollback,
      two concurrent moves/swaps/adds into the same cells give exactly one success and one 409
      `error.dashboard.cell.occupied`, add with out-of-grid or occupied `cellIndex` gives 400/409
      without fallback.
    - _Requirements: 19.7, 19.8, 19.10, 19.13, 19.14, 19.18, 21.8, 21.9_
  - [x] 11.8 Write ThumbnailShape and message-bundle tests
    - `ThumbnailShapeTest`: exact key set, no names/labels/filters/dataset code/config value in the
      serialized JSON, identical shape for `available = true` and `false`, malformed config gives
      zeros/false. Assert `error.dashboard.cell.invalid`, `.cell.occupied`, `.forbidden` exist in both
      `messages.properties` (PL) and `messages_ru.properties` (RU).
    - **Property 23: Thumbnail shape exposes only the whitelisted structure**
    - **Validates: Requirements 22.1, 22.2, 22.13**

- [x] 12. Amendment A1: Layout UX, frontend foundation (Requirements 19-22; foremen-frontend repo)
  - [x] 12.1 `lib/grid.ts` mirror
    - Add `isFree`, `freeCells`, `classifyPlacement`, `maximalFits`, `fitSize`, `fitsInAny`,
      `lastOccupiedRow`, `visibleRows`, `occupiedExcluding`, `dropTarget`, `canSwap`, `neighbor`,
      `stepTarget`, `applyMove`, `applySwap`, `restoreCells`, with the same names and semantics as
      `GridMath` (cells as index lists, no 32-bit bitwise on 36 cells). `firstFreeCell` stays.
    - _Requirements: 19.3, 19.11, 19.12, 19.13, 20.2, 20.10, 21.1, 21.10, 21.11_
  - [x] 12.2 Write property tests for `grid.ts`
    - vitest + fast-check (100+ runs) against a brute-force reference.
    - **Property 15: Placement classification is consistent between backend and frontend (drop target validity)**
    - **Property 19: Keyboard targets stay in the grid**
    - **Property 20: Free cells are disjoint from footprints and Add_Slots follow the visible rows**
    - **Property 21: Fit_Size and maximal fits are correct**
    - **Validates: Requirements 19.3, 19.11, 20.1, 20.2, 20.5, 20.10, 21.1, 21.2, 21.11**
  - [x] 12.3 Frontend contract test against the shared vectors
    - `grid-vectors.contract.test.ts` locating `grid-vectors.json` by walking up from
      `process.cwd()` (like the route-whitelist contract), `describe.skipIf` when absent; asserts
      `freeCells`, `lastOccupiedRow`, `visibleRows`, `maximalFits`, `fitSize`, `classifyPlacement`.
    - _Requirements: 21.10_
  - [x] 12.4 Types, API client and optimistic mutation hooks
    - `ThumbnailShape` in `types/dashboard.ts` and `LibraryItem.thumbnail`; `moveDashboardItem` and
      `swapDashboardItems` in `dashboard-api.ts`; `useMoveDashboardItem` and `useSwapDashboardItems`
      in `api/mutation-hooks.ts` with `onMutate` (cancel queries, `applyMove`/`applySwap` on the
      `dashboardKeys.layout()` cache), `onError` (`restoreCells` for the affected ids only) and
      `onSettled` (invalidate layout). Widget data queries are not refetched.
    - _Requirements: 19.4, 19.6, 19.14, 22.1_
  - [x] 12.5 Write tests for the mutation hooks
    - Optimistic update within one render, rollback restores only the affected placements, error
      surfaces as localized message, swap rollback restores both.
    - _Requirements: 19.4, 19.6, 19.14_

- [x] 13. Amendment A1: Layout UX, frontend UI (Requirements 19-22; foremen-frontend repo)
  - [x] 13.1 Add the drag-and-drop dependency (needs user approval)
    - Add `@dnd-kit/core` at an exact pinned version (no caret; 6.3.1, confirm at install). It is a
      new dependency: obtain user approval before installing, as was done for `recharts`.
      `@dnd-kit/sortable` and `utilities` are not used.
    - _Requirements: 19.1_
  - [x] 13.2 Localization (RU/PL)
    - Add all keys of design A1.5 to `ru.json` and `pl.json` (`dashboard.move.*`,
      `dashboard.addSlot.*`, `dashboard.picker.position|fitsUpTo|showAll|doesNotFit|noFit`,
      `dashboard.thumbnail.*`). Add a parity test that `ru.json` and `pl.json` have identical
      `dashboard.*` key sets.
    - _Requirements: 22.13, 19.15, 20.6, 21.2, 21.4, 21.7_
  - [x] 13.3 Add_Slots, visible rows and grid-full hint
    - `AddSlot` component (dashed neutral button, `data-testid="dashboard-add-slot-{cell}"`,
      `aria-label` with 1-based row/column and Fit_Size, no data fetch). In `DashboardGrid` on
      desktop render one `AddSlot` per `freeCells` cell in visible rows in ascending order, the
      full-width "show all free rows" control with local `showAllRows` state, and the
      `dashboard.addSlot.gridFull` hint when rows 0..8 have no free cell. `EmptyCellTile` stays as the
      single trailing "+" on tablet/mobile.
    - _Requirements: 20.1, 20.2, 20.3, 20.4, 20.5, 20.6, 20.7, 20.8, 20.10_
  - [x] 13.4 Drag and drop, keyboard move mode and tablet/mobile swap
    - `DashboardGrid` `interaction` state and `DndContext` (`PointerSensor` with distance 6, cell
      collision detection, `onDragCancel`); 36 `DropCell`s with valid/invalid highlight and footprint
      label during interaction; `onDragEnd` calls `onMove` only for a valid target different from the
      current cell. `TileActions` used by `WidgetTile` and `DisabledWidgetTile` (drag handle with
      listeners only on the handle, "Move" button; "Move earlier"/"Move later" on tablet/mobile via
      `neighbor` + `canSwap`, `aria-disabled` with `aria-describedby` reason and tooltip; 44 px touch
      targets). Keyboard move mode (`role="application"`, arrows via `stepTarget`, Enter, Escape,
      focus return) with `role="status"` `aria-live="polite"` announcements. Hide Add_Slots while
      `interaction !== idle`. Error banner `role="alert"` via `dashboardErrorMessage`.
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6, 19.10, 19.11, 19.12, 19.13, 19.14, 19.15, 19.16, 20.9_
  - [x] 13.5 Dashboard page wiring
    - `DashboardPage`: `pickerCell: number | null` replaces `freeCell`; header "Add widget" opens the
      picker with `null` (add without `cellIndex`), an Add_Slot opens it with its cell, the
      tablet/mobile trailing "+" opens it with `null`; wire move/swap mutations to `DashboardGrid`.
    - _Requirements: 19.4, 20.7, 21.5_
  - [x] 13.6 Move `buildSampleResult` to the dashboard feature
    - Move `buildSampleResult` and its constants from `features/dashboard-widgets/lib/builder.ts` to
      `features/dashboard/lib/sample-data.ts` taking a structural `{fields: {name; type}[]}`;
      `builder.ts` re-exports it so the builder and `StylePreview` keep working.
    - _Requirements: 22.4_
  - [x] 13.7 Thumbnail components
    - `lib/thumbnail-sample.ts#sampleFromShape`; `WidgetThumbnail` (`role="img"`, `aria-label` from
      `dashboard.thumbnail.aria`, aspect ratio colSpan:rowSpan in a max box, `inert` + `aria-hidden`
      inner markup, no links, user palette, default style, reduced opacity when disabled);
      `ThumbnailErrorBoundary` with visualization icon fallback (also for unsupported visualization or
      style); `LazyThumbnail` with `IntersectionObserver` (`rootMargin: '100px'`), same-size
      placeholder, mount in `requestAnimationFrame`, unmount when leaving the viewport, immediate
      mount without `IntersectionObserver`. Add `interactive={false}` to `WidgetRenderer` if its
      tooltips/animation cannot otherwise be silenced.
    - _Requirements: 22.3, 22.4, 22.5, 22.6, 22.7, 22.8, 22.9, 22.10, 22.11, 22.12_
  - [x] 13.8 Fit-aware picker with thumbnails
    - `WidgetPicker`: props `cellIndex`, `placements`, `showAll` toggle (off by default, reset on
      close). `fitOf` through `classifyPlacement` when a cell is given, `firstFit` otherwise;
      `listed = matchesSearch && (fits || showAll)`. Header with 1-based position and "fits up to"
      from `fitSize`; non-fitting entries only with the toggle, disabled, with the does-not-fit reason
      (plus the access warning when unavailable); "no widget fits here" message with Fit_Size and
      toggle; `choose()` always sends `cellIndex` when set. Render `LazyThumbnail` per row.
    - _Requirements: 21.2, 21.3, 21.4, 21.5, 21.6, 21.7, 21.9, 21.11, 21.12, 22.3, 22.5_
  - [x] 13.9 Update existing tests affected by the amendment
    - Rewrite the `WidgetPicker` tests that expect the `noSpace` warning from `firstFit` for the fit
      filter; update `DashboardComponents.test.tsx` cases that look for the single
      `dashboard-add-tile` on desktop to `dashboard-add-slot-{cell}`; keep tablet/mobile single "+"
      cases. Add `LibraryItem.thumbnail` to test fixtures.
    - _Requirements: 20.1, 20.7, 21.3, 21.4_
  - [x] 13.10 Write component tests
    - Drag cancel (Escape, invalid drop, outside grid, same cell) makes no backend call; valid drop
      sends one move; optimistic update and rollback with banner; keyboard mode with `aria-live`;
      tablet/mobile buttons with disabled reasons; Add_Slot rendering, ascending focus order and
      "show all free rows"; grid-full hint; Add_Slots hidden during interaction; a click on a linked
      element does not start a drag; picker fit filter + search AND, show-all reasons, no-fit message;
      `WidgetThumbnail` (mocked `IntersectionObserver` lazy mount/unmount, fallback icon,
      `aria-label`, `inert`, no data endpoint call).
    - **Property 22: Picker filter matches backend acceptance**
    - **Validates: Requirements 19.4, 19.5, 19.6, 19.11, 19.15, 19.16, 20.3, 20.6, 20.8, 20.9, 21.3, 21.4, 21.7, 21.11, 21.12, 22.4, 22.6, 22.8, 22.10, 22.12**

- [x] 14. Author test-cases.md (Amendment A1)
  - Update `test-cases.md` in the spec folder (Russian, per `.kiro/steering/test-cases.md`): add UI
    scenarios (browser engine) and API scenarios (Dockerized app) for Requirements 19-22: drag and
    drop incl. cancel and invalid drops, keyboard move mode, tablet/mobile move earlier/later,
    move/swap API (400/409/403, idempotency, state-independent movability, concurrency), Add_Slots on
    all free cells and "show all free rows", grid-full hint, Fit_Size and fit-aware picker with "show
    all" and reasons, thumbnails and the library `thumbnail` shape (no leaks). Specify the
    repeatability strategy (run-id generator and teardown) and extend the regression group with the add
    flow (header "Add widget", Add_Slot, tablet "+", first-fit without `cellIndex`) and the MD report
    template.
  - _Requirements: 19.1–19.18, 20.1–20.10, 21.1–21.12, 22.1–22.13_

- [x] 15. Final checkpoint (Amendment A1)
  - Run only the affected backend test classes (per the test-execution rules, log in
    `/tmp/for-10-test.log`, read the JUnit XML), frontend lint, type-check and the affected vitest
    files, verify both repos with `git status`, and confirm Requirements 19-22 are covered.
    Ensure all tests pass, ask the user if questions arise.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "2.1", "2.2", "5.1", "6.1", "6.10", "8.2"] },
    { "id": 1, "tasks": ["1.2", "2.3", "2.5", "4.1", "6.2", "6.3", "6.6"] },
    { "id": 2, "tasks": ["2.4.1", "2.4.2", "2.4.3", "2.4.4", "2.4.5", "3.3", "5.2", "6.4", "6.7", "8.1"] },
    { "id": 3, "tasks": ["2.6", "3.1", "6.5", "6.8", "6.9", "7.4"] },
    { "id": 4, "tasks": ["2.7", "2.8", "3.2", "7.1"] },
    { "id": 5, "tasks": ["5.3", "6.11", "7.2"] },
    { "id": 6, "tasks": ["7.3"] },
    { "id": 7, "tasks": ["9"] },
    { "id": 8, "tasks": ["10"] },
    { "id": 9, "tasks": ["11.1", "11.4", "12.1", "13.1", "13.2", "13.6"] },
    { "id": 10, "tasks": ["11.2", "11.3", "11.5", "11.8", "12.2", "12.4", "13.3"] },
    { "id": 11, "tasks": ["11.6", "11.7", "12.3", "12.5", "13.4", "13.7"] },
    { "id": 12, "tasks": ["13.5", "13.8"] },
    { "id": 13, "tasks": ["13.9"] },
    { "id": 14, "tasks": ["13.10"] },
    { "id": 15, "tasks": ["14"] },
    { "id": 16, "tasks": ["15"] }
  ]
}
```
