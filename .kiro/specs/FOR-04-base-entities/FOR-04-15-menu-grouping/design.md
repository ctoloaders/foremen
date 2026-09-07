# Design Document

FOR-04-15: Main-menu grouping — "Catalog" and "Dictionaries" navigation sections

## Overview

This is a frontend-only, configuration-level change. There is no backend work, no
database migration, no ABAC resource, and no new page or route. The FOR-04-02
through FOR-04-14 specs each already ship their CRUD page, its lazy route in
`src/app/router.tsx`, an interim `extra[...]` entry in
`src/config/route-permissions.ts`, and their per-page + `nav.*` i18n keys.

FOR-04-15 does exactly four things:

1. Extend the `NAV_CONFIG` array in `foremen-frontend/src/config/navigation.ts`
   with two new `NavSectionConfig` entries — **Catalog** and **Dictionaries** —
   each listing already-registered routes with their `requiredPermission`.
2. Add the two section-title i18n keys (`nav.sections.catalog`,
   `nav.sections.dictionaries`) to `pl.json` and `ru.json` (the per-item
   `nav.*` label keys already exist).
3. Make the **Dictionaries** section collapsible on the `Sidebar` and `Drawer`
   surfaces via a small, opt-in rendering change keyed on the section.
4. Verify (via tests) that the existing Projects/Rooms items, the FOR-03-07
   `Route_Requirement_Map`, and the `BottomNav` set remain correct.

Because `NAV_CONFIG` is the single source both the navigation surfaces and the
FOR-03-07 `Route_Requirement_Map` derive from, adding the items there
automatically (a) surfaces them in the Sidebar/Drawer, (b) keeps them out of
`BottomNav` (all set `bottomNav: false`), and (c) keeps deep-link route guarding
in agreement with the menu. The interim `extra[...]` guards the FOR-04-02..14
specs added continue to work; they are simply superseded as the source of truth
for those paths once the paths also appear in `NAV_CONFIG` (the map merges nav
paths with `extra`, so duplicates resolve to the same requirement).

## Architecture

```
NAV_CONFIG (src/config/navigation.ts)   ← single source of truth
        │
        ├── Sidebar.tsx   (desktop/tablet)   ─┐
        ├── Drawer.tsx    (mobile menu)       ├─ render sections + isNavItemVisible()
        ├── BottomNav.tsx (bottomNav:true)   ─┘   (FOR-03-07 permission filtering)
        │
        └── route-permissions.ts  → Route_Requirement_Map → PermissionGuard (deep links)
```

FOR-04-15 edits only `NAV_CONFIG`, the two locale files, and the section-render
block of `Sidebar`/`Drawer` (to make one section collapsible). No other module
changes.

## Components and Interfaces

### 1. `NAV_CONFIG` (src/config/navigation.ts)

Insert two sections. Section order after the change:

1. unnamed (`titleKey: null`) — Dashboard, Projects, Rooms, Estimate *(unchanged)*
2. **`nav.sections.catalog`** *(new)* — Work Catalog, Work Prices
3. `nav.sections.warehouse` — Materials, Finances, Deliveries *(unchanged)*
4. **`nav.sections.dictionaries`** *(new, collapsible)* — the nine reference pages
5. `nav.sections.system` — Users, Roles, Audit *(unchanged)*
6. `nav.sections.settings` — Appearance *(unchanged)*

New sections (illustrative):

```typescript
// inserted after the first unnamed section
{
  titleKey: 'nav.sections.catalog',
  items: [
    { path: '/catalog/works',  labelKey: 'nav.workCatalog', icon: 'book-open',  bottomNav: false,
      requiredPermission: { resource: 'WORK_CATALOG', operation: 'READ' } },
    { path: '/catalog/prices', labelKey: 'nav.workPrices',  icon: 'tag',        bottomNav: false,
      requiredPermission: { resource: 'WORK_PRICES', operation: 'READ' } },
  ],
},

// inserted after the warehouse section
{
  titleKey: 'nav.sections.dictionaries',
  items: [
    { path: '/measurement-units',   labelKey: 'nav.measurementUnits',   icon: 'ruler',       bottomNav: false,
      requiredPermission: { resource: 'MEASUREMENT_UNITS',   operation: 'READ' } },
    { path: '/currencies',          labelKey: 'nav.currencies',         icon: 'coins',       bottomNav: false,
      requiredPermission: { resource: 'CURRENCIES',          operation: 'READ' } },
    { path: '/vat-rates',           labelKey: 'nav.vatRates',           icon: 'percent',     bottomNav: false,
      requiredPermission: { resource: 'VAT_RATES',           operation: 'READ' } },
    { path: '/room-types',          labelKey: 'nav.roomTypes',          icon: 'layout-grid', bottomNav: false,
      requiredPermission: { resource: 'ROOM_TYPES',          operation: 'READ' } },
    { path: '/work-categories',     labelKey: 'nav.workCategories',     icon: 'list-tree',   bottomNav: false,
      requiredPermission: { resource: 'WORK_CATEGORIES',     operation: 'READ' } },
    { path: '/delivery-categories', labelKey: 'nav.deliveryCategories', icon: 'boxes',       bottomNav: false,
      requiredPermission: { resource: 'DELIVERY_CATEGORIES', operation: 'READ' } },
    { path: '/delivery-statuses',   labelKey: 'nav.deliveryStatuses',   icon: 'list-checks', bottomNav: false,
      requiredPermission: { resource: 'DELIVERY_STATUSES',   operation: 'READ' } },
    { path: '/material-categories', labelKey: 'nav.materialCategories', icon: 'layers',      bottomNav: false,
      requiredPermission: { resource: 'MATERIAL_CATEGORIES', operation: 'READ' } },
    { path: '/offer-packages',      labelKey: 'nav.offerPackages',      icon: 'package-2',   bottomNav: false,
      requiredPermission: { resource: 'OFFER_PACKAGES',      operation: 'READ' } },
  ],
},
```

Paths above are verified against the routes registered by each FOR-04-02..14
spec (all dictionary pages use **flat** routes such as `/currencies`,
`/room-types`; the catalog pages use `/catalog/works` and `/catalog/prices`).
Icon names are Lucide identifiers consistent with the existing config; exact
icon choice is a cosmetic detail and not load-bearing.

The Projects (`/projects` → `PROJECTS/READ`) and Rooms (`/rooms` → `ROOMS/READ`)
items already exist in the first section with the correct `requiredPermission`
(they were forward-declared in FOR-03-07). No edit is required; a unit test
asserts they still match the FOR-04-13/14 resources.

### 2. i18n — `nav.sections.*` (pl.json + ru.json)

Add two keys under the existing `nav.sections` object in each locale file:

| key | pl.json | ru.json |
|-----|---------|---------|
| `nav.sections.catalog` | `Katalog` | `Каталог` |
| `nav.sections.dictionaries` | `Słowniki` | `Справочники` |

All eleven per-item `nav.*` label keys already exist at parity in both files
(verified) and are reused unchanged.

### 3. Collapsible Dictionaries section (Sidebar.tsx / Drawer.tsx)

Both surfaces currently render each section as a static header `<p>` followed by
its items. The change: when a section is the Dictionaries section (identified by
`section.titleKey === 'nav.sections.dictionaries'`), render its header as a
`<button>` toggle that shows/hides the item list.

- Local component state holds `dictionariesOpen` (default expanded, `true`), kept
  in the existing `useState`-based component (no store change needed). Optionally
  the state can be persisted, but that is out of scope; default-open is the
  baseline behavior.
- The toggle button carries `aria-expanded={open}` and an accessible name from
  the localized title (`t('nav.sections.dictionaries')`), and toggles on
  click/Enter/Space (native `<button>` gives keyboard support for free).
- A chevron icon (Lucide `chevron-down` / rotated when collapsed) indicates
  state.
- When collapsed, the header stays visible and the items are not rendered.
- All other sections keep the current static `<p>` header rendering — the
  collapsible path is gated on the Dictionaries `titleKey` only.
- Permission filtering runs first (as today): if the Dictionaries section has no
  visible items for the current user, the whole section (header + toggle) is
  suppressed by the existing `visibleItems.length === 0` guard, so a
  no-dictionary role (e.g. CLIENT) never sees the toggle.

The `collapsed` sidebar (icon-only) mode already hides section titles; in that
mode the Dictionaries items render as icons like any other section (no toggle
shown, matching the existing "no header when collapsed" behavior).

### 4. Route_Requirement_Map (no code change)

`route-permissions.ts` already derives `path → requiredPermission` from
`NAV_CONFIG` and merges an `extra` map. After adding the eleven items to
`NAV_CONFIG`, their paths are covered by the nav-derived portion of the map; the
interim `extra[...]` entries the FOR-04-02..14 specs added resolve to the same
`{ resource, READ }` requirement, so the merged map is consistent. No edit is
needed in this spec; a test asserts each new path resolves to its expected
requirement.

## Data Models

None. This spec introduces no entity, DTO, table, or persisted state. The only
new "data" is the two static i18n strings per locale and the two static
`NavSectionConfig` literals.

## Error Handling

Not applicable at runtime — there are no network calls, mutations, or user input
in this change. The relevant failure mode is a mismatch between a nav `path` and
the actual registered route (a dead link) or a missing i18n key (raw key
rendered). Both are caught by the tests below rather than at runtime.

## Testing Strategy

All tests are frontend (Vitest + Testing Library), extending the existing
`navigation.test.ts`, `Sidebar.test.tsx`, `Drawer.test.tsx`, `BottomNav.test.tsx`
suites.

- **`NAV_CONFIG` structure** (`navigation.test.ts`): assert the Catalog and
  Dictionaries sections exist in the required positions; every new item has a
  non-empty `icon`, `bottomNav === false`, a `path` starting with `/`, and a
  `requiredPermission` with `operation === 'READ'`; the nine dictionary paths and
  two catalog paths match the expected set.
- **Projects/Rooms binding** (`navigation.test.ts`): assert the `/projects` and
  `/rooms` items still carry `{PROJECTS,READ}` / `{ROOMS,READ}` and remain in the
  first unnamed section.
- **Permission filtering** (`Sidebar.test.tsx` / `Drawer.test.tsx`): ADMIN
  (bypass) sees both new headers and all items; a role granted only a subset of
  the resources sees only those items with the header still shown; a role with no
  Catalog (resp. no Dictionaries) resource sees neither that header nor its items
  (section suppression).
- **Collapsible behavior** (`Sidebar.test.tsx` / `Drawer.test.tsx`): activating
  the Dictionaries header toggles the item list and flips `aria-expanded`; other
  section headers are not rendered as toggles; the toggle is a focusable
  `<button>` with an accessible name.
- **Localization** (existing i18n parity test): `nav.sections.catalog` and
  `nav.sections.dictionaries` exist and are non-empty in both `pl.json` and
  `ru.json`; `nav.sections.*` keys are identical across locales; no raw key is
  rendered by the surfaces.
- **Route map consistency** (`route-permissions.test.ts` if present, else a new
  small test): each new path resolves via `requirementForPath` to its expected
  `{ resource, READ }`.
- **`BottomNav` non-regression** (`BottomNav.test.tsx`): the set of rendered
  bottom-bar items is unchanged (all new items are `bottomNav: false`).

Full backend suite is not applicable. This spec's verification is `npm run
build` / `npm run test` in `foremen-frontend`, plus `tsc` type-check.

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Group changes live only in `NAV_CONFIG` + two i18n keys | `NAV_CONFIG` is the single source for all three nav surfaces and the route map; editing it is the minimal, non-duplicating change and keeps menu ↔ guard agreement automatic. |
| Reuse existing `nav.*` item labels; add only two `nav.sections.*` keys | The item labels were already added by each FOR-04-02..14 spec; re-adding them would risk drift. Only the two section headers are genuinely new. |
| Collapsible behavior gated on `titleKey === 'nav.sections.dictionaries'` | Only the nine-item Dictionaries group is long enough to warrant collapsing; keeping the other sections' rendering untouched avoids regressing their tests and UX. |
| Default-expanded, component-local state (no store, no persistence) | Simplest correct behavior; persistence across reloads is a nice-to-have explicitly out of scope. |
| No route/page/guard code added | Every path is already registered and interim-guarded by FOR-04-02..14; this spec is grouping/navigation only, per the OVERVIEW. |
| All new items `bottomNav: false` | The mobile bottom bar is intentionally limited to the five primary destinations; reference/catalog pages belong in the drawer, not the bottom bar. |
