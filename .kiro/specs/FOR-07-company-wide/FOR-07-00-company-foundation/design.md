# Design Document: FOR-07-00 — Company foundation

## 1. Overview

FOR-07-00 is the foundation layer for the company-wide (cross-project) area. It is a **frontend-only,
menu-realignment** spec — **no** backend entity, **no** table, **no** Liquibase changeset, **no** new
ABAC resource. It does two things:

1. **Remove three project-scoped items from the main menu** (`NAV_CONFIG`): `Projects`/`/projects`,
   `Rooms`/`/rooms`, `Estimate`/`/estimate`.
2. **Add a `nav.sections.company` section with two items** that reuse existing resources:
   - Staffing Gantt → `WORK_SCHEDULE`/READ
   - Company procurement → `PURCHASES`/READ

The Bank / Finance surface (FOR-07-04) is **not** added as a menu item here — it has no existing
resource to reuse, so its slot is deferred to FOR-07-04.

Both `isNavItemVisible` (the nav visibility rule) and `ProjectScopedService` (the project-scope list
filter) are reused **unchanged**. The key insight that makes "reuse existing resources + honor
project scope" work without any new code in 00 is that the services behind `WORK_SCHEDULE` and
`PURCHASES` already filter their LIST reads to the caller's accessible projects; a company list built
on them inherits that filter for free.

### Single git repo

Per `.kiro/steering/git-repo-structure.md`, this spec touches **only the frontend repo**
(`foremen-frontend/`): the `NAV_CONFIG` realignment and the `pl.json`/`ru.json` nav keys. It needs a
**single commit, from inside `foremen-frontend/`**. There is no root-repo (backend/changeset) change
beyond this spec's own documents under `.kiro/`.

## 2. Reused mechanisms (not re-implemented)

| Mechanism | Location | Reuse |
|-----------|----------|-------|
| `isNavItemVisible(item, hasPermission)` | `foremen-frontend/src/config/navigation.ts` | Unchanged. An item with a `requiredPermission` is visible only when the predicate grants it. The two company items reuse `WORK_SCHEDULE` / `PURCHASES`, so any caller already granted those at READ sees them. |
| `NAV_CONFIG` shape (`NavSectionConfig` / `NavItemConfig`) | `.../config/navigation.ts` | Unchanged. The realignment edits the section array only. |
| `requirementForPath` + the Route_Requirement_Map | `.../config/route-permissions.ts` | Unchanged. The `/projects` exact entry stays; no `/company/*` entry is added in 00. |
| Active-project surface | `.../components/shell/WorkingProjectButton.tsx`, `WorkingProjectChip.tsx` | Unchanged. Both already route to `/projects` ("choose / change") and `/projects/:projectId` (open), so project selection/entry survives the `Projects` menu-item removal. |
| `WORKSPACE_TABS` (`rooms`, `estimate` tabs) | `.../features/project-workspace/workspaceTabs.ts` | Unchanged. The in-workspace surfaces that supersede the removed `/rooms` and `/estimate` menu items. |
| `ProjectScopedService.addRequiredQuery()` + `ProjectAccessCache` | `foremen-backend/.../service/ProjectScopedService.java` | Unchanged. Already restricts non-ADMIN LIST reads to the caller's member projects; ADMIN is unfiltered. The FOR-05-10 schedule service and FOR-06-03/04 procurement service implement it, so the company read models inherit project-scope honoring. |

## 3. Main-menu realignment (frontend)

### 3.1 Removed items

The first (unnamed) `NAV_CONFIG` section currently holds four items. The realignment leaves only
`Dashboard` and removes the other three:

| Item | Path | Resource | Decision | Why it is safe to remove from the menu |
|------|------|----------|----------|-----------------------------------------|
| Dashboard | `/` | — | **keep** | The company landing surface. |
| Projects | `/projects` | `PROJECTS`/READ | **remove from menu** | The active-project surface (`WorkingProjectButton` / `WorkingProjectChip`) routes to `/projects` for "choose / change project"; the `/projects` route + `ProjectsPage` stay, so project list/management is not orphaned (R1.3). |
| Rooms | `/rooms` | `ROOMS`/READ | **remove from menu** | Reachable as the `rooms` workspace tab (FOR-05-02 "Pomieszczenia"). |
| Estimate | `/estimate` | `ESTIMATE`/READ | **remove from menu** | Reachable as the `estimate` workspace tab (FOR-05-05 "Kosztorys"). |

The `/projects`, `/rooms`, `/estimate` **routes**, their pages (`ProjectsPage`, `RoomsPage`,
`EstimatePage`), and their `route-permissions` entries are **left intact** in 00. Final route
retirement (and removing `RoomsPage` / `EstimatePage` + their route-requirement entries, if the team
decides to drop the standalone pages) is owned by **FOR-07-05**, so deep links keep working mid-epic
and 00 stays a minimal, reversible nav change.

### 3.2 Added section

A new section is appended to `NAV_CONFIG` (placed after the first/unnamed section, before `catalog`,
so the company surfaces sit near the top like the other primary areas — exact position is a
presentational choice; the tests assert membership and gating, not index):

```ts
{
  titleKey: 'nav.sections.company',
  items: [
    {
      path: '/company/staffing-gantt',
      labelKey: 'nav.staffingGantt',
      icon: 'calendar-range',
      bottomNav: false,
      requiredPermission: { resource: 'WORK_SCHEDULE', operation: 'READ' },
    },
    {
      path: '/company/procurement',
      labelKey: 'nav.companyProcurement',
      icon: 'shopping-cart',
      bottomNav: false,
      requiredPermission: { resource: 'PURCHASES', operation: 'READ' },
    },
  ],
}
```

Icons are Lucide names consistent with the existing config (`calendar-range`, `shopping-cart`); the
owning child may adjust its item's icon when it ships. **No new resource is introduced** — both items
reuse resources already seeded by FOR-05-10 (`WORK_SCHEDULE`, changeset 153) and FOR-06-03
(`PURCHASES`, changeset 169). A caller already granted `WORK_SCHEDULE`/READ or `PURCHASES`/READ sees
the matching item immediately (R2.3); no child needs to seed anything to make the item appear.

### 3.3 Route-requirement map

No `/company/*` entry is added to `route-permissions.ts` in 00: the routes do not yet exist (their
pages are delivered by FOR-07-02/03), and the menu items are reserved slots. Each owning child
registers its own route + route-requirement entry; FOR-07-05 does the final cross-surface gating
verification. (When the owning child adds the `/company/*` route, it will gate it on the same reused
resource the menu item uses, keeping the deep-link guard and the menu agreed.)

### 3.4 `bottomNav` and surfaces

The two new items set `bottomNav: false`, so they appear in the Sidebar (desktop/tablet) and Drawer
(mobile) but not the mobile BottomNav, consistent with most non-primary items. The removed `Projects`
item had `bottomNav: true`; its removal shrinks the BottomNav set by one — acceptable because the
active-project surface already provides project entry on mobile (the `WorkingProjectChip` in the
`TopBar`).

## 4. Project-scope honoring (no code in 00; recorded invariant)

The reuse of a *project* resource (`WORK_SCHEDULE`, `PURCHASES`) for a *company* list is safe because
the backing services are `ProjectScopedService` implementations whose `addRequiredQuery()`:

- returns `null` (no filter) for an ADMIN authority → ADMIN sees **all** projects;
- restricts the resolved `project.id` path to `allowedProjectIds(userId)` (from `ProjectAccessCache`)
  for a non-ADMIN → MANAGER/FINANCIER see **only their member projects**;
- matches nothing for an unauthenticated caller or an empty allowed set.

So a "company" list is simply the project-scoped LIST read **without** a single-project filter: the
inherited project-membership filter already narrows it to the caller's accessible projects. The
cross-project read models that aggregate across those accessible projects are built in FOR-07-01/02
(schedule) and FOR-07-03 (procurement) on top of these services. **FOR-07-00 writes none of that
logic** — it only reserves the menu slots and must not weaken the filter (it changes only the menu).

This section is a recorded invariant for the downstream children; it has no implementation in 00.

## 5. i18n keys (PL + RU, mirrored)

Added to both `src/locales/pl.json` and `src/locales/ru.json` under the `nav` block:

| key | PL | RU |
|-----|----|----|
| `nav.sections.company` | `Firma` | `Компания` |
| `nav.staffingGantt` | `Grafik zespołu` | `График команды` |
| `nav.companyProcurement` | `Zakupy firmowe` | `Закупки компании` |

The existing `nav.projects`, `nav.rooms`, `nav.estimate` keys are **not** removed in 00 (R5.3) —
their routes/pages remain, and removing them is deferred to FOR-07-05. The `nav.sections.company`
key is added inside the existing `nav.sections` object, mirroring `catalog` / `warehouse` /
`dictionaries` / `system` / `settings`. There is **no** backend i18n in this spec (no entity, no
resource row seeded).

## 6. Correctness properties

- **P1 (removed-items absent).** After the realignment, `NAV_CONFIG` contains no item whose `path`
  is `/projects`, `/rooms`, or `/estimate`; the first section contains only `Dashboard`.
- **P2 (projects route survives).** `requirementForPath('/projects')` still resolves to
  `{ PROJECTS, READ }`, and the active-project surface's navigate targets (`/projects`,
  `/projects/:projectId`) are unaffected.
- **P3 (company section present).** `NAV_CONFIG` contains a `nav.sections.company` section with
  exactly the two items at the specified paths, labels, and reused `requiredPermission` resources
  (`WORK_SCHEDULE`, `PURCHASES`).
- **P4 (visible under the reused grant).** Given a predicate that grants `WORK_SCHEDULE:READ`,
  `isNavItemVisible` returns `true` for the Staffing Gantt item; given `PURCHASES:READ`, `true` for
  the Company procurement item; given neither, each is hidden.
- **P5 (no resource change).** No ABAC resource or grant is added, renamed, or altered — the backend
  is untouched; the app starts unchanged.
- **P6 (locale parity).** `pl.json` and `ru.json` have identical key sets after the change (the
  locale-parity test passes), including the three added `nav.*` keys.
- **P7 (scope invariant preserved).** 00 makes no change to `ProjectScopedService` or any service, so
  the project-scope filter on `WORK_SCHEDULE` / `PURCHASES` LIST reads is unchanged (the company read
  models in FOR-07-01/02/03 inherit it).

## 7. Testing strategy

- **Frontend unit.** A `navigation` test asserting P1 (three items absent, `Dashboard` kept), P3
  (company section + two items with correct paths/labels/reused resources), P4 (`isNavItemVisible`
  visible under the matching reused grant, hidden under neither); a `route-permissions` regression
  asserting P2 (`/projects` still `{ PROJECTS, READ }`); the locale-parity test (P6).
- **Backend.** None required — no backend change. (P5/P7 are verified by the absence of any backend
  diff and an unchanged app start.)
- **Per `.kiro/steering/test-execution-rules.md`:** no Gradle run is needed for this spec; if a smoke
  check of app start is desired, it is a compile-only / startup check, not the full suite.
- **API/UI test-cases.md** scenarios: a browser-engine check that the three removed items are gone,
  the two company items are present and gated by the reused resources, project selection still works
  via the active-project surface, and (where a two-project fixture exists) that a non-ADMIN caller's
  company list is narrowed to their member projects while ADMIN sees all.
