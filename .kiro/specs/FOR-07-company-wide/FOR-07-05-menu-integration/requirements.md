# Requirements Document

## Introduction

This spec (**FOR-07-05-menu-integration**) is the **final integration child** of the FOR-07
"company-wide" parent. FOR-07-00..04 and FOR-07-06 each added their own menu slot piecemeal; the
result is a menu with leftover sections (`Catalog`, `Warehouse`), placeholder pages that nobody
uses, and a mobile bottom bar that is the same for every role. This spec **cleans up the unused
menu items and re-arranges the used ones** into a final structure, and makes the mobile bottom
navigation **role-specific**.

It is a **frontend-focused** spec with **one small backend seed** (a `COMPANY_SCHEDULE`/READ grant
for `FOREMAN`, needed by the foreman bottom-nav item). It adds no entity and no table.

### Final menu structure (target)

| Section (PL / RU) | Items |
|-------------------|-------|
| *(no title)* **Panel** | Panel (Dashboard), Dodaj wydatek |
| **Firma** / Компания | Grafik zespołu, Zakupy firmowe, Wynagrodzenia, Operacje bankowe |
| **Zasoby** / Ресурсы (new; replaces `Magazyn` + `Katalog`) | Dostawy, Materiały budowlane, Materiały wykończeniowe, Katalog prac |
| **Słowniki** / Справочники | unchanged (collapsible) |
| **System** | unchanged + `Bank` (bank connections) |
| **Ustawienia** / Настройки | unchanged |

### Terminology

- **Nav_Config** — `NAV_CONFIG` in `foremen-frontend/src/config/navigation.ts`, the single source
  consumed by `Sidebar`, `Drawer` and `BottomNav` through `isNavItemVisible`.
- **Active-project surface** — `WorkingProjectButton` (desktop/tablet Sidebar) and
  `WorkingProjectChip` (mobile TopBar), reading `useWorkingProject()` (FOR-05-01).
- **Bottom_Nav_Profile** — the ordered list of bottom-bar slots configured for one role code.
- **Slot** — one entry of a profile: a static `NAV_CONFIG` item (by path) or a *dynamic* slot
  (`activeProject`, `addWorkReport`) whose target depends on the working project.
- **Main menu (collapsible)** — the mobile `Drawer`, opened from the TopBar menu toggle; contains
  every permitted item regardless of the bottom bar.

### Scope boundaries

- **In scope:** Nav_Config restructuring; removal of unused menu items and their placeholder
  routes/pages/tests; `nav.sections.resources` i18n (PL + RU); role-specific bottom navigation with
  two dynamic slots; one idempotent Liquibase seed granting `FOREMAN` `COMPANY_SCHEDULE`/READ; tests.
- **Out of scope:**
  - The **Dostawy** screen itself (new FOR-07 child spec, see Req 4.2) and the single **Katalog
    prac** screen (`FOR-04-19-work-catalog-rework`). This spec only reserves their menu slots and
    keeps the current routes mapped until those specs land.
  - Any change to the `Słowniki` section (items, order, collapse behavior).
  - Removing ABAC resources (`ROOMS`, `ESTIMATE`, `FINANCES`, `PROJECTS` stay; the change is nav and
    route-level only).
  - A new Add-work-report page: the slot reuses the existing project `workReporting` tab.

## Requirements

### Requirement 1: Remove unused menu items and their placeholder pages

**User Story:** As a user, I want the menu to contain only screens that are actually used, so that I
am not led to empty placeholder pages.

#### Acceptance Criteria

1. THE Nav_Config SHALL contain no `Finances` (`/finances`) item and no `Warehouse`
   (`nav.sections.warehouse`) or `Catalog` (`nav.sections.catalog`) section.
2. THE `/rooms`, `/estimate` and `/finances` top-level routes, their page components
   (`app/pages/RoomsPage`, `EstimatePage`, `FinancesPage` placeholders and the now-unrouted
   `features/rooms/RoomsPage`), their `route-permissions` entries and their tests SHALL be removed.
   The reusable `features/rooms` components (`RoomsList`, forms) stay. Navigating to those paths SHALL render the existing NotFound route.
3. THE project-scoped routes `/projects/:projectId/:tab` (incl. `rooms`, `estimate` tabs) and
   `/projects/:projectId/rooms/:roomId` SHALL remain unchanged.
4. THE `/projects` list route SHALL remain (reachable from the active-project surface); it is not a
   menu item.
5. THE locale keys used only by removed items/pages (`nav.rooms`, `nav.estimate`, `nav.finances`,
   `nav.sections.warehouse`, `nav.sections.catalog`, `pages.rooms.*`, `pages.estimate.*`,
   `pages.finances.*`) SHALL be removed from both `pl.json` and `ru.json`, after a repository-wide
   grep confirms no other usage.
6. THE `/catalog/prices` and `/catalog/material-consumption` menu items SHALL be removed from the
   menu; their routes and `route-permissions` entries SHALL remain until
   `FOR-04-19-work-catalog-rework` replaces them with the single Katalog prac screen.
7. THE ABAC resources behind removed items (`ROOMS`, `ESTIMATE`, `FINANCES`, `WORK_PRICES`,
   `WORK_MATERIAL_CONSUMPTION`) SHALL NOT be touched.

### Requirement 2: Final top-of-menu and Company section

**User Story:** As a manager or financier, I want the company-wide screens grouped under one
section in a predictable order.

#### Acceptance Criteria

1. THE first (untitled) section SHALL contain exactly `Panel` (`/`) followed by `Dodaj wydatek`
   (`/expenses/new`, gated `EXPENSE_UPLOAD`/CREATE).
2. THE `nav.sections.company` section SHALL contain, in this order: Grafik zespołu
   (`/company/staffing-gantt`), Zakupy firmowe (`/company/procurement`), Wynagrodzenia
   (`/company/payroll`), Operacje bankowe (`/company/bank-transactions`), each keeping its current
   `requiredPermission` (`COMPANY_SCHEDULE`, `COMPANY_PURCHASES`, `PAYROLL`, `BANK_LEDGER`; READ).
3. THE `Bank` item (`/bank`, `BANK_CONNECTIONS`/READ) SHALL remain in the `System` section, after
   the document-signing items.

### Requirement 3: Unchanged sections

**User Story:** As an administrator, I want Dictionaries, System and Settings to keep working
exactly as before.

#### Acceptance Criteria

1. THE `Słowniki` section SHALL keep its items, order and collapsible behavior (Sidebar and Drawer).
2. THE `System` section SHALL keep Users, Roles, Audit, Document types, Document templates, Bank.
3. THE `Ustawienia` section (Wygląd) SHALL be unchanged.

### Requirement 4: Resources section with reserved slots

**User Story:** As a user, I want materials, deliveries and the work catalog in one place.

#### Acceptance Criteria

1. THE Nav_Config SHALL contain a section `nav.sections.resources` (PL **Zasoby**, RU **Ресурсы**)
   placed after `Firma` and before `Słowniki`, with items in this order: Dostawy (`/deliveries`,
   `DELIVERIES`/READ), Materiały budowlane (`/materials/construction`, `MATERIALS_CONSTRUCTION`/READ),
   Materiały wykończeniowe (`/materials/finishing`, `MATERIALS_FINISHING`/READ), Katalog prac
   (`/catalog/works`, `WORK_CATALOG`/READ).
2. THE **Dostawy** slot SHALL keep pointing at the current `/deliveries` route until a new FOR-07
   child spec (`FOR-07-07-company-deliveries`, registered in the FOR-07 OVERVIEW by this spec)
   delivers the real screen; that spec swaps the page only, not the menu entry.
3. THE **Katalog prac** slot SHALL keep pointing at `/catalog/works` until
   `FOR-04-19-work-catalog-rework` delivers the single screen; that spec may change the path and
   permission in one place (Nav_Config + route map).
4. THE section SHALL be suppressed (header included) when all its items are filtered out by
   permission, as for every other section.

### Requirement 5: Role-specific mobile bottom navigation

**User Story:** As a worker, foreman, financier or manager on a phone, I want the bottom bar to hold
the actions I use most, and everything else in the menu.

#### Acceptance Criteria

1. THE `BottomNav` SHALL render the Bottom_Nav_Profile of the signed-in user's `roleCode`:

   | Role | Slots (left to right) |
   |------|-----------------------|
   | `WORKER` | Active project, Add work report, Dodaj wydatek |
   | `FOREMAN` | Active project, Grafik zespołu, Dodaj wydatek |
   | `FINANCIER` | Active project, Zakupy firmowe, Operacje bankowe |
   | `MANAGER` | Active project, Grafik zespołu, Zakupy firmowe, Operacje bankowe |
   | `ADMIN` | same as `MANAGER` |
   | any other role (e.g. `CLIENT`, `ESTIMATOR`) | Active project only |

2. THE per-item `bottomNav: boolean` flag in Nav_Config SHALL be replaced by the profile mechanism
   (single source: a `BOTTOM_NAV_PROFILES` map in `navigation.ts`); `Sidebar` and `Drawer` SHALL be
   unaffected.
3. EVERY static slot SHALL still pass `isNavItemVisible`; a slot the user lacks permission for SHALL
   be omitted (the bar never shows a link the user cannot open).
4. THE **Active project** slot SHALL: show the working project (name, truncated) and link to
   `/projects/:workingProjectId` when a working project is set and hydrated; otherwise show the
   `workspace.chooseProject` label and link to `/projects`. It SHALL be visible to every role that
   holds `PROJECTS`/READ and SHALL read the same `useWorkingProject()` state as the Sidebar button
   and TopBar chip.
5. THE **Add work report** slot SHALL link to `/projects/:workingProjectId/workReporting` when a
   working project is set, gated by `WORK_REPORTS`/CREATE; with no working project it SHALL link to
   `/projects` (the user picks a project first). It SHALL NOT be shown to users without
   `WORK_REPORTS`/CREATE.
6. THE bar SHALL hold at most 5 slots; the active slot is highlighted by matching the current path
   (prefix match for the dynamic slots).
7. EVERY item not on the user's bottom bar SHALL remain reachable through the collapsible main menu:
   the mobile TopBar menu toggle SHALL open the `Drawer`, which lists all permitted sections/items.
8. THE desktop/tablet layout SHALL be unaffected (the bottom bar is rendered on mobile only).

### Requirement 6: FOREMAN access to the company schedule

**User Story:** As a foreman, I want the company staffing Gantt in my bottom bar.

#### Acceptance Criteria

1. A new idempotent Liquibase changeset (`217-grant-foreman-company-schedule.xml`, registered last
   in `changelog.xml`, guarded by `onFail="MARK_RAN"` preconditions) SHALL add a `role_resources`
   row and a `READ` operation row for `FOREMAN` × `COMPANY_SCHEDULE`. No other role/resource grants
   change; no CREATE/UPDATE/DELETE is added.
2. THE backend `GET /api/company-schedule` SHALL return, for a FOREMAN, only data from projects the
   caller is a member of (existing project-scope filtering of FOR-07-01); ADMIN stays unfiltered.
   An integration test SHALL assert that a FOREMAN now gets `200` with member-project data only
   (before the changeset the same call returned `403`).
3. THE `FOREMAN` Gantt route guard (`/company/staffing-gantt`) SHALL admit a user holding
   `COMPANY_SCHEDULE`/READ without further changes (Nav_Config and `route-permissions` already use
   that pair).

### Requirement 7: Internationalization

**User Story:** As a PL or RU user, I want every new or changed label in my language.

#### Acceptance Criteria

1. THE spec SHALL add these keys to **both** `pl.json` and `ru.json` with identical key sets
   (locale-parity test stays green):

   | Key | PL | RU |
   |-----|----|----|
   | `nav.sections.resources` | Zasoby | Ресурсы |
   | `nav.activeProject` (bottom slot fallback label) | Projekt | Проект |
   | `nav.addWorkReport` | Dodaj raport | Добавить отчёт |

2. THE existing keys `nav.dashboard`, `nav.addExpense`, `nav.deliveries`,
   `nav.constructionMaterials`, `nav.finishingMaterials`, `nav.workCatalog`, `nav.staffingGantt`,
   `nav.companyProcurement`, `nav.companyPayroll`, `nav.bankTransactions` SHALL be reused; the PL
   values SHALL read exactly: Panel, Dodaj wydatek, Dostawy, Materiały budowlane, Materiały
   wykończeniowe, Katalog prac, Grafik zespołu, Zakupy firmowe, Wynagrodzenia, Operacje bankowe.
3. NO backend entity field requires i18n variants (no `nameRU`/`namePL` fields are added).

### Requirement 8: Notifications

**User Story:** As a product owner, I want an explicit statement of the notification set.

#### Acceptance Criteria

1. THIS spec SHALL emit **no** notifications and add **no** notification types: it restructures
   navigation and grants one read permission; it introduces no domain state change and no
   recipient. No use of the generic notification service is required.

### Requirement 9: Regression safety

**User Story:** As a developer, I want the restructure to be covered by tests so that no screen
becomes unreachable.

#### Acceptance Criteria

1. THE `navigation.test.ts`, `BottomNav.test.tsx`, `Sidebar`/`Drawer` tests, `route-permissions`
   tests and locale-parity tests SHALL be updated to the new structure and pass.
2. A test SHALL assert that every `NAV_CONFIG` path resolves to a registered router route and a
   `route-permissions` requirement equal to the item's `requiredPermission`.
3. A test SHALL assert, per role profile in Req 5.1, the exact visible bottom-bar slots for a user
   holding the role's real permissions (and a reduced set when a permission is missing).
4. `test-cases.md` SHALL be authored per the `.kiro/steering/test-cases.md` standard (browser
   scenarios per role at mobile and desktop viewports, MD report template, regression group).
