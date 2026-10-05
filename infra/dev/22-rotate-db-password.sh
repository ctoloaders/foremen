#!/usr/bin/env bash
# Rotate the database password for the on-VM Postgres (the default dev setup).
#
# WHAT ROTATION MEANS FOR THIS STACK
#   The backend reads SPRING_DATASOURCE_PASSWORD as a static env var at container
#   start (written to /opt/foremen/.env at boot from Secret Manager). There is no
#   live/zero-downtime rotation: changing the password requires updating Postgres
#   AND restarting the backend with the new value. This script does both.
#
# STEPS
#   1. Generate a new password (or take $2) and add it as a new Secret Manager version.
#   2. ALTER the Postgres role inside the container to the new password.
#   3. Rewrite /opt/foremen/.env and restart backend (+postgres env) so they match.
#
# This is a brief restart (seconds). Dev data is preserved (same volume).
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null

NEW_PW="${2:-}"
gen() { openssl rand -base64 32 | tr -d '\n' | tr '+/' '-_' | cut -c1-44; }
[[ -z "${NEW_PW}" ]] && NEW_PW="$(gen)"

echo ">> 1/3 adding new Secret Manager version for ${SECRET_DB_PASSWORD}"
printf '%s' "${NEW_PW}" | gcloud secrets versions add "${SECRET_DB_PASSWORD}" --data-file=- >/dev/null

echo ">> 2/3 + 3/3 applying on the VM (ALTER ROLE + restart)"
# Run remotely; the VM SA can read the secret. We pass the new password via stdin
# to avoid it appearing in the process list / shell history on the VM.
gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --command="bash -s" <<'REMOTE'
set -euo pipefail
cd /opt/foremen
NEW_PW="$(gcloud secrets versions access latest --secret=foremen-dev-db-password)"
DB_USER="$(grep '^POSTGRES_USER=' .env | cut -d= -f2-)"
DB_NAME="$(grep '^POSTGRES_DB=' .env | cut -d= -f2-)"

# Update the role password inside the running Postgres container.
docker compose -f docker-compose.dev.yml exec -T postgres \
  psql -U "${DB_USER}" -d "${DB_NAME}" -v pw="${NEW_PW}" \
  -c "ALTER ROLE \"${DB_USER}\" WITH PASSWORD :'pw';"

# Rewrite .env with the new password (portable sed in-place).
tmp="$(mktemp)"
sed "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${NEW_PW}|" .env > "$tmp" && mv "$tmp" .env
chmod 600 .env

# Restart so backend picks up the new SPRING_DATASOURCE_PASSWORD.
docker compose -f docker-compose.dev.yml --env-file .env up -d
echo "rotation applied on VM"
REMOTE

echo ">> Done. DB password rotated and services restarted."
