/**
 * Cloud Function: webhook receiver for Bitrix24 automation.
 * Handles upsert_project, upsert_worker, remove_worker actions.
 *
 * Key feature: when upsert_project is called WITHOUT google_drive_url/google_sheets_url,
 * the webhook automatically creates a folder + estimate spreadsheet in Shared Drive
 * and returns the URLs in the response for Bitrix24 to fill back into the project fields.
 */

import { google } from "googleapis";
import { config } from "./config.js";
import { logger } from "./utils/logger.js";

// Shared Drive ID (Foremen tets)
const SHARED_DRIVE_ID = "0AHkU6n74cG-CUk9PVA";
// Parent folder for all project folders inside the Shared Drive
const PROJECTS_PARENT_FOLDER_ID = "12Q66EYWWsgjRtT2-8inJ91JtVctfsvjt"; // "Проекты" folder

function getAuth() {
  return new google.auth.GoogleAuth({
    credentials: config.google.serviceAccountKey as any,
    scopes: [
      "https://www.googleapis.com/auth/spreadsheets",
      "https://www.googleapis.com/auth/drive",
    ],
  });
}

function getSheets() {
  return google.sheets({ version: "v4", auth: getAuth() });
}

function getDrive() {
  return google.drive({ version: "v3", auth: getAuth() });
}

interface WebhookRequest {
  secret: string;
  action: string;
  // upsert_project
  project_name?: string;
  google_drive_url?: string;
  google_sheets_url?: string;
  workers?: { worker_name: string; role_in_project: string }[];
  // upsert_worker
  bitrix_user_id?: string;
  worker_name?: string;
  role?: string;
  telegram_id?: number | string;
}

interface WebhookResponse {
  status: string;
  message: string;
  google_drive_url?: string;
  google_sheets_url?: string;
}

export async function bitrixWebhook(req: any, res: any): Promise<void> {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ status: "error", message: "method not allowed" });
      return;
    }

    const data: WebhookRequest = req.body;

    // Validate secret
    if (data.secret !== config.appsScript.webhookSecret) {
      res.status(401).json({ status: "error", message: "invalid secret" });
      return;
    }

    let result: WebhookResponse;

    switch (data.action) {
      case "upsert_project":
        result = await handleUpsertProject(data);
        break;
      case "upsert_worker":
        result = await handleUpsertWorker(data);
        break;
      case "remove_worker":
        result = await handleRemoveWorker(data);
        break;
      default:
        res.status(400).json({ status: "error", message: `unknown action: ${data.action}` });
        return;
    }

    logger.info("Webhook processed", { action: data.action, result: result.message });
    res.status(200).json(result);
  } catch (err: any) {
    logger.error("Webhook error", { error: err.message });
    res.status(500).json({ status: "error", message: "internal error" });
  }
}

// --- Action handlers ---

async function handleUpsertProject(data: WebhookRequest): Promise<WebhookResponse> {
  if (!data.project_name) {
    return { status: "error", message: "missing field: project_name" };
  }

  const sheets = getSheets();
  const drive = getDrive();

  let driveUrl = data.google_drive_url || "";
  let sheetsUrl = data.google_sheets_url || "";
  let resourcesCreated = false;

  // If Drive URL or Sheets URL are missing — create resources automatically
  if (!driveUrl || !sheetsUrl) {
    logger.info("Creating project resources", { project: data.project_name });

    // 1. Create project folder in Shared Drive under "Проекты"
    const folder = await drive.files.create({
      requestBody: {
        name: `${data.project_name} — Чеки`,
        mimeType: "application/vnd.google-apps.folder",
        parents: [PROJECTS_PARENT_FOLDER_ID],
      },
      supportsAllDrives: true,
      fields: "id,webViewLink",
    });
    driveUrl = `https://drive.google.com/drive/folders/${folder.data.id}`;

    // 2. Create estimate spreadsheet inside that folder
    const estimate = await drive.files.create({
      requestBody: {
        name: `${data.project_name} — Смета`,
        mimeType: "application/vnd.google-apps.spreadsheet",
        parents: [folder.data.id!],
      },
      supportsAllDrives: true,
      fields: "id,webViewLink",
    });
    const estimateId = estimate.data.id!;
    sheetsUrl = `https://docs.google.com/spreadsheets/d/${estimateId}/edit`;

    // 3. Set up estimate sheet headers
    // Rename default sheet to "receipts" and add headers
    const estInfo = await sheets.spreadsheets.get({ spreadsheetId: estimateId });
    const defaultSheetId = estInfo.data.sheets![0].properties!.sheetId!;

    await sheets.spreadsheets.batchUpdate({
      spreadsheetId: estimateId,
      requestBody: {
        requests: [
          {
            updateSheetProperties: {
              properties: { sheetId: defaultSheetId, title: "receipts" },
              fields: "title",
            },
          },
          {
            repeatCell: {
              range: { sheetId: defaultSheetId, startRowIndex: 0, endRowIndex: 1 },
              cell: {
                userEnteredFormat: {
                  textFormat: { bold: true },
                  backgroundColor: { red: 0.93, green: 0.93, blue: 0.93 },
                },
              },
              fields: "userEnteredFormat(textFormat,backgroundColor)",
            },
          },
          {
            updateSheetProperties: {
              properties: { sheetId: defaultSheetId, gridProperties: { frozenRowCount: 1 } },
              fields: "gridProperties.frozenRowCount",
            },
          },
        ],
      },
    });

    await sheets.spreadsheets.values.update({
      spreadsheetId: estimateId,
      range: "receipts!A1:E1",
      valueInputOption: "RAW",
      requestBody: {
        values: [["Дата", "Сумма", "Что куплено", "Магазин", "Ссылка на фото"]],
      },
    });

    resourcesCreated = true;
    logger.info("Project resources created", {
      project: data.project_name,
      driveUrl,
      sheetsUrl,
    });
  }

  // Validate URLs
  if (!driveUrl.startsWith("https://") || !sheetsUrl.startsWith("https://")) {
    return { status: "error", message: "invalid URL (must start with https://)" };
  }

  // Upsert project in registry
  const projRes = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.projectsSpreadsheetId,
    range: `${config.google.projectsSheetName}!A2:A`,
  });
  const projRows = projRes.data.values || [];
  const projIndex = projRows.findIndex(r => r[0] === data.project_name);
  const today = new Date().toISOString().split("T")[0];
  const row = [data.project_name!, driveUrl, sheetsUrl, "active", today];

  if (projIndex >= 0) {
    const rowNum = projIndex + 2;
    await sheets.spreadsheets.values.update({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A${rowNum}:E${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  } else {
    await sheets.spreadsheets.values.append({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A:E`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  }

  // Upsert project_access for each worker
  const workers = data.workers || [];
  if (workers.length > 0) {
    const accessRes = await sheets.spreadsheets.values.get({
      spreadsheetId: config.google.workersSpreadsheetId,
      range: `${config.google.accessSheetName}!A2:C`,
    });
    const accessRows = accessRes.data.values || [];

    for (const worker of workers) {
      if (!worker.worker_name || worker.worker_name.trim() === "") continue;

      const accessIndex = accessRows.findIndex(
        r => r[0] === data.project_name && r[1] === worker.worker_name
      );
      const accessRow = [data.project_name!, worker.worker_name, worker.role_in_project || "other"];

      if (accessIndex >= 0) {
        const rowNum = accessIndex + 2;
        await sheets.spreadsheets.values.update({
          spreadsheetId: config.google.workersSpreadsheetId,
          range: `${config.google.accessSheetName}!A${rowNum}:C${rowNum}`,
          valueInputOption: "RAW",
          requestBody: { values: [accessRow] },
        });
      } else {
        await sheets.spreadsheets.values.append({
          spreadsheetId: config.google.workersSpreadsheetId,
          range: `${config.google.accessSheetName}!A:C`,
          valueInputOption: "RAW",
          requestBody: { values: [accessRow] },
        });
        accessRows.push(accessRow);
      }
    }
  }

  const result: WebhookResponse = {
    status: "ok",
    message: resourcesCreated ? "project created with new resources" : "project upserted",
  };

  // Return URLs so Bitrix24 can fill them into the project fields
  if (resourcesCreated) {
    result.google_drive_url = driveUrl;
    result.google_sheets_url = sheetsUrl;
  }

  return result;
}

async function handleUpsertWorker(data: WebhookRequest): Promise<WebhookResponse> {
  const missing: string[] = [];
  if (!data.bitrix_user_id) missing.push("bitrix_user_id");
  if (!data.worker_name) missing.push("worker_name");
  if (!data.role) missing.push("role");
  if (!data.telegram_id) missing.push("telegram_id");
  if (missing.length > 0) {
    return { status: "error", message: `missing fields: ${missing.join(", ")}` };
  }

  const validRoles = ["foreman", "pm", "estimator", "sales", "admin", "other"];
  if (!validRoles.includes(data.role!)) {
    return { status: "error", message: `invalid role: ${data.role}` };
  }

  const tgId = parseInt(String(data.telegram_id));
  if (!tgId || tgId <= 0) {
    return { status: "ok", message: "telegram_id empty or invalid, skipped" };
  }

  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.workersSheetName}!A2:D`,
  });
  const rows = res.data.values || [];
  const workerIndex = rows.findIndex(r => String(r[0]) === String(data.bitrix_user_id));

  // Admin role is sticky
  let newRole = data.role!;
  if (workerIndex >= 0 && rows[workerIndex][3] === "admin" && newRole !== "admin") {
    newRole = "admin";
  }

  const row = [data.bitrix_user_id!, tgId, data.worker_name!, newRole];

  if (workerIndex >= 0) {
    const rowNum = workerIndex + 2;
    await sheets.spreadsheets.values.update({
      spreadsheetId: config.google.workersSpreadsheetId,
      range: `${config.google.workersSheetName}!A${rowNum}:D${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  } else {
    await sheets.spreadsheets.values.append({
      spreadsheetId: config.google.workersSpreadsheetId,
      range: `${config.google.workersSheetName}!A:D`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  }

  return { status: "ok", message: "worker upserted" };
}

async function handleRemoveWorker(data: WebhookRequest): Promise<WebhookResponse> {
  if (!data.bitrix_user_id) {
    return { status: "error", message: "missing field: bitrix_user_id" };
  }

  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.workersSheetName}!A2:A`,
  });
  const rows = res.data.values || [];
  const workerIndex = rows.findIndex(r => String(r[0]) === String(data.bitrix_user_id));

  if (workerIndex >= 0) {
    const rowNum = workerIndex + 2;
    // Clear the row (can't delete via Sheets API values, so we blank it)
    await sheets.spreadsheets.values.update({
      spreadsheetId: config.google.workersSpreadsheetId,
      range: `${config.google.workersSheetName}!A${rowNum}:D${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [["", "", "", ""]] },
    });
    return { status: "ok", message: "worker removed" };
  }

  return { status: "ok", message: "worker not found, skipped" };
}
