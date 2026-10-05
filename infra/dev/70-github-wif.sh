#!/usr/bin/env bash
# Set up keyless CI auth: GitHub Actions -> Google Cloud via Workload Identity
# Federation (WIF). No service-account JSON keys are ever created or stored.
#
# What it creates:
#   - A CI service account the workflows impersonate.
#   - A Workload Identity Pool + a GitHub OIDC provider.
#   - IAM bindings allowing BOTH repos' `main` branch to impersonate the CI SA.
#   - Roles on the CI SA so it can: push to Artifact Registry, SSH to the VM
#     (via OS Login) and start/stop the instance if ever needed, read logs.
#
# After running, it prints the exact values to put into each repo's GitHub
# "Actions" settings as repository VARIABLES (not secrets — none are sensitive):
#   GCP_WIF_PROVIDER, GCP_CI_SA, GCP_PROJECT_ID, GCP_REGION, GCP_ZONE,
#   AR_IMAGE_BACKEND / AR_IMAGE_FRONTEND, VM_NAME
#
# Safe to re-run.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null

PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
POOL="github-pool"
PROVIDER="github-provider"
CI_SA_NAME="foremen-ci"
CI_SA_EMAIL="${CI_SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

# Both repositories allowed to use this federation (owner/repo).
REPO_BACKEND="ctoloaders/foremen"
REPO_FRONTEND="ctoloaders/foremen-frontend"
# GitHub's OIDC issuer owner (the attribute `repository_owner`) used to harden the provider.
GH_OWNER="ctoloaders"

echo ">> Project ${PROJECT_ID} (number ${PROJECT_NUMBER})"

# ---------------------------------------------------------------- CI service account
if gcloud iam service-accounts describe "${CI_SA_EMAIL}" >/dev/null 2>&1; then
  echo ">> CI SA exists: ${CI_SA_EMAIL}"
else
  echo ">> Creating CI SA: ${CI_SA_EMAIL}"
  gcloud iam service-accounts create "${CI_SA_NAME}" --display-name="Foremen GitHub Actions CI"
fi

# Wait until the SA is visible to IAM (creation is eventually consistent).
echo ">> Waiting for CI SA to be visible to IAM..."
for i in $(seq 1 20); do
  if gcloud iam service-accounts describe "${CI_SA_EMAIL}" >/dev/null 2>&1; then break; fi
  sleep 3
done

# Small helper: retry a command a few times (handles IAM propagation races).
retry() {
  local n=0 max=8
  until "$@"; do
    n=$((n+1))
    [ "$n" -ge "$max" ] && { echo "!! failed after ${max} attempts: $*"; return 1; }
    echo "   ...retry ${n}/${max} in 5s"
    sleep 5
  done
}

echo ">> Granting roles to CI SA"
for ROLE in \
  roles/artifactregistry.writer \
  roles/compute.osAdminLogin \
  roles/compute.instanceAdmin.v1 \
  roles/iap.tunnelResourceAccessor \
  roles/logging.viewer ; do
  retry gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${CI_SA_EMAIL}" --role="${ROLE}" --condition=None >/dev/null
done

# ---------------------------------------------------------------- Workload Identity Pool
if gcloud iam workload-identity-pools describe "${POOL}" --location=global >/dev/null 2>&1; then
  echo ">> WIF pool exists: ${POOL}"
else
  echo ">> Creating WIF pool: ${POOL}"
  gcloud iam workload-identity-pools create "${POOL}" \
    --location=global --display-name="GitHub Actions pool"
fi

# ---------------------------------------------------------------- OIDC provider (GitHub)
if gcloud iam workload-identity-pools providers describe "${PROVIDER}" \
     --location=global --workload-identity-pool="${POOL}" >/dev/null 2>&1; then
  echo ">> WIF provider exists: ${PROVIDER}"
else
  echo ">> Creating WIF OIDC provider: ${PROVIDER}"
  gcloud iam workload-identity-pools providers create-oidc "${PROVIDER}" \
    --location=global \
    --workload-identity-pool="${POOL}" \
    --display-name="GitHub OIDC" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner,attribute.ref=assertion.ref" \
    --attribute-condition="assertion.repository_owner=='${GH_OWNER}'"
fi

POOL_FULL="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}"
PROVIDER_FULL="${POOL_FULL}/providers/${PROVIDER}"

# ---------------------------------------------------------------- allow BOTH repos (main only) to impersonate CI SA
echo ">> Binding repos to CI SA (main branch only)"
for REPO in "${REPO_BACKEND}" "${REPO_FRONTEND}"; do
  retry gcloud iam service-accounts add-iam-policy-binding "${CI_SA_EMAIL}" \
    --role="roles/iam.workloadIdentityUser" \
    --member="principalSet://iam.googleapis.com/${POOL_FULL}/attribute.repository/${REPO}" >/dev/null
  echo "   bound: ${REPO}"
done

# ---------------------------------------------------------------- allow CI SA to actAs the VM SA (needed for SSH/OS Login)
echo ">> Allowing CI SA to use the VM service account (${VM_SA_EMAIL})"
# Note: VM SA (foremen-dev-vm) is created by 50-vm.sh; this binding only succeeds
# after that. If the VM SA doesn't exist yet, we skip and print a reminder.
if gcloud iam service-accounts describe "${VM_SA_EMAIL}" >/dev/null 2>&1; then
  retry gcloud iam service-accounts add-iam-policy-binding "${VM_SA_EMAIL}" \
    --role="roles/iam.serviceAccountUser" \
    --member="serviceAccount:${CI_SA_EMAIL}" >/dev/null
else
  echo "   !! VM SA ${VM_SA_EMAIL} not found yet (run 50-vm.sh first); re-run 70 afterwards to add this binding."
fi

cat <<EOF

==============================================================================
 WIF ready. Add these as GitHub Actions **repository variables** in BOTH repos
 (Settings -> Secrets and variables -> Actions -> Variables):

   GCP_PROJECT_ID      = ${PROJECT_ID}
   GCP_PROJECT_NUMBER  = ${PROJECT_NUMBER}
   GCP_REGION          = ${REGION}
   GCP_ZONE            = ${ZONE}
   GCP_WIF_PROVIDER    = ${PROVIDER_FULL}
   GCP_CI_SA           = ${CI_SA_EMAIL}
   AR_HOST             = ${AR_HOST}
   VM_NAME             = ${VM_NAME}

 Backend repo only:   AR_IMAGE = ${IMAGE_BACKEND%:*}      (workflow appends :TAG)
 Frontend repo only:  AR_IMAGE = ${IMAGE_FRONTEND%:*}

 No secrets are required (keyless). SSH to the VM uses OS Login via the CI SA.
==============================================================================
EOF
