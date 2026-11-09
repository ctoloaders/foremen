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
    { "id": 8, "tasks": ["10"] }
  ]
}
```
