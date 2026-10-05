#!/bin/bash
# Deploy the bitrix-webhook Cloud Function (project/worker upsert receiver).
# Mirrors deploy.sh's env block so config.ts validation passes (it requires GEMINI_API_KEY
# when OCR_ENABLED=true, even though this function doesn't use OCR).
set -e

set -a
source "$(dirname "$0")/.env.production"
set +a

PROJECT_ID="starry-tracker-505110-s3"
REGION="europe-central2"
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SOURCE_DIR"

SA_KEY_FILE="$SOURCE_DIR/../starry-tracker-505110-s3-326364be8f95.json"
SA_KEY_JSON=$(cat "$SA_KEY_FILE" | tr -d '\n')

gcloud config set project $PROJECT_ID 2>/dev/null

echo "=== Building TypeScript ==="
rm -rf dist
bun x tsc

echo "=== Deploying bitrix-webhook ==="
gcloud functions deploy bitrix-webhook \
  --gen2 \
  --runtime=nodejs20 \
  --trigger-http \
  --allow-unauthenticated \
  --entry-point=bitrixWebhook \
  --region=$REGION \
  --source=. \
  --memory=256MB \
  --timeout=120s \
  --set-env-vars="^##^NODE_ENV=production##TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}##TELEGRAM_WEBHOOK_SECRET=${TELEGRAM_WEBHOOK_SECRET}##GOOGLE_SERVICE_ACCOUNT_KEY_JSON=${SA_KEY_JSON}##GOOGLE_IMPERSONATE_EMAIL=${GOOGLE_IMPERSONATE_EMAIL}##WORKERS_SPREADSHEET_ID=${WORKERS_SPREADSHEET_ID}##PROJECTS_SPREADSHEET_ID=${PROJECTS_SPREADSHEET_ID}##ESTIMATES_FOLDER_ID=${ESTIMATES_FOLDER_ID}##PROJECTS_PARENT_FOLDER_ID=${PROJECTS_PARENT_FOLDER_ID}##SHARED_DRIVE_ID=${SHARED_DRIVE_ID}##WORKERS_SHEET_NAME=${WORKERS_SHEET_NAME}##ACCESS_SHEET_NAME=${ACCESS_SHEET_NAME}##BOT_STATE_SHEET_NAME=${BOT_STATE_SHEET_NAME}##PROJECTS_SHEET_NAME=${PROJECTS_SHEET_NAME}##RECEIPTS_SHEET_NAME=${RECEIPTS_SHEET_NAME}##TEMPLATE_SPREADSHEET_ID=${TEMPLATE_SPREADSHEET_ID}##APPS_SCRIPT_WEBHOOK_SECRET=${APPS_SCRIPT_WEBHOOK_SECRET}##ADMIN_TELEGRAM_ID=${ADMIN_TELEGRAM_ID}##BITRIX_DRIVE_URL_FIELD=${BITRIX_DRIVE_URL_FIELD}##BITRIX_SHEETS_URL_FIELD=${BITRIX_SHEETS_URL_FIELD}##BITRIX_TELEGRAM_ID_FIELD=${BITRIX_TELEGRAM_ID_FIELD}##OCR_ENABLED=${OCR_ENABLED}##GOOGLE_CLOUD_PROJECT_ID=${GCP_PROJECT_ID}##GEMINI_API_KEY=${GEMINI_API_KEY}##GEMINI_MODEL=${GEMINI_MODEL}"

echo "=== Done! bitrix-webhook deployed ==="
