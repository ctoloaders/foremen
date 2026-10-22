# Foremen OpenAPI — exposure stance & capture procedure (FOR-08-ai-assistant)

This document records the **springdoc exposure stance** (Req 2.1, 2.5) and the **repeatable
procedure** for capturing the raw `/v3/api-docs` document that is then curated into the bot toolset
`foremen-backend/src/main/resources/openapi/foremen-bot-toolset.yaml` (produced in task 2.2, Req 2.2,
2.6). The same procedure is mirrored into `bot-configuration.md` (task 4.1) so the toolset can be
re-captured when the API changes.

## Exposure stance (D3, Req 2.5) — behind authentication

springdoc (`springdoc-openapi-starter-webmvc-ui`, v2.8.6) is already on the classpath and emits:

- `GET /v3/api-docs` — the generated OpenAPI 3 JSON document.
- `GET /swagger-ui/index.html` — the Swagger UI.

**Decision: keep both behind authentication. Do NOT permit-list them.**

`SecurityConfig` permit-lists only `/api/auth/**`, `POST /api/signatures/callback`, and
`POST /api/payments/webhook/**`; everything else falls under the `anyRequest().authenticated()`
catch-all. `/v3/api-docs` and `/swagger-ui/**` are therefore reachable **only** with a valid Foremen
access token (an anonymous request is rejected with 401 by `JwtAuthenticationEntryPoint`).

Rationale:

- We capture the document once with an **authenticated ADMIN request**, so no public exposure is
  needed. Only the generated API **description** is ever reachable — never Foremen data.
- Leaving the docs behind auth avoids broadening the public allowlist for a build-time-only artifact.

A minimal `@OpenAPIDefinition` (title/info) plus a `bearer-jwt` HTTP security scheme is declared in
`foremen-backend/src/main/java/com/foremen/config/OpenApiConfig.java` so the captured document has a
stable title and describes the real bearer-token auth model. It adds no endpoints and changes no
behaviour.

## Capture procedure

Prerequisites: Docker + Docker Compose, and a JSON tool (`jq`) for pretty-printing.

1. **Boot the Docker stack** from the repo root. The `backend` service runs on `:8080` (profile
   `docker`); `FOREMEN_ADMIN_CREATE=true` seeds the first ADMIN from the compose env.

   ```bash
   docker compose up -d postgres liquibase backend
   # wait until the backend is healthy (GET http://localhost:8080/actuator/health or first 200)
   ```

2. **Authenticate as ADMIN** against the Dockerized app and capture the access token. The seeded
   ADMIN credentials come from `docker-compose.yml`
   (`FOREMEN_ADMIN_EMAIL` / `FOREMEN_ADMIN_PASSWORD`):

   ```bash
   TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"cto+1@loaders.dev","password":"alejano123"}' \
     | jq -r '.accessToken')
   ```

3. **GET `/v3/api-docs`** with the bearer token and save the raw document:

   ```bash
   curl -s http://localhost:8080/v3/api-docs \
     -H "Authorization: Bearer ${TOKEN}" \
     | jq '.' > /tmp/foremen-v3-api-docs.json
   ```

   (The Swagger UI is likewise reachable at
   `http://localhost:8080/swagger-ui/index.html` once authenticated.)

4. **Curate** `/tmp/foremen-v3-api-docs.json` down to the intended bot tool operations and commit the
   result as `foremen-backend/src/main/resources/openapi/foremen-bot-toolset.yaml` — this is task 2.2
   (include projects / rooms / estimate / materials&assortment / catalog / sellers / procurement /
   dictionaries / team / schedule / images; **exclude** `/api/auth/**`,
   `POST /api/signatures/callback`, `POST /api/payments/webhook/**`, and
   `/api/users|roles|resources|operations|audit`; declare the `bearer-jwt` scheme on every operation;
   mark GET as read and non-GET as write).

5. **Tear down** when done:

   ```bash
   docker compose down
   ```

## Re-capture when the API changes

Re-run steps 1–4 whenever Foremen endpoints change, then re-curate and re-commit
`foremen-bot-toolset.yaml`, and re-import it into the loaders-bot toolset (the import step is
documented in `bot-configuration.md`).

---

# Environment config, CSP & consent stance (FOR-08 task 6.1, Req 7)

> This section documents the environment-driven configuration, the Content-Security-Policy
> enforcement point, and the consent stance. It is kept here (not in `bot-configuration.md`) so
> task 3.1's authoring of the roles/matrix document is not disturbed.

## Environment-driven keys (Req 7.1, 7.4)

The site key and the identity secret are environment-driven, with **separate sandbox and production
values**, and never hardcoded:

| Value | Where | Env var | Config key | Public? |
|-------|-------|---------|------------|---------|
| Site key | **Frontend** | `VITE_LOADERS_BOT_SITE_KEY` | — (Vite build/runtime env) | Public — `pk_test_` sandbox / `pk_live_` production |
| Identity secret | **Backend** | `FOREMEN_BOT_IDENTITY_SECRET` | `foremen.bot.identity-secret` | **Secret** — backend only, never in frontend/logs |
| Site key (mirror) | **Backend** | `FOREMEN_BOT_SITE_KEY` | `foremen.bot.site-key` | Public — surfaced to the frontend via build/runtime config |

- **Frontend site key** — documented in `foremen-frontend/.env.example`. The sandbox/production split
  is driven entirely by which key is set (`pk_test_...` from the loaders-bot **sandbox** account vs
  `pk_live_...` from the **production** account); a key from one environment must not be used in the
  other (Req 7.4). Blank/absent → the widget is simply not embedded (graceful no-op, Req 6.1/6.6).
- **Backend identity secret + site key** — already added by task 1.1 in
  `foremen-backend/src/main/resources/application.yml` and `application-docker.yml`
  (`foremen.bot.identity-secret` / `foremen.bot.site-key`, both env-driven, blank by default).
  `BotIdentityTokenService` fails fast once a token is signed with a blank secret (Req 1.6). This
  task only confirms/documents that config; it does not duplicate it.

## CSP enforcement point (Req 7.2)

CSP is enforced at the **frontend nginx host** (the `frontend` Docker service →
`foremen-frontend/nginx.conf`). That is the delivered host serving the SPA `index.html`, so it is the
correct and only enforcement layer in this repo (the backend `SecurityConfig` sets no CSP, and
`index.html` carries no CSP `<meta>`).

The `location /` block in `foremen-frontend/nginx.conf` adds a `Content-Security-Policy` header
allow-listing the loaders-bot hosts with their `dev.` sandbox variants:

- `script-src ... https://cdn.loaders.dev https://dev.cdn.loaders.dev` — the widget loader JS.
- `connect-src 'self' https://api.loaders.dev https://dev.api.loaders.dev` — the agent runtime
  (`'self'` keeps the SPA's same-origin `/api/*` calls working).
- `img-src ... https://cdn.loaders.dev https://dev.cdn.loaders.dev` — widget images.

`'unsafe-inline'` is retained for `script-src`/`style-src` because `index.html` runs an inline
theme-bootstrap script and sets an inline font-size style before React mounts. When the app is served
some other way (e.g. a CDN/reverse proxy in production), the same three loaders directives must be
applied there too.

## Consent stance (Req 7.3)

Foremen has **no dedicated consent banner** of its own (the widget renders its own surface/copy,
Req 6.5). Per the documented stance, consent defaults to **`true`**: the frontend glue calls
`LoadersBot.setConsent(true)` during widget init (in
`foremen-frontend/src/features/assistant/loaders-bot.ts`, constant
`FOREMEN_LOADERS_BOT_DEFAULT_CONSENT`), immediately after `init`. If Foremen later adds its own
consent banner, that constant/call should be driven from the banner's decision instead of the
default.
