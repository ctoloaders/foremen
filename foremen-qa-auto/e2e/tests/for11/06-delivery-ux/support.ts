import { tokenOf } from '../../support/api.js';
import { execFileSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import bcrypt from 'bcryptjs';
import { adminToken, roleIdByCode } from '../../support/api.js';
// FOR-11-06 helpers (own copy; shared support files are read-only for this suite).
// Repeatability: a run-id per world; users `for1106+<run-id>-<tag>@example.com`, projects `FOR1106-<run-id>` and
// `FOR1106-OTHER-<run-id>`, external orders `QA06-<run-id>-n`, payment links `https://pay.example.com/<run-id>/n`.
// cleanup() (called in `finally`) removes receipts, delegations, bank rows, orders, proformas, positions, schedules,
// projects, notifications and users of the run. Fixtures REST cannot reach (positions in late stages, inactive
// membership, statement rows) are written by SQL against columns that exist in the pre-FOR-11-06 schema
// (purchase_positions, project_members, bank_*), never against the new client_payments table.
const API = process.env.QA_API_URL ?? 'http://localhost:8080';
const PG_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';
const PG_USER = process.env.QA_PG_USER ?? 'foremen';
const PG_DB = process.env.QA_PG_DB ?? 'foremen';
export interface Res {
  status: number;
  body: any;
  text: string;
  headers: Headers;
}
/** Raw JSON request, never throws on HTTP errors. */
export async function http(method: string, path: string, token?: string, body?: unknown, lang = 'pl'): Promise<Res> {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'Accept-Language': lang,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let parsed: any = text;
  try {
    parsed = text ? JSON.parse(text) : undefined;
  } catch {
    // plain text / binary
  }
  return { status: res.status, body: parsed, text, headers: res.headers };
}
/** Request that must succeed (2xx); throws with the body otherwise. */
export async function ok(method: string, path: string, token: string, body?: unknown): Promise<any> {
  const r = await http(method, path, token, body);
  if (r.status < 200 || r.status >= 300) throw new Error(`${method} ${path} -> ${r.status}: ${r.text.slice(0, 400)}`);
  return r.body;
}
export const get = (path: string, token?: string) => http('GET', path, token);
export const post = (path: string, token: string | undefined, body: unknown = {}) => http('POST', path, token, body);
export const put = (path: string, token: string | undefined, body: unknown) => http('PUT', path, token, body);
export const del = (path: string, token: string | undefined) => http('DELETE', path, token);
/** Binary GET (PDF). */
export async function getBytes(path: string, token: string): Promise<{ status: number; type: string | null; bytes: Buffer }> {
  const res = await fetch(`${API}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  return { status: res.status, type: res.headers.get('content-type'), bytes: Buffer.from(await res.arrayBuffer()) };
}
export function psql(sql: string, vars: Record<string, string> = {}): string {
  const args = ['exec', '-i', PG_CONTAINER, 'psql', '-U', PG_USER, '-d', PG_DB, '-v', 'ON_ERROR_STOP=1', '-At', '-q'];
  for (const [k, v] of Object.entries(vars)) args.push('-v', `${k}=${v}`);
  args.push('-f', '-');
  return execFileSync('docker', args, { input: sql, encoding: 'utf8' }).trim();
}
export const newRunId = (): string => `${Date.now().toString(36)}${randomBytes(2).toString('hex')}`;
export const uuid = (): string => randomUUID();
/** yyyy-mm-dd of today (Europe/Warsaw) shifted by `n` days. */
export function day(n = 0): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Warsaw', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date())
    .split('-')
    .map(Number);
  return new Date(Date.UTC(parts[0]!, parts[1]! - 1, parts[2]! + n)).toISOString().slice(0, 10);
}
/** Valid test IBANs (PL and DE); the API stores them without spaces, upper case. */
export const IBAN_PL = 'PL61109010140000071219812874';
export const IBAN_PL_SPACED = 'PL61 1090 1014 0000 0712 1981 2874';
export const IBAN_DE = 'DE89370400440532013000';
// ---- error keys -----------------------------------------------------------------------------
// The API error body carries only the localized `message`; a key is asserted by matching the PL text of the key in
// the backend's messages.properties ({n} placeholders are wildcards).
let messages: Map<string, string> | undefined;
function loadMessages(): Map<string, string> {
  const path = resolve(process.cwd(), '../../foremen-backend/src/main/resources', 'messages.properties');
  const m = new Map<string, string>();
  for (const line of readFileSync(path, 'utf8').split('\n')) {
    const i = line.indexOf('=');
    if (i > 0 && !line.startsWith('#')) m.set(line.slice(0, i).trim(), line.slice(i + 1).trim());
  }
  return m;
}
/** `null` when the response is `status` and its message is the text of `key`; otherwise a diagnosis string. */
export function errMatches(r: Res, status: number, key: string): string | null {
  messages ??= loadMessages();
  const tpl = messages.get(key);
  if (!tpl) return `unknown message key ${key}`;
  if (r.status !== status) return `${key}: expected HTTP ${status}, got ${r.status}: ${r.text.slice(0, 200)}`;
  const re = new RegExp('^' + tpl.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\{\d+\}/g, '.*') + '$');
  const msg = String(r.body?.message ?? '');
  return re.test(msg) ? null : `${key}: message "${msg}" does not match "${tpl}"`;
}
export async function eventually<T>(read: () => Promise<T | undefined | false>, ms = 8000): Promise<T | undefined> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await read();
    if (v) return v as T;
    if (Date.now() > end) return undefined;
    await new Promise((r) => setTimeout(r, 300));
  }
}
export interface QaUser {
  id: number;
  email: string;
  password: string;
  roleCode: string;
  token: string;
}
export type Tag =
  | 'MANAGER' | 'MANAGER2' | 'CLIENT' | 'CLIENT2' | 'FINANCIER' | 'BUYER' | 'FOREMAN'
  | 'WORKER' | 'WORKER2' | 'WORKERX' | 'WORKERI';
const ROLE_OF: Record<Tag, string> = {
  MANAGER: 'MANAGER', MANAGER2: 'MANAGER', CLIENT: 'CLIENT', CLIENT2: 'CLIENT', FINANCIER: 'FINANCIER', BUYER: 'BUYER',
  FOREMAN: 'FOREMAN', WORKER: 'WORKER', WORKER2: 'WORKER', WORKERX: 'WORKER', WORKERI: 'WORKER',
};
/** Project role of a member (`null` = not a member of any project). */
const PROJECT_ROLE_OF: Partial<Record<Tag, string>> = {
  MANAGER: 'MANAGER', MANAGER2: 'MANAGER', CLIENT: 'CLIENT', CLIENT2: 'CLIENT', FOREMAN: 'FOREMAN',
  WORKER: 'WORKER', WORKER2: 'WORKER', WORKERX: 'WORKER', WORKERI: 'WORKER',
};
/** Members of the main project; MANAGER2 and the `*2`/`X` tags belong to the OTHER project only. */
const MAIN_MEMBERS: Tag[] = ['MANAGER', 'CLIENT', 'FOREMAN', 'WORKER', 'WORKER2', 'WORKERI'];
const OTHER_MEMBERS: Tag[] = ['MANAGER2', 'CLIENT2', 'WORKERX'];
function insertUser(roleCode: string, email: string): { id: number; password: string } {
  const password = `Qa!${randomBytes(6).toString('hex')}`;
  const hash = bcrypt.hashSync(password, 10);
  const out = psql(
    `INSERT INTO users (name, email, phone, role_id, active, locale, status, password_hash, created_date, created_by)
     SELECT 'QA ' || :'email', :'email', NULL, r.id, true, 'pl', 'ACTIVE', :'hash', NOW(), 'qa'
     FROM roles r WHERE r.code = :'role' RETURNING id;`,
    { email, hash, role: roleCode },
  );
  const id = Number(out.split('\n')[0]);
  if (!Number.isFinite(id)) throw new Error(`insertUser(${roleCode}) failed`);
  return { id, password };
}
export interface World {
  id: string;
  admin: string;
  users: Partial<Record<Tag, QaUser>>;
  projectId: number;
  otherProjectId: number;
  sellerId: number;
  materialId: number;
  vat8: number;
  vat23: number;
  /** True when this run created the company profile (and removes it again). */
  profileCreated: boolean;
  seq: number;
  t(tag: Tag | 'ADMIN'): string;
  /** `<prefix>-<run-id>-<n>` unique string for notes, references and links. */
  tag(prefix: string): string;
  /** API-created AGREED position in PLANNED state. */
  plannedPosition(o?: { projectId?: number; qty?: number; sale?: number | null; purchase?: number | null }): Promise<number>;
  /** AGREED position taken to ACCEPTED through send + decide-on-behalf (qty x sale net). */
  acceptedPosition(qty?: number, sale?: number, o?: { projectId?: number }): Promise<number>;
  /** BUYER creates a buyer-added position (WITH_BUYER, client_unpaid). */
  buyerAdded(o?: { qty?: number; price?: number; sale?: number; orderId?: number; projectId?: number }): Promise<Res>;
  /** DRAFT external order of the run's seller with one line per `[positionId, qty, unitPurchaseNet]`. */
  draftOrder(lines: Array<[number, number, number]>, ref?: string): Promise<Res>;
  /** MANAGER creates a draft materials payment and issues it; `mode` tells whether the real issue worked. */
  issuedPayment(positionIds: number[]): Promise<{ id: number; mode: 'api' | 'pdf-unavailable'; draft: any; issued?: Res }>;
  /** Inserts a POSTED credit row on a CASH account owned by the run; returns the transaction id. */
  statementRow(amount: number, title?: string): number;
  /** BUYER creates a shipment (carrier DPD) with `[positionId, qty]` lines; the positions must be IN_DELIVERY-able. */
  shipment(lines: Array<[number, number]>): Promise<number>;
  /** SQL-inserted AGREED position at `SUPPLIER_PAID` (the stage a shipment needs). */
  paidPosition(qty?: number, projectId?: number): number;
  cleanup(): Promise<void>;
}
export interface WorldOpts {
  /** Tags to create in addition to the always-present MANAGER, CLIENT, BUYER, FINANCIER, FOREMAN. */
  tags?: Tag[];
}
const BASE_TAGS: Tag[] = ['MANAGER', 'CLIENT', 'BUYER', 'FINANCIER', 'FOREMAN'];
export async function makeWorld(opts: WorldOpts = {}): Promise<World> {
  const id = newRunId();
  const want = new Set<Tag>([...BASE_TAGS, ...(opts.tags ?? [])]);
  const users: Partial<Record<Tag, QaUser>> = {};
  const created: number[] = [];
  const projectIds: number[] = [];
  const w: World = {
    id, admin: '', users, projectId: 0, otherProjectId: 0, sellerId: 0, materialId: 0, vat8: 0, vat23: 0,
    profileCreated: false, seq: 0,
    t: (tag) => (tag === 'ADMIN' ? w.admin : users[tag]!.token),
    tag: (prefix) => `${prefix}-${id}-${++w.seq}`,
    plannedPosition: async () => 0,
    acceptedPosition: async () => 0,
    buyerAdded: async () => ({ status: 0, body: undefined, text: '', headers: new Headers() }),
    draftOrder: async () => ({ status: 0, body: undefined, text: '', headers: new Headers() }),
    issuedPayment: async () => ({ id: 0, mode: 'api', draft: undefined }),
    statementRow: () => 0,
    shipment: async () => 0,
    paidPosition: () => 0,
    cleanup: async () => undefined,
  };
  const cleanup = async (): Promise<void> => {
    const pl = projectIds.length ? projectIds.join(',') : '0';
    const ul = created.length ? created.join(',') : '0';
    const run = (sql: string): void => {
      try {
        psql(sql);
      } catch (e) {
        console.warn('cleanup sql failed:', String(e).slice(0, 200));
      }
    };
    run(`DELETE FROM bank_transaction_suggestions WHERE transaction_id IN (SELECT id FROM bank_transactions WHERE account_id IN (SELECT id FROM bank_accounts WHERE name LIKE 'qa06-${id}-%'));
         DELETE FROM bank_transaction_links WHERE transaction_id IN (SELECT id FROM bank_transactions WHERE account_id IN (SELECT id FROM bank_accounts WHERE name LIKE 'qa06-${id}-%'));
         DELETE FROM bank_transactions WHERE account_id IN (SELECT id FROM bank_accounts WHERE name LIKE 'qa06-${id}-%');
         DELETE FROM bank_balance_gaps WHERE account_id IN (SELECT id FROM bank_accounts WHERE name LIKE 'qa06-${id}-%');
         DELETE FROM bank_accounts WHERE name LIKE 'qa06-${id}-%';`);
    run(`DELETE FROM audit_log WHERE entity_class = 'GoodsReceiptEntity' AND entity_id IN (SELECT id FROM goods_receipts WHERE project_id IN (${pl}));
         DELETE FROM goods_receipts WHERE project_id IN (${pl});
         DELETE FROM goods_receipt_delegations WHERE project_id IN (${pl});`);
    run(`UPDATE position_approval_rounds SET chosen_option_id = NULL, source_suggestion_id = NULL WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id IN (${pl}));
         DELETE FROM shipments WHERE project_id IN (${pl});
         DELETE FROM external_order_documents WHERE external_order_id IN (SELECT id FROM external_orders WHERE project_id IN (${pl}));
         DELETE FROM external_order_lines WHERE external_order_id IN (SELECT id FROM external_orders WHERE project_id IN (${pl}));
         DELETE FROM external_orders WHERE project_id IN (${pl});
         DELETE FROM client_proforma_lines WHERE proforma_id IN (SELECT id FROM client_proformas WHERE project_id IN (${pl}));
         DELETE FROM client_proformas WHERE project_id IN (${pl});
         DELETE FROM position_approval_options WHERE round_id IN (SELECT r.id FROM position_approval_rounds r JOIN purchase_positions p ON p.id = r.position_id WHERE p.project_id IN (${pl}));
         DELETE FROM position_approval_rounds WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id IN (${pl}));
         DELETE FROM purchase_position_variants WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id IN (${pl}));
         DELETE FROM audit_log WHERE entity_class = 'PurchasePositionEntity' AND entity_id IN (SELECT id FROM purchase_positions WHERE project_id IN (${pl}));
         DELETE FROM purchase_positions WHERE project_id IN (${pl});
         DELETE FROM purchase_requests WHERE project_id IN (${pl});
         DELETE FROM finishing_materials WHERE project_id IN (${pl});`);
    // Rows that block the project delete: tranche allocations (manual mark-paid) and the SQL-seeded rooms
    // (rooms do not cascade from projects; their estimate cells and BoM rows cascade from the room).
    run(`DELETE FROM tranche_allocations WHERE tranche_id IN (SELECT t.id FROM payment_tranches t JOIN payment_schedules s ON s.id = t.schedule_id WHERE s.project_id IN (${pl}));
         DELETE FROM rooms WHERE project_id IN (${pl});`);
    for (const pid of projectIds) {
      try {
        await http('DELETE', `/api/projects/${pid}`, w.admin);
      } catch (e) {
        console.warn('deleteProject failed', pid, e);
      }
    }
    run(`DELETE FROM notifications WHERE recipient_id IN (${ul});`);
    run(`DELETE FROM material_sellers WHERE code = 'qa06_${id}';`);
    for (const uid of created) {
      try {
        psql(
          `DELETE FROM notifications WHERE recipient_id = :uid;
           DELETE FROM project_members WHERE user_id = :uid;
           DELETE FROM refresh_tokens WHERE user_id = :uid;
           DELETE FROM invite_tokens WHERE user_id = :uid;
           DELETE FROM password_reset_tokens WHERE user_id = :uid;
           DELETE FROM users WHERE id = :uid;`,
          { uid: String(uid) },
        );
      } catch (e) {
        console.warn('delete user failed', uid, String(e).slice(0, 150));
      }
    }
    if (w.profileCreated) run('DELETE FROM company_profile;');
  };
  w.cleanup = cleanup;
  try {
    w.admin = await adminToken();
    for (const tag of want) {
      const email = `for1106+${id}-${tag.toLowerCase()}@example.com`;
      const u = insertUser(ROLE_OF[tag], email);
      created.push(u.id);
      users[tag] = { id: u.id, email, password: u.password, roleCode: ROLE_OF[tag], token: '' };
    }
    for (const tag of want) {
      const r = { status: 200, body: { accessToken: await tokenOf(users[tag]!) } };
      if (r.status !== 200) throw new Error(`login ${tag} -> ${r.status}`);
      users[tag]!.token = r.body.accessToken as string;
    }
    const member = async (tag: Tag) => ({ userId: users[tag]!.id, projectRoleId: await roleIdByCode(PROJECT_ROLE_OF[tag]!) });
    // The API refuses the WORKER role while a project is created: workers are assigned afterwards.
    const mainMembers = [];
    const mainWorkers: Tag[] = [];
    for (const tag of MAIN_MEMBERS) if (users[tag]) (ROLE_OF[tag] === 'WORKER' ? mainWorkers.push(tag) : mainMembers.push(await member(tag)));
    const otherMembers = [];
    const otherWorkers: Tag[] = [];
    for (const tag of OTHER_MEMBERS) if (users[tag]) (ROLE_OF[tag] === 'WORKER' ? otherWorkers.push(tag) : otherMembers.push(await member(tag)));
    w.projectId = (await ok('POST', '/api/projects', w.admin, { name: `FOR1106-${id}`, status: 'ACTIVE', members: mainMembers })).id;
    projectIds.push(w.projectId);
    w.otherProjectId = (await ok('POST', '/api/projects', w.admin, { name: `FOR1106-OTHER-${id}`, status: 'ACTIVE', members: otherMembers })).id;
    projectIds.push(w.otherProjectId);
    for (const [workers, pid] of [[mainWorkers, w.projectId], [otherWorkers, w.otherProjectId]] as Array<[Tag[], number]>) {
      for (const tag of workers) {
        await ok('POST', '/api/project-members', w.admin, { userId: users[tag]!.id, projectId: pid, projectRoleId: await roleIdByCode('WORKER') });
      }
    }
    psql(`UPDATE projects SET start_date = :'d'::date WHERE id IN (${projectIds.join(',')});`, { d: day(0) });
    if (users.WORKERI) {
      psql(`UPDATE project_members SET assignment_status = 'INACTIVE' WHERE user_id = :uid AND project_id = :pid;`, {
        uid: String(users.WORKERI.id), pid: String(w.projectId),
      });
    }
    const vats = (await ok('GET', '/api/vat-rates?size=50', w.admin)).content as Array<{ id: number; rate: number }>;
    w.vat8 = vats.find((v) => Number(v.rate) === 8)!.id;
    w.vat23 = vats.find((v) => Number(v.rate) === 23)!.id;
    w.materialId = (await ok('GET', '/api/finishing-materials?size=1', w.admin)).content[0].id;
    const sellerName = `QA06-${id}-SELLER`;
    w.sellerId = (await ok('POST', '/api/material-sellers', w.admin, { code: `qa06_${id}`, nameRU: sellerName, namePL: sellerName, nip: nipFor(id) })).id;
    // Company profile: needed to issue a payment. Created only when the table is empty; removed in cleanup.
    if (Number(psql('SELECT COUNT(*) FROM company_profile;')) === 0) {
      await ok('PUT', '/api/company-profile', w.admin, {
        fullName: `QA06 Company ${id}`, registeredAddress: 'ul. Testowa 1, 00-001 Warszawa', nip: '5252248481', regon: null,
        representativeName: 'QA Rep', representativeRole: 'CEO', email: null, bankAccount: IBAN_PL,
      });
      w.profileCreated = true;
    }
  } catch (e) {
    await cleanup();
    throw e;
  }
  w.plannedPosition = async (o = {}) => {
    const sale = o.sale === undefined ? 100 : o.sale;
    const r = await ok('POST', '/api/purchase-positions', w.admin, {
      projectId: o.projectId ?? w.projectId,
      kind: 'FINISHING',
      sourcingPath: 'AGREED',
      plannedMaterialId: w.materialId,
      quantity: o.qty ?? 10,
      salePrice: sale,
      plannedPurchasePrice: o.purchase === undefined ? null : o.purchase,
      saleVatRateId: w.vat8,
      purchaseVatRateId: w.vat23,
      notes: w.tag('qa06'),
    });
    return r.id as number;
  };
  w.acceptedPosition = async (qty = 10, sale = 100, o = {}) => {
    const pid = await w.plannedPosition({ qty, sale, projectId: o.projectId });
    await ok('POST', '/api/position-approvals/staff/send', w.admin, { positionIds: [pid], decisionDeadline: null, managerNote: w.tag('qa06') });
    const round = (await ok('GET', `/api/position-approvals/staff/positions/${pid}/rounds`, w.admin))[0];
    const option = round.options[0];
    await ok('POST', `/api/position-approvals/staff/rounds/${round.roundId}/decide-on-behalf`, w.admin, {
      decision: 'ACCEPT', optionId: option.optionId, expectedUnitSalePrice: option.unitSalePrice, comment: w.tag('qa06'),
    });
    return pid;
  };
  w.buyerAdded = (o = {}) =>
    http('POST', '/api/purchase-positions/buyer-added', w.t('BUYER'), {
      projectId: o.projectId ?? w.projectId,
      plannedMaterialId: w.materialId,
      quantity: o.qty ?? 10,
      plannedPurchasePrice: o.price ?? 60,
      salePrice: o.sale ?? 100,
      saleVatRateId: w.vat8,
      purchaseVatRateId: w.vat23,
      externalOrderId: o.orderId,
      note: w.tag('qa06'),
    });
  w.draftOrder = (lines, ref) =>
    http('POST', '/api/external-orders', w.t('BUYER'), {
      projectId: w.projectId,
      sellerId: w.sellerId,
      header: { referenceNumber: ref ?? `QA06-${id}-${++w.seq}`, deliveryCostNet: 0, deliveryVatRateId: w.vat23 },
      lines: lines.map(([positionId, quantity, unitPurchaseNet]) => ({ positionId, quantity, unitPurchaseNet, aboveCeilingAcknowledged: true })),
    });
  w.issuedPayment = async (positionIds) => {
    const draft = await ok('POST', '/api/client-payments/materials', w.t('MANAGER'), {
      projectId: w.projectId, positionIds, dueDate: day(7), note: w.tag('qa06'),
    });
    const issued = await http('POST', `/api/client-payments/${draft.id}/issue`, w.t('MANAGER'), { language: 'pl' });
    return { id: draft.id as number, mode: issued.status === 200 ? 'api' : 'pdf-unavailable', draft, issued };
  };
  let cashAccount = 0;
  w.statementRow = (amount, title = 'payment') => {
    if (!cashAccount) {
      cashAccount = Number(
        psql(
          `INSERT INTO bank_accounts (type, name, currency, included_in_ledger, default_for_cash, created_by)
           VALUES ('CASH', :'name', 'PLN', true, false, 'qa') RETURNING id;`,
          { name: `qa06-${id}-acc` },
        ).split('\n')[0],
      );
    }
    return Number(
      psql(
        `INSERT INTO bank_transactions (account_id, channel, origin, status, amount, currency, operation_date, booking_date, title, created_by)
         VALUES (:acc, 'CASH', 'MANUAL', 'POSTED', :amt, 'PLN', CURRENT_DATE, CURRENT_DATE, :'title', 'qa') RETURNING id;`,
        { acc: String(cashAccount), amt: String(amount), title: `qa-${id} ${title}` },
      ).split('\n')[0],
    );
  };
  w.paidPosition = (qty = 3, projectId) => {
    const out = psql(
      `INSERT INTO purchase_positions (project_id, kind, sourcing_path, status, planned_material_id, quantity, unit_id, ordered_quantity, created_by)
       VALUES (:'pid'::bigint, 'FINISHING', 'AGREED', 'SUPPLIER_PAID', :'mat'::bigint, :'qty'::numeric, 1, :'qty'::numeric, 'qa') RETURNING id;`,
      { pid: String(projectId ?? w.projectId), mat: String(w.materialId), qty: String(qty) },
    );
    return Number(out.split('\n')[0]);
  };
  w.shipment = async (lines) => {
    const r = await http('POST', '/api/shipments', w.t('BUYER'), {
      projectId: w.projectId,
      deliveryMethod: 'CARRIER',
      carrierCode: 'DPD',
      trackingNumber: `DPD${id.toUpperCase()}${Math.floor(Math.random() * 1e6)}`,
      lines: lines.map(([positionId, quantity]) => ({ positionId, quantity })),
    });
    if (r.status !== 201) throw new Error(`create shipment -> ${r.status} ${r.text.slice(0, 300)}`);
    return r.body.id as number;
  };
  return w;
}
/** Valid-looking NIP (checksum is not enforced by the seller form in the API). */
function nipFor(rid: string): string {
  const wts = [6, 5, 7, 2, 3, 4, 5, 6, 7];
  const seed = parseInt(rid.slice(0, 7), 36);
  for (let attempt = 0; attempt < 50; attempt++) {
    const d = String(100000000 + ((seed + attempt) % 899999999)).split('').map(Number);
    const sum = d.reduce((s, x, i) => s + x * wts[i]!, 0) % 11;
    if (sum !== 10) return d.join('') + sum;
  }
  return '5252000000';
}
export async function notificationsOf(token: string): Promise<Array<{ id: number; type: string; deepLink: string | null; read: boolean }>> {
  const r = await get('/api/notifications?size=100', token);
  return Array.isArray(r.body) ? r.body : (r.body?.content ?? []);
}
export const notesOfType = async (token: string, type: string) => (await notificationsOf(token)).filter((n) => n.type === type);
/** Pages of the unified list as an array of rows. */
export const rowsOf = (r: Res): any[] => (r.body?.content ?? []) as any[];
export const listPayments = (w: World, tag: Tag | 'ADMIN', query = '') =>
  get(`/api/client-payments?projectId=${w.projectId}${query ? `&${query}` : ''}`, w.t(tag));
/** Position DTO as the admin sees it. */
export const positionOf = async (w: World, id: number) => (await ok('GET', `/api/purchase-positions/${id}`, w.admin)) as any;
/**
 * SQL-seeds the estimate basis REST cannot build: an estimate (PLN) and an APPROVED offer of `totalGross`.
 * Both rows belong to the project and vanish with it (ON DELETE CASCADE), so cleanup needs nothing extra.
 */
export function seedApprovedOffer(w: World, totalGross = 12300, projectId = w.projectId): void {
  const net = Math.round((totalGross / 1.23) * 100) / 100;
  psql(
    `INSERT INTO estimates (project_id, currency_id, status, total_net, total_vat, total_gross, created_by)
     SELECT :'pid'::bigint, c.id, 'DRAFT', :'net'::numeric, :'vat'::numeric, :'gross'::numeric, 'qa' FROM currencies c WHERE c.code = 'PLN'
     ON CONFLICT (project_id) DO NOTHING;
     INSERT INTO offers (project_id, estimate_id, revision, status, approved_revision, total_net, total_vat, total_gross, created_by)
     SELECT :'pid'::bigint, e.id, 1, 'APPROVED', 1, :'net'::numeric, :'vat'::numeric, :'gross'::numeric, 'qa' FROM estimates e WHERE e.project_id = :'pid'::bigint;`,
    { pid: String(projectId), gross: String(totalGross), net: String(net), vat: String(Math.round((totalGross - net) * 100) / 100) },
  );
}
export interface BomRow {
  /** `materialRow` has a chosen concrete material; `placeholder` has only a finishing type and a price range. */
  kind: 'materialRow' | 'placeholder';
  /** Material of the chosen row (finishing_materials.id). Its type is the row's finishing type. */
  materialId?: number;
  /** Finishing type of a placeholder row (material_types.id). */
  typeId?: number;
  /** Area / quantity of the room cell the norm is applied to. */
  roomQty?: number;
  /** Norm per unit. */
  norm?: number;
}
/**
 * SQL-seeds finishing BoM rows (estimate line, room cell, `estimate_line_room_materials`) of the run's project and
 * returns their ids, so a test can change or delete them. The estimate cascades from the project; the rooms do
 * not, so `cleanup()` deletes them explicitly (their cells and BoM rows cascade from the room).
 */
export function seedFinishingBom(w: World, rows: BomRow[], opts: { workItemId?: number } = {}): { roomId: number; lineId: number; bomIds: number[]; cellIds: number[] } {
  seedEstimateOnly(w);
  // One room per BoM row: the matrix keeps a single cell per (line, room).
  let roomSeq = 0;
  const newRoom = (): number =>
    Number(
      psql(
        `INSERT INTO rooms (project_id, room_type_id, label, created_by)
         SELECT :'pid'::bigint, (SELECT id FROM room_types ORDER BY id LIMIT 1), :'label', 'qa' RETURNING id;`,
        { pid: String(w.projectId), label: `QA06-${w.id}-room${++roomSeq}` },
      ).split('\n')[0],
    );
  const roomId = newRoom();
  const lineId = Number(
    psql(
      `INSERT INTO estimate_lines (estimate_id, work_item_id, unit_id, unit_price, quantity, value_net, created_by)
       SELECT e.id, COALESCE(NULLIF(:'wi'::bigint, 0), (SELECT id FROM work_items ORDER BY id LIMIT 1)), 1, 10, 10, 100, 'qa' FROM estimates e WHERE e.project_id = :'pid'::bigint RETURNING id;`,
      { pid: String(w.projectId), wi: String(opts.workItemId ?? 0) },
    ).split('\n')[0],
  );
  const bomIds: number[] = [];
  const cellIds: number[] = [];
  for (const [i, row] of rows.entries()) {
    const cell = Number(
      psql(
        `INSERT INTO estimate_line_room_qty (line_id, room_id, quantity, created_by) VALUES (:'line'::bigint, :'room'::bigint, :'qty'::numeric, 'qa') RETURNING id;`,
        { line: String(lineId), room: String(i === 0 ? roomId : newRoom()), qty: String(row.roomQty ?? 10) },
      ).split('\n')[0],
    );
    cellIds.push(cell);
    const chosen = row.kind === 'materialRow';
    const id = Number(
      psql(
        chosen
          ? `INSERT INTO estimate_line_room_materials (room_qty_id, branch, finishing_type_id, norm_qty, concrete_finishing_material_id, concrete_net, created_by)
             SELECT :'cell'::bigint, 'finishing', m.type_id, :'norm'::numeric, m.id, 50, 'qa' FROM finishing_materials m WHERE m.id = :'mat'::bigint RETURNING id;`
          : `INSERT INTO estimate_line_room_materials (room_qty_id, branch, finishing_type_id, norm_qty, range_min, range_max, created_by)
             VALUES (:'cell'::bigint, 'finishing', :'type'::bigint, :'norm'::numeric, 20, 80, 'qa') RETURNING id;`,
        { cell: String(cell), norm: String(row.norm ?? 1), mat: String(row.materialId ?? 0), type: String(row.typeId ?? 0) },
      ).split('\n')[0],
    );
    bomIds.push(id);
  }
  return { roomId, lineId, bomIds, cellIds };
}
/** Creates the project's estimate (PLN) when it does not exist yet. */
export function seedEstimateOnly(w: World, projectId = w.projectId): void {
  psql(
    `INSERT INTO estimates (project_id, currency_id, status, total_net, total_vat, total_gross, created_by)
     SELECT :'pid'::bigint, c.id, 'DRAFT', 0, 0, 0, 'qa' FROM currencies c WHERE c.code = 'PLN'
     ON CONFLICT (project_id) DO NOTHING;`,
    { pid: String(projectId) },
  );
}
/** Distinct finishing materials of two different types, plus a third type id for a placeholder row. */
export function finishingMaterialsOfDistinctTypes(): Array<{ id: number; typeId: number }> {
  const out = psql(
    `SELECT DISTINCT ON (type_id) id, type_id FROM finishing_materials WHERE project_id IS NULL AND active ORDER BY type_id, id LIMIT 3;`,
  );
  return out.split('\n').filter(Boolean).map((l) => {
    const [id, typeId] = l.split('|').map(Number);
    return { id: id!, typeId: typeId! };
  });
}
/**
 * Creates the works side of the run's project through the API: a MANUAL schedule with three tranches
 * (past, next, far). Publishing needs an estimate basis in some stacks, so it is attempted and its status
 * returned; the tranches are listed either way.
 */
export async function worksSchedule(w: World): Promise<{ scheduleId: number; published: boolean }> {
  seedApprovedOffer(w, 6000);
  const created = await ok('POST', '/api/client-payments/schedules', w.admin, { projectId: w.projectId, source: 'MANUAL' });
  const scheduleId = created.id as number;
  for (const [amount, due] of [[1000, day(-5)], [2000, day(10)], [3000, day(40)]] as Array<[number, string]>) {
    await ok('POST', `/api/client-payments/schedules/${scheduleId}/tranches`, w.admin, { amountGross: amount, dueDate: due });
  }
  const pub = await http('POST', `/api/client-payments/schedules/${scheduleId}/publish`, w.admin);
  if (pub.status !== 200) console.warn(`publish -> ${pub.status}: ${pub.text.slice(0, 300)}`);
  return { scheduleId, published: pub.status === 200 };
}

/** A work item that consumes a finishing type (tier-1 link type -> work category), with two catalog materials of that type. */
export function consumptionPick(): { workItemId: number; categoryId: number; typeId: number; placeholderTypeId: number; materialIds: number[] } {
  // A work item that consumes two finishing types: the first has two catalog materials, the second is the placeholder
  // (the norm unit of a placeholder comes from the consumption row of its type).
  const row = psql(
    `SELECT c.work_item_id, wi.work_category_id, c.finishing_material_type_id,
            (SELECT MIN(c2.finishing_material_type_id) FROM work_material_consumptions c2
              WHERE c2.work_item_id = c.work_item_id AND c2.branch = 'finishing' AND c2.finishing_material_type_id <> c.finishing_material_type_id)
     FROM work_material_consumptions c JOIN work_items wi ON wi.id = c.work_item_id
     WHERE c.branch = 'finishing'
       AND (SELECT COUNT(*) FROM finishing_materials m WHERE m.type_id = c.finishing_material_type_id AND m.project_id IS NULL AND m.active) >= 2
       AND EXISTS (SELECT 1 FROM work_material_consumptions c3 WHERE c3.work_item_id = c.work_item_id AND c3.branch = 'finishing' AND c3.finishing_material_type_id <> c.finishing_material_type_id)
     ORDER BY c.work_item_id, c.finishing_material_type_id LIMIT 1;`,
  ).split('\n')[0]!;
  const [workItemId, categoryId, typeId, placeholderTypeId] = row.split('|').map(Number);
  const mats = psql(`SELECT id FROM finishing_materials WHERE type_id = ${typeId} AND project_id IS NULL AND active ORDER BY id LIMIT 2;`)
    .split('\n').filter(Boolean).map(Number);
  return { workItemId: workItemId!, categoryId: categoryId!, typeId: typeId!, placeholderTypeId: placeholderTypeId!, materialIds: mats };
}
/** Gives the project a start date and a schedule bar of `categoryId` starting on calendar day `dayNo` (day 1 = start date). Returns the bar's date. */
export function seedWorkStart(w: World, categoryId: number, dayNo = 6): string {
  const start = day(3);
  psql(`UPDATE projects SET start_date = :'d'::date WHERE id = ${w.projectId};`, { d: start });
  psql(
    `INSERT INTO project_schedules (project_id, version, created_by) VALUES (:'pid'::bigint, 0, 'qa') ON CONFLICT DO NOTHING;
     INSERT INTO project_schedule_bars (schedule_id, work_category_id, start_half_days, duration_half_days, created_by)
     SELECT s.id, :'cat'::bigint, :'half'::int, 4, 'qa' FROM project_schedules s WHERE s.project_id = :'pid'::bigint;`,
    { pid: String(w.projectId), cat: String(categoryId), half: String(dayNo * 2 - 1) },
  );
  return day(3 + dayNo - 1);
}
/**
 * Copies a catalog finishing material into a project-owned one with a photo key (no catalog material has a photo).
 * The copy belongs to the run's project and is removed by `cleanup()` (`finishing_materials WHERE project_id`).
 */
export function seedPhotoMaterial(w: World, fromMaterialId: number): { id: number; model: string } {
  const model = `QA06-${w.id}-photo`;
  const id = Number(
    psql(
      `INSERT INTO finishing_materials (category_id, material_id, type_id, producer_id, unit_id, model, sku, purchase_price, photo, active, package_volume, cost_net, project_id, created_by)
       SELECT category_id, material_id, type_id, producer_id, unit_id, :'model', NULL, purchase_price, :'photo', true, package_volume, cost_net, :'pid'::bigint, 'qa'
       FROM finishing_materials WHERE id = :'src'::bigint RETURNING id;`,
      { model, photo: `qa06/${w.id}.png`, pid: String(w.projectId), src: String(fromMaterialId) },
    ).split('\n')[0],
  );
  return { id, model };
}
