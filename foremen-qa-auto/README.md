# foremen-qa-auto

Automated end-to-end (E2E) smoke suite for the Foremen project. It is a **Java 25 + Cucumber-JVM
(BDD) + Playwright-for-Java** module that drives the real application through its browser UI against
the running Docker stack. It is the executable counterpart of the per-spec `test-cases.md`
documents.

The suite provides a fast "is this build/deploy functional?" signal by covering the top-level,
**UI-visible** requirements of the completed specs **FOR-01 … FOR-04**. Its scope is designed to
grow: each newly completed spec appends smoke scenarios by adding a tagged `.feature` file — no
harness refactor required.

This module is a **standalone Gradle build**, intentionally not a subproject of `foremen-backend`,
so its tasks never trigger the ~20-minute backend Testcontainers integration suite.

---

## Prerequisites

- **JDK 25** on your `PATH` (`java -version` should report 25).
- **Docker + Docker Compose** to bring up the application stack.
- The module's own Gradle wrapper (`./gradlew`) — no global Gradle install required.
- **Playwright browser binaries** (one-time download, see setup below).

## 1. Bring up the stack

From the **repository root** (not this module):

```bash
docker compose up
```

This starts:

| Service    | Port | Notes |
|------------|------|-------|
| `postgres` | 5432 | test database (`foremen`, user/pass `foremen`/`foremen`) |
| `liquibase`| —    | applies migrations, then exits |
| `backend`  | 8080 | Spring Boot, `docker` profile; API under `/api`, auth under `/api/auth` |
| `frontend` | 3000 | SPA the UI scenarios drive |

The first ADMIN is bootstrapped by the backend from `FOREMEN_ADMIN_CREATE=true`,
`FOREMEN_ADMIN_EMAIL` (compose default `cto+1@loaders.dev`), `FOREMEN_ADMIN_PASSWORD` (compose
default `alejano123`). The suite reads the same values; override via config if your stack differs.

The suite **waits** for backend actuator health and frontend availability before running and
**fails fast** with a clear message if the stack is not up within `QA_READINESS_TIMEOUT_MS`
(default 120s). The suite assumes the stack is started separately — it never starts an embedded app
or Testcontainers.

## 2. One-time setup: install Playwright browsers

```bash
./gradlew installBrowsers
```

Downloads the Chromium browser binaries (plus OS deps where supported) so a clean checkout can run
the suite. This is intentionally **not** wired into every run to avoid re-downloading on CI caches —
run it once per environment.

## 3. Run the smoke suite

```bash
./gradlew smokeTest
```

Runs every scenario tagged `@smoke` against the live stack. Chromium runs **headless** by default.

### Slice by source spec (`@FOR-0N`)

Every scenario carries a `@smoke` tag **and** a source tag `@FOR-0N` identifying the spec it covers.
Override the tag filter with `-DQA_TAGS` (a Cucumber tag expression):

```bash
# Only the FOR-03 auth/menu-visibility slice
./gradlew smokeTest -DQA_TAGS="@smoke and @FOR-03"

# FOR-02 or FOR-04
./gradlew smokeTest -DQA_TAGS="@smoke and @FOR-02"
./gradlew smokeTest -DQA_TAGS="@smoke and @FOR-04"

# Everything except one spec
./gradlew smokeTest -DQA_TAGS="@smoke and not @FOR-04"
```

New `@smoke @FOR-0N` feature files placed under `src/test/resources/features` are **discovered
automatically** — the JUnit 5 `@Suite` runner (`RunSmokeTest`) selects the `features` classpath
resource and filters by tag, so no runner change is needed to extend coverage.

## Configuration

All configuration resolves in a fixed order: **system property → environment variable → default**.
Defaults match `docker-compose.yml`, so a local `docker compose up` needs no configuration.

| Key | Default | Purpose |
|-----|---------|---------|
| `QA_FRONTEND_URL` | `http://localhost:3000` | frontend base URL for UI scenarios |
| `QA_API_URL` | `http://localhost:8080` | API base URL for setup/teardown + assertions |
| `FOREMEN_ADMIN_EMAIL` | `cto+1@loaders.dev` | seeded ADMIN login |
| `FOREMEN_ADMIN_PASSWORD` | `alejano123` | seeded ADMIN password |
| `QA_HEADED` | `false` | `true` runs the browser visibly |
| `QA_BROWSER` | `chromium` | `chromium` \| `firefox` \| `webkit` |
| `QA_TIMEOUT_MS` | `15000` | default action/navigation timeout |
| `QA_READINESS_TIMEOUT_MS` | `120000` | max wait for the stack to become ready |
| `QA_TAGS` | `@smoke` | Cucumber tag filter (slice by `@FOR-0N`) |
| `QA_TRACE` | `false` | record a Playwright trace per scenario |
| `QA_DB_URL` | `jdbc:postgresql://localhost:5432/foremen` | test-DB JDBC URL (test-user provisioning) |
| `QA_DB_USER` / `QA_DB_PASSWORD` | `foremen` / `foremen` | test-DB credentials |
| `QA_CREATE_MISSING_ROLE` | `true` | create a missing custom `TESTROLE_*` role vs. fail clearly |
| `QA_REPORT_DIR` | `build/qa-report` | root output folder for reports |
| `QA_SHOTS` | `per-step` | `per-step` (demo report) or `on-failure` (fast CI) |
| `QA_FULLPAGE_SHOTS` | `true` | full-page vs. viewport screenshots |
| `QA_REPORT_KEEP_HISTORY` | `true` | preserve run-scoped report folders vs. rolling `latest` |

Example — headed Firefox run of the FOR-02 slice with tracing:

```bash
./gradlew smokeTest -DQA_TAGS="@smoke and @FOR-02" -DQA_HEADED=true -DQA_BROWSER=firefox -DQA_TRACE=true
```

## Reports

Each run writes to a run-scoped folder under `QA_REPORT_DIR` (keyed by `run-id`), so historical
reports are preserved and comparable. Outputs:

- **Client-facing demo report** — a browser-openable `index.html` organized by `@FOR-0N` and feature,
  showing every Gherkin step in order with a plain-language description, expected/actual, status, and
  a per-step screenshot (setup → verify → cleanup). Secrets (passwords/tokens) are redacted.
- **Machine-readable** — Cucumber JSON + standard Cucumber HTML (`build/qa-report/cucumber.{json,html}`)
  for CI, plus the steering-standard MD run-report tables.

## Project structure

```
foremen-qa-auto/
├─ build.gradle              # standalone build; smokeTest + installBrowsers tasks
├─ gradle.properties         # pinned dependency versions
└─ src/test/
   ├─ java/com/foremen/qa/
   │  ├─ RunSmokeTest.java    # JUnit 5 @Suite Cucumber runner (selects @smoke)
   │  ├─ support/             # framework infrastructure
   │  │  ├─ TestConfig.java       # config resolution (sysprop → env → default)
   │  │  ├─ PlaywrightFactory.java# browser/context/page lifecycle (incognito per scenario)
   │  │  ├─ Hooks.java            # @BeforeAll readiness gate, @Before/@After session + teardown
   │  │  ├─ WaitFor.java          # fail-fast stack-readiness gate
   │  │  ├─ World.java            # per-scenario shared state + LIFO teardown registry
   │  │  ├─ ApiHelper.java        # HTTP client for setup/teardown (login, roles, dictionaries, projects)
   │  │  └─ DataGen.java          # run-id + unique emails/codes/project names/passwords
   │  ├─ fixtures/            # direct test-DB user provisioning (Requirement 12)
   │  │  ├─ Db.java               # HikariCP DataSource from config
   │  │  ├─ TestUser.java         # provisioned-user record (email/password/id/role)
   │  │  └─ TestUserFixture.java  # bcrypt INSERT of ACTIVE user + FK-safe cascade cleanup
   │  ├─ pages/               # page objects (one per screen/component)
   │  │  ├─ LoginPage, AppShell, DataTablePage, ReferenceFilter, ForbiddenPage   # core
   │  │  ├─ UsersPage, UserFormSheet, RolesPage, RoleMatrix, ThemeSettingsPage   # FOR-02
   │  │  └─ DictionaryPage, WorkCatalogPage, WorkPricesPage, ProjectsPage        # FOR-04
   │  ├─ steps/               # Gherkin step definitions (glue)
   │  │  ├─ CommonSteps, AuthSteps, NavVisibilitySteps       # FOR-03
   │  │  ├─ AdminPanelSteps                                  # FOR-02
   │  │  ├─ DictionarySteps                                  # FOR-04
   │  │  └─ TestUserSteps                                    # reusable role-based user steps
   │  └─ report/              # DemoReportPlugin, ReportWriter, ReportModel, Demo, Secrets
   └─ resources/features/     # .feature files, grouped by source spec
      ├─ for02/  app_shell, roles_admin, users_admin, theme_settings
      ├─ for03/  auth_login, auth_guard, auth_logout, menu_visibility
      └─ for04/  dictionaries, reference_filter, work_catalog, work_prices, projects, menu_grouping
```

### How the layers fit together

- **Feature files** (`resources/features/forNN/*.feature`) express behavior in Gherkin, each tagged
  `@smoke @FOR-0N`.
- **Step definitions** (`steps/`) are the glue Cucumber binds to Gherkin lines. They orchestrate page
  objects and the `ApiHelper`, and read/write scenario state via `World`.
- **Page objects** (`pages/`) encapsulate the locators and actions for a single screen/component,
  centralizing routes and preferring role/label/test-id locators over brittle CSS/XPath.
- **`World`** is a per-scenario holder (injected by Cucumber's picocontainer) for shared state and,
  crucially, the **LIFO teardown registry**.
- **`ApiHelper` / `TestUserFixture`** perform setup/teardown: `ApiHelper` over HTTP (roles,
  dictionaries, projects), `TestUserFixture` directly via JDBC (bcrypt ACTIVE user + FK-safe cleanup).

## Recipe: add a smoke slice for a future spec (`FOR-0N`)

When a new spec is completed and you want a smoke slice for its UI-visible behavior:

1. **Add a feature file.** Create `src/test/resources/features/forNN/<area>.feature` and tag it at
   the top with both the smoke and source tags:

   ```gherkin
   @smoke @FOR-0N
   Feature: <short UI-visible behavior>

     Background:
       Given the application stack is ready

     Scenario: <happy path or critical negative path>
       # ...steps...
   ```

2. **Reuse existing steps and page objects first.** Compose from the reusable steps — e.g.
   `Given the application stack is ready` (`CommonSteps`), the login steps, and the role-based
   provisioning steps in `TestUserSteps`:

   ```gherkin
   Given a test user with role "MANAGER" exists
   When I log in through the UI as the test user
   ```

   These already create an ACTIVE, password-known user directly in the DB, remember it in `World`,
   and register automatic teardown.

3. **Add only what's new.** If the slice needs a screen not yet modeled, add a page object under
   `pages/` (centralize the route, use role/label/test-id locators) and the minimal new step methods
   under `steps/`. Keep smoke scenarios shallow — happy path plus critical negatives. Deep per-spec
   flows belong in a dedicated `FOR-QA-AUTO-0N-*` child spec, not in the smoke suite.

4. **Honor repeatability.** Generate data via `DataGen` (never hard-code emails/codes/names) and
   register a teardown for anything you create: `world.registerTeardown(() -> ...)`, or rely on the
   `TestUserSteps` provisioning which registers its own cleanup.

5. **Update the coverage map.** Add a row to the FOR-QA-AUTO-SMOKE OVERVIEW's coverage table (and the
   parent `FOR-QA-AUTO/OVERVIEW.md`) so the documented scope matches the feature files.

6. **Verify the slice runs.** `./gradlew smokeTest -DQA_TAGS="@smoke and @FOR-0N"` — the runner
   discovers the new file automatically; no change to `RunSmokeTest` is needed.

## Repeatability (no manual DB cleanup between runs)

The suite is designed to run repeatedly against the same stack with **no manual database cleanup**:

- **Unique data per run.** `DataGen` computes one `run-id` per JVM run and derives every data key
  from it plus a monotonic sequence — emails `test+{run-id}+{seq}@example.com`, dictionary codes
  `qa{run-id}{seq}`, project names `QA Project {run-id}`, custom roles `TESTROLE_{run-id}_{seq}`.
  This is collision-free within a run and across runs, so a re-run never trips over leftover data.
- **Teardown for everything created.** Each scenario registers cleanup callbacks in `World`, executed
  **LIFO** in the `@After` hook. LIFO matches creation order for FK-dependent data (project → user →
  role removed in reverse), and `TestUserFixture` removes FK-dependent rows first
  (`project_members`, `refresh_tokens`, `invite_tokens`, `otp_tokens` by email) before the `users`
  row. Callbacks are idempotent and failure-isolated, so the suite converges to a clean state even
  after a partial failure.
- **No shared-state leakage.** A fresh incognito browser context/page is opened per scenario, so
  `localStorage`/`sessionStorage`/cookies never leak between scenarios.
- **Seed data is never destroyed.** The seeded ADMIN and seed reference data are never modified or
  deleted; only run-created resources are torn down. System roles are never created or deleted.

Result: running `./gradlew smokeTest` twice in a row passes both times without intervention.

## Coverage notes

### FOR-01 — transitive coverage (no standalone scenarios)

FOR-01 is the generic CRUD framework and has **no UI surface of its own**, so the suite adds **no
standalone FOR-01 scenarios**. Instead, FOR-01's CRUD paths — list, pagination, create, delete, and
i18n rendering — are exercised **transitively** through the dictionary and user CRUD smoke scenarios
in the FOR-02 (users/roles) and FOR-04 (dictionaries/work-catalog/projects) slices. Those scenarios
run against real endpoints built on the FOR-01 framework, so a regression in the CRUD framework
surfaces there. This transitive mechanism is the documented FOR-01 coverage; it deliberately avoids
duplicating the backend's unit/property tests.

### Deferred deep flows → future child specs

The smoke suite covers shallow happy paths and critical negatives only. Deeper, harder-to-automate
authentication flows are **deferred**, not covered here:

- **Google OAuth login** — the third-party redirect flow.
- **OTP / passwordless (CLIENT) login** — the one-time-code flow. For test-user provisioning the
  suite sets a password so CLIENT users can UI-login, but the OTP flow itself is out of smoke scope.

These deep flows, along with per-spec deep E2E from FOR-05 onward, live in dedicated
`FOR-QA-AUTO-0N-*` child specs rather than in this smoke suite.
