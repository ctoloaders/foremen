# Foremen DEV — CI/CD (GitHub Actions)

Two repositories, two workflows, one VM. Each repo builds **its own** image on a
native-amd64 GitHub runner, pushes to Artifact Registry, then restarts **only its
own** service on the dev VM. Authentication is keyless via Workload Identity
Federation (WIF). Near-simultaneous backend+frontend deploys are serialized on
the VM by a file lock.

```
ctoloaders/foremen            (.github/workflows/deploy-backend.yml)  -> backend:dev  -> VM: deploy-service.sh backend
ctoloaders/foremen-frontend   (.github/workflows/deploy-frontend.yml) -> frontend:dev -> VM: deploy-service.sh frontend
```

## How a deploy flows

1. Push / merge PR to `main` in a repo.
2. The repo's workflow authenticates to GCP via WIF (OIDC, no keys), builds the
   image, and pushes two tags: `:dev` (moving pointer the VM tracks) and
   `:<git-sha>` (immutable, for rollback).
3. The workflow SSHes to the VM **through IAP** (port 22 is open only to Google's
   IAP range, never the internet) and runs `/opt/foremen/deploy-service.sh <service>`.
4. `deploy-service.sh`:
   - takes a `flock` so a backend and a frontend deploy can't interleave;
   - pulls only that service's image;
   - for backend, runs pending Liquibase migrations first (schema-first);
   - recreates only that service (`up -d --no-deps <service>`);
   - waits until the container is **stably healthy** (3 consecutive healthy
     checks) before reporting success.

## One-time setup

### 1. Run the WIF script (creates keyless auth)

```bash
cd infra/dev
./70-github-wif.sh
```

It prints the values to copy into **both** GitHub repos.

### 2. Add GitHub Actions repository VARIABLES

In each repo: **Settings -> Secrets and variables -> Actions -> Variables tab**.
These are not secrets (nothing sensitive), so use *Variables*, not *Secrets*.

Common to both repos:

| Variable | Value (from 70-github-wif.sh output) |
|----------|--------------------------------------|
| `GCP_PROJECT_ID`   | `starry-tracker-505110-s3` |
| `GCP_ZONE`         | `europe-central2-a` |
| `GCP_WIF_PROVIDER` | `projects/<number>/locations/global/workloadIdentityPools/github-pool/providers/github-provider` |
| `GCP_CI_SA`        | `foremen-ci@starry-tracker-505110-s3.iam.gserviceaccount.com` |
| `AR_HOST`          | `europe-central2-docker.pkg.dev` |
| `VM_NAME`          | `foremen-dev` |

Per repo (the image path differs):

| Repo | Variable | Value |
|------|----------|-------|
| `ctoloaders/foremen` (backend)           | `AR_IMAGE` | `europe-central2-docker.pkg.dev/starry-tracker-505110-s3/foremen/backend` |
| `ctoloaders/foremen-frontend` (frontend) | `AR_IMAGE` | `europe-central2-docker.pkg.dev/starry-tracker-505110-s3/foremen/frontend` |

### 3. Ensure the VM exists with IAP SSH + OS Login

`50-vm.sh` already:
- opens tcp:22 **only** to `35.235.240.0/20` (IAP), and
- sets `enable-oslogin=TRUE` on the VM, and
- stages `deploy-service.sh` to `/opt/foremen/`.

If the VM was created before these were added, re-run `50-vm.sh` (it is
idempotent) or apply them manually.

## Why keyless (WIF) and IAP

- **No long-lived secrets** in GitHub: the runner gets a short-lived OIDC token
  that GCP exchanges for temporary CI-service-account credentials. Nothing to
  rotate or leak.
- **SSH without public exposure**: `--tunnel-through-iap` means the VM's port 22
  is reachable only via Google IAP, authorized by the CI service account's
  `iap.tunnelResourceAccessor` + `compute.osAdminLogin` roles. GitHub runners
  have no fixed IP, so an IP-allowlist on port 22 would not work — IAP solves
  this cleanly.

## Rollback

Every build is also tagged with the commit sha. To roll a service back:

```bash
gcloud compute ssh foremen-dev --zone=europe-central2-a --tunnel-through-iap --command '\
  cd /opt/foremen && \
  docker tag <AR_IMAGE>:<old-sha> <AR_IMAGE>:dev && \
  ./deploy-service.sh <backend|frontend>'
```

(Or re-point `:dev` by re-pushing the old sha image as `:dev` from a machine with
registry access.)

## Notes / caveats

- **No TLS yet.** The app is served over `http://` on the VM's public hostname;
  CI/CD is unaffected by that.
- **Backend CORS** is currently pinned to `http://localhost:3000` in
  `application-docker.yml`. The SPA proxies `/api` same-origin, so this is fine
  for the browser; revisit when adding `dev.foremen.eu`.
- The frontend repo needs the workflow file committed at
  `.github/workflows/deploy-frontend.yml` **inside that repo** (it is tracked
  there, not in the root repo).
- First-ever deploy still requires the VM and stack to exist (run `40`/`50` once,
  or let the first CI run build+push and then `50-vm.sh` bring the stack up).
