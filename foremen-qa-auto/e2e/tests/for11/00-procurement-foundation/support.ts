import { execFileSync } from 'node:child_process';
import { adminToken, createProject, roleIdByCode, tokenOf } from '../../support/api.js';
import { createUser, deleteUser, runId, type TestUser } from '../../support/users.js';

// FOR-11-00 test support: world fixture (users, projects, project materials), position/request
// helpers, SQL helpers for the system-owned statuses and a complete teardown.
export const API = process.env.QA_API_URL ?? 'http://localhost:8080';
const PG = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';

export function psql(sql: string, vars: Record<string, string> = {}): string {
  const args = ['exec', '-i', PG, 'psql', '-U', process.env.QA_PG_USER ?? 'foremen', '-d', process.env.QA_PG_DB ?? 'foremen', '-v', 'ON_ERROR_STOP=1', '-At', '-q'];
  for (const [k, v] of Object.entries(vars)) args.push('-v', `${k}=${v}`);
  args.push('-f', '-');
  return execFileSync('docker', args, { input: sql, encoding: 'utf8' }).trim();
}

export interface Res { status: number; body: any }

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
  try { parsed = text ? JSON.parse(text) : undefined; } catch { /* keep text */ }
  return { status: res.status, body: parsed };
}

/** Message / key text of an error body, for loose assertions on error keys. */
export function errText(r: Res): string {
  return JSON.stringify(r.body ?? '');
}

/** PL texts of the error keys (messages.properties); the API error body carries only the localized `message`. */
export const MSG = {
  kindNotSupported: 'Ten rodzaj materiału nie jest jeszcze obsługiwany.',
  positionProjectNotActive: 'Projekt nie jest w realizacji.',
  materialProjectMismatch: 'Materiał należy do innego projektu.',
  readOnly: 'To pole jest zarządzane przez system.',
  forbiddenField: 'Brak uprawnień do filtrowania po tym polu.',
  transitionNotAllowed: 'Ta zmiana statusu jest niedozwolona.',
  gateViolation: 'Zakup jest możliwy dopiero po pełnej płatności proformy przez klienta.',
  deleteNotAllowed: 'Pozycję można usunąć tylko w statusie „Nowe” lub „Anulowane”.',
  buyerInvalid: 'Przypisany użytkownik musi mieć rolę zakupowca.',
  reqProjectMismatch: 'Pozycja należy do innego projektu.',
  alreadyAssigned: 'Pozycja jest już w innym zleceniu.',
  detachNotAllowed: 'Pozycji nie można usunąć ze zlecenia na tym etapie.',
  reqNotEmpty: 'Zlecenie zawiera pozycje.',
  materialProjectImmutable: 'Nie można zmienić projektu materiału.',
  materialInUse: 'Materiał jest używany w pozycjach zakupowych.',
  nipInvalid: 'Nieprawidłowy numer NIP.',
  nipDuplicate: 'Sprzedawca z tym NIP już istnieje.',
  quickNameRequired: 'Podaj nazwę sprzedawcy (do 100 znaków).',
  variantStage: 'Na tym etapie zamiana wymaga ponownej akceptacji klienta.',
  variantSame: 'Wybrany materiał i cena są takie same jak obecne.',
  daysInvalid: 'Zakres dni musi mieścić się między 1 a 60.',
} as const;

/** True when the error body's message is the given PL text (or contains it). */
export function hasMsg(r: Res, text: string): boolean {
  return String(r.body?.message ?? '').includes(text);
}

/** Valid Polish NIP (weights 6,5,7,2,3,4,5,6,7, mod 11), unique per call. */
let nipCounter = 0;
export function genNip(): string {
  const w = [6, 5, 7, 2, 3, 4, 5, 6, 7];
  for (;;) {
    nipCounter += 1;
    const seed = (Date.now() + nipCounter * 7919 + Math.floor(Math.random() * 1e6)) % 1e9;
    const d = String(Math.max(seed, 100000000)).padStart(9, '0').split('').map(Number);
    if (d[0] === 0) continue;
    const sum = d.reduce((s, x, i) => s + x * w[i], 0) % 11;
    if (sum === 10) continue;
    const nip = d.join('') + String(sum);
    const taken = psql(`SELECT COUNT(*) FROM material_sellers WHERE nip = :'n';`, { n: nip });
    if (taken === '0') return nip;
  }
}

export interface World {
  id: string;
  admin: string;
  manager: TestUser; foreman: TestUser; buyer: TestUser; financier: TestUser;
  tok: { admin: string; manager: string; foreman: string; buyer: string; financier: string };
  projA: number; projB: number; projD: number;
  nameA: string;
  matA: number; matB: number;
  extraUsers: TestUser[];
  extraProjects: number[];
  sellerCodes: string[];
  sellerIds: number[];
}

let tagCounter = 0;
const tag = (s: string) => `${s}${++tagCounter}`;

/** Reference ids used for project materials (any seeded finishing combination works). */
async function refs(admin: string) {
  const m = (await http('GET', '/api/finishing-materials?size=1&query=active==true', admin)).body.content[0];
  return { typeId: m.type?.id as number | undefined, categoryId: m.category.id as number, materialId: m.material.id as number, unitId: m.unit.id as number, packageId: m.packages[0].id as number };
}

export async function newProjectMaterial(w: Pick<World, 'admin' | 'id'>, projectId: number, model: string, extra: Record<string, unknown> = {}): Promise<Res> {
  const r = await refs(w.admin);
  return http('POST', '/api/project-materials', w.admin, {
    projectId, categoryId: r.categoryId, materialId: r.materialId, typeId: r.typeId, unitId: r.unitId, offerPackageIds: [r.packageId], model, ...extra,
  });
}

/**
 * Seeds a project material. Tries the API first; when the local DB carries a NOT NULL `package_volume`
 * column the main-branch code does not know (schema drift from another branch's migrations 124/125, see
 * FINDINGS.md "Blocked"), the API answers 409 error.data.integrity and the row is inserted by SQL.
 */
export async function seedProjectMaterial(w: Pick<World, 'admin' | 'id'>, projectId: number, model: string, sellerId?: number): Promise<number> {
  const r = await newProjectMaterial(w, projectId, model, sellerId ? { sellerId } : {});
  if (r.status === 201) return r.body.id as number;
  const out = psql(
    `WITH src AS (SELECT * FROM finishing_materials WHERE project_id IS NULL AND active AND type_id IS NOT NULL ORDER BY id LIMIT 1),
          ins AS (INSERT INTO finishing_materials (category_id, material_id, type_id, unit_id, model, active, package_volume, cost_net, project_id, seller_id, created_by)
                  SELECT category_id, material_id, type_id, unit_id, :'model', true, package_volume, 0, :pid, NULLIF(:'sid', '')::bigint, 'qa' FROM src RETURNING id)
     INSERT INTO finishing_material_packages (finishing_material_id, offer_package_id)
       SELECT ins.id, 3 FROM ins RETURNING finishing_material_id;`,
    { model, pid: String(projectId), sid: sellerId ? String(sellerId) : '' },
  );
  const id = Number(out.split('\n')[0]);
  if (!Number.isFinite(id)) throw new Error(`seedProjectMaterial failed: ${r.status} ${errText(r)}`);
  return id;
}

export async function createWorld(opts: { users?: boolean } = {}): Promise<World> {
  const id = runId();
  const admin = await adminToken();
  const manager = createUser('MANAGER', 'mgr');
  const foreman = createUser('FOREMAN', 'fm');
  const buyer = createUser('BUYER', 'buy');
  const financier = createUser('FINANCIER', 'fin');
  const w: World = {
    id, admin, manager, foreman, buyer, financier,
    tok: { admin, manager: '', foreman: '', buyer: '', financier: '' },
    projA: 0, projB: 0, projD: 0, nameA: `FOR11 QA A ${id}`, matA: 0, matB: 0,
    extraUsers: [], extraProjects: [], sellerCodes: [], sellerIds: [],
  };
  try {
    w.tok.manager = await tokenOf(manager);
    w.tok.foreman = await tokenOf(foreman);
    w.tok.buyer = await tokenOf(buyer);
    w.tok.financier = await tokenOf(financier);
    const members = [
      { userId: manager.id, projectRoleId: await roleIdByCode('MANAGER') },
      { userId: foreman.id, projectRoleId: await roleIdByCode('FOREMAN') },
    ];
    w.projA = await createProject(w.nameA, members);
    w.projB = await createProject(`FOR11 QA B ${id}`, []);
    w.projD = await createProject(`FOR11 QA D ${id}`, []);
    for (const p of [w.projA, w.projB]) {
      const a = await http('POST', `/api/projects/${p}/activate`, admin, { force: true });
      if (a.status !== 200) throw new Error(`activate ${p}: ${a.status} ${errText(a)}`);
    }
    w.matA = await seedProjectMaterial(w, w.projA, `QA-MAT-${id}-A`);
    w.matB = await seedProjectMaterial(w, w.projB, `QA-MAT-${id}-B`);
  } catch (e) {
    await destroyWorld(w);
    throw e;
  }
  return w;
}

/** Create a position through the API (MANAGER by default). */
export async function newPosition(w: World, over: Record<string, unknown> = {}, token?: string): Promise<Res> {
  return http('POST', '/api/purchase-positions', token ?? w.tok.manager, {
    projectId: w.projA, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: w.matA, quantity: 3, ...over,
  });
}

export async function newRequest(w: World, over: Record<string, unknown> = {}, token?: string): Promise<Res> {
  return http('POST', '/api/purchase-requests', token ?? w.tok.manager, {
    projectId: w.projA, referenceNumber: `REF-${w.id}`, ...over,
  });
}

/** System-owned statuses are not reachable via REST: seed them by SQL (test seed). */
export function setStatus(positionId: number, status: string): void {
  psql(`UPDATE purchase_positions SET status = :'s' WHERE id = :pid;`, { s: status, pid: String(positionId) });
}

export function isoDate(offsetDays: number): string {
  // The backend works in Europe/Warsaw (R11.9); the machine running the tests may be in another zone.
  const d = new Date(Date.now() + offsetDays * 86400000);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Warsaw', year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);
}

export function newSellerCode(w: World): string {
  const c = `qa_seller_${w.id}_${tag('s')}`.toLowerCase();
  w.sellerCodes.push(c);
  return c;
}

/** Complete teardown: positions/requests/materials of the world's projects, sellers, projects, users. */
export async function destroyWorld(w: World | undefined): Promise<void> {
  if (!w) return;
  const pids = [w.projA, w.projB, w.projD, ...w.extraProjects].filter(Boolean);
  const list = pids.join(',') || '0';
  const uids = [w.manager, w.foreman, w.buyer, w.financier, ...w.extraUsers].filter(Boolean).map((u) => u.id).join(',') || '0';
  try {
    psql(`
      DELETE FROM purchase_positions WHERE project_id IN (${list});
      DELETE FROM purchase_requests WHERE project_id IN (${list}) OR assigned_buyer_id IN (${uids});
      DELETE FROM finishing_material_packages WHERE finishing_material_id IN (SELECT id FROM finishing_materials WHERE project_id IN (${list}));
      DELETE FROM finishing_materials WHERE project_id IN (${list});
    `);
  } catch (e) { console.error('teardown sql', e); }
  for (const p of pids) {
    try { await http('DELETE', `/api/projects/${p}`, w.admin); } catch { /* ignore */ }
  }
  try {
    const like = `%${w.id}%`;
    psql(`UPDATE finishing_materials SET seller_id = NULL WHERE seller_id IN (SELECT id FROM material_sellers WHERE code ILIKE :'l' OR name_ru ILIKE :'l');
          DELETE FROM material_sellers WHERE code ILIKE :'l' OR name_ru ILIKE :'l';`, { l: like });
    for (const id of w.sellerIds) psql(`DELETE FROM material_sellers WHERE id = :i;`, { i: String(id) });
  } catch (e) { console.error('teardown sellers', e); }
  for (const u of [w.manager, w.foreman, w.buyer, w.financier, ...w.extraUsers]) {
    try { deleteUser(u); } catch (e) { console.error('teardown user', e); }
  }
}

export { createUser, deleteUser, runId, tokenOf };
export type { TestUser };
