import { config } from "../config.js";
import { serializePhotoIds } from "../state/store.js";
import { sanitizeErrorTrace } from "../utils/sanitize.js";
import { logger } from "../utils/logger.js";
import { getSheetsClient } from "./google-clients.js";

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

const spreadsheetId = config.google.workersSpreadsheetId;
const sheetName = config.google.sessionLogSheetName;

type CellValue = string | number;

/** Sheet column letter + cell serializer for every field of the A–Q session_log layout. */
const COLUMNS: { [K in keyof SessionLogRecord]-?: { col: string; ser: (v: SessionLogRecord[K]) => CellValue } } = {
  sessionId:        { col: "A", ser: (v) => v ?? "" },
  telegramId:       { col: "B", ser: (v) => v ?? "" },
  workerName:       { col: "C", ser: (v) => v || "" },
  role:             { col: "D", ser: (v) => v || "" },
  startedAt:        { col: "E", ser: (v) => v ?? "" },
  lastActivityAt:   { col: "F", ser: (v) => v ?? "" },
  step:             { col: "G", ser: (v) => v ?? "" },
  projectName:      { col: "H", ser: (v) => v || "" },
  projectDriveUrl:  { col: "I", ser: (v) => v || "" },
  projectSheetsUrl: { col: "J", ser: (v) => v || "" },
  photoFileIds:     { col: "K", ser: (v) => serializePhotoIds(v) },
  photoLinks:       { col: "L", ser: (v) => serializePhotoIds(v) },
  sum:              { col: "M", ser: (v) => (v !== undefined ? String(v) : "") },
  description:      { col: "N", ser: (v) => v || "" },
  storeName:        { col: "O", ser: (v) => v || "" },
  status:           { col: "P", ser: (v) => v ?? "" },
  errorTrace:       { col: "Q", ser: (v) => v || "" },
};

/** Field order matching the sheet's A–Q columns. */
const FIELD_ORDER: (keyof SessionLogRecord)[] = [
  "sessionId", "telegramId", "workerName", "role", "startedAt", "lastActivityAt", "step",
  "projectName", "projectDriveUrl", "projectSheetsUrl", "photoFileIds", "photoLinks",
  "sum", "description", "storeName", "status", "errorTrace",
];

/** Serializes a full record into the A–Q row layout of the session_log sheet. */
function toRow(r: SessionLogRecord): CellValue[] {
  return FIELD_ORDER.map((k) => (COLUMNS[k].ser as (v: unknown) => CellValue)(r[k]));
}

/**
 * Builds the minimal set of single-cell writes for a patch: one entry per patched field plus
 * last_activity_at. Fields explicitly present with `undefined` are written as an empty cell
 * (same as the previous merge-and-rewrite behaviour). Pure function — unit tested.
 */
export function buildCellUpdates(
  sheet: string,
  rowNum: number,
  patch: Partial<SessionLogRecord>,
  nowIso: string,
): { range: string; values: CellValue[][] }[] {
  const values = new Map<keyof SessionLogRecord, CellValue>();
  for (const key of Object.keys(patch) as (keyof SessionLogRecord)[]) {
    if (!(key in COLUMNS)) continue;
    values.set(key, (COLUMNS[key].ser as (v: unknown) => CellValue)(patch[key]));
  }
  values.set("lastActivityAt", nowIso); // always refreshed

  return [...values.entries()].map(([key, v]) => ({
    range: `${sheet}!${COLUMNS[key].col}${rowNum}`,
    values: [[v]],
  }));
}

/**
 * Finds the sheet row of a session by reading ONLY column A (session ids). The previous
 * implementation read the whole A–Q range (hundreds of KB, growing with every session) on every
 * update. Not cached across calls on purpose: a human re-sorting the log sheet must never make
 * us write to the wrong row.
 */
async function findSessionRow(sessionId: string): Promise<number | null> {
  const res = await getSheetsClient().spreadsheets.values.get({
    spreadsheetId,
    range: `${sheetName}!A2:A`,
  });
  const rows = res.data.values || [];
  const idx = rows.findIndex((row) => String(row[0]) === String(sessionId));
  return idx < 0 ? null : idx + 2;
}

async function appendRow(record: SessionLogRecord): Promise<void> {
  await getSheetsClient().spreadsheets.values.append({
    spreadsheetId,
    range: `${sheetName}!A:Q`,
    valueInputOption: "RAW",
    requestBody: { values: [toRow(record)] },
  });
}

/**
 * Applies a patch to an existing session row (looked up by sessionId), refreshing
 * last_activity_at, by writing only the touched cells in a single request. If the row is
 * missing it is (re)created. All failures are swallowed and logged — session logging is
 * best-effort and MUST NOT break the user-facing flow (Requirement 11.12).
 */
async function patchSession(sessionId: string, patch: Partial<SessionLogRecord>): Promise<void> {
  try {
    const rowNum = await findSessionRow(sessionId);
    const now = new Date().toISOString();
    if (rowNum) {
      await getSheetsClient().spreadsheets.values.batchUpdate({
        spreadsheetId,
        requestBody: {
          valueInputOption: "RAW",
          data: buildCellUpdates(sheetName, rowNum, patch, now),
        },
      });
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
      await appendRow(recreated);
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
    projectName?: string;
    projectDriveUrl?: string;
    projectSheetsUrl?: string;
  }): Promise<void> {
    try {
      const now = new Date().toISOString();
      const record: SessionLogRecord = {
        sessionId: input.sessionId,
        telegramId: input.telegramId,
        workerName: input.workerName,
        role: input.role,
        projectName: input.projectName,
        projectDriveUrl: input.projectDriveUrl,
        projectSheetsUrl: input.projectSheetsUrl,
        startedAt: now,
        lastActivityAt: now,
        step: input.step,
        status: "in_progress",
      };
      await appendRow(record);
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
