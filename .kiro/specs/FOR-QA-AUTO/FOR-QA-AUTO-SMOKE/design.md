# Design: FOR-QA-AUTO-SMOKE

## Overview

This document designs the Java BDD + Playwright E2E framework (`foremen-qa-auto/`) and the smoke
suite that covers the UI-visible top-level requirements of FOR-01 … FOR-04. The framework is a
standalone Gradle module using Cucumber-JVM for the BDD layer, Playwright-for-Java for browser
automation, and a thin API helper for setup/teardown. It runs against the live Docker stack and is
structured so future specs append smoke slices by adding tagged `.feature` files.

## Architecture

```
docker compose up  (postgres:5432, liquibase, backend:8080, frontend:3000)
        ▲                                   ▲
        │ HTTP (setup/teardown, contracts)  │ browser (UI scenarios)
        │                                    │
┌───────┴────────────────────────────────────┴──────────────────────────┐
│ foremen-qa-auto/ (Gradle module, Java 25)                              │
│                                                                        │
│  Gherkin .feature (@smoke @FOR-0N)                                     │
│        │ Cucumber-JVM (JUnit 5 platform)                              │
│        ▼                                                               │
│  Step Definitions ──uses──▶ Page Objects ──drive──▶ Playwright (Page) │
│        │                        │                                      │
│        └──setup/teardown──▶ ApiHelper (login, users, dictionaries)    │
│                                                                        │
│  Support: World (per-scenario state), Hooks (browser ctx lifecycle,   │
│           run-id, teardown), TestConfig, WaitFor (readiness), Report  │
└────────────────────────────────────────────────────────────────────────┘
```

### Layering rules

- **Feature files** describe behavior in business language; no selectors, no HTTP details.
- **Step definitions** translate Gherkin to actions; they orchestrate page objects and the API
  helper, and hold assertions. They contain no raw selectors.
- **Page objects** own locators and low-level interactions for one screen/component. They expose
  intention-revealing methods (`login(email, pwd)`, `openRole(name)`, `isNavItemVisible(key)`).
- **ApiHelper** performs HTTP for setup/teardown and UI-invisible contract checks only.
- **Support** classes manage lifecycle, config, waiting, data generation, and reporting.

## Module layout

```
foremen-qa-auto/
  build.gradle
  settings note: registered in root settings.gradle (or standalone settings.gradle)
  README.md
  src/test/java/com/foremen/qa/
    RunSmokeTest.java                 # JUnit5 Cucumber suite (selects @smoke)
    config/TestConfig.java            # base URLs, admin creds, browser mode, timeouts
    support/
      PlaywrightFactory.java          # Playwright/Browser/BrowserContext/Page lifecycle
      Hooks.java                      # @Before/@After: context per scenario, run-id, teardown
      World.java                      # per-scenario shared state (page, tokens, created ids)
      WaitFor.java                    # backend health + frontend readiness gate
      DataGen.java                    # run-id, unique emails/codes/names
      DemoReportPlugin.java           # Cucumber event listener: per-step screenshot + report.json
      Demo.java                       # optional Demo.capture(page,"caption") for extra frames
      ReportWriter.java               # renders demo HTML + steering MD run-report
    api/
      ApiHelper.java                  # APIRequestContext-based client
      dto/...                         # minimal request/response records
    fixtures/
      Db.java                         # JDBC DataSource (HikariCP) to the test DB
      TestUserFixture.java            # create/cleanup users directly in DB (bcrypt)
      TestUser.java                   # record: id, email, password, roleCode
    pages/
      LoginPage.java
      AppShell.java                   # sidebar/topbar/bottom nav, role badge, nav visibility
      DataTablePage.java              # generic list: rows, columns, pagination, search
      ReferenceFilter.java            # reference dropdown component (single/multi, search)
      UsersPage.java / UserFormSheet.java
      RolesPage.java / RoleMatrix.java
      ThemeSettingsPage.java
      DictionaryPage.java             # generic dictionary CRUD page (route-parameterized)
      WorkCatalogPage.java / WorkPricesPage.java / ProjectsPage.java
      ForbiddenPage.java              # /403 with Go_Back
    steps/
      AuthSteps.java
      NavVisibilitySteps.java
      AdminPanelSteps.java
      DictionarySteps.java
      TestUserSteps.java              # reusable "create/delete user in role X" steps
      CommonSteps.java                # background: stack-ready, login-as
  src/test/resources/
    features/
      for03/auth_login.feature
      for03/auth_guard.feature
      for03/auth_logout.feature
      for03/menu_visibility.feature
      for02/app_shell.feature
      for02/roles_admin.feature
      for02/users_admin.feature
      for02/theme_settings.feature
      for04/reference_filter.feature
      for04/dictionaries.feature
      for04/work_catalog.feature
      for04/work_prices.feature
      for04/projects.feature
      for04/menu_grouping.feature
    junit-platform.properties         # cucumber plugin + glue config
    cucumber.properties               # tag filter, report outputs
```

## Technology choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language/build | Java 25 + Gradle | Aligns with `foremen-backend`; one language for QA |
| BDD | Cucumber-JVM + JUnit 5 platform | Standard Gherkin BDD; tag-based selection enables growing scope |
| Browser | Playwright-for-Java (Chromium headless) | Auto-waiting, tracing, incognito contexts, cross-browser option |
| API helper | Playwright `APIRequestContext` | Reuses Playwright; shares config; no extra HTTP dep |
| DB access | PostgreSQL JDBC driver + HikariCP | Direct test-user provisioning into the isolated test DB |
| Password hashing | `spring-security-crypto` `BCryptPasswordEncoder` (or jBCrypt) | Must match the backend's bcrypt verification |
| Assertions | Playwright assertions + JUnit/AssertJ | Web-first auto-retrying assertions reduce flakiness |
| Reports | Cucumber HTML/JSON + custom MD writer | Steering standard requires MD tables |

> The existing `foremen-frontend/e2e` TypeScript Playwright harness is left untouched. FOR-QA-AUTO is
> the Java-owned, cross-project QA suite the task requests; the two can coexist.

## Configuration (`TestConfig`)

Resolution order: system property → environment variable → default (matching `docker-compose.yml`).

| Key | Env / Property | Default |
|-----|----------------|---------|
| Frontend base URL | `QA_FRONTEND_URL` | `http://localhost:3000` |
| API base URL | `QA_API_URL` | `http://localhost:8080` |
| Admin email | `FOREMEN_ADMIN_EMAIL` | `cto+1@loaders.dev` |
| Admin password | `FOREMEN_ADMIN_PASSWORD` | `alejano123` |
| Browser mode | `QA_HEADED` | `false` (headless) |
| Browser | `QA_BROWSER` | `chromium` |
| Action/nav timeout | `QA_TIMEOUT_MS` | `15000` |
| Readiness timeout | `QA_READINESS_TIMEOUT_MS` | `120000` |
| Tag filter | `QA_TAGS` | `@smoke` |
| DB JDBC URL | `QA_DB_URL` | `jdbc:postgresql://localhost:5432/foremen` |
| DB user | `QA_DB_USER` / `POSTGRES_USER` | `foremen` |
| DB password | `QA_DB_PASSWORD` / `POSTGRES_PASSWORD` | `foremen` |
| Default test password | `QA_TEST_PASSWORD` | generated per user (run-unique) |

## Lifecycle & hooks

- **Suite start** (`WaitFor`): poll `GET {api}/actuator/health` until `UP` and `GET {frontend}/`
  until 200, within `QA_READINESS_TIMEOUT_MS`; otherwise fail fast with a clear message
  (Requirement 2.2).
- **`@Before` scenario**: compute/attach `run-id` (once per run, referenced per scenario); open a
  fresh `BrowserContext` + `Page` (incognito) so storage does not leak (Requirement 3.4). Optionally
  start a Playwright trace.
- **`@After` scenario**: run registered teardown callbacks (deactivate users, delete dictionary
  rows/projects, logout tokens) via `ApiHelper`; on failure capture screenshot + trace; close the
  context. `World` records created resource ids so teardown is precise.
- **Suite end**: `ReportWriter` renders the client-facing demo HTML report and the steering MD
  run-report from the collected per-step results/screenshots (see § Reporting design).

## API helper (setup/teardown surface)

`ApiHelper` wraps a Playwright `APIRequestContext` pointed at the API base URL. Methods used by smoke:

- `String loginAdmin()` / `TokenPair login(email, pwd)` — `POST /api/auth/login`.
- `long createRole(code)` + `void setRolePermissions(id, matrix)` + `void deleteRole(id)` — restricted
  role setup/teardown for menu-visibility (Requirement 6.4).
- `long createDictionaryRow(resourcePath, body)` / `void deleteDictionaryRow(resourcePath, id)` —
  generic dictionary teardown.
- `long createProject(body)` / `void deleteProject(id)` — project teardown.

> **Note:** test-user creation for UI login is handled by `TestUserFixture` via **direct DB insert**
> (see § Test-user provisioning), not by the API helper — that is the primary, fastest path and the
> only reliable way to get an ACTIVE, password-known user per role. An API-based
> `createUser` + invite-token + `setPassword` path is a documented fallback but not the default.
- Assertions helper for UI-invisible contracts a UI scenario depends on (e.g. presence of tokens),
  kept minimal — deep API contract testing stays in each spec's own `test-cases.md`.

## Test-user provisioning (direct DB, bcrypt) — Requirement 12

Rendering role-specific UI requires logging in as an **ACTIVE user with a known password**. The
framework provisions such users by **writing directly to the isolated test database via JDBC** (no
invite/email round-trip), and exposes the flow as two reusable Gherkin steps. All of this lives in the
QA test code only; nothing is added to the application.

### Target schema (from Liquibase changesets)

`users(id BIGSERIAL, name, email UNIQUE, phone, role_id → roles(id), active BOOLEAN,
locale, display_preferences jsonb, password_hash VARCHAR(255), status VARCHAR(20), audit cols)`.
A login-ready user requires: `status='ACTIVE'`, `active=true`, `password_hash=<bcrypt>`,
`role_id=(SELECT id FROM roles WHERE code = :roleCode)`.

FK-dependent tables referencing a user (must be cleaned before deleting the `users` row):
`project_members.user_id`, `refresh_tokens.user_id`, `invite_tokens.user_id`; `otp_tokens` is keyed by
`email`.

### `Db` (connection)

- HikariCP `DataSource` built from `TestConfig` (`QA_DB_URL` / `QA_DB_USER` / `QA_DB_PASSWORD`,
  defaults matching `docker-compose.yml`). Opened once per run, closed at suite end.

### `TestUserFixture`

```
TestUser createActiveUser(String roleCode)          // generate email+password, bcrypt, insert
TestUser createActiveUser(String roleCode, String email, String password)  // explicit
long     resolveRoleId(String roleCode)             // SELECT id FROM roles WHERE code = ?
void     deleteUserAndResources(TestUser u)         // FK-safe cascade cleanup
```

`createActiveUser` steps:
1. `email = DataGen.nextEmail()`, `password = DataGen.nextPassword()` (run-unique) unless provided.
2. `roleId = resolveRoleId(roleCode)` — fails clearly if the role code does not exist (system roles
   are never created); for custom `TESTROLE_*` the fixture may create the role and register it for
   teardown (config-driven, Requirement 12.9).
3. `hash = BCryptPasswordEncoder.encode(password)` (bcrypt; strength aligned with backend cost 12).
4. `INSERT INTO users (name, email, phone, role_id, active, locale, status, password_hash,
   created_date, created_by) VALUES (?, ?, NULL, ?, true, 'ru', 'ACTIVE', ?, NOW(), 'qa')`
   (parameterized; returns generated id).
5. Wrap into `TestUser{id, email, password, roleCode}`, store in `World`, and
   `World.registerTeardown(() -> deleteUserAndResources(u))`.

`deleteUserAndResources` order (parameterized SQL, all scoped to the created user):
`DELETE FROM project_members WHERE user_id = ?` → `DELETE FROM refresh_tokens WHERE user_id = ?` →
`DELETE FROM invite_tokens WHERE user_id = ?` → `DELETE FROM otp_tokens WHERE email = ?` → delete
run-created dictionary rows / projects tracked in `World` (via `ApiHelper` or JDBC) →
`DELETE FROM users WHERE id = ?`. Cleanup is idempotent (missing rows are a no-op) so partial
failures still converge.

### Reusable Gherkin steps (`TestUserSteps`)

```gherkin
# Create — remembers username/password in World
Given a test user with role "MANAGER" exists
# → creates ACTIVE user, stores email/password/id in World as the "current test user"

# Optionally name it for multi-user scenarios
Given a test user "clientA" with role "CLIENT" exists

# Log in through the UI as the provisioned user
When I log in through the UI as the test user

# Cleanup — explicit (also runs automatically via teardown)
Then the test user with role "MANAGER" is deleted with all its resources
```

- The create step is **role-parameterized** and remembers credentials so the very next step can
  `LoginPage.login(email, password)` and land on the UI as that role.
- The delete step removes the user and all its resources; it is also invoked automatically by the
  `@After` teardown for any user created during the scenario (Requirement 12.7), so scenarios that
  forget the explicit step are still clean.
- These two steps are the reusable building blocks other features compose (e.g. menu-visibility uses
  "create user with role having only USERS:READ" by combining the API role-matrix helper with the
  provisioning step).

### Safety

- All SQL is parameterized (no string interpolation of test data).
- Only run-created rows are deleted; the seeded ADMIN and seed reference/roles are never removed.
- Intended for an **isolated test database**; the design assumes it is safe to write/delete directly
  there (confirmed for this suite).

## Page objects (key ones)

- **LoginPage**: `open()`, `login(email,pwd)`, `submitEmpty()`, `errorMessage()`, `isOnLogin()`, and
  a helper to read `localStorage` tokens via `page.evaluate`.
- **AppShell**: `isRendered()`, `roleBadgeText()`, `navGroups()`, `isNavItemVisible(routeOrKey)`,
  `navigateTo(route)`. Handles responsive layout (desktop sidebar vs mobile bottom nav) by viewport.
- **DataTablePage**: `isLoaded()`, `columnHeaders()`, `rowCount()`, `search(text)`, `rowByText(text)`,
  used by dictionary/users/roles pages.
- **ReferenceFilter**: `open(field)`, `search(text)`, `selectSingle(value)`, `clear()`,
  `resultsNarrowed()` — for FOR-04-01.
- **UsersPage / UserFormSheet**: `openCreate()`, `fill(name,email,phone,role,locale)`, `submit()`,
  `listContains(email)`.
- **RolesPage / RoleMatrix**: `openRole(name)`, `isMatrixVisible()`.
- **ThemeSettingsPage**: `open()`, `currentTheme()`, `toggleTheme()`, `reload()`.
- **DictionaryPage**: route-parameterized generic page for the nine flat dictionaries.
- **WorkCatalogPage / WorkPricesPage / ProjectsPage**: list + create happy paths with FK selects.
- **ForbiddenPage**: `isShown()`, `goBack()`.

Locator strategy: prefer `getByRole`, `getByLabel`, `getByTestId`. Where the frontend lacks stable
test ids, page objects fall back to accessible text/role; adding `data-testid` hooks in the frontend
is a recommended follow-up (documented in README) but not required for smoke.

## BDD scope → scenario mapping (traceability)

| Requirement | Feature file | Tag |
|-------------|--------------|-----|
| 5.1–5.3 | `for03/auth_login.feature` | `@smoke @FOR-03` |
| 5.4 | `for03/auth_guard.feature` | `@smoke @FOR-03` |
| 5.5 | `for03/auth_logout.feature` | `@smoke @FOR-03` |
| 6.1–6.4 | `for03/menu_visibility.feature` | `@smoke @FOR-03` |
| 7.1 | `for02/app_shell.feature` | `@smoke @FOR-02` |
| 7.2 | `for02/roles_admin.feature` | `@smoke @FOR-02` |
| 7.3 | `for02/users_admin.feature` | `@smoke @FOR-02` |
| 7.4 | `for02/theme_settings.feature` | `@smoke @FOR-02` |
| 8.5 | `for04/reference_filter.feature` | `@smoke @FOR-04` |
| 8.1 | `for04/dictionaries.feature` (Scenario Outline) | `@smoke @FOR-04` |
| 8.2 | `for04/work_catalog.feature` | `@smoke @FOR-04` |
| 8.3 | `for04/work_prices.feature` | `@smoke @FOR-04` |
| 8.4 | `for04/projects.feature` | `@smoke @FOR-04` |
| 8.6 | `for04/menu_grouping.feature` | `@smoke @FOR-04` |
| 9 | (transitive via FOR-02/FOR-04 CRUD scenarios) | — |

### Example feature (illustrative)

```gherkin
@smoke @FOR-03
Feature: Employee password login

  Background:
    Given the application stack is ready

  Scenario: Admin logs in with valid credentials
    Given I am on the login page
    When I log in with the seeded admin credentials
    Then I land on the home page
    And access and refresh tokens are stored

  Scenario: Empty fields block submission
    Given I am on the login page
    When I submit the login form without filling any field
    Then submission is blocked by client validation
    And no login request is sent
```

## Growing-scope mechanism

- Selection is purely tag-based (`QA_TAGS` default `@smoke`), so a new `for0N/*.feature` file tagged
  `@smoke @FOR-0N` is picked up with zero harness change (Requirement 4.2).
- Source-tag slicing: `-DQA_TAGS="@smoke and @FOR-03"` runs one spec's smoke slice (Requirement 4.3).
- README documents the recipe: add feature file, reuse `DictionaryPage`/`DataTablePage`/`AppShell`
  and steps where possible, add page object only for genuinely new screens, update the coverage map
  (Requirement 4.4).
- Deep scenarios for a spec go into `FOR-QA-AUTO-0N-*` (Requirement 4.5), reusing this module's
  support/page-object base.

## Repeatability design

- `DataGen` computes one `run-id` per JVM run and hands out `nextEmail()`, `nextPassword()`,
  `nextCode()`, `nextProjectName()`, `nextRoleCode()`.
- `World.registerTeardown(Runnable)` accumulates cleanup actions executed LIFO in `@After`.
- Fresh incognito `BrowserContext` per scenario guarantees clean storage (Requirement 3.4).
- Two consecutive runs pass because all created keys are run-unique and cleaned up; seed data is
  read-only (Requirement 3.5).

## Reporting design (demo-style, per-step screenshots) — Requirement 10

The suite produces two tiers of output:

1. **Client-facing demo report** (primary): a readable, browser-openable HTML report that walks a
   non-technical reader through each feature like a documented demo — every scenario, every step, a
   plain-language description, expected vs actual, status, and a **screenshot per step**.
2. **Machine/standard outputs** (secondary): Cucumber JSON + standard Cucumber HTML for CI, and the
   `.kiro/steering/test-cases.md`-style MD run-report tables for the QA standard.

### Per-step screenshot capture

Cucumber does not expose a native "after each step within a step" hook that can screenshot the UI at
the exact moment of each meaningful action, so capture is driven two ways that combine:

- **Step-event listener** (`DemoReportPlugin implements ConcludeEventListener/EventListener`):
  subscribes to `TestStepFinished`. After each Gherkin step finishes, it takes a full-page screenshot
  of the current `Page` (from `World`) and records `{scenario, stepText, status, timestamp, screenshot
  path, error?}`. This gives one screenshot per Gherkin step for free, keyed to the business-language
  step text (Requirement 10.1, 10.2).
- **Explicit demo annotations** (optional, for richer demos): a tiny `Demo` helper
  (`Demo.capture(page, "caption")`) that page objects/steps can call at extra sub-moments (e.g. right
  after filling the role form, right before submit) to add captioned frames. Used where a single
  end-of-step shot is not enough to tell the story (e.g. showing the filled form before submit).

Screenshots are written under the run-scoped output folder; the report references them by relative
path (Requirement 10.4, 10.9).

### Report structure (client-facing)

```
qa-report/<run-id>/
  index.html                 # landing: run summary + per-spec (@FOR-0N) navigation
  features/
    FOR-02-roles_admin.html  # one page per feature: scenarios → steps → screenshots
    ...
  assets/
    screenshots/<scenario>/<nnn>-<step-slug>.png
    traces/<scenario>.zip     # Playwright trace (attached on failure; optional otherwise)
    style.css
  report.json                # structured data the HTML is rendered from (also feeds MD)
```

- **index.html**: run-level summary — environment (URLs, run-id, timestamp), totals
  (features/scenarios, passed/failed/skipped), and a grouped list by source spec `@FOR-0N` and feature
  (Requirement 10.5, 10.6).
- **feature page**: for each scenario, an ordered, numbered step list. Each step shows: step text in
  plain language, expected/actual, a green/red status chip, and the step screenshot (thumbnail →
  click to enlarge). A CRUD scenario therefore reads as: *create role → screenshot of filled form →
  submit → screenshot of success toast → verify it appears in the list → screenshot of the row →
  cleanup (delete) → screenshot confirming removal* (Requirement 10.3).
- **failure**: the failing step row is highlighted red, shows the failure screenshot and the error
  message, and links the Playwright trace; preceding passing steps still render normally so the demo
  flow stays readable (Requirement 10.7).

### `ReportWriter` / `DemoReportPlugin`

- Collects `report.json` from the step-event stream + explicit `Demo.capture` frames.
- Renders `index.html` and per-feature pages from a small template (no runtime server needed;
  self-contained HTML + `assets/`).
- Also renders the steering-standard **MD run-report** tables and writes them next to the HTML
  (Requirement 10.8).
- Redacts secrets: never prints admin password/tokens as text in the report; generated test data
  (emails, codes) is allowed. Screenshots of the login form are taken **after** navigation but the
  report text does not echo the password (Requirement 10.10).

### Output configuration

| Key | Env / Property | Default |
|-----|----------------|---------|
| Report root | `QA_REPORT_DIR` | `foremen-qa-auto/build/qa-report` |
| Screenshot mode | `QA_SHOTS` | `per-step` (also: `on-failure` for fast CI runs) |
| Full-page shots | `QA_FULLPAGE_SHOTS` | `true` |
| Keep history | `QA_REPORT_KEEP_HISTORY` | `true` (run-scoped folder per run-id) |

> `QA_SHOTS=per-step` is the default because the client-facing demo report is a first-class
> deliverable. CI may set `QA_SHOTS=on-failure` for speed while still producing the standard reports.

## Error handling & flakiness controls

- No fixed sleeps; rely on Playwright auto-waiting and web-first assertions.
- Readiness gate prevents "stack not up" false negatives.
- Per-scenario context isolation prevents cross-test contamination.
- Timeouts are centralized and configurable.
- Locators prefer role/label/test-id to reduce brittleness.

## Testing the framework itself

- The framework is validated by running the smoke suite against a live stack (its scenarios are the
  tests). A minimal "stack ready + admin login" scenario acts as the bootstrap smoke check.
- CI entry point: `./gradlew :foremen-qa-auto:smokeTest` after `docker compose up` and readiness.

## Assumptions & open points

- Assumes standard admin credentials from `docker-compose.yml`; overridable via config.
- Assumes routes as documented in the FOR-02/03/04 specs (`/login`, `/403`, `/users`, `/roles`,
  `/settings/appearance` or equivalent, dictionary routes, `/catalog/works`, `/catalog/prices`,
  `/projects`). Page objects centralize routes so a rename is a one-line change.
- Google-login UI branches (FOR-03-06) require a stubbed `GoogleIdTokenVerifier`; these are **not**
  included in smoke (they are deep scenarios) and are deferred to a per-spec or dedicated slice.
- OTP client login (FOR-03-05) requires reading the code from a DB/mailhog helper; **not** in smoke.
- Adding `data-testid` hooks in the frontend would harden locators; recommended follow-up, not a
  blocker.
