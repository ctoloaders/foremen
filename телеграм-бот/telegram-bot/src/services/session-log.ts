import { google } from "googleapis";
import { config } from "../config.js";
import { serializePhotoIds } from "../state/store.js";
import { sanitizeErrorTrace } from "../utils/sanitize.js";
import { logger } from "../utils/logger.js";

export type SessionStatus = "in_progress" | "success" | "failed" | "cancelled";

export interface SessionLogRecord {
  sessionId: string;
  telegramId: number;
  workerName?: string;
  role?: string;
  startedAt: string;        // ISO
  lastActivityAt: string;   // ISO
  step: string;             // ConversationStep value
  projectName?: string;
  projectDriveUrl?: string;
  projectSheetsUrl?: string;
  photoFileIds?: string[];
  photoLinks?: string[];
  sum?: number;
  description?: string;
  storeName?: string;
  status: SessionStatus;
  errorTrace?: string;
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

const spreadsheetId = config.google.workersSpreadsheetId;
const sheetName = config.google.sessionLogSheetName;

/** Serializes a record into the A–Q row layout of the session_log sheet. */
function toRow(r: SessionLogRecord): (string | number)[] {
  return [
    r.sessionId,                                             // A
    r.telegramId,                                            // B
    r.workerName || "",                                      // C
    r.role || "",                                            // D
    r.startedAt,                                             // E
    r.lastActivityAt,                                        // F
    r.step,                                                  // G
    r.projectName || "",                                     // H
    r.projectDriveUrl || "",                                 // I
    r.projectSheetsUrl || "",                                // J
    serializePhotoIds(r.photoFileIds),                       // K
    serializePhotoIds(r.photoLinks),                         // L
    r.sum !== undefined ? String(r.sum) : "",                // M
    r.description || "",                                     // N
    r.storeName || "",                                       // O
    r.status,                                                // P
    r.errorTrace || "",                                      // Q
  ];
}

/** Reads the full record for a sessionId, or null if the row is absent. */
async function readRecord(sessionId: string): Promise<{ record: SessionLogRecord; rowNum: number } | null> {
  const sheets = getSheets();
  const res = await sheets.spreadsheets.values.get({
    spreadsheetId,
    range: `${sheetName}!A2:Q`,
  });
  const rows = res.data.values || [];
  const idx = rows.findIndex((row) => String(row[0]) === String(sessionId));
  if (idx < 0) return null;

  const row = rows[idx];
  const parseArr = (raw: string): string[] | undefined => {
    if (!raw) return undefined;
    try {
      const p = JSON.parse(raw);
      if (Array.isArray(p)) return p;
    } catch {
      /* fall through */
    }
    return [raw];
  };

  const record: SessionLogRecord = {
    sessionId: String(row[0]),
    telegramId: Number(row[1]),
    workerName: row[2] || undefined,
    role: row[3] || undefined,
    startedAt: row[4] || new Date().toISOString(),
    lastActivityAt: row[5] || new Date().toISOString(),
    step: row[6] || "",
    projectName: row[7] || undefined,
    projectDriveUrl: row[8] || undefined,
    projectSheetsUrl: row[9] || undefined,
    photoFileIds: parseArr(row[10] || ""),
    photoLinks: parseArr(row[11] || ""),
    sum: row[12] ? parseFloat(String(row[12])) || undefined : undefined,
    description: row[13] || undefined,
    storeName: row[14] || undefined,
    status: (row[15] as SessionStatus) || "in_progress",
    errorTrace: row[16] || undefined,
  };
  return { record, rowNum: idx + 2 };
}

async function writeRow(rowNum: number | null, record: SessionLogRecord): Promise<void> {
  const sheets = getSheets();
  const values = [toRow(record)];
  if (rowNum) {
    await sheets.spreadsheets.values.update({
      spreadsheetId,
      range: `${sheetName}!A${rowNum}:Q${rowNum}`,
      valueInputOption: "RAW",
      requestBody: { values },
    });
  } else {
    await sheets.spreadsheets.values.append({
      spreadsheetId,
      range: `${sheetName}!A:Q`,
      valueInputOption: "RAW",
      requestBody: { values },
    });
  }
}

/**
 * Applies a patch to an existing session row (looked up by sessionId),
 * refreshing last_activity_at. If the row is missing it is (re)created.
 * All failures are swallowed and logged — session logging is best-effort
 * and MUST NOT break the user-facing flow (Requirement 11.12).
 */
async function patchSession(sessionId: string, patch: Partial<SessionLogRecord>): Promise<void> {
  try {
    const existing = await readRecord(sessionId);
    const now = new Date().toISOString();
    if (existing) {
      const merged: SessionLogRecord = {
        ...existing.record,
        ...patch,
        sessionId,
        lastActivityAt: now,
      };
      await writeRow(existing.rowNum, merged);
    } else {
      // Row vanished (e.g. sheet reset) — recreate a best-effort record.
      const recreated: SessionLogRecord = {
        sessionId,
        telegramId: patch.telegramId ?? 0,
        startedAt: patch.startedAt ?? now,
        lastActivityAt: now,
        step: patch.step ?? "",
        status: patch.status ?? "in_progress",
        ...patch,
      };
      await writeRow(null, recreated);
    }
  } catch (err: any) {
    logger.error("session-log write failed", { sessionId, error: err?.message });
  }
}

export const sessionLog = {
  /** Creates a new in_progress row for a starting session. */
  async startSession(input: {
    sessionId: string;
    telegramId: number;
    workerName?: string;
    role?: string;
    step: string;
  }): Promise<void> {
    try {
      const now = new Date().toISOString();
      const record: SessionLogRecord = {
        sessionId: input.sessionId,
        telegramId: input.telegramId,
        workerName: input.workerName,
        role: input.role,
        startedAt: now,
        lastActivityAt: now,
        step: input.step,
        status: "in_progress",
      };
      await writeRow(null, record);
    } catch (err: any) {
      logger.error("session-log startSession failed", { sessionId: input.sessionId, error: err?.message });
    }
  },

  /** Updates collected fields / step and refreshes last_activity_at. */
  async updateSession(sessionId: string, patch: Partial<SessionLogRecord>): Promise<void> {
    await patchSession(sessionId, patch);
  },

  /** Terminal: mark success and record final sum + photo links. */
  async finalizeSuccess(sessionId: string, patch: Partial<SessionLogRecord>): Promise<void> {
    await patchSession(sessionId, { ...patch, status: "success" });
  },

  /** Terminal: mark the session cancelled. */
  async finalizeCancelled(sessionId: string): Promise<void> {
    await patchSession(sessionId, { status: "cancelled" });
  },

  /** Terminal: mark failed and store a sanitized error trace. */
  async finalizeFailed(sessionId: string, error: unknown, patch?: Partial<SessionLogRecord>): Promise<void> {
    await patchSession(sessionId, {
      ...patch,
      status: "failed",
      errorTrace: sanitizeErrorTrace(error),
    });
  },
};
