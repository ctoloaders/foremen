# Implementation Plan

This plan integrates the existing loaders-bot widget into Foremen. It delivers the backend
identity-token endpoint, the curated OpenAPI toolset, the frontend glue (embed / identify / auth
provider / page context), and the in-repo bot configuration (roles, matrix, goals). No new entity,
no new ABAC resource, no new i18n keys, no notifications.

> **Repo note:** backend + OpenAPI + spec/config docs commit from the **root** repo; the frontend
> glue commits from inside **`foremen-frontend/`** (separate git repo). A change spanning both needs
> a commit in each repo.

## Parallel execution plan (waves)

- **Wave 1 (parallel):** 1.1, 2.1, 4.1 — backend identity-token service/endpoint, springdoc
  exposure/capture setup, and the bot-configuration document skeleton are mutually independent
  (different files, no shared mutable state).
- **Wave 2 (parallel):** 1.2, 2.2, 3.1, 5.1 — backend identity-token tests (depend on 1.1); curated
  OpenAPI artifact + validation (depends on 2.1); frontend snippet embed + init (independent of
  backend); role/matrix configuration (depends on the toolset existing, i.e. 2.2 for tool names, but
  authored in the config doc started in 4.1). To keep waves honest, 3.1 runs after 2.2 (needs the
  tool list) — see dependency annotations; it is therefore placed in Wave 3, not Wave 2.
- **Wave 3 (parallel):** 3.1 (roles + Tool × Role matrix, needs 2.2), 5.2 (identify lifecycle, needs
  1.1 + 5.1), 5.3 (auth provider, needs 5.1), 5.4 (page context, needs 5.1).
- **Wave 4 (parallel):** 4.2 (author + test goals, needs 2.2 + 3.1 + 5.x so the end-to-end path
  works), 6.1 (CSP + env config, needs 5.1).
- **Wave 5 (serial, dependency-heavy):** 7.1 (Author test-cases.md), then 8.1 (end-to-end
  verification), then 8.2 (final checkpoint). These depend on everything above.

---

- [x] 1. Backend — identity-token endpoint
  - [x] 1.1 Implement `BotIdentityTokenService` + `BotIdentityController` (`POST /api/bot/identity-token`)
    - Sign an HS256 JWT with `sub` = user id, `iat`, `exp` ≤ 24 h, `traits{name, locale}`,
      `roles=[roleCode]` using jjwt and the `foremen.bot.identity-secret` config value; resolve
      `name`/`roleCode` via `AuthService.currentUser(userId)` and `locale` from `Accept-Language`
      (`ru` else `pl`). Endpoint is authenticated and carries no permission annotations (Unguarded).
      Fail fast at startup when the secret is blank. Add `foremen.bot.identity-secret` and
      `foremen.bot.site-key` to config (env-driven). Stateless — no entity/table/changeset.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_
    - _Depends on: none_
  - [x] 1.2 Backend tests for the identity-token endpoint
    - Valid token (correct `sub`/`traits.locale`/`roles`) for an authenticated caller; 401 for an
      unauthenticated caller; fail-fast on a missing/blank secret. Run only these classes with
      `--tests`, redirect to a temp log, verify via the JUnit XML.
    - _Requirements: 8.1_
    - _Depends on: 1.1_

- [x] 2. Backend — curated OpenAPI toolset
  - [x] 2.1 Configure + capture springdoc OpenAPI
    - Confirm the springdoc stance (keep `/v3/api-docs` + Swagger UI behind auth; capture with an
      ADMIN token — do not permit-list data) and optionally add a minimal `@OpenAPIDefinition`
      (title/info). Document the capture procedure (boot Docker stack, authenticate, `GET
      /v3/api-docs`).
    - _Requirements: 2.1, 2.5_
    - _Depends on: none_
  - [x] 2.2 Produce + commit the curated `foremen-bot-toolset.yaml`
    - Curate the captured spec to the tool operations (projects, rooms, estimate, materials/
      assortment, catalog, sellers, procurement, dictionaries, team, schedule, images) with good
      `operationId`s/summaries; **exclude** `/api/auth/**`, `POST /api/signatures/callback`, `POST
      /api/payments/webhook/**`, and `/api/users|roles|resources|operations|audit`. Declare bearer
      security on every operation; mark GET read / non-GET write. Commit at the documented path. Add
      a validation test/CI check: parses as OpenAPI 3, intended operations present, excluded paths
      absent.
    - _Requirements: 2.2, 2.3, 2.4, 2.6, 8.2_
    - _Depends on: 2.1_

- [x] 3. Bot configuration — roles + Tool × Role matrix
  - [x] 3.1 Define the seven agent roles + the Tool × Role matrix (in the bot admin panel; mirror in `bot-configuration.md`)
    - Roles ADMIN/MANAGER/FOREMAN/WORKER/ESTIMATOR/FINANCIER/CLIENT mapped from the verified
      identity-token `roles` claim (identity-claim rule per role). Assign each imported tool to the
      roles whose Foremen ABAC grants permit it (estimate-write → ADMIN/MANAGER/ESTIMATOR;
      procurement → ADMIN/MANAGER/FINANCIER; reads broadly). Capture as the versioned config doc.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_
    - _Depends on: 2.2_

- [x] 4. Bot configuration — goals + instructions
  - [x] 4.1 Create the `bot-configuration.md` skeleton (toolset import procedure, roles, matrix, goals sections)
    - In-repo mirror of the bot admin-panel state so configuration is reviewable/reproducible
      (D7). Sections for toolset import + host-callback auth, roles, matrix, and goals.
    - _Requirements: 3.4, 4.4, 8.4_
    - _Depends on: none_
  - [x] 4.2 Author + test-console-validate the goals
    - Author: materials-list import, seller search, project creation, step-by-step project
      management (incl. rooms-from-plan via image upload + room create/update), estimate preparation
      (preview → confirm persisting assignments/reserve), and further main-feature goals. Each with
      role audience, milestones, and "read-before-write, confirm-before-write" instructions.
      Validate each in the bot test console; record results in `bot-configuration.md`.
    - _Requirements: 4.1, 4.2, 4.3, 4.5_
    - _Depends on: 2.2, 3.1, 4.1, 5.2, 5.3, 5.4_

- [x] 5. Frontend — widget glue (commit from `foremen-frontend/`)
  - [x] 5.1 Snippet embed + init on authenticated app
    - Inject the loader snippet once on the authenticated shell (not on `/login`); site key from
      `VITE_LOADERS_BOT_SITE_KEY` (env-driven, never hardcoded). Graceful degradation if the script
      fails to load. No new i18n keys / no Foremen UI text.
    - _Requirements: 6.1, 6.3, 6.5, 6.6_
    - _Depends on: none_
  - [x] 5.2 Identify lifecycle
    - On authenticated (post-login + session-hydration-authenticated) request a fresh identity token
      (`POST /api/bot/identity-token`) and call `LoadersBot.identify({ token })`; on
      logout/`clearSession` call `LoadersBot.identify(null)`. Unit-test with the `LoadersBot` global
      stubbed.
    - _Requirements: 6.2_
    - _Depends on: 1.1, 5.1_
  - [x] 5.3 Host-callback auth provider
    - Register `LoadersBot.setAuthProvider` returning `{ headers: { Authorization: 'Bearer <token>' }}`
      from the current `foremen-access-token`, `null` when signed out. Relies on the existing refresh
      path to keep the token fresh. Unit-test both branches.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_
    - _Depends on: 5.1_
  - [x] 5.4 Page context on route change
    - Push `LoadersBot.setPageContext({...})` on navigation (`pageType`, and `projectId`/`roomId`
      inside a project workspace); payload ≤ 4 KB, no sensitive data.
    - _Requirements: 6.4_
    - _Depends on: 5.1_

- [x] 6. Environment, CSP, consent
  - [x] 6.1 Env config + CSP + consent stance
    - Env-driven site key (frontend) and identity secret (backend), separate sandbox/production.
      Add CSP entries (`script-src`/`connect-src`/`img-src` for loaders hosts; `dev.` for sandbox).
      Set `LoadersBot.setConsent(...)` per the documented stance (default `true`).
    - _Requirements: 7.1, 7.2, 7.3, 7.4_
    - _Depends on: 5.1_

- [x] 7. Test cases
  - [x] 7.1 Author test-cases.md
    - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard
      (feature grouping, step-by-step scenarios, repeatability via generator/clean-up, regression
      group, MD report template). This spec is primarily API + integration: the identity-token
      scenarios are API tests against the Dockerized app; the agent/tool scenarios are run via the
      bot test console and documented as MD-report tables.
    - _Requirements: 1.*, 2.*, 5.*, 8.*_
    - _Depends on: 1.1, 2.2, 3.1, 4.2, 5.2, 5.3, 5.4, 6.1_

- [x] 8. Verification + checkpoint
  - [x] 8.1 End-to-end verification
    - Via the bot test console + Docker stack: identify works; a read tool returns in-scope data; a
      write tool prompts confirmation then mutates; an out-of-scope id yields 403/404. Record results
      in the test-cases MD report.
    - _Requirements: 8.3_
    - _Depends on: 7.1_
  - [x] 8.2 Final checkpoint
    - Confirm: no new entity/table/changeset; curated OpenAPI excludes the forbidden paths; no new
      i18n keys; no notifications emitted; backend tests + OpenAPI validation green; bot
      configuration captured in-repo. Commit backend/OpenAPI/spec from the root repo and the
      frontend glue from `foremen-frontend/` (only when the user asks to commit).
    - _Requirements: 8.1, 8.2, 8.3, 8.4_
    - _Depends on: 8.1_
