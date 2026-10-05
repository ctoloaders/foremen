#!/usr/bin/env bash
# VM startup script (runs as root on every boot via the metadata startup-script).
# Idempotent: installs Docker once, then (re)writes config and brings the stack up.
#
# It reads configuration from instance metadata and secrets from Secret Manager
# using the VM's service account, so no secret value is ever stored in this file
# or in the image.
set -euo pipefail
exec > >(tee -a /var/log/foremen-startup.log) 2>&1
echo "=== foremen startup $(date -u) ==="

# --- metadata helpers ---
md() { curl -s -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/attributes/$1"; }
sm() { gcloud secrets versions access latest --secret="$1"; }

APP_DIR=/opt/foremen
mkdir -p "${APP_DIR}"

# --- install docker + compose plugin (once) ---
if ! command -v docker >/dev/null 2>&1; then
  echo ">> installing docker"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get install -y ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi

# --- read config from metadata ---
PROJECT_ID="$(md project-id)"
REGION="$(md region)"
AR_HOST="$(md ar-host)"
IMAGE_BACKEND="$(md image-backend)"
IMAGE_FRONTEND="$(md image-frontend)"
DB_NAME="$(md db-name)"
DB_USER="$(md db-user)"
ADMIN_EMAIL="$(md admin-email)"
SECRET_DB_PASSWORD="$(md secret-db-password)"
SECRET_JWT="$(md secret-jwt)"
SECRET_ADMIN_PASSWORD="$(md secret-admin-password)"
SECRET_GOOGLE_PLACES_KEY="$(md secret-google-places-key || true)"

# --- docker auth to Artifact Registry (via VM service account) ---
gcloud auth configure-docker "${AR_HOST}" --quiet

# --- pull secrets ---
echo ">> fetching secrets"
DB_PASSWORD="$(sm "${SECRET_DB_PASSWORD}")"
JWT_SECRET="$(sm "${SECRET_JWT}")"
ADMIN_PASSWORD="$(sm "${SECRET_ADMIN_PASSWORD}")"
# Optional: Google Places key (only if the secret exists)
GOOGLE_PLACES_API_KEY=""
GOOGLE_PLACES_ENABLED="false"
if [ -n "${SECRET_GOOGLE_PLACES_KEY}" ] && gcloud secrets describe "${SECRET_GOOGLE_PLACES_KEY}" >/dev/null 2>&1; then
  GOOGLE_PLACES_API_KEY="$(sm "${SECRET_GOOGLE_PLACES_KEY}")"
  [ -n "${GOOGLE_PLACES_API_KEY}" ] && GOOGLE_PLACES_ENABLED="true"
fi

# --- write .env for compose ---
cat > "${APP_DIR}/.env" <<EOF
POSTGRES_DB=${DB_NAME}
POSTGRES_USER=${DB_USER}
POSTGRES_PASSWORD=${DB_PASSWORD}
IMAGE_BACKEND=${IMAGE_BACKEND}
IMAGE_FRONTEND=${IMAGE_FRONTEND}
FOREMEN_JWT_SECRET=${JWT_SECRET}
FOREMEN_ADMIN_EMAIL=${ADMIN_EMAIL}
FOREMEN_ADMIN_PASSWORD=${ADMIN_PASSWORD}
GOOGLE_PLACES_API_KEY=${GOOGLE_PLACES_API_KEY}
GOOGLE_PLACES_ENABLED=${GOOGLE_PLACES_ENABLED}
EOF
chmod 600 "${APP_DIR}/.env"

# --- bring the stack up (compose + changelog are staged into APP_DIR by 50-vm.sh) ---
cd "${APP_DIR}"
# Serialize with CI deploys and avoid double-run races (metadata startup can run
# more than once): take the same lock deploy-service.sh uses.
exec 9>/var/lock/foremen-deploy.lock
flock -w 300 9 || echo ">> warning: proceeding without deploy lock"

echo ">> docker compose pull (ignore missing images; CI publishes them)"
docker compose -f docker-compose.dev.yml --env-file .env pull --ignore-pull-failures || true
echo ">> docker compose up -d (only services whose image is available)"
# --remove-orphans keeps repeat runs clean; pull failures above mean some
# services may not start yet — that's expected until CI has published them.
docker compose -f docker-compose.dev.yml --env-file .env up -d --remove-orphans || true

echo "=== foremen startup done $(date -u) ==="
