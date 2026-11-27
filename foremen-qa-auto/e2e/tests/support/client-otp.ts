// Client sign-in for the e2e suites. Clients have no password (FOR-03-05, lifecycle BUG-07): the backend
// refuses a password login for a CLIENT account. A client gets a one-time code by e-mail, and the test reads
// the code from `otp_tokens` (the table stores the issued code as-is, the way the Docker stack runs).
import { execFileSync } from 'node:child_process';
const API = process.env.QA_API_URL ?? 'http://localhost:8080';

async function post(path: string, body: unknown): Promise<{ status: number; body: any }> {
  const res = await fetch(`${API}${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : undefined };
}

const PG_CONTAINER = process.env.QA_PG_CONTAINER ?? 'foremen-postgres-1';
const PG_USER = process.env.QA_PG_USER ?? 'foremen';
const PG_DB = process.env.QA_PG_DB ?? 'foremen';

/** Latest one-time code issued for an e-mail address. */
export function latestOtpCode(email: string): string {
  if (!/^[A-Za-z0-9._+@-]+$/.test(email)) throw new Error(`unsafe e-mail for SQL: ${email}`);
  const sql = `SELECT code FROM otp_tokens WHERE email = '${email}' ORDER BY id DESC LIMIT 1;`;
  const out = execFileSync(
    'docker',
    ['exec', '-i', PG_CONTAINER, 'psql', '-U', PG_USER, '-d', PG_DB, '-At', '-c', sql],
    { encoding: 'utf8' },
  ).trim();
  if (!/^\d{6}$/.test(out)) throw new Error(`no OTP code for ${email}: "${out}"`);
  return out;
}

/** Access token of a CLIENT account, obtained through the one-time-code flow. */
export async function clientOtpToken(email: string): Promise<string> {
  const req = await post('/api/auth/otp/request', { email });
  if (req.status !== 200) throw new Error(`OTP request failed: HTTP ${req.status}`);
  const code = latestOtpCode(email);
  const ver = await post('/api/auth/otp/verify', { email, code });
  if (ver.status !== 200) throw new Error(`OTP verify failed: HTTP ${ver.status}`);
  return ver.body.accessToken as string;
}

/**
 * Signs a CLIENT in through the OTP page of the UI and waits for the workspace shell (the Panel heading).
 * Labels follow the UI locale: `ru` for the RU tests, PL otherwise.
 */
export async function otpSignInUi(app: any, screen: any, email: string, locale: 'pl' | 'ru' = 'pl'): Promise<void> {
  const { expect } = await import('e2e');
  const ru = locale === 'ru';
  await app.open('/auth/otp');
  await screen.getByLabel('E-mail').fill(email);
  await screen.getByRole('button', ru ? 'Отправить код' : 'Wyślij kod').tap();
  await expect(screen.getByText(ru ? 'Введите код' : 'Wprowadź kod').first()).toBeVisible();
  const code = latestOtpCode(email);
  for (let i = 0; i < 6; i++) {
    await screen.getByLabel(ru ? `Цифра ${i + 1} из 6` : `Cyfra ${i + 1} z 6`).fill(code[i]);
  }
  await expect(screen.getByTestId('notification-bell')).toBeVisible();
}
