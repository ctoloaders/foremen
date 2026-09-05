# Requirements Document

## Introduction

This specification defines the frontend authorization (visibility) layer for the Foremen web client (FOR-03-07, the seventh child spec of the FOR-03 auth system). Where FOR-03-06 answered *"is the user authenticated"*, this spec answers *"what may the authenticated user see and do"*. It consumes the permission set that FOR-03-06 already stores on the `Current_User` (from `GET /api/auth/me`) and turns it into three visibility effects: permission-based route guards, permission-filtered navigation, and permission-gated CRUD action controls, plus a dedicated Forbidden (`/403`) page.

The feature is purely frontend. It introduces no new backend endpoints, entities, or migrations. The permission source of truth is the `permissions` array already present on the `Current_User` object delivered by FOR-03-06 (`{ resource, operations[] }[]`), and the ADMIN-bypass semantics already established by the backend `ForemenPermissionEvaluator` (FOR-03-03) are mirrored on the client so the UI never shows a control the backend would reject.

The feature introduces:

- A **Permission_Hook** (`src/hooks/usePermission.ts`) exposing `hasPermission(resource, operation): boolean`, derived from the current `Current_User.permissions` in the Auth_Store, applying the ADMIN bypass (a user whose `roleCode` is `ADMIN` is granted every permission without consulting the array).
- A **Permission_Requirement** shape `{ resource: string; operation: string }` attached to each protected navigation item and each protected route, plus a small route → requirement map so a deep-linked route with no matching menu item is still gated.
- Extension of `NAV_CONFIG` (`src/config/navigation.ts`) so each navigation item carries an optional `requiredPermission: Permission_Requirement`; the **Sidebar**, **Drawer**, and **BottomNav** filter their rendered items through the Permission_Hook so a user never sees a link to a section they cannot open.
- A **Permission_Route_Guard** (`src/app/guards/PermissionGuard.tsx`, composed inside the existing `ProtectedLayout` chain) that, for an authenticated user, checks the matched route's Permission_Requirement and redirects to `/403` when the user lacks it. It runs only after authentication has been established, so an unauthenticated user still goes to `/login` (FOR-03-06 behavior), not `/403`.
- A **Forbidden_Page** at `/403` presenting a localized "you do not have access" message and a **Go_Back control** that returns the user to the **Last_Allowed_Location** — the last protected route the user successfully viewed before the denial — with a fallback to the application home route `/` when there is no such location or when it equals the route that triggered the `/403` redirect (so the button can never bounce the user straight back into the same forbidden route).
- Permission-gated **CRUD action controls** on data tables: the "Create" button above a table renders only with `CREATE` on the table's resource; the per-row "Edit" control renders only with `UPDATE`; the per-row "Delete/Deactivate" control renders only with `DELETE`; the per-row "Audit" control renders only with `READ` on the `AUDIT` resource. When a user has none of the row-level operations, the row actions column collapses entirely.
- RU/PL localization of the only new user-facing strings (the Forbidden page). Navigation labels and CRUD action labels already have i18n keys and are reused unchanged.

### Backend contract consumed (already implemented — do not redesign)

- `GET /api/auth/me` (Bearer) → `200` `CurrentUserResponse { id, name, email, roleCode, permissions: [{ resource, operations[] }] }`. This spec reads `roleCode` (for the ADMIN bypass) and `permissions` (resource → allowed operations). The ETag / `304` conditional-request behavior and the store population are delivered by FOR-03-06 and are not changed here.
- Server-side enforcement (`@RequiresPermission` + `PermissionInterceptor` + `ForemenPermissionEvaluator`, FOR-03-03 / FOR-03-08) remains the authoritative access gate. This spec is a UX layer that hides controls the backend would reject; it never becomes the sole line of defense.

### ADMIN bypass parity

The backend grants a role whose code equals the literal `ADMIN` every permission without consulting the matrix (FOR-03-03). The Permission_Hook mirrors this exactly: when `Current_User.roleCode === 'ADMIN'`, `hasPermission` returns `true` for every `(resource, operation)` without inspecting the `permissions` array. Only the exact literal `ADMIN` is bypassed (no case-folding, no trimming), matching the backend's exact-match rule.

### Out of scope

- Any backend change (endpoints, DTOs, entities, migrations, `@RequiresPermission` placement). Backend enforcement and the `/api/auth/me` contract are consumed as delivered by FOR-03-03 / FOR-03-06 / FOR-03-08.
- Project-ownership (`project_members`) row-level data filtering (FOR-03-04); this spec gates by resource/operation only, not by which project rows a user may see.
- The wholesale migration of feature fetchers onto the Api_Client (FOR-03-08).
- Authentication, token handling, refresh, login/OTP/set-password pages, and the authenticated-only guard (all delivered by FOR-03-06 and consumed unchanged).

## Glossary

- **Auth_Store**: The existing Zustand store at `src/stores/auth-store.ts` holding the `Current_User` (with its `permissions` and `roleCode`), delivered by FOR-03-06. This spec reads from it and adds no new session state.
- **Current_User**: The object from `GET /api/auth/me`: `{ id, name, email, roleCode, permissions }` where `permissions` is `{ resource: string; operations: string[] }[]`.
- **Permission_Requirement**: The pair `{ resource: string; operation: string }` a navigation item or route declares as the access it needs (e.g. `{ resource: 'USERS', operation: 'READ' }`).
- **Permission_Hook**: The hook `usePermission()` returning `{ hasPermission(resource, operation): boolean }`, reading the current `Current_User` from the Auth_Store and applying the ADMIN bypass.
- **hasPermission**: The predicate `(resource, operation) => boolean` that is `true` when the current user's `roleCode` is `ADMIN`, or when the user's `permissions` contains an entry for `resource` whose `operations` includes `operation`; otherwise `false` (deny by default, including when there is no `Current_User`).
- **Nav_Item**: A single entry in `NAV_CONFIG`, extended here with an optional `requiredPermission: Permission_Requirement`. An item without a `requiredPermission` is always visible to any authenticated user.
- **Route_Requirement_Map**: The mapping from a protected route path to its Permission_Requirement, used by the Permission_Route_Guard to gate deep-linked routes that may not correspond to a visible menu item.
- **Permission_Route_Guard**: The guard component that, for an authenticated user on a protected route, redirects to `/403` when the user lacks the route's Permission_Requirement. Composed after the FOR-03-06 authentication guard.
- **Forbidden_Page**: The route component at `/403` that presents a localized access-denied message and a Go_Back control that returns to the Last_Allowed_Location (or `/` as a fallback).
- **Last_Allowed_Location**: The most recent protected route (path plus query) that the current user successfully rendered (was granted) before a permission denial. It is tracked as the user navigates and is the target the Go_Back control returns to. It is analogous to the FOR-03-06 Return_Location but is used only for the `/403` "go back" affordance, is tracked separately, and is never a Public_Route.
- **Denied_Location**: The route (path plus query) whose permission requirement was denied, causing the redirect to `/403`. Used to detect the "go back would return to the same forbidden route" case.
- **Go_Back control**: The control on the Forbidden_Page that navigates to the Last_Allowed_Location, or to `/` when there is no Last_Allowed_Location or when the Last_Allowed_Location equals the Denied_Location.
- **CRUD_Action_Controls**: The Create / Edit / Delete(Deactivate) / Audit controls rendered by data-table pages, each gated by a specific `(resource, operation)` via the Permission_Hook.
- **Resource_Code / Operation_Code**: The uppercase string codes used by the backend matrix (e.g. resources `USERS`, `ROLES`, `AUDIT`; operations `CREATE`, `READ`, `UPDATE`, `DELETE`), matched exactly.

## Requirements

### Requirement 1: Permission Hook

**User Story:** As a developer, I want a single hook that answers "may the current user perform operation Y on resource Z", so that every visibility decision in the UI uses one consistent, ADMIN-aware rule.

#### Acceptance Criteria

1. THE Permission_Hook SHALL expose a `hasPermission(resource: string, operation: string): boolean` predicate derived from the current `Current_User` held in the Auth_Store.
2. WHEN the current `Current_User.roleCode` equals the exact literal `ADMIN`, THE hasPermission predicate SHALL return `true` for every `(resource, operation)` pair without inspecting the `permissions` array.
3. WHEN the current `Current_User.roleCode` is not `ADMIN`, THE hasPermission predicate SHALL return `true` if and only if the `permissions` array contains an entry whose `resource` equals the requested resource and whose `operations` includes the requested operation.
4. WHEN there is no `Current_User` in the Auth_Store (unauthenticated or not yet hydrated), THE hasPermission predicate SHALL return `false` for every `(resource, operation)` pair.
5. THE hasPermission predicate SHALL match `resource` and `operation` by exact string equality, so a case-differing or otherwise distinct code SHALL NOT be treated as a grant.
6. THE ADMIN bypass SHALL apply only to the exact literal `ADMIN`; any near-miss `roleCode` (differing by case, surrounding whitespace, or extra characters) SHALL NOT be bypassed and SHALL be evaluated against the `permissions` array.
7. WHEN the `Current_User` in the Auth_Store changes (for example after a re-hydration that reflects a role or permission change), THE Permission_Hook SHALL reflect the new permissions on the next render without a manual refresh.

---

### Requirement 2: Navigation Item Permission Requirements

**User Story:** As a maintainer, I want each navigation item to declare the permission it needs, so that menu visibility is data-driven and stays in sync with the access matrix.

#### Acceptance Criteria

1. THE Nav_Item type in `NAV_CONFIG` SHALL support an optional `requiredPermission: { resource: string; operation: string }` field.
2. THE navigation configuration SHALL assign a `requiredPermission` to each item that maps to a permission-guarded section, using the resource and operation codes from the FOR-03 access matrix (for example `/users` → `{ USERS, READ }`, `/roles` → `{ ROLES, READ }`, `/audit` → `{ AUDIT, READ }`).
3. WHEN a Nav_Item has no `requiredPermission`, THE navigation SHALL treat that item as visible to any authenticated user (no permission gate).
4. THE `requiredPermission` values SHALL be consistent with the Route_Requirement_Map so a menu item and its target route are gated by the same requirement.

---

### Requirement 3: Menu Visibility Filtering

**User Story:** As a signed-in user, I want to see only the sections I can actually open, so that the navigation is not cluttered with links that would deny me access.

#### Acceptance Criteria

1. WHEN the Sidebar renders its items, THE Sidebar SHALL render a Nav_Item only when the item has no `requiredPermission` OR the Permission_Hook grants the item's `requiredPermission`.
2. WHEN the Drawer renders its items, THE Drawer SHALL apply the same per-item permission filter as the Sidebar.
3. WHEN the BottomNav renders its items, THE BottomNav SHALL apply the same per-item permission filter, filtering the `bottomNav: true` items by permission before display.
4. WHEN every item in a navigation section is filtered out by permission, THE Sidebar and Drawer SHALL NOT render that section's header (an empty section produces no visible heading).
5. WHEN the current user is an `ADMIN`, THE navigation SHALL render every item (the ADMIN bypass makes all `requiredPermission` checks pass).
6. WHEN the `Current_User` permissions change, THE navigation SHALL re-evaluate item visibility on the next render.

---

### Requirement 4: Permission-Based Route Guard

**User Story:** As a security-conscious product, I want deep links to protected routes checked against permissions, so that a user cannot reach a page by typing its URL even when its menu link is hidden.

#### Acceptance Criteria

1. THE application SHALL maintain a Route_Requirement_Map associating each permission-guarded route path with its Permission_Requirement, consistent with the navigation `requiredPermission` values.
2. WHILE the user is authenticated AND the matched route has a Permission_Requirement, IF the Permission_Hook denies that requirement, THEN THE Permission_Route_Guard SHALL redirect the browser to `/403`.
3. WHILE the user is authenticated AND the matched route has a Permission_Requirement, IF the Permission_Hook grants that requirement, THEN THE Permission_Route_Guard SHALL render the requested route AND SHALL record that granted route (path plus query) as the Last_Allowed_Location.
4. WHEN the matched route has no Permission_Requirement, THE Permission_Route_Guard SHALL render the route without a permission check (authenticated access is sufficient) AND SHALL record that route (path plus query) as the Last_Allowed_Location, except that it SHALL NOT record the `/403` route itself.
5. THE Permission_Route_Guard SHALL run only after the FOR-03-06 authentication guard has established an authenticated session, so an unauthenticated user is redirected to `/login` (not `/403`) and Session_Hydration in progress still shows the FOR-03-06 loading indication rather than a `/403` redirect.
6. WHEN an `ADMIN` user navigates to any permission-guarded route, THE Permission_Route_Guard SHALL render the route (ADMIN bypass) AND SHALL record it as the Last_Allowed_Location.
7. WHEN a permission denial redirects to `/403`, THE Permission_Route_Guard SHALL NOT capture the denied location as a FOR-03-06 Return_Location, so a later successful login does not bounce the user back into a forbidden route.
8. WHEN a permission denial redirects to `/403`, THE Permission_Route_Guard SHALL record the denied route (path plus query) as the Denied_Location so the Forbidden_Page can avoid returning the user to it.

---

### Requirement 5: Forbidden (403) Page

**User Story:** As a user who reached a page I may not open, I want a clear message and a way back, so that I understand what happened and can continue working.

#### Acceptance Criteria

1. THE application SHALL provide a Forbidden_Page reachable at `/403`, rendered inside the authenticated application shell so an authenticated user retains their navigation.
2. THE Forbidden_Page SHALL present a localized heading and explanatory message indicating the user lacks access to the requested resource.
3. THE Forbidden_Page SHALL present a Go_Back control.
4. WHEN the Go_Back control is activated AND a Last_Allowed_Location exists AND the Last_Allowed_Location is not equal to the Denied_Location, THE Forbidden_Page SHALL navigate the user to the Last_Allowed_Location.
5. WHEN the Go_Back control is activated AND there is no Last_Allowed_Location, THE Forbidden_Page SHALL navigate the user to the application home route (`/`).
6. WHEN the Go_Back control is activated AND the Last_Allowed_Location is equal to the Denied_Location, THE Forbidden_Page SHALL navigate the user to the application home route (`/`), so the control never returns the user straight back into the route that was just denied.
7. THE `/403` route SHALL itself carry no Permission_Requirement, so any authenticated user can render it.
8. THE Forbidden_Page SHALL compare the Last_Allowed_Location and the Denied_Location by their full path-plus-query string, so a location differing only by query string is treated as a different location.

---

### Requirement 6: CRUD Action Visibility

**User Story:** As a signed-in user, I want to see only the table actions I am allowed to perform, so that I am not offered Create/Edit/Delete controls that the server would reject.

#### Acceptance Criteria

1. THE DataTable SHALL accept a resource identifier for the entity it presents, so its action controls can be gated by `(resource, operation)` via the Permission_Hook.
2. WHEN a data-table page renders its "Create" control, THE page SHALL render that control only when the Permission_Hook grants `(resource, CREATE)`.
3. WHEN the DataTable renders the per-row "Edit" control, THE DataTable SHALL render it only when the Permission_Hook grants `(resource, UPDATE)`.
4. WHEN the DataTable renders the per-row "Delete"/"Deactivate" control, THE DataTable SHALL render it only when the Permission_Hook grants `(resource, DELETE)`.
5. WHEN the DataTable renders the per-row "Audit" control, THE DataTable SHALL render it only when the Permission_Hook grants `(AUDIT, READ)`.
6. WHEN the current user has none of `(resource, UPDATE)`, `(resource, DELETE)`, or `(AUDIT, READ)`, THE DataTable SHALL render no row-actions column (the column collapses entirely rather than showing an empty control group).
7. WHEN the current user is an `ADMIN`, THE data-table pages SHALL render every applicable action control (ADMIN bypass).
8. THE reference migration for this spec SHALL apply the CRUD action gating to the existing Users, Roles, and Audit pages (the pages already shipped with visible actions), so their controls reflect the current user's permissions; other feature pages adopt the same gating when they are built.

---

### Requirement 7: Localization of New UI

**User Story:** As a Polish- or Russian-speaking user, I want the Forbidden page in my language, so that I understand why I was blocked.

#### Acceptance Criteria

1. THE Forbidden_Page SHALL render all of its user-facing strings (heading, message, and the Go_Back control label) through the existing i18n mechanism, with a non-blank entry for every new string in both the PL (`src/locales/pl.json`) and RU (`src/locales/ru.json`) resources.
2. WHEN the active Locale is `ru`, THE Forbidden_Page SHALL render Russian strings, and WHEN the active Locale is `pl` or unset, THE Forbidden_Page SHALL render Polish strings.
3. THE navigation labels and CRUD action labels reused by this spec SHALL continue to use their existing i18n keys and SHALL NOT require new translation entries.

---

### Requirement 8: Accessibility

**User Story:** As a user of assistive technology, I want the visibility changes and the Forbidden page to remain accessible, so that hidden controls do not leave confusing artifacts and the 403 page is understandable.

#### Acceptance Criteria

1. WHEN a control is hidden because the user lacks its permission, THE UI SHALL omit the control from the accessibility tree entirely (it SHALL NOT render a disabled or visually hidden but focusable element in its place).
2. THE Forbidden_Page SHALL expose its heading as a page heading and its Go_Back control as a keyboard-operable link/button with an accessible name.
3. WHEN the row-actions column collapses because the user has no row-level actions, THE data table SHALL remain a valid table without an empty trailing actions header.
