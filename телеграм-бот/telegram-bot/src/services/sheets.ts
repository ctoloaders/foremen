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
  workerId: string;
}

export interface ExpenseCategory {
  /** Category label shown to the user and stored in the sheet (column A). */
  category: string;
  /** Optional follow-up question text (column B). When present, the bot asks it and stores
   *  the answer as "{category}: {answer}". When empty, only the category is stored. */
  extraPrompt?: string;
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

export async function getProjectAccessForWorker(bitrixUserId: string): Promise<ProjectAccess[]> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.accessSheetName}!A2:D`,
  });

  return (res.data.values || [])
    .filter(r => r[3] === bitrixUserId)
    .map(r => ({
      projectName: r[0] || "",
      workerName: r[1] || "",
      roleInProject: r[2] || "",
      workerId: r[3] || "",
    }));
}

export async function getProjectsForWorker(worker: Worker): Promise<Project[]> {
  const allActive = await getAllActiveProjects();

  if (worker.role === "admin") {
    return allActive;
  }

  const access = await getProjectAccessForWorker(worker.bitrixUserId);
  const projectNames = new Set(access.map(a => a.projectName));
  return allActive.filter(p => projectNames.has(p.name));
}

/**
 * Pure parser: maps raw sheet rows (column A = category, column B = optional follow-up question)
 * into ExpenseCategory objects. Rows with an empty/whitespace-only category are skipped; an
 * empty column B yields `extraPrompt: undefined`. Extracted for unit testing.
 */
export function parseExpenseCategoryRows(rows: unknown[][]): ExpenseCategory[] {
  return (rows || [])
    .filter((r) => r && r[0] !== undefined && r[0] !== null && String(r[0]).trim() !== "")
    .map((r) => {
      const extra = (r[1] ?? "").toString().trim();
      return {
        category: String(r[0]).trim(),
        extraPrompt: extra !== "" ? extra : undefined,
      };
    });
}

/**
 * Reads the expense categories from the shared expense_categories sheet in the workers-registry
 * spreadsheet. Column A = category label, column B = optional follow-up question. Rows with an
 * empty category are skipped. Data starts at row 2 (row 1 is a header).
 */
export async function getExpenseCategories(): Promise<ExpenseCategory[]> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId: config.google.workersSpreadsheetId,
    range: `${config.google.expenseCategoriesSheetName}!A2:B`,
  });

  return parseExpenseCategoryRows(res.data.values || []);
}

/**
 * Appends a receipt row to the centralized `receipts` sheet in the workers-registry spreadsheet.
 *
 * Column layout (letters skipped are intentionally left untouched — they hold VAT/net formulas):
 *   A = Data (date)
 *   B = Projekt (project name)
 *   C = Kategoria (expense category, "{cat}" or "{cat}: {answer}")
 *   D = Sklep / Magazyn (store)
 *   E = Opis (description)
 *   F = Zakup Brutto (gross sum)
 *   G..J = VAT / Netto / Sprzedaż — NOT written
 *   K = Komentarz (file link)
 *   L = Kto dodał (added by)
 */
export async function appendReceiptRowCentral(row: {
  date: string;
  project: string;
  category: string;
  storeName: string;
  description: string;
  sum: number;
  fileLink: string;
  addedBy: string;
  sumNote?: string;
}): Promise<void> {
  const sheets = getSheets();
  const spreadsheetId = config.google.workersSpreadsheetId;
  const sheetName = config.google.centralReceiptsSheetName;

  // Find first empty data row. Data starts at row 2 (row 1 is the header). We probe column A.
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId,
    range: `'${sheetName}'!A2:A`,
  });
  const existingRows = res.data.values || [];
  let insertRow = 2;
  for (let i = 0; i < existingRows.length; i++) {
    if (existingRows[i] && existingRows[i][0]) {
      insertRow = i + 3; // next row after the last filled one (A2 => i=0 => next = 3)
    }
  }

  // A..F: Data, Projekt, Kategoria, Sklep, Opis, Zakup Brutto
  await sheets.spreadsheets.values.update({
    spreadsheetId,
    range: `'${sheetName}'!A${insertRow}:F${insertRow}`,
    valueInputOption: "RAW",
    requestBody: {
      values: [[row.date, row.project, row.category, row.storeName, row.description, row.sum]],
    },
  });

  // K..L: Komentarz (file link), Kto dodał
  await sheets.spreadsheets.values.update({
    spreadsheetId,
    range: `'${sheetName}'!K${insertRow}:L${insertRow}`,
    valueInputOption: "RAW",
    requestBody: {
      values: [[row.fileLink, row.addedBy]],
    },
  });

  // Note on the gross sum cell (column F = index 5) if provided.
  if (row.sumNote) {
    await addNoteToCell(spreadsheetId, sheetName, insertRow, 5, row.sumNote);
  }
}

export async function appendReceiptRow(
  sheetsUrl: string,
  row: { date: string; sum: number; description: string; storeName: string; photoLink: string; addedBy: string; sumNote?: string }
): Promise<void> {
  const sheets = getSheets();
  const spreadsheetId = extractSpreadsheetId(sheetsUrl);
  const sheetName = "Mat. budowlane";

  // Find first empty row starting from row 3
  // Check column A (Data) — if empty, that's our row
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId,
    range: `'${sheetName}'!A3:A500`,
  });
  const existingRows = res.data.values || [];
  let insertRow = 3; // default: row 3
  for (let i = 0; i < existingRows.length; i++) {
    if (existingRows[i] && existingRows[i][0]) {
      insertRow = i + 4; // next row after last filled
    }
  }

  // Write: Data(A), Sklep/Magazyn(B), Opis(C), Zakup Brutto(D)
  await sheets.spreadsheets.values.update({
    spreadsheetId,
    range: `'${sheetName}'!A${insertRow}:D${insertRow}`,
    valueInputOption: "RAW",
    requestBody: {
      values: [[row.date, row.storeName, row.description, row.sum]],
    },
  });

  // Write: Komentarz(I) = photo link, column J = who added
  await sheets.spreadsheets.values.update({
    spreadsheetId,
    range: `'${sheetName}'!I${insertRow}:J${insertRow}`,
    valueInputOption: "RAW",
    requestBody: {
      values: [[row.photoLink, row.addedBy]],
    },
  });

  // Add note to sum cell (column D) if provided
  if (row.sumNote) {
    await addNoteToCell(spreadsheetId, sheetName, insertRow, 3, row.sumNote); // column D = index 3
  }
}

/**
 * Adds a note (comment) to a specific cell using the Sheets API.
 * Row is 1-indexed, column is 0-indexed.
 */
async function addNoteToCell(
  spreadsheetId: string,
  sheetName: string,
  row: number,
  column: number,
  note: string
): Promise<void> {
  const sheets = getSheets();

  // Get the sheet ID (gid) from the sheet name
  const spreadsheet = await sheets.spreadsheets.get({
    spreadsheetId,
    fields: "sheets.properties",
  });

  const sheet = spreadsheet.data.sheets?.find(
    (s) => s.properties?.title === sheetName
  );
  const sheetId = sheet?.properties?.sheetId || 0;

  await sheets.spreadsheets.batchUpdate({
    spreadsheetId,
    requestBody: {
      requests: [
        {
          updateCells: {
            rows: [
              {
                values: [
                  {
                    note: note,
                  },
                ],
              },
            ],
            fields: "note",
            range: {
              sheetId,
              startRowIndex: row - 1, // convert to 0-indexed
              endRowIndex: row,
              startColumnIndex: column,
              endColumnIndex: column + 1,
            },
          },
        },
      ],
    },
  });
}
