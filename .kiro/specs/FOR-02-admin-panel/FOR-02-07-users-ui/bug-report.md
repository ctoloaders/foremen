# Bug report: FOR-02-07-users-ui

Screen URL: https://dev.foremen.eu/users

Owning spec: `.kiro/specs/FOR-02-admin-panel/FOR-02-07-users-ui`. The `/users` route renders
`foremen-frontend/src/features/users/UsersPage.tsx` (`UsersPage`), with the create/edit form in
`UserFormSheet.tsx`. The list is powered by the shared DataTable
(`foremen-frontend/src/components/data-table/**`, FOR-02-06t). Sorting/filtering hit the backend
generic admin read path (`UserController` → `AdminController` → `ReadOnlyAdminService`).

> Scope note (triage finding): bugs 3 and 4 concern the country selector **embedded in the phone
> field** (`react-phone-number-input` inside `PhoneInput`). There is no separate standalone country
> dropdown on this screen today. Confirm with the user whether "country dropdown" means this phone
> country picker (treated as such below) or a brand-new dedicated country field.

## Round 1 (2026-10-28)

- [x] 1. Sorting by `roleName,asc` returns 500 Internal Server Error
  - Triage: observed `GET /api/users?...&sort=roleName,asc` → 500; expected a sorted page (users
    ordered by localized role name). Layer: **both**. Correct mechanism (per product owner): i18n
    sorting is locale-driven — the **UI must send the association path `role.name`** (the unsuffixed
    i18n key), and the **backend resolves `name` → `nameRU`/`namePL` according to the caller's active
    locale** via the existing `ReadOnlyAdminService.processSort` + `getMapper().getI18nSupportedProperties()`
    mechanism (same pattern the reference-filter already uses with `role.id`). Today it 500s because
    (a) the UI emits the derived display field `roleName`, which is not a persistent `UserEntity`
    property (it's a mapper expression), so Spring Data builds an invalid `ORDER BY roleName` path and
    throws `PropertyReferenceException`; and (b) for users `processSort` is a passthrough because
    `UserServiceMapper.getI18nSupportedProperties()` is empty and nothing maps a nested `role.name`
    key. Fix direction: (frontend) make the `roleName` column emit `role.name` as its sort field; and
    (backend) ensure `processSort` resolves the nested i18n key `role.name` to `role.nameRU`/`role.namePL`
    by the active locale (extend the nested-path i18n resolution already present for filters in
    `resolveI18nFilterField` to the sort path, and expose `name` as an i18n-supported property for the
    role association). Suspect: `foremen-backend/.../service/ReadOnlyAdminService.java` (processSort /
    resolveI18nSortProperty), `foremen-backend/.../service/model/mapper/UserServiceMapper.java`
    (i18n-supported properties / locale suffix), `UsersPage.tsx` roleName column (`sortField`).
  - _Requirements: 1.1, 1.4_
  - _Depends on: none_

- [x] 2. Shared table: "clear filter" button should appear whenever a search/filter yields no results, not only when the global search string is filled
  - Triage: observed the clear-filter affordance appears only when the global search `Input` has
    text; a column header filter that returns zero rows does not reliably surface a usable clear
    button. Expected: when the current result set is empty **because of any active search or column
    filter**, the user can clear all filters in one click; a genuinely empty table (no filters) must
    NOT show a misleading clear button. Layer: **frontend** (shared component). Root cause: the
    toolbar clear button is gated by `hasActiveFilters = state.filters.length > 0 || state.search !==
    ''` (`DataTableToolbar.tsx`), and the empty-state clear button renders only if `onClearFilters`
    is passed — `DataTable` passes it unconditionally on `data.length === 0`, so the empty-state
    button also shows for a non-filtered empty table (does nothing useful). Fix direction: make the
    empty-state "clear all" button appear exactly when there are active filters/search (`filters > 0
    || search !== ''`) and otherwise show a no-filter empty message; keep toolbar behavior
    consistent. Suspect: `foremen-frontend/src/components/data-table/DataTableEmpty.tsx`,
    `DataTable.tsx` (empty branch wiring of `onClearFilters`), `DataTableToolbar.tsx`.
  - _Requirements: 1.7_
  - _Depends on: none_

- [x] 3. User create/edit: make the phone country selector a searchable dropdown with localized country names (project-standard searchable select UX)
  - Triage: observed the country picker inside the phone field is `react-phone-number-input`'s native
    `<select>` — not type-to-filter searchable, and its option labels are the library's English
    country names. Expected: a searchable (type-to-filter) dropdown with **localized** (PL/RU)
    country names, matching the app's standard searchable select (the `RoleSelect` /
    `AsyncEntitySelect` combobox pattern). Layer: **frontend**. Fix direction: supply
    `react-phone-number-input` a custom `countrySelectComponent` built on the project combobox
    (Popover + search input) and feed it localized labels via the library `labels` /
    `getCountries()` + a PL/RU country-name source (e.g. `i18n-iso-countries` locale data or a
    curated i18n map). New user-facing strings must have PL and RU translations. Suspect:
    `foremen-frontend/src/features/users/components/PhoneInput.tsx`, shared select components
    (`components/ui/async-entity-select.tsx`, `features/users/components/RoleSelect.tsx` as the
    pattern), `src/locales/pl.json` + `ru.json`.
  - _Requirements: 2.2, 2.3_
  - _Depends on: none_

- [x] 4. Country flag renders as a broken image in the phone country selector
  - Triage: observed a broken-image icon where the country flag should be. Expected: the flag renders
    for every country option and the selected value. Root cause: `react-phone-number-input` has no
    `flagUrl`/`flags` override in `PhoneInput`, so it falls back to loading each flag from the
    external CDN `https://purecatamphetamine.github.io/country-flag-icons/3x2/{CC}.svg`, which is
    unreachable/blocked in the deployed environment (CSP `img-src` / network). Layer: **frontend**.
    Fix direction: serve flags locally instead of the remote CDN — install/bundle
    `country-flag-icons` and pass `react-phone-number-input` a `flagComponent`/`flags` (or a
    `flagUrl` pointing at a bundled asset path) so flags load from the app bundle, not an external
    host. Suspect: `foremen-frontend/src/features/users/components/PhoneInput.tsx`,
    `foremen-frontend/src/index.css` (flag CSS), `package.json`.
  - _Requirements: 2.2_
  - _Depends on: 3_

- [x] 5. Edit user: language (locale) dropdown is not prefilled from the stored value, leaving it empty and blocking save
  - Triage: observed on edit the locale `Select` shows the placeholder (empty) and, because the Zod
    schema requires `locale` (`z.enum(['ru','pl'])`, `required_error`), the form fails validation and
    cannot be saved. Expected: the locale dropdown is prefilled with the user's stored language so
    edit can be saved without re-selecting. Layer: **frontend** (likely; confirm the exact wire value
    during validation). The edit `reset` sets `locale: userData.locale as 'ru' | 'pl'`; the Select
    only offers `pl`/`ru`, and the enum only accepts `['ru','pl']`. If the detail payload's `locale`
    is anything other than exactly `"ru"`/`"pl"` (e.g. empty, differently cased, or region-tagged),
    no option matches and the field stays empty. Backend maps locale via
    `UserServiceMapper.localeToTag` (`toLanguageTag().toLowerCase()`). Fix direction: confirm the
    detail endpoint returns `"ru"`/`"pl"`; normalize the value on prefill (lowercase, strip region)
    and/or align the Select option set + enum with the actual stored values so edit prefills
    correctly. Suspect: `foremen-frontend/src/features/users/components/UserFormSheet.tsx` (edit
    `reset` + locale Select), `schemas/user-schema.ts`, `types/index.ts`; verify against
    `UserServiceMapper.java` on the wire.
  - _Requirements: 3.2, 3.3_
  - _Depends on: none_

- [x] V1. Validate Round 1 in the browser
  - Bring up the local Docker stack (`docker compose up`, rebuild changed `backend`/`frontend`
    services), log in as ADMIN, open `/users`, and reproduce each fixed bug's steps:
    (1) sort by role ascending/descending → 200 + correctly ordered list, no 500;
    (2) apply a column filter that yields zero rows → "clear all" button visible and clears filters;
    an unfiltered empty table shows no misleading clear button;
    (3) open create/edit → phone country picker is searchable with localized country names;
    (4) flags render (no broken image) for options and selected value;
    (5) edit an existing user → language dropdown is prefilled and the form saves.
    Mark `[x]` only the bugs confirmed fixed in the browser.
  - _Requirements: 1.1, 1.4, 1.7, 2.2, 2.3, 3.2, 3.3_
  - _Depends on: 1, 2, 3, 4, 5_

## Parallel execution plan (waves) — Round 1

- **Wave 1 (parallel):** 1, 2, 3, 5 — independent; different files/layers (1: backend sort + list
  column; 2: DataTable empty/toolbar; 3: PhoneInput searchable country select; 5: UserFormSheet
  locale prefill). Bugs 3 and 5 both touch user form files but different fields/components; if the
  same file edits conflict in practice, serialize 5 after 3.
- **Wave 2:** 4 — depends on 3 (both touch `PhoneInput.tsx`; flag serving is layered on the
  custom country-select work to avoid conflicting edits).
- **Wave 3:** V1 — browser validation of all fixes; depends on every fix.

## Round 1 — validation results (2026-10-28)

Stack: `docker compose up` (backend + frontend rebuilt). Logged in as ADMIN, screen `/users`.

| # | Bug | Verified in browser | Evidence |
|---|-----|---------------------|----------|
| 1 | Sort by role → 500 | ✅ Fixed | UI emits `sort=role.name,<dir>`; API returns 200 with rows ordered by localized role name (RU locale: Администратор…Рабочий asc, reversed desc). Clicking the "Rola" header sorts with no error toast. Backend resolves `role.name` → `role.nameRU`/`role.namePL` by active locale. |
| 2 | Clear-filter button on empty filter | ✅ Fixed | Applying an Email column filter with no matches shows the empty state "Brak wyników…" **plus** a "Wyczyść filtry" button; clicking it restores all rows. A genuinely empty table (no filter) shows a plain "no data" message with no clear button. |
| 3 | Searchable localized country dropdown | ✅ Fixed | Phone country picker is now a searchable combobox; typing "Niem" filters to "Niemcy"; options show localized PL names (e.g. "Zjednoczone Emiraty Arabskie"). Selecting a country updates the dial code (+49). |
| 4 | Country flag broken image | ✅ Fixed | Flags render as local `country-flag-icons` SVG components (bundled), not the external CDN image; selected-country flag + per-option flags display. |
| 5 | Edit: language not prefilled, blocks save | ✅ Fixed | Editing Marek Nowak (stored `locale=pl`) prefills the language dropdown to "Polski (PL)" (hidden value `pl`); Save succeeds and closes the sheet with no validation error. |

Result: 5 fixed / 0 failed. Run strategy: live Docker stack + direct browser validation (Playwright).

### Backend i18n sort/filter design note

`getI18nSupportedProperties()` is shared by the read/write model-field i18n `@AfterMapping` hooks
(`processI18n` / `processI18nEmptyValues`) AND by sort/filter field resolution. Adding the nested
`role.name` to that shared set broke the model-field hooks (they looked up a non-existent
`role.namePL` field). The fix introduces a dedicated `getI18nSortFilterProperties()` (defaults to
`getI18nSupportedProperties()`) used ONLY by sort/filter resolution; `UserServiceMapper` overrides it
with `role.name` while keeping `getI18nSupportedProperties()` empty. The shared sort/filter resolvers
(`ReadOnlyAdminService.resolveI18nSortProperty`, `QueryParser.resolveFieldName`) were extended to
recognize a fully-qualified key (`role.name`) in addition to bare segments.

### Local-stack note (not a code change)

The committed `docker-compose.yml` does not set `FOREMEN_BOT_IDENTITY_SECRET`, which the backend now
requires to start (FOR-08). A local, git-ignored `docker-compose.override.yml` was used to supply a
dev value for validation. This is unrelated to the bugfixes and was not committed.

## Round 2 (2026-10-28)

- [x] 6. Role name always rendered in RU, regardless of the user's active locale
  - Triage: observed the `roleName` column/field always showed the Russian role name even when the
    UI locale was PL; expected the role name in the caller's active language (RU for a `ru` request,
    PL otherwise). Layer: **backend**. Root cause: `UserServiceMapper` mapped `roleName` with a
    hard-coded `source.getRole().getNameRU()` on both `toServiceModel` and `toServiceExtendedModel`,
    ignoring the request locale — unlike every other localized dictionary name, which resolves via
    `Accept-Language`. Fix: added `localizedRoleName(RoleEntity)` (ru→pl→code fallback, same pattern
    as the existing `localizedName(WorkCategoryEntity)` / `ProjectMemberService` role-name
    resolution, keyed on `LocaleContextHolder` / `isRussianLocale()`), and pointed both `roleName`
    mappings at it. The frontend already sends `Accept-Language: ru|pl` from the selected UI locale.
    Suspect: `foremen-backend/.../service/model/mapper/UserServiceMapper.java`.
  - _Requirements: 1.2_
  - _Depends on: none_
- [x] V2. Validate Round 2 in the browser
  - API: `GET /api/users` with `Accept-Language: pl` returns Polish role names (Pracownik, Klient,
    Brygadzista, Manager); with `ru` returns Russian (Рабочий, Клиент, Прораб). Browser: with the UI
    in PL the role column shows Polish names; toggling the top-bar locale to RU flips them to Russian
    and back. Confirmed fixed.
  - _Depends on: 6_

## Parallel execution plan (waves) — Round 2

- **Wave 1:** 6 — single backend mapper change.
- **Wave 2:** V2 — browser/API validation; depends on 6.
