#!/usr/bin/env bash
#
# dev-up.sh — bring the local stack up with FRESH Google credentials.
#
# Wraps `docker compose up` so the backend never starts against a stale ADC token
# (the "invalid_grant / invalid_rapt" reauth error). It first ensures a valid
# Application Default Credentials file is written to ./.secrets/adc.json (running
# the interactive `gcloud auth application-default login` only if needed), then
# starts the stack.
#
# Usage:
#   scripts/dev-up.sh                 # refresh ADC if stale, then `docker compose up`
#   scripts/dev-up.sh --force-auth    # always reauth before starting
#   scripts/dev-up.sh -d              # pass through to compose (detached), etc.
#
# Any args other than --force-auth are forwarded verbatim to `docker compose up`.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

FORCE_AUTH=0
COMPOSE_ARGS=()
for arg in "$@"; do
  case "${arg}" in
    --force-auth) FORCE_AUTH=1 ;;
    *) COMPOSE_ARGS+=("${arg}") ;;
  esac
done

echo "== Step 1/2: ensure fresh Google Application Default Credentials =="
if [[ "${FORCE_AUTH}" -eq 1 ]]; then
  "${SCRIPT_DIR}/refresh-adc.sh" --force
else
  "${SCRIPT_DIR}/refresh-adc.sh"
fi

echo
echo "== Step 2/2: docker compose up =="
# --force-recreate on the backend so a refreshed credential file is picked up even
# when the image/config is otherwise unchanged. Extra args (e.g. -d) pass through.
cd "${REPO_ROOT}"
# ${arr[@]+...} guards the expansion so an EMPTY array does not trip `set -u`
# ("unbound variable") on older Bash (macOS ships Bash 3.2).
exec docker compose up --force-recreate ${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"}
