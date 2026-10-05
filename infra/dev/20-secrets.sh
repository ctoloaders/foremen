#!/usr/bin/env bash
# Create (or reuse) the DEV secrets in Secret Manager:
#   - DB password, JWT secret, first-admin password
# Values are generated locally and never printed in full. Re-running does NOT
# rotate an existing secret (it only creates one if missing); use --rotate to
# add a new version.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null

ROTATE="${1:-}"

gen() { openssl rand -base64 "${1:-32}" | tr -d '\n' | tr '+/' '-_' | cut -c1-"${2:-44}"; }

ensure_secret() {
  local name="$1" value="$2"
  if gcloud secrets describe "$name" >/dev/null 2>&1; then
    if [[ "$ROTATE" == "--rotate" ]]; then
      printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- >/dev/null
      echo ">> Rotated secret: $name (new version added)"
    else
      echo ">> Secret exists, leaving as-is: $name  (pass --rotate to add a new version)"
    fi
  else
    gcloud secrets create "$name" --replication-policy="automatic" >/dev/null
    printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- >/dev/null
    echo ">> Created secret: $name"
  fi
}

ensure_secret "${SECRET_DB_PASSWORD}"    "$(gen 32 44)"
ensure_secret "${SECRET_JWT}"            "$(gen 32 44)"
ensure_secret "${SECRET_ADMIN_PASSWORD}" "$(gen 18 24)"

echo
echo ">> Secrets ready. To read the admin password later (for first login):"
echo "   gcloud secrets versions access latest --secret=${SECRET_ADMIN_PASSWORD}"
