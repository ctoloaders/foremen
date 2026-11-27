// Setup/teardown helpers for the FOR-07 bank specs: backend API (as ADMIN), the Fintable sandbox
// API, and a direct INSERT for a Salt Edge row (it cannot be created through the API while off).
import { execFileSync } from 'node:child_process';
import { adminToken } from './api';

const API = process.env.QA_API_URL ?? 'http://localhost:8080';
const FINTABLE = 'https://fintable.io/api/v2';
const PG_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';
const PG_USER = process.env.QA_PG_USER ?? 'foremen';
const PG_DB = process.env.QA_PG_DB ?? 'foremen';

export interface BankConnection {
  id: number;
  provider: string;
  status: string;
  displayName: string;
  providerAvailable: boolean;
  externalConnectionId: string | null;
  accountsCount: number;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export const runId = (): string => new Date().toISOString().replace(/\D/g, '').slice(0, 14);
async function api(method: string, path: string, body?: unknown): Promise<{ status: number; json: any }> {
  const token = await adminToken();
  const res = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : undefined };
}
export async function listConnections(): Promise<BankConnection[]> {
  return (await api('GET', '/api/bank/connections')).json as BankConnection[];
}
export async function fintableConnections(): Promise<BankConnection[]> {
  return (await listConnections()).filter((c) => c.provider === 'FINTABLE');
}
/** Creates a Fintable connection. Fintable allows one link a minute, so a rate-limit answer is retried. */
export async function createFintableConnection(bankCode: string, displayName: string): Promise<BankConnection> {
  for (let attempt = 0; attempt < 6; attempt++) {
    const r = await api('POST', '/api/bank/connections', { provider: 'FINTABLE', providerBankCode: bankCode, displayName });
    if (r.status === 201 || r.status === 200) return r.json as BankConnection;
    if (r.status !== 429 && r.status !== 409) throw new Error('create connection -> ' + r.status + ': ' + JSON.stringify(r.json));
    await sleep(15_000);
  }
  throw new Error('create connection: the Fintable link rate limit did not clear');
}
export async function completeConnection(id: number, params: Record<string, string>): Promise<any> {
  const r = await api('POST', '/api/bank/connections/' + id + '/complete', { params });
  if (r.status !== 200) throw new Error('complete ' + id + ' -> ' + r.status);
  return r.json;
}
/** Waits until the connection's newest sync run leaves QUEUED/RUNNING. Returns its final status. */
export async function waitForSyncRun(connectionId: number, timeoutMs = 240_000): Promise<string> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const r = await api('GET', '/api/bank/sync-runs?connectionId=' + connectionId + '&size=1&sort=id,desc');
    const rows = (Array.isArray(r.json) ? r.json : (r.json?.content ?? [])) as Array<{ status: string }>;
    if (rows.length && !['QUEUED', 'RUNNING', 'PENDING'].includes(rows[0].status)) return rows[0].status;
    await sleep(3_000);
  }
  throw new Error('sync run of connection ' + connectionId + ' did not finish in ' + timeoutMs + ' ms');
}
/** Disables (idempotent) and deletes a connection; teardown never masks a test failure. */
export async function removeConnection(id: number): Promise<void> {
  await api('POST', '/api/bank/connections/' + id + '/disable').catch(() => undefined);
  await api('DELETE', '/api/bank/connections/' + id).catch(() => undefined);
}
// Fintable sandbox helpers use the real Fintable API. The token comes from the environment only.
export const fintableToken = (): string | undefined => process.env.FINTABLE_API_TOKEN || undefined;
/** Id of the Plaid sandbox connection of the workspace, or undefined when there is none. */
export async function sandboxConnectionId(): Promise<string | undefined> {
  const token = fintableToken();
  if (!token) return undefined;
  const res = await fetch(FINTABLE + '/connections', { headers: { Authorization: 'Bearer ' + token } });
  if (!res.ok) return undefined;
  const body: any = await res.json();
  const rows: Array<{ id: string }> = Array.isArray(body) ? body : (body.data ?? []);
  return rows.map((r) => r.id).find((id) => id.startsWith('conn_plaid_'));
}
/** Recreates the sandbox after a test disabled its connection (Fintable deletes it on disable). */
export async function restoreSandbox(): Promise<void> {
  const token = fintableToken();
  if (!token || (await sandboxConnectionId())) return;
  for (let attempt = 0; attempt < 4; attempt++) {
    const init = { method: 'POST', headers: { Authorization: 'Bearer ' + token } };
    const res = await fetch(FINTABLE + '/connections/link/test_sandbox', init);
    if (res.ok) return;
    await sleep(20_000);
  }
}
function psql(sql: string): string {
  const args = ['exec', PG_CONTAINER, 'psql', '-U', PG_USER, '-d', PG_DB, '-Atc', sql];
  return execFileSync('docker', args, { encoding: 'utf8' }).trim();
}
/** Salt Edge rows cannot be created through the API while the provider is off, so insert one directly. */
export function insertSaltEdgeConnection(displayName: string): number {
  const values = ['SALT_EDGE', 'ACTIVE', displayName, 'Salt Edge QA'].map((v) => "'" + v + "'").join(',');
  const sql = 'INSERT INTO bank_connections (provider, status, display_name, bank_name) VALUES (' + values + ') RETURNING id';
  return Number(psql(sql).split('\n')[0]);
}
export function deleteSaltEdgeConnection(id: number): void {
  psql('DELETE FROM bank_connections WHERE id = ' + id + " AND provider = 'SALT_EDGE'");
}
/** Inserts a connection row directly (for UI states the provider cannot produce on demand). */
export function insertConnection(provider: string, status: string, displayName: string, providerCode = 'qa'): number {
  const values = [provider, status, displayName, 'QA bank', providerCode].map((v) => "'" + v + "'").join(',');
  const sql = 'INSERT INTO bank_connections (provider, status, display_name, bank_name, provider_code) VALUES (' + values + ') RETURNING id';
  return Number(psql(sql).split('\n')[0]);
}
export function deleteConnectionRow(id: number): void {
  psql('DELETE FROM bank_connections WHERE id = ' + id);
}
/** One create attempt; returns the new connection id, or null when the backend refused it. */
export async function tryCreateFintableConnection(bankCode: string, displayName: string): Promise<number | null> {
  const r = await api('POST', '/api/bank/connections', { provider: 'FINTABLE', providerBankCode: bankCode, displayName });
  return r.status === 201 || r.status === 200 ? (r.json.connection?.id ?? r.json.id ?? null) : null;
}
/** Ids of all connections, to find the ones a test created. */
export async function connectionIds(): Promise<Set<number>> {
  return new Set((await listConnections()).map((c) => c.id));
}
