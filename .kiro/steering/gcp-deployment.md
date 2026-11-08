---
inclusion: manual
---

# Standard: GCP Deployment Topology (dev) — backend + frontend

Companion to `git-repo-structure.md`. That file says **which repo** a change belongs in; this file
says **how each repo reaches production** and what infra it runs on. Scanned live from the GCP
project on 2026-10-23; re-verify with `gcloud` if infra drifts.

> **Not Cloud Run.** Despite the microservice shape, Foremen dev does **not** run on Cloud Run. It
> runs as a Docker Compose stack on a single **Compute Engine VM**. (The Cloud Run services
> `receipt-bot` / `bitrix-event` in the same project are unrelated to Foremen.)

## Project & identity

| Thing | Value |
|-------|-------|
| GCP project | `starry-tracker-505110-s3` |
| Region / zone | `europe-central2` / `europe-central2-a` |
| VM | `foremen-dev` (e2-medium, RUNNING), external IP `34.116.142.204`, network tag `foremen-dev` |
| VM service account | `foremen-dev-vm@starry-tracker-505110-s3.iam.gserviceaccount.com` (scope `cloud-platform`) |
| Public URL | `https://dev.foremen.eu` (Caddy TLS, Let's Encrypt HTTP-01) |
| Artifact Registry | `europe-central2-docker.pkg.dev/starry-tracker-505110-s3/foremen` (repo `foremen`) |

**gcloud access note:** the `info@foremen.eu` account can `describe`/act on named resources and
Secret Manager, but broad `list` calls (`projects list`, `compute instances list`,
`services list`) return empty due to IAM scoping. Query resources **by name**, not by listing.
OS Login is enabled (`enable-oslogin=TRUE`); CI and admins SSH **through IAP**
(`--tunnel-through-iap`) with passwordless sudo.

## CI/CD — push to `main` → Cloud Build → VM

Each repo has its own Cloud Build trigger (Terraform-managed) firing on **push to `main`**:

| Repo | Trigger source | Build config | Image |
|------|----------------|--------------|-------|
| `ctoloaders/foremen` (root) | push to `main` | `cloudbuild.backend.yaml` (repo root) | `…/foremen/backend:dev` + `:<short-sha>` |
| `ctoloaders/foremen-frontend` | push to `main` | `foremen-frontend/cloudbuild.frontend.yaml` | `…/foremen/frontend:dev` + `:<short-sha>` |

Both pipelines: `docker build` → push `:dev` + `:<short-sha>` to Artifact Registry → SSH to the VM
over IAP and run `/opt/foremen/deploy-service.sh <service>`, which pulls the new image and recreates
**only that one service** (the others keep running), waiting for a stable healthy state.

**Backend is schema-first.** `cloudbuild.backend.yaml` SCPs the repo's `foremen-backend/database_files`
to the VM *before* deploy; `deploy-service.sh backend <staged>` swaps them into
`/opt/foremen/database_files`, force-runs the one-shot `liquibase` service, and only then recreates
the backend. The backend runs `SPRING_JPA_HIBERNATE_DDL_AUTO=validate` with
`SPRING_LIQUIBASE_ENABLED=false`, so if the image moves ahead of the on-VM changesets it crash-loops
on a missing table and every `/api/*` returns 502. Never let the backend image outrun its migrations.

A `flock` at `/var/lock/foremen-deploy.lock` serializes concurrent backend+frontend deploys.

## On-VM runtime (`/opt/foremen`)

Compose file `docker-compose.dev.yml`, 4 services:

- **postgres** (`postgres:16-alpine`, volume `pgdata`)
- **liquibase** (`liquibase:4.31`, one-shot, mounts `./database_files` from the VM disk — *not* from
  the image)
- **backend** (`${IMAGE_BACKEND}` = AR `backend:dev`, profile `docker`, `:8080`, `/dev/tcp` health probe)
- **frontend** (`${IMAGE_FRONTEND}` = AR `frontend:dev`, nginx serving the SPA + proxying `/api` →
  `backend:8080`, exposed only internally)
- **caddy** (`caddy:2-alpine`, ports 80/443, TLS for `dev.foremen.eu`, `reverse_proxy frontend:3000`)

### Secrets & config injection (VM boot)

The VM **startup-script** (metadata key `startup-script`) installs Docker, reads config from
**instance metadata**, pulls secrets from **Secret Manager** via the attached SA, writes a
root-owned `0600` `/opt/foremen/.env`, then `docker compose … up -d`. No secret value is ever stored
in an image or in the repo.

Metadata → config: `project-id`, `region`, `ar-host`, `image-backend`, `image-frontend`, `db-name`,
`db-user`, `admin-email`, and `secret-*` pointers.

Secret Manager secrets (naming convention `foremen-dev-<thing>`), fetched into `.env` at boot:

| Secret | Purpose |
|--------|---------|
| `foremen-dev-db-password` | Postgres password |
| `foremen-dev-jwt-secret` | `FOREMEN_JWT_SECRET` |
| `foremen-dev-admin-password` | seeded ADMIN password |
| `foremen-dev-google-places-key` | Google Places (optional; gates `GOOGLE_PLACES_ENABLED`) |
| `foremen-dev-ocr-gemini-key` | FOR-06-03 OCR Gemini key (optional; gates `FOREMEN_OCR_ENABLED`) |

Keyless Google clients: object storage (bucket `foremen-dev-media-42938`, V4 signed URLs) and Vision
OCR use the VM's attached SA via metadata-server ADC — no key file on the VM.

## Adding a new backend env var / secret (the repeatable procedure)

A new `FOREMEN_*` backend setting reaches the running container only when **all** of these are done
(adding it to `application.yml` alone is not enough — the VM `.env` is authoritative at runtime):

1. **Secret** (if sensitive): create `foremen-dev-<thing>` in Secret Manager and grant the VM SA
   `roles/secretmanager.secretAccessor` on it.
2. **Metadata pointer** (if read from a secret): add a `secret-<thing>` instance-metadata key.
3. **startup-script**: read the metadata/secret and add the var to the generated `.env` heredoc.
4. **`docker-compose.dev.yml`**: pass the var into the `backend` service `environment:`.
5. Re-run the startup-script (or re-provision) so `.env` is regenerated, then redeploy.

The startup-script and compose file live **on the VM** (and in Terraform), not in these repos, so
steps 3–4 are infra changes, not app-repo commits.

> **GOTCHA — `secretAccessor` does NOT grant `gcloud secrets describe` (learned 2026-10-23).**
> `roles/secretmanager.secretAccessor` grants only `secretmanager.versions.access` — **not**
> `secretmanager.secrets.get`, which `gcloud secrets describe` requires. The startup-script's older
> blocks guard a fetch with `... && gcloud secrets describe "$SECRET" >/dev/null 2>&1`; under a
> VM SA that holds only `secretAccessor`, that `describe` **fails**, the `if` is skipped, and the var
> is written **blank** — even though `gcloud secrets versions access` works fine. A blank
> `FOREMEN_BOT_IDENTITY_SECRET` makes the backend fail fast at startup → crash-loop → every
> `/api/*` returns 502. **Fix:** in the startup-script, fetch directly
> (`VAL="$(gcloud secrets versions access latest --secret="$SECRET" || true)"`) and guard only on the
> metadata pointer being non-empty — never gate on `describe`. (The older Google-Places/OCR blocks
> only work because their secret's SA happens to have broader `get` access.)

## FOR-08 bot — deployment wiring (resolved 2026-10-23)

### Backend (DONE)

- Secrets: `foremen-dev-bot-identity-secret` (HS256, generated ≥32 bytes) and
  `foremen-dev-bot-site-key` (public `pk_test_`), VM SA granted `secretAccessor`.
- VM metadata pointers `secret-bot-identity` / `secret-bot-site-key`; startup-script fetches them
  with `versions access` (NOT `describe` — see the gotcha above) and writes `FOREMEN_BOT_IDENTITY_SECRET`
  / `FOREMEN_BOT_SITE_KEY` into `.env`; `docker-compose.dev.yml` backend `environment:` passes both.
- Verified: backend healthy, `POST /api/bot/identity-token` returns 401 unauth (live) on
  `https://dev.foremen.eu`.

### Frontend (site key must be baked at BUILD time)

Vite inlines `VITE_`-prefixed env **at build time**, so the loaders-bot site key cannot be injected
at container runtime on the VM — it must be a Docker **build-arg** when the frontend image is built:

- `foremen-frontend/Dockerfile`: `ARG VITE_LOADERS_BOT_SITE_KEY` + `ENV ...` before `npm run build`
  (empty default → widget simply not embedded, build still succeeds).
- `foremen-frontend/cloudbuild.frontend.yaml`: `availableSecrets` maps Secret Manager secret
  `foremen-dev-bot-frontend-site-key` → `secretEnv VITE_LOADERS_BOT_SITE_KEY`; the `build` step runs
  `docker build --build-arg VITE_LOADERS_BOT_SITE_KEY=$$VITE_LOADERS_BOT_SITE_KEY ...`.
- The **Cloud Build service account** needs `roles/secretmanager.secretAccessor` on
  `foremen-dev-bot-frontend-site-key` (granted to both `<num>@cloudbuild.gserviceaccount.com` and
  `<num>-compute@developer.gserviceaccount.com`; project number `820040091656`).
- Takes effect on the next push to `main` in `ctoloaders/foremen-frontend` (rebuilds `frontend:dev`
  with the key baked in, then redeploys the frontend container on the VM).

> Secret-name map (all in project `starry-tracker-505110-s3`): backend site key =
> `foremen-dev-bot-site-key`; frontend build site key = `foremen-dev-bot-frontend-site-key`;
> identity secret = `foremen-dev-bot-identity-secret`. (`foremen-bot-site-key` /
> `foremen-bot-frontend-site-key` without the `-dev-` are earlier generic copies.)

## Handy verified commands

```bash
PROJECT=starry-tracker-505110-s3
# VM status / metadata (describe by name — list is permission-gated)
gcloud compute instances describe foremen-dev --zone=europe-central2-a --project=$PROJECT \
  --format='value(status,serviceAccounts[0].email)'
# SSH to the VM (IAP; passwordless sudo via OS Login)
gcloud compute ssh foremen-dev --zone=europe-central2-a --project=$PROJECT --tunnel-through-iap
# What's running
#   sudo docker ps
#   sudo cat /opt/foremen/.env        # root-owned 0600
#   sudo docker compose -f /opt/foremen/docker-compose.dev.yml --env-file /opt/foremen/.env ps
# Manual redeploy of one service (CI uses the same script)
#   sudo /opt/foremen/deploy-service.sh frontend
#   sudo /opt/foremen/deploy-service.sh backend /path/to/staged/database_files
```
