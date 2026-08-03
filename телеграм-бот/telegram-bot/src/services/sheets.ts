import { google } from "googleapis";
import { config } from "../config.js";
import { extractSpreadsheetId } from "../utils/validators.js";

export interface Worker {
  bitrixUserId: string;
  telegramId: number;
  name: string;
  role: string;
}

export interface Project {
  name: string;
  googleDriveUrl: string;
  googleSheetsUrl: string;
  status: string;
}

export interface ProjectAccess {
  projectName: string;
  workerName: string;
  roleInProject: string;
}

function getAuth() {
  return new google.auth.GoogleAuth({
    credentials: config.google.serviceAccountKey as any,
    scopes: ["https://www.googleapis.com/auth/spreadsheets"],
  });
}

function getSheets() {
  return google.sheets({ version: "v4", auth: getAuth() });
}

export async function getWorker(telegramId: number): Promise<Worker | null> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.workersSheetName}!A2:D`,
  });

  const rows = res.data.values || [];
  const row = rows.find(r => String(r[1]) === String(telegramId));
  if (!row) return null;

  return {
    bitrixUserId: row[0] || "",
    telegramId: Number(row[1]),
    name: row[2] || "",
    role: row[3] || "other",
  };
}

export async function getAllWorkers(): Promise<Worker[]> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.workersSheetName}!A2:D`,
  });

  return (res.data.values || [])
    .filter(r => r[1]) // must have telegram_id
    .map(r => ({
      bitrixUserId: r[0] || "",
      telegramId: Number(r[1]),
      name: r[2] || "",
      role: r[3] || "other",
    }));
}

export async function getAllActiveProjects(): Promise<Project[]> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.projectsSpreadsheetId,
    range: `${config.google.projectsSheetName}!A2:E`,
  });

  return (res.data.values || [])
    .filter(r => r[3] === "active")
    .map(r => ({
      name: r[0] || "",
      googleDriveUrl: r[1] || "",
      googleSheetsUrl: r[2] || "",
      status: r[3] || "",
    }));
}

export async function getProjectAccessForWorker(workerName: string): Promise<ProjectAccess[]> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.accessSheetName}!A2:C`,
  });

  return (res.data.values || [])
    .filter(r => r[1] === workerName)
    .map(r => ({
      projectName: r[0] || "",
      workerName: r[1] || "",
      roleInProject: r[2] || "",
    }));
}

export async function getProjectsForWorker(worker: Worker): Promise<Project[]> {
  const allActive = await getAllActiveProjects();

  if (worker.role === "admin") {
    return allActive;
  }

  const access = await getProjectAccessForWorker(worker.name);
  const projectNames = new Set(access.map(a => a.projectName));
  return allActive.filter(p => projectNames.has(p.name));
}

export async function appendReceiptRow(
  sheetsUrl: string,
  row: { date: string; sum: number; description: string; storeName: string; photoLink: string }
): Promise<void> {
  const sheets = getSheets();
  const spreadsheetId = extractSpreadsheetId(sheetsUrl);

  await sheets.spreadsheets.values.append({
    spreadsheetId,
    range: "receipts!A:E",
    valueInputOption: "RAW",
    requestBody: {
      values: [[row.date, row.sum, row.description, row.storeName, row.photoLink]],
    },
  });
}
