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

const res = await sheets.spreadsheets.values.get({
  spreadsheetId: config.google.workersSpreadsheetId,
  range: `${config.google.botStateSheetName}!A1:M`,
});

console.log("bot_state sheet contents:");
console.log(JSON.stringify(res.data.values, null, 2));
