# Requirements: FOR-QA-AUTO-SMOKE

## Introduction

FOR-QA-AUTO-SMOKE delivers the shared automated E2E framework for the Foremen project and the first
smoke suite built on it. The framework is a Java + BDD (Cucumber-JVM) + Playwright-for-Java module
that drives the running application through its browser UI against the Docker stack
(`docker compose up`). The smoke suite covers the top-level, **UI-visible** requirements of the
already-completed specs FOR-01 … FOR-04, giving a fast signal that a build/deploy is functional. The
suite is designed so that its scope grows over time — each newly completed spec appends smoke
scenarios without restructuring the harness — while deep, per-spec E2E coverage lives in dedicated
child specs from FOR-05 onward.

This is a QA-automation spec: it introduces no domain entities and no new user-facing UI text, so it
carries no new i18n fields or translation keys. It only verifies existing localized behavior. All
spec documents are in English; the `test-cases.md` run scenarios are in Russian per the
`.kiro/steering/test-cases.md` standard.

### Terminology

- **Smoke scenario**: a shallow happy-path or critical negative-path check, tagged `@smoke` and
  `@FOR-0N`.
- **Page object**: a Java class encapsulating locators and actions for one screen/component.
- **API helper**: a Java client used only for setup/teardown and for asserting UI-invisible contracts
  a UI scenario depends on.
- **run-id**: a per-run unique token (timestamp/uuid) used to generate collision-free test data.

## Requirements

### Requirement 1 — Java BDD + Playwright framework module

**User Story:** As a QA engineer, I want a single Java-based BDD framework driving Playwright, so that
E2E automation lives in the project's primary backend language and integrates with its Gradle
tooling.

#### Acceptance Criteria

1. THE system SHALL provide a standalone Gradle module `foremen-qa-auto/` that is independent of the
   `foremen-backend` build (its tasks SHALL NOT trigger the backend Testcontainers suite).
2. THE module SHALL use Java 25, Cucumber-JVM (Gherkin `.feature` files + Java step definitions on
   the JUnit 5 platform), and Playwright-for-Java (`com.microsoft.playwright:playwright`).
3. THE module SHALL run Chromium headless by default AND SHALL allow headed mode and browser
   selection via configuration (system property or env var).
4. WHEN a QA engineer runs the documented Gradle task (e.g. `./gradlew :foremen-qa-auto:smokeTest`)
   THEN THE system SHALL execute all scenarios tagged `@smoke`.
5. THE module SHALL install/download the Playwright browser binaries as part of its setup so a clean
   checkout can run the suite after the documented setup step.
6. THE module SHALL centralize configuration (frontend base URL, API base URL, admin credentials,
   browser mode, timeouts) with env-var/system-property overrides and sensible defaults matching
   `docker-compose.yml`.

### Requirement 2 — Runs against the live Docker stack

**User Story:** As a QA engineer, I want the suite to run against the real running application, so
that it validates the deployed behavior end to end rather than mocks.

#### Acceptance Criteria

1. THE suite SHALL target the frontend at `http://localhost:3000` and the API at
   `http://localhost:8080` by default, overridable via configuration.
2. BEFORE executing scenarios THE suite SHALL wait for backend readiness (actuator health) and
   frontend availability, failing fast with a clear message if the stack is not up within a
   configurable timeout.
3. THE suite SHALL authenticate the seeded ADMIN using credentials read from configuration
   (defaults: `FOREMEN_ADMIN_EMAIL` / `FOREMEN_ADMIN_PASSWORD` as in `docker-compose.yml`).
4. THE suite SHALL NOT require Testcontainers or an embedded application; it SHALL assume the stack is
   started separately via `docker compose up`.

### Requirement 3 — Repeatable, self-cleaning runs

**User Story:** As a QA engineer, I want to run the suite repeatedly without manual DB cleanup, so
that runs are deterministic and CI-friendly.

#### Acceptance Criteria

1. THE suite SHALL generate a per-run `run-id` and derive unique data keys from it (users
   `test+{run-id}+{seq}@example.com`, dictionary codes `qa{run-id}{seq}`, project names
   `QA Project {run-id}`).
2. WHEN a scenario creates data THEN THE suite SHALL tear it down afterward (deactivate created users
   and log out their tokens; delete created dictionary rows/projects via their DELETE endpoints).
3. THE suite SHALL NOT destructively modify the seeded ADMIN or seed reference data.
4. THE suite SHALL use a fresh browser context (incognito) per scenario so `localStorage`/
   `sessionStorage`/cookies do not leak between scenarios.
5. WHEN the suite is run twice in a row against the same stack THEN both runs SHALL pass without
   manual intervention.

### Requirement 4 — Growing, tag-based smoke scope

**User Story:** As a QA lead, I want the smoke scope to grow as new specs are completed, so that
coverage expands by adding files rather than refactoring the harness.

#### Acceptance Criteria

1. Every smoke scenario SHALL carry a `@smoke` tag and a source tag `@FOR-0N` identifying the spec it
   covers.
2. THE smoke runner SHALL select scenarios by the `@smoke` tag so that newly added tagged `.feature`
   files are picked up automatically without runner changes.
3. THE runner SHALL support slicing by source tag (e.g. run only `@FOR-03`) via a tag-expression
   configuration.
4. THE repository SHALL document (in the module README and this spec) the exact steps to add a new
   smoke slice: add a `@smoke @FOR-0N` feature file, reuse step defs/page objects, and update the
   coverage map.
5. Deep per-spec scenarios SHALL live in dedicated `FOR-QA-AUTO-0N-*` child specs, NOT in the smoke
   suite.

### Requirement 5 — FOR-03 authentication smoke coverage

**User Story:** As a QA engineer, I want the core auth flows validated, so that a broken login/guard
is caught immediately.

#### Acceptance Criteria

1. WHEN valid ADMIN credentials are submitted on `/login` THEN THE suite SHALL verify navigation to
   `/` and presence of access + refresh tokens in `localStorage`.
2. WHEN the login form is submitted with empty fields THEN THE suite SHALL verify submission is
   blocked by client validation and no `POST /api/auth/login` request is sent.
3. WHEN invalid credentials are submitted THEN THE suite SHALL verify a localized error message is
   shown and the user stays on `/login`.
4. WHEN an unauthenticated user opens a protected deep-link THEN THE suite SHALL verify redirect to
   `/login`, and after successful login, return to the original deep-link.
5. WHEN an authenticated user triggers logout THEN THE suite SHALL verify tokens are cleared and the
   user is redirected to `/login`.

### Requirement 6 — FOR-03 menu-visibility & authorization smoke coverage

**User Story:** As a QA engineer, I want role-based UI visibility validated, so that permission
regressions are caught.

#### Acceptance Criteria

1. WHEN ADMIN is authenticated THEN THE suite SHALL verify the primary navigation groups are visible
   and the current role name is shown in the topbar.
2. GIVEN an ACTIVE user with a restricted role (e.g. READ on `USERS` only) WHEN that user is logged in
   THEN THE suite SHALL verify navigation items for resources they lack are hidden.
3. WHEN a restricted user opens a deep-link to a forbidden route THEN THE suite SHALL verify redirect
   to `/403` with a working Go_Back control.
4. THE restricted-role setup and teardown (create role + grant matrix + create/activate user, then
   remove) SHALL use the API helper and honor Requirement 3.

### Requirement 7 — FOR-02 admin-panel smoke coverage

**User Story:** As a QA engineer, I want the admin shell and its core management pages validated, so
that the SPA foundation is known-good.

#### Acceptance Criteria

1. WHEN an authenticated user loads the app THEN THE suite SHALL verify the shell renders (sidebar/
   topbar on desktop) and primary navigation is usable.
2. WHEN the Roles page is opened THEN THE suite SHALL verify the roles list renders AND opening a role
   shows the access matrix (resource × operation controls).
3. WHEN the Users page is opened THEN THE suite SHALL verify the users list renders; AND WHEN a user
   is created with a generated email THEN it SHALL appear in the list; AND the created user SHALL be
   deactivated in teardown.
4. WHEN the Appearance/theme settings page is opened THEN THE suite SHALL verify toggling the theme
   (e.g. dark↔light) persists across a page reload.

### Requirement 8 — FOR-04 base-entities smoke coverage

**User Story:** As a QA engineer, I want the dictionary/entity admin pages and the reference-filter
UX validated, so that the data foundation UI is known-good.

#### Acceptance Criteria

1. THE suite SHALL, via a data-driven scenario, open each dictionary route (`/measurement-units`,
   `/currencies`, `/vat-rates`, `/room-types`, `/work-categories`, `/delivery-categories`,
   `/delivery-statuses`, `/material-categories`, `/offer-packages`) and verify the page loads and its
   table renders with expected columns.
2. WHEN the Work Catalog page (`/catalog/works`) is opened THEN THE suite SHALL verify the list
   renders AND a work item can be created selecting category and unit via reference selects (with
   teardown).
3. WHEN the Work Prices page (`/catalog/prices`) is opened THEN THE suite SHALL verify the list
   renders and a current price (validTo null) is shown.
4. WHEN the Projects page (`/projects`) is opened THEN THE suite SHALL verify the list renders AND a
   project can be created via the happy path (with teardown).
5. WHEN a table exposing a reference filter is opened THEN THE suite SHALL verify applying a
   single-value reference filter narrows the list.
6. THE suite SHALL verify the "Catalog" and "Dictionaries" navigation groups are present and their
   items route to the correct pages (FOR-04-15).

### Requirement 9 — FOR-01 transitive validation

**User Story:** As a QA engineer, I want the CRUD framework exercised indirectly, so that its
correctness is covered without duplicating backend unit tests.

#### Acceptance Criteria

1. THE suite SHALL NOT add standalone FOR-01 scenarios (FOR-01 has no UI surface).
2. THE dictionary/user CRUD smoke scenarios SHALL exercise the FOR-01 CRUD framework paths (list,
   pagination, create, delete, i18n rendering) transitively, and this SHALL be documented as the
   FOR-01 coverage mechanism.

### Requirement 10 — Human-readable, demo-style reports with per-step screenshots

**User Story:** As a client representative (non-technical), I want the test results as readable,
demo-like reports with screenshots, so that I can follow how each feature works by reading the report
— seeing every step and its screenshot, from setup through verification through cleanup.

#### Acceptance Criteria

1. THE suite SHALL produce, for each run, a **human-readable report** that reads like a documented
   demo: organized by feature and scenario, showing each scenario's steps **in order** with, for
   every step, a short human-readable description, the expected outcome, the actual outcome, the
   status, and a **screenshot of the UI at that step**.
2. THE report SHALL capture a screenshot at **each meaningful UI step** (navigation, input, click,
   assertion), not only on failure, so the reader can visually follow the feature end to end.
3. FOR a CRUD scenario (e.g. Roles) THE report SHALL clearly show the full lifecycle: the create
   steps, the verification that the entity was created (with the confirming screenshot), and the
   cleanup of created resources — so a reader sees the feature demonstrated and left clean.
4. THE report SHALL be viewable without technical tooling: an **HTML report** (self-contained or with
   an accompanying assets folder) that opens in a browser, with embedded/linked screenshots and clear
   pass/fail indicators per step and per scenario.
5. THE report SHALL be understandable by a non-technical reader: step descriptions in plain language
   (business terms, not selectors or code), a per-scenario summary, and a run-level summary
   (feature/scenario counts, passed/failed/skipped, run-id, timestamp, environment).
6. THE report SHALL group scenarios by source spec (`@FOR-0N`) and feature so the reader can navigate
   to a specific feature's demo.
7. ON failure THE report SHALL highlight the failing step and include the failure screenshot plus
   diagnostics (e.g. Playwright trace link) sufficient to diagnose it, without breaking the
   demo-style flow of the passing steps.
8. THE suite SHALL ALSO emit machine-readable outputs (Cucumber JSON and the standard Cucumber HTML,
   plus the `.kiro/steering/test-cases.md`-style MD run-report tables) for CI and for the QA
   standard, in addition to the client-facing demo report.
9. THE report generation SHALL be deterministic and repeatable: each run writes to a run-scoped
   output folder (keyed by `run-id`/timestamp) so historical reports are preserved and comparable.
10. Screenshots SHALL avoid leaking secrets: the report SHALL NOT embed real credentials or tokens in
    visible text; generated test data (emails, codes) is acceptable.

### Requirement 11 — Documentation

**User Story:** As a new contributor, I want to know how to run and extend the suite, so that I can be
productive quickly.

#### Acceptance Criteria

1. THE module SHALL include a `README.md` documenting prerequisites, how to bring up the stack
   (`docker compose up`), and how to run `@smoke` (and slice by `@FOR-0N`).
2. THE README SHALL document the page-object + step-definition structure and the exact recipe for
   adding a new smoke slice for a future spec.
3. THE README SHALL state the repeatability strategy and confirm no manual DB cleanup is required
   between runs.

### Requirement 12 — Reusable role-based test-user provisioning (direct DB)

**User Story:** As a QA engineer, I want two reusable Gherkin steps to create and clean up a test user
in a given role, so that any scenario can render and exercise the UI under that role — the only way to
see role-specific UI is to log in as an ACTIVE user with a known password.

#### Acceptance Criteria

1. THE framework SHALL expose a reusable Gherkin step to **create a user in role X** that generates a
   run-unique email and password, resolves `role_id` from `roles.code = X`, and provisions a
   login-ready user.
2. THE provisioning SHALL insert the user **directly into the test database via JDBC** (not via the
   application API), consistent with running against an isolated test database.
3. THE stored password SHALL be a **bcrypt** hash of the generated password (compatible with the
   backend's bcrypt verification), and the inserted user SHALL have `status=ACTIVE` and `active=true`
   so UI password-login succeeds.
4. THE create step SHALL store the resulting username (email), password, and user id in the scenario
   `World` so later steps can UI-login as that user and reference it.
5. THE create step SHALL support all roles by `roles.code` (ADMIN, MANAGER, FOREMAN, WORKER,
   FINANCIER, CLIENT, and custom `TESTROLE_*`). FOR CLIENT THE step SHALL still set a password so UI
   password-login works in tests (OTP is not required for provisioning).
6. THE framework SHALL expose a reusable Gherkin step to **delete the user in role X and clean up all
   its resources**, removing FK-dependent rows first (`project_members`, `refresh_tokens`,
   `invite_tokens`, `otp_tokens` by email), then run-created dictionary rows and projects tracked in
   `World`, then the `users` row.
7. THE create step SHALL register the user for automatic teardown so that even scenarios that do not
   explicitly call the delete step are cleaned up (honoring Requirement 3).
8. THE provisioning logic SHALL live **only inside the QA test code** (step definitions + a
   `TestUserFixture` helper); it SHALL NOT add code to the application.
9. IF a custom role code is requested that does not exist THEN THE create step SHALL either create the
   role (and register it for teardown) or fail with a clear message, per configuration; existing
   system roles SHALL never be created or deleted.
10. THE DB connection parameters (JDBC URL, user, password) SHALL come from configuration with
    defaults matching `docker-compose.yml` (`jdbc:postgresql://localhost:5432/foremen`,
    `foremen`/`foremen`), overridable via env/system property.

## Non-Functional Requirements

- **Isolation**: no coupling to the backend build; the E2E module builds and runs independently.
- **Determinism**: unique data per run + teardown; fresh browser context per scenario.
- **Resilience**: explicit readiness waits and Playwright auto-waiting; no fixed `sleep`-based waits.
- **Locators**: prefer role/label/test-id locators over brittle CSS/XPath.
- **Portability**: configuration via env vars/system properties; defaults match `docker-compose.yml`.
- **CI-friendliness**: headless by default; single Gradle entry point; machine-readable reports.
