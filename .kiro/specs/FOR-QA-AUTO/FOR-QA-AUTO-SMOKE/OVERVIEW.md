# FOR-QA-AUTO-SMOKE: Framework + Smoke Suite

## Overview

FOR-QA-AUTO-SMOKE is the foundational child spec of FOR-QA-AUTO. It delivers two things at once:

1. **The shared E2E framework** — a Java + Cucumber (BDD) + Playwright-for-Java module
   (`foremen-qa-auto/`) that every FOR-QA-AUTO child spec builds on.
2. **The smoke suite** — a broad, shallow set of `@smoke`-tagged scenarios covering the top-level,
   **UI-visible** requirements of the completed specs **FOR-01 … FOR-04**, running against the live
   Docker stack.

The smoke suite is intentionally shallow: one or two happy-path (and a few critical negative-path)
scenarios per functional area, enough to catch a broken deploy fast. Deep coverage of each area lives
in that area's own child spec (FOR-QA-AUTO-05 onward). The smoke suite is built so its scope grows by
adding tagged `.feature` files, never by refactoring the harness.

## Aggregated inventory of existing `test-cases.md`

This section is the scan requested in the task: every `test-cases.md` under FOR-01 … FOR-04,
classified by whether it exposes a **UI surface** (in smoke scope), and what UI-visible behavior it
describes. Pure-API specs are listed for completeness but are **not** in the smoke UI scope (their API
scenarios stay in their own `test-cases.md`; the smoke suite only touches API for setup/teardown or
where a UI scenario depends on it).

### FOR-01 — Backend structure (CRUD framework)

| Sub-spec | `test-cases.md`? | Surface | In smoke UI scope? |
|----------|:---------------:|---------|:------------------:|
| FOR-01-01 … FOR-01-10 (gradle, base entity, exception, mapstruct, DAO, service, controller, audit, liquibase, config) | none | Pure backend framework, no UI | No |

FOR-01 has no `test-cases.md` and no browser surface. Its correctness is **implicitly** exercised by
every FOR-02/03/04 UI smoke scenario that reads/writes through the CRUD framework (list, create,
edit, delete, pagination, i18n mapping). No dedicated smoke scenarios.

### FOR-02 — Admin panel (React SPA)

| Sub-spec | `test-cases.md` | Type | UI-visible behavior | In smoke |
|----------|-----------------|------|---------------------|:--------:|
| FOR-02-01 frontend-setup | — | — | Project setup only | — |
| FOR-02-02 app-shell | — | — | Sidebar / topbar / bottom nav, responsive layout, routing | Yes (shell renders + nav) |
| FOR-02-03/04 abac entities/seed | — | backend | ABAC model + seed | No (backend) |
| FOR-02-05 user-entity | — | backend | User entity | No (backend) |
| FOR-02-06 roles-ui | — | UI | Roles list, create/edit role, access matrix (resource×operation checkboxes) | Yes |
| FOR-02-07 users-ui | — | UI | Users list, create/edit (name/email/phone/role/locale/displayPrefs), deactivate | Yes |
| FOR-02-07-users-ui-fixes | present | UI + API | RoleSelect styling, paginated role loading, client search, English locale option | Yes (users list + role select happy path) |
| FOR-02-07-users-create-jsonb-null-fix | present | API | jsonb null-bind on user create | No (backend contract; covered via UI create indirectly) |
| FOR-02-08 theme-settings | — | UI | Appearance page: theme (dark/light/system), color scheme, font size, live preview | Yes (open page + toggle theme persists) |

### FOR-03 — Auth system

| Sub-spec | `test-cases.md` | Type | UI-visible behavior | In smoke |
|----------|-----------------|------|---------------------|:--------:|
| FOR-03-01 jwt-auth | present | API | login/refresh/logout/me contracts | Setup only (login) |
| FOR-03-02 user-invitation | present | API | invite → set-password contract | No (deep in per-spec) |
| FOR-03-03 permission-evaluator | present | API | runtime ABAC check | No |
| FOR-03-04 project-ownership | present | API | project_members filtering | No |
| FOR-03-04a project-actions-validation | present | API | per-action ownership (404 out-of-scope) | No |
| FOR-03-05 otp-client-auth | present | API (+vitest) | OTP request/verify | No (deep in per-spec) |
| **FOR-03-06 frontend-auth** | present | **UI + API** | LoginPage (password), SetPasswordPage, OtpLoginPage (6-box code), Auth guard + Return_Location, logout, "already authed leaves /login", Google login branches | **Yes** (login happy path, empty-field validation, invalid-creds localized error, deep-link guard round-trip, logout) |
| **FOR-03-07 menu-visibility** | present | **UI** | Nav item visibility by permission, route guard → `/403` with Go_Back, CRUD action visibility (Create/Edit/Delete/Audit) on Users/Roles/Audit, role name in topbar | **Yes** (admin sees all nav; restricted role hides items; deep-link denied → /403) |
| FOR-03-08 api-protection | present | API | 401/403/200 enforcement | No (backend) |

### FOR-04 — Base entities & dictionaries

| Sub-spec | `test-cases.md` | Type | UI route | In smoke |
|----------|-----------------|------|----------|:--------:|
| FOR-04-01 table-reference-filter | present | UI + API | Reference dropdown (infinite scroll, search, single/multi select, clear) + mobile table UX; default for all tables | Yes (open a table with a reference filter, apply single filter) |
| FOR-04-02 measurement-units | present | UI + API | `/measurement-units` | Yes (list renders + create/delete happy path) |
| FOR-04-03 currencies | present | UI + API | `/currencies` | Yes (list renders) |
| FOR-04-04 vat-rates | present | UI + API | `/vat-rates` | Yes (list renders) |
| FOR-04-05 room-types | present | UI + API | `/room-types` | Yes (list renders + CRUD happy path) |
| FOR-04-06 work-categories | present | UI + API | `/work-categories` | Yes (list renders) |
| FOR-04-07 delivery-categories | present | UI + API | `/delivery-categories` | Yes (list renders) |
| FOR-04-08 delivery-statuses | present | UI + API | `/delivery-statuses` | Yes (list renders) |
| FOR-04-09 material-categories | present | UI + API | `/material-categories` | Yes (list renders) |
| FOR-04-10 offer-packages | present | UI + API | `/offer-packages` | Yes (list renders) |
| FOR-04-11 work-catalog | present | UI + API | `/catalog/works` | Yes (list renders + create with FK selects) |
| FOR-04-12 work-prices | present | UI + API | `/catalog/prices` | Yes (list renders, price history visible) |
| FOR-04-13 project | present | UI + API | `/projects` | Yes (list renders + create project happy path) |
| FOR-04-14 room | present | UI + API | `/rooms` | Yes (list renders) |
| FOR-04-15 menu-grouping | present | UI | Nav groups "Catalog" / "Dictionaries" | Yes (groups present, items route correctly) |
| FOR-04-bugs | present | UI + API | assorted fixes | No (regression, folded into per-spec) |

### Scan totals

- `test-cases.md` files found under FOR-01..FOR-04: **26** (0 in FOR-01, 2 in FOR-02, 10 in FOR-03,
  14 in FOR-04).
- UI-facing (in smoke scope): FOR-02 shell/roles/users/theme, FOR-03-06, FOR-03-07, FOR-04-01,
  FOR-04-02..15.
- Pure backend/API (out of smoke UI scope): all FOR-01, FOR-03-01/02/03/04/04a/05/08,
  FOR-02-07-jsonb-fix.

## Smoke coverage map (what the suite actually automates)

Each row becomes one `@smoke`-tagged Gherkin scenario (or a small scenario outline). The `@FOR-0N`
tag lets the runner slice by source spec.

| Feature file | Tags | Scenario summary |
|--------------|------|------------------|
| `auth_login.feature` | `@smoke @FOR-03` | Admin logs in with valid credentials → lands on `/`, tokens stored |
| `auth_login.feature` | `@smoke @FOR-03` | Empty fields block submit (client validation, no request) |
| `auth_login.feature` | `@smoke @FOR-03` | Invalid credentials → localized error, stays on `/login` |
| `auth_guard.feature` | `@smoke @FOR-03` | Deep-link without session → `/login` → back to deep-link after login |
| `auth_logout.feature` | `@smoke @FOR-03` | Logout clears tokens → `/login` |
| `menu_visibility.feature` | `@smoke @FOR-03` | Admin sees all nav groups; role name shown in topbar |
| `menu_visibility.feature` | `@smoke @FOR-03` | Restricted role: forbidden nav hidden; deep-link denied → `/403` with Go_Back |
| `app_shell.feature` | `@smoke @FOR-02` | Authenticated shell renders (sidebar/topbar); primary nav navigable |
| `roles_admin.feature` | `@smoke @FOR-02` | Roles list renders; open role → access matrix visible |
| `users_admin.feature` | `@smoke @FOR-02` | Users list renders; create user (generated email) → appears; deactivate (teardown) |
| `theme_settings.feature` | `@smoke @FOR-02` | Appearance page: toggle theme persists across reload |
| `reference_filter.feature` | `@smoke @FOR-04` | Open a table with a reference filter; apply single-value filter narrows list |
| `dictionaries.feature` | `@smoke @FOR-04` | Scenario Outline over all dictionary routes: page loads, table renders, columns present |
| `work_catalog.feature` | `@smoke @FOR-04` | Work Catalog list renders; create item with category+unit FK selects |
| `work_prices.feature` | `@smoke @FOR-04` | Work Prices list renders; current price (validTo null) shown |
| `projects.feature` | `@smoke @FOR-04` | Projects list renders; create project happy path (teardown) |
| `menu_grouping.feature` | `@smoke @FOR-04` | "Catalog" and "Dictionaries" nav groups present; items route to correct pages |

> FOR-01 is validated **transitively**: every dictionary/user CRUD smoke scenario drives the FOR-01
> CRUD framework (list, pagination, create, delete, i18n). No standalone FOR-01 scenario.

## Reusable test-user provisioning (role-based, direct DB)

Since the only way to render the UI under a given role is to log in as an ACTIVE user with a known
password, the framework ships a **reusable test-user provisioning module** exposed as two reusable
Gherkin steps. Because the suite runs against an **isolated test database**, provisioning **inserts
directly into the DB via JDBC** (fastest, no invite/email round-trip) rather than going through the
API. The provisioning logic lives **only inside the QA test code** (step definitions + a
`TestUserFixture` helper) — it is not added to the application.

- **Create step** — "create a user in role X and remember its username/password": generates a
  run-unique email and password, resolves `role_id` from `roles.code = X`, computes a **bcrypt** hash
  of the password, and inserts a `users` row with `status=ACTIVE`, `active=true`. The created
  credentials and id are stored in the scenario `World` so subsequent steps can UI-login as that
  user. Registers the user for automatic teardown.
- **Delete step** — "delete the user in role X and clean up all its resources": removes the user and
  all rows it owns/created during the run (FK-dependent rows first): `project_members`,
  `refresh_tokens`, `invite_tokens`, `otp_tokens` (by email), then run-created dictionary rows and
  projects tracked in `World`, then the `users` row itself.

Both steps are role-parameterized (ADMIN / MANAGER / FOREMAN / WORKER / FINANCIER / CLIENT / custom
`TESTROLE_*`). For CLIENT a password is set anyway so UI password-login works in tests (the OTP path
is not required for provisioning). Details in `design.md` (§ Test-user provisioning) and
`requirements.md` (Requirement 12).

## Repeatability strategy (per steering standard)

- **Generator**: each run computes a `run-id` (timestamp/uuid). Entities created during a run use
  unique keys: users `test+{run-id}+{seq}@example.com`; dictionary rows `code=qa{run-id}{seq}`;
  projects `QA Project {run-id}`.
- **Teardown**: after each scenario/suite, directly-provisioned users and all their owned/created
  rows are removed via the delete step (JDBC); users created through the API are deactivated
  (`PUT /api/users/{id}` `active=false`) and their refresh tokens logged out; created
  dictionary/project rows are deleted via their `DELETE /api/{resource}/{id}`; browser context is
  fresh (incognito) per scenario.
- The seeded ADMIN and seed reference data are **never** mutated destructively. The suite is
  re-runnable without manual DB cleanup.

## How this suite grows (contract for future specs)

1. New feature spec completed → add a `<area>.feature` file tagged `@smoke @FOR-0N` with 1–3
   happy/critical scenarios and reuse existing step definitions / page objects.
2. Add a row to the **Smoke coverage map** above and to the FOR-QA-AUTO parent OVERVIEW child table.
3. Deep scenarios go into the dedicated `FOR-QA-AUTO-0N-*` child spec, not here.
4. The `@smoke` runner picks up new files automatically; no harness change needed.

## Deliverables

- `foremen-qa-auto/` Gradle module: Cucumber-JVM + Playwright-for-Java + JUnit 5 platform.
- Page objects for Login, AppShell/Nav, DataTable, Users, Roles, Theme, generic Dictionary page.
- **`TestUserFixture`** (JDBC + bcrypt) + two reusable Gherkin steps for role-based test-user
  create/cleanup — the primary way scenarios obtain a UI session under any role.
- API helper (admin login, create/delete dictionary row/project, fetch tokens) for setup/teardown.
- `.feature` files listed in the coverage map, tagged `@smoke @FOR-0N`.
- Report output: a **client-facing, demo-style HTML report with a screenshot per step** (readable by
  a non-technical client rep — every step of each feature, incl. the full CRUD lifecycle
  create→verify→cleanup), plus Cucumber HTML/JSON and the steering-standard **MD run-report** tables
  for CI/QA.
- `README.md` documenting how to run against `docker compose up` and how to add a new smoke slice.
