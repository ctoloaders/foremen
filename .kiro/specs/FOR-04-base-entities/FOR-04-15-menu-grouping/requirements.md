# Requirements Document

FOR-04-15: Main-menu grouping — "Catalog" and "Dictionaries" navigation sections and route wiring

## Introduction

FOR-04-15 is a **pure frontend / navigation** spec. It introduces **no backend
changes, no new entity, no database migration, and no ABAC resource**. All CRUD
pages, API clients, forms, route guards, and per-page i18n keys for the FOR-04
dictionaries and entities are already delivered by their own specs (FOR-04-02
through FOR-04-14); each of those pages ships behind an interim `READ` route
guard until this spec surfaces it in the menu.

This spec's single job is to make those already-built pages **discoverable in the
main navigation** by extending the shared navigation configuration
(`foremen-frontend/src/config/navigation.ts`, the `NAV_CONFIG` array introduced
in FOR-02-02 and extended with `requiredPermission` in FOR-03-07):

- Add a new **"Catalog"** section grouping Work Catalog (`/catalog/works`) and
  Work Prices (`/catalog/prices`).
- Add a new, optionally collapsible **"Dictionaries"** section grouping the nine
  reference pages: Measurement Units, Currencies, VAT Rates, Room Types, Work
  Categories, Delivery Categories, Delivery Statuses, Material Categories, and
  Offer Packages.
- Bind the existing **Projects** (`/projects`) and **Rooms** (`/rooms`) items
  (already present in the first, unnamed section) to their now-real FOR-04-13 /
  FOR-04-14 resources — verifying their `requiredPermission` matches the seeded
  `PROJECTS` / `ROOMS` resources.
- Add the two new section-title i18n keys (`nav.sections.catalog`,
  `nav.sections.dictionaries`) in BOTH `pl.json` and `ru.json`. The per-item nav
  label keys (`nav.workCatalog`, `nav.workPrices`, `nav.measurementUnits`,
  `nav.currencies`, `nav.vatRates`, `nav.roomTypes`, `nav.workCategories`,
  `nav.deliveryCategories`, `nav.deliveryStatuses`, `nav.materialCategories`,
  `nav.offerPackages`) already exist in both locale files and are reused as-is.

Every nav item continues to be filtered by the FOR-03-07 permission rule
(`isNavItemVisible` / `usePermission`): a role without `READ` on an item's
resource does not see that item, and a section whose items are all filtered out
renders nothing (no empty header). Route paths added to `NAV_CONFIG` continue to
feed the FOR-03-07 `Route_Requirement_Map` (`route-permissions.ts`), so the menu
and the deep-link route guard stay in agreement. This spec adds no new routes and
no new page components — it only references the paths the FOR-04-02..14 specs
already registered in the router.

Depends on FOR-04-02 through FOR-04-14 (the dictionary/entity pages, their
routes, per-page i18n, and seeded ABAC resources), FOR-03-07 (permission-driven
navigation, `NAV_CONFIG.requiredPermission`, `isNavItemVisible`,
`Route_Requirement_Map`), and FOR-02-02 (the `NAV_CONFIG` shape and the Sidebar /
Drawer / BottomNav surfaces that render it).

## Glossary

- **`NAV_CONFIG`**: The single source-of-truth navigation configuration array in `foremen-frontend/src/config/navigation.ts` — an ordered list of `NavSectionConfig`, each with a nullable `titleKey` and an ordered list of `NavItemConfig`.
- **`NavSectionConfig`**: A navigation section: `{ titleKey: string | null, items: NavItemConfig[] }`. A `null` `titleKey` renders no header (the first, unnamed group).
- **`NavItemConfig`**: A single navigation entry: `{ path, labelKey, titleKey?, icon, bottomNav, requiredPermission? }`.
- **`requiredPermission`**: The optional `{ resource, operation }` a user must be granted (via FOR-03-07 `usePermission`) to see a nav item. Absent ⇒ visible to any authenticated user.
- **Catalog section**: The new `NavSectionConfig` titled `nav.sections.catalog`, grouping Work Catalog and Work Prices.
- **Dictionaries section**: The new `NavSectionConfig` titled `nav.sections.dictionaries`, grouping the nine reference pages, optionally collapsible on the Sidebar/Drawer surfaces.
- **`isNavItemVisible`**: The FOR-03-07 shared predicate that decides whether a nav item renders, given the `usePermission` `hasPermission(resource, operation)` function.
- **`Route_Requirement_Map`**: The FOR-03-07 map (`route-permissions.ts`) deriving `path → requiredPermission` from `NAV_CONFIG`, used by the deep-link route guard.
- **Navigation surfaces**: The three components that render `NAV_CONFIG` — `Sidebar` (desktop/tablet), `Drawer` (mobile menu), and `BottomNav` (mobile bottom bar, `bottomNav: true` items only).
- **Section suppression**: The FOR-03-07 rule that a section renders nothing (header included) when all its items are filtered out by permission.

## Requirements

### Requirement 1: Catalog navigation section

**User Story:** As a user with catalog access, I want a "Catalog" group in the main menu linking to the work catalog and work prices pages, so that I can reach them from navigation instead of typing the URL.

#### Acceptance Criteria

1. THE `NAV_CONFIG` SHALL include a `NavSectionConfig` whose `titleKey` is `nav.sections.catalog`, placed after the first unnamed section and before the `nav.sections.warehouse` section.
2. THE Catalog section SHALL contain, in order, a Work Catalog item (`path` `/catalog/works`, `labelKey` `nav.workCatalog`, `requiredPermission` `{ resource: 'WORK_CATALOG', operation: 'READ' }`) and a Work Prices item (`path` `/catalog/prices`, `labelKey` `nav.workPrices`, `requiredPermission` `{ resource: 'WORK_PRICES', operation: 'READ' }`).
3. THE Work Catalog and Work Prices items SHALL each declare a non-empty `icon` and SHALL set `bottomNav` to `false`.
4. THE `path` values of the Catalog items SHALL exactly match the routes registered by FOR-04-11 (`/catalog/works`) and FOR-04-12 (`/catalog/prices`).
5. WHERE the current user is granted `WORK_CATALOG` `READ` or `WORK_PRICES` `READ`, WHEN a navigation surface renders, THE System SHALL show the Catalog section header and the granted item(s).
6. WHERE the current user is granted neither `WORK_CATALOG` `READ` nor `WORK_PRICES` `READ`, WHEN a navigation surface renders, THE System SHALL render no Catalog section header and no Catalog items.

### Requirement 2: Dictionaries navigation section

**User Story:** As a user with reference-data access, I want a "Dictionaries" group listing all nine reference pages, so that the reference pages are organized under one collapsible heading rather than scattered.

#### Acceptance Criteria

1. THE `NAV_CONFIG` SHALL include a `NavSectionConfig` whose `titleKey` is `nav.sections.dictionaries`, placed after the `nav.sections.warehouse` section and before the `nav.sections.system` section.
2. THE Dictionaries section SHALL contain, in this order, items for Measurement Units, Currencies, VAT Rates, Room Types, Work Categories, Delivery Categories, Delivery Statuses, Material Categories, and Offer Packages, each with `bottomNav: false`, a non-empty `icon`, the label key and route and `requiredPermission` in the table below.

   | # | labelKey | path | requiredPermission resource |
   |---|----------|------|-----------------------------|
   | 1 | `nav.measurementUnits` | `/measurement-units` | `MEASUREMENT_UNITS` |
   | 2 | `nav.currencies` | `/currencies` | `CURRENCIES` |
   | 3 | `nav.vatRates` | `/vat-rates` | `VAT_RATES` |
   | 4 | `nav.roomTypes` | `/room-types` | `ROOM_TYPES` |
   | 5 | `nav.workCategories` | `/work-categories` | `WORK_CATEGORIES` |
   | 6 | `nav.deliveryCategories` | `/delivery-categories` | `DELIVERY_CATEGORIES` |
   | 7 | `nav.deliveryStatuses` | `/delivery-statuses` | `DELIVERY_STATUSES` |
   | 8 | `nav.materialCategories` | `/material-categories` | `MATERIAL_CATEGORIES` |
   | 9 | `nav.offerPackages` | `/offer-packages` | `OFFER_PACKAGES` |

3. THE `requiredPermission` `operation` of every Dictionaries item SHALL be `READ`.
4. THE `path` of every Dictionaries item SHALL exactly match the route registered by the corresponding FOR-04-02..10 spec's page.
5. WHERE the current user is granted `READ` on at least one Dictionaries resource, WHEN a navigation surface renders, THE System SHALL show the Dictionaries section header and exactly the granted item(s), and SHALL hide the non-granted item(s).
6. WHERE the current user is granted `READ` on none of the nine Dictionaries resources (for example a `CLIENT`), WHEN a navigation surface renders, THE System SHALL render no Dictionaries section header and no Dictionaries items.

### Requirement 3: Collapsible Dictionaries section

**User Story:** As a user, I want the long Dictionaries group to be collapsible on the sidebar and mobile drawer, so that it does not dominate the menu when I am not using it.

#### Acceptance Criteria

1. WHEN the Dictionaries section is rendered on the `Sidebar` or `Drawer` and has at least one visible item, THE System SHALL render its header as an interactive toggle exposing an expanded/collapsed state.
2. WHEN a user activates the Dictionaries header toggle, THE System SHALL flip the section between expanded (items visible) and collapsed (items hidden) and SHALL update the toggle's `aria-expanded` accordingly.
3. THE collapsible behavior SHALL apply ONLY to the Dictionaries section; all other sections SHALL keep their existing non-collapsible header rendering.
4. WHILE the Dictionaries section is collapsed, THE System SHALL keep its header visible so the user can expand it again.
5. THE collapsible toggle SHALL be keyboard operable (focusable and activatable via Enter/Space) and expose an accessible name from the localized section title.

### Requirement 4: Projects and Rooms binding

**User Story:** As a maintainer, I want the existing Projects and Rooms menu items confirmed against the now-real FOR-04-13/14 resources, so that navigation and access stay consistent with the seeded ABAC resources.

#### Acceptance Criteria

1. THE Projects nav item SHALL keep `path` `/projects` with `requiredPermission` `{ resource: 'PROJECTS', operation: 'READ' }`, matching the FOR-04-13 seeded `PROJECTS` resource.
2. THE Rooms nav item SHALL keep `path` `/rooms` with `requiredPermission` `{ resource: 'ROOMS', operation: 'READ' }`, matching the FOR-04-14 seeded `ROOMS` resource.
3. THE System SHALL NOT relocate the Projects or Rooms items out of the first unnamed section.
4. WHERE the current user lacks `PROJECTS` `READ` (respectively `ROOMS` `READ`), WHEN a navigation surface renders, THE System SHALL hide the Projects (respectively Rooms) item, unchanged from FOR-03-07 behavior.

### Requirement 5: Navigation i18n keys

**User Story:** As a bilingual user, I want the new section headers localized in Polish and Russian, so that the menu reads correctly in my language with no raw keys.

#### Acceptance Criteria

1. THE System SHALL define `nav.sections.catalog` and `nav.sections.dictionaries` in BOTH `pl.json` and `ru.json`, with non-empty values (PL: "Katalog" / "Słowniki"; RU: "Каталог" / "Справочники").
2. THE System SHALL reuse the pre-existing per-item nav label keys (`nav.workCatalog`, `nav.workPrices`, `nav.measurementUnits`, `nav.currencies`, `nav.vatRates`, `nav.roomTypes`, `nav.workCategories`, `nav.deliveryCategories`, `nav.deliveryStatuses`, `nav.materialCategories`, `nav.offerPackages`) and SHALL NOT redefine or remove them.
3. THE `nav.sections.*` key set SHALL have identical keys in `pl.json` and `ru.json`.
4. WHEN any navigation surface renders the Catalog or Dictionaries headers, THE System SHALL display the localized text for the active locale and SHALL NOT render any raw i18n key.

### Requirement 6: Route/menu consistency and non-regression

**User Story:** As a maintainer, I want the new menu paths to agree with the route guards and the rest of the menu to keep working, so that adding the groups introduces no dead links or access drift.

#### Acceptance Criteria

1. THE FOR-03-07 `Route_Requirement_Map` derived from `NAV_CONFIG` SHALL, after this change, map each new Catalog and Dictionaries `path` to its item's `requiredPermission`, so the deep-link guard and the menu agree.
2. WHEN a user navigates directly to any Catalog or Dictionaries `path` without the item's `READ` permission, THE System SHALL apply the existing FOR-03-07 route guard and deny access (redirect to `/403`), unchanged by this spec.
3. THE `BottomNav` surface SHALL be unaffected: because every new item sets `bottomNav: false`, the set of bottom-bar items SHALL be identical before and after this change.
4. THE existing sections (unnamed, `nav.sections.warehouse`, `nav.sections.system`, `nav.sections.settings`) and their items SHALL retain their order, labels, paths, and permissions.
5. THE System SHALL add no new page component and no new route in this spec; every referenced `path` SHALL already be registered by a FOR-04-02..14 spec.

### Requirement 7: Frontend tests

**User Story:** As a maintainer, I want tests covering the new sections, permission filtering, collapse behavior, and localization, so that the grouping stays correct as roles and pages evolve.

#### Acceptance Criteria

1. THE `NAV_CONFIG` structure SHALL be covered by a unit test asserting the Catalog and Dictionaries sections exist in the required order, every new item has a non-empty `icon`, `bottomNav: false`, a `path` starting with `/`, and a `requiredPermission` whose `operation` is `READ`.
2. THE navigation filtering SHALL be covered by a test asserting an ADMIN sees both new section headers and all their items, and a role granted only a subset of the resources sees only the granted items with the header still shown.
3. THE section suppression SHALL be covered by a test asserting a role with no Catalog resource (respectively no Dictionaries resource) sees neither that section's header nor its items.
4. THE collapsible Dictionaries behavior SHALL be covered by a component test asserting the header toggles the item list and updates `aria-expanded`, and that other section headers are not rendered as toggles.
5. THE localization SHALL be covered by a test asserting `nav.sections.catalog` and `nav.sections.dictionaries` exist and are non-empty in both `pl.json` and `ru.json`, with identical `nav.sections.*` keys across locales and no raw key rendered.
6. THE `BottomNav` non-regression SHALL be covered by a test asserting the bottom-bar item set is unchanged after adding the new (all `bottomNav: false`) items.
