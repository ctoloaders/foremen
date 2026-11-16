import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { loginAs } from '../../support/login.js';
import { createUser, deleteUser, type TestUser } from '../../support/users.js';

// FOR-11-00 Part B, B2 (BUYER shell). UI language PL.
// Repeatability: a fresh BUYER user per test (run-id e-mail), deleted in `finally`.
describe('FOR-11-00 B2 BUYER shell', { tags: ['for-11-00', 'ui'] }, () => {
  test('TC-B2-01 the BUYER menu has Dostawy and none of the bank / payroll / payments / estimate items; forbidden routes are blocked', async ({ app, screen }) => {
    let buyer: TestUser | undefined;
    try {
      buyer = createUser('BUYER', 'buyer');
      await loginAs(app, screen, buyer);
      const nav = screen.getByRole('navigation').first();
      await expect(screen.getByRole('link', 'Dostawy').first()).toBeVisible();
      for (const name of ['Bank', 'Wynagrodzenia', 'Płatności', 'Kosztorys']) {
        await expect(screen.getByRole('link', name)).toBeHidden();
      }
      void nav;

      await screen.getByRole('link', 'Dostawy').first().tap();
      await expect(screen.getByRole('heading', 'Brak dostępu')).toBeHidden();
      await expect(screen.getByText('Brak dostępu')).toBeHidden();

      for (const path of ['/company/payroll', '/bank/transactions']) {
        await app.open(path);
        await expect(screen.getByRole('heading', 'Brak dostępu')).toBeVisible();
      }
    } finally {
      deleteUser(buyer);
    }
  });

  test('TC-B2-02 the mobile bottom bar of a BUYER has only the Dostawy slot', async ({ app, screen, browser }) => {
    let buyer: TestUser | undefined;
    try {
      buyer = createUser('BUYER', 'buyer');
      await browser.setViewport({ width: 400, height: 800 });
      await loginAs(app, screen, buyer);
      const bar = screen.getByRole('navigation').first();
      await expect(bar.getByRole('link', 'Dostawy')).toBeVisible();
      await expect(bar.getByRole('link')).toHaveCount(1);
    } finally {
      deleteUser(buyer);
    }
  });
});

describe('FOR-11-00 B2 BUYER in the user admin', { tags: ['for-11-00', 'ui'], session: 'admin' }, () => {
  test('TC-B2-03 the role selector of the user form offers "Specjalista ds. zakupów"', async ({ app, screen }) => {
    await app.open('/users');
    await screen.getByRole('button', /Utwórz|Dodaj/).first().tap();
    const dialog = screen.getByRole('dialog');
    await expect(dialog).toBeVisible();
    const role = dialog.getByRole('combobox', 'Rola');
    await role.tap();
    // the first tap can be swallowed while the sheet animates in: tap again when the popover did not open
    if (!(await screen.getByRole('button', 'Specjalista ds. zakupów').isVisible())) await role.tap();
    await expect(screen.getByRole('button', 'Specjalista ds. zakupów')).toBeVisible();
  });
});
