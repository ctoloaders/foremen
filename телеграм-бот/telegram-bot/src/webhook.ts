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

// Shared Drive ID and parent folder from config
const SHARED_DRIVE_ID = config.google.sharedDriveId;
const PROJECTS_PARENT_FOLDER_ID = config.google.projectsParentFolderId;

function getAuth() {
  const opts: any = {
    credentials: config.google.serviceAccountKey as any,
    scopes: [
      "https://www.googleapis.com/auth/spreadsheets",
      "https://www.googleapis.com/auth/drive",
    ],
  };
  if (config.google.impersonateEmail) {
    opts.clientOptions = { subject: config.google.impersonateEmail };
  }
  return new google.auth.GoogleAuth(opts);
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
  deal_id?: string | number;
  bitrix_webhook_url?: string;
  google_drive_url?: string;
  google_sheets_url?: string;
  workers?: { worker_name: string; worker_id?: string; role_in_project: string }[];
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

  // Check if project already has URLs in our registry (deduplication)
  if (!driveUrl || !sheetsUrl) {
    const existingRes = await sheets.spreadsheets.values.get({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A2:D`,
    });
    const existingRows = existingRes.data.values || [];
    const existing = existingRows.find(r => r[0] === data.project_name);
    if (existing && existing[1] && existing[2]) {
      driveUrl = existing[1];
      sheetsUrl = existing[2];
      logger.info("Project already has resources in registry, skipping creation", { project: data.project_name });
    }
  }

  // If Drive URL or Sheets URL are STILL missing — create resources
  if (!driveUrl || !sheetsUrl) {
    logger.info("Creating project resources", { project: data.project_name });

    // 1. Create project folder
    const folder = await drive.files.create({
      requestBody: {
        name: `${data.project_name}`,
        mimeType: "application/vnd.google-apps.folder",
        parents: [PROJECTS_PARENT_FOLDER_ID],
      },
      fields: "id",
    });
    const folderId = folder.data.id!;
    driveUrl = `https://drive.google.com/drive/folders/${folderId}`;

    // 2. Copy template spreadsheet into the folder (kosztorys/estimate)
    const templateId = config.google.templateSpreadsheetId;
    if (templateId) {
      const copied = await drive.files.copy({
        fileId: templateId,
        requestBody: {
          name: `${data.project_name} — Kosztorys`,
          parents: [folderId],
        },
        fields: "id",
      });
      sheetsUrl = `https://docs.google.com/spreadsheets/d/${copied.data.id}/edit`;
    } else {
      // Fallback: create empty spreadsheet
      const est = await drive.files.create({
        requestBody: {
          name: `${data.project_name} — Kosztorys`,
          mimeType: "application/vnd.google-apps.spreadsheet",
          parents: [folderId],
        },
        fields: "id",
      });
      sheetsUrl = `https://docs.google.com/spreadsheets/d/${est.data.id}/edit`;
    }

    // 3. Create separate receipts file ("Чеки")
    const receiptsFile = await drive.files.create({
      requestBody: {
        name: `${data.project_name} — Чеки`,
        mimeType: "application/vnd.google-apps.spreadsheet",
        parents: [folderId],
      },
      fields: "id",
    });
    const receiptsId = receiptsFile.data.id!;
    const receiptsUrl = `https://docs.google.com/spreadsheets/d/${receiptsId}/edit`;

    // Setup receipts file: rename sheet, headers, SUM, PLN format
    const rInfo = await sheets.spreadsheets.get({ spreadsheetId: receiptsId });
    const rSheetId = rInfo.data.sheets![0].properties!.sheetId!;

    await sheets.spreadsheets.batchUpdate({
      spreadsheetId: receiptsId,
      requestBody: {
        requests: [
          { updateSheetProperties: { properties: { sheetId: rSheetId, title: config.google.receiptsSheetName }, fields: "title" } },
          { repeatCell: { range: { sheetId: rSheetId, startRowIndex: 0, endRowIndex: 2 }, cell: { userEnteredFormat: { textFormat: { bold: true } } }, fields: "userEnteredFormat.textFormat.bold" } },
          { repeatCell: { range: { sheetId: rSheetId, startColumnIndex: 1, endColumnIndex: 2 }, cell: { userEnteredFormat: { numberFormat: { type: "CURRENCY", pattern: "#,##0.00 \"PLN\"" } } }, fields: "userEnteredFormat.numberFormat" } },
          { updateSheetProperties: { properties: { sheetId: rSheetId, gridProperties: { frozenRowCount: 2 } }, fields: "gridProperties.frozenRowCount" } },
        ],
      },
    });

    await sheets.spreadsheets.values.update({
      spreadsheetId: receiptsId,
      range: `${config.google.receiptsSheetName}!A1:F2`,
      valueInputOption: "USER_ENTERED",
      requestBody: {
        values: [
          ["Дата", "Сумма", "Что куплено", "Магазин", "Ссылка на фото", "Кто добавил"],
          ["ИТОГО:", "=SUM(B3:B)", "", "", "", ""],
        ],
      },
    });

    // Store receipts URL (this goes into the project registry, not into Bitrix)
    (data as any)._receiptsUrl = receiptsUrl;

    resourcesCreated = true;
    logger.info("Project resources created", {
      project: data.project_name,
      driveUrl,
      sheetsUrl,
      receiptsUrl,
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
  const receiptsUrl = (data as any)._receiptsUrl || "";
  const row = [data.project_name!, driveUrl, sheetsUrl, receiptsUrl, "active", today];

  if (projIndex >= 0) {
    const rowNum = projIndex + 2;
    await sheets.spreadsheets.values.update({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A${rowNum}:F${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  } else {
    await sheets.spreadsheets.values.append({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!A:F`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  }

  // Upsert project_access for each worker
  const workers = data.workers || [];
  if (workers.length > 0) {
    // Resolve worker names from Bitrix API if we have worker_id but no name
    const bitrixUrl = data.bitrix_webhook_url?.replace(/\/$/, "");
    for (const worker of workers) {
      // If worker_name looks like "user_X" or is empty but we have worker_id — resolve from Bitrix
      if (bitrixUrl && worker.worker_id) {
        const userId = String(worker.worker_id).replace("user_", "");
        if (!worker.worker_name || worker.worker_name === worker.worker_id || worker.worker_name.startsWith("user_")) {
          try {
            const userRes = await fetch(`${bitrixUrl}/user.get`, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ ID: userId }),
            });
            const userData = await userRes.json() as any;
            if (userData.result && userData.result[0]) {
              worker.worker_name = `${userData.result[0].NAME} ${userData.result[0].LAST_NAME}`.trim();
              logger.info("Resolved worker name", { userId, name: worker.worker_name });
            }
          } catch (err: any) {
            logger.error("Failed to resolve worker name", { userId, error: err.message });
          }
        }
      }
    }

    const accessRes = await sheets.spreadsheets.values.get({
      spreadsheetId: config.google.workersSpreadsheetId,
      range: `${config.google.accessSheetName}!A2:D`,
    });
    const accessRows = accessRes.data.values || [];

    for (const worker of workers) {
      if (!worker.worker_name || worker.worker_name.trim() === "") continue;

      const workerId = worker.worker_id ? String(worker.worker_id).replace("user_", "") : "";

      // Match by project_name + worker_id (if available) or by project_name + worker_name
      const accessIndex = accessRows.findIndex(
        r => r[0] === data.project_name && (workerId ? r[3] === workerId : r[1] === worker.worker_name)
      );
      const accessRow = [data.project_name!, worker.worker_name, worker.role_in_project || "other", workerId];

      if (accessIndex >= 0) {
        const rowNum = accessIndex + 2;
        await sheets.spreadsheets.values.update({
          spreadsheetId: config.google.workersSpreadsheetId,
          range: `${config.google.accessSheetName}!A${rowNum}:D${rowNum}`,
          valueInputOption: "RAW",
          requestBody: { values: [accessRow] },
        });
      } else {
        await sheets.spreadsheets.values.append({
          spreadsheetId: config.google.workersSpreadsheetId,
          range: `${config.google.accessSheetName}!A:D`,
          valueInputOption: "RAW",
          requestBody: { values: [accessRow] },
        });
        accessRows.push(accessRow);
      }
    }

    // Sync workers registry: for each worker_id, fetch user data from Bitrix and upsert into workers sheet
    if (bitrixUrl) {
      const workersRes = await sheets.spreadsheets.values.get({
        spreadsheetId: config.google.workersSpreadsheetId,
        range: `${config.google.workersSheetName}!A2:D`,
      });
      const workersRows = workersRes.data.values || [];

      for (const worker of workers) {
        if (!worker.worker_id) continue;
        const userId = String(worker.worker_id).replace("user_", "");
        if (!userId || userId === "0") continue;

        // Check if this worker already has telegram_id in our registry
        const existingRow = workersRows.find(r => String(r[0]) === userId);
        const hasTelegramId = existingRow && existingRow[1] && String(existingRow[1]) !== "" && String(existingRow[1]) !== "0";

        if (!hasTelegramId) {
          // Fetch from Bitrix
          try {
            const userRes = await fetch(`${bitrixUrl}/user.get`, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ ID: userId }),
            });
            const userData = await userRes.json() as any;
            if (userData.result && userData.result[0]) {
              const user = userData.result[0];
              const name = `${user.NAME || ""} ${user.LAST_NAME || ""}`.trim();
              const telegramId = user[config.bitrix.telegramIdField] || ""; // Telegram ID field
              const role = worker.role_in_project || "other";

              if (telegramId) {
                const workerRow = [userId, telegramId, name, role];
                const workerIndex = workersRows.findIndex(r => String(r[0]) === userId);

                if (workerIndex >= 0) {
                  // Update existing — keep admin role if already set
                  const currentRole = workersRows[workerIndex][3];
                  if (currentRole === "admin") workerRow[3] = "admin";
                  const rowNum = workerIndex + 2;
                  await sheets.spreadsheets.values.update({
                    spreadsheetId: config.google.workersSpreadsheetId,
                    range: `${config.google.workersSheetName}!A${rowNum}:D${rowNum}`,
                    valueInputOption: "RAW",
                    requestBody: { values: [workerRow] },
                  });
                } else {
                  await sheets.spreadsheets.values.append({
                    spreadsheetId: config.google.workersSpreadsheetId,
                    range: `${config.google.workersSheetName}!A:D`,
                    valueInputOption: "RAW",
                    requestBody: { values: [workerRow] },
                  });
                  workersRows.push(workerRow);
                }
                logger.info("Worker synced from Bitrix", { userId, name, telegramId });
              }
            }
          } catch (err: any) {
            logger.error("Failed to sync worker from Bitrix", { userId, error: err.message });
          }
        }
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

    // If deal_id and bitrix_webhook_url provided — write URLs back to Bitrix24 automatically
    if (data.deal_id && data.bitrix_webhook_url) {
      try {
        const bitrixUrl = data.bitrix_webhook_url.replace(/\/$/, "");
        const updateResponse = await fetch(`${bitrixUrl}/crm.deal.update`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            ID: data.deal_id,
            FIELDS: {
              [config.bitrix.driveUrlField]: driveUrl,
              [config.bitrix.sheetsUrlField]: sheetsUrl,
            },
          }),
        });
        const updateResult = await updateResponse.json() as any;
        if (updateResult.result) {
          logger.info("Bitrix24 deal updated", { dealId: data.deal_id, driveUrl, sheetsUrl });
        } else {
          logger.error("Bitrix24 deal update failed", { dealId: data.deal_id, error: updateResult });
        }
      } catch (err: any) {
        logger.error("Bitrix24 callback failed", { dealId: data.deal_id, error: err.message });
      }
    }
  }

  return result;
}

async function handleUpsertWorker(data: WebhookRequest): Promise<WebhookResponse> {
  const missing: string[] = [];
  if (!data.bitrix_user_id) missing.push("bitrix_user_id");
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

  // Resolve worker name from Bitrix API if not provided
  let workerName = data.worker_name || "";
  if ((!workerName || workerName.startsWith("user_")) && data.bitrix_webhook_url) {
    try {
      const bitrixUrl = data.bitrix_webhook_url.replace(/\/$/, "");
      const userId = String(data.bitrix_user_id).replace("user_", "");
      const userRes = await fetch(`${bitrixUrl}/user.get`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ID: userId }),
      });
      const userData = await userRes.json() as any;
      if (userData.result && userData.result[0]) {
        workerName = `${userData.result[0].NAME} ${userData.result[0].LAST_NAME}`.trim();
      }
    } catch (err: any) {
      logger.error("Failed to resolve worker name", { error: err.message });
    }
  }

  if (!workerName) {
    return { status: "error", message: "missing field: worker_name (and could not resolve from Bitrix)" };
  }

  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.workersSheetName}!A2:D`,
  });
  const rows = res.data.values || [];
  const bitrixId = String(data.bitrix_user_id).replace("user_", "");
  const workerIndex = rows.findIndex(r => String(r[0]) === bitrixId);

  // Admin role is sticky
  let newRole = data.role!;
  if (workerIndex >= 0 && rows[workerIndex][3] === "admin" && newRole !== "admin") {
    newRole = "admin";
  }

  const row = [bitrixId, tgId, workerName, newRole];

  if (workerIndex >= 0) {
    const oldName = rows[workerIndex][2] || "";
    const rowNum = workerIndex + 2;
    await sheets.spreadsheets.values.update({
      spreadsheetId: config.google.workersSpreadsheetId,
      range: `${config.google.workersSheetName}!A${rowNum}:D${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });

    // If name changed — update project_access rows too
    if (oldName && oldName !== workerName) {
      logger.info("Worker name changed, updating project_access", { bitrixId, oldName, newName: workerName });
      const accessRes = await sheets.spreadsheets.values.get({
        spreadsheetId: config.google.workersSpreadsheetId,
        range: `${config.google.accessSheetName}!A2:D`,
      });
      const accessRows = accessRes.data.values || [];
      for (let i = 0; i < accessRows.length; i++) {
        // Match by worker_id (column D) or by old name (column B)
        if (accessRows[i][3] === bitrixId || accessRows[i][1] === oldName) {
          const rowNum = i + 2;
          accessRows[i][1] = workerName; // update name
          await sheets.spreadsheets.values.update({
            spreadsheetId: config.google.workersSpreadsheetId,
            range: `${config.google.accessSheetName}!B${rowNum}`,
            valueInputOption: "RAW",
            requestBody: { values: [[workerName]] },
          });
        }
      }
    }
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
