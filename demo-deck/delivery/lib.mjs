import { execFileSync } from 'node:child_process';
export const API = 'http://localhost:8080';
export function psql(sql, vars = {}) {
  const args = ['exec', '-i', 'foremen-postgres-1', 'psql', '-U', 'foremen', '-d', 'foremen', '-v', 'ON_ERROR_STOP=1', '-At', '-q'];
  for (const [k, v] of Object.entries(vars)) args.push('-v', `${k}=${v}`);
  args.push('-f', '-');
  return execFileSync('docker', args, { input: sql, encoding: 'utf8' }).trim();
}
export async function http(method, path, token, body, lang = 'pl') {
  const res = await fetch(API + path, {
    method,
    headers: { 'Content-Type': 'application/json', 'Accept-Language': lang, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let b = text;
  try { b = text ? JSON.parse(text) : undefined; } catch { /* plain */ }
  return { status: res.status, body: b, text };
}
export async function ok(method, path, token, body) {
  const r = await http(method, path, token, body);
  if (r.status < 200 || r.status >= 300) throw new Error(`${method} ${path} -> ${r.status}: ${r.text.slice(0, 500)}`);
  return r.body;
}
export const PASS = 'alejano123';
export async function login(email) {
  return (await ok('POST', '/api/auth/login', null, { email, password: PASS })).accessToken;
}
export const day = (n = 0) => {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
};
