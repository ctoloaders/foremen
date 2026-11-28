# Bug report: FOR-07-05-menu-integration

## Round 1 (2026-11-27)

Screen: mobile side menu (`Drawer`, opened from the TopBar hamburger).

- [x] 1. Clicking a side-menu item must navigate and close the menu
  - Triage: observed the route changes but the drawer stays open over the new page, so the user must close it by
    hand. Expected: a click on any menu link navigates and closes the drawer. Layer: frontend. Cause:
    `Drawer.tsx` renders `NavItem` links without any close handler; `AppShell` passes `onClose={toggleSidebar}`,
    which is a toggle, so the fix must call it only while the drawer is open. Fix: close the drawer on a click of
    any link inside the drawer's `<nav>` (also covers the working-project button and a click on the already
    active item, where the pathname does not change). Files: `Drawer.tsx` (+ `Drawer.test.tsx`). No backend, no
    i18n.
  - _Requirements: FOR-07-05 menu (mobile drawer), FOR-02-02 app shell 2.x_
  - _Depends on: none_
- [x] V1. Validate Round 1 on a mobile viewport
  - Open the drawer, tap a menu item: the page changes and the drawer is closed; tap the active item: the drawer
    closes; the backdrop and the close button still close it.
  - _Depends on: 1_

## Parallel execution plan (waves) — Round 1

- **Wave 1:** 1.
- **Wave 2:** V1.

## Round 1 — validation results (2026-11-28)

Local stack (rebuilt backend and frontend), WORKER `info+valera@foremen.eu`, MANAGER `katarzyna@loaders.dev`, SALESMAN `probe.salesman@example.com`.

- Mobile: open the drawer from the report page, tap "Dodaj wydatek": the page changes to `/expenses/new` and the
  drawer is closed. ✅
- Unit tests: a link click closes the drawer, the Dictionaries toggle does not, a closed drawer ignores clicks.
  V1 stays open until dev is redeployed.

## Dev validation (2026-11-28)

Dev (after deploy of root `d29ccff`, frontend `ba22b13`): test users created through the app (MANAGER 20, WORKER 21, `test+qa2-*@example.com`) on test project 31 (set to ACTIVE for the checks, back to CANCELLED after; both users deactivated). On dev the WORKER role holds `PAYROLL` READ and `WORK_CATALOG` READ, but not `WORK_PRICES` or `ESTIMATE`.

- Mobile, WORKER: tap "Dodaj wydatek" in the drawer → `/expenses/new`, drawer closed. The Dictionaries section is not in the WORKER menu. ✅
