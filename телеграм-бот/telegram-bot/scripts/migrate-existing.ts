/**
 * Migrate existing deals:
 * 1. Filter: not in excluded stages, both Drive + Sheets fields filled
 * 2. In each deal's Drive folder: copy template spreadsheet as "Чеки" file
 * 3. Add to projects registry (with receipts file link)
 * 4. Sync workers into workers registry + project_access
 * 
 * Does NOT modify Bitrix deal fields.
 */

import { google } from "googleapis";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";
import { config as dotenvConfig } from "dotenv";

const __dirname = dirname(fileURLToPath(import.meta.url));
dotenvConfig({ path: resolve(__dirname, "../.env.production"), override: true });

const BITRIX_URL = "https://foremen.bitrix24.pl/rest/747/jqsr7yxpg9yqy1nw";
const SA_KEY_PATH = resolve(__dirname, "../../starry-tracker-505110-s3-326364be8f95.json");
const IMPERSONATE_EMAIL = "info@foremen.eu";

const WORKERS_SPREADSHEET_ID = "1X599awhaiMApbNA-ioHdhMmHKQWI4q1iV_rngqqQ-zs";
const PROJECTS_SPREADSHEET_ID = "1GxUyUoecvw4NEldj5I_LvQ2-OiVg8V5HW5rkJheU36U";

// Template to copy for receipts
const TEMPLATE_SPREADSHEET_ID = "1c-oaCdrq3FRU1kSm-kMz4WrCZIdht0tF7aEozr5KTgU";

// Bitrix field codes
const DRIVE_FIELD = "UF_CRM_639869B45A2A9";
const SHEETS_FIELD = "UF_CRM_1771587767";
const FOREMAN_FIELD = "UF_CRM_1692271880";
const ESTIMATOR_FIELD = "UF_CRM_1724145235";
const SALES_FIELD = "UF_CRM_1758702568";
const TELEGRAM_ID_FIELD = "UF_USR_1786358822489";

// Excluded stages
const EXCLUDED_STAGES = ["UC_1OQS5K", "UC_7Y686T", "WON", "LOSE", "APOLOGY"];

// Google Auth
const keyFile = JSON.parse(readFileSync(SA_KEY_PATH, "utf-8"));
const auth = new google.auth.GoogleAuth({
  credentials: keyFile,
  scopes: ["https://www.googleapis.com/auth/spreadsheets", "https://www.googleapis.com/auth/drive"],
  clientOptions: { subject: IMPERSONATE_EMAIL },
});
const drive = google.drive({ version: "v3", auth });
const sheets = google.sheets({ version: "v4", auth });

// Bitrix helpers
async function bitrixCall(method: string, params: any = {}): Promise<any> {
  const res = await fetch(`${BITRIX_URL}/${method}`, {
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

function extractFolderId(url: string): string {
  const match = url.match(/\/folders\/([a-zA-Z0-9_-]+)/);
  if (!match) throw new Error(`Cannot extract folder ID from: ${url}`);
  return match[1];
}

async function main() {
  console.log("=== MIGRATE EXISTING DEALS ===\n");

  // Get all eligible deals
  console.log("Fetching eligible deals...");
  const allDeals = await bitrixGetAll("crm.deal.list", {
    filter: { "!STAGE_ID": EXCLUDED_STAGES },
    select: ["ID", "TITLE", "STAGE_ID", "ASSIGNED_BY_ID", DRIVE_FIELD, SHEETS_FIELD, FOREMAN_FIELD, ESTIMATOR_FIELD, SALES_FIELD],
  });

  const eligible = allDeals.filter(d => d[DRIVE_FIELD] && d[SHEETS_FIELD]);
  console.log(`Eligible deals: ${eligible.length}\n`);

  // Get all users
  console.log("Fetching users...");
  const users = await bitrixGetAll("user.get", { ACTIVE: true });
  const userMap = new Map<string, any>();
  for (const u of users) userMap.set(String(u.ID), u);
  console.log(`Users: ${users.length}\n`);

  // Process
  const projectsData: any[] = [];
  const accessData: any[] = [];
  const workersMap = new Map<string, any>();
  let created = 0;
  let errors = 0;

  for (const deal of eligible) {
    const title = deal.TITLE;
    const driveUrl = deal[DRIVE_FIELD];
    
    console.log(`[${created + 1}/${eligible.length}] "${title}" (#${deal.ID})`);

    // Extract folder ID from Drive URL
    let folderId: string;
    try {
      folderId = extractFolderId(driveUrl);
    } catch (e: any) {
      console.log(`  SKIP: ${e.message}`);
      errors++;
      continue;
    }

    // Copy template into the folder as receipts file
    let receiptsUrl: string;
    try {
      const copied = await drive.files.copy({
        fileId: TEMPLATE_SPREADSHEET_ID,
        requestBody: {
          name: `${title} — Чеки`,
          parents: [folderId],
        },
        fields: "id,webViewLink",
      });
      const receiptsId = copied.data.id!;
      receiptsUrl = `https://docs.google.com/spreadsheets/d/${receiptsId}/edit`;

      // Add "Чеки" sheet with headers
      const info = await sheets.spreadsheets.get({ spreadsheetId: receiptsId });
      const existingSheets = info.data.sheets?.map(s => s.properties?.title) || [];
      
      if (!existingSheets.includes("Чеки")) {
        await sheets.spreadsheets.batchUpdate({
          spreadsheetId: receiptsId,
          requestBody: {
            requests: [{ addSheet: { properties: { title: "Чеки" } } }],
          },
        });
      }

      // Get the "Чеки" sheet ID and set headers
      const updated = await sheets.spreadsheets.get({ spreadsheetId: receiptsId });
      const chekiSheet = updated.data.sheets?.find(s => s.properties?.title === "Чеки");
      if (chekiSheet) {
        const sheetId = chekiSheet.properties!.sheetId!;
        await sheets.spreadsheets.values.update({
          spreadsheetId: receiptsId,
          range: "Чеки!A1:F2",
          valueInputOption: "USER_ENTERED",
          requestBody: {
            values: [
              ["Дата", "Сумма", "Что куплено", "Магазин", "Ссылка на фото", "Кто добавил"],
              ["ИТОГО:", "=SUM(B3:B)", "", "", "", ""],
            ],
          },
        });
        await sheets.spreadsheets.batchUpdate({
          spreadsheetId: receiptsId,
          requestBody: {
            requests: [
              {
                repeatCell: {
                  range: { sheetId, startRowIndex: 0, endRowIndex: 2 },
                  cell: { userEnteredFormat: { textFormat: { bold: true } } },
                  fields: "userEnteredFormat.textFormat.bold",
                },
              },
              {
                repeatCell: {
                  range: { sheetId, startColumnIndex: 1, endColumnIndex: 2 },
                  cell: { userEnteredFormat: { numberFormat: { type: "CURRENCY", pattern: "#,##0.00 \"PLN\"" } } },
                  fields: "userEnteredFormat.numberFormat",
                },
              },
              {
                updateSheetProperties: {
                  properties: { sheetId, gridProperties: { frozenRowCount: 2 } },
                  fields: "gridProperties.frozenRowCount",
                },
              },
            ],
          },
        });
      }

      console.log(`  Receipts: ${receiptsUrl}`);
    } catch (e: any) {
      console.log(`  ERROR creating receipts file: ${e.message}`);
      errors++;
      continue;
    }

    // Add to projects registry
    const today = new Date().toISOString().split("T")[0];
    projectsData.push([title, driveUrl, receiptsUrl, "active", today]);

    // Collect workers
    const employeeFields = [
      { code: FOREMAN_FIELD, role: "foreman" },
      { code: ESTIMATOR_FIELD, role: "estimator" },
      { code: SALES_FIELD, role: "sales" },
    ];

    for (const field of employeeFields) {
      const userId = deal[field.code];
      if (!userId) continue;
      const uid = String(userId).replace("user_", "");
      const user = userMap.get(uid);
      if (!user) continue;

      const name = `${user.NAME || ""} ${user.LAST_NAME || ""}`.trim();
      const tgId = user[TELEGRAM_ID_FIELD] || "";

      if (!workersMap.has(uid)) {
        workersMap.set(uid, { bitrixId: uid, telegramId: tgId, name, role: field.role });
      }

      accessData.push([title, name, field.role, uid]);
    }

    // Also add ASSIGNED_BY_ID as PM
    if (deal.ASSIGNED_BY_ID) {
      const uid = String(deal.ASSIGNED_BY_ID);
      const user = userMap.get(uid);
      if (user) {
        const name = `${user.NAME || ""} ${user.LAST_NAME || ""}`.trim();
        const tgId = user[TELEGRAM_ID_FIELD] || "";
        if (!workersMap.has(uid)) {
          workersMap.set(uid, { bitrixId: uid, telegramId: tgId, name, role: "pm" });
        }
        accessData.push([title, name, "pm", uid]);
      }
    }

    created++;
    
    // Small delay to avoid rate limits
    await new Promise(r => setTimeout(r, 500));
  }

  // Write to Google Sheets
  console.log(`\n=== Writing to registries ===`);

  // Projects
  if (projectsData.length > 0) {
    await sheets.spreadsheets.values.append({
      spreadsheetId: PROJECTS_SPREADSHEET_ID,
      range: "projects!A:E",
      valueInputOption: "RAW",
      requestBody: { values: projectsData },
    });
    console.log(`Projects registry: ${projectsData.length} rows added`);
  }

  // Workers
  const workersData = Array.from(workersMap.values()).map(w => [w.bitrixId, w.telegramId, w.name, w.role]);
  if (workersData.length > 0) {
    await sheets.spreadsheets.values.append({
      spreadsheetId: WORKERS_SPREADSHEET_ID,
      range: "workers!A:D",
      valueInputOption: "RAW",
      requestBody: { values: workersData },
    });
    console.log(`Workers registry: ${workersData.length} rows added`);
  }

  // Project access
  if (accessData.length > 0) {
    await sheets.spreadsheets.values.append({
      spreadsheetId: WORKERS_SPREADSHEET_ID,
      range: "project_access!A:D",
      valueInputOption: "RAW",
      requestBody: { values: accessData },
    });
    console.log(`Project access: ${accessData.length} rows added`);
  }

  console.log(`\n=== DONE ===`);
  console.log(`Processed: ${created}`);
  console.log(`Errors: ${errors}`);
  console.log(`Workers: ${workersMap.size}`);
  console.log(`Access bindings: ${accessData.length}`);
}

main().catch(e => {
  console.error("FATAL:", e.message);
  process.exit(1);
});
