import { config } from "../src/config.js";
import { google } from "googleapis";

const opts: any = {
  credentials: config.google.serviceAccountKey as any,
  scopes: ["https://www.googleapis.com/auth/spreadsheets"],
};
if (config.google.impersonateEmail) {
  opts.clientOptions = { subject: config.google.impersonateEmail };
}
const auth = new google.auth.GoogleAuth(opts);
const sheets = google.sheets({ version: "v4", auth });

const spreadsheetId = config.google.workersSpreadsheetId;
const sheetName = config.google.botStateSheetName;

// Clear all data from row 2 onwards
await sheets.spreadsheets.values.clear({
  spreadsheetId,
  range: `${sheetName}!A2:Z100`,
});

console.log("Cleared bot_state sheet (rows 2-100)");

// Update header to match 13 columns (A-M)
await sheets.spreadsheets.values.update({
  spreadsheetId,
  range: `${sheetName}!A1:M1`,
  valueInputOption: "RAW",
  requestBody: {
    values: [[
      "telegram_id", "step", "project_name", "project_drive_url",
      "project_sheets_url", "photo_file_ids", "sum", "description",
      "store_name", "updated_at", "ocr_description", "ocr_store_name", "ocr_gross_amount"
    ]]
  }
});

console.log("Updated header to 13 columns (A-M)");
