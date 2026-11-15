// Thin backend API client for test setup/teardown (never used to assert UI behaviour).
const API = process.env.QA_API_URL ?? 'http://localhost:8080';
const ADMIN_EMAIL = process.env.FOREMEN_ADMIN_EMAIL ?? 'cto+1@loaders.dev';
const ADMIN_PASSWORD = process.env.FOREMEN_ADMIN_PASSWORD ?? 'alejano123';

async function call(method: string, path: string, token: string | undefined, body?: unknown): Promise<any> {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok && !(method === 'DELETE' && res.status === 404)) {
    throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
  }
  return text ? JSON.parse(text) : undefined;
}

let cachedToken: string | undefined;

export async function adminToken(): Promise<string> {
  if (!cachedToken) {
    const r = await call('POST', '/api/auth/login', undefined, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD });
    cachedToken = r.accessToken as string;
  }
  return cachedToken;
}

/** Creates a project; `members` are `{ userId, projectRoleId }` (see roleIdByCode()). */
export async function createProject(
  name: string,
  members: Array<{ userId: number; projectRoleId: number }> = [],
): Promise<number> {
  const r = await call('POST', '/api/projects', await adminToken(), { name, members });
  return r.id as number;
}

/** Id of a role by code (e.g. MANAGER). */
export async function roleIdByCode(code: string): Promise<number> {
  return idByCode('/api/roles', code);
}

export async function deleteProject(id: number): Promise<void> {
  await call('DELETE', `/api/projects/${id}`, await adminToken());
}

async function idByCode(path: string, code: string): Promise<number> {
  const r = await call('GET', `${path}?query=${encodeURIComponent(`code==${code}`)}&size=50`, await adminToken());
  const row = (r.content as Array<{ id: number; code: string }>).find((x) => x.code.toLowerCase() === code.toLowerCase());
  if (!row) throw new Error(`${path}: no row with code ${code}`);
  return row.id;
}

/**
 * Removes every widget from a user's dashboard through the API (as that user), so the layout is
 * empty and deterministic. The first GET seeds the role defaults; deleting them afterwards means
 * they are not seeded again (TC-A1-02).
 */
export async function clearDashboard(user: { email: string; password: string }): Promise<void> {
  const login = await call('POST', '/api/auth/login', undefined, { email: user.email, password: user.password });
  const token = login.accessToken as string;
  const layout = await call('GET', '/api/dashboard', token);
  for (const item of layout.items as Array<{ id: number }>) {
    await call('DELETE', `/api/dashboard/items/${item.id}`, token);
  }
}

/**
 * Clears the user's dashboard, then adds every available non-project widget (largest first, first-fit)
 * until the backend reports the grid is full. Returns the number of placements created.
 */
export async function fillDashboard(user: { email: string; password: string }): Promise<number> {
  await clearDashboard(user);
  const login = await call('POST', '/api/auth/login', undefined, { email: user.email, password: user.password });
  const token = login.accessToken as string;
  const lib = await call('GET', '/api/dashboard/library', token);
  const items = (Array.isArray(lib) ? lib : (lib.items ?? lib.content)) as Array<{
    id: number; available: boolean; projectScope: string; colSpan: number; rowSpan: number;
  }>;
  const candidates = items
    .filter((i) => i.available && i.projectScope !== 'SINGLE_PROJECT')
    .sort((a, b) => b.colSpan * b.rowSpan - a.colSpan * a.rowSpan);
  let added = 0;
  for (const w of candidates) {
    const res = await fetch(`${API}/api/dashboard/items`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ widgetId: w.id }),
    });
    if (res.status === 201) added += 1;
  }
  return added;
}

/** Access token of a test user. */
export async function tokenOf(user: { email: string; password: string }): Promise<string> {
  const r = await call('POST', '/api/auth/login', undefined, { email: user.email, password: user.password });
  return r.accessToken as string;
}

/** Library widget id by code, as the given user sees the library. */
export async function widgetIdByCode(token: string, code: string): Promise<number> {
  const lib = await call('GET', '/api/dashboard/library', token);
  const items = (Array.isArray(lib) ? lib : (lib.items ?? lib.content)) as Array<{ id: number; code: string }>;
  const w = items.find((i) => i.code === code);
  if (!w) throw new Error(`library has no widget ${code}`);
  return w.id;
}

/** Places a widget for the token's user; returns the placement. */
export async function addItem(
  token: string,
  body: { widgetId: number; cellIndex?: number; projectId?: number },
): Promise<{ id: number; cellIndex: number }> {
  return call('POST', '/api/dashboard/items', token, body);
}

/** The token user's layout items. */
export async function layoutOf(token: string): Promise<Array<{ id: number; code: string; cellIndex: number; projectId: number | null }>> {
  return (await call('GET', '/api/dashboard', token)).items;
}

/** Raw request for negative API cases: never throws, returns status and parsed body. */
export async function raw(
  method: string,
  path: string,
  opts: { token?: string; body?: string; lang?: string } = {},
): Promise<{ status: number; body: any }> {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(opts.token ? { Authorization: `Bearer ${opts.token}` } : {}),
      ...(opts.lang ? { 'Accept-Language': opts.lang } : {}),
    },
    body: opts.body,
  });
  const text = await res.text();
  let body: any = text;
  try {
    body = text ? JSON.parse(text) : undefined;
  } catch {
    // keep text
  }
  return { status: res.status, body };
}

/** Creates a custom role granting only the listed `[resourceCode, operationCode]` pairs. */
export async function createRoleWithGrants(code: string, grants: Array<[string, string]>): Promise<{ id: number; code: string }> {
  const token = await adminToken();
  const role = await call('POST', '/api/roles', token, {
    code,
    nameRU: `QA ${code}`,
    namePL: `QA ${code}`,
    descriptionRU: `QA test role ${code}`,
    descriptionPL: `QA test role ${code}`,
  });
  await setRoleGrants(role.id, grants);
  return { id: role.id as number, code };
}

/** Replaces the whole permission matrix of a role. */
export async function setRoleGrants(roleId: number, grants: Array<[string, string]>): Promise<void> {
  const byResource = new Map<number, number[]>();
  for (const [res, op] of grants) {
    const rid = await idByCode('/api/resources', res);
    const oid = await idByCode('/api/operations', op);
    byResource.set(rid, [...(byResource.get(rid) ?? []), oid]);
  }
  const permissions = [...byResource].map(([resourceId, operationIds]) => ({ resourceId, operationIds }));
  await call('PUT', `/api/roles/${roleId}/permissions`, await adminToken(), { permissions });
}

export async function deleteRole(roleId: number): Promise<void> {
  await call('DELETE', `/api/roles/${roleId}`, await adminToken());
}
