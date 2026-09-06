#!/bin/bash
# Deploy Cloud Functions to Google Cloud
set -e

# Load production env vars
set -a
source "$(dirname "$0")/.env.production"
set +a

PROJECT_ID="starry-tracker-505110-s3"
REGION="europe-central2"
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$SOURCE_DIR"

# Read service account key as inline JSON
SA_KEY_FILE="$SOURCE_DIR/../starry-tracker-505110-s3-326364be8f95.json"
if [ ! -f "$SA_KEY_FILE" ]; then
  echo "ERROR: Service account key not found: $SA_KEY_FILE"
  exit 1
fi
SA_KEY_JSON=$(cat "$SA_KEY_FILE" | tr -d '\n')

echo "=== Setting gcloud config ==="
gcloud config set account info@foremen.eu 2>/dev/null
gcloud config set project $PROJECT_ID 2>/dev/null

echo ""
echo "=== Building TypeScript ==="
rm -rf dist
bun x tsc

echo ""
echo "=== Deploying receipt-bot ==="
gcloud functions deploy receipt-bot \
  --gen2 \
  --runtime=nodejs20 \
  --trigger-http \
  --allow-unauthenticated \
  --entry-point=receiptBot \
  --region=$REGION \
  --source=. \
  --memory=512MB \
  --timeout=120s \
  --set-env-vars="^##^NODE_ENV=production##TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}##TELEGRAM_WEBHOOK_SECRET=${TELEGRAM_WEBHOOK_SECRET}##GOOGLE_SERVICE_ACCOUNT_KEY_JSON=${SA_KEY_JSON}##GOOGLE_IMPERSONATE_EMAIL=${GOOGLE_IMPERSONATE_EMAIL}##WORKERS_SPREADSHEET_ID=${WORKERS_SPREADSHEET_ID}##PROJECTS_SPREADSHEET_ID=${PROJECTS_SPREADSHEET_ID}##ESTIMATES_FOLDER_ID=${ESTIMATES_FOLDER_ID}##PROJECTS_PARENT_FOLDER_ID=${PROJECTS_PARENT_FOLDER_ID}##SHARED_DRIVE_ID=${SHARED_DRIVE_ID}##WORKERS_SHEET_NAME=${WORKERS_SHEET_NAME}##ACCESS_SHEET_NAME=${ACCESS_SHEET_NAME}##BOT_STATE_SHEET_NAME=${BOT_STATE_SHEET_NAME}##SESSION_LOG_SHEET_NAME=${SESSION_LOG_SHEET_NAME:-session_log}##PROJECTS_SHEET_NAME=${PROJECTS_SHEET_NAME}##RECEIPTS_SHEET_NAME=${RECEIPTS_SHEET_NAME}##TEMPLATE_SPREADSHEET_ID=${TEMPLATE_SPREADSHEET_ID}##APPS_SCRIPT_WEBHOOK_SECRET=${APPS_SCRIPT_WEBHOOK_SECRET}##ADMIN_TELEGRAM_ID=${ADMIN_TELEGRAM_ID}##BITRIX_DRIVE_URL_FIELD=${BITRIX_DRIVE_URL_FIELD}##BITRIX_SHEETS_URL_FIELD=${BITRIX_SHEETS_URL_FIELD}##BITRIX_TELEGRAM_ID_FIELD=${BITRIX_TELEGRAM_ID_FIELD}##OCR_ENABLED=${OCR_ENABLED}##GOOGLE_CLOUD_PROJECT_ID=${GCP_PROJECT_ID}##GEMINI_API_KEY=${GEMINI_API_KEY}##GEMINI_MODEL=${GEMINI_MODEL}"

echo ""
echo "=== Getting URL ==="
TELEGRAM_URL=$(gcloud functions describe receipt-bot --region=$REGION --gen2 --format="value(serviceConfig.uri)" 2>/dev/null)

echo ""
echo "receipt-bot deployed: $TELEGRAM_URL"
echo ""
echo "=== Done! ==="
