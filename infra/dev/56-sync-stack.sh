#!/usr/bin/env bash
# Sync the stack definition (docker-compose.dev.yml + deploy-service.sh) from this
# repo to the dev VM and bring up any NEW supporting services (FOR-05-13: redis),
# then redeploy the backend so it picks up the new environment.
#
# CI (cloudbuild.*.yaml) only ships images + Liquibase changesets — it never
# updates the compose file on the VM. Run this whenever infra/dev/docker-compose.dev.yml
# or deploy-service.sh changes.
#
# Usage:
#   ./56-sync-stack.sh --fetch    # download the VM's LIVE compose/startup to ./.vm-live/ for review
#   ./56-sync-stack.sh            # drift-check, back up, upload, start redis, redeploy backend
#
# DRIFT GUARD: the VM's compose file has historically been hand-edited (e.g. the
# FOR-08 bot secrets). Uploading a repo copy that lacks those settings would make
# the backend fail fast at startup (every /api/* -> 502). So before uploading, this
# script compares the backend `environment:` keys of the LIVE file with the repo
# file and ABORTS if the live file has any key the repo file lacks. Reconcile the
# repo file first (use --fetch), then re-run.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null

SSH=(gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --tunnel-through-iap --quiet)
SCP=(gcloud compute scp --zone="${ZONE}" --tunnel-through-iap --quiet)
APP_DIR=/opt/foremen
LIVE_DIR=./.vm-live
mkdir -p "${LIVE_DIR}"

fetch_live() {
  echo ">> fetching live files from ${VM_NAME}"
  "${SSH[@]}" --command="sudo cat ${APP_DIR}/docker-compose.dev.yml" > "${LIVE_DIR}/docker-compose.dev.yml"
  "${SSH[@]}" --command="sudo cat ${APP_DIR}/deploy-service.sh" > "${LIVE_DIR}/deploy-service.sh"
  "${SSH[@]}" --command="sudo curl -s -H 'Metadata-Flavor: Google' 'http://metadata.google.internal/computeMetadata/v1/instance/attributes/startup-script'" \
    > "${LIVE_DIR}/vm-startup.sh"
  echo ">> saved to ${LIVE_DIR}/ — diff against the repo copies:"
  for f in docker-compose.dev.yml deploy-service.sh vm-startup.sh; do
    if diff -q "${LIVE_DIR}/${f}" "./${f}" >/dev/null; then echo "   ${f}: identical"; else echo "   ${f}: DIFFERS (diff -u ${LIVE_DIR}/${f} ./${f})"; fi
  done
}

# Backend environment keys of a compose file (lines like "      KEY: value" under backend.environment).
backend_env_keys() {
  awk '
    /^  backend:/ {inb=1; next}
    inb && /^  [a-z]/ {inb=0}
    inb && /^    environment:/ {inenv=1; next}
    inenv && /^    [a-z]/ {inenv=0}
    inenv && /^      [A-Z0-9_]+:/ {sub(/^ +/, ""); sub(/:.*/, ""); print}
  ' "$1" | sort -u
}

if [ "${1:-}" = "--fetch" ]; then
  fetch_live
  exit 0
fi

fetch_live
MISSING="$(comm -23 <(backend_env_keys "${LIVE_DIR}/docker-compose.dev.yml") <(backend_env_keys ./docker-compose.dev.yml))"
if [ -n "${MISSING}" ]; then
  echo "!! DRIFT: the VM's live compose passes backend env keys that the repo file lacks:"
  echo "${MISSING}" | sed 's/^/     /'
  echo "!! Refusing to overwrite. Add them to infra/dev/docker-compose.dev.yml (see ${LIVE_DIR}/), then re-run."
  exit 1
fi
echo ">> drift check passed (repo compose covers every live backend env key)"

STAMP="$(date -u +%Y%m%d-%H%M%S)"
echo ">> backing up live files on the VM (*.bak.${STAMP})"
"${SSH[@]}" --command="sudo cp -a ${APP_DIR}/docker-compose.dev.yml ${APP_DIR}/docker-compose.dev.yml.bak.${STAMP} && sudo cp -a ${APP_DIR}/deploy-service.sh ${APP_DIR}/deploy-service.sh.bak.${STAMP}"

echo ">> uploading compose + deploy-service.sh"
"${SCP[@]}" ./docker-compose.dev.yml ./deploy-service.sh "${VM_NAME}:/tmp/"
"${SSH[@]}" --command="sudo install -m 0644 /tmp/docker-compose.dev.yml ${APP_DIR}/docker-compose.dev.yml && sudo install -m 0755 /tmp/deploy-service.sh ${APP_DIR}/deploy-service.sh && rm -f /tmp/docker-compose.dev.yml /tmp/deploy-service.sh"

echo ">> validating compose on the VM and starting redis"
"${SSH[@]}" --command="cd ${APP_DIR} && sudo docker compose -f docker-compose.dev.yml --env-file .env config -q && sudo docker compose -f docker-compose.dev.yml --env-file .env up -d --no-deps --wait redis && sudo docker compose -f docker-compose.dev.yml --env-file .env exec -T redis redis-cli ping"

echo ">> backing up the database before migrating (${APP_DIR}/backups/pg-${STAMP}.sql.gz)"
"${SSH[@]}" --command="cd ${APP_DIR} && sudo mkdir -p backups && sudo sh -c 'docker compose -f docker-compose.dev.yml --env-file .env exec -T postgres sh -c \"pg_dump -U \\\$POSTGRES_USER -d \\\$POSTGRES_DB\" | gzip > backups/pg-${STAMP}.sql.gz' && sudo ls -la backups/pg-${STAMP}.sql.gz"

# Stage THIS repo's changesets so the migrations match the backend:dev image
# (schema-first; same contract as cloudbuild.backend.yaml's copy-db-files step).
STAGE="/tmp/foremen-db-sync-manual-${STAMP}"
echo ">> staging Liquibase changesets to ${STAGE}"
"${SSH[@]}" --command="rm -rf '${STAGE}' && mkdir -p '${STAGE}'"
"${SCP[@]}" --recurse ../../foremen-backend/database_files "${VM_NAME}:${STAGE}/"

echo ">> redeploying backend (changesets -> liquibase -> redis -> backend, waits for healthy)"
"${SSH[@]}" --command="${APP_DIR}/deploy-service.sh backend '${STAGE}/database_files'"

echo ">> redeploying frontend (nginx /api/ws proxy + collab UI)"
"${SSH[@]}" --command="${APP_DIR}/deploy-service.sh frontend"

echo ">> done. Rollback:"
echo "   compose: sudo cp ${APP_DIR}/docker-compose.dev.yml.bak.${STAMP} ${APP_DIR}/docker-compose.dev.yml"
echo "   db:      gunzip -c ${APP_DIR}/backups/pg-${STAMP}.sql.gz | (restore into a fresh postgres volume)"
