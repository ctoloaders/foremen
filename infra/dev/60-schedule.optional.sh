#!/usr/bin/env bash
# OPTIONAL cost saver: auto-stop the VM at night and auto-start on weekday
# mornings using an instance schedule (resource policy). Opt-in; not run by the
# main flow. Times come from START_SCHEDULE / STOP_SCHEDULE in config.env.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null

POLICY="foremen-dev-sched"
if gcloud compute resource-policies describe "${POLICY}" --region="${REGION}" >/dev/null 2>&1; then
  echo ">> Schedule policy exists: ${POLICY}"
else
  echo ">> Creating instance schedule: start='${START_SCHEDULE}' stop='${STOP_SCHEDULE}'"
  gcloud compute resource-policies create instance-schedule "${POLICY}" \
    --region="${REGION}" \
    --vm-start-schedule="${START_SCHEDULE}" \
    --vm-stop-schedule="${STOP_SCHEDULE}" \
    --timezone="Europe/Warsaw"
fi

echo ">> Attaching schedule to ${VM_NAME}"
gcloud compute instances add-resource-policies "${VM_NAME}" \
  --zone="${ZONE}" --resource-policies="${POLICY}"

echo ">> Done. VM will stop/start on the configured schedule (Europe/Warsaw)."
echo "   Detach later with: gcloud compute instances remove-resource-policies ${VM_NAME} --zone=${ZONE} --resource-policies=${POLICY}"
