import { otpSignInUi } from '../../support/client-otp.js';
import { expect } from 'e2e';
import type { TestUser } from '../../support/users.js';

/** Signs in through the PL login form and waits until the app left /login (works for every role). */
export async function uiLogin(app: any, screen: any, browser: any, user: Pick<TestUser, 'email' | 'password'>): Promise<void> {
  if ((user as any).roleCode === 'CLIENT') { await otpSignInUi(app, screen, user.email, 'pl'); return; }
  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(user.email);
  await screen.getByRole('textbox', 'Hasło').fill(user.password);
  await screen.getByRole('button', 'Zaloguj się').tap();
  await expect(browser).not.toHaveURL(/\/login/);
}

/** Matches a money amount however the locale groups thousands ("1 890,00", "1890,00", NBSP, dot). */
export function moneyRe(amount: number): RegExp {
  const [int, frac] = amount.toFixed(2).split('.');
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, '[\\s\\u00a0\\u202f.]?');
  return new RegExp(`${grouped}[,.]${frac}`);
}

/** True when the page has no horizontal scrolling. */
export async function noHorizontalScroll(browser: any): Promise<boolean> {
  return browser.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
}
