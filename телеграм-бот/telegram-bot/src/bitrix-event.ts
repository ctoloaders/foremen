/**
 * Handler for Bitrix24 outgoing webhook from CRM robot.
 * Bitrix sends form-encoded data with deal ID.
 * We fetch all deal data + workers ourselves via Bitrix REST API.
 */

import { config } from "./config.js";
import { logger } from "./utils/logger.js";
import { google } from "googleapis";

const BITRIX_URL = (process.env.BITRIX_WEBHOOK_URL || "https://foremen.bitrix24.pl/rest/747/jqsr7yxpg9yqy1nw").replace(/\/$/, "");

const DRIVE_FIELD = config.bitrix.driveUrlField;
const SHEETS_FIELD = config.bitrix.sheetsUrlField;
const TELEGRAM_ID_FIELD = config.bitrix.telegramIdField;

// Employee fields on deal
const EMPLOYEE_FIELDS = [
  { code: "UF_CRM_1692271880", role: "foreman" },   // Brygadzista
  { code: "UF_CRM_1724145235", role: "estimator" }, // Kosztorysant
  { code: "UF_CRM_1758702568", role: "sales" },     // Продавец
];

async function bitrixCall(method: string, params: any = {}): Promise<any> {
  const res = await fetch(`${BITRIX_URL}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params),
  });
  return res.json();
}

function getAuth() {
  const opts: any = {
    credentials: config.google.serviceAccountKey as any,
    scopes: ["https://www.googleapis.com/auth/spreadsheets"],
  };
  if (config.google.impersonateEmail) {
    opts.clientOptions = { subject: config.google.impersonateEmail };
  }
  return new google.auth.GoogleAuth(opts);
}

function getSheets() {
  return google.sheets({ version: "v4", auth: getAuth() });
}

export async function handleBitrixEvent(rawBody: string): Promise<any> {
  logger.info("Bitrix event raw body", { body: rawBody.slice(0, 1000) });

  // Parse form-encoded or JSON body from Bitrix
  let dealId: string | null = null;

  // Try JSON first
  try {
    const json = JSON.parse(rawBody);
    dealId = json.data?.FIELDS?.ID || json.deal_id || json.ID || null;
  } catch {
    // Form-encoded: document_id[2]=DEAL_36 or data[FIELDS][ID]=36
    const params = new URLSearchParams(rawBody);
    
    // Try all known formats
    const docId = params.get("document_id[2]");  // "DEAL_36"
    if (docId) {
      dealId = docId.replace(/^DEAL_/i, "");
    }
    
    if (!dealId) dealId = params.get("data[FIELDS][ID]");
    
    // Fallback: find any number after DEAL_ or ID
    if (!dealId) {
      const match = rawBody.match(/DEAL_(\d+)/);
      if (match) dealId = match[1];
    }
    if (!dealId) {
      const match = rawBody.match(/\bID[=%]\s*(\d+)/);
      if (match) dealId = match[1];
    }
  }

  if (!dealId) {
    logger.error("Bitrix event: no deal_id found", { rawBody: rawBody.slice(0, 500) });
    return { status: "error", message: "no deal_id found in request" };
  }

  // Strip "DEAL_" prefix if present
  dealId = dealId.replace(/^DEAL_/i, "");

  logger.info("Bitrix event received", { dealId });

  // Fetch deal data from Bitrix
  const dealRes = await bitrixCall("crm.deal.get", { ID: dealId });
  if (!dealRes.result) {
    logger.error("Failed to fetch deal", { dealId, error: dealRes });
    return { status: "error", message: `failed to fetch deal ${dealId}` };
  }

  const deal = dealRes.result;
  const projectName = deal.TITLE;
  const driveUrl = deal[DRIVE_FIELD] || "";
  const sheetsUrl = deal[SHEETS_FIELD] || "";

  logger.info("Deal fetched", { dealId, projectName, driveUrl: !!driveUrl, sheetsUrl: !!sheetsUrl });

  // Skip if URLs missing
  if (!driveUrl || !sheetsUrl) {
    logger.info("Deal missing Drive/Sheets URLs, skipping", { dealId, projectName });
    return { status: "ok", message: "skipped (missing URLs)" };
  }

  // Fetch workers
  const workers: { userId: string; name: string; role: string }[] = [];
  
  for (const field of EMPLOYEE_FIELDS) {
    const userId = deal[field.code];
    if (!userId) continue;
    const uid = String(userId).replace("user_", "");
    
    // Fetch user data
    const userRes = await bitrixCall("user.get", { ID: uid });
    if (userRes.result && userRes.result[0]) {
      const user = userRes.result[0];
      workers.push({
        userId: uid,
        name: `${user.NAME || ""} ${user.LAST_NAME || ""}`.trim(),
        role: field.role,
      });
    }
  }

  // Also add ASSIGNED_BY_ID as PM
  if (deal.ASSIGNED_BY_ID) {
    const uid = String(deal.ASSIGNED_BY_ID);
    const userRes = await bitrixCall("user.get", { ID: uid });
    if (userRes.result && userRes.result[0]) {
      const user = userRes.result[0];
      workers.push({
        userId: uid,
        name: `${user.NAME || ""} ${user.LAST_NAME || ""}`.trim(),
        role: "pm",
      });
    }
  }

  logger.info("Workers resolved", { dealId, workers: workers.map(w => `${w.name}(${w.role})`) });

  // Write to registries
  const sheets = getSheets();
  const today = new Date().toISOString().split("T")[0];

  // 1. Upsert project in registry
  const projRes = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.projectsSpreadsheetId,
    range: `${config.google.projectsSheetName}!A2:A`,
  });
  const projRows = projRes.data.values || [];
  const projIndex = projRows.findIndex(r => r[0] === projectName);
  const projRow = [projectName, driveUrl, sheetsUrl, "active", today];

  if (projIndex >= 0) {
    const rowNum = projIndex + 2;
    await sheets.spreadsheets.values.update({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A${rowNum}:E${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [projRow] },
    });
  } else {
    await sheets.spreadsheets.values.append({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A:E`,
      valueInputOption: "RAW",
      requestBody: { values: [projRow] },
    });
  }

  // 2. Upsert workers + project_access
  const workersRes = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.workersSheetName}!A2:D`,
  });
  const workersRows = workersRes.data.values || [];

  const accessRes = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.accessSheetName}!A2:D`,
  });
  const accessRows = accessRes.data.values || [];

  for (const worker of workers) {
    // Fetch TG ID
    let tgId = "";
    const userRes2 = await bitrixCall("user.get", { ID: worker.userId });
    if (userRes2.result && userRes2.result[0]) {
      tgId = userRes2.result[0][TELEGRAM_ID_FIELD] || "";
    }

    // Upsert worker
    const wIndex = workersRows.findIndex(r => String(r[0]) === worker.userId);
    const wRow = [worker.userId, tgId, worker.name, worker.role];
    if (wIndex >= 0) {
      // Keep admin role if already set
      if (workersRows[wIndex][3] === "admin") wRow[3] = "admin";
      const rowNum = wIndex + 2;
      await sheets.spreadsheets.values.update({
        spreadsheetId: config.google.workersSpreadsheetId,
        range: `${config.google.workersSheetName}!A${rowNum}:D${rowNum}`,
        valueInputOption: "RAW",
        requestBody: { values: [wRow] },
      });
    } else {
      await sheets.spreadsheets.values.append({
        spreadsheetId: config.google.workersSpreadsheetId,
        range: `${config.google.workersSheetName}!A:D`,
        valueInputOption: "RAW",
        requestBody: { values: [wRow] },
      });
      workersRows.push(wRow);
    }

    // Upsert project_access
    const aIndex = accessRows.findIndex(r => r[0] === projectName && r[3] === worker.userId);
    const aRow = [projectName, worker.name, worker.role, worker.userId];
    if (aIndex >= 0) {
      const rowNum = aIndex + 2;
      await sheets.spreadsheets.values.update({
        spreadsheetId: config.google.workersSpreadsheetId,
        range: `${config.google.accessSheetName}!A${rowNum}:D${rowNum}`,
        valueInputOption: "RAW",
        requestBody: { values: [aRow] },
      });
    } else {
      await sheets.spreadsheets.values.append({
        spreadsheetId: config.google.workersSpreadsheetId,
        range: `${config.google.accessSheetName}!A:D`,
        valueInputOption: "RAW",
        requestBody: { values: [aRow] },
      });
      accessRows.push(aRow);
    }
  }

  logger.info("Bitrix event processed", { dealId, projectName, workersCount: workers.length });
  return { status: "ok", message: "project synced", projectName, workers: workers.length };
}
