import { otpSignInUi } from '../../support/client-otp.js';
import { execFileSync } from 'node:child_process';
import { adminToken, createProject, deleteProject, raw, roleIdByCode, tokenOf } from '../../support/api.js';
import { createUser, deleteUser, runId, type TestUser } from '../../support/users.js';

// FOR-11-01 helpers. Own file: shared support files are read-only for this suite.
// Fixtures that REST cannot reach (stages ACCEPTED/CLIENT_PAID/ORDERED/RECEIVED, estimate lines,
// schedule bars) are written by SQL, only for rows created by this run.

const PG_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';
const PG_USER = process.env.QA_PG_USER ?? 'foremen';
const PG_DB = process.env.QA_PG_DB ?? 'foremen';

/** Runs trusted SQL (only integers and run-id strings built by this file are interpolated). */
export function sql(statement: string): string {
  return execFileSync(
    'docker',
    ['exec', '-i', PG_CONTAINER, 'psql', '-U', PG_USER, '-d', PG_DB, '-v', 'ON_ERROR_STOP=1', '-At', '-q', '-f', '-'],
    { input: statement, encoding: 'utf8' },
  ).trim();
}

export { raw, runId, tokenOf, adminToken };

// ---- reference data of the seeded catalog (read-only) ----
export const CAT = {
  /** type 3 material (no estimate link, norms in catalog category 10 only) */
  plain: [40, 41, 42, 43],
  /** type 41 ("MT1"): norms for work items 73 (category 4 = C1) and 192 (category 11 = C2) */
  mt1: 569,
  mt1Other: 570,
  /** typed material whose type has no consumption norms ("MT9") */
  mt9: 61,
  /** material without a type */
  noType: 464,
  noType2: 465,
};
export const C1 = 4; // HYDRAULIKA BIAŁY MONTAŻ (work item 73 consumes type 41)
export const C2 = 11; // STOLARKA (work item 192 consumes type 41); never scheduled in our data unless a case says so
export const C3 = 9; // PRACE MALARSKIE, SZTUKATERIA, DEKOR
export const WORK_ITEM_C1_NORM = 73;
export const WORK_ITEM_C2_NORM = 192;

// ---- dates (Europe/Warsaw "today" +- n) ----
export function today(): Date {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Warsaw', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date())
    .split('-')
    .map(Number);
  return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
}
export function dayShift(n: number): string {
  const d = today();
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

// ---- users / project ----
export interface Env {
  id: string;
  manager: TestUser;
  manager2: TestUser;
  buyer: TestUser;
  buyer2: TestUser;
  foreman: TestUser;
  financier: TestUser;
  client: TestUser;
  projectA: number;
  projectB: number;
  tok: Record<'admin' | 'manager' | 'manager2' | 'buyer' | 'buyer2' | 'foreman' | 'financier', string>;
  sellers: number[];
  extraProjects: number[];
}

/** Valid NIP (weights 6,5,7,2,3,4,5,6,7, mod 11) from a seed number. */
export function nip(seed: number): string {
  const weights = [6, 5, 7, 2, 3, 4, 5, 6, 7];
  for (let k = 0; k < 1000; k++) {
    const digits = String(100000000 + ((seed + k) % 899999999)).padStart(9, '1').split('').map(Number);
    const sum = digits.reduce((s, d, i) => s + d * weights[i], 0) % 11;
    if (sum !== 10) return digits.join('') + String(sum);
  }
  throw new Error('nip');
}

async function memberList(users: Array<[TestUser, string]>) {
  const out: Array<{ userId: number; projectRoleId: number }> = [];
  for (const [u, role] of users) out.push({ userId: u.id, projectRoleId: await roleIdByCode(role) });
  return out;
}

/** The API gates DRAFT->ACTIVE on rooms/estimate readiness, so the status is set by SQL (fixture). */
export async function activateProject(projectId: number, _name: string, startDate: string): Promise<void> {
  sql(`UPDATE projects SET status='ACTIVE', start_date='${startDate}' WHERE id=${Number(projectId)};`);
}

/** Creates users and the two projects (A: manager+foreman+client; B: manager2+client, no BUYER/FOREMAN). */
export async function setupEnv(): Promise<Env> {
  const id = runId();
  const env = { id, sellers: [], extraProjects: [] } as unknown as Env;
  try {
    env.manager = createUser('MANAGER', 'f11mgr');
    env.manager2 = createUser('MANAGER', 'f11mgr2');
    env.buyer = createUser('BUYER', 'f11buy');
    env.buyer2 = createUser('BUYER', 'f11buy2');
    env.foreman = createUser('FOREMAN', 'f11fore');
    env.financier = createUser('FINANCIER', 'f11fin');
    env.client = createUser('CLIENT', 'f11cli');
    const nameA = `F11-${id}-A`;
    const nameB = `F11-${id}-B`;
    env.projectA = await createProject(nameA, await memberList([[env.manager, 'MANAGER'], [env.foreman, 'FOREMAN'], [env.client, 'CLIENT']]));
    env.projectB = await createProject(nameB, await memberList([[env.manager2, 'MANAGER'], [env.client, 'CLIENT']]));
    await activateProject(env.projectA, nameA, dayShift(0));
    await activateProject(env.projectB, nameB, dayShift(0));
    env.tok = {
      admin: await adminToken(),
      manager: await tokenOf(env.manager),
      manager2: await tokenOf(env.manager2),
      buyer: await tokenOf(env.buyer),
      buyer2: await tokenOf(env.buyer2),
      foreman: await tokenOf(env.foreman),
      financier: await tokenOf(env.financier),
    };
    return env;
  } catch (e) {
    await teardownEnv(env).catch(() => undefined);
    throw e;
  }
}

/** Removes everything the run created (children first). Idempotent. */
export async function teardownEnv(env: Partial<Env> | undefined): Promise<void> {
  if (!env) return;
  const ids = [env.projectA, env.projectB, ...(env.extraProjects ?? [])].filter((x): x is number => typeof x === 'number');
  if (ids.length) {
    const list = ids.join(',');
    // purchase data of the projects (all created by this run), then the project rows
    sql(`
      DELETE FROM purchase_position_variants WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id IN (${list}));
      UPDATE purchase_positions SET purchase_request_id = NULL WHERE project_id IN (${list});
      DELETE FROM purchase_positions WHERE project_id IN (${list});
      DELETE FROM purchase_requests WHERE project_id IN (${list});
      DELETE FROM project_schedule_bars WHERE schedule_id IN (SELECT id FROM project_schedules WHERE project_id IN (${list}));
      DELETE FROM project_schedules WHERE project_id IN (${list});
      DELETE FROM estimate_lines WHERE estimate_id IN (SELECT id FROM estimates WHERE project_id IN (${list}));
      DELETE FROM estimates WHERE project_id IN (${list});
      DELETE FROM finishing_materials WHERE project_id IN (${list});
    `);
    for (const p of ids) {
      try {
        await deleteProject(p);
      } catch {
        // project may already be gone or blocked; leftover is reported by the caller's cleanup check
      }
    }
  }
  if (env.id) {
    sql(`
      DELETE FROM notifications WHERE recipient_id IN (SELECT id FROM users WHERE email LIKE 'dash+${env.id}-%');
    `);
  }
  for (const u of [env.manager, env.manager2, env.buyer, env.buyer2, env.foreman, env.financier, env.client]) {
    try {
      deleteUser(u);
    } catch {
      // ignore
    }
  }
}

// ---- schedule / estimate fixtures (SQL, rows owned by this run's project) ----
/** Half-day index of the first half of calendar day `dayOffset` (0 = project start day). */
export const halfDayOf = (dayOffset: number): number => 2 * (dayOffset + 1) - 1;

/** Ensures an estimate with the given work items (one line each) exists for the project. */
export function setEstimateWorkItems(projectId: number, workItems: Array<{ id: number; unitId: number }>): void {
  const pid = Number(projectId);
  const lines = workItems
    .map((w, i) => `INSERT INTO estimate_lines (estimate_id, work_item_id, unit_id, line_no, unit_price, quantity, value_net)
       SELECT e.id, ${Number(w.id)}, ${Number(w.unitId)}, ${i + 1}, 1, 1, 1 FROM estimates e WHERE e.project_id=${pid};`)
    .join('\n');
  sql(`
    DELETE FROM estimate_lines WHERE estimate_id IN (SELECT id FROM estimates WHERE project_id=${pid});
    INSERT INTO estimates (project_id, currency_id, status) SELECT ${pid}, 1, 'DRAFT'
      WHERE NOT EXISTS (SELECT 1 FROM estimates WHERE project_id=${pid});
    ${lines}
  `);
}

/** Replaces the project schedule: one bar per category starting at the given day offset. */
export function setScheduleBars(projectId: number, bars: Array<{ categoryId: number; startDay: number; durationDays?: number }>): void {
  const pid = Number(projectId);
  const rows = bars
    .map((b) => `INSERT INTO project_schedule_bars (schedule_id, work_category_id, start_half_days, duration_half_days)
       SELECT s.id, ${Number(b.categoryId)}, ${halfDayOf(b.startDay)}, ${2 * (b.durationDays ?? 3)} FROM project_schedules s WHERE s.project_id=${pid};`)
    .join('\n');
  sql(`
    DELETE FROM project_schedule_bars WHERE schedule_id IN (SELECT id FROM project_schedules WHERE project_id=${pid});
    INSERT INTO project_schedules (project_id) SELECT ${pid} WHERE NOT EXISTS (SELECT 1 FROM project_schedules WHERE project_id=${pid});
    ${rows}
  `);
}

export function setStatus(positionId: number, status: string): void {
  sql(`UPDATE purchase_positions SET status='${status.replace(/[^A-Z_]/g, '')}' WHERE id=${Number(positionId)};`);
}

// ---- dataset (test-cases.md "Setup") ----
export interface Dataset {
  P: Record<string, number>; // P1..P12
  R1: number;
  R2: number;
  R1ref: string;
  R2ref: string;
}

export const j = (o: unknown): string => JSON.stringify(o);

export async function api(method: string, path: string, token: string, body?: unknown) {
  return raw(method, path, { token, body: body === undefined ? undefined : j(body) });
}

async function must(r: { status: number; body: any }, what: string): Promise<any> {
  if (r.status >= 300) throw new Error(`${what}: ${r.status} ${JSON.stringify(r.body).slice(0, 400)}`);
  return r.body;
}

/**
 * Creates P1..P12, R1 and R2 (see test-cases.md). Stage fixtures (ACCEPTED, CLIENT_PAID, RECEIVED) are SQL.
 * Sale VAT id 2 = 8 %. Project A schedule: C1 starts +7, C3 starts +20 (estimate has work items of C1 and C3).
 */
export async function buildDataset(env: Env): Promise<Dataset> {
  const t = env.tok.manager;
  const A = env.projectA;
  const mk = async (tag: string, over: Record<string, unknown>) => {
    const body = {
      projectId: A, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: CAT.plain[0], quantity: 1,
      salePrice: 100, saleVatRateId: 2, notes: `qa-${env.id}-${tag}`, ...over,
    };
    return (await must(await api('POST', '/api/purchase-positions', t, body), `create ${tag}`)).id as number;
  };
  const P: Record<string, number> = {};
  P.P1 = await mk('1', { needByDate: dayShift(30), salePrice: 100, plannedMaterialId: CAT.plain[0] });
  P.P2 = await mk('2', { needByDate: dayShift(-3), salePrice: 50, plannedMaterialId: CAT.plain[1] });
  P.P3 = await mk('3', { quantity: 12.5, salePrice: 92, plannedPurchasePrice: 61.5, purchaseVatRateId: 1, plannedMaterialId: CAT.plain[2] });
  P.P4 = await mk('4', { quantity: 2, salePrice: 200, plannedPurchasePrice: 150, purchaseVatRateId: 1, plannedMaterialId: CAT.plain[3] });
  P.P5 = await mk('5', { plannedMaterialId: CAT.plain[0] });
  P.P6 = await mk('6', { sourcingPath: 'PACKAGE', plannedMaterialId: CAT.plain[1] });
  P.P7 = await mk('7', { sourcingPath: 'CLIENT_OWN', plannedMaterialId: CAT.plain[2] });
  P.P8 = await mk('8', { plannedMaterialId: CAT.plain[3] });
  P.P9 = await mk('9', { sourcingPath: 'PACKAGE', salePrice: 100, plannedPurchasePrice: 150, purchaseVatRateId: 1, plannedMaterialId: CAT.plain[0] });
  P.P10 = await mk('10', { plannedMaterialId: CAT.plain[1] });
  P.P12 = await mk('12', { needByDate: dayShift(9), plannedMaterialId: CAT.mt1 });
  P.P11 = (await must(await api('POST', '/api/purchase-positions', env.tok.admin, {
    projectId: env.projectB, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: CAT.plain[0], quantity: 1,
    salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-11`,
  }), 'create P11')).id;

  const R1ref = `QA-${env.id}-R1`;
  const R2ref = `QA-${env.id}-R2`;
  const R1 = (await must(await api('POST', '/api/purchase-requests', t, {
    projectId: A, referenceNumber: R1ref, expectedDeliveryDate: dayShift(10),
  }), 'create R1')).id as number;
  const R2 = (await must(await api('POST', '/api/purchase-requests', t, {
    projectId: A, referenceNumber: R2ref, expectedDeliveryDate: dayShift(5), assignedBuyerId: env.buyer.id,
  }), 'create R2')).id as number;
  await must(await api('POST', `/api/purchase-requests/${R1}/positions`, t, { positionIds: [P.P2, P.P3] }), 'attach R1');
  await must(await api('POST', `/api/purchase-requests/${R2}/positions`, t, { positionIds: [P.P6, P.P9] }), 'attach R2');

  // stages reachable through REST
  await must(await api('POST', `/api/purchase-positions/${P.P6}/transition`, t, { target: 'WITH_BUYER' }), 'P6 WITH_BUYER');
  await must(await api('POST', `/api/purchase-positions/${P.P9}/transition`, t, { target: 'WITH_BUYER' }), 'P9 WITH_BUYER');
  await must(await api('POST', `/api/purchase-positions/${P.P7}/transition`, t, { target: 'IN_DELIVERY' }), 'P7 IN_DELIVERY');
  // variant history on P8
  await must(await api('POST', `/api/purchase-positions/${P.P8}/variants/replace`, t, {
    materialId: CAT.plain[0], reason: 'ANALOG', note: `qa-${env.id}`,
  }), 'P8 replace');
  // SQL stage fixtures
  setStatus(P.P3, 'ACCEPTED');
  setStatus(P.P4, 'ACCEPTED');
  setStatus(P.P5, 'CLIENT_PAID');
  setStatus(P.P10, 'RECEIVED');
  // P2 must be late: attaching to R1 gave it plannedDeliveryDate=+10, so pin it to -3 (fixture).
  sql(`UPDATE purchase_positions SET planned_delivery_date='${dayShift(-3)}' WHERE id=${P.P2};`);
  // P9 over ceiling = actualPurchasePrice > salePrice (requirements R4.4).
  sql(`UPDATE purchase_positions SET actual_purchase_price=150 WHERE id=${P.P9};`);

  // schedule: estimate has work items of C1 (consumes type 41) and C3; bars C1 +7, C3 +20
  setEstimateWorkItems(A, [{ id: WORK_ITEM_C1_NORM, unitId: 3 }, { id: 155, unitId: 3 }]);
  setScheduleBars(A, [{ categoryId: C1, startDay: 7 }, { categoryId: C3, startDay: 20 }]);
  return { P, R1, R2, R1ref, R2ref };
}

/** Planner rows (all pages) of a project for a token. */
export async function plannerRows(token: string, query = '', size = 100): Promise<any[]> {
  const r = await api('GET', `/api/purchase-positions/planner?size=${size}${query ? '&' + query : ''}`, token);
  if (r.status !== 200) throw new Error(`planner ${query}: ${r.status} ${JSON.stringify(r.body).slice(0, 300)}`);
  return r.body.content;
}

/** Signs in through the login form (PL UI) and waits until the login form is gone. */
export async function uiLogin(app: any, screen: any, user: { email: string; password: string }): Promise<void> {
  const { expect } = await import('e2e');
  if ((user as any).roleCode === 'CLIENT') { await otpSignInUi(app, screen, user.email, 'pl'); return; }
  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(user.email);
  await screen.getByRole('textbox', 'Hasło').fill(user.password);
  await screen.getByRole('button', 'Zaloguj się').tap();
  await expect(screen.getByTestId('dashboard-grid')).toBeVisible();
}
