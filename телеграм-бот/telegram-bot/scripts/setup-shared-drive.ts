/**
 * Setup script for Shared Drive.
 * Creates all spreadsheets and folders inside the Shared Drive.
 * SA can create files in Shared Drives without storage quota issues.
 */

import { google } from "googleapis";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const KEY_PATH = resolve(__dirname, "../../forementest-2064c9f53f45.json");

// Shared Drive ID (from URL: /folders/0AHkU6n74cG-CUk9PVA)
const SHARED_DRIVE_ID = "0AHkU6n74cG-CUk9PVA";

const ADMIN_TELEGRAM_ID = 571744833;
const ADMIN_NAME = "Alexander Borohov";

async function main() {
  const keyFile = JSON.parse(readFileSync(KEY_PATH, "utf-8"));
  const auth = new google.auth.GoogleAuth({
    credentials: keyFile,
    scopes: [
      "https://www.googleapis.com/auth/spreadsheets",
      "https://www.googleapis.com/auth/drive",
    ],
  });

  const drive = google.drive({ version: "v3", auth });
  const sheets = google.sheets({ version: "v4", auth });

  // Verify access to Shared Drive
  console.log("Verifying Shared Drive access...");
  const driveInfo = await drive.drives.get({ driveId: SHARED_DRIVE_ID });
  console.log(`  Shared Drive: "${driveInfo.data.name}"`);

  // --- Create folder structure ---
  console.log("\nCreating folder structure...");

  // 1. Create "Реестры" folder
  const registryFolder = await drive.files.create({
    requestBody: {
      name: "Реестры",
      mimeType: "application/vnd.google-apps.folder",
      parents: [SHARED_DRIVE_ID],
    },
    supportsAllDrives: true,
    fields: "id,name",
  });
  console.log(`  Created folder: Реестры (${registryFolder.data.id})`);

  // 2. Create "Проекты" folder (will contain per-project subfolders with receipts)
  const projectsFolder = await drive.files.create({
    requestBody: {
      name: "Проекты",
      mimeType: "application/vnd.google-apps.folder",
      parents: [SHARED_DRIVE_ID],
    },
    supportsAllDrives: true,
    fields: "id,name",
  });
  console.log(`  Created folder: Проекты (${projectsFolder.data.id})`);

  // 3. Create test project folder inside "Проекты"
  const testProjectFolder = await drive.files.create({
    requestBody: {
      name: "Тест-проект — Чеки",
      mimeType: "application/vnd.google-apps.folder",
      parents: [projectsFolder.data.id!],
    },
    supportsAllDrives: true,
    fields: "id,name,webViewLink",
  });
  console.log(`  Created folder: Тест-проект — Чеки (${testProjectFolder.data.id})`);

  // --- Create spreadsheets ---
  console.log("\nCreating spreadsheets...");

  // 1. Workers Registry (workers, project_access, bot_state)
  const workersSheet = await drive.files.create({
    requestBody: {
      name: "Реестр работников",
      mimeType: "application/vnd.google-apps.spreadsheet",
      parents: [registryFolder.data.id!],
    },
    supportsAllDrives: true,
    fields: "id,name,webViewLink",
  });
  const workersSpreadsheetId = workersSheet.data.id!;
  console.log(`  Created: Реестр работников (${workersSpreadsheetId})`);

  // 2. Projects Registry (projects)
  const projectsSheet = await drive.files.create({
    requestBody: {
      name: "Реестр проектов",
      mimeType: "application/vnd.google-apps.spreadsheet",
      parents: [registryFolder.data.id!],
    },
    supportsAllDrives: true,
    fields: "id,name,webViewLink",
  });
  const projectsSpreadsheetId = projectsSheet.data.id!;
  console.log(`  Created: Реестр проектов (${projectsSpreadsheetId})`);

  // 3. Test project estimate
  const estimateSheet = await drive.files.create({
    requestBody: {
      name: "Тест-проект — Смета",
      mimeType: "application/vnd.google-apps.spreadsheet",
      parents: [testProjectFolder.data.id!],
    },
    supportsAllDrives: true,
    fields: "id,name,webViewLink",
  });
  const estimateSpreadsheetId = estimateSheet.data.id!;
  console.log(`  Created: Тест-проект — Смета (${estimateSpreadsheetId})`);

  // --- Setup Workers spreadsheet tabs ---
  console.log("\nSetting up Workers Registry tabs...");

  // Rename default Sheet1 → workers, add project_access, bot_state
  const workersInfo = await sheets.spreadsheets.get({ spreadsheetId: workersSpreadsheetId });
  const defaultSheetId = workersInfo.data.sheets![0].properties!.sheetId!;

  await sheets.spreadsheets.batchUpdate({
    spreadsheetId: workersSpreadsheetId,
    requestBody: {
      requests: [
        { updateSheetProperties: { properties: { sheetId: defaultSheetId, title: "workers" }, fields: "title" } },
        { addSheet: { properties: { title: "project_access" } } },
        { addSheet: { properties: { title: "bot_state" } } },
      ],
    },
  });

  await sheets.spreadsheets.values.batchUpdate({
    spreadsheetId: workersSpreadsheetId,
    requestBody: {
      valueInputOption: "RAW",
      data: [
        { range: "workers!A1:D1", values: [["bitrix_user_id", "telegram_id", "worker_name", "role"]] },
        { range: "project_access!A1:C1", values: [["project_name", "worker_name", "role_in_project"]] },
        { range: "bot_state!A1:J1", values: [["telegram_id", "step", "project_name", "project_drive_url", "project_sheets_url", "photo_file_id", "sum", "description", "store_name", "updated_at"]] },
      ],
    },
  });

  // Add admin user
  await sheets.spreadsheets.values.append({
    spreadsheetId: workersSpreadsheetId,
    range: "workers!A:D",
    valueInputOption: "RAW",
    requestBody: { values: [["admin_1", String(ADMIN_TELEGRAM_ID), ADMIN_NAME, "admin"]] },
  });

  // Add project access for admin
  await sheets.spreadsheets.values.append({
    spreadsheetId: workersSpreadsheetId,
    range: "project_access!A:C",
    valueInputOption: "RAW",
    requestBody: { values: [["Тест-проект", ADMIN_NAME, "admin"]] },
  });

  console.log("  Workers Registry done (admin added with TG ID)");

  // --- Setup Projects spreadsheet ---
  console.log("\nSetting up Projects Registry...");

  const projectsInfo = await sheets.spreadsheets.get({ spreadsheetId: projectsSpreadsheetId });
  const projDefaultSheetId = projectsInfo.data.sheets![0].properties!.sheetId!;

  await sheets.spreadsheets.batchUpdate({
    spreadsheetId: projectsSpreadsheetId,
    requestBody: {
      requests: [
        { updateSheetProperties: { properties: { sheetId: projDefaultSheetId, title: "projects" }, fields: "title" } },
      ],
    },
  });

  await sheets.spreadsheets.values.update({
    spreadsheetId: projectsSpreadsheetId,
    range: "projects!A1:E1",
    valueInputOption: "RAW",
    requestBody: { values: [["project_name", "google_drive_url", "google_sheets_url", "status", "date_added"]] },
  });

  // Add test project
  const today = new Date().toISOString().split("T")[0];
  const testDriveUrl = `https://drive.google.com/drive/folders/${testProjectFolder.data.id}`;
  const testSheetsUrl = `https://docs.google.com/spreadsheets/d/${estimateSpreadsheetId}/edit`;

  await sheets.spreadsheets.values.append({
    spreadsheetId: projectsSpreadsheetId,
    range: "projects!A:E",
    valueInputOption: "RAW",
    requestBody: { values: [["Тест-проект", testDriveUrl, testSheetsUrl, "active", today]] },
  });

  console.log("  Projects Registry done (test project added)");

  // --- Setup Estimate spreadsheet ---
  console.log("\nSetting up Test Estimate...");

  const estInfo = await sheets.spreadsheets.get({ spreadsheetId: estimateSpreadsheetId });
  const estDefaultSheetId = estInfo.data.sheets![0].properties!.sheetId!;

  await sheets.spreadsheets.batchUpdate({
    spreadsheetId: estimateSpreadsheetId,
    requestBody: {
      requests: [
        { updateSheetProperties: { properties: { sheetId: estDefaultSheetId, title: "receipts" }, fields: "title" } },
      ],
    },
  });

  await sheets.spreadsheets.values.update({
    spreadsheetId: estimateSpreadsheetId,
    range: "receipts!A1:E1",
    valueInputOption: "RAW",
    requestBody: { values: [["Дата", "Сумма", "Что куплено", "Магазин", "Ссылка на фото"]] },
  });

  console.log("  Estimate done");

  // --- Bold + freeze all headers ---
  console.log("\nApplying formatting...");
  for (const sid of [workersSpreadsheetId, projectsSpreadsheetId, estimateSpreadsheetId]) {
    const info = await sheets.spreadsheets.get({ spreadsheetId: sid });
    const ids = info.data.sheets!.map(s => s.properties!.sheetId!);
    await sheets.spreadsheets.batchUpdate({
      spreadsheetId: sid,
      requestBody: {
        requests: [
          ...ids.map(sheetId => ({
            repeatCell: {
              range: { sheetId, startRowIndex: 0, endRowIndex: 1 },
              cell: { userEnteredFormat: { textFormat: { bold: true }, backgroundColor: { red: 0.93, green: 0.93, blue: 0.93 } } },
              fields: "userEnteredFormat(textFormat,backgroundColor)",
            },
          })),
          ...ids.map(sheetId => ({
            updateSheetProperties: {
              properties: { sheetId, gridProperties: { frozenRowCount: 1 } },
              fields: "gridProperties.frozenRowCount",
            },
          })),
        ],
      },
    });
  }
  console.log("  Formatting applied");

  // --- Test upload to Shared Drive ---
  console.log("\nTesting file upload to Shared Drive...");
  const { Readable } = await import("stream");
  const buf = Buffer.from("test");
  const s = new Readable(); s.push(buf); s.push(null);
  const testFile = await drive.files.create({
    requestBody: { name: "test-upload-delete-me.txt", parents: [testProjectFolder.data.id!] },
    media: { mimeType: "text/plain", body: s },
    supportsAllDrives: true,
    fields: "id",
  });
  await drive.files.delete({ fileId: testFile.data.id!, supportsAllDrives: true });
  console.log("  Upload test PASSED! Files can be uploaded to Shared Drive.");

  // --- SUMMARY ---
  console.log("\n" + "=".repeat(60));
  console.log("SETUP COMPLETE!");
  console.log("=".repeat(60));
  console.log(`\nShared Drive: ${driveInfo.data.name} (${SHARED_DRIVE_ID})`);
  console.log(`\nWorkers Registry: https://docs.google.com/spreadsheets/d/${workersSpreadsheetId}/edit`);
  console.log(`Projects Registry: https://docs.google.com/spreadsheets/d/${projectsSpreadsheetId}/edit`);
  console.log(`Test Estimate: https://docs.google.com/spreadsheets/d/${estimateSpreadsheetId}/edit`);
  console.log(`Test Project Folder: ${testDriveUrl}`);
  console.log(`\nAdmin: ${ADMIN_NAME} (TG ID: ${ADMIN_TELEGRAM_ID})`);
  console.log("\n--- .env values ---");
  console.log(`TELEGRAM_BOT_TOKEN=<SET_YOUR_BOT_TOKEN>`);
  console.log(`TELEGRAM_WEBHOOK_SECRET=foremen-bot-webhook-secret-2024`);
  console.log(`GOOGLE_SERVICE_ACCOUNT_JSON=../forementest-2064c9f53f45.json`);
  console.log(`WORKERS_SPREADSHEET_ID=${workersSpreadsheetId}`);
  console.log(`PROJECTS_SPREADSHEET_ID=${projectsSpreadsheetId}`);
  console.log(`ESTIMATES_FOLDER_ID=${testProjectFolder.data.id}`);
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
