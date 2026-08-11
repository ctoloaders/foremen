/**
 * Sync registry: for projects already in our registry,
 * update sheetsUrl from Bitrix (the file with "Mat. budowlane" sheet).
 * Also reformat registry to 5 columns: project_name, drive_url, sheets_url, status, date
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
const PROJECTS_SPREADSHEET_ID = "1GxUyUoecvw4NEldj5I_LvQ2-OiVg8V5HW5rkJheU36U";

const DRIVE_FIELD = "UF_CRM_639869B45A2A9";
const SHEETS_FIELD = "UF_CRM_1771587767";

const keyFile = JSON.parse(readFileSync(SA_KEY_PATH, "utf-8"));
const auth = new google.auth.GoogleAuth({
  credentials: keyFile,
  scopes: ["https://www.googleapis.com/auth/spreadsheets"],
  clientOptions: { subject: IMPERSONATE_EMAIL },
});
const sheets = google.sheets({ version: "v4", auth });

async function bitrixGetAll(method: string, params: any = {}): Promise<any[]> {
  let all: any[] = [];
  let start = 0;
  while (true) {
    const res = await fetch(`${BITRIX_URL}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...params, start }),
    });
    const data = await res.json() as any;
    if (data.result) all = all.concat(data.result);
    if (!data.next) break;
    start = data.next;
  }
  return all;
}

async function main() {
  console.log("Reading current registry...");
  const regRes = await sheets.spreadsheets.values.get({
    spreadsheetId: PROJECTS_SPREADSHEET_ID,
    range: "projects!A2:F",
  });
  const registryRows = regRes.data.values || [];
  console.log(`Registry has ${registryRows.length} projects`);

  // Get project names in registry
  const registryNames = new Set(registryRows.map(r => r[0]));

  // Get all deals from Bitrix with both fields
  console.log("Fetching deals from Bitrix...");
  const deals = await bitrixGetAll("crm.deal.list", {
    filter: {},
    select: ["ID", "TITLE", DRIVE_FIELD, SHEETS_FIELD],
  });

  // Match deals to registry by TITLE
  let updated = 0;
  const newRows: any[] = [];

  for (const row of registryRows) {
    const projectName = row[0];
    const deal = deals.find(d => d.TITLE === projectName);

    if (deal && deal[SHEETS_FIELD]) {
      // Use Bitrix sheets URL (the one with Mat. budowlane)
      newRows.push([projectName, deal[DRIVE_FIELD] || row[1], deal[SHEETS_FIELD], "active", row[4] || row[3] || ""]);
      if (row[2] !== deal[SHEETS_FIELD]) {
        updated++;
        console.log(`  Updated: "${projectName}" sheets → ${deal[SHEETS_FIELD]}`);
      }
    } else {
      // Keep as-is (5 columns)
      newRows.push([projectName, row[1] || "", row[2] || "", row[3] || "active", row[4] || ""]);
    }
  }

  // Clear and rewrite
  console.log(`\nRewriting registry (${newRows.length} rows, ${updated} updated)...`);
  await sheets.spreadsheets.values.clear({
    spreadsheetId: PROJECTS_SPREADSHEET_ID,
    range: "projects!A2:F1000",
  });
  if (newRows.length > 0) {
    await sheets.spreadsheets.values.update({
      spreadsheetId: PROJECTS_SPREADSHEET_ID,
      range: `projects!A2:E${newRows.length + 1}`,
      valueInputOption: "RAW",
      requestBody: { values: newRows },
    });
  }

  console.log("Done!");
}

main().catch(e => { console.error("Error:", e.message); process.exit(1); });
