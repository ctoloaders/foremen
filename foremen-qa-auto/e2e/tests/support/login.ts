import { expect } from 'e2e';
import type { TestUser } from './users.js';
import { latestOtpCode } from './client-otp.js';

/**
 * Signs in through the UI and waits for the Panel grid. A CLIENT has no password (FOR-03-05), so the client
 * path requests a one-time code on the OTP page and reads it from the database.
 */
export async function loginAs(app: any, screen: any, user: Pick<TestUser, 'email' | 'password'> & { roleCode?: string }): Promise<void> {
  if (user.roleCode === 'CLIENT') {
    await app.open('/auth/otp');
    await screen.getByLabel('E-mail').fill(user.email);
    await screen.getByRole('button', 'Wyślij kod').tap();
    await expect(screen.getByText('Wprowadź kod').first()).toBeVisible();
    const code = latestOtpCode(user.email);
    for (let i = 0; i < 6; i++) await screen.getByLabel(`Cyfra ${i + 1} z 6`).fill(code[i]);
    await expect(screen.getByRole('heading', 'Panel').first()).toBeVisible();
    return;
  }
  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(user.email);
  await screen.getByRole('textbox', 'Hasło').fill(user.password);
  await screen.getByRole('button', 'Zaloguj się').tap();
  await expect(screen.getByTestId('dashboard-grid')).toBeVisible();
}

/** Date helpers for the date-filter expectations (dd.MM.yyyy, as the filter renders them). */
export const fmt = (d: Date): string =>
  `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`;
