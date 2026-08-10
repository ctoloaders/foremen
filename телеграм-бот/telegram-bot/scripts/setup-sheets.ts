/**
 * Setup script: populates existing Google Sheets with correct tabs and headers.
 *
 * Mapping:
 * - "бот регистри" → workers registry (workers, project_access, bot_state)
 * - "список проектов" → projects registry (projects list)
 * - Папка → parent folder for per-project estimate spreadsheets
 */

import { google } from "googleapis";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));

const KEY_PATH = resolve(__dirname, "../../forementest-2064c9f53f45.json");

// "бот регистри" — workers, project_access, bot_state
const WORKERS_SPREADSHEET_ID = "16TNx4c1naOf281rOBqwOTivdiV9ylhxsaH2yEIs6-Cc";

// "список проектов" — projects list
const PROJECTS_SPREADSHEET_ID = "1GOtjZBPQnQHtGarGZhqDJTp2MIC4KFzaNLVmuWbO7hE";

// Папка — для смет (спредшитов) каждого проекта
const ESTIMATES_FOLDER_ID = "1KvRRHTHRAAvkg1Rv5P17qixiyaHPtgDR";

async function setupSpreadsheet(
  sheets: any,
  spreadsheetId: string,
  name: string,
  requiredTabs: { title: string; headers: string[] }[]
) {
  console.log(`\nSetting up "${name}"...`);

  const info = await sheets.spreadsheets.get({ spreadsheetId });
  const existingSheets = info.data.sheets?.map((s: any) => s.properties?.title) || [];
  console.log("  Existing tabs:", existingSheets.join(", ") || "(none)");

  // Create missing tabs
  const tabsToCreate = requiredTabs.filter(t => !existingSheets.includes(t.title));
  if (tabsToCreate.length > 0) {
    console.log("  Creating tabs:", tabsToCreate.map(t => t.title).join(", "));
    await sheets.spreadsheets.batchUpdate({
      spreadsheetId,
      requestBody: {
        requests: tabsToCreate.map(t => ({ addSheet: { properties: { title: t.title } } })),
      },
    });
  }

  // Remove default "Sheet1" / "Лист1" if exists
  for (const defaultName of ["Sheet1", "Лист1"]) {
    if (existingSheets.includes(defaultName)) {
      const defaultSheet = info.data.sheets?.find((s: any) => s.properties?.title === defaultName);
      if (defaultSheet) {
        try {
          await sheets.spreadsheets.batchUpdate({
            spreadsheetId,
            requestBody: {
              requests: [{ deleteSheet: { sheetId: defaultSheet.properties?.sheetId } }],
            },
          });
          console.log(`  Removed "${defaultName}"`);
        } catch {
          console.log(`  Could not remove "${defaultName}"`);
        }
      }
    }
  }

  // Set headers
  console.log("  Setting headers...");
  await sheets.spreadsheets.values.batchUpdate({
    spreadsheetId,
    requestBody: {
      valueInputOption: "RAW",
      data: requiredTabs.map(t => ({
        range: `${t.title}!A1:${String.fromCharCode(64 + t.headers.length)}1`,
        values: [t.headers],
      })),
    },
  });

  // Bold + freeze headers
  const updated = await sheets.spreadsheets.get({ spreadsheetId });
  const sheetIds = updated.data.sheets!
    .filter((s: any) => requiredTabs.some(t => t.title === s.properties?.title))
    .map((s: any) => s.properties!.sheetId!);

  await sheets.spreadsheets.batchUpdate({
    spreadsheetId,
    requestBody: {
      requests: [
        ...sheetIds.map((sheetId: number) => ({
          repeatCell: {
            range: { sheetId, startRowIndex: 0, endRowIndex: 1 },
            cell: {
              userEnteredFormat: {
                textFormat: { bold: true },
                backgroundColor: { red: 0.93, green: 0.93, blue: 0.93 },
              },
            },
            fields: "userEnteredFormat(textFormat,backgroundColor)",
          },
        })),
        ...sheetIds.map((sheetId: number) => ({
          updateSheetProperties: {
            properties: { sheetId, gridProperties: { frozenRowCount: 1 } },
            fields: "gridProperties.frozenRowCount",
          },
        })),
      ],
    },
  });

  console.log("  Done!");
}

async function main() {
  const keyFile = JSON.parse(readFileSync(KEY_PATH, "utf-8"));
  const auth = new google.auth.GoogleAuth({
    credentials: keyFile,
    scopes: [
      "https://www.googleapis.com/auth/spreadsheets",
      "https://www.googleapis.com/auth/drive",
    ],
  });

  const sheets = google.sheets({ version: "v4", auth });
  const drive = google.drive({ version: "v3", auth });

  // --- Workers spreadsheet (workers, project_access, bot_state) ---
  await setupSpreadsheet(sheets, WORKERS_SPREADSHEET_ID, "Workers Registry", [
    { title: "workers", headers: ["bitrix_user_id", "telegram_id", "worker_name", "role"] },
    { title: "project_access", headers: ["project_name", "worker_name", "role_in_project"] },
    { title: "bot_state", headers: ["telegram_id", "step", "project_name", "project_drive_url", "project_sheets_url", "photo_file_id", "sum", "description", "store_name", "updated_at"] },
  ]);

  // --- Projects spreadsheet (projects) ---
  await setupSpreadsheet(sheets, PROJECTS_SPREADSHEET_ID, "Projects Registry", [
    { title: "projects", headers: ["project_name", "google_drive_url", "google_sheets_url", "status", "date_added"] },
  ]);

  // --- Verify Drive folder access ---
  console.log("\nVerifying Drive folder access...");
  const folderCheck = await drive.files.get({
    fileId: ESTIMATES_FOLDER_ID,
    fields: "id,name,mimeType",
  });
  console.log(`  Folder OK: "${folderCheck.data.name}" (this is where per-project estimate sheets will be created)`);

  // --- Add test data ---
  console.log("\nAdding test data...");

  // Clear old data first (in case of re-run)
  await sheets.spreadsheets.values.clear({
    spreadsheetId: WORKERS_SPREADSHEET_ID,
    range: "workers!A2:D100",
  });
  await sheets.spreadsheets.values.clear({
    spreadsheetId: WORKERS_SPREADSHEET_ID,
    range: "project_access!A2:C100",
  });
  await sheets.spreadsheets.values.clear({
    spreadsheetId: PROJECTS_SPREADSHEET_ID,
    range: "projects!A2:E100",
  });

  // Add admin user (you) — telegram_id empty, fill after /myid
  await sheets.spreadsheets.values.append({
    spreadsheetId: WORKERS_SPREADSHEET_ID,
    range: "workers!A:D",
    valueInputOption: "RAW",
    requestBody: { values: [["admin_1", "", "Alexander Borohov", "admin"]] },
  });
  console.log("  Added admin user (Telegram ID blank — use /myid to get it)");

  // Add test project
  const today = new Date().toISOString().split("T")[0];
  await sheets.spreadsheets.values.append({
    spreadsheetId: PROJECTS_SPREADSHEET_ID,
    range: "projects!A:E",
    valueInputOption: "RAW",
    requestBody: {
      values: [[
        "Тест-проект",
        `https://drive.google.com/drive/folders/${ESTIMATES_FOLDER_ID}`,
        "", // no estimate sheet yet — will be created when first receipt comes in, or manually
        "active",
        today,
      ]],
    },
  });
  console.log("  Added test project (estimate sheet URL empty — create one or bot will need one)");

  // Add project access
  await sheets.spreadsheets.values.append({
    spreadsheetId: WORKERS_SPREADSHEET_ID,
    range: "project_access!A:C",
    valueInputOption: "RAW",
    requestBody: { values: [["Тест-проект", "Alexander Borohov", "admin"]] },
  });
  console.log("  Added project access: Alexander Borohov → Тест-проект");

  // --- SUMMARY ---
  console.log("\n" + "=".repeat(60));
  console.log("SETUP COMPLETE!");
  console.log("=".repeat(60));
  console.log(`\nWorkers Registry: https://docs.google.com/spreadsheets/d/${WORKERS_SPREADSHEET_ID}/edit`);
  console.log(`  Tabs: workers, project_access, bot_state`);
  console.log(`\nProjects Registry: https://docs.google.com/spreadsheets/d/${PROJECTS_SPREADSHEET_ID}/edit`);
  console.log(`  Tabs: projects`);
  console.log(`\nEstimates Folder: https://drive.google.com/drive/folders/${ESTIMATES_FOLDER_ID}`);
  console.log(`  Per-project estimate spreadsheets go here`);
  console.log("\n--- .env values ---");
  console.log(`TELEGRAM_BOT_TOKEN=<SET_YOUR_BOT_TOKEN>`);
  console.log(`TELEGRAM_WEBHOOK_SECRET=foremen-bot-webhook-secret-2024`);
  console.log(`GOOGLE_SERVICE_ACCOUNT_JSON=../forementest-2064c9f53f45.json`);
  console.log(`WORKERS_SPREADSHEET_ID=${WORKERS_SPREADSHEET_ID}`);
  console.log(`PROJECTS_SPREADSHEET_ID=${PROJECTS_SPREADSHEET_ID}`);
  console.log(`ESTIMATES_FOLDER_ID=${ESTIMATES_FOLDER_ID}`);
  console.log(`WORKERS_SHEET_NAME=workers`);
  console.log(`ACCESS_SHEET_NAME=project_access`);
  console.log(`BOT_STATE_SHEET_NAME=bot_state`);
  console.log(`PROJECTS_SHEET_NAME=projects`);
  console.log(`APPS_SCRIPT_WEBHOOK_SECRET=foremen-apps-script-secret-2024`);
  console.log(`GCP_PROJECT_ID=forementest`);
  console.log(`GCP_REGION=me-west1`);
  console.log(`FUNCTION_NAME=receipt-bot`);
}

main().catch((err) => {
  console.error("Setup failed:", err.message || err);
  if (err.response) {
    console.error("Status:", err.response.status);
    console.error("Data:", JSON.stringify(err.response.data, null, 2));
  }
  process.exit(1);
});
