import { otpSignInUi } from '../../support/client-otp.js';
import { tokenOf } from '../../support/api.js';
import { execFileSync } from 'node:child_process';
import { createUser, deleteUser, runId, type TestUser } from '../../support/users.js';
import { roleIdByCode } from '../../support/api.js';

// FOR-11-02 test support. Everything created here carries the run-id (users `dash+<run>-*`, notes
// `qa-<run>-<n>`, material model `QA-MAT-<run>-<n>`, project `QA-A-<run>`) and is removed by Env.cleanup().
export const API = process.env.QA_API_URL ?? 'http://localhost:8080';
const PG_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';
const PG_USER = process.env.QA_PG_USER ?? 'foremen';
const PG_DB = process.env.QA_PG_DB ?? 'foremen';

export const PURCHASE = '61.37';
export const DELIVERY = '4.21';
export const INTERNAL_OPTION = '59.99';
export const VAT8_ID = 2; // vat_rates: 1=23, 2=8, 3=5, 4=0

export interface Res {
  status: number;
  body: any;
  text: string;
}

export async function http(method: string, path: string, token?: string, body?: unknown, lang?: string): Promise<Res> {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(lang ? { 'Accept-Language': lang } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let parsed: any = text;
  try {
    parsed = text ? JSON.parse(text) : undefined;
  } catch {
    // keep text
  }
  return { status: res.status, body: parsed, text };
}

export function psql(sql: string, vars: Record<string, string> = {}): string {
  const args = ['exec', '-i', PG_CONTAINER, 'psql', '-U', PG_USER, '-d', PG_DB, '-v', 'ON_ERROR_STOP=1', '-At', '-q'];
  for (const [k, v] of Object.entries(vars)) args.push('-v', `${k}=${v}`);
  args.push('-f', '-');
  return execFileSync('docker', args, { input: sql, encoding: 'utf8' }).trim();
}

export function dayOffset(n: number): string {
  // "today" in Europe/Warsaw, shifted by n days, as yyyy-MM-dd
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Warsaw' }).format(new Date());
  const d = new Date(`${parts}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

const tokens = new Map<string, string>();
export async function login(user: { email: string; password: string }): Promise<string> {
  const cached = tokens.get(user.email);
  if (cached) return cached;
  const r = { status: 200, body: { accessToken: await tokenOf(user) } };
  if (r.status !== 200) throw new Error(`login ${user.email} -> ${r.status} ${r.text}`);
  tokens.set(user.email, r.body.accessToken);
  return r.body.accessToken as string;
}

let adminTok: string | undefined;
let adminTokAt = 0;
export async function admin(): Promise<string> {
  // Re-login every 5 minutes: access tokens expire during a long suite run.
  if (!adminTok || Date.now() - adminTokAt > 5 * 60 * 1000) {
    adminTok = await login({
      email: process.env.FOREMEN_ADMIN_EMAIL ?? 'cto+1@loaders.dev',
      password: process.env.FOREMEN_ADMIN_PASSWORD ?? 'alejano123',
    });
    adminTokAt = Date.now();
  }
  return adminTok;
}

// ---- localized error messages (the API returns only the translated text, not the key) ----
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const bundles = new Map<string, Map<string, string>>();
function bundle(lang: string): Map<string, string> {
  let m = bundles.get(lang);
  if (m) return m;
  m = new Map();
  const name = lang === 'pl' ? 'messages.properties' : `messages_${lang}.properties`; // PL is the default bundle
  const candidates = [
    resolve(process.cwd(), `../../foremen-backend/src/main/resources/${name}`),
    resolve(process.cwd(), `foremen-backend/src/main/resources/${name}`),
  ];
  const file = candidates.find((c) => existsSync(c));
  if (!file) throw new Error(`messages_${lang}.properties not found from ${process.cwd()}`);
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const i = line.indexOf('=');
    if (i < 0 || line.trimStart().startsWith('#')) continue;
    const value = line.slice(i + 1).replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
    m.set(line.slice(0, i).trim(), value);
  }
  bundles.set(lang, m);
  return m;
}

/** True when the response is `status` and its message is the translation of `key` (checked in RU and PL). */
export async function errIs(call: (lang: string) => Promise<Res>, status: number, key: string): Promise<string | null> {
  for (const lang of ['ru', 'pl']) {
    const r = await call(lang);
    const expected = bundle(lang).get(key);
    if (expected === undefined) return `no ${lang} translation for ${key}`;
    const prefix = expected.split('{')[0];
    if (r.status !== status) return `${lang}: expected ${status}, got ${r.status} ${r.text.slice(0, 200)}`;
    const msg = String(r.body?.message ?? '');
    if (!msg.startsWith(prefix)) return `${lang}: message "${msg}" is not the translation of ${key} ("${expected}")`;
  }
  return null;
}

/** Confidentiality scan of a client response body: sentinel values, seller name and internal field names. */
export function leaks(text: string): string[] {
  const found: string[] = [];
  for (const n of [PURCHASE, DELIVERY, INTERNAL_OPTION]) {
    if (new RegExp(`(?<![\\d.])${n.replace('.', '\\.')}(?!\\d)`).test(text)) found.push(`value ${n}`);
  }
  if (/SELLER-SENTINEL/i.test(text)) found.push('seller name');
  const keys = text.match(/"[A-Za-z]*(purchase|internal|margin|delivery|seller|needBy|orderBy)[A-Za-z]*"\s*:/gi);
  if (keys) found.push(`keys ${[...new Set(keys)].join(' ')}`);
  return found;
}

export function msgOf(key: string, lang: string): string {
  return bundle(lang).get(key) ?? '';
}

export async function waitFor<T>(fn: () => Promise<T | undefined | null | false>, ms = 8000): Promise<T | undefined> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v as T;
    if (Date.now() > end) return undefined;
    await new Promise((r) => setTimeout(r, 300));
  }
}

/** Signs a user in through the login form (PL UI) and waits until the login form is gone. */
export async function loginUi(
  app: any,
  screen: any,
  user: { email: string; password: string },
  opts: { browser?: any; locale?: 'pl' | 'ru' } = {},
): Promise<void> {
  const { expect } = await import('e2e');
  await app.clearState(); // a previous sign-in of the same test would redirect /login away
  if (opts.browser && opts.locale) {
    await opts.browser.addInitScript(`localStorage.setItem('foremen-locale', '${opts.locale}')`);
  }
  if ((user as any).roleCode === 'CLIENT') { await otpSignInUi(app, screen, user.email, opts.locale ?? 'pl'); return; }
  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(user.email);
  const ru = opts.locale === 'ru';
  await screen.getByRole('textbox', ru ? 'Пароль' : 'Hasło').fill(user.password);
  await screen.getByRole('button', ru ? 'Войти' : 'Zaloguj się').tap();
  await expect(screen.getByTestId('notification-bell')).toBeVisible(); // the top bar exists only when signed in
}

export interface PosOpts {
  block?: number | null;
  sale?: number | null;
  purchase?: number | null;
  qty?: number;
  path?: 'AGREED' | 'PACKAGE' | 'CLIENT_OWN';
  project?: 'A' | 'B';
  seller?: boolean;
}

export interface Pos {
  id: number;
  materialId: number;
  code: string; // P1.. label
}

/** One isolated run: users, projects A (+B), materials and positions. */
export class Env {
  readonly run = runId();
  users: Record<string, TestUser> = {};
  projectA = 0;
  projectB = 0;
  private projects: number[] = [];
  private materials: number[] = [];
  private positions: number[] = [];
  private sellers: number[] = [];
  private seq = 0;
  private sellerId?: number;

  /** Creates manager, client, buyer, foreman (+ manager2, client2 and project B when `second`). */
  static async create(opts: { second?: boolean; buyer?: boolean } = {}): Promise<Env> {
    const env = new Env();
    try {
      const u = env.users;
      u.manager = createUser('MANAGER', 'mgr');
      u.client = createUser('CLIENT', 'cl');
      u.foreman = createUser('FOREMAN', 'fm');
      if (opts.buyer !== false) u.buyer = createUser('BUYER', 'by');
      const rm = await roleIdByCode('MANAGER');
      const rc = await roleIdByCode('CLIENT');
      const rf = await roleIdByCode('FOREMAN');
      env.projectA = await env.makeProject(`QA-A-${env.run}`, [
        { userId: u.manager.id, projectRoleId: rm },
        { userId: u.client.id, projectRoleId: rc },
        { userId: u.foreman.id, projectRoleId: rf },
      ]);
      if (opts.second) {
        u.manager2 = createUser('MANAGER', 'mgr2');
        u.client2 = createUser('CLIENT', 'cl2');
        env.projectB = await env.makeProject(`QA-B-${env.run}`, [
          { userId: u.manager2.id, projectRoleId: rm },
          { userId: u.client2.id, projectRoleId: rc },
        ]);
      }
      return env;
    } catch (e) {
      await env.cleanup();
      throw e;
    }
  }

  private async makeProject(name: string, members: Array<{ userId: number; projectRoleId: number }>): Promise<number> {
    const t = await admin();
    const r = await http('POST', '/api/projects', t, { name, members });
    if (r.status !== 201 && r.status !== 200) throw new Error(`create project -> ${r.status} ${r.text}`);
    const id = r.body.id as number;
    this.projects.push(id);
    // The planner/approval needs an ACTIVE project; the readiness-gated activation is not under test here.
    psql(`UPDATE projects SET status = 'ACTIVE' WHERE id = :pid;`, { pid: String(id) });
    return id;
  }

  async tok(role: string): Promise<string> {
    return login(this.users[role]);
  }

  async material(): Promise<number> {
    this.seq += 1;
    // POST /api/finishing-materials fails with 409 on the local DB (column package_volume is NOT NULL
    // and unmapped; see FINDINGS.md "Blockers"), so the material is a SQL clone of a seeded one.
    const out = psql(
      `INSERT INTO finishing_materials (category_id, material_id, unit_id, model, sku, purchase_price, retail_gross,
                                        retail_net, cost_net, active, package_volume, created_date, created_by)
       VALUES (5, 1, 3, :'model', :'sku', 10, 20, 18, 10, true, 1, NOW(), 'qa') RETURNING id;`,
      { model: `QA-MAT-${this.run}-${this.seq}`, sku: `QA-SKU-${this.run}-${this.seq}` },
    );
    const id = Number(out.split('\n')[0]);
    this.materials.push(id);
    return id;
  }

  /** Sentinel seller `SELLER-SENTINEL-<run>` (created once, direct insert). */
  ensureSeller(): number {
    if (this.sellerId) return this.sellerId;
    const out = psql(
      `INSERT INTO material_sellers (code, name_ru, name_pl, active, created_date, created_by)
       VALUES (:'code', :'name', :'name', true, NOW(), 'qa') RETURNING id;`,
      { code: `qa-seller-${this.run}`, name: `SELLER-SENTINEL-${this.run}` },
    );
    this.sellerId = Number(out.split('\n')[0]);
    this.sellers.push(this.sellerId);
    return this.sellerId;
  }

  async position(label: string, o: PosOpts = {}): Promise<Pos> {
    const materialId = await this.material();
    const project = o.project === 'B' ? this.projectB : this.projectA;
    const sale = o.sale === undefined ? 100 : o.sale;
    const body: Record<string, unknown> = {
      projectId: project,
      kind: 'FINISHING',
      sourcingPath: o.path ?? 'AGREED',
      plannedMaterialId: materialId,
      quantity: o.qty ?? 1,
      notes: `qa-${this.run}-${label}`,
      blockNo: o.block === undefined ? 1 : o.block,
    };
    if (sale !== null) {
      body.salePrice = sale;
      body.saleVatRateId = VAT8_ID;
    }
    if (o.purchase !== null && o.purchase !== undefined) {
      body.plannedPurchasePrice = o.purchase;
      body.purchaseVatRateId = VAT8_ID;
    }
    const r = await http('POST', '/api/purchase-positions', await admin(), body);
    if (r.status !== 201 && r.status !== 200) throw new Error(`create position ${label} -> ${r.status} ${r.text}`);
    const id = r.body.id as number;
    this.positions.push(id);
    if (o.seller) {
      psql(`UPDATE finishing_materials SET seller_id = :sid WHERE id = :mid;`, {
        sid: String(this.ensureSeller()),
        mid: String(materialId),
      });
    }
    return { id, materialId, code: label };
  }

  /** Staff send as MANAGER. */
  async send(ids: number[], deadlineDays = 7, note = `qa-${this.run}`): Promise<Res> {
    return http('POST', '/api/position-approvals/staff/send', await this.tok('manager'), {
      positionIds: ids,
      decisionDeadline: dayOffset(deadlineDays),
      managerNote: note,
    });
  }

  /** The client-visible entry of one position (client view) or undefined. */
  async clientPos(posId: number, role = 'client'): Promise<any> {
    const r = await http('GET', `/api/position-approvals/client/projects/${this.projectA}`, await this.tok(role));
    return Array.isArray(r.body) ? r.body.find((p: any) => p.positionId === posId || p.id === posId) : undefined;
  }

  async staffPos(posId: number): Promise<any> {
    const r = await http('GET', `/api/position-approvals/staff/projects/${this.projectA}`, await this.tok('manager'));
    return Array.isArray(r.body) ? r.body.find((p: any) => p.positionId === posId || p.id === posId) : undefined;
  }

  /** Request as one of the env users (role key). */
  async as(role: string, method: string, path: string, body?: unknown, lang?: string): Promise<Res> {
    return http(method, path, await this.tok(role), body, lang);
  }

  /** Error check run in RU and PL (see errIs). Returns null when OK, a diagnosis otherwise. */
  async err(role: string, method: string, path: string, body: unknown, status: number, key: string): Promise<string | null> {
    return errIs((lang) => this.as(role, method, path, body, lang), status, key);
  }

  /** Open round id (client view) of a position. */
  async roundId(posId: number): Promise<number> {
    const p = await this.clientPos(posId);
    const id = p?.openRound?.roundId;
    if (!id) throw new Error(`position ${posId} has no open round in the client view: ${JSON.stringify(p)}`);
    return id as number;
  }

  async optionId(posId: number, idx = 0): Promise<number> {
    const p = await this.clientPos(posId);
    return p.openRound.options[idx].optionId as number;
  }

  /** Client accepts the open round with the current price. */
  async accept(posId: number, price: number | undefined = 100, role = 'client'): Promise<Res> {
    const p = await this.clientPos(posId, role);
    const body: Record<string, unknown> = { optionId: p.openRound.options[0].optionId };
    if (price !== undefined) body.expectedUnitSalePrice = price;
    return this.as(role, 'POST', `/api/position-approvals/client/rounds/${p.openRound.roundId}/accept`, body);
  }

  /** SQL fixture: force a position stage (only for positions of this run). */
  setStatus(posId: number, status: string): void {
    psql(`UPDATE purchase_positions SET status = :'st' WHERE id = :pid AND notes LIKE :'like';`, {
      st: status,
      pid: String(posId),
      like: `qa-${this.run}%`,
    });
  }

  /** Notifications of a role user: [{type, deepLink, ...}] straight from the table (type, deep_link). */
  notifs(role: string): Array<{ type: string; deepLink: string; body: string }> {
    const out = psql(
      `SELECT type || E'\\t' || COALESCE(deep_link, '') || E'\\t' || COALESCE(body, '') FROM notifications WHERE recipient_id = :uid ORDER BY id;`,
      { uid: String(this.users[role].id) },
    );
    return out
      ? out.split('\n').map((l) => {
          const [type, deepLink, body] = l.split('\t');
          return { type, deepLink, body };
        })
      : [];
  }

  async cleanup(): Promise<void> {
    const ids = this.positions.join(',');
    const mats = this.materials.join(',');
    const userIds = Object.values(this.users).map((u) => u.id).join(',');
    try {
      if (ids) {
        psql(`
          UPDATE position_approval_rounds SET source_suggestion_id = NULL, chosen_option_id = NULL
            WHERE position_id IN (${ids});
          DELETE FROM position_approval_options WHERE round_id IN (SELECT id FROM position_approval_rounds WHERE position_id IN (${ids}));
          DELETE FROM position_approval_rounds WHERE position_id IN (${ids});
          DELETE FROM purchase_position_variants WHERE position_id IN (${ids});
          DELETE FROM purchase_positions WHERE id IN (${ids});`);
      }
      if (userIds) psql(`DELETE FROM notifications WHERE recipient_id IN (${userIds});`);
      if (mats) {
        psql(`DELETE FROM purchase_position_variants WHERE material_id IN (${mats});
              DELETE FROM finishing_materials WHERE id IN (${mats});`);
      }
      if (this.sellers.length) psql(`DELETE FROM material_sellers WHERE id IN (${this.sellers.join(',')});`);
    } finally {
      for (const p of this.projects) {
        try {
          await http('DELETE', `/api/projects/${p}`, await admin());
        } catch {
          // ignore
        }
      }
      // Notification emission is asynchronous: let it settle, then purge again before the users go.
      await new Promise((r) => setTimeout(r, 800));
      if (userIds) psql(`DELETE FROM notifications WHERE recipient_id IN (${userIds});`);
      for (const u of Object.values(this.users)) deleteUser(u);
    }
  }
}
