#!/usr/bin/env bash
# Redeploy the app after you pushed new images (40-build-push.sh).
# Pulls the latest images on the VM and restarts the stack. Re-stages the
# Liquibase changelog first so new migrations are present.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null
REPO_ROOT="$(cd ../.. && pwd)"

echo ">> Re-staging Liquibase changelog"
gcloud compute scp --recurse "${REPO_ROOT}/foremen-backend/database_files" \
  "${VM_NAME}:/opt/foremen/database_files" --zone="${ZONE}"

echo ">> Pull + up on the VM"
gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --command="\
  cd /opt/foremen && \
  docker compose -f docker-compose.dev.yml --env-file .env pull && \
  docker compose -f docker-compose.dev.yml --env-file .env up -d && \
  docker image prune -f"

echo ">> Done."
