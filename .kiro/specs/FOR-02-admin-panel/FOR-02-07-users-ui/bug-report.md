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

## Round 3 (2026-10-28)

- [x] 7. WORKER-role user create/edit: show an OPTIONAL worker-type selector; persist the chosen worker type on the user
  - Request (user): for a WORKER-role user, the create/edit form must show a worker-type selector
    (optional). If chosen, it must be saved on the user entity.
  - Layer: **both** (backend persistence + frontend conditional selector). This is a small feature
    addition, not a defect, but is tracked here as requested ("append user management bugfix").
  - Investigation (context-gatherer, grounded):
    - A **WorkerType catalog already exists end-to-end** — no catalog work needed:
      `WorkerTypeEntity` (table `worker_types`), `GET /api/worker-types` (paginated,
      `WorkerTypeController`, ABAC resource `WORKER_TYPES`, read DTO `WorkerTypeDtoModel` with
      id/code/locale-resolved `name`), seeded by changeset `125-create-worker-types.xml` with
      ADMIN CRUD + MANAGER/FOREMAN/FINANCIER READ grants. Frontend already has
      `features/worker-types/api/worker-types-api.ts#fetchWorkerTypes` + `WorkerTypeDto`.
    - **Missing piece:** `UserEntity` has NO worker-type FK. A worker type is currently attached to
      a user only PER-PROJECT via `ProjectMemberEntity.workerType`, not on the user record. So the
      work is to add a nullable `users.worker_type_id` FK and thread it through create/update/read,
      plus a WORKER-conditional selector in the admin form.
    - **Strong precedent to mirror:** the FOR-05-10b `workCategoryIds` flow already added a
      WORKER-gated, nullable field to this exact user create/update/read path
      (`UserService.resolveWorkCategories` gated on `WORKER_ROLE_CODE = "WORKER"`; mapper
      write-ignore / read-ref; frontend payload append). Mirror it one-for-one for a scalar
      `workerTypeId`.
  - Fix plan:
    - **Backend (root repo):**
      1. New changeset `database_files/changesets/188-add-user-worker-type.xml` — `addColumn`
         `worker_type_id BIGINT` (nullable) on `users` + FK to `worker_types(id)` (`ON DELETE SET
         NULL`), idempotent (`columnExists` precondition + `MARK_RAN`); register LAST in
         `changelog.xml` after `187-...`. (Confirmed next free number is 188.)
      2. `UserEntity` — add `@ManyToOne(fetch = LAZY) @JoinColumn(name = "worker_type_id")
         WorkerTypeEntity workerType;` (nullable).
      3. DTOs — add optional `Long workerTypeId` to `UserCreateRequest` + `UserUpdateRequest`;
         add `workerTypeId` (write) + a `RefDto workerType` (read) to `UserServiceExtendedModel`;
         add `RefDto workerType` to `UserDtoExtendedModel` (the edit-form read model).
      4. Mappers — `UserServiceMapper`: `@Mapping(target="workerType", ignore=true)` on the write
         path (service resolves the id to a managed entity), add a read-path expression populating
         the `workerType` ref gated on WORKER (reuse `localizedName`), inject `WorkerTypeDao`;
         `UserControllerMapper` passes `workerTypeId` straight through.
      5. `UserService` — add `resolveWorkerType(role, workerTypeId)` that returns the managed
         `WorkerTypeEntity` only when `role.code == "WORKER"` (else null; reject unknown/inactive id),
         called from `validateCreate`/`validateUpdate` and applied in the overridden
         `create(...)`/`update(...)`, mirroring `resolveWorkCategories`. No new ABAC wiring
         (column on the existing `USERS` resource; `WORKER_TYPES` read already seeded).
    - **Frontend (foremen-frontend repo):**
      6. New `WorkerTypeSelect` cloned from `RoleSelect.tsx` (searchable/infinite Popover), backed by
         `fetchWorkerTypes`. Render it in `UserFormBody` (UserFormSheet) ONLY when the selected
         role's code is `WORKER` (surface the role code from `RoleSelect` or match `roleId` against
         loaded options); append `workerTypeId` to the create + update payloads; prefill on edit
         from `userData.workerType?.id`.
      7. Types — add `workerTypeId?: number | null` to `UserCreateRequest`/`UserUpdateRequest`,
         `workerType?: { id; name } | null` to `UserExtendedDto`; add `workerTypeId` to the zod
         `userFormSchema` (optional).
      8. i18n — add `users.form.workerType` (+ placeholder/none/search) to `pl.json` AND `ru.json`.
  - _Requirements: (new — FOR-02-07 user create/edit; worker-type persistence on the user. Mirrors FOR-05-10b WORKER-gated field precedent.)_
  - _Depends on: none_
  - Implemented (backend, root repo): changeset `188-add-user-worker-type.xml` (nullable
    `users.worker_type_id` FK → `worker_types(id)` `ON DELETE SET NULL`, idempotent) registered last
    in `changelog.xml`; `UserEntity.workerType` `@ManyToOne`; `workerTypeId` on
    `UserCreateRequest`/`UserUpdateRequest`; `workerTypeId` (write) + `RefDto workerType` (read) on
    `UserServiceExtendedModel`; `RefDto workerType` on `UserDtoExtendedModel`; `UserServiceMapper`
    write-ignore + read-ref (`toWorkerTypeRef`, WORKER-gated, localized `localizedName`);
    `UserControllerMapper` ignores the outbound ref; `UserService.resolveWorkerType(role, id)`
    (WORKER-only, rejects unknown/inactive → 400 `error.user.workerType.invalid`, PL+RU added),
    wired into validateCreate/validateUpdate and applied in create/update. All
    `UserServiceExtendedModel` call sites + 3 `UserService` test constructors updated.
  - Implemented (frontend, foremen-frontend repo): `WorkerTypeSelect` (searchable/infinite Popover,
    clearable, "none" option, cloned from `RoleSelect`, backed by `fetchWorkerTypes`);
    `UserFormSheet` renders it ONLY when the selected role code is WORKER (resolved from
    `useRolesForSelect` by id), appends `workerTypeId` to create+update payloads, prefills on edit;
    `userFormSchema` + types (`workerTypeId` request, `workerType` ref response); `users.form.workerType*`
    i18n in PL+RU.
  - Verified: backend `./gradlew compileJava compileTestJava` clean; affected user tests pass
    (`UserWorkCategoriesIntegrationTest` 9, `AdminRoleProhibitionPropertyTest` 5,
    `UserCreateInvitePropertyTest` 1, `UserServicePreservationTest` 3, `UserServiceBugConditionTest`
    2 — 0 failures). Frontend `npx tsc -b` clean; `npx vitest run src/features/users` → only the 3
    PRE-EXISTING RoleSelect preservation failures (confirmed identical on a stashed/clean tree),
    unrelated to this change. Liquibase applied 188 (column + FK verified in `\d users`).
  - _Requirements: (as above)_
  - _Depends on: none_

- [x] V3. Validate Round 3 in the browser
  - Create a WORKER user: the worker-type selector appears (optional); pick a type and save; reopen
    the user in edit and confirm the chosen worker type is prefilled (persisted on the user). Switch
    the role to a non-WORKER: the selector hides and no worker type is sent/kept. Confirm a WORKER
    saved with NO worker type persists null without error. Mark `[x]` only on a confirmed pass.
  - Result (2026-10-28, PASS): UI — with no role selected the worker-type field is hidden; selecting
    "Pracownik" (WORKER) revealed it with the placeholder + "Brak" + the 4 catalog tiers (Baza /
    Pracownik najemny bez narzędzi / Pracownik (JDG) z pełnym narzędziem / Firma); picking "Firma"
    and saving created user 3872 with `worker_type_id = 4` (code FIRM) in the DB, and
    `GET /api/users/3872` returned `workerType {id:4,name:"Firma"}` (localized) for edit prefill.
    API edge cases — a WORKER created with `workerTypeId:null` persisted null (user 3873); a
    non-WORKER (FOREMAN) sent `workerTypeId:4` ignored it and persisted null (user 3874, worker-only
    gating); a WORKER sent an invalid id (999999) was rejected 400 with the localized message. Test
    users 3872/3873/3874 deleted afterward (204) — no residual data.
  - _Requirements: (as bug 7)_
  - _Depends on: 7_

## Parallel execution plan (waves) — Round 3

- **Wave 1:** 7 — the worker-type field spans backend (changeset + entity + DTO/mapper/service) and
  frontend (selector + types + i18n); the backend and frontend halves are committed per their own
  repo but are one logical change, so they are a single leaf (serialized backend→frontend because
  the frontend selector consumes the new read `workerType` ref and request `workerTypeId`).
- **Wave 2:** V3 — browser validation; depends on 7.

## Round 4 (2026-10-29)

- [x] 8. Work-category multiselect dropdown is not wheel-scrollable (user edit form AND team change-categories dialog)
  - FIXED (2026-10-29): added `onWheel={(e) => e.stopPropagation()}` to the `max-h-60 overflow-y-auto`
    list `<div>` in `WorkCategoryMultiSelect.tsx`, stopping the Radix Dialog `react-remove-scroll`
    lock from swallowing wheel events over the portalled popover.
  - Triage: observed — the `WorkCategoryMultiSelect` options list overflows (e.g. 20 categories,
    scrollHeight 648 vs clientHeight 240) but the mouse WHEEL does not scroll it; the user can't
    reach the lower options. Expected — the dropdown scrolls with the wheel. Layer: **frontend**.
    Verified in-browser: the list container (`max-h-60 overflow-y-auto`) IS structurally scrollable
    (setting `scrollTop` works), but wheel events don't scroll it — the component renders inside a
    Radix `Popover` nested in a Radix `Dialog`/`Sheet`, whose `react-remove-scroll` scroll-lock
    swallows wheel events over the portalled popover. Same component is used by the admin user form
    (FOR-05-10 Work_Categories multiselect) and the team `EditWorkCategoriesDialog`, so both exhibit
    it. Fix: stop wheel-event propagation on the scrollable list container
    (`onWheel={(e) => e.stopPropagation()}`) so the Dialog's scroll-lock no longer intercepts it;
    the container's own `overflow-y-auto` then scrolls normally. (Apply the same guard to the sibling
    popovers that share the pattern — `RoleSelect`, `WorkerTypeSelect` — if they exhibit it.)
  - Suspect: `foremen-frontend/src/features/work-categories/components/WorkCategoryMultiSelect.tsx`
    (the `max-h-60 overflow-y-auto` list `<div>`).
  - _Requirements: FOR-05-10b R1/R2 (work-category assignment UI usable)_
  - _Depends on: none_

- [x] V4. Validate Round 4 in the browser
  - PASS (2026-10-29): in the user edit form (WORKER "Valera") the work-category dropdown had 20
    options (scrollHeight 648 > clientHeight 240); a real mouse wheel over the open popover scrolled
    the list from scrollTop 0 → 300. The dropdown is now wheel-scrollable. Validated against the
    live Docker stack.
  - In the user edit form (WORKER role) and the team change-work-categories dialog, open the
    work-category dropdown with enough categories to overflow and confirm the mouse wheel scrolls the
    list to the bottom options. Mark `[x]` only on a confirmed browser pass.
  - _Requirements: as bug 8_
  - _Depends on: 8_

## Parallel execution plan (waves) — Round 4

- **Wave 1:** 8 — single shared-component CSS/handler fix.
- **Wave 2:** V4 — browser validation; depends on 8.
