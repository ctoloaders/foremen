#!/bin/bash
# Deploy both Cloud Functions to Google Cloud
set -e

PROJECT_ID="forementest"
REGION="me-west1"
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$SOURCE_DIR"

echo "=== Setting gcloud config ==="
gcloud config set account cto@loaders.dev 2>/dev/null
gcloud config set project $PROJECT_ID 2>/dev/null

echo ""
echo "=== Enabling required APIs ==="
gcloud services enable cloudfunctions.googleapis.com 2>/dev/null || true
gcloud services enable cloudbuild.googleapis.com 2>/dev/null || true
gcloud services enable run.googleapis.com 2>/dev/null || true
gcloud services enable artifactregistry.googleapis.com 2>/dev/null || true

echo ""
echo "=== Building TypeScript ==="
rm -rf dist
bun x tsc

echo ""
echo "=== Preparing deploy package ==="
# Cloud Functions need: package.json + dist/ + node_modules/ + .env + service account key
# We deploy from the project root — main points to dist/index.js

echo ""
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
  --timeout=60s \
  --set-env-vars="TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN,GOOGLE_SERVICE_ACCOUNT_JSON=$GOOGLE_SERVICE_ACCOUNT_JSON,WORKERS_SPREADSHEET_ID=$WORKERS_SPREADSHEET_ID,PROJECTS_SPREADSHEET_ID=$PROJECTS_SPREADSHEET_ID,ESTIMATES_FOLDER_ID=$ESTIMATES_FOLDER_ID,WORKERS_SHEET_NAME=workers,ACCESS_SHEET_NAME=project_access,BOT_STATE_SHEET_NAME=bot_state,PROJECTS_SHEET_NAME=projects,APPS_SCRIPT_WEBHOOK_SECRET=$APPS_SCRIPT_WEBHOOK_SECRET"

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
  --memory=256MB \
  --timeout=60s \
  --set-env-vars="TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN,TELEGRAM_WEBHOOK_SECRET=$TELEGRAM_WEBHOOK_SECRET,GOOGLE_SERVICE_ACCOUNT_JSON=$GOOGLE_SERVICE_ACCOUNT_JSON,WORKERS_SPREADSHEET_ID=$WORKERS_SPREADSHEET_ID,PROJECTS_SPREADSHEET_ID=$PROJECTS_SPREADSHEET_ID,ESTIMATES_FOLDER_ID=$ESTIMATES_FOLDER_ID,WORKERS_SHEET_NAME=workers,ACCESS_SHEET_NAME=project_access,BOT_STATE_SHEET_NAME=bot_state,PROJECTS_SHEET_NAME=projects,APPS_SCRIPT_WEBHOOK_SECRET=$APPS_SCRIPT_WEBHOOK_SECRET"

echo ""
echo "=== Getting URLs ==="
TELEGRAM_URL=$(gcloud functions describe receipt-bot --region=$REGION --gen2 --format="value(serviceConfig.uri)" 2>/dev/null)
BITRIX_URL=$(gcloud functions describe bitrix-webhook --region=$REGION --gen2 --format="value(serviceConfig.uri)" 2>/dev/null)

echo ""
echo "Telegram bot webhook URL: $TELEGRAM_URL"
echo "Bitrix24 webhook URL:     $BITRIX_URL"
echo ""
echo "=== Done! ==="
echo ""
echo "Next steps:"
echo "  1. Set Telegram webhook: curl 'https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/setWebhook?url=$TELEGRAM_URL'"
echo "  2. Use '$BITRIX_URL' as webhook URL in Bitrix24 robots"
