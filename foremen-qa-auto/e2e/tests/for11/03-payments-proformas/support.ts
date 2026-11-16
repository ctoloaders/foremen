import { execFileSync } from 'node:child_process';
import { adminToken, roleIdByCode } from '../../support/api.js';
import { createUser, deleteUser, runId, type TestUser } from '../../support/users.js';

// Helpers for FOR-11-03 (proformas, external orders, ledger links). Local to this folder.
const API = process.env.QA_API_URL ?? 'http://localhost:8080';
const PG_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';

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
    // binary / plain text
  }
  return { status: res.status, body: parsed, text, headers: res.headers };
}

/** Request that must succeed (2xx); throws with the body otherwise. */
export async function ok(method: string, path: string, token: string, body?: unknown): Promise<any> {
  const r = await http(method, path, token, body);
  if (r.status < 200 || r.status >= 300) throw new Error(`${method} ${path} -> ${r.status}: ${r.text.slice(0, 400)}`);
  return r.body;
}

/** Binary GET (PDF). */
export async function getBytes(path: string, token: string): Promise<{ status: number; type: string | null; bytes: Buffer }> {
  const res = await fetch(`${API}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  return { status: res.status, type: res.headers.get('content-type'), bytes: Buffer.from(await res.arrayBuffer()) };
}

export function psql(sql: string, vars: Record<string, string> = {}): string {
  const args = ['exec', '-i', PG_CONTAINER, 'psql', '-U', 'foremen', '-d', 'foremen', '-v', 'ON_ERROR_STOP=1', '-At', '-q'];
  for (const [k, v] of Object.entries(vars)) args.push('-v', `${k}=${v}`);
  args.push('-f', '-');
  return execFileSync('docker', args, { input: sql, encoding: 'utf8' }).trim();
}

export async function login(user: { email: string; password: string }): Promise<string> {
  const r = await http('POST', '/api/auth/login', undefined, { email: user.email, password: user.password });
  if (r.status !== 200) throw new Error(`login ${user.email} -> ${r.status}`);
  return r.body.accessToken as string;
}

export interface Ctx {
  rid: string;
  projectId: number;
  projectName: string;
  otherProjectId?: number;
  users: Record<'mgr' | 'client' | 'buyer' | 'fin', TestUser>;
  tok: Record<'admin' | 'mgr' | 'client' | 'buyer' | 'fin', string>;
  sellerId: number;
  sellerName: string;
  sellerNip: string;
  materialId: number;
  vat8: number;
  vat23: number;
  accountId: number;
  extraUsers: TestUser[];
  profileCreated: boolean;
  seq: number;
}

/** Valid-looking NIP (checksum is not enforced by the seller form in the API). */
function nipFor(rid: string): string {
  const w = [6, 5, 7, 2, 3, 4, 5, 6, 7];
  let seed = parseInt(rid.slice(0, 7), 36);
  for (let attempt = 0; attempt < 50; attempt++) {
    const d = String(100000000 + ((seed + attempt) % 899999999)).split('').map(Number);
    const sum = d.reduce((s, x, i) => s + x * w[i], 0) % 11;
    if (sum !== 10) return d.join('') + sum;
  }
  return '5252000000';
}

/**
 * Builds the whole run fixture: users MANAGER/CLIENT/BUYER/FINANCIER, an ACTIVE project (MANAGER and
 * CLIENT members), a seller, a CASH bank account (SQL), and a complete company profile (only when the
 * table is empty; removed again in teardown).
 */
export async function setup(opts: { second?: boolean; mgrInBoth?: boolean } = {}): Promise<Ctx> {
  const rid = runId();
  const admin = await adminToken();
  const users = {
    mgr: createUser('MANAGER', `p03m`),
    client: createUser('CLIENT', `p03c`),
    buyer: createUser('BUYER', `p03b`),
    fin: createUser('FINANCIER', `p03f`),
  };
  const ctx: Ctx = {
    rid,
    projectId: 0,
    projectName: `QA03-${rid}`,
    users,
    tok: { admin, mgr: '', client: '', buyer: '', fin: '' },
    sellerId: 0,
    sellerName: `QA03-${rid}-SELLER`,
    sellerNip: nipFor(rid),
    materialId: 0,
    vat8: 0,
    vat23: 0,
    accountId: 0,
    extraUsers: [],
    profileCreated: false,
    seq: 0,
  };
  try {
    ctx.tok.mgr = await login(users.mgr);
    ctx.tok.client = await login(users.client);
    ctx.tok.buyer = await login(users.buyer);
    ctx.tok.fin = await login(users.fin);

    const members = [
      { userId: users.mgr.id, projectRoleId: await roleIdByCode('MANAGER') },
      { userId: users.client.id, projectRoleId: await roleIdByCode('CLIENT') },
    ];
    ctx.projectId = (await ok('POST', '/api/projects', admin, { name: ctx.projectName, status: 'ACTIVE', members })).id;
    if (opts.second) {
      const otherClient = createUser('CLIENT', 'p03c2');
      const otherMgr = createUser('MANAGER', 'p03m2');
      ctx.extraUsers.push(otherClient, otherMgr);
      ctx.otherProjectId = (await ok('POST', '/api/projects', admin, { name: `${ctx.projectName}-B`, status: 'ACTIVE', members: [
        { userId: opts.mgrInBoth ? users.mgr.id : otherMgr.id, projectRoleId: members[0].projectRoleId },
        { userId: otherClient.id, projectRoleId: members[1].projectRoleId },
      ] })).id;
    }
    const vats = (await ok('GET', '/api/vat-rates?size=50', admin)).content as Array<{ id: number; rate: number }>;
    ctx.vat8 = vats.find((v) => Number(v.rate) === 8)!.id;
    ctx.vat23 = vats.find((v) => Number(v.rate) === 23)!.id;
    ctx.materialId = (await ok('GET', '/api/finishing-materials?size=1', admin)).content[0].id;
    ctx.sellerId = (
      await ok('POST', '/api/material-sellers', admin, {
        code: `qa03_${rid}`,
        nameRU: ctx.sellerName,
        namePL: ctx.sellerName,
        nip: ctx.sellerNip,
      })
    ).id;
    ctx.accountId = Number(
      psql(
        `INSERT INTO bank_accounts (type, name, currency, included_in_ledger, default_for_cash, created_by)
         VALUES ('CASH', :'name', 'PLN', true, false, 'qa') RETURNING id;`,
        { name: `qa03-${rid}-acc` },
      ).split('\n')[0],
    );
    // Company profile: needed to issue a proforma. Created only when absent; removed in teardown.
    const cnt = Number(psql('SELECT COUNT(*) FROM company_profile;'));
    if (cnt === 0) {
      await ok('PUT', '/api/company-profile', admin, companyProfile(rid));
      ctx.profileCreated = true;
    }
  } catch (e) {
    await teardown(ctx);
    throw e;
  }
  return ctx;
}

export function companyProfile(rid: string, bankAccount: string | null = 'PL61109010140000071219812874') {
  return {
    fullName: `QA03 Company ${rid}`,
    registeredAddress: 'ul. Testowa 1, 00-001 Warszawa',
    nip: '5252248481',
    regon: null,
    representativeName: 'QA Rep',
    representativeRole: 'CEO',
    email: null,
    bankAccount,
  };
}

/** Removes everything the run created, in dependency order. Never throws. */
export async function teardown(ctx: Ctx): Promise<void> {
  const pids = [ctx.projectId, ctx.otherProjectId].filter(Boolean) as number[];
  const sql = (s: string, v: Record<string, string> = {}) => {
    try {
      psql(s, v);
    } catch (e) {
      console.warn('teardown sql failed:', String(e).slice(0, 200));
    }
  };
  for (const pid of pids) {
    sql(
      `DELETE FROM bank_transaction_suggestions WHERE client_proforma_id IN (SELECT id FROM client_proformas WHERE project_id = :pid)
         OR external_order_id IN (SELECT id FROM external_orders WHERE project_id = :pid);
       DELETE FROM bank_transaction_links WHERE client_proforma_id IN (SELECT id FROM client_proformas WHERE project_id = :pid)
         OR external_order_id IN (SELECT id FROM external_orders WHERE project_id = :pid) OR project_id = :pid;`,
      { pid: String(pid) },
    );
  }
  {
    const acc = `(SELECT id FROM bank_accounts WHERE name LIKE :'p')`;
    sql(
      `DELETE FROM bank_transaction_suggestions WHERE transaction_id IN (SELECT id FROM bank_transactions WHERE account_id IN ${acc});
       DELETE FROM bank_transaction_links WHERE transaction_id IN (SELECT id FROM bank_transactions WHERE account_id IN ${acc});
       DELETE FROM bank_transactions WHERE account_id IN ${acc};
       DELETE FROM bank_balance_gaps WHERE account_id IN ${acc};
       DELETE FROM bank_accounts WHERE name LIKE :'p';
       DELETE FROM bank_connections WHERE display_name LIKE :'p';`,
      { p: `qa03-${ctx.rid}-%` },
    );
  }
  for (const pid of pids) {
    sql(
      `UPDATE position_approval_rounds SET chosen_option_id = NULL WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id = :pid);
       DELETE FROM shipments WHERE project_id = :pid;
       DELETE FROM external_order_documents WHERE external_order_id IN (SELECT id FROM external_orders WHERE project_id = :pid);
       DELETE FROM external_order_lines WHERE external_order_id IN (SELECT id FROM external_orders WHERE project_id = :pid);
       DELETE FROM external_orders WHERE project_id = :pid;
       DELETE FROM client_proforma_lines WHERE proforma_id IN (SELECT id FROM client_proformas WHERE project_id = :pid);
       DELETE FROM client_proformas WHERE project_id = :pid;
       DELETE FROM position_approval_options WHERE round_id IN (SELECT r.id FROM position_approval_rounds r JOIN purchase_positions p ON p.id = r.position_id WHERE p.project_id = :pid);
       UPDATE position_approval_rounds SET source_suggestion_id = NULL WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id = :pid);
       DELETE FROM position_approval_rounds WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id = :pid);
       DELETE FROM purchase_position_variants WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id = :pid);
       DELETE FROM purchase_positions WHERE project_id = :pid;
       DELETE FROM purchase_requests WHERE project_id = :pid;`,
      { pid: String(pid) },
    );
  }
  for (const pid of pids) {
    try {
      await http('DELETE', `/api/projects/${pid}`, ctx.tok.admin);
    } catch {
      /* ignore */
    }
  }
  sql(`DELETE FROM procurement_documents WHERE raw_object_key LIKE :'p';`, { p: `qa03-${ctx.rid}/%` });
  sql(`DELETE FROM material_sellers WHERE code = :'code';`, { code: `qa03_${ctx.rid}` });
  const ids = [...Object.values(ctx.users), ...ctx.extraUsers].map((u) => u.id);
  if (ids.length) {
    sql(`DELETE FROM notifications WHERE recipient_id = ANY(string_to_array(:'ids', ',')::bigint[]);`, { ids: ids.join(',') });
  }
  for (const u of [...Object.values(ctx.users), ...ctx.extraUsers]) {
    try {
      deleteUser(u);
    } catch (e) {
      console.warn('deleteUser failed', String(e).slice(0, 150));
    }
  }
  if (ctx.profileCreated) sql('DELETE FROM company_profile;');
}

/** Runs the body with a fixture and always tears it down. */
export async function withCtx<T>(body: (c: Ctx) => Promise<T>, opts: { second?: boolean; mgrInBoth?: boolean } = {}): Promise<T> {
  const c = await setup(opts);
  try {
    return await body(c);
  } finally {
    await teardown(c);
  }
}

/**
 * Creates an AGREED position and brings it to ACCEPTED through the FOR-11-02 flow (send to the client,
 * accept on the client's behalf as MANAGER).
 */
export async function acceptedPosition(
  c: Ctx,
  qty: number,
  salePrice: number,
  opts: { projectId?: number; purchasePrice?: number } = {},
): Promise<number> {
  const projectId = opts.projectId ?? c.projectId;
  const pos = await ok('POST', '/api/purchase-positions', c.tok.admin, {
    projectId,
    kind: 'FINISHING',
    sourcingPath: 'AGREED',
    plannedMaterialId: c.materialId,
    quantity: qty,
    salePrice,
    plannedPurchasePrice: opts.purchasePrice ?? null,
    saleVatRateId: c.vat8,
    purchaseVatRateId: c.vat23,
    notes: `qa03-${c.rid}-${++c.seq}`,
  });
  await ok('POST', '/api/position-approvals/staff/send', c.tok.admin, { positionIds: [pos.id], decisionDeadline: null, managerNote: `qa03-${c.rid}` });
  const rounds = await ok('GET', `/api/position-approvals/staff/positions/${pos.id}/rounds`, c.tok.admin);
  const round = rounds[0];
  const option = round.options[0];
  await ok('POST', `/api/position-approvals/staff/rounds/${round.roundId}/decide-on-behalf`, c.tok.admin, {
    decision: 'ACCEPT',
    optionId: option.optionId,
    expectedUnitSalePrice: option.unitSalePrice,
    comment: `qa03-${c.rid}`,
  });
  return pos.id as number;
}

/** Creates a draft proforma (MANAGER) for the given positions. */
export async function draftProforma(c: Ctx, positionIds: number[], extra: Record<string, unknown> = {}): Promise<any> {
  return ok('POST', '/api/client-proformas', c.tok.mgr, { projectId: c.projectId, positionIds, dueDate: null, note: `qa03-${c.rid}`, ...extra });
}

/**
 * Issues a draft. Tries the real endpoint first. In the local stack the PDF is written to GCS with an
 * expired ADC token, so issue answers 502 error.proforma.pdf.failed (environment, not a product bug);
 * then the issue is emulated in SQL (number, dates, ISSUED, positions IN_PROFORMA) so that the payment,
 * release and order flows can still be exercised. `mode` tells which path was used.
 */
export async function issueProforma(c: Ctx, id: number): Promise<{ mode: 'api' | 'sql'; body: any }> {
  const r = await http('POST', `/api/client-proformas/${id}/issue`, c.tok.mgr, { language: 'pl' });
  if (r.status === 200) return { mode: 'api', body: r.body };
  if (r.status !== 502) throw new Error(`issue ${id} -> ${r.status}: ${r.text.slice(0, 300)}`);
  const n = ++c.seq;
  const num = `PF/${new Date().getFullYear()}/9${String(parseInt(c.rid.slice(0, 5), 36) % 10000).padStart(4, '0')}${String(n).padStart(1, '0')}`;
  psql(
    `UPDATE client_payments SET status='ISSUED', due_date=COALESCE(due_date, CURRENT_DATE + 7)
       WHERE status='DRAFT' AND id=(SELECT client_payment_id FROM client_proformas WHERE id=:id);
     UPDATE client_proformas SET number=:'num', issue_date=CURRENT_DATE, pdf_language='pl'
       WHERE id=:id AND number IS NULL;
     UPDATE purchase_positions SET status='IN_PROFORMA' WHERE id IN (SELECT position_id FROM client_proforma_lines WHERE proforma_id=:id) AND status='ACCEPTED';`,
    { num, id: String(id) },
  );
  return { mode: 'sql', body: (await http('GET', `/api/client-proformas/${id}`, c.tok.mgr)).body };
}

/** Accepted positions -> draft -> issued proforma. */
export async function issuedProforma(c: Ctx, specs: Array<[number, number]> = [[10, 100], [3, 250]]) {
  const pids: number[] = [];
  for (const [q, p] of specs) pids.push(await acceptedPosition(c, q, p));
  const d = await draftProforma(c, pids);
  const issued = await issueProforma(c, d.id);
  return { pids, id: d.id as number, ...issued };
}

/** Pays a proforma in full through the ledger (one credit row). */
export async function payProforma(c: Ctx, id: number, gross: number): Promise<number> {
  const t = txn(c, gross, 'PF pay');
  const r = await link(c, c.tok.fin, t, { kind: 'PROFORMA_PAYMENT', amount: gross, clientProformaId: id });
  if (r.status !== 201) throw new Error(`pay link -> ${r.status} ${r.text.slice(0, 300)}`);
  return t;
}

export async function getPf(c: Ctx, id: number, tok = c.tok.mgr): Promise<any> {
  return ok('GET', `/api/client-proformas/${id}`, tok);
}

export async function getEo(c: Ctx, id: number, tok = c.tok.buyer): Promise<any> {
  return ok('GET', `/api/external-orders/${id}`, tok);
}

export async function positionStatus(c: Ctx, id: number): Promise<string> {
  return (await ok('GET', `/api/purchase-positions/${id}`, c.tok.admin)).status as string;
}

/** Inserts a bank ledger row on the run's CASH account. `amount` > 0 credit, < 0 debit. */
export function txn(c: Ctx, amount: number, title: string, currency = 'PLN'): number {
  const full = `qa-${c.rid} ${title}`;
  const accountId = currency === 'PLN' ? c.accountId : Number(
    psql(
      `INSERT INTO bank_accounts (type, name, currency, included_in_ledger, default_for_cash, created_by)
       VALUES ('CASH', :'name', :'cur', true, false, 'qa') RETURNING id;`,
      { name: `qa03-${c.rid}-${currency}`, cur: currency },
    ).split('\n')[0],
  );
  return Number(
    psql(
      `INSERT INTO bank_transactions (account_id, channel, origin, status, amount, currency, operation_date, booking_date, title, created_by)
       VALUES (:acc, 'CASH', 'MANUAL', 'POSTED', :amt, :'cur', CURRENT_DATE, CURRENT_DATE, :'title', 'qa') RETURNING id;`,
      { acc: String(accountId), amt: String(amount), cur: currency, title: full },
    ).split('\n')[0],
  );
}

/**
 * Inserts a BANK_FEED ledger row (the only kind the detector looks at) on a STUB connection and a BANK
 * account created lazily for the run (`qa03-<rid>-feed`). `amount` > 0 credit, < 0 debit. Teardown removes it.
 */
export function feedTxn(c: Ctx, amount: number, title: string, counterparty = 'QA Counterparty'): number {
  const key = '__feedAccount';
  let acc = (c as any)[key] as number | undefined;
  if (!acc) {
    const conn = Number(
      psql(
        `INSERT INTO bank_connections (provider, status, display_name, created_by)
         VALUES ('STUB', 'ACTIVE', :'name', 'qa') RETURNING id;`,
        { name: `qa03-${c.rid}-conn` },
      ).split('\n')[0],
    );
    acc = Number(
      psql(
        `INSERT INTO bank_accounts (type, connection_id, external_id, name, currency, included_in_ledger, created_by)
         VALUES ('BANK', :conn, :'ext', :'name', 'PLN', true, 'qa') RETURNING id;`,
        { conn: String(conn), ext: `qa03-${c.rid}`, name: `qa03-${c.rid}-feed` },
      ).split('\n')[0],
    );
    (c as any)[key] = acc;
  }
  return Number(
    psql(
      `INSERT INTO bank_transactions (account_id, channel, origin, status, amount, currency, operation_date, booking_date, title, external_id, counterparty_name, created_by)
       VALUES (:acc, 'BANK', 'BANK_FEED', 'POSTED', :amt, 'PLN', CURRENT_DATE, CURRENT_DATE, :'title', :'ext', :'cp', 'qa') RETURNING id;`,
      { acc: String(acc), amt: String(amount), title: `qa-${c.rid} ${title}`, ext: `qa03-${c.rid}-${++c.seq}`, cp: counterparty },
    ).split('\n')[0],
  );
}

/**
 * Inserts a PARSED supplier document (what OCR would produce) so documents can be attached to orders
 * without a real upload. Key prefix `qa03-<rid>/` is swept by teardown.
 */
export function procDoc(c: Ctx, total: number, nip: string = c.sellerNip, invoice = `FV/${c.rid}/${++c.seq}`): number {
  return Number(
    psql(
      `INSERT INTO procurement_documents (project_id, uploaded_by_id, raw_object_key, source_mime_type, supplier_name, supplier_nip,
         document_total, invoice_number, invoice_date, status, uploaded_at, parsed_at, created_by)
       VALUES (:pid, :uid, :'key', 'application/pdf', :'sup', :'nip', :tot, :'inv', CURRENT_DATE, 'PARSED', NOW(), NOW(), 'qa') RETURNING id;`,
      { pid: String(c.projectId), uid: String(c.users.buyer.id), key: `qa03-${c.rid}/${c.seq}.pdf`, sup: c.sellerName, nip, tot: String(total), inv: invoice },
    ).split('\n')[0],
  );
}

/** Extra bank account in another currency is deleted by the title/account sweep below. */
export function sweepForeignAccounts(c: Ctx): void {
  const ids = psql(`SELECT id FROM bank_accounts WHERE name LIKE :'p';`, { p: `qa03-${c.rid}-%` })
    .split('\n')
    .filter((x) => x && Number(x) !== c.accountId);
  for (const id of ids) {
    psql(
      `DELETE FROM bank_transaction_links WHERE transaction_id IN (SELECT id FROM bank_transactions WHERE account_id = :a);
       DELETE FROM bank_transactions WHERE account_id = :a; DELETE FROM bank_accounts WHERE id = :a;`,
      { a: id },
    );
  }
}

export async function link(c: Ctx, tok: string, txnId: number, body: Record<string, unknown>): Promise<Res> {
  return http('POST', `/api/bank/transactions/${txnId}/links`, tok, body);
}

// ---- error keys -----------------------------------------------------------------------------
// The API error body has no key, only the localized `message`. The key is checked by looking its text
// up in the backend's messages.properties (the default locale is PL) and matching it, with {n}
// placeholders as wildcards.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

let messages: Map<string, string> | undefined;
function loadMessages(file = 'messages.properties'): Map<string, string> {
  const path = resolve(process.cwd(), '../../foremen-backend/src/main/resources', file);
  const m = new Map<string, string>();
  for (const line of readFileSync(path, 'utf8').split('\n')) {
    const i = line.indexOf('=');
    if (i > 0 && !line.startsWith('#')) m.set(line.slice(0, i).trim(), line.slice(i + 1).trim());
  }
  return m;
}

/** True when the response is `status` and its message is the text of `key`. Returns a diagnosis string. */
export function errMatches(r: Res, status: number, key: string): string | null {
  messages ??= loadMessages();
  const tpl = messages.get(key);
  if (!tpl) return `unknown message key ${key}`;
  if (r.status !== status) return `${key}: expected HTTP ${status}, got ${r.status}: ${r.text.slice(0, 200)}`;
  const re = new RegExp('^' + tpl.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\{\d+\}/g, '.*') + '$');
  const msg = String(r.body?.message ?? '');
  return re.test(msg) ? null : `${key}: message "${msg}" does not match "${tpl}"`;
}

export function newRid(): string {
  return runId();
}

export const today = (): string => new Date().toISOString().slice(0, 10);
