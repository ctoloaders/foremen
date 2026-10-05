#!/usr/bin/env bash
# VM-side deploy helper. Lives at /opt/foremen/deploy-service.sh on the VM and is
# invoked by CI (over SSH) as:  deploy-service.sh backend   |   deploy-service.sh frontend
#
# Guarantees:
#   - Only the named service is pulled and recreated (the other keeps running).
#   - A flock serializes near-simultaneous backend+frontend deploys so two
#     `compose up` calls never interleave (the second waits for the first).
#   - For backend, pending Liquibase migrations are applied BEFORE the backend
#     is recreated (schema-first), using the one-shot `liquibase` service.
#   - After recreating, it waits until the container reports a STABLE healthy
#     state (several consecutive healthy checks) before returning success.
set -euo pipefail

SERVICE="${1:-}"
case "${SERVICE}" in
  backend|frontend) ;;
  *) echo "usage: $0 <backend|frontend>"; exit 2 ;;
esac

APP_DIR=/opt/foremen
COMPOSE=(docker compose -f "${APP_DIR}/docker-compose.dev.yml" --env-file "${APP_DIR}/.env")
LOCK=/var/lock/foremen-deploy.lock

# --- serialize all deploys on this VM (wait up to 10 min for the other one) ---
exec 9>"${LOCK}"
echo ">> [$(date -u +%H:%M:%S)] waiting for deploy lock..."
flock -w 600 9 || { echo "!! could not acquire deploy lock within 600s"; exit 1; }
echo ">> lock acquired; deploying ${SERVICE}"

cd "${APP_DIR}"

# --- pull the new image for just this service ---
echo ">> pulling ${SERVICE} image"
"${COMPOSE[@]}" pull "${SERVICE}"

# --- backend: run pending migrations first (schema-first), then recreate ---
if [ "${SERVICE}" = "backend" ]; then
  echo ">> running Liquibase migrations"
  # `run --rm` executes the one-shot liquibase service to completion.
  "${COMPOSE[@]}" run --rm liquibase || { echo "!! liquibase failed"; exit 1; }
fi

echo ">> recreating ${SERVICE}"
"${COMPOSE[@]}" up -d --no-deps "${SERVICE}"

# --- wait for a STABLE healthy state ---
# Require N consecutive 'healthy' reads so a container that flaps during startup
# isn't reported as done prematurely (covers back-to-back deploys).
CID="$("${COMPOSE[@]}" ps -q "${SERVICE}")"
[ -n "${CID}" ] || { echo "!! no container id for ${SERVICE}"; exit 1; }

NEED_STABLE=3           # consecutive healthy checks required
MAX_WAIT=300            # seconds (e2-small backend cold start measured ~3 min)
STABLE=0
elapsed=0
echo ">> waiting for ${SERVICE} to become stably healthy (need ${NEED_STABLE} in a row, up to ${MAX_WAIT}s)"
while [ "${elapsed}" -lt "${MAX_WAIT}" ]; do
  STATUS="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${CID}" 2>/dev/null || echo "unknown")"
  case "${STATUS}" in
    healthy|running)
      STABLE=$((STABLE+1))
      echo "   [${elapsed}s] ${STATUS} (${STABLE}/${NEED_STABLE})"
      [ "${STABLE}" -ge "${NEED_STABLE}" ] && { echo ">> ${SERVICE} is stable (${STATUS})"; break; }
      ;;
    starting|created|unknown)
      STABLE=0
      echo "   [${elapsed}s] ${STATUS}"
      ;;
    unhealthy|exited|dead)
      echo "!! ${SERVICE} entered '${STATUS}'. Recent logs:"
      "${COMPOSE[@]}" logs --tail=50 "${SERVICE}" || true
      exit 1
      ;;
  esac
  sleep 5
  elapsed=$((elapsed+5))
done

if [ "${STABLE}" -lt "${NEED_STABLE}" ]; then
  echo "!! ${SERVICE} did not reach a stable healthy state within ${MAX_WAIT}s"
  "${COMPOSE[@]}" logs --tail=50 "${SERVICE}" || true
  exit 1
fi

# --- tidy up dangling images from the previous version ---
docker image prune -f >/dev/null 2>&1 || true
echo ">> [$(date -u +%H:%M:%S)] deploy of ${SERVICE} complete"
