import { config as dotenvConfig } from "dotenv";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

dotenvConfig();

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

const __dirname = dirname(fileURLToPath(import.meta.url));

function loadServiceAccountKey(): object {
  const jsonPath = required("GOOGLE_SERVICE_ACCOUNT_JSON");
  const absPath = resolve(__dirname, "..", jsonPath);
  const content = readFileSync(absPath, "utf-8");
  return JSON.parse(content);
}

export const config = {
  telegram: {
    botToken: required("TELEGRAM_BOT_TOKEN"),
    webhookSecret: process.env.TELEGRAM_WEBHOOK_SECRET || "",
  },
  google: {
    serviceAccountKey: loadServiceAccountKey(),
    workersSpreadsheetId: required("WORKERS_SPREADSHEET_ID"),
    projectsSpreadsheetId: required("PROJECTS_SPREADSHEET_ID"),
    estimatesFolderId: required("ESTIMATES_FOLDER_ID"),
    sharedDriveId: required("SHARED_DRIVE_ID"),
    projectsParentFolderId: required("PROJECTS_PARENT_FOLDER_ID"),
    workersSheetName: process.env.WORKERS_SHEET_NAME || "workers",
    accessSheetName: process.env.ACCESS_SHEET_NAME || "project_access",
    botStateSheetName: process.env.BOT_STATE_SHEET_NAME || "bot_state",
    projectsSheetName: process.env.PROJECTS_SHEET_NAME || "projects",
  },
  bitrix: {
    // CRM Deal field codes
    driveUrlField: process.env.BITRIX_DRIVE_URL_FIELD || "UF_CRM_1785938336262",
    sheetsUrlField: process.env.BITRIX_SHEETS_URL_FIELD || "UF_CRM_1785938353178",
    // User profile field codes
    telegramIdField: process.env.BITRIX_TELEGRAM_ID_FIELD || "UF_USR_1785937945029",
    roleField: process.env.BITRIX_ROLE_FIELD || "UF_USR_1785938002037",
  },
  appsScript: {
    webhookSecret: process.env.APPS_SCRIPT_WEBHOOK_SECRET || "",
  },
} as const;
