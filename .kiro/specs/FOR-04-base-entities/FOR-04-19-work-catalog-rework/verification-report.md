# FOR-04-19 — Final verification report

Date: 2026-11-08. Stack: local Docker (`docker compose up -d --build liquibase backend frontend`), admin `cto+1@loaders.dev`.
No data was modified during the browser walk (no Save clicked; the new consumption row was cancelled with Esc).

## 1. Fixes made during verification

| # | File | Problem | Fix |
|---|------|---------|-----|
| 1 | `work-catalog/components/FormulaValidationStatus.tsx` | Corrupted (duplicated fragments, syntax errors) | Rewritten |
| 2 | `WorkVolumeFormulaEditor.tsx`, `WorkPackageOverridesEditor.tsx` | Validator and status line existed but were NOT wired (task 4.3 incomplete) | Wired `validateFormula` + `FormulaValidationStatus` (`role="status"`), `aria-invalid`, Save disabled while invalid (empty volume formula stays valid = delete) |
| 3 | `data-table/utils/buildQueryString.ts` | TS2345 (`string \| undefined`) | Destructured single value |
| 4 | `FormulaInput.tsx` | TS2339 (`never` after type guard) | Reordered narrowing |
| 5 | `WorkCatalogList.tsx` | Unused `_branch` param (eslint error) | Removed |
| 6 | tests (`WorkCatalogList`, `WorkItemEditPage`, `WorkPackageOverridesEditor`) | TS strict index typing | Non-null assertions / casts |
| 7 | `WorkVolumeFormulaEditor`/`WorkPackageOverridesEditor` tests | Used invalid formula `bad` for server-error mapping, now blocked client-side | Use valid `floorArea`; added test "Save disabled while invalid" |

## 2. Automated checks

| Check | Command | Result |
|-------|---------|--------|
| Backend compile | `./gradlew compileJava compileTestJava` (`/tmp/for-04-19-compile.log`) | ✅ BUILD SUCCESSFUL |
| WorkItemConsumptionCountQueryResolverTest | JUnit XML | ✅ 28 tests, 0 failures |
| WorkItemConsumptionCountsIntegrationTest | JUnit XML | ✅ 5 tests, 0 failures |
| Vitest `src/features/work-catalog` | `--run` | ✅ 11 files / 166 tests (after adding 1 test: volume editor file 9/9) |
| Vitest `src/config`, `i18n.keys`, `data-table`, `app/guards`, `features/assistant` | `--run` | ✅ 35 files / 408 tests |
| `tsc --noEmit -p tsconfig.app.json` | | ✅ 0 errors |
| ESLint `work-catalog`, `data-table`, `config` | | ✅ 0 errors in touched files; 3 warnings (fast-refresh, unused disable directive, `useMemo` deps). Pre-existing errors remain in untouched `data-table/filters/{Boolean,Date,Number,String}Filter.tsx` (`_field` unused) |

The `for-04-bugs.preservation` failure on `nav.rooms` (pre-existing at HEAD) was ignored per instructions and not run in these batches.

## 3. Docker / Liquibase

| Step | Expectation | Fact | Status |
|------|-------------|------|--------|
| Liquibase run | Changeset 218 applies | `218-add-wmc-work-item-branch-index` EXECUTED (1 run, 696 previously) | ✅ |
| Index | `ix_wmc_work_item_branch (work_item_id, branch)` | present on `work_material_consumptions` | ✅ |
| Backend | up | responds (401 unauthenticated on health probe) | ✅ |

## 4. Manual / browser walk (desktop 1280×800)

| # | Flow | Step | Expectation | Fact | Status |
|---|------|------|-------------|------|--------|
| 1 | API counts | `GET /api/work-items?size=5` | DTO has both counts | `constructionConsumptionCount`/`finishingConsumptionCount` present | ✅ |
| 2 | API filter | `constructionConsumptionCount>0` / `==0` | partition of all | 202 + 7 = 209 total | ✅ |
| 3 | API filter | `finishingConsumptionCount>5` | 400 | 400 | ✅ |
| 4 | List | open `/catalog/works` | two new count columns | "Стройматериалы (нормы)", "Отделочные материалы (нормы)" shown; 10 rows | ✅ |
| 5 | Redirects | `/catalog/prices`, `/catalog/material-consumption` | → `/catalog/works` | both redirect | ✅ |
| 6 | Row click | first row | `/catalog/works/:id` | `/catalog/works/4` | ✅ |
| 7 | Edit page | consumption section | present, branch filter, rows | `consumption-section` present, 5 rows, filter "Все/Стройматериалы/Отделочные" | ✅ |
| 8 | Create route | `/catalog/works/new` | form shown | form shown | ✅ |
| 9 | Formula | type `floorArea * (2 +` | problems list, Save disabled | "Несогласованные скобки" listed, Save disabled | ✅ |
| 10 | Formula | type `floorArea * (2 + 3)` | "Формула корректна", Save enabled | confirmed | ✅ |
| 11 | Formula | append ` +` | trailing operator error, Save disabled | "Формула не может заканчиваться оператором", disabled | ✅ |
| 12 | Formula | blur | known word becomes chip | 1 chip rendered | ✅ |
| 13 | Consumption | "Добавить норму" | new row, focus on first dropdown | row count 5→6, focus on "Тип материала" | ✅ |
| 14 | Consumption | Esc | cancel draft | 6→5 | ✅ |
| 15 | Unsaved guard | navigate with dirty formula | `beforeunload` prompt | dialog shown | ✅ |

## 5. Not verified / caveats

| Item | Reason |
|------|--------|
| Narrow viewport (390×844) | **Horizontal overflow detected** on the edit page (header action row / button groups, `flex gap-1`, `flex items-center gap-2`). Not fixed; needs a responsive follow-up (wrap header actions). |
| Per-role matrix (MANAGER/FOREMAN read-only) | Only ADMIN was exercised; read-only mode covered by unit tests only. |
| Saving flows (create/update work, price, consumption row save/delete, formula save) | Not executed in the browser to avoid mutating dev data; covered by vitest. |
| List UI filter dropdown (All / Has / None) and cost-range cell → `#consumption` | Not clicked in the browser; filter behaviour verified via API and `buildQueryString` unit tests. |
| Package override per-row validation | Verified by code and tests only, not in the browser. |
| Full Gradle suite / full vitest suite | Intentionally not run (policy). |
| Commit | Not performed, per instruction. |
