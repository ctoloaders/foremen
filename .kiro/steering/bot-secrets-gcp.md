---
inclusion: manual
---

# Standard: Adding loaders-bot secrets via Google Cloud (FOR-08)

The FOR-08 assistant reads two env-driven values, blank by default. This file documents how to
supply them safely via **Google Cloud (gcloud / Cloud Console)**. Never put the identity secret in
`docker-compose.yml`, `application*.yml`, a committed `.env`, or any `VITE_`-prefixed variable.

## The two values

| Value | Scope | Env var | Spring key | Sensitivity |
|-------|-------|---------|------------|-------------|
| Identity signing key | Backend only | `FOREMEN_BOT_IDENTITY_SECRET` | `foremen.bot.identity-secret` | **SECRET** — HS256 key, ≥ 32 bytes, never in frontend/logs. App fails fast on blank. |
| loaders-bot site key (backend mirror) | Backend | `FOREMEN_BOT_SITE_KEY` | `foremen.bot.site-key` | Public (`pk_test_`/`pk_live_`) |
| loaders-bot site key (frontend) | Frontend | `VITE_LOADERS_BOT_SITE_KEY` | — (Vite build/runtime) | Public; ships in browser bundle |

Use **separate values per environment** (sandbox `pk_test_` vs producUse **separate values per environment** (sandbox `pk_test_` vs ideUse **separate values per environment** (sandbox `pk_test_` vs producUse **separate values p2 bUse **separate values per environment** (sandbox `pk_test_` vs producUse **separate values per Secret Manager → Create secret**
- Name: `foremen-bot-identity-secret` (per env, e.g. `-prod` / `-sandbox` suffix).
- - - - - - - - - - - - - - - - - - - - - - - - - on: automatic (or policy per your standard).

gcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivalengcloud equivaleng versions add foremen-bot-identity-secret --data-file=-
```

The site key is public, so it can live as a plain env var / Cloud Run variable; storing it in Secret
Manager too is fine for a single config flow but not required.

## 3. Grant read access to the backend service account only

```bash
gcloud secrets add-iam-policy-binding foremen-bot-identity-secret \
  --member="serviceAccount:<backend-runtime-sa>@<project>.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

Scope access to the backend runtime SA — not developer or frontend identities.

## 4. Inject into the backend at runtime

**Cloud Run** (Console: service → Edit & deploy new revision → Variables & Secrets):
- Reference secret `foremen-bot-identity-secret` → exposed as env var `FOREMEN_BOT_IDENTITY_SECRET`.
- Add plain variable `FOREMEN_BOT_SITE_KEY=pk_live_...` (or sandbox key).

gcloud equivalent:

```bash
gcloud run services update <backend-service> \
  --set-secrets=FOREMEN_BOT_IDENTITY_SECRET=foremen-bot-identity-secret:latest \
  --set-env-vars=FOREMEN_BOT_SITE_KEY=pk_live_xxx
```

(GKE: mount as a `secretKeyRef` env var via External Secrets or a synced `Secret`.)

## 5. Frontend site key

Set `VITE_LOADERS_BOT_SITE_KEY` in the Set `VITE_LOADERS_BOT_SITE_KEY` in the Set `VITE_LOAent
(`pk_test_` f(`pk_test_` f(`pk_test_` f(`pk_test_` f(`pk_test_` f(`pk_testoes not need Secret Manager.
Blank → the widget is simply not embedded (graceful no-op).


lank → the widget is simply not emompose.yml` currently hardcodes `FOREMEN_JWT_SECRET` in plaintext and does **not** set the
bot vars for the `backend` service. For real deployments, source these from Secret Manager (as above),
not from the committed compose finot from the committed compose finot from the committed compose finot from the committed compose finde not from the committed compose finot from the committed compose fishort-livnot from the c so rotating `not from the committed compose finot fsk: add a
new secret version, deploy a revision pointing at `:latest`, and old tokens expire naturally.
