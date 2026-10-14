#!/usr/bin/env bash
#
# refresh-adc.sh — ensure a VALID Google Application Default Credentials (ADC)
# file is available to the local Docker backend.
#
# The backend's GCS image storage (FOR-05-08 signed document URLs, image uploads)
# authenticates with ADC. docker-compose.override.yml mounts a STABLE project-local
# copy of the ADC file — ./.secrets/adc.json — into the backend at /gcp/adc.json
# (we mount a single file, not the live ~/.config/gcloud dir, because gcloud rewrites
# that dir and the macOS Docker Desktop bind mount can wedge on it).
#
# ADC refresh tokens expire / require periodic reauth. When that happens the backend
# logs:
#   invalid_grant / "reauth related error (invalid_rapt)"
# This script detects a stale/invalid ADC and runs the one interactive step that
# cannot live inside `docker compose` (it opens a browser): `gcloud auth
# application-default login`. Then it copies the fresh credentials into
# ./.secrets/adc.json so the mounted file is current.
#
# Usage:
#   scripts/refresh-adc.sh          # reauth only if the current ADC is invalid
#   scripts/refresh-adc.sh --force  # always reauth (e.g. switching accounts)
#
# It is also invoked automatically by scripts/dev-up.sh before `docker compose up`.

set -euo pipefail

# Resolve the repo root (this script lives in <root>/scripts).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

HOST_ADC="${HOME}/.config/gcloud/application_default_credentials.json"
SECRETS_DIR="${REPO_ROOT}/.secrets"
DEST_ADC="${SECRETS_DIR}/adc.json"

FORCE=0
if [[ "${1:-}" == "--force" ]]; then
  FORCE=1
fi

if ! command -v gcloud >/dev/null 2>&1; then
  echo "ERROR: gcloud CLI not found on PATH." >&2
  echo "       Install the Google Cloud SDK: https://cloud.google.com/sdk/docs/install" >&2
  exit 1
fi

# True when the current host ADC can mint an access token (i.e. it is valid and
# not in a reauth-required state). Runs non-interactively so a stale token fails
# fast instead of hanging on a prompt.
adc_is_valid() {
  gcloud auth application-default print-access-token >/dev/null 2>&1
}

needs_login=1
if [[ "${FORCE}" -eq 0 ]] && [[ -f "${HOST_ADC}" ]] && adc_is_valid; then
  needs_login=0
fi

if [[ "${needs_login}" -eq 1 ]]; then
  if [[ "${FORCE}" -eq 1 ]]; then
    echo ">> --force: refreshing Application Default Credentials..."
  else
    echo ">> Application Default Credentials are missing or expired (reauth required)."
  fi
  echo ">> Launching: gcloud auth application-default login (a browser window will open)"
  # The ONE interactive step: it opens a browser for consent and writes a fresh
  # ${HOST_ADC}. Cannot run inside `docker compose` (no browser in the container).
  gcloud auth application-default login
else
  echo ">> Application Default Credentials are valid — no reauth needed."
fi

if [[ ! -f "${HOST_ADC}" ]]; then
  echo "ERROR: expected ADC file not found at ${HOST_ADC} after login." >&2
  exit 1
fi

# Copy the fresh credentials into the project-local file the backend mounts.
mkdir -p "${SECRETS_DIR}"
cp "${HOST_ADC}" "${DEST_ADC}"
chmod 600 "${DEST_ADC}"
echo ">> Wrote fresh credentials to ${DEST_ADC#"${REPO_ROOT}/"}"
