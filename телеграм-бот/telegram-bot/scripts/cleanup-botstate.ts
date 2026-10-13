import { google } from "googleapis";
import { config } from "../src/config.js";

function auth() {
  const o: any = {
    credentials: config.google.serviceAccountKey,
    scopes: ["https://www.googleapis.com/auth/spreadsheets"],
  };
  if (config.google.impersonateEmail) o.clientOptions = { subject: config.google.impersonateEmail };
  return new google.auth.GoogleAuth(o);
}

const s = google.sheets({ version: "v4", auth: auth() });
const sheet = config.google.botStateSheetName;
const spreadsheetId = config.google.workersSpreadsheetId;

// 1. Ensure the header row spans all 18 columns (A–R) so the sheet structure matches the
//    state writer. The last four columns were added for the category-first flow.
const header = [
  "telegram_id", "step", "project_name", "project_drive_url", "project_sheets_url",
  "photo_file_ids", "sum", "description", "store_name", "updated_at",
  "ocr_description", "ocr_store_name", "ocr_gross_amount", "session_id",
  "is_material", "category_name", "category_extra_prompt", "category_detail",
];
await s.spreadsheets.values.update({
  spreadsheetId,
  range: `${sheet}!A1:R1`,
  valueInputOption: "RAW",
  requestBody: { values: [header] },
});
console.log("header updated (A1:R1, 18 cols)");

const res = await s.spreadsheets.values.get({ spreadsheetId, range: `${sheet}!A2:R200` });
const rows = res.data.values || [];
let cleared = 0;

for (let i = 0; i < rows.length; i++) {
  const r = rows[i] || [];
  const colA = (r[0] || "").toString().trim();
  const nonEmpty = r.some((c) => (c || "").toString().trim() !== "");
  // Corrupted/misaligned row: no telegram_id in column A but data present elsewhere.
  if (colA === "" && nonEmpty) {
    const rowNum = i + 2;
    await s.spreadsheets.values.update({
      spreadsheetId,
      range: `${sheet}!A${rowNum}:R${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [Array(18).fill("")] },
    });
    cleared++;
    console.log("cleared row " + rowNum);
  }
}
console.log("done, cleared=" + cleared);
