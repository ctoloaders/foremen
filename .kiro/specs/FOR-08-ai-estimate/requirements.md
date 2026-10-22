# Requirements Document

## Introduction

This spec (**FOR-08-ai-assistant**) integrates the **existing** third-party agentic chatbot
**loaders-bot** (`loaders.dev`) into the Foremen admin panel as an AI assistant that helps users
operate Foremen conversationally — importing a materials list, searching sellers, creating a
project, creating rooms from an uploaded plan, preparing an estimate, and driving the other main
Foremen workflows. **Foremen does not build a bot, an agent runtime, a tool executor, or a chat
UI.** All of that already exists in loaders-bot. Foremen's job is to **connect** to it:

1. **Expose a curated OpenAPI document** of the Foremen REST API that the loaders-bot admin panel
   imports as its toolset (the set of tools the agent may call).
2. **Define the agent roles** in the bot (aligned 1:1 with Foremen's Company_Roles) and the
   Tool × Role matrix so each signed-in user is offered only the tools their role can actually use.
3. **Author the agent goals and instructions** (materials-list import, seller search, project
   creation, rooms-from-plan, estimate preparation, and the other main features) in the bot's goal
   editor, each scoped to the appropriate role audience.
4. **Add the thin host-side glue** in Foremen so the widget works: embed the widget snippet on the
   authenticated admin-panel pages, issue a signed **identity token** so the bot knows who the user
   is, register a **host-callback auth provider** so the agent calls the Foremen API with the
   user's **own JWT**, and push **page context** on route changes.

The security model is the key invariant: **the agent acts only within the signed-in user's JWT.**
It inherits exactly that user's ABAC grants and project-scope — it can never do more than the user
can do in the UI. Every **write** (any non-GET operation) is designed to require the user's
**explicit in-widget confirmation** before it is sent, so the agent never mutates Foremen data
without the user approving that specific action.

### What already exists (loaders-bot) — not built here

Per `loaders-bot/docs/widget-integration.md` and `widget-protocol.md`, loaders-bot already
provides: the embeddable widget (snippet + `cdn.loaders.dev/widget/v1/loader.js`), the agent
runtime and goal router, the browser-tool executor that performs the user's API calls from the
page with the user's own credentials, the visitor-confirmation flow for write tools, role filtering,
visitor memory, consent handling, the OpenAPI toolset importer, the Tool × Role matrix, and the
goal/instruction editor with a test console. This spec **configures** those features for Foremen.

### What this spec delivers

- **Backend:** a stateless **identity-token endpoint** that signs an HS256 JWT (the loaders-bot
  identity contract) with a Foremen-held identity secret, plus a **curated OpenAPI document** of the
  Foremen API suitable for import as the bot toolset, plus the springdoc exposure needed to produce
  it. No new persisted entity.
- **Frontend:** the widget **snippet embed** on authenticated pages, **identify** on login /
  hydration and **de-identify** on logout, a **host-callback auth provider** returning the current
  Foremen bearer token (with the existing refresh behaviour), and **page context** on route change.
- **Configuration (in the loaders-bot admin panel, documented and versioned in this repo):** the
  imported toolset, the role definitions + Tool × Role matrix, and the authored goals + instructions.
- **Environment config:** the public site key and identity secret as environment-driven settings
  (sandbox `pk_test_` vs production `pk_live_`), and the CSP entries required to load the widget.

### Scope boundaries

- **In scope:** the Foremen-side glue (identity token, OpenAPI, widget embed, auth provider, page
  context), and the authored bot configuration (toolset import, roles, goals) delivered as
  versioned artifacts (OpenAPI file + a configuration document) in this repo.
- **Out of scope / already provided by loaders-bot:** the bot backend, agent runtime, tool
  executor, chat UI, confirmation UX, memory, consent UX, SSE protocol. We only drive them.
- **No new Foremen domain entity, no new ABAC resource.** The agent reuses the existing endpoints
  and the existing resource/operation matrix exactly as the human UI does. The identity-token
  endpoint is stateless (no persisted fields), so there are **no `nameRU`/`namePL` i18n entity
  fields** introduced by this spec.
- **No Foremen UI text / no PL-RU i18n keys.** The chat surface, launcher, consent banner, and all
  visitor-facing copy are rendered and localized by the loaders-bot widget itself (the widget is
  customizable on the bot side). The host-side glue renders **no** user-facing Foremen strings, so
  this spec introduces **no new `pl.json`/`ru.json` keys**. The user's current Foremen locale
  (`ru`/`pl`) is passed to the bot as an identity-token trait so the agent replies in the user's
  language.
- **No notifications.** This feature emits **no** in-app bell notifications. The agent's messaging,
  progress and goal-achieved signals are surfaced inside the widget's own chat surface (and, if
  needed, consumed transiently via `LoadersBot.on(...)`), not persisted per-recipient via the
  generic notification service. There is no domain state change in Foremen that this integration
  must announce to any recipient; a persisted notification would duplicate what the widget already
  shows. (Confirmed empty with the user.)

### Glossary

- **Widget** — the loaders-bot JS widget embedded on Foremen pages (`LoadersBot` global + the
  queued API: `init`, `identify`, `setAuthProvider`, `setConsent`, `setPageContext`, `track`,
  `open`, `close`, `on`).
- **Site key** — the loaders-bot public key identifying the Foremen site/account
  (`pk_test_…` sandbox, `pk_live_…` production). Public; safe in frontend.
- **Identity secret** — the per-site HMAC secret used to sign the identity token. **Server-side
  only**, never in frontend code; sourced from environment/secret config.
- **Identity token** — an HS256 JWT signed by Foremen with the identity secret: `sub` = Foremen
  user id, `iat`, `exp` (≤ 24 h), `traits` (e.g. `name`, `locale`), `roles` (the user's
  Company_Role code). Consumed by `LoadersBot.identify({ token })`.
- **Toolset / tool** — the curated OpenAPI document imported into the bot; each operation becomes a
  tool the agent may call.
- **Auth provider** — the host callback registered via `LoadersBot.setAuthProvider(...)` that
  returns `{ headers: { Authorization: 'Bearer <foremen-access-token>' } }` so the agent's browser
  tool calls carry the user's JWT.
- **Goal** — a configured objective in the bot (e.g. "prepare an estimate") with instructions,
  a role audience, and milestones.
- **Agent role** — a role defined in the bot's Toolset → Roles, mapped from the user's Company_Role
  via the identity token's `roles` claim; drives which goals/tools the visitor is offered.
- **Confirmation** — the widget's built-in prompt shown to the user before a write tool
  (non-GET) is executed; the user must approve the specific action.
- **Company_Role** — the Foremen single global role per user (`ADMIN`, `MANAGER`, `FOREMAN`,
  `WORKER`, `ESTIMATOR`/`SMETCHIK`, `FINANCIER`, `CLIENT`) returned by `GET /api/auth/me`.

---

## Requirements

### Requirement 1 — Identity-token endpoint (backend)

**User Story:** As a signed-in Foremen user, I want the bot to know who I am, so that it offers me
goals and tools for my role and remembers me across conversations, without exposing any secret to
the browser.

#### Acceptance Criteria

1. WHEN an authenticated user calls the identity-token endpoint (e.g. `POST /api/bot/identity-token`)
   THEN the backend SHALL return an HS256-signed JWT whose `sub` is the caller's Foremen user id
   (the `sub`/principal established by the JWT filter), `iat` = now, and `exp` ≤ 24 h from now.
2. The signed token SHALL include a `traits` claim carrying at least `name` (the user's display
   name) and `locale` (the user's current Foremen locale `ru`/`pl`, resolved from the request's
   `Accept-Language`), and a `roles` claim carrying the user's Company_Role code (from the live role
   graph, consistent with `GET /api/auth/me.roleCode`).
3. The endpoint SHALL sign with the **identity secret** read from environment/secret configuration
   (`foremen.bot.identity-secret`), and SHALL NOT expose that secret in any response or log.
4. WHEN the endpoint is called without a valid Foremen access token THEN it SHALL be rejected with
   HTTP 401 by the existing security layer (it is an authenticated endpoint).
5. The endpoint SHALL be reachable to any authenticated user regardless of Company_Role (it is a
   self-service identity operation, not an ABAC-guarded domain resource) — it carries no
   `@PermissionResource`/`@PermissionOperation` (Unguarded, like the other `/api/auth`-style
   self-service endpoints), so it never fails `PermissionAnnotationValidator`.
6. WHEN the identity secret is absent/blank at startup THEN the application SHALL fail fast with a
   clear configuration error rather than signing tokens with an empty key.
7. The endpoint SHALL be stateless: it persists nothing and introduces no new entity, table, or
   changeset.

### Requirement 2 — Curated OpenAPI toolset document (backend)

**User Story:** As the person configuring the bot, I want a clean OpenAPI document of the Foremen
API, so that I can import it into the bot's toolset importer and get well-named, correctly-scoped
tools instead of raw generated noise.

#### Acceptance Criteria

1. The backend SHALL expose an OpenAPI 3 document of the Foremen REST API (via the springdoc
   starter already on the classpath), reachable so the document can be captured and curated.
2. A **curated** OpenAPI artifact SHALL be produced and committed in this spec (e.g.
   `foremen-backend/src/main/resources/openapi/foremen-bot-toolset.yaml` or an equivalent repo
   path) containing only the operations intended as bot tools — covering projects, rooms, estimate
   / kosztorys, materials and assortment, catalog (works, prices, categories), sellers, procurement,
   dictionaries, team, and schedule — with meaningful `operationId`s, summaries, and request/response
   schemas usable by the bot.
3. The curated artifact SHALL **exclude** every endpoint the agent must never drive on the user's
   behalf: the entire `/api/auth/**` surface (login, refresh, logout, set-password, password-reset,
   otp, resend-invite, google), the external-provider webhooks (`POST /api/signatures/callback`,
   `POST /api/payments/webhook/**`), and the admin-only account/role administration endpoints
   (`/api/users`, `/api/roles`, `/api/resources`, `/api/operations`, `/api/audit`).
4. Each operation in the curated artifact SHALL declare bearer (JWT) security so the bot imports it
   as a tool that runs with the user's own token, and SHALL mark GET operations as read/non-
   destructive and non-GET operations as write (so the bot applies its confirmation policy — see
   Requirement 5).
5. The springdoc OpenAPI endpoint (`/v3/api-docs`, Swagger UI) exposure SHALL be configured
   deliberately: either kept behind authentication (document produced by an authenticated capture)
   or explicitly permit-listed — the chosen stance SHALL be documented, and if permit-listed it
   SHALL expose only the API description, never data.
6. The curated artifact SHALL be the **source of truth** imported into the bot; a documented,
   repeatable procedure SHALL describe how to (re)capture `/v3/api-docs` and re-curate it when the
   API changes.

### Requirement 3 — Agent role definitions and Tool × Role matrix (bot configuration)

**User Story:** As a user of a given role, I want the bot to offer only what my role can do, so that
I am not shown tools or goals that would just fail with 403/404.

#### Acceptance Criteria

1. The bot SHALL define agent roles aligned 1:1 with Foremen Company_Roles: `ADMIN`, `MANAGER`,
   `FOREMAN`, `WORKER`, `ESTIMATOR`, `FINANCIER`, `CLIENT`.
2. Each visitor's role SHALL be taken from the **verified** identity-token `roles` claim (not a
   client-reported probe), via an identity-claim rule per role in the bot admin panel.
3. The Tool × Role matrix SHALL assign each toolset operation to the roles whose Foremen ABAC grants
   permit it, mirroring the Foremen permission matrix (e.g. estimate-write tools → `ADMIN`,
   `MANAGER`, `ESTIMATOR`; procurement tools → `ADMIN`, `MANAGER`, `FINANCIER`; read-only catalog/
   dictionary tools → broadly readable roles). The matrix SHALL NOT expose admin-only
   account/role-administration tools to any role (they are excluded from the toolset entirely per
   Requirement 2.3).
4. The role mapping configuration SHALL be captured as a versioned configuration document in this
   spec so it is reviewable and reproducible (the bot admin-panel state is documented in-repo).
5. Role filtering in the bot is an offering convenience only; actual enforcement SHALL remain the
   Foremen backend's ABAC + project-scope on every request (a mis-scoped call still yields 403/404).

### Requirement 4 — Authored agent goals and instructions (bot configuration)

**User Story:** As a Foremen user, I want the assistant to help me accomplish the main Foremen
workflows step by step, so that I can get real work done conversationally.

#### Acceptance Criteria

1. The following **goals** SHALL be authored in the bot, each with instructions, a role audience,
   and milestones, and each grounded in the real toolset operations:
   - **Materials-list import** — help the user import/populate a project's list of materials
     (reserve/select materials for the estimate) using the estimate-materials tools.
   - **Seller search** — help the user find a material seller/supplier via the seller list/search
     tool and use the result where a seller is needed.
   - **Project creation** — walk the user through creating a project (`POST /api/projects`),
     collecting the required fields conversationally and confirming before the write.
   - **Step-by-step project management** — a guided flow covering room creation (including creating
     rooms from an uploaded plan/image), estimate preparation (apply package / apply work /
     recompute, then persist assignments), and progression through the main design-stage steps.
   - **Estimate preparation** — help assemble and refine a project's estimate (matrix preview →
     persist assignments → materials) with confirmation on each persisting step.
   - Additional goals covering the other main Foremen features MAY be authored following the same
     pattern (one goal per coherent workflow).
2. Each goal's instructions SHALL direct the agent to **confirm with the user before any write**
   and to prefer reading current state (GET tools) before proposing a change.
3. For the rooms-from-plan flow, the goal SHALL use the real image-upload path (the cross-cutting
   image endpoint) plus room create/update, and SHALL NOT assume a non-existent single "upload plan"
   endpoint; if the current API cannot create a room directly from a plan image in one call, the goal
   SHALL guide the user through the actual multi-step path.
4. The authored goals and instructions SHALL be captured as a versioned configuration document in
   this spec (so the bot state is reviewable and reproducible) and SHALL be validated in the bot's
   test console before being published.
5. Goals SHALL be scoped so a visitor is offered only goals eligible for their role (consistent with
   Requirement 3).

### Requirement 5 — Agent acts within the user's JWT; writes require explicit confirmation

**User Story:** As a Foremen user, I want the assistant to act strictly as me and to never change my
data without my explicit approval, so that I stay in control and nothing is done beyond my
permissions.

#### Acceptance Criteria

1. The widget SHALL be configured with a **host-callback auth provider**
   (`LoadersBot.setAuthProvider`) that returns the current Foremen access token as
   `Authorization: Bearer <token>` for the bearer scheme, and returns `null` when the user is signed
   out.
2. The auth-provider callback SHALL obtain the token from the existing Foremen session (the
   `foremen-access-token` managed by the auth store) and SHALL benefit from the existing refresh
   behaviour so a near-expiry token is refreshed the same way the app's own requests are.
3. Because the agent uses the user's JWT, every tool call SHALL be subject to Foremen's ABAC matrix
   and project-scope exactly as a UI request: out-of-grant calls yield 403, out-of-project-scope
   ids yield 404, and the agent can never escalate beyond the user's role.
4. Every **non-GET** tool (`POST`/`PUT`/`PATCH`/`DELETE`) SHALL require the user's **explicit
   in-widget confirmation** before execution — this is enforced by marking all non-GET operations as
   write tools (Requirement 2.4) and by not disabling the bot's default confirmation for them; GET
   tools MAY run without confirmation.
5. The user's credentials SHALL never leave the browser or be stored by loaders-bot — the browser
   tool executes the call from the Foremen page with the token supplied by the auth provider
   (per the loaders-bot browser-tool model).

### Requirement 6 — Widget embed, identify lifecycle, page context (frontend)

**User Story:** As a signed-in user, I want the assistant available on the admin-panel pages and
aware of what page I'm on, so that it can help me in context — and I don't want it on the login
screen or after I log out.

#### Acceptance Criteria

1. The widget loader snippet SHALL be installed so the widget is present on the **authenticated**
   admin-panel pages and SHALL use the environment-appropriate **site key** from configuration
   (`pk_test_` sandbox / `pk_live_` production), never a hardcoded production key in source.
2. WHEN a user is authenticated (after login and on session hydration) THEN the app SHALL call
   `LoadersBot.identify({ token })` with a freshly issued identity token (Requirement 1); WHEN the
   user logs out THEN the app SHALL call `LoadersBot.identify(null)`.
3. The widget SHALL be available to **all** authenticated users regardless of Company_Role; what the
   agent can actually do is bounded by the user's JWT (Requirement 5), not by a Foremen visibility
   gate. It SHALL NOT be initialized on public/unauthenticated routes (e.g. `/login`).
4. WHEN the user navigates between routes THEN the app SHALL push structured **page context** via
   `LoadersBot.setPageContext({...})` describing the current surface (e.g. `pageType`, and when in a
   project workspace, `projectId` / `roomId`), keeping the payload small (≤ 4 KB) and free of
   sensitive data.
5. The host-side glue SHALL render **no** user-facing Foremen text and introduce no new i18n keys;
   all visitor-facing copy is the widget's own.
6. WHEN the widget script fails to load (blocked/offline) THEN the Foremen app SHALL continue to
   function normally with no visible error attributable to the widget (graceful degradation).

### Requirement 7 — Environment configuration, CSP, consent

**User Story:** As an operator, I want the integration to be environment-correct and
security-compliant, so that sandbox and production stay separate and the browser can load the widget.

#### Acceptance Criteria

1. The **site key** and **identity secret** SHALL be environment-driven (separate sandbox and
   production values); the site key is a frontend config value, the identity secret is a backend
   secret.
2. The deployment SHALL add the Content-Security-Policy entries required by loaders-bot where a CSP
   is enforced: `script-src https://cdn.loaders.dev`, `connect-src https://api.loaders.dev`,
   `img-src https://cdn.loaders.dev` (production hosts; `dev.` variants for sandbox).
3. The integration SHALL set consent via `LoadersBot.setConsent(...)` consistent with Foremen's
   consent posture; absent a separate Foremen consent banner the default (`true`) MAY be used, and
   the chosen stance SHALL be documented.
4. Sandbox and production SHALL use their own loaders-bot accounts/keys; a key from one environment
   SHALL NOT be used in the other.

### Requirement 8 — Verification and documentation

**User Story:** As a reviewer, I want to confirm the integration is correctly scoped and safe, so
that I can trust the assistant acts only as the user and only with approval.

#### Acceptance Criteria

1. The identity-token endpoint SHALL be covered by backend tests: a valid HS256 token with the
   correct `sub`/`traits.locale`/`roles` for an authenticated caller, 401 for an unauthenticated
   caller, and fail-fast on a missing secret.
2. The curated OpenAPI artifact SHALL be validated (parses as OpenAPI 3; contains the intended
   operations; excludes the forbidden ones per Requirement 2.3).
3. An end-to-end check (via the bot test console and/or a manual run against the Docker stack) SHALL
   demonstrate: identify works, a read tool returns data within the user's scope, a write tool
   prompts for confirmation and only then mutates, and an out-of-scope call yields 403/404.
4. The bot configuration (toolset import procedure, roles + matrix, goals + instructions) SHALL be
   documented in-repo so it is reproducible without access to the live bot admin panel's prior state.
