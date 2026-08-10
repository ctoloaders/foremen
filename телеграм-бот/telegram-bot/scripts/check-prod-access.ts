import { google } from "googleapis";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const KEY_PATH = resolve(__dirname, "../../starry-tracker-505110-s3-326364be8f95.json");
const FOLDER_ID = "1OaJZL5HjSaRgXNfhwlszfhCPtFwZoige";
const IMPERSONATE_EMAIL = "info@foremen.eu";

const key = JSON.parse(readFileSync(KEY_PATH, "utf-8"));

// Step 1: Direct SA access
console.log("=== Step 1: Check direct SA access ===");
const auth1 = new google.auth.GoogleAuth({
  credentials: key,
  scopes: ["https://www.googleapis.com/auth/drive", "https://www.googleapis.com/auth/spreadsheets"],
});
const drive1 = google.drive({ version: "v3", auth: auth1 });

try {
  const res = await drive1.files.list({
    q: `'${FOLDER_ID}' in parents`,
    fields: "files(id,name,mimeType)",
    pageSize: 5,
  });
  console.log("Direct access OK. Files:", res.data.files?.length);
  res.data.files?.forEach(f => console.log(`  ${f.name} (${f.mimeType})`));
} catch (e: any) {
  console.log("Direct access FAILED:", e.message);
}

// Step 2: Impersonation
console.log("\n=== Step 2: Check impersonation ===");
const auth2 = new google.auth.GoogleAuth({
  credentials: key,
  scopes: ["https://www.googleapis.com/auth/drive", "https://www.googleapis.com/auth/spreadsheets"],
  clientOptions: { subject: IMPERSONATE_EMAIL },
});
const drive2 = google.drive({ version: "v3", auth: auth2 });

try {
  const res = await drive2.files.list({
    q: `'${FOLDER_ID}' in parents`,
    fields: "files(id,name,mimeType)",
    pageSize: 5,
  });
  console.log(`Impersonation (${IMPERSONATE_EMAIL}) OK. Files:`, res.data.files?.length);
  res.data.files?.forEach(f => console.log(`  ${f.name} (${f.mimeType})`));
} catch (e: any) {
  console.log("Impersonation FAILED:", e.message);
}

// Step 3: Try creating a spreadsheet in the folder (impersonation)
console.log("\n=== Step 3: Create test file (impersonation) ===");
try {
  const sheets = google.sheets({ version: "v4", auth: auth2 });
  const testFile = await drive2.files.create({
    requestBody: {
      name: "_test_delete_me",
      mimeType: "application/vnd.google-apps.spreadsheet",
      parents: [FOLDER_ID],
    },
    fields: "id,name",
  });
  console.log("Created:", testFile.data.id, testFile.data.name);
  await drive2.files.delete({ fileId: testFile.data.id! });
  console.log("Deleted. Write access OK!");
} catch (e: any) {
  console.log("Create FAILED:", e.message);
  
  // Try without impersonation
  console.log("\n=== Step 3b: Create test file (direct SA) ===");
  try {
    const testFile = await drive1.files.create({
      requestBody: {
        name: "_test_delete_me",
        mimeType: "application/vnd.google-apps.spreadsheet",
        parents: [FOLDER_ID],
      },
      fields: "id,name",
    });
    console.log("Created (direct SA):", testFile.data.id);
    await drive1.files.delete({ fileId: testFile.data.id! });
    console.log("Deleted. Direct SA write access OK!");
  } catch (e2: any) {
    console.log("Direct SA create also FAILED:", e2.message);
  }
}
