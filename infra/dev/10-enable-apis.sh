#!/usr/bin/env bash
# Enable the Google Cloud APIs the DEV environment needs.
# Safe to re-run (enabling an already-enabled API is a no-op).
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env

echo ">> Project: ${PROJECT_ID}"
gcloud config set project "${PROJECT_ID}" >/dev/null

APIS=(
  compute.googleapis.com          # Compute Engine (the VM, static IP, firewall)
  artifactregistry.googleapis.com # Docker image registry
  secretmanager.googleapis.com    # Secrets (DB password, JWT, admin password)
  cloudbuild.googleapis.com       # Building images in-cloud (optional path)
  iam.googleapis.com              # Service account for the VM
  logging.googleapis.com          # VM / container logs
)

echo ">> Enabling APIs: ${APIS[*]}"
gcloud services enable "${APIS[@]}"

echo ">> Done. Enabled APIs:"
gcloud services list --enabled --format="value(config.name)" \
  | grep -E "compute|artifactregistry|secretmanager|cloudbuild|iam|logging" || true
