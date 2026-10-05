#!/usr/bin/env bash
# Create the Artifact Registry Docker repo that will hold the dev images.
# Safe to re-run.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null

if gcloud artifacts repositories describe "${AR_REPO}" --location="${REGION}" >/dev/null 2>&1; then
  echo ">> Artifact Registry repo already exists: ${AR_REPO} (${REGION})"
else
  echo ">> Creating Artifact Registry repo: ${AR_REPO} (${REGION})"
  gcloud artifacts repositories create "${AR_REPO}" \
    --repository-format=docker \
    --location="${REGION}" \
    --description="Foremen dev images"
fi

echo ">> Configuring local docker auth for ${AR_HOST}"
gcloud auth configure-docker "${AR_HOST}" --quiet

echo ">> Done. Repo path: ${AR_HOST}/${PROJECT_ID}/${AR_REPO}"
