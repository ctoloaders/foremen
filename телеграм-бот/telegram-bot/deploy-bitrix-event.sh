#!/bin/bash
# Deploy the bitrix-event Cloud Function (the SINGLE Bitrix outgoing-webhook handler:
# receives a deal event, fetches deal data from Bitrix REST, syncs the project + access
# registries keyed by Bitrix deal_id). Passes the full env block so config validation passes;
# OCR_ENABLED=false because this function does not use OCR (and config requires GEMINI_API_KEY
# when OCR is on).
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

echo "=== Deploying bitrix-event ==="
gcloud functions deploy bitrix-event \
  --gen2 \
  --runtime=nodejs20 \
  --trigger-http \
  --allow-unauthenticated \
  --entry-point=bitrixEvent \
  --region=$REGION \
  --source=. \
  --memory=256MB \
  --timeout=120s \
  --set-env-vars="^##^NODE_ENV=production##TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}##TELEGRAM_WEBHOOK_SECRET=${TELEGRAM_WEBHOOK_SECRET}##GOOGLE_SERVICE_ACCOUNT_KEY_JSON=${SA_KEY_JSON}##GOOGLE_IMPERSONATE_EMAIL=${GOOGLE_IMPERSONATE_EMAIL}##WORKERS_SPREADSHEET_ID=${WORKERS_SPREADSHEET_ID}##PROJECTS_SPREADSHEET_ID=${PROJECTS_SPREADSHEET_ID}##ESTIMATES_FOLDER_ID=${ESTIMATES_FOLDER_ID}##PROJECTS_PARENT_FOLDER_ID=${PROJECTS_PARENT_FOLDER_ID}##SHARED_DRIVE_ID=${SHARED_DRIVE_ID}##WORKERS_SHEET_NAME=${WORKERS_SHEET_NAME}##ACCESS_SHEET_NAME=${ACCESS_SHEET_NAME}##BOT_STATE_SHEET_NAME=${BOT_STATE_SHEET_NAME}##PROJECTS_SHEET_NAME=${PROJECTS_SHEET_NAME}##RECEIPTS_SHEET_NAME=${RECEIPTS_SHEET_NAME}##TEMPLATE_SPREADSHEET_ID=${TEMPLATE_SPREADSHEET_ID}##APPS_SCRIPT_WEBHOOK_SECRET=${APPS_SCRIPT_WEBHOOK_SECRET}##ADMIN_TELEGRAM_ID=${ADMIN_TELEGRAM_ID}##BITRIX_DRIVE_URL_FIELD=${BITRIX_DRIVE_URL_FIELD}##BITRIX_SHEETS_URL_FIELD=${BITRIX_SHEETS_URL_FIELD}##BITRIX_TELEGRAM_ID_FIELD=${BITRIX_TELEGRAM_ID_FIELD}##BITRIX_WEBHOOK_URL=${BITRIX_WEBHOOK_URL}##OCR_ENABLED=false##GOOGLE_CLOUD_PROJECT_ID=${GCP_PROJECT_ID}##GEMINI_API_KEY=${GEMINI_API_KEY}##GEMINI_MODEL=${GEMINI_MODEL}"

echo "=== Done! bitrix-event deployed ==="
