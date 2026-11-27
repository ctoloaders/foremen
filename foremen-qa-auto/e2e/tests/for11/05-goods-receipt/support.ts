import { otpSignInUi } from '../../support/client-otp.js';
import { execFileSync } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import bcrypt from 'bcryptjs';
import { adminToken, createProject, deleteProject, raw, roleIdByCode, tokenOf } from '../../support/api.js';

// FOR-11-05 helpers (own copy; shared support files are read-only for this suite).
// Repeatability: every world has a run-id; users `for1105+<run-id>-<tag>@example.com`, projects
// `FOR1105-<run-id>` / `FOR1105-OTHER-<run-id>`. Positions are inserted by SQL (no API reaches SUPPLIER_PAID
// without the whole FOR-11-02/03 flow); shipments go through the real API. cleanup() removes everything,
// goods_receipts included (append-only in the product, so removal is by SQL).
const PG_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';
const PG_USER = process.env.QA_PG_USER ?? 'foremen';
const PG_DB = process.env.QA_PG_DB ?? 'foremen';
const API = process.env.QA_API_URL ?? 'http://localhost:8080';

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

export interface QaUser {
  id: number;
  email: string;
  password: string;
  roleCode: string;
  token: string;
}

export type Tag =
  | 'ADMIN' | 'BUYER' | 'MANAGER' | 'MANAGER2' | 'FOREMAN' | 'FOREMANB' | 'FOREMAN2'
  | 'FINANCIER' | 'WORKER' | 'CLIENT';
const ROLE_OF: Record<Tag, string> = {
  ADMIN: 'ADMIN', BUYER: 'BUYER', MANAGER: 'MANAGER', MANAGER2: 'MANAGER', FOREMAN: 'FOREMAN', FOREMANB: 'FOREMAN',
  FOREMAN2: 'FOREMAN', FINANCIER: 'FINANCIER', WORKER: 'WORKER', CLIENT: 'CLIENT',
};
const PROJECT_ROLE_OF: Partial<Record<Tag, string>> = {
  MANAGER: 'MANAGER', MANAGER2: 'MANAGER', FOREMAN: 'FOREMAN', FOREMANB: 'FOREMAN', FOREMAN2: 'FOREMAN', CLIENT: 'CLIENT',
};

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

export interface PositionSpec {
  path?: 'AGREED' | 'PACKAGE' | 'CLIENT_OWN';
  status?: string;
  quantity?: number;
  projectId?: number;
}

export interface World {
  id: string;
  admin: string;
  users: Partial<Record<Tag, QaUser>>;
  projectId: number;
  otherProjectId: number;
  t(tag: Tag): string;
  /** Inserts a position (default AGREED, SUPPLIER_PAID, qty 10). */
  position(spec?: PositionSpec): number;
  /** BUYER creates a shipment (carrier DPD) with `[positionId, qty]` lines; returns its id. */
  shipment(lines: Array<[number, number]>, extra?: Record<string, unknown>): Promise<number>;
  positionRow(id: number): { status: string; accepted: string };
  cleanup(): Promise<void>;
}

export async function makeWorld(tags: Tag[] = []): Promise<World> {
  const id = newRunId();
  const want = new Set<Tag>(['MANAGER', 'CLIENT', 'BUYER', 'FOREMAN', ...tags]);
  want.delete('ADMIN');
  const users: Partial<Record<Tag, QaUser>> = {};
  const projectIds: number[] = [];
  const created: number[] = [];
  const world: World = {
    id, admin: '', users, projectId: 0, otherProjectId: 0,
    t: (tag) => (tag === 'ADMIN' ? world.admin : users[tag]!.token),
    position: () => 0,
    shipment: async () => 0,
    positionRow: () => ({ status: '', accepted: '' }),
    cleanup: async () => undefined,
  };
  const cleanup = async (): Promise<void> => {
    const pl = projectIds.length ? projectIds.join(',') : '0';
    const ul = created.length ? created.join(',') : '0';
    try {
      psql(
        `DELETE FROM audit_log WHERE entity_class = 'GoodsReceiptEntity' AND entity_id IN (SELECT id FROM goods_receipts WHERE project_id IN (${pl}));
         DELETE FROM audit_log WHERE (entity_class = 'ShipmentEntity' AND entity_id IN (SELECT id FROM shipments WHERE project_id IN (${pl})))
            OR (entity_class = 'PurchasePositionEntity' AND entity_id IN (SELECT id FROM purchase_positions WHERE project_id IN (${pl})));
         DELETE FROM audit_log WHERE entity_class = 'PurchaseRequestEntity' AND entity_id IN (SELECT id FROM purchase_requests WHERE project_id IN (${pl}));
         DELETE FROM notifications WHERE recipient_id IN (${ul});
         DELETE FROM goods_receipts WHERE project_id IN (${pl});
         DELETE FROM shipments WHERE project_id IN (${pl});
         DELETE FROM external_orders WHERE project_id IN (${pl});
         DELETE FROM purchase_positions WHERE project_id IN (${pl});
         DELETE FROM purchase_requests WHERE project_id IN (${pl});`,
      );
    } catch (e) {
      console.error('cleanup SQL failed', e);
    }
    for (const pid of projectIds) {
      try { await deleteProject(pid); } catch (e) { console.error('deleteProject failed', pid, e); }
    }
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
      } catch (e) { console.error('delete user failed', uid, e); }
    }
  };
  world.cleanup = cleanup;
  try {
    world.admin = await adminToken();
    for (const tag of want) {
      const email = `for1105+${id}-${tag.toLowerCase()}@example.com`;
      const u = insertUser(ROLE_OF[tag], email);
      created.push(u.id);
      users[tag] = { id: u.id, email, password: u.password, roleCode: ROLE_OF[tag], token: '' };
    }
    for (const tag of want) users[tag]!.token = await tokenOf(users[tag]!);
    const member = async (tag: Tag) => ({ userId: users[tag]!.id, projectRoleId: await roleIdByCode(PROJECT_ROLE_OF[tag]!) });
    const mainMembers = [];
    for (const tag of ['MANAGER', 'MANAGER2', 'CLIENT', 'FOREMAN', 'FOREMANB'] as Tag[]) if (users[tag]) mainMembers.push(await member(tag));
    const otherMembers = [await member('MANAGER'), await member('CLIENT')];
    if (users.FOREMAN2) otherMembers.push(await member('FOREMAN2'));
    world.projectId = await createProject(`FOR1105-${id}`, mainMembers);
    projectIds.push(world.projectId);
    world.otherProjectId = await createProject(`FOR1105-OTHER-${id}`, otherMembers);
    projectIds.push(world.otherProjectId);
    psql(`UPDATE projects SET start_date = :'d'::date WHERE id IN (${projectIds.join(',')});`, { d: day(0) });
  } catch (e) {
    await cleanup();
    throw e;
  }
  world.position = (spec: PositionSpec = {}) => {
    const qty = spec.quantity ?? 3;
    const path = spec.path ?? 'AGREED';
    const out = psql(
      `INSERT INTO purchase_positions (project_id, kind, sourcing_path, status, planned_material_id, quantity, unit_id,
         ordered_quantity, created_by)
       VALUES (:'pid'::bigint, 'FINISHING', :'path', :'status', 20, :'qty'::numeric, 1, :'ord'::numeric, 'qa') RETURNING id;`,
      {
        pid: String(spec.projectId ?? world.projectId),
        path,
        status: spec.status ?? (path === 'CLIENT_OWN' ? 'IN_DELIVERY' : 'SUPPLIER_PAID'),
        qty: String(qty),
        ord: String(path === 'CLIENT_OWN' ? 0 : qty),
      },
    );
    return Number(out.split('\n')[0]);
  };
  world.shipment = async (lines, extra = {}) => {
    const r = await post('/api/shipments', world.t('BUYER'), {
      projectId: world.projectId,
      deliveryMethod: 'CARRIER',
      carrierCode: 'DPD',
      trackingNumber: `DPD${id.toUpperCase()}${Math.floor(Math.random() * 1e6)}`,
      lines: lines.map(([positionId, quantity]) => ({ positionId, quantity })),
      ...extra,
    });
    if (r.status !== 201) throw new Error(`create shipment -> ${r.status} ${JSON.stringify(r.body)}`);
    return r.body.id as number;
  };
  world.positionRow = (pid) => {
    const [status, accepted] = psql(
      `SELECT status || '|' || COALESCE(accepted_quantity::text, '') FROM purchase_positions WHERE id = :pid;`,
      { pid: String(pid) },
    ).split('|');
    return { status: status!, accepted: accepted ?? '' };
  };
  return world;
}

export const post = (path: string, token: string, body?: unknown, lang?: string) =>
  raw('POST', path, { token, body: body === undefined ? '{}' : JSON.stringify(body), lang });
export const put = (path: string, token: string, body: unknown) => raw('PUT', path, { token, body: JSON.stringify(body) });
export const get = (path: string, token: string, lang?: string) => raw('GET', path, { token, lang });
export const del = (path: string, token: string) => raw('DELETE', path, { token });

export interface Ev {
  positionId: number;
  kind: 'ACCEPTED' | 'MISSING' | 'RETURNED';
  quantity: number;
  shipmentId?: number;
  fromAccepted?: boolean;
  comment?: string;
  clientRequestId?: string;
  [k: string]: unknown;
}
export const ev = (positionId: number, kind: Ev['kind'], quantity: number, extra: Partial<Ev> = {}): Ev => ({
  positionId, kind, quantity, clientRequestId: uuid(), ...extra,
});
/** POST /api/goods-receipts with a batch of events. */
export const receive = (token: string, events: Ev[]) => post('/api/goods-receipts', token, { events });
export const ledgerOf = async (token: string, positionId: number) => {
  const r = await get(`/api/purchase-positions/${positionId}/ledger`, token);
  return r;
};
/** `reason: null` sends a body without a reason. */
export const voidReceipt = (token: string, id: number, reason: string | null = 'qa void') =>
  post(`/api/goods-receipts/${id}/void`, token, reason === null ? {} : { reason });

export async function notificationsOf(token: string): Promise<Array<{ id: number; type: string; deepLink: string; read: boolean }>> {
  const r = await get('/api/notifications', token);
  return Array.isArray(r.body) ? r.body : (r.body?.content ?? []);
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

/** The API error body carries only the localized message, so keys are asserted through the PL text. */
export const MSG = {
  notReceivable: 'Pozycja nie jest w etapie odbioru',
  exceedsInFlight: 'Ilość przekracza ilość w drodze',
  returnExceedsAccepted: 'Zwrot przekracza przyjętą ilość',
  commentRequired: 'Komentarz jest wymagany',
  quantityInvalid: 'Nieprawidłowa ilość',
  fromAcceptedInvalid: 'Flaga dotyczy tylko zwrotu',
  shipmentMismatch: 'Przesyłka nie pasuje do pozycji',
  alreadyVoided: 'Wpis został już anulowany',
  voidForbidden: 'Brak prawa do anulowania wpisu',
  mediaForbidden: 'Brak prawa do zmiany zdjęć',
  mediaType: 'Niedozwolony typ pliku zdjęcia',
  mediaSize: 'Plik zdjęcia jest zbyt duży',
  mediaCount: 'Przekroczono dopuszczalną liczbę zdjęć',
  accessDenied: 'Dostęp zabroniony',
  shipmentHasReceipts: 'Przesyłka ma wpisy odbioru',
  shipmentPositionHasReceipts: 'Pozycja ma wpisy odbioru bez przesyłki',
  shipmentTerminal: 'Przesyłka jest zamknięta i nie można jej zmienić',
  notFound: 'Nie znaleziono encji',
} as const;
export const msgOf = (r: { body: any }): string => String(r.body?.message ?? '');

/** Multipart upload of `files` (name, type, bytes) to a receipt. */
export async function uploadMedia(
  token: string,
  receiptId: number,
  files: Array<{ name: string; type: string; bytes: Uint8Array }>,
): Promise<{ status: number; body: any }> {
  const form = new FormData();
  for (const f of files) form.append('files', new Blob([f.bytes as BlobPart], { type: f.type }), f.name);
  const res = await fetch(`${API}/api/goods-receipts/${receiptId}/media`, {
    method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form,
  });
  const text = await res.text();
  let body: any = text;
  try { body = text ? JSON.parse(text) : undefined; } catch { /* keep */ }
  return { status: res.status, body };
}
export async function mediaContent(token: string, receiptId: number, mediaId: number) {
  const res = await fetch(`${API}/api/goods-receipts/${receiptId}/media/${mediaId}/content`, { headers: { Authorization: `Bearer ${token}` } });
  return { status: res.status, headers: res.headers, bytes: Buffer.from(await res.arrayBuffer()) };
}

// Minimal valid image files generated in memory (no fixtures on disk).
export const JPEG = (extra = 0): Uint8Array => Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0xff, 0xd9, ...new Array(extra).fill(0)]);
export const PNG = (): Uint8Array => Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1f, 0x15, 0xc4, 0x89, 0, 0, 0, 0x0a, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0d, 0x0a, 0x2d, 0xb4, 0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82]);
export const WEBP = (): Uint8Array => Uint8Array.from([0x52, 0x49, 0x46, 0x46, 0x1a, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c, 0x0d, 0, 0, 0, 0x2f, 0, 0, 0, 0x10, 0x07, 0x10, 0x11, 0x11, 0x88, 0x88, 0xfe, 0x07, 0, 0]);

/**
 * Opens the goods-receipt screen for `projectId` as the signed-in user. The working project is client-side
 * state in localStorage; a narrow viewport opens the navigation drawer on load (FOR-11-04 BUG-7), so it is closed.
 */
export async function openReceipt(app: any, screen: any, browser: any, projectId: number, query = ''): Promise<void> {
  await browser.evaluate((id: number) => localStorage.setItem('foremen.workingProjectId', String(id)), projectId);
  await app.open(`/deliveries/receipt${query}`);
  const close = screen.getByRole('button', 'Close menu', { visible: true });
  if (await close.isVisible()) await close.tap();
}

/** Signs in through the login form (PL UI). */
export async function uiLogin(app: any, screen: any, user: { email: string; password: string }): Promise<void> {
  if ((user as any).roleCode === 'CLIENT') { await otpSignInUi(app, screen, user.email, 'pl'); return; }
  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(user.email);
  await screen.getByRole('textbox', 'Hasło').fill(user.password);
  await screen.getByRole('button', 'Zaloguj się').tap();
  await screen.getByRole('heading', 'Logowanie').waitFor({ state: 'hidden', timeout: 15000 });
}
