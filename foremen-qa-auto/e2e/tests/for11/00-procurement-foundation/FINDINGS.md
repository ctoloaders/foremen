# FOR-11-00 procurement foundation: e2e findings

## 1. Summary

**74 passed (includes the shared `admin` login setup) / 4 skipped (3 as product bugs, 1 as environment) / 6 not automated or partial** (of 78 reported by the runner; 9 test files + `support.ts`).

Final run: `npx e2e run tests/for11/00-procurement-foundation` (from `foremen-qa-auto/e2e`) → **EXIT=0**, `Tests 74 passed | 4 skipped (78)`, 7 min 51 s. Run with the PATH/`NPM_CONFIG_USERCONFIG` from the task.
Repeatability: every entity carries the run-id (`dash+<run-id>-<tag>@example.com`, `FOR11 QA A/B/D <run-id>`, `QA-MAT-<run-id>-*`, sellers named/coded with the run-id) and is deleted in `finally` (`destroyWorld` in `support.ts`). After the final run psql shows 0 leftover projects, materials, sellers, requests and users of this suite (one earlier run was killed from outside mid-setup and left 4 users, which were removed by hand).

Files: `api-access`, `api-materials-sellers`, `api-positions`, `api-requests`, `api-dashboard`, `ui-sellers`, `ui-buyer`, `ui-seller-picker`, `ui-dashboard`, `ui-i18n-bugs` (`*.e2e.ts`) and `support.ts`.

## 2. Coverage

Create endpoints answer **200** (shared `AdminController`), not 201 as `test-cases.md` says; the tests accept 200/201 (see O-2). Error keys are asserted through the PL `message` text (the body has no key).

| TC | Test | Status |
|---|---|---|
| A1-01 | api-access: TC-A1-01 | pass |
| A1-02 | api-access: TC-A1-02 | pass |
| A1-03 | api-access: TC-A1-03 (real paths `/api/bank/connections`, `/api/payroll/company`, `/api/payments/projects/{id}/schedule`, `/api/estimates`, `/api/users`, `/api/projects`) | pass |
| A1-04 | api-access: TC-A1-04 | pass |
| (extra R9.9) | api-access: TC-X-01 foreign-project read/edit | pass |
| A2-01 | api-materials-sellers: TC-A2-01 | **skipped, ENV-1** (see section 4) |
| A2-02..A2-05 | api-materials-sellers | pass |
| A3-01..A3-06 | api-materials-sellers | pass (registry branch REGISTRY not testable: disabled by design) |
| A4-01..A4-05 | api-positions | pass |
| A5-01..A5-05 | api-positions | pass |
| A6-01, A6-02 | api-positions | pass |
| A7-01..A7-05 | api-requests (A7-03 checks the `notifications` table via psql) | pass |
| A8-01, A8-02 | not automated: need the stack started with `FOREMEN_PROCUREMENT_OVERDUE_SWEEP_CRON` and a restart is forbidden | not automated |
| A9-01, A9-02 | api-access | pass |
| A9-03 | api-access: TC-A9-03 | partial: liquibase is not re-run (no restart); checks no duplicate role/resource/stage rows |
| A10-01..A10-05 | api-materials-sellers | pass (A10-03 asserts `fieldErrors.name` = raw key, see O-1) |
| A11-01..A11-06 | api-positions | pass |
| A12-01, A12-02 | api-positions (system stages seeded by SQL) | pass |
| A12-03 | api-requests | pass |
| A13-01..A13-04 | api-requests | pass |
| A14-01..A14-04 | api-dashboard (A14-03 "existing users unchanged" step not checked) | pass / A14-03 partial |
| B1-01..B1-05 | ui-sellers | pass (the transient "Szukam…" state of B1-02 is not asserted, timing) |
| B2-01, B2-02, B2-03 | ui-buyer | pass |
| B3-01 | ui-i18n-bugs | pass (seller form + `notification.request.assigned` in the bell, PL then RU; the `overdue` title needs the cron) |
| B4-01..B4-04 | ui-seller-picker, reached through the planner row "Akcje > Edytuj" sheet | pass |
| B4-00 (planner "Dodaj pozycję") | ui-i18n-bugs | **BUG-1** (skipped) |
| B4-05 | not automated in the UI: FOREMAN has no planner row actions; the grant is covered by API A10-05 | not automated |
| B5-01 | ui-dashboard: data row, project, quantity | pass; click → **BUG-2**, status text → **BUG-3** (skipped) |
| B5-02 | ui-dashboard | pass |
| B5-03 | not automated in the UI (library add); API A14-01 covers availability | not automated |
| B5-04 | ui-dashboard (PL → RU titles) | pass |
| R-01..R-07 | not automated (regression of other screens; the tests only touch them indirectly: R-03 via A9-01, R-07 via A9-02/A10-05) | not automated |

## 3. Bugs

### BUG-1: planner button "Dodaj pozycję" does nothing; no way to create a position in the UI
- **Screen:** `/deliveries?project=<id>` (planner tab), as MANAGER.
- **Repro:** open the planner, pick a project, click "Dodaj pozycję".
- **Observed:** nothing happens (no dialog, no request). **Expected:** the create-position form opens (it hosts `MaterialPicker` with the `SellerPicker` of FOR-11-00 R17.8/R20.6; FOR-11-01 R14.1).
- **Evidence:** test `TC-B4-00 (planner)` in `ui-i18n-bugs.e2e.ts` (skipped); the same click is a no-op in the trace of the exploring run.
- **Layer:** frontend. **Files:** `foremen-frontend/src/features/purchase-planner/PurchasePlannerPage.tsx` renders `<PlannerTab state update hideDelivered />` without `onAddPosition`; `PositionFormSheet` is mounted only in `components/RowActionsMenu.tsx` (edit mode), create mode is never rendered.
- **Requirement:** FOR-11-01 R14.1 (and FOR-11-00 R17.8/R20.6 for the picker host). **Feature-sized:** no, small wiring fix.

### BUG-2: dashboard widget links (ROW / KPI → `/deliveries`) never work
- **Screen:** `/` (dashboard) as FOREMAN (`UPCOMING_DELIVERIES`), BUYER (`POSITIONS_WITH_BUYER`).
- **Repro:** open the dashboard, click a row of "Najbliższe dostawy" or the KPI "U kupca".
- **Observed:** nothing happens, URL stays `/`, the row has no link. **Expected:** navigation to `/deliveries` (seed config has `links: [{route:/deliveries, element: ROW|KPI}]`).
- **Evidence:** test `TC-B5-01 (click)` in `ui-dashboard.e2e.ts` (skipped); `dashboard_widgets.config` of the three codes holds the links.
- **Layer:** both. `DashboardGrid` takes an optional `configs` prop and `WidgetTile` falls back to a config with no `links`/`presentation`; no caller passes `configs` (`grep configs= src` is empty) and `GET /api/dashboard` returns placements without the widget config. It affects every seeded widget with links, not only the FOR-11 ones.
- **Files:** `foremen-frontend/src/features/dashboard/components/DashboardGrid.tsx`, `WidgetTile.tsx`, `renderers/TableRenderer.tsx` (`linkFor(config, 'ROW')`), backend layout DTO of `DashboardController#layout`.
- **Requirement:** R24.2 ("ROW link to /deliveries", KPI link), TC-B5-01. **Feature-sized:** small to medium (return config with the layout, pass it down).

### BUG-3: widgets show the raw status enum instead of the localized stage name
- **Screen:** `/` as FOREMAN/BUYER: "Najbliższe dostawy" status column shows `WITH_BUYER`; "Zakupy wg etapów" legend/rows show `WITH_BUYER`.
- **Observed:** `WITH_BUYER` in PL and RU. **Expected:** the stage label ("U zakupowca" / "У закупщика") from the delivery-status dictionary (R12).
- **Evidence:** test `TC-B5-01 (status)` (skipped); trace rows: `... 19 lis 2026 WITH_BUYER`.
- **Layer:** frontend (column `format: STATUS` is rendered as plain text; no stage translation; likely also the missing config of BUG-2 for the `presentation` block). **Files:** `features/dashboard/renderers/shared.tsx` (`useCellFormatter`), `PieRenderer`/`chart-shared.tsx`.
- **Requirement:** R24.2 (status column), R12. **Feature-sized:** no.

### Observations (not skipped tests)
- **O-1:** `POST /api/material-sellers/quick` with an empty name returns `fieldErrors.name = "error.materialSeller.quick.nameRequired"` (raw key, not localized) because `ForemenControllerAdvice#handleValidationException` does not resolve message keys. The UI validates before sending, so users do not see it; `test-cases.md` expects the key literally.
- **O-2:** create endpoints (`AdminController#create`) answer 200, test-cases.md says 201 (documentation mismatch only).
- **O-3:** the widget library returns the three procurement widgets to WORKER/CLIENT with `available=false` instead of omitting them (R24.5 "SHALL NOT see"); the test accepts the FOR-10 rule (`available=false`).
- **O-4:** `UPCOMING_DELIVERIES` table seed shows `ID projektu` plus both `Jedn. (RU)` and `Jedn. (PL)` columns (R24.2 lists project, material, quantity + unit, date, status).
- **O-5:** `UpcomingDeliveryDto` has `materialModel` + `producer`; test-cases.md names `materialLabel`.

## 4. Blocked / environment

- **ENV-1 (blocks A2-01):** the local DB has `finishing_materials.package_volume NOT NULL` (changesets 124/125, from branch `feat/FOR-05-05a-consumption-improvements`) that the checked-out `main` entity does not map. Every finishing/project material insert through the API therefore fails with `409 error.data.integrity`. `support.ts#seedProjectMaterial` tries the API and falls back to a SQL insert (copying `package_volume` from a global material), so all other tests run; `TC-A2-01` checks the API and skips itself on that 409. On a clean DB it runs as a normal test.
- The shared `.e2e/results` directory is overwritten by parallel agents' runs, so traces had to be read right after a run.
- Nothing else blocked; no product code, containers or seeded data were changed.
