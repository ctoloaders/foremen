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
SECRET_OCR_GEMINI_KEY="$(md secret-ocr-gemini-key || true)"
SECRET_BOT_IDENTITY="$(md secret-bot-identity || true)"
SECRET_BOT_SITE_KEY="$(md secret-bot-site-key || true)"
SECRET_GOOGLE_OAUTH_CLIENT="$(md secret-google-oauth-client || true)"
# FOR-07-04 bank ledger: NAMES of the Secret Manager secrets (values never here).
SECRET_BANK_SECRETS_KEY="$(md secret-bank-secrets-key || true)"
SECRET_SALTEDGE_APP_ID="$(md secret-saltedge-app-id || true)"
SECRET_SALTEDGE_SECRET="$(md secret-saltedge-secret || true)"
SECRET_SALTEDGE_LEAD_EMAIL="$(md secret-saltedge-lead-email || true)"
SECRET_SALTEDGE_MODE="$(md secret-saltedge-mode || true)"
# FOR-07-04a Fintable: NAMES of the Secret Manager secrets (values never here).
SECRET_FINTABLE_API_TOKEN="$(md secret-fintable-api-token || true)"
SECRET_FINTABLE_EXPIRES_ON="$(md secret-fintable-expires-on || true)"

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
# Fetch directly with `versions access`; do NOT gate on `gcloud secrets describe`
# (needs secrets.get, which secretAccessor lacks). The metadata pointer is the guard.
if [ -n "${SECRET_GOOGLE_PLACES_KEY}" ]; then
  GOOGLE_PLACES_API_KEY="$(sm "${SECRET_GOOGLE_PLACES_KEY}" 2>/dev/null || true)"
  [ -n "${GOOGLE_PLACES_API_KEY}" ] && GOOGLE_PLACES_ENABLED="true"
fi
# Optional: backend OCR Gemini Developer-API key (FOR-06-03). When present, OCR is
# enabled and uploaded procurement documents are parsed (corner-detect + per-line).
# The Vision client uses the VM's attached-SA ADC (no key file); only the Gemini
# Developer-API key is a secret, so only it comes from Secret Manager.
FOREMEN_OCR_ENABLED="false"
FOREMEN_OCR_LLM_API_KEY=""
FOREMEN_OCR_LLM_MODEL="gemini-3.8-flash"
# Fetch directly with `versions access` (NOT `secrets describe`, which the VM SA's
# secretAccessor role does not allow); the non-empty metadata pointer is the guard.
if [ -n "${SECRET_OCR_GEMINI_KEY}" ]; then
  FOREMEN_OCR_LLM_API_KEY="$(sm "${SECRET_OCR_GEMINI_KEY}" 2>/dev/null || true)"
  [ -n "${FOREMEN_OCR_LLM_API_KEY}" ] && FOREMEN_OCR_ENABLED="true"
fi
# FOR-08 loaders-bot (reconciled from the live startup-script metadata). The
# identity secret is required (backend fails fast on blank); the site key is
# public (pk_test_/pk_live_). Both pulled from Secret Manager.
# Fetch directly with `versions access` — do NOT gate on `gcloud secrets describe`,
# which needs secretmanager.secrets.get (NOT included in roles/secretmanager.secretAccessor
# that the VM SA holds for these secrets). The metadata-pointer non-empty check is the guard.
FOREMEN_BOT_IDENTITY_SECRET=""
FOREMEN_BOT_SITE_KEY=""
if [ -n "${SECRET_BOT_IDENTITY}" ]; then
  FOREMEN_BOT_IDENTITY_SECRET="$(sm "${SECRET_BOT_IDENTITY}" || true)"
fi
if [ -n "${SECRET_BOT_SITE_KEY}" ]; then
  FOREMEN_BOT_SITE_KEY="$(sm "${SECRET_BOT_SITE_KEY}" || true)"
fi

# FOR-03-10 Google OAuth client id (public; Sign in with Google). Optional:
# blank => Google login is rejected by the backend and hidden in the UI.
FOREMEN_GOOGLE_CLIENT_ID=""
if [ -n "${SECRET_GOOGLE_OAUTH_CLIENT}" ]; then
  FOREMEN_GOOGLE_CLIENT_ID="$(sm "${SECRET_GOOGLE_OAUTH_CLIENT}" || true)"
fi
# FOR-07-04 bank ledger. All optional: with nothing provisioned the backend runs with no
# bank provider (SALTEDGE_ENABLED=false) and no STUB. Same direct-`versions access`
# pattern as the bot secrets (the metadata-pointer non-empty check is the guard).
# Salt Edge is turned on only with metadata saltedge-enabled=true AND non-empty app id + secret.
FOREMEN_BANK_SECRETS_KEY=""
SALTEDGE_ENABLED="false"
SALTEDGE_MODE="CLIENT"
SALTEDGE_APP_ID=""
SALTEDGE_SECRET=""
SALTEDGE_LEAD_EMAIL=""
[ -n "${SECRET_BANK_SECRETS_KEY}" ] && FOREMEN_BANK_SECRETS_KEY="$(sm "${SECRET_BANK_SECRETS_KEY}" 2>/dev/null || true)"
[ -n "${SECRET_SALTEDGE_APP_ID}" ] && SALTEDGE_APP_ID="$(sm "${SECRET_SALTEDGE_APP_ID}" 2>/dev/null || true)"
[ -n "${SECRET_SALTEDGE_SECRET}" ] && SALTEDGE_SECRET="$(sm "${SECRET_SALTEDGE_SECRET}" 2>/dev/null || true)"
[ -n "${SECRET_SALTEDGE_LEAD_EMAIL}" ] && SALTEDGE_LEAD_EMAIL="$(sm "${SECRET_SALTEDGE_LEAD_EMAIL}" 2>/dev/null || true)"
if [ -n "${SECRET_SALTEDGE_MODE}" ]; then
  _mode="$(sm "${SECRET_SALTEDGE_MODE}" 2>/dev/null || true)"
  case "${_mode}" in CLIENT|PARTNER) SALTEDGE_MODE="${_mode}" ;; esac
fi
# FOR-07-04a R1.1: Salt Edge is OFF by default; having its secrets provisioned no longer turns it on.
# Re-enable explicitly with the instance metadata key `saltedge-enabled=true` (secrets still required).
SALTEDGE_OPT_IN="$(md saltedge-enabled || true)"
[ "${SALTEDGE_OPT_IN}" = "true" ] && [ -n "${SALTEDGE_APP_ID}" ] && [ -n "${SALTEDGE_SECRET}" ] && SALTEDGE_ENABLED="true"
# FOR-07-04a Fintable bank feed. Turned on only when the token secret resolves to a non-empty value.
# FINTABLE_TOKEN_EXPIRES_ON (YYYY-MM-DD) drives the in-app expiry warnings; blank => no warnings.
FINTABLE_ENABLED="false"
FINTABLE_API_TOKEN=""
FINTABLE_TOKEN_EXPIRES_ON=""
[ -n "${SECRET_FINTABLE_API_TOKEN}" ] && FINTABLE_API_TOKEN="$(sm "${SECRET_FINTABLE_API_TOKEN}" 2>/dev/null || true)"
[ -n "${SECRET_FINTABLE_EXPIRES_ON}" ] && FINTABLE_TOKEN_EXPIRES_ON="$(sm "${SECRET_FINTABLE_EXPIRES_ON}" 2>/dev/null || true)"
[ -n "${FINTABLE_API_TOKEN}" ] && FINTABLE_ENABLED="true"

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
FOREMEN_OCR_ENABLED=${FOREMEN_OCR_ENABLED}
FOREMEN_OCR_LLM_API_KEY=${FOREMEN_OCR_LLM_API_KEY}
FOREMEN_OCR_LLM_MODEL=${FOREMEN_OCR_LLM_MODEL}
FOREMEN_BOT_IDENTITY_SECRET=${FOREMEN_BOT_IDENTITY_SECRET}
FOREMEN_BOT_SITE_KEY=${FOREMEN_BOT_SITE_KEY}
FOREMEN_GOOGLE_CLIENT_ID=${FOREMEN_GOOGLE_CLIENT_ID}
FOREMEN_BANK_SECRETS_KEY=${FOREMEN_BANK_SECRETS_KEY}
SALTEDGE_ENABLED=${SALTEDGE_ENABLED}
SALTEDGE_MODE=${SALTEDGE_MODE}
SALTEDGE_APP_ID=${SALTEDGE_APP_ID}
SALTEDGE_SECRET=${SALTEDGE_SECRET}
SALTEDGE_LEAD_EMAIL=${SALTEDGE_LEAD_EMAIL}
FINTABLE_ENABLED=${FINTABLE_ENABLED}
FINTABLE_API_TOKEN=${FINTABLE_API_TOKEN}
FINTABLE_TOKEN_EXPIRES_ON=${FINTABLE_TOKEN_EXPIRES_ON}
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
