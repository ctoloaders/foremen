#!/usr/bin/env bash
# Import already-existing GCP dev resources into Terraform state so a subsequent
# `terraform apply` reconciles instead of recreating. Idempotent-ish: an already
# imported address is skipped by terraform with a clear message.
set -uo pipefail
cd "$(dirname "$0")"

# Terraform's google provider authenticates via this token (minted from the
# already-working gcloud CLI), so no `gcloud auth application-default login`
# (browser) is required.
export GOOGLE_OAUTH_ACCESS_TOKEN="$(gcloud auth print-access-token --account=info@foremen.eu 2>/dev/null | tail -1)"
[ -n "${GOOGLE_OAUTH_ACCESS_TOKEN}" ] || { echo "!! could not mint access token"; exit 1; }

P=starry-tracker-505110-s3
NUM=820040091656
R=europe-central2
Z=europe-central2-a

imp() { echo ">> import $1"; terraform import -input=false "$1" "$2" 2>&1 | tail -3; echo; }

# APIs (project-service): id form is "<project>/<service>"
for S in compute artifactregistry secretmanager cloudbuild iam iamcredentials logging iap; do
  imp "google_project_service.apis[\"${S}.googleapis.com\"]" "${P}/${S}.googleapis.com"
done

# Artifact Registry
imp "google_artifact_registry_repository.foremen" "projects/${P}/locations/${R}/repositories/foremen"

# Secrets (containers)
for S in foremen-dev-db-password foremen-dev-jwt-secret foremen-dev-admin-password foremen-dev-google-places-key; do
  imp "google_secret_manager_secret.secrets[\"${S}\"]" "projects/${P}/secrets/${S}"
done

# Service accounts
imp "google_service_account.vm" "projects/${P}/serviceAccounts/foremen-dev-vm@${P}.iam.gserviceaccount.com"
imp "google_service_account.ci" "projects/${P}/serviceAccounts/foremen-ci@${P}.iam.gserviceaccount.com"

# VM SA project roles
for ROLE in roles/secretmanager.secretAccessor roles/artifactregistry.reader roles/logging.logWriter; do
  imp "google_project_iam_member.vm_roles[\"${ROLE}\"]" "${P} ${ROLE} serviceAccount:foremen-dev-vm@${P}.iam.gserviceaccount.com"
done

# CI SA project roles
for ROLE in roles/artifactregistry.writer roles/compute.osAdminLogin roles/compute.instanceAdmin.v1 roles/iap.tunnelResourceAccessor roles/logging.viewer; do
  imp "google_project_iam_member.ci_roles[\"${ROLE}\"]" "${P} ${ROLE} serviceAccount:foremen-ci@${P}.iam.gserviceaccount.com"
done

# CI actAs VM SA
imp "google_service_account_iam_member.ci_actas_vm" "projects/${P}/serviceAccounts/foremen-dev-vm@${P}.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:foremen-ci@${P}.iam.gserviceaccount.com"

# WIF pool + provider
imp "google_iam_workload_identity_pool.github" "projects/${P}/locations/global/workloadIdentityPools/github-pool"
imp "google_iam_workload_identity_pool_provider.github" "projects/${P}/locations/global/workloadIdentityPools/github-pool/providers/github-provider"

# WIF repo bindings
imp "google_service_account_iam_member.wif_backend" "projects/${P}/serviceAccounts/foremen-ci@${P}.iam.gserviceaccount.com roles/iam.workloadIdentityUser principalSet://iam.googleapis.com/projects/${NUM}/locations/global/workloadIdentityPools/github-pool/attribute.repository/ctoloaders/foremen"
imp "google_service_account_iam_member.wif_frontend" "projects/${P}/serviceAccounts/foremen-ci@${P}.iam.gserviceaccount.com roles/iam.workloadIdentityUser principalSet://iam.googleapis.com/projects/${NUM}/locations/global/workloadIdentityPools/github-pool/attribute.repository/ctoloaders/foremen-frontend"

# Networking
imp "google_compute_address.vm_ip" "projects/${P}/regions/${R}/addresses/foremen-dev-ip"
imp "google_compute_firewall.web" "projects/${P}/global/firewalls/allow-foremen-dev"
imp "google_compute_firewall.iap_ssh" "projects/${P}/global/firewalls/allow-foremen-dev-iap-ssh"

# VM
imp "google_compute_instance.vm" "projects/${P}/zones/${Z}/instances/foremen-dev"

echo ">> import pass complete"
