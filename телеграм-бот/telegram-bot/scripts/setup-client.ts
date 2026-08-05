/**
 * Client setup script.
 * 
 * Input: Bitrix24 incoming webhook URL, Google SA JSON key, Telegram bot token.
 * 
 * What it does:
 * 1. Checks/creates required fields in Bitrix24 (CRM deal + user profile)
 * 2. Reads all active deals (projects) from Bitrix24
 * 3. For each project: creates Drive folder + estimate spreadsheet
 * 4. Syncs workers (resolves names, telegram IDs) into workers registry
 * 5. Creates project_access bindings
 * 6. Generates .env file
 * 7. Generates robot request body JSON
 *
 * Usage:
 *   bun scripts/setup-client.ts
 *   (reads from .env or pass args)
 */

import { google } from "googleapis";
import { readFileSync, writeFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));

// --- CONFIG (read from .env or hardcode for now) ---
import { config as dotenvConfig } from "dotenv";
dotenvConfig({ path: resolve(__dirname, "../.env") });

const BITRIX_WEBHOOK_URL = (process.env.BITRIX_WEBHOOK_URL || "https://b24-0vnef8.bitrix24.pl/rest/1/8d2rlmvb4feorcz4").replace(/\/$/, "");
const SA_KEY_PATH = resolve(__dirname, "..", process.env.GOOGLE_SERVICE_ACCOUNT_JSON || "../forementest-2064c9f53f45.json");
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || "";
const SHARED_DRIVE_ID = process.env.SHARED_DRIVE_ID || "0AHkU6n74cG-CUk9PVA";
const PROJECTS_PARENT_FOLDER_ID = process.env.PROJECTS_PARENT_FOLDER_ID || "12Q66EYWWsgjRtT2-8inJ91JtVctfsvjt";
const WORKERS_SPREADSHEET_ID = process.env.WORKERS_SPREADSHEET_ID || "";
const PROJECTS_SPREADSHEET_ID = process.env.PROJECTS_SPREADSHEET_ID || "";
const WEBHOOK_SECRET = process.env.APPS_SCRIPT_WEBHOOK_SECRET || "foremen-apps-script-secret-2024";

// --- Google Auth ---
const keyFile = JSON.parse(readFileSync(SA_KEY_PATH, "utf-8"));
const auth = new google.auth.GoogleAuth({
  credentials: keyFile,
  scopes: ["https://www.googleapis.com/auth/spreadsheets", "https://www.googleapis.com/auth/drive"],
});
const sheets = google.sheets({ version: "v4", auth });
const drive = google.drive({ version: "v3", auth });

// --- Bitrix helpers ---
async function bitrixCall(method: string, params: any = {}): Promise<any> {
  const res = await fetch(`${BITRIX_WEBHOOK_URL}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params),
  });
  return res.json();
}

async function bitrixGetAll(method: string, params: any = {}): Promise<any[]> {
  let all: any[] = [];
  let start = 0;
  while (true) {
    const res = await bitrixCall(method, { ...params, start });
    if (res.result) all = all.concat(res.result);
    if (!res.next) break;
    start = res.next;
  }
  return all;
}

// --- Main ---
async function main() {
  console.log("=== SETUP CLIENT ===\n");
  console.log(`Bitrix URL: ${BITRIX_WEBHOOK_URL}`);
  console.log(`SA Key: ${SA_KEY_PATH}`);
  console.log(`Shared Drive: ${SHARED_DRIVE_ID}`);

  // Step 1: Get Bitrix field codes
  console.log("\n--- Step 1: Parse Bitrix field codes ---");
  const dealFields = await bitrixCall("crm.deal.fields");
  const userFields = await bitrixCall("user.fields");

  const crmFields: Record<string, string> = {};
  for (const [k, v] of Object.entries(dealFields.result || {})) {
    if (k.startsWith("UF_CRM")) {
      const label = (v as any).formLabel || (v as any).listLabel || (v as any).title || k;
      crmFields[label] = k;
      console.log(`  Deal: ${k} = ${label}`);
    }
  }

  const usrFields: Record<string, string> = {};
  for (const [k, v] of Object.entries(userFields.result || {})) {
    if (k.startsWith("UF_USR")) {
      usrFields[v as string] = k;
      console.log(`  User: ${k} = ${v}`);
    }
  }

  // Identify key fields
  const driveUrlField = Object.values(crmFields).find(k => dealFields.result[k]?.formLabel?.toLowerCase().includes("drive")) || "";
  const sheetsUrlField = Object.values(crmFields).find(k => dealFields.result[k]?.formLabel?.toLowerCase().includes("sheet")) || "";
  const telegramIdField = Object.entries(usrFields).find(([label]) => label.toLowerCase().includes("telegram"))?.[1] || "";

  // Find employee fields (type === "employee")
  const employeeFields: { code: string; label: string }[] = [];
  for (const [k, v] of Object.entries(dealFields.result || {})) {
    if (k.startsWith("UF_CRM") && (v as any).type === "employee") {
      employeeFields.push({ code: k, label: (v as any).formLabel || (v as any).listLabel || k });
      console.log(`  Employee field: ${k} = ${(v as any).formLabel}`);
    }
  }

  console.log(`\n  Drive URL field: ${driveUrlField}`);
  console.log(`  Sheets URL field: ${sheetsUrlField}`);
  console.log(`  Telegram ID field: ${telegramIdField}`);
  console.log(`  Employee fields: ${employeeFields.map(f => f.label).join(", ")}`);

  // Step 2: Get all active deals
  console.log("\n--- Step 2: Read active deals from Bitrix ---");
  const deals = await bitrixGetAll("crm.deal.list", {
    filter: { "!STAGE_ID": "WON", "!STAGE_ID2": "LOSE" }, // not closed
    select: ["ID", "TITLE", ...employeeFields.map(f => f.code), driveUrlField, sheetsUrlField],
  });
  console.log(`  Found ${deals.length} active deals`);

  // Step 3: Get all users (for resolving names/TG IDs)
  console.log("\n--- Step 3: Read all users from Bitrix ---");
  const users = await bitrixGetAll("user.get", { ACTIVE: true });
  console.log(`  Found ${users.length} active users`);

  const userMap = new Map<string, any>();
  for (const u of users) {
    userMap.set(String(u.ID), u);
  }

  // Step 4: Process each deal — create Drive resources + sync
  console.log("\n--- Step 4: Process deals ---");

  const projectsData: any[] = [];
  const accessData: any[] = [];
  const workersMap = new Map<string, any>(); // userId -> worker data

  for (const deal of deals) {
    const projectName = deal.TITLE;
    let driveUrl = deal[driveUrlField] || "";
    let sheetsUrl = deal[sheetsUrlField] || "";

    console.log(`\n  Project: "${projectName}" (deal #${deal.ID})`);

    // Create Drive folder + estimate if not exists
    if (!driveUrl || !sheetsUrl) {
      console.log(`    Creating Drive resources...`);

      const folder = await drive.files.create({
        requestBody: {
          name: `${projectName} — Чеки`,
          mimeType: "application/vnd.google-apps.folder",
          parents: [PROJECTS_PARENT_FOLDER_ID],
        },
        supportsAllDrives: true,
        fields: "id",
      });
      driveUrl = `https://drive.google.com/drive/folders/${folder.data.id}`;

      const estimate = await drive.files.create({
        requestBody: {
          name: `${projectName} — Смета`,
          mimeType: "application/vnd.google-apps.spreadsheet",
          parents: [folder.data.id!],
        },
        supportsAllDrives: true,
        fields: "id",
      });
      const estimateId = estimate.data.id!;
      sheetsUrl = `https://docs.google.com/spreadsheets/d/${estimateId}/edit`;

      // Setup estimate headers
      const estInfo = await sheets.spreadsheets.get({ spreadsheetId: estimateId });
      const sheetId = estInfo.data.sheets![0].properties!.sheetId!;
      await sheets.spreadsheets.batchUpdate({
        spreadsheetId: estimateId,
        requestBody: {
          requests: [
            { updateSheetProperties: { properties: { sheetId, title: "receipts" }, fields: "title" } },
          ],
        },
      });
      await sheets.spreadsheets.values.update({
        spreadsheetId: estimateId,
        range: "receipts!A1:E1",
        valueInputOption: "RAW",
        requestBody: { values: [["Дата", "Сумма", "Что куплено", "Магазин", "Ссылка на фото"]] },
      });

      // Write URLs back to Bitrix
      if (driveUrlField && sheetsUrlField) {
        await bitrixCall("crm.deal.update", {
          ID: deal.ID,
          FIELDS: { [driveUrlField]: driveUrl, [sheetsUrlField]: sheetsUrl },
        });
        console.log(`    URLs written back to Bitrix`);
      }

      console.log(`    Drive: ${driveUrl}`);
      console.log(`    Sheets: ${sheetsUrl}`);
    } else {
      console.log(`    Already has URLs`);
    }

    // Collect project data
    const today = new Date().toISOString().split("T")[0];
    projectsData.push([projectName, driveUrl, sheetsUrl, "active", today]);

    // Collect workers from employee fields
    for (const field of employeeFields) {
      const userId = deal[field.code];
      if (!userId) continue;
      const uid = String(userId).replace("user_", "");
      const user = userMap.get(uid);
      if (!user) continue;

      const name = `${user.NAME || ""} ${user.LAST_NAME || ""}`.trim();
      const tgId = user[telegramIdField] || "";
      const role = field.label.toLowerCase().includes("прораб") ? "foreman"
        : field.label.toLowerCase().includes("менеджер") || field.label.toLowerCase().includes("manager") ? "pm"
        : field.label.toLowerCase().includes("сметчик") || field.label.toLowerCase().includes("estimat") ? "estimator"
        : field.label.toLowerCase().includes("продав") || field.label.toLowerCase().includes("sales") ? "sales"
        : "other";

      // Worker registry
      if (!workersMap.has(uid)) {
        workersMap.set(uid, { bitrixId: uid, telegramId: tgId, name, role });
      }

      // Project access
      accessData.push([projectName, name, role, uid]);
      console.log(`    Worker: ${name} (${role}, tg: ${tgId || "?"})`);
    }
  }

  // Step 5: Write to Google Sheets
  console.log("\n--- Step 5: Write to Google Sheets ---");

  if (PROJECTS_SPREADSHEET_ID) {
    // Clear and write projects
    await sheets.spreadsheets.values.clear({
      spreadsheetId: PROJECTS_SPREADSHEET_ID, range: "projects!A2:E1000",
    });
    if (projectsData.length > 0) {
      await sheets.spreadsheets.values.append({
        spreadsheetId: PROJECTS_SPREADSHEET_ID, range: "projects!A:E",
        valueInputOption: "RAW",
        requestBody: { values: projectsData },
      });
    }
    console.log(`  Projects: ${projectsData.length} rows written`);
  }

  if (WORKERS_SPREADSHEET_ID) {
    // Workers
    const workersData = Array.from(workersMap.values()).map(w => [w.bitrixId, w.telegramId, w.name, w.role]);
    await sheets.spreadsheets.values.clear({
      spreadsheetId: WORKERS_SPREADSHEET_ID, range: "workers!A2:D1000",
    });
    if (workersData.length > 0) {
      await sheets.spreadsheets.values.append({
        spreadsheetId: WORKERS_SPREADSHEET_ID, range: "workers!A:D",
        valueInputOption: "RAW",
        requestBody: { values: workersData },
      });
    }
    console.log(`  Workers: ${workersData.length} rows written`);

    // Project access
    await sheets.spreadsheets.values.clear({
      spreadsheetId: WORKERS_SPREADSHEET_ID, range: "project_access!A2:D1000",
    });
    if (accessData.length > 0) {
      await sheets.spreadsheets.values.append({
        spreadsheetId: WORKERS_SPREADSHEET_ID, range: "project_access!A:D",
        valueInputOption: "RAW",
        requestBody: { values: accessData },
      });
    }
    console.log(`  Project access: ${accessData.length} rows written`);
  }

  // Step 6: Generate .env
  console.log("\n--- Step 6: Generated .env ---");
  const envContent = `TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN}
TELEGRAM_WEBHOOK_SECRET=${crypto.randomUUID()}
GOOGLE_SERVICE_ACCOUNT_JSON=./service-account.json
WORKERS_SPREADSHEET_ID=${WORKERS_SPREADSHEET_ID}
PROJECTS_SPREADSHEET_ID=${PROJECTS_SPREADSHEET_ID}
ESTIMATES_FOLDER_ID=${PROJECTS_PARENT_FOLDER_ID}
SHARED_DRIVE_ID=${SHARED_DRIVE_ID}
PROJECTS_PARENT_FOLDER_ID=${PROJECTS_PARENT_FOLDER_ID}
WORKERS_SHEET_NAME=workers
ACCESS_SHEET_NAME=project_access
BOT_STATE_SHEET_NAME=bot_state
PROJECTS_SHEET_NAME=projects
APPS_SCRIPT_WEBHOOK_SECRET=${WEBHOOK_SECRET}
BITRIX_DRIVE_URL_FIELD=${driveUrlField}
BITRIX_SHEETS_URL_FIELD=${sheetsUrlField}
BITRIX_TELEGRAM_ID_FIELD=${telegramIdField}
BITRIX_ROLE_FIELD=
GCP_PROJECT_ID=
GCP_REGION=me-west1
FUNCTION_NAME=receipt-bot
`;
  console.log(envContent);

  // Step 7: Generate robot body
  console.log("--- Step 7: Robot request body (give to client) ---");
  const robotBody = {
    secret: WEBHOOK_SECRET,
    action: "upsert_project",
    project_name: "{{Название}}",
    deal_id: "{{ID}}",
    bitrix_webhook_url: `${BITRIX_WEBHOOK_URL}/`,
    google_drive_url: driveUrlField ? `{{${driveUrlField}}}` : "",
    google_sheets_url: sheetsUrlField ? `{{${sheetsUrlField}}}` : "",
    workers: employeeFields.map(f => ({
      worker_id: `{{${f.code}}}`,
      worker_name: "",
      role_in_project: f.label.toLowerCase().includes("прораб") ? "foreman"
        : f.label.toLowerCase().includes("менеджер") ? "pm"
        : f.label.toLowerCase().includes("сметчик") ? "estimator"
        : f.label.toLowerCase().includes("продав") ? "sales"
        : "other",
    })),
  };
  console.log(JSON.stringify(robotBody, null, 2));

  console.log("\n=== SETUP COMPLETE ===");
  console.log(`\nProjects synced: ${projectsData.length}`);
  console.log(`Workers synced: ${workersMap.size}`);
  console.log(`Access bindings: ${accessData.length}`);
}

main().catch((err) => {
  console.error("Setup failed:", err.message || err);
  if (err.response) console.error("Response:", JSON.stringify(err.response.data, null, 2));
  process.exit(1);
});
