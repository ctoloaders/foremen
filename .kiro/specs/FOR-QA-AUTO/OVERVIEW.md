# FOR-QA-AUTO: Automated E2E Test Suite (Java BDD + Playwright)

## Overview

FOR-QA-AUTO is the parent spec for the project's **automated end-to-end (E2E) test suite**. It
delivers a **Java-based BDD framework driving Playwright** that exercises the Foremen application
through its real UI (and, where a scenario is UI-invisible, its HTTP API) against the running Docker
stack (`docker compose up`).

The suite is the executable counterpart of the `test-cases.md` artifacts that every functional spec
already ships (per the `.kiro/steering/test-cases.md` standard). Where those documents describe
scenarios for a "live tester" that are "in fact executed by a browser engine", FOR-QA-AUTO turns
them into runnable Java BDD scenarios.

## Goals

1. **One test technology for the whole project**: a single Java + BDD + Playwright module, so QA
   automation lives next to the backend it validates and uses the same language and tooling.
2. **Smoke coverage of everything shipped so far**: a broad, shallow smoke suite covering the
   top-level, **UI-visible** requirements of the completed specs **FOR-01 … FOR-04**.
3. **A scope that grows over time**: the smoke suite is designed so new specs plug their smoke
   scenarios in without restructuring existing tests. Each newly completed feature spec adds a
   registered smoke slice.
4. **Deep per-spec suites from FOR-05 onward**: each new feature spec (starting with FOR-05) gets its
   own child QA spec under FOR-QA-AUTO with detailed E2E scenarios, beyond what the smoke suite
   checks.

## Scope boundaries

- **In scope**: automated E2E validation of behavior **observable through the browser UI**, plus the
  minimal API calls needed for test setup/teardown and for verifying UI-invisible contracts that a UI
  scenario depends on (e.g. the `GET /api/auth/me` ETag behind hydration).
- **Out of scope**: unit/property/integration tests that already live in `foremen-backend` (JUnit +
  jqwik + Testcontainers). FOR-QA-AUTO does **not** duplicate them and does **not** replace the
  per-spec `test-cases.md` documents — it automates the UI-facing subset of them.
- **Out of scope**: pure backend API-only specs' deep contract testing (that stays in each spec's own
  `test-cases.md` API part), except where it backs a UI scenario or is needed for the smoke path.

## Relationship to existing artifacts

| Artifact | Role | FOR-QA-AUTO relationship |
|----------|------|--------------------------|
| Per-spec `test-cases.md` | Human-readable scenarios (RU), UI + API | Source of truth for scenarios; FOR-QA-AUTO automates the UI-visible subset |
| `foremen-frontend/e2e` (Playwright TS) | Existing TS Playwright harness | Kept as-is; FOR-QA-AUTO is the Java-owned suite requested for cross-project QA. It does not remove the TS harness |
| `foremen-backend` tests (JUnit/jqwik/Testcontainers) | Backend unit/integration | Not touched; complementary layer |
| `docker compose up` | Full running stack (postgres, liquibase, backend :8080, frontend :3000) | The single environment the E2E suite runs against |

## Technology direction (detailed in child specs)

- **Language/build**: Java 25 + Gradle (aligned with `foremen-backend`), as a **separate Gradle
  module/project** (e.g. `foremen-qa-auto/`) so the ~20-minute backend Testcontainers build is never
  coupled to E2E runs.
- **BDD layer**: Cucumber-JVM (Gherkin `.feature` files, step definitions in Java, JUnit 5 platform).
- **Browser automation**: Microsoft Playwright for Java (`com.microsoft.playwright:playwright`),
  Chromium headless by default, headed/other browsers optional via config.
- **API helpers**: a thin Java HTTP client (Playwright `APIRequestContext` or `java.net.http`) for
  setup/teardown (admin login, create/deactivate users, fetch tokens) and API assertions.
- **Reporting**: Cucumber HTML/JSON reports plus the **MD run-report tables** mandated by the
  `test-cases.md` steering standard (step → action/request → expectation → actual → status).
- **Repeatability**: generator-based unique data (`run-id`) + teardown, matching the repeatability
  strategy every `test-cases.md` already declares. No manual DB cleanup between runs.

## Child specs

| # | Child spec | Type | Description | Status |
|---|-----------|------|-------------|--------|
| SMOKE | FOR-QA-AUTO-SMOKE | framework + smoke | The BDD+Playwright framework itself **and** the growing smoke suite covering top-level UI requirements of FOR-01 … FOR-04. Defines the registration mechanism new specs use to append smoke scenarios. | planned |
| 05 | FOR-QA-AUTO-05-project-estimate *(future)* | detailed E2E | Deep UI E2E for FOR-05 (project estimate / kosztorys) | future |
| 06 | FOR-QA-AUTO-06-work-schedule *(future)* | detailed E2E | Deep UI E2E for FOR-06 | future |
| … | FOR-QA-AUTO-NN-<feature> *(future)* | detailed E2E | One child spec per new feature spec from FOR-05 onward | future |

> **Numbering:** child specs from FOR-05 onward mirror the source spec number
> (`FOR-QA-AUTO-05-*` validates `FOR-05-*`, etc.). FOR-QA-AUTO-SMOKE is the special, always-present
> child that owns the shared framework and the cross-spec smoke path.

## How the smoke scope grows

1. The framework (owned by FOR-QA-AUTO-SMOKE) exposes a **tag-based registration convention**: every
   smoke scenario carries a `@smoke` tag plus a `@FOR-0N` source tag.
2. When a spec is completed, its smoke slice is added as a new `.feature` file (or feature section)
   tagged `@smoke @FOR-0N`, and the FOR-QA-AUTO-SMOKE OVERVIEW's coverage table gets a new row.
3. The smoke runner selects `@smoke`; the growing set is picked up automatically — no test-harness
   refactor is required to extend coverage.
4. Deep scenarios for a spec live in that spec's dedicated child spec (from FOR-05), not in the smoke
   suite.

## i18n note

FOR-QA-AUTO is a **QA automation** spec. It introduces **no new domain entities** (hence no new
`nameRU`/`namePL` fields) and **no new user-facing UI text** requiring PL/RU localization. It only
**verifies** existing localized behavior (e.g. RU/PL error messages, language switcher) that the
functional specs already implemented. All FOR-QA-AUTO spec documents are written in **English**;
per-spec `test-cases.md` run scenarios are written in **Russian** per the `test-cases.md` steering
standard.

## Environment (shared by all child specs)

- Bring up the stack from the repo root: `docker compose up` → `postgres` (:5432), `liquibase`
  (migrations), `backend` (:8080, profile `docker`), `frontend` (:3000).
- Frontend base URL for UI scenarios: `http://localhost:3000`.
- API base URL for setup/teardown and API assertions: `http://localhost:8080` (auth under
  `/api/auth`).
- The first ADMIN is bootstrapped via backend env: `FOREMEN_ADMIN_CREATE=true`,
  `FOREMEN_ADMIN_EMAIL` (docker-compose: `cto+1@loaders.dev`), `FOREMEN_ADMIN_PASSWORD`
  (docker-compose: `alejano123`). The automation reads the actual values from the run environment.
- The suite waits for backend readiness (actuator health) and frontend availability before running.

---

*Created: September 2026*
