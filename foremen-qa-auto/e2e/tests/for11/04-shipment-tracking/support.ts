import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import bcrypt from 'bcryptjs';
import { adminToken, createProject, deleteProject, raw, roleIdByCode, tokenOf } from '../../support/api.js';

// FOR-11-04 helpers (own copy; the shared support files are read-only for this suite).
// Repeatability: every world carries a run-id; users `for1104+<run-id>-<tag>@example.com`, projects
// `FOR1104-<run-id>[-OTHER]`. Positions, orders, shipments, schedule rows are inserted/removed by SQL
// (no API creates a SUPPLIER_PAID position without the whole FOR-11-02/03 flow) and cleaned in cleanup().

const PG_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';
const PG_USER = process.env.QA_PG_USER ?? 'foremen';
const PG_DB = process.env.QA_PG_DB ?? 'foremen';

export function psql(sql: string, vars: Record<string, string> = {}): string {
  const args = ['exec', '-i', PG_CONTAINER, 'psql', '-U', PG_USER, '-d', PG_DB, '-v', 'ON_ERROR_STOP=1', '-At', '-q'];
  for (const [k, v] of Object.entries(vars)) args.push('-v', `${k}=${v}`);
  args.push('-f', '-');
  return execFileSync('docker', args, { input: sql, encoding: 'utf8' }).trim();
}

export const newRunId = (): string => `${Date.now().toString(36)}${randomBytes(2).toString('hex')}`;

/** yyyy-mm-dd of today (Europe/Warsaw, the zone the backend uses) shifted by `n` days. */
export function day(n = 0): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Warsaw', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date())
    .split('-')
    .map(Number);
  const d = new Date(Date.UTC(parts[0]!, parts[1]! - 1, parts[2]! + n));
  return d.toISOString().slice(0, 10);
}

export interface QaUser {
  id: number;
  email: string;
  password: string;
  roleCode: string;
  token: string;
}

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

export type Tag = 'ADMIN' | 'BUYER' | 'MANAGER' | 'FOREMAN' | 'FOREMAN2' | 'FINANCIER' | 'CLIENT' | 'WORKER';
const ROLE_OF: Record<Tag, string> = {
  ADMIN: 'ADMIN', BUYER: 'BUYER', MANAGER: 'MANAGER', FOREMAN: 'FOREMAN', FOREMAN2: 'FOREMAN',
  FINANCIER: 'FINANCIER', CLIENT: 'CLIENT', WORKER: 'WORKER',
};

export interface PositionSpec {
  path?: 'AGREED' | 'PACKAGE' | 'CLIENT_OWN';
  status?: string;
  quantity?: number;
  ordered?: number;
  needBy?: string | null;
  orderBy?: string | null;
  workCategoryId?: number | null;
  projectId?: number;
}

export interface World {
  id: string;
  admin: string;
  users: Partial<Record<Tag, QaUser>>;
  projectId: number;
  otherProjectId: number;
  /** token of a world user (or ADMIN) */
  t(tag: Tag): string;
  position(spec?: PositionSpec): number;
  /** PAID external order of the main project with lines `[positionId, quantity]`. */
  order(lines: Array<[number, number]>): number;
  /** Project start date + one schedule row with a bar for the category starting on day `startDay` (1-based). */
  schedule(categoryId: number, startDay: number, startDate?: string): void;
  positionRow(id: number): { status: string; planned: string | null };
  cleanup(): Promise<void>;
}

const REQUIRED_MEMBERS: Tag[] = ['MANAGER', 'CLIENT'];

/**
 * Builds users (`tags`), a main project (MANAGER, CLIENT and, when present, FOREMAN as members) and an
 * "other" project (MANAGER, CLIENT and, when present, FOREMAN2). MANAGER and CLIENT are always created.
 * Call `cleanup()` in `finally`.
 */
export async function makeWorld(tags: Tag[] = []): Promise<World> {
  const id = newRunId();
  const want = new Set<Tag>([...REQUIRED_MEMBERS, ...tags]);
  want.delete('ADMIN');
  const users: Partial<Record<Tag, QaUser>> = {};
  const projectIds: number[] = [];
  const positionIds: number[] = [];
  const created: number[] = [];

  const world: World = {
    id,
    admin: '',
    users,
    projectId: 0,
    otherProjectId: 0,
    t: (tag) => (tag === 'ADMIN' ? world.admin : users[tag]!.token),
    position: () => 0,
    order: () => 0,
    schedule: () => undefined,
    positionRow: () => ({ status: '', planned: null }),
    cleanup: async () => undefined,
  };

  const cleanup = async (): Promise<void> => {
    const pl = projectIds.length ? projectIds.join(',') : '0';
    const ul = created.length ? created.join(',') : '0';
    try {
      psql(
        `DELETE FROM audit_log WHERE (entity_class = 'ShipmentEntity' AND entity_id IN (SELECT id FROM shipments WHERE project_id IN (${pl})))
            OR (entity_class = 'PurchasePositionEntity' AND entity_id IN (SELECT id FROM purchase_positions WHERE project_id IN (${pl})));
         DELETE FROM audit_log WHERE entity_class = 'PurchaseRequestEntity' AND entity_id IN (SELECT id FROM purchase_requests WHERE project_id IN (${pl}));
         DELETE FROM notifications WHERE recipient_id IN (${ul});
         DELETE FROM shipments WHERE project_id IN (${pl});
         DELETE FROM external_orders WHERE project_id IN (${pl});
         DELETE FROM purchase_positions WHERE project_id IN (${pl});
         DELETE FROM purchase_requests WHERE project_id IN (${pl});
         DELETE FROM project_schedule_bars WHERE schedule_id IN (SELECT id FROM project_schedules WHERE project_id IN (${pl}));
         DELETE FROM project_schedules WHERE project_id IN (${pl});
         DELETE FROM estimates WHERE project_id IN (${pl});`,
      );
    } catch (e) {
      console.error('cleanup SQL failed', e);
    }
    for (const pid of projectIds) {
      try {
        await deleteProject(pid);
      } catch (e) {
        console.error('deleteProject failed', pid, e);
      }
    }
    for (const uid of created) {
      try {
        psql(
          `DELETE FROM project_members WHERE user_id = :uid;
           DELETE FROM refresh_tokens WHERE user_id = :uid;
           DELETE FROM invite_tokens WHERE user_id = :uid;
           DELETE FROM password_reset_tokens WHERE user_id = :uid;
           DELETE FROM users WHERE id = :uid;`,
          { uid: String(uid) },
        );
      } catch (e) {
        console.error('delete user failed', uid, e);
      }
    }
  };
  world.cleanup = cleanup;

  try {
    world.admin = await adminToken();
    for (const tag of want) {
      const email = `for1104+${id}-${tag.toLowerCase()}@example.com`;
      const u = insertUser(ROLE_OF[tag], email);
      created.push(u.id);
      users[tag] = { id: u.id, email, password: u.password, roleCode: ROLE_OF[tag], token: '' };
    }
    for (const tag of want) {
      const u = users[tag]!;
      u.token = await tokenOf(u);
    }
    const member = async (tag: Tag, role: string) => ({ userId: users[tag]!.id, projectRoleId: await roleIdByCode(role) });
    const base = [await member('MANAGER', 'MANAGER'), await member('CLIENT', 'CLIENT')];
    const mainMembers = [...base];
    if (users.FOREMAN) mainMembers.push(await member('FOREMAN', 'FOREMAN'));
    const otherMembers = [...base];
    if (users.FOREMAN2) otherMembers.push(await member('FOREMAN2', 'FOREMAN'));
    world.projectId = await createProject(`FOR1104-${id}`, mainMembers);
    projectIds.push(world.projectId);
    world.otherProjectId = await createProject(`FOR1104-OTHER-${id}`, otherMembers);
    projectIds.push(world.otherProjectId);
    psql(`UPDATE projects SET start_date = :'d'::date WHERE id IN (${projectIds.join(',')});`, { d: day(0) });
  } catch (e) {
    await cleanup();
    throw e;
  }

  world.position = (spec: PositionSpec = {}) => {
    const out = psql(
      `INSERT INTO purchase_positions (project_id, kind, sourcing_path, status, planned_material_id, quantity, unit_id,
         ordered_quantity, need_by_date, order_by_date, work_category_id, created_by)
       VALUES (:'pid'::bigint, 'FINISHING', :'path', :'status', 20, :'qty'::numeric, 1, :'ord'::numeric,
         NULLIF(:'need','')::date, NULLIF(:'orderby','')::date, NULLIF(:'cat','')::bigint, 'qa') RETURNING id;`,
      {
        pid: String(spec.projectId ?? world.projectId),
        path: spec.path ?? 'AGREED',
        status: spec.status ?? 'SUPPLIER_PAID',
        qty: String(spec.quantity ?? 10),
        ord: String(spec.ordered ?? (spec.path === 'CLIENT_OWN' ? 0 : (spec.quantity ?? 10))),
        need: spec.needBy ?? '',
        orderby: spec.orderBy ?? '',
        cat: spec.workCategoryId ? String(spec.workCategoryId) : '',
      },
    );
    const pid = Number(out.split('\n')[0]);
    positionIds.push(pid);
    return pid;
  };

  world.order = (lines) => {
    const oid = Number(
      psql(
        `INSERT INTO external_orders (project_id, seller_id, status, reference_number, created_by)
         VALUES (:'pid'::bigint, 1, 'PAID', :'ref', 'qa') RETURNING id;`,
        { pid: String(world.projectId), ref: `FOR1104-${id}-EO` },
      ).split('\n')[0],
    );
    for (const [positionId, quantity] of lines) {
      psql(
        `INSERT INTO external_order_lines (external_order_id, position_id, quantity, unit_purchase_net, created_by)
         VALUES (:'oid'::bigint, :'p'::bigint, :'q'::numeric, 1, 'qa');`,
        { oid: String(oid), p: String(positionId), q: String(quantity) },
      );
    }
    return oid;
  };

  world.schedule = (categoryId, startDay, startDate) => {
    const pid = String(world.projectId);
    if (startDate) psql("UPDATE projects SET start_date = :'d'::date WHERE id = :pid;", { d: startDate, pid });
    const sid = psql("INSERT INTO project_schedules (project_id, version, created_by) VALUES (:pid, 0, 'qa') RETURNING id;", { pid }).split('\n')[0]!;
    // one estimate line of a work item of this category, so the project has a Gantt row for it
    const eid = psql(
      'INSERT INTO estimates (project_id, currency_id) VALUES (:pid, (SELECT id FROM currencies ORDER BY id LIMIT 1)) RETURNING id;',
      { pid },
    ).split('\n')[0]!;
    psql(
      `INSERT INTO estimate_lines (estimate_id, work_item_id, unit_id, unit_price, quantity, value_net)
       SELECT :eid, wi.id, 4, 10, 1, 10 FROM work_items wi WHERE wi.work_category_id = :cat ORDER BY wi.id LIMIT 1;`,
      { eid, cat: String(categoryId) },
    );
    psql(
      `INSERT INTO project_schedule_bars (schedule_id, work_category_id, start_half_days, duration_half_days, created_by)
       VALUES (:sid, :cat, :start, 4, 'qa');`,
      { sid, cat: String(categoryId), start: String(startDay * 2 - 1) },
    );
  };

  world.positionRow = (pid) => {
    const [status, planned] = psql(
      `SELECT status || '|' || COALESCE(planned_delivery_date::text, '') FROM purchase_positions WHERE id = :pid;`,
      { pid: String(pid) },
    ).split('|');
    return { status: status!, planned: planned || null };
  };

  return world;
}

export const post = (path: string, token: string, body?: unknown) =>
  raw('POST', path, { token, body: body === undefined ? '{}' : JSON.stringify(body) });
export const put = (path: string, token: string, body: unknown) => raw('PUT', path, { token, body: JSON.stringify(body) });
export const get = (path: string, token: string) => raw('GET', path, { token });

/** Request body for POST /api/shipments with sensible defaults (carrier DPD: link-out, not tracked). */
export function shipmentBody(w: World, lines: Array<[number, number]>, extra: Record<string, unknown> = {}) {
  return {
    projectId: w.projectId,
    deliveryMethod: 'CARRIER',
    carrierCode: 'DPD',
    trackingNumber: `DPD${w.id.toUpperCase()}${Math.floor(Math.random() * 1e6)}`,
    lines: lines.map(([positionId, quantity]) => ({ positionId, quantity })),
    ...extra,
  };
}

export async function notificationsOf(token: string): Promise<Array<{ id: number; type: string; deepLink: string; read: boolean }>> {
  const r = await get('/api/notifications', token);
  return Array.isArray(r.body) ? r.body : (r.body?.content ?? []);
}

/** Polls until `read` returns a truthy value (notifications are emitted after commit, asynchronously enough). */
export async function eventually<T>(read: () => Promise<T | undefined | false>, ms = 8000): Promise<T | undefined> {
  const end = Date.now() + ms;
  for (;;) {
    const v = await read();
    if (v) return v as T;
    if (Date.now() > end) return undefined;
    await new Promise((r) => setTimeout(r, 300));
  }
}

export function errorKey(body: any): string {
  return String(body?.messageKey ?? body?.errorKey ?? body?.key ?? body?.code ?? body?.error ?? JSON.stringify(body)).slice(0, 300);
}

/**
 * The API error body carries only the localized `message` (no key), so error keys are asserted through
 * their PL text from messages.properties (the API answers PL by default).
 */
export const MSG = {
  notFound: 'Przesyłka nie została znaleziona',
  terminal: 'Przesyłka jest zamknięta i nie można jej zmienić',
  carrierUnknown: 'Nieznany przewoźnik',
  trackingInvalid: 'Numer przesyłki może zawierać tylko litery A-Z, cyfry i myślniki (od 4 do 40 znaków)',
  linkInvalid: 'Podaj poprawny adres http lub https (do 1000 znaków)',
  fieldsMissing: 'Uzupełnij wymagane pola dla wybranej metody dostawy',
  phoneRequired: 'Podaj numer telefonu kierowcy lub kontaktu',
  notShippable: 'Pozycja nie jest w etapie umożliwiającym wysyłkę',
  exceedsCap: 'Ilość przekracza zamówioną ilość pozycji',
  exceedsOrderLine: 'Ilość przekracza ilość w zamówieniu zewnętrznym',
  duplicate: 'Ta sama pozycja została dodana więcej niż raz',
  empty: 'Dodaj co najmniej jedną pozycję z ilością większą od zera',
  projectMismatch: 'Wszystkie pozycje przesyłki muszą należeć do jednego projektu',
  refreshNotAvailable: 'Odświeżanie niedostępne dla tej przesyłki',
  revertNotAvailable: 'Brak poprzedniej daty dostawy do przywrócenia',
  daysInvalid: 'Zakres dni musi mieścić się między 1 a 60.',
} as const;

export const msgOf = (r: { body: any }): string => String(r.body?.message ?? '');

/** Signs in through the login form (PL UI); waits until the login form is gone. */
export async function uiLogin(app: any, screen: any, user: { email: string; password: string }): Promise<void> {
  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(user.email);
  await screen.getByRole('textbox', 'Hasło').fill(user.password);
  await screen.getByRole('button', 'Zaloguj się').tap();
  await screen.getByRole('heading', 'Logowanie').waitFor({ state: 'hidden', timeout: 15000 });
}
