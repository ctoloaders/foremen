# Requirements Document

## Introduction

This spec (**FOR-07-00-company-foundation**) is the **foundation child** of the FOR-07
"company-wide / cross-project concerns" parent. Like FOR-06-00 for the execution stage, it delivers
the shared scaffolding every other FOR-07 child plugs into, without delivering any of their feature
domains. It is a **frontend-only, menu-realignment** spec: it adds **no** backend entity, **no**
database table, and **no** new ABAC resource.

It has two parts:

1. **Remove three project-scoped items from the main menu.** The top-level navigation
   (`NAV_CONFIG` in `foremen-frontend/src/config/navigation.ts`) drops `Rooms` (`/rooms`) and
   `Estimate` (`/estimate`) — already reachable as project workspace tabs (FOR-05-02 "Pomieszczenia",
   FOR-05-05 "Kosztorys") — and `Projects` (`/projects`) — project selection/entry already lives in
   the always-visible active-project surface (the `WorkingProjectButton` on desktop/tablet and the
   `WorkingProjectChip` on mobile, FOR-05-01). This is a **menu-only** removal; the routes, pages,
   and ABAC resources stay.

2. **Add a company-wide menu section with two items, reusing existing resources.** A new
   `nav.sections.company` section is added with the two cross-project surfaces that build on domain
   that already exists:
   - **Staffing Gantt** (FOR-07-02) — gated on the **existing** `WORK_SCHEDULE` resource at READ.
   - **Company procurement** (FOR-07-03) — gated on the **existing** `PURCHASES` resource at READ.

   No new resource is seeded. Managers and financiers who already hold `WORK_SCHEDULE`/READ or
   `PURCHASES`/READ see the respective company menu item. The **Bank / Finance** surface (FOR-07-04)
   is **not** added as a menu item here — it is genuinely new persistence with no existing resource
   to reuse, so its menu slot is deferred to FOR-07-04, which introduces its own gating.

### Project-scope honoring (important)

Reusing `WORK_SCHEDULE` / `PURCHASES` does **not** mean a user sees every project's data. The
backend services behind these resources (the FOR-05-10 planning-schedule service and the FOR-06-03/04
procurement service) are `ProjectScopedService` implementations: their LIST reads already run through
the inherited `addRequiredQuery()` project filter, which restricts non-ADMIN callers to the projects
they are a member of (via `ProjectAccessCache`) and lets ADMIN see all. Therefore a cross-project
company list built on these services **automatically shows only data within the caller's accessible
projects** — ADMIN sees all projects, a MANAGER/FINANCIER sees only their member projects. FOR-07-00
itself adds no scoping logic; it only reserves the menu slots. The cross-project read models that
aggregate across the caller's accessible projects are delivered by FOR-07-01/02 (schedule) and
FOR-07-03 (procurement), each honoring this filter.

### Terminology

- **Active-project surface** — the always-visible working-project control in the app shell: the
  `WorkingProjectButton` (desktop/tablet, rendered by `Sidebar`) and the `WorkingProjectChip`
  (mobile, rendered by `TopBar`). Both read `useWorkingProject()` and route to `/projects` for
  "choose / change project" and to `/projects/:projectId` to open the working project (FOR-05-01).
- **Menu-only removal** — deleting an item from `NAV_CONFIG` so it no longer appears in the Sidebar,
  Drawer, or BottomNav. The item's underlying route and ABAC resource are left intact.
- **Project scope** — the set of projects a non-ADMIN caller may read, resolved from project
  membership by `ProjectScopedService.addRequiredQuery()` / `ProjectAccessCache`; ADMIN is unfiltered.

### Scope boundaries

- **In scope:**
  - Removing the `Projects` (`/projects`), `Rooms` (`/rooms`), and `Estimate` (`/estimate`) entries
    from the first (unnamed) `NAV_CONFIG` section.
  - Adding a new `nav.sections.company` menu section with two items: Staffing Gantt
    (`/company/staffing-gantt`, `WORK_SCHEDULE`/READ) and Company procurement (`/company/procurement`,
    `PURCHASES`/READ), each `requiredPermission`-gated.
  - The `nav.*` i18n keys (PL + RU) for the new section title and the two new labels, mirrored
    key-for-key in both locale files.
  - Keeping the `/projects` route and `ProjectsPage` intact (the active-project surface's target), so
    removing the menu item does not orphan project list/management.
- **Out of scope (Non-Goals):**
  - **Any new ABAC resource** — no `COMPANY`, `COMPANY_SCHEDULE`, `COMPANY_PURCHASES`, or
    `BANK_LEDGER`. The two company items reuse the existing `WORK_SCHEDULE` and `PURCHASES` resources.
  - **Any backend entity, table, or Liquibase changeset.** This spec touches only the frontend repo.
  - **The company feature domains themselves** — the cross-project schedule read model + staffing
    Gantt (FOR-07-01/02) and company procurement with nullable `projectId` (FOR-07-03). This spec only
    reserves their two menu slots.
  - **The Bank / Finance menu item** — deferred to FOR-07-04, which introduces its own gating. No
    bank menu slot is added here.
  - **Retiring the `/projects`, `/rooms`, `/estimate` routes / pages, deleting `RoomsPage` /
    `EstimatePage`, and removing their `route-permissions` entries** — final route retirement and the
    full nav permission-gating verification across Sidebar/Drawer/BottomNav are owned by FOR-07-05
    (menu integration). This spec is a menu-entry change only, so deep links keep working mid-epic.
  - **Changing `isNavItemVisible`, the route-requirement resolver (`requirementForPath`),
    `ProjectScopedService`, or the active-project surface components** — all reused unchanged.

## Requirements

### Requirement 1 — Remove project-scoped items from the main menu

**User Story:** As a user, I want the main menu to show only company-wide and cross-cutting surfaces,
so project-specific screens are reached through the project workspace rather than duplicated as
top-level menu items.

#### Acceptance Criteria

1. WHEN `NAV_CONFIG` is read THEN the first (unnamed) section SHALL contain the `Dashboard` (`/`)
   item and SHALL NOT contain the `Projects` (`/projects`), `Rooms` (`/rooms`), or `Estimate`
   (`/estimate`) items.
2. WHEN the Sidebar, Drawer, or BottomNav renders THEN none of the three removed items SHALL appear,
   for any role.
3. WHERE the `Projects` menu item is removed THE `/projects` route, `ProjectsPage`, and the
   `/projects` entry in the route-requirement map SHALL remain intact, so the active-project surface
   can still route to `/projects` for "choose / change project" without a broken link.
4. WHERE the `Rooms` and `Estimate` menu items are removed THE project workspace tabs that supersede
   them (the `rooms` and `estimate` entries in `WORKSPACE_TABS`) SHALL be unchanged, so those
   surfaces stay reachable inside the project workspace.
5. THE removal SHALL NOT change the `PROJECTS`, `ROOMS`, or `ESTIMATE` ABAC resources, grants, or any
   backend behaviour — it is a nav-only change.

### Requirement 2 — Add the company-wide menu section (reusing existing resources)

**User Story:** As a manager or financier, I want the company staffing Gantt and company procurement
surfaces to appear as top-level menu items gated by the permissions I already hold, so I can reach
the cross-project lists without a new permission grant.

#### Acceptance Criteria

1. THE SYSTEM SHALL add a new `NAV_CONFIG` section titled `nav.sections.company` containing exactly
   two items, in this order:
   - Staffing Gantt — `path: '/company/staffing-gantt'`, `requiredPermission: { resource:
     'WORK_SCHEDULE', operation: 'READ' }`, `labelKey: 'nav.staffingGantt'`.
   - Company procurement — `path: '/company/procurement'`, `requiredPermission: { resource:
     'PURCHASES', operation: 'READ' }`, `labelKey: 'nav.companyProcurement'`.
2. WHERE each new item is declared THE SYSTEM SHALL set an icon and a `bottomNav` flag consistent
   with the existing items, and a mandatory `requiredPermission` at `READ` on the reused resource.
3. WHEN a caller holds `WORK_SCHEDULE`/READ THEN the Staffing Gantt item SHALL be visible to that
   caller; WHEN a caller holds `PURCHASES`/READ THEN the Company procurement item SHALL be visible;
   otherwise each item SHALL be hidden (via `isNavItemVisible`, reused unchanged).
4. THE SYSTEM SHALL NOT seed, rename, or alter any ABAC resource or grant — `WORK_SCHEDULE` and
   `PURCHASES` are consumed exactly as the project surfaces already define them.
5. THE two new paths SHALL NOT yet resolve to a page in this spec — the routes are wired by the
   owning child (FOR-07-02/03) and finalized by FOR-07-05; the menu item is a reserved slot only.

### Requirement 3 — Project-scope honoring is preserved

**User Story:** As a company user, I want the cross-project lists to show only data from the projects
I am allowed to see, so reusing a project resource for a company view does not leak other projects'
data.

#### Acceptance Criteria

1. THE reuse of `WORK_SCHEDULE` / `PURCHASES` for the company menu items SHALL NOT bypass or weaken
   the existing `ProjectScopedService` project filter; the company read models (delivered by
   FOR-07-01/02/03) SHALL read through the same project-scoped services whose LIST reads are already
   restricted to the caller's accessible projects.
2. WHEN a non-ADMIN caller (e.g. MANAGER/FINANCIER) opens a company list THEN it SHALL show only rows
   belonging to projects the caller is a member of (per `ProjectAccessCache`); WHEN an ADMIN opens it
   THEN it SHALL show all projects.
3. FOR-07-00 itself SHALL add no project-scoping logic — the guarantee is inherited from the existing
   services; this requirement records the invariant the FOR-07-01/02/03 children must honor and that
   this spec must not undermine (it changes only the menu).

### Requirement 4 — No new notifications

#### Acceptance Criteria

1. THE SYSTEM SHALL emit **no** user-facing notifications in this spec. It is pure foundation (a
   main-menu realignment, reusing existing resources, with no backend change); none of its surface is
   a domain state change that notifies a project member or any recipient. Notification-emitting
   behaviour for the company surfaces (if any) belongs to the respective FOR-07 feature children
   (schedule conflicts, procurement re-link, bank reconciliation), each of which will emit via the
   generic per-recipient notification service rather than re-implementing delivery.

### Requirement 5 — Internationalization completeness

#### Acceptance Criteria

1. THE frontend SHALL add the new nav keys — `nav.sections.company`, `nav.staffingGantt`,
   `nav.companyProcurement` — to **both** `src/locales/pl.json` and `src/locales/ru.json` with real
   PL and RU values (no English fallback), and the locale-parity test SHALL pass.
2. THERE SHALL be **no** backend i18n in this spec — no entity is added, and no ABAC resource row is
   seeded, so there are no `name_ru`/`name_pl` (or any backend-localized) fields to define.
3. THE removal of the three menu items SHALL NOT remove any `nav.*` label key still referenced by a
   remaining route or surface — `nav.projects`, `nav.rooms`, and `nav.estimate` MAY remain in the
   locale files if any retained route/page still references them, and SHALL be removed only when
   FOR-07-05 retires those routes.
