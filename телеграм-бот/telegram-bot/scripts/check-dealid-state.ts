import { google } from "googleapis";
import { config } from "../src/config.js";

function auth() {
  const o: any = { credentials: config.google.serviceAccountKey, scopes: ["https://www.googleapis.com/auth/spreadsheets"] };
  if (config.google.impersonateEmail) o.clientOptions = { subject: config.google.impersonateEmail };
  return new google.auth.GoogleAuth(o);
}
const s = google.sheets({ version: "v4", auth: auth() });

const p = await s.spreadsheets.values.get({ spreadsheetId: config.google.projectsSpreadsheetId, range: `${config.google.projectsSheetName}!A1:F200` });
const pr = p.data.values || [];
console.log("projects header F1 =", pr[0]?.[5]);
let pFilled = 0, pTotal = 0;
for (let i = 1; i < pr.length; i++) { if (!pr[i]?.[0]) continue; pTotal++; if ((pr[i][5] || "").toString().trim()) pFilled++; }
console.log(`projects: total=${pTotal} withDealId=${pFilled}`);

const a = await s.spreadsheets.values.get({ spreadsheetId: config.google.workersSpreadsheetId, range: `${config.google.accessSheetName}!A1:E3000` });
const ar = a.data.values || [];
console.log("access header E1 =", ar[0]?.[4]);
let aFilled = 0, aTotal = 0;
for (let i = 1; i < ar.length; i++) { if (!ar[i]?.[0]) continue; aTotal++; if ((ar[i][4] || "").toString().trim()) aFilled++; }
console.log(`access: nonEmpty=${aTotal} withDealId=${aFilled}`);
