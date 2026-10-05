#!/usr/bin/env bash
# OPTIONAL: use a managed Cloud SQL Postgres instance instead of the on-VM
# Postgres container. NOT part of the default (cheap) flow.
#
# If you run this, you must then:
#   1. Remove the `postgres` and `liquibase` services from docker-compose.dev.yml
#      (or create a Cloud SQL variant), and run the Cloud SQL Auth Proxy on the VM.
#   2. Point SPRING_DATASOURCE_URL at the proxy (jdbc:postgresql://127.0.0.1:5432/...).
#   3. Grant the VM service account roles/cloudsql.client.
#
# Cost note: the smallest tier (db-f1-micro) is the cheapest; it still costs
# noticeably more than the on-VM container. Kept here only for an easy switch.
set -euo pipefail
cd "$(dirname "$0")"
source ./config.env
gcloud config set project "${PROJECT_ID}" >/dev/null

gcloud services enable sqladmin.googleapis.com

INSTANCE="foremen-dev-pg"
if gcloud sql instances describe "${INSTANCE}" >/dev/null 2>&1; then
  echo ">> Cloud SQL instance exists: ${INSTANCE}"
else
  echo ">> Creating Cloud SQL (db-f1-micro, zonal, no HA) — this takes several minutes"
  gcloud sql instances create "${INSTANCE}" \
    --database-version=POSTGRES_16 \
    --tier=db-f1-micro \
    --region="${REGION}" \
    --storage-size=10GB --storage-type=HDD \
    --availability-type=zonal \
    --no-backup
fi

DB_PASSWORD="$(gcloud secrets versions access latest --secret="${SECRET_DB_PASSWORD}")"
gcloud sql databases create "${DB_NAME}" --instance="${INSTANCE}" 2>/dev/null || true
gcloud sql users create "${DB_USER}" --instance="${INSTANCE}" --password="${DB_PASSWORD}" 2>/dev/null \
  || gcloud sql users set-password "${DB_USER}" --instance="${INSTANCE}" --password="${DB_PASSWORD}"

echo ">> Connection name:"
gcloud sql instances describe "${INSTANCE}" --format='value(connectionName)'
echo ">> Remember to wire the Auth Proxy + update compose/datasource as noted in this script's header."
