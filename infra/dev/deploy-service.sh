#!/usr/bin/env bash
# VM-side deploy helper. Lives at /opt/foremen/deploy-service.sh on the VM and is
# invoked by CI (over SSH) as:
#     deploy-service.sh backend [DB_FILES_SRC]   |   deploy-service.sh frontend
#
# DB_FILES_SRC (backend only, optional): path on the VM to a freshly staged copy
#   of the repo's database_files (CI SCPs it there before calling this script).
#   When provided, it is swapped into /opt/foremen/database_files BEFORE the
#   Liquibase migration runs, so the migrations the VM applies match the image
#   being deployed. Omit it to migrate with whatever files already sit on the VM
#   (manual/legacy invocation).
#
# Guarantees:
#   - Only the named service is pulled and recreated (the other keeps running).
#   - A flock serializes near-simultaneous backend+frontend deploys so two
#     `compose up` calls never interleave (the second waits for the first).
#   - For backend: the staged database_files (if given) are swapped in, then the
#     one-shot `liquibase` service is force-recreated to apply pending migrations
#     BEFORE the backend is recreated (schema-first). This ordering is what keeps
#     a validate-mode backend from crash-looping on a missing table.
#   - After recreating, it waits until the container reports a STABLE healthy
#     state (several consecutive healthy checks) before returning success.
set -euo pipefail

SERVICE="${1:-}"
DB_FILES_SRC="${2:-}"
case "${SERVICE}" in
  backend|frontend) ;;
  *) echo "usage: $0 <backend|frontend> [DB_FILES_SRC]"; exit 2 ;;
esac

APP_DIR=/opt/foremen
# Run docker/compose as root: /opt/foremen/.env is root-owned (0600) and the
# docker socket needs privilege. OS Login (osAdminLogin) grants passwordless sudo,
# so CI and admins invoke the same way without being in the docker group.
SUDO="sudo"
COMPOSE=(${SUDO} docker compose -f "${APP_DIR}/docker-compose.dev.yml" --env-file "${APP_DIR}/.env")
# flock serializes backend+frontend deploys on this single VM. The lock file is
# shared across DIFFERENT OS Login users (CI SA vs. admins), each a distinct
# POSIX uid, so it must be world-writable. Ensure that via sudo before opening.
LOCK=/var/lock/foremen-deploy.lock
${SUDO} sh -c ": >> '${LOCK}' && chmod 0666 '${LOCK}'" 2>/dev/null || true

# --- serialize all deploys on this VM (wait up to 10 min for the other one) ---
exec 9>"${LOCK}"
echo ">> [$(date -u +%H:%M:%S)] waiting for deploy lock..."
flock -w 600 9 || { echo "!! could not acquire deploy lock within 600s"; exit 1; }
echo ">> lock acquired; deploying ${SERVICE}"

cd "${APP_DIR}"

# --- pull the new image for just this service ---
echo ">> pulling ${SERVICE} image"
"${COMPOSE[@]}" pull "${SERVICE}"

# --- backend: sync changesets, migrate (schema-first), then recreate ---
if [ "${SERVICE}" = "backend" ]; then
  # 1) Swap in the staged changesets so the migration source matches the image.
  if [ -n "${DB_FILES_SRC}" ]; then
    if [ ! -d "${DB_FILES_SRC}" ]; then
      echo "!! staged database_files not found at ${DB_FILES_SRC}"; exit 1
    fi
    if [ ! -f "${DB_FILES_SRC}/changelog.xml" ]; then
      echo "!! ${DB_FILES_SRC} does not look like a database_files dir (no changelog.xml)"; exit 1
    fi
    echo ">> syncing Liquibase changesets from ${DB_FILES_SRC}"
    if [ -d "${APP_DIR}/database_files" ]; then
      BACKUP="${APP_DIR}/database_files.bak.$(date -u +%Y%m%d-%H%M%S)"
      echo ">> backing up current changesets to ${BACKUP}"
      ${SUDO} cp -a "${APP_DIR}/database_files" "${BACKUP}"
      # keep only the 5 most recent backups
      ${SUDO} sh -c "ls -1dt '${APP_DIR}'/database_files.bak.* 2>/dev/null | tail -n +6 | xargs -r rm -rf" || true
    fi
    ${SUDO} rm -rf "${APP_DIR}/database_files"
    ${SUDO} cp -a "${DB_FILES_SRC}" "${APP_DIR}/database_files"
    ${SUDO} rm -rf "${DB_FILES_SRC}" || true
  else
    echo ">> no DB_FILES_SRC given; migrating with existing ${APP_DIR}/database_files"
  fi

  # 2) Run the one-shot liquibase service to apply pending migrations BEFORE the
  #    backend is recreated. `run --rm` always spins up a FRESH container (and
  #    removes it after), so it runs against the just-synced changesets rather
  #    than reusing a stale one — no --force-recreate needed (that flag belongs to
  #    `compose up`, not `compose run`). Clear any leftover one-shot container
  #    first so a prior failed/exited run can't linger.
  echo ">> running Liquibase migrations (fresh one-shot service, before backend)"
  "${COMPOSE[@]}" rm -fsv liquibase >/dev/null 2>&1 || true
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
  STATUS="$(${SUDO} docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${CID}" 2>/dev/null || echo "unknown")"
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
${SUDO} docker image prune -f >/dev/null 2>&1 || true
echo ">> [$(date -u +%H:%M:%S)] deploy of ${SERVICE} complete"
