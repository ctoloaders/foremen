import { google } from "googleapis";
import { config } from "../config.js";
import { ConversationState, ConversationStep, emptyState } from "./machine.js";

const STALE_HOURS = 24;

function getAuth() {
  return new google.auth.GoogleAuth({
    credentials: config.google.serviceAccountKey as any,
    scopes: ["https://www.googleapis.com/auth/spreadsheets"],
  });
}

function getSheets() {
  return google.sheets({ version: "v4", auth: getAuth() });
}

const spreadsheetId = config.google.workersSpreadsheetId;
const sheetName = config.google.botStateSheetName;

export async function getState(telegramId: number): Promise<ConversationState | null> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId,
    range: `${sheetName}!A2:J`,
  });

  const rows = res.data.values || [];
  const row = rows.find(r => String(r[0]) === String(telegramId));
  if (!row) return null;

  const state: ConversationState = {
    telegramId: Number(row[0]),
    step: (row[1] as ConversationStep) || ConversationStep.IDLE,
    projectName: row[2] || undefined,
    projectDriveUrl: row[3] || undefined,
    projectSheetsUrl: row[4] || undefined,
    photoFileId: row[5] || undefined,
    sum: row[6] ? Number(row[6]) : undefined,
    description: row[7] || undefined,
    storeName: row[8] || undefined,
    updatedAt: row[9] || new Date().toISOString(),
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
    state.telegramId,
    state.step,
    state.projectName || "",
    state.projectDriveUrl || "",
    state.projectSheetsUrl || "",
    state.photoFileId || "",
    state.sum ?? "",
    state.description || "",
    state.storeName || "",
    state.updatedAt,
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
      range: `${sheetName}!A${rowNum}:J${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [row] },
    });
  } else {
    // Append new
    await sheets.spreadsheets.values.append({
      spreadsheetId,
      range: `${sheetName}!A:J`,
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
      range: `${sheetName}!A${rowNum}:J${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values: [["", "", "", "", "", "", "", "", "", ""]] },
    });
  }
}
