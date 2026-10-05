#!/usr/bin/env bash
# Build the backend and frontend images and push them to Artifact Registry.
# Builds for linux/amd64 (the dev VM is amd64) regardless of your laptop's arch.
#
# Prereq: Docker with buildx. On Apple Silicon this cross-builds via QEMU.
# Alternative: use 45-cloud-build.optional.sh to build in the cloud instead.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
REPO_ROOT="$(cd ../.. && pwd)"

echo ">> Repo root: ${REPO_ROOT}"
echo ">> Ensuring buildx builder exists"
docker buildx inspect foremen-builder >/dev/null 2>&1 || docker buildx create --name foremen-builder --use
docker buildx use foremen-builder

echo ">> Building + pushing backend -> ${IMAGE_BACKEND}"
docker buildx build --platform linux/amd64 \
  -t "${IMAGE_BACKEND}" \
  -f "${REPO_ROOT}/foremen-backend/Dockerfile" \
  "${REPO_ROOT}/foremen-backend" \
  --push

echo ">> Building + pushing frontend -> ${IMAGE_FRONTEND}"
docker buildx build --platform linux/amd64 \
  -t "${IMAGE_FRONTEND}" \
  -f "${REPO_ROOT}/foremen-frontend/Dockerfile" \
  "${REPO_ROOT}/foremen-frontend" \
  --push

echo ">> Done. Pushed:"
echo "   ${IMAGE_BACKEND}"
echo "   ${IMAGE_FRONTEND}"
