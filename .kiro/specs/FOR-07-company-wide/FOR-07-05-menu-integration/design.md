# Design Document: FOR-07-05 — Menu integration

## Overview

Two coordinated changes to the app shell:

1. **Restructure `NAV_CONFIG`** into the final section layout (Req 1–4) and delete the dead
   placeholder routes/pages.
2. **Replace the per-item `bottomNav` flag with role profiles** (Req 5) rendered by `BottomNav`,
   including two dynamic slots that depend on the working project.

Plus one backend seed: `FOREMAN` × `COMPANY_SCHEDULE` READ (Req 6).

Repositories (per `.kiro/steering/git-repo-structure.md`): frontend code is committed from inside
`foremen-frontend/`; the changeset, `changelog.xml` and spec files are committed from the root repo.

## 1. Target `NAV_CONFIG`

```ts
export const NAV_CONFIG: NavSectionConfig[] = [
  { titleKey: null, items: [dashboard, addExpense] },
  { titleKey: 'nav.sections.company',
    items: [staffingGantt, companyProcurement, companyPayroll, bankTransactions] },
  { titleKey: 'nav.sections.resources',
    items: [deliveries, constructionMaterials, finishingMaterials, workCatalog] },
  { titleKey: 'nav.sections.dictionaries', items: [/* unchanged */] },
  { titleKey: 'nav.sections.system',
    items: [users, roles, audit, documentTypes, documentTemplates, bank] },
  { titleKey: 'nav.sections.settings', items: [appearance] },
]
```

Removed from the array: the `Catalog` section (`/catalog/works` moves to Resources; `/catalog/prices`
and `/catalog/material-consumption` leave the menu), the `Warehouse` section (`/finances` removed;
`/deliveries`, `/materials/construction`, `/materials/finishing` move to Resources).

`NavItemConfig.bottomNav` is **removed**. `Sidebar`/`Drawer` do not read it; only `BottomNav` and
two tests do.

### Stable item ids

To reference items from profiles without duplicating paths, `NavItemConfig` gains an optional
`id: string` (set for the items that can appear in a bottom bar: `addExpense`, `staffingGantt`,
`companyProcurement`, `bankTransactions`). A helper `findNavItem(id)` returns the item.

## 2. Bottom-nav profiles

```ts
export type BottomSlot =
  | { kind: 'item'; id: string }                // static NAV_CONFIG item, permission via isNavItemVisible
  | { kind: 'activeProject' }                   // dynamic, PROJECTS/READ
  | { kind: 'addWorkReport' }                   // dynamic, WORK_REPORTS/CREATE

const MANAGER_SLOTS: BottomSlot[] = [
  { kind: 'activeProject' },
  { kind: 'item', id: 'staffingGantt' },
  { kind: 'item', id: 'companyProcurement' },
  { kind: 'item', id: 'bankTransactions' },
]

export const BOTTOM_NAV_PROFILES: Record<string, BottomSlot[]> = {
  WORKER:    [activeProject, addWorkReport, { kind: 'item', id: 'addExpense' }],
  FOREMAN:   [activeProject, { kind: 'item', id: 'staffingGantt' }, { kind: 'item', id: 'addExpense' }],
  FINANCIER: [activeProject, { kind: 'item', id: 'companyProcurement' }, { kind: 'item', id: 'bankTransactions' }],
  MANAGER:   MANAGER_SLOTS,
  ADMIN:     MANAGER_SLOTS,
}
export const DEFAULT_BOTTOM_SLOTS: BottomSlot[] = [{ kind: 'activeProject' }]  // other roles
```

`resolveBottomNav(roleCode, hasPermission, workingProjectId)` (pure, in `navigation.ts` or a sibling
`bottom-nav.ts`) returns the resolved list `{ key, icon, labelKey | label, path, matchPrefix }` after
permission filtering. Keeping it pure makes the per-role matrix (Req 9.3) a plain unit test.

### Dynamic slots

| Slot | Gate | Target | Icon / label |
|------|------|--------|--------------|
| `activeProject` | `PROJECTS`/READ | `/projects/:id` if working project set, else `/projects` | `folder-kanban`; label = project name (truncated by the existing `max-w-[56px] truncate`), else `nav.activeProject` |
| `addWorkReport` | `WORK_REPORTS`/CREATE | `/projects/:id/workReporting` if set, else `/projects` | `clipboard-pen`; `nav.addWorkReport` |

`BottomNav` reads `useWorkingProject()` (shared in-module store, so it updates instantly with the
Sidebar button and TopBar chip) and `useAuthStore` for `roleCode`. The project name comes from the
hydrated `project` summary; while loading it falls back to `nav.activeProject` (never a raw key).
`NavItem`'s `bottom-nav` variant is reused; only `path`, `labelKey` and an optional literal `label`
override are needed (add an optional `label?: string` prop that wins over `t(labelKey)`).

Active-state: static items match `pathname === path` (as today); dynamic slots match
`pathname.startsWith('/projects/')` for `activeProject` and `/workReporting` suffix for
`addWorkReport`.

### Where the rest of the menu is

The mobile TopBar already has the menu toggle that opens `Drawer`, which renders every permitted
section/item through the same `isNavItemVisible`. No new "More" button is needed; a test pins that
the toggle is present at mobile width (Req 5.7).

## 3. Removed routes and pages

`router.tsx`: delete the `rooms`, `estimate`, `finances` route entries and the `RoomsPage`,
`EstimatePage`, `FinancesPage` lazy imports; delete the three page files and their tests.
`route-permissions.ts`: delete `'/rooms'`, `'/estimate'`, `'/finances'`. The wildcard route already
renders NotFound. `/projects/:projectId/:tab` is unaffected: `requirementForPath` for
`/projects/42/estimate` keeps resolving through the `:tab` template.

Locale cleanup: grep each candidate key (`nav.rooms`, `nav.estimate`, `nav.finances`,
`nav.sections.warehouse`, `nav.sections.catalog`, `pages.rooms`, `pages.estimate`, `pages.finances`)
across `src/` before removing; keep a key if any non-test file still references it
(`workspace.tab.*` keys are separate and untouched).

`DeliveriesPage` (12-line placeholder) and the `/catalog/*` routes stay as they are.

## 4. Backend seed (Req 6)

`foremen-backend/database_files/changesets/217-grant-foreman-company-schedule.xml`, registered last
in `changelog.xml` (after `216-users-status-check.xml`), modelled on `187-seed-company-schedule-resource.xml`:

- changeSet 1: precondition `SELECT COUNT(*) FROM role_resources rr JOIN roles r ... WHERE r.code='FOREMAN'
  AND res.code='COMPANY_SCHEDULE'` = 0 (`onFail="MARK_RAN"`); insert `role_resources` row.
- changeSet 2: same guard on `role_resource_operations`; insert `READ` only.

`CrossProjectScheduleController` is already annotated `@PermissionResource("COMPANY_SCHEDULE")`; no
Java change. The service is project-scoped (FOR-07-01), so a FOREMAN sees only member projects; the
integration test confirms that rather than assuming it.

Entity-creation checklist (steering `entity-creation-rules`): no new entity/controller, so only the
role-matrix grant applies (step 2 equivalent); no new resource row.

## 5. Testing strategy

- **Unit:** `navigation.test.ts` updated (section order/contents, no `bottomNav` flag, unique paths,
  every item has `requiredPermission` except Dashboard/Settings); `resolveBottomNav` matrix test per
  role, with and without permissions, with and without a working project.
- **Component:** `BottomNav.test.tsx` (per-role rendering, dynamic slot targets, highlight),
  `Sidebar`/`Drawer` tests for the Resources section and the unchanged collapsible Dictionaries.
- **Consistency:** every `NAV_CONFIG` path matches a router route and a `route-permissions` entry
  equal to its `requiredPermission` (Req 9.2).
- **Locale parity:** existing parity test plus assertions for the 3 new keys.
- **Backend:** one integration test class for the FOREMAN grant (200 + member-scoped data); run only
  that class with `--tests` and read the JUnit XML (steering `test-execution-rules`).
- **Browser (test-cases.md):** per-role mobile (390px) and desktop (1280px) checks.

## 6. Risks / decisions

| Item | Decision |
|------|----------|
| Add-work-report has no standalone page | Slot deep-links into the existing `workReporting` project tab; no new screen. A "create report" auto-open (e.g. `?new=1`) is a possible follow-up, not in scope. |
| FOREMAN could not see the company Gantt | Fixed by the 217 grant; scoped by project membership. |
| `ADMIN` bottom bar not specified | Same as MANAGER; trivial to change in the profile map. |
| Orphaned `/catalog/prices`, `/catalog/material-consumption` | Routes kept, menu entries removed; resolved by FOR-04-19-work-catalog-rework: both routes now redirect to `/catalog/works`. |
| Dostawy is still a placeholder | Slot kept on `/deliveries`; new child `FOR-07-07-company-deliveries` replaces the page. |
