/**
 * Production setup: creates registry spreadsheets in the client's folder.
 * Uses impersonation (domain-wide delegation).
 * Does NOT touch existing files.
 */

import { google } from "googleapis";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const KEY_PATH = resolve(__dirname, "../../starry-tracker-505110-s3-326364be8f95.json");

const FOLDER_ID = "1OaJZL5HjSaRgXNfhwlszfhCPtFwZoige";
const IMPERSONATE_EMAIL = "info@foremen.eu";

const key = JSON.parse(readFileSync(KEY_PATH, "utf-8"));
const auth = new google.auth.GoogleAuth({
  credentials: key,
  scopes: ["https://www.googleapis.com/auth/drive", "https://www.googleapis.com/auth/spreadsheets"],
  clientOptions: { subject: IMPERSONATE_EMAIL },
});
const drive = google.drive({ version: "v3", auth });
const sheets = google.sheets({ version: "v4", auth });

async function createSpreadsheet(name: string, parentId: string): Promise<string> {
  const res = await drive.files.create({
    requestBody: {
      name,
      mimeType: "application/vnd.google-apps.spreadsheet",
      parents: [parentId],
    },
    fields: "id,webViewLink",
  });
  return res.data.id!;
}

async function main() {
  console.log("=== PRODUCTION SETUP ===");
  console.log(`Folder: ${FOLDER_ID}`);
  console.log(`Impersonate: ${IMPERSONATE_EMAIL}\n`);

  // 1. Create Workers Registry
  console.log("Creating: Реестр работников...");
  const workersId = await createSpreadsheet("Foremen Bot — Реестр работников", FOLDER_ID);
  console.log(`  ID: ${workersId}`);

  // Rename Sheet1 -> workers, add project_access, bot_state
  const wInfo = await sheets.spreadsheets.get({ spreadsheetId: workersId });
  const wDefaultId = wInfo.data.sheets![0].properties!.sheetId!;

  await sheets.spreadsheets.batchUpdate({
    spreadsheetId: workersId,
    requestBody: {
      requests: [
        { updateSheetProperties: { properties: { sheetId: wDefaultId, title: "workers" }, fields: "title" } },
        { addSheet: { properties: { title: "project_access" } } },
        { addSheet: { properties: { title: "bot_state" } } },
      ],
    },
  });

  await sheets.spreadsheets.values.batchUpdate({
    spreadsheetId: workersId,
    requestBody: {
      valueInputOption: "RAW",
      data: [
        { range: "workers!A1:D1", values: [["bitrix_user_id", "telegram_id", "worker_name", "role"]] },
        { range: "project_access!A1:D1", values: [["project_name", "worker_name", "role_in_project", "worker_id"]] },
        { range: "bot_state!A1:J1", values: [["telegram_id", "step", "project_name", "project_drive_url", "project_sheets_url", "photo_file_id", "sum", "description", "store_name", "updated_at"]] },
      ],
    },
  });

  // Bold + freeze headers
  const wUpdated = await sheets.spreadsheets.get({ spreadsheetId: workersId });
  const wSheetIds = wUpdated.data.sheets!.map(s => s.properties!.sheetId!);
  await sheets.spreadsheets.batchUpdate({
    spreadsheetId: workersId,
    requestBody: {
      requests: [
        ...wSheetIds.map(sheetId => ({
          repeatCell: {
            range: { sheetId, startRowIndex: 0, endRowIndex: 1 },
            cell: { userEnteredFormat: { textFormat: { bold: true }, backgroundColor: { red: 0.93, green: 0.93, blue: 0.93 } } },
            fields: "userEnteredFormat(textFormat,backgroundColor)",
          },
        })),
        ...wSheetIds.map(sheetId => ({
          updateSheetProperties: {
            properties: { sheetId, gridProperties: { frozenRowCount: 1 } },
            fields: "gridProperties.frozenRowCount",
          },
        })),
      ],
    },
  });
  console.log("  Done: workers, project_access, bot_state\n");

  // 2. Create Projects Registry
  console.log("Creating: Реестр проектов...");
  const projectsId = await createSpreadsheet("Foremen Bot — Реестр проектов", FOLDER_ID);
  console.log(`  ID: ${projectsId}`);

  const pInfo = await sheets.spreadsheets.get({ spreadsheetId: projectsId });
  const pDefaultId = pInfo.data.sheets![0].properties!.sheetId!;

  await sheets.spreadsheets.batchUpdate({
    spreadsheetId: projectsId,
    requestBody: {
      requests: [
        { updateSheetProperties: { properties: { sheetId: pDefaultId, title: "projects" }, fields: "title" } },
        {
          repeatCell: {
            range: { sheetId: pDefaultId, startRowIndex: 0, endRowIndex: 1 },
            cell: { userEnteredFormat: { textFormat: { bold: true }, backgroundColor: { red: 0.93, green: 0.93, blue: 0.93 } } },
            fields: "userEnteredFormat(textFormat,backgroundColor)",
          },
        },
        {
          updateSheetProperties: {
            properties: { sheetId: pDefaultId, gridProperties: { frozenRowCount: 1 } },
            fields: "gridProperties.frozenRowCount",
          },
        },
      ],
    },
  });

  await sheets.spreadsheets.values.update({
    spreadsheetId: projectsId,
    range: "projects!A1:E1",
    valueInputOption: "RAW",
    requestBody: { values: [["project_name", "google_drive_url", "google_sheets_url", "status", "date_added"]] },
  });
  console.log("  Done: projects\n");

  // Summary
  console.log("=== SETUP COMPLETE ===\n");
  console.log(`Workers Registry: https://docs.google.com/spreadsheets/d/${workersId}/edit`);
  console.log(`Projects Registry: https://docs.google.com/spreadsheets/d/${projectsId}/edit`);
  console.log(`\n--- For .env ---`);
  console.log(`WORKERS_SPREADSHEET_ID=${workersId}`);
  console.log(`PROJECTS_SPREADSHEET_ID=${projectsId}`);
  console.log(`PROJECTS_PARENT_FOLDER_ID=${FOLDER_ID}`);
  console.log(`GOOGLE_IMPERSONATE_EMAIL=${IMPERSONATE_EMAIL}`);
}

main().catch(err => {
  console.error("FAILED:", err.message);
  if (err.response) console.error(JSON.stringify(err.response.data, null, 2));
  process.exit(1);
});
