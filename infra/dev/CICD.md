# Foremen DEV — CI/CD (Cloud Build) + IaC (Terraform)

CI/CD is **Google Cloud Build**, triggered by push to `main` in each GitHub repo.
Infrastructure is described as code in `infra/dev/terraform/` (Terraform).

```
ctoloaders/foremen            push main -> Cloud Build trigger foremen-backend-deploy  -> build backend  -> push :dev + :sha -> deploy backend  on VM
ctoloaders/foremen-frontend   push main -> Cloud Build trigger foremen-frontend-deploy -> build frontend -> push :dev + :sha -> deploy frontend on VM
```

Each repo has a build config at its root:
- `cloudbuild.backend.yaml`  (in `ctoloaders/foremen`)
- `cloudbuild.frontend.yaml` (in `ctoloaders/foremen-frontend`)

A build: builds the image, pushes `:dev` (moving pointer the VM tracks) and
`:<short-sha>` (immutable, for rollback), then SSHes to the VM via IAP and runs
`/opt/foremen/deploy-service.sh <service>` — which locks (flock), pulls only that
service, runs Liquibase first for backend, recreates only that service, and waits
for a stable healthy state.

## Why Cloud Build (not GitHub Actions)

GitHub-hosted runners were unreliable for these repos (jobs stuck queued). Cloud
Build runs on Google's infrastructure, in the same project as everything else, so
there are no runner/billing surprises and auth is native (no WIF/keys needed for
the build itself — Cloud Build runs as a project service account).

## Infrastructure as Code (Terraform)

Everything in the dev environment is managed in `infra/dev/terraform/`:

| File | Manages |
|------|---------|
| `versions.tf`          | providers; auth via `GOOGLE_OAUTH_ACCESS_TOKEN` (see below) |
| `variables.tf`         | project/region/names, image paths (locals) |
| `apis.tf`              | enabled Google APIs |
| `registry_secrets.tf`  | Artifact Registry repo; Secret Manager **containers** (never values) |
| `iam.tf`               | VM + CI service accounts and their roles |
| `wif.tf`               | Workload Identity Federation (kept from the GH Actions era) |
| `vm.tf`                | static IP, firewalls (web + IAP SSH), the VM |
| `cloudbuild.tf`        | repo links, Cloud Build SA roles, the two triggers |

### Running Terraform

Terraform authenticates with a short-lived token from the gcloud CLI (no
`gcloud auth application-default login` / browser needed):

```bash
cd infra/dev/terraform
export GOOGLE_OAUTH_ACCESS_TOKEN="$(gcloud auth print-access-token --account=info@foremen.eu)"
terraform plan      # should say: No changes. Your infrastructure matches the configuration.
terraform apply
```

State is local (`terraform.tfstate`, git-ignored). For a team, switch to a GCS
backend (see the commented block in `versions.tf`). The existing cloud resources
were imported into state with `import-existing.sh`.

## One-time manual step (already done): connect GitHub to Cloud Build

The Cloud Build <-> GitHub OAuth handshake cannot be automated. It was done once:
a 2nd-gen host connection named **`github-foremen`** in region **europe-central2**,
authorizing the `ctoloaders` org and both repos. Terraform then manages the repo
links and triggers on top of that connection. If the connection is ever recreated,
update `var.cb_connection_name` if the name changes.

Console: https://console.cloud.google.com/cloud-build/repositories/2nd-gen?project=starry-tracker-505110-s3

## 2nd-gen trigger gotcha

Cloud Build 2nd-gen triggers **require an explicit build service account**
(`service_account` in Terraform). Without it the API returns a bare
`INVALID_ARGUMENT`. The triggers use `820040091656@cloudbuild.gserviceaccount.com`,
which is granted: `artifactregistry.writer`, `compute.osAdminLogin`,
`compute.instanceAdmin.v1`, `iap.tunnelResourceAccessor`, `logging.logWriter`, and
`iam.serviceAccountUser` on the VM SA.

## Rollback

Each build is tagged with the commit sha. To roll a service back, retag the old
sha image as `:dev` and re-run the deploy, or trigger a rebuild of the previous
commit. Images live in Artifact Registry
(`europe-central2-docker.pkg.dev/starry-tracker-505110-s3/foremen/{backend,frontend}`).

## Operating the VM

```bash
gcloud compute ssh foremen-dev --zone=europe-central2-a --tunnel-through-iap
sudo tail -f /var/log/foremen-startup.log
cd /opt/foremen && sudo docker compose -f docker-compose.dev.yml ps
```

Public URL: **https://dev.foremen.eu/** (TLS via Caddy + Let's Encrypt; HTTP
redirects to HTTPS). The frontend container is no longer published directly;
Caddy owns ports 80/443 and reverse-proxies to it.

## Notes / caveats

- **HTTPS** is served by a Caddy container (auto Let's Encrypt) for
  `dev.foremen.eu`. The DNS A record (`dev.foremen.eu -> 34.116.142.204`) lives at
  home.pl. Caddy persists its certs in the `caddy_data` volume and auto-renews.
- **Secrets** live only in Secret Manager; Terraform manages the containers, never
  the values. Load/rotate with `infra/dev/21-load-secrets.sh` / `22-rotate-db-password.sh`.
- The older step scripts (`10..70`) remain as an imperative alternative/reference,
  but Terraform is now the source of truth for the infrastructure.
