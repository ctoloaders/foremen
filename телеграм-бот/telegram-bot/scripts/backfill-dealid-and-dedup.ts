/**
 * One-time migration for the project registries:
 *
 *   1. Backfill Bitrix deal_id onto existing rows
 *      - projects sheet:        column F = deal_id
 *      - project_access sheet:  column E = deal_id
 *      Strategy A: pull all deals via crm.deal.list, build a TITLE -> dealId map, and match
 *      legacy rows by project name. Names that are ambiguous (same TITLE on multiple deals) or
 *      not found are left blank and reported.
 *
 *   2. De-duplicate project_access to STRICTLY one row per (project, person).
 *      Grouping key prefers deal_id; legacy rows with no deal_id group by project name.
 *      The kept row uses the highest-priority role: pm > foreman > estimator > sales > other.
 *
 * Dry run by default (reports only). Pass --apply to write changes.
 *   bun scripts/backfill-dealid-and-dedup.ts
 *   bun scripts/backfill-dealid-and-dedup.ts --apply
 */
import { google } from "googleapis";
import { config } from "../src/config.js";

const APPLY = process.argv.includes("--apply");
const BITRIX_URL = (process.env.BITRIX_WEBHOOK_URL || "").replace(/\/$/, "");

function auth() {
  const o: any = {
    credentials: config.google.serviceAccountKey,
    scopes: ["https://www.googleapis.com/auth/spreadsheets"],
  };
  if (config.google.impersonateEmail) o.clientOptions = { subject: config.google.impersonateEmail };
  return new google.auth.GoogleAuth(o);
}
const s = google.sheets({ version: "v4", auth: auth() });

async function bitrixCall(method: string, params: any = {}): Promise<any> {
  const res = await fetch(`${BITRIX_URL}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params),
  });
  return res.json();
}

/** Pulls every deal (id + title) with paging; returns a title -> [dealIds] map. */
async function fetchDealTitleMap(): Promise<Map<string, string[]>> {
  const byTitle = new Map<string, string[]>();
  let start = 0;
  for (;;) {
    const res = await bitrixCall("crm.deal.list", {
      select: ["ID", "TITLE"],
      start,
    });
    const items: any[] = res.result || [];
    for (const d of items) {
      const title = (d.TITLE || "").toString().trim();
      if (!title) continue;
      const arr = byTitle.get(title) ?? byTitle.set(title, []).get(title)!;
      arr.push(String(d.ID));
    }
    if (typeof res.next === "number") start = res.next;
    else break;
  }
  return byTitle;
}

/** Resolves a unique deal id for a project name, or null if missing/ambiguous. */
function resolveDealId(byTitle: Map<string, string[]>, name: string): { id: string | null; reason?: string } {
  const ids = byTitle.get(name.trim());
  if (!ids || ids.length === 0) return { id: null, reason: "not_found" };
  if (ids.length > 1) return { id: null, reason: `ambiguous(${ids.length})` };
  return { id: ids[0] };
}

const ROLE_PRIORITY: Record<string, number> = { pm: 0, foreman: 1, estimator: 2, sales: 3, other: 4 };
const roleRank = (r: string) => (r in ROLE_PRIORITY ? ROLE_PRIORITY[r] : 99);

async function main() {
  if (!BITRIX_URL) {
    console.error("BITRIX_WEBHOOK_URL is not set — cannot fetch deals for backfill.");
    process.exit(1);
  }

  console.log("Fetching deals from Bitrix…");
  const byTitle = await fetchDealTitleMap();
  console.log(`deals fetched: ${[...byTitle.values()].reduce((n, a) => n + a.length, 0)}, unique titles: ${byTitle.size}`);

  // Ensure the deal_id header cells exist (projects!F1, project_access!E1). Idempotent.
  if (APPLY) {
    await s.spreadsheets.values.update({
      spreadsheetId: config.google.projectsSpreadsheetId,
      range: `${config.google.projectsSheetName}!F1`,
      valueInputOption: "RAW",
      requestBody: { values: [["deal_id"]] },
    });
    await s.spreadsheets.values.update({
      spreadsheetId: config.google.workersSpreadsheetId,
      range: `${config.google.accessSheetName}!E1`,
      valueInputOption: "RAW",
      requestBody: { values: [["deal_id"]] },
    });
    console.log("deal_id headers ensured (projects!F1, project_access!E1)");
  }

  // ---- 1a. Backfill deal_id on the projects sheet (column F) ----
  const projSS = config.google.projectsSpreadsheetId;
  const projSheet = config.google.projectsSheetName;
  const projRes = await s.spreadsheets.values.get({ spreadsheetId: projSS, range: `${projSheet}!A2:F` });
  const projRows = projRes.data.values || [];
  let projFilled = 0;
  const projUnresolved: string[] = [];

  // Shared paced-write counter: pause 60s every 30 writes to stay under the Sheets
  // "write requests per minute per user" quota. Covers BOTH the projects and access writes.
  let writeCount = 0;
  const pacedWrite = async (fn: () => Promise<unknown>) => {
    await fn();
    if (++writeCount % 30 === 0) {
      console.log(`…${writeCount} writes done, pausing 60s for Sheets quota`);
      await new Promise((r) => setTimeout(r, 60000));
    }
  };

  for (let i = 0; i < projRows.length; i++) {
    const r = projRows[i];
    const name = (r[0] || "").toString().trim();
    const existing = (r[5] || "").toString().trim();
    if (!name || existing) continue; // skip empty rows and already-filled (resumable)
    const { id, reason } = resolveDealId(byTitle, name);
    if (!id) {
      projUnresolved.push(`${name} [${reason}]`);
      continue;
    }
    console.log(`projects row ${i + 2}: "${name}" -> deal ${id}`);
    projFilled++;
    if (APPLY) {
      await pacedWrite(() =>
        s.spreadsheets.values.update({
          spreadsheetId: projSS,
          range: `${projSheet}!F${i + 2}`,
          valueInputOption: "RAW",
          requestBody: { values: [[id]] },
        }),
      );
    }
  }

  // ---- 1b + 2. project_access: backfill deal_id (col E) and dedupe by (deal|name, userId) ----
  const wSS = config.google.workersSpreadsheetId;
  const accSheet = config.google.accessSheetName;
  const accRes = await s.spreadsheets.values.get({ spreadsheetId: wSS, range: `${accSheet}!A2:E` });
  const accRows = accRes.data.values || [];
  const accUnresolved = new Set<string>();

  // Compute a deal id for each row (existing E, else by project name).
  const rowDeal: (string | null)[] = accRows.map((r) => {
    const existing = (r[4] || "").toString().trim();
    if (existing) return existing;
    const name = (r[0] || "").toString().trim();
    if (!name) return null;
    const { id, reason } = resolveDealId(byTitle, name);
    if (!id) accUnresolved.add(`${name} [${reason}]`);
    return id;
  });

  // Group by (dealId || name) + userId.
  const groups = new Map<string, number[]>();
  accRows.forEach((r, i) => {
    const name = (r[0] || "").toString().trim();
    const userId = (r[3] || "").toString().trim();
    if (!name || !userId) return;
    const key = `${rowDeal[i] || "name:" + name}\u0000${userId}`;
    (groups.get(key) ?? groups.set(key, []).get(key)!).push(i);
  });

  const writes: { row: number; values: string[] }[] = [];
  const blanks: number[] = [];

  for (const [, idxs] of groups) {
    // Keep the highest-priority role row for this (project, person).
    idxs.sort((a, b) => roleRank((accRows[a][2] || "").toString()) - roleRank((accRows[b][2] || "").toString()));
    const keep = idxs[0];
    const r = accRows[keep];
    const dealId = rowDeal[keep] || "";
    const desiredRow = [
      (r[0] || "").toString(),
      (r[1] || "").toString(),
      (r[2] || "").toString(),
      (r[3] || "").toString(),
      dealId,
    ];
    // Rewrite kept row if deal_id missing/changed.
    if ((r[4] || "").toString().trim() !== dealId && dealId) {
      writes.push({ row: keep + 2, values: desiredRow });
    }
    // Blank the duplicates.
    for (const i of idxs) if (i !== keep) blanks.push(i + 2);
  }

  console.log("\n=== SUMMARY ===");
  console.log(`projects: deal_id to fill = ${projFilled}, unresolved = ${projUnresolved.length}`);
  if (projUnresolved.length) console.log("  unresolved projects:\n   - " + projUnresolved.join("\n   - "));
  console.log(`project_access: rows to rewrite (deal_id) = ${writes.length}, duplicate rows to blank = ${blanks.length}`);
  if (accUnresolved.size) console.log(`  access names unresolved: ${accUnresolved.size}\n   - ${[...accUnresolved].join("\n   - ")}`);
  console.log(`APPLY = ${APPLY}`);

  if (!APPLY) return;

  // Apply access writes then blanks, using the same paced-write throttle as the projects loop.
  for (const w of writes) {
    await pacedWrite(() =>
      s.spreadsheets.values.update({ spreadsheetId: wSS, range: `${accSheet}!A${w.row}:E${w.row}`, valueInputOption: "RAW", requestBody: { values: [w.values] } }),
    );
  }
  for (const rowNum of blanks) {
    await pacedWrite(() =>
      s.spreadsheets.values.update({ spreadsheetId: wSS, range: `${accSheet}!A${rowNum}:E${rowNum}`, valueInputOption: "RAW", requestBody: { values: [["", "", "", "", ""]] } }),
    );
  }
  console.log(`applied: ${writes.length} rewrites + ${blanks.length} blanks`);
}

await main();
