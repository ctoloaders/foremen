# Bug report: FOR-10-dashboard

Screen URL: http://localhost:3000/ (Panel), API http://localhost:8080/api/dashboard/**

## Round 1 (2026-11-14)

Source: automated runs of `foremen-qa-auto/e2e/tests/for10/*` against the local Docker stack, cases from
`test-cases.md` and `additional-test-cases.md`. Every bug below is reproduced by a test marked
`test.skip` with its `BUG-N` id; remove `.skip` to verify a fix.

- [x] 1. An Add_Slot opens the picker for the first free cell instead of its own cell
  - Triage: observed: tapping the slot of cell 6 shows "Wiersz 1, kolumna 2 · Mieści się do 3×9" and the add request carries the first free cell; expected: position "Wiersz 2, kolumna 3", fit filter and `cellIndex: 6` for that slot. Layer: frontend. Suspect: `foremen-frontend/src/app/pages/DashboardPage.tsx` — `DashboardGrid` calls `onAdd(cell)`, but the page passes `onAdd={openPicker}`, which ignores the argument, and the picker always gets `cellIndex={freeCell}`.
  - Test: TC-DX-B01 (`additional-ui.e2e.ts`).
  - _Requirements: 20.1, 21.2, 21.5_
  - _Depends on: none_
- [x] 2. The header "Dodaj widżet" button opens the picker with a position and sends `cellIndex`
  - Triage: observed: the picker shows the first free cell's position and fit size, and filters by it, so a widget that fits lower on the grid (4x3 `ACTIVE_PROJECTS` when rows 0–1 are blocked) is not offered; expected: no position, first-fit filter, add request without `cellIndex`. Layer: frontend. Suspect: `DashboardPage.tsx` (`cellIndex={freeCell}` for every open), `WidgetPicker.tsx` (fit filter without a position).
  - Test: TC-DX-B02. Also TC-B7-18 in `test-cases.md`.
  - _Requirements: 21.5, 3.3_
  - _Depends on: 1_ (same file and the same open-picker state)
- [x] 3. Full grid: the header picker says "no widget fits here" instead of "no space"
  - Triage: observed: "Żaden widżet nie mieści się w tym miejscu" (`dashboard.picker.noFit`, meant for a chosen cell); expected: "Brak wolnego miejsca" (`dashboard.picker.noSpace`). The page-level "Siatka jest pełna" hint is correct. Layer: frontend. Suspect: `WidgetPicker.tsx` empty-list branch (`hiddenByFit` with no cell), `DashboardPage.tsx`.
  - Test: TC-DX-B03. Also TC-B7-12 in `test-cases.md`.
  - _Requirements: 3.3, 20.8_
  - _Depends on: 2_
- [x] 4. After a project widget is added, the library reopens on the project step
  - Triage: observed: after "Karta projektu" is added for a project, the next "Dodaj widżet" (header or slot) shows "Wybierz projekt" instead of the widget list until the page is reloaded; expected: the widget list. Layer: frontend. Suspect: `WidgetPicker.tsx` — `projectFor`, `search` and `showAll` are reset only in its own `onOpenChange(false)`; `DashboardPage.handleChoose` closes the dialog with `setPickerOpen(false)`, so the reset never runs. Reset the state when `open` becomes true (or false) instead.
  - Test: TC-DX-B04; `project-widget.e2e.ts` reloads the page to work around it.
  - _Requirements: 3.4, 12_
  - _Depends on: 3_ (same file)
- [x] 5. In the PL UI the tile style selector is named in Russian
  - Triage: observed: `aria-label="Styl: Получено платежей"`; expected: the name in the active locale ("Styl: Otrzymane płatności"). Layer: frontend. Suspect: `foremen-frontend/src/features/dashboard/components/StyleSelect.tsx` line 18 uses `placement.nameRu || placement.code`; use the locale-aware placement name helper (`placementName(placement, i18n.language)`).
  - Test: TC-DX-B06.
  - _Requirements: 16.3, 16.4_
  - _Depends on: none_
- [x] 6. A failed remove shows its error behind the modal dialog, worded as a load failure
  - Triage: observed: on a 500 from `DELETE /api/dashboard/items/{id}` the dialog stays open, the error is rendered under the grid behind the dialog overlay (`dashboard-remove-error`) and reads "Nie udało się załadować danych" (the generic data-load fallback); expected: the message inside the dialog, worded as a remove failure. Layer: frontend. Suspect: `DashboardPage.tsx` (error paragraph outside `RemoveWidgetDialog`), `features/dashboard/lib/errors.ts` (fallback `dashboard.widget.error` used for every action; needs a remove-specific fallback key in `pl.json`/`ru.json`).
  - Test: TC-DX-B08.
  - _Requirements: 3.5, 16.4_
  - _Depends on: 3_ (same file `DashboardPage.tsx`)
- [x] 7. Malformed input returns HTTP 500 instead of 400 (app-wide)
  - Triage: observed: 500 for `dateFrom=2026-13-01` / `abc`, a non-numeric path id (`/items/abc/data`), wrong JSON types (`"cellIndex":"abc"`, swap ids as strings) and truncated JSON; the same 500 on `/api/projects/abc`, `/api/users/abc` and a truncated `POST /api/roles`. Backend logs show `ConversionFailedException`/`DateTimeParseException`, `MethodArgumentTypeMismatchException` and `HttpMessageNotReadableException` reaching the generic handler. Expected: 400 with a localized message (for dates, `error.dashboard.date.invalid`). Layer: backend. Suspect: `foremen-backend/src/main/java/com/foremen/controller/advice/ForemenControllerAdvice.java` — no handlers for `HttpMessageNotReadableException` and `MethodArgumentTypeMismatchException`, so the `RuntimeException` handler answers 500.
  - Scope note: the root cause is cross-cutting (not owned by FOR-10). Recorded here because FOR-10 tests found it; the fix may belong to the backend/API-protection spec. User to decide where to track it.
  - Test: TC-DX-A01, TC-DX-A02, TC-DX-A03 (`additional-api.e2e.ts`).
  - _Requirements: 2.5, 16.2_
  - _Depends on: none_
- [x] 8. Adding without `widgetId` reports "widget not found" instead of a validation error
  - Triage: observed: `POST /api/dashboard/items {}` → 400 "Nie znaleziono widżetu." (`error.dashboard.widget.notFound`); expected: a field validation error that `widgetId` is required. Layer: backend. Suspect: `DashboardController.add` (lines 100–103) throws `widget.notFound` for a null id; use `@Valid` with `@NotNull` on `AddItemRequest.widgetId`.
  - Test: TC-DX-A04.
  - _Requirements: 3_
  - _Depends on: none_
- [x] 9. The date range of one user carries over to the next user in the same tab (needs a product decision)
  - Triage: observed: user A picks "Poprzedni", logs out, user B logs in in the same tab and sees A's range; expected (proposed): B starts with the current month. Requirement 2.4 ties the range to the browser session, not to the user, so this may be accepted behaviour. Layer: frontend. Suspect: `stores/auth-store.ts` `logout()` does not remove `dashboard.dateFilter` from `sessionStorage` (`features/dashboard/components/useDateFilter.ts`).
  - Test: TC-DX-B12.
  - _Requirements: 2.4_
  - _Depends on: none_
- [x] V1. Validate Round 1
  - Rebuild the changed services, remove `.skip` from the tests listed above, run `npx e2e run tests/for10` in `foremen-qa-auto/e2e`, and check each fixed bug in the browser at http://localhost:3000/.
  - _Depends on: 1, 2, 3, 4, 5, 6, 7, 8, 9_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 5, 7, 8, 9 — different files (`DashboardPage.tsx`, `StyleSelect.tsx`, `ForemenControllerAdvice.java`, `DashboardController.java`, `auth-store.ts`).
- **Wave 2 (parallel):** 2 — depends on 1 (same file and picker-open state).
- **Wave 3:** 3 — depends on 2 (picker messages; `WidgetPicker.tsx`, `DashboardPage.tsx`).
- **Wave 4 (parallel):** 4, 6 — 4 depends on 3 (`WidgetPicker.tsx`); 6 depends on 3 (`DashboardPage.tsx`, `errors.ts`). Different files.
- **Wave 5:** V1 — validation, depends on all fixes.

## Round 1 — fixes and validation results (2026-11-14)

All nine bugs were approved for fixing (including 7, tracked here although its cause is app-wide, and 9,
accepted as a bug: the range is reset on logout).

| Bug | Fix | Files |
|-----|-----|-------|
| 1 | The page keeps the cell the picker was opened for; an Add_Slot passes its own cell. | `foremen-frontend/src/app/pages/DashboardPage.tsx` |
| 2 | The header button opens the picker with `cellIndex = null` (no position, first-fit filter, add request without `cellIndex`). | `DashboardPage.tsx` |
| 3 | Without a position and with nothing fitting, the picker shows `dashboard.picker.noSpace` (`dashboard-picker-no-space`); `noFit` stays for a chosen cell. | `foremen-frontend/src/features/dashboard/components/WidgetPicker.tsx` |
| 4 | The picker is remounted on every open (`key` = open counter), so the project step, search and show-all never leak into the next open. | `DashboardPage.tsx` |
| 5 | The style selector name uses `placementName(placement, i18n.language)`. | `.../components/StyleSelect.tsx` |
| 6 | The remove error is rendered inside the dialog; it falls back to the new key `dashboard.remove.error` (PL/RU) instead of the data-load text. `dashboardErrorMessage` takes an optional fallback key. | `.../components/RemoveWidgetDialog.tsx`, `DashboardPage.tsx`, `.../lib/errors.ts`, `locales/pl.json`, `locales/ru.json` |
| 7 | New handlers: `HttpMessageNotReadableException` → 400 `error.request.malformed`; `MethodArgumentTypeMismatchException` → 400 `error.request.parameter.invalid` with the parameter under `fieldErrors`. Messages added to PL/RU bundles. Applies to every endpoint. | `foremen-backend/.../controller/advice/ForemenControllerAdvice.java`, `messages.properties`, `messages_ru.properties` |
| 8 | A missing `widgetId` throws `FieldValidationException` → 400 `error.validation` with `fieldErrors.widgetId = error.field.required`. | `foremen-backend/.../controller/DashboardController.java`, message bundles |
| 9 | `logout()` removes `dashboard.dateFilter` from `sessionStorage`. | `foremen-frontend/src/stores/auth-store.ts` |

Updated unit tests that encoded the old behaviour:
`DashboardComponents.test.tsx` (no cell + nothing fits → `dashboard-picker-no-space`) and
`app/pages/__tests__/DashboardPage.test.tsx` (header → `cellIndex` null; Add_Slot → its own cell).

### Verification

- Frontend: `tsc -b` clean; `vitest --run src/features/dashboard src/locales src/stores src/app/pages`
  — 38 files, 548 tests passed; ESLint clean on the changed files.
- Backend: `compileJava compileTestJava` OK; `ForemenControllerAdviceIntegrationTest` (4/4) and
  `DashboardControllerIntegrationTest` (9/9) passed.
- Stack rebuilt: `docker compose up -d --build backend frontend`. API probes now return 400 for
  `dateFrom=2026-13-01`, `dateFrom=abc`, `/items/abc/data`, `"cellIndex":"abc"`, truncated JSON,
  swap ids as strings, and `{}` (with `fieldErrors.widgetId`).
- Browser (e2e runner, headless Chromium against http://localhost:3000): `npx e2e run` in
  `foremen-qa-auto/e2e` — **40 passed, 0 skipped**, including the previously failing TC-DX-A01..A04 and
  TC-DX-B01, B02, B03, B04, B06, B08, B12 (the `.skip` markers were removed). No test data left in the DB.

| Bug | Test | Result |
|-----|------|--------|
| 1 | TC-DX-B01 | ✅ slot of cell 6 → "Wiersz 2, kolumna 3", POST `cellIndex: 6`, placed @6 |
| 2 | TC-DX-B02 | ✅ no position, 4x3 widget offered, POST without `cellIndex`, placed @8 |
| 3 | TC-DX-B03 | ✅ "Brak wolnego miejsca" |
| 4 | TC-DX-B04 | ✅ widget list shown after a project widget was added, no reload |
| 5 | TC-DX-B06 | ✅ no Cyrillic in PL style-selector names |
| 6 | TC-DX-B08 | ✅ `role="alert"` inside the dialog, tile kept |
| 7 | TC-DX-A01..A03 | ✅ 400 |
| 8 | TC-DX-A04 | ✅ 400 with `fieldErrors.widgetId` |
| 9 | TC-DX-B12 | ✅ user B starts with the current month |
