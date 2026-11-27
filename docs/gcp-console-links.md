# Foremen: Google Cloud Console quick links

Project ID: `starry-tracker-505110-s3`
Region / zone: `europe-central2` / `europe-central2-a`
Environment: DEV (single VM running the docker-compose stack, Postgres in a container on the VM)

Source of names: `infra/dev/config.env`, `infra/dev/*.sh`, `cloudbuild.backend.yaml`,
`foremen-backend/src/main/resources/application.yml`, `docs/dev-checkup/REPORT.md`.

Legend: **[config]** name is defined in the repo. **[verify]** the repo does not prove the resource
exists today (or its exact name); check it in the console. Links need an account with access to the project
(`cto@loaders.dev`).

The `gcloud` session on this machine had expired credentials when this was written, so live resources
were NOT listed. Run `gcloud auth login`, then use the "CLI check" lines to confirm.

---

## 1. Compute (the running app)

| What | Link | Notes |
|---|---|---|
| VM instances list | https://console.cloud.google.com/compute/instances?project=starry-tracker-505110-s3 | |
| VM `foremen-dev` | https://console.cloud.google.com/compute/instancesDetail/zones/europe-central2-a/instances/foremen-dev?project=starry-tracker-505110-s3 | [config] e2-small, debian-12, 20 GB pd-balanced. Runs frontend (:80), backend, postgres, liquibase |
| VM serial console / logs | https://console.cloud.google.com/compute/instancesDetail/zones/europe-central2-a/instances/foremen-dev?project=starry-tracker-505110-s3&tab=monitoring | CPU / network graphs |
| Static external IP `foremen-dev-ip` | https://console.cloud.google.com/networking/addresses/list?project=starry-tracker-505110-s3 | [config] last seen `34.116.142.204` (REPORT.md) |
| Firewall rules (`allow-foremen-dev`, `allow-foremen-dev-iap-ssh`) | https://console.cloud.google.com/net-security/firewall-manager/firewall-policies/list?project=starry-tracker-505110-s3 | [config] ports 80, 443; IAP SSH |
| Instance schedule `foremen-dev-sched` | https://console.cloud.google.com/compute/instances/resourcePolicies?project=starry-tracker-505110-s3 | [config] optional, Mon-Fri 08:00-20:00; [verify] whether it was applied |
| Disks | https://console.cloud.google.com/compute/disks?project=starry-tracker-505110-s3 | |
| IAP tunnels (SSH without public port) | https://console.cloud.google.com/security/iap?project=starry-tracker-505110-s3 | Cloud Build deploys over IAP SSH |

CLI check: `gcloud compute instances list --project starry-tracker-505110-s3`

Public app URL: http://34.116.142.204/ and https://dev.foremen.eu (DNS is outside GCP unless Cloud DNS is used, see section 10).

## 2. Databases

| What | Link | Notes |
|---|---|---|
| Postgres (default) | Container `postgres` on the VM | Not visible in the console. Connect: IAP SSH to `foremen-dev`, then `docker compose -f docker-compose.dev.yml ps` in `/opt/foremen` |
| Cloud SQL instances | https://console.cloud.google.com/sql/instances?project=starry-tracker-505110-s3 | Optional only: `15-cloud-sql.optional.sh` creates `foremen-dev-pg` (10 GB HDD). [verify] it exists; expected NOT in use |
| Cloud SQL `foremen-dev-pg` | https://console.cloud.google.com/sql/instances/foremen-dev-pg/overview?project=starry-tracker-505110-s3 | Only if created |

## 3. Containers and CI/CD

| What | Link | Notes |
|---|---|---|
| Artifact Registry repo `foremen` | https://console.cloud.google.com/artifacts/docker/starry-tracker-505110-s3/europe-central2/foremen?project=starry-tracker-505110-s3 | [config] images `backend:dev`, `backend:<sha>`, `frontend:dev` |
| Cloud Build history | https://console.cloud.google.com/cloud-build/builds?project=starry-tracker-505110-s3 | `cloudbuild.backend.yaml` (E2_HIGHCPU_8, 30 min timeout) |
| Cloud Build triggers | https://console.cloud.google.com/cloud-build/triggers?project=starry-tracker-505110-s3 | Push to `main` in `ctoloaders/foremen` (Terraform-managed per the file header). [verify] region of the trigger |
| Cloud Build logs (Logging) | https://console.cloud.google.com/logs/query;query=resource.type%3D%22build%22?project=starry-tracker-505110-s3 | |
| GitHub repos | https://github.com/ctoloaders/foremen , https://github.com/ctoloaders/foremen-frontend | Backend and frontend repos |

## 4. Secrets

All in Secret Manager: https://console.cloud.google.com/security/secret-manager?project=starry-tracker-505110-s3

| Secret | Link | Used for |
|---|---|---|
| `foremen-dev-db-password` | https://console.cloud.google.com/security/secret-manager/secret/foremen-dev-db-password/versions?project=starry-tracker-505110-s3 | Postgres password (rotate with `22-rotate-db-password.sh`) |
| `foremen-dev-jwt-secret` | https://console.cloud.google.com/security/secret-manager/secret/foremen-dev-jwt-secret/versions?project=starry-tracker-505110-s3 | JWT signing |
| `foremen-dev-admin-password` | https://console.cloud.google.com/security/secret-manager/secret/foremen-dev-admin-password/versions?project=starry-tracker-505110-s3 | First ADMIN (`cto+dev@loaders.dev`) |
| `foremen-dev-mail-username` / `foremen-dev-mail-password` | https://console.cloud.google.com/security/secret-manager/secret/foremen-dev-mail-password/versions?project=starry-tracker-505110-s3 | SMTP, optional |
| `foremen-dev-google-places-key` | https://console.cloud.google.com/security/secret-manager/secret/foremen-dev-google-places-key/versions?project=starry-tracker-505110-s3 | Places API key, optional |
| `foremen-dev-google-oauth-client` | https://console.cloud.google.com/security/secret-manager/secret/foremen-dev-google-oauth-client/versions?project=starry-tracker-505110-s3 | Google sign-in, optional |
| `foremen-dev-ocr-gemini-key` | https://console.cloud.google.com/security/secret-manager/secret/foremen-dev-ocr-gemini-key/versions?project=starry-tracker-505110-s3 | Gemini Developer API key for OCR |

Optional secrets exist only if a value was loaded by `21-load-secrets.sh` [verify].

## 5. AI

| What | Link | Notes |
|---|---|---|
| Vertex AI dashboard | https://console.cloud.google.com/vertex-ai?project=starry-tracker-505110-s3 | Backend can use the Vertex backend (project + location + ADC) for Gemini |
| Vertex model garden (Gemini) | https://console.cloud.google.com/vertex-ai/model-garden?project=starry-tracker-505110-s3 | Default model in `application.yml`: `gemini-3.8-flash` (OCR, rooms import, dashboard AI, translation, bank AI) |
| Vertex AI quotas | https://console.cloud.google.com/iam-admin/quotas?project=starry-tracker-505110-s3&service=aiplatform.googleapis.com | Check on 429 errors |
| Gemini Developer API keys (AI Studio) | https://aistudio.google.com/apikey | The OCR key in Secret Manager comes from here (separate from Vertex billing view) |
| AI Studio usage and billing | https://aistudio.google.com/usage | Per-key usage |
| Cloud Vision API | https://console.cloud.google.com/apis/api/vision.googleapis.com/metrics?project=starry-tracker-505110-s3 | Backend depends on `google-cloud-vision`; [verify] API enabled |
| Generative Language API metrics | https://console.cloud.google.com/apis/api/generativelanguage.googleapis.com/metrics?project=starry-tracker-505110-s3 | Gemini Developer API traffic, if enabled in this project |
| loaders-bot (AI assistant widget) | https://cdn.loaders.dev/widget/v1/loader.js | Not GCP console; agent config lives in the loaders-bot admin panel (see `.kiro/specs/FOR-08-ai-estimate`) |

## 6. Storage

| What | Link | Notes |
|---|---|---|
| Cloud Storage buckets | https://console.cloud.google.com/storage/browser?project=starry-tracker-505110-s3 | Image / document storage (`FOREMEN_IMAGE_STORAGE_BUCKET`, signed URLs). Bucket name is an env var and is NOT in the repo: [verify] and add it here |
| Bucket `<IMAGE_STORAGE_BUCKET>` | https://console.cloud.google.com/storage/browser/<IMAGE_STORAGE_BUCKET>?project=starry-tracker-505110-s3 | Replace the placeholder with the real name |

Find the value: IAP SSH to the VM and run `grep IMAGE_STORAGE_BUCKET /opt/foremen/.env`.

## 7. Identity and access

| What | Link | Notes |
|---|---|---|
| IAM members | https://console.cloud.google.com/iam-admin/iam?project=starry-tracker-505110-s3 | |
| Service accounts | https://console.cloud.google.com/iam-admin/serviceaccounts?project=starry-tracker-505110-s3 | |
| VM SA `foremen-dev-vm@starry-tracker-505110-s3.iam.gserviceaccount.com` | https://console.cloud.google.com/iam-admin/serviceaccounts/details/foremen-dev-vm@starry-tracker-505110-s3.iam.gserviceaccount.com?project=starry-tracker-505110-s3 | Reads secrets, pulls images, signs GCS URLs |
| CI SA `foremen-ci@starry-tracker-505110-s3.iam.gserviceaccount.com` | https://console.cloud.google.com/iam-admin/serviceaccounts/details/foremen-ci@starry-tracker-505110-s3.iam.gserviceaccount.com?project=starry-tracker-505110-s3 | GitHub Actions via WIF |
| Workload Identity pool `github-pool` | https://console.cloud.google.com/iam-admin/workload-identity-pools?project=starry-tracker-505110-s3 | Provider `github-provider`; repos `ctoloaders/foremen`, `ctoloaders/foremen-frontend` |
| Enabled APIs | https://console.cloud.google.com/apis/dashboard?project=starry-tracker-505110-s3 | Compute, Artifact Registry, Secret Manager, Cloud Build, IAM, Logging (from `10-enable-apis.sh`) |
| API credentials (keys, OAuth) | https://console.cloud.google.com/apis/credentials?project=starry-tracker-505110-s3 | Places key, OAuth client |
| OAuth consent screen | https://console.cloud.google.com/apis/credentials/consent?project=starry-tracker-505110-s3 | |

## 8. Billing and cost

| What | Link | Notes |
|---|---|---|
| Billing overview for the project | https://console.cloud.google.com/billing/linkedaccount?project=starry-tracker-505110-s3 | Shows the linked billing account |
| Cost breakdown / reports | https://console.cloud.google.com/billing/reports?project=starry-tracker-505110-s3 | Filter by SKU: Compute, Vertex AI, Cloud Storage, Cloud Build |
| Budgets and alerts | https://console.cloud.google.com/billing/budgets?project=starry-tracker-505110-s3 | [verify] a budget exists; recommended |
| Cost table | https://console.cloud.google.com/billing/costtable?project=starry-tracker-505110-s3 | |
| Billing export (BigQuery) | https://console.cloud.google.com/billing/export?project=starry-tracker-505110-s3 | [verify] |
| Committed use / recommendations | https://console.cloud.google.com/home/recommendations?project=starry-tracker-505110-s3 | |

Billing account ID is not in the repo; get it with
`gcloud billing projects describe starry-tracker-505110-s3`.

## 9. Observability

| What | Link | Notes |
|---|---|---|
| Logs Explorer (all) | https://console.cloud.google.com/logs/query?project=starry-tracker-505110-s3 | |
| Logs: VM `foremen-dev` | https://console.cloud.google.com/logs/query;query=resource.type%3D%22gce_instance%22?project=starry-tracker-505110-s3 | Docker container logs reach Cloud Logging only if the Ops Agent is installed on the VM [verify]; otherwise use `docker compose logs` over SSH |
| Monitoring overview | https://console.cloud.google.com/monitoring?project=starry-tracker-505110-s3 | |
| Alerting policies | https://console.cloud.google.com/monitoring/alerting?project=starry-tracker-505110-s3 | |
| Uptime checks | https://console.cloud.google.com/monitoring/uptime?project=starry-tracker-505110-s3 | [verify] none configured by the repo |
| Error Reporting | https://console.cloud.google.com/errors?project=starry-tracker-505110-s3 | |
| Audit logs | https://console.cloud.google.com/logs/query;query=logName%3A%22cloudaudit.googleapis.com%22?project=starry-tracker-505110-s3 | |

## 10. Other

| What | Link | Notes |
|---|---|---|
| Cloud DNS zones | https://console.cloud.google.com/net-services/dns/zones?project=starry-tracker-505110-s3 | `dev.foremen.eu` DNS location [verify]; may be at an external registrar |
| Load balancing | https://console.cloud.google.com/net-services/loadbalancing/list/loadBalancers?project=starry-tracker-505110-s3 | Not used by the repo scripts (VM serves 80/443 directly) |
| Project settings (ID, number) | https://console.cloud.google.com/iam-admin/settings?project=starry-tracker-505110-s3 | Project number is needed for the WIF provider path |
| Quotas | https://console.cloud.google.com/iam-admin/quotas?project=starry-tracker-505110-s3 | |
| Cloud Shell | https://shell.cloud.google.com/?project=starry-tracker-505110-s3 | |

## 11. Telegram receipt bot (separate)

`docs/production-migration-guide.html` describes a Telegram receipt bot using Google Sheets, Drive, Cloud Functions,
Cloud Build, Cloud Run and Artifact Registry, with `GCP_REGION=me-west1` as an example. It may live in a different
project; the repo does not name it [verify]. Once known, add:

- Cloud Functions: https://console.cloud.google.com/functions/list?project=<BOT_PROJECT_ID>
- Cloud Run: https://console.cloud.google.com/run?project=<BOT_PROJECT_ID>

## 12. Handy CLI checks

```bash
gcloud auth login
P=starry-tracker-505110-s3
gcloud compute instances list --project $P
gcloud sql instances list --project $P
gcloud storage buckets list --project $P
gcloud artifacts docker images list europe-central2-docker.pkg.dev/$P/foremen
gcloud builds triggers list --project $P
gcloud secrets list --project $P
gcloud services list --enabled --project $P
gcloud billing projects describe $P
```

## Open items to confirm after re-login

- Real Cloud Storage bucket name.
- Whether Cloud SQL `foremen-dev-pg`, the instance schedule, and the optional secrets exist.
- Billing account ID and whether a budget alert is set.
- Whether Vertex AI and the Vision API are enabled, and whether AI traffic goes through Vertex or the Gemini Developer API key.
- Which project (if any) hosts the Telegram receipt bot.
