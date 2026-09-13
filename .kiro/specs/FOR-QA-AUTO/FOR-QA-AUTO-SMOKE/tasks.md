# Implementation Plan: FOR-QA-AUTO-SMOKE

Tasks are ordered so the framework skeleton comes first, then support/infra, then page objects, then
smoke feature slices per source spec, then reporting and docs. Each task references the requirements
it satisfies. Verification runs against a live stack (`docker compose up`); scenarios are selected by
the `@smoke` tag.

- [x] 1. Scaffold the `foremen-qa-auto` Gradle module
  - Create `foremen-qa-auto/build.gradle` (Java 25, JUnit 5 platform, Cucumber-JVM,
    Playwright-for-Java, AssertJ, PostgreSQL JDBC driver, HikariCP, `spring-security-crypto` for
    bcrypt) and register the module so it builds independently of `foremen-backend`.
  - Add the Playwright browser install step (Gradle task or documented command) so a clean checkout
    can run the suite.
  - Add `RunSmokeTest.java` JUnit 5 Cucumber suite selecting `@smoke` (tag filter overridable).
  - _Requirements: 1.1, 1.2, 1.4, 1.5_

- [x] 2. Implement configuration and browser lifecycle
  - `TestConfig`: resolve frontend/API URLs, admin creds, browser mode/name, timeouts, tag filter
    from system property → env → default (defaults match `docker-compose.yml`).
  - `PlaywrightFactory` + `Hooks`: Chromium headless by default, headed/other-browser via config;
    fresh `BrowserContext`/`Page` per scenario (incognito); optional trace.
  - `WaitFor`: readiness gate on backend actuator health and frontend availability, fail-fast with a
    clear message.
  - _Requirements: 1.3, 1.6, 2.1, 2.2, 2.4, 3.4_

- [x] 3. Implement the API helper for setup/teardown
  - `ApiHelper` over Playwright `APIRequestContext`: admin login, create role + set permissions +
    delete role, create/delete dictionary row, create/delete project.
  - `DataGen` (run-id + unique emails/passwords/codes/project names) and `World.registerTeardown`
    (LIFO cleanup executed in `@After`).
  - _Requirements: 2.3, 3.1, 3.2, 3.3_

- [x] 3a. Implement the reusable test-user provisioning module (direct DB, bcrypt)
  - `Db` (HikariCP `DataSource` from `TestConfig`: `QA_DB_URL`/`QA_DB_USER`/`QA_DB_PASSWORD`,
    defaults per `docker-compose.yml`).
  - `TestUserFixture`: `resolveRoleId(code)`, `createActiveUser(roleCode[, email, password])`
    (generate creds → bcrypt via `BCryptPasswordEncoder` → parameterized INSERT with `status=ACTIVE`,
    `active=true` → return `TestUser`), and `deleteUserAndResources(user)` (FK-safe cascade:
    project_members → refresh_tokens → invite_tokens → otp_tokens(by email) → run-created dictionary
    rows/projects → users). Idempotent cleanup.
  - `TestUserSteps`: reusable Gherkin steps — "a test user with role X exists" (stores email/password/
    id in `World`, registers teardown), "I log in through the UI as the test user", "the test user
    with role X is deleted with all its resources". Role-parameterized incl. CLIENT (password set for
    UI login) and custom `TESTROLE_*` (created/removed per config, system roles never touched).
  - Provisioning logic lives only in QA test code; nothing added to the application.
  - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8, 12.9, 12.10_

- [x] 4. Implement core page objects (shell, table, login)
  - `LoginPage`, `AppShell` (nav visibility, role badge, responsive), `DataTablePage` (rows/columns/
    pagination/search), `ReferenceFilter`, `ForbiddenPage`.
  - Centralize routes in page objects; prefer role/label/test-id locators.
  - _Requirements: 5.1, 6.1, 7.1, 8.1, 8.5_

- [x] 5. FOR-03 auth smoke slice
  - `for03/auth_login.feature` + `auth_guard.feature` + `auth_logout.feature`, `AuthSteps`,
    `CommonSteps` (stack-ready background, login-as).
  - Cover valid login + token storage, empty-field client validation (no request), invalid-creds
    localized error, deep-link guard round-trip, logout clears tokens.
  - Tag `@smoke @FOR-03`.
  - _Requirements: 4.1, 5.1, 5.2, 5.3, 5.4, 5.5_

- [x] 6. FOR-03 menu-visibility smoke slice
  - `for03/menu_visibility.feature`, `NavVisibilitySteps`; restricted-role setup via `ApiHelper`
    (create role + grant `USERS:READ`) combined with the reusable test-user provisioning step
    (task 3a) to create/activate the user in that role, remove after.
  - Cover admin sees all nav + role badge; restricted role hides forbidden items; deep-link denied →
    `/403` with Go_Back.
  - Tag `@smoke @FOR-03`.
  - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [x] 7. FOR-02 admin-panel smoke slice
  - Page objects `UsersPage`/`UserFormSheet`, `RolesPage`/`RoleMatrix`, `ThemeSettingsPage`.
  - Feature files `for02/app_shell.feature`, `roles_admin.feature`, `users_admin.feature`,
    `theme_settings.feature`; `AdminPanelSteps`.
  - Cover shell renders + nav usable; roles list + access matrix; users list + create (generated
    email) + deactivate teardown; theme toggle persists across reload.
  - Tag `@smoke @FOR-02`.
  - _Requirements: 4.1, 7.1, 7.2, 7.3, 7.4, 9.2_

- [x] 8. FOR-04 base-entities smoke slice
  - Page objects `DictionaryPage` (route-parameterized), `WorkCatalogPage`, `WorkPricesPage`,
    `ProjectsPage`.
  - Feature files: `for04/dictionaries.feature` (Scenario Outline over the nine dictionary routes),
    `reference_filter.feature`, `work_catalog.feature`, `work_prices.feature`, `projects.feature`,
    `menu_grouping.feature`; `DictionarySteps`.
  - Cover: each dictionary page loads + table renders; reference filter narrows list; work-catalog
    create with FK selects (teardown); work-prices current price shown; project create happy path
    (teardown); Catalog/Dictionaries nav groups present and route correctly.
  - Tag `@smoke @FOR-04`.
  - _Requirements: 4.1, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 9.2_

- [x] 9. Reporting — client-facing demo report with per-step screenshots
  - `DemoReportPlugin` (Cucumber `TestStepFinished` listener): capture a full-page screenshot after
    each Gherkin step, record `{scenario, step, expected/actual, status, screenshot, error?}` into a
    run-scoped `report.json`; `Demo.capture(page, caption)` helper for extra captioned frames.
  - `ReportWriter`: render a browser-openable demo `index.html` (run summary + navigation grouped by
    `@FOR-0N`/feature) and per-feature pages (ordered steps with plain-language descriptions,
    expected/actual, status chips, and screenshots incl. the full CRUD lifecycle create→verify→
    cleanup). Self-contained HTML + `assets/` (screenshots, traces, css).
  - Also emit Cucumber JSON + standard Cucumber HTML and the steering-standard MD run-report tables.
  - On failure: highlight the failing step, embed failure screenshot + trace link; keep passing steps
    readable. Redact secrets (no password/token text in the report). Run-scoped output folder per
    run-id (history preserved). Config: `QA_REPORT_DIR`, `QA_SHOTS` (`per-step` default),
    `QA_FULLPAGE_SHOTS`, `QA_REPORT_KEEP_HISTORY`.
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10_

- [ ] 10. Growing-scope wiring and verification
  - Confirm tag-based selection auto-discovers new `@smoke @FOR-0N` feature files (add a throwaway
    tagged feature, confirm it runs, remove it).
  - Confirm source-tag slicing (`@smoke and @FOR-03`) works.
  - Run the full `@smoke` suite twice against a live stack to confirm repeatability with no manual DB
    cleanup.
  - _Requirements: 3.5, 4.2, 4.3_

- [x] 11. Documentation
  - `foremen-qa-auto/README.md`: prerequisites, bring up stack, run `@smoke`, slice by `@FOR-0N`,
    page-object/step structure, the exact recipe to add a new smoke slice for a future spec, and the
    repeatability statement.
  - Note the FOR-01 transitive-coverage mechanism and the deferred deep flows (Google/OTP) → future
    child specs.
  - _Requirements: 4.4, 4.5, 9.1, 11.1, 11.2, 11.3_

- [x] 12. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
    (feature grouping, step-by-step scenarios, repeatability via generator + clean-up, regression
    group, MD report template). This is a UI spec, so scenarios are browser-engine scenarios executed
    by the framework; result artifacts are MD reports with tables. Written in Russian per the
    standard.
  - _Requirements: 5, 6, 7, 8, 10 (the UI-visible behaviors the smoke suite validates)_

- [ ] 13. Final checkpoint
  - Verify all `@smoke @FOR-02/03/04` scenarios pass against a fresh `docker compose up`; MD +
    Cucumber reports are generated; README is complete; the coverage maps in this spec's OVERVIEW and
    the FOR-QA-AUTO parent OVERVIEW match the implemented feature files.
  - _Requirements: all_
