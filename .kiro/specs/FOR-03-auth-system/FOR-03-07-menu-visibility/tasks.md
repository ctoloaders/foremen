# Implementation Plan: FOR-03-07 Menu Visibility

## Overview

This plan implements the frontend authorization (visibility) layer for the Foremen web client (`foremen-frontend`, TypeScript / React / Zustand / react-router). It is purely frontend: it consumes the `Current_User.roleCode` + `permissions` that FOR-03-06 already stores in the Auth_Store and turns them into permission-based menu visibility, route guarding, CRUD action gating, and a `/403` page. There are NO backend changes — server-side enforcement (FOR-03-03 / FOR-03-08) and the `/api/auth/me` contract (FOR-03-06) are consumed unchanged.

Work proceeds bottom-up and always ends wired into the running app: first the `usePermission` predicate (the single authorization rule), then the `NAV_CONFIG` `requiredPermission` extension and the `Route_Requirement_Map`, then the navigation filtering, then the `PermissionGuard` + `/403` page + router wiring, then the `DataTable` resource-gating and the Users/Roles/Audit reference pages, then i18n + accessibility, and finally end-to-end wiring, the `test-cases.md` artifact, and a checkpoint. Each step builds on the previous ones; there is no orphaned code.

Test sub-tasks marked with `*` are optional (property / unit tests) and validate the design's seven correctness properties plus example-based coverage. Property tests use **fast-check** (frontend), run a minimum of 100 iterations, and are tagged `Feature: FOR-03-07-menu-visibility, Property {n}: {text}`.

## Tasks

- [x] 1. Permission_Hook (`usePermission`)
  - [x] 1.1 Implement `usePermission` with the grant set and ADMIN bypass
    - Create `foremen-frontend/src/hooks/usePermission.ts` exporting `usePermission(): { hasPermission(resource, operation): boolean }`. Read `user` from `useAuthStore((s) => s.user)`. Build a memoized `Set<string>` of `"<RESOURCE>:<OPERATION>"` keys from `user.permissions` (memoized on the `user` identity). `hasPermission` returns `false` when there is no user (deny-by-default), `true` when `user.roleCode` equals the exact literal `ADMIN` (no case-fold / trim), otherwise the exact-match set membership. Export a `PermissionRequirement` type re-export point if convenient.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_
  - [x] 1.2 Write property test for non-ADMIN membership / deny-by-default
    - **Property 1: hasPermission equals matrix membership for non-ADMIN (deny by default)**
    - **Validates: Requirements 1.3, 1.5**
  - [x] 1.3 Write property test for the ADMIN bypass and its exact-match rule
    - **Property 2: ADMIN is always granted** and **Property 3: ADMIN bypass requires an exact code match**
    - **Validates: Requirements 1.2, 1.6**
  - [x] 1.4 Write property test for the no-current-user deny
    - **Property 4: No current user denies everything**
    - **Validates: Requirements 1.4**

- [x] 2. Navigation requirements and Route_Requirement_Map
  - [x] 2.1 Extend `NAV_CONFIG` with `requiredPermission`
    - Edit `foremen-frontend/src/config/navigation.ts`: add the exported `PermissionRequirement { resource; operation }` type and an optional `requiredPermission?: PermissionRequirement` on `NavItemConfig`. Annotate items per the design table (`/users`→`{USERS,READ}`, `/roles`→`{ROLES,READ}`, `/audit`→`{AUDIT,READ}`, plus the forward-declared project modules `/projects`,`/rooms`,`/estimate`,`/materials`,`/finances`,`/deliveries`); leave `/` and `/settings/appearance` unrestricted.
    - _Requirements: 2.1, 2.2, 2.3, 2.4_
  - [x] 2.2 Implement the Route_Requirement_Map
    - Create `foremen-frontend/src/config/route-permissions.ts` deriving `path → requiredPermission` from `NAV_CONFIG` (plus an empty `extra` map for future non-menu guarded routes) and exporting `requirementForPath(pathname): PermissionRequirement | undefined`. Unmapped paths and `/403` resolve to `undefined` (no requirement).
    - _Requirements: 4.1, 4.4, 5.4_

- [x] 3. Menu visibility filtering
  - [x] 3.1 Filter Sidebar, Drawer, and BottomNav by permission
    - Edit `foremen-frontend/src/app/layout/Sidebar.tsx`, `Drawer.tsx`, and `BottomNav.tsx` to call `usePermission()` and render a Nav_Item only when it has no `requiredPermission` or the requirement is granted. In Sidebar/Drawer, suppress a section's header when all its items are filtered out. In BottomNav, filter the `bottomNav: true` list by the same predicate. Extract a shared `isNavItemVisible(item, hasPermission)` helper (co-located, e.g. in `navigation.ts` or a small util) so all three use one rule.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 8.1_
  - [x] 3.2 Write property test for nav item + section visibility
    - **Property 5: A nav item is visible iff unrestricted or granted**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**
  - [x] 3.3 Update existing NAV_CONFIG / BottomNav count tests and add filtering unit tests
    - Update `src/config/__tests__/navigation.test.ts` and `src/app/layout/__tests__/BottomNav.test.tsx` so they assert permission-filtered behavior (ADMIN sees all bottomNav items; a limited role sees a subset) instead of a fixed count. Add Sidebar/Drawer unit tests for item hide + empty-section header suppression + ADMIN-shows-all.
    - _Requirements: 3.3, 3.4, 3.5_

- [x] 4. Permission route guard, /403 page, go-back tracking, and router wiring
  - [x] 4.1 Implement the Last_Allowed_Location tracking module
    - Create `foremen-frontend/src/lib/last-allowed-location.ts` with module-level `lastAllowed`/`denied` (`string | null`, full `path+query`) and `recordAllowedLocation(loc)` (ignores the `/403` path), `recordDeniedLocation(loc)`, `getLastAllowedLocation()`, `getDeniedLocation()`, and `resolveGoBackTarget()` returning `/` when `lastAllowed` is null or string-equal to `denied`, otherwise `lastAllowed`.
    - _Requirements: 4.4, 4.8, 5.4, 5.5, 5.6, 5.8_
  - [x] 4.2 Implement `PermissionGuard`
    - Create `foremen-frontend/src/app/guards/PermissionGuard.tsx`: read `useLocation()` (pathname + search), look up `requirementForPath`, and render `<Outlet/>` when there is no requirement or `usePermission().hasPermission(...)` grants it; otherwise `<Navigate to="/403" replace />`. Perform no auth check (it is only mounted under the authenticated branch of `ProtectedLayout`). In a `useEffect`, record the resolved location: `recordAllowedLocation(path+search)` when granted (the module ignores `/403`), `recordDeniedLocation(path+search)` when denied. Ensure the `/403` redirect does not write a FOR-03-06 Return_Location.
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8_
  - [x] 4.3 Implement `ForbiddenPage`
    - Create `foremen-frontend/src/app/pages/ForbiddenPage.tsx`: an `<h1>` heading, an explanatory paragraph, and a keyboard-operable Go_Back button whose `onClick` calls `navigate(resolveGoBackTarget())`, all strings via `t('forbidden.*')`. Rendered inside the AppShell.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 8.2_
  - [x] 4.4 Wire `PermissionGuard` and `/403` into the router
    - Edit `foremen-frontend/src/app/router.tsx`: nest the protected child routes under a pathless layout route whose element is `<PermissionGuard/>` (itself under `ProtectedLayout` → `AppShell`), so the guard sees the matched child path. Add the `/403` route as a protected child WITHOUT a requirement (lazy-loaded `ForbiddenPage`). Keep the `*` NotFound catch-all requirement-free.
    - _Requirements: 4.5, 5.1, 5.7_
  - [x] 4.5 Write property + unit tests for the guard and the tracking module
    - **Property 6: The route guard renders iff the requirement is absent or granted** and **Property 8: Go_Back target avoids the denied route and the empty case** plus example tests: no-requirement renders, granted renders + records Last_Allowed_Location, `/403` never recorded, denied → `/403` + records Denied_Location, ADMIN renders, no Return_Location written on the `/403` redirect.
    - **Validates: Requirements 4.2, 4.3, 4.4, 4.6, 4.7, 4.8, 5.4, 5.5, 5.6, 5.8**
  - [x] 4.6 Write unit test for ForbiddenPage
    - Assert the heading + Go_Back control render; clicking Go_Back navigates to the resolved target (last-allowed normally, `/` when absent or equal to the denied location); strings resolve in both PL and RU.
    - _Requirements: 5.2, 5.3, 5.4, 5.5, 5.6, 7.1, 7.2_

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. CRUD action gating
  - [x] 6.1 Add `resource` to `DataTable` and gate the audit button + column collapse
    - Edit `foremen-frontend/src/components/data-table/types.ts` to add `resource?: string` to `DataTableProps<T>`. Edit `DataTable.tsx` to call `usePermission()` and render the composed audit button only when `resource` is set and `hasPermission('AUDIT', 'READ')`; recompute `hasRowActions` as "audit button visible OR the page's `rowActions(row)` returns a non-null/non-empty result" so the actions column and its header collapse entirely when nothing is visible.
    - _Requirements: 6.1, 6.5, 6.6, 8.1, 8.3_
  - [x] 6.2 Gate actions on the Users page
    - Edit `foremen-frontend/src/features/users/UsersPage.tsx`: compute `canCreate/canUpdate/canDelete` via `usePermission()` for resource `USERS`; render the Create button only with CREATE; build `rowActions` so it returns `null` when neither UPDATE nor DELETE is permitted and otherwise renders Edit (UPDATE) and Deactivate (DELETE) conditionally; pass `resource="USERS"` to `DataTable`.
    - _Requirements: 6.2, 6.3, 6.4, 6.6, 6.7, 6.8_
  - [x] 6.3 Gate actions on the Roles and Audit pages
    - Apply the same pattern to `foremen-frontend/src/features/roles/RolesPage.tsx` (resource `ROLES`) and `foremen-frontend/src/features/audit/AuditPage.tsx` (resource `AUDIT`; read-only so Create/Edit/Delete never render, audit column gated by `AUDIT READ`). Pass the `resource` prop to each `DataTable`.
    - _Requirements: 6.1, 6.7, 6.8_
  - [x] 6.4 Write property + unit tests for action gating
    - **Property 7: Row-actions column collapses iff no row-level action is permitted** plus example tests: Create hidden without CREATE, Edit hidden without UPDATE, Delete hidden without DELETE, audit hidden without `AUDIT READ`, ADMIN sees all.
    - **Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 8.3**

- [x] 7. i18n keys for the Forbidden page
  - [x] 7.1 Add `forbidden.*` keys to PL and RU resources
    - Add `forbidden.title`, `forbidden.message`, and `forbidden.goBack` to both `foremen-frontend/src/locales/pl.json` and `src/locales/ru.json`, every key non-blank in both (PL default + RU translation). The Go_Back label should read as "go back" (e.g. RU «Вернуться назад», PL «Wróć»), not "home". Confirm the navigation and CRUD action labels reused by this spec already have keys and need no new entries.
    - _Requirements: 7.1, 7.2, 7.3_

- [x] 8. Final wiring and verification
  - [x] 8.1 Verify the whole flow builds and behaves end-to-end
    - Confirm the frontend builds/lints; manually or via test verify: a limited role sees only permitted nav items and a subset of bottom-nav items; deep-linking to a forbidden route lands on `/403` (with navigation intact) and a later login is not bounced there; on `/403` the Go_Back button returns to the last page the user could view, and goes to `/` when there is no such page or when it is the just-denied route; ADMIN sees every item, route, and action; the row-actions column disappears for a read-only role.
    - _Requirements: 3.1, 4.2, 4.7, 5.1, 5.4, 5.6, 6.6_

- [x] 9. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via a per-run generator/teardown, regression group, MD report template). This spec is UI-facing: author browser-engine scenarios executed by a headless browser / browser automation for menu visibility (limited role vs ADMIN), permission-based route guard (deep-link to a forbidden route → `/403`, granted route renders, no post-login bounce into `/403`), the `/403` page content + Go_Back behavior (returns to the last allowed page; `/` when absent or equal to the denied route), and CRUD action visibility on the Users/Roles/Audit pages (Create/Edit/Delete/Audit shown or hidden per permission, row-actions column collapse). Because visibility depends on the signed-in user's role, use the Dockerized app (`docker compose up`, base URL `http://localhost:8080`, frontend at `http://localhost:3000`, auth under `/api/auth`, ADMIN bootstrap via `FOREMEN_ADMIN_CREATE`/`FOREMEN_ADMIN_EMAIL`/`FOREMEN_ADMIN_PASSWORD`) and drive login through the UI. Ensure repeatability via a unique-email generator (`test+{run-id}@example.com`) for any created non-ADMIN test users and/or teardown (deactivate created users, revoke sessions); include a regression group and the MD report template with tables. Result artifacts are MD reports with tables.
  - _Requirements: 1.2, 3.1, 3.4, 4.2, 4.6, 4.7, 5.1, 5.2, 5.4, 5.5, 5.6, 6.2, 6.3, 6.4, 6.5, 6.6_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; they implement the property / unit tests that validate the design's seven correctness properties and example-based coverage.
- Each task references specific requirement sub-clauses for traceability.
- No backend work: the `/api/auth/me` permission payload (FOR-03-06) and server-side `@RequiresPermission` enforcement (FOR-03-03 / FOR-03-08) are consumed as-is. This spec is a UX/visibility layer only.
- Project-module nav items (`/projects`, `/rooms`, …) carry forward-declared requirements and, until their resources are seeded (FOR-06), are visible only to ADMIN — the correct conservative default.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "4.1", "7.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "1.4", "2.1"] },
    { "id": 2, "tasks": ["2.2", "3.1", "6.1"] },
    { "id": 3, "tasks": ["3.2", "3.3", "4.2", "4.3", "6.2", "6.3"] },
    { "id": 4, "tasks": ["4.4", "4.5", "4.6", "6.4"] },
    { "id": 5, "tasks": ["5", "8.1"] },
    { "id": 6, "tasks": ["9"] },
    { "id": 7, "tasks": ["10"] }
  ]
}
```
