# Bug report: FOR-11-00-procurement-foundation

Screen URL: http://localhost:3000/ (dashboard widgets of FOR-11-00 R24)

## Round 1 (2026-11-16)

Source: e2e run of `foremen-qa-auto/e2e/tests/for11/00-procurement-foundation` (74 passed, 3 skipped as
bugs, 1 environment skip). Evidence and repro details: `tests/for11/00-procurement-foundation/FINDINGS.md`.
The planner "Dodaj pozycję" bug found by this suite is owned by FOR-11-01 and tracked there (BUG-3).

- [x] 1. Dashboard widget links (ROW / KPI → `/deliveries`) never work
  - Triage: observed: clicking a row of "Najbliższe dostawy" (FOREMAN) or the KPI of "U kupca" (BUYER) does nothing, URL stays `/`; expected: navigation to `/deliveries` per the seeded `links`. Layer: both. Suspect: `GET /api/dashboard` returns placements without the widget config; `DashboardGrid`/`WidgetTile` accept an optional `configs` prop that no caller passes, so tiles render with a config without `links`/`presentation`. Affects every seeded widget with links (also FOR-10 R10).
  - Files: `foremen-backend/.../controller/DashboardController.java`, `service/dashboard/PlacementView`, `foremen-frontend/src/features/dashboard/components/DashboardGrid.tsx`, `WidgetTile.tsx`, `app/pages/DashboardPage.tsx`.
  - Test: `ui-dashboard.e2e.ts` TC-B5-01 (skipped as BUG-2 in FINDINGS).
  - _Requirements: R24.2; FOR-10 R10_
  - _Depends on: none_
- [x] 2. Widgets show the raw status `WITH_BUYER` instead of the localized stage name
  - Triage: observed: the status column of "Najbliższe dostawy" and the legend of "Zakupy wg etapów" show `WITH_BUYER` in PL and RU; expected: the stage label ("U kupca" / "У закупщика"). Layer: frontend. Suspect: `features/dashboard/renderers/shared.tsx` (`useCellFormatter` renders `format: STATUS` as plain text), pie/chart renderers.
  - Test: TC-B5-01 status check (skipped as BUG-3 in FINDINGS).
  - _Requirements: R24.2, R12_
  - _Depends on: 1_ (same renderers, presentation block comes with the config)
- [x] V1. Validate Round 1
  - Rebuild, remove `.skip` from the BUG tests of the folder, run `npx e2e run tests/for11/00-procurement-foundation`.
  - _Depends on: 1, 2_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1
- **Wave 2:** 2
- **Wave 3:** V1

## Round 1 — fixes and validation results (2026-11-16)

| Bug | Fix | Files |
|-----|-----|-------|
| 1 | `PlacementView` gets a `render` hint `{title, columns:[{field,format}], links, presentation}` for AVAILABLE placements only (null otherwise; never dataset/filters/measures, not named `config`, so FOR-10 TC-A9-13 still holds). `WidgetTile` builds its config from it, so links work; route permissions still apply. | `service/dashboard/PlacementView.java`, `DashboardLayoutService.java`; `features/dashboard/types/dashboard.ts`, `components/WidgetTile.tsx` |
| 2 | `useCellFormatter` translates `STATUS` values (and status-like `ENUM` columns) with the `purchasePlanner.stage.*` keys; tables, pie legend/tooltip and chart tables use it. | `features/dashboard/renderers/shared.tsx` |

Tests: `DashboardRenderHintsTest` (new), `DashboardControllerIntegrationTest`, `DashboardLayoutServiceTest`,
`DashboardPlacementsDefaultsNotificationsIntegrationTest`; vitest `status-labels.test.tsx` (new).
Validation: stack rebuilt; `npx e2e run tests/for10 tests/for11` — 505 passed, 0 failed after the toast-timeout
fix in FOR-11-02 tests, 9 skipped (environment / not automated); the BUG tests of
`tests/for11/00-procurement-foundation/ui-dashboard.e2e.ts` and `ui-i18n-bugs.e2e.ts` run unskipped and pass.
Note: the PL widget label for `WITH_BUYER` is now "U zakupowca" (planner dictionary) while a widget title says
"U kupca"; wording alignment is left to the product owner.
