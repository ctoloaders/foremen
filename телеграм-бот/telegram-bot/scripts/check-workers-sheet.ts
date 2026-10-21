import { google } from "googleapis";
import { config } from "../src/config.js";

function auth() {
  const o: any = { credentials: config.google.serviceAccountKey, scopes: ["https://www.googleapis.com/auth/spreadsheets"] };
  if (config.google.impersonateEmail) o.clientOptions = { subject: config.google.impersonateEmail };
  return new google.auth.GoogleAuth(o);
}
const s = google.sheets({ version: "v4", auth: auth() });

const r = await s.spreadsheets.values.get({
  spreadsheetId: config.google.workersSpreadsheetId,
  range: `${config.google.workersSheetName}!A1:H12`,
});
const rows = r.data.values || [];
console.log("sheet:", config.google.workersSheetName, "rows:", rows.length);
rows.forEach((row, i) => console.log(`row ${i + 1}:`, JSON.stringify(row)));
