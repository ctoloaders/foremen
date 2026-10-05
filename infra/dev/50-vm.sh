#!/usr/bin/env bash
# Create the DEV VM and everything it needs:
#   - a dedicated service account (least privilege: read secrets + pull images + logs)
#   - a reserved static external IP (stable *.bc.googleusercontent.com hostname)
#   - a firewall rule opening the configured ports
#   - the Compute Engine VM, wired to vm-startup.sh with config passed via metadata
#
# The Liquibase changelog and the compose file are staged onto the VM over SCP
# after it boots, then the stack is (re)started.
#
# Safe to re-run: existing resources are reused; the VM is only created if absent.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null
REPO_ROOT="$(cd ../.. && pwd)"

# ---------------------------------------------------------------- service account
if gcloud iam service-accounts describe "${VM_SA_EMAIL}" >/dev/null 2>&1; then
  echo ">> SA exists: ${VM_SA_EMAIL}"
else
  echo ">> Creating SA: ${VM_SA_EMAIL}"
  gcloud iam service-accounts create "${VM_SA_NAME}" --display-name="Foremen dev VM"
fi

echo ">> Granting least-privilege roles to the VM SA"
for ROLE in roles/secretmanager.secretAccessor roles/artifactregistry.reader roles/logging.logWriter; do
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${VM_SA_EMAIL}" --role="${ROLE}" \
    --condition=None >/dev/null
done

# ---------------------------------------------------------------- static IP
if gcloud compute addresses describe "${STATIC_IP_NAME}" --region="${REGION}" >/dev/null 2>&1; then
  echo ">> Static IP exists: ${STATIC_IP_NAME}"
else
  echo ">> Reserving static IP: ${STATIC_IP_NAME}"
  gcloud compute addresses create "${STATIC_IP_NAME}" --region="${REGION}"
fi
STATIC_IP="$(gcloud compute addresses describe "${STATIC_IP_NAME}" --region="${REGION}" --format='value(address)')"
echo ">> Static IP = ${STATIC_IP}"

# ---------------------------------------------------------------- firewall
FW_NAME="allow-${FIREWALL_TAG}"
PORTS_CSV="$(echo "${OPEN_PORTS}" | tr ' ' ',')"
if gcloud compute firewall-rules describe "${FW_NAME}" >/dev/null 2>&1; then
  echo ">> Firewall exists: ${FW_NAME} (updating ports -> ${PORTS_CSV})"
  gcloud compute firewall-rules update "${FW_NAME}" --allow="tcp:${PORTS_CSV}" >/dev/null
else
  echo ">> Creating firewall: ${FW_NAME} (tcp:${PORTS_CSV})"
  gcloud compute firewall-rules create "${FW_NAME}" \
    --allow="tcp:${PORTS_CSV}" \
    --target-tags="${FIREWALL_TAG}" \
    --source-ranges="0.0.0.0/0" \
    --description="Foremen dev inbound"
fi

# SSH via IAP only (for CI and admins) — port 22 open ONLY to Google's IAP range,
# never to the public internet. GitHub runners reach the VM through IAP tunneling.
FW_IAP="allow-${FIREWALL_TAG}-iap-ssh"
if gcloud compute firewall-rules describe "${FW_IAP}" >/dev/null 2>&1; then
  echo ">> IAP SSH firewall exists: ${FW_IAP}"
else
  echo ">> Creating IAP SSH firewall: ${FW_IAP} (tcp:22 from 35.235.240.0/20)"
  gcloud compute firewall-rules create "${FW_IAP}" \
    --allow="tcp:22" \
    --target-tags="${FIREWALL_TAG}" \
    --source-ranges="35.235.240.0/20" \
    --description="Foremen dev SSH via IAP only"
fi

# ---------------------------------------------------------------- the VM
if gcloud compute instances describe "${VM_NAME}" --zone="${ZONE}" >/dev/null 2>&1; then
  echo ">> VM already exists: ${VM_NAME} (leaving it; use 'gcloud compute instances delete' to recreate)"
else
  echo ">> Creating VM: ${VM_NAME}"
  # Build the metadata key=value list as a single comma-separated string.
  META="project-id=${PROJECT_ID}"
  META="${META},region=${REGION}"
  META="${META},ar-host=${AR_HOST}"
  META="${META},image-backend=${IMAGE_BACKEND}"
  META="${META},image-frontend=${IMAGE_FRONTEND}"
  META="${META},db-name=${DB_NAME}"
  META="${META},db-user=${DB_USER}"
  META="${META},admin-email=${ADMIN_EMAIL}"
  META="${META},secret-db-password=${SECRET_DB_PASSWORD}"
  META="${META},secret-jwt=${SECRET_JWT}"
  META="${META},secret-admin-password=${SECRET_ADMIN_PASSWORD}"
  META="${META},secret-google-places-key=${SECRET_GOOGLE_PLACES_KEY}"
  META="${META},enable-oslogin=TRUE"

  gcloud compute instances create "${VM_NAME}" \
    --zone="${ZONE}" \
    --machine-type="${MACHINE_TYPE}" \
    --image-family="${IMAGE_FAMILY}" --image-project="${IMAGE_PROJECT}" \
    --boot-disk-size="${BOOT_DISK_SIZE}" --boot-disk-type="${BOOT_DISK_TYPE}" \
    --address="${STATIC_IP}" \
    --tags="${FIREWALL_TAG}" \
    --service-account="${VM_SA_EMAIL}" \
    --scopes="https://www.googleapis.com/auth/cloud-platform" \
    --metadata-from-file=startup-script=./vm-startup.sh \
    --metadata="${META}"
fi

# ---------------------------------------------------------------- stage files onto VM
echo ">> Waiting for SSH to come up..."
for i in $(seq 1 30); do
  if gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --command="echo ok" >/dev/null 2>&1; then break; fi
  sleep 10
done

echo ">> Staging compose file + Liquibase changelog onto the VM"
gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --command="sudo mkdir -p /opt/foremen && sudo chown \$(whoami) /opt/foremen"
gcloud compute scp ./docker-compose.dev.yml "${VM_NAME}:/opt/foremen/docker-compose.dev.yml" --zone="${ZONE}"
gcloud compute scp ./deploy-service.sh "${VM_NAME}:/opt/foremen/deploy-service.sh" --zone="${ZONE}"
gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --command="chmod +x /opt/foremen/deploy-service.sh"
gcloud compute scp --recurse "${REPO_ROOT}/foremen-backend/database_files" "${VM_NAME}:/opt/foremen/database_files" --zone="${ZONE}"

echo ">> Re-running startup to apply config and bring the stack up"
gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --command="sudo google_metadata_script_runner startup"

echo
echo "=============================================================="
echo " VM ready."
echo " Static IP : ${STATIC_IP}"
echo " Public URL: http://${STATIC_IP//./-}.bc.googleusercontent.com/"
echo "   (or just http://${STATIC_IP}/ )"
echo " Admin login: ${ADMIN_EMAIL}"
echo " Admin pass : gcloud secrets versions access latest --secret=${SECRET_ADMIN_PASSWORD}"
echo "=============================================================="
