#!/usr/bin/env bash
# Tear down the DEV environment. DESTRUCTIVE. Prompts before deleting.
# Does NOT delete secrets or the Artifact Registry repo by default (cheap to keep);
# pass --all to remove those too.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null
ALL="${1:-}"

echo "This will delete the following in project ${PROJECT_ID}:"
echo "  - VM:          ${VM_NAME} (${ZONE})   [DELETES THE POSTGRES VOLUME / ALL DEV DATA]"
echo "  - Firewall:    allow-${FIREWALL_TAG}"
echo "  - Static IP:   ${STATIC_IP_NAME} (${REGION})"
if [[ "${ALL}" == "--all" ]]; then
  echo "  - Secrets:     ${SECRET_DB_PASSWORD}, ${SECRET_JWT}, ${SECRET_ADMIN_PASSWORD}"
  echo "  - AR repo:     ${AR_REPO}"
fi
read -r -p "Type 'destroy' to proceed: " CONFIRM
[[ "${CONFIRM}" == "destroy" ]] || { echo "Aborted."; exit 1; }

gcloud compute instances delete "${VM_NAME}" --zone="${ZONE}" --quiet || true
gcloud compute firewall-rules delete "allow-${FIREWALL_TAG}" --quiet || true
gcloud compute addresses delete "${STATIC_IP_NAME}" --region="${REGION}" --quiet || true

if [[ "${ALL}" == "--all" ]]; then
  for S in "${SECRET_DB_PASSWORD}" "${SECRET_JWT}" "${SECRET_ADMIN_PASSWORD}"; do
    gcloud secrets delete "${S}" --quiet || true
  done
  gcloud artifacts repositories delete "${AR_REPO}" --location="${REGION}" --quiet || true
fi
echo ">> Teardown complete."
