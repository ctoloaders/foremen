import { beforeEach, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { fmt } from '../support/login.js';

// FOR-10 UI B2 (global date filter). Source: .kiro/specs/FOR-10-dashboard/test-cases.md.
// Read-only: the filter state lives in the browser session, nothing is persisted server-side.
describe('FOR-10 date filter', { tags: ['for-10', 'date-filter'], session: 'admin' }, () => {
  beforeEach(async ({ app, screen }) => {
    await app.open('/');
    await expect(screen.getByTestId('dashboard-grid')).toBeVisible();
  });

  const pick = async (screen: any, period: string, unit: string) => {
    await screen.getByTestId('dashboard-quick-period').tap();
    await screen.getByRole('option', period).tap();
    await screen.getByTestId('dashboard-quick-unit').tap();
    await screen.getByRole('option', unit).tap();
  };

  test('TC-B2-02 quick range: previous month', async ({ screen }) => {
    const n = new Date();
    await pick(screen, 'Poprzedni', 'Miesiąc');
    await expect(screen.getByRole('button', 'Od')).toHaveText(fmt(new Date(n.getFullYear(), n.getMonth() - 1, 1)));
    await expect(screen.getByRole('button', 'Do')).toHaveText(fmt(new Date(n.getFullYear(), n.getMonth(), 0)));
  });

  test('TC-B2-02 quick range: week before last is Monday to Sunday', async ({ screen }) => {
    const n = new Date();
    const mondayOffset = (n.getDay() + 6) % 7;
    const monday = new Date(n.getFullYear(), n.getMonth(), n.getDate() - mondayOffset - 14);
    const sunday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6);
    await pick(screen, 'Przedostatni', 'Tydzień');
    await expect(screen.getByRole('button', 'Od')).toHaveText(fmt(monday));
    await expect(screen.getByRole('button', 'Do')).toHaveText(fmt(sunday));
  });

  test('TC-B2-02 quick range: current day and current year', async ({ screen }) => {
    const n = new Date();
    await pick(screen, 'Bieżący', 'Dzień');
    await expect(screen.getByRole('button', 'Od')).toHaveText(fmt(n));
    await expect(screen.getByRole('button', 'Do')).toHaveText(fmt(n));

    await pick(screen, 'Bieżący', 'Rok');
    await expect(screen.getByRole('button', 'Od')).toHaveText(`01.01.${n.getFullYear()}`);
    await expect(screen.getByRole('button', 'Do')).toHaveText(`31.12.${n.getFullYear()}`);
  });

  test('TC-B2-02 a new range makes widgets refetch with the new dates', async ({ screen, browser }) => {
    const n = new Date();
    const from = new Date(n.getFullYear(), n.getMonth() - 1, 1);
    const iso = `${from.getFullYear()}-${String(from.getMonth() + 1).padStart(2, '0')}-01`;
    const response = browser.waitForResponse(new RegExp(`/api/dashboard/.*dateFrom=${iso}`));
    await pick(screen, 'Poprzedni', 'Miesiąc');
    expect((await response).url).toContain(`dateFrom=${iso}`);
  });

  test('TC-B2-03 the range survives a reload', async ({ screen, browser }) => {
    await pick(screen, 'Poprzedni', 'Miesiąc');
    const from = (await screen.getByRole('button', 'Od').textContent()) ?? '';
    await browser.reload();
    await expect(screen.getByTestId('dashboard-grid')).toBeVisible();
    await expect(screen.getByRole('button', 'Od')).toHaveText(from);
  });

  test('TC-B2-04 an inverted range shows an error and sends no request', async ({ screen, browser }) => {
    // Calendar day buttons are named in English, e.g. "Friday, November 20th, 2026".
    const month = new Date().toLocaleString('en-US', { month: 'long' });
    const day = (n: number) => new RegExp(`${month} ${n}(st|nd|rd|th),`);

    // Valid start: the 20th (the end of the range is still the end of the month).
    await screen.getByRole('button', 'Od').tap();
    await screen.getByRole('button', day(20)).tap();

    let dataRequests = 0;
    await browser.route('**/api/dashboard/**', async (route: any) => {
      dataRequests += 1;
      await route.continue();
    });

    // Invalid end: the 10th is before the start.
    await screen.getByRole('button', 'Do').tap();
    await screen.getByRole('button', day(10)).tap();

    await expect(screen.getByTestId('dashboard-date-error')).toHaveText('Nieprawidłowy zakres dat');
    expect(dataRequests).toBe(0);
  });

  test('TC-B2-05 a widget independent of the date filter says so', async ({ screen }) => {
    await expect(
      screen.getByTestId('dashboard-grid').getByText('Nie zależy od filtra dat').first(),
    ).toBeVisible();
  });
});
