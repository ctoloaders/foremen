#!/usr/bin/env bash
# Load secrets from a local filled-in file into Secret Manager.
#
#   ./21-load-secrets.sh secrets.local.env
#
# - Blank REQUIRED values (DB_PASSWORD/JWT_SECRET/ADMIN_PASSWORD) are auto-generated.
# - Blank OPTIONAL values are skipped (secret not created/updated).
# - Each provided value is written as a NEW version of its secret (so this is how
#   you rotate too: edit the file, re-run).
# - The plaintext file is NEVER uploaded; only individual values are piped to
#   `gcloud secrets versions add` over stdin. Nothing is echoed.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null

SRC="${1:-}"
[[ -n "${SRC}" && -f "${SRC}" ]] || { echo "Usage: $0 <path-to-filled-secrets-file>"; exit 1; }

gen() { openssl rand -base64 "${1:-32}" | tr -d '\n' | tr '+/' '-_' | cut -c1-"${2:-44}"; }

# Read the raw value for a KEY from the file (first match wins). Avoids `source`
# so values with special chars are safe and nothing is executed. No associative
# arrays, so this works on the stock macOS bash 3.2.
getval() {  # getval <KEY>
  local want="$1" line key val
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      \#*|'') continue ;;
    esac
    key="${line%%=*}"
    key="$(printf '%s' "$key" | tr -d '[:space:]')"
    [ "$key" = "$want" ] || continue
    val="${line#*=}"
    printf '%s' "$val"
    return 0
  done < "${SRC}"
  printf ''
}

upsert() {  # upsert <secret-name> <value>
  local name="$1" value="$2"
  if ! gcloud secrets describe "$name" >/dev/null 2>&1; then
    gcloud secrets create "$name" --replication-policy="automatic" >/dev/null
    echo ">> created secret: $name"
  fi
  printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- >/dev/null
  echo ">> wrote new version: $name"
}

# --- REQUIRED (auto-generate if blank) ---
DB_PASSWORD="$(getval DB_PASSWORD)";       [ -z "$DB_PASSWORD" ]    && DB_PASSWORD="$(gen 32 44)"    && echo ">> DB_PASSWORD blank -> generated"
JWT_SECRET="$(getval JWT_SECRET)";         [ -z "$JWT_SECRET" ]     && JWT_SECRET="$(gen 32 44)"     && echo ">> JWT_SECRET blank -> generated"
ADMIN_PASSWORD="$(getval ADMIN_PASSWORD)"; [ -z "$ADMIN_PASSWORD" ] && ADMIN_PASSWORD="$(gen 18 24)" && echo ">> ADMIN_PASSWORD blank -> generated"

upsert "${SECRET_DB_PASSWORD}"    "$DB_PASSWORD"
upsert "${SECRET_JWT}"            "$JWT_SECRET"
upsert "${SECRET_ADMIN_PASSWORD}" "$ADMIN_PASSWORD"

# --- OPTIONAL (only if provided) ---
maybe() {  # maybe <secret-name> <value> ; skip if blank
  local name="$1" value="$2"
  [[ -z "$value" ]] && { echo ">> skip (blank): $name"; return; }
  upsert "$name" "$value"
}
maybe "${SECRET_MAIL_USERNAME}"       "$(getval MAIL_USERNAME)"
maybe "${SECRET_MAIL_PASSWORD}"       "$(getval MAIL_PASSWORD)"
maybe "${SECRET_GOOGLE_PLACES_KEY}"   "$(getval GOOGLE_PLACES_API_KEY)"
maybe "${SECRET_GOOGLE_OAUTH_CLIENT}" "$(getval GOOGLE_OAUTH_CLIENT_ID)"

echo
echo ">> Done. Reminder: delete the plaintext file now:"
echo "     rm -P \"${SRC}\"   # macOS   (or: shred -u on Linux)"
