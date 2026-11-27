// Lifecycle suite support: step recording with dependency gating, per-role API calls,
// screenshots, and the run report. Nothing here asserts product behaviour by itself.
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { adminToken, raw } from '../../support/api.js';
import { clientOtpToken, latestOtpCode } from '../../support/client-otp.js';
import type { TestUser } from '../../support/users.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const API = process.env.QA_API_URL ?? 'http://localhost:8080';
// tests/lifecycle/support -> e2e/lifecycle/reports
const REPORTS_ROOT = resolve(HERE, '../../../lifecycle/reports');

/**
 * The run id names the report folder and the state file. An explicit LIFECYCLE_RUN_ID wins. Without
 * it, the first load of this module picks a new id and remembers it in `.current-run`, so every
 * worker of the same run (the runner may load the module again after a failed UI step) resumes it.
 */
function resolveRunId(): string {
  if (process.env.LIFECYCLE_RUN_ID) return process.env.LIFECYCLE_RUN_ID;
  mkdirSync(REPORTS_ROOT, { recursive: true });
  const marker = join(REPORTS_ROOT, '.current-run');
  if (existsSync(marker)) return readFileSync(marker, 'utf8').trim();
  const id = `run-${new Date().toISOString().replace(/[:.]/g, '-')}`;
  writeFileSync(marker, id);
  return id;
}

export const RUN_ID = resolveRunId();
export const REPORT_DIR = join(REPORTS_ROOT, RUN_ID);

export type Status = 'PASS' | 'FAIL' | 'BLOCKED' | 'WORKAROUND' | 'NOT_RUN';

export interface StepDef {
  id: string;
  phase: string;
  role: string;
  mode: 'UI' | 'API';
  title: string;
  what: string;
  expected: string;
  needs?: string[];
  workaround?: boolean;
}

export interface StepRecord extends StepDef {
  status: Status;
  actual: string;
  bugs: string[];
  blockedBy: string[];
  error?: string;
  screenshots: string[];
  durationMs: number;
}

type RunState = { projectId?: number; projectName?: string; users: Partial<Record<string, TestUser>>; ids: Record<string, any> };

const STATE_FILE = join(REPORT_DIR, 'state.json');
const STEPS_DIR = join(REPORT_DIR, 'steps');

function loadJson<T>(file: string, fallback: T): T {
  return existsSync(file) ? (JSON.parse(readFileSync(file, 'utf8')) as T) : fallback;
}

/** Step records of this run, reloaded from disk so a resumed worker sees the steps that already ran. */
export const records = new Map<string, StepRecord>();
if (existsSync(STEPS_DIR)) {
  for (const f of readdirSync(STEPS_DIR)) {
    const r = loadJson<StepRecord | null>(join(STEPS_DIR, f), null);
    if (r) records.set(r.id, r);
  }
}

/** Shared run data (users, project id, ids), persisted in the run folder. */
export const state: RunState = loadJson<RunState>(STATE_FILE, { users: {}, ids: {} });

export function saveState(): void {
  mkdirSync(REPORT_DIR, { recursive: true });
  writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
}

/** Known defects and environment blockers, referenced from steps and written into the run report. */
export const CATALOG: Record<string, string> = {
  'BUG-01': 'Project status does not move to OFFERED after the offer is sent (stays DRAFT).',
  'BUG-02': 'Estimate stays editable after the offer is sent (lock not enforced).',
  'BUG-03': 'MANAGER gets 403 on GET /api/signable-document-types, so the contract type list cannot load.',
  'BUG-06': 'A deactivated user can still log in.',
  'BUG-07': 'A CLIENT can log in with a password (the spec says OTP only).',
  'BUG-10': 'POST /api/amendments (documented as creating the OPEN amendment) runs the generic CRUD insert, leaves seq_no null and returns 409 error.data.integrity. The eligibility guard is also skipped.',
  'BUG-09': 'DELETE /api/projects/{id} returns 500 when the project has rooms (FK fk_rooms_project). The FK violation maps to error.internal instead of a 409.',
  'BLK-1': 'Google Cloud Storage credentials (ADC) are expired. Freezing a document PDF fails with 500.',
  'O-1': 'SALESMAN holds SIGNABLE_DOCUMENTS CREATE in the matrix, but the signing guard denies it.',
};

export function snippet(body: unknown): string {
  const s = typeof body === 'string' ? body : JSON.stringify(body);
  return (s ?? '').slice(0, 240);
}

export function begin(def: StepDef): StepRecord {
  const rec: StepRecord = { ...def, status: 'NOT_RUN', actual: '', bugs: [], blockedBy: [], screenshots: [], durationMs: 0 };
  records.set(def.id, rec);
  return rec;
}

/** Steps this one depends on that did not pass (or ran as a documented workaround). */
export function unmetNeeds(def: StepDef): string[] {
  return (def.needs ?? []).filter((n) => {
    const s = records.get(n)?.status;
    return s !== 'PASS' && s !== 'WORKAROUND';
  });
}

export function finish(rec: StepRecord, startedAt: number): void {
  rec.durationMs = Date.now() - startedAt;
  mkdirSync(STEPS_DIR, { recursive: true });
  writeFileSync(join(STEPS_DIR, `${rec.id}.json`), JSON.stringify(rec, null, 2));
  saveState();
}

/** Fails the step with a message that names the bug it hits. */
export function check(rec: StepRecord, ok: boolean, actual: string, bug?: string): asserts ok {
  if (!ok) {
    rec.actual = actual;
    if (bug && !rec.bugs.includes(bug)) rec.bugs.push(bug);
    throw new Error(`${rec.id}: ${actual}${bug ? ` [${bug}]` : ''}`);
  }
}

/** Expects one of the HTTP statuses; records the actual status and body on failure. */
export function expectHttp(
  rec: StepRecord,
  res: { status: number; body: unknown },
  allowed: number[],
  label: string,
  bug?: string,
): void {
  const actual = `${label}: HTTP ${res.status} ${snippet(res.body)}`;
  check(rec, allowed.includes(res.status), actual, bug);
  rec.actual = actual;
}

const tokens = new Map<string, { token: string; at: number }>();

export async function tokenOf(role: string): Promise<string> {
  if (role === 'ADMIN') return adminToken();
  const cached = tokens.get(role);
  if (cached && Date.now() - cached.at < 10 * 60_000) return cached.token;
  const u = state.users[role];
  if (!u) throw new Error(`no provisioned user for role ${role}`);
  if (role === 'CLIENT') {
    const token = await clientOtpToken(u.email);
    tokens.set(role, { token, at: Date.now() });
    return token;
  }
  const r = await raw('POST', '/api/auth/login', { body: JSON.stringify({ email: u.email, password: u.password }) });
  if (r.status !== 200) throw new Error(`login as ${role} failed: HTTP ${r.status} ${snippet(r.body)}`);
  tokens.set(role, { token: r.body.accessToken as string, at: Date.now() });
  return r.body.accessToken as string;
}

/** Calls the API as a role (ADMIN or a provisioned user). Never throws on HTTP status. */
export async function call(role: string, method: string, path: string, body?: unknown) {
  const token = await tokenOf(role);
  return raw(method, path, { token, body: body === undefined ? undefined : JSON.stringify(body) });
}

/** Multipart file upload as a role. Used for signature scans. */
export async function upload(role: string, path: string, bytes: Uint8Array, filename: string, mime: string) {
  const token = await tokenOf(role);
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: mime }), filename);
  const res = await fetch(`${API}${path}`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const text = await res.text();
  let body: unknown = text;
  try {
    body = text ? JSON.parse(text) : undefined;
  } catch {
    // keep text
  }
  return { status: res.status, body };
}

/** Attaches a screenshot of the current screen to the step and copies it into the report folder. */
export async function snap(app: any, rec: StepRecord, label: string): Promise<void> {
  const src: string = await app.screenshot(label);
  const rel = `screenshots/${rec.id}-${label}.png`;
  const dest = join(REPORT_DIR, rel);
  mkdirSync(dirname(dest), { recursive: true });
  copyFileSync(src, dest);
  rec.screenshots.push(rel);
}

/** Writes REPORT.md from the recorded steps. */
export function writeReport(bugTitles: Record<string, string>): void {
  const all = [...records.values()];
  const count = (s: Status) => all.filter((r) => r.status === s).length;
  const lines: string[] = [];
  lines.push(`# Project lifecycle run ${RUN_ID}`, '');
  lines.push(
    `Totals: ${all.length} steps. PASS ${count('PASS')}, WORKAROUND ${count('WORKAROUND')}, FAIL ${count('FAIL')}, BLOCKED ${count('BLOCKED')}.`,
    '',
  );
  lines.push('| ID | Phase | Role | Mode | Step | Status | Bugs | Blocked by | Time (s) |', '|---|---|---|---|---|---|---|---|---|');
  for (const r of all) {
    lines.push(
      `| ${r.id} | ${r.phase} | ${r.role} | ${r.mode} | ${r.title} | ${r.status} | ${r.bugs.join(', ') || '—'} | ${r.blockedBy.join(', ') || '—'} | ${(r.durationMs / 1000).toFixed(1)} |`,
    );
  }
  lines.push('', '## Steps', '');
  for (const r of all) {
    lines.push(`### ${r.id} · ${r.role} · ${r.title}`, '');
    lines.push(`- **Status:** ${r.status}`);
    lines.push(`- **What the user does:** ${r.what}`);
    lines.push(`- **Expected:** ${r.expected}`);
    lines.push(`- **Actual:** ${r.actual || '—'}`);
    if (r.blockedBy.length) lines.push(`- **Blocked by:** ${r.blockedBy.join(', ')}`);
    if (r.bugs.length) lines.push(`- **Bugs:** ${r.bugs.map((b) => `${b} (${bugTitles[b] ?? CATALOG[b] ?? ''})`).join('; ')}`);
    if (r.error) lines.push(`- **Error:** \`${r.error.replace(/\n/g, ' ').slice(0, 400)}\``);
    for (const s of r.screenshots) lines.push(`- ![${r.id}](${s})`);
    lines.push('');
  }
  mkdirSync(REPORT_DIR, { recursive: true });
  writeFileSync(join(REPORT_DIR, 'REPORT.md'), lines.join('\n'));
  writeFileSync(join(REPORT_DIR, 'results.json'), JSON.stringify(all, null, 2));
}
