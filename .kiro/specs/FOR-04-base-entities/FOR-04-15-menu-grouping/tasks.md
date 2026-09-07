# Implementation Plan

This is a frontend-only spec (`foremen-frontend`, TypeScript / React). There are
NO backend, database, or ABAC changes. All CRUD pages, routes, interim guards,
and per-page/`nav.*` label i18n keys already exist (shipped by FOR-04-02..14);
this plan only adds two navigation sections to `NAV_CONFIG`, two section-title
i18n keys, and a collapsible header for the Dictionaries section, then verifies
non-regression. Verify with `npm run test` / `tsc` in `foremen-frontend`.

- [x] 1. Navigation config: add Catalog and Dictionaries sections
  - Edit `foremen-frontend/src/config/navigation.ts`: insert a `nav.sections.catalog` section (Work Catalog `/catalog/works` → `{WORK_CATALOG,READ}`, Work Prices `/catalog/prices` → `{WORK_PRICES,READ}`) after the first unnamed section, and a `nav.sections.dictionaries` section (the nine reference items per the design table, each `{<RESOURCE>,READ}`) after the warehouse section. Every new item: non-empty Lucide `icon`, `bottomNav: false`, `path` matching the FOR-04-02..14 registered routes.
  - Confirm the existing Projects (`/projects` → `{PROJECTS,READ}`) and Rooms (`/rooms` → `{ROOMS,READ}`) items are unchanged and remain in the first unnamed section.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 4.1, 4.2, 4.3, 6.3, 6.4, 6.5_

- [x] 2. i18n: add section-title keys
  - Add `nav.sections.catalog` (PL "Katalog", RU "Каталог") and `nav.sections.dictionaries` (PL "Słowniki", RU "Справочники") under the existing `nav.sections` object in BOTH `foremen-frontend/src/locales/pl.json` and `ru.json`. Do not modify or remove the existing `nav.*` item label keys.
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [x] 3. Collapsible Dictionaries section (Sidebar + Drawer)
  - In `foremen-frontend/src/app/layout/Sidebar.tsx` and `Drawer.tsx`, render the section whose `titleKey === 'nav.sections.dictionaries'` with an interactive `<button>` header (chevron icon, `aria-expanded`, accessible name from the localized title) that toggles component-local, default-expanded state to show/hide its items. Leave all other sections' static header rendering untouched. Keep the existing permission filtering + empty-section suppression so a no-dictionary role sees no header/toggle.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 4. Tests
  - [x] 4.1 `NAV_CONFIG` structure + Projects/Rooms binding
    - Extend `src/config/__tests__/navigation.test.ts`: assert the Catalog and Dictionaries sections exist in the required order; every new item has a non-empty `icon`, `bottomNav: false`, a `path` starting with `/`, and `requiredPermission.operation === 'READ'`; the eleven new paths match the expected set; `/projects`/`/rooms` still carry `{PROJECTS,READ}`/`{ROOMS,READ}` in the first section.
    - _Requirements: 7.1, 4.1, 4.2_
  - [x] 4.2 Permission filtering + section suppression
    - Extend `Sidebar.test.tsx` / `Drawer.test.tsx`: ADMIN sees both new headers and all items; a subset-granted role sees only granted items with the header shown; a role with no Catalog (resp. no Dictionaries) resource sees neither the header nor its items.
    - _Requirements: 7.2, 7.3, 1.5, 1.6, 2.5, 2.6_
  - [x] 4.3 Collapsible behavior
    - Add a component test: activating the Dictionaries header toggles the item list and flips `aria-expanded`; other section headers are not toggles; the toggle is a focusable `<button>` with an accessible name.
    - _Requirements: 7.4, 3.1, 3.2, 3.3, 3.5_
  - [x] 4.4 Localization parity + route-map consistency + BottomNav non-regression
    - Assert `nav.sections.catalog` / `nav.sections.dictionaries` exist, are non-empty, and are identical-keyed in `pl.json` / `ru.json` with no raw key rendered; assert each new path resolves via `requirementForPath` to its `{resource, READ}`; assert the `BottomNav` item set is unchanged.
    - _Requirements: 7.5, 7.6, 5.1, 5.3, 6.1, 6.2, 6.3_

- [x] 5. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard. This is a UI spec, so scenarios are browser-engine scenarios (navigate the running frontend, open the menu, verify the Catalog/Dictionaries groups, collapse/expand, permission-filtered visibility per role, PL/RU localization). Group by feature, give step-by-step scenarios, state the repeatability strategy (read-only navigation — no data mutation, so no teardown needed; distinct login sessions per role), include a regression group, and end with the MD report template with tables. Result artifacts are MD reports with tables.
  - _Requirements: 1.5, 1.6, 2.5, 2.6, 3.1, 3.2, 4.4, 5.4, 6.2_

- [x] 6. Verification
  - Run `npm run test` and `tsc --noEmit` (or the project's type-check script) in `foremen-frontend`; confirm the new and existing navigation/localization tests pass. Manually verify the two groups appear, collapse/expand works, and menu items are permission-filtered.
  - _Requirements: 6.4, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_
