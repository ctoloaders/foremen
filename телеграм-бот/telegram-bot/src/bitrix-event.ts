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

// Managed roles synced from Bitrix (role "other" is managed via the bot and left untouched).
export const MANAGED_ROLES = ["pm", "foreman", "estimator", "sales"];
// Primary-role priority when one person holds several roles on a project (lower = higher priority).
const ROLE_PRIORITY: Record<string, number> = { pm: 0, foreman: 1, estimator: 2, sales: 3 };

export interface AccessWorker {
  userId: string;
  name: string;
  role: string;
}

/**
 * Collapses a worker list into the DESIRED project-access set: strictly one entry per unique
 * userId (one row per person per project), keeping a single primary role chosen by priority
 * (pm > foreman > estimator > sales). Workers whose role is not managed (e.g. "other") are
 * dropped. Pure function — unit tested.
 */
export function computeDesiredAccess(
  workers: AccessWorker[],
): Map<string, { name: string; role: string }> {
  const desired = new Map<string, { name: string; role: string }>();
  for (const w of workers) {
    if (!MANAGED_ROLES.includes(w.role)) continue;
    const existing = desired.get(w.userId);
    if (!existing || ROLE_PRIORITY[w.role] < ROLE_PRIORITY[existing.role]) {
      desired.set(w.userId, { name: w.name, role: w.role });
    }
  }
  return desired;
}

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
  const stageId = deal.STAGE_ID || "";

  // Determine status based on stage
  const CLOSED_STAGES = ["WON", "LOSE", "APOLOGY", "UC_1OQS5K", "UC_7Y686T"];
  const projectStatus = CLOSED_STAGES.includes(stageId) ? "archived" : "active";

  logger.info("Deal fetched", { dealId, projectName, driveUrl: !!driveUrl, sheetsUrl: !!sheetsUrl, stageId, projectStatus });

  // Skip if URLs missing (only for active projects)
  if (projectStatus === "active" && (!driveUrl || !sheetsUrl)) {
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

  // 1. Upsert project in registry — matched by Bitrix deal id (column F), so a renamed deal
  //    updates the existing row instead of creating a duplicate. For legacy rows with no deal
  //    id yet, fall back to matching by name and backfill the deal id on write.
  const projRes = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.projectsSpreadsheetId,
    range: `${config.google.projectsSheetName}!A2:F`,
  });
  const projRows = projRes.data.values || [];
  // Prefer deal-id match (column F = index 5); fall back to name match for un-backfilled rows.
  let projIndex = projRows.findIndex(r => (r[5] || "").toString().trim() === dealId);
  if (projIndex < 0) {
    projIndex = projRows.findIndex(r => !((r[5] || "").toString().trim()) && r[0] === projectName);
  }
  // A..F: name, drive, sheets, status, date_added, deal_id
  const projRow = [projectName, driveUrl, sheetsUrl, projectStatus, today, dealId];

  if (projIndex >= 0) {
    const rowNum = projIndex + 2;
    await sheets.spreadsheets.values.update({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A${rowNum}:F${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [projRow] },
    });
  } else {
    await sheets.spreadsheets.values.append({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A:F`,
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
    range: `${config.google.accessSheetName}!A2:E`,
  });
  const accessRows = accessRes.data.values || [];

  // --- 2a. Upsert each worker into the workers registry (keyed by userId) ---
  for (const worker of workers) {
    let tgId = "";
    const userRes2 = await bitrixCall("user.get", { ID: worker.userId });
    if (userRes2.result && userRes2.result[0]) {
      tgId = userRes2.result[0][TELEGRAM_ID_FIELD] || "";
    }

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
  }

  // --- 2b. Reconcile project_access: STRICTLY one row per (project, person) ---
  // This runs ONCE (not per-worker). The previous per-worker loop with appends could create
  // duplicate rows for the same person on the same project (and a person with multiple Bitrix
  // roles got one row per role). We now collapse to a single row per person, pick a primary
  // role deterministically, remove rows no longer assigned, and de-duplicate any pre-existing
  // duplicates by blanking the extras. Role "other" is left untouched (managed via the bot).
  // Desired set: one entry per unique userId among managed-role Bitrix workers, primary role
  // chosen by priority (pm > foreman > estimator > sales).
  const desiredByUser = computeDesiredAccess(workers);

  // Current managed-role access rows for THIS project, with their sheet row indices. Matched by
  // deal id (column E = index 4); legacy rows without a deal id fall back to name match. This
  // keeps rename handling correct: renaming the deal still finds the person's existing rows.
  const currentManaged = accessRows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => {
      if (!MANAGED_ROLES.includes(row[2] || "")) return false;
      const rowDealId = (row[4] || "").toString().trim();
      return rowDealId ? rowDealId === dealId : row[0] === projectName;
    });

  const seenUsers = new Set<string>();
  const blankRow = ["", "", "", "", ""];

  for (const { row, index } of currentManaged) {
    const rowNum = index + 2;
    const userId = String(row[3] || "");
    const desired = desiredByUser.get(userId);

    if (!desired || seenUsers.has(userId)) {
      // No longer assigned, or a duplicate of a person we already kept → blank it.
      await sheets.spreadsheets.values.update({
        spreadsheetId: config.google.workersSpreadsheetId,
        range: `${config.google.accessSheetName}!A${rowNum}:E${rowNum}`,
        valueInputOption: "RAW",
        requestBody: { values: [blankRow] },
      });
      logger.info("Removed project access (unassigned/duplicate)", { dealId, project: projectName, userId, role: row[2] });
      continue;
    }

    // Keep exactly one row for this user; rewrite it so name/role AND project_name/deal_id are
    // kept in sync (handles rename + deal-id backfill on legacy rows).
    seenUsers.add(userId);
    const needsUpdate =
      row[0] !== projectName ||
      row[1] !== desired.name ||
      row[2] !== desired.role ||
      (row[4] || "").toString().trim() !== dealId;
    if (needsUpdate) {
      await sheets.spreadsheets.values.update({
        spreadsheetId: config.google.workersSpreadsheetId,
        range: `${config.google.accessSheetName}!A${rowNum}:E${rowNum}`,
        valueInputOption: "RAW",
        requestBody: { values: [[projectName, desired.name, desired.role, userId, dealId]] },
      });
      logger.info("Updated project access", { dealId, project: projectName, userId, role: desired.role });
    }
  }

  // Append rows for desired users that had no existing row.
  for (const [userId, desired] of desiredByUser) {
    if (seenUsers.has(userId)) continue;
    await sheets.spreadsheets.values.append({
      spreadsheetId: config.google.workersSpreadsheetId,
      range: `${config.google.accessSheetName}!A:E`,
      valueInputOption: "RAW",
      requestBody: { values: [[projectName, desired.name, desired.role, userId, dealId]] },
    });
    seenUsers.add(userId);
    logger.info("Added project access", { dealId, project: projectName, userId, role: desired.role });
  }

  logger.info("Bitrix event processed", { dealId, projectName, workersCount: workers.length });
  return { status: "ok", message: "project synced", projectName, workers: workers.length };
}
