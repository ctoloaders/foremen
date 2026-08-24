import { google } from "googleapis";
import { config } from "../config.js";
import { ConversationState, ConversationStep, emptyState } from "./machine.js";

const STALE_HOURS = 24;

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

const spreadsheetId = config.google.workersSpreadsheetId;
const sheetName = config.google.botStateSheetName;

// --- Photo IDs serialization helpers ---

/**
 * Serialize an array of photo file_ids to a JSON string for storage in Sheets.
 * Returns empty string if the array is undefined or empty.
 */
export function serializePhotoIds(ids?: string[]): string {
  if (!ids || ids.length === 0) return "";
  return JSON.stringify(ids);
}

/**
 * Deserialize a photo file_ids value from Sheets.
 * Handles backward compatibility: if the value is not valid JSON array,
 * treat it as a single file_id string (old format).
 * Returns undefined for empty/falsy input.
 */
export function deserializePhotoIds(raw: string): string[] | undefined {
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed;
  } catch {
    // Not valid JSON — treat as single file_id (backward compat)
  }
  return [raw];
}

// --- State CRUD ---

export async function getState(telegramId: number): Promise<ConversationState | null> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId,
    range: `${sheetName}!A2:M`,
  });

  const rows = res.data.values || [];
  const row = rows.find(r => String(r[0]) === String(telegramId));
  if (!row) return null;

  const photoFileIds = deserializePhotoIds(row[5] || "");

  const state: ConversationState = {
    telegramId: Number(row[0]),
    step: (row[1] as ConversationStep) || ConversationStep.IDLE,
    projectName: row[2] || undefined,
    projectDriveUrl: row[3] || undefined,
    projectSheetsUrl: row[4] || undefined,
    // Backward compat: if deserialized as array of one, also set deprecated photoFileId
    photoFileId: photoFileIds && photoFileIds.length === 1 ? photoFileIds[0] : undefined,
    photoFileIds,
    sum: row[6] ? parseFloat(String(row[6])) || undefined : undefined,
    description: row[7] || undefined,
    storeName: row[8] || undefined,
    updatedAt: row[9] || new Date().toISOString(),
    // New OCR fields (columns K, L, M)
    ocrDescription: row[10] || undefined,
    ocrStoreName: row[11] || undefined,
    ocrGrossAmount: row[12] ? parseFloat(String(row[12])) || undefined : undefined,
  };

  // Check staleness
  const updatedAt = new Date(state.updatedAt);
  const hoursAgo = (Date.now() - updatedAt.getTime()) / (1000 * 60 * 60);
  if (hoursAgo > STALE_HOURS) {
    await clearState(telegramId);
    return null;
  }

  return state;
}

export async function setState(state: ConversationState): Promise<void> {
  const sheets = getSheets();
  state.updatedAt = new Date().toISOString();

  const row = [
    state.telegramId,                                       // A
    state.step,                                             // B
    state.projectName || "",                                // C
    state.projectDriveUrl || "",                            // D
    state.projectSheetsUrl || "",                           // E
    serializePhotoIds(state.photoFileIds) || state.photoFileId || "",  // F
    state.sum !== undefined ? String(state.sum) : "",       // G
    state.description || "",                                // H
    state.storeName || "",                                  // I
    state.updatedAt,                                        // J
    state.ocrDescription || "",                             // K
    state.ocrStoreName || "",                               // L
    state.ocrGrossAmount !== undefined ? String(state.ocrGrossAmount) : "",  // M
  ];

  // Find existing row
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId,
    range: `${sheetName}!A2:A`,
  });

  const rows = res.data.values || [];
  const rowIndex = rows.findIndex(r => String(r[0]) === String(state.telegramId));

  if (rowIndex >= 0) {
    // Update existing
    const rowNum = rowIndex + 2;
    await sheets.spreadsheets.values.update({
      spreadsheetId,
      range: `${sheetName}!A${rowNum}:M${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  } else {
    // Append new
    await sheets.spreadsheets.values.append({
      spreadsheetId,
      range: `${sheetName}!A:M`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  }
}

export async function clearState(telegramId: number): Promise<void> {
  const sheets = getSheets();

  const res = await sheets.spreadsheets.values.get({
    spreadsheetId,
    range: `${sheetName}!A2:A`,
  });

  const rows = res.data.values || [];
  const rowIndex = rows.findIndex(r => String(r[0]) === String(telegramId));

  if (rowIndex >= 0) {
    const rowNum = rowIndex + 2;
    await sheets.spreadsheets.values.update({
      spreadsheetId,
      range: `${sheetName}!A${rowNum}:M${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [["", "", "", "", "", "", "", "", "", "", "", "", ""]] },
    });
  }
}
