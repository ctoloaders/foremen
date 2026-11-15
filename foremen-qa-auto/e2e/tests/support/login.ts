import { expect } from 'e2e';
import type { TestUser } from './users.js';

/** Signs in through the login form (PL UI) and waits for the Panel grid. */
export async function loginAs(app: any, screen: any, user: Pick<TestUser, 'email' | 'password'>): Promise<void> {
  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(user.email);
  await screen.getByRole('textbox', 'Hasło').fill(user.password);
  await screen.getByRole('button', 'Zaloguj się').tap();
  await expect(screen.getByTestId('dashboard-grid')).toBeVisible();
}

/** Date helpers for the date-filter expectations (dd.MM.yyyy, as the filter renders them). */
export const fmt = (d: Date): string =>
  `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`;
